/**
 * Pure risk policy shared by fake/server v2 presentation and policy tests.
 * Detection presence alone is not an alert: path relation, stable tracking and trusted distance
 * evidence decide whether an object is display-only, report-only or user-alertable.
 */
import type { DetectionEvent } from "@/types/inference";
import type { DetectionDistanceSource, TwoModelDetection } from "@/types/inference-v2";
import { estimatePseudoDepthFromHistory, type ApproachState } from "./depth-estimator";

export type RiskType =
  | "no_risk"
  | "display_only"
  | "report_only_damage"
  | "surface_hazard"
  | "path_obstacle"
  | "approaching_object"
  | "blocking_object";

export type RiskLevel = "none" | "low" | "medium" | "high";

export type TtcRiskBucket = "stop" | "warning" | "aware" | "none";

export const TTC_STOP_MAX_MS = 3_000;
export const TTC_WARNING_MAX_MS = 10_000;
export const TTC_AWARE_MAX_MS = 30_000;
export const MODEL_ESTIMATE_MIN_CONFIDENCE = 0.5;
export const MIN_STABLE_TRACKING_FRAMES = 3;
export const MIN_STABLE_TRACKING_MS = 700;

export const WALKSAFE_CORE_CLASS_NAMES = [
  "person",
  "bicycle",
  "car",
  "motorcycle",
  "bus",
  "truck",
  "traffic light",
  "normal_tactile_block",
  "damaged_tactile_block",
  "crosswalk",
  "curb_step",
  "uneven_sidewalk",
  "e_scooter_obstruction"
] as const;

const WALKSAFE_CORE_CLASS_SET = new Set<string>(WALKSAFE_CORE_CLASS_NAMES);

export type PathRelation = "on_path" | "near_path" | "off_path" | "unknown";

export type RiskObjectTracking = {
  object_id?: string;
  stable_frames?: number;
  stable_ms?: number;
  approach_state?: ApproachState;
  approaching?: boolean;
  area_growth_ratio?: number | null;
  area_growth_per_second?: number | null;
  blocking_path?: boolean;
  projected_path_intersection?: boolean;
  time_to_collision_ms?: number | null;
  time_to_collision_source?: "bbox_scale_heuristic" | "trusted_depth" | null;
  distance_m?: number | null;
  distance_source?: DetectionDistanceSource | null;
  distance_confidence?: number | null;
};

export type RiskDepthContext = {
  distance_m?: number | null;
  confidence?: number | null;
  source?: DetectionDistanceSource | null;
};

export type RiskSegmentationContext = {
  path_relation?: PathRelation;
  walkable_ratio?: number | null;
};

export type RiskImuContext = {
  walking_speed_mps?: number | null;
  acceleration_mps2?: number | null;
};

export type RiskEvaluationContext = {
  heading?: number | null;
  imu?: RiskImuContext;
  depth?: RiskDepthContext;
  segmentation?: RiskSegmentationContext;
  tracking?: RiskObjectTracking;
};

export type RiskDecision = {
  alertable: boolean;
  reportable: boolean;
  risk_type: RiskType;
  risk_level: RiskLevel;
  reason: string;
  recommended_message: string | null;
};

type V1RiskPolicy = {
  risk_type: RiskType;
  risk_level: RiskLevel;
  reportable: boolean;
  message: string | null;
};

type GeneralRiskSignal = {
  risk_type: "approaching_object" | "blocking_object";
  risk_level: Exclude<RiskLevel, "none">;
  alertable: boolean;
  reason: string;
};

export type BBoxHistorySample = {
  detection: TwoModelDetection;
  observed_at_ms?: number;
};

type BBoxHistoryRiskOptions = {
  max_history_ms?: number;
  max_sample_gap_ms?: number;
  min_iou?: number;
  max_center_shift?: number;
  min_stable_frames?: number;
  motion_stability?: number | null;
};

