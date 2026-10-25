"""S1617-57: restoring a pre-deletion backup, then reapplying signed tombstones.

A backup taken before an account deletion is restored into a separate
database: without the tombstone the deleted generation could write again.
The signed off-database ledger exported from the live store is verified,
the missing tombstone is reapplied in one transaction, and afterwards the old
generation's report, RAW and consent writes are refused while other accounts
are unaffected. A ledger whose key fingerprints the restored store does not
hold (key not recovered), a wrong signer, a tampered ledger and a conflicting
restored tombstone all fail closed.
"""

from __future__ import annotations

from dataclasses import replace
import json
import os
from pathlib import Path
from types import SimpleNamespace
import uuid

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import (
    Encoding,
    NoEncryption,
    PrivateFormat,
    PublicFormat,
)
import pytest
from sqlalchemy import create_engine, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import sessionmaker

import backend.app.services.privacy_lifecycle as privacy_lifecycle
from backend.app.models import AccountDeletionTombstone
from backend.app.schemas import (
    AccountDeletionRequestV2,
    PRIVACY_CONSENT_ITEM_VERSIONS,
    PRIVACY_CONSENT_POLICY_VERSION,
)
from backend.app.services.account_tombstone_ledger import (
    AccountTombstoneLedgerError,
    export_account_tombstone_ledger,
    plan_account_tombstone_reapply,
    verify_account_tombstone_ledger,
)
from backend.app.services.privacy_lifecycle import (
    PrivacyLifecycleError,
    accept_account_deletion,
    bind_or_verify_privacy_hmac_key,
    lock_report_ingest_transaction,
    privacy_subject_hmac,
    record_consent_event,
)
from backend.app.services.raw_collection_ingest import authorize_raw_collection_write
from scripts import reapply_account_deletion_tombstones as reapply_cli
from test_privacy_sweep_w23_postgres_integration import _KEY_A, _disposable_database


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _keypair() -> tuple[bytes, bytes]:
    key = Ed25519PrivateKey.generate()
    return (
        key.private_bytes(Encoding.Raw, PrivateFormat.Raw, NoEncryption()),
        key.public_key().public_bytes(Encoding.Raw, PublicFormat.Raw),
    )


def _consent(SessionFactory, actor: str) -> str:
    with SessionFactory() as db:
        return record_consent_event(
            db,
            actor_id=actor,
            account_generation=1,
            installation_id=f"install_{actor.rsplit('.', 1)[-1]}",
            request_id=f"consent_{uuid.uuid4().hex}",
            client_revision=1,
            policy_version=PRIVACY_CONSENT_POLICY_VERSION,
            item_versions=PRIVACY_CONSENT_ITEM_VERSIONS,
            raw_source_collection=True,
            automatic_reporting=True,
            mobile_network_transfer=False,
            training_reuse=False,
            secret=_KEY_A,
            key_version=1,
        ).event.receipt_sha256


def _delete(SessionFactory, actor: str, request_id: str):
    with SessionFactory() as db:
        return accept_account_deletion(
            db,
            payload=AccountDeletionRequestV2(
                schema_version="walksafe.account-deletion-request.v2",
                request_id=request_id,
                client_revision=1,
                confirmation="DELETE_MY_ACCOUNT",
            ),
            actor_id=actor,
            account_generation=1,
            access_pre_digest="a" * 64,
            tombstone_id=None,
            secret=_KEY_A,
            key_version=1,
        )


def _ingest(SessionFactory, actor: str) -> None:
    with SessionFactory() as db:
        try:
            lock_report_ingest_transaction(
                db, privacy_subject_hmac(actor, 1, _KEY_A), 1, enforce_consent=True
            )
        finally:
            db.rollback()


