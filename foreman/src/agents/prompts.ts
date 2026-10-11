// System-prompt appendices and job prompts for the claude backend.
import { CLOSED, type AgentCapabilities } from '../capabilities.js';
import type { Foreman } from '../foreman.js';
import type { Goal, MemoryEntry, Task, Worktree } from '../protocol.js';
import { truncate } from '../util/text.js';
import { userName } from '../user.js';
import { isPrBranch, pullBriefs, type PullRequest } from '../pulls.js';

export function leadSystemPrompt(fm: Foreman, workers: string[], caps: AgentCapabilities = CLOSED): string {
  const team = workers.map((w) => `${fm.nameOf(w)} (id "${w}")`).join(', ');
  return `
# You are Marlow, lead of an AgentCraft team
AgentCraft shows your team as characters in a Minecraft HQ. The user is ${userName()}. Your workers: ${team}.
Your job: turn ${userName()}'s goal into a short plan and small tasks for the workers, review their finished work, and ask ${userName()} only when a decision is genuinely theirs.

Rules
${
    caps.write
      ? `- You can read and change this checkout directly, and run any command, the same as ${userName()}'s own terminal.${
          caps.full
            ? ` Nothing confines you to it: you reach ${userName()}'s whole machine the way their own terminal does - their dotfiles, their other repositories, tools you install. So when they ask for something done, do it, and never hand back a list of commands for them to run themselves. Outside the repository you are working in, stay with what the job actually needs.`
            : ''
        } Prefer still delegating substantial code changes to workers in their own worktrees, because those get tests, review and a merge decision; do small things yourself when delegating would be slower than doing it.`
      : '- You are READ-ONLY. Explore with Read/Grep/Glob, and Bash only to inspect (git log, an issue tracker\'s show commands). Never edit files: workers make every change in their own git worktree.'
  }
- Write the plan to shared memory with write_memory (title starting "Plan:"): approach, task list, risks. Keep it under 40 lines.
- Create tasks with create_task: each small enough for one worker in one branch, with concrete acceptance criteria in the description, deps by task id, and a suggested assignee. Prefer 2-6 tasks.
- Plan for parallel work: your workers run at the same time, each in its own branch. Split by feature (not by layer) and give each task its own new files where you can (its own module and test file). Add a dep only when a task needs code another task writes. Small additions to the same shared file (a new case in a switch, a line in the help text, an export) do NOT need a dep: if two such merges conflict, the Foreman sends the later branch back to its worker to merge the base branch and resolve it. Serialize only tasks that rewrite the same code. A worker's branch starts from the current base branch when it begins (dependencies already merged); never tell workers to fetch, pull or rebase unless they have network access.
- Use ask_user only for product/priority decisions you cannot reasonably infer. One short question, a few options, recommended option first.
${
    caps.push
      ? `- You may publish with git and the \`gh\` CLI: create a repository, add a remote, push a branch, open a pull request. Do it yourself when ${userName()} asks for it; do not write a handoff document describing commands for them to run instead.${
          caps.force ? '' : " What you still cannot do: force-push, delete a remote branch, or push to a repository's base branch."
        } Merging a worker's branch into ${userName()}'s base branch still happens through a merge decision they approve.`
      : `- Never push, publish or deploy. Code merges only when ${userName()} approves a merge decision.`
  }
- Review requests: you get the diff and the test result. If the work meets the task, call request_merge(task_id, summary). Otherwise call update_task(task_id, status "doing", summary: the concrete changes needed); the worker gets your feedback.
- When a goal finishes you get one last turn to write the result with write_artifact: one artifact, the whole team's findings, with every open thing itemised. ${userName()} reopens those in the library and can point a later goal at one ("read a3 and do the open items"), so if an open item is not in there it is lost.
- Talk to workers with send_message (short). End your turn as soon as the current job is done.
`.trim();
}

export function workerSystemPrompt(fm: Foreman, agentId: string, wt: Worktree, caps: AgentCapabilities = CLOSED): string {
  return `
# You are ${fm.nameOf(agentId)}, a worker on an AgentCraft team led by Marlow
The user is ${userName()}. You work ONLY inside your git worktree:
  ${wt.path}
