from __future__ import annotations

import json
import uuid
from datetime import datetime
from typing import List, Optional

from fastapi import APIRouter, Depends, File, Form, HTTPException, Query, UploadFile, status
from geoalchemy2.elements import WKTElement
from pydantic import ValidationError
from sqlalchemy import select, text
from sqlalchemy.orm import Session

from backend.app.config import Settings
from backend.app.database import get_db
from backend.app.models import Report
from backend.app.schemas import (
    ClassName,
    DetectorSource,
    DuplicateCheckResponse,
    ReportMetadata,
    ReportResponse,
    ReportStatus,
    ReportStatusUpdate,
    ReportV2Metadata,
)
from backend.app.services.duplicates import (
    DUPLICATE_RADIUS_M,
    DUPLICATE_WINDOW_MINUTES,
    duplicate_candidate_statement,
    find_duplicate_candidates,
    find_duplicate_candidates_v2,
)
from backend.app.services.report_policy import ensure_report_v2_allowed, report_v2_source
from backend.app.services.report_serialization import report_to_response
from backend.app.uploads import image_suffix, read_image_upload, write_image_file


def create_router(settings: Settings) -> APIRouter:
    router = APIRouter()

    @router.post("/reports", response_model=ReportResponse, status_code=status.HTTP_201_CREATED)
    async def create_report(
        metadata: str = Form(...),
        image: UploadFile = File(...),
        db: Session = Depends(get_db),
    ) -> ReportResponse:
        try:
            parsed = ReportMetadata.model_validate_json(metadata)
        except (ValidationError, json.JSONDecodeError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

        content, content_type = await read_image_upload(image, settings)

        report_id = uuid.uuid4()
        filename = f"{report_id}{image_suffix(content_type)}"
        destination = settings.upload_dir / filename
        write_image_file(destination, content)

        gps = parsed.gps
        latitude = gps.latitude if gps else None
        longitude = gps.longitude if gps else None
        location = WKTElement(f"POINT({longitude} {latitude})", srid=4326) if gps else None
        duplicate_candidates = find_duplicate_candidates(db, parsed)

        report = Report(
            id=report_id,
            class_id=parsed.class_id,
            class_name=parsed.class_name,
            confidence=parsed.confidence,
            bbox_x=parsed.bbox.x,
            bbox_y=parsed.bbox.y,
            bbox_width=parsed.bbox.width,
            bbox_height=parsed.bbox.height,
            captured_at=parsed.captured_at,
            source=parsed.source,
            latitude=latitude,
            longitude=longitude,
            accuracy_m=gps.accuracy_m if gps else None,
            heading=parsed.heading,
            location=location,
            image_path=f"/uploads/{filename}",
            image_content_type=content_type,
            payload=parsed.model_dump(mode="json"),
        )

        db.add(report)
        db.commit()
        db.refresh(report)
        return report_to_response(report, [str(candidate.id) for candidate in duplicate_candidates])

    @router.post("/reports/v2", response_model=ReportResponse, status_code=status.HTTP_201_CREATED)
    async def create_report_v2(
        metadata: str = Form(...),
        image: UploadFile = File(...),
        db: Session = Depends(get_db),
    ) -> ReportResponse:
        try:
            raw_payload = json.loads(metadata)
            parsed = ReportV2Metadata.model_validate(raw_payload)
        except (ValidationError, json.JSONDecodeError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc

        ensure_report_v2_allowed(parsed)
        content, content_type = await read_image_upload(image, settings)

        report_id = uuid.uuid4()
        filename = f"{report_id}{image_suffix(content_type)}"
        destination = settings.upload_dir / filename
        write_image_file(destination, content)

        gps = parsed.gps
        latitude = gps.latitude if gps else None
        longitude = gps.longitude if gps else None
        location = WKTElement(f"POINT({longitude} {latitude})", srid=4326) if gps else None
        duplicate_candidates = find_duplicate_candidates_v2(db, parsed)

        report = Report(
            id=report_id,
            class_id=parsed.model_class_id,
            class_name=parsed.class_name,
            confidence=parsed.confidence,
            bbox_x=parsed.bbox.x,
            bbox_y=parsed.bbox.y,
            bbox_width=parsed.bbox.width,
            bbox_height=parsed.bbox.height,
            captured_at=parsed.captured_at,
            source=report_v2_source(parsed),
            latitude=latitude,
            longitude=longitude,
            accuracy_m=gps.accuracy_m if gps else None,
            heading=parsed.heading,
            location=location,
            image_path=f"/uploads/{filename}",
            image_content_type=content_type,
            payload=raw_payload,
        )

        db.add(report)
        db.commit()
        db.refresh(report)
        return report_to_response(report, [str(candidate.id) for candidate in duplicate_candidates])

    @router.get("/reports", response_model=List[ReportResponse])
    async def list_reports(
        db: Session = Depends(get_db),
        limit: int = Query(default=25, ge=1, le=100),
        status: Optional[ReportStatus] = Query(default=None),
        class_name: Optional[ClassName] = Query(default=None),
        source: Optional[DetectorSource] = Query(default=None),
        created_from: Optional[datetime] = Query(default=None),
        created_to: Optional[datetime] = Query(default=None),
        lat: Optional[float] = Query(default=None, ge=-90, le=90),
        lng: Optional[float] = Query(default=None, ge=-180, le=180),
        radius_m: Optional[float] = Query(default=None, gt=0),
    ) -> list[ReportResponse]:
        has_radius_query = lat is not None or lng is not None or radius_m is not None
        if has_radius_query and (lat is None or lng is None or radius_m is None):
            raise HTTPException(status_code=400, detail="lat, lng, and radius_m must be provided together")

        statement = select(Report)
        if status is not None:
            statement = statement.where(Report.status == status)
        if class_name is not None:
            statement = statement.where(Report.class_name == class_name)
        if source is not None:
            statement = statement.where(Report.source == source)
        if created_from is not None:
            statement = statement.where(Report.created_at >= created_from)
        if created_to is not None:
            statement = statement.where(Report.created_at <= created_to)
        if has_radius_query:
            statement = statement.where(
                text(
                    "location IS NOT NULL AND "
                    "ST_DWithin(location::geography, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius_m)"
                )
            ).params(lat=lat, lng=lng, radius_m=radius_m)

        statement = statement.order_by(Report.created_at.desc()).limit(limit)
        reports = db.scalars(statement).all()
        return [report_to_response(report) for report in reports]

    @router.get("/reports/duplicate-check", response_model=DuplicateCheckResponse)
    async def check_report_duplicates(
        db: Session = Depends(get_db),
        class_name: ClassName = Query(...),
        captured_at: datetime = Query(...),
        lat: float = Query(..., ge=-90, le=90),
        lng: float = Query(..., ge=-180, le=180),
        radius_m: float = Query(default=DUPLICATE_RADIUS_M, gt=0, le=200),
        minutes: int = Query(default=DUPLICATE_WINDOW_MINUTES, ge=1, le=60),
    ) -> DuplicateCheckResponse:
        statement = duplicate_candidate_statement(
            class_name=class_name,
            captured_at=captured_at,
            lat=lat,
            lng=lng,
            radius_m=radius_m,
            minutes=minutes,
        )
        reports = list(db.scalars(statement).all())
        report_ids = [str(report.id) for report in reports]
        return DuplicateCheckResponse(
            duplicate_report_ids=report_ids,
            reports=[report_to_response(report) for report in reports],
        )

    @router.get("/reports/{report_id}", response_model=ReportResponse)
    async def get_report(report_id: uuid.UUID, db: Session = Depends(get_db)) -> ReportResponse:
        report = db.get(Report, report_id)
        if report is None:
            raise HTTPException(status_code=404, detail="report not found")
        return report_to_response(report)

    @router.patch("/reports/{report_id}/status", response_model=ReportResponse)
    async def update_report_status(
        report_id: uuid.UUID,
        payload: ReportStatusUpdate,
        db: Session = Depends(get_db),
    ) -> ReportResponse:
        report = db.get(Report, report_id)
        if report is None:
            raise HTTPException(status_code=404, detail="report not found")

        report.status = payload.status
        db.commit()
        db.refresh(report)
        return report_to_response(report)

    return router
