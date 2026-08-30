from __future__ import annotations

from datetime import UTC, datetime
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

    response = reports._field_creation_response(legacy)

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
