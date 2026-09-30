"""S1617-56: receipt purge removes only expired evidence; blocking stays.

The manual CLI (scripts/purge_account_deletion_receipts.py) runs as a
dedicated walksafe_receipt_purger login, deletes expired completion receipts in
bounded committed batches through the existing database function, and never
touches the tombstone or request ledger: an old request of the deleted account
generation is still refused after its receipt is purged.
"""

from __future__ import annotations

from datetime import datetime, timezone
import hashlib
import json
import os
import uuid

import pytest
from sqlalchemy import create_engine, select, text
from sqlalchemy.engine import make_url
from sqlalchemy.orm import sessionmaker

from backend.app.models import AccountDeletionReceipt, AccountDeletionRequest, AccountDeletionTombstone
from backend.app.services.account_deletion_receipt_purge import (
    ReceiptPurgeError,
    purge_expired_account_deletion_receipts,
)
from backend.app.services.privacy_lifecycle import (
    PrivacyLifecycleError,
    accept_account_deletion,
    get_account_deletion_status,
    lock_report_ingest_transaction,
    privacy_subject_hmac,
)
from scripts import purge_account_deletion_receipts as purge_cli
from test_fp046_postgres_integration import (
    PRIVACY_SECRET,
    _accept,
    _request,
    _session_factory,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


def _insert_receipts(engine, subject: str, *, expired: int, active: int) -> tuple[list[str], list[str]]:
    now = datetime.now(timezone.utc)
    expired_processed = now.replace(year=now.year - 4)
    rows: tuple[list[str], list[str]] = ([], [])
    with engine.begin() as connection:
        # Migration-owner fixture: synthetic receipts exercise expiry only.
        connection.execute(
            text(
                "ALTER TABLE account_deletion_receipts DISABLE TRIGGER "
                "account_deletion_receipts_validate_provenance"
            )
        )
        try:
            for bucket, count, processed in (
                (rows[0], expired, expired_processed),
                (rows[1], active, now),
            ):
                for _ in range(count):
                    digest = hashlib.sha256(uuid.uuid4().bytes).hexdigest()
                    connection.execute(
                        text(
                            "INSERT INTO account_deletion_receipts (id, privacy_subject_hmac, "
                            "processed_at, result, expires_at, receipt_sha256) VALUES "
                            "(:id, :subject, :processed, 'COMPLETED', :expires, :digest)"
                        ),
                        {
                            "id": uuid.uuid4(),
                            "subject": subject,
                            "processed": processed,
                            "expires": processed.replace(year=processed.year + 3),
                            "digest": digest,
                        },
                    )
                    bucket.append(digest)
        finally:
            connection.execute(
                text(
                    "ALTER TABLE account_deletion_receipts ENABLE TRIGGER "
                    "account_deletion_receipts_validate_provenance"
                )
            )
    return rows


def test_s1617_56_purge_cli_keeps_tombstone_blocking_and_request_ledger(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"receipt-purge.{suffix}"
    request_id = f"delete_receipt_purge_{suffix}"
    subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
    accepted = _accept(SessionFactory, actor_id=actor_id, request_id=request_id)
    expired, active = _insert_receipts(engine, subject, expired=3, active=1)
    role_name = f"w25_receipt_purger_{suffix}"
    role_password = f"ReceiptPurger{suffix}"
    purger_engine = None
    with engine.begin() as connection:
        connection.execute(
            text(
                f"CREATE ROLE {role_name} LOGIN INHERIT NOSUPERUSER NOCREATEDB "
                f"NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD '{role_password}'"
            )
        )
        connection.execute(
            text(
                f"GRANT walksafe_receipt_purger TO {role_name} "
                "WITH ADMIN FALSE, INHERIT TRUE, SET FALSE"
            )
        )
    try:
        # The migration owner / superuser test connection is refused.
        with pytest.raises(ReceiptPurgeError):
            with SessionFactory() as db:
                purge_expired_account_deletion_receipts(db, batch_limit=2)
        purger_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"]).set(
            username=role_name, password=role_password
        ).render_as_string(hide_password=False)
        monkeypatch.setenv(purge_cli.DATABASE_URL_ENV, purger_url)
        assert purge_cli.main(["--batch-limit", "0"]) == 2
        assert purge_cli.main(["--batch-limit", "2", "--max-batches", "50"]) == 0
        output = capsys.readouterr()
        assert role_password not in output.out + output.err
        result = json.loads(output.out.strip().splitlines()[-1])
        assert result["schema_version"] == "walksafe.account-deletion-receipt-purge.v1"
        assert result["exhausted"] is True
        assert result["deleted"] >= 3
        assert result["batches"] >= 2
        with SessionFactory() as db:
            remaining = set(
                db.scalars(
                    select(AccountDeletionReceipt.receipt_sha256).where(
                        AccountDeletionReceipt.receipt_sha256.in_(expired + active)
                    )
                ).all()
            )
        assert remaining == set(active)

        # A second run finds nothing expired.
        purger_engine = create_engine(purger_url, pool_pre_ping=True)
        with sessionmaker(bind=purger_engine)() as purger_db:
            again = purge_expired_account_deletion_receipts(purger_db, batch_limit=2)
        assert again.deleted == 0 and again.batches == 1 and again.exhausted

        # Blocking and the request ledger survive the receipt purge.
        with SessionFactory() as db:
            assert db.scalar(
                select(AccountDeletionTombstone).where(
                    AccountDeletionTombstone.privacy_subject_hmac == subject,
                    AccountDeletionTombstone.account_generation == 1,
                )
            ) is not None
            assert db.get(AccountDeletionRequest, request_id) is not None
            with pytest.raises(PrivacyLifecycleError) as ingest_blocked:
                lock_report_ingest_transaction(db, subject, 1, enforce_consent=False)
            assert ingest_blocked.value.code == "account_generation_tombstoned"
            db.rollback()
        with pytest.raises(PrivacyLifecycleError) as reaccept:
            with SessionFactory() as db:
                accept_account_deletion(
                    db,
                    payload=_request(f"delete_receipt_purge_again_{suffix}"),
                    actor_id=actor_id,
                    account_generation=1,
                    access_pre_digest="a" * 64,
                    tombstone_id=None,
                    secret=PRIVACY_SECRET,
                )
        assert reaccept.value.code == "account_generation_tombstoned"
        with SessionFactory() as db:
            status = get_account_deletion_status(
                db,
                request_id=request_id,
                actor_id=actor_id,
                account_generation=1,
                access_pre_digest="a" * 64,
                tombstone_id=accepted.status.tombstone_id,
                secret=PRIVACY_SECRET,
            )
        assert status.request_id == request_id
    finally:
        if purger_engine is not None:
            purger_engine.dispose()
        with engine.begin() as connection:
            connection.execute(text(f"REVOKE walksafe_receipt_purger FROM {role_name}"))
            connection.execute(text(f"DROP ROLE {role_name}"))
            connection.execute(
                text("ALTER TABLE account_deletion_receipts DISABLE TRIGGER USER")
            )
            try:
                connection.execute(
                    text(
                        "DELETE FROM account_deletion_receipts "
                        "WHERE receipt_sha256 = ANY(:digests)"
                    ),
                    {"digests": list(active)},
                )
            finally:
                connection.execute(
                    text("ALTER TABLE account_deletion_receipts ENABLE TRIGGER USER")
                )
        engine.dispose()
