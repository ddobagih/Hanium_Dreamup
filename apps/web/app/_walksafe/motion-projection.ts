/**
 * Bounded browser motion fusion for field guidance.
 * GPS is smoothed with an alpha-beta filter, then heading, route bearing and recent step cadence
 * produce a 3-5 second projection. The result is advisory screen-space context, not collision proof.
 */
import type { GpsFixV2, TwoModelDetection } from "@/types/inference-v2";
import type { RoutePoint, WalkingRouteResponse } from "@/types/navigation";
import { buildRouteMeasure, projectPointToRoute } from "./route-progress";
import { TACTILE_ROUTE_POLICY_THRESHOLDS } from "./tactile-route-policy";

const EARTH_RADIUS_M = 6371000;
const DEFAULT_HORIZON_S = 4;
const MIN_HORIZON_S = 3;
const MAX_HORIZON_S = 5;
const MAX_FILTER_GAP_MS = 10000;
const MAX_WALKING_SPEED_MPS = 2.5;
const MIN_PROJECTION_SPEED_MPS = 0.15;
const MAX_SCREEN_SHIFT_X = 0.18;
const STEP_SPEED_WINDOW_MS = 4000;
const STEP_SPEED_STALE_MS = 1800;

export type LocationObservation = {
  latitude: number;
  longitude: number;
  accuracyM: number | null;
  speedMps: number | null;
  headingDeg: number | null;
  observedAtMs: number;
};

export type AlphaBetaLocationState = {
  latitude: number;
  longitude: number;
  velocityNorthMps: number;
  velocityEastMps: number;
  speedMps: number;
  accuracyM: number | null;
  observedAtMs: number;
  sampleCount: number;
  lastObservationAccepted: boolean;
};

export type FutureMotionProjection = {
  horizonS: number;
  projectedDistanceM: number;
  projectedPoint: RoutePoint | null;
  bearingDeg: number | null;
  routeBearingDeg: number | null;
  headingDeg: number | null;
  headingRouteDeltaDeg: number | null;
  screenShiftX: number;
  speedMps: number;
  speedSource: "gps_step" | "gps" | "step" | "stationary";
  confidence: number;
};

export type TactileRouteSupport = {
  supported: boolean;
  detection: TwoModelDetection | null;
  reason:
    | "supervised_disabled"
    | "projection_unavailable"
    | "projection_invalid"
    | "supported"
    | "missing_motion"
    | "route_not_visible"
    | "stationary"
    | "low_projection_confidence"
    | "outside_future_roi"
    | "no_normal_tactile";
  corridorCenterX: number;
  corridorHalfWidth: number;
};

export type CameraRouteProjectionEvidence = {
  source: "walksafe.camera_route_projection.v1";
  routeId: string;
  observedAtMs: number;
  confidence: number;
  corridorCenterX: number;
  corridorHalfWidth: number;
};

export type TactileRouteSupportOptions = {
  supervisedFieldEnabled: boolean;
  expectedRouteId: string | null;
  projectionEvidence: CameraRouteProjectionEvidence | null;
  nowMs?: number;
};

const CAMERA_ROUTE_PROJECTION_MAX_AGE_MS = 1200;
const CAMERA_ROUTE_PROJECTION_MIN_CONFIDENCE = 0.7;

function clamp(value: number, min: number, max: number): number {
  return Math.min(max, Math.max(min, value));
}

function normalizeHeading(value: number | null | undefined): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return null;
  }
  return ((value % 360) + 360) % 360;
}

export function signedHeadingDeltaDegrees(targetDeg: number, sourceDeg: number): number {
  return ((((targetDeg - sourceDeg + 180) % 360) + 360) % 360) - 180;
}

function isValidPoint(point: Pick<RoutePoint, "latitude" | "longitude"> | null | undefined): point is RoutePoint {
  return Boolean(
    point &&
      Number.isFinite(point.latitude) &&
      Number.isFinite(point.longitude) &&
      point.latitude >= -90 &&
      point.latitude <= 90 &&
      point.longitude >= -180 &&
      point.longitude <= 180
  );
}

function metersPerLongitudeDegree(latitude: number): number {
  return Math.max(1, (Math.PI / 180) * EARTH_RADIUS_M * Math.cos((latitude * Math.PI) / 180));
}

