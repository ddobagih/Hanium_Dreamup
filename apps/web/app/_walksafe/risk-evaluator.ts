import type { DetectionEvent } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";

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
  blocking_path?: boolean;
  projected_path_intersection?: boolean;
  time_to_collision_ms?: number | null;
  distance_m?: number | null;
};

export type RiskDepthContext = {
  distance_m?: number | null;
  confidence?: number | null;
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

const TACTILE_DAMAGE_CLASSES = new Set(["tactile_damage_area", "damaged_tactile_block"]);

function highConfidenceLevel(confidence: number, fallback: RiskLevel): RiskLevel {
  if (confidence >= 0.9) {
    return "high";
  }

  return fallback;
}

function isTactileDamage(detection: TwoModelDetection): boolean {
  return detection.category === "tactile_damage" || TACTILE_DAMAGE_CLASSES.has(detection.class_name);
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
  const isApproaching =
    tracking.approaching === true &&
    (timeToCollision === null || timeToCollision === undefined || timeToCollision <= 3500 || isCloseContext(context));

  if (isApproaching) {
    return {
      risk_type: "approaching_object",
      risk_level: timeToCollision !== null && timeToCollision !== undefined && timeToCollision <= 1800 ? "high" : "medium",
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
  if (isTactileDamage(detection)) {
    return {
      alertable: false,
      reportable: true,
      risk_type: "report_only_damage",
      risk_level: highConfidenceLevel(detection.confidence, "medium"),
      reason: "v2 tactile damage는 자동 신고 전용으로 처리합니다.",
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
