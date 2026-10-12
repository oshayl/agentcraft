// The turn deadline measures work, not waiting. Four turns died in one night because the 45
// minute clock kept running while the agent sat at an unanswered permission prompt - one of them
// with its tests already passing - so the rule it encodes is: a held clock never expires, and
// what is left of it is what the agent had left when it stopped working.
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TurnClock } from '../src/agents/team.js';

describe('TurnClock', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('does not expire while the agent is waiting on the user', () => {
    const expire = vi.fn();
    const clock = new TurnClock(45 * 60_000, expire);
    vi.advanceTimersByTime(5 * 60_000); // five minutes of work
    const release = clock.hold(); // permission prompt goes up
    vi.advanceTimersByTime(10 * 60 * 60_000); // nobody is awake for ten hours
    expect(expire).not.toHaveBeenCalled();
    release();
    // 40 minutes of the budget were left when it stopped working, and they still are
    vi.advanceTimersByTime(39 * 60_000);
    expect(expire).not.toHaveBeenCalled();
    vi.advanceTimersByTime(2 * 60_000);
    expect(expire).toHaveBeenCalledTimes(1);
  });

  it('expires on working time alone', () => {
    const expire = vi.fn();
    new TurnClock(60_000, expire);
    vi.advanceTimersByTime(61_000);
    expect(expire).toHaveBeenCalledTimes(1);
  });

  it('keeps holding until the last wait is released', () => {
    const expire = vi.fn();
    const clock = new TurnClock(60_000, expire);
    const first = clock.hold();
    const second = clock.hold(); // a second prompt opens under the first
    first();
    vi.advanceTimersByTime(10 * 60_000);
    expect(expire).not.toHaveBeenCalled(); // the second is still open
    second();
    vi.advanceTimersByTime(61_000);
    expect(expire).toHaveBeenCalledTimes(1);
  });

  it('ignores a release called twice, so one wait cannot lift another wait\'s hold', () => {
    const expire = vi.fn();
    const clock = new TurnClock(60_000, expire);
    const first = clock.hold();
    clock.hold(); // never released
    first();
    first();
    vi.advanceTimersByTime(10 * 60_000);
    expect(expire).not.toHaveBeenCalled();
  });

  it('gives a turn a minute back, so answering never expires it on the spot', () => {
    const expire = vi.fn();
    const clock = new TurnClock(60_000, expire);
    vi.advanceTimersByTime(59_000); // almost all of it spent working
    const release = clock.hold();
    release();
    vi.advanceTimersByTime(30_000);
    expect(expire).not.toHaveBeenCalled();
    vi.advanceTimersByTime(31_000);
    expect(expire).toHaveBeenCalledTimes(1);
  });

  it('stops for good when the turn ends', () => {
    const expire = vi.fn();
    const clock = new TurnClock(60_000, expire);
    const release = clock.hold();
    clock.stop();
    release(); // the engine returned while a prompt was still up
    vi.advanceTimersByTime(10 * 60_000);
    expect(expire).not.toHaveBeenCalled();
  });
});
