// Memory: markdown files, shared + per-agent + artifacts, under <profile>/memory.
//
//   memory/shared/<slug>.md
//   memory/agents/<agentId>/<slug>.md
//   memory/artifacts/<slug>.md
//
// Artifacts are the team's durable output rather than its scratch notes: one per finished goal,
// written by the lead (write_artifact, see agents/tools.ts) with an itemised list of what is
// still open. Everyone can read them, the user can open the file in any editor, and a later goal
// can name one ("read a3 and do the open items"), which is why they have their own scope: they
// outlive the goal that produced them, and the library shows them under their own tab.
//
// Each file starts with a tiny front matter block (title/author/updated). Files the user drops in
// or edits by hand are picked up too (title falls back to the first heading or the file name).
import fs from 'node:fs';
import path from 'node:path';
import type { Ctx } from './context.js';
import type { MemoryEntry } from './protocol.js';
import { ensureDir, writeFileAtomic } from './util/fsx.js';
import { slugify } from './util/text.js';

export class MemoryError extends Error {}

/** The memory scope holding artifacts (see the header). */
export const ARTIFACT_SCOPE = 'artifacts';

interface Parsed {
  title?: string;
  author?: string;
  updated?: number;
  body: string;
}

function parseFile(raw: string): Parsed {
  const text = raw.replace(/\r\n/g, '\n');
  if (!text.startsWith('---\n')) return { body: text };
  const end = text.indexOf('\n---\n', 4);
  if (end < 0) return { body: text };
  const header = text.slice(4, end);
  // serialize() ends the file with exactly one newline after the body: strip that one, so a body
  // round-trips byte for byte across restarts
  const body = text.slice(end + 5).replace(/^\n/, '').replace(/\n$/, '');
  const out: Parsed = { body };
  for (const line of header.split('\n')) {
    const m = /^(\w+):\s*(.*)$/.exec(line);
    if (!m) continue;
    const [, k, v] = m;
    if (k === 'title') out.title = v;
    else if (k === 'author') out.author = v;
    else if (k === 'updated' && v && /^\d+$/.test(v)) out.updated = Number(v);
  }
  return out;
}

function serialize(e: MemoryEntry): string {
  const title = e.title.replace(/\n/g, ' ');
  return `---\ntitle: ${title}\nauthor: ${e.author ?? ''}\nupdated: ${e.updated}\n---\n${e.body}\n`;
}

export class Memory {
  readonly dir: string;
  private entries = new Map<string, MemoryEntry>();

  constructor(
    private ctx: Ctx,
    dir: string,
  ) {
    this.dir = ensureDir(dir);
    ensureDir(path.join(this.dir, 'shared'));
    ensureDir(path.join(this.dir, 'agents'));
    ensureDir(path.join(this.dir, ARTIFACT_SCOPE));
    this.reload();
  }

  private fileFor(id: string): string {
    const [scope, slug] = this.splitId(id);
    if (scope === 'shared') return path.join(this.dir, 'shared', `${slug}.md`);
    if (scope === ARTIFACT_SCOPE) return path.join(this.dir, ARTIFACT_SCOPE, `${slug}.md`);
    return path.join(this.dir, 'agents', scope, `${slug}.md`);
  }

  private splitId(id: string): [string, string] {
    const i = id.indexOf('/');
    if (i <= 0) throw new MemoryError(`bad memory id ${id}`);
    const scope = id.slice(0, i);
    const slug = id.slice(i + 1);
    if (!/^[a-z0-9_-]+$/i.test(scope) || !/^[a-z0-9-]+$/.test(slug)) throw new MemoryError(`bad memory id ${id}`);
    return [scope, slug];
  }

  /** Re-scan the memory directory (picks up hand-edited files). */
  reload(): void {
    this.entries.clear();
    const load = (scope: string, d: string) => {
      if (!fs.existsSync(d)) return;
      for (const f of fs.readdirSync(d)) {
        if (!f.endsWith('.md')) continue;
        const slug = f.slice(0, -3);
        if (!/^[a-z0-9-]+$/.test(slug)) continue;
        const full = path.join(d, f);
        const p = parseFile(fs.readFileSync(full, 'utf8'));
        const heading = /^#\s+(.+)$/m.exec(p.body)?.[1];
        const e: MemoryEntry = {
          id: `${scope}/${slug}`,
          scope,
          title: p.title || heading || slug,
          body: p.body,
          updated: p.updated ?? Math.floor(fs.statSync(full).mtimeMs),
        };
        if (p.author) e.author = p.author;
        this.entries.set(e.id, e);
      }
    };
    load('shared', path.join(this.dir, 'shared'));
    load(ARTIFACT_SCOPE, path.join(this.dir, ARTIFACT_SCOPE));
    const agentsDir = path.join(this.dir, 'agents');
    for (const a of fs.existsSync(agentsDir) ? fs.readdirSync(agentsDir) : []) {
      if (/^[a-z0-9_-]+$/i.test(a)) load(a, path.join(agentsDir, a));
    }
  }

  list(): MemoryEntry[] {
    return [...this.entries.values()].sort((a, b) => a.updated - b.updated);
  }

  get(id: string): MemoryEntry | undefined {
    return this.entries.get(id);
  }

  /** Shared notes, this agent's own private ones, and every artifact (those are for everyone). */
  visibleTo(agentId: string): MemoryEntry[] {
    return this.list().filter((e) => e.scope === 'shared' || e.scope === ARTIFACT_SCOPE || e.scope === agentId);
  }

  /** The artifacts, newest first: the team's durable output, one per finished goal. */
  artifacts(): MemoryEntry[] {
    return this.list().filter((e) => e.scope === ARTIFACT_SCOPE).reverse();
  }

  /** The file an entry is stored in, for telling an agent (or the user) where to read it. */
  pathOf(id: string): string {
    return this.fileFor(id);
  }

  search(query: string, agentId?: string): MemoryEntry[] {
    const q = query.toLowerCase();
    const pool = agentId ? this.visibleTo(agentId) : this.list();
    return pool.filter((e) => e.title.toLowerCase().includes(q) || e.body.toLowerCase().includes(q));
  }

  /**
   * Write (create or replace/append) an entry. `scope` is "shared" or an agent id.
   * `slug` defaults to slugify(title).
   */
  write(input: { scope: string; title: string; body: string; author: string; slug?: string; mode?: 'replace' | 'append' }): MemoryEntry {
    const scope = input.scope;
    if (!/^[a-z0-9_-]+$/i.test(scope)) throw new MemoryError(`bad memory scope ${scope}`);
    const slug = input.slug ? slugify(input.slug, 48) : slugify(input.title, 48);
    const id = `${scope}/${slug}`;
    const prev = this.entries.get(id);
    const body = input.mode === 'append' && prev ? `${prev.body.replace(/\n+$/, '')}\n\n${input.body}` : input.body;
    const e: MemoryEntry = { id, scope, title: input.title.trim() || slug, body, updated: this.ctx.now(), author: input.author };
    writeFileAtomic(this.fileFor(id), serialize(e));
    this.entries.set(id, e);
    this.ctx.emit({ type: 'memory.upsert', entry: e });
    return e;
  }
}
