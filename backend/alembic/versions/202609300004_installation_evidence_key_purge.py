"""purge installation evidence keys when the account deletion completes

S1617-52-F1: installation evidence public keys are deletion-scope data. The
statement-level append-only trigger of 202609300003 is replaced:

- UPDATE and TRUNCATE stay rejected for every role;
- DELETE is allowed only inside
  walksafe_purge_privacy_installation_evidence_keys(subject, generation), a
  SECURITY DEFINER function with a fixed search_path, and only for a subject
  generation whose deletion request is COMPLETED;
- the runtime role (and the account deletion worker that can complete the last
  server item) may EXECUTE that function, never DELETE on the table.

The verification fact stays on DEVICE_EVIDENCE events as the key fingerprint
installation_evidence_key_sha256 (NPC-DATA-LIFECYCLE rules[5]: fingerprint,
processing time, and result only). The column is outside every evidence digest.

Revision ID: 202609300004
Revises: 202609300003
Create Date: 2026-09-30
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "202609300004"
down_revision = "202609300003"
branch_labels = None
depends_on = None

_TABLE = "privacy_installation_evidence_keys"
_RUNTIME_ROLE = "walksafe_backend_runtime"
_WORKER_ROLE = "walksafe_account_deletion_worker"
_EVENT_COLUMN = "installation_evidence_key_sha256"
_EVENT_CONSTRAINT = "ck_account_deletion_event_installation_key_sha256"
_PURGE_FUNCTION = "walksafe_purge_privacy_installation_evidence_keys"
_PURGE_SIGNATURE = f"{_PURGE_FUNCTION}(text, bigint)"
_GUARD_FUNCTION = "walksafe_guard_privacy_installation_evidence_key_delete"
_PURGE_SETTING = "walksafe.installation_evidence_key_purge"


def upgrade() -> None:
    op.add_column(
        "account_deletion_events",
        sa.Column(_EVENT_COLUMN, sa.String(length=64), nullable=True),
    )
    op.create_check_constraint(
        _EVENT_CONSTRAINT,
        "account_deletion_events",
        f"{_EVENT_COLUMN} IS NULL OR "
        f"(event_type = 'DEVICE_EVIDENCE' AND {_EVENT_COLUMN} ~ '^[0-9a-f]{{64}}$')",
    )
    op.execute(f"DROP TRIGGER {_TABLE}_append_only ON {_TABLE}")
    op.execute(
        f"CREATE TRIGGER {_TABLE}_no_update "
        f"BEFORE UPDATE OR TRUNCATE ON {_TABLE} "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(
        f"""
        CREATE FUNCTION {_GUARD_FUNCTION}()
        RETURNS trigger AS $$
        BEGIN
          IF pg_catalog.current_setting('{_PURGE_SETTING}', true)
               IS DISTINCT FROM (OLD.privacy_subject_hmac || ':' || OLD.account_generation::text)
             OR NOT EXISTS (
               SELECT 1
                 FROM public.account_deletion_requests AS request
                WHERE request.privacy_subject_hmac = OLD.privacy_subject_hmac
                  AND request.account_generation = OLD.account_generation
                  AND request.overall_status = 'COMPLETED'
             ) THEN
            RAISE EXCEPTION 'installation evidence keys are deleted only by the completed-deletion purge'
              USING ERRCODE = '23514';
          END IF;
          RETURN OLD;
        END;
        $$ LANGUAGE plpgsql
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        f"CREATE TRIGGER {_TABLE}_purge_only_delete "
        f"BEFORE DELETE ON {_TABLE} FOR EACH ROW "
        f"EXECUTE FUNCTION {_GUARD_FUNCTION}()"
    )
    op.execute(
        f"""
        CREATE FUNCTION {_PURGE_FUNCTION}(
          p_privacy_subject_hmac text,
          p_account_generation bigint
        ) RETURNS bigint AS $$
        DECLARE
          deleted_count bigint;
        BEGIN
          IF p_privacy_subject_hmac IS NULL
             OR p_privacy_subject_hmac !~ '^[0-9a-f]{{64}}$'
             OR p_account_generation IS NULL
             OR p_account_generation < 1 THEN
            RAISE EXCEPTION 'installation evidence key purge subject is invalid'
              USING ERRCODE = '22023';
          END IF;
          IF NOT EXISTS (
            SELECT 1
              FROM public.account_deletion_requests AS request
             WHERE request.privacy_subject_hmac = p_privacy_subject_hmac
               AND request.account_generation = p_account_generation
               AND request.overall_status = 'COMPLETED'
          ) THEN
            RAISE EXCEPTION 'installation evidence keys are purged only after the deletion request completes'
              USING ERRCODE = '23514';
          END IF;
          -- The marker is transaction-local and scoped to this subject; a
          -- failure rolls the block (and the marker) back before re-raising.
          BEGIN
            PERFORM pg_catalog.set_config(
              '{_PURGE_SETTING}',
              p_privacy_subject_hmac || ':' || p_account_generation::text,
              true
            );
            DELETE FROM public.{_TABLE} AS evidence_key
             WHERE evidence_key.privacy_subject_hmac = p_privacy_subject_hmac
               AND evidence_key.account_generation = p_account_generation;
            GET DIAGNOSTICS deleted_count = ROW_COUNT;
            PERFORM pg_catalog.set_config('{_PURGE_SETTING}', '', true);
          EXCEPTION WHEN OTHERS THEN
            RAISE;
          END;
          RETURN deleted_count;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(f"REVOKE ALL ON FUNCTION {_PURGE_SIGNATURE} FROM PUBLIC")
    op.execute(
        f"GRANT EXECUTE ON FUNCTION {_PURGE_SIGNATURE} TO {_RUNTIME_ROLE}, {_WORKER_ROLE}"
    )
    op.execute(f"REVOKE ALL ON FUNCTION {_GUARD_FUNCTION}() FROM PUBLIC")
    # Runtime keeps SELECT/INSERT only (202609300003); no DELETE is granted.
    op.execute(f"REVOKE UPDATE, DELETE, TRUNCATE ON TABLE {_TABLE} FROM {_RUNTIME_ROLE}")


def downgrade() -> None:
    # The predecessor cannot purge keys when a deletion completes, and the
    # 202609300003 downgrade drops the installation bindings (a different key
    # could then bind to the same installation). Refuse while any binding or
    # key-fingerprint verification record exists instead of losing it.
    bind = op.get_bind()
    registered = bind.execute(sa.text(f"SELECT count(*) FROM {_TABLE}")).scalar_one()
    if registered:
        raise RuntimeError(
            "cannot downgrade 202609300004 while installation evidence keys are "
            "registered: the predecessor cannot purge them at deletion completion "
            "and would drop the installation bindings"
        )
    fingerprinted = bind.execute(
        sa.text(
            f"SELECT count(*) FROM account_deletion_events WHERE {_EVENT_COLUMN} IS NOT NULL"
        )
    ).scalar_one()
    if fingerprinted:
        raise RuntimeError(
            "cannot downgrade 202609300004 after signed device evidence was recorded: "
            "the key-fingerprint verification record would be dropped"
        )
    op.execute(f"DROP FUNCTION {_PURGE_SIGNATURE}")
    op.execute(f"DROP TRIGGER {_TABLE}_purge_only_delete ON {_TABLE}")
    op.execute(f"DROP FUNCTION {_GUARD_FUNCTION}()")
    op.execute(f"DROP TRIGGER {_TABLE}_no_update ON {_TABLE}")
    op.execute(
        f"CREATE TRIGGER {_TABLE}_append_only "
        f"BEFORE UPDATE OR DELETE OR TRUNCATE ON {_TABLE} "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.drop_constraint(_EVENT_CONSTRAINT, "account_deletion_events", type_="check")
    op.drop_column("account_deletion_events", _EVENT_COLUMN)
