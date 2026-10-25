"""Direct regression coverage for duplicate-review cycle prevention."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, datetime
import os
from pathlib import Path
from threading import Barrier
import time
import uuid

from alembic import command
from alembic.config import Config
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import Connection
from sqlalchemy.engine import make_url
from sqlalchemy.exc import DBAPIError

from backend.app.models import Report, ReportReviewDecision
from backend.app.schemas import ReportReviewDecisionRequest
from backend.app.services import admin_report_workflow as workflow
from backend.app.services.admin_security import AdminSessionIdentity


def _identity() -> AdminSessionIdentity:
    return AdminSessionIdentity(
        admin_id="reviewer@example.com",
        session_id=uuid.uuid4(),
        device_id="admin-device-0001",
        device_label="review tablet",
        expires_at=datetime(2030, 1, 1, tzinfo=UTC),
        step_up_verified_at=datetime(2026, 9, 24, tzinfo=UTC),
    )


def _payload(target_id: uuid.UUID, *, decision_id: uuid.UUID | None = None) -> ReportReviewDecisionRequest:
    return ReportReviewDecisionRequest(
        decision_id=decision_id or uuid.uuid4(),
        decision="DUPLICATE",
        reason="Duplicate of another report",
        user_visible_reason="This report is a duplicate.",
        duplicate_of_report_id=target_id,
        location_reviewed=False,
        photo_reviewed=False,
        privacy_reviewed=False,
    )


class _Session:
    def __init__(
        self,
        reports: dict[uuid.UUID, Report],
        latest: dict[uuid.UUID, ReportReviewDecision],
        existing: ReportReviewDecision | None = None,
    ) -> None:
        self.reports = reports
        self.latest = latest
        self.existing = existing
        self.added: list[ReportReviewDecision] = []
        self.commits = 0
        self.rollbacks = 0

    def get(self, model: type[object], key: uuid.UUID) -> object | None:
        if model is Report:
            return self.reports.get(key)
        if model is ReportReviewDecision and self.existing is not None and key == self.existing.id:
            return self.existing
        return None

    def scalar(self, statement):
        # Visibility preflight selects the ID; report reads select the entity.
        description, = statement.column_descriptions
        assert description["entity"] is Report
        sql = str(statement)
        assert "NOT (EXISTS (" in sql and "account_deletion_tombstones" in sql
        report_id = statement.compile().params["id_1"]
        report = self.reports.get(report_id)
        if description["expr"] is Report.id:
            return report.id if report is not None else None
        assert description["expr"] is Report
        return report

    def add(self, decision: ReportReviewDecision) -> None:
        self.added.append(decision)

    def commit(self) -> None:
        self.commits += 1

    def rollback(self) -> None:
        self.rollbacks += 1

    def refresh(self, _decision: ReportReviewDecision) -> None:
        pass

    def expunge(self, _decision: ReportReviewDecision) -> None:
        pass


def _service_session(
    monkeypatch: pytest.MonkeyPatch,
    reports: dict[uuid.UUID, Report],
    latest: dict[uuid.UUID, ReportReviewDecision],
    existing: ReportReviewDecision | None = None,
) -> _Session:
    db = _Session(reports, latest, existing)
    monkeypatch.setattr(workflow, "_secured_review_dml_required", lambda _db: False)
    monkeypatch.setattr(workflow, "_locked_report", lambda _db, report_id: reports[report_id])
    monkeypatch.setattr(workflow, "_latest_review_decision", lambda _db, report_id: latest.get(report_id))
    monkeypatch.setattr(workflow, "_active_duplicate_dependents_exist", lambda _db, _report_id: False)
    return db


@pytest.mark.parametrize("length", [2, 3])
def test_service_rejects_indirect_duplicate_cycle(
    monkeypatch: pytest.MonkeyPatch,
    length: int,
) -> None:
    ids = [uuid.uuid4() for _ in range(length)]
    reports = {report_id: Report(id=report_id) for report_id in ids}
    latest = {
        ids[index]: ReportReviewDecision(
            report_id=ids[index],
            revision=1,
            decision="DUPLICATE",
            duplicate_of_report_id=ids[index + 1],
        )
        for index in range(1, length - 1)
    }
    latest[ids[-1]] = ReportReviewDecision(
        report_id=ids[-1],
        revision=1,
        decision="DUPLICATE",
        duplicate_of_report_id=ids[0],
    )
    db = _service_session(monkeypatch, reports, latest)

    with pytest.raises(workflow.AdminReportWorkflowError) as rejected:
        workflow.append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=ids[0],
            payload=_payload(ids[1]),
            identity=_identity(),
            correlation_id=uuid.uuid4(),
        )

    assert (rejected.value.code, rejected.value.status_code) == (
        "duplicate_report_cycle", 422
    )
    assert db.added == []
    assert (db.commits, db.rollbacks) == (0, 1)


def test_service_rejects_nonrepresentative_target(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_id, target_id, terminal_id = (uuid.uuid4() for _ in range(3))
    reports = {report_id: Report(id=report_id) for report_id in (source_id, target_id, terminal_id)}
    latest = {
        target_id: ReportReviewDecision(
            report_id=target_id,
            revision=1,
            decision="DUPLICATE",
            duplicate_of_report_id=terminal_id,
        ),
        terminal_id: ReportReviewDecision(
            report_id=terminal_id,
            revision=1,
            decision="REJECTED",
        ),
    }
    db = _service_session(monkeypatch, reports, latest)
    payload = _payload(target_id)
    identity = _identity()

    with pytest.raises(workflow.AdminReportWorkflowError) as rejected:
        workflow.append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=source_id,
            payload=payload,
            identity=identity,
            correlation_id=uuid.uuid4(),
        )
    assert (rejected.value.code, rejected.value.status_code) == (
        "duplicate_report_target_not_representative", 422
    )
    assert db.added == []
    assert (db.commits, db.rollbacks) == (0, 1)


def test_service_rejects_duplicate_source_with_active_dependents_and_preserves_replay(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_id, target_id, child_id = (uuid.uuid4() for _ in range(3))
    reports = {report_id: Report(id=report_id) for report_id in (source_id, target_id, child_id)}
    latest = {
        child_id: ReportReviewDecision(
            report_id=child_id,
            revision=1,
            decision="DUPLICATE",
            duplicate_of_report_id=source_id,
        ),
    }
    db = _service_session(monkeypatch, reports, latest)
    monkeypatch.setattr(workflow, "_active_duplicate_dependents_exist", lambda _db, _id: True)
    payload = _payload(target_id)
    identity = _identity()

    with pytest.raises(workflow.AdminReportWorkflowError) as rejected:
        workflow.append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=source_id,
            payload=payload,
            identity=identity,
            correlation_id=uuid.uuid4(),
        )
    assert (rejected.value.code, rejected.value.status_code) == (
        "duplicate_report_cycle", 422
    )
    assert db.added == []

    existing = ReportReviewDecision(
        id=payload.decision_id,
        report_id=source_id,
        content_revision=0,
        revision=1,
        decision="DUPLICATE",
        reason=payload.reason,
        user_visible_reason=payload.user_visible_reason,
        duplicate_of_report_id=target_id,
        location_reviewed=False,
        photo_reviewed=False,
        privacy_reviewed=False,
        admin_id=identity.admin_id,
    )
    replay_db = _service_session(monkeypatch, reports, latest, existing)
    assert workflow.append_report_review_decision(
        replay_db,  # type: ignore[arg-type]
        report_id=source_id,
        payload=payload,
        identity=identity,
        correlation_id=uuid.uuid4(),
    ) is existing


def test_secured_service_maps_nonrepresentative_target_to_actionable_422(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_id, target_id = uuid.uuid4(), uuid.uuid4()
    db = _Session(
        {source_id: Report(id=source_id), target_id: Report(id=target_id)},
        {},
    )

    class _Stored:
        def mappings(self) -> _Stored:
            return self

        def one(self) -> dict[str, str]:
            return {"result_status": "DUPLICATE_TARGET_NOT_REPRESENTATIVE"}

    monkeypatch.setattr(workflow, "_secured_review_dml_required", lambda _db: True)
    monkeypatch.setattr(workflow, "_resolve_admin_credential_issuer_key", lambda _db, _key: "test-key")
    monkeypatch.setattr(db, "execute", lambda *_args, **_kwargs: _Stored(), raising=False)

    with pytest.raises(workflow.AdminReportWorkflowError) as rejected:
        workflow.append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=source_id,
            payload=_payload(target_id),
            identity=_identity(),
            correlation_id=uuid.uuid4(),
            proof_challenge_id=uuid.uuid4(),
            proof_request_body=b"{}",
        )
    assert (rejected.value.code, rejected.value.status_code) == (
        "duplicate_report_target_not_representative", 422
    )
    assert db.added == []


@pytest.mark.parametrize(
    ("sqlstate", "expected_code"),
    [
        ("P1837", "duplicate_report_target_not_representative"),
        ("P1838", "duplicate_report_cycle"),
    ],
)
def test_secured_service_maps_table_guard_errors_to_422(
    monkeypatch: pytest.MonkeyPatch,
    sqlstate: str,
    expected_code: str,
) -> None:
    source_id, target_id = uuid.uuid4(), uuid.uuid4()
    db = _Session(
        {source_id: Report(id=source_id), target_id: Report(id=target_id)},
        {},
    )

    class _GuardError(Exception):
        pass

    error = _GuardError("duplicate guard")
    error.sqlstate = sqlstate  # type: ignore[attr-defined]

    def fail_guard(*_args: object, **_kwargs: object) -> None:
        raise DBAPIError("review append", {}, error)

    monkeypatch.setattr(workflow, "_secured_review_dml_required", lambda _db: True)
    monkeypatch.setattr(workflow, "_resolve_admin_credential_issuer_key", lambda _db, _key: "test-key")
    monkeypatch.setattr(db, "execute", fail_guard, raising=False)

    with pytest.raises(workflow.AdminReportWorkflowError) as rejected:
        workflow.append_report_review_decision(
            db,  # type: ignore[arg-type]
            report_id=source_id,
            payload=_payload(target_id),
            identity=_identity(),
            correlation_id=uuid.uuid4(),
            proof_challenge_id=uuid.uuid4(),
            proof_request_body=b"{}",
        )
    assert (rejected.value.code, rejected.value.status_code) == (expected_code, 422)
    assert db.added == []


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_postgres_function_rejects_cycles_and_concurrent_mutual_links(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    configured_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip())
    database_name = f"walksafe_duplicate_cycle_test_{uuid.uuid4().hex}"
    disposable_url = configured_url.set(database=database_name)
    migration_url = disposable_url.render_as_string(hide_password=False)
    admin_engine = create_engine(configured_url, pool_pre_ping=True)
    engine = create_engine(disposable_url, pool_pre_ping=True)
    quoted_database = admin_engine.dialect.identifier_preparer.quote(database_name)
    created_database = False
    ids = [uuid.uuid4() for _ in range(6)]

    def append_using(connection: Connection, source_id: uuid.UUID, target_id: uuid.UUID) -> str:
        return connection.execute(
            text(
                "SELECT result_status FROM public.walksafe_append_report_review_decision("
                "CAST(:decision_id AS uuid), CAST(:report_id AS uuid), 0, "
                "'DUPLICATE', 'Duplicate of another report', "
                "'This report is a duplicate.', CAST(:target_id AS uuid), "
                "false, false, false, NULL, 'reviewer@example.com', "
                "CAST(:session_id AS uuid), 'admin-device-0001', "
                "CAST(:correlation_id AS uuid), clock_timestamp(), NULL, NULL)"
            ),
            {
                "decision_id": uuid.uuid4(),
                "report_id": source_id,
                "target_id": target_id,
                "session_id": uuid.uuid4(),
                "correlation_id": uuid.uuid4(),
            },
        ).scalar_one()

    def append(source_id: uuid.UUID, target_id: uuid.UUID) -> str:
        with engine.begin() as connection:
            connection.exec_driver_sql("SET LOCAL ROLE walksafe_report_evidence_owner")
            return append_using(connection, source_id, target_id)

    try:
        with admin_engine.connect().execution_options(isolation_level="AUTOCOMMIT") as connection:
            connection.exec_driver_sql(f"CREATE DATABASE {quoted_database}")
            created_database = True
        monkeypatch.setenv("DATABASE_URL", migration_url)
        monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", migration_url)
        config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
        command.upgrade(config, "head")

        with engine.begin() as connection:
            connection.execute(
                text(
                    "CREATE OR REPLACE FUNCTION "
                    "public.walksafe_lock_admin_original_access_session("
                    "p_admin_id text, p_session_id uuid, p_device_id text, "
                    "p_observed_at timestamptz, p_runtime_totp_secret text, "
                    "p_credential_issuer_key text) RETURNS boolean "
                    "AS $test$ BEGIN RETURN true; END $test$ "
                    "LANGUAGE plpgsql SECURITY DEFINER "
                    "SET search_path = pg_catalog, pg_temp"
                )
            )
            for report_id in ids:
                connection.execute(
                    text(
                        "INSERT INTO public.reports ("
                        "id, class_id, class_name, confidence, bbox_x, bbox_y, "
                        "bbox_width, bbox_height, captured_at, source, image_path, "
                        "image_content_type, metadata) VALUES ("
                        ":report_id, 0, 'damaged_tactile_block', 0.9, 0.1, 0.1, "
                        "0.5, 0.5, clock_timestamp(), 'android', :image_path, "
                        "'image/jpeg', '{}'::jsonb)"
                    ),
                    {"report_id": report_id, "image_path": f"/uploads/{report_id}.jpg"},
                )

        assert append(ids[0], ids[1]) == "CREATED"
        assert append(ids[1], ids[0]) == "DUPLICATE_CYCLE"
        assert append(ids[1], ids[2]) == "DUPLICATE_CYCLE"
        assert append(ids[2], ids[0]) == "DUPLICATE_TARGET_NOT_REPRESENTATIVE"
        assert append(ids[2], ids[1]) == "CREATED"

        barrier = Barrier(2)

        def concurrent_append(source_id: uuid.UUID, target_id: uuid.UUID) -> str:
            barrier.wait(timeout=10)
            return append(source_id, target_id)

        with ThreadPoolExecutor(max_workers=2) as pool:
            first = pool.submit(concurrent_append, ids[3], ids[4])
            second = pool.submit(concurrent_append, ids[4], ids[3])
            assert sorted((first.result(timeout=20), second.result(timeout=20))) == [
                "CREATED", "DUPLICATE_CYCLE"
            ]

        # A call can enter the predecessor function before migration commits,
        # then resume its old body afterwards. The new table trigger must stop
        # that in-flight call even though the function body is now replaced.
        command.downgrade(config, "202609300008")

        def pending_advisory_locks() -> int:
            with engine.connect() as observer:
                return int(observer.execute(text(
                    "SELECT count(*) FROM pg_catalog.pg_locks "
                    "WHERE locktype = 'advisory' AND NOT granted "
                    "AND database = (SELECT oid FROM pg_catalog.pg_database "
                    "WHERE datname = pg_catalog.current_database())"
                )).scalar_one())

        def wait_for_pending_locks(count: int) -> None:
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                if pending_advisory_locks() >= count:
                    return
                time.sleep(0.05)
            pytest.fail(f"expected {count} waiting duplicate advisory locks")

        with engine.connect() as blocker:
            blocker_transaction = blocker.begin()
            blocker.execute(text(
                "SELECT pg_catalog.pg_advisory_xact_lock("
                "pg_catalog.hashtextextended('walksafe-report-duplicate-cycle-v1', 0))"
            ))
            with ThreadPoolExecutor(max_workers=2) as pool:
                try:
                    upgrade = pool.submit(command.upgrade, config, "202609300009")
                    wait_for_pending_locks(1)
                    old_call = pool.submit(append, ids[1], ids[5])
                    wait_for_pending_locks(2)
                finally:
                    blocker_transaction.commit()
                upgrade.result(timeout=20)
                with pytest.raises(DBAPIError) as blocked_old_call:
                    old_call.result(timeout=20)
                assert getattr(blocked_old_call.value.orig, "sqlstate", None) == "P1838"

        # Existing signed history is not silently retargeted by upgrade. The
        # migration refuses an active chain created by the predecessor.
        command.downgrade(config, "202609300008")
        with engine.connect() as writer:
            transaction = writer.begin()
            try:
                writer.exec_driver_sql("SET LOCAL ROLE walksafe_report_evidence_owner")
                assert append_using(writer, ids[1], ids[5]) == "CREATED"
                with ThreadPoolExecutor(max_workers=1) as pool:
                    upgrade = pool.submit(command.upgrade, config, "202609300009")
                    waiting_on_duplicate_lock = False
                    deadline = time.monotonic() + 5
                    while time.monotonic() < deadline:
                        with engine.connect() as observer:
                            waiting_on_duplicate_lock = bool(
                                observer.execute(
                                    text(
                                        "SELECT EXISTS (SELECT 1 FROM pg_catalog.pg_locks "
                                        "WHERE locktype = 'advisory' AND NOT granted "
                                        "AND database = (SELECT oid FROM pg_catalog.pg_database "
                                        "WHERE datname = pg_catalog.current_database()))"
                                    )
                                ).scalar_one()
                            )
                        if waiting_on_duplicate_lock or upgrade.done():
                            break
                        time.sleep(0.05)
                    assert waiting_on_duplicate_lock, (
                        "upgrade did not wait for the writer's duplicate lock"
                    )
                    transaction.commit()
                    with pytest.raises(RuntimeError, match="active indirect duplicate links"):
                        upgrade.result(timeout=20)
            finally:
                if transaction.is_active:
                    transaction.rollback()
        with engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM public.alembic_version")
            ).scalar_one() == "202609300008"
            assert connection.execute(
                text(
                    "SELECT duplicate_of_report_id FROM public.report_review_decisions "
                    "WHERE report_id = :report_id ORDER BY revision DESC LIMIT 1"
                ),
                {"report_id": ids[0]},
            ).scalar_one() == ids[1]
    finally:
        engine.dispose()
        if created_database:
            with admin_engine.connect().execution_options(isolation_level="AUTOCOMMIT") as connection:
                connection.exec_driver_sql(f"DROP DATABASE IF EXISTS {quoted_database}")
        admin_engine.dispose()
