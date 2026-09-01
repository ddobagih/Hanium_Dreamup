from __future__ import annotations

from pathlib import Path
import re
import uuid

import pytest

from backend.app.models import (
    ReportDeletionExternalCopyState,
    ReportDeletionLegalHold,
    ReportDeletionTombstone,
)
from backend.app.schemas import ReportDeletionStatusV1
from backend.app.services.report_deletion import (
    _logical_storage_name,
    list_report_deletion_candidates,
)
from backend.app.services.report_user_requests import allowed_request_statuses
from scripts.delete_reports import (
    QUARANTINE_DIRECTORY,
    ReportDeletionQuarantine,
    ReportDeletionWorkerError,
)


def test_deletion_tombstone_has_no_report_content_location_or_photo_columns() -> None:
    columns = set(ReportDeletionTombstone.__table__.columns.keys())
    assert columns == {
        "id",
        "request_id",
        "report_id",
        "privacy_subject_hmac",
        "account_generation",
        "request_status_version",
        "external_copy_count",
        "deleted_at",
    }
    forbidden_fragments = {"content", "location", "photo", "image", "payload", "metadata"}
    assert all(
        fragment not in column
        for column in columns
        for fragment in forbidden_fragments
    )


def test_external_copy_snapshot_excludes_recipient_and_performs_no_delivery() -> None:
    columns = set(ReportDeletionExternalCopyState.__table__.columns.keys())
    assert "recipient" not in columns
    assert "institution" in columns
    assert "status" in columns


def test_legal_hold_requires_content_free_approval_and_expiry_fields() -> None:
    assert set(ReportDeletionLegalHold.__table__.columns.keys()) == {
        "report_id",
        "legal_basis_code",
        "authority_reference",
        "reason_code",
        "approved_by",
        "expires_at",
        "created_at",
    }
    constraints = {
        constraint.name: str(constraint.sqltext)
        for constraint in ReportDeletionLegalHold.__table__.constraints
        if getattr(constraint, "sqltext", None) is not None
    }
    assert "expires_at > created_at" in constraints[
        "ck_report_deletion_legal_holds_expires_at"
    ]
    assert "authority_reference ~" in constraints[
        "ck_report_deletion_legal_holds_authority"
    ]


def test_deletion_status_contract_is_strict_and_bounded() -> None:
    payload = ReportDeletionStatusV1(
        schema_version="walksafe.report-deletion-status.v1",
        request_id=uuid.uuid4(),
        report_id=uuid.uuid4(),
        state="LEGAL_HOLD",
        request_status_version=2,
        external_copy_count=0,
        updated_at="2026-08-29T00:00:00Z",
    )
    assert payload.state == "LEGAL_HOLD"
    assert set(payload.model_dump()) == {
        "schema_version",
        "request_id",
        "report_id",
        "state",
        "request_status_version",
        "external_copy_count",
        "updated_at",
    }


def test_admin_cannot_mark_delete_request_resolved_without_physical_effect() -> None:
    assert allowed_request_statuses(
        "ACKNOWLEDGED", request_type="DELETE"
    ) == ("REJECTED",)
    assert allowed_request_statuses("ACKNOWLEDGED", request_type="CORRECTION") == (
        "RESOLVED",
        "REJECTED",
    )


def test_only_report_bound_logical_image_names_are_deletable() -> None:
    report_id = uuid.uuid4()
    assert _logical_storage_name(report_id, f"/uploads/{report_id}.jpg") == f"{report_id}.jpg"
    assert _logical_storage_name(report_id, f"/uploads/{report_id}.wse") is None
    assert _logical_storage_name(report_id, "/uploads/other.jpg") is None
    assert _logical_storage_name(report_id, f"/other/{report_id}.jpg") is None


def test_candidate_query_is_acknowledged_delete_only_and_one_per_report() -> None:
    class _Result:
        @staticmethod
        def all() -> list[object]:
            return []

    class _Session:
        statement = None

        def execute(self, statement):
            self.statement = statement
            return _Result()

    db = _Session()
    assert list_report_deletion_candidates(db, limit=25) == []  # type: ignore[arg-type]
    sql = str(db.statement)
    assert "report_user_requests.request_type" in sql
    assert "report_user_requests.status" in sql
    assert "row_number() OVER (PARTITION BY report_user_requests.report_id" in sql
    assert "report_deletion_tombstones" in sql


