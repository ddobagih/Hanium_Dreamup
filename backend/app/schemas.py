"""Pydantic contracts shared by backend routers, services, and tests.

Legacy report ids and v2 model-local class ids intentionally remain separate.
Changing a schema here changes the HTTP contract as well as persisted report
metadata expectations.
"""

from __future__ import annotations

from datetime import UTC, datetime
import re
from typing import Annotated, Any, Dict, List, Literal, Optional
import uuid

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    StringConstraints,
    field_serializer,
    field_validator,
    model_validator,
)


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
DetectorSource = Literal["fake", "onnx", "server", "android"]
ReportDemoFilter = Literal["all", "only_fake", "exclude_fake"]
LocationQuality = Literal["missing", "low", "medium", "high"]
ModelStatus = Literal["unavailable", "ready"]
DetectV2ModelKey = Literal["custom_tactile", "coco_general", "unified_walksafe"]
DetectV2DistanceSource = Literal["sensor_depth", "manual_fixture", "model_estimate", "unknown"]
DetectV2ApproachState = Literal["approaching", "stable", "receding", "unknown"]
ReportV2Trigger = Literal["auto", "voice"]
ReportCoordinateGateStatus = Literal["pass", "pending", "failed", "gps_missing"]
ReportReviewFlag = Annotated[str, StringConstraints(min_length=1, max_length=64, pattern=r"^[a-z0-9_:-]+$")]
ReportOriginalAccessPurpose = Literal["report_review", "security_incident", "data_subject_request"]
ReportReviewDecisionValue = Literal["APPROVED", "REJECTED", "DUPLICATE"]
ReportInstitutionDeliveryStatus = Literal["SUBMITTED", "ACKNOWLEDGED", "RESOLVED", "FAILED"]
ReportReviewReason = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=500)]
ReportInstitution = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=160)]
ReportDeliveryChannel = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=32)]
ReportDeliveryRecipient = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=255)]
ReportExternalReceiptId = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=160)]
Sha256LowerHex = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]
AccountDeletionRequestId = Annotated[
    str,
    StringConstraints(min_length=16, max_length=128, pattern=r"^[A-Za-z0-9_-]{16,128}$"),
]
PrivacyEvidenceId = Annotated[
    str,
    StringConstraints(min_length=8, max_length=128, pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$"),
]
AccountDeletionItemKey = Literal[
    "device_untransmitted_data",
    "server_originals",
    "server_quarantine",
    "server_copies",
    "report_records",
    "training_datasets",
    "training_labels",
    "derived_artifacts",
    "backups",
]
AccountDeletionItemState = Literal[
    "PENDING",
    "IN_PROGRESS",
    "EXTERNAL_PENDING",
    "RETRY_WAIT",
    "LEGAL_HOLD",
    "FAILED",
    "COMPLETED",
    "NOT_APPLICABLE",
]
AccountDeletionOverallStatus = Literal[
    "PROCESSING",
    "RETRY_WAIT",
    "PARTIAL",
    "RESTRICTED",
    "FAILED",
    "COMPLETED",
]
WalkingRouteProvider = Literal["tmap_pedestrian"]
DestinationSearchProvider = Literal["tmap_poi"]
WalkingRoutePriority = Literal["RECOMMEND", "MAIN_STREET", "DISTANCE", "STAIR_AVOID"]
_RFC3339_UTC_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|\+00:00)$"
)
_WHOLE_SECOND_UTC_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$"
)


class BBox(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)

    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)
    width: float = Field(gt=0, le=1)
    height: float = Field(gt=0, le=1)

    @model_validator(mode="after")
    def _within_unit_square(self) -> "BBox":
        if self.x + self.width > 1:
            raise ValueError("x + width must be <= 1")
        if self.y + self.height > 1:
            raise ValueError("y + height must be <= 1")
        return self


class AndroidDebugRect(BaseModel):
    model_config = ConfigDict(extra="forbid")

    x: float = Field(ge=0, le=1)
    y: float = Field(ge=0, le=1)
    width: float = Field(gt=0, le=1)
    height: float = Field(gt=0, le=1)


