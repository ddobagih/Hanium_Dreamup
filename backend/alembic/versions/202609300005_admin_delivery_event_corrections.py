"""Append-only corrections of manual institution-delivery events (S18-40).

A wrongly recorded delivery observation (for example an ACKNOWLEDGED or
RESOLVED entered for the wrong case) could not be undone: the history is
append-only and RESOLVED is terminal. Rows are still never updated or deleted;
instead a separate correction names the corrected event, a reason code and a
memo, and every consumer skips corrected events when it derives the effective
delivery state.

- report_institution_delivery_corrections: one row per corrected event
  (PRIMARY KEY corrects_event_id, UNIQUE correction id), append-only trigger, owner SELECT/INSERT and
  runtime SELECT only.
- walksafe_correct_report_delivery_event: SECURITY DEFINER; binds the exact
  request body, the administrator session, a consumed ACTION device proof for
  report.delivery.correct and a consumed HIGH reconfirmation for the same
  path. Only the latest effective (uncorrected) event of the same report can
  be corrected, so the uncorrected events always form a valid state machine
  chain; repeated corrections step further back.
- walksafe_append_report_delivery_event_v5: v4 with the transition base taken
  from the latest uncorrected event; the CAS revision stays the highest event
  revision of the report.
- v3/v4 delivery functions lose runtime EXECUTE (they do not know corrections);
  downgrade restores it.
- The reconfirmation issuer accepts report.delivery.correct for its exact path.

Downgrade refuses while any correction exists (it would lose evidence).

Revision ID: 202609300005
Revises: 202609300004
Create Date: 2026-09-30
"""

from __future__ import annotations

from alembic import op


revision = "202609300005"
down_revision = "202609300004"
branch_labels = None
depends_on = None

