from __future__ import annotations

import os
from pathlib import Path

import pytest

from scripts.walksafe_environment_identity import (
    _validated_psql_stdin,
    database_identity_sha256,
    explicit_backup_database_url,
    normalized_postgresql_url,
    path_identity_sha256,
    postgres_client_invocation,
    postgresql_database_name,
    private_restore_output_path,
    sqlalchemy_psycopg_url,
)


@pytest.mark.parametrize(
    "database_url",
    [
        "postgresql:///walksafe",
        "postgresql://walksafe@127.0.0.1/walksafe",
        "postgresql://127.0.0.1:5432/walksafe",
        "postgresql://walksafe@127.0.0.1:5432/walksafe?service=attacker",
        "postgresql://walksafe@127.0.0.1:5432/walksafe?hostaddr=203.0.113.1",
        "postgresql://walksafe@db.example,127.0.0.1:5432/walksafe?sslmode=verify-full&gssencmode=disable",
        "postgresql://walksafe@db.example%2C127.0.0.1:5432/walksafe?sslmode=verify-full&gssencmode=disable",
        "postgresql://walksafe@%2Ftmp:5432/walksafe?sslmode=verify-full&gssencmode=disable",
    ],
)
def test_backup_database_url_requires_one_explicit_target(database_url: str) -> None:
    with pytest.raises(ValueError, match="explicitly bind"):
        explicit_backup_database_url(database_url)


def test_backup_database_url_accepts_explicit_local_and_verified_remote_targets() -> None:
    local = "postgresql://walksafe@127.0.0.1:5432/walksafe?sslmode=disable"
    remote = (
        "postgresql://walksafe@db.example:5432/walksafe"
        "?sslmode=verify-full&gssencmode=disable"
    )

    assert explicit_backup_database_url(local) == local
    assert explicit_backup_database_url(remote) == remote


def test_backup_database_url_rejects_remote_tls_downgrade() -> None:
    with pytest.raises(ValueError, match="sslmode=verify-full"):
        explicit_backup_database_url(
            "postgresql://walksafe@db.example:5432/walksafe?sslmode=require"
        )


@pytest.mark.parametrize(
    "query",
    [
        "sslmode=verify-full&gssencmode=disable&ssl=true",
        "ssl=true&gssencmode=disable&sslmode=verify-full",
    ],
)
def test_backup_database_url_rejects_ambiguous_ssl_alias_in_any_order(query: str) -> None:
    with pytest.raises(ValueError, match="selector overrides"):
        explicit_backup_database_url(
            f"postgresql://walksafe@db.example:5432/walksafe?{query}"
        )


@pytest.mark.parametrize("query_key", ["password", "passfile", "sslpassword"])
def test_backup_database_url_rejects_secret_query_parameters(query_key: str) -> None:
    with pytest.raises(ValueError, match="selector overrides"):
        explicit_backup_database_url(
            "postgresql://walksafe@127.0.0.1:5432/walksafe"
            f"?sslmode=disable&{query_key}=p+ss"
        )


def test_sqlalchemy_postgresql_driver_is_removed_for_pg_tools() -> None:
    sqlalchemy_url = "postgresql+psycopg://walksafe:secret@db.example:5432/walksafe?sslmode=require"

    assert normalized_postgresql_url(sqlalchemy_url) == (
        "postgresql://walksafe:secret@db.example:5432/walksafe?sslmode=require"
    )


def test_plain_postgresql_url_is_bound_to_the_installed_sqlalchemy_driver() -> None:
    plain_url = "postgresql://walksafe:secret@db.example:5432/walksafe?sslmode=require"

    assert sqlalchemy_psycopg_url(plain_url) == (
        "postgresql+psycopg://walksafe:secret@db.example:5432/walksafe?sslmode=require"
    )


def test_postgresql_database_name_decodes_one_explicit_name() -> None:
    assert postgresql_database_name("postgresql://user@db/walksafe%5Fgoal%5Ftest") == "walksafe_goal_test"

    with pytest.raises(ValueError, match="exactly one"):
        postgresql_database_name("postgresql://user@db/")


def test_database_identity_excludes_password_but_tracks_database() -> None:
    first = database_identity_sha256("postgresql+psycopg://walksafe:first@db.example/walksafe")
    rotated = database_identity_sha256("postgresql://walksafe:second@db.example:5432/walksafe")
    other_database = database_identity_sha256("postgresql://walksafe:second@db.example:5432/other")

    assert first == rotated
    assert first != other_database


def test_database_identity_preserves_rfc3986_plus_and_space_semantics() -> None:
    encoded_space = database_identity_sha256(
        "postgresql://walksafe@db.example:5432/walksafe?application_name=walk%20safe"
    )
    literal_plus = database_identity_sha256(
        "postgresql://walksafe@db.example:5432/walksafe?application_name=walk+safe"
    )

    assert encoded_space != literal_plus


def test_postgres_client_keeps_password_out_of_process_arguments() -> None:
    executable, invocation, environment = postgres_client_invocation(
        "postgresql://walksafe:p%40ss@127.0.0.1:55432/walksafe_test"
        "?sslmode=disable&application_name=walk%20safe+backup",
        tool="pg_dump",
        arguments=["--format=custom", "--no-owner", "--no-acl"],
    )

    assert executable == "/usr/bin/pg_dump"
    assert invocation[0] == executable
    assert invocation[1] == (
        "--dbname=postgresql://walksafe@127.0.0.1:55432/walksafe_test"
        "?sslmode=disable&application_name=walk%20safe+backup"
    )
    assert "p@ss" not in " ".join(invocation)
    assert environment == {
        "PATH": "/usr/bin:/bin",
        "PGPASSWORD": "p@ss",
    }


