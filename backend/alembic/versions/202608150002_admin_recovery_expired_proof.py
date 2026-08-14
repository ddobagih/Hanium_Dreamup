"""Permit an exact expired recovery proof to reach transaction cleanup.

Revision ID: 202608150002
Revises: 202608150001
"""

from alembic import op


revision = "202608150002"
down_revision = "202608150001"
branch_labels = None
depends_on = None


_PREDECESSOR_DEFINITION_SHA256 = (
    "b463f8617e13ca2ff8a789a1aeb52586dd0d000be3a01c90371c5edd5ba4bed1"
)
_SUCCESSOR_DEFINITION_SHA256 = (
    "0dcfddf07b0a6dca5289a6e6b8a05e8c5244dbbb2cd757676903f769147fb17f"
)
_CLASSIFIER_PREDECESSOR_DEFINITION_SHA256 = (
    "0aa6123f04fa5e4c0fce5055a28f24464dc14fb042ea35d29db71a7d51f9820d"
)
_CLASSIFIER_SUCCESSOR_DEFINITION_SHA256 = (
    "5cc6c5177be9b8e270b4965a7865d0518bed638c6f9c8f1096d8ea0acb8b232e"
)
_UNSAFE_DOWNGRADE_SQL = """
        DO $walksafe_expired_proof_downgrade_fence$
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
        $walksafe_expired_proof_downgrade_fence$;

        LOCK TABLE
          public.admin_security_controls,
          public.walksafe_recovery_custody_capabilities,
          public.admin_security_recovery_transactions
        IN SHARE ROW EXCLUSIVE MODE;

        DO $walksafe_expired_proof_downgrade$
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
        $walksafe_expired_proof_downgrade$
        """


def _device_proof_function_authority_sql(expected_sha256: str) -> str:
    return f"""
        DO $walksafe_device_proof_authority$
        DECLARE
          function_oid oid;
          owner_is_current boolean;
          security_contract boolean;
          public_execute boolean;
          runtime_execute boolean;
          unexpected_execute boolean;
          definition_sha256 text;
        BEGIN
          function_oid := pg_catalog.to_regprocedure(
            'public.walksafe_lock_admin_device_proof_context('
            'text,text,bigint,text,timestamp with time zone,text,text,text)'
          );
          IF function_oid IS NULL THEN
            RAISE EXCEPTION 'administrator device proof function is missing'
              USING ERRCODE = '42501';
          END IF;
          SELECT
            procedure.proowner = (
              SELECT role.oid
              FROM pg_catalog.pg_roles AS role
              WHERE role.rolname = current_user
            ),
            procedure.prosecdef
              AND language.lanname = 'plpgsql'
              AND pg_catalog.pg_get_function_result(procedure.oid) =
                'TABLE(context_status text, public_key_spki_der bytea)'
              AND procedure.proconfig IS NOT DISTINCT FROM
                ARRAY['search_path=pg_catalog, pg_temp']::text[],
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
            ),
            pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(
                  pg_catalog.pg_get_functiondef(procedure.oid),
                  'UTF8'
                )
              ),
              'hex'
            )
          INTO owner_is_current,
               security_contract,
               public_execute,
               runtime_execute,
               unexpected_execute,
               definition_sha256
          FROM pg_catalog.pg_proc AS procedure
          JOIN pg_catalog.pg_language AS language
            ON language.oid = procedure.prolang
          WHERE procedure.oid = function_oid;
          IF owner_is_current IS DISTINCT FROM TRUE
            OR security_contract IS DISTINCT FROM TRUE
            OR public_execute IS DISTINCT FROM FALSE
            OR runtime_execute IS DISTINCT FROM TRUE
            OR unexpected_execute IS DISTINCT FROM FALSE
            OR definition_sha256 IS DISTINCT FROM '{expected_sha256}'
          THEN
            RAISE EXCEPTION 'administrator device proof authority differs'
              USING ERRCODE = '42501';
          END IF;
        END;
        $walksafe_device_proof_authority$
        """


def _assert_device_proof_function_authority(expected_sha256: str) -> None:
    op.execute(_device_proof_function_authority_sql(expected_sha256))


