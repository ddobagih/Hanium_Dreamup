/**
 * Converts WebXR depth samples into metric distance and bbox history into a qualitative approach trend.
 * Pseudo-depth estimates remain model estimates and must not be presented as measured distance.
 */
import type { DetectionDistanceSource, NormalizedBBoxV2, TwoModelDetection } from "@/types/inference-v2";

export type DepthPoint = {
  x: number;
  y: number;
};

export type ApproachState = "approaching" | "stable" | "receding" | "unknown";

export type DepthFrameSampler = {
  observedAtMs: number;
  getDepthInMeters: (x: number, y: number) => number | null;
};

export type DepthEstimateResult = {
  distance_m: number;
  source: DetectionDistanceSource;
  confidence: number;
  approach_state: ApproachState;
  sample_count: number;
  valid_sample_ratio: number;
};

export type DepthHistorySample = {
  detection: TwoModelDetection;
  observed_at_ms?: number;
};

type SensorDepthOptions = {
  gridSize?: number;
  roiInsetRatio?: number;
  minValidSamples?: number;
  minValidRatio?: number;
  motionStability?: number | null;
  detectionConfidence?: number | null;
  nowMs?: number;
  maxFrameAgeMs?: number;
};

type PseudoDepthOptions = {
  motionStability?: number | null;
  minStableFrames?: number;
};

const MAX_DEPTH_M = 50;
const MIN_DEPTH_M = 0.15;
const DEFAULT_DEPTH_FRAME_MAX_AGE_MS = 900;
const DEFAULT_VERTICAL_FOV_DEG = 60;
const DEFAULT_ROI_INSET_RATIO = 0.2;
const DEFAULT_GRID_SIZE = 5;
const DEFAULT_MIN_VALID_DEPTH_SAMPLES = 6;
const DEFAULT_MIN_VALID_DEPTH_RATIO = 0.45;
const PSEUDO_DEPTH_APPROACH_MIN_MOTION_STABILITY = 0.55;
const PSEUDO_DEPTH_LOW_MOTION_CONFIDENCE_CAP = 0.35;

const REFERENCE_HEIGHT_M: Partial<Record<string, number>> = {
  person: 1.65,
  bicycle: 1.1,
  motorcycle: 1.2,
  car: 1.5,
  bus: 2.8,
  truck: 2.8,
  curb_step: 0.2,
  uneven_sidewalk: 0.15,
  e_scooter_obstruction: 1.1,
  bench: 0.8
};

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function clamp01(value: number): number {
  return clamp(value, 0, 1);
}

function finiteUnit(value: number | null | undefined, fallback: number): number {
  return typeof value === "number" && Number.isFinite(value) ? clamp01(value) : fallback;
}

function bboxArea(bbox: NormalizedBBoxV2): number {
  return Math.max(0, bbox.width) * Math.max(0, bbox.height);
}

function timestampForSample(sample: DepthHistorySample): number {
  if (typeof sample.observed_at_ms === "number" && Number.isFinite(sample.observed_at_ms)) {
    return sample.observed_at_ms;
  }

  const parsed = Date.parse(sample.detection.captured_at);
  return Number.isFinite(parsed) ? parsed : 0;
}

function median(values: number[]): number | null {
  if (values.length === 0) {
    return null;
  }

  const sorted = [...values].sort((a, b) => a - b);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0 ? (sorted[middle - 1] + sorted[middle]) / 2 : sorted[middle];
}

function validDepthMeters(value: number | null | undefined): value is number {
  return typeof value === "number" && Number.isFinite(value) && value >= MIN_DEPTH_M && value <= MAX_DEPTH_M;
}

export function polygonFromBBox(bbox: NormalizedBBoxV2, insetRatio = 0): DepthPoint[] {
  const insetX = clamp01(insetRatio) * bbox.width;
  const insetY = clamp01(insetRatio) * bbox.height;
  const left = clamp01(bbox.x + insetX);
  const top = clamp01(bbox.y + insetY);
  const right = clamp01(bbox.x + bbox.width - insetX);
  const bottom = clamp01(bbox.y + bbox.height - insetY);

  return [
    { x: left, y: top },
    { x: right, y: top },
    { x: right, y: bottom },
    { x: left, y: bottom }
  ];
}

