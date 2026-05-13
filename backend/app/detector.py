from __future__ import annotations

from backend.app.config import get_settings
from backend.app.schemas import DetectContext, DetectHealthResponse, DetectResponse


MODEL_NOT_CONFIGURED_REASON = "model_not_configured"
MODEL_ADAPTER_NOT_IMPLEMENTED_REASON = "model_adapter_not_implemented"


def _health_response(reason: str) -> DetectHealthResponse:
    settings = get_settings()
    model_artifact_path = str(settings.model_artifact_path) if settings.model_artifact_path else None

    return DetectHealthResponse(
        model_status="unavailable",
        model_version=settings.model_version,
        reason=reason,
        model_artifact_path=model_artifact_path,
        model_class_order=list(settings.model_class_order),
        model_confidence_threshold=settings.model_confidence_threshold,
        model_iou_threshold=settings.model_iou_threshold,
        model_image_size=settings.model_image_size,
    )


def detect_health() -> DetectHealthResponse:
    settings = get_settings()
    if settings.model_artifact_path and settings.model_artifact_path.exists():
        return _health_response(MODEL_ADAPTER_NOT_IMPLEMENTED_REASON)

    return _health_response(MODEL_NOT_CONFIGURED_REASON)


async def run_detection(image_bytes: bytes, content_type: str, context: DetectContext) -> DetectResponse:
    health = detect_health()
    raise RuntimeError(health.reason or MODEL_NOT_CONFIGURED_REASON)
