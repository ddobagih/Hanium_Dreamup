"use client";

import { useCallback, useEffect, useMemo, useRef } from "react";
import { labelForTwoModelDetection } from "@/lib/detector-v2";
import { CLASS_LABELS, type DetectionEvent, type GpsFix } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";
import { SPEECH_COOLDOWN_MS, WALKSAFE_GUIDE_COOLDOWN_MS } from "../config";
import { resolveVoiceFeedbackState, shouldApplyNavigationStatusMessage } from "../voice-priority";
import { alertForDetection, alertForTwoModelDetection, speak, vibrate } from "../feedback";
import { buildRiskGuidanceMessage, selectRiskGuidanceCandidate } from "../risk-guidance";
import { evaluateDetectionRisk, evaluateTwoModelDetectionRisk } from "../risk-evaluator";
import type { RiskDecision, RiskEvaluationContext } from "../risk-evaluator";
import { formatPercent } from "../utils";
import type { ReportState } from "./useManualReportV1";
import { useTwoModelRiskHistory } from "./useTwoModelRiskHistory";

type UseRiskFeedbackOptions = {
  detection: DetectionEvent | null;
  v2Primary: TwoModelDetection | null;
  v2Secondary: TwoModelDetection | null;
  detectorMessage: string;
  reportState: ReportState;
  reportMessage: string;
  gps: GpsFix | null;
  gpsError: string | null;
  speechEnabled: boolean;
  getCurrentLocationMessage: () => string;
  stepLengthM?: number | null;
  navigationSpeechPrompt?: string | null;
  navigationSpeechKey?: string | null;
  navigationStatusMessage?: string | null;
};

function distanceForRiskContext(context: RiskEvaluationContext): number | null {
  // 실제 depth/외부 센서 값은 아직 연결되지 않았다.
  // bbox 크기 등으로 가짜 거리를 만들지 않고, 명시적으로 전달된 거리만 안내에 사용한다.
  return context.depth?.distance_m ?? null;
}

function messageForV2Risk(
  detectionValue: TwoModelDetection,
  risk: RiskDecision,
  context: RiskEvaluationContext,
  fallbackLabel: string,
  stepLengthM?: number | null
): string | null {
  return buildRiskGuidanceMessage({
    riskType: risk.risk_type,
    riskLevel: risk.risk_level,
    bbox: detectionValue.bbox,
    distanceM: distanceForRiskContext(context),
    stepLengthM,
    label: fallbackLabel,
    fallback: risk.recommended_message
  });
}

