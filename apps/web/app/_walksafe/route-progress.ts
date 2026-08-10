/**
 * Pure route projection policy. GPS accuracy overlap remains uncertain, and an off-route result
 * becomes actionable only after consecutive samples confirm it.
 */
import type { RoutePoint, WalkingRouteResponse } from "@/types/navigation";

const EARTH_RADIUS_M = 6371000;

export const DEFAULT_OFF_ROUTE_THRESHOLD_M = 25;
export const DEFAULT_REQUIRED_CONSECUTIVE_OFF_ROUTE_COUNT = 2;
export const DEFAULT_ARRIVAL_MAX_ACCURACY_M = 20;
export const DEFAULT_REQUIRED_CONSECUTIVE_ARRIVAL_COUNT = 2;
export const DEFAULT_MAX_ROUTE_PROGRESS_BACKTRACK_M = 8;
export const DEFAULT_MAX_ROUTE_PROGRESS_ADVANCE_M = 80;

export type OffRouteStatus = "on_route" | "off_route_candidate" | "off_route" | "unknown";

export type RouteMeasureSource = "polyline" | "steps" | "empty";

export type RouteMeasure = {
  source: RouteMeasureSource;
  points: RoutePoint[];
  cumulativeDistancesM: number[];
  totalDistanceM: number;
};

export type RouteProjection = {
  distanceToRouteM: number;
  distanceFromStartM: number;
  progressRatio: number;
  nearestPoint: RoutePoint;
  segmentIndex: number | null;
};

export type RouteProgressReason =
  | "empty_route"
  | "invalid_point"
  | "within_threshold"
  | "outside_threshold"
  | "accuracy_overlap"
  | "accuracy_uncertain";

export type RouteProgressEvaluation = {
  status: OffRouteStatus;
  distanceToRouteM: number | null;
  distanceFromStartM: number | null;
  progressRatio: number | null;
  thresholdM: number;
  accuracyM: number | null;
  reason: RouteProgressReason;
};

export type EvaluateOffRouteStatusParams = {
  route?: Pick<WalkingRouteResponse, "polyline" | "steps"> | null;
  measure?: RouteMeasure | null;
  currentPoint: RoutePoint | null | undefined;
  accuracyM?: number | null;
  offRouteThresholdM?: number | null;
  previousDistanceFromStartM?: number | null;
  maxBacktrackM?: number | null;
  maxAdvanceM?: number | null;
};

export type RouteProjectionContinuity = {
  previousDistanceFromStartM?: number | null;
  maxBacktrackM?: number | null;
  maxAdvanceM?: number | null;
};

export type StabilizeOffRouteStatusOptions = {
  requiredConsecutiveOffRouteCount?: number | null;
};

export type StabilizedOffRouteReason =
  | "no_samples"
  | "latest_on_route"
  | "latest_unknown"
  | "latest_accuracy_uncertain"
  | "latest_off_route_candidate"
  | "off_route_candidate_until_threshold"
  | "consecutive_off_route_confirmed";

export type StabilizedOffRouteStatus = {
  status: OffRouteStatus;
  confirmed: boolean;
  consecutiveOffRouteCount: number;
  requiredConsecutiveOffRouteCount: number;
  reason: StabilizedOffRouteReason;
  latestSample: RouteProgressEvaluation | null;
};

export type RouteArrivalStatus = {
  reached: boolean;
  eligible: boolean;
  distanceToDestinationM: number | null;
  thresholdM: number;
  accuracyM: number | null;
  maxAccuracyM: number;
  reason:
    | "invalid_route_or_point"
    | "accuracy_unknown"
    | "accuracy_poor"
    | "route_progress_incomplete"
    | "outside_radius"
    | "within_radius";
};

export type StabilizedRouteArrivalStatus = {
  reached: boolean;
  consecutiveArrivalCount: number;
  requiredConsecutiveArrivalCount: number;
  latestSample: RouteArrivalStatus | null;
};

type LocalPoint = {
  x: number;
  y: number;
};