const V1_POLICY: Record<DetectionEvent["class_name"], V1RiskPolicy> = {
  damaged_tactile_block: {
    risk_type: "report_only_damage",
    risk_level: "medium",
    reportable: true,
    message: null
  },
  parked_kickboard_bicycle: {
    risk_type: "path_obstacle",
    risk_level: "medium",
    reportable: false,
    message: "전방 장애물입니다. 천천히 이동하세요."
  },
  construction_obstacle: {
    risk_type: "path_obstacle",
    risk_level: "high",
    reportable: false,
    message: "공사 장애물입니다. 우회하세요."
  },
  pothole: {
    risk_type: "surface_hazard",
    risk_level: "medium",
    reportable: false,
    message: "노면 파임입니다. 발밑을 주의하세요."
  }
};

const REPORTABLE_TACTILE_DAMAGE_CLASSES = new Set(["damaged_tactile_block"]);
const SURFACE_HAZARD_CLASSES = new Set(["curb_step", "uneven_sidewalk"]);
const PATH_OBSTACLE_CLASSES = new Set(["e_scooter_obstruction"]);
const PATH_GUIDANCE_CLASSES = new Set(["crosswalk"]);

const DEFAULT_BBOX_HISTORY_OPTIONS: Required<BBoxHistoryRiskOptions> = {
  max_history_ms: 2500,
  max_sample_gap_ms: 1300,
  min_iou: 0.1,
  max_center_shift: 0.3,
  min_stable_frames: 3,
  motion_stability: null
};
// Model/bbox estimates may describe a trend, but cannot satisfy a close-distance safety gate.
const CLOSE_RISK_DISTANCE_SOURCES = new Set<DetectionDistanceSource>(["sensor_depth", "manual_fixture"]);

export function isWalkSafeCoreClassName(className: string): boolean {
  return WALKSAFE_CORE_CLASS_SET.has(className);
}

export function ttcRiskBucketForMilliseconds(timeToCollisionMs: number | null | undefined): TtcRiskBucket {
  if (typeof timeToCollisionMs !== "number" || !Number.isFinite(timeToCollisionMs) || timeToCollisionMs < 0) {
    return "none";
  }
  if (timeToCollisionMs <= TTC_STOP_MAX_MS) {
    return "stop";
  }
  if (timeToCollisionMs <= TTC_WARNING_MAX_MS) {
    return "warning";
  }
  if (timeToCollisionMs <= TTC_AWARE_MAX_MS) {
    return "aware";
  }
  return "none";
}

export function shouldVibrateForRiskLevel(riskLevel: RiskLevel): boolean {
  return riskLevel === "high";
}

export function trackingKeyForDetection(detection: TwoModelDetection): string {
  return `${detection.source_model}:${detection.model_key}:${detection.model_class_id}:${detection.class_name}`;
}

function timestampForSample(sample: BBoxHistorySample): number {
  if (typeof sample.observed_at_ms === "number" && Number.isFinite(sample.observed_at_ms)) {
    return sample.observed_at_ms;
  }

  const parsed = Date.parse(sample.detection.captured_at);
  return Number.isFinite(parsed) ? parsed : 0;
}

function bboxArea(detection: TwoModelDetection): number {
  return Math.max(0, detection.bbox.width) * Math.max(0, detection.bbox.height);
}

function bboxCenter(detection: TwoModelDetection): { x: number; y: number } {
  return {
    x: detection.bbox.x + detection.bbox.width / 2,
    y: detection.bbox.y + detection.bbox.height / 2
  };
}

