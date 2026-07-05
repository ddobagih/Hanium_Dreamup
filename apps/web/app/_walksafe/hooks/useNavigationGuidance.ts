"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { fetchWalkingRoute, NavigationApiError, searchDestinations } from "@/lib/navigation-api";
import type { GpsFixV2, TwoModelDetection } from "@/types/inference-v2";
import type { RoutePoint, WalkingRouteGuidePoint, WalkingRouteResponse } from "@/types/navigation";
import {
  WALKSAFE_DEFAULT_DESTINATION,
  WALKSAFE_DEFAULT_STEP_LENGTH_M,
  WALKSAFE_DESTINATION_MAX_DISTANCE_M,
  WALKSAFE_DESTINATION_SEARCH_DEBOUNCE_MS,
  WALKSAFE_ARRIVAL_RADIUS_M,
  WALKSAFE_AUTO_REROUTE_COOLDOWN_MS,
  WALKSAFE_AUTO_REROUTE_MAX_COUNT,
  WALKSAFE_GPS_SAMPLE_MAX_DISTANCE_M,
  WALKSAFE_GPS_SAMPLE_MAX_INTERVAL_MS,
  WALKSAFE_GPS_SAMPLE_MIN_INTERVAL_MS,
  WALKSAFE_GUIDE_SOON_RADIUS_M,
  WALKSAFE_GUIDE_TURN_RADIUS_M,
  WALKSAFE_MAX_WALKING_SPEED_MPS,
  WALKSAFE_MIN_WALKING_SPEED_MPS,
  WALKSAFE_OFF_ROUTE_THRESHOLD_M,
  WALKSAFE_REROUTE_MAX_ACCURACY_M,
  WALKSAFE_REROUTE_MAX_GPS_JUMP_M
} from "../config";
import {
  resolveNavigationDestinationPolicy,
  type NavigationDestinationCandidate,
  type NavigationDestinationPolicy
} from "../navigation-destination";
import {
  buildRouteMeasure,
  distanceMeters,
  evaluateRouteArrivalStatus,
  evaluateOffRouteStatus,
  projectPointToRoute,
  stabilizeOffRouteStatus,
  type RouteMeasure,
  type RouteProgressEvaluation
} from "../route-progress";

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

