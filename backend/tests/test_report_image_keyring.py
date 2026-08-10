from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import stat
from threading import Thread
from types import SimpleNamespace

import pytest

from backend.app.services.report_image_keys import (
    KEYRING_REQUEST_SCHEMA,
    KmsAgentReportImageKeyProvider,
    ReportImageKeyManager,
    ReportImageKeyUnavailable,
    ReportImageKeyringInvalid,
    RETIREMENT_BLOCKED_REASON,
    SecretFileReportImageKeyProvider,
    _assert_normal_terminal_keys_unused,
    _validate_rotation,
    parse_report_image_keyring,
    synchronize_report_image_keyring,
)


def _material(byte: int) -> str:
    return base64.urlsafe_b64encode(bytes([byte]) * 32).decode("ascii").rstrip("=")


def _raw_keyring(keys: list[dict[str, object]], *, generation: int = 1, previous: str | None = None) -> bytes:
    return json.dumps(
        {
            "generation": generation,
            "keys": keys,
            "previous_manifest_sha256": previous,
            "schema": "walksafe.report-image-keyring.v1",
        },
        separators=(",", ":"),
        sort_keys=True,
    ).encode()


def test_keyring_requires_one_active_aes256_key_and_omits_material_from_public_state() -> None:
    compromised_fingerprint = hashlib.sha256(bytes([3]) * 32).hexdigest()
    keyring = parse_report_image_keyring(
        _raw_keyring(
            [
                {"id": "key-v1", "material": _material(1), "state": "decrypt-only"},
                {"id": "key-v2", "material": _material(2), "state": "active"},
                {
                    "id": "key-v0",
                    "material_sha256": compromised_fingerprint,
                    "state": "compromised",
                },
            ]
        )
    )

    assert keyring.active_slot.key_id == "key-v2"
    assert all("material" not in state for state in keyring.public_states())
    assert keyring.slot("key-v0").material is None  # type: ignore[union-attr]


@pytest.mark.parametrize(
    "keys",
    [
        [{"id": "key-v1", "material": _material(1), "state": "decrypt-only"}],
        [
            {"id": "key-v1", "material": _material(1), "state": "active"},
            {"id": "key-v2", "material": _material(2), "state": "active"},
        ],
        [{"id": "key-v1", "material": _material(1)[:-1], "state": "active"}],
        [
            {"id": "key-v1", "material": _material(1), "state": "active"},
            {"id": "key-alias", "material": _material(1), "state": "decrypt-only"},
        ],
        [
            {
                "id": "key-v1",
                "material": _material(1),
                "state": "compromised",
            }
        ],
    ],
)
def test_keyring_rejects_invalid_state_material_and_aliases(keys: list[dict[str, object]]) -> None:
    with pytest.raises(ReportImageKeyringInvalid):
        parse_report_image_keyring(_raw_keyring(keys))


def test_rotation_allows_decrypt_only_and_compromise_but_never_reactivation() -> None:
    first = parse_report_image_keyring(
        _raw_keyring([{"id": "key-v1", "material": _material(1), "state": "active"}])
    )
    second = parse_report_image_keyring(
        _raw_keyring(
            [
                {"id": "key-v1", "material": _material(1), "state": "decrypt-only"},
                {"id": "key-v2", "material": _material(2), "state": "active"},
            ],
            generation=2,
            previous=first.manifest_sha256,
        )
    )
    _validate_rotation(first.public_states(), second)

    reactivated = parse_report_image_keyring(
        _raw_keyring(
            [
                {"id": "key-v1", "material": _material(1), "state": "active"},
                {"id": "key-v2", "material": _material(2), "state": "decrypt-only"},
            ],
            generation=3,
            previous=second.manifest_sha256,
        )
    )
    with pytest.raises(ReportImageKeyringInvalid, match="transition"):
        _validate_rotation(second.public_states(), reactivated)


