from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import threading
import time
from types import SimpleNamespace
import uuid

from alembic.autogenerate import compare_metadata
from alembic.migration import MigrationContext
import pytest
from fastapi import HTTPException
from sqlalchemy import create_engine, delete, inspect, select, text, update
from sqlalchemy.engine import make_url
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.orm import Session, sessionmaker

from asgi_client import ASGITestClient
from backend.app.main import app
from backend.app.main import settings as app_settings
from backend.app.api.health import _privacy_hmac_binding_readiness
from backend.app.api.reports import _privacy_report_binding
from backend.app.models import (
    AccountDeletionEvent,
    AccountDeletionDeviceTarget,
    AccountDeletionItem,
    AccountDeletionReceipt,
    AccountDeletionRequest,
    AccountDeletionTombstone,
    Base,
    PrivacyConsentEvent,
    PrivacyHmacKeyBinding,
    Report,
    ReportImageObject,
)
from backend.app.schemas import (
    AccountDeletionRequestV2,
    AccountDeletionStatusV2,
    DeviceDeletionEvidenceV2,
)
from backend.app.field_test_security import (
    FieldTestAccess,
    create_actor_assertion,
    create_privacy_deletion_assertion,
)
import backend.app.services.privacy_lifecycle as privacy_lifecycle
from backend.app.services.privacy_lifecycle import (
    DELETION_ITEM_KEYS,
    EXTERNAL_ITEM_KEYS,
    ITEM_SLA,
    PrivacyLifecycleError,
    SERVER_OWNED_ITEM_KEYS,
    accept_account_deletion,
    assert_account_deletion_worker_database_role,
    assert_privacy_runtime_database_role,
    complete_server_deletion_inventory_from_manifest,
    deletion_evidence_sha256,
    get_account_deletion_status,
    lock_privacy_subject_exclusive,
    lock_report_ingest_transaction,
    privacy_subject_hmac,
    record_consent_event,
    record_device_deletion_evidence,
    training_ingest_allowed,
    transition_deletion_item,
    bind_or_verify_privacy_hmac_key,
)
from scripts.account_deletion_worker import (
    _prepare_roots as prepare_account_deletion_worker_roots,
    process_request as process_account_deletion_request,
    reconcile_journal as reconcile_account_deletion_journal,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)

PRIVACY_SECRET = "walksafe-pytest-privacy-hmac-secret-boundary-v2"


def _session_factory():
    engine = create_engine(
        os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(),
        pool_pre_ping=True,
    )
    return engine, sessionmaker(bind=engine, expire_on_commit=False)


@contextmanager
def _worker_session_factory(engine, suffix: str):
    role_name = f"walksafe_delete_worker_{suffix}"
    role_password = f"WorkerRole{suffix}"
    role_created = False
    worker_engine = None
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    f"CREATE ROLE {role_name} LOGIN INHERIT NOSUPERUSER "
                    f"NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS "
                    f"PASSWORD '{role_password}'"
                )
            )
            connection.execute(
                text(
                    "GRANT walksafe_account_deletion_worker "
                    f"TO {role_name} WITH ADMIN FALSE, INHERIT TRUE, SET FALSE"
                )
            )
        role_created = True
        worker_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"]).set(
            username=role_name,
            password=role_password,
        ).render_as_string(hide_password=False)
        worker_engine = create_engine(worker_url, pool_pre_ping=True)
        yield sessionmaker(worker_engine, expire_on_commit=False)
    finally:
        if worker_engine is not None:
            worker_engine.dispose()
        if role_created:
            with engine.begin() as connection:
                connection.execute(
                    text(f"REVOKE walksafe_account_deletion_worker FROM {role_name}")
                )
                connection.execute(text(f"DROP ROLE {role_name}"))


def _request(request_id: str) -> AccountDeletionRequestV2:
    return AccountDeletionRequestV2(
        schema_version="walksafe.account-deletion-request.v2",
        request_id=request_id,
        client_revision=1,
        confirmation="DELETE_MY_ACCOUNT",
    )


def _consent_payload(*, request_id: str, installation_id: str, revision: int = 1):
    return {
        "schema_version": "walksafe.privacy-consent-event.v2",
        "installation_id": installation_id,
        "request_id": request_id,
        "client_revision": revision,
        "policy_version": "FP-013-1.0.0",
        "item_versions": {
            "raw_source_collection": "FP-013-RAW-1.0.0",
            "automatic_reporting": "FP-013-AUTO-1.0.0",
            "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
            "training_reuse": "FP-013-TRAINING-1.0.0",
        },
        "raw_source_collection": True,
        "automatic_reporting": True,
        "mobile_network_transfer": False,
        "training_reuse": False,
    }


