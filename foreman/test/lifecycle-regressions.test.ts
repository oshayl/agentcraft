import { afterEach, describe, expect, it, vi } from 'vitest';
import { TeamBackend } from '../src/agents/team.js';
import type { Engine, TurnSpec } from '../src/agents/engine.js';
import type { Task } from '../src/protocol.js';
import { makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => { resolve = r; });
  return { promise, resolve };
}
const passed = { pass: true, command: 'fake tests', output: 'ok', durationMs: 1, code: 0, failures: [] };
const failed = { ...passed, pass: false, output: 'failure' };
const ok = { isError: false, errors: [], subtype: 'success' };
const fixtures: Array<{ h: Harness; backend: TeamBackend }> = [];
afterEach(async () => {
  for (const { h, backend } of fixtures.splice(0)) {
    await backend.stop();
    await h.fm.close();
    rmrf(h.home);
  }
});

function setup() {
  const h = makeForeman(tempDir(), ['--backend', 'sim', '--workers', 'kit,juniper']);
  const fm = h.fm;
  fm.store.data.repos.push({ id: 'r', name: 'fake repo', path: h.home, branch: 'main', dirty: false, ci: 'unknown', worktrees: [] });
  fm.teams.adopt(['r']);
  const goal = fm.createGoal('Lifecycle regression', 'r');
  fm.setGoal(goal.id, { status: 'active' });
  const calls: TurnSpec[] = [];
  let run: (spec: TurnSpec) => ReturnType<Engine['runTurn']> = async (spec) => {
    await new Promise<void>((resolve) => spec.abort.signal.addEventListener('abort', () => resolve(), { once: true }));
    return ok;
  };
  const engine: Engine = {
    id: 'codex', label: 'Fake', model: () => 'fake', checkAuth: async () => ({ ok: true, account: 'fake' }), authFailedMessage: () => '',
    runTurn: async (spec) => { calls.push(spec); return run(spec); },
  };
  const backend = new TeamBackend(fm, h.cfg.claude, { name: 'codex', engines: { lead: engine, worker: engine }, skipAuthCheck: true });
  // Exercise the real queue and turn handling without launching engines, git, or a scheduler.
  const internals = backend as any;
  (fm as any).backend = backend;
  vi.spyOn(backend, 'tick').mockImplementation(() => {});
  vi.spyOn(internals, 'writableRoots').mockResolvedValue([]);
  vi.spyOn(internals, 'reap').mockResolvedValue(undefined);
  vi.spyOn(fm.repos, 'refresh').mockResolvedValue(undefined as never);
  vi.spyOn(fm.repos, 'runTests').mockResolvedValue(passed);
  vi.spyOn(fm.repos, 'diff').mockResolvedValue({ files: [], stats: '' } as never);
  vi.spyOn(fm.repos, 'uncommittedReview').mockResolvedValue(undefined as never);
  vi.spyOn(fm.repos, 'collidingUntracked').mockResolvedValue([]);
  function task(status: Task['status'] = 'review') {
    const t = fm.tasks.create({ title: 'Test task', createdBy: 'marlow', goalId: goal.id, repoId: 'r', assignee: 'kit' });
    const wt = `kit-${t.id}`;
    fm.repos.require('r').worktrees.push({ id: wt, agentId: 'kit', taskId: t.id, path: h.home, branch: `ac-${t.id}`, base: 'main', status: 'active', ahead: 0, files: 1, additions: 1, deletions: 0 });
    fm.tasks.update(t.id, { worktree: wt });
    fm.tasks.setStatus(t.id, status, { force: true });
    fm.setAgent('kit', { active: true, paused: false, taskId: t.id, repoId: 'r', worktree: wt });
    return t;
  }
  const job = (t: Task) => ({ kind: 'work', agentId: 'kit', taskId: t.id, goalId: goal.id, sessionKey: `kit:${t.id}`, prompt: 'fake work' });
  fixtures.push({ h, backend });
  return { h, fm, goal, backend, internals, calls, task, job, setRun: (fn: typeof run) => { run = fn; } };
}

