// Per-agent capabilities: what opening the network and pushes really changes, and what stays
// shut in every combination. The guarantees that must survive a fully opened agent are:
//   - no force push, no branch delete, no --mirror/--all
//   - no push to a repository's base branch (that is what merge decisions are for)
//   - no signing with the user's key
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { CLOSED, describeCapabilities, resolveCapabilities } from '../src/capabilities.js';
import { CODEX_WEB_SEARCH, codexWebSearch } from '../src/agents/codex/engine.js';
import { gitSafetyEnv, withGitSafety } from '../src/gitsafety.js';
import { classifyToolUse, type PolicyContext } from '../src/policy.js';
import { git, gitOut } from '../src/util/git.js';
import { runShell } from '../src/util/proc.js';
import { rmrf, tempDir } from './helpers.js';

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

  it('"all" in config means every capability', () => {
    const every = resolveCapabilities({ lead: { network: true, push: true, mcp: true, web: true, write: true, force: true } }, 'lead');
    expect(Object.values(every).every(Boolean)).toBe(true);
  });
});
