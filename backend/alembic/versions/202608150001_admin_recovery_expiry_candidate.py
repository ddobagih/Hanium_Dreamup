"""Allow an exact pending recovery TOTP candidate to expire its transaction.

Revision ID: 202608150001
Revises: 202608130001
"""

from alembic import op


revision = "202608150001"
down_revision = "202608130001"
branch_labels = None
depends_on = None


_EXPIRY_FUNCTION_AUTHORITY_SQL = """
        DO $walksafe_expiry_authority$
        DECLARE
          function_oid oid;
          owner_is_current boolean;
          public_execute boolean;
          runtime_execute boolean;
          unexpected_execute boolean;
        BEGIN
          function_oid := pg_catalog.to_regprocedure(
            'public.walksafe_expire_admin_recovery('
            'text,uuid,text,timestamp with time zone,text,text)'
          );
          IF function_oid IS NULL THEN
            RAISE EXCEPTION 'administrator recovery expiry function is missing'
              USING ERRCODE = '42501';
          END IF;
          SELECT
            procedure.proowner = (
              SELECT role.oid
              FROM pg_catalog.pg_roles AS role
              WHERE role.rolname = current_user
            ),
            EXISTS (
              SELECT 1
              FROM pg_catalog.aclexplode(coalesce(
                procedure.proacl,
                pg_catalog.acldefault('f', procedure.proowner)
              )) AS acl
              WHERE acl.grantee = 0
                AND acl.privilege_type = 'EXECUTE'
            ),
            EXISTS (
              SELECT 1
              FROM pg_catalog.aclexplode(coalesce(
                procedure.proacl,
                pg_catalog.acldefault('f', procedure.proowner)
              )) AS acl
              WHERE acl.grantee = 'walksafe_backend_runtime'::regrole::oid
                AND acl.privilege_type = 'EXECUTE'
                AND NOT acl.is_grantable
            ),
            EXISTS (
              SELECT 1
              FROM pg_catalog.aclexplode(coalesce(
                procedure.proacl,
                pg_catalog.acldefault('f', procedure.proowner)
              )) AS acl
              WHERE acl.grantee NOT IN (
                0,
                procedure.proowner,
                'walksafe_backend_runtime'::regrole::oid
              )
                AND acl.privilege_type = 'EXECUTE'
            )
          INTO owner_is_current,
               public_execute,
               runtime_execute,
               unexpected_execute
          FROM pg_catalog.pg_proc AS procedure
          WHERE procedure.oid = function_oid;
          IF owner_is_current IS DISTINCT FROM TRUE
            OR public_execute IS DISTINCT FROM FALSE
            OR runtime_execute IS DISTINCT FROM TRUE
            OR unexpected_execute IS DISTINCT FROM FALSE
          THEN
            RAISE EXCEPTION 'administrator recovery expiry authority differs'
              USING ERRCODE = '42501';
          END IF;
        END;
        $walksafe_expiry_authority$
        """


def _assert_expiry_function_authority() -> None:
    op.execute(_EXPIRY_FUNCTION_AUTHORITY_SQL)