def _consent_receipt_sha256(
    *,
    privacy_subject: str,
    account_generation: int,
    installation_subject: str,
    request_id: str,
    client_revision: int,
    subject_revision: int,
) -> str:
    canonical = json.dumps(
        {
            "account_generation": account_generation,
            "automatic_reporting": True,
            "client_revision": client_revision,
            "installation_subject_hmac": installation_subject,
            "item_versions": {
                "raw_source_collection": "FP-013-RAW-1.0.0",
                "automatic_reporting": "FP-013-AUTO-1.0.0",
                "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
                "training_reuse": "FP-013-TRAINING-1.0.0",
            },
            "mobile_network_transfer": False,
            "policy_version": "FP-013-1.0.0",
            "privacy_subject_hmac": privacy_subject,
            "raw_source_collection": True,
            "request_id": request_id,
            "subject_revision": subject_revision,
            "training_reuse": False,
        },
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hashlib.sha256(b"walksafe/privacy-consent-event/v2\0" + canonical).hexdigest()


def _installation_id(actor_id: str) -> str:
    return f"install_{actor_id.rsplit('.', 1)[-1]}"


def _record_consent(
    SessionFactory,
    *,
    actor_id: str,
    installation_id: str | None = None,
    client_revision: int = 1,
    raw_source_collection: bool = True,
    automatic_reporting: bool = True,
    training_reuse: bool = False,
):
    with SessionFactory() as db:
        return record_consent_event(
            db,
            actor_id=actor_id,
            account_generation=1,
            installation_id=installation_id or _installation_id(actor_id),
            request_id=f"consent_{uuid.uuid4().hex}",
            client_revision=client_revision,
            policy_version="FP-013-1.0.0",
            item_versions={
                "raw_source_collection": "FP-013-RAW-1.0.0",
                "automatic_reporting": "FP-013-AUTO-1.0.0",
                "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
                "training_reuse": "FP-013-TRAINING-1.0.0",
            },
            raw_source_collection=raw_source_collection,
            automatic_reporting=automatic_reporting,
            mobile_network_transfer=False,
            training_reuse=training_reuse,
            secret=PRIVACY_SECRET,
        )


def _accept(SessionFactory, *, actor_id: str, request_id: str, pre_digest: str = "a" * 64):
    subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
    with SessionFactory() as db:
        registered = db.scalar(
            select(PrivacyConsentEvent.id).where(
                PrivacyConsentEvent.privacy_subject_hmac == subject,
                PrivacyConsentEvent.account_generation == 1,
            ).limit(1)
        )
    if registered is None:
        _record_consent(SessionFactory, actor_id=actor_id)
    with SessionFactory() as db:
        return accept_account_deletion(
            db,
            payload=_request(request_id),
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest=pre_digest,
            tombstone_id=None,
            secret=PRIVACY_SECRET,
        )


def _evidence(
    status,
    *,
    installation_id: str,
    evidence_id: str,
    result: str = "DELETED",
    expected_revision: int | None = None,
) -> DeviceDeletionEvidenceV2:
    evidence = DeviceDeletionEvidenceV2(
        schema_version="walksafe.device-deletion-evidence.v2",
        request_id=status.request_id,
        tombstone_id=status.tombstone_id,
        request_receipt_sha256=status.request_receipt_sha256,
        installation_id=installation_id,
        evidence_id=evidence_id,
        client_revision=status.client_revision,
        expected_status_revision=expected_revision or status.revision,
        item="device_untransmitted_data",
        result=result,
        completed_at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        evidence_sha256="0" * 64,
    )
    return evidence.model_copy(
        update={"evidence_sha256": deletion_evidence_sha256(evidence)}
    )


def test_fp046_schema_migration_constraints_and_append_only_evidence() -> None:
    engine, SessionFactory = _session_factory()
    privacy_tables = {
        "walksafe_fp046_runtime_acl_baseline",
        "privacy_hmac_key_bindings",
        "privacy_consent_events",
        "account_deletion_tombstones",
        "account_deletion_requests",
        "account_deletion_items",
        "account_deletion_device_targets",
        "account_deletion_events",
        "account_deletion_receipts",
    }

    def include_privacy(obj, name, type_, reflected, compare_to):
        del name, reflected, compare_to
        if type_ == "table":
            return obj.name in privacy_tables
        table = getattr(obj, "table", None)
        return table is not None and table.name in privacy_tables

    with engine.connect() as connection:
        assert connection.execute(text("SELECT version_num FROM alembic_version")).scalar_one() == "202608300001"
        assert compare_metadata(
            MigrationContext.configure(
                connection,
                opts={"include_object": include_privacy},
            ),
            Base.metadata,
        ) == []
        report_columns = {column["name"] for column in inspect(connection).get_columns("reports")}
        assert {"privacy_subject_hmac", "account_generation"}.issubset(report_columns)
        triggers = {
            row["tgname"]
            for row in connection.execute(
                text(
                    "SELECT tgname FROM pg_trigger "
                    "WHERE NOT tgisinternal AND tgrelid IN ("
                        "'privacy_consent_events'::regclass, "
                        "'privacy_hmac_key_bindings'::regclass, "
                    "'account_deletion_tombstones'::regclass, "
                        "'account_deletion_items'::regclass, "
                        "'account_deletion_device_targets'::regclass, "
                    "'account_deletion_events'::regclass, "
                    "'account_deletion_receipts'::regclass)"
                )
            ).mappings()
        }
    assert {
        "privacy_consent_events_append_only",
        "privacy_consent_events_validate_insert",
        "privacy_hmac_key_bindings_append_only",
        "account_deletion_tombstones_append_only",
        "account_deletion_items_no_delete",
        "account_deletion_items_server_terminal_worker_only",
        "account_deletion_items_terminal_immutable",
        "account_deletion_device_targets_no_delete",
        "account_deletion_device_targets_terminal_immutable",
        "account_deletion_events_append_only",
        "account_deletion_receipts_no_update",
        "account_deletion_receipts_expiry_delete",
        "account_deletion_receipts_no_truncate",
        "account_deletion_receipts_validate_provenance",
    }.issubset(triggers)

    suffix = uuid.uuid4().hex[:12]
    accepted = _accept(
        SessionFactory,
        actor_id=f"schema.{suffix}",
        request_id=f"delete_schema_{suffix}",
    )
    with pytest.raises(SQLAlchemyError):
        with SessionFactory.begin() as db:
            db.execute(
                update(AccountDeletionEvent)
                .where(AccountDeletionEvent.request_id == accepted.status.request_id)
                .values(operation_id="mutated")
            )
    with pytest.raises(SQLAlchemyError):
        with SessionFactory.begin() as db:
            item = db.get(
                AccountDeletionItem,
                (accepted.status.request_id, "server_originals"),
            )
            assert item is not None
            db.delete(item)
            db.flush()
    with pytest.raises(SQLAlchemyError):
        with SessionFactory.begin() as db:
            db.execute(
                update(AccountDeletionItem)
                .where(
                    AccountDeletionItem.request_id == accepted.status.request_id,
                    AccountDeletionItem.item_key == "server_originals",
                )
                .values(
                    state="NOT_APPLICABLE",
                    evidence_sha256="b" * 64,
                    disposition_basis=None,
                    terminal_at=datetime.now(timezone.utc),
                )
            )
    engine.dispose()


@pytest.mark.parametrize(
    ("attack", "client_revision", "subject_revision", "future_seconds", "forge_digest", "message"),
    (
        ("gap", 99, 99, 0, False, "revision or chronology"),
        ("future", 2, 2, 300, False, "revision or chronology"),
        ("digest", 2, 2, 0, True, "receipt provenance"),
    ),
)
def test_fp046_database_rejects_raw_consent_revision_time_and_digest_forgery(
    attack: str,
    client_revision: int,
    subject_revision: int,
    future_seconds: int,
    forge_digest: bool,
    message: str,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"consent-ledger.{suffix}"
    installation_id = f"install_consent_ledger_{suffix}"
    first = _record_consent(
        SessionFactory,
        actor_id=actor_id,
        installation_id=installation_id,
    ).event
    request_id = f"consent_raw_{attack}_{suffix}"
    receipt_sha256 = _consent_receipt_sha256(
        privacy_subject=first.privacy_subject_hmac,
        account_generation=1,
        installation_subject=first.installation_subject_hmac,
        request_id=request_id,
        client_revision=client_revision,
        subject_revision=subject_revision,
    )
    if forge_digest:
        receipt_sha256 = "f" * 64
    with pytest.raises(IntegrityError) as rejected:
        with SessionFactory.begin() as db:
            db.execute(
                text(
                    "INSERT INTO privacy_consent_events ("
                    "id, request_id, privacy_subject_hmac, account_generation, "
                    "installation_subject_hmac, client_revision, subject_revision, "
                    "policy_version, item_versions, raw_source_collection, "
                    "automatic_reporting, mobile_network_transfer, training_reuse, "
                    "receipt_sha256, recorded_at) VALUES ("
                    ":id, :request_id, :privacy_subject, 1, :installation_subject, "
                    ":client_revision, :subject_revision, 'FP-013-1.0.0', "
                    "CAST(:item_versions AS jsonb), true, true, false, false, "
                    ":receipt_sha256, :recorded_at)"
                ),
                {
                    "id": uuid.uuid4(),
                    "request_id": request_id,
                    "privacy_subject": first.privacy_subject_hmac,
                    "installation_subject": first.installation_subject_hmac,
                    "client_revision": client_revision,
                    "subject_revision": subject_revision,
                    "item_versions": json.dumps(
                        {
                            "raw_source_collection": "FP-013-RAW-1.0.0",
                            "automatic_reporting": "FP-013-AUTO-1.0.0",
                            "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
                            "training_reuse": "FP-013-TRAINING-1.0.0",
                        }
                    ),
                    "receipt_sha256": receipt_sha256,
                    "recorded_at": datetime.now(timezone.utc)
                    + timedelta(seconds=future_seconds),
                },
            )
    assert getattr(rejected.value.orig, "sqlstate", None) == "23514"
    assert message in str(rejected.value.orig)
    engine.dispose()


@pytest.mark.parametrize(
    ("privacy_subject_hmac", "account_generation"),
    (
        (None, 1),
        ("a" * 64, None),
    ),
)
def test_fp046_report_binding_rejects_partial_nulls(
    privacy_subject_hmac: str | None,
    account_generation: int | None,
) -> None:
    engine, SessionFactory = _session_factory()
    report_id = uuid.uuid4()
    with pytest.raises(IntegrityError) as rejected:
        with SessionFactory.begin() as db:
            db.add(
                Report(
                    id=report_id,
                    status="new",
                    class_id=0,
                    class_name="damaged_tactile_block",
                    confidence=0.9,
                    bbox_x=0.1,
                    bbox_y=0.1,
                    bbox_width=0.5,
                    bbox_height=0.5,
                    captured_at=datetime.now(timezone.utc),
                    source="android",
                    image_path=f"/uploads/{report_id}.jpg",
                    image_content_type="image/jpeg",
                    payload={"test": "fp046-partial-binding"},
                    privacy_subject_hmac=privacy_subject_hmac,
                    account_generation=account_generation,
                )
            )
    assert getattr(rejected.value.orig, "sqlstate", None) == "23514"
    engine.dispose()


def test_fp046_accept_replay_capability_device_evidence_and_api_status() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"deletion.{suffix}"
    request_id = f"delete_request_{suffix}"
    pre_digest = "c" * 64
    accepted = _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=request_id,
        pre_digest=pre_digest,
    )
    assert accepted.created is True
    status = accepted.status
    assert status.overall_status == "PARTIAL"
    assert [item.key for item in status.items] == list(DELETION_ITEM_KEYS)
    for item in status.items:
        expected_state = "EXTERNAL_PENDING" if item.key in EXTERNAL_ITEM_KEYS else "PENDING"
        assert item.status == expected_state
        assert item.due_at - status.accepted_at == ITEM_SLA[item.key]

    with SessionFactory() as db:
        stored = db.get(AccountDeletionRequest, request_id)
        assert stored is not None
        assert stored.access_secret_digest != pre_digest
        assert len(stored.access_secret_digest) == 64
        replay = accept_account_deletion(
            db,
            payload=_request(request_id),
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest=pre_digest,
            tombstone_id=None,
            secret=PRIVACY_SECRET,
        )
    assert replay.created is False
    assert replay.status.model_dump(mode="json") == status.model_dump(mode="json")

    with pytest.raises(PrivacyLifecycleError) as wrong_capability:
        with SessionFactory() as db:
            accept_account_deletion(
                db,
                payload=_request(request_id),
                actor_id=actor_id,
                account_generation=1,
                access_pre_digest="d" * 64,
                tombstone_id=None,
                secret=PRIVACY_SECRET,
            )
    assert wrong_capability.value.status_code == 404

    evidence = DeviceDeletionEvidenceV2(
        schema_version="walksafe.device-deletion-evidence.v2",
        request_id=request_id,
        tombstone_id=str(status.tombstone_id),
        request_receipt_sha256=status.request_receipt_sha256,
        installation_id=f"install_{suffix}",
        evidence_id=f"evidence_{suffix}",
        client_revision=1,
        expected_status_revision=1,
        item="device_untransmitted_data",
        result="DELETED",
        completed_at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        evidence_sha256="0" * 64,
    )
    evidence = evidence.model_copy(
        update={"evidence_sha256": deletion_evidence_sha256(evidence)}
    )
    with SessionFactory() as db:
        after_evidence = record_device_deletion_evidence(
            db,
            payload=evidence,
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest=pre_digest,
            tombstone_id=str(status.tombstone_id),
            secret=PRIVACY_SECRET,
        )
    assert after_evidence.revision == 2
    assert after_evidence.items[0].status == "COMPLETED"
    assert after_evidence.overall_status == "PARTIAL"
    with SessionFactory() as db:
        exact_evidence_replay = record_device_deletion_evidence(
            db,
            payload=evidence,
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest=pre_digest,
            tombstone_id=str(status.tombstone_id),
            secret=PRIVACY_SECRET,
        )
    assert exact_evidence_replay.revision == 2

    client = ASGITestClient(app)
    api_suffix = uuid.uuid4().hex[:12]
    api_actor = f"api.{api_suffix}"
    api_request = f"delete_api_{api_suffix}"
    headers = {
        "x-walksafe-actor-id": api_actor,
        "x-walksafe-account-generation": "1",
        "x-walksafe-deletion-access-pre-digest": "e" * 64,
    }
    consent = client.post(
        "/privacy/consent-events",
        headers={
            "x-walksafe-actor-id": api_actor,
            "x-walksafe-account-generation": "1",
        },
        json=_consent_payload(
            request_id=f"consent_api_{api_suffix}",
            installation_id=_installation_id(api_actor),
        ),
    )
    assert consent.status_code == 201, consent.text
    assert consent.headers["cache-control"] == "no-store"
    second_installation = f"install_second_{api_suffix}"
    second_consent = client.post(
        "/privacy/consent-events",
        headers={
            "x-walksafe-actor-id": api_actor,
            "x-walksafe-account-generation": "1",
        },
        json=_consent_payload(
            request_id=f"consent_api_second_{api_suffix}",
            installation_id=second_installation,
        ),
    )
    assert second_consent.status_code == 201, second_consent.text
    created = client.post(
        "/privacy/account-deletions",
        headers=headers,
        json=_request(api_request).model_dump(mode="json"),
    )
    assert created.status_code == 202, created.text
    assert created.headers["cache-control"] == "no-store"
    body = created.json()
    replay_response = client.post(
        "/privacy/account-deletions",
        headers=headers,
        json=_request(api_request).model_dump(mode="json"),
    )
    assert replay_response.status_code == 200
    fetched = client.get(
        f"/privacy/account-deletions/{api_request}/status",
        headers={
            **headers,
            "x-walksafe-deletion-tombstone-id": body["tombstone_id"],
        },
    )
    assert fetched.status_code == 200, fetched.text
    assert fetched.json() == body

    deletion_headers = {
        **headers,
        "x-walksafe-deletion-tombstone-id": body["tombstone_id"],
    }
    first_device = _evidence(
        AccountDeletionStatusV2.model_validate(body),
        installation_id=_installation_id(api_actor),
        evidence_id=f"api-device-first-{api_suffix}",
    )
    first_device_response = client.post(
        f"/privacy/account-deletions/{api_request}/device-evidence",
        headers=deletion_headers,
        json=first_device.model_dump(mode="json"),
    )
    assert first_device_response.status_code == 200, first_device_response.text
    first_device_status = first_device_response.json()
    assert first_device_status["revision"] == 2
    assert first_device_status["items"][0]["status"] == "EXTERNAL_PENDING"

    stale_second = _evidence(
        AccountDeletionStatusV2.model_validate(first_device_status),
        installation_id=second_installation,
        evidence_id=f"api-device-stale-{api_suffix}",
        expected_revision=1,
    )
    stale_response = client.post(
        f"/privacy/account-deletions/{api_request}/device-evidence",
        headers=deletion_headers,
        json=stale_second.model_dump(mode="json"),
    )
    assert stale_response.status_code == 409
    assert stale_response.json()["detail"] == {
        "code": "account_deletion_revision_conflict",
        "message": "The deletion status revision changed.",
        "current_status_revision": 2,
        "current_overall_status": "PARTIAL",
    }
    rebased_second = _evidence(
        AccountDeletionStatusV2.model_validate(first_device_status),
        installation_id=second_installation,
        evidence_id=f"api-device-rebased-{api_suffix}",
        expected_revision=2,
    )
    completed_device_response = client.post(
        f"/privacy/account-deletions/{api_request}/device-evidence",
        headers=deletion_headers,
        json=rebased_second.model_dump(mode="json"),
    )
    assert completed_device_response.status_code == 200
    assert completed_device_response.json()["items"][0]["status"] == "COMPLETED"
    engine.dispose()


@pytest.mark.parametrize("item_key", DELETION_ITEM_KEYS)
@pytest.mark.parametrize("mutation", ("state", "sla"))
def test_fp046_database_rejects_noncanonical_acceptance_inventory(
    monkeypatch: pytest.MonkeyPatch,
    item_key: str,
    mutation: str,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"acceptance-map.{mutation}.{suffix}"
    _record_consent(SessionFactory, actor_id=actor_id)
    if mutation == "state":
        external_keys = set(privacy_lifecycle.EXTERNAL_ITEM_KEYS)
        if item_key in external_keys:
            external_keys.remove(item_key)
        else:
            external_keys.add(item_key)
        monkeypatch.setattr(
            privacy_lifecycle,
            "EXTERNAL_ITEM_KEYS",
            frozenset(external_keys),
        )
    else:
        monkeypatch.setitem(
            privacy_lifecycle.ITEM_SLA,
            item_key,
            privacy_lifecycle.ITEM_SLA[item_key] + timedelta(seconds=1),
        )
    with pytest.raises(IntegrityError) as rejected:
        _accept(
            SessionFactory,
            actor_id=actor_id,
            request_id=f"delete_acceptance_map_{mutation}_{suffix}",
        )
    assert getattr(rejected.value.orig, "sqlstate", None) == "23514"
    assert "account deletion acceptance inventory is invalid" in str(
        rejected.value.orig
    )
    engine.dispose()


def test_fp046_database_rejects_noncanonical_initial_request_status() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    accepted_at = datetime.now(timezone.utc).replace(microsecond=0)
    tombstone_id = uuid.uuid4()
    request_id = f"delete_initial_status_{suffix}"
    with pytest.raises(IntegrityError) as rejected:
        with SessionFactory.begin() as db:
            db.execute(
                text(
                    "INSERT INTO account_deletion_tombstones ("
                    "tombstone_id, privacy_subject_hmac, account_generation, "
                    "request_receipt_sha256, tombstoned_at) VALUES ("
                    ":tombstone_id, :privacy_subject, 1, :receipt_sha256, :accepted_at)"
                ),
                {
                    "tombstone_id": tombstone_id,
                    "privacy_subject": "c" * 64,
                    "receipt_sha256": "d" * 64,
                    "accepted_at": accepted_at,
                },
            )
            db.execute(
                text(
                    "INSERT INTO account_deletion_requests ("
                    "request_id, tombstone_id, privacy_subject_hmac, account_generation, "
                    "request_body_sha256, request_receipt_sha256, access_secret_digest, "
                    "client_revision, status_revision, overall_status, "
                    "completion_receipt_sha256, accepted_at, updated_at) VALUES ("
                    ":request_id, :tombstone_id, :privacy_subject, 1, :body_sha256, "
                    ":receipt_sha256, :access_digest, 1, 1, 'PROCESSING', NULL, "
                    ":accepted_at, :accepted_at)"
                ),
                {
                    "request_id": request_id,
                    "tombstone_id": tombstone_id,
                    "privacy_subject": "c" * 64,
                    "body_sha256": "e" * 64,
                    "receipt_sha256": "d" * 64,
                    "access_digest": "f" * 64,
                    "accepted_at": accepted_at,
                },
            )
    assert getattr(rejected.value.orig, "sqlstate", None) == "23514"
    assert "account deletion request chronology is invalid" in str(rejected.value.orig)
    engine.dispose()


def test_fp046_gateway_scoped_assertion_binds_body_path_and_tombstone(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    gateway_secret = "fp046-gateway-session-assertion-secret-v2"
    field_token = "fp046-field-token-for-scoped-assertion"
    monkeypatch.setattr(app_settings, "field_test_security_enabled", True)
    monkeypatch.setattr(app_settings, "field_test_token", field_token)
    monkeypatch.setattr(app_settings, "gateway_session_secret", gateway_secret)
    monkeypatch.setattr(app_settings, "walksafe_environment", "test")
    monkeypatch.setattr(app_settings, "allow_insecure_local_dev", False)
    monkeypatch.setattr(app_settings, "actor_rate_limit_store", "postgresql")

    suffix = uuid.uuid4().hex[:12]
    actor_id = f"scoped.{suffix}"
    request_id = f"delete_scoped_{suffix}"
    pre_digest = "9" * 64
    path = "/privacy/account-deletions"
    raw_body = json.dumps(
        _request(request_id).model_dump(mode="json"),
        separators=(",", ":"),
    ).encode("utf-8")
    assertion = create_privacy_deletion_assertion(
        actor_id=actor_id,
        account_generation=1,
        request_id=request_id,
        tombstone_id=None,
        access_pre_digest=pre_digest,
        method="POST",
        path=path,
        body_sha256=hashlib.sha256(raw_body).hexdigest(),
        secret=gateway_secret,
    )
    headers = {
        "content-type": "application/json",
        "x-walksafe-field-test-token": field_token,
        "x-walksafe-actor-id": actor_id,
        "x-walksafe-account-generation": "1",
        "x-walksafe-deletion-access-pre-digest": pre_digest,
        "x-walksafe-actor-assertion": assertion,
    }
    client = ASGITestClient(app)
    consent_body = json.dumps(
        _consent_payload(
            request_id=f"consent_scoped_{suffix}",
            installation_id=_installation_id(actor_id),
        ),
        separators=(",", ":"),
    ).encode("utf-8")
    # This first secured privacy admission previously failed with a PostgreSQL
    # CHECK violation and surfaced as actor_rate_limit_store_unavailable (503).
    consent_response = client.post(
        "/privacy/consent-events",
        headers={
            "content-type": "application/json",
            "x-walksafe-field-test-token": field_token,
            "x-walksafe-actor-id": actor_id,
            "x-walksafe-account-generation": "1",
            "x-walksafe-actor-assertion": create_actor_assertion(
                actor_id,
                FieldTestAccess.FIELD,
                gateway_secret,
                account_generation=1,
            ),
        },
        content=consent_body,
    )
    assert consent_response.status_code == 201, consent_response.text
    created = client.post(path, headers=headers, content=raw_body)
    assert created.status_code == 202, created.text
    assert created.headers["cache-control"] == "no-store"

    changed_body = raw_body.replace(b'"client_revision":1', b'"client_revision":2')
    body_mismatch = client.post(path, headers=headers, content=changed_body)
    assert body_mismatch.status_code == 401
    assert body_mismatch.json()["detail"]["code"] == "actor_assertion_invalid"

    generic_assertion = create_actor_assertion(
        actor_id,
        FieldTestAccess.FIELD,
        gateway_secret,
        account_generation=1,
    )
    generic = client.post(
        path,
        headers={**headers, "x-walksafe-actor-assertion": generic_assertion},
        content=raw_body,
    )
    assert generic.status_code == 401

    status_path = f"{path}/{request_id}/status"
    tombstone_id = created.json()["tombstone_id"]
    status_assertion = create_privacy_deletion_assertion(
        actor_id=actor_id,
        account_generation=1,
        request_id=request_id,
        tombstone_id=tombstone_id,
        access_pre_digest=pre_digest,
        method="GET",
        path=status_path,
        body_sha256=hashlib.sha256(b"").hexdigest(),
        secret=gateway_secret,
    )
    fetched = client.get(
        status_path,
        headers={
            **headers,
            "x-walksafe-actor-assertion": status_assertion,
            "x-walksafe-deletion-tombstone-id": tombstone_id,
        },
    )
    assert fetched.status_code == 200, fetched.text
    assert fetched.json() == created.json()


def test_fp046_optional_training_refusal_does_not_block_service_report_gate() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"consent.{suffix}"
    subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
    with SessionFactory() as db:
        recorded = record_consent_event(
            db,
            actor_id=actor_id,
            account_generation=1,
            installation_id=_installation_id(actor_id),
            request_id=f"consent_{suffix}",
            client_revision=1,
            policy_version="FP-013-1.0.0",
            item_versions={
                "raw_source_collection": "FP-013-RAW-1.0.0",
                "automatic_reporting": "FP-013-AUTO-1.0.0",
                "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
                "training_reuse": "FP-013-TRAINING-1.0.0",
            },
            raw_source_collection=True,
            automatic_reporting=True,
            mobile_network_transfer=False,
            training_reuse=False,
            secret=PRIVACY_SECRET,
        )
    assert recorded.event.training_reuse is False
    with SessionFactory() as db:
        assert training_ingest_allowed(
            db,
            privacy_subject=subject,
            account_generation=1,
        ) is False
        db.rollback()
    with SessionFactory() as db:
        # Reporting checks only the tombstone fence, never optional training consent.
        lock_report_ingest_transaction(db, subject, 1)
        db.rollback()

    accepted = _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=f"delete_consent_{suffix}",
    )
    assert accepted.status.overall_status == "PARTIAL"
    with pytest.raises(PrivacyLifecycleError) as tombstoned:
        with SessionFactory() as db:
            lock_report_ingest_transaction(db, subject, 1)
    assert tombstoned.value.code == "account_generation_tombstoned"
    engine.dispose()


def test_fp046_all_items_terminal_create_only_source_less_three_year_receipt() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"complete.{suffix}"
    accepted = _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=f"delete_complete_{suffix}",
    )
    current = accepted.status
    device_evidence = DeviceDeletionEvidenceV2(
        schema_version="walksafe.device-deletion-evidence.v2",
        request_id=current.request_id,
        tombstone_id=current.tombstone_id,
        request_receipt_sha256=current.request_receipt_sha256,
        installation_id=_installation_id(actor_id),
        evidence_id=f"complete-device-{suffix}",
        client_revision=1,
        expected_status_revision=current.revision,
        item="device_untransmitted_data",
        result="DELETED",
        completed_at=datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        evidence_sha256="0" * 64,
    )
    device_evidence = device_evidence.model_copy(
        update={"evidence_sha256": deletion_evidence_sha256(device_evidence)}
    )
    with SessionFactory() as db:
        current = record_device_deletion_evidence(
            db,
            payload=device_evidence,
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest="a" * 64,
            tombstone_id=current.tombstone_id,
            secret=PRIVACY_SECRET,
        )
    with _worker_session_factory(engine, suffix) as WorkerSessionFactory:
        with WorkerSessionFactory() as db:
            current = complete_server_deletion_inventory_from_manifest(
                db,
                request_id=current.request_id,
                manifest_sha256="1" * 64,
                terminal_at=datetime.now(timezone.utc),
            )
    assert current.overall_status == "PARTIAL"
    assert current.completion_receipt_sha256 is None
    remaining_keys = [
        key
        for key in DELETION_ITEM_KEYS
        if key != "device_untransmitted_data" and key not in SERVER_OWNED_ITEM_KEYS
    ]
    for index, key in enumerate(remaining_keys, start=2):
        state = "NOT_APPLICABLE" if key == "training_labels" else "COMPLETED"
        with SessionFactory() as db:
            current = transition_deletion_item(
                db,
                request_id=current.request_id,
                item_key=key,
                operation_id=f"complete-{index}-{suffix}",
                expected_status_revision=current.revision,
                next_state=state,
                evidence_sha256=f"{index + 1:064x}",
                disposition_basis=("no_training_label_rows" if state == "NOT_APPLICABLE" else None),
                terminal_at=datetime.now(timezone.utc),
            )
    assert current.overall_status == "COMPLETED"
    assert current.completion_receipt_sha256 is not None
    with SessionFactory() as db:
        receipt = db.scalar(
            select(AccountDeletionReceipt).where(
                AccountDeletionReceipt.receipt_sha256 == current.completion_receipt_sha256
            )
        )
        assert receipt is not None
        assert receipt.result == "COMPLETED"
        assert receipt.expires_at.year == receipt.processed_at.year + 3
        receipt_columns = set(AccountDeletionReceipt.__table__.columns.keys())
    assert "request_id" not in receipt_columns
    assert "tombstone_id" not in receipt_columns
    assert "actor_id" not in receipt_columns
    engine.dispose()


def test_fp046_account_deletion_worker_removes_server_data_and_retains_ledger(
    tmp_path: Path,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"worker.{suffix}"
    request_id = f"delete_worker_{suffix}"
    subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
    report_id = uuid.uuid4()
    storage_name = f"{uuid.uuid4()}.wse"
    envelope = (b"walksafe-worker-envelope-" + suffix.encode("ascii")) * 3
    envelope_sha256 = hashlib.sha256(envelope).hexdigest()
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    object_path = upload_dir / storage_name
    object_path.write_bytes(envelope)
    object_path.chmod(0o600)
    tmp_path.chmod(0o700)

    _record_consent(SessionFactory, actor_id=actor_id)
    with SessionFactory.begin() as db:
        db.add(
            Report(
                id=report_id,
                status="new",
                class_id=0,
                class_name="damaged_tactile_block",
                confidence=0.9,
                bbox_x=0.1,
                bbox_y=0.1,
                bbox_width=0.5,
                bbox_height=0.5,
                captured_at=datetime.now(timezone.utc),
                source="android",
                image_path=f"/uploads/{storage_name}",
                image_content_type="image/jpeg",
                payload={"source": "account-deletion-worker-test"},
                privacy_subject_hmac=subject,
                account_generation=1,
            )
        )
        db.add(
            ReportImageObject(
                report_id=report_id,
                storage_name=storage_name,
                envelope_version=1,
                algorithm="AES-256-GCM",
                aad_version=1,
                key_id=f"worker-key-{suffix}",
                nonce=uuid.uuid4().bytes[:12],
                plaintext_sha256=hashlib.sha256(b"plaintext").hexdigest(),
                plaintext_size=9,
                envelope_sha256=envelope_sha256,
                envelope_size=len(envelope),
                content_type="image/jpeg",
            )
        )
    accepted = _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=request_id,
    )
    device_evidence = _evidence(
        accepted.status,
        installation_id=_installation_id(actor_id),
        evidence_id=f"worker-device-{suffix}",
    )
    with SessionFactory() as db:
        current = record_device_deletion_evidence(
            db,
            payload=device_evidence,
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest="a" * 64,
            tombstone_id=accepted.status.tombstone_id,
            secret=PRIVACY_SECRET,
        )
    external_keys = [
        key
        for key in EXTERNAL_ITEM_KEYS
        if key != "device_untransmitted_data"
    ]
    for index, item_key in enumerate(sorted(external_keys), start=1):
        next_state = "NOT_APPLICABLE" if item_key == "training_labels" else "COMPLETED"
        with SessionFactory() as db:
            current = transition_deletion_item(
                db,
                request_id=request_id,
                item_key=item_key,
                operation_id=f"worker-external-{index}-{suffix}",
                expected_status_revision=current.revision,
                next_state=next_state,
                evidence_sha256=f"{index:064x}",
                disposition_basis=(
                    "no_training_label_rows"
                    if next_state == "NOT_APPLICABLE"
                    else None
                ),
                terminal_at=datetime.now(timezone.utc),
            )
    assert current.overall_status == "PARTIAL"
    assert current.completion_receipt_sha256 is None

    role_name = f"walksafe_delete_test_{suffix}"
    role_password = f"WorkerTest{suffix}"
    role_created = False
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    f"CREATE ROLE {role_name} LOGIN INHERIT NOSUPERUSER "
                    f"NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS "
                    f"PASSWORD '{role_password}'"
                )
            )
            connection.execute(
                text(
                    "GRANT walksafe_account_deletion_worker "
                    f"TO {role_name} WITH ADMIN FALSE, INHERIT TRUE, SET FALSE"
                )
            )
        role_created = True
        worker_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"]).set(
            username=role_name,
            password=role_password,
        ).render_as_string(hide_password=False)
        journal_root = tmp_path / "worker"

        prepare_account_deletion_worker_roots(journal_root.resolve())
        worker_engine = create_engine(worker_url, pool_pre_ping=True)
        WorkerSessionFactory = sessionmaker(worker_engine, expire_on_commit=False)

        class SimulatedCrash(BaseException):
            pass

        def crash_after_database_commit(point: str) -> None:
            if point == "after_database_commit":
                raise SimulatedCrash

        try:
            with pytest.raises(SimulatedCrash):
                process_account_deletion_request(
                    WorkerSessionFactory,
                    request_id=request_id,
                    upload_dir=upload_dir.resolve(),
                    root=journal_root.resolve(),
                    fault=crash_after_database_commit,
                )
        finally:
            worker_engine.dispose()
        assert not object_path.exists()
        journal_path = next((journal_root / "journals").glob("*.json"))

        worker_engine = create_engine(worker_url, pool_pre_ping=True)
        WorkerSessionFactory = sessionmaker(worker_engine, expire_on_commit=False)
        try:
            manifest_digest = reconcile_account_deletion_journal(
                WorkerSessionFactory,
                journal_root.resolve(),
                journal_path,
            )
            with WorkerSessionFactory() as db:
                repeated = complete_server_deletion_inventory_from_manifest(
                    db,
                    request_id=request_id,
                    manifest_sha256=manifest_digest,
                    terminal_at=datetime.now(timezone.utc),
                )
        finally:
            worker_engine.dispose()

        assert repeated.overall_status == "COMPLETED"
        assert repeated.completion_receipt_sha256 is not None
        assert not object_path.exists()
        manifest_path = journal_root / "manifests" / f"{request_id}.json"
        assert manifest_path.is_file()
        assert hashlib.sha256(manifest_path.read_bytes()).hexdigest() == manifest_digest
        with SessionFactory() as db:
            assert db.get(Report, report_id) is None
            assert db.get(ReportImageObject, report_id) is None
            assert db.scalar(
                select(AccountDeletionTombstone).where(
                    AccountDeletionTombstone.privacy_subject_hmac == subject,
                    AccountDeletionTombstone.account_generation == 1,
                )
            ) is not None
            request = db.get(AccountDeletionRequest, request_id)
            assert request is not None
            assert request.overall_status == "COMPLETED"
            assert request.completion_receipt_sha256 is not None
            items = db.scalars(
                select(AccountDeletionItem).where(
                    AccountDeletionItem.request_id == request_id
                )
            ).all()
            assert len(items) == 9
            server_items = [item for item in items if item.item_key in SERVER_OWNED_ITEM_KEYS]
            assert {item.state for item in server_items} == {"COMPLETED", "NOT_APPLICABLE"}
            assert {item.evidence_sha256 for item in server_items} == {manifest_digest}
            assert db.scalar(
                select(AccountDeletionReceipt).where(
                    AccountDeletionReceipt.receipt_sha256
                    == request.completion_receipt_sha256
                )
            ) is not None
            assert db.scalar(
                select(PrivacyConsentEvent.id).where(
                    PrivacyConsentEvent.privacy_subject_hmac == subject,
                    PrivacyConsentEvent.account_generation == 1,
                )
            ) is not None
    finally:
        if role_created:
            with engine.begin() as connection:
                connection.execute(
                    text(f"REVOKE walksafe_account_deletion_worker FROM {role_name}")
                )
                connection.execute(text(f"DROP ROLE {role_name}"))
        engine.dispose()


