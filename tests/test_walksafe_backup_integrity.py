from __future__ import annotations

from contextlib import contextmanager
from copy import deepcopy
from datetime import UTC, datetime, timedelta
import fcntl
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest

import scripts.walksafe_backup_integrity as integrity
from scripts.walksafe_environment_identity import explicit_restore_database_url


TRUSTED_SIGNER = "a" * 40
CONTROL_SIGNER = "e" * 40
VALIDATION_SIGNER = "d" * 40
CONTROL_SHA256 = "f" * 64
PREDECESSOR_VALIDATION_SHA256 = "8" * 64
AUTHORITY_LOCK_SHA256 = "9" * 64
BACKUP_KEY_ID = "backup-key-1"
ROOT = Path(__file__).resolve().parents[1]
_REAL_ROOT_AUTHORITY_POLICY = integrity._require_root_owned_authority_ancestry


@pytest.fixture(autouse=True)
def _allow_temporary_authority_roots(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        integrity,
        "_require_root_owned_authority_ancestry",
        integrity._authority_ancestry,
    )


def _restore_embedded_python(containing: str) -> str:
    script = (ROOT / "scripts" / "restore_walksafe_backup_drill_20260711.sh").read_text(encoding="utf-8")
    for chunk in script.split("<<'PY'\n")[1:]:
        code, separator, _remainder = chunk.partition("\nPY\n")
        if separator and containing in code:
            return code
    raise AssertionError(f"embedded restore Python was not found: {containing}")


def _backup_embedded_python(containing: str) -> str:
    script = (ROOT / "scripts" / "backup_walksafe_data_20260711.sh").read_text(encoding="utf-8")
    for chunk in script.split("<<'PY'\n")[1:]:
        code, separator, _remainder = chunk.partition("\nPY\n")
        if separator and containing in code:
            return code
    raise AssertionError(f"embedded backup Python was not found: {containing}")


def _restore_shell_function(name: str) -> str:
    script = (ROOT / "scripts" / "restore_walksafe_backup_drill_20260711.sh").read_text(
        encoding="utf-8"
    )
    start = script.index(f"{name}() {{")
    end = script.index("\n}\n", start) + 3
    return script[start:end]


def _valid_gpg_status(*, hash_algorithm: int = 8) -> str:
    key_id = TRUSTED_SIGNER[-16:]
    return (
        f"[GNUPG:] GOODSIG {key_id} Backup Signer\n"
        f"[GNUPG:] VALIDSIG {TRUSTED_SIGNER} 2026-07-16 0 0 4 0 1 "
        f"{hash_algorithm} 00 {TRUSTED_SIGNER}\n"
    )


def _backup(tmp_path: Path) -> Path:
    artifacts: dict[str, str] = {}
    for name, content in (
        ("reports.dump.gpg", b"encrypted database"),
        ("uploads.tar.gz.gpg", b"encrypted uploads"),
    ):
        (tmp_path / name).write_bytes(content)
        artifacts[name] = hashlib.sha256(content).hexdigest()
    manifest = tmp_path / "manifest.json"
    manifest.write_text(
        json.dumps(
            {
                "schema_version": "walksafe.backup.v1",
                "run_id": "backup-1",
                "created_at": "2026-07-16T00:00:00Z",
                "recipient_fingerprint": "b" * 40,
                "signer_fingerprint": TRUSTED_SIGNER,
                "database_identity_sha256": "c" * 64,
                "upload_root_identity_sha256": "d" * 64,
                "encryption_at_rest": "openpgp",
                "backup_key": {
                    "schema_version": "walksafe.backup-key-binding.v1",
                    "key_id": BACKUP_KEY_ID,
                    "key_version": 1,
                    "recipient_fingerprint": "b" * 40,
                    "control_id": "backup-control",
                    "control_revision": 1,
                    "control_sha256": CONTROL_SHA256,
                    "control_signer_fingerprint": CONTROL_SIGNER,
                    "authority_lock_identity_sha256": AUTHORITY_LOCK_SHA256,
                    "data_boundary_id": "backup-data",
                    "key_boundary_id": "backup-key-custody",
                },
                "impact_inventory": {
                    "schema_version": "walksafe.backup-impact-inventory.v1",
                    "data_classes": ["REPORT_DATABASE", "REPORT_UPLOADS"],
                    "artifact_names": ["reports.dump.gpg", "uploads.tar.gz.gpg"],
                    "database_identity_sha256": "c" * 64,
                    "upload_root_identity_sha256": "d" * 64,
                },
                "artifacts_sha256": artifacts,
            }
        ),
        encoding="utf-8",
    )
    (tmp_path / "manifest.json.sig").write_bytes(b"signature")
    return manifest


def _key_control(*, state: str = "ACTIVE") -> dict[str, object]:
    if state == "ACTIVE":
        revision = 1
        history: list[dict[str, object]] = []
        transition = {
            "transition_id": "activate-1",
            "kind": "INITIAL_ACTIVATION",
            "from_key_id": None,
            "to_key_id": BACKUP_KEY_ID,
            "rekey_status": "NOT_RUN",
            "rekey_source_inventory_sha256": None,
            "rekey_inventory_sha256": None,
        }
    elif state == "COMPROMISED":
        revision = 2
        history = [{"revision": 1, "control_sha256": CONTROL_SHA256}]
        transition = {
            "transition_id": "compromise-1",
            "kind": "COMPROMISE",
            "from_key_id": BACKUP_KEY_ID,
            "to_key_id": None,
            "rekey_status": "NOT_RUN",
            "rekey_source_inventory_sha256": None,
            "rekey_inventory_sha256": None,
        }
    else:
        raise AssertionError(f"unsupported fixture state: {state}")
    return {
        "schema_version": "walksafe.backup-key-control.v1",
        "control_id": "backup-control",
        "revision": revision,
        "issued_at": (
            "2026-07-16T00:12:00Z"
            if state == "COMPROMISED"
            else "2026-07-16T00:10:00Z"
        ),
        "control_signer_fingerprint": CONTROL_SIGNER,
        "authority_lock_identity_sha256": AUTHORITY_LOCK_SHA256,
        "predecessor_validation_sha256": (
            PREDECESSOR_VALIDATION_SHA256 if revision > 1 else None
        ),
        "data_boundary_id": "backup-data",
        "key_boundary_id": "backup-key-custody",
        "history": history,
        "keys": [
            {
                "key_id": BACKUP_KEY_ID,
                "key_version": 1,
                "recipient_fingerprint": "b" * 40,
                "state": state,
                "predecessor_key_id": None,
                "activated_at": "2026-07-16T00:00:00Z",
                "state_changed_at": (
                    "2026-07-16T00:08:00Z"
                    if state == "COMPROMISED"
                    else "2026-07-16T00:05:00Z"
                ),
                "state_event_id": (
                    "compromise-event-1" if state == "COMPROMISED" else "key-state-1"
                ),
                "incident_id": "incident-1" if state == "COMPROMISED" else None,
            }
        ],
        "transition": transition,
    }


def _validation_attestation(
    control: dict[str, object],
    digest: str,
    *,
    validated_at: str = "2026-07-16T02:00:00Z",
) -> dict[str, object]:
    if control["revision"] == 1:
        validated_head = integrity.validate_operational_backup_key_control(
            control,
            current_sha256=digest,
        )
    else:
        previous = _key_control()
        validated_head = integrity.validate_operational_backup_key_control(
            control,
            current_sha256=digest,
            previous=previous,
            previous_sha256=CONTROL_SHA256,
            previous_validation_attestation=_validation_attestation(
                previous,
                CONTROL_SHA256,
            ),
            previous_validation_sha256=str(control["predecessor_validation_sha256"]),
            verified_validation_signer_fingerprint=VALIDATION_SIGNER,
        )
    return integrity.build_backup_key_control_validation_attestation(
        validated_head,
        validated_at=validated_at,
        validator_signer_fingerprint=VALIDATION_SIGNER,
    )


def _authority_lock_identity(path: Path) -> str:
    descriptor, identity = integrity.acquire_backup_key_control_authority_lock(
        path,
        exclusive=False,
    )
    os.close(descriptor)
    return identity


