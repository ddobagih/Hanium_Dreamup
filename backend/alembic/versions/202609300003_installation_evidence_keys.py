"""register per-installation keys that sign device deletion evidence

S1617-52: an installation registers a P-256 public key; device deletion
evidence for that installation must then carry its signature. Evidence signed by
another installation, or unsigned, is refused. Rows are append-only; the runtime
role may read and insert only.

Revision ID: 202609300003
Revises: 202609300002
Create Date: 2026-09-30
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "202609300003"
down_revision = "202609300002"
branch_labels = None
depends_on = None

_TABLE = "privacy_installation_evidence_keys"
_RUNTIME_ROLE = "walksafe_backend_runtime"


def upgrade() -> None:
    op.create_table(
        _TABLE,
        sa.Column("privacy_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("account_generation", sa.BigInteger(), nullable=False),
        sa.Column("installation_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("public_key_der", sa.LargeBinary(), nullable=False),
        sa.Column("public_key_sha256", sa.String(length=64), nullable=False),
        sa.Column(
            "registered_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$' AND "
            "installation_subject_hmac ~ '^[0-9a-f]{64}$' AND "
            "public_key_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_privacy_installation_evidence_key_digests",
        ),
        sa.CheckConstraint(
            "account_generation >= 1",
            name="ck_privacy_installation_evidence_key_generation",
        ),
        sa.CheckConstraint(
            "octet_length(public_key_der) BETWEEN 59 AND 158",
            name="ck_privacy_installation_evidence_key_der",
        ),
        sa.PrimaryKeyConstraint(
            "privacy_subject_hmac",
            "account_generation",
            "installation_subject_hmac",
        ),
    )
    op.execute(
        f"CREATE TRIGGER {_TABLE}_append_only "
        f"BEFORE UPDATE OR DELETE OR TRUNCATE ON {_TABLE} "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(f"REVOKE ALL PRIVILEGES ON TABLE {_TABLE} FROM {_RUNTIME_ROLE}")
    op.execute(f"GRANT SELECT, INSERT ON TABLE {_TABLE} TO {_RUNTIME_ROLE}")


def downgrade() -> None:
    # Predecessor code has no signature enforcement, so the bindings are
    # dropped with the table; installations re-register after an upgrade.
    # Recorded evidence and its digests are untouched.
    op.execute(f"DROP TRIGGER {_TABLE}_append_only ON {_TABLE}")
    op.drop_table(_TABLE)
