"""isolate administrator original-evidence mutations behind narrow functions

Revision ID: 202608300004
Revises: 202608300003
Create Date: 2026-08-30
"""

from __future__ import annotations

from alembic import op


revision = "202608300004"
down_revision = "202608300003"
branch_labels = None
depends_on = None


_OWNER_ROLE = "walksafe_report_evidence_owner"
_RUNTIME_ROLE = "walksafe_backend_runtime"

_ISSUE_SIGNATURE = (
    "public.walksafe_issue_report_original_evidence_grant("
    "uuid, uuid, uuid, uuid, text, uuid, text, text, text, text, bigint, "
    "timestamptz, timestamptz, text, text)"
)
_PREPARE_SIGNATURE = (
    "public.walksafe_prepare_report_original_evidence_access("
    "uuid, text, text, uuid, text, timestamptz, text, text)"
)
_FAILURE_SIGNATURE = (
    "public.walksafe_record_report_original_evidence_failure("
    "uuid, uuid, uuid, text, uuid, text, text, text, text, text, text, "
    "smallint, boolean, timestamptz, text, text)"
)
_COMPLETE_SIGNATURE = (
    "public.walksafe_complete_report_original_evidence_access("
    "uuid, uuid, uuid, text, uuid, text, text, bigint, text, smallint, text, "
    "bigint, text, timestamptz, text, text)"
)
_REVIEW_SIGNATURE = (
    "public.walksafe_append_report_review_decision("
    "uuid, uuid, bigint, text, text, text, uuid, boolean, boolean, boolean, "
    "uuid, text, uuid, text, uuid, timestamptz, text, text)"
)
_FUNCTION_SIGNATURES = (
    _ISSUE_SIGNATURE,
    _PREPARE_SIGNATURE,
    _FAILURE_SIGNATURE,
    _COMPLETE_SIGNATURE,
    _REVIEW_SIGNATURE,
)


def _create_owner_role() -> None:
    op.execute(
        f"""
        DO $role$
        DECLARE role_record record;
        BEGIN
          SELECT rolsuper, rolcreaterole, rolcreatedb, rolcanlogin,
                 rolinherit, rolreplication, rolbypassrls
            INTO role_record
            FROM pg_catalog.pg_roles
           WHERE rolname = '{_OWNER_ROLE}';
          IF NOT FOUND THEN
            CREATE ROLE {_OWNER_ROLE}
              NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE
              NOREPLICATION NOBYPASSRLS;
          ELSIF role_record.rolsuper OR role_record.rolcreaterole
             OR role_record.rolcreatedb OR role_record.rolcanlogin
             OR role_record.rolinherit OR role_record.rolreplication
             OR role_record.rolbypassrls THEN
            RAISE EXCEPTION '{_OWNER_ROLE} has unsafe role attributes'
              USING ERRCODE = '42501';
          END IF;
          IF pg_catalog.pg_has_role(
               '{_RUNTIME_ROLE}', '{_OWNER_ROLE}', 'MEMBER'
             ) THEN
            RAISE EXCEPTION '{_RUNTIME_ROLE} must not inherit evidence owner'
              USING ERRCODE = '42501';
          END IF;
        END
        $role$
        """
    )
    op.execute(
        f"GRANT {_OWNER_ROLE} TO CURRENT_USER WITH SET TRUE, INHERIT TRUE"
    )
    op.execute(f"REVOKE {_OWNER_ROLE} FROM {_RUNTIME_ROLE}")
    op.execute(f"REVOKE ALL PRIVILEGES ON SCHEMA public FROM {_OWNER_ROLE}")
    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE public.reports, "
        "public.report_image_objects, public.report_original_access_grants, "
        "public.report_original_access_audits, public.report_review_decisions "
        f"FROM {_OWNER_ROLE}"
    )
    op.execute(
        "REVOKE ALL PRIVILEGES ON FUNCTION "
        "public.walksafe_lock_admin_original_access_session("
        "text, uuid, text, timestamptz, text, text) "
        f"FROM {_OWNER_ROLE}"
    )
    op.execute(f"GRANT USAGE, CREATE ON SCHEMA public TO {_OWNER_ROLE}")
    op.execute(
        f"GRANT EXECUTE ON FUNCTION "
        "public.walksafe_lock_admin_original_access_session("
        "text, uuid, text, timestamptz, text, text) "
        f"TO {_OWNER_ROLE}"
    )
    op.execute(
        "GRANT SELECT ON TABLE public.reports, public.report_image_objects, "
        "public.report_original_access_grants, "
        "public.report_original_access_audits, public.report_review_decisions "
        f"TO {_OWNER_ROLE}"
    )
    op.execute(
        f"GRANT UPDATE (updated_at) ON TABLE public.reports TO {_OWNER_ROLE}"
    )
    op.execute(
        "GRANT INSERT, UPDATE (consumed_at, access_granted_at, "
        "review_decision_id, review_bound_at) ON TABLE "
        f"public.report_original_access_grants TO {_OWNER_ROLE}"
    )
    op.execute(
        "GRANT INSERT ON TABLE public.report_original_access_audits, "
        f"public.report_review_decisions TO {_OWNER_ROLE}"
    )


