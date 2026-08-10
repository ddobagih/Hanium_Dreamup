"use client";

/**
 * Arbitrates visual, vibration and speech feedback across detection risk and navigation guidance.
 * Immediate risk speech outranks route prompts, and model-estimated distance never becomes metric guidance.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { labelForTwoModelDetection } from "@/lib/detector-v2";
import { CLASS_LABELS, type DetectionEvent, type GpsFix } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";
import type { FutureMotionProjection } from "../motion-projection";
import {
  detectionAvailabilityLabel,
  detectionAvailabilitySpeech,
  type DetectionAvailability
} from "../detection-availability";
import { SPEECH_COOLDOWN_MS, WALKSAFE_GUIDANCE_MAX_GPS_ACCURACY_M, WALKSAFE_GUIDE_COOLDOWN_MS } from "../config";
import {
  resolveVoiceFeedbackState,
  shouldApplyNavigationStatusMessage,
  shouldAttemptNavigationSpeech
} from "../voice-priority";
import {
  alertForDetection,
  alertForTwoModelDetection,
  getActiveSpeechPriority,
  getSpeechRecognitionActive,
  getSpeechOutputStatus,
  speak,
  WALKSAFE_SPEECH_ARBITRATION_STATUS_EVENT,
  WALKSAFE_SPEECH_RECOGNITION_STATUS_EVENT,
  WALKSAFE_SPEECH_OUTPUT_STATUS_EVENT,
  type SpeechOutputStatus
} from "../feedback";
import { buildRiskGuidanceMessage, isMetricDistanceGuidanceSource, selectRiskGuidanceCandidate } from "../risk-guidance";
import {
  evaluateDetectionRisk,
  evaluateTwoModelDetectionRisk,
  formatModelEstimateStatusText
} from "../risk-evaluator";
import type { RiskDecision, RiskEvaluationContext } from "../risk-evaluator";
import { formatPercent } from "../utils";
import {
  EMPTY_NON_METRIC_ADVISORY_CONTINUITY_STATE,
  NON_METRIC_HAZARD_ADVISORY_CAPABILITY_LABEL,
  NON_METRIC_HAZARD_ADVISORY_THRESHOLDS,
  advanceNonMetricAdvisoryContinuity,
  isNonMetricHazardAdvisoryRuntimeActive,
  resolveNonMetricAdvisoryActivity,
  selectNonMetricHazardAdvisories,
  type NonMetricAdvisoryFrameObservation
} from "../nonmetric-hazard-advisory";
import type { ReportState } from "./useManualReportV1";
import { useSequentialRiskFeedback } from "./useSequentialRiskFeedback";
import { useTwoModelRiskHistories } from "./useTwoModelRiskHistory";

const NAVIGATION_SPEECH_RETRY_MS = 400;
const NAVIGATION_SPEECH_MAX_RETRIES = 30;
const EMPTY_RISK_CONTEXT: RiskEvaluationContext = {};

type DetectionSafetyAlertState = {
  observedStatus: DetectionAvailability;
  observedEventGeneration: number;
  message: string | null;
  delivery: "idle" | "pending" | "spoken" | "fallback";
  generation: number;
};

type UseRiskFeedbackOptions = {
  detection: DetectionEvent | null;
  detectionAvailability: DetectionAvailability;
  detectionSafetyEventGeneration: number;
  v2Detections: TwoModelDetection[];
  v2Primary: TwoModelDetection | null;
  v2Secondary: TwoModelDetection | null;
  detectorMessage: string;
  reportState: ReportState;
  reportMessage: string;
  gps: GpsFix | null;
  gpsError: string | null;
  heading?: number | null;
  walkingSpeedMps?: number | null;
  motionStability?: number | null;
  futureMotion?: FutureMotionProjection | null;
  cameraReady: boolean;
  navigationActive: boolean;
  sessionActive: boolean;
  nonMetricInferenceFrame: NonMetricAdvisoryFrameObservation;
  speechEnabled: boolean;
  getCurrentLocationMessage: () => string;
  stepLengthM?: number | null;
  navigationSpeechPrompt?: string | null;
  navigationSpeechKey?: string | null;
  navigationStatusMessage?: string | null;
};

function distanceForRiskContext(context: RiskEvaluationContext): number | null {
  if (isMetricDistanceGuidanceSource(context.depth?.source) && typeof context.depth?.distance_m === "number") {
    return context.depth.distance_m;
  }

  // model_estimate/polygon trend는 내부 접근 판단에만 사용하고 사용자 보폭 거리 안내에는 쓰지 않는다.
  return null;
}

export function messageForV2Risk(
  detectionValue: TwoModelDetection,
  risk: RiskDecision,
  context: RiskEvaluationContext,
  fallbackLabel: string,
  stepLengthM?: number | null
): string | null {
  const guidance = buildRiskGuidanceMessage({
    riskType: risk.risk_type,
    riskLevel: risk.risk_level,
    bbox: detectionValue.bbox,
    distanceM: distanceForRiskContext(context),
    distanceSource: context.depth?.source ?? null,
    stepLengthM,
    label: fallbackLabel,
    fallback: risk.recommended_message
  });
  // Monocular bbox distance/TTC remains available to the visual diagnostic status and
  // internal trend policy, but it is not a calibrated metric and must never be spoken.
  return guidance;
}

export function useRiskFeedback({
  detection,
  detectionAvailability,
  detectionSafetyEventGeneration,
  v2Detections,
  v2Primary,
  v2Secondary,
  detectorMessage,
  reportState,
  reportMessage,
  gps,
  gpsError,
  heading = null,
  walkingSpeedMps = null,
  motionStability = null,
  futureMotion = null,
  cameraReady,
  navigationActive,
  sessionActive,
  nonMetricInferenceFrame,
  speechEnabled,
  getCurrentLocationMessage,
  stepLengthM = null,
  navigationSpeechPrompt = null,
  navigationSpeechKey = null,
  navigationStatusMessage = null
}: UseRiskFeedbackOptions) {
  const lastNavigationAlertRef = useRef<{ key: string; time: number }>({ key: "", time: 0 });
  const navigationSpeechRetryRef = useRef<{ key: string; count: number }>({ key: "", count: 0 });
  const navigationSpeechPendingKeyRef = useRef<string | null>(null);
  const navigationSpeechRetryGenerationRef = useRef(0);
  const navigationSpeechTransitionRef = useRef<{ key: string; pending: boolean }>({ key: "", pending: false });
  const [navigationSpeechRetrySequence, setNavigationSpeechRetrySequence] = useState(0);
  const [nonMetricPolicyNowMs, setNonMetricPolicyNowMs] = useState(0);
  const [nonMetricContinuityState, setNonMetricContinuityState] = useState(
    EMPTY_NON_METRIC_ADVISORY_CONTINUITY_STATE
  );
  const { enqueueRiskFeedback, cancelActiveRiskFeedback } = useSequentialRiskFeedback();
  const [speechOutputStatus, setSpeechOutputStatus] = useState<SpeechOutputStatus>(() => getSpeechOutputStatus());
  const [speechRecognitionActive, setSpeechRecognitionStatus] = useState(() => getSpeechRecognitionActive());
  const [activeSpeechPriority, setSpeechPriorityStatus] = useState(() => getActiveSpeechPriority());
  const lastStatusMessageRef = useRef<string | null>(null);
  const currentRiskGateRef = useRef<Map<string, number>>(new Map());
  const nonMetricRuntimeGateRef = useRef(false);
  const nonMetricCurrentKeysRef = useRef<Set<string>>(new Set());
  const [detectionSafetyAlert, setDetectionSafetyAlert] = useState<DetectionSafetyAlertState>({
    observedStatus: detectionAvailability,
    observedEventGeneration: detectionSafetyEventGeneration,
    message: null,
    delivery: "idle",
    generation: 0
  });
  if (
    detectionSafetyAlert.observedStatus !== detectionAvailability ||
    detectionSafetyAlert.observedEventGeneration !== detectionSafetyEventGeneration
  ) {
    const message = detectionAvailabilitySpeech(detectionAvailability);
    const keepSafetyAlert = message !== null && (
      detectionSafetyAlert.observedEventGeneration !== detectionSafetyEventGeneration ||
      detectionSafetyAlert.observedStatus === "available" ||
      detectionSafetyAlert.observedStatus === "checking" ||
      detectionSafetyAlert.message !== null
    );
    setDetectionSafetyAlert({
      observedStatus: detectionAvailability,
      observedEventGeneration: detectionSafetyEventGeneration,
      message: keepSafetyAlert ? message : null,
      delivery: keepSafetyAlert ? "pending" : "idle",
      generation: keepSafetyAlert ? detectionSafetyAlert.generation + 1 : detectionSafetyAlert.generation
    });
  }
  const updateDetectionSafetyDelivery = useCallback((
    status: DetectionAvailability,
    message: string,
    generation: number,
    delivery: DetectionSafetyAlertState["delivery"]
  ) => {
    setDetectionSafetyAlert((current) => {
      if (
        current.observedStatus !== status ||
        current.message !== message ||
        current.generation !== generation ||
        current.delivery === delivery
      ) {
        return current;
      }
      return { ...current, delivery };
    });
  }, []);
  const trackedV2Contexts = useTwoModelRiskHistories(v2Detections, {
    heading,
    walkingSpeedMps,
    motionStability,
    futureMotion
  });

  useEffect(() => {
    const updateSpeechOutputStatus = () => setSpeechOutputStatus(getSpeechOutputStatus());
    window.addEventListener(WALKSAFE_SPEECH_OUTPUT_STATUS_EVENT, updateSpeechOutputStatus);
    updateSpeechOutputStatus();
    return () => window.removeEventListener(WALKSAFE_SPEECH_OUTPUT_STATUS_EVENT, updateSpeechOutputStatus);
  }, []);
  useEffect(() => {
    const updateSpeechPriorityStatus = () => setSpeechPriorityStatus(getActiveSpeechPriority());
    window.addEventListener(WALKSAFE_SPEECH_ARBITRATION_STATUS_EVENT, updateSpeechPriorityStatus);
    updateSpeechPriorityStatus();
    return () => window.removeEventListener(WALKSAFE_SPEECH_ARBITRATION_STATUS_EVENT, updateSpeechPriorityStatus);
  }, []);
  useEffect(() => {
    const updateSpeechRecognitionStatus = () => setSpeechRecognitionStatus(getSpeechRecognitionActive());
    window.addEventListener(WALKSAFE_SPEECH_RECOGNITION_STATUS_EVENT, updateSpeechRecognitionStatus);
    updateSpeechRecognitionStatus();
    return () => window.removeEventListener(WALKSAFE_SPEECH_RECOGNITION_STATUS_EVENT, updateSpeechRecognitionStatus);
  }, []);
  const evaluatedV2Candidates = useMemo(
    () =>
      trackedV2Contexts.map((tracked, index) => ({
        item: tracked.detection,
        risk: evaluateTwoModelDetectionRisk(tracked.detection, tracked.context),
        context: tracked.context,
        confidence: tracked.detection.confidence,
        index
      })),
    [trackedV2Contexts]
  );
  const v2PrimaryCandidate = evaluatedV2Candidates.find((candidate) => candidate.item === v2Primary) ?? null;
  const v2SecondaryCandidate = evaluatedV2Candidates.find((candidate) => candidate.item === v2Secondary) ?? null;
  const v2PrimaryRiskContext = v2PrimaryCandidate?.context ?? EMPTY_RISK_CONTEXT;
  const v2SecondaryRiskContext = v2SecondaryCandidate?.context ?? EMPTY_RISK_CONTEXT;

  const detectionRisk = useMemo(() => (detection ? evaluateDetectionRisk(detection) : null), [detection]);
  const v2PrimaryRisk = v2PrimaryCandidate?.risk ?? null;
  const v2SecondaryRisk = v2SecondaryCandidate?.risk ?? null;
  const gpsAccuracyM = gps?.accuracy_m;
  const nonMetricAdvisoryModeActive = isNonMetricHazardAdvisoryRuntimeActive({
    cameraReady,
    rearCamera: cameraReady,
    navigationActive,
    tmapGuidanceAvailable: typeof gpsAccuracyM === "number" &&
      Number.isFinite(gpsAccuracyM) &&
      gpsAccuracyM >= 0 &&
      gpsAccuracyM <= WALKSAFE_GUIDANCE_MAX_GPS_ACCURACY_M,
    detectionAvailable: detectionAvailability === "available",
    sessionActive,
    // The page lifecycle changes sessionActive on visibility loss; delivery rechecks document visibility too.
    pageVisible: sessionActive
  });
  const alertableV2Candidate = useMemo(
    () => selectRiskGuidanceCandidate(evaluatedV2Candidates),
    [evaluatedV2Candidates]
  );
  const nonMetricFrameCandidates = useMemo(
    () => evaluatedV2Candidates.flatMap((candidate) => {
      const trackId = candidate.context.tracking?.object_id;
      const capturedAtMs = Date.parse(candidate.item.captured_at);
      return trackId && Number.isFinite(capturedAtMs) ? [{ trackId, capturedAtMs }] : [];
    }),
    [evaluatedV2Candidates]
  );
  useEffect(() => {
    const timer = window.setTimeout(() => {
      setNonMetricContinuityState((previous) => nonMetricAdvisoryModeActive
        ? advanceNonMetricAdvisoryContinuity(previous, nonMetricInferenceFrame, nonMetricFrameCandidates).state
        : EMPTY_NON_METRIC_ADVISORY_CONTINUITY_STATE
      );
    }, 0);
    return () => window.clearTimeout(timer);
  }, [nonMetricAdvisoryModeActive, nonMetricFrameCandidates, nonMetricInferenceFrame]);
  const nonMetricContinuityByTrack = useMemo(
    () => new Map(nonMetricContinuityState.evidence.map((item) => [item.trackId, item])),
    [nonMetricContinuityState.evidence]
  );
  useEffect(() => {
    const timer = window.setTimeout(() => setNonMetricPolicyNowMs(Date.now()), 0);
    return () => window.clearTimeout(timer);
  }, [evaluatedV2Candidates, nonMetricAdvisoryModeActive, nonMetricContinuityState.evidence]);
  const nonMetricAdvisories = useMemo(
    () => nonMetricAdvisoryModeActive
      ? selectNonMetricHazardAdvisories(
          evaluatedV2Candidates.flatMap((candidate) => {
            const trackId = candidate.context.tracking?.object_id;
            const continuity = trackId ? nonMetricContinuityByTrack.get(trackId) : null;
            return continuity ? [{
              detection: candidate.item,
              context: candidate.context,
              riskAlertable: candidate.risk.alertable,
              continuity
            }] : [];
          }),
          { nowMs: nonMetricPolicyNowMs, motionStability }
        )
      : [],
    [evaluatedV2Candidates, motionStability, nonMetricAdvisoryModeActive, nonMetricContinuityByTrack, nonMetricPolicyNowMs]
  );
  const activeNonMetricAdvisory = nonMetricAdvisories[0] ?? null;
  const alertableV2Detection = alertableV2Candidate?.item ?? null;
  const alertableV2Risk = alertableV2Candidate?.risk ?? null;
  const alertableV2RiskContext = alertableV2Candidate?.context ?? EMPTY_RISK_CONTEXT;
  const primaryModelEstimateText = formatModelEstimateStatusText(v2PrimaryRiskContext);
  const feedbackActivity = resolveNonMetricAdvisoryActivity(
    Boolean(detectionRisk?.alertable || alertableV2Risk?.alertable),
    nonMetricAdvisories.length
  );
  const riskActive = feedbackActivity.riskActive;
  const detectionLabel = detection
    ? CLASS_LABELS[detection.class_name]
    : alertableV2Detection
      ? labelForTwoModelDetection(alertableV2Detection)
      : v2Primary
        ? labelForTwoModelDetection(v2Primary)
        : "탐지 대기";
  const riskText = detection && detectionRisk?.alertable
    ? `${detectionLabel} ${formatPercent(detection.confidence)}`
    : alertableV2Detection && alertableV2Risk?.alertable
      ? `${messageForV2Risk(alertableV2Detection, alertableV2Risk, alertableV2RiskContext, detectionLabel, stepLengthM) ?? detectionLabel} ${formatPercent(alertableV2Detection.confidence)}`
      : activeNonMetricAdvisory
        ? `${activeNonMetricAdvisory.message} ${formatPercent(activeNonMetricAdvisory.detection.confidence)}`
      : !nonMetricAdvisoryModeActive && primaryModelEstimateText && v2Primary
        ? `${labelForTwoModelDetection(v2Primary)} · ${primaryModelEstimateText}`
        : nonMetricAdvisoryModeActive
          ? `${NON_METRIC_HAZARD_ADVISORY_CAPABILITY_LABEL} · 안정적인 3프레임 감지 대기`
          : detectionAvailabilityLabel(detectionAvailability);

  useEffect(() => {
    nonMetricRuntimeGateRef.current = nonMetricAdvisoryModeActive &&
      (typeof document === "undefined" || document.visibilityState === "visible");
  }, [nonMetricAdvisoryModeActive]);

  useEffect(() => {
    if (nonMetricAdvisories.length === 0) return;
    const expiresAtMs = Math.min(
      ...nonMetricAdvisories.map(
        (advisory) => advisory.lastSeenAtMs + NON_METRIC_HAZARD_ADVISORY_THRESHOLDS.maximumDetectionAgeMs
      )
    );
    const timer = window.setTimeout(
      () => setNonMetricPolicyNowMs(Date.now()),
      Math.max(0, expiresAtMs - Date.now() + 1)
    );
    return () => window.clearTimeout(timer);
  }, [nonMetricAdvisories]);

  useEffect(() => {
    const message = detectionSafetyAlert.message;
    for (const key of currentRiskGateRef.current.keys()) {
      if (key.startsWith("availability:")) currentRiskGateRef.current.delete(key);
    }
    if (message === null) {
      cancelActiveRiskFeedback("availability:");
      return;
    }
    if (speechEnabled) {
      const generation = detectionSafetyAlert.generation;
      const key = `availability:${detectionAvailability}:${generation}`;
      const lastSeenAtMs = Date.now();
      currentRiskGateRef.current.set(key, 3);
      enqueueRiskFeedback({
        key,
        message,
        riskLevel: "high",
        lastSeenAtMs,
        speechEnabled: true,
        isCurrent: () => currentRiskGateRef.current.get(key) === 3,
        observer: {
          onStart: () => updateDetectionSafetyDelivery(detectionAvailability, message, generation, "spoken"),
          onFailure: () => updateDetectionSafetyDelivery(detectionAvailability, message, generation, "fallback"),
          onCancel: () => updateDetectionSafetyDelivery(detectionAvailability, message, generation, "pending")
        }
      });
    }
  }, [cancelActiveRiskFeedback, detectionAvailability, detectionSafetyAlert.generation, detectionSafetyAlert.message, enqueueRiskFeedback, speechEnabled, updateDetectionSafetyDelivery]);

  useEffect(() => {
    for (const key of currentRiskGateRef.current.keys()) {
      if (key.startsWith("v1:")) currentRiskGateRef.current.delete(key);
    }
    if (!detection) {
      return;
    }

    const risk = evaluateDetectionRisk(detection);
    if (!risk.alertable) {
      lastStatusMessageRef.current = risk.reportable
        ? `신고 대상 감지. ${CLASS_LABELS[detection.class_name]}.`
        : `탐지 상태. ${CLASS_LABELS[detection.class_name]}.`;
      return;
    }

    lastStatusMessageRef.current = `현재 위험. ${CLASS_LABELS[detection.class_name]}. 신뢰도 ${formatPercent(detection.confidence)}.`;
    const key = `v1:${detection.class_name}`;
    const alert = alertForDetection(detection, speechEnabled, risk.risk_level);
    const rank = risk.risk_level === "high" ? 3 : risk.risk_level === "medium" ? 2 : 1;
    currentRiskGateRef.current.set(key, rank);
    enqueueRiskFeedback({
      key,
      message: risk.recommended_message ?? alert.speech,
      riskLevel: risk.risk_level,
      lastSeenAtMs: Date.parse(detection.captured_at),
      speechEnabled,
      vibration: alert.vibration,
      isCurrent: () => currentRiskGateRef.current.get(key) === rank
    });
  }, [detection, enqueueRiskFeedback, speechEnabled]);

  useEffect(() => {
    for (const key of currentRiskGateRef.current.keys()) {
      if (key.startsWith("v2:")) currentRiskGateRef.current.delete(key);
    }
    if (v2Detections.length === 0) {
      return;
    }

    const primaryLabel = v2Primary ? labelForTwoModelDetection(v2Primary) : "탐지 객체";
    const activeRiskDetection = alertableV2Candidate?.item ?? null;
    const activeRisk = alertableV2Candidate?.risk ?? null;

    if (!activeRiskDetection || !activeRisk) {
      const estimateText = nonMetricAdvisoryModeActive ? null : formatModelEstimateStatusText(v2PrimaryRiskContext);
      lastStatusMessageRef.current = v2PrimaryRisk?.reportable
        ? `자동 신고 대상 감지. ${primaryLabel}.`
        : nonMetricAdvisoryModeActive
          ? `${NON_METRIC_HAZARD_ADVISORY_CAPABILITY_LABEL}. 안정적인 감지를 확인 중입니다.`
          : `탐지 상태. ${primaryLabel}.${estimateText ? ` ${estimateText}.` : ""}`;
      return;
    }

    const activeLabel = labelForTwoModelDetection(activeRiskDetection);
    const activeRiskContext = alertableV2Candidate?.context ?? EMPTY_RISK_CONTEXT;
    const activeRiskMessage = messageForV2Risk(activeRiskDetection, activeRisk, activeRiskContext, activeLabel, stepLengthM);
    lastStatusMessageRef.current = `현재 위험. ${activeRiskMessage ?? activeLabel}. 신뢰도 ${formatPercent(activeRiskDetection.confidence)}.`;
    for (const candidate of evaluatedV2Candidates) {
      if (!candidate.risk.alertable) continue;
      const candidateContext = candidate.context ?? EMPTY_RISK_CONTEXT;
      const key = `v2:${candidateContext.tracking?.object_id ?? `${candidate.item.model_key}:${candidate.item.class_name}`}`;
      const label = labelForTwoModelDetection(candidate.item);
      const message = messageForV2Risk(candidate.item, candidate.risk, candidateContext, label, stepLengthM);
      const alert = alertForTwoModelDetection(candidate.item, speechEnabled, candidate.risk.risk_level);
      const rank = candidate.risk.risk_level === "high" ? 3 : candidate.risk.risk_level === "medium" ? 2 : 1;
      currentRiskGateRef.current.set(key, rank);
      enqueueRiskFeedback({
        key,
        message: message ?? alert.speech,
        riskLevel: candidate.risk.risk_level,
        lastSeenAtMs: Date.parse(candidate.item.captured_at),
        speechEnabled,
        vibration: alert.vibration,
        isCurrent: () => currentRiskGateRef.current.get(key) === rank
      });
    }
  }, [alertableV2Candidate, enqueueRiskFeedback, evaluatedV2Candidates, nonMetricAdvisoryModeActive, speechEnabled, stepLengthM, v2Detections.length, v2Primary, v2PrimaryRisk, v2PrimaryRiskContext]);

  useEffect(() => {
    const nextKeys = new Set(nonMetricAdvisories.map((advisory) => advisory.key));
    for (const previousKey of nonMetricCurrentKeysRef.current) {
      if (!nextKeys.has(previousKey)) cancelActiveRiskFeedback(previousKey);
    }
    for (const key of currentRiskGateRef.current.keys()) {
      if (key.startsWith("nonmetric:")) currentRiskGateRef.current.delete(key);
    }
    nonMetricCurrentKeysRef.current = nextKeys;

    if (!nonMetricAdvisoryModeActive || nonMetricAdvisories.length === 0) {
      if (nonMetricAdvisoryModeActive && !v2PrimaryRisk?.reportable) {
        lastStatusMessageRef.current = `${NON_METRIC_HAZARD_ADVISORY_CAPABILITY_LABEL}. 안정적인 감지를 확인 중입니다.`;
      }
      return;
    }

    const activeAdvisory = nonMetricAdvisories[0];
    lastStatusMessageRef.current = `카메라 보조 경고. ${activeAdvisory.message}`;
    // This local event has no report callback or reportable field; consented damaged-tactile reporting stays separate.
    for (const advisory of nonMetricAdvisories) {
      const rank = 1;
      currentRiskGateRef.current.set(advisory.key, rank);
      enqueueRiskFeedback({
        key: advisory.key,
        message: advisory.message,
        riskLevel: advisory.riskLevel,
        lastSeenAtMs: advisory.lastSeenAtMs,
        speechEnabled,
        speechPriority: "advisory",
        isCurrent: () => currentRiskGateRef.current.get(advisory.key) === rank &&
          nonMetricRuntimeGateRef.current &&
          (typeof document === "undefined" || document.visibilityState === "visible")
      });
    }
  }, [cancelActiveRiskFeedback, enqueueRiskFeedback, nonMetricAdvisories, nonMetricAdvisoryModeActive, speechEnabled, v2PrimaryRisk?.reportable]);

  useEffect(() => {
    if (!detection && v2Detections.length === 0) {
      lastStatusMessageRef.current = `탐지 상태. ${detectorMessage}.`;
    }
  }, [detection, detectorMessage, v2Detections.length]);

  useEffect(() => {
    if (!riskActive) {
      lastStatusMessageRef.current = `신고 상태. ${reportMessage}.`;
    }
  }, [reportMessage, reportState, riskActive]);

  useEffect(() => {
    if (!riskActive && (gps || gpsError)) {
      lastStatusMessageRef.current = getCurrentLocationMessage();
    }
  }, [getCurrentLocationMessage, gps, gpsError, riskActive]);

  useEffect(() => {
    if (shouldApplyNavigationStatusMessage(navigationStatusMessage, riskActive)) {
      lastStatusMessageRef.current = navigationStatusMessage;
    }
  }, [navigationStatusMessage, riskActive]);

  const voiceFeedbackState = resolveVoiceFeedbackState({
    speechEnabled,
    riskActive: riskActive || (detectionSafetyAlert.message !== null && detectionSafetyAlert.delivery === "pending"),
    navigationSpeechPrompt
  });

  useEffect(() => {
    if (voiceFeedbackState !== "navigation_guidance" || !navigationSpeechPrompt) {
      navigationSpeechTransitionRef.current = { key: "", pending: false };
      return;
    }
    if (speechRecognitionActive) return;
    if (typeof document !== "undefined" && document.visibilityState !== "visible") return;

    const now = Date.now();
    const key = navigationSpeechKey ?? navigationSpeechPrompt;
    if (navigationSpeechTransitionRef.current.key !== key) {
      navigationSpeechTransitionRef.current = { key, pending: true };
    }
    if (navigationSpeechRetryRef.current.key !== key) {
      navigationSpeechRetryGenerationRef.current += 1;
      navigationSpeechRetryRef.current = { key, count: 0 };
      navigationSpeechPendingKeyRef.current = null;
    }
    if (
      activeSpeechPriority !== null &&
      activeSpeechPriority !== "navigation" &&
      activeSpeechPriority !== "advisory"
    ) return;
    if (
      navigationSpeechPendingKeyRef.current === key ||
      (
        !navigationSpeechTransitionRef.current.pending &&
        !shouldAttemptNavigationSpeech(lastNavigationAlertRef.current, key, now, WALKSAFE_GUIDE_COOLDOWN_MS)
      )
    ) {
      return;
    }
    let failureHandled = false;
    const scheduleRetry = () => {
      if (failureHandled) return;
      failureHandled = true;
      if (navigationSpeechRetryRef.current.key !== key) return;
      navigationSpeechPendingKeyRef.current = key;
      if (lastNavigationAlertRef.current.key === key) {
        lastNavigationAlertRef.current = { key: "", time: 0 };
      }
      const nextRetryCount = navigationSpeechRetryRef.current.count + 1;
      const coolingDown = nextRetryCount >= NAVIGATION_SPEECH_MAX_RETRIES;
      navigationSpeechRetryRef.current = { key, count: coolingDown ? 0 : nextRetryCount };
      const retryGeneration = navigationSpeechRetryGenerationRef.current + 1;
      navigationSpeechRetryGenerationRef.current = retryGeneration;
      window.setTimeout(() => {
        if (
          navigationSpeechRetryRef.current.key !== key ||
          navigationSpeechRetryGenerationRef.current !== retryGeneration ||
          navigationSpeechPendingKeyRef.current !== key
        ) return;
        navigationSpeechPendingKeyRef.current = null;
        setNavigationSpeechRetrySequence((sequence) => sequence + 1);
      }, coolingDown ? SPEECH_COOLDOWN_MS : NAVIGATION_SPEECH_RETRY_MS);
    };
    navigationSpeechPendingKeyRef.current = key;
    const accepted = speak(navigationSpeechPrompt, "navigation", {
      onStart: () => {
        navigationSpeechRetryGenerationRef.current += 1;
        navigationSpeechPendingKeyRef.current = null;
        if (navigationSpeechTransitionRef.current.key === key) {
          navigationSpeechTransitionRef.current = { key, pending: false };
        }
        lastNavigationAlertRef.current = { key, time: Date.now() };
      },
      onEnd: () => {
        if (navigationSpeechRetryRef.current.key === key) {
          navigationSpeechRetryGenerationRef.current += 1;
          navigationSpeechRetryRef.current = { key, count: 0 };
        }
      },
      onFailure: scheduleRetry,
      onCancel: () => {
        if (navigationSpeechRetryRef.current.key !== key) return;
        navigationSpeechRetryGenerationRef.current += 1;
        navigationSpeechPendingKeyRef.current = null;
        navigationSpeechRetryRef.current = { key, count: 0 };
        if (lastNavigationAlertRef.current.key === key) {
          lastNavigationAlertRef.current = { key: "", time: 0 };
        }
      }
    });
    if (accepted) {
      return;
    }
    scheduleRetry();
  }, [activeSpeechPriority, navigationSpeechKey, navigationSpeechPrompt, navigationSpeechRetrySequence, speechRecognitionActive, voiceFeedbackState]);

  const getLastStatusMessage = useCallback(() => lastStatusMessageRef.current, []);

  const setLastStatusMessage = useCallback((message: string) => {
    lastStatusMessageRef.current = message;
  }, []);

  return {
    detectionRisk,
    v2PrimaryRisk,
    v2SecondaryRisk,
    v2PrimaryRiskContext,
    v2SecondaryRiskContext,
    activeV2RiskDetection: alertableV2Detection,
    activeV2Risk: alertableV2Risk,
    activeV2RiskContext: alertableV2RiskContext,
    riskActive,
    detectionLabel,
    riskText,
    nonMetricAdvisoryCapabilityLabel: nonMetricAdvisoryModeActive
      ? NON_METRIC_HAZARD_ADVISORY_CAPABILITY_LABEL
      : null,
    nonMetricAdvisoryActive: feedbackActivity.advisoryActive,
    nonMetricAdvisoryTier: activeNonMetricAdvisory?.tier ?? null,
    nonMetricAdvisoryDirection: activeNonMetricAdvisory?.direction ?? null,
    nonMetricAdvisoryMessage: activeNonMetricAdvisory?.message ?? null,
    nonMetricAdvisoryConsecutiveFrames: activeNonMetricAdvisory?.continuity.consecutiveFrames ?? null,
    nonMetricAdvisoryStableMs: activeNonMetricAdvisory?.continuity.stableMs ?? null,
    speechOutputStatus,
    detectionSafetyAlertMessage: detectionSafetyAlert.message,
    detectionSafetyFallbackRequired: detectionSafetyAlert.message !== null && detectionSafetyAlert.delivery !== "spoken",
    getLastStatusMessage,
    setLastStatusMessage
  };
}
