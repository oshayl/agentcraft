// Teams: a lead, some workers, and the repositories they own.
//
// One Foreman, one HQ, one decision queue - but more than one team inside it. A team is the unit
// of *attention*, not of state: splitting the Foreman per client would mean separate ports,
// separate state.json files and separate services, and you would lose the one pane of glass that
// is the point of the place.
//
// The rule that does the work:
//
//   **A repository belongs to exactly one team.**
//
// Everything else follows from it. A goal is filed against a repo, so the repo says which team
// owns the goal, which lead plans it and which workers may be given its tasks. Two teams can
// never land in the same worktree, because they can never be given tasks in the same repository.
//
// The other reason to split, and the stronger one, is the lead's session thread. A lead carries
// one conversation across a goal; a single lead holding two clients' plans in one thread bleeds
// one into the other, and one compaction loses the state of whichever it was not talking about.
// A lead per team is a thread per team.
//
// An agent belongs to exactly one team too, so "who is free" is always answerable without asking
// which hat someone is wearing today.
import type { Ctx } from './context.js';
import type { Team } from './protocol.js';

export class TeamError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'TeamError';
  }
}

export interface CreateTeamInput {
  id: string;
  name: string;
  lead: string;
  members?: string[];
  repos?: string[];
  color?: string;
  floor?: number;
}

export interface TeamPatch {
  name?: string;
  lead?: string;
  members?: string[];
  repos?: string[];
  color?: string;
  floor?: number;
}

const ID = /^[a-z][a-z0-9-]{0,31}$/;
/** Team colours: far enough apart to tell two floors apart at a glance, and from the agents' own. */
const COLORS = ['#D97757', '#2FA3A0', '#C9A227', '#8FA98B', '#B4553A', '#6F8FB5'];

export class Teams {
  private listeners = new Set<() => void>();

  constructor(private ctx: Ctx) {}

  /** Called after any change to any team, so Agent.teamId and the roster can be kept in step. */
  onChanged(l: () => void): void {
    this.listeners.add(l);
  }

  private get teams(): Team[] {
    return this.ctx.store.data.teams;
  }

  list(): Team[] {
    return this.teams;
  }

  get(id: string): Team | undefined {
    return this.teams.find((t) => t.id === id);
  }

  require(id: string): Team {
    const t = this.get(id);
    if (!t) throw new TeamError(`no team "${id}"`);
    return t;
  }

  /**
   * The team everything unowned falls back to: the first one. Something always has to answer
   * "whose is this?" - an ad-hoc message with no repo, a goal filed before any team owned that
   * repo - and an answer that is sometimes undefined would be checked for in thirty places and
   * forgotten in three.
   */
  primary(): Team {
    const first = this.teams[0];
    if (!first) throw new TeamError('no teams: the Foreman was not seeded');
    return first;
  }

  ofRepo(repoId: string | undefined): Team | undefined {
    return repoId === undefined ? undefined : this.teams.find((t) => t.repos.includes(repoId));
  }

  ofAgent(agentId: string): Team | undefined {
    return this.teams.find((t) => t.lead === agentId || t.members.includes(agentId));
  }

  /** Everyone on a team, the lead first. */
  roster(teamId: string): string[] {
    const t = this.require(teamId);
    return [t.lead, ...t.members];
  }

  /** The lead that plans for a repo (the primary team's, when nobody owns it yet). */
  leadForRepo(repoId: string | undefined): string {
    return (this.ofRepo(repoId) ?? this.primary()).lead;
  }

  isLead(agentId: string): boolean {
    return this.teams.some((t) => t.lead === agentId);
  }

  /** Validate the whole roster before changing any team, including on a rejected command. */
  private validateRoster(id: string, lead: string, members: string[]): void {
    if (!lead) throw new TeamError('a team must have a lead');
    for (const t of this.teams) {
      if (t.id === id) continue;
      if (t.lead === lead) throw new TeamError(`${lead} already leads "${t.id}"`);
      if (members.includes(t.lead)) throw new TeamError(`${t.lead} leads "${t.id}"; give that team a new lead first`);
    }
  }

  create(input: CreateTeamInput): Team {
    if (!ID.test(input.id)) throw new TeamError(`bad team id "${input.id}" (lowercase letters, digits and dashes)`);
    if (this.get(input.id)) throw new TeamError(`team "${input.id}" already exists`);
    this.validateRoster(input.id, input.lead, (input.members ?? []).filter((m) => m !== input.lead));
    const team: Team = {
      id: input.id,
      name: input.name.trim() || input.id,
      lead: input.lead,
      members: [],
      repos: [],
      color: input.color ?? COLORS[this.teams.length % COLORS.length]!,
      floor: input.floor ?? this.teams.length,
    };
    this.teams.push(team);
    // through update(), so a new team takes its lead, members and repos off whoever had them
    return this.update(team.id, {
      lead: input.lead,
      ...(input.members ? { members: input.members } : {}),
      ...(input.repos ? { repos: input.repos } : {}),
    });
  }

