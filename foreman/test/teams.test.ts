// Teams. Each of these fails without the change it guards, and names the thing that goes wrong
// when it does: work filed against the wrong lead, two teams in one worktree, one team's queue
// eating the whole concurrency budget, one client's notes in another client's context.
import { afterEach, describe, expect, it } from 'vitest';
import { mayStartWorker } from '../src/agents/team.js';
import { makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

let h: Harness;
let home: string;
afterEach(async () => {
  await h?.fm.close();
  rmrf(home);
});

function setup(args: string[] = []): Harness {
  home = tempDir();
  h = makeForeman(home, ['--backend', 'sim', ...args]);
  return h;
}

/** Register a repo id without touching git: these tests are about ownership, not checkouts. */
function fakeRepo(h: Harness, id: string): void {
  h.fm.store.data.repos.push({ id, name: id, path: `/tmp/${id}`, branch: 'main', dirty: false, worktrees: [], ci: 'unknown' });
}

describe('teams', () => {
  it('seeds one team from the roster, so a state file written before teams behaves as it did', () => {
    const { fm } = setup(['--workers', 'juniper,kit']);
    expect(fm.teams.list().length).toBe(1);
    const t = fm.teams.primary();
    expect(t.lead).toBe('marlow');
    expect(t.members).toEqual(['juniper', 'kit']);
    // not every face in the cast: --workers is who is on shift, and rostering everybody would
    // quietly put agents the user left off back to work
    expect(t.members).not.toContain('tove');
    expect(fm.agent('kit')!.teamId).toBe(t.id);
  });

  it('gives a repo to exactly one team: naming it takes it off whoever had it', () => {
    const { fm } = setup();
    fakeRepo(h, 'movement');
    fakeRepo(h, 'travis-murphy');
    fm.teams.adopt(['movement', 'travis-murphy']);
    expect(fm.teams.primary().repos).toEqual(['movement', 'travis-murphy']);

    fm.teams.create({ id: 'clients', name: 'Clients', lead: 'juniper', repos: ['travis-murphy'] });
    expect(fm.teams.primary().repos).toEqual(['movement']);
    expect(fm.teams.get('clients')!.repos).toEqual(['travis-murphy']);
    // one owner, always: this is the whole reason two teams cannot land in the same worktree
    expect(fm.teams.list().filter((t) => t.repos.includes('travis-murphy')).length).toBe(1);
  });

  it('gives an agent to exactly one team, and refuses to strand a team without a lead', () => {
    const { fm } = setup(['--workers', 'juniper,kit,wren']);
    fm.teams.create({ id: 'clients', name: 'Clients', lead: 'juniper', members: ['wren'] });
    expect(fm.teams.primary().members).toEqual(['kit']); // juniper and wren moved across
    expect(fm.teamOf('wren').id).toBe('clients');
    expect(fm.agent('juniper')!.teamId).toBe('clients');
    // juniper leads clients; taking her as a worker elsewhere would leave that team leaderless
    expect(() => fm.teams.update(fm.teams.primary().id, { members: ['kit', 'juniper'] })).toThrow(/leads "clients"/);
  });

  it("routes a goal to the lead of the team that owns its repo, not to the newest goal's lead", async () => {
    const { fm } = setup();
    fakeRepo(h, 'movement');
    fakeRepo(h, 'travis-murphy');
    fm.teams.adopt(['movement', 'travis-murphy']);
    fm.teams.create({ id: 'clients', name: 'Clients', lead: 'juniper', repos: ['travis-murphy'] });

    const mine = fm.createGoal('ship the release', 'movement');
    const theirs = fm.createGoal('fix the footer', 'travis-murphy');
    expect(mine.teamId).toBe(fm.teams.primary().id);
    expect(theirs.teamId).toBe('clients');
    // and each lead's "current goal" is its own team's, never whatever was typed last
    expect(fm.currentGoalFor('clients')!.id).toBe(theirs.id);
    expect(fm.currentGoalFor(fm.teams.primary().id)!.id).toBe(mine.id);
  });

  it('keeps one team out of the other team notes', () => {
    const { fm } = setup(['--workers', 'juniper,kit']);
    fm.teams.create({ id: 'clients', name: 'Clients', lead: 'juniper' });
    const studio = fm.teams.primary().id;
    fm.memory.write({ scope: studio, title: 'Deploy is blocked on billing', body: 'x', author: 'marlow' });
    fm.memory.write({ scope: 'clients', title: 'Footer copy approved', body: 'y', author: 'juniper' });
    fm.memory.write({ scope: 'shared', title: 'House rules', body: 'z', author: 'marlow' });

    const kitSees = fm.memory.visibleTo('kit', fm.teamOf('kit').id).map((e) => e.title);
    const juniperSees = fm.memory.visibleTo('juniper', fm.teamOf('juniper').id).map((e) => e.title);
    // a lead that reads another client's "blocked on billing" will act on it: that is the bug
    expect(juniperSees).not.toContain('Deploy is blocked on billing');
    expect(kitSees).not.toContain('Footer copy approved');
    // the house notes still reach everyone, and so do the notes written before teams existed
    expect(kitSees).toContain('House rules');
    expect(juniperSees).toContain('House rules');
  });

  it('disbanding a team leaves nothing stranded', () => {
    const { fm } = setup(['--workers', 'juniper,kit']);
    fakeRepo(h, 'travis-murphy');
    fm.teams.adopt(['travis-murphy']);
    fm.teams.create({ id: 'clients', name: 'Clients', lead: 'juniper', repos: ['travis-murphy'] });
    fm.teams.remove('clients');
    expect(fm.teams.list().length).toBe(1);
    expect(fm.teams.primary().repos).toContain('travis-murphy');
    expect(fm.teams.primary().members).toContain('juniper'); // the lead comes back as a worker
    expect(() => fm.teams.remove(fm.teams.primary().id)).toThrow(/last team/);
  });

  it('survives a restart: teams, membership and ownership come back from the state file', async () => {
    const { fm } = setup(['--workers', 'juniper,kit']);
    fakeRepo(h, 'travis-murphy');
    fm.teams.adopt(['travis-murphy']);
    fm.teams.create({ id: 'clients', name: 'Clients', lead: 'juniper', repos: ['travis-murphy'], floor: 1 });
    await fm.close();

    const again = makeForeman(home, ['--backend', 'sim', '--workers', 'juniper,kit']);
    h = again;
    expect(again.fm.teams.list().map((t) => t.id)).toEqual(['studio', 'clients']);
    expect(again.fm.teams.get('clients')!.repos).toEqual(['travis-murphy']);
    expect(again.fm.teams.get('clients')!.floor).toBe(1);
    expect(again.fm.teamOf('juniper').id).toBe('clients');
  });
});

describe('sharing the concurrency budget', () => {
  const s = (o: Partial<Parameters<typeof mayStartWorker>[0]>) =>
    mayStartWorker({ maxConcurrent: 3, teams: 2, running: 0, runningForTeam: 0, othersWaiting: false, ...o });

  it('never goes over the global cap, however many teams there are', () => {
    expect(s({ running: 3 })).toBe(false);
    expect(s({ teams: 4, running: 3, runningForTeam: 0, othersWaiting: true })).toBe(false);
  });

  it('is the old rule exactly while there is one team', () => {
    expect(s({ teams: 1, running: 2, runningForTeam: 2 })).toBe(true);
    expect(s({ teams: 1, running: 3, runningForTeam: 3 })).toBe(false);
  });

  it("does not let one team's long queue starve another team's single task", () => {
    // 3 slots, 2 teams: a share of 1 each. The studio already holds 2 and the clients team is
    // waiting, so the studio does not take the third - that is the one the clients team gets.
    expect(s({ running: 2, runningForTeam: 2, othersWaiting: true })).toBe(false);
    expect(s({ running: 2, runningForTeam: 0, othersWaiting: false })).toBe(true);
  });

  it('lends the spare slots out while nobody else wants them', () => {
    // same position, but nothing is queued for the other team: idle capacity is worse than unfair
    expect(s({ running: 2, runningForTeam: 2, othersWaiting: false })).toBe(true);
  });

  it('guarantees every team one slot even when there are more teams than slots', () => {
    expect(s({ maxConcurrent: 2, teams: 5, running: 1, runningForTeam: 0, othersWaiting: true })).toBe(true);
    expect(s({ maxConcurrent: 2, teams: 5, running: 1, runningForTeam: 1, othersWaiting: true })).toBe(false);
  });
});