def _classifier_function_authority_sql(expected_sha256: str) -> str:
    return f"""
        DO $walksafe_startup_classifier_authority$
        DECLARE
          function_oid oid;
          owner_is_current boolean;
          security_contract boolean;
          public_execute boolean;
          runtime_execute boolean;
          unexpected_execute boolean;
          definition_sha256 text;
        BEGIN
          function_oid := pg_catalog.to_regprocedure(
            'public.walksafe_classify_admin_startup_totp_binding(text,text,text)'
          );
          IF function_oid IS NULL THEN
            RAISE EXCEPTION 'administrator startup classifier is missing'
              USING ERRCODE = '42501';
          END IF;
          SELECT
            procedure.proowner = (
              SELECT role.oid
              FROM pg_catalog.pg_roles AS role
              WHERE role.rolname = current_user
            ),
            procedure.prosecdef
              AND language.lanname = 'plpgsql'
              AND pg_catalog.pg_get_function_result(procedure.oid) = 'text'
              AND procedure.proconfig IS NOT DISTINCT FROM
                ARRAY['search_path=pg_catalog, pg_temp']::text[],
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
            ),
            pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(
                  pg_catalog.pg_get_functiondef(procedure.oid),
                  'UTF8'
                )
              ),
              'hex'
            )
          INTO owner_is_current,
               security_contract,
               public_execute,
               runtime_execute,
               unexpected_execute,
               definition_sha256
          FROM pg_catalog.pg_proc AS procedure
          JOIN pg_catalog.pg_language AS language
            ON language.oid = procedure.prolang
          WHERE procedure.oid = function_oid;
          IF owner_is_current IS DISTINCT FROM TRUE
            OR security_contract IS DISTINCT FROM TRUE
            OR public_execute IS DISTINCT FROM FALSE
            OR runtime_execute IS DISTINCT FROM TRUE
            OR unexpected_execute IS DISTINCT FROM FALSE
            OR definition_sha256 IS DISTINCT FROM '{expected_sha256}'
          THEN
            RAISE EXCEPTION 'administrator startup classifier authority differs'
              USING ERRCODE = '42501';
          END IF;
        END;
        $walksafe_startup_classifier_authority$
        """


def _assert_classifier_function_authority(expected_sha256: str) -> None:
    op.execute(_classifier_function_authority_sql(expected_sha256))


