"""add singleton recovery custody state

Revision ID: 202608120001
Revises: 202608090001
Create Date: 2026-08-12
"""

from __future__ import annotations

from alembic import op
import sqlalchemy as sa


revision = "202608120001"
down_revision = "202608090001"
branch_labels = None
depends_on = None


def _assert_single_admin_control(connection) -> None:
    control_count = int(
        connection.execute(
            sa.text("SELECT count(*) FROM admin_security_controls")
        ).scalar_one()
    )
    if control_count > 1:
        raise RuntimeError(
            "admin_security_controls contains multiple rows; "
            "refusing to choose or delete an administrator during migration"
        )


def upgrade() -> None:
    _assert_single_admin_control(op.get_bind())
    op.add_column(
        "admin_security_controls",
        sa.Column(
            "singleton_scope",
            sa.Boolean(),
            server_default=sa.text("true"),
            nullable=False,
        ),
    )
    op.add_column(
        "admin_security_controls",
        sa.Column(
            "recovery_custody_state",
            sa.String(length=16),
            server_default="UNATTESTED",
            nullable=False,
        ),
    )
    op.add_column(
        "admin_security_controls",
        sa.Column(
            "recovery_custody_attested_at",
            sa.DateTime(timezone=True),
            nullable=True,
        ),
    )
    op.add_column(
        "admin_security_controls",
        sa.Column(
            "recovery_custody_reference_sha256",
            sa.String(length=64),
            nullable=True,
        ),
    )
    op.add_column(
        "admin_security_controls",
        sa.Column(
            "recovery_custody_material_kind",
            sa.String(length=32),
            nullable=True,
        ),
    )
    op.add_column(
        "admin_security_controls",
        sa.Column(
            "recovery_custody_storage_location",
            sa.String(length=32),
            nullable=True,
        ),
    )
    op.add_column(
        "admin_security_controls",
        sa.Column(
            "recovery_custody_separate_backup_confirmed",
            sa.Boolean(),
            server_default=sa.text("false"),
            nullable=False,
        ),
    )
    op.create_check_constraint(
        "ck_admin_security_controls_singleton",
        "admin_security_controls",
        "singleton_scope",
    )
    op.create_unique_constraint(
        "uq_admin_security_controls_singleton",
        "admin_security_controls",
        ["singleton_scope"],
    )
    op.create_check_constraint(
        "ck_admin_security_controls_custody_state",
        "admin_security_controls",
        "recovery_custody_state IN ('UNATTESTED', 'ATTESTED')",
    )
    op.create_check_constraint(
        "ck_admin_security_controls_custody_reference",
        "admin_security_controls",
        "recovery_custody_reference_sha256 IS NULL OR "
        "recovery_custody_reference_sha256 ~ '^[0-9a-f]{64}$'",
    )
    op.create_check_constraint(
        "ck_admin_security_controls_custody_attestation",
        "admin_security_controls",
        "(recovery_custody_state = 'UNATTESTED' "
        "AND recovery_custody_attested_at IS NULL "
        "AND recovery_custody_reference_sha256 IS NULL "
        "AND recovery_custody_material_kind IS NULL "
        "AND recovery_custody_storage_location IS NULL "
        "AND NOT recovery_custody_separate_backup_confirmed) OR "
        "(recovery_custody_state = 'ATTESTED' "
        "AND recovery_custody_attested_at IS NOT NULL "
        "AND recovery_custody_reference_sha256 IS NOT NULL "
        "AND recovery_custody_material_kind IN ('RECOVERY_CODE', 'SECURITY_KEY') "
        "AND recovery_custody_storage_location = 'OFF_PHONE' "
        "AND recovery_custody_separate_backup_confirmed)",
    )
    op.create_table(
        "walksafe_recovery_custody_markers",
        sa.Column("transaction_id", sa.BigInteger(), nullable=False),
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("action", sa.String(length=64), nullable=False),
        sa.Column("previous_custody_state", sa.String(length=16), nullable=False),
        sa.Column("next_custody_state", sa.String(length=16), nullable=False),
        sa.Column("details", sa.JSON(), nullable=False),
        sa.PrimaryKeyConstraint(
            "transaction_id",
            "admin_id",
            "action",
            name="pk_walksafe_recovery_custody_markers",
        ),
    )
    op.create_table(
        "walksafe_recovery_custody_capabilities",
        sa.Column("admin_id", sa.String(length=64), nullable=False),
        sa.Column("totp_secret_fingerprint", sa.String(length=64), nullable=False),
        sa.Column("issuer_key_sha256", sa.String(length=64), nullable=True),
        sa.Column(
            "pending_recovery_token_sha256",
            sa.String(length=64),
            nullable=True,
        ),
        sa.Column(
            "pending_recovery_expires_at",
            sa.DateTime(timezone=True),
            nullable=True,
        ),
        sa.Column(
            "pending_next_totp_fingerprint",
            sa.String(length=64),
            nullable=True,
        ),
        sa.CheckConstraint(
            "totp_secret_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_walksafe_recovery_custody_capability_totp",
        ),
        sa.CheckConstraint(
            "issuer_key_sha256 IS NULL OR "
            "issuer_key_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_walksafe_recovery_custody_capability_issuer_key",
        ),
        sa.CheckConstraint(
            "pending_recovery_token_sha256 IS NULL OR "
            "pending_recovery_token_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_walksafe_recovery_custody_capability_token",
        ),
        sa.CheckConstraint(
            "(pending_recovery_token_sha256 IS NULL AND "
            "pending_recovery_expires_at IS NULL) OR "
            "(pending_recovery_token_sha256 IS NOT NULL AND "
            "pending_recovery_expires_at IS NOT NULL)",
            name="ck_walksafe_recovery_custody_capability_pending",
        ),
        sa.CheckConstraint(
            "pending_next_totp_fingerprint IS NULL OR "
            "pending_next_totp_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_walksafe_recovery_custody_capability_pending_next_totp",
        ),
        sa.CheckConstraint(
            "pending_next_totp_fingerprint IS NULL OR "
            "pending_next_totp_fingerprint <> totp_secret_fingerprint",
            name="ck_walksafe_recovery_custody_capability_pending_next_is_new",
        ),
        sa.CheckConstraint(
            "pending_next_totp_fingerprint IS NULL OR "
            "(pending_recovery_token_sha256 IS NOT NULL AND "
            "pending_recovery_expires_at IS NOT NULL)",
            name="ck_walksafe_recovery_custody_capability_pending_next_binding",
        ),
        sa.PrimaryKeyConstraint(
            "admin_id",
            name="pk_walksafe_recovery_custody_capabilities",
        ),
        sa.ForeignKeyConstraint(
            ["admin_id"],
            ["admin_security_controls.admin_id"],
            name="fk_walksafe_recovery_custody_capability_admin",
            ondelete="CASCADE",
        ),
    )
    op.execute(
        "INSERT INTO public.walksafe_recovery_custody_capabilities ("
        "admin_id, totp_secret_fingerprint) "
        "SELECT admin_id, totp_secret_fingerprint "
        "FROM public.admin_security_controls"
    )
    op.execute(
        "REVOKE ALL PRIVILEGES ON TABLE "
        "public.walksafe_recovery_custody_markers, "
        "public.walksafe_recovery_custody_capabilities "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE INSERT ON TABLE public.admin_security_controls "
        "FROM walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE UPDATE ON TABLE public.admin_security_controls "
        "FROM walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE INSERT ON TABLE public.admin_device_keys "
        "FROM walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE INSERT ON TABLE public.admin_security_sessions, "
        "public.admin_security_reconfirmations, "
        "public.admin_security_recovery_codes, "
        "public.admin_security_recovery_transactions "
        "FROM walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE UPDATE ON TABLE public.admin_security_sessions, "
        "public.admin_security_reconfirmations, "
        "public.admin_security_recovery_codes, "
        "public.admin_security_recovery_transactions "
        "FROM walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE SELECT ON TABLE public.admin_security_recovery_codes, "
        "public.admin_security_recovery_transactions "
        "FROM walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE UPDATE (last_seen_at, step_up_verified_at, revoked_at, "
        "revoked_reason) ON TABLE public.admin_security_sessions "
        "FROM walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE UPDATE (consumed_at) ON TABLE "
        "public.admin_security_reconfirmations FROM walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE UPDATE ON TABLE public.admin_device_keys "
        "FROM walksafe_backend_runtime"
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_require_admin_reconfirmation_consumption()
        RETURNS trigger AS $$
        BEGIN
          IF ROW(
            NEW.id,
            NEW.admin_id,
            NEW.session_id,
            NEW.device_id,
            NEW.action,
            NEW.method,
            NEW.path,
            NEW.nonce_sha256,
            NEW.verified_at,
            NEW.expires_at,
            NEW.created_at
          ) IS DISTINCT FROM ROW(
            OLD.id,
            OLD.admin_id,
            OLD.session_id,
            OLD.device_id,
            OLD.action,
            OLD.method,
            OLD.path,
            OLD.nonce_sha256,
            OLD.verified_at,
            OLD.expires_at,
            OLD.created_at
          )
            OR OLD.consumed_at IS NOT NULL
            OR NEW.consumed_at IS NULL
            OR NEW.consumed_at < NEW.verified_at
            OR NEW.consumed_at >= NEW.expires_at
          THEN
            RAISE EXCEPTION
              'administrator reconfirmation may only be consumed once'
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
        "walksafe_require_admin_reconfirmation_consumption() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "CREATE TRIGGER admin_security_reconfirmations_consume_once "
        "BEFORE UPDATE ON admin_security_reconfirmations FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_require_admin_reconfirmation_consumption()"
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_require_admin_session_transition()
        RETURNS trigger AS $$
        BEGIN
          IF ROW(
            NEW.id,
            NEW.admin_id,
            NEW.device_id,
            NEW.device_label,
            NEW.token_sha256,
            NEW.issued_at,
            NEW.expires_at,
            NEW.created_at
          ) IS DISTINCT FROM ROW(
            OLD.id,
            OLD.admin_id,
            OLD.device_id,
            OLD.device_label,
            OLD.token_sha256,
            OLD.issued_at,
            OLD.expires_at,
            OLD.created_at
          )
            OR NEW.last_seen_at < OLD.last_seen_at
            OR NEW.last_seen_at < NEW.issued_at
            OR NEW.last_seen_at >= NEW.expires_at
            OR NOT (
              NEW.step_up_verified_at IS NOT DISTINCT FROM
                OLD.step_up_verified_at
              OR (
                OLD.step_up_verified_at IS NOT NULL
                AND NEW.step_up_verified_at IS NULL
              )
            )
            OR NOT (
              ROW(NEW.revoked_at, NEW.revoked_reason) IS NOT DISTINCT FROM
                ROW(OLD.revoked_at, OLD.revoked_reason)
              OR (
                OLD.revoked_at IS NULL
                AND OLD.revoked_reason IS NULL
                AND NEW.revoked_at IS NOT NULL
                AND NEW.revoked_at >= NEW.issued_at
                AND NEW.revoked_reason IN (
                  'administrator_revoked',
                  'device_reported_lost',
                  'device_session_replaced',
                  'recovery_started'
                )
              )
            )
          THEN
            RAISE EXCEPTION
              'administrator session transition is invalid'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql SECURITY INVOKER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_require_admin_session_transition() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "CREATE TRIGGER admin_security_sessions_transition_only "
        "BEFORE UPDATE ON admin_security_sessions FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_require_admin_session_transition()"
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_require_admin_device_key_revocation()
        RETURNS trigger AS $$
        BEGIN
          IF ROW(
            NEW.id,
            NEW.admin_id,
            NEW.device_id,
            NEW.key_version,
            NEW.public_key_spki_der,
            NEW.key_marker,
            NEW.created_at
          ) IS DISTINCT FROM ROW(
            OLD.id,
            OLD.admin_id,
            OLD.device_id,
            OLD.key_version,
            OLD.public_key_spki_der,
            OLD.key_marker,
            OLD.created_at
          )
            OR OLD.status <> 'ACTIVE'
            OR OLD.revoked_at IS NOT NULL
            OR NEW.status <> 'REVOKED'
            OR NEW.revoked_at IS NULL
          THEN
            RAISE EXCEPTION
              'administrator device keys may only transition from active to revoked'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql SECURITY INVOKER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_require_admin_device_key_revocation() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "CREATE TRIGGER admin_device_keys_revocation_only "
        "BEFORE UPDATE ON admin_device_keys FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_require_admin_device_key_revocation()"
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_assert_admin_credential_issuer_key(
          p_admin_id text,
          p_credential_issuer_key text
        ) RETURNS boolean AS $$
        BEGIN
          IF p_admin_id IS NULL
            OR p_credential_issuer_key IS NULL
            OR pg_catalog.length(p_credential_issuer_key) <> 43
            OR p_credential_issuer_key !~ '^[A-Za-z0-9_-]{43}$'
          THEN
            RETURN false;
          END IF;
          RETURN pg_catalog.count(*) = 1
          FROM public.walksafe_recovery_custody_capabilities AS capability
          WHERE capability.admin_id = p_admin_id
            AND capability.issuer_key_sha256 = pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_credential_issuer_key, 'UTF8')
              ),
              'hex'
            );
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_bind_admin_credential_issuer_key(
          p_admin_id text,
          p_credential_issuer_key text
        ) RETURNS boolean AS $$
        DECLARE
          current_issuer_key_sha256 text;
          supplied_issuer_key_sha256 text;
        BEGIN
          IF p_credential_issuer_key IS NULL
            OR pg_catalog.length(p_credential_issuer_key) <> 43
            OR p_credential_issuer_key !~ '^[A-Za-z0-9_-]{43}$'
          THEN
            RAISE EXCEPTION 'administrator credential issuer key is invalid'
              USING ERRCODE = '22023';
          END IF;
          supplied_issuer_key_sha256 := pg_catalog.encode(
            pg_catalog.sha256(
              pg_catalog.convert_to(p_credential_issuer_key, 'UTF8')
            ),
            'hex'
          );
          SELECT capability.issuer_key_sha256
          INTO STRICT current_issuer_key_sha256
          FROM public.walksafe_recovery_custody_capabilities AS capability
          WHERE capability.admin_id = p_admin_id
          FOR UPDATE;

          IF current_issuer_key_sha256 IS NULL THEN
            UPDATE public.walksafe_recovery_custody_capabilities
            SET issuer_key_sha256 = supplied_issuer_key_sha256
            WHERE admin_id = p_admin_id;
            RETURN true;
          END IF;
          IF current_issuer_key_sha256 IS DISTINCT FROM
            supplied_issuer_key_sha256
          THEN
            RAISE EXCEPTION
              'administrator credential issuer key is already bound'
              USING ERRCODE = '42501';
          END IF;
          RETURN false;
        EXCEPTION
          WHEN NO_DATA_FOUND OR TOO_MANY_ROWS THEN
            RAISE EXCEPTION
              'administrator credential issuer binding target is invalid'
              USING ERRCODE = '42501';
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION "
        "walksafe_assert_admin_credential_issuer_key(text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION "
        "walksafe_assert_admin_credential_issuer_key(text, text) "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION "
        "walksafe_bind_admin_credential_issuer_key(text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_lock_admin_security_control(
          p_admin_id text,
          p_credential_issuer_key text
        )
        RETURNS text AS $$
        DECLARE
          locked_admin_id text;
          current_security_state text;
          current_state_version bigint;
          public_totp_fingerprint text;
          private_totp_fingerprint text;
          private_issuer_fingerprint text;
          current_custody_state text;
          custody_attested_at timestamptz;
          custody_reference_sha256 text;
          custody_material_kind text;
          custody_storage_location text;
          custody_separate_backup_confirmed boolean;
          supplied_issuer_key_sha256 text;
        BEGIN
          IF p_admin_id IS NULL
            OR p_credential_issuer_key IS NULL
            OR pg_catalog.length(p_credential_issuer_key) <> 43
            OR p_credential_issuer_key !~ '^[A-Za-z0-9_-]{43}$'
          THEN
            RAISE EXCEPTION 'administrator security control is invalid'
              USING ERRCODE = '42501';
          END IF;
          supplied_issuer_key_sha256 := pg_catalog.encode(
            pg_catalog.sha256(
              pg_catalog.convert_to(p_credential_issuer_key, 'UTF8')
            ),
            'hex'
          );
          SELECT control.admin_id,
                 control.security_state,
                 control.state_version,
                 control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint,
                 capability.issuer_key_sha256,
                 control.recovery_custody_state,
                 control.recovery_custody_attested_at,
                 control.recovery_custody_reference_sha256,
                 control.recovery_custody_material_kind,
                 control.recovery_custody_storage_location,
                 control.recovery_custody_separate_backup_confirmed
          INTO STRICT locked_admin_id,
                      current_security_state,
                      current_state_version,
                      public_totp_fingerprint,
                      private_totp_fingerprint,
                      private_issuer_fingerprint,
                      current_custody_state,
                      custody_attested_at,
                      custody_reference_sha256,
                      custody_material_kind,
                      custody_storage_location,
                      custody_separate_backup_confirmed
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.singleton_scope IS TRUE
            AND control.admin_id = p_admin_id
            AND capability.issuer_key_sha256 = supplied_issuer_key_sha256
          FOR UPDATE OF control, capability;

          IF (
            locked_admin_id IS NOT NULL
            AND locked_admin_id IS NOT DISTINCT FROM p_admin_id
            AND pg_catalog.length(locked_admin_id) BETWEEN 1 AND 64
            AND current_security_state IN (
              'NORMAL', 'RECOVERY_REQUIRED', 'RECOVERY_IN_PROGRESS'
            )
            AND current_state_version >= 1
            AND public_totp_fingerprint ~ '^[0-9a-f]{64}$'
            AND private_totp_fingerprint ~ '^[0-9a-f]{64}$'
            AND public_totp_fingerprint IS NOT DISTINCT FROM
              private_totp_fingerprint
            AND private_issuer_fingerprint ~ '^[0-9a-f]{64}$'
            AND private_issuer_fingerprint IS NOT DISTINCT FROM
              supplied_issuer_key_sha256
            AND (
              (
                current_custody_state = 'UNATTESTED'
                AND custody_attested_at IS NULL
                AND custody_reference_sha256 IS NULL
                AND custody_material_kind IS NULL
                AND custody_storage_location IS NULL
                AND custody_separate_backup_confirmed IS FALSE
              )
              OR (
                current_custody_state = 'ATTESTED'
                AND custody_attested_at IS NOT NULL
                AND custody_reference_sha256 ~ '^[0-9a-f]{64}$'
                AND custody_material_kind IN ('RECOVERY_CODE', 'SECURITY_KEY')
                AND custody_storage_location = 'OFF_PHONE'
                AND custody_separate_backup_confirmed IS TRUE
              )
            )
          ) IS NOT TRUE
          THEN
            RAISE EXCEPTION 'administrator security control is invalid'
              USING ERRCODE = '42501';
          END IF;
          RETURN locked_admin_id;
        EXCEPTION
          WHEN NO_DATA_FOUND OR TOO_MANY_ROWS THEN
            RAISE EXCEPTION 'administrator security control is invalid'
              USING ERRCODE = '42501';
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_lock_admin_security_control(text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_lock_admin_security_control(text, text) "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_assert_admin_totp_capability(
          p_admin_id text,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS boolean AS $$
        BEGIN
          IF p_admin_id IS NULL
            OR p_runtime_totp_secret IS NULL
            OR p_credential_issuer_key IS NULL
            OR pg_catalog.length(p_credential_issuer_key) <> 43
            OR p_credential_issuer_key !~ '^[A-Za-z0-9_-]{43}$'
          THEN
            RETURN false;
          END IF;
          RETURN pg_catalog.count(*) = 1
          FROM public.walksafe_recovery_custody_capabilities AS capability
          WHERE capability.admin_id = p_admin_id
            AND capability.issuer_key_sha256 = pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_credential_issuer_key, 'UTF8')
              ),
              'hex'
            )
            AND capability.totp_secret_fingerprint = pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
              ),
              'hex'
            );
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_lock_admin_original_access_session(
          p_admin_id text,
          p_session_id uuid,
          p_device_id text,
          p_observed_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS boolean AS $$
        DECLARE
          current_security_state text;
          public_totp_fingerprint text;
          private_totp_fingerprint text;
          current_custody_state text;
          custody_attested_at timestamptz;
          custody_reference_sha256 text;
          custody_material_kind text;
          custody_storage_location text;
          custody_separate_backup_confirmed boolean;
          session_device_id text;
          session_expires_at timestamptz;
          session_revoked_at timestamptz;
          supplied_issuer_key_sha256 text;
          supplied_totp_fingerprint text;
        BEGIN
          IF p_admin_id IS NULL
            OR p_session_id IS NULL
            OR p_device_id IS NULL
            OR p_observed_at IS NULL
            OR p_runtime_totp_secret IS NULL
            OR p_credential_issuer_key IS NULL
            OR pg_catalog.length(p_credential_issuer_key) <> 43
            OR p_credential_issuer_key !~ '^[A-Za-z0-9_-]{43}$'
          THEN
            RAISE EXCEPTION
              'administrator original access session capability is invalid'
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

          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION
              'administrator original access session capability is invalid'
              USING ERRCODE = '42501';
          END IF;

          BEGIN
            SELECT control.security_state,
                   control.totp_secret_fingerprint,
                   control.recovery_custody_state,
                   control.recovery_custody_attested_at,
                   control.recovery_custody_reference_sha256,
                   control.recovery_custody_material_kind,
                   control.recovery_custody_storage_location,
                   control.recovery_custody_separate_backup_confirmed
            INTO STRICT current_security_state,
                        public_totp_fingerprint,
                        current_custody_state,
                        custody_attested_at,
                        custody_reference_sha256,
                        custody_material_kind,
                        custody_storage_location,
                        custody_separate_backup_confirmed
            FROM public.admin_security_controls AS control
            WHERE control.singleton_scope IS TRUE
              AND control.admin_id = p_admin_id
              AND control.totp_secret_fingerprint = supplied_totp_fingerprint
            FOR UPDATE;

            SELECT capability.totp_secret_fingerprint
            INTO STRICT private_totp_fingerprint
            FROM public.walksafe_recovery_custody_capabilities AS capability
            WHERE capability.admin_id = p_admin_id
              AND capability.issuer_key_sha256 = supplied_issuer_key_sha256
              AND capability.totp_secret_fingerprint = supplied_totp_fingerprint
            FOR UPDATE;
          EXCEPTION
            WHEN NO_DATA_FOUND OR TOO_MANY_ROWS THEN
              RAISE EXCEPTION
                'administrator original access session capability is invalid'
                USING ERRCODE = '42501';
          END;

          IF current_security_state <> 'NORMAL'
            OR public_totp_fingerprint IS DISTINCT FROM
              private_totp_fingerprint
            OR current_custody_state <> 'ATTESTED'
            OR custody_attested_at IS NULL
            OR custody_reference_sha256 !~ '^[0-9a-f]{64}$'
            OR custody_material_kind NOT IN ('RECOVERY_CODE', 'SECURITY_KEY')
            OR custody_storage_location <> 'OFF_PHONE'
            OR custody_separate_backup_confirmed IS DISTINCT FROM true
          THEN
            RETURN false;
          END IF;

          SELECT session.device_id,
                 session.expires_at,
                 session.revoked_at
          INTO session_device_id,
               session_expires_at,
               session_revoked_at
          FROM public.admin_security_sessions AS session
          WHERE session.admin_id = p_admin_id
            AND session.id = p_session_id
            AND session.device_id = p_device_id
          FOR UPDATE;

          IF session_device_id IS DISTINCT FROM p_device_id
            OR session_revoked_at IS NOT NULL
            OR session_expires_at IS NULL
            OR session_expires_at <= p_observed_at
          THEN
            RETURN false;
          END IF;
          RETURN true;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_lock_admin_device_proof_context(
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
          pending_next_totp_fingerprint text;
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
            OR p_device_key_marker !~ '^[0-9a-f]{64}$'
            OR p_observed_at IS NULL
            OR p_purpose NOT IN ('LOGIN', 'ACTION', 'READ', 'RECOVERY_COMPLETE')
            OR p_runtime_totp_secret IS NULL
            OR p_credential_issuer_key IS NULL
            OR pg_catalog.length(p_credential_issuer_key) <> 43
            OR p_credential_issuer_key !~ '^[A-Za-z0-9_-]{43}$'
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
                 capability.pending_next_totp_fingerprint
          INTO STRICT private_totp_fingerprint,
                      pending_next_totp_fingerprint
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
                   )
            INTO active_recovery_count,
                 recovery_device_id,
                 requested_device_has_expired_recovery
            FROM public.admin_security_recovery_transactions AS recovery
            WHERE recovery.admin_id = p_admin_id
              AND recovery.completed_at IS NULL;
            IF active_recovery_count = 0
              AND requested_device_has_expired_recovery
            THEN
              context_status := 'RECOVERY_EXPIRED';
              public_key_spki_der := NULL;
              RETURN NEXT;
              RETURN;
            END IF;
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
        """
        CREATE FUNCTION walksafe_touch_admin_session(
          p_admin_id text,
          p_session_id uuid,
          p_device_id text,
          p_observed_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS boolean AS $$
        DECLARE
          locked_admin_id text;
          session_issued_at timestamptz;
          session_expires_at timestamptz;
          session_last_seen_at timestamptz;
          session_revoked_at timestamptz;
        BEGIN
          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION 'administrator session touch capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          locked_admin_id := public.walksafe_lock_admin_security_control(
            p_admin_id,
            p_credential_issuer_key
          );
          SELECT session.issued_at,
                 session.expires_at,
                 session.last_seen_at,
                 session.revoked_at
          INTO STRICT session_issued_at,
                      session_expires_at,
                      session_last_seen_at,
                      session_revoked_at
          FROM public.admin_security_sessions AS session
          WHERE session.id = p_session_id
            AND session.admin_id = p_admin_id
            AND session.device_id = p_device_id
          FOR UPDATE;

          IF locked_admin_id IS DISTINCT FROM p_admin_id
            OR NOT public.walksafe_assert_admin_totp_capability(
              p_admin_id,
              p_runtime_totp_secret,
              p_credential_issuer_key
            )
            OR p_observed_at IS NULL
            OR session_revoked_at IS NOT NULL
            OR p_observed_at < session_issued_at
            OR p_observed_at < session_last_seen_at
            OR p_observed_at >= session_expires_at
          THEN
            RAISE EXCEPTION 'administrator session touch capability is invalid'
              USING ERRCODE = '42501';
          END IF;

          UPDATE public.admin_security_sessions
          SET last_seen_at = p_observed_at
          WHERE id = p_session_id;
          RETURN true;
        EXCEPTION
          WHEN NO_DATA_FOUND OR TOO_MANY_ROWS THEN
            RAISE EXCEPTION 'administrator session touch capability is invalid'
              USING ERRCODE = '42501';
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_consume_admin_reconfirmation(
          p_admin_id text,
          p_session_id uuid,
          p_device_id text,
          p_action text,
          p_method text,
          p_path text,
          p_nonce_sha256 text,
          p_verified_after timestamptz,
          p_observed_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS timestamptz AS $$
        DECLARE
          locked_admin_id text;
          matched_verified_at timestamptz;
        BEGIN
          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION
              'administrator reconfirmation consumption capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          locked_admin_id := public.walksafe_lock_admin_security_control(
            p_admin_id,
            p_credential_issuer_key
          );
          IF locked_admin_id IS DISTINCT FROM p_admin_id
            OR NOT public.walksafe_assert_admin_totp_capability(
              p_admin_id,
              p_runtime_totp_secret,
              p_credential_issuer_key
            )
            OR p_nonce_sha256 IS NULL
            OR p_nonce_sha256 !~ '^[0-9a-f]{64}$'
            OR p_verified_after IS NULL
            OR p_observed_at IS NULL
            OR p_verified_after > p_observed_at
          THEN
            RAISE EXCEPTION
              'administrator reconfirmation consumption capability is invalid'
              USING ERRCODE = '42501';
          END IF;

          UPDATE public.admin_security_reconfirmations AS reconfirmation
          SET consumed_at = p_observed_at
          WHERE reconfirmation.session_id = p_session_id
            AND reconfirmation.admin_id = p_admin_id
            AND reconfirmation.device_id = p_device_id
            AND reconfirmation.action = p_action
            AND reconfirmation.method = p_method
            AND reconfirmation.path = p_path
            AND reconfirmation.nonce_sha256 = p_nonce_sha256
            AND reconfirmation.consumed_at IS NULL
            AND reconfirmation.verified_at >= p_verified_after
            AND reconfirmation.verified_at <= p_observed_at
            AND reconfirmation.expires_at > p_observed_at
          RETURNING reconfirmation.verified_at INTO matched_verified_at;
          RETURN matched_verified_at;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_revoke_admin_session(
          p_admin_id text,
          p_current_session_id uuid,
          p_current_device_id text,
          p_target_session_id uuid,
          p_observed_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS TABLE (
          session_found boolean,
          revoked boolean,
          revoked_device_id text
        ) AS $$
        DECLARE
          locked_admin_id text;
          current_security_state text;
          caller_device_id text;
          caller_expires_at timestamptz;
          caller_revoked_at timestamptz;
          target_revoked_at timestamptz;
        BEGIN
          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION 'administrator session revocation capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          locked_admin_id := public.walksafe_lock_admin_security_control(
            p_admin_id,
            p_credential_issuer_key
          );
          PERFORM session.id
          FROM public.admin_security_sessions AS session
          WHERE session.admin_id = p_admin_id
            AND session.id IN (p_current_session_id, p_target_session_id)
          ORDER BY session.id
          FOR UPDATE;

          SELECT control.security_state
          INTO STRICT current_security_state
          FROM public.admin_security_controls AS control
          WHERE control.admin_id = p_admin_id;
          SELECT session.device_id,
                 session.expires_at,
                 session.revoked_at
          INTO caller_device_id,
               caller_expires_at,
               caller_revoked_at
          FROM public.admin_security_sessions AS session
          WHERE session.id = p_current_session_id
            AND session.admin_id = p_admin_id;

          IF locked_admin_id IS DISTINCT FROM p_admin_id
            OR NOT public.walksafe_assert_admin_totp_capability(
              p_admin_id,
              p_runtime_totp_secret,
              p_credential_issuer_key
            )
            OR current_security_state <> 'NORMAL'
            OR p_observed_at IS NULL
            OR caller_device_id IS DISTINCT FROM p_current_device_id
            OR caller_revoked_at IS NOT NULL
            OR caller_expires_at <= p_observed_at
          THEN
            RAISE EXCEPTION 'administrator session revocation capability is invalid'
              USING ERRCODE = '42501';
          END IF;

          SELECT session.device_id, session.revoked_at
          INTO revoked_device_id, target_revoked_at
          FROM public.admin_security_sessions AS session
          WHERE session.id = p_target_session_id
            AND session.admin_id = p_admin_id;
          session_found := FOUND;
          revoked := false;
          IF session_found AND target_revoked_at IS NULL THEN
            UPDATE public.admin_security_sessions
            SET revoked_at = p_observed_at,
                revoked_reason = 'administrator_revoked'
            WHERE id = p_target_session_id;
            revoked := true;
          END IF;
          RETURN NEXT;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_issue_admin_session(
          p_session_id uuid,
          p_admin_id text,
          p_device_id text,
          p_device_label text,
          p_token_sha256 text,
          p_issued_at timestamptz,
          p_expires_at timestamptz,
          p_step_up_verified_at timestamptz,
          p_last_seen_at timestamptz,
          p_authentication_kind text,
          p_expected_last_totp_timecode bigint,
          p_next_last_totp_timecode bigint,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS uuid AS $$
        DECLARE
          current_security_state text;
          current_last_totp_timecode bigint;
          public_totp_fingerprint text;
          private_totp_fingerprint text;
        BEGIN
          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION 'administrator session issuance capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT control.security_state,
                 control.last_totp_timecode,
                 control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint
          INTO STRICT current_security_state,
                      current_last_totp_timecode,
                      public_totp_fingerprint,
                      private_totp_fingerprint
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability;

          IF NOT public.walksafe_assert_admin_credential_issuer_key(
              p_admin_id,
              p_credential_issuer_key
            )
            OR p_runtime_totp_secret IS NULL
            OR pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
              ),
              'hex'
            ) IS DISTINCT FROM private_totp_fingerprint
            OR public_totp_fingerprint IS DISTINCT FROM private_totp_fingerprint
            OR current_security_state <> 'NORMAL'
          THEN
            RAISE EXCEPTION 'administrator session issuance capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_session_id IS NULL
            OR p_device_id IS NULL
            OR pg_catalog.length(p_device_id) < 1
            OR pg_catalog.length(p_device_id) > 128
            OR p_device_label IS NULL
            OR pg_catalog.length(p_device_label) < 1
            OR pg_catalog.length(p_device_label) > 128
            OR p_token_sha256 IS NULL
            OR p_token_sha256 !~ '^[0-9a-f]{64}$'
            OR p_issued_at IS NULL
            OR p_expires_at IS NULL
            OR p_expires_at <= p_issued_at
            OR p_expires_at - p_issued_at > interval '24 hours'
            OR p_step_up_verified_at IS DISTINCT FROM p_issued_at
            OR p_last_seen_at IS DISTINCT FROM p_issued_at
            OR NOT (
              (
                p_authentication_kind = 'LOGIN'
                AND current_last_totp_timecode IS NOT DISTINCT FROM
                  p_expected_last_totp_timecode
                AND p_next_last_totp_timecode IS NOT NULL
                AND p_next_last_totp_timecode >= 0
                AND (
                  current_last_totp_timecode IS NULL
                  OR p_next_last_totp_timecode > current_last_totp_timecode
                )
              )
              OR (
                p_authentication_kind = 'RECOVERY_COMPLETE'
                AND current_last_totp_timecode IS NOT NULL
                AND p_expected_last_totp_timecode IS NOT DISTINCT FROM
                  current_last_totp_timecode
                AND p_next_last_totp_timecode IS NOT DISTINCT FROM
                  current_last_totp_timecode
              )
            )
          THEN
            RAISE EXCEPTION 'administrator session issuance payload is invalid'
              USING ERRCODE = '22023';
          END IF;
          IF EXISTS (
            SELECT 1
            FROM public.admin_security_sessions AS session
            WHERE session.admin_id = p_admin_id
              AND session.device_id = p_device_id
              AND session.revoked_at IS NULL
              AND session.issued_at > p_issued_at
          ) THEN
            RAISE EXCEPTION 'administrator session issuance is stale'
              USING ERRCODE = '42501';
          END IF;

          UPDATE public.admin_security_sessions
          SET revoked_at = p_issued_at,
              revoked_reason = 'device_session_replaced'
          WHERE admin_id = p_admin_id
            AND device_id = p_device_id
            AND revoked_at IS NULL;

          IF p_authentication_kind = 'LOGIN' THEN
            UPDATE public.admin_security_controls
            SET last_totp_timecode = p_next_last_totp_timecode,
                updated_at = p_issued_at
            WHERE admin_id = p_admin_id;
          END IF;

          INSERT INTO public.admin_security_sessions (
            id,
            admin_id,
            device_id,
            device_label,
            token_sha256,
            issued_at,
            expires_at,
            step_up_verified_at,
            last_seen_at
          ) VALUES (
            p_session_id,
            p_admin_id,
            p_device_id,
            p_device_label,
            p_token_sha256,
            p_issued_at,
            p_expires_at,
            p_step_up_verified_at,
            p_last_seen_at
          );
          RETURN p_session_id;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_issue_admin_reconfirmation(
          p_reconfirmation_id uuid,
          p_admin_id text,
          p_session_id uuid,
          p_device_id text,
          p_action text,
          p_method text,
          p_path text,
          p_nonce_sha256 text,
          p_verified_at timestamptz,
          p_expires_at timestamptz,
          p_expected_last_totp_timecode bigint,
          p_next_last_totp_timecode bigint,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS uuid AS $$
        DECLARE
          current_security_state text;
          current_last_totp_timecode bigint;
          public_totp_fingerprint text;
          private_totp_fingerprint text;
          session_issued_at timestamptz;
          session_expires_at timestamptz;
          session_step_up_verified_at timestamptz;
          session_last_seen_at timestamptz;
          session_revoked_at timestamptz;
        BEGIN
          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION
              'administrator reconfirmation issuance capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          PERFORM public.walksafe_lock_admin_security_control(
            p_admin_id,
            p_credential_issuer_key
          );
          SELECT control.security_state,
                 control.last_totp_timecode,
                 control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint,
                 session.issued_at,
                 session.expires_at,
                 session.step_up_verified_at,
                 session.last_seen_at,
                 session.revoked_at
          INTO STRICT current_security_state,
                      current_last_totp_timecode,
                      public_totp_fingerprint,
                      private_totp_fingerprint,
                      session_issued_at,
                      session_expires_at,
                      session_step_up_verified_at,
                      session_last_seen_at,
                      session_revoked_at
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          JOIN public.admin_security_sessions AS session
            ON session.id = p_session_id
           AND session.admin_id = control.admin_id
           AND session.device_id = p_device_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability, session;

          IF NOT public.walksafe_assert_admin_credential_issuer_key(
              p_admin_id,
              p_credential_issuer_key
            )
            OR p_runtime_totp_secret IS NULL
            OR pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
              ),
              'hex'
            ) IS DISTINCT FROM private_totp_fingerprint
            OR public_totp_fingerprint IS DISTINCT FROM private_totp_fingerprint
            OR current_security_state <> 'NORMAL'
            OR session_revoked_at IS NOT NULL
          THEN
            RAISE EXCEPTION
              'administrator reconfirmation issuance capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_reconfirmation_id IS NULL
            OR p_nonce_sha256 IS NULL
            OR p_nonce_sha256 !~ '^[0-9a-f]{64}$'
            OR p_verified_at IS NULL
            OR p_expires_at IS NULL
            OR p_verified_at < session_issued_at
            OR p_verified_at >= session_expires_at
            OR session_last_seen_at > p_verified_at
            OR p_expires_at <= p_verified_at
            OR p_expires_at - p_verified_at > interval '15 minutes'
            OR current_last_totp_timecode IS DISTINCT FROM
              p_expected_last_totp_timecode
            OR p_next_last_totp_timecode IS NULL
            OR p_next_last_totp_timecode < 0
            OR (
              current_last_totp_timecode IS NOT NULL
              AND p_next_last_totp_timecode <= current_last_totp_timecode
            )
            OR NOT (
              (p_action = 'report.export'
                AND p_method = 'GET'
                AND p_path = '/reports/export')
              OR (p_action = 'release.approval'
                AND p_method = 'POST'
                AND p_path = '/admin/operations/release-approvals')
              OR (p_action = 'privilege.change'
                AND p_method = 'POST'
                AND p_path = '/admin/operations/privilege-changes')
              OR (p_action = 'data.delete'
                AND p_method = 'POST'
                AND p_path = '/admin/operations/data-deletions')
              OR (p_action = 'report.status.patch'
                AND p_method = 'PATCH'
                AND p_path ~
                  '^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/status$')
              OR (p_action = 'report.original.grant'
                AND p_method = 'POST'
                AND p_path ~
                  '^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/original-access-grants$')
            )
          THEN
            RAISE EXCEPTION
              'administrator reconfirmation issuance payload is invalid'
              USING ERRCODE = '22023';
          END IF;

          UPDATE public.admin_security_reconfirmations
          SET consumed_at = p_verified_at
          WHERE session_id = p_session_id
            AND consumed_at IS NULL
            AND verified_at <= p_verified_at
            AND expires_at > p_verified_at;

          UPDATE public.admin_security_sessions
          SET step_up_verified_at = NULL,
              last_seen_at = p_verified_at
          WHERE id = p_session_id;

          UPDATE public.admin_security_controls
          SET last_totp_timecode = p_next_last_totp_timecode,
              updated_at = p_verified_at
          WHERE admin_id = p_admin_id;

          INSERT INTO public.admin_security_reconfirmations (
            id,
            admin_id,
            session_id,
            device_id,
            action,
            method,
            path,
            nonce_sha256,
            verified_at,
            expires_at
          ) VALUES (
            p_reconfirmation_id,
            p_admin_id,
            p_session_id,
            p_device_id,
            p_action,
            p_method,
            p_path,
            p_nonce_sha256,
            p_verified_at,
            p_expires_at
          );
          RETURN p_reconfirmation_id;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_report_admin_lost_device(
          p_admin_id text,
          p_current_session_id uuid,
          p_current_device_id text,
          p_target_device_id text,
          p_observed_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS TABLE (
          revoked_device_key_count bigint,
          revoked_session_count bigint,
          resulting_state_version bigint
        ) AS $$
        DECLARE
          current_security_state text;
          public_totp_fingerprint text;
          private_totp_fingerprint text;
          caller_device_id text;
          caller_expires_at timestamptz;
          caller_revoked_at timestamptz;
          target_exists boolean;
        BEGIN
          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION 'administrator lost-device capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          PERFORM public.walksafe_lock_admin_security_control(
            p_admin_id,
            p_credential_issuer_key
          );
          SELECT control.security_state,
                 control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint,
                 session.device_id,
                 session.expires_at,
                 session.revoked_at
          INTO STRICT current_security_state,
                      public_totp_fingerprint,
                      private_totp_fingerprint,
                      caller_device_id,
                      caller_expires_at,
                      caller_revoked_at
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          JOIN public.admin_security_sessions AS session
            ON session.id = p_current_session_id
           AND session.admin_id = control.admin_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability, session;

          IF NOT public.walksafe_assert_admin_credential_issuer_key(
              p_admin_id,
              p_credential_issuer_key
            )
            OR p_runtime_totp_secret IS NULL
            OR pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
              ),
              'hex'
            ) IS DISTINCT FROM private_totp_fingerprint
            OR public_totp_fingerprint IS DISTINCT FROM private_totp_fingerprint
            OR current_security_state <> 'NORMAL'
            OR p_observed_at IS NULL
            OR caller_revoked_at IS NOT NULL
            OR caller_expires_at <= p_observed_at
            OR caller_device_id IS DISTINCT FROM p_current_device_id
          THEN
            RAISE EXCEPTION 'administrator lost-device capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_current_device_id IS NULL
            OR pg_catalog.length(p_current_device_id) < 1
            OR pg_catalog.length(p_current_device_id) > 128
            OR p_target_device_id IS NULL
            OR pg_catalog.length(p_target_device_id) < 1
            OR pg_catalog.length(p_target_device_id) > 128
            OR p_target_device_id = p_current_device_id
          THEN
            RAISE EXCEPTION 'administrator lost-device payload is invalid'
              USING ERRCODE = '22023';
          END IF;

          SELECT EXISTS (
            SELECT 1
            FROM public.admin_device_keys AS device_key
            WHERE device_key.admin_id = p_admin_id
              AND device_key.device_id = p_target_device_id
            UNION ALL
            SELECT 1
            FROM public.admin_security_sessions AS target_session
            WHERE target_session.admin_id = p_admin_id
              AND target_session.device_id = p_target_device_id
          ) INTO target_exists;
          IF NOT target_exists THEN
            RAISE EXCEPTION 'administrator lost device does not exist'
              USING ERRCODE = '22023';
          END IF;

          UPDATE public.admin_security_sessions AS target_session
          SET revoked_at = p_observed_at,
              revoked_reason = 'device_reported_lost'
          WHERE target_session.admin_id = p_admin_id
            AND target_session.device_id = p_target_device_id
            AND target_session.revoked_at IS NULL;
          GET DIAGNOSTICS revoked_session_count = ROW_COUNT;

          UPDATE public.admin_device_keys AS device_key
          SET status = 'REVOKED',
              revoked_at = p_observed_at
          WHERE device_key.admin_id = p_admin_id
            AND device_key.device_id = p_target_device_id
            AND device_key.status = 'ACTIVE'
            AND device_key.revoked_at IS NULL;
          GET DIAGNOSTICS revoked_device_key_count = ROW_COUNT;

          IF revoked_device_key_count > 0 OR revoked_session_count > 0 THEN
            UPDATE public.admin_security_controls AS control
            SET state_version = control.state_version + 1,
                updated_at = p_observed_at
            WHERE control.admin_id = p_admin_id
            RETURNING control.state_version INTO resulting_state_version;
          ELSE
            SELECT control.state_version
            INTO STRICT resulting_state_version
            FROM public.admin_security_controls AS control
            WHERE control.admin_id = p_admin_id;
          END IF;
          RETURN NEXT;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_inspect_admin_recovery_start(
          p_admin_id text,
          p_recovery_code_sha256 text,
          p_observed_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS TABLE (
          recovery_code_id uuid,
          active_transaction_id uuid,
          active_recovery_code_id uuid,
          active_device_id text,
          active_expires_at timestamptz
        ) AS $$
        DECLARE
          public_totp_fingerprint text;
          expected_totp_fingerprint text;
        BEGIN
          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION
              'administrator recovery inspection capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint
          INTO STRICT public_totp_fingerprint,
                      expected_totp_fingerprint
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability;

          IF NOT public.walksafe_assert_admin_credential_issuer_key(
              p_admin_id,
              p_credential_issuer_key
            )
            OR p_runtime_totp_secret IS NULL
            OR pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
              ),
              'hex'
            ) IS DISTINCT FROM expected_totp_fingerprint
            OR public_totp_fingerprint IS DISTINCT FROM
              expected_totp_fingerprint
          THEN
            RAISE EXCEPTION
              'administrator recovery inspection capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_recovery_code_sha256 IS NULL
            OR p_recovery_code_sha256 !~ '^[0-9a-f]{64}$'
            OR p_observed_at IS NULL
          THEN
            RAISE EXCEPTION
              'administrator recovery inspection payload is invalid'
              USING ERRCODE = '22023';
          END IF;

          SELECT code.id
          INTO recovery_code_id
          FROM public.admin_security_recovery_codes AS code
          WHERE code.admin_id = p_admin_id
            AND code.code_sha256 = p_recovery_code_sha256
            AND code.used_at IS NULL
          FOR UPDATE;
          IF recovery_code_id IS NULL THEN
            RETURN NEXT;
            RETURN;
          END IF;

          SELECT recovery.id,
                 recovery.recovery_code_id,
                 recovery.device_id,
                 recovery.expires_at
          INTO active_transaction_id,
               active_recovery_code_id,
               active_device_id,
               active_expires_at
          FROM public.admin_security_recovery_transactions AS recovery
          WHERE recovery.admin_id = p_admin_id
            AND recovery.completed_at IS NULL
            AND recovery.expires_at > p_observed_at
          ORDER BY recovery.started_at DESC, recovery.id DESC
          LIMIT 1
          FOR UPDATE;
          RETURN NEXT;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_issue_admin_recovery_transaction(
          p_transaction_id uuid,
          p_admin_id text,
          p_recovery_code_id uuid,
          p_recovery_token_sha256 text,
          p_previous_totp_secret_fingerprint text,
          p_device_id text,
          p_device_label text,
          p_started_at timestamptz,
          p_expires_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS uuid AS $$
        DECLARE
          public_totp_fingerprint text;
          private_totp_fingerprint text;
          locked_recovery_code_id uuid;
          active_transaction_id uuid;
        BEGIN
          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION
              'administrator recovery issuance capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint
          INTO STRICT public_totp_fingerprint, private_totp_fingerprint
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability;

          SELECT code.id
          INTO locked_recovery_code_id
          FROM public.admin_security_recovery_codes AS code
          WHERE code.id = p_recovery_code_id
            AND code.admin_id = p_admin_id
            AND code.used_at IS NULL
          FOR UPDATE;
          SELECT recovery.id
          INTO active_transaction_id
          FROM public.admin_security_recovery_transactions AS recovery
          WHERE recovery.admin_id = p_admin_id
            AND recovery.completed_at IS NULL
            AND recovery.expires_at > p_started_at
          LIMIT 1
          FOR UPDATE;

          IF NOT public.walksafe_assert_admin_credential_issuer_key(
              p_admin_id,
              p_credential_issuer_key
            )
            OR p_runtime_totp_secret IS NULL
            OR pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
              ),
              'hex'
            ) IS DISTINCT FROM private_totp_fingerprint
            OR public_totp_fingerprint IS DISTINCT FROM private_totp_fingerprint
          THEN
            RAISE EXCEPTION
              'administrator recovery issuance capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_transaction_id IS NULL
            OR p_recovery_token_sha256 IS NULL
            OR p_recovery_token_sha256 !~ '^[0-9a-f]{64}$'
            OR p_previous_totp_secret_fingerprint IS DISTINCT FROM
              public_totp_fingerprint
            OR p_device_id IS NULL
            OR pg_catalog.length(p_device_id) < 1
            OR pg_catalog.length(p_device_id) > 128
            OR p_device_label IS NULL
            OR pg_catalog.length(p_device_label) < 1
            OR pg_catalog.length(p_device_label) > 128
            OR p_started_at IS NULL
            OR p_expires_at IS NULL
            OR p_expires_at <= p_started_at
            OR p_expires_at - p_started_at > interval '30 minutes'
            OR locked_recovery_code_id IS NULL
            OR active_transaction_id IS NOT NULL
          THEN
            RAISE EXCEPTION
              'administrator recovery issuance payload is invalid'
              USING ERRCODE = '22023';
          END IF;

          INSERT INTO public.admin_security_recovery_transactions (
            id,
            admin_id,
            recovery_code_id,
            recovery_token_sha256,
            previous_totp_secret_fingerprint,
            device_id,
            device_label,
            started_at,
            expires_at
          ) VALUES (
            p_transaction_id,
            p_admin_id,
            p_recovery_code_id,
            p_recovery_token_sha256,
            p_previous_totp_secret_fingerprint,
            p_device_id,
            p_device_label,
            p_started_at,
            p_expires_at
          );
          RETURN p_transaction_id;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_resume_admin_recovery_transaction(
          p_transaction_id uuid,
          p_recovery_token_sha256 text,
          p_observed_at timestamptz,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS void AS $$
        DECLARE
          transaction_admin_id text;
          transaction_is_active boolean;
          public_totp_fingerprint text;
          private_totp_fingerprint text;
        BEGIN
          SELECT recovery.admin_id
          INTO transaction_admin_id
          FROM public.admin_security_recovery_transactions AS recovery
          WHERE recovery.id = p_transaction_id;
          IF transaction_admin_id IS NULL
            OR NOT public.walksafe_assert_admin_totp_capability(
              transaction_admin_id,
              p_runtime_totp_secret,
              p_credential_issuer_key
            )
          THEN
            RAISE EXCEPTION
              'administrator recovery resume capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT control.admin_id,
                 control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint
          INTO STRICT transaction_admin_id,
                      public_totp_fingerprint,
                      private_totp_fingerprint
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.admin_id = (
            SELECT recovery.admin_id
            FROM public.admin_security_recovery_transactions AS recovery
            WHERE recovery.id = p_transaction_id
          )
          FOR UPDATE OF control, capability;

          SELECT true
          INTO transaction_is_active
          FROM public.admin_security_recovery_transactions AS recovery
          WHERE recovery.id = p_transaction_id
            AND recovery.admin_id = transaction_admin_id
            AND recovery.completed_at IS NULL
            AND recovery.expires_at > p_observed_at
          FOR UPDATE;

          IF NOT public.walksafe_assert_admin_credential_issuer_key(
              transaction_admin_id,
              p_credential_issuer_key
            )
            OR p_runtime_totp_secret IS NULL
            OR pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
              ),
              'hex'
            ) IS DISTINCT FROM private_totp_fingerprint
            OR public_totp_fingerprint IS DISTINCT FROM private_totp_fingerprint
            OR transaction_is_active IS DISTINCT FROM true
          THEN
            RAISE EXCEPTION
              'administrator recovery resume capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_recovery_token_sha256 IS NULL
            OR p_recovery_token_sha256 !~ '^[0-9a-f]{64}$'
            OR p_observed_at IS NULL
          THEN
            RAISE EXCEPTION
              'administrator recovery resume payload is invalid'
              USING ERRCODE = '22023';
          END IF;

          UPDATE public.admin_security_recovery_transactions
          SET recovery_token_sha256 = p_recovery_token_sha256
          WHERE id = p_transaction_id;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_inspect_admin_recovery_completion(
          p_admin_id text,
          p_recovery_token text,
          p_observed_at timestamptz,
          p_next_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS TABLE (
          transaction_id uuid,
          recovery_code_id uuid,
          transaction_device_id text,
          transaction_expires_at timestamptz,
          transaction_completed_at timestamptz,
          previous_totp_secret_fingerprint text,
          recovery_code_exists boolean,
          recovery_code_used_at timestamptz,
          newer_active_transaction_exists boolean,
          replacement_totp_secret_is_new boolean
        ) AS $$
        DECLARE
          public_totp_fingerprint text;
          private_totp_fingerprint text;
          pending_next_totp_fingerprint text;
          next_totp_fingerprint text;
          newer_active_transaction_id uuid;
        BEGIN
          next_totp_fingerprint := pg_catalog.encode(
            pg_catalog.sha256(
              pg_catalog.convert_to(p_next_runtime_totp_secret, 'UTF8')
            ),
            'hex'
          );
          IF NOT EXISTS (
            SELECT 1
            FROM public.walksafe_recovery_custody_capabilities AS capability
            WHERE capability.admin_id = p_admin_id
              AND capability.issuer_key_sha256 = pg_catalog.encode(
                pg_catalog.sha256(
                  pg_catalog.convert_to(p_credential_issuer_key, 'UTF8')
                ),
                'hex'
              )
              AND capability.pending_next_totp_fingerprint =
                next_totp_fingerprint
          ) THEN
            RAISE EXCEPTION
              'administrator recovery completion capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint,
                 capability.pending_next_totp_fingerprint
          INTO STRICT public_totp_fingerprint,
                      private_totp_fingerprint,
                      pending_next_totp_fingerprint
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability;

          IF NOT public.walksafe_assert_admin_credential_issuer_key(
              p_admin_id,
              p_credential_issuer_key
            )
          THEN
            RAISE EXCEPTION
              'administrator recovery completion capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_recovery_token IS NULL
            OR pg_catalog.length(p_recovery_token) < 1
            OR pg_catalog.length(p_recovery_token) > 512
            OR p_observed_at IS NULL
            OR p_next_runtime_totp_secret IS NULL
          THEN
            RAISE EXCEPTION
              'administrator recovery completion inspection payload is invalid'
              USING ERRCODE = '22023';
          END IF;
          IF public_totp_fingerprint IS DISTINCT FROM private_totp_fingerprint
          THEN
            RAISE EXCEPTION
              'administrator recovery completion capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          replacement_totp_secret_is_new :=
            next_totp_fingerprint IS DISTINCT FROM private_totp_fingerprint
            AND next_totp_fingerprint IS NOT DISTINCT FROM
              pending_next_totp_fingerprint;

          SELECT recovery.id,
                 recovery.recovery_code_id,
                 recovery.device_id,
                 recovery.expires_at,
                 recovery.completed_at,
                 recovery.previous_totp_secret_fingerprint
          INTO transaction_id,
               recovery_code_id,
               transaction_device_id,
               transaction_expires_at,
               transaction_completed_at,
               previous_totp_secret_fingerprint
          FROM public.admin_security_recovery_transactions AS recovery
          WHERE recovery.admin_id = p_admin_id
            AND recovery.recovery_token_sha256 = pg_catalog.encode(
              pg_catalog.sha256(pg_catalog.convert_to(p_recovery_token, 'UTF8')),
              'hex'
            )
          FOR UPDATE;
          IF transaction_id IS NULL THEN
            RETURN;
          END IF;

          SELECT true, code.used_at
          INTO recovery_code_exists, recovery_code_used_at
          FROM public.admin_security_recovery_codes AS code
          WHERE code.id = recovery_code_id
            AND code.admin_id = p_admin_id
          FOR UPDATE;

          SELECT recovery.id
          INTO newer_active_transaction_id
          FROM public.admin_security_recovery_transactions AS recovery
          WHERE recovery.admin_id = p_admin_id
            AND recovery.id <> transaction_id
            AND recovery.completed_at IS NULL
            AND recovery.expires_at > p_observed_at
          ORDER BY recovery.started_at DESC, recovery.id DESC
          LIMIT 1
          FOR UPDATE;
          newer_active_transaction_exists :=
            newer_active_transaction_id IS NOT NULL;
          RETURN NEXT;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_expire_admin_recovery(
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
          private_totp_fingerprint text;
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
          );

          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION 'administrator recovery expiry capability is invalid'
              USING ERRCODE = '42501';
          END IF;

          SELECT control.security_state,
                 control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint
          INTO STRICT current_security_state,
                      public_totp_fingerprint,
                      private_totp_fingerprint
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability;

          SELECT recovery.expires_at, recovery.completed_at
          INTO transaction_expires_at, transaction_completed_at
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
            OR pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
              ),
              'hex'
            ) IS DISTINCT FROM private_totp_fingerprint
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
        """
        CREATE FUNCTION walksafe_complete_admin_recovery_transaction(
          p_admin_id text,
          p_recovery_token text,
          p_device_id text,
          p_replacement_password_hash text,
          p_next_runtime_totp_secret text,
          p_next_last_totp_timecode bigint,
          p_completed_at timestamptz,
          p_credential_issuer_key text
        ) RETURNS bigint AS $$
        DECLARE
          recovery_transaction_id uuid;
          recovery_code_id uuid;
          recovery_started_at timestamptz;
          recovery_expires_at timestamptz;
          recovery_device_id text;
          previous_totp_fingerprint text;
          current_security_state text;
          current_password_hash text;
          public_totp_fingerprint text;
          private_totp_fingerprint text;
          pending_recovery_token_sha256 text;
          pending_recovery_expires_at timestamptz;
          pending_next_totp_fingerprint text;
          supplied_token_sha256 text;
          next_totp_fingerprint text;
          resulting_state_version bigint;
        BEGIN
          IF p_recovery_token IS NULL
            OR pg_catalog.length(p_recovery_token) < 1
            OR pg_catalog.length(p_recovery_token) > 512
            OR p_device_id IS NULL
            OR pg_catalog.length(p_device_id) < 1
            OR pg_catalog.length(p_device_id) > 128
            OR p_replacement_password_hash IS NULL
            OR pg_catalog.length(p_replacement_password_hash) < 1
            OR pg_catalog.length(p_replacement_password_hash) > 4096
            OR p_next_runtime_totp_secret IS NULL
            OR pg_catalog.length(p_next_runtime_totp_secret) < 1
            OR pg_catalog.length(p_next_runtime_totp_secret) > 512
            OR p_next_last_totp_timecode IS NULL
            OR p_next_last_totp_timecode < 0
            OR p_completed_at IS NULL
          THEN
            RAISE EXCEPTION
              'administrator recovery completion payload is invalid'
              USING ERRCODE = '22023';
          END IF;
          supplied_token_sha256 := pg_catalog.encode(
            pg_catalog.sha256(pg_catalog.convert_to(p_recovery_token, 'UTF8')),
            'hex'
          );
          next_totp_fingerprint := pg_catalog.encode(
            pg_catalog.sha256(
              pg_catalog.convert_to(p_next_runtime_totp_secret, 'UTF8')
            ),
            'hex'
          );

          IF NOT EXISTS (
            SELECT 1
            FROM public.walksafe_recovery_custody_capabilities AS capability
            WHERE capability.admin_id = p_admin_id
              AND capability.issuer_key_sha256 = pg_catalog.encode(
                pg_catalog.sha256(
                  pg_catalog.convert_to(p_credential_issuer_key, 'UTF8')
                ),
                'hex'
              )
              AND capability.pending_next_totp_fingerprint =
                next_totp_fingerprint
          ) THEN
            RAISE EXCEPTION
              'administrator recovery completion capability is invalid'
              USING ERRCODE = '42501';
          END IF;

          SELECT control.security_state,
                 control.password_hash,
                 control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint,
                 capability.pending_recovery_token_sha256,
                 capability.pending_recovery_expires_at,
                 capability.pending_next_totp_fingerprint
          INTO STRICT current_security_state,
                      current_password_hash,
                      public_totp_fingerprint,
                      private_totp_fingerprint,
                      pending_recovery_token_sha256,
                      pending_recovery_expires_at,
                      pending_next_totp_fingerprint
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability;

          IF NOT public.walksafe_assert_admin_credential_issuer_key(
              p_admin_id,
              p_credential_issuer_key
            )
          THEN
            RAISE EXCEPTION
              'administrator recovery completion capability is invalid'
              USING ERRCODE = '42501';
          END IF;

          SELECT recovery.id,
                 recovery.recovery_code_id,
                 recovery.started_at,
                 recovery.expires_at,
                 recovery.device_id,
                 recovery.previous_totp_secret_fingerprint
          INTO recovery_transaction_id,
               recovery_code_id,
               recovery_started_at,
               recovery_expires_at,
               recovery_device_id,
               previous_totp_fingerprint
          FROM public.admin_security_recovery_transactions AS recovery
          JOIN public.admin_security_recovery_codes AS code
            ON code.id = recovery.recovery_code_id
           AND code.admin_id = recovery.admin_id
          WHERE recovery.admin_id = p_admin_id
            AND recovery.recovery_token_sha256 = supplied_token_sha256
            AND recovery.completed_at IS NULL
            AND code.used_at IS NULL
          FOR UPDATE OF recovery, code;

          IF recovery_transaction_id IS NULL
            OR current_security_state <> 'RECOVERY_IN_PROGRESS'
            OR public_totp_fingerprint IS DISTINCT FROM private_totp_fingerprint
            OR previous_totp_fingerprint IS DISTINCT FROM
              public_totp_fingerprint
            OR supplied_token_sha256 IS DISTINCT FROM
              pending_recovery_token_sha256
            OR pending_recovery_expires_at IS NULL
            OR pending_recovery_expires_at IS DISTINCT FROM recovery_expires_at
            OR recovery_expires_at <= p_completed_at
            OR recovery_started_at > p_completed_at
            OR recovery_device_id IS DISTINCT FROM p_device_id
            OR next_totp_fingerprint IS NOT DISTINCT FROM
              private_totp_fingerprint
            OR next_totp_fingerprint IS DISTINCT FROM
              pending_next_totp_fingerprint
            OR p_replacement_password_hash IS NOT DISTINCT FROM
              current_password_hash
          THEN
            RAISE EXCEPTION
              'administrator recovery completion capability is invalid'
              USING ERRCODE = '42501';
          END IF;

          UPDATE public.walksafe_recovery_custody_capabilities AS capability
          SET totp_secret_fingerprint = next_totp_fingerprint,
              pending_recovery_token_sha256 = NULL,
              pending_recovery_expires_at = NULL,
              pending_next_totp_fingerprint = NULL
          WHERE capability.admin_id = p_admin_id;
          UPDATE public.admin_security_controls AS control
          SET password_hash = p_replacement_password_hash,
              totp_secret_fingerprint = next_totp_fingerprint,
              last_totp_timecode = p_next_last_totp_timecode,
              security_state = 'NORMAL',
              state_version = control.state_version + 1,
              updated_at = p_completed_at
          WHERE control.admin_id = p_admin_id
          RETURNING control.state_version INTO resulting_state_version;
          UPDATE public.admin_security_recovery_transactions
          SET completed_at = p_completed_at
          WHERE id = recovery_transaction_id;
          UPDATE public.admin_security_recovery_codes
          SET used_at = p_completed_at
          WHERE id = recovery_code_id;
          RETURN resulting_state_version;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_attest_recovery_custody(
          p_admin_id text,
          p_attested_at timestamptz,
          p_reference_sha256 text,
          p_material_kind text,
          p_storage_location text,
          p_separate_backup_confirmed boolean,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS void AS $$
        DECLARE
          previous_state text;
          current_security_state text;
          public_totp_fingerprint text;
          private_totp_fingerprint text;
          marker_details jsonb;
        BEGIN
          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION 'recovery custody capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT control.recovery_custody_state,
                 control.security_state,
                 control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint
          INTO STRICT previous_state,
                      current_security_state,
                      public_totp_fingerprint,
                      private_totp_fingerprint
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability;

          IF NOT public.walksafe_assert_admin_credential_issuer_key(
              p_admin_id,
              p_credential_issuer_key
            )
            OR p_runtime_totp_secret IS NULL
            OR pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
              ),
              'hex'
            ) IS DISTINCT FROM private_totp_fingerprint
            OR public_totp_fingerprint IS DISTINCT FROM private_totp_fingerprint
            OR current_security_state <> 'NORMAL'
          THEN
            RAISE EXCEPTION 'recovery custody capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_attested_at IS NULL
            OR p_reference_sha256 IS NULL
            OR p_reference_sha256 !~ '^[0-9a-f]{64}$'
            OR p_material_kind NOT IN ('RECOVERY_CODE', 'SECURITY_KEY')
            OR p_storage_location <> 'OFF_PHONE'
            OR p_separate_backup_confirmed IS DISTINCT FROM true
          THEN
            RAISE EXCEPTION 'recovery custody attestation payload is invalid'
              USING ERRCODE = '22023';
          END IF;

          marker_details := pg_catalog.jsonb_build_object(
            'idempotent', false,
            'material_kind', p_material_kind,
            'replaced_attestation', previous_state = 'ATTESTED',
            'separate_backup_confirmed', true,
            'storage_location', p_storage_location
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
            p_admin_id,
            'recovery.custody.attest',
            previous_state,
            'ATTESTED',
            marker_details
          );
          UPDATE public.admin_security_controls
          SET recovery_custody_state = 'ATTESTED',
              recovery_custody_attested_at = p_attested_at,
              recovery_custody_reference_sha256 = p_reference_sha256,
              recovery_custody_material_kind = p_material_kind,
              recovery_custody_storage_location = p_storage_location,
              recovery_custody_separate_backup_confirmed =
                p_separate_backup_confirmed,
              state_version = state_version + 1,
              updated_at = p_attested_at
          WHERE admin_id = p_admin_id;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_reset_recovery_custody(
          p_admin_id text,
          p_recovery_device_key_preserved boolean,
          p_recovery_token_sha256 text,
          p_recovery_expires_at timestamptz,
          p_resume boolean,
          p_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS bigint AS $$
        DECLARE
          previous_state text;
          current_security_state text;
          public_totp_fingerprint text;
          expected_totp_fingerprint text;
          previous_pending_token_sha256 text;
          previous_pending_expires_at timestamptz;
          recovery_device_id text;
          recovery_started_at timestamptz;
          recovery_device_key_count bigint;
          revoked_device_key_count bigint := 0;
          marker_details jsonb;
        BEGIN
          IF NOT public.walksafe_assert_admin_totp_capability(
            p_admin_id,
            p_runtime_totp_secret,
            p_credential_issuer_key
          ) THEN
            RAISE EXCEPTION 'recovery custody capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT control.recovery_custody_state,
                 control.security_state,
                 control.totp_secret_fingerprint,
                 capability.totp_secret_fingerprint,
                 capability.pending_recovery_token_sha256,
                 capability.pending_recovery_expires_at
          INTO STRICT previous_state,
                      current_security_state,
                      public_totp_fingerprint,
                      expected_totp_fingerprint,
                      previous_pending_token_sha256,
                      previous_pending_expires_at
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability;

          IF NOT public.walksafe_assert_admin_credential_issuer_key(
              p_admin_id,
              p_credential_issuer_key
            )
            OR p_runtime_totp_secret IS NULL
            OR pg_catalog.encode(
              pg_catalog.sha256(
                pg_catalog.convert_to(p_runtime_totp_secret, 'UTF8')
              ),
              'hex'
            ) IS DISTINCT FROM expected_totp_fingerprint
            OR public_totp_fingerprint IS DISTINCT FROM
              expected_totp_fingerprint
          THEN
            RAISE EXCEPTION 'recovery custody capability is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF p_recovery_token_sha256 IS NULL
            OR p_recovery_token_sha256 !~ '^[0-9a-f]{64}$'
            OR p_recovery_expires_at IS NULL
            OR p_recovery_expires_at <= pg_catalog.statement_timestamp()
            OR p_resume IS NULL
            OR p_recovery_device_key_preserved IS NULL
            OR (
              p_resume
              AND current_security_state <> 'RECOVERY_IN_PROGRESS'
            )
          THEN
            RAISE EXCEPTION 'recovery token binding is invalid'
              USING ERRCODE = '22023';
          END IF;

          IF p_resume AND (
            previous_pending_token_sha256 IS NULL
            OR previous_pending_expires_at IS NULL
            OR previous_pending_expires_at <= pg_catalog.statement_timestamp()
            OR p_recovery_expires_at IS DISTINCT FROM previous_pending_expires_at
          ) THEN
            RAISE EXCEPTION 'recovery expiry binding is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF NOT p_resume
            AND previous_pending_expires_at > pg_catalog.statement_timestamp()
          THEN
            RAISE EXCEPTION 'active recovery capability cannot be replaced'
              USING ERRCODE = '42501';
          END IF;
          SELECT recovery.device_id, recovery.started_at
          INTO recovery_device_id, recovery_started_at
          FROM public.admin_security_recovery_transactions AS recovery
          WHERE recovery.admin_id = p_admin_id
            AND recovery.recovery_token_sha256 = p_recovery_token_sha256
            AND recovery.completed_at IS NULL
            AND recovery.expires_at IS NOT DISTINCT FROM p_recovery_expires_at
          FOR UPDATE;
          IF recovery_device_id IS NULL THEN
            RAISE EXCEPTION 'recovery transaction binding is invalid'
              USING ERRCODE = '42501';
          END IF;
          IF NOT p_resume AND p_recovery_device_key_preserved THEN
            SELECT pg_catalog.count(*)
            INTO recovery_device_key_count
            FROM public.admin_device_keys AS device_key
            WHERE device_key.admin_id = p_admin_id
              AND device_key.device_id = recovery_device_id
              AND device_key.status = 'ACTIVE'
              AND device_key.revoked_at IS NULL;
            IF recovery_device_key_count <> 1 THEN
              RAISE EXCEPTION 'recovery device key binding is invalid'
                USING ERRCODE = '42501';
            END IF;
          END IF;

          UPDATE public.walksafe_recovery_custody_capabilities AS capability
          SET pending_recovery_token_sha256 = p_recovery_token_sha256,
              pending_recovery_expires_at = p_recovery_expires_at,
              pending_next_totp_fingerprint = NULL
          WHERE capability.admin_id = p_admin_id;

          IF NOT p_resume AND previous_state <> 'UNATTESTED' THEN
            marker_details := pg_catalog.jsonb_build_object(
              'all_sessions_revoked', true,
              'custody_reset', true,
              'other_device_keys_revoked', true,
              'previous_custody_state', previous_state,
              'recovery_device_key_preserved', p_recovery_device_key_preserved
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
              p_admin_id,
              'recovery.start',
              previous_state,
              'UNATTESTED',
              marker_details
            );
          END IF;
          IF NOT p_resume THEN
            UPDATE public.admin_security_sessions
            SET revoked_at = recovery_started_at,
                revoked_reason = 'recovery_started'
            WHERE admin_id = p_admin_id
              AND revoked_at IS NULL;

            UPDATE public.admin_device_keys AS device_key
            SET status = 'REVOKED',
                revoked_at = recovery_started_at
            WHERE device_key.admin_id = p_admin_id
              AND device_key.status = 'ACTIVE'
              AND device_key.revoked_at IS NULL
              AND NOT (
                p_recovery_device_key_preserved
                AND device_key.device_id = recovery_device_id
              );
            GET DIAGNOSTICS revoked_device_key_count = ROW_COUNT;

            UPDATE public.admin_security_controls
            SET recovery_custody_state = 'UNATTESTED',
                recovery_custody_attested_at = NULL,
                recovery_custody_reference_sha256 = NULL,
                recovery_custody_material_kind = NULL,
                recovery_custody_storage_location = NULL,
                recovery_custody_separate_backup_confirmed = false,
                security_state = 'RECOVERY_IN_PROGRESS',
                state_version = state_version + 1,
                updated_at = recovery_started_at
            WHERE admin_id = p_admin_id;
          END IF;
          RETURN revoked_device_key_count;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_rotate_recovery_custody_capability(
          p_admin_id text,
          p_recovery_token text,
          p_next_runtime_totp_secret text,
          p_credential_issuer_key text
        ) RETURNS void AS $$
        DECLARE
          pending_token_sha256 text;
          pending_expires_at timestamptz;
          supplied_token_sha256 text;
          current_totp_fingerprint text;
          pending_next_totp_fingerprint text;
          next_totp_fingerprint text;
          current_security_state text;
        BEGIN
          supplied_token_sha256 := pg_catalog.encode(
            pg_catalog.sha256(pg_catalog.convert_to(p_recovery_token, 'UTF8')),
            'hex'
          );
          next_totp_fingerprint := pg_catalog.encode(
            pg_catalog.sha256(
              pg_catalog.convert_to(p_next_runtime_totp_secret, 'UTF8')
            ),
            'hex'
          );
          IF NOT EXISTS (
            SELECT 1
            FROM public.walksafe_recovery_custody_capabilities AS capability
            WHERE capability.admin_id = p_admin_id
              AND capability.issuer_key_sha256 = pg_catalog.encode(
                pg_catalog.sha256(
                  pg_catalog.convert_to(p_credential_issuer_key, 'UTF8')
                ),
                'hex'
              )
              AND capability.pending_next_totp_fingerprint =
                next_totp_fingerprint
          ) THEN
            RAISE EXCEPTION 'recovery custody capability rotation is invalid'
              USING ERRCODE = '42501';
          END IF;
          SELECT capability.pending_recovery_token_sha256,
                 capability.pending_recovery_expires_at,
                 capability.totp_secret_fingerprint,
                 capability.pending_next_totp_fingerprint,
                 control.security_state
          INTO STRICT pending_token_sha256,
                      pending_expires_at,
                      current_totp_fingerprint,
                      pending_next_totp_fingerprint,
                      current_security_state
          FROM public.admin_security_controls AS control
          JOIN public.walksafe_recovery_custody_capabilities AS capability
            ON capability.admin_id = control.admin_id
          WHERE control.admin_id = p_admin_id
          FOR UPDATE OF control, capability;

          IF NOT public.walksafe_assert_admin_credential_issuer_key(
              p_admin_id,
              p_credential_issuer_key
            )
            OR p_recovery_token IS NULL
            OR p_next_runtime_totp_secret IS NULL
            OR current_security_state <> 'RECOVERY_IN_PROGRESS'
            OR supplied_token_sha256 IS DISTINCT FROM pending_token_sha256
            OR pending_expires_at IS NULL
            OR pending_expires_at <= pg_catalog.statement_timestamp()
            OR next_totp_fingerprint IS NOT DISTINCT FROM current_totp_fingerprint
            OR next_totp_fingerprint IS DISTINCT FROM
              pending_next_totp_fingerprint
            OR NOT EXISTS (
              SELECT 1
              FROM public.admin_security_recovery_transactions AS recovery
              WHERE recovery.admin_id = p_admin_id
                AND recovery.recovery_token_sha256 = supplied_token_sha256
                AND recovery.completed_at IS NULL
                AND recovery.expires_at > pg_catalog.statement_timestamp()
            )
          THEN
            RAISE EXCEPTION 'recovery custody capability rotation is invalid'
              USING ERRCODE = '42501';
          END IF;

          UPDATE public.walksafe_recovery_custody_capabilities
          SET totp_secret_fingerprint = next_totp_fingerprint,
              pending_recovery_token_sha256 = NULL,
              pending_recovery_expires_at = NULL,
              pending_next_totp_fingerprint = NULL
          WHERE admin_id = p_admin_id;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_assert_admin_totp_capability("
        "text, text, text) FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_lock_admin_original_access_session("
        "text, uuid, text, timestamptz, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_lock_admin_device_proof_context("
        "text, text, bigint, text, timestamptz, text, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_touch_admin_session("
        "text, uuid, text, timestamptz, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_consume_admin_reconfirmation("
        "text, uuid, text, text, text, text, text, timestamptz, "
        "timestamptz, text, text) FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_revoke_admin_session("
        "text, uuid, text, uuid, timestamptz, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_issue_admin_session("
        "uuid, text, text, text, text, timestamptz, timestamptz, "
        "timestamptz, timestamptz, text, bigint, bigint, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_issue_admin_reconfirmation("
        "uuid, text, uuid, text, text, text, text, text, timestamptz, "
        "timestamptz, bigint, bigint, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_report_admin_lost_device("
        "text, uuid, text, text, timestamptz, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_inspect_admin_recovery_start("
        "text, text, timestamptz, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_issue_admin_recovery_transaction("
        "uuid, text, uuid, text, text, text, text, timestamptz, "
        "timestamptz, text, text) FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_resume_admin_recovery_transaction("
        "uuid, text, timestamptz, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_inspect_admin_recovery_completion("
        "text, text, timestamptz, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_expire_admin_recovery("
        "text, uuid, text, timestamptz, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_complete_admin_recovery_transaction("
        "text, text, text, text, text, bigint, timestamptz, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_attest_recovery_custody("
        "text, timestamptz, text, text, text, boolean, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_reset_recovery_custody("
        "text, boolean, text, timestamptz, boolean, text, text) "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_rotate_recovery_custody_capability("
        "text, text, text, text) FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_assert_admin_totp_capability("
        "text, text, text) TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_lock_admin_original_access_session("
        "text, uuid, text, timestamptz, text, text) "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_lock_admin_device_proof_context("
        "text, text, bigint, text, timestamptz, text, text, text) "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_touch_admin_session("
        "text, uuid, text, timestamptz, text, text) "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_consume_admin_reconfirmation("
        "text, uuid, text, text, text, text, text, timestamptz, "
        "timestamptz, text, text) TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_revoke_admin_session("
        "text, uuid, text, uuid, timestamptz, text, text) "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_issue_admin_session("
        "uuid, text, text, text, text, timestamptz, timestamptz, "
        "timestamptz, timestamptz, text, bigint, bigint, text, text) "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_issue_admin_reconfirmation("
        "uuid, text, uuid, text, text, text, text, text, timestamptz, "
        "timestamptz, bigint, bigint, text, text) TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_report_admin_lost_device("
        "text, uuid, text, text, timestamptz, text, text) "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_inspect_admin_recovery_start("
        "text, text, timestamptz, text, text) TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_issue_admin_recovery_transaction("
        "uuid, text, uuid, text, text, text, text, timestamptz, "
        "timestamptz, text, text) TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_resume_admin_recovery_transaction("
        "uuid, text, timestamptz, text, text) TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_inspect_admin_recovery_completion("
        "text, text, timestamptz, text, text) TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_expire_admin_recovery("
        "text, uuid, text, timestamptz, text, text) TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_complete_admin_recovery_transaction("
        "text, text, text, text, text, bigint, timestamptz, text) "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_attest_recovery_custody("
        "text, timestamptz, text, text, text, boolean, text, text) "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT EXECUTE ON FUNCTION walksafe_reset_recovery_custody("
        "text, boolean, text, timestamptz, boolean, text, text) "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        """
        CREATE FUNCTION walksafe_require_recovery_custody_audit()
        RETURNS trigger AS $$
        DECLARE
          required_action text;
          required_details jsonb;
          marker_count integer;
          marker_preserved boolean;
        BEGIN
          IF TG_OP = 'INSERT' THEN
            IF NEW.recovery_custody_state <> 'UNATTESTED'
              OR NEW.recovery_custody_attested_at IS NOT NULL
              OR NEW.recovery_custody_reference_sha256 IS NOT NULL
              OR NEW.recovery_custody_material_kind IS NOT NULL
              OR NEW.recovery_custody_storage_location IS NOT NULL
              OR NEW.recovery_custody_separate_backup_confirmed
            THEN
              RAISE EXCEPTION 'new administrator control custody must be unattested'
                USING ERRCODE = '23514';
            END IF;
            IF NOT EXISTS (
              SELECT 1
              FROM public.admin_security_audits AS audit
              WHERE audit.admin_id = NEW.admin_id
                AND audit.action = 'security.provision'
                AND audit.outcome = 'SUCCESS'
                AND audit.xmin = pg_catalog.pg_current_xact_id()::pg_catalog.xid
            ) THEN
              RAISE EXCEPTION
                'administrator control insertion requires a same-transaction provision audit'
                USING ERRCODE = '23514';
            END IF;
            IF NOT EXISTS (
              SELECT 1
              FROM public.walksafe_recovery_custody_capabilities AS capability
              WHERE capability.admin_id = NEW.admin_id
                AND capability.totp_secret_fingerprint =
                  NEW.totp_secret_fingerprint
                AND capability.issuer_key_sha256 IS NOT NULL
            ) THEN
              RAISE EXCEPTION
                'administrator provision requires a bound issuer capability'
                USING ERRCODE = '23514';
            END IF;
            RETURN NEW;
          END IF;

          IF ROW(
            OLD.recovery_custody_state,
            OLD.recovery_custody_attested_at,
            OLD.recovery_custody_reference_sha256,
            OLD.recovery_custody_material_kind,
            OLD.recovery_custody_storage_location,
            OLD.recovery_custody_separate_backup_confirmed
          ) IS NOT DISTINCT FROM ROW(
            NEW.recovery_custody_state,
            NEW.recovery_custody_attested_at,
            NEW.recovery_custody_reference_sha256,
            NEW.recovery_custody_material_kind,
            NEW.recovery_custody_storage_location,
            NEW.recovery_custody_separate_backup_confirmed
          ) THEN
            RETURN NEW;
          END IF;

          CASE NEW.recovery_custody_state
            WHEN 'ATTESTED' THEN
              required_action := 'recovery.custody.attest';
              required_details := pg_catalog.jsonb_build_object(
                'idempotent', false,
                'material_kind', NEW.recovery_custody_material_kind,
                'replaced_attestation', OLD.recovery_custody_state = 'ATTESTED',
                'separate_backup_confirmed', true,
                'storage_location', NEW.recovery_custody_storage_location
              );
            WHEN 'UNATTESTED' THEN
              required_action := 'recovery.start';
              SELECT (marker.details ->> 'recovery_device_key_preserved')::boolean
              INTO marker_preserved
              FROM public.walksafe_recovery_custody_markers AS marker
              WHERE marker.transaction_id =
                  pg_catalog.pg_current_xact_id()::text::bigint
                AND marker.admin_id = NEW.admin_id
                AND marker.action = 'recovery.start'
                AND marker.previous_custody_state = OLD.recovery_custody_state
                AND marker.next_custody_state = NEW.recovery_custody_state;
              required_details := pg_catalog.jsonb_build_object(
                'all_sessions_revoked', true,
                'custody_reset', true,
                'other_device_keys_revoked', true,
                'previous_custody_state', OLD.recovery_custody_state,
                'recovery_device_key_preserved', marker_preserved
              );
            ELSE
              RAISE EXCEPTION 'invalid recovery custody transition'
                USING ERRCODE = '23514';
          END CASE;
          DELETE FROM public.walksafe_recovery_custody_markers AS marker
          WHERE marker.transaction_id =
              pg_catalog.pg_current_xact_id()::text::bigint
            AND marker.admin_id = NEW.admin_id
            AND marker.action = required_action
            AND marker.previous_custody_state = OLD.recovery_custody_state
            AND marker.next_custody_state = NEW.recovery_custody_state
            AND marker.details::jsonb = required_details;
          GET DIAGNOSTICS marker_count = ROW_COUNT;
          IF marker_count <> 1 THEN
            RAISE EXCEPTION
              'recovery custody transition requires an authorized database marker'
              USING ERRCODE = '23514';
          END IF;
          IF NOT EXISTS (
            SELECT 1
            FROM public.admin_security_audits AS audit
            WHERE audit.admin_id = NEW.admin_id
              AND audit.action = required_action
              AND audit.outcome = 'SUCCESS'
              AND audit.details @> required_details
              AND audit.xmin = pg_catalog.pg_current_xact_id()::pg_catalog.xid
          ) THEN
            RAISE EXCEPTION
              'recovery custody transition requires a same-transaction security audit'
              USING ERRCODE = '23514';
          END IF;
          RETURN NEW;
        END;
        $$ LANGUAGE plpgsql SECURITY DEFINER
        SET search_path = pg_catalog, pg_temp
        """
    )
    op.execute(
        "REVOKE ALL ON FUNCTION walksafe_require_recovery_custody_audit() "
        "FROM PUBLIC, walksafe_backend_runtime"
    )
    op.execute(
        "CREATE CONSTRAINT TRIGGER admin_security_controls_custody_audited "
        "AFTER INSERT OR UPDATE ON admin_security_controls "
        "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW "
        "EXECUTE FUNCTION walksafe_require_recovery_custody_audit()"
    )


def downgrade() -> None:
    op.execute(
        """
        DO $$
        BEGIN
          IF EXISTS (
            SELECT 1
            FROM public.walksafe_recovery_custody_capabilities AS capability
            JOIN public.admin_security_controls AS control
              ON control.admin_id = capability.admin_id
            WHERE capability.issuer_key_sha256 IS NOT NULL
              OR capability.pending_recovery_token_sha256 IS NOT NULL
              OR capability.pending_recovery_expires_at IS NOT NULL
              OR capability.pending_next_totp_fingerprint IS NOT NULL
              OR control.recovery_custody_state <> 'UNATTESTED'
              OR control.recovery_custody_attested_at IS NOT NULL
              OR control.recovery_custody_reference_sha256 IS NOT NULL
              OR control.recovery_custody_material_kind IS NOT NULL
              OR control.recovery_custody_storage_location IS NOT NULL
              OR control.recovery_custody_separate_backup_confirmed
          ) THEN
            RAISE EXCEPTION
              'administrator recovery authority must be cleared before downgrade'
              USING ERRCODE = '55000';
          END IF;
        END;
        $$
        """
    )
    op.execute(
        "DROP TRIGGER IF EXISTS admin_security_sessions_transition_only "
        "ON admin_security_sessions"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_require_admin_session_transition()"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS admin_security_reconfirmations_consume_once "
        "ON admin_security_reconfirmations"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "walksafe_require_admin_reconfirmation_consumption()"
    )
    op.execute(
        "REVOKE UPDATE (consumed_at) ON TABLE "
        "public.admin_security_reconfirmations FROM walksafe_backend_runtime"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS admin_device_keys_revocation_only "
        "ON admin_device_keys"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_require_admin_device_key_revocation()"
    )
    op.execute(
        "DROP TRIGGER IF EXISTS admin_security_controls_custody_audited "
        "ON admin_security_controls"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_require_recovery_custody_audit()"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_rotate_recovery_custody_capability("
        "text, text, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_reset_recovery_custody("
        "text, boolean, text, timestamptz, boolean, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_attest_recovery_custody("
        "text, timestamptz, text, text, text, boolean, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_complete_admin_recovery_transaction("
        "text, text, text, text, text, bigint, timestamptz, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_expire_admin_recovery("
        "text, uuid, text, timestamptz, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_inspect_admin_recovery_completion("
        "text, text, timestamptz, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_resume_admin_recovery_transaction("
        "uuid, text, timestamptz, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_issue_admin_recovery_transaction("
        "uuid, text, uuid, text, text, text, text, timestamptz, "
        "timestamptz, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_inspect_admin_recovery_start("
        "text, text, timestamptz, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_issue_admin_reconfirmation("
        "uuid, text, uuid, text, text, text, text, text, timestamptz, "
        "timestamptz, bigint, bigint, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_report_admin_lost_device("
        "text, uuid, text, text, timestamptz, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_issue_admin_session("
        "uuid, text, text, text, text, timestamptz, timestamptz, "
        "timestamptz, timestamptz, text, bigint, bigint, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_revoke_admin_session("
        "text, uuid, text, uuid, timestamptz, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_consume_admin_reconfirmation("
        "text, uuid, text, text, text, text, text, timestamptz, "
        "timestamptz, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_touch_admin_session("
        "text, uuid, text, timestamptz, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_lock_admin_device_proof_context("
        "text, text, bigint, text, timestamptz, text, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_lock_admin_original_access_session("
        "text, uuid, text, timestamptz, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_assert_admin_totp_capability("
        "text, text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS walksafe_lock_admin_security_control(text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "walksafe_bind_admin_credential_issuer_key(text, text)"
    )
    op.execute(
        "DROP FUNCTION IF EXISTS "
        "walksafe_assert_admin_credential_issuer_key(text, text)"
    )
    op.execute(
        "GRANT INSERT ON TABLE public.admin_security_controls "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT UPDATE ON TABLE public.admin_security_controls "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT INSERT ON TABLE public.admin_device_keys "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT UPDATE ON TABLE public.admin_device_keys "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT INSERT ON TABLE public.admin_security_sessions, "
        "public.admin_security_reconfirmations, "
        "public.admin_security_recovery_codes, "
        "public.admin_security_recovery_transactions "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT UPDATE ON TABLE public.admin_security_sessions, "
        "public.admin_security_recovery_codes, "
        "public.admin_security_recovery_transactions "
        "TO walksafe_backend_runtime"
    )
    op.execute(
        "GRANT SELECT ON TABLE public.admin_security_recovery_codes, "
        "public.admin_security_recovery_transactions "
        "TO walksafe_backend_runtime"
    )
    op.drop_table("walksafe_recovery_custody_markers")
    op.drop_table("walksafe_recovery_custody_capabilities")
    op.drop_constraint(
        "ck_admin_security_controls_custody_attestation",
        "admin_security_controls",
        type_="check",
    )
    op.drop_constraint(
        "ck_admin_security_controls_custody_reference",
        "admin_security_controls",
        type_="check",
    )
    op.drop_constraint(
        "ck_admin_security_controls_custody_state",
        "admin_security_controls",
        type_="check",
    )
    op.drop_constraint(
        "uq_admin_security_controls_singleton",
        "admin_security_controls",
        type_="unique",
    )
    op.drop_constraint(
        "ck_admin_security_controls_singleton",
        "admin_security_controls",
        type_="check",
    )
    op.drop_column(
        "admin_security_controls",
        "recovery_custody_separate_backup_confirmed",
    )
    op.drop_column(
        "admin_security_controls",
        "recovery_custody_storage_location",
    )
    op.drop_column(
        "admin_security_controls",
        "recovery_custody_material_kind",
    )
    op.drop_column(
        "admin_security_controls",
        "recovery_custody_reference_sha256",
    )
    op.drop_column(
        "admin_security_controls",
        "recovery_custody_attested_at",
    )
    op.drop_column("admin_security_controls", "recovery_custody_state")
    op.drop_column("admin_security_controls", "singleton_scope")
