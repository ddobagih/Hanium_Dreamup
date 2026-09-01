from __future__ import annotations

from datetime import UTC, datetime, timedelta
import hashlib
import os
from pathlib import Path
import threading
import uuid

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
import pytest
from sqlalchemy import create_engine, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import OperationalError, ProgrammingError

from backend.app.services import report_restore_tombstones as tombstones
from backend.app.services.report_restore_handoff import (
    held_report_restore_source_fence,
    postgresql_runtime_identity,
    prepare_signed_evidence_paths,
    verify_stable_handoff,
    wait_for_stable_signed_evidence,
)
from backend.app.services.report_restore_postgres import (
    RESTORE_WORKER_ROLE,
    TARGET_ADVISORY_LOCK_KEY,
    RestoreInventoryContext,
    isolated_postgres_report_restore_target,
)
from backend.app.services.report_storage import REPORT_STORAGE_TRANSACTION_LOCK_KEY
from scripts.walksafe_environment_identity import database_identity_sha256


SOURCE_URL_ENV = "WALKSAFE_REPORT_RESTORE_TEST_SOURCE_DATABASE_URL"
TARGET_URL_ENV = "WALKSAFE_REPORT_RESTORE_TEST_TARGET_DATABASE_URL"
MAINTENANCE_LOCK_ENV = "WALKSAFE_REPORT_RESTORE_TEST_MAINTENANCE_LOCK_PATH"


pytestmark = pytest.mark.skipif(
    not os.environ.get(SOURCE_URL_ENV, "").strip()
    or not os.environ.get(TARGET_URL_ENV, "").strip()
    or not os.environ.get(MAINTENANCE_LOCK_ENV, "").strip(),
    reason="isolated PostgreSQL URLs and trusted maintenance lock are not configured",
)


def _public_bytes(private_key: Ed25519PrivateKey) -> bytes:
    return private_key.public_key().public_bytes(
        serialization.Encoding.Raw,
        serialization.PublicFormat.Raw,
    )


def test_same_postgres_cluster_is_rejected(
    tmp_path: Path,
) -> None:
    target_url = os.environ[TARGET_URL_ENV].strip()
    same_cluster_source_url = make_url(target_url).set(database="postgres")
    source_engine = create_engine(same_cluster_source_url, hide_parameters=True)
    try:
        with source_engine.connect() as connection:
            source_runtime_identity = postgresql_runtime_identity(connection)
    finally:
        source_engine.dispose()
    source_upload = tmp_path / "source"
    target_upload = tmp_path / "target-drill"
    source_upload.mkdir(mode=0o700)
    target_upload.mkdir(mode=0o700)
    inventory_context = RestoreInventoryContext(
        restore_run_id=uuid.uuid4(),
        backup_run_id="same-cluster-rejection",
        backup_manifest_sha256="a" * 64,
        restore_receipt_sha256="b" * 64,
        data_boundary_id="walksafe-report-production",
        privacy_hmac_key_version=7,
        source_identity_sha256=database_identity_sha256(
            same_cluster_source_url.render_as_string(hide_password=False)
        ),
        source_backup_created_at=datetime.now(UTC),
    )

    with pytest.raises(tombstones.ReportRestoreTombstoneError) as rejected:
        with isolated_postgres_report_restore_target(
            target_database_url=target_url,
            source_runtime_identity=source_runtime_identity,
            source_upload_root=source_upload,
            target_upload_root=target_upload,
            inventory_context=inventory_context,
            mutation_guard=lambda: None,
            target_admission_notifier=lambda _runtime, _pid: None,
        ):
            pytest.fail("same-cluster restore target was accepted")
    assert rejected.value.code == "restore_target_cluster_not_isolated"


