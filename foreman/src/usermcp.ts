// The user's own MCP servers, for agents that have the `mcp` capability.
//
// The Codex engine does not need this: Codex reads ~/.codex/config.toml itself, and AgentCraft
// only has to stop disabling what it finds there (see codex/engine.ts). The Claude Agent SDK
// runs with `settingSources: []` so that a project's settings cannot change an agent's rules,
// which also means it loads no MCP servers of its own. Those are passed explicitly instead.
//
// Read as data, never executed here: each entry is handed to the SDK, which spawns it. A server
// the user configured is as trusted as the user's own shell; a server's *output* is not, and
// reaches the agent as tool results that the permission policy still governs
// (policy.ts asks before any `mcp__<server>__*` tool that is not AgentCraft's own).

import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

/** Shape the Claude Agent SDK accepts; we pass entries through untouched. */
export type UserMcpServers = Record<string, unknown>;

/** `~/.claude.json`'s global `mcpServers`, or {} if the file is missing or unreadable. */
export function readUserMcpServers(home: string = os.homedir()): UserMcpServers {
  const file = path.join(home, '.claude.json');
  let raw: string;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch {
    return {};
  }
  try {
    const parsed = JSON.parse(raw) as { mcpServers?: UserMcpServers };
    const servers = parsed?.mcpServers;
    if (!servers || typeof servers !== 'object' || Array.isArray(servers)) return {};
    // Never let a user entry shadow AgentCraft's own in-process server.
    const out: UserMcpServers = {};
    for (const [name, cfg] of Object.entries(servers)) if (name !== 'agentcraft' && cfg && typeof cfg === 'object') out[name] = cfg;
    return out;
  } catch {
    return {};
  }
}
