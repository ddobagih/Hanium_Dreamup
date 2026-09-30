from __future__ import annotations

from datetime import UTC, datetime, timedelta
import hashlib
from pathlib import Path
from types import SimpleNamespace
import uuid

import pytest
from fastapi import HTTPException

from backend.app.api import reports
from backend.app.field_test_security import (
    FieldTestAccess,
    VerifiedActorAssertion,
    required_field_test_access,
    requires_account_generation,
)
from backend.app.models import Report
from backend.app.schemas import ReportV2Metadata


REPORT_ID = uuid.UUID("aaaaaaaa-1111-4111-8111-111111111111")
MARKER_ID = uuid.UUID("22222222-2222-4222-8222-222222222222")
SUBJECT = "a" * 64
METADATA_BYTES = b'{"schema_version":"detect.v2"}'
IMAGE_BYTES = b"jpeg-bytes"
NOW = datetime(2026, 8, 29, 1, 2, 3, tzinfo=UTC)


def _contract() -> reports.ReportTransportContract:
    return reports.ReportTransportContract(
        report_id=REPORT_ID,
        payload_sha256=reports.report_transport_payload_sha256(
            REPORT_ID,
            METADATA_BYTES,
            IMAGE_BYTES,
        ),
        payload_bytes=len(METADATA_BYTES) + len(IMAGE_BYTES),
    )


def _metadata() -> ReportV2Metadata:
    return ReportV2Metadata.model_validate(
        {
            "schema_version": "detect.v2",
            "model_key": "custom_tactile",
            "source_model": "fake/custom-tactile-contract",
            "model_class_id": 1,
            "class_name": "damaged_tactile_block",
            "category": "tactile",
            "confidence": 0.91,
            "bbox": {"x": 0.2, "y": 0.35, "width": 0.4, "height": 0.22},
            "threshold_used": 0.25,
            "captured_at": "2026-08-29T01:00:00Z",
            "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
            "heading": 181.0,
            "trigger": "auto",
            "auto_reported": True,
        }
    )


def _persist(
    monkeypatch: pytest.MonkeyPatch,
    *,
    duplicate_ids: list[uuid.UUID] | None = None,
) -> tuple[object, list[Report]]:
    writes: list[Report] = []
    duplicate_candidates = [
        SimpleNamespace(id=value) for value in (duplicate_ids or [])
    ]
    monkeypatch.setattr(
        reports,
        "_privacy_report_binding",
        lambda **_kwargs: (SUBJECT, 7),
    )
    monkeypatch.setattr(
        reports,
        "lock_report_ingest_transaction",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        reports,
        "_report_transport_replay",
        lambda *_args, **_kwargs: writes[0] if writes else None,
    )
    monkeypatch.setattr(
        reports,
        "lock_duplicate_candidate_window",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        reports,
        "find_duplicate_candidates_v2",
        lambda *_args, **_kwargs: duplicate_candidates,
    )
    monkeypatch.setattr(
        reports,
        "find_active_auto_report_cooldown",
        lambda *_args, **_kwargs: pytest.fail(
            "transport reports must not use cooldown as idempotency"
        ),
    )
    monkeypatch.setattr(
        reports,
        "_idempotent_report_replay",
        lambda *_args, **_kwargs: pytest.fail(
            "transport reports must not use inferred replay"
        ),
    )

    def commit(_db: object, report: Report, **_kwargs: object) -> None:
        report.created_at = NOW
        report.updated_at = NOW
        writes.append(report)

    monkeypatch.setattr(reports, "_commit_new_report_with_image", commit)
    settings = SimpleNamespace(
        field_test_security_enabled=False,
        upload_dir=Path("/tmp/walksafe-report-transport-tests"),
        maintenance_lock_path=None,
        maintenance_lock_group_gid=None,
    )
    response = reports._persist_v2_report(
        SimpleNamespace(rollback=lambda: None),  # type: ignore[arg-type]
        parsed=_metadata(),
        content=IMAGE_BYTES,
        content_type="image/jpeg",
        actor_id="field@example.com",
        account_generation=7,
        settings=settings,  # type: ignore[arg-type]
        key_manager=object(),  # type: ignore[arg-type]
        transport_contract=_contract(),
    )
    return response, writes


