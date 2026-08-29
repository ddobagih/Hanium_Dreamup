"""add record-only critical incident projection and event ledger

Revision ID: 202608290015
Revises: 202608290014
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608290015"
down_revision = "202608290014"
branch_labels = None
depends_on = None


_RUNTIME_ROLE = "walksafe_backend_runtime"


def upgrade() -> None:
    op.create_table(
        "critical_incidents",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("producer_source", sa.String(length=32), nullable=False),
        sa.Column("opening_intent_sha256", sa.String(length=64), nullable=False),
        sa.Column("severity", sa.String(length=16), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("status_version", sa.BigInteger(), nullable=False),
        sa.Column("reason_code", sa.String(length=48), nullable=False),
        sa.Column("summary", sa.String(length=200), nullable=False),
        sa.Column("started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("detected_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.Column(
            "updated_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "producer_source = 'WALKSAFE_BACKEND'",
            name="ck_critical_incidents_producer_source",
        ),
        sa.CheckConstraint(
            "opening_intent_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_critical_incidents_opening_intent_sha256",
        ),
        sa.CheckConstraint(
            "severity = 'CRITICAL'",
            name="ck_critical_incidents_severity",
        ),
        sa.CheckConstraint(
            "status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'REOPENED')",
            name="ck_critical_incidents_status",
        ),
        sa.CheckConstraint(
            "status_version BETWEEN 1 AND 256",
            name="ck_critical_incidents_status_version",
        ),
        sa.CheckConstraint(
            "reason_code IN ('USER_SAFETY_RISK', 'PERSONAL_DATA_BREACH', "
            "'DELETION_INTEGRITY_FAILURE', 'CORE_SERVICE_TOTAL_OUTAGE', "
            "'IRREVERSIBLE_DATA_LOSS')",
            name="ck_critical_incidents_reason_code",
        ),
        sa.CheckConstraint(
            "length(summary) BETWEEN 1 AND 200",
            name="ck_critical_incidents_summary",
        ),
        sa.CheckConstraint(
            "started_at <= detected_at AND detected_at <= created_at "
            "AND created_at <= updated_at",
            name="ck_critical_incidents_time_order",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_critical_incidents_detected_id",
        "critical_incidents",
        ["detected_at", "id"],
    )
    op.create_index(
        "ix_critical_incidents_status_detected_id",
        "critical_incidents",
        ["status", "detected_at", "id"],
    )

    op.create_table(
        "critical_incident_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("incident_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("revision", sa.BigInteger(), nullable=False),
        sa.Column("event_type", sa.String(length=16), nullable=False),
        sa.Column("previous_state", sa.String(length=16), nullable=True),
        sa.Column("next_state", sa.String(length=16), nullable=False),
        sa.Column("expected_version", sa.BigInteger(), nullable=False),
        sa.Column("idempotency_key", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("intent_sha256", sa.String(length=64), nullable=False),
        sa.Column("reason", sa.String(length=500), nullable=False),
        sa.Column("observation", sa.String(length=500), nullable=False),
        sa.Column("evidence_sha256", sa.String(length=64), nullable=False),
        sa.Column("observed_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column(
            "recorded_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.Column("actor_id", sa.String(length=64), nullable=True),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("device_id", sa.String(length=128), nullable=True),
        sa.Column("correlation_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.ForeignKeyConstraint(
            ["incident_id"],
            ["critical_incidents.id"],
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "incident_id",
            "revision",
            name="uq_critical_incident_events_revision",
        ),
        sa.UniqueConstraint(
            "incident_id",
            "idempotency_key",
            name="uq_critical_incident_events_idempotency",
        ),
        sa.CheckConstraint(
            "revision BETWEEN 1 AND 256 AND expected_version = revision - 1",
            name="ck_critical_incident_events_revision",
        ),
        sa.CheckConstraint(
            "event_type IN ('OPENED', 'ACKNOWLEDGED', 'RESOLVED', 'REOPENED')",
            name="ck_critical_incident_events_type",
        ),
        sa.CheckConstraint(
            "previous_state IS NULL OR previous_state IN "
            "('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'REOPENED')",
            name="ck_critical_incident_events_previous_state",
        ),
        sa.CheckConstraint(
            "next_state IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'REOPENED')",
            name="ck_critical_incident_events_next_state",
        ),
        sa.CheckConstraint(
            "(revision = 1 AND event_type = 'OPENED' AND previous_state IS NULL "
            "AND next_state = 'OPEN' AND actor_id IS NULL AND session_id IS NULL "
            "AND device_id IS NULL AND correlation_id IS NULL) OR "
            "(revision > 1 AND event_type = next_state AND actor_id IS NOT NULL "
            "AND session_id IS NOT NULL AND device_id IS NOT NULL "
            "AND correlation_id IS NOT NULL)",
            name="ck_critical_incident_events_actor_binding",
        ),
        sa.CheckConstraint(
            "revision = 1 OR (previous_state IS NOT NULL AND ("
            "(previous_state = 'OPEN' AND next_state = 'ACKNOWLEDGED') OR "
            "(previous_state = 'ACKNOWLEDGED' AND next_state = 'RESOLVED') OR "
            "(previous_state = 'RESOLVED' AND next_state = 'REOPENED') OR "
            "(previous_state = 'REOPENED' AND next_state = 'ACKNOWLEDGED')))",
            name="ck_critical_incident_events_transition",
        ),
        sa.CheckConstraint(
            "intent_sha256 ~ '^[0-9a-f]{64}$' "
            "AND evidence_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_critical_incident_events_sha256",
        ),
        sa.CheckConstraint(
            "length(reason) BETWEEN 8 AND 500 "
            "AND length(observation) BETWEEN 8 AND 500",
            name="ck_critical_incident_events_text",
        ),
        sa.CheckConstraint(
            "observed_at <= recorded_at",
            name="ck_critical_incident_events_time_order",
        ),
    )
    op.execute(
        """
        CREATE FUNCTION public.walksafe_guard_critical_incident_projection()
        RETURNS trigger
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF TG_OP = 'DELETE' OR TG_OP = 'TRUNCATE' THEN
                RAISE EXCEPTION 'critical incident projections are immutable records';
            END IF;
            IF TG_OP = 'INSERT' THEN
                IF NEW.status <> 'OPEN' OR NEW.status_version <> 1 THEN
                    RAISE EXCEPTION 'critical incident must begin OPEN at version 1';
                END IF;
                RETURN NEW;
            END IF;
            IF ROW(NEW.id, NEW.producer_source, NEW.opening_intent_sha256,
                   NEW.severity, NEW.reason_code, NEW.summary, NEW.started_at,
                   NEW.detected_at, NEW.created_at)
               IS DISTINCT FROM
               ROW(OLD.id, OLD.producer_source, OLD.opening_intent_sha256,
                   OLD.severity, OLD.reason_code, OLD.summary, OLD.started_at,
                   OLD.detected_at, OLD.created_at) THEN
                RAISE EXCEPTION 'critical incident metadata is immutable';
            END IF;
            IF NEW.status_version <> OLD.status_version + 1 OR (
                NOT (OLD.status = 'OPEN' AND NEW.status = 'ACKNOWLEDGED') AND
                NOT (OLD.status = 'ACKNOWLEDGED' AND NEW.status = 'RESOLVED') AND
                NOT (OLD.status = 'RESOLVED' AND NEW.status = 'REOPENED') AND
                NOT (OLD.status = 'REOPENED' AND NEW.status = 'ACKNOWLEDGED')
            ) THEN
                RAISE EXCEPTION 'critical incident status transition is not allowed';
            END IF;
            IF NEW.updated_at <= OLD.updated_at THEN
                RAISE EXCEPTION 'critical incident update time must advance';
            END IF;
            RETURN NEW;
        END
        $guard$
        """
    )
    op.execute(
        "CREATE TRIGGER critical_incidents_update_guard "
        "BEFORE INSERT OR UPDATE OR DELETE ON public.critical_incidents "
        "FOR EACH ROW EXECUTE FUNCTION public.walksafe_guard_critical_incident_projection()"
    )
    op.execute(
        "CREATE TRIGGER critical_incidents_no_truncate "
        "BEFORE TRUNCATE ON public.critical_incidents "
        "FOR EACH STATEMENT EXECUTE FUNCTION public.walksafe_guard_critical_incident_projection()"
    )

    op.execute(
        """
        CREATE FUNCTION public.walksafe_require_critical_incident_event_projection()
        RETURNS trigger
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF TG_TABLE_NAME = 'critical_incidents' THEN
                IF TG_OP = 'INSERT' THEN
                    IF NOT EXISTS (
                        SELECT 1
                        FROM public.critical_incident_events AS event
                        WHERE event.incident_id = NEW.id
                          AND event.revision = 1
                          AND event.event_type = 'OPENED'
                          AND event.previous_state IS NULL
                          AND event.next_state = 'OPEN'
                          AND event.expected_version = 0
                          AND event.idempotency_key = NEW.id
                          AND event.intent_sha256 = NEW.opening_intent_sha256
                          AND event.recorded_at = NEW.updated_at
                    ) THEN
                        RAISE EXCEPTION 'critical incident opening event is missing';
                    END IF;
                ELSIF NOT EXISTS (
                    SELECT 1
                    FROM public.critical_incident_events AS event
                    WHERE event.incident_id = NEW.id
                      AND event.revision = NEW.status_version
                      AND event.previous_state = OLD.status
                      AND event.next_state = NEW.status
                      AND event.expected_version = OLD.status_version
                      AND event.recorded_at = NEW.updated_at
                ) THEN
                    RAISE EXCEPTION 'critical incident transition event is missing';
                END IF;
            ELSIF NEW.revision = 1 THEN
                IF NOT EXISTS (
                    SELECT 1
                    FROM public.critical_incidents AS incident
                    WHERE incident.id = NEW.incident_id
                      AND incident.status_version = 1
                      AND incident.status = 'OPEN'
                      AND incident.opening_intent_sha256 = NEW.intent_sha256
                      AND NEW.idempotency_key = incident.id
                      AND incident.updated_at = NEW.recorded_at
                ) THEN
                    RAISE EXCEPTION 'critical incident opening projection is missing';
                END IF;
            ELSIF NOT EXISTS (
                SELECT 1
                FROM public.critical_incidents AS incident
                WHERE incident.id = NEW.incident_id
                  AND incident.status_version = NEW.revision
                  AND incident.status = NEW.next_state
                  AND incident.updated_at = NEW.recorded_at
            ) OR NOT EXISTS (
                SELECT 1
                FROM public.critical_incident_events AS previous_event
                WHERE previous_event.incident_id = NEW.incident_id
                  AND previous_event.revision = NEW.revision - 1
                  AND previous_event.next_state = NEW.previous_state
                  AND previous_event.recorded_at < NEW.recorded_at
            ) THEN
                RAISE EXCEPTION 'critical incident event projection is missing';
            END IF;
            RETURN NEW;
        END
        $guard$
        """
    )
    op.execute(
        "CREATE CONSTRAINT TRIGGER critical_incident_projection_event_guard "
        "AFTER INSERT OR UPDATE ON public.critical_incidents "
        "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION "
        "public.walksafe_require_critical_incident_event_projection()"
    )
    op.execute(
        "CREATE CONSTRAINT TRIGGER critical_incident_event_projection_guard "
        "AFTER INSERT ON public.critical_incident_events "
        "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION "
        "public.walksafe_require_critical_incident_event_projection()"
    )
    op.execute(
        "CREATE TRIGGER critical_incident_events_append_only "
        "BEFORE UPDATE OR DELETE ON public.critical_incident_events "
        "FOR EACH ROW EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(
        "CREATE TRIGGER critical_incident_events_no_truncate "
        "BEFORE TRUNCATE ON public.critical_incident_events "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )

    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE public.critical_incidents, "
        "public.critical_incident_events FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT SELECT, INSERT ON TABLE public.critical_incidents "
        f"TO {_RUNTIME_ROLE}"
    )
    op.execute(
        "GRANT UPDATE (status, status_version, updated_at) ON TABLE "
        f"public.critical_incidents TO {_RUNTIME_ROLE}"
    )
    op.execute(
        "GRANT SELECT, INSERT ON TABLE public.critical_incident_events "
        f"TO {_RUNTIME_ROLE}"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_guard_critical_incident_projection() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION "
        "public.walksafe_require_critical_incident_event_projection() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )


def downgrade() -> None:
    op.execute(
        "LOCK TABLE public.critical_incidents, public.critical_incident_events "
        "IN ACCESS EXCLUSIVE MODE"
    )
    op.execute(
        "DO $$ BEGIN IF EXISTS (SELECT 1 FROM public.critical_incidents) "
        "OR EXISTS (SELECT 1 FROM public.critical_incident_events) THEN "
        "RAISE EXCEPTION 'cannot downgrade while critical incident evidence exists' "
        "USING ERRCODE = '55000'; END IF; END $$"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS critical_incident_event_projection_guard "
        "ON public.critical_incident_events"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS critical_incident_projection_event_guard "
        "ON public.critical_incidents"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS critical_incident_events_no_truncate "
        "ON public.critical_incident_events"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS critical_incident_events_append_only "
        "ON public.critical_incident_events"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS critical_incidents_no_truncate "
        "ON public.critical_incidents"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS critical_incidents_update_guard "
        "ON public.critical_incidents"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "public.walksafe_require_critical_incident_event_projection()"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS public.walksafe_guard_critical_incident_projection()"
    )
    op.drop_table("critical_incident_events")
    op.drop_index(
        "ix_critical_incidents_status_detected_id",
        table_name="critical_incidents",
    )
    op.drop_index(
        "ix_critical_incidents_detected_id",
        table_name="critical_incidents",
    )
    op.drop_table("critical_incidents")
