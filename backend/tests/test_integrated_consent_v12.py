"""Owner decision R3 (2026-09-29): integrated consent FP-013 1.2.0.

RAW and automatic-reporting transfer moved to after the walk ends, so both item
versions and the document version are 1.2.0. A 1.1.0 consent is stale: the
bootstrap asks again (RECONSENT_REQUIRED), new 1.1.0 events are refused, and
report ingest needs a current consent. Mobile-network and training items keep
their versions.
"""

from __future__ import annotations

import importlib
import os
from pathlib import Path
import uuid

from alembic import command
from alembic.config import Config
import pytest
from pydantic import ValidationError
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import sessionmaker

from backend.app.models import PrivacyConsentEvent
from backend.app.schemas import (
    PRIVACY_CONSENT_ITEM_VERSIONS,
    PRIVACY_CONSENT_POLICY_VERSION,
    PrivacyConsentEventV2,
)
from backend.app.services import admin_report_integrity as integrity
from backend.app.services import privacy_lifecycle
from backend.app.services.privacy_lifecycle import (
    PrivacyLifecycleError,
    _consent_receipt_sha256,
    bind_or_verify_privacy_hmac_key,
)

PRIVACY_SECRET = "integrated-consent-v12-test-secret-boundary"
V11_POLICY = "FP-013-1.1.0"
V11_ITEMS = {
    "raw_source_collection": "FP-013-RAW-1.1.0",
    "automatic_reporting": "FP-013-AUTO-1.1.0",
    "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
    "training_reuse": "FP-013-TRAINING-1.1.0",
}
V12_SIGNUP_VERSIONS = {
    "terms_of_service": "walksafe.terms-of-service.v1",
    "privacy_notice": "walksafe.privacy-notice.v1",
    "location_terms": "walksafe.location-terms.v1",
    "raw_original": "FP-013-RAW-1.2.0",
    "automatic_reporting": "FP-013-AUTO-1.2.0",
    "training_reuse": "FP-013-TRAINING-1.1.0",
}
OLD_INGEST_HASH = "37d67daddfca6121cc6cde66b0375561f49395b3f0c33a4afe757dfe7c2ff013"
NEW_INGEST_HASH = "bdb19747b1347dceccd00698dfb88c26e59dfb77b1cdcf4d53baf278bd56d3e2"
MIGRATION = "backend.alembic.versions.202609300006_integrated_consent_v12"


class FakeSession:
    def __init__(self, scalars: list[object | None]) -> None:
        self._scalars = iter(scalars)

    def scalar(self, _statement):
        return next(self._scalars)


@pytest.fixture
def isolated_consent_service(monkeypatch: pytest.MonkeyPatch) -> None:
    for name, value in (
        ("bind_or_verify_privacy_hmac_key", None),
        ("lock_privacy_subject_shared", None),
        ("lock_privacy_subject_exclusive", None),
        ("assert_report_ingest_active", None),
    ):
        monkeypatch.setattr(privacy_lifecycle, name, lambda *_a, _v=value, **_k: _v)
    monkeypatch.setattr(privacy_lifecycle, "privacy_subject_hmac", lambda *_a, **_k: "a" * 64)
    monkeypatch.setattr(
        privacy_lifecycle, "installation_subject_hmac", lambda *_a, **_k: "b" * 64
    )
    monkeypatch.setattr(
        privacy_lifecycle, "_require_rotation_continuity", lambda *_a, **_k: None
    )
    monkeypatch.setattr(
        privacy_lifecycle,
        "privacy_subject_lookup_candidates",
        lambda *_a, **_k: [],
    )


def _v11_event(**updates: object):
    from types import SimpleNamespace

    values = {
        "request_id": "consent_request_v11_0001",
        "privacy_subject_hmac": "a" * 64,
        "account_generation": 1,
        "installation_subject_hmac": "b" * 64,
        "client_revision": 3,
        "subject_revision": 3,
        "policy_version": V11_POLICY,
        "item_versions": dict(V11_ITEMS),
        "raw_source_collection": True,
        "automatic_reporting": True,
        "mobile_network_transfer": False,
        "training_reuse": False,
        "receipt_sha256": "c" * 64,
    }
    values.update(updates)
    return SimpleNamespace(**values)


