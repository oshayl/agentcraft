#!/bin/bash
# Application setup only. Does not stop unrelated workloads or accept the Minecraft EULA implicitly.
set -euo pipefail
release=''
owner=''
owner_name=''
address=''
accept_eula=0
while (($#)); do
  case "$1" in
    --release) release="$2"; shift 2 ;;
    --owner-uuid) owner="$2"; shift 2 ;;
    --owner-name) owner_name="$2"; shift 2 ;;
    --server-address) address="$2"; shift 2 ;;
    --accept-eula) accept_eula=1; shift ;;
    *) printf 'Unknown argument: %s\n' "$1" >&2; exit 2 ;;
  esac
done
[[ "$EUID" == 0 ]] || { printf '%s\n' 'Run the installer as root inside the Linux guest.' >&2; exit 2; }
[[ "$(uname -m)" == x86_64 ]] || { printf '%s\n' 'This release targets Linux x86_64.' >&2; exit 2; }
[[ -n "$release" && -n "$owner" && -n "$owner_name" && -n "$address" && "$accept_eula" == 1 ]] || { printf '%s\n' 'Required: --release DIR --owner-uuid UUID --owner-name USERNAME --server-address TAILSCALE_IP --accept-eula' >&2; exit 2; }
for command_name in python3 curl tar xz git systemctl sha256sum; do command -v "$command_name" >/dev/null; done
python3 - "$owner" "$address" "$owner_name" <<'PY'
import ipaddress,re,sys,uuid
uuid.UUID(sys.argv[1])
if not re.fullmatch(r"[A-Za-z0-9_]{3,16}",sys.argv[3]): raise SystemExit("Supply the authenticated Minecraft Java username")
ip=ipaddress.ip_address(sys.argv[2])
if ip not in ipaddress.ip_network('100.64.0.0/10'):
    raise SystemExit('Bind the game to this guest\'s Tailscale IPv4 address')
PY
release="$(realpath "$release")"
(cd "$release" && sha256sum --check --quiet SHA256SUMS)
version="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$release/release.json")"
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || exit 2
destination="/opt/agentcraft/releases/$version"
if [[ -e /opt/agentcraft/current && "$(readlink -f /opt/agentcraft/current)" != "$destination" ]]; then
  printf '%s\n' 'Another release is active; use the maintenance update procedure.' >&2
  exit 2
fi
for unit in agentcraft-foreman.service agentcraft-minecraft.service; do
  if systemctl is-active --quiet "$unit"; then
    printf '%s\n' 'An AgentCraft service is active; wait for idle maintenance before reinstalling.' >&2
    exit 75
  fi
done
python3 - "$owner" "$address" <<'CHECK_CONFIG'
from pathlib import Path
import sys
world=Path('/var/lib/agentcraft/minecraft')
def properties(path):
    if not path.exists(): return {}
    return dict(line.split('=',1) for line in path.read_text().splitlines() if '=' in line and not line.startswith('#'))
config=properties(world/'config/agentcraft-server.properties')
server=properties(world/'server.properties')
if config.get('ownerUuid',sys.argv[1])!=sys.argv[1] or server.get('server-ip',sys.argv[2])!=sys.argv[2]:
    raise SystemExit('Existing owner or bind address differs; review configuration before changing it')
CHECK_CONFIG
if [[ -e "$destination" && "$release" != "$destination" ]]; then
  printf '%s\n' 'That release already exists; preserve it and use the documented update procedure.' >&2
  exit 2
fi
if [[ "$release" != "$destination" ]]; then
  install -d -m 755 /opt/agentcraft/releases
  cp -a "$release" "$destination"
fi
install -d -m 755 "$destination/runtime/node" "$destination/runtime/java"
fetch_runtime() {
  local kind="$1" archive
  archive="$(mktemp)"
  python3 - "$destination/release.json" "$kind" "$archive" <<'PY'
import hashlib,json,sys,urllib.request
item=json.load(open(sys.argv[1]))['runtimes'][sys.argv[2]]
with urllib.request.urlopen(item['url'],timeout=120) as response, open(sys.argv[3],'wb') as output:
    while True:
        data=response.read(1024*1024)
        if not data: break
        output.write(data)
h=hashlib.sha256()
with open(sys.argv[3],'rb') as stream:
    for data in iter(lambda:stream.read(1024*1024),b''): h.update(data)
if h.hexdigest()!=item['sha256']: raise SystemExit('Runtime checksum mismatch')
PY
  tar -xf "$archive" --strip-components=1 -C "$destination/runtime/$kind"
  rm -f "$archive"
}
if [[ ! -x "$destination/runtime/node/bin/node" ]]; then fetch_runtime node; fi
if [[ ! -x "$destination/runtime/java/bin/java" ]]; then fetch_runtime java; fi
export PATH="$destination/runtime/node/bin:$PATH"
(cd "$destination/foreman" && npm ci --omit=dev --no-audit --no-fund)
(cd "$destination/codex" && npm ci --omit=dev --no-audit --no-fund)
getent group agentcraft-bridge >/dev/null || groupadd --system agentcraft-bridge
id agentcraft >/dev/null 2>&1 || useradd --system --user-group --home-dir /var/lib/agentcraft/foreman --shell /usr/sbin/nologin agentcraft
id agentcraft-minecraft >/dev/null 2>&1 || useradd --system --gid agentcraft-bridge --home-dir /var/lib/agentcraft/minecraft --shell /usr/sbin/nologin agentcraft-minecraft
usermod -a -G agentcraft-bridge agentcraft
install -d -m 755 /var/lib/agentcraft
install -d -m 700 -o agentcraft -g agentcraft /var/lib/agentcraft/foreman /var/lib/agentcraft/codex /var/lib/agentcraft/workspaces /var/lib/agentcraft/backups
install -d -m 700 -o agentcraft-minecraft -g agentcraft-bridge /var/lib/agentcraft/minecraft
install -d -m 750 -o root -g agentcraft-bridge /etc/agentcraft
if [[ ! -f /etc/agentcraft/bridge-token ]]; then
  python3 - <<'PY'
