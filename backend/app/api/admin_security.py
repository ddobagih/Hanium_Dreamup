"""Strict Android administrator authentication and recovery HTTP contract."""

from __future__ import annotations

from collections.abc import Callable
from typing import Annotated, Any, Literal, NoReturn
import uuid

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    model_validator,
)
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.database import SessionLocal, get_db
from backend.app.services.admin_device_proof import (
    AdminDeviceProofService,
    VerifiedAdminDeviceProof,
)
from backend.app.services.admin_security import (
    ADMIN_APP_KIND,
    ADMIN_AUDIENCE,
    ADMIN_ROLE,
    AdminSecurityError,
    AdminSecurityService,
    AdminSessionIdentity,
    authorize_admin_bearer,
    load_admin_credential_issuer_key_for_settings,
    valid_recovery_custody_reference,
)


AdminId = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=64,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$",
    ),
]
DeviceId = Annotated[
    str,
    StringConstraints(
        min_length=8,
        max_length=128,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$",
    ),
]
DeviceLabel = Annotated[str, StringConstraints(min_length=1, max_length=128, strip_whitespace=True)]
Password = Annotated[str, StringConstraints(min_length=12, max_length=256)]
NewPassword = Annotated[str, StringConstraints(min_length=12, max_length=256)]
TotpCode = Annotated[str, StringConstraints(pattern=r"^[0-9]{6}$")]
RecoveryCode = Annotated[str, StringConstraints(min_length=24, max_length=256, strip_whitespace=True)]
CustodyReference = Annotated[
    str,
    StringConstraints(
        min_length=43,
        max_length=43,
        pattern=r"^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$",
    ),
    Field(
        description=(
            "Canonical unpadded Base64url encoding of an opaque 32-byte "
            "recovery custody reference."
        )
    ),
]
OpaqueRecoveryToken = Annotated[str, StringConstraints(min_length=32, max_length=512)]
AdminAction = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=64,
        pattern=r"^[a-z][a-z0-9_.:-]{0,63}$",
    ),
]
AdminMethod = Literal["GET", "POST", "PUT", "PATCH", "DELETE"]
AdminPath = Annotated[str, StringConstraints(min_length=1, max_length=512, pattern=r"^/[^?#%]*$")]
ReconfirmationNonce = Annotated[
    str,
    StringConstraints(min_length=22, max_length=128, pattern=r"^[A-Za-z0-9_-]+$"),
]
SecurityStateWire = Literal["NORMAL", "RECOVERY_REQUIRED", "RECOVERY_IN_PROGRESS"]
DeviceProofPurpose = Literal["LOGIN", "ACTION", "RECOVERY_COMPLETE"]
LowerSha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
CanonicalUuid = Annotated[
    str,
    StringConstraints(
        pattern=(
            r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            r"[0-9a-f]{4}-[0-9a-f]{12}$"
        )
    ),
]
DeviceKeyMarker = LowerSha256
ReadPurpose = Annotated[
    str,
    StringConstraints(min_length=3, max_length=64, pattern=r"^[a-z][a-z0-9_.:-]{2,63}$"),
]


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class LoginRequest(_StrictModel):
    admin_id: AdminId
    password: Password
    totp_code: TotpCode
    device_id: DeviceId
    device_label: DeviceLabel


class LoginResponse(_StrictModel):
    access_token: str
    security_state: SecurityStateWire
    current_session_id: CanonicalUuid


class StateResponse(_StrictModel):
    security_state: SecurityStateWire
    state_version: str
    observed_at: str
    recovery_custody_state: Literal["UNATTESTED", "ATTESTED"]
    recovery_custody_attested_at: AwareDatetime | None

    @model_validator(mode="after")
    def validate_custody_timestamp(self) -> "StateResponse":
        if (self.recovery_custody_state == "ATTESTED") != (
            self.recovery_custody_attested_at is not None
        ):
            raise ValueError("recovery custody state and timestamp must agree")
        return self


class SessionItem(_StrictModel):
    session_id: str
    device_id: str
    device_label: str
    current: bool
    revoked: bool
    last_seen_at: str


class DeviceItem(_StrictModel):
    device_id: DeviceId
    current: bool


