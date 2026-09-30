"""Require duplicate decisions to point directly to a representative report.

Revision ID: 202609300009
Revises: 202609300008
Create Date: 2026-09-30
"""

from __future__ import annotations

import hashlib

from alembic import op
from sqlalchemy import text


revision = "202609300009"
down_revision = "202609300008"
branch_labels = None
depends_on = None


_FUNCTION = (
    "public.walksafe_append_report_review_decision("
    "uuid,uuid,bigint,text,text,text,uuid,boolean,boolean,boolean,uuid,"
    "text,uuid,text,uuid,timestamptz,text,text)"
)
_PREVIOUS_SHA256 = "1f486b97e023c7599dd1b1944d7e6090c698075a55fd3c5dd485a2a792f6b924"
_REPRESENTATIVE_SHA256 = "c2fb02f571021324c9ced411354abca78ea8a25b01590c3b68c27ffc51e6cb37"
_CYCLE_END = """            IF duplicate_cycle THEN
              RETURN QUERY SELECT 'DUPLICATE_CYCLE'::text,
                                  NULL::uuid, NULL::bigint;
              RETURN;
            END IF;
          END IF;"""
_REPRESENTATIVE_END = """            IF duplicate_cycle THEN
              RETURN QUERY SELECT 'DUPLICATE_CYCLE'::text,
                                  NULL::uuid, NULL::bigint;
              RETURN;
            END IF;
            -- The signed target ID is never rewritten: ask for the current
            -- representative instead of silently making an indirect link.
            IF EXISTS (
              SELECT 1 FROM public.report_review_decisions AS target
               WHERE target.report_id = p_duplicate_of_report_id
                 AND target.decision = 'DUPLICATE'
                 AND NOT EXISTS (
                   SELECT 1 FROM public.report_review_decisions AS newer
                    WHERE newer.report_id = target.report_id
                      AND newer.revision > target.revision
                 )
            ) THEN
              RETURN QUERY SELECT 'DUPLICATE_TARGET_NOT_REPRESENTATIVE'::text,
                                  NULL::uuid, NULL::bigint;
              RETURN;
            END IF;
            -- A report with active duplicate children must remain a root.
            -- This catches B -> C after A -> B, including concurrent writes
            -- serialized by the duplicate advisory lock above.
            IF EXISTS (
              SELECT 1 FROM public.report_review_decisions AS child
               WHERE child.duplicate_of_report_id = p_report_id
                 AND child.decision = 'DUPLICATE'
                 AND NOT EXISTS (
                   SELECT 1 FROM public.report_review_decisions AS newer
                    WHERE newer.report_id = child.report_id
                      AND newer.revision > child.revision
                 )
            ) THEN
              RETURN QUERY SELECT 'DUPLICATE_CYCLE'::text,
                                  NULL::uuid, NULL::bigint;
              RETURN;
            END IF;
          END IF;"""

_INSERT_GUARD_FUNCTION = """
    CREATE FUNCTION public.walksafe_enforce_duplicate_representative()
    RETURNS trigger AS $function$
    BEGIN
      IF NEW.decision <> 'DUPLICATE' THEN
        RETURN NEW;
      END IF;
      PERFORM pg_catalog.pg_advisory_xact_lock(
        pg_catalog.hashtextextended('walksafe-report-duplicate-cycle-v1', 0)
      );
      IF EXISTS (
        SELECT 1 FROM public.report_review_decisions AS target
         WHERE target.report_id = NEW.duplicate_of_report_id
           AND target.decision = 'DUPLICATE'
           AND NOT EXISTS (
             SELECT 1 FROM public.report_review_decisions AS newer
              WHERE newer.report_id = target.report_id
                AND newer.revision > target.revision
           )
      ) THEN
        RAISE EXCEPTION 'duplicate target is not a representative report'
          USING ERRCODE = 'P1837';
      END IF;
      IF EXISTS (
        SELECT 1 FROM public.report_review_decisions AS child
         WHERE child.duplicate_of_report_id = NEW.report_id
           AND child.decision = 'DUPLICATE'
           AND NOT EXISTS (
             SELECT 1 FROM public.report_review_decisions AS newer
              WHERE newer.report_id = child.report_id
                AND newer.revision > child.revision
           )
      ) THEN
        RAISE EXCEPTION 'report with active duplicates cannot become a duplicate'
          USING ERRCODE = 'P1838';
      END IF;
      RETURN NEW;
    END;
    $function$ LANGUAGE plpgsql SECURITY DEFINER
    SET search_path = pg_catalog, pg_temp
"""


