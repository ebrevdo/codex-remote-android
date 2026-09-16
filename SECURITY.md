# Security hardening and validation status

This working branch is based on v0.1.2, commit
`372581ad5cc66677d5322a9a447a03277d36ce15`. It is a locally modified build, not an
authenticated upstream release.

## Daemon transport prototype

The optional shared-daemon transport adds Java-WebSocket 1.6.0 with pinned JAR
and POM checksums. Its release signatures were verified, and the updated advisory
inventory contains 561 component/version entries with zero OSV matches.
[Daemon transport validation](docs/DAEMON_TRANSPORT.md) describes the added bounds,
real OpenSSH/Codex integration test, and platform/device testing limits. The
baseline results below describe the earlier hardened release.

## Hardening baseline validated on 2026-09-15

The hardened app builds with strict dependency verification and
application/test and build-plugin lockfiles. Validation used JDK 17, Gradle
9.4.1, SDK platform 35 and Build Tools 36.0.0.

- 78 JVM unit tests passed, including the new security regression tests.
- Android lint passed with zero errors and 19 warnings.
- Release packaging and Android instrumentation-test APK assembly passed.
- The advisory audit checked 560 component/version entries and reported zero
  OSV matches. This includes build and test libraries as well as runtime libraries.
- All 85 runtime binary artifacts have published source archives.
- SSHJ and all three Bouncy Castle binary signatures were verified, and Gradle's
  cached bytes matched the verified artifacts.

No Android device was connected and hardware emulator acceleration was
unavailable. Instrumentation tests were compiled but **not run**. No runtime
traffic capture or exhaustive dependency source audit was performed.

The Gradle release output is unsigned. A separate installable APK was subsequently
signed with a newly generated personal RSA-4096 key; APK signature schemes v2 and
v3 and ZIP alignment were verified. All original archive entry contents match the
unsigned build. The key and generated password are held in restricted files
outside the repository. The validation manifest records the signed APK and
certificate hashes. This personal signature does not establish upstream publisher
identity or guarantee that all third-party code is safe.

### Lint security warning reviewed

Bouncy Castle contains an optional EST TLS helper,
`org.bouncycastle.est.jcajce.JcaJceUtils.getTrustAllTrustManager`. This app does
not call it. R8 removes EST and DANE clients from the release, and explicit
`-checkdiscard` rules make future release builds fail if those classes are
retained. The lint warning refers to the dependency JAR before shrinking;
it is not a trust manager used by the application. The remaining warnings
concern version availability, target SDK, obsolete API guards and resource shrinking.

The [validation manifest](docs/security-validation.json) records the APK SHA-256,
test results, dependency counts and verified signer fingerprints.

## Changes

| Original issue | Hardened behavior |
| --- | --- |
| Feedback implicitly attached remote logs and task identity | Requests set `includeLogs=false`, omit `threadId`, and send only the explicit category/message plus client tag. The dialog identifies the remote feedback service boundary. |
| Approval dialogs omitted actual access | The dialog shows command/cwd, network and filesystem permissions, and complete file-change payloads including rename destinations. Unknown fields/scopes, missing diffs and persistent write-root grants cannot be approved. |
| Approval confusion | A click is bound to the exact request object and current connection. Another request cannot overwrite an open dialog; it is denied. Duplicate pending RPC request IDs are rejected. Unknown methods receive a protocol error. |
| Overbroad or accidental approvals | Additional permissions last for one turn. Session approval buttons are removed. User-input choices start empty; denial never sends answers. `/permissions` still offers Ask, Approve for me, Full access and Read only. |
| Plaintext private-key cache files | SSHJ loads PEM/OpenSSH keys from memory. One-off passphrase character arrays are cleared. Old matching cache files are removed before connecting. JVM strings cannot be reliably erased from memory. |
| Legacy SSH negotiation | Explicit allowlists exclude SHA-1 signatures/KEX, DSA, group1, CBC, RC4 and non-encrypt-then-MAC algorithms. AES-GCM/CTR, ChaCha20, SHA-2, ECDH, Curve25519 and modern RSA/Ed25519 signatures remain. Old servers may stop connecting. |
| Unbounded incoming data | Limits are applied during reads, before whole inputs are allocated. See the limits below. Excessive input closes the SSH transport. |
| Unrestricted browser launches | MCP authorization and Markdown links require a destination confirmation and HTTPS on port 443. Userinfo, ambiguous authorities, controls and custom/intent schemes are blocked. Device login only accepts the documented OpenAI device page. |
| Development SSH helper exposure | Helpers bind only to loopback IPs, require explicit credentials, take passwords via a prompt or named environment variable, limit concurrent clients, restrict key/log permissions and omit sensitive protocol payloads from bounded event logs. |
| Mutable build inputs | Gradle distribution checksum and CI action commit pins are set. Strict dependency lock and artifact verification configuration plus an OSV inventory audit are added; the complete dependency pins and advisory audit are in place. |
| Mixed interface language | App-owned dialogs, widget resources, labels, errors and dates are English; language splitting is disabled so English resources remain available. Remote/user content and Android-owned pickers retain their original/system language. |