function offsetMeters(from: RoutePoint, to: RoutePoint): { northM: number; eastM: number } {
  const northM = ((to.latitude - from.latitude) * Math.PI * EARTH_RADIUS_M) / 180;
  const eastM = (to.longitude - from.longitude) * metersPerLongitudeDegree(from.latitude);
  return { northM, eastM };
}

export function projectPointMeters(point: RoutePoint, northM: number, eastM: number): RoutePoint {
  return {
    latitude: point.latitude + (northM / EARTH_RADIUS_M) * (180 / Math.PI),
    longitude: point.longitude + eastM / metersPerLongitudeDegree(point.latitude)
  };
}

function velocityFromHeading(speedMps: number | null, headingDeg: number | null): { northMps: number; eastMps: number } | null {
  const normalizedHeading = normalizeHeading(headingDeg);
  if (
    normalizedHeading === null ||
    typeof speedMps !== "number" ||
    !Number.isFinite(speedMps) ||
    speedMps < 0 ||
    speedMps > MAX_WALKING_SPEED_MPS
  ) {
    return null;
  }
  const radians = (normalizedHeading * Math.PI) / 180;
  return {
    northMps: Math.cos(radians) * speedMps,
    eastMps: Math.sin(radians) * speedMps
  };
}

function clampVelocity(northMps: number, eastMps: number): { northMps: number; eastMps: number; speedMps: number } {
  const speedMps = Math.hypot(northMps, eastMps);
  if (speedMps <= MAX_WALKING_SPEED_MPS || speedMps === 0) {
    return { northMps, eastMps, speedMps };
  }
  const scale = MAX_WALKING_SPEED_MPS / speedMps;
  return {
    northMps: northMps * scale,
    eastMps: eastMps * scale,
    speedMps: MAX_WALKING_SPEED_MPS
  };
}

function initialFilterState(observation: LocationObservation): AlphaBetaLocationState {
  const sensorVelocity = velocityFromHeading(observation.speedMps, observation.headingDeg);
  const velocity = clampVelocity(sensorVelocity?.northMps ?? 0, sensorVelocity?.eastMps ?? 0);
  return {
    latitude: observation.latitude,
    longitude: observation.longitude,
    velocityNorthMps: velocity.northMps,
    velocityEastMps: velocity.eastMps,
    speedMps: velocity.speedMps,
    accuracyM: observation.accuracyM,
    observedAtMs: observation.observedAtMs,
    sampleCount: 1,
    lastObservationAccepted: true
  };
}

export function updateAlphaBetaLocation(
  previous: AlphaBetaLocationState | null,
  observation: LocationObservation
): AlphaBetaLocationState | null {
  if (!isValidPoint(observation) || !Number.isFinite(observation.observedAtMs)) {
    return previous;
  }
  if (!previous) {
    return initialFilterState(observation);
  }

  const elapsedMs = observation.observedAtMs - previous.observedAtMs;
  if (elapsedMs <= 0) {
    return previous;
  }
  if (elapsedMs > MAX_FILTER_GAP_MS) {
    return initialFilterState(observation);
  }

  const elapsedS = elapsedMs / 1000;
  const previousPoint = { latitude: previous.latitude, longitude: previous.longitude };
  const predictedPoint = projectPointMeters(
    previousPoint,
    previous.velocityNorthMps * elapsedS,
    previous.velocityEastMps * elapsedS
  );
  const innovation = offsetMeters(predictedPoint, observation);
  const innovationDistanceM = Math.hypot(innovation.northM, innovation.eastM);
  const accuracyM =
    typeof observation.accuracyM === "number" && Number.isFinite(observation.accuracyM) && observation.accuracyM >= 0
      ? observation.accuracyM
      : null;
  const maximumInnovationM = Math.max(15, (accuracyM ?? 20) * 3, MAX_WALKING_SPEED_MPS * elapsedS + (accuracyM ?? 10));

  if (innovationDistanceM > maximumInnovationM) {
    const projectedAccuracyM =
      previous.accuracyM === null
        ? null
        : previous.accuracyM + MAX_WALKING_SPEED_MPS * elapsedS;
    return {
      ...previous,
      latitude: predictedPoint.latitude,
      longitude: predictedPoint.longitude,
      // The new fix was rejected, so its accuracy cannot describe the predicted coordinates.
      accuracyM: projectedAccuracyM,
      observedAtMs: observation.observedAtMs,
      sampleCount: previous.sampleCount + 1,
      lastObservationAccepted: false
    };
  }

  const alpha = accuracyM !== null && accuracyM <= 8 ? 0.65 : accuracyM !== null && accuracyM <= 20 ? 0.45 : 0.25;
  const beta = 0.08;
  const filteredPoint = projectPointMeters(predictedPoint, innovation.northM * alpha, innovation.eastM * alpha);
  let velocityNorthMps = previous.velocityNorthMps + (innovation.northM * beta) / elapsedS;
  let velocityEastMps = previous.velocityEastMps + (innovation.eastM * beta) / elapsedS;
  const sensorVelocity = velocityFromHeading(observation.speedMps, observation.headingDeg);
  if (sensorVelocity) {
    velocityNorthMps = velocityNorthMps * 0.65 + sensorVelocity.northMps * 0.35;
    velocityEastMps = velocityEastMps * 0.65 + sensorVelocity.eastMps * 0.35;
  }
  const velocity = clampVelocity(velocityNorthMps, velocityEastMps);

  return {
    latitude: filteredPoint.latitude,
    longitude: filteredPoint.longitude,
    velocityNorthMps: velocity.northMps,
    velocityEastMps: velocity.eastMps,
    speedMps: velocity.speedMps,
    accuracyM,
    observedAtMs: observation.observedAtMs,
    sampleCount: previous.sampleCount + 1,
    lastObservationAccepted: true
  };
}

