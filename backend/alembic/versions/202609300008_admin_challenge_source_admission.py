"""per-source admission for session-less administrator device proof challenges

S18-5: LOGIN and RECOVERY_COMPLETE device proof challenges are issued before a
session exists, so anyone who knows the public device identifiers could fill
the eight-slot pool. The Backend now records each session-less issuance in
admin_security_auth_attempts (source and principal digests only) and admits
at most half a pool per source within one challenge lifetime. This revision
only widens the attempt action CHECK with 'challenge_login' and
'challenge_recovery'.

Downgrade removes those short-lived admission rows (they carry no evidence)
before restoring the previous CHECK.

Revision ID: 202609300008
Revises: 202609300007
Create Date: 2026-09-30
"""

from __future__ import annotations

from alembic import op


revision = "202609300008"
down_revision = "202609300007"
branch_labels = None
depends_on = None

_TABLE = "admin_security_auth_attempts"
_CONSTRAINT = "ck_admin_security_auth_attempts_action"
_PREVIOUS = "action IN ('login', 'reauthenticate', 'recovery_start', 'recovery_complete')"
_CURRENT = (
    "action IN ('login', 'reauthenticate', 'recovery_start', 'recovery_complete', "
    "'challenge_login', 'challenge_recovery')"
)


def upgrade() -> None:
    op.drop_constraint(_CONSTRAINT, _TABLE, type_="check")
    op.create_check_constraint(_CONSTRAINT, _TABLE, _CURRENT)


def downgrade() -> None:
    op.execute(
        f"DELETE FROM {_TABLE} WHERE action IN ('challenge_login', 'challenge_recovery')"
    )
    op.drop_constraint(_CONSTRAINT, _TABLE, type_="check")
    op.create_check_constraint(_CONSTRAINT, _TABLE, _PREVIOUS)
