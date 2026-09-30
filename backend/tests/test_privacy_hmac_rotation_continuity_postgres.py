"""S1617-50: privacy HMAC key rotation keeps every actor path continuous.

After an owner rotation (key A LOOKUP_ONLY, key B ACTIVE) an actor whose rows
were written under key A keeps subject A for consent, reports, RAW, report
reads, deletion status and device evidence (no re-keying); a tombstone from
before the rotation still blocks every write; rows under two subjects of the
same actor generation, a missing or wrong lookup key fail closed (503).
"""

from __future__ import annotations

import os
from types import SimpleNamespace
import uuid

from fastapi import HTTPException
import pytest
from sqlalchemy import select

import backend.app.services.privacy_lifecycle as privacy_lifecycle
from backend.app.api.report_user_requests import _field_binding
from backend.app.api.reports import (
    _assert_report_precommit_active,
    _privacy_report_binding,
    _privacy_report_candidates,
)
from backend.app.models import (
    AccountDeletionDeviceTarget,
    AccountDeletionTombstone,
    PrivacyConsentEvent,
)
from backend.app.schemas import (
    AccountDeletionRequestV2,
    PRIVACY_CONSENT_ITEM_VERSIONS,
    PRIVACY_CONSENT_POLICY_VERSION,
)
from backend.app.services.privacy_lifecycle import (
    PrivacyLifecycleError,
    accept_account_deletion,
    bind_or_verify_privacy_hmac_key,
    configure_privacy_hmac_lookup_keys,
    get_account_deletion_status,
    get_consent_bootstrap,
    installation_subject_hmac,
    lock_report_ingest_transaction,
    privacy_subject_candidates,
    privacy_subject_hmac,
    record_consent_event,
    record_device_deletion_evidence,
    resolve_privacy_subject,
    rotate_privacy_hmac_key,
)
from backend.app.services.raw_collection_ingest import authorize_raw_collection_write
from test_fp046_postgres_integration import _evidence
from test_privacy_sweep_w23_postgres_integration import (
    _KEY_A,
    _KEY_B,
    _disposable_database,
)


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)

_WRONG_A = "walksafe-w25-wrong-lookup-privacy-key-000000000000000"
_SIGNUP_VERSIONS = {
    "raw_original": "1.2.0",
    "automatic_reporting": "1.2.0",
    "training_reuse": "1.1.0",
}


def _request(request_id: str) -> AccountDeletionRequestV2:
    return AccountDeletionRequestV2(
        schema_version="walksafe.account-deletion-request.v2",
        request_id=request_id,
        client_revision=1,
        confirmation="DELETE_MY_ACCOUNT",
    )


def _consent(
    SessionFactory,
    *,
    actor: str,
    installation: str,
    secret: str,
    version: int,
    revision: int = 1,
    previous: str | None = None,
):
    with SessionFactory() as db:
        return record_consent_event(
            db,
            actor_id=actor,
            account_generation=1,
            installation_id=installation,
            request_id=f"consent_{uuid.uuid4().hex}",
            client_revision=revision,
            policy_version=PRIVACY_CONSENT_POLICY_VERSION,
            item_versions=PRIVACY_CONSENT_ITEM_VERSIONS,
            raw_source_collection=True,
            automatic_reporting=True,
            mobile_network_transfer=False,
            training_reuse=False,
            secret=secret,
            key_version=version,
            expected_previous_backend_receipt_sha256=previous,
        )


def _bootstrap(db, *, actor: str, installation: str):
    return get_consent_bootstrap(
        db,
        actor_id=actor,
        account_generation=1,
        installation_id=installation,
        policy_version=PRIVACY_CONSENT_POLICY_VERSION,
        signup_document_versions=_SIGNUP_VERSIONS,
        secret=_KEY_B,
        key_version=2,
    )