def test_fp046_account_deletion_worker_role_rejects_privilege_escalation() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    role_name = f"walksafe_delete_acl_{suffix}"
    role_password = f"WorkerAcl{suffix}"
    accepted = _accept(
        SessionFactory,
        actor_id=f"worker-acl.{suffix}",
        request_id=f"delete_worker_acl_{suffix}",
    )
    role_created = False
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    f"CREATE ROLE {role_name} LOGIN INHERIT NOSUPERUSER "
                    f"NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS "
                    f"PASSWORD '{role_password}'"
                )
            )
            connection.execute(
                text(
                    "GRANT walksafe_account_deletion_worker "
                    f"TO {role_name} WITH ADMIN FALSE, INHERIT TRUE, SET FALSE"
                )
            )
        role_created = True
        worker_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"]).set(
            username=role_name,
            password=role_password,
        ).render_as_string(hide_password=False)

        worker_engine = create_engine(worker_url, pool_pre_ping=True)
        try:
            with Session(worker_engine) as db:
                assert_account_deletion_worker_database_role(db)
        finally:
            worker_engine.dispose()

        with engine.begin() as connection:
            connection.execute(
                text(
                    "GRANT walksafe_account_deletion_worker "
                    f"TO {role_name} WITH ADMIN TRUE, INHERIT TRUE, SET FALSE"
                )
            )
        admin_option_engine = create_engine(worker_url, pool_pre_ping=True)
        try:
            with Session(admin_option_engine) as db:
                with pytest.raises(PrivacyLifecycleError) as admin_option:
                    assert_account_deletion_worker_database_role(db)
            assert admin_option.value.code == "account_deletion_worker_role_unsafe"
        finally:
            admin_option_engine.dispose()
        with engine.begin() as connection:
            connection.execute(
                text(
                    "GRANT walksafe_account_deletion_worker "
                    f"TO {role_name} WITH ADMIN FALSE, INHERIT TRUE, SET FALSE"
                )
            )

        with engine.begin() as connection:
            connection.execute(text(f"GRANT walksafe_receipt_purger TO {role_name}"))
        purger_engine = create_engine(worker_url, pool_pre_ping=True)
        try:
            with Session(purger_engine) as db:
                with pytest.raises(PrivacyLifecycleError) as purger:
                    assert_account_deletion_worker_database_role(db)
            assert purger.value.code == "account_deletion_worker_role_unsafe"
        finally:
            purger_engine.dispose()
        with engine.begin() as connection:
            connection.execute(text(f"REVOKE walksafe_receipt_purger FROM {role_name}"))

        with engine.begin() as connection:
            connection.execute(
                text(
                    "GRANT EXECUTE ON FUNCTION "
                    "walksafe_purge_expired_account_deletion_receipts(integer) "
                    f"TO {role_name}"
                )
            )
        purge_function_engine = create_engine(worker_url, pool_pre_ping=True)
        try:
            with Session(purge_function_engine) as db:
                with pytest.raises(PrivacyLifecycleError) as purge_function:
                    assert_account_deletion_worker_database_role(db)
            assert purge_function.value.code == "account_deletion_worker_role_unsafe"
        finally:
            purge_function_engine.dispose()
        with engine.begin() as connection:
            connection.execute(
                text(
                    "REVOKE EXECUTE ON FUNCTION "
                    "walksafe_purge_expired_account_deletion_receipts(integer) "
                    f"FROM {role_name}"
                )
            )

        with engine.begin() as connection:
            connection.execute(
                text(
                    "GRANT EXECUTE ON FUNCTION "
                    "walksafe_bind_admin_credential_issuer_key(text,text) "
                    f"TO {role_name}"
                )
            )
        security_definer_engine = create_engine(worker_url, pool_pre_ping=True)
        try:
            with Session(security_definer_engine) as db:
                with pytest.raises(PrivacyLifecycleError) as security_definer:
                    assert_account_deletion_worker_database_role(db)
            assert security_definer.value.code == "account_deletion_worker_role_unsafe"
        finally:
            security_definer_engine.dispose()
        with engine.begin() as connection:
            connection.execute(
                text(
                    "REVOKE EXECUTE ON FUNCTION "
                    "walksafe_bind_admin_credential_issuer_key(text,text) "
                    f"FROM {role_name}"
                )
            )

        with engine.begin() as connection:
            connection.execute(
                text(
                    "GRANT EXECUTE ON FUNCTION "
                    "walksafe_guard_server_deletion_terminal_transition() "
                    f"TO {role_name} WITH GRANT OPTION"
                )
            )
        function_grant_option_engine = create_engine(worker_url, pool_pre_ping=True)
        try:
            with Session(function_grant_option_engine) as db:
                with pytest.raises(PrivacyLifecycleError) as function_grant_option:
                    assert_account_deletion_worker_database_role(db)
            assert function_grant_option.value.code == (
                "account_deletion_worker_role_unsafe"
            )
        finally:
            function_grant_option_engine.dispose()
        with engine.begin() as connection:
            connection.execute(
                text(
                    "REVOKE EXECUTE ON FUNCTION "
                    "walksafe_guard_server_deletion_terminal_transition() "
                    f"FROM {role_name}"
                )
            )

        with engine.begin() as connection:
            connection.execute(
                text(
                    "GRANT DELETE ON TABLE public.reports "
                    f"TO {role_name} WITH GRANT OPTION"
                )
            )
        grant_option_engine = create_engine(worker_url, pool_pre_ping=True)
        try:
            with Session(grant_option_engine) as db:
                with pytest.raises(PrivacyLifecycleError) as grant_option:
                    assert_account_deletion_worker_database_role(db)
            assert grant_option.value.code == "account_deletion_worker_role_unsafe"
        finally:
            grant_option_engine.dispose()
        with engine.begin() as connection:
            connection.execute(
                text(f"REVOKE DELETE ON TABLE public.reports FROM {role_name}")
            )

        with engine.begin() as connection:
            connection.execute(text(f"GRANT pg_read_all_data TO {role_name}"))
        overprivileged_engine = create_engine(worker_url, pool_pre_ping=True)
        try:
            with Session(overprivileged_engine) as db:
                with pytest.raises(PrivacyLifecycleError) as overprivileged:
                    assert_account_deletion_worker_database_role(db)
            assert overprivileged.value.code == "account_deletion_worker_role_unsafe"
        finally:
            overprivileged_engine.dispose()
        with engine.begin() as connection:
            connection.execute(text(f"REVOKE pg_read_all_data FROM {role_name}"))

        with engine.begin() as connection:
            connection.execute(text(f"GRANT walksafe_backend_runtime TO {role_name}"))
            connection.execute(
                text(
                    "GRANT walksafe_account_deletion_worker "
                    f"TO {role_name} WITH ADMIN FALSE, INHERIT TRUE, SET TRUE"
                )
            )
        dual_role_engine = create_engine(worker_url, pool_pre_ping=True)
        try:
            with Session(dual_role_engine) as db:
                with pytest.raises(PrivacyLifecycleError) as unsafe_worker:
                    assert_account_deletion_worker_database_role(db)
                with pytest.raises(PrivacyLifecycleError) as unsafe_runtime:
                    assert_privacy_runtime_database_role(db)
            assert unsafe_worker.value.code == "account_deletion_worker_role_unsafe"
            assert unsafe_runtime.value.code == "privacy_database_role_unsafe"
            with dual_role_engine.connect() as connection:
                transaction = connection.begin()
                try:
                    connection.execute(
                        text("SET ROLE walksafe_account_deletion_worker")
                    )
                    with pytest.raises(SQLAlchemyError) as runtime_bypass:
                        connection.execute(
                            text(
                                "UPDATE account_deletion_items SET state = 'COMPLETED', "
                                "item_revision = item_revision + 1, "
                                "evidence_sha256 = :evidence_sha256, "
                                "terminal_at = clock_timestamp(), "
                                "updated_at = clock_timestamp() "
                                "WHERE request_id = :request_id "
                                "AND item_key = 'server_originals'"
                            ),
                            {
                                "evidence_sha256": "b" * 64,
                                "request_id": accepted.status.request_id,
                            },
                        )
                    assert getattr(runtime_bypass.value.orig, "sqlstate", None) == "42501"
                finally:
                    transaction.rollback()
        finally:
            dual_role_engine.dispose()
    finally:
        if role_created:
            with engine.begin() as connection:
                connection.execute(
                    text(f"REVOKE walksafe_receipt_purger FROM {role_name}")
                )
                connection.execute(
                    text(
                        "REVOKE EXECUTE ON FUNCTION "
                        "walksafe_purge_expired_account_deletion_receipts(integer) "
                        f"FROM {role_name}"
                    )
                )
                connection.execute(
                    text(
                        "REVOKE EXECUTE ON FUNCTION "
                        "walksafe_bind_admin_credential_issuer_key(text,text) "
                        f"FROM {role_name}"
                    )
                )
                connection.execute(
                    text(
                        "REVOKE EXECUTE ON FUNCTION "
                        "walksafe_guard_server_deletion_terminal_transition() "
                        f"FROM {role_name}"
                    )
                )
                connection.execute(
                    text(f"REVOKE DELETE ON TABLE public.reports FROM {role_name}")
                )
                connection.execute(text(f"REVOKE pg_read_all_data FROM {role_name}"))
                connection.execute(
                    text(f"REVOKE walksafe_backend_runtime FROM {role_name}")
                )
                connection.execute(
                    text(f"REVOKE walksafe_account_deletion_worker FROM {role_name}")
                )
                connection.execute(text(f"DROP ROLE {role_name}"))
        engine.dispose()


