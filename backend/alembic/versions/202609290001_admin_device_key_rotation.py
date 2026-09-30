"""Two-phase administrator device key rotation and recorded key security level.

S18-3: a higher key version is first registered as PENDING. The existing ACTIVE
key keeps working until the device proves possession of the PENDING key in a
session authenticated by the ACTIVE key; only then are the old key revoked and
the new key activated, in one transaction. S18-2: the Android KeyInfo security
level (TEE or StrongBox only) is recorded per key.

Revision ID: 202609290001
Revises: 202609280002
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "202609290001"
down_revision = "202609280002"
branch_labels = None
depends_on = None

_RUNTIME_ROLE = "walksafe_backend_runtime"
_CONFIRM_SIGNATURE = (
    "public.walksafe_confirm_admin_device_key_rotation("
    "text, uuid, text, bigint, text, bigint, text, timestamptz, text, text)"
)

_TRIGGER_FUNCTION_V1 = """
        CREATE OR REPLACE FUNCTION public.walksafe_require_admin_device_key_revocation()
        RETURNS trigger AS $$
        BEGIN
          IF ROW(
            NEW.id,
            NEW.admin_id,
            NEW.device_id,
            NEW.key_version,
            NEW.public_key_spki_der,
            NEW.key_marker,
            NEW.created_at
          ) IS DISTINCT FROM ROW(
            OLD.id,
            OLD.admin_id,
            OLD.device_id,
            OLD.key_version,
            OLD.public_key_spki_der,
            OLD.key_marker,
            OLD.created_at
          )
            OR OLD.status <> 'ACTIVE'
            OR OLD.revoked_at IS NOT NULL
            OR NEW.status <> 'REVOKED'
            OR NEW.revoked_at IS NULL
          THEN
            RAISE EXCEPTION
              'administrator device keys may only transition from active to revoked'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql SECURITY INVOKER
        SET search_path = pg_catalog, pg_temp
"""

# PENDING -> ACTIVE is the only way a key becomes usable after registration;
# ACTIVE and PENDING may only end as REVOKED. Key material, version, marker and
# the recorded security level stay immutable.
_TRIGGER_FUNCTION_V2 = """
        CREATE OR REPLACE FUNCTION public.walksafe_require_admin_device_key_revocation()
        RETURNS trigger AS $$
        BEGIN
          IF ROW(
            NEW.id,
            NEW.admin_id,
            NEW.device_id,
            NEW.key_version,
            NEW.public_key_spki_der,
            NEW.key_marker,
            NEW.created_at,
            NEW.security_level
          ) IS DISTINCT FROM ROW(
            OLD.id,
            OLD.admin_id,
            OLD.device_id,
            OLD.key_version,
            OLD.public_key_spki_der,
            OLD.key_marker,
            OLD.created_at,
            OLD.security_level
          )
            OR OLD.revoked_at IS NOT NULL
            OR NOT (
              (
                OLD.status IN ('ACTIVE', 'PENDING')
                AND NEW.status = 'REVOKED'
                AND NEW.revoked_at IS NOT NULL
              )
              OR (
                OLD.status = 'PENDING'
                AND NEW.status = 'ACTIVE'
                AND NEW.revoked_at IS NULL
              )
            )
          THEN
            RAISE EXCEPTION
              'administrator device key transition is not allowed'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql SECURITY INVOKER
        SET search_path = pg_catalog, pg_temp