export function useRiskFeedback({
  detection,
  v2Primary,
  v2Secondary,
  detectorMessage,
  reportState,
  reportMessage,
  gps,
  gpsError,
  speechEnabled,
  getCurrentLocationMessage,
  stepLengthM = null,
  navigationSpeechPrompt = null,
  navigationSpeechKey = null,
  navigationStatusMessage = null
}: UseRiskFeedbackOptions) {
  const lastAlertRef = useRef<{ key: string; time: number }>({ key: "", time: 0 });
  const lastNavigationAlertRef = useRef<{ key: string; time: number }>({ key: "", time: 0 });
  const lastStatusMessageRef = useRef<string | null>(null);
  const v2PrimaryRiskContext = useTwoModelRiskHistory(v2Primary);
  const v2SecondaryRiskContext = useTwoModelRiskHistory(v2Secondary);

  const detectionRisk = useMemo(() => (detection ? evaluateDetectionRisk(detection) : null), [detection]);
  const v2PrimaryRisk = useMemo(
    () => (v2Primary ? evaluateTwoModelDetectionRisk(v2Primary, v2PrimaryRiskContext) : null),
    [v2Primary, v2PrimaryRiskContext]
  );
  const v2SecondaryRisk = useMemo(
    () => (v2Secondary ? evaluateTwoModelDetectionRisk(v2Secondary, v2SecondaryRiskContext) : null),
    [v2Secondary, v2SecondaryRiskContext]
  );
  const alertableV2Candidate = useMemo(
    () =>
      selectRiskGuidanceCandidate(
        [
          v2Primary
            ? {
                item: v2Primary,
                risk: v2PrimaryRisk,
                context: v2PrimaryRiskContext,
                confidence: v2Primary.confidence,
                index: 0
              }
            : null,
          v2Secondary
            ? {
                item: v2Secondary,
                risk: v2SecondaryRisk,
                context: v2SecondaryRiskContext,
                confidence: v2Secondary.confidence,
                index: 1
              }
            : null
        ].filter((candidate): candidate is NonNullable<typeof candidate> => candidate !== null)
      ),
    [v2Primary, v2PrimaryRisk, v2PrimaryRiskContext, v2Secondary, v2SecondaryRisk, v2SecondaryRiskContext]
  );
  const alertableV2Detection = alertableV2Candidate?.item ?? null;
  const alertableV2Risk = alertableV2Candidate?.risk ?? null;
  const alertableV2RiskContext = alertableV2Candidate?.context ?? {};
  const riskActive = Boolean(detectionRisk?.alertable || alertableV2Risk?.alertable);
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
      : "위험 요소 없음";

  useEffect(() => {
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
    const now = Date.now();
    const key = detection.class_name;
    if (lastAlertRef.current.key === key && now - lastAlertRef.current.time < SPEECH_COOLDOWN_MS) {
      return;
    }

    lastAlertRef.current = { key, time: now };
    const alert = alertForDetection(detection, speechEnabled);
    vibrate(alert.vibration);
    if (speechEnabled) {
      speak(risk.recommended_message ?? alert.speech);
    }
  }, [detection, speechEnabled]);

  useEffect(() => {
    if (!v2Primary) {
      return;
    }

    if (!v2PrimaryRisk) {
      return;
    }

    const primaryLabel = labelForTwoModelDetection(v2Primary);
    const secondaryText = v2Secondary ? ` 보조 정보. ${labelForTwoModelDetection(v2Secondary)}.` : "";
    const activeRiskDetection = alertableV2Candidate?.item ?? null;
    const activeRisk = alertableV2Candidate?.risk ?? null;

    if (!activeRiskDetection || !activeRisk) {
      lastStatusMessageRef.current = v2PrimaryRisk.reportable
        ? `자동 신고 대상 감지. ${primaryLabel}.`
        : `탐지 상태. ${primaryLabel}.${secondaryText}`;
      return;
    }

    const activeLabel = labelForTwoModelDetection(activeRiskDetection);
    const activeRiskContext = alertableV2Candidate?.context ?? {};
    const activeRiskMessage = messageForV2Risk(activeRiskDetection, activeRisk, activeRiskContext, activeLabel, stepLengthM);
    lastStatusMessageRef.current = `현재 위험. ${activeLabel}. 신뢰도 ${formatPercent(activeRiskDetection.confidence)}.${secondaryText}`;
    const now = Date.now();
    const key = `${activeRiskDetection.model_key}:${activeRiskDetection.class_name}`;
    if (lastAlertRef.current.key === key && now - lastAlertRef.current.time < SPEECH_COOLDOWN_MS) {
      return;
    }

    lastAlertRef.current = { key, time: now };
    const alert = alertForTwoModelDetection(activeRiskDetection, speechEnabled);
    vibrate(alert.vibration);
    if (speechEnabled) {
      speak(activeRiskMessage ?? alert.speech);
    }
  }, [alertableV2Candidate, speechEnabled, stepLengthM, v2Primary, v2PrimaryRisk, v2Secondary]);

  useEffect(() => {
    if (!detection) {
      lastStatusMessageRef.current = `탐지 상태. ${detectorMessage}.`;
    }
  }, [detection, detectorMessage]);

  useEffect(() => {
    lastStatusMessageRef.current = `신고 상태. ${reportMessage}.`;
  }, [reportMessage, reportState]);

  useEffect(() => {
    if (gps || gpsError) {
      lastStatusMessageRef.current = getCurrentLocationMessage();
    }
  }, [getCurrentLocationMessage, gps, gpsError]);

  useEffect(() => {
    if (shouldApplyNavigationStatusMessage(navigationStatusMessage, riskActive)) {
      lastStatusMessageRef.current = navigationStatusMessage;
    }
  }, [navigationStatusMessage, riskActive]);

  const voiceFeedbackState = resolveVoiceFeedbackState({
    speechEnabled,
    riskActive,
    navigationSpeechPrompt
  });

  useEffect(() => {
    if (voiceFeedbackState !== "navigation_guidance" || !navigationSpeechPrompt) {
      return;
    }

    const now = Date.now();
    const key = navigationSpeechKey ?? navigationSpeechPrompt;
    if (lastNavigationAlertRef.current.key === key && now - lastNavigationAlertRef.current.time < WALKSAFE_GUIDE_COOLDOWN_MS) {
      return;
    }

    lastNavigationAlertRef.current = { key, time: now };
    speak(navigationSpeechPrompt);
  }, [navigationSpeechKey, navigationSpeechPrompt, voiceFeedbackState]);

  const getLastStatusMessage = useCallback(() => lastStatusMessageRef.current, []);

  const setLastStatusMessage = useCallback((message: string) => {
    lastStatusMessageRef.current = message;
  }, []);

  return {
    detectionRisk,
    v2PrimaryRisk,
    v2SecondaryRisk,
    riskActive,
    detectionLabel,
    riskText,
    getLastStatusMessage,
    setLastStatusMessage
  };
}