class AndroidDebugDepthLogEntry(BaseModel):
    model_config = ConfigDict(extra="forbid")

    frame_timestamp_ms: int = Field(ge=0)
    detector_frame_timestamp_ms: Optional[int] = Field(default=None, ge=0)
    detector_age_ms: Optional[int] = Field(default=None, ge=0)
    detector_source_age_ms: Optional[int] = Field(default=None, ge=0)
    detector_completed_age_ms: Optional[int] = Field(default=None, ge=0)
    detector_frame_delta_ms: Optional[int] = Field(default=None, ge=0)
    detect_duration_ms: Optional[int] = Field(default=None, ge=0)
    detector_yuv_decode_ms: Optional[int] = Field(default=None, ge=0)
    detector_model_key: Optional[str] = Field(default=None, max_length=80)
    detector_loaded_model_key: Optional[str] = Field(default=None, max_length=80)
    detector_model_fallback_used: Optional[bool] = None
    detector_model_load_reason: Optional[str] = Field(default=None, max_length=160)
    detector_model_preprocess_ms: Optional[int] = Field(default=None, ge=0)
    detector_model_inference_ms: Optional[int] = Field(default=None, ge=0)
    detector_model_parse_ms: Optional[int] = Field(default=None, ge=0)
    detector_coco_preprocess_ms: Optional[int] = Field(default=None, ge=0)
    detector_coco_inference_ms: Optional[int] = Field(default=None, ge=0)
    detector_coco_parse_ms: Optional[int] = Field(default=None, ge=0)
    detector_custom_preprocess_ms: Optional[int] = Field(default=None, ge=0)
    detector_custom_inference_ms: Optional[int] = Field(default=None, ge=0)
    detector_custom_parse_ms: Optional[int] = Field(default=None, ge=0)
    detector_completed_models: List[str] = Field(default_factory=list, max_length=4)
    detector_skipped_models: List[str] = Field(default_factory=list, max_length=4)
    detector_partial: bool = False
    detection_count: int = Field(ge=0, le=200)
    detections_used_for_depth: bool
    stale_reason: Optional[str] = Field(default=None, max_length=128)
    top_detection_class_name: Optional[str] = Field(default=None, max_length=80)
    top_detection_confidence: Optional[float] = Field(default=None, ge=0, le=1)
    top_detection_bbox: Optional[AndroidDebugRect] = None
    best_depth_class_name: Optional[str] = Field(default=None, max_length=80)
    best_depth_track_id: Optional[str] = Field(default=None, max_length=80)
    best_depth_source: Optional[str] = Field(default=None, max_length=80)
    best_depth_detection_confidence: Optional[float] = Field(default=None, ge=0, le=1)
    best_depth_confidence_score: Optional[float] = Field(default=None, ge=0, le=1)
    best_depth_median_m: Optional[float] = Field(default=None, ge=0, le=50)
    best_depth_p20_m: Optional[float] = Field(default=None, ge=0, le=50)
    best_depth_risk_distance_m: Optional[float] = Field(default=None, ge=0, le=50)
    best_depth_iqr_m: Optional[float] = Field(default=None, ge=0, le=50)
    best_depth_valid_sample_count: Optional[int] = Field(default=None, ge=0, le=10000)
    best_depth_valid_sample_ratio: Optional[float] = Field(default=None, ge=0, le=1)
    best_depth_bbox: Optional[AndroidDebugRect] = None
    preview_width: Optional[int] = Field(default=None, gt=0, le=10000)
    preview_height: Optional[int] = Field(default=None, gt=0, le=10000)
    camera_image_width: Optional[int] = Field(default=None, gt=0, le=10000)
    camera_image_height: Optional[int] = Field(default=None, gt=0, le=10000)
    display_rotation: Optional[int] = Field(default=None, ge=0, le=3)
    depth_width: Optional[int] = Field(default=None, gt=0, le=10000)
    depth_height: Optional[int] = Field(default=None, gt=0, le=10000)
    overlay_transform_path: Optional[str] = Field(default=None, max_length=80)
    depth_transform_path: Optional[str] = Field(default=None, max_length=80)
    transform_path: Optional[str] = Field(default=None, max_length=80)
    fallback_reason: Optional[str] = Field(default=None, max_length=160)


class AndroidDebugDepthLogRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["android.depth_debug.v1"]
    session_id: str = Field(min_length=1, max_length=128)
    device_model: Optional[str] = Field(default=None, max_length=128)
    android_version: Optional[str] = Field(default=None, max_length=64)
    app_version_name: Optional[str] = Field(default=None, max_length=64)
    entries: List[AndroidDebugDepthLogEntry] = Field(min_length=1, max_length=30)


class GpsFix(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)

    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    accuracy_m: Optional[float] = Field(default=None, ge=0)


class RoutePoint(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)

    latitude: float = Field(ge=-90, le=90)
    longitude: float = Field(ge=-180, le=180)
    name: Optional[str] = Field(default=None, max_length=160)


class WalkingRouteRequest(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)

    origin: RoutePoint
    destination: RoutePoint
    waypoints: List[RoutePoint] = Field(default_factory=list, max_length=5)
    priority: WalkingRoutePriority = "STAIR_AVOID"
    radius_m: int = Field(default=5000, gt=0, le=12000)
    default_speed: Optional[float] = Field(default=None, gt=0, le=20)


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
    bearing_deg: Optional[float] = Field(default=None, ge=0, lt=360)


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
    model_config = ConfigDict(allow_inf_nan=False)

    class_id: ClassId
    class_name: ClassName
    confidence: float = Field(ge=0, le=1)
    bbox: BBox
    captured_at: AwareDatetime
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
    expected_updated_at: Optional[AwareDatetime] = None


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
    duplicate_count: int = Field(default=0, ge=0)
    created_at: datetime
    updated_at: datetime


class ReportOriginalAccessGrantRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    purpose: ReportOriginalAccessPurpose
    reason: str = Field(min_length=8, max_length=500)

    @field_validator("reason")
    @classmethod
    def normalize_reason(cls, value: str) -> str:
        normalized = " ".join(value.split())
        if len(normalized) < 8:
            raise ValueError("reason must contain at least 8 non-whitespace characters")
        return normalized


class ReportOriginalAccessGrantResponse(BaseModel):
    schema_version: Literal["walksafe.report-original-access-grant.v1"]
    grant_id: uuid.UUID
    report_id: uuid.UUID
    purpose: ReportOriginalAccessPurpose
    access_token: str
    expires_at: AwareDatetime


class ReportReviewDecisionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    decision: ReportReviewDecisionValue
    reason: ReportReviewReason
    duplicate_of_report_id: uuid.UUID | None
    location_reviewed: bool = Field(strict=True)
    photo_reviewed: bool = Field(strict=True)
    privacy_reviewed: bool = Field(strict=True)

    @model_validator(mode="after")
    def validate_decision_evidence(self) -> "ReportReviewDecisionRequest":
        if self.decision == "APPROVED":
            if self.duplicate_of_report_id is not None:
                raise ValueError("APPROVED decisions cannot reference a duplicate report")
            if not (
                self.location_reviewed
                and self.photo_reviewed
                and self.privacy_reviewed
            ):
                raise ValueError("APPROVED decisions require every review check")
        elif self.decision == "DUPLICATE":
            if self.duplicate_of_report_id is None:
                raise ValueError("DUPLICATE decisions require duplicate_of_report_id")
        elif self.duplicate_of_report_id is not None:
            raise ValueError("REJECTED decisions cannot reference a duplicate report")
        return self


class ReportReviewDecisionResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    report_id: uuid.UUID
    revision: int = Field(ge=1)
    decision: ReportReviewDecisionValue
    reason: str
    duplicate_of_report_id: uuid.UUID | None
    location_reviewed: bool
    photo_reviewed: bool
    privacy_reviewed: bool
    admin_id: str
    session_id: uuid.UUID
    device_id: str
    correlation_id: uuid.UUID
    decided_at: AwareDatetime
    created_at: AwareDatetime


class ReportInstitutionDeliveryRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    institution: ReportInstitution
    channel: ReportDeliveryChannel
    recipient: ReportDeliveryRecipient
    status: ReportInstitutionDeliveryStatus
    external_receipt_id: ReportExternalReceiptId | None
    reason: ReportReviewReason
    evidence_sha256: Sha256LowerHex | None
    observed_at: AwareDatetime = Field(
        json_schema_extra={"pattern": _RFC3339_UTC_PATTERN.pattern}
    )
    expected_revision: int = Field(ge=0, strict=True)
    idempotency_key: uuid.UUID

    @field_validator("observed_at", mode="before")
    @classmethod
    def require_rfc3339_utc_text(cls, value: object) -> object:
        if type(value) is not str or _RFC3339_UTC_PATTERN.fullmatch(value) is None:
            raise ValueError("observed_at must be RFC3339 UTC text")
        return value

    @field_validator("observed_at")
    @classmethod
    def require_utc_observed_at(cls, value: datetime) -> datetime:
        if value.utcoffset() is None or value.utcoffset().total_seconds() != 0:
            raise ValueError("observed_at must use UTC offset zero")
        return value.astimezone(UTC)

    @field_validator("idempotency_key", mode="before")
    @classmethod
    def require_canonical_idempotency_key(cls, value: object) -> object:
        if isinstance(value, uuid.UUID):
            return value
        if not isinstance(value, str):
            raise ValueError("idempotency_key must be a canonical UUID")
        try:
            parsed = uuid.UUID(value)
        except ValueError as exc:
            raise ValueError("idempotency_key must be a canonical UUID") from exc
        if str(parsed) != value:
            raise ValueError("idempotency_key must be a canonical UUID")
        return parsed

    @model_validator(mode="after")
    def require_receipt_for_confirmed_status(self) -> "ReportInstitutionDeliveryRequest":
        if self.status in {"ACKNOWLEDGED", "RESOLVED"} and self.external_receipt_id is None:
            raise ValueError("ACKNOWLEDGED and RESOLVED events require external_receipt_id")
        return self


class ReportInstitutionDeliveryResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    report_id: uuid.UUID
    review_decision_id: uuid.UUID
    revision: int = Field(ge=1)
    institution: str
    channel: str
    recipient: str
    status: ReportInstitutionDeliveryStatus
    external_receipt_id: str | None
    reason: str
    evidence_sha256: str | None
    observed_at: AwareDatetime
    expected_revision: int = Field(ge=0)
    idempotency_key: uuid.UUID
    admin_id: str
    session_id: uuid.UUID
    device_id: str
    correlation_id: uuid.UUID
    recorded_at: AwareDatetime


class DuplicateCheckResponse(BaseModel):
    duplicate_report_ids: List[str]
    duplicate_count: int = Field(ge=0)


class DetectContext(BaseModel):
    model_config = ConfigDict(allow_inf_nan=False)

    captured_at: Optional[AwareDatetime] = None
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
    model_config = ConfigDict(allow_inf_nan=False)

    schema_version: Literal["detect.v2"]
    model_key: DetectV2ModelKey
    source_model: str = Field(
        min_length=1,
        max_length=160,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._ +/@:-]*$",
    )
    model_class_id: int = Field(ge=0)
    class_name: str
    category: str
    confidence: float = Field(ge=0, le=1)
    bbox: BBox
    distance_m: Optional[float] = Field(default=None, ge=0, le=50)
    distance_source: Optional[DetectV2DistanceSource] = None
    distance_confidence: Optional[float] = Field(default=None, ge=0, le=1)
    approach_state: Optional[DetectV2ApproachState] = None
    threshold_used: float = Field(ge=0, le=1)
    captured_at: AwareDatetime
    gps: Optional[GpsFix] = None
    heading: Optional[float] = Field(default=None, ge=0, lt=360)

    @field_validator("source_model")
    @classmethod
    def source_model_is_identifier_not_filesystem_path(cls, value: str) -> str:
        normalized = value.strip()
        if not normalized:
            raise ValueError("source_model must not be blank")
        if any(ord(character) < 32 for character in normalized):
            raise ValueError("source_model must not contain control characters")
        if (
            normalized.startswith(("/", "\\", "~"))
            or "\\" in normalized
            or "://" in normalized
            or re.match(r"^[A-Za-z]:", normalized)
            or any(part in {"", ".", ".."} for part in normalized.split("/"))
        ):
            raise ValueError("source_model must be a model identifier, not a filesystem path or URL")
        return normalized