The device URL policy follows the [upstream app-server authentication protocol](https://github.com/openai/codex/blob/61a44880a85d2fd0d8770908dea5733495e571c8/codex-rs/app-server/README.md).
Permission and file-change validation follows the protocol schema at the same
commit; a future schema extension requires an explicit client update.

## Input limits

- RPC line: 4 Mi characters; JSON nesting: 64 levels.
- Per connection: 64 Mi incoming RPC characters or 100,000 lines, whichever is
  reached first. Reconnect to continue after the limit.
- Diagnostic line: 16 Ki characters; diagnostic session: 2 Mi characters.
- Preflight stdout and stderr: 64 KiB each.
- Image: 20 MiB, enforced while reading even if its provider reports a false size;
  the existing four-image limit remains.
- Approval params and associated file-change details: 64 Ki characters each;
  approval nesting: 32; at most 32 cached file-change events and pending requests.
- Automatic catalog/history pagination: 100 pages. Repeated cursors also fail.
- Messages longer than 32 Ki characters use plain selectable text instead of
  rich Markdown/LaTeX rendering.

These limits can reject unusually large legitimate histories or outputs. They
bound input size; they are not a proof against every parser or rendering DoS.

## Dependency changes

| Component | Version selected |
| --- | --- |
| SSHJ | 0.40.0, which removes the old net.i2p.crypto EdDSA dependency |
| Bouncy Castle provider, PKIX and utility | 1.86 |
| Kotlin Android, Compose and serialization build plugins | 2.4.20 |
| Android Gradle plugin | 9.2.1 |
| Gradle distribution | 9.4.1 |
| SLF4J no-op binding | 2.0.17 |

The complete inventory audit returned zero OSV matches on 2026-09-15 after
upgrading affected build/test transitive dependencies too. The extra pins are
in `gradle/security-versions.properties`: Guava 33.7.1 (Android/JRE variants),
Netty 4.1.138.Final, Commons Lang 3.20.0, Apache HttpClient 4.5.14, jose4j 0.9.7,
JDOM 2.0.6.1 and Bouncy Castle 1.86. Known vulnerable older versions were removed
from the checksum allowlist only after both current lockfiles no longer selected them.

An advisory database can have incomplete coverage. A zero-match report is not
proof that a library has no vulnerabilities.

The bundled Bouncy Castle implementation is registered under a separate provider
name for SSHJ's Ed25519/X25519 support on older Android versions. It does not
replace the platform's `BC` provider. Its JVM regression tests pass, including valid and altered Ed25519 signatures
and X25519 key generation. Android API 26 device validation remains outstanding.

The Gradle wrapper JAR is the previously verified stock 8.10 wrapper; it can
launch the checksum-pinned 9.4.1 distribution. Its SHA-256 is enforced by
`tools/verify_build_pins.py`. Checksums protect against subsequent artifact
changes; they do not prove that a published binary matches its source.

### Critical artifact signature verification

- Bouncy Castle signer fingerprint:
  `7B121B76A7ED6CE6E60AD51784E913A8E3A748C0`.
  The key came from the [official download page](https://www.bouncycastle.org/download/bouncy-castle-java/)
  and [published Maven signing key](https://downloads.bouncycastle.org/java/bc_maven_public_key.asc).
- SSHJ signer fingerprint:
  `B200B4AA914DE0340741F598EBE906E1F4ACA3B7`.
  The key came from [the maintainer's GitHub profile](https://github.com/hierynomus.gpg);
  its key ID also matches the [signed upstream release commit](https://github.com/hierynomus/sshj/releases).

All four critical JARs verified successfully with these keys. Every runtime
artifact has a source archive on its declared Maven Central or Google repository.
Other artifacts are protected by SHA-256 pins after their initial repository
download; their publisher signatures were not exhaustively authenticated.
Global Gradle PGP verification is not enabled: the verified critical bytes are
instead enforced by their checked-in SHA-256 hashes.

## Rebuilding and maintaining dependency pins

Run `python3 tools/verify_build_pins.py` before building. Then use:

```text
./gradlew --dependency-verification strict testDebugUnitTest lintDebug assembleRelease assembleDebugAndroidTest
python3 tools/audit_dependencies.py
```

Configure JDK 17 and an Android SDK path first. The audit sends public Maven
package names and versions to OSV. It does not send source code or credentials.
Normal builds must not use verification-writing flags or bypass strict locks.

For a deliberate dependency update, use an isolated build environment and
generate candidate locks/checksums with `--write-locks
--write-verification-metadata sha256` while running the actual build/test tasks.
Review repository origin, checksums, available signatures and advisory results
before trusting new pins. Generating hashes from downloads alone does not
establish authenticity. Preserve existing verification settings and remove
obsolete approvals only after confirming they are not selected in current locks.

Before using the app with sensitive credentials, complete device checks on API
26 and a current Android version: encrypted and unencrypted OpenSSH/PEM keys,
host-key changes, modern and legacy-only SSH servers, approval/denial including
renames, oversized streams/images, and all external link paths. Capture traffic
on a controlled device/host to verify the expected destinations.

The locally generated APK signing key establishes continuity for personal build
updates. Preserve the encrypted key and its password. The original Gradle output
remains unsigned; the separately signed APK carries the personal build certificate.

## Development helper use

Both SSH helpers accept `--username` and `--password-env NAME`; without the
password option they prompt without echo. Passwords must have at least 16
characters. The literal `--host` IP must be loopback. Use a local emulator and
ADB port forwarding for testing; the real bridge can access the account and
files available to the local Codex process and must only be used with test data.

## Remaining trust boundary

The chosen SSH host and its Codex installation enforce remote sandboxing,
approvals, plugins and outbound service access. An already malicious remote host
can ignore this client's decisions. Use a dedicated remote account and verify
its host fingerprint through an independent channel.

No source-to-binary reproduction, exhaustive third-party source audit, runtime
packet capture or device validation has been completed. Treat this as a hardened,
build-validated client with the remaining trust boundaries described above.

## Reporting an upstream vulnerability

Please report upstream vulnerabilities privately through
[GitHub Security Advisories](https://github.com/liuaho6-commits/codex-remote-android/security/advisories/new).

Do not open a public issue for credentials, authentication bypasses, host-key
verification problems, or other vulnerabilities that could put users at risk.
