"""S21-44: upgrade from the supported predecessor schema with representative data.

``SUPPORTED_PREDECESSOR_REVISION`` is the most recent stable point in the head's
down_revision chain: the Alembic head of the last committed release tree. There
is no production deployment yet (release NOT_ELIGIBLE); after the first
production deployment replace it with that deployed revision.

A disposable database is migrated to the predecessor, seeded with an account, a
report, a pending report deletion request and an in-progress account deletion,
then upgraded to head. The run checks row preservation, that constraints still
hold, and that an interrupted upgrade rolls back to the predecessor and can be
re-run to head.
"""
from __future__ import annotations

import os
from pathlib import Path
import re
import time
import uuid

from alembic import command
from alembic.config import Config
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import DBAPIError, SQLAlchemyError

from backend.app.api.health import EXPECTED_ALEMBIC_HEAD

pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)

SUPPORTED_PREDECESSOR_REVISION = "202609010003"
# Disposable databases carry their creation time so a killed run (the fixture's
# finally never ran) can be cleaned up later without touching a concurrent run.
_DISPOSABLE_PREFIX = "walksafe_predecessor_upgrade_test_"
_DISPOSABLE_NAME = re.compile(rf"^{_DISPOSABLE_PREFIX}(?P<created>[0-9]{{10}})_[0-9a-f]{{16}}$")
_STALE_DISPOSABLE_SECONDS = 6 * 3600
# A later migration creates this table; a stray copy interrupts the upgrade.
_INTERRUPTING_TABLE = "report_institution_delivery_corrections"

_ACCOUNT_ID = uuid.UUID("5a4e0000-0000-4000-8000-000000000001")
_ACTOR_ID = "5a4e0000-0000-4000-8000-0000000000aa"
_REPORT_ID = uuid.UUID("5a4e0000-0000-4000-8000-000000000002")
_USER_REQUEST_ID = uuid.UUID("5a4e0000-0000-4000-8000-000000000003")
_TOMBSTONE_ID = uuid.UUID("5a4e0000-0000-4000-8000-000000000004")
_DELETION_REQUEST_ID = "w25_predecessor_delete_0001"
_ACCEPTED_AT = "2026-09-01T00:00:00+00:00"
_DELETION_ITEMS = (
    ("device_untransmitted_data", "NOT_APPLICABLE", 24),
    ("server_originals", "PENDING", 168),
    ("server_quarantine", "PENDING", 168),
    ("server_copies", "PENDING", 168),
    ("report_records", "PENDING", 168),
    ("training_datasets", "EXTERNAL_PENDING", 720),
    ("training_labels", "EXTERNAL_PENDING", 720),
    ("derived_artifacts", "EXTERNAL_PENDING", 720),
    ("backups", "EXTERNAL_PENDING", 840),
)

