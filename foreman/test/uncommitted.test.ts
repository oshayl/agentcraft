// Reviewing and landing the user's own uncommitted work, instead of a merge just refusing.
//
// The distinction that matters throughout: only MODIFIED TRACKED files block a merge
// (isDirty uses --untracked-files=no), so untracked files are reported but never committed.
import fs from 'node:fs';
import path from 'node:path';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { MERGE_OPTIONS, MERGE_WITH_LAND_OPTION, type Decision } from '../src/protocol.js';
import { formatUncommittedReview } from '../src/repos.js';
import { git, gitOut } from '../src/util/git.js';
import { demoRepo, makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

let h: Harness;
let home: string;
let repoPath: string;
let repoId: string;

const write = (rel: string, body: string) => fs.writeFileSync(path.join(repoPath, rel), body);
const firstTracked = async () => (await gitOut(repoPath, ['ls-files'])).split('\n').filter(Boolean)[0]!;

beforeAll(async () => {
  home = tempDir();
  repoPath = await demoRepo();
  h = makeForeman(home, ['--backend', 'sim']);
  repoId = (await h.fm.repos.add(repoPath)).id;
});
afterAll(async () => {
  await h.fm.close();
  rmrf(home);
  rmrf(path.dirname(repoPath));
});

describe('uncommittedReview', () => {
  it('reports nothing on a clean checkout', async () => {
    const rev = await h.fm.repos.uncommittedReview(repoId);
    expect(rev.files).toEqual([]);
    expect(formatUncommittedReview(rev)[0]).toMatch(/nothing uncommitted/);
  });

  it('lists modified tracked files with their counts and a diff', async () => {
    const f = await firstTracked();
    const original = fs.readFileSync(path.join(repoPath, f), 'utf8');
    write(f, `${original}\n// a line the user added\n`);
    const rev = await h.fm.repos.uncommittedReview(repoId);
    expect(rev.files.map((x) => x.path)).toContain(f);
    expect(rev.additions).toBeGreaterThan(0);
    expect(rev.diff).toContain('a line the user added');
    const lines = formatUncommittedReview(rev);
    expect(lines[0]).toMatch(/1 modified file\(s\).*block merges/);
    // restore for the next tests
    write(f, original);
  });

  it('reports untracked files separately, and does not promise they are harmless', async () => {
    write('a-brand-new-file.txt', 'scratch\n');
    const rev = await h.fm.repos.uncommittedReview(repoId);
    expect(rev.files).toEqual([]);
    expect(rev.untracked).toContain('a-brand-new-file.txt');
    // this used to read "they never block a merge", which is true until a branch adds the same
    // file - and then git aborts the merge at the last step
    expect(formatUncommittedReview(rev)[0]).toMatch(/only block if the branch adds the same ones/);
  });

  // The real case: the user has files locally that an agent's branch also adds. Git refuses to
  // overwrite a file it has no record of, so a merge that looked fine dies with "please move or
  // remove them before you merge".
  it('finds untracked files that the branch would overwrite, and moves them aside', async () => {
    const wt = await h.fm.repos.createWorktree(repoId, 'kit', h.fm.tasks.create({ title: 'adds a file', createdBy: 'marlow', repoId, assignee: 'kit' }));
    fs.writeFileSync(path.join(wt.path, 'shared-name.txt'), 'the agent version\n');
    await h.fm.repos.commitAll(repoId, wt.id, 'agent adds a file');

    write('shared-name.txt', 'my own version\n');
    const clashing = await h.fm.repos.collidingUntracked(repoId, wt.id);
    expect(clashing).toEqual(['shared-name.txt']);

    const check = await h.fm.repos.canMerge(repoId, wt.id);
    expect(check.ok, 'the merge is refused before it is attempted').toBe(false);
    if (!check.ok) {
      expect(check.code).toBe('untracked');
      expect(check.reason).toContain('shared-name.txt');
    }

    const aside = await h.fm.repos.setAsideUntracked(repoId, wt.id);
    expect(aside.files).toEqual(['shared-name.txt']);
    // nothing of theirs is destroyed: the file moved, with its path kept
    expect(fs.readFileSync(path.join(aside.dir, 'shared-name.txt'), 'utf8')).toBe('my own version\n');
    expect(fs.existsSync(path.join(repoPath, 'shared-name.txt'))).toBe(false);
    expect((await h.fm.repos.canMerge(repoId, wt.id)).ok, 'and now it can merge').toBe(true);
  });

  it('flags overlap with the branch waiting to merge, and its absence', async () => {
    const f = await firstTracked();
    const original = fs.readFileSync(path.join(repoPath, f), 'utf8');
    // a branch that changes the same file
    await git(repoPath, ['checkout', '-q', '-b', 'touches-same']);
    write(f, `${original}\n// from the branch\n`);
    await git(repoPath, ['add', '-A']);
    await git(repoPath, ['-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'branch change']);
    await git(repoPath, ['checkout', '-q', 'main']);
    write(f, `${original}\n// the user's own edit\n`);

    const clash = await h.fm.repos.uncommittedReview(repoId, 'touches-same');
    expect(clash.overlapping).toContain(f);
    expect(formatUncommittedReview(clash).join('\n')).toMatch(/careful/);

    // a branch that changes something else entirely. Stage only that file: `git add -A` here
    // would sweep up the user's own uncommitted edit and leave the tree clean.
    write(f, original);
    await git(repoPath, ['checkout', '-q', '-b', 'touches-other', 'main']);
    write('unrelated.txt', 'other\n');
    await git(repoPath, ['add', '--', 'unrelated.txt']);
    await git(repoPath, ['-c', 'user.name=t', '-c', 'user.email=t@t', 'commit', '-qm', 'other change']);
    await git(repoPath, ['checkout', '-q', 'main']);
    write(f, `${original}\n// the user's own edit again\n`);

    const clear = await h.fm.repos.uncommittedReview(repoId, 'touches-other');
    expect(clear.overlapping).toEqual([]);
    expect(formatUncommittedReview(clear).join('\n')).toMatch(/cannot conflict/);
    write(f, original);
  });
});

describe('land', () => {
  it('needs a message, and will not pose as a park checkpoint', async () => {
    await expect(h.fm.repos.land(repoId, '   ')).rejects.toThrow(/needs a message/);
    await expect(h.fm.repos.land(repoId, 'WIP checkpoint (parked from AgentCraft)')).rejects.toThrow(/use park instead/);
  });

  it('commits modified tracked files and leaves untracked ones alone', async () => {
    const f = await firstTracked();
    const original = fs.readFileSync(path.join(repoPath, f), 'utf8');
    write(f, `${original}\n// landing this\n`);
    write('still-untracked.txt', 'left alone\n');
    const before = await gitOut(repoPath, ['rev-parse', 'HEAD']);

    const out = await h.fm.repos.land(repoId, 'My own work in progress');
    expect(out).toMatch(/committed as [0-9a-f]+/);
    expect(await gitOut(repoPath, ['rev-parse', 'HEAD'])).not.toBe(before);
    expect(await gitOut(repoPath, ['log', '-1', '--format=%s'])).toBe('My own work in progress');
    // the merge blocker is gone, the untracked file is untouched
    expect(await h.fm.repos.isDirty(repoPath)).toBe(false);
    expect(fs.existsSync(path.join(repoPath, 'still-untracked.txt'))).toBe(true);
    expect((await gitOut(repoPath, ['ls-files', '--others', '--exclude-standard'])).split('\n')).toContain('still-untracked.txt');
  });

  it('says so plainly when there is nothing to commit', async () => {
    expect(await h.fm.repos.land(repoId, 'nothing here')).toMatch(/nothing to commit/);
  });
});

describe('a merge decision that also lands the user work', () => {
  it('accepts the commit-then-merge option where a plain merge decision would be refused', async () => {
    // a worktree with real work to merge
    const wt = await h.fm.repos.createWorktree(repoId, 'kit', { id: 't1', title: 'Agent work' });
    fs.writeFileSync(path.join(wt.path, 'from-agent.txt'), 'agent work\n');

    // the user has their own uncommitted change in the way
    const f = await firstTracked();
    const original = fs.readFileSync(path.join(repoPath, f), 'utf8');
    write(f, `${original}\n// user work blocking the merge\n`);
    expect(await h.fm.repos.isDirty(repoPath)).toBe(true);

    const plain: Decision = h.fm.createDecision({ agentId: 'marlow', kind: 'merge', question: 'Merge?', options: [...MERGE_OPTIONS], repoId, worktree: wt.id });
    h.fm.decisions.answer(plain.id, 'Merge');
    // a plain merge is refused while the checkout is dirty
    await expect(h.fm.repos.merge(h.fm.decisions.get(plain.id)!)).rejects.toThrow(/uncommitted changes/);

    // the same merge, answered with the option that commits the user work first
    await h.fm.repos.land(repoId, 'User work committed before the merge');
    const withLand: Decision = h.fm.createDecision({
      agentId: 'marlow',
      kind: 'merge',
      question: 'Merge?',
      options: [MERGE_OPTIONS[0], MERGE_WITH_LAND_OPTION, ...MERGE_OPTIONS.slice(1)],
      repoId,
      worktree: wt.id,
    });
    h.fm.decisions.answer(withLand.id, MERGE_WITH_LAND_OPTION);
    const res = await h.fm.repos.merge(h.fm.decisions.get(withLand.id)!);
    expect(res.sha).toMatch(/^[0-9a-f]{7}$/);
  });
});