export function estimateRecentStepSpeedMps(
  stepEventTimesMs: readonly number[],
  stepLengthM: number,
  nowMs: number,
  windowMs = STEP_SPEED_WINDOW_MS
): number | null {
  if (!Number.isFinite(stepLengthM) || stepLengthM <= 0 || !Number.isFinite(nowMs)) {
    return null;
  }
  const recent = stepEventTimesMs.filter((time) => nowMs - time >= 0 && nowMs - time <= windowMs);
  const last = recent.at(-1);
  if (recent.length < 2 || last === undefined || nowMs - last > STEP_SPEED_STALE_MS) {
    return null;
  }
  const elapsedS = (last - recent[0]) / 1000;
  if (elapsedS <= 0) {
    return null;
  }
  const speedMps = ((recent.length - 1) * stepLengthM) / elapsedS;
  return speedMps >= MIN_PROJECTION_SPEED_MPS && speedMps <= MAX_WALKING_SPEED_MPS ? speedMps : null;
}

export function bearingDegrees(from: RoutePoint, to: RoutePoint): number | null {
  if (!isValidPoint(from) || !isValidPoint(to)) {
    return null;
  }
  const fromLat = (from.latitude * Math.PI) / 180;
  const toLat = (to.latitude * Math.PI) / 180;
  const deltaLongitude = ((to.longitude - from.longitude) * Math.PI) / 180;
  const y = Math.sin(deltaLongitude) * Math.cos(toLat);
  const x = Math.cos(fromLat) * Math.sin(toLat) - Math.sin(fromLat) * Math.cos(toLat) * Math.cos(deltaLongitude);
  if (x === 0 && y === 0) {
    return null;
  }
  return normalizeHeading((Math.atan2(y, x) * 180) / Math.PI);
}

export function resolveRouteBearing(
  route: Pick<WalkingRouteResponse, "polyline" | "steps"> | null,
  currentPoint: RoutePoint | null,
  previousDistanceFromStartM: number | null = null
): number | null {
  if (!route || !isValidPoint(currentPoint)) {
    return null;
  }
  const measure = buildRouteMeasure(route);
  const projection = projectPointToRoute(currentPoint, measure, { previousDistanceFromStartM });
  if (!projection || projection.segmentIndex === null || measure.points.length < 2) {
    return null;
  }
  const segmentIndex = Math.min(projection.segmentIndex, measure.points.length - 2);
  return bearingDegrees(measure.points[segmentIndex], measure.points[segmentIndex + 1]);
}

function validWalkingSpeed(value: number | null | undefined): number | null {
  return typeof value === "number" && Number.isFinite(value) && value >= MIN_PROJECTION_SPEED_MPS && value <= MAX_WALKING_SPEED_MPS
    ? value
    : null;
}

