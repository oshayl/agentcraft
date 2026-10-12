# Working on AgentCraft

You are working on the program that is running you. That is less strange than it sounds, because
of one rule:

**Verify in your worktree. Never restart anything.**

Your checkout is a git worktree of your own. Everything you need to prove a change works runs
there, against your copy, and affects nobody. The running Foreman and the running game keep using
the code they started with until Noira merges your branch and reloads them — which is their call,
from in game, not yours. A restart you trigger kills your own turn and everybody else's.

## The two verifications

```
cd foreman && npm run check      # typecheck, 630+ tests, and the protocol doc is in sync
cd mod && sh gradlew compileClientJava compileJava -q
```

`npm run check` is the one that matters for anything under `foreman/`. It is slow (about 35
seconds) and worth it every time. The protocol doc check is part of it: if you change
`protocol.ts`, run `npm run gen:protocol-doc` or the check fails.

The Java side has no test suite, so compiling is the whole of the verification. `JAVA_HOME` is
`/usr/local/opt/openjdk@25` if it is not already set.

## The shape of the thing

| where | what |
|---|---|
| `foreman/src` | the orchestrator: TypeScript, Node, port 7878. Owns agents, tasks, decisions, repos, memory |
| `foreman/src/protocol.ts` | every message between Foreman and mod, as zod schemas. `docs/protocol.md` is generated from it |
| `foreman/src/agents` | the engines (claude, codex, sim), the team backend, the prompts, the tools |
| `mod/src/main/java` | the Minecraft mod's common side: blocks, the HQ builders, commands |
| `mod/src/client/java` | the client: screens, renderers, the console, the agents' pathfinding and motion |
| `mod/src/main/resources` | blockstates, models, textures, painting variants, lang |

The Foreman and the mod talk over a WebSocket, and the mod is only a window onto the Foreman —
the Foreman outlives it and keeps every piece of state.

## House style

Match the file you are editing: its comment density, its naming, its idioms. The comments here
explain **why**, especially where something looks odd — a reader who can see what the code does
still cannot see which bug it is avoiding. Several comments in this codebase name the failure
that produced them; that is deliberate, keep it up.

Write the test that would have caught the bug, not a test that restates the implementation.

## Things that have bitten people here

- **A resumed engine thread keeps the tools and instructions it was created with.** If you add a
  tool or change capabilities, the session fingerprint has to change or agents on old threads
  silently never see it (`SessionRecord.caps`, `SessionRecord.tools`).
- **`npx tsc` is a decoy** in this repo and prints a joke. Use `npm run typecheck`.
- **git porcelain lines are `XY <path>`** and X is a space for an unstaged change, so trimming
  the whole output eats the first line's status column.
- **Minecraft 26.3 renamed things.** `ResourceLocation` is `Identifier`; block-state NBT keys are
  `id`/`properties`, not `Name`/`Properties`; coloured blocks come from `ColorCollection`
  (`Blocks.CONCRETE.get(DyeColor.WHITE)`). When an API is missing, disassemble the real class out
  of `.gradle-home/caches/fabric-loom/26.3/minecraft-common.jar` rather than guessing.
- **Paintings ignore alpha** (`entitySolidZOffsetForward`). Blocks do not.
- **Never restart the Foreman, the game, or the launchd service.** See the rule at the top.
