// The AgentCraft team tools, shared by every engine (Claude: an in-process MCP server named
// "agentcraft", see claude/tools.ts; Codex: app-server dynamic tools, see codex/engine.ts):
//   send_message, ask_user, write_memory, read_memory, update_task, report_status, list_tasks
//   lead only: create_task, request_merge, write_artifact
// Every tool result carries any unread messages for the agent (so mid-turn messages arrive).
import { z } from 'zod';
import { formatInbox } from '../bus.js';
import type { Foreman } from '../foreman.js';
import { ARTIFACT_SCOPE } from '../memory.js';
import { renderArtifact } from '../artifacts.js';
import type { AgentState, Decision, TaskStatus } from '../protocol.js';
import { MERGE_OPTIONS } from '../protocol.js';
import { isPrBranch } from '../pulls.js';
import { truncate } from '../util/text.js';
import { boardSummary } from './prompts.js';
import { userName } from '../user.js';

export interface ToolHooks {
  /** worker moved its task to review */
  onReview(agentId: string, taskId: string): void;
  /** lead asked for changes on a task (status -> doing) */
  onChangesRequested(taskId: string, feedback: string): void;
  /** a task was created (scheduler tick) */
  onTasksChanged(): void;
  /** lead requested a merge (decision created) */
  onMergeRequested(taskId: string, decision: Decision): void;
  /** agent is blocked waiting for the user */
  onWaiting(agentId: string, waiting: boolean): void;
}

/** The turn a tool server belongs to: once it is aborted, tools refuse to act. */
export interface TurnHandle {
  signal: AbortSignal;
  /** why it was aborted: pause | stop | shutdown | cancel | timeout */
  reason(): string | undefined;
  /**
   * Hold the turn's deadline while the agent waits on the user; the result releases that hold.
   * Waiting for a human is not the agent's own time to spend.
   */
  waitingOnUser(): () => void;
}

export type ToolResult = { content: Array<{ type: 'text'; text: string }>; isError?: boolean };

/** One team tool: an engine exposes it under `name` with `shape` as its input schema. */
export interface AgentTool {
  name: string;
  description: string;
  shape: z.ZodRawShape;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  handler(args: any): Promise<ToolResult>;
}

/** Names of the team tools for a role (unprefixed). */
export const TOOL_NAMES = {
  common: ['send_message', 'ask_user', 'write_memory', 'read_memory', 'update_task', 'report_status', 'list_tasks'],
  lead: ['create_task', 'request_merge', 'write_artifact'],
} as const;

function tool<S extends z.ZodRawShape>(name: string, description: string, shape: S, handler: (args: z.infer<z.ZodObject<S>>) => Promise<ToolResult>): AgentTool {
  return { name, description, shape, handler };
}

/**
 * A task in review whose worktree changed nothing (a report, an investigation): there is nothing
 * to merge, so it is closed as done (worktree abandoned, branch kept) instead of asking the user to
 * approve an empty merge. Returns true if it was closed.
 */
export async function closeIfNoChanges(fm: Foreman, taskId: string): Promise<boolean> {
  const t = fm.tasks.get(taskId);
  if (!t || t.status !== 'review' || !t.repoId || !t.worktree) return false;
  await fm.repos.refresh(t.repoId);
  const wt = fm.repos.findWorktree(t.repoId, t.worktree);
  if (!wt || wt.status !== 'active' || wt.files > 0) return false;
  await fm.repos.abandon(t.repoId, wt.id, `agentcraft: ${t.id} (no changes)`);
  fm.tasks.setStatus(t.id, 'done', { force: true, summary: t.summary ?? 'no changes' });
  fm.bus.feed('task', `${t.id} changed no files (report only): closed as done, nothing to merge`, { agentId: t.assignee ?? 'marlow' });
  if (t.assignee && fm.agent(t.assignee)?.taskId === t.id) fm.setAgent(t.assignee, { state: 'idle', station: 'lounge', activity: `${t.id} done`, taskId: null, worktree: null });
  return true;
}