def test_actual_postgres_artifact_commit_recovery_and_receipt_replay(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    request: pytest.FixtureRequest,
) -> None:
    source_url = os.environ[SOURCE_URL_ENV].strip()
    target_admin_url = os.environ[TARGET_URL_ENV].strip()
    source_identity = database_identity_sha256(source_url)
    source_upload = tmp_path / "source"
    target_upload = tmp_path / "target-drill"
    for directory in (source_upload, target_upload):
        directory.mkdir(mode=0o700)
    maintenance_lock = Path(os.environ[MAINTENANCE_LOCK_ENV].strip())
    evidence_parent = tmp_path / "signed-evidence"
    evidence_parent.mkdir(mode=0o700)
    evidence_paths = {
        "ledger": evidence_parent / "ledger.json",
        "ledger_signature": evidence_parent / "ledger.sig.json",
        "trusted_head": evidence_parent / "head.json",
        "head_signature": evidence_parent / "head.sig.json",
        "fence_binding": evidence_parent / "binding.json",
        "fence_binding_signature": evidence_parent / "binding.sig.json",
    }
    prepared_evidence = prepare_signed_evidence_paths(evidence_paths)
    request.addfinalizer(prepared_evidence.close)

    target_setup_engine = create_engine(target_admin_url, hide_parameters=True)
    report_id: uuid.UUID
    target_login = f"walksafe_restore_test_{uuid.uuid4().hex[:12]}"
    target_password = uuid.uuid4().hex + uuid.uuid4().hex
    target_login_created = False
    target_url = make_url(target_admin_url).set(
        username=target_login,
        password=target_password,
    ).render_as_string(hide_password=False)
    target_database_name = make_url(target_admin_url).database
    assert target_database_name is not None
    target_maintenance_url = make_url(target_admin_url).set(database="postgres")
    plan_to_authorize: tombstones.ReportRestoreReapplyPlan | None = None
    target_admission_was_disabled = False

    def set_target_admission(*, allowed: bool, keep_backend_pid: int | None = None) -> None:
        maintenance = create_engine(target_maintenance_url, hide_parameters=True)
        try:
            with maintenance.connect().execution_options(
                isolation_level="AUTOCOMMIT"
            ) as connection:
                statement = connection.execute(
                    text(
                        "SELECT format('ALTER DATABASE %I WITH ALLOW_CONNECTIONS %s', "
                        "CAST(:database_name AS text), CAST(:allowed AS text))"
                    ),
                    {
                        "database_name": target_database_name,
                        "allowed": "true" if allowed else "false",
                    },
                ).scalar_one()
                connection.exec_driver_sql(statement)
                if not allowed:
                    connection.execute(
                        text(
                            "SELECT pg_terminate_backend(pid) "
                            "FROM pg_catalog.pg_stat_activity "
                            "WHERE datname = :database_name "
                            "AND pid <> pg_backend_pid() AND pid <> :keep_backend_pid"
                        ),
                        {
                            "database_name": target_database_name,
                            "keep_backend_pid": keep_backend_pid,
                        },
                    ).all()
        finally:
            maintenance.dispose()

    def authorize_and_close_target_admission(
        _runtime: object,
        backend_pid: int,
    ) -> None:
        nonlocal target_admission_was_disabled
        assert plan_to_authorize is not None
        with target_setup_engine.begin() as connection:
            connection.execute(
                text(
                    "INSERT INTO public.report_restore_reapply_authorizations ("
                    "plan_sha256, restore_run_id, data_boundary_id, "
                    "target_identity_sha256, source_fence_sha256, "
                    "trusted_head_sha256, inventory_sha256, action_count) VALUES ("
                    ":plan_sha256, :restore_run_id, :data_boundary_id, "
                    ":target_identity_sha256, :source_fence_sha256, "
                    ":trusted_head_sha256, :inventory_sha256, :action_count)"
                ),
                {
                    "plan_sha256": plan_to_authorize.plan_sha256,
                    "restore_run_id": plan_to_authorize.restore_run_id,
                    "data_boundary_id": plan_to_authorize.data_boundary_id,
                    "target_identity_sha256": (
                        plan_to_authorize.target_identity_sha256
                    ),
                    "source_fence_sha256": (
                        tombstones.report_restore_source_fence_sha256(
                            plan_to_authorize.source_fence
                        )
                    ),
                    "trusted_head_sha256": plan_to_authorize.trusted_head_sha256,
                    "inventory_sha256": plan_to_authorize.inventory_sha256,
                    "action_count": len(plan_to_authorize.actions),
                },
            )
            for action in plan_to_authorize.actions:
                connection.execute(
                    text(
                        "INSERT INTO public.report_restore_reapply_authorized_actions ("
                        "plan_sha256, report_id, request_id, tombstone_id, "
                        "privacy_subject_hmac, account_generation, "
                        "request_status_version, entry_sha256, "
                        "report_row_present, bound_artifact_count) VALUES ("
                        ":plan_sha256, :report_id, :request_id, :tombstone_id, "
                        ":privacy_subject_hmac, :account_generation, "
                        ":request_status_version, :entry_sha256, "
                        ":report_row_present, :bound_artifact_count)"
                    ),
                    {
                        "plan_sha256": plan_to_authorize.plan_sha256,
                        "report_id": action.report_id,
                        "request_id": action.request_id,
                        "tombstone_id": action.tombstone_id,
                        "privacy_subject_hmac": action.privacy_subject_hmac,
                        "account_generation": action.account_generation,
                        "request_status_version": action.request_status_version,
                        "entry_sha256": action.entry_sha256,
                        "report_row_present": action.report_row_present,
                        "bound_artifact_count": action.bound_artifact_count,
                    },
                )
        set_target_admission(allowed=False, keep_backend_pid=backend_pid)
        target_admission_was_disabled = True
    artifact_bytes = b"restore-target-artifact" + b"\0" * 378
    assert len(artifact_bytes) == 401
    try:
        with target_setup_engine.begin() as connection:
            create_login = connection.execute(
                text(
                        "SELECT format('CREATE ROLE %I LOGIN NOSUPERUSER "
                        "NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS "
                        "PASSWORD %L', CAST(:role_name AS text), "
                        "CAST(:password AS text))"
                ),
                {"role_name": target_login, "password": target_password},
            ).scalar_one()
            connection.exec_driver_sql(create_login)
            connection.exec_driver_sql(
                f"GRANT {RESTORE_WORKER_ROLE} TO {target_login} "
                "WITH INHERIT FALSE, SET TRUE, ADMIN FALSE"
            )
            report_id = uuid.uuid4()
            connection.execute(
                text(
                    "INSERT INTO public.reports ("
                    "id, class_id, class_name, confidence, bbox_x, bbox_y, "
                    "bbox_width, bbox_height, captured_at, source, image_path, "
                    "image_content_type, metadata) VALUES ("
                    ":report_id, 0, 'damaged_tactile_block', 0.9, 0.1, 0.1, "
                    "0.5, 0.5, clock_timestamp(), 'android', :image_path, "
                    "'image/jpeg', '{}'::jsonb)"
                ),
                {
                    "report_id": report_id,
                    "image_path": f"/uploads/{report_id}.jpg",
                },
            )
            connection.execute(
                text(
                    "INSERT INTO public.report_image_objects ("
                    "report_id, storage_name, envelope_version, algorithm, "
                    "aad_version, key_id, nonce, plaintext_sha256, "
                    "plaintext_size, envelope_sha256, envelope_size, content_type) "
                    "VALUES (:report_id, :storage_name, 1, 'AES-256-GCM', 1, "
                    ":key_id, :nonce, :plaintext_sha256, 128, "
                    ":envelope_sha256, 256, 'image/jpeg')"
                ),
                {
                    "report_id": report_id,
                    "storage_name": f"{report_id}.wse",
                    "key_id": f"restore-test-{uuid.uuid4().hex}",
                    "nonce": uuid.uuid4().bytes[:12],
                    "plaintext_sha256": hashlib.sha256(
                        b"restore plaintext"
                    ).hexdigest(),
                    "envelope_sha256": hashlib.sha256(
                        b"restore envelope"
                    ).hexdigest(),
                },
            )
            connection.execute(
                text(
                    "UPDATE public.reports SET privacy_subject_hmac = :owner, "
                    "account_generation = 3 WHERE id = :report_id"
                ),
                {"owner": "a" * 64, "report_id": report_id},
            )
            connection.execute(
                text(
                    "UPDATE public.report_image_objects SET envelope_sha256 = :digest, "
                    "envelope_size = :size WHERE report_id = :report_id"
                ),
                {
                    "digest": hashlib.sha256(artifact_bytes).hexdigest(),
                    "size": len(artifact_bytes),
                    "report_id": report_id,
                },
            )
        target_login_created = True
        target_setup_engine.dispose()
        artifact = target_upload / f"{report_id}.wse"
        artifact.write_bytes(artifact_bytes)
        artifact.chmod(0o600)

        restore_run_id = uuid.uuid4()
        backup_manifest_sha256 = "b" * 64
        with held_report_restore_source_fence(
            source_database_url=source_url,
            maintenance_lock_path=maintenance_lock,
            restore_run_id=restore_run_id,
            backup_run_id="postgres-restore-drill",
            backup_manifest_sha256=backup_manifest_sha256,
            source_identity_sha256=source_identity,
            data_boundary_id="walksafe-report-production",
        ) as held_source:
            prepared_evidence.verify_absent_unchanged()
            contender_engine = create_engine(source_url, hide_parameters=True)
            try:
                with contender_engine.connect() as contender:
                    assert contender.execute(
                        text(
                            "SELECT pg_try_advisory_lock("
                            "hashtextextended(:lock_key, 0))"
                        ),
                        {
                            "lock_key": (
                                tombstones.REPORT_DELETION_ADVISORY_LOCK_KEY
                            )
                        },
                    ).scalar_one() is False
                    contender.rollback()
            finally:
                contender_engine.dispose()
            cutoff = held_source.connection.execute(
                text("SELECT clock_timestamp()")
            ).scalar_one()
            held_source.connection.rollback()
            private_key = Ed25519PrivateKey.generate()
            public_bytes = _public_bytes(private_key)
            ledger = tombstones.build_report_tombstone_ledger_bytes(
                (
                    tombstones.ReportDeletionTombstoneRecord(
                        tombstone_id=uuid.uuid4(),
                        request_id=uuid.uuid4(),
                        report_id=report_id,
                        privacy_subject_hmac="a" * 64,
                        account_generation=3,
                        request_status_version=2,
                        external_copy_count=0,
                        deleted_at=cutoff,
                    ),
                ),
                ledger_id=uuid.uuid4(),
                data_boundary_id="walksafe-report-production",
                privacy_hmac_key_version=7,
                cutoff_at=cutoff,
            )
            head = tombstones.build_report_tombstone_head_bytes(
                ledger,
                issued_at=cutoff,
                predecessor_head_sha256=None,
            )
            key = tombstones.build_key_descriptor(public_bytes)
            key_id = tombstones.ed25519_key_id(public_bytes)
            binding_bytes = (
                tombstones.build_report_restore_source_fence_binding_bytes(
                    held_source.request,
                    ledger_bytes=ledger,
                    trusted_head_bytes=head,
                )
            )
            publication_payloads = {
                "ledger": ledger,
                "ledger_signature": tombstones.build_signature_descriptor(
                    ledger,
                    public_key_bytes=public_bytes,
                    signature=private_key.sign(
                        tombstones.LEDGER_SIGNATURE_DOMAIN + ledger
                    ),
                ),
                "trusted_head": head,
                "head_signature": tombstones.build_signature_descriptor(
                    head,
                    public_key_bytes=public_bytes,
                    signature=private_key.sign(
                        tombstones.HEAD_SIGNATURE_DOMAIN + head
                    ),
                ),
                "fence_binding": binding_bytes,
                "fence_binding_signature": tombstones.build_signature_descriptor(
                    binding_bytes,
                    public_key_bytes=public_bytes,
                    signature=private_key.sign(
                        tombstones.SOURCE_FENCE_BINDING_SIGNATURE_DOMAIN
                        + binding_bytes
                    ),
                ),
            }
            publication_errors: list[BaseException] = []

            def publish_test_evidence() -> None:
                try:
                    for label, payload in publication_payloads.items():
                        destination = evidence_paths[label]
                        temporary = destination.with_name(
                            f".{destination.name}.{uuid.uuid4().hex}.tmp"
                        )
                        temporary.write_bytes(payload)
                        temporary.chmod(0o600)
                        os.replace(temporary, destination)
                except BaseException as exc:  # pragma: no cover - assertion below
                    publication_errors.append(exc)

            publisher = threading.Thread(target=publish_test_evidence, daemon=True)
            publisher.start()
            evidence = wait_for_stable_signed_evidence(
                evidence_paths,
                timeout_seconds=5,
                verify_source_fence=held_source.verify_request_held,
                prepared=prepared_evidence,
            )
            request.addfinalizer(evidence.close)
            publisher.join(timeout=5)
            assert not publisher.is_alive()
            assert publication_errors == []
            bundle, source_fence = verify_stable_handoff(
                held_source=held_source,
                evidence=evidence,
                trusted_key_bytes=key,
                expected_key_id=key_id,
            )

            mutation_guard_calls = 0
            fail_mutation_guard_at: int | None = None

            def verify_mutation_boundary() -> None:
                nonlocal mutation_guard_calls
                held_source.verify_request_held()
                evidence.verify_unchanged()
                mutation_guard_calls += 1
                if mutation_guard_calls == fail_mutation_guard_at:
                    raise tombstones.ReportRestoreTombstoneError(
                        "restore_source_fence_not_held",
                        "simulated source fence loss at the target commit boundary",
                    )

            context = RestoreInventoryContext(
                restore_run_id=restore_run_id,
                backup_run_id="postgres-restore-drill",
                backup_manifest_sha256=backup_manifest_sha256,
                restore_receipt_sha256="c" * 64,
                data_boundary_id="walksafe-report-production",
                privacy_hmac_key_version=7,
                source_identity_sha256=source_identity,
                source_backup_created_at=held_source.request.acquired_at
                - timedelta(minutes=1),
            )
            with isolated_postgres_report_restore_target(
                target_database_url=target_url,
                source_runtime_identity=held_source.runtime_identity,
                source_upload_root=source_upload,
                target_upload_root=target_upload,
                inventory_context=context,
                mutation_guard=verify_mutation_boundary,
                target_admission_notifier=authorize_and_close_target_admission,
                target_admission_timeout_seconds=5,
            ) as target:
                evidence.verify_unchanged()
                with pytest.raises(ProgrammingError, match="permission denied"):
                    target.connection.exec_driver_sql(
                        "DELETE FROM public.reports WHERE false"
                    )
                target.connection.rollback()
                with pytest.raises(ProgrammingError, match="permission denied"):
                    target.connection.exec_driver_sql(
                        "INSERT INTO public.report_restore_reapply_effects DEFAULT VALUES"
                    )
                target.connection.rollback()
                with pytest.raises(ProgrammingError, match="permission denied"):
                    target.connection.exec_driver_sql(
                        "INSERT INTO public.report_restore_reapply_postchecks DEFAULT VALUES"
                    )
                target.connection.rollback()
                target._assert_role()
                target._assert_isolated()
                target_contender = create_engine(target_admin_url, hide_parameters=True)
                try:
                    with target_contender.connect() as contender:
                        locked = contender.execute(
                            text(
                                "SELECT "
                                "pg_try_advisory_lock(hashtextextended(:target_key, 0)), "
                                "pg_try_advisory_lock(hashtextextended(:storage_key, 0))"
                            ),
                            {
                                "target_key": TARGET_ADVISORY_LOCK_KEY,
                                "storage_key": REPORT_STORAGE_TRANSACTION_LOCK_KEY,
                            },
                        ).one()
                        assert tuple(locked) == (False, False)
                        contender.rollback()
                finally:
                    target_contender.dispose()

                dry_before = target.build_inventory()
                dry_plan = tombstones.plan_report_restore_reapply(
                    bundle,
                    dry_before,
                    source_fence=source_fence,
                )
                dry_outcome = tombstones.execute_report_restore_reapply(
                    dry_plan,
                    target,
                )
                assert dry_outcome.mode == "DRY_RUN"
                assert artifact.read_bytes() == artifact_bytes
                assert not (
                    target_upload / ".report-deletion-quarantine"
                ).exists()

                before = target.build_inventory()
                plan = tombstones.plan_report_restore_reapply(
                    bundle,
                    before,
                    source_fence=source_fence,
                )
                plan_to_authorize = plan
                assert len(plan.actions) == 1

                drift_bytes = b"x" * len(artifact_bytes)
                artifact.write_bytes(drift_bytes)
                artifact.chmod(0o600)
                with target_setup_engine.begin() as connection:
                    connection.execute(
                        text(
                            "UPDATE public.report_image_objects "
                            "SET envelope_sha256 = :digest, envelope_size = :size "
                            "WHERE report_id = :report_id"
                        ),
                        {
                            "digest": hashlib.sha256(drift_bytes).hexdigest(),
                            "size": len(drift_bytes),
                            "report_id": report_id,
                        },
                    )
                with pytest.raises(tombstones.ReportRestoreTombstoneError) as drifted:
                    tombstones.execute_report_restore_reapply(
                        plan,
                        target,
                        dry_run=False,
                        confirmation=tombstones.APPLY_CONFIRMATION,
                        source_fence_verifier=held_source,
                    )
                assert drifted.value.code == "restore_reapply_target_changed"
                blocked = create_engine(
                    target_admin_url,
                    hide_parameters=True,
                    connect_args={"connect_timeout": 2},
                )
                try:
                    with pytest.raises(OperationalError, match="not currently accepting"):
                        with blocked.connect():
                            pass
                finally:
                    blocked.dispose()
                set_target_admission(allowed=True)
                target_setup_engine.dispose()
                artifact.write_bytes(artifact_bytes)
                artifact.chmod(0o600)
                with target_setup_engine.begin() as connection:
                    connection.execute(
                        text(
                            "UPDATE public.report_image_objects "
                            "SET envelope_sha256 = :digest, envelope_size = :size "
                            "WHERE report_id = :report_id"
                        ),
                        {
                            "digest": hashlib.sha256(artifact_bytes).hexdigest(),
                            "size": len(artifact_bytes),
                            "report_id": report_id,
                        },
                    )
                set_target_admission(
                    allowed=False,
                    keep_backend_pid=target.backend_pid,
                )

                fail_mutation_guard_at = mutation_guard_calls + 6
                with pytest.raises(tombstones.ReportRestoreTombstoneError) as fenced:
                    tombstones.execute_report_restore_reapply(
                        plan,
                        target,
                        dry_run=False,
                        confirmation=tombstones.APPLY_CONFIRMATION,
                        source_fence_verifier=held_source,
                    )
                assert fenced.value.code == "restore_source_fence_not_held"
                assert mutation_guard_calls >= fail_mutation_guard_at
                fail_mutation_guard_at = None
                assert artifact.read_bytes() == artifact_bytes
                assert target.connection.execute(
                    text("SELECT 1 FROM public.reports WHERE id = :report_id"),
                    {"report_id": report_id},
                ).scalar_one() == 1
                assert target.connection.execute(
                    text("SELECT count(*) FROM public.report_restore_reapply_receipts")
                ).scalar_one() == 0
                assert target.connection.execute(
                    text("SELECT count(*) FROM public.report_restore_reapply_effects")
                ).scalar_one() == 0
                target.connection.rollback()

                original_rename = os.rename
                failed_stage_once = False

                def fail_after_stage_move_once(
                    source: str | bytes | os.PathLike[str] | os.PathLike[bytes],
                    destination: str | bytes | os.PathLike[str] | os.PathLike[bytes],
                    *,
                    src_dir_fd: int | None = None,
                    dst_dir_fd: int | None = None,
                ) -> None:
                    nonlocal failed_stage_once
                    original_rename(
                        source,
                        destination,
                        src_dir_fd=src_dir_fd,
                        dst_dir_fd=dst_dir_fd,
                    )
                    if not failed_stage_once and source == artifact.name:
                        failed_stage_once = True
                        raise InterruptedError(
                            "simulated signal after artifact staging"
                        )

                monkeypatch.setattr(os, "rename", fail_after_stage_move_once)
                with pytest.raises(
                    tombstones.ReportRestoreTombstoneError,
                    match="could not be staged safely",
                ):
                    tombstones.execute_report_restore_reapply(
                        plan,
                        target,
                        dry_run=False,
                        confirmation=tombstones.APPLY_CONFIRMATION,
                        source_fence_verifier=held_source,
                    )
                assert failed_stage_once
                assert artifact.read_bytes() == artifact_bytes
                quarantine = target_upload / ".report-deletion-quarantine"
                assert quarantine.is_dir()
                assert list(quarantine.iterdir()) == []
                monkeypatch.setattr(os, "rename", original_rename)

                applied_at = target.connection.execute(
                    text("SELECT clock_timestamp()")
                ).scalar_one()
                target.connection.rollback()
                original_record_postcheck = target._record_postcheck
                failed_postcheck_record_once = False

                def fail_first_passed_postcheck_record(
                    plan_sha256: str,
                    *,
                    outcome: str,
                    failure_code: str | None,
                ) -> None:
                    nonlocal failed_postcheck_record_once
                    if outcome == "PASSED" and not failed_postcheck_record_once:
                        failed_postcheck_record_once = True
                        raise tombstones.ReportRestoreTombstoneError(
                            "restore_reapply_postcheck_record_failed",
                            "simulated crash before durable post-check recording",
                        )
                    original_record_postcheck(
                        plan_sha256,
                        outcome=outcome,
                        failure_code=failure_code,
                    )

                monkeypatch.setattr(
                    target,
                    "_record_postcheck",
                    fail_first_passed_postcheck_record,
                )
                with pytest.raises(tombstones.ReportRestorePostCommitError) as postcheck:
                    tombstones.execute_report_restore_reapply(
                        plan,
                        target,
                        dry_run=False,
                        confirmation=tombstones.APPLY_CONFIRMATION,
                        applied_at=applied_at,
                        source_fence_verifier=held_source,
                    )
                assert postcheck.value.code == "restore_reapply_postcheck_record_failed"
                assert failed_postcheck_record_once
                assert target.mutation_state == "DB_COMMITTED_POSTCHECK_REQUIRED"
                assert target.postcheck_error is None
                assert not artifact.exists()
                assert target.connection.execute(
                    text(
                        "SELECT count(*) FROM public.report_restore_reapply_postchecks "
                        "WHERE plan_sha256 = :plan_sha256"
                    ),
                    {"plan_sha256": plan.plan_sha256},
                ).scalar_one() == 0
                target.connection.rollback()
                monkeypatch.setattr(
                    target,
                    "_record_postcheck",
                    original_record_postcheck,
                )

                # A committed receipt with no outcome represents only an
                # interrupted post-check. Live fences and artifact recovery
                # must pass before it can become PASSED and replayable.
                outcome = tombstones.execute_report_restore_reapply(
                    plan,
                    target,
                    dry_run=False,
                    confirmation=tombstones.APPLY_CONFIRMATION,
                    source_fence_verifier=held_source,
                )
                assert outcome.mode == "REPLAYED"
                assert outcome.receipt is not None
                assert tuple(
                    target.connection.execute(
                        text(
                            "SELECT outcome, failure_code "
                            "FROM public.report_restore_reapply_postchecks "
                            "WHERE plan_sha256 = :plan_sha256"
                        ),
                        {"plan_sha256": plan.plan_sha256},
                    ).one()
                ) == ("PASSED", None)
                target.connection.rollback()
                quarantine = target_upload / ".report-deletion-quarantine"
                assert quarantine.is_dir()
                assert list(quarantine.iterdir()) == []

                replay = tombstones.execute_report_restore_reapply(
                    plan,
                    target,
                    dry_run=False,
                    confirmation=tombstones.APPLY_CONFIRMATION,
                    source_fence_verifier=held_source,
                )
                assert replay.mode == "REPLAYED"
                assert replay.receipt == outcome.receipt
                post = target.build_inventory(reconcile=True)
                gate = tombstones.assert_report_restore_publishable(
                    bundle,
                    before_inventory=before,
                    post_inventory=post,
                    receipt_bytes=(
                        tombstones.report_restore_reapply_receipt_bytes(
                            outcome.receipt
                        )
                    ),
                    source_fence=source_fence,
                    source_fence_verifier=held_source,
                )
                evidence.verify_unchanged()
                assert gate.verdict == "PASS"
                assert post.items == ()

                # A distinct committed plan with FAILED is immutable and must
                # stay blocked even when in-memory adapter state is cleared.
                failed_plan_sha256 = hashlib.sha256(uuid.uuid4().bytes).hexdigest()
                failed_report_id = uuid.uuid4()
                failed_request_id = uuid.uuid4()
                failed_tombstone_id = uuid.uuid4()
                failed_receipt_sha256 = hashlib.sha256(
                    uuid.uuid4().bytes
                ).hexdigest()
                failed_entry_sha256 = hashlib.sha256(
                    uuid.uuid4().bytes
                ).hexdigest()
                failed_applied_at = target.connection.execute(
                    text("SELECT clock_timestamp()")
                ).scalar_one()
                target.connection.rollback()
                set_target_admission(allowed=True)
                target_setup_engine.dispose()
                with target_setup_engine.begin() as connection:
                    connection.execute(
                        text(
                            "INSERT INTO public.report_restore_reapply_authorizations ("
                            "plan_sha256, restore_run_id, data_boundary_id, "
                            "target_identity_sha256, source_fence_sha256, "
                            "trusted_head_sha256, inventory_sha256, action_count) "
                            "VALUES (:plan_sha256, :restore_run_id, :data_boundary_id, "
                            ":target_identity_sha256, :source_fence_sha256, "
                            ":trusted_head_sha256, :inventory_sha256, 1)"
                        ),
                        {
                            "plan_sha256": failed_plan_sha256,
                            "restore_run_id": restore_run_id,
                            "data_boundary_id": context.data_boundary_id,
                            "target_identity_sha256": target.target_identity_sha256,
                            "source_fence_sha256": (
                                tombstones.report_restore_source_fence_sha256(
                                    source_fence
                                )
                            ),
                            "trusted_head_sha256": bundle.head_sha256,
                            "inventory_sha256": hashlib.sha256(
                                b"durable-failed-postcheck"
                            ).hexdigest(),
                        },
                    )
                    connection.execute(
                        text(
                            "INSERT INTO public.report_restore_reapply_authorized_actions ("
                            "plan_sha256, report_id, request_id, tombstone_id, "
                            "privacy_subject_hmac, account_generation, "
                            "request_status_version, entry_sha256, "
                            "report_row_present, bound_artifact_count) VALUES ("
                            ":plan_sha256, :report_id, :request_id, :tombstone_id, "
                            ":privacy_subject_hmac, 1, 1, :entry_sha256, false, 1)"
                        ),
                        {
                            "plan_sha256": failed_plan_sha256,
                            "report_id": failed_report_id,
                            "request_id": failed_request_id,
                            "tombstone_id": failed_tombstone_id,
                            "privacy_subject_hmac": "d" * 64,
                            "entry_sha256": failed_entry_sha256,
                        },
                    )
                    connection.execute(
                        text(
                            "INSERT INTO public.report_restore_reapply_effects ("
                            "plan_sha256, report_id, request_id, tombstone_id, "
                            "privacy_subject_hmac, account_generation, entry_sha256, "
                            "result, bound_artifact_count) VALUES ("
                            ":plan_sha256, :report_id, :request_id, :tombstone_id, "
                            ":privacy_subject_hmac, 1, :entry_sha256, 'DELETED', 1)"
                        ),
                        {
                            "plan_sha256": failed_plan_sha256,
                            "report_id": failed_report_id,
                            "request_id": failed_request_id,
                            "tombstone_id": failed_tombstone_id,
                            "privacy_subject_hmac": "d" * 64,
                            "entry_sha256": failed_entry_sha256,
                        },
                    )
                    connection.execute(
                        text(
                            "INSERT INTO public.report_restore_reapply_receipts ("
                            "plan_sha256, restore_run_id, data_boundary_id, "
                            "target_identity_sha256, source_fence_sha256, "
                            "trusted_head_sha256, action_count, receipt_sha256, "
                            "receipt_bytes, applied_at) VALUES ("
                            ":plan_sha256, :restore_run_id, :data_boundary_id, "
                            ":target_identity_sha256, :source_fence_sha256, "
                            ":trusted_head_sha256, 1, :receipt_sha256, "
                            ":receipt_bytes, :applied_at)"
                        ),
                        {
                            "plan_sha256": failed_plan_sha256,
                            "restore_run_id": restore_run_id,
                            "data_boundary_id": context.data_boundary_id,
                            "target_identity_sha256": target.target_identity_sha256,
                            "source_fence_sha256": (
                                tombstones.report_restore_source_fence_sha256(
                                    source_fence
                                )
                            ),
                            "trusted_head_sha256": bundle.head_sha256,
                            "receipt_sha256": failed_receipt_sha256,
                            "receipt_bytes": b"{}",
                            "applied_at": failed_applied_at,
                        },
                    )
                set_target_admission(
                    allowed=False,
                    keep_backend_pid=target.backend_pid,
                )
                target._record_postcheck(
                    failed_plan_sha256,
                    outcome="FAILED",
                    failure_code="restore_target_not_isolated",
                )
                with pytest.raises(tombstones.ReportRestoreTombstoneError) as immutable:
                    target._record_postcheck(
                        failed_plan_sha256,
                        outcome="PASSED",
                        failure_code=None,
                    )
                assert immutable.value.code == "restore_reapply_postcheck_record_failed"
                for _ in range(2):
                    target.postcheck_error = None
                    target.mutation_state = "DB_COMMITTED_RECONCILIATION_REQUIRED"
                    with pytest.raises(
                        tombstones.ReportRestorePostCommitError
                    ) as persisted:
                        target.load_receipt(failed_plan_sha256)
                    assert persisted.value.code == "restore_target_not_isolated"
                    assert target.mutation_state == "DB_COMMITTED_POSTCHECK_FAILED"
            evidence.close()
    finally:
        if target_admission_was_disabled:
            set_target_admission(allowed=True)
        target_setup_engine.dispose()
        if target_login_created:
            cleanup_engine = create_engine(target_admin_url, hide_parameters=True)
            try:
                with cleanup_engine.begin() as connection:
                    connection.exec_driver_sql(
                        f"REVOKE {RESTORE_WORKER_ROLE} FROM {target_login}"
                    )
                    connection.exec_driver_sql(
                        f"DROP ROLE IF EXISTS {target_login}"
                    )
            finally:
                cleanup_engine.dispose()
