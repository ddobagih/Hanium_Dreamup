from __future__ import annotations

from fastapi import UploadFile

from backend.app.config import get_settings
from backend.app.schemas import DetectContext, DetectHealthResponse, DetectResponse


MODEL_UNAVAILABLE_REASON = "model_not_configured"


def detect_health() -> DetectHealthResponse:
    settings = get_settings()
    if settings.model_artifact_path and settings.model_artifact_path.exists():
        return DetectHealthResponse(
            model_status="unavailable",
            reason="model_adapter_not_implemented",
        )

    return DetectHealthResponse(
        model_status="unavailable",
        reason=MODEL_UNAVAILABLE_REASON,
    )


async def run_detection(image: UploadFile, context: DetectContext) -> DetectResponse:
    health = detect_health()
    raise RuntimeError(health.reason or MODEL_UNAVAILABLE_REASON)