export function projectFutureMotion(args: {
  gps: GpsFixV2 | null;
  headingDeg: number | null;
  routeBearingDeg: number | null;
  gpsSpeedMps?: number | null;
  stepSpeedMps?: number | null;
  motionStability?: number | null;
  horizonS?: number;
}): FutureMotionProjection {
  const horizonS = clamp(args.horizonS ?? DEFAULT_HORIZON_S, MIN_HORIZON_S, MAX_HORIZON_S);
  const headingDeg = normalizeHeading(args.headingDeg);
  const routeBearingDeg = normalizeHeading(args.routeBearingDeg);
  const gpsSpeedMps = validWalkingSpeed(args.gpsSpeedMps ?? args.gps?.speed_mps);
  const stepSpeedMps = validWalkingSpeed(args.stepSpeedMps);
  const speedMps =
    gpsSpeedMps !== null && stepSpeedMps !== null
      ? gpsSpeedMps * 0.45 + stepSpeedMps * 0.55
      : (stepSpeedMps ?? gpsSpeedMps ?? 0);
  const speedSource =
    gpsSpeedMps !== null && stepSpeedMps !== null
      ? "gps_step"
      : stepSpeedMps !== null
        ? "step"
        : gpsSpeedMps !== null
          ? "gps"
          : "stationary";
  const bearingDeg = routeBearingDeg ?? headingDeg;
  const projectedDistanceM = speedMps * horizonS;
  const projectedPoint =
    args.gps && bearingDeg !== null && projectedDistanceM > 0
      ? projectPointMeters(
          args.gps,
          Math.cos((bearingDeg * Math.PI) / 180) * projectedDistanceM,
          Math.sin((bearingDeg * Math.PI) / 180) * projectedDistanceM
        )
      : null;
  const headingRouteDeltaDeg =
    routeBearingDeg !== null && headingDeg !== null ? signedHeadingDeltaDegrees(routeBearingDeg, headingDeg) : null;
  const distanceFactor = clamp(projectedDistanceM / 6, 0, 1);
  const screenShiftX =
    headingRouteDeltaDeg === null
      ? 0
      : clamp(
          Math.sin((headingRouteDeltaDeg * Math.PI) / 180) * MAX_SCREEN_SHIFT_X * Math.max(0.35, distanceFactor),
          -MAX_SCREEN_SHIFT_X,
          MAX_SCREEN_SHIFT_X
        );
  const accuracyScore =
    args.gps?.accuracy_m === null || args.gps?.accuracy_m === undefined
      ? 0.45
      : clamp(1 - Math.max(0, args.gps.accuracy_m - 5) / 45, 0.15, 1);
  const sensorScore = speedSource === "gps_step" ? 1 : speedSource === "stationary" ? 0 : 0.7;
  const stabilityScore =
    typeof args.motionStability === "number" && Number.isFinite(args.motionStability)
      ? clamp(args.motionStability, 0, 1)
      : 0.7;
  const alignmentScore = headingRouteDeltaDeg === null ? 0.7 : clamp(1 - Math.abs(headingRouteDeltaDeg) / 120, 0.1, 1);
  const confidence = clamp(accuracyScore * 0.35 + sensorScore * 0.3 + stabilityScore * 0.2 + alignmentScore * 0.15, 0, 1);

  return {
    horizonS,
    projectedDistanceM,
    projectedPoint,
    bearingDeg,
    routeBearingDeg,
    headingDeg,
    headingRouteDeltaDeg,
    screenShiftX,
    speedMps,
    speedSource,
    confidence
  };
}

function isNormalTactileBlock(detection: TwoModelDetection): boolean {
  return (
    (detection.model_key === "custom_tactile" || detection.model_key === "unified_walksafe") &&
    detection.class_name === "normal_tactile_block"
  );
}

