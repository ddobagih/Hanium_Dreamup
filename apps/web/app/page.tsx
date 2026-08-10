"use client";

/**
 * Composes the user-facing camera, detection, risk, navigation, voice and report flows.
 * Detector modes are mutually exclusive; hooks for inactive modes must not publish or persist results.
 */
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
  IS_V2_MODE,
  WALKSAFE_GUIDANCE_MAX_GPS_ACCURACY_M
} from "./_walksafe/config";
import { useAutoReportV2 } from "./_walksafe/hooks/useAutoReportV2";
import { useAutoStepLength } from "./_walksafe/hooks/useAutoStepLength";
import { useCamera } from "./_walksafe/hooks/useCamera";
import { useDetectionV1 } from "./_walksafe/hooks/useDetectionV1";
import { useDetectionV2 } from "./_walksafe/hooks/useDetectionV2";
import { useDepthSensor } from "./_walksafe/hooks/useDepthSensor";
import {
  hasStoredFieldTelemetryConsent,
  storeFieldTelemetryConsent,
  useFieldTestTelemetry,
  withdrawFieldTelemetryConsent
} from "./_walksafe/hooks/useFieldTestTelemetry";
import { useManualReportV1, type ReportState } from "./_walksafe/hooks/useManualReportV1";
import { useNavigationGuidance } from "./_walksafe/hooks/useNavigationGuidance";
import { usePwaStatus, useScreenWakeLock } from "./_walksafe/hooks/usePwaStatus";
import { useRiskFeedback } from "./_walksafe/hooks/useRiskFeedback";
import { useSensors } from "./_walksafe/hooks/useSensors";
import { useVoiceCommands } from "./_walksafe/hooks/useVoiceCommands";
import { useWalkSafeSettings } from "./_walksafe/hooks/useWalkSafeSettings";
import { AssistPanel } from "./_walksafe/components/AssistPanel";
import { CameraSurface } from "./_walksafe/components/CameraSurface";
import { TestCapturePanel } from "./_walksafe/components/TestCapturePanel";
import { GatewaySessionGate } from "./_walksafe/components/GatewaySessionGate";
import { useGatewaySession } from "./_walksafe/hooks/useGatewaySession";
import { phraseForApproxSteps } from "./_walksafe/risk-guidance";
import type { NonMetricAdvisoryFrameObservation } from "./_walksafe/nonmetric-hazard-advisory";
import { formatPercent } from "./_walksafe/utils";
import type { DetectionAvailability } from "./_walksafe/detection-availability";
import { transitionAssistSessionLifecycle, type AssistSessionLifecycle } from "./_walksafe/assist-session-lifecycle";
import {
  allowServerV2FrameProcessing,
  allowServerV2ReportStorage,
  createServerV2PrivacyConsent,
  isServerV2FrameProcessingAllowed,
  isServerV2ReportStorageAllowed,
  withdrawServerV2FrameProcessing,
  withdrawServerV2ReportStorage,
  type ServerV2PrivacyConsent
} from "./_walksafe/server-v2-privacy";

const TEST_CAPTURE_PANEL_ENABLED = process.env.NEXT_PUBLIC_WALKSAFE_TEST_CAPTURE_PANEL === "true";
const TEST_CAPTURE_PANEL_CONSENT_STORAGE_KEY = "walksafe-test-capture-consent";
const TEST_CAPTURE_CONSENT_MAX_AGE_MS = 12 * 60 * 60 * 1000;

