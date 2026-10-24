#!/bin/sh
set -eu
umask 077
mode="${1:-foreman}"
shift || true
case "$mode" in
  foreman)
    cd /var/lib/agentcraft/foreman
    exec node /opt/agentcraft/release/foreman/dist/main.js --backend codex --lead-engine codex --worker-engine codex --workers 1 --max-concurrent 1 --no-notify "$@"
    ;;
  minecraft)
    cd /var/lib/agentcraft/minecraft
    if ! grep -qx 'eula=true' eula.txt 2>/dev/null; then
      printf '%s\n' 'Minecraft EULA acceptance is pending; server was not launched.' >&2
      exit 78
    fi
    exec java -Xms1G -Xmx3G -jar /opt/agentcraft/release/fabric-server.jar nogui "$@"
    ;;
  health) exec node /opt/agentcraft/release/deploy/foreman-health.mjs "$@" ;;
  *) exec "$mode" "$@" ;;
esac
