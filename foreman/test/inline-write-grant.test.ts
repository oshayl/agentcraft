// Inline code can construct paths and method names at runtime; a string scan cannot confine it.
// In particular, an old "Always allow" grant must not let new snippets write to read-only
// memory/library directories or follow relative symlinks out of the worktree.
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { afterEach, describe, expect, it } from 'vitest';
import { classifyToolUse, describeRuleKey, exactKey, type PolicyContext, type Verdict } from '../src/policy.js';

const wt = path.join(os.tmpdir(), 'ac-policy', 'worktrees', 'demo-app', 'ash-t76');
const worker: PolicyContext = { role: 'worker', cwd: wt, tempDirs: [] };
const LEGACY_KEY = 'Bash:inline-write';
const verdict = (command: string, ctx: PolicyContext = worker) => classifyToolUse('Bash', { command }, ctx);
const keys = (v: Verdict): string[] => (v.action === 'ask' ? v.ruleKeys : []);
const legacyGranted = (command: string, ctx: PolicyContext = worker) => verdict(command, { ...ctx, alwaysAllow: [LEGACY_KEY] });

const heredoc = `python3 - <<'EOF'
def sub(p, pairs):
    s = open(p).read()
    for a, b in pairs:
        s = s.replace(a, b)
    open(p, 'w').write(s)
sub('docs/ci-and-smoke.md', [("before", "after")])
EOF`;

describe('exact inline-code approvals', () => {
  it('asks for the exact snippet even when its visible paths are inside the worktree', () => {
    expect(keys(verdict(heredoc))).toEqual([exactKey(heredoc)]);
    expect(legacyGranted(heredoc).action).toBe('ask');
  });

  it('honors approval for that command without covering another snippet', () => {
    const ctx = { ...worker, alwaysAllow: [LEGACY_KEY, exactKey(heredoc)] };
    expect(verdict(heredoc, ctx).action).toBe('allow');
    const other = `python3 -c "open('src/app.ts','w').write('new')"`;
    expect(keys(verdict(other, ctx))).toEqual([exactKey(other)]);
  });

  it('identifies the obsolete persisted grant', () => {
    expect(describeRuleKey(LEGACY_KEY)).toBe('legacy inline-script grant (no longer used)');
  });

  it.each([
    ['Python -c', `python3 -c "print('hello')"`],
    ['Node --eval', `node --eval "console.log('hello')"`],
    ['Node --print', `node --print "1 + 1"`],
    ['Python heredoc', "python3 - <<'EOF'\nprint('hello')\nEOF"],
    ['Python here-string', `python3 <<< "print('hello')"`],
    ['Node pipeline', `echo "console.log('hello')" | node`],
    ['nested shell', `bash -c 'python3 -c "print(1)"'`],
  ])('requires exact approval for %s, including apparently read-only code', (_label, command) => {
    expect(keys(legacyGranted(command))).toEqual([exactKey(command)]);
  });
});

describe('the legacy grant never authorizes writes', () => {
  it.each([
    ['an absolute outside path', `python3 -c "open('${path.join(os.tmpdir(), 'ac-policy', 'elsewhere', 'x')}','w').write('x')"`],
    ['a home path', `python3 -c "open('~/.ssh/authorized_keys','w').write('k')"`],
    ['relative parent traversal', `python3 -c "open('../../../../elsewhere/secret.txt','w').write('x')"`],
    ['an unrecognized absolute root', `python3 -c "open('/private/other/file','w').write('x')"`],
    ['an absolute temp path', `python3 -c "open('/tmp/other-file','w').write('x')"`],
    ['a computed path', `python3 -c "p = chr(47) + 'private' + chr(47) + 'file'; open(p,'w').write('x')"`],
    ['a computed write method', `node -e "require('fs')['write' + 'FileSync']('file','x')"`],
    ['an imported module with unknown effects', `python3 -c "import local_module"`],
    ['a process', `python3 -c "import subprocess; subprocess.run(['rm','-rf','/'])"`],
    ['network access', `node -e "fetch('https://example.com').then(r=>r.text())"`],
    ['a socket', `python3 -c "import socket; socket.socket().connect(('10.0.0.1',22))"`],
    ['runtime code', `node -e "eval(process.argv[1])"`],
    ['a path from arguments', `python3 -c "import sys; open(sys.argv[1],'w').write('x')"`],
  ])('asks for exact approval for %s', (_label, command) => {
    expect(keys(legacyGranted(command))).toEqual([exactKey(command)]);
  });

  it.each(['memory', 'library'])('keeps %s readable without allowing inline writes', (dir) => {
    const readDir = path.join(os.tmpdir(), 'ac-policy', dir);
    const file = path.join(readDir, 'note.md');
    const ctx: PolicyContext = { ...worker, readDirs: [readDir] };
    expect(classifyToolUse('Read', { file_path: file }, ctx).action).toBe('allow');
    expect(classifyToolUse('Write', { file_path: file, content: 'x' }, ctx).action).toBe('ask');
    const command = `python3 -c "open('${file}','w').write('x')"`;
    expect(keys(legacyGranted(command, ctx))).toEqual([exactKey(command)]);
  });

  const roots: string[] = [];
  afterEach(() => {
    for (const root of roots.splice(0)) fs.rmSync(root, { recursive: true, force: true });
  });

  it('does not authorize a relative write through a symlink outside the worktree', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'ac-inline-policy-'));
    roots.push(root);
    const cwd = path.join(root, 'worktree');
    const outside = path.join(root, 'outside');
    fs.mkdirSync(cwd);
    fs.mkdirSync(outside);
    fs.symlinkSync(outside, path.join(cwd, 'linked'), process.platform === 'win32' ? 'junction' : 'dir');
    const ctx = { ...worker, cwd };
    expect(classifyToolUse('Write', { file_path: 'linked/file.txt', content: 'x' }, ctx).action).toBe('ask');
    const command = `python3 -c "open('linked/file.txt','w').write('x')"`;
    expect(keys(legacyGranted(command, ctx))).toEqual([exactKey(command)]);
    expect(fs.readdirSync(outside)).toEqual([]);
  });

  it('still refuses a recognizable push even if its exact command was approved', () => {
    const command = `node -e "require('child_process').execSync('git push')"`;
    expect(verdict(command, { ...worker, alwaysAllow: [LEGACY_KEY, exactKey(command)] }).action).toBe('deny');
  });

  it('also requires exact approval for a lead', () => {
    expect(keys(legacyGranted(heredoc, { ...worker, role: 'lead' }))).toEqual([`lead:${exactKey(heredoc)}`]);
  });
});
