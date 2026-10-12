import fs from 'node:fs';
import path from 'node:path';
import { afterAll, describe, expect, it } from 'vitest';
import { DevActionMsg, type ClientMessage } from '../src/protocol.js';
import type { Outbound } from '../src/protocol.js';
import { DEV_ACTIONS } from '../src/selfhost.js';
import { makeForeman, rmrf, tempDir, until } from './helpers.js';

const cleanup: string[] = [];
afterAll(() => {
  for (const d of cleanup) rmrf(d);
});

/** A checkout whose two verifications are slow no-ops, so the test measures timing, not npm. */
function slowCheckout(seconds: number): string {
  const root = tempDir();
  cleanup.push(root);
  fs.mkdirSync(path.join(root, 'foreman'), { recursive: true });
  fs.mkdirSync(path.join(root, 'mod'), { recursive: true });
  fs.writeFileSync(
    path.join(root, 'foreman', 'package.json'),
    JSON.stringify({ name: 'slow', scripts: { check: `sleep ${seconds}; echo "Tests  7 passed (7)"` } }),
  );
  fs.writeFileSync(path.join(root, 'mod', 'gradlew'), `sleep ${seconds}\n`);
  return root;
}

describe('the in-game dev loop', () => {
  it('acknowledges a slow step at once and reports the result through the feed', async () => {
    // The bug: the ack waited for the work. `npm run check` is about 35 seconds and a client
    // gives up on an unanswered message after 15, so every check "failed" from in game while
    // succeeding on the machine.
    const home = tempDir();
    cleanup.push(home);
    const h = makeForeman(home, ['--backend', 'sim', '--no-ambient']);
    h.cfg.projectRoot = slowCheckout(3);

    const acks: Outbound[] = [];
    const t0 = Date.now();
    await h.fm.handle({ v: 1, id: 'c1', type: 'dev.action', action: 'check' } as ClientMessage, (m) => acks.push(m as Outbound));
    const waited = Date.now() - t0;

    expect(waited).toBeLessThan(1500);
    const ack = acks.find((m) => m.type === 'ack');
    expect(ack).toMatchObject({ ok: true, result: { lines: [expect.stringMatching(/Running the Foreman/)] } });

    // and the answer still arrives, late, where the console will show it
    const feed = () => h.fm.store.data.feed.map((f) => f.text).join('\n');
    await until(() => /npm run check passed/.test(feed()), 20_000);
    expect(feed()).toMatch(/7 passed/);
  });

  it('offers exactly the steps the wire allows', () => {
    // The enum in protocol.ts is the guard - an unknown action never reaches the handler - and
    // it is written out by hand, so this is what notices when the two lists drift apart.
    const wire = DevActionMsg.shape.action.options;
    expect([...wire].sort()).toEqual([...DEV_ACTIONS].sort());
  });

  it('rejects a step it does not have', () => {
    expect(DevActionMsg.safeParse({ v: 1, type: 'dev.action', action: 'rm -rf' }).success).toBe(false);
  });
});
