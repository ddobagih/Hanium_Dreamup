import type { NormalizedBBoxV2, TwoModelDetection } from "@/types/inference-v2";
import type { PathRelation, RiskEvaluationContext } from "./risk-evaluator";

export type RoiRiskOptions = {
  centerBandMinX?: number;
  centerBandMaxX?: number;
  nearBandMinX?: number;
  nearBandMaxX?: number;
  lowerHalfMinY?: number;
  minBlockingArea?: number;
  maxMotionShiftX?: number;
  minMotionSpeedMps?: number;
  excludeClassNames?: readonly string[];
  motion?: RoiMotionContext;
  debug?: boolean;
};

export type RoiMotionContext = {
  heading_deg?: number | null;
  route_bearing_deg?: number | null;
  walking_speed_mps?: number | null;
};

export type RoiRiskResult = {
  pathRelation: PathRelation;
  blockingPath: boolean;
  projectedPathIntersection: boolean;
  centerX: number;
  centerY: number;
  area: number;
  reason: string;
  debug?: {
    centerBandMinX: number;
    centerBandMaxX: number;
    lowerHalfMinY: number;
    motionShiftX: number;
    headingKnown: boolean;
    speedKnown: boolean;
    excludedByClass: boolean;
  };
};

const DEFAULT_NON_BLOCKING_GENERAL_CLASSES = ["traffic light"] as const;

const DEFAULT_ROI_OPTIONS: Required<RoiRiskOptions> = {
  centerBandMinX: 0.35,
  centerBandMaxX: 0.65,
  nearBandMinX: 0.2,
  nearBandMaxX: 0.8,
  lowerHalfMinY: 0.48,
  minBlockingArea: 0.025,
  maxMotionShiftX: 0.08,
  minMotionSpeedMps: 0.15,
  excludeClassNames: DEFAULT_NON_BLOCKING_GENERAL_CLASSES,
  motion: {},
  debug: false
};

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value));
}

function bboxCenter(bbox: NormalizedBBoxV2): { x: number; y: number } {
  return {
    x: clamp01(bbox.x + bbox.width / 2),
    y: clamp01(bbox.y + bbox.height / 2)
  };
}

function bboxArea(bbox: NormalizedBBoxV2): number {
  return Math.max(0, bbox.width) * Math.max(0, bbox.height);
}

function isGeneralObstacle(detection: TwoModelDetection): boolean {
  return detection.model_key === "coco_general";
}

function normalizeClassName(className: string): string {
  return className.trim().toLowerCase();
}

function normalizeDeltaDegrees(delta: number): number {
  return ((((delta + 180) % 360) + 360) % 360) - 180;
}

function resolveMotionShiftX(
  motion: RoiMotionContext,
  maxMotionShiftX: number,
  minMotionSpeedMps: number
): { shiftX: number; headingKnown: boolean; speedKnown: boolean } {
  const headingKnown = typeof motion.heading_deg === "number" && Number.isFinite(motion.heading_deg);
  const routeKnown = typeof motion.route_bearing_deg === "number" && Number.isFinite(motion.route_bearing_deg);
  const speedKnown =
    typeof motion.walking_speed_mps === "number" &&
    Number.isFinite(motion.walking_speed_mps) &&
    motion.walking_speed_mps >= minMotionSpeedMps;

  if (!headingKnown || !routeKnown || !speedKnown) {
    return { shiftX: 0, headingKnown: headingKnown && routeKnown, speedKnown };
  }

  const delta = normalizeDeltaDegrees((motion.heading_deg ?? 0) - (motion.route_bearing_deg ?? 0));
  return {
    shiftX: Math.max(-maxMotionShiftX, Math.min(maxMotionShiftX, (delta / 90) * maxMotionShiftX)),
    headingKnown: true,
    speedKnown: true
  };
}

