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
CLASS_ORDER = tuple(CLASS_NAMES[class_id] for class_id in sorted(CLASS_NAMES))

ClassId = Literal[0, 1, 2, 3]
ClassName = Literal["damaged_tactile_block", "parked_kickboard_bicycle", "construction_obstacle", "pothole"]
ReportStatus = Literal["new", "reviewed", "resolved"]
DetectorSource = Literal["fake", "onnx", "server"]
ReportDemoFilter = Literal["all", "only_fake", "exclude_fake"]
LocationQuality = Literal["missing", "low", "medium", "high"]
ModelStatus = Literal["unavailable", "ready"]
DetectV2ModelKey = Literal["custom_tactile", "coco_general"]
DetectV2DistanceSource = Literal["sensor_depth", "manual_fixture", "model_estimate", "unknown"]
ReportV2Trigger = Literal["auto", "voice"]
WalkingRouteProvider = Literal["tmap_pedestrian", "kakao_mobility"]
DestinationSearchProvider = Literal["tmap_poi"]
WalkingRoutePriority = Literal["RECOMMEND", "MAIN_STREET", "DISTANCE", "STAIR_AVOID"]


class BBox(BaseModel):
    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)
    width: float = Field(gt=0, le=1)
    height: float = Field(gt=0, le=1)


class GpsFix(BaseModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy_m: Optional[float] = Field(default=None, ge=0)


class RoutePoint(BaseModel):
    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    name: Optional[str] = None


class WalkingRouteRequest(BaseModel):
    origin: RoutePoint
    destination: RoutePoint
    waypoints: List[RoutePoint] = Field(default_factory=list, max_length=5)
    priority: WalkingRoutePriority = "STAIR_AVOID"
    radius_m: int = Field(default=5000, gt=0, le=12000)
    default_speed: Optional[float] = Field(default=None, ge=0)


class DestinationSearchResult(BaseModel):
    id: str
    name: str
    point: RoutePoint
    address: Optional[str] = None
    road_address: Optional[str] = None
    category: Optional[str] = None
    result_type: Literal["poi", "address", "alias"] = "poi"
    distance_m: Optional[int] = Field(default=None, ge=0)


class DestinationSearchResponse(BaseModel):
    schema_version: Literal["walksafe.destination_search.v1"]
    provider: DestinationSearchProvider
    query: str
    results: List[DestinationSearchResult]


class WalkingRouteSummary(BaseModel):
    distance_m: int = Field(ge=0)
    duration_s: int = Field(ge=0)


class WalkingRouteStep(BaseModel):
    index: int = Field(ge=0)
    distance_m: int = Field(ge=0)
    duration_s: int = Field(ge=0)
    points: List[RoutePoint]
    instruction: Optional[str] = None
    road_name: Optional[str] = None
    turn_type: Optional[int] = None
    facility_type: Optional[int] = None


class WalkingRouteGuidePoint(BaseModel):
    index: int = Field(ge=0)
    point: RoutePoint
    instruction: Optional[str] = None
    turn_type: Optional[int] = None
    point_type: Optional[str] = None
    facility_type: Optional[int] = None
    distance_from_start_m: Optional[int] = Field(default=None, ge=0)
    remaining_distance_m: Optional[int] = Field(default=None, ge=0)


class WalkingRouteResponse(BaseModel):
    schema_version: Literal["walksafe.walking_route.v1"]
    provider: WalkingRouteProvider
    provider_route_id: Optional[str] = None
    priority: WalkingRoutePriority
    summary: WalkingRouteSummary
    polyline: List[RoutePoint]
    steps: List[WalkingRouteStep]
    guide_points: List[WalkingRouteGuidePoint] = Field(default_factory=list)
    provider_result_code: int
    provider_result_message: str


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
    note: Optional[str] = Field(default=None, max_length=500)
    resolution_reason: Optional[str] = Field(default=None, max_length=500)
    expected_updated_at: Optional[datetime] = None


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
    model_artifact_path: Optional[str] = None
    model_class_order: List[ClassName]
    model_confidence_threshold: float = Field(ge=0, le=1)
    model_iou_threshold: float = Field(ge=0, le=1)
    model_image_size: int = Field(gt=0)


class DetectResponse(BaseModel):
    model_status: Literal["ready"]
    model_version: str
    detections: List[ReportMetadata]


class DetectV2Detection(BaseModel):
    schema_version: Literal["detect.v2"]
    model_key: DetectV2ModelKey
    source_model: str
    model_class_id: int = Field(ge=0)
    class_name: str
    category: str
    confidence: float = Field(ge=0, le=1)
    bbox: BBox
    distance_m: Optional[float] = Field(default=None, ge=0, le=50)
    distance_source: Optional[DetectV2DistanceSource] = None
    distance_confidence: Optional[float] = Field(default=None, ge=0, le=1)
    threshold_used: float = Field(ge=0, le=1)
    captured_at: datetime
    gps: Optional[GpsFix] = None
    heading: Optional[float] = Field(default=None, ge=0, lt=360)


class DetectV2Response(BaseModel):
    schema_version: Literal["detect.v2"]
    detections: List[DetectV2Detection]


class ReportV2Metadata(DetectV2Detection):
    trigger: ReportV2Trigger
    auto_reported: bool