def test_payload_digest_uses_exact_framing_and_bytes() -> None:
    expected = hashlib.sha256(
        b"walksafe-report-payload-v1\0"
        + str(REPORT_ID).encode("ascii")
        + len(METADATA_BYTES).to_bytes(8, "big")
        + METADATA_BYTES
        + len(IMAGE_BYTES).to_bytes(8, "big")
        + IMAGE_BYTES
    ).hexdigest()

    assert reports.report_transport_payload_sha256(
        REPORT_ID,
        METADATA_BYTES,
        IMAGE_BYTES,
    ) == expected


def test_zero_headers_is_legacy_and_partial_headers_fail() -> None:
    assert reports._resolve_report_transport_contract(None, None, None) is None

    with pytest.raises(HTTPException) as captured:
        reports._resolve_report_transport_contract(str(REPORT_ID), None, None)
    assert captured.value.status_code == 422
    assert captured.value.detail["code"] == "report_transport_headers_incomplete"


@pytest.mark.parametrize(
    ("report_id", "payload_sha256", "payload_bytes"),
    [
        (str(REPORT_ID).upper(), "a" * 64, "1"),
        (str(REPORT_ID), "A" * 64, "1"),
        (str(REPORT_ID), "a" * 64, "0"),
    ],
)
def test_transport_headers_require_canonical_values(
    report_id: str,
    payload_sha256: str,
    payload_bytes: str,
) -> None:
    with pytest.raises(HTTPException) as captured:
        reports._resolve_report_transport_contract(
            report_id,
            payload_sha256,
            payload_bytes,
        )
    assert captured.value.status_code == 422


def test_declared_hash_or_bytes_mismatch_is_rejected_before_persistence() -> None:
    contract = _contract()
    for invalid in (
        reports.ReportTransportContract(
            REPORT_ID,
            "f" * 64,
            contract.payload_bytes,
        ),
        reports.ReportTransportContract(
            REPORT_ID,
            contract.payload_sha256,
            contract.payload_bytes + 1,
        ),
    ):
        with pytest.raises(HTTPException) as captured:
            reports._validate_report_transport_payload(
                invalid,
                metadata_bytes=METADATA_BYTES,
                image_bytes=IMAGE_BYTES,
            )
        assert captured.value.status_code == 422
        assert captured.value.detail["code"] == "report_transport_payload_mismatch"