function isValidRoutePoint(point: RoutePoint | null | undefined): point is RoutePoint {
  return (
    point !== null &&
    point !== undefined &&
    Number.isFinite(point.latitude) &&
    Number.isFinite(point.longitude) &&
    point.latitude >= -90 &&
    point.latitude <= 90 &&
    point.longitude >= -180 &&
    point.longitude <= 180
  );
}

function sameCoordinates(left: RoutePoint, right: RoutePoint): boolean {
  return left.latitude === right.latitude && left.longitude === right.longitude;
}

function normalizeRoutePoints(points: RoutePoint[]): RoutePoint[] {
  const normalized: RoutePoint[] = [];

  for (const point of points) {
    if (!isValidRoutePoint(point)) {
      continue;
    }
    const previous = normalized[normalized.length - 1];
    if (!previous || !sameCoordinates(previous, point)) {
      normalized.push(point);
    }
  }

  return normalized;
}

function normalizePositive(value: number | null | undefined, fallback: number): number {
  if (typeof value === "number" && Number.isFinite(value) && value > 0) {
    return value;
  }
  return fallback;
}

function normalizeAccuracy(value: number | null | undefined): number | null {
  if (typeof value === "number" && Number.isFinite(value) && value >= 0) {
    return value;
  }
  return null;
}

function normalizePositiveInteger(value: number | null | undefined, fallback: number): number {
  if (typeof value === "number" && Number.isFinite(value) && value > 0) {
    return Math.max(1, Math.floor(value));
  }
  return fallback;
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value));
}

function isConfirmableOffRouteSample(sample: RouteProgressEvaluation): boolean {
  return sample.status === "off_route" && sample.reason === "outside_threshold";
}

function emptyRouteMeasure(): RouteMeasure {
  return {
    source: "empty",
    points: [],
    cumulativeDistancesM: [],
    totalDistanceM: 0
  };
}

export function distanceMeters(from: RoutePoint, to: RoutePoint): number {
  const fromLat = (from.latitude * Math.PI) / 180;
  const toLat = (to.latitude * Math.PI) / 180;
  const deltaLat = ((to.latitude - from.latitude) * Math.PI) / 180;
  const deltaLng = ((to.longitude - from.longitude) * Math.PI) / 180;
  const a =
    Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
    Math.cos(fromLat) * Math.cos(toLat) * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
  return EARTH_RADIUS_M * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
}

export function buildRouteMeasure(route: Pick<WalkingRouteResponse, "polyline" | "steps">): RouteMeasure {
  const polylinePoints = normalizeRoutePoints(route.polyline);
  const stepPoints = normalizeRoutePoints(route.steps.flatMap((step) => step.points));

  let points = polylinePoints;
  let source: RouteMeasureSource = polylinePoints.length > 0 ? "polyline" : "empty";

  if (polylinePoints.length < 2 && stepPoints.length > 0) {
    points = stepPoints;
    source = "steps";
  }

  if (points.length === 0) {
    return emptyRouteMeasure();
  }

  const cumulativeDistancesM = [0];
  for (let index = 1; index < points.length; index += 1) {
    cumulativeDistancesM.push(cumulativeDistancesM[index - 1] + distanceMeters(points[index - 1], points[index]));
  }

  return {
    source,
    points,
    cumulativeDistancesM,
    totalDistanceM: cumulativeDistancesM[cumulativeDistancesM.length - 1] ?? 0
  };
}

function localFrame(reference: RoutePoint) {
  const referenceLatitudeRad = (reference.latitude * Math.PI) / 180;
  const longitudeScale = Math.max(Math.cos(referenceLatitudeRad), 0.000001);

  return {
    toLocal(point: RoutePoint): LocalPoint {
      return {
        x: ((point.longitude - reference.longitude) * Math.PI * EARTH_RADIUS_M * longitudeScale) / 180,
        y: ((point.latitude - reference.latitude) * Math.PI * EARTH_RADIUS_M) / 180
      };
    },
    toRoutePoint(point: LocalPoint): RoutePoint {
      return {
        latitude: reference.latitude + (point.y / EARTH_RADIUS_M) * (180 / Math.PI),
        longitude: reference.longitude + (point.x / (EARTH_RADIUS_M * longitudeScale)) * (180 / Math.PI)
      };
    }
  };
}

