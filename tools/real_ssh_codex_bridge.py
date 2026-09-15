#!/usr/bin/env python3
"""Expose the installed Codex app-server through a local SSH test endpoint.

This is an integration harness, not an app-server double. Every JSONL byte is
forwarded to the real Codex CLI process so Android exercises the production
protocol, account, model catalog, history, and agent runtime.
"""

from __future__ import annotations

import argparse
import base64
import getpass
import hashlib
import hmac
import ipaddress
import json
import os
import shutil
import socket
import subprocess
import threading
import time
from pathlib import Path
from typing import Any

import paramiko


class EventLog:
    def __init__(self, path: Path) -> None:
        self.path = path
        self.lock = threading.Lock()

    def write(self, event: str, **details: Any) -> None:
        # Protocol bodies, shell text, usernames, and exception strings may contain secrets.
        safe_details = {
            key: value
            for key, value in details.items()
            if key in {"accepted", "channel_id", "exit_status", "host", "port", "fingerprint"}
        }
        record = {"time": time.time(), "event": event, **safe_details}
        line = json.dumps(record, ensure_ascii=True, separators=(",", ":"))
        with self.lock:
            if self.path.exists() and self.path.stat().st_size >= 1024 * 1024:
                self.path.write_text("", encoding="utf-8")
            with self.path.open("a", encoding="utf-8") as stream:
                stream.write(line + "\n")
        print(line, flush=True)


class BridgeSshServer(paramiko.ServerInterface):
    def __init__(self, username: str, password: str, events: EventLog) -> None:
        self.username = username
        self.password = password
        self.events = events
        self.commands: dict[int, str] = {}
        self.condition = threading.Condition()

    def get_allowed_auths(self, username: str) -> str:
        return "password"

    def check_auth_password(self, username: str, password: str) -> int:
        accepted = (
            hmac.compare_digest(username.encode(), self.username.encode())
            and hmac.compare_digest(password.encode(), self.password.encode())
        )
        self.events.write("auth_password", username=username, accepted=accepted)
        return paramiko.AUTH_SUCCESSFUL if accepted else paramiko.AUTH_FAILED

    def check_channel_request(self, kind: str, chanid: int) -> int:
        return paramiko.OPEN_SUCCEEDED if kind == "session" else paramiko.OPEN_FAILED_ADMINISTRATIVELY_PROHIBITED

    def check_channel_exec_request(self, channel: paramiko.Channel, command: bytes) -> bool:
        command_text = command.decode("utf-8", errors="replace")
        with self.condition:
            self.commands[channel.get_id()] = command_text
            self.condition.notify_all()
        self.events.write("exec_request", channel_id=channel.get_id(), command=command_text)
        return True

    def wait_for_command(self, channel_id: int, timeout: float) -> str | None:
        deadline = time.monotonic() + timeout
        with self.condition:
            while channel_id not in self.commands:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    return None
                self.condition.wait(remaining)
            return self.commands.pop(channel_id)


def host_fingerprint(key: paramiko.PKey) -> str:
    digest = hashlib.sha256(key.asbytes()).digest()
    return "SHA256:" + base64.b64encode(digest).decode("ascii").rstrip("=")


def load_or_create_host_key(path: Path) -> paramiko.RSAKey:
    if path.exists():
        path.chmod(0o600)
        return paramiko.RSAKey.from_private_key_file(str(path))
    key = paramiko.RSAKey.generate(2048)
    key.write_private_key_file(str(path))
    path.chmod(0o600)
    return key


def resolve_codex_process(node: Path, codex_js: Path, *arguments: str) -> list[str]:
    if not node.is_file():
        raise FileNotFoundError(f"Node executable not found: {node}")
    if not codex_js.is_file():
        raise FileNotFoundError(f"Codex CLI entrypoint not found: {codex_js}")
    return [str(node), str(codex_js), *arguments]


def handle_probe(
    channel: paramiko.Channel,
    command: str,
    node: Path,
    codex_js: Path,
    events: EventLog,
) -> bool:
    if "__CODEX_POSIX__" in command:
        channel.send_stderr(b"Windows integration bridge\n")
        channel.send_exit_status(1)
        channel.close()
        return True
    if "codex --version" not in command:
        return False

    completed = subprocess.run(
        resolve_codex_process(node, codex_js, "--version"),
        capture_output=True,
        check=False,
        timeout=20,
    )
    if completed.stdout:
        channel.sendall(completed.stdout)
    if completed.stderr:
        channel.send_stderr(completed.stderr)
    channel.send_exit_status(completed.returncode)
    channel.close()
    events.write("codex_version", exit_status=completed.returncode)
    return True


def pump_channel_to_process(channel: paramiko.Channel, process: subprocess.Popen[bytes]) -> None:
    try:
        while process.poll() is None and not channel.closed:
            try:
                chunk = channel.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                break
            if process.stdin is None:
                break
            process.stdin.write(chunk)
            process.stdin.flush()
    except (EOFError, OSError):
        pass
    finally:
        if process.stdin is not None:
            try:
                process.stdin.close()
            except OSError:
                pass


def pump_process_stream(stream: Any, sender: Any) -> None:
    try:
        while True:
            chunk = stream.read(65536)
            if not chunk:
                break
            sender(chunk)
    except (EOFError, OSError, socket.error):
        pass


