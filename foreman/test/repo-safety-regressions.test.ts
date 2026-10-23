import fs from 'node:fs';
import path from 'node:path';
import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Decision, Repo, Worktree } from '../src/protocol.js';
import { git, gitOut } from '../src/util/git.js';
import { makeForeman, rmrf, tempDir, type Harness } from './helpers.js';

const cases: Array<{ home: string; h: Harness }> = [];

async function setup(name = 'repo'): Promise<{ h: Harness; root: string; repo: Repo }> {
  const home = tempDir('ac-repo-safety-');
  const root = path.join(home, name);
  fs.mkdirSync(root);
  const h = makeForeman(path.join(home, 'profile'), ['--backend', 'sim']);
  cases.push({ home, h });
  await git(root, ['init', '-q', '-b', 'main']);
  await git(root, ['config', 'user.name', 'Safety Test']);
  await git(root, ['config', 'user.email', 'safety@example.invalid']);
  fs.writeFileSync(path.join(root, 'tracked.txt'), 'initial\n');
  await git(root, ['add', '.']);
  await git(root, ['commit', '-qm', 'initial']);
  return { h, root, repo: await h.fm.repos.add(root) };
}

async function worker(h: Harness, repo: Repo): Promise<Worktree> {
  return h.fm.repos.createWorktree(repo.id, 'kit', { id: 't1', title: 'Safety regression' });
}

function approval(repo: Repo, wt: Worktree): Decision {
  return {
    id: 'safety-merge', agentId: 'marlow', kind: 'merge', status: 'answered',
    question: 'Merge?', options: ['Merge'], createdAt: 1,
    repoId: repo.id, worktree: wt.id, answer: { option: 'Merge', ts: 1 },
  };
}

afterEach(async () => {
  for (const { home, h } of cases.splice(0)) {
    await h.fm.close();
    rmrf(home);
  }
});

