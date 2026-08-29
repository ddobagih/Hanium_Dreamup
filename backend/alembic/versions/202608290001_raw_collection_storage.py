"""add encrypted single-chunk raw collection storage

Revision ID: 202608290001
Revises: 202608250002
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa
from sqlalchemy.dialects import postgresql


revision = "202608290001"
down_revision = "202608250002"
branch_labels = None
depends_on = None


_RAW_TABLES = (
    "raw_collections",
    "raw_collection_objects",
    "raw_collection_chunks",
)
_RUNTIME_ROLE = "walksafe_backend_runtime"
_DELETION_WORKER_ROLE = "walksafe_account_deletion_worker"


def _replace_rate_group_constraint(*, include_raw: bool) -> None:
    op.drop_constraint(
        "ck_actor_rate_limit_events_group",
        "actor_rate_limit_events",
        type_="check",
    )
    groups = [
        "report",
        "navigation",
        "detect",
        "export",
        "admin_read",
        "privacy",
    ]
    if include_raw:
        groups.append("raw_collection")
    allowed = ", ".join(f"'{group}'" for group in groups)
    op.create_check_constraint(
        "ck_actor_rate_limit_events_group",
        "actor_rate_limit_events",
        f"rate_group IN ({allowed})",
    )


def upgrade() -> None:
    op.create_unique_constraint(
        "uq_privacy_consent_receipt_subject_generation",
        "privacy_consent_events",
        ["receipt_sha256", "privacy_subject_hmac", "account_generation"],
    )

    op.create_table(
        "raw_collections",
        sa.Column(
            "collection_id",
            postgresql.UUID(as_uuid=True),
            nullable=False,
        ),
        sa.Column("privacy_subject_hmac", sa.String(length=64), nullable=False),
        sa.Column("account_generation", sa.BigInteger(), nullable=False),
        sa.Column("walk_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("segment_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("purpose", sa.String(length=16), nullable=False),
        sa.Column("consent_receipt_sha256", sa.String(length=64), nullable=False),
        sa.Column("manifest_sha256", sa.String(length=64), nullable=False),
        sa.Column("captured_started_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("captured_ended_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("object_count", sa.SmallInteger(), nullable=False),
        sa.Column("chunk_count", sa.Integer(), nullable=False),
        sa.Column("total_bytes", sa.BigInteger(), nullable=False),
        sa.Column("state", sa.String(length=32), nullable=False),
        sa.Column(
            "created_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_raw_collections_privacy_subject_hmac",
        ),
        sa.CheckConstraint(
            "account_generation >= 1",
            name="ck_raw_collections_account_generation",
        ),
        sa.CheckConstraint(
            "manifest_sha256 ~ '^[0-9a-f]{64}$' AND "
            "consent_receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_raw_collections_digests",
        ),
        sa.CheckConstraint(
            "purpose IN ('GENERAL_RAW', 'AUTO_REPORT')",
            name="ck_raw_collections_purpose",
        ),
        sa.CheckConstraint(
            "state IN ('MANIFEST_ACCEPTED', 'RECEIVING', "
            "'READY_TO_COMMIT', 'COMMITTED', 'QUARANTINED')",
            name="ck_raw_collections_state",
        ),
        sa.CheckConstraint(
            "object_count BETWEEN 1 AND 64 AND "
            "chunk_count BETWEEN 1 AND 2048 AND "
            "total_bytes BETWEEN 1 AND 17179869184",
            name="ck_raw_collections_contract_limits",
        ),
        sa.CheckConstraint(
            "captured_ended_at >= captured_started_at",
            name="ck_raw_collections_capture_order",
        ),
        sa.ForeignKeyConstraint(
            [
                "consent_receipt_sha256",
                "privacy_subject_hmac",
                "account_generation",
            ],
            [
                "privacy_consent_events.receipt_sha256",
                "privacy_consent_events.privacy_subject_hmac",
                "privacy_consent_events.account_generation",
            ],
            name="fk_raw_collections_consent_subject_generation",
            ondelete="RESTRICT",
        ),
        sa.PrimaryKeyConstraint("collection_id"),
        sa.UniqueConstraint(
            "collection_id",
            "manifest_sha256",
            "consent_receipt_sha256",
            name="uq_raw_collections_manifest_consent_binding",
        ),
    )
    op.create_index(
        "ix_raw_collections_privacy_subject_generation",
        "raw_collections",
        ["privacy_subject_hmac", "account_generation"],
    )

    op.create_table(
        "raw_collection_objects",
        sa.Column(
            "collection_id",
            postgresql.UUID(as_uuid=True),
            nullable=False,
        ),
        sa.Column("object_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("manifest_sha256", sa.String(length=64), nullable=False),
        sa.Column("consent_receipt_sha256", sa.String(length=64), nullable=False),
        sa.Column("kind", sa.String(length=32), nullable=False),
        sa.Column("content_type", sa.String(length=128), nullable=False),
        sa.Column("size_bytes", sa.BigInteger(), nullable=False),
        sa.Column("sha256", sa.String(length=64), nullable=False),
        sa.Column("chunk_count", sa.Integer(), nullable=False),
        sa.CheckConstraint(
            "manifest_sha256 ~ '^[0-9a-f]{64}$' AND "
            "consent_receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_raw_collection_objects_binding_digests",
        ),
        sa.CheckConstraint(
            "kind IN ('VIDEO', 'AUDIO', 'EXACT_LOCATION', 'SENSOR', "
            "'ROUTE', 'DETECTION', 'REPORT', 'PERFORMANCE')",
            name="ck_raw_collection_objects_kind",
        ),
        sa.CheckConstraint(
            "content_type ~ "
            "'^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/"
            "[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$'",
            name="ck_raw_collection_objects_content_type",
        ),
        sa.CheckConstraint(
            "size_bytes BETWEEN 1 AND 17179869184 AND "
            "chunk_count BETWEEN 1 AND 2048",
            name="ck_raw_collection_objects_contract_limits",
        ),
        sa.CheckConstraint(
            "sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_raw_collection_objects_sha256",
        ),
        sa.ForeignKeyConstraint(
            ["collection_id", "manifest_sha256", "consent_receipt_sha256"],
            [
                "raw_collections.collection_id",
                "raw_collections.manifest_sha256",
                "raw_collections.consent_receipt_sha256",
            ],
            name="fk_raw_collection_objects_manifest_consent",
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("collection_id", "object_id"),
        sa.UniqueConstraint(
            "collection_id",
            "object_id",
            "manifest_sha256",
            "consent_receipt_sha256",
            name="uq_raw_collection_objects_manifest_consent_binding",
        ),
    )

    op.create_table(
        "raw_collection_chunks",
        sa.Column(
            "collection_id",
            postgresql.UUID(as_uuid=True),
            nullable=False,
        ),
        sa.Column("object_id", postgresql.UUID(as_uuid=True), nullable=False),
        sa.Column("chunk_index", sa.Integer(), nullable=False),
        sa.Column("manifest_sha256", sa.String(length=64), nullable=False),
        sa.Column("consent_receipt_sha256", sa.String(length=64), nullable=False),
        sa.Column("declared_size_bytes", sa.BigInteger(), nullable=False),
        sa.Column("declared_sha256", sa.String(length=64), nullable=False),
        sa.Column("storage_name", sa.String(length=160), nullable=True),
        sa.Column("envelope_version", sa.SmallInteger(), nullable=True),
        sa.Column("algorithm", sa.String(length=16), nullable=True),
        sa.Column("aad_version", sa.SmallInteger(), nullable=True),
        sa.Column("key_id", sa.String(length=64), nullable=True),
        sa.Column("nonce", sa.LargeBinary(length=12), nullable=True),
        sa.Column("envelope_sha256", sa.String(length=64), nullable=True),
        sa.Column("envelope_size", sa.BigInteger(), nullable=True),
        sa.Column("persisted_at", sa.DateTime(timezone=True), nullable=True),
        sa.CheckConstraint(
            "manifest_sha256 ~ '^[0-9a-f]{64}$' AND "
            "consent_receipt_sha256 ~ '^[0-9a-f]{64}$' AND "
            "declared_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_raw_collection_chunks_binding_digests",
        ),
        sa.CheckConstraint(
            "chunk_index BETWEEN 0 AND 2047 AND "
            "declared_size_bytes BETWEEN 1 AND 8388608",
            name="ck_raw_collection_chunks_contract_limits",
        ),
        sa.CheckConstraint(
            "storage_name IS NULL OR storage_name ~ "
            "'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
            "[0-9a-f]{12}\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            "[0-9a-f]{4}-[0-9a-f]{12}\\.[0-9]{1,4}\\.wsrc$'",
            name="ck_raw_collection_chunks_storage_name",
        ),
        sa.CheckConstraint(
            "envelope_version IS NULL OR envelope_version = 1",
            name="ck_raw_collection_chunks_envelope_version",
        ),
        sa.CheckConstraint(
            "algorithm IS NULL OR algorithm = 'AES-256-GCM'",
            name="ck_raw_collection_chunks_algorithm",
        ),
        sa.CheckConstraint(
            "aad_version IS NULL OR aad_version = 1",
            name="ck_raw_collection_chunks_aad_version",
        ),
        sa.CheckConstraint(
            "key_id IS NULL OR key_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'",
            name="ck_raw_collection_chunks_key_id",
        ),
        sa.CheckConstraint(
            "nonce IS NULL OR octet_length(nonce) = 12",
            name="ck_raw_collection_chunks_nonce",
        ),
        sa.CheckConstraint(
            "envelope_sha256 IS NULL OR envelope_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_raw_collection_chunks_envelope_sha256",
        ),
        sa.CheckConstraint(
            "envelope_size IS NULL OR "
            "(envelope_size > declared_size_bytes + 16 "
            "AND envelope_size <= declared_size_bytes + 4124)",
            name="ck_raw_collection_chunks_envelope_size",
        ),
        sa.CheckConstraint(
            "(storage_name IS NULL AND envelope_version IS NULL AND "
            "algorithm IS NULL AND aad_version IS NULL AND key_id IS NULL AND "
            "nonce IS NULL AND envelope_sha256 IS NULL AND "
            "envelope_size IS NULL AND persisted_at IS NULL) OR "
            "(storage_name IS NOT NULL AND envelope_version IS NOT NULL AND "
            "algorithm IS NOT NULL AND aad_version IS NOT NULL AND "
            "key_id IS NOT NULL AND nonce IS NOT NULL AND "
            "envelope_sha256 IS NOT NULL AND envelope_size IS NOT NULL AND "
            "persisted_at IS NOT NULL)",
            name="ck_raw_collection_chunks_persistence_all_or_none",
        ),
        sa.ForeignKeyConstraint(
            [
                "collection_id",
                "object_id",
                "manifest_sha256",
                "consent_receipt_sha256",
            ],
            [
                "raw_collection_objects.collection_id",
                "raw_collection_objects.object_id",
                "raw_collection_objects.manifest_sha256",
                "raw_collection_objects.consent_receipt_sha256",
            ],
            name="fk_raw_collection_chunks_object_manifest_consent",
            ondelete="CASCADE",
        ),
        sa.PrimaryKeyConstraint("collection_id", "object_id", "chunk_index"),
        sa.UniqueConstraint(
            "storage_name",
            name="uq_raw_collection_chunks_storage_name",
        ),
        sa.UniqueConstraint(
            "key_id",
            "nonce",
            name="uq_raw_collection_chunks_key_nonce",
        ),
    )
    op.create_index(
        "ix_raw_collection_chunks_key_id",
        "raw_collection_chunks",
        ["key_id"],
    )

    op.execute(
        """
        CREATE FUNCTION walksafe_guard_raw_collection_state()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        AS $$
        BEGIN
          IF TG_OP = 'INSERT' THEN
            IF NEW.state <> 'MANIFEST_ACCEPTED' THEN
              RAISE EXCEPTION 'raw collection must start in MANIFEST_ACCEPTED'
                USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
          END IF;
          IF ROW(
               NEW.collection_id, NEW.privacy_subject_hmac,
               NEW.account_generation, NEW.walk_id, NEW.segment_id,
               NEW.purpose, NEW.consent_receipt_sha256, NEW.manifest_sha256,
               NEW.captured_started_at, NEW.captured_ended_at,
               NEW.object_count, NEW.chunk_count, NEW.total_bytes,
               NEW.created_at
             ) IS DISTINCT FROM ROW(
               OLD.collection_id, OLD.privacy_subject_hmac,
               OLD.account_generation, OLD.walk_id, OLD.segment_id,
               OLD.purpose, OLD.consent_receipt_sha256, OLD.manifest_sha256,
               OLD.captured_started_at, OLD.captured_ended_at,
               OLD.object_count, OLD.chunk_count, OLD.total_bytes,
               OLD.created_at
             ) THEN
            RAISE EXCEPTION 'raw collection manifest metadata is immutable'
              USING ERRCODE = '23514';
          END IF;
          IF NEW.state IS DISTINCT FROM OLD.state AND NOT (
            OLD.state = 'MANIFEST_ACCEPTED'
            AND NEW.state = 'READY_TO_COMMIT'
          ) THEN
            RAISE EXCEPTION 'raw collection state transition is not allowed in B1b'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END
        $$
        """
    )
    op.execute(
        "CREATE TRIGGER raw_collections_state_guard "
        "BEFORE INSERT OR UPDATE ON public.raw_collections FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_raw_collection_state()"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_guard_raw_collection_state() "
        f"FROM PUBLIC, {_RUNTIME_ROLE}, {_DELETION_WORKER_ROLE}"
    )

    op.execute(
        """
        CREATE FUNCTION walksafe_guard_raw_chunk_persistence()
        RETURNS trigger
        LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        AS $$
        BEGIN
          IF TG_OP = 'INSERT' THEN
            IF NEW.storage_name IS NOT NULL THEN
              RAISE EXCEPTION 'raw chunk must start without persisted metadata'
                USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
          END IF;
          IF ROW(
               NEW.collection_id, NEW.object_id, NEW.chunk_index,
               NEW.manifest_sha256, NEW.consent_receipt_sha256,
               NEW.declared_size_bytes, NEW.declared_sha256
             ) IS DISTINCT FROM ROW(
               OLD.collection_id, OLD.object_id, OLD.chunk_index,
               OLD.manifest_sha256, OLD.consent_receipt_sha256,
               OLD.declared_size_bytes, OLD.declared_sha256
             ) THEN
            RAISE EXCEPTION 'raw chunk declared metadata is immutable'
              USING ERRCODE = '23514';
          END IF;
          IF OLD.storage_name IS NOT NULL AND ROW(
               NEW.storage_name, NEW.envelope_version, NEW.algorithm,
               NEW.aad_version, NEW.key_id, NEW.nonce,
               NEW.envelope_sha256, NEW.envelope_size, NEW.persisted_at
             ) IS DISTINCT FROM ROW(
               OLD.storage_name, OLD.envelope_version, OLD.algorithm,
               OLD.aad_version, OLD.key_id, OLD.nonce,
               OLD.envelope_sha256, OLD.envelope_size, OLD.persisted_at
             ) THEN
            RAISE EXCEPTION 'raw chunk persisted metadata is immutable'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END
        $$
        """
    )
    op.execute(
        "CREATE TRIGGER raw_collection_chunks_persistence_immutable "
        "BEFORE INSERT OR UPDATE ON public.raw_collection_chunks FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_raw_chunk_persistence()"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_guard_raw_chunk_persistence() "
        f"FROM PUBLIC, {_RUNTIME_ROLE}, {_DELETION_WORKER_ROLE}"
    )

    qualified_tables = ", ".join(f"public.{table}" for table in _RAW_TABLES)
    op.execute(
        f"REVOKE ALL PRIVILEGES ON TABLE {qualified_tables} "
        f"FROM PUBLIC, {_RUNTIME_ROLE}, {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        f"GRANT SELECT, INSERT ON TABLE {qualified_tables} TO {_RUNTIME_ROLE}"
    )
    op.execute(
        "GRANT UPDATE (state) ON TABLE public.raw_collections "
        f"TO {_RUNTIME_ROLE}"
    )
    op.execute(
        "GRANT UPDATE (storage_name, envelope_version, algorithm, aad_version, "
        "key_id, nonce, envelope_sha256, envelope_size, persisted_at) "
        "ON TABLE public.raw_collection_chunks "
        f"TO {_RUNTIME_ROLE}"
    )
    op.execute(
        f"REVOKE DELETE, TRUNCATE ON TABLE {qualified_tables} "
        f"FROM {_RUNTIME_ROLE}, {_DELETION_WORKER_ROLE}"
    )

    _replace_rate_group_constraint(include_raw=True)


def downgrade() -> None:
    op.execute(
        "DELETE FROM actor_rate_limit_events "
        "WHERE rate_group = 'raw_collection'"
    )
    _replace_rate_group_constraint(include_raw=False)
    qualified_tables = ", ".join(f"public.{table}" for table in _RAW_TABLES)
    op.execute(
        f"REVOKE ALL PRIVILEGES ON TABLE {qualified_tables} "
        f"FROM {_RUNTIME_ROLE}, {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS raw_collection_chunks_persistence_immutable "
        "ON public.raw_collection_chunks"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS raw_collections_state_guard "
        "ON public.raw_collections"
    )
    op.execute("DROP FUNCTION IF EXISTS walksafe_guard_raw_chunk_persistence()")
    op.execute("DROP FUNCTION IF EXISTS walksafe_guard_raw_collection_state()")
    op.drop_table("raw_collection_chunks")
    op.drop_table("raw_collection_objects")
    op.drop_table("raw_collections")
    op.drop_constraint(
        "uq_privacy_consent_receipt_subject_generation",
        "privacy_consent_events",
        type_="unique",
    )