export function roiSamplePointsFromBBox(
  bbox: NormalizedBBoxV2,
  { gridSize = DEFAULT_GRID_SIZE, roiInsetRatio = DEFAULT_ROI_INSET_RATIO }: Pick<SensorDepthOptions, "gridSize" | "roiInsetRatio"> = {}
): DepthPoint[] {
  const samplesPerAxis = Math.max(3, Math.min(9, Math.round(gridSize)));
  const inset = clamp(roiInsetRatio, 0, 0.42);
  const left = clamp01(bbox.x + bbox.width * inset);
  const top = clamp01(bbox.y + bbox.height * inset);
  const right = clamp01(bbox.x + bbox.width * (1 - inset));
  const bottom = clamp01(bbox.y + bbox.height * (1 - inset));
  const points: DepthPoint[] = [];

  for (let row = 0; row < samplesPerAxis; row += 1) {
    const y = samplesPerAxis === 1 ? (top + bottom) / 2 : top + ((bottom - top) * row) / (samplesPerAxis - 1);
    for (let column = 0; column < samplesPerAxis; column += 1) {
      const x = samplesPerAxis === 1 ? (left + right) / 2 : left + ((right - left) * column) / (samplesPerAxis - 1);
      points.push({ x: clamp01(x), y: clamp01(y) });
    }
  }

  return points;
}

export function estimateSensorDepthForBBox(
  bbox: NormalizedBBoxV2,
  sampler: DepthFrameSampler | null | undefined,
  options: SensorDepthOptions = {}
): DepthEstimateResult | null {
  if (!sampler) {
    return null;
  }

  const nowMs = options.nowMs ?? Date.now();
  const frameAgeMs = Math.max(0, nowMs - sampler.observedAtMs);
  const maxFrameAgeMs = options.maxFrameAgeMs ?? DEFAULT_DEPTH_FRAME_MAX_AGE_MS;
  if (frameAgeMs > maxFrameAgeMs) {
    return null;
  }

  const points = roiSamplePointsFromBBox(bbox, options);
  const rawDepths = points
    .map((point) => {
      try {
        return sampler.getDepthInMeters(point.x, point.y);
      } catch {
        return null;
      }
    })
    .filter(validDepthMeters);
  const validRatio = rawDepths.length / points.length;
  const requestedMinValidSamples =
    typeof options.minValidSamples === "number" && Number.isFinite(options.minValidSamples)
      ? options.minValidSamples
      : DEFAULT_MIN_VALID_DEPTH_SAMPLES;
  const requestedMinValidRatio =
    typeof options.minValidRatio === "number" && Number.isFinite(options.minValidRatio)
      ? options.minValidRatio
      : DEFAULT_MIN_VALID_DEPTH_RATIO;
  const minValidSamples = Math.min(
    points.length,
    Math.max(1, Math.round(requestedMinValidSamples))
  );
  const minValidRatio = clamp(requestedMinValidRatio, 0, 1);
  if (rawDepths.length < minValidSamples || validRatio < minValidRatio) {
    return null;
  }

  const rawMedian = median(rawDepths);
  if (rawMedian === null) {
    return null;
  }
  const trimmed = rawDepths.filter((value) => Math.abs(value - rawMedian) <= Math.max(0.45, rawMedian * 0.35));
  if (trimmed.length < minValidSamples) {
    return null;
  }

  const trimmedMedian = median(trimmed);
  if (trimmedMedian === null) {
    return null;
  }

  const spread = trimmed.length > 1 ? Math.max(...trimmed) - Math.min(...trimmed) : 0;
  const spreadScore = clamp01(1 - spread / Math.max(0.6, trimmedMedian * 0.45));
  const freshnessScore = clamp01(1 - frameAgeMs / maxFrameAgeMs);
  const motionStability = finiteUnit(options.motionStability, 1);
  const detectionConfidence = finiteUnit(options.detectionConfidence, 1);
  const confidence = clamp01((0.44 * validRatio + 0.26 * spreadScore + 0.18 * freshnessScore + 0.12 * motionStability) * detectionConfidence);

  return {
    distance_m: Math.round(trimmedMedian * 100) / 100,
    source: "sensor_depth",
    confidence: Math.round(confidence * 100) / 100,
    approach_state: "unknown",
    sample_count: trimmed.length,
    valid_sample_ratio: Math.round(validRatio * 100) / 100
  };
}

