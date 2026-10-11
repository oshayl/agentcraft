// Per-agent capabilities: what opening the network, pushes and the sandbox really changes, and
// what each flag still leaves shut. The ones that must stay shut unless the matching capability
// is granted:
//   - no git transport at all without `network`, and no push without `push`
//   - no force push, branch delete, --mirror/--all or push to a base branch without `force`
//   - no sandbox escape without `full`
//   - never signing with the user's key, whatever is granted
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { CAPABILITY_NAMES, CLOSED, describeCapabilities, resolveCapabilities, withRepoCapabilities } from '../src/capabilities.js';
import { CODEX_SANDBOX, CODEX_WEB_SEARCH, codexWebSearch, sandboxMode } from '../src/agents/codex/engine.js';
import { capsFrom } from '../src/config.js';
import { gitSafetyEnv, withGitSafety } from '../src/gitsafety.js';
import { classifyToolUse, type PolicyContext } from '../src/policy.js';
import { git, gitOut } from '../src/util/git.js';
import { runShell } from '../src/util/proc.js';
import { demoRepo, makeForeman, rmrf, tempDir } from './helpers.js';
import { CodexEngine } from '../src/agents/codex/engine.js';
import { TeamBackend } from '../src/agents/team.js';
import type { ClientMessage, Outbound } from '../src/protocol.js';

describe('resolveCapabilities', () => {
  it('is closed by default', () => {
    expect(resolveCapabilities(undefined, 'worker')).toEqual(CLOSED);
    expect(resolveCapabilities({}, 'lead')).toEqual(CLOSED);
  });

  it('applies the role, then the agent', () => {
    const cfg = { lead: { network: true, mcp: true }, worker: { mcp: true }, byAgent: { juniper: { network: true } } };
    expect(resolveCapabilities(cfg, 'lead')).toMatchObject({ network: true, mcp: true, push: false });
    expect(resolveCapabilities(cfg, 'worker', 'kit')).toMatchObject({ network: false, mcp: true });
    expect(resolveCapabilities(cfg, 'worker', 'juniper')).toMatchObject({ network: true, mcp: true });
  });

  it('push implies network: git cannot push over a transport it may not use', () => {
    expect(resolveCapabilities({ lead: { push: true } }, 'lead')).toMatchObject({ push: true, network: true });
  });

  it('describes what is on', () => {
    expect(describeCapabilities(CLOSED)).toBe('closed');
    expect(describeCapabilities(resolveCapabilities({ lead: { push: true } }, 'lead'))).toContain('push');
  });
});

describe('git safety env by capability', () => {
  const keys = (env: Record<string, string>) => {
    const out: Record<string, string> = {};
    const n = Number(env.GIT_CONFIG_COUNT ?? 0);
    for (let i = 0; i < n; i++) out[env[`GIT_CONFIG_KEY_${i}`]!] = env[`GIT_CONFIG_VALUE_${i}`]!;
    return out;
  };

  it('closed: no transport at all, and pushes rewritten to a dead URL', () => {
    const env = gitSafetyEnv({});
    expect(env.GIT_ALLOW_PROTOCOL).toBe('agentcraft-none');
    const k = keys(env);
    expect(k['protocol.allow']).toBe('never');
    expect(Object.keys(k).some((x) => x.endsWith('.pushInsteadOf'))).toBe(true);
  });

  it('network: transports work, but pushes are still rewritten away', () => {
    const env = gitSafetyEnv({}, { network: true });
    expect(env.GIT_ALLOW_PROTOCOL).toBeUndefined();
    const k = keys(env);
    expect(k['protocol.allow']).toBeUndefined();
    expect(Object.keys(k).some((x) => x.endsWith('.pushInsteadOf'))).toBe(true);
  });

  it('network + push: pushes reach the remote, authenticated by the user gh login', () => {
    const env = gitSafetyEnv({}, { network: true, push: true, credentialHelper: '!gh auth git-credential' });
    const k = keys(env);
    expect(Object.keys(k).some((x) => x.endsWith('.pushInsteadOf'))).toBe(false);
    expect(k['credential.helper']).toBe('!gh auth git-credential');
  });

  it('never signs with the user key, whatever the capabilities', () => {
    for (const opts of [{}, { network: true }, { network: true, push: true }]) {
      const k = keys(gitSafetyEnv({}, opts));
      expect(k['commit.gpgsign']).toBe('false');
      expect(k['gpg.program']).toBe('agentcraft-signing-disabled');
    }
  });
});

