// Read-only markdown folders the team and the user can read in game: an Obsidian vault, a docs
// directory, a handbook. Configured in ~/.agentcraft/config.json:
//
//   "library": [{ "id": "vault", "label": "Vault", "path": "/Users/me/Vault" }]
//   (or just "library": "/Users/me/Vault")
//
// Unlike memory, a source like this is far too big to ship in the snapshot - a real vault is
// thousands of notes and tens of megabytes - so nothing is sent until it is asked for. The mod
// requests an index (titles and paths, no bodies) when its tab is opened, and one body when a
// note is selected (protocol.ts: `library.request`). The index is built by walking the folder
// and cached for a few seconds, which is cheap enough to redo whenever someone looks again.
//
// Read-only on purpose: these are the user's own notes. Nothing here writes, and the policy
// (policy.ts readDirs) only stops asking about READS inside these roots. An agent that wants to
// change a note still goes through the ordinary permission path.
import fs from 'node:fs';
import path from 'node:path';

export interface LibrarySource {
  /** short, used as the tab name and in requests: "vault" */
  id: string;
  label: string;
  root: string;
}

export interface LibraryItem {
  /** path relative to the source root, e.g. "10 Projects/AgentCraft.md" */
  id: string;
  title: string;
  /** the containing folder, relative to the root ("" at the top) */
  folder: string;
  updated: number;
  size: number;
}

export class LibraryError extends Error {}

/** Directory names never walked into: editor state, trashed notes, version control, deps. */
const SKIP_DIRS = new Set(['.git', '.obsidian', '.trash', '.smart-env', 'node_modules', '.DS_Store', '.stfolder', '.sync']);
const EXTENSIONS = ['.md', '.markdown', '.txt'];
/** A walk stops here: a source this big is a mistake (a home directory, "/"), not a vault. */
const MAX_FILES = 20_000;
const INDEX_TTL_MS = 10_000;
/** One note is read in full up to this; beyond it the reader gets the head and a note saying so. */
export const MAX_BODY_BYTES = 400_000;

interface Cached {
  at: number;
  items: LibraryItem[];
  truncated: boolean;
}

function titleOf(file: string, rel: string): string {
  // a heading on the first non-empty line wins, as it does in memory.ts; else the file name
  try {
    const head = fs.readFileSync(file, { encoding: 'utf8', flag: 'r' }).slice(0, 2000);
    const m = /^#\s+(.+)$/m.exec(head.split('\n').slice(0, 12).join('\n'));
    if (m) return m[1]!.trim().slice(0, 200);
  } catch {
    /* unreadable: fall back to the name */
  }
  return path.basename(rel).replace(/\.(md|markdown|txt)$/i, '');
}

export class FileLibrary {
  private cache = new Map<string, Cached>();

  constructor(private readonly srcs: LibrarySource[]) {}

  sources(): LibrarySource[] {
    return this.srcs.filter((s) => fs.existsSync(s.root));
  }

  /** The roots, for the permission policy: reads inside these never need to ask. */
  roots(): string[] {
    return this.srcs.map((s) => s.root);
  }

  source(id: string): LibrarySource {
    const s = this.srcs.find((x) => x.id === id);
    if (!s) throw new LibraryError(`no library "${id}"${this.srcs.length ? ` (have ${this.srcs.map((x) => x.id).join(', ')})` : ' configured'}`);
    return s;
  }

  /**
   * Notes in a source, newest first. `query` filters on the title and the path, every word has
   * to appear somewhere in one of them, so "agentcraft install" finds a note in an AgentCraft
   * folder titled "Install notes".
   */
  list(sourceId: string, opts: { query?: string; limit?: number } = {}): { items: LibraryItem[]; total: number; truncated: boolean } {
    const src = this.source(sourceId);
    const index = this.index(src);
    const words = (opts.query ?? '')
      .toLowerCase()
      .split(/\s+/)
      .filter((w) => w.length > 1);
    const matched = words.length ? index.items.filter((it) => words.every((w) => it.title.toLowerCase().includes(w) || it.id.toLowerCase().includes(w))) : index.items;
    const limit = Math.max(1, Math.min(opts.limit ?? 150, 1000));
    return { items: matched.slice(0, limit), total: matched.length, truncated: index.truncated };
  }

