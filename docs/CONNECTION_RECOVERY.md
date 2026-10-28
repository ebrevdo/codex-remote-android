# Connection recovery

## Behavior

Both SSH transports use the same recovery policy. While the app is visible, a
read-only `account/read` check runs every 20 seconds, with a 10-second response
budget. Returning to the app checks immediately only when the last successful check
or connection is at least 20 seconds old. A quick return on the same network keeps
the existing connection and waits for the next check. A changed or regained default
network triggers an immediate check; repeated availability callbacks do not.
The check age uses Android's [elapsed real-time clock](https://developer.android.com/reference/android/os/SystemClock#elapsedRealtime()),
which includes device sleep.
A JSON-RPC error response still proves the server is reachable. Checks do
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
network triggers recovery immediately when a connection has already failed. The
app does not create a foreground service
or promise an uninterrupted background socket.

Retryable disconnects update the connection banner and Remote status details without
appending chat notices. Socket-abort notices are also discarded when a pending
operation reports them. Authentication, host-key, and other actionable failures
still produce notices.

Reconnection uses stored credentials and the pinned host key, initializes a fresh
RPC client, refreshes host state, and resumes the selected thread. Its history and
reported active-turn ID are restored. Manual reconnect to the same host also keeps
the selected conversation. Messages whose delivery failed or became uncertain, commands,
and approval responses are not resent. Old pending approvals are cleared; the server
must send any new requests.

The saved-session catalog starts loading as soon as RPC initialization finishes,
alongside account and model metadata. Restoring the open conversation does not wait
for the catalog. Each page becomes visible as it arrives, with recent tasks first.
The first request asks for 20 tasks in descending update-time order; subsequent pages
request 100. Listing uses `useStateDbOnly` to avoid scan-and-repair reads of session
logs on supporting servers. An empty index or an explicit unsupported/unavailable
response falls back to normal listing. Empty archived history does not disable the
fast path for active history. Older servers may ignore the option and retain their
usual listing cost.

The workspace and sidebar show loading until import finishes; only a successful,
empty result shows no history. Catalog errors have a Retry button in the sidebar.
Notifications share one refresh job; after import they refresh only the recent page
and merge it with cached older tasks. Archive/delete notifications remove those tasks
without requiring another full scan. Unchanged results do not rebuild the project
list. Disconnect cancels the previous catalog job.
Skills and plugins load only for the selected project, and changing projects cancels
the previous catalog request so its result cannot replace the new project's choices.

Review-start replies are guarded by the turn-event version, selection, and connection.
A completion or a newer turn takes precedence over a delayed reply. When a message is
explicitly rejected because its exact thread/conversation ID is not loaded, the app
resumes that same ID and retries once. The restored turn determines whether the input
starts a turn or steers an active one. A failed resume, a repeated rejection, another
RPC error, or an I/O failure stops this recovery. No replacement conversation is created.

A connection check keeps the composer visible and editable; sending waits for the
check to finish. Draft text, cursor position, attachments, mentions and goal mode
stay in memory while the workspace reconnects to the same open task. Draft state
sits above the temporary connection panels, and restoring that task keeps its
conversation revision. Choosing another task or New task still clears the draft.
A task chosen during reconnection also takes precedence over the previous selection.

A daemon remains on the host when its SSH connection closes. Per-connection mode
starts a new app server and resumes persisted history; it cannot preserve the old
server process or guarantee that its interrupted work continues.

Host-key changes, unknown keys, authentication failures, invalid configuration,
protocol violations and input-limit failures stop automatic retries. Explicit
disconnect cancels pending recovery. Neither a network event nor returning to the
app overrides those decisions.

## Task navigation

Choosing New task or switching projects clears the previous task's running state,
turn ID, history-loading state and composer draft. It does not stop the remote turn.
Task creation and resume responses are tied to the selection and connection that
started them, so delayed results cannot reopen an abandoned task or disable the
new composer. A failed resume clears loading state and reports its error.

Refreshing the task list updates the sidebar without changing the open task. A
list response can predate a newly created task, so omission from that list must
not clear its selection, history or live-event subscription.

JVM regressions cover new-task resets, stale catalogs, pending task creation,
reopening the same task, connection replacement and archive selection. The Android
regression opens a new task during restoration and verifies that sending works;
it requires a device or emulator to execute.

## Input bounds

Parser-depth, fragment and queued-message-count bounds apply to WebSocket input,
which uses the library's default message-size range. Stdio input has a per-line cap.
RPC and WebSocket input have burst budgets of 64 Mi characters/64 MiB and 100,000
messages/frames, refilling at those amounts per minute. Diagnostic input has budgets
of 2 Mi characters and 10,000 lines, also refilling per minute. Quiet time cannot
accumulate more than one full burst. This permits long sessions while still rejecting
excessive bursts and sustained input.

## Validation

On 2026-10-12, 163 JVM tests passed and one integration test was skipped. Six
workspace UI tests passed on an Android 15/API 35 software emulator. These cover
editable drafts during checks, drafts and goal mode across reconnection, new-task
resets, thread switching, visible recovery status and Back navigation. Strict offline
release and test-APK builds passed; lint reported zero errors and five existing
warnings. The release was signed with the existing key and its payload verified.

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
