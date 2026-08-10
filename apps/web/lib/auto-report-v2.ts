/**
 * Applies the damaged-tactile-only automatic-report policy, including GPS,
 * confidence, consecutive-frame, spatial continuity, and cooldown gates.
 */
import type { GpsFixV2, TwoModelDetection } from "@/types/inference-v2";

const AUTO_REPORT_CLASSES = new Set(["damaged_tactile_block"]);
const AUTO_REPORT_MODEL_KEYS = new Set(["custom_tactile", "unified_walksafe"]);
export const AUTO_REPORT_COOLDOWN_MS = 10 * 60 * 1000;
export const AUTO_REPORT_LOCATION_PRECISION = 4;
export const AUTO_REPORT_MIN_CONSECUTIVE_FRAMES = 3;
export const AUTO_REPORT_MIN_STABLE_MS = 700;
export const AUTO_REPORT_MIN_CONFIDENCE = 0.7;
export const AUTO_REPORT_MAX_GPS_ACCURACY_M = 15;
export const AUTO_REPORT_MAX_FRAME_GAP_MS = 1800;
export const AUTO_REPORT_MAX_GPS_JUMP_M = 20;
export const AUTO_REPORT_COOLDOWN_STORAGE_KEY = "walksafe-auto-report-v2-cooldowns";

export type AutoReportV2GateState = {
  candidate: TwoModelDetection;
  consecutiveFrames: number;
  firstObservedAtMs: number;
  observedAtMs: number;
  gps: Pick<GpsFixV2, "latitude" | "longitude" | "accuracy_m">;
};

export type AutoReportV2GateReason =
  | "ready"
  | "gps_quality"
  | "gps_continuity"
  | "no_candidate"
  | "confidence"
  | "stabilizing";

export type AutoReportV2GateResult = {
  state: AutoReportV2GateState | null;
  target: TwoModelDetection | null;
  reason: AutoReportV2GateReason;
};

export function isAutoReportV2Target(detection: TwoModelDetection): boolean {
  return AUTO_REPORT_MODEL_KEYS.has(detection.model_key) && AUTO_REPORT_CLASSES.has(detection.class_name);
}

export function shouldEmitReportUserFeedback(trigger: "auto" | "voice"): boolean {
  return trigger === "auto" || trigger === "voice";
}

export function parseAutoReportV2Cooldowns(
  serialized: string | null | undefined,
  now = Date.now()
): Map<string, number> {
  if (!serialized) {
    return new Map();
  }

  try {
    const parsed: unknown = JSON.parse(serialized);
    if (!Array.isArray(parsed)) {
      return new Map();
    }
    return new Map(
      parsed.filter(
        (entry): entry is [string, number] =>
          Array.isArray(entry) &&
          entry.length === 2 &&
          typeof entry[0] === "string" &&
          entry[0].length <= 300 &&
          typeof entry[1] === "number" &&
          Number.isFinite(entry[1]) &&
          entry[1] <= now + 60_000 &&
          now - entry[1] < AUTO_REPORT_COOLDOWN_MS
      )
    );
  } catch {
    return new Map();
  }
}

export function serializeAutoReportV2Cooldowns(
  cooldowns: ReadonlyMap<string, number>,
  now = Date.now()
): string {
  return JSON.stringify(
    [...cooldowns].filter(
      ([key, reportedAt]) =>
        key.length <= 300 &&
        Number.isFinite(reportedAt) &&
        reportedAt <= now + 60_000 &&
        now - reportedAt < AUTO_REPORT_COOLDOWN_MS
    )
  );
}

export function selectAutoReportV2Detection(
  detections: readonly TwoModelDetection[],
  minConfidence = 0
): TwoModelDetection | null {
  return detections
    .filter((detection) => isAutoReportV2Target(detection) && detection.confidence >= minConfidence)
    .sort((a, b) => b.confidence - a.confidence)[0] ?? null;
}

export function hasAutoReportGpsQuality(gps: GpsFixV2 | null | undefined): gps is GpsFixV2 {
  return Boolean(
    gps &&
      Number.isFinite(gps.latitude) &&
      gps.latitude >= -90 &&
      gps.latitude <= 90 &&
      Number.isFinite(gps.longitude) &&
      gps.longitude >= -180 &&
      gps.longitude <= 180 &&
      typeof gps.accuracy_m === "number" &&
      Number.isFinite(gps.accuracy_m) &&
      gps.accuracy_m >= 0 &&
      gps.accuracy_m <= AUTO_REPORT_MAX_GPS_ACCURACY_M
  );
}

function bboxIou(first: TwoModelDetection, second: TwoModelDetection): number {
  const firstRight = first.bbox.x + first.bbox.width;
  const firstBottom = first.bbox.y + first.bbox.height;
  const secondRight = second.bbox.x + second.bbox.width;
  const secondBottom = second.bbox.y + second.bbox.height;
  const intersectionWidth = Math.max(0, Math.min(firstRight, secondRight) - Math.max(first.bbox.x, second.bbox.x));
  const intersectionHeight = Math.max(0, Math.min(firstBottom, secondBottom) - Math.max(first.bbox.y, second.bbox.y));
  const intersection = intersectionWidth * intersectionHeight;
  const union = first.bbox.width * first.bbox.height + second.bbox.width * second.bbox.height - intersection;
  return union > 0 ? intersection / union : 0;
}