  /** One note's text. `id` is a path relative to the root; anything outside the root is refused. */
  read(sourceId: string, id: string): LibraryItem & { body: string } {
    const src = this.source(sourceId);
    const file = this.resolve(src, id);
    const st = fs.statSync(file);
    if (!st.isFile()) throw new LibraryError(`not a file: ${id}`);
    const raw = fs.readFileSync(file, 'utf8');
    const body = raw.length > MAX_BODY_BYTES ? `${raw.slice(0, MAX_BODY_BYTES)}\n\n_(truncated: the note is ${Math.round(raw.length / 1024)} KB; open ${file} to read the rest)_` : raw;
    const rel = path.relative(src.root, file).split(path.sep).join('/');
    return { id: rel, title: titleOf(file, rel), folder: path.posix.dirname(rel) === '.' ? '' : path.posix.dirname(rel), updated: Math.floor(st.mtimeMs), size: st.size, body };
  }

  /** The absolute path of a note, refusing anything that climbs out of the source root. */
  resolve(src: LibrarySource, id: string): string {
    const root = fs.realpathSync.native(src.root);
    const full = path.resolve(root, id);
    const real = fs.existsSync(full) ? fs.realpathSync.native(full) : full;
    const inside = real === root || real.startsWith(root + path.sep);
    if (!inside) throw new LibraryError(`"${id}" is outside ${src.label}`);
    if (!fs.existsSync(real)) throw new LibraryError(`no note "${id}" in ${src.label}`);
    return real;
  }

  private index(src: LibrarySource): Cached {
    const hit = this.cache.get(src.id);
    if (hit && Date.now() - hit.at < INDEX_TTL_MS) return hit;
    const items: LibraryItem[] = [];
    let truncated = false;
    const walk = (dir: string, rel: string): void => {
      if (truncated) return;
      let ents: fs.Dirent[];
      try {
        ents = fs.readdirSync(dir, { withFileTypes: true });
      } catch {
        return; // unreadable folder: skip it rather than fail the whole index
      }
      for (const e of ents) {
        if (e.name.startsWith('.') || SKIP_DIRS.has(e.name)) continue;
        const full = path.join(dir, e.name);
        const r = rel ? `${rel}/${e.name}` : e.name;
        if (e.isDirectory()) {
          walk(full, r);
          if (truncated) return;
        } else if (e.isFile() && EXTENSIONS.some((x) => e.name.toLowerCase().endsWith(x))) {
          if (items.length >= MAX_FILES) {
            truncated = true;
            return;
          }
          let st: fs.Stats;
          try {
            st = fs.statSync(full);
          } catch {
            continue;
          }
          items.push({ id: r, title: titleOf(full, r), folder: rel, updated: Math.floor(st.mtimeMs), size: st.size });
        }
      }
    };
    walk(src.root, '');
    items.sort((a, b) => b.updated - a.updated);
    const cached: Cached = { at: Date.now(), items, truncated };
    this.cache.set(src.id, cached);
    return cached;
  }
}

/** `library` from config.json / the --library flag: a path, a list of paths, or full entries. */
export function librarySourcesFrom(v: unknown, home: string): LibrarySource[] {
  if (v === undefined || v === null || v === '') return [];
  const raw = Array.isArray(v) ? v : typeof v === 'string' ? v.split(',') : [v];
  const out: LibrarySource[] = [];
  for (const item of raw) {
    const entry = typeof item === 'string' ? { path: item.trim() } : (item as Record<string, unknown>);
    const p = typeof entry.path === 'string' ? entry.path.trim() : '';
    if (!p) continue;
    const root = path.resolve(p.startsWith('~') ? path.join(home, p.slice(1)) : p);
    const base = path.basename(root) || 'files';
    const id = String(entry.id ?? base).toLowerCase().replace(/[^a-z0-9_-]+/g, '-').replace(/^-+|-+$/g, '') || 'files';
    if (out.some((s) => s.id === id)) continue;
    out.push({ id, label: String(entry.label ?? base), root });
  }
  return out;
}
