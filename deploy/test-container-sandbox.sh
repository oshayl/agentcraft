#!/bin/bash
# An actual Codex sandbox must permit worktree writes and refuse sibling-directory writes.
set -euo pipefail
image="${1:?Supply the built AgentCraft image}"
profile="$(realpath "${2:-$(dirname "$0")/seccomp-codex.json}")"
docker run --rm --read-only --cap-drop ALL --security-opt no-new-privileges --security-opt "seccomp=$profile" \
  --tmpfs /tmp:rw,nosuid,nodev,size=64m \
  --tmpfs /var/lib/agentcraft/codex:rw,nosuid,nodev,size=64m,uid=10001,gid=10003 \
  --tmpfs /workspace:rw,nosuid,nodev,size=32m,uid=10001,gid=10003 \
  --user 10001:10003 --workdir /workspace "$image" sh -c '
    set -eu
    mkdir -p project outside
    printf outer-writable > outside/probe
    codex sandbox -P :workspace --cd /workspace/project -- /bin/sh -c '\''
      set -eu
      printf allowed > allowed.txt
      if (printf forbidden > ../outside/denied.txt) 2>/dev/null; then
        printf "%s\n" "Sandbox isolation failed" >&2
        exit 1
      fi
      printf "%s\n" "SANDBOX_ISOLATION_OK"
    '\''
    test -f project/allowed.txt
    test ! -f outside/denied.txt
  '
