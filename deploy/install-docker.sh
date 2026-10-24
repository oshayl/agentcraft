#!/bin/bash
# Unraid application-only installation. Existing containers and host runtimes are preserved.
set -euo pipefail
release=''
owner=''
owner_name=''
address=''
accept_eula=0
base="${AGENTCRAFT_DEPLOY_ROOT:-/mnt/user/appdata/agentcraft}"
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
[[ "$EUID" == 0 && -n "$release" && -n "$owner" && -n "$owner_name" && -n "$address" ]] || { printf '%s\n' 'Required as root: --release DIR --owner-uuid UUID --owner-name USERNAME --server-address TAILSCALE_IP [--accept-eula]'; exit 2; }
[[ "$(uname -m)" == x86_64 && "$base" == /* && "$base" != / ]] || exit 2
[[ "$owner" =~ ^[0-9a-fA-F-]{32,36}$ && "$owner_name" =~ ^[A-Za-z0-9_]{3,16}$ ]] || exit 2
[[ "$address" =~ ^100\.([6-9][0-9]|1[01][0-9]|12[0-7])\.[0-9]{1,3}\.[0-9]{1,3}$ ]] || exit 2
[[ "$(tailscale ip -4)" == "$address" ]] || { printf '%s\n' 'The server address must belong to this host Tailscale node.'; exit 2; }
release="$(realpath "$release")"
(cd "$release" && sha256sum --check --quiet SHA256SUMS)
fingerprint="$(sha256sum "$release/SHA256SUMS" | cut -d' ' -f1)"
image="agentcraft:release-${fingerprint:0:12}"
label='dev.agentcraft.deployment'
for name in agentcraft-net agentcraft-foreman agentcraft-minecraft; do
  if docker container inspect "$name" >/dev/null 2>&1; then
    installed="$(docker inspect --format '{{index .Config.Labels "dev.agentcraft.deployment"}}' "$name")"
    [[ "$installed" == "$fingerprint" ]] || { printf '%s\n' 'A different deployment already exists; use the idle maintenance procedure.'; exit 2; }
  fi
done
for name in agentcraft-foreman agentcraft-minecraft; do
  if [[ "$(docker inspect --format '{{.State.Running}}' "$name" 2>/dev/null || true)" == true ]]; then
    printf '%s\n' 'Application containers are active; wait for idle maintenance before reinstalling.' >&2
    exit 75
  fi
done
if ! docker image inspect "$image" >/dev/null 2>&1; then
  docker build --tag "$image" --file "$release/deploy/Dockerfile" "$release"
fi
install -d -m 700 "$base" "$base/state"
install -m 600 "$release/deploy/seccomp-codex.json" "$base/seccomp-codex.json"
config_args=(--owner-uuid "$owner" --owner-name "$owner_name")
if [[ "$accept_eula" == 1 ]]; then config_args+=(--accept-eula); fi
docker run --rm --network none --user 0:0 --cap-drop ALL --cap-add CHOWN --cap-add DAC_OVERRIDE --security-opt no-new-privileges \
  --read-only --tmpfs /tmp:rw,nosuid,nodev,size=32m --mount "type=bind,src=$base/state,dst=/state" \
  "$image" python3 /opt/agentcraft/release/deploy/container-config.py "${config_args[@]}"
if ! docker network inspect agentcraft >/dev/null 2>&1; then docker network create --label "$label=$fingerprint" agentcraft >/dev/null; fi
if ! docker container inspect agentcraft-net >/dev/null 2>&1; then
  docker create --name agentcraft-net --label "$label=$fingerprint" --network agentcraft --restart always \
    --read-only --cap-drop ALL --security-opt no-new-privileges --memory 16m --pids-limit 32 \
    --publish "127.0.0.1:25565:25565/tcp" --log-opt max-size=10m --log-opt max-file=3 \
    busybox@sha256:3c6ae8008e2c2eedd141725c30b20d9c36b026eb796688f88205845ef17aa213 sleep infinity >/dev/null
fi
docker start agentcraft-net >/dev/null
# Serve works with both kernel and userspace Tailscale (Unraid may have no tailscale0).
# Only this TCP port is configured; unrelated Serve listeners remain intact.
tailscale serve status --json | docker run --rm -i --network none --read-only --user 10001:10003 --cap-drop ALL --security-opt no-new-privileges "$image" python3 -c 'import json,sys; d=json.load(sys.stdin); p=d.get("TCP",{}).get("25565"); expected={"TCPForward":"127.0.0.1:25565"}; sys.exit("A different Serve listener already uses 25565" if p and p!=expected else 0)'
tailscale serve --bg --yes --tcp=25565 tcp://127.0.0.1:25565
common=(--label "$label=$fingerprint" --network container:agentcraft-net --read-only --cap-drop ALL --security-opt no-new-privileges --tmpfs /tmp:rw,nosuid,nodev,size=512m --tmpfs /run:rw,nosuid,nodev,size=16m --pids-limit 1024 --cpus 2 --memory 4g --memory-swap 4g --stop-timeout 90 --log-opt max-size=10m --log-opt max-file=3 --mount "type=bind,src=$base/state/config,dst=/etc/agentcraft,readonly")
if ! docker container inspect agentcraft-foreman >/dev/null 2>&1; then
  docker create --name agentcraft-foreman --restart unless-stopped "${common[@]}" --user 10001:10003 --security-opt "seccomp=$base/seccomp-codex.json" \
    --mount "type=bind,src=$base/state/foreman,dst=/var/lib/agentcraft/foreman" \
    --mount "type=bind,src=$base/state/codex,dst=/var/lib/agentcraft/codex" \
    --mount "type=bind,src=$base/state/workspaces,dst=/var/lib/agentcraft/workspaces" \
    "$image" foreman >/dev/null
fi
if ! docker container inspect agentcraft-minecraft >/dev/null 2>&1; then
  docker create --name agentcraft-minecraft --restart no "${common[@]}" --user 10002:10003 \
    --env HOME=/var/lib/agentcraft/minecraft --workdir /var/lib/agentcraft/minecraft \
    --mount "type=bind,src=$base/state/minecraft,dst=/var/lib/agentcraft/minecraft" \
    "$image" minecraft >/dev/null
fi
if [[ "$accept_eula" == 1 ]]; then docker update --restart unless-stopped agentcraft-minecraft >/dev/null; fi
printf '%s\n' 'Containers prepared. Authenticate Codex and configure an approved repository before starting agentcraft-foreman.'
if [[ "$accept_eula" == 0 ]]; then printf '%s\n' 'Minecraft is stopped with restart disabled until explicit EULA acceptance.'; fi
printf 'Image: %s\n' "$image"
