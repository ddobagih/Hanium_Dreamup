"""Make administrator report review decisions replay-safe.

Revision ID: 202609010003
Revises: 202609010002
"""

from alembic import op


revision = "202609010003"
down_revision = "202609010002"
branch_labels = None
depends_on = None


def _function_definition(*parts: str) -> str:
    # Function source whitespace is part of the runtime integrity digest. Keep
    # the predecessor's indentation so downgrade restores its exact hash.
    statement = parts[0] + "".join(part.lstrip("\n") for part in parts[1:])
    prefix, body_and_suffix = statement.split("AS $function$\n", 1)
    body, suffix = body_and_suffix.rsplit("\n$function$", 1)
    indented_body = "\n".join(f"        {line}" for line in body.splitlines())
    return (
        prefix
        + "AS $function$\n"
        + indented_body
        + "\n        $function$"
        + suffix
    )


_CLAIM_FUNCTION_PREFIX = """
CREATE OR REPLACE FUNCTION public.walksafe_claim_admin_report_mutation(
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
"""

_CLAIM_FUNCTION_BODY = """
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
"""

_CLAIM_FUNCTION_SUFFIX = """
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

_IDEMPOTENT_CLAIM_FUNCTION = _function_definition(
    _CLAIM_FUNCTION_PREFIX,
    "  v_resource_claim public.admin_report_mutation_claims%ROWTYPE;\n",
    _CLAIM_FUNCTION_BODY,
    """
  IF p_resource_type = 'review_decision' THEN
    SELECT claim.* INTO v_resource_claim
      FROM public.admin_report_mutation_claims AS claim
     WHERE claim.resource_type = p_resource_type
       AND claim.resource_id = p_resource_id
     FOR UPDATE;
    IF FOUND THEN
      IF v_resource_claim.reconfirmation_id IS NULL
        AND v_resource_claim.report_id = p_report_id
        AND v_resource_claim.action = p_action
        AND v_resource_claim.admin_id = p_admin_id
      THEN
        RETURN v_reconfirmation_id;
      END IF;
      RAISE EXCEPTION 'administrator mutation resource replay is invalid'
        USING ERRCODE = '42501';
    END IF;
  END IF;
""",
    _CLAIM_FUNCTION_SUFFIX,
)

_LEGACY_CLAIM_FUNCTION = _function_definition(
    _CLAIM_FUNCTION_PREFIX,
    _CLAIM_FUNCTION_BODY,
    _CLAIM_FUNCTION_SUFFIX,
)


_REVIEW_FUNCTION_PREFIX = """
CREATE OR REPLACE FUNCTION public.walksafe_append_report_review_decision_v3(
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
"""

_LEGACY_REVIEW_PROOF_BODY = """
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
"""

_IDEMPOTENT_REVIEW_PROOF_BODY = """
  IF pg_catalog.jsonb_typeof(v_body) <> 'object'
    OR NOT (v_body ?& ARRAY[
      'decision_id', 'decision', 'reason', 'duplicate_of_report_id',
      'location_reviewed', 'photo_reviewed', 'privacy_reviewed'
    ])
    OR EXISTS (
      SELECT 1 FROM pg_catalog.jsonb_object_keys(v_body) AS key
       WHERE key NOT IN (
         'decision_id', 'decision', 'reason', 'user_visible_reason',
         'duplicate_of_report_id', 'location_reviewed',
         'photo_reviewed', 'privacy_reviewed', 'content_revision',
         'evidence_grant_id'
       )
    )
    OR pg_catalog.jsonb_typeof(v_body -> 'decision_id') <> 'string'
    OR pg_catalog.jsonb_typeof(v_body -> 'decision') <> 'string'
    OR pg_catalog.jsonb_typeof(v_body -> 'reason') <> 'string'
    OR pg_catalog.jsonb_typeof(v_body -> 'location_reviewed') <> 'boolean'
    OR pg_catalog.jsonb_typeof(v_body -> 'photo_reviewed') <> 'boolean'
    OR pg_catalog.jsonb_typeof(v_body -> 'privacy_reviewed') <> 'boolean'
    OR v_body ->> 'decision_id' IS DISTINCT FROM p_decision_id::text
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
"""

_LEGACY_REVIEW_EXISTING = """
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
"""

_IDEMPOTENT_REVIEW_EXISTING = """
  PERFORM pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(
      'walksafe-report-review-decision-idempotency-v1:'
        || p_decision_id::text,
      0
    )
  );
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
    THEN
      RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT'::text,
                          v_existing_decision.id,
                          v_existing_decision.revision;
      RETURN;
    END IF;
"""

_REVIEW_FUNCTION_SUFFIX = """
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

_IDEMPOTENT_REVIEW_FUNCTION = _function_definition(
    _REVIEW_FUNCTION_PREFIX,
    _IDEMPOTENT_REVIEW_PROOF_BODY,
    _IDEMPOTENT_REVIEW_EXISTING,
    _REVIEW_FUNCTION_SUFFIX,
)

_LEGACY_REVIEW_FUNCTION = _function_definition(
    _REVIEW_FUNCTION_PREFIX,
    _LEGACY_REVIEW_PROOF_BODY,
    _LEGACY_REVIEW_EXISTING,
    _REVIEW_FUNCTION_SUFFIX,
)


def upgrade() -> None:
    op.execute(_IDEMPOTENT_CLAIM_FUNCTION)
    op.execute(_IDEMPOTENT_REVIEW_FUNCTION)


def downgrade() -> None:
    op.execute(_LEGACY_CLAIM_FUNCTION)
    op.execute(_LEGACY_REVIEW_FUNCTION)
