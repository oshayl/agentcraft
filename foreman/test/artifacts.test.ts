// Artifacts: the one note a goal leaves behind, and the way a later goal is pointed at it.
// What matters here is that the open items survive as data (not prose the next model has to
// re-read), and that naming an artifact in a goal actually puts it in front of the lead.
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { CodexEngine } from '../src/agents/codex/engine.js';
import { TeamBackend } from '../src/agents/team.js';
import { TOOL_NAMES } from '../src/agents/tools.js';
import type { Engine, TurnSpec, TurnStats } from '../src/agents/engine.js';
import { agentTools } from '../src/agents/tools.js';
import { artifactBrief, artifactItems, artifactLine, artifactsNamedIn, mentionsIn, notesBrief, openItems, renderArtifact, shortId } from '../src/artifacts.js';
import { ARTIFACT_SCOPE } from '../src/memory.js';
import type { Goal, MemoryEntry } from '../src/protocol.js';
import { demoRepo, makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

let h: Harness;
let home: string;
let repoPath: string;

beforeAll(async () => {
  home = tempDir();
  repoPath = await demoRepo();
  h = makeForeman(home, ['--backend', 'sim']);
  await h.fm.repos.add(repoPath);
});
afterAll(async () => {
  await h.fm.close();
  rmrf(home);
  rmrf(path.dirname(repoPath));
});

const entry = (id: string, title: string, body: string): MemoryEntry => ({ id, scope: ARTIFACT_SCOPE, title, body, updated: 1 });

describe('rendering an artifact', () => {
  const body = renderArtifact({
    title: 'The Movement: code review',
    summary: 'main is not releasable yet.\nThe README describes routes that no longer exist.',
    openItems: [
      { text: 'Rewrite the README routes section', evidence: 'README:37, app/page.tsx:14', owner: 'Wren' },
      { text: 'Measure the 5000-node graph cap', owner: 'Kit' },
    ],
    done: ['Lock/manifest consistency checked'],
    notes: 'No hosted build was run.',
    goal: { id: 'g1', text: 'review The Movement', progress: 1, status: 'done', createdAt: 1, updatedAt: 2 } as Goal,
  });

  it('puts the open work in checkboxes, so it is data and not prose', () => {
    const e = entry(`${ARTIFACT_SCOPE}/movement`, 'The Movement: code review', body);
    const items = artifactItems(e);
    expect(items).toHaveLength(3);
    expect(openItems(e).map((i) => i.n)).toEqual([1, 2]);
    expect(items[0]!.text).toContain('Rewrite the README routes section');
    expect(items[0]!.text).toContain('README:37');
    expect(items[0]!.text).toContain('found by Wren');
    expect(items[2]).toMatchObject({ done: true, n: 3 });
  });

  it('carries the goal and the free-form parts', () => {
    expect(body).toContain('> Goal g1: review The Movement');
    expect(body).toContain('## Notes');
    expect(body).toContain('No hosted build was run.');
  });

  it('says plainly when nothing is open', () => {
    const empty = renderArtifact({ title: 't', summary: 's', openItems: [], done: [] });
    expect(empty).toContain('_Nothing open._');
    expect(artifactLine(entry(`${ARTIFACT_SCOPE}/t`, 'All clear', empty))).toBe('t · All clear — no items');
  });

  it('counts what is left in one line', () => {
    expect(artifactLine(entry(`${ARTIFACT_SCOPE}/movement`, 'The Movement: code review', body))).toBe('movement · The Movement: code review — 2 open of 3');
  });

  // A user ticking a box in their editor is the same act as the lead ticking it here: the file
  // is the record, so an item that comes back `- [x]` must stop counting as open.
  it('respects a box ticked by hand in the file', () => {
    const edited = body.replace('- [ ] Measure', '- [x] Measure');
    expect(openItems(entry(`${ARTIFACT_SCOPE}/m`, 'm', edited))).toHaveLength(1);
  });
});

describe('naming an artifact in a prompt', () => {
  const all = [entry(`${ARTIFACT_SCOPE}/movement-review`, 'The Movement: code review', renderArtifact({ title: 'x', summary: 's', openItems: [{ text: 'Fix the README' }], done: [] }))];

  it('matches the id, the slug and the file name', () => {
    for (const text of ['read artifacts/movement-review and do the open items', 'read movement-review and execute everything', 'open movement-review.md first']) {
      expect(artifactsNamedIn(text, all), text).toHaveLength(1);
    }
  });

  it('matches the title as written', () => {
    expect(artifactsNamedIn('work through The Movement: code review', all)).toHaveLength(1);
  });

  // Fuzzy matching here would drag an unrelated artifact into a goal that never asked for it,
  // and the lead would plan from it. Nothing named = nothing resolved.
  it('does not match a goal that names nothing', () => {
    expect(artifactsNamedIn('review the movement of the sidebar', all)).toHaveLength(0);
    expect(artifactsNamedIn('amovement-reviewing', all)).toHaveLength(0);
    expect(artifactsNamedIn('', all)).toHaveLength(0);
  });

  it('hands the lead the file path and the numbered open items', () => {
    const brief = artifactBrief(all, (id) => `/tmp/${id}.md`);
    expect(brief).toContain('/tmp/artifacts/movement-review.md');
    expect(brief).toContain('read_memory id "artifacts/movement-review"');
    expect(brief).toContain('open items (1 of 1)');
    expect(brief).toContain('1. Fix the README');
    expect(artifactBrief([], () => '')).toBe('');
  });
});

describe('write_artifact', () => {
  it('saves a readable file the user can open, and shows up in the library', async () => {
    const hooks = { onReview() {}, onChangesRequested() {}, onTasksChanged() {}, onMergeRequested() {}, onWaiting() {} };
    const tools = agentTools(h.fm, 'marlow', 'lead', hooks);
    const write = tools.find((t) => t.name === 'write_artifact');
    expect(write, 'the lead has write_artifact').toBeTruthy();
    expect(agentTools(h.fm, 'kit', 'worker', hooks).some((t) => t.name === 'write_artifact'), 'workers do not').toBe(false);

    const res = await write!.handler({
      title: 'Review: the demo app',
      summary: 'Two things need doing.',
      open_items: [{ text: 'Add a test for tags', evidence: 'src/tags.ts:12' }, { text: 'Document the CLI flag' }],
      done: ['The parser is correct'],
    });
    expect(res.isError).toBeFalsy();

    const saved = h.fm.memory.artifacts();
    expect(saved).toHaveLength(1);
    expect(saved[0]!.id).toBe(`${ARTIFACT_SCOPE}/review-the-demo-app`);
    expect(openItems(saved[0]!)).toHaveLength(2);
    // a real file in the memory directory, not only an entry in the snapshot
    const file = h.fm.memory.pathOf(saved[0]!.id);
    expect(fs.existsSync(file), file).toBe(true);
    expect(fs.readFileSync(file, 'utf8')).toContain('- [ ] Add a test for tags — src/tags.ts:12');
    // everyone can read it, unlike a private note
    expect(h.fm.memory.visibleTo('kit').some((e) => e.id === saved[0]!.id)).toBe(true);
    const read = await agentTools(h.fm, 'kit', 'worker', hooks).find((t) => t.name === 'read_memory')!.handler({ id: shortId(saved[0]!) });
    expect(read.content[0]!.text).toContain('Add a test for tags');
  });

  it('replaces an artifact instead of adding another when given its id', async () => {
    const tools = agentTools(h.fm, 'marlow', 'lead', { onReview() {}, onChangesRequested() {}, onTasksChanged() {}, onMergeRequested() {}, onWaiting() {} });
    const write = tools.find((t) => t.name === 'write_artifact')!;
    await write.handler({ title: 'Review: the demo app', summary: 'Now only one thing.', open_items: [{ text: 'Document the CLI flag' }], done: [], id: `${ARTIFACT_SCOPE}/review-the-demo-app` });
    expect(h.fm.memory.artifacts()).toHaveLength(1);
    expect(openItems(h.fm.memory.artifacts()[0]!)).toHaveLength(1);
  });
});

describe('the goal report turn', () => {
  it('queues one last turn for the lead when a goal finishes, with what the team wrote', async () => {
    const isolatedHome = tempDir();
    const isolatedRepo = await demoRepo();
    const iso = makeForeman(isolatedHome, ['--backend', 'sim']);
    try {
      const repo = await iso.fm.repos.add(isolatedRepo);
      const engine = new CodexEngine(iso.fm, iso.cfg.codex);
      const backend = new TeamBackend(iso.fm, iso.cfg.claude, { name: 'codex', engines: { lead: engine, worker: engine } });
      iso.fm['backend'] = backend;
      const goal = await iso.fm.submitGoal('review the demo app', repo.id).catch(() => undefined);
      const g = goal ?? iso.fm.goals()[0]!;
      const task = iso.fm.tasks.create({ title: 'review it', createdBy: 'marlow', repoId: repo.id, assignee: 'kit', goalId: g.id });
      iso.fm.setGoal(g.id, { status: 'active' });
      iso.fm.memory.write({ scope: 'shared', title: 'Review: what Kit found', body: 'The README is stale at line 37.', author: 'kit' });

      backend['queues'].clear();
      iso.fm.tasks.setStatus(task.id, 'done', { force: true, summary: 'read it end to end' });
      await new Promise((r) => setTimeout(r, 0));
      const queued = backend['queues'].get('marlow') ?? [];
      const report = queued.find((j: { kind: string }) => j.kind === 'report');
      if (!report) throw new Error(`no report job queued (${queued.map((j: { kind: string }) => j.kind).join(', ') || 'queue empty'})`);
      expect(report.sessionKey).toBe(`marlow:${g.id}`);
      expect(report.prompt).toContain('write_artifact');
      expect(report.prompt).toContain('The README is stale at line 37.');
      expect(report.prompt).toContain('read it end to end');

      // and only once, however often the goal is recomputed as complete
      backend.onGoalComplete(iso.fm.goal(g.id)!);
      expect((backend['queues'].get('marlow') ?? []).filter((j: { kind: string }) => j.kind === 'report')).toHaveLength(1);
    } finally {
      await iso.fm.close();
      rmrf(isolatedHome);
      rmrf(path.dirname(isolatedRepo));
    }
  });

  it('says nothing when the goal had no tasks at all', async () => {
    const isolatedHome = tempDir();
    const iso = makeForeman(isolatedHome, ['--backend', 'sim']);
    try {
      const engine = new CodexEngine(iso.fm, iso.cfg.codex);
      const backend = new TeamBackend(iso.fm, iso.cfg.claude, { name: 'codex', engines: { lead: engine, worker: engine } });
      iso.fm['backend'] = backend;
      backend.onGoalComplete({ id: 'g9', text: 'nothing happened', progress: 1, status: 'done', createdAt: 1, updatedAt: 2 });
      expect(backend['queues'].get('marlow') ?? []).toHaveLength(0);
    } finally {
      await iso.fm.close();
      rmrf(isolatedHome);
    }
  });
});

// A resumed Codex thread answers with the tools it was created with: a tool added by a later
// AgentCraft version is simply not in its registry, and the agent reports it as unavailable
// ("write_artifact isn't available in this session's tool registry", seen for real). So the
// tool list is part of a session's fingerprint, exactly like the capabilities.
describe('a thread started before a tool existed', () => {
  /** An engine that runs no CLI: it records the spec it was handed and ends the turn. */
  class SpyEngine implements Engine {
    readonly id = 'codex' as const;
    readonly label = 'Spy';
    seen: TurnSpec[] = [];
    model(): string {
      return 'spy';
    }
    async checkAuth(): Promise<{ ok: true; account: string }> {
      return { ok: true, account: 'spy' };
    }
    authFailedMessage(d: string): string {
      return d;
    }
    async runTurn(spec: TurnSpec): Promise<TurnStats> {
      this.seen.push(spec);
      spec.onSession('thread-new');
      return { sessionId: 'thread-new', numTurns: 1, isError: false, errors: [] };
    }
  }

  async function leadTurn(session: Record<string, unknown>): Promise<TurnSpec> {
    const isolatedHome = tempDir();
    const isolatedRepo = await demoRepo();
    const iso = makeForeman(isolatedHome, ['--backend', 'codex']);
    try {
      const repo = await iso.fm.repos.add(isolatedRepo);
      const engine = new SpyEngine();
      const backend = new TeamBackend(iso.fm, iso.cfg.claude, { name: 'codex', engines: { lead: engine, worker: engine }, skipAuthCheck: true });
      iso.fm['backend'] = backend;
      await backend.start();
      const goal = await iso.fm.submitGoal('do a thing', repo.id);
      // the planning turn first (always a fresh thread), then the one under test
      for (let i = 0; i < 100 && !engine.seen.length; i++) await new Promise((r) => setTimeout(r, 20));
      expect(engine.seen.length, 'the lead planned').toBe(1);
      iso.fm.store.data.sessions[`marlow:${goal.id}`] = { turns: 1, costUsd: 0, updatedAt: 1, sessionId: 'thread-old', engine: 'codex', ...session };
      iso.fm.bus.send('user', 'marlow', 'ping');
      backend.onUserMessage('marlow', 'ping');
      for (let i = 0; i < 100 && engine.seen.length < 2; i++) await new Promise((r) => setTimeout(r, 20));
      expect(engine.seen.length, 'the lead answered the message').toBe(2);
      return engine.seen.at(-1)!;
    } finally {
      await iso.fm.close();
      rmrf(isolatedHome);
      rmrf(path.dirname(isolatedRepo));
    }
  }

  it('starts a fresh thread when the tool list has changed', async () => {
    const spec = await leadTurn({ caps: 'closed', tools: 'ask_user,send_message' });
    expect(spec.resume, 'the stale thread was not resumed').toBeUndefined();
    expect(spec.tools.map((t) => t.name)).toContain('write_artifact');
  });

  it('still resumes a thread whose tools and capabilities match', async () => {
    const tools = `goal-context-v1:${[...TOOL_NAMES.common, ...TOOL_NAMES.lead].slice().sort().join(',')}`;
    const spec = await leadTurn({ caps: 'closed', tools });
    expect(spec.resume).toBe('thread-old');
  });

  it('starts a fresh thread when names match but goal context and team instructions predate this version', async () => {
    const tools = [...TOOL_NAMES.common, ...TOOL_NAMES.lead].slice().sort().join(',');
    const spec = await leadTurn({ caps: 'closed', tools });
    expect(spec.resume).toBeUndefined();
  });
});

// Typing an id by hand is what the `#` mention exists to avoid: the console completes these from
// the artifacts and the user's own folders, so the text that arrives holds an exact name.
describe('# mentions', () => {
  it('reads a bare name and a quoted one', () => {
    expect(mentionsIn('read #movement-review and do the open items')).toEqual(['movement-review']);
    expect(mentionsIn('read #"10 Projects/Second Brain.md" first')).toEqual(['10 Projects/Second Brain.md']);
    expect(mentionsIn('check #aa and #"b c" and #aa again')).toEqual(['aa', 'b c']);
    expect(mentionsIn('a lone #x is noise, not a name'), 'one character is not a mention').toEqual([]);
  });

  it('ignores a bare # and the middle of a word', () => {
    expect(mentionsIn('nothing here')).toEqual([]);
    expect(mentionsIn('# alone')).toEqual([]);
    expect(mentionsIn('issue#1')).toEqual([]);
  });

  it('resolves an artifact named by mention even when the prose does not', () => {
    const e = { id: 'artifacts/review', scope: 'artifacts', title: 'A review', body: '- [ ] do it', updated: 1 };
    expect(artifactsNamedIn('work on #review now', [e])).toHaveLength(1);
    expect(artifactsNamedIn('work on reviewing now', [e])).toHaveLength(0);
  });

  it('tells the lead where a note lives and that it is not theirs to change', () => {
    const brief = notesBrief([{ source: 'vault', label: 'Vault', path: '/Users/me/Vault/Notes.md', title: 'Notes' }]);
    expect(brief).toContain('/Users/me/Vault/Notes.md');
    expect(brief).toContain('read-only');
    expect(notesBrief([])).toBe('');
  });
});