function bboxIou(a: TwoModelDetection, b: TwoModelDetection): number {
  const ax2 = a.bbox.x + a.bbox.width;
  const ay2 = a.bbox.y + a.bbox.height;
  const bx2 = b.bbox.x + b.bbox.width;
  const by2 = b.bbox.y + b.bbox.height;
  const intersectionWidth = Math.max(0, Math.min(ax2, bx2) - Math.max(a.bbox.x, b.bbox.x));
  const intersectionHeight = Math.max(0, Math.min(ay2, by2) - Math.max(a.bbox.y, b.bbox.y));
  const intersectionArea = intersectionWidth * intersectionHeight;
  const unionArea = bboxArea(a) + bboxArea(b) - intersectionArea;

  return unionArea > 0 ? intersectionArea / unionArea : 0;
}

function centerShift(a: TwoModelDetection, b: TwoModelDetection): number {
  const aCenter = bboxCenter(a);
  const bCenter = bboxCenter(b);
  return Math.hypot(aCenter.x - bCenter.x, aCenter.y - bCenter.y);
}

function isSameTrackCandidate(a: TwoModelDetection, b: TwoModelDetection, options: Required<BBoxHistoryRiskOptions>): boolean {
  if (trackingKeyForDetection(a) !== trackingKeyForDetection(b)) {
    return false;
  }

  return bboxIou(a, b) >= options.min_iou || centerShift(a, b) <= options.max_center_shift;
}

function isFreshSampleGap(previous: BBoxHistorySample | undefined, current: BBoxHistorySample, maxGapMs: number): boolean {
  if (!previous) {
    return true;
  }

  const previousTime = timestampForSample(previous);
  const currentTime = timestampForSample(current);
  if (previousTime === 0 || currentTime === 0) {
    return true;
  }

  return Math.abs(currentTime - previousTime) <= maxGapMs;
}

function areaGrowthStepCounts(track: BBoxHistorySample[]): { growing: number; shrinking: number } {
  let growing = 0;
  let shrinking = 0;

  for (let index = 1; index < track.length; index += 1) {
    const previous = bboxArea(track[index - 1].detection);
    const current = bboxArea(track[index].detection);
    if (previous <= 0) {
      continue;
    }

    const ratio = current / previous - 1;
    if (ratio >= 0.03) {
      growing += 1;
    } else if (ratio <= -0.03) {
      shrinking += 1;
    }
  }

  return { growing, shrinking };
}