function isSameAutoReportCandidate(first: TwoModelDetection, second: TwoModelDetection): boolean {
  return (
    first.model_key === second.model_key &&
    first.source_model === second.source_model &&
    first.class_name === second.class_name &&
    bboxIou(first, second) >= 0.1
  );
}

function detectionObservedAtMs(detection: TwoModelDetection, fallbackNowMs: number): number {
  const parsed = Date.parse(detection.captured_at);
  return Number.isFinite(parsed) ? parsed : fallbackNowMs;
}

function gpsDistanceM(first: GpsFixV2, second: GpsFixV2): number {
  const earthRadiusM = 6_371_000;
  const toRadians = (degrees: number) => (degrees * Math.PI) / 180;
  const latitudeDelta = toRadians(second.latitude - first.latitude);
  const longitudeDelta = toRadians(second.longitude - first.longitude);
  const firstLatitude = toRadians(first.latitude);
  const secondLatitude = toRadians(second.latitude);
  const haversine =
    Math.sin(latitudeDelta / 2) ** 2 +
    Math.cos(firstLatitude) * Math.cos(secondLatitude) * Math.sin(longitudeDelta / 2) ** 2;
  return earthRadiusM * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(Math.max(0, 1 - haversine)));
}

export function advanceAutoReportV2Gate(
  previous: AutoReportV2GateState | null,
  detections: readonly TwoModelDetection[],
  gps: GpsFixV2 | null | undefined,
  nowMs = Date.now()
): AutoReportV2GateResult {
  if (!hasAutoReportGpsQuality(gps)) {
    return { state: null, target: null, reason: "gps_quality" };
  }

  const reportableDetections = detections.filter(isAutoReportV2Target);
  if (reportableDetections.length === 0) {
    return { state: null, target: null, reason: "no_candidate" };
  }

  const confidentDetections = reportableDetections.filter(
    (detection) => detection.confidence >= AUTO_REPORT_MIN_CONFIDENCE
  );
  if (confidentDetections.length === 0) {
    return { state: null, target: null, reason: "confidence" };
  }

  const matchingCandidate = previous
    ? confidentDetections.find((detection) => isSameAutoReportCandidate(previous.candidate, detection))
    : null;
  const candidate = matchingCandidate ?? selectAutoReportV2Detection(confidentDetections, AUTO_REPORT_MIN_CONFIDENCE);
  if (!candidate) {
    return { state: null, target: null, reason: "no_candidate" };
  }

  const observedAtMs = detectionObservedAtMs(candidate, nowMs);
  const frameGapMs = previous ? observedAtMs - previous.observedAtMs : null;
  const gpsContinuous = !previous || gpsDistanceM(previous.gps, gps) <= AUTO_REPORT_MAX_GPS_JUMP_M;
  const continuesPrevious = Boolean(
    previous &&
      matchingCandidate &&
      frameGapMs !== null &&
      frameGapMs > 0 &&
      frameGapMs <= AUTO_REPORT_MAX_FRAME_GAP_MS &&
      gpsContinuous
  );
  const state: AutoReportV2GateState = {
    candidate,
    consecutiveFrames: continuesPrevious && previous ? previous.consecutiveFrames + 1 : 1,
    firstObservedAtMs: continuesPrevious && previous ? previous.firstObservedAtMs : observedAtMs,
    observedAtMs,
    gps: {
      latitude: gps.latitude,
      longitude: gps.longitude,
      accuracy_m: gps.accuracy_m
    }
  };

  if (
    state.consecutiveFrames < AUTO_REPORT_MIN_CONSECUTIVE_FRAMES ||
    state.observedAtMs - state.firstObservedAtMs < AUTO_REPORT_MIN_STABLE_MS
  ) {
    return { state, target: null, reason: previous && !gpsContinuous ? "gps_continuity" : "stabilizing" };
  }
  return { state, target: candidate, reason: "ready" };
}

export function autoReportV2CooldownKey(detection: TwoModelDetection, gps: GpsFixV2): string {
  return [
    detection.class_name,
    gps.latitude.toFixed(AUTO_REPORT_LOCATION_PRECISION),
    gps.longitude.toFixed(AUTO_REPORT_LOCATION_PRECISION)
  ].join(":");
}

export function canAutoReportV2(
  cooldowns: ReadonlyMap<string, number>,
  detection: TwoModelDetection,
  gps: GpsFixV2,
  now = Date.now()
): boolean {
  const lastReportedAt = cooldowns.get(autoReportV2CooldownKey(detection, gps));
  return lastReportedAt === undefined || now - lastReportedAt >= AUTO_REPORT_COOLDOWN_MS;
}