# Predecessor columns only; later migrations may add columns with defaults.
_SNAPSHOTS = {
    "user_accounts": (
        "SELECT id, actor_id, privacy_subject_hmac, email_lookup_hmac, email_ciphertext, "
        "email_nonce, email_key_version, password_hash, status, account_generation, "
        "auth_epoch, created_at, updated_at FROM public.user_accounts WHERE id = :id",
        {"id": _ACCOUNT_ID},
    ),
    "reports": (
        "SELECT id, class_id, class_name, confidence, bbox_x, bbox_y, bbox_width, "
        "bbox_height, captured_at, source, image_path, image_content_type, metadata "
        "FROM public.reports WHERE id = :id",
        {"id": _REPORT_ID},
    ),
    "report_user_requests": (
        "SELECT id, report_id, client_request_id, request_type, request_text, "
        "intent_sha256, status, status_version, public_response, internal_note, "
        "created_at, updated_at, discovery_revision "
        "FROM public.report_user_requests WHERE id = :id",
        {"id": _USER_REQUEST_ID},
    ),
    "account_deletion_tombstones": (
        "SELECT tombstone_id, privacy_subject_hmac, account_generation, "
        "request_receipt_sha256, tombstoned_at "
        "FROM public.account_deletion_tombstones WHERE tombstone_id = :id",
        {"id": _TOMBSTONE_ID},
    ),
    "account_deletion_events": (
        "SELECT id, request_id, operation_id, event_type, status_revision, "
        "operation_sha256, evidence_sha256, recorded_at "
        "FROM public.account_deletion_events WHERE request_id = :id",
        {"id": _DELETION_REQUEST_ID},
    ),
    "report_user_request_status_events": (
        "SELECT id, request_id, previous_status, next_status, previous_version, "
        "next_version, actor_kind, privacy_subject_hmac, account_generation, created_at "
        "FROM public.report_user_request_status_events WHERE request_id = :id",
        {"id": _USER_REQUEST_ID},
    ),
    "account_deletion_items": (
        "SELECT request_id, item_key, state, item_revision, due_at, updated_at, "
        "evidence_sha256, disposition_basis, terminal_at FROM public.account_deletion_items "
        "WHERE request_id = :id ORDER BY item_key",
        {"id": _DELETION_REQUEST_ID},
    ),
    "account_deletion_requests": (
        "SELECT request_id, tombstone_id, privacy_subject_hmac, account_generation, "
        "request_body_sha256, request_receipt_sha256, access_secret_digest, "
        "client_revision, status_revision, overall_status, completion_receipt_sha256, "
        "accepted_at, updated_at, credential_account_id "
        "FROM public.account_deletion_requests WHERE request_id = :id",
        {"id": _DELETION_REQUEST_ID},
    ),
}


