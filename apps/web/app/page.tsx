"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { DetectionEvent } from "@/types/inference";
import type { DetectV2RequestAudit, TwoModelDetection } from "@/types/inference-v2";
import type { RiskEvaluationContext } from "./_walksafe/risk-evaluator";
import {
  DETECTOR_MODE,
  INITIAL_DETECTOR_MESSAGE,
  IS_CAMERA_ONLY_MODE,
  IS_FAKE_V2_MODE,
  IS_SERVER_V2_MODE,
  IS_V2_MODE
} from "./_walksafe/config";
import { useAutoReportV2 } from "./_walksafe/hooks/useAutoReportV2";
import { useAutoStepLength } from "./_walksafe/hooks/useAutoStepLength";
import { useCamera } from "./_walksafe/hooks/useCamera";
import { useDetectionV1 } from "./_walksafe/hooks/useDetectionV1";
import { useDetectionV2 } from "./_walksafe/hooks/useDetectionV2";
import { useDepthSensor } from "./_walksafe/hooks/useDepthSensor";
import { useManualReportV1, type ReportState } from "./_walksafe/hooks/useManualReportV1";
import { useNavigationGuidance } from "./_walksafe/hooks/useNavigationGuidance";
import { usePwaStatus } from "./_walksafe/hooks/usePwaStatus";
import { useRiskFeedback } from "./_walksafe/hooks/useRiskFeedback";
import { useSensors } from "./_walksafe/hooks/useSensors";
import { useVoiceCommands } from "./_walksafe/hooks/useVoiceCommands";
import { useWalkSafeSettings } from "./_walksafe/hooks/useWalkSafeSettings";
import { AssistPanel } from "./_walksafe/components/AssistPanel";
import { CameraSurface } from "./_walksafe/components/CameraSurface";
import { TestCapturePanel } from "./_walksafe/components/TestCapturePanel";
import { phraseForApproxSteps } from "./_walksafe/risk-guidance";
import { formatPercent } from "./_walksafe/utils";

const TEST_CAPTURE_PANEL_ENABLED = process.env.NEXT_PUBLIC_WALKSAFE_TEST_CAPTURE_PANEL === "true";
const TEST_CAPTURE_PANEL_CONSENT_STORAGE_KEY = "walksafe-test-capture-consent";

function depthDistanceText(distanceM: number | null | undefined, stepLengthM: number | null | undefined): string | null {
  if (typeof distanceM !== "number" || !Number.isFinite(distanceM)) {
    return null;
  }

  const stepText = phraseForApproxSteps(distanceM, stepLengthM);
  return stepText ? `${distanceM.toFixed(1)}m · ${stepText}` : `${distanceM.toFixed(1)}m`;
}

function depthStatusFromContext(
  context: RiskEvaluationContext,
  sensorMessage: string,
  stepLengthM: number | null,
  motionStability: number
): { status: string; detail: string } {
  if (context.depth?.source === "sensor_depth" && typeof context.depth.distance_m === "number") {
    return {
      status: `WebXR 깊이 ${depthDistanceText(context.depth.distance_m, stepLengthM)}`,
      detail: `WebXR depth · Android native ARCore는 앱 경로에서 별도 처리 · 신뢰도 ${formatPercent(context.depth.confidence ?? 0)} · 흔들림 안정도 ${formatPercent(motionStability)}`
    };
  }

  const tracking = context.tracking;
  if (tracking?.distance_source === "model_estimate" && typeof tracking.distance_m === "number") {
    const approachText =
      tracking.approach_state === "approaching"
        ? "가까워지는 중"
        : tracking.approach_state === "receding"
          ? "멀어지는 중"
          : tracking.approach_state === "stable"
            ? "거리 안정"
            : "접근 추정 중";
    return {
      status: approachText,
      detail: `폴리곤/bbox 변화 기반 추세 · 신뢰도 ${formatPercent(tracking.distance_confidence ?? 0)} · 실제 depth가 아니므로 보폭 거리 안내는 보류`
    };
  }

  return {
    status: sensorMessage,
    detail: `실제 depth가 없으면 폴리곤/bbox 변화로 접근만 보조 추정합니다. 흔들림 안정도 ${formatPercent(motionStability)}`
  };
}

