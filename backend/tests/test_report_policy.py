from __future__ import annotations

from datetime import datetime, timezone
import errno
import fcntl
import os
from pathlib import Path
from types import SimpleNamespace
import uuid
import pytest
from fastapi import HTTPException
from pydantic import ValidationError

from backend.app.api.reports import (
    _agency_export_row,
    _agency_review_was_named,
    _bounded_summary_reports,
    _commit_new_report_with_image,
    _durable_export_audit_filters,
    _minimum_export_row,
    _report_export_row,
)
import backend.app.api.reports as reports_api
from backend.app.models import Report
from backend.app.schemas import ReportV2Metadata
from backend.app.services.report_policy import ensure_report_v2_allowed, report_v2_source
from backend.app.services.report_storage import ReportStorageCommitState, reconcile_pending_report_writes
from backend.app.services.report_serialization import report_to_response


class _StaticKeyManager:
    def synchronize(self, _db) -> None:
        return None

    def encryption_slot(self):
        return SimpleNamespace(key_id="test-key-v1", material=bytes(range(32)))


def report_metadata(**overrides: object) -> ReportV2Metadata:
    payload = {
        "schema_version": "detect.v2",
        "model_key": "unified_walksafe",
        "source_model": "fake/unified-walksafe-contract",
        "model_class_id": 8,
        "class_name": "damaged_tactile_block",
        "category": "tactile_damage",
        "confidence": 0.91,
        "bbox": {"x": 0.2, "y": 0.35, "width": 0.4, "height": 0.22},
        "threshold_used": 0.35,
        "captured_at": "2026-06-02T12:00:00.000Z",
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "heading": 181.0,
        "trigger": "auto",
        "auto_reported": True,
        "reporter_user_id": "user-123",
    }
    payload.update(overrides)
    return ReportV2Metadata.model_validate(payload)


def test_report_v2_allows_unified_damaged_tactile_block() -> None:
    ensure_report_v2_allowed(report_metadata())


def test_report_v2_maps_android_source() -> None:
    metadata = report_metadata(
        source="android",
        source_model="android/unified-walksafe",
    )

    ensure_report_v2_allowed(metadata)

    assert report_v2_source(metadata) == "android"
    assert metadata.reporter_user_id == "user-123"


def test_durable_export_audit_redacts_exact_filter_coordinates() -> None:
    filters = {
        "lat": 37.56651234,
        "lng": 126.97801234,
        "radius_m": 25,
        "profile": "internal",
    }

    durable = _durable_export_audit_filters(filters)

    assert "lat" not in durable
    assert "lng" not in durable
    assert durable["radius_m"] == 25
    assert durable["exact_location_filter_redacted"] is True
    assert filters["lat"] == 37.56651234