def test_fp046_server_terminal_transition_rejects_nonworker_with_direct_acl() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    role_name = f"walksafe_delete_nonworker_{suffix}"
    role_password = f"NonworkerTest{suffix}"
    accepted = _accept(
        SessionFactory,
        actor_id=f"worker-boundary.{suffix}",
        request_id=f"delete_worker_boundary_{suffix}",
    )
    role_created = False
    try:
        with engine.begin() as connection:
            connection.execute(
                text(
                    f"CREATE ROLE {role_name} LOGIN INHERIT NOSUPERUSER "
                    f"NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS "
                    f"PASSWORD '{role_password}'"
                )
            )
            connection.execute(
                text(
                    "GRANT UPDATE ON TABLE public.account_deletion_items "
                    f"TO {role_name}"
                )
            )
            connection.execute(
                text(
                    "GRANT INSERT ON TABLE public.account_deletion_events "
                    f"TO {role_name}"
                )
            )
        role_created = True
        nonworker_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"]).set(
            username=role_name,
            password=role_password,
        ).render_as_string(hide_password=False)
        nonworker_engine = create_engine(nonworker_url, pool_pre_ping=True)
        try:
            with nonworker_engine.connect() as connection:
                transaction = connection.begin()
                try:
                    with pytest.raises(SQLAlchemyError) as blocked:
                        connection.execute(
                            text(
                                "UPDATE account_deletion_items SET state = 'COMPLETED', "
                                "item_revision = item_revision + 1, "
                                "evidence_sha256 = :evidence_sha256, "
                                "terminal_at = clock_timestamp(), "
                                "updated_at = clock_timestamp() "
                                "WHERE request_id = :request_id "
                                "AND item_key = 'server_originals'"
                            ),
                            {
                                "evidence_sha256": "c" * 64,
                                "request_id": accepted.status.request_id,
                            },
                        )
                    assert getattr(blocked.value.orig, "sqlstate", None) == "42501"
                finally:
                    transaction.rollback()
        finally:
            nonworker_engine.dispose()
    finally:
        if role_created:
            with engine.begin() as connection:
                connection.execute(
                    text(
                        "REVOKE UPDATE ON TABLE public.account_deletion_items "
                        f"FROM {role_name}"
                    )
                )
                connection.execute(
                    text(
                        "REVOKE INSERT ON TABLE public.account_deletion_events "
                        f"FROM {role_name}"
                    )
                )
                connection.execute(text(f"DROP ROLE {role_name}"))
        engine.dispose()


