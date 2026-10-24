# Private AgentCraft server release

AgentCraft 0.2.0 adds a dedicated Fabric server bridge and a separate Mac launcher profile. Foreman owns Codex jobs independently of Minecraft and of every player connection. This first release allows one authenticated owner. Installation and account validation are required before live use.

## Build and verify

Use Java 25 and Node 22 or later. Build from a clean working checkout; private machine configuration belongs outside Git.

```sh
npm ci --prefix foreman
npm run check --prefix foreman
python3 tools/test/client-regressions.py
python3 deploy/test-install-client.py
node deploy/build-release.mjs
```

The release builder also runs the Java build and shared bridge checks. It writes a checksummed release directory and archive under `dist/`. The archive contains the mod, Fabric API, launcher/installer JARs, compiled Foreman, deployment helpers, and dependency manifests. Minecraft itself is downloaded by the official server launcher at first start. Runtime downloads use the exact URLs and checksums captured in `release.json`.

## Linux guest installation

First verify hardware virtualization, storage health, free capacity, private networking, and an independent backup destination. Preserve existing NAS workloads. Install in a Linux x86_64 guest with systemd, Python 3, Git, curl, tar, xz, coreutils, and util-linux. The installer supplies private pinned Java/Node runtimes; it does not change Unraid's host runtimes.

