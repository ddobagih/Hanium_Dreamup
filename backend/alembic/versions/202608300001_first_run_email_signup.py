"""Add the first-run email signup lifecycle.

FP-010 stages 4, 5, 7 and 8 wait on operating credential, verification,
activation and login evidence. A paid identity-verification provider is not
available during development, so email stands in for phone verification until
release. See product/decisions.md, 2026-08-30.

The address itself is never stored. Only its keyed pseudonym is kept, so the
table cannot re-derive who signed up, and the opaque handles are random rather
than derived from anything the user typed.
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy.dialects.postgresql import UUID

revision = "202608300001"
down_revision = "202608250002"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "first_run_signups",
        sa.Column("id", UUID(as_uuid=True), primary_key=True),
        sa.Column("email_hmac", sa.String(64), nullable=False, unique=True, index=True),
        sa.Column(
            "submission_handle", sa.String(36), nullable=False, unique=True, index=True
        ),
        sa.Column("verification_code_sha256", sa.String(64), nullable=False),
        sa.Column("verification_expires_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("verification_attempts", sa.Integer(), nullable=False, server_default="0"),
        sa.Column("verified_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("activated_at", sa.DateTime(timezone=True), nullable=True),
        sa.Column("actor_binding", sa.String(38), nullable=True, unique=True, index=True),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            nullable=False,
            server_default=sa.func.now(),
        ),
        sa.CheckConstraint(
            "email_hmac ~ '^[0-9a-f]{64}$'", name="ck_first_run_signups_email_hmac"
        ),
        sa.CheckConstraint(
            "submission_handle ~ '^onb_[0-9a-f]{32}$'",
            name="ck_first_run_signups_submission_handle",
        ),
        sa.CheckConstraint(
            "verification_code_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_first_run_signups_code_sha256",
        ),
        sa.CheckConstraint(
            "actor_binding IS NULL OR actor_binding ~ '^actor_[0-9a-f]{32}$'",
            name="ck_first_run_signups_actor_binding",
        ),
        sa.CheckConstraint(
            "verification_attempts >= 0", name="ck_first_run_signups_attempts"
        ),
        # 단계는 되돌아가지 않는다. 활성화는 검증 뒤, 로그인 결속은 활성화 뒤에만 생긴다.
        sa.CheckConstraint(
            "verified_at IS NOT NULL OR activated_at IS NULL",
            name="ck_first_run_signups_activation_after_verification",
        ),
        sa.CheckConstraint(
            "activated_at IS NOT NULL OR actor_binding IS NULL",
            name="ck_first_run_signups_binding_after_activation",
        ),
    )


def downgrade() -> None:
    op.drop_table("first_run_signups")
