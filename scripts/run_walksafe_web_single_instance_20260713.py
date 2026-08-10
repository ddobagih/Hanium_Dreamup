#!/usr/bin/env python3
"""Start the Web runtime under a private same-host single-instance lock."""

from __future__ import annotations

import fcntl
import hashlib
import os
from pathlib import Path
import socket
import stat
import sys
from typing import NoReturn


PROTECTED_ENVIRONMENTS = {"field", "staging", "production"}
LOOPBACK_NEXT_COMMANDS = {
    (
        "./node_modules/.bin/next",
        mode,
        "--hostname",
        "127.0.0.1",
        "--port",
        "3000",
    )
    for mode in ("dev", "start")
}


def fail(message: str) -> NoReturn:
    print(message, file=sys.stderr)
    raise SystemExit(2)


def protected_environment() -> bool:
    environment = os.environ.get("WALKSAFE_ENVIRONMENT", "").strip().lower()
    node_environment = os.environ.get("NODE_ENV", "").strip().lower()
    start_mode = os.environ.get("WALKSAFE_WEB_START_MODE", "").strip().lower()
    return environment in PROTECTED_ENVIRONMENTS or node_environment == "production" or start_mode == "production"


def require_loopback_web_command(command: list[str]) -> None:
    if tuple(command) not in LOOPBACK_NEXT_COMMANDS:
        fail("Legacy Web/BFF runtime commands must use the exact loopback Next dev/start contract")


def acquire_process_lock() -> tuple[int, int]:
    if os.environ.get("WALKSAFE_WEB_REPLICAS", "").strip() != "1":
        fail("WALKSAFE_WEB_REPLICAS must be exactly 1 in field, staging, and production")

    configured = os.environ.get("WALKSAFE_WEB_PROCESS_LOCK_PATH", "").strip()
    if not configured:
        fail("WALKSAFE_WEB_PROCESS_LOCK_PATH is required in field, staging, and production")
    lock_path = Path(configured)
    if not lock_path.is_absolute() or lock_path != lock_path.resolve(strict=False):
        fail("WALKSAFE_WEB_PROCESS_LOCK_PATH must be an absolute normalized path")

    parent = lock_path.parent
    try:
        parent_metadata = parent.lstat()
        resolved_parent = parent.resolve(strict=True)
    except OSError as error:
        fail(f"Web process lock parent is unavailable: {error}")
    if (
        not stat.S_ISDIR(parent_metadata.st_mode)
        or parent.is_symlink()
        or resolved_parent != parent
        or parent_metadata.st_uid != os.geteuid()
        or parent_metadata.st_mode & 0o077
    ):
        fail("Web process lock parent must be a private current-user-owned real directory")

    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(lock_path, flags, 0o600)
    except OSError as error:
        fail(f"Could not open Web process lock safely: {error}")
    try:
        metadata = os.fstat(descriptor)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != os.geteuid()
            or metadata.st_nlink != 1
            or metadata.st_mode & 0o077
        ):
            fail("Web process lock must be a private current-user-owned single-link regular file")
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            fail("Another WalkSafe Web instance already holds the configured process lock")
        namespace = "\0walksafe-web-" + hashlib.sha256(
            f"{os.geteuid()}:{lock_path}".encode("utf-8")
        ).hexdigest()
        namespace_socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            namespace_socket.bind(namespace)
        except OSError:
            namespace_socket.close()
            fail("Another WalkSafe Web instance already holds the configured process lock")
        namespace_socket.set_inheritable(True)
        os.set_inheritable(descriptor, True)
        return descriptor, namespace_socket.detach()
    except BaseException:
        os.close(descriptor)
        raise


def main() -> None:
    command = sys.argv[1:]
    if command[:1] == ["--"]:
        command = command[1:]
    if not command:
        fail("A Web runtime command is required after --")
    require_loopback_web_command(command)
    if protected_environment():
        acquire_process_lock()
    os.execvp(command[0], command)


if __name__ == "__main__":
    main()
