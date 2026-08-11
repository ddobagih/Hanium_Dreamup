from __future__ import annotations

from datetime import UTC, datetime
from contextlib import contextmanager
import json
import os
from pathlib import Path

import pytest

import scripts.prune_walksafe_backups_20260711 as prune
import scripts.walksafe_backup_integrity as integrity


def _backup(root: Path, name: str, created_at: str) -> Path:
    directory = root / name
    directory.mkdir()
    (directory / "manifest.json").write_text(
        json.dumps({"schema_version": "walksafe.backup.v1", "created_at": created_at}),
        encoding="utf-8",
    )
    return directory


def _controlled_backup(root: Path, name: str, created_at: str) -> Path:
    directory = _backup(root, name, created_at)
    (directory / "manifest.json").write_text(
        json.dumps(
            {
                "schema_version": "walksafe.backup.v1",
                "run_id": name,
                "created_at": created_at,
                "recipient_fingerprint": "b" * 40,
                "signer_fingerprint": "a" * 40,
                "database_identity_sha256": "c" * 64,
                "upload_root_identity_sha256": "d" * 64,
                "encryption_at_rest": "openpgp",
                "backup_key": {
                    "schema_version": "walksafe.backup-key-binding.v1",
                    "key_id": "backup-key-1",
                    "key_version": 1,
                    "recipient_fingerprint": "b" * 40,
                    "control_id": "backup-control",
                    "control_revision": 1,
                    "control_sha256": "f" * 64,
                    "control_signer_fingerprint": "e" * 40,
                    "authority_lock_identity_sha256": "9" * 64,
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
                "artifacts_sha256": {
                    "reports.dump.gpg": "1" * 64,
                    "uploads.tar.gz.gpg": "2" * 64,
                },
            }
        ),
        encoding="utf-8",
    )
    return directory


def _compromised_control() -> dict[str, object]:
    return {
        "schema_version": "walksafe.backup-key-control.v1",
        "control_id": "backup-control",
        "revision": 2,
        "issued_at": "2026-07-11T00:00:00Z",
        "control_signer_fingerprint": "e" * 40,
        "authority_lock_identity_sha256": "9" * 64,
        "predecessor_validation_sha256": "8" * 64,
        "data_boundary_id": "backup-data",
        "key_boundary_id": "backup-key-custody",
        "history": [{"revision": 1, "control_sha256": "f" * 64}],
        "keys": [
            {
                "key_id": "backup-key-1",
                "key_version": 1,
                "recipient_fingerprint": "b" * 40,
                "state": "COMPROMISED",
                "predecessor_key_id": None,
                "activated_at": "2026-07-01T00:00:00Z",
                "state_changed_at": "2026-07-10T00:00:00Z",
                "state_event_id": "compromise-event-1",
                "incident_id": "incident-1",
            }
        ],
        "transition": {
            "transition_id": "compromise-1",
            "kind": "COMPROMISE",
            "from_key_id": "backup-key-1",
            "to_key_id": None,
            "rekey_status": "NOT_RUN",
            "rekey_source_inventory_sha256": None,
            "rekey_inventory_sha256": None,
        },
    }


def test_backup_prune_keeps_minimum_recent_copies_and_is_dry_run_by_default(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    newest = _backup(tmp_path, "walksafe-backup-new", "2026-07-10T00:00:00Z")
    old = _backup(tmp_path, "walksafe-backup-old", "2026-01-01T00:00:00Z")
    monkeypatch.setattr(
        prune,
        "verify_signed_backup_manifest",
        lambda manifest, **_kwargs: json.loads(manifest.read_text(encoding="utf-8")),
    )
    authorized: list[str] = []

    @contextmanager
    def allow(action: str, **_kwargs):
        authorized.append(action)
        yield None

    monkeypatch.setattr(
        prune,
        "walksafe_admin_high_risk_operation",
        allow,
    )

    plan = prune.plan_backup_prune(
        tmp_path,
        trusted_signer_fingerprint="a" * 40,
        retention_days=30,
        minimum_copies=1,
        now=datetime(2026, 7, 11, tzinfo=UTC),
    )

    assert plan["delete_candidates"] == [old.name]
    assert plan["retained"] == [newest.name]
    assert plan["destructive_action"] is False
    assert old.exists()

    applied = prune.apply_backup_prune(tmp_path, plan)
    assert authorized == ["DATA_DELETE"]
    assert applied["deleted"] == [old.name]
    assert newest.exists()
    assert not old.exists()


def test_backup_prune_uses_strict_35_day_retention_boundary(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    newest = _backup(tmp_path, "walksafe-backup-new", "2026-07-11T00:00:00Z")
    age_34 = _backup(tmp_path, "walksafe-backup-age-34", "2026-06-07T00:00:00Z")
    age_35 = _backup(tmp_path, "walksafe-backup-age-35", "2026-06-06T00:00:00Z")
    age_36 = _backup(tmp_path, "walksafe-backup-age-36", "2026-06-05T00:00:00Z")
    monkeypatch.setattr(
        prune,
        "verify_signed_backup_manifest",
        lambda manifest, **_kwargs: json.loads(manifest.read_text(encoding="utf-8")),
    )

    plan = prune.plan_backup_prune(
        tmp_path,
        trusted_signer_fingerprint="a" * 40,
        retention_days=35,
        minimum_copies=1,
        now=datetime(2026, 7, 11, tzinfo=UTC),
    )

    assert plan["delete_candidates"] == [age_36.name]
    assert plan["retained"] == [newest.name, age_34.name, age_35.name]
    assert plan["destructive_action"] is False
    assert all(path.exists() for path in (newest, age_34, age_35, age_36))


@pytest.mark.parametrize(
    ("extra_arguments", "expected_retention_days"),
    [
        ([], 35),
        (["--retention-days", "21"], 21),
    ],
)
def test_backup_prune_cli_default_and_explicit_retention_are_dry_run(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    extra_arguments: list[str],
    expected_retention_days: int,
) -> None:
    captured: list[dict[str, object]] = []

    def fake_plan(backup_root: Path, **kwargs: object) -> dict[str, object]:
        captured.append({"backup_root": backup_root, **kwargs})
        return {"destructive_action": False, "delete_candidates": []}

    monkeypatch.setattr(prune, "require_backup_runtime_capabilities", lambda: None)
    monkeypatch.setattr(prune, "plan_backup_prune", fake_plan)
    monkeypatch.setattr(
        prune,
        "apply_backup_prune",
        lambda *_args, **_kwargs: pytest.fail("default CLI invocation must remain dry-run"),
    )
    monkeypatch.setattr(
        prune.sys,
        "argv",
        [
            "prune_walksafe_backups_20260711.py",
            "--backup-root",
            str(tmp_path),
            "--trusted-signer-fingerprint",
            "a" * 40,
            *extra_arguments,
        ],
    )

    assert prune.main() == 0
    assert captured[0]["retention_days"] == expected_retention_days
    assert json.loads(capsys.readouterr().out)["destructive_action"] is False


def test_backup_prune_denial_happens_before_any_delete(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    old = _backup(tmp_path, "walksafe-backup-old", "2026-01-01T00:00:00Z")
    plan = {
        "delete_candidates": [old.name],
        "trusted_signer_fingerprint": "a" * 40,
    }

    @contextmanager
    def deny(*_args: object, **_kwargs: object):
        raise RuntimeError("administrator high-risk operation is frozen")
        yield

    monkeypatch.setattr(
        prune,
        "walksafe_admin_high_risk_operation",
        deny,
    )
    monkeypatch.setattr(
        prune.shutil,
        "rmtree",
        lambda _path: pytest.fail("delete must not start before authorization"),
    )
    monkeypatch.setattr(
        prune,
        "verify_signed_backup_manifest",
        lambda manifest, **_kwargs: json.loads(manifest.read_text(encoding="utf-8")),
    )

    with pytest.raises(RuntimeError, match="operation is frozen"):
        prune.apply_backup_prune(tmp_path, plan)

    assert old.exists()


def test_compromised_backup_key_builds_impact_then_freezes_prune_for_legal_review(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    old = _controlled_backup(
        tmp_path,
        "walksafe-backup-old",
        "2026-01-01T00:00:00Z",
    )
    _controlled_backup(
        tmp_path,
        "walksafe-backup-new",
        "2026-07-10T00:00:00Z",
    )
    monkeypatch.setattr(
        prune,
        "verify_signed_backup_manifest",
        lambda manifest, **_kwargs: json.loads(manifest.read_text(encoding="utf-8")),
    )
    control = _compromised_control()

    plan = prune.plan_backup_prune(
        tmp_path,
        trusted_signer_fingerprint="a" * 40,
        retention_days=35,
        minimum_copies=1,
        now=datetime(2026, 7, 11, tzinfo=UTC),
        key_control=control,
        key_control_sha256="3" * 64,
        incident_id="incident-1",
    )

    assert plan["delete_candidates"] == []
    assert plan["prune_frozen_for_legal_review"] is True
    assert {item["run_id"] for item in plan["key_impact_inventory"]["runs"]} == {
        "walksafe-backup-new",
        old.name,
    }
    workflow = plan["incident_workflows"][0]
    assert workflow["status"] == "LEGAL_REVIEW_REQUIRED"
    assert workflow["legal_review_status"] == "NOT_RUN"
    assert workflow["notification_status"] == "NOT_RUN"
    assert workflow["recovery_status"] == "NOT_RUN"

    monkeypatch.setattr(
        prune,
        "walksafe_admin_high_risk_operation",
        lambda *_args, **_kwargs: pytest.fail("frozen prune must stop before authorization"),
    )
    with pytest.raises(ValueError, match="pending legal review"):
        prune.apply_backup_prune(
            tmp_path,
            plan,
            key_control=control,
            key_control_sha256="3" * 64,
        )
    assert old.exists()


def test_controlled_backup_without_pinned_key_control_blocks_entire_prune(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _backup(tmp_path, "walksafe-backup-new", "2026-07-10T00:00:00Z")
    controlled = _controlled_backup(
        tmp_path,
        "walksafe-backup-controlled-old",
        "2026-01-01T00:00:00Z",
    )
    monkeypatch.setattr(
        prune,
        "verify_signed_backup_manifest",
        lambda manifest, **_kwargs: json.loads(manifest.read_text(encoding="utf-8")),
    )

    with pytest.raises(ValueError, match="entire prune is blocked"):
        prune.plan_backup_prune(
            tmp_path,
            trusted_signer_fingerprint="a" * 40,
            retention_days=35,
            minimum_copies=1,
            now=datetime(2026, 7, 15, tzinfo=UTC),
        )
    assert controlled.exists()


def test_no_control_apply_preflight_blocks_if_controlled_backup_appears(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    legacy = _backup(tmp_path, "walksafe-backup-legacy", "2026-01-01T00:00:00Z")
    controlled = _controlled_backup(
        tmp_path,
        "walksafe-backup-controlled",
        "2026-07-01T00:00:00Z",
    )
    monkeypatch.setattr(
        prune,
        "verify_signed_backup_manifest",
        lambda manifest, **_kwargs: json.loads(manifest.read_text(encoding="utf-8")),
    )
    monkeypatch.setattr(
        prune,
        "walksafe_admin_high_risk_operation",
        lambda *_args, **_kwargs: pytest.fail("preflight must run before authorization"),
    )
    plan = {
        "delete_candidates": [legacy.name],
        "trusted_signer_fingerprint": "a" * 40,
    }

    with pytest.raises(ValueError, match="controlled backup appeared"):
        prune.apply_backup_prune(tmp_path, plan)
    assert legacy.exists()
    assert controlled.exists()


def test_controlled_prune_rejects_a_service_writable_lock_authority_before_delete(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    legacy = _backup(tmp_path, "walksafe-backup-legacy", "2026-01-01T00:00:00Z")
    authority_lock = tmp_path / "backup-key-control.lock"
    authority_lock.write_bytes(b"lock")
    authority_lock.chmod(0o600)
    original_policy = integrity._require_root_owned_authority_ancestry
    monkeypatch.setattr(
        integrity,
        "_require_root_owned_authority_ancestry",
        integrity._authority_ancestry,
    )
    descriptor, identity = integrity.acquire_backup_key_control_authority_lock(
        authority_lock,
        exclusive=False,
    )
    monkeypatch.setattr(
        integrity,
        "_require_root_owned_authority_ancestry",
        original_policy,
    )
    control = _compromised_control()
    control["authority_lock_identity_sha256"] = identity
    plan = {
        "delete_candidates": [legacy.name],
        "trusted_signer_fingerprint": "a" * 40,
        "key_control": {
            "control_id": control["control_id"],
            "revision": control["revision"],
            "sha256": "3" * 64,
        },
    }
    monkeypatch.setattr(
        prune,
        "walksafe_admin_high_risk_operation",
        lambda *_args, **_kwargs: pytest.fail("authority policy must fail before authorization"),
    )
    monkeypatch.setattr(
        prune.shutil,
        "rmtree",
        lambda _path: pytest.fail("authority policy must fail before deletion"),
    )
    try:
        with pytest.raises(ValueError, match="root-owned and non-writable"):
            prune.apply_backup_prune(
                tmp_path,
                plan,
                key_control=control,
                key_control_sha256="3" * 64,
                authority_lock_path=authority_lock,
                authority_lock_descriptor=descriptor,
                authority_lock_identity_sha256=identity,
            )
    finally:
        os.close(descriptor)

    assert legacy.exists()


def test_incomplete_inventory_freezes_all_compromised_key_pruning(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    candidate = _backup(
        tmp_path,
        "walksafe-backup-unreadable",
        "2026-01-01T00:00:00Z",
    )

    def reject_manifest(_manifest: Path, **_kwargs):
        raise ValueError("signed manifest unavailable")

    monkeypatch.setattr(prune, "verify_signed_backup_manifest", reject_manifest)
    plan = prune.plan_backup_prune(
        tmp_path,
        trusted_signer_fingerprint="a" * 40,
        retention_days=35,
        minimum_copies=1,
        now=datetime(2026, 7, 15, tzinfo=UTC),
        key_control=_compromised_control(),
        key_control_sha256="3" * 64,
    )

    assert candidate.exists()
    assert plan["key_impact_inventory"]["status"] == "INCOMPLETE"
    assert plan["prune_frozen_for_legal_review"] is True
    assert plan["delete_candidates"] == []
    assert plan["incident_workflows"][0]["incident_id"] == "incident-1"
