# Codex Remote for Android

[![Android CI](https://github.com/liuaho6-commits/codex-remote-android/actions/workflows/android.yml/badge.svg)](https://github.com/liuaho6-commits/codex-remote-android/actions/workflows/android.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

An Android client for Codex hosts reached over SSH. A saved connection represents
one host; after connecting, the app imports every resumable remote Codex
conversation and groups projects from each thread's working directory. The app
does not run a local agent: it connects to a remote `codex app-server` over SSH.
Each host can use a separate app server per connection or a shared background daemon.

Implementation notes and the audited Codex source boundary are documented in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
The current Desktop parity matrix and known gaps are tracked in
[`docs/FEATURE_PARITY.md`](docs/FEATURE_PARITY.md).

## Remote host requirements

- OpenSSH access with a password or PEM/OpenSSH private key.
- Codex installed and available through the configured remote command prefix (default: `codex`).
- A Codex account on the remote host. Existing API-key/ChatGPT auth is reused;
  when required, the app can start Codex's remote ChatGPT device-code login.
- A trusted, least-privilege remote user.

No project path is configured on Android. Existing projects and conversations
come from the remote Codex history returned by `thread/list`.
Opening a conversation resumes its live app-server subscription while loading
only the latest five full turns; older turns are fetched as the chat is scrolled
to the top, with a legacy full-history fallback for older Codex hosts.

The model and reasoning pickers are also remote data. They are loaded from
`model/list`, so the choices follow the Codex version and account configured on
that SSH host rather than a hard-coded Android catalog.

The composer uses the same remote app-server surfaces for Plan mode, service
tiers, permission profiles, image input, running-turn steering, Goals, context
compaction, forks, code review, MCP status, remote skills and installed plugins.
Task pins are stored on the remote Codex thread rather than only on Android.

## Adding a host

The host editor scrolls through the save actions and adjusts for the keyboard and
system bars. Unsaved fields survive screen rotation in memory. Saving or cancelling
clears that draft; Android process termination still discards unsaved credentials.

Under **App server**, set **Codex command prefix** to the command you want the
remote shell to run. It defaults to `codex`, including for existing saved hosts.
Environment settings and global Codex arguments are supported, for example:

```sh
CODEX_HOME=/srv/codex-work /opt/bin/codex-dev -c 'model="gpt-5"'
```

The app appends `--version`, `app-server --listen stdio://`,
`app-server daemon start`, or `app-server proxy --sock ...` to this prefix.
Use POSIX shell syntax for Linux/macOS and PowerShell syntax for Windows.
Save and reconnect to apply a changed prefix.

## Background daemon connections

Edit an SSH host and select **App server → Background daemon**. The app runs
`codex app-server daemon start`, then connects through `codex app-server proxy`
using WebSocket framing over the existing SSH connection. Disconnecting leaves
the daemon running. Existing saved hosts keep **Per connection** mode.

This requires a remote Codex installation supporting both commands. See
[daemon transport](docs/DAEMON_TRANSPORT.md) for requirements, limits, and validation.

## Connection recovery

The app checks the remote connection when you return and periodically while it is
visible. A lost or unresponsive connection shows **Reconnecting** in the workspace;
retries pause while backgrounded or offline and resume when you return or the network
recovers. The selected conversation is reattached and refreshed without replaying
messages, commands or approval responses. Host-key, credential and security-limit
failures require manual attention. See [connection recovery](docs/CONNECTION_RECOVERY.md).

Saved sessions start loading after initialization, with recent tasks appearing first
and a loading indicator until import finishes. Reconnecting restores the open
conversation without waiting for the session catalog; routine list updates
fetch only the recent page. The initial page requests 20 tasks, using database-only
listing on supporting servers to avoid scanning session logs. Code-review completion
cannot reactivate a finished turn.
If the server explicitly reports the selected conversation missing from memory, sending
resumes that same conversation and retries once. Uncertain deliveries are never replayed.

Approval-review notices, warnings, and other status messages appear in the
conversation's scrolling history. They stay visible as you scroll and survive a
reconnect to the same open task. Host settings show persistent inline messages
with a Dismiss button. Routine disconnects stay in connection status rather than
adding repeated socket-abort lines to the conversation.

Automatic approvals appear as compact, collapsed rows with their risk level.
Consecutive approvals share a count; tap the row to see every full explanation
and authorization detail. All risk levels start collapsed, with higher risk still
highlighted. Expansion state is temporary. Denials, warnings, and failures remain
separate and visible.

Expanded tool output uses a scrolling text panel, so a long command cannot grow
one Compose row beyond its size limits. The panel keeps the complete output and
follows new text when you are at the end. Markdown uses the same containment for
exceptionally tall replies, without a message-length cutoff.

The composer's **Add** menu offers **Files and folders** for remote paths,
**Images** for image attachments, and **Upload file** for any document or binary
file on your phone. Uploads stream over the existing SSH connection with progress,
cancellation, and no file-size limit. Each upload gets its own private
`.codex-remote-upload-…` directory in the remote user's home. When it finishes, its
absolute path is inserted into your draft for Codex to read. Uploaded files remain
on the remote host for later turns; project files are not overwritten.

## File previews

Tap a file link in an assistant message to preview it from the connected SSH host.
Markdown files use the same renderer as chat, including tables and math. Other
UTF-8 text files use a selectable, numbered source view. Relative links resolve from the
conversation directory, or from the current document when following a nested link.
Source references such as `report.md:20`, `report.md:20:7` or `report.md#L20`
open the numbered source at line 20 and highlight it. References past the end
land on the last line. For Markdown, switch between **Source** and **Preview**
without fetching the file again. Preview renders the complete document; column
positions and line-range endings are not used.

Image links open an image preview with pinch zoom, including SVG, PNG, JPEG, GIF,
WebP, BMP, ICO and AVIF. Images are fetched over SFTP and displayed locally;
previews do not execute scripts or load resources from other servers. If the
phone cannot start WebView or its renderer exits, the preview offers downloading
the file while keeping the app open.

The SSH server must provide SFTP. Text previews are read-only and limited to
regular UTF-8 files up to 256 KiB. Use **Download** to save any regular file,
including binary files and files too large to preview. Download starts immediately
and streams the original bytes with no file-size limit. On Android 10 or newer,
files are saved to the phone's Downloads folder; older phones use app-owned
storage. The completion dropdown offers **Open**, which launches the phone's
registered file handler, and **Download again**. Android chooses an app if no
default has been set. If no app supports the file type, the preview explains that.
Line references affect only the preview: downloads save the entire file.

Progress and cancellation are available during the transfer. Closing the preview
cancels an unfinished transfer and removes its incomplete output. Completed
downloads remain available after closing the preview. Temporary image preview
files are removed when the preview closes.

## Install

Download the signed APK from the latest
[GitHub release](https://github.com/liuaho6-commits/codex-remote-android/releases/latest).
Android may ask you to allow installation from your browser or file manager.

The project is an unofficial community client. It is not affiliated with or
endorsed by OpenAI. Codex and OpenAI are trademarks of their respective owner.

## Build

This working branch contains security hardening and an English interface.
The release build, JVM tests, Android lint and strict dependency verification
have passed. Read [SECURITY.md](SECURITY.md) for the changes, validation evidence
and remaining device-testing limits. Releases are unsigned unless signing
credentials are supplied.

Open this directory in Android Studio, or run `./gradlew assembleDebug` with
JDK 17, Android SDK platform 35 and Build Tools 36.0.0 installed.

Run the device-side regression suite on a connected emulator or Android device:

```text
./gradlew connectedDebugAndroidTest
```

The first SSH handshake asks you to verify its SHA-256 host-key fingerprint
before any password or private key is sent. Subsequent key changes are blocked
until the saved fingerprint is explicitly cleared by editing the connection.

To produce a signed release build, provide these environment variables before
running `./gradlew assembleRelease`:

```text
CODEX_REMOTE_KEYSTORE_PATH
CODEX_REMOTE_KEYSTORE_PASSWORD
CODEX_REMOTE_KEY_ALIAS
CODEX_REMOTE_KEY_PASSWORD
```

Signing material must remain outside the repository. APKs, keystores, local SDK
configuration, build caches, and QA captures are excluded by `.gitignore`.

## License

Licensed under the [Apache License 2.0](LICENSE).
