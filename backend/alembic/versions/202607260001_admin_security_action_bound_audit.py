"""bind administrator reconfirmation and chain security audits

Revision ID: 202607260001
Revises: 202607220001
Create Date: 2026-07-26
"""

from __future__ import annotations

from datetime import timezone
import hashlib
import json

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202607260001"
down_revision = "202607220001"
branch_labels = None
depends_on = None


def _timestamp(value) -> str:
    if value.tzinfo is None:
        value = value.replace(tzinfo=timezone.utc)
    return (
        value.astimezone(timezone.utc)
        .isoformat(timespec="microseconds")
        .replace("+00:00", "Z")
    )


def _entry_sha256(row, sequence: int, previous_entry_sha256: str | None) -> str:
    payload = {
        "action": row["action"],
        "admin_id": row["admin_id"],
        "created_at": _timestamp(row["created_at"]),
        "details": row["details"],
        "device_id": row["device_id"],
        "event_id": str(row["id"]),
        "outcome": row["outcome"],
        "previous_entry_sha256": previous_entry_sha256,
        "schema_version": "walksafe-admin-security-audit-chain-v1",
        "sequence": sequence,
        "session_id": str(row["session_id"]) if row["session_id"] is not None else None,
    }
    canonical = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def _drop_audit_guards() -> None:
    op.execute(
        "DROP TRIGGER IF EXISTS admin_security_audits_reject_truncate "
        "ON admin_security_audits"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS admin_security_audits_append_only "
        "ON admin_security_audits"
    )


def _create_audit_guards() -> None:
    op.execute(
        "CREATE TRIGGER admin_security_audits_append_only "
        "BEFORE UPDATE OR DELETE OR TRUNCATE ON admin_security_audits "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )


def _create_legacy_audit_guards() -> None:
    op.execute(
        "CREATE TRIGGER admin_security_audits_append_only "
        "BEFORE UPDATE OR DELETE ON admin_security_audits "
        "FOR EACH ROW EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(
        "CREATE TRIGGER admin_security_audits_reject_truncate "
        "BEFORE TRUNCATE ON admin_security_audits "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )


def upgrade() -> None:
    op.create_table(
        "admin_security_reconfirmations",
        sa.Column("id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("session_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("device_id", sa.String(length=128), nullable=False),
        sa.Column("action", sa.String(length=64), nullable=False),
        sa.Column("method", sa.String(length=8), nullable=False),
        sa.Column("path", sa.String(length=512), nullable=False),
        sa.Column("nonce_sha256", sa.String(length=64), nullable=False),
        sa.Column("verified_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("consumed_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("now()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')",
            name="ck_admin_security_reconfirmations_method",
        ),
        sa.CheckConstraint(
            "nonce_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_reconfirmations_nonce_sha256",
        ),
        sa.CheckConstraint(
            "expires_at > verified_at",
            name="ck_admin_security_reconfirmations_expiry",
        ),
        sa.PrimaryKeyConstraint("id"),
    )
    op.create_index(
        "ix_admin_security_reconfirmations_admin_id",
        "admin_security_reconfirmations",
        ["admin_id"],
    )
    op.create_index(
        "ix_admin_security_reconfirmations_session_id",
        "admin_security_reconfirmations",
        ["session_id"],
    )
    op.create_index(
        "ix_admin_security_reconfirmations_device_id",
        "admin_security_reconfirmations",
        ["device_id"],
    )
    op.create_index(
        "ix_admin_security_reconfirmations_action",
        "admin_security_reconfirmations",
        ["action"],
    )
    op.create_index(
        "ix_admin_security_reconfirmations_nonce_sha256",
        "admin_security_reconfirmations",
        ["nonce_sha256"],
        unique=True,
    )
    op.create_index(
        "ix_admin_security_reconfirmations_expires_at",
        "admin_security_reconfirmations",
        ["expires_at"],
    )
    op.create_index(
        "ix_admin_security_reconfirmations_consumed_at",
        "admin_security_reconfirmations",
        ["consumed_at"],
    )

    op.execute("LOCK TABLE admin_security_audits IN ACCESS EXCLUSIVE MODE")
    _drop_audit_guards()
    op.add_column(
        "admin_security_audits",
        sa.Column("sequence", sa.BigInteger(), nullable=True),
    )
    op.add_column(
        "admin_security_audits",
        sa.Column("previous_entry_sha256", sa.String(length=64), nullable=True),
    )
    op.add_column(
        "admin_security_audits",
        sa.Column("entry_sha256", sa.String(length=64), nullable=True),
    )

    connection = op.get_bind()
    rows = connection.execute(
        sa.text(
            "SELECT id, admin_id, session_id, device_id, action, outcome, details, created_at "
            "FROM admin_security_audits ORDER BY created_at, id FOR UPDATE"
        )
    ).mappings()
    previous_entry_sha256 = None
    for sequence, row in enumerate(rows, start=1):
        entry_sha256 = _entry_sha256(row, sequence, previous_entry_sha256)
        connection.execute(
            sa.text(
                "UPDATE admin_security_audits "
                "SET sequence = :sequence, "
                "previous_entry_sha256 = :previous_entry_sha256, "
                "entry_sha256 = :entry_sha256 "
                "WHERE id = :id"
            ),
            {
                "sequence": sequence,
                "previous_entry_sha256": previous_entry_sha256,
                "entry_sha256": entry_sha256,
                "id": row["id"],
            },
        )
        previous_entry_sha256 = entry_sha256

    op.alter_column(
        "admin_security_audits",
        "sequence",
        existing_type=sa.BigInteger(),
        nullable=False,
    )
    op.alter_column(
        "admin_security_audits",
        "entry_sha256",
        existing_type=sa.String(length=64),
        nullable=False,
    )
    op.create_check_constraint(
        "ck_admin_security_audits_sequence",
        "admin_security_audits",
        "sequence >= 1",
    )
    op.create_check_constraint(
        "ck_admin_security_audits_previous_entry_sha256",
        "admin_security_audits",
        "previous_entry_sha256 IS NULL OR "
        "previous_entry_sha256 ~ '^[0-9a-f]{64}$'",
    )
    op.create_check_constraint(
        "ck_admin_security_audits_entry_sha256",
        "admin_security_audits",
        "entry_sha256 ~ '^[0-9a-f]{64}$'",
    )
    op.create_check_constraint(
        "ck_admin_security_audits_chain_position",
        "admin_security_audits",
        "(sequence = 1 AND previous_entry_sha256 IS NULL) OR "
        "(sequence > 1 AND previous_entry_sha256 IS NOT NULL)",
    )
    op.create_unique_constraint(
        "uq_admin_security_audits_sequence",
        "admin_security_audits",
        ["sequence"],
    )
    op.create_index(
        "ix_admin_security_audits_sequence",
        "admin_security_audits",
        ["sequence"],
        unique=True,
    )
    op.create_index(
        "ix_admin_security_audits_entry_sha256",
        "admin_security_audits",
        ["entry_sha256"],
        unique=True,
    )
    _create_audit_guards()


def downgrade() -> None:
    op.execute("LOCK TABLE admin_security_audits IN ACCESS EXCLUSIVE MODE")
    _drop_audit_guards()
    op.drop_index(
        "ix_admin_security_audits_entry_sha256",
        table_name="admin_security_audits",
    )
    op.drop_index(
        "ix_admin_security_audits_sequence",
        table_name="admin_security_audits",
    )
    op.drop_constraint(
        "uq_admin_security_audits_sequence",
        "admin_security_audits",
        type_="unique",
    )
    op.drop_constraint(
        "ck_admin_security_audits_chain_position",
        "admin_security_audits",
        type_="check",
    )
    op.drop_constraint(
        "ck_admin_security_audits_entry_sha256",
        "admin_security_audits",
        type_="check",
    )
    op.drop_constraint(
        "ck_admin_security_audits_previous_entry_sha256",
        "admin_security_audits",
        type_="check",
    )
    op.drop_constraint(
        "ck_admin_security_audits_sequence",
        "admin_security_audits",
        type_="check",
    )
    op.drop_column("admin_security_audits", "entry_sha256")
    op.drop_column("admin_security_audits", "previous_entry_sha256")
    op.drop_column("admin_security_audits", "sequence")
    _create_legacy_audit_guards()

    op.drop_table("admin_security_reconfirmations")
