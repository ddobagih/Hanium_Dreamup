"""Keep backend tests independent from an operator's ignored field-test .env.

Database integration tests run only when ``WALKSAFE_TEST_DATABASE_URL`` points
to an explicitly test-named database.  All other test processes receive an
unreachable sentinel URL, so importing the application can never fall back to
the field database from ``backend/.env``.
"""

from __future__ import annotations

import base64
from collections.abc import Callable, Iterator
import json
import os
from pathlib import Path
import tempfile
from urllib.parse import unquote, urlsplit

import pytest
from sqlalchemy import create_engine, text


_PROTECTED_DATABASE_NAMES = frozenset({"postgres", "template0", "template1", "walksafe"})
_UNCONFIGURED_TEST_DATABASE_URL = (
    "postgresql+psycopg://walksafe_test:disabled@127.0.0.1:1/"
    "walksafe_pytest_unconfigured"
)
_ADMIN_SECURITY_CLEANUP_TABLES = (
    "walksafe_recovery_custody_markers",
    "walksafe_recovery_custody_capabilities",
    "admin_security_audits",
    "admin_security_auth_attempts",
    "admin_security_reconfirmations",
    "admin_security_recovery_transactions",
    "admin_security_recovery_codes",
    "admin_security_sessions",
    "admin_security_controls",
)
_REPORT_IMAGE_CLEANUP_TABLES = (
    "report_original_access_audits",
    "report_original_access_grants",
    "report_image_objects",
    "report_image_keyring_events",
)
_FP008_CLEANUP_TABLES = (
    "report_institution_delivery_events",
    "report_delivery_packages",
    "report_review_decisions",
    "admin_device_proof_challenges",
    "admin_device_keys",
)
_CRITICAL_INCIDENT_CLEANUP_TABLES = (
    "critical_incident_events",
    "critical_incidents",
)
_PRIVACY_CLEANUP_TABLES = (
    "training_dataset_lifecycle_events",
    "training_dataset_members",
    "training_dataset_revisions",
    "training_artifact_deletion_receipts",
    "approved_training_artifacts",
    "raw_collection_deletion_receipts",
    "raw_collection_legal_hold_events",
    "raw_collection_purpose_decisions",
    "raw_collection_chunks",
    "raw_collection_objects",
    "raw_collections",
    "account_deletion_events",
    "account_deletion_device_targets",
    "account_deletion_items",
    "account_deletion_requests",
    "account_deletion_receipts",
    "account_deletion_tombstones",
    "privacy_consent_events",
    "privacy_hmac_key_bindings",
    "signup_consent_receipts",
    "account_enrollments",
    "user_accounts",
)


def _database_name(database_url: str) -> str:
    parsed = urlsplit(database_url.replace("postgresql+psycopg://", "postgresql://", 1))
    name = unquote(parsed.path).lstrip("/")
    if not name:
        raise pytest.UsageError("WALKSAFE_TEST_DATABASE_URL must include a database name")
    return name


def _validated_test_database_url(raw_url: str | None) -> str | None:
    if raw_url is None or not raw_url.strip():
        return None
    database_url = raw_url.strip()
    parsed = urlsplit(database_url)
    if parsed.scheme.lower() != "postgresql+psycopg":
        raise pytest.UsageError("WALKSAFE_TEST_DATABASE_URL must use postgresql+psycopg")
    name = _database_name(database_url).lower()
    if name in _PROTECTED_DATABASE_NAMES or "test" not in name:
        raise pytest.UsageError(
            "WALKSAFE_TEST_DATABASE_URL must use a dedicated database whose name contains 'test'; "
            f"refusing database {name!r}"
        )
    operator_url = os.environ.get("DATABASE_URL", "").strip()
    if operator_url:
        try:
            operator_name = _database_name(operator_url).lower()
        except pytest.UsageError as exc:
            raise pytest.UsageError("operator DATABASE_URL must identify a database safely") from exc
        if operator_name == name:
            raise pytest.UsageError(
                "WALKSAFE_TEST_DATABASE_URL database name must differ from the operator DATABASE_URL; "
                "URL scheme, host aliases, credentials, and query strings do not establish isolation"
            )
    return database_url


_TEST_DATABASE_URL = _validated_test_database_url(os.environ.get("WALKSAFE_TEST_DATABASE_URL"))
os.environ["DATABASE_URL"] = _TEST_DATABASE_URL or _UNCONFIGURED_TEST_DATABASE_URL

