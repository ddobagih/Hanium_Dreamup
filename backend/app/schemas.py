"""Pydantic contracts shared by backend routers, services, and tests.

Legacy report ids and v2 model-local class ids intentionally remain separate.
Changing a schema here changes the HTTP contract as well as persisted report
metadata expectations.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
import hashlib
import hmac
import json
import math
import re
import unicodedata
from typing import Annotated, Any, Dict, List, Literal, Mapping, Optional
import uuid

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
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
ReportV2ModelKey = Literal[DetectV2ModelKey, "walkmate_21cls"]
DetectV2DistanceSource = Literal["sensor_depth", "manual_fixture", "model_estimate", "unknown"]
DetectV2ApproachState = Literal["approaching", "stable", "receding", "unknown"]
ReportV2Trigger = Literal["auto", "on_screen", "voice"]
ReportCoordinateGateStatus = Literal["pass", "pending", "failed", "gps_missing"]
ReportReviewFlag = Annotated[str, StringConstraints(min_length=1, max_length=64, pattern=r"^[a-z0-9_:-]+$")]
ReportOriginalAccessPurpose = Literal["report_review", "security_incident", "data_subject_request"]
ReportReviewDecisionValue = Literal["APPROVED", "REJECTED", "DUPLICATE"]
ReportUserStatus = Literal["RECEIVED", "INSTITUTION_SUBMITTED", "REJECTED", "RESOLVED"]
ReportUserRequestType = Literal["CORRECTION", "DELETE"]
ReportUserRequestStatus = Literal["RECEIVED", "ACKNOWLEDGED", "RESOLVED", "REJECTED"]
ReportDeletionState = Literal["PENDING", "LEGAL_HOLD", "REJECTED", "DELETED"]
ReportExternalCopyDeletionRecordedState = Literal[
    "REQUEST_SENT",
    "REPLY_ACKNOWLEDGED",
    "REPLY_DELETION_CONFIRMED",
    "REPLY_DECLINED",
]
ReportExternalCopyDeletionState = Literal[
    "NOT_REQUESTED",
    "REQUEST_SENT",
    "REPLY_ACKNOWLEDGED",
    "REPLY_DELETION_CONFIRMED",
    "REPLY_DECLINED",
]
ReportContentCategoryHint = Literal[
    "SIDEWALK_OBSTRUCTION",
    "ROAD_DAMAGE",
    "ACCESSIBILITY_BARRIER",
    "OTHER",
]
ReportInstitutionDeliveryStatus = Literal["SUBMITTED", "ACKNOWLEDGED", "RESOLVED", "FAILED"]
AdminAuditEventType = Literal["SECURITY", "READ", "STATUS", "REVIEW", "EXPORT", "DELIVERY"]
AdminAuditOutcome = Literal["SUCCEEDED", "DENIED", "ERROR"]
CriticalIncidentSeverity = Literal["CRITICAL"]
CriticalIncidentStatus = Literal["OPEN", "ACKNOWLEDGED", "RESOLVED", "REOPENED"]
CriticalIncidentMutationState = Literal["ACKNOWLEDGED", "RESOLVED", "REOPENED"]
CriticalIncidentEventType = Literal["OPENED", "ACKNOWLEDGED", "RESOLVED", "REOPENED"]
CriticalIncidentReasonCode = Literal[
    "USER_SAFETY_RISK",
    "PERSONAL_DATA_BREACH",
    "DELETION_INTEGRITY_FAILURE",
    "CORE_SERVICE_TOTAL_OUTAGE",
    "IRREVERSIBLE_DATA_LOSS",
]
ReportReviewReason = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=500)]
ReportUserVisibleReason = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=500)]
ReportUserRequestText = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=500)]
ReportUserRequestResponse = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=500)]
ReportInstitution = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=160)]
ReportDeliveryChannel = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=32)]
ReportDeliveryRecipient = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=255)]
ReportExternalReceiptId = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=160)]


def _reject_disallowed_admin_text_controls(value: object) -> object:
    if isinstance(value, str) and any(
        (ord(character) < 0x20 and character not in "\t\n\r")
        or ord(character) == 0x7F
        for character in value
    ):
        raise ValueError("administrator workflow text contains a disallowed control character")
    return value


ReportInstitutionReference = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=160,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:/-]{0,159}$",
    ),
]
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


AndroidDebugModelName = Annotated[str, StringConstraints(min_length=1, max_length=80)]


class AndroidDebugFrameCaptureDetection(BaseModel):
    model_config = ConfigDict(extra="forbid")

    class_name: str = Field(min_length=1, max_length=80)
    confidence: float = Field(ge=0, le=1)
    bbox: AndroidDebugRect


class AndroidDebugFrameCaptureEntry(BaseModel):
    """Allow-listed detector fields of one explicit debug capture; no location, text or tokens."""

    model_config = ConfigDict(extra="forbid")

    frame_timestamp_ms: int = Field(ge=0)
    camera_image_width: Optional[int] = Field(default=None, gt=0, le=10000)
    camera_image_height: Optional[int] = Field(default=None, gt=0, le=10000)
    # Wall-clock difference on the device; a clock adjustment can make it negative.
    detect_duration_ms: Optional[int] = None
    # The detector parser accepts up to 300 detections per frame (max_detections=300).
    detection_count: int = Field(ge=0, le=300)
    capture_policy: Optional[str] = Field(default=None, max_length=80)
    force_custom_tactile: Optional[bool] = None
    detector_completed_models: List[AndroidDebugModelName] = Field(default_factory=list, max_length=4)
    detector_skipped_models: List[AndroidDebugModelName] = Field(default_factory=list, max_length=4)
    detector_partial: bool = False
    detector_model_key: Optional[str] = Field(default=None, max_length=80)
    detector_loaded_model_key: Optional[str] = Field(default=None, max_length=80)
    detector_model_fallback_used: Optional[bool] = None
    detector_model_load_reason: Optional[str] = Field(default=None, max_length=160)
    detector_model_preprocess_ms: Optional[int] = Field(default=None, ge=0)
    detector_model_inference_ms: Optional[int] = Field(default=None, ge=0)
    detector_model_parse_ms: Optional[int] = Field(default=None, ge=0)
    detector_custom_inference_ms: Optional[int] = Field(default=None, ge=0)
    detector_coco_inference_ms: Optional[int] = Field(default=None, ge=0)
    detections: List[AndroidDebugFrameCaptureDetection] = Field(default_factory=list, max_length=20)
    top_detection_class_name: Optional[str] = Field(default=None, max_length=80)
    top_detection_confidence: Optional[float] = Field(default=None, ge=0, le=1)
    top_detection_bbox: Optional[AndroidDebugRect] = None
    top_detection_threshold_used: Optional[float] = Field(default=None, ge=0, le=1)


class AndroidDebugFrameCaptureMetadata(BaseModel):
    """Versioned envelope written by the debug-only Android frame capture uploader."""

    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["android.frame_capture.v1"]
    session_id: str = Field(min_length=1, max_length=128)
    device_model: Optional[str] = Field(default=None, max_length=128)
    android_version: Optional[str] = Field(default=None, max_length=64)
    app_version_name: Optional[str] = Field(default=None, max_length=64)
    entry: AndroidDebugFrameCaptureEntry


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


DestinationCoordinateKind = Literal["noor", "front", "lat", "new"]


class DestinationCoordinateCandidate(BaseModel):
    # Provider-labelled TMAP coordinate family (noorLat/Lon, frontLat/Lon, lat/lon,
    # newLat/Lon). A label only: no family is verified as a walkable entrance,
    # an arrival point, a building side or a floor.
    kind: DestinationCoordinateKind
    point: RoutePoint


class DestinationSearchResult(BaseModel):
    id: str
    name: str
    point: RoutePoint
    # Family that produced `point` (existing noor > front > lat > new priority);
    # null when the result is not derived from TMAP POI coordinates.
    coordinate_kind: Optional[DestinationCoordinateKind] = None
    # Every complete, valid provider coordinate family in priority order; the first
    # entry is `point`. Additive provenance only: route requests still use `point`.
    coordinate_candidates: List[DestinationCoordinateCandidate] = Field(default_factory=list, max_length=4)
    address: Optional[str] = None
    road_address: Optional[str] = None
    category: Optional[str] = None
    result_type: Literal["poi", "address", "alias"] = "poi"
    distance_m: Optional[int] = Field(default=None, ge=0)
    # Measured from the search origin, never along a walking route: the provider's
    # reported radius or a straight-line fallback. Null exactly when distance_m is null.
    distance_source: Optional[Literal["tmap_radius", "straight_line"]] = None


class DestinationSearchResponse(BaseModel):
    schema_version: Literal["walksafe.destination_search.v1"]
    provider: DestinationSearchProvider
    query: str
    results: List[DestinationSearchResult]
    # Provider-wide total before normalization; null when the provider did not report it.
    total_count: Optional[int] = Field(default=None, ge=0)
    # Provider paging progress, not the normalized unique count; null when unknown.
    more_results_available: Optional[bool] = None


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
    turn_type: Optional[int] = Field(default=None, ge=0, le=2_147_483_647, strict=True)
    facility_type: Optional[int] = Field(default=None, ge=0, le=2_147_483_647, strict=True)


class WalkingRouteGuidePoint(BaseModel):
    index: int = Field(ge=0)
    point: RoutePoint
    instruction: Optional[str] = None
    turn_type: Optional[int] = Field(default=None, ge=0, le=2_147_483_647, strict=True)
    point_type: Optional[str] = None
    facility_type: Optional[int] = Field(default=None, ge=0, le=2_147_483_647, strict=True)
    distance_from_start_m: Optional[int] = Field(default=None, ge=0)
    remaining_distance_m: Optional[int] = Field(default=None, ge=0)
    bearing_deg: Optional[float] = Field(default=None, ge=0, lt=360)


# Local navigation policy until representative TMAP turns can be measured:
# require an instruction at a step boundary only for an obvious manoeuvre.
# Short segments and small bends are common geometry/segmentation artefacts.
_TURN_MIN_LEG_M = 10.0
_TURN_MIN_EDGE_M = 1.0
_TURN_MIN_ANGLE_DEG = 60.0
_TURN_STRAIGHT_RUN_MAX_BEND_DEG = 60.0
ROUTE_TURN_GUIDE_PROGRESS_TOLERANCE_M = 5.0
ROUTE_TURN_GUIDE_CORNER_TOLERANCE_M = 5.0
_SPOKEN_TURN_TYPES = frozenset({12, 13, 14, 16, 17, 18, 19})


def route_turn_guide_speaks(guide: WalkingRouteGuidePoint) -> bool:
    # Only an Android-mapped directional code proves a spoken corner cue.
    # Provider prose cannot authorize movement for absent/unmapped codes.
    return guide.turn_type in _SPOKEN_TURN_TYPES


def route_required_turn_boundaries(
    steps: List[WalkingRouteStep],
) -> list[tuple[int, float, RoutePoint]]:
    """Return (outgoing step, route fraction, corner) for clear step turns."""
    step_lengths: list[float] = []
    first_edges: list[tuple[float, float] | None] = []
    last_edges: list[tuple[float, float] | None] = []
    for step in steps:
        length = 0.0
        first = last = None
        for start, end in zip(step.points, step.points[1:]):
            latitude = math.radians((start.latitude + end.latitude) / 2)
            east = math.radians(end.longitude - start.longitude) * 6_371_000.0 * math.cos(latitude)
            north = math.radians(end.latitude - start.latitude) * 6_371_000.0
            edge_m = math.hypot(east, north)
            length += edge_m
            if edge_m >= _TURN_MIN_EDGE_M:
                if first is None:
                    first = (east, north)
                last = (east, north)
        step_lengths.append(length)
        first_edges.append(first)
        last_edges.append(last)

    total_m = sum(step_lengths)
    if total_m <= 0:
        return []
    boundaries = []
    progress_m = 0.0
    for outgoing_index in range(1, len(steps)):
        incoming_index = outgoing_index - 1
        progress_m += step_lengths[incoming_index]
        incoming, outgoing = last_edges[incoming_index], first_edges[outgoing_index]
        if (
            step_lengths[incoming_index] < _TURN_MIN_LEG_M
            or step_lengths[outgoing_index] < _TURN_MIN_LEG_M
            or incoming is None or outgoing is None
        ):
            continue
        cosine = (incoming[0] * outgoing[0] + incoming[1] * outgoing[1]) / (
            math.hypot(*incoming) * math.hypot(*outgoing)
        )
        if cosine <= math.cos(math.radians(_TURN_MIN_ANGLE_DEG)):
            boundaries.append((outgoing_index, progress_m / total_m, steps[incoming_index].points[-1]))
    return boundaries


def route_required_geometry_turns(polyline: List[RoutePoint]) -> list[tuple[float, RoutePoint]]:
    """Find clear turns inside a LineString as well as across feature seams."""
    points: list[RoutePoint] = []
    for point in polyline:
        if not points or (point.latitude, point.longitude) != (points[-1].latitude, points[-1].longitude):
            points.append(point)
    raw_edges: list[tuple[int, float, float, float]] = []
    prefixes = [0.0]
    for index, (start, end) in enumerate(zip(points, points[1:])):
        latitude = math.radians((start.latitude + end.latitude) / 2)
        east = math.radians(end.longitude - start.longitude) * 6_371_000.0 * math.cos(latitude)
        north = math.radians(end.latitude - start.latitude) * 6_371_000.0
        length = math.hypot(east, north)
        prefixes.append(prefixes[-1] + length)
        if length > 0:
            raw_edges.append((index, east, north, length))
    total_m = prefixes[-1]
    if total_m <= 0:
        return []

    def cosine_between(first: tuple[int, float, float, float], second: tuple[int, float, float, float]) -> float:
        return (first[1] * second[1] + first[2] * second[2]) / (first[3] * second[3])

    turns: list[tuple[float, RoutePoint]] = []
    turn_cosine = math.cos(math.radians(_TURN_MIN_ANGLE_DEG))
    run_cosine = math.cos(math.radians(_TURN_STRAIGHT_RUN_MAX_BEND_DEG))
    # The raw pass catches a 12 m straight leg encoded as twenty 0.6 m
    # segments. The material-edge pass ignores an isolated sub-metre spur
    # that would otherwise split an obvious turn into two 45-degree bends.
    for edges in (raw_edges, [edge for edge in raw_edges if edge[3] >= _TURN_MIN_EDGE_M]):
        runs: list[tuple[int, int, float, float, float]] = []
        previous_edge = None
        for edge in edges:
            if runs and previous_edge is not None and cosine_between(previous_edge, edge) > run_cosine:
                start_index, _, length, east, north = runs[-1]
                runs[-1] = (start_index, edge[0], length + edge[3], east + edge[1], north + edge[2])
            else:
                runs.append((edge[0], edge[0], edge[3], edge[1], edge[2]))
            previous_edge = edge
        material = [
            index for index, run in enumerate(runs)
            if run[2] >= _TURN_MIN_EDGE_M and math.hypot(run[3], run[4]) >= _TURN_MIN_EDGE_M
        ]
        for before_index, after_index in zip(material, material[1:]):
            before, after = runs[before_index], runs[after_index]
            skipped_m = sum(run[2] for run in runs[before_index + 1:after_index])
            if skipped_m > _TURN_MIN_EDGE_M or before[2] < _TURN_MIN_LEG_M or after[2] < _TURN_MIN_LEG_M:
                continue
            cosine = (before[3] * after[3] + before[4] * after[4]) / (
                math.hypot(before[3], before[4]) * math.hypot(after[3], after[4])
            )
            if cosine > turn_cosine:
                continue
            corner_index = before[1] + 1
            progress = prefixes[corner_index]
            if not any(abs(progress - fraction * total_m) <= 0.01 for fraction, _ in turns):
                turns.append((progress / total_m, points[corner_index]))
    return turns


class WalkingRouteResponse(BaseModel):
    schema_version: Literal["walksafe.walking_route.v1"]
    provider: WalkingRouteProvider
    provider_route_id: Optional[str] = None
    distance_source: Optional[Literal[
        "provider_total_geometry_scaled", "provider_step_sum_geometry_scaled",
    ]] = None
    priority: WalkingRoutePriority
    summary: WalkingRouteSummary
    polyline: List[RoutePoint]
    steps: List[WalkingRouteStep]
    guide_points: List[WalkingRouteGuidePoint] = Field(default_factory=list)
    # S20-27: straight-line metres between the requested origin/destination and the first/last
    # polyline point. The route ends at polyline[-1]; a client must keep the final approach to the
    # destination separate from route progress. Null when a provider did not compute them.
    start_gap_m: Optional[int] = Field(default=None, ge=0)
    end_gap_m: Optional[int] = Field(default=None, ge=0)
    provider_result_code: int
    provider_result_message: str

    @model_validator(mode="after")
    def normalized_parts_form_one_route(self) -> "WalkingRouteResponse":
        # Older responses can omit steps and distance provenance. Their distance
        # totals were not guaranteed, but any steps they do carry must still form
        # the displayed route in the stated order.
        if not self.steps and self.distance_source is not None:
            raise ValueError("route_steps_missing")

        def coordinates(point: RoutePoint) -> tuple[float, float]:
            return point.latitude, point.longitude

        def without_adjacent_duplicates(points: List[RoutePoint]) -> list[tuple[float, float]]:
            result: list[tuple[float, float]] = []
            for point in points:
                coordinate = coordinates(point)
                if not result or result[-1] != coordinate:
                    result.append(coordinate)
            return result

        route_shape = without_adjacent_duplicates(self.polyline)
        if self.steps:
            assembled: list[tuple[float, float]] = []
            previous_end: tuple[float, float] | None = None
            for expected_index, step in enumerate(self.steps):
                if step.index != expected_index or not step.points:
                    raise ValueError("route_step_order_invalid")
                first = coordinates(step.points[0])
                if previous_end is not None and previous_end != first:
                    raise ValueError("route_step_geometry_disconnected")
                for coordinate in without_adjacent_duplicates(step.points):
                    if not assembled or assembled[-1] != coordinate:
                        assembled.append(coordinate)
                previous_end = coordinates(step.points[-1])
            if assembled != route_shape:
                raise ValueError("route_step_geometry_mismatch")

        if self.distance_source is not None and (
            self.summary.distance_m <= 0
            or sum(step.distance_m for step in self.steps) != self.summary.distance_m
        ):
            raise ValueError("route_step_distance_total_invalid")

        if not self.guide_points:
            raise ValueError("route_guidance_incomplete")

        # Guides are checked even on older responses with no distance source or
        # no steps. Their physical visit must be unambiguous when progress is absent.
        route_points = [RoutePoint(latitude=lat, longitude=lon) for lat, lon in route_shape]
        if len(route_points) < 2:
            raise ValueError("route_geometry_missing")

        def distance_m(start: RoutePoint, end: RoutePoint) -> float:
            lat1, lat2 = math.radians(start.latitude), math.radians(end.latitude)
            d_lat = lat2 - lat1
            d_lon = math.radians(end.longitude - start.longitude)
            value = math.sin(d_lat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(d_lon / 2) ** 2
            value = min(1.0, max(0.0, value))
            return 6_371_000.0 * 2 * math.atan2(math.sqrt(value), math.sqrt(1 - value))

        geometry_prefixes = [0.0]
        for start, end in zip(route_points, route_points[1:]):
            geometry_prefixes.append(geometry_prefixes[-1] + distance_m(start, end))
        geometry_total = geometry_prefixes[-1]
        if geometry_total <= 0:
            raise ValueError("route_geometry_missing")

        if self.summary.distance_m <= 0 or geometry_total <= 0:
            raise ValueError("route_geometry_missing")

        previous_projected_m = -10.0
        previous_declared_m = -10.0
        previous_remaining_m = self.summary.distance_m + 10.0
        verified_guide_progress_m: list[float] = []
        for expected_index, guide in enumerate(self.guide_points):
            progress = guide.distance_from_start_m
            remaining = guide.remaining_distance_m
            if (
                guide.index != expected_index
                or (progress is not None and progress > self.summary.distance_m)
                or (remaining is not None and remaining > self.summary.distance_m)
            ):
                raise ValueError("route_guide_progress_invalid")
            if self.distance_source is not None and (
                progress is None or remaining is None
                or progress + remaining != self.summary.distance_m
            ):
                raise ValueError("route_guide_progress_invalid")
            if progress is not None and remaining is not None and (
                abs(progress + remaining - self.summary.distance_m) > 30.0
            ):
                raise ValueError("route_guide_progress_invalid")
            declared_m = progress if progress is not None else (
                self.summary.distance_m - remaining if remaining is not None else None
            )

            # Keep every spatial candidate so a declaration cannot select a
            # much farther visit of the same coordinate on a returning route.
            candidates: list[tuple[float, float, int, float, RoutePoint]] = []
            for segment_index in range(len(route_points) - 1):
                start, end = route_points[segment_index], route_points[segment_index + 1]
                longitude_scale = max(0.01, math.cos(math.radians(
                    (start.latitude + end.latitude + guide.point.latitude) / 3,
                )))
                dx = (end.longitude - start.longitude) * longitude_scale
                dy = end.latitude - start.latitude
                px = (guide.point.longitude - start.longitude) * longitude_scale
                py = guide.point.latitude - start.latitude
                denominator = dx * dx + dy * dy
                if denominator <= 0.0:
                    continue
                fraction = max(0.0, min(1.0, (px * dx + py * dy) / denominator))
                projected = RoutePoint(
                    latitude=start.latitude + (end.latitude - start.latitude) * fraction,
                    longitude=start.longitude + (end.longitude - start.longitude) * fraction,
                )
                projected_progress = geometry_prefixes[segment_index] + (
                    geometry_prefixes[segment_index + 1] - geometry_prefixes[segment_index]
                ) * fraction
                gap_m = distance_m(guide.point, projected)
                if gap_m <= 30.0:
                    candidates.append((gap_m, projected_progress, segment_index, fraction, projected))
            if not candidates:
                raise ValueError("route_guide_step_mismatch")

            nearest_m = min(candidate[0] for candidate in candidates)
            # 3 m retains the existing valid 2.2 m offset on a declared repeat
            # visit. Without progress, only effectively shortest candidates can
            # establish the visit; two separate visits fail closed.
            near_m = 3.0 if declared_m is not None else 1.0
            nearest_candidates = [
                candidate for candidate in candidates if candidate[0] <= nearest_m + near_m
            ]
            if declared_m is None:
                ordered = sorted(nearest_candidates, key=lambda candidate: candidate[2])
                previous_candidate: tuple[float, float, int, float, RoutePoint] | None = None
                for candidate in ordered:
                    if previous_candidate is not None:
                        same_position = abs(candidate[1] - previous_candidate[1]) <= 0.01
                        same_corner_visit = (
                            candidate[2] == previous_candidate[2] + 1
                            and previous_candidate[3] >= 0.5 and candidate[3] <= 0.5
                            and abs(candidate[1] - previous_candidate[1]) <= (
                                2 * distance_m(previous_candidate[4], candidate[4]) + 0.01
                            )
                        )
                        if not (same_position or same_corner_visit):
                            raise ValueError("route_guide_visit_ambiguous")
                    previous_candidate = candidate
                selected = min(ordered, key=lambda candidate: candidate[0])
            else:
                matching = [
                    candidate for candidate in nearest_candidates
                    if abs(candidate[1] * self.summary.distance_m / geometry_total - declared_m) <= 30.0
                ]
                if not matching:
                    raise ValueError("route_guide_step_mismatch")
                selected = min(matching, key=lambda candidate: (
                    abs(candidate[1] * self.summary.distance_m / geometry_total - declared_m),
                    candidate[0],
                ))

            projected_m = selected[1] * self.summary.distance_m / geometry_total
            verified_guide_progress_m.append(selected[1])
            actual_declared_m = declared_m if declared_m is not None else projected_m
            if (
                projected_m + 10.0 < previous_projected_m
                or actual_declared_m + 10.0 < previous_declared_m
                or (remaining is not None and remaining > previous_remaining_m + 10.0)
            ):
                raise ValueError("route_guide_order_invalid")
            previous_projected_m = projected_m
            previous_declared_m = actual_declared_m
            if remaining is not None:
                previous_remaining_m = remaining
        required_turns = [
            (fraction, corner) for _, fraction, corner in route_required_turn_boundaries(self.steps)
        ]
        required_turns.extend(route_required_geometry_turns(route_points))
        for fraction, corner in required_turns:
            corner_progress_m = fraction * geometry_total
            if not any(
                abs(progress_m - corner_progress_m) <= ROUTE_TURN_GUIDE_PROGRESS_TOLERANCE_M
                and distance_m(guide.point, corner) <= ROUTE_TURN_GUIDE_CORNER_TOLERANCE_M
                and route_turn_guide_speaks(guide)
                for guide, progress_m in zip(self.guide_points, verified_guide_progress_m)
            ):
                raise ValueError("route_guidance_incomplete")
        return self


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
    model_config = ConfigDict(extra="forbid")

    status: ReportStatus
    note: Optional[str] = Field(default=None, max_length=500)
    resolution_reason: Optional[str] = Field(default=None, max_length=500)
    expected_updated_at: Optional[AwareDatetime] = None


class ReportTransportReceiptV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    marker: Literal["DATABASE_AND_ENCRYPTED_IMAGE_STORE"]
    report_id: uuid.UUID
    persistence_marker: uuid.UUID
    payload_sha256: Sha256LowerHex
    payload_bytes: int = Field(gt=0)


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
    transport_receipt: Optional[ReportTransportReceiptV1] = None


ReportCreationOutcome = Literal["CREATED", "REPLAYED", "SUPPRESSED_BY_COOLDOWN"]


class ReportCreationResponse(ReportResponse):
    creation_outcome: ReportCreationOutcome


class ReportTransportStatusV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    persistence_state: Literal["PERSISTED"]
    user_status: Literal["RECEIVED", "IN_REVIEW", "COMPLETED"]
    transport_receipt: ReportTransportReceiptV1


class ReportUserRequestCreateV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    client_request_id: uuid.UUID
    request_type: ReportUserRequestType
    request_text: ReportUserRequestText

    @field_validator("client_request_id", mode="before")
    @classmethod
    def require_canonical_client_request_id(cls, value: object) -> object:
        if isinstance(value, uuid.UUID):
            return value
        if not isinstance(value, str):
            raise ValueError("client_request_id must be a canonical UUID")
        try:
            parsed = uuid.UUID(value)
        except ValueError as exc:
            raise ValueError("client_request_id must be a canonical UUID") from exc
        if str(parsed) != value:
            raise ValueError("client_request_id must be a canonical UUID")
        return parsed


class ReportContentCorrectionRequestV1(BaseModel):
    """Structured patch; an explicit null clears a field and omission preserves it."""

    model_config = ConfigDict(extra="forbid")

    expected_revision: int = Field(ge=0, strict=True)
    idempotency_key: uuid.UUID
    user_description: str | None = None
    category_hint: ReportContentCategoryHint | None = None

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

    @field_validator("user_description", mode="before")
    @classmethod
    def canonicalize_user_description(cls, value: object) -> object:
        if value is None:
            return None
        if not isinstance(value, str):
            raise ValueError("user_description must be text or null")
        normalized = unicodedata.normalize("NFC", value)
        if any(unicodedata.category(char).startswith("C") for char in normalized):
            raise ValueError("user_description cannot contain control characters")
        normalized = " ".join(normalized.split())
        if not 1 <= len(normalized) <= 500:
            raise ValueError("user_description must contain 1 to 500 characters")
        return normalized

    @model_validator(mode="after")
    def require_structured_patch(self) -> "ReportContentCorrectionRequestV1":
        if not self.model_fields_set.intersection(
            {"user_description", "category_hint"}
        ):
            raise ValueError("at least one correction field is required")
        return self


class ReportContentCurrentV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.report-content-current.v1"]
    report_id: uuid.UUID
    revision: int = Field(ge=0)
    content_sha256: Sha256LowerHex
    user_description: str | None = Field(default=None, min_length=1, max_length=500)
    category_hint: ReportContentCategoryHint | None
    corrected_at: AwareDatetime | None


class ReportContentRevisionV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.report-content-revision.v1"]
    report_id: uuid.UUID
    revision: int = Field(ge=1)
    expected_revision: int = Field(ge=0)
    idempotency_key: uuid.UUID
    content_sha256: Sha256LowerHex
    user_description: str | None = Field(default=None, min_length=1, max_length=500)
    category_hint: ReportContentCategoryHint | None
    corrected_at: AwareDatetime


class ReportUserRequestSummaryV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: uuid.UUID
    request_type: ReportUserRequestType
    status: ReportUserRequestStatus
    status_version: int = Field(ge=1)
    public_response: str | None = Field(default=None, min_length=1, max_length=500)
    created_at: AwareDatetime
    updated_at: AwareDatetime


class ReportDeletionExternalCopyStatusV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    institution: ReportInstitution
    state: ReportExternalCopyDeletionState
    status_recorded_at: AwareDatetime | None


class ReportDeletionStatusV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.report-deletion-status.v2"]
    request_id: uuid.UUID
    report_id: uuid.UUID
    state: ReportDeletionState
    request_status_version: int = Field(ge=1)
    external_copy_count: int = Field(ge=0)
    external_copies: List[ReportDeletionExternalCopyStatusV2]
    updated_at: AwareDatetime

    @model_validator(mode="after")
    def require_complete_external_copy_projection(self) -> "ReportDeletionStatusV2":
        if self.external_copy_count != len(self.external_copies):
            raise ValueError("external_copy_count must match external_copies")
        if self.state != "DELETED" and self.external_copies:
            raise ValueError("external copy snapshots require local deletion")
        return self


class UserReportRequestHistoryItemV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    revision: int = Field(ge=1, le=9_007_199_254_740_991)
    source: Literal["ACTIVE_REQUEST", "DELETION_TOMBSTONE"]
    report_id: uuid.UUID
    request_id: uuid.UUID
    request: ReportUserRequestSummaryV1 | None
    deletion_status: ReportDeletionStatusV2 | None

    @model_validator(mode="after")
    def validate_source_projection(self) -> "UserReportRequestHistoryItemV1":
        if self.source == "ACTIVE_REQUEST":
            if self.request is None or self.request.request_id != self.request_id:
                raise ValueError("active request history requires its request projection")
            if (self.request.request_type == "DELETE") != (
                self.deletion_status is not None
            ):
                raise ValueError("only deletion requests require deletion status")
            if (
                self.deletion_status is not None
                and self.request.status_version
                != self.deletion_status.request_status_version
            ):
                raise ValueError("deletion request projections must share one version")
            if (
                self.deletion_status is not None
                and self.deletion_status.state == "DELETED"
            ):
                raise ValueError("completed deletion must use its tombstone source")
        elif self.request is not None:
            raise ValueError("deletion tombstones cannot synthesize request status")
        if self.deletion_status is not None and (
            self.deletion_status.request_id != self.request_id
            or self.deletion_status.report_id != self.report_id
            or (
                self.source == "DELETION_TOMBSTONE"
                and self.deletion_status.state != "DELETED"
            )
        ):
            raise ValueError("deletion status does not match request history")
        if self.source == "DELETION_TOMBSTONE" and self.deletion_status is None:
            raise ValueError("deletion tombstones require durable deletion status")
        return self


class UserReportRequestHistoryPageV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.user-report-request-history-page.v1"]
    report_id: uuid.UUID | None
    snapshot_revision: int = Field(ge=0, le=9_007_199_254_740_991)
    total_count: int = Field(ge=0, le=9_007_199_254_740_991)
    items: List[UserReportRequestHistoryItemV1] = Field(max_length=25)
    next_cursor: str | None = Field(default=None, min_length=1, max_length=1024)

    @model_validator(mode="after")
    def validate_snapshot_page(self) -> "UserReportRequestHistoryPageV1":
        revisions = [item.revision for item in self.items]
        if revisions and (
            revisions != sorted(set(revisions), reverse=True)
            or revisions[0] > self.snapshot_revision
            or (
                self.report_id is not None
                and any(item.report_id != self.report_id for item in self.items)
            )
        ):
            raise ValueError("request history page is not strictly newest-first")
        if (self.total_count == 0) != (self.snapshot_revision == 0):
            raise ValueError("request history empty snapshot is inconsistent")
        return self


class UserReportSummaryV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    report_id: uuid.UUID
    created_at: AwareDatetime
    user_status: ReportUserStatus
    public_rejection_reason: str | None = Field(
        default=None, min_length=1, max_length=500
    )
    latest_request: ReportUserRequestSummaryV1 | None


class UserReportListPageV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.user-report-list.v1"]
    items: List[UserReportSummaryV1] = Field(max_length=100)
    next_cursor: str | None = Field(default=None, min_length=1, max_length=1024)


class UserReportDetailV1(UserReportSummaryV1):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.user-report-detail.v1"]


class AdminReportUserRequestSummaryV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: uuid.UUID
    report_id: uuid.UUID
    request_type: ReportUserRequestType
    status: ReportUserRequestStatus
    status_version: int = Field(ge=1)
    created_at: AwareDatetime
    updated_at: AwareDatetime


class AdminReportUserRequestListPageV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.admin-report-request-list.v1"]
    items: List[AdminReportUserRequestSummaryV1] = Field(max_length=100)
    next_cursor: str | None = Field(default=None, min_length=1, max_length=1024)


class AdminReportUserRequestDetailV1(AdminReportUserRequestSummaryV1):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.admin-report-request-detail.v1"]
    request_text: ReportUserRequestText
    public_response: str | None = Field(default=None, min_length=1, max_length=500)
    internal_note: str | None = Field(default=None, min_length=1, max_length=500)


class AdminReportUserRequestStatusUpdateV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: ReportUserRequestStatus
    expected_version: int = Field(ge=1, strict=True)
    public_response: ReportUserRequestResponse | None = None
    internal_note: ReportUserRequestResponse | None = None


class AdminReportUserRequestStatusV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.admin-report-request-status.v1"]
    request_id: uuid.UUID
    report_id: uuid.UUID
    status: ReportUserRequestStatus
    status_version: int = Field(ge=1)
    allowed_next_statuses: List[ReportUserRequestStatus] = Field(max_length=2)
    public_response: str | None = Field(default=None, min_length=1, max_length=500)
    updated_at: AwareDatetime


class AdminReportDeletionExternalCopyEventCreateV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    state: ReportExternalCopyDeletionRecordedState
    expected_revision: int = Field(ge=0, strict=True)
    idempotency_key: uuid.UUID
    observed_at: AwareDatetime = Field(
        json_schema_extra={"pattern": _RFC3339_UTC_PATTERN.pattern}
    )
    institution_reference: ReportInstitutionReference | None = None
    evidence_sha256: Sha256LowerHex | None = None

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
    def require_reply_evidence(self) -> "AdminReportDeletionExternalCopyEventCreateV1":
        if self.state.startswith("REPLY_") and (
            self.institution_reference is None and self.evidence_sha256 is None
        ):
            raise ValueError("institution replies require a reference or evidence digest")
        if (
            self.state == "REPLY_DELETION_CONFIRMED"
            and self.evidence_sha256 is None
        ):
            raise ValueError("deletion confirmation replies require evidence_sha256")
        return self


class AdminReportDeletionExternalCopyItemV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    request_id: uuid.UUID
    copy_id: uuid.UUID
    institution: ReportInstitution
    delivery_status_at_local_deletion: ReportInstitutionDeliveryStatus
    state: ReportExternalCopyDeletionState
    revision: int = Field(ge=0)
    allowed_next_states: List[ReportExternalCopyDeletionRecordedState] = Field(
        max_length=3
    )
    status_observed_at: AwareDatetime | None
    status_recorded_at: AwareDatetime | None


class AdminReportDeletionExternalCopyListPageV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal[
        "walksafe.admin-report-deletion-external-copy-list.v1"
    ]
    items: List[AdminReportDeletionExternalCopyItemV1] = Field(max_length=100)
    next_cursor: str | None = Field(default=None, min_length=1, max_length=1024)


class AdminReportDeletionExternalCopyEventV1(
    AdminReportDeletionExternalCopyItemV1
):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal[
        "walksafe.admin-report-deletion-external-copy-event.v1"
    ]


class AdminReportSummaryV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    id: uuid.UUID
    status: ReportStatus
    status_version: int = Field(default=1, ge=1)
    class_name: ClassName
    confidence: float = Field(ge=0, le=1, allow_inf_nan=False)
    location_quality: LocationQuality
    duplicate_count: int = Field(ge=0)
    captured_at: AwareDatetime
    created_at: AwareDatetime
    updated_at: AwareDatetime


class AdminReportListPageV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.admin-report-list.v1"]
    items: List[AdminReportSummaryV1] = Field(max_length=100)
    next_cursor: Optional[str] = Field(default=None, min_length=1, max_length=1024)


class AdminReportReviewSummaryV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    revision: int = Field(ge=1)
    decision: ReportReviewDecisionValue
    user_visible_reason: str | None = Field(default=None, min_length=1, max_length=500)
    duplicate_of_report_id: uuid.UUID | None
    location_reviewed: bool
    photo_reviewed: bool
    privacy_reviewed: bool
    decided_at: AwareDatetime


class AdminReportReviewSummaryV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    revision: int = Field(ge=1)
    decision: ReportReviewDecisionValue
    user_visible_reason: str | None = Field(min_length=1, max_length=500)
    duplicate_of_report_id: uuid.UUID | None
    location_reviewed: bool
    photo_reviewed: bool
    privacy_reviewed: bool
    decided_at: AwareDatetime


class AdminReportDeliverySummaryV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    revision: int = Field(ge=1)
    package_id: uuid.UUID
    package_revision: int = Field(ge=1)
    package_content_revision: int = Field(ge=0)
    package_schema_version: str = Field(min_length=1, max_length=64)
    package_version: int = Field(ge=1)
    export_audit_id: uuid.UUID
    package_sha256: Sha256LowerHex
    csv_sha256: Sha256LowerHex
    manifest_sha256: Sha256LowerHex
    package_byte_count: int = Field(gt=0)
    status: ReportInstitutionDeliveryStatus
    external_receipt_present: bool
    evidence_present: bool
    observed_at: AwareDatetime
    recorded_at: AwareDatetime


class AdminReportCapabilitiesV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    review_decisions_path: str = Field(min_length=1, max_length=512)
    deliveries_path: str = Field(min_length=1, max_length=512)
    original_access_grants_path: str = Field(min_length=1, max_length=512)
    status_path: str | None = Field(default=None, min_length=1, max_length=512)
    delivery_packages_path: str | None = Field(default=None, min_length=1, max_length=512)


class AdminReportCapabilitiesV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    review_decisions_path: str = Field(min_length=1, max_length=512)
    deliveries_path: str = Field(min_length=1, max_length=512)
    original_access_grants_path: str = Field(min_length=1, max_length=512)
    status_path: str = Field(min_length=1, max_length=512)
    delivery_packages_path: str = Field(min_length=1, max_length=512)


class AdminReportDetailV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.admin-report-detail.v2"]
    id: uuid.UUID
    status: ReportStatus
    status_version: int = Field(ge=1)
    content_revision: int = Field(ge=0)
    latest_delivery_revision: int = Field(ge=0)
    # Report-wide latest review revision across every content revision
    # (0 = no decision). current_review is scoped to the current content, so
    # it is null after a content correction; the next review decision binds
    # expected_review_revision to this value (W20A-R1, S18-35).
    latest_review_revision: int = Field(ge=0)
    allowed_next_statuses: List[ReportStatus] = Field(max_length=2)
    class_name: ClassName
    confidence: float = Field(ge=0, le=1, allow_inf_nan=False)
    location_quality: LocationQuality
    captured_at: AwareDatetime
    created_at: AwareDatetime
    updated_at: AwareDatetime
    current_review: AdminReportReviewSummaryV2 | None
    current_delivery: AdminReportDeliverySummaryV1 | None
    capabilities: AdminReportCapabilitiesV2

    @model_validator(mode="after")
    def latest_review_revision_covers_current_review(self) -> "AdminReportDetailV2":
        if (
            self.current_review is not None
            and self.latest_review_revision < self.current_review.revision
        ):
            raise ValueError(
                "latest_review_revision must not be older than current_review"
            )
        return self


class AdminReportStatusUpdateV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    status: ReportStatus
    expected_version: int = Field(ge=1, strict=True)
    note: Optional[str] = Field(default=None, max_length=500)
    resolution_reason: Optional[str] = Field(default=None, max_length=500)


class AdminReportStatusV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.admin-report-status.v1"]
    id: uuid.UUID
    status: ReportStatus
    status_version: int = Field(ge=1)
    allowed_next_statuses: List[ReportStatus] = Field(max_length=2)
    updated_at: AwareDatetime


class AdminReportPackageCreateRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    expected_content_revision: int = Field(ge=0, strict=True)
    expected_review_revision: int = Field(ge=1, strict=True)


class AdminReportDeliveryPackageProofV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.admin-report-delivery-package-proof.v1"]
    package_revision: int = Field(ge=1)
    content_revision: int = Field(ge=0)
    review_revision: int = Field(ge=1)
    package_schema_version: Literal["walksafe.admin-report-delivery-package.v2"]
    package_byte_count: int = Field(gt=0)
    package_sha256: Sha256LowerHex


class AdminIncidentSummaryV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    incident_id: uuid.UUID
    severity: CriticalIncidentSeverity
    status: CriticalIncidentStatus
    status_version: int = Field(ge=1, le=256)
    reason_code: CriticalIncidentReasonCode
    summary: str = Field(min_length=1, max_length=200)
    started_at: AwareDatetime
    detected_at: AwareDatetime
    updated_at: AwareDatetime


class AdminIncidentListPageV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.admin-incident-list.v1"]
    items: List[AdminIncidentSummaryV1] = Field(max_length=100)
    next_cursor: str | None = Field(min_length=1, max_length=1024)


class AdminIncidentEventV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    event_id: uuid.UUID
    revision: int = Field(ge=1, le=256)
    event_type: CriticalIncidentEventType
    previous_state: CriticalIncidentStatus | None
    next_state: CriticalIncidentStatus
    reason: str = Field(min_length=8, max_length=500)
    observation: str = Field(min_length=8, max_length=500)
    evidence_sha256: Sha256LowerHex
    observed_at: AwareDatetime
    recorded_at: AwareDatetime
    actor_id: str | None = Field(
        ...,
        min_length=1,
        max_length=64,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$",
    )


class AdminIncidentHistoryPageV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.admin-incident-history-page.v1"]
    incident: AdminIncidentSummaryV1
    allowed_next_states: List[CriticalIncidentStatus] = Field(
        min_length=1,
        max_length=1,
    )
    snapshot_revision: int = Field(ge=1, le=256)
    total_count: int = Field(ge=1, le=256)
    items: List[AdminIncidentEventV1] = Field(min_length=1, max_length=25)
    next_cursor: str | None = Field(min_length=1, max_length=1024)

    @model_validator(mode="after")
    def validate_snapshot_page(self) -> "AdminIncidentHistoryPageV1":
        if (
            self.snapshot_revision != self.total_count
            or self.incident.status_version != self.snapshot_revision
        ):
            raise ValueError("incident history snapshot is inconsistent")
        revisions = [item.revision for item in self.items]
        if (
            revisions != list(range(revisions[0], revisions[0] + len(revisions)))
            or revisions[-1] > self.snapshot_revision
        ):
            raise ValueError("incident history page is not contiguous")
        return self


class AdminIncidentDetailV1(AdminIncidentSummaryV1):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.admin-incident-detail.v1"]
    allowed_next_states: List[CriticalIncidentStatus] = Field(
        min_length=1,
        max_length=1,
    )
    events: List[AdminIncidentEventV1] = Field(min_length=1, max_length=256)


class AdminIncidentStatusUpdateV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    next_state: CriticalIncidentMutationState
    expected_version: int = Field(ge=1, le=256, strict=True)
    idempotency_key: uuid.UUID
    reason: str = Field(min_length=8, max_length=500)
    observation: str = Field(min_length=8, max_length=500)
    evidence_sha256: Sha256LowerHex

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

    @field_validator("reason", "observation")
    @classmethod
    def normalize_bounded_incident_text(cls, value: str) -> str:
        normalized = value.strip()
        if not 8 <= len(normalized) <= 500 or any(
            ord(character) < 0x20 or ord(character) == 0x7F
            for character in normalized
        ):
            raise ValueError("incident text must contain 8 to 500 safe characters")
        return normalized


class AdminIncidentStatusV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.admin-incident-status.v1"]
    incident_id: uuid.UUID
    status: CriticalIncidentStatus
    status_version: int = Field(ge=1, le=256)
    allowed_next_states: List[CriticalIncidentStatus] = Field(
        min_length=1,
        max_length=1,
    )
    updated_at: AwareDatetime


class AdminReportDeliveryPackageV1(BaseModel):
    model_config = ConfigDict(from_attributes=True, extra="forbid")

    schema_version: Literal["walksafe.admin-report-delivery-package.v1"]
    package_id: uuid.UUID
    report_id: uuid.UUID
    review_decision_id: uuid.UUID
    revision: int = Field(ge=1)
    export_audit_id: uuid.UUID
    csv_sha256: Sha256LowerHex
    manifest_sha256: Sha256LowerHex
    package_sha256: Sha256LowerHex
    generated_at: AwareDatetime


class AdminAuditEventV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    event_id: str = Field(min_length=1, max_length=160)
    event_type: AdminAuditEventType
    action: str = Field(min_length=1, max_length=64)
    outcome: AdminAuditOutcome
    actor_id: str | None = Field(default=None, max_length=64)
    resource_type: str = Field(min_length=1, max_length=32)
    resource_id: str = Field(min_length=1, max_length=160)
    occurred_at: AwareDatetime
    correlation_id: uuid.UUID | None


class AdminAuditListPageV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.admin-audit-list.v1"]
    items: List[AdminAuditEventV1] = Field(max_length=100)
    next_cursor: str | None = Field(default=None, min_length=1, max_length=1024)


class ReportOriginalAccessGrantRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    purpose: ReportOriginalAccessPurpose
    reason: str = Field(min_length=8, max_length=500)
    expected_content_revision: int = Field(ge=0, strict=True)

    @field_validator("reason")
    @classmethod
    def normalize_reason(cls, value: str) -> str:
        normalized = " ".join(value.split())
        if len(normalized) < 8:
            raise ValueError("reason must contain at least 8 non-whitespace characters")
        return normalized


class ReportOriginalAccessExactLocation(BaseModel):
    model_config = ConfigDict(extra="forbid")

    lat: float = Field(ge=-90, le=90, allow_inf_nan=False)
    lon: float = Field(ge=-180, le=180, allow_inf_nan=False)
    accuracy: float | None = Field(ge=0, allow_inf_nan=False)


class ReportOriginalAccessImage(BaseModel):
    model_config = ConfigDict(extra="forbid")

    resource_path: str = Field(min_length=1, max_length=255)
    content_type: Literal["image/jpeg", "image/png", "image/webp"]
    sha256: Sha256LowerHex
    byte_count: int = Field(gt=0)
    access_token: str = Field(
        min_length=43,
        max_length=43,
        pattern=r"^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$",
    )


class ReportOriginalAccessGrantResponse(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.report-original-access-grant.v2"]
    grant_id: uuid.UUID
    content_revision: int = Field(ge=0)
    expires_at: AwareDatetime
    exact_location: ReportOriginalAccessExactLocation
    image: ReportOriginalAccessImage


class ReportReviewDecisionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")

    decision_id: uuid.UUID = Field(
        json_schema_extra={
            "pattern": (
                r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
                r"[0-9a-f]{4}-[0-9a-f]{12}$"
            )
        }
    )
    decision: ReportReviewDecisionValue
    reason: ReportReviewReason
    user_visible_reason: ReportUserVisibleReason | None = None
    duplicate_of_report_id: uuid.UUID | None
    location_reviewed: bool = Field(strict=True)
    photo_reviewed: bool = Field(strict=True)
    privacy_reviewed: bool = Field(strict=True)
    # Upper bounds keep both revisions inside PostgreSQL bigint so an oversized
    # value is a 422 instead of a CAST overflow reported as 503 (W20A-R9).
    content_revision: int = Field(
        default=0, ge=0, le=9_223_372_036_854_775_807, strict=True
    )
    evidence_grant_id: uuid.UUID | None = None
    # Report-wide latest review revision the administrator saw (0 = no
    # decision yet); the admin report detail exposes it as
    # latest_review_revision. A different latest revision is a review race and
    # returns 409 review_revision_conflict, distinct from a content revision
    # race. Like content_revision, omission means 0 and so conflicts with any
    # existing decision instead of silently stacking a late one.
    expected_review_revision: int = Field(
        default=0, ge=0, le=9_223_372_036_854_775_807, strict=True
    )

    @field_validator("reason", "user_visible_reason", mode="before")
    @classmethod
    def reject_disallowed_text_controls(cls, value: object) -> object:
        return _reject_disallowed_admin_text_controls(value)

    @field_validator(
        "decision_id",
        "duplicate_of_report_id",
        "evidence_grant_id",
        mode="before",
    )
    @classmethod
    def require_canonical_review_uuid(cls, value: object) -> object:
        if value is None or isinstance(value, uuid.UUID):
            return value
        if not isinstance(value, str):
            raise ValueError("review UUID must be canonical")
        try:
            parsed = uuid.UUID(value)
        except ValueError as exc:
            raise ValueError("review UUID must be canonical") from exc
        if str(parsed) != value:
            raise ValueError("review UUID must be canonical")
        return parsed

    @model_validator(mode="after")
    def validate_decision_evidence(self) -> "ReportReviewDecisionRequest":
        if self.decision == "APPROVED":
            if self.user_visible_reason is not None:
                raise ValueError("APPROVED decisions cannot have a user-visible reason")
            if self.duplicate_of_report_id is not None:
                raise ValueError("APPROVED decisions cannot reference a duplicate report")
            if not (
                self.location_reviewed
                and self.photo_reviewed
                and self.privacy_reviewed
            ):
                raise ValueError("APPROVED decisions require every review check")
            if self.evidence_grant_id is None:
                raise ValueError("APPROVED decisions require evidence_grant_id")
        elif self.decision == "DUPLICATE":
            if self.duplicate_of_report_id is None:
                raise ValueError("DUPLICATE decisions require duplicate_of_report_id")
        elif self.duplicate_of_report_id is not None:
            raise ValueError("REJECTED decisions cannot reference a duplicate report")
        if self.decision != "APPROVED" and self.evidence_grant_id is not None:
            raise ValueError("Only APPROVED decisions can reference evidence_grant_id")
        if self.decision in {"REJECTED", "DUPLICATE"} and self.user_visible_reason is None:
            raise ValueError("REJECTED and DUPLICATE decisions require a user-visible reason")
        return self


class ReportReviewDecisionResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    report_id: uuid.UUID
    revision: int = Field(ge=1)
    content_revision: int = Field(ge=0)
    decision: ReportReviewDecisionValue
    reason: str
    user_visible_reason: str | None
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


class ReportReviewDecisionPageV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.report-review-decision-page.v1"]
    report_id: uuid.UUID
    snapshot_revision: int = Field(ge=0, le=9_223_372_036_854_775_807)
    total_count: int = Field(ge=0, le=9_223_372_036_854_775_807)
    items: List[ReportReviewDecisionResponse] = Field(max_length=25)
    next_cursor: str | None = Field(min_length=1, max_length=1024)

    @model_validator(mode="after")
    def validate_snapshot_page(self) -> "ReportReviewDecisionPageV1":
        if self.snapshot_revision != self.total_count:
            raise ValueError("review-decision history snapshot is inconsistent")
        revisions = [item.revision for item in self.items]
        if revisions and (
            revisions != list(range(revisions[0], revisions[0] + len(revisions)))
            or revisions[-1] > self.snapshot_revision
            or any(item.report_id != self.report_id for item in self.items)
        ):
            raise ValueError("review-decision history page is not contiguous")
        return self


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
    package_revision: int = Field(ge=1, strict=True)
    expected_revision: int = Field(ge=0, strict=True)
    idempotency_key: uuid.UUID

    @field_validator(
        "institution", "channel", "recipient", "external_receipt_id", "reason", mode="before"
    )
    @classmethod
    def reject_disallowed_text_controls(cls, value: object) -> object:
        return _reject_disallowed_admin_text_controls(value)

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


ReportDeliveryCorrectionReasonCode = Literal[
    "WRONG_STATUS", "WRONG_REPORT", "DUPLICATE_ENTRY", "OTHER"
]


def _canonical_uuid_text(value: object, field: str) -> object:
    if isinstance(value, uuid.UUID):
        return value
    if not isinstance(value, str):
        raise ValueError(f"{field} must be a canonical UUID")
    try:
        parsed = uuid.UUID(value)
    except ValueError as exc:
        raise ValueError(f"{field} must be a canonical UUID") from exc
    if str(parsed) != value:
        raise ValueError(f"{field} must be a canonical UUID")
    return parsed


class ReportInstitutionDeliveryCorrectionRequest(BaseModel):
    """S18-40: marks the latest effective delivery event as wrongly recorded."""

    model_config = ConfigDict(extra="forbid")

    correction_id: uuid.UUID
    corrects_event_id: uuid.UUID
    expected_revision: int = Field(ge=0, le=9_223_372_036_854_775_807, strict=True)
    reason_code: ReportDeliveryCorrectionReasonCode
    memo: ReportReviewReason

    @field_validator("correction_id", "corrects_event_id", mode="before")
    @classmethod
    def require_canonical_ids(cls, value: object, info) -> object:
        return _canonical_uuid_text(value, info.field_name)

    @field_validator("memo", mode="before")
    @classmethod
    def reject_disallowed_text_controls(cls, value: object) -> object:
        return _reject_disallowed_admin_text_controls(value)


class ReportInstitutionDeliveryCorrectionV1(BaseModel):
    model_config = ConfigDict(from_attributes=True, extra="forbid")

    schema_version: Literal["walksafe.report-delivery-correction.v1"] = (
        "walksafe.report-delivery-correction.v1"
    )
    id: uuid.UUID
    report_id: uuid.UUID
    corrects_event_id: uuid.UUID
    corrected_revision: int = Field(ge=1)
    expected_revision: int = Field(ge=1)
    reason_code: ReportDeliveryCorrectionReasonCode
    memo: str
    admin_id: str
    session_id: uuid.UUID
    device_id: str
    correlation_id: uuid.UUID
    created_at: AwareDatetime


class ReportInstitutionDeliveryResponse(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    report_id: uuid.UUID
    review_decision_id: uuid.UUID
    package_id: uuid.UUID | None
    package_revision: int | None = Field(default=None, ge=1)
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
    # S18-40: the append-only correction that names this event, if any.
    correction: ReportInstitutionDeliveryCorrectionV1 | None = None


class ReportInstitutionDeliveryPageV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.report-delivery-event-page.v1"]
    report_id: uuid.UUID
    snapshot_revision: int = Field(ge=0, le=9_223_372_036_854_775_807)
    total_count: int = Field(ge=0, le=9_223_372_036_854_775_807)
    items: List[ReportInstitutionDeliveryResponse] = Field(max_length=25)
    next_cursor: str | None = Field(min_length=1, max_length=1024)

    @model_validator(mode="after")
    def validate_snapshot_page(self) -> "ReportInstitutionDeliveryPageV1":
        if self.snapshot_revision != self.total_count:
            raise ValueError("delivery history snapshot is inconsistent")
        revisions = [item.revision for item in self.items]
        if revisions and (
            revisions != list(range(revisions[0], revisions[0] + len(revisions)))
            or revisions[-1] > self.snapshot_revision
            or any(item.report_id != self.report_id for item in self.items)
        ):
            raise ValueError("delivery history page is not contiguous")
        return self


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
    model_key: ReportV2ModelKey
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
    # S15-14: `gps` is the phone position at capture time, not the damaged facility. Only this
    # meaning is accepted; a facility-position estimate would need its own field and uncertainty.
    location_kind: Optional[Literal["CAPTURE_DEVICE"]] = None

    @model_validator(mode="after")
    def trigger_matches_auto_reported(self) -> "ReportV2Metadata":
        expected_auto_reported = self.trigger == "auto"
        if self.auto_reported != expected_auto_reported:
            raise ValueError("auto_reported must be true for auto trigger and false for explicit triggers")
        return self


class AccountDeletionRequestV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.account-deletion-request.v2"]
    request_id: AccountDeletionRequestId
    client_revision: int = Field(ge=1, le=9_223_372_036_854_775_807)
    confirmation: Literal["DELETE_MY_ACCOUNT"]


# Owner decision R3 (2026-09-29): the RAW and automatic-reporting disclosures now
# send after the walk ends, a substantive change, so both items and the document
# are 1.2.0; mobile-network and training texts are unchanged and keep theirs.
PRIVACY_CONSENT_POLICY_VERSION = "FP-013-1.2.0"
PRIVACY_CONSENT_ITEM_VERSIONS = {
    "raw_source_collection": "FP-013-RAW-1.2.0",
    "automatic_reporting": "FP-013-AUTO-1.2.0",
    "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
    "training_reuse": "FP-013-TRAINING-1.1.0",
}


class PrivacyConsentItemVersionsV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    raw_source_collection: Literal["FP-013-RAW-1.2.0"]
    automatic_reporting: Literal["FP-013-AUTO-1.2.0"]
    mobile_network_transfer: Literal["FP-013-MOBILE-1.0.0"]
    training_reuse: Literal["FP-013-TRAINING-1.1.0"]


class PrivacyConsentEventV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.privacy-consent-event.v2"]
    installation_id: PrivacyEvidenceId
    request_id: AccountDeletionRequestId
    client_revision: int = Field(ge=1, le=9_223_372_036_854_775_807)
    policy_version: Literal["FP-013-1.2.0"]
    item_versions: PrivacyConsentItemVersionsV2
    raw_source_collection: StrictBool
    automatic_reporting: StrictBool
    mobile_network_transfer: StrictBool
    training_reuse: StrictBool
    expected_previous_backend_receipt_sha256: Optional[Sha256LowerHex]


class PrivacyConsentSelectionsV2(BaseModel):
    model_config = ConfigDict(extra="forbid")

    raw_source_collection: StrictBool
    automatic_reporting: StrictBool
    mobile_network_transfer: StrictBool
    training_reuse: StrictBool


class PrivacyConsentBootstrapV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.integrated-consent-bootstrap.v1"]
    status: Literal["READY", "RECONSENT_REQUIRED"]
    source: Literal["CURRENT_CONSENT", "SIGNUP_CONSENT", "NONE"]
    installation_id: PrivacyEvidenceId
    policy_version: Literal["FP-013-1.2.0"]
    item_versions: PrivacyConsentItemVersionsV2
    client_revision_floor: int = Field(ge=0, le=9_223_372_036_854_775_807)
    selections: Optional[PrivacyConsentSelectionsV2]
    source_receipt_sha256: Optional[Sha256LowerHex]
    expected_previous_backend_receipt_sha256: Optional[Sha256LowerHex]

    @model_validator(mode="after")
    def require_consistent_source(self) -> "PrivacyConsentBootstrapV1":
        if (
            self.expected_previous_backend_receipt_sha256 is None
            and self.client_revision_floor != 0
        ):
            raise ValueError("a nonzero installation floor requires a previous event")
        if self.status == "RECONSENT_REQUIRED":
            if (
                self.source != "NONE"
                or self.selections is not None
                or self.source_receipt_sha256 is not None
            ):
                raise ValueError("reconsent-required bootstrap cannot expose a source")
            return self
        if (
            self.source == "NONE"
            or self.selections is None
            or self.source_receipt_sha256 is None
        ):
            raise ValueError("ready bootstrap requires an exact consent source")
        if self.source == "SIGNUP_CONSENT":
            if self.expected_previous_backend_receipt_sha256 is not None:
                raise ValueError("signup bootstrap cannot have a previous backend event")
        elif not hmac.compare_digest(
            self.source_receipt_sha256,
            self.expected_previous_backend_receipt_sha256 or "",
        ):
            raise ValueError("current bootstrap receipts must match")
        return self


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


class InstallationEvidenceKeyV1(BaseModel):
    model_config = ConfigDict(extra="forbid")
    installation_id: PrivacyEvidenceId
    public_key: str = Field(min_length=79, max_length=122, pattern=r"^[A-Za-z0-9_-]+$")


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
    # S1617-52: base64url ECDSA P-256 (DER) signature by the evidence
    # installation over "walksafe/device-deletion-evidence-signature/v1\0" +
    # evidence_sha256. Required once that installation registered a key.
    installation_signature: Optional[str] = Field(
        default=None,
        min_length=86,
        max_length=100,
        pattern=r"^[A-Za-z0-9_-]+$",
    )

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


ACCOUNT_DELETION_STATUS_VIEW_HEADER = "x-walksafe-deletion-status-view"
ACCOUNT_DELETION_DEVICE_VERIFICATION_VIEW = "device-verification.v1"


class AccountDeletionDeviceVerificationV1(BaseModel):
    """Per-installation verification summary (S1617-51, FP-046 status receipt).

    A frozen installation that has not answered by the 24-hour device deadline is
    shown as unverifiable. It is never converted to NOT_FOUND and the request is
    never COMPLETED on that basis; the deletion duty stays open for that device.
    """

    model_config = ConfigDict(extra="forbid")

    schema_version: Literal["walksafe.account-deletion-device-verification.v1"]
    completion_view: Literal[
        "COMPLETED",
        "IN_PROGRESS",
        "SERVER_COMPLETED_DEVICE_UNVERIFIED",
    ]
    target_count: int = Field(ge=0)
    deleted_count: int = Field(ge=0)
    not_found_count: int = Field(ge=0)
    failed_count: int = Field(ge=0)
    pending_count: int = Field(ge=0)
    unverifiable_count: int = Field(ge=0)
    unverifiable_reason: Optional[Literal["SLA_ELAPSED_NO_CONTACT"]]
    follow_up: Optional[Literal["DELETE_ON_NEXT_CONTACT_OR_REMOVE_APP"]]
    device_clock_anomaly_count: int = Field(ge=0)
    device_due_at: AwareDatetime
    evaluated_at: AwareDatetime

    @model_validator(mode="after")
    def require_consistent_counts(self) -> "AccountDeletionDeviceVerificationV1":
        if self.target_count != (
            self.deleted_count
            + self.not_found_count
            + self.failed_count
            + self.pending_count
            + self.unverifiable_count
        ):
            raise ValueError("device verification counts must cover every target")
        unverifiable = self.unverifiable_count > 0
        if unverifiable != (self.unverifiable_reason is not None) or unverifiable != (
            self.follow_up is not None
        ):
            raise ValueError("unverifiable devices require a reason and follow-up exclusively")
        if self.completion_view == "SERVER_COMPLETED_DEVICE_UNVERIFIED" and not unverifiable:
            raise ValueError("server-completed view requires an unverifiable device")
        if self.completion_view == "COMPLETED" and (
            self.pending_count or self.unverifiable_count or self.failed_count
        ):
            raise ValueError("COMPLETED cannot contain an unresolved device")
        return self


class AccountDeletionStatusWithDeviceVerificationV2(AccountDeletionStatusV2):
    """Opt-in status view; the base v2 status bytes stay unchanged for old callers."""

    device_verification: AccountDeletionDeviceVerificationV1

    @model_validator(mode="after")
    def require_completion_view_matches_overall(
        self,
    ) -> "AccountDeletionStatusWithDeviceVerificationV2":
        if (self.device_verification.completion_view == "COMPLETED") != (
            self.overall_status == "COMPLETED"
        ):
            raise ValueError("completion_view COMPLETED must match overall COMPLETED")
        return self


# EPIC-07 B1a freezes the public raw-ingest contract before any persistence
# implementation is enabled. These limits are deliberately fixed contract
# values rather than operator-tunable knobs.
RAW_COLLECTION_MAX_OBJECTS = 64
RAW_COLLECTION_MAX_CHUNKS = 2_048
RAW_CHUNK_MAX_BYTES = 8 * 1024 * 1024
RAW_COLLECTION_MAX_TOTAL_BYTES = RAW_COLLECTION_MAX_CHUNKS * RAW_CHUNK_MAX_BYTES
RAW_RETENTION_DAYS = 180
RAW_QUARANTINE_DAYS = 14
RAW_CANONICAL_UUID_PATTERN = (
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}$"
)
_RAW_UTC_PATTERN = re.compile(
    r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$"
)
_RAW_CONTENT_TYPE_PATTERN = (
    r"^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/"
    r"[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$"
)

RawCanonicalUuid = Annotated[
    str,
    StringConstraints(min_length=36, max_length=36, pattern=RAW_CANONICAL_UUID_PATTERN),
]
RawCollectionPurpose = Literal["GENERAL_RAW", "AUTO_REPORT"]
RawCollectionObjectKind = Literal[
    "VIDEO",
    "AUDIO",
    "EXACT_LOCATION",
    "SENSOR",
    "ROUTE",
    "DETECTION",
    "REPORT",
    "PERFORMANCE",
]
RawCollectionState = Literal[
    "MANIFEST_ACCEPTED",
    "RECEIVING",
    "READY_TO_COMMIT",
    "COMMITTED",
    "QUARANTINED",
]
RawContentType = Annotated[
    str,
    StringConstraints(
        min_length=3,
        max_length=128,
        pattern=_RAW_CONTENT_TYPE_PATTERN,
    ),
]


def _raw_contract_sha256(
    domain: bytes,
    payload: Mapping[str, object] | BaseModel,
    *,
    digest_field: str,
) -> str:
    value = (
        payload.model_dump(mode="json")
        if isinstance(payload, BaseModel)
        else dict(payload)
    )
    value.pop(digest_field, None)
    canonical = json.dumps(
        value,
        ensure_ascii=False,
        allow_nan=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(domain + canonical).hexdigest()


def raw_collection_manifest_sha256(
    payload: Mapping[str, object] | BaseModel,
) -> str:
    return _raw_contract_sha256(
        b"walksafe/raw-collection-manifest/v1\0",
        payload,
        digest_field="manifest_sha256",
    )


def raw_collection_receipt_sha256(
    payload: Mapping[str, object] | BaseModel,
) -> str:
    return _raw_contract_sha256(
        b"walksafe/raw-collection-receipt/v1\0",
        payload,
        digest_field="receipt_sha256",
    )


def raw_collection_receipt_v2_sha256(
    payload: Mapping[str, object] | BaseModel,
) -> str:
    return _raw_contract_sha256(
        b"walksafe/raw-collection-receipt/v2\0",
        payload,
        digest_field="receipt_sha256",
    )


def raw_collection_commit_sha256(
    payload: Mapping[str, object] | BaseModel,
) -> str:
    return _raw_contract_sha256(
        b"walksafe/raw-collection-commit/v1\0",
        payload,
        digest_field="commit_sha256",
    )


class RawCollectionChunkV1(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    index: int = Field(ge=0, lt=RAW_COLLECTION_MAX_CHUNKS)
    size_bytes: int = Field(ge=1, le=RAW_CHUNK_MAX_BYTES)
    sha256: Sha256LowerHex


class RawCollectionErrorDetailV1(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    code: str = Field(min_length=1, max_length=128)
    message: str = Field(min_length=1, max_length=500)
    max_bytes: Optional[int] = Field(default=None, ge=1)


class RawCollectionErrorResponseV1(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    detail: RawCollectionErrorDetailV1


class RawCollectionObjectV1(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    object_id: RawCanonicalUuid
    kind: RawCollectionObjectKind
    content_type: RawContentType
    size_bytes: int = Field(ge=1, le=RAW_COLLECTION_MAX_TOTAL_BYTES)
    sha256: Sha256LowerHex
    chunks: List[RawCollectionChunkV1] = Field(
        min_length=1,
        max_length=RAW_COLLECTION_MAX_CHUNKS,
    )

    @model_validator(mode="after")
    def require_canonical_chunks(self) -> "RawCollectionObjectV1":
        indices = tuple(chunk.index for chunk in self.chunks)
        if indices != tuple(range(len(self.chunks))):
            raise ValueError("chunks must be contiguous and ordered from index 0")
        if sum(chunk.size_bytes for chunk in self.chunks) != self.size_bytes:
            raise ValueError("chunk sizes must equal the declared object size")
        return self


class RawCollectionManifestV1(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    schema_version: Literal["walksafe.raw-collection-manifest.v1"]
    collection_id: RawCanonicalUuid
    walk_id: RawCanonicalUuid
    segment_id: RawCanonicalUuid
    purpose: RawCollectionPurpose
    captured_started_at: AwareDatetime = Field(
        strict=False,
        json_schema_extra={"pattern": _RAW_UTC_PATTERN.pattern}
    )
    captured_ended_at: AwareDatetime = Field(
        strict=False,
        json_schema_extra={"pattern": _RAW_UTC_PATTERN.pattern}
    )
    consent_receipt_sha256: Sha256LowerHex
    object_count: int = Field(ge=1, le=RAW_COLLECTION_MAX_OBJECTS)
    chunk_count: int = Field(ge=1, le=RAW_COLLECTION_MAX_CHUNKS)
    total_bytes: int = Field(ge=1, le=RAW_COLLECTION_MAX_TOTAL_BYTES)
    objects: List[RawCollectionObjectV1] = Field(
        min_length=1,
        max_length=RAW_COLLECTION_MAX_OBJECTS,
    )
    manifest_sha256: Sha256LowerHex

    @field_validator("captured_started_at", "captured_ended_at", mode="before")
    @classmethod
    def require_canonical_utc_text(cls, value: object) -> object:
        if type(value) is not str or _RAW_UTC_PATTERN.fullmatch(value) is None:
            raise ValueError("raw collection timestamps must be canonical UTC text ending in Z")
        return value

    @field_serializer("captured_started_at", "captured_ended_at")
    def canonical_capture_time(self, value: datetime) -> str:
        return value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")

    @model_validator(mode="after")
    def require_canonical_inventory(self) -> "RawCollectionManifestV1":
        if self.captured_ended_at < self.captured_started_at:
            raise ValueError("captured_ended_at cannot precede captured_started_at")
        object_ids = tuple(item.object_id for item in self.objects)
        if object_ids != tuple(sorted(object_ids)) or len(set(object_ids)) != len(object_ids):
            raise ValueError("objects must have unique IDs in canonical lexical order")
        observed_chunks = sum(len(item.chunks) for item in self.objects)
        observed_bytes = sum(item.size_bytes for item in self.objects)
        if self.object_count != len(self.objects):
            raise ValueError("object_count does not match objects")
        if self.chunk_count != observed_chunks:
            raise ValueError("chunk_count does not match objects")
        if self.total_bytes != observed_bytes:
            raise ValueError("total_bytes does not match objects")
        expected = raw_collection_manifest_sha256(self)
        if not hmac.compare_digest(self.manifest_sha256, expected):
            raise ValueError("manifest_sha256 does not match the canonical manifest")
        return self


class RawCollectionCommitV1(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    schema_version: Literal["walksafe.raw-collection-commit.v1"]
    collection_id: RawCanonicalUuid
    manifest_sha256: Sha256LowerHex
    object_count: int = Field(ge=1, le=RAW_COLLECTION_MAX_OBJECTS)
    chunk_count: int = Field(ge=1, le=RAW_COLLECTION_MAX_CHUNKS)
    total_bytes: int = Field(ge=1, le=RAW_COLLECTION_MAX_TOTAL_BYTES)


class RawCollectionMissingRangeV1(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    start: int = Field(ge=0, lt=RAW_COLLECTION_MAX_CHUNKS)
    end: int = Field(ge=0, lt=RAW_COLLECTION_MAX_CHUNKS)

    @model_validator(mode="after")
    def require_ordered_range(self) -> "RawCollectionMissingRangeV1":
        if self.end < self.start:
            raise ValueError("missing range end cannot precede start")
        return self


class RawCollectionObjectStatusV1(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    object_id: RawCanonicalUuid
    kind: RawCollectionObjectKind
    sha256: Sha256LowerHex
    chunk_count: int = Field(ge=1, le=RAW_COLLECTION_MAX_CHUNKS)
    received_chunk_count: int = Field(ge=0, le=RAW_COLLECTION_MAX_CHUNKS)
    size_bytes: int = Field(ge=1, le=RAW_COLLECTION_MAX_TOTAL_BYTES)
    received_bytes: int = Field(ge=0, le=RAW_COLLECTION_MAX_TOTAL_BYTES)
    missing_ranges: List[RawCollectionMissingRangeV1] = Field(
        max_length=RAW_COLLECTION_MAX_CHUNKS
    )

    @model_validator(mode="after")
    def require_compact_missing_ranges(self) -> "RawCollectionObjectStatusV1":
        if self.received_chunk_count > self.chunk_count or self.received_bytes > self.size_bytes:
            raise ValueError("received counters cannot exceed declared counters")
        if (self.received_chunk_count == 0) != (self.received_bytes == 0):
            raise ValueError("zero received chunks and bytes must agree")
        if 0 < self.received_chunk_count < self.chunk_count and not (
            0 < self.received_bytes < self.size_bytes
        ):
            raise ValueError("partial chunk progress requires partial byte progress")
        previous_end = -2
        missing_count = 0
        for item in self.missing_ranges:
            if item.end >= self.chunk_count:
                raise ValueError("missing ranges must stay within object chunk_count")
            if item.start <= previous_end + 1:
                raise ValueError("missing ranges must be ordered, disjoint, and compact")
            previous_end = item.end
            missing_count += item.end - item.start + 1
        if missing_count != self.chunk_count - self.received_chunk_count:
            raise ValueError("missing ranges do not match received_chunk_count")
        complete = self.received_chunk_count == self.chunk_count
        if complete != (self.received_bytes == self.size_bytes):
            raise ValueError("complete chunk and byte counters must agree")
        return self


class RawCollectionReceiptObjectV1(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    object_id: RawCanonicalUuid
    kind: RawCollectionObjectKind
    size_bytes: int = Field(ge=1, le=RAW_COLLECTION_MAX_TOTAL_BYTES)
    sha256: Sha256LowerHex
    chunk_count: int = Field(ge=1, le=RAW_COLLECTION_MAX_CHUNKS)


class RawCollectionReceiptV1(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    schema_version: Literal["walksafe.raw-collection-receipt.v1"]
    collection_id: RawCanonicalUuid
    manifest_sha256: Sha256LowerHex
    purpose: RawCollectionPurpose
    persistence_marker: Literal["DATABASE_AND_ENCRYPTED_CHUNK_STORE"]
    object_count: int = Field(ge=1, le=RAW_COLLECTION_MAX_OBJECTS)
    chunk_count: int = Field(ge=1, le=RAW_COLLECTION_MAX_CHUNKS)
    total_bytes: int = Field(ge=1, le=RAW_COLLECTION_MAX_TOTAL_BYTES)
    objects: List[RawCollectionReceiptObjectV1] = Field(
        min_length=1,
        max_length=RAW_COLLECTION_MAX_OBJECTS,
    )
    retention_class: Literal["RAW_ORIGINAL_180D"]
    committed_at: AwareDatetime = Field(
        strict=False,
        json_schema_extra={"pattern": _RAW_UTC_PATTERN.pattern}
    )
    retention_expires_at: AwareDatetime = Field(
        strict=False,
        json_schema_extra={"pattern": _RAW_UTC_PATTERN.pattern}
    )
    receipt_sha256: Sha256LowerHex

    @field_validator("committed_at", "retention_expires_at", mode="before")
    @classmethod
    def require_canonical_receipt_time(cls, value: object) -> object:
        if type(value) is not str or _RAW_UTC_PATTERN.fullmatch(value) is None:
            raise ValueError("receipt timestamps must be canonical UTC text ending in Z")
        return value

    @field_serializer("committed_at", "retention_expires_at")
    def canonical_receipt_time(self, value: datetime) -> str:
        return value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")

    @model_validator(mode="after")
    def require_receipt_inventory(self) -> "RawCollectionReceiptV1":
        object_ids = tuple(item.object_id for item in self.objects)
        if object_ids != tuple(sorted(object_ids)) or len(set(object_ids)) != len(object_ids):
            raise ValueError("receipt objects must have unique IDs in canonical lexical order")
        if self.object_count != len(self.objects):
            raise ValueError("receipt object_count does not match objects")
        if self.chunk_count != sum(item.chunk_count for item in self.objects):
            raise ValueError("receipt chunk_count does not match objects")
        if self.total_bytes != sum(item.size_bytes for item in self.objects):
            raise ValueError("receipt total_bytes does not match objects")
        if self.retention_expires_at != self.committed_at + timedelta(days=RAW_RETENTION_DAYS):
            raise ValueError("raw receipt retention must be exactly 180 days")
        expected = raw_collection_receipt_sha256(self)
        if not hmac.compare_digest(self.receipt_sha256, expected):
            raise ValueError("receipt_sha256 does not match the canonical receipt")
        return self


class RawCollectionReceiptV2(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    schema_version: Literal["walksafe.raw-collection-receipt.v2"]
    collection_id: RawCanonicalUuid
    manifest_sha256: Sha256LowerHex
    purpose: RawCollectionPurpose
    persistence_marker: Literal["DATABASE_AND_ENCRYPTED_CHUNK_STORE"]
    object_count: int = Field(ge=1, le=RAW_COLLECTION_MAX_OBJECTS)
    chunk_count: int = Field(ge=1, le=RAW_COLLECTION_MAX_CHUNKS)
    total_bytes: int = Field(ge=1, le=RAW_COLLECTION_MAX_TOTAL_BYTES)
    objects: List[RawCollectionReceiptObjectV1] = Field(min_length=1, max_length=RAW_COLLECTION_MAX_OBJECTS)
    retention_class: Literal["RAW_QUARANTINE_14D"]
    committed_at: AwareDatetime = Field(strict=False, json_schema_extra={"pattern": _RAW_UTC_PATTERN.pattern})
    quarantine_expires_at: AwareDatetime = Field(strict=False, json_schema_extra={"pattern": _RAW_UTC_PATTERN.pattern})
    receipt_sha256: Sha256LowerHex

    @field_validator("committed_at", "quarantine_expires_at", mode="before")
    @classmethod
    def require_canonical_receipt_time(cls, value: object) -> object:
        if type(value) is not str or _RAW_UTC_PATTERN.fullmatch(value) is None:
            raise ValueError("receipt timestamps must be canonical UTC text ending in Z")
        return value

    @field_serializer("committed_at", "quarantine_expires_at")
    def canonical_receipt_time(self, value: datetime) -> str:
        return value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")

    @model_validator(mode="after")
    def require_receipt_inventory(self) -> "RawCollectionReceiptV2":
        object_ids = tuple(item.object_id for item in self.objects)
        if object_ids != tuple(sorted(object_ids)) or len(set(object_ids)) != len(object_ids):
            raise ValueError("receipt objects must have unique IDs in canonical lexical order")
        if self.object_count != len(self.objects):
            raise ValueError("receipt object_count does not match objects")
        if self.chunk_count != sum(item.chunk_count for item in self.objects):
            raise ValueError("receipt chunk_count does not match objects")
        if self.total_bytes != sum(item.size_bytes for item in self.objects):
            raise ValueError("receipt total_bytes does not match objects")
        if self.quarantine_expires_at != self.committed_at + timedelta(days=RAW_QUARANTINE_DAYS):
            raise ValueError("raw quarantine must be exactly 14 days")
        expected = raw_collection_receipt_v2_sha256(self)
        if not hmac.compare_digest(self.receipt_sha256, expected):
            raise ValueError("receipt_sha256 does not match the canonical receipt")
        return self


class RawCollectionStatusV1(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    schema_version: Literal["walksafe.raw-collection-status.v1"]
    collection_id: RawCanonicalUuid
    manifest_sha256: Sha256LowerHex
    purpose: RawCollectionPurpose
    state: RawCollectionState
    object_count: int = Field(ge=1, le=RAW_COLLECTION_MAX_OBJECTS)
    chunk_count: int = Field(ge=1, le=RAW_COLLECTION_MAX_CHUNKS)
    total_bytes: int = Field(ge=1, le=RAW_COLLECTION_MAX_TOTAL_BYTES)
    received_chunk_count: int = Field(ge=0, le=RAW_COLLECTION_MAX_CHUNKS)
    received_bytes: int = Field(ge=0, le=RAW_COLLECTION_MAX_TOTAL_BYTES)
    objects: List[RawCollectionObjectStatusV1] = Field(
        min_length=1,
        max_length=RAW_COLLECTION_MAX_OBJECTS,
    )
    receipt: Optional[RawCollectionReceiptV1 | RawCollectionReceiptV2]

    @model_validator(mode="after")
    def require_status_inventory(self) -> "RawCollectionStatusV1":
        object_ids = tuple(item.object_id for item in self.objects)
        if object_ids != tuple(sorted(object_ids)) or len(set(object_ids)) != len(object_ids):
            raise ValueError("status objects must have unique IDs in canonical lexical order")
        if self.object_count != len(self.objects):
            raise ValueError("status object_count does not match objects")
        if self.chunk_count != sum(item.chunk_count for item in self.objects):
            raise ValueError("status chunk_count does not match objects")
        if self.total_bytes != sum(item.size_bytes for item in self.objects):
            raise ValueError("status total_bytes does not match objects")
        if self.received_chunk_count != sum(
            item.received_chunk_count for item in self.objects
        ):
            raise ValueError("status received_chunk_count does not match objects")
        if self.received_bytes != sum(item.received_bytes for item in self.objects):
            raise ValueError("status received_bytes does not match objects")
        complete = (
            self.received_chunk_count == self.chunk_count
            and self.received_bytes == self.total_bytes
        )
        if self.state in {"READY_TO_COMMIT", "COMMITTED", "QUARANTINED"} and not complete:
            raise ValueError("ready and committed states require a complete inventory")
        if self.state == "MANIFEST_ACCEPTED" and (
            self.received_chunk_count != 0 or self.received_bytes != 0
        ):
            raise ValueError("manifest-accepted state cannot contain received bytes")
        if self.state == "RECEIVING" and (
            self.received_chunk_count == 0 or complete
        ):
            raise ValueError("receiving state requires a partial inventory")
        if (self.state in {"COMMITTED", "QUARANTINED"}) != (self.receipt is not None):
            raise ValueError("only terminal committed status contains a receipt")
        if self.state == "COMMITTED" and not isinstance(self.receipt, RawCollectionReceiptV1):
            raise ValueError("legacy COMMITTED status requires receipt v1")
        if self.state == "QUARANTINED" and not isinstance(self.receipt, RawCollectionReceiptV2):
            raise ValueError("QUARANTINED status requires receipt v2")
        if self.receipt is not None and (
            self.receipt.collection_id != self.collection_id
            or self.receipt.manifest_sha256 != self.manifest_sha256
            or self.receipt.purpose != self.purpose
            or self.receipt.object_count != self.object_count
            or self.receipt.chunk_count != self.chunk_count
            or self.receipt.total_bytes != self.total_bytes
        ):
            raise ValueError("receipt does not bind the status inventory")
        if self.receipt is not None:
            status_inventory = tuple(
                (
                    item.object_id,
                    item.kind,
                    item.size_bytes,
                    item.sha256,
                    item.chunk_count,
                )
                for item in self.objects
            )
            receipt_inventory = tuple(
                (
                    item.object_id,
                    item.kind,
                    item.size_bytes,
                    item.sha256,
                    item.chunk_count,
                )
                for item in self.receipt.objects
            )
            if receipt_inventory != status_inventory:
                raise ValueError("receipt objects do not bind the status objects")
        return self


class RawCollectionChunkAckV1(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)

    schema_version: Literal["walksafe.raw-collection-chunk-ack.v1"]
    collection_id: RawCanonicalUuid
    object_id: RawCanonicalUuid
    index: int = Field(ge=0, lt=RAW_COLLECTION_MAX_CHUNKS)
    size_bytes: int = Field(ge=1, le=RAW_CHUNK_MAX_BYTES)
    sha256: Sha256LowerHex
    state: RawCollectionState
    stored_at: AwareDatetime = Field(
        strict=False,
        json_schema_extra={"pattern": _RAW_UTC_PATTERN.pattern}
    )

    @field_validator("stored_at", mode="before")
    @classmethod
    def require_canonical_stored_time(cls, value: object) -> object:
        if type(value) is not str or _RAW_UTC_PATTERN.fullmatch(value) is None:
            raise ValueError("stored_at must be canonical UTC text ending in Z")
        return value

    @field_serializer("stored_at")
    def canonical_stored_at(self, value: datetime) -> str:
        return value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class RawPurposeDecisionRequestV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    scope: Literal["REPORT", "TRAINING"]
    decision: Literal["APPROVED", "REJECTED"]
    expected_revision: int = Field(ge=0)
    idempotency_key: uuid.UUID
    reason: str = Field(min_length=1, max_length=500)
    training_consent_receipt_sha256: Optional[Sha256LowerHex] = None
    deidentification_receipt_sha256: Optional[Sha256LowerHex] = None
    sanitized_manifest_sha256: Optional[Sha256LowerHex] = None
    target_dataset_id: Optional[uuid.UUID] = None
    exact_location_excluded: bool = False
    raw_audio_excluded: bool = False
    third_party_faces_excluded: bool = False

    @model_validator(mode="after")
    def require_training_approval_evidence(self) -> "RawPurposeDecisionRequestV1":
        evidence = (
            self.training_consent_receipt_sha256,
            self.deidentification_receipt_sha256,
            self.sanitized_manifest_sha256,
            self.target_dataset_id,
        )
        flags = (
            self.exact_location_excluded,
            self.raw_audio_excluded,
            self.third_party_faces_excluded,
        )
        if self.scope == "TRAINING" and self.decision == "APPROVED":
            if any(value is None for value in evidence) or not all(flags):
                raise ValueError("TRAINING approval requires consent, de-identification, sanitized manifest, target dataset, and all exclusions")
        elif any(value is not None for value in evidence) or any(flags):
            raise ValueError("training approval evidence is exclusive to an approved TRAINING decision")
        return self


class RawPurposeDecisionResponseV1(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    collection_id: uuid.UUID
    scope: Literal["REPORT", "TRAINING"]
    revision: int
    expected_revision: int
    idempotency_key: uuid.UUID
    decision: Literal["APPROVED", "REJECTED"]
    reason: str
    source_manifest_sha256: Sha256LowerHex
    source_receipt_sha256: Sha256LowerHex
    training_consent_receipt_sha256: Optional[Sha256LowerHex]
    deidentification_receipt_sha256: Optional[Sha256LowerHex]
    sanitized_manifest_sha256: Optional[Sha256LowerHex]
    target_dataset_id: Optional[uuid.UUID]
    exact_location_excluded: bool
    raw_audio_excluded: bool
    third_party_faces_excluded: bool
    admin_id: str
    decided_at: AwareDatetime


class RawLegalHoldRequestV1(BaseModel):
    model_config = ConfigDict(extra="forbid")

    action: Literal["APPLY", "RELEASE"]
    expected_revision: int = Field(ge=0)
    idempotency_key: uuid.UUID
    reason: str = Field(min_length=1, max_length=500)
    legal_basis: Optional[str] = Field(default=None, min_length=1, max_length=500)
    authority_reference: Optional[str] = Field(default=None, min_length=1, max_length=160)
    contact: Optional[str] = Field(default=None, min_length=1, max_length=160)
    expires_at: Optional[AwareDatetime] = None

    @model_validator(mode="after")
    def require_apply_fields(self) -> "RawLegalHoldRequestV1":
        fields = (self.legal_basis, self.authority_reference, self.contact, self.expires_at)
        if self.action == "APPLY" and any(value is None for value in fields):
            raise ValueError("APPLY requires legal basis, authority reference, contact, and expiry")
        if self.action == "RELEASE" and any(value is not None for value in fields):
            raise ValueError("RELEASE cannot carry apply-only legal-hold fields")
        return self


class RawLegalHoldResponseV1(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    id: uuid.UUID
    collection_id: uuid.UUID
    revision: int
    expected_revision: int
    idempotency_key: uuid.UUID
    action: Literal["APPLY", "RELEASE"]
    reason: str
    legal_basis: Optional[str]
    authority_reference: Optional[str]
    contact: Optional[str]
    expires_at: Optional[AwareDatetime]
    admin_id: str
    recorded_at: AwareDatetime


class AdminRawCollectionSummaryV1(BaseModel):
    model_config = ConfigDict(from_attributes=True)

    collection_id: uuid.UUID
    purpose: RawCollectionPurpose
    state: RawCollectionState
    manifest_sha256: Sha256LowerHex
    receipt_sha256: Optional[Sha256LowerHex]
    object_count: int
    total_bytes: int
    committed_at: Optional[AwareDatetime]
    quarantine_expires_at: Optional[AwareDatetime]
    report_decision: Optional[Literal["APPROVED", "REJECTED"]]
    report_decision_revision: int = Field(ge=0)
    training_decision: Optional[Literal["APPROVED", "REJECTED"]]
    training_decision_revision: int = Field(ge=0)
    legal_hold_active: bool
    legal_hold_revision: int = Field(ge=0)


class AdminRawCollectionListV1(BaseModel):
    schema_version: Literal["walksafe.admin-raw-collection-list.v1"]
    items: List[AdminRawCollectionSummaryV1]