export function projectPointToRoute(
  point: RoutePoint,
  measure: RouteMeasure,
  continuity: RouteProjectionContinuity = {}
): RouteProjection | null {
  if (!isValidRoutePoint(point) || measure.points.length === 0) {
    return null;
  }

  if (measure.points.length === 1) {
    return {
      distanceToRouteM: distanceMeters(point, measure.points[0]),
      distanceFromStartM: 0,
      progressRatio: 0,
      nearestPoint: measure.points[0],
      segmentIndex: null
    };
  }

  const frame = localFrame(measure.points[0]);
  const projectedPoint = frame.toLocal(point);
  const localRoutePoints = measure.points.map((routePoint) => frame.toLocal(routePoint));
  let bestDistanceToRouteM = Number.POSITIVE_INFINITY;
  let bestDistanceFromStartM = 0;
  let bestNearestPoint = measure.points[0];
  let bestSegmentIndex = 0;
  let continuousBest: RouteProjection | null = null;
  const previousDistanceFromStartM =
    typeof continuity.previousDistanceFromStartM === "number" &&
    Number.isFinite(continuity.previousDistanceFromStartM) &&
    continuity.previousDistanceFromStartM >= 0
      ? continuity.previousDistanceFromStartM
      : null;
  const minimumContinuousDistanceM =
    previousDistanceFromStartM === null
      ? 0
      : Math.max(0, previousDistanceFromStartM - normalizePositive(continuity.maxBacktrackM, DEFAULT_MAX_ROUTE_PROGRESS_BACKTRACK_M));
  const maximumContinuousDistanceM =
    previousDistanceFromStartM === null
      ? Number.POSITIVE_INFINITY
      : previousDistanceFromStartM + normalizePositive(continuity.maxAdvanceM, DEFAULT_MAX_ROUTE_PROGRESS_ADVANCE_M);

  for (let index = 0; index < localRoutePoints.length - 1; index += 1) {
    const from = localRoutePoints[index];
    const to = localRoutePoints[index + 1];
    const dx = to.x - from.x;
    const dy = to.y - from.y;
    const segmentLengthSq = dx * dx + dy * dy;
    if (segmentLengthSq <= 0) {
      continue;
    }

    const rawT = ((projectedPoint.x - from.x) * dx + (projectedPoint.y - from.y) * dy) / segmentLengthSq;
    const t = clamp01(rawT);
    const nearestLocal = {
      x: from.x + dx * t,
      y: from.y + dy * t
    };
    const distanceToSegmentM = Math.hypot(projectedPoint.x - nearestLocal.x, projectedPoint.y - nearestLocal.y);
    const segmentDistanceM = (measure.cumulativeDistancesM[index + 1] ?? 0) - (measure.cumulativeDistancesM[index] ?? 0);
    const distanceFromStartM = (measure.cumulativeDistancesM[index] ?? 0) + segmentDistanceM * t;
    const candidate: RouteProjection = {
      distanceToRouteM: distanceToSegmentM,
      distanceFromStartM,
      progressRatio: measure.totalDistanceM > 0 ? clamp01(distanceFromStartM / measure.totalDistanceM) : 0,
      nearestPoint: frame.toRoutePoint(nearestLocal),
      segmentIndex: index
    };

    if (distanceToSegmentM < bestDistanceToRouteM) {
      bestDistanceToRouteM = distanceToSegmentM;
      bestDistanceFromStartM = distanceFromStartM;
      bestNearestPoint = candidate.nearestPoint;
      bestSegmentIndex = index;
    }

    if (
      previousDistanceFromStartM !== null &&
      distanceFromStartM >= minimumContinuousDistanceM &&
      distanceFromStartM <= maximumContinuousDistanceM &&
      (!continuousBest ||
        distanceToSegmentM < continuousBest.distanceToRouteM - 0.01 ||
        (Math.abs(distanceToSegmentM - continuousBest.distanceToRouteM) <= 0.01 &&
          Math.abs(distanceFromStartM - previousDistanceFromStartM) <
            Math.abs(continuousBest.distanceFromStartM - previousDistanceFromStartM)))
    ) {
      continuousBest = candidate;
    }
  }

  if (!Number.isFinite(bestDistanceToRouteM)) {
    return null;
  }

  return continuousBest ?? {
    distanceToRouteM: bestDistanceToRouteM,
    distanceFromStartM: bestDistanceFromStartM,
    progressRatio: measure.totalDistanceM > 0 ? clamp01(bestDistanceFromStartM / measure.totalDistanceM) : 0,
    nearestPoint: bestNearestPoint,
    segmentIndex: bestSegmentIndex
  };
}

