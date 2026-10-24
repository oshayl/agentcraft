import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { expect, it } from 'vitest';
import { CodexEngine } from '../src/agents/codex/engine.js';
import { makeForeman, rmrf, tempDir } from './helpers.js';

const fake = fileURLToPath(new URL('./fixtures/fake-codex.mjs', import.meta.url));

it('uses the CLI default when no model is configured, while preserving explicit models', async () => {
  const home = tempDir();
  const scenario = path.join(home, 'scenario.json');
  const log = path.join(home, 'requests.jsonl');
  fs.writeFileSync(scenario, JSON.stringify({ config: {}, turns: [{ match: 'probe', steps: [{ say: 'ok' }] }] }));
  const h = makeForeman(home, ['--backend', 'codex']);
  const engine = new CodexEngine(h.fm, h.cfg.codex, { bin: process.execPath, args: [fake] });
  try {
    for (const model of [engine.model('lead'), engine.model('worker'), 'gpt-explicit']) {
      const stats = await engine.runTurn({
        agentId: 'marlow', role: 'lead', cwd: home, prompt: 'probe', instructions: '', model,
        env: { ...process.env, FAKE_CODEX_SCENARIO: scenario, FAKE_CODEX_LOG: log },
        abort: new AbortController(), turn: {} as never, tools: [],
        permission: async () => ({ allow: true }), onProcess: () => {}, onSession: () => {},
      });
      expect(stats.isError).toBe(false);
    }
    const starts = fs.readFileSync(log, 'utf8').trim().split('\n').map(line => JSON.parse(line)).filter(x => x.method === 'thread/start');
    expect(starts).toHaveLength(3);
    expect(starts[0].params).not.toHaveProperty('model');
    expect(starts[1].params).not.toHaveProperty('model');
    expect(starts[2].params.model).toBe('gpt-explicit');
  } finally {
    await h.fm.close();
    rmrf(home);
  }
});
