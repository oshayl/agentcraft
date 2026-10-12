// Working on AgentCraft from inside AgentCraft.
//
// The loop everywhere else is: an agent changes a repository, you approve a merge, you look at
// the result. Here the repository is the thing you are looking at, so the last step needs help:
// a change to the Foreman only takes effect when the Foreman restarts, and a change to the mod
// only when the game does - and both of those are what you are sitting in.
//
// None of it is as circular as it sounds. Agents work in their own worktrees and verify there
// (`npm run check`, `gradlew compileClientJava`), which needs no restart at all; the Foreman
// remembers in-flight turns and resumes them after a restart, so reloading mid-work costs a few
// seconds rather than the work; and the game reconnects to the Foreman by itself. What was
// missing was a way to drive those last steps without leaving the window, which is what this is.
import path from 'node:path';
import type { Ctx } from './context.js';
import { runShell } from './util/proc.js';

/** What the in-game dev loop can do. */
export const DEV_ACTIONS = ['check', 'build', 'reload', 'restart-game'] as const;
export type DevAction = (typeof DEV_ACTIONS)[number];

export interface DevResult {
  lines: string[];
  /** the Foreman is about to exit and be restarted; say so before it happens */
  restarting?: boolean;
}

const CHECK_MS = 10 * 60_000;

// The one slow step running right now, if any. Two reasons to know: a reload exits the process a
// moment after it answers, which would take a running check down with it and leave the console
// saying "running the tests" forever; and two npm checks in one directory tread on each other's
// temporary files, so the second would be reporting noise.
let inFlight: DevAction | null = null;

/** Which slow step is running, if one is. */
export function devInFlight(): DevAction | null {
  return inFlight;
}

/** Marks a slow step as running; the caller releases it when the work settles. */
export function devBegin(action: DevAction): () => void {
  inFlight = action;
  return () => {
    if (inFlight === action) inFlight = null;
  };
}

/**
 * Runs one step of the loop in the AgentCraft checkout the Foreman is running from.
 *
 * `check` and `build` are the two verifications; `reload` and `restart-game` are how a verified
 * change becomes the thing you are using.
 */
export async function dev(ctx: Ctx, projectRoot: string, action: DevAction): Promise<DevResult> {
  switch (action) {
    case 'check': {
      const r = await runShell('npm run check', { cwd: path.join(projectRoot, 'foreman'), timeoutMs: CHECK_MS });
      const out = `${r.stdout}\n${r.stderr}`.split('\n').filter((l) => l.trim());
      const tests = out.find((l) => /Tests\s+\d+ passed/.test(l)) ?? out.find((l) => /Tests/.test(l));
      const failed = r.code !== 0;
      return {
        lines: [
          failed ? 'npm run check FAILED' : 'npm run check passed',
          ...(tests ? [tests.trim()] : []),
          ...(failed ? out.slice(-6).map((l) => l.trim()) : []),
        ],
      };
    }
    case 'build': {
      // the mod's own compile, which is the only way to know a Java change is real
      const r = await runShell('sh gradlew compileClientJava compileJava -q', {
        cwd: path.join(projectRoot, 'mod'),
        timeoutMs: CHECK_MS,
        env: { ...process.env, JAVA_HOME: process.env.JAVA_HOME ?? '/usr/local/opt/openjdk@25' },
      });
      const out = `${r.stdout}\n${r.stderr}`.split('\n').filter((l) => l.trim());
      return {
        lines:
          r.code === 0
            ? ['the mod compiles. /dev restart-game to run it (the Foreman and your work stay up)']
            : ['the mod does NOT compile', ...out.slice(-6).map((l) => l.trim())],
      };
    }
    case 'reload': {
      // Exit and let the service manager bring it back. State is written on the way out and
      // in-flight turns are resumed, so this costs seconds rather than work.
      if (inFlight) {
        return { lines: [`not reloading: ${inFlight} is still running, and reloading now would kill it without a word. Try again when it reports.`] };
      }
      return { lines: ['Reloading the Foreman. Your agents resume where they were; the game reconnects by itself.'], restarting: true };
    }
    case 'restart-game': {
      // detached, because the thing being restarted is the client that asked
      await runShell('(agentcraft --restart game >/dev/null 2>&1 &)', { cwd: projectRoot, timeoutMs: 10_000 }).catch((e) =>
        ctx.log.warn(`restart-game: ${(e as Error).message}`),
      );
      return { lines: ['Restarting the game. The Foreman keeps running, so nothing is lost.'] };
    }
  }
}
