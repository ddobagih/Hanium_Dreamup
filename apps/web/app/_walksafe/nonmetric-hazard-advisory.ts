/**
 * Conservative camera-relative advisory for browsers without aligned metric depth.
 * It never supplies route projection, distance, steering, or reporting authority.
 */
import type { TwoModelDetection } from "@/types/inference-v2";
import { directionFromBBox, type BBoxDirection } from "./risk-guidance";
import type { RiskEvaluationContext, RiskLevel } from "./risk-evaluator";

export const NON_METRIC_HAZARD_ADVISORY_CAPABILITY_LABEL = "카메라 보조 경고 · TMAP 경로 유지";
export const NON_METRIC_HAZARD_ADVISORY_TIER = "CAMERA_NON_METRIC_ADVISORY" as const;

export const NON_METRIC_HAZARD_ADVISORY_THRESHOLDS = {
  minimumConfidence: 0.6,
  minimumStableFrames: 3,
  minimumStableMs: 700,
  minimumMotionStability: 0.35,
  minimumBboxArea: 0.02,
  minimumBboxBottom: 0.45,
  maximumDetectionAgeMs: 1_200,
  maximumConsecutiveFrameGapMs: 1_200,
  maximumDirections: 3
} as const;

export type NonMetricHazardDirection = Exclude<BBoxDirection, "unknown">;

export type NonMetricHazardAdvisory = {
  tier: typeof NON_METRIC_HAZARD_ADVISORY_TIER;
  key: string;
  detection: TwoModelDetection;
  direction: NonMetricHazardDirection;
  message: string;
  riskLevel: Extract<RiskLevel, "low">;
  lastSeenAtMs: number;
  score: number;
  continuity: Pick<NonMetricAdvisoryContinuity, "consecutiveFrames" | "stableMs">;
};

export type NonMetricHazardAdvisoryRuntimeGate = {
  cameraReady: boolean;
  rearCamera: boolean;
  navigationActive: boolean;
  tmapGuidanceAvailable: boolean;
  detectionAvailable: boolean;
  sessionActive: boolean;
  pageVisible: boolean;
};

type AdvisoryOptions = {
  nowMs: number;
  motionStability: number | null;
};

type AdvisoryEvaluationOptions = AdvisoryOptions & {
  continuity: NonMetricAdvisoryContinuity;
};

type AdvisoryCandidate = {
  detection: TwoModelDetection;
  context: RiskEvaluationContext;
  riskAlertable?: boolean;
  continuity: NonMetricAdvisoryContinuity;
};

export type NonMetricAdvisoryContinuity = {
  trackId: string;
  consecutiveFrames: number;
  stableMs: number;
  firstCapturedAtMs: number;
  lastCapturedAtMs: number;
};

export type NonMetricAdvisoryContinuityState = {
  lastFrameSequence: number;
  evidence: NonMetricAdvisoryContinuity[];
};

export type NonMetricAdvisoryFrameObservation = {
  sequence: number;
  observedAtMs: number;
  hasDetections: boolean;
};

export type NonMetricAdvisoryFrameCandidate = {
  trackId: string;
  capturedAtMs: number;
};

export const EMPTY_NON_METRIC_ADVISORY_CONTINUITY_STATE: NonMetricAdvisoryContinuityState = {
  lastFrameSequence: 0,
  evidence: []
};

const ADVISORY_CLASS_NAMES = new Set([
  "normal_tactile_block",
  "person",
  "bicycle",
  "car",
  "motorcycle",
  "bus",
  "truck",
  "curb_step",
  "uneven_sidewalk",
  "e_scooter_obstruction"
]);

const ADVISORY_LABELS: Record<string, string> = {
  person: "보행자",
  bicycle: "자전거",
  car: "차량",
  motorcycle: "오토바이",
  bus: "버스",
  truck: "트럭",
  curb_step: "보도 턱",
  uneven_sidewalk: "고르지 않은 보도",
  e_scooter_obstruction: "방치 킥보드"
};

const DIRECTION_ORDER: Record<NonMetricHazardDirection, number> = {
  front: 0,
  left: 1,
  right: 2
};

function directionLabel(direction: NonMetricHazardDirection): string {
  if (direction === "left") return "왼쪽";
  if (direction === "right") return "오른쪽";
  return "정면";
}

function advisoryMessage(detection: TwoModelDetection, direction: NonMetricHazardDirection): string {
  const directionText = directionLabel(direction);
  if (detection.class_name === "normal_tactile_block") {
    return `카메라 기준 ${directionText}에 점자블록이 감지됐습니다. TMAP 길 안내를 기준으로 주변을 확인하세요.`;
  }
  const label = ADVISORY_LABELS[detection.class_name] ?? "장애물";
  return `카메라 기준 ${directionText}에 ${label} 감지. TMAP 길 안내를 기준으로 주변을 확인하세요.`;
}

function finiteMotionStability(value: number | null): number | null {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 1 ? value : null;
}

export function isNonMetricHazardAdvisoryRuntimeActive(gate: NonMetricHazardAdvisoryRuntimeGate): boolean {
  return gate.cameraReady && gate.rearCamera && gate.navigationActive && gate.tmapGuidanceAvailable && gate.detectionAvailable &&
    gate.sessionActive && gate.pageVisible;
}

export function resolveNonMetricAdvisoryActivity(
  existingRiskActive: boolean,
  advisoryCount: number
): { riskActive: boolean; advisoryActive: boolean } {
  return {
    riskActive: existingRiskActive,
    advisoryActive: Number.isFinite(advisoryCount) && advisoryCount > 0
  };
}

