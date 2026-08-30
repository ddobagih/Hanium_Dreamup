from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/manage_raw_collection_retention.py"
MIGRATION = ROOT / "backend/alembic/versions/202608290007_raw_collection_retention_worker.py"
POLICY = ROOT / "docs/operations/data_retention_policy.md"


def test_raw_retention_is_manual_only_and_uses_dedicated_authority() -> None:
    source = SCRIPT.read_text(encoding="utf-8")

    for required in (
        'DATABASE_URL_ENV = "WALKSAFE_RAW_RETENTION_DATABASE_URL"',
        'APPLY_CONFIRMATION = "DELETE-EXPIRED-RAW-COLLECTIONS"',
        'walksafe_admin_high_risk_operation(',
        '"DATA_DELETE"',
        'mode.add_argument("--local-isolated"',
        'mode.add_argument("--manual-one-shot"',
        'action.add_argument("--apply"',
        'action.add_argument("--reconcile"',
        'raw_storage_has_pending_writes(raw_root)',
        'RECOVERY_DIRECTORY_NAME = ".raw-retention-recovery"',
        'lock_raw_storage_reconciliation_transaction(db)',
        'lock_raw_capacity_reservation_transaction(db)',
        '.with_for_update()',
    ):
        assert required in source
    assert 'os.environ.get("DATABASE_URL"' not in source
    assert "skip_locked" not in source.casefold()
    assert not (ROOT / "deploy/systemd/walksafe-raw-retention.service").exists()
    assert not (ROOT / "deploy/systemd/walksafe-raw-retention.timer").exists()


def test_worker_migration_grants_only_raw_select_and_parent_delete() -> None:
    source = MIGRATION.read_text(encoding="utf-8")

    assert "CREATE ROLE {_WORKER_ROLE}" in source
    assert "NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE" in source
    assert 'GRANT DELETE ON TABLE public.raw_collections' in source
    assert "REVOKE DELETE ON TABLE public.raw_collection_objects" in source
    assert "REVOKE INSERT, UPDATE, TRUNCATE" in source
    for denied in (
        "public.reports",
        "public.privacy_consent_events",
        "public.account_deletion_requests",
        "public.account_deletion_receipts",
    ):
        assert denied in source


def test_raw_and_training_retention_targets_remain_explicitly_not_run() -> None:
    policy = POLICY.read_text(encoding="utf-8")

    assert "14일 검역" in policy
    assert "승인 시각부터 3년 만료" in policy
    assert "승인된 법적 보유기간이나 최종 사용자 고지가 아니라" in policy
    assert "실제 운영 DB·object store에서의 삭제와 reconcile" in policy
    assert "NOT_RUN" in policy
