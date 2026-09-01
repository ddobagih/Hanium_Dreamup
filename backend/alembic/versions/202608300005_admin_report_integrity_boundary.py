"""bind report ingestion and administrator mutations to database invariants

Revision ID: 202608300005
Revises: 202608300004
Create Date: 2026-08-30
"""

from __future__ import annotations

from alembic import op


revision = "202608300005"
down_revision = "202608300004"
branch_labels = None
depends_on = None


_OWNER_ROLE = "walksafe_report_evidence_owner"
_RUNTIME_ROLE = "walksafe_backend_runtime"

_CLAIM_SIGNATURE = (
    "public.walksafe_claim_admin_report_mutation("
    "uuid, uuid, text, uuid, text, uuid, text, text, text, uuid, bytea, text, text, text)"
)
_ISSUE_V3_SIGNATURE = (
    "public.walksafe_issue_report_original_evidence_grant_v3("
    "uuid, uuid, uuid, uuid, text, uuid, text, text, text, text, bigint, integer, "
    "uuid, bytea, text, text, text)"
)
_PREPARE_V3_SIGNATURE = (
    "public.walksafe_prepare_report_original_evidence_access_v3("
    "uuid, text, text, uuid, text, text, text)"
)
_COMPLETE_V3_SIGNATURE = (
    "public.walksafe_complete_report_original_evidence_access_v3("
    "uuid, uuid, uuid, text, uuid, text, text, bigint, text, smallint, text, "
    "bigint, text, text, text, text)"
)
_REVIEW_V3_SIGNATURE = (
    "public.walksafe_append_report_review_decision_v3("
    "uuid, uuid, bigint, text, text, text, uuid, boolean, boolean, boolean, "
    "uuid, text, uuid, text, uuid, uuid, bytea, text, text)"
)
_PACKAGE_V3_SIGNATURE = (
    "public.walksafe_create_report_delivery_package_v3("
    "uuid, uuid, bigint, text, uuid, bigint, bigint, uuid, uuid, text, text, "
    "text, bigint, bigint, bigint, text, uuid, text, uuid, timestamptz, uuid, bytea, "
    "text, text, text)"
)
_DELIVERY_V3_SIGNATURE = (
    "public.walksafe_append_report_delivery_event_v3("
    "uuid, uuid, bigint, bigint, uuid, text, text, text, text, text, text, text, "
    "timestamptz, text, uuid, text, uuid, uuid, bytea, text, text)"
)
_REPORT_INGEST_TRIGGER_SIGNATURE = (
    "public.walksafe_enforce_runtime_report_ingest_integrity()"
)
_PYTHON_STRIP_SIGNATURE = "public.walksafe_python_strip(text)"
_PYTHON_SPLIT_JOIN_SIGNATURE = "public.walksafe_python_split_join(text)"

_OLD_RUNTIME_FUNCTIONS = (
    "public.walksafe_issue_report_original_evidence_grant("
    "uuid, uuid, uuid, uuid, text, uuid, text, text, text, text, bigint, "
    "timestamptz, timestamptz, text, text)",
    "public.walksafe_prepare_report_original_evidence_access("
    "uuid, text, text, uuid, text, timestamptz, text, text)",
    "public.walksafe_complete_report_original_evidence_access("
    "uuid, uuid, uuid, text, uuid, text, text, bigint, text, smallint, text, "
    "bigint, text, timestamptz, text, text)",
    "public.walksafe_append_report_review_decision("
    "uuid, uuid, bigint, text, text, text, uuid, boolean, boolean, boolean, "
    "uuid, text, uuid, text, uuid, timestamptz, text, text)",
)

_OWNER_ROLE_CONTRACT_VIOLATION_SQL = f"""
current_user IS DISTINCT FROM session_user
OR NOT EXISTS (
  SELECT 1 FROM pg_catalog.pg_roles AS owner_role
   WHERE owner_role.rolname = '{_OWNER_ROLE}'
     AND NOT owner_role.rolsuper
     AND NOT owner_role.rolcreaterole
     AND NOT owner_role.rolcreatedb
     AND NOT owner_role.rolcanlogin
     AND NOT owner_role.rolinherit
     AND NOT owner_role.rolreplication
     AND NOT owner_role.rolbypassrls
)
OR COALESCE((
  SELECT pg_catalog.count(*) = 1
     AND pg_catalog.bool_and(
       NOT membership.admin_option
       AND membership.inherit_option
       AND membership.set_option
     )
    FROM pg_catalog.pg_auth_members AS membership
   WHERE membership.roleid = '{_OWNER_ROLE}'::regrole::oid
     AND membership.member = current_user::regrole::oid
), false) IS DISTINCT FROM true
OR EXISTS (
   WITH RECURSIVE owner_inheritors(role_oid) AS (
     SELECT membership.member
       FROM pg_catalog.pg_auth_members AS membership
      WHERE membership.roleid = '{_OWNER_ROLE}'::regrole::oid
     UNION
     SELECT membership.member
       FROM pg_catalog.pg_auth_members AS membership
       JOIN owner_inheritors AS inherited
         ON membership.roleid = inherited.role_oid
   )
   SELECT 1
     FROM owner_inheritors
    WHERE role_oid <> current_user::regrole::oid
 )
OR EXISTS (
   SELECT 1
     FROM pg_catalog.pg_auth_members AS membership
    WHERE membership.member = '{_OWNER_ROLE}'::regrole::oid
 )
""".strip()


def _preflight_existing_database() -> None:
    op.execute(
        f"""
        DO $preflight$
        BEGIN
          LOCK TABLE public.report_review_decisions,
                     public.report_original_access_grants
            IN ACCESS EXCLUSIVE MODE;
          IF {_OWNER_ROLE_CONTRACT_VIOLATION_SQL}
          THEN
            RAISE EXCEPTION 'evidence owner has unexpected role membership'
              USING ERRCODE = '42501';
          END IF;
          IF EXISTS (
               SELECT 1
                 FROM public.report_review_decisions AS decision
                WHERE decision.decision = 'APPROVED'
                  AND decision.evidence_grant_id IS NULL
             )
          THEN
            RAISE EXCEPTION 'existing approval has no original evidence grant'
              USING ERRCODE = '23514';
          END IF;
          IF EXISTS (
               SELECT 1
                 FROM public.report_review_decisions AS decision
                 LEFT JOIN public.report_original_access_grants AS evidence_grant
                   ON evidence_grant.id = decision.evidence_grant_id
                WHERE decision.decision = 'APPROVED'
                  AND (
                    evidence_grant.id IS NULL
                    OR evidence_grant.report_id <> decision.report_id
                    OR evidence_grant.review_decision_id <> decision.id
                    OR evidence_grant.access_granted_at IS NULL
                    OR evidence_grant.location_disclosed_at IS NULL
                    OR evidence_grant.consumed_at IS NULL
                    OR evidence_grant.content_revision IS DISTINCT FROM
                       decision.content_revision
                  )
             )
          THEN
            RAISE EXCEPTION 'existing approval evidence binding is invalid'
              USING ERRCODE = '23514';
          END IF;
        END
        $preflight$
        """
    )
    op.execute(
        "ALTER TABLE public.report_review_decisions VALIDATE CONSTRAINT "
        "ck_report_review_decisions_evidence_grant"
    )


def _create_claim_ledger() -> None:
    op.execute(
        """
        CREATE TABLE public.admin_report_mutation_claims (
          challenge_id uuid PRIMARY KEY,
          reconfirmation_id uuid UNIQUE,
          report_id uuid NOT NULL,
          resource_type text NOT NULL,
          resource_id uuid NOT NULL,
          action text NOT NULL,
          admin_id text NOT NULL,
          session_id uuid NOT NULL,
          device_id text NOT NULL,
          correlation_id uuid NOT NULL,
          claimed_at timestamptz NOT NULL DEFAULT pg_catalog.clock_timestamp(),
          CONSTRAINT fk_admin_report_mutation_claim_challenge
            FOREIGN KEY (challenge_id)
            REFERENCES public.admin_device_proof_challenges(id)
            ON DELETE RESTRICT,
          CONSTRAINT fk_admin_report_mutation_claim_reconfirmation
            FOREIGN KEY (reconfirmation_id)
            REFERENCES public.admin_security_reconfirmations(id)
            ON DELETE RESTRICT,
          CONSTRAINT ck_admin_report_mutation_claim_resource_type
            CHECK (resource_type IN (
              'original_access_grant', 'review_decision',
              'delivery_package', 'delivery_event'
            )),
          CONSTRAINT ck_admin_report_mutation_claim_action CHECK (
            action IN (
              'report.original.grant', 'report.review.decide',
              'admin.report.delivery_package.create', 'report.delivery.create'
            )
          )
        )
        """
    )
    op.execute(
        "CREATE UNIQUE INDEX uq_admin_report_mutation_claim_resource "
        "ON public.admin_report_mutation_claims(resource_type, resource_id)"
    )
    op.execute(
        f"ALTER TABLE public.admin_report_mutation_claims OWNER TO {_OWNER_ROLE}"
    )
    op.execute(
        "REVOKE ALL ON TABLE public.admin_report_mutation_claims "
        f"FROM PUBLIC, {_RUNTIME_ROLE}"
    )
    op.execute(
        f"GRANT SELECT ON TABLE public.admin_report_mutation_claims TO {_RUNTIME_ROLE}"
    )


