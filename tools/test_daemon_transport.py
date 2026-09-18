#!/usr/bin/env python3
"""Exercise real Codex daemon/proxy and stdio through a private loopback OpenSSH server.

Requires Linux, sshd/ssh-keygen, a Codex CLI supporting daemon/proxy, and the
normal Android build environment. No account credentials or user history are
copied. Daemon auto-updates and remote control are disabled in the test home.
"""

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
    codex = shutil.which("codex")
    sshd = shutil.which("sshd")
    sftp_server = shutil.which("sftp-server", path="/usr/lib/openssh:/usr/libexec/openssh:/usr/lib/ssh")
    if not sftp_server:
        raise SystemExit("Install the OpenSSH SFTP server before running this test.")
    if not codex or not sshd or not shutil.which("ssh-keygen"):
        raise SystemExit("Install Codex and OpenSSH server/client before running this opt-in test.")
    with tempfile.TemporaryDirectory(prefix=".codex-ssh-", dir=Path.home()) as directory:
        work = Path(directory)
        codex_home = work / "home"
        settings_dir = codex_home / "app-server-daemon"
        settings_dir.mkdir(parents=True)
        (codex_home / "config.toml").write_text("[features]\nplugins = false\n")
        (settings_dir / "settings.json").write_text(json.dumps({
            "remoteControlEnabled": False,
            "shutdownGraceSeconds": 0,
            "updater": {"autoUpdateEnabled": False},
        }))
        # CLI 0.154 requires this managed layout. The link reuses the installed
        # executable; disabled updates ensure the test never changes its target.
        managed = codex_home / "packages/standalone/current"
        managed.mkdir(parents=True)
        (managed / "codex").symlink_to(Path(codex).resolve())
        child_env = dict(os.environ, CODEX_HOME=str(codex_home))
        for name in ("host", "client"):
            subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", str(work / name)], check=True)
        public = (work / "host.pub").read_text().split()[1]
        fingerprint = "SHA256:" + base64.b64encode(hashlib.sha256(base64.b64decode(public)).digest()).decode().rstrip("=")
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        forced = work / "command.py"
        # Allow the read-only SFTP subsystem and the app's precise Codex commands. Never execute
        # client-provided shell text. Shell quoting is separately regression-tested.
        forced.write_text("import os, shlex\n" +
            "if os.environ.get('SSH_ORIGINAL_COMMAND') == 'codex-remote-test-sftp':\n" +
            f"    os.execv({sftp_server!r}, [{sftp_server!r}, '-R'])\n" +
            "outer = shlex.split(os.environ.get('SSH_ORIGINAL_COMMAND', ''))\n" +
            "assert len(outer) == 4 and outer[:3] == ['exec', '${SHELL:-/bin/sh}', '-lc']\n" +
            "args = shlex.split(outer[3])\n" +
            "if args and args[0] == 'exec': args = args[1:]\n" +
            f"sock = {str(codex_home / 'app-server-control/app-server-control.sock')!r}\n" +
            "assert args in [['codex', 'app-server', 'daemon', 'start'], " +
            "['codex', 'app-server', 'proxy', '--sock', sock], " +
            "['codex', 'app-server', '--listen', 'stdio://']]\n" +
            f"env = dict(os.environ, CODEX_HOME={str(codex_home)!r})\n" +
            f"os.execve({codex!r}, [{codex!r}] + args[1:], env)\n")
        config = work / "sshd_config"
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
StrictModes yes
DisableForwarding yes
PermitTTY no
MaxAuthTries 2
MaxSessions 4
Subsystem sftp codex-remote-test-sftp
ForceCommand {shlex.quote(shutil.which('python3'))} {shlex.quote(str(forced))}
""")
        text_file = work / "report $(false); 'draft'.md"
        text_file.write_text("# SFTP preview\n\n**Works** without executing a shell.\n")
        (work / "large.txt").write_bytes(b"x" * (256 * 1024 + 1))
        (work / "binary.bin").write_bytes(b"binary\0data")
        os.mkfifo(work / "pipe")
        fixture = work / "fixture.json"
        fixture.write_text(json.dumps({"port": port, "username": getpass.getuser(),
            "privateKey": str(work / "client"), "fingerprint": fingerprint, "codexHome": str(codex_home), "previewFile": str(text_file), "previewDirectory": str(work)}))
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
            env = dict(os.environ, CODEX_REMOTE_SSH_TEST_FIXTURE=str(fixture))
            subprocess.run([str(ROOT / "gradlew"), "testDebugUnitTest", "--rerun", "--tests",
                "com.codex.remote.data.ssh.DaemonSshIntegrationTest", "--offline",
                "--dependency-verification", "strict", "--console", "plain"],
                cwd=ROOT, env=env, check=True, timeout=240)
            report = ROOT / "app/build/test-results/testDebugUnitTest/TEST-com.codex.remote.data.ssh.DaemonSshIntegrationTest.xml"
            result = ET.parse(report).getroot().attrib
            if result.get("tests") != "1" or any(result.get(key) != "0" for key in ("skipped", "failures", "errors")):
                raise RuntimeError("Integration test did not run successfully: " + repr(result))
            print("PASS: real OpenSSH file previews and error isolation, Codex daemon reuse, and stdio compatibility")
        except Exception:
            print((work / "sshd.log").read_text())
            raise
        finally:
            server.terminate()
            server.wait(timeout=10)
            stopped = subprocess.run([codex, "app-server", "daemon", "stop"], env=child_env,
                capture_output=True, text=True, timeout=30)
            if stopped.returncode:
                raise RuntimeError("Test daemon cleanup failed: " + stopped.stderr)


if __name__ == "__main__":
    main()
