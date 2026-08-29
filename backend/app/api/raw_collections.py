"""Default-off EPIC-07 raw collection contract and pre-storage gates."""

from __future__ import annotations

from functools import partial
import hashlib
from typing import Any, Callable, Protocol

from anyio import to_thread
from fastapi import APIRouter, Depends, Header, HTTPException, Path, Request, Response, status
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.config import Settings
from backend.app.database import get_db
from backend.app.api.reports import shared_maintenance_write_lock
from backend.app.field_test_security import (
    FieldTestAccess,
    RawCollectionOperation,
    VerifiedActorAssertion,
    VerifiedRawCollectionRequestProof,
)
from backend.app.schemas import (
    RAW_CANONICAL_UUID_PATTERN,
    RAW_CHUNK_MAX_BYTES,
    RawCollectionChunkAckV1,
    RawCollectionCommitV1,
    RawCollectionErrorResponseV1,
    RawCollectionManifestV1,
    RawCollectionPurpose,
    RawCollectionReceiptV1,
    RawCollectionReceiptV2,
    RawCollectionStatusV1,
    raw_collection_commit_sha256,
)
from backend.app.services.privacy_lifecycle import PrivacyLifecycleError
from backend.app.services.raw_collection_ingest import (
    RawCollectionAdmission,
    RawCollectionStorageError,
    authorize_raw_collection_write,
)


AdmissionGate = Callable[..., RawCollectionAdmission]
RAW_ERROR_RESPONSES = {
    error_status: {"model": RawCollectionErrorResponseV1}
    for error_status in (400, 401, 403, 404, 409, 413, 415, 422, 429, 503)
}


class RawCollectionStorageHandler(Protocol):
    """Durable raw collection storage implementation point."""

    def put_manifest(
        self,
        db: Session,
        admission: RawCollectionAdmission,
        manifest: RawCollectionManifestV1,
    ) -> tuple[RawCollectionStatusV1, bool]: ...

    def put_chunk(
        self,
        db: Session,
        admission: RawCollectionAdmission,
        *,
        collection_id: str,
        object_id: str,
        index: int,
        walk_id: str,
        manifest_sha256: str,
        content: bytes,
        content_sha256: str,
    ) -> tuple[RawCollectionChunkAckV1, bool]: ...

    def get_status(
        self,
        db: Session,
        *,
        actor_id: str,
        account_generation: int,
        collection_id: str,
        purpose: RawCollectionPurpose,
        walk_id: str,
        manifest_sha256: str,
    ) -> RawCollectionStatusV1: ...

    def commit(
        self,
        db: Session,
        admission: RawCollectionAdmission,
        payload: RawCollectionCommitV1,
        *,
        walk_id: str,
    ) -> RawCollectionReceiptV1 | RawCollectionReceiptV2: ...


def _error(status_code: int, code: str, message: str) -> HTTPException:
    return HTTPException(
        status_code=status_code,
        detail={"code": code, "message": message},
        headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
    )


def _require_enabled(settings: Settings) -> None:
    if not settings.raw_ingest_enabled:
        raise _error(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "raw_ingest_disabled",
            "Raw collection ingest is not enabled.",
        )


def _verified_gateway_actor(
    request: Request,
    *,
    actor_id: str,
    account_generation: int,
    operation: RawCollectionOperation,
    purpose: RawCollectionPurpose,
    walk_id: str,
    manifest_sha256: str,
    consent_receipt_sha256: str | None,
    chunk_sha256: str | None,
    commit_sha256: str | None,
) -> tuple[VerifiedActorAssertion, VerifiedRawCollectionRequestProof]:
    verified = getattr(request.state, "verified_actor_assertion", None)
    if (
        not isinstance(verified, VerifiedActorAssertion)
        or verified.access is not FieldTestAccess.FIELD
        or verified.actor_id != actor_id
        or verified.account_generation != account_generation
    ):
        raise _error(
            status.HTTP_401_UNAUTHORIZED,
            "raw_gateway_assertion_required",
            "A verified Gateway actor assertion is required for raw collection access.",
        )
    proof = getattr(request.state, "verified_raw_collection_request_proof", None)
    if (
        not isinstance(proof, VerifiedRawCollectionRequestProof)
        or proof.actor_id != actor_id
        or proof.account_generation != account_generation
        or proof.operation is not operation
        or proof.method != request.method.upper()
        or proof.path != request.url.path
        or proof.purpose != purpose
        or proof.walk_id != walk_id
        or proof.manifest_sha256 != manifest_sha256
        or proof.consent_receipt_sha256 != consent_receipt_sha256
        or proof.chunk_sha256 != chunk_sha256
        or proof.commit_sha256 != commit_sha256
    ):
        raise _error(
            status.HTTP_401_UNAUTHORIZED,
            "raw_request_proof_required",
            "A verified request-bound Gateway proof is required.",
        )
    return verified, proof