def _grant_owner_boundary_privileges() -> None:
    op.execute(
        "GRANT SELECT ON TABLE public.admin_device_proof_challenges, "
        "public.admin_security_reconfirmations, public.privacy_consent_events, "
        "public.account_deletion_tombstones, public.report_content_revisions, "
        "public.report_delivery_packages, "
        "public.report_institution_delivery_events, public.report_export_audits "
        f"TO {_OWNER_ROLE}"
    )
    op.execute(
        "GRANT INSERT ON TABLE public.admin_report_mutation_claims, "
        "public.report_delivery_packages, "
        f"public.report_institution_delivery_events TO {_OWNER_ROLE}"
    )


def _install_python_whitespace_functions() -> None:
    strip_whitespace = (
        r"U&'\0009\000A\000B\000C\000D\0020"
        r"\0085\00A0\1680\2000\2001\2002\2003\2004\2005\2006"
        r"\2007\2008\2009\200A\2028\2029\202F\205F\3000'"
    )
    split_whitespace = (
        r"U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020"
        r"\0085\00A0\1680\2000\2001\2002\2003\2004\2005\2006"
        r"\2007\2008\2009\200A\2028\2029\202F\205F\3000'"
    )
    op.execute(
        f"""
        CREATE FUNCTION public.walksafe_python_strip(p_value text)
        RETURNS text AS $function$
          SELECT pg_catalog.btrim(p_value, {strip_whitespace})
        $function$ LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        f"""
        CREATE FUNCTION public.walksafe_python_split_join(p_value text)
        RETURNS text AS $function$
          SELECT pg_catalog.btrim(
            pg_catalog.regexp_replace(
              pg_catalog.translate(
                p_value,
                {split_whitespace},
                pg_catalog.repeat(
                  ' ', pg_catalog.char_length({split_whitespace})
                )
              ),
              ' +', ' ', 'g'
            ),
            ' '
          )
        $function$ LANGUAGE sql IMMUTABLE STRICT PARALLEL SAFE
        SET search_path = pg_catalog, pg_temp
        """
    )


def _install_claim_function() -> None:
    op.execute(
        """
        CREATE FUNCTION public.walksafe_claim_admin_report_mutation(
          p_challenge_id uuid,
          p_report_id uuid,
          p_admin_id text,
          p_session_id uuid,
          p_device_id text,
          p_correlation_id uuid,
          p_action text,
          p_resource_type text,
          p_method text,
          p_resource_id uuid,
          p_request_body bytea,
          p_reconfirmation_nonce_sha256 text,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS uuid AS $function$
        DECLARE
          v_now timestamptz := pg_catalog.clock_timestamp();
          v_path text;
          v_resource_type text;
          v_reconfirmation_id uuid;
          v_challenge public.admin_device_proof_challenges%ROWTYPE;
          v_existing public.admin_report_mutation_claims%ROWTYPE;
        BEGIN
          IF p_challenge_id IS NULL OR p_report_id IS NULL
            OR p_admin_id IS NULL OR p_session_id IS NULL
            OR p_device_id IS NULL OR p_correlation_id IS NULL
            OR p_resource_id IS NULL OR p_method <> 'POST'
            OR p_request_body IS NULL
            OR pg_catalog.octet_length(p_request_body) NOT BETWEEN 2 AND 65536
          THEN
            RAISE EXCEPTION 'administrator mutation claim input is invalid'
              USING ERRCODE = '22023';
          END IF;
          v_path := CASE p_action
            WHEN 'report.original.grant' THEN
              '/reports/' || p_report_id::text || '/original-access-grants'
            WHEN 'report.review.decide' THEN
              '/reports/' || p_report_id::text || '/review-decisions'
            WHEN 'admin.report.delivery_package.create' THEN
              '/admin/reports/' || p_report_id::text || '/delivery-packages'
            WHEN 'report.delivery.create' THEN
              '/reports/' || p_report_id::text || '/deliveries'
            ELSE NULL
          END;
          v_resource_type := CASE p_action
            WHEN 'report.original.grant' THEN 'original_access_grant'
            WHEN 'report.review.decide' THEN 'review_decision'
            WHEN 'admin.report.delivery_package.create' THEN 'delivery_package'
            WHEN 'report.delivery.create' THEN 'delivery_event'
            ELSE NULL
          END;
          IF v_path IS NULL
            OR p_resource_type IS DISTINCT FROM v_resource_type
          THEN
            RAISE EXCEPTION 'administrator mutation action is invalid'
              USING ERRCODE = '22023';
          END IF;
          IF public.walksafe_lock_admin_original_access_session(
               p_admin_id, p_session_id, p_device_id, v_now,
               p_runtime_totp_secret, p_credential_issuer_key
             ) IS DISTINCT FROM true
          THEN
            RAISE EXCEPTION 'administrator mutation session is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT challenge.* INTO v_challenge
            FROM public.admin_device_proof_challenges AS challenge
           WHERE challenge.id = p_challenge_id;
          IF NOT FOUND
            OR v_challenge.admin_id IS DISTINCT FROM p_admin_id
            OR v_challenge.session_id IS DISTINCT FROM p_session_id
            OR v_challenge.device_id IS DISTINCT FROM p_device_id
            OR v_challenge.correlation_id IS DISTINCT FROM p_correlation_id
            OR v_challenge.action IS DISTINCT FROM p_action
            OR v_challenge.purpose IS DISTINCT FROM 'ACTION'
            OR v_challenge.read_purpose IS NOT NULL
            OR v_challenge.method IS DISTINCT FROM p_method
            OR v_challenge.path IS DISTINCT FROM v_path
            OR v_challenge.query_sha256 IS DISTINCT FROM
               'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
            OR v_challenge.body_sha256 IS DISTINCT FROM pg_catalog.encode(
                 pg_catalog.sha256(p_request_body), 'hex'
               )
            OR v_challenge.consumed_at IS NULL
            OR v_challenge.consumed_at > v_now
            OR v_challenge.expires_at <= v_now
          THEN
            RAISE EXCEPTION 'administrator device proof binding is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_action IN (
               'report.original.grant',
               'admin.report.delivery_package.create'
             )
          THEN
            IF p_reconfirmation_nonce_sha256 IS NULL
              OR p_reconfirmation_nonce_sha256 !~ '^[0-9a-f]{64}$'
            THEN
              RAISE EXCEPTION 'administrator reconfirmation is required'
                USING ERRCODE = '42501';
            END IF;
            SELECT reconfirmation.id INTO v_reconfirmation_id
              FROM public.admin_security_reconfirmations AS reconfirmation
             WHERE reconfirmation.nonce_sha256 = p_reconfirmation_nonce_sha256
               AND reconfirmation.admin_id = p_admin_id
               AND reconfirmation.session_id = p_session_id
               AND reconfirmation.device_id = p_device_id
               AND reconfirmation.action = p_action
               AND reconfirmation.method = p_method
               AND reconfirmation.path = v_path
               AND reconfirmation.consumed_at IS NOT NULL
               AND reconfirmation.consumed_at <= v_now
               AND reconfirmation.expires_at > v_now;
            IF v_reconfirmation_id IS NULL THEN
              RAISE EXCEPTION 'administrator reconfirmation binding is invalid'
                USING ERRCODE = '42501';
            END IF;
          ELSIF p_reconfirmation_nonce_sha256 IS NOT NULL THEN
            RAISE EXCEPTION 'administrator reconfirmation is not allowed'
              USING ERRCODE = '22023';
          END IF;
          SELECT claim.* INTO v_existing
            FROM public.admin_report_mutation_claims AS claim
           WHERE claim.challenge_id = p_challenge_id
           FOR UPDATE;
          IF FOUND THEN
            IF v_existing.reconfirmation_id IS NOT DISTINCT FROM v_reconfirmation_id
              AND v_existing.report_id = p_report_id
              AND v_existing.resource_type = p_resource_type
              AND v_existing.resource_id = p_resource_id
              AND v_existing.action = p_action
              AND v_existing.admin_id = p_admin_id
              AND v_existing.session_id = p_session_id
              AND v_existing.device_id = p_device_id
              AND v_existing.correlation_id = p_correlation_id
            THEN
              RETURN v_reconfirmation_id;
            END IF;
            RAISE EXCEPTION 'administrator mutation claim replay is invalid'
              USING ERRCODE = '42501';
          END IF;
          INSERT INTO public.admin_report_mutation_claims (
            challenge_id, reconfirmation_id, report_id, resource_type,
            resource_id, action, admin_id, session_id, device_id,
            correlation_id, claimed_at
          ) VALUES (
            p_challenge_id, v_reconfirmation_id, p_report_id, p_resource_type,
            p_resource_id, p_action, p_admin_id, p_session_id, p_device_id,
            p_correlation_id, v_now
          );
          RETURN v_reconfirmation_id;
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )


def _install_original_evidence_v3_functions() -> None:
    op.execute(
        """
        CREATE FUNCTION public.walksafe_issue_report_original_evidence_grant_v3(
          p_grant_id uuid, p_grant_audit_id uuid, p_location_audit_id uuid,
          p_report_id uuid, p_admin_id text, p_session_id uuid,
          p_device_id text, p_purpose text, p_reason text,
          p_token_sha256 text, p_expected_content_revision bigint,
          p_ttl_seconds integer, p_proof_challenge_id uuid,
          p_request_body bytea,
          p_reconfirmation_nonce_sha256 text, p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS TABLE (
          grant_id uuid, content_revision bigint, expires_at timestamptz,
          latitude double precision, longitude double precision,
          accuracy_m double precision, resource_path text, content_type text,
          image_sha256 text, image_byte_count bigint
        ) AS $function$
        DECLARE
          v_now timestamptz := pg_catalog.clock_timestamp();
          v_body jsonb := pg_catalog.convert_from(p_request_body, 'UTF8')::jsonb;
          v_issued record;
          v_existing_grant public.report_original_access_grants%ROWTYPE;
          v_locked_content_revision bigint;
        BEGIN
          IF pg_catalog.jsonb_typeof(v_body) <> 'object'
            OR NOT (v_body ?& ARRAY[
              'purpose', 'reason', 'expected_content_revision'
            ])
            OR EXISTS (
              SELECT 1 FROM pg_catalog.jsonb_object_keys(v_body) AS key
               WHERE key NOT IN (
                 'purpose', 'reason', 'expected_content_revision'
               )
            )
            OR pg_catalog.jsonb_typeof(v_body -> 'purpose') <> 'string'
            OR pg_catalog.jsonb_typeof(v_body -> 'reason') <> 'string'
            OR pg_catalog.jsonb_typeof(
                 v_body -> 'expected_content_revision'
               ) <> 'number'
            OR v_body ->> 'purpose' IS DISTINCT FROM p_purpose
            OR public.walksafe_python_split_join(v_body ->> 'reason')
                 IS DISTINCT FROM p_reason
            OR (v_body -> 'expected_content_revision')::text
                 !~ '^(0|[1-9][0-9]*)$'
            OR (v_body ->> 'expected_content_revision')::bigint
                 IS DISTINCT FROM p_expected_content_revision
            OR p_ttl_seconds NOT BETWEEN 1 AND 300
          THEN
            RAISE EXCEPTION 'original evidence grant proof body binding is invalid'
              USING ERRCODE = '22023';
          END IF;
          SELECT report.content_revision INTO v_locked_content_revision
            FROM public.reports AS report
           WHERE report.id = p_report_id
           FOR UPDATE;
          IF NOT FOUND
            OR v_locked_content_revision IS DISTINCT FROM
               p_expected_content_revision
          THEN
            RAISE EXCEPTION 'original evidence content revision changed'
              USING ERRCODE = '40001';
          END IF;
          SELECT evidence_grant.* INTO v_existing_grant
            FROM public.report_original_access_grants AS evidence_grant
           WHERE evidence_grant.id = p_grant_id
           FOR UPDATE;
          IF FOUND THEN
            IF v_existing_grant.report_id IS DISTINCT FROM p_report_id
              OR v_existing_grant.admin_id IS DISTINCT FROM p_admin_id
              OR v_existing_grant.session_id IS DISTINCT FROM p_session_id
              OR v_existing_grant.device_id IS DISTINCT FROM p_device_id
              OR v_existing_grant.purpose::text IS DISTINCT FROM p_purpose
              OR v_existing_grant.reason IS DISTINCT FROM p_reason
              OR v_existing_grant.token_sha256 IS DISTINCT FROM p_token_sha256
              OR v_existing_grant.content_revision
                   IS DISTINCT FROM p_expected_content_revision
              OR v_existing_grant.expires_at - v_existing_grant.issued_at
                   IS DISTINCT FROM pg_catalog.make_interval(secs => p_ttl_seconds)
              OR v_existing_grant.expires_at <= v_now
              OR NOT EXISTS (
                SELECT 1 FROM public.report_original_access_audits AS audit
                 WHERE audit.id = p_grant_audit_id
                   AND audit.grant_id = p_grant_id
                   AND audit.action = 'GRANT_ISSUED'
                   AND audit.outcome = 'SUCCESS'
              )
              OR NOT EXISTS (
                SELECT 1 FROM public.report_original_access_audits AS audit
                 WHERE audit.id = p_location_audit_id
                   AND audit.grant_id = p_grant_id
                   AND audit.action = 'LOCATION_DISCLOSED'
                   AND audit.outcome = 'SUCCESS'
              )
            THEN
              RAISE EXCEPTION 'original evidence grant replay is invalid'
                USING ERRCODE = '42501';
            END IF;
            SELECT evidence_grant.id, evidence_grant.content_revision,
                   evidence_grant.expires_at, report.latitude,
                   report.longitude, report.accuracy_m, report.image_path,
                   image_object.content_type, image_object.plaintext_sha256,
                   image_object.plaintext_size
              INTO STRICT v_issued
              FROM public.report_original_access_grants AS evidence_grant
              JOIN public.reports AS report ON report.id = evidence_grant.report_id
              JOIN public.report_image_objects AS image_object
                ON image_object.report_id = report.id
             WHERE evidence_grant.id = p_grant_id
               AND report.content_revision = evidence_grant.content_revision;
            PERFORM public.walksafe_claim_admin_report_mutation(
              p_proof_challenge_id, p_report_id, p_admin_id, p_session_id,
              p_device_id,
              (SELECT challenge.correlation_id
                 FROM public.admin_device_proof_challenges AS challenge
                WHERE challenge.id = p_proof_challenge_id),
              'report.original.grant', 'original_access_grant', 'POST',
              p_grant_id, p_request_body, p_reconfirmation_nonce_sha256,
              p_runtime_totp_secret, p_credential_issuer_key
            );
            RETURN QUERY SELECT
              v_issued.id, v_issued.content_revision, v_issued.expires_at,
              v_issued.latitude, v_issued.longitude, v_issued.accuracy_m,
              v_issued.image_path, v_issued.content_type,
              v_issued.plaintext_sha256, v_issued.plaintext_size;
            RETURN;
          END IF;
          SELECT issued.* INTO STRICT v_issued
            FROM public.walksafe_issue_report_original_evidence_grant(
              p_grant_id, p_grant_audit_id, p_location_audit_id, p_report_id,
              p_admin_id, p_session_id, p_device_id, p_purpose, p_reason,
              p_token_sha256, p_expected_content_revision, v_now,
              v_now + pg_catalog.make_interval(secs => p_ttl_seconds),
              p_runtime_totp_secret, p_credential_issuer_key
            ) AS issued;
          PERFORM public.walksafe_claim_admin_report_mutation(
            p_proof_challenge_id, p_report_id, p_admin_id, p_session_id,
            p_device_id,
            (SELECT challenge.correlation_id
               FROM public.admin_device_proof_challenges AS challenge
              WHERE challenge.id = p_proof_challenge_id),
            'report.original.grant', 'original_access_grant', 'POST',
            p_grant_id, p_request_body, p_reconfirmation_nonce_sha256,
            p_runtime_totp_secret, p_credential_issuer_key
          );
          RETURN QUERY SELECT
            v_issued.grant_id, v_issued.content_revision, v_issued.expires_at,
            v_issued.latitude, v_issued.longitude, v_issued.accuracy_m,
            v_issued.resource_path, v_issued.content_type,
            v_issued.image_sha256, v_issued.image_byte_count;
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION public.walksafe_prepare_report_original_evidence_access_v3(
          p_report_id uuid, p_raw_access_token text, p_admin_id text,
          p_session_id uuid, p_device_id text, p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS TABLE (
          access_status text, reason_code text, consume_grant boolean,
          grant_id uuid, purpose text, content_revision bigint,
          expires_at timestamptz, storage_name text, envelope_size bigint,
          envelope_sha256 text, key_id text, envelope_version smallint,
          content_type text, plaintext_sha256 text, plaintext_size bigint
        ) AS $function$
        DECLARE
          v_now timestamptz := pg_catalog.clock_timestamp();
          v_token_sha256 text;
        BEGIN
          IF p_raw_access_token IS NULL
            OR p_raw_access_token !~ '^[A-Za-z0-9_-]{43}$'
          THEN
            RAISE EXCEPTION 'original evidence access token is invalid'
              USING ERRCODE = '22023';
          END IF;
          v_token_sha256 := pg_catalog.encode(
            pg_catalog.sha256(pg_catalog.convert_to(p_raw_access_token, 'UTF8')),
            'hex'
          );
          RETURN QUERY SELECT prepared.*
            FROM public.walksafe_prepare_report_original_evidence_access(
              p_report_id, v_token_sha256, p_admin_id, p_session_id,
              p_device_id, v_now, p_runtime_totp_secret,
              p_credential_issuer_key
            ) AS prepared;
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION public.walksafe_complete_report_original_evidence_access_v3(
          p_audit_id uuid, p_grant_id uuid, p_report_id uuid, p_admin_id text,
          p_session_id uuid, p_device_id text, p_purpose text,
          p_content_revision bigint, p_key_id text,
          p_envelope_version smallint, p_plaintext_sha256 text,
          p_plaintext_size bigint, p_content_type text,
          p_raw_access_token text, p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS boolean AS $function$
        DECLARE
          v_now timestamptz := pg_catalog.clock_timestamp();
          v_prepared record;
        BEGIN
          SELECT * INTO v_prepared
            FROM public.walksafe_prepare_report_original_evidence_access_v3(
              p_report_id, p_raw_access_token, p_admin_id, p_session_id,
              p_device_id, p_runtime_totp_secret, p_credential_issuer_key
            );
          IF v_prepared.access_status IS DISTINCT FROM 'READY'
            OR v_prepared.grant_id IS DISTINCT FROM p_grant_id
            OR v_prepared.purpose IS DISTINCT FROM p_purpose
            OR v_prepared.content_revision IS DISTINCT FROM p_content_revision
            OR v_prepared.key_id IS DISTINCT FROM p_key_id
            OR v_prepared.envelope_version IS DISTINCT FROM p_envelope_version
            OR v_prepared.plaintext_sha256 IS DISTINCT FROM p_plaintext_sha256
            OR v_prepared.plaintext_size IS DISTINCT FROM p_plaintext_size
            OR v_prepared.content_type IS DISTINCT FROM p_content_type
          THEN
            RETURN false;
          END IF;
          RETURN public.walksafe_complete_report_original_evidence_access(
            p_audit_id, p_grant_id, p_report_id, p_admin_id, p_session_id,
            p_device_id, p_purpose, p_content_revision, p_key_id,
            p_envelope_version, p_plaintext_sha256, p_plaintext_size,
            p_content_type, v_now, p_runtime_totp_secret,
            p_credential_issuer_key
          );
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION public.walksafe_append_report_review_decision_v3(
          p_decision_id uuid, p_report_id uuid, p_content_revision bigint,
          p_decision text, p_reason text, p_user_visible_reason text,
          p_duplicate_of_report_id uuid, p_location_reviewed boolean,
          p_photo_reviewed boolean, p_privacy_reviewed boolean,
          p_evidence_grant_id uuid, p_admin_id text, p_session_id uuid,
          p_device_id text, p_correlation_id uuid, p_proof_challenge_id uuid,
          p_request_body bytea,
          p_runtime_totp_secret text, p_credential_issuer_key text
        ) RETURNS TABLE (
          result_status text, decision_id uuid, decision_revision bigint
        ) AS $function$
        DECLARE
          v_now timestamptz := pg_catalog.clock_timestamp();
          v_body jsonb := pg_catalog.convert_from(p_request_body, 'UTF8')::jsonb;
          v_stored record;
          v_existing_decision public.report_review_decisions%ROWTYPE;
        BEGIN
          IF pg_catalog.jsonb_typeof(v_body) <> 'object'
            OR NOT (v_body ?& ARRAY[
              'decision', 'reason', 'duplicate_of_report_id',
              'location_reviewed', 'photo_reviewed', 'privacy_reviewed'
            ])
            OR EXISTS (
              SELECT 1 FROM pg_catalog.jsonb_object_keys(v_body) AS key
               WHERE key NOT IN (
                 'decision', 'reason', 'user_visible_reason',
                 'duplicate_of_report_id', 'location_reviewed',
                 'photo_reviewed', 'privacy_reviewed', 'content_revision',
                 'evidence_grant_id'
               )
            )
            OR pg_catalog.jsonb_typeof(v_body -> 'decision') <> 'string'
            OR pg_catalog.jsonb_typeof(v_body -> 'reason') <> 'string'
            OR pg_catalog.jsonb_typeof(v_body -> 'location_reviewed') <> 'boolean'
            OR pg_catalog.jsonb_typeof(v_body -> 'photo_reviewed') <> 'boolean'
            OR pg_catalog.jsonb_typeof(v_body -> 'privacy_reviewed') <> 'boolean'
            OR v_body ->> 'decision' IS DISTINCT FROM p_decision
            OR public.walksafe_python_strip(v_body ->> 'reason')
                 IS DISTINCT FROM p_reason
            OR public.walksafe_python_strip(v_body ->> 'user_visible_reason')
                 IS DISTINCT FROM p_user_visible_reason
            OR v_body ->> 'duplicate_of_report_id'
                 IS DISTINCT FROM p_duplicate_of_report_id::text
            OR v_body -> 'location_reviewed'
                 IS DISTINCT FROM pg_catalog.to_jsonb(p_location_reviewed)
            OR v_body -> 'photo_reviewed'
                 IS DISTINCT FROM pg_catalog.to_jsonb(p_photo_reviewed)
            OR v_body -> 'privacy_reviewed'
                 IS DISTINCT FROM pg_catalog.to_jsonb(p_privacy_reviewed)
            OR (
              v_body ? 'content_revision'
              AND (
                pg_catalog.jsonb_typeof(v_body -> 'content_revision') <> 'number'
                OR (v_body -> 'content_revision')::text
                     !~ '^(0|[1-9][0-9]*)$'
              )
            )
            OR COALESCE((v_body ->> 'content_revision')::bigint, 0)
                 IS DISTINCT FROM p_content_revision
            OR v_body ->> 'evidence_grant_id'
                 IS DISTINCT FROM p_evidence_grant_id::text
          THEN
            RAISE EXCEPTION 'report review proof body binding is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT decision.* INTO v_existing_decision
            FROM public.report_review_decisions AS decision
           WHERE decision.id = p_decision_id;
          IF FOUND THEN
            IF v_existing_decision.report_id IS DISTINCT FROM p_report_id
              OR v_existing_decision.content_revision
                   IS DISTINCT FROM p_content_revision
              OR v_existing_decision.decision::text IS DISTINCT FROM p_decision
              OR v_existing_decision.reason IS DISTINCT FROM p_reason
              OR v_existing_decision.user_visible_reason
                   IS DISTINCT FROM p_user_visible_reason
              OR v_existing_decision.duplicate_of_report_id
                   IS DISTINCT FROM p_duplicate_of_report_id
              OR v_existing_decision.location_reviewed
                   IS DISTINCT FROM p_location_reviewed
              OR v_existing_decision.photo_reviewed
                   IS DISTINCT FROM p_photo_reviewed
              OR v_existing_decision.privacy_reviewed
                   IS DISTINCT FROM p_privacy_reviewed
              OR v_existing_decision.evidence_grant_id
                   IS DISTINCT FROM p_evidence_grant_id
              OR v_existing_decision.admin_id IS DISTINCT FROM p_admin_id
              OR v_existing_decision.session_id IS DISTINCT FROM p_session_id
              OR v_existing_decision.device_id IS DISTINCT FROM p_device_id
              OR v_existing_decision.correlation_id
                   IS DISTINCT FROM p_correlation_id
            THEN
              RAISE EXCEPTION 'report review replay is invalid'
                USING ERRCODE = '42501';
            END IF;
            PERFORM public.walksafe_claim_admin_report_mutation(
              p_proof_challenge_id, p_report_id, p_admin_id, p_session_id,
              p_device_id, p_correlation_id, 'report.review.decide',
              'review_decision', 'POST', p_decision_id, p_request_body, NULL,
              p_runtime_totp_secret, p_credential_issuer_key
            );
            RETURN QUERY SELECT 'EXISTING'::text, v_existing_decision.id,
                                v_existing_decision.revision;
            RETURN;
          END IF;
          SELECT stored.* INTO STRICT v_stored
            FROM public.walksafe_append_report_review_decision(
              p_decision_id, p_report_id, p_content_revision, p_decision,
              p_reason, p_user_visible_reason, p_duplicate_of_report_id,
              p_location_reviewed, p_photo_reviewed, p_privacy_reviewed,
              p_evidence_grant_id, p_admin_id, p_session_id, p_device_id,
              p_correlation_id, v_now, p_runtime_totp_secret,
              p_credential_issuer_key
            ) AS stored;
          IF v_stored.result_status = 'CREATED' THEN
            PERFORM public.walksafe_claim_admin_report_mutation(
              p_proof_challenge_id, p_report_id, p_admin_id, p_session_id,
              p_device_id, p_correlation_id, 'report.review.decide',
              'review_decision', 'POST', p_decision_id, p_request_body, NULL,
              p_runtime_totp_secret, p_credential_issuer_key
            );
          END IF;
          RETURN QUERY SELECT v_stored.result_status, v_stored.decision_id,
                              v_stored.decision_revision;
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )


def _install_delivery_v3_functions() -> None:
    op.execute(
        """
        CREATE FUNCTION public.walksafe_create_report_delivery_package_v3(
          p_package_id uuid, p_report_id uuid,
          p_expected_content_revision bigint, p_expected_content_sha256 text,
          p_review_decision_id uuid, p_expected_review_revision bigint,
          p_package_revision bigint, p_supersedes_package_id uuid,
          p_export_audit_id uuid, p_csv_sha256 text, p_manifest_sha256 text,
          p_package_sha256 text, p_csv_byte_count bigint,
          p_manifest_byte_count bigint, p_package_byte_count bigint,
          p_admin_id text, p_session_id uuid, p_device_id text,
          p_correlation_id uuid, p_generated_at timestamptz,
          p_proof_challenge_id uuid, p_request_body bytea,
          p_reconfirmation_nonce_sha256 text,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS TABLE (
          result_status text, package_id uuid, package_revision bigint
        ) AS $function$
        DECLARE
          v_now timestamptz := pg_catalog.clock_timestamp();
          v_content_revision bigint;
          v_content_sha256 text;
          v_review public.report_review_decisions%ROWTYPE;
          v_previous public.report_delivery_packages%ROWTYPE;
          v_existing_package public.report_delivery_packages%ROWTYPE;
          v_next_revision bigint;
          v_expected_supersedes uuid;
          v_body jsonb := pg_catalog.convert_from(p_request_body, 'UTF8')::jsonb;
        BEGIN
          IF pg_catalog.jsonb_typeof(v_body) <> 'object'
            OR NOT (v_body ?& ARRAY[
              'expected_content_revision', 'expected_review_revision'
            ])
            OR EXISTS (
              SELECT 1 FROM pg_catalog.jsonb_object_keys(v_body) AS key
               WHERE key NOT IN (
                 'expected_content_revision', 'expected_review_revision'
               )
            )
            OR pg_catalog.jsonb_typeof(
                 v_body -> 'expected_content_revision'
               ) <> 'number'
            OR pg_catalog.jsonb_typeof(
                 v_body -> 'expected_review_revision'
               ) <> 'number'
            OR (v_body -> 'expected_content_revision')::text
                 !~ '^(0|[1-9][0-9]*)$'
            OR (v_body -> 'expected_review_revision')::text
                 !~ '^[1-9][0-9]*$'
            OR (v_body ->> 'expected_content_revision')::bigint
                 IS DISTINCT FROM p_expected_content_revision
            OR (v_body ->> 'expected_review_revision')::bigint
                 IS DISTINCT FROM p_expected_review_revision
          THEN
            RAISE EXCEPTION 'delivery package proof body binding is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT report.content_revision INTO v_content_revision
            FROM public.reports AS report
           WHERE report.id = p_report_id
           FOR UPDATE;
          IF NOT FOUND THEN
            RETURN QUERY SELECT 'REPORT_NOT_FOUND'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          IF v_content_revision = 0 THEN
            v_content_sha256 := pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to('walksafe.report-content.v1', 'UTF8')
                || pg_catalog.decode('00', 'hex')
                || pg_catalog.convert_to(
                  '{"category_hint"' || pg_catalog.chr(58) ||
                  'null,"report_id"' || pg_catalog.chr(58) || '"' ||
                  p_report_id::text || '","revision"' || pg_catalog.chr(58) ||
                  '0,"user_description"' || pg_catalog.chr(58) || 'null}',
                  'UTF8'
                )
              ), 'hex'
            );
          ELSE
            SELECT revision.content_sha256 INTO v_content_sha256
              FROM public.report_content_revisions AS revision
             WHERE revision.report_id = p_report_id
               AND revision.revision = v_content_revision;
          END IF;
          IF v_content_revision IS DISTINCT FROM p_expected_content_revision
            OR v_content_sha256 IS NULL
            OR v_content_sha256 IS DISTINCT FROM p_expected_content_sha256
          THEN
            RETURN QUERY SELECT 'CONTENT_CONFLICT'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          SELECT decision.* INTO v_review
            FROM public.report_review_decisions AS decision
           WHERE decision.report_id = p_report_id
           ORDER BY decision.revision DESC
           LIMIT 1;
          IF NOT FOUND THEN
            RETURN QUERY SELECT 'REVIEW_INVALID'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          IF v_review.id IS DISTINCT FROM p_review_decision_id
            OR v_review.revision IS DISTINCT FROM p_expected_review_revision
          THEN
            RETURN QUERY SELECT 'REVIEW_CONFLICT'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          IF v_review.content_revision IS DISTINCT FROM v_content_revision
            OR v_review.decision IS DISTINCT FROM 'APPROVED'
            OR v_review.location_reviewed IS DISTINCT FROM true
            OR v_review.photo_reviewed IS DISTINCT FROM true
            OR v_review.privacy_reviewed IS DISTINCT FROM true
            OR v_review.duplicate_of_report_id IS NOT NULL
            OR v_review.evidence_grant_id IS NULL
            OR NOT EXISTS (
              SELECT 1
                FROM public.report_original_access_grants AS evidence_grant
               WHERE evidence_grant.id = v_review.evidence_grant_id
                 AND evidence_grant.report_id = p_report_id
                 AND evidence_grant.review_decision_id = v_review.id
                 AND evidence_grant.content_revision = v_content_revision
                 AND evidence_grant.consumed_at IS NOT NULL
                 AND evidence_grant.access_granted_at IS NOT NULL
                 AND evidence_grant.location_disclosed_at IS NOT NULL
            )
          THEN
            RETURN QUERY SELECT 'REVIEW_INVALID'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          SELECT package.* INTO v_existing_package
            FROM public.report_delivery_packages AS package
           WHERE package.id = p_package_id;
          IF FOUND THEN
            IF v_existing_package.report_id IS DISTINCT FROM p_report_id
              OR v_existing_package.review_decision_id
                   IS DISTINCT FROM p_review_decision_id
              OR v_existing_package.revision IS DISTINCT FROM p_package_revision
              OR v_existing_package.content_revision
                   IS DISTINCT FROM p_expected_content_revision
              OR v_existing_package.content_sha256
                   IS DISTINCT FROM p_expected_content_sha256
              OR v_existing_package.supersedes_package_id
                   IS DISTINCT FROM p_supersedes_package_id
              OR v_existing_package.export_audit_id
                   IS DISTINCT FROM p_export_audit_id
              OR v_existing_package.package_version IS DISTINCT FROM 2
              OR v_existing_package.csv_sha256 IS DISTINCT FROM p_csv_sha256
              OR v_existing_package.manifest_sha256
                   IS DISTINCT FROM p_manifest_sha256
              OR v_existing_package.package_sha256
                   IS DISTINCT FROM p_package_sha256
              OR v_existing_package.csv_byte_count
                   IS DISTINCT FROM p_csv_byte_count
              OR v_existing_package.manifest_byte_count
                   IS DISTINCT FROM p_manifest_byte_count
              OR v_existing_package.package_byte_count
                   IS DISTINCT FROM p_package_byte_count
              OR v_existing_package.admin_id IS DISTINCT FROM p_admin_id
              OR v_existing_package.session_id IS DISTINCT FROM p_session_id
              OR v_existing_package.device_id IS DISTINCT FROM p_device_id
              OR v_existing_package.correlation_id
                   IS DISTINCT FROM p_correlation_id
              OR v_existing_package.generated_at IS DISTINCT FROM p_generated_at
            THEN
              RAISE EXCEPTION 'delivery package replay is invalid'
                USING ERRCODE = '42501';
            END IF;
            PERFORM public.walksafe_claim_admin_report_mutation(
              p_proof_challenge_id, p_report_id, p_admin_id, p_session_id,
              p_device_id, p_correlation_id,
              'admin.report.delivery_package.create', 'delivery_package',
              'POST', p_package_id, p_request_body,
              p_reconfirmation_nonce_sha256,
              p_runtime_totp_secret, p_credential_issuer_key
            );
            RETURN QUERY SELECT 'EXISTING'::text, v_existing_package.id,
                                v_existing_package.revision;
            RETURN;
          END IF;
          SELECT package.* INTO v_previous
            FROM public.report_delivery_packages AS package
           WHERE package.report_id = p_report_id
           ORDER BY package.revision DESC
           LIMIT 1;
          IF FOUND THEN
            v_next_revision := v_previous.revision + 1;
            v_expected_supersedes := v_previous.id;
          ELSE
            v_next_revision := 1;
            v_expected_supersedes := NULL;
          END IF;
          IF p_package_revision IS DISTINCT FROM v_next_revision
            OR p_supersedes_package_id IS DISTINCT FROM v_expected_supersedes
          THEN
            RETURN QUERY SELECT 'REVISION_CONFLICT'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          IF p_package_id IS NULL OR p_export_audit_id IS NULL
            OR p_generated_at IS NULL
            OR p_generated_at < v_now - pg_catalog.make_interval(secs => 300)
            OR p_generated_at > v_now + pg_catalog.make_interval(secs => 300)
            OR p_csv_sha256 !~ '^[0-9a-f]{64}$'
            OR p_manifest_sha256 !~ '^[0-9a-f]{64}$'
            OR p_package_sha256 !~ '^[0-9a-f]{64}$'
            OR p_csv_byte_count <= 0 OR p_manifest_byte_count <= 0
            OR p_package_byte_count <= 0
            OR NOT EXISTS (
              SELECT 1 FROM public.report_export_audits AS export_audit
               WHERE export_audit.audit_id = p_export_audit_id
                 AND export_audit.actor_id = p_admin_id
                 AND export_audit.export_format = 'csv'
                 AND export_audit.profile = 'agency'
                 AND export_audit.row_count = 1
                 AND export_audit.rows_sha256 = p_csv_sha256
            )
          THEN
            RAISE EXCEPTION 'delivery package binding is invalid'
              USING ERRCODE = '23514';
          END IF;
          PERFORM public.walksafe_claim_admin_report_mutation(
            p_proof_challenge_id, p_report_id, p_admin_id, p_session_id,
            p_device_id, p_correlation_id,
            'admin.report.delivery_package.create', 'delivery_package',
            'POST', p_package_id, p_request_body,
            p_reconfirmation_nonce_sha256, p_runtime_totp_secret,
            p_credential_issuer_key
          );
          INSERT INTO public.report_delivery_packages (
            id, report_id, review_decision_id, revision, content_revision,
            content_sha256, supersedes_package_id, export_audit_id,
            schema_version, package_version, csv_sha256, manifest_sha256,
            package_sha256, csv_byte_count, manifest_byte_count,
            package_byte_count, admin_id, session_id, device_id,
            correlation_id, generated_at
          ) VALUES (
            p_package_id, p_report_id, p_review_decision_id, v_next_revision,
            v_content_revision, v_content_sha256, p_supersedes_package_id,
            p_export_audit_id, 'walksafe.admin-report-delivery-package.v2', 2,
            p_csv_sha256, p_manifest_sha256, p_package_sha256,
            p_csv_byte_count, p_manifest_byte_count, p_package_byte_count,
            p_admin_id, p_session_id, p_device_id, p_correlation_id,
            p_generated_at
          );
          RETURN QUERY SELECT 'CREATED'::text, p_package_id, v_next_revision;
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION public.walksafe_append_report_delivery_event_v3(
          p_event_id uuid, p_report_id uuid, p_package_revision bigint,
          p_expected_revision bigint, p_idempotency_key uuid,
          p_institution text, p_channel text, p_recipient text, p_status text,
          p_external_receipt_id text, p_reason text, p_evidence_sha256 text,
          p_observed_at timestamptz, p_admin_id text, p_session_id uuid,
          p_device_id text, p_correlation_id uuid, p_proof_challenge_id uuid,
          p_request_body bytea,
          p_runtime_totp_secret text, p_credential_issuer_key text
        ) RETURNS TABLE (
          result_status text, event_id uuid, event_revision bigint
        ) AS $function$
        DECLARE
          v_content_revision bigint;
          v_content_sha256 text;
          v_review public.report_review_decisions%ROWTYPE;
          v_package public.report_delivery_packages%ROWTYPE;
          v_previous public.report_institution_delivery_events%ROWTYPE;
          v_previous_package public.report_delivery_packages%ROWTYPE;
          v_existing public.report_institution_delivery_events%ROWTYPE;
          v_current_revision bigint;
          v_allowed boolean := false;
          v_body jsonb := pg_catalog.convert_from(p_request_body, 'UTF8')::jsonb;
        BEGIN
          IF pg_catalog.jsonb_typeof(v_body) <> 'object'
            OR NOT (v_body ?& ARRAY[
              'institution', 'channel', 'recipient', 'status',
              'external_receipt_id', 'reason', 'evidence_sha256',
              'observed_at', 'package_revision', 'expected_revision',
              'idempotency_key'
            ])
            OR EXISTS (
              SELECT 1 FROM pg_catalog.jsonb_object_keys(v_body) AS key
               WHERE key NOT IN (
                 'institution', 'channel', 'recipient', 'status',
                 'external_receipt_id', 'reason', 'evidence_sha256',
                 'observed_at', 'package_revision', 'expected_revision',
                 'idempotency_key'
               )
            )
            OR public.walksafe_python_strip(v_body ->> 'institution')
                 IS DISTINCT FROM p_institution
            OR public.walksafe_python_strip(v_body ->> 'channel')
                 IS DISTINCT FROM p_channel
            OR public.walksafe_python_strip(v_body ->> 'recipient')
                 IS DISTINCT FROM p_recipient
            OR v_body ->> 'status' IS DISTINCT FROM p_status
            OR public.walksafe_python_strip(v_body ->> 'external_receipt_id')
                 IS DISTINCT FROM p_external_receipt_id
            OR public.walksafe_python_strip(v_body ->> 'reason')
                 IS DISTINCT FROM p_reason
            OR v_body ->> 'evidence_sha256' IS DISTINCT FROM p_evidence_sha256
            OR (v_body ->> 'observed_at')::timestamptz
                 IS DISTINCT FROM p_observed_at
            OR pg_catalog.jsonb_typeof(v_body -> 'package_revision') <> 'number'
            OR (v_body -> 'package_revision')::text !~ '^[1-9][0-9]*$'
            OR (v_body ->> 'package_revision')::bigint
                 IS DISTINCT FROM p_package_revision
            OR pg_catalog.jsonb_typeof(v_body -> 'expected_revision') <> 'number'
            OR (v_body -> 'expected_revision')::text
                 !~ '^(0|[1-9][0-9]*)$'
            OR (v_body ->> 'expected_revision')::bigint
                 IS DISTINCT FROM p_expected_revision
            OR v_body ->> 'idempotency_key'
                 IS DISTINCT FROM p_idempotency_key::text
          THEN
            RAISE EXCEPTION 'delivery event proof body binding is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT report.content_revision INTO v_content_revision
            FROM public.reports AS report
           WHERE report.id = p_report_id
           FOR UPDATE;
          IF NOT FOUND THEN
            RETURN QUERY SELECT 'REPORT_NOT_FOUND'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          SELECT event.* INTO v_existing
            FROM public.report_institution_delivery_events AS event
           WHERE event.report_id = p_report_id
             AND event.idempotency_key = p_idempotency_key;
          IF FOUND THEN
            IF v_existing.package_revision IS NOT DISTINCT FROM p_package_revision
              AND v_existing.expected_revision = p_expected_revision
              AND v_existing.institution = p_institution
              AND v_existing.channel = p_channel
              AND v_existing.recipient = p_recipient
              AND v_existing.status = p_status
              AND v_existing.external_receipt_id IS NOT DISTINCT FROM p_external_receipt_id
              AND v_existing.reason = p_reason
              AND v_existing.evidence_sha256 IS NOT DISTINCT FROM p_evidence_sha256
              AND v_existing.observed_at = p_observed_at
            THEN
              IF p_event_id = v_existing.id THEN
                PERFORM public.walksafe_claim_admin_report_mutation(
                  p_proof_challenge_id, p_report_id, p_admin_id, p_session_id,
                  p_device_id, p_correlation_id, 'report.delivery.create',
                  'delivery_event', 'POST', p_event_id, p_request_body, NULL,
                  p_runtime_totp_secret, p_credential_issuer_key
                );
              END IF;
              RETURN QUERY SELECT 'EXISTING'::text, v_existing.id,
                                  v_existing.revision;
            ELSE
              RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT'::text, NULL::uuid,
                                  NULL::bigint;
            END IF;
            RETURN;
          END IF;
          SELECT event.* INTO v_previous
            FROM public.report_institution_delivery_events AS event
           WHERE event.report_id = p_report_id
           ORDER BY event.revision DESC
           LIMIT 1;
          v_current_revision := CASE WHEN FOUND THEN v_previous.revision ELSE 0 END;
          IF p_expected_revision IS DISTINCT FROM v_current_revision THEN
            RETURN QUERY SELECT 'REVISION_CONFLICT'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          IF v_content_revision = 0 THEN
            v_content_sha256 := pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to('walksafe.report-content.v1', 'UTF8')
                || pg_catalog.decode('00', 'hex')
                || pg_catalog.convert_to(
                  '{"category_hint"' || pg_catalog.chr(58) ||
                  'null,"report_id"' || pg_catalog.chr(58) || '"' ||
                  p_report_id::text || '","revision"' || pg_catalog.chr(58) ||
                  '0,"user_description"' || pg_catalog.chr(58) || 'null}',
                  'UTF8'
                )
              ), 'hex'
            );
          ELSE
            SELECT revision.content_sha256 INTO v_content_sha256
              FROM public.report_content_revisions AS revision
             WHERE revision.report_id = p_report_id
               AND revision.revision = v_content_revision;
          END IF;
          SELECT decision.* INTO v_review
            FROM public.report_review_decisions AS decision
           WHERE decision.report_id = p_report_id
           ORDER BY decision.revision DESC
           LIMIT 1;
          IF NOT FOUND OR v_review.decision <> 'APPROVED'
            OR v_review.content_revision <> v_content_revision
            OR NOT v_review.location_reviewed OR NOT v_review.photo_reviewed
            OR NOT v_review.privacy_reviewed
            OR v_review.duplicate_of_report_id IS NOT NULL
            OR v_review.evidence_grant_id IS NULL
          THEN
            RETURN QUERY SELECT 'REVIEW_INVALID'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          SELECT package.* INTO v_package
            FROM public.report_delivery_packages AS package
           WHERE package.report_id = p_report_id
             AND package.revision = p_package_revision;
          IF NOT FOUND OR v_package.package_version <> 2
            OR v_package.review_decision_id <> v_review.id
            OR v_package.content_revision <> v_content_revision
            OR v_package.content_sha256 IS DISTINCT FROM v_content_sha256
          THEN
            RETURN QUERY SELECT 'PACKAGE_INVALID'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          IF v_current_revision = 0 THEN
            v_allowed := p_status IN ('SUBMITTED', 'FAILED');
          ELSIF v_previous.package_id = v_package.id
            AND v_previous.package_revision = v_package.revision
          THEN
            v_allowed := CASE v_previous.status
              WHEN 'FAILED' THEN p_status IN ('FAILED', 'SUBMITTED')
              WHEN 'SUBMITTED' THEN p_status IN ('ACKNOWLEDGED', 'FAILED')
              WHEN 'ACKNOWLEDGED' THEN p_status = 'RESOLVED'
              ELSE false
            END;
          ELSE
            SELECT package.* INTO v_previous_package
              FROM public.report_delivery_packages AS package
             WHERE package.id = v_previous.package_id
               AND package.report_id = p_report_id
               AND package.revision = v_previous.package_revision;
            IF NOT FOUND THEN
              RETURN QUERY SELECT 'PACKAGE_REVISION_CONFLICT'::text,
                                  NULL::uuid, NULL::bigint;
              RETURN;
            END IF;
            IF v_previous.status <> 'RESOLVED'
              AND v_previous_package.package_version = 1
              AND v_previous_package.review_decision_id = v_package.review_decision_id
              AND v_previous_package.content_revision = v_package.content_revision
            THEN
              v_allowed := p_status = 'SUBMITTED';
            ELSIF v_previous_package.content_revision < v_package.content_revision
            THEN
              v_allowed := p_status IN ('SUBMITTED', 'FAILED');
            END IF;
          END IF;
          IF NOT v_allowed THEN
            RETURN QUERY SELECT 'TRANSITION_INVALID'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          PERFORM public.walksafe_claim_admin_report_mutation(
            p_proof_challenge_id, p_report_id, p_admin_id, p_session_id,
            p_device_id, p_correlation_id, 'report.delivery.create',
            'delivery_event', 'POST', p_event_id, p_request_body, NULL,
            p_runtime_totp_secret, p_credential_issuer_key
          );
          INSERT INTO public.report_institution_delivery_events (
            id, report_id, review_decision_id, package_id, package_revision,
            revision, institution, channel, recipient, status,
            external_receipt_id, reason, evidence_sha256, observed_at,
            expected_revision, idempotency_key, admin_id, session_id,
            device_id, correlation_id
          ) VALUES (
            p_event_id, p_report_id, v_review.id, v_package.id,
            v_package.revision, v_current_revision + 1, p_institution,
            p_channel, p_recipient, p_status, p_external_receipt_id, p_reason,
            p_evidence_sha256, p_observed_at, p_expected_revision,
            p_idempotency_key, p_admin_id, p_session_id, p_device_id,
            p_correlation_id
          );
          RETURN QUERY SELECT 'CREATED'::text, p_event_id,
                              v_current_revision + 1;
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )


