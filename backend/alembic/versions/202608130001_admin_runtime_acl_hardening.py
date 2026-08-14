"""harden administrator runtime ACL and recovery startup binding

Revision ID: 202608130001
Revises: 202608120001
Create Date: 2026-08-13
"""

from __future__ import annotations

import sqlalchemy as sa
from alembic import op

revision = "202608130001"
down_revision = "202608120001"
branch_labels = None
depends_on = None


_PREDECESSOR_CONSTRAINT_HASHES = {
    "ck_walksafe_recovery_custody_capability_pending_next_binding": (
        "4daef80d3a9ceff997c2bf9ec02a7afe3af113cb1ab4bbe443623c298d44c8d7"
    ),
    "ck_walksafe_recovery_custody_capability_pending_next_is_new": (
        "2d2e29d271cd9ecf93ba39a94f2247836c22dd94cf8852b84a6b9bede8efe85f"
    ),
    "ck_walksafe_recovery_custody_capability_pending_next_totp": (
        "c4f3cff41dd386cd40148aff00a38429c4d975407bd98cebbcdbe9dd32447b34"
    ),
}

_PREDECESSOR_FUNCTIONS = {
    "walksafe_assert_admin_totp_capability(text, text, text)": (
        "boolean",
        True,
        "c6a95b20292cd519a5a346edbbbf9753b0158bbceb1264d9dc70d72d1d6e5b87",
    ),
    "walksafe_complete_admin_recovery_transaction(text, text, text, text, "
    "text, bigint, timestamp with time zone, text)": (
        "bigint",
        True,
        "83fcd058292ed8c2987f1e53a842a82cacef38a5232928783fac0cf580e083e5",
    ),
    "walksafe_consume_admin_reconfirmation(text, uuid, text, text, text, "
    "text, text, timestamp with time zone, timestamp with time zone, text, text)": (
        "timestamp with time zone",
        True,
        "c6b714190e3ca0c306a32b5e07ff6d386463620815ad796e294054eef0d79332",
    ),
    "walksafe_issue_admin_reconfirmation(uuid, text, uuid, text, text, text, "
    "text, text, timestamp with time zone, timestamp with time zone, bigint, "
    "bigint, text, text)": (
        "uuid",
        True,
        "53602987a84390d92b447a384e767515fbb9c89eb308476c9b556adb5e40d6ea",
    ),
    "walksafe_issue_admin_recovery_transaction(uuid, text, uuid, text, text, "
    "text, text, timestamp with time zone, timestamp with time zone, text, text)": (
        "uuid",
        True,
        "b0353ca01454ba22b07b8dbdbe8dc31352d8e38bdcc0b3bd472f186855680ab7",
    ),
    "walksafe_issue_admin_session(uuid, text, text, text, text, timestamp with "
    "time zone, timestamp with time zone, timestamp with time zone, timestamp "
    "with time zone, text, bigint, bigint, text, text)": (
        "uuid",
        True,
        "da4f50d8a725fec085af6ac01280a8cb640137df223323503cee3b869ce60b79",
    ),
    "walksafe_lock_admin_device_proof_context(text, text, bigint, text, "
    "timestamp with time zone, text, text, text)": (
        "TABLE(context_status text, public_key_spki_der bytea)",
        True,
        "b463f8617e13ca2ff8a789a1aeb52586dd0d000be3a01c90371c5edd5ba4bed1",
    ),
    "walksafe_lock_admin_original_access_session(text, uuid, text, timestamp "
    "with time zone, text, text)": (
        "boolean",
        True,
        "9c65b0a6958d4c6ebadd5cb76336c627130c88a48b1226f69bdc7d8a349d537d",
    ),
    "walksafe_lock_admin_security_control(text, text)": (
        "text",
        True,
        "2fdbbc85570cd098b635a8b40bdd8f40070623d768283eb70ae842d0db130c95",
    ),
    "walksafe_report_admin_lost_device(text, uuid, text, text, timestamp with "
    "time zone, text, text)": (
        (
            "TABLE(revoked_device_key_count bigint, revoked_session_count bigint, "
            "resulting_state_version bigint)"
        ),
        True,
        "f9b5717a658d814cbe190583d263967abc2bba3f53cc861d3d5536b673f64862",
    ),
    "walksafe_reset_recovery_custody(text, boolean, text, timestamp with time "
    "zone, boolean, text, text)": (
        "bigint",
        True,
        "70be43f00aa6a2827088fe1dd92f761d374f19626af947ec8812aa7962e1b787",
    ),
    "walksafe_resume_admin_recovery_transaction(uuid, text, timestamp with "
    "time zone, text, text)": (
        "void",
        True,
        "fe70bdb80d585432cb272faf493f8f01bca854e7fe8a2834263bbefc67369f79",
    ),
    "walksafe_revoke_admin_session(text, uuid, text, uuid, timestamp with time "
    "zone, text, text)": (
        "TABLE(session_found boolean, revoked boolean, revoked_device_id text)",
        True,
        "ba5aca347098b8f1de87b55cf269c14c09e55dbc781ff6b25f267e00755acc69",
    ),
    "walksafe_rotate_recovery_custody_capability(text, text, text, text)": (
        "void",
        False,
        "1819913a7967e8bf614eb20c98a4f7bb2b87471a3113240432d3dd3a3ef1d37a",
    ),
    "walksafe_touch_admin_session(text, uuid, text, timestamp with time zone, "
    "text, text)": (
        "boolean",
        True,
        "1ab68c11538ec9476dfadcf18fb234ba78bbbcad9a2ab879b6bb993a2138893d",
    ),
}


