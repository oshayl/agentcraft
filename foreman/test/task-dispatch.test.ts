import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { TeamBackend } from '../src/agents/team.js';
import type { Engine, TurnSpec } from '../src/agents/engine.js';
import { demoRepo, makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';

let h: Harness;
let backend: TeamBackend;
let repo: string;
const calls: TurnSpec[] = [];
afterEach(async () => {
  await h?.fm.close();
  if (h) rmrf(h.home);
  if (repo) rmrf(path.dirname(repo));
  vi.restoreAllMocks();
  calls.length = 0;
});

async function setup(slots = 2) {
  repo = await demoRepo();
  h = makeForeman(tempDir(), ['--backend', 'claude', '--workers', 'kit,wren', '--max-concurrent', String(slots), '--repo', repo]);
  const engine: Engine = {
    id: 'claude', label: 'Fake', model: () => 'fake', checkAuth: async () => ({ ok: true, account: 'test' }), authFailedMessage: (s) => s,
    runTurn: async (spec) => {
      calls.push(spec);
      await new Promise<void>((resolve) => {
        if (spec.abort.signal.aborted) resolve();
        else spec.abort.signal.addEventListener('abort', () => resolve(), { once: true });
      });
      return { isError: false, errors: [] };
    },
  };
  backend = new TeamBackend(h.fm, h.cfg.claude, { name: 'claude', engines: { lead: engine, worker: engine }, skipAuthCheck: true });
  await h.fm.start(backend);
  const goal = h.fm.createGoal('dispatch regression', 'demo-app');
  h.fm.setGoal(goal.id, { status: 'active' });
  return { fm: h.fm, goal };
}

const pause = (ms: number) => new Promise((r) => setTimeout(r, ms));

describe('task dispatch', () => {
  it('uses an idle teammate for unstarted work behind a busy preferred assignee', async () => {
    const { fm, goal } = await setup();
    const first = fm.tasks.create({ title: 'first', assignee: 'kit', goalId: goal.id, createdBy: 'marlow' });
    backend.tick();
    await until(() => calls.length === 1);
    const next = fm.tasks.create({ title: 'next', assignee: 'kit', goalId: goal.id, createdBy: 'marlow' });
    backend.tick();
    await until(() => calls.length === 2);
    expect(first.assignee).toBe('kit');
    expect(next.assignee).toBe('wren');
    expect(next.status).toBe('doing');
  });

  it('keeps a user assignment pinned, then Auto releases it to an idle teammate', async () => {
    const { fm, goal } = await setup();
    fm.tasks.create({ title: 'first', assignee: 'kit', goalId: goal.id, createdBy: 'marlow' });
    backend.tick();
    await until(() => calls.length === 1);
    const next = fm.tasks.create({ title: 'next', goalId: goal.id, createdBy: 'marlow' });
    fm.taskAction(next.id, 'reassign', 'kit');
    await pause(200);
    expect(calls).toHaveLength(1);
    expect(next).toMatchObject({ status: 'todo', assignee: 'kit', assigneePinned: true });
    fm.taskAction(next.id, 'reassign', 'auto');
    await until(() => calls.length === 2);
    expect(next).toMatchObject({ status: 'doing', assignee: 'wren', assigneePinned: false });
  });

  it('prioritizes across goals on the same team', async () => {
    const { fm, goal } = await setup(1);
    const old = fm.tasks.create({ title: 'old goal work', goalId: goal.id, createdBy: 'marlow' });
    const newer = fm.createGoal('newer goal', 'demo-app');
    fm.setGoal(newer.id, { status: 'active' });
    const urgent = fm.tasks.create({ title: 'urgent', goalId: newer.id, createdBy: 'marlow' });
    fm.taskAction(urgent.id, 'prioritize');
    await until(() => calls.length === 1);
    expect(urgent.status).toBe('doing');
    expect(old.status).toBe('todo');
  });

  it('starts the prerequisite of a prioritized outcome, without bypassing dependencies', async () => {
    const { fm, goal } = await setup(1);
    const unrelated = fm.tasks.create({ title: 'unrelated', goalId: goal.id, createdBy: 'marlow' });
    const prereq = fm.tasks.create({ title: 'needed', goalId: goal.id, createdBy: 'marlow' });
    const target = fm.tasks.create({ title: 'outcome', deps: [prereq.id], goalId: goal.id, createdBy: 'marlow' });
    fm.taskAction(target.id, 'prioritize');
    await until(() => calls.length === 1);
    expect(prereq.status).toBe('doing');
    expect(target.status).toBe('todo');
    expect(unrelated.status).toBe('todo');
  });

  it('does not start the same task twice when another tick arrives during git preparation', async () => {
    const { fm, goal } = await setup();
    const create = fm.repos.createWorktree.bind(fm.repos);
    const spy = vi.spyOn(fm.repos, 'createWorktree').mockImplementation(async (...args) => {
      backend.tick();
      await pause(150);
      return create(...args);
    });
    fm.tasks.create({ title: 'slow git', goalId: goal.id, createdBy: 'marlow' });
    backend.tick();
    await until(() => calls.length === 1);
    await pause(200);
    expect(spy).toHaveBeenCalledTimes(1);
    expect(calls).toHaveLength(1);
  });

  it('honours cancellation while a worktree is being prepared', async () => {
    const { fm, goal } = await setup();
    const t = fm.tasks.create({ title: 'cancel during git', goalId: goal.id, createdBy: 'marlow' });
    const create = fm.repos.createWorktree.bind(fm.repos);
    let prepared = false;
    vi.spyOn(fm.repos, 'createWorktree').mockImplementation(async (...args) => {
      fm.taskAction(t.id, 'cancel');
      const wt = await create(...args);
      prepared = true;
      return wt;
    });
    backend.tick();
    await until(() => prepared);
    await pause(150);
    expect(t.status).toBe('cancelled');
    expect(calls).toHaveLength(0);
  });

  it('rejects reassignment to a worker from another team instead of silently undoing it', async () => {
    const { fm, goal } = await setup();
    fm.teams.create({ id: 'other', name: 'Other', lead: 'juniper', members: ['wren'] });
    const t = fm.tasks.create({ title: 'ours', assignee: 'kit', goalId: goal.id, createdBy: 'marlow' });
    expect(() => fm.taskAction(t.id, 'reassign', 'wren')).toThrow(/not on/);
    expect(t.assignee).toBe('kit');
  });
});