def _install_report_ingest_trigger() -> None:
    op.execute(
        """
        CREATE FUNCTION public.walksafe_enforce_runtime_report_ingest_integrity()
        RETURNS trigger AS $function$
        DECLARE
          v_consent public.privacy_consent_events%ROWTYPE;
          v_lock_key text;
        BEGIN
          IF NOT (
               pg_catalog.pg_has_role(
                 session_user, 'walksafe_backend_runtime', 'USAGE'
               )
               OR pg_catalog.pg_has_role(
                 session_user, 'walksafe_backend_runtime', 'MEMBER'
               )
               OR pg_catalog.current_setting('role', true)
                    = 'walksafe_backend_runtime'
             ) OR EXISTS (
               SELECT 1 FROM pg_catalog.pg_roles AS login_role
                WHERE login_role.rolname = session_user
                  AND login_role.rolsuper
             ) THEN
            RETURN NEW;
          END IF;
          IF NEW.privacy_subject_hmac IS NULL OR NEW.account_generation IS NULL
            OR NEW.privacy_subject_hmac !~ '^[0-9a-f]{64}$'
            OR NEW.account_generation < 1
          THEN
            RAISE EXCEPTION 'runtime report privacy binding is required'
              USING ERRCODE = '23514';
          END IF;
          v_lock_key := 'walksafe-privacy-subject-v2' || pg_catalog.chr(10)
            || NEW.privacy_subject_hmac || pg_catalog.chr(10)
            || NEW.account_generation::text;
          PERFORM pg_catalog.pg_advisory_xact_lock_shared(
            pg_catalog.hashtextextended(v_lock_key, 0)
          );
          IF EXISTS (
               SELECT 1 FROM public.account_deletion_tombstones AS tombstone
                WHERE tombstone.privacy_subject_hmac = NEW.privacy_subject_hmac
                  AND tombstone.account_generation = NEW.account_generation
             )
          THEN
            RAISE EXCEPTION 'runtime report account generation is tombstoned'
              USING ERRCODE = '23514';
          END IF;
          SELECT consent.* INTO v_consent
            FROM public.privacy_consent_events AS consent
           WHERE consent.privacy_subject_hmac = NEW.privacy_subject_hmac
             AND consent.account_generation = NEW.account_generation
           ORDER BY consent.subject_revision DESC
           LIMIT 1;
          IF NOT FOUND OR v_consent.policy_version <> 'FP-013-1.1.0'
            OR v_consent.item_versions <> '{
              "automatic_reporting":"FP-013-AUTO-1.1.0",
              "mobile_network_transfer":"FP-013-MOBILE-1.0.0",
              "raw_source_collection":"FP-013-RAW-1.1.0",
              "training_reuse":"FP-013-TRAINING-1.1.0"
            }'::jsonb
            OR (
              NEW.metadata -> 'auto_reported' = 'true'::jsonb
              AND v_consent.automatic_reporting IS DISTINCT FROM true
            )
          THEN
            RAISE EXCEPTION 'current report consent is required'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "CREATE TRIGGER reports_runtime_ingest_integrity "
        "BEFORE INSERT ON public.reports FOR EACH ROW EXECUTE FUNCTION "
        "public.walksafe_enforce_runtime_report_ingest_integrity()"
    )


def _harden_acl() -> None:
    signatures = (
        _PYTHON_STRIP_SIGNATURE,
        _PYTHON_SPLIT_JOIN_SIGNATURE,
        _CLAIM_SIGNATURE,
        _ISSUE_V3_SIGNATURE,
        _PREPARE_V3_SIGNATURE,
        _COMPLETE_V3_SIGNATURE,
        _REVIEW_V3_SIGNATURE,
        _PACKAGE_V3_SIGNATURE,
        _DELIVERY_V3_SIGNATURE,
        _REPORT_INGEST_TRIGGER_SIGNATURE,
    )
    for signature in signatures:
        op.execute(f"ALTER FUNCTION {signature} OWNER TO {_OWNER_ROLE}")
        op.execute(
            f"REVOKE ALL ON FUNCTION {signature} FROM PUBLIC, {_RUNTIME_ROLE}"
        )
    for signature in (
        _ISSUE_V3_SIGNATURE,
        _PREPARE_V3_SIGNATURE,
        _COMPLETE_V3_SIGNATURE,
        _REVIEW_V3_SIGNATURE,
        _PACKAGE_V3_SIGNATURE,
        _DELIVERY_V3_SIGNATURE,
    ):
        op.execute(f"GRANT EXECUTE ON FUNCTION {signature} TO {_RUNTIME_ROLE}")
    for signature in _OLD_RUNTIME_FUNCTIONS:
        op.execute(f"REVOKE EXECUTE ON FUNCTION {signature} FROM {_RUNTIME_ROLE}")
    op.execute(
        "REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE "
        "public.report_delivery_packages, "
        "public.report_institution_delivery_events "
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
                 'report_delivery_packages',
                 'report_institution_delivery_events'
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
        f"GRANT SELECT ON TABLE public.report_delivery_packages, "
        f"public.report_institution_delivery_events TO {_RUNTIME_ROLE}"
    )


def _assert_boundary() -> None:
    op.execute(
        f"""
        DO $assertion$
        DECLARE target regprocedure;
        BEGIN
          IF {_OWNER_ROLE_CONTRACT_VIOLATION_SQL}
          THEN
            RAISE EXCEPTION 'evidence owner has unexpected role membership'
              USING ERRCODE = '42501';
          END IF;
          IF pg_catalog.has_table_privilege(
               '{_RUNTIME_ROLE}', 'public.report_delivery_packages', 'INSERT'
             )
            OR pg_catalog.has_any_column_privilege(
               '{_RUNTIME_ROLE}', 'public.report_delivery_packages', 'INSERT'
             )
            OR pg_catalog.has_table_privilege(
               '{_RUNTIME_ROLE}',
               'public.report_institution_delivery_events', 'INSERT'
             )
            OR pg_catalog.has_any_column_privilege(
               '{_RUNTIME_ROLE}',
               'public.report_institution_delivery_events', 'INSERT'
             )
          THEN
            RAISE EXCEPTION 'delivery runtime direct DML remains enabled'
              USING ERRCODE = '42501';
          END IF;
          FOREACH target IN ARRAY ARRAY[
            '{_ISSUE_V3_SIGNATURE}'::regprocedure,
            '{_PREPARE_V3_SIGNATURE}'::regprocedure,
            '{_COMPLETE_V3_SIGNATURE}'::regprocedure,
            '{_REVIEW_V3_SIGNATURE}'::regprocedure,
            '{_PACKAGE_V3_SIGNATURE}'::regprocedure,
            '{_DELIVERY_V3_SIGNATURE}'::regprocedure
          ] LOOP
            IF NOT pg_catalog.has_function_privilege(
                 '{_RUNTIME_ROLE}', target, 'EXECUTE'
               )
              OR EXISTS (
                SELECT 1
                  FROM pg_catalog.pg_proc AS function
                 WHERE function.oid = target
                   AND (
                     function.prosecdef IS DISTINCT FROM true
                     OR function.proowner <> '{_OWNER_ROLE}'::regrole::oid
                     OR function.proconfig IS DISTINCT FROM
                        ARRAY['search_path=pg_catalog, pg_temp']::text[]
                   )
              )
            THEN
              RAISE EXCEPTION 'administrator mutation function is unsafe'
                USING ERRCODE = '42501';
            END IF;
          END LOOP;
        END
        $assertion$
        """
    )


def upgrade() -> None:
    _preflight_existing_database()
    _create_claim_ledger()
    _grant_owner_boundary_privileges()
    _install_python_whitespace_functions()
    _install_claim_function()
    _install_original_evidence_v3_functions()
    _install_delivery_v3_functions()
    _install_report_ingest_trigger()
    _harden_acl()
    _assert_boundary()


def downgrade() -> None:
    op.execute(
        """
        DO $guard$
        BEGIN
          LOCK TABLE public.admin_report_mutation_claims
            IN ACCESS EXCLUSIVE MODE;
          IF EXISTS (SELECT 1 FROM public.admin_report_mutation_claims) THEN
            RAISE EXCEPTION
              'cannot downgrade administrator integrity boundary while claims exist'
              USING ERRCODE = '55000';
          END IF;
        END
        $guard$
        """
    )
    op.execute(
        "DROP TRIGGER IF EXISTS reports_runtime_ingest_integrity "
        "ON public.reports"
    )
    op.execute(
        "ALTER TABLE public.report_review_decisions DROP CONSTRAINT "
        "ck_report_review_decisions_evidence_grant"
    )
    op.execute(
        "ALTER TABLE public.report_review_decisions ADD CONSTRAINT "
        "ck_report_review_decisions_evidence_grant CHECK ("
        "(decision = 'APPROVED' AND evidence_grant_id IS NOT NULL) OR "
        "(decision <> 'APPROVED' AND evidence_grant_id IS NULL)) NOT VALID"
    )
    for signature in (
        _REPORT_INGEST_TRIGGER_SIGNATURE,
        _DELIVERY_V3_SIGNATURE,
        _PACKAGE_V3_SIGNATURE,
        _REVIEW_V3_SIGNATURE,
        _COMPLETE_V3_SIGNATURE,
        _PREPARE_V3_SIGNATURE,
        _ISSUE_V3_SIGNATURE,
        _CLAIM_SIGNATURE,
        _PYTHON_SPLIT_JOIN_SIGNATURE,
        _PYTHON_STRIP_SIGNATURE,
    ):
        op.execute(f"DROP FUNCTION IF EXISTS {signature}")
    op.execute("DROP TABLE public.admin_report_mutation_claims")
    op.execute(
        "REVOKE SELECT ON TABLE public.admin_device_proof_challenges, "
        "public.admin_security_reconfirmations, public.privacy_consent_events, "
        "public.account_deletion_tombstones, public.report_content_revisions, "
        "public.report_delivery_packages, "
        "public.report_institution_delivery_events, public.report_export_audits "
        f"FROM {_OWNER_ROLE}"
    )
    op.execute(
        "REVOKE INSERT ON TABLE public.report_delivery_packages, "
        f"public.report_institution_delivery_events FROM {_OWNER_ROLE}"
    )
    for signature in _OLD_RUNTIME_FUNCTIONS:
        op.execute(f"GRANT EXECUTE ON FUNCTION {signature} TO {_RUNTIME_ROLE}")
    op.execute(
        "GRANT INSERT ON TABLE public.report_delivery_packages, "
        f"public.report_institution_delivery_events TO {_RUNTIME_ROLE}"
    )