def test_s1617_57_backup_restore_reapplies_signed_account_tombstones(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.setattr(privacy_lifecycle, "_CONFIGURED_LOOKUP_KEYS", ())
    suffix = uuid.uuid4().hex[:12]
    deleted_actor = f"restored-deleted.{suffix}"
    kept_actor = f"restored-kept.{suffix}"
    later_actor = f"restored-later.{suffix}"
    settings = SimpleNamespace(privacy_hmac_secret=_KEY_A, privacy_hmac_key_version=1)
    private_key, public_key = _keypair()
    _other_private, other_public = _keypair()
    configured = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip())
    backup_name = f"walksafe_w25_restore_{uuid.uuid4().hex}"
    backup = configured.set(database=backup_name)
    admin_engine = create_engine(configured, pool_pre_ping=True)
    restored_engine = None
    backup_created = False
    with _disposable_database(monkeypatch) as (engine, SessionFactory):
        try:
            with SessionFactory() as db:
                bind_or_verify_privacy_hmac_key(db, secret=_KEY_A, key_version=1)
                db.commit()
            deleted_receipt = _consent(SessionFactory, deleted_actor)
            _consent(SessionFactory, kept_actor)
            _consent(SessionFactory, later_actor)

            from test_account_deletion_server_inventory_postgres import _seed_report
            from backend.app.models import Report
            from backend.app.services.privacy_visibility import account_subject_visible
            from backend.app.services.admin_report_workflow import _locked_report, AdminReportWorkflowError
            deleted_report, _ = _seed_report(SessionFactory, subject=privacy_subject_hmac(deleted_actor, 1, _KEY_A), upload_dir=tmp_path, suffix="restored_deleted")
            kept_report, _ = _seed_report(SessionFactory, subject=privacy_subject_hmac(kept_actor, 1, _KEY_A), upload_dir=tmp_path, suffix="restored_kept")
            from datetime import UTC, datetime
            from backend.app.models import RawCollection, ReportReviewDecision
            from backend.app.schemas import ReportReviewDecisionRequest, ReportUserRequestCreateV1
            from backend.app.services.report_user_requests import create_report_user_request
            from test_fp008_postgres_integration import _identity
            identity = _identity("restore-reviewer@example.com", "restore-test-device")
            subject = privacy_subject_hmac(deleted_actor, 1, _KEY_A)
            raw_id = uuid.uuid4()
            now = datetime.now(UTC)
            with SessionFactory.begin() as db:
                db.add(RawCollection(
                    collection_id=raw_id, privacy_subject_hmac=subject,
                    account_generation=1, walk_id=uuid.uuid4(), segment_id=uuid.uuid4(),
                    purpose="GENERAL_RAW", lifecycle_version=2,
                    retention_class="RAW_QUARANTINE_14D", state="MANIFEST_ACCEPTED",
                    consent_receipt_sha256=deleted_receipt, manifest_sha256="b" * 64,
                    captured_started_at=now, captured_ended_at=now,
                    object_count=1, chunk_count=1, total_bytes=1,
                ))
            review_payload = ReportReviewDecisionRequest(
                decision_id=uuid.uuid4(), decision="REJECTED", reason="synthetic restore test",
                user_visible_reason="확인된 시험 상태", duplicate_of_report_id=None,
                location_reviewed=True, photo_reviewed=True, privacy_reviewed=True,
            )
            with SessionFactory.begin() as db:
                db.add(ReportReviewDecision(
                    id=review_payload.decision_id, report_id=deleted_report,
                    revision=1, content_revision=0, decision=review_payload.decision,
                    reason=review_payload.reason, user_visible_reason=review_payload.user_visible_reason,
                    location_reviewed=True, photo_reviewed=True, privacy_reviewed=True,
                    admin_id=identity.admin_id, session_id=identity.session_id,
                    device_id=identity.device_id, correlation_id=uuid.uuid4(), decided_at=now,
                ))
            with SessionFactory() as db:
                user_request, _ = create_report_user_request(
                    db, report_id=deleted_report, privacy_subject=subject, account_generation=1,
                    payload=ReportUserRequestCreateV1(client_request_id=uuid.uuid4(),
                        request_type="CORRECTION", request_text="합성 복원 시험"),
                )

            # "Backup" taken before the deletion (template copy of the store).
            engine.dispose()
            source_name = engine.url.database
            with admin_engine.connect().execution_options(
                isolation_level="AUTOCOMMIT"
            ) as connection:
                connection.exec_driver_sql(
                    f'CREATE DATABASE "{backup_name}" TEMPLATE "{source_name}"'
                )
            backup_created = True

            # Live store deletes the account; the ledger is exported off-database.
            _delete(SessionFactory, deleted_actor, f"delete_restore_{suffix}")
            with SessionFactory() as db:
                ledger, signature = export_account_tombstone_ledger(
                    db, private_key=private_key
                )
            (tmp_path / "ledger.json").write_bytes(ledger)
            (tmp_path / "ledger.sig.json").write_bytes(signature)
            (tmp_path / "trusted.key").write_bytes(public_key)
            verified = verify_account_tombstone_ledger(
                ledger, signature, trusted_public_key=public_key
            )
            deleted_subject = privacy_subject_hmac(deleted_actor, 1, _KEY_A)
            assert [entry.privacy_subject_hmac for entry in verified.entries] == [
                deleted_subject
            ]
            assert verified.entries[0].hmac_key_version == 1

            # Wrong signer and a tampered ledger are refused.
            with pytest.raises(AccountTombstoneLedgerError) as wrong_signer:
                verify_account_tombstone_ledger(
                    ledger, signature, trusted_public_key=other_public
                )
            assert wrong_signer.value.code == "account_tombstone_ledger_signature_invalid"
            tampered = ledger.replace(deleted_subject.encode(), b"0" * 64)
            with pytest.raises(AccountTombstoneLedgerError):
                verify_account_tombstone_ledger(
                    tampered, signature, trusted_public_key=public_key
                )

            # The restored backup has no tombstone: the deleted generation
            # could write again (the resurrection this drill closes).
            restored_engine = create_engine(backup, pool_pre_ping=True)
            Restored = sessionmaker(bind=restored_engine, expire_on_commit=False)
            _ingest(Restored, deleted_actor)
            with Restored() as db:
                assert db.scalar(select(AccountDeletionTombstone.tombstone_id).where(
                    AccountDeletionTombstone.privacy_subject_hmac == deleted_subject
                )) is None

            # Key recovery is part of the drill: fingerprints must match.
            unrecovered = replace(verified, key_fingerprints={1: "f" * 64})
            with pytest.raises(AccountTombstoneLedgerError) as key_missing:
                with Restored() as db:
                    plan_account_tombstone_reapply(db, unrecovered)
            assert key_missing.value.code == "account_tombstone_key_recovery_required"

            restored_url = backup.render_as_string(hide_password=False)
            monkeypatch.setenv(reapply_cli.TARGET_DATABASE_ENV, restored_url)
            files = [
                "--trusted-key", str(tmp_path / "trusted.key"),
                "--ledger", str(tmp_path / "ledger.json"),
                "--signature", str(tmp_path / "ledger.sig.json"),
            ]
            assert reapply_cli.main(["plan", *files]) == 0
            planned = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
            assert (planned["missing"], planned["present"], planned["applied"]) == (1, 0, False)
            assert reapply_cli.main(["apply", *files, "--confirm", "yes"]) == 1
            assert "account_tombstone_reapply_confirmation_required" in capsys.readouterr().err
            assert reapply_cli.main(
                ["apply", *files, "--confirm", "REAPPLY-RESTORED-ACCOUNT-TOMBSTONES"]
            ) == 2
            applied = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
            assert (applied["missing"], applied["applied"]) == (1, True)
            assert applied["requires_physical_redeletion"] is True
            assert "consent_events" in applied["retained_data_categories"]
            assert restored_url not in capsys.readouterr().out

            with Restored() as db:
                # Workers can still find restored rows; product reads and mutation locks cannot.
                assert db.get(Report, deleted_report) is not None
                visible = set(db.scalars(select(Report.id).where(account_subject_visible(Report))).all())
                assert deleted_report not in visible and kept_report in visible
                from backend.app.api.reports import _filtered_reports_statement
                from backend.app.services.report_summary_aggregation import build_report_summary_statement
                from backend.app.services.admin_report_workflow import _report_history_snapshot
                from backend.app.models import ReportReviewDecision
                filtered = _filtered_reports_statement(status=None, class_name=None, source=None,
                    demo_filter="all", model_key=None, trigger=None, auto_reported=None,
                    performance_excluded=None, created_from=None, created_to=None,
                    lat=None, lng=None, radius_m=None)
                assert [report.id for report in db.scalars(filtered)] == [kept_report]
                summary = db.execute(build_report_summary_statement(filtered,
                    grid_size_degrees=0.1, top_limit=2)).mappings().one()
                assert summary["total"] == 1
                with pytest.raises(AdminReportWorkflowError) as history_hidden:
                    _report_history_snapshot(db, report_id=deleted_report, history_model=ReportReviewDecision)
                assert history_hidden.value.status_code == 404
                with pytest.raises(AdminReportWorkflowError) as hidden:
                    _locked_report(db, deleted_report)
                assert hidden.value.status_code == 404
            assert "reports" in applied["retained_data_categories"]
            assert "raw_collections" in applied["retained_data_categories"]
            from privacy_restore_consumer_assertions import assert_restored_consumers_hidden
            assert_restored_consumers_hidden(
                monkeypatch, Restored, report_id=deleted_report, kept_report_id=kept_report,
                subject=subject, raw_id=raw_id, user_request_id=user_request.id,
                identity=identity, review_payload=review_payload, upload_root=tmp_path,
            )

            # After reapply the old generation's report, RAW and consent writes fail.
            with pytest.raises(PrivacyLifecycleError) as report_blocked:
                _ingest(Restored, deleted_actor)
            assert report_blocked.value.code == "account_generation_tombstoned"
            with pytest.raises(PrivacyLifecycleError) as raw_blocked:
                with Restored() as db:
                    authorize_raw_collection_write(
                        db,
                        actor_id=deleted_actor,
                        account_generation=1,
                        purpose="GENERAL_RAW",
                        consent_receipt_sha256=deleted_receipt,
                        settings=settings,
                    )
            assert raw_blocked.value.code == "account_generation_tombstoned"
            with pytest.raises(PrivacyLifecycleError) as consent_blocked:
                _consent(Restored, deleted_actor)
            assert consent_blocked.value.code == "account_generation_tombstoned"
            _ingest(Restored, kept_actor)  # unrelated account is unaffected

            # Reapply is idempotent.
            assert reapply_cli.main(
                ["apply", *files, "--confirm", "REAPPLY-RESTORED-ACCOUNT-TOMBSTONES"]
            ) == 2
            again = json.loads(capsys.readouterr().out.strip().splitlines()[-1])
            assert (again["missing"], again["present"]) == (0, 1)

            # A restored tombstone that differs from the ledger is a conflict.
            _delete(SessionFactory, later_actor, f"delete_live_later_{suffix}")
            _delete(Restored, later_actor, f"delete_restored_later_{suffix}")
            with SessionFactory() as db:
                ledger_2, signature_2 = export_account_tombstone_ledger(
                    db, private_key=private_key
                )
            verified_2 = verify_account_tombstone_ledger(
                ledger_2, signature_2, trusted_public_key=public_key
            )
            assert len(verified_2.entries) == 2
            with pytest.raises(AccountTombstoneLedgerError) as conflict:
                with Restored() as db:
                    plan_account_tombstone_reapply(db, verified_2)
            assert conflict.value.code == "account_tombstone_reapply_conflict"
        finally:
            if restored_engine is not None:
                restored_engine.dispose()
            if backup_created:
                with admin_engine.connect().execution_options(
                    isolation_level="AUTOCOMMIT"
                ) as connection:
                    connection.execute(
                        text(
                            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                            "WHERE datname = :name AND pid <> pg_backend_pid()"
                        ),
                        {"name": backup_name},
                    ).all()
                    connection.exec_driver_sql(f'DROP DATABASE IF EXISTS "{backup_name}"')
            admin_engine.dispose()
