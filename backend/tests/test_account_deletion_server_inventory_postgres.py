"""S1617-55 / S1617-59: server deletion items follow a re-queried inventory.

S1617-55: an object of a deleted report that survives outside the worker
journal (here in the report retention quarantine) keeps every server item
open and publishes no manifest; the quarantine/copy items are closed only on
the worker's re-queried inventory, never on a fixed NOT_APPLICABLE.
S1617-59: a report delivered to an institution leaves ``server_copies``
EXTERNAL_PENDING, so the account deletion never reaches COMPLETED while that
external copy is unconfirmed, even when every other item is terminal.
"""

from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import uuid

import pytest
from sqlalchemy import func, select, text

from backend.app.models import (
    AccountDeletionEvent,
    AccountDeletionItem,
    AccountDeletionRequest,
    Report,
    ReportImageObject,
)
from backend.app.services.privacy_lifecycle import (
    DELETION_ITEM_KEYS,
    PrivacyLifecycleError,
    SERVER_OWNED_ITEM_KEYS,
    _server_manifest_transitions,
    complete_server_deletion_inventory_from_manifest,
    privacy_subject_hmac,
    record_device_deletion_evidence,
    transition_deletion_item,
)
from backend.app.services.report_storage import RETENTION_QUARANTINE_DIRECTORY_NAME
from scripts.account_deletion_worker import (
    _prepare_roots as prepare_account_deletion_worker_roots,
    process_request as process_account_deletion_request,
    reconcile_journal as reconcile_account_deletion_journal,
)
from test_fp046_postgres_integration import (
    PRIVACY_SECRET,
    _accept,
    _evidence,
    _installation_id,
    _record_consent,
    _session_factory,
    _worker_session_factory,
)


_PG = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def test_s1617_55_59_manifest_transitions_follow_the_counted_inventory() -> None:
    legacy = dict((key, (state, basis)) for key, state, basis in _server_manifest_transitions(
        quarantined_raw_collection_count=None, external_copy_count=None,
    ))
    assert legacy["server_copies"][0] == "NOT_APPLICABLE"
    empty = dict((key, (state, basis)) for key, state, basis in _server_manifest_transitions(
        quarantined_raw_collection_count=0, external_copy_count=0,
    ))
    assert empty["server_quarantine"][0] == "NOT_APPLICABLE"
    assert "re-query" in str(empty["server_quarantine"][1])
    assert empty["server_copies"] == (
        "NOT_APPLICABLE",
        "no institution delivery recorded for the subject's reports at worker preparation",
    )
    counted = dict((key, (state, basis)) for key, state, basis in _server_manifest_transitions(
        quarantined_raw_collection_count=2, external_copy_count=1,
    ))
    assert counted["server_quarantine"] == ("COMPLETED", None)
    assert counted["server_copies"] == ("EXTERNAL_PENDING", None)
    for bad in (-1, 10_001, True, 1.0):
        with pytest.raises(ValueError):
            _server_manifest_transitions(
                quarantined_raw_collection_count=0, external_copy_count=bad,
            )


def _seed_report(SessionFactory, *, subject: str, upload_dir: Path, suffix: str) -> tuple[uuid.UUID, str]:
    report_id = uuid.uuid4()
    storage_name = f"{report_id}.wse"
    envelope = (b"walksafe-server-inventory-envelope-" + suffix.encode("ascii")) * 3
    object_path = upload_dir / storage_name
    object_path.write_bytes(envelope)
    object_path.chmod(0o600)
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
                payload={"source": "account-deletion-server-inventory-test"},
                privacy_subject_hmac=subject,
                account_generation=1,
            )
        )
        db.flush()
        db.add(
            ReportImageObject(
                report_id=report_id,
                storage_name=storage_name,
                envelope_version=1,
                algorithm="AES-256-GCM",
                aad_version=1,
                key_id=f"server-inventory-key-{suffix}",
                nonce=uuid.uuid4().bytes[:12],
                plaintext_sha256=hashlib.sha256(b"plaintext").hexdigest(),
                plaintext_size=9,
                envelope_sha256=hashlib.sha256(envelope).hexdigest(),
                envelope_size=len(envelope),
                content_type="image/jpeg",
            )
        )
    return report_id, storage_name


def _server_items(SessionFactory, request_id: str) -> dict[str, AccountDeletionItem]:
    with SessionFactory() as db:
        return {
            item.item_key: item
            for item in db.scalars(
                select(AccountDeletionItem).where(
                    AccountDeletionItem.request_id == request_id
                )
            ).all()
            if item.item_key in SERVER_OWNED_ITEM_KEYS
        }


def _roots(tmp_path: Path) -> tuple[Path, Path, Path]:
    tmp_path.chmod(0o700)
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    raw_object_dir = tmp_path / "raw-objects"
    raw_object_dir.mkdir(mode=0o700)
    journal_root = (tmp_path / "worker").resolve()
    prepare_account_deletion_worker_roots(journal_root)
    return upload_dir.resolve(), raw_object_dir.resolve(), journal_root


