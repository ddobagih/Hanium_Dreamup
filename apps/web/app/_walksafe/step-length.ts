/**
 * Estimates step length from bounded GPS and motion evidence, rejecting noisy or implausible segments.
 * A stored calibration is accepted only while its confidence and expiry gates remain valid.
 */
export type StepLengthEstimateStatus = "collecting" | "estimated" | "fallback";
export type StepLengthEstimateSource = "gps_motion" | "stored_calibration" | "default";

export type GpsStepLengthSample = {
  latitude: number;
  longitude: number;
  accuracy_m?: number | null;
  speed_mps?: number | null;
  observedAtMs: number;
};

export type StepLengthEvidenceSummary = {
  distanceM: number;
  stepCount: number;
  gpsSampleCount: number;
  validSegmentCount: number;
  ignoredSegmentCount: number;
  walkingSpeedMps: number | null;
};

export type StepLengthEstimate = StepLengthEvidenceSummary & {
  stepLengthM: number;
  status: StepLengthEstimateStatus;
  source: StepLengthEstimateSource;
  message: string;
  confidence: number;
  expiresAtMs: number | null;
};

export type StoredStepLengthCalibration = {
  schemaVersion: 1;
  stepLengthM: number;
  confidence: number;
  distanceM: number;
  stepCount: number;
  validSegmentCount: number;
  observedAtMs: number;
  expiresAtMs: number;
  source: "gps_motion_dry_run";
};

export type StepLengthCalibrationLog = {
  schemaVersion: 1;
  dryRun: true;
  status: StepLengthEstimateStatus;
  source: StepLengthEstimateSource;
  sampleCount: number;
  stepEventCount: number;
  gpsSampleCount: number;
  distanceM: number;
  ignoredSegmentCount: number;
  walkingSpeedMps: number | null;
  confidence: number;
  stepLengthM: number;
  observedAtMs: number;
  expiresAtMs: number | null;
};

const MIN_STEP_LENGTH_M = 0.3;
const MAX_STEP_LENGTH_M = 1.2;
const FALLBACK_STEP_LENGTH_M = 0.65;
export const STEP_LENGTH_CALIBRATION_TTL_MS = 1000 * 60 * 60 * 24 * 7;
export const MIN_STEP_LENGTH_CALIBRATION_DISTANCE_M = 4;
export const MIN_STEP_LENGTH_CALIBRATION_STEPS = 8;
export const MIN_STEP_LENGTH_CALIBRATION_CONFIDENCE = 0.45;
const MAX_GPS_ACCURACY_M = 20;
const MIN_SEGMENT_INTERVAL_MS = 500;
const MAX_SEGMENT_INTERVAL_MS = 15000;
const MIN_SEGMENT_DISTANCE_M = 1.2;
const MAX_SEGMENT_DISTANCE_M = 25;
const MIN_WALKING_SPEED_MPS = 0.2;
const MAX_WALKING_SPEED_MPS = 2.2;
const MAX_STATIONARY_SPEED_MPS = 0.15;
const MIN_CADENCE_STEPS_PER_MIN = 50;
const MAX_CADENCE_STEPS_PER_MIN = 140;
const MIN_STEPS_PER_VALID_SEGMENT = 2;
const INITIAL_GPS_SETTLE_MS = 12000;
const MIN_ACCURACY_DISTANCE_RATIO = 0.35;
const MAX_ACCURACY_ADJUSTED_MIN_DISTANCE_M = 5;

function clamp(value: number, min: number, max: number): number {
  if (!Number.isFinite(value)) {
    return min;
  }
  return Math.min(max, Math.max(min, value));
}

function clampStepLength(value: number): number {
  if (!Number.isFinite(value)) {
    return FALLBACK_STEP_LENGTH_M;
  }
  return Math.min(MAX_STEP_LENGTH_M, Math.max(MIN_STEP_LENGTH_M, value));
}

