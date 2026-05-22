from __future__ import annotations

import json

from fastapi import APIRouter, File, Form, HTTPException, UploadFile
from pydantic import ValidationError

from backend.app.config import Settings
from backend.app.detector import detect_health, run_detection
from backend.app.schemas import DetectContext, DetectHealthResponse, DetectResponse, DetectV2Response
from backend.app.services.detect_v2 import fake_detect_v2_detections
from backend.app.uploads import read_image_upload


def create_router(settings: Settings) -> APIRouter:
    router = APIRouter()

    @router.get("/detect/health", response_model=DetectHealthResponse)
    async def get_detect_health() -> DetectHealthResponse:
        return detect_health()

    @router.post("/detect", response_model=DetectResponse)
    async def detect_objects(
        context: str = Form(default="{}"),
        image: UploadFile = File(...),
    ) -> DetectResponse:
        try:
            parsed_context = DetectContext.model_validate_json(context)
        except (ValidationError, json.JSONDecodeError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

        image_bytes, content_type = await read_image_upload(image, settings)

        try:
            return await run_detection(image_bytes=image_bytes, content_type=content_type, context=parsed_context)
        except RuntimeError as exc:
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "model_unavailable",
                    "reason": str(exc),
                    "message": "Server inference is not available until a model adapter is implemented.",
                },
            ) from exc

    @router.post("/detect/v2", response_model=DetectV2Response)
    async def detect_objects_v2(
        context: str = Form(default="{}"),
        image: UploadFile = File(...),
    ) -> DetectV2Response:
        try:
            parsed_context = DetectContext.model_validate_json(context)
        except (ValidationError, json.JSONDecodeError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

        await read_image_upload(image, settings)
        return fake_detect_v2_detections(parsed_context)

    return router