def test_normal_key_retirement_and_destruction_are_blocked_without_backup_proof() -> None:
    active = parse_report_image_keyring(
        _raw_keyring([{"id": "key-v1", "material": _material(1), "state": "active"}])
    )
    decrypt_only = parse_report_image_keyring(
        _raw_keyring(
            [
                {"id": "key-v1", "material": _material(1), "state": "decrypt-only"},
                {"id": "key-v2", "material": _material(2), "state": "active"},
            ],
            generation=2,
            previous=active.manifest_sha256,
        )
    )
    retired = parse_report_image_keyring(
        _raw_keyring(
            [
                {
                    "id": "key-v1",
                    "material_sha256": hashlib.sha256(bytes([1]) * 32).hexdigest(),
                    "state": "retired",
                },
                {"id": "key-v2", "material": _material(2), "state": "active"},
            ],
            generation=3,
            previous=decrypt_only.manifest_sha256,
        )
    )
    destroyed = parse_report_image_keyring(
        _raw_keyring(
            [
                {
                    "id": "key-v1",
                    "material_sha256": hashlib.sha256(bytes([1]) * 32).hexdigest(),
                    "state": "destroyed",
                },
                {"id": "key-v2", "material": _material(2), "state": "active"},
            ],
            generation=4,
            previous=retired.manifest_sha256,
        )
    )

    _validate_rotation(active.public_states(), decrypt_only)
    with pytest.raises(ReportImageKeyringInvalid, match=RETIREMENT_BLOCKED_REASON):
        _validate_rotation(decrypt_only.public_states(), retired)
    with pytest.raises(ReportImageKeyringInvalid, match=RETIREMENT_BLOCKED_REASON):
        _validate_rotation(retired.public_states(), destroyed)
    assert retired.slot("key-v1").material is None  # type: ignore[union-attr]
    assert destroyed.slot("key-v1").material is None  # type: ignore[union-attr]

    compromised_instead = parse_report_image_keyring(
        _raw_keyring(
            [
                {
                    "id": "key-v1",
                    "material_sha256": hashlib.sha256(bytes([1]) * 32).hexdigest(),
                    "state": "compromised",
                },
                {"id": "key-v2", "material": _material(2), "state": "active"},
            ],
            generation=4,
            previous=retired.manifest_sha256,
        )
    )
    with pytest.raises(ReportImageKeyringInvalid, match="transition"):
        _validate_rotation(retired.public_states(), compromised_instead)


def test_normal_terminal_key_requires_zero_report_image_object_use() -> None:
    terminal = parse_report_image_keyring(
        _raw_keyring(
            [
                {
                    "id": "key-v1",
                    "material_sha256": hashlib.sha256(bytes([1]) * 32).hexdigest(),
                    "state": "retired",
                },
                {"id": "key-v2", "material": _material(2), "state": "active"},
            ]
        )
    )

    class ScalarResult:
        def __init__(self, values: list[str]) -> None:
            self._values = values

        def all(self) -> list[str]:
            return self._values

    class FakeSession:
        def __init__(self, values: list[str]) -> None:
            self._values = values

        def scalars(self, _statement) -> ScalarResult:
            return ScalarResult(self._values)

    _assert_normal_terminal_keys_unused(FakeSession([]), terminal)  # type: ignore[arg-type]
    with pytest.raises(ReportImageKeyringInvalid, match="objects still use"):
        _assert_normal_terminal_keys_unused(  # type: ignore[arg-type]
            FakeSession(["key-v1"]),
            terminal,
        )


def test_normal_retirement_is_not_appended_without_retained_backup_proof() -> None:
    previous = parse_report_image_keyring(
        _raw_keyring(
            [
                {"id": "key-v1", "material": _material(1), "state": "decrypt-only"},
                {"id": "key-v2", "material": _material(2), "state": "active"},
            ],
            generation=2,
            previous="0" * 64,
        )
    )
    retired = parse_report_image_keyring(
        _raw_keyring(
            [
                {
                    "id": "key-v1",
                    "material_sha256": hashlib.sha256(bytes([1]) * 32).hexdigest(),
                    "state": "retired",
                },
                {"id": "key-v2", "material": _material(2), "state": "active"},
            ],
            generation=3,
            previous=previous.manifest_sha256,
        )
    )
    latest = SimpleNamespace(
        generation=previous.generation,
        manifest_sha256=previous.manifest_sha256,
        key_states=previous.public_states(),
    )
    added: list[object] = []

    class QueryResult:
        def scalar_one_or_none(self):
            return latest

    class EmptyScalars:
        @staticmethod
        def all() -> list[str]:
            return []

    class FakeSession:
        def execute(self, _statement, _parameters=None):
            return QueryResult()

        def scalars(self, _statement):
            return EmptyScalars()

        def add(self, value) -> None:
            added.append(value)

        def flush(self) -> None:
            return None

    with pytest.raises(ReportImageKeyringInvalid, match=RETIREMENT_BLOCKED_REASON):
        synchronize_report_image_keyring(FakeSession(), retired)  # type: ignore[arg-type]

    assert added == []
    assert latest.generation == 2