@pytest.mark.parametrize(
    ("tool", "arguments"),
    [
        ("psql", ["-dpostgresql://attacker@127.0.0.1/other"]),
        ("psql", ["-hattacker.example"]),
        ("psql", ["-p1"]),
        ("psql", ["-Uattacker"]),
        ("psql", ["--db=postgresql://attacker@127.0.0.1/other"]),
        ("psql", ["other_database"]),
        ("psql", []),
        ("psql", ["--no-psqlrc", "--command", "SELECT 1;"]),
        ("pg_dump", []),
        ("pg_dump", ["--format=custom", "--no-owner"]),
        ("pg_dump", ["--dbname=postgresql://attacker@127.0.0.1/other"]),
        ("pg_restore", ["/proc/self/fd/7"]),
        ("pg_restore", ["--create", "/proc/self/fd/7"]),
    ],
)
def test_postgres_client_rejects_connection_or_behavior_overrides(
    tool: str, arguments: list[str]
) -> None:
    with pytest.raises(ValueError, match="not allowed for the bound target"):
        postgres_client_invocation(
            "postgresql://walksafe@127.0.0.1:55432/walksafe_test",
            tool=tool,
            arguments=arguments,
        )


def test_postgres_client_accepts_only_the_operational_psql_and_restore_grammar() -> None:
    _executable, psql_invocation, _environment = postgres_client_invocation(
        "postgresql://walksafe@127.0.0.1:55432/walksafe_test?sslmode=disable",
        tool="psql",
        arguments=[
            "--no-psqlrc",
            "--tuples-only",
            "--no-align",
            "--command",
            "SHOW server_version_num",
        ],
    )
    _executable, restore_invocation, _environment = postgres_client_invocation(
        "postgresql://walksafe@127.0.0.1:55432/walksafe_test?sslmode=disable",
        tool="pg_restore",
        arguments=[
            "--exit-on-error",
            "--single-transaction",
            "--no-owner",
            "--no-acl",
            "/proc/self/fd/7",
        ],
    )

    assert psql_invocation[-2:] == ["--command", "SHOW server_version_num"]
    assert restore_invocation[-1] == "/proc/self/fd/7"


def test_postgres_client_stdin_rejects_psql_meta_commands() -> None:
    arguments = [
        "--no-psqlrc",
        "--set",
        "ON_ERROR_STOP=1",
        "--tuples-only",
        "--no-align",
    ]

    script = (
        Path(__file__).parents[1] / "scripts" / "restore_walksafe_backup_drill_20260711.sh"
    ).read_text(encoding="utf-8")
    after_marker = script.split("<<'SQL'\n", 1)[1]
    payload = after_marker.split("\nSQL\n", 1)[0].encode("utf-8") + b"\n"

    assert _validated_psql_stdin(arguments, payload) == payload
    with pytest.raises(ValueError, match="pinned empty-target proof"):
        _validated_psql_stdin(arguments, b"SELECT 1;\n")
    with pytest.raises(ValueError, match="meta-commands"):
        _validated_psql_stdin(arguments, b"SELECT 1;\n\\connect attacker\n")


def test_identity_rejects_non_postgresql_url() -> None:
    with pytest.raises(ValueError, match="PostgreSQL"):
        database_identity_sha256("sqlite:///walksafe.db")


def test_path_identity_uses_the_resolved_path(tmp_path: Path) -> None:
    assert path_identity_sha256(tmp_path / "logs" / ".." / "uploads") == path_identity_sha256(tmp_path / "uploads")


def test_restore_output_path_requires_private_canonical_ancestry(tmp_path: Path) -> None:
    private_parent = tmp_path / "private"
    private_parent.mkdir(mode=0o700)
    output = private_parent / "restore.json"
    assert private_restore_output_path(str(output)) == str(output)

    shared = tmp_path / "shared"
    shared.mkdir(mode=0o700)
    nested = shared / "private"
    nested.mkdir(mode=0o700)
    shared.chmod(0o777)
    try:
        with pytest.raises(ValueError, match="renameable"):
            private_restore_output_path(str(nested / "restore.json"))
    finally:
        shared.chmod(0o700)


def test_restore_output_path_rejects_symlinked_or_ambiguous_path(tmp_path: Path) -> None:
    private_parent = tmp_path / "private"
    private_parent.mkdir(mode=0o700)
    alias = tmp_path / "alias"
    alias.symlink_to(private_parent, target_is_directory=True)

    with pytest.raises(ValueError, match="canonical"):
        private_restore_output_path(str(alias / "restore.json"))
    with pytest.raises(ValueError, match="simple basename"):
        private_restore_output_path(str(private_parent / "restore receipt.json"))


def test_pinned_private_parent_fd_is_not_redirected_by_path_replacement(tmp_path: Path) -> None:
    parent = tmp_path / "private"
    parent.mkdir(mode=0o700)
    descriptor = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
    displaced = tmp_path / "displaced"
    replacement = tmp_path / "replacement"
    try:
        parent.rename(displaced)
        replacement.mkdir(mode=0o700)
        replacement.rename(parent)
        anchored_output = Path(f"/proc/self/fd/{descriptor}") / "receipt.json"
        anchored_output.write_text("pinned", encoding="utf-8")
        assert (displaced / "receipt.json").read_text(encoding="utf-8") == "pinned"
        assert not (parent / "receipt.json").exists()
    finally:
        os.close(descriptor)
