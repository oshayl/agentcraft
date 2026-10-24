#!/bin/bash
# Operate only this deployment. Backups wait for idle rather than killing useful work.
set -euo pipefail
base="${AGENTCRAFT_DEPLOY_ROOT:-/mnt/user/appdata/agentcraft}"
health() { docker exec agentcraft-foreman node /opt/agentcraft/release/deploy/foreman-health.mjs "$@"; }
case "${1:-health}" in
  health)
    docker inspect --format '{{.Name}} {{.State.Status}} restart={{.HostConfig.RestartPolicy.Name}}' agentcraft-net agentcraft-foreman agentcraft-minecraft
    health
    tailscale serve status
    ;;
  logs) docker logs --tail 100 "agentcraft-${2:-foreman}" ;;
  backup)
    [[ "$EUID" == 0 ]] || exit 2
    exec 9>"$base/backup.lock"
    flock -n 9 || exit 75
    health --require-idle
    minecraft_active="$(docker inspect --format '{{.State.Running}}' agentcraft-minecraft)"
    restart_services() {
      docker start agentcraft-foreman >/dev/null
      if [[ "$minecraft_active" == true ]]; then docker start agentcraft-minecraft >/dev/null; fi
    }
    trap restart_services EXIT
    if [[ "$minecraft_active" == true ]]; then docker stop --time 90 agentcraft-minecraft >/dev/null; fi
    health --require-idle
    docker stop --time 90 agentcraft-foreman >/dev/null
    install -d -m 700 "$base/state/backups"
    archive="$base/state/backups/agentcraft-$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
    umask 077
    docker inspect --format '{{.Config.Image}}' agentcraft-foreman > "$base/state/config/backup-image"
    tar --exclude=codex/auth.json --exclude=config/bridge-token -czf "$archive.tmp" -C "$base/state" config minecraft foreman codex workspaces
    mv "$archive.tmp" "$archive"
    sha256sum "$archive" > "$archive.sha256"
    printf 'Private consistent backup: %s\n' "$archive"
    ;;
  *) printf '%s\n' 'Usage: docker-control.sh health | logs [foreman|minecraft] | backup' >&2; exit 2 ;;
esac