def _seed(connection) -> None:
    connection.execute(text(
        "INSERT INTO public.user_accounts (id, actor_id, privacy_subject_hmac, "
        "email_lookup_hmac, email_ciphertext, email_nonce, email_key_version, "
        "password_hash, status, account_generation, auth_epoch) VALUES ("
        ":id, :actor, :subject, :lookup, :ciphertext, :nonce, 1, :password, "
        "'ACTIVE', 1, 1)"
    ), {
        "id": _ACCOUNT_ID, "actor": _ACTOR_ID, "subject": "a" * 64, "lookup": "b" * 64,
        "ciphertext": b"\x01" * 32, "nonce": b"\x02" * 12,
        "password": "scrypt$16384$8$1$" + "p" * 40,
    })
    connection.execute(text(
        "INSERT INTO public.reports (id, class_id, class_name, confidence, bbox_x, "
        "bbox_y, bbox_width, bbox_height, captured_at, source, image_path, "
        "image_content_type, metadata) VALUES (:id, 0, 'damaged_tactile_block', 0.9, "
        "0.1, 0.1, 0.5, 0.5, '2026-09-01T00:00:00Z'::timestamptz, 'android', :path, "
        "'image/jpeg', CAST(:metadata AS jsonb))"
    ), {
        "id": _REPORT_ID, "path": f"/uploads/{_REPORT_ID}.jpg",
        "metadata": '{"source_commit": "' + "d" * 40 + '", "trigger": "voice"}',
    })
    connection.execute(text(
        "INSERT INTO public.report_user_requests (id, report_id, client_request_id, "
        "request_type, request_text, intent_sha256, status, status_version) VALUES ("
        ":id, :report, :client, 'DELETE', '신고 삭제를 요청합니다', :intent, 'RECEIVED', 1)"
    ), {
        "id": _USER_REQUEST_ID, "report": _REPORT_ID,
        "client": uuid.UUID("5a4e0000-0000-4000-8000-000000000005"), "intent": "c" * 64,
    })
    # The deferred projection guard requires the matching creation event.
    connection.execute(text(
        "INSERT INTO public.report_user_request_status_events (id, request_id, "
        "previous_status, next_status, previous_version, next_version, actor_kind, "
        "privacy_subject_hmac, account_generation) VALUES (:id, :request, NULL, "
        "'RECEIVED', 0, 1, 'FIELD', :subject, 1)"
    ), {
        "id": uuid.UUID("5a4e0000-0000-4000-8000-000000000006"),
        "request": _USER_REQUEST_ID, "subject": "a" * 64,
    })
    connection.execute(text(
        "INSERT INTO public.account_deletion_tombstones (tombstone_id, "
        "privacy_subject_hmac, account_generation, request_receipt_sha256, tombstoned_at) "
        "VALUES (:id, :subject, 1, :receipt, '2026-09-01T00:00:00Z'::timestamptz)"
    ), {"id": _TOMBSTONE_ID, "subject": "a" * 64, "receipt": "e" * 64})
    connection.execute(text(
        "INSERT INTO public.account_deletion_requests (request_id, tombstone_id, "
        "privacy_subject_hmac, account_generation, request_body_sha256, "
        "request_receipt_sha256, access_secret_digest, client_revision, status_revision, "
        "overall_status, completion_receipt_sha256, accepted_at, updated_at, "
        "credential_account_id) VALUES ("
        ":id, :tombstone, :subject, 1, :body, :receipt, :secret, 1, 1, 'PARTIAL', "
        "NULL, CAST(:accepted AS timestamptz), CAST(:accepted AS timestamptz), :account)"
    ), {
        "id": _DELETION_REQUEST_ID, "tombstone": _TOMBSTONE_ID, "subject": "a" * 64,
        "body": "f" * 64, "receipt": "e" * 64, "secret": "1" * 64,
        "accepted": _ACCEPTED_AT, "account": _ACCOUNT_ID,
    })
    # Acceptance inventory of an account-only request: nine items, no device.
    for item_key, state, hours in _DELETION_ITEMS:
        not_applicable = state == "NOT_APPLICABLE"
        connection.execute(text(
            "INSERT INTO public.account_deletion_items (request_id, item_key, state, "
            "item_revision, due_at, updated_at, evidence_sha256, disposition_basis, "
            "terminal_at) VALUES (:request, :item, :state, 1, "
            "CAST(:accepted AS timestamptz) + make_interval(hours => :hours), "
            "CAST(:accepted AS timestamptz), :evidence, :basis, :terminal)"
        ), {
            "request": _DELETION_REQUEST_ID, "item": item_key, "state": state,
            "accepted": _ACCEPTED_AT, "hours": hours,
            "evidence": "8" * 64 if not_applicable else None,
            "basis": "no_synchronized_installations_at_acceptance" if not_applicable else None,
            "terminal": _ACCEPTED_AT if not_applicable else None,
        })
    # The deferred ledger guard requires the acceptance event of the request.
    connection.execute(text(
        "INSERT INTO public.account_deletion_events (id, request_id, operation_id, "
        "event_type, status_revision, operation_sha256, evidence_sha256, recorded_at) "
        "VALUES (:id, :request, 'request-accepted', 'REQUEST_ACCEPTED', 1, :body, "
        ":receipt, CAST(:accepted AS timestamptz))"
    ), {
        "id": uuid.UUID("5a4e0000-0000-4000-8000-000000000007"),
        "request": _DELETION_REQUEST_ID, "body": "f" * 64, "receipt": "e" * 64,
        "accepted": _ACCEPTED_AT,
    })


def _snapshot(engine) -> dict[str, tuple[tuple[object, ...], ...]]:
    with engine.connect() as connection:
        snapshot = {
            name: tuple(tuple(row) for row in connection.execute(text(sql), parameters).all())
            for name, (sql, parameters) in _SNAPSHOTS.items()
        }
    assert all(snapshot.values()), "every seeded table must return its rows"
    return snapshot


def _revision(engine) -> str:
    with engine.connect() as connection:
        return connection.execute(text("SELECT version_num FROM public.alembic_version")).scalar_one()


def _disposable_database_name(now: float) -> str:
    return f"{_DISPOSABLE_PREFIX}{int(now):010d}_{uuid.uuid4().hex[:16]}"