class SessionsResponse(_StrictModel):
    sessions: list[SessionItem] = Field(max_length=100)
    devices: list[DeviceItem] = Field(max_length=100)

    @model_validator(mode="after")
    def validate_current_device(self) -> "SessionsResponse":
        device_ids = [item.device_id for item in self.devices]
        if len(device_ids) != len(set(device_ids)):
            raise ValueError("administrator device identifiers must be unique")
        current_sessions = [
            item for item in self.sessions if item.current and not item.revoked
        ]
        current_devices = [item for item in self.devices if item.current]
        if (
            len(current_sessions) != 1
            or len(current_devices) != 1
            or current_sessions[0].device_id != current_devices[0].device_id
        ):
            raise ValueError("current administrator session and device must agree")
        return self


class AdminRecoveryExpiredErrorDetail(_StrictModel):
    code: Literal["admin_recovery_expired"]
    message: str


class AdminRecoveryExpiredErrorResponse(_StrictModel):
    detail: AdminRecoveryExpiredErrorDetail


class ReauthenticateRequest(_StrictModel):
    password: Password
    totp_code: TotpCode
    action: AdminAction
    method: AdminMethod
    path: AdminPath
    nonce: ReconfirmationNonce


class ReauthenticateResponse(_StrictModel):
    reauthenticated_until_epoch_ms: int = Field(gt=0)
    action: AdminAction
    method: AdminMethod
    path: AdminPath


class RecoveryStartRequest(_StrictModel):
    admin_id: AdminId
    recovery_code: RecoveryCode
    device_id: DeviceId
    device_label: DeviceLabel


class RecoveryStartResponse(_StrictModel):
    recovery_token: str
    security_state: Literal["RECOVERY_IN_PROGRESS"]


class RecoveryCompleteRequest(_StrictModel):
    recovery_token: OpaqueRecoveryToken
    new_password: NewPassword
    totp_code: TotpCode
    device_id: DeviceId
    device_label: DeviceLabel


class RecoveryCustodyAttestRequest(_StrictModel):
    custody_reference: CustodyReference
    material_kind: Literal["RECOVERY_CODE", "SECURITY_KEY"]
    storage_location: Literal["OFF_PHONE"]
    separate_encrypted_backup_confirmed: Literal[True]

    @model_validator(mode="after")
    def validate_canonical_custody_reference(
        self,
    ) -> "RecoveryCustodyAttestRequest":
        if not valid_recovery_custody_reference(self.custody_reference):
            raise ValueError(
                "custody_reference must be canonical unpadded Base64url for 32 bytes"
            )
        return self


class EmptyRequest(_StrictModel):
    pass


class DeviceProofChallengeRequest(_StrictModel):
    """All exact13 fields are required, including explicit nullable fields."""

    action: AdminAction | None
    admin_id: AdminId
    body_sha256: LowerSha256
    correlation_id: CanonicalUuid
    device_id: DeviceId
    device_key_marker: DeviceKeyMarker
    device_key_version: int = Field(ge=1, strict=True)
    method: AdminMethod
    path: AdminPath
    purpose: DeviceProofPurpose
    query_sha256: LowerSha256
    read_purpose: ReadPurpose | None
    session_id: CanonicalUuid | None


class DeviceProofChallengeResponse(_StrictModel):
    """The exact18 signed fields plus the plain UTF-8 signing payload."""

    action: AdminAction | None
    admin_id: AdminId
    body_sha256: LowerSha256
    challenge_id: CanonicalUuid
    correlation_id: CanonicalUuid
    device_id: DeviceId
    device_key_marker: DeviceKeyMarker
    device_key_version: int = Field(ge=1, strict=True)
    expires_at_epoch_ms: int = Field(gt=0)
    issued_at_epoch_ms: int = Field(gt=0)
    method: AdminMethod
    nonce: Annotated[
        str,
        StringConstraints(min_length=43, max_length=43, pattern=r"^[A-Za-z0-9_-]{43}$"),
    ]
    path: AdminPath
    purpose: DeviceProofPurpose
    query_sha256: LowerSha256
    read_purpose: ReadPurpose | None
    schema_version: Literal["walksafe.admin-device-proof.v2"]
    session_id: CanonicalUuid | None
    signing_payload: str = Field(min_length=1)


