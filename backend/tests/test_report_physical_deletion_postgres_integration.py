from __future__ import annotations

from datetime import UTC, datetime, timedelta
import os
import uuid

import pytest
from sqlalchemy import create_engine, func, select, text
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import sessionmaker

from backend.app.models import (
    Report,
    ReportContentRevision,
    ReportDeletionLegalHold,
    ReportDeletionTombstone,
    ReportUserRequest,
)
from backend.app.schemas import (
    AdminReportUserRequestStatusUpdateV1,
    ReportUserRequestCreateV1,
)
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.report_deletion import (
    apply_report_deletion,
    list_report_deletion_candidates,
)
from backend.app.services.report_content_corrections import report_content_sha256
from backend.app.services.report_user_requests import (
    create_report_user_request,
    update_admin_request_status,
)
from backend.app.services.report_user_request_history import (
    list_owned_report_request_history,
)
from scripts.delete_reports import _assert_manual_role


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


class _StorageEffect:
    def __init__(self) -> None:
        self.staged: tuple[str, ...] = ()
        self.restored = False
        self.finalized = False

    def stage(self, storage_names: tuple[str, ...]) -> None:
        self.staged = storage_names

    def restore(self) -> None:
        self.restored = True

    def finalize(self) -> None:
        self.finalized = True


class _FailStorageEffect(_StorageEffect):
    def stage(self, storage_names: tuple[str, ...]) -> None:
        del storage_names
        pytest.fail("idempotent replay must not touch storage")


