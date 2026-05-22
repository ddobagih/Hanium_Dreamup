"use client";

import { useCallback, useEffect, useMemo, useRef } from "react";
import { labelForTwoModelDetection } from "@/lib/detector-v2";
import { CLASS_LABELS, type DetectionEvent, type GpsFix } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";
import { SPEECH_COOLDOWN_MS } from "../config";
import { alertForDetection, alertForTwoModelDetection, speak, vibrate } from "../feedback";
import { evaluateDetectionRisk, evaluateTwoModelDetectionRisk } from "../risk-evaluator";
import { formatPercent } from "../utils";
import type { ReportState } from "./useManualReportV1";

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
};

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
  getCurrentLocationMessage
}: UseRiskFeedbackOptions) {
  const lastAlertRef = useRef<{ key: string; time: number }>({ key: "", time: 0 });
  const lastStatusMessageRef = useRef<string | null>(null);

  const detectionRisk = useMemo(() => (detection ? evaluateDetectionRisk(detection) : null), [detection]);
  const v2PrimaryRisk = useMemo(() => (v2Primary ? evaluateTwoModelDetectionRisk(v2Primary) : null), [v2Primary]);
  const riskActive = Boolean(detectionRisk?.alertable || v2PrimaryRisk?.alertable);
  const detectionLabel = detection ? CLASS_LABELS[detection.class_name] : v2Primary ? labelForTwoModelDetection(v2Primary) : "탐지 대기";
  const riskText = detection && detectionRisk?.alertable
    ? `${detectionLabel} ${formatPercent(detection.confidence)}`
    : v2Primary && v2PrimaryRisk?.alertable
      ? `${v2PrimaryRisk.recommended_message ?? detectionLabel} ${formatPercent(v2Primary.confidence)}`
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

    const risk = evaluateTwoModelDetectionRisk(v2Primary);
    const primaryLabel = labelForTwoModelDetection(v2Primary);
    const secondaryText = v2Secondary ? ` 보조 정보. ${labelForTwoModelDetection(v2Secondary)}.` : "";
    if (!risk.alertable) {
      lastStatusMessageRef.current = risk.reportable
        ? `자동 신고 대상 감지. ${primaryLabel}.`
        : `탐지 상태. ${primaryLabel}.${secondaryText}`;
      return;
    }

    lastStatusMessageRef.current = `현재 위험. ${primaryLabel}. 신뢰도 ${formatPercent(v2Primary.confidence)}.${secondaryText}`;
    const now = Date.now();
    const key = `${v2Primary.model_key}:${v2Primary.class_name}`;
    if (lastAlertRef.current.key === key && now - lastAlertRef.current.time < SPEECH_COOLDOWN_MS) {
      return;
    }

    lastAlertRef.current = { key, time: now };
    const alert = alertForTwoModelDetection(v2Primary, speechEnabled);
    vibrate(alert.vibration);
    if (speechEnabled) {
      speak(risk.recommended_message ?? alert.speech);
    }
  }, [speechEnabled, v2Primary, v2Secondary]);

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

  const getLastStatusMessage = useCallback(() => lastStatusMessageRef.current, []);

  const setLastStatusMessage = useCallback((message: string) => {
    lastStatusMessageRef.current = message;
  }, []);

  return {
    detectionRisk,
    v2PrimaryRisk,
    riskActive,
    detectionLabel,
    riskText,
    getLastStatusMessage,
    setLastStatusMessage
  };
}
