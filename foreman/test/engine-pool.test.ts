// One engine's concurrency limit must not be spent on another engine's workers. Codex refuses a
// fifth concurrent agent; when that refusal was charged to the whole roster, four Claude workers
// sat idle for a limit that was never theirs, and adding agents bought nothing.
import { describe, expect, it } from 'vitest';
import { ENGINE_POOL, mayStartWorker } from '../src/agents/team.js';

describe('engine pools', () => {
  it('holds codex to the four agents it allows', () => {
    expect(ENGINE_POOL.codex).toBe(4);
  });

  it('states no limit for claude, so the global cap is the only ceiling', () => {
    expect(ENGINE_POOL.claude).toBeUndefined();
  });
});

describe('mayStartWorker', () => {
  const base = { maxConcurrent: 8, teams: 1, running: 0, runningForTeam: 0, othersWaiting: false };

  it('stops at the global cap', () => {
    expect(mayStartWorker({ ...base, running: 8 })).toBe(false);
    expect(mayStartWorker({ ...base, running: 7 })).toBe(true);
  });

  it('is the old rule while there is one team', () => {
    expect(mayStartWorker({ ...base, running: 7, runningForTeam: 7 })).toBe(true);
  });

  it('guarantees each team its share when another team is waiting', () => {
    const two = { ...base, teams: 2, maxConcurrent: 8, running: 5 };
    expect(mayStartWorker({ ...two, runningForTeam: 4, othersWaiting: true })).toBe(false);
    expect(mayStartWorker({ ...two, runningForTeam: 3, othersWaiting: true })).toBe(true);
  });

  it('lends the spare slots out when nobody else wants them', () => {
    const two = { ...base, teams: 2, maxConcurrent: 8, running: 7, runningForTeam: 7 };
    expect(mayStartWorker({ ...two, othersWaiting: false })).toBe(true);
  });

  it('never starves a team when the cap divides badly', () => {
    // floor(3/4) is 0: a share of none would mean a team that can never start at all
    expect(mayStartWorker({ maxConcurrent: 3, teams: 4, running: 0, runningForTeam: 0, othersWaiting: true })).toBe(true);
  });
});
