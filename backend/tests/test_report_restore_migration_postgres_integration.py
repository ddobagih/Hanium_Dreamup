from __future__ import annotations

from datetime import UTC, datetime
import hashlib
import json
import os
from pathlib import Path
import uuid

from alembic import command
from alembic.config import Config
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import SQLAlchemyError

from backend.app.services import report_restore_tombstones as tombstones


pytestmark = pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)


_RECEIPT_DOMAIN = tombstones.RECEIPT_HASH_DOMAIN


def _migration_config(monkeypatch: pytest.MonkeyPatch, url: str) -> Config:
    monkeypatch.setenv("DATABASE_URL", url)
    monkeypatch.setenv("WALKSAFE_MIGRATION_DATABASE_URL", url)
    return Config(str(Path(__file__).resolve().parents[1] / "alembic.ini"))


def _receipt_bytes(
    *,
    plan_sha256: str,
    restore_run_id: uuid.UUID,
    target_identity_sha256: str,
    report_id: uuid.UUID,
    entry_sha256: str,
    applied_at: datetime,
    receipt_sha256: str | None = None,
    compact: bool = True,
    null_plan: bool = False,
) -> tuple[bytes, str]:
    payload: dict[str, object] = {
        "applied_at": applied_at.astimezone(UTC).strftime(
            "%Y-%m-%dT%H:%M:%S.%fZ"
        ).replace(".000000Z", "Z"),
        "data_boundary_id": "walksafe-report-production",
        "plan_sha256": None if null_plan else plan_sha256,
        "restore_run_id": str(restore_run_id),
        "results": [
            {
                "entry_sha256": entry_sha256,
                "report_id": str(report_id),
                "result": "DELETED",
            }
        ],
        "schema_version": "walksafe.report-restore-reapply-receipt.v1",
        "target_identity_sha256": target_identity_sha256,
    }
    canonical_payload = json.dumps(
        payload,
        ensure_ascii=True,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("ascii")
    expected_hash = hashlib.sha256(_RECEIPT_DOMAIN + canonical_payload).hexdigest()
    document = {**payload, "receipt_sha256": receipt_sha256 or expected_hash}
    separators = (",", ":") if compact else None
    return (
        json.dumps(
            document,
            ensure_ascii=True,
            allow_nan=False,
            separators=separators,
            sort_keys=True,
        ).encode("ascii"),
        receipt_sha256 or expected_hash,
    )


def _multi_receipt_bytes(
    *,
    plan_sha256: str,
    restore_run_id: uuid.UUID,
    target_identity_sha256: str,
    results: tuple[tuple[uuid.UUID, str], ...],
    applied_at: datetime,
) -> tuple[bytes, str]:
    receipt_results = tuple(
        tombstones.ReportRestoreReapplyResult(
            report_id=report_id,
            entry_sha256=entry_sha256,
            result="DELETED",
        )
        for report_id, entry_sha256 in results
    )
    placeholder = tombstones.ReportRestoreReapplyReceipt(
        plan_sha256=plan_sha256,
        restore_run_id=restore_run_id,
        data_boundary_id="walksafe-report-production",
        target_identity_sha256=target_identity_sha256,
        applied_at=applied_at,
        results=receipt_results,
        receipt_sha256="",
    )
    payload = json.loads(
        tombstones.report_restore_reapply_receipt_bytes(placeholder)
    )
    payload.pop("receipt_sha256")
    receipt_sha256 = hashlib.sha256(
        _RECEIPT_DOMAIN + tombstones.canonical_json_bytes(payload)
    ).hexdigest()
    receipt = tombstones.ReportRestoreReapplyReceipt(
        plan_sha256=placeholder.plan_sha256,
        restore_run_id=placeholder.restore_run_id,
        data_boundary_id=placeholder.data_boundary_id,
        target_identity_sha256=placeholder.target_identity_sha256,
        applied_at=placeholder.applied_at,
        results=placeholder.results,
        receipt_sha256=receipt_sha256,
    )
    return tombstones.report_restore_reapply_receipt_bytes(receipt), receipt_sha256


def test_restore_migration_fail_closed_role_receipt_and_downgrade_boundaries(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    configured_url = make_url(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip())
    database_name = f"walksafe_restore_migration_test_{uuid.uuid4().hex}"
    disposable_url = configured_url.set(database=database_name)
    migration_url = disposable_url.render_as_string(hide_password=False)
    admin_engine = create_engine(configured_url, pool_pre_ping=True)
    disposable_engine = create_engine(disposable_url, pool_pre_ping=True)
    quoted_database = admin_engine.dialect.identifier_preparer.quote(database_name)
    worker_login = f"walksafe_restore_finish_test_{uuid.uuid4().hex[:12]}"
    quoted_worker_login = admin_engine.dialect.identifier_preparer.quote(worker_login)
    unsafe_parent_role = f"walksafe_restore_parent_test_{uuid.uuid4().hex[:12]}"
    quoted_unsafe_parent = admin_engine.dialect.identifier_preparer.quote(
        unsafe_parent_role
    )
    created_database = False
    created_login = False
    created_unsafe_parent = False

    try:
        with admin_engine.connect().execution_options(
            isolation_level="AUTOCOMMIT"
        ) as connection:
            assert "test" in connection.execute(
                text("SELECT current_database()")
            ).scalar_one().lower()
            connection.exec_driver_sql(f"CREATE DATABASE {quoted_database}")
            created_database = True

        config = _migration_config(monkeypatch, migration_url)
        command.upgrade(config, "202608300002")

        with admin_engine.connect().execution_options(
            isolation_level="AUTOCOMMIT"
        ) as connection:
            connection.exec_driver_sql(
                "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE "
                "rolname = 'walksafe_report_restore_authorizer') THEN "
                "CREATE ROLE walksafe_report_restore_authorizer NOLOGIN "
                "NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT "
                "NOREPLICATION NOBYPASSRLS; END IF; END $$"
            )
            connection.exec_driver_sql(
                f"CREATE ROLE {quoted_unsafe_parent} NOLOGIN NOSUPERUSER "
                "NOCREATEDB NOCREATEROLE NOINHERIT "
                "NOREPLICATION NOBYPASSRLS"
            )
            created_unsafe_parent = True
            connection.exec_driver_sql(
                f"GRANT {quoted_unsafe_parent} TO "
                "walksafe_report_restore_authorizer"
            )
        with pytest.raises(SQLAlchemyError, match="must not inherit another role"):
            command.upgrade(config, "202608300003")
        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608300002"
        with admin_engine.connect().execution_options(
            isolation_level="AUTOCOMMIT"
        ) as connection:
            connection.exec_driver_sql(
                f"REVOKE {quoted_unsafe_parent} FROM "
                "walksafe_report_restore_authorizer"
            )
            connection.exec_driver_sql(
                f"DROP ROLE {quoted_unsafe_parent}"
            )
            created_unsafe_parent = False
        with disposable_engine.begin() as connection:
            connection.exec_driver_sql(
                "CREATE SCHEMA walksafe_restore_unsafe_owner "
                "AUTHORIZATION walksafe_report_restore_authorizer"
            )
        with pytest.raises(SQLAlchemyError, match="must not own database objects"):
            command.upgrade(config, "202608300003")
        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608300002"
        with disposable_engine.begin() as connection:
            connection.exec_driver_sql(
                "DROP SCHEMA walksafe_restore_unsafe_owner"
            )

        command.upgrade(config, "202608300003")
        with admin_engine.connect().execution_options(
            isolation_level="AUTOCOMMIT"
        ) as connection:
            connection.exec_driver_sql(
                f"CREATE ROLE {quoted_worker_login} LOGIN NOINHERIT "
                "NOSUPERUSER NOCREATEDB NOCREATEROLE "
                "NOREPLICATION NOBYPASSRLS"
            )
            created_login = True
            connection.exec_driver_sql(
                "GRANT walksafe_report_restore_worker TO "
                f"{quoted_worker_login} WITH ADMIN FALSE, INHERIT FALSE, SET TRUE"
            )

        with disposable_engine.begin() as connection:
            connection.exec_driver_sql(
                """
                CREATE FUNCTION public.walksafe_test_finish_restore_receipt(
                  p_plan_sha256 text,
                  p_restore_run_id uuid,
                  p_target_identity_sha256 text,
                  p_report_ids uuid[],
                  p_request_ids uuid[],
                  p_tombstone_ids uuid[],
                  p_entry_sha256s text[],
                  p_receipt_sha256 text,
                  p_receipt_bytes bytea,
                  p_applied_at timestamptz
                ) RETURNS void
                LANGUAGE plpgsql
                SECURITY DEFINER
                SET search_path = pg_catalog, public
                AS $function$
                BEGIN
                  INSERT INTO public.report_restore_reapply_authorizations (
                    plan_sha256, restore_run_id, data_boundary_id,
                    target_identity_sha256, source_fence_sha256,
                    trusted_head_sha256, inventory_sha256, action_count
                  ) VALUES (
                    p_plan_sha256, p_restore_run_id,
                    'walksafe-report-production', p_target_identity_sha256,
                    repeat('a', 64), repeat('b', 64), repeat('c', 64),
                    cardinality(p_report_ids)
                  );
                  INSERT INTO public.report_restore_reapply_authorized_actions (
                    plan_sha256, report_id, request_id, tombstone_id,
                    privacy_subject_hmac, account_generation,
                    request_status_version, entry_sha256,
                    report_row_present, bound_artifact_count
                  )
                  SELECT
                    p_plan_sha256, p_report_ids[item.index],
                    p_request_ids[item.index], p_tombstone_ids[item.index],
                    repeat('d', 64), 1, 1,
                    p_entry_sha256s[item.index], false, 1
                  FROM generate_subscripts(p_report_ids, 1) AS item(index);
                  INSERT INTO public.report_restore_reapply_effects (
                    plan_sha256, report_id, request_id, tombstone_id,
                    privacy_subject_hmac, account_generation, entry_sha256,
                    result, bound_artifact_count
                  )
                  SELECT
                    p_plan_sha256, p_report_ids[item.index],
                    p_request_ids[item.index], p_tombstone_ids[item.index],
                    repeat('d', 64), 1,
                    p_entry_sha256s[item.index], 'DELETED', 1
                  FROM generate_subscripts(p_report_ids, 1) AS item(index);
                  PERFORM public.walksafe_finish_report_restore_reapply(
                    p_plan_sha256, p_receipt_sha256,
                    p_receipt_bytes, p_applied_at
                  );
                END
                $function$
                """
            )
            connection.exec_driver_sql(
                "REVOKE ALL ON FUNCTION "
                "public.walksafe_test_finish_restore_receipt("
                "text,uuid,text,uuid[],uuid[],uuid[],text[],"
                "text,bytea,timestamptz) "
                "FROM PUBLIC"
            )
            connection.exec_driver_sql(
                "GRANT EXECUTE ON FUNCTION "
                "public.walksafe_test_finish_restore_receipt("
                "text,uuid,text,uuid[],uuid[],uuid[],text[],"
                "text,bytea,timestamptz) "
                "TO walksafe_report_restore_worker"
            )

        call_sql = text(
            "SELECT public.walksafe_test_finish_restore_receipt("
            ":plan_sha256, :restore_run_id, :target_identity_sha256, "
            ":report_ids, :request_ids, :tombstone_ids, :entry_sha256s, "
            ":receipt_sha256, :receipt_bytes, :applied_at)"
        )

        def call_as_worker(
            *,
            receipt_bytes: bytes,
            receipt_sha256: str,
            plan_sha256: str,
            restore_run_id: uuid.UUID,
            target_identity_sha256: str,
            results: tuple[tuple[uuid.UUID, str], ...],
            applied_at: datetime,
            reject: bool,
        ) -> None:
            parameters = {
                "plan_sha256": plan_sha256,
                "restore_run_id": restore_run_id,
                "target_identity_sha256": target_identity_sha256,
                "report_ids": [item[0] for item in results],
                "request_ids": [uuid.uuid4() for _item in results],
                "tombstone_ids": [uuid.uuid4() for _item in results],
                "entry_sha256s": [item[1] for item in results],
                "receipt_sha256": receipt_sha256,
                "receipt_bytes": receipt_bytes,
                "applied_at": applied_at,
            }
            with disposable_engine.connect() as connection:
                connection.exec_driver_sql(
                    f"SET SESSION AUTHORIZATION {quoted_worker_login}"
                )
                connection.exec_driver_sql(
                    "SET ROLE walksafe_report_restore_worker"
                )
                connection.commit()
                try:
                    if reject:
                        with pytest.raises(SQLAlchemyError) as invalid:
                            connection.execute(call_sql, parameters)
                        assert getattr(invalid.value.orig, "sqlstate", None) == "23514"
                        connection.rollback()
                    else:
                        connection.execute(call_sql, parameters)
                        connection.commit()
                finally:
                    if connection.in_transaction():
                        connection.rollback()
                    connection.exec_driver_sql("RESET SESSION AUTHORIZATION")
                    connection.commit()

        for invalid_kind in ("noncanonical", "wrong_hash", "json_null"):
            applied_at = datetime.now(UTC)
            plan_sha256 = hashlib.sha256(uuid.uuid4().bytes).hexdigest()
            restore_run_id = uuid.uuid4()
            target_identity_sha256 = hashlib.sha256(uuid.uuid4().bytes).hexdigest()
            report_id = uuid.uuid4()
            entry_sha256 = hashlib.sha256(uuid.uuid4().bytes).hexdigest()
            if invalid_kind == "wrong_hash":
                receipt_bytes, receipt_sha256 = _receipt_bytes(
                    plan_sha256=plan_sha256,
                    restore_run_id=restore_run_id,
                    target_identity_sha256=target_identity_sha256,
                    report_id=report_id,
                    entry_sha256=entry_sha256,
                    applied_at=applied_at,
                    receipt_sha256="f" * 64,
                )
            else:
                receipt_bytes, receipt_sha256 = _receipt_bytes(
                    plan_sha256=plan_sha256,
                    restore_run_id=restore_run_id,
                    target_identity_sha256=target_identity_sha256,
                    report_id=report_id,
                    entry_sha256=entry_sha256,
                    applied_at=applied_at,
                    compact=invalid_kind != "noncanonical",
                    null_plan=invalid_kind == "json_null",
                )
            call_as_worker(
                receipt_bytes=receipt_bytes,
                receipt_sha256=receipt_sha256,
                plan_sha256=plan_sha256,
                restore_run_id=restore_run_id,
                target_identity_sha256=target_identity_sha256,
                results=((report_id, entry_sha256),),
                applied_at=applied_at,
                reject=True,
            )

        applied_at = datetime.now(UTC)
        plan_sha256 = hashlib.sha256(uuid.uuid4().bytes).hexdigest()
        restore_run_id = uuid.uuid4()
        target_identity_sha256 = hashlib.sha256(uuid.uuid4().bytes).hexdigest()
        report_id = uuid.uuid4()
        entry_sha256 = hashlib.sha256(uuid.uuid4().bytes).hexdigest()
        receipt_bytes, receipt_sha256 = _receipt_bytes(
            plan_sha256=plan_sha256,
            restore_run_id=restore_run_id,
            target_identity_sha256=target_identity_sha256,
            report_id=report_id,
            entry_sha256=entry_sha256,
            applied_at=applied_at,
        )
        receipt_bytes = tombstones.report_restore_reapply_receipt_bytes(
            tombstones.ReportRestoreReapplyReceipt(
                plan_sha256=plan_sha256,
                restore_run_id=restore_run_id,
                data_boundary_id="walksafe-report-production",
                target_identity_sha256=target_identity_sha256,
                applied_at=applied_at,
                results=(
                    tombstones.ReportRestoreReapplyResult(
                        report_id=report_id,
                        entry_sha256=entry_sha256,
                        result="DELETED",
                    ),
                ),
                receipt_sha256=receipt_sha256,
            )
        )
        call_as_worker(
            receipt_bytes=receipt_bytes,
            receipt_sha256=receipt_sha256,
            plan_sha256=plan_sha256,
            restore_run_id=restore_run_id,
            target_identity_sha256=target_identity_sha256,
            results=((report_id, entry_sha256),),
            applied_at=applied_at,
            reject=False,
        )
        with disposable_engine.connect() as connection:
            persisted = connection.execute(
                text(
                    "SELECT receipt_sha256, receipt_bytes "
                    "FROM public.report_restore_reapply_receipts "
                    "WHERE plan_sha256 = :plan_sha256"
                ),
                {"plan_sha256": plan_sha256},
            ).one()
            assert persisted[0] == receipt_sha256
            assert bytes(persisted[1]) == receipt_bytes
            connection.rollback()

        multi_applied_at = datetime.now(UTC)
        multi_plan_sha256 = hashlib.sha256(uuid.uuid4().bytes).hexdigest()
        multi_restore_run_id = uuid.uuid4()
        multi_target_identity_sha256 = hashlib.sha256(
            uuid.uuid4().bytes
        ).hexdigest()
        multi_results = tuple(
            sorted(
                (
                    (uuid.uuid4(), hashlib.sha256(uuid.uuid4().bytes).hexdigest()),
                    (uuid.uuid4(), hashlib.sha256(uuid.uuid4().bytes).hexdigest()),
                ),
                key=lambda item: str(item[0]),
            )
        )
        swapped_receipt_bytes, swapped_receipt_sha256 = _multi_receipt_bytes(
            plan_sha256=multi_plan_sha256,
            restore_run_id=multi_restore_run_id,
            target_identity_sha256=multi_target_identity_sha256,
            results=tuple(reversed(multi_results)),
            applied_at=multi_applied_at,
        )
        call_as_worker(
            receipt_bytes=swapped_receipt_bytes,
            receipt_sha256=swapped_receipt_sha256,
            plan_sha256=multi_plan_sha256,
            restore_run_id=multi_restore_run_id,
            target_identity_sha256=multi_target_identity_sha256,
            results=multi_results,
            applied_at=multi_applied_at,
            reject=True,
        )
        multi_receipt_bytes, multi_receipt_sha256 = _multi_receipt_bytes(
            plan_sha256=multi_plan_sha256,
            restore_run_id=multi_restore_run_id,
            target_identity_sha256=multi_target_identity_sha256,
            results=multi_results,
            applied_at=multi_applied_at,
        )
        call_as_worker(
            receipt_bytes=multi_receipt_bytes,
            receipt_sha256=multi_receipt_sha256,
            plan_sha256=multi_plan_sha256,
            restore_run_id=multi_restore_run_id,
            target_identity_sha256=multi_target_identity_sha256,
            results=multi_results,
            applied_at=multi_applied_at,
            reject=False,
        )
        with disposable_engine.connect() as connection:
            persisted_multi = connection.execute(
                text(
                    "SELECT action_count, receipt_sha256, receipt_bytes "
                    "FROM public.report_restore_reapply_receipts "
                    "WHERE plan_sha256 = :plan_sha256"
                ),
                {"plan_sha256": multi_plan_sha256},
            ).one()
            assert persisted_multi[0] == 2
            assert persisted_multi[1] == multi_receipt_sha256
            assert bytes(persisted_multi[2]) == multi_receipt_bytes
            connection.rollback()

        with pytest.raises(SQLAlchemyError) as downgrade_rejected:
            command.downgrade(config, "202608300002")
        assert getattr(downgrade_rejected.value.orig, "sqlstate", None) == "55000"
        with disposable_engine.connect() as connection:
            assert connection.execute(
                text("SELECT version_num FROM alembic_version")
            ).scalar_one() == "202608300003"
            assert connection.execute(
                text(
                    "SELECT count(*) FROM public.report_restore_reapply_receipts "
                    "WHERE plan_sha256 = :plan_sha256"
                ),
                {"plan_sha256": plan_sha256},
            ).scalar_one() == 1
    finally:
        disposable_engine.dispose()
        if created_database:
            with admin_engine.connect().execution_options(
                isolation_level="AUTOCOMMIT"
            ) as connection:
                connection.execute(
                    text(
                        "SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                        "WHERE datname = :database_name AND pid <> pg_backend_pid()"
                    ),
                    {"database_name": database_name},
                ).all()
                connection.exec_driver_sql(
                    f"DROP DATABASE IF EXISTS {quoted_database}"
                )
        if created_login:
            with admin_engine.connect().execution_options(
                isolation_level="AUTOCOMMIT"
            ) as connection:
                connection.exec_driver_sql(
                    "REVOKE walksafe_report_restore_worker FROM "
                    f"{quoted_worker_login}"
                )
                connection.exec_driver_sql(
                    f"DROP ROLE IF EXISTS {quoted_worker_login}"
                )
        if created_unsafe_parent:
            with admin_engine.connect().execution_options(
                isolation_level="AUTOCOMMIT"
            ) as connection:
                connection.exec_driver_sql(
                    f"REVOKE {quoted_unsafe_parent} FROM "
                    "walksafe_report_restore_authorizer"
                )
                connection.exec_driver_sql(
                    f"DROP ROLE IF EXISTS {quoted_unsafe_parent}"
                )
        admin_engine.dispose()
