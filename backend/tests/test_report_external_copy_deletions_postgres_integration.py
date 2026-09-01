from __future__ import annotations

from datetime import UTC, datetime, timedelta
import os
from pathlib import Path
import uuid

from alembic import command
from alembic.config import Config
import pytest
from sqlalchemy import create_engine, func, select, text
from sqlalchemy.exc import DBAPIError
from sqlalchemy.orm import sessionmaker

from backend.app.api.health import _database_readiness
from backend.app.models import AdminOperationAudit, ReportDeletionExternalCopyEvent
from backend.app.schemas import AdminReportDeletionExternalCopyEventCreateV1
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.report_deletion import (
    ReportDeletionError,
    get_owned_report_deletion_status,
)
from backend.app.services.report_external_copy_deletions import (
    ReportExternalCopyDeletionError,
    list_admin_external_copy_deletions,
    record_admin_external_copy_deletion_event,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


REQUEST_ID = uuid.UUID("71111111-1111-4111-8111-111111111111")
REPORT_ID = uuid.UUID("72222222-2222-4222-8222-222222222222")
TOMBSTONE_ID = uuid.UUID("73333333-3333-4333-8333-333333333333")
COPY_ID = uuid.UUID("74444444-4444-4444-8444-444444444444")
SOURCE_EVENT_ID = uuid.UUID("75555555-5555-4555-8555-555555555555")
SUBJECT = "a" * 64


def _database_url() -> str:
    return os.environ["WALKSAFE_TEST_DATABASE_URL"].strip()


def _alembic_config() -> Config:
    return Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))


def _identity(now: datetime) -> AdminSessionIdentity:
    return AdminSessionIdentity(
        admin_id="admin@example.com",
        session_id=uuid.uuid4(),
        device_id="device-0001",
        device_label="관리자 기기",
        expires_at=now + timedelta(hours=1),
        step_up_verified_at=None,
    )


def _utc_text(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


@pytest.fixture(scope="module")
def external_copy_database() -> tuple[object, sessionmaker]:
    command.upgrade(_alembic_config(), "head")
    engine = create_engine(_database_url(), pool_pre_ping=True)
    SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)
    deleted_at = datetime.now(UTC) - timedelta(hours=1)
    with engine.begin() as connection:
        assert connection.execute(
            text("SELECT version_num FROM alembic_version")
        ).scalar_one() == "202609010003"
        for table_name in (
            "report_deletion_tombstones",
            "report_deletion_external_copy_states",
        ):
            connection.execute(
                text(f"ALTER TABLE {table_name} DISABLE TRIGGER USER")
            )
        try:
            connection.execute(
                text(
                    "INSERT INTO report_deletion_tombstones "
                    "(id, request_id, report_id, privacy_subject_hmac, "
                    "account_generation, request_status_version, "
                    "external_copy_count, deleted_at) VALUES "
                    "(:id, :request_id, :report_id, :subject, 3, 2, 1, :deleted_at)"
                ),
                {
                    "id": TOMBSTONE_ID,
                    "request_id": REQUEST_ID,
                    "report_id": REPORT_ID,
                    "subject": SUBJECT,
                    "deleted_at": deleted_at,
                },
            )
            connection.execute(
                text(
                    "INSERT INTO report_deletion_external_copy_states "
                    "(id, deletion_tombstone_id, source_delivery_event_id, "
                    "package_id, institution, status, observed_at, recorded_at) "
                    "VALUES (:id, :tombstone_id, :source_id, NULL, "
                    "'서울시', 'RESOLVED', :observed_at, :recorded_at)"
                ),
                {
                    "id": COPY_ID,
                    "tombstone_id": TOMBSTONE_ID,
                    "source_id": SOURCE_EVENT_ID,
                    "observed_at": deleted_at - timedelta(hours=1),
                    "recorded_at": deleted_at,
                },
            )
        finally:
            for table_name in reversed(
                (
                    "report_deletion_tombstones",
                    "report_deletion_external_copy_states",
                )
            ):
                connection.execute(
                    text(f"ALTER TABLE {table_name} ENABLE TRIGGER USER")
                )
    yield engine, SessionFactory
    engine.dispose()