def _definition() -> str:
    return op.get_bind().execute(
        text(f"SELECT pg_catalog.pg_get_functiondef('{_FUNCTION}'::regprocedure)")
    ).scalar_one()


def _replace_guard(old: str, new: str, expected_sha256: str) -> None:
    definition = _definition()
    if hashlib.sha256(definition.encode("utf-8")).hexdigest() != expected_sha256:
        raise RuntimeError("report review function changed outside its migration")
    if definition.count(old) != 1:
        raise RuntimeError("report review duplicate guard is not the expected version")
    op.execute(definition.replace(old, new, 1))


def upgrade() -> None:
    if op.get_bind().execute(
        text("SELECT pg_catalog.current_setting('transaction_isolation')")
    ).scalar_one() != "read committed":
        raise RuntimeError("duplicate representative upgrade requires read committed")
    # The predecessor acquires this same transaction lock before reading the
    # duplicate graph. Hold it across the historical scan and replacement so
    # a concurrent old-version append cannot commit between those steps.
    op.get_bind().execute(
        text(
            "SELECT pg_catalog.pg_advisory_xact_lock("
            "pg_catalog.hashtextextended('walksafe-report-duplicate-cycle-v1', 0))"
        )
    )
    # Signed append-only decisions cannot be retargeted by a migration. If
    # historical active chains exist, deployment pauses for explicit review.
    historical_chains = op.get_bind().execute(
        text(
            "SELECT EXISTS ("
            "SELECT 1 FROM public.report_review_decisions AS child "
            "JOIN public.report_review_decisions AS target "
            "ON target.report_id = child.duplicate_of_report_id "
            "WHERE child.decision = 'DUPLICATE' "
            "AND target.decision = 'DUPLICATE' "
            "AND NOT EXISTS (SELECT 1 FROM public.report_review_decisions AS newer "
            "WHERE newer.report_id = child.report_id "
            "AND newer.revision > child.revision) "
            "AND NOT EXISTS (SELECT 1 FROM public.report_review_decisions AS newer "
            "WHERE newer.report_id = target.report_id "
            "AND newer.revision > target.revision))"
        )
    ).scalar_one()
    if historical_chains:
        raise RuntimeError("active indirect duplicate links require review before upgrade")
    _replace_guard(_CYCLE_END, _REPRESENTATIVE_END, _PREVIOUS_SHA256)
    op.execute(_INSERT_GUARD_FUNCTION)
    op.execute(
        "ALTER FUNCTION public.walksafe_enforce_duplicate_representative() "
        "OWNER TO walksafe_report_evidence_owner"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_enforce_duplicate_representative() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "CREATE TRIGGER report_review_duplicate_representative "
        "BEFORE INSERT ON public.report_review_decisions FOR EACH ROW "
        "EXECUTE FUNCTION public.walksafe_enforce_duplicate_representative()"
    )


def downgrade() -> None:
    op.execute(
        "DROP TRIGGER report_review_duplicate_representative "
        "ON public.report_review_decisions"
    )
    op.execute("DROP FUNCTION public.walksafe_enforce_duplicate_representative()")
    _replace_guard(_REPRESENTATIVE_END, _CYCLE_END, _REPRESENTATIVE_SHA256)