function hasTestCaptureConsent(actorId: string | null, nowMs = Date.now()): boolean {
  if (!actorId || typeof window === "undefined") return false;
  try {
    const stored = JSON.parse(window.sessionStorage.getItem(TEST_CAPTURE_PANEL_CONSENT_STORAGE_KEY) ?? "null") as {
      actor_id?: string;
      accepted_at?: string;
      expires_at?: string;
    } | null;
    const acceptedAtMs = typeof stored?.accepted_at === "string" ? Date.parse(stored.accepted_at) : Number.NaN;
    const expiresAtMs = typeof stored?.expires_at === "string" ? Date.parse(stored.expires_at) : Number.NaN;
    return (
      stored?.actor_id === actorId &&
      Number.isFinite(acceptedAtMs) &&
      Number.isFinite(expiresAtMs) &&
      acceptedAtMs <= nowMs + 60_000 &&
      expiresAtMs > nowMs &&
      expiresAtMs <= acceptedAtMs + TEST_CAPTURE_CONSENT_MAX_AGE_MS
    );
  } catch {
    return false;
  }
}

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
  const gatewaySession = useGatewaySession("field");
  const fieldSessionAuthenticated = gatewaySession.state === "authenticated";
  const detectionIndexRef = useRef(0);
  const reportStateRef = useRef<ReportState>("idle");
  const [testCaptureConsent, setTestCaptureConsent] = useState(false);
  const [testCapturePolicyMessage, setTestCapturePolicyMessage] = useState(
    "이미지·정확 GPS·방향·탐지 결과·기기/브라우저 정보는 서버에 최대 7일 저장됩니다."
  );
  const [fieldTelemetryConsent, setFieldTelemetryConsent] = useState(false);
  const [fieldTelemetryMessage, setFieldTelemetryMessage] = useState("현장 기록은 동의 전까지 수집하지 않습니다.");
  const [serverV2PrivacyConsent, setServerV2PrivacyConsent] = useState(createServerV2PrivacyConsent);
  const serverV2PrivacyConsentRef = useRef(serverV2PrivacyConsent);
  const [serverV2ProcessingConsentMessage, setServerV2ProcessingConsentMessage] = useState(
    "동의 전에는 카메라 프레임과 정확 위치를 서버로 전송하지 않습니다."
  );
  const [serverV2ReportStorageConsentMessage, setServerV2ReportStorageConsentMessage] = useState(
    "서버 탐지 처리에 동의한 뒤 신고 이미지 저장을 별도로 선택할 수 있습니다."
  );
  const serverV2FrameProcessingAllowed = isServerV2FrameProcessingAllowed(serverV2PrivacyConsent);
  const serverV2ReportStorageAllowed = isServerV2ReportStorageAllowed(serverV2PrivacyConsent);

  const updateServerV2PrivacyConsent = useCallback(
    (transition: (current: ServerV2PrivacyConsent) => ServerV2PrivacyConsent) => {
      const nextConsent = transition(serverV2PrivacyConsentRef.current);
      serverV2PrivacyConsentRef.current = nextConsent;
      setServerV2PrivacyConsent(nextConsent);
      return nextConsent;
    },
    []
  );

  const [detection, setDetection] = useState<DetectionEvent | null>(null);
  const [v2Detections, setV2Detections] = useState<TwoModelDetection[]>([]);
  const [v2Primary, setV2Primary] = useState<TwoModelDetection | null>(null);
  const [v2Secondary, setV2Secondary] = useState<TwoModelDetection | null>(null);
  const [nonMetricInferenceFrame, setNonMetricInferenceFrame] = useState<NonMetricAdvisoryFrameObservation>({
    sequence: 0,
    observedAtMs: 0,
    hasDetections: false
  });
  const [detectionV2Audit, setDetectionV2Audit] = useState<DetectV2RequestAudit | null>(null);
  const [detectorMessage, setDetectorMessage] = useState(INITIAL_DETECTOR_MESSAGE);
  const [detectorBusy, setDetectorBusy] = useState(false);
  const [detectionAvailability, setDetectionAvailability] = useState<DetectionAvailability>("unavailable");
  const [cameraFailureGeneration, setCameraFailureGeneration] = useState(0);
  const [speechEnabled, setSpeechEnabled] = useState(true);
  const [reportState, setReportState] = useState<ReportState>("idle");
  const [reportMessage, setReportMessage] = useState("신고 대기 중");
  const [lastDuplicateCount, setLastDuplicateCount] = useState(0);
  const [voiceMessage, setVoiceMessage] = useState("음성 명령 대기");
  const [permissionRequestMessage, setPermissionRequestMessage] = useState<string | null>(null);
  const [assistSessionLifecycle, setAssistSessionLifecycle] = useState<AssistSessionLifecycle>("active");
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
    setDetectionAvailability("unavailable");
    setReportMessage("카메라 준비 필요");
  }, []);

  const enableTestCaptureConsent = useCallback(() => {
    if (!gatewaySession.actorId) return;
    const acceptedAt = new Date();
    try {
      window.sessionStorage.setItem(
        TEST_CAPTURE_PANEL_CONSENT_STORAGE_KEY,
        JSON.stringify({
          actor_id: gatewaySession.actorId,
          accepted_at: acceptedAt.toISOString(),
          expires_at: new Date(acceptedAt.getTime() + TEST_CAPTURE_CONSENT_MAX_AGE_MS).toISOString()
        })
      );
      setTestCaptureConsent(true);
      setTestCapturePolicyMessage("수집 동의가 활성화됐습니다. 철회하면 이 계정이 저장한 테스트 캡처를 삭제합니다.");
    } catch {
      setTestCaptureConsent(false);
    }
  }, [gatewaySession.actorId]);

  const revokeTestCaptureConsent = useCallback(async () => {
    setTestCaptureConsent(false);
    window.localStorage.removeItem(TEST_CAPTURE_PANEL_CONSENT_STORAGE_KEY);
    window.sessionStorage.removeItem(TEST_CAPTURE_PANEL_CONSENT_STORAGE_KEY);
    if (gatewaySession.state !== "authenticated") return;
    try {
      const response = await fetch("/api/walksafe-test-log?all=true", {
        method: "DELETE",
        credentials: "same-origin",
        cache: "no-store"
      });
      if (!response.ok) throw new Error(`delete failed: ${response.status}`);
      const result = (await response.json()) as { deleted_count?: number };
      setTestCapturePolicyMessage(`동의를 철회했고 이 계정의 저장본 ${result.deleted_count ?? 0}건을 삭제했습니다.`);
    } catch {
      setTestCapturePolicyMessage("수집은 중지했지만 서버 저장본 삭제 확인에 실패했습니다. 관리자에게 삭제를 요청해 주세요.");
    }
  }, [gatewaySession.state]);

  useEffect(() => {
    const actorId = gatewaySession.state === "authenticated" ? gatewaySession.actorId : null;
    const refreshConsentState = () => {
      setFieldTelemetryConsent(actorId ? hasStoredFieldTelemetryConsent(actorId) : false);
      setTestCaptureConsent(Boolean(actorId && TEST_CAPTURE_PANEL_ENABLED && hasTestCaptureConsent(actorId)));
    };
    const timer = window.setTimeout(refreshConsentState, 0);
    const expiryInterval = window.setInterval(refreshConsentState, 60_000);
    // A legacy unscoped persistent capture consent must never be inherited.
    window.localStorage.removeItem(TEST_CAPTURE_PANEL_CONSENT_STORAGE_KEY);
    return () => {
      window.clearTimeout(timer);
      window.clearInterval(expiryInterval);
    };
  }, [gatewaySession.actorId, gatewaySession.state]);

  const handleCameraUnavailable = useCallback(() => {
    clearCameraDependentState();
    setCameraFailureGeneration((generation) => generation + 1);
  }, [clearCameraDependentState]);

  const handleCameraError = useCallback(() => {
    handleCameraUnavailable();
    setReportState("idle");
  }, [handleCameraUnavailable]);

  const {
    videoRef,
    cameraReady,
    cameraError,
    cameraPermissionState,
    refreshCameraPermissionState,
    startCamera,
    stopCamera,
    captureFrame
  } = useCamera({
    onCameraUnavailable: handleCameraUnavailable,
    onCameraError: handleCameraError
  });
  const screenWakeLock = useScreenWakeLock(
    fieldSessionAuthenticated && assistSessionLifecycle === "active" && cameraReady
  );
  const {
    gps,
    gpsError,
    heading,
    headingMessage,
    directionLabel,
    startGpsWatch,
    stopSensors,
    requestHeadingPermission,
    getCurrentLocationMessage
  } = useSensors(fieldSessionAuthenticated);
  const stepLengthEstimate = useAutoStepLength(gps, fieldSessionAuthenticated && cameraReady);
  const depthSensor = useDepthSensor({ motionStability: stepLengthEstimate.motionStability });
  const { stopDepthSensor } = depthSensor;
  const {
    autoReportStatus,
    cancelAutoReportV2,
    resetAutoReportV2Session,
    setAutoReportV2State,
    submitV2ReportTarget,
    recordV2DetectionSnapshot,
    handleVoiceReportV2
  } = useAutoReportV2({
    captureFrame,
    gps,
    heading,
    speechEnabled,
    v2Detections,
    isServerV2Mode: IS_SERVER_V2_MODE,
    serverV2PrivacyConsentRef,
    setReportState,
    setReportMessage,
    setLastDuplicateCount,
    setVoiceMessage
  });
  const recordNonMetricInferenceFrame = useCallback((detections: TwoModelDetection[], capturedAt: string) => {
    const capturedAtMs = Date.parse(capturedAt);
    setNonMetricInferenceFrame((previous) => ({
      sequence: previous.sequence + 1,
      observedAtMs: Number.isFinite(capturedAtMs) ? capturedAtMs : Date.now(),
      hasDetections: detections.length > 0
    }));
  }, []);
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
    setDetectionAvailability,
    setReportState,
    setLastDuplicateCount,
    setReportMessage
  });
  const { cancelServerV2Processing, cancelServerV2ReportScheduling } = useDetectionV2({
    cameraReady,
    serverV2FrameProcessingAllowed,
    serverV2ReportStorageAllowed,
    serverV2PrivacyConsentRef,
    gps,
    heading,
    captureFrame,
    estimateDepthForDetection: depthSensor.estimateDepthForDetection,
    onInferenceFrame: recordNonMetricInferenceFrame,
    detectionIndexRef,
    reportStateRef,
    submitV2ReportTarget,
    setAutoReportV2State,
    onDetectionV2Audit: setDetectionV2Audit,
    onDetectionFrame: recordV2DetectionSnapshot,
    setDetection,
    setV2Detections,
    setV2Primary,
    setV2Secondary,
    setDetectorMessage,
    setDetectorBusy,
    setDetectionAvailability,
    setReportState,
    setLastDuplicateCount,
    setReportMessage
  });

  const enableServerV2ProcessingConsent = useCallback(() => {
    updateServerV2PrivacyConsent(allowServerV2FrameProcessing);
    setServerV2ProcessingConsentMessage(
      "서버 탐지 처리를 허용했습니다. 카메라가 켜져 있으면 새 프레임부터 전송합니다."
    );
    setServerV2ReportStorageConsentMessage(
      "서버 탐지는 동작하지만 신고 이미지는 아직 저장하지 않습니다. 별도 동의가 필요합니다."
    );
  }, [updateServerV2PrivacyConsent]);

  const revokeServerV2ProcessingConsent = useCallback(() => {
    updateServerV2PrivacyConsent(withdrawServerV2FrameProcessing);
    cancelServerV2Processing();
    cancelAutoReportV2("자동 신고 중지 · 서버 탐지 처리 동의 철회됨");
    setServerV2ProcessingConsentMessage(
      "동의를 철회해 반복 탐지와 진행 중인 프레임 전송·신고 업로드를 취소했습니다."
    );
    setServerV2ReportStorageConsentMessage(
      "서버 탐지 처리 동의 철회로 신고 이미지 저장 동의도 함께 철회했습니다."
    );
  }, [cancelAutoReportV2, cancelServerV2Processing, updateServerV2PrivacyConsent]);

  const enableServerV2ReportStorageConsent = useCallback(() => {
    if (!isServerV2FrameProcessingAllowed(serverV2PrivacyConsentRef.current)) {
      setServerV2ReportStorageConsentMessage("서버 탐지 처리 동의를 먼저 확인해 주세요.");
      return;
    }
    cancelServerV2ReportScheduling();
    cancelAutoReportV2("자동 신고 대기 · 신고 저장 동의 확인됨");
    updateServerV2PrivacyConsent(allowServerV2ReportStorage);
    setServerV2ReportStorageConsentMessage(
      "신고 이미지 저장과 자동 신고를 허용했습니다. 이제 자동 신고 조건을 확인합니다."
    );
  }, [cancelAutoReportV2, cancelServerV2ReportScheduling, updateServerV2PrivacyConsent]);

  const revokeServerV2ReportStorageConsent = useCallback(() => {
    updateServerV2PrivacyConsent(withdrawServerV2ReportStorage);
    cancelServerV2ReportScheduling();
    cancelAutoReportV2("자동 신고 중지 · 신고 이미지 저장 동의 철회됨");
    setServerV2ReportStorageConsentMessage(
      "신고 저장 동의를 철회해 예약된 자동 신고와 진행 중인 신고 업로드를 취소했습니다. 서버 탐지는 계속됩니다."
    );
  }, [cancelAutoReportV2, cancelServerV2ReportScheduling, updateServerV2PrivacyConsent]);

  const {
    navigationActive,
    navigationStatusText,
    navigationInstructionText,
    navigationDetailText,
    navigationSpeechPrompt,
    navigationSpeechKey,
    navigationStatusMessage,
    navigationFutureMotion,
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
    cancelDestinationAndNavigation,
    retryDestinationSearch,
    getNextNavigationInstruction,
    startNavigation,
    stopNavigation
  } = useNavigationGuidance({
    gps,
    heading,
    v2Detections,
    stepLengthM: stepLengthEstimate.stepLengthM,
    stepBasedSpeedMps: stepLengthEstimate.recentStepSpeedMps,
    motionStability: stepLengthEstimate.motionStability
  });

  const {
    v2PrimaryRiskContext,
    v2SecondaryRiskContext,
    activeV2RiskDetection,
    activeV2RiskContext,
    riskActive,
    detectionLabel,
    riskText,
    nonMetricAdvisoryCapabilityLabel,
    nonMetricAdvisoryActive,
    nonMetricAdvisoryTier,
    nonMetricAdvisoryDirection,
    nonMetricAdvisoryMessage,
    nonMetricAdvisoryConsecutiveFrames,
    nonMetricAdvisoryStableMs,
    speechOutputStatus,
    detectionSafetyAlertMessage,
    detectionSafetyFallbackRequired,
    getLastStatusMessage,
    setLastStatusMessage
  } = useRiskFeedback({
    detection,
    detectionAvailability,
    detectionSafetyEventGeneration: cameraFailureGeneration,
    v2Detections,
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
    futureMotion: navigationFutureMotion,
    cameraReady,
    navigationActive,
    sessionActive: fieldSessionAuthenticated && assistSessionLifecycle === "active",
    nonMetricInferenceFrame,
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
    setAssistSessionLifecycle((current) => transitionAssistSessionLifecycle(current, "user_resume"));
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
    stopVoiceSession,
    handleSpeechToggle,
    handleVoiceCommandButton
  } = useVoiceCommands({
    speechEnabled,
    setSpeechEnabled,
    setVoiceMessage,
    isReportSending,
    onCreateReport,
    onSetDestination: setVoiceDestination,
    onCancelDestination: cancelDestinationAndNavigation,
    onSelectDestinationCandidateByIndex: selectDestinationCandidateByIndex,
    onStartNavigation: startNavigation,
    onStopNavigation: stopNavigation,
    onGetNextNavigationInstruction: getNextNavigationInstruction,
    getLastStatusMessage,
    setLastStatusMessage,
    getCurrentLocationMessage,
    hasGps: Boolean(gps),
    hasNavigationDestination,
    safetyAlertActive: riskActive
  });

  const stopAssistResources = useCallback((message: string) => {
    cancelAutoReportV2();
    if (navigationSearchActive) cancelDestinationSearch();
    stopCamera();
    stopSensors();
    stopNavigation();
    stopVoiceSession();
    void stopDepthSensor();
    clearCameraDependentState();
    setPermissionRequestMessage(message);
  }, [cancelAutoReportV2, cancelDestinationSearch, clearCameraDependentState, navigationSearchActive, stopCamera, stopDepthSensor, stopNavigation, stopSensors, stopVoiceSession]);

  const stopAssistSession = useCallback(() => {
    stopAssistResources("보행 보조 중지됨 · 카메라·위치·방향·depth·음성을 종료했습니다.");
    setAssistSessionLifecycle((current) => transitionAssistSessionLifecycle(current, "user_stop"));
  }, [stopAssistResources]);

  const pauseAssistSession = useCallback(() => {
    stopAssistResources("백그라운드 전환으로 보행 보조를 일시 중지했습니다. 화면으로 돌아온 뒤 재개 버튼을 눌러주세요.");
    setAssistSessionLifecycle((current) => transitionAssistSessionLifecycle(current, "background"));
  }, [stopAssistResources]);

  useEffect(() => {
    if (fieldSessionAuthenticated) {
      return;
    }
    const timer = window.setTimeout(() => {
      revokeServerV2ProcessingConsent();
      stopAssistSession();
    }, 0);
    return () => window.clearTimeout(timer);
  }, [fieldSessionAuthenticated, revokeServerV2ProcessingConsent, stopAssistSession]);

  useEffect(() => {
    const stopWhenHidden = () => {
      if (document.visibilityState !== "visible") {
        pauseAssistSession();
        return;
      }
      setAssistSessionLifecycle((current) => transitionAssistSessionLifecycle(current, "foreground"));
      setPermissionRequestMessage((current) =>
        current?.includes("백그라운드 전환")
          ? "보행 보조가 일시 중지되어 있습니다. 카메라·GPS 재개 버튼을 눌러 명시적으로 다시 시작해 주세요."
          : current
      );
    };
    const stopOnPageHide = () => pauseAssistSession();
    document.addEventListener("visibilitychange", stopWhenHidden);
    window.addEventListener("pagehide", stopOnPageHide);
    return () => {
      document.removeEventListener("visibilitychange", stopWhenHidden);
      window.removeEventListener("pagehide", stopOnPageHide);
    };
  }, [pauseAssistSession]);

  const reportButtonLabel = useMemo(() => {
    if (IS_V2_MODE) {
      if (IS_SERVER_V2_MODE && !serverV2ReportStorageAllowed) {
        return "자동 신고 중지";
      }
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
  }, [autoReportStatus, reportState, serverV2ReportStorageAllowed]);

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
        activeV2RiskDetection
          ? activeV2RiskContext
          : v2PrimaryRiskContext.depth || v2PrimaryRiskContext.tracking
            ? v2PrimaryRiskContext
            : v2SecondaryRiskContext,
        IS_SERVER_V2_MODE || IS_CAMERA_ONLY_MODE
          ? "실기기 테스트 중 · WebXR 깊이는 비활성화"
          : depthSensor.message,
        stepLengthEstimate.stepLengthM,
        stepLengthEstimate.motionStability
      ),
    [
      depthSensor.message,
      activeV2RiskContext,
      activeV2RiskDetection,
      stepLengthEstimate.motionStability,
      stepLengthEstimate.stepLengthM,
      v2PrimaryRiskContext,
      v2SecondaryRiskContext
    ]
  );
  const fieldTelemetrySnapshot = useMemo(
    () => ({
      detector_mode: DETECTOR_MODE,
      camera_ready: cameraReady,
      detector_busy: detectorBusy,
      detector_status: detectorStatusText,
      detect_v2_audit: detectionV2Audit,
      detections: v2Detections,
      primary_detection: v2Primary,
      secondary_detection: v2Secondary,
      gps,
      heading,
      walking_speed_mps: stepLengthEstimate.walkingSpeedMps,
      step_based_speed_mps: stepLengthEstimate.recentStepSpeedMps,
      step_length_m: stepLengthEstimate.stepLengthM,
      motion_stability: stepLengthEstimate.motionStability,
      risk_active: riskActive,
      risk_text: riskText,
      non_metric_advisory_active: nonMetricAdvisoryActive,
      non_metric_advisory_tier: nonMetricAdvisoryTier,
      non_metric_advisory_direction: nonMetricAdvisoryDirection,
      non_metric_advisory_message: nonMetricAdvisoryMessage,
      non_metric_advisory_consecutive_frames: nonMetricAdvisoryConsecutiveFrames,
      non_metric_advisory_stable_ms: nonMetricAdvisoryStableMs,
      non_metric_advisory_max_gps_accuracy_m: WALKSAFE_GUIDANCE_MAX_GPS_ACCURACY_M,
      non_metric_advisory_metric: false,
      non_metric_advisory_tmap_authoritative: true,
      non_metric_advisory_reports_allowed: false,
      depth_status: depthStatus.status,
      depth_detail: depthStatus.detail,
      navigation_active: navigationActive,
      navigation_status: navigationStatusText,
      navigation_instruction: navigationInstructionText,
      navigation_detail: navigationDetailText,
      navigation_future_motion: navigationFutureMotion,
      report_status: IS_V2_MODE ? autoReportStatus : reportState,
      report_message: reportMessage,
      duplicate_report_count: lastDuplicateCount
    }),
    [
      autoReportStatus,
      cameraReady,
      depthStatus.detail,
      depthStatus.status,
      detectionV2Audit,
      detectorBusy,
      detectorStatusText,
      gps,
      heading,
      lastDuplicateCount,
      navigationActive,
      navigationDetailText,
      navigationFutureMotion,
      navigationInstructionText,
      navigationStatusText,
      nonMetricAdvisoryActive,
      nonMetricAdvisoryDirection,
      nonMetricAdvisoryConsecutiveFrames,
      nonMetricAdvisoryMessage,
      nonMetricAdvisoryStableMs,
      nonMetricAdvisoryTier,
      reportMessage,
      reportState,
      riskActive,
      riskText,
      stepLengthEstimate.motionStability,
      stepLengthEstimate.recentStepSpeedMps,
      stepLengthEstimate.stepLengthM,
      stepLengthEstimate.walkingSpeedMps,
      v2Detections,
      v2Primary,
      v2Secondary
    ]
  );
  useFieldTestTelemetry(
    fieldTelemetrySnapshot,
    gatewaySession.state === "authenticated" && fieldTelemetryConsent,
    gatewaySession.actorId
  );

  const enableFieldTelemetry = useCallback(() => {
    if (!gatewaySession.actorId) {
      setFieldTelemetryMessage("사용자 계정을 확인할 수 없어 수집을 시작하지 않았습니다.");
      return;
    }
    try {
      storeFieldTelemetryConsent(gatewaySession.actorId);
      setFieldTelemetryConsent(true);
      setFieldTelemetryMessage("현장 기록 수집을 시작했습니다. 언제든 중지하고 현재 세션 기록을 삭제할 수 있습니다.");
    } catch {
      setFieldTelemetryMessage("이 브라우저에 동의 상태를 저장할 수 없어 수집을 시작하지 않았습니다.");
    }
  }, [gatewaySession.actorId]);

  const disableFieldTelemetry = useCallback(async () => {
    setFieldTelemetryConsent(false);
    try {
      const deleted = await withdrawFieldTelemetryConsent(gatewaySession.actorId);
      setFieldTelemetryMessage(
        deleted ? "수집을 중지하고 현재 브라우저 세션 기록을 삭제했습니다." : "수집을 중지했습니다. 저장된 현재 세션 기록은 없었습니다."
      );
    } catch {
      setFieldTelemetryMessage("수집은 중지했지만 서버 기록 삭제 확인에 실패했습니다. 관리자에게 삭제를 요청해 주세요.");
    }
  }, [gatewaySession.actorId]);

  const logoutFieldSession = useCallback(async () => {
    revokeServerV2ProcessingConsent();
    stopAssistSession();
    resetAutoReportV2Session();
    clearSettings();
    stepLengthEstimate.resetCalibration();
    if (fieldTelemetryConsent) await disableFieldTelemetry();
    await revokeTestCaptureConsent();
    await gatewaySession.logout();
  }, [clearSettings, disableFieldTelemetry, fieldTelemetryConsent, gatewaySession, resetAutoReportV2Session, revokeServerV2ProcessingConsent, revokeTestCaptureConsent, stepLengthEstimate, stopAssistSession]);

  if (gatewaySession.state !== "authenticated") {
    return (
      <GatewaySessionGate
        access="field"
        state={gatewaySession.state}
        message={gatewaySession.message}
        onAuthenticate={gatewaySession.authenticate}
        onRetry={gatewaySession.refresh}
      />
    );
  }

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
      {assistSessionLifecycle === "paused" ? (
        <div className="offline-banner" role="status" aria-live="assertive">
          백그라운드 전환으로 카메라·GPS·길안내·음성을 중지했습니다.
          <button type="button" onClick={reconnectCameraAndSensors}>카메라·GPS 재개</button>
        </div>
      ) : null}
      {cameraReady && assistSessionLifecycle === "active" ? (
        <div
          className={`offline-banner ${screenWakeLock.warning ? "offline" : ""}`}
          role={screenWakeLock.warning ? "alert" : "status"}
          aria-live={screenWakeLock.warning ? "assertive" : "polite"}
        >
          {screenWakeLock.message}
          {screenWakeLock.canRetry ? (
            <button type="button" onClick={screenWakeLock.retry}>화면 켜짐 유지 다시 요청</button>
          ) : null}
        </div>
      ) : null}

      {IS_SERVER_V2_MODE ? (
        <section className="field-privacy-controls" aria-label="서버 탐지 처리 동의">
          <div>
            <strong>
              서버 탐지 처리: {serverV2FrameProcessingAllowed ? "프레임 전송 허용" : "동의 전 중지"}
            </strong>
            <p>
              동의하면 server-v2 분석을 위해 후면 카메라 영상에서 만든 최대 960px 전체 프레임 JPEG와
              촬영 시각을 반복 전송합니다. GPS가 확인되면 정확한 위도·경도·정확도와 가능한 경우 속도를,
              방향이 확인되면 방향값도 same-origin gateway를 거쳐 서버로 전송합니다.
            </p>
            <p>
              얼굴·차량번호를 가리지 않은 프레임을 추론에 사용하지만, backend /detect/v2 자체는 프레임을 파일이나 DB에 저장하지 않습니다.
              이 동의만으로 신고 이미지가 저장되거나 자동 신고되지는 않습니다. 두 동의 상태는 브라우저 저장소에
              남기지 않으므로 새로고침이나 로그아웃 후 다시 선택해야 합니다.
            </p>
            <small role="status" aria-live="polite">{serverV2ProcessingConsentMessage}</small>
          </div>
          <div className="field-privacy-actions">
            {serverV2FrameProcessingAllowed ? (
              <button type="button" onClick={revokeServerV2ProcessingConsent}>
                처리 동의 철회·탐지/신고 전송 취소
              </button>
            ) : (
              <button type="button" onClick={enableServerV2ProcessingConsent}>
                전송 항목 확인·서버 탐지 동의
              </button>
            )}
          </div>
        </section>
      ) : null}

      {IS_SERVER_V2_MODE ? (
        <section className="field-privacy-controls" aria-label="신고 이미지 저장 및 자동 신고 동의">
          <div>
            <strong>
              신고 이미지 저장·자동 신고: {serverV2ReportStorageAllowed ? "동의됨" : "중지됨"}
            </strong>
            <p>
              별도로 동의하면 손상 점자블록이 신고 조건을 통과할 때 분석에 사용한 동일한 전체 프레임 JPEG,
              정확한 GPS와 정확도·가능한 경우 속도, 확인된 방향, 촬영 시각, 탐지 모델·class·confidence·bbox·threshold,
              가능한 경우 거리 추정값과 자동 신고 표시를 /reports/v2에 전송합니다.
              음성 신고도 같은 저장 동의를 적용합니다.
            </p>
            <p>
              보존 정책: 180일. 서버는 얼굴·차량번호를 모자이크하지 않은 신고 이미지를 metadata 없이 재인코딩해
              정확 위치·신고 정보와 저장합니다. 로그인 field 계정 actor가 수집 감사 정보로 기록되며,
              named 관리자가 내부 검수·export 후 필요한 신고를 기관에 수동 제출합니다. 기관 자동 제출은 하지 않습니다.
              동의 철회는 예약·진행 중 요청을 취소하며 이미 저장 완료된 신고에는 180일 보존 정책이 적용됩니다.
            </p>
            <small role="status" aria-live="polite">{serverV2ReportStorageConsentMessage}</small>
          </div>
          <div className="field-privacy-actions">
            {serverV2ReportStorageAllowed ? (
              <button type="button" onClick={revokeServerV2ReportStorageConsent}>
                신고 저장 동의 철회·업로드 취소
              </button>
            ) : (
              <button
                type="button"
                onClick={enableServerV2ReportStorageConsent}
                disabled={!serverV2FrameProcessingAllowed}
              >
                저장 항목 확인·자동 신고 동의
              </button>
            )}
          </div>
        </section>
      ) : null}

      <section className="field-privacy-controls" aria-label="현장 기록 및 세션 제어">
        <div>
          <strong>현장 진단 기록: {fieldTelemetryConsent ? "수집 중" : "중지됨"}</strong>
          <p>
            이 진단 기록 동의는 위 서버 탐지 처리 및 신고 이미지 저장 동의와 서로 별개입니다.
            동의하면 탐지·경로·신고 상태, 기기 정보와 약 1m 단위로 축약한 위치를 최대 7일간 저장합니다.
            카메라·음성 원본은 이 기록에 포함하지 않습니다.
          </p>
          <small role="status" aria-live="polite">{fieldTelemetryMessage}</small>
        </div>
        <div className="field-privacy-actions">
          {fieldTelemetryConsent ? (
            <button type="button" onClick={() => void disableFieldTelemetry()}>수집 중지·현재 기록 삭제</button>
          ) : (
            <button type="button" onClick={enableFieldTelemetry}>고지 확인·수집 동의</button>
          )}
          <button type="button" onClick={stopAssistSession}>보행 보조 중지</button>
          <button type="button" onClick={() => void logoutFieldSession()}>
            {gatewaySession.actorId ? `${gatewaySession.actorId} 로그아웃` : "로그아웃"}
          </button>
        </div>
      </section>

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
        nonMetricAdvisoryActive={nonMetricAdvisoryActive}
        nonMetricAdvisoryCapabilityLabel={nonMetricAdvisoryCapabilityLabel}
        detectionAvailability={detectionAvailability}
        modeText={modeText}
        isFakeV2Mode={IS_FAKE_V2_MODE}
        riskText={riskText}
        detectorStatusText={detectorStatusText}
      />

      <AssistPanel
        panelId="assist-status"
        detection={detection}
        v2Detections={v2Detections}
        activeV2RiskDetection={activeV2RiskDetection}
        riskActive={riskActive}
        detectionAvailability={detectionAvailability}
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
        nonMetricAdvisoryCapabilityLabel={nonMetricAdvisoryCapabilityLabel}
        nonMetricAdvisoryActive={nonMetricAdvisoryActive}
        nonMetricAdvisoryMessage={nonMetricAdvisoryMessage}
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
        speechOutputStatus={speechOutputStatus}
        detectionSafetyAlertMessage={detectionSafetyAlertMessage}
        detectionSafetyFallbackRequired={detectionSafetyFallbackRequired}
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
          onRevokeConsent={revokeTestCaptureConsent}
        />
      ) : TEST_CAPTURE_PANEL_ENABLED ? (
        <section className="test-capture-panel" aria-label="실기기 테스트 기록 동의">
          <p>실기기 테스트 로그 저장을 사용하려면 동의가 필요합니다.</p>
          <p>이미지·정확 GPS·방향·탐지 결과·기기 및 브라우저 정보가 named field 계정에 연결되어 서버에 최대 7일 저장됩니다.</p>
          <p>동의는 최대 12시간 유지되며 철회하면 새 수집을 중지하고 이 계정의 저장본 삭제를 요청합니다.</p>
          <small>{testCapturePolicyMessage}</small>
          <button type="button" onClick={enableTestCaptureConsent}>
            동의 후 활성화
          </button>
        </section>
      ) : null}
    </main>
  );
}
