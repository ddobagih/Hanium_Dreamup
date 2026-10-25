"""w23 privacy sweep: device verification view, server-clock evidence, HMAC keyring.

S1617-51: an installation that does not answer by the 24-hour device deadline is
shown as unverifiable, never synthesized as NOT_FOUND, and later evidence from
the same installation still completes the request.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
import os
import uuid

import pytest
from sqlalchemy import select, text

from asgi_client import ASGITestClient
from backend.app.main import app
from backend.app.models import AccountDeletionDeviceTarget, AccountDeletionEvent
from backend.app.schemas import (
    AccountDeletionStatusV2,
    AccountDeletionStatusWithDeviceVerificationV2,
)
import backend.app.services.privacy_lifecycle as privacy_lifecycle
from backend.app.services.privacy_lifecycle import (
    DELETION_ITEM_KEYS,
    SERVER_OWNED_ITEM_KEYS,
    complete_server_deletion_inventory_from_manifest,
    get_account_deletion_status,
    record_device_deletion_evidence,
    transition_deletion_item,
)
from test_fp046_postgres_integration import (
    PRIVACY_SECRET,
    _accept,
    _evidence,
    _record_consent,
    _session_factory,
    _worker_session_factory,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)

_VIEW_HEADER = "x-walksafe-deletion-status-view"
_VIEW = "device-verification.v1"


def _status_view(SessionFactory, *, status, actor_id: str, pre_digest: str = "a" * 64):
    with SessionFactory() as db:
        return get_account_deletion_status(
            db,
            request_id=status.request_id,
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest=pre_digest,
            tombstone_id=status.tombstone_id,
            secret=PRIVACY_SECRET,
            include_device_verification=True,
        )


def _finish_server_side_items(engine, SessionFactory, current, suffix: str):
    current = _finish_server_manifest(engine, current, suffix)
    return _finish_external_items(SessionFactory, current, suffix)


def _finish_server_manifest(engine, current, suffix: str):
    with _worker_session_factory(engine, suffix) as WorkerSessionFactory:
        with WorkerSessionFactory() as db:
            return complete_server_deletion_inventory_from_manifest(
                db,
                request_id=current.request_id,
                manifest_sha256="1" * 64,
                terminal_at=datetime.now(timezone.utc),
                quarantined_raw_collection_count=0,
                external_copy_count=0,
            )


def _finish_external_items(SessionFactory, current, suffix: str):
    remaining = [
        key
        for key in DELETION_ITEM_KEYS
        if key != "device_untransmitted_data" and key not in SERVER_OWNED_ITEM_KEYS
    ]
    for index, key in enumerate(remaining, start=2):
        with SessionFactory() as db:
            current = transition_deletion_item(
                db,
                request_id=current.request_id,
                item_key=key,
                operation_id=f"w23-server-{index}-{suffix}",
                expected_status_revision=current.revision,
                next_state="COMPLETED",
                evidence_sha256=f"{index + 1:064x}",
                terminal_at=datetime.now(timezone.utc),
            )
    return current


def test_s1617_51_unanswered_device_after_sla_is_unverifiable_not_completed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"unverifiable.{suffix}"
    answered = f"install_answered_{suffix}"
    silent = f"install_silent_{suffix}"
    _record_consent(SessionFactory, actor_id=actor_id, installation_id=answered)
    _record_consent(SessionFactory, actor_id=actor_id, installation_id=silent)
    accepted = _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=f"delete_unverifiable_{suffix}",
    )
    with SessionFactory() as db:
        current = record_device_deletion_evidence(
            db,
            payload=_evidence(
                accepted.status,
                installation_id=answered,
                evidence_id=f"w23-answered-{suffix}",
            ),
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest="a" * 64,
            tombstone_id=accepted.status.tombstone_id,
            secret=PRIVACY_SECRET,
        )
    current = _finish_server_side_items(engine, SessionFactory, current, suffix)
    assert current.overall_status == "PARTIAL"

    before_sla = _status_view(SessionFactory, status=current, actor_id=actor_id)
    assert isinstance(before_sla, AccountDeletionStatusWithDeviceVerificationV2)
    view = before_sla.device_verification
    assert view.completion_view == "IN_PROGRESS"
    assert (view.target_count, view.deleted_count, view.pending_count) == (2, 1, 1)
    assert view.unverifiable_count == 0 and view.unverifiable_reason is None

    real_now = privacy_lifecycle._device_verification_now
    monkeypatch.setattr(
        privacy_lifecycle,
        "_device_verification_now",
        lambda db: real_now(db) + timedelta(hours=25),
    )
    after_sla = _status_view(SessionFactory, status=current, actor_id=actor_id)
    view = after_sla.device_verification
    assert after_sla.overall_status == "PARTIAL"
    assert after_sla.completion_receipt_sha256 is None
    assert after_sla.items[0].status == "EXTERNAL_PENDING"
    assert view.completion_view == "SERVER_COMPLETED_DEVICE_UNVERIFIED"
    assert view.unverifiable_count == 1
    assert view.pending_count == 0
    assert view.unverifiable_reason == "SLA_ELAPSED_NO_CONTACT"
    assert view.follow_up == "DELETE_ON_NEXT_CONTACT_OR_REMOVE_APP"
    assert view.not_found_count == 0
    # Reading the view never writes: no NOT_FOUND is synthesized for the silent device.
    with SessionFactory() as db:
        target_states = sorted(
            db.scalars(
                select(AccountDeletionDeviceTarget.state).where(
                    AccountDeletionDeviceTarget.request_id == current.request_id
                )
            ).all()
        )
        not_found_events = db.scalars(
            select(AccountDeletionEvent.id).where(
                AccountDeletionEvent.request_id == current.request_id,
                AccountDeletionEvent.result == "NOT_FOUND",
            )
        ).all()
    assert target_states == ["COMPLETED", "PENDING"]
    assert not_found_events == []

    # The same installation reconnects later and proves deletion: request completes.
    with SessionFactory() as db:
        completed = record_device_deletion_evidence(
            db,
            payload=_evidence(
                current,
                installation_id=silent,
                evidence_id=f"w23-silent-late-{suffix}",
            ),
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest="a" * 64,
            tombstone_id=current.tombstone_id,
            secret=PRIVACY_SECRET,
        )
    assert completed.items[0].status == "COMPLETED"
    assert completed.overall_status == "COMPLETED"
    final = _status_view(SessionFactory, status=completed, actor_id=actor_id)
    assert final.device_verification.completion_view == "COMPLETED"
    assert final.device_verification.deleted_count == 2
    assert final.device_verification.unverifiable_count == 0
    engine.dispose()


def test_s1617_51_status_view_is_opt_in_and_base_status_bytes_are_unchanged() -> None:
    engine, SessionFactory = _session_factory()
    client = ASGITestClient(app)
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"viewapi.{suffix}"
    request_id = f"delete_view_api_{suffix}"
    accepted = _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=request_id,
        pre_digest="e" * 64,
    )
    headers = {
        "x-walksafe-actor-id": actor_id,
        "x-walksafe-account-generation": "1",
        "x-walksafe-deletion-access-pre-digest": "e" * 64,
        "x-walksafe-deletion-tombstone-id": accepted.status.tombstone_id,
    }
    path = f"/privacy/account-deletions/{request_id}/status"
    base = client.get(path, headers=headers)
    assert base.status_code == 200, base.text
    assert "device_verification" not in base.json()
    AccountDeletionStatusV2.model_validate(base.json())

    viewed = client.get(path, headers={**headers, _VIEW_HEADER: _VIEW})
    assert viewed.status_code == 200, viewed.text
    assert viewed.headers["cache-control"] == "no-store"
    body = viewed.json()
    verification = body.pop("device_verification")
    assert body == base.json()
    assert verification["completion_view"] == "IN_PROGRESS"
    assert verification["target_count"] == 1
    assert verification["pending_count"] == 1

    rejected = client.get(path, headers={**headers, _VIEW_HEADER: "device-verification.v9"})
    assert rejected.status_code == 422
    assert rejected.json()["detail"] == {"code": "account_deletion_status_view_invalid"}
    engine.dispose()


@pytest.mark.parametrize(
    ("label", "skew"),
    (
        ("behind", -timedelta(hours=1)),
        ("ahead", timedelta(hours=1)),
        ("accurate", timedelta(0)),
    ),
)
def test_s1617_53_device_clock_skew_keeps_evidence_with_server_terminal_time(
    label: str,
    skew: timedelta,
) -> None:
    engine, SessionFactory = _session_factory()
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"skew{label}.{suffix}"
    installation = f"install_skew_{suffix}"
    _record_consent(SessionFactory, actor_id=actor_id, installation_id=installation)
    accepted = _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=f"delete_skew_{label}_{suffix}",
    )
    evidence = _evidence(
        accepted.status,
        installation_id=installation,
        evidence_id=f"w23-skew-{label}-{suffix}",
    )
    observed = (
        datetime.now(timezone.utc).replace(microsecond=0) + skew
        if skew
        else max(
            accepted.status.accepted_at,
            datetime.now(timezone.utc).replace(microsecond=0),
        )
    )
    evidence = evidence.model_copy(update={"completed_at": observed})
    evidence = evidence.model_copy(
        update={"evidence_sha256": privacy_lifecycle.deletion_evidence_sha256(evidence)}
    )
    with SessionFactory() as db:
        status = record_device_deletion_evidence(
            db,
            payload=evidence,
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest="a" * 64,
            tombstone_id=accepted.status.tombstone_id,
            secret=PRIVACY_SECRET,
        )
    assert status.items[0].status == "COMPLETED"
    with SessionFactory() as db:
        event = db.scalar(
            select(AccountDeletionEvent).where(
                AccountDeletionEvent.request_id == status.request_id,
                AccountDeletionEvent.operation_id == evidence.evidence_id,
            )
        )
        target = db.scalar(
            select(AccountDeletionDeviceTarget).where(
                AccountDeletionDeviceTarget.request_id == status.request_id
            )
        )
        assert event is not None and target is not None
        # Server receipt time is authoritative; the device time is kept verbatim.
        assert event.terminal_at == event.recorded_at
        assert target.terminal_at == event.recorded_at
        assert status.items[0].terminal_at == event.recorded_at
        assert event.device_observed_completed_at == observed
        assert event.device_clock_anomaly is (label != "accurate")
    view = _status_view(SessionFactory, status=status, actor_id=actor_id)
    assert view.device_verification.device_clock_anomaly_count == (
        0 if label == "accurate" else 1
    )
    # Lost-response replay of the exact same evidence is still idempotent.
    with SessionFactory() as db:
        replay = record_device_deletion_evidence(
            db,
            payload=evidence,
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest="a" * 64,
            tombstone_id=accepted.status.tombstone_id,
            secret=PRIVACY_SECRET,
        )
    assert replay.revision == status.revision
    engine.dispose()


def test_s1617_53_database_rejects_device_time_as_terminal_time() -> None:
    """New evidence rows must carry server time as terminal_at (DB CHECK)."""

    engine, SessionFactory = _session_factory()
    with engine.connect() as connection:
        definition = connection.execute(
            text(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                "WHERE conname = 'ck_account_deletion_event_device_observed_time'"
            )
        ).scalar_one()
    assert "terminal_at = recorded_at" in definition
    assert "DEVICE_EVIDENCE" in definition

    # S1617-53-F1: the CHECK binds terminal_at = recorded_at only on rows that
    # record the device observation; a violating INSERT is refused. Triggers are
    # disabled for this transaction (replica mode, rolled back) so the CHECK is
    # the only guard exercised.
    from sqlalchemy.exc import IntegrityError

    suffix = uuid.uuid4().hex[:12]
    actor_id = f"checkonly.{suffix}"
    installation = f"install_checkonly_{suffix}"
    _record_consent(SessionFactory, actor_id=actor_id, installation_id=installation)
    accepted = _accept(
        SessionFactory,
        actor_id=actor_id,
        request_id=f"delete_checkonly_{suffix}",
    )
    with SessionFactory() as db:
        target_hmac = db.scalar(
            select(AccountDeletionDeviceTarget.installation_subject_hmac).where(
                AccountDeletionDeviceTarget.request_id == accepted.status.request_id
            )
        )
    assert target_hmac is not None
    insert_event = text(
        "INSERT INTO account_deletion_events ("
        "id, request_id, operation_id, event_type, item_key, previous_state, "
        "next_state, status_revision, operation_sha256, evidence_sha256, "
        "terminal_at, installation_subject_hmac, result, target_state, recorded_at, "
        "device_observed_completed_at, device_clock_anomaly"
        ") VALUES ("
        "gen_random_uuid(), :request_id, :operation_id, 'DEVICE_EVIDENCE', "
        "'device_untransmitted_data', 'EXTERNAL_PENDING', 'COMPLETED', :revision, "
        "repeat('a', 64), repeat('b', 64), :terminal_at, :target, 'DELETED', "
        "'COMPLETED', :recorded_at, :observed, :anomaly)"
    )
    recorded_at = datetime.now(timezone.utc)
    earlier = recorded_at - timedelta(minutes=5)

    def attempt(*, terminal_at, observed, anomaly, revision: int) -> None:
        with engine.connect() as connection:
            transaction = connection.begin()
            try:
                connection.exec_driver_sql("SET LOCAL session_replication_role = replica")
                connection.execute(
                    insert_event,
                    {
                        "request_id": accepted.status.request_id,
                        "operation_id": f"w23-check-only-{revision}-{suffix}",
                        "revision": revision,
                        "terminal_at": terminal_at,
                        "target": target_hmac,
                        "recorded_at": recorded_at,
                        "observed": observed,
                        "anomaly": anomaly,
                    },
                )
            finally:
                transaction.rollback()

    # Controls: an observation with server terminal time, and a legacy row
    # without observation columns, are both accepted by the CHECK.
    attempt(terminal_at=recorded_at, observed=earlier, anomaly=False, revision=9001)
    attempt(terminal_at=earlier, observed=None, anomaly=None, revision=9002)
    # Violations: device time as terminal_at, or a half-recorded observation.
    for label, values in (
        ("device_time_terminal", {"terminal_at": earlier, "observed": earlier, "anomaly": False}),
        ("anomaly_only", {"terminal_at": recorded_at, "observed": None, "anomaly": True}),
        ("observed_only", {"terminal_at": recorded_at, "observed": earlier, "anomaly": None}),
    ):
        with pytest.raises(IntegrityError) as rejected:
            attempt(revision=9003, **values)
        assert rejected.value.orig.sqlstate == "23514", label
        assert "ck_account_deletion_event_device_observed_time" in str(rejected.value.orig), label
    engine.dispose()


_KEY_A = "walksafe-w23-privacy-hmac-key-a-boundary-000000000001"
_KEY_B = "walksafe-w23-privacy-hmac-key-b-boundary-000000000002"


def _disposable_database(monkeypatch: pytest.MonkeyPatch):
    """Rotation mutates the global keyring, so it runs in a throwaway database."""

    from contextlib import contextmanager
    from pathlib import Path

    from alembic import command
    from alembic.config import Config
    from sqlalchemy import create_engine
    from sqlalchemy.engine import make_url
    from sqlalchemy.orm import sessionmaker

    @contextmanager
    def _factory():
        configured = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip())
        name = f"walksafe_w23_keyring_{uuid.uuid4().hex}"
        disposable = configured.set(database=name)
        url = disposable.render_as_string(hide_password=False)
        admin_engine = create_engine(configured, pool_pre_ping=True)
        engine = create_engine(disposable, pool_pre_ping=True)
        quoted = admin_engine.dialect.identifier_preparer.quote(name)
        created = False
        try:
            with admin_engine.connect().execution_options(
                isolation_level="AUTOCOMMIT"
            ) as connection:
                connection.exec_driver_sql(f"CREATE DATABASE {quoted}")
                created = True
            monkeypatch.setenv("DATABASE_URL", url)
            monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", url)
            command.upgrade(
                Config(str(Path(__file__).resolve().parents[1] / "alembic.ini")),
                "head",
            )
            yield engine, sessionmaker(bind=engine, expire_on_commit=False)
        finally:
            engine.dispose()
            if created:
                with admin_engine.connect().execution_options(
                    isolation_level="AUTOCOMMIT"
                ) as connection:
                    connection.execute(
                        text(
                            "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                            "WHERE datname = :name AND pid <> pg_backend_pid()"
                        ),
                        {"name": name},
                    ).all()
                    connection.exec_driver_sql(f"DROP DATABASE IF EXISTS {quoted}")
            admin_engine.dispose()

    return _factory()


def test_s1617_50_rotation_keeps_tombstone_block_and_fails_closed_without_old_key(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from backend.app.models import AccountDeletionTombstone, PrivacyHmacKeyringEntry
    from backend.app.schemas import AccountDeletionRequestV2
    from backend.app.services.privacy_lifecycle import (
        PrivacyLifecycleError,
        accept_account_deletion,
        assert_privacy_hmac_key_bound,
        bind_or_verify_privacy_hmac_key,
        configure_privacy_hmac_lookup_keys,
        lock_report_ingest_transaction,
        privacy_subject_hmac,
        privacy_subject_lookup_candidates,
        record_consent_event,
        rotate_privacy_hmac_key,
    )
    from backend.app.schemas import PRIVACY_CONSENT_ITEM_VERSIONS, PRIVACY_CONSENT_POLICY_VERSION

    monkeypatch.setattr(privacy_lifecycle, "_CONFIGURED_LOOKUP_KEYS", ())
    suffix = uuid.uuid4().hex[:12]
    actor_id = f"rotation.{suffix}"
    fresh_actor = f"fresh.{suffix}"
    a_era_actor = f"aera.{suffix}"

    def request(request_id: str) -> AccountDeletionRequestV2:
        return AccountDeletionRequestV2(
            schema_version="walksafe.account-deletion-request.v2",
            request_id=request_id,
            client_revision=1,
            confirmation="DELETE_MY_ACCOUNT",
        )

    def consent(SessionFactory, actor: str, secret: str, version: int) -> None:
        with SessionFactory() as db:
            record_consent_event(
                db,
                actor_id=actor,
                account_generation=1,
                installation_id=f"install_{actor.rsplit('.', 1)[-1]}_{version}",
                request_id=f"consent_{uuid.uuid4().hex}",
                client_revision=1,
                policy_version=PRIVACY_CONSENT_POLICY_VERSION,
                item_versions=PRIVACY_CONSENT_ITEM_VERSIONS,
                raw_source_collection=True,
                automatic_reporting=True,
                mobile_network_transfer=False,
                training_reuse=False,
                secret=secret,
                key_version=version,
                expected_previous_backend_receipt_sha256=None,
            )

    with _disposable_database(monkeypatch) as (engine, SessionFactory):
        # Key A era: consent and deletion create a tombstone under subject A.
        with SessionFactory() as db:
            bind_or_verify_privacy_hmac_key(db, secret=_KEY_A, key_version=1)
            db.commit()
        consent(SessionFactory, actor_id, _KEY_A, 1)
        consent(SessionFactory, a_era_actor, _KEY_A, 1)
        with SessionFactory() as db:
            accepted = accept_account_deletion(
                db,
                payload=request(f"delete_rotation_a_{suffix}"),
                actor_id=actor_id,
                account_generation=1,
                access_pre_digest="a" * 64,
                tombstone_id=None,
                secret=_KEY_A,
                key_version=1,
            )
        subject_a = privacy_subject_hmac(actor_id, 1, _KEY_A)

        # Owner-run rotation: A becomes LOOKUP_ONLY, B becomes ACTIVE. No re-keying.
        with SessionFactory() as db:
            rotate_privacy_hmac_key(
                db,
                current_secret=_KEY_A,
                current_key_version=1,
                new_secret=_KEY_B,
                new_key_version=2,
                lookup_keys={},
            )
            db.commit()
        with SessionFactory() as db:
            states = {
                entry.key_version: entry.state
                for entry in db.scalars(select(PrivacyHmacKeyringEntry)).all()
            }
            tombstone = db.scalar(
                select(AccountDeletionTombstone).where(
                    AccountDeletionTombstone.tombstone_id
                    == uuid.UUID(accepted.status.tombstone_id)
                )
            )
        assert states == {1: "LOOKUP_ONLY", 2: "ACTIVE"}
        assert tombstone is not None
        assert tombstone.privacy_subject_hmac == subject_a
        assert tombstone.hmac_key_version == 1

        # Keyring row cannot be deleted or un-retired.
        with pytest.raises(Exception):
            with engine.begin() as connection:
                connection.execute(text("DELETE FROM privacy_hmac_keyring WHERE key_version = 1"))
        with pytest.raises(Exception):
            with engine.begin() as connection:
                connection.execute(
                    text("UPDATE privacy_hmac_keyring SET state = 'ACTIVE' WHERE key_version = 1")
                )

        # Old key missing from the configured keyring: fail closed (503).
        configure_privacy_hmac_lookup_keys({})
        for check in (
            lambda db: bind_or_verify_privacy_hmac_key(db, secret=_KEY_B, key_version=2),
            lambda db: assert_privacy_hmac_key_bound(db, secret=_KEY_B, key_version=2),
        ):
            with pytest.raises(PrivacyLifecycleError) as missing_old:
                with SessionFactory() as db:
                    check(db)
            assert (missing_old.value.code, missing_old.value.status_code) == (
                "privacy_hmac_key_mismatch",
                503,
            )
        # A wrong "old" key is not bound either.
        with pytest.raises(PrivacyLifecycleError) as wrong_old:
            with SessionFactory() as db:
                bind_or_verify_privacy_hmac_key(
                    db,
                    secret=_KEY_B,
                    key_version=2,
                    lookup_keys={1: "walksafe-w23-wrong-old-privacy-key-0000000000000"},
                )
        assert wrong_old.value.code == "privacy_hmac_key_mismatch"
        # Still using key A as ACTIVE after rotation also fails closed.
        with pytest.raises(PrivacyLifecycleError) as stale_active:
            with SessionFactory() as db:
                bind_or_verify_privacy_hmac_key(
                    db, secret=_KEY_A, key_version=1, lookup_keys={}
                )
        assert stale_active.value.code == "privacy_hmac_key_mismatch"

        # Correct keyring {B active, A lookup-only}.
        configure_privacy_hmac_lookup_keys({1: _KEY_A})
        subject_b = privacy_subject_hmac(actor_id, 1, _KEY_B)
        assert subject_b != subject_a
        candidates = privacy_subject_lookup_candidates(actor_id, 1)
        assert candidates == (subject_a,)
        with SessionFactory() as db:
            bind_or_verify_privacy_hmac_key(db, secret=_KEY_B, key_version=2)
            # Deletion block continuity: the A-era tombstone still blocks ingest.
            with pytest.raises(PrivacyLifecycleError) as blocked:
                lock_report_ingest_transaction(
                    db,
                    subject_b,
                    1,
                    enforce_consent=False,
                    lookup_subjects=candidates,
                )
            assert blocked.value.code == "account_generation_tombstoned"
            db.rollback()
        with SessionFactory() as db:
            # A caller that does not pass lookup subjects fails closed.
            with pytest.raises(PrivacyLifecycleError) as unwired:
                lock_report_ingest_transaction(db, subject_b, 1, enforce_consent=False)
            assert unwired.value.code == "privacy_hmac_lookup_candidates_required"
            db.rollback()
        # A second deletion under key B is refused as already tombstoned.
        with pytest.raises(PrivacyLifecycleError) as again:
            with SessionFactory() as db:
                accept_account_deletion(
                    db,
                    payload=request(f"delete_rotation_b_{suffix}"),
                    actor_id=actor_id,
                    account_generation=1,
                    access_pre_digest="a" * 64,
                    tombstone_id=None,
                    secret=_KEY_B,
                    key_version=2,
                )
        assert again.value.code == "account_generation_tombstoned"

        # S1617-50-F2: evidence key registration for an A-era subject under key
        # B joins subject A, so "first registered key wins" cannot be bypassed
        # by registering a second key under the new subject B.
        # (w25 beprivacy: continuity wiring replaced the earlier 503 fail-closed.)
        from backend.app.services.privacy_lifecycle import (
            installation_subject_hmac,
            register_installation_evidence_key,
        )

        _key, a_era_der = _p256_keypair()
        _other_key, other_der = _p256_keypair()
        a_era_installation = f"install_{a_era_actor.rsplit('.', 1)[-1]}_1"
        a_era_subject = privacy_subject_hmac(a_era_actor, 1, _KEY_A)
        with SessionFactory() as db:
            registered = register_installation_evidence_key(
                db,
                actor_id=a_era_actor,
                account_generation=1,
                installation_id=a_era_installation,
                public_key_der=a_era_der,
                secret=_KEY_B,
                key_version=2,
            )
            assert registered.privacy_subject_hmac == a_era_subject
            assert registered.installation_subject_hmac == installation_subject_hmac(
                a_era_installation, a_era_subject, _KEY_A
            )
        with pytest.raises(PrivacyLifecycleError) as second_key:
            with SessionFactory() as db:
                register_installation_evidence_key(
                    db,
                    actor_id=a_era_actor,
                    account_generation=1,
                    installation_id=a_era_installation,
                    public_key_der=other_der,
                    secret=_KEY_B,
                    key_version=2,
                )
        assert second_key.value.code == "installation_evidence_key_conflict"
        with SessionFactory() as db:
            assert db.scalar(text("SELECT count(*) FROM privacy_installation_evidence_keys")) == 1

        # An A-era account without a tombstone is deleted under B with its
        # A-era installation target and an A-subject tombstone (key version 1).
        with SessionFactory() as db:
            a_era_deleted = accept_account_deletion(
                db,
                payload=request(f"delete_rotation_aera_{suffix}"),
                actor_id=a_era_actor,
                account_generation=1,
                access_pre_digest="a" * 64,
                tombstone_id=None,
                secret=_KEY_B,
                key_version=2,
            )
        assert a_era_deleted.created is True
        with SessionFactory() as db:
            a_era_tombstone = db.scalar(
                select(AccountDeletionTombstone).where(
                    AccountDeletionTombstone.tombstone_id
                    == uuid.UUID(a_era_deleted.status.tombstone_id)
                )
            )
            a_era_targets = db.scalars(
                select(AccountDeletionDeviceTarget.installation_subject_hmac).where(
                    AccountDeletionDeviceTarget.request_id
                    == a_era_deleted.status.request_id
                )
            ).all()
        assert a_era_tombstone is not None
        assert a_era_tombstone.privacy_subject_hmac == a_era_subject
        assert a_era_tombstone.hmac_key_version == 1
        assert a_era_targets == [
            installation_subject_hmac(a_era_installation, a_era_subject, _KEY_A)
        ]

        # A new account under key B stores key version 2 on its tombstone.
        consent(SessionFactory, fresh_actor, _KEY_B, 2)
        with SessionFactory() as db:
            fresh = accept_account_deletion(
                db,
                payload=request(f"delete_rotation_fresh_{suffix}"),
                actor_id=fresh_actor,
                account_generation=1,
                access_pre_digest="a" * 64,
                tombstone_id=None,
                secret=_KEY_B,
                key_version=2,
            )
        with SessionFactory() as db:
            fresh_tombstone = db.scalar(
                select(AccountDeletionTombstone).where(
                    AccountDeletionTombstone.tombstone_id
                    == uuid.UUID(fresh.status.tombstone_id)
                )
            )
        assert fresh_tombstone is not None
        assert fresh_tombstone.hmac_key_version == 2
        assert fresh_tombstone.privacy_subject_hmac == privacy_subject_hmac(
            fresh_actor, 1, _KEY_B
        )
        configure_privacy_hmac_lookup_keys({})


def _p256_keypair():
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec

    private_key = ec.generate_private_key(ec.SECP256R1())
    public_der = private_key.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return private_key, public_der


def _sign_evidence(private_key, evidence):
    import base64

    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec

    signature = private_key.sign(
        privacy_lifecycle.installation_evidence_signature_message(evidence.evidence_sha256),
        ec.ECDSA(hashes.SHA256()),
    )
    return evidence.model_copy(
        update={
            "installation_signature": base64.urlsafe_b64encode(signature)
            .decode("ascii")
            .rstrip("=")
        }
    )


_P256_COMPRESSED_SPKI_PREFIX = bytes.fromhex(
    "3039301306072a8648ce3d020106082a8648ce3d030107032200"
)


def _compressed_spki(private_key) -> bytes:
    from cryptography.hazmat.primitives import serialization

    return _P256_COMPRESSED_SPKI_PREFIX + private_key.public_key().public_bytes(
        serialization.Encoding.X962,
        serialization.PublicFormat.CompressedPoint,
    )


def _explicit_parameter_spki(private_key) -> bytes:
    """The same P-256 key encoded with explicit curve parameters (no curve OID)."""

    from cryptography.hazmat.primitives import serialization

    def der(tag: int, body: bytes) -> bytes:
        size = len(body)
        if size < 0x80:
            head = bytes([size])
        else:
            raw = size.to_bytes((size.bit_length() + 7) // 8, "big")
            head = bytes([0x80 | len(raw)]) + raw
        return bytes([tag]) + head + body

    def integer(value: int) -> bytes:
        return der(0x02, value.to_bytes(value.bit_length() // 8 + 1, "big"))

    p = 0xFFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF
    b = 0x5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B
    gx = 0x6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296
    gy = 0x4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5
    n = 0xFFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551
    point = private_key.public_key().public_bytes(
        serialization.Encoding.X962,
        serialization.PublicFormat.UncompressedPoint,
    )
    parameters = der(
        0x30,
        integer(1)
        + der(0x30, bytes.fromhex("06072a8648ce3d0101") + integer(p))
        + der(0x30, der(0x04, (p - 3).to_bytes(32, "big")) + der(0x04, b.to_bytes(32, "big")))
        + der(0x04, b"\x04" + gx.to_bytes(32, "big") + gy.to_bytes(32, "big"))
        + integer(n)
        + integer(1),
    )
    return der(
        0x30,
        der(0x30, bytes.fromhex("06072a8648ce3d0201") + parameters)
        + der(0x03, b"\x00" + point),
    )


def _runtime_session(engine):
    """Session whose whole transaction runs as the backend runtime role."""

    from contextlib import contextmanager

    from sqlalchemy.orm import Session

    @contextmanager
    def _session():
        with engine.connect() as connection:
            transaction = connection.begin()
            connection.exec_driver_sql("SET LOCAL ROLE walksafe_backend_runtime")
            db = Session(bind=connection, expire_on_commit=False)
            try:
                yield db
                transaction.commit()
            finally:
                db.close()
                if transaction.is_active:
                    transaction.rollback()

    return _session


def _register_key(SessionFactory, actor: str, installation: str, der: bytes):
    from backend.app.services.privacy_lifecycle import register_installation_evidence_key

    with SessionFactory() as db:
        return register_installation_evidence_key(
            db,
            actor_id=actor,
            account_generation=1,
            installation_id=installation,
            public_key_der=der,
            secret=PRIVACY_SECRET,
        )


def _subject_keys(SessionFactory, subject: str) -> dict[str, tuple[bytes, str]]:
    from backend.app.models import PrivacyInstallationEvidenceKey

    with SessionFactory() as db:
        return {
            row.installation_subject_hmac: (bytes(row.public_key_der), row.public_key_sha256)
            for row in db.scalars(
                select(PrivacyInstallationEvidenceKey).where(
                    PrivacyInstallationEvidenceKey.privacy_subject_hmac == subject
                )
            ).all()
        }


def _submit_evidence(session_factory, evidence, *, actor_id: str, tombstone_id: str):
    with session_factory() as db:
        return record_device_deletion_evidence(
            db,
            payload=evidence,
            actor_id=actor_id,
            account_generation=1,
            access_pre_digest="a" * 64,
            tombstone_id=tombstone_id,
            secret=PRIVACY_SECRET,
        )


def test_s1617_52_key_encoding_is_canonical_and_explicit_parameters_are_422(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """S1617-52-F2: one key has one stored form; other encodings are 422."""

    import hashlib

    from backend.app.services.privacy_lifecycle import (
        PrivacyLifecycleError,
        installation_subject_hmac,
        privacy_subject_hmac,
    )

    with _disposable_database(monkeypatch) as (_engine, SessionFactory):
        suffix = uuid.uuid4().hex[:12]
        actor_id = f"encoding.{suffix}"
        installation = f"install_encoding_{suffix}"
        _record_consent(SessionFactory, actor_id=actor_id, installation_id=installation)
        key, uncompressed = _p256_keypair()
        compressed = _compressed_spki(key)
        explicit = _explicit_parameter_spki(key)
        assert (len(compressed), len(uncompressed)) == (59, 91)
        assert len(explicit) > 158
        _register_key(SessionFactory, actor_id, installation, compressed)
        # Re-registering the same key in the uncompressed form is idempotent.
        _register_key(SessionFactory, actor_id, installation, uncompressed)
        subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
        installation_hmac = installation_subject_hmac(installation, subject, PRIVACY_SECRET)
        assert _subject_keys(SessionFactory, subject) == {
            installation_hmac: (uncompressed, hashlib.sha256(uncompressed).hexdigest())
        }
        # Explicit curve parameters (same curve and point) and garbage are 422,
        # never a storage error.
        for label, invalid in (("explicit", explicit), ("garbage", b"\x30" * 91)):
            with pytest.raises(PrivacyLifecycleError) as rejected:
                _register_key(SessionFactory, actor_id, f"install_{label}_{suffix}", invalid)
            assert (rejected.value.code, rejected.value.status_code) == (
                "installation_evidence_key_invalid",
                422,
            ), label
        assert len(_subject_keys(SessionFactory, subject)) == 1


def test_s1617_52_installation_signed_evidence_rejects_cross_installation_claims(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Signed evidence binds the reporting installation (S1617-52, F3 assertions).

    Runs in a throwaway database (S1617-52-F4) so the shared test database never
    keeps installation keys.
    """

    from sqlalchemy.exc import DBAPIError

    from backend.app.services.privacy_lifecycle import (
        PrivacyLifecycleError,
        privacy_subject_hmac,
    )

    with _disposable_database(monkeypatch) as (engine, SessionFactory):
        suffix = uuid.uuid4().hex[:12]
        actor_id = f"signed.{suffix}"
        phone = f"install_phone_{suffix}"
        tablet = f"install_tablet_{suffix}"
        _record_consent(SessionFactory, actor_id=actor_id, installation_id=phone)
        _record_consent(SessionFactory, actor_id=actor_id, installation_id=tablet)
        phone_key, phone_der = _p256_keypair()
        tablet_key, tablet_der = _p256_keypair()
        for installation, der in ((phone, phone_der), (tablet, tablet_der)):
            _register_key(SessionFactory, actor_id, installation, der)
        # Idempotent for the same key; a different key is a conflict, not a swap.
        _register_key(SessionFactory, actor_id, phone, phone_der)
        with pytest.raises(PrivacyLifecycleError) as swapped:
            _register_key(SessionFactory, actor_id, phone, tablet_der)
        assert swapped.value.code == "installation_evidence_key_conflict"
        with pytest.raises(PrivacyLifecycleError) as not_p256:
            _register_key(SessionFactory, actor_id, f"install_bad_{suffix}", b"\x30" * 91)
        assert not_p256.value.code == "installation_evidence_key_invalid"

        accepted = _accept(
            SessionFactory,
            actor_id=actor_id,
            request_id=f"delete_signed_{suffix}",
        )
        subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)

        # S1617-52-F3: registration after the deletion is accepted is refused.
        with pytest.raises(PrivacyLifecycleError) as tombstoned:
            _register_key(SessionFactory, actor_id, f"install_late_{suffix}", _p256_keypair()[1])
        assert (tombstoned.value.code, tombstoned.value.status_code) == (
            "account_generation_tombstoned",
            409,
        )
        # S1617-52-F3: the key rows cannot be updated or deleted directly, and
        # the runtime role has no DELETE privilege at all.
        subject_filter = {"subject": subject}
        for statement in (
            "UPDATE privacy_installation_evidence_keys "
            "SET public_key_sha256 = repeat('0', 64) WHERE privacy_subject_hmac = :subject",
            "DELETE FROM privacy_installation_evidence_keys "
            "WHERE privacy_subject_hmac = :subject",
        ):
            with pytest.raises(DBAPIError):
                with engine.begin() as connection:
                    connection.execute(text(statement), subject_filter)
        with pytest.raises(DBAPIError) as runtime_delete:
            with engine.begin() as connection:
                connection.exec_driver_sql("SET LOCAL ROLE walksafe_backend_runtime")
                connection.execute(
                    text(
                        "DELETE FROM privacy_installation_evidence_keys "
                        "WHERE privacy_subject_hmac = :subject"
                    ),
                    subject_filter,
                )
        assert runtime_delete.value.orig.sqlstate == "42501"
        assert len(_subject_keys(SessionFactory, subject)) == 2

        def submit(evidence):
            return _submit_evidence(
                SessionFactory,
                evidence,
                actor_id=actor_id,
                tombstone_id=accepted.status.tombstone_id,
            )

        tablet_claim = _evidence(
            accepted.status,
            installation_id=tablet,
            evidence_id=f"w23-tablet-claim-{suffix}",
        )
        # The phone (holding the deletion capability) claims the tablet deleted: refused.
        for forged in (tablet_claim, _sign_evidence(phone_key, tablet_claim)):
            with pytest.raises(PrivacyLifecycleError) as cross:
                submit(forged)
            assert (cross.value.code, cross.value.status_code) == (
                "device_deletion_evidence_signature_invalid",
                403,
            )
        # A signature over different evidence does not transfer.
        other = _evidence(
            accepted.status,
            installation_id=tablet,
            evidence_id=f"w23-tablet-other-{suffix}",
            result="NOT_FOUND",
        )
        transplanted = tablet_claim.model_copy(
            update={
                "installation_signature": _sign_evidence(tablet_key, other).installation_signature
            }
        )
        with pytest.raises(PrivacyLifecycleError) as transplanted_error:
            submit(transplanted)
        assert transplanted_error.value.code == "device_deletion_evidence_signature_invalid"

        with SessionFactory() as db:
            untouched = db.scalars(
                select(AccountDeletionDeviceTarget.state).where(
                    AccountDeletionDeviceTarget.request_id == accepted.status.request_id
                )
            ).all()
        assert sorted(untouched) == ["PENDING", "PENDING"]

        after_phone = submit(
            _sign_evidence(
                phone_key,
                _evidence(
                    accepted.status,
                    installation_id=phone,
                    evidence_id=f"w23-phone-own-{suffix}",
                ),
            )
        )
        assert after_phone.items[0].status == "EXTERNAL_PENDING"
        completed = submit(
            _sign_evidence(
                tablet_key,
                _evidence(
                    after_phone,
                    installation_id=tablet,
                    evidence_id=f"w23-tablet-own-{suffix}",
                ),
            )
        )
        assert completed.items[0].status == "COMPLETED"
        # The request is not COMPLETED yet (server items remain): keys stay.
        assert completed.overall_status != "COMPLETED"
        assert len(_subject_keys(SessionFactory, subject)) == 2