def test_report_summary_requires_narrower_filters_above_the_row_cap(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(reports_api, "MAX_REPORT_SUMMARY_ROWS", 2)

    with pytest.raises(HTTPException) as exc_info:
        _bounded_summary_reports([object(), object(), object()])  # type: ignore[list-item]

    assert exc_info.value.status_code == 413
    assert exc_info.value.detail["code"] == "report_summary_too_large"


@pytest.mark.parametrize("invalid_value", [float("nan"), float("inf"), float("-inf")])
def test_report_v2_rejects_nonfinite_gps_values(invalid_value: float) -> None:
    with pytest.raises(ValidationError):
        report_metadata(
            gps={"latitude": 37.5, "longitude": 127.0, "accuracy_m": invalid_value}
        )


@pytest.mark.parametrize(
    "actor_id",
    ["", "unknown", "system", "anonymous", "admin-shared", "not a valid actor"],
)
def test_agency_review_requires_a_named_human_actor(actor_id: str) -> None:
    assert not _agency_review_was_named(
        {
            "agency_review_verified": True,
            "agency_reviewed_by_actor_id": actor_id,
        }
    )


def test_agency_review_accepts_a_named_human_actor() -> None:
    assert _agency_review_was_named(
        {
            "agency_review_verified": True,
            "agency_reviewed_by_actor_id": "operator.kim",
        }
    )


def test_report_v2_rejects_unified_damaged_tactile_block_wrong_class_id() -> None:
    with pytest.raises(HTTPException) as exc_info:
        ensure_report_v2_allowed(report_metadata(model_class_id=1))

    assert exc_info.value.status_code == 422
    assert "model_class_id=8" in exc_info.value.detail


def test_export_row_includes_duplicate_candidate_ids_without_database() -> None:
    report = Report(
        status="new",
        class_id=8,
        class_name="damaged_tactile_block",
        confidence=0.91,
        bbox_x=0.2,
        bbox_y=0.35,
        bbox_width=0.4,
        bbox_height=0.22,
        captured_at=datetime(2026, 6, 2, 12, 0, tzinfo=timezone.utc),
        source="android",
        latitude=37.5665,
        longitude=126.978,
        accuracy_m=9.5,
        heading=181.0,
        image_path="reports/example.jpg",
        image_content_type="image/jpeg",
        payload={
            "review_flags": ["duplicate_candidate"],
            "duplicate_report_ids": [
                "11111111-1111-1111-1111-111111111111",
                "22222222-2222-2222-2222-222222222222",
            ],
            "reporter_user_id": "user-123",
        },
        created_at=datetime(2026, 6, 2, 12, 1, tzinfo=timezone.utc),
        updated_at=datetime(2026, 6, 2, 12, 1, tzinfo=timezone.utc),
    )

    row = _report_export_row(report)

    assert "duplicate_candidate" in row["review_flags"]
    assert row["duplicate_report_ids"] == (
        "11111111-1111-1111-1111-111111111111,"
        "22222222-2222-2222-2222-222222222222"
    )
    assert row["reporter_user_id"] == "user-123"


def test_export_row_escapes_formula_like_review_flags() -> None:
    report = Report(
        status="new",
        class_id=8,
        class_name="damaged_tactile_block",
        confidence=0.91,
        bbox_x=0.2,
        bbox_y=0.35,
        bbox_width=0.4,
        bbox_height=0.22,
        captured_at=datetime(2026, 6, 2, 12, 0, tzinfo=timezone.utc),
        source="android",
        latitude=37.5665,
        longitude=126.978,
        accuracy_m=9.5,
        heading=181.0,
        image_path="reports/example.jpg",
        image_content_type="image/jpeg",
        payload={"review_flags": ["\t=HYPERLINK(bad)"]},
        created_at=datetime(2026, 6, 2, 12, 1, tzinfo=timezone.utc),
        updated_at=datetime(2026, 6, 2, 12, 1, tzinfo=timezone.utc),
    )

    assert str(_report_export_row(report)["review_flags"]).startswith("'")


def test_minimum_export_row_removes_internal_evidence_fields() -> None:
    minimum = _minimum_export_row(
        {
            "latitude": 37.56651234,
            "longitude": 126.97801234,
            "image_path": "/uploads/private.jpg",
            "reporter_user_id": "user-123",
            "trace_id": "trace-123",
            "heading": 181.0,
            "bbox_x": 0.2,
            "bbox_y": 0.3,
            "bbox_width": 0.4,
            "bbox_height": 0.2,
            "duplicate_report_ids": "duplicate-id",
            "review_note": "internal",
            "resolution_reason": "internal",
        }
    )

    assert minimum["latitude"] == 37.57
    assert minimum["longitude"] == 126.98
    for field in {
        "image_path",
        "reporter_user_id",
        "trace_id",
        "heading",
        "bbox_x",
        "bbox_y",
        "bbox_width",
        "bbox_height",
        "duplicate_report_ids",
        "review_note",
        "resolution_reason",
    }:
        assert minimum[field] == ""


def test_agency_export_row_keeps_exact_location_and_only_business_fields() -> None:
    source = {field: f"private-{field}" for field in {
        "source_model",
        "reporter_user_id",
        "trace_id",
        "image_path",
        "review_note",
        "review_flags",
    }}
    source.update(
        {
            "id": "report-id",
            "status": "new",
            "class_name": "damaged_tactile_block",
            "confidence": 0.91,
            "latitude": 37.56651234,
            "longitude": 126.97801234,
            "accuracy_m": 7.5,
            "captured_at": "2026-07-11T00:00:00Z",
            "created_at": "2026-07-11T00:00:01Z",
            "location_quality": "high",
        }
    )

    agency = _agency_export_row(source)

    assert agency["latitude"] == 37.56651234
    assert agency["longitude"] == 126.97801234
    assert agency["class_name"] == "damaged_tactile_block"
    for field in {"source_model", "reporter_user_id", "trace_id", "image_path", "review_note", "review_flags"}:
        assert agency[field] == ""


def test_nonfinite_persisted_location_is_never_reported_as_high_quality() -> None:
    report = Report(
        id="00000000-0000-4000-8000-000000000001",
        status="new",
        class_id=0,
        class_name="damaged_tactile_block",
        confidence=0.9,
        bbox_x=0.1,
        bbox_y=0.1,
        bbox_width=0.2,
        bbox_height=0.2,
        captured_at=datetime.now(timezone.utc),
        source="server",
        latitude=37.5,
        longitude=127.0,
        accuracy_m=float("nan"),
        heading=float("nan"),
        image_path="/uploads/test.jpg",
        image_content_type="image/jpeg",
        payload={},
        created_at=datetime.now(timezone.utc),
        updated_at=datetime.now(timezone.utc),
    )

    response = report_to_response(report)

    assert response.location_quality == "low"
    assert response.gps is not None and response.gps.accuracy_m is None
    assert response.heading is None


def test_report_commit_failure_rolls_back_and_removes_written_image(tmp_path) -> None:
    class FailingSession:
        rolled_back = False

        def execute(self, statement: object, parameters: object) -> None:
            assert "pg_advisory_xact_lock_shared" in str(statement)
            assert parameters == {"lock_key": "walksafe-report-storage-reconciliation-v1"}

        def add(self, _report: object) -> None:
            return None

        def commit(self) -> None:
            raise RuntimeError("database unavailable")

        def rollback(self) -> None:
            self.rolled_back = True

    session = FailingSession()
    report_id = uuid.uuid4()
    destination = tmp_path / f"{report_id}.wse"

    with pytest.raises(RuntimeError, match="database unavailable"):
        _commit_new_report_with_image(  # type: ignore[arg-type]
            session,
            SimpleNamespace(id=report_id),
            destination=destination,
            content=b"image",
            content_type="image/jpeg",
            key_manager=_StaticKeyManager(),
        )

    assert session.rolled_back is True
    assert destination.exists()

    reconciled = reconcile_pending_report_writes(
        tmp_path,
        lambda _report_id: ReportStorageCommitState(False, None),
    )

    assert reconciled.removed_orphans == 1
    assert not destination.exists()
    assert not any(path.is_file() for path in tmp_path.rglob("*"))


def test_report_write_fails_closed_while_maintenance_has_exclusive_lock(
    tmp_path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class UnexpectedSession:
        def add(self, _report: object) -> None:
            raise AssertionError("database write must not start during maintenance")

    monkeypatch.setattr(reports_api, "_trusted_maintenance_lock_parent", lambda *_args: True)
    lock_path = tmp_path / "maintenance.lock"
    lock_path.touch(mode=0o600)
    descriptor = os.open(lock_path, os.O_RDONLY | os.O_NOFOLLOW)
    fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
    destination = tmp_path / "report.wse"
    try:
        with pytest.raises(HTTPException) as exc_info:
            _commit_new_report_with_image(  # type: ignore[arg-type]
                UnexpectedSession(),
                object(),
                    destination=destination,
                    content=b"image",
                    content_type="image/jpeg",
                    key_manager=_StaticKeyManager(),
                maintenance_lock_path=lock_path,
            )
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)

    assert exc_info.value.status_code == 503
    assert exc_info.value.detail["code"] == "maintenance_in_progress"
    assert not destination.exists()


def test_report_write_requires_preprovisioned_single_link_maintenance_leaf(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o700)
    lock_path = tmp_path / "maintenance.lock"
    monkeypatch.setattr(reports_api, "_trusted_maintenance_lock_parent", lambda *_args: True)

    with pytest.raises(HTTPException) as missing:
        with reports_api._shared_report_write_lock(lock_path):
            pytest.fail("missing maintenance lock must not be entered")
    assert missing.value.detail["code"] == "maintenance_lock_unavailable"
    assert not lock_path.exists()

    lock_path.touch(mode=0o600)
    os.link(lock_path, tmp_path / "maintenance.alias")
    with pytest.raises(HTTPException) as aliased:
        with reports_api._shared_report_write_lock(lock_path):
            pytest.fail("hard-linked maintenance lock must not be entered")
    assert aliased.value.detail["code"] == "maintenance_lock_unavailable"


def test_backend_shared_leaf_lock_blocks_retention(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from scripts import check_report_retention_dry_run as retention

    tmp_path.chmod(0o700)
    lock_path = tmp_path / "maintenance.lock"
    lock_path.touch(mode=0o600)
    monkeypatch.setattr(
        reports_api,
        "_trusted_maintenance_lock_parent",
        lambda *_args: True,
    )
    monkeypatch.setattr(
        retention,
        "_validate_trusted_maintenance_lock_parent",
        lambda *_args: None,
    )

    with reports_api._shared_report_write_lock(lock_path):
        with pytest.raises(TimeoutError, match="timed out acquiring maintenance lock"):
            with retention.exclusive_maintenance_lock(lock_path, timeout_seconds=0):
                pytest.fail("retention must not enter while Backend holds the leaf")


def test_report_write_rejects_maintenance_lock_acl_inspection_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    tmp_path.chmod(0o700)
    lock_path = tmp_path / "maintenance.lock"
    lock_path.touch(mode=0o600)
    monkeypatch.setattr(reports_api, "_trusted_maintenance_lock_parent", lambda *_args: True)
    monkeypatch.setattr(
        reports_api.os,
        "listxattr",
        lambda _descriptor: (_ for _ in ()).throw(OSError(errno.EACCES, "denied")),
    )

    with pytest.raises(HTTPException) as exc_info:
        with reports_api._shared_report_write_lock(lock_path):
            pytest.fail("uninspectable ACL state must not be entered")
    assert exc_info.value.detail["code"] == "maintenance_lock_unavailable"


def test_report_write_rejects_replaceable_maintenance_lock_parent(tmp_path) -> None:
    lock_parent = tmp_path / "maintenance"
    lock_parent.mkdir(mode=0o700)
    lock_path = lock_parent / "maintenance.lock"
    lock_path.touch(mode=0o600)

    with pytest.raises(HTTPException) as exc_info:
        _commit_new_report_with_image(  # type: ignore[arg-type]
            object(),
            object(),
            destination=tmp_path / "report.wse",
            content=b"image",
            content_type="image/jpeg",
            key_manager=_StaticKeyManager(),
            maintenance_lock_path=lock_path,
        )

    assert exc_info.value.status_code == 503
    assert exc_info.value.detail["code"] == "maintenance_lock_unavailable"


def test_maintenance_lock_authority_rejects_root_service(
    tmp_path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    descriptor = os.open(tmp_path, os.O_RDONLY | os.O_DIRECTORY)
    monkeypatch.setattr(reports_api.os, "geteuid", lambda: 0)
    try:
        assert reports_api._trusted_maintenance_lock_parent(tmp_path, descriptor) is False
    finally:
        os.close(descriptor)


def test_maintenance_lock_authority_rejects_user_owned_higher_ancestor(
    tmp_path,
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

    descriptor = os.open(lock_parent, os.O_RDONLY | os.O_DIRECTORY)
    monkeypatch.setattr(reports_api.os, "fstat", only_higher_ancestor_remains_user_owned)
    monkeypatch.setattr(reports_api.os, "access", simulated_safe_ancestors_appear_non_writable)
    try:
        assert reports_api._trusted_maintenance_lock_parent(lock_parent, descriptor) is False
        assert higher_ancestor_identity in visited_identities
    finally:
        os.close(descriptor)


@pytest.mark.parametrize(
    "binding",
    ("authority_path", "authority_parent_entry", "lock_parent_path", "lock_parent_entry"),
)
def test_maintenance_lock_authority_rejects_inode_binding_change(
    binding: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    parent = Path("/run/user")
    if os.geteuid() == 0 or not parent.is_dir():
        pytest.skip("requires a non-root Linux runtime with /run/user")
    descriptor = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        assert reports_api._trusted_maintenance_lock_parent(parent, descriptor) is True
        real_stat = os.stat
        real_fstat = os.fstat
        root_identity = (Path("/").stat().st_dev, Path("/").stat().st_ino)
        run_identity = (Path("/run").stat().st_dev, Path("/run").stat().st_ino)

        def changed_inode(
            path: str | os.PathLike[str],
            *,
            dir_fd: int | None = None,
            follow_symlinks: bool = True,
        ) -> os.stat_result:
            metadata = real_stat(path, dir_fd=dir_fd, follow_symlinks=follow_symlinks)
            directory_identity = None
            if dir_fd is not None:
                directory = real_fstat(dir_fd)
                directory_identity = (directory.st_dev, directory.st_ino)
            should_change = (
                (binding == "authority_path" and dir_fd is None and Path(path) == Path("/"))
                or (
                    binding == "authority_parent_entry"
                    and directory_identity == root_identity
                    and os.fspath(path) == "run"
                )
                or (binding == "lock_parent_path" and dir_fd is None and Path(path) == parent)
                or (
                    binding == "lock_parent_entry"
                    and directory_identity == run_identity
                    and os.fspath(path) == "user"
                )
            )
            if not should_change:
                return metadata
            fields = list(metadata)
            fields[1] += 1
            return os.stat_result(fields)

        monkeypatch.setattr(reports_api.os, "stat", changed_inode)
        assert reports_api._trusted_maintenance_lock_parent(parent, descriptor) is False
    finally:
        os.close(descriptor)


def test_maintenance_lock_authority_rejects_effective_acl_write_access(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    parent = Path("/run/user")
    if os.geteuid() == 0 or not parent.is_dir():
        pytest.skip("requires a non-root Linux runtime with /run/user")
    descriptor = os.open(parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        assert reports_api._trusted_maintenance_lock_parent(parent, descriptor) is True
        real_access = os.access
        real_fstat = os.fstat
        run_identity = (Path("/run").stat().st_dev, Path("/run").stat().st_ino)

        def writable_through_acl(
            path: str,
            mode: int,
            *,
            dir_fd: int | None = None,
            effective_ids: bool = False,
            follow_symlinks: bool = True,
        ) -> bool:
            if dir_fd is not None:
                directory = real_fstat(dir_fd)
                if (directory.st_dev, directory.st_ino) == run_identity:
                    return True
            return real_access(
                path,
                mode,
                dir_fd=dir_fd,
                effective_ids=effective_ids,
                follow_symlinks=follow_symlinks,
            )

        monkeypatch.setattr(reports_api.os, "access", writable_through_acl)
        assert reports_api._trusted_maintenance_lock_parent(parent, descriptor) is False
    finally:
        os.close(descriptor)


def test_report_write_rejects_maintenance_lock_not_owned_by_service_user(
    tmp_path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    lock_path = tmp_path / "maintenance.lock"
    lock_path.touch(mode=0o600)
    monkeypatch.setattr(reports_api, "_trusted_maintenance_lock_parent", lambda *_args: True)
    monkeypatch.setattr(
        "backend.app.api.reports.os.geteuid",
        lambda: os.stat(lock_path).st_uid + 1,
    )

    with pytest.raises(HTTPException) as exc_info:
        _commit_new_report_with_image(  # type: ignore[arg-type]
            object(),
            object(),
            destination=tmp_path / "report.wse",
            content=b"image",
            content_type="image/jpeg",
            key_manager=_StaticKeyManager(),
            maintenance_lock_path=lock_path,
        )

    assert exc_info.value.status_code == 503
    assert exc_info.value.detail["code"] == "maintenance_lock_unavailable"


def test_report_response_restores_duplicate_ids_from_metadata_without_database() -> None:
    report = Report(
        status="new",
        class_id=8,
        class_name="damaged_tactile_block",
        confidence=0.91,
        bbox_x=0.2,
        bbox_y=0.35,
        bbox_width=0.4,
        bbox_height=0.22,
        captured_at=datetime(2026, 6, 2, 12, 0, tzinfo=timezone.utc),
        source="android",
        latitude=37.5665,
        longitude=126.978,
        accuracy_m=9.5,
        heading=181.0,
        image_path="reports/example.jpg",
        image_content_type="image/jpeg",
        payload={
            "review_flags": ["duplicate_candidate"],
            "duplicate_report_ids": ["11111111-1111-1111-1111-111111111111"],
        },
        created_at=datetime(2026, 6, 2, 12, 1, tzinfo=timezone.utc),
        updated_at=datetime(2026, 6, 2, 12, 1, tzinfo=timezone.utc),
    )

    response = report_to_response(report)

    assert response.duplicate_report_ids == ["11111111-1111-1111-1111-111111111111"]
    assert "duplicate_candidate" in response.review_flags