export function evaluateOffRouteStatus(params: EvaluateOffRouteStatusParams): RouteProgressEvaluation {
  const thresholdM = normalizePositive(params.offRouteThresholdM, DEFAULT_OFF_ROUTE_THRESHOLD_M);
  const accuracyM = normalizeAccuracy(params.accuracyM);
  const measure = params.measure ?? (params.route ? buildRouteMeasure(params.route) : emptyRouteMeasure());
  const projection = params.currentPoint
    ? projectPointToRoute(params.currentPoint, measure, {
        previousDistanceFromStartM: params.previousDistanceFromStartM,
        maxBacktrackM: params.maxBacktrackM,
        maxAdvanceM: params.maxAdvanceM
      })
    : null;

  if (!projection) {
    return {
      status: "unknown",
      distanceToRouteM: null,
      distanceFromStartM: null,
      progressRatio: null,
      thresholdM,
      accuracyM,
      reason: measure.points.length === 0 ? "empty_route" : "invalid_point"
    };
  }

  const base = {
    distanceToRouteM: projection.distanceToRouteM,
    distanceFromStartM: projection.distanceFromStartM,
    progressRatio: projection.progressRatio,
    thresholdM,
    accuracyM
  };

  if (accuracyM === null) {
    return {
      ...base,
      status: "unknown",
      reason: "accuracy_uncertain"
    };
  }

  if (projection.distanceToRouteM <= thresholdM) {
    if (accuracyM > thresholdM) {
      return {
        ...base,
        status: "unknown",
        reason: "accuracy_uncertain"
      };
    }
    return {
      ...base,
      status: "on_route",
      reason: "within_threshold"
    };
  }

  if (accuracyM > thresholdM) {
    return {
      ...base,
      status: "off_route_candidate",
      reason: "accuracy_uncertain"
    };
  }

  if (projection.distanceToRouteM <= thresholdM + accuracyM) {
    return {
      ...base,
      status: "off_route_candidate",
      reason: "accuracy_overlap"
    };
  }

  return {
    ...base,
    status: "off_route",
    reason: "outside_threshold"
  };
}

export function evaluateRouteArrivalStatus(args: {
  route: Pick<WalkingRouteResponse, "polyline" | "steps"> | null | undefined;
  currentPoint: RoutePoint | null | undefined;
  destination?: RoutePoint | null;
  progressRatio?: number | null;
  arrivalRadiusM?: number | null;
  accuracyM?: number | null;
  maxAccuracyM?: number | null;
}): RouteArrivalStatus {
  const thresholdM = normalizePositive(args.arrivalRadiusM, 8);
  const accuracyM = normalizeAccuracy(args.accuracyM);
  const maxAccuracyM = normalizePositive(args.maxAccuracyM, DEFAULT_ARRIVAL_MAX_ACCURACY_M);
  const measure = args.route ? buildRouteMeasure(args.route) : emptyRouteMeasure();
  const destination = isValidRoutePoint(args.destination)
    ? args.destination
    : (measure.points[measure.points.length - 1] ?? null);

  if (!destination || !isValidRoutePoint(args.currentPoint)) {
    return {
      reached: false,
      eligible: false,
      distanceToDestinationM: null,
      thresholdM,
      accuracyM,
      maxAccuracyM,
      reason: "invalid_route_or_point"
    };
  }

  const distanceToDestinationM = distanceMeters(args.currentPoint, destination);
  if (accuracyM === null) {
    return {
      reached: false,
      eligible: false,
      distanceToDestinationM,
      thresholdM,
      accuracyM,
      maxAccuracyM,
      reason: "accuracy_unknown"
    };
  }
  if (accuracyM > maxAccuracyM) {
    return {
      reached: false,
      eligible: false,
      distanceToDestinationM,
      thresholdM,
      accuracyM,
      maxAccuracyM,
      reason: "accuracy_poor"
    };
  }

  if (typeof args.progressRatio !== "number" || !Number.isFinite(args.progressRatio) || args.progressRatio < 0.9) {
    return {
      reached: false,
      eligible: false,
      distanceToDestinationM,
      thresholdM,
      accuracyM,
      maxAccuracyM,
      reason: "route_progress_incomplete"
    };
  }

  const reached = distanceToDestinationM + accuracyM <= thresholdM;
  return {
    reached,
    eligible: true,
    distanceToDestinationM,
    thresholdM,
    accuracyM,
    maxAccuracyM,
    reason: reached ? "within_radius" : "outside_radius"
  };
}