def test_fp046_report_ingest_and_tombstone_use_one_actor_generation_lock() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"race.{suffix}"
    request_id = f"delete_race_{suffix}"
    subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
    _record_consent(SessionFactory, actor_id=actor_id)
    ingest_locked = threading.Event()
    release_ingest = threading.Event()

    def ingest() -> uuid.UUID:
        report_id = uuid.uuid4()
        with SessionFactory() as db:
            lock_report_ingest_transaction(db, subject, 1)
            ingest_locked.set()
            assert release_ingest.wait(timeout=5)
            db.add(
                Report(
                    id=report_id,
                    status="new",
                    class_id=0,
                    class_name="damaged_tactile_block",
                    confidence=0.9,
                    bbox_x=0.1,
                    bbox_y=0.1,
                    bbox_width=0.5,
                    bbox_height=0.5,
                    captured_at=datetime.now(timezone.utc),
                    source="android",
                    image_path=f"/uploads/{report_id}.jpg",
                    image_content_type="image/jpeg",
                    payload={"actor_provenance": "gateway_forwarded"},
                    privacy_subject_hmac=subject,
                    account_generation=1,
                )
            )
            db.commit()
        return report_id

    def delete():
        return _accept(
            SessionFactory,
            actor_id=actor_id,
            request_id=request_id,
        )

    with ThreadPoolExecutor(max_workers=2) as executor:
        ingest_future = executor.submit(ingest)
        assert ingest_locked.wait(timeout=5)
        delete_future = executor.submit(delete)
        time.sleep(0.15)
        assert not delete_future.done()
        release_ingest.set()
        report_id = ingest_future.result(timeout=5)
        deletion = delete_future.result(timeout=5)
    assert deletion.status.overall_status == "PARTIAL"
    with SessionFactory() as db:
        assert db.get(Report, report_id) is not None
        assert db.get(AccountDeletionRequest, request_id) is not None
    with pytest.raises(PrivacyLifecycleError) as post_tombstone:
        with SessionFactory() as db:
            lock_report_ingest_transaction(db, subject, 1)
    assert post_tombstone.value.code == "account_generation_tombstoned"
    engine.dispose()


def test_fp046_status_refreshes_rows_after_waiting_for_subject_lock(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"status-race.{suffix}"
    request_id = f"delete_status_race_{suffix}"
    pre_digest = "f" * 64
    accepted = _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=request_id,
        pre_digest=pre_digest,
    )
    subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
    writer_locked = threading.Event()
    reader_loaded = threading.Event()
    release_writer = threading.Event()
    original_shared_lock = privacy_lifecycle.lock_privacy_subject_shared

    def observed_shared_lock(db, privacy_subject: str, account_generation: int) -> None:
        reader_loaded.set()
        original_shared_lock(db, privacy_subject, account_generation)

    monkeypatch.setattr(
        privacy_lifecycle,
        "lock_privacy_subject_shared",
        observed_shared_lock,
    )

    def update_status() -> None:
        with SessionFactory() as db:
            lock_privacy_subject_exclusive(db, subject, 1)
            writer_locked.set()
            assert release_writer.wait(timeout=5)
            transition_deletion_item(
                db,
                request_id=request_id,
                item_key="server_originals",
                operation_id=f"status-race-{suffix}",
                expected_status_revision=1,
                next_state="IN_PROGRESS",
                evidence_sha256=None,
            )

    def read_status():
        with SessionFactory() as db:
            return get_account_deletion_status(
                db,
                request_id=request_id,
                actor_id=actor_id,
                account_generation=1,
                access_pre_digest=pre_digest,
                tombstone_id=str(accepted.status.tombstone_id),
                secret=PRIVACY_SECRET,
            )

    with ThreadPoolExecutor(max_workers=2) as executor:
        writer = executor.submit(update_status)
        assert writer_locked.wait(timeout=5)
        reader = executor.submit(read_status)
        assert reader_loaded.wait(timeout=5)
        release_writer.set()
        writer.result(timeout=5)
        status = reader.result(timeout=5)

    assert status.revision == 2
    assert next(
        item for item in status.items if item.key == "server_originals"
    ).status == "IN_PROGRESS"
    engine.dispose()


def test_fp046_multi_device_target_set_requires_every_installation() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"devices.{suffix}"
    first_installation = f"install_a_{suffix}"
    second_installation = f"install_b_{suffix}"
    _record_consent(
        SessionFactory,
        actor_id=actor_id,
        installation_id=first_installation,
    )
    _record_consent(
        SessionFactory,
        actor_id=actor_id,
        installation_id=second_installation,
    )
    accepted = _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=f"delete_devices_{suffix}",
    )
    with SessionFactory() as db:
        targets = db.scalars(
            select(AccountDeletionDeviceTarget).where(
                AccountDeletionDeviceTarget.request_id == accepted.status.request_id
            )
        ).all()
    assert len(targets) == 2

    first = _evidence(
        accepted.status,
        installation_id=first_installation,
        evidence_id=f"device-first-{suffix}",
    )
    with SessionFactory() as db:
        after_first = record_device_deletion_evidence(
            db,
            payload=first,
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest="a" * 64,
            tombstone_id=accepted.status.tombstone_id,
            secret=PRIVACY_SECRET,
        )
    assert after_first.revision == 2
    assert after_first.items[0].status == "EXTERNAL_PENDING"
    assert after_first.items[0].evidence_sha256 is None
    with SessionFactory() as db:
        replay = record_device_deletion_evidence(
            db,
            payload=first,
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest="a" * 64,
            tombstone_id=accepted.status.tombstone_id,
            secret=PRIVACY_SECRET,
        )
    assert replay.revision == 2

    duplicate = _evidence(
        after_first,
        installation_id=first_installation,
        evidence_id=f"device-duplicate-{suffix}",
    )
    with pytest.raises(PrivacyLifecycleError) as already_terminal:
        with SessionFactory() as db:
            record_device_deletion_evidence(
                db,
                payload=duplicate,
                actor_id=actor_id,
                account_generation=1,
                access_pre_digest="a" * 64,
                tombstone_id=accepted.status.tombstone_id,
                secret=PRIVACY_SECRET,
            )
    assert already_terminal.value.code == "device_deletion_installation_already_terminal"

    outsider = _evidence(
        after_first,
        installation_id=f"install_outsider_{suffix}",
        evidence_id=f"device-outsider-{suffix}",
    )
    with pytest.raises(PrivacyLifecycleError) as not_targeted:
        with SessionFactory() as db:
            record_device_deletion_evidence(
                db,
                payload=outsider,
                actor_id=actor_id,
                account_generation=1,
                access_pre_digest="a" * 64,
                tombstone_id=accepted.status.tombstone_id,
                secret=PRIVACY_SECRET,
            )
    assert not_targeted.value.code == "device_deletion_installation_not_targeted"

    second = _evidence(
        after_first,
        installation_id=second_installation,
        evidence_id=f"device-second-{suffix}",
        result="NOT_FOUND",
    )
    with SessionFactory() as db:
        completed = record_device_deletion_evidence(
            db,
            payload=second,
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest="a" * 64,
            tombstone_id=accepted.status.tombstone_id,
            secret=PRIVACY_SECRET,
        )
    assert completed.revision == 3
    assert completed.items[0].status == "COMPLETED"
    assert completed.items[0].evidence_sha256 is not None
    engine.dispose()