  /**
   * Change a team, keeping both exclusivity rules: a repo and an agent each belong to one team.
   * Anything this team takes is taken *from* the team that had it, and that team is published
   * too - a half-applied move (owned by both, owned by neither) is the bug this prevents.
   */
  update(id: string, patch: TeamPatch): Team {
    const team = this.require(id);
    const lead = patch.lead ?? team.lead;
    const members = (patch.members ?? [...team.members, ...(team.lead !== lead ? [team.lead] : [])]).filter((m) => m !== lead);
    this.validateRoster(id, lead, members);
    const touched = new Set<Team>([team]);

    if (patch.name !== undefined) team.name = patch.name.trim() || team.id;
    if (patch.color !== undefined) team.color = patch.color;
    if (patch.floor !== undefined) team.floor = patch.floor;

    if (patch.lead !== undefined) {
      if (!patch.lead) throw new TeamError('a team must have a lead');
      for (const t of this.teams) {
        if (t !== team && t.lead === patch.lead) throw new TeamError(`${patch.lead} already leads "${t.id}"`);
      }
      // the outgoing lead does not vanish: it stays on as a worker, which is also how a
      // one-person team survives having its lead handed to someone else
      if (team.lead && team.lead !== patch.lead && !team.members.includes(team.lead)) team.members.push(team.lead);
      team.lead = patch.lead;
      team.members = team.members.filter((m) => m !== patch.lead);
      for (const t of this.release(patch.lead, team)) touched.add(t);
    }

    if (patch.members !== undefined) {
      const members = [...new Set(patch.members)].filter((m) => m !== team.lead);
      team.members = members;
      for (const m of members) for (const t of this.release(m, team)) touched.add(t);
    }

    if (patch.repos !== undefined) {
      const repos = [...new Set(patch.repos)];
      team.repos = repos;
      for (const r of repos) {
        for (const t of this.teams) {
          if (t !== team && t.repos.includes(r)) {
            t.repos = t.repos.filter((x) => x !== r);
            touched.add(t);
          }
        }
      }
    }

    this.ctx.store.markDirty();
    for (const l of this.listeners) l();
    for (const t of touched) this.ctx.emit({ type: 'team.upsert', team: structuredClone(t) });
    return team;
  }

  /** Take an agent off every team but {@code keep}; returns the teams that changed. */
  private release(agentId: string, keep: Team): Team[] {
    const changed: Team[] = [];
    for (const t of this.teams) {
      if (t === keep) continue;
      if (t.lead === agentId) throw new TeamError(`${agentId} leads "${t.id}"; give that team a new lead first`);
      if (t.members.includes(agentId)) {
        t.members = t.members.filter((m) => m !== agentId);
        changed.push(t);
      }
    }
    return changed;
  }

  /** Disband a team: its repos and workers go to {@code into} (the primary team by default). */
  remove(id: string, into?: string): void {
    const team = this.require(id);
    if (this.teams.length === 1) throw new TeamError('the last team cannot be disbanded');
    const target = into ? this.require(into) : this.teams.find((t) => t !== team)!;
    if (target === team) throw new TeamError('a team cannot be disbanded into itself');
    const goals = this.ctx.store.data.goals.filter((g) => g.teamId === id || (!g.teamId && team.repos.includes(g.repoId ?? '')));
    const goalIds = new Set(goals.map((g) => g.id));
    const tasks = this.ctx.store.data.tasks.filter((t) => t.teamId === id || goalIds.has(t.goalId ?? '') || (!t.teamId && team.repos.includes(t.repoId ?? '')));
    // Running/paused turns still belong to the old lead and workers. Moving them behind their
    // backs leaves stale queues and worktree ownership; stop or finish that work first.
    if (goals.some((g) => g.status === 'planning' || g.status === 'active') || tasks.some((t) => t.status !== 'done' && t.status !== 'cancelled')) {
      throw new TeamError(`team "${id}" has unfinished work; stop or finish its goals and tasks before disbanding`);
    }
    this.teams.splice(this.teams.indexOf(team), 1);
    for (const g of goals) {
      g.teamId = target.id;
      g.updatedAt = this.ctx.now();
    }
    for (const t of tasks) {
      t.teamId = target.id;
      t.updatedAt = this.ctx.now();
    }
    this.update(target.id, {
      members: [...target.members, ...team.members, team.lead],
      repos: [...target.repos, ...team.repos],
    });
    this.ctx.store.markDirty();
    for (const g of goals) this.ctx.emit({ type: 'goal.upsert', goal: { ...g } });
    for (const t of tasks) this.ctx.emit({ type: 'task.upsert', task: { ...t, deps: [...t.deps] } });
  }

  /**
   * The first team, made out of whoever and whatever is already here.
   *
   * <p>Every Foreman before teams existed had exactly one: one lead, one worker roster, every
   * repository. That is a team, it just had no name - so a state file written before this change
   * becomes a single-team state file, and nothing about how it behaves changes.
   */
  seed(input: { lead: string; members: string[]; repos: string[] }): Team {
    if (this.teams.length) return this.primary();
    return this.create({ id: 'studio', name: 'Studio', lead: input.lead, members: input.members, repos: input.repos, floor: 0 });
  }

  /** Repos nobody owns yet (a repo added after its team existed): they belong to the primary. */
  adopt(repoIds: string[]): void {
    const orphans = repoIds.filter((r) => !this.ofRepo(r));
    if (!orphans.length) return;
    const t = this.primary();
    this.update(t.id, { repos: [...t.repos, ...orphans] });
  }
}
