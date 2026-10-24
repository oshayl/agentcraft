#!/bin/bash
# Run once at boot in the background. Restore only this deployment, in network-first order.
set -euo pipefail
base="${AGENTCRAFT_DEPLOY_ROOT:-/mnt/user/appdata/agentcraft}"
for attempt in {1..180}; do
  if docker info >/dev/null 2>&1 && docker container inspect agentcraft-net >/dev/null 2>&1; then break; fi
  sleep 2
done
docker start agentcraft-net >/dev/null
docker start agentcraft-foreman >/dev/null
if grep -qx 'eula=true' "$base/state/minecraft/eula.txt" 2>/dev/null; then
  docker start agentcraft-minecraft >/dev/null
fi
printf '%s\n' 'AgentCraft boot startup completed.'
