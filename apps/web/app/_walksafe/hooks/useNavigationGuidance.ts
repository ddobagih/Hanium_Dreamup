"use client";

/**
 * Browser-side navigation state machine.
 * Destination search, explicit route start and automatic reroute share request/cancellation gates;
 * noisy GPS must be confirmed before it can replace the active route or produce speech.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { fetchWalkingRoute, NavigationApiError, searchDestinations, WALKING_ROUTE_PROVIDER_LABEL } from "@/lib/navigation-api";
import type { GpsFixV2, TwoModelDetection } from "@/types/inference-v2";
import type { RoutePoint, WalkingRouteGuidePoint, WalkingRouteResponse } from "@/types/navigation";
import {
  WALKSAFE_DEFAULT_DESTINATION,
  WALKSAFE_DEFAULT_STEP_LENGTH_M,
  WALKSAFE_DESTINATION_MAX_DISTANCE_M,
  WALKSAFE_DESTINATION_SEARCH_DEBOUNCE_MS,
  WALKSAFE_ARRIVAL_MAX_ACCURACY_M,
  WALKSAFE_ARRIVAL_RADIUS_M,
  WALKSAFE_AUTO_REROUTE_COOLDOWN_MS,
  WALKSAFE_AUTO_REROUTE_MAX_COUNT,
  WALKSAFE_GPS_SAMPLE_MAX_DISTANCE_M,
  WALKSAFE_GPS_SAMPLE_MAX_INTERVAL_MS,
  WALKSAFE_GPS_SAMPLE_MIN_INTERVAL_MS,
  WALKSAFE_GUIDE_SOON_RADIUS_M,
  WALKSAFE_GUIDE_TURN_RADIUS_M,
  WALKSAFE_GUIDANCE_MAX_GPS_ACCURACY_M,
  WALKSAFE_MAX_WALKING_SPEED_MPS,
  WALKSAFE_MIN_WALKING_SPEED_MPS,
  WALKSAFE_OFF_ROUTE_THRESHOLD_M,
  WALKSAFE_REROUTE_MAX_ACCURACY_M,
  WALKSAFE_REROUTE_MAX_GPS_JUMP_M,
  WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING_ENABLED
} from "../config";
import {
  resolveNavigationDestinationPolicy,
  type NavigationDestinationCandidate,
  type NavigationDestinationPolicy
} from "../navigation-destination";
import {
  NavigationRequestCoordinator,
  type NavigationRequestToken
} from "../navigation-request-coordinator";
import {
  evaluateTactileRouteSupport,
  projectFutureMotion,
  resolveRouteBearing,
  type CameraRouteProjectionEvidence,
  type FutureMotionProjection
} from "../motion-projection";
import {
  buildRouteMeasure,
  distanceMeters,
  evaluateRouteArrivalStatus,
  evaluateOffRouteStatus,
  projectPointToRoute,
  stabilizeRouteArrivalStatus,
  stabilizeOffRouteStatus,
  type RouteMeasure,
  type RouteArrivalStatus,
  type RouteProgressEvaluation
} from "../route-progress";
import {
  advanceTactileObservationTracker,
  evaluateTactileRoutePolicy,
  selectTactileRouteDetection,
  tactileObservationMatchesTracker,
  type TactileObservationTracker,
  type TactileRoutePolicyDecision
} from "../tactile-route-policy";

export type NavigationGuidanceStatus =
  | "idle"
  | "missing_location"
  | "missing_destination"
  | "searching_destination"
  | "destination_candidates"
  | "loading"
  | "active"
  | "off_route_candidate"
  | "rerouting"
  | "error";

export type NavigationActionResult = {
  ok: boolean;
  message: string;
};

export type NavigationSpeechCandidate = { prompt: string; key: string };

export function resolveNavigationSpeechCandidate(args: {
  reroute: NavigationSpeechCandidate | null;
  routeStart: NavigationSpeechCandidate | null;
  sensorPause: NavigationSpeechCandidate | null;
  tactileLocal: NavigationSpeechCandidate | null;
  tmapGuide: NavigationSpeechCandidate | null;
}): NavigationSpeechCandidate | null {
  return args.reroute ?? args.sensorPause ?? args.routeStart ?? args.tactileLocal ?? args.tmapGuide;
}

type UseNavigationGuidanceOptions = {
  gps: GpsFixV2 | null;
  heading: number | null;
  v2Detections: TwoModelDetection[];
  stepLengthM?: number;
  stepBasedSpeedMps?: number | null;
  motionStability?: number | null;
  cameraRouteProjectionEvidence?: CameraRouteProjectionEvidence | null;
};

const ROUTE_START_PROMPT_MS = 6000;
export const WALKSAFE_ROUTE_REQUEST_COOLDOWN_MS = 10000;
const EARLY_GUIDE_SECONDS = 10;
const SOON_GUIDE_SECONDS = 3;

export type GpsSample = {
  point: RoutePoint;
  observedAtMs: number;
  accuracyM: number | null;
};

type RouteProgressHistory = {
  routeId: string | null;
  samples: RouteProgressEvaluation[];
};

type AutoRerouteState = {
  count: number;
};

type RouteArrivalHistory = {
  routeId: string | null;
  samples: RouteArrivalStatus[];
};

type RouteProjectionAnchor = {
  routeId: string | null;
  distanceFromStartM: number | null;
};

type GuideAnnouncementStage = "prepare10" | "soon3" | "now";
type GuideActionKind = "left" | "right" | "uturn" | "crosswalk" | "stairs" | "destination" | "straight" | "direction";

type GuideAction = {
  kind: GuideActionKind;
  label: string;
};

export type NavigationGuidePrompt = {
  key: string;
  prompt: string;
  distanceM: number;
  etaS: number | null;
  steps: number;
  stage: GuideAnnouncementStage;
};

export type NavigationRouteRequestGateResult = {
  allowed: boolean;
  message: string | null;
  remainingCooldownMs: number;
};

export type ActiveNavigationSensorGate = {
  allowed: boolean;
  reason: "ready" | "gps_missing" | "gps_accuracy_unknown" | "gps_accuracy_poor";
  message: string | null;
};

export function resolveActiveNavigationSensorGate(args: {
  gps: GpsFixV2 | null;
  maxGpsAccuracyM?: number;
}): ActiveNavigationSensorGate {
  if (!args.gps) {
    return { allowed: false, reason: "gps_missing", message: "현재 위치가 없어 길안내를 일시 중지합니다." };
  }
  if (
    typeof args.gps.accuracy_m !== "number" ||
    !Number.isFinite(args.gps.accuracy_m) ||
    args.gps.accuracy_m < 0
  ) {
    return { allowed: false, reason: "gps_accuracy_unknown", message: "GPS 정확도를 확인할 때까지 길안내를 일시 중지합니다." };
  }
  const maxGpsAccuracyM = args.maxGpsAccuracyM ?? WALKSAFE_GUIDANCE_MAX_GPS_ACCURACY_M;
  if (args.gps.accuracy_m > maxGpsAccuracyM) {
    return {
      allowed: false,
      reason: "gps_accuracy_poor",
      message: `GPS 정확도 ${formatDistance(args.gps.accuracy_m)}로 낮아 길안내를 일시 중지합니다.`
    };
  }
  return { allowed: true, reason: "ready", message: null };
}

export function navigationStartLocationError(
  gps: GpsFixV2 | null,
  maxAccuracyM = WALKSAFE_REROUTE_MAX_ACCURACY_M
): string | null {
  if (!gps) {
    return "현재 위치 확인 후 길안내를 시작해 주세요.";
  }
  if (
    typeof gps.accuracy_m !== "number" ||
    !Number.isFinite(gps.accuracy_m) ||
    gps.accuracy_m < 0
  ) {
    return "GPS 정확도 확인 후 길안내를 시작해 주세요.";
  }
  if (gps.accuracy_m > maxAccuracyM) {
    return `GPS 정확도 ${formatDistance(gps.accuracy_m)}로 낮아 길안내를 시작할 수 없습니다.`;
  }
  return null;
}

export function isNavigationRouteRequestCurrent(args: {
  expectedSequence: number;
  currentSequence: number;
  aborted: boolean;
}): boolean {
  return args.expectedSequence === args.currentSequence && !args.aborted;
}

export type NavigationAutoRerouteDecisionReason =
  | "allowed"
  | "not_confirmed_off_route"
  | "missing_destination"
  | "gps_missing"
  | "gps_accuracy_unknown"
  | "gps_accuracy_poor"
  | "gps_stability_pending"
  | "gps_sample_interval"
  | "gps_jump"
  | "request_in_flight"
  | "cooldown"
  | "max_count";

export type NavigationAutoRerouteDecision = {
  allowed: boolean;
  reason: NavigationAutoRerouteDecisionReason;
  message: string;
  remainingCooldownMs: number;
  gpsJumpM: number | null;
};

export function resolveNavigationRouteRequestGate(args: {
  requestInFlight: boolean;
  lastRequestStartedAtMs: number | null;
  nowMs: number;
  cooldownMs?: number;
}): NavigationRouteRequestGateResult {
  if (args.requestInFlight) {
    return {
      allowed: false,
      message: "이미 경로 요청 중입니다. 잠시만 기다려 주세요.",
      remainingCooldownMs: 0
    };
  }

  const cooldownMs = Math.max(0, args.cooldownMs ?? WALKSAFE_ROUTE_REQUEST_COOLDOWN_MS);
  if (args.lastRequestStartedAtMs !== null) {
    const elapsedMs = Math.max(0, args.nowMs - args.lastRequestStartedAtMs);
    const remainingCooldownMs = Math.max(0, cooldownMs - elapsedMs);
    if (remainingCooldownMs > 0) {
      const remainingSeconds = Math.max(1, Math.ceil(remainingCooldownMs / 1000));
      return {
        allowed: false,
        message: `재탐색은 ${remainingSeconds}초 후 다시 시도해 주세요.`,
        remainingCooldownMs
      };
    }
  }

  return {
    allowed: true,
    message: null,
    remainingCooldownMs: 0
  };
}

function routePointFromGps(gps: GpsFixV2): RoutePoint {
  return {
    latitude: gps.latitude,
    longitude: gps.longitude,
    name: "현재 위치"
  };
}

function formatDistance(distanceM: number): string {
  if (distanceM >= 1000) {
    return `${(distanceM / 1000).toFixed(1)}km`;
  }
  return `${Math.round(distanceM)}m`;
}

function formatDuration(durationS: number): string {
  const minutes = Math.max(1, Math.round(durationS / 60));
  return `약 ${minutes}분`;
}

export function resolveAutoRerouteDecision(args: {
  confirmedOffRoute: boolean;
  destination: RoutePoint | null;
  currentGpsSample: GpsSample | null;
  previousGpsSample: GpsSample | null;
  requestInFlight: boolean;
  lastRequestStartedAtMs: number | null;
  nowMs: number;
  autoRerouteCount: number;
  maxAutoReroutes?: number;
  cooldownMs?: number;
  maxAccuracyM?: number;
  maxGpsJumpM?: number;
  minSampleIntervalMs?: number;
  maxSampleIntervalMs?: number;
}): NavigationAutoRerouteDecision {
  if (!args.confirmedOffRoute) {
    return {
      allowed: false,
      reason: "not_confirmed_off_route",
      message: "경로 이탈 확인 중입니다.",
      remainingCooldownMs: 0,
      gpsJumpM: null
    };
  }

  if (!args.destination) {
    return {
      allowed: false,
      reason: "missing_destination",
      message: "목적지 선택 후 재탐색할 수 있습니다.",
      remainingCooldownMs: 0,
      gpsJumpM: null
    };
  }

  const maxAutoReroutes = Math.max(1, Math.floor(args.maxAutoReroutes ?? WALKSAFE_AUTO_REROUTE_MAX_COUNT));
  if (args.autoRerouteCount >= maxAutoReroutes) {
    return {
      allowed: false,
      reason: "max_count",
      message: `자동 재탐색은 ${maxAutoReroutes}회까지만 시도합니다. 음성으로 재탐색을 요청해 주세요.`,
      remainingCooldownMs: 0,
      gpsJumpM: null
    };
  }

  const requestGate = resolveNavigationRouteRequestGate({
    requestInFlight: args.requestInFlight,
    lastRequestStartedAtMs: args.lastRequestStartedAtMs,
    nowMs: args.nowMs,
    cooldownMs: args.cooldownMs ?? WALKSAFE_AUTO_REROUTE_COOLDOWN_MS
  });
  if (!requestGate.allowed) {
    return {
      allowed: false,
      reason: args.requestInFlight ? "request_in_flight" : "cooldown",
      message: requestGate.message ?? "자동 재탐색을 잠시 보류합니다.",
      remainingCooldownMs: requestGate.remainingCooldownMs,
      gpsJumpM: null
    };
  }

  if (!args.currentGpsSample) {
    return {
      allowed: false,
      reason: "gps_missing",
      message: "현재 위치가 확인되면 자동 재탐색합니다.",
      remainingCooldownMs: 0,
      gpsJumpM: null
    };
  }

  const maxAccuracyM = Math.max(1, args.maxAccuracyM ?? WALKSAFE_REROUTE_MAX_ACCURACY_M);
  if (args.currentGpsSample.accuracyM === null) {
    return {
      allowed: false,
      reason: "gps_accuracy_unknown",
      message: "GPS 정확도 확인 중이라 자동 재탐색을 보류합니다.",
      remainingCooldownMs: 0,
      gpsJumpM: null
    };
  }
  if (args.currentGpsSample.accuracyM > maxAccuracyM) {
    return {
      allowed: false,
      reason: "gps_accuracy_poor",
      message: `GPS 정확도 ${formatDistance(args.currentGpsSample.accuracyM)}로 낮아 자동 재탐색을 보류합니다.`,
      remainingCooldownMs: 0,
      gpsJumpM: null
    };
  }

  if (!args.previousGpsSample) {
    return {
      allowed: false,
      reason: "gps_stability_pending",
      message: "GPS 위치 안정성을 한 번 더 확인한 뒤 자동 재탐색합니다.",
      remainingCooldownMs: 0,
      gpsJumpM: null
    };
  }

  const elapsedMs = args.currentGpsSample.observedAtMs - args.previousGpsSample.observedAtMs;
  const minSampleIntervalMs = Math.max(0, args.minSampleIntervalMs ?? WALKSAFE_GPS_SAMPLE_MIN_INTERVAL_MS);
  const maxSampleIntervalMs = Math.max(minSampleIntervalMs + 1, args.maxSampleIntervalMs ?? WALKSAFE_GPS_SAMPLE_MAX_INTERVAL_MS);
  if (elapsedMs < minSampleIntervalMs || elapsedMs > maxSampleIntervalMs) {
    return {
      allowed: false,
      reason: "gps_sample_interval",
      message: "GPS 샘플 간격이 불안정해 자동 재탐색을 보류합니다.",
      remainingCooldownMs: 0,
      gpsJumpM: null
    };
  }

  const gpsJumpM = distanceMeters(args.previousGpsSample.point, args.currentGpsSample.point);
  const maxGpsJumpM = Math.max(1, args.maxGpsJumpM ?? WALKSAFE_REROUTE_MAX_GPS_JUMP_M);
  if (gpsJumpM > maxGpsJumpM) {
    return {
      allowed: false,
      reason: "gps_jump",
      message: `GPS 위치가 갑자기 ${formatDistance(gpsJumpM)} 이동해 자동 재탐색을 보류합니다.`,
      remainingCooldownMs: 0,
      gpsJumpM
    };
  }

  return {
    allowed: true,
    reason: "allowed",
    message: "경로를 벗어나 자동 재탐색을 시작합니다.",
    remainingCooldownMs: 0,
    gpsJumpM
  };
}

export function estimateWalkingSpeedMps(previous: GpsSample | null, next: GpsSample): number | null {
  if (!previous) {
    return null;
  }

  const elapsedMs = next.observedAtMs - previous.observedAtMs;
  if (elapsedMs < WALKSAFE_GPS_SAMPLE_MIN_INTERVAL_MS || elapsedMs > WALKSAFE_GPS_SAMPLE_MAX_INTERVAL_MS) {
    return null;
  }

  const distanceM = distanceMeters(previous.point, next.point);
  if (distanceM <= 0 || distanceM > WALKSAFE_GPS_SAMPLE_MAX_DISTANCE_M) {
    return null;
  }

  const rawSpeed = distanceM / (elapsedMs / 1000);
  if (!Number.isFinite(rawSpeed) || rawSpeed < WALKSAFE_MIN_WALKING_SPEED_MPS) {
    return null;
  }
  return Math.min(rawSpeed, WALKSAFE_MAX_WALKING_SPEED_MPS);
}

function guideDistanceFromStartM(guide: WalkingRouteGuidePoint, measure: RouteMeasure, routeDistanceM: number): number {
  if (typeof guide.distance_from_start_m === "number") {
    return guide.distance_from_start_m;
  }
  if (typeof guide.remaining_distance_m === "number") {
    return Math.max(0, routeDistanceM - guide.remaining_distance_m);
  }
  return projectPointToRoute(guide.point, measure)?.distanceFromStartM ?? 0;
}

function actionFromTurnType(turnType: number | null | undefined): GuideAction | null {
  switch (turnType) {
    case 12:
      return { kind: "left", label: "좌회전" };
    case 13:
      return { kind: "right", label: "우회전" };
    case 14:
      return { kind: "uturn", label: "유턴" };
    case 16:
      return { kind: "direction", label: "왼쪽 8시 방향" };
    case 17:
      return { kind: "direction", label: "왼쪽 10시 방향" };
    case 18:
      return { kind: "direction", label: "오른쪽 2시 방향" };
    case 19:
      return { kind: "direction", label: "오른쪽 4시 방향" };
    case 125:
      return { kind: "stairs", label: "육교" };
    case 126:
      return { kind: "stairs", label: "지하보도" };
    case 127:
      return { kind: "stairs", label: "계단" };
    case 201:
      return { kind: "destination", label: "목적지" };
    case 211:
    case 212:
    case 213:
      return { kind: "crosswalk", label: "횡단보도" };
    default:
      return null;
  }
}

function actionFromInstruction(instruction: string | null | undefined): GuideAction | null {
  const normalized = instruction?.replace(/\s+/g, " ").trim();
  if (!normalized) {
    return null;
  }
  if (normalized.includes("좌회전")) {
    return { kind: "left", label: "좌회전" };
  }
  if (normalized.includes("우회전")) {
    return { kind: "right", label: "우회전" };
  }
  if (normalized.includes("유턴")) {
    return { kind: "uturn", label: "유턴" };
  }
  if (normalized.includes("횡단보도")) {
    return { kind: "crosswalk", label: "횡단보도" };
  }
  if (normalized.includes("도착")) {
    return { kind: "destination", label: "목적지" };
  }
  if (normalized.includes("직진")) {
    return { kind: "straight", label: "직진" };
  }
  return null;
}

function actionFromGuide(guide: WalkingRouteGuidePoint): GuideAction | null {
  return actionFromTurnType(guide.turn_type) ?? actionFromInstruction(guide.instruction);
}

function isActionableGuide(guide: WalkingRouteGuidePoint): boolean {
  return actionFromGuide(guide) !== null;
}

function nowPrompt(action: GuideAction): string {
  if (action.kind === "destination") {
    return "목적지에 도착했습니다.";
  }
  if (action.kind === "crosswalk" || action.kind === "stairs") {
    return `지금 ${action.label}입니다.`;
  }
  if (action.kind === "straight") {
    return "계속 직진하세요.";
  }
  if (action.kind === "direction") {
    return `지금 ${action.label}으로 이동하세요.`;
  }
  return `지금 ${action.label}하세요.`;
}

function soonPrompt(action: GuideAction, steps: number): string {
  if (action.kind === "destination") {
    return `곧 목적지입니다. 약 ${steps}보 앞입니다.`;
  }
  if (action.kind === "crosswalk" || action.kind === "stairs") {
    return `곧 ${action.label}입니다. 약 ${steps}보 앞입니다.`;
  }
  return `곧 ${action.label}입니다. 약 ${steps}보 앞입니다.`;
}

function preparePrompt(action: GuideAction, steps: number): string {
  if (action.kind === "destination") {
    return `10초 뒤 목적지입니다. 약 ${steps}보 앞입니다.`;
  }
  if (action.kind === "crosswalk" || action.kind === "stairs") {
    return `10초 뒤 ${action.label}입니다. 약 ${steps}보 앞입니다.`;
  }
  return `10초 뒤 ${action.label} 준비. 약 ${steps}보 앞입니다.`;
}

function stepPrompt(action: GuideAction, steps: number): string {
  if (action.kind === "destination") {
    return `약 ${steps}보 앞에 목적지입니다.`;
  }
  if (action.kind === "crosswalk" || action.kind === "stairs") {
    return `약 ${steps}보 앞에 ${action.label}입니다.`;
  }
  return `약 ${steps}보 앞에서 ${action.label}.`;
}

function formatSteps(distanceM: number, stepLengthM: number): number {
  return Math.max(1, Math.round(distanceM / stepLengthM));
}

export function resolveNextNavigationInstruction(args: {
  navigationActive: boolean;
  rerouting?: boolean;
  sensorGate: ActiveNavigationSensorGate;
  guide: WalkingRouteGuidePoint | null;
  distanceM: number | null;
  remainingToDestinationM: number | null;
  stepLengthM?: number;
}): NavigationActionResult {
  if (!args.navigationActive) {
    return { ok: false, message: "진행 중인 길안내가 없습니다." };
  }
  if (args.rerouting) {
    return { ok: false, message: "경로를 벗어나 재탐색 중입니다. 새 경로를 확인할 때까지 기다려 주세요." };
  }
  if (!args.sensorGate.allowed) {
    return { ok: false, message: args.sensorGate.message ?? "센서 확인 후 다음 경로를 안내합니다." };
  }
  if (!args.guide || args.distanceM === null) {
    return {
      ok: true,
      message: typeof args.remainingToDestinationM === "number"
        ? `다음 안내는 목적지 도착입니다. 약 ${formatDistance(args.remainingToDestinationM)} 남았습니다.`
        : "다음 안내는 목적지 도착입니다."
    };
  }
  const action = actionFromGuide(args.guide);
  if (!action) {
    return { ok: false, message: "다음 회전 안내를 확인할 수 없습니다." };
  }
  const distanceM = Math.max(0, args.distanceM);
  const stepLengthM = args.stepLengthM ?? WALKSAFE_DEFAULT_STEP_LENGTH_M;
  const immediatePrompt = buildNavigationGuidePrompt({
    guide: args.guide,
    distanceM,
    speedMps: null,
    routeId: "voice-query",
    stepLengthM
  });
  return {
    ok: true,
    message: `다음 안내. ${immediatePrompt?.prompt ?? stepPrompt(action, formatSteps(distanceM, stepLengthM))}`
  };
}

export function buildNavigationGuidePrompt(args: {
  guide: WalkingRouteGuidePoint;
  distanceM: number;
  speedMps: number | null;
  routeId: string;
  stepLengthM?: number;
}): NavigationGuidePrompt | null {
  const distanceM = Math.max(0, args.distanceM);
  const etaS = args.speedMps && args.speedMps > 0 ? distanceM / args.speedMps : null;
  const action = actionFromGuide(args.guide);
  if (!action) {
    return null;
  }
  const stepLengthM = args.stepLengthM ?? WALKSAFE_DEFAULT_STEP_LENGTH_M;
  const steps = formatSteps(distanceM, stepLengthM);
  let stage: GuideAnnouncementStage | null = null;
  let prompt: string | null = null;

  if (distanceM <= WALKSAFE_GUIDE_TURN_RADIUS_M) {
    stage = "now";
    prompt = nowPrompt(action);
  } else if ((etaS !== null && etaS <= SOON_GUIDE_SECONDS) || distanceM <= WALKSAFE_GUIDE_SOON_RADIUS_M) {
    stage = "soon3";
    prompt = soonPrompt(action, steps);
  } else if (etaS !== null && etaS <= EARLY_GUIDE_SECONDS) {
    stage = "prepare10";
    prompt = preparePrompt(action, steps);
  } else if (distanceM <= stepLengthM * 20) {
    stage = "prepare10";
    prompt = stepPrompt(action, steps);
  }

  if (!stage || !prompt) {
    return null;
  }

  return {
    key: `guide:${args.routeId}:${args.guide.index}:${stage}`,
    prompt,
    distanceM,
    etaS,
    steps,
    stage
  };
}

function routeId(route: WalkingRouteResponse): string {
  return route.provider_route_id ?? `${route.provider}:${route.summary.distance_m}:${route.summary.duration_s}`;
}

function candidatePoint(candidate: NavigationDestinationCandidate): RoutePoint {
  return {
    ...candidate.result.point,
    name: candidate.name
  };
}

function isTrackingStatus(status: NavigationGuidanceStatus): boolean {
  return status === "active" || status === "off_route_candidate" || status === "rerouting";
}

function routeProgressText(progress: RouteProgressEvaluation | null): string | null {
  if (progress?.distanceToRouteM === null || progress?.distanceToRouteM === undefined) {
    return null;
  }
  return `경로 이탈 거리 ${formatDistance(progress.distanceToRouteM)}`;
}

function tactileSteeringPrompt(decision: TactileRoutePolicyDecision): string | null {
  if (decision.mode !== "tactile_local") return null;
  if (decision.steering === "left") return "점자블록이 왼쪽에 있습니다. 왼쪽으로 정렬해 점자블록을 따라가세요.";
  if (decision.steering === "right") return "점자블록이 오른쪽에 있습니다. 오른쪽으로 정렬해 점자블록을 따라가세요.";
  return "점자블록이 TMAP 진행 방향에 안정적으로 이어집니다. 점자블록을 따라 직진하세요.";
}

export function describeFutureRouteRoiState(reason: ReturnType<typeof evaluateTactileRouteSupport>["reason"]): string {
  switch (reason) {
    case "supervised_disabled":
      return "점자블록 로컬 조향 비활성 · TMAP 경로 유지";
    case "projection_unavailable":
      return "카메라-경로 projection 근거 없음 · TMAP 경로 유지";
    case "projection_invalid":
      return "카메라-경로 projection 근거 무효 · TMAP 경로 유지";
    case "supported":
      return "감독 현장용 카메라-경로 projection과 점자블록 일치";
    case "missing_motion":
      return "미래 경로 ROI 대기 · 위치와 방향 확인 필요";
    case "route_not_visible":
      return "미래 경로 ROI 대기 · 진행 방향과 경로 불일치";
    case "stationary":
      return "미래 경로 ROI 대기 · 보행 속도 확인 중";
    case "low_projection_confidence":
      return "미래 경로 ROI 대기 · 위치·센서 신뢰도 낮음";
    case "outside_future_roi":
      return "감지된 점자블록이 3~5초 미래 경로 ROI 밖";
    case "no_normal_tactile":
      return "미래 경로 ROI 대기 · 정상 점자블록 감지 없음";
  }
}

export function useNavigationGuidance({
  gps,
  heading,
  v2Detections,
  stepLengthM = WALKSAFE_DEFAULT_STEP_LENGTH_M,
  stepBasedSpeedMps = null,
  motionStability = null,
  cameraRouteProjectionEvidence = null
}: UseNavigationGuidanceOptions) {
  const [status, setStatus] = useState<NavigationGuidanceStatus>("idle");
  const [route, setRoute] = useState<WalkingRouteResponse | null>(null);
  const [destinationName, setDestinationName] = useState(WALKSAFE_DEFAULT_DESTINATION?.name ?? "");
  const [selectedDestination, setSelectedDestination] = useState<RoutePoint | null>(() => WALKSAFE_DEFAULT_DESTINATION);
  const [destinationCandidates, setDestinationCandidates] = useState<NavigationDestinationCandidate[]>([]);
  const [message, setMessage] = useState("길안내 대기");
  const [routeStartSpeechPrompt, setRouteStartSpeechPrompt] = useState<string | null>(null);
  const [routeStartSpeechKey, setRouteStartSpeechKey] = useState<string | null>(null);
  const [autoRerouteMessage, setAutoRerouteMessage] = useState<string | null>(null);
  const [walkingSpeedMps, setWalkingSpeedMps] = useState<number | null>(null);
  const lastGpsSampleRef = useRef<GpsSample | null>(null);
  const previousGpsSampleRef = useRef<GpsSample | null>(null);
  // These refs coordinate async requests without making transient network state drive extra effects.
  const lastRouteRequestStartedAtMsRef = useRef<number | null>(null);
  const [requestCoordinator] = useState(() => new NavigationRequestCoordinator());
  const lastDestinationSearchQueryRef = useRef<string | null>(null);
  const lastDestinationSearchResponseRef = useRef<Awaited<ReturnType<typeof searchDestinations>> | null>(null);
  const [destinationCandidateLimit, setDestinationCandidateLimit] = useState(3);
  const [hiddenDestinationCandidateCount, setHiddenDestinationCandidateCount] = useState(0);
  const [destinationSearchActive, setDestinationSearchActive] = useState(false);
  const autoRerouteStateRef = useRef<AutoRerouteState>({ count: 0 });
  const [routeProgressHistory, setRouteProgressHistory] = useState<RouteProgressHistory>({ routeId: null, samples: [] });
  const [routeArrivalHistory, setRouteArrivalHistory] = useState<RouteArrivalHistory>({ routeId: null, samples: [] });
  const [routeProjectionAnchor, setRouteProjectionAnchor] = useState<RouteProjectionAnchor>({
    routeId: null,
    distanceFromStartM: null
  });
  const [tactileObservationTracker, setTactileObservationTracker] = useState<TactileObservationTracker | null>(null);
  const [tactilePolicyNowMs, setTactilePolicyNowMs] = useState(() => Date.now());

  const hasNavigationDestination = selectedDestination !== null;

  const cancelPendingRouteRequest = useCallback(() => {
    requestCoordinator.cancelRoute();
  }, [requestCoordinator]);

  const cancelPendingDestinationSearch = useCallback(() => {
    requestCoordinator.cancelDestinationSearch();
  }, [requestCoordinator]);

  const isCurrentRouteRequest = useCallback(
    (token: NavigationRequestToken) => requestCoordinator.isCurrent(token),
    [requestCoordinator]
  );

  const applyDestinationPolicy = useCallback((policy: NavigationDestinationPolicy) => {
    setDestinationCandidates(policy.candidates);
    setHiddenDestinationCandidateCount(policy.hiddenCandidateCount);
  }, []);

  const setVoiceDestination = useCallback(async (nextDestinationName: string): Promise<NavigationActionResult> => {
    const query = nextDestinationName.replace(/\s+/g, " ").trim();
    const preserveExistingNavigation = route !== null && isTrackingStatus(status);
    if (!preserveExistingNavigation) cancelPendingRouteRequest();
    const requestToken = requestCoordinator.beginDestination();
    const abortController = requestToken.controller;
    lastDestinationSearchQueryRef.current = query;
    lastDestinationSearchResponseRef.current = null;
    setDestinationCandidateLimit(3);
    setHiddenDestinationCandidateCount(0);
    setDestinationCandidates([]);

    if (!query) {
      const nextMessage = "목적지를 다시 말씀해 주세요.";
      requestCoordinator.finish(requestToken);
      setDestinationSearchActive(false);
      if (!preserveExistingNavigation) setStatus("missing_destination");
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    }

    setDestinationSearchActive(true);
    if (!preserveExistingNavigation) setStatus("searching_destination");
    setMessage(`${query} 목적지 검색 중`);

    try {
      if (WALKSAFE_DESTINATION_SEARCH_DEBOUNCE_MS > 0) {
        await new Promise<void>((resolve, reject) => {
          let timer: number | null = null;
          const abort = () => {
            if (timer !== null) {
              window.clearTimeout(timer);
            }
            abortController.signal.removeEventListener("abort", abort);
            reject(new NavigationApiError("목적지 검색을 취소했습니다.", 0, "aborted"));
          };
          timer = window.setTimeout(() => {
            abortController.signal.removeEventListener("abort", abort);
            resolve();
          }, WALKSAFE_DESTINATION_SEARCH_DEBOUNCE_MS);
          if (abortController.signal.aborted) {
            abort();
            return;
          }
          abortController.signal.addEventListener("abort", abort, { once: true });
        });
      }

      const response = await searchDestinations(query, {
        limit: 10,
        origin: gps ? routePointFromGps(gps) : null,
        signal: abortController.signal
      });
      if (!requestCoordinator.isCurrent(requestToken)) {
        return { ok: false, message: "이전 목적지 검색 결과를 무시했습니다." };
      }
      lastDestinationSearchResponseRef.current = response;
      const policy = resolveNavigationDestinationPolicy(response, {
        candidateLimit: 3,
        maxCandidateDistanceM: WALKSAFE_DESTINATION_MAX_DISTANCE_M
      });
      applyDestinationPolicy(policy);

      if (policy.status === "single_candidate" && policy.selectedCandidate) {
        const candidate = policy.candidates[0];
        const nextDestination = candidatePoint(candidate);
        cancelPendingRouteRequest();
        setDestinationCandidates([]);
        setHiddenDestinationCandidateCount(0);
        setSelectedDestination(nextDestination);
        setDestinationName(candidate.name);
        setRoute(null);
        setStatus("idle");
        setRouteStartSpeechPrompt(null);
        setRouteStartSpeechKey(null);
        const nextMessage = `${candidate.label} 선택됨 · “길안내 시작”이라고 말하면 시작합니다.`;
        setMessage(nextMessage);
        return { ok: true, message: `${policy.speechMessage} 길안내 시작이라고 말하면 시작합니다.` };
      }

      if (policy.status === "multiple_candidates" || policy.status === "distance_unknown") {
        if (!preserveExistingNavigation) setStatus("destination_candidates");
        setMessage(policy.message);
        return { ok: false, message: policy.speechMessage };
      }

      if (!preserveExistingNavigation) setStatus("missing_destination");
      setMessage(policy.message);
      return { ok: false, message: policy.speechMessage };
    } catch (error) {
      if (
        !requestCoordinator.isCurrent(requestToken) ||
        (error instanceof NavigationApiError && error.code === "aborted")
      ) {
        return { ok: false, message: "목적지 검색을 취소했습니다." };
      }
      const nextMessage = error instanceof Error ? error.message : "목적지 검색에 실패했습니다.";
      if (!preserveExistingNavigation) setStatus("error");
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    } finally {
      if (requestCoordinator.finish(requestToken)) {
        setDestinationSearchActive(false);
      }
    }
  }, [applyDestinationPolicy, cancelPendingRouteRequest, gps, requestCoordinator, route, status]);

  const selectDestinationCandidate = useCallback((candidateId: string): NavigationActionResult => {
    const candidate = destinationCandidates.find((item) => item.id === candidateId);
    if (!candidate) {
      const nextMessage = "선택할 목적지 후보를 찾지 못했습니다.";
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    }

    const nextDestination = candidatePoint(candidate);
    cancelPendingRouteRequest();
    setSelectedDestination(nextDestination);
    setDestinationName(candidate.name);
    setDestinationCandidates([]);
    setHiddenDestinationCandidateCount(0);
    setDestinationSearchActive(false);
    setRoute(null);
    setStatus("idle");
    const nextMessage = `${candidate.label} 선택됨 · 길안내 시작 가능`;
    setMessage(nextMessage);
    return { ok: true, message: `${candidate.speechLabel} 선택됨. 길안내 시작이라고 말하면 시작합니다.` };
  }, [cancelPendingRouteRequest, destinationCandidates]);

  const selectDestinationCandidateByIndex = useCallback((candidateIndex: number): NavigationActionResult => {
    if (destinationCandidates.length === 0) {
      const nextMessage = "선택할 목적지 후보가 없습니다. 목적지를 먼저 검색해 주세요.";
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    }

    if (!Number.isInteger(candidateIndex) || candidateIndex < 1 || candidateIndex > destinationCandidates.length) {
      const nextMessage = `목적지 후보는 1번부터 ${destinationCandidates.length}번까지 있습니다. 다시 선택해 주세요.`;
      setStatus("destination_candidates");
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    }

    return selectDestinationCandidate(destinationCandidates[candidateIndex - 1].id);
  }, [destinationCandidates, selectDestinationCandidate]);

  const showMoreDestinationCandidates = useCallback((): NavigationActionResult => {
    const response = lastDestinationSearchResponseRef.current;
    if (!response || hiddenDestinationCandidateCount <= 0) {
      const nextMessage = "더 보여줄 목적지 후보가 없습니다.";
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    }

    const nextLimit = destinationCandidateLimit + 3;
    const policy = resolveNavigationDestinationPolicy(response, {
      candidateLimit: nextLimit,
      maxCandidateDistanceM: WALKSAFE_DESTINATION_MAX_DISTANCE_M
    });
    setDestinationCandidateLimit(nextLimit);
    applyDestinationPolicy(policy);
    if (!isTrackingStatus(status)) {
      setStatus(policy.status === "multiple_candidates" || policy.status === "distance_unknown" ? "destination_candidates" : status);
    }
    setMessage(policy.message);
    return { ok: true, message: policy.speechMessage };
  }, [applyDestinationPolicy, destinationCandidateLimit, hiddenDestinationCandidateCount, status]);

  const cancelDestinationSearch = useCallback((): NavigationActionResult => {
    const hadSearch = Boolean(
      destinationSearchActive ||
      destinationCandidates.length > 0 ||
      requestCoordinator.destinationInFlight
    );
    cancelPendingDestinationSearch();
    setDestinationSearchActive(false);
    lastDestinationSearchQueryRef.current = null;
    lastDestinationSearchResponseRef.current = null;
    setDestinationCandidates([]);
    setHiddenDestinationCandidateCount(0);
    setDestinationCandidateLimit(3);
    if (!isTrackingStatus(status)) setStatus("idle");
    const nextMessage = hadSearch
      ? route && isTrackingStatus(status)
        ? "새 목적지 검색을 취소했습니다. 기존 길안내를 유지합니다."
        : "목적지 검색을 취소했습니다."
      : "취소할 목적지 검색이 없습니다.";
    setMessage(nextMessage);
    return { ok: hadSearch, message: nextMessage };
  }, [cancelPendingDestinationSearch, destinationCandidates.length, destinationSearchActive, requestCoordinator, route, status]);

  const cancelDestinationAndNavigation = useCallback((): NavigationActionResult => {
    const hadDestination = Boolean(
      route ||
      selectedDestination ||
      destinationCandidates.length > 0 ||
      destinationName ||
      requestCoordinator.destinationInFlight
    );
    requestCoordinator.cancelDestinationAndNavigation();
    setDestinationSearchActive(false);
    lastDestinationSearchQueryRef.current = null;
    lastDestinationSearchResponseRef.current = null;
    setDestinationCandidates([]);
    setHiddenDestinationCandidateCount(0);
    setDestinationCandidateLimit(3);
    setRoute(null);
    setSelectedDestination(null);
    setDestinationName("");
    setStatus("idle");
    setRouteStartSpeechPrompt(null);
    setRouteStartSpeechKey(null);
    const nextMessage = hadDestination
      ? "목적지와 진행 중인 경로를 취소했습니다."
      : "취소할 목적지가 없습니다.";
    setMessage(nextMessage);
    return { ok: hadDestination, message: nextMessage };
  }, [destinationCandidates.length, destinationName, requestCoordinator, route, selectedDestination]);

  const retryDestinationSearch = useCallback(async (): Promise<NavigationActionResult> => {
    const query = lastDestinationSearchQueryRef.current ?? destinationName;
    if (!query) {
      const nextMessage = "다시 검색할 목적지가 없습니다.";
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    }
    return setVoiceDestination(query);
  }, [destinationName, setVoiceDestination]);

  const startNavigation = useCallback(async (): Promise<NavigationActionResult> => {
    const locationError = navigationStartLocationError(gps);
    if (locationError || !gps) {
      const nextMessage = locationError ?? "현재 위치 확인 후 길안내를 시작해 주세요.";
      setStatus("missing_location");
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    }

    if (!selectedDestination) {
      const nextMessage = destinationCandidates.length > 0 ? "목적지 후보를 먼저 선택해 주세요." : "목적지를 먼저 검색해 주세요.";
      setStatus(destinationCandidates.length > 0 ? "destination_candidates" : "missing_destination");
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    }

    const nowMs = Date.now();
    const beginsNewNavigationSession = !route || !isTrackingStatus(status);
    // Explicit starts and automatic reroutes use the same in-flight/cooldown contract.
    const routeRequestGate = resolveNavigationRouteRequestGate({
      requestInFlight: requestCoordinator.routeInFlight,
      lastRequestStartedAtMs: lastRouteRequestStartedAtMsRef.current,
      nowMs
    });
    if (!routeRequestGate.allowed) {
      const nextMessage = routeRequestGate.message ?? "잠시 후 다시 시도해 주세요.";
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    }

    const requestToken = requestCoordinator.beginRoute();
    lastRouteRequestStartedAtMsRef.current = nowMs;
    setStatus("loading");
    setMessage("보행 경로 제공자 응답 대기 중");
    setRouteStartSpeechPrompt(null);
    setRouteStartSpeechKey(null);

    try {
      const nextRoute = await fetchWalkingRoute(
        {
          origin: routePointFromGps(gps),
          destination: selectedDestination,
          priority: "STAIR_AVOID"
        },
        { signal: requestToken.controller.signal }
      );
      if (!isCurrentRouteRequest(requestToken)) {
        return { ok: false, message: "취소된 길안내 응답을 무시했습니다." };
      }
      const routeSummary = `${formatDistance(nextRoute.summary.distance_m)}, ${formatDuration(nextRoute.summary.duration_s)}`;
      const nextMessage = `길안내 시작 · ${WALKING_ROUTE_PROVIDER_LABEL} · ${routeSummary}`;
      if (beginsNewNavigationSession) {
        autoRerouteStateRef.current = { count: 0 };
      }
      setAutoRerouteMessage(null);
      setRouteProgressHistory({ routeId: null, samples: [] });
      setRouteArrivalHistory({ routeId: null, samples: [] });
      setRoute(nextRoute);
      setStatus("active");
      setMessage(nextMessage);
      setRouteStartSpeechPrompt(
        `길 안내를 시작합니다. ${WALKING_ROUTE_PROVIDER_LABEL} 경로 전체 ${routeSummary}. 기본 안내는 TMAP 경로를 유지합니다.`
      );
      setRouteStartSpeechKey(`route-start:${nextRoute.provider_route_id ?? Date.now()}`);
      return { ok: true, message: nextMessage };
    } catch (error) {
      if (!isCurrentRouteRequest(requestToken)) {
        return { ok: false, message: "길안내 요청을 취소했습니다." };
      }
      const nextMessage = error instanceof Error ? error.message : "도보 길안내 요청에 실패했습니다.";
      setRoute(null);
      setStatus("error");
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    } finally {
      requestCoordinator.finish(requestToken);
    }
  }, [destinationCandidates.length, gps, isCurrentRouteRequest, requestCoordinator, route, selectedDestination, status]);

  const stopNavigation = useCallback((): NavigationActionResult => {
    const hadActiveNavigation = Boolean(route || isTrackingStatus(status) || requestCoordinator.routeInFlight);
    cancelPendingRouteRequest();
    setStatus("idle");
    setRoute(null);
    setWalkingSpeedMps(null);
    lastGpsSampleRef.current = null;
    previousGpsSampleRef.current = null;
    autoRerouteStateRef.current = { count: 0 };
    setRouteProgressHistory({ routeId: null, samples: [] });
    setRouteArrivalHistory({ routeId: null, samples: [] });
    const nextMessage = hadActiveNavigation ? "길안내를 중지했습니다." : "진행 중인 길안내가 없습니다.";
    setMessage(nextMessage);
    setAutoRerouteMessage(null);
    setRouteStartSpeechPrompt(null);
    setRouteStartSpeechKey(null);
    return { ok: hadActiveNavigation, message: nextMessage };
  }, [cancelPendingRouteRequest, requestCoordinator, route, status]);

  useEffect(() => () => cancelPendingRouteRequest(), [cancelPendingRouteRequest]);

  useEffect(() => {
    const cancelHiddenRequests = () => {
      if (document.visibilityState !== "visible") {
        cancelPendingDestinationSearch();
        setDestinationSearchActive(false);
        cancelPendingRouteRequest();
      }
    };
    document.addEventListener("visibilitychange", cancelHiddenRequests);
    return () => {
      document.removeEventListener("visibilitychange", cancelHiddenRequests);
      cancelPendingDestinationSearch();
    };
  }, [cancelPendingDestinationSearch, cancelPendingRouteRequest]);

  useEffect(() => {
    if (!routeStartSpeechPrompt) {
      return;
    }
    const timer = window.setTimeout(() => {
      setRouteStartSpeechPrompt(null);
      setRouteStartSpeechKey(null);
    }, ROUTE_START_PROMPT_MS);
    return () => window.clearTimeout(timer);
  }, [routeStartSpeechPrompt]);

  useEffect(() => {
    if (!gps) {
      return;
    }

    const previousSample = lastGpsSampleRef.current;
    const sample = { point: routePointFromGps(gps), observedAtMs: Date.now(), accuracyM: gps.accuracy_m ?? null };
    previousGpsSampleRef.current = previousSample;
    const nextSpeed = estimateWalkingSpeedMps(previousSample, sample);
    lastGpsSampleRef.current = sample;
    if (nextSpeed === null) {
      return;
    }
    setWalkingSpeedMps((previousSpeed) => (previousSpeed === null ? nextSpeed : previousSpeed * 0.7 + nextSpeed * 0.3));
  }, [gps]);

  const routeProgress = useMemo(() => {
    if (!route || !gps) {
      return null;
    }
    const currentRouteId = routeId(route);
    const previousDistanceFromStartM =
      routeProjectionAnchor.routeId === currentRouteId
        ? routeProjectionAnchor.distanceFromStartM
        : null;
    return evaluateOffRouteStatus({
      route,
      currentPoint: routePointFromGps(gps),
      accuracyM: gps.accuracy_m ?? null,
      offRouteThresholdM: WALKSAFE_OFF_ROUTE_THRESHOLD_M,
      previousDistanceFromStartM
    });
  }, [gps, route, routeProjectionAnchor.distanceFromStartM, routeProjectionAnchor.routeId]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (!route || routeProgress?.status !== "on_route" || routeProgress.distanceFromStartM === null) {
        if (!route) {
          setRouteProjectionAnchor((current) =>
            current.routeId === null && current.distanceFromStartM === null
              ? current
              : { routeId: null, distanceFromStartM: null }
          );
        }
        return;
      }
      const currentRouteId = routeId(route);
      setRouteProjectionAnchor((current) =>
        current.routeId === currentRouteId && current.distanceFromStartM === routeProgress.distanceFromStartM
          ? current
          : { routeId: currentRouteId, distanceFromStartM: routeProgress.distanceFromStartM }
      );
    }, 0);
    return () => window.clearTimeout(timer);
  }, [route, routeProgress]);

  const routeBearingDeg = useMemo(
    () => resolveRouteBearing(route, gps ? routePointFromGps(gps) : null, routeProgress?.distanceFromStartM),
    [gps, route, routeProgress?.distanceFromStartM]
  );
  const activeSensorGate = useMemo(
    () =>
      resolveActiveNavigationSensorGate({
        gps,
      }),
    [gps]
  );
  const futureMotion = useMemo<FutureMotionProjection>(
    () =>
      projectFutureMotion({
        gps,
        headingDeg: heading,
        routeBearingDeg,
        gpsSpeedMps: walkingSpeedMps ?? gps?.speed_mps ?? null,
        stepSpeedMps: stepBasedSpeedMps,
        motionStability,
        horizonS: 4
      }),
    [gps, heading, motionStability, routeBearingDeg, stepBasedSpeedMps, walkingSpeedMps]
  );
  const tactileRouteSupport = useMemo(
    () =>
      evaluateTactileRouteSupport(v2Detections, futureMotion, {
        supervisedFieldEnabled: WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING_ENABLED,
        expectedRouteId: route ? routeId(route) : null,
        projectionEvidence: cameraRouteProjectionEvidence,
        nowMs: tactilePolicyNowMs
      }),
    [cameraRouteProjectionEvidence, futureMotion, route, tactilePolicyNowMs, v2Detections]
  );
  const tactileDetection = useMemo(
    () =>
      selectTactileRouteDetection(v2Detections, {
        centerX: tactileRouteSupport.corridorCenterX,
        halfWidth: tactileRouteSupport.corridorHalfWidth
      }, tactileRouteSupport.supported),
    [
      tactileRouteSupport.corridorCenterX,
      tactileRouteSupport.corridorHalfWidth,
      tactileRouteSupport.supported,
      v2Detections
    ]
  );

  useEffect(() => {
    const capturedAtMs = tactileDetection ? Date.parse(tactileDetection.captured_at) : Number.NaN;
    const timer = window.setTimeout(() => {
      setTactileObservationTracker((previous) =>
        advanceTactileObservationTracker(
          previous,
          tactileDetection && Number.isFinite(capturedAtMs)
            ? { className: tactileDetection.class_name, capturedAtMs, bbox: tactileDetection.bbox }
            : null,
          Date.now()
        )
      );
    }, 0);
    return () => window.clearTimeout(timer);
  }, [tactileDetection]);

  const routeArrival = useMemo(() => {
    if (!route || !gps) {
      return null;
    }
    return evaluateRouteArrivalStatus({
      route,
      currentPoint: routePointFromGps(gps),
      destination: selectedDestination,
      progressRatio: routeProgress?.progressRatio ?? null,
      accuracyM: gps.accuracy_m ?? null,
      arrivalRadiusM: WALKSAFE_ARRIVAL_RADIUS_M,
      maxAccuracyM: WALKSAFE_ARRIVAL_MAX_ACCURACY_M
    });
  }, [gps, route, routeProgress?.progressRatio, selectedDestination]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (!route || !routeProgress) {
        setRouteProgressHistory({ routeId: null, samples: [] });
        return;
      }

      const currentRouteId = routeId(route);
      setRouteProgressHistory((previousHistory) => {
        const previousSamples = previousHistory.routeId === currentRouteId ? previousHistory.samples : [];
        return {
          routeId: currentRouteId,
          samples: [...previousSamples, routeProgress].slice(-5)
        };
      });
    }, 0);
    return () => window.clearTimeout(timer);
  }, [route, routeProgress]);

  useEffect(() => {
    const timer = window.setTimeout(() => {
      if (!route || !routeArrival) {
        setRouteArrivalHistory({ routeId: null, samples: [] });
        return;
      }

      const currentRouteId = routeId(route);
      setRouteArrivalHistory((previousHistory) => {
        const previousSamples = previousHistory.routeId === currentRouteId ? previousHistory.samples : [];
        return {
          routeId: currentRouteId,
          samples: [...previousSamples, routeArrival].slice(-4)
        };
      });
    }, 0);
    return () => window.clearTimeout(timer);
  }, [route, routeArrival]);

  const stabilizedRouteArrival = useMemo(
    () => stabilizeRouteArrivalStatus(routeArrivalHistory.samples),
    [routeArrivalHistory.samples]
  );

  useEffect(() => {
    if (!route || !routeArrival?.reached || !stabilizedRouteArrival.reached || !isTrackingStatus(status)) {
      return;
    }

    // Invalidate an in-flight reroute before a route state change can cancel this timer.
    cancelPendingRouteRequest();
    const timer = window.setTimeout(() => {
      autoRerouteStateRef.current = { count: 0 };
      setAutoRerouteMessage(null);
      setRoute(null);
      setRouteProgressHistory({ routeId: null, samples: [] });
      setRouteArrivalHistory({ routeId: null, samples: [] });
      setWalkingSpeedMps(null);
      setStatus("idle");
      const nextMessage = "목적지에 도착했습니다.";
      setMessage(nextMessage);
      setRouteStartSpeechPrompt(nextMessage);
      setRouteStartSpeechKey(`route-arrival:${routeId(route)}:${Date.now()}`);
    }, 0);
    return () => window.clearTimeout(timer);
  }, [cancelPendingRouteRequest, route, routeArrival?.reached, stabilizedRouteArrival.reached, status]);

  const stabilizedRouteProgress = useMemo(
    () => stabilizeOffRouteStatus(routeProgressHistory.samples),
    [routeProgressHistory.samples]
  );

  const effectiveStatus = useMemo<NavigationGuidanceStatus>(() => {
    if (
      status === "active" &&
      routeProgress?.status === "off_route" &&
      stabilizedRouteProgress.status === "off_route" &&
      stabilizedRouteProgress.confirmed
    ) {
      return "rerouting";
    }
    if (
      status === "active" &&
      (stabilizedRouteProgress.status === "off_route_candidate" ||
        routeProgress?.status === "off_route_candidate" ||
        routeProgress?.status === "off_route")
    ) {
      return "off_route_candidate";
    }
    return status;
  }, [routeProgress?.status, stabilizedRouteProgress.confirmed, stabilizedRouteProgress.status, status]);

  useEffect(() => {
    if (!tactileObservationTracker || !isTrackingStatus(effectiveStatus)) return;
    const timer = window.setInterval(() => setTactilePolicyNowMs(Date.now()), 250);
    return () => window.clearInterval(timer);
  }, [effectiveStatus, tactileObservationTracker]);

  const tactileRoutePolicy = useMemo(() => {
    const centerX = tactileDetection ? tactileDetection.bbox.x + tactileDetection.bbox.width / 2 : 0.5;
    const centerY = tactileDetection ? tactileDetection.bbox.y + tactileDetection.bbox.height / 2 : 0;
    const area = tactileDetection
      ? Math.max(0, tactileDetection.bbox.width) * Math.max(0, tactileDetection.bbox.height)
      : 0;
    const trackerMatches = Boolean(
      tactileDetection &&
        tactileObservationTracker &&
        tactileObservationTracker.className === tactileDetection.class_name &&
        tactileObservationTracker.lastCapturedAtMs === Date.parse(tactileDetection.captured_at) &&
        tactileObservationMatchesTracker(tactileObservationTracker, tactileDetection.bbox)
    );
    return evaluateTactileRoutePolicy({
      navigation_active: effectiveStatus === "active" && Boolean(route),
      tmap_on_route: routeProgress?.status === "on_route",
      gps_accuracy_m: gps?.accuracy_m ?? null,
      tactile:
        tactileDetection && tactileObservationTracker && trackerMatches
          ? {
              class_name: tactileDetection.class_name,
              confidence: tactileDetection.confidence,
              stable_frames: tactileObservationTracker.stableFrames,
              stable_ms: tactileObservationTracker.lastCapturedAtMs - tactileObservationTracker.firstCapturedAtMs,
              age_ms: Math.max(
                0,
                Math.max(tactilePolicyNowMs, tactileObservationTracker.evaluatedAtMs) -
                  tactileObservationTracker.lastCapturedAtMs
              ),
              route_heading_delta_deg: futureMotion.headingRouteDeltaDeg ?? Number.POSITIVE_INFINITY,
              overlaps_tmap_corridor:
                centerY >= 0.46 &&
                area >= 0.01 &&
                Math.abs(centerX - tactileRouteSupport.corridorCenterX) <= tactileRouteSupport.corridorHalfWidth,
              center_x_normalized: centerX
            }
          : null
    });
  }, [
    effectiveStatus,
    futureMotion.headingRouteDeltaDeg,
    gps?.accuracy_m,
    route,
    routeProgress?.status,
    tactileDetection,
    tactileObservationTracker,
    tactilePolicyNowMs,
    tactileRouteSupport.corridorCenterX,
    tactileRouteSupport.corridorHalfWidth
  ]);

  // Reroute only after consecutive off-route evidence and GPS accuracy/jump checks pass.
  useEffect(() => {
    if (effectiveStatus !== "rerouting" || !route) {
      return;
    }

    const currentRouteId = routeId(route);
    const autoState = autoRerouteStateRef.current;

    const nowMs = Date.now();
    const decision = resolveAutoRerouteDecision({
      confirmedOffRoute: stabilizedRouteProgress.confirmed,
      destination: selectedDestination,
      currentGpsSample: lastGpsSampleRef.current,
      previousGpsSample: previousGpsSampleRef.current,
      requestInFlight: requestCoordinator.routeInFlight,
      lastRequestStartedAtMs: lastRouteRequestStartedAtMsRef.current,
      nowMs,
      autoRerouteCount: autoState.count,
      maxAutoReroutes: WALKSAFE_AUTO_REROUTE_MAX_COUNT,
      cooldownMs: WALKSAFE_AUTO_REROUTE_COOLDOWN_MS,
      maxAccuracyM: WALKSAFE_REROUTE_MAX_ACCURACY_M,
      maxGpsJumpM: WALKSAFE_REROUTE_MAX_GPS_JUMP_M
    });
    setAutoRerouteMessage(decision.message);

    if (!decision.allowed || !gps || !selectedDestination) {
      return;
    }

    const requestToken = requestCoordinator.beginRoute();
    lastRouteRequestStartedAtMsRef.current = nowMs;
    autoRerouteStateRef.current = {
      count: autoState.count + 1
    };
    setStatus("rerouting");

    void (async () => {
      try {
        const nextRoute = await fetchWalkingRoute(
          {
            origin: routePointFromGps(gps),
            destination: selectedDestination,
            priority: "STAIR_AVOID"
          },
          { signal: requestToken.controller.signal }
        );
        if (!isCurrentRouteRequest(requestToken)) {
          return;
        }
        const routeSummary = `${formatDistance(nextRoute.summary.distance_m)}, ${formatDuration(nextRoute.summary.duration_s)}`;
        setRoute(nextRoute);
        setRouteProgressHistory({ routeId: null, samples: [] });
        setRouteArrivalHistory({ routeId: null, samples: [] });
        setStatus("active");
        setAutoRerouteMessage(null);
        setMessage(`자동 재탐색 완료 · ${WALKING_ROUTE_PROVIDER_LABEL} · ${routeSummary} · 세션 ${autoRerouteStateRef.current.count}/${WALKSAFE_AUTO_REROUTE_MAX_COUNT}회`);
        setRouteStartSpeechPrompt(`경로를 다시 탐색했습니다. ${WALKING_ROUTE_PROVIDER_LABEL} 경로 전체 ${routeSummary}.`);
        setRouteStartSpeechKey(`route-reroute:${routeId(nextRoute)}:${Date.now()}`);
      } catch (error) {
        if (!isCurrentRouteRequest(requestToken)) {
          return;
        }
        const nextMessage = error instanceof Error ? error.message : "자동 재탐색에 실패했습니다.";
        setStatus("active");
        setAutoRerouteMessage(`자동 재탐색 실패 · ${nextMessage}`);
        setMessage(`자동 재탐색 실패 · 기존 경로를 유지합니다. ${nextMessage}`);
        setRouteStartSpeechPrompt(`자동 재탐색에 실패했습니다. 기존 경로를 유지합니다.`);
        setRouteStartSpeechKey(`route-reroute-failed:${currentRouteId}:${Date.now()}`);
      } finally {
        requestCoordinator.finish(requestToken);
      }
    })();
  }, [effectiveStatus, gps, isCurrentRouteRequest, requestCoordinator, route, selectedDestination, stabilizedRouteProgress.confirmed]);

  const effectiveMessage = useMemo(() => {
    if (effectiveStatus === "rerouting") {
      return autoRerouteMessage ?? "경로를 벗어났습니다. 자동 재탐색을 준비합니다.";
    }
    if (effectiveStatus === "off_route_candidate") {
      const countText =
        stabilizedRouteProgress.consecutiveOffRouteCount > 0
          ? ` · 확인 ${stabilizedRouteProgress.consecutiveOffRouteCount}/${stabilizedRouteProgress.requiredConsecutiveOffRouteCount}`
          : "";
      return `경로 이탈 가능성 확인 중 · ${routeProgressText(routeProgress) ?? "GPS 정확도 확인 중"}${countText}`;
    }
    return message;
  }, [autoRerouteMessage, effectiveStatus, message, routeProgress, stabilizedRouteProgress.consecutiveOffRouteCount, stabilizedRouteProgress.requiredConsecutiveOffRouteCount]);

  const rerouteSpeechPrompt = effectiveStatus === "rerouting" ? (autoRerouteMessage ?? "경로를 벗어났습니다. 자동 재탐색을 준비합니다.") : null;
  const rerouteSpeechKey = route && effectiveStatus === "rerouting" ? `reroute:${routeId(route)}:${autoRerouteMessage ?? "pending"}` : null;

  const nextGuideState = useMemo(() => {
    if ((effectiveStatus !== "active" && effectiveStatus !== "off_route_candidate") || !route || !gps || route.guide_points.length === 0) {
      return null;
    }

    const measure = buildRouteMeasure(route);
    const routeDistanceM = route.summary.distance_m || measure.totalDistanceM;
    const projectedDistanceFromStartM =
      routeProgress?.distanceFromStartM ??
      projectPointToRoute(routePointFromGps(gps), measure, {
        previousDistanceFromStartM:
          routeProjectionAnchor.routeId === routeId(route)
            ? routeProjectionAnchor.distanceFromStartM
            : null
      })?.distanceFromStartM ??
      0;
    const currentDistanceFromStartM =
      measure.totalDistanceM > 0 && routeDistanceM > 0
        ? (projectedDistanceFromStartM * routeDistanceM) / measure.totalDistanceM
        : projectedDistanceFromStartM;
    const nextGuide = route.guide_points
      .filter(isActionableGuide)
      .map((guide) => ({
        guide,
        distanceM: guideDistanceFromStartM(guide, measure, routeDistanceM) - currentDistanceFromStartM
      }))
      .filter(({ distanceM }) => distanceM >= -WALKSAFE_GUIDE_TURN_RADIUS_M)
      .sort((left, right) => left.distanceM - right.distanceM)[0];

    return nextGuide ?? null;
  }, [effectiveStatus, gps, route, routeProgress?.distanceFromStartM, routeProjectionAnchor.distanceFromStartM, routeProjectionAnchor.routeId]);

  const guidePrompt = useMemo(() => {
    if (!activeSensorGate.allowed || !route || !nextGuideState) {
      return null;
    }
    return buildNavigationGuidePrompt({
      guide: nextGuideState.guide,
      distanceM: nextGuideState.distanceM,
      speedMps: futureMotion.speedMps > 0 ? futureMotion.speedMps : null,
      routeId: routeId(route),
      stepLengthM
    });
  }, [activeSensorGate.allowed, futureMotion.speedMps, nextGuideState, route, stepLengthM]);

  const getNextNavigationInstruction = useCallback((): NavigationActionResult => {
    return resolveNextNavigationInstruction({
      navigationActive: Boolean(route && isTrackingStatus(effectiveStatus)),
      rerouting: effectiveStatus === "rerouting",
      sensorGate: activeSensorGate,
      guide: nextGuideState?.guide ?? null,
      distanceM: nextGuideState?.distanceM ?? null,
      remainingToDestinationM: routeArrival?.distanceToDestinationM ?? null,
      stepLengthM
    });
  }, [activeSensorGate, effectiveStatus, nextGuideState, route, routeArrival?.distanceToDestinationM, stepLengthM]);

  // Risk and interaction speech are still enforced above this navigation-only ordering.
  const tactilePrompt = tactileSteeringPrompt(tactileRoutePolicy);
  const navigationSpeech = resolveNavigationSpeechCandidate({
    reroute:
      rerouteSpeechPrompt && rerouteSpeechKey ? { prompt: rerouteSpeechPrompt, key: rerouteSpeechKey } : null,
    routeStart:
      routeStartSpeechPrompt && routeStartSpeechKey ? { prompt: routeStartSpeechPrompt, key: routeStartSpeechKey } : null,
    sensorPause:
      isTrackingStatus(effectiveStatus) && !activeSensorGate.allowed && activeSensorGate.message
        ? { prompt: activeSensorGate.message, key: `navigation-sensor-pause:${activeSensorGate.reason}` }
        : null,
    tactileLocal:
      tactilePrompt && route
        ? { prompt: tactilePrompt, key: `normal-tactile-local:${routeId(route)}:${tactileRoutePolicy.steering}` }
        : null,
    tmapGuide: guidePrompt ? { prompt: guidePrompt.prompt, key: guidePrompt.key } : null
  });
  const navigationSpeechPrompt = navigationSpeech?.prompt ?? null;
  const navigationSpeechKey = navigationSpeech?.key ?? null;
  const futureRouteRoiText = describeFutureRouteRoiState(tactileRouteSupport.reason);

  const instructionText = useMemo(() => {
    if (effectiveStatus === "searching_destination") {
      return "목적지 검색 중";
    }
    if (effectiveStatus === "destination_candidates") {
      return "목적지 후보 선택 필요";
    }
    if (effectiveStatus === "loading") {
      return "보행 경로 제공자 응답 대기 중";
    }
    if (effectiveStatus === "missing_location") {
      return "현재 위치 확인 필요";
    }
    if (effectiveStatus === "missing_destination") {
      return "목적지 좌표 설정 필요";
    }
    if (effectiveStatus === "error") {
      return "길안내 실패";
    }
    if (effectiveStatus === "off_route_candidate") {
      return "경로 이탈 가능성 확인 중";
    }
    if (effectiveStatus === "rerouting") {
      return "경로 이탈 · 재탐색 필요";
    }
    if (effectiveStatus === "active") {
      if (!activeSensorGate.allowed) {
        return activeSensorGate.message ?? "길안내 일시 중지";
      }
      return tactileRoutePolicy.mode === "tactile_local"
        ? `점자블록 로컬 경로 안내 중 · ${tactileRoutePolicy.steering === "left" ? "왼쪽 정렬" : tactileRoutePolicy.steering === "right" ? "오른쪽 정렬" : "직진"}`
        : `TMAP 경로 안내 중 · ${futureRouteRoiText}`;
    }
    return hasNavigationDestination ? "음성으로 길안내 시작 가능" : "목적지 검색 필요";
  }, [activeSensorGate.allowed, activeSensorGate.message, effectiveStatus, futureRouteRoiText, hasNavigationDestination, tactileRoutePolicy.mode, tactileRoutePolicy.steering]);

  const detailText = useMemo(() => {
    if (route) {
      const providerText = `경로 제공자 ${WALKING_ROUTE_PROVIDER_LABEL}`;
      const speedText = walkingSpeedMps === null ? "속도 추정 중" : `보행 속도 ${walkingSpeedMps.toFixed(1)}m/s`;
      const progressText = routeProgressText(routeProgress);
      const progressRatioText =
        routeProgress?.progressRatio === null || routeProgress?.progressRatio === undefined
          ? null
          : `진행률 ${Math.round(routeProgress.progressRatio * 100)}%`;
      const arrivalText =
        routeArrival?.distanceToDestinationM === null || routeArrival?.distanceToDestinationM === undefined
          ? null
          : `도착까지 ${formatDistance(routeArrival.distanceToDestinationM)} · 도착 확인 ${stabilizedRouteArrival.consecutiveArrivalCount}/${stabilizedRouteArrival.requiredConsecutiveArrivalCount}`;
      return [
        `${formatDistance(route.summary.distance_m)} · ${formatDuration(route.summary.duration_s)} · ${route.steps.length}개 보행 구간`,
        providerText,
        speedText,
        activeSensorGate.allowed ? null : activeSensorGate.message,
        futureRouteRoiText,
        progressText,
        progressRatioText,
        arrivalText
      ].filter(Boolean).join(" · ");
    }
    if (destinationCandidates.length > 0) {
      return destinationCandidates.map((candidate, index) => `${index + 1}. ${candidate.label}`).join(" / ");
    }
    if (selectedDestination) {
      return `목적지: ${selectedDestination.name ?? destinationName}`;
    }
    if (destinationName) {
      return `목적지: ${destinationName}`;
    }
    return "목적지를 먼저 말해 주세요";
  }, [activeSensorGate.allowed, activeSensorGate.message, destinationCandidates, destinationName, futureRouteRoiText, route, routeArrival, routeProgress, selectedDestination, stabilizedRouteArrival.consecutiveArrivalCount, stabilizedRouteArrival.requiredConsecutiveArrivalCount, walkingSpeedMps]);

  const statusMessage = useMemo(() => {
    const headingText = heading === null ? "" : ` 방향 ${heading}도.`;
    return `길안내 상태. ${instructionText}. ${detailText}.${headingText}`;
  }, [detailText, heading, instructionText]);

  return {
    navigationStatus: effectiveStatus,
    navigationActive: isTrackingStatus(effectiveStatus),
    navigationStatusText: effectiveMessage,
    navigationInstructionText: instructionText,
    navigationDetailText: detailText,
    navigationSpeechPrompt,
    navigationSpeechKey,
    navigationStatusMessage: statusMessage,
    navigationRouteBearingDeg: routeBearingDeg,
    navigationFutureMotion: futureMotion,
    navigationTactileRouteSupport: tactileRouteSupport,
    navigationTactileRoutePolicy: tactileRoutePolicy,
    navigationDestinationCandidates: destinationCandidates,
    navigationHiddenDestinationCandidateCount: hiddenDestinationCandidateCount,
    navigationCanShowMoreDestinations: hiddenDestinationCandidateCount > 0,
    navigationSearchActive: destinationSearchActive,
    hasNavigationDestination,
    setVoiceDestination,
    selectDestinationCandidate,
    selectDestinationCandidateByIndex,
    showMoreDestinationCandidates,
    cancelDestinationSearch,
    cancelDestinationAndNavigation,
    retryDestinationSearch,
    getNextNavigationInstruction,
    startNavigation,
    stopNavigation
  };
}
