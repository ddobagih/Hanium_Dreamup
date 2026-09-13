#!/usr/bin/env python3
"""Configure external SMTP in a private NUL-delimited env file, without network I/O."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import getpass
import ipaddress
import os
from pathlib import Path
import re
import stat
import sys
import tempfile
import warnings


SMTP_PREFIX = "WALKSAFE_ACCOUNT_SMTP_"
SMTP_FIELDS = ("HOST", "PORT", "SECURITY", "FROM", "USERNAME", "PASSWORD")
RESERVED_DOMAINS = ("localhost", "invalid", "test", "example", "example.com", "example.net", "example.org")
CAPTURE_NAMES = {"mailpit", "mailhog", "maildev", "smtp4dev"}
DOMAIN_LABEL = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
EMAIL_LOCAL = re.compile(r"[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*")


def read_private_file(path: Path) -> bytes:
    if not path.is_absolute() or path.parent.resolve() != path.parent:
        raise ValueError("Use an absolute path without symlink directories.")
    if path.is_relative_to(Path(__file__).resolve().parents[1]):
        raise ValueError("Keep private SMTP files outside the project repository.")
    parent = path.parent.stat()
    if parent.st_uid != os.getuid() or parent.st_mode & 0o022:
        raise ValueError("The private directory must be owned by this user and not writable by others.")
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
    with os.fdopen(descriptor, "rb") as stream:
        info = os.fstat(stream.fileno())
        if (
            not stat.S_ISREG(info.st_mode)
            or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != 0o600
            or info.st_nlink != 1
        ):
            raise ValueError("Private files must be regular, user-owned, mode 0600, with one hard link.")
        content = stream.read(8 * 1024 * 1024 + 1)
        if len(content) > 8 * 1024 * 1024:
            raise ValueError("The private configuration file is too large.")
        return content


def env_entries(content: bytes) -> tuple[list[tuple[bytes, bytes]], bool]:
    if b"\0" not in content:
        raise ValueError("The target must be NUL-delimited, not a line-based .env file.")
    terminated = content.endswith(b"\0")
    parts = content.split(b"\0")
    if terminated:
        parts.pop()
    entries: list[tuple[bytes, bytes]] = []
    names: set[bytes] = set()
    for part in parts:
        key, separator, value = part.partition(b"=")
        if not separator or re.fullmatch(rb"[A-Za-z_][A-Za-z0-9_]*", key) is None:
            raise ValueError("The target must be a valid NUL-delimited KEY=value file.")
        if key in names:
            raise ValueError("The target contains duplicate environment keys.")
        names.add(key)
        entries.append((key, value))
    return entries, terminated


def is_reserved_domain(domain: str) -> bool:
    return any(domain == item or domain.endswith("." + item) for item in RESERVED_DOMAINS)


def validate_host(host: str) -> None:
    host = host.lower()
    if not host or not host.isascii() or len(host) > 253 or is_reserved_domain(host):
        raise ValueError("Use a real SMTP host, not a reserved or local capture address.")
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        labels = host.split(".")
        if any(DOMAIN_LABEL.fullmatch(label) is None for label in labels):
            raise ValueError("SMTP host must be a hostname or an IP address.") from None
        if all(label.isdecimal() for label in labels):
            raise ValueError("Use the full canonical IP address instead of an abbreviated numeric host.")
        if any(label in CAPTURE_NAMES for label in labels):
            raise ValueError("A local mail capture service cannot be used for external delivery.")
    else:
        address = getattr(address, "ipv4_mapped", None) or address
        if address.is_loopback or address.is_unspecified or address.is_multicast:
            raise ValueError("Loopback, unspecified, and multicast SMTP addresses are not allowed.")


def validate_sender(sender: str) -> None:
    if not sender.isascii() or not 3 <= len(sender) <= 254 or sender.count("@") != 1:
        raise ValueError("The sender must be one canonical ASCII email address.")
    local, domain = sender.rsplit("@", 1)
    labels = domain.split(".")
    if (
        len(local) > 64
        or EMAIL_LOCAL.fullmatch(local) is None
        or domain != domain.lower()
        or len(labels) < 2
        or any(
            DOMAIN_LABEL.fullmatch(label) is None
            or (label[2:4] == "--" and not label.startswith("xn--"))
            for label in labels
        )
        or is_reserved_domain(domain)
    ):
        raise ValueError("The sender must use an authorized real domain and canonical ASCII form.")


def validate_smtp(values: dict[str, str]) -> None:
    for value in values.values():
        if value != value.strip() or any(ord(char) < 32 or ord(char) == 127 for char in value):
            raise ValueError("SMTP values cannot contain control characters or surrounding whitespace.")
    validate_host(values["HOST"])
    validate_sender(values["FROM"])
    if re.fullmatch(r"[0-9]{1,5}", values["PORT"]) is None:
        raise ValueError("SMTP port must be a decimal number from 1 to 65535.")
    port = int(values["PORT"])
    if not 1 <= port <= 65535 or port in {1025, 8025, 1080}:
        raise ValueError("Use the provider SMTP port, not a common mail capture port.")
    if values["SECURITY"] not in {"implicit_tls", "starttls"}:
        raise ValueError("SMTP security must be implicit_tls or starttls.")
    if bool(values["USERNAME"]) != bool(values["PASSWORD"]):
        raise ValueError("SMTP username and password must both be set, or both be empty.")


def hidden_input(prompt: str) -> str:
    with warnings.catch_warnings():
        warnings.simplefilter("error", getpass.GetPassWarning)
        return getpass.getpass(prompt)


def collect_smtp(preset: str | None, identity_file: Path | None) -> dict[str, str]:
    if not sys.stdin.isatty() or not sys.stderr.isatty():
        raise ValueError("Run this command directly in a local terminal; redirected input is not accepted.")
    if identity_file is not None and preset != "kakao":
        raise ValueError("--identity-file requires --preset kakao.")
    if preset == "kakao":
        if identity_file is None:
            sender = hidden_input("Kakao sender address (hidden): ")
        else:
            sender = read_private_file(identity_file).decode("ascii").removesuffix("\n")
        validate_sender(sender)
        if sender.rsplit("@", 1)[1] != "kakao.com":
            raise ValueError("The Kakao preset requires a kakao.com sender address.")
        # The provider calls this the Kakao Mail ID; allow an explicit ID override.
        username = hidden_input("Kakao Mail ID (hidden; Enter uses sender ID before @): ")
        values = {
            "HOST": "smtp.kakao.com",
            "PORT": "465",
            "SECURITY": "implicit_tls",
            "FROM": sender,
            "USERNAME": username or sender.rsplit("@", 1)[0],
            "PASSWORD": hidden_input("Kakao app password (hidden): "),
        }
    else:
        values = {field: hidden_input(f"SMTP {field} (hidden): ") for field in SMTP_FIELDS}
    validate_smtp(values)
    if values["PASSWORD"] != hidden_input("Repeat SMTP app password (hidden; empty if unauthenticated): "):
        raise ValueError("The two password entries did not match.")
    return values


def replace_smtp(content: bytes, values: dict[str, str]) -> bytes:
    entries, terminated = env_entries(content)
    changes = {(SMTP_PREFIX + key).encode("ascii"): value.encode("utf-8") for key, value in values.items()}
    output = []
    for key, value in entries:
        output.append(key + b"=" + changes.pop(key, value))
    output.extend(key + b"=" + value for key, value in changes.items())
    return b"\0".join(output) + (b"\0" if terminated else b"")


def write_private_copy(parent: Path, prefix: str, content: bytes) -> Path:
    descriptor, name = tempfile.mkstemp(prefix=prefix, dir=parent)
    path = Path(name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            os.fchmod(stream.fileno(), 0o600)
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
    except BaseException:
        path.unlink(missing_ok=True)
        raise
    return path


@contextmanager
def smtp_update_lock(path: Path):
    # Keep a stable inode: the env file is replaced, while this lock is never removed.
    lock_path = path.with_name(path.name + ".smtp.lock")
    descriptor = os.open(
        lock_path,
        os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_CLOEXEC,
        0o600,
    )
    try:
        info = os.fstat(descriptor)
        if (
            not stat.S_ISREG(info.st_mode)
            or info.st_uid != os.getuid()
            or stat.S_IMODE(info.st_mode) != 0o600
            or info.st_nlink != 1
        ):
            raise ValueError("The SMTP lock must be a private, user-owned regular file.")
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError("Another SMTP configuration update is running; retry after it finishes.") from None
        yield
    finally:
        os.close(descriptor)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env0", type=Path, required=True, help="Existing private NUL-delimited runtime env file")
    parser.add_argument("--preset", choices=("kakao",))
    parser.add_argument("--identity-file", type=Path, help="Private 0600 file containing only the Kakao sender address")
    parser.add_argument("--dry-run", action="store_true", help="Validate local file and input shape only; do not write")
    args = parser.parse_args()
    temporary: Path | None = None
    applied = False
    try:
        original = read_private_file(args.env0)
        env_entries(original)
        values = collect_smtp(args.preset, args.identity_file)
        updated = replace_smtp(original, values)
        if args.dry_run:
            print("Local input shape is valid. No files changed; no network connection or mail sent.")
            return 0
        with smtp_update_lock(args.env0):
            if read_private_file(args.env0) != original:
                raise ValueError("The target changed during input. No settings were overwritten; retry with current data.")
            if updated == original:
                print("SMTP settings are unchanged. No network connection or mail sent.")
                return 0
            temporary = write_private_copy(args.env0.parent, ".smtp-pending-", updated)
            backup = write_private_copy(args.env0.parent, args.env0.name + ".smtp-backup-", original)
            if read_private_file(args.env0) != original:
                raise ValueError("The target changed during backup. No settings were overwritten; retry with current data.")
            os.replace(temporary, args.env0)
            temporary = None
            applied = True
            directory = os.open(args.env0.parent, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)
        print("Saved SMTP settings as 0600; all other environment values were preserved.")
        print(f"Private backup: {backup}")
        print("Restart the backend to apply. No network connection or mail sent.")
        return 0
    except (OSError, UnicodeError):
        if applied:
            print("SMTP settings were replaced, but directory sync failed. Check the private file before restarting.", file=sys.stderr)
        else:
            print("Configuration not completed: private file access or encoding failed. No credential details logged.", file=sys.stderr)
        return 2
    except (ValueError, getpass.GetPassWarning) as exc:
        print(f"Configuration not completed: {exc}", file=sys.stderr)
        return 2
    except (KeyboardInterrupt, EOFError):
        message = (
            "\nSMTP settings were replaced before interruption; check the private file before restarting."
            if applied
            else "\nConfiguration cancelled; input was not saved."
        )
        print(message, file=sys.stderr)
        return 2
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


if __name__ == "__main__":
    raise SystemExit(main())