def _drop_stale_disposable_databases(connection, *, now: float) -> list[str]:
    """Drop timestamped leftovers older than 6 h; never FORCE, never other names."""
    names = connection.execute(
        text("SELECT datname FROM pg_database WHERE starts_with(datname, :prefix)"),
        {"prefix": _DISPOSABLE_PREFIX},
    ).scalars().all()
    dropped = []
    quote = connection.dialect.identifier_preparer.quote
    for name in sorted(names):
        matched = _DISPOSABLE_NAME.fullmatch(name)
        if matched is None or now - int(matched.group("created")) < _STALE_DISPOSABLE_SECONDS:
            continue
        try:
            # Without FORCE a database that still has sessions is left alone.
            connection.exec_driver_sql(f"DROP DATABASE IF EXISTS {quote(name)}")
        except DBAPIError:
            continue
        dropped.append(name)
    return dropped


def test_s21_44_stale_cleanup_only_drops_old_timestamped_disposable_databases() -> None:
    now = 2_000_000_000.0
    fresh = _disposable_database_name(now - 60)
    stale = _disposable_database_name(now - _STALE_DISPOSABLE_SECONDS - 1)
    legacy = f"{_DISPOSABLE_PREFIX}{uuid.uuid4().hex}"  # pre-timestamp format
    names = [fresh, stale, legacy, "walksafe_sweep_test_other", f"{_DISPOSABLE_PREFIX}x"]
    assert len(stale) <= 63 and _DISPOSABLE_NAME.fullmatch(stale)

    class Connection:
        dialect = create_engine("postgresql+psycopg://").dialect

        def __init__(self) -> None:
            self.statements: list[str] = []

        def execute(self, statement, parameters):
            assert parameters == {"prefix": _DISPOSABLE_PREFIX}
            return SimpleResult([name for name in names if name.startswith(_DISPOSABLE_PREFIX)])

        def exec_driver_sql(self, statement):
            self.statements.append(statement)

    class SimpleResult:
        def __init__(self, rows):
            self._rows = rows

        def scalars(self):
            return self

        def all(self):
            return self._rows

    connection = Connection()
    assert _drop_stale_disposable_databases(connection, now=now) == [stale]
    assert connection.statements == [f"DROP DATABASE IF EXISTS {stale}"]


@pytest.fixture(scope="module")
def upgraded_predecessor():
    configured_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip())
    now = time.time()
    database_name = _disposable_database_name(now)
    disposable_url = configured_url.set(database=database_name)
    migration_url = disposable_url.render_as_string(hide_password=False)
    admin_engine = create_engine(configured_url, pool_pre_ping=True)
    disposable_engine = create_engine(disposable_url, pool_pre_ping=True)
    quoted = admin_engine.dialect.identifier_preparer.quote(database_name)
    observed: dict[str, object] = {}
    created = False
    with pytest.MonkeyPatch.context() as patch:
        patch.setenv("DATABASE_URL", migration_url)
        patch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", migration_url)
        config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
        try:
            with admin_engine.connect().execution_options(isolation_level="AUTOCOMMIT") as connection:
                _drop_stale_disposable_databases(connection, now=now)
                connection.exec_driver_sql(f"CREATE DATABASE {quoted}")
                created = True
            command.upgrade(config, SUPPORTED_PREDECESSOR_REVISION)
            with disposable_engine.begin() as connection:
                _seed(connection)
            observed["predecessor"] = _snapshot(disposable_engine)

            # Interrupt: a later migration fails part-way through the run.
            with disposable_engine.begin() as connection:
                connection.exec_driver_sql(f"CREATE TABLE public.{_INTERRUPTING_TABLE} (id integer)")
            try:
                command.upgrade(config, "head")
            except SQLAlchemyError as exc:
                observed["interrupted_error"] = type(exc).__name__
            else:
                observed["interrupted_error"] = None
            observed["interrupted_revision"] = _revision(disposable_engine)
            observed["interrupted"] = _snapshot(disposable_engine)

            # Recover: remove the obstacle and run the same upgrade again.
            with disposable_engine.begin() as connection:
                connection.exec_driver_sql(f"DROP TABLE public.{_INTERRUPTING_TABLE}")
            command.upgrade(config, "head")
            observed["head_revision"] = _revision(disposable_engine)
            observed["head"] = _snapshot(disposable_engine)
            yield observed, disposable_engine
        finally:
            disposable_engine.dispose()
            if created:
                with admin_engine.connect().execution_options(isolation_level="AUTOCOMMIT") as connection:
                    connection.exec_driver_sql(f"DROP DATABASE IF EXISTS {quoted} WITH (FORCE)")
            admin_engine.dispose()


