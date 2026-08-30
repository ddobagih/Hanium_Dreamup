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
from backend.app.services.report_user_requests import (
    create_report_user_request,
    update_admin_request_status,
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

        with SessionFactory() as db:
            item = db.get(ReportUserRequest, state.id)
            assert item is not None
            assert item.status == "ACKNOWLEDGED"
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
                    "'public.report_user_requests', 'SELECT,INSERT'), "
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
        assert privileges == (True, False, True, False, True, False)
    finally:
        engine.dispose()