@_PG
def test_s1617_55_leftover_outside_journal_keeps_server_items_open(tmp_path: Path) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"server-leftover.{suffix}"
    request_id = f"delete_server_leftover_{suffix}"
    subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
    upload_dir, raw_object_dir, journal_root = _roots(tmp_path)
    _record_consent(SessionFactory, actor_id=actor_id)
    report_id, _storage_name = _seed_report(
        SessionFactory, subject=subject, upload_dir=upload_dir, suffix=suffix
    )
    _accept(SessionFactory, actor_id=actor_id, request_id=request_id)
    quarantine = upload_dir / RETENTION_QUARANTINE_DIRECTORY_NAME
    leftover = quarantine / f"{report_id}.jpg"

    def leave_quarantined_copy(point: str) -> None:
        if point == "before_object_removal":
            quarantine.mkdir(mode=0o700)
            leftover.write_bytes(b"legacy plaintext copy outside the worker journal")

    manifest_path = journal_root / "manifests" / f"{request_id}.json"
    try:
        with _worker_session_factory(engine, suffix) as WorkerSessionFactory:
            with pytest.raises(
                RuntimeError, match="server object inventory is not empty after commit"
            ):
                process_account_deletion_request(
                    WorkerSessionFactory,
                    request_id=request_id,
                    upload_dir=upload_dir,
                    raw_object_dir=raw_object_dir,
                    root=journal_root,
                    fault=leave_quarantined_copy,
                )
            with SessionFactory() as db:
                assert db.get(Report, report_id) is None
            assert not manifest_path.exists()
            assert all(
                item.state not in {"COMPLETED", "NOT_APPLICABLE"}
                for item in _server_items(SessionFactory, request_id).values()
            )
            journal_path = next((journal_root / "journals").glob("*.json"))
            # A restart re-scans and still refuses while the copy is there.
            with pytest.raises(
                RuntimeError, match="server object inventory is not empty after commit"
            ):
                reconcile_account_deletion_journal(
                    WorkerSessionFactory, journal_root, journal_path
                )
            assert not manifest_path.exists()
            leftover.unlink()
            manifest_digest = reconcile_account_deletion_journal(
                WorkerSessionFactory, journal_root, journal_path
            )
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        assert hashlib.sha256(manifest_path.read_bytes()).hexdigest() == manifest_digest
        assert manifest["quarantined_raw_collection_count"] == 0
        assert manifest["external_copy_count"] == 0
        items = _server_items(SessionFactory, request_id)
        assert {key: item.state for key, item in items.items()} == {
            "server_originals": "COMPLETED",
            "server_quarantine": "NOT_APPLICABLE",
            "server_copies": "NOT_APPLICABLE",
            "report_records": "COMPLETED",
        }
        assert "re-query" in str(items["server_quarantine"].disposition_basis)
        assert items["server_copies"].disposition_basis == (
            "no institution delivery recorded for the subject's reports at worker preparation"
        )
    finally:
        engine.dispose()