def test_migration_and_worker_are_manual_and_minimum_privilege() -> None:
    root = Path(__file__).resolve().parents[2]
    migration = (
        root
        / "backend/alembic/versions/202608290013_report_physical_deletion.py"
    ).read_text(encoding="utf-8")
    worker = (root / "scripts/delete_reports.py").read_text(encoding="utf-8")
    service = (
        root / "backend/app/services/report_deletion.py"
    ).read_text(encoding="utf-8")
    report_storage = (
        root / "backend/app/services/report_storage.py"
    ).read_text(encoding="utf-8")
    assert 'down_revision = "202608290012"' in migration
    assert "walksafe_report_deletion_worker" in migration
    assert "GRANT DELETE ON TABLE public.reports" in migration
    assert "REVOKE INSERT, UPDATE, DELETE, TRUNCATE" in migration
    assert "report_deletion_tombstones_admission_guard" in migration
    assert "report_deletion_tombstones_completion_guard" in migration
    assert "reports_deletion_effect_guard" in migration
    assert "report_deletion_legal_holds_future_guard" in migration
    assert "NEW.expires_at <= clock_timestamp()" in migration
    assert "report deletion is blocked by an active legal hold" in migration
    assert "hold.expires_at > clock_timestamp()" in migration
    assert re.search(r"pg_has_role\(\s*current_user", migration) is None
    for role in (
        "walksafe_account_deletion_worker",
        "walksafe_report_deletion_worker",
    ):
        assert len(
            re.findall(
                rf"pg_has_role\(\s*session_user,\s*'{role}',\s*'USAGE'\s*\)",
                migration,
            )
        ) == 2
    assert service.count(
        "ReportDeletionLegalHold.expires_at > func.clock_timestamp()"
    ) == 2
    assert "--manual-one-shot" in worker
    assert "--reconcile" in worker
    assert "DELETE-ACKNOWLEDGED-REPORTS" in worker
    assert "candidate inventory changed after preview" in worker
    assert "REPORT_DELETION_QUARANTINE_DIRECTORY_NAME" in report_storage
    assert 'label="report deletion quarantine"' in report_storage
    assert "requests." not in worker
    assert "httpx" not in worker


def test_candidate_lock_is_narrow_and_does_not_grant_update() -> None:
    root = Path(__file__).resolve().parents[2]
    migration = (
        root
        / "backend/alembic/versions/202608300001_report_deletion_candidate_lock.py"
    ).read_text(encoding="utf-8")
    service = (
        root / "backend/app/services/report_deletion.py"
    ).read_text(encoding="utf-8")
    worker = (root / "scripts/delete_reports.py").read_text(encoding="utf-8")

    assert 'down_revision = "202608290016"' in migration
    assert "SECURITY DEFINER" in migration
    assert "SET search_path = pg_catalog, pg_temp" in migration
    assert "request.status = 'ACKNOWLEDGED'" in migration
    assert "request.status_version = p_request_status_version" in migration
    assert "FOR UPDATE OF request, report" in migration
    assert "USING ERRCODE = '42501'" in migration
    assert "FROM PUBLIC, walksafe_backend_runtime" in migration
    assert "TO walksafe_report_deletion_worker" in migration
    assert "GRANT UPDATE" not in migration
    assert "walksafe_lock_report_deletion_candidate" in service
    assert ".with_for_update(of=(ReportUserRequest, Report))" not in service
    assert "walksafe_lock_report_deletion_candidate" in worker
    assert "'public.reports', 'UPDATE'" in worker
    assert "'public.report_user_requests', 'UPDATE'" in worker


def test_user_deletion_status_route_is_no_store_and_owner_bound() -> None:
    source = (
        Path(__file__).resolve().parents[1] / "app/api/report_user_requests.py"
    ).read_text(encoding="utf-8")
    assert '"/reports/mine/deletions/{request_id}"' in source
    route = source.split('"/reports/mine/deletions/{request_id}"', 1)[1].split(
        '@router.get("/reports/mine/{report_id}"', 1
    )[0]
    assert "response.headers.update(_NO_STORE)" in route
    assert "_field_binding(" in route
    assert "get_owned_report_deletion_status(" in route


