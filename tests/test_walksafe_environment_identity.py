from __future__ import annotations

import os
from pathlib import Path

import pytest

from scripts.walksafe_environment_identity import (
    database_identity_sha256,
    explicit_backup_database_url,
    normalized_postgresql_url,
    path_identity_sha256,
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
