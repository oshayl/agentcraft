// Artifacts: the team's durable output, as opposed to its scratch notes.
//
// A goal produces a plan from the lead and a review note from every worker, and when the goal
// ends those sit in memory as a dozen separate notes nobody will read again. An artifact is the
// one note that outlives the goal: what the team established, and - itemised, one line each -
// what is still open. The lead writes it with write_artifact when a goal finishes (see
// agents/team.ts, the "report" job), and the user can hand it back to the next goal:
//
//   "read a3 and do the open items"
//
// Storage is a memory scope (memory.ts, ARTIFACT_SCOPE), so an artifact is an ordinary markdown
// file the user can open in any editor, it shows up in the in-game library under its own tab,
// and agents reach it with read_memory. The open items are plain `- [ ]` checkboxes, so ticking
// one off in an editor is the same act as ticking it off here.
import type { Goal, MemoryEntry } from './protocol.js';
import { userName } from './user.js';

/** One line of work an artifact still has open (or has had ticked off). */
export interface ArtifactItem {
  /** stable within the artifact: "1", "2", ... in file order */
  n: number;
  text: string;
  done: boolean;
}

export interface ArtifactInput {
  title: string;
  summary: string;
  openItems: Array<{ text: string; evidence?: string; owner?: string }>;
  done: string[];
  notes?: string;
  goal?: Goal;
}

/** `- [ ] text` / `- [x] text`, the only thing this file really parses. */
const ITEM_RE = /^\s*[-*]\s+\[( |x|X)\]\s+(.*\S)\s*$/;

/**
 * The markdown an artifact is stored as. Rendered here rather than left to the model, so every
 * artifact has the same shape and the open items are always machine-readable.
 */
export function renderArtifact(a: ArtifactInput): string {
  const out: string[] = [];
  if (a.goal) out.push(`> Goal ${a.goal.id}: ${a.goal.text.replace(/\s+/g, ' ').trim()}`, '');
  out.push(a.summary.trim(), '');
  out.push('## Open items', '');
  if (a.openItems.length) {
    for (const it of a.openItems) {
      const tail = [it.evidence?.trim(), it.owner?.trim() ? `found by ${it.owner.trim()}` : ''].filter(Boolean).join(' · ');
      out.push(`- [ ] ${it.text.trim()}${tail ? ` — ${tail}` : ''}`);
    }
  } else {
    out.push('_Nothing open._');
  }
  if (a.done.length) {
    out.push('', '## Settled', '');
    for (const d of a.done) out.push(`- [x] ${d.trim()}`);
  }
  if (a.notes?.trim()) out.push('', '## Notes', '', a.notes.trim());
  return out.join('\n');
}

/** Every checkbox in an artifact's body, in file order. */
export function artifactItems(entry: MemoryEntry): ArtifactItem[] {
  const items: ArtifactItem[] = [];
  for (const line of entry.body.split('\n')) {
    const m = ITEM_RE.exec(line);
    if (m) items.push({ n: items.length + 1, text: m[2]!, done: m[1]!.toLowerCase() === 'x' });
  }
  return items;
}

export function openItems(entry: MemoryEntry): ArtifactItem[] {
  return artifactItems(entry).filter((i) => !i.done);
}

/** "a3 · The Movement: code review — 7 open of 9" */
export function artifactLine(entry: MemoryEntry): string {
  const items = artifactItems(entry);
  const open = items.filter((i) => !i.done).length;
  const state = !items.length ? 'no items' : open ? `${open} open of ${items.length}` : `all ${items.length} done`;
  return `${shortId(entry)} · ${entry.title} — ${state}`;
}

/** The slug an artifact is named by: "artifacts/movement-review" -> "movement-review". */
export function shortId(entry: MemoryEntry): string {
  return entry.id.slice(entry.id.indexOf('/') + 1);
}

/**
 * The artifacts a prompt refers to. Matched on the id (with or without the "artifacts/" prefix)
 * and on the exact title, both case-insensitively, on word boundaries so "a-review" in prose does
 * not drag in an unrelated artifact. Deliberately not fuzzy: a goal that names nothing should
 * resolve to nothing rather than to the lead's best guess.
 */
export function artifactsNamedIn(text: string, all: MemoryEntry[]): MemoryEntry[] {
  // a `#mention` names a thing outright, so it matches before any prose does
  const mentioned = mentionsIn(text);
  const hay = text.toLowerCase();
  const hit = (needle: string): boolean => {
    const n = needle.toLowerCase();
    if (!n || n.length < 3) return false;
    let from = 0;
    for (;;) {
      const i = hay.indexOf(n, from);
      if (i < 0) return false;
      const before = i === 0 ? '' : hay[i - 1]!;
      const after = hay[i + n.length] ?? '';
      if (!/[a-z0-9]/.test(before) && !/[a-z0-9]/.test(after)) return true;
      from = i + 1;
    }
  };
  const named = (e: MemoryEntry): boolean =>
    mentioned.some((t) => t.toLowerCase() === e.id.toLowerCase() || t.toLowerCase() === shortId(e).toLowerCase() || t.toLowerCase() === e.title.toLowerCase());
  return all.filter((e) => named(e) || hit(e.id) || hit(shortId(e)) || hit(`${shortId(e)}.md`) || hit(e.title));
}

/**
 * The things a prompt points at with `#`: `#movement-review`, or `#"10 Projects/Notes.md"` when
 * the name has spaces in it. The console completes these from the artifacts and the user's own
 * folders, so in practice they are picked from a list rather than typed.
 */
export function mentionsIn(text: string): string[] {
  const out: string[] = [];
  const re = /#(?:"([^"\n]{1,300})"|([^\s"#]{2,300}))/g;
  for (let m = re.exec(text); m; m = re.exec(text)) {
    const token = (m[1] ?? m[2] ?? '').trim();
    if (token && !out.includes(token)) out.push(token);
  }
  return out;
}

/** What the lead is told about the artifacts a goal or message names. */
export function artifactBrief(entries: MemoryEntry[], pathOf: (id: string) => string): string {
  if (!entries.length) return '';
  const blocks = entries.map((e) => {
    const items = artifactItems(e);
    const open = items.filter((i) => !i.done);
    const lines = open.map((i) => `  ${i.n}. ${i.text}`);
    return [
      `Artifact ${shortId(e)} — "${e.title}"`,
      `  file: ${pathOf(e.id)} (read_memory id "${e.id}")`,
      open.length ? `  open items (${open.length} of ${items.length}):` : `  nothing open (${items.length} item(s), all ticked)`,
      ...lines,
    ].join('\n');
  });
  return `\nArtifacts named above - read each one before you plan, and treat its open items as the work:\n${blocks.join('\n')}\n`;
}

/** What the lead is told about the user's own notes a prompt points at. */
export function notesBrief(notes: Array<{ source: string; label: string; path: string; title: string }>): string {
  if (!notes.length) return '';
  const lines = notes.map((n) => `- "${n.title}" (${n.label}): ${n.path}`);
  return `\nNotes ${userName()} pointed at - read these files before you plan. They are ${userName()}'s own notes and read-only: never change them.\n${lines.join('\n')}\n`;
}
