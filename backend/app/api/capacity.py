"""Authenticated internal delivery of the current coarse capacity state."""

from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Response
from pydantic import BaseModel, ConfigDict, Field
from starlette.responses import JSONResponse

from backend.app.services.capacity_state import (
    MAX_JS_SAFE_INTEGER,
    CapacityLevel,
    CapacityState,
    CapacityStateUnavailable,
)


_UTC_RFC3339_PATTERN = (
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
    r"(?:\.[0-9]{1,6})?Z$"
)


class CapacityStateResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    version: int = Field(gt=0, le=MAX_JS_SAFE_INTEGER)
    observed_at: str = Field(pattern=_UTC_RFC3339_PATTERN)
    expires_at: str = Field(pattern=_UTC_RFC3339_PATTERN)
    level: CapacityLevel
    reason: Literal["STORAGE_UTILIZATION"]


def create_router(capacity_state: CapacityState) -> APIRouter:
    router = APIRouter(tags=["capacity"])

    @router.get(
        "/internal/capacity",
        response_model=CapacityStateResponse,
        responses={503: {"description": "Capacity state unavailable or expired."}},
    )
    def get_capacity(response: Response) -> CapacityStateResponse | JSONResponse:
        try:
            snapshot = capacity_state.current()
        except CapacityStateUnavailable:
            return JSONResponse(
                status_code=503,
                headers={"Cache-Control": "no-store"},
                content={"detail": {"code": "capacity_state_unavailable"}},
            )
        response.headers["Cache-Control"] = "no-store"
        return CapacityStateResponse.model_validate(snapshot.to_wire())

    return router


__all__ = ["CapacityStateResponse", "create_router"]