describe('git safety env, against a real remote', () => {
  let root: string;
  let remote: string;
  let work: string;
  const refs = async () => (await gitOut(remote, ['for-each-ref', '--format=%(refname)'])).split('\n').filter(Boolean);
  const sh = (cmd: string, env: NodeJS.ProcessEnv) => runShell(cmd, { cwd: work, env, timeoutMs: 60_000 });

  beforeAll(async () => {
    root = tempDir('ac-caps-');
    remote = path.join(root, 'remote.git');
    work = path.join(root, 'work');
    await git(root, ['init', '-q', '--bare', remote]);
    await git(root, ['init', '-q', '-b', 'main', work]);
    fs.writeFileSync(path.join(work, 'a.txt'), 'a\n');
    await git(work, ['add', '-A']);
    await git(work, ['-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'init']);
    await git(work, ['remote', 'add', 'origin', remote.replace(/\\/g, '/')]);
  });
  afterAll(() => rmrf(root));

  it('network alone does not let a push through', async () => {
    const before = await refs();
    const env = withGitSafety(process.env, {}, { network: true });
    expect((await sh('git push -q origin HEAD:refs/heads/net-only', env)).code).not.toBe(0);
    expect(await refs()).toEqual(before);
  });

  it('network + push lets the agent publish its branch', async () => {
    const env = withGitSafety(process.env, {}, { network: true, push: true });
    const r = await sh('git push -q origin HEAD:refs/heads/agent-branch', env);
    expect(r.code, r.stderr).toBe(0);
    expect(await refs()).toContain('refs/heads/agent-branch');
  });
});

describe('policy: git push by capability', () => {
  const wt = path.join(tempDir('ac-caps-policy-'), 'worktrees', 'demo', 'kit-t2');
  const base: PolicyContext = { role: 'worker', cwd: wt, protectedBranches: ['main', 'release'] };
  const closed: PolicyContext = base;
  const pushing: PolicyContext = { ...base, capabilities: { ...CLOSED, network: true, push: true } };
  const verdict = (command: string, ctx: PolicyContext) => classifyToolUse('Bash', { command }, ctx).action;

  it('refuses every push without the capability', () => {
    for (const c of ['git push', 'git push origin HEAD', 'git push origin agentcraft/kit/t2']) {
      expect(verdict(c, closed), c).toBe('deny');
    }
  });

  it('asks for an ordinary push once the capability is on', () => {
    // an ask, not a silent allow: visible in `ask` mode, skipped in `auto` mode
    expect(verdict('git push origin agentcraft/kit/t2', pushing)).toBe('ask');
    expect(verdict('git push', pushing)).toBe('ask');
    expect(verdict('git push -u origin feature/x', pushing)).toBe('ask');
  });

  it('still refuses what would rewrite or remove the user work', () => {
    for (const c of [
      'git push --force origin feature/x',
      'git push -f origin feature/x',
      'git push --force-with-lease origin feature/x',
      'git push origin +feature/x',
      'git push --delete origin feature/x',
      'git push --mirror origin',
      'git push --all origin',
      'git push origin :feature/x',
    ]) {
      expect(verdict(c, pushing), c).toBe('deny');
    }
  });

  it('still refuses a push to a protected branch: that is what merge decisions are for', () => {
    for (const c of ['git push origin main', 'git push origin HEAD:main', 'git push origin release', 'git push origin feature/x:refs/heads/main', 'git push origin master']) {
      expect(verdict(c, pushing), c).toBe('deny');
    }
  });

  it('still refuses a push it cannot read', () => {
    expect(verdict('git push origin $BRANCH', pushing)).toBe('deny');
    expect(verdict('git $SUB origin x', pushing)).toBe('deny');
  });

  it('leaves the other remote-writing subcommands refused', () => {
    expect(verdict('git send-pack origin HEAD', pushing)).toBe('deny');
  });
});

describe('what the Codex thread config says', () => {
  // A wrong value here does not just lose a feature: `thread/start` fails to load the whole
  // configuration, so the agent cannot run. That happened in real use with web_search:'enabled'.
  it('only ever asks for a web_search variant Codex accepts', () => {
    expect(CODEX_WEB_SEARCH).toEqual(['disabled', 'cached', 'indexed', 'live']);
    expect(CODEX_WEB_SEARCH).toContain(codexWebSearch(true));
    expect(CODEX_WEB_SEARCH).toContain(codexWebSearch(false));
    expect(CODEX_WEB_SEARCH).toContain(codexWebSearch(undefined));
  });

  it('turns search on for the web capability and off without it', () => {
    expect(codexWebSearch(true)).toBe('live');
    expect(codexWebSearch(false)).toBe('disabled');
    expect(codexWebSearch(undefined)).toBe('disabled');
  });
});

describe('the write and force capabilities', () => {
  const wt = path.join(tempDir('ac-caps-write-'), 'worktrees', 'demo', 'kit-t2');
  const leadClosed: PolicyContext = { role: 'lead', cwd: wt, tempDirs: [] };
  const leadWrite: PolicyContext = { ...leadClosed, capabilities: { ...CLOSED, write: true } };
  const file = path.join(wt, 'src', 'app.ts');

  it('keeps the lead read-only by default', () => {
    expect(classifyToolUse('Edit', { file_path: file, old_string: 'a', new_string: 'b' }, leadClosed).action).toBe('deny');
  });

  it('lets the lead edit and write once it has the write capability', () => {
    expect(classifyToolUse('Edit', { file_path: file, old_string: 'a', new_string: 'b' }, leadWrite).action).toBe('allow');
    expect(classifyToolUse('Write', { file_path: file, content: 'x' }, leadWrite).action).toBe('allow');
  });

  it('force opens the destructive git the user terminal already has', () => {
    const base: PolicyContext = { role: 'worker', cwd: wt, protectedBranches: ['main'] };
    const pushing: PolicyContext = { ...base, capabilities: { ...CLOSED, network: true, push: true } };
    const forcing: PolicyContext = { ...base, capabilities: { ...CLOSED, network: true, push: true, force: true } };
    for (const c of ['git push --force origin feature/x', 'git push origin main', 'git push --delete origin feature/x']) {
      expect(classifyToolUse('Bash', { command: c }, pushing).action, `without force: ${c}`).toBe('deny');
      expect(classifyToolUse('Bash', { command: c }, forcing).action, `with force: ${c}`).not.toBe('deny');
    }
  });

  it('force implies push and network: they are the same reach', () => {
    expect(resolveCapabilities({ lead: { force: true } }, 'lead')).toMatchObject({ force: true, push: true, network: true });
  });

  it('"all" in config means every capability, including ones added later', () => {
    // Through the real parser, not a hand-written list: "all" has to keep meaning all.
    const every = resolveCapabilities({ lead: capsFrom('all', 'lead') }, 'lead');
    expect(Object.values(every).every(Boolean)).toBe(true);
    expect(Object.keys(every).sort()).toEqual([...CAPABILITY_NAMES].sort());
  });
});

// Full access is the one capability with nothing underneath it: the Codex sandbox is what keeps
// an agent inside the worktree at all, so these tests pin both the mode name (an unknown one
// fails thread/start outright, the way `web_search: 'enabled'` once did) and the implications.
describe('the full capability', () => {
  it('is a sandbox mode Codex actually knows', () => {
    expect(CODEX_SANDBOX).toContain('danger-full-access');
  });

  it('turns the sandbox off for both roles', () => {
    const full = resolveCapabilities({ lead: { full: true }, worker: { full: true } }, 'lead');
    expect(sandboxMode('lead', full)).toBe('danger-full-access');
    expect(sandboxMode('worker', full)).toBe('danger-full-access');
  });

  it('leaves the sandbox on without it', () => {
    expect(sandboxMode('lead', CLOSED)).toBe('read-only');
    expect(sandboxMode('lead', { ...CLOSED, network: true })).toBe('workspace-write');
    expect(sandboxMode('lead', { ...CLOSED, write: true, push: true, force: true })).toBe('workspace-write');
    expect(sandboxMode('worker', CLOSED)).toBe('workspace-write');
  });

  it('implies write, force, push and network: a sandbox was all those were behind', () => {
    expect(resolveCapabilities({ worker: { full: true } }, 'worker')).toEqual({ ...CLOSED, full: true, write: true, force: true, push: true, network: true });
  });

  it('lets the lead write and push, since the policy is the only check left', () => {
    const ctx = (capabilities: ReturnType<typeof resolveCapabilities>): PolicyContext => ({ role: 'lead', cwd: process.cwd(), capabilities, protectedBranches: ['main'] });
    const full = ctx(resolveCapabilities({ lead: { full: true } }, 'lead'));
    expect(classifyToolUse('Edit', { file_path: path.join(process.cwd(), 'src/x.ts') }, full).action).toBe('allow');
    expect(classifyToolUse('Bash', { command: 'git push origin main' }, full).action).not.toBe('deny');
  });
});

describe('per-repository capabilities', () => {
  const cfg = { lead: { mcp: true }, byAgent: { marlow: { web: true } }, byRepo: { trusted: { lead: { full: true } } } };

  it('the repo wins over the role and the agent: it is the runtime toggle', () => {
    expect(resolveCapabilities(cfg, 'lead', 'marlow', 'trusted').full).toBe(true);
    expect(resolveCapabilities(cfg, 'lead', 'marlow', 'other').full).toBe(false);
    expect(resolveCapabilities(cfg, 'lead', 'marlow', 'other').web).toBe(true);
  });

  it('only touches the role it was set for', () => {
    expect(resolveCapabilities(cfg, 'worker', 'kit', 'trusted')).toEqual(CLOSED);
  });

  it('merges a runtime override over the config file, per role', () => {
    const merged = withRepoCapabilities(cfg, { trusted: { worker: { mcp: true } }, extra: { lead: { network: true } } });
    expect(resolveCapabilities(merged, 'lead', 'marlow', 'trusted').full).toBe(true);
    expect(resolveCapabilities(merged, 'worker', 'kit', 'trusted').mcp).toBe(true);
    expect(resolveCapabilities(merged, 'lead', 'marlow', 'extra').network).toBe(true);
    expect(resolveCapabilities(merged, 'lead', 'marlow', 'extra').full).toBe(false);
  });

  it('keeps the repos from the config file when there is no runtime override', () => {
    expect(withRepoCapabilities(cfg, {})).toBe(cfg);
    expect(withRepoCapabilities(cfg, undefined).byRepo?.trusted?.lead?.full).toBe(true);
  });

  it('parses a repo entry as one list for both roles, or per role', () => {
    expect(capsFrom('full', 'r')).toEqual({ full: true });
    expect(capsFrom('none', 'r')).toEqual(CLOSED);
    expect(() => capsFrom('sudo', 'r')).toThrow(/unknown r capability "sudo"/);
    expect(CAPABILITY_NAMES).toContain('full');
  });
});

// The runtime toggle the user actually reaches for: `/caps <repo> full` in the console, which is
// this message. It has to persist, win over the config file, and only touch the repo it names.
describe('caps.set', () => {
  async function harness(capabilities: Parameters<typeof resolveCapabilities>[0]) {
    const home = tempDir();
    const repoPath = await demoRepo();
    const h = makeForeman(home, ['--backend', 'sim']);
    const engine = new CodexEngine(h.fm, h.cfg.codex);
    const backend = new TeamBackend(h.fm, h.cfg.claude, { name: 'codex', engines: { lead: engine, worker: engine }, capabilities });
    h.fm['backend'] = backend;
    const repo = await h.fm.repos.add(repoPath);
    const send = async (msg: Partial<Extract<ClientMessage, { type: 'caps.set' }>>): Promise<Extract<Outbound, { type: 'ack' }>> => {
      const replies: Outbound[] = [];
      await h.fm.handle({ v: 1, id: 'x', type: 'caps.set', ...msg } as ClientMessage, (m) => replies.push(m));
      return replies.find((m) => m.type === 'ack') as Extract<Outbound, { type: 'ack' }>;
    };
    const lines = (a: Extract<Outbound, { type: 'ack' }>) => (a.result?.lines as string[]) ?? [];
    return { h, backend, repo, send, lines, cleanup: async () => { await h.fm.close(); rmrf(home); rmrf(path.dirname(repoPath)); } };
  }

  it('reports what each repo has now, and changes nothing', async () => {
    const t = await harness({ lead: { mcp: true }, worker: {} });
    try {
      const a = await t.send({});
      expect(a.ok).toBe(true);
      expect(t.lines(a)[0]).toMatch(/demo-app: lead mcp \| workers closed/);
      expect(t.h.fm.repoCapabilities()).toEqual({});
    } finally {
      await t.cleanup();
    }
  });

  it('grants full access to one lead, for one repo, and persists it', async () => {
    const t = await harness({ lead: { mcp: true }, worker: {} });
    try {
      const a = await t.send({ repoId: t.repo.id, role: 'lead', caps: 'full' });
      expect(a.ok).toBe(true);
      // `mcp` stays: a repo override patches what the role already has, like a per-agent one
      expect(t.lines(a)[0]).toMatch(/The lead in demo-app: lead network, push, mcp, write, force, full/);
      expect(t.backend.capsFor('marlow', 'lead', t.repo.id).full).toBe(true);
      // the workers were not named, so they are untouched
      expect(t.backend.capsFor('kit', 'worker', t.repo.id)).toEqual(CLOSED);
      // and another repo is untouched: the grant is per repo, which is the whole point
      expect(t.backend.capsFor('marlow', 'lead', 'elsewhere').full).toBe(false);
      expect(t.h.fm.store.data.repoCaps[t.repo.id]?.lead).toEqual({ full: true });
    } finally {
      await t.cleanup();
    }
  });

  it('wins over the config file, and "default" gives it back', async () => {
    const t = await harness({ lead: capsFrom('all', 'lead'), worker: {} });
    try {
      expect(t.backend.capsFor('marlow', 'lead', t.repo.id).full).toBe(true);
      await t.send({ repoId: t.repo.id, caps: 'none' });
      expect(t.backend.capsFor('marlow', 'lead', t.repo.id)).toEqual(CLOSED);
      const back = await t.send({ repoId: t.repo.id, caps: 'default' });
      expect(t.lines(back)[0]).toMatch(/back to the config file/);
      expect(t.backend.capsFor('marlow', 'lead', t.repo.id).full).toBe(true);
      expect(t.h.fm.repoCapabilities()).toEqual({});
    } finally {
      await t.cleanup();
    }
  });

  it('asks which repo rather than granting everywhere, and refuses a name it does not know', async () => {
    const t = await harness({});
    try {
      const vague = await t.send({ caps: 'full' });
      expect(t.backend.capsFor('marlow', 'lead', t.repo.id).full).toBe(false);
      expect(t.lines(vague).at(-1)).toMatch(/Name the repo to change/);
      const bad = await t.send({ repoId: t.repo.id, caps: 'sudo' });
      expect(bad.ok).toBe(false);
      // a typo is a user error, not an "internal error: ..."
      expect(bad.error).toMatch(/^unknown repo demo-app capability "sudo"/);
    } finally {
      await t.cleanup();
    }
  });
});