_UPLOAD_TMP = tempfile.TemporaryDirectory(prefix="walksafe-pytest-uploads-")
os.environ["UPLOAD_DIR"] = str(Path(_UPLOAD_TMP.name).resolve())
_REPORT_IMAGE_KEY_TMP = tempfile.TemporaryDirectory(prefix="walksafe-pytest-report-image-key-")
_REPORT_IMAGE_KEY_FILE = Path(_REPORT_IMAGE_KEY_TMP.name) / "keyring.json"
_REPORT_IMAGE_TEST_KEY = base64.urlsafe_b64encode(b"walksafe-test-report-image-key!!").decode("ascii").rstrip("=")
_REPORT_IMAGE_KEY_FILE.write_text(
    json.dumps(
        {
            "generation": 1,
            "keys": [
                {
                    "id": "pytest-report-image-key-v1",
                    "material": _REPORT_IMAGE_TEST_KEY,
                    "state": "active",
                }
            ],
            "previous_manifest_sha256": None,
            "schema": "walksafe.report-image-keyring.v1",
        },
        separators=(",", ":"),
        sort_keys=True,
    ),
    encoding="utf-8",
)
_REPORT_IMAGE_KEY_FILE.chmod(0o400)
os.environ["WALKSAFE_REPORT_IMAGE_KEY_PROVIDER"] = "secret_file"
os.environ["WALKSAFE_REPORT_IMAGE_KEY_FILE"] = str(_REPORT_IMAGE_KEY_FILE.resolve())
os.environ["WALKSAFE_REPORT_IMAGE_KMS_AGENT_SOCKET"] = ""
os.environ["WALKSAFE_REPORT_ORIGINAL_GRANT_TTL_SECONDS"] = "120"
os.environ["WALKSAFE_DATABASE_AT_REST_ENCRYPTION_CONFIRMED"] = "true"
os.environ["WALKSAFE_DATABASE_TRANSPORT_SECURITY_CONFIRMED"] = "true"
os.environ["WALKSAFE_DATABASE_ENCRYPTION_KEY_BOUNDARY"] = "pytest-database-boundary"
os.environ["WALKSAFE_REPORT_IMAGE_KEY_BOUNDARY"] = "pytest-report-image-boundary"


os.environ["DETECT_V2_MODE"] = "fake"
os.environ["DETECT_V2_IMAGE_SIZE"] = "768"
os.environ["DETECT_V2_CUSTOM_TACTILE_MODEL_PATH"] = ""
os.environ["DETECT_V2_COCO_MODEL_PATH"] = ""
os.environ["DETECT_V2_UNIFIED_MODEL_PATH"] = ""
os.environ["DETECT_V2_RUNTIME_CONFIG_PATH"] = ""
os.environ["WALKSAFE_FIELD_TEST_SECURITY_ENABLED"] = "false"
os.environ["WALKSAFE_RAW_INGEST_ENABLED"] = "false"
os.environ["WALKSAFE_RAW_OBJECT_DIR"] = ""
os.environ["WALKSAFE_ALLOW_INSECURE_LOCAL_DEV"] = "true"
os.environ["WALKSAFE_ENVIRONMENT"] = "test"
os.environ["WALKSAFE_SOURCE_COMMIT"] = ""
os.environ["WALKSAFE_FIELD_TEST_TOKEN"] = ""
os.environ["WALKSAFE_ADMIN_TOKEN"] = ""
os.environ["WALKSAFE_PRIVACY_HMAC_SECRET"] = (
    "walksafe-pytest-privacy-hmac-secret-boundary-v2"
)
os.environ["WALKSAFE_ACCOUNT_EMAIL_ENCRYPTION_KEY_B64"] = (
    base64.urlsafe_b64encode(b"E" * 32).decode("ascii").rstrip("=")
)
os.environ["WALKSAFE_ACCOUNT_EMAIL_LOOKUP_HMAC_KEY_B64"] = (
    base64.urlsafe_b64encode(b"L" * 32).decode("ascii").rstrip("=")
)
os.environ["WALKSAFE_ACCOUNT_OTP_HMAC_KEY_B64"] = (
    base64.urlsafe_b64encode(b"O" * 32).decode("ascii").rstrip("=")
)


