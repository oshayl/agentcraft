#!/bin/sh
set -eu
systemctl is-active agentcraft-foreman.service
systemctl is-active agentcraft-minecraft.service
ss -lnt '( sport = :25565 or sport = :7878 )'
printf '%s\n' 'Foreman must listen on loopback only; Minecraft must listen on the configured Tailscale interface.'
/opt/agentcraft/current/runtime/node/bin/node /opt/agentcraft/current/deploy/foreman-health.mjs