def test_r3_bumps_only_the_raw_and_automatic_items_and_the_document() -> None:
    assert PRIVACY_CONSENT_POLICY_VERSION == "FP-013-1.2.0"
    assert PRIVACY_CONSENT_ITEM_VERSIONS == {
        "raw_source_collection": "FP-013-RAW-1.2.0",
        "automatic_reporting": "FP-013-AUTO-1.2.0",
        "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
        "training_reuse": "FP-013-TRAINING-1.1.0",
    }
    payload = {
        "schema_version": "walksafe.privacy-consent-event.v2",
        "installation_id": "installation_0001",
        "request_id": "consent_request_0001",
        "client_revision": 1,
        "policy_version": V11_POLICY,
        "item_versions": V11_ITEMS,
        "raw_source_collection": True,
        "automatic_reporting": False,
        "mobile_network_transfer": False,
        "training_reuse": True,
        "expected_previous_backend_receipt_sha256": None,
    }
    with pytest.raises(ValidationError):
        PrivacyConsentEventV2.model_validate(payload)
    for key in ("raw_source_collection", "automatic_reporting"):
        with pytest.raises(ValidationError):
            PrivacyConsentEventV2.model_validate(
                {
                    **payload,
                    "policy_version": PRIVACY_CONSENT_POLICY_VERSION,
                    "item_versions": {**PRIVACY_CONSENT_ITEM_VERSIONS, key: V11_ITEMS[key]},
                }
            )
    assert PrivacyConsentEventV2.model_validate(
        {
            **payload,
            "policy_version": PRIVACY_CONSENT_POLICY_VERSION,
            "item_versions": PRIVACY_CONSENT_ITEM_VERSIONS,
        }
    ).policy_version == "FP-013-1.2.0"


def test_latest_v11_consent_requires_reconsent_and_keeps_only_the_cas_receipt(
    isolated_consent_service: None,
) -> None:
    stale = _v11_event()
    response = privacy_lifecycle.get_consent_bootstrap(
        FakeSession([stale, stale]),  # type: ignore[arg-type]
        actor_id="field.actor",
        account_generation=1,
        installation_id="installation_0001",
        policy_version=PRIVACY_CONSENT_POLICY_VERSION,
        signup_document_versions=V12_SIGNUP_VERSIONS,
        secret=PRIVACY_SECRET,
    )
    assert response.status == "RECONSENT_REQUIRED"
    assert response.source == "NONE"
    assert response.selections is None
    assert response.source_receipt_sha256 is None
    assert response.expected_previous_backend_receipt_sha256 == "c" * 64
    assert response.client_revision_floor == 3
    assert response.policy_version == "FP-013-1.2.0"