def _assert_current_predecessor_contract(connection) -> None:
    mismatches: list[str] = []
    column = connection.execute(
        sa.text(
            "SELECT format_type(atttypid, atttypmod), attnotnull "
            "FROM pg_attribute "
            "WHERE attrelid = "
            "'public.walksafe_recovery_custody_capabilities'::regclass "
            "AND attname = 'pending_next_totp_fingerprint' "
            "AND attnum > 0 AND NOT attisdropped"
        )
    ).one_or_none()
    if column != ("character varying(64)", False):
        mismatches.append("pending_next_totp_fingerprint column")

    constraints = dict(
        connection.execute(
            sa.text(
                "SELECT conname, encode(pg_catalog.sha256(pg_catalog.convert_to("
                "pg_get_expr(conbin, conrelid), 'UTF8')), 'hex') "
                "FROM pg_constraint "
                "WHERE conrelid = "
                "'public.walksafe_recovery_custody_capabilities'::regclass "
                "AND convalidated"
            )
        ).all()
    )
    if any(
        constraints.get(name) != expected_hash
        for name, expected_hash in _PREDECESSOR_CONSTRAINT_HASHES.items()
    ):
        mismatches.append("pending_next_totp_fingerprint constraints")

    expected_names = {signature.partition("(")[0] for signature in _PREDECESSOR_FUNCTIONS}
    functions = {}
    for row in connection.execute(
        sa.text(
            "SELECT p.proname || '(' || oidvectortypes(p.proargtypes) || ')', "
            "pg_get_function_result(p.oid), l.lanname, p.prosecdef, "
            "coalesce(array_to_string(p.proconfig, ','), ''), "
            "EXISTS (SELECT 1 FROM aclexplode(coalesce("
            "p.proacl, acldefault('f', p.proowner))) AS acl "
            "JOIN pg_roles AS role ON role.oid = acl.grantee "
            "WHERE role.rolname = 'walksafe_backend_runtime' "
            "AND acl.privilege_type = 'EXECUTE'), "
            "EXISTS (SELECT 1 FROM aclexplode(coalesce("
            "p.proacl, acldefault('f', p.proowner))) AS acl "
            "WHERE acl.grantee = 0 AND acl.privilege_type = 'EXECUTE'), "
            "encode(pg_catalog.sha256(pg_catalog.convert_to("
            "pg_get_functiondef(p.oid), 'UTF8')), 'hex') "
            "FROM pg_proc AS p "
            "JOIN pg_namespace AS n ON n.oid = p.pronamespace "
            "JOIN pg_language AS l ON l.oid = p.prolang "
            "WHERE n.nspname = 'public' "
            "AND p.proname LIKE 'walksafe\\_%' ESCAPE '\\' "
            "AND p.prokind = 'f'"
        )
    ).all():
        if row[0].partition("(")[0] in expected_names:
            functions[row[0]] = row[1:]

    if set(functions) != set(_PREDECESSOR_FUNCTIONS):
        mismatches.append("administrator helper function signatures")
    for signature, (result, runtime_execute, definition_hash) in (
        _PREDECESSOR_FUNCTIONS.items()
    ):
        if functions.get(signature) != (
            result,
            "plpgsql",
            True,
            "search_path=pg_catalog, pg_temp",
            runtime_execute,
            False,
            definition_hash,
        ):
            mismatches.append(signature)

    if mismatches:
        detail = ", ".join(dict.fromkeys(mismatches))
        raise RuntimeError(
            "database stamped 202608120001 does not match the current "
            "predecessor contract; recreate it with the current migrations "
            f"before upgrading: {detail}"
        )