def test_original_delivery_resolved_is_not_external_deletion_confirmation(
    external_copy_database: tuple[object, sessionmaker],
) -> None:
    _engine, SessionFactory = external_copy_database
    with SessionFactory() as db:
        status = get_owned_report_deletion_status(
            db,
            request_id=REQUEST_ID,
            privacy_subject=SUBJECT,
            account_generation=3,
        )
        db.rollback()
    assert status.state == "DELETED"
    assert status.external_copy_count == 1
    assert len(status.external_copies) == 1
    assert status.external_copies[0].institution == "서울시"
    assert status.external_copies[0].state == "NOT_REQUESTED"
    assert status.external_copies[0].status_recorded_at is None


def test_owner_generation_and_request_mismatches_are_concealed_404(
    external_copy_database: tuple[object, sessionmaker],
) -> None:
    _engine, SessionFactory = external_copy_database
    for request_id, subject, generation in (
        (REQUEST_ID, "b" * 64, 3),
        (REQUEST_ID, SUBJECT, 4),
        (uuid.uuid4(), SUBJECT, 3),
    ):
        with SessionFactory() as db, pytest.raises(ReportDeletionError) as captured:
            get_owned_report_deletion_status(
                db,
                request_id=request_id,
                privacy_subject=subject,
                account_generation=generation,
            )
        assert captured.value.status_code == 404
        assert captured.value.code == "report_deletion_not_found"