export function buildBBoxHistoryRiskContext(
  current: TwoModelDetection,
  history: BBoxHistorySample[],
  options: BBoxHistoryRiskOptions = {}
): RiskEvaluationContext {
  const resolvedOptions = { ...DEFAULT_BBOX_HISTORY_OPTIONS, ...options };
  const sortedHistory = history
    .filter((sample) => trackingKeyForDetection(sample.detection) === trackingKeyForDetection(current))
    .sort((a, b) => timestampForSample(a) - timestampForSample(b));
  const latestHistorySample = sortedHistory.at(-1);
  const latestIsCurrent = latestHistorySample?.detection === current;
  const samples = latestIsCurrent
    ? sortedHistory
    : [...sortedHistory, { detection: current, observed_at_ms: Date.parse(current.captured_at) }];
  const latestSample = samples.at(-1);

  if (!latestSample) {
    return {};
  }

  const latestTime = timestampForSample(latestSample);
  const track: BBoxHistorySample[] = [];

  for (let index = samples.length - 1; index >= 0; index -= 1) {
    const sample = samples[index];
    const sampleTime = timestampForSample(sample);
    const nextSample = track[0];
    const withinWindow = latestTime === 0 || sampleTime === 0 || latestTime - sampleTime <= resolvedOptions.max_history_ms;
    const continuous =
      !nextSample ||
      (isSameTrackCandidate(sample.detection, nextSample.detection, resolvedOptions) &&
        isFreshSampleGap(sample, nextSample, resolvedOptions.max_sample_gap_ms));

    if (!withinWindow || !continuous) {
      break;
    }

    track.unshift(sample);
  }

  const firstSample = track[0] ?? latestSample;
  const stableFrames = track.length;
  const stableMs = Math.max(0, latestTime - timestampForSample(firstSample));
  const firstArea = bboxArea(firstSample.detection);
  const latestArea = bboxArea(latestSample.detection);
  const elapsedSeconds = stableMs / 1000;
  const areaGrowthRatio = firstArea > 0 ? latestArea / firstArea - 1 : null;
  const areaGrowthPerSecond = areaGrowthRatio !== null && elapsedSeconds > 0 ? areaGrowthRatio / elapsedSeconds : null;
  const firstScale = Math.sqrt(firstArea);
  const latestScale = Math.sqrt(latestArea);
  const firstRelativeDistance = firstScale > 0 ? 1 / firstScale : null;
  const latestRelativeDistance = latestScale > 0 ? 1 / latestScale : null;
  const relativeClosingSpeed =
    firstRelativeDistance !== null && latestRelativeDistance !== null && elapsedSeconds > 0
      ? (firstRelativeDistance - latestRelativeDistance) / elapsedSeconds
      : null;
  const timeToCollisionMs =
    latestRelativeDistance !== null && relativeClosingSpeed !== null && relativeClosingSpeed > 0
      ? Math.round((latestRelativeDistance / relativeClosingSpeed) * 1000)
      : null;
  const growthSteps = areaGrowthStepCounts(track);
  const pseudoDepth = estimatePseudoDepthFromHistory(current, track, {
    minStableFrames: resolvedOptions.min_stable_frames,
    motionStability: resolvedOptions.motion_stability
  });
  const approaching =
    stableFrames >= resolvedOptions.min_stable_frames &&
    areaGrowthRatio !== null &&
    areaGrowthRatio >= 0.18 &&
    (areaGrowthPerSecond ?? 0) >= 0.12 &&
    growthSteps.growing >= resolvedOptions.min_stable_frames - 1 &&
    growthSteps.shrinking === 0;

  return {
    tracking: {
      object_id: trackingKeyForDetection(current),
      stable_frames: stableFrames,
      stable_ms: stableMs,
      approaching,
      area_growth_ratio: areaGrowthRatio,
      area_growth_per_second: areaGrowthPerSecond,
      time_to_collision_ms: timeToCollisionMs,
      time_to_collision_source: timeToCollisionMs === null ? null : "bbox_scale_heuristic",
      distance_m: pseudoDepth?.distance_m ?? null,
      distance_source: pseudoDepth?.source ?? null,
      distance_confidence: pseudoDepth?.confidence ?? null,
      approach_state: pseudoDepth?.approach_state ?? (approaching ? "approaching" : "unknown")
    }
  };
}

function isReportableTactileDamage(detection: TwoModelDetection): boolean {
  return (
    (detection.model_key === "custom_tactile" || detection.model_key === "unified_walksafe") &&
    REPORTABLE_TACTILE_DAMAGE_CLASSES.has(detection.class_name)
  );
}

function isTactileDamageAreaDetail(detection: TwoModelDetection): boolean {
  return (
    (detection.model_key === "custom_tactile" || detection.model_key === "unified_walksafe") &&
    detection.class_name === "tactile_damage_area"
  );
}

function isNormalTactile(detection: TwoModelDetection): boolean {
  return detection.category === "tactile_normal" || detection.class_name === "normal_tactile_block";
}

function isSurfaceHazard(detection: TwoModelDetection): boolean {
  return detection.category === "surface_hazard" || SURFACE_HAZARD_CLASSES.has(detection.class_name);
}

function isPathObstacle(detection: TwoModelDetection): boolean {
  return detection.category === "obstruction" || PATH_OBSTACLE_CLASSES.has(detection.class_name);
}

function isPathGuidance(detection: TwoModelDetection): boolean {
  return detection.category === "path_guidance" || PATH_GUIDANCE_CLASSES.has(detection.class_name);
}

export function isStableTracking(tracking: RiskObjectTracking): boolean {
  return (
    (tracking.stable_frames ?? 0) >= MIN_STABLE_TRACKING_FRAMES &&
    (tracking.stable_ms ?? 0) >= MIN_STABLE_TRACKING_MS
  );
}

