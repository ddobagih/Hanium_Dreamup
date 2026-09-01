"""record manual institution-copy deletion request and reply facts

Revision ID: 202608300006
Revises: 202608300005
Create Date: 2026-09-01
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608300006"
down_revision = "202608300005"
branch_labels = None
depends_on = None


_RUNTIME_ROLE = "walksafe_backend_runtime"
_DELETION_WORKER_ROLE = "walksafe_report_deletion_worker"


def upgrade() -> None:
    op.create_table(
        "report_deletion_external_copy_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "external_copy_state_id",
            postgresql.UUID(as_uuid=True),
            nullable=False,
        ),
        sa.Column("revision", sa.BigInteger(), nullable=False),
        sa.Column("expected_revision", sa.BigInteger(), nullable=False),
        sa.Column(
            "idempotency_key",
            postgresql.UUID(as_uuid=True),
            nullable=False,
        ),
        sa.Column("state", sa.String(length=32), nullable=False),
        sa.Column("institution_reference", sa.String(length=160), nullable=True),
        sa.Column("evidence_sha256", sa.String(length=64), nullable=True),
        sa.Column("observed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", sa.String(length=128), nullable=False),
        sa.Column("correlation_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column(
            "recorded_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.ForeignKeyConstraint(
            ["external_copy_state_id"],
            ["report_deletion_external_copy_states.id"],
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "external_copy_state_id",
            "revision",
            name="uq_report_deletion_external_copy_events_revision",
        ),
        sa.UniqueConstraint(
            "external_copy_state_id",
            "idempotency_key",
            name="uq_report_deletion_external_copy_events_idempotency",
        ),
        sa.CheckConstraint(
            "revision >= 1 AND expected_revision >= 0 "
            "AND revision = expected_revision + 1",
            name="ck_report_deletion_external_copy_events_revision",
        ),
        sa.CheckConstraint(
            "state IN ('REQUEST_SENT', 'REPLY_ACKNOWLEDGED', "
            "'REPLY_DELETION_CONFIRMED', 'REPLY_DECLINED')",
            name="ck_report_deletion_external_copy_events_state",
        ),
        sa.CheckConstraint(
            "institution_reference IS NULL OR institution_reference ~ "
            "'^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$'",
            name="ck_report_deletion_external_copy_events_reference",
        ),
        sa.CheckConstraint(
            "evidence_sha256 IS NULL OR evidence_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_deletion_external_copy_events_evidence",
        ),
        sa.CheckConstraint(
            "state = 'REQUEST_SENT' OR institution_reference IS NOT NULL "
            "OR evidence_sha256 IS NOT NULL",
            name="ck_report_deletion_external_copy_events_reply_evidence",
        ),
        sa.CheckConstraint(
            "state <> 'REPLY_DELETION_CONFIRMED' OR evidence_sha256 IS NOT NULL",
            name="ck_report_deletion_external_copy_events_confirmation_evidence",
        ),
        sa.CheckConstraint(
            "observed_at <= recorded_at",
            name="ck_report_deletion_external_copy_events_time_order",
        ),
        sa.CheckConstraint(
            "admin_id ~ '^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$'",
            name="ck_report_deletion_external_copy_events_admin",
        ),
        sa.CheckConstraint(
            "device_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$'",
            name="ck_report_deletion_external_copy_events_device",
        ),
    )
    op.create_index(
        "ix_report_deletion_external_copy_events_external_copy_state_id",
        "report_deletion_external_copy_events",
        ["external_copy_state_id"],
    )

    op.execute(
        """
        CREATE FUNCTION public.walksafe_guard_report_deletion_external_copy_event()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, public
        AS $guard$
        DECLARE
          previous_revision bigint;
          previous_state text;
          previous_observed_at timestamptz;
        BEGIN
          NEW.recorded_at := pg_catalog.clock_timestamp();
          SELECT event.revision, event.state, event.observed_at
            INTO previous_revision, previous_state, previous_observed_at
            FROM public.report_deletion_external_copy_events AS event
           WHERE event.external_copy_state_id = NEW.external_copy_state_id
           ORDER BY event.revision DESC, event.id DESC
           LIMIT 1;

          IF NOT FOUND THEN
            IF NEW.revision <> 1 OR NEW.expected_revision <> 0
               OR NEW.state <> 'REQUEST_SENT' THEN
              RAISE EXCEPTION 'external-copy deletion record must begin REQUEST_SENT'
                USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
          END IF;

          IF NEW.revision <> previous_revision + 1
             OR NEW.expected_revision <> previous_revision THEN
            RAISE EXCEPTION 'external-copy deletion event revision is stale'
              USING ERRCODE = '40001';
          END IF;
          IF NEW.observed_at < previous_observed_at THEN
            RAISE EXCEPTION 'external-copy deletion observation time cannot move backward'
              USING ERRCODE = '23514';
          END IF;
          IF NOT (
            (previous_state = 'REQUEST_SENT' AND NEW.state IN (
              'REPLY_ACKNOWLEDGED', 'REPLY_DELETION_CONFIRMED', 'REPLY_DECLINED'
            )) OR
            (previous_state = 'REPLY_ACKNOWLEDGED' AND NEW.state IN (
              'REPLY_DELETION_CONFIRMED', 'REPLY_DECLINED'
            )) OR
            (previous_state = 'REPLY_DECLINED' AND NEW.state = 'REQUEST_SENT')
          ) THEN
            RAISE EXCEPTION 'external-copy deletion state transition is not allowed'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION "
        "public.walksafe_guard_report_deletion_external_copy_event() "
        f"FROM PUBLIC, {_RUNTIME_ROLE}, {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "CREATE TRIGGER report_deletion_external_copy_events_insert_guard "
        "BEFORE INSERT ON public.report_deletion_external_copy_events "
        "FOR EACH ROW EXECUTE FUNCTION "
        "public.walksafe_guard_report_deletion_external_copy_event()"
    )
    op.execute(
        "CREATE TRIGGER report_deletion_external_copy_events_append_only "
        "BEFORE UPDATE OR DELETE OR TRUNCATE ON "
        "public.report_deletion_external_copy_events FOR EACH STATEMENT "
        "EXECUTE FUNCTION public.walksafe_reject_audit_mutation()"
    )

    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE "
        "public.report_deletion_external_copy_events "
        f"FROM PUBLIC, {_RUNTIME_ROLE}, {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT, INSERT ON TABLE "
        "public.report_deletion_external_copy_events "
        f"TO {_RUNTIME_ROLE}"
    )


def downgrade() -> None:
    op.execute(
        """
        DO $guard$
        BEGIN
          IF EXISTS (
            SELECT 1
              FROM public.report_deletion_external_copy_events
             LIMIT 1
          ) THEN
            RAISE EXCEPTION 'cannot drop external-copy deletion evidence with rows';
          END IF;
        END
        $guard$
        """
    )
    op.execute(
        "DROP TRIGGER report_deletion_external_copy_events_append_only "
        "ON public.report_deletion_external_copy_events"
    )
    op.execute(
        "DROP TRIGGER report_deletion_external_copy_events_insert_guard "
        "ON public.report_deletion_external_copy_events"
    )
    op.execute(
        "DROP FUNCTION public.walksafe_guard_report_deletion_external_copy_event()"
    )
    op.drop_index(
        "ix_report_deletion_external_copy_events_external_copy_state_id",
        table_name="report_deletion_external_copy_events",
    )
    op.drop_table("report_deletion_external_copy_events")