export function advanceNonMetricAdvisoryContinuity(
  previous: NonMetricAdvisoryContinuityState,
  frame: NonMetricAdvisoryFrameObservation,
  candidates: readonly NonMetricAdvisoryFrameCandidate[]
): { state: NonMetricAdvisoryContinuityState; evidence: NonMetricAdvisoryContinuity[] } {
  if (
    !Number.isSafeInteger(frame.sequence) ||
    frame.sequence <= previous.lastFrameSequence ||
    !Number.isFinite(frame.observedAtMs)
  ) {
    return { state: previous, evidence: previous.evidence };
  }

  const frameIsConsecutive = frame.sequence === previous.lastFrameSequence + 1;
  const previousByTrack = frameIsConsecutive && frame.hasDetections
    ? new Map(previous.evidence.map((item) => [item.trackId, item]))
    : new Map<string, NonMetricAdvisoryContinuity>();
  const nextEvidence: NonMetricAdvisoryContinuity[] = [];
  const seenTrackIds = new Set<string>();

  if (frame.hasDetections) {
    for (const candidate of candidates) {
      if (
        !candidate.trackId ||
        seenTrackIds.has(candidate.trackId) ||
        !Number.isFinite(candidate.capturedAtMs) ||
        candidate.capturedAtMs !== frame.observedAtMs
      ) {
        continue;
      }
      seenTrackIds.add(candidate.trackId);
      const prior = previousByTrack.get(candidate.trackId);
      const continuesTrack = Boolean(
        prior &&
        candidate.capturedAtMs > prior.lastCapturedAtMs &&
        candidate.capturedAtMs - prior.lastCapturedAtMs <=
          NON_METRIC_HAZARD_ADVISORY_THRESHOLDS.maximumConsecutiveFrameGapMs
      );
      const firstCapturedAtMs = continuesTrack && prior ? prior.firstCapturedAtMs : candidate.capturedAtMs;
      nextEvidence.push({
        trackId: candidate.trackId,
        consecutiveFrames: continuesTrack && prior ? prior.consecutiveFrames + 1 : 1,
        stableMs: candidate.capturedAtMs - firstCapturedAtMs,
        firstCapturedAtMs,
        lastCapturedAtMs: candidate.capturedAtMs
      });
    }
  }

  const state = { lastFrameSequence: frame.sequence, evidence: nextEvidence };
  return { state, evidence: nextEvidence };
}

export function evaluateNonMetricHazardAdvisory(
  detection: TwoModelDetection,
  context: RiskEvaluationContext,
  options: AdvisoryEvaluationOptions
): NonMetricHazardAdvisory | null {
  const threshold = NON_METRIC_HAZARD_ADVISORY_THRESHOLDS;
  const capturedAtMs = Date.parse(detection.captured_at);
  const ageMs = options.nowMs - capturedAtMs;
  const tracking = context.tracking;
  const motionStability = finiteMotionStability(options.motionStability);
  const bboxArea = detection.bbox.width * detection.bbox.height;
  const bboxBottom = detection.bbox.y + detection.bbox.height;
  const direction = directionFromBBox(detection.bbox);

  if (!ADVISORY_CLASS_NAMES.has(detection.class_name)) return null;
  if (!Number.isFinite(options.nowMs) || !Number.isFinite(capturedAtMs) || ageMs < 0 || ageMs > threshold.maximumDetectionAgeMs) {
    return null;
  }
  if (!Number.isFinite(detection.confidence) || detection.confidence < threshold.minimumConfidence) return null;
  if (
    !tracking?.object_id ||
    options.continuity.trackId !== tracking.object_id ||
    options.continuity.consecutiveFrames < threshold.minimumStableFrames ||
    options.continuity.stableMs < threshold.minimumStableMs
  ) {
    return null;
  }
  if (motionStability !== null && motionStability < threshold.minimumMotionStability) return null;
  if (!Number.isFinite(bboxArea) || bboxArea < threshold.minimumBboxArea || bboxBottom < threshold.minimumBboxBottom) {
    return null;
  }
  // Trusted aligned depth stays on the metric path and must not produce a duplicate degraded warning.
  if (context.depth?.source === "sensor_depth" || context.depth?.source === "manual_fixture") return null;
  if (direction === "unknown") return null;

  const score = bboxArea + detection.confidence * 0.1 + (tracking.approaching === true ? 0.1 : 0);
  return {
    tier: NON_METRIC_HAZARD_ADVISORY_TIER,
    key: `nonmetric:${tracking.object_id}`,
    detection,
    direction,
    message: advisoryMessage(detection, direction),
    riskLevel: "low",
    lastSeenAtMs: capturedAtMs,
    score,
    continuity: {
      consecutiveFrames: options.continuity.consecutiveFrames,
      stableMs: options.continuity.stableMs
    }
  };
}

export function selectNonMetricHazardAdvisories(
  candidates: readonly AdvisoryCandidate[],
  options: AdvisoryOptions
): NonMetricHazardAdvisory[] {
  const strongestByDirection = new Map<NonMetricHazardDirection, NonMetricHazardAdvisory>();
  for (const candidate of candidates) {
    if (candidate.riskAlertable) continue;
    const advisory = evaluateNonMetricHazardAdvisory(candidate.detection, candidate.context, {
      ...options,
      continuity: candidate.continuity
    });
    if (!advisory) continue;
    const current = strongestByDirection.get(advisory.direction);
    if (!current || advisory.score > current.score) {
      strongestByDirection.set(advisory.direction, advisory);
    }
  }

  return [...strongestByDirection.values()]
    .sort((left, right) => DIRECTION_ORDER[left.direction] - DIRECTION_ORDER[right.direction])
    .slice(0, NON_METRIC_HAZARD_ADVISORY_THRESHOLDS.maximumDirections);
}