@pytest.mark.parametrize(
    "with_revision",
    [False, True],
    ids=["without-content-revision", "with-content-revision"],
)
def test_acknowledged_request_and_physical_effect_are_separate_and_idempotent(
    with_revision: bool,
) -> None:
    engine = create_engine(
        os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(), pool_pre_ping=True
    )
    SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)
    report_id = uuid.uuid4()
    subject = "d" * 64
    now = datetime.now(UTC)
    worker_session_role = f"walksafe_report_delete_it_{uuid.uuid4().hex}"
    worker_session_role_created = False
    try:
        with engine.begin() as connection:
            if not bool(
                connection.execute(
                    text(
                        "SELECT rolsuper FROM pg_catalog.pg_roles "
                        "WHERE rolname = session_user"
                    )
                ).scalar_one()
            ):
                pytest.skip(
                    "non-owner session_user regression requires a PostgreSQL "
                    "superuser test connection"
                )
            connection.exec_driver_sql(
                f'CREATE ROLE "{worker_session_role}" NOLOGIN'
            )
            connection.exec_driver_sql(
                "GRANT walksafe_report_deletion_worker TO "
                f'"{worker_session_role}"'
            )
            worker_session_role_created = True

        with SessionFactory() as db:
            db.add(
                Report(
                    id=report_id,
                    status="new",
                    status_version=1,
                    content_revision=1 if with_revision else 0,
                    class_id=1,
                    class_name="damaged_tactile_block",
                    confidence=0.9,
                    bbox_x=0.1,
                    bbox_y=0.1,
                    bbox_width=0.5,
                    bbox_height=0.5,
                    captured_at=now,
                    source="android",
                    image_path=f"/uploads/{report_id}.jpg",
                    image_content_type="image/jpeg",
                    payload={"schema_version": "detect.v2"},
                    privacy_subject_hmac=subject,
                    account_generation=3,
                    created_at=now,
                    updated_at=now,
                )
            )
            if with_revision:
                description = "현장 설명 정정"
                db.add(
                    ReportContentRevision(
                        id=uuid.uuid4(),
                        report_id=report_id,
                        revision=1,
                        expected_revision=0,
                        idempotency_key=uuid.uuid4(),
                        privacy_subject_hmac=subject,
                        account_generation=3,
                        user_description=description,
                        category_hint=None,
                        intent_sha256="a" * 64,
                        content_sha256=report_content_sha256(
                            report_id=report_id,
                            revision=1,
                            user_description=description,
                            category_hint=None,
                        ),
                        created_at=now,
                    )
                )
            db.commit()

        with SessionFactory() as db:
            db.add(
                ReportDeletionLegalHold(
                    report_id=report_id,
                    legal_basis_code="PIPA_LEGAL_OBLIGATION",
                    authority_reference="case:report-delete-expired-hold",
                    reason_code="legal_obligation",
                    approved_by="admin@example.com",
                    created_at=now - timedelta(days=2),
                    expires_at=now - timedelta(days=1),
                )
            )
            with pytest.raises(DBAPIError):
                db.commit()
            db.rollback()

        with SessionFactory() as db:
            request_state, created = create_report_user_request(
                db,
                report_id=report_id,
                payload=ReportUserRequestCreateV1(
                    client_request_id=uuid.uuid4(),
                    request_type="DELETE",
                    request_text="이 신고를 삭제해 주세요.",
                ),
                privacy_subject=subject,
                account_generation=3,
                now=now,
            )
        assert created is True

        with SessionFactory() as db:
            request_discovery_revision = db.scalar(
                select(ReportUserRequest.discovery_revision).where(
                    ReportUserRequest.id == request_state.id
                )
            )
        assert request_discovery_revision is not None

        with SessionFactory() as db:
            acknowledged = update_admin_request_status(
                db,
                request_id=request_state.id,
                payload=AdminReportUserRequestStatusUpdateV1(
                    status="ACKNOWLEDGED",
                    expected_version=1,
                    public_response="삭제 요청을 확인했습니다.",
                    internal_note="수동 worker 대기",
                ),
                identity=AdminSessionIdentity(
                    admin_id="admin@example.com",
                    session_id=uuid.uuid4(),
                    device_id="report-delete-test-device",
                    device_label="report delete test",
                    expires_at=now + timedelta(hours=1),
                    step_up_verified_at=now,
                ),
                correlation_id=uuid.uuid4(),
                query_sha256="e" * 64,
                now=now + timedelta(seconds=1),
            )
        assert acknowledged.status == "ACKNOWLEDGED"

        with SessionFactory() as db:
            candidates = list_report_deletion_candidates(db, limit=10)
            db.rollback()
        candidate = next(item for item in candidates if item.report_id == report_id)
        storage_effect = _StorageEffect()
        with engine.connect() as connection:
            try:
                connection.exec_driver_sql(
                    f'SET SESSION AUTHORIZATION "{worker_session_role}"'
                )
                connection.commit()
                with SessionFactory(bind=connection) as worker_db:
                    _assert_manual_role(worker_db)
                    worker_db.rollback()
                session_user, table_owner, is_worker = connection.execute(
                    text(
                        "SELECT session_user, tableowner, "
                        "pg_has_role(session_user, "
                        "'walksafe_report_deletion_worker', 'USAGE') "
                        "FROM pg_catalog.pg_tables "
                        "WHERE schemaname = 'public' "
                        "AND tablename = 'report_content_revisions'"
                    )
                ).one()
                assert session_user == worker_session_role
                assert session_user != table_owner
                assert is_worker is True
                privileges = connection.execute(
                    text(
                        "SELECT "
                        "has_function_privilege(session_user, "
                        "'public.walksafe_lock_report_deletion_candidate"
                        "(uuid,uuid,bigint,text,bigint)', 'EXECUTE'), "
                        "has_table_privilege(session_user, "
                        "'public.report_user_requests', 'UPDATE'), "
                        "has_table_privilege(session_user, 'public.reports', 'UPDATE')"
                    )
                ).one()
                can_execute, can_update_request, can_update_report = privileges
                assert can_execute is True
                assert can_update_request is False
                assert can_update_report is False
                connection.commit()
                stale_locked = connection.scalar(
                    select(
                        func.walksafe_lock_report_deletion_candidate(
                            candidate.request_id,
                            candidate.report_id,
                            candidate.request_status_version + 1,
                            candidate.privacy_subject_hmac,
                            candidate.account_generation,
                        )
                    )
                )
                assert stale_locked is False
                connection.commit()
                exact_locked = connection.scalar(
                    select(
                        func.walksafe_lock_report_deletion_candidate(
                            candidate.request_id,
                            candidate.report_id,
                            candidate.request_status_version,
                            candidate.privacy_subject_hmac,
                            candidate.account_generation,
                        )
                    )
                )
                assert exact_locked is True
                with engine.connect() as concurrent:
                    concurrent.exec_driver_sql("SET lock_timeout = '250ms'")
                    with pytest.raises(DBAPIError) as blocked:
                        concurrent.execute(
                            select(ReportUserRequest)
                            .where(ReportUserRequest.id == candidate.request_id)
                            .with_for_update()
                        ).scalar_one()
                    assert blocked.value.orig.sqlstate == "55P03"
                    concurrent.rollback()
                connection.commit()
                with SessionFactory(bind=connection) as db:
                    effect = apply_report_deletion(
                        db,
                        candidate=candidate,
                        storage_effect=storage_effect,
                        now=now + timedelta(seconds=2),
                    )
            finally:
                if connection.in_transaction():
                    connection.rollback()
                connection.exec_driver_sql("RESET SESSION AUTHORIZATION")
                connection.commit()
        assert effect.state == "DELETED"
        assert storage_effect.staged == (f"{report_id}.jpg",)
        assert storage_effect.restored is False
        assert storage_effect.finalized is True

        with SessionFactory() as db:
            assert db.get(Report, report_id) is None
            assert db.get(ReportUserRequest, request_state.id) is None
            tombstone = db.scalar(
                select(ReportDeletionTombstone).where(
                    ReportDeletionTombstone.request_id == request_state.id
                )
            )
            assert tombstone is not None
            assert tombstone.discovery_revision == request_discovery_revision
            assert tombstone.external_copy_count == 0

        with engine.connect() as connection:
            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            connection.commit()
            try:
                RuntimeSession = sessionmaker(
                    bind=connection,
                    expire_on_commit=False,
                )
                with RuntimeSession() as db:
                    filtered_history_page = list_owned_report_request_history(
                        db,
                        privacy_subject=subject,
                        account_generation=3,
                        report_id=report_id,
                        limit=10,
                        cursor=None,
                    )
                    global_history_page = list_owned_report_request_history(
                        db,
                        privacy_subject=subject,
                        account_generation=3,
                        report_id=None,
                        limit=10,
                        cursor=None,
                    )
                    db.rollback()
            finally:
                connection.rollback()
                connection.execute(text("RESET SESSION AUTHORIZATION"))
                connection.commit()
        assert filtered_history_page.total_count == 1
        filtered_item = filtered_history_page.items[0]
        assert filtered_item.source == "DELETION_TOMBSTONE"
        assert filtered_item.revision == request_discovery_revision
        assert filtered_item.request is None
        assert filtered_item.deletion_status is not None
        assert filtered_item.deletion_status.state == "DELETED"
        global_item = next(
            item
            for item in global_history_page.items
            if item.request_id == request_state.id
        )
        assert global_item.source == "DELETION_TOMBSTONE"
        assert global_item.revision == request_discovery_revision

        with SessionFactory() as db:
            replay = apply_report_deletion(
                db,
                candidate=candidate,
                storage_effect=_FailStorageEffect(),
            )
        assert replay.state == "DELETED"
    finally:
        if worker_session_role_created:
            with engine.begin() as connection:
                connection.exec_driver_sql(
                    f'DROP ROLE IF EXISTS "{worker_session_role}"'
                )
        engine.dispose()


