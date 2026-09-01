"""add isolated report restore reapply receipts and worker role

Revision ID: 202608300003
Revises: 202608300002
Create Date: 2026-08-30
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608300003"
down_revision = "202608300002"
branch_labels = None
depends_on = None


_WORKER_ROLE = "walksafe_report_restore_worker"
_AUTHORIZER_ROLE = "walksafe_report_restore_authorizer"


def _create_worker_role() -> None:
    op.execute(
        f"""
        DO $$
        DECLARE role_record record;
        BEGIN
          SELECT rolsuper, rolcreaterole, rolcreatedb, rolcanlogin,
                 rolinherit, rolreplication, rolbypassrls, rolconfig
            INTO role_record
            FROM pg_roles
           WHERE rolname = '{_WORKER_ROLE}';
          IF NOT FOUND THEN
            CREATE ROLE {_WORKER_ROLE}
              NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
              NOINHERIT NOREPLICATION NOBYPASSRLS;
          ELSIF role_record.rolsuper OR role_record.rolcreaterole
             OR role_record.rolcreatedb OR role_record.rolcanlogin
             OR role_record.rolinherit
             OR role_record.rolreplication OR role_record.rolbypassrls
             OR role_record.rolconfig IS NOT NULL THEN
            RAISE EXCEPTION '{_WORKER_ROLE} has unsafe role attributes';
          END IF;
          IF EXISTS (
              SELECT 1
              FROM pg_auth_members AS membership
              JOIN pg_roles AS member_role
                ON member_role.oid = membership.member
              WHERE member_role.rolname = '{_WORKER_ROLE}'
          ) THEN
            RAISE EXCEPTION '{_WORKER_ROLE} must not inherit another role';
          END IF;
          IF EXISTS (
              SELECT 1 FROM pg_class AS object
              JOIN pg_roles AS owner ON owner.oid = object.relowner
              WHERE owner.rolname = '{_WORKER_ROLE}'
          ) OR EXISTS (
              SELECT 1 FROM pg_type AS object
              JOIN pg_roles AS owner ON owner.oid = object.typowner
              WHERE owner.rolname = '{_WORKER_ROLE}'
          ) OR EXISTS (
              SELECT 1 FROM pg_proc AS object
              JOIN pg_roles AS owner ON owner.oid = object.proowner
              WHERE owner.rolname = '{_WORKER_ROLE}'
          ) OR EXISTS (
              SELECT 1 FROM pg_namespace AS object
              JOIN pg_roles AS owner ON owner.oid = object.nspowner
              WHERE owner.rolname = '{_WORKER_ROLE}'
          ) OR EXISTS (
              SELECT 1 FROM pg_database AS object
              JOIN pg_roles AS owner ON owner.oid = object.datdba
              WHERE owner.rolname = '{_WORKER_ROLE}'
          ) THEN
            RAISE EXCEPTION '{_WORKER_ROLE} must not own database objects';
          END IF;
        END
        $$
        """
    )


def _create_authorizer_role() -> None:
    op.execute(
        f"""
        DO $$
        DECLARE role_record record;
        BEGIN
          SELECT rolsuper, rolcreaterole, rolcreatedb, rolcanlogin,
                 rolinherit, rolreplication, rolbypassrls, rolconfig
            INTO role_record
            FROM pg_roles
           WHERE rolname = '{_AUTHORIZER_ROLE}';
          IF NOT FOUND THEN
            CREATE ROLE {_AUTHORIZER_ROLE}
              NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
              NOINHERIT NOREPLICATION NOBYPASSRLS;
          ELSIF role_record.rolsuper OR role_record.rolcreaterole
             OR role_record.rolcreatedb OR role_record.rolcanlogin
             OR role_record.rolinherit
             OR role_record.rolreplication OR role_record.rolbypassrls
             OR role_record.rolconfig IS NOT NULL THEN
            RAISE EXCEPTION '{_AUTHORIZER_ROLE} has unsafe role attributes';
          END IF;
          IF pg_has_role('{_WORKER_ROLE}', '{_AUTHORIZER_ROLE}', 'MEMBER')
             OR pg_has_role('{_AUTHORIZER_ROLE}', '{_WORKER_ROLE}', 'MEMBER') THEN
            RAISE EXCEPTION 'restore worker and authorizer roles must be separate';
          END IF;
          IF EXISTS (
              SELECT 1
              FROM pg_auth_members AS membership
              JOIN pg_roles AS member_role
                ON member_role.oid = membership.member
              WHERE member_role.rolname = '{_AUTHORIZER_ROLE}'
          ) THEN
            RAISE EXCEPTION '{_AUTHORIZER_ROLE} must not inherit another role';
          END IF;
          IF EXISTS (
              SELECT 1 FROM pg_class AS object
              JOIN pg_roles AS owner ON owner.oid = object.relowner
              WHERE owner.rolname = '{_AUTHORIZER_ROLE}'
          ) OR EXISTS (
              SELECT 1 FROM pg_type AS object
              JOIN pg_roles AS owner ON owner.oid = object.typowner
              WHERE owner.rolname = '{_AUTHORIZER_ROLE}'
          ) OR EXISTS (
              SELECT 1 FROM pg_proc AS object
              JOIN pg_roles AS owner ON owner.oid = object.proowner
              WHERE owner.rolname = '{_AUTHORIZER_ROLE}'
          ) OR EXISTS (
              SELECT 1 FROM pg_namespace AS object
              JOIN pg_roles AS owner ON owner.oid = object.nspowner
              WHERE owner.rolname = '{_AUTHORIZER_ROLE}'
          ) OR EXISTS (
              SELECT 1 FROM pg_database AS object
              JOIN pg_roles AS owner ON owner.oid = object.datdba
              WHERE owner.rolname = '{_AUTHORIZER_ROLE}'
          ) THEN
            RAISE EXCEPTION '{_AUTHORIZER_ROLE} must not own database objects';
          END IF;
        END
        $$
        """
    )


def _install_content_revision_guard() -> None:
    op.execute(
        f"""
        CREATE OR REPLACE FUNCTION public.walksafe_report_content_revision_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF TG_OP = 'DELETE' AND (
                pg_has_role(session_user, 'walksafe_account_deletion_worker', 'USAGE')
                OR pg_has_role(session_user, 'walksafe_report_deletion_worker', 'USAGE')
                OR pg_has_role(session_user, '{_WORKER_ROLE}', 'MEMBER')
            ) THEN
                RETURN NULL;
            END IF;
            RAISE EXCEPTION 'report content revisions are append-only';
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_report_content_revision_guard() "
        f"FROM PUBLIC, walksafe_backend_runtime, walksafe_report_deletion_worker, {_WORKER_ROLE}"
    )


