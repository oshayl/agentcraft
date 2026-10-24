#!/usr/bin/env python3
"""Initialize private container state. Minecraft EULA acceptance is always explicit."""
import argparse
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--owner-uuid', required=True)
parser.add_argument('--owner-name', required=True)
parser.add_argument('--accept-eula', action='store_true')
parser.add_argument('--state-dir', type=Path, default=Path('/state'))
parser.add_argument('--release', type=Path, default=Path('/opt/agentcraft/release'))
args = parser.parse_args()
owner = str(uuid.UUID(args.owner_uuid))
if not re.fullmatch(r'[A-Za-z0-9_]{3,16}', args.owner_name):
    parser.error('Supply the authenticated Minecraft Java username')
os.umask(0o077)
root = args.state_dir
if root.is_symlink() or any((root / name).is_symlink() for name in ('foreman', 'codex', 'workspaces', 'minecraft', 'config', 'backups')):
    raise SystemExit('Application state directories must not be symlinks')
world = root / 'minecraft'
bridge = world / 'config' / 'agentcraft-server.properties'
if bridge.exists() and f'ownerUuid={owner}' not in bridge.read_text().splitlines():
    raise SystemExit('Existing owner differs; preserve the installed configuration')
release = args.release
manifest = json.loads((release / 'release.json').read_text())
expected = {item['file'] for item in manifest['clientMods']}
for old in (world / 'mods').glob('*.jar'):
    if old.name.startswith(('agentcraft-', 'fabric-api-')) and old.name not in expected:
        raise SystemExit('An older mod is installed; use the update procedure')
for item in manifest['clientMods']:
    target = world / 'mods' / item['file']
    if target.is_symlink() or (target.exists() and target.read_bytes() != (release / 'mods' / item['file']).read_bytes()):
        raise SystemExit('Installed mod content differs; use the update procedure')
for name, uid in [('foreman', 10001), ('codex', 10001), ('workspaces', 10001), ('minecraft', 10002), ('config', 0), ('backups', 0)]:
    directory = root / name
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chown(directory, uid, 10003)
config = root / 'config'
os.chmod(config, 0o750)
token = config / 'bridge-token'
if not token.exists():
    with token.open('x') as output:
        output.write(secrets.token_urlsafe(48) + '\n')
os.chown(token, 0, 10003)
os.chmod(token, 0o640)
bridge.parent.mkdir(exist_ok=True)
bridge.write_text(f'ownerUuid={owner}\ntokenFile=/etc/agentcraft/bridge-token\n')
props = world / 'server.properties'
if not props.exists():
    flat = json.dumps({'biome': 'minecraft:plains', 'layers': [{'height': 1, 'block': 'minecraft:bedrock'}, {'height': 124, 'block': 'minecraft:stone'}, {'height': 3, 'block': 'minecraft:dirt'}, {'height': 1, 'block': 'minecraft:grass_block'}], 'structure_overrides': []}, separators=(',', ':'))
    props.write_text('online-mode=true\nwhite-list=true\nenforce-whitelist=true\nmax-players=1\nserver-port=25565\nserver-ip=0.0.0.0\nlevel-name=AgentCraft HQ\nlevel-type=minecraft:flat\ngenerator-settings=' + flat + '\ngamemode=creative\ndifficulty=peaceful\nview-distance=6\nsimulation-distance=4\npause-when-empty-seconds=-1\nenable-rcon=false\nenable-query=false\n')
if args.accept_eula:
    (world / 'eula.txt').write_text('eula=true\n')
if not (world / 'whitelist.json').exists():
    (world / 'whitelist.json').write_text(json.dumps([{'uuid': owner, 'name': args.owner_name}]) + '\n')
(world / 'mods').mkdir(exist_ok=True)
for item in manifest['clientMods']:
    target = world / 'mods' / item['file']
    if target.exists() and target.read_bytes() != (release / 'mods' / item['file']).read_bytes():
        raise SystemExit('Installed mod content differs; use the update procedure')
    shutil.copyfile(release / 'mods' / item['file'], target)
foreman = root / 'foreman' / 'config.json'
if not foreman.exists():
    foreman.write_text(json.dumps({'backend': 'codex', 'userName': args.owner_name, 'engines': {'lead': 'codex', 'worker': 'codex'}, 'claude': {'workers': ['juniper'], 'maxConcurrent': 1, 'resumeOnStart': True}, 'repos': []}, indent=2) + '\n')
for name, uid in [('minecraft', 10002), ('foreman', 10001)]:
    for path in (root / name).rglob('*'):
        if path.is_symlink():
            raise SystemExit('Unexpected symlink in application state')
        os.chown(path, uid, 10003)
print('Private state initialized. EULA accepted:', (world / 'eula.txt').exists())