def test_persistence_keeps_transport_digest_separate_from_stored_image_digest(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    # Exercise real field mapping; the endpoint regression covers JPEG sanitization.
    _, writes = _persist(monkeypatch)
    stored, = writes
    contract = _contract()
    stored_image_digest = hashlib.sha256(IMAGE_BYTES).hexdigest()

    assert stored_image_digest != contract.payload_sha256
    assert stored.client_payload_sha256 == contract.payload_sha256
    assert stored.client_payload_bytes == contract.payload_bytes
    assert stored.payload["image_sha256"] == stored_image_digest


def test_fixed_id_persists_one_random_marker_and_replays_same_receipt(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    first, writes = _persist(monkeypatch)
    second = reports._persist_v2_report(
        SimpleNamespace(rollback=lambda: None),  # type: ignore[arg-type]
        parsed=_metadata(),
        content=IMAGE_BYTES,
        content_type="image/jpeg",
        actor_id="field@example.com",
        account_generation=7,
        settings=SimpleNamespace(
            field_test_security_enabled=False,
            upload_dir=Path("/tmp/walksafe-report-transport-tests"),
            maintenance_lock_path=None,
            maintenance_lock_group_gid=None,
        ),  # type: ignore[arg-type]
        key_manager=object(),  # type: ignore[arg-type]
        transport_contract=_contract(),
    )

    assert len(writes) == 1
    assert writes[0].id == REPORT_ID
    assert writes[0].persistence_marker is not None
    assert first.transport_receipt == second.transport_receipt
    assert first.transport_receipt.report_id == REPORT_ID


class _ReplaySession:
    def __init__(self, existing: Report | None) -> None:
        self.existing = existing
        self.executed: list[object] = []
        self.rollback_count = 0

    def execute(self, statement: object, *_args: object, **_kwargs: object) -> object:
        self.executed.append(statement)
        return object()

    def get(self, _model: object, _key: object) -> Report | None:
        return self.existing

    def rollback(self) -> None:
        self.rollback_count += 1


def _existing_transport_report(**overrides: object) -> Report:
    values = {
        "id": REPORT_ID,
        "client_payload_sha256": _contract().payload_sha256,
        "client_payload_bytes": _contract().payload_bytes,
        "persistence_marker": MARKER_ID,
        "privacy_subject_hmac": SUBJECT,
        "account_generation": 7,
    }
    values.update(overrides)
    return Report(**values)


def test_id_lock_replay_requires_same_payload_and_privacy_binding() -> None:
    existing = _existing_transport_report()
    db = _ReplaySession(existing)

    assert reports._report_transport_replay(
        db,  # type: ignore[arg-type]
        contract=_contract(),
        privacy_subject=SUBJECT,
        account_generation=7,
    ) is existing
    assert len(db.executed) == 1

    for conflict in (
        _existing_transport_report(client_payload_sha256="f" * 64),
        _existing_transport_report(privacy_subject_hmac="b" * 64),
        _existing_transport_report(account_generation=8),
    ):
        conflict_db = _ReplaySession(conflict)
        with pytest.raises(HTTPException) as captured:
            reports._report_transport_replay(
                conflict_db,  # type: ignore[arg-type]
                contract=_contract(),
                privacy_subject=SUBJECT,
                account_generation=7,
            )
        assert captured.value.status_code == 409
        assert captured.value.detail["code"] == "report_transport_id_conflict"
        assert conflict_db.rollback_count == 1


def test_secured_auto_report_stale_replay_succeeds_but_new_id_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from backend.app.config import get_settings

    parsed = _metadata().model_copy(
        update={"captured_at": datetime.now(UTC) - timedelta(minutes=6)}
    )
    metadata_bytes = parsed.model_dump_json(exclude_none=True).encode("utf-8")

    def contract_for(report_id: uuid.UUID) -> reports.ReportTransportContract:
        return reports.ReportTransportContract(
            report_id=report_id,
            payload_sha256=reports.report_transport_payload_sha256(
                report_id, metadata_bytes, IMAGE_BYTES,
            ),
            payload_bytes=len(metadata_bytes) + len(IMAGE_BYTES),
        )

    saved_contract = contract_for(REPORT_ID)
    existing = _existing_transport_report(
        client_payload_sha256=saved_contract.payload_sha256,
        client_payload_bytes=saved_contract.payload_bytes,
        captured_at=parsed.captured_at,
    )
    db = _ReplaySession(existing)
    settings = get_settings()
    monkeypatch.setattr(settings, "field_test_security_enabled", True)
    monkeypatch.setattr(reports, "_privacy_report_binding", lambda **_kwargs: (SUBJECT, 7))
    ingest_locks: list[bool] = []
    monkeypatch.setattr(
        reports,
        "lock_report_ingest_transaction",
        lambda *_args, **kwargs: ingest_locks.append(kwargs["automatic_reporting"]),
    )
    monkeypatch.setattr(reports, "_field_creation_response", lambda report, *, creation_outcome: report)

    def persist(contract: reports.ReportTransportContract) -> object:
        reports._validate_report_transport_payload(
            contract, metadata_bytes=metadata_bytes, image_bytes=IMAGE_BYTES,
        )
        return reports._persist_v2_report(
            db,  # type: ignore[arg-type]
            parsed=parsed,
            content=IMAGE_BYTES,
            content_type="image/jpeg",
            actor_id="field@example.com",
            account_generation=7,
            settings=settings,
            key_manager=object(),  # type: ignore[arg-type]
            transport_contract=contract,
        )

    assert persist(saved_contract) is existing
    assert db.rollback_count == 1

    db.existing = None
    with pytest.raises(HTTPException) as captured:
        persist(contract_for(uuid.uuid4()))
    assert captured.value.status_code == 422
    assert "captur" in str(captured.value.detail).lower()
    assert ingest_locks == [True, True]


def test_business_duplicate_is_evidence_not_transport_replay(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    duplicate_id = uuid.UUID("33333333-3333-4333-8333-333333333333")
    response, writes = _persist(monkeypatch, duplicate_ids=[duplicate_id])

    assert len(writes) == 1
    assert writes[0].id == REPORT_ID
    assert writes[0].payload["duplicate_report_ids"] == [str(duplicate_id)]
    assert response.duplicate_count == 1
    assert response.transport_receipt.report_id == REPORT_ID


def test_legacy_response_remains_valid_without_transport_headers(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _response, writes = _persist(monkeypatch)
    legacy = writes[0]
    legacy.client_payload_sha256 = None
    legacy.client_payload_bytes = None
    legacy.persistence_marker = None

    response = reports._field_creation_response(legacy, creation_outcome="CREATED")

    assert response.id == str(REPORT_ID)
    assert response.transport_receipt is None


def test_status_is_field_scoped_and_missing_or_wrong_actor_is_hidden() -> None:
    path = f"/reports/v2/{REPORT_ID}/status"
    assert required_field_test_access(path, "GET") is FieldTestAccess.FIELD
    assert requires_account_generation(path, "GET") is True
    assert required_field_test_access(
        "/reports/v2/{report_id}/status",
        "GET",
    ) is FieldTestAccess.FIELD
    verified = VerifiedActorAssertion(
        actor_id="field@example.com",
        account_generation=7,
        access=FieldTestAccess.FIELD,
    )
    request = SimpleNamespace(
        state=SimpleNamespace(verified_actor_assertion=verified),
    )
    assert reports._report_transport_status_actor(
        request,
        actor_header="field@example.com",
        account_generation_header="7",
        security_enabled=True,
    ) == ("field@example.com", 7)

    for actor in (None, "other@example.com"):
        with pytest.raises(HTTPException) as captured:
            reports._report_transport_status_actor(
                request,
                actor_header=actor,
                account_generation_header="7",
                security_enabled=True,
            )
        assert captured.value.status_code == 404


def test_response_loss_status_projection_returns_only_the_same_receipt() -> None:
    row = SimpleNamespace(
        id=REPORT_ID,
        status="new",
        client_payload_sha256=_contract().payload_sha256,
        client_payload_bytes=_contract().payload_bytes,
        persistence_marker=MARKER_ID,
    )

    status = reports._report_transport_status_response(row)
    body = status.model_dump(mode="json")

    assert body == {
        "persistence_state": "PERSISTED",
        "user_status": "RECEIVED",
        "transport_receipt": {
            "marker": "DATABASE_AND_ENCRYPTED_IMAGE_STORE",
            "report_id": str(REPORT_ID),
            "persistence_marker": str(MARKER_ID),
            "payload_sha256": _contract().payload_sha256,
            "payload_bytes": _contract().payload_bytes,
        },
    }
    assert {
        "gps",
        "image_path",
        "confidence",
        "duplicate_count",
    }.isdisjoint(body)

    statement = reports._report_transport_status_statement(
        REPORT_ID,
        privacy_subject=SUBJECT,
        account_generation=7,
    )
    assert list(statement.selected_columns.keys()) == [
        "id",
        "status",
        "client_payload_sha256",
        "client_payload_bytes",
        "persistence_marker",
    ]
    sql = str(statement)
    assert "reports.privacy_subject_hmac =" in sql
    assert "reports.account_generation =" in sql


def test_database_contract_is_nullable_only_for_legacy_rows() -> None:
    columns = Report.__table__.columns
    assert columns.client_payload_sha256.nullable is True
    assert columns.client_payload_bytes.nullable is True
    assert columns.persistence_marker.nullable is True
    constraint_names = {constraint.name for constraint in Report.__table__.constraints}
    assert "ck_reports_transport_contract_all_or_none" in constraint_names
    assert "uq_reports_persistence_marker" in constraint_names


@pytest.mark.parametrize(
    "failure_point",
    [
        "success",
        "refresh",
        "journal-unlink",
        "journal-rmdir",
        "commit-failure",
        "commit-uncertain",
        "cleanup-runtimeerror",
        "response-validation",
    ],
)
def test_postcommit_outcomes_preserve_confirmed_receipt_and_failure_boundaries(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
    failure_point: str,
) -> None:
    import errno
    import io
    import json
    import logging

    from fastapi import FastAPI
    from fastapi.exception_handlers import http_exception_handler
    from PIL import Image
    from pydantic import ValidationError

    from asgi_client import ASGITestClient
    from backend.app.config import get_settings
    from backend.app.services import report_storage

    destination = tmp_path / f"{REPORT_ID}.wse"
    journal_directory = report_storage.pending_journal_directory(tmp_path)
    journal_path = journal_directory / f"{REPORT_ID}.json"
    fault_hits = []
    propagated = []
    handled_http_exceptions = []
    events = []
    cleanup_calls = []
    private_fault_text = "private-postcommit-fault-sentinel"
    fault = (
        OSError(errno.EIO, private_fault_text)
        if failure_point in {"journal-unlink", "journal-rmdir"}
        else RuntimeError(private_fault_text)
    )
    if failure_point == "response-validation":
        with pytest.raises(ValidationError) as invalid_response:
            reports.ReportResponse.model_validate({})
        fault = invalid_response.value
    caplog.set_level(logging.WARNING, logger=reports.__name__)

    class FakeSession:
        def __init__(self):
            self.added = []
            self.committed = {}
            self.commit_calls = 0
            self.rollback_calls = 0
            self.refresh_calls = 0
            self.response_ready = False
            self.staged_envelope = b""

        def execute(self, *_args, **_kwargs):
            return object()

        def get(self, model, _key):
            value = self.committed.get(model)
            if isinstance(value, Report):
                # A replay lookup supplies DB-generated fields independently.
                value.created_at = NOW
                value.updated_at = NOW
                self.response_ready = True
            return value

        def add(self, value):
            self.added.append(value)

        def commit(self):
            events.append("commit-enter")
            self.commit_calls += 1
            self.staged_envelope = destination.read_bytes()
            if failure_point == "commit-failure":
                fault_hits.append(failure_point)
                raise fault
            for value in self.added:
                if isinstance(value, Report):
                    # This is a readiness sentinel, not SQLAlchemy expiration.
                    value.created_at = None
                    value.updated_at = None
                self.committed[type(value)] = value
            if failure_point == "commit-uncertain":
                # The DB applied the write, but its acknowledgement was lost.
                fault_hits.append(failure_point)
                raise fault
            events.append("commit-return")

        def rollback(self):
            events.append("rollback")
            self.rollback_calls += 1

        def refresh(self, report):
            events.append("refresh-enter")
            self.refresh_calls += 1
            if failure_point == "refresh":
                fault_hits.append(failure_point)
                raise fault
            report.created_at = NOW
            report.updated_at = NOW
            self.response_ready = True
            events.append("refresh-return")

    db = FakeSession()
    key_manager = SimpleNamespace(
        synchronize=lambda _db: None,
        encryption_slot=lambda: SimpleNamespace(
            key_id="postcommit-probe-key",
            material=bytes(range(32)),
        ),
    )
    monkeypatch.setattr(reports, "_privacy_report_binding", lambda **_kwargs: (SUBJECT, 7))
    monkeypatch.setattr(reports, "lock_report_ingest_transaction", lambda *_a, **_kw: None)
    monkeypatch.setattr(reports, "_assert_report_precommit_active", lambda *_a, **_kw: None)
    monkeypatch.setattr(reports, "lock_duplicate_candidate_window", lambda *_a, **_kw: None)
    monkeypatch.setattr(reports, "find_duplicate_candidates_v2", lambda *_a, **_kw: [])

    real_remove = report_storage.remove_image_file
    real_rmdir = Path.rmdir
    real_complete = reports.complete_report_image_write
    real_response = reports._field_creation_response

    def remove_with_fault(path):
        if path == journal_path and failure_point == "journal-unlink":
            fault_hits.append(failure_point)
            raise fault
        return real_remove(path)

    def rmdir_with_fault(path):
        if path == journal_directory and failure_point == "journal-rmdir":
            fault_hits.append(failure_point)
            raise fault
        return real_rmdir(path)

    def complete_with_fault(pending):
        assert db.response_ready
        assert events[-1] == "refresh-return"
        cleanup_calls.append(pending)
        events.append("cleanup-enter")
        if failure_point == "cleanup-runtimeerror":
            fault_hits.append(failure_point)
            raise fault
        real_complete(pending)
        events.append("cleanup-return")

    def response_with_ready_fields(report, *args, **kwargs):
        assert db.response_ready
        assert report.created_at == NOW
        assert report.updated_at == NOW
        events.append("serialize")
        if failure_point == "response-validation" and not fault_hits:
            fault_hits.append(failure_point)
            raise fault
        return real_response(report, *args, **kwargs)

    monkeypatch.setattr(report_storage, "remove_image_file", remove_with_fault)
    monkeypatch.setattr(Path, "rmdir", rmdir_with_fault)
    monkeypatch.setattr(reports, "complete_report_image_write", complete_with_fault)
    monkeypatch.setattr(reports, "_field_creation_response", response_with_ready_fields)

    settings = get_settings()
    for name, value in {
        "field_test_security_enabled": False,
        "upload_dir": tmp_path,
        "maintenance_lock_path": None,
        "maintenance_lock_group_gid": None,
    }.items():
        monkeypatch.setattr(settings, name, value)
    app = FastAPI()
    app.include_router(reports.create_router(settings, key_manager))
    app.dependency_overrides[reports.get_db] = lambda: db

    async def capture_http_exception(request, exc):
        handled_http_exceptions.append(exc)
        return await http_exception_handler(request, exc)

    app.add_exception_handler(HTTPException, capture_http_exception)

    async def observe_app(scope, receive, send):
        try:
            await app(scope, receive, send)
        except Exception as exc:
            # Preserve the real ASGI error response already sent by FastAPI.
            propagated.append(exc)

    client = ASGITestClient(observe_app)
    image = io.BytesIO()
    Image.new("RGB", (4, 4), color=(80, 100, 120)).save(image, format="JPEG")
    image_bytes = image.getvalue()
    metadata_bytes = json.dumps(
        _metadata().model_dump(mode="json", exclude_none=True),
        separators=(",", ":"),
    ).encode()
    headers = {
        "x-walksafe-actor-id": "field@example.com",
        "x-walksafe-account-generation": "7",
        "x-walksafe-report-id": str(REPORT_ID),
        "x-walksafe-report-payload-sha256": reports.report_transport_payload_sha256(
            REPORT_ID, metadata_bytes, image_bytes,
        ),
        "x-walksafe-report-payload-bytes": str(len(metadata_bytes) + len(image_bytes)),
    }

    def submit():
        return client.post(
            "/reports/v2",
            data={"metadata": metadata_bytes.decode()},
            files={"image": ("probe.jpg", image_bytes, "image/jpeg")},
            headers=headers,
        )

    first = submit()
    first_events = list(events)
    first_rollback_calls = db.rollback_calls
    first_response_ready = db.response_ready
    image_preserved = (
        destination.is_file()
        and destination.read_bytes() == db.staged_envelope
        and db.staged_envelope.startswith(b"WSRI")
    )
    journal_remaining = journal_path.exists()
    committed = db.committed.get(Report)
    second = submit() if committed is not None else None
    projection = (
        reports._report_transport_status_response(committed).model_dump(mode="json")
        if committed is not None else None
    )
    print(json.dumps({
        "probe": "postcommit_minimum_contract",
        "failure_point": failure_point,
        "fault_hits": fault_hits,
        "first_http_status": first.status_code,
        "first_events": first_events,
        "first_rollback_calls": first_rollback_calls,
        "propagated_exception_types": [type(exc).__name__ for exc in propagated],
        "commit_calls": db.commit_calls,
        "refresh_calls": db.refresh_calls,
        "cleanup_calls": len(cleanup_calls),
        "committed_report_count": int(committed is not None),
        "encrypted_image_preserved": image_preserved,
        "journal_remaining_after_first_response": journal_remaining,
        "retry_http_status": second.status_code if second is not None else None,
        "status_projection": projection["persistence_state"] if projection else None,
    }, sort_keys=True))

    first_succeeds = failure_point in {"success", "journal-unlink", "journal-rmdir"}
    commit_raises = failure_point in {"commit-failure", "commit-uncertain"}
    refresh_succeeds = not commit_raises and failure_point != "refresh"
    assert fault_hits == ([] if failure_point == "success" else [failure_point])
    assert db.commit_calls == 1
    assert first_rollback_calls == int(commit_raises)
    assert db.refresh_calls == int(not commit_raises)
    assert first_response_ready is refresh_succeeds
    assert len(cleanup_calls) == int(refresh_succeeds)
    assert (committed is not None) is (failure_point != "commit-failure")
    assert image_preserved, "staged ciphertext must not be compensated away"
    assert journal_remaining is (failure_point not in {"success", "journal-rmdir", "response-validation"})
    assert journal_path.exists() is journal_remaining, "a replay must not recreate a journal"
    assert destination.read_bytes() == db.staged_envelope
    expected_status = 201 if first_succeeds else 500 if commit_raises else 503
    assert first.status_code == expected_status, first.text
    assert private_fault_text not in first.text
    if first_succeeds:
        assert not propagated
        assert not handled_http_exceptions
        assert first_events[-1] == "serialize"
        assert first.json()["transport_receipt"] == projection["transport_receipt"]
        assert datetime.fromisoformat(first.json()["created_at"]) == NOW
        assert datetime.fromisoformat(first.json()["updated_at"]) == NOW
    elif not commit_raises:
        expected_code, expected_message = {
            "refresh": (
                "report_postcommit_refresh_failed",
                "The report was stored, but its confirmation response is unavailable.",
            ),
            "cleanup-runtimeerror": (
                "report_postcommit_journal_cleanup_failed",
                "The report was stored, but its storage cleanup could not be completed.",
            ),
            "response-validation": (
                "report_postcommit_response_failed",
                "The report was stored, but its confirmation response is unavailable.",
            ),
        }[failure_point]
        assert first.json() == {
            "detail": {
                "code": expected_code,
                "message": expected_message,
                "report_id": str(REPORT_ID),
            },
        }
        assert not propagated
        assert len(handled_http_exceptions) == 1
        assert handled_http_exceptions[0].__cause__ is fault
        assert ("serialize" in first_events) is (failure_point == "response-validation")
    else:
        assert first.text == "Internal Server Error"
        assert len(propagated) == 1 and propagated[0] is fault
        assert not handled_http_exceptions
        assert "serialize" not in first_events
    if second is not None:
        assert second.status_code == 201, second.text
        assert second.json()["transport_receipt"] == projection["transport_receipt"]
        assert reports._report_transport_replay(
            db,
            contract=reports.ReportTransportContract(
                REPORT_ID,
                headers["x-walksafe-report-payload-sha256"],
                len(metadata_bytes) + len(image_bytes),
            ),
            privacy_subject=SUBJECT,
            account_generation=7,
        ) is committed

    expected_warnings = {
        "refresh": ["report_postcommit_refresh_failed"],
        "journal-unlink": ["report_postcommit_journal_cleanup_deferred"],
        "journal-rmdir": ["report_postcommit_journal_cleanup_deferred"],
        "cleanup-runtimeerror": ["report_postcommit_journal_cleanup_failed"],
        "response-validation": ["report_postcommit_response_failed"],
    }.get(failure_point, [])
    records = [record for record in caplog.records if record.name == reports.__name__]
    assert [record.getMessage() for record in records] == expected_warnings
    assert all(record.levelno == logging.WARNING for record in records)
    assert all(record.exc_info is None and not record.args for record in records)
    assert private_fault_text not in caplog.text