@_PG
def test_s1617_59_unconfirmed_institution_copy_keeps_account_deletion_incomplete(
    tmp_path: Path,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"server-copy.{suffix}"
    request_id = f"delete_server_copy_{suffix}"
    subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
    upload_dir, raw_object_dir, journal_root = _roots(tmp_path)
    _record_consent(SessionFactory, actor_id=actor_id)
    report_id, _storage_name = _seed_report(
        SessionFactory, subject=subject, upload_dir=upload_dir, suffix=suffix
    )
    delivery_ids = (uuid.uuid4(), uuid.uuid4())
    with engine.begin() as connection:
        for revision, delivery_id in enumerate(delivery_ids, start=1):
            # Two manual delivery facts (no package) = two institution copies.
            connection.execute(
                text(
                    "INSERT INTO report_institution_delivery_events "
                    "(id, report_id, review_decision_id, revision, institution, channel, "
                    "recipient, status, reason, observed_at, expected_revision, "
                    "idempotency_key, admin_id, session_id, device_id, correlation_id) "
                    "VALUES (:id, :report_id, :decision, :revision, :institution, 'EMAIL', "
                    "'recipient@example.invalid', 'SUBMITTED', 'manual delivery', "
                    "clock_timestamp(), :expected, :idempotency, 'admin-w25', :session, "
                    "'device-w25', :correlation)"
                ),
                {
                    "id": delivery_id,
                    "report_id": report_id,
                    "decision": uuid.uuid4(),
                    "revision": revision,
                    "institution": f"기관-{revision}",
                    "expected": revision - 1,
                    "idempotency": uuid.uuid4(),
                    "session": uuid.uuid4(),
                    "correlation": uuid.uuid4(),
                },
            )
    accepted = _accept(SessionFactory, actor_id=actor_id, request_id=request_id)
    manifest_path = journal_root / "manifests" / f"{request_id}.json"
    try:
        with _worker_session_factory(engine, suffix) as WorkerSessionFactory:
            manifest_digest = process_account_deletion_request(
                WorkerSessionFactory,
                request_id=request_id,
                upload_dir=upload_dir,
                raw_object_dir=raw_object_dir,
                root=journal_root,
            )
            manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
            assert manifest["external_copy_count"] == 2
            assert manifest["server_inventory"]["server_copies"] == "EXTERNAL_PENDING"
            items = _server_items(SessionFactory, request_id)
            assert items["server_copies"].state == "EXTERNAL_PENDING"
            assert items["server_copies"].evidence_sha256 is None
            assert items["server_copies"].terminal_at is None
            assert {
                items[key].state
                for key in ("server_originals", "report_records")
            } == {"COMPLETED"}
            with SessionFactory() as db:
                events_before = db.scalar(
                    select(func.count(AccountDeletionEvent.operation_id)).where(
                        AccountDeletionEvent.request_id == request_id
                    )
                )
            # Replaying the same manifest is idempotent and adds no event.
            with WorkerSessionFactory() as db:
                replay = complete_server_deletion_inventory_from_manifest(
                    db,
                    request_id=request_id,
                    manifest_sha256=manifest_digest,
                    terminal_at=datetime.now(timezone.utc),
                    quarantined_raw_collection_count=0,
                    external_copy_count=2,
                )
            assert replay.overall_status == "PARTIAL"
            # A replay that claims no copies conflicts instead of closing the item.
            with pytest.raises(PrivacyLifecycleError) as conflict:
                with WorkerSessionFactory() as db:
                    complete_server_deletion_inventory_from_manifest(
                        db,
                        request_id=request_id,
                        manifest_sha256=manifest_digest,
                        terminal_at=datetime.now(timezone.utc),
                        quarantined_raw_collection_count=0,
                        external_copy_count=0,
                    )
            assert (conflict.value.code, conflict.value.status_code) == (
                "account_deletion_server_manifest_conflict",
                409,
            )
            assert _server_items(SessionFactory, request_id)["server_copies"].state == (
                "EXTERNAL_PENDING"
            )
            with SessionFactory() as db:
                assert db.scalar(
                    select(func.count(AccountDeletionEvent.operation_id)).where(
                        AccountDeletionEvent.request_id == request_id
                    )
                ) == events_before
        # Every other item becomes terminal: the unconfirmed copy still blocks.
        with SessionFactory() as db:
            current = record_device_deletion_evidence(
                db,
                payload=_evidence(
                    accepted.status,
                    installation_id=_installation_id(actor_id),
                    evidence_id=f"server-copy-evidence-{suffix}",
                    expected_revision=replay.revision,
                ),
                actor_id=actor_id,
                account_generation=1,
                access_pre_digest="a" * 64,
                tombstone_id=accepted.status.tombstone_id,
                secret=PRIVACY_SECRET,
            )
        remaining = [
            key
            for key in DELETION_ITEM_KEYS
            if key != "device_untransmitted_data" and key not in SERVER_OWNED_ITEM_KEYS
        ]
        for index, key in enumerate(remaining, start=2):
            with SessionFactory() as db:
                current = transition_deletion_item(
                    db,
                    request_id=request_id,
                    item_key=key,
                    operation_id=f"w25-server-copy-{index}-{suffix}",
                    expected_status_revision=current.revision,
                    next_state="COMPLETED",
                    evidence_sha256=f"{index + 1:064x}",
                    terminal_at=datetime.now(timezone.utc),
                )
        states = {item.key: item.status for item in current.items}
        assert states["server_copies"] == "EXTERNAL_PENDING"
        assert all(
            state in {"COMPLETED", "NOT_APPLICABLE"}
            for key, state in states.items()
            if key != "server_copies"
        )
        assert current.overall_status == "PARTIAL"
        assert current.completion_receipt_sha256 is None
        with SessionFactory() as db:
            request = db.get(AccountDeletionRequest, request_id)
            assert request is not None
            assert request.overall_status == "PARTIAL"
            assert request.completion_receipt_sha256 is None
    finally:
        with engine.begin() as connection:
            # Fixture-only cleanup of the seeded append-only delivery facts.
            connection.execute(
                text("ALTER TABLE report_institution_delivery_events DISABLE TRIGGER USER")
            )
            try:
                connection.execute(
                    text("DELETE FROM report_institution_delivery_events WHERE id = ANY(:ids)"),
                    {"ids": list(delivery_ids)},
                )
            finally:
                connection.execute(
                    text("ALTER TABLE report_institution_delivery_events ENABLE TRIGGER USER")
                )
        engine.dispose()
