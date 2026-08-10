from __future__ import annotations

from datetime import UTC, datetime
import os
from pathlib import Path
import sys

import pytest


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from scripts.manage_field_telemetry_retention_20260711 import run_retention  # noqa: E402


@pytest.fixture(autouse=True)
def explicit_local_security_opt_in(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    monkeypatch.setenv("WALKSAFE_ALLOW_INSECURE_LOCAL_DEV", "true")
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "false")


def test_retention_is_dry_run_by_default_and_apply_deletes_only_expired_dates(tmp_path: Path) -> None:
    old = tmp_path / "2026-06-01"
    boundary = tmp_path / "2026-07-04"
    recent = tmp_path / "2026-07-10"
    unrelated = tmp_path / "manual"
    for directory in (old, boundary, recent, unrelated):
        directory.mkdir()
        (directory / "session.jsonl").write_text("{}\n", encoding="utf-8")

    now = datetime(2026, 7, 11, 12, tzinfo=UTC)
    dry_run = run_retention(tmp_path, 7, apply=False, now=now)
    assert dry_run["candidate_dates"] == ["2026-06-01"]
    assert old.exists()

    applied = run_retention(tmp_path, 7, apply=True, now=now)
    assert applied["deleted_dates"] == ["2026-06-01"]
    assert not old.exists()
    assert boundary.exists()
    assert recent.exists()
    assert unrelated.exists()
    assert len(applied["inventory_before_sha256"]) == 64
    assert len(applied["inventory_after_sha256"]) == 64
    assert applied["inventory_before_sha256"] != applied["inventory_after_sha256"]


def test_retention_ignores_symlinked_date_folder(tmp_path: Path) -> None:
    outside = tmp_path / "outside"
    outside.mkdir()
    root = tmp_path / "logs"
    root.mkdir(mode=0o700)
    (root / "2020-01-01").symlink_to(outside, target_is_directory=True)

    result = run_retention(root, 7, apply=True, now=datetime(2026, 7, 11, tzinfo=UTC))
    assert result["deleted_dates"] == []
    assert outside.exists()


def test_test_capture_scope_has_a_distinct_release_receipt_schema(tmp_path: Path) -> None:
    expired = tmp_path / "2026-06-01"
    expired.mkdir()
    (expired / "capture.jpg").write_bytes(b"private-test-capture")

    result = run_retention(
        tmp_path,
        7,
        apply=True,
        scope="test_capture",
        now=datetime(2026, 7, 11, tzinfo=UTC),
    )

    assert result["schema_version"] == "walksafe.test-capture-retention.v1"
    assert result["scope"] == "test_capture"
    assert result["deleted_dates"] == ["2026-06-01"]
    assert result["candidate_sidecars"] == []
    assert not expired.exists()


def test_field_retention_deletes_expired_owner_and_revocation_sidecars(tmp_path: Path) -> None:
    owners = tmp_path / ".owners"
    revoked = tmp_path / ".revoked"
    owners.mkdir()
    revoked.mkdir()
    old_owner = owners / "expired.owner"
    old_revoked = revoked / "expired.revoked"
    recent_owner = owners / "recent.owner"
    for path in (old_owner, old_revoked, recent_owner):
        path.write_text("binding\n", encoding="utf-8")
    old_timestamp = datetime(2026, 7, 1, tzinfo=UTC).timestamp()
    recent_timestamp = datetime(2026, 7, 10, tzinfo=UTC).timestamp()
    os.utime(old_owner, (old_timestamp, old_timestamp))
    os.utime(old_revoked, (old_timestamp, old_timestamp))
    os.utime(recent_owner, (recent_timestamp, recent_timestamp))

    result = run_retention(
        tmp_path,
        7,
        apply=True,
        now=datetime(2026, 7, 11, 12, tzinfo=UTC),
    )

    assert result["deleted_sidecars"] == [".owners/expired.owner", ".revoked/expired.revoked"]
    assert not old_owner.exists()
    assert not old_revoked.exists()
    assert recent_owner.exists()


def test_daily_retention_runner_covers_both_short_lived_log_scopes() -> None:
    runner = (ROOT / "scripts/run_walksafe_log_retention_20260711.sh").read_text(encoding="utf-8")

    assert "for scope in field_telemetry test_capture" in runner
    assert "--apply" in runner
    assert "--confirm DELETE-EXPIRED-WALKSAFE-LOGS" in runner
    assert '--lock "${LOCK_PATH}"' in runner
    assert "--receipt" in runner

    service = (ROOT / "deploy/systemd/walksafe-log-retention.service").read_text(
        encoding="utf-8"
    )
    assert "LimitCORE=0" in service


def test_retention_apply_rejects_symlink_or_nonprivate_root(tmp_path: Path) -> None:
    real_root = tmp_path / "real"
    real_root.mkdir(mode=0o700)
    linked_root = tmp_path / "linked"
    linked_root.symlink_to(real_root, target_is_directory=True)

    try:
        run_retention(linked_root, 7, apply=True, now=datetime(2026, 7, 11, tzinfo=UTC))
    except ValueError as exc:
        assert "private" in str(exc)
    else:  # pragma: no cover - fail closed assertion
        raise AssertionError("symlink retention root was accepted")

    real_root.chmod(0o755)
    try:
        run_retention(real_root, 7, apply=True, now=datetime(2026, 7, 11, tzinfo=UTC))
    except ValueError as exc:
        assert "private" in str(exc)
    else:  # pragma: no cover - fail closed assertion
        raise AssertionError("world-readable retention root was accepted")
