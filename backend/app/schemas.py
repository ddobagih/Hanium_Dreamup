from __future__ import annotations

from datetime import datetime
from typing import Any, Dict, List, Literal, Optional

from pydantic import BaseModel, Field, model_validator


CLASS_NAMES = {
    0: "damaged_tactile_block",
    1: "parked_kickboard_bicycle",
    2: "construction_obstacle",
    3: "pothole",
}

ClassId = Literal[0, 1, 2, 3]
ClassName = Literal["damaged_tactile_block", "parked_kickboard_bicycle", "construction_obstacle", "pothole"]
ReportStatus = Literal["new", "reviewed", "resolved"]
DetectorSource = Literal["fake", "onnx", "server"]
LocationQuality = Literal["missing", "low", "medium", "high"]
ModelStatus = Literal["unavailable", "ready"]


class BBox(BaseModel):
    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)
    width: float = Field(gt=0, le=1)
    height: float = Field(gt=0, le=1)


class GpsFix(BaseModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy_m: Optional[float] = Field(default=None, ge=0)


class ReportMetadata(BaseModel):
    class_id: ClassId
    class_name: ClassName
    confidence: float = Field(ge=0, le=1)
    bbox: BBox
    captured_at: datetime
    source: DetectorSource
    gps: Optional[GpsFix] = None
    heading: Optional[float] = Field(default=None, ge=0, lt=360)

    @model_validator(mode="after")
    def class_name_matches_id(self) -> "ReportMetadata":
        if CLASS_NAMES[self.class_id] != self.class_name:
            raise ValueError("class_name must match class_id")
        return self


class ReportStatusUpdate(BaseModel):
    status: ReportStatus


class ReportResponse(BaseModel):
    id: str
    status: ReportStatus
    class_id: int
    class_name: str
    confidence: float
    bbox: BBox
    captured_at: datetime
    source: str
    gps: Optional[GpsFix]
    heading: Optional[float]
    image_path: str
    image_content_type: str
    metadata: Dict[str, Any]
    location_quality: LocationQuality
    review_flags: List[str]
    duplicate_report_ids: List[str] = Field(default_factory=list)
    created_at: datetime
    updated_at: datetime


class DuplicateCheckResponse(BaseModel):
    duplicate_report_ids: List[str]
    reports: List[ReportResponse]


class DetectContext(BaseModel):
    captured_at: Optional[datetime] = None
    gps: Optional[GpsFix] = None
    heading: Optional[float] = Field(default=None, ge=0, lt=360)


class DetectHealthResponse(BaseModel):
    model_status: ModelStatus
    model_version: Optional[str] = None
    reason: Optional[str] = None


class DetectResponse(BaseModel):
    model_status: Literal["ready"]
    model_version: str
    detections: List[ReportMetadata]