After unpacking and verifying the release, run `deploy/install-linux.sh` as root with `--release`, `--owner-uuid`, `--owner-name`, `--server-address`, and `--accept-eula`. The address must be the guest's Tailscale IPv4 address. Supply EULA acceptance only after the user accepts [Minecraft's EULA](https://www.minecraft.net/eula). The installer creates users and directories, downloads verified runtimes, installs locked npm dependencies, generates a private bridge token, and enables two units. It does not start or restart services.

Configure the approved repository under `/var/lib/agentcraft/workspaces` and register it in `/var/lib/agentcraft/foreman/config.json`. Configure Git identity and a working signing method for the service account: real-backend merges are signed by default. Do not grant agents administrator access or mount unrelated NAS shares.

Authenticate Codex under the `agentcraft` account with `CODEX_HOME=/var/lib/agentcraft/codex`, using `/opt/agentcraft/current/codex/node_modules/.bin/codex`. Add `/opt/agentcraft/current/runtime/node/bin` to that command's PATH. Use supported device-code login and complete the human sign-in in the Orca browser. Keep credentials out of terminal arguments, chat, and Git. The service uses both lead and worker engine `codex`, a one-worker roster, and maximum concurrency one. Validate the Linux sandbox and a real Codex turn before accepting useful jobs.

Then start the new services:

```sh
sudo systemctl start agentcraft-foreman.service
sudo systemctl start agentcraft-minecraft.service
sudo /opt/agentcraft/current/deploy/health.sh
```

Allow only the owner's authorized tailnet access to guest TCP 25565 and SSH TCP 22. Foreman TCP 7878 remains on loopback and requires the bridge credential. Development port 7879 stays disabled. Retain `online-mode=true`, the owner allowlist, and the configured UUID. The two units are independent: stopping Minecraft must never stop Foreman or Codex. Neither unit depends on an SSH session. Confirm empty-server behavior and boot recovery on the chosen guest.

## Unraid Docker installation

When hardware virtualization is unavailable, `deploy/install-docker.sh` supplies an application-only Docker deployment. It creates three containers: a stable network namespace, Foreman, and Minecraft. The two application lifetimes remain independent. A separate Docker bridge, non-root users, dropped capabilities, read-only container roots, private persistent directories, and memory/CPU/PID limits constrain the deployment. No Docker socket or unrelated share is mounted. Only host loopback TCP 25565 is published; persistent Tailscale Serve forwards private TCP 25565 to it, including on userspace-networking Unraid installations. Foreman is reachable only on the shared container loopback and requires its credential.

Run the verified archive's installer as root on Unraid with `--release DIR`, `--owner-uuid UUID`, `--owner-name USERNAME`, and `--server-address TAILSCALE_IP`. It builds the pinned image and prepares the containers under `/mnt/user/appdata/agentcraft`, without starting either application. `AGENTCRAFT_DEPLOY_ROOT` can override that private state location. Existing unrelated containers and Serve ports are preserved. A different existing AgentCraft release is refused and requires idle maintenance.

The Docker installer can stage everything without EULA acceptance. In that case Minecraft remains stopped with its restart policy disabled. Once the owner has explicitly accepted the EULA, rerun the same installer with `--accept-eula`, then start the new application containers. Authenticate Codex into the Foreman container's persistent `/var/lib/agentcraft/codex`; configure only approved local repositories in `/var/lib/agentcraft/foreman/config.json`. The official headless authentication-cache transfer method is supported; transfer credentials privately over SSH and preserve owner-only access. Never put authentication data in an image or command argument.

```sh
docker start agentcraft-foreman
docker start agentcraft-minecraft
bash deploy/docker-control.sh health
bash deploy/test-container-sandbox.sh IMAGE
```

Run the sandbox check against the actual image before real work. `seccomp-codex.json` retains Moby's default deny policy and adds only the namespace/mount operations needed by nested unprivileged bubblewrap. Foreman remains non-root with all outer capabilities dropped and no new privileges. Minecraft retains Docker's default seccomp policy. Profile provenance is recorded in the added rule; its Apache license is supplied in `LICENSE.moby-profiles`. The standard Codex per-thread sandbox and AgentCraft approval policy remain enabled.

For logs and an idle-only consistent backup, use `docker-control.sh logs foreman` or `docker-control.sh backup`. The latter temporarily stops only these application containers after confirming no active work, archives private state without the auth cache or bridge token, and restores their previous running state. Copy backups off-host. Restore into a separate empty directory/container deployment, use the recorded image, regenerate the token, restore ownership, authenticate Codex, and verify health, worktree state, and job recovery before reconnecting a client. Never mount live and restored state into the same instance. Before an update, wait for idle, take a verified backup, stop and remove only the three AgentCraft containers, then run the new release installer against the preserved private state. Do not remove the state directory or unrelated Docker images/containers.

## MacBook Pro installation

Install Minecraft Launcher and sign in with a Microsoft account that has Minecraft Java access. Install or reuse Tailscale on the same tailnet as the guest. The helper reuses Java 25 when installed; otherwise it downloads a verified private Java runtime for the Mac's architecture. Python 3 is required for setup only.

Close Minecraft Launcher, unpack the release, and run:

```sh
python3 deploy/install-client.py --server agentcraft-hq:25565
```

Replace `agentcraft-hq` with the guest's actual private MagicDNS name. The helper uses the official Fabric installer, creates an AgentCraft launcher profile and separate game directory, installs verified mod files, and saves a server entry. Existing profiles and worlds are preserved; previous mod files are backed up inside the AgentCraft profile. Microsoft sign-in stays in Minecraft Launcher. Normal play needs neither Node, Codex, Foreman, nor a development checkout on the Mac.

Open Minecraft Launcher, select AgentCraft, and join the saved server. Client and server must use the same release version. Test joining from an external network, such as a phone hotspot, and reconnecting after laptop sleep or a network change.

## Operations and unattended work

```sh
sudo systemctl status agentcraft-foreman.service agentcraft-minecraft.service
sudo journalctl -u agentcraft-foreman.service -f
sudo journalctl -u agentcraft-minecraft.service -f
sudo /opt/agentcraft/current/deploy/health.sh
```

The authenticated health check reports backend authentication and active work without printing the bridge credential or full private state. Jobs continue with zero connected clients and with Minecraft stopped. Approvals remain pending until the owner answers; disconnection never grants approval. Authentication expiry, account limits, power/internet loss, or job errors remain real blockers.

The deployment journal records mutation IDs before execution. Known results can be replayed after reconnect; requests with an uncertain outcome are refused instead of automatically repeated. Refresh the visible state before submitting another action after a timeout. A corrupt request journal fails closed and requires recovery from the verified backup.

## Backup and recovery

Run `deploy/backup.sh` as root from the installed release. It refuses while work is active, closes Minecraft submissions, checks for work again, and gracefully stops only the two application services before archiving consistent state. It restarts those services afterward. It never interrupts an active job merely because no player is connected. Keep private backups outside the VM and retain another copy on a separate device or off-host destination.

For a recovery test, use a separate empty Linux instance. Verify the archive checksum and inventory with `tar -tzf`. Stop only the target instance's AgentCraft services and restore the archive into that isolated instance. Install the matching recorded application version, regenerate the shared bridge token, authenticate Codex, verify file ownership, and run the health and job-resume checks before reconnecting the Mac. Archives omit the bridge token and file-based Codex auth cache; these must be reprovisioned. Protect the remaining private configuration and session history.

Before updates, take a verified backup and record application/data versions. Install into a new immutable release directory. During maintenance, stop the new deployment's services, switch the release link, apply only compatible migrations, update the matching Mac mod, and validate before resuming work. Restore compatible state along with application files when migrations prevent a simple version rollback. VM snapshots do not replace an independent backup.
