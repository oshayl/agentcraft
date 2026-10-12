// Switching an agent's model from in game. The override wins over config.json, persists, and
// reaches the engine as the model that turn runs - a turn already under way keeps the model it
// started with, so a nameplate never claims work was done on a model it was not.
import { describe, expect, it } from 'vitest';
import { ModelSetMsg } from '../src/protocol.js';

describe('model.set', () => {
  it('accepts a model for one agent, a role, or everyone', () => {
    for (const who of ['ash', 'lead', 'workers', 'all']) {
      expect(ModelSetMsg.safeParse({ v: 1, type: 'model.set', who, model: 'claude-opus-5-5' }).success).toBe(true);
    }
  });

  it('accepts a bare report with neither field', () => {
    expect(ModelSetMsg.safeParse({ v: 1, type: 'model.set' }).success).toBe(true);
  });

  it('accepts "default", which is how an override is dropped', () => {
    expect(ModelSetMsg.safeParse({ v: 1, type: 'model.set', who: 'workers', model: 'default' }).success).toBe(true);
  });

  it('refuses a model name long enough to be a paste of something else', () => {
    expect(ModelSetMsg.safeParse({ v: 1, type: 'model.set', who: 'ash', model: 'x'.repeat(65) }).success).toBe(false);
  });
});