def test_v11_signup_optional_documents_require_reconsent(
    isolated_consent_service: None,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from types import SimpleNamespace

    receipt = SimpleNamespace(
        document_versions={
            **V12_SIGNUP_VERSIONS,
            "raw_original": "FP-013-RAW-1.1.0",
            "automatic_reporting": "FP-013-AUTO-1.1.0",
        },
        selections={"raw_original": True, "automatic_reporting": True, "training_reuse": False},
        receipt_sha256="d" * 64,
    )
    account = SimpleNamespace(id=uuid.uuid4())
    response = privacy_lifecycle.get_consent_bootstrap(
        FakeSession([None, None, account, receipt]),  # type: ignore[arg-type]
        actor_id="field.actor",
        account_generation=1,
        installation_id="installation_0001",
        policy_version=PRIVACY_CONSENT_POLICY_VERSION,
        signup_document_versions=V12_SIGNUP_VERSIONS,
        secret=PRIVACY_SECRET,
    )
    assert response.status == "RECONSENT_REQUIRED"
    assert response.selections is None
    current = SimpleNamespace(
        document_versions=dict(V12_SIGNUP_VERSIONS),
        selections=receipt.selections,
        receipt_sha256="d" * 64,
    )
    ready = privacy_lifecycle.get_consent_bootstrap(
        FakeSession([None, None, account, current]),  # type: ignore[arg-type]
        actor_id="field.actor",
        account_generation=1,
        installation_id="installation_0001",
        policy_version=PRIVACY_CONSENT_POLICY_VERSION,
        signup_document_versions=V12_SIGNUP_VERSIONS,
        secret=PRIVACY_SECRET,
    )
    assert ready.status == "READY" and ready.source == "SIGNUP_CONSENT"


def test_v11_event_and_v11_bootstrap_query_are_refused(
    isolated_consent_service: None,
) -> None:
    with pytest.raises(PrivacyLifecycleError) as recorded:
        privacy_lifecycle.record_consent_event(
            FakeSession([]),  # type: ignore[arg-type]
            actor_id="field.actor",
            account_generation=1,
            installation_id="installation_0001",
            request_id="consent_request_0001",
            client_revision=1,
            policy_version=V11_POLICY,
            item_versions=V11_ITEMS,
            raw_source_collection=True,
            automatic_reporting=False,
            mobile_network_transfer=False,
            training_reuse=False,
            secret=PRIVACY_SECRET,
        )
    assert recorded.value.code == "privacy_consent_policy_version_unsupported"
    with pytest.raises(PrivacyLifecycleError) as bootstrap:
        privacy_lifecycle.get_consent_bootstrap(
            FakeSession([]),  # type: ignore[arg-type]
            actor_id="field.actor",
            account_generation=1,
            installation_id="installation_0001",
            policy_version=V11_POLICY,
            signup_document_versions=V12_SIGNUP_VERSIONS,
            secret=PRIVACY_SECRET,
        )
    assert bootstrap.value.status_code == 422


def test_reports_under_a_latest_v11_consent_wait_for_reconsent() -> None:
    with pytest.raises(PrivacyLifecycleError) as refused:
        privacy_lifecycle.assert_report_consent_active(
            FakeSession([_v11_event()]),  # type: ignore[arg-type]
            "a" * 64,
            1,
            automatic_reporting=True,
        )
    assert refused.value.code == "privacy_consent_reconsent_required"
    assert refused.value.status_code == 409
    current = _v11_event(
        policy_version=PRIVACY_CONSENT_POLICY_VERSION,
        item_versions=dict(PRIVACY_CONSENT_ITEM_VERSIONS),
    )
    assert privacy_lifecycle.assert_report_consent_active(
        FakeSession([current]),  # type: ignore[arg-type]
        "a" * 64,
        1,
        automatic_reporting=True,
    ) is current


def test_migration_chain_and_readiness_boundary_follow_v12() -> None:
    migration = importlib.import_module(MIGRATION)
    assert migration.revision == "202609300006"
    assert migration.down_revision == "202609300005"
    assert [count for *_pair, count in migration._VALIDATOR_FRAGMENTS] == [1, 2, 2]
    assert all(
        "1.1.0" in old and "1.2.0" in new and old.replace("1.1.0", "1.2.0") == new
        for old, new, _count in migration._VALIDATOR_FRAGMENTS
        + migration._REPORT_INGEST_FRAGMENTS
    )
    assert "202609300006" in integrity.ADMIN_REPORT_INTEGRITY_COMPATIBLE_REVISIONS
    assert integrity.ADMIN_REPORT_CONSENT_V12_REVISIONS == {"202609300006"}
    queries: list[str] = []

    class Executor:
        def execute(self, statement):
            queries.append(str(statement))

            class Result:
                @staticmethod
                def scalar_one() -> bool:
                    return True

            return Result()

    assert integrity.admin_report_integrity_boundary_state(
        Executor(), revision="202609300006"
    ) is True
    assert NEW_INGEST_HASH in queries[-1] and OLD_INGEST_HASH not in queries[-1]
    assert integrity.admin_report_integrity_boundary_state(
        Executor(), revision="202609300005"
    ) is True
    assert OLD_INGEST_HASH in queries[-1] and NEW_INGEST_HASH not in queries[-1]


# --- PostgreSQL ------------------------------------------------------------------------


def _requires_postgres() -> str:
    url = os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip()
    if not url:
        pytest.skip("WALKSAFE_TEST_DATABASE_URL is not configured")
    return url


def _insert_event(SessionFactory, *, policy: str, items: dict[str, str], revision: int) -> str:
    privacy_subject = "e" * 64
    installation_subject = "f" * 64
    request_id = f"consent_v12_{uuid.uuid4().hex}"
    receipt = _consent_receipt_sha256(
        account_generation=1,
        automatic_reporting=True,
        client_revision=revision,
        installation_subject_hmac=installation_subject,
        item_versions=items,
        mobile_network_transfer=False,
        policy_version=policy,
        privacy_subject_hmac=privacy_subject,
        raw_source_collection=True,
        request_id=request_id,
        subject_revision=revision,
        training_reuse=False,
    )
    with SessionFactory.begin() as db:
        bind_or_verify_privacy_hmac_key(db, secret=PRIVACY_SECRET)
        db.add(
            PrivacyConsentEvent(
                request_id=request_id,
                privacy_subject_hmac=privacy_subject,
                account_generation=1,
                installation_subject_hmac=installation_subject,
                client_revision=revision,
                subject_revision=revision,
                policy_version=policy,
                item_versions=dict(items),
                raw_source_collection=True,
                automatic_reporting=True,
                mobile_network_transfer=False,
                training_reuse=False,
                receipt_sha256=receipt,
            )
        )
    return receipt


def _ingest_hash(connection) -> str:
    return connection.execute(
        text(
            "SELECT pg_catalog.encode(pg_catalog.sha256(pg_catalog.convert_to("
            "pg_catalog.pg_get_functiondef('public.walksafe_enforce_runtime_report_"
            "ingest_integrity()'::regprocedure), 'UTF8')), 'hex')"
        )
    ).scalar_one()


def _runtime_report_insert(connection, login_role: str) -> str | None:
    """Insert one automatic report as a runtime login; returns the SQLSTATE or None."""

    connection.exec_driver_sql(f"SET SESSION AUTHORIZATION {login_role}")
    connection.commit()
    try:
        report_id = uuid.uuid4()
        connection.execute(
            text(
                "INSERT INTO public.reports ("
                "id, class_id, class_name, confidence, bbox_x, bbox_y, "
                "bbox_width, bbox_height, captured_at, source, image_path, "
                "image_content_type, metadata, privacy_subject_hmac, "
                "account_generation) VALUES ("
                ":report_id, 0, 'damaged_tactile_block', 0.9, 0.1, 0.1, "
                "0.5, 0.5, clock_timestamp(), 'android', :image_path, "
                "'image/jpeg', '{\"auto_reported\": true}'::jsonb, :subject, 1)"
            ),
            {
                "report_id": report_id,
                "image_path": f"/uploads/{report_id}.jpg",
                "subject": "e" * 64,
            },
        )
        return None
    except SQLAlchemyError as error:
        return getattr(error.orig, "sqlstate", "unknown")
    finally:
        connection.rollback()
        connection.exec_driver_sql("RESET SESSION AUTHORIZATION")
        connection.commit()


def test_v12_migration_round_trip_on_postgres(monkeypatch: pytest.MonkeyPatch) -> None:
    configured_url = make_url(_requires_postgres())
    database_name = f"walksafe_consent_v12_test_{uuid.uuid4().hex}"
    login_role = f"walksafe_consent_v12_runtime_{uuid.uuid4().hex[:12]}"
    disposable_url = configured_url.set(database=database_name)
    migration_url = disposable_url.render_as_string(hide_password=False)
    admin_engine = create_engine(configured_url, pool_pre_ping=True)
    disposable_engine = create_engine(disposable_url, pool_pre_ping=True)
    quoted_database = admin_engine.dialect.identifier_preparer.quote(database_name)
    created_database = False
    created_role = False
    try:
        with admin_engine.connect().execution_options(isolation_level="AUTOCOMMIT") as c:
            c.exec_driver_sql(f"CREATE DATABASE {quoted_database}")
            created_database = True
            c.exec_driver_sql(
                f"CREATE ROLE {login_role} LOGIN INHERIT NOSUPERUSER NOCREATEDB "
                "NOCREATEROLE NOREPLICATION NOBYPASSRLS"
            )
            created_role = True
            c.exec_driver_sql(
                f"GRANT walksafe_backend_runtime TO {login_role} "
                "WITH SET TRUE, INHERIT TRUE"
            )
        monkeypatch.setenv("DATABASE_URL", migration_url)
        monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", migration_url)
        config = Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))
        SessionFactory = sessionmaker(bind=disposable_engine)

        command.upgrade(config, "202609300005")
        _insert_event(SessionFactory, policy=V11_POLICY, items=V11_ITEMS, revision=1)
        with disposable_engine.connect() as connection:
            assert _ingest_hash(connection) == OLD_INGEST_HASH
            assert _runtime_report_insert(connection, login_role) is None

        command.upgrade(config, "head")
        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM public.alembic_version")
            ).scalar_one() == "202609300009"
            assert _ingest_hash(connection) == NEW_INGEST_HASH
            assert integrity.admin_report_integrity_boundary_state(connection) is True
            # The latest consent is 1.1.0: reports wait for the 1.2.0 reconsent.
            assert _runtime_report_insert(connection, login_role) == "23514"
        with pytest.raises(SQLAlchemyError) as stale_insert:
            _insert_event(SessionFactory, policy=V11_POLICY, items=V11_ITEMS, revision=2)
        assert getattr(stale_insert.value.orig, "sqlstate", None) == "23514"

        # Without v1.2 evidence the downgrade restores the exact predecessor.
        command.downgrade(config, "202609300005")
        with disposable_engine.connect() as connection:
            assert _ingest_hash(connection) == OLD_INGEST_HASH
            assert integrity.admin_report_integrity_boundary_state(connection) is True
        command.upgrade(config, "head")

        _insert_event(
            SessionFactory,
            policy=PRIVACY_CONSENT_POLICY_VERSION,
            items=PRIVACY_CONSENT_ITEM_VERSIONS,
            revision=2,
        )
        with disposable_engine.connect() as connection:
            assert _runtime_report_insert(connection, login_role) is None
        # A downgrade never deletes or refuses v1.2 evidence: the v1.1 functions come back
        # byte-for-byte (v1.2 is then stale and fails closed) and the CHECK keeps v1.2 rows.
        command.downgrade(config, "202609300005")
        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM public.alembic_version")
            ).scalar_one() == "202609300005"
            assert _ingest_hash(connection) == OLD_INGEST_HASH
            assert integrity.admin_report_integrity_boundary_state(connection) is True
            assert connection.execute(
                text(
                    "SELECT count(*) FROM public.privacy_consent_events "
                    "WHERE policy_version = 'FP-013-1.2.0'"
                )
            ).scalar_one() == 1
            assert "FP-013-1.2.0" in connection.execute(
                text(
                    "SELECT pg_catalog.pg_get_constraintdef(oid) FROM pg_catalog.pg_constraint "
                    "WHERE conname = 'ck_privacy_consent_approved_versions'"
                )
            ).scalar_one()
            assert _runtime_report_insert(connection, login_role) == "23514"
        with pytest.raises(SQLAlchemyError) as predecessor_insert:
            _insert_event(
                SessionFactory,
                policy=PRIVACY_CONSENT_POLICY_VERSION,
                items=PRIVACY_CONSENT_ITEM_VERSIONS,
                revision=3,
            )
        assert getattr(predecessor_insert.value.orig, "sqlstate", None) == "23514"
        command.upgrade(config, "head")
        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM public.alembic_version")
            ).scalar_one() == "202609300009"
            assert _ingest_hash(connection) == NEW_INGEST_HASH
            assert integrity.admin_report_integrity_boundary_state(connection) is True
            assert _runtime_report_insert(connection, login_role) is None
    finally:
        disposable_engine.dispose()
        with admin_engine.connect().execution_options(isolation_level="AUTOCOMMIT") as c:
            if created_database:
                c.execute(
                    text(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                        "WHERE datname = :name AND pid <> pg_backend_pid()"
                    ),
                    {"name": database_name},
                ).all()
                c.exec_driver_sql(f"DROP DATABASE IF EXISTS {quoted_database}")
            if created_role:
                c.exec_driver_sql(f"DROP ROLE IF EXISTS {login_role}")
        admin_engine.dispose()
