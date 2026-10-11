// Per-agent capabilities: how far outside its worktree an agent may reach.
//
// AgentCraft's default is a closed world: no git transports, no pushes, the user's own MCP
// servers and plugins switched off (codex/engine.ts disables every one it finds), and no web
// search. That default suits a team of autonomous agents working in parallel in someone else's
// repositories, but it also means an agent asked to publish a repository can only write a
// handoff document describing what the user should run.
//
// These flags open that world deliberately, per role or per agent, from ~/.agentcraft/config.json:
//
//   "capabilities": {
//     "lead":    { "network": true, "push": true, "mcp": true, "web": true },
//     "worker":  { "mcp": true, "web": true },
//     "byAgent": { "juniper": { "network": true } }
//   }
//
// `push` implies `network`: git cannot push over a transport it is not allowed to use.
//
// What no capability opens, in any combination:
//   - merging into one of the user's branches still needs an answered merge decision
//   - force pushes, branch deletes, `--mirror` and pushes to a repository's base branch stay
//     denied, so an agent can publish its own work but cannot rewrite or remove the user's
//   - agents still never sign with the user's key, and git still cannot walk up out of the
//     worktree (see gitsafety.ts: those protections are about identity and scope, not network)

export interface AgentCapabilities {
  /** git transports work, and the Codex sandbox gets network access */
  network: boolean;
  /** `git push` of a non-protected branch is allowed (implies network) */
  push: boolean;
  /** the user's own MCP servers and plugins stay enabled for this agent */
  mcp: boolean;
  /** WebSearch and WebFetch are available */
  web: boolean;
  /**
   * The lead may change files and run commands that write, like a worker. Without this the lead
   * is read-only: it inspects the user's checkout and delegates the work.
   */
  write: boolean;
  /**
   * Git's destructive operations are allowed: force push, branch delete, --mirror/--all, and
   * pushes to a base branch. This is what a plain terminal can do; AgentCraft withholds it by
   * default so an autonomous agent cannot rewrite or remove the user's work.
   */
  force: boolean;
}

/** The default: everything closed. */
export const CLOSED: AgentCapabilities = Object.freeze({ network: false, push: false, mcp: false, web: false, write: false, force: false });

export interface CapabilityConfig {
  lead?: Partial<AgentCapabilities>;
  worker?: Partial<AgentCapabilities>;
  /** per-agent overrides, applied over the role's */
  byAgent?: Record<string, Partial<AgentCapabilities>>;
}

/** Capabilities for one agent: CLOSED <- role <- byAgent[agentId], with push implying network. */
export function resolveCapabilities(cfg: CapabilityConfig | undefined, role: 'lead' | 'worker', agentId?: string): AgentCapabilities {
  const caps: AgentCapabilities = { ...CLOSED, ...(role === 'lead' ? cfg?.lead : cfg?.worker), ...(agentId ? cfg?.byAgent?.[agentId] : undefined) };
  if (caps.push) caps.network = true;
  if (caps.force) caps.push = caps.network = true;
  return caps;
}

/** A short description for logs and the HUD, e.g. "network, push, mcp". */
export function describeCapabilities(caps: AgentCapabilities): string {
  const on = (Object.keys(caps) as Array<keyof AgentCapabilities>).filter((k) => caps[k]);
  return on.length ? on.join(', ') : 'closed';
}
