"""HTTP proof dependencies shared by administrator report and incident routes."""
from __future__ import annotations
from fastapi import HTTPException, Request
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.admin_device_proof import VerifiedAdminDeviceProof

def require_admin_read_context(
    request: Request,
    *,
    expected_operation: str,
) -> tuple[AdminSessionIdentity, VerifiedAdminDeviceProof]:
    identity = getattr(request.state, "admin_security_identity", None)
    if not isinstance(identity, AdminSessionIdentity):
        raise HTTPException(
            status_code=401,
            detail={
                "code": "admin_session_required",
                "message": "An authenticated administrator session is required.",
            },
        )
    proof = getattr(request.state, "admin_device_proof", None)
    if not isinstance(proof, VerifiedAdminDeviceProof):
        raise HTTPException(
            status_code=403,
            detail={
                "code": "admin_device_proof_required",
                "message": "A verified administrator device proof is required.",
            },
        )
    if (
        proof.admin_id != identity.admin_id
        or proof.session_id != identity.session_id
        or proof.device_id != identity.device_id
        or proof.correlation_id is None
        or proof.action is not None
        or proof.purpose != "ACTION"
        or proof.read_purpose != expected_operation
        or proof.method != request.method.upper()
        or proof.path != request.url.path
    ):
        raise HTTPException(
            status_code=403,
            detail={
                "code": "admin_device_proof_invalid",
                "message": "The administrator device proof does not match this request.",
            },
        )
    return identity, proof


def require_admin_action_context(
    request: Request,
    *,
    expected_action: str,
) -> tuple[AdminSessionIdentity, VerifiedAdminDeviceProof]:
    identity = getattr(request.state, "admin_security_identity", None)
    if not isinstance(identity, AdminSessionIdentity):
        raise HTTPException(status_code=401, detail={"code": "admin_session_required", "message": "An authenticated administrator session is required."})
    proof = getattr(request.state, "admin_device_proof", None)
    if not isinstance(proof, VerifiedAdminDeviceProof):
        raise HTTPException(status_code=403, detail={"code": "admin_device_proof_required", "message": "A verified administrator device proof is required."})
    if identity.step_up_verified_at is None:
        raise HTTPException(status_code=403, detail={"code": "admin_step_up_required", "message": "Recent administrator reauthentication is required."})
    if (
        proof.admin_id != identity.admin_id
        or proof.session_id != identity.session_id
        or proof.device_id != identity.device_id
        or proof.correlation_id is None
        or proof.action != expected_action
        or proof.read_purpose is not None
        or proof.purpose != "ACTION"
        or proof.method != request.method.upper()
        or proof.path != request.url.path
    ):
        raise HTTPException(status_code=403, detail={"code": "admin_device_proof_invalid", "message": "The administrator device proof does not match this request."})
    return identity, proof
