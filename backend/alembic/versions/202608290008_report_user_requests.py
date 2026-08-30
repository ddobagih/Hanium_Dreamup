"""add user-visible review reasons and report correction/deletion requests

Revision ID: 202608290008
Revises: 202608290007
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608290008"
down_revision = "202608290007"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.add_column(
        "report_review_decisions",
        sa.Column("user_visible_reason", sa.String(length=500), nullable=True),
    )
    # Never derive public text from the pre-existing internal reviewer reason.
    op.execute(
        "ALTER TABLE report_review_decisions DISABLE TRIGGER "
        "report_review_decisions_append_only"
    )
    op.execute(
        "UPDATE report_review_decisions SET user_visible_reason = "
        "'이전 검수 기록의 공개 사유를 확인할 수 없습니다.' "
        "WHERE decision IN ('REJECTED', 'DUPLICATE') "
        "AND user_visible_reason IS NULL"
    )
    op.execute(
        "ALTER TABLE report_review_decisions ENABLE TRIGGER "
        "report_review_decisions_append_only"
    )
    op.execute(
        "ALTER TABLE report_review_decisions ADD CONSTRAINT "
        "ck_report_review_decisions_user_visible_reason CHECK ("
        "(decision = 'APPROVED' AND user_visible_reason IS NULL) OR "
        "(decision IN ('REJECTED', 'DUPLICATE') AND "
        "length(user_visible_reason) BETWEEN 1 AND 500)) NOT VALID"
    )
    op.execute(
        "ALTER TABLE report_review_decisions VALIDATE CONSTRAINT "
        "ck_report_review_decisions_user_visible_reason"
    )

    op.create_table(
        "report_user_requests",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("report_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("client_request_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("request_type", sa.String(length=16), nullable=False),
        sa.Column("request_text", sa.String(length=500), nullable=False),
        sa.Column("intent_sha256", sa.String(length=64), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("status_version", sa.BigInteger(), nullable=False),
        sa.Column("public_response", sa.String(length=500), nullable=True),
        sa.Column("internal_note", sa.String(length=500), nullable=True),
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
            "request_type IN ('CORRECTION', 'DELETE')",
            name="ck_report_user_requests_type",
        ),
        sa.CheckConstraint(
            "length(request_text) BETWEEN 1 AND 500",
            name="ck_report_user_requests_text",
        ),
        sa.CheckConstraint(
            "intent_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_user_requests_intent_sha256",
        ),
        sa.CheckConstraint(
            "status IN ('RECEIVED', 'ACKNOWLEDGED', 'RESOLVED', 'REJECTED')",
            name="ck_report_user_requests_status",
        ),
        sa.CheckConstraint(
            "status_version >= 1",
            name="ck_report_user_requests_status_version",
        ),
        sa.CheckConstraint(
            "public_response IS NULL OR length(public_response) BETWEEN 1 AND 500",
            name="ck_report_user_requests_public_response",
        ),
        sa.CheckConstraint(
            "internal_note IS NULL OR length(internal_note) BETWEEN 1 AND 500",
            name="ck_report_user_requests_internal_note",
        ),
        sa.ForeignKeyConstraint(
            ["report_id"], ["reports.id"], ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "report_id",
            "client_request_id",
            name="uq_report_user_requests_report_client_request",
        ),
    )
    op.create_index(
        "ix_report_user_requests_status_created_at",
        "report_user_requests",
        ["status", "created_at"],
    )
    op.create_index(
        "ix_report_user_requests_report_created_at",
        "report_user_requests",
        ["report_id", "created_at"],
    )

    op.create_table(
        "report_user_request_status_events",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("request_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("previous_status", sa.String(length=16), nullable=True),
        sa.Column("next_status", sa.String(length=16), nullable=False),
        sa.Column("previous_version", sa.BigInteger(), nullable=False),
        sa.Column("next_version", sa.BigInteger(), nullable=False),
        sa.Column("actor_kind", sa.String(length=16), nullable=False),
        sa.Column("actor_id", sa.String(length=64), nullable=True),
        sa.Column("privacy_subject_hmac", sa.String(length=64), nullable=True),
        sa.Column("account_generation", sa.BigInteger(), nullable=True),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("device_id", sa.String(length=128), nullable=True),
        sa.Column("correlation_id", postgresql.UUID(as_uuid=True), nullable=True),
        sa.Column("public_response", sa.String(length=500), nullable=True),
        sa.Column("internal_note", sa.String(length=500), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "previous_status IS NULL OR previous_status IN "
            "('RECEIVED', 'ACKNOWLEDGED', 'RESOLVED', 'REJECTED')",
            name="ck_report_user_request_events_previous_status",
        ),
        sa.CheckConstraint(
            "next_status IN ('RECEIVED', 'ACKNOWLEDGED', 'RESOLVED', 'REJECTED')",
            name="ck_report_user_request_events_next_status",
        ),
        sa.CheckConstraint(
            "(previous_status IS NULL AND previous_version = 0 AND "
            "next_status = 'RECEIVED' AND next_version = 1) OR "
            "(previous_status IS NOT NULL AND previous_status <> next_status AND "
            "previous_version >= 1 AND next_version = previous_version + 1)",
            name="ck_report_user_request_events_transition",
        ),
        sa.CheckConstraint(
            "actor_kind IN ('FIELD', 'ADMIN')",
            name="ck_report_user_request_events_actor_kind",
        ),
        sa.CheckConstraint(
            "(actor_kind = 'FIELD' AND actor_id IS NULL AND "
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$' AND "
            "account_generation >= 1 AND session_id IS NULL AND "
            "device_id IS NULL AND correlation_id IS NULL) OR "
            "(actor_kind = 'ADMIN' AND actor_id IS NOT NULL AND "
            "privacy_subject_hmac IS NULL AND account_generation IS NULL AND "
            "session_id IS NOT NULL AND device_id IS NOT NULL AND "
            "correlation_id IS NOT NULL)",
            name="ck_report_user_request_events_actor_binding",
        ),
        sa.CheckConstraint(
            "public_response IS NULL OR length(public_response) BETWEEN 1 AND 500",
            name="ck_report_user_request_events_public_response",
        ),
        sa.CheckConstraint(
            "internal_note IS NULL OR length(internal_note) BETWEEN 1 AND 500",
            name="ck_report_user_request_events_internal_note",
        ),
        sa.ForeignKeyConstraint(
            ["request_id"], ["report_user_requests.id"], ondelete="CASCADE"
        ),
        sa.PrimaryKeyConstraint("id"),
        sa.UniqueConstraint(
            "request_id",
            "next_version",
            name="uq_report_user_request_events_request_version",
        ),
    )
    op.create_index(
        "ix_report_user_request_events_request_created_at",
        "report_user_request_status_events",
        ["request_id", "created_at"],
    )

    op.execute(
        """
        CREATE FUNCTION public.walksafe_report_user_request_transition_guard()
        RETURNS trigger
        LANGUAGE plpgsql
        SECURITY DEFINER
        SET search_path = pg_catalog, public
        AS $guard$
        BEGIN
            IF TG_TABLE_NAME = 'report_user_requests' THEN
                IF TG_OP = 'UPDATE' AND (
                    NEW.id IS DISTINCT FROM OLD.id OR
                    NEW.report_id IS DISTINCT FROM OLD.report_id OR
                    NEW.client_request_id IS DISTINCT FROM OLD.client_request_id OR
                    NEW.request_type IS DISTINCT FROM OLD.request_type OR
                    NEW.request_text IS DISTINCT FROM OLD.request_text OR
                    NEW.intent_sha256 IS DISTINCT FROM OLD.intent_sha256 OR
                    NEW.created_at IS DISTINCT FROM OLD.created_at OR
                    NEW.status_version <> OLD.status_version + 1 OR
                    NEW.status IS NOT DISTINCT FROM OLD.status
                ) THEN
                    RAISE EXCEPTION 'report request immutable or transition columns changed';
                END IF;
                IF TG_OP = 'INSERT' AND NOT EXISTS (
                    SELECT 1
                    FROM public.report_user_request_status_events AS event
                    WHERE event.request_id = NEW.id
                      AND event.previous_status IS NULL
                      AND event.previous_version = 0
                      AND event.next_status = NEW.status
                      AND event.next_version = NEW.status_version
                      AND event.public_response IS NOT DISTINCT FROM NEW.public_response
                      AND event.internal_note IS NOT DISTINCT FROM NEW.internal_note
                ) THEN
                    RAISE EXCEPTION 'report request creation event is missing';
                ELSIF TG_OP = 'UPDATE' AND NOT EXISTS (
                    SELECT 1
                    FROM public.report_user_request_status_events AS event
                    WHERE event.request_id = NEW.id
                      AND event.previous_status = OLD.status
                      AND event.previous_version = OLD.status_version
                      AND event.next_status = NEW.status
                      AND event.next_version = NEW.status_version
                      AND event.public_response IS NOT DISTINCT FROM NEW.public_response
                      AND event.internal_note IS NOT DISTINCT FROM NEW.internal_note
                ) THEN
                    RAISE EXCEPTION 'report request transition event is missing';
                END IF;
            ELSIF NOT EXISTS (
                SELECT 1
                FROM public.report_user_requests AS request
                WHERE request.id = NEW.request_id
                  AND request.status = NEW.next_status
                  AND request.status_version = NEW.next_version
                  AND request.public_response IS NOT DISTINCT FROM NEW.public_response
                  AND request.internal_note IS NOT DISTINCT FROM NEW.internal_note
            ) THEN
                RAISE EXCEPTION 'report request event projection is missing';
            END IF;
            RETURN NEW;
        END
        $guard$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION "
        "public.walksafe_report_user_request_transition_guard() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "CREATE CONSTRAINT TRIGGER report_user_request_projection_event_guard "
        "AFTER INSERT OR UPDATE ON public.report_user_requests "
        "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION "
        "public.walksafe_report_user_request_transition_guard()"
    )
    op.execute(
        "CREATE CONSTRAINT TRIGGER report_user_request_event_projection_guard "
        "AFTER INSERT ON public.report_user_request_status_events "
        "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION "
        "public.walksafe_report_user_request_transition_guard()"
    )

    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE public.report_user_requests, "
        "public.report_user_request_status_events FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT SELECT, INSERT ON TABLE public.report_user_requests "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT UPDATE (status, status_version, public_response, internal_note, updated_at) "
        "ON TABLE public.report_user_requests TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT INSERT ON TABLE public.report_user_request_status_events "
        "TO walksafe_backend_runtime"
    )


def downgrade() -> None:
    op.execute(
        "DROP TRIGGER IF EXISTS report_user_request_event_projection_guard "
        "ON public.report_user_request_status_events"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS report_user_request_projection_event_guard "
        "ON public.report_user_requests"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "public.walksafe_report_user_request_transition_guard()"
    )
    op.drop_index(
        "ix_report_user_request_events_request_created_at",
        table_name="report_user_request_status_events",
    )
    op.drop_table("report_user_request_status_events")
    op.drop_index(
        "ix_report_user_requests_report_created_at",
        table_name="report_user_requests",
    )
    op.drop_index(
        "ix_report_user_requests_status_created_at",
        table_name="report_user_requests",
    )
    op.drop_table("report_user_requests")
    op.drop_constraint(
        "ck_report_review_decisions_user_visible_reason",
        "report_review_decisions",
        type_="check",
    )
    op.drop_column("report_review_decisions", "user_visible_reason")