def _raise_http(exc: AdminSecurityError) -> NoReturn:
    headers = (
        {"Retry-After": str(exc.retry_after)}
        if exc.retry_after is not None
        else None
    )
    raise HTTPException(
        status_code=exc.status_code,
        headers=headers,
        detail={"code": exc.code, "message": exc.message},
    ) from exc


def _require_enabled(settings: Any) -> None:
    if not settings.admin_security_enabled:
        raise HTTPException(
            status_code=503,
            detail={
                "code": "admin_security_disabled",
                "message": "Database-backed administrator security is not enabled.",
            },
        )


def _require_android_contract(request: Request, device_id: str) -> None:
    expected = {
        "x-walksafe-app-kind": ADMIN_APP_KIND,
        "x-walksafe-role": ADMIN_ROLE,
        "x-walksafe-audience": ADMIN_AUDIENCE,
        "x-walksafe-device-id": device_id,
    }
    if any(request.headers.get(name, "") != value for name, value in expected.items()):
        raise HTTPException(
            status_code=403,
            detail={
                "code": "admin_client_contract_invalid",
                "message": "The administrator client headers do not match this device and API.",
            },
        )


def _bearer_token(request: Request) -> str:
    authorization = request.headers.get("authorization", "")
    scheme, separator, token = authorization.partition(" ")
    if (
        not separator
        or scheme.lower() != "bearer"
        or not token
        or any(character.isspace() for character in token)
    ):
        return ""
    return token


def _authorize_action_challenge(settings: Any, request: Request) -> AdminSessionIdentity:
    try:
        with SessionLocal.begin() as db:
            return authorize_admin_bearer(
                db,
                _bearer_token(request),
                runtime_totp_secret=settings.admin_totp_secret,
                credential_issuer_key=(
                    load_admin_credential_issuer_key_for_settings(settings)
                ),
                device_id=request.headers.get("x-walksafe-device-id"),
                app_kind=request.headers.get("x-walksafe-app-kind"),
                role=request.headers.get("x-walksafe-role"),
                audience=request.headers.get("x-walksafe-audience"),
            )
    except AdminSecurityError:
        raise
    except SQLAlchemyError as exc:
        from backend.app.services.admin_security import AdminSecurityStoreUnavailable

        raise AdminSecurityStoreUnavailable() from exc


def _require_public_auth_device_proof(
    request: Request,
    settings: Any,
    *,
    purpose: Literal["LOGIN", "RECOVERY_COMPLETE"],
    admin_id: str,
    device_id: str,
    path: str,
) -> None:
    if not bool(getattr(settings, "admin_device_proof_enabled", False)):
        return
    proof = getattr(request.state, "admin_device_proof", None)
    if not isinstance(proof, VerifiedAdminDeviceProof) or (
        proof.purpose != purpose
        or proof.action is not None
        or proof.admin_id != admin_id
        or proof.device_id != device_id
        or proof.session_id is not None
        or proof.read_purpose is not None
        or proof.method != "POST"
        or proof.path != path
    ):
        raise HTTPException(
            status_code=401,
            detail={
                "code": "admin_device_proof_required",
                "message": "A valid registered administrator device proof is required.",
            },
        )


def _request_identity(request: Request) -> AdminSessionIdentity:
    identity = getattr(request.state, "admin_security_identity", None)
    if not isinstance(identity, AdminSessionIdentity):
        raise HTTPException(
            status_code=401,
            detail={
                "code": "admin_session_required",
                "message": "A valid administrator session is required.",
            },
        )
    return identity


def _source(request: Request) -> str:
    return request.client.host if request.client is not None else "unknown"


def _disable_sensitive_response_caching(response: Response) -> None:
    response.headers["Cache-Control"] = "no-store"
    response.headers["Pragma"] = "no-cache"