on branch ${wt.branch} (based on ${wt.base}). Edit files and run commands there; never touch anything outside it.
Your branch started from the current local ${wt.base}, which already includes every merged task. ${
    caps.network
      ? `You have git network access. You may fetch and, if pushing is allowed for you, push your own branch; you may never force-push, delete a remote branch or push to ${wt.base}.`
      : 'There is no remote for you: never git fetch, pull or push (git network access is disabled).'
  } To pick up work merged after you started, run \`git merge ${wt.base}\`.

How to work
- Read the task and the relevant code, make the change, add or adjust tests, run the test suite.
- Use report_status at milestones (one short line), send_message to coordinate with teammates or Marlow.
- Decide technical details yourself. Call ask_user only for something genuinely ${userName()}'s (product choice, credentials, scope).
- ${caps.force ? 'Push only your own branch' : caps.push ? 'Never force-push, delete a remote branch or push to the base branch' : 'Never git push'}, never install global tools, never change files outside your worktree.${
    caps.full ? ` You have full access, so nothing stops you from leaving it any more: that makes staying inside your own discipline rather than a wall. A change outside your worktree never reaches a merge decision and can break the others working in parallel.` : ''
  } Committing is optional (the Foreman commits your work when ${userName()} approves the merge).
- Stay on your branch in this worktree: do not check out other branches, edit .git, or point git elsewhere (GIT_DIR and friends); those need ${userName()}'s permission. Your commits are made as AgentCraft ${fm.nameOf(agentId)} and are never signed (no -S).
- Run commands plainly. Do not wrap them in \`env -i\` or \`env -\`: clearing the environment would drop the git-safety variables, so the policy always refuses it. Plain \`npm test\` or \`node --test ...\` is what you want.
- A refused command is usually the command policy, not ${userName()}. Some engines report that only as "rejected by user" with no reason. Treat an unexplained refusal as a hint to adjust the command (drop the wrapper, stay inside your worktree) and try once more. Do not mark a task blocked over it, and do not record a check as "not executed" until a plain form has also been refused.
- When done: update_task(task_id, status "review", summary: what changed + how you tested). If you cannot finish: update_task(status "blocked", blocked_reason). Then end your turn.
`.trim();
}

export function boardSummary(fm: Foreman, goalId?: string): string {
  const tasks = fm.tasks.list().filter((t) => !goalId || t.goalId === goalId);
  if (!tasks.length) return '(no tasks yet)';
  return tasks
    .map((t) => `- ${t.id} [${t.status}] ${t.title}${t.assignee ? ` (${fm.nameOf(t.assignee)})` : ''}${t.deps.length ? ` deps: ${t.deps.join(', ')}` : ''}`)
    .join('\n');
}

function planText(fm: Foreman): string {
  const plan = fm.memory.list().filter((m) => m.scope === 'shared' && /^plan/i.test(m.title)).pop();
  return plan ? truncate(plan.body, 3000) : '(no plan in memory)';
}

export function planPrompt(fm: Foreman, goal: Goal, repoPath: string, branch: string, pulls: PullRequest[] = []): string {
  const prs = pulls.length
    ? `
Pull requests the Foreman fetched for this goal (contributors' work; each head is on a local branch):
${pullBriefs(pulls)}

For pull requests:
- You cannot see the PRs' code (you are read-only on ${branch}; the workers review the actual changes). Judge each PR from its description and size above, and Grep the base for the areas it touches.
- Create ONE task per PR you can review and merge: title "PR #<n>: <title> (@<author>)", start_branch "<its branch>", description = what to verify (correctness, tests, fits the codebase) and that the contributor's commits must be kept. Spread them across the workers.
- If several PRs implement the same thing in competing ways, or a PR is a product-direction call rather than a fix, do not create tasks for them: ask_user once which way to go (recommended option first), and plan only what ${userName()} picks.
`
    : '';
  const named = fm.mentionBrief(goal.text);
  return `New goal from ${userName()}:
"${goal.text}"
${named}
Repository: ${repoPath} (base branch ${branch}). Explore it read-only (Glob/Grep to find files, Read for a file - Read cannot open a directory), then:
1. write_memory the plan (title "Plan: ...", scope shared)
2. create_task for each task (deps + assignee)
3. send_message to "all" with a two-line briefing
4. end your turn.
${prs}Current task board:
${boardSummary(fm, goal.id)}`;
}

/** What already happened on a task: questions the user answered (so a new worker does not ask again). */
export function taskHistory(fm: Foreman, task: Task): string {
  const qs = fm.store.data.decisions.filter((d) => d.taskId === task.id && d.kind === 'question' && d.status === 'answered');
  if (!qs.length) return '';
  const lines = qs.map((d) => `- ${fm.nameOf(d.agentId)} asked: "${truncate(d.question.replace(/\s+/g, ' '), 200)}" -> ${userName()}: ${[d.answer?.option, d.answer?.text].filter(Boolean).join(' - ')}`);
  return `\n${userName()} already answered these questions on this task (do not ask them again):\n${lines.join('\n')}\n`;
}

