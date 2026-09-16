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
- `codex` installed and available from the remote login shell (`codex --version`).
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

## Background daemon connections

Edit an SSH host and select **App server → Background daemon**. The app runs
`codex app-server daemon start`, then connects through `codex app-server proxy`
using WebSocket framing over the existing SSH connection. Disconnecting leaves
the daemon running. Existing saved hosts keep **Per connection** mode.

This requires a remote Codex installation supporting both commands. See
[daemon transport](docs/DAEMON_TRANSPORT.md) for requirements, limits, and validation.

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
