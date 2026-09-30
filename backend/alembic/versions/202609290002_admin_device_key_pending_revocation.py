"""Revoke PENDING administrator device keys on report-lost and recovery start.

S18-3-F1: 202609290001 added PENDING device keys, but report-lost and the
recovery start still revoked only ACTIVE keys, so a PENDING key registered
before the event survived it (and a recovery device's PENDING key could later
be confirmed). Both SECURITY DEFINER functions are replaced in place with
CREATE OR REPLACE (owner and EXECUTE ACL are kept) so that:

* walksafe_report_admin_lost_device revokes the lost device's ACTIVE and
  PENDING keys (its revoked_device_key_count now includes PENDING rows);
* walksafe_reset_recovery_custody revokes every PENDING key, including the
  recovery device's, and every ACTIVE key except the preserved recovery key.

The 202609290001 trigger already allows PENDING -> REVOKED. Downgrade restores
the verbatim 202608120001 bodies and checks md5(pg_proc.prosrc).

Revision ID: 202609290002
Revises: 202609290001
"""

from __future__ import annotations

import hashlib

from alembic import op

revision = "202609290002"
down_revision = "202609290001"
branch_labels = None
depends_on = None

_LOST_DEVICE_SIGNATURE = (
    "public.walksafe_report_admin_lost_device("
    "text, uuid, text, text, timestamp with time zone, text, text)"
)
_RESET_CUSTODY_SIGNATURE = (
    "public.walksafe_reset_recovery_custody("
    "text, boolean, text, timestamp with time zone, boolean, text, text)"
)
# md5(pg_proc.prosrc) of the 202608120001 bodies that downgrade restores.
_LOST_DEVICE_V1_PROSRC_MD5 = "25632ff9b1d94137f98b7c85ddc24b88"
_RESET_CUSTODY_V1_PROSRC_MD5 = "6983347319034b3fcd23a675e7117540"

