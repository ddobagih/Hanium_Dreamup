"""reject cycles in the active report-review append function

Revision ID: 202609240001
Revises: 202609210001
Create Date: 2026-09-24
"""

from __future__ import annotations

from alembic import op


revision = "202609240001"
down_revision = "202609210001"
branch_labels = None
depends_on = None


_CYCLE_GUARD = """
          IF p_decision = 'DUPLICATE' THEN
            -- A later statement must see the preceding duplicate transaction's commit.
            IF pg_catalog.current_setting('transaction_isolation') <> 'read committed' THEN
              RETURN QUERY SELECT 'DUPLICATE_ISOLATION_UNSUPPORTED'::text,
                                  NULL::uuid, NULL::bigint;
              RETURN;
            END IF;
            PERFORM pg_catalog.pg_advisory_xact_lock(
              pg_catalog.hashtextextended('walksafe-report-duplicate-cycle-v1', 0)
            );
            WITH RECURSIVE chain(report_id) AS (
              SELECT p_duplicate_of_report_id
              UNION
              SELECT latest.duplicate_of_report_id
                FROM chain
                JOIN LATERAL (
                  SELECT decision.duplicate_of_report_id
                    FROM public.report_review_decisions AS decision
                   WHERE decision.report_id = chain.report_id
                   ORDER BY decision.revision DESC
                   LIMIT 1
                ) AS latest ON latest.duplicate_of_report_id IS NOT NULL
            )
            SELECT EXISTS (
              SELECT 1 FROM chain WHERE chain.report_id = p_report_id
            ) INTO duplicate_cycle;
            IF duplicate_cycle THEN
              RETURN QUERY SELECT 'DUPLICATE_CYCLE'::text,
                                  NULL::uuid, NULL::bigint;
              RETURN;
            END IF;
          END IF;
"""


_REVIEW_FUNCTION_SQL = """
        CREATE OR REPLACE FUNCTION public.walksafe_append_report_review_decision(
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
          duplicate_cycle boolean;
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
          -- CYCLE_GUARD
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


def upgrade() -> None:
    op.execute(_REVIEW_FUNCTION_SQL.replace("          -- CYCLE_GUARD", _CYCLE_GUARD))


def downgrade() -> None:
    op.execute(
        _REVIEW_FUNCTION_SQL.replace("          duplicate_cycle boolean;\n", "").replace(
            "          -- CYCLE_GUARD\n", ""
        )
    )