def test_manual_event_flow_cas_idempotency_and_user_minimum_projection(
    external_copy_database: tuple[object, sessionmaker],
) -> None:
    engine, SessionFactory = external_copy_database
    now = datetime.now(UTC)
    identity = _identity(now)
    request_sent = AdminReportDeletionExternalCopyEventCreateV1(
        state="REQUEST_SENT",
        expected_revision=0,
        idempotency_key=uuid.uuid4(),
        observed_at=_utc_text(now - timedelta(minutes=3)),
    )
    with engine.connect() as runtime_connection:
        runtime_connection.execute(text("SET ROLE walksafe_backend_runtime"))
        try:
            RuntimeSession = sessionmaker(
                bind=runtime_connection,
                expire_on_commit=False,
            )
            with RuntimeSession() as db:
                first, created = record_admin_external_copy_deletion_event(
                    db,
                    request_id=REQUEST_ID,
                    copy_id=COPY_ID,
                    payload=request_sent,
                    identity=identity,
                    correlation_id=uuid.uuid4(),
                    query_sha256="c" * 64,
                )
            runtime_connection.commit()
        finally:
            if runtime_connection.in_transaction():
                runtime_connection.rollback()
            runtime_connection.execute(text("RESET ROLE"))
            runtime_connection.commit()
    assert created is True
    assert (first.state, first.revision) == ("REQUEST_SENT", 1)

    with SessionFactory() as db:
        replay, created = record_admin_external_copy_deletion_event(
            db,
            request_id=REQUEST_ID,
            copy_id=COPY_ID,
            payload=request_sent,
            identity=identity,
            correlation_id=uuid.uuid4(),
            query_sha256="d" * 64,
        )
    assert created is False
    assert (replay.state, replay.revision) == ("REQUEST_SENT", 1)

    conflicting_intent = AdminReportDeletionExternalCopyEventCreateV1(
        state="REQUEST_SENT",
        expected_revision=1,
        idempotency_key=request_sent.idempotency_key,
        observed_at=_utc_text(now - timedelta(minutes=2)),
    )
    with SessionFactory() as db, pytest.raises(
        ReportExternalCopyDeletionError
    ) as captured:
        record_admin_external_copy_deletion_event(
            db,
            request_id=REQUEST_ID,
            copy_id=COPY_ID,
            payload=conflicting_intent,
            identity=identity,
            correlation_id=uuid.uuid4(),
            query_sha256="e" * 64,
        )
    assert captured.value.status_code == 409
    assert captured.value.code == "report_external_copy_idempotency_conflict"

    stale = AdminReportDeletionExternalCopyEventCreateV1(
        state="REQUEST_SENT",
        expected_revision=0,
        idempotency_key=uuid.uuid4(),
        observed_at=_utc_text(now - timedelta(minutes=2)),
    )
    with SessionFactory() as db, pytest.raises(
        ReportExternalCopyDeletionError
    ) as captured:
        record_admin_external_copy_deletion_event(
            db,
            request_id=REQUEST_ID,
            copy_id=COPY_ID,
            payload=stale,
            identity=identity,
            correlation_id=uuid.uuid4(),
            query_sha256="f" * 64,
        )
    assert captured.value.status_code == 409
    assert captured.value.latest is not None
    assert captured.value.latest["revision"] == 1

    acknowledged = AdminReportDeletionExternalCopyEventCreateV1(
        state="REPLY_ACKNOWLEDGED",
        expected_revision=1,
        idempotency_key=uuid.uuid4(),
        observed_at=_utc_text(now - timedelta(minutes=2)),
        institution_reference="case:ack-1234",
    )
    with SessionFactory() as db:
        second, created = record_admin_external_copy_deletion_event(
            db,
            request_id=REQUEST_ID,
            copy_id=COPY_ID,
            payload=acknowledged,
            identity=identity,
            correlation_id=uuid.uuid4(),
            query_sha256="1" * 64,
        )
    assert created is True
    assert (second.state, second.revision) == ("REPLY_ACKNOWLEDGED", 2)

    confirmed = AdminReportDeletionExternalCopyEventCreateV1(
        state="REPLY_DELETION_CONFIRMED",
        expected_revision=2,
        idempotency_key=uuid.uuid4(),
        observed_at=_utc_text(now - timedelta(minutes=1)),
        institution_reference="case:done-1234",
        evidence_sha256="9" * 64,
    )
    with SessionFactory() as db:
        third, created = record_admin_external_copy_deletion_event(
            db,
            request_id=REQUEST_ID,
            copy_id=COPY_ID,
            payload=confirmed,
            identity=identity,
            correlation_id=uuid.uuid4(),
            query_sha256="2" * 64,
        )
    assert created is True
    assert (third.state, third.revision) == (
        "REPLY_DELETION_CONFIRMED",
        3,
    )

    after_terminal = AdminReportDeletionExternalCopyEventCreateV1(
        state="REPLY_DECLINED",
        expected_revision=3,
        idempotency_key=uuid.uuid4(),
        observed_at=_utc_text(now),
        institution_reference="case:late-1234",
    )
    with SessionFactory() as db, pytest.raises(
        ReportExternalCopyDeletionError
    ) as captured:
        record_admin_external_copy_deletion_event(
            db,
            request_id=REQUEST_ID,
            copy_id=COPY_ID,
            payload=after_terminal,
            identity=identity,
            correlation_id=uuid.uuid4(),
            query_sha256="3" * 64,
        )
    assert captured.value.status_code == 422
    assert captured.value.code == "report_external_copy_transition_invalid"

    with SessionFactory() as db:
        user_status = get_owned_report_deletion_status(
            db,
            request_id=REQUEST_ID,
            privacy_subject=SUBJECT,
            account_generation=3,
        )
        db.rollback()
    assert user_status.external_copy_count == len(user_status.external_copies) == 1
    assert user_status.external_copies[0].state == "REPLY_DELETION_CONFIRMED"
    assert user_status.external_copies[0].status_recorded_at is not None
    assert set(user_status.external_copies[0].__dataclass_fields__) == {
        "institution",
        "state",
        "status_recorded_at",
    }
    with SessionFactory() as db:
        assert db.scalar(
            select(func.count(AdminOperationAudit.id)).where(
                AdminOperationAudit.operation
                == "report.external_copy_deletion.record",
                AdminOperationAudit.outcome == "SUCCEEDED",
            )
        ) == 4
        db.rollback()

    with SessionFactory() as db:
        page = list_admin_external_copy_deletions(
            db,
            limit=25,
            cursor=None,
            request_id=REQUEST_ID,
        )
        db.rollback()
    assert len(page.items) == 1
    item = page.items[0]
    assert item.copy_id == COPY_ID
    assert item.delivery_status_at_local_deletion == "RESOLVED"
    assert item.state == "REPLY_DELETION_CONFIRMED"
    assert item.allowed_next_states == ()

    with engine.begin() as connection:
        privileges = connection.execute(
            text(
                "SELECT "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.report_deletion_external_copy_events', 'SELECT'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.report_deletion_external_copy_events', 'INSERT'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.report_deletion_external_copy_events', 'UPDATE'), "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.report_deletion_external_copy_events', 'DELETE'), "
                "has_table_privilege('walksafe_report_deletion_worker', "
                "'public.report_deletion_external_copy_events', 'SELECT'), "
                "has_table_privilege('walksafe_report_deletion_worker', "
                "'public.report_deletion_external_copy_events', 'INSERT')"
            )
        ).one()
    assert tuple(privileges) == (True, True, False, False, False, False)
    assert _database_readiness(_database_url()) == {
        "ready": True,
        "current_revision": "202609010003",
    }

    with SessionFactory() as db:
        db.add(
            ReportDeletionExternalCopyEvent(
                id=uuid.uuid4(),
                external_copy_state_id=COPY_ID,
                revision=4,
                expected_revision=3,
                idempotency_key=uuid.uuid4(),
                state="REQUEST_SENT",
                institution_reference=None,
                evidence_sha256=None,
                observed_at=now,
                admin_id="admin@example.com",
                session_id=uuid.uuid4(),
                device_id="device-0001",
                correlation_id=uuid.uuid4(),
            )
        )
        with pytest.raises(DBAPIError):
            db.commit()
        db.rollback()

    with pytest.raises(DBAPIError):
        command.downgrade(_alembic_config(), "202608300005")
    with engine.connect() as connection:
        # PostgreSQL rolls back the whole multi-revision downgrade transaction.
        assert connection.execute(
            text("SELECT version_num FROM alembic_version")
        ).scalar_one() == "202609010003"
        assert connection.execute(
            text("SELECT count(*) FROM report_deletion_external_copy_events")
        ).scalar_one() == 3

    with SessionFactory() as db:
        event = db.scalar(
            select(ReportDeletionExternalCopyEvent).where(
                ReportDeletionExternalCopyEvent.external_copy_state_id == COPY_ID,
                ReportDeletionExternalCopyEvent.revision == 3,
            )
        )
        assert event is not None
        event.state = "REPLY_DECLINED"
        with pytest.raises(DBAPIError):
            db.commit()
        db.rollback()