def test_fp046_database_rejects_forged_device_target_set_aggregate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"device-aggregate.{suffix}"
    first_installation = f"install_aggregate_a_{suffix}"
    second_installation = f"install_aggregate_b_{suffix}"
    _record_consent(
        SessionFactory,
        actor_id=actor_id,
        installation_id=first_installation,
    )
    _record_consent(
        SessionFactory,
        actor_id=actor_id,
        installation_id=second_installation,
    )
    accepted = _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=f"delete_device_aggregate_{suffix}",
    )
    first = _evidence(
        accepted.status,
        installation_id=first_installation,
        evidence_id=f"aggregate-first-{suffix}",
    )
    with SessionFactory() as db:
        after_first = record_device_deletion_evidence(
            db,
            payload=first,
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest="a" * 64,
            tombstone_id=accepted.status.tombstone_id,
            secret=PRIVACY_SECRET,
        )

    monkeypatch.setattr(
        privacy_lifecycle,
        "_device_target_set_evidence_sha256",
        lambda targets: "f" * 64,
    )
    second = _evidence(
        after_first,
        installation_id=second_installation,
        evidence_id=f"aggregate-forged-{suffix}",
    )
    with pytest.raises(IntegrityError) as rejected:
        with SessionFactory() as db:
            record_device_deletion_evidence(
                db,
                payload=second,
                actor_id=actor_id,
                account_generation=1,
                access_pre_digest="a" * 64,
                tombstone_id=accepted.status.tombstone_id,
                secret=PRIVACY_SECRET,
            )
    assert getattr(rejected.value.orig, "sqlstate", None) == "23514"
    assert "device aggregate does not match the frozen target set" in str(
        rejected.value.orig
    )
    with SessionFactory() as db:
        stored = db.get(AccountDeletionRequest, accepted.status.request_id)
        device_item = db.get(
            AccountDeletionItem,
            (accepted.status.request_id, "device_untransmitted_data"),
        )
        assert stored is not None
        assert device_item is not None
        assert stored.status_revision == after_first.revision
        assert device_item.state == "EXTERNAL_PENDING"
        assert device_item.evidence_sha256 is None
    engine.dispose()


def test_fp046_consent_withdrawal_serializes_before_report_ingest() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"withdraw.{suffix}"
    subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
    installation_id = _installation_id(actor_id)
    _record_consent(
        SessionFactory,
        actor_id=actor_id,
        installation_id=installation_id,
    )
    withdrawal_locked = threading.Event()
    release_withdrawal = threading.Event()
    ingest_started = threading.Event()

    def withdraw() -> None:
        with SessionFactory() as db:
            lock_privacy_subject_exclusive(db, subject, 1)
            withdrawal_locked.set()
            assert release_withdrawal.wait(timeout=5)
            record_consent_event(
                db,
                actor_id=actor_id,
                account_generation=1,
                installation_id=installation_id,
                request_id=f"withdraw_consent_{suffix}",
                client_revision=2,
                policy_version="FP-013-1.0.0",
                item_versions={
                    "raw_source_collection": "FP-013-RAW-1.0.0",
                    "automatic_reporting": "FP-013-AUTO-1.0.0",
                    "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
                    "training_reuse": "FP-013-TRAINING-1.0.0",
                },
                raw_source_collection=False,
                automatic_reporting=False,
                mobile_network_transfer=False,
                training_reuse=False,
                secret=PRIVACY_SECRET,
            )

    def ingest() -> str:
        ingest_started.set()
        with SessionFactory() as db:
            try:
                lock_report_ingest_transaction(
                    db,
                    subject,
                    1,
                    automatic_reporting=False,
                )
            except PrivacyLifecycleError as exc:
                return exc.code
        return "allowed"

    with ThreadPoolExecutor(max_workers=2) as executor:
        withdrawal = executor.submit(withdraw)
        assert withdrawal_locked.wait(timeout=5)
        report = executor.submit(ingest)
        assert ingest_started.wait(timeout=5)
        time.sleep(0.15)
        assert not report.done()
        release_withdrawal.set()
        withdrawal.result(timeout=5)
        assert report.result(timeout=5) == "raw_source_collection_consent_required"
    engine.dispose()


def test_fp046_auto_reporting_requires_automatic_consent_only_for_auto() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"auto-consent.{suffix}"
    subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
    _record_consent(
        SessionFactory,
        actor_id=actor_id,
        automatic_reporting=False,
        training_reuse=False,
    )
    with SessionFactory() as db:
        lock_report_ingest_transaction(
            db,
            subject,
            1,
            automatic_reporting=False,
        )
        db.rollback()
    with pytest.raises(PrivacyLifecycleError) as automatic_denied:
        with SessionFactory() as db:
            lock_report_ingest_transaction(
                db,
                subject,
                1,
                automatic_reporting=True,
            )
    assert automatic_denied.value.code == "automatic_reporting_consent_required"
    engine.dispose()


def test_fp046_hmac_rotation_mismatch_fails_closed() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"key-binding.{suffix}"
    _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=f"delete_key_bound_{suffix}",
    )
    mismatched_secret = "walksafe-pytest-rotated-privacy-hmac-secret-boundary-b"
    with pytest.raises(PrivacyLifecycleError) as mismatch:
        with SessionFactory() as db:
            bind_or_verify_privacy_hmac_key(
                db,
                secret=mismatched_secret,
                key_version=1,
            )
    assert mismatch.value.code == "privacy_hmac_key_mismatch"
    with pytest.raises(PrivacyLifecycleError) as deletion_rejected:
        with SessionFactory() as db:
            accept_account_deletion(
                db,
                payload=_request(f"delete_key_mismatch_{suffix}"),
                actor_id=actor_id,
                account_generation=1,
                access_pre_digest="a" * 64,
                tombstone_id=None,
                secret=mismatched_secret,
                key_version=1,
            )
    assert deletion_rejected.value.code == "privacy_hmac_key_mismatch"
    mismatched_settings = SimpleNamespace(
        privacy_hmac_secret=mismatched_secret,
        privacy_hmac_key_version=1,
    )
    readiness = _privacy_hmac_binding_readiness(mismatched_settings)
    assert readiness == {
        "ready": False,
        "reason": "privacy_hmac_binding_mismatch",
    }
    with pytest.raises(HTTPException) as report_rejected:
        with SessionFactory() as db:
            _privacy_report_binding(
                db=db,
                actor_id=actor_id,
                account_generation=1,
                settings=mismatched_settings,
            )
    assert report_rejected.value.status_code == 503
    assert report_rejected.value.detail["code"] == "privacy_hmac_key_mismatch"
    with SessionFactory() as db:
        binding = db.get(PrivacyHmacKeyBinding, 1)
        assert binding is not None
        assert binding.key_version == 1
    engine.dispose()


@pytest.mark.parametrize("future_seconds", (0, 300))
def test_fp046_database_rejects_raw_unbound_or_future_completion_receipts(
    future_seconds: int,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"receipt-provenance.{suffix}"
    _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=f"delete_receipt_provenance_{suffix}",
    )
    processed_at = datetime.now(timezone.utc) + timedelta(seconds=future_seconds)
    with pytest.raises(IntegrityError) as rejected:
        with SessionFactory.begin() as db:
            db.add(
                AccountDeletionReceipt(
                    privacy_subject_hmac=privacy_subject_hmac(
                        actor_id,
                        1,
                        PRIVACY_SECRET,
                    ),
                    processed_at=processed_at,
                    result="COMPLETED",
                    expires_at=processed_at.replace(year=processed_at.year + 3),
                    receipt_sha256="f" * 64,
                )
            )
    assert getattr(rejected.value.orig, "sqlstate", None) == "23514"
    assert "receipt chronology or binding is invalid" in str(rejected.value.orig)
    engine.dispose()


