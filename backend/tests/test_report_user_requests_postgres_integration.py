from __future__ import annotations

from datetime import UTC, datetime, timedelta
import os
import uuid

import pytest
from sqlalchemy import create_engine, select, text
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import sessionmaker

from backend.app.models import (
    AdminOperationAudit,
    Report,
    ReportUserRequest,
    ReportUserRequestStatusEvent,
)
from backend.app.schemas import (
    AdminReportUserRequestStatusUpdateV1,
    ReportUserRequestCreateV1,
)
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.privacy_lifecycle import lock_privacy_subject_shared
from backend.app.services.report_user_requests import (
    create_report_user_request,
    update_admin_request_status,
)
from backend.app.services.report_user_request_history import (
    UserReportRequestHistoryError,
    list_owned_report_request_history,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _session_factory():
    engine = create_engine(
        os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(),
        pool_pre_ping=True,
    )
    return engine, sessionmaker(bind=engine, expire_on_commit=False)


def _run_as_runtime(engine, callback):
    with engine.connect() as connection:
        connection.execute(text("SET SESSION AUTHORIZATION walksafe_backend_runtime"))
        connection.commit()
        try:
            RuntimeSession = sessionmaker(bind=connection, expire_on_commit=False)
            with RuntimeSession() as db:
                return callback(db)
        finally:
            connection.rollback()
            connection.execute(text("RESET SESSION AUTHORIZATION"))
            connection.commit()


def _insert_owned_report(SessionFactory, report_id: uuid.UUID, subject: str) -> None:
    now = datetime.now(UTC)
    with SessionFactory() as db:
        db.add(
            Report(
                id=report_id,
                status="new",
                status_version=1,
                class_id=1,
                class_name="damaged_tactile_block",
                confidence=0.9,
                bbox_x=0.1,
                bbox_y=0.1,
                bbox_width=0.5,
                bbox_height=0.5,
                captured_at=now,
                source="android",
                image_path=f"postgres-a6-{report_id}.jpg.enc",
                image_content_type="image/jpeg",
                payload={"schema_version": "detect.v2"},
                privacy_subject_hmac=subject,
                account_generation=7,
                created_at=now,
                updated_at=now,
            )
        )
        db.commit()


def test_runtime_request_event_acl_and_deferred_projection_binding() -> None:
    engine, SessionFactory = _session_factory()
    report_id = uuid.uuid4()
    client_request_id = uuid.uuid4()
    subject = "a" * 64
    now = datetime.now(UTC)
    _insert_owned_report(SessionFactory, report_id, subject)
    try:
        create_payload = ReportUserRequestCreateV1(
            client_request_id=client_request_id,
            request_type="CORRECTION",
            request_text="신고 내용을 정정해 주세요.",
        )

        state, created = _run_as_runtime(
            engine,
            lambda db: create_report_user_request(
                db,
                report_id=report_id,
                payload=create_payload,
                privacy_subject=subject,
                account_generation=7,
                now=now,
            ),
        )
        assert created is True
        assert state.status == "RECEIVED"

        def insert_with_explicit_discovery_revision(db) -> None:
            with pytest.raises(DBAPIError) as captured:
                db.execute(
                    text(
                        "INSERT INTO public.report_user_requests ("
                        "id, report_id, client_request_id, request_type, "
                        "request_text, intent_sha256, status, status_version, "
                        "discovery_revision, created_at, updated_at) VALUES ("
                        ":id, :report_id, :client_request_id, 'CORRECTION', "
                        ":request_text, :intent_sha256, 'RECEIVED', 1, "
                        "9007199254740991, :created_at, :updated_at)"
                    ),
                    {
                        "id": uuid.uuid4(),
                        "report_id": report_id,
                        "client_request_id": uuid.uuid4(),
                        "request_text": "revision 우회 시도",
                        "intent_sha256": "c" * 64,
                        "created_at": now,
                        "updated_at": now,
                    },
                )
            assert captured.value.orig.sqlstate == "42501"
            db.rollback()

        _run_as_runtime(engine, insert_with_explicit_discovery_revision)

        identity = AdminSessionIdentity(
            admin_id="admin@example.com",
            session_id=uuid.uuid4(),
            device_id="device-a6-postgres",
            device_label="A6 PostgreSQL integration",
            expires_at=now + timedelta(hours=1),
            step_up_verified_at=now,
        )
        updated = _run_as_runtime(
            engine,
            lambda db: update_admin_request_status(
                db,
                request_id=state.id,
                payload=AdminReportUserRequestStatusUpdateV1(
                    status="ACKNOWLEDGED",
                    expected_version=1,
                    public_response="요청을 확인하고 있습니다.",
                    internal_note="담당자 확인 중",
                ),
                identity=identity,
                correlation_id=uuid.uuid4(),
                query_sha256="b" * 64,
                now=now + timedelta(seconds=1),
            ),
        )
        assert updated.status == "ACKNOWLEDGED"
        assert updated.status_version == 2

        history_page = _run_as_runtime(
            engine,
            lambda db: list_owned_report_request_history(
                db,
                privacy_subject=subject,
                account_generation=7,
                report_id=None,
                limit=10,
                cursor=None,
            ),
        )
        assert history_page.total_count == 1
        assert history_page.next_cursor is None
        assert history_page.items[0].source == "ACTIVE_REQUEST"
        assert history_page.items[0].request_id == state.id
        assert history_page.items[0].request is not None
        assert history_page.items[0].request.status == "ACKNOWLEDGED"

        with SessionFactory() as db:
            item = db.get(ReportUserRequest, state.id)
            assert item is not None
            assert item.status == "ACKNOWLEDGED"
            assert 1 <= item.discovery_revision <= 9_007_199_254_740_991
            events = db.execute(
                select(ReportUserRequestStatusEvent)
                .where(ReportUserRequestStatusEvent.request_id == state.id)
                .order_by(ReportUserRequestStatusEvent.next_version)
            ).scalars().all()
            assert len(events) == 2
            assert events[0].actor_kind == "FIELD"
            assert events[0].actor_id is None
            assert events[0].privacy_subject_hmac == subject
            assert events[0].account_generation == 7
            assert events[1].actor_kind == "ADMIN"
            assert events[1].actor_id == identity.admin_id
            assert events[1].privacy_subject_hmac is None
            assert db.scalar(
                select(AdminOperationAudit).where(
                    AdminOperationAudit.resource_id == str(state.id),
                    AdminOperationAudit.outcome == "SUCCEEDED",
                )
            ) is not None

        def update_without_event(db) -> None:
            db.execute(
                text(
                    "UPDATE report_user_requests SET status = 'RESOLVED', "
                    "status_version = 3, updated_at = clock_timestamp() "
                    "WHERE id = :request_id"
                ),
                {"request_id": state.id},
            )
            with pytest.raises(DBAPIError, match="transition event is missing"):
                db.commit()
            db.rollback()

        _run_as_runtime(engine, update_without_event)

        with engine.connect() as connection:
            privileges = connection.execute(
                text(
                    "SELECT "
                    "has_table_privilege('walksafe_backend_runtime', "
                    "'public.report_user_requests', 'SELECT'), "
                    "has_table_privilege('walksafe_backend_runtime', "
                    "'public.report_user_requests', 'INSERT'), "
                    "has_column_privilege('walksafe_backend_runtime', "
                    "'public.report_user_requests', 'id', 'INSERT'), "
                    "has_column_privilege('walksafe_backend_runtime', "
                    "'public.report_user_requests', 'updated_at', 'INSERT'), "
                    "has_column_privilege('walksafe_backend_runtime', "
                    "'public.report_user_requests', 'discovery_revision', 'INSERT'), "
                    "has_table_privilege('walksafe_backend_runtime', "
                    "'public.report_user_requests', 'DELETE,TRUNCATE'), "
                    "has_column_privilege('walksafe_backend_runtime', "
                    "'public.report_user_requests', 'status', 'UPDATE'), "
                    "has_column_privilege('walksafe_backend_runtime', "
                    "'public.report_user_requests', 'request_text', 'UPDATE'), "
                    "has_table_privilege('walksafe_backend_runtime', "
                    "'public.report_user_request_status_events', 'INSERT'), "
                    "has_table_privilege('walksafe_backend_runtime', "
                    "'public.report_user_request_status_events', 'SELECT,UPDATE,DELETE,TRUNCATE')"
                )
            ).one()
        assert privileges == (
            True,
            False,
            True,
            True,
            False,
            False,
            True,
            False,
            True,
            False,
        )
    finally:
        engine.dispose()


def test_history_exclusive_lock_drains_reverse_commit_shared_creates() -> None:
    engine, SessionFactory = _session_factory()
    first_report_id = uuid.uuid4()
    second_report_id = uuid.uuid4()
    first_request_id = uuid.uuid4()
    subject = "f" * 64
    now = datetime.now(UTC)
    _insert_owned_report(SessionFactory, first_report_id, subject)
    _insert_owned_report(SessionFactory, second_report_id, subject)
    writer_connection = engine.connect()
    WriterSession = sessionmaker(
        bind=writer_connection,
        expire_on_commit=False,
    )
    writer_db = WriterSession()
    try:
        writer_connection.execute(
            text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
        )
        writer_connection.commit()
        lock_privacy_subject_shared(writer_db, subject, 7)
        first_request = ReportUserRequest(
            id=first_request_id,
            report_id=first_report_id,
            client_request_id=uuid.uuid4(),
            request_type="CORRECTION",
            request_text="먼저 revision을 할당하고 나중에 commit합니다.",
            intent_sha256="d" * 64,
            status="RECEIVED",
            status_version=1,
            created_at=now,
            updated_at=now,
        )
        writer_db.add(first_request)
        writer_db.add(
            ReportUserRequestStatusEvent(
                request_id=first_request_id,
                previous_status=None,
                next_status="RECEIVED",
                previous_version=0,
                next_version=1,
                actor_kind="FIELD",
                actor_id=None,
                privacy_subject_hmac=subject,
                account_generation=7,
                session_id=None,
                device_id=None,
                correlation_id=None,
                public_response=None,
                internal_note=None,
                created_at=now,
            )
        )
        writer_db.flush()
        first_revision = int(first_request.discovery_revision)

        second_state, second_created = _run_as_runtime(
            engine,
            lambda db: create_report_user_request(
                db,
                report_id=second_report_id,
                payload=ReportUserRequestCreateV1(
                    client_request_id=uuid.uuid4(),
                    request_type="CORRECTION",
                    request_text="나중 revision을 먼저 commit합니다.",
                ),
                privacy_subject=subject,
                account_generation=7,
                now=now + timedelta(seconds=1),
            ),
        )
        assert second_created is True
        with SessionFactory() as db:
            second_revision = db.scalar(
                select(ReportUserRequest.discovery_revision).where(
                    ReportUserRequest.id == second_state.id
                )
            )
        assert second_revision is not None
        assert first_revision < second_revision

        def history_while_first_writer_is_open(db):
            db.execute(text("SET LOCAL lock_timeout = '250ms'"))
            return list_owned_report_request_history(
                db,
                privacy_subject=subject,
                account_generation=7,
                report_id=None,
                limit=10,
                cursor=None,
            )

        with pytest.raises(UserReportRequestHistoryError) as captured:
            _run_as_runtime(engine, history_while_first_writer_is_open)
        assert captured.value.status_code == 503
        assert captured.value.code == "report_request_history_unavailable"

        writer_db.commit()
        page = _run_as_runtime(
            engine,
            lambda db: list_owned_report_request_history(
                db,
                privacy_subject=subject,
                account_generation=7,
                report_id=None,
                limit=10,
                cursor=None,
            ),
        )
        assert page.total_count == 2
        assert [item.request_id for item in page.items] == [
            second_state.id,
            first_request_id,
        ]
        assert [item.revision for item in page.items] == [
            second_revision,
            first_revision,
        ]
    finally:
        writer_db.rollback()
        writer_db.close()
        if writer_connection.in_transaction():
            writer_connection.rollback()
        writer_connection.execute(text("RESET SESSION AUTHORIZATION"))
        writer_connection.commit()
        writer_connection.close()
        engine.dispose()
