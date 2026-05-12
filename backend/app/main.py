from __future__ import annotations

import json
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import List, Optional, Sequence

from fastapi import Depends, FastAPI, File, Form, HTTPException, Query, UploadFile, status
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from geoalchemy2.elements import WKTElement
from pydantic import ValidationError
from sqlalchemy import select, text
from sqlalchemy.orm import Session

from backend.app.config import get_settings
from backend.app.database import get_db
from backend.app.detector import detect_health, run_detection
from backend.app.models import Report
from backend.app.schemas import (
    ClassName,
    DetectContext,
    DetectHealthResponse,
    DetectResponse,
    DetectorSource,
    DuplicateCheckResponse,
    LocationQuality,
    ReportMetadata,
    ReportResponse,
    ReportStatus,
    ReportStatusUpdate,
)


settings = get_settings()
settings.upload_dir.mkdir(parents=True, exist_ok=True)

app = FastAPI(title="WalkSafe Assist API", version="0.1.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.mount("/uploads", StaticFiles(directory=settings.upload_dir), name="uploads")


DUPLICATE_RADIUS_M = 25.0
DUPLICATE_WINDOW_MINUTES = 10
LOW_CONFIDENCE_THRESHOLD = 0.7
HIGH_LOCATION_ACCURACY_M = 15.0
MEDIUM_LOCATION_ACCURACY_M = 50.0


def location_quality(report: Report) -> LocationQuality:
    if report.latitude is None or report.longitude is None:
        return "missing"
    if report.accuracy_m is None or report.accuracy_m > MEDIUM_LOCATION_ACCURACY_M:
        return "low"
    if report.accuracy_m > HIGH_LOCATION_ACCURACY_M:
        return "medium"
    return "high"


def review_flags(report: Report) -> list[str]:
    flags: list[str] = []
    quality = location_quality(report)

    if report.source == "fake":
        flags.append("fake_source")
    if report.confidence < LOW_CONFIDENCE_THRESHOLD:
        flags.append("low_confidence")
    if quality == "missing":
        flags.append("missing_location")
    elif quality == "low":
        flags.append("low_location_accuracy")
    if report.heading is None:
        flags.append("missing_heading")

    return flags


def report_to_response(report: Report, duplicate_report_ids: Optional[Sequence[str]] = None) -> ReportResponse:
    gps = None
    if report.latitude is not None and report.longitude is not None:
        gps = {
            "latitude": report.latitude,
            "longitude": report.longitude,
            "accuracy_m": report.accuracy_m,
        }

    return ReportResponse(
        id=str(report.id),
        status=report.status,
        class_id=report.class_id,
        class_name=report.class_name,
        confidence=report.confidence,
        bbox={
            "x": report.bbox_x,
            "y": report.bbox_y,
            "width": report.bbox_width,
            "height": report.bbox_height,
        },
        captured_at=report.captured_at,
        source=report.source,
        gps=gps,
        heading=report.heading,
        image_path=report.image_path,
        image_content_type=report.image_content_type,
        metadata=report.payload,
        location_quality=location_quality(report),
        review_flags=review_flags(report),
        duplicate_report_ids=list(duplicate_report_ids or []),
        created_at=report.created_at,
        updated_at=report.updated_at,
    )


def duplicate_candidate_statement(
    class_name: ClassName,
    captured_at: datetime,
    lat: float,
    lng: float,
    radius_m: float,
    minutes: int,
    exclude_id: Optional[uuid.UUID] = None,
):
    if captured_at.tzinfo is None:
        captured_at = captured_at.replace(tzinfo=timezone.utc)
    starts_at = captured_at - timedelta(minutes=minutes)
    ends_at = captured_at + timedelta(minutes=minutes)

    statement = (
        select(Report)
        .where(Report.class_name == class_name)
        .where(Report.location.is_not(None))
        .where(Report.captured_at >= starts_at)
        .where(Report.captured_at <= ends_at)
        .where(
            text(
                "ST_DWithin(location::geography, ST_SetSRID(ST_MakePoint(:lng, :lat), 4326)::geography, :radius_m)"
            )
        )
        .params(lat=lat, lng=lng, radius_m=radius_m)
        .order_by(Report.created_at.desc())
        .limit(20)
    )

    if exclude_id is not None:
        statement = statement.where(Report.id != exclude_id)

    return statement


def find_duplicate_candidates(
    db: Session,
    metadata: ReportMetadata,
    radius_m: float = DUPLICATE_RADIUS_M,
    minutes: int = DUPLICATE_WINDOW_MINUTES,
) -> list[Report]:
    if metadata.gps is None:
        return []

    statement = duplicate_candidate_statement(
        class_name=metadata.class_name,
        captured_at=metadata.captured_at,
        lat=metadata.gps.latitude,
        lng=metadata.gps.longitude,
        radius_m=radius_m,
        minutes=minutes,
    )
    return list(db.scalars(statement).all())


def image_suffix(upload: UploadFile) -> str:
    content_type = upload.content_type or ""
    if content_type == "image/png":
        return ".png"
    if content_type == "image/webp":
        return ".webp"
    return ".jpg"


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/detect/health", response_model=DetectHealthResponse)
def get_detect_health() -> DetectHealthResponse:
    return detect_health()


@app.post("/detect", response_model=DetectResponse)
async def detect_objects(
    context: str = Form(default="{}"),
    image: UploadFile = File(...),
) -> DetectResponse:
    try:
        parsed_context = DetectContext.model_validate_json(context)
    except (ValidationError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    content_type = image.content_type or "application/octet-stream"
    if not content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="image must be an image upload")

    try:
        return await run_detection(image=image, context=parsed_context)
    except RuntimeError as exc:
        raise HTTPException(
            status_code=503,
            detail={
                "code": "model_unavailable",
                "reason": str(exc),
                "message": "Server inference is not available until a model adapter is implemented.",
            },
        ) from exc


@app.post("/reports", response_model=ReportResponse, status_code=status.HTTP_201_CREATED)
async def create_report(
    metadata: str = Form(...),
    image: UploadFile = File(...),
    db: Session = Depends(get_db),
) -> ReportResponse:
    try:
        parsed = ReportMetadata.model_validate_json(metadata)
    except (ValidationError, json.JSONDecodeError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    content_type = image.content_type or "application/octet-stream"
    if not content_type.startswith("image/"):
        raise HTTPException(status_code=400, detail="image must be an image upload")

    report_id = uuid.uuid4()
    filename = f"{report_id}{image_suffix(image)}"
    destination = settings.upload_dir / filename
    content = await image.read()
    destination.write_bytes(content)

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


@app.get("/reports", response_model=List[ReportResponse])
def list_reports(
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


@app.get("/reports/duplicate-check", response_model=DuplicateCheckResponse)
def check_report_duplicates(
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


@app.get("/reports/{report_id}", response_model=ReportResponse)
def get_report(report_id: uuid.UUID, db: Session = Depends(get_db)) -> ReportResponse:
    report = db.get(Report, report_id)
    if report is None:
        raise HTTPException(status_code=404, detail="report not found")
    return report_to_response(report)


@app.patch("/reports/{report_id}/status", response_model=ReportResponse)
def update_report_status(
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
