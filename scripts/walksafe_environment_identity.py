#!/usr/bin/env python3
"""Derive non-secret environment identities and libpq-compatible PostgreSQL URLs."""

from __future__ import annotations

import argparse
import fcntl
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import stat
from urllib.parse import quote, unquote, urlsplit, urlunsplit


POSTGRES_SCHEMES = {"postgres", "postgresql"}
SECRET_QUERY_KEYS = {"password", "passfile", "sslpassword"}
AMBIGUOUS_TLS_QUERY_KEYS = {"ssl", "requiressl"}
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
POSTGRES_CLIENT_PATHS = {
    "pg_dump": "/usr/bin/pg_dump",
    "pg_restore": "/usr/bin/pg_restore",
    "psql": "/usr/bin/psql",
}
PG_DUMP_ARGUMENTS = ("--format=custom", "--no-owner", "--no-acl")
PG_RESTORE_ARGUMENTS = (
    "--exit-on-error",
    "--single-transaction",
    "--no-owner",
    "--no-acl",
)
PSQL_SERVER_VERSION_ARGUMENTS = (
    "--no-psqlrc",
    "--tuples-only",
    "--no-align",
    "--command",
    "SHOW server_version_num",
)
PSQL_EMPTY_TARGET_ARGUMENTS = (
    "--no-psqlrc",
    "--set",
    "ON_ERROR_STOP=1",
    "--tuples-only",
    "--no-align",
)
PSQL_REPORT_COUNT_ARGUMENTS = (
    "--no-psqlrc",
    "--set",
    "ON_ERROR_STOP=1",
    "--tuples-only",
    "--no-align",
    "--command",
    "SELECT count(*) FROM reports;",
)
PSQL_REPORT_IMAGES_QUERY = (
    "SELECT reports.id::text, COALESCE(objects.storage_name, ''), "
    "COALESCE(objects.envelope_sha256, ''), "
    "COALESCE(objects.envelope_size::text, '') FROM reports "
    "LEFT JOIN report_image_objects AS objects ON objects.report_id = reports.id "
    "ORDER BY reports.id;"
)
PSQL_REPORT_IMAGES_ARGUMENTS = (
    "--no-psqlrc",
    "--set",
    "ON_ERROR_STOP=1",
    "--tuples-only",
    "--no-align",
    "--field-separator=\t",
    "--csv",
    "--command",
    PSQL_REPORT_IMAGES_QUERY,
)
PSQL_ARGUMENT_PROFILES = frozenset(
    {
        PSQL_SERVER_VERSION_ARGUMENTS,
        PSQL_EMPTY_TARGET_ARGUMENTS,
        PSQL_REPORT_COUNT_ARGUMENTS,
        PSQL_REPORT_IMAGES_ARGUMENTS,
    }
)
POSTGRES_FD_PATH_PATTERN = re.compile(r"/proc/self/fd/[0-9]+")
INVALID_PERCENT_ESCAPE_PATTERN = re.compile(r"%(?![0-9A-Fa-f]{2})")
MAX_PSQL_STDIN_BYTES = 1024 * 1024
PSQL_EMPTY_TARGET_STDIN_SHA256 = (
    "cad6615ede8ce7e3291f0e8b6de2a21ed5fc8655f1ae547330f226566f8fa359"
)


def _strict_uri_unquote(value: str, *, context: str) -> str:
    if INVALID_PERCENT_ESCAPE_PATTERN.search(value):
        raise ValueError(f"{context} contains invalid percent encoding")
    try:
        decoded = unquote(value, encoding="utf-8", errors="strict")
    except UnicodeDecodeError as exc:
        raise ValueError(f"{context} is not valid UTF-8") from exc
    if "\x00" in decoded:
        raise ValueError(f"{context} contains a null byte")
    return decoded


