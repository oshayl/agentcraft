# Use an immutable official base; application runtimes are separately checksum pinned.
FROM node@sha256:83f487e0a63425e5b4d146fb5e5be574bcbe1b7b843d3ebafdd95eaf7767a7e5
USER root
RUN apt-get update && apt-get install -y --no-install-recommends python3 git openssh-client ca-certificates curl xz-utils util-linux tini iproute2 ripgrep && rm -rf /var/lib/apt/lists/*
COPY . /opt/agentcraft/release
WORKDIR /opt/agentcraft/release
RUN sha256sum --check --quiet SHA256SUMS && python3 deploy/container-runtime.py && \
    PATH=/opt/agentcraft/release/runtime/node/bin:$PATH npm ci --prefix foreman --omit=dev --no-audit --no-fund && \
    PATH=/opt/agentcraft/release/runtime/node/bin:$PATH npm ci --prefix codex --omit=dev --no-audit --no-fund && \
    groupadd --gid 10003 agentcraft-bridge && \
    useradd --uid 10001 --gid agentcraft-bridge --home-dir /var/lib/agentcraft/foreman --no-create-home agentcraft && \
    useradd --uid 10002 --gid agentcraft-bridge --home-dir /var/lib/agentcraft/minecraft --no-create-home agentcraft-minecraft
ENV PATH=/opt/agentcraft/release/runtime/node/bin:/opt/agentcraft/release/runtime/java/bin:/opt/agentcraft/release/codex/node_modules/.bin:/usr/local/bin:/usr/bin:/bin
ENV HOME=/var/lib/agentcraft/foreman AGENTCRAFT_HOME=/var/lib/agentcraft/foreman CODEX_HOME=/var/lib/agentcraft/codex
ENV AGENTCRAFT_BRIDGE_TOKEN_FILE=/etc/agentcraft/bridge-token AGENTCRAFT_REQUIRE_BRIDGE_AUTH=1
ENV AGENTCRAFT_BACKEND=codex AGENTCRAFT_CODEX_PATH=/opt/agentcraft/release/codex/node_modules/.bin/codex
WORKDIR /var/lib/agentcraft/foreman
USER 10001:10003
ENTRYPOINT ["/usr/bin/tini", "-g", "--", "/opt/agentcraft/release/deploy/container-entrypoint.sh"]
CMD ["foreman"]