export default function Home() {
  const detectionIndexRef = useRef(0);
  const reportStateRef = useRef<ReportState>("idle");
  const [testCaptureConsent, setTestCaptureConsent] = useState(() => {
    if (!TEST_CAPTURE_PANEL_ENABLED || typeof window === "undefined") {
      return false;
    }
    return window.localStorage.getItem(TEST_CAPTURE_PANEL_CONSENT_STORAGE_KEY) === "true";
  });

  const [detection, setDetection] = useState<DetectionEvent | null>(null);
  const [v2Detections, setV2Detections] = useState<TwoModelDetection[]>([]);
  const [v2Primary, setV2Primary] = useState<TwoModelDetection | null>(null);
  const [v2Secondary, setV2Secondary] = useState<TwoModelDetection | null>(null);
  const [detectionV2Audit, setDetectionV2Audit] = useState<DetectV2RequestAudit | null>(null);
  const [detectorMessage, setDetectorMessage] = useState(INITIAL_DETECTOR_MESSAGE);
  const [detectorBusy, setDetectorBusy] = useState(false);
  const [speechEnabled, setSpeechEnabled] = useState(true);
  const [reportState, setReportState] = useState<ReportState>("idle");
  const [reportMessage, setReportMessage] = useState("신고 대기 중");
  const [lastDuplicateCount, setLastDuplicateCount] = useState(0);
  const [voiceMessage, setVoiceMessage] = useState("음성 명령 대기");
  const [permissionRequestMessage, setPermissionRequestMessage] = useState<string | null>(null);
  const {
    settingsForm,
    settingsMessage,
    settingsExpanded,
    guardianSummary,
    setupChecklist,
    updateEmergencyContactName,
    updateEmergencyContactPhone,
    addEmergencyContact,
    removeEmergencyContact,
    saveSettings,
    clearSettings,
    toggleSettingsExpanded
  } = useWalkSafeSettings();
  const {
    isOnline,
    installMessage: pwaInstallMessage,
    updateMessage: pwaUpdateMessage,
    swVersion,
    canInstallPwa,
    canApplyServiceWorkerUpdate,
    installApp,
    applyServiceWorkerUpdate
  } = usePwaStatus();

  const clearCameraDependentState = useCallback(() => {
    setDetection(null);
    setV2Detections([]);
    setV2Primary(null);
    setV2Secondary(null);
    setDetectionV2Audit(null);
    setReportMessage("카메라 준비 필요");
  }, []);

  const enableTestCaptureConsent = useCallback(() => {
    setTestCaptureConsent(true);
    window.localStorage.setItem(TEST_CAPTURE_PANEL_CONSENT_STORAGE_KEY, "true");
  }, []);

  const handleCameraError = useCallback(() => {
    clearCameraDependentState();
    setReportState("idle");
  }, [clearCameraDependentState]);

  const {
    videoRef,
    cameraReady,
    cameraError,
    cameraPermissionState,
    refreshCameraPermissionState,
    startCamera,
    captureFrame
  } = useCamera({
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
  const stepLengthEstimate = useAutoStepLength(gps);
  const depthSensor = useDepthSensor({ motionStability: stepLengthEstimate.motionStability });
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
    estimateDepthForDetection: depthSensor.estimateDepthForDetection,
    detectionIndexRef,
    reportStateRef,
    submitV2ReportTarget,
    setAutoReportV2State,
    onDetectionV2Audit: setDetectionV2Audit,
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
    navigationActive,
    navigationStatusText,
    navigationInstructionText,
    navigationDetailText,
    navigationSpeechPrompt,
    navigationSpeechKey,
    navigationStatusMessage,
    navigationDestinationCandidates,
    navigationHiddenDestinationCandidateCount,
    navigationCanShowMoreDestinations,
    navigationSearchActive,
    hasNavigationDestination,
    setVoiceDestination,
    selectDestinationCandidate,
    selectDestinationCandidateByIndex,
    showMoreDestinationCandidates,
    cancelDestinationSearch,
    retryDestinationSearch,
    startNavigation,
    stopNavigation
  } = useNavigationGuidance({
    gps,
    heading,
    v2Detections,
    stepLengthM: stepLengthEstimate.stepLengthM
  });

  const {
    v2PrimaryRiskContext,
    v2SecondaryRiskContext,
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
    heading,
    walkingSpeedMps: stepLengthEstimate.walkingSpeedMps,
    motionStability: stepLengthEstimate.motionStability,
    speechEnabled,
    getCurrentLocationMessage,
    stepLengthM: stepLengthEstimate.stepLengthM,
    navigationSpeechPrompt,
    navigationSpeechKey,
    navigationStatusMessage
  });
  const modeText = IS_CAMERA_ONLY_MODE ? "실기기 카메라 테스트" : DETECTOR_MODE === "fake" ? "데모 탐지 모드" : DETECTOR_MODE === "server" ? "서버 탐지 모드" : IS_FAKE_V2_MODE ? "unified-v2 데모 모드" : IS_SERVER_V2_MODE ? "unified-v2 서버 모드" : "모델 연결 대기";
  const detectorStatusText =
    DETECTOR_MODE === "server" && detectorBusy && !detection
      ? "서버 탐지 중"
      : IS_SERVER_V2_MODE && detectorBusy && !v2Primary
        ? "unified-v2 서버 탐지 중"
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
    const nextMessage = "버튼 입력 확인됨 · 카메라와 GPS 권한을 요청합니다. 브라우저 팝업이 뜨면 허용을 눌러주세요.";
    setPermissionRequestMessage(nextMessage);
    setVoiceMessage(nextMessage);

    void refreshCameraPermissionState();
    const cameraPermission = startCamera();
    startGpsWatch();

    void cameraPermission.then((cameraReady) => {
      setPermissionRequestMessage(
        cameraReady
          ? "카메라 연결 성공 · GPS/방향 센서 상태를 확인 중입니다."
          : "카메라 연결 실패 · 위 안내 문구에 따라 브라우저 사이트 설정을 확인해 주세요."
      );
    });

    void requestHeadingPermission();
    void stepLengthEstimate.requestMotionPermission();
  }, [refreshCameraPermissionState, requestHeadingPermission, startCamera, startGpsWatch, stepLengthEstimate]);

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
    onSetDestination: setVoiceDestination,
    onSelectDestinationCandidateByIndex: selectDestinationCandidateByIndex,
    onStartNavigation: startNavigation,
    onStopNavigation: stopNavigation,
    getLastStatusMessage,
    setLastStatusMessage,
    getCurrentLocationMessage,
    hasGps: Boolean(gps),
    hasNavigationDestination
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

  const motionPermissionLabel = useMemo(() => {
    switch (stepLengthEstimate.motionPermissionStatus) {
      case "granted":
        return "허용됨";
      case "listening":
        return "수집 중";
      case "denied":
        return "거부됨";
      case "unsupported":
        return "미지원";
      default:
        return "요청 필요";
    }
  }, [stepLengthEstimate.motionPermissionStatus]);

  const stepLengthDetail = useMemo(() => {
    const speedText =
      stepLengthEstimate.walkingSpeedMps === null ? "속도 대기" : `속도 ${stepLengthEstimate.walkingSpeedMps.toFixed(1)}m/s`;
    return `GPS ${stepLengthEstimate.gpsSampleCount}개 · 유효 구간 ${stepLengthEstimate.validSegmentCount}개 · GPS 튐 ${stepLengthEstimate.ignoredSegmentCount}개 제외 · ${speedText} · 흔들림 ${formatPercent(stepLengthEstimate.shakeScore)}`;
  }, [
    stepLengthEstimate.gpsSampleCount,
    stepLengthEstimate.ignoredSegmentCount,
    stepLengthEstimate.shakeScore,
    stepLengthEstimate.validSegmentCount,
    stepLengthEstimate.walkingSpeedMps
  ]);
  const depthStatus = useMemo(
    () =>
      depthStatusFromContext(
        v2PrimaryRiskContext.depth || v2PrimaryRiskContext.tracking ? v2PrimaryRiskContext : v2SecondaryRiskContext,
        IS_SERVER_V2_MODE || IS_CAMERA_ONLY_MODE
          ? "실기기 테스트 중 · WebXR 깊이는 비활성화"
          : depthSensor.message,
        stepLengthEstimate.stepLengthM,
        stepLengthEstimate.motionStability
      ),
    [
      depthSensor.message,
      stepLengthEstimate.motionStability,
      stepLengthEstimate.stepLengthM,
      v2PrimaryRiskContext,
      v2SecondaryRiskContext
    ]
  );

  return (
    <main className={`assist-shell ${isOnline ? "online" : "offline"}`}>
      <a className="skip-link" href="#assist-status">
        보행 상태로 건너뛰기
      </a>
      {!isOnline ? (
        <div className="offline-banner offline" role="status" aria-live="polite">
          오프라인 상태 · 탐지/신고/길안내 API 요청을 보내지 않습니다.
        </div>
      ) : null}

      <CameraSurface
        videoRef={videoRef}
        cameraReady={cameraReady}
        cameraError={cameraError}
        cameraPermissionState={cameraPermissionState}
        reconnectCameraAndSensors={reconnectCameraAndSensors}
        permissionRequestMessage={permissionRequestMessage}
        detection={detection}
        v2Detections={v2Detections}
        v2Primary={v2Primary}
        riskActive={riskActive}
        modeText={modeText}
        isFakeV2Mode={IS_FAKE_V2_MODE}
        riskText={riskText}
        detectorStatusText={detectorStatusText}
      />

      <AssistPanel
        panelId="assist-status"
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
        navigationActive={navigationActive}
        navigationStatusText={navigationStatusText}
        navigationInstructionText={navigationInstructionText}
        navigationDetailText={navigationDetailText}
        navigationDestinationCandidates={navigationDestinationCandidates}
        navigationHiddenDestinationCandidateCount={navigationHiddenDestinationCandidateCount}
        navigationCanShowMoreDestinations={navigationCanShowMoreDestinations}
        navigationSearchActive={navigationSearchActive}
        onSelectNavigationCandidate={selectDestinationCandidate}
        onShowMoreNavigationCandidates={showMoreDestinationCandidates}
        onCancelNavigationSearch={cancelDestinationSearch}
        onRetryNavigationSearch={() => void retryDestinationSearch()}
        onStopNavigation={stopNavigation}
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
        permissionRequestMessage={permissionRequestMessage}
        guardianSummary={guardianSummary}
        settingsMessage={settingsMessage}
        settingsExpanded={settingsExpanded}
        setupChecklist={setupChecklist}
        settingsForm={settingsForm}
        stepLengthSummary={stepLengthEstimate.message}
        stepLengthDetail={stepLengthDetail}
        motionPermissionLabel={motionPermissionLabel}
        motionSampleCount={stepLengthEstimate.motionSampleCount}
        stepLengthConfidence={stepLengthEstimate.confidence}
        storedStepCalibrationActive={stepLengthEstimate.storedCalibrationActive}
        depthStatusText={depthStatus.status}
        depthDetailText={depthStatus.detail}
        depthSensorStatus={depthSensor.status}
        depthFrameCount={depthSensor.frameCount}
        disableDepthSensorControls={IS_SERVER_V2_MODE || IS_CAMERA_ONLY_MODE}
        isOnline={isOnline}
        pwaInstallMessage={pwaInstallMessage}
        pwaUpdateMessage={pwaUpdateMessage}
        swVersion={swVersion}
        canInstallPwa={canInstallPwa}
        canApplyPwaUpdate={canApplyServiceWorkerUpdate}
        onEmergencyContactNameChange={updateEmergencyContactName}
        onEmergencyContactPhoneChange={updateEmergencyContactPhone}
        onAddEmergencyContact={addEmergencyContact}
        onRemoveEmergencyContact={removeEmergencyContact}
        onRequestMotionPermission={() => void stepLengthEstimate.requestMotionPermission()}
        onResetStepLengthCalibration={stepLengthEstimate.resetCalibration}
        onRequestDepthSensor={() => void depthSensor.startDepthSensor()}
        onStopDepthSensor={() => void depthSensor.stopDepthSensor()}
        onInstallPwa={() => void installApp()}
        onApplyPwaUpdate={applyServiceWorkerUpdate}
        onSaveSettings={saveSettings}
        onClearSettings={clearSettings}
        onToggleSettingsExpanded={toggleSettingsExpanded}
      />

      {TEST_CAPTURE_PANEL_ENABLED && testCaptureConsent ? (
        <TestCapturePanel
          cameraReady={cameraReady}
          captureFrame={captureFrame}
          detection={detection}
          v2Detections={v2Detections}
          v2Primary={v2Primary}
          detectorStatusText={detectorStatusText}
          riskText={riskText}
          depthStatusText={depthStatus.status}
          depthDetailText={depthStatus.detail}
          gps={gps}
          heading={heading}
          stepLengthDetail={stepLengthDetail}
          detectionV2Audit={detectionV2Audit}
        />
      ) : TEST_CAPTURE_PANEL_ENABLED ? (
        <section className="test-capture-panel" aria-label="실기기 테스트 기록 동의">
          <p>실기기 테스트 로그 저장을 사용하려면 동의가 필요합니다.</p>
          <p>이 패널은 이미지/메타데이터를 저장하므로 기본 비활성 상태입니다.</p>
          <button type="button" onClick={enableTestCaptureConsent}>
            동의 후 활성화
          </button>
        </section>
      ) : null}

    </main>
  );
}