def _install_report_delete_guard(*, include_restore_worker: bool) -> None:
    restore_branch = (
        f"""
            IF pg_has_role(session_user, '{_WORKER_ROLE}', 'MEMBER') THEN
                IF NOT EXISTS (
                    SELECT 1
                    FROM public.report_restore_reapply_effects AS effect
                    WHERE effect.plan_sha256 = current_setting(
                              'walksafe.report_restore_plan_sha256', true
                          )
                      AND effect.report_id = OLD.id
                      AND effect.privacy_subject_hmac = OLD.privacy_subject_hmac
                      AND effect.account_generation = OLD.account_generation
                      AND effect.result = 'DELETED'
                ) THEN
                    RAISE EXCEPTION 'report restore deletion requires its durable effect';
                END IF;
            ELSIF pg_has_role(
        """
        if include_restore_worker
        else """
            IF pg_has_role(
        """
    )
    op.execute(
        f"""
        CREATE OR REPLACE FUNCTION public.walksafe_report_deletion_report_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            PERFORM hold.report_id
            FROM public.report_deletion_legal_holds AS hold
            WHERE hold.report_id = OLD.id
            FOR UPDATE;
            IF EXISTS (
                SELECT 1
                FROM public.report_deletion_legal_holds AS hold
                WHERE hold.report_id = OLD.id
                  AND hold.expires_at > clock_timestamp()
            ) THEN
                RAISE EXCEPTION 'report deletion is blocked by an active legal hold';
            END IF;
{restore_branch}
                session_user, 'walksafe_report_deletion_worker', 'USAGE'
            ) AND NOT EXISTS (
                SELECT 1
                FROM public.report_deletion_tombstones AS tombstone
                WHERE tombstone.report_id = OLD.id
                  AND tombstone.privacy_subject_hmac = OLD.privacy_subject_hmac
                  AND tombstone.account_generation = OLD.account_generation
            ) THEN
                RAISE EXCEPTION 'report deletion requires its durable effect';
            END IF;
            RETURN OLD;
        END
        $guard$
        """
    )
    revoke_roles = (
        f"PUBLIC, walksafe_backend_runtime, walksafe_report_deletion_worker, {_WORKER_ROLE}"
        if include_restore_worker
        else "PUBLIC, walksafe_backend_runtime, walksafe_report_deletion_worker"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_report_deletion_report_guard() "
        f"FROM {revoke_roles}"
    )


