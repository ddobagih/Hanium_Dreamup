"""Encrypted report commit coordinator; owns journal, DB commit and post-commit recovery outcome."""
from __future__ import annotations

from backend.app.services.maintenance_lock import _ReportWriteLockExitError, _shared_report_write_lock
import logging
from pathlib import Path
from typing import Callable
from fastapi import HTTPException
from sqlalchemy.orm import Session
from backend.app.models import Report, ReportImageObject
from backend.app.services.storage_readiness_state import StorageReadinessState
from backend.app.services.report_storage import ReportImageCommitMetadata, complete_report_image_write, lock_report_storage_write_transaction, stage_report_image, validate_report_image_write
from backend.app.services.privacy_lifecycle import PrivacyLifecycleError
from backend.app.services.report_image_crypto import encrypt_report_image
from backend.app.services.report_image_keys import ReportImageKeyError, ReportImageKeyManager
from backend.app.uploads import image_suffix


def _commit_new_report_with_image(
    db: Session,
    report: Report,
    *,
    destination: Path,
    content: bytes,
    content_type: str,
    key_manager: ReportImageKeyManager,
    maintenance_lock_path: Path | None = None,
    maintenance_lock_group_gid: int | None = None,
    precommit_check: Callable[[], None] | None = None,
    storage_readiness: StorageReadinessState | None = None,
) -> None:
    token = storage_readiness.begin_commit("report", str(report.id)) if storage_readiness is not None else None
    staged = cleanup_complete = completed = False
    committed_report_id: str | None = None
    try:
        with _shared_report_write_lock(
            maintenance_lock_path,
            expected_group_gid=maintenance_lock_group_gid,
        ):
            lock_report_storage_write_transaction(db)
            try:
                key_manager.synchronize(db)
                active_key = key_manager.encryption_slot()
                assert active_key.material is not None
                encrypted = encrypt_report_image(
                    content,
                    report_id=report.id,
                    content_type=content_type,
                    key_id=active_key.key_id,
                    key=active_key.material,
                )
                staged = True
                pending = stage_report_image(destination, encrypted)
                db.add(report)
                image_object = ReportImageObject(
                    report_id=report.id,
                    storage_name=destination.name,
                    envelope_version=1,
                    algorithm="AES-256-GCM",
                    aad_version=1,
                    key_id=encrypted.key_id,
                    nonce=encrypted.nonce,
                    plaintext_sha256=encrypted.plaintext_sha256,
                    plaintext_size=encrypted.plaintext_length,
                    envelope_sha256=encrypted.envelope_sha256,
                    envelope_size=len(encrypted.envelope),
                    content_type=content_type,
                )
                db.add(image_object)
                if precommit_check is not None:
                    precommit_check()
                if (
                    pending.report_id != report.id
                    or pending.destination != destination
                    or image_object.report_id != report.id
                    or destination.name != f"{report.id}.wse"
                    or report.image_content_type != content_type
                    or report.image_path != f"/uploads/{report.id}{image_suffix(content_type)}"
                ):
                    raise RuntimeError("report logical image path and encrypted object metadata disagree")
                validate_report_image_write(
                    pending,
                    ReportImageCommitMetadata(
                        storage_name=image_object.storage_name,
                        envelope_version=image_object.envelope_version,
                        algorithm=image_object.algorithm,
                        aad_version=image_object.aad_version,
                        key_id=image_object.key_id,
                        nonce=image_object.nonce,
                        envelope_sha256=image_object.envelope_sha256,
                        envelope_size=image_object.envelope_size,
                        plaintext_sha256=image_object.plaintext_sha256,
                        plaintext_size=image_object.plaintext_size,
                        content_type=image_object.content_type,
                    ),
                )
                report_id_snapshot = str(report.id)
                db.commit()
                committed_report_id = report_id_snapshot
            except ReportImageKeyError as exc:
                db.rollback()
                raise HTTPException(
                    status_code=503,
                    detail={
                        "code": "report_image_encryption_unavailable",
                        "message": "The report was not stored because image encryption is unavailable.",
                    },
                ) from exc
            except PrivacyLifecycleError as exc:
                db.rollback()
                raise HTTPException(
                    status_code=exc.status_code,
                    detail={"code": exc.code, "message": exc.message},
                ) from exc
            except BaseException:
                db.rollback()
                raise
            try:
                db.refresh(report)
            except Exception as exc:
                try:
                    logging.getLogger(__name__).warning("report_postcommit_refresh_failed")
                except Exception:
                    pass
                raise HTTPException(
                    status_code=503,
                    detail={
                        "code": "report_postcommit_refresh_failed",
                        "message": "The report was stored, but its confirmation response is unavailable.",
                        "report_id": report_id_snapshot,
                    },
                ) from exc
            try:
                complete_report_image_write(pending)
                cleanup_complete = True
            except OSError:
                try:
                    logging.getLogger(__name__).warning("report_postcommit_journal_cleanup_deferred")
                except Exception:
                    pass
            except Exception as exc:
                try:
                    logging.getLogger(__name__).warning("report_postcommit_journal_cleanup_failed")
                except Exception:
                    pass
                raise HTTPException(
                    status_code=503,
                    detail={
                        "code": "report_postcommit_journal_cleanup_failed",
                        "message": "The report was stored, but its storage cleanup could not be completed.",
                        "report_id": report_id_snapshot,
                    },
                ) from exc
    except _ReportWriteLockExitError as exc:
        if committed_report_id is None:
            raise
        try:
            logging.getLogger(__name__).warning("report_postcommit_lock_exit_failed")
        except Exception:
            pass
        raise HTTPException(
                status_code=503,
                detail={
                    "code": "report_postcommit_lock_exit_failed",
                    "message": "The report was stored, but the maintenance lock could not be verified after the write.",
                    "report_id": committed_report_id,
                },
            ) from exc
    else:
        completed = True
    finally:
        if token is not None:
            if completed and cleanup_complete:
                storage_readiness.publish_success(token)
            elif staged:
                storage_readiness.publish_failure(token, "report_storage_recovery_pending")
            else:
                storage_readiness.publish_clean_abort(token)