def test_s1617_52_completed_deletion_purges_installation_keys_and_keeps_fingerprint(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """S1617-52-F1: installation keys are deletion-scope data.

    When the subject generation's deletion request becomes COMPLETED (by the
    last device evidence through the runtime role, the last item transition,
    or the worker's server manifest), its keys are deleted by the purge
    function; each DEVICE_EVIDENCE keeps only the signing key fingerprint.
    Before completion nothing, including a forged purge marker, deletes them.
    """

    import hashlib

    from sqlalchemy import func
    from sqlalchemy.exc import DBAPIError

    from backend.app.models import PrivacyInstallationEvidenceKey
    from backend.app.services.privacy_lifecycle import (
        installation_subject_hmac,
        privacy_subject_hmac,
    )

    with _disposable_database(monkeypatch) as (engine, SessionFactory):
        # The worker role helper connects through this URL.
        monkeypatch.setenv(
            "WALKSAFE_TEST_DATABASE_URL",
            engine.url.render_as_string(hide_password=False),
        )
        suffix = uuid.uuid4().hex[:12]
        actor_id = f"purge.{suffix}"
        bystander = f"bystander.{suffix}"
        phone = f"install_phone_{suffix}"
        tablet = f"install_tablet_{suffix}"
        bystander_installation = f"install_bystander_{suffix}"
        _record_consent(SessionFactory, actor_id=actor_id, installation_id=phone)
        _record_consent(SessionFactory, actor_id=actor_id, installation_id=tablet)
        _record_consent(
            SessionFactory, actor_id=bystander, installation_id=bystander_installation
        )
        phone_key, phone_der = _p256_keypair()
        tablet_key, tablet_der = _p256_keypair()
        _register_key(SessionFactory, actor_id, phone, phone_der)
        _register_key(SessionFactory, actor_id, tablet, tablet_der)
        bystander_key, bystander_der = _p256_keypair()
        _register_key(SessionFactory, bystander, bystander_installation, bystander_der)
        subject = privacy_subject_hmac(actor_id, 1, PRIVACY_SECRET)
        expected_fingerprints = {
            installation_subject_hmac(phone, subject, PRIVACY_SECRET): hashlib.sha256(
                phone_der
            ).hexdigest(),
            installation_subject_hmac(tablet, subject, PRIVACY_SECRET): hashlib.sha256(
                tablet_der
            ).hexdigest(),
        }
        accepted = _accept(
            SessionFactory,
            actor_id=actor_id,
            request_id=f"delete_purge_{suffix}",
        )

        # Before completion: direct DELETE, the purge function, and a forged
        # purge marker are all refused by the database.
        subject_filter = {"subject": subject}
        delete_keys = (
            "DELETE FROM privacy_installation_evidence_keys "
            "WHERE privacy_subject_hmac = :subject"
        )
        for statement in (
            delete_keys,
            "SELECT walksafe_purge_privacy_installation_evidence_keys(:subject, 1)",
        ):
            with pytest.raises(DBAPIError) as refused:
                with engine.begin() as connection:
                    connection.execute(text(statement), subject_filter)
            assert refused.value.orig.sqlstate == "23514", statement
        with pytest.raises(DBAPIError) as forged:
            with engine.begin() as connection:
                connection.execute(
                    text(
                        "SELECT set_config('walksafe.installation_evidence_key_purge', "
                        ":marker, true)"
                    ),
                    {"marker": f"{subject}:1"},
                )
                connection.execute(text(delete_keys), subject_filter)
        assert forged.value.orig.sqlstate == "23514"
        with engine.connect() as connection:
            privileges = connection.execute(
                text(
                    "SELECT "
                    "has_table_privilege('walksafe_backend_runtime', "
                    "'public.privacy_installation_evidence_keys', 'DELETE'), "
                    "has_table_privilege('walksafe_backend_runtime', "
                    "'public.privacy_installation_evidence_keys', 'UPDATE'), "
                    "has_function_privilege('walksafe_backend_runtime', "
                    "'public.walksafe_purge_privacy_installation_evidence_keys(text, bigint)', "
                    "'EXECUTE'), "
                    "has_function_privilege('walksafe_account_deletion_worker', "
                    "'public.walksafe_purge_privacy_installation_evidence_keys(text, bigint)', "
                    "'EXECUTE'), "
                    "has_function_privilege('public', "
                    "'public.walksafe_purge_privacy_installation_evidence_keys(text, bigint)', "
                    "'EXECUTE'), "
                    "(SELECT prosecdef FROM pg_proc "
                    "WHERE proname = 'walksafe_purge_privacy_installation_evidence_keys'), "
                    "(SELECT proconfig FROM pg_proc "
                    "WHERE proname = 'walksafe_purge_privacy_installation_evidence_keys')"
                )
            ).one()
        assert tuple(privileges) == (
            False,
            False,
            True,
            True,
            False,
            True,
            ["search_path=pg_catalog, pg_temp"],
        )
        assert len(_subject_keys(SessionFactory, subject)) == 2

        after_phone = _submit_evidence(
            SessionFactory,
            _sign_evidence(
                phone_key,
                _evidence(
                    accepted.status,
                    installation_id=phone,
                    evidence_id=f"w23-purge-phone-{suffix}",
                ),
            ),
            actor_id=actor_id,
            tombstone_id=accepted.status.tombstone_id,
        )
        current = _finish_server_side_items(engine, SessionFactory, after_phone, suffix)
        assert current.overall_status == "PARTIAL"
        assert len(_subject_keys(SessionFactory, subject)) == 2

        # The last device evidence arrives through the runtime role (the API
        # path) and completes the request; the keys go in the same transaction.
        tablet_evidence = _sign_evidence(
            tablet_key,
            _evidence(
                current,
                installation_id=tablet,
                evidence_id=f"w23-purge-tablet-{suffix}",
            ),
        )
        completed = _submit_evidence(
            _runtime_session(engine),
            tablet_evidence,
            actor_id=actor_id,
            tombstone_id=accepted.status.tombstone_id,
        )
        assert completed.overall_status == "COMPLETED"
        assert _subject_keys(SessionFactory, subject) == {}
        with SessionFactory() as db:
            recorded = {
                event.installation_subject_hmac: event.installation_evidence_key_sha256
                for event in db.scalars(
                    select(AccountDeletionEvent).where(
                        AccountDeletionEvent.request_id == completed.request_id,
                        AccountDeletionEvent.event_type == "DEVICE_EVIDENCE",
                    )
                ).all()
            }
            remaining_subjects = db.scalars(
                select(PrivacyInstallationEvidenceKey.privacy_subject_hmac)
            ).all()
        # Only the fingerprint of the key that signed each evidence remains.
        assert recorded == expected_fingerprints
        # Other subjects keep their keys.
        assert remaining_subjects == [privacy_subject_hmac(bystander, 1, PRIVACY_SECRET)]
        # A lost-response replay after the purge is still idempotent.
        replay = _submit_evidence(
            SessionFactory,
            tablet_evidence,
            actor_id=actor_id,
            tombstone_id=accepted.status.tombstone_id,
        )
        assert replay.revision == completed.revision

        def completes_with_key(label: str, finish) -> None:
            late_actor = f"{label}.{suffix}"
            installation = f"install_{label}_{suffix}"
            _record_consent(SessionFactory, actor_id=late_actor, installation_id=installation)
            late_key, late_der = _p256_keypair()
            _register_key(SessionFactory, late_actor, installation, late_der)
            late_accepted = _accept(
                SessionFactory,
                actor_id=late_actor,
                request_id=f"delete_{label}_{suffix}",
            )
            device_done = _submit_evidence(
                SessionFactory,
                _sign_evidence(
                    late_key,
                    _evidence(
                        late_accepted.status,
                        installation_id=installation,
                        evidence_id=f"w23-{label}-device-{suffix}",
                    ),
                ),
                actor_id=late_actor,
                tombstone_id=late_accepted.status.tombstone_id,
            )
            assert device_done.items[0].status == "COMPLETED"
            late_subject = privacy_subject_hmac(late_actor, 1, PRIVACY_SECRET)
            count = (
                select(func.count())
                .select_from(PrivacyInstallationEvidenceKey)
                .where(PrivacyInstallationEvidenceKey.privacy_subject_hmac == late_subject)
            )
            with SessionFactory() as db:
                assert db.scalar(count) == 1
            done = finish(device_done, f"{label}{suffix}")
            assert done.overall_status == "COMPLETED"
            with SessionFactory() as db:
                assert db.scalar(count) == 0

        # Last step = general item transition (_apply_item_transition).
        completes_with_key(
            "itemlast",
            lambda status, tag: _finish_server_side_items(engine, SessionFactory, status, tag),
        )
        # Last step = server manifest run by the account deletion worker role.
        completes_with_key(
            "manifestlast",
            lambda status, tag: _finish_server_manifest(
                engine,
                _finish_external_items(SessionFactory, status, tag),
                tag,
            ),
        )
        with SessionFactory() as db:
            assert db.scalars(
                select(PrivacyInstallationEvidenceKey.privacy_subject_hmac)
            ).all() == [privacy_subject_hmac(bystander, 1, PRIVACY_SECRET)]

        # S1617-52-F4: the 202609300004 downgrade refuses while a binding or a
        # key-fingerprint verification record exists (the 202609300003
        # downgrade would drop bindings; the predecessor cannot purge).
        from pathlib import Path

        from alembic import command
        from alembic.config import Config

        config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))

        def assert_downgrade_refused(match: str) -> None:
            with pytest.raises(RuntimeError, match=match):
                command.downgrade(config, "202609300003")
            with engine.connect() as connection:
                # The whole downgrade is one transaction, so a refusal keeps
                # the repository head (202610110001 including opt-in diagnostic tables).
                assert connection.execute(
                    text("SELECT version_num FROM alembic_version")
                ).scalar_one() == "202610110001"

        assert_downgrade_refused("installation evidence keys are registered")
        bystander_accepted = _accept(
            SessionFactory,
            actor_id=bystander,
            request_id=f"delete_bystander_{suffix}",
        )
        bystander_done = _submit_evidence(
            SessionFactory,
            _sign_evidence(
                bystander_key,
                _evidence(
                    bystander_accepted.status,
                    installation_id=bystander_installation,
                    evidence_id=f"w23-bystander-device-{suffix}",
                ),
            ),
            actor_id=bystander,
            tombstone_id=bystander_accepted.status.tombstone_id,
        )
        bystander_done = _finish_server_side_items(
            engine, SessionFactory, bystander_done, f"bystander{suffix}"
        )
        assert bystander_done.overall_status == "COMPLETED"
        with SessionFactory() as db:
            assert db.scalar(
                select(func.count()).select_from(PrivacyInstallationEvidenceKey)
            ) == 0
        assert_downgrade_refused("key-fingerprint verification record")


