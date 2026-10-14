// Stopping and removing a goal from in game. Before this the only way off the board was to cancel
// every task by hand and hope the goal followed; a goal the user had given up on kept its workers
// running, kept its lead planning more work for it, and could never be removed because the board
// could still see live tasks under it.
import { afterEach, describe, expect, it } from 'vitest';
import { makeForeman, rmrf, tempDir, until, type Harness } from './helpers.js';
import type { Outbound } from '../src/protocol.js';

let h: Harness;
let home: string;
afterEach(async () => {
  await h?.fm.close();
  rmrf(home);
});

function setup(): Harness {
  home = tempDir();
  h = makeForeman(home, ['--backend', 'sim']);
  return h;
}

/** Send a goal.action and return the ack's result (or its error message). */
async function act(action: 'cancel' | 'remove' | 'clear', goalId?: string): Promise<Record<string, unknown>> {
  const out: Outbound[] = [];
  await h.fm.handle({ v: 1, id: 'q', type: 'goal.action', action, ...(goalId ? { goalId } : {}) }, (m) => out.push(m));
  const ack = out.find((m) => m.type === 'ack');
  if (ack?.type !== 'ack') throw new Error('no ack');
  return ack.ok ? (ack.result ?? {}) : { error: ack.error };
}

describe('goal.action stop', () => {
  it('stops the goal and every unfinished task under it, leaving the finished ones alone', async () => {
    const { fm } = setup();
    const g = fm.createGoal('take the movement over by hand');
    const done = fm.tasks.create({ title: 'already landed', createdBy: 'marlow', goalId: g.id });
    fm.tasks.setStatus(done.id, 'done', { force: true });
    const doing = fm.tasks.create({ title: 'half done', createdBy: 'marlow', goalId: g.id, assignee: 'kit' });
    fm.tasks.setStatus(doing.id, 'doing', { force: true });
    const todo = fm.tasks.create({ title: 'never started', createdBy: 'marlow', goalId: g.id });

    const res = await act('cancel', g.id);
    expect(res.cancelled).toBe(2);
    expect(fm.goal(g.id)!.status).toBe('cancelled');
    expect(fm.tasks.require(doing.id).status).toBe('cancelled');
    expect(fm.tasks.require(todo.id).status).toBe('cancelled');
    // a finished task is history, not work in progress: stopping the goal must not rewrite it
    expect(fm.tasks.require(done.id).status).toBe('done');
  });

  it('stays stopped: finished tasks do not count as work that revives it', async () => {
    const { fm } = setup();
    const g = fm.createGoal('mostly finished');
    for (let i = 0; i < 3; i++) {
      const t = fm.tasks.create({ title: `done ${i}`, createdBy: 'marlow', goalId: g.id });
      fm.tasks.setStatus(t.id, 'done', { force: true });
    }
    const last = fm.tasks.create({ title: 'the one still going', createdBy: 'marlow', goalId: g.id });
    fm.tasks.setStatus(last.id, 'doing', { force: true });

    await act('cancel', g.id);
    // the goal recompute runs in a microtask after the cancels; it used to read the three done
    // tasks as "live" and set the goal back to active, then to done - so a goal the user stopped
    // reported itself complete
    await new Promise((r) => setTimeout(r, 20));
    expect(fm.goal(g.id)!.status).toBe('cancelled');
  });

  it('comes back only when there is unfinished work again', async () => {
    const { fm } = setup();
    const g = fm.createGoal('reopened');
    const t = fm.tasks.create({ title: 'first', createdBy: 'marlow', goalId: g.id });
    fm.tasks.setStatus(t.id, 'done', { force: true });
    await act('cancel', g.id);
    await new Promise((r) => setTimeout(r, 20));
    expect(fm.goal(g.id)!.status).toBe('cancelled');

    fm.tasks.create({ title: 'the lead added more', createdBy: 'marlow', goalId: g.id });
    await until(() => fm.goal(g.id)!.status === 'active');
  });

  it('removes a stopped goal and takes its tasks with it', async () => {
    const { fm } = setup();
    const g = fm.createGoal('gone for good');
    fm.tasks.create({ title: 'a', createdBy: 'marlow', goalId: g.id });
    fm.tasks.create({ title: 'b', createdBy: 'marlow', goalId: g.id });

    // live work blocks removal, and the refusal says how to get past it
    const refused = await act('remove', g.id);
    expect(String(refused.lines)).toMatch(/stop it first|stop them first/);
    expect(fm.goal(g.id)).toBeDefined();

    await act('cancel', g.id);
    const res = await act('remove', g.id);
    expect(res.removed).toBe(1);
    expect(fm.goal(g.id)).toBeUndefined();
    expect(fm.tasks.list().filter((t) => t.goalId === g.id)).toEqual([]);
  });

  it('refuses a goal id that is not there rather than quietly doing nothing', async () => {
    setup();
    expect(String((await act('cancel', 'g999')).error)).toContain('no goal g999');
  });

  it('says so when there was nothing left to stop', async () => {
    const { fm } = setup();
    const g = fm.createGoal('already finished');
    const t = fm.tasks.create({ title: 'a', createdBy: 'marlow', goalId: g.id });
    fm.tasks.setStatus(t.id, 'done', { force: true });
    fm.setGoal(g.id, { status: 'done', progress: 1 });
    expect(String((await act('cancel', g.id)).lines)).toContain('nothing to stop');
    expect(fm.goal(g.id)!.status).toBe('done');
  });
});