# Verbatim 202608120001 definitions; only "CREATE FUNCTION <name>(" became
# "CREATE OR REPLACE FUNCTION public.<name>(" so the ACL is preserved.
_LOST_DEVICE_V1 = """
        CREATE OR REPLACE FUNCTION public.walksafe_report_admin_lost_device(
          p_admin_id text,
          p_current_session_id uuid,
          p_current_device_id text,
          p_target_device_id text,
          p_observed_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS TABLE (
          revoked_device_key_count bigint,
          revoked_session_count bigint,
          resulting_state_version bigint
        ) AS $$
        DECLARE
          current_security_state text;
          public_totp_fingerprint text;
          private_totp_fingerprint text;
          caller_device_id text;
          caller_expires_at timestamptz;
          caller_revoked_at timestamptz;
          target_exists boolean;
        BEGIN
          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION 'administrator lost-device capability is invalid'
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
            OR caller_device_id IS DISTINCT FROM p_current_device_id
          THEN
            RAISE EXCEPTION 'administrator lost-device capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_current_device_id IS NULL
            OR pg_catalog.length(p_current_device_id) < 1
            OR pg_catalog.length(p_current_device_id) > 128
            OR p_target_device_id IS NULL
            OR pg_catalog.length(p_target_device_id) < 1
            OR pg_catalog.length(p_target_device_id) > 128
            OR p_target_device_id = p_current_device_id
          THEN
            RAISE EXCEPTION 'administrator lost-device payload is invalid'
              USING ERRCODE = '22023';
          END IF;

          SELECT EXISTS (
            SELECT 1
            FROM public.admin_device_keys AS device_key
            WHERE device_key.admin_id = p_admin_id
              AND device_key.device_id = p_target_device_id
            UNION ALL
            SELECT 1
            FROM public.admin_security_sessions AS target_session
            WHERE target_session.admin_id = p_admin_id
              AND target_session.device_id = p_target_device_id
          ) INTO target_exists;
          IF NOT target_exists THEN
            RAISE EXCEPTION 'administrator lost device does not exist'
              USING ERRCODE = '22023';
          END IF;

          UPDATE public.admin_security_sessions AS target_session
          SET revoked_at = p_observed_at,
              revoked_reason = 'device_reported_lost'
          WHERE target_session.admin_id = p_admin_id
            AND target_session.device_id = p_target_device_id
            AND target_session.revoked_at IS NULL;
          GET DIAGNOSTICS revoked_session_count = ROW_COUNT;

          UPDATE public.admin_device_keys AS device_key
          SET status = 'REVOKED',
              revoked_at = p_observed_at
          WHERE device_key.admin_id = p_admin_id
            AND device_key.device_id = p_target_device_id
            AND device_key.status = 'ACTIVE'
            AND device_key.revoked_at IS NULL;
          GET DIAGNOSTICS revoked_device_key_count = ROW_COUNT;

          IF revoked_device_key_count > 0 OR revoked_session_count > 0 THEN
            UPDATE public.admin_security_controls AS control
            SET state_version = control.state_version + 1,
                updated_at = p_observed_at
            WHERE control.admin_id = p_admin_id
            RETURNING control.state_version INTO resulting_state_version;
          ELSE
            SELECT control.state_version
            INTO STRICT resulting_state_version
            FROM public.admin_security_controls AS control
            WHERE control.admin_id = p_admin_id;
          END IF;
          RETURN NEXT;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
_RESET_CUSTODY_V1 = """
        CREATE OR REPLACE FUNCTION public.walksafe_reset_recovery_custody(
          p_admin_id text,
          p_recovery_device_key_preserved boolean,
          p_recovery_token_sha256 text,
          p_recovery_expires_at timestamptz,
          p_resume boolean,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS bigint AS $$
        DECLARE
          previous_state text;
          current_security_state text;
          public_totp_fingerprint text;
          expected_totp_fingerprint text;
          previous_pending_token_sha256 text;
          previous_pending_expires_at timestamptz;
          recovery_device_id text;
          recovery_started_at timestamptz;
          recovery_device_key_count bigint;
          revoked_device_key_count bigint := 0;
          marker_details jsonb;
        BEGIN
          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION 'recovery custody capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT control.recovery_custody_state,
                 control.security_state,
                 control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint,
                 capability.pending_recovery_token_sha256,
                 capability.pending_recovery_expires_at
          INTO STRICT previous_state,
                      current_security_state,
                      public_totp_fingerprint,
                      expected_totp_fingerprint,
                      previous_pending_token_sha256,
                      previous_pending_expires_at
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability;

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
            ) IS DISTINCT FROM expected_totp_fingerprint
            OR public_totp_fingerprint IS DISTINCT FROM
              expected_totp_fingerprint
          THEN
            RAISE EXCEPTION 'recovery custody capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_recovery_token_sha256 IS NULL
            OR p_recovery_token_sha256 !~ '^[0-9a-f]{64}$'
            OR p_recovery_expires_at IS NULL
            OR p_recovery_expires_at <= pg_catalog.statement_timestamp()
            OR p_resume IS NULL
            OR p_recovery_device_key_preserved IS NULL
            OR (
              p_resume
              AND current_security_state <> 'RECOVERY_IN_PROGRESS'
            )
          THEN
            RAISE EXCEPTION 'recovery token binding is invalid'
              USING ERRCODE = '22023';
          END IF;

          IF p_resume AND (
            previous_pending_token_sha256 IS NULL
            OR previous_pending_expires_at IS NULL
            OR previous_pending_expires_at <= pg_catalog.statement_timestamp()
            OR p_recovery_expires_at IS DISTINCT FROM previous_pending_expires_at
          ) THEN
            RAISE EXCEPTION 'recovery expiry binding is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF NOT p_resume
            AND previous_pending_expires_at > pg_catalog.statement_timestamp()
          THEN
            RAISE EXCEPTION 'active recovery capability cannot be replaced'
              USING ERRCODE = '42501';
          END IF;
          SELECT recovery.device_id, recovery.started_at
          INTO recovery_device_id, recovery_started_at
          FROM public.admin_security_recovery_transactions AS recovery
          WHERE recovery.admin_id = p_admin_id
            AND recovery.recovery_token_sha256 = p_recovery_token_sha256
            AND recovery.completed_at IS NULL
            AND recovery.expires_at IS NOT DISTINCT FROM p_recovery_expires_at
          FOR UPDATE;
          IF recovery_device_id IS NULL THEN
            RAISE EXCEPTION 'recovery transaction binding is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF NOT p_resume AND p_recovery_device_key_preserved THEN
            SELECT pg_catalog.count(*)
            INTO recovery_device_key_count
            FROM public.admin_device_keys AS device_key
            WHERE device_key.admin_id = p_admin_id
              AND device_key.device_id = recovery_device_id
              AND device_key.status = 'ACTIVE'
              AND device_key.revoked_at IS NULL;
            IF recovery_device_key_count <> 1 THEN
              RAISE EXCEPTION 'recovery device key binding is invalid'
                USING ERRCODE = '42501';
            END IF;
          END IF;

          UPDATE public.walksafe_recovery_custody_capabilities AS capability
          SET pending_recovery_token_sha256 = p_recovery_token_sha256,
              pending_recovery_expires_at = p_recovery_expires_at,
              pending_next_totp_fingerprint = NULL
          WHERE capability.admin_id = p_admin_id;

          IF NOT p_resume AND previous_state <> 'UNATTESTED' THEN
            marker_details := pg_catalog.jsonb_build_object(
              'all_sessions_revoked', true,
              'custody_reset', true,
              'other_device_keys_revoked', true,
              'previous_custody_state', previous_state,
              'recovery_device_key_preserved', p_recovery_device_key_preserved
            );
            INSERT INTO public.walksafe_recovery_custody_markers (
              transaction_id,
              admin_id,
              action,
              previous_custody_state,
              next_custody_state,
              details
            ) VALUES (
              pg_catalog.pg_current_xact_id()::text::bigint,
              p_admin_id,
              'recovery.start',
              previous_state,
              'UNATTESTED',
              marker_details
            );
          END IF;
          IF NOT p_resume THEN
            UPDATE public.admin_security_sessions
            SET revoked_at = recovery_started_at,
                revoked_reason = 'recovery_started'
            WHERE admin_id = p_admin_id
              AND revoked_at IS NULL;

            UPDATE public.admin_device_keys AS device_key
            SET status = 'REVOKED',
                revoked_at = recovery_started_at
            WHERE device_key.admin_id = p_admin_id
              AND device_key.status = 'ACTIVE'
              AND device_key.revoked_at IS NULL
              AND NOT (
                p_recovery_device_key_preserved
                AND device_key.device_id = recovery_device_id
              );
            GET DIAGNOSTICS revoked_device_key_count = ROW_COUNT;

            UPDATE public.admin_security_controls
            SET recovery_custody_state = 'UNATTESTED',
                recovery_custody_attested_at = NULL,
                recovery_custody_reference_sha256 = NULL,
                recovery_custody_material_kind = NULL,
                recovery_custody_storage_location = NULL,
                recovery_custody_separate_backup_confirmed = false,
                security_state = 'RECOVERY_IN_PROGRESS',
                state_version = state_version + 1,
                updated_at = recovery_started_at
            WHERE admin_id = p_admin_id;
          END IF;
          RETURN revoked_device_key_count;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """


def _prosrc(definition: str) -> str:
    start = definition.index("AS $$") + len("AS $$")
    return definition[start:definition.index("$$ LANGUAGE", start)]


def _prosrc_md5(definition: str) -> str:
    return hashlib.md5(_prosrc(definition).encode("utf-8")).hexdigest()


def _replace_once(definition: str, old: str, new: str) -> str:
    if definition.count(old) != 1:
        raise RuntimeError("expected exactly one device key revocation clause")
    return definition.replace(old, new)


_LOST_DEVICE_V2 = _replace_once(
    _LOST_DEVICE_V1,
    """            AND device_key.status = 'ACTIVE'
            AND device_key.revoked_at IS NULL;
          GET DIAGNOSTICS revoked_device_key_count = ROW_COUNT;""",
    """            AND device_key.status IN ('ACTIVE', 'PENDING')
            AND device_key.revoked_at IS NULL;
          GET DIAGNOSTICS revoked_device_key_count = ROW_COUNT;""",
)
_RESET_CUSTODY_V2 = _replace_once(
    _RESET_CUSTODY_V1,
    """              AND device_key.status = 'ACTIVE'
              AND device_key.revoked_at IS NULL
              AND NOT (
                p_recovery_device_key_preserved
                AND device_key.device_id = recovery_device_id
              );""",
    """              AND device_key.status IN ('ACTIVE', 'PENDING')
              AND device_key.revoked_at IS NULL
              AND NOT (
                device_key.status = 'ACTIVE'
                AND p_recovery_device_key_preserved
                AND device_key.device_id = recovery_device_id
              );""",
)

# The embedded V1 text must be the exact body 202608120001 installed.
if _prosrc_md5(_LOST_DEVICE_V1) != _LOST_DEVICE_V1_PROSRC_MD5:
    raise RuntimeError("embedded report-lost function body drifted")
if _prosrc_md5(_RESET_CUSTODY_V1) != _RESET_CUSTODY_V1_PROSRC_MD5:
    raise RuntimeError("embedded recovery custody function body drifted")


def _require_prosrc_md5(signature: str, expected_md5: str) -> None:
    op.execute(
        f"""
        DO $guard$
        BEGIN
          IF (
            SELECT pg_catalog.md5(procedure.prosrc)
            FROM pg_catalog.pg_proc AS procedure
            WHERE procedure.oid = '{signature}'::pg_catalog.regprocedure
          ) IS DISTINCT FROM '{expected_md5}' THEN
            RAISE EXCEPTION 'unexpected definition of {signature}'
              USING ERRCODE = '55000';
          END IF;
        END
        $guard$
        """
    )


def upgrade() -> None:
    _require_prosrc_md5(_LOST_DEVICE_SIGNATURE, _LOST_DEVICE_V1_PROSRC_MD5)
    _require_prosrc_md5(_RESET_CUSTODY_SIGNATURE, _RESET_CUSTODY_V1_PROSRC_MD5)
    op.execute(_LOST_DEVICE_V2)
    op.execute(_RESET_CUSTODY_V2)
    _require_prosrc_md5(_LOST_DEVICE_SIGNATURE, _prosrc_md5(_LOST_DEVICE_V2))
    _require_prosrc_md5(_RESET_CUSTODY_SIGNATURE, _prosrc_md5(_RESET_CUSTODY_V2))


def downgrade() -> None:
    op.execute(_LOST_DEVICE_V1)
    op.execute(_RESET_CUSTODY_V1)
    _require_prosrc_md5(_LOST_DEVICE_SIGNATURE, _LOST_DEVICE_V1_PROSRC_MD5)
    _require_prosrc_md5(_RESET_CUSTODY_SIGNATURE, _RESET_CUSTODY_V1_PROSRC_MD5)