class DetectV2Response(BaseModel):
    schema_version: Literal["detect.v2"]
    detections: List[DetectV2Detection]


class ReportV2Metadata(DetectV2Detection):
    source: Optional[Literal["android"]] = None
    trigger: ReportV2Trigger
    auto_reported: bool
    reporter_user_id: Optional[str] = Field(default=None, min_length=1, max_length=128)
    review_flags: List[ReportReviewFlag] = Field(default_factory=list, max_length=20)
    fake_source: bool = False
    trace_id: Optional[str] = Field(default=None, min_length=1, max_length=128)
    apk_sha256: Optional[str] = Field(default=None, min_length=64, max_length=64, pattern=r"^[0-9a-fA-F]{64}$")
    source_commit: Optional[str] = Field(
        default=None,
        pattern=r"^(?:[0-9a-fA-F]{40}|unverified)$",
    )
    model_config_sha256: Optional[str] = Field(
        default=None,
        min_length=64,
        max_length=64,
        pattern=r"^[0-9a-fA-F]{64}$",
    )
    android_model_version: Optional[str] = Field(default=None, min_length=1, max_length=80)
    bbox_coordinate_space: Optional[str] = Field(default=None, min_length=1, max_length=80)
    depth_coordinate_space: Optional[str] = Field(default=None, min_length=1, max_length=80)
    depth_sample_count: Optional[int] = Field(default=None, ge=0, le=100000)
    depth_valid_sample_ratio: Optional[float] = Field(default=None, ge=0, le=1)
    detection_age_ms: Optional[int] = Field(default=None, ge=0, le=60000)
    coordinate_gate_status: Optional[ReportCoordinateGateStatus] = None
    fallback_used: Optional[bool] = None
    loaded_model_key: Optional[str] = Field(default=None, min_length=1, max_length=80)
    model_load_reason: Optional[str] = Field(default=None, min_length=1, max_length=160)

    @model_validator(mode="after")
    def trigger_matches_auto_reported(self) -> "ReportV2Metadata":
        expected_auto_reported = self.trigger == "auto"
        if self.auto_reported != expected_auto_reported:
            raise ValueError("auto_reported must be true for auto trigger and false for voice trigger")
        return self


class AccountDeletionRequestV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.account-deletion-request.v2"]
    request_id: AccountDeletionRequestId
    client_revision: int = Field(ge=1, le=9_223_372_036_854_775_807)
    confirmation: Literal["DELETE_MY_ACCOUNT"]


PRIVACY_CONSENT_POLICY_VERSION = "FP-013-1.0.0"
PRIVACY_CONSENT_ITEM_VERSIONS = {
    "raw_source_collection": "FP-013-RAW-1.0.0",
    "automatic_reporting": "FP-013-AUTO-1.0.0",
    "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
    "training_reuse": "FP-013-TRAINING-1.0.0",
}


class PrivacyConsentItemVersionsV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    raw_source_collection: Literal["FP-013-RAW-1.0.0"]
    automatic_reporting: Literal["FP-013-AUTO-1.0.0"]
    mobile_network_transfer: Literal["FP-013-MOBILE-1.0.0"]
    training_reuse: Literal["FP-013-TRAINING-1.0.0"]