@pytest.fixture(scope="session", autouse=True)
def clean_test_storage() -> Iterator[Callable[[], None] | None]:
    """Start and finish with an empty, explicitly isolated report table."""

    if _TEST_DATABASE_URL is None:
        yield None
        _UPLOAD_TMP.cleanup()
        _REPORT_IMAGE_KEY_TMP.cleanup()
        return

    engine = create_engine(_TEST_DATABASE_URL, pool_pre_ping=True)
    lock_connection = engine.connect()
    lock_acquired = bool(
        lock_connection.execute(
            text("SELECT pg_try_advisory_lock(hashtextextended('walksafe-pytest-exclusive', 0))")
        ).scalar_one()
    )
    if not lock_acquired:
        lock_connection.close()
        engine.dispose()
        pytest.fail(
            "WALKSAFE_TEST_DATABASE_URL is already in use by another test process",
            pytrace=False,
        )

    def truncate_reports_if_present() -> None:
        with engine.begin() as connection:
            actual_name = connection.execute(text("SELECT current_database()")).scalar_one()
            if actual_name.lower() in _PROTECTED_DATABASE_NAMES or "test" not in actual_name.lower():
                raise RuntimeError(f"refusing to clean non-test database {actual_name!r}")
            if connection.execute(text("SELECT to_regclass('public.reports')")).scalar_one() is not None:
                report_tables = [
                    table_name
                    for table_name in (
                        "report_deletion_external_copy_events",
                        "report_deletion_external_copy_states",
                        "report_deletion_tombstones",
                        "report_deletion_legal_holds",
                        "report_content_revisions",
                        "report_user_request_status_events",
                        "report_user_requests",
                        "report_image_objects",
                        "report_original_access_grants",
                        "reports",
                    )
                    if connection.execute(
                        text("SELECT to_regclass(:table_name)"),
                        {"table_name": table_name},
                    ).scalar_one()
                    is not None
                ]
                guarded_tables = {
                    "report_content_revisions",
                    "report_deletion_external_copy_events",
                    "report_deletion_external_copy_states",
                    "report_deletion_tombstones",
                }.intersection(report_tables)
                for table_name in guarded_tables:
                    connection.execute(
                        text(f"ALTER TABLE {table_name} DISABLE TRIGGER USER")
                    )
                connection.execute(
                    text("TRUNCATE TABLE " + ", ".join(report_tables))
                )
                for table_name in guarded_tables:
                    connection.execute(
                        text(f"ALTER TABLE {table_name} ENABLE TRIGGER USER")
                    )
            privacy_tables = [
                table_name
                for table_name in _PRIVACY_CLEANUP_TABLES
                if connection.execute(
                    text("SELECT to_regclass(:table_name)"),
                    {"table_name": table_name},
                ).scalar_one()
                is not None
            ]
            if privacy_tables:
                for table_name in privacy_tables:
                    connection.execute(
                        text(f"ALTER TABLE {table_name} DISABLE TRIGGER USER")
                    )
                connection.execute(
                    text("TRUNCATE TABLE " + ", ".join(privacy_tables))
                )
                for table_name in privacy_tables:
                    connection.execute(
                        text(f"ALTER TABLE {table_name} ENABLE TRIGGER USER")
                    )
            cleanup_tables = [
                    table_name
                    for table_name in (
                        "admin_report_mutation_claims",
                        *_FP008_CLEANUP_TABLES,
                    *_CRITICAL_INCIDENT_CLEANUP_TABLES,
                    *_REPORT_IMAGE_CLEANUP_TABLES,
                        *_ADMIN_SECURITY_CLEANUP_TABLES,
                        "admin_operation_audits",
                        "report_export_audits",
                    "report_status_audits",
                    "report_read_audits",
                    "actor_rate_limit_events",
                )
                if connection.execute(
                    text("SELECT to_regclass(:table_name)"),
                    {"table_name": table_name},
                ).scalar_one()
                is not None
            ]
            for table_name in cleanup_tables:
                # Test cleanup is the only controlled bypass for append-only
                # audit tables. Mutable control/rate-limit tables have no
                # mutation trigger, so disabling USER triggers is harmless.
                connection.execute(text(f"ALTER TABLE {table_name} DISABLE TRIGGER USER"))
            if cleanup_tables:
                # PostgreSQL requires a referenced parent and every referencing
                # child to be truncated by the same statement even when the
                # child is already empty.
                connection.execute(
                    text("TRUNCATE TABLE " + ", ".join(cleanup_tables))
                )
            for table_name in cleanup_tables:
                connection.execute(text(f"ALTER TABLE {table_name} ENABLE TRIGGER USER"))

    try:
        truncate_reports_if_present()
        yield truncate_reports_if_present
    finally:
        try:
            truncate_reports_if_present()
        finally:
            try:
                lock_connection.execute(
                    text("SELECT pg_advisory_unlock(hashtextextended('walksafe-pytest-exclusive', 0))")
                )
            finally:
                lock_connection.close()
                engine.dispose()
                _UPLOAD_TMP.cleanup()
                _REPORT_IMAGE_KEY_TMP.cleanup()


@pytest.fixture(scope="module", autouse=True)
def clean_test_storage_before_module(
    clean_test_storage: Callable[[], None] | None,
) -> Iterator[None]:
    """Keep PostgreSQL integration modules independent in one pytest process."""

    if clean_test_storage is not None:
        clean_test_storage()
    yield