def _install_authorized_executor_functions() -> None:
    op.execute(
        f"""
        CREATE FUNCTION public.walksafe_execute_report_restore_action(
          p_plan_sha256 text,
          p_report_id uuid
        ) RETURNS text
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, public
        AS $function$
        DECLARE
          authorized_action public.report_restore_reapply_authorized_actions%ROWTYPE;
          expected_action_count bigint;
          deleted_count bigint;
        BEGIN
          IF NOT pg_has_role(session_user, '{_WORKER_ROLE}', 'MEMBER')
             OR pg_has_role(session_user, '{_AUTHORIZER_ROLE}', 'MEMBER') THEN
            RAISE EXCEPTION 'restore action caller is unauthorized'
              USING ERRCODE = '42501';
          END IF;
          IF p_plan_sha256 !~ '^[0-9a-f]{{64}}$' OR p_report_id IS NULL THEN
            RAISE EXCEPTION 'restore action input is invalid'
              USING ERRCODE = '22023';
          END IF;

          SELECT approved_plan.action_count
            INTO STRICT expected_action_count
            FROM public.report_restore_reapply_authorizations AS approved_plan
           WHERE approved_plan.plan_sha256 = p_plan_sha256
           FOR UPDATE;
          IF (
            SELECT count(*)
            FROM public.report_restore_reapply_authorized_actions AS action
            WHERE action.plan_sha256 = p_plan_sha256
          ) <> expected_action_count THEN
            RAISE EXCEPTION 'restore authorization is incomplete'
              USING ERRCODE = '23514';
          END IF;
          SELECT action.*
            INTO STRICT authorized_action
            FROM public.report_restore_reapply_authorized_actions AS action
           WHERE action.plan_sha256 = p_plan_sha256
             AND action.report_id = p_report_id
           FOR UPDATE;
          IF EXISTS (
            SELECT 1 FROM public.report_restore_reapply_effects AS effect
            WHERE effect.plan_sha256 = p_plan_sha256
              AND effect.report_id = p_report_id
          ) THEN
            RAISE EXCEPTION 'restore action was already consumed'
              USING ERRCODE = '23505';
          END IF;

          PERFORM hold.report_id
          FROM public.report_deletion_legal_holds AS hold
          WHERE hold.report_id = p_report_id
          FOR UPDATE;
          IF EXISTS (
            SELECT 1 FROM public.report_deletion_legal_holds AS hold
            WHERE hold.report_id = p_report_id
              AND hold.expires_at > clock_timestamp()
          ) THEN
            RAISE EXCEPTION 'restore action is blocked by an active legal hold'
              USING ERRCODE = '23514';
          END IF;
          IF authorized_action.report_row_present THEN
            PERFORM report.id
            FROM public.reports AS report
            WHERE report.id = authorized_action.report_id
              AND report.privacy_subject_hmac = authorized_action.privacy_subject_hmac
              AND report.account_generation = authorized_action.account_generation
            FOR UPDATE;
            IF NOT FOUND THEN
              RAISE EXCEPTION 'authorized restore report binding changed'
                USING ERRCODE = '40001';
            END IF;
          ELSIF EXISTS (
            SELECT 1 FROM public.reports AS report
            WHERE report.id = authorized_action.report_id
          ) THEN
            RAISE EXCEPTION 'authorized absent restore report reappeared'
              USING ERRCODE = '40001';
          END IF;

          INSERT INTO public.report_restore_reapply_effects (
            plan_sha256, report_id, request_id, tombstone_id,
            privacy_subject_hmac, account_generation, entry_sha256,
            result, bound_artifact_count
          ) VALUES (
            authorized_action.plan_sha256,
            authorized_action.report_id,
            authorized_action.request_id,
            authorized_action.tombstone_id,
            authorized_action.privacy_subject_hmac,
            authorized_action.account_generation,
            authorized_action.entry_sha256,
            'DELETED',
            authorized_action.bound_artifact_count
          );
          PERFORM set_config(
            'walksafe.report_restore_plan_sha256', p_plan_sha256, true
          );
          IF authorized_action.report_row_present THEN
            DELETE FROM public.reports AS report
            WHERE report.id = authorized_action.report_id
              AND report.privacy_subject_hmac = authorized_action.privacy_subject_hmac
              AND report.account_generation = authorized_action.account_generation;
            GET DIAGNOSTICS deleted_count = ROW_COUNT;
            IF deleted_count <> 1 THEN
              RAISE EXCEPTION 'authorized restore report changed during deletion'
                USING ERRCODE = '40001';
            END IF;
          END IF;
          RETURN 'DELETED';
        END
        $function$
        """
    )
    op.execute(
        f"""
        CREATE FUNCTION public.walksafe_finish_report_restore_reapply(
          p_plan_sha256 text,
          p_receipt_sha256 text,
          p_receipt_bytes bytea,
          p_applied_at timestamptz
        ) RETURNS void
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, public
        AS $function$
        DECLARE
          approved_plan public.report_restore_reapply_authorizations%ROWTYPE;
          receipt_document jsonb;
          canonical_payload text;
          canonical_receipt text;
          canonical_results text;
          expected_receipt_sha256 text;
          receipt_result jsonb;
        BEGIN
          IF NOT pg_has_role(session_user, '{_WORKER_ROLE}', 'MEMBER')
             OR pg_has_role(session_user, '{_AUTHORIZER_ROLE}', 'MEMBER') THEN
            RAISE EXCEPTION 'restore receipt caller is unauthorized'
              USING ERRCODE = '42501';
          END IF;
          IF p_plan_sha256 !~ '^[0-9a-f]{{64}}$'
             OR p_receipt_sha256 !~ '^[0-9a-f]{{64}}$'
             OR p_receipt_bytes IS NULL
             OR octet_length(p_receipt_bytes) NOT BETWEEN 2 AND 67108864
             OR p_applied_at IS NULL THEN
            RAISE EXCEPTION 'restore receipt input is invalid'
              USING ERRCODE = '22023';
          END IF;
          SELECT value.* INTO STRICT approved_plan
          FROM public.report_restore_reapply_authorizations AS value
          WHERE value.plan_sha256 = p_plan_sha256
          FOR UPDATE;
          BEGIN
            receipt_document := convert_from(p_receipt_bytes, 'UTF8')::jsonb;
          EXCEPTION WHEN OTHERS THEN
            RAISE EXCEPTION 'restore receipt document is invalid'
              USING ERRCODE = '22023';
          END;
          IF jsonb_typeof(receipt_document) IS DISTINCT FROM 'object' THEN
            RAISE EXCEPTION 'restore receipt document is invalid'
              USING ERRCODE = '23514';
          END IF;
          IF (
            SELECT array_agg(key ORDER BY key)
            FROM jsonb_object_keys(receipt_document) AS field(key)
          ) IS DISTINCT FROM ARRAY[
            'applied_at', 'data_boundary_id', 'plan_sha256',
            'receipt_sha256', 'restore_run_id', 'results',
            'schema_version', 'target_identity_sha256'
          ]::text[] THEN
            RAISE EXCEPTION 'restore receipt fields are invalid'
              USING ERRCODE = '23514';
          END IF;
          IF jsonb_typeof(receipt_document->'applied_at')
                IS DISTINCT FROM 'string'
             OR jsonb_typeof(receipt_document->'data_boundary_id')
                IS DISTINCT FROM 'string'
             OR jsonb_typeof(receipt_document->'plan_sha256')
                IS DISTINCT FROM 'string'
             OR jsonb_typeof(receipt_document->'receipt_sha256')
                IS DISTINCT FROM 'string'
             OR jsonb_typeof(receipt_document->'restore_run_id')
                IS DISTINCT FROM 'string'
             OR jsonb_typeof(receipt_document->'schema_version')
                IS DISTINCT FROM 'string'
             OR jsonb_typeof(receipt_document->'target_identity_sha256')
                IS DISTINCT FROM 'string' THEN
            RAISE EXCEPTION 'restore receipt string fields are invalid'
              USING ERRCODE = '23514';
          END IF;
          IF jsonb_typeof(receipt_document->'results')
                IS DISTINCT FROM 'array' THEN
            RAISE EXCEPTION 'restore receipt results are invalid'
              USING ERRCODE = '23514';
          END IF;
          FOR receipt_result IN
            SELECT item.value
            FROM jsonb_array_elements(receipt_document->'results') AS item(value)
          LOOP
            IF jsonb_typeof(receipt_result) IS DISTINCT FROM 'object' THEN
              RAISE EXCEPTION 'restore receipt result is invalid'
                USING ERRCODE = '23514';
            END IF;
            IF (
              SELECT array_agg(key ORDER BY key)
              FROM jsonb_object_keys(receipt_result) AS field(key)
            ) IS DISTINCT FROM
                ARRAY['entry_sha256', 'report_id', 'result']::text[] THEN
              RAISE EXCEPTION 'restore receipt result fields are invalid'
                USING ERRCODE = '23514';
            END IF;
            IF jsonb_typeof(receipt_result->'entry_sha256')
                  IS DISTINCT FROM 'string'
               OR jsonb_typeof(receipt_result->'report_id')
                  IS DISTINCT FROM 'string'
               OR jsonb_typeof(receipt_result->'result')
                  IS DISTINCT FROM 'string' THEN
              RAISE EXCEPTION 'restore receipt result values are invalid'
                USING ERRCODE = '23514';
            END IF;
          END LOOP;
          IF receipt_document->>'applied_at'
                !~ '^[0-9]{{4}}-[0-9]{{2}}-[0-9]{{2}}T[0-9]{{2}}:'
                   '[0-9]{{2}}:[0-9]{{2}}(?:\\.[0-9]{{1,6}})?Z$' THEN
            RAISE EXCEPTION 'restore receipt timestamp is invalid'
              USING ERRCODE = '23514';
          END IF;
          SELECT coalesce(
                   string_agg(
                     '{{"entry_sha256":'
                     || pg_catalog.to_json(item.value->>'entry_sha256')::text
                     || ',"report_id":'
                     || pg_catalog.to_json(item.value->>'report_id')::text
                     || ',"result":'
                     || pg_catalog.to_json(item.value->>'result')::text
                     || '}}',
                     ',' ORDER BY item.ordinality
                   ),
                   ''
                 )
            INTO canonical_results
            FROM jsonb_array_elements(receipt_document->'results')
                 WITH ORDINALITY AS item(value, ordinality);
          canonical_payload :=
            '{{"applied_at":'
            || pg_catalog.to_json(receipt_document->>'applied_at')::text
            || ',"data_boundary_id":'
            || pg_catalog.to_json(receipt_document->>'data_boundary_id')::text
            || ',"plan_sha256":'
            || pg_catalog.to_json(receipt_document->>'plan_sha256')::text
            || ',"restore_run_id":'
            || pg_catalog.to_json(receipt_document->>'restore_run_id')::text
            || ',"results":[' || canonical_results || ']'
            || ',"schema_version":'
            || pg_catalog.to_json(receipt_document->>'schema_version')::text
            || ',"target_identity_sha256":'
            || pg_catalog.to_json(
                 receipt_document->>'target_identity_sha256'
               )::text
            || '}}';
          canonical_receipt :=
            '{{"applied_at":'
            || pg_catalog.to_json(receipt_document->>'applied_at')::text
            || ',"data_boundary_id":'
            || pg_catalog.to_json(receipt_document->>'data_boundary_id')::text
            || ',"plan_sha256":'
            || pg_catalog.to_json(receipt_document->>'plan_sha256')::text
            || ',"receipt_sha256":'
            || pg_catalog.to_json(receipt_document->>'receipt_sha256')::text
            || ',"restore_run_id":'
            || pg_catalog.to_json(receipt_document->>'restore_run_id')::text
            || ',"results":[' || canonical_results || ']'
            || ',"schema_version":'
            || pg_catalog.to_json(receipt_document->>'schema_version')::text
            || ',"target_identity_sha256":'
            || pg_catalog.to_json(
                 receipt_document->>'target_identity_sha256'
               )::text
            || '}}';
          expected_receipt_sha256 := encode(
            pg_catalog.sha256(
              pg_catalog.convert_to(
                'walksafe/report-restore-reapply-receipt/v1', 'UTF8'
              )
              || pg_catalog.decode('00', 'hex')
              || pg_catalog.convert_to(canonical_payload, 'UTF8')
            ),
            'hex'
          );
          IF pg_catalog.convert_to(canonical_receipt, 'UTF8')
                IS DISTINCT FROM p_receipt_bytes
             OR receipt_document->>'schema_version'
                IS DISTINCT FROM 'walksafe.report-restore-reapply-receipt.v1'
             OR receipt_document->>'plan_sha256'
                IS DISTINCT FROM approved_plan.plan_sha256
             OR receipt_document->>'restore_run_id'
                IS DISTINCT FROM approved_plan.restore_run_id::text
             OR receipt_document->>'data_boundary_id'
                IS DISTINCT FROM approved_plan.data_boundary_id
             OR receipt_document->>'target_identity_sha256'
                IS DISTINCT FROM approved_plan.target_identity_sha256
             OR receipt_document->>'receipt_sha256'
                IS DISTINCT FROM p_receipt_sha256
             OR receipt_document->>'receipt_sha256'
                IS DISTINCT FROM expected_receipt_sha256
             OR (receipt_document->>'applied_at')::timestamptz
                IS DISTINCT FROM p_applied_at
             OR jsonb_typeof(receipt_document->'results') <> 'array'
             OR jsonb_array_length(receipt_document->'results')
                <> approved_plan.action_count
             OR (
               SELECT count(*)
               FROM public.report_restore_reapply_authorized_actions AS action
               WHERE action.plan_sha256 = approved_plan.plan_sha256
             ) <> approved_plan.action_count
             OR (
               SELECT count(*)
               FROM public.report_restore_reapply_effects AS effect
               WHERE effect.plan_sha256 = approved_plan.plan_sha256
             ) <> approved_plan.action_count
             OR EXISTS (
               SELECT 1
               FROM (
                 SELECT
                   action.report_id,
                   action.entry_sha256 AS authorized_entry_sha256,
                   effect.entry_sha256 AS effect_entry_sha256,
                   effect.result,
                   row_number() OVER (
                     ORDER BY action.report_id
                   ) AS ordinality
                 FROM public.report_restore_reapply_authorized_actions AS action
                 JOIN public.report_restore_reapply_effects AS effect
                   ON effect.plan_sha256 = action.plan_sha256
                  AND effect.report_id = action.report_id
                 WHERE action.plan_sha256 = approved_plan.plan_sha256
               ) AS expected
               FULL OUTER JOIN jsonb_array_elements(
                 receipt_document->'results'
               ) WITH ORDINALITY AS item(value, ordinality)
                 ON item.ordinality = expected.ordinality
               WHERE expected.report_id IS NULL
                  OR item.value IS NULL
                  OR expected.authorized_entry_sha256
                     IS DISTINCT FROM expected.effect_entry_sha256
                  OR item.value->>'report_id'
                     IS DISTINCT FROM expected.report_id::text
                  OR item.value->>'entry_sha256'
                     IS DISTINCT FROM expected.authorized_entry_sha256
                  OR item.value->>'result'
                     IS DISTINCT FROM expected.result
             ) THEN
            RAISE EXCEPTION 'restore receipt is not bound to its authorization'
              USING ERRCODE = '23514';
          END IF;

          INSERT INTO public.report_restore_reapply_receipts (
            plan_sha256, restore_run_id, data_boundary_id,
            target_identity_sha256, source_fence_sha256,
            trusted_head_sha256, action_count, receipt_sha256,
            receipt_bytes, applied_at
          ) VALUES (
            approved_plan.plan_sha256,
            approved_plan.restore_run_id,
            approved_plan.data_boundary_id,
            approved_plan.target_identity_sha256,
            approved_plan.source_fence_sha256,
            approved_plan.trusted_head_sha256,
            approved_plan.action_count,
            p_receipt_sha256,
            p_receipt_bytes,
            p_applied_at
          );
        END
        $function$
        """
    )
    op.execute(
        f"""
        CREATE FUNCTION public.walksafe_record_report_restore_postcheck(
          p_plan_sha256 text,
          p_outcome text,
          p_failure_code text
        ) RETURNS void
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, public
        AS $function$
        DECLARE
          expected_action_count bigint;
          existing_outcome text;
          existing_failure_code text;
        BEGIN
          IF NOT pg_has_role(session_user, '{_WORKER_ROLE}', 'MEMBER')
             OR pg_has_role(session_user, '{_AUTHORIZER_ROLE}', 'MEMBER') THEN
            RAISE EXCEPTION 'restore postcheck caller is unauthorized'
              USING ERRCODE = '42501';
          END IF;
          IF p_plan_sha256 !~ '^[0-9a-f]{{64}}$'
             OR p_outcome NOT IN ('PASSED', 'FAILED')
             OR (p_outcome = 'PASSED' AND p_failure_code IS NOT NULL)
             OR (p_outcome = 'FAILED' AND (
               p_failure_code IS NULL
               OR p_failure_code !~ '^restore_[a-z0-9_]{{1,119}}$'
             )) THEN
            RAISE EXCEPTION 'restore postcheck input is invalid'
              USING ERRCODE = '22023';
          END IF;

          SELECT receipt.action_count
            INTO STRICT expected_action_count
            FROM public.report_restore_reapply_receipts AS receipt
           WHERE receipt.plan_sha256 = p_plan_sha256
           FOR UPDATE;
          IF (
            SELECT count(*)
            FROM public.report_restore_reapply_authorized_actions AS action
            WHERE action.plan_sha256 = p_plan_sha256
          ) <> expected_action_count OR (
            SELECT count(*)
            FROM public.report_restore_reapply_effects AS effect
            WHERE effect.plan_sha256 = p_plan_sha256
          ) <> expected_action_count OR EXISTS (
            SELECT 1
            FROM public.report_restore_reapply_effects AS effect
            JOIN public.reports AS report ON report.id = effect.report_id
            WHERE effect.plan_sha256 = p_plan_sha256
          ) THEN
            RAISE EXCEPTION 'restore postcheck target is incomplete'
              USING ERRCODE = '23514';
          END IF;

          SELECT postcheck.outcome, postcheck.failure_code
            INTO existing_outcome, existing_failure_code
            FROM public.report_restore_reapply_postchecks AS postcheck
           WHERE postcheck.plan_sha256 = p_plan_sha256
           FOR UPDATE;
          IF FOUND THEN
            IF existing_outcome = p_outcome
               AND existing_failure_code IS NOT DISTINCT FROM p_failure_code THEN
              RETURN;
            END IF;
            RAISE EXCEPTION 'restore postcheck outcome is immutable'
              USING ERRCODE = '55000';
          END IF;

          INSERT INTO public.report_restore_reapply_postchecks (
            plan_sha256, outcome, failure_code
          ) VALUES (
            p_plan_sha256, p_outcome, p_failure_code
          );
        END
        $function$
        """
    )


