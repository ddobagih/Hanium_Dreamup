/** Admits only fresh, stable, route-aligned normal tactile blocks as short local steering evidence. */
import type { TwoModelDetection } from "@/types/inference-v2";

export const TACTILE_ROUTE_POLICY_THRESHOLDS = {
  minimumConfidence: 0.55,
  minimumStableFrames: 3,
  minimumStableMs: 700,
  maximumDetectionAgeMs: 1200,
  maximumRouteHeadingDeltaDeg: 35,
  maximumGpsAccuracyM: 25,
  steeringDeadZone: 0.15
} as const;

export type TactileLocalSteering = "left" | "straight" | "right";
export type TactileRouteMode = "tmap" | "tactile_local";
export type TactileRouteReason =
  | "navigation_inactive"
  | "tmap_off_route"
  | "gps_untrusted"
  | "tactile_not_visible"
  | "tactile_not_traversable"
  | "tactile_confidence_low"
  | "tactile_not_stable"
  | "tactile_stale"
  | "route_heading_mismatch"
  | "tactile_outside_tmap_corridor"
  | "tactile_geometry_invalid"
  | "stable_aligned_tactile";

export type TactileRouteObservation = {
  class_name: string;
  confidence: number;
  stable_frames: number;
  stable_ms: number;
  age_ms: number;
  route_heading_delta_deg: number;
  overlaps_tmap_corridor: boolean;
  center_x_normalized: number;
};

export type TactileRoutePolicyInput = {
  navigation_active: boolean;
  tmap_on_route: boolean;
  gps_accuracy_m: number | null;
  tactile: TactileRouteObservation | null;
};

export type TactileRoutePolicyDecision = {
  mode: TactileRouteMode;
  steering: TactileLocalSteering | null;
  reason: TactileRouteReason;
};

export type TactileObservationTracker = {
  className: string;
  firstCapturedAtMs: number;
  lastCapturedAtMs: number;
  evaluatedAtMs: number;
  stableFrames: number;
  centerX: number;
  centerY: number;
  bboxX: number;
  bboxY: number;
  bboxWidth: number;
  bboxHeight: number;
};

const TACTILE_TRACK_MIN_IOU = 0.35;

function tactileTrackerIou(
  previous: TactileObservationTracker,
  bbox: { x: number; y: number; width: number; height: number }
): number {
  const intersectionLeft = Math.max(previous.bboxX, bbox.x);
  const intersectionTop = Math.max(previous.bboxY, bbox.y);
  const intersectionRight = Math.min(previous.bboxX + previous.bboxWidth, bbox.x + bbox.width);
  const intersectionBottom = Math.min(previous.bboxY + previous.bboxHeight, bbox.y + bbox.height);
  const intersection = Math.max(0, intersectionRight - intersectionLeft) * Math.max(0, intersectionBottom - intersectionTop);
  const union = previous.bboxWidth * previous.bboxHeight + bbox.width * bbox.height - intersection;
  return union > 0 ? intersection / union : 0;
}

export function tactileObservationMatchesTracker(
  tracker: TactileObservationTracker,
  bbox: { x: number; y: number; width: number; height: number }
): boolean {
  return tactileTrackerIou(tracker, bbox) >= TACTILE_TRACK_MIN_IOU;
}

export function selectTactileRouteDetection(
  detections: readonly TwoModelDetection[],
  corridor: { centerX: number; halfWidth: number },
  routeSupportAllowed: boolean
): TwoModelDetection | null {
  if (!routeSupportAllowed) return null;
  return (
    detections
      .filter(
        (detection) =>
          (detection.model_key === "custom_tactile" || detection.model_key === "unified_walksafe") &&
          (detection.class_name === "normal_tactile_block" || detection.class_name === "damaged_tactile_block")
      )
      .filter((detection) => {
        const centerX = detection.bbox.x + detection.bbox.width / 2;
        const centerY = detection.bbox.y + detection.bbox.height / 2;
        const area = Math.max(0, detection.bbox.width) * Math.max(0, detection.bbox.height);
        return centerY >= 0.46 && area >= 0.01 && Math.abs(centerX - corridor.centerX) <= corridor.halfWidth;
      })
      .sort((left, right) => {
        const damagePriority =
          Number(right.class_name === "damaged_tactile_block") - Number(left.class_name === "damaged_tactile_block");
        if (damagePriority !== 0) return damagePriority;
        const capturedDelta = Date.parse(right.captured_at) - Date.parse(left.captured_at);
        if (Number.isFinite(capturedDelta) && capturedDelta !== 0) return capturedDelta;
        return right.confidence - left.confidence;
      })[0] ?? null
  );
}

