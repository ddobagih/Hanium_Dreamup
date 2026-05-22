"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { DetectionEvent } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";
import {
  DETECTOR_MODE,
  INITIAL_DETECTOR_MESSAGE,
  IS_FAKE_V2_MODE,
  IS_SERVER_V2_MODE,
  IS_V2_MODE
} from "./_walksafe/config";
import { useAutoReportV2 } from "./_walksafe/hooks/useAutoReportV2";
import { useCamera } from "./_walksafe/hooks/useCamera";
import { useDetectionV1 } from "./_walksafe/hooks/useDetectionV1";
import { useDetectionV2 } from "./_walksafe/hooks/useDetectionV2";
import { useManualReportV1, type ReportState } from "./_walksafe/hooks/useManualReportV1";
import { useRiskFeedback } from "./_walksafe/hooks/useRiskFeedback";
import { useSensors } from "./_walksafe/hooks/useSensors";
import { useVoiceCommands } from "./_walksafe/hooks/useVoiceCommands";
import { AssistPanel } from "./_walksafe/components/AssistPanel";
import { CameraSurface } from "./_walksafe/components/CameraSurface";

export default function Home() {
  const detectionIndexRef = useRef(0);
  const reportStateRef = useRef<ReportState>("idle");

  const [detection, setDetection] = useState<DetectionEvent | null>(null);
  const [v2Detections, setV2Detections] = useState<TwoModelDetection[]>([]);
  const [v2Primary, setV2Primary] = useState<TwoModelDetection | null>(null);
  const [v2Secondary, setV2Secondary] = useState<TwoModelDetection | null>(null);
  const [detectorMessage, setDetectorMessage] = useState(INITIAL_DETECTOR_MESSAGE);
  const [detectorBusy, setDetectorBusy] = useState(false);
  const [speechEnabled, setSpeechEnabled] = useState(true);
  const [reportState, setReportState] = useState<ReportState>("idle");
  const [reportMessage, setReportMessage] = useState("신고 대기 중");
  const [lastDuplicateCount, setLastDuplicateCount] = useState(0);
  const [voiceMessage, setVoiceMessage] = useState("음성 명령 대기");

  const clearCameraDependentState = useCallback(() => {
    setDetection(null);
    setV2Detections([]);
    setV2Primary(null);
    setV2Secondary(null);
    setReportMessage("카메라 준비 필요");
  }, []);

  const handleCameraError = useCallback(() => {
    clearCameraDependentState();
    setReportState("idle");
  }, [clearCameraDependentState]);

  const { videoRef, cameraReady, cameraError, startCamera, captureFrame } = useCamera({
    onCameraUnavailable: clearCameraDependentState,
    onCameraError: handleCameraError
  });
  const {
    gps,
    gpsError,
    heading,
    headingMessage,
    directionLabel,
    startGpsWatch,
    requestHeadingPermission,
    getCurrentLocationMessage
  } = useSensors();
  const {
    autoReportStatus,
    setAutoReportV2State,
    submitV2ReportTarget,
    handleVoiceReportV2
  } = useAutoReportV2({
    captureFrame,
    gps,
    heading,
    speechEnabled,
    v2Detections,
    isServerV2Mode: IS_SERVER_V2_MODE,
    setReportState,
    setReportMessage,
    setLastDuplicateCount,
    setVoiceMessage
  });
  useDetectionV1({
    cameraReady,
    gps,
    heading,
    captureFrame,
    reportStateRef,
    detectionIndexRef,
    setDetection,
    setV2Detections,
    setV2Primary,
    setV2Secondary,
    setDetectorMessage,
    setDetectorBusy,
    setReportState,
    setLastDuplicateCount,
    setReportMessage
  });
  useDetectionV2({
    cameraReady,
    gps,
    heading,
    captureFrame,
    detectionIndexRef,
    reportStateRef,
    submitV2ReportTarget,
    setAutoReportV2State,
    setDetection,
    setV2Detections,
    setV2Primary,
    setV2Secondary,
    setDetectorMessage,
    setDetectorBusy,
    setReportState,
    setLastDuplicateCount,
    setReportMessage
  });

  const {
    riskActive,
    detectionLabel,
    riskText,
    getLastStatusMessage,
    setLastStatusMessage
  } = useRiskFeedback({
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
  });
  const modeText = DETECTOR_MODE === "fake" ? "데모 탐지 모드" : DETECTOR_MODE === "server" ? "서버 탐지 모드" : IS_FAKE_V2_MODE ? "two-model 데모 모드" : IS_SERVER_V2_MODE ? "two-model 서버 모드" : "모델 연결 대기";
  const detectorStatusText =
    DETECTOR_MODE === "server" && detectorBusy && !detection
      ? "서버 탐지 중"
      : IS_SERVER_V2_MODE && detectorBusy && !v2Primary
        ? "two-model 서버 탐지 중"
        : detectorMessage;
  const {
    canReport,
    reportDisabledReason,
    reportHelpText,
    handleReport
  } = useManualReportV1({
    detection,
    cameraReady,
    gps,
    heading,
    captureFrame,
    speechEnabled,
    isV2Mode: IS_V2_MODE,
    reportState,
    reportMessage,
    setReportState,
    setReportMessage,
    setLastDuplicateCount
  });

  const reconnectCameraAndSensors = useCallback(() => {
    void startCamera();
    startGpsWatch();
    void requestHeadingPermission();
  }, [requestHeadingPermission, startCamera, startGpsWatch]);

  useEffect(() => {
    if ("serviceWorker" in navigator) {
      navigator.serviceWorker.register("/sw.js").catch(() => undefined);
    }
  }, []);

  useEffect(() => {
    reportStateRef.current = reportState;
  }, [reportState]);

  const isReportSending = useCallback(() => reportStateRef.current === "sending", []);

  const onCreateReport = useCallback(async () => {
    if (IS_V2_MODE) {
      await handleVoiceReportV2();
      return;
    }

    setVoiceMessage("음성 명령: 현재 위험 신고");
    await handleReport();
  }, [handleReport, handleVoiceReportV2]);

  const {
    voiceSupported,
    voiceState,
    voiceResultText,
    voiceButtonLabel,
    voiceButtonHelp,
    handleSpeechToggle,
    handleVoiceCommandButton
  } = useVoiceCommands({
    speechEnabled,
    setSpeechEnabled,
    setVoiceMessage,
    isReportSending,
    onCreateReport,
    getLastStatusMessage,
    setLastStatusMessage,
    getCurrentLocationMessage,
    hasGps: Boolean(gps)
  });

  const reportButtonLabel = useMemo(() => {
    if (IS_V2_MODE) {
      if (autoReportStatus === "sending") {
        return "자동 신고 중";
      }
      if (autoReportStatus === "sent") {
        return "자동 신고 완료";
      }
      if (autoReportStatus === "failed") {
        return "자동 신고 실패";
      }
      return "자동 신고 모드";
    }
    if (reportState === "sending") {
      return "전송 중";
    }
    if (reportState === "error") {
      return "다시 신고";
    }
    return "현재 위험 신고";
  }, [autoReportStatus, reportState]);

  return (
    <main className="assist-shell">
      <CameraSurface
        videoRef={videoRef}
        cameraReady={cameraReady}
        cameraError={cameraError}
        reconnectCameraAndSensors={reconnectCameraAndSensors}
        detection={detection}
        v2Detections={v2Detections}
        v2Primary={v2Primary}
        riskActive={riskActive}
        modeText={modeText}
        riskText={riskText}
        detectorStatusText={detectorStatusText}
      />

      <AssistPanel
        detection={detection}
        v2Detections={v2Detections}
        v2Primary={v2Primary}
        v2Secondary={v2Secondary}
        riskActive={riskActive}
        isV2Mode={IS_V2_MODE}
        detectionLabel={detectionLabel}
        detectorStatusText={detectorStatusText}
        gps={gps}
        gpsError={gpsError}
        directionLabel={directionLabel}
        heading={heading}
        headingMessage={headingMessage}
        reportMessage={reportMessage}
        lastDuplicateCount={lastDuplicateCount}
        voiceMessage={voiceMessage}
        voiceResultText={voiceResultText}
        speechEnabled={speechEnabled}
        handleSpeechToggle={handleSpeechToggle}
        voiceState={voiceState}
        voiceSupported={voiceSupported}
        voiceButtonLabel={voiceButtonLabel}
        voiceButtonHelp={voiceButtonHelp}
        handleVoiceCommandButton={handleVoiceCommandButton}
        handleReport={handleReport}
        canReport={canReport}
        reportDisabledReason={reportDisabledReason}
        reportState={reportState}
        reportButtonLabel={reportButtonLabel}
        reportHelpText={reportHelpText}
        reconnectCameraAndSensors={reconnectCameraAndSensors}
        modeText={modeText}
      />
    </main>
  );
}
