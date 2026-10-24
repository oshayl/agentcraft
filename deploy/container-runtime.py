#!/usr/bin/env python3
"""Download only the immutable, checksum-recorded Linux application runtimes."""
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile
import urllib.request

release = Path(__file__).resolve().parent.parent
manifest = json.loads((release / 'release.json').read_text())
for kind in ('node', 'java'):
    item = manifest['runtimes'][kind]
    destination = release / 'runtime' / kind
    destination.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as work:
        archive = Path(work) / 'runtime.tar'
        with urllib.request.urlopen(item['url'], timeout=120) as response, archive.open('wb') as output:
            digest = hashlib.sha256()
            while chunk := response.read(1024 * 1024):
                digest.update(chunk)
                output.write(chunk)
        if digest.hexdigest() != item['sha256']:
            raise SystemExit('Runtime checksum mismatch: ' + kind)
        subprocess.run(['tar', '-xf', str(archive), '--strip-components=1', '-C', str(destination)], check=True)