type UseNavigationGuidanceOptions = {
  gps: GpsFixV2 | null;
  heading: number | null;
  v2Detections: TwoModelDetection[];
  stepLengthM?: number;
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
  routeId: string | null;
  count: number;
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

function isNormalTactileBlock(detection: TwoModelDetection): boolean {
  return (
    (detection.model_key === "custom_tactile" || detection.model_key === "unified_walksafe") &&
    detection.class_name === "normal_tactile_block"
  );
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

export function useNavigationGuidance({ gps, heading, v2Detections, stepLengthM = WALKSAFE_DEFAULT_STEP_LENGTH_M }: UseNavigationGuidanceOptions) {
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
  const routeRequestInFlightRef = useRef(false);
  const lastRouteRequestStartedAtMsRef = useRef<number | null>(null);
  const destinationSearchAbortRef = useRef<AbortController | null>(null);
  const destinationSearchSequenceRef = useRef(0);
  const lastDestinationSearchQueryRef = useRef<string | null>(null);
  const lastDestinationSearchResponseRef = useRef<Awaited<ReturnType<typeof searchDestinations>> | null>(null);
  const [destinationCandidateLimit, setDestinationCandidateLimit] = useState(3);
  const [hiddenDestinationCandidateCount, setHiddenDestinationCandidateCount] = useState(0);
  const autoRerouteStateRef = useRef<AutoRerouteState>({ routeId: null, count: 0 });
  const [routeProgressHistory, setRouteProgressHistory] = useState<RouteProgressHistory>({ routeId: null, samples: [] });

  const hasNavigationDestination = selectedDestination !== null;
  const hasNormalTactileBlock = useMemo(() => v2Detections.some(isNormalTactileBlock), [v2Detections]);

  const applyDestinationPolicy = useCallback((policy: NavigationDestinationPolicy) => {
    setDestinationCandidates(policy.candidates);
    setHiddenDestinationCandidateCount(policy.hiddenCandidateCount);
  }, []);

  const setVoiceDestination = useCallback(async (nextDestinationName: string): Promise<NavigationActionResult> => {
    const query = nextDestinationName.replace(/\s+/g, " ").trim();
    const requestSequence = destinationSearchSequenceRef.current + 1;
    destinationSearchSequenceRef.current = requestSequence;
    destinationSearchAbortRef.current?.abort();
    const abortController = new AbortController();
    destinationSearchAbortRef.current = abortController;
    lastDestinationSearchQueryRef.current = query;
    lastDestinationSearchResponseRef.current = null;
    setDestinationCandidateLimit(3);
    setHiddenDestinationCandidateCount(0);
    setDestinationName(query);
    setRoute(null);
    setSelectedDestination(null);
    setDestinationCandidates([]);
    setRouteStartSpeechPrompt(null);
    setRouteStartSpeechKey(null);

    if (!query) {
      const nextMessage = "목적지를 다시 말씀해 주세요.";
      destinationSearchAbortRef.current = null;
      setStatus("missing_destination");
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    }

    setStatus("searching_destination");
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
      if (destinationSearchSequenceRef.current !== requestSequence) {
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
        setDestinationCandidates([]);
        setHiddenDestinationCandidateCount(0);
        setSelectedDestination(nextDestination);
        setDestinationName(candidate.name);
        setStatus("idle");
        const nextMessage = `${candidate.label} 선택됨 · “길안내 시작”이라고 말하면 시작합니다.`;
        setMessage(nextMessage);
        return { ok: true, message: `${policy.speechMessage} 길안내 시작이라고 말하면 시작합니다.` };
      }

      if (policy.status === "multiple_candidates") {
        setStatus("destination_candidates");
        setMessage(policy.message);
        return { ok: false, message: policy.speechMessage };
      }

      setStatus("missing_destination");
      setMessage(policy.message);
      return { ok: false, message: policy.speechMessage };
    } catch (error) {
      if (
        destinationSearchSequenceRef.current !== requestSequence ||
        (error instanceof NavigationApiError && error.code === "aborted")
      ) {
        return { ok: false, message: "목적지 검색을 취소했습니다." };
      }
      const nextMessage = error instanceof Error ? error.message : "목적지 검색에 실패했습니다.";
      setStatus("error");
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    } finally {
      if (destinationSearchAbortRef.current === abortController) {
        destinationSearchAbortRef.current = null;
      }
    }
  }, [applyDestinationPolicy, gps]);

  const selectDestinationCandidate = useCallback((candidateId: string): NavigationActionResult => {
    const candidate = destinationCandidates.find((item) => item.id === candidateId);
    if (!candidate) {
      const nextMessage = "선택할 목적지 후보를 찾지 못했습니다.";
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    }

    const nextDestination = candidatePoint(candidate);
    setSelectedDestination(nextDestination);
    setDestinationName(candidate.name);
    setDestinationCandidates([]);
    setHiddenDestinationCandidateCount(0);
    setRoute(null);
    setStatus("idle");
    const nextMessage = `${candidate.label} 선택됨 · 길안내 시작 가능`;
    setMessage(nextMessage);
    return { ok: true, message: `${candidate.speechLabel} 선택됨. 길안내 시작이라고 말하면 시작합니다.` };
  }, [destinationCandidates]);

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
    setStatus(policy.status === "multiple_candidates" ? "destination_candidates" : status);
    setMessage(policy.message);
    return { ok: true, message: policy.speechMessage };
  }, [applyDestinationPolicy, destinationCandidateLimit, hiddenDestinationCandidateCount, status]);

  const cancelDestinationSearch = useCallback((): NavigationActionResult => {
    destinationSearchAbortRef.current?.abort();
    destinationSearchAbortRef.current = null;
    destinationSearchSequenceRef.current += 1;
    lastDestinationSearchResponseRef.current = null;
    setDestinationCandidates([]);
    setHiddenDestinationCandidateCount(0);
    setDestinationCandidateLimit(3);
    setRoute(null);
    setSelectedDestination(null);
    setStatus("missing_destination");
    const nextMessage = "목적지 검색을 취소했습니다. 목적지를 다시 말해 주세요.";
    setMessage(nextMessage);
    return { ok: true, message: nextMessage };
  }, []);

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
    if (!gps) {
      const nextMessage = "현재 위치 확인 후 길안내를 시작해 주세요.";
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
    const routeRequestGate = resolveNavigationRouteRequestGate({
      requestInFlight: routeRequestInFlightRef.current,
      lastRequestStartedAtMs: lastRouteRequestStartedAtMsRef.current,
      nowMs
    });
    if (!routeRequestGate.allowed) {
      const nextMessage = routeRequestGate.message ?? "잠시 후 다시 시도해 주세요.";
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    }

    routeRequestInFlightRef.current = true;
    lastRouteRequestStartedAtMsRef.current = nowMs;
    setStatus("loading");
    setMessage("TMAP 보행 경로 요청 중");
    setRouteStartSpeechPrompt(null);
    setRouteStartSpeechKey(null);

    try {
      const nextRoute = await fetchWalkingRoute({
        origin: routePointFromGps(gps),
        destination: selectedDestination,
        priority: "STAIR_AVOID"
      });
      const routeSummary = `${formatDistance(nextRoute.summary.distance_m)}, ${formatDuration(nextRoute.summary.duration_s)}`;
      const nextMessage = `길안내 시작 · ${routeSummary}`;
      autoRerouteStateRef.current = { routeId: routeId(nextRoute), count: 0 };
      setAutoRerouteMessage(null);
      setRoute(nextRoute);
      setStatus("active");
      setMessage(nextMessage);
      setRouteStartSpeechPrompt(`길 안내를 시작합니다. 전체 ${routeSummary}. 점자블록이 감지되면 따라 이동하세요.`);
      setRouteStartSpeechKey(`route-start:${nextRoute.provider_route_id ?? Date.now()}`);
      return { ok: true, message: nextMessage };
    } catch (error) {
      const nextMessage = error instanceof Error ? error.message : "도보 길안내 요청에 실패했습니다.";
      setRoute(null);
      setStatus("error");
      setMessage(nextMessage);
      return { ok: false, message: nextMessage };
    } finally {
      routeRequestInFlightRef.current = false;
    }
  }, [destinationCandidates.length, gps, selectedDestination]);

  const stopNavigation = useCallback(() => {
    setStatus("idle");
    setRoute(null);
    setWalkingSpeedMps(null);
    lastGpsSampleRef.current = null;
    previousGpsSampleRef.current = null;
    autoRerouteStateRef.current = { routeId: null, count: 0 };
    setMessage("길안내 대기");
    setAutoRerouteMessage(null);
    setRouteStartSpeechPrompt(null);
    setRouteStartSpeechKey(null);
  }, []);

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
    return evaluateOffRouteStatus({
      route,
      currentPoint: routePointFromGps(gps),
      accuracyM: gps.accuracy_m ?? null,
      offRouteThresholdM: WALKSAFE_OFF_ROUTE_THRESHOLD_M
    });
  }, [gps, route]);

  const routeArrival = useMemo(() => {
    if (!route || !gps) {
      return null;
    }
    return evaluateRouteArrivalStatus({
      route,
      currentPoint: routePointFromGps(gps),
      accuracyM: gps.accuracy_m ?? null,
      arrivalRadiusM: WALKSAFE_ARRIVAL_RADIUS_M
    });
  }, [gps, route]);

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
    if (!route || !routeArrival?.reached || !isTrackingStatus(status)) {
      return;
    }

    const timer = window.setTimeout(() => {
      autoRerouteStateRef.current = { routeId: null, count: 0 };
      setAutoRerouteMessage(null);
      setRoute(null);
      setRouteProgressHistory({ routeId: null, samples: [] });
      setWalkingSpeedMps(null);
      setStatus("idle");
      const nextMessage = "목적지에 도착했습니다.";
      setMessage(nextMessage);
      setRouteStartSpeechPrompt(nextMessage);
      setRouteStartSpeechKey(`route-arrival:${routeId(route)}:${Date.now()}`);
    }, 0);
    return () => window.clearTimeout(timer);
  }, [route, routeArrival?.reached, status]);

  const stabilizedRouteProgress = useMemo(
    () => stabilizeOffRouteStatus(routeProgressHistory.samples),
    [routeProgressHistory.samples]
  );

  const effectiveStatus = useMemo<NavigationGuidanceStatus>(() => {
    if (status === "active" && stabilizedRouteProgress.status === "off_route" && stabilizedRouteProgress.confirmed) {
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
    if (effectiveStatus !== "rerouting" || !route) {
      return;
    }

    const currentRouteId = routeId(route);
    const autoState =
      autoRerouteStateRef.current.routeId === currentRouteId
        ? autoRerouteStateRef.current
        : { routeId: currentRouteId, count: 0 };
    autoRerouteStateRef.current = autoState;

    const nowMs = Date.now();
    const decision = resolveAutoRerouteDecision({
      confirmedOffRoute: stabilizedRouteProgress.confirmed,
      destination: selectedDestination,
      currentGpsSample: lastGpsSampleRef.current,
      previousGpsSample: previousGpsSampleRef.current,
      requestInFlight: routeRequestInFlightRef.current,
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

    routeRequestInFlightRef.current = true;
    lastRouteRequestStartedAtMsRef.current = nowMs;
    autoRerouteStateRef.current = {
      routeId: currentRouteId,
      count: autoState.count + 1
    };
    setStatus("rerouting");

    void (async () => {
      try {
        const nextRoute = await fetchWalkingRoute({
          origin: routePointFromGps(gps),
          destination: selectedDestination,
          priority: "STAIR_AVOID"
        });
        const routeSummary = `${formatDistance(nextRoute.summary.distance_m)}, ${formatDuration(nextRoute.summary.duration_s)}`;
        setRoute(nextRoute);
        setRouteProgressHistory({ routeId: null, samples: [] });
        setStatus("active");
        setAutoRerouteMessage(null);
        setMessage(`자동 재탐색 완료 · ${routeSummary}`);
        setRouteStartSpeechPrompt(`경로를 다시 탐색했습니다. 전체 ${routeSummary}.`);
        setRouteStartSpeechKey(`route-reroute:${routeId(nextRoute)}:${Date.now()}`);
        autoRerouteStateRef.current = {
          routeId: routeId(nextRoute),
          count: 0
        };
      } catch (error) {
        const nextMessage = error instanceof Error ? error.message : "자동 재탐색에 실패했습니다.";
        setStatus("active");
        setAutoRerouteMessage(`자동 재탐색 실패 · ${nextMessage}`);
        setMessage(`자동 재탐색 실패 · 기존 경로를 유지합니다. ${nextMessage}`);
        setRouteStartSpeechPrompt(`자동 재탐색에 실패했습니다. 기존 경로를 유지합니다.`);
        setRouteStartSpeechKey(`route-reroute-failed:${currentRouteId}:${Date.now()}`);
      } finally {
        routeRequestInFlightRef.current = false;
      }
    })();
  }, [effectiveStatus, gps, route, selectedDestination, stabilizedRouteProgress.confirmed]);

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

  const guidePrompt = useMemo(() => {
    if ((effectiveStatus !== "active" && effectiveStatus !== "off_route_candidate") || !route || !gps || route.guide_points.length === 0) {
      return null;
    }

    const measure = buildRouteMeasure(route);
    const routeDistanceM = route.summary.distance_m || measure.totalDistanceM;
    const projectedDistanceFromStartM = projectPointToRoute(routePointFromGps(gps), measure)?.distanceFromStartM ?? 0;
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

    if (!nextGuide) {
      return null;
    }

    return buildNavigationGuidePrompt({
      guide: nextGuide.guide,
      distanceM: nextGuide.distanceM,
      speedMps: walkingSpeedMps,
      routeId: routeId(route),
      stepLengthM
    });
  }, [effectiveStatus, gps, route, stepLengthM, walkingSpeedMps]);

  const navigationSpeechPrompt =
    rerouteSpeechPrompt ??
    routeStartSpeechPrompt ??
    guidePrompt?.prompt ??
    (effectiveStatus === "active" && hasNormalTactileBlock ? "점자블록을 따라 이동하세요." : null);
  const navigationSpeechKey =
    rerouteSpeechKey ??
    routeStartSpeechKey ??
    guidePrompt?.key ??
    (effectiveStatus === "active" && hasNormalTactileBlock ? "normal-tactile-follow" : null);

  const instructionText = useMemo(() => {
    if (effectiveStatus === "searching_destination") {
      return "목적지 검색 중";
    }
    if (effectiveStatus === "destination_candidates") {
      return "목적지 후보 선택 필요";
    }
    if (effectiveStatus === "loading") {
      return "TMAP 보행 경로 요청 중";
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
      return hasNormalTactileBlock ? "점자블록 감지 · 따라 이동" : "경로 안내 중 · 점자블록 탐색 중";
    }
    return hasNavigationDestination ? "음성으로 길안내 시작 가능" : "목적지 검색 필요";
  }, [effectiveStatus, hasNavigationDestination, hasNormalTactileBlock]);

  const detailText = useMemo(() => {
    if (route) {
      const speedText = walkingSpeedMps === null ? "속도 추정 중" : `보행 속도 ${walkingSpeedMps.toFixed(1)}m/s`;
      const progressText = routeProgressText(routeProgress);
      const progressRatioText =
        routeProgress?.progressRatio === null || routeProgress?.progressRatio === undefined
          ? null
          : `진행률 ${Math.round(routeProgress.progressRatio * 100)}%`;
      const arrivalText =
        routeArrival?.distanceToDestinationM === null || routeArrival?.distanceToDestinationM === undefined
          ? null
          : `도착까지 ${formatDistance(routeArrival.distanceToDestinationM)}`;
      return [
        `${formatDistance(route.summary.distance_m)} · ${formatDuration(route.summary.duration_s)} · ${route.steps.length}개 보행 구간`,
        speedText,
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
  }, [destinationCandidates, destinationName, route, routeArrival, routeProgress, selectedDestination, walkingSpeedMps]);

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
    navigationDestinationCandidates: destinationCandidates,
    navigationHiddenDestinationCandidateCount: hiddenDestinationCandidateCount,
    navigationCanShowMoreDestinations: hiddenDestinationCandidateCount > 0,
    navigationSearchActive: status === "searching_destination",
    hasNavigationDestination,
    setVoiceDestination,
    selectDestinationCandidate,
    selectDestinationCandidateByIndex,
    showMoreDestinationCandidates,
    cancelDestinationSearch,
    retryDestinationSearch,
    startNavigation,
    stopNavigation
  };
}
