#!/usr/bin/env python3
"""Run JVM upload tests against a private loopback OpenSSH SFTP server, without Android."""

import base64
import getpass
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import socket
import subprocess
import tempfile
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]


def main():
    sshd = shutil.which("sshd")
    if not sshd or not shutil.which("ssh-keygen"):
        raise SystemExit("Install OpenSSH server/client before running this opt-in test.")
    with tempfile.TemporaryDirectory(prefix="codex-upload-test-") as directory:
        work = Path(directory)
        for name in ("host", "client"):
            subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(work / name)], check=True)
        public = (work / "host.pub").read_text().split()[1]
        fingerprint = "SHA256:" + base64.b64encode(hashlib.sha256(base64.b64decode(public)).digest()).decode().rstrip("=")
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        config = work / "sshd_config"
        # This ephemeral key permits only SFTP, bound to loopback. Its directory is private.
        config.write_text(f"""Port {port}
ListenAddress 127.0.0.1
HostKey {work / 'host'}
PidFile {work / 'sshd.pid'}
AuthorizedKeysFile {work / 'client.pub'}
AllowUsers {getpass.getuser()}
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
PermitRootLogin prohibit-password
UsePAM no
StrictModes no
DisableForwarding yes
PermitTTY no
MaxAuthTries 2
Subsystem sftp internal-sftp
ForceCommand internal-sftp -d {shlex.quote(str(work))}
""")
        fixture = work / "fixture.json"
        fixture.write_text(json.dumps({
            "port": port, "username": getpass.getuser(), "privateKey": str(work / "client"),
            "fingerprint": fingerprint, "directory": str(work),
        }))
        fixture.chmod(0o600)
        server = subprocess.Popen([sshd, "-D", "-f", str(config), "-E", str(work / "sshd.log")])
        try:
            for _ in range(50):
                if server.poll() is not None:
                    raise RuntimeError((work / "sshd.log").read_text())
                try:
                    with socket.create_connection(("127.0.0.1", port), timeout=0.1):
                        break
                except OSError:
                    time.sleep(0.1)
            else:
                raise RuntimeError("Private SSH server did not become ready")
            env = dict(os.environ, CODEX_REMOTE_UPLOAD_TEST_FIXTURE=str(fixture))
            subprocess.run([
                str(ROOT / "gradlew"), "testDebugUnitTest", "--rerun", "--tests",
                "com.codex.remote.data.ssh.RemoteFileUploaderTest", "--offline",
                "--dependency-verification", "strict", "--no-parallel", "--max-workers=2", "--no-daemon",
                "-Dorg.gradle.jvmargs=-Xmx2g -Dfile.encoding=UTF-8", "-Pkotlin.compiler.execution.strategy=in-process",
            ], cwd=ROOT, env=env, check=True, timeout=240)
            report = ROOT / "app/build/test-results/testDebugUnitTest/TEST-com.codex.remote.data.ssh.RemoteFileUploaderTest.xml"
            result = ET.parse(report).getroot().attrib
            if int(result.get("tests", "0")) < 5 or any(result.get(key) != "0" for key in ("skipped", "failures", "errors")):
                raise RuntimeError("Upload tests did not run successfully: " + repr(result))
            print("PASS: raw binary uploads, private permissions, unique paths, failure/cancellation cleanup, and SSH reuse")
        except Exception:
            print((work / "sshd.log").read_text())
            raise
        finally:
            server.terminate()
            server.wait(timeout=10)


if __name__ == "__main__":
    main()