def _install_startup_classifier(*, allow_expired_candidate: bool) -> None:
    extra_declaration = (
        "\n          exact_expired_binding_count bigint;"
        if allow_expired_candidate
        else ""
    )
    expiry_rejection = (
        ""
        if allow_expired_candidate
        else "\n            OR pending_recovery_expires_at <= "
        "pg_catalog.statement_timestamp()"
    )
    expired_aggregate = """,
                 pg_catalog.count(*) FILTER (
                   WHERE recovery.expires_at <= pg_catalog.statement_timestamp()
                     AND recovery.recovery_token_sha256 =
                       pending_recovery_token_sha256
                     AND recovery.expires_at IS NOT DISTINCT FROM
                       pending_recovery_expires_at
                     AND recovery.previous_totp_secret_fingerprint IS NOT
                       DISTINCT FROM private_totp_fingerprint
                 )""" if allow_expired_candidate else ""
    expired_target = (
        ", exact_expired_binding_count" if allow_expired_candidate else ""
    )
    if allow_expired_candidate:
        result_logic = """
          IF active_recovery_count = 0
            AND exact_recovery_binding_count = 0
            AND exact_expired_binding_count = 1
            AND pending_next_totp_fingerprint IS NOT DISTINCT FROM
              supplied_totp_fingerprint
          THEN
            RETURN 'RECOVERY_EXPIRED_CANDIDATE';
          END IF;
          IF active_recovery_count <> 1 OR exact_recovery_binding_count <> 1 THEN
            RETURN 'INVALID';
          END IF;"""
    else:
        result_logic = """
          IF active_recovery_count <> 1 OR exact_recovery_binding_count <> 1 THEN
            RETURN 'INVALID';
          END IF;"""

    op.execute(
        f"""
        CREATE OR REPLACE FUNCTION public.walksafe_classify_admin_startup_totp_binding(
          p_admin_id text,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS text AS $$
        DECLARE
          current_security_state text;
          public_totp_fingerprint text;
          private_totp_fingerprint text;
          pending_next_totp_fingerprint text;
          pending_recovery_token_sha256 text;
          pending_recovery_expires_at timestamptz;
          supplied_totp_fingerprint text;
          supplied_issuer_key_sha256 text;
          active_recovery_count bigint;
          exact_recovery_binding_count bigint;{extra_declaration}
        BEGIN
          IF p_admin_id IS NULL
            OR p_runtime_totp_secret IS NULL
            OR pg_catalog.length(p_runtime_totp_secret) NOT BETWEEN 32 AND 512
            OR p_runtime_totp_secret !~ '^[A-Z2-7]+$'
            OR p_credential_issuer_key IS NULL
            OR pg_catalog.length(p_credential_issuer_key) <> 43
            OR p_credential_issuer_key !~ '^[A-Za-z0-9_-]{{43}}$'
          THEN
            RETURN 'INVALID';
          END IF;
          supplied_totp_fingerprint := pg_catalog.encode(
            pg_catalog.sha256(
              pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
            ),
            'hex'
          );
          supplied_issuer_key_sha256 := pg_catalog.encode(
            pg_catalog.sha256(
              pg_catalog.convert_to(p_credential_issuer_key, 'UTF8')
            ),
            'hex'
          );

          SELECT control.security_state,
                 control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint,
                 capability.pending_next_totp_fingerprint,
                 capability.pending_recovery_token_sha256,
                 capability.pending_recovery_expires_at
          INTO STRICT current_security_state,
                      public_totp_fingerprint,
                      private_totp_fingerprint,
                      pending_next_totp_fingerprint,
                      pending_recovery_token_sha256,
                      pending_recovery_expires_at
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.singleton_scope IS TRUE
            AND control.admin_id = p_admin_id
            AND capability.issuer_key_sha256 = supplied_issuer_key_sha256
          FOR UPDATE OF control, capability;

          IF public_totp_fingerprint IS DISTINCT FROM private_totp_fingerprint
            OR public_totp_fingerprint !~ '^[0-9a-f]{{64}}$'
          THEN
            RETURN 'INVALID';
          END IF;
          IF supplied_totp_fingerprint = private_totp_fingerprint THEN
            IF pending_next_totp_fingerprint IS NULL
              OR current_security_state <> 'RECOVERY_IN_PROGRESS'
            THEN
              RETURN 'CURRENT';
            END IF;
            RETURN 'INVALID';
          END IF;
          IF current_security_state <> 'RECOVERY_IN_PROGRESS'
            OR pending_recovery_token_sha256 IS NULL
            OR pending_recovery_token_sha256 !~ '^[0-9a-f]{{64}}$'
            OR pending_recovery_expires_at IS NULL{expiry_rejection}
            OR supplied_totp_fingerprint = private_totp_fingerprint
            OR (
              pending_next_totp_fingerprint IS NOT NULL
              AND pending_next_totp_fingerprint IS DISTINCT FROM
                supplied_totp_fingerprint
            )
          THEN
            RETURN 'INVALID';
          END IF;

          PERFORM recovery.id
          FROM public.admin_security_recovery_transactions AS recovery
          WHERE recovery.admin_id = p_admin_id
            AND recovery.completed_at IS NULL
          ORDER BY recovery.started_at, recovery.id
          FOR UPDATE;
          SELECT pg_catalog.count(*) FILTER (
                   WHERE recovery.expires_at > pg_catalog.statement_timestamp()
                 ),
                 pg_catalog.count(*) FILTER (
                   WHERE recovery.expires_at > pg_catalog.statement_timestamp()
                     AND recovery.recovery_token_sha256 =
                       pending_recovery_token_sha256
                     AND recovery.expires_at IS NOT DISTINCT FROM
                       pending_recovery_expires_at
                     AND recovery.previous_totp_secret_fingerprint IS NOT
                       DISTINCT FROM private_totp_fingerprint
                 ){expired_aggregate}
          INTO active_recovery_count, exact_recovery_binding_count{expired_target}
          FROM public.admin_security_recovery_transactions AS recovery
          WHERE recovery.admin_id = p_admin_id
            AND recovery.completed_at IS NULL;{result_logic}

          IF pending_next_totp_fingerprint IS NULL THEN
            UPDATE public.walksafe_recovery_custody_capabilities AS capability
            SET pending_next_totp_fingerprint = supplied_totp_fingerprint
            WHERE capability.admin_id = p_admin_id
              AND capability.pending_next_totp_fingerprint IS NULL;
            IF NOT FOUND THEN
              RETURN 'INVALID';
            END IF;
          END IF;
          RETURN 'RECOVERY_CANDIDATE';
        EXCEPTION
          WHEN NO_DATA_FOUND OR TOO_MANY_ROWS THEN
            RETURN 'INVALID';
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION "
        "public.walksafe_classify_admin_startup_totp_binding(text, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION "
        "public.walksafe_classify_admin_startup_totp_binding(text, text, text) "
        "TO walksafe_backend_runtime"
    )


def _install_device_proof_context(*, expose_exact_expired_key: bool) -> None:
    extra_declarations = """
          pending_recovery_token_sha256 text;
          pending_recovery_expires_at timestamptz;
          exact_expired_recovery_count bigint;""" if expose_exact_expired_key else ""
    extra_capability_columns = """,
                 capability.pending_recovery_token_sha256,
                 capability.pending_recovery_expires_at""" if expose_exact_expired_key else ""
    extra_capability_targets = """,
                      pending_recovery_token_sha256,
                      pending_recovery_expires_at""" if expose_exact_expired_key else ""
    extra_expired_aggregate = """,
                   pg_catalog.count(*) FILTER (
                     WHERE recovery.expires_at <= p_observed_at
                       AND recovery.device_id = p_device_id
                       AND recovery.recovery_token_sha256 IS NOT DISTINCT FROM
                         pending_recovery_token_sha256
                       AND recovery.expires_at IS NOT DISTINCT FROM
                         pending_recovery_expires_at
                       AND recovery.previous_totp_secret_fingerprint IS NOT
                         DISTINCT FROM private_totp_fingerprint
                   )""" if expose_exact_expired_key else ""
    extra_expired_target = ",\n                 exact_expired_recovery_count" if expose_exact_expired_key else ""
    if expose_exact_expired_key:
        expired_branch = """
            IF active_recovery_count = 0
              AND requested_device_has_expired_recovery
            THEN
              IF pending_recovery_token_sha256 IS NOT NULL
                AND pending_recovery_expires_at IS NOT NULL
                AND pending_next_totp_fingerprint IS NOT DISTINCT FROM
                  supplied_totp_fingerprint
                AND exact_expired_recovery_count = 1
              THEN
                SELECT device_key.public_key_spki_der
                INTO public_key_spki_der
                FROM public.admin_device_keys AS device_key
                WHERE device_key.admin_id = p_admin_id
                  AND device_key.device_id = p_device_id
                  AND device_key.key_version = p_device_key_version
                  AND device_key.key_marker = p_device_key_marker
                  AND device_key.status = 'ACTIVE'
                  AND device_key.revoked_at IS NULL
                FOR UPDATE;
              ELSE
                public_key_spki_der := NULL;
              END IF;
              context_status := 'RECOVERY_EXPIRED';
              RETURN NEXT;
              RETURN;
            END IF;"""
    else:
        expired_branch = """
            IF active_recovery_count = 0
              AND requested_device_has_expired_recovery
            THEN
              context_status := 'RECOVERY_EXPIRED';
              public_key_spki_der := NULL;
              RETURN NEXT;
              RETURN;
            END IF;"""

    op.execute(
        f"""
        CREATE OR REPLACE FUNCTION public.walksafe_lock_admin_device_proof_context(
          p_admin_id text,
          p_device_id text,
          p_device_key_version bigint,
          p_device_key_marker text,
          p_observed_at timestamptz,
          p_purpose text,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS TABLE (
          context_status text,
          public_key_spki_der bytea
        ) AS $$
        DECLARE
          current_security_state text;
          public_totp_fingerprint text;
          private_totp_fingerprint text;
          pending_next_totp_fingerprint text;{extra_declarations}
          recovery_device_id text;
          active_recovery_count bigint;
          requested_device_has_expired_recovery boolean;
          supplied_issuer_key_sha256 text;
          supplied_totp_fingerprint text;
          recovery_lock_key bigint;
        BEGIN
          IF p_admin_id IS NULL
            OR p_device_id IS NULL
            OR p_device_key_version IS NULL
            OR p_device_key_version < 1
            OR p_device_key_marker IS NULL
            OR p_device_key_marker !~ '^[0-9a-f]{{64}}$'
            OR p_observed_at IS NULL
            OR p_purpose NOT IN ('LOGIN', 'ACTION', 'READ', 'RECOVERY_COMPLETE')
            OR p_runtime_totp_secret IS NULL
            OR p_credential_issuer_key IS NULL
            OR pg_catalog.length(p_credential_issuer_key) <> 43
            OR p_credential_issuer_key !~ '^[A-Za-z0-9_-]{{43}}$'
          THEN
            RAISE EXCEPTION
              'administrator device proof capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          supplied_issuer_key_sha256 := pg_catalog.encode(
            pg_catalog.sha256(
              pg_catalog.convert_to(p_credential_issuer_key, 'UTF8')
            ),
            'hex'
          );
          supplied_totp_fingerprint := pg_catalog.encode(
            pg_catalog.sha256(
              pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
            ),
            'hex'
          );
          IF NOT EXISTS (
            SELECT 1
            FROM public.walksafe_recovery_custody_capabilities AS capability
            WHERE capability.admin_id = p_admin_id
              AND capability.issuer_key_sha256 = supplied_issuer_key_sha256
              AND (
                (
                  p_purpose <> 'RECOVERY_COMPLETE'
                  AND capability.totp_secret_fingerprint =
                    supplied_totp_fingerprint
                )
                OR (
                  p_purpose = 'RECOVERY_COMPLETE'
                  AND capability.totp_secret_fingerprint <>
                    supplied_totp_fingerprint
                  AND (
                    capability.pending_next_totp_fingerprint IS NULL
                    OR capability.pending_next_totp_fingerprint =
                      supplied_totp_fingerprint
                  )
                )
              )
          ) THEN
            RAISE EXCEPTION
              'administrator device proof capability is invalid'
              USING ERRCODE = '42501';
          END IF;

          IF p_purpose = 'RECOVERY_COMPLETE' THEN
            recovery_lock_key := (
              ('x' || pg_catalog.substr(
                pg_catalog.encode(
                  pg_catalog.sha256(
                    pg_catalog.convert_to('recovery-state', 'UTF8')
                    || pg_catalog.decode('00', 'hex')
                    || pg_catalog.convert_to(p_admin_id, 'UTF8')
                  ),
                  'hex'
                ),
                1,
                16
              ))::bit(64)::bigint
            );
            PERFORM pg_catalog.pg_advisory_xact_lock(recovery_lock_key);
          END IF;

          SELECT control.security_state,
                 control.totp_secret_fingerprint
          INTO STRICT current_security_state,
                      public_totp_fingerprint
          FROM public.admin_security_controls AS control
          WHERE control.singleton_scope IS TRUE
            AND control.admin_id = p_admin_id
            AND (
              p_purpose = 'RECOVERY_COMPLETE'
              OR control.totp_secret_fingerprint = supplied_totp_fingerprint
            )
          FOR UPDATE;

          SELECT capability.totp_secret_fingerprint,
                 capability.pending_next_totp_fingerprint{extra_capability_columns}
          INTO STRICT private_totp_fingerprint,
                      pending_next_totp_fingerprint{extra_capability_targets}
          FROM public.walksafe_recovery_custody_capabilities AS capability
          WHERE capability.admin_id = p_admin_id
            AND capability.issuer_key_sha256 = supplied_issuer_key_sha256
            AND (
              (
                p_purpose <> 'RECOVERY_COMPLETE'
                AND capability.totp_secret_fingerprint =
                  supplied_totp_fingerprint
              )
              OR (
                p_purpose = 'RECOVERY_COMPLETE'
                AND capability.totp_secret_fingerprint <>
                  supplied_totp_fingerprint
                AND (
                  capability.pending_next_totp_fingerprint IS NULL
                  OR capability.pending_next_totp_fingerprint =
                    supplied_totp_fingerprint
                )
              )
            )
          FOR UPDATE;

          IF public_totp_fingerprint IS DISTINCT FROM private_totp_fingerprint
          THEN
            context_status := 'INVALID';
            public_key_spki_der := NULL;
            RETURN NEXT;
            RETURN;
          END IF;

          IF p_purpose = 'RECOVERY_COMPLETE' THEN
            IF current_security_state <> 'RECOVERY_IN_PROGRESS' THEN
              context_status := 'INVALID';
              public_key_spki_der := NULL;
              RETURN NEXT;
              RETURN;
            END IF;
            PERFORM recovery.id
            FROM public.admin_security_recovery_transactions AS recovery
            WHERE recovery.admin_id = p_admin_id
              AND recovery.completed_at IS NULL
            ORDER BY recovery.started_at, recovery.id
            FOR UPDATE;

            SELECT pg_catalog.count(*) FILTER (
                     WHERE recovery.expires_at > p_observed_at
                   ),
                   pg_catalog.max(recovery.device_id) FILTER (
                     WHERE recovery.expires_at > p_observed_at
                   ),
                   COALESCE(
                     pg_catalog.bool_or(
                       recovery.expires_at <= p_observed_at
                       AND recovery.device_id = p_device_id
                     ),
                     false
                   ){extra_expired_aggregate}
            INTO active_recovery_count,
                 recovery_device_id,
                 requested_device_has_expired_recovery{extra_expired_target}
            FROM public.admin_security_recovery_transactions AS recovery
            WHERE recovery.admin_id = p_admin_id
              AND recovery.completed_at IS NULL;{expired_branch}
            IF active_recovery_count <> 1
              OR recovery_device_id IS DISTINCT FROM p_device_id
            THEN
              context_status := 'INVALID';
              public_key_spki_der := NULL;
              RETURN NEXT;
              RETURN;
            END IF;
          ELSIF current_security_state <> 'NORMAL' THEN
            context_status := 'INVALID';
            public_key_spki_der := NULL;
            RETURN NEXT;
            RETURN;
          END IF;

          SELECT device_key.public_key_spki_der
          INTO public_key_spki_der
          FROM public.admin_device_keys AS device_key
          WHERE device_key.admin_id = p_admin_id
            AND device_key.device_id = p_device_id
            AND device_key.key_version = p_device_key_version
            AND device_key.key_marker = p_device_key_marker
            AND device_key.status = 'ACTIVE'
            AND device_key.revoked_at IS NULL
          FOR UPDATE;
          context_status := CASE
            WHEN public_key_spki_der IS NULL THEN 'KEY_INACTIVE'
            ELSE 'OK'
          END;
          IF context_status = 'OK'
            AND p_purpose = 'RECOVERY_COMPLETE'
            AND pending_next_totp_fingerprint IS NULL
          THEN
            UPDATE public.walksafe_recovery_custody_capabilities AS capability
            SET pending_next_totp_fingerprint = supplied_totp_fingerprint
            WHERE capability.admin_id = p_admin_id;
          END IF;
          RETURN NEXT;
        EXCEPTION
          WHEN NO_DATA_FOUND OR TOO_MANY_ROWS THEN
            context_status := 'INVALID';
            public_key_spki_der := NULL;
            RETURN NEXT;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION public.walksafe_lock_admin_device_proof_context("
        "text, text, bigint, text, timestamptz, text, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION public.walksafe_lock_admin_device_proof_context("
        "text, text, bigint, text, timestamptz, text, text, text) "
        "TO walksafe_backend_runtime"
    )


def _reject_unsafe_downgrade() -> None:
    op.execute(_UNSAFE_DOWNGRADE_SQL)


def upgrade() -> None:
    _assert_device_proof_function_authority(_PREDECESSOR_DEFINITION_SHA256)
    _assert_classifier_function_authority(
        _CLASSIFIER_PREDECESSOR_DEFINITION_SHA256
    )
    _install_startup_classifier(allow_expired_candidate=True)
    _install_device_proof_context(expose_exact_expired_key=True)
    _assert_device_proof_function_authority(_SUCCESSOR_DEFINITION_SHA256)
    _assert_classifier_function_authority(
        _CLASSIFIER_SUCCESSOR_DEFINITION_SHA256
    )


def downgrade() -> None:
    _assert_device_proof_function_authority(_SUCCESSOR_DEFINITION_SHA256)
    _assert_classifier_function_authority(
        _CLASSIFIER_SUCCESSOR_DEFINITION_SHA256
    )
    _reject_unsafe_downgrade()
    _install_startup_classifier(allow_expired_candidate=False)
    _install_device_proof_context(expose_exact_expired_key=False)
    _assert_device_proof_function_authority(_PREDECESSOR_DEFINITION_SHA256)
    _assert_classifier_function_authority(
        _CLASSIFIER_PREDECESSOR_DEFINITION_SHA256
    )