export function modelEstimateForRiskContext(context: RiskEvaluationContext): RiskDepthContext | null {
  const tracking = context.tracking;
  if (
    !tracking ||
    !isStableTracking(tracking) ||
    tracking.distance_source !== "model_estimate" ||
    (tracking.distance_confidence ?? 0) < MODEL_ESTIMATE_MIN_CONFIDENCE ||
    typeof tracking.distance_m !== "number" ||
    !Number.isFinite(tracking.distance_m) ||
    tracking.distance_m <= 0 ||
    tracking.distance_m > 50
  ) {
    return null;
  }

  return {
    distance_m: tracking.distance_m,
    confidence: tracking.distance_confidence,
    source: "model_estimate"
  };
}

export function formatModelEstimateStatusText(context: RiskEvaluationContext): string | null {
  const estimate = modelEstimateForRiskContext(context);
  return estimate && typeof estimate.distance_m === "number"
    ? `단안 bbox 추정 거리 약 ${estimate.distance_m.toFixed(1)}미터`
    : null;
}

export function formatBBoxTtcStatusText(context: RiskEvaluationContext): string | null {
  const tracking = context.tracking;
  if (
    tracking?.time_to_collision_source !== "bbox_scale_heuristic" ||
    typeof tracking.time_to_collision_ms !== "number" ||
    !Number.isFinite(tracking.time_to_collision_ms) ||
    tracking.time_to_collision_ms < 0
  ) {
    return null;
  }

  return `bbox 크기 변화 추정 충돌 여유 약 ${(tracking.time_to_collision_ms / 1000).toFixed(1)}초`;
}

function closeRiskDistanceM(context: RiskEvaluationContext): number | null {
  if (context.depth?.source && CLOSE_RISK_DISTANCE_SOURCES.has(context.depth.source)) {
    return context.depth.distance_m ?? null;
  }

  if (context.tracking?.distance_source && CLOSE_RISK_DISTANCE_SOURCES.has(context.tracking.distance_source)) {
    return context.tracking.distance_m ?? null;
  }

  return null;
}

function isCloseContext(context: RiskEvaluationContext): boolean {
  const distance = closeRiskDistanceM(context);
  return typeof distance === "number" && distance > 0 && distance <= 2.2;
}

function ttcSignalForTracking(tracking: RiskObjectTracking): TtcRiskBucket {
  if (tracking.distance_source === "model_estimate") {
    const hasTrustedEstimate =
      (tracking.distance_confidence ?? 0) >= MODEL_ESTIMATE_MIN_CONFIDENCE &&
      isStableTracking(tracking);
    if (!hasTrustedEstimate) {
      return "none";
    }
  }
  const bucket = ttcRiskBucketForMilliseconds(tracking.time_to_collision_ms);
  // Bbox scale is a monocular trend heuristic, not a physical TTC sensor. It may warn,
  // but STOP/high severity requires an independently trusted close-depth signal.
  if (bucket === "stop" && tracking.time_to_collision_source !== "trusted_depth") {
    return "warning";
  }
  return bucket;
}

function riskLevelForTtcBucket(bucket: TtcRiskBucket): Exclude<RiskLevel, "none"> {
  if (bucket === "stop") {
    return "high";
  }
  if (bucket === "warning") {
    return "medium";
  }
  return "low";
}