class PrivacyConsentEventV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.privacy-consent-event.v2"]
    installation_id: PrivacyEvidenceId
    request_id: AccountDeletionRequestId
    client_revision: int = Field(ge=1, le=9_223_372_036_854_775_807)
    policy_version: Literal["FP-013-1.0.0"]
    item_versions: PrivacyConsentItemVersionsV2
    raw_source_collection: bool
    automatic_reporting: bool
    mobile_network_transfer: bool
    training_reuse: bool


class PrivacyConsentReceiptV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.privacy-consent-receipt.v2"]
    request_id: AccountDeletionRequestId
    client_revision: int = Field(ge=1)
    receipt_sha256: Sha256LowerHex
    recorded_at: AwareDatetime

    @field_serializer("recorded_at")
    def canonical_recorded_at(self, value: datetime) -> str:
        return value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class PrivacyErrorDetailV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    code: str
    message: Optional[str] = None
    current_status_revision: Optional[int] = Field(default=None, ge=1)
    current_overall_status: Optional[str] = None
    max_bytes: Optional[int] = Field(default=None, ge=1)


class PrivacyErrorResponseV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    detail: PrivacyErrorDetailV2


class DeviceDeletionEvidenceV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.device-deletion-evidence.v2"]
    request_id: AccountDeletionRequestId
    tombstone_id: PrivacyEvidenceId
    request_receipt_sha256: Sha256LowerHex
    installation_id: PrivacyEvidenceId
    evidence_id: PrivacyEvidenceId
    client_revision: int = Field(ge=1, le=9_223_372_036_854_775_807)
    expected_status_revision: int = Field(ge=1, le=9_223_372_036_854_775_807)
    item: Literal["device_untransmitted_data"]
    result: Literal["DELETED", "NOT_FOUND", "FAILED"]
    completed_at: AwareDatetime = Field(
        json_schema_extra={"pattern": _WHOLE_SECOND_UTC_PATTERN.pattern}
    )
    evidence_sha256: Sha256LowerHex

    @field_validator("completed_at", mode="before")
    @classmethod
    def require_rfc3339_utc_text(cls, value: object) -> object:
        if type(value) is not str or _WHOLE_SECOND_UTC_PATTERN.fullmatch(value) is None:
            raise ValueError("completed_at must be whole-second UTC text ending in Z")
        return value

    @field_validator("completed_at")
    @classmethod
    def require_utc_offset(cls, value: datetime) -> datetime:
        if value.utcoffset() is None or value.utcoffset().total_seconds() != 0:
            raise ValueError("completed_at must use UTC")
        return value


class AccountDeletionItemStatusV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    key: AccountDeletionItemKey
    status: AccountDeletionItemState
    item_revision: int = Field(ge=1)
    due_at: AwareDatetime
    updated_at: AwareDatetime
    evidence_sha256: Optional[Sha256LowerHex]
    disposition_basis: Optional[str] = Field(min_length=1, max_length=500)
    retry_after: Optional[AwareDatetime]
    restriction_reason: Optional[str] = Field(min_length=1, max_length=500)
    legal_hold_review_at: Optional[AwareDatetime]
    legal_hold_contact: Optional[str] = Field(min_length=1, max_length=160)
    terminal_at: Optional[AwareDatetime]

    @model_validator(mode="after")
    def require_state_evidence(self) -> "AccountDeletionItemStatusV2":
        terminal = self.status in {"COMPLETED", "NOT_APPLICABLE"}
        if terminal != (self.terminal_at is not None) or (
            terminal and self.evidence_sha256 is None
        ) or (not terminal and self.evidence_sha256 is not None):
            raise ValueError("terminal states require evidence and terminal_at")
        if (self.status == "RETRY_WAIT") != (self.retry_after is not None):
            raise ValueError("RETRY_WAIT requires retry_after exclusively")
        if self.status == "NOT_APPLICABLE":
            if self.disposition_basis is None:
                raise ValueError("NOT_APPLICABLE requires disposition_basis")
        elif self.disposition_basis is not None:
            raise ValueError("disposition_basis is exclusive to NOT_APPLICABLE")
        legal_hold_fields = (
            self.restriction_reason,
            self.legal_hold_review_at,
            self.legal_hold_contact,
        )
        if self.status == "LEGAL_HOLD":
            if any(value is None for value in legal_hold_fields):
                raise ValueError("LEGAL_HOLD requires reason, review time, and contact")
        elif any(value is not None for value in legal_hold_fields):
            raise ValueError("legal-hold fields are exclusive to LEGAL_HOLD")
        if self.terminal_at is not None and self.terminal_at > self.updated_at:
            raise ValueError("terminal_at cannot be after updated_at")
        if self.retry_after is not None and self.retry_after <= self.updated_at:
            raise ValueError("retry_after must be after updated_at")
        if (
            self.legal_hold_review_at is not None
            and self.legal_hold_review_at <= self.updated_at
        ):
            raise ValueError("legal_hold_review_at must be after updated_at")
        return self