export function stabilizeRouteArrivalStatus(
  samples: RouteArrivalStatus[],
  options: { requiredConsecutiveArrivalCount?: number | null } = {}
): StabilizedRouteArrivalStatus {
  const requiredConsecutiveArrivalCount = normalizePositiveInteger(
    options.requiredConsecutiveArrivalCount,
    DEFAULT_REQUIRED_CONSECUTIVE_ARRIVAL_COUNT
  );
  const latestSample = samples[samples.length - 1] ?? null;
  let consecutiveArrivalCount = 0;

  for (let index = samples.length - 1; index >= 0; index -= 1) {
    if (!samples[index]?.reached) {
      break;
    }
    consecutiveArrivalCount += 1;
  }

  return {
    reached: consecutiveArrivalCount >= requiredConsecutiveArrivalCount,
    consecutiveArrivalCount,
    requiredConsecutiveArrivalCount,
    latestSample
  };
}

export function stabilizeOffRouteStatus(
  samples: RouteProgressEvaluation[],
  options: StabilizeOffRouteStatusOptions = {}
): StabilizedOffRouteStatus {
  const requiredConsecutiveOffRouteCount = normalizePositiveInteger(
    options.requiredConsecutiveOffRouteCount,
    DEFAULT_REQUIRED_CONSECUTIVE_OFF_ROUTE_COUNT
  );
  const latestSample = samples[samples.length - 1] ?? null;

  if (!latestSample) {
    return {
      status: "unknown",
      confirmed: false,
      consecutiveOffRouteCount: 0,
      requiredConsecutiveOffRouteCount,
      reason: "no_samples",
      latestSample
    };
  }

  if (latestSample.status === "on_route") {
    return {
      status: "on_route",
      confirmed: false,
      consecutiveOffRouteCount: 0,
      requiredConsecutiveOffRouteCount,
      reason: "latest_on_route",
      latestSample
    };
  }

  if (latestSample.status === "unknown") {
    return {
      status: "unknown",
      confirmed: false,
      consecutiveOffRouteCount: 0,
      requiredConsecutiveOffRouteCount,
      reason: latestSample.reason === "accuracy_uncertain" ? "latest_accuracy_uncertain" : "latest_unknown",
      latestSample
    };
  }

  let consecutiveOffRouteCount = 0;
  for (let index = samples.length - 1; index >= 0; index -= 1) {
    const sample = samples[index];
    if (!sample || !isConfirmableOffRouteSample(sample)) {
      break;
    }
    consecutiveOffRouteCount += 1;
  }

  if (consecutiveOffRouteCount >= requiredConsecutiveOffRouteCount) {
    return {
      status: "off_route",
      confirmed: true,
      consecutiveOffRouteCount,
      requiredConsecutiveOffRouteCount,
      reason: "consecutive_off_route_confirmed",
      latestSample
    };
  }

  return {
    status: "off_route_candidate",
    confirmed: false,
    consecutiveOffRouteCount,
    requiredConsecutiveOffRouteCount,
    reason:
      latestSample.reason === "accuracy_uncertain"
        ? "latest_accuracy_uncertain"
        : latestSample.status === "off_route_candidate"
          ? "latest_off_route_candidate"
          : "off_route_candidate_until_threshold",
    latestSample
  };
}