describe('asynchronous CI and review honor newer user intent', () => {
  it.each([true, false])('does not revive cancelled work after CI passes=%s', async (pass) => {
    const { fm, goal, internals, task, calls } = setup();
    const t = task();
    const ci = deferred<typeof passed>();
    vi.mocked(fm.repos.runTests).mockReturnValue(ci.promise);
    const pipeline = internals.ciThenReview(t.id);
    fm.taskAction(t.id, 'cancel');
    fm.setGoal(goal.id, { status: 'cancelled' });
    ci.resolve({ ...passed, pass });
    await pipeline;
    expect(t.status).toBe('cancelled');
    expect(t.ci).toBe('running'); // a superseded result must not overwrite task state
    expect(goal.status).toBe('cancelled');
    expect(fm.repos.refresh).not.toHaveBeenCalled();
    expect(fm.decisions.open()).toEqual([]);
    expect(calls).toEqual([]);
    expect(internals.queues.get('kit') ?? []).toEqual([]);
  });

  it.each(['reassigned', 'removed', 'worktree changed', 'goal removed'])('drops a CI result when %s', async (change) => {
    const { fm, goal, internals, task } = setup();
    const t = task();
    const ci = deferred<typeof passed>();
    vi.mocked(fm.repos.runTests).mockReturnValue(ci.promise);
    const pipeline = internals.ciThenReview(t.id);
    if (change === 'reassigned') fm.taskAction(t.id, 'reassign', 'juniper');
    if (change === 'removed') fm.store.data.tasks = fm.store.data.tasks.filter((x) => x.id !== t.id);
    if (change === 'worktree changed') fm.tasks.update(t.id, { worktree: 'replacement' });
    if (change === 'goal removed') fm.store.data.goals = fm.store.data.goals.filter((x) => x.id !== goal.id);
    ci.resolve(failed);
    await pipeline;
    expect(fm.repos.refresh).not.toHaveBeenCalled();
    expect(fm.decisions.open()).toEqual([]);
    expect(internals.queues.get('kit') ?? []).toEqual([]);
  });

  it.each(['refresh', 'diff', 'uncommittedReview', 'collidingUntracked'] as const)('does not review or merge after cancellation during %s', async (stage) => {
    const { fm, h, internals, task } = setup();
    const t = task();
    h.cfg.claude.leadReview = stage === 'diff';
    const gate = deferred<any>();
    const hit = deferred<void>();
    vi.mocked(fm.repos[stage]).mockImplementation((async () => { hit.resolve(); return gate.promise; }) as never);
    const pipeline = internals.ciThenReview(t.id);
    await hit.promise;
    fm.taskAction(t.id, 'cancel');
    gate.resolve(stage === 'diff' ? { files: [], stats: '' } : stage === 'collidingUntracked' ? [] : undefined);
    await pipeline;
    expect(t.status).toBe('cancelled');
    expect(fm.decisions.open()).toEqual([]);
    expect(internals.queues.get('marlow') ?? []).toEqual([]);
  });

  it('an older pipeline cannot overwrite a newer CI result for the same task', async () => {
    const { fm, h, internals, task } = setup();
    const t = task();
    h.cfg.claude.leadReview = false;
    const old = deferred<typeof passed>();
    vi.mocked(fm.repos.runTests).mockReturnValueOnce(old.promise).mockResolvedValueOnce(passed);
    const first = internals.ciThenReview(t.id);
    await internals.ciThenReview(t.id);
    old.resolve(failed);
    await first;
    expect(t.ci).toBe('pass');
    expect(t.status).toBe('review');
    expect(fm.decisions.open().filter((d) => d.kind === 'merge')).toHaveLength(1);
    expect(internals.queues.get('kit') ?? []).toEqual([]);
  });

  it('reassignment aborts an already running lead review before it can open a merge decision', async () => {
    const { fm, internals, task, calls } = setup();
    const t = task();
    internals.enqueue({ kind: 'review', agentId: 'marlow', taskId: t.id, goalId: t.goalId, sessionKey: `marlow:${t.goalId}`, prompt: 'review this' });
    await until(() => calls.length === 1);
    fm.taskAction(t.id, 'reassign', 'juniper');
    await until(() => !internals.running.has('marlow'));
    expect(calls[0]!.abort.signal.aborted).toBe(true);
    expect(fm.decisions.open()).toEqual([]);
  });

  it('cancelling then retrying allows new CI without the old pipeline clearing its lock', async () => {
    const { fm, internals, task } = setup();
    const t = task();
    const old = deferred<typeof passed>();
    const replacement = deferred<typeof passed>();
    vi.mocked(fm.repos.runTests).mockReturnValueOnce(old.promise).mockReturnValueOnce(replacement.promise);
    const first = internals.afterWorkerDone(t.id);
    fm.taskAction(t.id, 'cancel');
    fm.taskAction(t.id, 'retry');
    fm.tasks.setStatus(t.id, 'review', { force: true });
    const second = internals.afterWorkerDone(t.id);
    old.resolve(failed);
    await first;
    expect(internals.reviewing.has(t.id)).toBe(true);
    replacement.resolve(passed);
    await second;
    expect(t.ci).toBe('pass');
    expect(internals.reviewing.has(t.id)).toBe(false);
  });
});

