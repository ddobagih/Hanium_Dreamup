/**
 * Converts evaluated risk into short spatial guidance without promoting heuristics to metric facts.
 * Bboxes provide qualitative direction; step counts require distance from an approved metric source.
 */
import type { NormalizedBBoxV2 } from "@/types/inference-v2";
import type { RiskDecision, RiskEvaluationContext, RiskLevel, RiskType } from "./risk-evaluator";

export type BBoxDirection = "left" | "front" | "right" | "unknown";
export type BBoxVerticalPosition = "upper" | "middle" | "lower" | "foot" | "unknown";

const DEFAULT_STEP_LENGTH_M = 0.65;
const MIN_STEP_LENGTH_M = 0.3;
const MAX_STEP_LENGTH_M = 1.2;
const METRIC_DISTANCE_GUIDANCE_SOURCES = new Set(["sensor_depth"]);

function safeStepLengthM(stepLengthM: number | null | undefined): number {
  if (typeof stepLengthM !== "number" || !Number.isFinite(stepLengthM)) {
    return DEFAULT_STEP_LENGTH_M;
  }

  if (stepLengthM < MIN_STEP_LENGTH_M || stepLengthM > MAX_STEP_LENGTH_M) {
    return DEFAULT_STEP_LENGTH_M;
  }

  return stepLengthM;
}

export function directionFromBBox(bbox: NormalizedBBoxV2 | null | undefined): BBoxDirection {
  if (!bbox) {
    return "unknown";
  }

  if (
    !Number.isFinite(bbox.x) ||
    !Number.isFinite(bbox.y) ||
    !Number.isFinite(bbox.width) ||
    !Number.isFinite(bbox.height) ||
    bbox.x < 0 ||
    bbox.y < 0 ||
    bbox.width <= 0 ||
    bbox.height <= 0 ||
    bbox.x + bbox.width > 1 ||
    bbox.y + bbox.height > 1
  ) {
    return "unknown";
  }

  const centerX = bbox.x + bbox.width / 2;
  if (!Number.isFinite(centerX)) {
    return "unknown";
  }

  if (centerX < 0.4) {
    return "left";
  }

  if (centerX > 0.6) {
    return "right";
  }

  return "front";
}

export function phraseForBBoxDirection(bbox: NormalizedBBoxV2 | null | undefined): string | null {
  const direction = directionFromBBox(bbox);

  if (direction === "left") {
    return "왼쪽";
  }

  if (direction === "right") {
    return "오른쪽";
  }

  if (direction === "front") {
    return "전방";
  }

  return null;
}

export function verticalPositionFromBBox(bbox: NormalizedBBoxV2 | null | undefined): BBoxVerticalPosition {
  if (!bbox || directionFromBBox(bbox) === "unknown") {
    return "unknown";
  }

  const centerY = bbox.y + bbox.height / 2;
  const bottomY = bbox.y + bbox.height;
  if (!Number.isFinite(centerY) || !Number.isFinite(bottomY)) {
    return "unknown";
  }

  if (bottomY >= 0.88) {
    return "foot";
  }

  if (centerY >= 0.68) {
    return "lower";
  }

  if (centerY <= 0.34) {
    return "upper";
  }

  return "middle";
}

export function phraseForBBoxVerticalPosition(bbox: NormalizedBBoxV2 | null | undefined): string | null {
  const position = verticalPositionFromBBox(bbox);

  if (position === "foot") {
    return "발밑";
  }

  if (position === "lower") {
    return "하단";
  }

  return null;
}

export function actionForRiskType(riskType: RiskType, riskLevel: RiskLevel = "medium"): string | null {
  if (riskType === "blocking_object") {
    return riskLevel === "high" ? "멈추세요" : "주의해서 피하세요";
  }

  if (riskType === "approaching_object" || riskType === "path_obstacle") {
    if (riskLevel === "high") {
      return "멈추고 피하세요";
    }

    return "피하세요";
  }

  if (riskType === "surface_hazard") {
    if (riskLevel === "high") {
      return "발밑을 확인하고 천천히 이동하세요";
    }

    return "천천히 이동하세요";
  }

  return null;
}