_OWNER_ROLE = "walksafe_report_evidence_owner"
_RUNTIME_ROLE = "walksafe_backend_runtime"
_DELIVERY_ARGS = (
    "uuid, uuid, bigint, bigint, uuid, text, text, text, text, text, text, text, "
    "timestamptz, text, uuid, text, uuid, uuid, bytea, text, text"
)
_DELIVERY_V3_SIGNATURE = f"public.walksafe_append_report_delivery_event_v3({_DELIVERY_ARGS})"
_DELIVERY_V4_SIGNATURE = f"public.walksafe_append_report_delivery_event_v4({_DELIVERY_ARGS})"
_DELIVERY_V5_SIGNATURE = f"public.walksafe_append_report_delivery_event_v5({_DELIVERY_ARGS})"
_CORRECT_SIGNATURE = (
    "public.walksafe_correct_report_delivery_event("
    "uuid, uuid, uuid, bigint, text, text, text, uuid, text, uuid, uuid, bytea, "
    "text, text, text)"
)
_ISSUE_RECONFIRMATION_SIGNATURE = (
    "public.walksafe_issue_admin_reconfirmation("
    "uuid,text,uuid,text,text,text,text,text,timestamptz,timestamptz,"
    "bigint,bigint,text,text)"
)
_ALLOWLIST_ANCHOR = """              OR (p_action = 'device.report_lost'
                AND p_method = 'POST'
                AND p_path ~
                  '^/admin/security/devices/[A-Za-z0-9][A-Za-z0-9._:-]{7,127}/report-lost$')
"""
_UUID = (
    "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    "[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
)
_CORRECTION_ALLOWLIST = f"""              OR (p_action = 'report.delivery.correct'
                AND p_method = 'POST'
                AND p_path ~
                  '^/reports/{_UUID}/delivery-corrections$')
"""
REASON_CODES = ("WRONG_STATUS", "WRONG_REPORT", "DUPLICATE_ENTRY", "OTHER")
_REASON_CODES_SQL = ", ".join(f"'{code}'" for code in REASON_CODES)


def _replace_issue_allowlist(old: str, new: str) -> None:
    # Same exact-fragment rewrite as 202609280002: every other byte of the
    # installed definition (owner, ACL, SECURITY DEFINER, search_path) is kept.
    op.execute(
        f"""
        DO $migration$
        DECLARE
          v_definition text;
          v_old text := $old${old}$old$;
          v_new text := $new${new}$new$;
        BEGIN
          SELECT pg_catalog.pg_get_functiondef(
                   '{_ISSUE_RECONFIRMATION_SIGNATURE}'::regprocedure
                 )
            INTO v_definition;
          IF v_definition IS NULL
            OR (
              pg_catalog.length(v_definition)
              - pg_catalog.length(pg_catalog.replace(v_definition, v_old, ''))
            ) <> pg_catalog.length(v_old)
          THEN
            RAISE EXCEPTION
              'administrator reconfirmation allowlist predecessor is unexpected';
          END IF;
          EXECUTE pg_catalog.replace(v_definition, v_old, v_new);
        END
        $migration$
        """
    )


_CORRECTIONS_TABLE = f"""
        CREATE TABLE public.report_institution_delivery_corrections (
          id uuid NOT NULL,
          report_id uuid NOT NULL,
          corrects_event_id uuid NOT NULL,
          corrected_revision bigint NOT NULL,
          expected_revision bigint NOT NULL,
          reason_code varchar(32) NOT NULL,
          memo varchar(500) NOT NULL,
          admin_id varchar(64) NOT NULL,
          session_id uuid NOT NULL,
          device_id varchar(128) NOT NULL,
          correlation_id uuid NOT NULL,
          proof_challenge_id uuid,
          reconfirmation_id uuid,
          created_at timestamptz NOT NULL DEFAULT pg_catalog.clock_timestamp(),
          -- One correction per event: the corrected event is the key.
          CONSTRAINT pk_report_delivery_corrections
            PRIMARY KEY (corrects_event_id),
          CONSTRAINT uq_report_delivery_correction_id UNIQUE (id),
          CONSTRAINT uq_report_delivery_correction_challenge
            UNIQUE (proof_challenge_id),
          CONSTRAINT uq_report_delivery_correction_reconfirmation
            UNIQUE (reconfirmation_id),
          CONSTRAINT fk_report_delivery_correction_event
            FOREIGN KEY (corrects_event_id)
            REFERENCES public.report_institution_delivery_events(id)
            ON DELETE RESTRICT,
          CONSTRAINT fk_report_delivery_correction_challenge
            FOREIGN KEY (proof_challenge_id)
            REFERENCES public.admin_device_proof_challenges(id)
            ON DELETE RESTRICT,
          CONSTRAINT fk_report_delivery_correction_reconfirmation
            FOREIGN KEY (reconfirmation_id)
            REFERENCES public.admin_security_reconfirmations(id)
            ON DELETE RESTRICT,
          CONSTRAINT ck_report_delivery_correction_reason_code
            CHECK (reason_code IN ({_REASON_CODES_SQL})),
          CONSTRAINT ck_report_delivery_correction_memo
            CHECK (length(memo) BETWEEN 1 AND 500),
          CONSTRAINT ck_report_delivery_correction_revisions
            CHECK (corrected_revision >= 1
                   AND expected_revision >= corrected_revision)
        )
"""


_CORRECT_FUNCTION = f"""
        CREATE FUNCTION public.walksafe_correct_report_delivery_event(
          p_correction_id uuid, p_report_id uuid, p_corrects_event_id uuid,
          p_expected_revision bigint, p_reason_code text, p_memo text,
          p_admin_id text, p_session_id uuid, p_device_id text,
          p_correlation_id uuid, p_proof_challenge_id uuid,
          p_request_body bytea, p_reconfirmation_nonce_sha256 text,
          p_runtime_totp_secret text, p_credential_issuer_key text
        ) RETURNS TABLE (
          result_status text, correction_id uuid, corrected_event_id uuid
        ) AS $function$
        DECLARE
          v_now timestamptz := pg_catalog.clock_timestamp();
          v_body jsonb;
          v_path text;
          v_current_revision bigint;
          v_latest_id uuid;
          v_target public.report_institution_delivery_events%ROWTYPE;
          v_existing public.report_institution_delivery_corrections%ROWTYPE;
          v_challenge public.admin_device_proof_challenges%ROWTYPE;
          v_reconfirmation_id uuid;
        BEGIN
          IF p_correction_id IS NULL OR p_report_id IS NULL
            OR p_corrects_event_id IS NULL OR p_expected_revision IS NULL
            OR p_admin_id IS NULL OR p_session_id IS NULL
            OR p_device_id IS NULL OR p_correlation_id IS NULL
            OR p_proof_challenge_id IS NULL OR p_request_body IS NULL
            OR pg_catalog.octet_length(p_request_body) NOT BETWEEN 2 AND 65536
          THEN
            RAISE EXCEPTION 'delivery correction input is invalid'
              USING ERRCODE = '22023';
          END IF;
          v_body := pg_catalog.convert_from(p_request_body, 'UTF8')::jsonb;
          IF pg_catalog.jsonb_typeof(v_body) <> 'object'
            OR NOT (v_body ?& ARRAY[
              'correction_id', 'corrects_event_id', 'expected_revision',
              'reason_code', 'memo'
            ])
            OR EXISTS (
              SELECT 1 FROM pg_catalog.jsonb_object_keys(v_body) AS key
               WHERE key NOT IN (
                 'correction_id', 'corrects_event_id', 'expected_revision',
                 'reason_code', 'memo'
               )
            )
            OR v_body ->> 'correction_id' IS DISTINCT FROM p_correction_id::text
            OR v_body ->> 'corrects_event_id'
                 IS DISTINCT FROM p_corrects_event_id::text
            OR pg_catalog.jsonb_typeof(v_body -> 'expected_revision') <> 'number'
            OR (v_body -> 'expected_revision')::text !~ '^(0|[1-9][0-9]{{0,17}})$'
            OR (v_body ->> 'expected_revision')::bigint
                 IS DISTINCT FROM p_expected_revision
            OR v_body ->> 'reason_code' IS DISTINCT FROM p_reason_code
            OR public.walksafe_python_strip(v_body ->> 'memo')
                 IS DISTINCT FROM p_memo
          THEN
            RAISE EXCEPTION 'delivery correction proof body binding is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_reason_code IS NULL
            OR p_reason_code NOT IN ({_REASON_CODES_SQL})
            OR p_memo IS NULL
            OR public.walksafe_python_strip(p_memo) IS DISTINCT FROM p_memo
            OR pg_catalog.char_length(p_memo) NOT BETWEEN 1 AND 500
          THEN
            RETURN QUERY SELECT 'REASON_INVALID'::text, NULL::uuid, NULL::uuid;
            RETURN;
          END IF;
          -- Same lock order as walksafe_append_report_delivery_event_v5:
          -- the report row first, then the administrator session.
          PERFORM report.id
            FROM public.reports AS report
           WHERE report.id = p_report_id
           FOR UPDATE;
          IF NOT FOUND THEN
            RETURN QUERY SELECT 'REPORT_NOT_FOUND'::text, NULL::uuid, NULL::uuid;
            RETURN;
          END IF;
          IF public.walksafe_lock_admin_original_access_session(
               p_admin_id, p_session_id, p_device_id, v_now,
               p_runtime_totp_secret, p_credential_issuer_key
             ) IS DISTINCT FROM true
          THEN
            RETURN QUERY SELECT 'AUTH_INVALID'::text, NULL::uuid, NULL::uuid;
            RETURN;
          END IF;
          v_path := '/reports/' || p_report_id::text || '/delivery-corrections';
          SELECT challenge.* INTO v_challenge
            FROM public.admin_device_proof_challenges AS challenge
           WHERE challenge.id = p_proof_challenge_id;
          IF NOT FOUND
            OR v_challenge.admin_id IS DISTINCT FROM p_admin_id
            OR v_challenge.session_id IS DISTINCT FROM p_session_id
            OR v_challenge.device_id IS DISTINCT FROM p_device_id
            OR v_challenge.correlation_id IS DISTINCT FROM p_correlation_id
            OR v_challenge.action IS DISTINCT FROM 'report.delivery.correct'
            OR v_challenge.purpose IS DISTINCT FROM 'ACTION'
            OR v_challenge.read_purpose IS NOT NULL
            OR v_challenge.method IS DISTINCT FROM 'POST'
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
            RAISE EXCEPTION 'delivery correction device proof binding is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_reconfirmation_nonce_sha256 IS NULL
            OR p_reconfirmation_nonce_sha256 !~ '^[0-9a-f]{{64}}$'
          THEN
            RAISE EXCEPTION 'delivery correction reconfirmation is required'
              USING ERRCODE = '42501';
          END IF;
          SELECT reconfirmation.id INTO v_reconfirmation_id
            FROM public.admin_security_reconfirmations AS reconfirmation
           WHERE reconfirmation.nonce_sha256 = p_reconfirmation_nonce_sha256
             AND reconfirmation.admin_id = p_admin_id
             AND reconfirmation.session_id = p_session_id
             AND reconfirmation.device_id = p_device_id
             AND reconfirmation.action = 'report.delivery.correct'
             AND reconfirmation.method = 'POST'
             AND reconfirmation.path = v_path
             AND reconfirmation.consumed_at IS NOT NULL
             AND reconfirmation.consumed_at <= v_now
             AND reconfirmation.expires_at > v_now;
          IF v_reconfirmation_id IS NULL THEN
            RAISE EXCEPTION 'delivery correction reconfirmation binding is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT correction.* INTO v_existing
            FROM public.report_institution_delivery_corrections AS correction
           WHERE correction.id = p_correction_id;
          IF FOUND THEN
            IF v_existing.report_id = p_report_id
              AND v_existing.corrects_event_id = p_corrects_event_id
              AND v_existing.expected_revision = p_expected_revision
              AND v_existing.reason_code = p_reason_code
              AND v_existing.memo = p_memo
              AND v_existing.admin_id = p_admin_id
            THEN
              RETURN QUERY SELECT 'EXISTING'::text, v_existing.id,
                                  v_existing.corrects_event_id;
            ELSE
              RETURN QUERY SELECT 'IDEMPOTENCY_CONFLICT'::text, NULL::uuid,
                                  NULL::uuid;
            END IF;
            RETURN;
          END IF;
          SELECT COALESCE(pg_catalog.max(event.revision), 0)
            INTO v_current_revision
            FROM public.report_institution_delivery_events AS event
           WHERE event.report_id = p_report_id;
          IF p_expected_revision IS DISTINCT FROM v_current_revision THEN
            RETURN QUERY SELECT 'REVISION_CONFLICT'::text, NULL::uuid, NULL::uuid;
            RETURN;
          END IF;
          SELECT event.* INTO v_target
            FROM public.report_institution_delivery_events AS event
           WHERE event.id = p_corrects_event_id;
          IF NOT FOUND OR v_target.report_id IS DISTINCT FROM p_report_id THEN
            RETURN QUERY SELECT 'TARGET_NOT_FOUND'::text, NULL::uuid, NULL::uuid;
            RETURN;
          END IF;
          IF EXISTS (
            SELECT 1
              FROM public.report_institution_delivery_corrections AS correction
             WHERE correction.corrects_event_id = p_corrects_event_id
          ) THEN
            RETURN QUERY SELECT 'ALREADY_CORRECTED'::text, NULL::uuid, NULL::uuid;
            RETURN;
          END IF;
          SELECT event.id INTO v_latest_id
            FROM public.report_institution_delivery_events AS event
           WHERE event.report_id = p_report_id
             AND NOT EXISTS (
               SELECT 1
                 FROM public.report_institution_delivery_corrections AS correction
                WHERE correction.corrects_event_id = event.id
             )
           ORDER BY event.revision DESC
           LIMIT 1;
          IF v_latest_id IS DISTINCT FROM p_corrects_event_id THEN
            RETURN QUERY SELECT 'TARGET_NOT_LATEST'::text, NULL::uuid, NULL::uuid;
            RETURN;
          END IF;
          INSERT INTO public.report_institution_delivery_corrections (
            id, report_id, corrects_event_id, corrected_revision,
            expected_revision, reason_code, memo, admin_id, session_id,
            device_id, correlation_id, proof_challenge_id, reconfirmation_id,
            created_at
          ) VALUES (
            p_correction_id, p_report_id, p_corrects_event_id,
            v_target.revision, p_expected_revision, p_reason_code, p_memo,
            p_admin_id, p_session_id, p_device_id, p_correlation_id,
            p_proof_challenge_id, v_reconfirmation_id, v_now
          );
          RETURN QUERY SELECT 'CREATED'::text, p_correction_id,
                              p_corrects_event_id;
        END;
        $function$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
"""


_DELIVERY_V5_FUNCTION = """
        CREATE FUNCTION public.walksafe_append_report_delivery_event_v5(
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
          v_review_id uuid;
          v_package public.report_delivery_packages%ROWTYPE;
          v_previous public.report_institution_delivery_events%ROWTYPE;
          v_previous_package public.report_delivery_packages%ROWTYPE;
          v_existing public.report_institution_delivery_events%ROWTYPE;
          v_current_revision bigint;
          v_has_previous boolean := false;
          v_follow_up boolean := false;
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
          -- The CAS revision is the highest event revision of the report,
          -- corrected or not; corrections never change it.
          SELECT COALESCE(pg_catalog.max(event.revision), 0)
            INTO v_current_revision
            FROM public.report_institution_delivery_events AS event
           WHERE event.report_id = p_report_id;
          IF p_expected_revision IS DISTINCT FROM v_current_revision THEN
            RETURN QUERY SELECT 'REVISION_CONFLICT'::text, NULL::uuid, NULL::bigint;
            RETURN;
          END IF;
          -- S18-40: the transition base is the latest event no correction
          -- names; corrected events stay in the history without state.
          SELECT event.* INTO v_previous
            FROM public.report_institution_delivery_events AS event
           WHERE event.report_id = p_report_id
             AND NOT EXISTS (
               SELECT 1
                 FROM public.report_institution_delivery_corrections AS correction
                WHERE correction.corrects_event_id = event.id
             )
           ORDER BY event.revision DESC
           LIMIT 1;
          v_has_previous := FOUND;
          -- A follow-up observation continues the submission it names: it is
          -- bound to that submission's review decision and package, not to
          -- the latest review or content.
          v_follow_up := v_has_previous
            AND v_previous.status IN ('SUBMITTED', 'ACKNOWLEDGED')
            AND v_previous.package_revision IS NOT DISTINCT FROM p_package_revision
            AND p_status <> 'SUBMITTED';
          IF v_follow_up THEN
            SELECT package.* INTO v_package
              FROM public.report_delivery_packages AS package
             WHERE package.report_id = p_report_id
               AND package.revision = p_package_revision;
            IF NOT FOUND OR v_package.package_version <> 2
              OR v_package.id IS DISTINCT FROM v_previous.package_id
              OR v_package.review_decision_id
                   IS DISTINCT FROM v_previous.review_decision_id
            THEN
              RETURN QUERY SELECT 'PACKAGE_INVALID'::text, NULL::uuid, NULL::bigint;
              RETURN;
            END IF;
            v_review_id := v_previous.review_decision_id;
            v_allowed := CASE v_previous.status
              WHEN 'SUBMITTED' THEN p_status IN ('ACKNOWLEDGED', 'FAILED')
              WHEN 'ACKNOWLEDGED' THEN p_status = 'RESOLVED'
              ELSE false
            END;
          ELSE
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
            v_review_id := v_review.id;
            IF NOT v_has_previous THEN
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
            p_event_id, p_report_id, v_review_id, v_package.id,
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


def upgrade() -> None:
    op.execute(_CORRECTIONS_TABLE)
    op.execute(
        "CREATE INDEX ix_report_delivery_corrections_report_id "
        "ON public.report_institution_delivery_corrections(report_id)"
    )
    op.execute(
        "REVOKE ALL ON TABLE public.report_institution_delivery_corrections "
        f"FROM PUBLIC, {_RUNTIME_ROLE}, {_OWNER_ROLE}"
    )
    op.execute(
        "GRANT SELECT ON TABLE public.report_institution_delivery_corrections "
        f"TO {_RUNTIME_ROLE}"
    )
    op.execute(
        "GRANT SELECT, INSERT ON TABLE "
        f"public.report_institution_delivery_corrections TO {_OWNER_ROLE}"
    )
    op.execute(
        "CREATE TRIGGER report_institution_delivery_corrections_append_only "
        "BEFORE UPDATE OR DELETE OR TRUNCATE ON "
        "public.report_institution_delivery_corrections "
        "FOR EACH STATEMENT EXECUTE FUNCTION public.walksafe_reject_audit_mutation()"
    )
    op.execute(_CORRECT_FUNCTION)
    op.execute(_DELIVERY_V5_FUNCTION)
    for signature in (_CORRECT_SIGNATURE, _DELIVERY_V5_SIGNATURE):
        op.execute(f"ALTER FUNCTION {signature} OWNER TO {_OWNER_ROLE}")
        op.execute(
            f"REVOKE ALL ON FUNCTION {signature} FROM PUBLIC, {_RUNTIME_ROLE}"
        )
        op.execute(f"GRANT EXECUTE ON FUNCTION {signature} TO {_RUNTIME_ROLE}")
    # The predecessors ignore corrections, so the runtime may no longer call them.
    for signature in (_DELIVERY_V3_SIGNATURE, _DELIVERY_V4_SIGNATURE):
        op.execute(f"REVOKE EXECUTE ON FUNCTION {signature} FROM {_RUNTIME_ROLE}")
    _replace_issue_allowlist(
        _ALLOWLIST_ANCHOR,
        _CORRECTION_ALLOWLIST + _ALLOWLIST_ANCHOR,
    )


def downgrade() -> None:
    op.execute(
        """
        DO $migration$
        BEGIN
          IF EXISTS (
            SELECT 1 FROM public.report_institution_delivery_corrections
          ) THEN
            RAISE EXCEPTION
              'delivery corrections exist; downgrade would discard them'
              USING ERRCODE = '55000';
          END IF;
        END
        $migration$
        """
    )
    _replace_issue_allowlist(
        _CORRECTION_ALLOWLIST + _ALLOWLIST_ANCHOR,
        _ALLOWLIST_ANCHOR,
    )
    for signature in (_DELIVERY_V3_SIGNATURE, _DELIVERY_V4_SIGNATURE):
        op.execute(f"GRANT EXECUTE ON FUNCTION {signature} TO {_RUNTIME_ROLE}")
    op.execute(f"DROP FUNCTION {_DELIVERY_V5_SIGNATURE}")
    op.execute(f"DROP FUNCTION {_CORRECT_SIGNATURE}")
    op.execute("DROP TABLE public.report_institution_delivery_corrections")
