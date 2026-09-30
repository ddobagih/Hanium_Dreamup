"""Creation outcomes follow actual persistence branches, outside receipt identity."""

from datetime import UTC, datetime
from types import SimpleNamespace
import uuid

from fastapi import HTTPException
import pytest

from backend.app.api import reports
from backend.app.schemas import ReportMetadata
from backend.tests.test_report_transport_contract import _metadata


@pytest.fixture
def persistence(monkeypatch, tmp_path):
    state = SimpleNamespace(
        rows=[], replay=None, cooldown=None, rollbacks=0,
        contract=reports.ReportTransportContract(uuid.uuid4(), "b" * 64, 40),
    )
    monkeypatch.setattr(reports, "_privacy_report_binding", lambda **kwargs: ("a" * 64, 1))
    for name in ("lock_report_ingest_transaction", "_enforce_secured_auto_report_gate",
                 "lock_duplicate_candidate_window", "lock_auto_report_cooldown"):
        monkeypatch.setattr(reports, name, lambda *args, **kwargs: None)
    for name in ("_idempotent_report_replay", "_report_transport_replay"):
        monkeypatch.setattr(reports, name, lambda *args, **kwargs: state.replay)
    for name in ("find_duplicate_candidates", "find_duplicate_candidates_v2"):
        monkeypatch.setattr(reports, name, lambda *args, **kwargs: [])
    monkeypatch.setattr(reports, "find_active_auto_report_cooldown", lambda *args, **kwargs: state.cooldown)

    def commit(db, report, **kwargs):
        report.status = "new"
        report.created_at = report.updated_at = datetime.now(UTC)
        state.rows.append(report)

    monkeypatch.setattr(reports, "_commit_new_report_with_image", commit)
    settings = SimpleNamespace(
        field_test_security_enabled=True, upload_dir=tmp_path,
        maintenance_lock_path=None, maintenance_lock_group_gid=None,
    )

    def rollback():
        state.rollbacks += 1

    def persist(variant):
        metadata = _metadata()
        kwargs = dict(
            db=SimpleNamespace(rollback=rollback), parsed=metadata, content=b"owned image",
            content_type="image/jpeg", actor_id="outcome@example.com", account_generation=1,
            settings=settings, key_manager=object(),
        )
        if variant == "legacy":
            kwargs["parsed"] = ReportMetadata(
                class_id=0, class_name="damaged_tactile_block", confidence=0.9,
                bbox=metadata.bbox, captured_at=metadata.captured_at,
                source="fake", gps=metadata.gps,
            )
            return reports._persist_legacy_report(**kwargs)
        if variant == "transport":
            kwargs["transport_contract"] = state.contract
        return reports._persist_v2_report(**kwargs)

    state.persist = persist
    return state


@pytest.mark.parametrize("variant", ["legacy", "v2", "transport"])
def test_created_and_exact_replay_outcomes_preserve_the_same_receipt(persistence, variant):
    created = persistence.persist(variant)
    persistence.replay = persistence.rows[0]
    replayed = persistence.persist(variant)
    assert created.model_dump()["creation_outcome"] == "CREATED"
    assert replayed.model_dump()["creation_outcome"] == "REPLAYED"
    assert created.id == replayed.id
    assert created.transport_receipt == replayed.transport_receipt
    assert created.model_dump(exclude={"creation_outcome"}) == replayed.model_dump(exclude={"creation_outcome"})
    assert len(persistence.rows) == 1
    assert persistence.rollbacks == 1


def test_legacy_cooldown_is_explicit_and_does_not_claim_a_new_receipt(persistence):
    created = persistence.persist("v2")
    persistence.cooldown = persistence.rows[0]
    suppressed = persistence.persist("v2")
    assert suppressed.model_dump()["creation_outcome"] == "SUPPRESSED_BY_COOLDOWN"
    assert suppressed.id == created.id
    assert suppressed.transport_receipt is None
    assert len(persistence.rows) == 1
    assert persistence.rollbacks == 1


def test_transport_cooldown_keeps_http_error_code_and_retry_header(persistence):
    persistence.persist("transport")
    persistence.cooldown = persistence.rows[0]
    persistence.contract = reports.ReportTransportContract(uuid.uuid4(), "c" * 64, 41)
    with pytest.raises(HTTPException) as error:
        persistence.persist("transport")
    assert error.value.status_code == 429
    assert error.value.headers == {"Retry-After": "600"}
    assert error.value.detail == {
        "code": "automatic_report_cooldown_active",
        "creation_outcome": "SUPPRESSED_BY_COOLDOWN",
    }
    assert len(persistence.rows) == 1


def test_creation_outcome_is_required_only_on_the_post_response_schema():
    from backend.app import schemas

    creation = getattr(schemas, "ReportCreationResponse", None)
    assert creation is not None, "POST needs an explicit creation outcome schema"
    assert creation.model_fields["creation_outcome"].is_required()
    assert "creation_outcome" not in schemas.ReportResponse.model_fields
    assert set(schemas.ReportTransportStatusV1.model_fields) == {
        "persistence_state", "user_status", "transport_receipt",
    }
    assert set(schemas.ReportTransportReceiptV1.model_fields) == {
        "marker", "report_id", "persistence_marker", "payload_sha256", "payload_bytes",
    }