function haversineMeters(from: GpsStepLengthSample, to: GpsStepLengthSample): number {
  const earthRadiusM = 6371000;
  const lat1 = (from.latitude * Math.PI) / 180;
  const lat2 = (to.latitude * Math.PI) / 180;
  const deltaLat = ((to.latitude - from.latitude) * Math.PI) / 180;
  const deltaLon = ((to.longitude - from.longitude) * Math.PI) / 180;
  const a = Math.sin(deltaLat / 2) ** 2 + Math.cos(lat1) * Math.cos(lat2) * Math.sin(deltaLon / 2) ** 2;
  return earthRadiusM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

function gpsAccuracyOk(sample: GpsStepLengthSample): boolean {
  return typeof sample.accuracy_m === "number" && sample.accuracy_m <= MAX_GPS_ACCURACY_M;
}

function stepsInWindow(stepEventTimesMs: number[], startMs: number, endMs: number): number[] {
  return stepEventTimesMs.filter((observedAtMs) => observedAtMs >= startMs && observedAtMs <= endMs);
}

function cadenceOk(stepTimesMs: number[], elapsedMs: number): boolean {
  if (stepTimesMs.length < MIN_STEPS_PER_VALID_SEGMENT || elapsedMs <= 0) {
    return false;
  }
  const cadenceStepsPerMin = stepTimesMs.length / (elapsedMs / 60000);
  return cadenceStepsPerMin >= MIN_CADENCE_STEPS_PER_MIN && cadenceStepsPerMin <= MAX_CADENCE_STEPS_PER_MIN;
}

function stationaryBySensorSpeed(previous: GpsStepLengthSample, current: GpsStepLengthSample): boolean {
  const speeds = [previous.speed_mps, current.speed_mps].filter((value): value is number => typeof value === "number" && Number.isFinite(value));
  return speeds.length > 0 && Math.max(...speeds) <= MAX_STATIONARY_SPEED_MPS;
}

function minSegmentDistanceForAccuracy(previous: GpsStepLengthSample, current: GpsStepLengthSample): number {
  const accuracyM = Math.max(previous.accuracy_m ?? 0, current.accuracy_m ?? 0);
  return Math.min(
    MAX_ACCURACY_ADJUSTED_MIN_DISTANCE_M,
    Math.max(MIN_SEGMENT_DISTANCE_M, accuracyM * MIN_ACCURACY_DISTANCE_RATIO)
  );
}

export function summarizeStepLengthEvidence(
  samples: GpsStepLengthSample[],
  stepEventTimesMs: number[]
): StepLengthEvidenceSummary {
  let distanceM = 0;
  let validSegmentCount = 0;
  let ignoredSegmentCount = 0;
  let validDurationMs = 0;
  const validStepEvents = new Set<number>();
  const firstObservedAtMs = samples[0]?.observedAtMs ?? null;

  for (let index = 1; index < samples.length; index += 1) {
    const previous = samples[index - 1];
    const current = samples[index];
    if (firstObservedAtMs !== null && current.observedAtMs - firstObservedAtMs < INITIAL_GPS_SETTLE_MS) {
      ignoredSegmentCount += 1;
      continue;
    }
    if (!gpsAccuracyOk(previous) || !gpsAccuracyOk(current)) {
      ignoredSegmentCount += 1;
      continue;
    }

    const elapsedMs = current.observedAtMs - previous.observedAtMs;
    const segmentDistanceM = haversineMeters(previous, current);
    const speedMps = elapsedMs > 0 ? segmentDistanceM / (elapsedMs / 1000) : Number.POSITIVE_INFINITY;
    const segmentSteps = stepsInWindow(stepEventTimesMs, previous.observedAtMs, current.observedAtMs);
    const validSegment =
      elapsedMs >= MIN_SEGMENT_INTERVAL_MS &&
      elapsedMs <= MAX_SEGMENT_INTERVAL_MS &&
      segmentDistanceM >= minSegmentDistanceForAccuracy(previous, current) &&
      segmentDistanceM <= MAX_SEGMENT_DISTANCE_M &&
      speedMps >= MIN_WALKING_SPEED_MPS &&
      speedMps <= MAX_WALKING_SPEED_MPS &&
      !stationaryBySensorSpeed(previous, current) &&
      cadenceOk(segmentSteps, elapsedMs);

    if (!validSegment) {
      ignoredSegmentCount += 1;
      continue;
    }

    distanceM += segmentDistanceM;
    validDurationMs += elapsedMs;
    validSegmentCount += 1;
    segmentSteps.forEach((observedAtMs) => validStepEvents.add(observedAtMs));
  }

  return {
    distanceM,
    stepCount: validStepEvents.size,
    gpsSampleCount: samples.length,
    validSegmentCount,
    ignoredSegmentCount,
    walkingSpeedMps: validDurationMs > 0 ? distanceM / (validDurationMs / 1000) : null
  };
}

export function measureValidWalkingDistanceM(samples: GpsStepLengthSample[]): number {
  return summarizeStepLengthEvidence(samples, []).distanceM;
}

function confidenceFromEvidence(evidence: StepLengthEvidenceSummary): number {
  if (evidence.distanceM < MIN_STEP_LENGTH_CALIBRATION_DISTANCE_M || evidence.stepCount < MIN_STEP_LENGTH_CALIBRATION_STEPS) {
    return 0;
  }

  const distanceScore = clamp(evidence.distanceM / 12, 0, 1);
  const stepScore = clamp(evidence.stepCount / 24, 0, 1);
  const segmentScore = clamp(evidence.validSegmentCount / 4, 0, 1);
  const totalSegments = evidence.validSegmentCount + evidence.ignoredSegmentCount;
  const outlierPenalty = totalSegments === 0 ? 0 : (evidence.ignoredSegmentCount / totalSegments) * 0.25;
  return clamp(0.2 + distanceScore * 0.35 + stepScore * 0.3 + segmentScore * 0.2 - outlierPenalty, 0, 1);
}

function baseEstimate(
  stepLengthM: number,
  status: StepLengthEstimateStatus,
  source: StepLengthEstimateSource,
  message: string,
  evidence: StepLengthEvidenceSummary,
  confidence: number,
  expiresAtMs: number | null
): StepLengthEstimate {
  return {
    stepLengthM: clampStepLength(stepLengthM),
    status,
    source,
    message,
    distanceM: evidence.distanceM,
    stepCount: evidence.stepCount,
    gpsSampleCount: evidence.gpsSampleCount,
    validSegmentCount: evidence.validSegmentCount,
    ignoredSegmentCount: evidence.ignoredSegmentCount,
    walkingSpeedMps: evidence.walkingSpeedMps,
    confidence: clamp(confidence, 0, 1),
    expiresAtMs
  };
}

export function estimateStepLengthFromGpsAndMotion(
  samples: GpsStepLengthSample[],
  stepEventTimesMs: number[],
  fallbackStepLengthM = FALLBACK_STEP_LENGTH_M,
  storedCalibration: StoredStepLengthCalibration | null = null
): StepLengthEstimate {
  const evidence = summarizeStepLengthEvidence(samples, stepEventTimesMs);
  const fallback = clampStepLength(storedCalibration?.stepLengthM ?? fallbackStepLengthM);

  if (samples.length < 2 || evidence.stepCount === 0) {
    if (storedCalibration) {
      return baseEstimate(
        fallback,
        "fallback",
        "stored_calibration",
        `저장 보폭 ${fallback.toFixed(2)}m 사용 · 재보정 대기`,
        evidence,
        storedCalibration.confidence,
        storedCalibration.expiresAtMs
      );
    }
    return baseEstimate(
      fallback,
      "collecting",
      "default",
      `자동 보폭 측정 대기 · 기본 ${fallback.toFixed(2)}m`,
      evidence,
      0,
      null
    );
  }

  if (evidence.distanceM < MIN_STEP_LENGTH_CALIBRATION_DISTANCE_M || evidence.stepCount < MIN_STEP_LENGTH_CALIBRATION_STEPS) {
    if (storedCalibration) {
      return baseEstimate(
        fallback,
        "fallback",
        "stored_calibration",
        `저장 보폭 ${fallback.toFixed(2)}m 사용 · ${evidence.stepCount}보/${Math.round(evidence.distanceM)}m 수집`,
        evidence,
        storedCalibration.confidence,
        storedCalibration.expiresAtMs
      );
    }
    return baseEstimate(
      fallback,
      "collecting",
      "default",
      `자동 보폭 측정 중 · ${evidence.stepCount}보/${Math.round(evidence.distanceM)}m`,
      evidence,
      0,
      null
    );
  }

  const estimated = clampStepLength(evidence.distanceM / evidence.stepCount);
  const confidence = confidenceFromEvidence(evidence);
  const ignoredLabel = evidence.ignoredSegmentCount > 0 ? ` · 튐 ${evidence.ignoredSegmentCount}개 제외` : "";
  return baseEstimate(
    estimated,
    "estimated",
    "gps_motion",
    `자동 보폭 ${estimated.toFixed(2)}m · ${evidence.stepCount}보 기준 · 신뢰도 ${Math.round(confidence * 100)}%${ignoredLabel}`,
    evidence,
    confidence,
    Date.now() + STEP_LENGTH_CALIBRATION_TTL_MS
  );
}

function normalizeStoredStepLengthCalibration(
  value: Partial<StoredStepLengthCalibration> | null | undefined,
  nowMs: number
): StoredStepLengthCalibration | null {
  if (!value) {
    return null;
  }
  const stepLengthM = clampStepLength(Number(value.stepLengthM));
  const confidence = clamp(Number(value.confidence), 0, 1);
  const observedAtMs = Number(value.observedAtMs);
  const expiresAtMs = Number(value.expiresAtMs);
  if (!Number.isFinite(observedAtMs) || !Number.isFinite(expiresAtMs) || expiresAtMs <= nowMs) {
    return null;
  }
  return {
    schemaVersion: 1,
    stepLengthM,
    confidence,
    distanceM: Math.max(0, Number(value.distanceM) || 0),
    stepCount: Math.max(0, Math.floor(Number(value.stepCount) || 0)),
    validSegmentCount: Math.max(0, Math.floor(Number(value.validSegmentCount) || 0)),
    observedAtMs,
    expiresAtMs,
    source: "gps_motion_dry_run"
  };
}

export function parseStoredStepLengthCalibration(value: string | null, nowMs = Date.now()): StoredStepLengthCalibration | null {
  if (!value) {
    return null;
  }
  try {
    return normalizeStoredStepLengthCalibration(JSON.parse(value) as Partial<StoredStepLengthCalibration>, nowMs);
  } catch {
    return null;
  }
}

export function createStoredStepLengthCalibration(
  estimate: StepLengthEstimate,
  nowMs = Date.now()
): StoredStepLengthCalibration | null {
  if (estimate.status !== "estimated" || estimate.confidence < MIN_STEP_LENGTH_CALIBRATION_CONFIDENCE) {
    return null;
  }
  return {
    schemaVersion: 1,
    stepLengthM: clampStepLength(estimate.stepLengthM),
    confidence: clamp(estimate.confidence, 0, 1),
    distanceM: Math.max(0, estimate.distanceM),
    stepCount: Math.max(0, estimate.stepCount),
    validSegmentCount: Math.max(0, estimate.validSegmentCount),
    observedAtMs: nowMs,
    expiresAtMs: nowMs + STEP_LENGTH_CALIBRATION_TTL_MS,
    source: "gps_motion_dry_run"
  };
}

export function buildStepLengthCalibrationLog(
  estimate: StepLengthEstimate,
  motionSampleCount: number,
  observedAtMs = Date.now()
): StepLengthCalibrationLog {
  return {
    schemaVersion: 1,
    dryRun: true,
    status: estimate.status,
    source: estimate.source,
    sampleCount: Math.max(0, motionSampleCount),
    stepEventCount: estimate.stepCount,
    gpsSampleCount: estimate.gpsSampleCount,
    distanceM: estimate.distanceM,
    ignoredSegmentCount: estimate.ignoredSegmentCount,
    walkingSpeedMps: estimate.walkingSpeedMps,
    confidence: estimate.confidence,
    stepLengthM: estimate.stepLengthM,
    observedAtMs,
    expiresAtMs: estimate.expiresAtMs
  };
}
