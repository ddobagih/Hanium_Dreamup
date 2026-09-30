"""rehash legacy administrator passwords within approved login issuance

Revision ID: 202609240002
Revises: 202609240001
Create Date: 2026-09-24
"""

from __future__ import annotations

from alembic import op


revision = "202609240002"
down_revision = "202609240001"
branch_labels = None
depends_on = None


_REHASH_FUNCTION_SIGNATURE = (
    "uuid, text, text, text, text, timestamptz, timestamptz, "
    "timestamptz, timestamptz, text, bigint, bigint, text, text, text, text"
)


def upgrade() -> None:
    # The sealed 14-argument issuer and its runtime ACL remain unchanged.
    # The new wrapper delegates every capability, issuer, state, and TOTP
    # check to that issuer before its single protected password-hash CAS.
    op.execute(
        r"""
        CREATE FUNCTION public.walksafe_issue_admin_session_with_password_rehash(
          p_session_id uuid,
          p_admin_id text,
          p_device_id text,
          p_device_label text,
          p_token_sha256 text,
          p_issued_at timestamptz,
          p_expires_at timestamptz,
          p_step_up_verified_at timestamptz,
          p_last_seen_at timestamptz,
          p_authentication_kind text,
          p_expected_last_totp_timecode bigint,
          p_next_last_totp_timecode bigint,
          p_runtime_totp_secret text,
          p_credential_issuer_key text,
          p_expected_password_hash text,
          p_replacement_password_hash text
        ) RETURNS uuid AS $function$
        DECLARE
          issued_session_id uuid;
          changed_count integer;
        BEGIN
          IF p_authentication_kind IS DISTINCT FROM 'LOGIN'
            OR p_expected_password_hash IS NULL
            OR p_expected_password_hash !~
              '^scrypt-v1[$]16384[$]8[$]1[$][A-Za-z0-9_-]{22}[$][A-Za-z0-9_-]{43}$'
            OR p_replacement_password_hash IS NULL
            OR p_replacement_password_hash !~
              '^scrypt-v1[$]16384[$]8[$]5[$][A-Za-z0-9_-]{22}[$][A-Za-z0-9_-]{43}$'
          THEN
            RAISE EXCEPTION 'administrator password rehash payload is invalid'
              USING ERRCODE = '22023';
          END IF;

          issued_session_id := public.walksafe_issue_admin_session(
            p_session_id,
            p_admin_id,
            p_device_id,
            p_device_label,
            p_token_sha256,
            p_issued_at,
            p_expires_at,
            p_step_up_verified_at,
            p_last_seen_at,
            p_authentication_kind,
            p_expected_last_totp_timecode,
            p_next_last_totp_timecode,
            p_runtime_totp_secret,
            p_credential_issuer_key
          );

          UPDATE public.admin_security_controls AS control
          SET password_hash = p_replacement_password_hash
          WHERE control.admin_id = p_admin_id
            AND control.password_hash = p_expected_password_hash;
          GET DIAGNOSTICS changed_count = ROW_COUNT;
          IF changed_count <> 1 THEN
            RAISE EXCEPTION 'administrator password rehash is stale'
              USING ERRCODE = '40001';
          END IF;
          RETURN issued_session_id;
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION "
        "public.walksafe_issue_admin_session_with_password_rehash("
        f"{_REHASH_FUNCTION_SIGNATURE}) FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION "
        "public.walksafe_issue_admin_session_with_password_rehash("
        f"{_REHASH_FUNCTION_SIGNATURE}) TO walksafe_backend_runtime"
    )


def downgrade() -> None:
    # Historical password hashes are data; dropping the wrapper never rewrites
    # them or changes the sealed 14-argument issuer and its ACL.
    op.execute(
        "DROP FUNCTION public.walksafe_issue_admin_session_with_password_rehash("
        f"{_REHASH_FUNCTION_SIGNATURE})"
    )
