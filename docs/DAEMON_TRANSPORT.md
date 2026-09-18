# Background daemon transport prototype

## Using it

Edit an SSH host and select **App server → Background daemon**, then reconnect.
Existing and newly created connections default to **Per connection**; switching
modes is explicit and saved per host.

The remote Codex CLI must support `app-server daemon start` and `app-server proxy`.
The installed CLI 0.154.0 requires a managed standalone installation for daemon
startup. Newer versions may prepare their managed package on the first start.
The app reports startup errors without silently switching transport modes.

## Connection sequence

1. Verify the pinned SSH host key, authenticate, detect the platform and probe Codex.
2. Run `codex app-server daemon start` on its own SSH exec channel. It starts the
   daemon if necessary and otherwise reuses it. The app does not request a restart.
3. Parse the bounded lifecycle JSON and require `started` or `alreadyRunning` plus
   a valid socket path. Use the reported app-server version when available.
4. Open another exec channel running `codex app-server proxy --sock <socketPath>`.
   The socket path is passed as one quoted argument, including paths with shell
   metacharacters. Windows uses an encoded PowerShell command.
5. Complete the HTTP WebSocket upgrade over that channel, then exchange one JSON-RPC
   document per WebSocket message. The proxy itself is only a byte relay.
6. On disconnect, close SSH and the proxy channel. The daemon continues running.

Java-WebSocket's public `WebSocketImpl` protocol engine operates directly on SSH
bytes. It opens no sockets and performs no DNS lookups. The HTTP `Host: localhost`
header is only a handshake field. SSH remains the encryption and authentication
boundary. The app's cleartext-network policy is unchanged.

The RPC client has a message-based transport boundary; per-connection mode keeps
its bounded JSONL reader. JSON validation, approval review, host-key pinning,
modern SSH algorithms, and RPC rate limits apply in both modes.

## Daemon ownership

This is the remote user's shared daemon, including its existing account,
environment and configuration. `daemon start` honors that host's saved remote-control
and updater preferences. This app does not enable remote control, change updater
settings, install a separate remote helper, or stop the daemon on disconnect.
Eligible Codex installations can update according to their own settings.

The prototype tests initialization, reading history and reconnecting. It does not
promise automatic recovery of a running turn or its approval requests after a
connection loss; the existing app's reconnect/resume behavior still applies.

## Additional transport limits

- HTTP upgrade input: 16 KiB; handshake deadline: 15 seconds.
- Incoming WebSocket frame and reassembled message: 4 MiB.
- Fragmented message: at most 1,024 fragments, including empty fragments.
- Buffered decoded messages: at most 128 and at most 4 Mi characters combined.
- WebSocket traffic: burst budgets of 64 MiB of wire input and 100,000 frames
  (including control frames), replenished continuously at those amounts per minute,
  in addition to the RPC character/message rate limits. No lifetime traffic cap.
- Outgoing JSON: 64 MiB encoded as UTF-8, fragmented into frames of at most 1 MiB;
  write deadline: 15 seconds. Very large image combinations can exceed this cap.
- Compression/extensions and subprotocols are not negotiated; unsolicited ones
  are rejected. Binary messages are rejected; ping/pong and close use the library.
- Library output is drained synchronously instead of accumulating an unbounded
  outgoing queue. Closing SSH interrupts blocked reads/writes without taking the
  WebSocket engine lock.
- Preflight/lifecycle stdout and stderr are each capped at 64 KiB and drained
  concurrently. Preflight deadline: 15 seconds; daemon startup: 60 seconds.

## Dependency provenance

`org.java-websocket:Java-WebSocket:1.6.0` is the sole added runtime component.
Its SLF4J dependency uses the existing pinned 2.0.17 API/no-op binding. The release
JAR, POM and source archive came from Maven Central. JAR and POM PGP signatures
verified against fingerprint `C17A66E0723E87B00EC89FCC4B942E3B7A9D8E91`, whose key
identifies maintainer Marcel Prestel. The key was fetched from a public keyserver;
this does not independently establish the signer's identity. Gradle checksums pin
the exact inspected artifacts. No source-to-binary reproducibility claim is made.

OSV returned no matches for the new version or the 561-component pinned inventory
on 2026-09-15. This is not a complete security audit of the library.

## Validation

On 2026-09-15, 101 regular JVM tests passed; the separately invoked real SSH
integration test also passed. Android lint reported zero errors and 19 warnings
(the same warnings as the hardening baseline). Release packaging and Android
test APK assembly passed with strict dependency verification.

The JVM tests include an independent WebSocket wire peer, masking, fragmentation,
ping/pong, close handling, malformed and oversized input, empty-fragment floods,
queue bounds, handshake/write timeouts, shell quoting and saved-host compatibility.

The opt-in integration test uses real OpenSSH and Codex CLI 0.154.0 on Linux. It
initialized and read an empty thread list through two independent SSH proxy
connections, verified that the daemon process survived disconnect, and exercised
the original stdio connection. It uses temporary keys and a private Codex home,
with remote control and automatic updates disabled, then stops its own daemon.
It does not copy or access the user's Codex history or account credentials.

Run it with an installed Codex CLI, OpenSSH server/client and the usual Android
build environment:

```sh
python3 tools/test_daemon_transport.py
```

Normal `testDebugUnitTest` runs skip this opt-in test. Android device/UI execution,
macOS and Windows host integration, and running-turn reconnect behavior remain
untested. PowerShell command construction has JVM coverage.