class AccountDeletionStatusV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.account-deletion-status.v2"]
    request_id: AccountDeletionRequestId
    client_revision: int = Field(ge=1)
    revision: int = Field(ge=1)
    accepted_at: AwareDatetime
    updated_at: AwareDatetime
    account_generation: int = Field(ge=1)
    tombstone_id: PrivacyEvidenceId
    request_receipt_sha256: Sha256LowerHex
    overall_status: AccountDeletionOverallStatus
    items: List[AccountDeletionItemStatusV2] = Field(min_length=9, max_length=9)
    completion_receipt_sha256: Optional[Sha256LowerHex]

    @model_validator(mode="after")
    def require_canonical_inventory_order(self) -> "AccountDeletionStatusV2":
        expected = (
            "device_untransmitted_data",
            "server_originals",
            "server_quarantine",
            "server_copies",
            "report_records",
            "training_datasets",
            "training_labels",
            "derived_artifacts",
            "backups",
        )
        if tuple(item.key for item in self.items) != expected:
            raise ValueError("items must contain the canonical nine-item inventory in order")
        if (self.overall_status == "COMPLETED") != (
            self.completion_receipt_sha256 is not None
        ):
            raise ValueError("COMPLETED requires a completion receipt exclusively")
        if self.updated_at < self.accepted_at:
            raise ValueError("updated_at cannot precede accepted_at")
        return self


SUBMISSION_HANDLE_PATTERN = r"^onb_[0-9a-f]{32}$"
ACTOR_BINDING_PATTERN = r"^actor_[0-9a-f]{32}$"
RECEIPT_SHA256_PATTERN = r"^[0-9a-f]{64}$"


class FirstRunSubmitEmailRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    email: str = Field(min_length=3, max_length=254)


class FirstRunVerifyEmailRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    submission_handle: str = Field(pattern=SUBMISSION_HANDLE_PATTERN)
    code: str = Field(min_length=1, max_length=16)


class FirstRunActivationRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    submission_handle: str = Field(pattern=SUBMISSION_HANDLE_PATTERN)


class FirstRunLoginRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    submission_handle: str = Field(pattern=SUBMISSION_HANDLE_PATTERN)


class FirstRunStageReceipt(BaseModel):
    model_config = ConfigDict(extra="forbid")

    receipt_sha256: str = Field(pattern=RECEIPT_SHA256_PATTERN)


class FirstRunSubmissionReceipt(FirstRunStageReceipt):
    submission_handle: str = Field(pattern=SUBMISSION_HANDLE_PATTERN)
    # 메일 발송 수단이 아직 없다. 운영 환경에서는 이 경로 자체가 503 으로 닫힌다.
    verification_code: str = Field(min_length=1, max_length=16)


class FirstRunLoginReceipt(FirstRunStageReceipt):
    actor_binding: str = Field(pattern=ACTOR_BINDING_PATTERN)