def test_s1617_50_resolution_without_lookup_keys_touches_no_table(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(privacy_lifecycle, "_CONFIGURED_LOOKUP_KEYS", ())
    resolved = resolve_privacy_subject(
        object(),  # any DB access would raise AttributeError
        "no-rotation.actor",
        1,
        secret=_KEY_A,
        key_version=1,
    )
    subject = privacy_subject_hmac("no-rotation.actor", 1, _KEY_A)
    assert resolved.subject == subject
    assert resolved.secret == _KEY_A
    assert resolved.key_version == 1
    assert resolved.candidates == (subject,)


def test_s1617_50_rotated_actor_paths_stay_continuous_and_fail_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(privacy_lifecycle, "_CONFIGURED_LOOKUP_KEYS", ())
    suffix = uuid.uuid4().hex[:12]
    active_actor = f"active.{suffix}"
    deleted_actor = f"deleted.{suffix}"
    split_actor = f"split.{suffix}"
    active_install = f"install_active_{suffix}"
    deleted_install = f"install_deleted_{suffix}"
    split_install = f"install_split_{suffix}"
    settings_b = SimpleNamespace(
        privacy_hmac_secret=_KEY_B,
        privacy_hmac_key_version=2,
        field_test_security_enabled=False,
    )

    with _disposable_database(monkeypatch) as (_engine, SessionFactory):
        # ---- Key A era ------------------------------------------------------
        with SessionFactory() as db:
            bind_or_verify_privacy_hmac_key(db, secret=_KEY_A, key_version=1)
            db.commit()
        a_era_consent = _consent(
            SessionFactory, actor=active_actor, installation=active_install,
            secret=_KEY_A, version=1,
        ).event
        _consent(
            SessionFactory, actor=deleted_actor, installation=deleted_install,
            secret=_KEY_A, version=1,
        )
        _consent(
            SessionFactory, actor=split_actor, installation=split_install,
            secret=_KEY_A, version=1,
        )
        with SessionFactory() as db:
            deleted = accept_account_deletion(
                db,
                payload=_request(f"delete_before_rotation_{suffix}"),
                actor_id=deleted_actor,
                account_generation=1,
                access_pre_digest="b" * 64,
                tombstone_id=None,
                secret=_KEY_A,
                key_version=1,
            ).status
        subject_a = privacy_subject_hmac(active_actor, 1, _KEY_A)
        deleted_subject_a = privacy_subject_hmac(deleted_actor, 1, _KEY_A)

        # ---- Owner rotation A -> B, no re-keying ---------------------------
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
        configure_privacy_hmac_lookup_keys({1: _KEY_A})
        try:
            candidates = privacy_subject_candidates(active_actor, 1, _KEY_B)
            assert set(candidates) == {subject_a, privacy_subject_hmac(active_actor, 1, _KEY_B)}

            # Consent bootstrap and change continue the A-era chain (was 503).
            with SessionFactory() as db:
                bootstrap = _bootstrap(db, actor=active_actor, installation=active_install)
            assert bootstrap.status == "READY"
            assert bootstrap.source == "CURRENT_CONSENT"
            assert bootstrap.expected_previous_backend_receipt_sha256 == (
                a_era_consent.receipt_sha256
            )
            assert bootstrap.client_revision_floor == 1
            changed = _consent(
                SessionFactory, actor=active_actor, installation=active_install,
                secret=_KEY_B, version=2, revision=2,
                previous=a_era_consent.receipt_sha256,
            ).event
            assert changed.privacy_subject_hmac == subject_a
            assert changed.subject_revision == 2
            assert changed.installation_subject_hmac == installation_subject_hmac(
                active_install, subject_a, _KEY_A
            )

            # Report ingest joins subject A and passes the fenced checks.
            with SessionFactory() as db:
                subject, generation = _privacy_report_binding(
                    db=db, actor_id=active_actor, account_generation=1,
                    settings=settings_b,
                )
                report_candidates = _privacy_report_candidates(
                    privacy_subject=subject, actor_id=active_actor,
                    account_generation=generation, settings=settings_b,
                )
                assert (subject, generation) == (subject_a, 1)
                assert report_candidates == candidates
                lock_report_ingest_transaction(
                    db, subject, 1, automatic_reporting=True,
                    enforce_consent=True, lookup_subjects=report_candidates,
                )
                _assert_report_precommit_active(
                    db, privacy_subject=subject, account_generation=1,
                    settings=settings_b, lookup_subjects=report_candidates,
                )
                db.rollback()

            # RAW admission binds subject A and the A-chain receipt.
            with SessionFactory() as db:
                admission = authorize_raw_collection_write(
                    db,
                    actor_id=active_actor,
                    account_generation=1,
                    purpose="GENERAL_RAW",
                    consent_receipt_sha256=changed.receipt_sha256,
                    settings=settings_b,
                )
                db.rollback()
            assert admission.privacy_subject_hmac == subject_a

            # "My reports" reads resolve subject A.
            with SessionFactory() as db:
                _actor, _generation, read_subject = _field_binding(
                    SimpleNamespace(state=SimpleNamespace()),
                    settings_b,
                    actor_id=active_actor,
                    account_generation="1",
                    db=db,
                )
            assert read_subject == subject_a

            # A deletion accepted before the rotation stays readable and
            # accepts device evidence with the A-derived access/installation HMACs.
            with SessionFactory() as db:
                status = get_account_deletion_status(
                    db,
                    request_id=deleted.request_id,
                    actor_id=deleted_actor,
                    account_generation=1,
                    access_pre_digest="b" * 64,
                    tombstone_id=deleted.tombstone_id,
                    secret=_KEY_B,
                    key_version=2,
                )
            assert status.request_id == deleted.request_id
            with pytest.raises(PrivacyLifecycleError) as wrong_digest:
                with SessionFactory() as db:
                    get_account_deletion_status(
                        db,
                        request_id=deleted.request_id,
                        actor_id=deleted_actor,
                        account_generation=1,
                        access_pre_digest="c" * 64,
                        tombstone_id=deleted.tombstone_id,
                        secret=_KEY_B,
                        key_version=2,
                    )
            assert wrong_digest.value.status_code == 404
            with SessionFactory() as db:
                after_evidence = record_device_deletion_evidence(
                    db,
                    payload=_evidence(
                        status,
                        installation_id=deleted_install,
                        evidence_id=f"evidence_{suffix}",
                    ),
                    actor_id=deleted_actor,
                    account_generation=1,
                    access_pre_digest="b" * 64,
                    tombstone_id=deleted.tombstone_id,
                    secret=_KEY_B,
                    key_version=2,
                )
            device_item = next(
                item for item in after_evidence.items
                if item.key == "device_untransmitted_data"
            )
            assert device_item.status == "COMPLETED"
            with SessionFactory() as db:
                target = db.get(
                    AccountDeletionDeviceTarget,
                    (
                        deleted.request_id,
                        installation_subject_hmac(
                            deleted_install, deleted_subject_a, _KEY_A
                        ),
                    ),
                )
                assert target is not None and target.state == "COMPLETED"

            # The A-era tombstone still blocks every write path under key B.
            with SessionFactory() as db:
                subject, _generation = _privacy_report_binding(
                    db=db, actor_id=deleted_actor, account_generation=1,
                    settings=settings_b,
                )
                assert subject == deleted_subject_a
                with pytest.raises(PrivacyLifecycleError) as report_blocked:
                    lock_report_ingest_transaction(
                        db, subject, 1, enforce_consent=False,
                        lookup_subjects=_privacy_report_candidates(
                            privacy_subject=subject, actor_id=deleted_actor,
                            account_generation=1, settings=settings_b,
                        ),
                    )
                assert report_blocked.value.code == "account_generation_tombstoned"
                db.rollback()
            with pytest.raises(PrivacyLifecycleError) as raw_blocked:
                with SessionFactory() as db:
                    authorize_raw_collection_write(
                        db,
                        actor_id=deleted_actor,
                        account_generation=1,
                        purpose="GENERAL_RAW",
                        consent_receipt_sha256="0" * 64,
                        settings=settings_b,
                    )
            assert raw_blocked.value.code == "account_generation_tombstoned"
            with pytest.raises(PrivacyLifecycleError) as consent_blocked:
                _consent(
                    SessionFactory, actor=deleted_actor, installation=deleted_install,
                    secret=_KEY_B, version=2, revision=2,
                )
            assert consent_blocked.value.code == "account_generation_tombstoned"
            with pytest.raises(PrivacyLifecycleError) as again:
                with SessionFactory() as db:
                    accept_account_deletion(
                        db,
                        payload=_request(f"delete_after_rotation_{suffix}"),
                        actor_id=deleted_actor,
                        account_generation=1,
                        access_pre_digest="b" * 64,
                        tombstone_id=None,
                        secret=_KEY_B,
                        key_version=2,
                    )
            assert again.value.code == "account_generation_tombstoned"

            # An unwired writer that ignored the lookup key would split the
            # subject; every resolving path then fails closed.
            monkeypatch.setattr(privacy_lifecycle, "_CONFIGURED_LOOKUP_KEYS", ())
            with monkeypatch.context() as unwired:
                unwired.setattr(
                    privacy_lifecycle, "_verify_privacy_keyring", lambda *_a, **_k: True
                )
                unwired.setattr(
                    privacy_lifecycle,
                    "bind_or_verify_privacy_hmac_key",
                    lambda *_a, **_k: None,
                )
                split_b = _consent(
                    SessionFactory, actor=split_actor, installation=split_install,
                    secret=_KEY_B, version=2,
                ).event
            assert split_b.privacy_subject_hmac == privacy_subject_hmac(split_actor, 1, _KEY_B)
            configure_privacy_hmac_lookup_keys({1: _KEY_A})
            split_checks = (
                lambda db: _bootstrap(db, actor=split_actor, installation=split_install),
                lambda db: accept_account_deletion(
                    db,
                    payload=_request(f"delete_split_{suffix}"),
                    actor_id=split_actor,
                    account_generation=1,
                    access_pre_digest="a" * 64,
                    tombstone_id=None,
                    secret=_KEY_B,
                    key_version=2,
                ),
                lambda db: authorize_raw_collection_write(
                    db,
                    actor_id=split_actor,
                    account_generation=1,
                    purpose="GENERAL_RAW",
                    consent_receipt_sha256=split_b.receipt_sha256,
                    settings=settings_b,
                ),
            )
            for check in split_checks:
                with pytest.raises(PrivacyLifecycleError) as split:
                    with SessionFactory() as db:
                        check(db)
                assert (split.value.code, split.value.status_code) == (
                    "privacy_hmac_rotation_continuity_incomplete",
                    503,
                )
            with pytest.raises(HTTPException) as split_report:
                with SessionFactory() as db:
                    _privacy_report_binding(
                        db=db, actor_id=split_actor, account_generation=1,
                        settings=settings_b,
                    )
            assert split_report.value.status_code == 503
            with pytest.raises(HTTPException) as split_read:
                with SessionFactory() as db:
                    _field_binding(
                        SimpleNamespace(state=SimpleNamespace()),
                        settings_b,
                        actor_id=split_actor,
                        account_generation="1",
                        db=db,
                    )
            assert split_read.value.status_code == 503

            # A wrong or missing lookup key never opens processing.
            for lookup in ({1: _WRONG_A}, {}):
                configure_privacy_hmac_lookup_keys(lookup)
                for check in (
                    lambda db: _bootstrap(db, actor=active_actor, installation=active_install),
                    lambda db: get_account_deletion_status(
                        db,
                        request_id=deleted.request_id,
                        actor_id=deleted_actor,
                        account_generation=1,
                        access_pre_digest="b" * 64,
                        tombstone_id=deleted.tombstone_id,
                        secret=_KEY_B,
                        key_version=2,
                    ),
                    lambda db: authorize_raw_collection_write(
                        db,
                        actor_id=active_actor,
                        account_generation=1,
                        purpose="GENERAL_RAW",
                        consent_receipt_sha256=changed.receipt_sha256,
                        settings=settings_b,
                    ),
                ):
                    with pytest.raises(PrivacyLifecycleError) as mismatch:
                        with SessionFactory() as db:
                            check(db)
                    assert (mismatch.value.code, mismatch.value.status_code) == (
                        "privacy_hmac_key_mismatch",
                        503,
                    )
            configure_privacy_hmac_lookup_keys({1: _KEY_A})

            # Nothing was re-keyed: the A-era rows keep subject A and the
            # pre-rotation tombstone keeps key version 1.
            with SessionFactory() as db:
                tombstone = db.scalar(
                    select(AccountDeletionTombstone).where(
                        AccountDeletionTombstone.privacy_subject_hmac == deleted_subject_a
                    )
                )
                assert tombstone is not None and tombstone.hmac_key_version == 1
                assert set(
                    db.scalars(
                        select(PrivacyConsentEvent.privacy_subject_hmac).where(
                            PrivacyConsentEvent.privacy_subject_hmac.in_(candidates)
                        )
                    ).all()
                ) == {subject_a}
        finally:
            configure_privacy_hmac_lookup_keys({})