def test_s21_44_interrupted_upgrade_rolls_back_to_the_supported_predecessor(
    upgraded_predecessor,
) -> None:
    observed, _engine = upgraded_predecessor
    assert observed["interrupted_error"] is not None
    assert observed["interrupted_revision"] == SUPPORTED_PREDECESSOR_REVISION
    assert observed["interrupted"] == observed["predecessor"]


def test_s21_44_recovered_upgrade_reaches_head_and_preserves_rows(upgraded_predecessor) -> None:
    observed, _engine = upgraded_predecessor
    assert observed["head_revision"] == EXPECTED_ALEMBIC_HEAD
    assert observed["head"] == observed["predecessor"]
    (deletion,) = observed["head"]["account_deletion_requests"]
    assert deletion[9] == "PARTIAL" and deletion[10] is None
    assert len(observed["head"]["account_deletion_items"]) == len(_DELETION_ITEMS)


@pytest.mark.parametrize(
    ("statement", "parameters"),
    [
        (  # report deletion request must still reference an existing report
            "INSERT INTO public.report_user_requests (id, report_id, client_request_id, "
            "request_type, request_text, intent_sha256, status, status_version) VALUES "
            "(gen_random_uuid(), gen_random_uuid(), gen_random_uuid(), 'DELETE', 'x', :digest, "
            "'RECEIVED', 1)",
            {"digest": "c" * 64},
        ),
        ("UPDATE public.user_accounts SET status = 'BOGUS' WHERE id = :id", {"id": _ACCOUNT_ID}),
        (  # the account lookup key stays unique
            "INSERT INTO public.user_accounts (id, actor_id, privacy_subject_hmac, "
            "email_lookup_hmac, email_ciphertext, email_nonce, email_key_version, "
            "password_hash, status, account_generation, auth_epoch) SELECT gen_random_uuid(), "
            "'5a4e0000-0000-4000-8000-0000000000bb', :subject, email_lookup_hmac, "
            "email_ciphertext, email_nonce, 1, password_hash, 'ACTIVE', 1, 1 "
            "FROM public.user_accounts WHERE id = :id",
            {"subject": "9" * 64, "id": _ACCOUNT_ID},
        ),
        (  # completion still requires its receipt
            "UPDATE public.account_deletion_requests SET overall_status = 'COMPLETED' "
            "WHERE request_id = :id",
            {"id": _DELETION_REQUEST_ID},
        ),
        (  # the in-progress request stays bound to its tombstone
            "DELETE FROM public.account_deletion_tombstones WHERE tombstone_id = :id",
            {"id": _TOMBSTONE_ID},
        ),
        (  # the deletion ledger stays append-only
            "UPDATE public.account_deletion_events SET recorded_at = recorded_at "
            "WHERE request_id = :id",
            {"id": _DELETION_REQUEST_ID},
        ),
        (  # a report request transition still needs its status event
            "UPDATE public.report_user_requests SET status = 'ACKNOWLEDGED', "
            "status_version = 2 WHERE id = :id",
            {"id": _USER_REQUEST_ID},
        ),
    ],
    ids=["report-fk", "account-status-check", "account-lookup-unique",
         "deletion-completion-receipt", "deletion-tombstone-fk",
         "deletion-ledger-append-only", "report-request-transition-event"],
)
def test_s21_44_constraints_hold_on_upgraded_data(
    upgraded_predecessor, statement: str, parameters: dict[str, object],
) -> None:
    _observed, engine = upgraded_predecessor
    with pytest.raises(DBAPIError):
        with engine.begin() as connection:
            connection.execute(text(statement), parameters)
    assert _snapshot(engine) == _observed["predecessor"]