function resultDebug(
  enabled: boolean,
  options: Required<RoiRiskOptions>,
  motionShiftX: number,
  headingKnown: boolean,
  speedKnown: boolean,
  excludedByClass: boolean
): RoiRiskResult["debug"] {
  if (!enabled) {
    return undefined;
  }

  return {
    centerBandMinX: options.centerBandMinX + motionShiftX,
    centerBandMaxX: options.centerBandMaxX + motionShiftX,
    lowerHalfMinY: options.lowerHalfMinY,
    motionShiftX,
    headingKnown,
    speedKnown,
    excludedByClass
  };
}

export function evaluateRoiRisk(detection: TwoModelDetection, options: RoiRiskOptions = {}): RoiRiskResult {
  const resolvedOptions = { ...DEFAULT_ROI_OPTIONS, ...options };
  const motion = resolveMotionShiftX(
    resolvedOptions.motion,
    resolvedOptions.maxMotionShiftX,
    resolvedOptions.minMotionSpeedMps
  );
  const centerBandMinX = resolvedOptions.centerBandMinX + motion.shiftX;
  const centerBandMaxX = resolvedOptions.centerBandMaxX + motion.shiftX;
  const center = bboxCenter(detection.bbox);
  const area = bboxArea(detection.bbox);
  const isLowerHalf = center.y >= resolvedOptions.lowerHalfMinY;
  const isCenterBand = center.x >= centerBandMinX && center.x <= centerBandMaxX;
  const isNearBand = center.x >= resolvedOptions.nearBandMinX && center.x <= resolvedOptions.nearBandMaxX;
  const isLargeEnough = area >= resolvedOptions.minBlockingArea;
  const excludedByClass = resolvedOptions.excludeClassNames
    .map(normalizeClassName)
    .includes(normalizeClassName(detection.class_name));
  const debug = resultDebug(
    resolvedOptions.debug,
    resolvedOptions,
    motion.shiftX,
    motion.headingKnown,
    motion.speedKnown,
    excludedByClass
  );

  if (!isGeneralObstacle(detection)) {
    return {
      pathRelation: "unknown",
      blockingPath: false,
      projectedPathIntersection: false,
      centerX: center.x,
      centerY: center.y,
      area,
      reason: "ROI 정책은 general 객체에만 적용합니다.",
      ...(debug ? { debug } : {})
    };
  }

  if (excludedByClass) {
    return {
      pathRelation: "off_path",
      blockingPath: false,
      projectedPathIntersection: false,
      centerX: center.x,
      centerY: center.y,
      area,
      reason: "비차단 클래스로 분류되어 보행 경로 차단 판단에서 제외했습니다.",
      ...(debug ? { debug } : {})
    };
  }

  if (isLowerHalf && isCenterBand && isLargeEnough) {
    return {
      pathRelation: "on_path",
      blockingPath: true,
      projectedPathIntersection: true,
      centerX: center.x,
      centerY: center.y,
      area,
      reason: "객체 중심이 보행 진행 ROI 중앙 하단에 있습니다.",
      ...(debug ? { debug } : {})
    };
  }

  if (isLowerHalf && isNearBand && isLargeEnough) {
    return {
      pathRelation: "near_path",
      blockingPath: false,
      projectedPathIntersection: false,
      centerX: center.x,
      centerY: center.y,
      area,
      reason: "객체가 진행 ROI 주변에 있습니다.",
      ...(debug ? { debug } : {})
    };
  }

  return {
    pathRelation: "off_path",
    blockingPath: false,
    projectedPathIntersection: false,
    centerX: center.x,
    centerY: center.y,
    area,
    reason: "객체가 진행 ROI 밖이거나 너무 작습니다.",
    ...(debug ? { debug } : {})
  };
}

export function buildRoiRiskContext(detection: TwoModelDetection, options: RoiRiskOptions = {}): RiskEvaluationContext {
  const roi = evaluateRoiRisk(detection, options);
  return {
    heading: options.motion?.heading_deg ?? null,
    imu: {
      walking_speed_mps: options.motion?.walking_speed_mps ?? null
    },
    segmentation: {
      path_relation: roi.pathRelation
    },
    tracking: {
      stable_frames: roi.blockingPath ? 3 : 0,
      blocking_path: roi.blockingPath,
      projected_path_intersection: roi.projectedPathIntersection
    }
  };
}