function generalRiskSignal(context: RiskEvaluationContext): GeneralRiskSignal | null {
  const tracking = context.tracking;
  const pathRelation = context.segmentation?.path_relation;
  if (!tracking || !isStableTracking(tracking)) {
    return null;
  }

  const sensorDepthClose =
    context.depth?.source === "sensor_depth" &&
    (context.depth.confidence ?? 0) >= 0.5 &&
    isCloseContext(context) &&
    pathRelation !== "off_path";

  if (sensorDepthClose) {
    return {
      risk_type: "blocking_object",
      risk_level: (context.depth?.distance_m ?? 99) <= 1.2 ? "high" : "medium",
      alertable: true,
      reason: "실제 depth 센서가 가까운 전방 객체를 표시했습니다."
    };
  }

  const isBlocking =
    tracking.blocking_path === true ||
    tracking.projected_path_intersection === true ||
    pathRelation === "on_path";
  const ttcBucket = ttcSignalForTracking(tracking);

  if (isBlocking) {
    return {
      risk_type: "blocking_object",
      risk_level: isCloseContext(context) || ttcBucket === "stop" ? "high" : "medium",
      alertable: true,
      reason: "추적/경로 정보가 보행 경로 차단 가능성을 표시했습니다."
    };
  }

  const timeToCollision = tracking.time_to_collision_ms;
  const hasAreaGrowthSignal =
    (tracking.area_growth_ratio ?? 0) >= 0.25 || (tracking.area_growth_per_second ?? 0) >= 0.3;
  const isApproaching = tracking.approaching === true;

  if (isApproaching) {
    if (ttcBucket === "aware") {
      return {
        risk_type: "approaching_object",
        risk_level: riskLevelForTtcBucket(ttcBucket),
        alertable: false,
        reason: "10~30초 TTC 후보는 AWARE 내부 상태로만 유지합니다."
      };
    }
    if (ttcBucket === "stop" || ttcBucket === "warning") {
      return {
        risk_type: "approaching_object",
        risk_level: riskLevelForTtcBucket(ttcBucket),
        alertable: true,
        reason:
          tracking.time_to_collision_source === "trusted_depth"
            ? "안정적인 추적과 신뢰 가능한 depth TTC가 접근 위험을 표시했습니다."
            : "안정적인 추적과 bbox 크기 변화 기반 TTC 보조 추정이 접근 주의를 표시했습니다."
      };
    }
    if (typeof timeToCollision === "number" && Number.isFinite(timeToCollision)) {
      return null;
    }
    if (!hasAreaGrowthSignal || !modelEstimateForRiskContext(context)) {
      return null;
    }
    return {
      risk_type: "approaching_object",
      risk_level: "medium",
      alertable: true,
      reason: "안정적인 3프레임 bbox/known-height 추정이 접근 중인 객체를 표시했습니다."
    };
  }

  return null;
}

function stablePathRiskSignal(context: RiskEvaluationContext): GeneralRiskSignal | null {
  const tracking = context.tracking;
  if (!tracking || !isStableTracking(tracking)) {
    return null;
  }
  const pathRelation = context.segmentation?.path_relation;
  const intersectsPath =
    tracking.blocking_path === true ||
    tracking.projected_path_intersection === true ||
    pathRelation === "on_path" ||
    (pathRelation === "near_path" && isCloseContext(context));
  return intersectsPath ? generalRiskSignal(context) : null;
}

function messageForGeneralRisk(signal: GeneralRiskSignal, detection: TwoModelDetection): string {
  if (!signal.alertable) {
    return "";
  }
  if (signal.risk_type === "approaching_object") {
    return `${detection.class_name} 접근 중입니다. 전방을 확인하세요.`;
  }

  return `${detection.class_name}이 보행 경로를 막을 수 있습니다. 천천히 이동하세요.`;
}