def test_fp046_receipt_purge_is_expiry_only_and_function_scoped() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    now = datetime.now(timezone.utc)
    expired_processed = now.replace(year=now.year - 4)
    expired_at = expired_processed.replace(year=expired_processed.year + 3)
    active_processed = now
    active_at = active_processed.replace(year=active_processed.year + 3)
    expired_hash = hashlib.sha256(f"expired-{uuid.uuid4()}".encode()).hexdigest()
    active_hash = hashlib.sha256(f"active-{uuid.uuid4()}".encode()).hexdigest()
    with SessionFactory.begin() as db:
        # Migration-owner fixture setup: these synthetic rows exercise expiry
        # purging only and intentionally do not represent completed requests.
        db.execute(
            text(
                "ALTER TABLE account_deletion_receipts DISABLE TRIGGER "
                "account_deletion_receipts_validate_provenance"
            )
        )
        db.add_all(
            [
                AccountDeletionReceipt(
                    privacy_subject_hmac="a" * 64,
                    processed_at=expired_processed,
                    result="COMPLETED",
                    expires_at=expired_at,
                    receipt_sha256=expired_hash,
                ),
                AccountDeletionReceipt(
                    privacy_subject_hmac="b" * 64,
                    processed_at=active_processed,
                    result="COMPLETED",
                    expires_at=active_at,
                    receipt_sha256=active_hash,
                ),
            ]
        )
        db.flush()
        db.execute(
            text(
                "ALTER TABLE account_deletion_receipts ENABLE TRIGGER "
                "account_deletion_receipts_validate_provenance"
            )
        )
    with engine.connect().execution_options(isolation_level="AUTOCOMMIT") as connection:
        roles = {
            row["rolname"]: row
            for row in connection.execute(
                text(
                    "SELECT rolname, rolsuper, rolcreaterole, rolcreatedb, "
                    "rolcanlogin, rolreplication, rolbypassrls, rolinherit "
                    "FROM pg_roles "
                    "WHERE rolname IN ('walksafe_backend_runtime', "
                    "'walksafe_receipt_purger', 'walksafe_receipt_purge_owner')"
                )
            ).mappings()
        }
        assert set(roles) == {
            "walksafe_backend_runtime",
            "walksafe_receipt_purger",
            "walksafe_receipt_purge_owner",
        }
        for role_name, role in roles.items():
            assert not any(
                role[field]
                for field in (
                    "rolsuper",
                    "rolcreaterole",
                    "rolcreatedb",
                    "rolcanlogin",
                    "rolreplication",
                    "rolbypassrls",
                )
            )
            assert role["rolinherit"] is (role_name == "walksafe_backend_runtime")
        migration_owner_membership = connection.execute(
            text(
                "SELECT bool_or(set_option) AS can_set, "
                "bool_or(inherit_option) AS can_inherit "
                "FROM pg_auth_members "
                "WHERE roleid = 'walksafe_receipt_purge_owner'::regrole "
                "AND member = current_user::regrole"
            )
        ).mappings().one()
        assert migration_owner_membership == {
            "can_set": True,
            "can_inherit": True,
        }
        boundary = connection.execute(
            text(
                "SELECT pg_get_userbyid(class.relowner) AS table_owner, "
                "pg_has_role('walksafe_backend_runtime', "
                "'walksafe_receipt_purge_owner', 'MEMBER') AS runtime_owner_member, "
                "pg_has_role('walksafe_receipt_purger', "
                "'walksafe_receipt_purge_owner', 'MEMBER') AS purger_owner_member, "
                "has_table_privilege('walksafe_backend_runtime', class.oid, 'SELECT') "
                "AS runtime_select, "
                "has_table_privilege('walksafe_backend_runtime', class.oid, 'INSERT') "
                "AS runtime_insert, "
                "has_table_privilege('walksafe_backend_runtime', class.oid, 'UPDATE') "
                "AS runtime_update, "
                "has_table_privilege('walksafe_backend_runtime', class.oid, 'DELETE') "
                "AS runtime_delete, "
                "has_table_privilege('walksafe_backend_runtime', class.oid, 'TRUNCATE') "
                "AS runtime_truncate, "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.alembic_version', 'SELECT') AS runtime_migration_select, "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.alembic_version', 'INSERT') AS runtime_migration_insert, "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.alembic_version', 'UPDATE') AS runtime_migration_update, "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.alembic_version', 'DELETE') AS runtime_migration_delete, "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.alembic_version', 'TRUNCATE') AS runtime_migration_truncate, "
                "has_table_privilege('walksafe_backend_runtime', "
                "'public.walksafe_fp046_runtime_acl_baseline', 'SELECT') "
                "AS runtime_acl_baseline_select, "
                "has_schema_privilege('walksafe_receipt_purger', 'public', 'USAGE') "
                "AS purger_schema_usage, "
                "has_function_privilege('walksafe_backend_runtime', "
                "'walksafe_purge_expired_account_deletion_receipts(integer)', "
                "'EXECUTE') AS runtime_execute, "
                "has_function_privilege('walksafe_receipt_purger', "
                "'walksafe_purge_expired_account_deletion_receipts(integer)', "
                "'EXECUTE') AS purger_execute "
                "FROM pg_class AS class "
                "WHERE class.oid = 'account_deletion_receipts'::regclass"
            )
        ).mappings().one()
        assert boundary == {
            "table_owner": "walksafe_receipt_purge_owner",
            "runtime_owner_member": False,
            "purger_owner_member": False,
            "runtime_select": True,
            "runtime_insert": True,
            "runtime_update": False,
            "runtime_delete": False,
            "runtime_truncate": False,
            "runtime_migration_select": True,
            "runtime_migration_insert": False,
            "runtime_migration_update": False,
            "runtime_migration_delete": False,
            "runtime_migration_truncate": False,
            "runtime_acl_baseline_select": False,
            "purger_schema_usage": True,
            "runtime_execute": False,
            "purger_execute": True,
        }

        sequence_privileges = {
            row["relname"]: row
            for row in connection.execute(
                text(
                    "SELECT class.relname, "
                    "has_sequence_privilege('walksafe_backend_runtime', class.oid, "
                    "'USAGE') AS runtime_usage, "
                    "has_sequence_privilege('walksafe_backend_runtime', class.oid, "
                    "'SELECT') AS runtime_select, "
                    "has_sequence_privilege('walksafe_backend_runtime', class.oid, "
                    "'UPDATE') AS runtime_update "
                    "FROM pg_class AS class "
                    "JOIN pg_namespace AS namespace "
                    "ON namespace.oid = class.relnamespace "
                    "WHERE namespace.nspname = 'public' AND class.relkind = 'S'"
                )
            ).mappings()
        }
        for sequence_name in (
            "actor_rate_limit_events_id_seq",
            "admin_security_auth_attempts_id_seq",
        ):
            assert sequence_privileges[sequence_name] == {
                "relname": sequence_name,
                "runtime_usage": True,
                "runtime_select": True,
                "runtime_update": False,
            }
        assert all(not row["runtime_update"] for row in sequence_privileges.values())

        runtime_extension_acl_count = connection.execute(
            text(
                "SELECT count(*) FROM pg_class AS class "
                "JOIN pg_namespace AS namespace "
                "ON namespace.oid = class.relnamespace "
                "JOIN pg_depend AS dependency "
                "ON dependency.classid = 'pg_class'::regclass "
                "AND dependency.objid = class.oid "
                "AND dependency.objsubid = 0 "
                "AND dependency.refclassid = 'pg_extension'::regclass "
                "AND dependency.deptype = 'e' "
                "CROSS JOIN LATERAL aclexplode(class.relacl) AS acl "
                "WHERE namespace.nspname = 'public' "
                "AND acl.grantee = 'walksafe_backend_runtime'::regrole"
            )
        ).scalar_one()
        assert runtime_extension_acl_count == 0

        app_table_privileges = {
            row["relname"]: row
            for row in connection.execute(
                text(
                    "SELECT class.relname, "
                    "has_table_privilege('walksafe_backend_runtime', class.oid, "
                    "'SELECT') AS runtime_select, "
                    "has_table_privilege('walksafe_backend_runtime', class.oid, "
                    "'INSERT') AS runtime_insert, "
                    "has_table_privilege('walksafe_backend_runtime', class.oid, "
                    "'UPDATE') AS runtime_update, "
                    "has_table_privilege('walksafe_backend_runtime', class.oid, "
                    "'DELETE') AS runtime_delete, "
                    "has_table_privilege('walksafe_backend_runtime', class.oid, "
                    "'TRUNCATE') AS runtime_truncate "
                    "FROM pg_class AS class "
                    "JOIN pg_namespace AS namespace "
                    "ON namespace.oid = class.relnamespace "
                    "WHERE namespace.nspname = 'public' "
                    "AND class.relkind IN ('r', 'p') "
                    "AND class.relname <> 'alembic_version' "
                    "AND class.relname <> 'walksafe_fp046_runtime_acl_baseline' "
                    "AND NOT EXISTS (SELECT 1 FROM pg_depend AS dependency "
                    "WHERE dependency.classid = 'pg_class'::regclass "
                    "AND dependency.objid = class.oid "
                    "AND dependency.objsubid = 0 "
                    "AND dependency.refclassid = 'pg_extension'::regclass "
                    "AND dependency.deptype = 'e')"
                )
            ).mappings()
        }
        expected_update_tables = {
            "reports",
            "report_original_access_grants",
            "account_deletion_requests",
            "account_deletion_items",
            "account_deletion_device_targets",
        }
        assert len(app_table_privileges) == 30
        assert {
            table_name
            for table_name, row in app_table_privileges.items()
            if not row["runtime_select"]
        } == {
            "admin_security_recovery_codes",
            "admin_security_recovery_transactions",
            "walksafe_recovery_custody_capabilities",
            "walksafe_recovery_custody_markers",
        }
        assert {
            table_name
            for table_name, row in app_table_privileges.items()
            if not row["runtime_insert"]
        } == {
            "admin_device_keys",
            "admin_security_controls",
            "admin_security_reconfirmations",
            "admin_security_recovery_codes",
            "admin_security_recovery_transactions",
            "admin_security_sessions",
            "walksafe_recovery_custody_capabilities",
            "walksafe_recovery_custody_markers",
        }
        assert {
            table_name
            for table_name, row in app_table_privileges.items()
            if row["runtime_update"]
        } == expected_update_tables
        assert {
            table_name
            for table_name, row in app_table_privileges.items()
            if row["runtime_delete"]
        } == {"actor_rate_limit_events"}
        assert all(
            not row["runtime_truncate"] for row in app_table_privileges.values()
        )

        connection.execute(text("SET ROLE walksafe_backend_runtime"))
        try:
            with pytest.raises(PrivacyLifecycleError) as reset_role_escape:
                with Session(bind=connection) as runtime_db:
                    assert_privacy_runtime_database_role(runtime_db)
            assert reset_role_escape.value.code == "privacy_database_role_unsafe"
        finally:
            connection.execute(text("RESET ROLE"))

        connection.execute(text("SET SESSION AUTHORIZATION walksafe_backend_runtime"))
        try:
            with Session(bind=connection) as runtime_db:
                assert_privacy_runtime_database_role(runtime_db)
            connection.execute(text("SET walksafe.receipt_purge = 'on'"))
            connection.execute(
                text(
                    "CREATE TEMP TABLE privacy_consent_events "
                    "(client_revision bigint)"
                )
            )
            connection.execute(
                text(
                    "CREATE TEMP TABLE account_deletion_requests "
                    "(request_id text)"
                )
            )
            with pytest.raises(SQLAlchemyError) as raw_consent_rejected:
                connection.execute(
                    text(
                        "INSERT INTO public.privacy_consent_events ("
                        "id, request_id, privacy_subject_hmac, account_generation, "
                        "installation_subject_hmac, client_revision, subject_revision, "
                        "policy_version, item_versions, raw_source_collection, "
                        "automatic_reporting, mobile_network_transfer, training_reuse, "
                        "receipt_sha256, recorded_at) VALUES ("
                        ":id, :request_id, :privacy_subject, 1, :installation_subject, "
                        "99, 99, 'FP-013-1.0.0', CAST(:item_versions AS jsonb), "
                        "true, true, false, false, :receipt_sha256, clock_timestamp())"
                    ),
                    {
                        "id": uuid.uuid4(),
                        "request_id": f"runtime_consent_{suffix}",
                        "privacy_subject": "c" * 64,
                        "installation_subject": "d" * 64,
                        "item_versions": json.dumps(
                            {
                                "raw_source_collection": "FP-013-RAW-1.0.0",
                                "automatic_reporting": "FP-013-AUTO-1.0.0",
                                "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
                                "training_reuse": "FP-013-TRAINING-1.0.0",
                            }
                        ),
                        "receipt_sha256": "e" * 64,
                    },
                )
            assert getattr(raw_consent_rejected.value.orig, "sqlstate", None) == "23514"
            with pytest.raises(SQLAlchemyError) as raw_receipt_rejected:
                connection.execute(
                    text(
                        "INSERT INTO public.account_deletion_receipts ("
                        "id, privacy_subject_hmac, processed_at, result, expires_at, "
                        "receipt_sha256) VALUES ("
                        ":id, :privacy_subject, statement_timestamp(), 'COMPLETED', "
                        "statement_timestamp() + interval '3 years', :receipt_sha256)"
                    ),
                    {
                        "id": uuid.uuid4(),
                        "privacy_subject": "c" * 64,
                        "receipt_sha256": hashlib.sha256(
                            f"runtime-receipt-{suffix}".encode()
                        ).hexdigest(),
                    },
                )
            assert getattr(raw_receipt_rejected.value.orig, "sqlstate", None) == "23514"
            runtime_attacks = (
                text(
                    "DELETE FROM account_deletion_receipts "
                    "WHERE receipt_sha256 = :receipt_sha256"
                ).bindparams(receipt_sha256=expired_hash),
                text(
                    "UPDATE account_deletion_receipts SET result = result "
                    "WHERE receipt_sha256 = :receipt_sha256"
                ).bindparams(receipt_sha256=active_hash),
                text("TRUNCATE TABLE account_deletion_receipts"),
                text("UPDATE alembic_version SET version_num = version_num"),
                text(
                    "SELECT setval('public.actor_rate_limit_events_id_seq', "
                    "1, false)"
                ),
                text(
                    "ALTER TABLE account_deletion_receipts DISABLE TRIGGER "
                    "account_deletion_receipts_expiry_delete"
                ),
                text("SELECT walksafe_purge_expired_account_deletion_receipts(10)"),
            )
            for statement in runtime_attacks:
                with pytest.raises(SQLAlchemyError) as denied:
                    connection.execute(statement)
                assert getattr(denied.value.orig, "sqlstate", None) == "42501"
        finally:
            connection.execute(text("RESET SESSION AUTHORIZATION"))

        connection.execute(text("SET SESSION AUTHORIZATION walksafe_receipt_purger"))
        try:
            with pytest.raises(SQLAlchemyError) as invalid_batch_limit:
                connection.execute(
                    text(
                        "SELECT "
                        "walksafe_purge_expired_account_deletion_receipts(NULL)"
                    )
                )
            assert getattr(invalid_batch_limit.value.orig, "sqlstate", None) == "22023"
            with pytest.raises(SQLAlchemyError) as denied:
                connection.execute(
                    text(
                        "DELETE FROM account_deletion_receipts "
                        "WHERE receipt_sha256 = :receipt_sha256"
                    ),
                    {"receipt_sha256": expired_hash},
                )
            assert getattr(denied.value.orig, "sqlstate", None) == "42501"
            purged = connection.execute(
                text("SELECT walksafe_purge_expired_account_deletion_receipts(10)")
            ).scalar_one()
        finally:
            connection.execute(text("RESET SESSION AUTHORIZATION"))
    assert purged == 1
    with SessionFactory() as db:
        assert db.scalar(
            select(AccountDeletionReceipt).where(
                AccountDeletionReceipt.receipt_sha256 == expired_hash
            )
        ) is None
        assert db.scalar(
            select(AccountDeletionReceipt).where(
                AccountDeletionReceipt.receipt_sha256 == active_hash
            )
        ) is not None
    engine.dispose()