export function estimateMonocularDistanceM(
  detection: TwoModelDetection,
  verticalFovDeg = DEFAULT_VERTICAL_FOV_DEG
): number | null {
  const referenceHeightM = REFERENCE_HEIGHT_M[detection.class_name.toLowerCase()];
  if (!referenceHeightM || detection.bbox.height <= 0.04) {
    return null;
  }

  const verticalFovRad = (verticalFovDeg * Math.PI) / 180;
  const distanceM = referenceHeightM / (2 * Math.tan(verticalFovRad / 2) * detection.bbox.height);
  if (!validDepthMeters(distanceM)) {
    return null;
  }

  return Math.round(distanceM * 100) / 100;
}

export function estimatePseudoDepthFromHistory(
  current: TwoModelDetection,
  history: DepthHistorySample[],
  options: PseudoDepthOptions = {}
): DepthEstimateResult | null {
  const sorted = [...history].sort((a, b) => timestampForSample(a) - timestampForSample(b));
  const latest = sorted.at(-1);
  if (!latest) {
    return null;
  }

  const latestDistanceM = estimateMonocularDistanceM(current);
  if (latestDistanceM === null) {
    return null;
  }

  const minStableFrames = options.minStableFrames ?? 3;
  const first = sorted[0];
  const firstDistanceM = first ? estimateMonocularDistanceM(first.detection) : null;
  const firstArea = first ? bboxArea(first.detection.bbox) : 0;
  const latestArea = bboxArea(current.bbox);
  const stableFrames = sorted.length;
  const elapsedMs = first ? Math.max(0, timestampForSample(latest) - timestampForSample(first)) : 0;
  const distanceDeltaM = firstDistanceM === null ? null : firstDistanceM - latestDistanceM;
  const areaGrowthRatio = firstArea > 0 ? latestArea / firstArea - 1 : null;
  const enoughHistory = stableFrames >= minStableFrames && elapsedMs >= 500;
  const motionStability = finiteUnit(options.motionStability, 1);
  const detectionConfidence = finiteUnit(current.confidence, 1);
  const stableMotionForApproach = motionStability >= PSEUDO_DEPTH_APPROACH_MIN_MOTION_STABILITY;

  let approachState: ApproachState = "unknown";
  if (enoughHistory && stableMotionForApproach && distanceDeltaM !== null && areaGrowthRatio !== null) {
    if (distanceDeltaM >= 0.45 && areaGrowthRatio >= 0.18) {
      approachState = "approaching";
    } else if (distanceDeltaM <= -0.45 && areaGrowthRatio <= -0.15) {
      approachState = "receding";
    } else {
      approachState = "stable";
    }
  }

  const areaSignal = areaGrowthRatio === null ? 0 : clamp01(Math.abs(areaGrowthRatio) / 0.45);
  const distanceSignal = distanceDeltaM === null ? 0 : clamp01(Math.abs(distanceDeltaM) / 1.2);
  const historyScore = clamp01((stableFrames - 1) / Math.max(1, minStableFrames - 1));
  const baseConfidence = clamp01(
    (0.34 * historyScore + 0.24 * areaSignal + 0.22 * distanceSignal + 0.2 * motionStability) * detectionConfidence
  );
  const confidence = stableMotionForApproach
    ? baseConfidence
    : Math.min(baseConfidence, PSEUDO_DEPTH_LOW_MOTION_CONFIDENCE_CAP);

  return {
    distance_m: latestDistanceM,
    source: "model_estimate",
    confidence: Math.round(confidence * 100) / 100,
    approach_state: approachState,
    sample_count: stableFrames,
    valid_sample_ratio: 1
  };
}
