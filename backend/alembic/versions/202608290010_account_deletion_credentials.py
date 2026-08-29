"""bind account deletion to credentials and one live enrollment

Revision ID: 202608290010
Revises: 202608290009
Create Date: 2026-08-29
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "202608290010"
down_revision = "202608290009"
branch_labels = None
depends_on = None


_RUNTIME_ROLE = "walksafe_backend_runtime"
_DELETION_WORKER_ROLE = "walksafe_account_deletion_worker"
_ENROLLMENT_PURGER_ROLE = "walksafe_account_enrollment_purger"
_ACCOUNT_ONLY_BASIS = "no_synchronized_installations_at_acceptance"


def _replace_event_validator_fragment(old: str, new: str) -> None:
    op.execute(
        f"""
        DO $migration$
        DECLARE
          definition text;
        BEGIN
          SELECT pg_get_functiondef(
                   'public.walksafe_validate_account_deletion_event()'::regprocedure
                 )
            INTO definition;
          IF definition IS NULL OR strpos(definition, $old${old}$old$) = 0 THEN
            RAISE EXCEPTION 'account deletion event validator differs from expected';
          END IF;
          definition := replace(definition, $old${old}$old$, $new${new}$new$);
          EXECUTE definition;
        END
        $migration$
        """
    )


def _install_enrollment_transition_guard(*, supersession: bool) -> None:
    pending_states = (
        "'PENDING_DELIVERY', 'ACTIVE', 'DELIVERY_FAILED', 'EXPIRED'"
        if supersession
        else "'ACTIVE', 'DELIVERY_FAILED'"
    )
    failed_states = "NOT IN ('PENDING_DELIVERY', 'EXPIRED')" if supersession else "<> 'PENDING_DELIVERY'"
    op.execute(
        f"""
        CREATE OR REPLACE FUNCTION public.walksafe_guard_account_enrollment_transition()
        RETURNS trigger
        LANGUAGE plpgsql
        AS $guard$
        BEGIN
            IF OLD.state IN ('EXPIRED', 'EXHAUSTED', 'CONSUMED') THEN
                RAISE EXCEPTION 'terminal account enrollment is immutable';
            END IF;
            IF NEW.id IS DISTINCT FROM OLD.id
               OR NEW.request_id IS DISTINCT FROM OLD.request_id
               OR NEW.request_hmac IS DISTINCT FROM OLD.request_hmac
               OR NEW.enrollment_handle IS DISTINCT FROM OLD.enrollment_handle
               OR NEW.email_lookup_hmac IS DISTINCT FROM OLD.email_lookup_hmac
               OR NEW.email_ciphertext IS DISTINCT FROM OLD.email_ciphertext
               OR NEW.email_nonce IS DISTINCT FROM OLD.email_nonce
               OR NEW.email_key_version IS DISTINCT FROM OLD.email_key_version
               OR NEW.max_attempts IS DISTINCT FROM OLD.max_attempts
               OR NEW.created_at IS DISTINCT FROM OLD.created_at
               OR NEW.state_version <> OLD.state_version + 1 THEN
                RAISE EXCEPTION 'account enrollment immutable or CAS columns changed';
            END IF;
            IF OLD.state = 'PENDING_DELIVERY'
               AND NEW.state NOT IN ({pending_states}) THEN
                RAISE EXCEPTION 'invalid account enrollment delivery transition';
            ELSIF OLD.state = 'DELIVERY_FAILED'
               AND NEW.state {failed_states} THEN
                RAISE EXCEPTION 'invalid account enrollment retry transition';
            ELSIF OLD.state = 'ACTIVE'
               AND NEW.state NOT IN ('ACTIVE', 'EXPIRED', 'EXHAUSTED', 'CONSUMED') THEN
                RAISE EXCEPTION 'invalid account enrollment verification transition';
            END IF;
            RETURN NEW;
        END;
        $guard$
        """
    )


def upgrade() -> None:
    op.create_table(
        "account_crypto_key_bindings",
        sa.Column("binding_id", sa.SmallInteger(), nullable=False),
        sa.Column("key_version", sa.BigInteger(), nullable=False),
        sa.Column("encryption_key_fingerprint", sa.String(length=64), nullable=False),
        sa.Column("lookup_hmac_key_fingerprint", sa.String(length=64), nullable=False),
        sa.Column("otp_hmac_key_fingerprint", sa.String(length=64), nullable=False),
        sa.Column(
            "bound_at",
            sa.DateTime(timezone=True),
            server_default=sa.text("clock_timestamp()"),
            nullable=False,
        ),
        sa.CheckConstraint(
            "binding_id = 1 AND key_version >= 1",
            name="ck_account_crypto_key_bindings_singleton",
        ),
        sa.CheckConstraint(
            "encryption_key_fingerprint ~ '^[0-9a-f]{64}$' AND "
            "lookup_hmac_key_fingerprint ~ '^[0-9a-f]{64}$' AND "
            "otp_hmac_key_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_account_crypto_key_bindings_fingerprints",
        ),
        sa.PrimaryKeyConstraint("binding_id"),
    )
    op.execute(
        "CREATE TRIGGER account_crypto_key_bindings_append_only "
        "BEFORE UPDATE OR DELETE ON public.account_crypto_key_bindings "
        "FOR EACH ROW EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(
        "CREATE TRIGGER account_crypto_key_bindings_no_truncate "
        "BEFORE TRUNCATE ON public.account_crypto_key_bindings "
        "FOR EACH STATEMENT EXECUTE FUNCTION walksafe_reject_audit_mutation()"
    )
    op.execute(
        "REVOKE ALL ON TABLE public.account_crypto_key_bindings "
        f"FROM PUBLIC, {_RUNTIME_ROLE}"
    )
    op.execute(
        "GRANT SELECT, INSERT ON TABLE public.account_crypto_key_bindings "
        f"TO {_RUNTIME_ROLE}"
    )

    op.execute(
        """
        DO $$
        BEGIN
          IF EXISTS (
            SELECT 1
              FROM public.account_deletion_requests AS request
              JOIN public.user_accounts AS account
                ON account.id = request.credential_account_id
             WHERE account.auth_epoch >= 9007199254740991
          ) THEN
            RAISE EXCEPTION 'cannot fence a deletion-linked account with exhausted auth_epoch';
          END IF;
        END
        $$
        """
    )
    op.execute(
        """
        UPDATE public.user_accounts AS account
           SET status = 'DISABLED',
               auth_epoch = account.auth_epoch + 1,
               updated_at = clock_timestamp()
          FROM public.account_deletion_requests AS request
         WHERE request.credential_account_id = account.id
        """
    )

    _install_enrollment_transition_guard(supersession=True)
    op.execute(
        """
        WITH ranked AS (
          SELECT id,
                 row_number() OVER (
                   PARTITION BY email_lookup_hmac
                   ORDER BY created_at DESC, id DESC
                 ) AS live_rank
            FROM public.account_enrollments
           WHERE state IN ('PENDING_DELIVERY', 'ACTIVE', 'DELIVERY_FAILED')
        )
        UPDATE public.account_enrollments AS enrollment
           SET state = 'EXPIRED',
               state_version = enrollment.state_version + 1,
               otp_hmac = NULL,
               updated_at = clock_timestamp()
          FROM ranked
         WHERE ranked.id = enrollment.id
           AND ranked.live_rank > 1
        """
    )
    op.execute(
        "CREATE UNIQUE INDEX uq_account_enrollments_one_live_email "
        "ON public.account_enrollments (email_lookup_hmac) "
        "WHERE state IN ('PENDING_DELIVERY', 'ACTIVE', 'DELIVERY_FAILED')"
    )

    old_condition = "IF item_count <> 9 OR target_count < 1 OR EXISTS ("
    new_condition = f"""IF item_count <> 9 OR (
              target_count < 1 AND NOT EXISTS (
                SELECT 1
                  FROM public.account_deletion_requests AS account_only_request
                  JOIN public.account_deletion_items AS account_only_item
                    ON account_only_item.request_id = account_only_request.request_id
                 WHERE account_only_request.request_id = NEW.request_id
                   AND account_only_request.credential_account_id IS NOT NULL
                   AND account_only_item.item_key = 'device_untransmitted_data'
                   AND account_only_item.state = 'NOT_APPLICABLE'
                   AND account_only_item.disposition_basis = '{_ACCOUNT_ONLY_BASIS}'
                   AND account_only_item.evidence_sha256 IS NOT NULL
                   AND account_only_item.terminal_at = accepted
              )
            ) OR EXISTS ("""
    _replace_event_validator_fragment(old_condition, new_condition)
    old_state = "OR item.state IS DISTINCT FROM expected.initial_state"
    new_state = """OR item.state IS DISTINCT FROM CASE
                      WHEN expected.item_key = 'device_untransmitted_data'
                           AND target_count = 0
                        THEN 'NOT_APPLICABLE'
                      ELSE expected.initial_state
                    END"""
    _replace_event_validator_fragment(old_state, new_state)

    op.execute(
        "GRANT UPDATE (status, auth_epoch, updated_at) ON TABLE public.user_accounts "
        f"TO {_RUNTIME_ROLE}"
    )
    op.execute(
        "GRANT SELECT (id, privacy_subject_hmac, account_generation, "
        "email_lookup_hmac, status) ON TABLE public.user_accounts "
        f"TO {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "GRANT DELETE ON TABLE public.user_accounts "
        f"TO {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT (id, email_lookup_hmac) ON TABLE public.account_enrollments "
        f"TO {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "GRANT DELETE ON TABLE public.account_enrollments "
        f"TO {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "GRANT SELECT (account_id) ON TABLE public.signup_consent_receipts "
        f"TO {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        f"""
        DO $$
        DECLARE role_record record;
        BEGIN
          SELECT rolsuper, rolcreaterole, rolcreatedb, rolcanlogin,
                 rolreplication, rolbypassrls
            INTO role_record
            FROM pg_roles
           WHERE rolname = '{_ENROLLMENT_PURGER_ROLE}';
          IF NOT FOUND THEN
            CREATE ROLE {_ENROLLMENT_PURGER_ROLE}
              NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
              NOINHERIT NOREPLICATION NOBYPASSRLS;
          ELSIF role_record.rolsuper OR role_record.rolcreaterole
             OR role_record.rolcreatedb OR role_record.rolcanlogin
             OR role_record.rolreplication OR role_record.rolbypassrls THEN
            RAISE EXCEPTION '{_ENROLLMENT_PURGER_ROLE} has unsafe role attributes';
          END IF;
        END
        $$
        """
    )
    op.execute(
        f"GRANT USAGE ON SCHEMA public TO {_ENROLLMENT_PURGER_ROLE}"
    )
    op.execute(
        f"REVOKE CREATE ON SCHEMA public FROM {_ENROLLMENT_PURGER_ROLE}"
    )
    op.execute(
        "GRANT SELECT (id, state, expires_at, updated_at) "
        "ON TABLE public.account_enrollments "
        f"TO {_ENROLLMENT_PURGER_ROLE}"
    )
    op.execute(
        "GRANT DELETE ON TABLE public.account_enrollments "
        f"TO {_ENROLLMENT_PURGER_ROLE}"
    )
    op.execute(
        "REVOKE INSERT, UPDATE, TRUNCATE ON TABLE public.account_enrollments "
        f"FROM {_ENROLLMENT_PURGER_ROLE}"
    )


def downgrade() -> None:
    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE public.account_enrollments "
        f"FROM {_ENROLLMENT_PURGER_ROLE}"
    )
    op.execute(
        f"REVOKE USAGE, CREATE ON SCHEMA public FROM {_ENROLLMENT_PURGER_ROLE}"
    )
    op.execute(
        "REVOKE SELECT (account_id) ON TABLE public.signup_consent_receipts "
        f"FROM {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "REVOKE DELETE ON TABLE public.account_enrollments "
        f"FROM {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "REVOKE SELECT (id, email_lookup_hmac) ON TABLE public.account_enrollments "
        f"FROM {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "REVOKE DELETE ON TABLE public.user_accounts "
        f"FROM {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "REVOKE SELECT (id, privacy_subject_hmac, account_generation, "
        "email_lookup_hmac, status) ON TABLE public.user_accounts "
        f"FROM {_DELETION_WORKER_ROLE}"
    )
    op.execute(
        "REVOKE UPDATE (status, auth_epoch, updated_at) ON TABLE public.user_accounts "
        f"FROM {_RUNTIME_ROLE}"
    )

    new_state = """OR item.state IS DISTINCT FROM CASE
                      WHEN expected.item_key = 'device_untransmitted_data'
                           AND target_count = 0
                        THEN 'NOT_APPLICABLE'
                      ELSE expected.initial_state
                    END"""
    _replace_event_validator_fragment(
        new_state,
        "OR item.state IS DISTINCT FROM expected.initial_state",
    )
    new_condition = f"""IF item_count <> 9 OR (
              target_count < 1 AND NOT EXISTS (
                SELECT 1
                  FROM public.account_deletion_requests AS account_only_request
                  JOIN public.account_deletion_items AS account_only_item
                    ON account_only_item.request_id = account_only_request.request_id
                 WHERE account_only_request.request_id = NEW.request_id
                   AND account_only_request.credential_account_id IS NOT NULL
                   AND account_only_item.item_key = 'device_untransmitted_data'
                   AND account_only_item.state = 'NOT_APPLICABLE'
                   AND account_only_item.disposition_basis = '{_ACCOUNT_ONLY_BASIS}'
                   AND account_only_item.evidence_sha256 IS NOT NULL
                   AND account_only_item.terminal_at = accepted
              )
            ) OR EXISTS ("""
    _replace_event_validator_fragment(
        new_condition,
        "IF item_count <> 9 OR target_count < 1 OR EXISTS (",
    )

    op.drop_index(
        "uq_account_enrollments_one_live_email",
        table_name="account_enrollments",
    )
    _install_enrollment_transition_guard(supersession=False)
    op.execute(
        "REVOKE ALL ON TABLE public.account_crypto_key_bindings "
        f"FROM PUBLIC, {_RUNTIME_ROLE}"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS account_crypto_key_bindings_no_truncate "
        "ON public.account_crypto_key_bindings"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS account_crypto_key_bindings_append_only "
        "ON public.account_crypto_key_bindings"
    )
    op.drop_table("account_crypto_key_bindings")
    # Disabling and epoch increments fence accepted deletions and are intentionally
    # not reversed by a schema downgrade.
