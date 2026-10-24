import fs from 'node:fs';
import { timingSafeEqual } from 'node:crypto';
import type { IncomingMessage } from 'node:http';

/** Deployment secrets stay in service files, never command arguments or protocol messages. */
export function bridgeToken(env: NodeJS.ProcessEnv = process.env): string | undefined {
  const file = env.AGENTCRAFT_BRIDGE_TOKEN_FILE;
  if (!file) {
    if (env.AGENTCRAFT_REQUIRE_BRIDGE_AUTH === '1') throw new Error('AGENTCRAFT_BRIDGE_TOKEN_FILE is required');
    return undefined;
  }
  const token = fs.readFileSync(file, 'utf8').trim();
  if (!/^[A-Za-z0-9_-]{32,256}$/.test(token)) throw new Error('bridge token must contain 32-256 URL-safe characters');
  return token;
}

export function bridgeAuthorized(req: IncomingMessage, token: string | undefined): boolean {
  if (!token) return true; // existing local development mode
  const header = req.headers.authorization;
  if (typeof header !== 'string' || !header.startsWith('Bearer ')) return false;
  const supplied = Buffer.from(header.slice(7));
  const expected = Buffer.from(token);
  return supplied.length === expected.length && timingSafeEqual(supplied, expected);
}
