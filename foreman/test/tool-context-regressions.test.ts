import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { leadSystemPrompt, workerSystemPrompt } from '../src/agents/prompts.js';
import { agentTools, closeIfNoChanges, type ToolHooks } from '../src/agents/tools.js';
import type { Worktree } from '../src/protocol.js';
import { makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

let h: Harness;
const hooks: ToolHooks = { onReview() {}, onChangesRequested() {}, onTasksChanged() {}, onMergeRequested() {}, onWaiting() {} };

beforeEach(() => {
  h = makeForeman(tempDir(), ['--backend', 'sim', '--workers', 'kit,juniper,wren']);
  for (const id of ['a', 'b']) {
    h.fm.store.data.repos.push({ id, name: id, path: `/tmp/${id}`, branch: 'main', dirty: false, worktrees: [], ci: 'unknown' });
  }
  h.fm.teams.adopt(['a', 'b']);
});

afterEach(async () => {
  vi.restoreAllMocks();
  await h.fm.close();
  rmrf(h.home);
});

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((r) => { resolve = r; });
  return { promise, resolve };
}

function reviewTask(files: number) {
  const goal = h.fm.createGoal('Review repo A', 'a');
  h.fm.setGoal(goal.id, { status: 'active' });
  const task = h.fm.tasks.create({ title: 'Review work', createdBy: 'marlow', goalId: goal.id, repoId: 'a', assignee: 'kit' });
  const wt: Worktree = { id: `kit-${task.id}`, agentId: 'kit', taskId: task.id, path: h.home, branch: `ac-${task.id}`, base: 'main', status: 'active', ahead: 0, files, additions: files, deletions: 0 };
  h.fm.repos.require('a').worktrees.push(wt);
  h.fm.tasks.update(task.id, { worktree: wt.id });
  h.fm.tasks.setStatus(task.id, 'review', { force: true });
  h.fm.setAgent('kit', { taskId: task.id, repoId: 'a', worktree: wt.id, state: 'idle', station: 'lounge' });
  vi.spyOn(h.fm.repos, 'refresh').mockResolvedValue(h.fm.repos.require('a'));
  vi.spyOn(h.fm.repos, 'abandon').mockImplementation(async () => { wt.status = 'abandoned'; });
  const abort = new AbortController();
  const turn = { signal: abort.signal, reason: () => 'pause', waitingOnUser: () => () => {} };
  const tool = agentTools(h.fm, 'marlow', 'lead', hooks, turn, { goalId: goal.id }).find((t) => t.name === 'request_merge')!;
  return { goal, task, wt, abort, tool };
}