def _rollback(db: Session) -> None:
    try:
        db.rollback()
    except Exception:
        pass


def _raise_storage_unavailable(db: Session) -> None:
    _rollback(db)
    raise _error(
        status.HTTP_503_SERVICE_UNAVAILABLE,
        "raw_ingest_storage_unavailable",
        "Raw collection persistence is not available in this build.",
    )


def _raise_admission_error(db: Session, exc: Exception) -> None:
    _rollback(db)
    if isinstance(exc, RawCollectionStorageError):
        raise _error(exc.status_code, exc.code, exc.message) from exc
    if isinstance(exc, PrivacyLifecycleError):
        raise _error(exc.status_code, exc.code, exc.message) from exc
    if isinstance(exc, SQLAlchemyError):
        raise _error(
            status.HTTP_503_SERVICE_UNAVAILABLE,
            "raw_ingest_admission_unavailable",
            "Raw collection admission is temporarily unavailable.",
        ) from exc
    raise exc


def _run_guarded_write(
    admission_gate: AdmissionGate,
    db: Session,
    storage_handler: RawCollectionStorageHandler | None,
    operation: Callable[
        [RawCollectionStorageHandler, RawCollectionAdmission],
        Any,
    ],
    *,
    actor_id: str,
    account_generation: int,
    purpose: RawCollectionPurpose,
    consent_receipt_sha256: str,
    settings: Settings,
) -> Any:
    with shared_maintenance_write_lock(
        settings.maintenance_lock_path,
        expected_group_gid=settings.maintenance_lock_group_gid,
    ):
        admission = admission_gate(
            db,
            actor_id=actor_id,
            account_generation=account_generation,
            purpose=purpose,
            consent_receipt_sha256=consent_receipt_sha256,
            settings=settings,
        )
        if storage_handler is None:
            _raise_storage_unavailable(db)
        return operation(storage_handler, admission)


def _run_guarded_preflight(
    admission_gate: AdmissionGate,
    db: Session,
    storage_handler: RawCollectionStorageHandler | None,
    *,
    actor_id: str,
    account_generation: int,
    purpose: RawCollectionPurpose,
    consent_receipt_sha256: str,
    settings: Settings,
) -> None:
    with shared_maintenance_write_lock(
        settings.maintenance_lock_path,
        expected_group_gid=settings.maintenance_lock_group_gid,
    ):
        admission_gate(
            db,
            actor_id=actor_id,
            account_generation=account_generation,
            purpose=purpose,
            consent_receipt_sha256=consent_receipt_sha256,
            settings=settings,
        )
        if storage_handler is None:
            _raise_storage_unavailable(db)
        _rollback(db)


async def _read_raw_chunk(
    request: Request,
    *,
    content_length: int,
    expected_sha256: str,
) -> bytes:
    if request.headers.get("content-type", "").strip().lower() != "application/octet-stream":
        raise _error(
            status.HTTP_415_UNSUPPORTED_MEDIA_TYPE,
            "raw_chunk_content_type_invalid",
            "Raw chunks require application/octet-stream.",
        )
    content = bytearray()
    async for part in request.stream():
        content.extend(part)
        if len(content) > RAW_CHUNK_MAX_BYTES:
            raise _error(
                status.HTTP_413_CONTENT_TOO_LARGE,
                "raw_chunk_too_large",
                "The raw chunk exceeds the fixed byte limit.",
            )
    if len(content) != content_length:
        raise _error(
            status.HTTP_400_BAD_REQUEST,
            "raw_chunk_length_mismatch",
            "Content-Length does not match the raw chunk body.",
        )
    observed_sha256 = hashlib.sha256(content).hexdigest()
    if observed_sha256 != expected_sha256:
        raise _error(
            status.HTTP_400_BAD_REQUEST,
            "raw_chunk_sha256_mismatch",
            "The raw chunk body does not match its SHA-256 header.",
        )
    return bytes(content)