from pathlib import Path
import secrets
p=Path('/etc/agentcraft/bridge-token')
with p.open('x') as f: f.write(secrets.token_urlsafe(48)+'\n')
PY
  chown root:agentcraft-bridge /etc/agentcraft/bridge-token
  chmod 640 /etc/agentcraft/bridge-token
fi
python3 - "$destination" "$owner" "$address" "$owner_name" <<'PY'
from pathlib import Path
import json,shutil,sys
release=Path(sys.argv[1]); world=Path('/var/lib/agentcraft/minecraft')
(world/'mods').mkdir(exist_ok=True); (world/'config').mkdir(exist_ok=True)
manifest=json.loads((release/'release.json').read_text())
for item in manifest['clientMods']:
    for old in (world/'mods').glob('agentcraft-*.jar' if item['file'].startswith('agentcraft-') else 'fabric-api-*.jar'):
        if old.name!=item['file']: raise SystemExit('Remove old mod versions through the update procedure before continuing')
    shutil.copyfile(release/'mods'/item['file'],world/'mods'/item['file'])
props=world/'server.properties'
if not props.exists():
    flat=json.dumps({'biome':'minecraft:plains','layers':[{'height':1,'block':'minecraft:bedrock'},{'height':124,'block':'minecraft:stone'},{'height':3,'block':'minecraft:dirt'},{'height':1,'block':'minecraft:grass_block'}],'structure_overrides':[]},separators=(',',':'))
    props.write_text('online-mode=true\nwhite-list=true\nenforce-whitelist=true\nmax-players=1\nserver-port=25565\nserver-ip='+sys.argv[3]+'\nlevel-name=AgentCraft HQ\nlevel-type=minecraft:flat\ngenerator-settings='+flat+'\ngamemode=creative\ndifficulty=peaceful\nview-distance=6\nsimulation-distance=4\npause-when-empty-seconds=-1\nenable-rcon=false\nenable-query=false\n')
(world/'eula.txt').write_text('eula=true\n')
if not (world/'whitelist.json').exists(): (world/'whitelist.json').write_text(json.dumps([{'uuid':sys.argv[2],'name':sys.argv[4]}])+'\n')
(world/'config'/'agentcraft-server.properties').write_text('ownerUuid='+sys.argv[2]+'\ntokenFile=/etc/agentcraft/bridge-token\n')
foreman=Path('/var/lib/agentcraft/foreman/config.json')
if not foreman.exists(): foreman.write_text(json.dumps({'backend':'codex','userName':sys.argv[4],'engines':{'lead':'codex','worker':'codex'},'claude':{'workers':['juniper'],'maxConcurrent':1,'resumeOnStart':True},'repos':[]},indent=2)+'\n')
Path('/etc/agentcraft/minecraft.env').touch(exist_ok=True)
PY
chown -R agentcraft-minecraft:agentcraft-bridge /var/lib/agentcraft/minecraft
chown agentcraft:agentcraft /var/lib/agentcraft/foreman/config.json
chown root:agentcraft-bridge /etc/agentcraft/minecraft.env
chmod 640 /etc/agentcraft/minecraft.env
install -m 644 "$destination/deploy/foreman.service" /etc/systemd/system/agentcraft-foreman.service
install -m 644 "$destination/deploy/minecraft.service" /etc/systemd/system/agentcraft-minecraft.service
if [[ -e /opt/agentcraft/current && "$(readlink -f /opt/agentcraft/current)" != "$destination" ]]; then
  printf '%s\n' 'Another release is active; use the maintenance update procedure.' >&2
  exit 2
fi
ln -sfn "$destination" /opt/agentcraft/current
systemctl daemon-reload
systemctl enable agentcraft-foreman.service agentcraft-minecraft.service
printf '%s\n' 'Installed. Authenticate Codex as agentcraft with CODEX_HOME=/var/lib/agentcraft/codex, configure an approved repository and signing identity, then start the two services.'
printf '%s\n' 'The installer does not start or restart services.'