def test_first_generation_cannot_introduce_normal_terminal_tombstones() -> None:
    retired = parse_report_image_keyring(
        _raw_keyring(
            [
                {
                    "id": "key-v0",
                    "material_sha256": hashlib.sha256(bytes([0]) * 32).hexdigest(),
                    "state": "retired",
                },
                {"id": "key-v1", "material": _material(1), "state": "active"},
            ]
        )
    )

    class QueryResult:
        @staticmethod
        def scalar_one_or_none():
            return None

    class EmptyScalars:
        @staticmethod
        def all() -> list[str]:
            return []

    class FakeSession:
        def execute(self, _statement, _parameters=None):
            return QueryResult()

        def scalars(self, _statement):
            return EmptyScalars()

    with pytest.raises(ReportImageKeyringInvalid, match=RETIREMENT_BLOCKED_REASON):
        synchronize_report_image_keyring(FakeSession(), retired)  # type: ignore[arg-type]


def test_manager_blocks_cached_key_immediately_when_external_manifest_changes() -> None:
    first = parse_report_image_keyring(
        _raw_keyring([{"id": "key-v1", "material": _material(1), "state": "active"}])
    )
    compromised = parse_report_image_keyring(
        _raw_keyring(
            [
                {
                    "id": "key-v1",
                    "material_sha256": hashlib.sha256(bytes([1]) * 32).hexdigest(),
                    "state": "compromised",
                },
                {"id": "key-v2", "material": _material(2), "state": "active"},
            ],
            generation=2,
            previous=first.manifest_sha256,
        )
    )

    class MutableProvider:
        keyring = first

        def load(self):
            return self.keyring

        def authority_proof(self):
            return {"provider": "test"}

    provider = MutableProvider()
    manager = ReportImageKeyManager(provider)
    assert manager.decryption_key("key-v1") == bytes([1]) * 32

    provider.keyring = compromised
    with pytest.raises(ReportImageKeyUnavailable, match="restart"):
        manager.decryption_key("key-v1")
    assert manager.readiness()["ready"] is False


def test_manager_never_decrypts_with_a_key_loaded_as_compromised() -> None:
    compromised_fingerprint = hashlib.sha256(bytes([1]) * 32).hexdigest()
    keyring = parse_report_image_keyring(
        _raw_keyring(
            [
                {
                    "id": "key-v1",
                    "material_sha256": compromised_fingerprint,
                    "state": "compromised",
                },
                {"id": "key-v2", "material": _material(2), "state": "active"},
            ]
        )
    )

    class FixedProvider:
        def load(self):
            return keyring

        def authority_proof(self):
            return {"provider": "test"}

    manager = ReportImageKeyManager(FixedProvider())
    with pytest.raises(ReportImageKeyUnavailable, match="unavailable"):
        manager.decryption_key("key-v1")


def test_secret_file_provider_requires_single_link_private_regular_file(tmp_path: Path) -> None:
    tmp_path.chmod(0o700)
    key_file = tmp_path / "keyring.json"
    key_file.write_bytes(
        _raw_keyring([{"id": "key-v1", "material": _material(1), "state": "active"}])
    )
    key_file.chmod(0o400)
    provider = SecretFileReportImageKeyProvider(key_file)

    assert provider.load().active_slot.key_id == "key-v1"
    assert provider.authority_proof() == {
        "ancestor_authority": "local_private_parent",
        "credential_boundary": "local_private_file",
        "endpoint_identity": "fd_path_matched_after_read",
        "provider": "secret_file",
    }

    key_file.chmod(0o644)
    with pytest.raises(ReportImageKeyringInvalid, match="private"):
        provider.load()

    key_file.chmod(0o400)
    hardlink = tmp_path / "hardlink.json"
    os.link(key_file, hardlink)
    with pytest.raises(ReportImageKeyringInvalid, match="private"):
        provider.load()


def test_secret_file_provider_rejects_symlink(tmp_path: Path) -> None:
    tmp_path.chmod(0o700)
    key_file = tmp_path / "real.json"
    key_file.write_bytes(
        _raw_keyring([{"id": "key-v1", "material": _material(1), "state": "active"}])
    )
    key_file.chmod(0o400)
    link = tmp_path / "linked.json"
    link.symlink_to(key_file)

    with pytest.raises(ReportImageKeyUnavailable):
        SecretFileReportImageKeyProvider(link).load()


