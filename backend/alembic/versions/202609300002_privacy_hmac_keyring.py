"""bind a privacy HMAC keyring for rotation without re-keying

S1617-50: privacy_hmac_keyring holds one ACTIVE key and older LOOKUP_ONLY keys
(fingerprints only). Tombstones created under an older key keep blocking the
same account after rotation because lookups use every bound key. The runtime
role may only read the keyring; rotation is an owner operation. Tombstones gain
hmac_key_version for rows written after this revision.

Revision ID: 202609300002
Revises: 202609300001
Create Date: 2026-09-30
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "202609300002"
down_revision = "202609300001"
branch_labels = None
depends_on = None

_RUNTIME_ROLE = "walksafe_backend_runtime"


def upgrade() -> None:
    op.create_table(
        "privacy_hmac_keyring",
        sa.Column("key_version", sa.BigInteger(), autoincrement=False, nullable=False),
        sa.Column("secret_fingerprint", sa.String(length=64), nullable=False),
        sa.Column("state", sa.String(length=16), nullable=False),
        sa.Column(
            "bound_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint("key_version >= 1", name="ck_privacy_hmac_keyring_version"),
        sa.CheckConstraint(
            "secret_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_privacy_hmac_keyring_fingerprint",
        ),
        sa.CheckConstraint(
            "state IN ('ACTIVE', 'LOOKUP_ONLY')",
            name="ck_privacy_hmac_keyring_state",
        ),
        sa.PrimaryKeyConstraint("key_version"),
        sa.UniqueConstraint(
            "secret_fingerprint",
            name="uq_privacy_hmac_keyring_fingerprint",
        ),
    )
    op.create_index(
        "uq_privacy_hmac_keyring_single_active",
        "privacy_hmac_keyring",
        ["state"],
        unique=True,
        postgresql_where=sa.text("state = 'ACTIVE'"),
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_guard_privacy_hmac_keyring()
        RETURNS trigger AS $$
        BEGIN
          IF TG_OP IN ('DELETE', 'TRUNCATE') THEN
            RAISE EXCEPTION 'privacy HMAC keyring entries are retained'
              USING ERRCODE = '23514';
          END IF;
          IF NEW.key_version IS DISTINCT FROM OLD.key_version
             OR NEW.secret_fingerprint IS DISTINCT FROM OLD.secret_fingerprint
             OR NEW.bound_at IS DISTINCT FROM OLD.bound_at
             OR NOT (OLD.state = 'ACTIVE' AND NEW.state = 'LOOKUP_ONLY') THEN
            RAISE EXCEPTION 'privacy HMAC keyring entries only retire ACTIVE to LOOKUP_ONLY'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "CREATE TRIGGER privacy_hmac_keyring_row_guard "
        "BEFORE UPDATE OR DELETE ON privacy_hmac_keyring FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_privacy_hmac_keyring()"
    )
    op.execute(
        "CREATE TRIGGER privacy_hmac_keyring_no_truncate "
        "BEFORE TRUNCATE ON privacy_hmac_keyring FOR EACH STATEMENT "
        "EXECUTE FUNCTION walksafe_guard_privacy_hmac_keyring()"
    )
    op.execute(f"REVOKE ALL PRIVILEGES ON TABLE privacy_hmac_keyring FROM {_RUNTIME_ROLE}")
    op.execute(f"GRANT SELECT ON TABLE privacy_hmac_keyring TO {_RUNTIME_ROLE}")

    op.add_column(
        "account_deletion_tombstones",
        sa.Column("hmac_key_version", sa.BigInteger(), nullable=True),
    )
    op.create_check_constraint(
        "ck_account_deletion_tombstone_hmac_key_version",
        "account_deletion_tombstones",
        "hmac_key_version IS NULL OR hmac_key_version >= 1",
    )


def downgrade() -> None:
    bind = op.get_bind()
    rotated = bind.execute(
        sa.text("SELECT count(*) FROM privacy_hmac_keyring WHERE state = 'LOOKUP_ONLY'")
    ).scalar_one()
    if rotated:
        raise RuntimeError(
            "cannot downgrade 202609300002 after a privacy HMAC rotation: "
            "lookup-only keys still identify tombstoned subjects"
        )
    op.drop_constraint(
        "ck_account_deletion_tombstone_hmac_key_version",
        "account_deletion_tombstones",
        type_="check",
    )
    op.drop_column("account_deletion_tombstones", "hmac_key_version")
    op.execute("DROP TRIGGER privacy_hmac_keyring_no_truncate ON privacy_hmac_keyring")
    op.execute("DROP TRIGGER privacy_hmac_keyring_row_guard ON privacy_hmac_keyring")
    op.drop_index("uq_privacy_hmac_keyring_single_active", table_name="privacy_hmac_keyring")
    op.drop_table("privacy_hmac_keyring")
    op.execute("DROP FUNCTION walksafe_guard_privacy_hmac_keyring()")