def upgrade() -> None:
    _assert_current_predecessor_contract(op.get_bind())
    op.execute(
        "REVOKE UPDATE ON TABLE public.admin_device_proof_challenges "
        "FROM walksafe_backend_runtime"
    )
    op.execute(
        "GRANT UPDATE (consumed_at) ON TABLE "
        "public.admin_device_proof_challenges TO walksafe_backend_runtime"
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_require_admin_device_proof_consumption()
        RETURNS trigger AS $$
        BEGIN
          IF ROW(
            NEW.id,
            NEW.challenge_type,
            NEW.action,
            NEW.admin_id,
            NEW.body_sha256,
            NEW.correlation_id,
            NEW.device_id,
            NEW.device_key_marker,
            NEW.device_key_version,
            NEW.expires_at,
            NEW.issued_at,
            NEW.method,
            NEW.nonce,
            NEW.purpose,
            NEW.path,
            NEW.query_sha256,
            NEW.read_purpose,
            NEW.schema_version,
            NEW.session_id,
            NEW.signing_payload,
            NEW.created_at
          ) IS DISTINCT FROM ROW(
            OLD.id,
            OLD.challenge_type,
            OLD.action,
            OLD.admin_id,
            OLD.body_sha256,
            OLD.correlation_id,
            OLD.device_id,
            OLD.device_key_marker,
            OLD.device_key_version,
            OLD.expires_at,
            OLD.issued_at,
            OLD.method,
            OLD.nonce,
            OLD.purpose,
            OLD.path,
            OLD.query_sha256,
            OLD.read_purpose,
            OLD.schema_version,
            OLD.session_id,
            OLD.signing_payload,
            OLD.created_at
          )
            OR OLD.consumed_at IS NOT NULL
            OR NEW.consumed_at IS NULL
            OR NEW.consumed_at < NEW.issued_at
            OR NEW.consumed_at > NEW.expires_at
          THEN
            RAISE EXCEPTION
              'administrator device proof challenge may only be consumed once'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql SECURITY INVOKER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION "
        "walksafe_require_admin_device_proof_consumption() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "CREATE TRIGGER admin_device_proof_challenges_consume_once "
        "BEFORE UPDATE ON public.admin_device_proof_challenges FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_require_admin_device_proof_consumption()"
    )

    op.execute(
        """
        CREATE FUNCTION walksafe_classify_admin_startup_totp_binding(
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
          exact_recovery_binding_count bigint;
        BEGIN
          IF p_admin_id IS NULL
            OR p_runtime_totp_secret IS NULL
            OR pg_catalog.length(p_runtime_totp_secret) NOT BETWEEN 32 AND 512
            OR p_runtime_totp_secret !~ '^[A-Z2-7]+$'
            OR p_credential_issuer_key IS NULL
            OR pg_catalog.length(p_credential_issuer_key) <> 43
            OR p_credential_issuer_key !~ '^[A-Za-z0-9_-]{43}$'
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
            OR public_totp_fingerprint !~ '^[0-9a-f]{64}$'
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
            OR pending_recovery_token_sha256 !~ '^[0-9a-f]{64}$'
            OR pending_recovery_expires_at IS NULL
            OR pending_recovery_expires_at <= pg_catalog.statement_timestamp()
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
                 )
          INTO active_recovery_count, exact_recovery_binding_count
          FROM public.admin_security_recovery_transactions AS recovery
          WHERE recovery.admin_id = p_admin_id
            AND recovery.completed_at IS NULL;
          IF active_recovery_count <> 1 OR exact_recovery_binding_count <> 1 THEN
            RETURN 'INVALID';
          END IF;

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
        "walksafe_classify_admin_startup_totp_binding(text, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION "
        "walksafe_classify_admin_startup_totp_binding(text, text, text) "
        "TO walksafe_backend_runtime"
    )

    op.execute(
        """
        CREATE FUNCTION walksafe_mark_initial_unattested_recovery_start()
        RETURNS trigger AS $$
        DECLARE
          recovery_device_id text;
          recovery_device_key_preserved boolean;
          marker_details jsonb;
        BEGIN
          IF OLD.recovery_custody_state = 'UNATTESTED'
            AND NEW.recovery_custody_state = 'UNATTESTED'
            AND OLD.security_state <> 'RECOVERY_IN_PROGRESS'
            AND NEW.security_state = 'RECOVERY_IN_PROGRESS'
          THEN
            SELECT recovery.device_id
            INTO STRICT recovery_device_id
            FROM public.admin_security_recovery_transactions AS recovery
            JOIN public.walksafe_recovery_custody_capabilities AS capability
              ON capability.admin_id = recovery.admin_id
             AND capability.pending_recovery_token_sha256 =
               recovery.recovery_token_sha256
             AND capability.pending_recovery_expires_at IS NOT DISTINCT FROM
               recovery.expires_at
            WHERE recovery.admin_id = NEW.admin_id
              AND recovery.completed_at IS NULL
              AND recovery.expires_at > pg_catalog.statement_timestamp();

            SELECT pg_catalog.count(*) = 1
            INTO recovery_device_key_preserved
            FROM public.admin_device_keys AS device_key
            WHERE device_key.admin_id = NEW.admin_id
              AND device_key.device_id = recovery_device_id
              AND device_key.status = 'ACTIVE'
              AND device_key.revoked_at IS NULL;
            marker_details := pg_catalog.jsonb_build_object(
              'all_sessions_revoked', true,
              'custody_reset', true,
              'other_device_keys_revoked', true,
              'previous_custody_state', OLD.recovery_custody_state,
              'recovery_device_key_preserved', recovery_device_key_preserved
            );
            INSERT INTO public.walksafe_recovery_custody_markers (
              transaction_id,
              admin_id,
              action,
              previous_custody_state,
              next_custody_state,
              details
            ) VALUES (
              pg_catalog.pg_current_xact_id()::text::bigint,
              NEW.admin_id,
              'recovery.start',
              OLD.recovery_custody_state,
              NEW.recovery_custody_state,
              marker_details
            );
          END IF;
          RETURN NEW;
        EXCEPTION
          WHEN NO_DATA_FOUND OR TOO_MANY_ROWS THEN
            RAISE EXCEPTION 'initial recovery start binding is invalid'
              USING ERRCODE = '23514';
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION "
        "walksafe_mark_initial_unattested_recovery_start() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "CREATE TRIGGER admin_security_controls_initial_recovery_marker "
        "BEFORE UPDATE ON public.admin_security_controls FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_mark_initial_unattested_recovery_start()"
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_require_initial_unattested_recovery_audit()
        RETURNS trigger AS $$
        DECLARE
          marker_details jsonb;
          marker_count integer;
        BEGIN
          IF OLD.recovery_custody_state <> 'UNATTESTED'
            OR NEW.recovery_custody_state <> 'UNATTESTED'
            OR OLD.security_state = 'RECOVERY_IN_PROGRESS'
            OR NEW.security_state <> 'RECOVERY_IN_PROGRESS'
          THEN
            RETURN NEW;
          END IF;
          SELECT marker.details::jsonb
          INTO STRICT marker_details
          FROM public.walksafe_recovery_custody_markers AS marker
          WHERE marker.transaction_id =
              pg_catalog.pg_current_xact_id()::text::bigint
            AND marker.admin_id = NEW.admin_id
            AND marker.action = 'recovery.start'
            AND marker.previous_custody_state = 'UNATTESTED'
            AND marker.next_custody_state = 'UNATTESTED';
          DELETE FROM public.walksafe_recovery_custody_markers AS marker
          WHERE marker.transaction_id =
              pg_catalog.pg_current_xact_id()::text::bigint
            AND marker.admin_id = NEW.admin_id
            AND marker.action = 'recovery.start'
            AND marker.previous_custody_state = 'UNATTESTED'
            AND marker.next_custody_state = 'UNATTESTED'
            AND marker.details::jsonb = marker_details;
          GET DIAGNOSTICS marker_count = ROW_COUNT;
          IF marker_count <> 1 OR NOT EXISTS (
            SELECT 1
            FROM public.admin_security_audits AS audit
            WHERE audit.admin_id = NEW.admin_id
              AND audit.action = 'recovery.start'
              AND audit.outcome = 'SUCCESS'
              AND audit.details @> marker_details
              AND audit.xmin = pg_catalog.pg_current_xact_id()::pg_catalog.xid
          ) THEN
            RAISE EXCEPTION
              'initial recovery start requires a same-transaction security audit'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        EXCEPTION
          WHEN NO_DATA_FOUND OR TOO_MANY_ROWS THEN
            RAISE EXCEPTION
              'initial recovery start requires an authorized database marker'
              USING ERRCODE = '23514';
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION "
        "walksafe_require_initial_unattested_recovery_audit() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "CREATE CONSTRAINT TRIGGER "
        "admin_security_controls_initial_recovery_audited "
        "AFTER UPDATE ON public.admin_security_controls "
        "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_require_initial_unattested_recovery_audit()"
    )


def downgrade() -> None:
    op.execute(
        "DROP TRIGGER IF EXISTS "
        "admin_security_controls_initial_recovery_audited "
        "ON public.admin_security_controls"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "walksafe_require_initial_unattested_recovery_audit()"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS admin_security_controls_initial_recovery_marker "
        "ON public.admin_security_controls"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_mark_initial_unattested_recovery_start()"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "walksafe_classify_admin_startup_totp_binding(text, text, text)"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS admin_device_proof_challenges_consume_once "
        "ON public.admin_device_proof_challenges"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_require_admin_device_proof_consumption()"
    )
    op.execute(
        "REVOKE UPDATE (consumed_at) ON TABLE "
        "public.admin_device_proof_challenges FROM walksafe_backend_runtime"
    )
    op.execute(
        "GRANT UPDATE ON TABLE public.admin_device_proof_challenges "
        "TO walksafe_backend_runtime"
    )