describe('paused and queued jobs cannot resurrect stale tasks', () => {
  it.each(['cancel', 'reassign', 'goal cancel', 'remove'])('discards paused work after %s', async (change) => {
    const { fm, goal, backend, internals, task, job, calls } = setup();
    const t = task('doing');
    internals.enqueue(job(t));
    await until(() => calls.length === 1);
    await fm.agentAction('kit', 'pause');
    await until(() => internals.pausedJobs.has('kit'));
    if (change === 'cancel') fm.taskAction(t.id, 'cancel');
    if (change === 'reassign') fm.taskAction(t.id, 'reassign', 'juniper');
    if (change === 'goal cancel') { fm.setGoal(goal.id, { status: 'cancelled' }); backend.onGoalCancelled(goal); }
    if (change === 'remove') fm.store.data.tasks = fm.store.data.tasks.filter((x) => x.id !== t.id);
    if (change !== 'remove') expect(internals.pausedJobs.has('kit')).toBe(false);
    await fm.agentAction('kit', 'resume');
    await new Promise((r) => setTimeout(r, 20));
    expect(calls).toHaveLength(1);
    expect(internals.pausedJobs.has('kit')).toBe(false);
    expect(internals.queues.get('kit') ?? []).toEqual([]);
  });

  it('cancellation while the paused engine unwinds does not recreate a paused job', async () => {
    const { fm, internals, task, job, calls, setRun } = setup();
    const finish = deferred<typeof ok>();
    setRun(() => finish.promise);
    const t = task('doing');
    internals.enqueue(job(t));
    await until(() => calls.length === 1);
    await fm.agentAction('kit', 'pause');
    fm.taskAction(t.id, 'cancel');
    finish.resolve(ok);
    await until(() => !internals.running.has('kit'));
    await fm.agentAction('kit', 'resume');
    expect(internals.pausedJobs.has('kit')).toBe(false);
    expect(calls).toHaveLength(1);
    expect(t.status).toBe('cancelled');
  });

  it('validates queued jobs even when no cancellation callback removed them', () => {
    const { fm, internals, task, job, calls } = setup();
    const t = task('doing');
    fm.setAgent('kit', { paused: true });
    internals.enqueue(job(t));
    fm.tasks.setStatus(t.id, 'cancelled', { force: true });
    fm.setAgent('kit', { paused: false });
    internals.pump('kit');
    expect(calls).toEqual([]);
    expect(internals.queues.get('kit')).toEqual([]);
  });

  it('cancellation during writable-root preparation clears inflight state and never launches an engine', async () => {
    const { fm, internals, task, job, calls } = setup();
    const roots = deferred<string[]>();
    vi.mocked(internals.writableRoots).mockReturnValue(roots.promise);
    const t = task('doing');
    internals.enqueue(job(t));
    await until(() => !!fm.store.data.backend.claude);
    fm.taskAction(t.id, 'cancel');
    roots.resolve([]);
    await until(() => !internals.running.has('kit'));
    expect((fm.store.data.backend.claude as any).inflight.kit).toBeUndefined();
    expect(calls).toEqual([]);
    expect(t.status).toBe('cancelled');
  });
});

describe('revision queue preserves active worker assignments', () => {
  it('finishes B with its own tools before binding the queued CI revision of A', async () => {
    const { fm, internals, task, job, calls, setRun } = setup();
    const a = task();
    const ci = deferred<typeof passed>();
    vi.mocked(fm.repos.runTests).mockReturnValue(ci.promise);
    const pipeline = internals.ciThenReview(a.id);
    const b = task('doing');
    const finishB = deferred<typeof ok>();
    setRun((spec) => spec.prompt === 'fake work' ? finishB.promise : new Promise((resolve) => spec.abort.signal.addEventListener('abort', () => resolve(ok), { once: true })));
    vi.spyOn(internals, 'afterWorkerDone').mockResolvedValue(undefined);
    internals.enqueue(job(b));
    await until(() => calls.length === 1);
    ci.resolve(failed);
    await pipeline;
    expect(fm.agent('kit')!.taskId).toBe(b.id);
    expect(fm.agent('kit')!.worktree).toBe(b.worktree);
    expect(calls).toHaveLength(1);
    const update = calls[0]!.tools.find((tool) => tool.name === 'update_task')!;
    const result = await update.handler({ task_id: b.id, status: 'review', summary: 'B finished' });
    expect(result.isError).not.toBe(true);
    expect(b.status).toBe('review');
    finishB.resolve(ok);
    await until(() => calls.length === 2);
    expect(fm.agent('kit')!.taskId).toBe(a.id);
    expect(fm.agent('kit')!.worktree).toBe(a.worktree);
    expect(calls[1]!.prompt).toContain(`CI failed for ${a.id}`);
    expect(a.status).toBe('doing');
  });

  it('holds a revision until its dependencies are satisfied', async () => {
    const { fm, internals, task, calls } = setup();
    const prerequisite = task('todo');
    const t = task();
    fm.tasks.update(t.id, { deps: [prerequisite.id] });
    internals.sendBackToWorker(t.id, 'revise');
    expect(calls).toHaveLength(0);
    fm.tasks.setStatus(prerequisite.id, 'done', { force: true });
    internals.pump('kit');
    await until(() => calls.length === 1);
    expect(fm.agent('kit')!.taskId).toBe(t.id);
  });

  it('does not start a queued revision while global worker capacity is full', async () => {
    const { fm, h, internals, task, calls } = setup();
    h.cfg.claude.maxConcurrent = 1;
    const t = task();
    internals.running.set('juniper', { job: { kind: 'work', agentId: 'juniper' }, abort: new AbortController() });
    internals.sendBackToWorker(t.id, 'revise');
    expect(calls).toHaveLength(0);
    internals.running.delete('juniper');
    internals.pump('kit');
    await until(() => calls.length === 1);
    expect(fm.agent('kit')!.taskId).toBe(t.id);
  });
});
