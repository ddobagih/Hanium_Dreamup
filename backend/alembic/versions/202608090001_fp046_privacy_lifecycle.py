"""add FP-046 consent and account deletion lifecycle

Revision ID: 202608090001
Revises: 202608080001
Create Date: 2026-08-09
"""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import re

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608090001"
down_revision = "202608080001"
branch_labels = None
depends_on = None


_RUNTIME_APP_TABLES = (
    "reports",
    "report_export_audits",
    "report_status_audits",
    "report_read_audits",
    "actor_rate_limit_events",
    "report_image_objects",
    "report_original_access_grants",
    "report_original_access_audits",
    "report_image_keyring_events",
    "admin_security_controls",
    "admin_security_sessions",
    "admin_security_reconfirmations",
    "admin_security_recovery_codes",
    "admin_security_recovery_transactions",
    "admin_security_auth_attempts",
    "admin_security_audits",
    "admin_device_keys",
    "admin_device_proof_challenges",
    "report_review_decisions",
    "report_institution_delivery_events",
    "privacy_hmac_key_bindings",
    "privacy_consent_events",
    "account_deletion_tombstones",
    "account_deletion_requests",
    "account_deletion_items",
    "account_deletion_device_targets",
    "account_deletion_events",
    "account_deletion_receipts",
)
_RUNTIME_PREDECESSOR_TABLES = _RUNTIME_APP_TABLES[:20]
_RUNTIME_APP_SEQUENCES = (
    "actor_rate_limit_events_id_seq",
    "admin_security_auth_attempts_id_seq",
)
_RUNTIME_UPDATE_TABLES = (
    "reports",
    "report_original_access_grants",
    "admin_security_controls",
    "admin_security_sessions",
    "admin_security_recovery_codes",
    "admin_security_recovery_transactions",
    "admin_device_keys",
    "admin_device_proof_challenges",
    "account_deletion_requests",
    "account_deletion_items",
    "account_deletion_device_targets",
)
_RUNTIME_DELETE_TABLES = ("actor_rate_limit_events",)
_RUNTIME_ACL_BASELINE_TABLE = "walksafe_fp046_runtime_acl_baseline"
_LEGACY_NAMED_ACTOR_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")
_LEGACY_UNBOUND_ACTORS = frozenset({"unknown", "system", "anonymous"})


def _legacy_privacy_subject_hmac(actor_id: str, secret: str) -> str:
    canonical_subject = json.dumps(
        {"account_generation": 1, "actor_id": actor_id},
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")
    return hmac.new(
        secret.encode("utf-8"),
        b"walksafe/privacy-subject/v2\0" + canonical_subject,
        hashlib.sha256,
    ).hexdigest()


def _legacy_actor_is_unbound(actor_id: object) -> bool:
    return (
        isinstance(actor_id, str)
        and actor_id.casefold() in _LEGACY_UNBOUND_ACTORS
    )


def _legacy_privacy_key_fingerprint(secret: str) -> str:
    return hashlib.sha256(
        b"walksafe/privacy-hmac-key-fingerprint/v1\0" + secret.encode("utf-8")
    ).hexdigest()


def _backfill_legacy_report_bindings() -> None:
    """Bind trusted legacy gateway actors before removing raw identity metadata."""

    connection = op.get_bind()
    legacy_rows = connection.execute(
        sa.text(
            "SELECT id, metadata ->> 'ingested_by_actor_id' AS actor_id "
            "FROM reports WHERE metadata ? 'ingested_by_actor_id'"
        )
    ).mappings().all()
    named_rows: list[tuple[object, str]] = []
    for row in legacy_rows:
        actor_id = row["actor_id"]
        if _legacy_actor_is_unbound(actor_id):
            continue
        if not isinstance(actor_id, str) or _LEGACY_NAMED_ACTOR_PATTERN.fullmatch(actor_id) is None:
            raise RuntimeError("legacy report contains an invalid gateway actor identifier")
        named_rows.append((row["id"], actor_id))

    if named_rows:
        secret = os.environ.get("WALKSAFE_PRIVACY_HMAC_SECRET", "").strip()
        if len(secret.encode("utf-8")) < 32:
            raise RuntimeError(
                "WALKSAFE_PRIVACY_HMAC_SECRET is required to bind legacy named-actor reports"
            )
        raw_key_version = os.environ.get("WALKSAFE_PRIVACY_HMAC_KEY_VERSION", "1").strip()
        try:
            key_version = int(raw_key_version)
        except ValueError as exc:
            raise RuntimeError(
                "WALKSAFE_PRIVACY_HMAC_KEY_VERSION must be a positive integer"
            ) from exc
        if key_version < 1 or key_version > 9_223_372_036_854_775_807:
            raise RuntimeError("WALKSAFE_PRIVACY_HMAC_KEY_VERSION must be a positive integer")
        connection.execute(
            sa.text(
                "INSERT INTO privacy_hmac_key_bindings "
                "(binding_id, key_version, secret_fingerprint) "
                "VALUES (1, :key_version, :secret_fingerprint)"
            ),
            {
                "key_version": key_version,
                "secret_fingerprint": _legacy_privacy_key_fingerprint(secret),
            },
        )
        connection.execute(
            sa.text(
                "UPDATE reports SET privacy_subject_hmac = :privacy_subject_hmac, "
                "account_generation = 1 WHERE id = :report_id"
            ),
            [
                {
                    "report_id": report_id,
                    "privacy_subject_hmac": _legacy_privacy_subject_hmac(actor_id, secret),
                }
                for report_id, actor_id in named_rows
            ],
        )

    connection.execute(
        sa.text(
            "UPDATE reports "
            "SET metadata = metadata - 'ingested_by_actor_id' - 'reporter_user_id' "
            "WHERE metadata ?| ARRAY['ingested_by_actor_id', 'reporter_user_id']"
        )
    )


def _append_only(table_name: str) -> None:
    op.execute(
        f"CREATE TRIGGER {table_name}_append_only "
        f"BEFORE UPDATE OR DELETE OR TRUNCATE ON {table_name} "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )


def _revoke_runtime_extension_object_acl() -> None:
    """Remove pre-existing direct runtime ACLs without touching extension defaults."""

    op.execute(
        """
        DO $$
        DECLARE
          object_schema text;
          object_name text;
          object_kind "char";
        BEGIN
          FOR object_schema, object_name, object_kind IN
            SELECT DISTINCT namespace.nspname, class.relname, class.relkind
            FROM pg_class AS class
            JOIN pg_namespace AS namespace ON namespace.oid = class.relnamespace
            JOIN pg_depend AS dependency
              ON dependency.classid = 'pg_class'::regclass
             AND dependency.objid = class.oid
             AND dependency.objsubid = 0
             AND dependency.refclassid = 'pg_extension'::regclass
             AND dependency.deptype = 'e'
            CROSS JOIN LATERAL aclexplode(class.relacl) AS acl
            WHERE namespace.nspname = 'public'
              AND class.relkind IN ('r', 'p', 'S', 'v', 'm', 'f')
              AND acl.grantee = 'walksafe_backend_runtime'::regrole
          LOOP
            IF object_kind = 'S' THEN
              EXECUTE format(
                'REVOKE ALL PRIVILEGES ON SEQUENCE %I.%I FROM walksafe_backend_runtime',
                object_schema,
                object_name
              );
            ELSE
              EXECUTE format(
                'REVOKE ALL PRIVILEGES ON TABLE %I.%I FROM walksafe_backend_runtime',
                object_schema,
                object_name
              );
            END IF;
          END LOOP;
          IF EXISTS (
            SELECT 1
            FROM pg_class AS class
            JOIN pg_namespace AS namespace ON namespace.oid = class.relnamespace
            JOIN pg_depend AS dependency
              ON dependency.classid = 'pg_class'::regclass
             AND dependency.objid = class.oid
             AND dependency.objsubid = 0
             AND dependency.refclassid = 'pg_extension'::regclass
             AND dependency.deptype = 'e'
            CROSS JOIN LATERAL aclexplode(class.relacl) AS acl
            WHERE namespace.nspname = 'public'
              AND class.relkind IN ('r', 'p', 'S', 'v', 'm', 'f')
              AND acl.grantee = 'walksafe_backend_runtime'::regrole
          ) THEN
            RAISE EXCEPTION 'runtime extension-object ACL could not be revoked'
              USING ERRCODE = '42501';
          END IF;
        END;
        $$
        """
    )


def _snapshot_runtime_predecessor_acl() -> None:
    """Preserve the direct runtime ACL that existed before FP-046."""

    op.create_table(
        _RUNTIME_ACL_BASELINE_TABLE,
        sa.Column("object_kind", sa.String(length=16), nullable=False),
        sa.Column("object_schema", sa.String(length=63), nullable=False),
        sa.Column("object_name", sa.String(length=63), nullable=False),
        sa.Column("privilege_type", sa.String(length=32), nullable=False),
        sa.Column("is_grantable", sa.Boolean(), nullable=False),
        sa.CheckConstraint(
            "object_kind IN ('TABLE', 'SEQUENCE', 'SCHEMA')",
            name="ck_fp046_runtime_acl_baseline_kind",
        ),
        sa.PrimaryKeyConstraint(
            "object_kind",
            "object_schema",
            "object_name",
            "privilege_type",
            name="pk_fp046_runtime_acl_baseline",
        ),
    )
    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE public."
        + _RUNTIME_ACL_BASELINE_TABLE
        + " FROM PUBLIC, walksafe_backend_runtime, walksafe_receipt_purger"
    )
    predecessor_relations = ", ".join(
        f"'{table_name}'" for table_name in (*_RUNTIME_PREDECESSOR_TABLES, "alembic_version")
    )
    predecessor_sequences = ", ".join(
        f"'{sequence_name}'" for sequence_name in _RUNTIME_APP_SEQUENCES
    )
    op.execute(
        f"""
        INSERT INTO public.{_RUNTIME_ACL_BASELINE_TABLE}
          (object_kind, object_schema, object_name, privilege_type, is_grantable)
        SELECT
          CASE WHEN class.relkind = 'S' THEN 'SEQUENCE' ELSE 'TABLE' END,
          namespace.nspname,
          class.relname,
          acl.privilege_type,
          bool_or(acl.is_grantable)
        FROM pg_class AS class
        JOIN pg_namespace AS namespace ON namespace.oid = class.relnamespace
        CROSS JOIN LATERAL aclexplode(class.relacl) AS acl
        WHERE namespace.nspname = 'public'
          AND (
            (class.relkind IN ('r', 'p')
             AND class.relname IN ({predecessor_relations}))
            OR (class.relkind = 'S'
                AND class.relname IN ({predecessor_sequences}))
          )
          AND acl.grantee = 'walksafe_backend_runtime'::regrole
        GROUP BY class.relkind, namespace.nspname, class.relname, acl.privilege_type
        UNION ALL
        SELECT
          'SCHEMA',
          namespace.nspname,
          namespace.nspname,
          acl.privilege_type,
          bool_or(acl.is_grantable)
        FROM pg_namespace AS namespace
        CROSS JOIN LATERAL aclexplode(namespace.nspacl) AS acl
        WHERE namespace.nspname = 'public'
          AND acl.grantee = 'walksafe_backend_runtime'::regrole
        GROUP BY namespace.nspname, acl.privilege_type
        """
    )