def test_s01_14_lifespan_first_binding_and_otp_only_key_change_keep_login(
    monkeypatch: pytest.MonkeyPatch,
    caplog: pytest.LogCaptureFixture,
) -> None:
    """S01-14-F1: production startup verifies only the login key purposes."""

    from backend.app.models import AccountCryptoKeyBinding
    from backend.app.services.accounts import AccountService, AccountServiceError
    from test_account_key_purpose_binding import (
        _authenticate,
        _create,
        _issue,
        run_production_startup,
    )
    from test_accounts import _settings

    caplog.set_level("WARNING", logger="backend.app.main")
    with _disposable_database(monkeypatch) as (engine, SessionFactory):
        # Empty store: the first startup still binds all three fingerprints.
        original = _settings()
        assert run_production_startup(monkeypatch, SessionFactory, original) is True
        with SessionFactory() as db:
            binding = db.get(AccountCryptoKeyBinding, 1)
            assert binding is not None
            assert binding.key_version == 1
            assert (
                binding.encryption_key_fingerprint,
                binding.lookup_hmac_key_fingerprint,
                binding.otp_hmac_key_fingerprint,
            ) == AccountService(original)._crypto().key_fingerprints
        assert "otp_hmac" not in caplog.text

        # OTP key alone changes: startup succeeds and names only the purpose.
        rotated_otp = _settings(account_otp_hmac_key=b"Q" * 32)
        assert run_production_startup(monkeypatch, SessionFactory, rotated_otp) is True
        assert "otp_hmac" in caplog.text
        for secret_text in (
            binding.otp_hmac_key_fingerprint,
            *AccountService(rotated_otp)._crypto().key_fingerprints,
        ):
            assert secret_text not in caplog.text
        service = AccountService(rotated_otp)
        with SessionFactory() as db:
            login = _authenticate(service, db)
        assert (login.code, login.status_code) == ("account_authentication_failed", 401)
        with SessionFactory() as db:
            issue = _issue(service, db)
        assert (issue.code, issue.status_code) == ("account_crypto_key_mismatch", 503)
        with SessionFactory() as db:
            create = _create(service, db)
        assert (create.code, create.status_code) == ("account_crypto_key_mismatch", 503)

        # Lookup, encryption, or version changes still stop startup.
        for changed in (
            {"account_email_lookup_hmac_key": b"M" * 32},
            {"account_email_encryption_key": b"F" * 32},
            {"account_email_key_version": 2},
        ):
            with pytest.raises(AccountServiceError) as rejected:
                run_production_startup(monkeypatch, SessionFactory, _settings(**changed))
            assert (rejected.value.code, rejected.value.status_code) == (
                "account_crypto_key_mismatch",
                503,
            ), changed
        with SessionFactory() as db:
            unchanged = db.get(AccountCryptoKeyBinding, 1)
            assert unchanged.otp_hmac_key_fingerprint == binding.otp_hmac_key_fingerprint