def create_router(
    settings: Any,
    service_factory: Callable[[], Any] | None = None,
    device_proof_service_factory: Callable[[], Any] | None = None,
    action_challenge_authorizer: Callable[[Request], AdminSessionIdentity] | None = None,
) -> APIRouter:
    router = APIRouter(prefix="/admin/security", tags=["admin-security"])

    if service_factory is None:
        def get_service(db: Session = Depends(get_db)) -> AdminSecurityService:
            return AdminSecurityService(db, settings)
    else:
        def get_service() -> Any:
            return service_factory()

    if device_proof_service_factory is None:
        def get_device_proof_service(
            db: Session = Depends(get_db),
        ) -> AdminDeviceProofService:
            return AdminDeviceProofService(db, settings)
    else:
        def get_device_proof_service() -> Any:
            return device_proof_service_factory()

    authorize_action_challenge = action_challenge_authorizer or (
        lambda request: _authorize_action_challenge(settings, request)
    )

    @router.post(
        "/device-proof/challenges",
        response_model=DeviceProofChallengeResponse,
    )
    def create_device_proof_challenge(
        payload: DeviceProofChallengeRequest,
        request: Request,
        response: Response,
        service: Any = Depends(get_device_proof_service),
    ) -> DeviceProofChallengeResponse:
        _disable_sensitive_response_caching(response)
        _require_enabled(settings)
        if not bool(getattr(settings, "admin_device_proof_enabled", False)):
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "admin_device_proof_disabled",
                    "message": "Administrator device proof is not enabled.",
                },
            )
        _require_android_contract(request, payload.device_id)
        if request.headers.get("x-walksafe-correlation-id", "") != payload.correlation_id:
            raise HTTPException(
                status_code=422,
                detail={
                    "code": "admin_device_proof_correlation_invalid",
                    "message": "The correlation header must match the challenge request.",
                },
            )
        identity: AdminSessionIdentity | None = None
        if payload.purpose == "ACTION":
            try:
                identity = authorize_action_challenge(request)
            except AdminSecurityError as exc:
                _raise_http(exc)
        elif payload.admin_id != settings.admin_id:
            raise HTTPException(
                status_code=403,
                detail={
                    "code": "admin_device_key_not_registered",
                    "message": "The administrator device key is not registered and active.",
                },
            )
        try:
            result = service.issue_challenge(
                **payload.model_dump(),
                identity=identity,
            )
        except AdminSecurityError as exc:
            _raise_http(exc)
        return DeviceProofChallengeResponse(**result)

    @router.post("/sessions", response_model=LoginResponse)
    def login(
        payload: LoginRequest,
        request: Request,
        response: Response,
        service: Any = Depends(get_service),
    ) -> LoginResponse:
        _disable_sensitive_response_caching(response)
        _require_enabled(settings)
        _require_android_contract(request, payload.device_id)
        _require_public_auth_device_proof(
            request,
            settings,
            purpose="LOGIN",
            admin_id=payload.admin_id,
            device_id=payload.device_id,
            path="/admin/security/sessions",
        )
        try:
            grant = service.login(
                admin_id=payload.admin_id,
                password=payload.password,
                totp_code=payload.totp_code,
                device_id=payload.device_id,
                device_label=payload.device_label,
                source=_source(request),
            )
        except AdminSecurityError as exc:
            _raise_http(exc)
        return LoginResponse(
            access_token=grant.access_token,
            security_state=grant.security_state,
            current_session_id=grant.current_session_id,
        )

    @router.get("/state", response_model=StateResponse)
    def get_state(
        request: Request,
        response: Response,
        service: Any = Depends(get_service),
    ) -> StateResponse:
        _disable_sensitive_response_caching(response)
        _require_enabled(settings)
        try:
            result = service.get_state(_request_identity(request))
        except AdminSecurityError as exc:
            _raise_http(exc)
        return StateResponse(**result)

    @router.get("/sessions", response_model=SessionsResponse)
    def get_sessions(
        request: Request,
        response: Response,
        service: Any = Depends(get_service),
    ) -> SessionsResponse:
        _disable_sensitive_response_caching(response)
        _require_enabled(settings)
        try:
            identity = _request_identity(request)
            sessions = service.list_sessions(identity)
            devices = service.list_active_devices(identity)
        except AdminSecurityError as exc:
            _raise_http(exc)
        return SessionsResponse(
            sessions=[SessionItem(**item) for item in sessions],
            devices=[DeviceItem(**item) for item in devices],
        )

    @router.post("/sessions/{session_id}/revoke", response_model=StateResponse)
    def revoke_session(
        session_id: uuid.UUID,
        request: Request,
        response: Response,
        service: Any = Depends(get_service),
    ) -> StateResponse:
        _disable_sensitive_response_caching(response)
        _require_enabled(settings)
        try:
            result = service.revoke_session(_request_identity(request), session_id)
        except AdminSecurityError as exc:
            _raise_http(exc)
        return StateResponse(**result)

    @router.post("/recovery-custody/attest", response_model=StateResponse)
    def attest_recovery_custody(
        payload: RecoveryCustodyAttestRequest,
        request: Request,
        response: Response,
        service: Any = Depends(get_service),
    ) -> StateResponse:
        _disable_sensitive_response_caching(response)
        _require_enabled(settings)
        try:
            result = service.attest_recovery_custody(
                _request_identity(request),
                **payload.model_dump(),
            )
        except AdminSecurityError as exc:
            _raise_http(exc)
        return StateResponse(**result)

    @router.post(
        "/devices/{device_id}/report-lost",
        response_model=StateResponse,
    )
    def report_lost_device(
        device_id: DeviceId,
        payload: EmptyRequest,
        request: Request,
        response: Response,
        service: Any = Depends(get_service),
    ) -> StateResponse:
        del payload
        _disable_sensitive_response_caching(response)
        _require_enabled(settings)
        try:
            result = service.report_lost_device(
                _request_identity(request),
                device_id,
            )
        except AdminSecurityError as exc:
            _raise_http(exc)
        return StateResponse(**result)

    @router.post("/reauthenticate", response_model=ReauthenticateResponse)
    def reauthenticate(
        payload: ReauthenticateRequest,
        request: Request,
        response: Response,
        service: Any = Depends(get_service),
    ) -> ReauthenticateResponse:
        _disable_sensitive_response_caching(response)
        _require_enabled(settings)
        try:
            grant = service.reauthenticate(
                _request_identity(request),
                password=payload.password,
                totp_code=payload.totp_code,
                action=payload.action,
                method=payload.method,
                path=payload.path,
                nonce=payload.nonce,
                source=_source(request),
            )
        except AdminSecurityError as exc:
            _raise_http(exc)
        return ReauthenticateResponse(
            reauthenticated_until_epoch_ms=grant.reauthenticated_until_epoch_ms,
            action=grant.action,
            method=grant.method,
            path=grant.path,
        )

    @router.post("/recovery/start", response_model=RecoveryStartResponse)
    def start_recovery(
        payload: RecoveryStartRequest,
        request: Request,
        response: Response,
        service: Any = Depends(get_service),
    ) -> RecoveryStartResponse:
        _disable_sensitive_response_caching(response)
        _require_enabled(settings)
        _require_android_contract(request, payload.device_id)
        try:
            grant = service.start_recovery(
                admin_id=payload.admin_id,
                recovery_code=payload.recovery_code,
                device_id=payload.device_id,
                device_label=payload.device_label,
                source=_source(request),
            )
        except AdminSecurityError as exc:
            _raise_http(exc)
        return RecoveryStartResponse(
            recovery_token=grant.recovery_token,
            security_state=grant.security_state,
        )

    @router.post(
        "/recovery/complete",
        response_model=LoginResponse,
        responses={
            410: {
                "model": AdminRecoveryExpiredErrorResponse,
                "description": "The administrator recovery transaction expired.",
            }
        },
    )
    def complete_recovery(
        payload: RecoveryCompleteRequest,
        request: Request,
        response: Response,
        service: Any = Depends(get_service),
    ) -> LoginResponse:
        _disable_sensitive_response_caching(response)
        _require_enabled(settings)
        _require_android_contract(request, payload.device_id)
        _require_public_auth_device_proof(
            request,
            settings,
            purpose="RECOVERY_COMPLETE",
            admin_id=getattr(settings, "admin_id", ""),
            device_id=payload.device_id,
            path="/admin/security/recovery/complete",
        )
        try:
            grant = service.complete_recovery(
                recovery_token=payload.recovery_token,
                new_password=payload.new_password,
                totp_code=payload.totp_code,
                device_id=payload.device_id,
                device_label=payload.device_label,
                source=_source(request),
            )
        except AdminSecurityError as exc:
            _raise_http(exc)
        return LoginResponse(
            access_token=grant.access_token,
            security_state=grant.security_state,
            current_session_id=grant.current_session_id,
        )

    return router


__all__ = [
    "DeviceProofChallengeRequest",
    "DeviceProofChallengeResponse",
    "create_router",
]