def _reject_lossy_downgrade() -> None:
    op.execute(
        """
        DO $$
        BEGIN
          IF EXISTS (
            SELECT 1 FROM reports
            WHERE privacy_subject_hmac IS NOT NULL
               OR account_generation IS NOT NULL
          )
          OR EXISTS (SELECT 1 FROM privacy_hmac_key_bindings)
          OR EXISTS (SELECT 1 FROM privacy_consent_events)
          OR EXISTS (SELECT 1 FROM account_deletion_tombstones)
          OR EXISTS (SELECT 1 FROM account_deletion_requests)
          OR EXISTS (SELECT 1 FROM account_deletion_items)
          OR EXISTS (SELECT 1 FROM account_deletion_device_targets)
          OR EXISTS (SELECT 1 FROM account_deletion_events)
          OR EXISTS (SELECT 1 FROM account_deletion_receipts) THEN
            RAISE EXCEPTION
              'cannot downgrade FP-046 while pseudonymous privacy lifecycle data exists'
              USING ERRCODE = '55000';
          END IF;
        END;
        $$
        """
    )


def _restore_runtime_predecessor_acl() -> None:
    predecessor_relation_names = ", ".join(
        f"'{table_name}'"
        for table_name in (*_RUNTIME_PREDECESSOR_TABLES, "alembic_version")
    )
    predecessor_sequence_names = ", ".join(
        f"'{sequence_name}'" for sequence_name in _RUNTIME_APP_SEQUENCES
    )
    predecessor_relations = ", ".join(
        f"public.{table_name}"
        for table_name in (*_RUNTIME_PREDECESSOR_TABLES, "alembic_version")
    )
    predecessor_sequences = ", ".join(
        f"public.{sequence_name}" for sequence_name in _RUNTIME_APP_SEQUENCES
    )
    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE "
        + predecessor_relations
        + " FROM walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL PRIVILEGES ON SEQUENCE "
        + predecessor_sequences
        + " FROM walksafe_backend_runtime"
    )
    op.execute("REVOKE USAGE, CREATE ON SCHEMA public FROM walksafe_backend_runtime")
    op.execute(
        f"""
        DO $$
        DECLARE
          baseline record;
          grant_suffix text;
        BEGIN
          IF to_regclass('public.{_RUNTIME_ACL_BASELINE_TABLE}') IS NULL THEN
            RAISE EXCEPTION 'FP-046 runtime ACL baseline is missing'
              USING ERRCODE = '55000';
          END IF;
          FOR baseline IN
            SELECT object_kind, object_schema, object_name,
                   privilege_type, is_grantable
            FROM public.{_RUNTIME_ACL_BASELINE_TABLE}
            ORDER BY object_kind, object_schema, object_name, privilege_type
          LOOP
            grant_suffix := CASE
              WHEN baseline.is_grantable THEN ' WITH GRANT OPTION'
              ELSE ''
            END;
            IF baseline.object_kind = 'SCHEMA' THEN
              EXECUTE format(
                'GRANT %s ON SCHEMA %I TO walksafe_backend_runtime%s',
                baseline.privilege_type,
                baseline.object_name,
                grant_suffix
              );
            ELSIF baseline.object_kind = 'SEQUENCE' THEN
              EXECUTE format(
                'GRANT %s ON SEQUENCE %I.%I TO walksafe_backend_runtime%s',
                baseline.privilege_type,
                baseline.object_schema,
                baseline.object_name,
                grant_suffix
              );
            ELSE
              EXECUTE format(
                'GRANT %s ON TABLE %I.%I TO walksafe_backend_runtime%s',
                baseline.privilege_type,
                baseline.object_schema,
                baseline.object_name,
                grant_suffix
              );
            END IF;
          END LOOP;
        END;
        $$
        """
    )
    actual_rows = {
        tuple(row)
        for row in op.get_bind().execute(
            sa.text(
                f"""
                SELECT
                  CASE WHEN class.relkind = 'S' THEN 'SEQUENCE' ELSE 'TABLE' END,
                  namespace.nspname,
                  class.relname,
                  acl.privilege_type,
                  bool_or(acl.is_grantable)
                FROM pg_class AS class
                JOIN pg_namespace AS namespace
                  ON namespace.oid = class.relnamespace
                CROSS JOIN LATERAL aclexplode(class.relacl) AS acl
                WHERE namespace.nspname = 'public'
                  AND (
                    (class.relkind IN ('r', 'p')
                     AND class.relname IN ({predecessor_relation_names}))
                    OR (class.relkind = 'S'
                        AND class.relname IN ({predecessor_sequence_names}))
                  )
                  AND acl.grantee = 'walksafe_backend_runtime'::regrole
                GROUP BY class.relkind, namespace.nspname, class.relname,
                         acl.privilege_type
                UNION ALL
                SELECT
                  'SCHEMA',
                  namespace.nspname,
                  namespace.nspname,
                  acl.privilege_type,
                  bool_or(acl.is_grantable)
                FROM pg_namespace AS namespace
                CROSS JOIN LATERAL aclexplode(namespace.nspacl) AS acl
                WHERE namespace.nspname = 'public'
                  AND acl.grantee = 'walksafe_backend_runtime'::regrole
                GROUP BY namespace.nspname, acl.privilege_type
                """
            )
        )
    }
    expected_rows = {
        tuple(row)
        for row in op.get_bind().execute(
            sa.text(
                f"SELECT object_kind, object_schema, object_name, "
                f"privilege_type, is_grantable "
                f"FROM public.{_RUNTIME_ACL_BASELINE_TABLE}"
            )
        )
    }
    if actual_rows != expected_rows:
        raise RuntimeError("FP-046 runtime ACL baseline could not be restored exactly")


