#!/usr/bin/env python3
"""Derive non-secret environment identities and libpq-compatible PostgreSQL URLs."""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
from urllib.parse import parse_qsl, unquote, urlsplit, urlunsplit


POSTGRES_SCHEMES = {"postgres", "postgresql"}
SECRET_QUERY_KEYS = {"password", "passfile", "sslpassword"}
RESTORE_TARGET_SELECTOR_KEYS = {
    "client_encoding",
    "database",
    "dbname",
    "host",
    "hostaddr",
    "options",
    "port",
    "service",
    "servicefile",
    "user",
}
RESTORE_OUTPUT_NAME_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}")


def normalized_postgresql_url(database_url: str) -> str:
    parts = urlsplit(database_url.strip())
    base_scheme = parts.scheme.split("+", 1)[0].lower()
    if base_scheme not in POSTGRES_SCHEMES:
        raise ValueError("database URL must use a PostgreSQL scheme")
    return urlunsplit(("postgresql", parts.netloc, parts.path, parts.query, parts.fragment))


def sqlalchemy_psycopg_url(database_url: str) -> str:
    normalized = urlsplit(normalized_postgresql_url(database_url))
    return urlunsplit(("postgresql+psycopg", normalized.netloc, normalized.path, normalized.query, ""))


def explicit_restore_database_url(database_url: str) -> str:
    normalized = normalized_postgresql_url(database_url)
    parts = urlsplit(normalized)
    try:
        port = parts.port
    except ValueError as exc:
        raise ValueError("restore database URL port is invalid") from exc
    query = parse_qsl(parts.query, keep_blank_values=True)
    query_names = [key.casefold() for key, _value in query]
    raw_hostname = parts.hostname or ""
    decoded_hostname = unquote(raw_hostname)
    if (
        not unquote(parts.username or "")
        or not raw_hostname
        or port is None
        or not unquote(parts.path.lstrip("/"))
        or "%" in raw_hostname
        or decoded_hostname != raw_hostname
        or any(character in decoded_hostname for character in (",", "/", "\\"))
        or any(ord(character) < 0x21 or ord(character) == 0x7F for character in decoded_hostname)
        or parts.fragment
        or len(query_names) != len(set(query_names))
        or RESTORE_TARGET_SELECTOR_KEYS.intersection(query_names)
    ):
        raise ValueError(
            "restore database URL must explicitly bind one user, host, port and database without selector overrides"
        )
    try:
        is_loopback = ipaddress.ip_address(decoded_hostname).is_loopback
    except ValueError:
        is_loopback = decoded_hostname.casefold() == "localhost"
    query_values = {key.casefold(): value.casefold() for key, value in query}
    if not is_loopback and (
        query_values.get("sslmode") != "verify-full"
        or query_values.get("gssencmode") != "disable"
    ):
        raise ValueError(
            "remote restore database URL must require sslmode=verify-full and gssencmode=disable"
        )
    return normalized


def explicit_backup_database_url(database_url: str) -> str:
    """Require the same single explicit PostgreSQL target used by restore drills."""
    return explicit_restore_database_url(database_url)


def private_restore_output_path(output_path: str, *, user_id: int | None = None) -> str:
    if not output_path or any(ord(character) < 0x20 or ord(character) == 0x7F for character in output_path):
        raise ValueError("restore output path contains unsupported control characters")
    candidate = Path(output_path)
    if (
        not candidate.is_absolute()
        or str(candidate) != output_path
        or RESTORE_OUTPUT_NAME_PATTERN.fullmatch(candidate.name) is None
    ):
        raise ValueError("restore output path must be an exact absolute path with a simple basename")
    parent = candidate.parent
    try:
        if parent.resolve(strict=True) != parent:
            raise ValueError("restore output parent must be a canonical real path")
    except OSError as exc:
        raise ValueError("restore output parent must already exist") from exc

    expected_user = os.getuid() if user_id is None else user_id
    for index, ancestor in enumerate((parent, *parent.parents)):
        try:
            metadata = os.lstat(ancestor)
        except OSError as exc:
            raise ValueError("restore output ancestry cannot be inspected") from exc
        mode = stat.S_IMODE(metadata.st_mode)
        if not stat.S_ISDIR(metadata.st_mode) or stat.S_ISLNK(metadata.st_mode):
            raise ValueError("restore output ancestry must contain only real directories")
        if metadata.st_uid not in {0, expected_user}:
            raise ValueError("restore output ancestry must be owned by root or the current user")
        if index == 0:
            if metadata.st_uid != expected_user or mode & 0o077:
                raise ValueError("restore output parent must be private and owned by the current user")
        elif mode & 0o022 and not mode & stat.S_ISVTX:
            raise ValueError("restore output ancestry must not be renameable by other users")
    return output_path


def postgresql_database_name(database_url: str) -> str:
    parts = urlsplit(normalized_postgresql_url(database_url))
    name = unquote(parts.path.lstrip("/"))
    if not name or "/" in name:
        raise ValueError("database URL must name exactly one PostgreSQL database")
    return name


def database_identity_sha256(database_url: str) -> str:
    parts = urlsplit(normalized_postgresql_url(database_url))
    query = [
        (key, value)
        for key, value in parse_qsl(parts.query, keep_blank_values=True)
        if key.casefold() not in SECRET_QUERY_KEYS
    ]
    query.sort()
    identity = {
        "scheme": "postgresql",
        "username": unquote(parts.username or ""),
        "host": (parts.hostname or "").casefold(),
        "port": parts.port or 5432,
        "database": unquote(parts.path.lstrip("/")),
        "query": query,
    }
    canonical = json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def path_identity_sha256(path: Path) -> str:
    resolved = str(path.expanduser().resolve())
    return hashlib.sha256(resolved.encode("utf-8")).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    normalize = subparsers.add_parser("normalize-database-url")
    normalize.add_argument("--database-url", required=True)
    restore_database = subparsers.add_parser("validate-restore-database-url")
    restore_database.add_argument("--database-url", required=True)
    backup_database = subparsers.add_parser("validate-backup-database-url")
    backup_database.add_argument("--database-url", required=True)
    restore_output = subparsers.add_parser("validate-private-restore-output")
    restore_output.add_argument("--path", required=True)
    database_name = subparsers.add_parser("database-name")
    database_name.add_argument("--database-url", required=True)
    database_identity = subparsers.add_parser("database-identity")
    database_identity.add_argument("--database-url", required=True)
    path_identity = subparsers.add_parser("path-identity")
    path_identity.add_argument("--path", type=Path, required=True)
    args = parser.parse_args()

    if args.command == "normalize-database-url":
        print(normalized_postgresql_url(args.database_url))
    elif args.command == "validate-restore-database-url":
        print(explicit_restore_database_url(args.database_url))
    elif args.command == "validate-backup-database-url":
        print(explicit_backup_database_url(args.database_url))
    elif args.command == "validate-private-restore-output":
        print(private_restore_output_path(args.path))
    elif args.command == "database-name":
        print(postgresql_database_name(args.database_url))
    elif args.command == "database-identity":
        print(database_identity_sha256(args.database_url))
    else:
        print(path_identity_sha256(args.path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