/** The team tools for one agent's turn (lead: also create_task and request_merge). */
export function agentTools(fm: Foreman, agentId: string, role: 'lead' | 'worker', hooks: ToolHooks, turn?: TurnHandle): AgentTool[] {
  const withInbox = (text: string, isError = false): ToolResult => {
    const inbox = fm.bus.inbox(agentId, { markRead: true });
    const extra = inbox.length ? `\n\n[New messages]\n${formatInbox(inbox, (id) => fm.nameOf(id))}` : '';
    return { content: [{ type: 'text', text: text + extra }], ...(isError ? { isError: true } : {}) };
  };
  const fail = (text: string) => withInbox(`Error: ${text}`, true);

  const tools: AgentTool[] = [
    tool(
      'send_message',
      `Send a short message to a teammate (id or name), "lead", "all", or "user" (${userName()}; not a question — use ask_user for questions).`,
      { to: z.string().describe('agent id/name, "lead", "all" or "user"'), text: z.string().describe('the message (1-3 sentences)') },
      async ({ to, text }) => {
        let target = to.trim().toLowerCase();
        if (target === 'lead') target = 'marlow';
        if (!['all', 'user'].includes(target)) {
          const id = fm.resolveAgentId(target);
          if (!id) return fail(`no teammate "${to}". Team: ${fm.agents().filter((a) => a.active).map((a) => a.id).join(', ')}`);
          target = id;
        }
        if (target === agentId) return fail('you cannot message yourself');
        fm.bus.send(agentId, target, text);
        // A worker reads messages only while it works on a task: one with no task would read this
        // whenever its next task starts, so a request sent this way silently never happens.
        if (role === 'lead' && !['all', 'user'].includes(target) && !fm.agent(target)?.taskId) {
          return withInbox(
            `Sent to ${target}, but ${fm.nameOf(target)} is not on a task, so they will only read it when their next task starts. ` +
              `To have ${fm.nameOf(target)} do something now, create a task for it with create_task (assignee "${target}").`,
          );
        }
        return withInbox(`Sent to ${target}.`);
      },
    ),
    tool(
      'ask_user',
      `Ask ${userName()} a question and WAIT for the answer. Only for decisions that are genuinely the user's. Put the recommended option first.`,
      {
        question: z.string(),
        options: z.array(z.string()).max(6).optional().describe('2-4 short choices, recommended first'),
        context: z.string().optional().describe('one or two lines of background'),
      },
      async ({ question, options, context }) => {
        const prev = fm.agent(agentId);
        // the stream mapper may already show the agent waiting at the user (it saw the tool call):
        // after the answer the agent goes back to thinking at its own station, not "waiting"
        const home = role === 'lead' ? 'meeting' : 'desk';
        const waiting = prev?.state === 'waiting_user' || prev?.station === 'user';
        const prevState = { state: waiting ? 'thinking' : (prev?.state ?? 'thinking'), station: waiting ? home : (prev?.station ?? home), activity: prev?.activity ?? '' };
        const d = fm.createDecision({ agentId, kind: 'question', question, options: options ?? [], ...(context ? { context } : {}), ...(prev?.taskId ? { taskId: prev.taskId } : {}) });
        fm.setAgent(agentId, { state: 'waiting_user', station: 'user', activity: 'waiting for your answer' });
        hooks.onWaiting(agentId, true);
        // If the turn is aborted (stop, pause, task cancelled, timeout) nobody will read the answer:
        // withdraw the question so it does not sit on the podium forever. A Foreman shutdown keeps
        // it open on purpose: after the restart the answer resumes the session.
        const onAbort = () => {
          if (turn?.reason() !== 'shutdown') fm.decisions.cancel(d.id, `${fm.nameOf(agentId)}'s turn was stopped`);
        };
        if (turn?.signal.aborted) onAbort();
        else turn?.signal.addEventListener('abort', onAbort, { once: true });
        // the turn's deadline stops here: the agent is waiting on a person, not working
        const release = turn?.waitingOnUser() ?? (() => {});
        let done;
        try {
          done = await fm.decisions.wait(d.id);
        } finally {
          release();
        }
        turn?.signal.removeEventListener('abort', onAbort);
        hooks.onWaiting(agentId, false);
        if (turn?.signal.aborted) return withInbox(`Your turn was stopped before ${userName()} answered.`, true);
        fm.setAgent(agentId, { state: prevState.state as AgentState, station: prevState.station, activity: 'got your answer' });
        if (done.status === 'cancelled') return withInbox('The question was cancelled. Use your best judgement and note the assumption.');
        const ans = [done.answer?.option, done.answer?.text].filter(Boolean).join(' — ');
        return withInbox(`${userName()} answered: ${ans}`);
      },
    ),
    tool(
      'write_memory',
      'Write a markdown note to memory. scope "team" (your team, the default — project notes go here), "shared" (every team in the studio: house rules, conventions) or "private" (only you). mode "append" adds to an existing note with the same title.',
      {
        title: z.string(),
        body: z.string().describe('markdown'),
        scope: z.enum(['team', 'shared', 'private']).optional(),
        mode: z.enum(['replace', 'append']).optional(),
      },
      async ({ title, body, scope, mode }) => {
        // "shared" meant the whole team when there was only one; it still reaches everyone, and a
        // thread resumed from before teams keeps passing it, so it has to go on meaning that
        const where = scope === 'private' ? agentId : scope === 'shared' ? 'shared' : fm.teamOf(agentId).id;
        const e = fm.memory.write({ scope: where, title, body, author: agentId, mode: mode ?? 'replace' });
        fm.bus.feed('memory', `${fm.nameOf(agentId)} wrote memory: ${e.title}`, { agentId });
        return withInbox(`Saved memory ${e.id}.`);
      },
    ),
    tool(
      'read_memory',
      'Read memory. With id: that note. With query: matching notes. With neither: list all notes you can see.',
      { id: z.string().optional(), query: z.string().optional() },
      async ({ id, query }) => {
        const teamScope = fm.teamOf(agentId).id;
        if (id) {
          const e =
            fm.memory.get(id) ??
            fm.memory.get(`${teamScope}/${id}`) ??
            fm.memory.get(`shared/${id}`) ??
            fm.memory.get(`${ARTIFACT_SCOPE}/${id}`) ??
            fm.memory.get(`${agentId}/${id}`);
          if (!e || (e.scope !== 'shared' && e.scope !== teamScope && e.scope !== ARTIFACT_SCOPE && e.scope !== agentId)) return fail(`no memory ${id}`);
          return withInbox(`# ${e.title} (${e.id})\n\n${e.body}`);
        }
        const list = query ? fm.memory.search(query, agentId, teamScope) : fm.memory.visibleTo(agentId, teamScope);
        if (!list.length) return withInbox('No memory notes.');
        if (query && list.length <= 3) return withInbox(list.map((e) => `# ${e.title} (${e.id})\n\n${truncate(e.body, 3000)}`).join('\n\n---\n\n'));
        return withInbox(list.map((e) => `- ${e.id}: ${e.title}`).join('\n'));
      },
    ),
    tool(
      'update_task',
      role === 'lead'
        ? 'Update a task: status (todo|doing|blocked|cancelled; "doing" on a review task = request changes), summary/feedback, assignee, title, description, deps.'
        : 'Update YOUR task: status "review" when done (with summary of changes + testing), "blocked" with blocked_reason, or "doing".',
      {
        task_id: z.string(),
        status: z.enum(['todo', 'doing', 'review', 'blocked', 'cancelled']).optional(),
        summary: z.string().optional(),
        blocked_reason: z.string().optional(),
        assignee: z.string().optional(),
        title: z.string().optional(),
        description: z.string().optional(),
        deps: z
          .array(z.string())
          .optional()
          .describe('the whole dependency list, replacing whatever it was. Use this when re-planning puts new work in front of a task that already exists'),
      },
      async (a) => {
        const t = fm.tasks.get(a.task_id);
        if (!t) return fail(`no task ${a.task_id}. ${boardSummary(fm)}`);
        if (role === 'worker') {
          const current = fm.agent(agentId)?.taskId;
          if (t.assignee !== agentId) return fail(`${t.id} is not your task`);
          if (current && t.id !== current) return fail(`you are working on ${current}; you can only update that task. Tell Marlow (send_message to "lead") if ${t.id} is already covered.`);
          if (a.status && !['review', 'blocked', 'doing'].includes(a.status)) return fail('workers can set status review, blocked or doing');
          if (a.assignee || a.title || a.description || a.deps) return fail('only the lead can change assignee/title/description/deps');
        }
        try {
          if (a.assignee) {
            const id = fm.resolveAgentId(a.assignee);
            if (!id) return fail(`no agent ${a.assignee}`);
            fm.tasks.update(t.id, { assignee: id });
          }
          // Re-planning routinely puts new work in front of a task that already exists, and
          // there was no way to say so: the lead had to write "clear this hold once t66 merges"
          // into a blocked reason and trust somebody to remember. A dependency the graph
          // enforces is not a note somebody has to read.
          if (a.deps) {
            for (const d of a.deps) if (!fm.tasks.get(d)) return fail(`no task ${d}`);
            fm.tasks.update(t.id, { deps: a.deps });
          }
          if (a.title) fm.tasks.update(t.id, { title: a.title });
          if (a.description) fm.tasks.update(t.id, { description: a.description });
          if (a.summary) fm.tasks.update(t.id, { summary: a.summary });
          if (a.status && a.status !== t.status) {
            const prev = t.status;
            fm.tasks.setStatus(t.id, a.status as TaskStatus, { force: role === 'lead' || a.status === 'review', ...(a.blocked_reason ? { reason: a.blocked_reason } : {}), ...(a.summary ? { summary: a.summary } : {}) });
            fm.bus.feed('task', `${fm.nameOf(agentId)}: ${t.id} ${prev} -> ${a.status}`, { agentId });
            if (a.status === 'review' && role === 'worker') hooks.onReview(agentId, t.id);
            if (a.status === 'doing' && prev === 'review' && role === 'lead') hooks.onChangesRequested(t.id, a.summary ?? 'see review comments');
          }
          hooks.onTasksChanged();
          return withInbox(`Updated ${t.id}: ${fm.tasks.get(t.id)!.status}.`);
        } catch (e) {
          return fail((e as Error).message);
        }
      },
    ),
    tool(
      'report_status',
      'Tell the team what you are doing (shown on your nameplate). One short line.',
      { activity: z.string().max(80), note: z.string().optional().describe('optional longer line for your monitor') },
      async ({ activity, note }) => {
        fm.setAgent(agentId, { activity });
        if (note) fm.agentLog(agentId, 'text', note);
        return withInbox('ok');
      },
    ),
    tool('list_tasks', 'Show the task board (ids, status, assignee, deps).', {}, async () => withInbox(boardSummary(fm))),
  ];

  if (role === 'lead') {
    tools.push(
      tool(
        'write_artifact',
        `Save the team's result as an artifact: one durable, itemised note ${userName()} can reopen, and a later goal can be pointed at ("read <id> and do the open items"). Write one when a goal finishes, summarising what the whole team established - not one per task. Pass id to update an existing artifact instead of adding another.`,
        {
          title: z.string().describe('what this is about, e.g. "The Movement: code review, 11 Oct"'),
          summary: z.string().describe('3-6 lines: what the team established, in plain words'),
          open_items: z
            .array(
              z.object({
                text: z.string().describe('one line, an action: "Rewrite README routes section to match app/page.tsx"'),
                evidence: z.string().optional().describe('where it was found: "README:37, app/page.tsx:14"'),
                owner: z.string().optional().describe('the agent who found it'),
              }),
            )
            .describe('everything still to do, one entry each; [] when nothing is open'),
          done: z.array(z.string()).optional().describe('what is settled: checked and clear, or already changed'),
          notes: z.string().optional().describe('markdown appended under Notes (detail, caveats, how it was checked)'),
          id: z.string().optional().describe('an existing artifact id to replace'),
          goal_id: z.string().optional(),
        },
        async ({ title, summary, open_items, done, notes, id, goal_id }) => {
          const goal = goal_id ? fm.goal(goal_id) : fm.currentGoalFor(fm.teamOf(agentId).id);
          const body = renderArtifact({ title, summary, openItems: open_items, done: done ?? [], ...(notes ? { notes } : {}), ...(goal ? { goal } : {}) });
          const slug = id ? id.replace(`${ARTIFACT_SCOPE}/`, '') : undefined;
          const e = fm.memory.write({ scope: ARTIFACT_SCOPE, title, body, author: agentId, ...(slug ? { slug } : {}) });
          const open = open_items.length;
          fm.bus.feed('memory', `${fm.nameOf(agentId)} saved artifact ${e.id}: ${e.title} (${open} open item${open === 1 ? '' : 's'})`, { agentId });
          return withInbox(`Saved artifact ${e.id} (${open} open item${open === 1 ? '' : 's'}) at ${fm.memory.pathOf(e.id)}. ${userName()} can reopen it in the library, and a later goal can name it.`);
        },
      ),
      tool(
        'create_task',
        'Create a task for a worker. deps = task ids that must be merged first. Returns the new task id.',
        {
          title: z.string(),
          description: z.string().describe('what to do + acceptance criteria'),
          deps: z.array(z.string()).optional(),
          assignee: z.string().optional().describe('worker id/name'),
          priority: z.number().int().optional(),
          start_branch: z.string().optional().describe('only for a fetched pull request: its branch, e.g. "agentcraft/pr-12"; the worker starts from the contributor\'s commits'),
        },
        async ({ title, description, deps, assignee, priority, start_branch }) => {
          const team = fm.teamOf(agentId);
          const goal = fm.currentGoalFor(team.id);
          if (start_branch && !isPrBranch(start_branch)) return fail(`start_branch must be a fetched pull request branch (agentcraft/pr-<n>), not ${start_branch}`);
          let who: string | undefined;
          if (assignee) {
            who = fm.resolveAgentId(assignee);
            if (!who) return fail(`no worker ${assignee}`);
            if (fm.teams.isLead(who)) return fail('assign tasks to workers, not to a lead');
            // another team's worker is not short of work, and their repos are not yours
            if (!team.members.includes(who)) return fail(`${fm.nameOf(who)} is on ${fm.teamOf(who).name}, not ${team.name}; assign this to one of ${team.members.join(', ') || 'your own workers'}`);
          }
          try {
            const t = fm.tasks.create({
              title,
              description,
              deps: deps ?? [],
              ...(who ? { assignee: who } : {}),
              ...(priority !== undefined ? { priority } : {}),
              ...(start_branch ? { startBranch: start_branch } : {}),
              createdBy: agentId,
              ...(goal ? { goalId: goal.id } : {}),
              ...(goal?.repoId ? { repoId: goal.repoId } : {}),
              teamId: team.id,
            });
            fm.bus.feed('task', `${fm.nameOf(agentId)} created ${t.id}: ${t.title}`, { agentId });
            hooks.onTasksChanged();
            return withInbox(`Created ${t.id}.`);
          } catch (e) {
            return fail((e as Error).message);
          }
        },
      ),
      tool(
        'request_merge',
        `After reviewing a task in "review": send ${userName()} a merge decision for its branch. ${userName()} decides; you do not wait.`,
        { task_id: z.string(), summary: z.string().describe(`2-4 lines for ${userName()}: what changed, how it was tested, risks`) },
        async ({ task_id, summary }) => {
          const t = fm.tasks.get(task_id);
          if (!t) return fail(`no task ${task_id}`);
          if (t.status !== 'review') return fail(`${t.id} is ${t.status}, not in review`);
          if (!t.worktree || !t.repoId) return fail(`${t.id} has no worktree to merge`);
          const open = fm.decisions.open().find((d) => d.kind === 'merge' && d.taskId === t.id);
          if (open) return withInbox(`Merge decision ${open.id} for ${t.id} is already waiting for ${userName()}.`);
          if (await closeIfNoChanges(fm, t.id)) {
            hooks.onTasksChanged();
            return withInbox(`${t.id} changed no files, so there is nothing to merge: it is closed as done. Tell ${userName()} the result with send_message if you have not yet.`);
          }
          const wt = fm.repos.requireWorktree(t.repoId, t.worktree);
          const d = fm.createDecision({
            agentId,
            kind: 'merge',
            question: `Merge ${t.id} "${t.title}" (${wt.branch}) into ${wt.base}?`,
            options: [...MERGE_OPTIONS],
            context: `${summary}\n${wt.files} files, +${wt.additions} -${wt.deletions} | tests: ${t.ci}`,
            taskId: t.id,
            repoId: t.repoId,
            worktree: wt.id,
          });
          hooks.onMergeRequested(t.id, d);
          return withInbox(`Merge decision ${d.id} sent to ${userName()}.`);
        },
      ),
    );
  }

  // a stopped/aborted turn must not change anything any more, even if its CLI lingers
  for (const t of tools) {
    const inner = t.handler;
    t.handler = async (args) => (turn?.signal.aborted ? fail('your turn was stopped; nothing was changed') : inner(args));
  }
  return tools;
}