def _install_issue_function() -> None:
    op.execute(
        """
        CREATE FUNCTION public.walksafe_issue_report_original_evidence_grant(
          p_grant_id uuid,
          p_grant_audit_id uuid,
          p_location_audit_id uuid,
          p_report_id uuid,
          p_admin_id text,
          p_session_id uuid,
          p_device_id text,
          p_purpose text,
          p_reason text,
          p_token_sha256 text,
          p_expected_content_revision bigint,
          p_issued_at timestamptz,
          p_expires_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS TABLE (
          grant_id uuid,
          content_revision bigint,
          expires_at timestamptz,
          latitude double precision,
          longitude double precision,
          accuracy_m double precision,
          resource_path text,
          content_type text,
          image_sha256 text,
          image_byte_count bigint
        ) AS $function$
        DECLARE
          locked_content_revision bigint;
          locked_latitude double precision;
          locked_longitude double precision;
          locked_accuracy_m double precision;
          locked_resource_path text;
          locked_report_content_type text;
          locked_image_content_type text;
          locked_image_sha256 text;
          locked_image_byte_count bigint;
          locked_key_id text;
          locked_envelope_version smallint;
        BEGIN
          IF p_grant_id IS NULL OR p_grant_audit_id IS NULL
            OR p_location_audit_id IS NULL
            OR p_grant_id IN (p_grant_audit_id, p_location_audit_id)
            OR p_grant_audit_id = p_location_audit_id
            OR p_report_id IS NULL OR p_admin_id IS NULL
            OR pg_catalog.length(p_admin_id) NOT BETWEEN 1 AND 64
            OR p_session_id IS NULL OR p_device_id IS NULL
            OR pg_catalog.length(p_device_id) NOT BETWEEN 1 AND 128
            OR p_purpose NOT IN (
              'report_review', 'security_incident', 'data_subject_request'
            )
            OR p_reason IS NULL
            OR pg_catalog.length(p_reason) NOT BETWEEN 8 AND 500
            OR p_token_sha256 IS NULL
            OR p_token_sha256 !~ '^[0-9a-f]{64}$'
            OR p_expected_content_revision IS NULL
            OR p_expected_content_revision < 0
            OR p_issued_at IS NULL OR p_expires_at IS NULL
            OR p_expires_at <= p_issued_at
            OR p_expires_at > p_issued_at + pg_catalog.make_interval(secs => 300)
          THEN
            RAISE EXCEPTION 'original evidence grant input is invalid'
              USING ERRCODE = '22023';
          END IF;
          IF public.walksafe_lock_admin_original_access_session(
               p_admin_id, p_session_id, p_device_id, p_issued_at,
               p_runtime_totp_secret, p_credential_issuer_key
             ) IS DISTINCT FROM true THEN
            RAISE EXCEPTION 'original evidence grant authorization is invalid'
              USING ERRCODE = '42501';
          END IF;

          SELECT report.content_revision,
                 report.latitude,
                 report.longitude,
                 report.accuracy_m,
                 report.image_path,
                 report.image_content_type,
                 image_object.content_type,
                 image_object.plaintext_sha256,
                 image_object.plaintext_size,
                 image_object.key_id,
                 image_object.envelope_version
            INTO STRICT locked_content_revision,
                        locked_latitude,
                        locked_longitude,
                        locked_accuracy_m,
                        locked_resource_path,
                        locked_report_content_type,
                        locked_image_content_type,
                        locked_image_sha256,
                        locked_image_byte_count,
                        locked_key_id,
                        locked_envelope_version
            FROM public.reports AS report
            JOIN public.report_image_objects AS image_object
              ON image_object.report_id = report.id
           WHERE report.id = p_report_id
           FOR UPDATE OF report;

          IF locked_content_revision <> p_expected_content_revision
            OR locked_latitude IS NULL OR locked_longitude IS NULL
            OR locked_latitude NOT BETWEEN -90 AND 90
            OR locked_longitude NOT BETWEEN -180 AND 180
            OR (
              locked_accuracy_m IS NOT NULL
              AND (
                locked_accuracy_m < 0
                OR locked_accuracy_m >= 'Infinity'::double precision
              )
            )
            OR locked_resource_path NOT IN (
              '/uploads/' || p_report_id::text || '.jpg',
              '/uploads/' || p_report_id::text || '.png',
              '/uploads/' || p_report_id::text || '.webp'
            )
            OR locked_report_content_type IS DISTINCT FROM
               locked_image_content_type
            OR locked_image_content_type NOT IN (
              'image/jpeg', 'image/png', 'image/webp'
            )
            OR locked_image_sha256 !~ '^[0-9a-f]{64}$'
            OR locked_image_byte_count <= 0
            OR locked_key_id IS NULL
            OR pg_catalog.length(locked_key_id) NOT BETWEEN 1 AND 64
            OR locked_envelope_version <> 1
          THEN
            RAISE EXCEPTION 'original evidence grant binding is invalid'
              USING ERRCODE = '23514';
          END IF;

          INSERT INTO public.report_original_access_grants (
            id, report_id, admin_id, session_id, device_id, purpose, reason,
            token_sha256, issued_at, expires_at, content_revision,
            location_disclosed_at
          ) VALUES (
            p_grant_id, p_report_id, p_admin_id, p_session_id, p_device_id,
            p_purpose, p_reason, p_token_sha256, p_issued_at, p_expires_at,
            locked_content_revision, p_issued_at
          );
          INSERT INTO public.report_original_access_audits (
            id, grant_id, report_id, admin_id, session_id, device_id, purpose,
            action, outcome, reason_code, key_id, envelope_version
          ) VALUES (
            p_grant_audit_id, p_grant_id, p_report_id, p_admin_id,
            p_session_id, p_device_id, p_purpose, 'GRANT_ISSUED', 'SUCCESS',
            'approved', locked_key_id, locked_envelope_version
          );
          INSERT INTO public.report_original_access_audits (
            id, grant_id, report_id, admin_id, session_id, device_id, purpose,
            action, outcome, reason_code
          ) VALUES (
            p_location_audit_id, p_grant_id, p_report_id, p_admin_id,
            p_session_id, p_device_id, p_purpose, 'LOCATION_DISCLOSED',
            'SUCCESS', 'exact_location_disclosed'
          );
          RETURN QUERY SELECT
            p_grant_id, locked_content_revision, p_expires_at,
            locked_latitude, locked_longitude, locked_accuracy_m,
            locked_resource_path, locked_image_content_type,
            locked_image_sha256, locked_image_byte_count;
        EXCEPTION
          WHEN NO_DATA_FOUND OR TOO_MANY_ROWS THEN
            RAISE EXCEPTION 'original evidence report binding is invalid'
              USING ERRCODE = '23514';
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )


def _install_prepare_function() -> None:
    op.execute(
        """
        CREATE FUNCTION public.walksafe_prepare_report_original_evidence_access(
          p_report_id uuid,
          p_token_sha256 text,
          p_admin_id text,
          p_session_id uuid,
          p_device_id text,
          p_observed_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS TABLE (
          access_status text,
          reason_code text,
          consume_grant boolean,
          grant_id uuid,
          purpose text,
          content_revision bigint,
          expires_at timestamptz,
          storage_name text,
          envelope_size bigint,
          envelope_sha256 text,
          key_id text,
          envelope_version smallint,
          content_type text,
          plaintext_sha256 text,
          plaintext_size bigint
        ) AS $function$
        DECLARE
          authorization_locked boolean;
          locked_report public.reports%ROWTYPE;
          locked_grant public.report_original_access_grants%ROWTYPE;
          locked_image public.report_image_objects%ROWTYPE;
          evidence_audit_count integer;
        BEGIN
          IF p_report_id IS NULL OR p_token_sha256 IS NULL
            OR p_token_sha256 !~ '^[0-9a-f]{64}$'
            OR p_admin_id IS NULL OR p_session_id IS NULL
            OR p_device_id IS NULL OR p_observed_at IS NULL
          THEN
            RAISE EXCEPTION 'original evidence access input is invalid'
              USING ERRCODE = '22023';
          END IF;
          authorization_locked := public.walksafe_lock_admin_original_access_session(
            p_admin_id, p_session_id, p_device_id, p_observed_at,
            p_runtime_totp_secret, p_credential_issuer_key
          );
          IF authorization_locked IS DISTINCT FROM true THEN
            RETURN QUERY SELECT
              'DENIED'::text,
              'admin_session_or_security_state_changed'::text,
              false, NULL::uuid, NULL::text, NULL::bigint, NULL::timestamptz,
              NULL::text, NULL::bigint, NULL::text, NULL::text,
              NULL::smallint, NULL::text, NULL::text, NULL::bigint;
            RETURN;
          END IF;

          SELECT report.* INTO locked_report
            FROM public.reports AS report
           WHERE report.id = p_report_id
           FOR UPDATE;
          IF NOT FOUND THEN
            RETURN QUERY SELECT
              'NOT_FOUND'::text, 'report_not_found'::text, false,
              NULL::uuid, NULL::text, NULL::bigint, NULL::timestamptz,
              NULL::text, NULL::bigint, NULL::text, NULL::text,
              NULL::smallint, NULL::text, NULL::text, NULL::bigint;
            RETURN;
          END IF;

          SELECT evidence_grant.* INTO locked_grant
            FROM public.report_original_access_grants AS evidence_grant
           WHERE evidence_grant.report_id = p_report_id
             AND evidence_grant.token_sha256 = p_token_sha256
           FOR UPDATE;
          IF NOT FOUND THEN
            RETURN QUERY SELECT
              'DENIED'::text, 'grant_invalid'::text, false,
              NULL::uuid, NULL::text, NULL::bigint, NULL::timestamptz,
              NULL::text, NULL::bigint, NULL::text, NULL::text,
              NULL::smallint, NULL::text, NULL::text, NULL::bigint;
            RETURN;
          END IF;
          IF locked_grant.admin_id <> p_admin_id
            OR locked_grant.session_id <> p_session_id
            OR locked_grant.device_id <> p_device_id
          THEN
            RETURN QUERY SELECT
              'DENIED'::text, 'grant_identity_mismatch'::text, false,
              locked_grant.id, locked_grant.purpose::text,
              locked_grant.content_revision, locked_grant.expires_at,
              NULL::text, NULL::bigint, NULL::text, NULL::text,
              NULL::smallint, NULL::text, NULL::text, NULL::bigint;
            RETURN;
          END IF;
          IF locked_grant.content_revision IS NULL
            OR locked_grant.location_disclosed_at IS NULL
            OR locked_grant.content_revision <> locked_report.content_revision
          THEN
            RETURN QUERY SELECT
              'DENIED'::text, 'grant_content_revision_mismatch'::text, true,
              locked_grant.id, locked_grant.purpose::text,
              locked_grant.content_revision, locked_grant.expires_at,
              NULL::text, NULL::bigint, NULL::text, NULL::text,
              NULL::smallint, NULL::text, NULL::text, NULL::bigint;
            RETURN;
          END IF;
          IF locked_grant.consumed_at IS NOT NULL
            OR locked_grant.expires_at <= p_observed_at
          THEN
            RETURN QUERY SELECT
              'DENIED'::text, 'grant_expired_or_consumed'::text, false,
              locked_grant.id, locked_grant.purpose::text,
              locked_grant.content_revision, locked_grant.expires_at,
              NULL::text, NULL::bigint, NULL::text, NULL::text,
              NULL::smallint, NULL::text, NULL::text, NULL::bigint;
            RETURN;
          END IF;

          SELECT image_object.* INTO locked_image
            FROM public.report_image_objects AS image_object
           WHERE image_object.report_id = p_report_id;
          IF NOT FOUND THEN
            RETURN QUERY SELECT
              'GONE'::text, 'legacy_plaintext_unavailable'::text, true,
              locked_grant.id, locked_grant.purpose::text,
              locked_grant.content_revision, locked_grant.expires_at,
              NULL::text, NULL::bigint, NULL::text, NULL::text,
              NULL::smallint, NULL::text, NULL::text, NULL::bigint;
            RETURN;
          END IF;
          IF locked_report.image_path NOT IN (
               '/uploads/' || p_report_id::text || '.jpg',
               '/uploads/' || p_report_id::text || '.png',
               '/uploads/' || p_report_id::text || '.webp'
             )
            OR locked_report.image_content_type IS DISTINCT FROM
               locked_image.content_type
            OR locked_image.envelope_version <> 1
            OR locked_image.envelope_size <= locked_image.plaintext_size + 16
            OR locked_image.envelope_sha256 !~ '^[0-9a-f]{64}$'
            OR locked_image.plaintext_sha256 !~ '^[0-9a-f]{64}$'
            OR locked_image.plaintext_size <= 0
          THEN
            RETURN QUERY SELECT
              'ERROR'::text, 'image_metadata_mismatch'::text, true,
              locked_grant.id, locked_grant.purpose::text,
              locked_grant.content_revision, locked_grant.expires_at,
              NULL::text, NULL::bigint, NULL::text, locked_image.key_id::text,
              locked_image.envelope_version, NULL::text, NULL::text, NULL::bigint;
            RETURN;
          END IF;
          SELECT pg_catalog.count(DISTINCT audit.action)::integer
            INTO evidence_audit_count
            FROM public.report_original_access_audits AS audit
           WHERE audit.grant_id = locked_grant.id
             AND audit.report_id = p_report_id
             AND audit.admin_id = p_admin_id
             AND audit.session_id = p_session_id
             AND audit.device_id = p_device_id
             AND audit.purpose = locked_grant.purpose
             AND audit.action IN ('GRANT_ISSUED', 'LOCATION_DISCLOSED')
             AND audit.outcome = 'SUCCESS'
             AND audit.created_at <= p_observed_at;
          IF evidence_audit_count <> 2 THEN
            RETURN QUERY SELECT
              'ERROR'::text, 'grant_audit_missing'::text, true,
              locked_grant.id, locked_grant.purpose::text,
              locked_grant.content_revision, locked_grant.expires_at,
              NULL::text, NULL::bigint, NULL::text, locked_image.key_id::text,
              locked_image.envelope_version, NULL::text, NULL::text, NULL::bigint;
            RETURN;
          END IF;
          RETURN QUERY SELECT
            'READY'::text, 'approved'::text, false,
            locked_grant.id, locked_grant.purpose::text,
            locked_grant.content_revision, locked_grant.expires_at,
            locked_image.storage_name::text, locked_image.envelope_size,
            locked_image.envelope_sha256::text, locked_image.key_id::text,
            locked_image.envelope_version, locked_image.content_type::text,
            locked_image.plaintext_sha256::text, locked_image.plaintext_size;
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )


def _install_failure_function() -> None:
    op.execute(
        """
        CREATE FUNCTION public.walksafe_record_report_original_evidence_failure(
          p_audit_id uuid,
          p_report_id uuid,
          p_grant_id uuid,
          p_admin_id text,
          p_session_id uuid,
          p_device_id text,
          p_purpose text,
          p_action text,
          p_outcome text,
          p_reason_code text,
          p_key_id text,
          p_envelope_version smallint,
          p_consume_grant boolean,
          p_observed_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS boolean AS $function$
        DECLARE
          authorization_locked boolean;
          updated_count integer;
        BEGIN
          IF p_audit_id IS NULL OR p_admin_id IS NULL
            OR p_session_id IS NULL OR p_device_id IS NULL
            OR p_action NOT IN (
              'GRANT_DENIED', 'GRANT_ERROR', 'ACCESS_DENIED', 'ACCESS_ERROR'
            )
            OR (p_action IN ('GRANT_DENIED', 'ACCESS_DENIED') AND p_outcome <> 'DENIED')
            OR (p_action IN ('GRANT_ERROR', 'ACCESS_ERROR') AND p_outcome <> 'ERROR')
            OR p_reason_code NOT IN (
              'report_not_found', 'content_revision_conflict',
              'exact_location_unavailable', 'legacy_plaintext_unavailable',
              'image_metadata_mismatch', 'key_unavailable',
              'invalid_resource_name', 'admin_session_or_security_state_changed',
              'admin_security_control_unavailable', 'admin_security_state_changed',
              'admin_session_changed', 'report_changed', 'grant_invalid',
              'grant_identity_mismatch', 'grant_content_revision_mismatch',
              'grant_expired_or_consumed', 'encrypted_original_unavailable',
              'authorization_expired_before_audit_commit', 'grant_audit_missing'
            )
            OR (p_envelope_version IS NOT NULL AND p_envelope_version <> 1)
            OR p_consume_grant IS NULL OR p_observed_at IS NULL
          THEN
            RAISE EXCEPTION 'original evidence failure audit input is invalid'
              USING ERRCODE = '22023';
          END IF;
          authorization_locked := public.walksafe_lock_admin_original_access_session(
            p_admin_id, p_session_id, p_device_id, p_observed_at,
            p_runtime_totp_secret, p_credential_issuer_key
          );
          IF authorization_locked IS DISTINCT FROM true
            AND NOT (
              p_action = 'ACCESS_DENIED'
              AND p_reason_code IN (
                'admin_session_or_security_state_changed',
                'admin_security_state_changed', 'admin_session_changed',
                'authorization_expired_before_audit_commit'
              )
            )
          THEN
            RAISE EXCEPTION 'original evidence failure audit authorization is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_grant_id IS NOT NULL AND NOT EXISTS (
            SELECT 1
              FROM public.report_original_access_grants AS evidence_grant
             WHERE evidence_grant.id = p_grant_id
               AND evidence_grant.report_id IS NOT DISTINCT FROM p_report_id
               AND evidence_grant.admin_id = p_admin_id
               AND evidence_grant.session_id = p_session_id
               AND evidence_grant.device_id = p_device_id
               AND evidence_grant.purpose IS NOT DISTINCT FROM p_purpose
          ) THEN
            RAISE EXCEPTION 'original evidence failure grant binding is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_consume_grant THEN
            IF p_grant_id IS NULL THEN
              RAISE EXCEPTION 'original evidence failure consume binding is invalid'
                USING ERRCODE = '22023';
            END IF;
            UPDATE public.report_original_access_grants AS evidence_grant
               SET consumed_at = p_observed_at
             WHERE evidence_grant.id = p_grant_id
               AND evidence_grant.report_id = p_report_id
               AND evidence_grant.admin_id = p_admin_id
               AND evidence_grant.session_id = p_session_id
               AND evidence_grant.device_id = p_device_id
               AND evidence_grant.purpose IS NOT DISTINCT FROM p_purpose
               AND evidence_grant.consumed_at IS NULL;
            GET DIAGNOSTICS updated_count = ROW_COUNT;
            IF updated_count <> 1 THEN
              RAISE EXCEPTION 'original evidence grant was not consumed once'
                USING ERRCODE = '40001';
            END IF;
          END IF;
          INSERT INTO public.report_original_access_audits (
            id, grant_id, report_id, admin_id, session_id, device_id, purpose,
            action, outcome, reason_code, key_id, envelope_version
          ) VALUES (
            p_audit_id, p_grant_id, p_report_id, p_admin_id, p_session_id,
            p_device_id, p_purpose, p_action, p_outcome, p_reason_code,
            p_key_id, p_envelope_version
          );
          RETURN true;
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )


def _install_complete_function() -> None:
    op.execute(
        """
        CREATE FUNCTION public.walksafe_complete_report_original_evidence_access(
          p_access_audit_id uuid,
          p_grant_id uuid,
          p_report_id uuid,
          p_admin_id text,
          p_session_id uuid,
          p_device_id text,
          p_purpose text,
          p_content_revision bigint,
          p_key_id text,
          p_envelope_version smallint,
          p_plaintext_sha256 text,
          p_plaintext_size bigint,
          p_content_type text,
          p_completed_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS boolean AS $function$
        DECLARE
          updated_count integer;
          evidence_audit_count integer;
        BEGIN
          IF p_access_audit_id IS NULL OR p_grant_id IS NULL
            OR p_report_id IS NULL OR p_admin_id IS NULL
            OR p_session_id IS NULL OR p_device_id IS NULL
            OR p_purpose NOT IN (
              'report_review', 'security_incident', 'data_subject_request'
            )
            OR p_content_revision IS NULL OR p_content_revision < 0
            OR p_key_id IS NULL OR p_envelope_version <> 1
            OR p_plaintext_sha256 !~ '^[0-9a-f]{64}$'
            OR p_plaintext_size IS NULL OR p_plaintext_size <= 0
            OR p_content_type NOT IN ('image/jpeg', 'image/png', 'image/webp')
            OR p_completed_at IS NULL
          THEN
            RAISE EXCEPTION 'original evidence completion input is invalid'
              USING ERRCODE = '22023';
          END IF;
          IF public.walksafe_lock_admin_original_access_session(
               p_admin_id, p_session_id, p_device_id, p_completed_at,
               p_runtime_totp_secret, p_credential_issuer_key
             ) IS DISTINCT FROM true THEN
            RETURN false;
          END IF;
          PERFORM report.id
            FROM public.reports AS report
           WHERE report.id = p_report_id
             AND report.content_revision = p_content_revision
             AND report.image_content_type = p_content_type
           FOR UPDATE;
          IF NOT FOUND THEN
            RETURN false;
          END IF;
          PERFORM evidence_grant.id
            FROM public.report_original_access_grants AS evidence_grant
           WHERE evidence_grant.id = p_grant_id
             AND evidence_grant.report_id = p_report_id
             AND evidence_grant.admin_id = p_admin_id
             AND evidence_grant.session_id = p_session_id
             AND evidence_grant.device_id = p_device_id
             AND evidence_grant.purpose = p_purpose
             AND evidence_grant.content_revision = p_content_revision
             AND evidence_grant.location_disclosed_at IS NOT NULL
             AND evidence_grant.consumed_at IS NULL
             AND evidence_grant.access_granted_at IS NULL
             AND evidence_grant.review_decision_id IS NULL
             AND evidence_grant.review_bound_at IS NULL
             AND evidence_grant.expires_at > p_completed_at
           FOR UPDATE;
          IF NOT FOUND THEN
            RETURN false;
          END IF;
          IF NOT EXISTS (
            SELECT 1
              FROM public.report_image_objects AS image_object
             WHERE image_object.report_id = p_report_id
               AND image_object.key_id = p_key_id
               AND image_object.envelope_version = p_envelope_version
               AND image_object.plaintext_sha256 = p_plaintext_sha256
               AND image_object.plaintext_size = p_plaintext_size
               AND image_object.content_type = p_content_type
          ) THEN
            RETURN false;
          END IF;
          SELECT pg_catalog.count(DISTINCT audit.action)::integer
            INTO evidence_audit_count
            FROM public.report_original_access_audits AS audit
           WHERE audit.grant_id = p_grant_id
             AND audit.report_id = p_report_id
             AND audit.admin_id = p_admin_id
             AND audit.session_id = p_session_id
             AND audit.device_id = p_device_id
             AND audit.purpose = p_purpose
             AND audit.action IN ('GRANT_ISSUED', 'LOCATION_DISCLOSED')
             AND audit.outcome = 'SUCCESS'
             AND audit.created_at <= p_completed_at;
          IF evidence_audit_count <> 2 THEN
            RETURN false;
          END IF;
          UPDATE public.report_original_access_grants AS evidence_grant
             SET consumed_at = p_completed_at,
                 access_granted_at = p_completed_at
           WHERE evidence_grant.id = p_grant_id
             AND evidence_grant.consumed_at IS NULL
             AND evidence_grant.access_granted_at IS NULL
             AND evidence_grant.expires_at > p_completed_at;
          GET DIAGNOSTICS updated_count = ROW_COUNT;
          IF updated_count <> 1 THEN
            RETURN false;
          END IF;
          INSERT INTO public.report_original_access_audits (
            id, grant_id, report_id, admin_id, session_id, device_id, purpose,
            action, outcome, reason_code, key_id, envelope_version
          ) VALUES (
            p_access_audit_id, p_grant_id, p_report_id, p_admin_id,
            p_session_id, p_device_id, p_purpose, 'ACCESS_GRANTED', 'SUCCESS',
            'approved_grant_consumed', p_key_id, p_envelope_version
          );
          RETURN true;
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )


def _install_review_function() -> None:
    op.execute(
        """
        CREATE FUNCTION public.walksafe_append_report_review_decision(
          p_decision_id uuid,
          p_report_id uuid,
          p_content_revision bigint,
          p_decision text,
          p_reason text,
          p_user_visible_reason text,
          p_duplicate_of_report_id uuid,
          p_location_reviewed boolean,
          p_photo_reviewed boolean,
          p_privacy_reviewed boolean,
          p_evidence_grant_id uuid,
          p_admin_id text,
          p_session_id uuid,
          p_device_id text,
          p_correlation_id uuid,
          p_decided_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS TABLE (
          result_status text,
          decision_id uuid,
          decision_revision bigint
        ) AS $function$
        DECLARE
          next_revision bigint;
          evidence_audit_count integer;
          updated_count integer;
        BEGIN
          IF p_decision_id IS NULL OR p_report_id IS NULL
            OR p_content_revision IS NULL OR p_content_revision < 0
            OR p_decision NOT IN ('APPROVED', 'REJECTED', 'DUPLICATE')
            OR p_reason IS NULL OR pg_catalog.length(p_reason) NOT BETWEEN 1 AND 500
            OR p_location_reviewed IS NULL OR p_photo_reviewed IS NULL
            OR p_privacy_reviewed IS NULL OR p_admin_id IS NULL
            OR p_session_id IS NULL OR p_device_id IS NULL
            OR p_correlation_id IS NULL OR p_decided_at IS NULL
            OR (
              p_decision = 'APPROVED'
              AND (
                p_user_visible_reason IS NOT NULL
                OR p_duplicate_of_report_id IS NOT NULL
                OR p_evidence_grant_id IS NULL
                OR NOT p_location_reviewed OR NOT p_photo_reviewed
                OR NOT p_privacy_reviewed
              )
            )
            OR (
              p_decision IN ('REJECTED', 'DUPLICATE')
              AND (
                p_evidence_grant_id IS NOT NULL
                OR p_user_visible_reason IS NULL
                OR pg_catalog.length(p_user_visible_reason) NOT BETWEEN 1 AND 500
              )
            )
            OR (
              p_decision = 'DUPLICATE'
              AND (
                p_duplicate_of_report_id IS NULL
                OR p_duplicate_of_report_id = p_report_id
              )
            )
            OR (p_decision <> 'DUPLICATE' AND p_duplicate_of_report_id IS NOT NULL)
          THEN
            RAISE EXCEPTION 'report review decision input is invalid'
              USING ERRCODE = '22023';
          END IF;
          IF public.walksafe_lock_admin_original_access_session(
               p_admin_id, p_session_id, p_device_id, p_decided_at,
               p_runtime_totp_secret, p_credential_issuer_key
             ) IS DISTINCT FROM true THEN
            RETURN QUERY SELECT 'AUTH_INVALID'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          PERFORM report.id
            FROM public.reports AS report
           WHERE report.id = p_report_id
             AND report.content_revision = p_content_revision
           FOR UPDATE;
          IF NOT FOUND THEN
            RETURN QUERY SELECT 'CONTENT_CONFLICT'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          IF p_duplicate_of_report_id IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM public.reports AS duplicate_report
             WHERE duplicate_report.id = p_duplicate_of_report_id
          ) THEN
            RETURN QUERY SELECT 'DUPLICATE_NOT_FOUND'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          SELECT COALESCE(pg_catalog.max(decision.revision), 0) + 1
            INTO next_revision
            FROM public.report_review_decisions AS decision
           WHERE decision.report_id = p_report_id;

          IF p_decision = 'APPROVED' THEN
            PERFORM evidence_grant.id
              FROM public.report_original_access_grants AS evidence_grant
             WHERE evidence_grant.id = p_evidence_grant_id
               AND evidence_grant.report_id = p_report_id
               AND evidence_grant.admin_id = p_admin_id
               AND evidence_grant.session_id = p_session_id
               AND evidence_grant.device_id = p_device_id
               AND evidence_grant.purpose = 'report_review'
               AND evidence_grant.content_revision = p_content_revision
               AND evidence_grant.location_disclosed_at IS NOT NULL
               AND evidence_grant.access_granted_at IS NOT NULL
               AND evidence_grant.consumed_at IS NOT NULL
               AND evidence_grant.location_disclosed_at <= p_decided_at
               AND evidence_grant.access_granted_at <= p_decided_at
               AND evidence_grant.expires_at > p_decided_at
               AND evidence_grant.review_decision_id IS NULL
               AND evidence_grant.review_bound_at IS NULL
             FOR UPDATE;
            IF NOT FOUND THEN
              RETURN QUERY SELECT 'EVIDENCE_INVALID'::text, NULL::uuid, NULL::bigint;
              RETURN;
            END IF;
            SELECT pg_catalog.count(DISTINCT audit.action)::integer
              INTO evidence_audit_count
              FROM public.report_original_access_audits AS audit
             WHERE audit.grant_id = p_evidence_grant_id
               AND audit.report_id = p_report_id
               AND audit.admin_id = p_admin_id
               AND audit.session_id = p_session_id
               AND audit.device_id = p_device_id
               AND audit.purpose = 'report_review'
               AND audit.action IN ('LOCATION_DISCLOSED', 'ACCESS_GRANTED')
               AND audit.outcome = 'SUCCESS'
               AND audit.created_at <= p_decided_at;
            IF evidence_audit_count <> 2 THEN
              RETURN QUERY SELECT 'AUDIT_REQUIRED'::text, NULL::uuid, NULL::bigint;
              RETURN;
            END IF;
          END IF;

          INSERT INTO public.report_review_decisions (
            id, report_id, revision, content_revision, decision, reason,
            user_visible_reason, duplicate_of_report_id, evidence_grant_id,
            location_reviewed, photo_reviewed, privacy_reviewed, admin_id,
            session_id, device_id, correlation_id, decided_at
          ) VALUES (
            p_decision_id, p_report_id, next_revision, p_content_revision,
            p_decision, p_reason, p_user_visible_reason,
            p_duplicate_of_report_id, p_evidence_grant_id,
            p_location_reviewed, p_photo_reviewed, p_privacy_reviewed,
            p_admin_id, p_session_id, p_device_id, p_correlation_id, p_decided_at
          );
          IF p_decision = 'APPROVED' THEN
            UPDATE public.report_original_access_grants AS evidence_grant
               SET review_decision_id = p_decision_id,
                   review_bound_at = p_decided_at
             WHERE evidence_grant.id = p_evidence_grant_id
               AND evidence_grant.review_decision_id IS NULL
               AND evidence_grant.review_bound_at IS NULL;
            GET DIAGNOSTICS updated_count = ROW_COUNT;
            IF updated_count <> 1 THEN
              RAISE EXCEPTION 'report review evidence binding changed concurrently'
                USING ERRCODE = '40001';
            END IF;
          END IF;
          RETURN QUERY SELECT 'CREATED'::text, p_decision_id, next_revision;
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )


def _harden_function_ownership_and_acl() -> None:
    for signature in _FUNCTION_SIGNATURES:
        op.execute(f"ALTER FUNCTION {signature} OWNER TO {_OWNER_ROLE}")
        op.execute(
            f"REVOKE ALL ON FUNCTION {signature} FROM PUBLIC, {_RUNTIME_ROLE}"
        )
        op.execute(f"GRANT EXECUTE ON FUNCTION {signature} TO {_RUNTIME_ROLE}")
    op.execute(f"REVOKE CREATE ON SCHEMA public FROM {_OWNER_ROLE}")


def _revoke_runtime_direct_dml() -> None:
    op.execute(
        "REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE "
        "public.report_original_access_grants, "
        "public.report_original_access_audits, public.report_review_decisions "
        f"FROM PUBLIC, {_RUNTIME_ROLE}"
    )
    op.execute(
        f"""
        DO $columns$
        DECLARE target record;
        BEGIN
          FOR target IN
            SELECT relation.relname, attribute.attname
              FROM pg_catalog.pg_class AS relation
              JOIN pg_catalog.pg_namespace AS namespace
                ON namespace.oid = relation.relnamespace
              JOIN pg_catalog.pg_attribute AS attribute
                ON attribute.attrelid = relation.oid
               AND attribute.attnum > 0
               AND NOT attribute.attisdropped
             WHERE namespace.nspname = 'public'
               AND relation.relname IN (
                 'report_original_access_grants',
                 'report_original_access_audits',
                 'report_review_decisions'
               )
          LOOP
            EXECUTE pg_catalog.format(
              'REVOKE INSERT (%I), UPDATE (%I) ON TABLE public.%I '
              'FROM PUBLIC, {_RUNTIME_ROLE}',
              target.attname, target.attname, target.relname
            );
          END LOOP;
        END
        $columns$
        """
    )
    op.execute(
        "GRANT SELECT ON TABLE public.report_original_access_grants, "
        "public.report_original_access_audits, public.report_review_decisions "
        f"TO {_RUNTIME_ROLE}"
    )


def _assert_installed_contract() -> None:
    op.execute(
        f"""
        DO $assertion$
        DECLARE installed_function_count integer;
        DECLARE invalid_function_count integer;
        BEGIN
          IF pg_catalog.pg_has_role(
               '{_RUNTIME_ROLE}', '{_OWNER_ROLE}', 'MEMBER'
             )
            OR EXISTS (
              SELECT 1
                FROM pg_catalog.pg_auth_members AS membership
               WHERE membership.member = '{_OWNER_ROLE}'::regrole::oid
            )
            OR pg_catalog.has_schema_privilege(
                 '{_OWNER_ROLE}', 'public', 'CREATE'
               )
            OR EXISTS (
              SELECT 1
                FROM pg_catalog.pg_class AS relation
               WHERE relation.oid IN (
                 'public.reports'::regclass,
                 'public.report_image_objects'::regclass,
                 'public.report_original_access_grants'::regclass,
                 'public.report_original_access_audits'::regclass,
                 'public.report_review_decisions'::regclass
               )
                 AND (
                   pg_catalog.has_table_privilege(
                     '{_OWNER_ROLE}', relation.oid, 'DELETE'
                   )
                   OR pg_catalog.has_table_privilege(
                     '{_OWNER_ROLE}', relation.oid, 'TRUNCATE'
                   )
                 )
            )
            OR EXISTS (
              SELECT 1
                FROM pg_catalog.pg_class AS relation
                JOIN pg_catalog.pg_attribute AS attribute
                  ON attribute.attrelid = relation.oid
                 AND attribute.attnum > 0
                 AND NOT attribute.attisdropped
               WHERE relation.oid IN (
                 'public.reports'::regclass,
                 'public.report_image_objects'::regclass,
                 'public.report_original_access_grants'::regclass,
                 'public.report_original_access_audits'::regclass,
                 'public.report_review_decisions'::regclass
               )
                 AND pg_catalog.has_column_privilege(
                   '{_OWNER_ROLE}', relation.oid, attribute.attnum, 'UPDATE'
                 )
                 AND NOT (
                   (relation.oid = 'public.reports'::regclass
                    AND attribute.attname = 'updated_at')
                   OR (
                     relation.oid =
                       'public.report_original_access_grants'::regclass
                     AND attribute.attname IN (
                       'consumed_at', 'access_granted_at',
                       'review_decision_id', 'review_bound_at'
                     )
                   )
                 )
            )
            OR EXISTS (
              SELECT 1
                FROM pg_catalog.pg_class AS relation
                CROSS JOIN LATERAL pg_catalog.aclexplode(
                  coalesce(
                    relation.relacl,
                    pg_catalog.acldefault('r', relation.relowner)
                  )
                ) AS table_acl
               WHERE relation.oid IN (
                 'public.report_original_access_grants'::regclass,
                 'public.report_original_access_audits'::regclass,
                 'public.report_review_decisions'::regclass
               )
                 AND table_acl.grantee = 0
                 AND table_acl.privilege_type IN (
                   'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE'
                 )
            )
            OR EXISTS (
              SELECT 1
                FROM pg_catalog.pg_class AS relation
                JOIN pg_catalog.pg_attribute AS attribute
                  ON attribute.attrelid = relation.oid
                 AND attribute.attnum > 0
                 AND NOT attribute.attisdropped
                CROSS JOIN LATERAL pg_catalog.aclexplode(
                  attribute.attacl
                ) AS column_acl
               WHERE relation.oid IN (
                 'public.report_original_access_grants'::regclass,
                 'public.report_original_access_audits'::regclass,
                 'public.report_review_decisions'::regclass
               )
                 AND column_acl.grantee = 0
                 AND column_acl.privilege_type IN ('INSERT', 'UPDATE')
            )
            OR EXISTS (
              SELECT 1
                FROM pg_catalog.pg_class AS relation
               WHERE relation.oid IN (
                 'public.report_original_access_grants'::regclass,
                 'public.report_original_access_audits'::regclass,
                 'public.report_review_decisions'::regclass
               )
                 AND (
                   pg_catalog.has_table_privilege(
                     '{_RUNTIME_ROLE}', relation.oid, 'INSERT'
                   )
                   OR pg_catalog.has_table_privilege(
                     '{_RUNTIME_ROLE}', relation.oid, 'UPDATE'
                   )
                   OR pg_catalog.has_table_privilege(
                     '{_RUNTIME_ROLE}', relation.oid, 'DELETE'
                   )
                   OR pg_catalog.has_table_privilege(
                     '{_RUNTIME_ROLE}', relation.oid, 'TRUNCATE'
                   )
                   OR pg_catalog.has_any_column_privilege(
                     '{_RUNTIME_ROLE}', relation.oid, 'INSERT'
                   )
                   OR pg_catalog.has_any_column_privilege(
                     '{_RUNTIME_ROLE}', relation.oid, 'UPDATE'
                   )
                 )
            )
          THEN
            RAISE EXCEPTION 'original evidence runtime ACL is unsafe'
              USING ERRCODE = '42501';
          END IF;
          SELECT pg_catalog.count(*)::integer
            INTO installed_function_count
            FROM pg_catalog.pg_proc AS function
            JOIN pg_catalog.pg_namespace AS namespace
              ON namespace.oid = function.pronamespace
           WHERE namespace.nspname = 'public'
             AND function.proname IN (
               'walksafe_issue_report_original_evidence_grant',
               'walksafe_prepare_report_original_evidence_access',
               'walksafe_record_report_original_evidence_failure',
               'walksafe_complete_report_original_evidence_access',
               'walksafe_append_report_review_decision'
             );
          IF installed_function_count <> 5 THEN
            RAISE EXCEPTION 'original evidence function set is incomplete'
              USING ERRCODE = '42501';
          END IF;
          SELECT pg_catalog.count(*)::integer
            INTO invalid_function_count
            FROM pg_catalog.pg_proc AS function
            JOIN pg_catalog.pg_namespace AS namespace
              ON namespace.oid = function.pronamespace
            JOIN pg_catalog.pg_roles AS owner
              ON owner.oid = function.proowner
           WHERE namespace.nspname = 'public'
             AND function.proname IN (
               'walksafe_issue_report_original_evidence_grant',
               'walksafe_prepare_report_original_evidence_access',
               'walksafe_record_report_original_evidence_failure',
               'walksafe_complete_report_original_evidence_access',
               'walksafe_append_report_review_decision'
             )
             AND (
               owner.rolname <> '{_OWNER_ROLE}'
               OR function.prosecdef IS DISTINCT FROM true
               OR function.proconfig IS DISTINCT FROM
                    ARRAY['search_path=pg_catalog, pg_temp']::text[]
               OR EXISTS (
                    SELECT 1
                      FROM pg_catalog.aclexplode(
                        coalesce(
                          function.proacl,
                          pg_catalog.acldefault('f', function.proowner)
                        )
                      ) AS privilege
                     WHERE privilege.grantee = 0
                       AND privilege.privilege_type = 'EXECUTE'
                  )
               OR EXISTS (
                    SELECT 1
                      FROM pg_catalog.aclexplode(
                        coalesce(
                          function.proacl,
                          pg_catalog.acldefault('f', function.proowner)
                        )
                      ) AS privilege
                     WHERE privilege.privilege_type = 'EXECUTE'
                       AND privilege.grantee NOT IN (
                         function.proowner,
                         '{_RUNTIME_ROLE}'::regrole::oid
                       )
                  )
               OR NOT pg_catalog.has_function_privilege(
                    '{_RUNTIME_ROLE}', function.oid, 'EXECUTE'
                  )
             );
          IF invalid_function_count <> 0 THEN
            RAISE EXCEPTION 'original evidence function ACL is unsafe'
              USING ERRCODE = '42501';
          END IF;
        END
        $assertion$
        """
    )


def upgrade() -> None:
    _create_owner_role()
    _install_issue_function()
    _install_prepare_function()
    _install_failure_function()
    _install_complete_function()
    _install_review_function()
    _harden_function_ownership_and_acl()
    _revoke_runtime_direct_dml()
    _assert_installed_contract()


def downgrade() -> None:
    for signature in reversed(_FUNCTION_SIGNATURES):
        op.execute(f"DROP FUNCTION IF EXISTS {signature}")
    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE public.reports, "
        "public.report_image_objects, public.report_original_access_grants, "
        "public.report_original_access_audits, public.report_review_decisions "
        f"FROM {_OWNER_ROLE}"
    )
    op.execute(
        "REVOKE EXECUTE ON FUNCTION "
        "public.walksafe_lock_admin_original_access_session("
        "text, uuid, text, timestamptz, text, text) "
        f"FROM {_OWNER_ROLE}"
    )
    op.execute(f"REVOKE USAGE, CREATE ON SCHEMA public FROM {_OWNER_ROLE}")
    op.execute(
        "GRANT INSERT ON TABLE public.report_original_access_grants, "
        "public.report_original_access_audits, public.report_review_decisions "
        f"TO {_RUNTIME_ROLE}"
    )
    op.execute(
        "GRANT UPDATE ON TABLE public.report_original_access_grants "
        f"TO {_RUNTIME_ROLE}"
    )