def _install_expiry_function(*, allow_pending_candidate: bool) -> None:
    candidate_declarations = """
          pending_recovery_token_sha256 text;
          pending_recovery_expires_at timestamptz;
          pending_next_totp_fingerprint text;
          supplied_totp_fingerprint text;
          previous_totp_secret_fingerprint text;""" if allow_pending_candidate else ""
    candidate_fingerprint = """
          supplied_totp_fingerprint := pg_catalog.encode(
            pg_catalog.sha256(
              pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
            ),
            'hex'
          );""" if allow_pending_candidate else ""
    initial_capability_suffix = """
          ) AND (
            NOT public.walksafe_assert_admin_credential_issuer_key(
              p_admin_id,
              p_credential_issuer_key
            )
            OR NOT EXISTS (
              SELECT 1
              FROM public.admin_security_controls AS control
              JOIN public.walksafe_recovery_custody_capabilities AS capability
                ON capability.admin_id = control.admin_id
              JOIN public.admin_security_recovery_transactions AS recovery
                ON recovery.admin_id = control.admin_id
              WHERE control.admin_id = p_admin_id
                AND recovery.id = p_transaction_id
                AND recovery.recovery_token_sha256 = supplied_token_sha256
                AND recovery.completed_at IS NULL
                AND recovery.expires_at <= p_observed_at
                AND recovery.previous_totp_secret_fingerprint IS NOT DISTINCT
                  FROM capability.totp_secret_fingerprint
                AND control.totp_secret_fingerprint IS NOT DISTINCT FROM
                  capability.totp_secret_fingerprint
                AND capability.pending_recovery_token_sha256 IS NOT DISTINCT
                  FROM supplied_token_sha256
                AND capability.pending_recovery_expires_at IS NOT DISTINCT FROM
                  recovery.expires_at
                AND capability.pending_next_totp_fingerprint IS NOT DISTINCT
                  FROM supplied_totp_fingerprint
            )
          """ if allow_pending_candidate else ""
    capability_columns = """,
                 capability.pending_recovery_token_sha256,
                 capability.pending_recovery_expires_at,
                 capability.pending_next_totp_fingerprint""" if allow_pending_candidate else ""
    capability_targets = """,
                      pending_recovery_token_sha256,
                      pending_recovery_expires_at,
                      pending_next_totp_fingerprint""" if allow_pending_candidate else ""
    transaction_columns = (
        "recovery.expires_at,\n"
        "                 recovery.completed_at,\n"
        "                 recovery.previous_totp_secret_fingerprint"
        if allow_pending_candidate
        else "recovery.expires_at, recovery.completed_at"
    )
    transaction_targets = (
        "transaction_expires_at,\n"
        "               transaction_completed_at,\n"
        "               previous_totp_secret_fingerprint"
        if allow_pending_candidate
        else "transaction_expires_at, transaction_completed_at"
    )
    totp_predicate = """(
              supplied_totp_fingerprint IS DISTINCT FROM
                private_totp_fingerprint
              AND (
                supplied_totp_fingerprint IS DISTINCT FROM
                  pending_next_totp_fingerprint
                OR pending_recovery_token_sha256 IS DISTINCT FROM
                  supplied_token_sha256
                OR pending_recovery_expires_at IS DISTINCT FROM
                  transaction_expires_at
                OR previous_totp_secret_fingerprint IS DISTINCT FROM
                  private_totp_fingerprint
              )
            )""" if allow_pending_candidate else """pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
              ),
              'hex'
            ) IS DISTINCT FROM private_totp_fingerprint"""

    op.execute(
        f"""
        CREATE OR REPLACE FUNCTION public.walksafe_expire_admin_recovery(
          p_admin_id text,
          p_transaction_id uuid,
          p_recovery_token text,
          p_observed_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS text AS $$
        DECLARE
          current_security_state text;
          public_totp_fingerprint text;
          private_totp_fingerprint text;{candidate_declarations}
          supplied_token_sha256 text;
          transaction_expires_at timestamptz;
          transaction_completed_at timestamptz;
          newer_active_transaction_exists boolean;
          next_security_state text;
        BEGIN
          IF p_transaction_id IS NULL
            OR p_recovery_token IS NULL
            OR pg_catalog.length(p_recovery_token) < 1
            OR pg_catalog.length(p_recovery_token) > 512
            OR p_observed_at IS NULL
          THEN
            RAISE EXCEPTION 'administrator recovery expiry payload is invalid'
              USING ERRCODE = '22023';
          END IF;
          supplied_token_sha256 := pg_catalog.encode(
            pg_catalog.sha256(pg_catalog.convert_to(p_recovery_token, 'UTF8')),
            'hex'
          );{candidate_fingerprint}

          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          {initial_capability_suffix}) THEN
            RAISE EXCEPTION 'administrator recovery expiry capability is invalid'
              USING ERRCODE = '42501';
          END IF;

          SELECT control.security_state,
                 control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint{capability_columns}
          INTO STRICT current_security_state,
                      public_totp_fingerprint,
                      private_totp_fingerprint{capability_targets}
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability;

          SELECT {transaction_columns}
          INTO {transaction_targets}
          FROM public.admin_security_recovery_transactions AS recovery
          WHERE recovery.id = p_transaction_id
            AND recovery.admin_id = p_admin_id
            AND recovery.recovery_token_sha256 = supplied_token_sha256
          FOR UPDATE;

          IF NOT public.walksafe_assert_admin_credential_issuer_key(
              p_admin_id,
              p_credential_issuer_key
            )
            OR p_runtime_totp_secret IS NULL
            OR {totp_predicate}
            OR public_totp_fingerprint IS DISTINCT FROM private_totp_fingerprint
            OR current_security_state NOT IN (
              'RECOVERY_IN_PROGRESS', 'RECOVERY_REQUIRED'
            )
            OR transaction_expires_at IS NULL
            OR transaction_completed_at IS NOT NULL
            OR transaction_expires_at > p_observed_at
          THEN
            RAISE EXCEPTION 'administrator recovery expiry capability is invalid'
              USING ERRCODE = '42501';
          END IF;

          SELECT EXISTS (
            SELECT 1
            FROM public.admin_security_recovery_transactions AS recovery
            WHERE recovery.admin_id = p_admin_id
              AND recovery.id <> p_transaction_id
              AND recovery.completed_at IS NULL
              AND recovery.expires_at > p_observed_at
          ) INTO newer_active_transaction_exists;
          next_security_state := CASE
            WHEN newer_active_transaction_exists THEN 'RECOVERY_IN_PROGRESS'
            ELSE 'RECOVERY_REQUIRED'
          END;
          IF NOT newer_active_transaction_exists THEN
            UPDATE public.walksafe_recovery_custody_capabilities AS capability
            SET pending_recovery_token_sha256 = NULL,
                pending_recovery_expires_at = NULL,
                pending_next_totp_fingerprint = NULL
            WHERE capability.admin_id = p_admin_id
              AND capability.pending_recovery_token_sha256 =
                supplied_token_sha256;
          END IF;
          IF current_security_state <> next_security_state THEN
            UPDATE public.admin_security_controls AS control
            SET security_state = next_security_state,
                state_version = control.state_version + 1,
                updated_at = p_observed_at
            WHERE control.admin_id = p_admin_id;
          END IF;
          RETURN next_security_state;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_expire_admin_recovery("
        "text, uuid, text, timestamptz, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION public.walksafe_expire_admin_recovery("
        "text, uuid, text, timestamptz, text, text) "
        "TO walksafe_backend_runtime"
    )


def upgrade() -> None:
    _assert_expiry_function_authority()
    _install_expiry_function(allow_pending_candidate=True)
    _assert_expiry_function_authority()


_UNSAFE_DOWNGRADE_SQL = """
        DO $walksafe_expiry_downgrade_fence$
        DECLARE
          recovery_admin_id text;
          recovery_lock_key bigint;
        BEGIN
          FOR recovery_admin_id IN
            SELECT control.admin_id
            FROM public.admin_security_controls AS control
            ORDER BY control.admin_id
          LOOP
            recovery_lock_key := (
              ('x' || pg_catalog.substr(
                pg_catalog.encode(
                  pg_catalog.sha256(
                    pg_catalog.convert_to('recovery-state', 'UTF8')
                    || pg_catalog.decode('00', 'hex')
                    || pg_catalog.convert_to(recovery_admin_id, 'UTF8')
                  ),
                  'hex'
                ),
                1,
                16
              ))::bit(64)::bigint
            );
            PERFORM pg_catalog.pg_advisory_xact_lock(recovery_lock_key);
          END LOOP;
        END;
        $walksafe_expiry_downgrade_fence$;

        LOCK TABLE
          public.admin_security_controls,
          public.walksafe_recovery_custody_capabilities,
          public.admin_security_recovery_transactions
        IN SHARE ROW EXCLUSIVE MODE;

        DO $walksafe_expiry_downgrade$
        BEGIN
          IF EXISTS (
            SELECT 1
            FROM public.admin_security_controls AS control
            FULL JOIN public.walksafe_recovery_custody_capabilities AS capability
              ON capability.admin_id = control.admin_id
            WHERE control.security_state = 'RECOVERY_IN_PROGRESS'
              OR capability.pending_recovery_token_sha256 IS NOT NULL
              OR capability.pending_recovery_expires_at IS NOT NULL
              OR capability.pending_next_totp_fingerprint IS NOT NULL
          ) OR EXISTS (
            SELECT 1
            FROM public.admin_security_recovery_transactions AS recovery
            WHERE recovery.completed_at IS NULL
          ) THEN
            RAISE EXCEPTION
              'active administrator recovery must be cleared before downgrade'
              USING ERRCODE = '55000';
          END IF;
        END;
        $walksafe_expiry_downgrade$
        """


def _reject_unsafe_downgrade() -> None:
    op.execute(_UNSAFE_DOWNGRADE_SQL)


def downgrade() -> None:
    _assert_expiry_function_authority()
    _reject_unsafe_downgrade()
    _install_expiry_function(allow_pending_candidate=False)
    _assert_expiry_function_authority()