"""

_CONFIRM_FUNCTION = """
        CREATE FUNCTION public.walksafe_confirm_admin_device_key_rotation(
          p_admin_id text,
          p_current_session_id uuid,
          p_device_id text,
          p_previous_key_version bigint,
          p_previous_key_marker text,
          p_key_version bigint,
          p_key_marker text,
          p_observed_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS TABLE (
          result_status text,
          resulting_state_version bigint
        ) AS $$
        DECLARE
          current_security_state text;
          public_totp_fingerprint text;
          private_totp_fingerprint text;
          caller_device_id text;
          caller_expires_at timestamptz;
          caller_revoked_at timestamptz;
          previous_status text;
          pending_status text;
        BEGIN
          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION 'administrator key rotation capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          PERFORM public.walksafe_lock_admin_security_control(
            p_admin_id,
            p_credential_issuer_key
          );
          SELECT control.security_state,
                 control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint,
                 session.device_id,
                 session.expires_at,
                 session.revoked_at
          INTO STRICT current_security_state,
                      public_totp_fingerprint,
                      private_totp_fingerprint,
                      caller_device_id,
                      caller_expires_at,
                      caller_revoked_at
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          JOIN public.admin_security_sessions AS session
            ON session.id = p_current_session_id
           AND session.admin_id = control.admin_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability, session;

          IF NOT public.walksafe_assert_admin_credential_issuer_key(
              p_admin_id,
              p_credential_issuer_key
            )
            OR p_runtime_totp_secret IS NULL
            OR pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
              ),
              'hex'
            ) IS DISTINCT FROM private_totp_fingerprint
            OR public_totp_fingerprint IS DISTINCT FROM private_totp_fingerprint
            OR current_security_state <> 'NORMAL'
            OR p_observed_at IS NULL
            OR caller_revoked_at IS NOT NULL
            OR caller_expires_at <= p_observed_at
            OR caller_device_id IS DISTINCT FROM p_device_id
          THEN
            RAISE EXCEPTION 'administrator key rotation capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_previous_key_version IS NULL
            OR p_key_version IS NULL
            OR p_key_version <= p_previous_key_version
            OR p_previous_key_marker IS NULL
            OR p_previous_key_marker !~ '^[0-9a-f]{64}$'
            OR p_key_marker IS NULL
            OR p_key_marker !~ '^[0-9a-f]{64}$'
            OR p_key_marker = p_previous_key_marker
          THEN
            RAISE EXCEPTION 'administrator key rotation payload is invalid'
              USING ERRCODE = '22023';
          END IF;

          SELECT device_key.status
          INTO previous_status
          FROM public.admin_device_keys AS device_key
          WHERE device_key.admin_id = p_admin_id
            AND device_key.device_id = p_device_id
            AND device_key.key_version = p_previous_key_version
            AND device_key.key_marker = p_previous_key_marker
          FOR UPDATE;
          SELECT device_key.status
          INTO pending_status
          FROM public.admin_device_keys AS device_key
          WHERE device_key.admin_id = p_admin_id
            AND device_key.device_id = p_device_id
            AND device_key.key_version = p_key_version
            AND device_key.key_marker = p_key_marker
          FOR UPDATE;

          IF previous_status = 'REVOKED' AND pending_status = 'ACTIVE' THEN
            result_status := 'EXISTING';
            SELECT control.state_version
            INTO STRICT resulting_state_version
            FROM public.admin_security_controls AS control
            WHERE control.admin_id = p_admin_id;
            RETURN NEXT;
            RETURN;
          END IF;
          IF previous_status IS DISTINCT FROM 'ACTIVE'
            OR pending_status IS DISTINCT FROM 'PENDING'
          THEN
            result_status := 'NOT_PENDING';
            resulting_state_version := NULL;
            RETURN NEXT;
            RETURN;
          END IF;

          -- Revoke first: at most one ACTIVE key per device is enforced by index.
          UPDATE public.admin_device_keys AS device_key
          SET status = 'REVOKED',
              revoked_at = p_observed_at
          WHERE device_key.admin_id = p_admin_id
            AND device_key.device_id = p_device_id
            AND device_key.key_version = p_previous_key_version
            AND device_key.status = 'ACTIVE';
          UPDATE public.admin_device_keys AS device_key
          SET status = 'ACTIVE'
          WHERE device_key.admin_id = p_admin_id
            AND device_key.device_id = p_device_id
            AND device_key.key_version = p_key_version
            AND device_key.status = 'PENDING';
          UPDATE public.admin_security_controls AS control
          SET state_version = control.state_version + 1,
              updated_at = p_observed_at
          WHERE control.admin_id = p_admin_id
          RETURNING control.state_version INTO resulting_state_version;
          result_status := 'CONFIRMED';
          RETURN NEXT;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
"""


def upgrade() -> None:
    op.add_column(
        "admin_device_keys",
        sa.Column("security_level", sa.String(length=32), nullable=True),
    )
    op.create_check_constraint(
        "ck_admin_device_keys_security_level",
        "admin_device_keys",
        "security_level IS NULL OR security_level IN "
        "('TRUSTED_ENVIRONMENT', 'STRONGBOX')",
    )
    op.drop_constraint(
        "ck_admin_device_keys_revocation_state", "admin_device_keys", type_="check"
    )
    op.drop_constraint("ck_admin_device_keys_status", "admin_device_keys", type_="check")
    op.create_check_constraint(
        "ck_admin_device_keys_status",
        "admin_device_keys",
        "status IN ('ACTIVE', 'PENDING', 'REVOKED')",
    )
    op.create_check_constraint(
        "ck_admin_device_keys_revocation_state",
        "admin_device_keys",
        "(status IN ('ACTIVE', 'PENDING') AND revoked_at IS NULL) OR "
        "(status = 'REVOKED' AND revoked_at IS NOT NULL)",
    )
    op.create_index(
        "uq_admin_device_keys_one_pending",
        "admin_device_keys",
        ["admin_id", "device_id"],
        unique=True,
        postgresql_where=sa.text("status = 'PENDING'"),
    )
    op.execute(_TRIGGER_FUNCTION_V2)
    op.execute(_CONFIRM_FUNCTION)
    op.execute(
        f"REVOKE ALL ON FUNCTION {_CONFIRM_SIGNATURE} FROM PUBLIC, {_RUNTIME_ROLE}"
    )
    op.execute(f"GRANT EXECUTE ON FUNCTION {_CONFIRM_SIGNATURE} TO {_RUNTIME_ROLE}")


def downgrade() -> None:
    op.execute(f"DROP FUNCTION {_CONFIRM_SIGNATURE}")
    # A pending key was never usable; end it before the predecessor rules return.
    op.execute(
        "UPDATE public.admin_device_keys SET status = 'REVOKED', "
        "revoked_at = pg_catalog.clock_timestamp() WHERE status = 'PENDING'"
    )
    op.execute(_TRIGGER_FUNCTION_V1)
    op.drop_index("uq_admin_device_keys_one_pending", table_name="admin_device_keys")
    op.drop_constraint(
        "ck_admin_device_keys_revocation_state", "admin_device_keys", type_="check"
    )
    op.drop_constraint("ck_admin_device_keys_status", "admin_device_keys", type_="check")
    op.create_check_constraint(
        "ck_admin_device_keys_status",
        "admin_device_keys",
        "status IN ('ACTIVE', 'REVOKED')",
    )
    op.create_check_constraint(
        "ck_admin_device_keys_revocation_state",
        "admin_device_keys",
        "(status = 'ACTIVE' AND revoked_at IS NULL) OR "
        "(status = 'REVOKED' AND revoked_at IS NOT NULL)",
    )
    op.drop_constraint(
        "ck_admin_device_keys_security_level", "admin_device_keys", type_="check"
    )
    op.drop_column("admin_device_keys", "security_level")