def test_admin_list_rejects_zero_snapshot_count_mismatch(
    external_copy_database: tuple[object, sessionmaker],
) -> None:
    engine, SessionFactory = external_copy_database
    inconsistent_request_id = uuid.uuid4()
    with engine.begin() as connection:
        connection.execute(
            text("ALTER TABLE report_deletion_tombstones DISABLE TRIGGER USER")
        )
        try:
            connection.execute(
                text(
                    "INSERT INTO report_deletion_tombstones "
                    "(id, request_id, report_id, privacy_subject_hmac, "
                    "account_generation, request_status_version, "
                    "external_copy_count, deleted_at) VALUES "
                    "(:id, :request_id, :report_id, :subject, 3, 2, 1, "
                    "clock_timestamp())"
                ),
                {
                    "id": uuid.uuid4(),
                    "request_id": inconsistent_request_id,
                    "report_id": uuid.uuid4(),
                    "subject": "b" * 64,
                },
            )
        finally:
            connection.execute(
                text("ALTER TABLE report_deletion_tombstones ENABLE TRIGGER USER")
            )
    with SessionFactory() as db, pytest.raises(
        ReportExternalCopyDeletionError
    ) as captured:
        list_admin_external_copy_deletions(
            db,
            limit=25,
            cursor=None,
            request_id=inconsistent_request_id,
        )
    assert captured.value.status_code == 503
    assert captured.value.code == "report_external_copy_evidence_inconsistent"