def create_router(
    settings: Settings,
    *,
    admission_gate: AdmissionGate | None = None,
    storage_handler: RawCollectionStorageHandler | None = None,
) -> APIRouter:
    router = APIRouter(prefix="/raw-collections", tags=["raw-collections"])
    gate = admission_gate or authorize_raw_collection_write

    async def run_head_preflight(
        request: Request,
        *,
        db: Session,
        actor_id: str,
        account_generation: int,
        operation: RawCollectionOperation,
        purpose: RawCollectionPurpose,
        walk_id: str,
        manifest_sha256: str,
        consent_receipt_sha256: str,
        chunk_sha256: str | None,
        commit_sha256: str | None,
    ) -> Response:
        _require_enabled(settings)
        _verified_gateway_actor(
            request,
            actor_id=actor_id,
            account_generation=account_generation,
            operation=operation,
            purpose=purpose,
            walk_id=walk_id,
            manifest_sha256=manifest_sha256,
            consent_receipt_sha256=consent_receipt_sha256,
            chunk_sha256=chunk_sha256,
            commit_sha256=commit_sha256,
        )
        try:
            await to_thread.run_sync(
                partial(
                    _run_guarded_preflight,
                    gate,
                    db,
                    storage_handler,
                    actor_id=actor_id,
                    account_generation=account_generation,
                    purpose=purpose,
                    consent_receipt_sha256=consent_receipt_sha256,
                    settings=settings,
                )
            )
        except Exception as exc:
            _raise_admission_error(db, exc)
            raise AssertionError("unreachable")
        return Response(status_code=status.HTTP_204_NO_CONTENT)

    @router.head(
        "/{collection_id}/manifest",
        responses=RAW_ERROR_RESPONSES,
        status_code=status.HTTP_204_NO_CONTENT,
    )
    async def head_manifest(
        request: Request,
        collection_id: str = Path(pattern=RAW_CANONICAL_UUID_PATTERN),
        actor_id: str = Header(alias="X-WalkSafe-Actor-Id"),
        account_generation: int = Header(
            alias="X-WalkSafe-Account-Generation",
            ge=1,
            le=9_223_372_036_854_775_807,
        ),
        purpose: RawCollectionPurpose = Header(alias="X-WalkSafe-Raw-Purpose"),
        walk_id: str = Header(
            alias="X-WalkSafe-Raw-Walk-Id",
            pattern=RAW_CANONICAL_UUID_PATTERN,
        ),
        manifest_sha256: str = Header(
            alias="X-WalkSafe-Raw-Manifest-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        consent_receipt_sha256: str = Header(
            alias="X-WalkSafe-Consent-Receipt-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        db: Session = Depends(get_db),
    ) -> Response:
        return await run_head_preflight(
            request,
            db=db,
            actor_id=actor_id,
            account_generation=account_generation,
            operation=RawCollectionOperation.PUT_MANIFEST,
            purpose=purpose,
            walk_id=walk_id,
            manifest_sha256=manifest_sha256,
            consent_receipt_sha256=consent_receipt_sha256,
            chunk_sha256=None,
            commit_sha256=None,
        )

    @router.head(
        "/{collection_id}/objects/{object_id}/chunks/{index}",
        responses=RAW_ERROR_RESPONSES,
        status_code=status.HTTP_204_NO_CONTENT,
    )
    async def head_chunk(
        request: Request,
        collection_id: str = Path(pattern=RAW_CANONICAL_UUID_PATTERN),
        object_id: str = Path(pattern=RAW_CANONICAL_UUID_PATTERN),
        index: int = Path(ge=0, lt=2_048),
        actor_id: str = Header(alias="X-WalkSafe-Actor-Id"),
        account_generation: int = Header(
            alias="X-WalkSafe-Account-Generation",
            ge=1,
            le=9_223_372_036_854_775_807,
        ),
        purpose: RawCollectionPurpose = Header(alias="X-WalkSafe-Raw-Purpose"),
        walk_id: str = Header(
            alias="X-WalkSafe-Raw-Walk-Id",
            pattern=RAW_CANONICAL_UUID_PATTERN,
        ),
        manifest_sha256: str = Header(
            alias="X-WalkSafe-Raw-Manifest-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        consent_receipt_sha256: str = Header(
            alias="X-WalkSafe-Consent-Receipt-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        chunk_sha256: str = Header(
            alias="X-WalkSafe-Chunk-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        db: Session = Depends(get_db),
    ) -> Response:
        return await run_head_preflight(
            request,
            db=db,
            actor_id=actor_id,
            account_generation=account_generation,
            operation=RawCollectionOperation.PUT_CHUNK,
            purpose=purpose,
            walk_id=walk_id,
            manifest_sha256=manifest_sha256,
            consent_receipt_sha256=consent_receipt_sha256,
            chunk_sha256=chunk_sha256,
            commit_sha256=None,
        )

    @router.head(
        "/{collection_id}/commit",
        responses=RAW_ERROR_RESPONSES,
        status_code=status.HTTP_204_NO_CONTENT,
    )
    async def head_commit(
        request: Request,
        collection_id: str = Path(pattern=RAW_CANONICAL_UUID_PATTERN),
        actor_id: str = Header(alias="X-WalkSafe-Actor-Id"),
        account_generation: int = Header(
            alias="X-WalkSafe-Account-Generation",
            ge=1,
            le=9_223_372_036_854_775_807,
        ),
        purpose: RawCollectionPurpose = Header(alias="X-WalkSafe-Raw-Purpose"),
        walk_id: str = Header(
            alias="X-WalkSafe-Raw-Walk-Id",
            pattern=RAW_CANONICAL_UUID_PATTERN,
        ),
        manifest_sha256: str = Header(
            alias="X-WalkSafe-Raw-Manifest-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        consent_receipt_sha256: str = Header(
            alias="X-WalkSafe-Consent-Receipt-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        commit_sha256: str = Header(
            alias="X-WalkSafe-Raw-Commit-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        db: Session = Depends(get_db),
    ) -> Response:
        return await run_head_preflight(
            request,
            db=db,
            actor_id=actor_id,
            account_generation=account_generation,
            operation=RawCollectionOperation.COMMIT,
            purpose=purpose,
            walk_id=walk_id,
            manifest_sha256=manifest_sha256,
            consent_receipt_sha256=consent_receipt_sha256,
            chunk_sha256=None,
            commit_sha256=commit_sha256,
        )

    @router.put(
        "/{collection_id}/manifest",
        response_model=RawCollectionStatusV1,
        responses={
            200: {"model": RawCollectionStatusV1},
            **RAW_ERROR_RESPONSES,
        },
        status_code=status.HTTP_201_CREATED,
    )
    async def put_manifest(
        request: Request,
        response: Response,
        payload: RawCollectionManifestV1,
        collection_id: str = Path(pattern=RAW_CANONICAL_UUID_PATTERN),
        actor_id: str = Header(alias="X-WalkSafe-Actor-Id"),
        account_generation: int = Header(
            alias="X-WalkSafe-Account-Generation",
            ge=1,
            le=9_223_372_036_854_775_807,
        ),
        purpose: RawCollectionPurpose = Header(alias="X-WalkSafe-Raw-Purpose"),
        walk_id: str = Header(
            alias="X-WalkSafe-Raw-Walk-Id",
            pattern=RAW_CANONICAL_UUID_PATTERN,
        ),
        manifest_sha256: str = Header(
            alias="X-WalkSafe-Raw-Manifest-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        consent_receipt_sha256: str = Header(
            alias="X-WalkSafe-Consent-Receipt-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        db: Session = Depends(get_db),
    ) -> RawCollectionStatusV1:
        _require_enabled(settings)
        _verified_gateway_actor(
            request,
            actor_id=actor_id,
            account_generation=account_generation,
            operation=RawCollectionOperation.PUT_MANIFEST,
            purpose=purpose,
            walk_id=walk_id,
            manifest_sha256=manifest_sha256,
            consent_receipt_sha256=consent_receipt_sha256,
            chunk_sha256=None,
            commit_sha256=None,
        )
        if (
            payload.collection_id != collection_id
            or payload.purpose != purpose
            or payload.walk_id != walk_id
            or payload.manifest_sha256 != manifest_sha256
            or payload.consent_receipt_sha256 != consent_receipt_sha256
        ):
            raise _error(
                409,
                "raw_manifest_binding_mismatch",
                "The manifest does not match its path and proof-binding headers.",
            )
        try:
            result, created = await to_thread.run_sync(
                partial(
                    _run_guarded_write,
                    gate,
                    db,
                    storage_handler,
                    lambda handler, admission: handler.put_manifest(
                        db,
                        admission,
                        payload,
                    ),
                    actor_id=actor_id,
                    account_generation=account_generation,
                    purpose=purpose,
                    consent_receipt_sha256=consent_receipt_sha256,
                    settings=settings,
                )
            )
        except Exception as exc:
            _raise_admission_error(db, exc)
            raise AssertionError("unreachable")
        response.status_code = 201 if created else 200
        return result

    @router.put(
        "/{collection_id}/objects/{object_id}/chunks/{index}",
        response_model=RawCollectionChunkAckV1,
        responses={
            200: {"model": RawCollectionChunkAckV1},
            **RAW_ERROR_RESPONSES,
        },
        status_code=status.HTTP_201_CREATED,
    )
    async def put_chunk(
        request: Request,
        response: Response,
        collection_id: str = Path(pattern=RAW_CANONICAL_UUID_PATTERN),
        object_id: str = Path(pattern=RAW_CANONICAL_UUID_PATTERN),
        index: int = Path(ge=0, lt=2_048),
        actor_id: str = Header(alias="X-WalkSafe-Actor-Id"),
        account_generation: int = Header(
            alias="X-WalkSafe-Account-Generation",
            ge=1,
            le=9_223_372_036_854_775_807,
        ),
        purpose: RawCollectionPurpose = Header(alias="X-WalkSafe-Raw-Purpose"),
        walk_id: str = Header(
            alias="X-WalkSafe-Raw-Walk-Id",
            pattern=RAW_CANONICAL_UUID_PATTERN,
        ),
        manifest_sha256: str = Header(
            alias="X-WalkSafe-Raw-Manifest-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        consent_receipt_sha256: str = Header(
            alias="X-WalkSafe-Consent-Receipt-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        chunk_sha256: str = Header(
            alias="X-WalkSafe-Chunk-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        content_length: int = Header(alias="Content-Length", ge=1, le=RAW_CHUNK_MAX_BYTES),
        db: Session = Depends(get_db),
    ) -> RawCollectionChunkAckV1:
        _require_enabled(settings)
        _verified_gateway_actor(
            request,
            actor_id=actor_id,
            account_generation=account_generation,
            operation=RawCollectionOperation.PUT_CHUNK,
            purpose=purpose,
            walk_id=walk_id,
            manifest_sha256=manifest_sha256,
            consent_receipt_sha256=consent_receipt_sha256,
            chunk_sha256=chunk_sha256,
            commit_sha256=None,
        )
        content = await _read_raw_chunk(
            request,
            content_length=content_length,
            expected_sha256=chunk_sha256,
        )
        try:
            result, created = await to_thread.run_sync(
                partial(
                    _run_guarded_write,
                    gate,
                    db,
                    storage_handler,
                    lambda handler, admission: handler.put_chunk(
                        db,
                        admission,
                        collection_id=collection_id,
                        object_id=object_id,
                        index=index,
                        walk_id=walk_id,
                        manifest_sha256=manifest_sha256,
                        content=content,
                        content_sha256=chunk_sha256,
                    ),
                    actor_id=actor_id,
                    account_generation=account_generation,
                    purpose=purpose,
                    consent_receipt_sha256=consent_receipt_sha256,
                    settings=settings,
                )
            )
        except Exception as exc:
            _raise_admission_error(db, exc)
            raise AssertionError("unreachable")
        response.status_code = 201 if created else 200
        return result

    @router.get(
        "/{collection_id}",
        response_model=RawCollectionStatusV1,
        responses=RAW_ERROR_RESPONSES,
    )
    async def get_status(
        request: Request,
        collection_id: str = Path(pattern=RAW_CANONICAL_UUID_PATTERN),
        actor_id: str = Header(alias="X-WalkSafe-Actor-Id"),
        account_generation: int = Header(
            alias="X-WalkSafe-Account-Generation",
            ge=1,
            le=9_223_372_036_854_775_807,
        ),
        purpose: RawCollectionPurpose = Header(alias="X-WalkSafe-Raw-Purpose"),
        walk_id: str = Header(
            alias="X-WalkSafe-Raw-Walk-Id",
            pattern=RAW_CANONICAL_UUID_PATTERN,
        ),
        manifest_sha256: str = Header(
            alias="X-WalkSafe-Raw-Manifest-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        db: Session = Depends(get_db),
    ) -> RawCollectionStatusV1:
        _verified_gateway_actor(
            request,
            actor_id=actor_id,
            account_generation=account_generation,
            operation=RawCollectionOperation.GET_STATUS,
            purpose=purpose,
            walk_id=walk_id,
            manifest_sha256=manifest_sha256,
            consent_receipt_sha256=None,
            chunk_sha256=None,
            commit_sha256=None,
        )
        if storage_handler is None:
            _raise_storage_unavailable(db)
        try:
            return await to_thread.run_sync(
                partial(
                    storage_handler.get_status,
                    db,
                    actor_id=actor_id,
                    account_generation=account_generation,
                    collection_id=collection_id,
                    purpose=purpose,
                    walk_id=walk_id,
                    manifest_sha256=manifest_sha256,
                )
            )
        except Exception as exc:
            _raise_admission_error(db, exc)
            raise AssertionError("unreachable")

    @router.post(
        "/{collection_id}/commit",
        response_model=RawCollectionReceiptV1 | RawCollectionReceiptV2,
        responses=RAW_ERROR_RESPONSES,
    )
    async def commit(
        request: Request,
        payload: RawCollectionCommitV1,
        collection_id: str = Path(pattern=RAW_CANONICAL_UUID_PATTERN),
        actor_id: str = Header(alias="X-WalkSafe-Actor-Id"),
        account_generation: int = Header(
            alias="X-WalkSafe-Account-Generation",
            ge=1,
            le=9_223_372_036_854_775_807,
        ),
        purpose: RawCollectionPurpose = Header(alias="X-WalkSafe-Raw-Purpose"),
        walk_id: str = Header(
            alias="X-WalkSafe-Raw-Walk-Id",
            pattern=RAW_CANONICAL_UUID_PATTERN,
        ),
        manifest_sha256: str = Header(
            alias="X-WalkSafe-Raw-Manifest-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        consent_receipt_sha256: str = Header(
            alias="X-WalkSafe-Consent-Receipt-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        commit_sha256: str = Header(
            alias="X-WalkSafe-Raw-Commit-SHA256",
            min_length=64,
            max_length=64,
            pattern=r"^[0-9a-f]{64}$",
        ),
        db: Session = Depends(get_db),
    ) -> RawCollectionReceiptV1 | RawCollectionReceiptV2:
        _require_enabled(settings)
        _verified_gateway_actor(
            request,
            actor_id=actor_id,
            account_generation=account_generation,
            operation=RawCollectionOperation.COMMIT,
            purpose=purpose,
            walk_id=walk_id,
            manifest_sha256=manifest_sha256,
            consent_receipt_sha256=consent_receipt_sha256,
            chunk_sha256=None,
            commit_sha256=commit_sha256,
        )
        if (
            payload.collection_id != collection_id
            or payload.manifest_sha256 != manifest_sha256
            or raw_collection_commit_sha256(payload) != commit_sha256
        ):
            raise _error(
                409,
                "raw_commit_binding_mismatch",
                "The commit does not match its path and proof-binding headers.",
            )
        try:
            return await to_thread.run_sync(
                partial(
                    _run_guarded_write,
                    gate,
                    db,
                    storage_handler,
                    lambda handler, admission: handler.commit(
                        db,
                        admission,
                        payload,
                        walk_id=walk_id,
                    ),
                    actor_id=actor_id,
                    account_generation=account_generation,
                    purpose=purpose,
                    consent_receipt_sha256=consent_receipt_sha256,
                    settings=settings,
                )
            )
        except Exception as exc:
            _raise_admission_error(db, exc)
            raise AssertionError("unreachable")

    return router


__all__ = [
    "RawCollectionStorageHandler",
    "create_router",
]
