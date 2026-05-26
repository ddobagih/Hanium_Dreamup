import type { DetectionEvent } from "@/types/inference";
import type { DetectionDistanceSource, TwoModelDetection } from "@/types/inference-v2";

export type RiskType =
  | "no_risk"
  | "display_only"
  | "report_only_damage"
  | "surface_hazard"
  | "path_obstacle"
  | "approaching_object"
  | "blocking_object";

export type RiskLevel = "none" | "low" | "medium" | "high";

export type PathRelation = "on_path" | "near_path" | "off_path" | "unknown";

export type RiskObjectTracking = {
  object_id?: string;
  stable_frames?: number;
  stable_ms?: number;
  approaching?: boolean;
  area_growth_ratio?: number | null;
  area_growth_per_second?: number | null;
  blocking_path?: boolean;
  projected_path_intersection?: boolean;
  time_to_collision_ms?: number | null;
  distance_m?: number | null;
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

const DEFAULT_BBOX_HISTORY_OPTIONS: Required<BBoxHistoryRiskOptions> = {
  max_history_ms: 2500,
  max_sample_gap_ms: 1300,
  min_iou: 0.1,
  max_center_shift: 0.3,
  min_stable_frames: 3
};

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
      time_to_collision_ms: timeToCollisionMs
    }
  };
}

function highConfidenceLevel(confidence: number, fallback: RiskLevel): RiskLevel {
  if (confidence >= 0.9) {
    return "high";
  }

  return fallback;
}

function isReportableTactileDamage(detection: TwoModelDetection): boolean {
  return detection.model_key === "custom_tactile" && REPORTABLE_TACTILE_DAMAGE_CLASSES.has(detection.class_name);
}

function isTactileDamageAreaDetail(detection: TwoModelDetection): boolean {
  return detection.model_key === "custom_tactile" && detection.class_name === "tactile_damage_area";
}

function isNormalTactile(detection: TwoModelDetection): boolean {
  return detection.category === "tactile_normal" || detection.class_name === "normal_tactile_block";
}

function isStableTracking(tracking: RiskObjectTracking): boolean {
  return (tracking.stable_frames ?? 0) >= 3 || (tracking.stable_ms ?? 0) >= 700;
}

function isCloseContext(context: RiskEvaluationContext): boolean {
  const distance = context.depth?.distance_m ?? context.tracking?.distance_m ?? null;
  return typeof distance === "number" && distance > 0 && distance <= 2.2;
}

function generalRiskSignal(context: RiskEvaluationContext): GeneralRiskSignal | null {
  const tracking = context.tracking;
  if (!tracking || !isStableTracking(tracking)) {
    return null;
  }

  const pathRelation = context.segmentation?.path_relation;
  const isBlocking =
    tracking.blocking_path === true ||
    tracking.projected_path_intersection === true ||
    pathRelation === "on_path";

  if (isBlocking) {
    return {
      risk_type: "blocking_object",
      risk_level: isCloseContext(context) ? "high" : "medium",
      reason: "추적/경로 정보가 보행 경로 차단 가능성을 표시했습니다."
    };
  }

  const timeToCollision = tracking.time_to_collision_ms;
  const hasAreaGrowthSignal =
    (tracking.area_growth_ratio ?? 0) >= 0.25 || (tracking.area_growth_per_second ?? 0) >= 0.3;
  const hasCollisionSignal =
    timeToCollision === null ||
    timeToCollision === undefined ||
    timeToCollision <= 3500 ||
    hasAreaGrowthSignal ||
    isCloseContext(context);
  const isApproaching =
    tracking.approaching === true &&
    hasCollisionSignal;

  if (isApproaching) {
    return {
      risk_type: "approaching_object",
      risk_level:
        (timeToCollision !== null && timeToCollision !== undefined && timeToCollision <= 1800) ||
        (tracking.area_growth_per_second ?? 0) >= 0.6
          ? "high"
          : "medium",
      reason: "안정적인 추적 정보가 접근 중인 객체를 표시했습니다."
    };
  }

  return null;
}

function messageForGeneralRisk(signal: GeneralRiskSignal, detection: TwoModelDetection): string {
  if (signal.risk_type === "approaching_object") {
    return `${detection.class_name} 접근 중입니다. 전방을 확인하세요.`;
  }

  return `${detection.class_name}이 보행 경로를 막을 수 있습니다. 천천히 이동하세요.`;
}

export function evaluateDetectionRisk(detection: DetectionEvent, context: RiskEvaluationContext = {}): RiskDecision {
  const policy = V1_POLICY[detection.class_name];
  const riskLevel = highConfidenceLevel(detection.confidence, policy.risk_level);
  const shouldAlert = policy.message !== null;
  const contextSignal = shouldAlert ? generalRiskSignal(context) : null;

  if (contextSignal) {
    return {
      alertable: true,
      reportable: policy.reportable,
      risk_type: contextSignal.risk_type,
      risk_level: contextSignal.risk_level,
      reason: contextSignal.reason,
      recommended_message: policy.message
    };
  }

  return {
    alertable: shouldAlert,
    reportable: policy.reportable,
    risk_type: policy.risk_type,
    risk_level: riskLevel,
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
      risk_level: highConfidenceLevel(detection.confidence, "medium"),
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

  const contextSignal = generalRiskSignal(context);
  if (contextSignal) {
    return {
      alertable: true,
      reportable: false,
      risk_type: contextSignal.risk_type,
      risk_level: contextSignal.risk_level,
      reason: contextSignal.reason,
      recommended_message: messageForGeneralRisk(contextSignal, detection)
    };
  }

  return {
    alertable: false,
    reportable: false,
    risk_type: "display_only",
    risk_level: "low",
    reason: "v2 general 객체는 추적/예측 정보 없이는 표시 전용으로 처리합니다.",
    recommended_message: null
  };
}