function tmap(reason: Exclude<TactileRouteReason, "stable_aligned_tactile">): TactileRoutePolicyDecision {
  return { mode: "tmap", steering: null, reason };
}

export function evaluateTactileRoutePolicy(input: TactileRoutePolicyInput): TactileRoutePolicyDecision {
  const threshold = TACTILE_ROUTE_POLICY_THRESHOLDS;
  if (!input.navigation_active) return tmap("navigation_inactive");
  if (!input.tmap_on_route) return tmap("tmap_off_route");
  if (
    typeof input.gps_accuracy_m !== "number" ||
    !Number.isFinite(input.gps_accuracy_m) ||
    input.gps_accuracy_m < 0 ||
    input.gps_accuracy_m > threshold.maximumGpsAccuracyM
  ) {
    return tmap("gps_untrusted");
  }
  const tactile = input.tactile;
  if (!tactile) return tmap("tactile_not_visible");
  if (tactile.class_name !== "normal_tactile_block") return tmap("tactile_not_traversable");
  if (!Number.isFinite(tactile.confidence) || tactile.confidence < threshold.minimumConfidence) {
    return tmap("tactile_confidence_low");
  }
  if (
    !Number.isFinite(tactile.stable_frames) ||
    !Number.isFinite(tactile.stable_ms) ||
    tactile.stable_frames < threshold.minimumStableFrames ||
    tactile.stable_ms < threshold.minimumStableMs
  ) {
    return tmap("tactile_not_stable");
  }
  if (!Number.isFinite(tactile.age_ms) || tactile.age_ms < 0 || tactile.age_ms > threshold.maximumDetectionAgeMs) {
    return tmap("tactile_stale");
  }
  if (
    !Number.isFinite(tactile.route_heading_delta_deg) ||
    Math.abs(tactile.route_heading_delta_deg) > threshold.maximumRouteHeadingDeltaDeg
  ) {
    return tmap("route_heading_mismatch");
  }
  if (!tactile.overlaps_tmap_corridor) return tmap("tactile_outside_tmap_corridor");
  if (
    !Number.isFinite(tactile.center_x_normalized) ||
    tactile.center_x_normalized < 0 ||
    tactile.center_x_normalized > 1
  ) {
    return tmap("tactile_geometry_invalid");
  }

  const offset = tactile.center_x_normalized - 0.5;
  const steering: TactileLocalSteering =
    offset < -threshold.steeringDeadZone
      ? "left"
      : offset > threshold.steeringDeadZone
        ? "right"
        : "straight";
  return { mode: "tactile_local", steering, reason: "stable_aligned_tactile" };
}

export function advanceTactileObservationTracker(
  previous: TactileObservationTracker | null,
  observation: {
    className: string;
    capturedAtMs: number;
    bbox: { x: number; y: number; width: number; height: number };
  } | null,
  evaluatedAtMs: number
): TactileObservationTracker | null {
  const bbox = observation?.bbox;
  if (
    !observation ||
    !bbox ||
    !Number.isFinite(observation.capturedAtMs) ||
    !Number.isFinite(evaluatedAtMs) ||
    observation.capturedAtMs > evaluatedAtMs ||
    ![bbox.x, bbox.y, bbox.width, bbox.height].every(Number.isFinite) ||
    bbox.x < 0 ||
    bbox.y < 0 ||
    bbox.width <= 0 ||
    bbox.height <= 0 ||
    bbox.x + bbox.width > 1 ||
    bbox.y + bbox.height > 1
  ) {
    return null;
  }
  if (previous?.lastCapturedAtMs === observation.capturedAtMs) {
    return { ...previous, evaluatedAtMs };
  }
  const continuous = Boolean(
    previous &&
      previous.className === observation.className &&
      observation.capturedAtMs > previous.lastCapturedAtMs &&
      observation.capturedAtMs - previous.lastCapturedAtMs <= TACTILE_ROUTE_POLICY_THRESHOLDS.maximumDetectionAgeMs &&
      tactileObservationMatchesTracker(previous, bbox)
  );
  const centerX = bbox.x + bbox.width / 2;
  const centerY = bbox.y + bbox.height / 2;
  return {
    className: observation.className,
    firstCapturedAtMs: continuous && previous ? previous.firstCapturedAtMs : observation.capturedAtMs,
    lastCapturedAtMs: observation.capturedAtMs,
    evaluatedAtMs,
    stableFrames: continuous && previous ? previous.stableFrames + 1 : 1,
    centerX,
    centerY,
    bboxX: bbox.x,
    bboxY: bbox.y,
    bboxWidth: bbox.width,
    bboxHeight: bbox.height
  };
}