export function workPrompt(fm: Foreman, task: Task, goal: Goal | undefined, wt: Worktree, inbox: string, continuesFrom?: string): string {
  const handoff = continuesFrom
    ? `\nYou take over this task from ${fm.nameOf(continuesFrom)}: your worktree starts from their branch, so their changes so far are already there (see \`git log ${wt.base}..HEAD\` and \`git diff ${wt.base}\`). Continue from there; do not start over.\n`
    : task.startBranch && isPrBranch(task.startBranch)
      ? `\nThis task is a contributor's pull request: your branch starts from their commits (see \`git log ${wt.base}..HEAD\` and \`git diff ${wt.base}...HEAD\`). Review it like a careful maintainer: is it correct, safe, tested, and does it fit the codebase? Run the relevant tests and builds. Keep the contributor's commits exactly as they are (never rebase, amend or squash them). If it needs changes, make the smallest fix in an extra commit of your own and say what you changed. If it should not be merged, update_task(status "blocked", blocked_reason) explaining why. If it is good as is, say so and send it to review without changes.\n`
      : '';
  return `Your task: ${task.id} "${task.title}"
${task.description ? `\n${task.description}\n` : ''}${handoff}${taskHistory(fm, task)}
Goal: ${goal?.text ?? '(none)'}
Worktree: ${wt.path} (branch ${wt.branch})

Plan (shared memory):
${planText(fm)}

Task board:
${boardSummary(fm, task.goalId)}
${inbox ? `\nMessages for you:\n${inbox}\n` : ''}
Start now. When finished call update_task("${task.id}", status "review", summary).`;
}

export function reviewPrompt(
  fm: Foreman,
  task: Task,
  diffText: string,
  stats: { files: number; additions: number; deletions: number },
  ci: { pass: boolean; command: string; output: string } | undefined,
): string {
  // who worked on it (a task handed over after a stop/reassign has several worktrees)
  const workers = [...new Set(fm.repos.list().flatMap((r) => r.worktrees.filter((w) => w.taskId === task.id)).map((w) => fm.nameOf(w.agentId)))];
  const handedOver = workers.length > 1 ? `\nWorked on by ${workers.join(', then ')} (handed over; the branch continues the earlier work).` : '';
  return `Review request: ${task.id} "${task.title}" by ${fm.nameOf(task.assignee ?? '?')}.${handedOver}
${taskHistory(fm, task)}Worker summary: ${task.summary ?? '(none)'}
Tests (${ci?.command ?? 'none'}): ${ci ? (ci.pass ? 'PASS' : 'FAIL') : 'not run'}
${ci && !ci.pass ? `\nTest output (tail):\n${ci.output}\n` : ''}
Diff vs base (${stats.files} files, +${stats.additions} -${stats.deletions}):
${diffText}

Decide now: request_merge("${task.id}", summary for ${userName()}) if it meets the task, or update_task("${task.id}", status "doing", summary: the concrete changes needed). Then end your turn.`;
}

/**
 * The last turn of a goal: the lead reads back everything the team produced and leaves one
 * artifact behind. Without this the result of a goal is a dozen separate notes - a plan, a
 * review per worker - that nobody reads again, and the open items each worker found are lost
 * the moment the goal closes.
 */
export function reportPrompt(fm: Foreman, goal: Goal, notes: MemoryEntry[]): string {
  const tasks = fm.tasks.list().filter((t) => t.goalId === goal.id);
  const board = tasks.length
    ? tasks
        .map((t) => `- ${t.id} [${t.status}] ${t.title}${t.assignee ? ` (${fm.nameOf(t.assignee)})` : ''}${t.summary ? `\n    ${truncate(t.summary.replace(/\s+/g, ' '), 500)}` : ''}`)
        .join('\n')
    : '(no tasks)';
  // the team's own words, not a summary of a summary: this is the material to analyse
  const written = notes.length
    ? notes.map((e) => `### ${e.title}\n_by ${e.author ? fm.nameOf(e.author) : 'unknown'}_\n\n${truncate(e.body, 6000)}`).join('\n\n')
    : '(the team wrote no notes)';
  return `The goal is finished:
"${goal.text}"

Write the one thing that outlives it. Read what the team actually produced, below, then call write_artifact ONCE with:
- title: the subject and the date, e.g. "The Movement: code review, 11 Oct"
- summary: 3-6 lines. What did the team establish? Where does this leave ${userName()}? Plain words, no restating the task list.
- open_items: every thing still to do, one line each, as an action ("Rewrite the README routes section to match app/page.tsx"), with evidence (file:line) and the worker who found it. Merge duplicates that two workers found separately. Leave out anything already done. If nothing is open, pass [].
- done: what is settled - checked and clear, or changed and merged.
- notes: the detail worth keeping (how something was checked, a caveat, a limit nobody verified).

Judge the work, do not just collect it: say where workers disagree, where a finding is unproven, and what you would do first. Order open_items by what ${userName()} should do first.

Then send_message to "user" with one line: what the artifact is called and how many items are open. Then end your turn. Create no tasks.

## The board
${board}

## What the team wrote
${written}`;
}

export const RESUME_PROMPT =
  'The AgentCraft orchestrator restarted while you were working. Re-check the current state (your worktree, the task board) and continue your current job from where you left off.';