def test_fp046_database_rejects_false_completion_and_terminal_rewrite() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    accepted = _accept(
        SessionFactory,
        actor_id=f"db-guard.{suffix}",
        request_id=f"delete_db_guard_{suffix}",
    )
    with pytest.raises(IntegrityError):
        with SessionFactory.begin() as db:
            db.execute(
                update(AccountDeletionRequest)
                .where(AccountDeletionRequest.request_id == accepted.status.request_id)
                .values(
                    overall_status="COMPLETED",
                    completion_receipt_sha256="f" * 64,
                )
            )
    with pytest.raises(PrivacyLifecycleError) as unverified:
        with SessionFactory() as db:
            transition_deletion_item(
                db,
                request_id=accepted.status.request_id,
                item_key="server_originals",
                operation_id=f"terminal-{suffix}",
                expected_status_revision=accepted.status.revision,
                next_state="COMPLETED",
                evidence_sha256="c" * 64,
                terminal_at=datetime.now(timezone.utc),
            )
    assert unverified.value.code == "account_deletion_server_manifest_required"
    with engine.connect() as connection:
        transaction = connection.begin()
        try:
            connection.execute(
                text("SET SESSION AUTHORIZATION walksafe_backend_runtime")
            )
            with pytest.raises(SQLAlchemyError) as runtime_bypass:
                connection.execute(
                    text(
                        "UPDATE account_deletion_items SET state = 'COMPLETED', "
                        "item_revision = item_revision + 1, "
                        "evidence_sha256 = :evidence_sha256, "
                        "terminal_at = clock_timestamp(), "
                        "updated_at = clock_timestamp() "
                        "WHERE request_id = :request_id "
                        "AND item_key = 'server_originals'"
                    ),
                    {
                        "evidence_sha256": "b" * 64,
                        "request_id": accepted.status.request_id,
                    },
                )
            assert getattr(runtime_bypass.value.orig, "sqlstate", None) == "42501"
        finally:
            transaction.rollback()
    with _worker_session_factory(engine, suffix) as WorkerSessionFactory:
        with WorkerSessionFactory() as db:
            transitioned = complete_server_deletion_inventory_from_manifest(
                db,
                request_id=accepted.status.request_id,
                manifest_sha256="c" * 64,
                terminal_at=datetime.now(timezone.utc),
            )
    with pytest.raises(SQLAlchemyError):
        with SessionFactory.begin() as db:
            db.execute(
                update(AccountDeletionItem)
                .where(
                    AccountDeletionItem.request_id == accepted.status.request_id,
                    AccountDeletionItem.item_key == "server_originals",
                )
                .values(state="IN_PROGRESS", evidence_sha256=None, terminal_at=None)
            )
    with pytest.raises(PrivacyLifecycleError) as service_rewrite:
        with SessionFactory() as db:
            complete_server_deletion_inventory_from_manifest(
                db,
                request_id=accepted.status.request_id,
                manifest_sha256="d" * 64,
                terminal_at=datetime.now(timezone.utc),
            )
    assert transitioned.revision == accepted.status.revision + 4
    assert service_rewrite.value.code == "account_deletion_worker_role_unsafe"
    engine.dispose()


def test_fp046_database_rejects_malformed_event_before_device_replay() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"event-guard.{suffix}"
    accepted = _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=f"delete_event_guard_{suffix}",
    )
    operation_id = f"device-event-guard-{suffix}"
    with pytest.raises(IntegrityError):
        with SessionFactory.begin() as db:
            db.add(
                AccountDeletionEvent(
                    request_id=accepted.status.request_id,
                    operation_id=operation_id,
                    event_type="ITEM_TRANSITION",
                    item_key="server_originals",
                    previous_state="PENDING",
                    next_state="IN_PROGRESS",
                    status_revision=2,
                    operation_sha256="e" * 64,
                    evidence_sha256=None,
                )
            )
    engine.dispose()


def test_fp046_database_enforces_frozen_targets_and_revision_event_ledger() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    accepted = _accept(
        SessionFactory,
        actor_id=f"ledger-guard.{suffix}",
        request_id=f"delete_ledger_guard_{suffix}",
    )
    request_id = accepted.status.request_id

    with pytest.raises(IntegrityError):
        with SessionFactory.begin() as db:
            db.add(
                AccountDeletionDeviceTarget(
                    request_id=request_id,
                    installation_subject_hmac="f" * 64,
                    state="PENDING",
                    updated_at=accepted.status.accepted_at,
                    evidence_sha256=None,
                    terminal_at=None,
                )
            )

    with pytest.raises(IntegrityError):
        with SessionFactory.begin() as db:
            db.execute(
                text(
                    "UPDATE account_deletion_requests "
                    "SET status_revision = status_revision + 1, "
                    "updated_at = clock_timestamp() WHERE request_id = :request_id"
                ),
                {"request_id": request_id},
            )

    with pytest.raises(IntegrityError):
        with SessionFactory.begin() as db:
            db.execute(
                text(
                    "UPDATE account_deletion_items SET state = 'IN_PROGRESS', "
                    "item_revision = item_revision + 1, updated_at = clock_timestamp() "
                    "WHERE request_id = :request_id AND item_key = 'server_originals'"
                ),
                {"request_id": request_id},
            )

    with pytest.raises(IntegrityError):
        with SessionFactory.begin() as db:
            db.execute(
                text(
                    "UPDATE account_deletion_requests "
                    "SET status_revision = status_revision + 1, "
                    "updated_at = clock_timestamp() + interval '1 minute' "
                    "WHERE request_id = :request_id"
                ),
                {"request_id": request_id},
            )

    with SessionFactory() as db:
        transitioned = transition_deletion_item(
            db,
            request_id=request_id,
            item_key="server_originals",
            operation_id=f"ledger-transition-{suffix}",
            expected_status_revision=1,
            next_state="IN_PROGRESS",
            evidence_sha256=None,
        )
    with pytest.raises(IntegrityError):
        with SessionFactory.begin() as db:
            db.add(
                AccountDeletionEvent(
                    request_id=request_id,
                    operation_id=f"duplicate-revision-{suffix}",
                    event_type="ITEM_TRANSITION",
                    item_key="server_originals",
                    previous_state="PENDING",
                    next_state="IN_PROGRESS",
                    status_revision=transitioned.revision,
                    operation_sha256="d" * 64,
                    evidence_sha256=None,
                    recorded_at=transitioned.updated_at,
                )
            )
    with SessionFactory() as db:
        revisions = db.scalars(
            select(AccountDeletionEvent.status_revision)
            .where(AccountDeletionEvent.request_id == request_id)
            .order_by(AccountDeletionEvent.status_revision)
        ).all()
        request = db.get(AccountDeletionRequest, request_id)
        assert request is not None
        assert revisions == list(range(1, request.status_revision + 1))
    engine.dispose()


def test_fp046_database_rejects_past_retry_and_review_schedules() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    accepted = _accept(
        SessionFactory,
        actor_id=f"db-schedule.{suffix}",
        request_id=f"delete_db_schedule_{suffix}",
    )
    request_id = accepted.status.request_id
    past_schedule = accepted.status.accepted_at + timedelta(microseconds=1)

    invalid_item_values = (
        {
            "state": "RETRY_WAIT",
            "retry_after": past_schedule,
        },
        {
            "state": "LEGAL_HOLD",
            "restriction_reason": "court order",
            "legal_hold_review_at": past_schedule,
            "legal_hold_contact": "privacy@example.invalid",
        },
    )
    for values in invalid_item_values:
        with pytest.raises(IntegrityError) as rejected:
            with SessionFactory.begin() as db:
                db.execute(
                    update(AccountDeletionItem)
                    .where(
                        AccountDeletionItem.request_id == request_id,
                        AccountDeletionItem.item_key == "server_originals",
                    )
                    .values(
                        item_revision=AccountDeletionItem.item_revision + 1,
                        **values,
                    )
                )
        assert getattr(rejected.value.orig, "sqlstate", None) == "23514"
        assert "account deletion item schedule must be in the future" in str(
            rejected.value.orig
        )

    with pytest.raises(IntegrityError) as rejected_event:
        with SessionFactory.begin() as db:
            db.execute(
                update(AccountDeletionRequest)
                .where(AccountDeletionRequest.request_id == request_id)
                .values(
                    status_revision=AccountDeletionRequest.status_revision + 1,
                    updated_at=accepted.status.accepted_at,
                )
            )
            db.add(
                AccountDeletionEvent(
                    request_id=request_id,
                    operation_id=f"past-event-schedule-{suffix}",
                    event_type="ITEM_TRANSITION",
                    item_key="server_originals",
                    previous_state="PENDING",
                    next_state="RETRY_WAIT",
                    status_revision=2,
                    operation_sha256="e" * 64,
                    evidence_sha256=None,
                    retry_after=past_schedule,
                    recorded_at=accepted.status.accepted_at,
                )
            )
            db.flush()
    assert getattr(rejected_event.value.orig, "sqlstate", None) == "23514"
    engine.dispose()


def test_fp046_service_rejects_future_or_pre_acceptance_transition_times() -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"chronology.{suffix}"
    accepted = _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=f"delete_chronology_{suffix}",
    )
    invalid_item_transitions = (
        {
            "operation_id": f"terminal-before-{suffix}",
            "next_state": "COMPLETED",
            "evidence_sha256": "1" * 64,
            "terminal_at": accepted.status.accepted_at - timedelta(seconds=1),
        },
        {
            "operation_id": f"terminal-future-{suffix}",
            "next_state": "COMPLETED",
            "evidence_sha256": "2" * 64,
            "terminal_at": datetime.now(timezone.utc) + timedelta(minutes=5),
        },
        {
            "operation_id": f"retry-past-{suffix}",
            "next_state": "RETRY_WAIT",
            "evidence_sha256": None,
            "retry_after": datetime.now(timezone.utc) - timedelta(seconds=1),
        },
        {
            "operation_id": f"review-past-{suffix}",
            "next_state": "LEGAL_HOLD",
            "evidence_sha256": None,
            "restriction_reason": "court order",
            "legal_hold_review_at": datetime.now(timezone.utc) - timedelta(seconds=1),
            "legal_hold_contact": "privacy@example.invalid",
        },
    )
    for transition in invalid_item_transitions:
        with pytest.raises(PrivacyLifecycleError) as invalid_time:
            with SessionFactory() as db:
                transition_deletion_item(
                    db,
                    request_id=accepted.status.request_id,
                    item_key="training_datasets",
                    expected_status_revision=accepted.status.revision,
                    **transition,
                )
        assert invalid_time.value.status_code == 422

    with pytest.raises(PrivacyLifecycleError) as device_item:
        with SessionFactory() as db:
            transition_deletion_item(
                db,
                request_id=accepted.status.request_id,
                item_key="device_untransmitted_data",
                operation_id=f"generic-device-{suffix}",
                expected_status_revision=accepted.status.revision,
                next_state="IN_PROGRESS",
                evidence_sha256=None,
            )
    assert device_item.value.code == "device_deletion_evidence_required"

    for label, completed_at in (
        ("before", accepted.status.accepted_at - timedelta(seconds=1)),
        ("future", datetime.now(timezone.utc) + timedelta(minutes=5)),
    ):
        evidence = DeviceDeletionEvidenceV2(
            schema_version="walksafe.device-deletion-evidence.v2",
            request_id=accepted.status.request_id,
            tombstone_id=accepted.status.tombstone_id,
            request_receipt_sha256=accepted.status.request_receipt_sha256,
            installation_id=_installation_id(actor_id),
            evidence_id=f"device-{label}-{suffix}",
            client_revision=accepted.status.client_revision,
            expected_status_revision=accepted.status.revision,
            item="device_untransmitted_data",
            result="DELETED",
            completed_at=completed_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
            evidence_sha256="0" * 64,
        )
        evidence = evidence.model_copy(
            update={"evidence_sha256": deletion_evidence_sha256(evidence)}
        )
        with pytest.raises(PrivacyLifecycleError) as invalid_device_time:
            with SessionFactory() as db:
                record_device_deletion_evidence(
                    db,
                    payload=evidence,
                    actor_id=actor_id,
                    account_generation=1,
                    access_pre_digest="a" * 64,
                    tombstone_id=accepted.status.tombstone_id,
                    secret=PRIVACY_SECRET,
                )
        assert invalid_device_time.value.code == "device_deletion_completed_time_out_of_range"
    engine.dispose()


def test_fp046_bound_hmac_fast_path_does_not_wait_for_global_lock() -> None:
    engine, SessionFactory = _session_factory()
    with SessionFactory.begin() as db:
        bind_or_verify_privacy_hmac_key(
            db,
            secret=PRIVACY_SECRET,
            key_version=1,
        )

    lock_key = "walksafe/privacy-hmac-key-binding/v1"
    with engine.connect() as blocker:
        blocker.execute(
            text("SELECT pg_advisory_lock(hashtextextended(:lock_key, 0))"),
            {"lock_key": lock_key},
        )

        def verify_binding() -> int:
            with SessionFactory.begin() as db:
                return bind_or_verify_privacy_hmac_key(
                    db,
                    secret=PRIVACY_SECRET,
                    key_version=1,
                ).binding_id

        with ThreadPoolExecutor(max_workers=1) as executor:
            future = executor.submit(verify_binding)
            try:
                assert future.result(timeout=1) == 1
            finally:
                blocker.execute(
                    text("SELECT pg_advisory_unlock(hashtextextended(:lock_key, 0))"),
                    {"lock_key": lock_key},
                )
    engine.dispose()
