#!/bin/bash
# Skip busy work. Stop only these new services and preserve a consistent complete application state.
set -euo pipefail
[[ "$EUID" == 0 ]] || { printf '%s\n' 'Run as root inside the AgentCraft guest.' >&2; exit 2; }
exec 9>/run/lock/agentcraft-backup.lock
flock -n 9 || { printf '%s\n' 'Another AgentCraft maintenance operation is running.' >&2; exit 75; }
base=/opt/agentcraft/current
"$base/runtime/node/bin/node" "$base/deploy/foreman-health.mjs" --require-idle
minecraft_was_active=0
if systemctl is-active --quiet agentcraft-minecraft.service; then minecraft_was_active=1; fi
restart_services() {
  systemctl start agentcraft-foreman.service
  if [[ "$minecraft_was_active" == 1 ]]; then systemctl start agentcraft-minecraft.service; fi
}
trap restart_services EXIT
systemctl stop agentcraft-minecraft.service
# Close submissions before checking again; jobs accepted just before disconnection must finish.
"$base/runtime/node/bin/node" "$base/deploy/foreman-health.mjs" --require-idle
systemctl stop agentcraft-foreman.service
install -d -m 700 /var/lib/agentcraft/backups
archive="/var/lib/agentcraft/backups/agentcraft-$(date -u +%Y%m%dT%H%M%SZ).tar.gz"
umask 077
tar --exclude=var/lib/agentcraft/codex/auth.json --exclude=etc/agentcraft/bridge-token \
  -czf "$archive.tmp" -C / etc/agentcraft var/lib/agentcraft/minecraft var/lib/agentcraft/foreman var/lib/agentcraft/codex var/lib/agentcraft/workspaces
mv "$archive.tmp" "$archive"
sha256sum "$archive" > "$archive.sha256"
printf 'Backup ready: %s\n' "$archive"
printf '%s\n' 'Copy this private archive and checksum to the verified off-host destination. Codex reauthentication and a new bridge credential are required after disaster recovery.'
