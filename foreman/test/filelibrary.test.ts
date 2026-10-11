// Read-only markdown folders (an Obsidian vault, a docs directory) browsed in game. The two
// things that matter: nothing outside the configured root is ever readable, and a folder far
// too large to ship in a snapshot is still usable - which is why this is a request, not state.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { FileLibrary, LibraryError, librarySourcesFrom, MAX_BODY_BYTES } from '../src/filelibrary.js';
import { rmrf, tempDir } from './helpers.js';

let root: string;
let lib: FileLibrary;

beforeAll(() => {
  root = tempDir('ac-vault-');
  const write = (rel: string, text: string, ageMs = 0): void => {
    const file = path.join(root, rel);
    fs.mkdirSync(path.dirname(file), { recursive: true });
    fs.writeFileSync(file, text);
    if (ageMs) fs.utimesSync(file, new Date(), new Date(Date.now() - ageMs));
  };
  write('Home.md', '# Home\n\nthe front page\n', 60_000);
  write('10 Projects/AgentCraft.md', '# AgentCraft install\n\nnotes about the install\n');
  write('10 Projects/Sundancer.md', 'no heading here\n', 30_000);
  write('20 Knowledge/deep/Nested.md', '# Nested\n', 90_000);
  write('.obsidian/workspace.json', '{"ignored":true}');
  write('.trash/Deleted.md', '# Deleted\n');
  write('_attachments/photo.png', 'not markdown');
  lib = new FileLibrary([{ id: 'vault', label: 'Vault', root }]);
});
afterAll(() => rmrf(root));

describe('indexing a folder', () => {
  it('lists markdown newest first, with the heading as the title', () => {
    const { items } = lib.list('vault');
    expect(items[0]!.title).toBe('AgentCraft install');
    expect(items[0]!.id).toBe('10 Projects/AgentCraft.md');
    expect(items[0]!.folder).toBe('10 Projects');
    expect(items.map((i) => i.title)).toContain('Home');
    // no heading: the file name stands in
    expect(items.find((i) => i.id.endsWith('Sundancer.md'))!.title).toBe('Sundancer');
  });

  it('skips editor state, trashed notes and anything that is not markdown', () => {
    const ids = lib.list('vault').items.map((i) => i.id);
    expect(ids.some((i) => i.startsWith('.obsidian'))).toBe(false);
    expect(ids.some((i) => i.startsWith('.trash'))).toBe(false);
    expect(ids.some((i) => i.endsWith('.png'))).toBe(false);
    expect(ids).toContain('20 Knowledge/deep/Nested.md');
  });

  it('searches the title and the path, every word', () => {
    expect(lib.list('vault', { query: 'agentcraft' }).items).toHaveLength(1);
    expect(lib.list('vault', { query: 'projects install' }).items).toHaveLength(1);
    expect(lib.list('vault', { query: 'projects nothing' }).items).toHaveLength(0);
  });

  it('caps what one answer carries, and says how many matched', () => {
    const r = lib.list('vault', { limit: 2 });
    expect(r.items).toHaveLength(2);
    expect(r.total).toBeGreaterThan(2);
  });

  it('names the libraries it has when asked for one it does not', () => {
    expect(() => lib.list('nope')).toThrow(/no library "nope".*vault/s);
  });
});

describe('reading one note', () => {
  it('returns the text', () => {
    const note = lib.read('vault', '10 Projects/AgentCraft.md');
    expect(note.body).toContain('notes about the install');
    expect(note.title).toBe('AgentCraft install');
  });

  // The id comes from a client. A vault sits in a home directory, so an id that climbs out of
  // the root would hand back anything the Foreman can read.
  it('refuses to climb out of the root, however the path is written', () => {
    for (const bad of ['../outside.md', '../../etc/hosts', '10 Projects/../../outside.md', '/etc/hosts']) {
      expect(() => lib.read('vault', bad), bad).toThrow(LibraryError);
    }
  });

  it('refuses a note that is not there', () => {
    expect(() => lib.read('vault', 'nothing.md')).toThrow(/no note "nothing.md"/);
  });

  it('truncates a note too big to send, and says so', () => {
    const big = path.join(root, 'Big.md');
    fs.writeFileSync(big, 'x'.repeat(MAX_BODY_BYTES + 5000));
    try {
      const note = lib.read('vault', 'Big.md');
      expect(note.body.length).toBeLessThan(MAX_BODY_BYTES + 500);
      expect(note.body).toContain('truncated');
    } finally {
      fs.rmSync(big);
    }
  });
});

describe('configuring sources', () => {
  it('takes a bare path, a list, or full entries', () => {
    const home = os.homedir();
    expect(librarySourcesFrom('/tmp/Vault', home)).toEqual([{ id: 'vault', label: 'Vault', root: path.resolve('/tmp/Vault') }]);
    expect(librarySourcesFrom([{ id: 'docs', label: 'Docs', path: '/tmp/d' }], home)[0]!.id).toBe('docs');
    expect(librarySourcesFrom('~/Vault', home)[0]!.root).toBe(path.join(home, 'Vault'));
    expect(librarySourcesFrom(undefined, home)).toEqual([]);
    expect(librarySourcesFrom('', home)).toEqual([]);
  });

  it('keeps ids unique and usable as a tab name', () => {
    const srcs = librarySourcesFrom(['/tmp/My Notes!', '/tmp/other/My Notes!'], os.homedir());
    expect(srcs).toHaveLength(1);
    expect(srcs[0]!.id).toBe('my-notes');
  });

  it('hides a source whose folder is not there, rather than failing at startup', () => {
    const gone = new FileLibrary([{ id: 'gone', label: 'Gone', root: path.join(root, 'no-such-dir') }]);
    expect(gone.sources()).toEqual([]);
    expect(gone.roots()).toHaveLength(1);
  });
});