export function evaluateDetectionRisk(detection: DetectionEvent, context: RiskEvaluationContext = {}): RiskDecision {
  const policy = V1_POLICY[detection.class_name];
  const shouldAlert = policy.message !== null;
  const contextSignal = shouldAlert ? generalRiskSignal(context) : null;

  if (contextSignal) {
    return {
      alertable: contextSignal.alertable,
      reportable: policy.reportable,
      risk_type: contextSignal.risk_type,
      risk_level: contextSignal.risk_level,
      reason: contextSignal.reason,
      recommended_message: contextSignal.alertable ? policy.message : null
    };
  }

  return {
    alertable: shouldAlert,
    reportable: policy.reportable,
    risk_type: policy.risk_type,
    risk_level: policy.risk_level,
    reason: policy.reportable
      ? "타일/점자블록 손상은 자동 신고 대상이며 기본 사용자 경고 대상은 아닙니다."
      : "v1 보행 위험 클래스입니다.",
    recommended_message: policy.message
  };
}

export function evaluateTwoModelDetectionRisk(
  detection: TwoModelDetection,
  context: RiskEvaluationContext = {}
): RiskDecision {
  if (isReportableTactileDamage(detection)) {
    return {
      alertable: false,
      reportable: true,
      risk_type: "report_only_damage",
      risk_level: "medium",
      reason: "v2 손상 점자블록은 자동 신고 전용으로 처리합니다.",
      recommended_message: null
    };
  }

  if (isTactileDamageAreaDetail(detection)) {
    return {
      alertable: false,
      reportable: false,
      risk_type: "display_only",
      risk_level: "low",
      reason: "손상 영역 bbox는 보조 정보이며 현재 신고 기준은 손상 점자블록 단위입니다.",
      recommended_message: null
    };
  }

  if (isNormalTactile(detection)) {
    return {
      alertable: false,
      reportable: false,
      risk_type: "no_risk",
      risk_level: "none",
      reason: "정상 점자블록은 경고 대상이 아닙니다.",
      recommended_message: null
    };
  }

  if (isPathGuidance(detection)) {
    return {
      alertable: false,
      reportable: false,
      risk_type: "no_risk",
      risk_level: "none",
      reason: "횡단보도/경로 안내 클래스는 위험 경고가 아닌 안내 후보로 처리합니다.",
      recommended_message: null
    };
  }

  if (isSurfaceHazard(detection)) {
    const signal = stablePathRiskSignal(context);
    return {
      alertable: signal?.alertable ?? false,
      reportable: false,
      risk_type: "surface_hazard",
      risk_level: signal?.risk_level ?? "low",
      reason: signal?.reason ?? "보행 표면 위험은 3프레임 안정성과 경로/ROI 조건을 기다립니다.",
      recommended_message: signal?.alertable
        ? detection.class_name === "curb_step"
          ? "전방 보도 턱입니다. 발밑을 확인하세요."
          : "전방 보도가 고르지 않습니다. 발밑을 주의하세요."
        : null
    };
  }

  if (isPathObstacle(detection)) {
    const signal = stablePathRiskSignal(context);
    return {
      alertable: signal?.alertable ?? false,
      reportable: false,
      risk_type: "path_obstacle",
      risk_level: signal?.risk_level ?? "low",
      reason: signal?.reason ?? "보행 경로 장애물은 3프레임 안정성과 경로/ROI 조건을 기다립니다.",
      recommended_message: signal?.alertable ? "전방 방치 킥보드 장애물입니다. 천천히 피하세요." : null
    };
  }

  const contextSignal = generalRiskSignal(context);
  if (contextSignal) {
    return {
      alertable: contextSignal.alertable,
      reportable: false,
      risk_type: contextSignal.risk_type,
      risk_level: contextSignal.risk_level,
      reason: contextSignal.reason,
      recommended_message: contextSignal.alertable ? messageForGeneralRisk(contextSignal, detection) : null
    };
  }

  return {
    alertable: false,
    reportable: false,
    risk_type: "display_only",
    risk_level: "low",
    reason: isWalkSafeCoreClassName(detection.class_name)
      ? "WalkSafe 핵심 클래스는 안정적인 추적/경로 근거가 생길 때까지 표시 전용으로 처리합니다."
      : "v2 general 객체는 추적/예측 정보 없이는 표시 전용으로 처리합니다.",
    recommended_message: null
  };
}