def upgrade() -> None:
    op.add_column(
        "reports",
        sa.Column("privacy_subject_hmac", sa.String(length=64), nullable=True),
    )
    op.add_column(
        "reports",
        sa.Column("account_generation", sa.BigInteger(), nullable=True),
    )
    op.create_check_constraint(
        "ck_reports_privacy_subject_binding",
        "reports",
        "(privacy_subject_hmac IS NULL AND account_generation IS NULL) OR "
        "(privacy_subject_hmac IS NOT NULL AND account_generation IS NOT NULL "
        "AND privacy_subject_hmac ~ '^[0-9a-f]{64}$' AND account_generation >= 1)",
    )
    op.create_index(
        "ix_reports_privacy_subject_generation",
        "reports",
        ["privacy_subject_hmac", "account_generation"],
    )
    op.create_table(
        "privacy_hmac_key_bindings",
        sa.Column("binding_id", sa.SmallInteger(), nullable=False),
        sa.Column("key_version", sa.BigInteger(), nullable=False),
        sa.Column("secret_fingerprint", sa.String(length=64), nullable=False),
        sa.Column(
            "bound_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "binding_id = 1",
            name="ck_privacy_hmac_binding_singleton",
        ),
        sa.CheckConstraint(
            "key_version >= 1",
            name="ck_privacy_hmac_binding_version",
        ),
        sa.CheckConstraint(
            "secret_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_privacy_hmac_binding_fingerprint",
        ),
        sa.PrimaryKeyConstraint("binding_id"),
    )
    _append_only("privacy_hmac_key_bindings")
    _backfill_legacy_report_bindings()

    op.create_table(
        "privacy_consent_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("request_id", sa.String(length=128), nullable=False),
        sa.Column("privacy_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("account_generation", sa.BigInteger(), nullable=False),
        sa.Column("installation_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("client_revision", sa.BigInteger(), nullable=False),
        sa.Column("subject_revision", sa.BigInteger(), nullable=False),
        sa.Column("policy_version", sa.String(length=64), nullable=False),
        sa.Column("item_versions", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("raw_source_collection", sa.Boolean(), nullable=False),
        sa.Column("automatic_reporting", sa.Boolean(), nullable=False),
        sa.Column("mobile_network_transfer", sa.Boolean(), nullable=False),
        sa.Column("training_reuse", sa.Boolean(), nullable=False),
        sa.Column("receipt_sha256", sa.String(length=64), nullable=False),
        sa.Column(
            "recorded_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "request_id ~ '^[A-Za-z0-9_-]{16,128}$'",
            name="ck_privacy_consent_request_id",
        ),
        sa.CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_privacy_consent_subject_hmac",
        ),
        sa.CheckConstraint(
            "account_generation >= 1 AND client_revision >= 1 AND subject_revision >= 1",
            name="ck_privacy_consent_revisions",
        ),
        sa.CheckConstraint(
            "receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_privacy_consent_receipt_sha256",
        ),
        sa.CheckConstraint(
            "installation_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_privacy_consent_installation_hmac",
        ),
        sa.CheckConstraint(
            "policy_version = 'FP-013-1.0.0' AND item_versions = "
            "'{\"automatic_reporting\": \"FP-013-AUTO-1.0.0\", "
            "\"mobile_network_transfer\": \"FP-013-MOBILE-1.0.0\", "
            "\"raw_source_collection\": \"FP-013-RAW-1.0.0\", "
            "\"training_reuse\": \"FP-013-TRAINING-1.0.0\"}'::jsonb",
            name="ck_privacy_consent_approved_versions",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("receipt_sha256"),
        sa.UniqueConstraint(
            "privacy_subject_hmac",
            "account_generation",
            "subject_revision",
            name="uq_privacy_consent_subject_revision",
        ),
        sa.UniqueConstraint(
            "privacy_subject_hmac",
            "account_generation",
            "installation_subject_hmac",
            "client_revision",
            name="uq_privacy_consent_installation_revision",
        ),
        sa.UniqueConstraint(
            "privacy_subject_hmac",
            "account_generation",
            "request_id",
            name="uq_privacy_consent_subject_request",
        ),
    )
    op.create_index(
        "ix_privacy_consent_events_privacy_subject_hmac",
        "privacy_consent_events",
        ["privacy_subject_hmac"],
    )
    op.create_index(
        "ix_privacy_consent_events_installation_subject_hmac",
        "privacy_consent_events",
        ["installation_subject_hmac"],
    )
    _append_only("privacy_consent_events")

    op.create_table(
        "account_deletion_tombstones",
        sa.Column("tombstone_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("privacy_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("account_generation", sa.BigInteger(), nullable=False),
        sa.Column("request_receipt_sha256", sa.String(length=64), nullable=False),
        sa.Column("tombstoned_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_tombstone_subject_hmac",
        ),
        sa.CheckConstraint(
            "account_generation >= 1",
            name="ck_account_deletion_tombstone_generation",
        ),
        sa.CheckConstraint(
            "request_receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_tombstone_receipt_sha256",
        ),
        sa.PrimaryKeyConstraint("tombstone_id"),
        sa.UniqueConstraint("request_receipt_sha256"),
        sa.UniqueConstraint(
            "privacy_subject_hmac",
            "account_generation",
            name="uq_account_deletion_tombstone_subject_generation",
        ),
        sa.UniqueConstraint(
            "tombstone_id",
            "privacy_subject_hmac",
            "account_generation",
            "request_receipt_sha256",
            name="uq_account_deletion_tombstone_request_binding",
        ),
    )
    op.create_index(
        "ix_account_deletion_tombstones_privacy_subject_hmac",
        "account_deletion_tombstones",
        ["privacy_subject_hmac"],
    )
    _append_only("account_deletion_tombstones")

    op.execute(
        """
        CREATE FUNCTION walksafe_validate_privacy_consent_event()
        RETURNS trigger AS $$
        DECLARE
          expected_client_revision bigint;
          expected_subject_revision bigint;
          latest_installation_time timestamptz;
          latest_subject_time timestamptz;
          database_recorded_at timestamptz;
          canonical_payload text;
          expected_receipt text;
        BEGIN
          PERFORM pg_advisory_xact_lock(
            hashtextextended(
              E'walksafe-privacy-subject-v2\\n' || NEW.privacy_subject_hmac ||
              E'\\n' || NEW.account_generation::text,
              0
            )
          );
          SELECT COALESCE(max(client_revision), 0) + 1, max(recorded_at)
            INTO expected_client_revision, latest_installation_time
          FROM public.privacy_consent_events
          WHERE privacy_subject_hmac = NEW.privacy_subject_hmac
            AND account_generation = NEW.account_generation
            AND installation_subject_hmac = NEW.installation_subject_hmac;
          SELECT COALESCE(max(subject_revision), 0) + 1, max(recorded_at)
            INTO expected_subject_revision, latest_subject_time
          FROM public.privacy_consent_events
          WHERE privacy_subject_hmac = NEW.privacy_subject_hmac
            AND account_generation = NEW.account_generation;
          database_recorded_at := clock_timestamp();
          IF NEW.client_revision <> expected_client_revision
             OR NEW.subject_revision <> expected_subject_revision
             OR NEW.recorded_at > database_recorded_at
             OR (latest_installation_time IS NOT NULL
                 AND NEW.recorded_at < latest_installation_time)
             OR (latest_subject_time IS NOT NULL
                 AND NEW.recorded_at < latest_subject_time)
             OR EXISTS (
               SELECT 1 FROM public.account_deletion_tombstones
               WHERE privacy_subject_hmac = NEW.privacy_subject_hmac
                 AND account_generation = NEW.account_generation
             ) THEN
            RAISE EXCEPTION 'privacy consent event revision or chronology is invalid'
              USING ERRCODE = '23514';
          END IF;
          canonical_payload :=
            '{"account_generation":' || NEW.account_generation::text ||
            ',"automatic_reporting":' || NEW.automatic_reporting::text ||
            ',"client_revision":' || NEW.client_revision::text ||
            ',"installation_subject_hmac":' ||
              to_jsonb(NEW.installation_subject_hmac)::text ||
            ',"item_versions":{"automatic_reporting":"FP-013-AUTO-1.0.0",' ||
              '"mobile_network_transfer":"FP-013-MOBILE-1.0.0",' ||
              '"raw_source_collection":"FP-013-RAW-1.0.0",' ||
              '"training_reuse":"FP-013-TRAINING-1.0.0"}' ||
            ',"mobile_network_transfer":' || NEW.mobile_network_transfer::text ||
            ',"policy_version":' || to_jsonb(NEW.policy_version)::text ||
            ',"privacy_subject_hmac":' || to_jsonb(NEW.privacy_subject_hmac)::text ||
            ',"raw_source_collection":' || NEW.raw_source_collection::text ||
            ',"request_id":' || to_jsonb(NEW.request_id)::text ||
            ',"subject_revision":' || NEW.subject_revision::text ||
            ',"training_reuse":' || NEW.training_reuse::text || '}';
          expected_receipt := encode(
            pg_catalog.sha256(
              convert_to('walksafe/privacy-consent-event/v2', 'UTF8') ||
              decode('00', 'hex') || convert_to(canonical_payload, 'UTF8')
            ),
            'hex'
          );
          IF NEW.receipt_sha256 <> expected_receipt THEN
            RAISE EXCEPTION 'privacy consent receipt provenance is invalid'
              USING ERRCODE = '23514';
          END IF;
          NEW.recorded_at := database_recorded_at;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql VOLATILE
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "CREATE TRIGGER privacy_consent_events_validate_insert "
        "BEFORE INSERT ON privacy_consent_events FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_validate_privacy_consent_event()"
    )

    op.create_table(
        "account_deletion_requests",
        sa.Column("request_id", sa.String(length=128), nullable=False),
        sa.Column("tombstone_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("privacy_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("account_generation", sa.BigInteger(), nullable=False),
        sa.Column("request_body_sha256", sa.String(length=64), nullable=False),
        sa.Column("request_receipt_sha256", sa.String(length=64), nullable=False),
        sa.Column("access_secret_digest", sa.String(length=64), nullable=False),
        sa.Column("client_revision", sa.BigInteger(), nullable=False),
        sa.Column("status_revision", sa.BigInteger(), nullable=False),
        sa.Column("overall_status", sa.String(length=32), nullable=False),
        sa.Column("completion_receipt_sha256", sa.String(length=64), nullable=True),
        sa.Column("accepted_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.CheckConstraint(
            "request_id ~ '^[A-Za-z0-9_-]{16,128}$'",
            name="ck_account_deletion_request_id",
        ),
        sa.CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_request_subject_hmac",
        ),
        sa.CheckConstraint(
            "request_body_sha256 ~ '^[0-9a-f]{64}$' AND "
            "request_receipt_sha256 ~ '^[0-9a-f]{64}$' AND "
            "access_secret_digest ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_request_digests",
        ),
        sa.CheckConstraint(
            "account_generation >= 1 AND client_revision >= 1 AND status_revision >= 1",
            name="ck_account_deletion_request_revisions",
        ),
        sa.CheckConstraint(
            "overall_status IN ('PROCESSING', 'RETRY_WAIT', 'PARTIAL', "
            "'RESTRICTED', 'FAILED', 'COMPLETED')",
            name="ck_account_deletion_request_overall_status",
        ),
        sa.CheckConstraint(
            "(overall_status = 'COMPLETED' AND completion_receipt_sha256 IS NOT NULL) OR "
            "(overall_status <> 'COMPLETED' AND completion_receipt_sha256 IS NULL)",
            name="ck_account_deletion_request_completion_receipt",
        ),
        sa.CheckConstraint(
            "completion_receipt_sha256 IS NULL OR "
            "completion_receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_request_completion_digest",
        ),
        sa.CheckConstraint(
            "updated_at >= accepted_at",
            name="ck_account_deletion_request_time_order",
        ),
        sa.ForeignKeyConstraint(
            [
                "tombstone_id",
                "privacy_subject_hmac",
                "account_generation",
                "request_receipt_sha256",
            ],
            [
                "account_deletion_tombstones.tombstone_id",
                "account_deletion_tombstones.privacy_subject_hmac",
                "account_deletion_tombstones.account_generation",
                "account_deletion_tombstones.request_receipt_sha256",
            ],
            name="fk_account_deletion_request_tombstone_binding",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("request_id"),
        sa.UniqueConstraint("tombstone_id"),
        sa.UniqueConstraint("request_receipt_sha256"),
        sa.UniqueConstraint(
            "privacy_subject_hmac",
            "account_generation",
            name="uq_account_deletion_request_subject_generation",
        ),
    )
    op.create_index(
        "ix_account_deletion_requests_privacy_subject_hmac",
        "account_deletion_requests",
        ["privacy_subject_hmac"],
    )

    op.create_table(
        "account_deletion_items",
        sa.Column("request_id", sa.String(length=128), nullable=False),
        sa.Column("item_key", sa.String(length=48), nullable=False),
        sa.Column("state", sa.String(length=32), nullable=False),
        sa.Column("item_revision", sa.BigInteger(), nullable=False),
        sa.Column("due_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("evidence_sha256", sa.String(length=64), nullable=True),
        sa.Column("disposition_basis", sa.String(length=500), nullable=True),
        sa.Column("retry_after", sa.DateTime(timezone=True), nullable=True),
        sa.Column("restriction_reason", sa.String(length=500), nullable=True),
        sa.Column("legal_hold_review_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("legal_hold_contact", sa.String(length=160), nullable=True),
        sa.Column("terminal_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "item_key IN ('device_untransmitted_data', 'server_originals', "
            "'server_quarantine', 'server_copies', 'report_records', "
            "'training_datasets', 'training_labels', 'derived_artifacts', 'backups')",
            name="ck_account_deletion_item_key",
        ),
        sa.CheckConstraint(
            "state IN ('PENDING', 'IN_PROGRESS', 'EXTERNAL_PENDING', 'RETRY_WAIT', "
            "'LEGAL_HOLD', 'FAILED', 'COMPLETED', 'NOT_APPLICABLE')",
            name="ck_account_deletion_item_state",
        ),
        sa.CheckConstraint(
            "item_revision >= 1",
            name="ck_account_deletion_item_revision",
        ),
        sa.CheckConstraint(
            "(state IN ('COMPLETED', 'NOT_APPLICABLE') "
            "AND evidence_sha256 IS NOT NULL "
            "AND evidence_sha256 ~ '^[0-9a-f]{64}$') OR "
            "(state NOT IN ('COMPLETED', 'NOT_APPLICABLE') AND evidence_sha256 IS NULL)",
            name="ck_account_deletion_item_evidence_sha256",
        ),
        sa.CheckConstraint(
            "state <> 'NOT_APPLICABLE' OR "
            "(evidence_sha256 IS NOT NULL AND disposition_basis IS NOT NULL "
            "AND length(btrim(disposition_basis)) >= 1)",
            name="ck_account_deletion_item_not_applicable_evidence",
        ),
        sa.CheckConstraint(
            "(state = 'NOT_APPLICABLE' AND disposition_basis IS NOT NULL "
            "AND length(btrim(disposition_basis)) >= 1) OR "
            "(state <> 'NOT_APPLICABLE' AND disposition_basis IS NULL)",
            name="ck_account_deletion_item_disposition_basis",
        ),
        sa.CheckConstraint(
            "(state = 'LEGAL_HOLD' AND restriction_reason IS NOT NULL "
            "AND length(btrim(restriction_reason)) >= 1 "
            "AND legal_hold_review_at IS NOT NULL AND legal_hold_contact IS NOT NULL "
            "AND length(btrim(legal_hold_contact)) >= 1) OR "
            "(state <> 'LEGAL_HOLD' AND restriction_reason IS NULL "
            "AND legal_hold_review_at IS NULL AND legal_hold_contact IS NULL)",
            name="ck_account_deletion_item_legal_hold",
        ),
        sa.CheckConstraint(
            "(state = 'RETRY_WAIT' AND retry_after IS NOT NULL) OR "
            "(state <> 'RETRY_WAIT' AND retry_after IS NULL)",
            name="ck_account_deletion_item_retry_after",
        ),
        sa.CheckConstraint(
            "(state IN ('COMPLETED', 'NOT_APPLICABLE') AND terminal_at IS NOT NULL) OR "
            "(state NOT IN ('COMPLETED', 'NOT_APPLICABLE') AND terminal_at IS NULL)",
            name="ck_account_deletion_item_terminal_at",
        ),
        sa.CheckConstraint(
            "terminal_at IS NULL OR terminal_at <= updated_at",
            name="ck_account_deletion_item_terminal_time_order",
        ),
        sa.CheckConstraint(
            "retry_after IS NULL OR retry_after > updated_at",
            name="ck_account_deletion_item_retry_time_order",
        ),
        sa.CheckConstraint(
            "legal_hold_review_at IS NULL OR legal_hold_review_at > updated_at",
            name="ck_account_deletion_item_review_time_order",
        ),
        sa.ForeignKeyConstraint(
            ["request_id"],
            ["account_deletion_requests.request_id"],
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("request_id", "item_key"),
    )
    op.execute(
        "CREATE TRIGGER account_deletion_items_no_delete "
        "BEFORE DELETE OR TRUNCATE ON account_deletion_items "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )

    op.create_table(
        "account_deletion_device_targets",
        sa.Column("request_id", sa.String(length=128), nullable=False),
        sa.Column("installation_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("state", sa.String(length=32), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("evidence_sha256", sa.String(length=64), nullable=True),
        sa.Column("terminal_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "installation_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_device_target_hmac",
        ),
        sa.CheckConstraint(
            "state IN ('PENDING', 'FAILED', 'COMPLETED', 'NOT_APPLICABLE')",
            name="ck_account_deletion_device_target_state",
        ),
        sa.CheckConstraint(
            "(state IN ('COMPLETED', 'NOT_APPLICABLE') "
            "AND evidence_sha256 IS NOT NULL "
            "AND evidence_sha256 ~ '^[0-9a-f]{64}$' AND terminal_at IS NOT NULL) OR "
            "(state NOT IN ('COMPLETED', 'NOT_APPLICABLE') "
            "AND evidence_sha256 IS NULL AND terminal_at IS NULL)",
            name="ck_account_deletion_device_target_terminal",
        ),
        sa.CheckConstraint(
            "terminal_at IS NULL OR terminal_at <= updated_at",
            name="ck_account_deletion_device_target_time_order",
        ),
        sa.ForeignKeyConstraint(
            ["request_id"],
            ["account_deletion_requests.request_id"],
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("request_id", "installation_subject_hmac"),
    )
    op.execute(
        "CREATE TRIGGER account_deletion_device_targets_no_delete "
        "BEFORE DELETE OR TRUNCATE ON account_deletion_device_targets "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )

    op.create_table(
        "account_deletion_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("request_id", sa.String(length=128), nullable=False),
        sa.Column("operation_id", sa.String(length=160), nullable=False),
        sa.Column("event_type", sa.String(length=32), nullable=False),
        sa.Column("item_key", sa.String(length=48), nullable=True),
        sa.Column("previous_state", sa.String(length=32), nullable=True),
        sa.Column("next_state", sa.String(length=32), nullable=True),
        sa.Column("status_revision", sa.BigInteger(), nullable=False),
        sa.Column("operation_sha256", sa.String(length=64), nullable=False),
        sa.Column("evidence_sha256", sa.String(length=64), nullable=True),
        sa.Column("disposition_basis", sa.String(length=500), nullable=True),
        sa.Column("failure_reason", sa.String(length=500), nullable=True),
        sa.Column("retry_after", sa.DateTime(timezone=True), nullable=True),
        sa.Column("restriction_reason", sa.String(length=500), nullable=True),
        sa.Column("legal_hold_review_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("legal_hold_contact", sa.String(length=160), nullable=True),
        sa.Column("terminal_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("installation_subject_hmac", sa.String(length=64), nullable=True),
        sa.Column("result", sa.String(length=16), nullable=True),
        sa.Column("target_state", sa.String(length=32), nullable=True),
        sa.Column(
            "recorded_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "event_type IN ('REQUEST_ACCEPTED', 'ITEM_TRANSITION', 'DEVICE_EVIDENCE')",
            name="ck_account_deletion_event_type",
        ),
        sa.CheckConstraint(
            "operation_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_event_operation_sha256",
        ),
        sa.CheckConstraint(
            "evidence_sha256 IS NULL OR evidence_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_event_evidence_sha256",
        ),
        sa.CheckConstraint(
            "status_revision >= 1",
            name="ck_account_deletion_event_status_revision",
        ),
        sa.CheckConstraint(
            "(event_type = 'REQUEST_ACCEPTED' AND item_key IS NULL "
            "AND previous_state IS NULL AND next_state IS NULL "
            "AND installation_subject_hmac IS NULL AND result IS NULL "
            "AND target_state IS NULL) OR "
            "(event_type = 'ITEM_TRANSITION' AND item_key IS NOT NULL "
            "AND item_key <> 'device_untransmitted_data' "
            "AND previous_state IS NOT NULL AND next_state IS NOT NULL "
            "AND installation_subject_hmac IS NULL AND result IS NULL "
            "AND target_state IS NULL) OR "
            "(event_type = 'DEVICE_EVIDENCE' AND item_key = 'device_untransmitted_data' "
            "AND item_key IS NOT NULL "
            "AND previous_state IS NOT NULL AND next_state IS NOT NULL "
            "AND installation_subject_hmac IS NOT NULL "
            "AND installation_subject_hmac ~ '^[0-9a-f]{64}$' "
            "AND result IS NOT NULL AND result IN ('DELETED', 'NOT_FOUND', 'FAILED') "
            "AND target_state IS NOT NULL "
            "AND target_state IN ('COMPLETED', 'NOT_APPLICABLE', 'FAILED'))",
            name="ck_account_deletion_event_item_transition",
        ),
        sa.CheckConstraint(
            "(event_type = 'REQUEST_ACCEPTED' AND status_revision = 1 "
            "AND evidence_sha256 IS NOT NULL AND disposition_basis IS NULL "
            "AND failure_reason IS NULL AND retry_after IS NULL "
            "AND restriction_reason IS NULL AND legal_hold_review_at IS NULL "
            "AND legal_hold_contact IS NULL AND terminal_at IS NULL) OR "
            "(event_type = 'ITEM_TRANSITION' "
            "AND previous_state IN ('PENDING', 'IN_PROGRESS', 'EXTERNAL_PENDING', "
            "'RETRY_WAIT', 'LEGAL_HOLD', 'FAILED', 'COMPLETED', 'NOT_APPLICABLE') "
            "AND next_state IN ('PENDING', 'IN_PROGRESS', 'EXTERNAL_PENDING', "
            "'RETRY_WAIT', 'LEGAL_HOLD', 'FAILED', 'COMPLETED', 'NOT_APPLICABLE') "
            "AND ((next_state IN ('COMPLETED', 'NOT_APPLICABLE') "
            "AND evidence_sha256 IS NOT NULL AND terminal_at IS NOT NULL) OR "
            "(next_state NOT IN ('COMPLETED', 'NOT_APPLICABLE') "
            "AND evidence_sha256 IS NULL AND terminal_at IS NULL)) "
            "AND ((next_state = 'NOT_APPLICABLE' AND disposition_basis IS NOT NULL "
            "AND length(btrim(disposition_basis)) >= 1) OR "
            "(next_state <> 'NOT_APPLICABLE' AND disposition_basis IS NULL)) "
            "AND ((next_state = 'FAILED' AND failure_reason IS NOT NULL "
            "AND length(btrim(failure_reason)) >= 1) OR "
            "(next_state <> 'FAILED' AND failure_reason IS NULL)) "
            "AND ((next_state = 'RETRY_WAIT' AND retry_after IS NOT NULL) OR "
            "(next_state <> 'RETRY_WAIT' AND retry_after IS NULL)) "
            "AND ((next_state = 'LEGAL_HOLD' AND restriction_reason IS NOT NULL "
            "AND length(btrim(restriction_reason)) >= 1 "
            "AND legal_hold_review_at IS NOT NULL AND legal_hold_contact IS NOT NULL "
            "AND length(btrim(legal_hold_contact)) >= 1) OR "
            "(next_state <> 'LEGAL_HOLD' AND restriction_reason IS NULL "
            "AND legal_hold_review_at IS NULL AND legal_hold_contact IS NULL))) OR "
            "(event_type = 'DEVICE_EVIDENCE' AND evidence_sha256 IS NOT NULL "
            "AND terminal_at IS NOT NULL AND retry_after IS NULL "
            "AND restriction_reason IS NULL AND legal_hold_review_at IS NULL "
            "AND legal_hold_contact IS NULL "
            "AND ((result = 'DELETED' AND target_state = 'COMPLETED' "
            "AND disposition_basis IS NULL AND failure_reason IS NULL) OR "
            "(result = 'NOT_FOUND' AND target_state = 'NOT_APPLICABLE' "
            "AND disposition_basis IS NOT NULL "
            "AND length(btrim(disposition_basis)) >= 1 AND failure_reason IS NULL) OR "
            "(result = 'FAILED' AND target_state = 'FAILED' "
            "AND disposition_basis IS NULL AND failure_reason IS NOT NULL "
            "AND length(btrim(failure_reason)) >= 1)))",
            name="ck_account_deletion_event_details",
        ),
        sa.CheckConstraint(
            "terminal_at IS NULL OR terminal_at <= recorded_at",
            name="ck_account_deletion_event_terminal_time_order",
        ),
        sa.CheckConstraint(
            "retry_after IS NULL OR retry_after > recorded_at",
            name="ck_account_deletion_event_retry_time_order",
        ),
        sa.CheckConstraint(
            "legal_hold_review_at IS NULL OR legal_hold_review_at > recorded_at",
            name="ck_account_deletion_event_review_time_order",
        ),
        sa.ForeignKeyConstraint(
            ["request_id"],
            ["account_deletion_requests.request_id"],
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["request_id", "item_key"],
            ["account_deletion_items.request_id", "account_deletion_items.item_key"],
            name="fk_account_deletion_event_item",
            ondelete="RESTRICT",
        ),
        sa.ForeignKeyConstraint(
            ["request_id", "installation_subject_hmac"],
            [
                "account_deletion_device_targets.request_id",
                "account_deletion_device_targets.installation_subject_hmac",
            ],
            name="fk_account_deletion_event_device_target",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "request_id",
            "operation_id",
            name="uq_account_deletion_event_operation",
        ),
        sa.UniqueConstraint(
            "request_id",
            "status_revision",
            name="uq_account_deletion_event_status_revision",
        ),
    )
    op.create_index(
        "ix_account_deletion_events_request_id",
        "account_deletion_events",
        ["request_id"],
    )
    _append_only("account_deletion_events")

    op.execute(
        """
        CREATE FUNCTION walksafe_guard_account_deletion_target_insert()
        RETURNS trigger AS $$
        DECLARE request_status text;
        BEGIN
          PERFORM pg_advisory_xact_lock(
            hashtextextended(E'walksafe-account-deletion-request-v2\\n' || NEW.request_id, 0)
          );
          SELECT overall_status INTO request_status
          FROM public.account_deletion_requests
          WHERE request_id = NEW.request_id
          FOR UPDATE;
          IF request_status IS NULL THEN
            RAISE EXCEPTION 'account deletion request is missing'
              USING ERRCODE = '23503';
          END IF;
          IF request_status = 'COMPLETED' OR EXISTS (
            SELECT 1 FROM public.account_deletion_events
            WHERE request_id = NEW.request_id
              AND event_type = 'REQUEST_ACCEPTED'
          ) THEN
            RAISE EXCEPTION 'account deletion installation target set is frozen'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql VOLATILE
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "CREATE TRIGGER account_deletion_device_targets_frozen_insert "
        "BEFORE INSERT ON account_deletion_device_targets FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_account_deletion_target_insert()"
    )

    op.execute(
        """
        CREATE FUNCTION walksafe_guard_account_deletion_chronology()
        RETURNS trigger AS $$
        DECLARE accepted timestamptz;
        BEGIN
          IF TG_TABLE_NAME = 'account_deletion_requests' THEN
            IF TG_OP = 'INSERT' THEN
              IF NEW.updated_at <> NEW.accepted_at
                 OR NEW.accepted_at > clock_timestamp()
                 OR NEW.client_revision <> 1
                 OR NEW.status_revision <> 1
                 OR NEW.overall_status <> 'PARTIAL' THEN
                RAISE EXCEPTION 'account deletion request chronology is invalid'
                  USING ERRCODE = '23514';
              END IF;
            ELSE
              IF NEW.request_id IS DISTINCT FROM OLD.request_id
                 OR NEW.tombstone_id IS DISTINCT FROM OLD.tombstone_id
                 OR NEW.privacy_subject_hmac IS DISTINCT FROM OLD.privacy_subject_hmac
                 OR NEW.account_generation IS DISTINCT FROM OLD.account_generation
                 OR NEW.request_body_sha256 IS DISTINCT FROM OLD.request_body_sha256
                 OR NEW.request_receipt_sha256 IS DISTINCT FROM OLD.request_receipt_sha256
                 OR NEW.access_secret_digest IS DISTINCT FROM OLD.access_secret_digest
                 OR NEW.client_revision IS DISTINCT FROM OLD.client_revision
                 OR NEW.accepted_at IS DISTINCT FROM OLD.accepted_at
                 OR NEW.status_revision <> OLD.status_revision + 1
                 OR NEW.updated_at < OLD.updated_at
                 OR NEW.updated_at > clock_timestamp() THEN
                RAISE EXCEPTION 'account deletion request chronology is invalid'
                  USING ERRCODE = '23514';
              END IF;
            END IF;
            RETURN NEW;
          END IF;

          SELECT accepted_at INTO accepted
          FROM public.account_deletion_requests
          WHERE request_id = NEW.request_id;
          IF accepted IS NULL
             OR NEW.updated_at < accepted
             OR NEW.updated_at > clock_timestamp() THEN
            RAISE EXCEPTION 'account deletion state chronology is invalid'
              USING ERRCODE = '23514';
          END IF;

          IF TG_TABLE_NAME = 'account_deletion_items' THEN
            IF TG_OP = 'INSERT' THEN
              IF NEW.item_revision <> 1 THEN
                RAISE EXCEPTION 'account deletion item revision is invalid'
                  USING ERRCODE = '23514';
              END IF;
            ELSIF NEW.request_id IS DISTINCT FROM OLD.request_id
               OR NEW.item_key IS DISTINCT FROM OLD.item_key
               OR NEW.due_at IS DISTINCT FROM OLD.due_at
               OR NEW.item_revision <> OLD.item_revision + 1
               OR NEW.updated_at < OLD.updated_at THEN
              RAISE EXCEPTION 'account deletion item chronology is invalid'
                USING ERRCODE = '23514';
            END IF;
            IF (NEW.retry_after IS NOT NULL AND NEW.retry_after <= clock_timestamp())
               OR (NEW.legal_hold_review_at IS NOT NULL
                   AND NEW.legal_hold_review_at <= clock_timestamp()) THEN
              RAISE EXCEPTION 'account deletion item schedule must be in the future'
                USING ERRCODE = '23514';
            END IF;
          ELSIF TG_OP = 'UPDATE' AND (
            NEW.request_id IS DISTINCT FROM OLD.request_id
            OR NEW.installation_subject_hmac IS DISTINCT FROM OLD.installation_subject_hmac
            OR NEW.updated_at < OLD.updated_at
          ) THEN
            RAISE EXCEPTION 'account deletion device target chronology is invalid'
              USING ERRCODE = '23514';
          END IF;

          IF NEW.terminal_at IS NOT NULL AND NEW.terminal_at < accepted THEN
            RAISE EXCEPTION 'account deletion terminal time precedes acceptance'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "CREATE TRIGGER account_deletion_requests_chronology "
        "BEFORE INSERT OR UPDATE ON account_deletion_requests FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_account_deletion_chronology()"
    )
    op.execute(
        "CREATE TRIGGER account_deletion_items_chronology "
        "BEFORE INSERT OR UPDATE ON account_deletion_items FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_account_deletion_chronology()"
    )
    op.execute(
        "CREATE TRIGGER account_deletion_device_targets_chronology "
        "BEFORE INSERT OR UPDATE ON account_deletion_device_targets FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_account_deletion_chronology()"
    )

    op.execute(
        """
        CREATE FUNCTION walksafe_validate_account_deletion_event()
        RETURNS trigger AS $$
        DECLARE
          request_revision bigint;
          accepted timestamptz;
          request_updated timestamptz;
          predecessor_time timestamptz;
          expected_previous text;
          current_item public.account_deletion_items%ROWTYPE;
          current_target public.account_deletion_device_targets%ROWTYPE;
          item_count integer;
          target_count integer;
          terminal_target_count integer;
          failed_target_count integer;
          device_terminal_at timestamptz;
          device_serialized text;
          expected_device_state text;
          expected_device_evidence text;
        BEGIN
          IF NEW.event_type = 'REQUEST_ACCEPTED' THEN
            PERFORM pg_advisory_xact_lock(
              hashtextextended(E'walksafe-account-deletion-request-v2\\n' || NEW.request_id, 0)
            );
          END IF;
          SELECT status_revision, accepted_at, updated_at
            INTO request_revision, accepted, request_updated
          FROM public.account_deletion_requests
          WHERE request_id = NEW.request_id;
          IF request_revision IS NULL
             OR NEW.status_revision <> request_revision
             OR NEW.recorded_at <> request_updated
             OR NEW.recorded_at < accepted
             OR (NEW.terminal_at IS NOT NULL AND NEW.terminal_at < accepted)
             OR (NEW.retry_after IS NOT NULL AND NEW.retry_after <= clock_timestamp())
             OR (NEW.legal_hold_review_at IS NOT NULL
                 AND NEW.legal_hold_review_at <= clock_timestamp())
             OR NEW.recorded_at > clock_timestamp() THEN
            RAISE EXCEPTION 'account deletion event is not bound to the current request revision'
              USING ERRCODE = '23514';
          END IF;

          IF NEW.status_revision = 1 THEN
            IF NEW.event_type <> 'REQUEST_ACCEPTED'
               OR NEW.operation_id <> 'request-accepted'
               OR NEW.recorded_at <> accepted THEN
              RAISE EXCEPTION 'account deletion acceptance event is invalid'
                USING ERRCODE = '23514';
            END IF;
            SELECT count(*) INTO item_count FROM public.account_deletion_items
            WHERE request_id = NEW.request_id;
            SELECT count(*) INTO target_count FROM public.account_deletion_device_targets
            WHERE request_id = NEW.request_id;
            IF item_count <> 9 OR target_count < 1 OR EXISTS (
              SELECT 1
              FROM (
                VALUES
                  ('device_untransmitted_data', 'EXTERNAL_PENDING', interval '24 hours'),
                  ('server_originals', 'PENDING', interval '168 hours'),
                  ('server_quarantine', 'PENDING', interval '168 hours'),
                  ('server_copies', 'PENDING', interval '168 hours'),
                  ('report_records', 'PENDING', interval '168 hours'),
                  ('training_datasets', 'EXTERNAL_PENDING', interval '720 hours'),
                  ('training_labels', 'EXTERNAL_PENDING', interval '720 hours'),
                  ('derived_artifacts', 'EXTERNAL_PENDING', interval '720 hours'),
                  ('backups', 'EXTERNAL_PENDING', interval '840 hours')
              ) AS expected(item_key, initial_state, service_level)
              LEFT JOIN public.account_deletion_items AS item
                ON item.request_id = NEW.request_id
               AND item.item_key = expected.item_key
              WHERE item.request_id IS NULL
                 OR item.state IS DISTINCT FROM expected.initial_state
                 OR item.item_revision IS DISTINCT FROM 1
                 OR item.due_at IS DISTINCT FROM accepted + expected.service_level
                 OR item.updated_at IS DISTINCT FROM accepted
            ) OR EXISTS (
              SELECT 1 FROM public.account_deletion_device_targets
              WHERE request_id = NEW.request_id
                AND (state <> 'PENDING' OR updated_at <> accepted)
            ) THEN
              RAISE EXCEPTION 'account deletion acceptance inventory is invalid'
                USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
          END IF;

          SELECT recorded_at INTO predecessor_time
          FROM public.account_deletion_events
          WHERE request_id = NEW.request_id
            AND status_revision = NEW.status_revision - 1;
          IF predecessor_time IS NULL OR NEW.recorded_at < predecessor_time THEN
            RAISE EXCEPTION 'account deletion event chronology has a gap'
              USING ERRCODE = '23514';
          END IF;

          SELECT next_state INTO expected_previous
          FROM public.account_deletion_events
          WHERE request_id = NEW.request_id
            AND item_key = NEW.item_key
            AND status_revision < NEW.status_revision
          ORDER BY status_revision DESC LIMIT 1;
          IF expected_previous IS NULL THEN
            expected_previous := CASE
              WHEN NEW.item_key IN (
                'device_untransmitted_data', 'training_datasets', 'training_labels',
                'derived_artifacts', 'backups'
              ) THEN 'EXTERNAL_PENDING' ELSE 'PENDING' END;
          END IF;
          IF NEW.previous_state <> expected_previous THEN
            RAISE EXCEPTION 'account deletion event previous state is invalid'
              USING ERRCODE = '23514';
          END IF;

          SELECT * INTO current_item FROM public.account_deletion_items
          WHERE request_id = NEW.request_id AND item_key = NEW.item_key;
          IF current_item.request_id IS NULL
             OR current_item.state <> NEW.next_state
             OR current_item.updated_at <> NEW.recorded_at THEN
            RAISE EXCEPTION 'account deletion event does not match current item state'
              USING ERRCODE = '23514';
          END IF;

          IF NEW.event_type = 'ITEM_TRANSITION' THEN
            IF current_item.evidence_sha256 IS DISTINCT FROM NEW.evidence_sha256
               OR current_item.disposition_basis IS DISTINCT FROM NEW.disposition_basis
               OR current_item.retry_after IS DISTINCT FROM NEW.retry_after
               OR current_item.restriction_reason IS DISTINCT FROM NEW.restriction_reason
               OR current_item.legal_hold_review_at IS DISTINCT FROM NEW.legal_hold_review_at
               OR current_item.legal_hold_contact IS DISTINCT FROM NEW.legal_hold_contact
               OR current_item.terminal_at IS DISTINCT FROM NEW.terminal_at THEN
              RAISE EXCEPTION 'account deletion event evidence does not match current item state'
                USING ERRCODE = '23514';
            END IF;
          ELSE
            SELECT * INTO current_target FROM public.account_deletion_device_targets
            WHERE request_id = NEW.request_id
              AND installation_subject_hmac = NEW.installation_subject_hmac;
            IF current_target.request_id IS NULL
               OR current_target.state <> NEW.target_state
               OR current_target.updated_at <> NEW.recorded_at
               OR (
                 NEW.target_state IN ('COMPLETED', 'NOT_APPLICABLE') AND (
                   current_target.evidence_sha256 IS DISTINCT FROM NEW.evidence_sha256
                   OR current_target.terminal_at IS DISTINCT FROM NEW.terminal_at
                 )
               ) THEN
              RAISE EXCEPTION 'device evidence does not match the frozen target state'
                USING ERRCODE = '23514';
            END IF;
            SELECT
              count(*),
              count(*) FILTER (
                WHERE target.state IN ('COMPLETED', 'NOT_APPLICABLE')
              ),
              count(*) FILTER (WHERE target.state = 'FAILED'),
              max(target.terminal_at),
              string_agg(
                target.evidence_sha256 || chr(9) ||
                target.installation_subject_hmac || chr(9) ||
                target.state || chr(9) ||
                to_char(
                  target.terminal_at AT TIME ZONE 'UTC',
                  'YYYY-MM-DD"T"HH24:MI:SS'
                ) ||
                CASE
                  WHEN extract(microseconds FROM target.terminal_at)::bigint % 1000000 = 0
                    THEN ''
                  ELSE '.' || to_char(target.terminal_at AT TIME ZONE 'UTC', 'US')
                END || 'Z' || chr(10),
                '' ORDER BY target.installation_subject_hmac COLLATE "C"
              )
              INTO target_count, terminal_target_count, failed_target_count,
                   device_terminal_at, device_serialized
            FROM public.account_deletion_device_targets AS target
            WHERE target.request_id = NEW.request_id;
            IF target_count < 1 THEN
              RAISE EXCEPTION 'account deletion installation inventory is missing'
                USING ERRCODE = '23514';
            ELSIF terminal_target_count = target_count THEN
              expected_device_state := 'COMPLETED';
              expected_device_evidence := encode(
                pg_catalog.sha256(
                  convert_to('walksafe/device-deletion-target-set/v2', 'UTF8') ||
                  decode('00', 'hex') || convert_to(device_serialized, 'UTF8')
                ),
                'hex'
              );
            ELSIF failed_target_count > 0 THEN
              expected_device_state := 'FAILED';
              expected_device_evidence := NULL;
              device_terminal_at := NULL;
            ELSE
              expected_device_state := 'EXTERNAL_PENDING';
              expected_device_evidence := NULL;
              device_terminal_at := NULL;
            END IF;
            IF current_item.state IS DISTINCT FROM expected_device_state
               OR current_item.evidence_sha256 IS DISTINCT FROM expected_device_evidence
               OR current_item.terminal_at IS DISTINCT FROM device_terminal_at THEN
              RAISE EXCEPTION 'device aggregate does not match the frozen target set'
                USING ERRCODE = '23514';
            END IF;
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql VOLATILE
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "CREATE TRIGGER account_deletion_events_current_revision "
        "BEFORE INSERT ON account_deletion_events FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_validate_account_deletion_event()"
    )

    op.execute(
        """
        CREATE FUNCTION walksafe_validate_account_deletion_ledger()
        RETURNS trigger AS $$
        DECLARE
          target_request_id text;
          request_row public.account_deletion_requests%ROWTYPE;
          event_count integer;
          minimum_revision bigint;
          maximum_revision bigint;
          latest_recorded timestamptz;
        BEGIN
          target_request_id := NEW.request_id;
          SELECT * INTO request_row FROM public.account_deletion_requests
          WHERE request_id = target_request_id;
          IF request_row.request_id IS NULL THEN RETURN NULL; END IF;
          SELECT count(*), min(status_revision), max(status_revision)
            INTO event_count, minimum_revision, maximum_revision
          FROM public.account_deletion_events WHERE request_id = target_request_id;
          SELECT recorded_at INTO latest_recorded FROM public.account_deletion_events
          WHERE request_id = target_request_id
          ORDER BY status_revision DESC LIMIT 1;
          IF event_count <> request_row.status_revision
             OR minimum_revision <> 1
             OR maximum_revision <> request_row.status_revision
             OR latest_recorded <> request_row.updated_at
             OR NOT EXISTS (
               SELECT 1 FROM public.account_deletion_events
               WHERE request_id = target_request_id
                 AND status_revision = 1
                 AND event_type = 'REQUEST_ACCEPTED'
                 AND operation_id = 'request-accepted'
                 AND operation_sha256 = request_row.request_body_sha256
                 AND evidence_sha256 = request_row.request_receipt_sha256
                 AND recorded_at = request_row.accepted_at
             ) THEN
            RAISE EXCEPTION 'account deletion event ledger is incomplete'
              USING ERRCODE = '23514';
          END IF;
          IF EXISTS (
            SELECT 1 FROM public.account_deletion_items AS item
            WHERE item.request_id = target_request_id
              AND item.item_revision <> 1 + (
                SELECT count(*) FROM public.account_deletion_events AS event
                WHERE event.request_id = item.request_id
                  AND event.item_key = item.item_key
              )
          ) OR EXISTS (
            SELECT 1 FROM public.account_deletion_items AS item
            WHERE item.request_id = target_request_id
              AND item.item_revision > 1
              AND NOT EXISTS (
                SELECT 1 FROM public.account_deletion_events AS event
                WHERE event.request_id = item.request_id
                  AND event.item_key = item.item_key
                  AND event.status_revision = (
                    SELECT max(latest.status_revision)
                    FROM public.account_deletion_events AS latest
                    WHERE latest.request_id = item.request_id
                      AND latest.item_key = item.item_key
                  )
                  AND event.next_state = item.state
                  AND event.recorded_at = item.updated_at
              )
          ) OR EXISTS (
            SELECT 1 FROM public.account_deletion_device_targets AS target
            WHERE target.request_id = target_request_id
              AND (target.state <> 'PENDING' OR target.updated_at <> request_row.accepted_at)
              AND NOT EXISTS (
                SELECT 1 FROM public.account_deletion_events AS event
                WHERE event.request_id = target.request_id
                  AND event.installation_subject_hmac = target.installation_subject_hmac
                  AND event.status_revision = (
                    SELECT max(latest.status_revision)
                    FROM public.account_deletion_events AS latest
                    WHERE latest.request_id = target.request_id
                      AND latest.installation_subject_hmac = target.installation_subject_hmac
                  )
                  AND event.target_state = target.state
                  AND event.recorded_at = target.updated_at
              )
          ) THEN
            RAISE EXCEPTION 'account deletion state is not represented by the event ledger'
              USING ERRCODE = '23514';
          END IF;
          RETURN NULL;
        END;
        $$ LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        """
    )
    for table_name in (
        "account_deletion_requests",
        "account_deletion_items",
        "account_deletion_device_targets",
    ):
        op.execute(
            f"CREATE CONSTRAINT TRIGGER {table_name}_validate_ledger "
            f"AFTER INSERT OR UPDATE ON {table_name} "
            "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
            "EXECUTE FUNCTION walksafe_validate_account_deletion_ledger()"
        )
    op.execute(
        "CREATE CONSTRAINT TRIGGER account_deletion_events_validate_ledger "
        "AFTER INSERT ON account_deletion_events "
        "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_validate_account_deletion_ledger()"
    )

    op.create_table(
        "account_deletion_receipts",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("privacy_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("processed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("result", sa.String(length=16), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("receipt_sha256", sa.String(length=64), nullable=False),
        sa.CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_receipt_subject_hmac",
        ),
        sa.CheckConstraint(
            "result = 'COMPLETED'",
            name="ck_account_deletion_receipt_result",
        ),
        sa.CheckConstraint(
            "receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_receipt_sha256",
        ),
        sa.CheckConstraint(
            "expires_at = processed_at + interval '3 years'",
            name="ck_account_deletion_receipt_retention",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("receipt_sha256"),
        sa.UniqueConstraint(
            "receipt_sha256",
            "privacy_subject_hmac",
            name="uq_account_deletion_receipt_subject_binding",
        ),
    )
    op.create_index(
        "ix_account_deletion_receipts_privacy_subject_hmac",
        "account_deletion_receipts",
        ["privacy_subject_hmac"],
    )
    op.create_index(
        "ix_account_deletion_receipts_expires_at",
        "account_deletion_receipts",
        ["expires_at"],
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_validate_account_deletion_receipt_provenance()
        RETURNS trigger AS $$
        DECLARE
          request_row public.account_deletion_requests%ROWTYPE;
          matching_requests integer;
          processed_at_utc text;
          canonical_payload text;
          expected_receipt text;
        BEGIN
          SELECT count(*) INTO matching_requests
          FROM public.account_deletion_requests
          WHERE privacy_subject_hmac = NEW.privacy_subject_hmac
            AND overall_status = 'COMPLETED'
            AND completion_receipt_sha256 = NEW.receipt_sha256
            AND updated_at = NEW.processed_at;
          IF NEW.processed_at > clock_timestamp() OR matching_requests <> 1 THEN
            RAISE EXCEPTION 'account deletion receipt chronology or binding is invalid'
              USING ERRCODE = '23514';
          END IF;
          SELECT * INTO STRICT request_row
          FROM public.account_deletion_requests
          WHERE privacy_subject_hmac = NEW.privacy_subject_hmac
            AND overall_status = 'COMPLETED'
            AND completion_receipt_sha256 = NEW.receipt_sha256
            AND updated_at = NEW.processed_at;
          processed_at_utc :=
            to_char(
              NEW.processed_at AT TIME ZONE 'UTC',
              'YYYY-MM-DD"T"HH24:MI:SS'
            ) ||
            CASE
              WHEN extract(microseconds FROM NEW.processed_at)::bigint % 1000000 = 0
                THEN ''
              ELSE '.' || to_char(NEW.processed_at AT TIME ZONE 'UTC', 'US')
            END || 'Z';
          canonical_payload :=
            '{"privacy_subject_hmac":' || to_jsonb(NEW.privacy_subject_hmac)::text ||
            ',"processed_at":' || to_jsonb(processed_at_utc)::text ||
            ',"request_receipt_sha256":' ||
              to_jsonb(request_row.request_receipt_sha256)::text ||
            ',"result":"COMPLETED"}';
          expected_receipt := encode(
            pg_catalog.sha256(
              convert_to('walksafe/account-deletion-completion-receipt/v2', 'UTF8') ||
              decode('00', 'hex') || convert_to(canonical_payload, 'UTF8')
            ),
            'hex'
          );
          IF NEW.receipt_sha256 <> expected_receipt THEN
            RAISE EXCEPTION 'account deletion receipt provenance is invalid'
              USING ERRCODE = '23514';
          END IF;
          RETURN NULL;
        END;
        $$ LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "CREATE CONSTRAINT TRIGGER account_deletion_receipts_validate_provenance "
        "AFTER INSERT ON account_deletion_receipts "
        "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_validate_account_deletion_receipt_provenance()"
    )
    op.execute(
        "CREATE FUNCTION walksafe_guard_privacy_terminal_update() RETURNS trigger AS $$ "
        "BEGIN "
        "IF TG_TABLE_NAME = 'account_deletion_requests' THEN "
        "IF OLD.overall_status = 'COMPLETED' AND NEW IS DISTINCT FROM OLD THEN "
        "RAISE EXCEPTION 'completed account deletion request is immutable' "
        "USING ERRCODE = '23514'; END IF; "
        "ELSIF OLD.state IN ('COMPLETED', 'NOT_APPLICABLE') "
        "AND NEW IS DISTINCT FROM OLD THEN "
        "RAISE EXCEPTION 'terminal account deletion state is immutable' "
        "USING ERRCODE = '23514'; END IF; "
        "RETURN NEW; END; $$ LANGUAGE plpgsql"
    )
    op.execute(
        "CREATE TRIGGER account_deletion_requests_terminal_immutable "
        "BEFORE UPDATE ON account_deletion_requests FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_privacy_terminal_update()"
    )
    op.execute(
        "CREATE TRIGGER account_deletion_items_terminal_immutable "
        "BEFORE UPDATE ON account_deletion_items FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_privacy_terminal_update()"
    )
    op.execute(
        "CREATE TRIGGER account_deletion_device_targets_terminal_immutable "
        "BEFORE UPDATE ON account_deletion_device_targets FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_privacy_terminal_update()"
    )
    op.execute(
        "CREATE TRIGGER account_deletion_requests_no_delete "
        "BEFORE DELETE OR TRUNCATE ON account_deletion_requests FOR EACH STATEMENT "
        "EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )

    op.execute(
        """
        CREATE FUNCTION walksafe_validate_account_deletion_completion()
        RETURNS trigger AS $$
        DECLARE
          item_count integer;
          terminal_item_count integer;
          target_count integer;
          terminal_target_count integer;
          receipt_count integer;
          event_count integer;
          device_terminal_at timestamptz;
          device_serialized text;
          expected_device_evidence text;
        BEGIN
          IF NEW.overall_status <> 'COMPLETED' THEN RETURN NULL; END IF;
          SELECT count(*), count(*) FILTER (
            WHERE state IN ('COMPLETED', 'NOT_APPLICABLE')
              AND evidence_sha256 IS NOT NULL AND terminal_at IS NOT NULL
          )
            INTO item_count, terminal_item_count
          FROM public.account_deletion_items WHERE request_id = NEW.request_id;
          SELECT
            count(*),
            count(*) FILTER (
              WHERE target.state IN ('COMPLETED', 'NOT_APPLICABLE')
                AND target.evidence_sha256 IS NOT NULL
                AND target.terminal_at IS NOT NULL
            ),
            max(target.terminal_at),
            string_agg(
              target.evidence_sha256 || chr(9) ||
              target.installation_subject_hmac || chr(9) ||
              target.state || chr(9) ||
              to_char(
                target.terminal_at AT TIME ZONE 'UTC',
                'YYYY-MM-DD"T"HH24:MI:SS'
              ) ||
              CASE
                WHEN extract(microseconds FROM target.terminal_at)::bigint % 1000000 = 0
                  THEN ''
                ELSE '.' || to_char(target.terminal_at AT TIME ZONE 'UTC', 'US')
              END || 'Z' || chr(10),
              '' ORDER BY target.installation_subject_hmac COLLATE "C"
            )
            INTO target_count, terminal_target_count, device_terminal_at,
                 device_serialized
          FROM public.account_deletion_device_targets AS target
          WHERE target.request_id = NEW.request_id;
          expected_device_evidence := encode(
            pg_catalog.sha256(
              convert_to('walksafe/device-deletion-target-set/v2', 'UTF8') ||
              decode('00', 'hex') || convert_to(device_serialized, 'UTF8')
            ),
            'hex'
          );
          SELECT count(*) INTO receipt_count FROM public.account_deletion_receipts
          WHERE receipt_sha256 = NEW.completion_receipt_sha256
            AND privacy_subject_hmac = NEW.privacy_subject_hmac
            AND result = 'COMPLETED' AND processed_at = NEW.updated_at;
          SELECT count(*) INTO event_count FROM public.account_deletion_events
          WHERE request_id = NEW.request_id;
          IF item_count <> 9 OR terminal_item_count <> 9
             OR target_count < 1 OR terminal_target_count <> target_count
             OR receipt_count <> 1 OR event_count <> NEW.status_revision
             OR NOT EXISTS (
               SELECT 1 FROM public.account_deletion_items
               WHERE request_id = NEW.request_id
                 AND item_key = 'device_untransmitted_data'
                 AND state = 'COMPLETED'
                 AND evidence_sha256 = expected_device_evidence
                 AND terminal_at = device_terminal_at
             )
             OR EXISTS (
               SELECT 1 FROM public.account_deletion_items AS item
               WHERE item.request_id = NEW.request_id
                 AND item.item_key <> 'device_untransmitted_data'
                 AND NOT EXISTS (
                   SELECT 1 FROM public.account_deletion_events AS event
                   WHERE event.request_id = item.request_id
                     AND event.item_key = item.item_key
                     AND event.event_type = 'ITEM_TRANSITION'
                     AND event.next_state = item.state
                     AND event.evidence_sha256 IS NOT DISTINCT FROM item.evidence_sha256
                     AND event.disposition_basis IS NOT DISTINCT FROM item.disposition_basis
                     AND event.terminal_at IS NOT DISTINCT FROM item.terminal_at
                 )
             )
             OR EXISTS (
               SELECT 1 FROM public.account_deletion_device_targets AS target
               WHERE target.request_id = NEW.request_id
                 AND NOT EXISTS (
                   SELECT 1 FROM public.account_deletion_events AS event
                   WHERE event.request_id = target.request_id
                     AND event.event_type = 'DEVICE_EVIDENCE'
                     AND event.installation_subject_hmac = target.installation_subject_hmac
                     AND event.target_state = target.state
                     AND event.evidence_sha256 IS NOT DISTINCT FROM target.evidence_sha256
                     AND event.terminal_at IS NOT DISTINCT FROM target.terminal_at
                 )
             )
             OR NOT EXISTS (
               SELECT 1 FROM public.account_deletion_events
               WHERE request_id = NEW.request_id
                 AND status_revision = NEW.status_revision
                 AND recorded_at = NEW.updated_at
                 AND next_state IN ('COMPLETED', 'NOT_APPLICABLE')
             ) THEN
            RAISE EXCEPTION 'account deletion completion evidence is inconsistent'
              USING ERRCODE = '23514';
          END IF;
          RETURN NULL;
        END;
        $$ LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "CREATE CONSTRAINT TRIGGER account_deletion_requests_validate_completion "
        "AFTER INSERT OR UPDATE ON account_deletion_requests "
        "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_validate_account_deletion_completion()"
    )

    op.execute(
        """
        DO $$
        BEGIN
          IF NOT EXISTS (
            SELECT 1 FROM pg_roles WHERE rolname = 'walksafe_receipt_purge_owner'
          ) THEN
            CREATE ROLE walksafe_receipt_purge_owner
              NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
              NOREPLICATION NOBYPASSRLS;
          END IF;
          IF NOT EXISTS (
            SELECT 1 FROM pg_roles WHERE rolname = 'walksafe_receipt_purger'
          ) THEN
            CREATE ROLE walksafe_receipt_purger
              NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
              NOREPLICATION NOBYPASSRLS;
          END IF;
          IF NOT EXISTS (
            SELECT 1 FROM pg_roles WHERE rolname = 'walksafe_backend_runtime'
          ) THEN
            CREATE ROLE walksafe_backend_runtime
              NOLOGIN INHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
              NOREPLICATION NOBYPASSRLS;
          END IF;

          IF (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) THEN
            ALTER ROLE walksafe_receipt_purge_owner NOLOGIN NOINHERIT
              NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
            ALTER ROLE walksafe_receipt_purger NOLOGIN NOINHERIT
              NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
            ALTER ROLE walksafe_backend_runtime NOLOGIN INHERIT
              NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
          ELSIF EXISTS (
            SELECT 1 FROM pg_roles
            WHERE rolname IN (
              'walksafe_receipt_purge_owner', 'walksafe_receipt_purger'
            ) AND (
              rolsuper OR rolcreaterole OR rolcreatedb OR rolcanlogin
              OR rolreplication OR rolbypassrls OR rolinherit
            )
          ) OR EXISTS (
            SELECT 1 FROM pg_roles
            WHERE rolname = 'walksafe_backend_runtime' AND (
              rolsuper OR rolcreaterole OR rolcreatedb OR rolcanlogin
              OR rolreplication OR rolbypassrls OR NOT rolinherit
            )
          ) THEN
            RAISE EXCEPTION 'pre-existing WalkSafe database roles are unsafe'
              USING ERRCODE = '42501';
          END IF;
        END;
        $$
        """
    )
    op.execute(
        "GRANT walksafe_receipt_purge_owner TO CURRENT_USER "
        "WITH SET TRUE, INHERIT TRUE"
    )
    op.execute(
        """
        DO $$
        BEGIN
          IF NOT EXISTS (
            SELECT 1
            FROM pg_auth_members
            WHERE roleid = 'walksafe_receipt_purge_owner'::regrole
              AND member = current_user::regrole
              AND set_option
              AND inherit_option
          ) THEN
            RAISE EXCEPTION
              'migration role requires explicit SET and INHERIT membership in purge owner'
              USING ERRCODE = '42501';
          END IF;
        END;
        $$
        """
    )
    op.execute(
        "REVOKE walksafe_receipt_purge_owner FROM walksafe_backend_runtime, "
        "walksafe_receipt_purger"
    )
    _snapshot_runtime_predecessor_acl()
    _revoke_runtime_extension_object_acl()
    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE "
        + ", ".join(f"public.{table_name}" for table_name in _RUNTIME_APP_TABLES)
        + ", public.alembic_version FROM walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL PRIVILEGES ON SEQUENCE "
        + ", ".join(
            f"public.{sequence_name}" for sequence_name in _RUNTIME_APP_SEQUENCES
        )
        + " FROM walksafe_backend_runtime"
    )
    op.execute("GRANT USAGE ON SCHEMA public TO walksafe_backend_runtime")
    op.execute("REVOKE CREATE ON SCHEMA public FROM walksafe_backend_runtime")
    op.execute(
        "GRANT SELECT, INSERT ON TABLE "
        + ", ".join(f"public.{table_name}" for table_name in _RUNTIME_APP_TABLES)
        + " TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT UPDATE ON TABLE "
        + ", ".join(f"public.{table_name}" for table_name in _RUNTIME_UPDATE_TABLES)
        + " TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT DELETE ON TABLE "
        + ", ".join(f"public.{table_name}" for table_name in _RUNTIME_DELETE_TABLES)
        + " TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT SELECT ON TABLE public.alembic_version "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT USAGE, SELECT ON SEQUENCE "
        + ", ".join(
            f"public.{sequence_name}" for sequence_name in _RUNTIME_APP_SEQUENCES
        )
        + " TO walksafe_backend_runtime"
    )
    op.execute("GRANT USAGE ON SCHEMA public TO walksafe_receipt_purger")
    op.execute(
        "CREATE FUNCTION walksafe_guard_account_deletion_receipt_mutation() RETURNS trigger AS $$ "
        "BEGIN IF TG_OP = 'DELETE' "
        "AND current_user = 'walksafe_receipt_purge_owner' "
        "AND OLD.expires_at <= clock_timestamp() THEN RETURN OLD; END IF; "
        "RAISE EXCEPTION 'account deletion receipts are immutable until database expiry' "
        "USING ERRCODE = '23514'; END; $$ LANGUAGE plpgsql"
    )
    op.execute(
        "CREATE TRIGGER account_deletion_receipts_no_update "
        "BEFORE UPDATE ON account_deletion_receipts FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_account_deletion_receipt_mutation()"
    )
    op.execute(
        "CREATE TRIGGER account_deletion_receipts_expiry_delete "
        "BEFORE DELETE ON account_deletion_receipts FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_account_deletion_receipt_mutation()"
    )
    op.execute(
        "CREATE TRIGGER account_deletion_receipts_no_truncate "
        "BEFORE TRUNCATE ON account_deletion_receipts FOR EACH STATEMENT "
        "EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(
        "CREATE FUNCTION walksafe_purge_expired_account_deletion_receipts(" 
        "batch_limit integer DEFAULT 1000) RETURNS bigint "
        "LANGUAGE plpgsql SECURITY DEFINER "
        "SET search_path = pg_catalog, pg_temp AS $$ "
        "DECLARE deleted_count bigint; BEGIN "
        "IF batch_limit IS NULL OR batch_limit < 1 OR batch_limit > 10000 THEN "
        "RAISE EXCEPTION 'batch_limit must be between 1 and 10000' "
        "USING ERRCODE = '22023'; END IF; "
        "WITH expired AS (SELECT id FROM public.account_deletion_receipts "
        "WHERE expires_at <= clock_timestamp() ORDER BY expires_at, id "
        "FOR UPDATE SKIP LOCKED LIMIT batch_limit) "
        "DELETE FROM public.account_deletion_receipts AS receipt USING expired "
        "WHERE receipt.id = expired.id; GET DIAGNOSTICS deleted_count = ROW_COUNT; "
        "RETURN deleted_count; END; $$"
    )
    op.execute(
        "GRANT USAGE, CREATE ON SCHEMA public TO walksafe_receipt_purge_owner"
    )
    op.execute(
        "ALTER FUNCTION walksafe_purge_expired_account_deletion_receipts(integer) "
        "OWNER TO walksafe_receipt_purge_owner"
    )
    op.execute(
        "REVOKE CREATE ON SCHEMA public FROM walksafe_receipt_purge_owner"
    )
    op.execute(
        "GRANT SELECT, DELETE ON TABLE public.account_deletion_receipts "
        "TO walksafe_receipt_purge_owner"
    )
    op.execute(
        "REVOKE ALL ON TABLE public.account_deletion_receipts FROM PUBLIC, "
        "walksafe_backend_runtime, walksafe_receipt_purger"
    )
    op.execute(
        "GRANT SELECT, INSERT ON TABLE public.account_deletion_receipts "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_purge_expired_account_deletion_receipts(integer) "
        "FROM PUBLIC"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION "
        "walksafe_purge_expired_account_deletion_receipts(integer) "
        "TO walksafe_receipt_purger"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_guard_account_deletion_receipt_mutation() "
        "FROM PUBLIC, walksafe_backend_runtime, walksafe_receipt_purger"
    )
    op.execute(
        "GRANT USAGE, CREATE ON SCHEMA public TO walksafe_receipt_purge_owner"
    )
    op.execute(
        "ALTER FUNCTION walksafe_guard_account_deletion_receipt_mutation() "
        "OWNER TO walksafe_receipt_purge_owner"
    )
    op.execute(
        "ALTER TABLE public.account_deletion_receipts "
        "OWNER TO walksafe_receipt_purge_owner"
    )
    op.execute(
        "REVOKE CREATE ON SCHEMA public FROM walksafe_receipt_purge_owner"
    )


def downgrade() -> None:
    _reject_lossy_downgrade()
    _restore_runtime_predecessor_acl()
    op.drop_table(_RUNTIME_ACL_BASELINE_TABLE)
    op.execute("REVOKE USAGE, CREATE ON SCHEMA public FROM walksafe_receipt_purger")
    op.execute(
        "REVOKE USAGE, CREATE ON SCHEMA public FROM walksafe_receipt_purge_owner"
    )
    op.execute(
        "ALTER TABLE public.account_deletion_receipts OWNER TO CURRENT_USER"
    )
    op.execute(
        "ALTER FUNCTION walksafe_guard_account_deletion_receipt_mutation() "
        "OWNER TO CURRENT_USER"
    )
    op.execute(
        "ALTER FUNCTION walksafe_purge_expired_account_deletion_receipts(integer) "
        "OWNER TO CURRENT_USER"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "walksafe_purge_expired_account_deletion_receipts(integer)"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_requests_validate_completion "
        "ON account_deletion_requests"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_requests_terminal_immutable "
        "ON account_deletion_requests"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_requests_no_delete "
        "ON account_deletion_requests"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_items_terminal_immutable "
        "ON account_deletion_items"
    )
    for table_name in (
        "account_deletion_requests",
        "account_deletion_items",
        "account_deletion_device_targets",
        "account_deletion_events",
    ):
        op.execute(
            f"DROP TRIGGER IF EXISTS {table_name}_validate_ledger ON {table_name}"
        )
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_events_current_revision "
        "ON account_deletion_events"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_device_targets_frozen_insert "
        "ON account_deletion_device_targets"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS privacy_consent_events_validate_insert "
        "ON privacy_consent_events"
    )
    op.execute("DROP FUNCTION IF EXISTS walksafe_validate_privacy_consent_event()")
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_requests_chronology "
        "ON account_deletion_requests"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_items_chronology "
        "ON account_deletion_items"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_device_targets_chronology "
        "ON account_deletion_device_targets"
    )
    op.execute("DROP FUNCTION IF EXISTS walksafe_validate_account_deletion_ledger()")
    op.execute("DROP FUNCTION IF EXISTS walksafe_validate_account_deletion_event()")
    op.execute("DROP FUNCTION IF EXISTS walksafe_guard_account_deletion_chronology()")
    op.execute("DROP FUNCTION IF EXISTS walksafe_guard_account_deletion_target_insert()")
    op.drop_table("account_deletion_receipts")
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "walksafe_validate_account_deletion_receipt_provenance()"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_events_append_only "
        "ON account_deletion_events"
    )
    op.drop_table("account_deletion_events")
    op.execute("DROP TABLE IF EXISTS account_deletion_device_targets")
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_items_no_delete "
        "ON account_deletion_items"
    )
    op.drop_table("account_deletion_items")
    op.drop_table("account_deletion_requests")
    op.execute("DROP FUNCTION IF EXISTS walksafe_validate_account_deletion_completion()")
    op.execute("DROP FUNCTION IF EXISTS walksafe_guard_privacy_terminal_update()")
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_guard_account_deletion_receipt_mutation()"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_tombstones_append_only "
        "ON account_deletion_tombstones"
    )
    op.drop_table("account_deletion_tombstones")
    op.execute(
        "DROP TRIGGER IF EXISTS privacy_consent_events_append_only "
        "ON privacy_consent_events"
    )
    op.drop_table("privacy_consent_events")
    op.execute("DROP TABLE IF EXISTS privacy_hmac_key_bindings")
    op.drop_index("ix_reports_privacy_subject_generation", table_name="reports")
    op.drop_constraint(
        "ck_reports_privacy_subject_binding",
        "reports",
        type_="check",
    )
    op.drop_column("reports", "account_generation")
    op.drop_column("reports", "privacy_subject_hmac")