describe('merge tools honor changes made while git is pending', () => {
  const changes = ['task cancelled', 'goal cancelled', 'task removed', 'goal removed', 'reassigned', 'worktree changed', 'repo changed', 'turn aborted'] as const;
  const cases = changes.flatMap((change) => [
    { stage: 'refresh' as const, files: 0, change },
    { stage: 'refresh' as const, files: 1, change },
    { stage: 'abandon' as const, files: 0, change },
  ]);
  it.each(cases)('does not merge or close when $change during $stage with $files files', async ({ stage, files, change }) => {
    const { goal, task, wt, abort, tool } = reviewTask(files);
    const entered = deferred<void>();
    const gate = deferred<void>();
    if (stage === 'refresh') {
      vi.mocked(h.fm.repos.refresh).mockImplementation(async () => { entered.resolve(); await gate.promise; return h.fm.repos.require('a'); });
    } else {
      vi.mocked(h.fm.repos.abandon).mockImplementation(async () => { entered.resolve(); await gate.promise; wt.status = 'abandoned'; });
    }
    const pending = tool.handler({ task_id: task.id, summary: 'Looks good.' });
    await entered.promise;
    if (change === 'task cancelled') h.fm.tasks.setStatus(task.id, 'cancelled', { force: true });
    if (change === 'goal cancelled') h.fm.setGoal(goal.id, { status: 'cancelled' });
    if (change === 'task removed') h.fm.store.data.tasks = [];
    if (change === 'goal removed') h.fm.store.data.goals = [];
    if (change === 'reassigned') h.fm.tasks.update(task.id, { assignee: 'juniper' });
    if (change === 'worktree changed') h.fm.tasks.update(task.id, { worktree: 'replacement' });
    if (change === 'repo changed') h.fm.tasks.update(task.id, { repoId: 'b' });
    if (change === 'turn aborted') abort.abort();
    gate.resolve();
    const result = await pending;
    expect(result.isError).toBe(true);
    expect(h.fm.decisions.open()).toEqual([]);
    expect(h.fm.tasks.get(task.id)?.status).toBe(change === 'task removed' ? undefined : change === 'task cancelled' ? 'cancelled' : 'review');
    expect(h.fm.agent('kit')!.taskId).toBe(task.id);
    if (stage === 'refresh') expect(h.fm.repos.abandon).not.toHaveBeenCalled();
  });

  it('guards direct report-only closing after refresh even without a turn predicate', async () => {
    const { task } = reviewTask(0);
    const gate = deferred<void>();
    vi.mocked(h.fm.repos.refresh).mockImplementation(async () => { await gate.promise; return h.fm.repos.require('a'); });
    const pending = closeIfNoChanges(h.fm, task.id);
    h.fm.tasks.setStatus(task.id, 'cancelled', { force: true });
    gate.resolve();
    expect(await pending).toBe(false);
    expect(task.status).toBe('cancelled');
    expect(h.fm.repos.abandon).not.toHaveBeenCalled();
  });

  it('finishes an empty-tree close on a fresh retry after a pause interrupts abandonment', async () => {
    const { goal, task, wt, abort, tool } = reviewTask(0);
    const entered = deferred<void>();
    const gate = deferred<void>();
    vi.mocked(h.fm.repos.abandon).mockImplementation(async () => { entered.resolve(); await gate.promise; wt.status = 'abandoned'; });
    const pending = tool.handler({ task_id: task.id, summary: 'Report complete.' });
    await entered.promise;
    abort.abort();
    gate.resolve();
    expect((await pending).isError).toBe(true);
    expect(task.status).toBe('review');
    expect(wt.status).toBe('abandoned');
    const retry = agentTools(h.fm, 'marlow', 'lead', hooks, undefined, { goalId: goal.id }).find((t) => t.name === 'request_merge')!;
    expect((await retry.handler({ task_id: task.id, summary: 'Report complete.' })).isError).toBeFalsy();
    expect(task.status).toBe('done');
    expect(h.fm.repos.abandon).toHaveBeenCalledTimes(1);
    expect(h.fm.decisions.open()).toEqual([]);
  });

  it.each([0, 1])('still closes empty work or requests a merge for %s files when its review remains current', async (files) => {
    const { task, tool } = reviewTask(files);
    expect((await tool.handler({ task_id: task.id, summary: 'Looks good.' })).isError).toBeFalsy();
    expect(task.status).toBe(files ? 'review' : 'done');
    expect(h.fm.decisions.open()).toHaveLength(files ? 1 : 0);
    expect(h.fm.repos.abandon).toHaveBeenCalledTimes(files ? 0 : 1);
  });
});

