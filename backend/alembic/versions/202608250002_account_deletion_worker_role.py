"""Bind server-owned deletion completion to the dedicated worker role.

Revision ID: 202608250002
Revises: 202608250001
"""

from alembic import op


revision = "202608250002"
down_revision = "202608250001"
branch_labels = None
depends_on = None


_WORKER_ROLE = "walksafe_account_deletion_worker"


def upgrade() -> None:
    op.execute(
        f"""
        DO $$
        DECLARE role_record record;
        BEGIN
          SELECT rolsuper, rolcreaterole, rolcreatedb, rolcanlogin,
                 rolreplication, rolbypassrls
            INTO role_record
            FROM pg_roles
           WHERE rolname = '{_WORKER_ROLE}';
          IF NOT FOUND THEN
            CREATE ROLE {_WORKER_ROLE}
              NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
              NOINHERIT NOREPLICATION NOBYPASSRLS;
          ELSIF role_record.rolsuper OR role_record.rolcreaterole
             OR role_record.rolcreatedb OR role_record.rolcanlogin
             OR role_record.rolreplication OR role_record.rolbypassrls THEN
            RAISE EXCEPTION '{_WORKER_ROLE} has unsafe role attributes';
          END IF;
        END
        $$
        """
    )
    op.execute(f"GRANT USAGE ON SCHEMA public TO {_WORKER_ROLE}")
    op.execute(f"REVOKE CREATE ON SCHEMA public FROM {_WORKER_ROLE}")
    op.execute(
        f"GRANT SELECT ON TABLE public.reports, public.report_image_objects, "
        "public.account_deletion_tombstones, public.account_deletion_requests, "
        "public.account_deletion_items, public.account_deletion_events, "
        "public.account_deletion_receipts, public.account_deletion_device_targets, "
        "public.privacy_consent_events "
        f"TO {_WORKER_ROLE}"
    )
    op.execute(f"GRANT DELETE ON TABLE public.reports TO {_WORKER_ROLE}")
    op.execute(
        "GRANT UPDATE ON TABLE public.account_deletion_requests, "
        f"public.account_deletion_items TO {_WORKER_ROLE}"
    )
    op.execute(
        "GRANT INSERT ON TABLE public.account_deletion_events, "
        f"public.account_deletion_receipts TO {_WORKER_ROLE}"
    )
    op.execute(
        f"REVOKE DELETE, TRUNCATE ON TABLE public.account_deletion_tombstones, "
        "public.account_deletion_requests, public.account_deletion_items, "
        "public.account_deletion_events, public.account_deletion_receipts, "
        f"public.privacy_consent_events FROM {_WORKER_ROLE}"
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_guard_server_deletion_terminal_transition()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $$
        BEGIN
          IF OLD.item_key IN (
               'server_originals', 'server_quarantine',
               'server_copies', 'report_records'
             )
             AND OLD.state NOT IN ('COMPLETED', 'NOT_APPLICABLE')
             AND NEW.state IN ('COMPLETED', 'NOT_APPLICABLE')
             AND NOT (
               current_user = session_user
               AND EXISTS (
                 SELECT 1
                   FROM pg_auth_members AS worker_membership
                   JOIN pg_roles AS worker_login
                     ON worker_login.oid = worker_membership.member
                  WHERE worker_membership.roleid =
                        'walksafe_account_deletion_worker'::regrole
                    AND worker_login.rolname = current_user
                    AND worker_membership.admin_option IS FALSE
                    AND worker_membership.inherit_option IS TRUE
                    AND worker_membership.set_option IS FALSE
               )
             )
          THEN
            RAISE EXCEPTION
              'server-owned deletion completion requires the dedicated worker role'
              USING ERRCODE = '42501';
          END IF;
          RETURN NEW;
        END
        $$
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION "
        "walksafe_guard_server_deletion_terminal_transition() FROM PUBLIC"
    )
    op.execute(
        "CREATE TRIGGER account_deletion_items_server_terminal_worker_only "
        "BEFORE UPDATE ON public.account_deletion_items FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_guard_server_deletion_terminal_transition()"
    )


def downgrade() -> None:
    op.execute(
        "DROP TRIGGER IF EXISTS account_deletion_items_server_terminal_worker_only "
        "ON public.account_deletion_items"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_guard_server_deletion_terminal_transition()"
    )
    op.execute(
        f"REVOKE ALL PRIVILEGES ON TABLE public.reports, "
        "public.report_image_objects, public.account_deletion_tombstones, "
        "public.account_deletion_requests, public.account_deletion_items, "
        "public.account_deletion_events, public.account_deletion_receipts, "
        "public.account_deletion_device_targets, "
        f"public.privacy_consent_events FROM {_WORKER_ROLE}"
    )
    op.execute(f"REVOKE USAGE, CREATE ON SCHEMA public FROM {_WORKER_ROLE}")
    # PostgreSQL roles are cluster-wide. Another database may still use this
    # NOLOGIN role, so a database-local downgrade only revokes its own grants.