def _postgresql_query_pairs(raw_query: str) -> list[tuple[str, str]]:
    if not raw_query:
        return []
    pairs: list[tuple[str, str]] = []
    for field in raw_query.split("&"):
        raw_key, separator, raw_value = field.partition("=")
        if not separator or not raw_key:
            raise ValueError("database URL query must use non-empty name=value fields")
        key = _strict_uri_unquote(raw_key, context="database URL query name")
        value = _strict_uri_unquote(raw_value, context="database URL query value")
        pairs.append((key, value))
    return pairs


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
    query = _postgresql_query_pairs(parts.query)
    query_names = [key.casefold() for key, _value in query]
    raw_hostname = parts.hostname or ""
    decoded_hostname = _strict_uri_unquote(raw_hostname, context="restore database URL host")
    username = _strict_uri_unquote(parts.username or "", context="restore database URL user")
    database = _strict_uri_unquote(
        parts.path.lstrip("/"), context="restore database URL database"
    )
    if (
        not username
        or not raw_hostname
        or port is None
        or not database
        or "%" in raw_hostname
        or decoded_hostname != raw_hostname
        or any(character in decoded_hostname for character in (",", "/", "\\"))
        or any(ord(character) < 0x21 or ord(character) == 0x7F for character in decoded_hostname)
        or parts.fragment
        or len(query_names) != len(set(query_names))
        or RESTORE_TARGET_SELECTOR_KEYS.intersection(query_names)
        or AMBIGUOUS_TLS_QUERY_KEYS.intersection(query_names)
        or SECRET_QUERY_KEYS.intersection(query_names)
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
    name = _strict_uri_unquote(parts.path.lstrip("/"), context="database URL database")
    if not name or "/" in name:
        raise ValueError("database URL must name exactly one PostgreSQL database")
    return name


def database_identity_sha256(database_url: str) -> str:
    parts = urlsplit(normalized_postgresql_url(database_url))
    query = [
        (key, value)
        for key, value in _postgresql_query_pairs(parts.query)
        if key.casefold() not in SECRET_QUERY_KEYS
    ]
    query.sort()
    identity = {
        "scheme": "postgresql",
        "username": _strict_uri_unquote(parts.username or "", context="database URL user"),
        "host": (parts.hostname or "").casefold(),
        "port": parts.port or 5432,
        "database": _strict_uri_unquote(
            parts.path.lstrip("/"), context="database URL database"
        ),
        "query": query,
    }
    canonical = json.dumps(identity, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()


def path_identity_sha256(path: Path) -> str:
    resolved = str(path.expanduser().resolve())
    return hashlib.sha256(resolved.encode("utf-8")).hexdigest()


def _validate_postgres_client_arguments(tool: str, arguments: list[str]) -> None:
    if any(not isinstance(argument, str) or "\x00" in argument for argument in arguments):
        raise ValueError("PostgreSQL client arguments contain an unsupported value")

    if tool == "pg_dump":
        if tuple(arguments) != PG_DUMP_ARGUMENTS:
            raise ValueError("pg_dump arguments are not allowed for the bound target")
        return

    if tool == "pg_restore":
        options = tuple(arguments[:-1])
        input_path = arguments[-1] if arguments else ""
        if (
            options != PG_RESTORE_ARGUMENTS
            or POSTGRES_FD_PATH_PATTERN.fullmatch(input_path) is None
        ):
            raise ValueError("pg_restore arguments are not allowed for the bound target")
        return

    if tool != "psql":
        raise ValueError("PostgreSQL client tool is not allowed")

    if tuple(arguments) not in PSQL_ARGUMENT_PROFILES:
        raise ValueError("psql arguments are not allowed for the bound target")


def _validated_psql_stdin(arguments: list[str], payload: bytes) -> bytes:
    if tuple(arguments) != PSQL_EMPTY_TARGET_ARGUMENTS:
        raise ValueError("PostgreSQL client stdin is not allowed for this operation")
    if not payload or len(payload) > MAX_PSQL_STDIN_BYTES:
        raise ValueError("psql stdin is empty or exceeds the operational limit")
    if b"\\" in payload or b"\x00" in payload:
        raise ValueError("psql stdin must not contain meta-commands or null bytes")
    if hashlib.sha256(payload).hexdigest() != PSQL_EMPTY_TARGET_STDIN_SHA256:
        raise ValueError("psql stdin does not match the pinned empty-target proof")
    return payload


def _replace_stdin_with_validated_sql(arguments: list[str]) -> None:
    chunks: list[bytes] = []
    size = 0
    while size <= MAX_PSQL_STDIN_BYTES:
        chunk = os.read(0, min(64 * 1024, MAX_PSQL_STDIN_BYTES + 1 - size))
        if not chunk:
            break
        chunks.append(chunk)
        size += len(chunk)
    payload = _validated_psql_stdin(arguments, b"".join(chunks))
    descriptor = os.memfd_create(
        "walksafe-psql-stdin", os.MFD_CLOEXEC | os.MFD_ALLOW_SEALING
    )
    try:
        offset = 0
        while offset < len(payload):
            offset += os.write(descriptor, payload[offset:])
        fcntl.fcntl(
            descriptor,
            fcntl.F_ADD_SEALS,
            fcntl.F_SEAL_SEAL
            | fcntl.F_SEAL_SHRINK
            | fcntl.F_SEAL_GROW
            | fcntl.F_SEAL_WRITE,
        )
        os.lseek(descriptor, 0, os.SEEK_SET)
        os.dup2(descriptor, 0, inheritable=True)
    finally:
        os.close(descriptor)


def postgres_client_invocation(
    database_url: str,
    *,
    tool: str,
    arguments: list[str],
) -> tuple[str, list[str], dict[str, str]]:
    normalized = explicit_restore_database_url(database_url)
    executable = POSTGRES_CLIENT_PATHS.get(tool)
    if executable is None:
        raise ValueError("PostgreSQL client tool is not allowed")
    _validate_postgres_client_arguments(tool, arguments)

    parts = urlsplit(normalized)
    authority = parts.netloc.rsplit("@", 1)[-1]
    username = _strict_uri_unquote(parts.username or "", context="database URL user")
    password = (
        _strict_uri_unquote(parts.password, context="database URL password")
        if parts.password is not None
        else None
    )

    passwordless_url = urlunsplit(
        (
            "postgresql",
            f"{quote(username, safe='')}@{authority}",
            parts.path,
            parts.query,
            "",
        )
    )
    environment = {"PATH": "/usr/bin:/bin"}
    if password is not None:
        environment["PGPASSWORD"] = password
    return (
        executable,
        [executable, f"--dbname={passwordless_url}", *arguments],
        environment,
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("normalize-database-url")
    subparsers.add_parser("validate-restore-database-url")
    subparsers.add_parser("validate-backup-database-url")
    restore_output = subparsers.add_parser("validate-private-restore-output")
    restore_output.add_argument("--path", required=True)
    subparsers.add_parser("database-name")
    subparsers.add_parser("database-identity")
    path_identity = subparsers.add_parser("path-identity")
    path_identity.add_argument("--path", type=Path, required=True)
    postgres_client = subparsers.add_parser("exec-postgres-client")
    postgres_client.add_argument("--tool", choices=sorted(POSTGRES_CLIENT_PATHS), required=True)
    postgres_client.add_argument("arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if args.command == "exec-postgres-client":
        arguments = list(args.arguments)
        if arguments[:1] == ["--"]:
            arguments = arguments[1:]
        executable, invocation, environment = postgres_client_invocation(
            os.environ.get("DATABASE_URL", ""),
            tool=args.tool,
            arguments=arguments,
        )
        if args.tool == "psql" and tuple(arguments) == PSQL_EMPTY_TARGET_ARGUMENTS:
            _replace_stdin_with_validated_sql(arguments)
        os.execve(executable, invocation, environment)
        raise AssertionError("PostgreSQL client exec unexpectedly returned")

    database_url = os.environ.get("DATABASE_URL", "")
    if args.command == "normalize-database-url":
        print(normalized_postgresql_url(database_url))
    elif args.command == "validate-restore-database-url":
        print(explicit_restore_database_url(database_url))
    elif args.command == "validate-backup-database-url":
        print(explicit_backup_database_url(database_url))
    elif args.command == "validate-private-restore-output":
        print(private_restore_output_path(args.path))
    elif args.command == "database-name":
        print(postgresql_database_name(database_url))
    elif args.command == "database-identity":
        print(database_identity_sha256(database_url))
    else:
        print(path_identity_sha256(args.path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
