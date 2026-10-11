// Per-agent capabilities: how far outside its worktree an agent may reach.
//
// AgentCraft's default is a closed world: no git transports, no pushes, the user's own MCP
// servers and plugins switched off (codex/engine.ts disables every one it finds), and no web
// search. That default suits a team of autonomous agents working in parallel in someone else's
// repositories, but it also means an agent asked to publish a repository can only write a
// handoff document describing what the user should run.
//
// These flags open that world deliberately, per role, per agent or per repository, from
// ~/.agentcraft/config.json:
//
//   "capabilities": {
//     "lead":    { "network": true, "push": true, "mcp": true, "web": true },
//     "worker":  { "mcp": true, "web": true },
//     "byAgent": { "juniper": { "network": true } },
//     "byRepo":  { "agentcraft": { "lead": "full" } }
//   }
//
// `byRepo` can also be set while the game runs (the `caps.set` message, `/caps` in the console,
// `agentcraft --caps`), which is how a repository the user trusts gets more reach than the rest.
//
// Implications, because some of these cannot mean anything on their own:
//   `push`  -> `network`   git cannot push over a transport it is not allowed to use
//   `force` -> `push`
//   `full`  -> `write`, `push`, `network`, `force`
//
// What no capability opens, in any combination:
//   - merging into one of the user's branches still needs an answered merge decision
//   - agents still never sign with the user's key
//   - the policy still shows the user anything it cannot allow by itself (permission mode `ask`)
//
// `full` is the exception to the rest of this file: it turns the Codex sandbox off entirely
// (`danger-full-access`), so the agent reaches the whole machine the way the user's own terminal
// does. Nothing below the policy confines it any more. See below.

/** Every capability name, in the order they are reported. */
export const CAPABILITY_NAMES = ['network', 'push', 'mcp', 'web', 'write', 'force', 'full'] as const;
export type CapabilityName = (typeof CAPABILITY_NAMES)[number];

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
  /**
   * Full access: no sandbox at all. The Codex thread runs `danger-full-access`, so the agent
   * reads and writes anywhere the user can - their home directory, their dotfiles, their other
   * repositories - and runs anything, exactly like `codex` in a terminal. Implies write, push,
   * network and force, because a sandbox is the only thing those were layered behind.
   *
   * After this the permission policy is the only check left, and in permission mode `auto` it
   * approves everything it would otherwise ask about. Grant it where that is what you want (your
   * own machine, your own repositories) and not as a way to unblock one command.
   */
  full: boolean;
}

/** The default: everything closed. */
export const CLOSED: AgentCapabilities = Object.freeze({ network: false, push: false, mcp: false, web: false, write: false, force: false, full: false });

/** Capabilities for the lead and the workers of one repository. */
export interface RoleCapabilities {
  lead?: Partial<AgentCapabilities>;
  worker?: Partial<AgentCapabilities>;
}

export interface CapabilityConfig extends RoleCapabilities {
  /** per-agent overrides, applied over the role's */
  byAgent?: Record<string, Partial<AgentCapabilities>>;
  /** per-repository overrides, applied over everything else (the most specific wins) */
  byRepo?: Record<string, RoleCapabilities>;
}

/**
 * Capabilities for one agent working in one repository:
 * CLOSED <- role <- byAgent[agentId] <- byRepo[repoId][role], then the implications above.
 *
 * The repository comes last on purpose: it is the runtime toggle, so "full access in this one
 * repository" has to win over whatever the roles say in the config file.
 */
export function resolveCapabilities(cfg: CapabilityConfig | undefined, role: 'lead' | 'worker', agentId?: string, repoId?: string): AgentCapabilities {
  const caps: AgentCapabilities = {
    ...CLOSED,
    ...(role === 'lead' ? cfg?.lead : cfg?.worker),
    ...(agentId ? cfg?.byAgent?.[agentId] : undefined),
    ...(repoId ? cfg?.byRepo?.[repoId]?.[role] : undefined),
  };
  if (caps.full) caps.write = caps.force = true;
  if (caps.force) caps.push = true;
  if (caps.push) caps.network = true;
  return caps;
}

/** A short description for logs and the HUD, e.g. "network, push, mcp". */
export function describeCapabilities(caps: AgentCapabilities): string {
  const on = CAPABILITY_NAMES.filter((k) => caps[k]);
  return on.length ? on.join(', ') : 'closed';
}

/** `cfg` with runtime per-repository overrides merged over the ones from the config file. */
export function withRepoCapabilities(cfg: CapabilityConfig | undefined, byRepo: Record<string, RoleCapabilities> | undefined): CapabilityConfig {
  if (!byRepo || !Object.keys(byRepo).length) return cfg ?? {};
  const merged: Record<string, RoleCapabilities> = { ...cfg?.byRepo };
  for (const [repoId, roles] of Object.entries(byRepo)) {
    merged[repoId] = {
      ...(merged[repoId]?.lead || roles.lead ? { lead: { ...merged[repoId]?.lead, ...roles.lead } } : {}),
      ...(merged[repoId]?.worker || roles.worker ? { worker: { ...merged[repoId]?.worker, ...roles.worker } } : {}),
    };
  }
  return { ...cfg, byRepo: merged };
}
