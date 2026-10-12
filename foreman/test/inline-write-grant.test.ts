// "Always allow for this agent" must be able to stick.
//
// Every heredoc is different text, so an exact-command grant never matched a second time: 311
// prompts were answered, 236 dead keys were banked, and the agents stood waiting 11 hours. The
// work was always the same shape, so it gets a key of that shape - but only where the snippet
// plainly cannot leave the worktree, because for a Claude worker there is no sandbox underneath
// and this check is the whole boundary.
import os from 'node:os';
import path from 'node:path';
import { describe, expect, it } from 'vitest';
import { classifyToolUse, describeRuleKey, type PolicyContext, type Verdict } from '../src/policy.js';

const wt = path.join(os.tmpdir(), 'ac-policy', 'worktrees', 'demo-app', 'ash-t76');
const worker: PolicyContext = { role: 'worker', cwd: wt, tempDirs: [] };
const lead: PolicyContext = { role: 'lead', cwd: wt, tempDirs: [] };
const KEY = 'Bash:inline-write';

const verdict = (command: string, ctx: PolicyContext = worker) => classifyToolUse('Bash', { command }, ctx);
/** rule keys, which only an 'ask' carries */
const keys = (v: Verdict): string[] => (v.action === 'ask' ? v.ruleKeys : []);
const granted = (command: string) => classifyToolUse('Bash', { command }, { ...worker, alwaysAllow: [KEY] });

// the real one out of Bryn's log, shortened
const heredoc = `python3 - <<'EOF'
def sub(p, pairs):
    s = open(p).read()
    for a, b in pairs:
        s = s.replace(a, b)
    open(p, 'w').write(s)
sub('docs/ci-and-smoke.md', [("before", "after")])
EOF`;

describe('inline code that only edits the worktree', () => {
  it('asks once with a key that can be granted', () => {
    const v = verdict(heredoc);
    expect(v.action).toBe('ask');
    expect(keys(v)).toContain(KEY);
    expect(keys(v).some((k) => k.startsWith('Bash:exact:'))).toBe(false);
  });

  it('stops asking once that key is granted', () => {
    expect(granted(heredoc).action).toBe('allow');
  });

  it('covers the next snippet too, which is the whole point', () => {
    const other = `python3 - <<'EOF'\np = 'src/app.ts'\nopen(p, 'w').write(open(p).read().replace('a', 'b'))\nEOF`;
    expect(keys(verdict(other))).toContain(KEY);
    expect(granted(other).action).toBe('allow');
  });

  it('says in plain words what it covers', () => {
    expect(describeRuleKey(KEY)).toBe('inline scripts that change files inside this worktree');
  });
});

describe('what the grant must never cover', () => {
  const refuses = (label: string, command: string) =>
    it(label, () => {
      const v = verdict(command);
      expect(v.action).not.toBe('allow');
      expect(keys(v)).not.toContain(KEY);
      // still granted one command at a time
      if (v.action === 'ask') expect(keys(v).some((k) => k.startsWith('Bash:exact:'))).toBe(true);
      expect(granted(command).action).not.toBe('allow');
    });

  refuses('writing a file outside the worktree', `python3 -c "open('${path.join(os.tmpdir(), 'ac-policy', 'elsewhere', 'x')}','w').write('x')"`);
  refuses('writing through the home directory', `python3 -c "open('~/.ssh/authorized_keys','w').write('k')"`);
  refuses('climbing out with ..', `python3 -c "open('../../../../elsewhere/secret.txt','w').write('x')"`);
  refuses('starting a process', `python3 -c "import subprocess; subprocess.run(['rm','-rf','/'])"`);
  refuses('reaching the network', `node -e "fetch('https://example.com').then(r=>r.text())"`);
  refuses('opening a socket', `python3 -c "import socket; socket.socket().connect(('10.0.0.1',22))"`);
  refuses('building code at runtime', `node -e "eval(process.argv[1])"`);
  refuses('a path it never names, so nothing can be checked', `python3 -c "import sys; open(sys.argv[1],'w').write('x')"`);
});

describe('the lead', () => {
  it('never gets the grant: it works in the user own checkout, not a worktree', () => {
    const v = verdict(heredoc, lead);
    expect(keys(v)).not.toContain(KEY);
    expect(classifyToolUse('Bash', { command: heredoc }, { ...lead, alwaysAllow: [KEY] }).action).not.toBe('allow');
  });
});
