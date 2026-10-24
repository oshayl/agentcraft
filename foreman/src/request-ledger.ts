import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import type { ClientMessage, Outbound } from './protocol.js';

type Ack = Extract<Outbound, { type: 'ack' }>;
interface Entry { id: string; fingerprint: string; ack?: Ack }

/** Write ahead of side effects: after a crash an uncertain request must never run twice. */
export class RequestLedger {
  private entries = new Map<string, Entry>();
  constructor(private file: string) {
    if (fs.existsSync(file)) {
      for (const line of fs.readFileSync(file, 'utf8').split('\n').filter(Boolean)) {
        const e = JSON.parse(line) as Entry;
        if (typeof e.id !== 'string' || typeof e.fingerprint !== 'string') throw new Error('invalid request ledger');
        this.entries.set(e.id, e);
      }
    }
  }

  begin(msg: ClientMessage): Ack | undefined {
    if (!msg.id) throw new Error('deployment actions require a request id');
    const fingerprint = createHash('sha256').update(JSON.stringify(msg)).digest('hex');
    const existing = this.entries.get(msg.id);
    if (existing) {
      if (existing.fingerprint !== fingerprint) return { type: 'ack', re: msg.id, ok: false, error: 'request id already used for a different action' };
      return existing.ack ?? { type: 'ack', re: msg.id, ok: false, error: 'request outcome pending or unknown; refresh state before submitting another action' };
    }
    // Do not evict ids silently: eviction would allow an old action to execute again.
    if (this.entries.size >= 100_000) throw new Error('request ledger full; maintenance required');
    this.append({ id: msg.id, fingerprint });
    return undefined;
  }

  complete(ack: Ack): void {
    if (!ack.re) return;
    const entry = this.entries.get(ack.re);
    if (entry) this.append({ ...entry, ack });
  }

  private append(entry: Entry): void {
    fs.mkdirSync(path.dirname(this.file), { recursive: true, mode: 0o700 });
    const fd = fs.openSync(this.file, 'a', 0o600);
    try {
      fs.writeSync(fd, JSON.stringify(entry) + '\n');
      fs.fsyncSync(fd);
    } finally { fs.closeSync(fd); }
    this.entries.set(entry.id, entry);
  }
}
