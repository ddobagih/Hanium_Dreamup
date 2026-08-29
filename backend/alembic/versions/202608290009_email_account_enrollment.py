"""add encrypted email enrollment, user accounts, and signup receipts

Revision ID: 202608290009
Revises: 202608290008
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608290009"
down_revision = "202608290008"
branch_labels = None
depends_on = None


_RATE_GROUPS = (
    "report",
    "navigation",
    "detect",
    "export",
    "admin_read",
    "privacy",
    "raw_collection",
    "account_enrollment_global",
    "account_enrollment_ip",
    "account_enrollment_email",
)

_DOWNGRADE_LOCK_SQL = (
    "LOCK TABLE public.user_accounts, public.account_enrollments, "
    "public.signup_consent_receipts IN ACCESS EXCLUSIVE MODE"
)
_UNSAFE_DOWNGRADE_SQL = """
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM public.user_accounts)
     OR EXISTS (SELECT 1 FROM public.account_enrollments)
     OR EXISTS (SELECT 1 FROM public.signup_consent_receipts) THEN
    RAISE EXCEPTION
      'cannot downgrade email accounts while account data exists'
      USING ERRCODE = '55000';
  END IF;
END
$$
"""


def _rate_group_expression(groups: tuple[str, ...]) -> str:
    return "rate_group IN (" + ", ".join(f"'{group}'" for group in groups) + ")"


def upgrade() -> None:
    op.drop_constraint(
        "ck_actor_rate_limit_events_group",
        "actor_rate_limit_events",
        type_="check",
    )
    op.create_check_constraint(
        "ck_actor_rate_limit_events_group",
        "actor_rate_limit_events",
        _rate_group_expression(_RATE_GROUPS),
    )

    op.create_table(
        "user_accounts",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("actor_id", sa.String(length=36), nullable=False),
        sa.Column("privacy_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("email_lookup_hmac", sa.String(length=64), nullable=False),
        sa.Column("email_ciphertext", sa.LargeBinary(), nullable=False),
        sa.Column("email_nonce", sa.LargeBinary(), nullable=False),
        sa.Column("email_key_version", sa.BigInteger(), nullable=False),
        sa.Column("password_hash", sa.String(length=512), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("account_generation", sa.BigInteger(), nullable=False),
        sa.Column("auth_epoch", sa.BigInteger(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "actor_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
            "[89ab][0-9a-f]{3}-[0-9a-f]{12}$'",
            name="ck_user_accounts_actor_id",
        ),
        sa.CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$' AND "
            "email_lookup_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_user_accounts_hmacs",
        ),
        sa.CheckConstraint(
            "octet_length(email_nonce) = 12 AND "
            "octet_length(email_ciphertext) BETWEEN 17 AND 512 AND "
            "email_key_version >= 1",
            name="ck_user_accounts_email_envelope",
        ),
        sa.CheckConstraint(
            "length(password_hash) BETWEEN 32 AND 512",
            name="ck_user_accounts_password_hash",
        ),
        sa.CheckConstraint(
            "status IN ('ACTIVE', 'DISABLED', 'DELETED')",
            name="ck_user_accounts_status",
        ),
        sa.CheckConstraint(
            "account_generation BETWEEN 1 AND 9007199254740991 AND "
            "auth_epoch BETWEEN 1 AND 9007199254740991",
            name="ck_user_accounts_safe_integer_epochs",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("actor_id", name="uq_user_accounts_actor_id"),
        sa.UniqueConstraint(
            "email_lookup_hmac",
            name="uq_user_accounts_email_lookup_hmac",
        ),
        sa.UniqueConstraint(
            "privacy_subject_hmac",
            "account_generation",
            name="uq_user_accounts_privacy_generation",
        ),
    )
    op.create_index(
        "ix_user_accounts_privacy_subject_hmac",
        "user_accounts",
        ["privacy_subject_hmac"],
    )

    op.create_table(
        "account_enrollments",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("request_id", sa.String(length=128), nullable=False),
        sa.Column("request_hmac", sa.String(length=64), nullable=False),
        sa.Column("enrollment_handle", sa.String(length=43), nullable=False),
        sa.Column("email_lookup_hmac", sa.String(length=64), nullable=False),
        sa.Column("email_ciphertext", sa.LargeBinary(), nullable=False),
        sa.Column("email_nonce", sa.LargeBinary(), nullable=False),
        sa.Column("email_key_version", sa.BigInteger(), nullable=False),
        sa.Column("otp_hmac", sa.String(length=64), nullable=True),
        sa.Column("state", sa.String(length=32), nullable=False),
        sa.Column("state_version", sa.BigInteger(), nullable=False),
        sa.Column("attempt_count", sa.Integer(), nullable=False),
        sa.Column("max_attempts", sa.Integer(), nullable=False),
        sa.Column("issue_count", sa.Integer(), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("resend_not_before", sa.DateTime(timezone=True), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "request_id ~ '^[A-Za-z0-9_-]{16,128}$'",
            name="ck_account_enrollments_request_id",
        ),
        sa.CheckConstraint(
            "enrollment_handle ~ '^[A-Za-z0-9_-]{43}$'",
            name="ck_account_enrollments_handle",
        ),
        sa.CheckConstraint(
            "request_hmac ~ '^[0-9a-f]{64}$' AND "
            "email_lookup_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_account_enrollments_hmacs",
        ),
        sa.CheckConstraint(
            "otp_hmac IS NULL OR otp_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_account_enrollments_otp_hmac",
        ),
        sa.CheckConstraint(
            "octet_length(email_nonce) = 12 AND "
            "octet_length(email_ciphertext) BETWEEN 17 AND 512 AND "
            "email_key_version >= 1",
            name="ck_account_enrollments_email_envelope",
        ),
        sa.CheckConstraint(
            "state IN ('PENDING_DELIVERY', 'ACTIVE', 'DELIVERY_FAILED', "
            "'EXPIRED', 'EXHAUSTED', 'CONSUMED')",
            name="ck_account_enrollments_state",
        ),
        sa.CheckConstraint(
            "attempt_count >= 0 AND max_attempts BETWEEN 1 AND 10 "
            "AND attempt_count <= max_attempts AND issue_count >= 1 "
            "AND state_version >= 1",
            name="ck_account_enrollments_counters",
        ),
        sa.CheckConstraint(
            "expires_at > created_at AND resend_not_before >= created_at "
            "AND updated_at >= created_at",
            name="ck_account_enrollments_time_order",
        ),
        sa.CheckConstraint(
            "(state IN ('PENDING_DELIVERY', 'ACTIVE') AND otp_hmac IS NOT NULL) OR "
            "(state IN ('DELIVERY_FAILED', 'EXPIRED', 'EXHAUSTED', 'CONSUMED') "
            "AND otp_hmac IS NULL)",
            name="ck_account_enrollments_otp_state",
        ),
        sa.CheckConstraint(
            "(state = 'CONSUMED' AND consumed_at IS NOT NULL) OR "
            "(state <> 'CONSUMED' AND consumed_at IS NULL)",
            name="ck_account_enrollments_consumed",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("request_id", name="uq_account_enrollments_request_id"),
        sa.UniqueConstraint(
            "enrollment_handle",
            name="uq_account_enrollments_handle",
        ),
    )
    op.create_index(
        "ix_account_enrollments_email_created_at",
        "account_enrollments",
        ["email_lookup_hmac", "created_at"],
    )
    op.create_index(
        "ix_account_enrollments_expires_at",
        "account_enrollments",
        ["expires_at"],
    )

    op.create_table(
        "signup_consent_receipts",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("account_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("schema_version", sa.String(length=64), nullable=False),
        sa.Column("document_versions", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("selections", postgresql.JSONB(astext_type=sa.Text()), nullable=False),
        sa.Column("receipt_sha256", sa.String(length=64), nullable=False),
        sa.Column(
            "recorded_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "schema_version = 'walksafe.signup-consent.v1'",
            name="ck_signup_consent_receipts_schema",
        ),
        sa.CheckConstraint(
            "receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_signup_consent_receipts_sha256",
        ),
        sa.CheckConstraint(
            "jsonb_typeof(document_versions) = 'object' AND "
            "jsonb_object_length(document_versions) = 6 AND "
            "document_versions ?& ARRAY['terms_of_service', 'privacy_notice', "
            "'location_terms', 'raw_original', 'automatic_reporting', 'training_reuse']",
            name="ck_signup_consent_receipts_document_keys",
        ),
        sa.CheckConstraint(
            "jsonb_typeof(selections) = 'object' AND "
            "jsonb_object_length(selections) = 6 AND "
            "selections ?& ARRAY['terms_of_service', 'privacy_notice', "
            "'location_terms', 'raw_original', 'automatic_reporting', 'training_reuse'] "
            "AND selections->'terms_of_service' = 'true'::jsonb "
            "AND selections->'privacy_notice' = 'true'::jsonb "
            "AND selections->'location_terms' = 'true'::jsonb "
            "AND jsonb_typeof(selections->'raw_original') = 'boolean' "
            "AND jsonb_typeof(selections->'automatic_reporting') = 'boolean' "
            "AND jsonb_typeof(selections->'training_reuse') = 'boolean'",
            name="ck_signup_consent_receipts_selections",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint("account_id", name="uq_signup_consent_receipts_account"),
        sa.UniqueConstraint(
            "receipt_sha256",
            name="uq_signup_consent_receipts_sha256",
        ),
    )
    op.create_index(
        "ix_signup_consent_receipts_account_id",
        "signup_consent_receipts",
        ["account_id"],
    )

    op.add_column(
        "account_deletion_requests",
        sa.Column("credential_account_id", postgresql.UUID(as_uuid=True), nullable=True),
    )
    op.create_unique_constraint(
        "uq_account_deletion_requests_credential_account",
        "account_deletion_requests",
        ["credential_account_id"],
    )
    op.create_index(
        "ix_account_deletion_requests_credential_account_id",
        "account_deletion_requests",
        ["credential_account_id"],
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_guard_account_deletion_credential_link()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        AS $guard$
        BEGIN
            IF TG_OP = 'UPDATE' THEN
                IF NEW.credential_account_id IS DISTINCT FROM
                   OLD.credential_account_id THEN
                    RAISE EXCEPTION 'account deletion credential link is immutable';
                END IF;
                RETURN NEW;
            END IF;
            IF NEW.credential_account_id IS NOT NULL
               AND NOT EXISTS (
                   SELECT 1
                   FROM public.user_accounts AS account
                   WHERE account.id = NEW.credential_account_id
                     AND account.privacy_subject_hmac = NEW.privacy_subject_hmac
                     AND account.account_generation = NEW.account_generation
               ) THEN
                RAISE EXCEPTION 'account deletion credential link is invalid';
            END IF;
            RETURN NEW;
        END;
        $guard$
        """
    )
    op.execute(
        "CREATE TRIGGER account_deletion_requests_credential_link_guard "
        "BEFORE INSERT OR UPDATE ON account_deletion_requests FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_account_deletion_credential_link()"
    )

    op.execute(
        """
        CREATE FUNCTION walksafe_guard_account_enrollment_transition()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $guard$
        BEGIN
            IF OLD.state IN ('EXPIRED', 'EXHAUSTED', 'CONSUMED') THEN
                RAISE EXCEPTION 'terminal account enrollment is immutable';
            END IF;
            IF NEW.id IS DISTINCT FROM OLD.id
               OR NEW.request_id IS DISTINCT FROM OLD.request_id
               OR NEW.request_hmac IS DISTINCT FROM OLD.request_hmac
               OR NEW.enrollment_handle IS DISTINCT FROM OLD.enrollment_handle
               OR NEW.email_lookup_hmac IS DISTINCT FROM OLD.email_lookup_hmac
               OR NEW.email_ciphertext IS DISTINCT FROM OLD.email_ciphertext
               OR NEW.email_nonce IS DISTINCT FROM OLD.email_nonce
               OR NEW.email_key_version IS DISTINCT FROM OLD.email_key_version
               OR NEW.max_attempts IS DISTINCT FROM OLD.max_attempts
               OR NEW.created_at IS DISTINCT FROM OLD.created_at
               OR NEW.state_version <> OLD.state_version + 1 THEN
                RAISE EXCEPTION 'account enrollment immutable or CAS columns changed';
            END IF;
            IF OLD.state = 'PENDING_DELIVERY'
               AND NEW.state NOT IN ('ACTIVE', 'DELIVERY_FAILED') THEN
                RAISE EXCEPTION 'invalid account enrollment delivery transition';
            ELSIF OLD.state = 'DELIVERY_FAILED'
               AND NEW.state <> 'PENDING_DELIVERY' THEN
                RAISE EXCEPTION 'invalid account enrollment retry transition';
            ELSIF OLD.state = 'ACTIVE'
               AND NEW.state NOT IN ('ACTIVE', 'EXPIRED', 'EXHAUSTED', 'CONSUMED') THEN
                RAISE EXCEPTION 'invalid account enrollment verification transition';
            END IF;
            RETURN NEW;
        END;
        $guard$
        """
    )
    op.execute(
        "CREATE TRIGGER account_enrollments_transition_guard "
        "BEFORE UPDATE ON account_enrollments FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_account_enrollment_transition()"
    )
    op.execute(
        "CREATE TRIGGER signup_consent_receipts_append_only "
        "BEFORE UPDATE OR DELETE ON signup_consent_receipts FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(
        "CREATE TRIGGER signup_consent_receipts_no_truncate "
        "BEFORE TRUNCATE ON signup_consent_receipts FOR EACH STATEMENT "
        "EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )

    op.execute(
        "REVOKE ALL ON TABLE account_enrollments, user_accounts, "
        "signup_consent_receipts FROM walksafe_backend_runtime"
    )
    op.execute(
        "GRANT SELECT, INSERT, UPDATE ON TABLE account_enrollments "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT SELECT, INSERT ON TABLE user_accounts TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT SELECT, INSERT ON TABLE signup_consent_receipts "
        "TO walksafe_backend_runtime"
    )


def downgrade() -> None:
    op.execute(_DOWNGRADE_LOCK_SQL)
    op.execute(_UNSAFE_DOWNGRADE_SQL)
    op.execute(
        "REVOKE ALL ON TABLE signup_consent_receipts, account_enrollments, "
        "user_accounts FROM walksafe_backend_runtime"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS signup_consent_receipts_no_truncate "
        "ON signup_consent_receipts"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS signup_consent_receipts_append_only "
        "ON signup_consent_receipts"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS account_enrollments_transition_guard "
        "ON account_enrollments"
    )
    op.execute("DROP FUNCTION IF EXISTS walksafe_guard_account_enrollment_transition()")
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_requests_credential_link_guard "
        "ON account_deletion_requests"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_guard_account_deletion_credential_link()"
    )

    op.drop_index(
        "ix_account_deletion_requests_credential_account_id",
        table_name="account_deletion_requests",
    )
    op.drop_constraint(
        "uq_account_deletion_requests_credential_account",
        "account_deletion_requests",
        type_="unique",
    )
    op.drop_column("account_deletion_requests", "credential_account_id")

    op.drop_index(
        "ix_signup_consent_receipts_account_id",
        table_name="signup_consent_receipts",
    )
    op.drop_table("signup_consent_receipts")
    op.drop_index(
        "ix_account_enrollments_expires_at",
        table_name="account_enrollments",
    )
    op.drop_index(
        "ix_account_enrollments_email_created_at",
        table_name="account_enrollments",
    )
    op.drop_table("account_enrollments")
    op.drop_index(
        "ix_user_accounts_privacy_subject_hmac",
        table_name="user_accounts",
    )
    op.drop_table("user_accounts")

    op.execute(
        "DELETE FROM actor_rate_limit_events WHERE rate_group IN ("
        "'account_enrollment_global', 'account_enrollment_ip', "
        "'account_enrollment_email')"
    )
    op.drop_constraint(
        "ck_actor_rate_limit_events_group",
        "actor_rate_limit_events",
        type_="check",
    )
    op.create_check_constraint(
        "ck_actor_rate_limit_events_group",
        "actor_rate_limit_events",
        _rate_group_expression(_RATE_GROUPS[:7]),
    )
