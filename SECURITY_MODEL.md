# node-monitor threat model

Status: drafted from the code during a Devin security scan; edit as the system changes. Facts marked *(inferred)* come from reading the code, not from the owner; they do not move findings. Owner-provided facts are listed under "Owner input".

## System overview and trust boundaries

A self-hosted monitoring tool: a read-only metrics **agent** runs on each monitored Linux/Windows host; native **clients** (macOS, Windows, Android) poll it directly. No central hub.

Trust boundaries *(inferred)*:
1. Network ↔ agent HTTP listener (`/health`, `/v1/snapshot`).
2. Tailnet peers ↔ agent's embedded Tailscale listener (optional, `--tailscale-listen`; optional tokenless same-owner access `--tailscale-owner-auth`).
3. Client ↔ agent over LAN HTTP, HTTPS, SSH tunnel or tailnet; the client sends a bearer token.
4. Client app ↔ its local storage (tokens, settings, history) and, on macOS/Windows, ↔ the `nodemon-tailscale-bridge` child process over stdin/stdout pipes.
5. Agent ↔ local OS: reads `/proc`, `/sys`, runs `nvidia-smi` and `systemctl show` with fixed args.
6. Build/supply chain: Go toolchain download (`scripts/bootstrap-go.py`), Go modules (tailscale.com v1.104.4), Android/Windows build scripts.

## Components

| Component | Code |
| --- | --- |
| Agent | `Agent/cmd/nodemon-agent/`, `Agent/internal/server/`, `Agent/internal/metrics/`, `Agent/internal/securestate/`, `deploy/` |
| Tailscale transport | `Agent/internal/tailnet/`, `Agent/cmd/nodemon-tailscale-bridge/`, `Agent/cmd/nodemon-android/`, `Agent/internal/androidnet/` |
| macOS client | `Sources/`, `packaging/`, `Tools/pair-server.py` |
| Windows client | `Windows/` |
| Android client | `Android/` |
| Build tooling | `scripts/`, `Tools/` (other than pair-server.py), `Package.swift`, `Agent/go.mod` |

## Entry points and who can reach them

- `GET /health` — unauthenticated, returns static `{"status":"ok","service":"nodemon-agent"}` (`Agent/internal/server/server.go`).
- `GET /v1/snapshot` — bearer token (SHA-256 + constant-time compare), or, on the tailnet listener with `--tailscale-owner-auth`, a request with no `Authorization` header from a same-user, untagged, unshared tailnet device (`tailnet.SameOwner`).
- Agent listener defaults to `127.0.0.1:9274`; non-loopback requires TLS (`--tls-cert/--tls-key`) or explicit `--allow-http-lan` (`Agent/cmd/nodemon-agent/main.go`).
- Tailscale login: `--tailscale-login` prints the auth URL only to `/dev/tty`.
- Android: exported launcher activity, `WidgetConfigureActivity` (APPWIDGET_CONFIGURE), six exported AppWidget receivers; JNI bridge `nmRequest` (`Agent/cmd/nodemon-android/main.go`).
- macOS/Windows: bridge child process reads JSON requests on stdin (actions status/login/fetch).
- Snapshot JSON parsed by every client (responses capped at 1 MiB, redirects disabled).

## Assets and sensitivity

- Agent bearer tokens: `data/agent.token` (0600, ≥32 chars) on hosts; client copies in `data/mac/credentials/*.token` (0600), Android Keystore-encrypted SharedPreferences, Windows DPAPI (`Secret.Protect`).
- Embedded Tailscale node identity/state dirs (`data/tailscale-agent`, client state dirs; 0700 + `securestate.Protect`) and temporary login URLs.
- The monitored hosts themselves: the agent runs on them (as a dedicated `nodemon` user in the systemd template, `NoNewPrivileges`).
- Metric data: hostnames, hardware, network interfaces, optional process names/PIDs (`--processes`) and systemd service states (`--services`).
- Client-side history (SQLite) and settings.

## Attacker profiles *(inferred, pending owner input)*

- Network attacker able to reach a non-loopback agent listener (LAN, or internet if exposed).
- Other devices/users on the same tailnet (shared or tagged devices, other users).
- Malicious app on the Android phone; other local user on a client machine.
- Unprivileged local user on a monitored host.
- Supply-chain attacker (Go modules, toolchain download, build scripts).

## Existing controls

- Read-only API, no remote commands; constant-time token check; tokens never logged.
- Loopback default; TLS or explicit opt-in for plaintext; HTTP server timeouts and 8 KiB header limit.
- Same-owner check rejects tagged/shared devices; tokenless path only when no `Authorization` header is sent and only on the tailnet listener.
- tsnet log upload disabled via `ts_omit_logtail` build tag; refuses to start with conflicting `TS_*` env vars; auth URL host pinned to `login.tailscale.com`.
- Clients: no redirects, no cookies/disk cache, 1 MiB response cap, tailnet address allowlist (100.64.0.0/10, fd7a:115c:a1e0::/48, `*.ts.net`); Android refuses plaintext to non-IP hostnames (DNS rebinding) and to non-private IPs unless loopback.
- Diagnostics: `/proc/<pid>/stat` only (no cmdlines), `systemctl show` with timeout and output cap, max 16 services.
- Android `allowBackup="false"`; `usesCleartextTraffic="true"` is app-wide but guarded in `MonitorClient.direct`.

## Owner input

(none yet)

## Out of scope / accepted risk

(none stated yet)

## Open questions

- How agents are exposed in practice (loopback+SSH, tailnet with owner-auth, LAN plaintext, public HTTPS).
- Which attackers matter; which asset matters most.
- Whether Windows/Android dev previews, cleartext LAN HTTP, unsigned macOS builds and build tooling are in scope.
- No infrastructure repository; deployment is described only by `deploy/*.service` and `docs/部署与使用.md`.