export function phraseForApproxSteps(
  distanceM: number | null | undefined,
  stepLengthM?: number | null
): string | null {
  if (typeof distanceM !== "number" || !Number.isFinite(distanceM) || distanceM <= 0) {
    return null;
  }

  if (distanceM <= 0.3) {
    return "바로 앞";
  }

  const steps = Math.max(1, Math.round(distanceM / safeStepLengthM(stepLengthM)));
  return `약 ${steps}보 앞`;
}

export function isMetricDistanceGuidanceSource(source: string | null | undefined): boolean {
  return typeof source === "string" && METRIC_DISTANCE_GUIDANCE_SOURCES.has(source);
}

type RiskGuidanceMessageOptions = {
  riskType: RiskType;
  riskLevel?: RiskLevel;
  bbox?: NormalizedBBoxV2 | null;
  distanceM?: number | null;
  distanceSource?: string | null;
  stepLengthM?: number | null;
  label?: string | null;
  fallback?: string | null;
};

export function buildRiskGuidanceMessage({
  riskType,
  riskLevel,
  bbox,
  distanceM,
  distanceSource,
  stepLengthM,
  label,
  fallback
}: RiskGuidanceMessageOptions): string | null {
  const action = actionForRiskType(riskType, riskLevel);
  if (!action) {
    return fallback ?? null;
  }

  const prefix = [
    phraseForBBoxDirection(bbox),
    phraseForBBoxVerticalPosition(bbox),
    phraseForApproxSteps(isMetricDistanceGuidanceSource(distanceSource) ? distanceM : null, stepLengthM),
    label
  ]
    .filter((part): part is string => Boolean(part))
    .join(" ");

  return prefix ? `${prefix}. ${action}.` : `${action}.`;
}

export type RiskGuidanceCandidate<T> = {
  item: T;
  risk: RiskDecision | null;
  context?: RiskEvaluationContext;
  confidence?: number;
  index?: number;
};

const RISK_LEVEL_PRIORITY: Record<RiskLevel, number> = {
  none: 0,
  low: 1,
  medium: 2,
  high: 3
};

const RISK_TYPE_PRIORITY: Record<RiskType, number> = {
  no_risk: 0,
  display_only: 0,
  report_only_damage: 1,
  surface_hazard: 2,
  path_obstacle: 3,
  approaching_object: 4,
  blocking_object: 5
};

function distancePriority(context: RiskEvaluationContext | undefined): number {
  const distance = isMetricDistanceGuidanceSource(context?.depth?.source) ? context?.depth?.distance_m : null;
  if (typeof distance !== "number" || !Number.isFinite(distance) || distance <= 0) {
    return 0;
  }

  return Math.max(0, 50 - distance);
}

export function selectRiskGuidanceCandidate<T>(
  candidates: readonly RiskGuidanceCandidate<T>[]
): RiskGuidanceCandidate<T> | null {
  const alertableCandidates = candidates.filter((candidate) => candidate.risk?.alertable);
  if (alertableCandidates.length === 0) {
    return null;
  }

  return [...alertableCandidates].sort((a, b) => {
    const aRisk = a.risk as RiskDecision;
    const bRisk = b.risk as RiskDecision;
    const levelDelta = RISK_LEVEL_PRIORITY[bRisk.risk_level] - RISK_LEVEL_PRIORITY[aRisk.risk_level];
    if (levelDelta !== 0) {
      return levelDelta;
    }

    const typeDelta = RISK_TYPE_PRIORITY[bRisk.risk_type] - RISK_TYPE_PRIORITY[aRisk.risk_type];
    if (typeDelta !== 0) {
      return typeDelta;
    }

    const distanceDelta = distancePriority(b.context) - distancePriority(a.context);
    if (distanceDelta !== 0) {
      return distanceDelta;
    }

    const confidenceDelta = (b.confidence ?? 0) - (a.confidence ?? 0);
    if (confidenceDelta !== 0) {
      return confidenceDelta;
    }

    return (a.index ?? 0) - (b.index ?? 0);
  })[0];
}
