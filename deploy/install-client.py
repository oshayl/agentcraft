#!/usr/bin/env python3
"""Install a verified release into a separate Minecraft profile. Never handles account tokens."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import tempfile
import tarfile
import urllib.request


def sha256(p):
    with p.open('rb') as stream:
        digest = hashlib.sha256()
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
        return digest.hexdigest()


def nbt_servers(address):
    def string(value):
        raw = value.encode('utf-8')
        return struct.pack('>H', len(raw)) + raw
    def field(name, value):
        return b'\x08' + string(name) + string(value)
    return b'\x0a\x00\x00\x09' + string('servers') + b'\x0a\x00\x00\x00\x01' + field('name', 'AgentCraft') + field('ip', address) + b'\x00\x00'


def install(release, game, launcher, address, java, configure=True):
    manifest = json.loads((release / 'release.json').read_text())
    if not re.fullmatch(r'[a-zA-Z0-9.-]+(?::[0-9]{1,5})?', address):
        raise ValueError('use the private Tailscale hostname or IPv4 address, optionally with a port')
    marker = game / '.agentcraft-profile.json'
    if game.exists() and any(game.iterdir()) and not marker.exists():
        raise ValueError('selected game directory is not an AgentCraft profile; choose a new separate directory')
    if game.is_symlink() or (game / 'mods').is_symlink():
        raise ValueError('game and mods directories must not be symlinks')
    files = []
    for item in manifest['clientMods']:
        if Path(item['file']).name != item['file']:
            raise ValueError('invalid release filename')
        src = release / 'mods' / item['file']
        if sha256(src) != item['sha256']:
            raise ValueError('release checksum mismatch: ' + item['file'])
        files.append(src)
    profile_file = launcher / 'launcher_profiles.json'
    data = None
    if configure:
        data = json.loads(profile_file.read_text()) if profile_file.exists() else {'profiles': {}}
        profile = data.setdefault('profiles', {}).get('AgentCraft')
        if profile and profile.get('gameDir') != str(game):
            raise ValueError('an AgentCraft launcher entry already targets another directory; preserve it and rename it before setup')
        installer = release / 'fabric-installer.jar'
        if sha256(installer) != manifest['fabricInstaller']['sha256']:
            raise ValueError('Fabric installer checksum mismatch')
        subprocess.run([java, '-jar', str(installer), 'client', '-dir', str(launcher), '-mcversion', manifest['minecraft'], '-loader', manifest['fabricLoader'], '-noprofile'], check=True)
    game.mkdir(parents=True, exist_ok=True, mode=0o700)
    mods = game / 'mods'
    mods.mkdir(exist_ok=True)
    current = {p.name for p in files}
    backup = game / 'mod-backups' / datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    for old in mods.glob('*.jar'):
        if old.is_symlink():
            raise ValueError('refusing a symlink in the mods directory')
        if old.name.startswith(('agentcraft-', 'fabric-api-')):
            backup.mkdir(parents=True, exist_ok=True)
            shutil.move(str(old), backup / old.name)
    for src in files:
        temp = mods / (src.name + '.tmp')
        shutil.copyfile(src, temp)
        os.replace(temp, mods / src.name)
    marker.write_text(json.dumps({'version': manifest['version'], 'mods': sorted(current)}, indent=2) + '\n')
    server_file = game / 'servers.dat'
    if not server_file.exists():
        server_file.write_bytes(nbt_servers(address))
    if configure:
        profiles = data['profiles']
        profiles['AgentCraft'] = {'name': 'AgentCraft', 'type': 'custom', 'lastVersionId': f"fabric-loader-{manifest['fabricLoader']}-{manifest['minecraft']}", 'gameDir': str(game), 'javaDir': java, 'javaArgs': '-Xmx4G -Dagentcraft.dev=0 -Dagentcraft.autoworld=0'}
        if profile_file.exists():
            shutil.copy2(profile_file, profile_file.with_name('launcher_profiles.agentcraft-backup-' + datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ') + '.json'))
        temp = profile_file.with_suffix('.agentcraft.tmp')
        temp.write_text(json.dumps(data, indent=2) + '\n')
        os.chmod(temp, 0o600)
        os.replace(temp, profile_file)
    return game



def resolve_java(release):
    probe = subprocess.run(['/usr/libexec/java_home', '-v', '25'], capture_output=True, text=True)
    if probe.returncode == 0:
        return probe.stdout.strip() + '/bin/java'
    arm = subprocess.run(['sysctl', '-n', 'hw.optional.arm64'], capture_output=True, text=True)
    architecture = 'aarch64' if arm.stdout.strip() == '1' else 'x64'
    manifest = json.loads((release / 'release.json').read_text())
    item = manifest['macJava'][architecture]
    cache = Path.home() / 'Library/Application Support/AgentCraft/runtime' / (item['version'] + '-' + architecture)
    if (cache / 'bin/java').exists():
        return str(cache / 'bin/java')
    cache.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='java-setup-', dir=cache.parent) as work:
        work = Path(work)
        archive = work / 'java.tar.gz'
        with urllib.request.urlopen(item['url'], timeout=120) as response, archive.open('wb') as output:
            shutil.copyfileobj(response, output)
        if sha256(archive) != item['sha256']:
            raise ValueError('Java runtime checksum mismatch')
        extracted = (work / 'extracted').resolve()
        extracted.mkdir()
        with tarfile.open(archive, 'r:gz') as tar:
            for member in tar.getmembers():
                target = (extracted / member.name).resolve()
                try:
                    target.relative_to(extracted)
                    if member.issym():
                        (target.parent / member.linkname).resolve().relative_to(extracted)
                    if member.islnk():
                        (extracted / member.linkname).resolve().relative_to(extracted)
                except ValueError:
                    raise ValueError('unsafe Java runtime archive path')
                if not (member.isfile() or member.isdir() or member.issym() or member.islnk()):
                    raise ValueError('unsupported Java runtime archive entry')
            tar.extractall(extracted)
        candidates = list(extracted.glob('*/Contents/Home/bin/java')) + list(extracted.glob('*/bin/java'))
        if len(candidates) != 1:
            raise ValueError('Java runtime layout is not recognized')
        shutil.move(str(candidates[0].parent.parent), cache)
    return str(cache / 'bin/java')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--release', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--server', required=True, help='private Tailscale hostname or IP')
    parser.add_argument('--game-dir', type=Path, default=Path.home() / 'Library/Application Support/AgentCraft/Minecraft')
    parser.add_argument('--launcher-dir', type=Path, default=Path.home() / 'Library/Application Support/minecraft')
    parser.add_argument('--java', type=Path)
    args = parser.parse_args()
    if sys.platform != 'darwin':
        parser.error('this installer targets macOS')
    java = str(args.java) if args.java else resolve_java(args.release.resolve())
    version = subprocess.run([java, '-version'], capture_output=True, text=True, check=True)
    if not re.search(r'version "25(?:[.\"]|$)', version.stderr + version.stdout):
        parser.error('Java 25 is required for the pinned Minecraft release')
    subprocess.run(['osascript', '-e', 'if application "Minecraft" is running then error "Close Minecraft Launcher before updating its profile"'], check=True)
    result = install(args.release.resolve(), args.game_dir.expanduser().absolute(), args.launcher_dir.expanduser().absolute(), args.server, java)
    print(f'AgentCraft installed in {result}. Open Minecraft Launcher, sign in, select AgentCraft, and join the saved server.')


if __name__ == '__main__':
    main()