export function evaluateTactileRouteSupport(
  detections: readonly TwoModelDetection[],
  motion: FutureMotionProjection | null,
  options: TactileRouteSupportOptions = {
    supervisedFieldEnabled: false,
    expectedRouteId: null,
    projectionEvidence: null
  }
): TactileRouteSupport {
  const fallbackCorridorCenterX = 0.5;
  const fallbackCorridorHalfWidth = 0.18;
  if (!options.supervisedFieldEnabled) {
    return {
      supported: false,
      detection: null,
      reason: "supervised_disabled",
      corridorCenterX: fallbackCorridorCenterX,
      corridorHalfWidth: fallbackCorridorHalfWidth
    };
  }
  const evidence = options.projectionEvidence;
  if (!evidence) {
    return {
      supported: false,
      detection: null,
      reason: "projection_unavailable",
      corridorCenterX: fallbackCorridorCenterX,
      corridorHalfWidth: fallbackCorridorHalfWidth
    };
  }
  const nowMs = options.nowMs ?? Date.now();
  const evidenceAgeMs = nowMs - evidence.observedAtMs;
  const projectionValid =
    evidence.source === "walksafe.camera_route_projection.v1" &&
    typeof options.expectedRouteId === "string" &&
    options.expectedRouteId.length > 0 &&
    evidence.routeId === options.expectedRouteId &&
    Number.isFinite(evidence.observedAtMs) &&
    evidenceAgeMs >= 0 &&
    evidenceAgeMs <= CAMERA_ROUTE_PROJECTION_MAX_AGE_MS &&
    Number.isFinite(evidence.confidence) &&
    evidence.confidence >= CAMERA_ROUTE_PROJECTION_MIN_CONFIDENCE &&
    Number.isFinite(evidence.corridorCenterX) &&
    Number.isFinite(evidence.corridorHalfWidth) &&
    evidence.corridorCenterX >= 0 &&
    evidence.corridorCenterX <= 1 &&
    evidence.corridorHalfWidth >= 0.05 &&
    evidence.corridorHalfWidth <= 0.35 &&
    evidence.corridorCenterX - evidence.corridorHalfWidth >= 0 &&
    evidence.corridorCenterX + evidence.corridorHalfWidth <= 1;
  if (!projectionValid) {
    return {
      supported: false,
      detection: null,
      reason: "projection_invalid",
      corridorCenterX: fallbackCorridorCenterX,
      corridorHalfWidth: fallbackCorridorHalfWidth
    };
  }
  const corridorCenterX = evidence.corridorCenterX;
  const corridorHalfWidth = evidence.corridorHalfWidth;
  if (!motion || motion.headingDeg === null || motion.routeBearingDeg === null) {
    return { supported: false, detection: null, reason: "missing_motion", corridorCenterX, corridorHalfWidth };
  }
  if (
    motion.headingRouteDeltaDeg === null ||
    Math.abs(motion.headingRouteDeltaDeg) > TACTILE_ROUTE_POLICY_THRESHOLDS.maximumRouteHeadingDeltaDeg
  ) {
    return { supported: false, detection: null, reason: "route_not_visible", corridorCenterX, corridorHalfWidth };
  }
  if (motion.speedMps < MIN_PROJECTION_SPEED_MPS || motion.projectedDistanceM < 0.75) {
    return { supported: false, detection: null, reason: "stationary", corridorCenterX, corridorHalfWidth };
  }
  if (motion.confidence < 0.35) {
    return { supported: false, detection: null, reason: "low_projection_confidence", corridorCenterX, corridorHalfWidth };
  }

  const normalTactile = detections
    .filter(isNormalTactileBlock)
    .filter((detection) => detection.confidence >= TACTILE_ROUTE_POLICY_THRESHOLDS.minimumConfidence)
    .sort((left, right) => right.confidence - left.confidence);
  if (normalTactile.length === 0) {
    return { supported: false, detection: null, reason: "no_normal_tactile", corridorCenterX, corridorHalfWidth };
  }
  const matched = normalTactile.find((detection) => {
    const centerX = detection.bbox.x + detection.bbox.width / 2;
    const centerY = detection.bbox.y + detection.bbox.height / 2;
    const area = Math.max(0, detection.bbox.width) * Math.max(0, detection.bbox.height);
    return centerY >= 0.46 && area >= 0.01 && Math.abs(centerX - corridorCenterX) <= corridorHalfWidth;
  });
  if (!matched) {
    return { supported: false, detection: null, reason: "outside_future_roi", corridorCenterX, corridorHalfWidth };
  }
  return { supported: true, detection: matched, reason: "supported", corridorCenterX, corridorHalfWidth };
}