describe('repository safety regressions', () => {
  it('retains uncommitted work after a failed abandonment checkpoint, and can retry', async () => {
    const { h, root, repo } = await setup();
    const wt = await worker(h, repo);
    const unique = path.join(wt.path, 'never-committed.txt');
    fs.writeFileSync(unique, 'valuable unique work\n');
    const gitDir = await gitOut(wt.path, ['rev-parse', '--absolute-git-dir']);
    const lock = path.join(gitDir, 'index.lock');
    fs.writeFileSync(lock, 'simulated transient lock');

    await expect(h.fm.repos.abandon(repo.id, wt.id)).rejects.toThrow(/uncommitted work was left in place/);
    expect(wt.status).toBe('active');
    expect(fs.readFileSync(unique, 'utf8')).toBe('valuable unique work\n');
    expect(await h.fm.repos.sweepPendingRemovals()).toBe(0);
    expect(fs.existsSync(unique)).toBe(true);

    fs.rmSync(lock);
    await h.fm.repos.abandon(repo.id, wt.id);
    expect(wt.status).toBe('abandoned');
    expect(fs.existsSync(wt.path)).toBe(false);
    expect(await gitOut(root, ['show', `${wt.branch}:never-committed.txt`])).toBe('valuable unique work');
  });

  it.each(['.env', 'private "draft".env', 'private\nnote.env', 'private[draft].env'])('preserves ignored user file %j through an explicit set-aside merge', async (file) => {
    const { h, root, repo } = await setup();
    fs.writeFileSync(path.join(root, '.gitignore'), '*.env\n');
    await git(root, ['add', '.gitignore']);
    await git(root, ['commit', '-qm', 'ignore local environment']);
    fs.writeFileSync(path.join(root, file), 'user private work\n');
    const wt = await worker(h, repo);
    fs.writeFileSync(path.join(wt.path, '.gitignore'), '');
    fs.writeFileSync(path.join(wt.path, file), 'worker default\n');
    const baseBefore = await gitOut(root, ['rev-parse', 'HEAD']);

    expect(await h.fm.repos.collidingUntracked(repo.id, wt.id)).toEqual([file]);
    expect(await h.fm.repos.canMerge(repo.id, wt.id)).toMatchObject({ ok: false, code: 'untracked', files: [file] });
    await expect(h.fm.repos.merge(approval(repo, wt))).rejects.toMatchObject({ code: 'untracked' });
    expect(await gitOut(root, ['rev-parse', 'HEAD'])).toBe(baseBefore);
    expect(fs.readFileSync(path.join(root, file), 'utf8')).toBe('user private work\n');

    const aside = await h.fm.repos.setAsideUntracked(repo.id, wt.id);
    expect(aside.files).toEqual([file]);
    expect(fs.readFileSync(path.join(aside.dir, file), 'utf8')).toBe('user private work\n');
    await h.fm.repos.merge(approval(repo, wt));
    expect(fs.readFileSync(path.join(root, file), 'utf8')).toBe('worker default\n');
    expect(fs.readFileSync(path.join(aside.dir, file), 'utf8')).toBe('user private work\n');
  });

  it('preserves ignored directory contents when the incoming branch replaces the directory with a file', async () => {
    const { h, root, repo } = await setup();
    fs.writeFileSync(path.join(root, '.gitignore'), '.cache/\n');
    await git(root, ['add', '.gitignore']);
    await git(root, ['commit', '-qm', 'ignore local cache']);
    fs.mkdirSync(path.join(root, '.cache'));
    fs.writeFileSync(path.join(root, '.cache', 'local.txt'), 'local work\n');
    const wt = await worker(h, repo);
    fs.writeFileSync(path.join(wt.path, '.cache'), 'worker file\n');
    expect(await h.fm.repos.collidingUntracked(repo.id, wt.id)).toEqual(['.cache/local.txt']);
    const aside = await h.fm.repos.setAsideUntracked(repo.id, wt.id);
    await h.fm.repos.merge(approval(repo, wt));
    expect(fs.readFileSync(path.join(root, '.cache'), 'utf8')).toBe('worker file\n');
    expect(fs.readFileSync(path.join(aside.dir, '.cache', 'local.txt'), 'utf8')).toBe('local work\n');
  });

  it('refuses to overwrite an ignored file created after the merge safety check', async () => {
    const { h, root, repo } = await setup();
    fs.writeFileSync(path.join(root, '.gitignore'), '.env\n');
    await git(root, ['add', '.gitignore']);
    await git(root, ['commit', '-qm', 'ignore local environment']);
    const wt = await worker(h, repo);
    fs.writeFileSync(path.join(wt.path, '.gitignore'), '');
    fs.writeFileSync(path.join(wt.path, '.env'), 'worker default\n');
    const baseBefore = await gitOut(root, ['rev-parse', 'HEAD']);
    const check = h.fm.repos.canMerge.bind(h.fm.repos);
    vi.spyOn(h.fm.repos, 'canMerge').mockImplementationOnce(async (...args) => {
      const result = await check(...args);
      expect(result).toEqual({ ok: true });
      fs.writeFileSync(path.join(root, '.env'), 'created during merge\n');
      return result;
    });

    await expect(h.fm.repos.merge(approval(repo, wt))).rejects.toMatchObject({ code: 'refused' });
    expect(await gitOut(root, ['rev-parse', 'HEAD'])).toBe(baseBefore);
    expect(fs.readFileSync(path.join(root, '.env'), 'utf8')).toBe('created during merge\n');
    expect(wt.status).toBe('active');
  });

  it('preserves an ignored parent file that blocks an incoming directory', async () => {
    const { h, root, repo } = await setup();
    fs.writeFileSync(path.join(root, '.gitignore'), 'folder\n');
    await git(root, ['add', '.gitignore']);
    await git(root, ['commit', '-qm', 'ignore local file']);
    fs.writeFileSync(path.join(root, 'folder'), 'local work\n');
    const wt = await worker(h, repo);
    fs.writeFileSync(path.join(wt.path, '.gitignore'), '');
    fs.mkdirSync(path.join(wt.path, 'folder'));
    fs.writeFileSync(path.join(wt.path, 'folder', 'child.txt'), 'worker file\n');
    expect(await h.fm.repos.collidingUntracked(repo.id, wt.id)).toEqual(['folder']);
    const aside = await h.fm.repos.setAsideUntracked(repo.id, wt.id);
    await h.fm.repos.merge(approval(repo, wt));
    expect(fs.readFileSync(path.join(root, 'folder', 'child.txt'), 'utf8')).toBe('worker file\n');
    expect(fs.readFileSync(path.join(aside.dir, 'folder'), 'utf8')).toBe('local work\n');
  });

  it.each(['land', 'park', 'unpark'] as const)('refuses %s when the registered base branch is not checked out', async (operation) => {
    const { h, root, repo } = await setup();
    await git(root, ['checkout', '-qb', 'user-feature']);
    fs.appendFileSync(path.join(root, 'tracked.txt'), 'pending work\n');
    // A checkpoint subject on another branch must not authorize unpark to rewrite that branch.
    if (operation === 'unpark') {
      await git(root, ['commit', '-qam', 'WIP checkpoint (parked from AgentCraft)']);
    }
    const headBefore = await gitOut(root, ['rev-parse', 'HEAD']);
    const baseBefore = await gitOut(root, ['rev-parse', 'main']);
    const statusBefore = (await git(root, ['status', '--porcelain'])).stdout;
    const result = operation === 'land' ? h.fm.repos.land(repo.id, 'my work') : h.fm.repos[operation](repo.id);
    await expect(result).rejects.toMatchObject({ code: 'refused' });
    expect(await gitOut(root, ['rev-parse', 'HEAD'])).toBe(headBefore);
    expect(await gitOut(root, ['rev-parse', 'main'])).toBe(baseBefore);
    expect((await git(root, ['status', '--porcelain'])).stdout).toBe(statusBefore);
  });

  it('uses another checkout that actually holds the base branch for fixes', async () => {
    const { h, root, repo } = await setup();
    await git(root, ['checkout', '-qb', 'user-feature']);
    const base = path.join(path.dirname(root), 'base-checkout');
    await git(root, ['worktree', 'add', '-q', base, 'main']);
    fs.appendFileSync(path.join(base, 'tracked.txt'), 'base work\n');
    fs.appendFileSync(path.join(root, 'tracked.txt'), 'unrelated work\n');
    const otherBefore = await gitOut(root, ['rev-parse', 'HEAD']);
    await h.fm.repos.land(repo.id, 'commit base work');
    expect(await gitOut(root, ['rev-parse', 'HEAD'])).toBe(otherBefore);
    expect(await gitOut(base, ['log', '-1', '--format=%s'])).toBe('commit base work');
    expect(fs.readFileSync(path.join(root, 'tracked.txt'), 'utf8')).toContain('unrelated work');
  });

  it('copies dependencies from literal shell-looking repository names without expanding them', async () => {
    const { h, root, repo } = await setup('repo-${AC_REPO_LITERAL_TEST}-$(touch shell-expanded)');
    const bins = path.join(root, 'node_modules', '.bin');
    fs.mkdirSync(bins, { recursive: true });
    fs.writeFileSync(path.join(bins, 'test-bin'), 'dependency marker');
    const wt = await worker(h, repo);
    expect(await h.fm.repos.linkDeps(repo.id)).toContain(wt.id);
    expect(fs.readFileSync(path.join(wt.path, 'node_modules', '.bin', 'test-bin'), 'utf8')).toBe('dependency marker');
    expect(fs.existsSync(path.join(wt.path, 'shell-expanded'))).toBe(false);
  });
});