def test_user_deletion_status_security_contract_is_field_bound() -> None:
    from backend.app.field_test_security import (
        FieldTestAccess,
        required_field_test_access,
        requires_account_generation,
        requires_actor_identity,
    )

    path = "/reports/mine/deletions/00000000-0000-0000-0000-000000000001"
    assert required_field_test_access(path, "GET") is FieldTestAccess.FIELD
    assert requires_actor_identity(path, "GET")
    assert requires_account_generation(path, "GET")


def _write_report_object(root: Path, name: str) -> Path:
    path = root / name
    path.write_bytes(b"encrypted-report")
    return path


def test_quarantine_stage_then_restore_is_lossless(tmp_path: Path) -> None:
    request_id = uuid.uuid4()
    report_id = uuid.uuid4()
    name = f"{report_id}.wse"
    original = _write_report_object(tmp_path, name)
    effect = ReportDeletionQuarantine(
        tmp_path, request_id=request_id, report_id=report_id
    )
    effect.stage((name,))
    assert not original.exists()
    assert (tmp_path / QUARANTINE_DIRECTORY / f"{request_id}.{name}").is_file()
    effect.restore()
    assert original.read_bytes() == b"encrypted-report"
    assert list((tmp_path / QUARANTINE_DIRECTORY).iterdir()) == []


def test_quarantine_stage_then_finalize_physically_deletes(tmp_path: Path) -> None:
    request_id = uuid.uuid4()
    report_id = uuid.uuid4()
    name = f"{report_id}.jpg"
    original = _write_report_object(tmp_path, name)
    effect = ReportDeletionQuarantine(
        tmp_path, request_id=request_id, report_id=report_id
    )
    effect.stage((name,))
    effect.finalize()
    assert not original.exists()
    assert list((tmp_path / QUARANTINE_DIRECTORY).iterdir()) == []


def test_reconcile_uses_tombstone_decision_only(tmp_path: Path) -> None:
    request_id = uuid.uuid4()
    report_id = uuid.uuid4()
    name = f"{report_id}.webp"
    original = _write_report_object(tmp_path, name)
    effect = ReportDeletionQuarantine(
        tmp_path, request_id=request_id, report_id=report_id
    )
    effect.stage((name,))
    finalized, restored = ReportDeletionQuarantine.reconcile_all(
        tmp_path, is_committed=lambda _request, _report: False
    )
    assert (finalized, restored) == (0, 1)
    assert original.is_file()

    effect.stage((name,))
    finalized, restored = ReportDeletionQuarantine.reconcile_all(
        tmp_path,
        is_committed=lambda request, report: (
            request == request_id and report == report_id
        ),
    )
    assert (finalized, restored) == (1, 0)
    assert not original.exists()


@pytest.mark.parametrize("unsafe_kind", ["symlink", "hardlink"])
def test_quarantine_rejects_unsafe_source_objects(
    tmp_path: Path, unsafe_kind: str
) -> None:
    request_id = uuid.uuid4()
    report_id = uuid.uuid4()
    name = f"{report_id}.wse"
    original = tmp_path / name
    target = tmp_path / "target"
    target.write_bytes(b"target")
    if unsafe_kind == "symlink":
        original.symlink_to(target)
    else:
        original.hardlink_to(target)
    effect = ReportDeletionQuarantine(
        tmp_path, request_id=request_id, report_id=report_id
    )
    with pytest.raises(ReportDeletionWorkerError):
        effect.stage((name,))


def test_reconcile_rejects_unknown_quarantine_entries(tmp_path: Path) -> None:
    quarantine = tmp_path / QUARANTINE_DIRECTORY
    quarantine.mkdir(mode=0o700)
    (quarantine / "unknown").write_bytes(b"unknown")
    with pytest.raises(ReportDeletionWorkerError):
        ReportDeletionQuarantine.reconcile_all(
            tmp_path, is_committed=lambda _request, _report: False
        )