def test_candidate_lock_rejects_a_non_worker_with_insufficient_privilege() -> None:
    engine = create_engine(
        os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(), pool_pre_ping=True
    )
    caller_role = f"walksafe_report_delete_denied_{uuid.uuid4().hex}"
    role_created = False
    try:
        with engine.begin() as connection:
            if not bool(
                connection.execute(
                    text(
                        "SELECT rolsuper FROM pg_catalog.pg_roles "
                        "WHERE rolname = session_user"
                    )
                ).scalar_one()
            ):
                pytest.skip(
                    "non-owner session_user regression requires a PostgreSQL "
                    "superuser test connection"
                )
            connection.exec_driver_sql(f'CREATE ROLE "{caller_role}" NOLOGIN')
            connection.exec_driver_sql(
                f'GRANT USAGE ON SCHEMA public TO "{caller_role}"'
            )
            role_created = True

        with engine.connect() as connection:
            try:
                connection.exec_driver_sql(
                    f'SET SESSION AUTHORIZATION "{caller_role}"'
                )
                connection.commit()
                with pytest.raises(DBAPIError) as denied:
                    connection.scalar(
                        select(
                            func.walksafe_lock_report_deletion_candidate(
                                uuid.uuid4(),
                                uuid.uuid4(),
                                1,
                                "f" * 64,
                                1,
                            )
                        )
                    )
                assert denied.value.orig.sqlstate == "42501"
            finally:
                if connection.in_transaction():
                    connection.rollback()
                connection.exec_driver_sql("RESET SESSION AUTHORIZATION")
                connection.commit()
    finally:
        if role_created:
            with engine.begin() as connection:
                connection.exec_driver_sql(
                    f'REVOKE ALL PRIVILEGES ON SCHEMA public FROM "{caller_role}"'
                )
                connection.exec_driver_sql(f'DROP ROLE IF EXISTS "{caller_role}"')
        engine.dispose()
