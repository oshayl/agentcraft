import fs from 'node:fs';
import path from 'node:path';
import type { IncomingMessage } from 'node:http';
import { describe, expect, it } from 'vitest';
import WebSocket from 'ws';
import { bridgeAuthorized, bridgeToken } from '../src/bridge-auth.js';
import { RequestLedger } from '../src/request-ledger.js';
import { ForemanServer } from '../src/server.js';
import type { Foreman } from '../src/foreman.js';
import type { ClientMessage, Outbound } from '../src/protocol.js';
import { silentLogger } from '../src/context.js';
import { rmrf, tempDir, until } from './helpers.js';

const token = 'test-only-bridge-credential-with-32-characters';
const request: ClientMessage = { v: 1, type: 'goal.submit', id: 'stable-job-1', text: 'Make the change' };

describe('dedicated server deployment', () => {
  it('requires a valid service credential without exposing it in errors', () => {
    const dir = tempDir();
    try {
      expect(() => bridgeToken({ AGENTCRAFT_REQUIRE_BRIDGE_AUTH: '1' })).toThrow('TOKEN_FILE');
      const file = path.join(dir, 'token');
      fs.writeFileSync(file, token);
      expect(bridgeToken({ AGENTCRAFT_BRIDGE_TOKEN_FILE: file })).toBe(token);
      fs.writeFileSync(file, 'short');
      expect(() => bridgeToken({ AGENTCRAFT_BRIDGE_TOKEN_FILE: file })).toThrow('32-256');
      const req = (authorization?: string) => ({ headers: { authorization } }) as IncomingMessage;
      expect(bridgeAuthorized(req(), token)).toBe(false);
      expect(bridgeAuthorized(req('Bearer wrong'), token)).toBe(false);
      expect(bridgeAuthorized(req('Bearer ' + token), token)).toBe(true);
    } finally { rmrf(dir); }
  });

  it('never repeats a mutation whose outcome was lost across process restart', () => {
    const dir = tempDir();
    try {
      const file = path.join(dir, 'requests.jsonl');
      const first = new RequestLedger(file);
      expect(first.begin(request)).toBeUndefined();
      const restarted = new RequestLedger(file);
      expect(restarted.begin(request)).toMatchObject({ ok: false, error: expect.stringContaining('unknown') });
      expect(restarted.begin({ ...request, text: 'different work' })).toMatchObject({ ok: false, error: expect.stringContaining('different') });
      const ack = { type: 'ack', re: request.id!, ok: true, result: { goalId: 'g1' } } as const;
      restarted.complete(ack);
      expect(new RequestLedger(file).begin(request)).toEqual(ack);
      fs.appendFileSync(file, '{"partial');
      expect(() => new RequestLedger(file)).toThrow();
    } finally { rmrf(dir); }
  });

  it('keeps accepted work running with zero clients and replays its result after reconnect', async () => {
    const dir = tempDir();
    let runs = 0;
    let finished = false;
    let finishWork!: () => void;
    const work = new Promise<void>((resolve) => { finishWork = resolve; });
    const fm = {
      subscribe: () => () => {},
      handle: async (msg: ClientMessage, reply: (out: Outbound) => void) => {
        runs++;
        await work;
        finished = true;
        reply({ type: 'ack', re: msg.id!, ok: true, result: { goalId: 'g1' } });
      },
    } as unknown as Foreman;
    const server = new ForemanServer(fm, { host: '127.0.0.1', port: 0, bridgeToken: token, requestLedger: path.join(dir, 'requests.jsonl'), log: silentLogger });
    const port = await server.start();
    const sockets: WebSocket[] = [];
    const connect = (secret?: string) => new Promise<WebSocket>((resolve, reject) => {
      const ws = new WebSocket(`ws://127.0.0.1:${port}`, secret ? { headers: { Authorization: 'Bearer ' + secret } } : {});
      sockets.push(ws);
      ws.once('open', () => resolve(ws));
      ws.once('error', reject);
    });
    try {
      await expect(connect()).rejects.toThrow(/401/);
      await expect(connect('incorrect')).rejects.toThrow(/401/);
      const first = await connect(token);
      first.send(JSON.stringify(request));
      await until(() => runs === 1);
      first.close();
      await until(() => server.clientCount === 0);
      expect(finished).toBe(false);
      finishWork();
      await until(() => finished);
      const second = await connect(token);
      const result = new Promise<Record<string, unknown>>((resolve) => second.once('message', (raw) => resolve(JSON.parse(raw.toString()))));
      second.send(JSON.stringify(request));
      expect(await result).toMatchObject({ type: 'ack', ok: true, result: { goalId: 'g1' } });
      expect(runs).toBe(1);
    } finally {
      finishWork();
      for (const ws of sockets) ws.terminate();
      await server.stop();
      rmrf(dir);
    }
  });
});
