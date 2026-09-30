"""Admin review/delivery v4 functions and lost-device reconfirmation.

- S18-35: walksafe_append_report_review_decision_v4 binds the review revision
  the administrator saw (expected_review_revision) and returns
  REVIEW_REVISION_CONFLICT instead of silently stacking a late decision.
- S18-41: walksafe_append_report_delivery_event_v4 lets a follow-up
  observation (ACKNOWLEDGED/RESOLVED/FAILED) of an existing submission bind to
  that submission's review decision and package; a new SUBMITTED still needs
  the latest approval of the current content.
- S18-29: device.report_lost becomes a HIGH action, so the reconfirmation
  issuer must accept its exact path.

The v3 functions stay installed and executable (integrity boundary parity and
downgrade); an older backend still fails closed at the integrity revision
check (its admin_report_integrity compatible set and health head lack this
revision), so the admin app and backend must ship together.

Revision ID: 202609280001
Revises: 202609240002
Create Date: 2026-09-28
"""

from __future__ import annotations

from alembic import op


revision = "202609280001"
down_revision = "202609240002"
branch_labels = None
depends_on = None

_OWNER_ROLE = "walksafe_report_evidence_owner"
_RUNTIME_ROLE = "walksafe_backend_runtime"
_REVIEW_V4_SIGNATURE = (
    "public.walksafe_append_report_review_decision_v4("
    "uuid, uuid, bigint, bigint, text, text, text, uuid, boolean, boolean, "
    "boolean, uuid, text, uuid, text, uuid, uuid, bytea, text, text)"
)
_DELIVERY_V4_SIGNATURE = (
    "public.walksafe_append_report_delivery_event_v4("
    "uuid, uuid, bigint, bigint, uuid, text, text, text, text, text, text, text, "
    "timestamptz, text, uuid, text, uuid, uuid, bytea, text, text)"
)
_ISSUE_RECONFIRMATION_SIGNATURE = (
    "public.walksafe_issue_admin_reconfirmation("
    "uuid,text,uuid,text,text,text,text,text,timestamptz,timestamptz,"
    "bigint,bigint,text,text)"
)
_ALLOWLIST_ANCHOR = """              OR (p_action = 'report.original.grant'
"""
_LOST_DEVICE_ALLOWLIST = """              OR (p_action = 'device.report_lost'
                AND p_method = 'POST'
                AND p_path ~
                  '^/admin/security/devices/[A-Za-z0-9][A-Za-z0-9._:-]{7,127}/report-lost$')
"""


def _replace_issue_allowlist(old: str, new: str) -> None:
    # Rewrite only the exact allowlist fragment of the installed function so
    # every other byte of the predecessor definition is preserved.
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
          -- The fragment must occur exactly once in the installed definition.
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


_REVIEW_V4_FUNCTION = """
        CREATE FUNCTION public.walksafe_append_report_review_decision_v4(
          p_decision_id uuid, p_report_id uuid, p_content_revision bigint,
          p_expected_review_revision bigint,
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
          v_latest_revision bigint;
        BEGIN
          IF p_expected_review_revision IS NULL
            OR p_expected_review_revision < 0
            OR pg_catalog.jsonb_typeof(v_body) <> 'object'
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
                 'evidence_grant_id', 'expected_review_revision'
               )
            )
            OR pg_catalog.jsonb_typeof(v_body -> 'decision_id') <> 'string'
            OR pg_catalog.jsonb_typeof(v_body -> 'decision') <> 'string'
            OR pg_catalog.jsonb_typeof(v_body -> 'reason') <> 'string'
            OR pg_catalog.jsonb_typeof(v_body -> 'location_reviewed') <> 'boolean'
            OR pg_catalog.jsonb_typeof(v_body -> 'photo_reviewed') <> 'boolean'
            OR pg_catalog.jsonb_typeof(v_body -> 'privacy_reviewed') <> 'boolean'
            -- Like content_revision, an omitted expected_review_revision
            -- means 0 ("no decision seen"), which conflicts with any existing
            -- decision, so omission can never overwrite silently.
            OR (
              v_body ? 'expected_review_revision'
              AND (
                pg_catalog.jsonb_typeof(v_body -> 'expected_review_revision')
                  <> 'number'
                OR (v_body -> 'expected_review_revision')::text
                     !~ '^(0|[1-9][0-9]*)$'
              )
            )
            OR COALESCE((v_body ->> 'expected_review_revision')::bigint, 0)
                 IS DISTINCT FROM p_expected_review_revision
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
            -- A replay must repeat the original intent, including the review
            -- revision it was decided against (revision = expected + 1).
            IF v_existing_decision.report_id IS DISTINCT FROM p_report_id
              OR v_existing_decision.content_revision
                   IS DISTINCT FROM p_content_revision
              OR v_existing_decision.revision
                   IS DISTINCT FROM p_expected_review_revision + 1
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
          -- Same lock order as the inner append: session first, then report.
          IF public.walksafe_lock_admin_original_access_session(
               p_admin_id, p_session_id, p_device_id, v_now,
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
          IF FOUND THEN
            -- Content races stay CONTENT_CONFLICT (inner append); a review race
            -- on unchanged content is reported separately.
            SELECT COALESCE(pg_catalog.max(decision.revision), 0)
              INTO v_latest_revision
              FROM public.report_review_decisions AS decision
             WHERE decision.report_id = p_report_id;
            IF v_latest_revision IS DISTINCT FROM p_expected_review_revision THEN
              RETURN QUERY SELECT 'REVIEW_REVISION_CONFLICT'::text, NULL::uuid,
                                  v_latest_revision;
              RETURN;
            END IF;
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


_DELIVERY_V4_FUNCTION = """
        CREATE FUNCTION public.walksafe_append_report_delivery_event_v4(
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
          -- A follow-up observation continues the submission it names: it is
          -- bound to that submission's review decision and package, not to
          -- the latest review or content.
          v_follow_up := v_current_revision > 0
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
    _replace_issue_allowlist(
        _ALLOWLIST_ANCHOR,
        _LOST_DEVICE_ALLOWLIST + _ALLOWLIST_ANCHOR,
    )
    op.execute(_REVIEW_V4_FUNCTION)
    op.execute(_DELIVERY_V4_FUNCTION)
    for signature in (_REVIEW_V4_SIGNATURE, _DELIVERY_V4_SIGNATURE):
        op.execute(f"ALTER FUNCTION {signature} OWNER TO {_OWNER_ROLE}")
        op.execute(
            f"REVOKE ALL ON FUNCTION {signature} FROM PUBLIC, {_RUNTIME_ROLE}"
        )
        op.execute(f"GRANT EXECUTE ON FUNCTION {signature} TO {_RUNTIME_ROLE}")


def downgrade() -> None:
    op.execute(f"DROP FUNCTION {_DELIVERY_V4_SIGNATURE}")
    op.execute(f"DROP FUNCTION {_REVIEW_V4_SIGNATURE}")
    _replace_issue_allowlist(
        _LOST_DEVICE_ALLOWLIST + _ALLOWLIST_ANCHOR,
        _ALLOWLIST_ANCHOR,
    )
