from __future__ import annotations

from pathlib import Path

from fastapi import APIRouter, HTTPException
from fastapi.responses import Response

from backend.app.config import Settings
from backend.app.uploads import IMAGE_EXTENSIONS


UPLOAD_MEDIA_TYPES = {
    extension: content_type
    for content_type, extensions in IMAGE_EXTENSIONS.items()
    for extension in extensions
}


def create_router(settings: Settings) -> APIRouter:
    router = APIRouter()

    @router.get("/uploads/{filename}")
    async def get_upload(filename: str) -> Response:
        if Path(filename).name != filename:
            raise HTTPException(status_code=404, detail="upload not found")

        path = settings.upload_dir / filename
        if not path.is_file():
            raise HTTPException(status_code=404, detail="upload not found")

        media_type = UPLOAD_MEDIA_TYPES.get(path.suffix.lower(), "application/octet-stream")
        return Response(content=path.read_bytes(), media_type=media_type, headers={"Cache-Control": "no-store"})

    return router
