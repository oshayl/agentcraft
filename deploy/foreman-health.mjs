#!/usr/bin/env node
// Only a summary leaves this process; the bridge credential is read privately from disk.
import fs from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
const release = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(path.join(release, 'foreman/package.json'));
const WebSocket = require('ws');
const token = fs.readFileSync(process.env.AGENTCRAFT_BRIDGE_TOKEN_FILE ?? '/etc/agentcraft/bridge-token', 'utf8').trim();
const port = Number(process.env.AGENTCRAFT_PORT ?? 7878);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('invalid Foreman port');
const ws = new WebSocket(`ws://127.0.0.1:${port}`, { headers: { Authorization: 'Bearer ' + token } });
const timer = setTimeout(() => { ws.terminate(); console.error('Foreman readiness timed out'); process.exitCode = 1; }, 8000);
ws.once('open', () => ws.send(JSON.stringify({ v: 1, type: 'hello', protocol: 1, modVersion: 'health', client: 'health' })));
ws.once('error', () => { clearTimeout(timer); console.error('Foreman unavailable or bridge authentication refused'); process.exitCode = 1; });
ws.on('message', raw => {
  const msg = JSON.parse(raw.toString());
  if (msg.type !== 'snapshot') return;
  clearTimeout(timer);
  const activeGoals = (msg.goals ?? []).filter(g => !['done', 'failed', 'cancelled'].includes(g.status)).length;
  const activeAgents = (msg.agents ?? []).filter(a => ['thinking', 'reading', 'editing', 'running', 'testing', 'waiting_user'].includes(a.state)).length;
  console.log(JSON.stringify({ backend: msg.foreman.backend, auth: msg.foreman.auth, activeGoals, activeAgents, waitingDecisions: (msg.decisions ?? []).filter(d => d.status === 'open').length }));
  const expected = process.argv.includes('--allow-sim') ? ['sim', 'codex'] : ['codex'];
  if (!expected.includes(msg.foreman.backend) || msg.foreman.auth !== 'ok') process.exitCode = 1;
  else if (process.argv.includes('--require-idle') && (activeGoals || activeAgents)) process.exitCode = 75;
  ws.close();
});
