# Connection recovery

## Behavior

Both SSH transports use the same recovery policy. While the app is visible, a
read-only `account/read` check runs every 20 seconds, with a 10-second response
budget. Returning to the app or changing the default network triggers an immediate
check. A JSON-RPC error response still proves the server is reachable. Checks do
not refresh account credentials, launch turns or modify permissions.

EOF, broken writes, diagnostic failures and request timeouts publish a persistent
failure state, independently of the notification stream. The workspace shows
Checking connection, Reconnecting or Disconnected instead of leaving a dead
connection marked as healthy. Pending RPC callers are released when the client
closes. Normal requests have a 60-second deadline; writes in both transports have
a 15-second I/O deadline that closes SSH to interrupt blocked writes.

Transient failures retry after 1, 2, 4, 8, 16 and then 30 seconds. Only one connection
attempt is current. A superseded attempt cannot install a stale connection. Retries
and health checks pause while backgrounded or offline; returning or regaining a
network triggers recovery immediately. The app does not create a foreground service
or promise an uninterrupted background socket.

Reconnection uses stored credentials and the pinned host key, initializes a fresh
RPC client, refreshes host state, and resumes the selected thread. Its history and
reported active-turn ID are restored. Manual reconnect to the same host also keeps
the selected conversation. No failed message, command, turn or approval response is
resent. Old pending approvals are cleared; the server must send any new requests.
A daemon remains on the host when its SSH connection closes. Per-connection mode
starts a new app server and resumes persisted history; it cannot preserve the old
server process or guarantee that its interrupted work continues.

Host-key changes, unknown keys, authentication failures, invalid configuration,
protocol violations and input-limit failures stop automatic retries. Explicit
disconnect cancels pending recovery. Neither a network event nor returning to the
app overrides those decisions.

## Input bounds

Per-message, parser-depth, fragment and queue bounds remain in place. Cumulative
connection counters are replaced by token buckets, which replenish continuously:
RPC and WebSocket input have burst budgets of 64 Mi characters/64 MiB and 100,000
messages/frames, refilling at those amounts per minute. Diagnostic input has budgets
of 2 Mi characters and 10,000 lines, also refilling per minute. Quiet time cannot
accumulate more than one full burst. This permits long sessions while still rejecting
oversized messages and excessive sustained input.

## Validation

On 2026-09-16, 115 JVM tests and the separate real OpenSSH/Codex integration test
passed. Strict offline release/test-APK builds passed; lint reported zero errors
and 19 existing warnings. The release was signed with the same key as the preceding
build and its APK payload verified unchanged. See
[validation record](connection-recovery-validation.json) for the artifact digest.

JVM regressions cover silent connections, EOF, broken writes, request timeout,
late failure observers, pending-call cancellation, probe cancellation, retry
backoff, foreground/network changes, explicit disconnect, security retry exclusions,
active-turn restoration and long-running input budgets. The real OpenSSH/Codex
fixture exercises health checks in both daemon and per-connection modes.

The Android regression switches the main workspace through checking, reconnecting
and disconnected states without opening a status dialog. Device/Doze testing requires
an attached phone or emulator; compiling the instrumentation APK alone does not
verify those device behaviors.