describe('the goal that owns a tool server', () => {
  it('keeps a planning turn on its own goal and repository when another goal arrives', async () => {
    const a = h.fm.createGoal('Implement repo A', 'a');
    const tools = agentTools(h.fm, 'marlow', 'lead', hooks);
    const b = h.fm.createGoal('Implement repo B', 'b');

    const result = await tools.find((t) => t.name === 'create_task')!.handler({ title: 'Work for A', description: 'Change repo A', assignee: 'kit' });
    expect(result.isError).toBeFalsy();
    expect(h.fm.tasks.list()).toHaveLength(1);
    expect(h.fm.tasks.list()[0]).toMatchObject({ goalId: a.id, repoId: 'a', assignee: 'kit' });
    expect(h.fm.tasks.list()[0]!.goalId).not.toBe(b.id);
  });

  it('uses the job goal even when a newer goal exists before the tools are constructed', async () => {
    const a = h.fm.createGoal('Implement repo A', 'a');
    h.fm.createGoal('Implement repo B', 'b');
    const create = agentTools(h.fm, 'marlow', 'lead', hooks, undefined, { goalId: a.id }).find((t) => t.name === 'create_task')!;
    await create.handler({ title: 'Work for A', description: 'Change repo A' });
    expect(h.fm.tasks.list()[0]).toMatchObject({ goalId: a.id, repoId: 'a' });
  });

  it.each(['cancelled', 'done', 'failed'] as const)('refuses new tasks after its goal becomes %s instead of taking the next goal', async (status) => {
    const a = h.fm.createGoal('Implement repo A', 'a');
    const create = agentTools(h.fm, 'marlow', 'lead', hooks, undefined, { goalId: a.id }).find((t) => t.name === 'create_task')!;
    h.fm.setGoal(a.id, { status });
    h.fm.createGoal('Implement repo B', 'b');
    const result = await create.handler({ title: 'Stale work', description: 'Do not run' });
    expect(result.isError).toBe(true);
    expect(result.content[0]!.text).toContain(`goal ${a.id} is ${status}`);
    expect(h.fm.tasks.list()).toHaveLength(0);
  });

  it('refuses tasks for a removed goal without falling back to another repository', async () => {
    const a = h.fm.createGoal('Implement repo A', 'a');
    const create = agentTools(h.fm, 'marlow', 'lead', hooks, undefined, { goalId: a.id }).find((t) => t.name === 'create_task')!;
    h.fm.store.data.goals = [];
    h.fm.createGoal('Implement repo B', 'b');
    const result = await create.handler({ title: 'Stale work', description: 'Do not run' });
    expect(result.isError).toBe(true);
    expect(h.fm.tasks.list()).toHaveLength(0);
  });

  it('does not attach a goal-free job to a goal that arrives later', async () => {
    h.fm.createGoal('Implement repo A', 'a');
    const create = agentTools(h.fm, 'marlow', 'lead', hooks, undefined, {}).find((t) => t.name === 'create_task')!;
    h.fm.createGoal('Implement repo B', 'b');
    await create.handler({ title: 'Ad hoc work', description: 'No goal context' });
    expect(h.fm.tasks.list()[0]!.goalId).toBeUndefined();
    expect(h.fm.tasks.list()[0]!.repoId).toBeUndefined();
  });

  it('files a finished goal artifact against that goal when another goal is active', async () => {
    const a = h.fm.createGoal('Implement repo A', 'a');
    h.fm.setGoal(a.id, { status: 'done' });
    h.fm.createGoal('Implement repo B', 'b');
    const write = agentTools(h.fm, 'marlow', 'lead', hooks, undefined, { goalId: a.id }).find((t) => t.name === 'write_artifact')!;
    const result = await write.handler({ title: 'Repo A results', summary: 'A is complete.', open_items: [] });
    expect(result.isError).toBeFalsy();
    expect(h.fm.memory.artifacts()[0]!.body).toContain(`> Goal ${a.id}: Implement repo A`);
    expect(h.fm.memory.artifacts()[0]!.body).not.toContain('Implement repo B');
  });

  it('refuses an artifact for a removed goal rather than silently dropping its association', async () => {
    const a = h.fm.createGoal('Implement repo A', 'a');
    const write = agentTools(h.fm, 'marlow', 'lead', hooks, undefined, { goalId: a.id }).find((t) => t.name === 'write_artifact')!;
    h.fm.store.data.goals = [];
    h.fm.createGoal('Implement repo B', 'b');
    expect((await write.handler({ title: 'Repo A results', summary: 'A is complete.', open_items: [] })).isError).toBe(true);
    expect(h.fm.memory.artifacts()).toHaveLength(0);
  });
});

describe('the sender’s team lead', () => {
  beforeEach(() => {
    h.fm.teams.create({ id: 'clients', name: 'Clients', lead: 'juniper', members: ['wren'], repos: ['b'] });
  });

  it('routes the lead alias from Wren to Juniper', async () => {
    const send = agentTools(h.fm, 'wren', 'worker', hooks).find((t) => t.name === 'send_message')!;
    expect((await send.handler({ to: 'lead', text: 'Please review my approach.' })).isError).toBeFalsy();
    expect(h.fm.bus.inbox('juniper')).toMatchObject([{ from: 'wren', to: 'juniper', text: 'Please review my approach.' }]);
    expect(h.fm.bus.inbox('marlow')).toHaveLength(0);
  });

  it('describes Juniper and her own roster in lead and worker instructions', () => {
    const team = h.fm.teamOf('juniper');
    const lead = leadSystemPrompt(h.fm, team.members, undefined, 'juniper');
    expect(lead).toContain('# You are Juniper, lead');
    expect(lead).toContain('Your workers: Wren (id "wren").');
    expect(lead).not.toContain('Marlow');
    const worktree = { path: '/tmp/wren', branch: 'worker/wren', base: 'main' } as Worktree;
    const worker = workerSystemPrompt(h.fm, 'wren', worktree);
    expect(worker).toContain('team led by Juniper');
    expect(worker).not.toContain('Marlow');
  });
});