def bridge_app_server(
    channel: paramiko.Channel,
    node: Path,
    codex_js: Path,
    events: EventLog,
) -> None:
    process = subprocess.Popen(
        resolve_codex_process(node, codex_js, "app-server", "--listen", "stdio://"),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,
    )
    events.write("app_server_started", pid=process.pid)
    channel.settimeout(1.0)
    workers = [threading.Thread(target=pump_channel_to_process, args=(channel, process), daemon=True)]
    if process.stdout is not None:
        workers.append(threading.Thread(target=pump_process_stream, args=(process.stdout, channel.sendall), daemon=True))
    if process.stderr is not None:
        workers.append(threading.Thread(target=pump_process_stream, args=(process.stderr, channel.send_stderr), daemon=True))
    for worker in workers:
        worker.start()

    try:
        return_code = process.wait()
    finally:
        if process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                process.kill()
        try:
            channel.send_exit_status(process.returncode or 0)
        except (EOFError, OSError, socket.error):
            pass
        channel.close()
        events.write("app_server_stopped", pid=process.pid, exit_status=process.returncode)


def handle_channel(
    channel: paramiko.Channel,
    server: BridgeSshServer,
    node: Path,
    codex_js: Path,
    events: EventLog,
) -> None:
    command = server.wait_for_command(channel.get_id(), 15)
    if command is None:
        channel.send_stderr(b"Expected an exec request\n")
        channel.send_exit_status(1)
        channel.close()
        return
    # Let Paramiko send CHANNEL_SUCCESS for the exec request before a fast
    # probe writes its exit status and closes the channel.
    time.sleep(0.05)
    if handle_probe(channel, command, node, codex_js, events):
        return
    if "codex app-server --listen stdio://" in command:
        bridge_app_server(channel, node, codex_js, events)
        return
    channel.send_stderr(b"Unsupported integration command\n")
    channel.send_exit_status(127)
    channel.close()


def handle_client(
    client: socket.socket,
    peer: tuple[str, int],
    host_key: paramiko.PKey,
    username: str,
    password: str,
    node: Path,
    codex_js: Path,
    events: EventLog,
) -> None:
    events.write("connection_open", peer=f"{peer[0]}:{peer[1]}")
    transport = paramiko.Transport(client)
    transport.get_security_options().kex = ("diffie-hellman-group14-sha256",)
    transport.add_server_key(host_key)
    server = BridgeSshServer(username, password, events)
    workers: list[threading.Thread] = []
    try:
        transport.start_server(server=server)
        while transport.is_active():
            channel = transport.accept(1)
            if channel is None:
                continue
            workers = [worker for worker in workers if worker.is_alive()]
            if len(workers) >= 4:
                channel.close()
                continue
            worker = threading.Thread(
                target=handle_channel,
                args=(channel, server, node, codex_js, events),
                daemon=True,
            )
            worker.start()
            workers.append(worker)
    except (EOFError, OSError, paramiko.SSHException) as error:
        events.write("connection_error", error=type(error).__name__, detail=str(error))
    finally:
        transport.close()
        client.close()
        events.write("connection_closed", peer=f"{peer[0]}:{peer[1]}")


def main() -> None:
    app_data = Path(os.environ.get("APPDATA", ""))
    default_codex_js = app_data / "npm" / "node_modules" / "@openai" / "codex" / "bin" / "codex.js"
    default_node = Path(shutil.which("node.exe") or shutil.which("node") or "node.exe")

    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=22223)
    parser.add_argument("--username", required=True)
    parser.add_argument(
        "--password-env",
        help="Environment variable containing the password; otherwise prompt securely",
    )
    parser.add_argument("--state-dir", type=Path, required=True)
    parser.add_argument("--node", type=Path, default=default_node)
    parser.add_argument("--codex-js", type=Path, default=default_codex_js)
    args = parser.parse_args()
    try:
        if not ipaddress.ip_address(args.host).is_loopback:
            parser.error("This development helper only accepts a loopback bind address")
    except ValueError:
        parser.error("--host must be a literal loopback IP address")
    if args.password_env:
        password = os.environ.get(args.password_env, "")
    else:
        password = getpass.getpass("SSH test password: ")
    if not args.username.strip() or len(password) < 16:
        parser.error("Supply a username and a password of at least 16 characters")
    os.umask(0o077)

    resolve_codex_process(args.node, args.codex_js, "--version")
    args.state_dir.mkdir(parents=True, exist_ok=True)
    events_path = args.state_dir / "events.jsonl"
    events_path.write_text("", encoding="utf-8")
    events_path.chmod(0o600)
    events = EventLog(events_path)
    host_key = load_or_create_host_key(args.state_dir / "host_rsa_key")

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    listener.bind((args.host, args.port))
    listener.listen(20)
    events.write(
        "ready",
        host=args.host,
        port=args.port,
        username=args.username,
        fingerprint=host_fingerprint(host_key),
        codex_js=str(args.codex_js),
    )
    slots = threading.BoundedSemaphore(4)

    def limited_client(client: socket.socket, peer: tuple[str, int]) -> None:
        try:
            handle_client(
                client, peer, host_key, args.username, password, args.node, args.codex_js, events
            )
        finally:
            slots.release()

    try:
        while True:
            client, peer = listener.accept()
            if not slots.acquire(blocking=False):
                client.close()
                continue
            threading.Thread(
                target=limited_client,
                args=(client, peer),
                daemon=True,
            ).start()
    except KeyboardInterrupt:
        pass
    finally:
        listener.close()


if __name__ == "__main__":
    main()
