import { afterEach, describe, expect, it } from 'vitest';
import type { ClientMessage, Outbound } from '../src/protocol.js';
import { makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

const cases: Harness[] = [];
function setup(): Harness {
  const h = makeForeman(tempDir('ac-ownership-'), ['--backend', 'sim', '--workers', 'juniper,kit,wren']);
  cases.push(h);
  return h;
}

afterEach(async () => {
  for (const h of cases.splice(0)) {
    await h.fm.close();
    rmrf(h.home);
  }
});

async function send(h: Harness, msg: ClientMessage) {
  const replies: Outbound[] = [];
  await h.fm.handle({ ...msg, id: 'ownership-test' }, (m) => replies.push(m));
  const ack = replies.find((m) => m.type === 'ack');
  if (ack?.type !== 'ack') throw new Error('no ack');
  expect(ack.ok).toBe(true);
  return ack.result;
}

describe('team ownership regressions', () => {
  it('leaves no phantom team or partial roster after a rejected create', () => {
    const { fm, events } = setup();
    const before = structuredClone(fm.store.data);
    const count = events.length;
    expect(() => fm.teams.create({ id: 'bad', name: 'Bad', lead: 'marlow' })).toThrow(/already leads/);
    expect(() => fm.teams.create({ id: 'bad', name: 'Bad', lead: 'kit', members: ['wren', 'marlow'] })).toThrow(/leads/);
    expect(fm.store.data).toEqual(before);
    expect(events).toHaveLength(count);
  });

  it('validates a complete update before moving workers or changing metadata', () => {
    const { fm, events } = setup();
    fm.teams.create({ id: 'clients', name: 'Clients', lead: 'juniper', members: ['wren'] });
    const before = structuredClone(fm.store.data);
    const count = events.length;
    expect(() => fm.teams.update('studio', { name: 'Changed', members: ['wren', 'juniper'] })).toThrow(/leads/);
    expect(fm.store.data).toEqual(before);
    expect(events).toHaveLength(count);
  });

  it('refuses disband while goals or tasks are unfinished, without stranding them', () => {
    const { fm } = setup();
    fm.teams.create({ id: 'clients', name: 'Clients', lead: 'juniper', members: ['wren'] });
    const goal = fm.createGoal('client work');
    goal.teamId = 'clients';
    fm.setGoal(goal.id, { status: 'active' });
    const task = fm.tasks.create({ title: 'unfinished', createdBy: 'juniper', goalId: goal.id, teamId: 'clients' });
    const before = structuredClone(fm.store.data);
    expect(() => fm.teams.remove('clients')).toThrow(/unfinished work/);
    expect(fm.store.data).toEqual(before);
    expect(fm.teams.get('clients')?.members).toContain('wren');
    fm.setGoal(goal.id, { status: 'cancelled' });
    expect(() => fm.teams.remove('clients')).toThrow(/unfinished work/);
    fm.tasks.setStatus(task.id, 'cancelled', { force: true });
    fm.teams.remove('clients');
    expect(goal.teamId).toBe('studio');
    expect(task.teamId).toBe('studio');
    expect(fm.agent('juniper')?.role).toBe('worker');
    expect(() => fm.taskAction(task.id, 'reassign', 'kit')).not.toThrow();
  });

  it('rejects invalid disband destinations without removing the source', () => {
    const { fm } = setup();
    fm.teams.create({ id: 'clients', name: 'Clients', lead: 'juniper' });
    expect(() => fm.teams.remove('clients', 'clients')).toThrow(/itself/);
    expect(() => fm.teams.remove('clients', 'missing')).toThrow(/no team/);
    expect(fm.teams.list().map((t) => t.id)).toEqual(['studio', 'clients']);
  });

  it('replaces client team maps after disband and migrates historical ownership', async () => {
    const h = setup();
    h.fm.teams.create({ id: 'clients', name: 'Clients', lead: 'juniper' });
    const goal = h.fm.createGoal('finished client goal');
    goal.teamId = 'clients';
    h.fm.setGoal(goal.id, { status: 'cancelled' });
    await send(h, { v: 1, type: 'team.set', teamId: 'clients', disband: true });
    const snapshot = h.events.filter((m) => m.type === 'snapshot').at(-1);
    expect(snapshot?.type).toBe('snapshot');
    if (snapshot?.type !== 'snapshot') throw new Error('no replacement snapshot');
    expect(snapshot.teams.map((t) => t.id)).toEqual(['studio']);
    expect(snapshot.goals.find((g) => g.id === goal.id)?.teamId).toBe('studio');
  });

  it('uses actual team leadership for model commands and the client roster, including restart', async () => {
    const h = setup();
    h.fm.teams.create({ id: 'clients', name: 'Clients', lead: 'juniper', members: ['wren'] });
    expect(h.fm.agent('juniper')?.role).toBe('lead');
    await send(h, { v: 1, type: 'model.set', who: 'lead', model: 'lead-model' });
    await send(h, { v: 1, type: 'model.set', who: 'workers', model: 'worker-model' });
    expect(h.fm.store.data.agentModels.juniper).toBe('lead-model');
    expect(h.fm.store.data.agentModels.marlow).toBe('lead-model');
    expect(h.fm.store.data.agentModels.wren).toBe('worker-model');
    await h.fm.close();
    const again = makeForeman(h.home, ['--backend', 'sim']);
    cases[cases.indexOf(h)] = again;
    expect(again.fm.agent('juniper')?.role).toBe('lead');
    again.fm.teams.update('clients', { lead: 'wren' });
    expect(again.fm.agent('juniper')?.role).toBe('worker');
    expect(again.fm.agent('wren')?.role).toBe('lead');
  });

  it('resolves an old deleted team ID by repository when explicitly reassigning', () => {
    const { fm } = setup();
    const task = fm.tasks.create({ title: 'legacy stale team', createdBy: 'marlow', teamId: 'deleted' });
    expect(() => fm.taskAction(task.id, 'reassign', 'kit')).not.toThrow();
    expect(task.assignee).toBe('kit');
  });
});

describe('goal removal preserves dependency identity', () => {
  it('retains a finished prerequisite goal while another goal references its tasks', async () => {
    const h = setup();
    const a = h.fm.createGoal('prerequisite');
    const first = h.fm.tasks.create({ title: 'done', createdBy: 'marlow', goalId: a.id });
    h.fm.tasks.setStatus(first.id, 'done', { force: true });
    h.fm.setGoal(a.id, { status: 'done' });
    const b = h.fm.createGoal('dependent');
    const next = h.fm.tasks.create({ title: 'next', createdBy: 'marlow', goalId: b.id, deps: [first.id] });
    const result = await send(h, { v: 1, type: 'goal.action', action: 'remove', goalId: a.id });
    expect(result?.removed).toBe(0);
    expect(String(result?.lines)).toContain(next.id);
    expect(h.fm.tasks.depsSatisfied(next)).toBe(true);
    expect(h.fm.goal(a.id)).toBeDefined();
  });

  it('clears finished dependency chains together without leaving dangling references', async () => {
    const h = setup();
    const a = h.fm.createGoal('first');
    const first = h.fm.tasks.create({ title: 'done', createdBy: 'marlow', goalId: a.id });
    h.fm.tasks.setStatus(first.id, 'done', { force: true });
    h.fm.setGoal(a.id, { status: 'done' });
    const b = h.fm.createGoal('second');
    const second = h.fm.tasks.create({ title: 'done too', createdBy: 'marlow', goalId: b.id, deps: [first.id] });
    h.fm.tasks.setStatus(second.id, 'done', { force: true });
    h.fm.setGoal(b.id, { status: 'done' });
    const result = await send(h, { v: 1, type: 'goal.action', action: 'clear' });
    expect(result?.removed).toBe(2);
    expect(h.fm.tasks.list()).toEqual([]);
  });

  it('retains transitive finished prerequisites when an outside task needs the chain', async () => {
    const h = setup();
    const a = h.fm.createGoal('first');
    const first = h.fm.tasks.create({ title: 'done', createdBy: 'marlow', goalId: a.id });
    h.fm.tasks.setStatus(first.id, 'done', { force: true });
    h.fm.setGoal(a.id, { status: 'done' });
    const b = h.fm.createGoal('second');
    const second = h.fm.tasks.create({ title: 'done too', createdBy: 'marlow', goalId: b.id, deps: [first.id] });
    h.fm.tasks.setStatus(second.id, 'done', { force: true });
    h.fm.setGoal(b.id, { status: 'done' });
    const outside = h.fm.tasks.create({ title: 'no goal', createdBy: 'marlow', deps: [second.id] });
    const result = await send(h, { v: 1, type: 'goal.action', action: 'clear' });
    expect(result?.removed).toBe(0);
    expect(h.fm.tasks.depsSatisfied(outside)).toBe(true);
    expect(h.fm.tasks.get(first.id)).toBeDefined();
    expect(h.fm.tasks.get(second.id)).toBeDefined();
  });
});