def test_backup_runtime_preflight_accepts_canonical_python_and_rejects_pinned_312(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    integrity.require_backup_runtime_capabilities()
    monkeypatch.delattr(integrity.os, "MFD_ALLOW_SEALING")
    with pytest.raises(ValueError, match="lacks required memfd sealing constants"):
        integrity.require_backup_runtime_capabilities()

    pinned_python_312 = Path(
        "/home/ddobagi/.local/share/hanium-dreamup/"
        "walksafe-general-cpu-verify-20260715/bin/python"
    )
    if pinned_python_312.exists():
        completed = subprocess.run(
            [
                str(pinned_python_312),
                "-I",
                "-S",
                "-B",
                str(ROOT / "scripts" / "walksafe_backup_integrity.py"),
                "--runtime-capability-preflight",
            ],
            text=True,
            capture_output=True,
            check=False,
        )
        assert completed.returncode == 2
        assert "require the attested Linux CPython 3.14 runtime" in completed.stderr


def test_signed_manifest_hashes_are_compared_to_actual_artifacts(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    manifest = _backup(tmp_path)
    monkeypatch.setattr(
        integrity,
        "verify_signed_json_document",
        lambda document, _signature, _trusted: (
            json.loads(document.read_text(encoding="utf-8")),
            TRUSTED_SIGNER,
        ),
    )

    assert integrity.verify_signed_backup_manifest(
        manifest,
        trusted_signer_fingerprint=TRUSTED_SIGNER,
    )["run_id"] == "backup-1"

    (tmp_path / "reports.dump.gpg").write_bytes(b"replacement encrypted by an attacker")
    (tmp_path / "SHA256SUMS").write_text(
        f"{hashlib.sha256((tmp_path / 'reports.dump.gpg').read_bytes()).hexdigest()}  reports.dump.gpg\n",
        encoding="utf-8",
    )
    with pytest.raises(ValueError, match="signed manifest"):
        integrity.verify_signed_backup_manifest(
            manifest,
            trusted_signer_fingerprint=TRUSTED_SIGNER,
        )


def test_legacy_manifest_remains_verifiable_but_is_not_restore_authorized(
    tmp_path: Path,
) -> None:
    manifest = _backup(tmp_path)
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    payload.pop("backup_key")
    payload.pop("impact_inventory")
    artifact_hashes = {
        name: hashlib.sha256((tmp_path / name).read_bytes()).hexdigest()
        for name in integrity.BACKUP_ARTIFACT_NAMES
    }

    assert integrity._validate_signed_backup_payload(
        payload,
        TRUSTED_SIGNER,
        artifact_hashes,
    )["run_id"] == "backup-1"
    with pytest.raises(ValueError, match="required together"):
        integrity._validate_signed_backup_payload(
            payload,
            TRUSTED_SIGNER,
            artifact_hashes,
            require_security_extensions=True,
        )


def test_backup_key_control_separates_boundaries_and_allows_only_active_backup_key() -> None:
    control = _key_control()
    integrity.validate_backup_key_control(
        control,
        verified_signer_fingerprint=CONTROL_SIGNER,
    )
    authorization = integrity.authorize_active_backup_key(
        control,
        key_control_sha256=CONTROL_SHA256,
        recipient_fingerprint="b" * 40,
        manifest_signer_fingerprint=TRUSTED_SIGNER,
    )

    assert authorization["key_id"] == BACKUP_KEY_ID
    same_boundary = deepcopy(control)
    same_boundary["key_boundary_id"] = same_boundary["data_boundary_id"]
    with pytest.raises(ValueError, match="boundaries must be distinct"):
        integrity.validate_backup_key_control(
            same_boundary,
            verified_signer_fingerprint=CONTROL_SIGNER,
        )

    compromised = _key_control(state="COMPROMISED")
    with pytest.raises(ValueError, match="not the sole active key"):
        integrity.authorize_active_backup_key(
            compromised,
            key_control_sha256="1" * 64,
            recipient_fingerprint="b" * 40,
            manifest_signer_fingerprint=TRUSTED_SIGNER,
        )

    signer_reused_as_old_recipient = deepcopy(control)
    signer_reused_as_old_recipient["keys"].append(
        {
            "key_id": "backup-key-old-signer",
            "key_version": 2,
            "recipient_fingerprint": TRUSTED_SIGNER,
            "state": "DECRYPT_ONLY",
            "predecessor_key_id": BACKUP_KEY_ID,
            "activated_at": "2026-07-16T00:01:00Z",
            "state_changed_at": "2026-07-16T00:05:00Z",
            "state_event_id": "retired-signer-key",
            "incident_id": None,
        }
    )
    with pytest.raises(ValueError, match="must be separate"):
        integrity.authorize_active_backup_key(
            signer_reused_as_old_recipient,
            key_control_sha256=CONTROL_SHA256,
            recipient_fingerprint="b" * 40,
            manifest_signer_fingerprint=TRUSTED_SIGNER,
        )


def test_initial_backup_cli_does_not_invent_rekey_signer(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    observed: dict[str, object] = {}
    control = _key_control()
    authority_descriptor = os.open("/dev/null", os.O_RDONLY)

    monkeypatch.setattr(integrity, "require_backup_runtime_capabilities", lambda: None)
    monkeypatch.setattr(
        integrity,
        "acquire_backup_key_control_authority_lock",
        lambda *_args, **_kwargs: (authority_descriptor, AUTHORITY_LOCK_SHA256),
    )

    def verify(*_args, **kwargs):
        observed.update(kwargs)
        return control, CONTROL_SHA256

    monkeypatch.setattr(integrity, "verify_operational_backup_key_control", verify)
    monkeypatch.setattr(
        integrity,
        "require_backup_key_control_authority_lock",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        integrity,
        "verify_backup_key_control_authority_lock_binding",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "walksafe_backup_integrity.py",
            "--authorize-backup-key",
            "--trusted-signer-fingerprint",
            TRUSTED_SIGNER,
            "--key-control-document",
            "/authority/key-control.json",
            "--key-control-signature",
            "/authority/key-control.json.sig",
            "--key-control-authority-lock",
            "/authority/key-control.lock",
            "--trusted-key-control-signer-fingerprint",
            CONTROL_SIGNER,
            "--expected-key-control-sha256",
            CONTROL_SHA256,
            "--recipient-fingerprint",
            "b" * 40,
        ],
    )

    assert integrity.main() == 0
    assert observed["before_rekey_root"] is None
    assert observed["after_rekey_root"] is None
    assert observed["trusted_rekey_manifest_signer_fingerprint"] is None


def test_key_control_authority_lock_is_identity_bound_and_exclusive(
    tmp_path: Path,
) -> None:
    authority_lock = tmp_path / "backup-key-control.lock"
    authority_lock.write_bytes(b"lock")
    authority_lock.chmod(0o600)
    exclusive_descriptor, identity = integrity.acquire_backup_key_control_authority_lock(
        authority_lock,
        exclusive=True,
    )
    try:
        with pytest.raises(ValueError, match="busy"):
            integrity.acquire_backup_key_control_authority_lock(
                authority_lock,
                exclusive=False,
            )
        control = _key_control()
        control["authority_lock_identity_sha256"] = identity
        integrity.require_backup_key_control_authority_lock(
            control,
            authority_lock_identity_sha256=identity,
        )
        with pytest.raises(ValueError, match="not the lock bound"):
            integrity.require_backup_key_control_authority_lock(
                control,
                authority_lock_identity_sha256="0" * 64,
            )
        displaced = tmp_path / "displaced.lock"
        authority_lock.rename(displaced)
        authority_lock.write_bytes(b"replacement")
        authority_lock.chmod(0o600)
        with pytest.raises(ValueError, match="no longer bound"):
            integrity.verify_backup_key_control_authority_lock_binding(
                authority_lock,
                exclusive_descriptor,
                expected_identity_sha256=identity,
            )
        replacement_descriptor, replacement_identity = (
            integrity.acquire_backup_key_control_authority_lock(
                authority_lock,
                exclusive=True,
            )
        )
        try:
            assert replacement_identity != identity
        finally:
            os.close(replacement_descriptor)
    finally:
        os.close(exclusive_descriptor)


def test_key_control_authority_policy_rejects_service_writable_ancestry(
    tmp_path: Path,
) -> None:
    with pytest.raises(ValueError, match="root-owned and non-writable"):
        _REAL_ROOT_AUTHORITY_POLICY(tmp_path)


def test_non_initial_operational_control_requires_and_validates_previous_revision() -> None:
    previous = _key_control()
    compromised = _key_control(state="COMPROMISED")

    with pytest.raises(ValueError, match="previous signed revision and validated-head"):
        integrity.validate_operational_backup_key_control(
            compromised,
            current_sha256="1" * 64,
        )
    result = integrity.validate_operational_backup_key_control(
        compromised,
        current_sha256="1" * 64,
        previous=previous,
        previous_sha256=CONTROL_SHA256,
        previous_validation_attestation=_validation_attestation(
            previous,
            CONTROL_SHA256,
        ),
        previous_validation_sha256=PREDECESSOR_VALIDATION_SHA256,
        verified_validation_signer_fingerprint=VALIDATION_SIGNER,
    )
    assert result["transition_status"] == "NOT_RUN"


def test_validated_predecessor_head_is_digest_bound_and_independently_signed() -> None:
    previous = _key_control()
    current = _key_control(state="COMPROMISED")
    attestation = _validation_attestation(previous, CONTROL_SHA256)

    wrong_binding = deepcopy(current)
    wrong_binding["predecessor_validation_sha256"] = "7" * 64
    with pytest.raises(ValueError, match="does not bind the validated predecessor"):
        integrity.validate_operational_backup_key_control(
            wrong_binding,
            current_sha256="1" * 64,
            previous=previous,
            previous_sha256=CONTROL_SHA256,
            previous_validation_attestation=attestation,
            previous_validation_sha256=PREDECESSOR_VALIDATION_SHA256,
            verified_validation_signer_fingerprint=VALIDATION_SIGNER,
        )

    forged_signer = deepcopy(attestation)
    forged_signer["validator_signer_fingerprint"] = CONTROL_SIGNER
    with pytest.raises(ValueError, match="independent"):
        integrity.validate_operational_backup_key_control(
            current,
            current_sha256="1" * 64,
            previous=previous,
            previous_sha256=CONTROL_SHA256,
            previous_validation_attestation=forged_signer,
            previous_validation_sha256=PREDECESSOR_VALIDATION_SHA256,
            verified_validation_signer_fingerprint=CONTROL_SIGNER,
        )


def test_validation_attestation_builder_rejects_a_bare_invalid_transition() -> None:
    previous = _key_control()
    invalid = _key_control(state="COMPROMISED")
    invalid["keys"][0]["recipient_fingerprint"] = "c" * 40
    integrity.validate_backup_key_control(
        invalid,
        verified_signer_fingerprint=CONTROL_SIGNER,
    )

    with pytest.raises(ValueError, match="validated operational head"):
        integrity.build_backup_key_control_validation_attestation(
            invalid,
            validated_at="2026-07-16T02:00:00Z",
            validator_signer_fingerprint=VALIDATION_SIGNER,
        )
    with pytest.raises(ValueError, match="changed the source key identity"):
        integrity.validate_operational_backup_key_control(
            invalid,
            current_sha256="1" * 64,
            previous=previous,
            previous_sha256=CONTROL_SHA256,
            previous_validation_attestation=_validation_attestation(
                previous,
                CONTROL_SHA256,
            ),
            previous_validation_sha256=PREDECESSOR_VALIDATION_SHA256,
            verified_validation_signer_fingerprint=VALIDATION_SIGNER,
        )


def test_compromise_is_one_way_and_cannot_replace_a_signed_incident() -> None:
    compromised = _key_control(state="COMPROMISED")
    repeated = deepcopy(compromised)
    repeated.update(
        revision=3,
        issued_at="2026-07-16T00:20:00Z",
        predecessor_validation_sha256="7" * 64,
        history=[
            {"revision": 1, "control_sha256": CONTROL_SHA256},
            {"revision": 2, "control_sha256": "1" * 64},
        ],
    )
    repeated["keys"][0].update(
        state_changed_at="2026-07-16T00:18:00Z",
        state_event_id="compromise-event-2",
        incident_id="incident-2",
    )
    repeated["transition"] = {
        **repeated["transition"],
        "transition_id": "compromise-2",
    }

    with pytest.raises(ValueError, match="cannot replace an existing incident"):
        integrity.validate_atomic_key_control_transition(
            compromised,
            repeated,
            previous_sha256="1" * 64,
            current_sha256="2" * 64,
        )


def test_compromise_incident_id_cannot_be_reused_from_another_key() -> None:
    previous = _key_control(state="COMPROMISED")
    previous["keys"].append(
        {
            "key_id": "backup-key-2",
            "key_version": 2,
            "recipient_fingerprint": "1" * 40,
            "state": "ACTIVE",
            "predecessor_key_id": BACKUP_KEY_ID,
            "activated_at": "2026-07-16T00:08:00Z",
            "state_changed_at": "2026-07-16T00:08:00Z",
            "state_event_id": "replacement-active-1",
            "incident_id": None,
        }
    )
    previous["transition"]["to_key_id"] = "backup-key-2"
    current = deepcopy(previous)
    current.update(
        revision=3,
        issued_at="2026-07-16T00:20:00Z",
        predecessor_validation_sha256="7" * 64,
        history=[
            {"revision": 1, "control_sha256": CONTROL_SHA256},
            {"revision": 2, "control_sha256": "1" * 64},
        ],
    )
    current["keys"][1].update(
        state="COMPROMISED",
        state_changed_at="2026-07-16T00:18:00Z",
        state_event_id="compromise-second-key",
        incident_id="incident-1",
    )
    current["transition"] = {
        "transition_id": "compromise-second-key",
        "kind": "COMPROMISE",
        "from_key_id": "backup-key-2",
        "to_key_id": None,
        "rekey_status": "NOT_RUN",
        "rekey_source_inventory_sha256": None,
        "rekey_inventory_sha256": None,
    }

    with pytest.raises(ValueError, match="repeats a compromise incident id"):
        integrity.validate_atomic_key_control_transition(
            previous,
            current,
            previous_sha256="1" * 64,
            current_sha256="2" * 64,
        )


def test_rotation_cannot_disguise_a_compromise_or_claim_same_revision_rekey() -> None:
    initial = _key_control()
    disguised = deepcopy(initial)
    disguised.update(
        revision=2,
        issued_at="2026-07-16T00:20:00Z",
        predecessor_validation_sha256=PREDECESSOR_VALIDATION_SHA256,
        history=[{"revision": 1, "control_sha256": CONTROL_SHA256}],
    )
    disguised["keys"][0].update(
        state="COMPROMISED",
        state_changed_at="2026-07-16T00:15:00Z",
        state_event_id="hidden-compromise",
        incident_id="incident-hidden",
    )
    disguised["keys"].append(
        {
            "key_id": "backup-key-2",
            "key_version": 2,
            "recipient_fingerprint": "1" * 40,
            "state": "ACTIVE",
            "predecessor_key_id": BACKUP_KEY_ID,
            "activated_at": "2026-07-16T00:15:00Z",
            "state_changed_at": "2026-07-16T00:15:00Z",
            "state_event_id": "replacement-1",
            "incident_id": None,
        }
    )
    disguised["transition"] = {
        "transition_id": "rotation-hidden-compromise",
        "kind": "ROTATION",
        "from_key_id": BACKUP_KEY_ID,
        "to_key_id": "backup-key-2",
        "rekey_status": "VERIFIED",
        "rekey_source_inventory_sha256": "3" * 64,
        "rekey_inventory_sha256": "4" * 64,
    }

    with pytest.raises(ValueError, match="non-incident decrypt-only"):
        integrity.validate_backup_key_control(
            disguised,
            verified_signer_fingerprint=CONTROL_SIGNER,
        )


def test_compromised_key_cannot_be_reintroduced_as_an_active_rotation_target() -> None:
    compromised = _key_control(state="COMPROMISED")
    compromised["keys"].append(
        {
            "key_id": "backup-key-2",
            "key_version": 2,
            "recipient_fingerprint": "1" * 40,
            "state": "ACTIVE",
            "predecessor_key_id": BACKUP_KEY_ID,
            "activated_at": "2026-07-16T00:08:00Z",
            "state_changed_at": "2026-07-16T00:08:00Z",
            "state_event_id": "replacement-active-1",
            "incident_id": None,
        }
    )
    compromised["transition"]["to_key_id"] = "backup-key-2"
    reactivated = deepcopy(compromised)
    reactivated.update(
        revision=3,
        issued_at="2026-07-16T00:20:00Z",
        history=[
            {"revision": 1, "control_sha256": CONTROL_SHA256},
            {"revision": 2, "control_sha256": "1" * 64},
        ],
    )
    reactivated["keys"] = [
        {
            **reactivated["keys"][0],
            "key_version": 3,
            "state": "ACTIVE",
            "predecessor_key_id": "backup-key-2",
            "activated_at": "2026-07-16T00:15:00Z",
            "state_changed_at": "2026-07-16T00:15:00Z",
            "state_event_id": "reactivation-attempt",
            "incident_id": None,
        },
        {
            **reactivated["keys"][1],
            "state": "DECRYPT_ONLY",
            "predecessor_key_id": None,
            "state_changed_at": "2026-07-16T00:15:00Z",
            "state_event_id": "retire-replacement",
        },
    ]
    reactivated["transition"] = {
        "transition_id": "rotation-reactivation",
        "kind": "ROTATION",
        "from_key_id": "backup-key-2",
        "to_key_id": BACKUP_KEY_ID,
        "rekey_status": "NOT_RUN",
        "rekey_source_inventory_sha256": None,
        "rekey_inventory_sha256": None,
    }

    integrity.validate_backup_key_control(
        reactivated,
        verified_signer_fingerprint=CONTROL_SIGNER,
    )
    with pytest.raises(ValueError, match="unrelated key|newly introduced"):
        integrity.validate_operational_backup_key_control(
            reactivated,
            current_sha256="2" * 64,
            previous=compromised,
            previous_sha256="1" * 64,
            previous_validation_attestation=_validation_attestation(
                compromised,
                "1" * 64,
            ),
            previous_validation_sha256=PREDECESSOR_VALIDATION_SHA256,
            verified_validation_signer_fingerprint=VALIDATION_SIGNER,
        )


def test_compromised_key_blocks_restore_and_stops_incident_at_legal_review(
    tmp_path: Path,
) -> None:
    manifest = _backup(tmp_path)
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    control = _key_control(state="COMPROMISED")
    current_digest = "1" * 64

    resolved = integrity.resolve_manifest_backup_key(
        payload,
        control,
        key_control_sha256=current_digest,
        allow_compromised=True,
    )
    assert resolved["state"] == "COMPROMISED"
    with pytest.raises(ValueError, match="decryption is blocked before GPG"):
        integrity.resolve_manifest_backup_key(
            payload,
            control,
            key_control_sha256=current_digest,
            allow_compromised=False,
        )

    inventory = integrity.build_backup_impact_inventory([payload])
    workflow = integrity.build_backup_key_incident_workflow(
        control,
        key_control_sha256=current_digest,
        key_id=BACKUP_KEY_ID,
        incident_id="incident-1",
        detected_at="2026-07-16T00:20:00Z",
        impact_inventory=inventory,
    )
    assert [item["stage"] for item in workflow["timeline"]] == [
        "DETECTED",
        "IMPACT_INVENTORY",
        "CONTAINMENT",
        "LEGAL_REVIEW",
    ]
    assert workflow["status"] == "LEGAL_REVIEW_REQUIRED"
    assert workflow["legal_review_status"] == "NOT_RUN"
    assert workflow["notification_status"] == "NOT_RUN"
    assert workflow["recovery_status"] == "NOT_RUN"
    assert workflow["kms_operation_status"] == "NOT_RUN"
    assert workflow["restore_drill_status"] == "NOT_RUN"


def test_rekey_verified_requires_two_revisions_and_fd_anchored_actual_decrypt(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    initial = _key_control()
    rotation = {
        **deepcopy(initial),
        "revision": 2,
        "issued_at": "2026-07-16T01:10:00Z",
        "predecessor_validation_sha256": PREDECESSOR_VALIDATION_SHA256,
        "history": [{"revision": 1, "control_sha256": CONTROL_SHA256}],
        "keys": [
            {
                **initial["keys"][0],
                "state": "DECRYPT_ONLY",
                "state_changed_at": "2026-07-16T01:00:00Z",
                "state_event_id": "rotate-old-1",
            },
            {
                "key_id": "backup-key-2",
                "key_version": 2,
                "recipient_fingerprint": "1" * 40,
                "state": "ACTIVE",
                "predecessor_key_id": BACKUP_KEY_ID,
                "activated_at": "2026-07-16T01:00:00Z",
                "state_changed_at": "2026-07-16T01:00:00Z",
                "state_event_id": "rotate-new-1",
                "incident_id": None,
            },
        ],
        "transition": {
            "transition_id": "rotation-1",
            "kind": "ROTATION",
            "from_key_id": BACKUP_KEY_ID,
            "to_key_id": "backup-key-2",
            "rekey_status": "NOT_RUN",
            "rekey_source_inventory_sha256": None,
            "rekey_inventory_sha256": None,
        },
    }
    assert integrity.validate_atomic_key_control_transition(
        initial,
        rotation,
        previous_sha256=CONTROL_SHA256,
        current_sha256="2" * 64,
    )["rekey_status"] == "NOT_RUN"

    impossible_single_revision = deepcopy(rotation)
    impossible_single_revision["transition"].update(
        rekey_status="VERIFIED",
        rekey_source_inventory_sha256="3" * 64,
        rekey_inventory_sha256="4" * 64,
    )
    with pytest.raises(ValueError, match="separate rekey verification revision"):
        integrity.validate_backup_key_control(
            impossible_single_revision,
            verified_signer_fingerprint=CONTROL_SIGNER,
        )

    before_root = tmp_path / "before"
    after_root = tmp_path / "after"
    for root in (before_root, after_root):
        root.mkdir(mode=0o700)

    def write_run(
        root: Path,
        *,
        run_id: str,
        recipient: str,
        key_id: str,
        key_version: int,
        control_revision: int,
        control_sha256: str,
        prefix: bytes,
    ) -> None:
        candidate = root / f"walksafe-backup-{run_id}"
        candidate.mkdir(mode=0o700)
        (candidate / "manifest.json.sig").write_bytes(b"signed")
        artifacts = {
            "reports.dump.gpg": prefix + b"-database",
            "uploads.tar.gz.gpg": prefix + b"-uploads",
        }
        for name, content in artifacts.items():
            (candidate / name).write_bytes(content)
        payload_root = tmp_path / f"payload-{key_id}-{run_id}"
        payload_root.mkdir()
        payload = json.loads(_backup(payload_root).read_text())
        payload["run_id"] = run_id
        payload["backup_key"].update(
            key_id=key_id,
            key_version=key_version,
            recipient_fingerprint=recipient,
            control_revision=control_revision,
            control_sha256=control_sha256,
        )
        payload["recipient_fingerprint"] = recipient
        payload["artifacts_sha256"] = {
            name: hashlib.sha256(content).hexdigest()
            for name, content in artifacts.items()
        }
        (candidate / "manifest.json").write_text(json.dumps(payload), encoding="utf-8")

    write_run(
        before_root,
        run_id="backup-1",
        recipient="b" * 40,
        key_id=BACKUP_KEY_ID,
        key_version=1,
        control_revision=1,
        control_sha256=CONTROL_SHA256,
        prefix=b"old-one",
    )
    write_run(
        before_root,
        run_id="backup-2",
        recipient="b" * 40,
        key_id=BACKUP_KEY_ID,
        key_version=1,
        control_revision=1,
        control_sha256=CONTROL_SHA256,
        prefix=b"old-two",
    )
    write_run(
        after_root,
        run_id="backup-1",
        recipient="1" * 40,
        key_id="backup-key-2",
        key_version=2,
        control_revision=2,
        control_sha256="2" * 64,
        prefix=b"new-one",
    )
    write_run(
        after_root,
        run_id="backup-2",
        recipient="1" * 40,
        key_id="backup-key-2",
        key_version=2,
        control_revision=2,
        control_sha256="2" * 64,
        prefix=b"new-two",
    )

    def verified_bundle(**kwargs):
        manifest_bytes = os.pread(
            kwargs["manifest_fd"],
            os.fstat(kwargs["manifest_fd"]).st_size,
            0,
        )
        payload = json.loads(manifest_bytes)
        artifact_hashes = {
            name: integrity._sha256_stable_fd(kwargs[field], context=name)
            for name, field in (
                ("reports.dump.gpg", "reports_fd"),
                ("uploads.tar.gz.gpg", "uploads_fd"),
            )
        }
        return integrity._validate_signed_backup_payload(
            payload,
            TRUSTED_SIGNER,
            artifact_hashes,
            require_security_extensions=True,
        )

    observed_recipients: list[str] = []

    def decrypt(encrypted_fd: int, *, expected_recipient_fingerprint: str, **_kwargs):
        encrypted = os.pread(encrypted_fd, os.fstat(encrypted_fd).st_size, 0)
        observed_recipients.append(expected_recipient_fingerprint)
        plaintext = b"database-plain" if encrypted.endswith(b"database") else b"uploads-plain"
        output = os.memfd_create("rekey-test-plaintext")
        os.write(output, plaintext)
        return output

    monkeypatch.setattr(integrity, "verify_signed_backup_fd_bundle", verified_bundle)
    monkeypatch.setattr(integrity, "_decrypt_to_inheritable_snapshot", decrypt)
    evidence = integrity.verify_backup_rekey_roots(
        before_root,
        after_root,
        trusted_manifest_signer_fingerprint=TRUSTED_SIGNER,
        rotation_control=rotation,
        rotation_control_sha256="2" * 64,
    )
    assert observed_recipients == [
        "b" * 40,
        "b" * 40,
        "b" * 40,
        "b" * 40,
        "1" * 40,
        "1" * 40,
        "1" * 40,
        "1" * 40,
    ]

    unscanned_entry = before_root / "unscanned-evidence"
    unscanned_entry.write_bytes(b"must not be ignored")
    with pytest.raises(ValueError, match="outside the complete backup set"):
        integrity.verify_backup_rekey_roots(
            before_root,
            after_root,
            trusted_manifest_signer_fingerprint=TRUSTED_SIGNER,
            rotation_control=rotation,
            rotation_control_sha256="2" * 64,
        )
    unscanned_entry.unlink()

    replaced_source = False

    def replace_after_snapshot(**kwargs):
        nonlocal replaced_source
        payload = verified_bundle(**kwargs)
        if payload["backup_key"]["key_id"] == BACKUP_KEY_ID and not replaced_source:
            replaced_source = True
            source = before_root / "walksafe-backup-backup-1" / "reports.dump.gpg"
            replacement = source.with_name("reports.dump.gpg.replacement")
            replacement.write_bytes(b"replacement encrypted artifact")
            os.replace(replacement, source)
        return payload

    monkeypatch.setattr(
        integrity,
        "verify_signed_backup_fd_bundle",
        replace_after_snapshot,
    )
    with pytest.raises(ValueError, match="captured source path changed"):
        integrity.verify_backup_rekey_roots(
            before_root,
            after_root,
            trusted_manifest_signer_fingerprint=TRUSTED_SIGNER,
            rotation_control=rotation,
            rotation_control_sha256="2" * 64,
        )
    assert replaced_source is True

    replaced_reports = (
        before_root / "walksafe-backup-backup-1" / "reports.dump.gpg"
    )
    replaced_reports.write_bytes(b"old-one-database")
    interleaved_mutation = False

    def mutate_first_candidate_while_verifying_second(**kwargs):
        nonlocal interleaved_mutation
        payload = verified_bundle(**kwargs)
        if (
            payload["backup_key"]["key_id"] == BACKUP_KEY_ID
            and payload["run_id"] == "backup-2"
            and not interleaved_mutation
        ):
            interleaved_mutation = True
            descriptor = os.open(replaced_reports, os.O_WRONLY)
            try:
                original_size = os.fstat(descriptor).st_size
                os.pwrite(descriptor, b"X" * original_size, 0)
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        return payload

    monkeypatch.setattr(
        integrity,
        "verify_signed_backup_fd_bundle",
        mutate_first_candidate_while_verifying_second,
    )
    with pytest.raises(ValueError, match="source changed after complete scan"):
        integrity.verify_backup_rekey_roots(
            before_root,
            after_root,
            trusted_manifest_signer_fingerprint=TRUSTED_SIGNER,
            rotation_control=rotation,
            rotation_control_sha256="2" * 64,
        )
    assert interleaved_mutation is True

    rekey_verification = {
        **deepcopy(rotation),
        "revision": 3,
        "issued_at": "2026-07-16T01:20:00Z",
        "predecessor_validation_sha256": "7" * 64,
        "history": [
            {"revision": 1, "control_sha256": CONTROL_SHA256},
            {"revision": 2, "control_sha256": "2" * 64},
        ],
        "transition": {
            "transition_id": "rekey-verification-1",
            "kind": "REKEY_VERIFICATION",
            "from_key_id": BACKUP_KEY_ID,
            "to_key_id": "backup-key-2",
            "rekey_status": "VERIFIED",
            "rekey_source_inventory_sha256": evidence.before["inventory_sha256"],
            "rekey_inventory_sha256": evidence.after["inventory_sha256"],
        },
    }
    result = integrity.validate_atomic_key_control_transition(
        rotation,
        rekey_verification,
        previous_sha256="2" * 64,
        current_sha256="3" * 64,
        rekey_evidence=evidence,
    )
    assert result["rekey_status"] == "VERIFIED"
    assert all(
        row["control_sha256"] == "2" * 64 for row in evidence.after["runs"]
    )

    with pytest.raises(ValueError, match="fd-anchored operational evidence"):
        integrity.validate_atomic_key_control_transition(
            rotation,
            rekey_verification,
            previous_sha256="2" * 64,
            current_sha256="3" * 64,
        )


def test_gpg_decrypt_status_must_bind_the_signed_recipient(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    encrypted_path = tmp_path / "artifact.gpg"
    encrypted_path.write_bytes(b"encrypted")
    encrypted_fd = integrity._capture_inheritable_snapshot(
        encrypted_path,
        context="test encrypted artifact",
    )

    class FakeGpg:
        def matches_path(self) -> bool:
            return True

    @contextmanager
    def fake_gpg_snapshot():
        yield FakeGpg()

    def fake_run(command, **kwargs):
        os.write(kwargs["stdout"], b"plaintext")
        return subprocess.CompletedProcess(
            command,
            0,
            stdout=b"",
            stderr=(
                f"[GNUPG:] DECRYPTION_KEY {'c' * 40} {'0' * 40} u\n"
                "[GNUPG:] DECRYPTION_OKAY\n"
            ).encode(),
        )

    monkeypatch.setattr(integrity, "_trusted_gpg_snapshot", fake_gpg_snapshot)
    monkeypatch.setattr(integrity.subprocess, "run", fake_run)
    try:
        with pytest.raises(ValueError, match="signed recipient key"):
            integrity._decrypt_to_inheritable_snapshot(
                encrypted_fd,
                context="test artifact",
                expected_recipient_fingerprint="b" * 40,
            )
    finally:
        os.close(encrypted_fd)


def test_signed_json_digest_is_computed_from_verified_document_bytes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    verified_bytes = b'{"schema_version":"walksafe.restore-drill.v1"}'
    monkeypatch.setattr(
        integrity,
        "_verified_detached_document_bytes",
        lambda _document, _signature, _trusted: (verified_bytes, TRUSTED_SIGNER),
    )

    payload, signer, digest = integrity.verify_signed_json_document_with_digest(
        tmp_path / "receipt.json",
        tmp_path / "receipt.json.sig",
        TRUSTED_SIGNER,
    )

    assert payload["schema_version"] == "walksafe.restore-drill.v1"
    assert signer == TRUSTED_SIGNER
    assert digest == hashlib.sha256(verified_bytes).hexdigest()


def test_detached_signature_requires_the_configured_trusted_fingerprint(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    document = tmp_path / "document.json"
    signature = tmp_path / "document.json.sig"
    document.write_bytes(b"{}")
    signature.write_bytes(b"signature")
    observed: dict[str, object] = {}

    def fake_gpg(command, **kwargs):
        observed["command"] = command
        observed["environment"] = kwargs["env"]
        return subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout=_valid_gpg_status(),
            stderr="",
        )

    monkeypatch.setattr(integrity.subprocess, "run", fake_gpg)

    assert integrity.verify_detached_signature(document, signature, TRUSTED_SIGNER) == TRUSTED_SIGNER
    assert observed["command"][0] == "/usr/bin/gpg"
    assert observed["command"][-2].startswith("/proc/self/fd/")
    assert observed["command"][-1].startswith("/proc/self/fd/")
    assert observed["environment"]["PATH"] == "/usr/bin:/bin"
    with pytest.raises(ValueError, match="trusted signer"):
        integrity.verify_detached_signature(document, signature, "b" * 40)


def test_detached_signature_rejects_a_document_replaced_during_gpg(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    document = tmp_path / "document.json"
    signature = tmp_path / "document.json.sig"
    original = b'{"value":"original"}'
    document.write_bytes(original)
    signature.write_bytes(b"signature")

    def replace_during_gpg(command, **_kwargs):
        document.write_bytes(b'{"value":"replacement"}')
        document.write_bytes(original)
        return subprocess.CompletedProcess(
            args=command,
            returncode=0,
            stdout=_valid_gpg_status(),
            stderr="",
        )

    monkeypatch.setattr(integrity.subprocess, "run", replace_during_gpg)

    with pytest.raises(ValueError, match="changed during verification"):
        integrity.verify_detached_signature(document, signature, TRUSTED_SIGNER)


def test_detached_signature_rejects_weak_digest(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    document = tmp_path / "document.json"
    signature = tmp_path / "document.json.sig"
    document.write_bytes(b"{}")
    signature.write_bytes(b"signature")
    monkeypatch.setattr(
        integrity.subprocess,
        "run",
        lambda command, **_kwargs: subprocess.CompletedProcess(
            args=command,
            returncode=0,
            stdout=_valid_gpg_status(hash_algorithm=2),
            stderr="",
        ),
    )

    with pytest.raises(ValueError, match="digest"):
        integrity.verify_detached_signature(document, signature, TRUSTED_SIGNER)


def test_detached_signature_rejects_expired_key_status(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    document = tmp_path / "document.json"
    signature = tmp_path / "document.json.sig"
    document.write_bytes(b"{}")
    signature.write_bytes(b"signature")
    monkeypatch.setattr(
        integrity.subprocess,
        "run",
        lambda command, **_kwargs: subprocess.CompletedProcess(
            args=command,
            returncode=0,
            stdout=(
                f"[GNUPG:] EXPKEYSIG {TRUSTED_SIGNER[-16:]} Backup Signer\n"
                f"{_valid_gpg_status()}"
            ),
            stderr="",
        ),
    )

    with pytest.raises(ValueError, match="invalid"):
        integrity.verify_detached_signature(document, signature, TRUSTED_SIGNER)


def test_signed_manifest_rejects_symlinked_artifact(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    manifest = _backup(tmp_path)
    target = tmp_path / "outside.gpg"
    target.write_bytes((tmp_path / "uploads.tar.gz.gpg").read_bytes())
    (tmp_path / "uploads.tar.gz.gpg").unlink()
    (tmp_path / "uploads.tar.gz.gpg").symlink_to(target)
    monkeypatch.setattr(
        integrity,
        "verify_signed_json_document",
        lambda document, _signature, _trusted: (
            json.loads(document.read_text(encoding="utf-8")),
            TRUSTED_SIGNER,
        ),
    )

    with pytest.raises(ValueError, match="regular non-symlink"):
        integrity.verify_signed_backup_manifest(
            manifest,
            trusted_signer_fingerprint=TRUSTED_SIGNER,
        )


def test_backup_snapshot_memfd_is_kernel_sealed(tmp_path: Path) -> None:
    source = tmp_path / "artifact.gpg"
    source.write_bytes(b"immutable backup bytes")
    descriptor = integrity._capture_inheritable_snapshot(source, context="test backup")
    try:
        seals = fcntl.fcntl(descriptor, fcntl.F_GET_SEALS)
        assert seals & integrity.REQUIRED_SNAPSHOT_SEALS == integrity.REQUIRED_SNAPSHOT_SEALS
        assert os.fstat(descriptor).st_mode & 0o777 == 0o400
        with pytest.raises(OSError):
            os.pwrite(descriptor, b"attacker", 0)
        with integrity._snapshot_from_owner_fd(
            os.getpid(),
            descriptor,
            context="test inherited backup",
        ) as snapshot:
            assert snapshot.sha256 == hashlib.sha256(source.read_bytes()).hexdigest()
    finally:
        os.close(descriptor)


def test_inherited_backup_rejects_unsealed_memfd() -> None:
    descriptor = os.memfd_create("unsealed", os.MFD_ALLOW_SEALING)
    try:
        os.write(descriptor, b"unsealed")
        os.fchmod(descriptor, 0o400)
        with pytest.raises(ValueError, match="private unlinked read-only snapshot"):
            integrity._snapshot_from_owner_fd(
                os.getpid(),
                descriptor,
                context="unsealed backup",
            )
    finally:
        os.close(descriptor)


def test_decrypted_snapshot_is_sealed_and_bound_to_the_encrypted_input(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    encrypted = tmp_path / "backup.gpg"
    encrypted.write_bytes(b"encrypted backup")
    plaintext = tmp_path / "backup.dump"
    plaintext.write_bytes(b"decrypted backup")
    wrong_plaintext = tmp_path / "wrong.dump"
    wrong_plaintext.write_bytes(b"attacker plaintext")
    encrypted_fd = integrity._capture_inheritable_snapshot(encrypted, context="encrypted test backup")

    def fake_gpg(command, **kwargs):
        os.write(kwargs["stdout"], plaintext.read_bytes())
        return subprocess.CompletedProcess(
            args=command,
            returncode=0,
            stdout=None,
            stderr=b"[GNUPG:] DECRYPTION_OKAY\n",
        )

    monkeypatch.setattr(integrity.subprocess, "run", fake_gpg)
    decrypted_fd = integrity._decrypt_to_inheritable_snapshot(
        encrypted_fd,
        context="test backup",
    )
    expected_fd = integrity._capture_inheritable_snapshot(plaintext, context="expected plaintext")
    wrong_fd = integrity._capture_inheritable_snapshot(wrong_plaintext, context="wrong plaintext")
    try:
        seals = fcntl.fcntl(decrypted_fd, fcntl.F_GET_SEALS)
        assert seals & integrity.REQUIRED_SNAPSHOT_SEALS == integrity.REQUIRED_SNAPSHOT_SEALS
        assert os.pread(decrypted_fd, len(plaintext.read_bytes()), 0) == plaintext.read_bytes()
        integrity._require_matching_decrypted_snapshot(
            encrypted_fd,
            expected_fd,
            context="test backup",
        )
        with pytest.raises(ValueError, match="does not match the signed encrypted input"):
            integrity._require_matching_decrypted_snapshot(
                encrypted_fd,
                wrong_fd,
                context="test backup",
            )
    finally:
        for descriptor in (encrypted_fd, decrypted_fd, expected_fd, wrong_fd):
            os.close(descriptor)


def test_fd_bundle_verifies_signature_and_signed_artifact_hashes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    manifest = _backup(tmp_path)
    descriptors = [
        integrity._capture_inheritable_snapshot(path, context=path.name)
        for path in (
            manifest,
            tmp_path / "manifest.json.sig",
            tmp_path / "reports.dump.gpg",
            tmp_path / "uploads.tar.gz.gpg",
        )
    ]
    monkeypatch.setattr(
        integrity.subprocess,
        "run",
        lambda command, **_kwargs: subprocess.CompletedProcess(
            args=command,
            returncode=0,
            stdout=_valid_gpg_status(),
            stderr="",
        ),
    )
    try:
        payload = integrity.verify_signed_backup_fd_bundle(
            owner_pid=os.getpid(),
            manifest_fd=descriptors[0],
            signature_fd=descriptors[1],
            reports_fd=descriptors[2],
            uploads_fd=descriptors[3],
            trusted_signer_fingerprint=TRUSTED_SIGNER,
            max_age_seconds=60,
            future_skew_seconds=300,
            now=datetime(2026, 7, 16, 0, 1, tzinfo=UTC),
        )
        authorization = integrity.resolve_manifest_backup_key(
            payload,
            _key_control(),
            key_control_sha256=CONTROL_SHA256,
            allow_compromised=False,
        )
        assert integrity._restore_manifest_fields(
            payload,
            key_authorization=authorization,
        )[0] == "backup-1"
        with pytest.raises(ValueError, match="older than"):
            integrity.verify_signed_backup_fd_bundle(
                owner_pid=os.getpid(),
                manifest_fd=descriptors[0],
                signature_fd=descriptors[1],
                reports_fd=descriptors[2],
                uploads_fd=descriptors[3],
                trusted_signer_fingerprint=TRUSTED_SIGNER,
                max_age_seconds=60,
                future_skew_seconds=300,
                now=datetime(2026, 7, 16, 0, 1, 1, tzinfo=UTC),
            )
    finally:
        for descriptor in descriptors:
            os.close(descriptor)


def test_signed_manifest_age_rejects_stale_future_and_missing_created_at() -> None:
    now = datetime(2026, 8, 25, 12, 0, tzinfo=UTC)
    payload = {"created_at": (now - timedelta(seconds=60)).isoformat()}

    assert integrity.require_backup_manifest_age(
        payload,
        max_age_seconds=60,
        future_skew_seconds=300,
        now=now,
    ) == now - timedelta(seconds=60)

    with pytest.raises(ValueError, match="older than"):
        integrity.require_backup_manifest_age(
            {"created_at": (now - timedelta(seconds=61)).isoformat()},
            max_age_seconds=60,
            future_skew_seconds=300,
            now=now,
        )
    with pytest.raises(ValueError, match="future skew"):
        integrity.require_backup_manifest_age(
            {"created_at": (now + timedelta(seconds=301)).isoformat()},
            max_age_seconds=60,
            future_skew_seconds=300,
            now=now,
        )
    with pytest.raises(ValueError, match="created_at"):
        integrity.require_backup_manifest_age(
            {},
            max_age_seconds=60,
            future_skew_seconds=300,
            now=now,
        )


def test_restore_age_policy_is_kernel_sealed_and_cannot_be_relaxed() -> None:
    descriptor = integrity._create_inheritable_age_policy_snapshot(
        max_age_seconds=93600,
        future_skew_seconds=300,
    )
    try:
        integrity._require_inherited_age_policy(
            owner_pid=os.getpid(),
            policy_fd=descriptor,
            max_age_seconds=93600,
            future_skew_seconds=300,
        )
        with pytest.raises(ValueError, match="does not match"):
            integrity._require_inherited_age_policy(
                owner_pid=os.getpid(),
                policy_fd=descriptor,
                max_age_seconds=604800,
                future_skew_seconds=300,
            )
    finally:
        os.close(descriptor)


def test_restore_age_policy_allows_the_canonical_35_day_retention_boundary() -> None:
    assert integrity._validated_backup_age_policy(3024000, 300) == (3024000, 300)
    with pytest.raises(ValueError, match="3024000"):
        integrity._validated_backup_age_policy(3024001, 300)


def test_restore_handoff_scrubs_shell_injection_and_seals_all_inputs(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    backup_dir = tmp_path / "backup"
    backup_dir.mkdir()
    manifest = _backup(backup_dir)
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    control_document = tmp_path / "key-control.json"
    control_signature = tmp_path / "key-control.json.sig"
    authority_lock = tmp_path / "key-control.lock"
    authority_lock.write_bytes(b"lock")
    authority_lock.chmod(0o600)
    authority_lock_identity = _authority_lock_identity(authority_lock)
    control = _key_control()
    control["authority_lock_identity_sha256"] = authority_lock_identity
    payload["backup_key"]["authority_lock_identity_sha256"] = authority_lock_identity
    control_document.write_text(json.dumps(control), encoding="utf-8")
    control_signature.write_bytes(b"control signature")
    plaintext = tmp_path / "plaintext"
    plaintext.write_bytes(b"decrypted bytes")
    observed: dict[str, object] = {}

    monkeypatch.setattr(integrity, "verify_signed_backup_fd_bundle", lambda **_kwargs: payload)
    monkeypatch.setattr(
        integrity,
        "verify_signed_backup_key_control_fd_bundle",
        lambda **_kwargs: (control, CONTROL_SHA256),
    )
    monkeypatch.setattr(
        integrity,
        "verify_operational_backup_key_control",
        lambda *_args, **_kwargs: (control, CONTROL_SHA256),
    )
    monkeypatch.setattr(
        integrity,
        "_decrypt_to_inheritable_snapshot",
        lambda _descriptor, **_kwargs: integrity._capture_inheritable_snapshot(
            plaintext,
            context="decrypted test backup",
        ),
    )
    for name in ("BASH_ENV", "TAR_OPTIONS", "PYTHONPATH", "PGHOST", "PGSERVICE"):
        monkeypatch.setenv(name, f"attacker-{name}")
    target_database_url = "postgresql://user:secret@127.0.0.1:5432/walksafe_drill"
    monkeypatch.setenv("TARGET_DATABASE_URL", target_database_url)

    class ExecObserved(RuntimeError):
        pass

    def fake_execve(path, argv, environment):
        observed.update(path=path, argv=argv, environment=environment)
        authority_descriptor = int(
            environment[integrity.VERIFIED_AUTHORITY_LOCK_FD_ENV]
        )
        assert os.get_inheritable(authority_descriptor) is True
        assert (os.fstat(authority_descriptor).st_dev, os.fstat(authority_descriptor).st_ino) == (
            authority_lock.stat().st_dev,
            authority_lock.stat().st_ino,
        )
        descriptors = [int(value) for value in environment[integrity.VERIFIED_BACKUP_FDS_ENV].split(":")]
        assert len(descriptors) == 9
        for descriptor in descriptors:
            seals = fcntl.fcntl(descriptor, fcntl.F_GET_SEALS)
            assert seals & integrity.REQUIRED_SNAPSHOT_SEALS == integrity.REQUIRED_SNAPSHOT_SEALS
            with pytest.raises(OSError):
                os.pwrite(descriptor, b"attacker", 0)
        with pytest.raises(ValueError, match="busy"):
            integrity.acquire_backup_key_control_authority_lock(
                authority_lock,
                exclusive=True,
            )
        raise ExecObserved

    monkeypatch.setattr(integrity.os, "execve", fake_execve)
    with pytest.raises(ExecObserved):
        integrity._seal_and_exec_restore(
            backup_dir=backup_dir,
            restore_script=ROOT / "scripts" / "restore_walksafe_backup_drill_20260711.sh",
            trusted_signer_fingerprint=TRUSTED_SIGNER,
            key_control_document=control_document,
            key_control_signature=control_signature,
            key_control_authority_lock=authority_lock,
            trusted_key_control_signer_fingerprint=CONTROL_SIGNER,
            expected_key_control_sha256=CONTROL_SHA256,
            previous_key_control_document=None,
            previous_key_control_signature=None,
            expected_previous_key_control_sha256=None,
            previous_validation_document=None,
            previous_validation_signature=None,
            trusted_validation_signer_fingerprint=None,
            before_rekey_root=None,
            after_rekey_root=None,
            trusted_rekey_manifest_signer_fingerprint=None,
            max_age_seconds=93600,
            future_skew_seconds=300,
            restore_arguments=["--", "--backup-dir", str(backup_dir)],
        )

    assert observed["path"] == "/bin/bash"
    assert observed["argv"][1] == "-p"
    assert target_database_url not in "\0".join(observed["argv"])
    environment = observed["environment"]
    assert environment["TARGET_DATABASE_URL"] == target_database_url
    for name in ("BASH_ENV", "TAR_OPTIONS", "PYTHONPATH", "PGHOST", "PGSERVICE"):
        assert name not in environment


def test_restore_blocks_compromised_key_before_any_decrypt(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    backup_dir = tmp_path / "backup"
    backup_dir.mkdir()
    manifest = _backup(backup_dir)
    payload = json.loads(manifest.read_text(encoding="utf-8"))
    control_document = tmp_path / "key-control.json"
    control_signature = tmp_path / "key-control.json.sig"
    authority_lock = tmp_path / "key-control.lock"
    authority_lock.write_bytes(b"lock")
    authority_lock.chmod(0o600)
    authority_lock_identity = _authority_lock_identity(authority_lock)
    control = _key_control(state="COMPROMISED")
    control["authority_lock_identity_sha256"] = authority_lock_identity
    payload["backup_key"]["authority_lock_identity_sha256"] = authority_lock_identity
    control_document.write_text(json.dumps(control), encoding="utf-8")
    control_signature.write_bytes(b"control signature")
    decrypt_calls = 0

    monkeypatch.setattr(integrity, "verify_signed_backup_fd_bundle", lambda **_kwargs: payload)
    monkeypatch.setattr(
        integrity,
        "verify_signed_backup_key_control_fd_bundle",
        lambda **_kwargs: (control, "1" * 64),
    )
    monkeypatch.setattr(
        integrity,
        "verify_operational_backup_key_control",
        lambda *_args, **_kwargs: (control, "1" * 64),
    )

    def must_not_decrypt(*_args, **_kwargs):
        nonlocal decrypt_calls
        decrypt_calls += 1
        pytest.fail("compromised key must be blocked before decryption")

    monkeypatch.setattr(integrity, "_decrypt_to_inheritable_snapshot", must_not_decrypt)

    with pytest.raises(ValueError, match="decryption is blocked before GPG"):
        integrity._seal_and_exec_restore(
            backup_dir=backup_dir,
            restore_script=ROOT / "scripts" / "restore_walksafe_backup_drill_20260711.sh",
            trusted_signer_fingerprint=TRUSTED_SIGNER,
            key_control_document=control_document,
            key_control_signature=control_signature,
            key_control_authority_lock=authority_lock,
            trusted_key_control_signer_fingerprint=CONTROL_SIGNER,
            expected_key_control_sha256="1" * 64,
            previous_key_control_document=None,
            previous_key_control_signature=None,
            expected_previous_key_control_sha256=None,
            previous_validation_document=None,
            previous_validation_signature=None,
            trusted_validation_signer_fingerprint=None,
            before_rekey_root=None,
            after_rekey_root=None,
            trusted_rekey_manifest_signer_fingerprint=None,
            max_age_seconds=93600,
            future_skew_seconds=300,
            restore_arguments=[],
        )

    assert decrypt_calls == 0


def test_restore_snapshot_rejects_backup_directory_swap(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    backup_dir = tmp_path / "backup"
    replacement = tmp_path / "replacement"
    displaced = tmp_path / "displaced"
    backup_dir.mkdir()
    replacement.mkdir()
    _backup(backup_dir)
    _backup(replacement)
    control_document = tmp_path / "key-control.json"
    control_signature = tmp_path / "key-control.json.sig"
    control_document.write_text(json.dumps(_key_control()), encoding="utf-8")
    control_signature.write_bytes(b"control signature")
    authority_lock = tmp_path / "key-control.lock"
    authority_lock.write_bytes(b"lock")
    authority_lock.chmod(0o600)
    lock_bound_control = _key_control()
    lock_bound_control["authority_lock_identity_sha256"] = _authority_lock_identity(
        authority_lock
    )
    monkeypatch.setattr(
        integrity,
        "verify_operational_backup_key_control",
        lambda *_args, **_kwargs: (lock_bound_control, CONTROL_SHA256),
    )
    capture = integrity._capture_inheritable_snapshot
    calls = 0

    def capture_then_swap(*args, **kwargs):
        nonlocal calls
        descriptor = capture(*args, **kwargs)
        calls += 1
        if calls == 1:
            backup_dir.rename(displaced)
            replacement.rename(backup_dir)
        return descriptor

    monkeypatch.setattr(integrity, "_capture_inheritable_snapshot", capture_then_swap)
    with pytest.raises(ValueError, match="backup directory changed"):
        integrity._seal_and_exec_restore(
            backup_dir=backup_dir,
            restore_script=ROOT / "scripts" / "restore_walksafe_backup_drill_20260711.sh",
            trusted_signer_fingerprint=TRUSTED_SIGNER,
            key_control_document=control_document,
            key_control_signature=control_signature,
            key_control_authority_lock=authority_lock,
            trusted_key_control_signer_fingerprint=CONTROL_SIGNER,
            expected_key_control_sha256=CONTROL_SHA256,
            previous_key_control_document=None,
            previous_key_control_signature=None,
            expected_previous_key_control_sha256=None,
            previous_validation_document=None,
            previous_validation_signature=None,
            trusted_validation_signer_fingerprint=None,
            before_rekey_root=None,
            after_rekey_root=None,
            trusted_rekey_manifest_signer_fingerprint=None,
            max_age_seconds=93600,
            future_skew_seconds=300,
            restore_arguments=[],
        )


@pytest.mark.parametrize(
    "database_url",
    [
        "postgresql:///walksafe_drill",
        "postgresql://user@127.0.0.1/walksafe_drill",
        "postgresql://127.0.0.1:5432/walksafe_drill",
        "postgresql://user@127.0.0.1:5432/walksafe_drill?service=attacker",
        "postgresql://user@127.0.0.1:5432/walksafe_drill?hostaddr=203.0.113.1",
        "postgresql://user@db.example%2C127.0.0.1:5432/walksafe_drill?sslmode=verify-full&gssencmode=disable",
        "postgresql://user@%2Ftmp:5432/walksafe_drill?sslmode=verify-full&gssencmode=disable",
    ],
)
def test_restore_database_url_requires_explicit_unambiguous_target(database_url: str) -> None:
    with pytest.raises(ValueError, match="explicitly bind"):
        explicit_restore_database_url(database_url)


def test_restore_database_url_accepts_explicit_test_target() -> None:
    database_url = "postgresql://walksafe@127.0.0.1:55432/walksafe_drill?sslmode=disable"
    assert explicit_restore_database_url(database_url) == database_url


def test_restore_database_url_requires_verified_tls_for_remote_target() -> None:
    insecure = "postgresql://walksafe@db.example:5432/walksafe_drill?sslmode=require"
    with pytest.raises(ValueError, match="sslmode=verify-full"):
        explicit_restore_database_url(insecure)

    secure = (
        "postgresql://walksafe@db.example:5432/walksafe_drill"
        "?sslmode=verify-full&gssencmode=disable"
    )
    assert explicit_restore_database_url(secure) == secure


def test_restore_direct_launcher_ignores_bash_env(tmp_path: Path) -> None:
    marker = tmp_path / "bash-env-ran"
    bash_env = tmp_path / "attacker-bash-env"
    bash_env.write_text(f"/usr/bin/touch {marker}\n", encoding="utf-8")
    environment = os.environ.copy()
    environment["BASH_ENV"] = str(bash_env)

    completed = subprocess.run(
        [str(ROOT / "scripts" / "restore_walksafe_backup_drill_20260711.sh")],
        check=False,
        capture_output=True,
        text=True,
        env=environment,
    )

    assert completed.returncode == 2
    assert not marker.exists()


def test_restore_directory_publish_is_fd_anchored_and_no_replace(tmp_path: Path) -> None:
    parent = tmp_path / "private"
    parent.mkdir(mode=0o700)
    source = parent / ".walksafe-restore.source"
    source.mkdir(mode=0o700)
    (source / "upload.jpg").write_bytes(b"upload")
    descriptor = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
    source_descriptor = os.open(source, os.O_RDONLY | os.O_DIRECTORY)
    code = _restore_embedded_python("renameat2 = libc.renameat2")
    try:
        subprocess.run(
            [
                sys.executable,
                "-I",
                "-S",
                "-B",
                "-",
                str(descriptor),
                str(source_descriptor),
                source.name,
                "uploads",
            ],
            input=code,
            text=True,
            check=True,
            pass_fds=(descriptor, source_descriptor),
        )
        os.close(source_descriptor)
        assert (parent / "uploads" / "upload.jpg").read_bytes() == b"upload"

        second_source = parent / ".walksafe-restore.second"
        second_source.mkdir(mode=0o700)
        second_source_descriptor = os.open(second_source, os.O_RDONLY | os.O_DIRECTORY)
        failed = subprocess.run(
            [
                sys.executable,
                "-I",
                "-S",
                "-B",
                "-",
                str(descriptor),
                str(second_source_descriptor),
                second_source.name,
                "uploads",
            ],
            input=code,
            text=True,
            capture_output=True,
            check=False,
            pass_fds=(descriptor, second_source_descriptor),
        )
        os.close(second_source_descriptor)
        assert failed.returncode != 0
        assert second_source.is_dir()
        assert (parent / "uploads" / "upload.jpg").read_bytes() == b"upload"
    finally:
        try:
            os.close(source_descriptor)
        except OSError:
            pass
        os.close(descriptor)


def test_restore_published_tree_binding_detects_replacement_and_aba(tmp_path: Path) -> None:
    parent = tmp_path / "private"
    parent.mkdir(mode=0o700)
    target = parent / "uploads"
    target.mkdir(mode=0o700)
    (target / "upload.jpg").write_bytes(b"verified")
    displaced = parent / "verified-tree"
    replacement = parent / "replacement"
    replacement.mkdir(mode=0o700)
    probe = f"""
set -euo pipefail
export PATH=/usr/bin:/bin
TARGET_UPLOAD_PATH="$1"
displaced="$2"
replacement="$3"
exec {{RESTORE_TREE_FD}}<"${{TARGET_UPLOAD_PATH}}"
RESTORE_TREE_ANCHOR="/proc/self/fd/${{RESTORE_TREE_FD}}"
RESTORE_TREE_DEVICE_INODE="$(stat -Lc '%d:%i' "${{RESTORE_TREE_ANCHOR}}")"
RESTORE_TREE_STABLE_STATE="$(stat -Lc '%d:%i:%f:%u:%g:%s:%y:%z' "${{RESTORE_TREE_ANCHOR}}")"
UPLOAD_PUBLISHED=true
{_restore_shell_function("verify_target_upload_binding")}
verify_target_upload_binding
mv "${{TARGET_UPLOAD_PATH}}" "${{displaced}}"
mv "${{replacement}}" "${{TARGET_UPLOAD_PATH}}"
if verify_target_upload_binding; then exit 10; fi
mv "${{TARGET_UPLOAD_PATH}}" "${{replacement}}"
mv "${{displaced}}" "${{TARGET_UPLOAD_PATH}}"
if verify_target_upload_binding; then exit 11; fi
"""

    subprocess.run(
        ["/bin/bash", "-p", "-s", "--", str(target), str(displaced), str(replacement)],
        input=probe,
        text=True,
        check=True,
    )


def test_restore_upload_hash_detects_in_place_change_while_reading(tmp_path: Path) -> None:
    root = tmp_path / "uploads"
    root.mkdir(mode=0o700)
    image = root / "00000000-0000-0000-0000-000000000001.wse"
    original = b"a" * (2 * 1024 * 1024)
    image.write_bytes(original)
    code = _restore_embedded_python("def hash_open_envelope")
    namespace = {"__name__": "restore_upload_hash_test"}
    exec(compile(code, "<restore-upload-hash>", "exec"), namespace)
    directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    try:
        actual_hash, actual_size, _identity = namespace["hash_open_envelope"](
            directory_fd, image.name
        )
        assert actual_hash == hashlib.sha256(original).hexdigest()
        assert actual_size == len(original)

        changed = False

        def read_then_restore(descriptor: int, size: int) -> bytes:
            nonlocal changed
            chunk = os.read(descriptor, size)
            if not changed:
                changed = True
                image.write_bytes(b"b" * len(original))
                image.write_bytes(original)
            return chunk

        with pytest.raises(ValueError, match="upload file changed while hashing"):
            namespace["hash_open_envelope"](
                directory_fd,
                image.name,
                read_chunk=read_then_restore,
            )
        assert changed
    finally:
        os.close(directory_fd)


def test_restore_upload_snapshot_detects_change_restored_to_original_bytes(
    tmp_path: Path,
) -> None:
    root = tmp_path / "uploads"
    root.mkdir(mode=0o700)
    image = root / "00000000-0000-0000-0000-000000000001.wse"
    original = b"verified upload"
    image.write_bytes(original)
    rows = tmp_path / "report-images.csv"
    rows.write_text(
        "00000000-0000-0000-0000-000000000001,"
        f"{image.name},{hashlib.sha256(original).hexdigest()},{len(original)}\n",
        encoding="utf-8",
    )
    rows.chmod(0o600)
    code = _restore_embedded_python("def hash_open_envelope")
    namespace = {"__name__": "restore_upload_snapshot_test"}
    exec(compile(code, "<restore-upload-snapshot>", "exec"), namespace)
    directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    rows_fd = os.open(rows, os.O_RDONLY)
    rows.unlink()
    try:
        count, before_snapshot = namespace["verify_upload_tree"](directory_fd, rows_fd)
        metadata = image.stat()
        image.write_bytes(b"changed content")
        image.write_bytes(original)
        os.utime(image, ns=(metadata.st_atime_ns, metadata.st_mtime_ns))
        after_count, after_snapshot = namespace["verify_upload_tree"](directory_fd, rows_fd)
    finally:
        os.close(rows_fd)
        os.close(directory_fd)

    assert count == after_count == 1
    assert image.read_bytes() == original
    assert before_snapshot != after_snapshot


def test_restore_report_rows_fd_detects_change_restored_while_reading(tmp_path: Path) -> None:
    root = tmp_path / "uploads"
    root.mkdir(mode=0o700)
    image = root / "00000000-0000-0000-0000-000000000001.wse"
    image.write_bytes(b"verified upload")
    rows = tmp_path / "report-images.csv"
    report_payload = (
        "00000000-0000-0000-0000-000000000001,"
        f"{image.name},{hashlib.sha256(image.read_bytes()).hexdigest()},{image.stat().st_size}\n"
    ).encode()
    rows.write_bytes(report_payload)
    rows.chmod(0o600)
    code = _restore_embedded_python("def hash_open_envelope")
    namespace = {"__name__": "restore_report_rows_test"}
    exec(compile(code, "<restore-report-rows>", "exec"), namespace)
    directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    rows_fd = os.open(rows, os.O_RDWR)
    rows.unlink()
    original_read = os.read
    changed = False

    def read_then_restore(descriptor: int, size: int) -> bytes:
        nonlocal changed
        chunk = original_read(descriptor, size)
        if descriptor == rows_fd and chunk and not changed:
            changed = True
            os.pwrite(rows_fd, b"X", 0)
            os.pwrite(rows_fd, report_payload[:1], 0)
        return chunk

    namespace["os"].read = read_then_restore
    try:
        with pytest.raises(ValueError, match="report image rows changed while reading"):
            namespace["verify_upload_tree"](directory_fd, rows_fd)
        assert changed
    finally:
        namespace["os"].read = original_read
        os.close(rows_fd)
        os.close(directory_fd)


def test_restore_encrypted_object_validation_rejects_legacy_and_false_authority(
    tmp_path: Path,
) -> None:
    root = tmp_path / "uploads"
    root.mkdir(mode=0o700)
    report_id = "00000000-0000-0000-0000-000000000001"
    storage_name = f"{report_id}.wse"
    envelope = b"authenticated encrypted envelope"
    (root / storage_name).write_bytes(envelope)
    envelope_hash = hashlib.sha256(envelope).hexdigest()
    plaintext_hash = hashlib.sha256(b"legacy plaintext image").hexdigest()
    invalid_rows = (
        (
            f"{report_id},/uploads/original.jpg,{plaintext_hash}\n",
            "invalid restored report image object row",
        ),
        (
            f"{report_id},/uploads/original.jpg,{envelope_hash},{len(envelope)}\n",
            "storage name is invalid",
        ),
        (
            f"{report_id},{storage_name},{plaintext_hash},{len(envelope)}\n",
            "database envelope hash",
        ),
        (
            f"{report_id},{storage_name},{envelope_hash},{len(envelope) + 1}\n",
            "database envelope size",
        ),
    )
    code = _restore_embedded_python("def hash_open_envelope")
    namespace = {"__name__": "restore_envelope_authority_test"}
    exec(compile(code, "<restore-envelope-authority>", "exec"), namespace)
    directory_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY)
    try:
        for row, error in invalid_rows:
            rows = tmp_path / "report-image-object.csv"
            rows.write_text(row, encoding="utf-8")
            rows.chmod(0o600)
            rows_fd = os.open(rows, os.O_RDONLY)
            rows.unlink()
            try:
                with pytest.raises(ValueError, match=error):
                    namespace["verify_upload_tree"](directory_fd, rows_fd)
            finally:
                os.close(rows_fd)
    finally:
        os.close(directory_fd)


def test_restore_receipt_publish_is_fd_anchored_and_no_replace(tmp_path: Path) -> None:
    parent = tmp_path / "private"
    parent.mkdir(mode=0o700)
    receipt_source = parent / ".walksafe-receipt.source"
    signature_source = parent / ".walksafe-signature.source"
    receipt_source.write_bytes(b"receipt")
    signature_source.write_bytes(b"signature")
    receipt_source.chmod(0o600)
    signature_source.chmod(0o600)
    descriptor = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
    code = _restore_embedded_python("published = []")
    try:
        subprocess.run(
            [
                sys.executable,
                "-I",
                "-S",
                "-B",
                "-",
                str(descriptor),
                receipt_source.name,
                signature_source.name,
                "restore.json",
            ],
            input=code,
            text=True,
            check=True,
            pass_fds=(descriptor,),
        )
        assert (parent / "restore.json").read_bytes() == b"receipt"
        assert (parent / "restore.json.sig").read_bytes() == b"signature"
        assert not receipt_source.exists()
        assert not signature_source.exists()

        second_receipt = parent / ".walksafe-receipt.second"
        second_signature = parent / ".walksafe-signature.second"
        second_receipt.write_bytes(b"replacement")
        second_signature.write_bytes(b"replacement signature")
        second_receipt.chmod(0o600)
        second_signature.chmod(0o600)
        failed = subprocess.run(
            [
                sys.executable,
                "-I",
                "-S",
                "-B",
                "-",
                str(descriptor),
                second_receipt.name,
                second_signature.name,
                "restore.json",
            ],
            input=code,
            text=True,
            capture_output=True,
            check=False,
            pass_fds=(descriptor,),
        )
        assert failed.returncode != 0
        assert (parent / "restore.json").read_bytes() == b"receipt"
        assert second_receipt.read_bytes() == b"replacement"
        assert second_signature.read_bytes() == b"replacement signature"
    finally:
        os.close(descriptor)


def test_restore_drill_uses_signed_hashes_and_signs_its_receipt() -> None:
    script = (ROOT / "scripts" / "restore_walksafe_backup_drill_20260711.sh").read_text(encoding="utf-8")

    assert "walksafe_backup_integrity.py" in script
    assert 'BACKUP_RUNTIME_PYTHON="/usr/bin/python3.14"' in script
    assert '"${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_backup_integrity.py"' in script
    assert "--runtime-capability-preflight" in script
    assert "python3 -I -S -B" not in script
    assert "sha256sum --check" not in script
    assert script.startswith("#!/bin/bash -p\n")
    assert "unset BASH_ENV ENV CDPATH GLOBIGNORE TAR_OPTIONS" in script
    assert "--verify-inherited-restore" in script
    assert "--key-control-document" in script
    assert "--key-control-signature" in script
    assert "--key-control-authority-lock" in script
    assert "--trusted-key-control-signer-fingerprint" in script
    assert "--expected-key-control-sha256" in script
    assert '--key-control-document-fd "${KEY_CONTROL_DOCUMENT_FD}"' in script
    assert '"compromised_key_decrypt_block_enforced": true' in script
    assert '--decrypted-reports-fd "${DECRYPTED_REPORTS_FD}"' in script
    assert '--decrypted-uploads-fd "${DECRYPTED_UPLOADS_FD}"' in script
    assert '--age-policy-fd "${BACKUP_AGE_POLICY_FD}"' in script
    assert '--max-age-seconds "${MAX_BACKUP_AGE_SECONDS}"' in script
    assert '--future-skew-seconds "${BACKUP_FUTURE_SKEW_SECONDS}"' in script
    assert 'REPORTS_RESTORE_PATH="/proc/self/fd/${DECRYPTED_REPORTS_FD}"' in script
    assert 'UPLOADS_RESTORE_PATH="/proc/self/fd/${DECRYPTED_UPLOADS_FD}"' in script
    assert '${BACKUP_DIR}/reports.dump.gpg' not in script
    assert "gpg --batch --decrypt" not in script
    assert "--receipt-gpg-signer" in script
    assert 'detach-sign --local-user "${RECEIPT_SIGNER_FINGERPRINT}"' in script
    assert "validate-private-restore-output" in script
    assert "--target-database-url" not in script
    assert 'TARGET_DATABASE_URL="${TARGET_DATABASE_URL:-}"' in script
    assert "validate-restore-database-url --database-url" not in script
    assert "database-name --database-url" not in script
    assert "database-identity --database-url" not in script
    assert 'env -i PATH=/usr/bin:/bin DATABASE_URL="${TARGET_DATABASE_URL}"' in script
    assert 'TARGET_UPLOAD_PARENT_ANCHOR="/proc/self/fd/${TARGET_UPLOAD_PARENT_FD}"' in script
    assert 'RESTORE_TREE_ANCHOR="/proc/self/fd/${RESTORE_TREE_FD}"' in script
    assert "verify_target_upload_binding" in script
    assert script.count("verify_target_upload_binding ||") >= 4
    assert "WALKSAFE_VERIFIED_BACKUP_AUTHORITY_LOCK_FD" in script
    assert script.count("verify_inherited_authority_lock_binding ||") >= 5
    assert "def hash_open_envelope" in script
    assert "read_chunk(descriptor, 1024 * 1024)" in script
    assert "after = os.fstat(descriptor)" in script
    assert 'os.stat(filename, dir_fd=directory_fd, follow_symlinks=False)' in script
    assert 'exec {REPORT_IMAGE_ROWS_FD}<>"${REPORT_IMAGE_ROWS}"' in script
    assert 'rm -f "${REPORT_IMAGE_ROWS}"' in script
    assert "os.lseek(report_rows_fd, 0, os.SEEK_SET)" in script
    assert "report_before = os.fstat(report_rows_fd)" in script
    assert "report_before.st_nlink != 0" in script
    assert script.count("verify_restored_upload_snapshot_unchanged ||") == 2
    assert "renameat2" in script
    assert "os.link(" in script
    first_verification = script.index('VERIFIED_BACKUP_FIELDS="$(verify_inherited_backup)"')
    restore = script.index('run_restore_postgres_client pg_restore --exit-on-error')
    receipt = script.index('cat > "${RECEIPT_TEMP}"')
    successful_completion = script.rindex("trap - EXIT")
    assert first_verification < restore < receipt < successful_completion
    assert 'mktemp -d "${TARGET_UPLOAD_PARENT_ANCHOR}/.walksafe-restore.XXXXXX"' in script
    assert 'rm -rf "${TARGET_UPLOAD_PATH}"' in script
    assert 'os.fsync(stream.fileno())' in script
    assert "metadata->>'image_sha256'" not in script
    assert "SELECT id::text, image_path" not in script
    assert "FROM reports LEFT JOIN report_image_objects AS objects" in script
    assert "objects.storage_name" in script
    assert "objects.envelope_sha256" in script
    assert "objects.envelope_size" in script
    assert '"source_table": "report_image_objects"' in script
    assert '"reports_image_path_role": "logical_api_routing_only_not_restore_file_authority"' in script
    assert '"plaintext_digest_used_for_restore_validation": false' in script
    assert "sha256sum" not in script
    assert '"missing_image_hash_count": ${MISSING_IMAGE_HASH_COUNT}' in script
    assert '"image_hash_mismatch_count": ${IMAGE_HASH_MISMATCH_COUNT}' in script
    assert '"envelope_hash_mismatch_count": ${ENVELOPE_HASH_MISMATCH_COUNT}' in script
    assert '"envelope_size_mismatch_count": ${ENVELOPE_SIZE_MISMATCH_COUNT}' in script
    assert '"restore_upload_snapshot_sha256": "${RESTORE_UPLOAD_SNAPSHOT_SHA256}"' in script
    assert '"restore_tree_device_inode": "${RESTORE_TREE_DEVICE_INODE}"' in script
    assert "MISSING_IMAGE_HASH_COUNT=0" in script
    assert "IMAGE_HASH_MISMATCH_COUNT=0" in script
    assert "WITH canonical_context AS MATERIALIZED" in script
    assert "postgis_direct(classid, objid) AS" in script
    assert "extension.extname = 'postgis'" in script
    assert "postgis\\_%" not in script
    assert "WITH RECURSIVE" not in script
    assert "postgis_owned" not in script
    assert "dependency.deptype IN ('a', 'i')" not in script
    assert "FROM postgis_seed owned" in script
    assert "extension.extname NOT IN ('plpgsql', 'postgis')" in script
    assert "'pg_catalog.pg_extension'::pg_catalog.regclass" in script
    assert "installation.extversion <> '3.5.2'" in script
    assert "installation.server_major <> 16" in script
    assert "installation.member_count <> 894" in script
    assert "6ce2e8000c7cf99dd9812b96266cddf89aa81f567b92f207a7e4cc0b600ee424" in script
    assert "(SELECT count(*) FROM postgis_seed) <> 899" in script
    assert "definition_count <> 754" in script
    assert "dc9b06f1a12a9ba54c78764a3eb08bbade97ea1f47f7e1fdab6a2cb112eb6f8e" in script
    assert "definition_count <> 2" in script
    assert "6aa19e1a67d888f81b102dc449b0cfa3797f4f458bffbe4fa3a3f8e888008445" in script
    assert "b6fab382e48770a278cfa57a91652e6733e349dd879aaaf7394808afcc9eb992" in script
    assert "expected_postgis_relation_signatures" in script
    assert "unexpected_postgis_relation_signatures" in script
    assert "'pg_catalog.pg_proc'::pg_catalog.regclass" in script
    assert "'pg_catalog.pg_type'::pg_catalog.regclass" in script
    assert "'pg_catalog.pg_namespace'::pg_catalog.regclass" in script
    assert "relation.relkind IN ('r', 'p', 'i', 'I', 'S', 'v', 'm', 'c', 'f')" in script
    assert "type.typrelid = 0 AND type.typelem = 0" in script
    assert "object.rulename <> '_RETURN'" in script
    assert "TARGET_USER_OBJECT_COUNT" in script
    assert "DROP SCHEMA public CASCADE" not in script
    empty_proof = script.index('TARGET_USER_OBJECT_COUNT="$(run_restore_postgres_client psql')
    restore = script.index("run_restore_postgres_client pg_restore --exit-on-error")
    assert empty_proof < restore


def test_backup_direct_launcher_ignores_bash_env_and_hostile_path(tmp_path: Path) -> None:
    bash_env_marker = tmp_path / "bash-env-ran"
    path_marker = tmp_path / "hostile-path-ran"
    bash_env = tmp_path / "attacker-bash-env"
    bash_env.write_text(f"/usr/bin/touch {bash_env_marker}\n", encoding="utf-8")
    hostile_bin = tmp_path / "bin"
    hostile_bin.mkdir()
    hostile_dirname = hostile_bin / "dirname"
    hostile_dirname.write_text(
        f"#!/bin/sh\n/usr/bin/touch {path_marker}\nexit 99\n",
        encoding="utf-8",
    )
    hostile_dirname.chmod(0o755)
    environment = os.environ.copy()
    environment["BASH_ENV"] = str(bash_env)
    environment["PATH"] = str(hostile_bin)

    completed = subprocess.run(
        [str(ROOT / "scripts" / "backup_walksafe_data_20260711.sh")],
        check=False,
        capture_output=True,
        text=True,
        env=environment,
    )

    assert completed.returncode == 2
    assert not bash_env_marker.exists()
    assert not path_marker.exists()


def test_backup_directory_publish_is_fd_anchored_and_no_replace(tmp_path: Path) -> None:
    output = tmp_path / "output"
    output.mkdir(mode=0o700)
    code = _backup_embedded_python("renameat2 = libc.renameat2")

    def staging(name: str, content: bytes) -> Path:
        path = output / name
        path.mkdir(mode=0o700)
        for artifact in (
            "reports.dump.gpg",
            "uploads.tar.gz.gpg",
            "manifest.json",
            "manifest.json.sig",
        ):
            target = path / artifact
            target.write_bytes(content + artifact.encode())
            target.chmod(0o600)
        return path

    output_fd = os.open(output, os.O_RDONLY | os.O_DIRECTORY)
    first = staging(".first", b"first")
    first_fd = os.open(first, os.O_RDONLY | os.O_DIRECTORY)
    try:
        subprocess.run(
            [
                sys.executable,
                "-I",
                "-S",
                "-B",
                "-",
                str(output_fd),
                str(first_fd),
                first.name,
                "walksafe-backup-first",
            ],
            input=code,
            text=True,
            check=True,
            pass_fds=(output_fd, first_fd),
        )
    finally:
        os.close(first_fd)
    assert (output / "walksafe-backup-first" / "manifest.json").is_file()

    competitor = output / "walksafe-backup-competitor"
    competitor.mkdir(mode=0o700)
    (competitor / "sentinel").write_text("keep", encoding="utf-8")
    second = staging(".second", b"second")
    second_fd = os.open(second, os.O_RDONLY | os.O_DIRECTORY)
    try:
        failed = subprocess.run(
            [
                sys.executable,
                "-I",
                "-S",
                "-B",
                "-",
                str(output_fd),
                str(second_fd),
                second.name,
                competitor.name,
            ],
            input=code,
            text=True,
            capture_output=True,
            check=False,
            pass_fds=(output_fd, second_fd),
        )
    finally:
        os.close(second_fd)
        os.close(output_fd)

    assert failed.returncode != 0
    assert second.is_dir()
    assert (competitor / "sentinel").read_text(encoding="utf-8") == "keep"
    assert not (competitor / second.name).exists()


def test_lock_parent_state_detects_path_swap_even_after_original_is_restored(
    tmp_path: Path,
) -> None:
    parent = tmp_path / "locks"
    parent.mkdir(mode=0o700)
    lock = parent / "walksafe.lock"
    lock.touch(mode=0o600)
    parent_fd = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
    lock_fd = os.open(lock, os.O_RDONLY)
    contender_fd = os.open(lock, os.O_RDONLY)
    try:
        fcntl.flock(lock_fd, fcntl.LOCK_EX)
        with pytest.raises(BlockingIOError):
            fcntl.flock(contender_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        before = os.fstat(parent_fd)
        displaced = parent / "original.lock"
        lock.rename(displaced)
        lock.touch(mode=0o600)
        lock.unlink()
        displaced.rename(lock)
        os.utime(parent, ns=(before.st_atime_ns, before.st_mtime_ns))

        assert os.stat(lock).st_ino == os.fstat(lock_fd).st_ino
        assert os.fstat(parent_fd).st_ctime_ns != before.st_ctime_ns
    finally:
        fcntl.flock(lock_fd, fcntl.LOCK_UN)
        os.close(contender_fd)
        os.close(lock_fd)
        os.close(parent_fd)


def test_backup_lock_authority_rejects_user_owned_higher_ancestor(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    service_directory = tmp_path / "service"
    trusted_immediate_ancestor = service_directory / "trusted"
    lock_parent = trusted_immediate_ancestor / "maintenance"
    lock_parent.mkdir(parents=True, mode=0o700)
    service_directory.chmod(0o700)
    trusted_immediate_ancestor.chmod(0o755)
    higher_ancestor_identity = (service_directory.stat().st_dev, service_directory.stat().st_ino)
    authority_paths = [Path("/")]
    for component in trusted_immediate_ancestor.relative_to("/").parts:
        authority_paths.append(authority_paths[-1] / component)
    simulated_safe_paths = set(authority_paths) - {service_directory}
    simulated_safe_identities = {
        (path.stat().st_dev, path.stat().st_ino) for path in simulated_safe_paths
    }
    visited_identities: list[tuple[int, int]] = []
    real_fstat = os.fstat
    real_access = os.access

    def only_higher_ancestor_remains_user_owned(descriptor: int) -> os.stat_result:
        metadata = real_fstat(descriptor)
        identity = (metadata.st_dev, metadata.st_ino)
        visited_identities.append(identity)
        if identity not in simulated_safe_identities:
            return metadata
        fields = list(metadata)
        fields[0] = (fields[0] & ~0o7777) | 0o755
        fields[4] = 0
        return os.stat_result(fields)

    def simulated_safe_ancestors_appear_non_writable(
        path: str,
        mode: int,
        *,
        dir_fd: int | None = None,
        effective_ids: bool = False,
        follow_symlinks: bool = True,
    ) -> bool:
        if dir_fd is not None:
            metadata = real_fstat(dir_fd)
            if (metadata.st_dev, metadata.st_ino) in simulated_safe_identities:
                return False
        if dir_fd is None and Path(path) in simulated_safe_paths:
            return False
        return real_access(
            path,
            mode,
            dir_fd=dir_fd,
            effective_ids=effective_ids,
            follow_symlinks=follow_symlinks,
        )

    parent_fd = os.open(lock_parent, os.O_RDONLY | os.O_DIRECTORY)
    monkeypatch.setattr(os, "fstat", only_higher_ancestor_remains_user_owned)
    monkeypatch.setattr(os, "access", simulated_safe_ancestors_appear_non_writable)
    monkeypatch.setattr(
        sys,
        "argv",
        ["embedded-backup", str(parent_fd), "walksafe.lock", str(lock_parent), "-1"],
    )
    try:
        with pytest.raises(SystemExit, match="authority ancestors"):
            exec(
                compile(
                    _backup_embedded_python("authority_fds = []"),
                    "<backup>",
                    "exec",
                ),
                {},
            )
        assert higher_ancestor_identity in visited_identities
    finally:
        os.close(parent_fd)


def test_backup_lock_authority_accepts_provisioned_read_only_group_leaf(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    expected_gid = os.getegid()
    if os.geteuid() == 0 or expected_gid == 0:
        pytest.skip("requires a non-root service user and group")
    lock_parent = tmp_path / "maintenance"
    lock_parent.mkdir(mode=0o750)
    lock = lock_parent / "walksafe.lock"
    lock.touch(mode=0o440)
    authority_paths = [Path("/")]
    for component in lock_parent.parent.relative_to("/").parts:
        authority_paths.append(authority_paths[-1] / component)
    authority_identities = {
        (path.stat().st_dev, path.stat().st_ino) for path in authority_paths
    }
    parent_identity = (lock_parent.stat().st_dev, lock_parent.stat().st_ino)
    leaf_identity = (lock.stat().st_dev, lock.stat().st_ino)
    real_fstat = os.fstat
    real_stat = os.stat
    real_listxattr = os.listxattr

    def provisioned(metadata: os.stat_result) -> os.stat_result:
        identity = (metadata.st_dev, metadata.st_ino)
        if identity not in authority_identities | {parent_identity, leaf_identity}:
            return metadata
        fields = list(metadata)
        fields[4] = 0
        if identity in authority_identities:
            fields[0] = (fields[0] & ~0o7777) | 0o755
        return os.stat_result(fields)

    def provisioned_fstat(descriptor: int) -> os.stat_result:
        return provisioned(real_fstat(descriptor))

    def provisioned_stat(
        path: os.PathLike[str] | str,
        *args: object,
        **kwargs: object,
    ) -> os.stat_result:
        return provisioned(real_stat(path, *args, **kwargs))

    parent_fd = os.open(lock_parent, os.O_RDONLY | os.O_DIRECTORY)
    monkeypatch.setattr(os, "fstat", provisioned_fstat)
    monkeypatch.setattr(os, "stat", provisioned_stat)
    monkeypatch.setattr(os, "access", lambda *_args, **_kwargs: False)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "embedded-backup",
            str(parent_fd),
            lock.name,
            str(lock_parent),
            str(expected_gid),
        ],
    )
    code = compile(
        _backup_embedded_python("authority_fds = []"),
        "<backup>",
        "exec",
    )
    try:
        exec(code, {})
        monkeypatch.setattr(
            os,
            "listxattr",
            lambda descriptor: (
                [b"system.posix_acl_access"]
                if (real_fstat(descriptor).st_dev, real_fstat(descriptor).st_ino)
                == leaf_identity
                else []
            ),
        )
        with pytest.raises(SystemExit, match="owner/group/mode contract"):
            exec(code, {})
        monkeypatch.setattr(os, "listxattr", real_listxattr)
        lock.unlink()
        with pytest.raises(FileNotFoundError):
            exec(code, {})
        assert not lock.exists()
    finally:
        os.close(parent_fd)


def test_backup_lock_authority_accepts_standard_user_runtime(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime_parent = Path(f"/run/user/{os.geteuid()}")
    if (
        os.geteuid() == 0
        or not runtime_parent.is_dir()
        or runtime_parent.stat().st_uid != os.geteuid()
        or runtime_parent.stat().st_mode & 0o777 != 0o700
    ):
        pytest.skip("requires the standard non-root /run/user/$UID runtime directory")
    lock_name = f"walksafe-backup-authority-test-{os.getpid()}.lock"
    lock_path = runtime_parent / lock_name
    if lock_path.exists() or lock_path.is_symlink():
        pytest.skip("runtime lock test path is already occupied")
    lock_path.touch(mode=0o600)
    parent_fd = os.open(runtime_parent, os.O_RDONLY | os.O_DIRECTORY)
    monkeypatch.setattr(
        sys,
        "argv",
        ["embedded-backup", str(parent_fd), lock_name, str(runtime_parent), "-1"],
    )
    try:
        exec(
            compile(
                _backup_embedded_python("authority_fds = []"),
                "<backup>",
                "exec",
            ),
            {},
        )
        assert lock_path.stat().st_mode & 0o777 == 0o600
    finally:
        lock_path.unlink(missing_ok=True)
        os.close(parent_fd)


def test_backup_lock_authority_rejects_mismatched_parent_descriptor(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    runtime_parent = Path(f"/run/user/{os.geteuid()}")
    if (
        os.geteuid() == 0
        or not runtime_parent.is_dir()
        or runtime_parent.stat().st_uid != os.geteuid()
        or runtime_parent.stat().st_mode & 0o777 != 0o700
    ):
        pytest.skip("requires the standard non-root /run/user/$UID runtime directory")
    other_parent = runtime_parent / f"walksafe-backup-parent-test-{os.getpid()}"
    if other_parent.exists() or other_parent.is_symlink():
        pytest.skip("runtime parent test path is already occupied")
    other_parent.mkdir(mode=0o700)
    parent_fd = os.open(other_parent, os.O_RDONLY | os.O_DIRECTORY)
    monkeypatch.setattr(
        sys,
        "argv",
        ["embedded-backup", str(parent_fd), "walksafe.lock", str(runtime_parent), "-1"],
    )
    try:
        with pytest.raises(SystemExit, match="ancestry changed"):
            exec(
                compile(
                    _backup_embedded_python("authority_fds = []"),
                    "<backup>",
                    "exec",
                ),
                {},
            )
    finally:
        os.close(parent_fd)
        other_parent.rmdir()


def test_backup_script_rejects_nested_output_and_fsyncs_completed_artifacts() -> None:
    script = (ROOT / "scripts" / "backup_walksafe_data_20260711.sh").read_text(encoding="utf-8")

    assert "backup output root must not be inside the upload source tree" in script
    assert 'OUTPUT_ABSOLUTE="$(realpath -m "${OUTPUT_DIR}")"' in script
    assert script.startswith("#!/bin/bash -p\n")
    assert "unset BASH_ENV ENV CDPATH GLOBIGNORE TAR_OPTIONS PYTHONPATH" in script
    assert 'BACKUP_RUNTIME_PYTHON="/usr/bin/python3.14"' in script
    assert "--runtime-capability-preflight" in script
    assert "python3 -I -S -B" not in script
    assert "validate-backup-database-url" in script
    assert "validate-backup-database-url --database-url" not in script
    assert "database-identity --database-url" not in script
    assert 'env -i PATH=/usr/bin:/bin DATABASE_URL="${PG_DUMP_DATABASE_URL}"' in script
    assert 'run_backup_postgres_client pg_dump --format=custom' in script
    assert "PG_DUMP_CLIENT_MAJOR" in script
    assert "SOURCE_SERVER_VERSION_NUM" in script
    assert '--upload-dir-fd "${UPLOAD_FD}"' in script
    assert '--archive-output --result-fd "${SOURCE_RESULT_FD}"' in script
    assert 'tar --create' not in script
    assert "renameat2" in script
    assert 'mv "${TEMP_DIR}" "${FINAL_DIR}"' not in script
    assert "os.fsync(descriptor)" in script
    assert "os.fsync(output_fd)" in script
    assert "LOCK_PARENT_STABLE_STATE" in script
    assert script.count('LOCK_PARENT_STABLE_STATE="$(stat -Lc') == 1
    assert "LOCK_STABLE_STATE" in script
    assert script.count('LOCK_STABLE_STATE="$(stat -Lc') == 1
    assert "opened_authority.st_uid != 0" in script
    assert 'authority_fds.append(os.open("/", flags))' in script
    assert 'for component in parent.parent.relative_to("/").parts:' in script
    assert "dir_fd=authority_fd" in script
    assert "opened_parent.st_uid != os.geteuid()" in script
    assert "stat.S_IMODE(opened_parent.st_mode) != 0o700" in script
    assert "opened_parent.st_uid != 0" in script
    assert "opened_parent.st_gid != expected_gid" in script
    assert "stat.S_IMODE(opened_parent.st_mode) != 0o750" in script
    assert "stat.S_IMODE(metadata.st_mode) != expected_mode" in script
    assert "descriptor_acl_is_absent" in script
    assert "validate_lock_descriptor_acls" in script
    assert "os.listxattr" in script
    assert "if os.geteuid() == 0:" in script
    assert 'exec {LOCK_FD}<"${LOCK_PATH}"' in script
    assert "os.O_CREAT" not in script
    assert 'flock --exclusive --timeout "${LOCK_TIMEOUT_SECONDS}" "${LOCK_FD}"' in script
    assert 'flock --exclusive --timeout "${LOCK_TIMEOUT_SECONDS}" "${LOCK_PARENT_FD}"' not in script
    assert 'LOCK_AUTHORITY_DEVICE_INODE="${LOCK_DEVICE_INODE}"' in script
    assert '"maintenance_lock_device_inode": "${LOCK_AUTHORITY_DEVICE_INODE}"' in script
    assert 'MAINTENANCE_LOCK_GROUP="${WALKSAFE_MAINTENANCE_LOCK_GROUP:-}"' in script
    assert 'UPLOAD_BACKUP_READER_GROUP="${WALKSAFE_UPLOAD_BACKUP_READER_GROUP:-}"' in script
    assert "grp.getgrnam" in script
    assert 'pwd.getpwnam("walksafe-backup")' in script
    assert 'os.geteuid() != backup_account.pw_uid' in script
    assert 'effective_groups != {primary_gid, lock_gid, reader_gid}' in script
    assert "issubset(effective_groups)" not in script
    assert 'SOURCE_GROUP_ARGUMENTS+=(--upload-reader-gid "${UPLOAD_BACKUP_READER_GID}")' in script
    assert "--authorize-backup-key" in script
    assert 'flock --shared --timeout "${LOCK_TIMEOUT_SECONDS}" "${KEY_CONTROL_AUTHORITY_LOCK_FD}"' in script
    assert script.count('[[ "$(authorize_backup_key)" == "${KEY_AUTHORIZATION_FIELDS}" ]]') == 2
    assert '"schema_version": "walksafe.backup-key-binding.v1"' in script
    assert '"schema_version": "walksafe.backup-impact-inventory.v1"' in script
    assert '"key_id": "${BACKUP_KEY_ID}"' in script
    assert '"control_sha256": "${KEY_CONTROL_SHA256}"' in script
    assert '"authority_lock_identity_sha256": "${KEY_CONTROL_AUTHORITY_LOCK_IDENTITY_SHA256}"' in script
    assert "backup key control must be stored outside backup data boundaries" in script
    assert "stat -Lc '%d:%i:%u:%g:%a:%h:%y:%z'" in script
    acquired = script.index('flock --exclusive --timeout "${LOCK_TIMEOUT_SECONDS}"')
    before_snapshot = script.index('verify_lock_binding || { echo "maintenance lock changed before snapshot"')
    source_check = script.index('SOURCE_CONSISTENCY_JSON="$(check_source_consistency)"')
    post_snapshot = script.index(
        'verify_lock_binding || { echo "maintenance lock changed while creating the snapshot"'
    )
    unlock = script.index('flock --unlock "${LOCK_FD}"')
    assert acquired < before_snapshot < source_check < post_snapshot < unlock