def test_kms_agent_provider_checks_peer_and_exact_response_frame(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    socket_path = tmp_path / "kms-agent.sock"
    response = _raw_keyring(
        [{"id": "key-v1", "material": _material(1), "state": "active"}]
    )
    received: list[bytes] = []
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as server:
        server.bind(str(socket_path))
        server.listen(1)

        def serve() -> None:
            connection, _address = server.accept()
            with connection:
                received.append(connection.recv(4096))
                connection.sendall(response + b"\n")

        thread = Thread(target=serve)
        thread.start()
        peer_uid = os.geteuid()
        with monkeypatch.context() as constructor_context:
            constructor_context.setattr(os, "geteuid", lambda: peer_uid + 1)
            provider = KmsAgentReportImageKeyProvider(
                socket_path,
                expected_peer_uid=peer_uid,
                timeout_seconds=1,
            )
        loaded = provider.load()
        thread.join(timeout=2)

    assert not thread.is_alive()
    assert loaded.active_slot.key_id == "key-v1"
    assert received == [
        json.dumps(
            {"schema": KEYRING_REQUEST_SCHEMA},
            separators=(",", ":"),
            sort_keys=True,
        ).encode()
        + b"\n"
    ]
    assert provider.authority_proof()["credential_boundary"] == "separate_peer_uid_verified"
    assert provider.authority_proof()["endpoint_identity"] == "pinned_fd_matched_after_exchange"


def test_kms_agent_provider_rejects_unterminated_response(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    socket_path = tmp_path / "kms-agent.sock"
    response = _raw_keyring(
        [{"id": "key-v1", "material": _material(1), "state": "active"}]
    )
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as server:
        server.bind(str(socket_path))
        server.listen(1)

        def serve() -> None:
            connection, _address = server.accept()
            with connection:
                connection.recv(4096)
                connection.sendall(response)

        thread = Thread(target=serve)
        thread.start()
        peer_uid = os.geteuid()
        with monkeypatch.context() as constructor_context:
            constructor_context.setattr(os, "geteuid", lambda: peer_uid + 1)
            provider = KmsAgentReportImageKeyProvider(
                socket_path,
                expected_peer_uid=peer_uid,
                timeout_seconds=1,
            )
        with pytest.raises(ReportImageKeyringInvalid, match="framing"):
            provider.load()
        thread.join(timeout=2)

    assert not thread.is_alive()


def test_kms_agent_provider_rejects_same_uid_as_backend(tmp_path: Path) -> None:
    with pytest.raises(ReportImageKeyringInvalid, match="separate service uid"):
        KmsAgentReportImageKeyProvider(
            tmp_path / "kms-agent.sock",
            expected_peer_uid=os.geteuid(),
            timeout_seconds=1,
        )


def test_production_secret_file_requires_root_service_group_authority(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o700)
    key_file = tmp_path / "keyring.json"
    key_file.write_bytes(
        _raw_keyring([{"id": "key-v1", "material": _material(1), "state": "active"}])
    )
    key_file.chmod(0o440)
    provider = SecretFileReportImageKeyProvider(
        key_file,
        expected_service_gid=os.getegid(),
        require_root_authority=True,
    )

    with pytest.raises(ReportImageKeyringInvalid, match="root-owned"):
        provider.load()

    real_fstat = os.fstat
    real_stat = os.stat

    def root_authority(metadata: os.stat_result) -> os.stat_result:
        fields = list(metadata)
        fields[0] = (fields[0] & ~0o7777) | (0o755 if stat.S_ISDIR(fields[0]) else 0o440)
        fields[4] = 0
        fields[5] = os.getegid()
        return os.stat_result(fields)

    def authority_fstat(descriptor: int) -> os.stat_result:
        return root_authority(real_fstat(descriptor))

    def authority_stat(*args, **kwargs) -> os.stat_result:
        return root_authority(real_stat(*args, **kwargs))

    monkeypatch.setattr("backend.app.services.report_image_keys.os.fstat", authority_fstat)
    monkeypatch.setattr("backend.app.services.report_image_keys.os.stat", authority_stat)

    assert provider.load().active_slot.key_id == "key-v1"
    assert provider.authority_proof() == {
        "ancestor_authority": "root_owned_non_writable",
        "credential_boundary": "root_owned_service_group_readable",
        "endpoint_identity": "fd_path_matched_after_read",
        "provider": "secret_file",
    }
