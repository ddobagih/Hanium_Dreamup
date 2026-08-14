from __future__ import annotations

import base64
import os
from pathlib import Path
import stat

import pytest

from backend.app.config import (
    DEFAULT_ADMIN_CREDENTIAL_ISSUER_KEY_FILE,
    _parse_admin_credential_issuer_key_file,
)
from backend.app.services import admin_credential_issuer_key as issuer_key_module
from backend.app.services.admin_credential_issuer_key import (
    AdminCredentialIssuerKeyError,
    load_admin_credential_issuer_key,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
KEY_BYTES = bytes(range(32))
ENCODED_KEY = base64.urlsafe_b64encode(KEY_BYTES).rstrip(b"=")


def _write_private_key(path: Path, payload: bytes = ENCODED_KEY) -> None:
    path.parent.chmod(0o700)
    path.write_bytes(payload)
    path.chmod(0o400)


@pytest.mark.parametrize("suffix", [b"", b"\n"])
def test_private_file_accepts_only_canonical_256_bit_key(
    tmp_path: Path,
    suffix: bytes,
) -> None:
    key_file = tmp_path / "issuer.key"
    _write_private_key(key_file, ENCODED_KEY + suffix)

    loaded = load_admin_credential_issuer_key(
        key_file,
        require_root_authority=False,
    )

    assert loaded == ENCODED_KEY.decode("ascii")
    assert len(loaded) == 43


@pytest.mark.parametrize(
    "payload",
    [
        base64.urlsafe_b64encode(bytes(range(31))).rstrip(b"="),
        ENCODED_KEY + b"=",
        ENCODED_KEY + b"\n\n",
        ENCODED_KEY + b"\r\n",
        base64.urlsafe_b64encode(b"\xff" * 32).rstrip(b"=").replace(b"_", b"/"),
    ],
)
def test_private_file_rejects_noncanonical_or_wrong_length_keys(
    tmp_path: Path,
    payload: bytes,
) -> None:
    key_file = tmp_path / "issuer.key"
    _write_private_key(key_file, payload)

    with pytest.raises(AdminCredentialIssuerKeyError, match="issuer key is invalid"):
        load_admin_credential_issuer_key(
            key_file,
            require_root_authority=False,
        )


def test_private_file_rejects_unsafe_mode_hardlink_and_symlink_without_path_leak(
    tmp_path: Path,
) -> None:
    key_file = tmp_path / "issuer-sensitive-name.key"
    _write_private_key(key_file)

    key_file.chmod(0o644)
    with pytest.raises(AdminCredentialIssuerKeyError) as unsafe_mode:
        load_admin_credential_issuer_key(key_file, require_root_authority=False)
    assert str(key_file) not in str(unsafe_mode.value)
    assert ENCODED_KEY.decode("ascii") not in str(unsafe_mode.value)

    key_file.chmod(0o400)
    hardlink = tmp_path / "issuer-hardlink.key"
    os.link(key_file, hardlink)
    with pytest.raises(AdminCredentialIssuerKeyError):
        load_admin_credential_issuer_key(key_file, require_root_authority=False)
    hardlink.unlink()

    symlink = tmp_path / "issuer-symlink.key"
    symlink.symlink_to(key_file)
    with pytest.raises(AdminCredentialIssuerKeyError) as symlink_error:
        load_admin_credential_issuer_key(symlink, require_root_authority=False)
    assert str(symlink) not in str(symlink_error.value)


def test_private_file_rejects_public_service_owned_parent(tmp_path: Path) -> None:
    key_file = tmp_path / "issuer.key"
    _write_private_key(key_file)
    tmp_path.chmod(0o755)

    with pytest.raises(AdminCredentialIssuerKeyError, match="authority is invalid"):
        load_admin_credential_issuer_key(key_file, require_root_authority=False)


def test_private_file_rejects_fifo(tmp_path: Path) -> None:
    tmp_path.chmod(0o700)
    fifo = tmp_path / "issuer.pipe"
    os.mkfifo(fifo, mode=0o600)

    with pytest.raises(AdminCredentialIssuerKeyError):
        load_admin_credential_issuer_key(fifo, require_root_authority=False)


def test_private_file_detects_named_file_identity_change(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    key_file = tmp_path / "issuer.key"
    _write_private_key(key_file)
    real_stat = issuer_key_module.os.stat

    def changed_named_file(*args, **kwargs):
        metadata = real_stat(*args, **kwargs)
        if args and args[0] == key_file.name and kwargs.get("dir_fd") is not None:
            fields = list(metadata)
            fields[1] += 1
            return os.stat_result(fields)
        return metadata

    monkeypatch.setattr(issuer_key_module.os, "stat", changed_named_file)

    with pytest.raises(AdminCredentialIssuerKeyError, match="identity changed"):
        load_admin_credential_issuer_key(key_file, require_root_authority=False)


def test_production_file_requires_root_service_group_authority(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    key_file = tmp_path / "issuer.key"
    _write_private_key(key_file)
    real_fstat = issuer_key_module.os.fstat
    real_stat = issuer_key_module.os.stat

    def root_authority(metadata: os.stat_result) -> os.stat_result:
        fields = list(metadata)
        mode = 0o755 if stat.S_ISDIR(fields[0]) else 0o440
        fields[0] = (fields[0] & ~0o7777) | mode
        fields[4] = 0
        fields[5] = os.getegid()
        return os.stat_result(fields)

    monkeypatch.setattr(
        issuer_key_module.os,
        "fstat",
        lambda descriptor: root_authority(real_fstat(descriptor)),
    )
    monkeypatch.setattr(
        issuer_key_module.os,
        "stat",
        lambda *args, **kwargs: root_authority(real_stat(*args, **kwargs)),
    )

    assert load_admin_credential_issuer_key(
        key_file,
        require_root_authority=True,
        expected_service_gid=os.getegid(),
    ) == ENCODED_KEY.decode("ascii")


def test_production_file_rejects_local_user_authority(tmp_path: Path) -> None:
    key_file = tmp_path / "issuer.key"
    _write_private_key(key_file)

    with pytest.raises(AdminCredentialIssuerKeyError, match="authority is invalid"):
        load_admin_credential_issuer_key(
            key_file,
            require_root_authority=True,
            expected_service_gid=os.getegid(),
        )


def test_production_file_rejects_mode_0640(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    key_file = tmp_path / "issuer.key"
    _write_private_key(key_file)
    key_file.chmod(0o640)
    real_fstat = issuer_key_module.os.fstat
    real_stat = issuer_key_module.os.stat

    def root_owned(metadata: os.stat_result) -> os.stat_result:
        fields = list(metadata)
        fields[4] = 0
        fields[5] = os.getegid()
        return os.stat_result(fields)

    monkeypatch.setattr(
        issuer_key_module.os,
        "fstat",
        lambda descriptor: root_owned(real_fstat(descriptor)),
    )
    monkeypatch.setattr(
        issuer_key_module.os,
        "stat",
        lambda *args, **kwargs: root_owned(real_stat(*args, **kwargs)),
    )

    with pytest.raises(AdminCredentialIssuerKeyError, match="authority is invalid"):
        load_admin_credential_issuer_key(
            key_file,
            require_root_authority=True,
            expected_service_gid=os.getegid(),
        )


def test_issuer_key_path_has_production_default_and_no_relative_override() -> None:
    assert (
        _parse_admin_credential_issuer_key_file("", "production")
        == DEFAULT_ADMIN_CREDENTIAL_ISSUER_KEY_FILE
    )
    assert _parse_admin_credential_issuer_key_file("", "test") is None
    custom = Path("/var/lib/walksafe-test/issuer.key")
    assert (
        _parse_admin_credential_issuer_key_file(str(custom), "test") == custom
    )
    with pytest.raises(ValueError, match="normalized absolute path"):
        _parse_admin_credential_issuer_key_file("relative/issuer.key", "production")


def test_database_engine_hides_bound_parameter_values() -> None:
    from backend.app.database import engine

    assert engine.hide_parameters is True


def test_deployment_examples_expose_only_a_read_only_key_file_path() -> None:
    environment = (
        REPOSITORY_ROOT / "deploy/config/walksafe-backend.env.example"
    ).read_text(encoding="utf-8")
    service = (
        REPOSITORY_ROOT / "deploy/systemd/walksafe-backend.service"
    ).read_text(encoding="utf-8")

    assert (
        "WALKSAFE_ADMIN_CREDENTIAL_ISSUER_KEY_FILE="
        "/etc/walksafe/admin-credential-issuer.key"
    ) in environment
    assert "WALKSAFE_ADMIN_CREDENTIAL_ISSUER_KEY=" not in environment
    assert (
        "ReadOnlyPaths=/etc/walksafe/report-image-keyring.json "
        "/etc/walksafe/admin-credential-issuer.key"
    ) in service