def upgrade() -> None:
    _create_worker_role()
    _create_authorizer_role()
    op.create_table(
        "report_restore_reapply_authorizations",
        sa.Column("plan_sha256", sa.String(length=64), nullable=False),
        sa.Column("restore_run_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("data_boundary_id", sa.String(length=128), nullable=False),
        sa.Column("target_identity_sha256", sa.String(length=64), nullable=False),
        sa.Column("source_fence_sha256", sa.String(length=64), nullable=False),
        sa.Column("trusted_head_sha256", sa.String(length=64), nullable=False),
        sa.Column("inventory_sha256", sa.String(length=64), nullable=False),
        sa.Column("action_count", sa.BigInteger(), nullable=False),
        sa.Column(
            "authorized_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "plan_sha256 ~ '^[0-9a-f]{64}$' "
            "AND target_identity_sha256 ~ '^[0-9a-f]{64}$' "
            "AND source_fence_sha256 ~ '^[0-9a-f]{64}$' "
            "AND trusted_head_sha256 ~ '^[0-9a-f]{64}$' "
            "AND inventory_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_restore_reapply_authorizations_hashes",
        ),
        sa.CheckConstraint(
            "data_boundary_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$'",
            name="ck_report_restore_reapply_authorizations_boundary",
        ),
        sa.CheckConstraint(
            "action_count BETWEEN 1 AND 200000",
            name="ck_report_restore_reapply_authorizations_action_count",
        ),
        sa.PrimaryKeyConstraint("plan_sha256"),
    )
    op.create_table(
        "report_restore_reapply_authorized_actions",
        sa.Column("plan_sha256", sa.String(length=64), nullable=False),
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("request_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("tombstone_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("privacy_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("account_generation", sa.BigInteger(), nullable=False),
        sa.Column("request_status_version", sa.BigInteger(), nullable=False),
        sa.Column("entry_sha256", sa.String(length=64), nullable=False),
        sa.Column("report_row_present", sa.Boolean(), nullable=False),
        sa.Column("bound_artifact_count", sa.BigInteger(), nullable=False),
        sa.Column(
            "authorized_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "plan_sha256 ~ '^[0-9a-f]{64}$' "
            "AND privacy_subject_hmac ~ '^[0-9a-f]{64}$' "
            "AND entry_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_restore_reapply_authorized_actions_hashes",
        ),
        sa.CheckConstraint(
            "account_generation >= 1 AND request_status_version >= 1 "
            "AND bound_artifact_count = 1",
            name="ck_report_restore_reapply_authorized_actions_counts",
        ),
        sa.ForeignKeyConstraint(
            ["plan_sha256"],
            ["report_restore_reapply_authorizations.plan_sha256"],
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("plan_sha256", "report_id"),
        sa.UniqueConstraint(
            "plan_sha256",
            "request_id",
            name="uq_report_restore_reapply_authorized_actions_request",
        ),
        sa.UniqueConstraint(
            "plan_sha256",
            "tombstone_id",
            name="uq_report_restore_reapply_authorized_actions_tombstone",
        ),
    )
    op.create_table(
        "report_restore_reapply_receipts",
        sa.Column("plan_sha256", sa.String(length=64), nullable=False),
        sa.Column("restore_run_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("data_boundary_id", sa.String(length=128), nullable=False),
        sa.Column("target_identity_sha256", sa.String(length=64), nullable=False),
        sa.Column("source_fence_sha256", sa.String(length=64), nullable=False),
        sa.Column("trusted_head_sha256", sa.String(length=64), nullable=False),
        sa.Column("action_count", sa.BigInteger(), nullable=False),
        sa.Column("receipt_sha256", sa.String(length=64), nullable=False),
        sa.Column("receipt_bytes", sa.LargeBinary(), nullable=False),
        sa.Column("applied_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "plan_sha256 ~ '^[0-9a-f]{64}$' "
            "AND target_identity_sha256 ~ '^[0-9a-f]{64}$' "
            "AND source_fence_sha256 ~ '^[0-9a-f]{64}$' "
            "AND trusted_head_sha256 ~ '^[0-9a-f]{64}$' "
            "AND receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_restore_reapply_receipts_hashes",
        ),
        sa.CheckConstraint(
            "data_boundary_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$'",
            name="ck_report_restore_reapply_receipts_boundary",
        ),
        sa.CheckConstraint(
            "action_count BETWEEN 0 AND 200000",
            name="ck_report_restore_reapply_receipts_action_count",
        ),
        sa.CheckConstraint(
            "octet_length(receipt_bytes) BETWEEN 2 AND 67108864",
            name="ck_report_restore_reapply_receipts_size",
        ),
        sa.PrimaryKeyConstraint("plan_sha256"),
        sa.ForeignKeyConstraint(
            ["plan_sha256"],
            ["report_restore_reapply_authorizations.plan_sha256"],
            ondelete="RESTRICT",
        ),
        sa.UniqueConstraint(
            "receipt_sha256",
            name="uq_report_restore_reapply_receipts_receipt",
        ),
    )
    op.create_table(
        "report_restore_reapply_effects",
        sa.Column("plan_sha256", sa.String(length=64), nullable=False),
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("request_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("tombstone_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("privacy_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("account_generation", sa.BigInteger(), nullable=False),
        sa.Column("entry_sha256", sa.String(length=64), nullable=False),
        sa.Column("result", sa.String(length=16), nullable=False),
        sa.Column("bound_artifact_count", sa.BigInteger(), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "plan_sha256 ~ '^[0-9a-f]{64}$' "
            "AND privacy_subject_hmac ~ '^[0-9a-f]{64}$' "
            "AND entry_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_restore_reapply_effects_hashes",
        ),
        sa.CheckConstraint(
            "account_generation >= 1 AND bound_artifact_count BETWEEN 0 AND 2",
            name="ck_report_restore_reapply_effects_counts",
        ),
        sa.CheckConstraint(
            "result IN ('DELETED', 'ALREADY_ABSENT')",
            name="ck_report_restore_reapply_effects_result",
        ),
        sa.ForeignKeyConstraint(
            ["plan_sha256"],
            ["report_restore_reapply_receipts.plan_sha256"],
            ondelete="RESTRICT",
            deferrable=True,
            initially="DEFERRED",
        ),
        sa.ForeignKeyConstraint(
            ["plan_sha256", "report_id"],
            [
                "report_restore_reapply_authorized_actions.plan_sha256",
                "report_restore_reapply_authorized_actions.report_id",
            ],
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("plan_sha256", "report_id"),
        sa.UniqueConstraint(
            "plan_sha256",
            "request_id",
            name="uq_report_restore_reapply_effects_request",
        ),
        sa.UniqueConstraint(
            "plan_sha256",
            "tombstone_id",
            name="uq_report_restore_reapply_effects_tombstone",
        ),
    )
    op.create_index(
        "ix_report_restore_reapply_effects_request_report",
        "report_restore_reapply_effects",
        ["request_id", "report_id"],
    )
    op.create_table(
        "report_restore_reapply_postchecks",
        sa.Column("plan_sha256", sa.String(length=64), nullable=False),
        sa.Column("outcome", sa.String(length=16), nullable=False),
        sa.Column("failure_code", sa.String(length=128), nullable=True),
        sa.Column(
            "checked_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "plan_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_restore_reapply_postchecks_plan_hash",
        ),
        sa.CheckConstraint(
            "(outcome = 'PASSED' AND failure_code IS NULL) OR "
            "(outcome = 'FAILED' AND "
            "failure_code ~ '^restore_[a-z0-9_]{1,119}$')",
            name="ck_report_restore_reapply_postchecks_outcome",
        ),
        sa.ForeignKeyConstraint(
            ["plan_sha256"],
            ["report_restore_reapply_receipts.plan_sha256"],
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("plan_sha256"),
    )

    for table_name in (
        "report_restore_reapply_authorizations",
        "report_restore_reapply_authorized_actions",
        "report_restore_reapply_receipts",
        "report_restore_reapply_effects",
        "report_restore_reapply_postchecks",
    ):
        op.execute(
            f"CREATE TRIGGER {table_name}_append_only "
            f"BEFORE UPDATE OR DELETE OR TRUNCATE ON public.{table_name} "
            "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
        )

    op.execute(
        """
        CREATE FUNCTION public.walksafe_report_restore_reapply_completion_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF NOT EXISTS (
                SELECT 1
                FROM public.report_restore_reapply_authorizations AS approved_plan
                WHERE approved_plan.plan_sha256 = NEW.plan_sha256
                  AND approved_plan.restore_run_id = NEW.restore_run_id
                  AND approved_plan.data_boundary_id = NEW.data_boundary_id
                  AND approved_plan.target_identity_sha256 = NEW.target_identity_sha256
                  AND approved_plan.source_fence_sha256 = NEW.source_fence_sha256
                  AND approved_plan.trusted_head_sha256 = NEW.trusted_head_sha256
                  AND approved_plan.action_count = NEW.action_count
            ) OR (
                SELECT count(*)
                FROM public.report_restore_reapply_authorized_actions AS action
                WHERE action.plan_sha256 = NEW.plan_sha256
            ) <> NEW.action_count OR (
                SELECT count(*)
                FROM public.report_restore_reapply_effects AS effect
                WHERE effect.plan_sha256 = NEW.plan_sha256
            ) <> NEW.action_count OR EXISTS (
                SELECT 1
                FROM public.report_restore_reapply_effects AS effect
                JOIN public.reports AS report ON report.id = effect.report_id
                WHERE effect.plan_sha256 = NEW.plan_sha256
            ) THEN
                RAISE EXCEPTION 'report restore reapply receipt is incomplete';
            END IF;
            RETURN NEW;
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION "
        "public.walksafe_report_restore_reapply_completion_guard() "
        f"FROM PUBLIC, walksafe_backend_runtime, {_WORKER_ROLE}"
    )
    op.execute(
        "CREATE CONSTRAINT TRIGGER report_restore_reapply_receipts_completion_guard "
        "AFTER INSERT ON public.report_restore_reapply_receipts "
        "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION "
        "public.walksafe_report_restore_reapply_completion_guard()"
    )

    _install_content_revision_guard()
    _install_report_delete_guard(include_restore_worker=True)
    _install_authorized_executor_functions()

    op.execute(
        f"REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM {_WORKER_ROLE}"
    )
    op.execute(
        f"REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM {_WORKER_ROLE}"
    )
    op.execute(
        f"REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public FROM {_WORKER_ROLE}"
    )
    op.execute(
        f"REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM {_AUTHORIZER_ROLE}"
    )
    op.execute(
        f"REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM {_AUTHORIZER_ROLE}"
    )
    op.execute(
        f"REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public FROM {_AUTHORIZER_ROLE}"
    )
    op.execute(
        f"""
        DO $$
        BEGIN
          EXECUTE format(
              'REVOKE ALL PRIVILEGES ON DATABASE %I FROM {_WORKER_ROLE}',
              current_database()
          );
          EXECUTE format(
              'REVOKE ALL PRIVILEGES ON DATABASE %I FROM {_AUTHORIZER_ROLE}',
              current_database()
          );
        END
        $$
        """
    )
    op.execute(f"GRANT USAGE ON SCHEMA public TO {_WORKER_ROLE}")
    op.execute(f"REVOKE CREATE ON SCHEMA public FROM {_WORKER_ROLE}")
    op.execute(f"GRANT USAGE ON SCHEMA public TO {_AUTHORIZER_ROLE}")
    op.execute(f"REVOKE CREATE ON SCHEMA public FROM {_AUTHORIZER_ROLE}")
    op.execute(
        "GRANT SELECT (id, privacy_subject_hmac, account_generation, image_path, "
        f"image_content_type) ON TABLE public.reports TO {_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT (report_id, storage_name, envelope_sha256, envelope_size, "
        f"content_type) ON TABLE public.report_image_objects TO {_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT (report_id, expires_at) ON TABLE "
        f"public.report_deletion_legal_holds TO {_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT ON TABLE public.report_deletion_tombstones "
        f"TO {_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT ON TABLE public.report_restore_reapply_authorizations, "
        "public.report_restore_reapply_authorized_actions, "
        "public.report_restore_reapply_receipts, "
        "public.report_restore_reapply_effects, "
        f"public.report_restore_reapply_postchecks TO {_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT, INSERT ON TABLE "
        "public.report_restore_reapply_authorizations, "
        "public.report_restore_reapply_authorized_actions "
        f"TO {_AUTHORIZER_ROLE}"
    )
    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE "
        "public.report_restore_reapply_authorizations, "
        "public.report_restore_reapply_authorized_actions, "
        "public.report_restore_reapply_receipts, "
        "public.report_restore_reapply_effects, "
        "public.report_restore_reapply_postchecks "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT SELECT ON TABLE public.report_restore_reapply_receipts, "
        "public.report_restore_reapply_effects, "
        "public.report_restore_reapply_postchecks TO walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION "
        "public.walksafe_execute_report_restore_action(text, uuid), "
        "public.walksafe_finish_report_restore_reapply(text, text, bytea, timestamptz), "
        "public.walksafe_record_report_restore_postcheck(text, text, text) "
        f"FROM PUBLIC, walksafe_backend_runtime, {_AUTHORIZER_ROLE}"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION "
        "public.walksafe_execute_report_restore_action(text, uuid), "
        "public.walksafe_finish_report_restore_reapply(text, text, bytea, timestamptz), "
        "public.walksafe_record_report_restore_postcheck(text, text, text) "
        f"TO {_WORKER_ROLE}"
    )
    op.execute(
        f"""
        DO $$
        BEGIN
          IF has_schema_privilege('{_WORKER_ROLE}', 'public', 'CREATE') THEN
            RAISE EXCEPTION '{_WORKER_ROLE} can create objects in public';
          END IF;
        END
        $$
        """
    )


def downgrade() -> None:
    op.execute(
        "LOCK TABLE public.report_restore_reapply_authorizations, "
        "public.report_restore_reapply_authorized_actions, "
        "public.report_restore_reapply_effects, "
        "public.report_restore_reapply_receipts, "
        "public.report_restore_reapply_postchecks IN ACCESS EXCLUSIVE MODE"
    )
    op.execute(
        """
        DO $$
        BEGIN
          IF EXISTS (
            SELECT 1 FROM public.report_restore_reapply_authorizations
          ) OR EXISTS (
            SELECT 1 FROM public.report_restore_reapply_authorized_actions
          ) OR EXISTS (
            SELECT 1 FROM public.report_restore_reapply_effects
          ) OR EXISTS (
            SELECT 1 FROM public.report_restore_reapply_receipts
          ) OR EXISTS (
            SELECT 1 FROM public.report_restore_reapply_postchecks
          ) THEN
            RAISE EXCEPTION
              'report restore reapply history blocks lossy downgrade'
              USING ERRCODE = '55000';
          END IF;
        END
        $$
        """
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "public.walksafe_record_report_restore_postcheck(text, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "public.walksafe_finish_report_restore_reapply(text, text, bytea, timestamptz)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "public.walksafe_execute_report_restore_action(text, uuid)"
    )
    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE public.reports, "
        "public.report_image_objects, public.report_deletion_legal_holds, "
        "public.report_deletion_tombstones, "
        "public.report_restore_reapply_authorizations, "
        "public.report_restore_reapply_authorized_actions, "
        "public.report_restore_reapply_receipts, "
        "public.report_restore_reapply_effects, "
        f"public.report_restore_reapply_postchecks FROM {_WORKER_ROLE}"
    )
    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE "
        "public.report_restore_reapply_authorizations, "
        "public.report_restore_reapply_authorized_actions "
        f"FROM {_AUTHORIZER_ROLE}"
    )
    op.execute(f"REVOKE USAGE, CREATE ON SCHEMA public FROM {_WORKER_ROLE}")
    op.execute(f"REVOKE USAGE, CREATE ON SCHEMA public FROM {_AUTHORIZER_ROLE}")
    _install_report_delete_guard(include_restore_worker=False)
    op.execute(
        """
        CREATE OR REPLACE FUNCTION public.walksafe_report_content_revision_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF TG_OP = 'DELETE' AND (
                pg_has_role(session_user, 'walksafe_account_deletion_worker', 'USAGE')
                OR pg_has_role(session_user, 'walksafe_report_deletion_worker', 'USAGE')
            ) THEN
                RETURN NULL;
            END IF;
            RAISE EXCEPTION 'report content revisions are append-only';
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_report_content_revision_guard() "
        "FROM PUBLIC, walksafe_backend_runtime, walksafe_report_deletion_worker"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS report_restore_reapply_receipts_completion_guard "
        "ON public.report_restore_reapply_receipts"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "public.walksafe_report_restore_reapply_completion_guard()"
    )
    op.drop_index(
        "ix_report_restore_reapply_effects_request_report",
        table_name="report_restore_reapply_effects",
    )
    op.drop_table("report_restore_reapply_postchecks")
    op.drop_table("report_restore_reapply_effects")
    op.drop_table("report_restore_reapply_receipts")
    op.drop_table("report_restore_reapply_authorized_actions")
    op.drop_table("report_restore_reapply_authorizations")
