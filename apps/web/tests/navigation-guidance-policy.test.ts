import React, { createElement, isValidElement, type ReactNode } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import {
  buildNavigationGuidePrompt,
  describeFutureRouteRoiState,
  estimateWalkingSpeedMps,
  isNavigationRouteRequestCurrent,
  navigationStartLocationError,
  resolveActiveNavigationSensorGate,
  resolveAutoRerouteDecision,
  resolveNavigationRouteRequestGate,
  resolveNavigationSpeechCandidate,
  resolveNextNavigationInstruction,
  WALKSAFE_ROUTE_REQUEST_COOLDOWN_MS,
  type NavigationGuidePrompt
} from "../app/_walksafe/hooks/useNavigationGuidance";
import { WALKING_ROUTE_PROVIDER_LABEL } from "../lib/navigation-api";
import {
  WALKSAFE_DEFAULT_STEP_LENGTH_M,
  WALKSAFE_GUIDE_SOON_RADIUS_M,
  WALKSAFE_GUIDE_TURN_RADIUS_M
} from "../app/_walksafe/config";
import { reportExportUrl, type ReportExportFormat, type ReportListParams } from "../lib/report-api";
import {
  attemptNavigationSpeechDelivery,
  commitNavigationSpeechDelivery,
  resolveVoiceFeedbackState,
  shouldApplyNavigationStatusMessage,
  shouldAttemptNavigationSpeech,
  shouldBlockSpeechDuringRecognition,
  shouldPreemptSpeech,
  shouldStopSpeechOwnedByDelivery
} from "../app/_walksafe/voice-priority";
import { transitionAssistSessionLifecycle } from "../app/_walksafe/assist-session-lifecycle";
import { evaluateTactileRoutePolicy } from "../app/_walksafe/tactile-route-policy";
import {
  shouldBlockVoiceRecordingForSafety,
  shouldCancelVoiceSessionForVisibility,
  useVoiceCommands
} from "../app/_walksafe/hooks/useVoiceCommands";
import {
  getActiveSpeechPriority,
  getSpeechRecognitionActive,
  getSpeechOutputStatus,
  setSpeechRecognitionActive,
  speak,
  stopSpeaking,
  WALKSAFE_SPEECH_ARBITRATION_STATUS_EVENT,
  WALKSAFE_SPEECH_OUTPUT_STATUS_EVENT,
  WALKSAFE_SPEECH_RECOGNITION_STATUS_EVENT,
  WALKSAFE_URGENT_SPEECH_EVENT
} from "../app/_walksafe/feedback";
import { AssistPanel } from "../app/_walksafe/components/AssistPanel";
import { AssistiveAnnouncement } from "../app/_walksafe/components/AssistiveAnnouncement";
import { NavigationRequestCoordinator } from "../app/_walksafe/navigation-request-coordinator";
import type { VoiceSttResponse } from "../lib/voice-api";
import type { RoutePoint, WalkingRouteGuidePoint } from "../types/navigation";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}


function assertSearchParam(url: URL, key: string, expected: string) {
  assert(url.searchParams.get(key) === expected, `${key} should be ${expected}`);
}

type AssistPanelProps = Parameters<typeof AssistPanel>[0];

function productionAssistPanelProps(overrides: Partial<AssistPanelProps>): AssistPanelProps {
  const noop = () => undefined;
  return {
    detection: null,
    v2Detections: [],
    activeV2RiskDetection: null,
    riskActive: false,
    detectionAvailability: "available",
    isV2Mode: true,
    detectionLabel: "위험 요소 없음",
    detectorStatusText: "탐지 정상",
    gps: null,
    gpsError: null,
    directionLabel: "방향 대기",
    heading: null,
    headingMessage: "방향 센서 대기",
    reportMessage: "",
    lastDuplicateCount: 0,
    voiceMessage: "",
    voiceResultText: "",
    navigationActive: false,
    navigationStatusText: "",
    navigationInstructionText: "",
    navigationDetailText: "",
    navigationDestinationCandidates: [],
    navigationHiddenDestinationCandidateCount: 0,
    navigationCanShowMoreDestinations: false,
    navigationSearchActive: false,
    onSelectNavigationCandidate: noop,
    onShowMoreNavigationCandidates: noop,
    onCancelNavigationSearch: noop,
    onRetryNavigationSearch: noop,
    onStopNavigation: noop,
    speechEnabled: true,
    speechOutputStatus: "failed",
    detectionSafetyAlertMessage: null,
    detectionSafetyFallbackRequired: false,
    handleSpeechToggle: noop,
    voiceState: "idle",
    voiceSupported: true,
    voiceButtonLabel: "음성 명령",
    voiceButtonHelp: "음성 명령 도움말",
    handleVoiceCommandButton: noop,
    handleReport: noop,
    canReport: false,
    reportDisabledReason: "신고 대기",
    reportState: "idle",
    reportButtonLabel: "신고",
    reportHelpText: "",
    reconnectCameraAndSensors: noop,
    permissionRequestMessage: null,
    guardianSummary: "",
    settingsMessage: "",
    settingsExpanded: false,
    setupChecklist: [],
    settingsForm: { emergencyContacts: [] },
    stepLengthSummary: "보폭 대기",
    stepLengthDetail: "",
    motionPermissionLabel: "동작 권한",
    motionSampleCount: 0,
    stepLengthConfidence: 0,
    storedStepCalibrationActive: false,
    depthStatusText: "깊이 대기",
    depthDetailText: "",
    depthSensorStatus: "idle",
    depthFrameCount: 0,
    isOnline: true,
    pwaInstallMessage: "",
    pwaUpdateMessage: "",
    swVersion: null,
    canInstallPwa: false,
    canApplyPwaUpdate: false,
    onEmergencyContactNameChange: noop,
    onEmergencyContactPhoneChange: noop,
    onAddEmergencyContact: noop,
    onRemoveEmergencyContact: noop,
    onRequestMotionPermission: noop,
    onResetStepLengthCalibration: noop,
    onRequestDepthSensor: noop,
    onStopDepthSensor: noop,
    onInstallPwa: noop,
    onApplyPwaUpdate: noop,
    onSaveSettings: noop,
    onClearSettings: noop,
    onToggleSettingsExpanded: noop,
    ...overrides
  };
}

function renderProductionAssistPanel(overrides: Partial<AssistPanelProps>): string {
  return renderToStaticMarkup(createElement(AssistPanel, productionAssistPanelProps(overrides)));
}

function testDestinationSearchKeepsActiveRouteUntilSelection() {
  const coordinator = new NavigationRequestCoordinator();
  const route = coordinator.beginRoute();
  const destination = coordinator.beginDestination();

  coordinator.cancelDestinationSearch();

  assert(destination.controller.signal.aborted, "search cancellation must abort the destination request");
  assert(!coordinator.isCurrent(destination), "a cancelled destination response must be stale");
  assert(!route.controller.signal.aborted, "search cancellation must preserve the active TMAP route request");
  assert(coordinator.isCurrent(route), "the existing TMAP route must remain current after search cancellation");
}

function testDestinationSearchCancelDoesNotCancelExistingNavigation() {
  const coordinator = new NavigationRequestCoordinator();
  const firstDestination = coordinator.beginDestination();
  const replacementDestination = coordinator.beginDestination();

  assert(firstDestination.controller.signal.aborted, "a replacement search must abort the previous request");
  assert(!coordinator.finish(firstDestination), "a late previous search completion must not clear its replacement");
  assert(coordinator.isCurrent(replacementDestination), "the replacement search must remain current");

  const oldRoute = coordinator.beginRoute();
  coordinator.cancelDestinationAndNavigation();
  const newRoute = coordinator.beginRoute();
  const newDestination = coordinator.beginDestination();

  assert(oldRoute.controller.signal.aborted, "full cancellation must abort the route request");
  assert(replacementDestination.controller.signal.aborted, "full cancellation must abort the destination request");
  assert(!coordinator.isCurrent(oldRoute), "a fully cancelled route response must be stale");
  assert(!coordinator.finish(oldRoute), "a late cancelled route completion must not clear the new route");
  assert(!coordinator.finish(replacementDestination), "a late cancelled search completion must not clear the new search");
  assert(coordinator.isCurrent(newRoute), "the post-cancel route request must remain current");
  assert(coordinator.isCurrent(newDestination), "the post-cancel destination request must remain current");
}

function textContent(node: ReactNode): string {
  if (typeof node === "string" || typeof node === "number") return String(node);
  if (Array.isArray(node)) return node.map(textContent).join("");
  if (!isValidElement(node)) return "";
  return textContent((node.props as { children?: ReactNode }).children);
}

function testProductionNavigationButtonsInvokeTheirWiredActions() {
  const reactHooks = React as unknown as {
    useState: typeof React.useState;
    useEffect: typeof React.useEffect;
  };
  const originalUseState = reactHooks.useState;
  const originalUseEffect = reactHooks.useEffect;
  let searchCancels = 0;
  let navigationStops = 0;
  const clickedLabels: string[] = [];
  reactHooks.useState = (((initial: unknown) => [
    typeof initial === "function" ? (initial as () => unknown)() : initial,
    () => undefined
  ]) as unknown) as typeof React.useState;
  reactHooks.useEffect = (() => undefined) as typeof React.useEffect;
  try {
    const tree = AssistPanel(productionAssistPanelProps({
      navigationActive: true,
      navigationSearchActive: true,
      navigationDestinationCandidates: [{
        id: "destination-1",
        name: "서울역",
        label: "서울역",
        addressLabel: "서울특별시 중구",
        distanceLabel: "1.0km",
        speechLabel: "서울역, 서울특별시 중구, 1.0km",
        result: {
          id: "destination-1",
          name: "서울역",
          point: { latitude: 37.5547, longitude: 126.9707 },
          road_address: "서울특별시 중구",
          distance_m: 1000
        }
      }],
      onCancelNavigationSearch: () => {
        searchCancels += 1;
      },
      onStopNavigation: () => {
        navigationStops += 1;
      }
    }));
    const visit = (node: ReactNode) => {
      if (Array.isArray(node)) {
        node.forEach(visit);
        return;
      }
      if (!isValidElement(node)) return;
      const props = node.props as { children?: ReactNode; onClick?: () => void };
      const label = textContent(props.children).trim();
      if (node.type === "button" && ["길안내 중지", "검색 취소", "취소"].includes(label)) {
        clickedLabels.push(label);
        props.onClick?.();
      }
      visit(props.children);
    };
    visit(tree);
  } finally {
    reactHooks.useState = originalUseState;
    reactHooks.useEffect = originalUseEffect;
  }

  assert(clickedLabels.includes("길안내 중지"), "the production panel must expose the route-stop action");
  assert(clickedLabels.includes("검색 취소") && clickedLabels.includes("취소"), "both search-cancel controls must be executable");
  assert(navigationStops === 1, "the production route-stop button must invoke its wired action once");
  assert(searchCancels === 2, "both production search-cancel buttons must invoke the search-only action");
}

async function testVoiceHookForwardsFullDestinationCancellation() {
  const reactHooks = React as unknown as {
    useCallback: typeof React.useCallback;
    useEffect: typeof React.useEffect;
    useMemo: typeof React.useMemo;
    useRef: typeof React.useRef;
    useState: typeof React.useState;
  };
  const originals = {
    useCallback: reactHooks.useCallback,
    useEffect: reactHooks.useEffect,
    useMemo: reactHooks.useMemo,
    useRef: reactHooks.useRef,
    useState: reactHooks.useState
  };
  let cancelCalls = 0;
  const acceptedIntent: { current: ((result: VoiceSttResponse) => Promise<void>) | null } = { current: null };
  const fullCancel = () => {
    cancelCalls += 1;
    return { ok: true, message: "목적지와 진행 중인 경로를 취소했습니다." };
  };
  const onCreateReport = async () => undefined;
  reactHooks.useState = (((initial: unknown) => [
    typeof initial === "function" ? (initial as () => unknown)() : initial,
    () => undefined
  ]) as unknown) as typeof React.useState;
  reactHooks.useRef = ((initial: unknown) => ({ current: initial })) as typeof React.useRef;
  reactHooks.useEffect = (() => undefined) as typeof React.useEffect;
  reactHooks.useMemo = ((factory: () => unknown) => factory()) as typeof React.useMemo;
  reactHooks.useCallback = (((callback: unknown, dependencies?: readonly unknown[]) => {
    if (dependencies?.includes(fullCancel) && dependencies.includes(onCreateReport)) {
      acceptedIntent.current = callback as (result: VoiceSttResponse) => Promise<void>;
    }
    return callback;
  }) as unknown) as typeof React.useCallback;
  try {
    useVoiceCommands({
      speechEnabled: false,
      setSpeechEnabled: () => undefined,
      setVoiceMessage: () => undefined,
      isReportSending: () => false,
      onCreateReport,
      onCancelDestination: fullCancel,
      getLastStatusMessage: () => null,
      setLastStatusMessage: () => undefined,
      getCurrentLocationMessage: () => "현재 위치 확인",
      hasGps: true,
      hasNavigationDestination: true,
      safetyAlertActive: false
    });
    assert(acceptedIntent.current !== null, "the production voice hook must assemble its accepted-intent coordinator");
    await acceptedIntent.current({
      transcript: "목적지 취소",
      normalized: "목적지 취소",
      intent: "cancel_destination",
      confidence: 1,
      acoustic_execution_allowed: true,
      slots: {},
      action: "execute",
      should_execute: true
    });
  } finally {
    reactHooks.useCallback = originals.useCallback;
    reactHooks.useEffect = originals.useEffect;
    reactHooks.useMemo = originals.useMemo;
    reactHooks.useRef = originals.useRef;
    reactHooks.useState = originals.useState;
  }

  assert(cancelCalls === 1, "useVoiceCommands must forward full destination cancellation exactly once");
}

async function runApprovedCancelThroughProductionRecording(
  options: Parameters<typeof useVoiceCommands>[0]
): Promise<void> {
  const reactHooks = React as unknown as {
    useCallback: typeof React.useCallback;
    useEffect: typeof React.useEffect;
    useMemo: typeof React.useMemo;
    useRef: typeof React.useRef;
    useState: typeof React.useState;
  };
  const originals = {
    useCallback: reactHooks.useCallback,
    useEffect: reactHooks.useEffect,
    useMemo: reactHooks.useMemo,
    useRef: reactHooks.useRef,
    useState: reactHooks.useState
  };
  const voiceApiModule = require("../lib/voice-api") as typeof import("../lib/voice-api");
  const originalUploadSpeechStt = voiceApiModule.uploadSpeechStt;
  const originalWindow = Object.getOwnPropertyDescriptor(globalThis, "window");
  const originalDocument = Object.getOwnPropertyDescriptor(globalThis, "document");
  const originalNavigator = Object.getOwnPropertyDescriptor(globalThis, "navigator");
  const originalMediaRecorder = Object.getOwnPropertyDescriptor(globalThis, "MediaRecorder");
  const originalSpeechSynthesisUtterance = Object.getOwnPropertyDescriptor(globalThis, "SpeechSynthesisUtterance");
  const stateSlots: unknown[] = [];
  const refSlots: Array<{ current: unknown }> = [];
  let stateCursor = 0;
  let refCursor = 0;
  let uploadCalls = 0;
  let getUserMediaCalls = 0;
  let recorderStartCalls = 0;
  let recorderStopCalls = 0;
  let speechCancelCalls = 0;
  let trackStopCalls = 0;
  let activeRecorder: MockMediaRecorder | null = null;
  const activeRiskUtterance: { current: MockSpeechSynthesisUtterance | null } = { current: null };
  const getUserMediaCallCount = () => getUserMediaCalls;
  const recorderStartCallCount = () => recorderStartCalls;

  const microphoneTrack = {
    addEventListener: () => undefined,
    stop: () => {
      trackStopCalls += 1;
    }
  };
  const microphoneStream = {
    getAudioTracks: () => [microphoneTrack],
    getTracks: () => [microphoneTrack]
  } as unknown as MediaStream;
  class MockMediaRecorder {
    static isTypeSupported() {
      return true;
    }

    state: RecordingState = "inactive";
    ondataavailable: ((event: { data: Blob }) => void) | null = null;
    onerror: (() => void) | null = null;
    onstop: (() => void) | null = null;

    constructor(_stream: MediaStream, _options?: MediaRecorderOptions) {
      activeRecorder = this;
    }

    start() {
      this.state = "recording";
      recorderStartCalls += 1;
    }

    stop() {
      if (this.state === "inactive") return;
      this.state = "inactive";
      recorderStopCalls += 1;
      this.ondataavailable?.({ data: new Blob(["approved cancel destination"], { type: "audio/webm" }) });
      this.onstop?.();
    }
  }
  class MockSpeechSynthesisUtterance {
    lang = "";
    rate = 1;
    onstart: (() => void) | null = null;
    onend: (() => void) | null = null;
    onerror: (() => void) | null = null;

    constructor(readonly text: string) {}
  }
  const approvedCancelResult: VoiceSttResponse = {
    transcript: "목적지 취소",
    normalized: "목적지 취소",
    intent: "cancel_destination",
    confidence: 1,
    acoustic_execution_allowed: true,
    slots: {},
    action: "execute",
    should_execute: true
  };

  reactHooks.useState = (((initial: unknown) => {
    const slot = stateCursor;
    stateCursor += 1;
    if (!(slot in stateSlots)) {
      stateSlots[slot] = typeof initial === "function" ? (initial as () => unknown)() : initial;
    }
    const setState = (value: unknown) => {
      stateSlots[slot] = typeof value === "function"
        ? (value as (current: unknown) => unknown)(stateSlots[slot])
        : value;
    };
    return [stateSlots[slot], setState];
  }) as unknown) as typeof React.useState;
  reactHooks.useRef = ((initial: unknown) => {
    const slot = refCursor;
    refCursor += 1;
    if (!(slot in refSlots)) refSlots[slot] = { current: initial };
    return refSlots[slot];
  }) as typeof React.useRef;
  reactHooks.useEffect = (() => undefined) as typeof React.useEffect;
  reactHooks.useMemo = ((factory: () => unknown) => factory()) as typeof React.useMemo;
  reactHooks.useCallback = ((callback: unknown) => callback) as typeof React.useCallback;
  voiceApiModule.uploadSpeechStt = (async (audio, signal) => {
    uploadCalls += 1;
    assert(audio.size > 0, "the MediaRecorder stop event must upload a non-empty audio blob");
    assert(!signal?.aborted, "the approved recording upload must remain active");
    return approvedCancelResult;
  }) as typeof voiceApiModule.uploadSpeechStt;
  Object.defineProperty(globalThis, "window", {
    configurable: true,
    writable: true,
    value: Object.assign(new EventTarget(), {
      clearTimeout: () => undefined,
      setTimeout: () => 1,
      speechSynthesis: {
        cancel: () => {
          speechCancelCalls += 1;
        },
        speak: (utterance: MockSpeechSynthesisUtterance) => {
          activeRiskUtterance.current = utterance;
          utterance.onstart?.();
        }
      }
    })
  });
  Object.defineProperty(globalThis, "document", {
    configurable: true,
    writable: true,
    value: Object.assign(new EventTarget(), { visibilityState: "visible" })
  });
  Object.defineProperty(globalThis, "navigator", {
    configurable: true,
    writable: true,
    value: {
      mediaDevices: {
        getUserMedia: async () => {
          getUserMediaCalls += 1;
          return microphoneStream;
        }
      },
      onLine: true,
      vibrate: () => true
    }
  });
  Object.defineProperty(globalThis, "MediaRecorder", {
    configurable: true,
    writable: true,
    value: MockMediaRecorder
  });
  Object.defineProperty(globalThis, "SpeechSynthesisUtterance", {
    configurable: true,
    writable: true,
    value: MockSpeechSynthesisUtterance
  });

  const renderVoiceHook = () => {
    stateCursor = 0;
    refCursor = 0;
    return useVoiceCommands(options);
  };

  try {
    setSpeechRecognitionActive(false);
    assert(
      speak("실시간 장애물 탐지 오류입니다. 전방을 직접 확인해 주세요.", "risk"),
      "the production detector safety warning must acquire risk speech priority"
    );
    assert(getActiveSpeechPriority() === "risk", "the detector safety warning must retain risk speech ownership");
    const cancelCallsBeforeVoiceButton = speechCancelCalls;
    const idleVoice = renderVoiceHook();
    idleVoice.handleVoiceCommandButton();
    await Promise.resolve();
    assert(getUserMediaCallCount() === 0, "risk speech ownership must block microphone permission and recording startup");
    assert(recorderStartCallCount() === 0, "risk speech ownership must block MediaRecorder startup");
    assert(
      speechCancelCalls === cancelCallsBeforeVoiceButton,
      "a blocked voice command must not cancel the active detector safety warning"
    );
    assert(getActiveSpeechPriority() === "risk", "a blocked voice command must preserve risk speech ownership");

    assert(activeRiskUtterance.current !== null, "the detector safety warning must reach browser speech synthesis");
    activeRiskUtterance.current.onend?.();
    assert(getActiveSpeechPriority() === null, "completed detector safety speech must release risk ownership");
    const voiceAfterRisk = renderVoiceHook();
    voiceAfterRisk.handleVoiceCommandButton();
    for (let attempt = 0; attempt < 10 && recorderStartCallCount() === 0; attempt += 1) {
      await Promise.resolve();
    }
    assert(activeRecorder !== null, "the production voice button must create a MediaRecorder");
    assert(getUserMediaCallCount() === 1, "voice recording must request the microphone after risk speech completes");
    assert(recorderStartCallCount() === 1, "the production voice button must start one recording");

    const recordingVoice = renderVoiceHook();
    assert(recordingVoice.voiceState === "recording", "the hook rerender must expose the active recording state");
    recordingVoice.handleVoiceCommandButton();
    await new Promise<void>((resolve) => setImmediate(resolve));

    assert(recorderStopCalls === 1, "the recording-state voice button must stop the MediaRecorder once");
    assert(uploadCalls === 1, "the MediaRecorder onstop flow must upload exactly one recording");
    assert(trackStopCalls === 1, "the MediaRecorder onstop flow must release the microphone track");
  } finally {
    setSpeechRecognitionActive(false);
    stopSpeaking();
    voiceApiModule.uploadSpeechStt = originalUploadSpeechStt;
    reactHooks.useCallback = originals.useCallback;
    reactHooks.useEffect = originals.useEffect;
    reactHooks.useMemo = originals.useMemo;
    reactHooks.useRef = originals.useRef;
    reactHooks.useState = originals.useState;
    if (originalWindow) Object.defineProperty(globalThis, "window", originalWindow);
    else Reflect.deleteProperty(globalThis, "window");
    if (originalDocument) Object.defineProperty(globalThis, "document", originalDocument);
    else Reflect.deleteProperty(globalThis, "document");
    if (originalNavigator) Object.defineProperty(globalThis, "navigator", originalNavigator);
    else Reflect.deleteProperty(globalThis, "navigator");
    if (originalMediaRecorder) Object.defineProperty(globalThis, "MediaRecorder", originalMediaRecorder);
    else Reflect.deleteProperty(globalThis, "MediaRecorder");
    if (originalSpeechSynthesisUtterance) {
      Object.defineProperty(globalThis, "SpeechSynthesisUtterance", originalSpeechSynthesisUtterance);
    } else {
      Reflect.deleteProperty(globalThis, "SpeechSynthesisUtterance");
    }
  }
}

async function testHomeVoiceCancelExecutesFullProductionCancellation() {
  const reactHooks = React as unknown as {
    useCallback: typeof React.useCallback;
    useEffect: typeof React.useEffect;
    useId: typeof React.useId;
    useMemo: typeof React.useMemo;
    useRef: typeof React.useRef;
    useState: typeof React.useState;
  };
  const originals = {
    useCallback: reactHooks.useCallback,
    useEffect: reactHooks.useEffect,
    useId: reactHooks.useId,
    useMemo: reactHooks.useMemo,
    useRef: reactHooks.useRef,
    useState: reactHooks.useState
  };
  const navigationHookModule = require("../app/_walksafe/hooks/useNavigationGuidance") as typeof import("../app/_walksafe/hooks/useNavigationGuidance");
  const voiceHookModule = require("../app/_walksafe/hooks/useVoiceCommands") as typeof import("../app/_walksafe/hooks/useVoiceCommands");
  const originalNavigationHook = navigationHookModule.useNavigationGuidance;
  const originalVoiceHook = voiceHookModule.useVoiceCommands;
  const noop = () => undefined;
  let fullCancelCalls = 0;
  let searchCancelCalls = 0;
  const voiceOptions: { current: Parameters<typeof useVoiceCommands>[0] | null } = { current: null };

  reactHooks.useState = (((initial: unknown) => [
    typeof initial === "function" ? (initial as () => unknown)() : initial,
    noop
  ]) as unknown) as typeof React.useState;
  reactHooks.useRef = ((initial: unknown) => ({ current: initial })) as typeof React.useRef;
  reactHooks.useEffect = noop as typeof React.useEffect;
  reactHooks.useMemo = ((factory: () => unknown) => factory()) as typeof React.useMemo;
  reactHooks.useCallback = ((callback: unknown) => callback) as typeof React.useCallback;
  reactHooks.useId = (() => "home-voice-composition") as typeof React.useId;
  navigationHookModule.useNavigationGuidance = (() => ({
    navigationActive: true,
    navigationStatusText: "길안내 중",
    navigationInstructionText: "직진하세요.",
    navigationDetailText: "테스트 경로",
    navigationSpeechPrompt: null,
    navigationSpeechKey: null,
    navigationStatusMessage: null,
    navigationFutureMotion: null,
    navigationDestinationCandidates: [],
    navigationHiddenDestinationCandidateCount: 0,
    navigationCanShowMoreDestinations: false,
    navigationSearchActive: true,
    hasNavigationDestination: true,
    setVoiceDestination: noop,
    selectDestinationCandidate: noop,
    selectDestinationCandidateByIndex: async () => ({ ok: false, message: "후보 없음" }),
    showMoreDestinationCandidates: noop,
    cancelDestinationSearch: () => {
      searchCancelCalls += 1;
      return { ok: true, message: "검색만 취소했습니다." };
    },
    cancelDestinationAndNavigation: () => {
      fullCancelCalls += 1;
      return { ok: true, message: "목적지와 진행 중인 경로를 취소했습니다." };
    },
    retryDestinationSearch: async () => ({ ok: false, message: "재검색 없음" }),
    getNextNavigationInstruction: () => ({ ok: true, message: "직진하세요." }),
    startNavigation: async () => ({ ok: true, message: "길안내 시작" }),
    stopNavigation: noop
  })) as unknown as typeof navigationHookModule.useNavigationGuidance;
  voiceHookModule.useVoiceCommands = ((options: Parameters<typeof useVoiceCommands>[0]) => {
    voiceOptions.current = options;
    return {
      voiceSupported: true,
      voiceState: "idle",
      voiceResultText: "",
      voiceButtonLabel: "음성 명령",
      voiceButtonHelp: "음성 명령 도움말",
      stopVoiceSession: noop,
      handleSpeechToggle: noop,
      handleVoiceCommandButton: noop
    };
  }) as typeof voiceHookModule.useVoiceCommands;

  try {
    const { default: Home } = require("../app/page") as typeof import("../app/page");
    Home();
    assert(voiceOptions.current !== null, "Home must compose the production voice command hook");
  } finally {
    navigationHookModule.useNavigationGuidance = originalNavigationHook;
    voiceHookModule.useVoiceCommands = originalVoiceHook;
    reactHooks.useCallback = originals.useCallback;
    reactHooks.useEffect = originals.useEffect;
    reactHooks.useId = originals.useId;
    reactHooks.useMemo = originals.useMemo;
    reactHooks.useRef = originals.useRef;
    reactHooks.useState = originals.useState;
  }

  assert(voiceOptions.current !== null, "Home must retain its production voice command options for recording");
  await runApprovedCancelThroughProductionRecording(voiceOptions.current);
  assert(
    fullCancelCalls === 1,
    "an approved recorded cancel_destination must execute Home full destination and route cancellation once"
  );
  assert(searchCancelCalls === 0, "an approved recorded voice cancellation must not execute search-only cancellation");
}

function parseExportUrl(filters: ReportListParams, format: ReportExportFormat) {
  return new URL(reportExportUrl(filters, format), "http://localhost");
}

function testReportExportUrlSupportsFormats() {
  const formats: ReportExportFormat[] = ["csv", "json", "geojson"];

  for (const format of formats) {
    const url = parseExportUrl({}, format);
    assert(url.pathname === "/api/reports/export", `${format} export path should target same-origin report gateway`);
    assertSearchParam(url, "format", format);
    assert(url.searchParams.get("limit") === null, `${format} export should not include list limit`);
  }
}

function testReportExportUrlIncludesFilters() {
  const url = parseExportUrl(
    {
      limit: 50,
      status: "reviewed",
      class_name: "damaged_tactile_block",
      source: "onnx",
      model_key: "custom_tactile",
      trigger: "auto",
      auto_reported: false,
      created_from: "2026-05-24T00:00:00Z",
      created_to: "2026-05-24T23:59:59Z",
      lat: "37.5665",
      lng: "126.978",
      radius_m: "300"
    },
    "json"
  );

  assertSearchParam(url, "format", "json");
  assertSearchParam(url, "status", "reviewed");
  assertSearchParam(url, "class_name", "damaged_tactile_block");
  assertSearchParam(url, "source", "onnx");
  assertSearchParam(url, "model_key", "custom_tactile");
  assertSearchParam(url, "trigger", "auto");
  assertSearchParam(url, "auto_reported", "false");
  assertSearchParam(url, "created_from", "2026-05-24T00:00:00Z");
  assertSearchParam(url, "created_to", "2026-05-24T23:59:59Z");
  assertSearchParam(url, "lat", "37.5665");
  assertSearchParam(url, "lng", "126.978");
  assertSearchParam(url, "radius_m", "300");
  assert(url.searchParams.get("limit") === null, "export URL should ignore list limit filter");
}

function testReportExportUrlOmitsEmptyFilters() {
  const url = parseExportUrl(
    {
      status: "",
      class_name: "",
      source: "",
      model_key: "",
      trigger: "",
      auto_reported: "",
      created_from: undefined,
      created_to: undefined,
      lat: undefined,
      lng: undefined,
      radius_m: undefined
    },
    "geojson"
  );

  assertSearchParam(url, "format", "geojson");
  assert(Array.from(url.searchParams.keys()).length === 1, "empty export filters should be omitted");
}

function point(latitude: number, longitude: number): RoutePoint {
  return { latitude, longitude };
}

function guide(instruction: string | null = "좌회전"): WalkingRouteGuidePoint {
  return {
    index: 1,
    point: point(37.0, 127.0),
    instruction,
    turn_type: null,
    point_type: "Point",
    distance_from_start_m: null,
    remaining_distance_m: null
  };
}

function guideFixture(overrides: Partial<WalkingRouteGuidePoint>): WalkingRouteGuidePoint {
  return {
    ...guide(null),
    ...overrides,
    point: overrides.point ?? guide().point
  };
}

function promptAt(distanceM: number, speedMps: number | null): NavigationGuidePrompt | null {
  return buildNavigationGuidePrompt({
    guide: guide(),
    distanceM,
    speedMps,
    routeId: "test-route"
  });
}

function testTurnTypeFallbackUsesRightTurn() {
  const prompt = buildNavigationGuidePrompt({
    guide: { ...guide(null), turn_type: 13 },
    distanceM: 3,
    speedMps: 1.2,
    routeId: "test-route"
  });

  assert(prompt?.prompt === "지금 우회전하세요.", "turn_type should drive speech when instruction is missing");
}

function testUnknownTurnTypeFallsBackToInstruction() {
  const prompt = buildNavigationGuidePrompt({
    guide: { ...guide("우회전"), turn_type: 999 },
    distanceM: 3,
    speedMps: 1.2,
    routeId: "test-route"
  });

  assert(prompt?.prompt === "지금 우회전하세요.", "unknown turn_type should fall back to instruction text");
}

function testMoveOnlyGuideIsNotSpoken() {
  const prompt = buildNavigationGuidePrompt({
    guide: { ...guide("73m 이동"), turn_type: 200, point_type: "SP" },
    distanceM: 0,
    speedMps: 1.2,
    routeId: "test-route"
  });

  assert(prompt === null, "start or move-only guide point should not create a turn prompt");
}

function testOfflineTimingFixturesCoverActionBoundaries() {
  const leftNow = buildNavigationGuidePrompt({
    guide: guideFixture({ index: 11, instruction: "좌회전", turn_type: null }),
    distanceM: WALKSAFE_GUIDE_TURN_RADIUS_M,
    speedMps: 1.2,
    routeId: "offline-fixture-route"
  });
  assert(leftNow?.stage === "now", "left turn fixture should speak now at turn radius boundary");
  assert(leftNow.prompt === "지금 좌회전하세요.", "left turn fixture should use now speech");

  const rightSoon = buildNavigationGuidePrompt({
    guide: guideFixture({ index: 12, instruction: null, turn_type: 13 }),
    distanceM: WALKSAFE_GUIDE_SOON_RADIUS_M,
    speedMps: null,
    routeId: "offline-fixture-route"
  });
  assert(rightSoon?.stage === "soon3", "right turn fixture should speak soon at soon radius boundary without speed");
  assert(rightSoon.prompt === "곧 우회전입니다. 약 12보 앞입니다.", "right turn fixture should use step-only soon speech");

  const crosswalkPrepare = buildNavigationGuidePrompt({
    guide: guideFixture({ index: 13, instruction: null, turn_type: 211 }),
    distanceM: 12,
    speedMps: 1.2,
    routeId: "offline-fixture-route"
  });
  assert(crosswalkPrepare?.stage === "prepare10", "crosswalk fixture should speak prepare at 10 second ETA boundary");
  assert(crosswalkPrepare.prompt === "10초 뒤 횡단보도입니다. 약 18보 앞입니다.", "crosswalk fixture should use crossing speech");

  const destinationPrepare = buildNavigationGuidePrompt({
    guide: guideFixture({ index: 14, instruction: null, turn_type: 201 }),
    distanceM: WALKSAFE_DEFAULT_STEP_LENGTH_M * 20,
    speedMps: null,
    routeId: "offline-fixture-route"
  });
  assert(destinationPrepare?.stage === "prepare10", "destination fixture should speak at 20 step boundary without speed");
  assert(destinationPrepare.prompt === "약 20보 앞에 목적지입니다.", "destination fixture should use arrival step speech");
}

function testSpeedEstimateClampsOutlier() {
  const previous = { point: point(37.0, 127.0), observedAtMs: 0, accuracyM: 5 };
  const next = { point: point(37.001, 127.0), observedAtMs: 1000, accuracyM: 5 };

  assert(estimateWalkingSpeedMps(previous, next) === null, "large GPS jump should be ignored");
}

function testSpeedEstimateUsesValidSample() {
  const previous = { point: point(37.0, 127.0), observedAtMs: 0, accuracyM: 5 };
  const next = { point: point(37.00001, 127.0), observedAtMs: 1000, accuracyM: 5 };

  const speed = estimateWalkingSpeedMps(previous, next);
  assert(speed !== null && speed > 1 && speed < 1.2, "valid GPS movement should estimate walking speed");
}

function testTenSecondBoundaryPromptUsesSecondsAndSteps() {
  const prompt = promptAt(12, 1.2);

  assert(prompt?.stage === "prepare10", "ETA exactly 10 seconds should create prepare prompt");
  assert(prompt.prompt.includes("10초 뒤 좌회전 준비"), "prepare prompt should prefer seconds");
  assert(prompt.prompt.includes("보 앞"), "prepare prompt should include steps");
}

function testSoonBoundaryBeatsPreparePrompt() {
  const prompt = promptAt(4.8, 1.6);

  assert(prompt?.stage === "soon3", "ETA exactly 3 seconds should create soon prompt when outside turn radius");
  assert(prompt.prompt.includes("곧 좌회전"), "soon prompt should be friendly");
}

function testNowBoundaryBeatsSoonPrompt() {
  const prompt = promptAt(4, 1.6);

  assert(prompt?.stage === "now", "turn radius boundary should use now prompt before soon prompt");
  assert(prompt.prompt === "지금 좌회전하세요.", "turn prompt should be immediate");
}

function testDefaultStepLengthPolicyUses065m() {
  const prompt = promptAt(13, null);

  assert(prompt?.stage === "prepare10", "20 default steps should create prepare prompt without speed");
  assert(prompt.steps === 20, "default step length should be 0.65m when env is unset or invalid");
  assert(prompt.prompt === "약 20보 앞에서 좌회전.", "step fallback prompt should use default step count");
}

function testRouteRequestGateAllowsFirstManualRequest() {
  const gate = resolveNavigationRouteRequestGate({
    requestInFlight: false,
    lastRequestStartedAtMs: null,
    nowMs: 1000
  });

  assert(gate.allowed, "first explicit navigation command should be allowed");
  assert(gate.message === null, "allowed route request should not have a blocking message");
}

function testNavigationStartRequiresKnownAccurateGps() {
  assert(
    navigationStartLocationError({ latitude: 37, longitude: 127, accuracy_m: null })?.includes("정확도 확인") ?? false,
    "navigation start must reject GPS without an accuracy estimate"
  );
  assert(
    navigationStartLocationError({ latitude: 37, longitude: 127, accuracy_m: 36 }, 35)?.includes("낮아") ?? false,
    "navigation start must reject GPS worse than its safety threshold"
  );
  assert(
    navigationStartLocationError({ latitude: 37, longitude: 127, accuracy_m: 8 }, 35) === null,
    "navigation start may use an accurate current fix"
  );
}

function testActiveTmapGuidanceRequiresAccurateGpsButNotHeading() {
  const ready = resolveActiveNavigationSensorGate({
    gps: { latitude: 37, longitude: 127, accuracy_m: 8 },
    maxGpsAccuracyM: 20
  });
  assert(ready.allowed, "accurate GPS should keep base TMAP guidance active without a heading");
  assert(
    resolveActiveNavigationSensorGate({
      gps: { latitude: 37, longitude: 127, accuracy_m: 21 },
      maxGpsAccuracyM: 20
    }).reason === "gps_accuracy_poor",
    "poor GPS must pause turn guidance"
  );
  const tactileWithoutHeading = evaluateTactileRoutePolicy({
    navigation_active: true,
    tmap_on_route: true,
    gps_accuracy_m: 8,
    tactile: {
      class_name: "normal_tactile_block",
      confidence: 0.9,
      stable_frames: 4,
      stable_ms: 900,
      age_ms: 50,
      route_heading_delta_deg: Number.POSITIVE_INFINITY,
      overlaps_tmap_corridor: true,
      center_x_normalized: 0.5
    }
  });
  assert(
    tactileWithoutHeading.mode === "tmap" && tactileWithoutHeading.reason === "route_heading_mismatch",
    "missing heading must block only the tactile override and fall back to TMAP"
  );
}

function testNextNavigationInstructionAnswersFromCurrentRouteState() {
  const readyGate = { allowed: true, reason: "ready", message: null } as const;
  assert(
    !resolveNextNavigationInstruction({
      navigationActive: false,
      sensorGate: readyGate,
      guide: null,
      distanceM: null,
      remainingToDestinationM: null
    }).ok,
    "a next-route query must not invent guidance without an active route"
  );
  const nextTurn = resolveNextNavigationInstruction({
    navigationActive: true,
    sensorGate: readyGate,
    guide: guide("좌회전"),
    distanceM: 13,
    remainingToDestinationM: 100,
    stepLengthM: 0.65
  });
  assert(nextTurn.ok && nextTurn.message.includes("약 20보 앞에서 좌회전"), "the query should answer the next actionable guide point");
  const paused = resolveNextNavigationInstruction({
    navigationActive: true,
    sensorGate: { allowed: false, reason: "gps_accuracy_poor", message: "GPS 정확도가 낮아 길안내를 일시 중지합니다." },
    guide: guide("우회전"),
    distanceM: 5,
    remainingToDestinationM: 100
  });
  assert(!paused.ok && paused.message.includes("GPS 정확도"), "the query must disclose a GPS-quality pause");
  const rerouting = resolveNextNavigationInstruction({
    navigationActive: true,
    rerouting: true,
    sensorGate: readyGate,
    guide: null,
    distanceM: null,
    remainingToDestinationM: 80
  });
  assert(
    !rerouting.ok && rerouting.message.includes("재탐색 중"),
    "a confirmed off-route state must suppress stale next-guide or destination speech"
  );
}

function testRouteRequestGateBlocksInFlightRequest() {
  const gate = resolveNavigationRouteRequestGate({
    requestInFlight: true,
    lastRequestStartedAtMs: null,
    nowMs: 1000
  });

  assert(!gate.allowed, "route request in progress should block duplicate live route request");
  assert(gate.message?.includes("이미 경로 요청 중") ?? false, "in-flight guard should explain duplicate route request");
}

function testRouteRequestGateBlocksCooldownRequest() {
  const gate = resolveNavigationRouteRequestGate({
    requestInFlight: false,
    lastRequestStartedAtMs: 1000,
    nowMs: 1000 + WALKSAFE_ROUTE_REQUEST_COOLDOWN_MS - 1000
  });

  assert(!gate.allowed, "manual reroute should respect route request cooldown");
  assert(gate.remainingCooldownMs === 1000, "cooldown gate should report remaining wait time");
  assert(gate.message === "재탐색은 1초 후 다시 시도해 주세요.", "cooldown message should be user-safe");
}

function testRouteRequestGateAllowsAfterCooldown() {
  const gate = resolveNavigationRouteRequestGate({
    requestInFlight: false,
    lastRequestStartedAtMs: 1000,
    nowMs: 1000 + WALKSAFE_ROUTE_REQUEST_COOLDOWN_MS
  });

  assert(gate.allowed, "manual reroute should be allowed after cooldown");
}

function testRouteRequestGenerationRejectsStaleOrAbortedResponses() {
  assert(
    isNavigationRouteRequestCurrent({ expectedSequence: 4, currentSequence: 4, aborted: false }),
    "the latest non-aborted route response should remain current"
  );
  assert(
    !isNavigationRouteRequestCurrent({ expectedSequence: 4, currentSequence: 5, aborted: false }),
    "a stop, destination change, or arrival generation change must stale the previous response"
  );
  assert(
    !isNavigationRouteRequestCurrent({ expectedSequence: 4, currentSequence: 4, aborted: true }),
    "an aborted route response must not become active"
  );
}

function autoRerouteBase(overrides: Partial<Parameters<typeof resolveAutoRerouteDecision>[0]> = {}) {
  return resolveAutoRerouteDecision({
    confirmedOffRoute: true,
    destination: point(37.002, 127.0),
    currentGpsSample: { point: point(37.00001, 127.0), observedAtMs: 2000, accuracyM: 8 },
    previousGpsSample: { point: point(37.0, 127.0), observedAtMs: 1000, accuracyM: 8 },
    requestInFlight: false,
    lastRequestStartedAtMs: null,
    nowMs: 3000,
    autoRerouteCount: 0,
    maxAutoReroutes: 2,
    cooldownMs: 10000,
    maxAccuracyM: 35,
    maxGpsJumpM: 50,
    ...overrides
  });
}

function testAutoRerouteAllowsStableConfirmedOffRoute() {
  const decision = autoRerouteBase();

  assert(decision.allowed, "stable confirmed off-route should allow automatic live reroute");
  assert(decision.reason === "allowed", "allowed auto reroute should expose allowed reason");
}

function testAutoRerouteBlocksPoorGpsAccuracy() {
  const decision = autoRerouteBase({
    currentGpsSample: { point: point(37.00001, 127.0), observedAtMs: 2000, accuracyM: 80 }
  });

  assert(!decision.allowed, "poor GPS accuracy must block automatic live reroute");
  assert(decision.reason === "gps_accuracy_poor", "poor GPS accuracy should expose reason");
}

function testAutoRerouteBlocksUnknownGpsAccuracy() {
  const decision = autoRerouteBase({
    currentGpsSample: { point: point(37.00001, 127.0), observedAtMs: 2000, accuracyM: null }
  });

  assert(!decision.allowed, "unknown GPS accuracy must block automatic live reroute");
  assert(decision.reason === "gps_accuracy_unknown", "unknown GPS accuracy should expose reason");
}

function testTmapProviderLabelAndFutureRoiMessages() {
  assert(WALKING_ROUTE_PROVIDER_LABEL === "TMAP", "walking routes should use the single TMAP provider label");
  assert(
    describeFutureRouteRoiState("outside_future_roi").includes("ROI 밖"),
    "outside future ROI should be visible instead of a generic waiting state"
  );
  assert(
    describeFutureRouteRoiState("missing_motion").includes("위치와 방향"),
    "future ROI waiting should explain the missing inputs"
  );
}

function testAutoRerouteBlocksGpsJump() {
  const decision = autoRerouteBase({
    currentGpsSample: { point: point(37.002, 127.0), observedAtMs: 2000, accuracyM: 8 }
  });

  assert(!decision.allowed, "GPS jump must block automatic live reroute");
  assert(decision.reason === "gps_jump", "GPS jump should expose reason");
  assert(decision.gpsJumpM !== null && decision.gpsJumpM > 50, "GPS jump distance should be reported");
}

function testAutoRerouteBlocksInFlightCooldownAndMaxCount() {
  assert(
    autoRerouteBase({ requestInFlight: true }).reason === "request_in_flight",
    "in-flight route request should block duplicate auto reroute"
  );
  assert(
    autoRerouteBase({ lastRequestStartedAtMs: 2500, nowMs: 3000 }).reason === "cooldown",
    "recent route request should block auto reroute cooldown"
  );
  assert(
    autoRerouteBase({ autoRerouteCount: 2 }).reason === "max_count",
    "auto reroute should stop after max count"
  );
}


function testRiskVoiceHasPriorityOverNavigationGuidance() {
  const state = resolveVoiceFeedbackState({
    speechEnabled: true,
    riskActive: true,
    navigationSpeechPrompt: "지금 좌회전하세요."
  });

  assert(state === "risk_alert", "active risk should keep risk alert priority over navigation speech");
}

function testNavigationVoicePlaysOnlyWhenNoRisk() {
  const state = resolveVoiceFeedbackState({
    speechEnabled: true,
    riskActive: false,
    navigationSpeechPrompt: "지금 좌회전하세요."
  });

  assert(state === "navigation_guidance", "navigation speech should play when speech is enabled and no risk is active");
}

function testBlockedNavigationSpeechDoesNotConsumeCooldown() {
  const previous = { key: "", time: 0 };
  const blocked = commitNavigationSpeechDelivery(previous, "guide:left", 1000, false);
  assert(blocked === previous, "a priority-blocked route prompt must not be marked delivered");
  assert(
    shouldAttemptNavigationSpeech(blocked, "guide:left", 1001, 10000),
    "a blocked route prompt should be eligible for immediate retry"
  );
  const delivered = commitNavigationSpeechDelivery(blocked, "guide:left", 1001, true);
  assert(
    !shouldAttemptNavigationSpeech(delivered, "guide:left", 1002, 10000),
    "only an accepted route prompt should start its delivery cooldown"
  );
}

function testSameNavigationSpeechKeySucceedsAfterPriorityBlockClears() {
  const initial = { key: "", time: 0 };
  let blocked = true;
  const first = attemptNavigationSpeechDelivery({
    lastDelivery: initial,
    key: "guide:right",
    nowMs: 1000,
    cooldownMs: 10000,
    deliver: () => !blocked
  });
  blocked = false;
  const retry = attemptNavigationSpeechDelivery({
    lastDelivery: first.delivery,
    key: "guide:right",
    nowMs: 1400,
    cooldownMs: 10000,
    deliver: () => !blocked
  });

  assert(first.attempted && !first.delivered, "the first priority-blocked attempt should remain undelivered");
  assert(retry.delivered, "the same prompt key should succeed after the priority block clears");
  assert(retry.delivery.key === "guide:right" && retry.delivery.time === 1400, "the successful retry should start cooldown");
}

function testSpeechPriorityIsRiskThenInteractionThenNavigation() {
  assert(!shouldPreemptSpeech("navigation", "advisory"), "camera advisory must not interrupt active TMAP speech");
  assert(shouldPreemptSpeech("advisory", "navigation"), "new TMAP speech must interrupt a camera advisory");
  assert(
    !shouldStopSpeechOwnedByDelivery("navigation", "advisory"),
    "cancelling an advisory retry must not stop active TMAP speech"
  );
  assert(
    shouldStopSpeechOwnedByDelivery("advisory", "advisory"),
    "cancelling an advisory that owns speech must stop that speech"
  );
  assert(
    shouldStopSpeechOwnedByDelivery("risk"),
    "existing metric risk deliveries must retain risk as their default speech owner"
  );
  assert(shouldBlockSpeechDuringRecognition(true, "advisory"), "camera advisory speech must wait while voice recognition is active");
  assert(!shouldBlockSpeechDuringRecognition(true, "risk"), "metric risk speech must retain its urgent recognition override");
  assert(shouldPreemptSpeech("navigation", "interaction"), "voice interaction should interrupt route guidance");
  assert(shouldPreemptSpeech("interaction", "risk"), "risk speech should interrupt voice interaction");
  assert(!shouldPreemptSpeech("risk", "navigation"), "route guidance must not interrupt a risk warning");
  assert(
    resolveVoiceFeedbackState({
      speechEnabled: true,
      riskActive: false,
      voiceInteractionActive: true,
      navigationSpeechPrompt: "지금 좌회전하세요."
    }) === "voice_interaction",
    "active voice interaction should suppress navigation speech"
  );
}

function testBrowserTtsSchedulerSuppressesSttEchoAndPreemptsForRisk() {
  const hadWindow = Reflect.has(globalThis, "window");
  const previousWindow = Reflect.get(globalThis, "window");
  const hadUtterance = Reflect.has(globalThis, "SpeechSynthesisUtterance");
  const previousUtterance = Reflect.get(globalThis, "SpeechSynthesisUtterance");
  let urgentEvents = 0;
  let spokenCount = 0;
  const spokenUtterances: Array<{ onstart?: (() => void) | null; onend?: (() => void) | null }> = [];
  const testWindow = Object.assign(new EventTarget(), {
    speechSynthesis: {
      cancel: () => undefined,
      speak: (utterance: { onstart?: (() => void) | null; onend?: (() => void) | null }) => {
        spokenCount += 1;
        spokenUtterances.push(utterance);
      }
    }
  });
  testWindow.addEventListener(WALKSAFE_URGENT_SPEECH_EVENT, () => {
    urgentEvents += 1;
  });
  class MockUtterance {
    lang = "";
    rate = 1;
    onstart: (() => void) | null = null;
    onend: (() => void) | null = null;
    onerror: (() => void) | null = null;
    constructor(public readonly text: string) {}
  }
  Reflect.set(globalThis, "window", testWindow);
  Reflect.set(globalThis, "SpeechSynthesisUtterance", MockUtterance);
  try {
    assert(speak("길안내", "navigation"), "navigation TTS should play when idle");
    spokenUtterances.at(-1)?.onstart?.();
    assert(getSpeechOutputStatus() === "available", "a started utterance must confirm browser TTS availability");
    setSpeechRecognitionActive(true);
    assert(!speak("음성 응답", "interaction"), "non-risk TTS must stay silent while the microphone/STT session is active");
    assert(speak("위험 경고", "risk"), "risk TTS must preempt an active microphone/STT session");
    assert(urgentEvents === 1, "risk TTS must emit one recognition-interrupt event");
    assert(!speak("다시 길안내", "navigation"), "navigation TTS must not interrupt an active risk warning");
    spokenUtterances.at(-1)?.onend?.();
    setSpeechRecognitionActive(false);
    assert(speak("최근 상태 반복", "interaction"), "repeat-last browser TTS should play after the risk warning ends");
    assert(spokenCount === 3, "only navigation, risk, and repeat-last utterances should reach browser TTS");
  } finally {
    setSpeechRecognitionActive(false);
    stopSpeaking();
    if (hadWindow) Reflect.set(globalThis, "window", previousWindow);
    else Reflect.deleteProperty(globalThis, "window");
    if (hadUtterance) Reflect.set(globalThis, "SpeechSynthesisUtterance", previousUtterance);
    else Reflect.deleteProperty(globalThis, "SpeechSynthesisUtterance");
  }
}

function testBrowserTtsFailurePublishesFallbackStatus() {
  const hadWindow = Reflect.has(globalThis, "window");
  const previousWindow = Reflect.get(globalThis, "window");
  const hadUtterance = Reflect.has(globalThis, "SpeechSynthesisUtterance");
  const previousUtterance = Reflect.get(globalThis, "SpeechSynthesisUtterance");
  const spokenUtterances: Array<{
    onstart?: (() => void) | null;
    onerror?: (() => void) | null;
  }> = [];
  let statusEvents = 0;
  const testWindow = Object.assign(new EventTarget(), {
    speechSynthesis: {
      cancel: () => undefined,
      speak: (utterance: { onstart?: (() => void) | null; onerror?: (() => void) | null }) => {
        spokenUtterances.push(utterance);
      }
    }
  });
  testWindow.addEventListener(WALKSAFE_SPEECH_OUTPUT_STATUS_EVENT, () => {
    statusEvents += 1;
  });
  class MockUtterance {
    lang = "";
    rate = 1;
    onstart: (() => void) | null = null;
    onend: (() => void) | null = null;
    onerror: (() => void) | null = null;
    constructor(public readonly text: string) {}
  }
  Reflect.set(globalThis, "window", testWindow);
  Reflect.set(globalThis, "SpeechSynthesisUtterance", MockUtterance);
  try {
    assert(speak("음성 상태 확인", "interaction"), "supported browser TTS should accept an utterance");
    spokenUtterances.at(-1)?.onstart?.();
    assert(getSpeechOutputStatus() === "available", "onstart must publish an available speech status");

    assert(speak("비동기 실패", "risk"), "an async failure occurs after the browser accepts an utterance");
    spokenUtterances.at(-1)?.onerror?.();
    assert(getSpeechOutputStatus() === "failed", "utterance.onerror must activate the accessible fallback");

    Reflect.deleteProperty(testWindow, "speechSynthesis");
    assert(!speak("미지원", "interaction"), "missing speechSynthesis must fail synchronously");
    assert(getSpeechOutputStatus() === "unavailable", "missing speechSynthesis must publish unavailable");

    Reflect.set(testWindow, "speechSynthesis", {
      cancel: () => undefined,
      speak: () => {
        throw new Error("synthetic browser failure");
      }
    });
    assert(!speak("동기 실패", "interaction"), "a synchronous browser error must fail closed");
    assert(getSpeechOutputStatus() === "failed", "a synchronous browser error must publish failed");
    assert(statusEvents >= 3, "speech liveness changes must notify the React fallback subscriber");
  } finally {
    stopSpeaking();
    if (hadWindow) Reflect.set(globalThis, "window", previousWindow);
    else Reflect.deleteProperty(globalThis, "window");
    if (hadUtterance) Reflect.set(globalThis, "SpeechSynthesisUtterance", previousUtterance);
    else Reflect.deleteProperty(globalThis, "SpeechSynthesisUtterance");
  }
}

function testBrowserTtsStartTimeoutReleasesSpeechPriority() {
  const hadWindow = Reflect.has(globalThis, "window");
  const previousWindow = Reflect.get(globalThis, "window");
  const hadUtterance = Reflect.has(globalThis, "SpeechSynthesisUtterance");
  const previousUtterance = Reflect.get(globalThis, "SpeechSynthesisUtterance");
  const previousSetTimeout = globalThis.setTimeout;
  const previousClearTimeout = globalThis.clearTimeout;
  let deadline: (() => void) | null = null;
  let cancelCount = 0;
  const testWindow = Object.assign(new EventTarget(), {
    speechSynthesis: {
      cancel: () => {
        cancelCount += 1;
      },
      speak: () => undefined
    }
  });
  class MockUtterance {
    lang = "";
    rate = 1;
    onstart: (() => void) | null = null;
    onend: (() => void) | null = null;
    onerror: (() => void) | null = null;
    constructor(public readonly text: string) {}
  }
  Reflect.set(globalThis, "window", testWindow);
  Reflect.set(globalThis, "SpeechSynthesisUtterance", MockUtterance);
  Reflect.set(globalThis, "setTimeout", (callback: () => void) => {
    deadline = callback;
    return 1;
  });
  Reflect.set(globalThis, "clearTimeout", () => undefined);
  try {
    assert(speak("위험 경고", "risk"), "browser TTS should accept the risk utterance before its callback deadline");
    assert(deadline !== null, "accepted browser TTS must arm its start deadline");
    (deadline as () => void)();
    assert(getSpeechOutputStatus() === "failed", "a missing browser callback must publish failed speech status");
    assert(cancelCount >= 2, "the watchdog must cancel the stalled browser utterance");
    assert(speak("길안내 재개", "navigation"), "the watchdog must release stale risk priority for later guidance");
  } finally {
    stopSpeaking();
    Reflect.set(globalThis, "setTimeout", previousSetTimeout);
    Reflect.set(globalThis, "clearTimeout", previousClearTimeout);
    if (hadWindow) Reflect.set(globalThis, "window", previousWindow);
    else Reflect.deleteProperty(globalThis, "window");
    if (hadUtterance) Reflect.set(globalThis, "SpeechSynthesisUtterance", previousUtterance);
    else Reflect.deleteProperty(globalThis, "SpeechSynthesisUtterance");
  }
}

function testBrowserTtsTerminalTimeoutAndStaleCallbacksCannotOwnNewSpeech() {
  const hadWindow = Reflect.has(globalThis, "window");
  const previousWindow = Reflect.get(globalThis, "window");
  const hadUtterance = Reflect.has(globalThis, "SpeechSynthesisUtterance");
  const previousUtterance = Reflect.get(globalThis, "SpeechSynthesisUtterance");
  const previousSetTimeout = globalThis.setTimeout;
  const previousClearTimeout = globalThis.clearTimeout;
  const deadlines: Array<{ callback: () => void; delay: number; cleared: boolean }> = [];
  const spokenUtterances: Array<{
    onstart?: (() => void) | null;
    onend?: (() => void) | null;
    onerror?: (() => void) | null;
  }> = [];
  let arbitrationEvents = 0;
  let supersededFailures = 0;
  let terminalFailures = 0;
  let cancelledDeliveries = 0;
  const testWindow = Object.assign(new EventTarget(), {
    speechSynthesis: {
      cancel: () => undefined,
      speak: (utterance: (typeof spokenUtterances)[number]) => spokenUtterances.push(utterance)
    }
  });
  testWindow.addEventListener(WALKSAFE_SPEECH_ARBITRATION_STATUS_EVENT, () => {
    arbitrationEvents += 1;
  });
  class MockUtterance {
    lang = "";
    rate = 1;
    onstart: (() => void) | null = null;
    onend: (() => void) | null = null;
    onerror: (() => void) | null = null;
    constructor(public readonly text: string) {}
  }
  Reflect.set(globalThis, "window", testWindow);
  Reflect.set(globalThis, "SpeechSynthesisUtterance", MockUtterance);
  Reflect.set(globalThis, "setTimeout", (callback: () => void, delay = 0) => {
    deadlines.push({ callback, delay, cleared: false });
    return deadlines.length;
  });
  Reflect.set(globalThis, "clearTimeout", (deadlineId: number) => {
    const deadline = deadlines[deadlineId - 1];
    if (deadline) deadline.cleared = true;
  });
  try {
    assert(speak("기존 상호작용", "interaction", { onFailure: () => { supersededFailures += 1; } }), "interaction speech should start");
    const staleUtterance = spokenUtterances.at(-1);
    staleUtterance?.onstart?.();

    assert(
      speak("가".repeat(350), "risk", { onFailure: () => { terminalFailures += 1; } }),
      "risk speech should preempt the interaction"
    );
    assert(supersededFailures === 1, "preemption must notify the superseded delivery owner once");
    const currentUtterance = spokenUtterances.at(-1);
    const currentStartDeadline = deadlines.at(-1);
    staleUtterance?.onend?.();
    assert(!currentStartDeadline?.cleared, "a stale onend must not clear the new utterance start deadline");

    currentUtterance?.onstart?.();
    const terminalDeadline = deadlines.at(-1);
    assert((terminalDeadline?.delay ?? 0) >= 92_500, "a 350-character prompt needs a length-aware terminal deadline");
    staleUtterance?.onend?.();
    assert(!terminalDeadline?.cleared, "a stale onend must not clear the new utterance terminal deadline");
    terminalDeadline?.callback();
    assert(terminalFailures === 1, "a missing terminal callback must notify the delivery owner once");
    assert(getSpeechOutputStatus() === "failed", "terminal timeout must activate the accessible fallback");
    assert(getActiveSpeechPriority() === null, "terminal timeout must release speech arbitration ownership");
    assert(
      speak("길안내 재개", "navigation", { onCancel: () => { cancelledDeliveries += 1; } }),
      "navigation must resume after a terminal timeout"
    );
    assert(arbitrationEvents >= 4, "speech owner changes must notify arbitration subscribers");
    stopSpeaking();
    assert(cancelledDeliveries === 1, "intentional stop must release the delivery owner without a failure retry");
  } finally {
    stopSpeaking();
    Reflect.set(globalThis, "setTimeout", previousSetTimeout);
    Reflect.set(globalThis, "clearTimeout", previousClearTimeout);
    if (hadWindow) Reflect.set(globalThis, "window", previousWindow);
    else Reflect.deleteProperty(globalThis, "window");
    if (hadUtterance) Reflect.set(globalThis, "SpeechSynthesisUtterance", previousUtterance);
    else Reflect.deleteProperty(globalThis, "SpeechSynthesisUtterance");
  }
}

function testSpeechRecognitionStatePublishesOnlyRealTransitions() {
  const hadWindow = Reflect.has(globalThis, "window");
  const previousWindow = Reflect.get(globalThis, "window");
  const testWindow = new EventTarget();
  let transitions = 0;
  testWindow.addEventListener(WALKSAFE_SPEECH_RECOGNITION_STATUS_EVENT, () => {
    transitions += 1;
  });
  Reflect.set(globalThis, "window", testWindow);
  try {
    setSpeechRecognitionActive(true);
    setSpeechRecognitionActive(true);
    assert(getSpeechRecognitionActive(), "the recognition getter must expose the active microphone session");
    setSpeechRecognitionActive(false);
    assert(!getSpeechRecognitionActive(), "the recognition getter must expose the closed microphone session");
    assert(transitions === 2, "duplicate recognition states must not create retry-driving events");
  } finally {
    setSpeechRecognitionActive(false);
    if (hadWindow) Reflect.set(globalThis, "window", previousWindow);
    else Reflect.deleteProperty(globalThis, "window");
  }
}

function testTtsFailureRendersOrderedLiveFallback() {
  const riskMarkup = renderToStaticMarkup(
    createElement(AssistiveAnnouncement, {
      enabled: true,
      riskActive: true,
      riskMessage: "위험 경고",
      interactionMessage: "음성 상호작용",
      navigationMessage: "경로 안내"
    })
  );
  assert(riskMarkup.includes('role="alert"'), "risk fallback must use an alert role");
  assert(riskMarkup.includes('aria-live="assertive"'), "risk fallback must be assertive");
  assert(riskMarkup.includes("위험 경고"), "risk fallback must announce the active hazard");
  assert(!riskMarkup.includes("음성 상호작용"), "risk fallback must preempt interaction output");

  const interactionMarkup = renderToStaticMarkup(
    createElement(AssistiveAnnouncement, {
      enabled: true,
      riskActive: false,
      riskMessage: "위험 경고",
      interactionMessage: "음성 상호작용",
      navigationMessage: "경로 안내"
    })
  );
  assert(interactionMarkup.includes('role="status"'), "non-risk fallback must use a status role");
  assert(interactionMarkup.includes('aria-live="polite"'), "non-risk fallback must be polite");
  assert(interactionMarkup.includes("음성 상호작용"), "interaction fallback must precede navigation");
  assert(!interactionMarkup.includes("경로 안내"), "only the highest-priority fallback message may be live");

  const navigationMarkup = renderToStaticMarkup(
    createElement(AssistiveAnnouncement, {
      enabled: true,
      riskActive: false,
      riskMessage: "위험 경고",
      interactionMessage: null,
      navigationMessage: "경로 안내"
    })
  );
  assert(navigationMarkup.includes("경로 안내"), "idle interaction state must yield the live region to navigation");

  const assembledRiskMarkup = renderProductionAssistPanel({
    riskActive: true,
    detectionLabel: "차량 접근"
  });
  assert(
    assembledRiskMarkup.includes('role="alert"') &&
      assembledRiskMarkup.includes('aria-live="assertive"'),
    "the production AssistPanel must assemble an assertive live risk fallback when TTS fails"
  );
  assert(
    assembledRiskMarkup.includes("현재 위험. 잠시 멈추고 주변을 확인하세요. 차량 접근."),
    "the production risk fallback must tell the pedestrian what to do"
  );

  const detectorOutageMessage = "실시간 장애물 탐지 오류입니다. 전방을 직접 확인해 주세요.";
  const detectorOutageMarkup = renderProductionAssistPanel({
    riskActive: false,
    detectionAvailability: "error",
    speechOutputStatus: "available",
    detectionSafetyAlertMessage: detectorOutageMessage,
    detectionSafetyFallbackRequired: true
  });
  assert(
    detectorOutageMarkup.includes('role="alert"') &&
      detectorOutageMarkup.includes('aria-live="assertive"'),
    "an unresolved detector outage must remain an assertive production fallback"
  );
  assert(
    detectorOutageMarkup.includes(detectorOutageMessage),
    "the production AssistPanel must render the detector safety message"
  );
  assert(
    detectorOutageMarkup.includes("화면 읽기 안내를 유지합니다."),
    "per-alert fallback must override a globally available speech status"
  );

  const assembledNavigationMarkup = renderProductionAssistPanel({
    navigationActive: true,
    navigationInstructionText: "50미터 앞에서 좌회전",
    navigationStatusText: "경로 안내 중"
  });
  assert(
    assembledNavigationMarkup.includes("50미터 앞에서 좌회전. 경로 안내 중"),
    "the production AssistPanel must assemble the navigation live fallback when TTS fails"
  );

  const disabledMarkup = renderToStaticMarkup(
    createElement(AssistiveAnnouncement, {
      enabled: false,
      riskActive: true,
      riskMessage: "위험 경고",
      interactionMessage: null,
      navigationMessage: null
    })
  );
  assert(disabledMarkup === "", "confirmed TTS delivery must disable the duplicate live fallback");
}

function testRiskStatusHasPriorityOverNavigationStatus() {
  assert(
    !shouldApplyNavigationStatusMessage("길안내 상태. 경로 안내 중.", true),
    "navigation status should not overwrite active risk status"
  );
  assert(
    shouldApplyNavigationStatusMessage("길안내 상태. 경로 안내 중.", false),
    "navigation status can update when no risk is active"
  );
}

function testNonMetricCameraAdvisoryIsVisibleAndKeepsItsAccessibleWording() {
  const message = "카메라 기준 왼쪽에 점자블록이 감지됐습니다. TMAP 길 안내를 기준으로 주변을 확인하세요.";
  const markup = renderProductionAssistPanel({
    riskActive: false,
    activeV2RiskDetection: {
      schema_version: "detect.v2",
      model_key: "unified_walksafe",
      source_model: "policy-test",
      model_class_id: 7,
      class_name: "normal_tactile_block",
      category: "tactile_normal",
      confidence: 0.82,
      bbox: { x: 0.1, y: 0.5, width: 0.2, height: 0.3 },
      threshold_used: 0.35,
      captured_at: "2026-05-23T06:00:00.000Z"
    },
    detectionLabel: "정상 점자블록",
    navigationActive: true,
    nonMetricAdvisoryCapabilityLabel: "카메라 보조 경고 · TMAP 경로 유지",
    nonMetricAdvisoryActive: true,
    nonMetricAdvisoryMessage: message
  });

  assert(markup.includes("카메라 보조 경고 · TMAP 경로 유지"), "degraded camera capability label must be visible");
  assert(markup.includes(message), "screen-reader fallback must preserve the conservative camera-relative wording");
  assert(!markup.includes("위험 요소 없음"), "an active camera advisory must not simultaneously claim that no hazard exists");
  assert(!markup.includes("현재 위험. 잠시 멈추고"), "non-metric advisory must not inherit the metric risk fallback command");
}

function testBackgroundTransitionCancelsRecordingAndPendingStt() {
  assert(
    shouldCancelVoiceSessionForVisibility("hidden", "recording"),
    "hidden page must cancel an active voice recording before it can execute a partial command"
  );
  assert(
    shouldCancelVoiceSessionForVisibility("hidden", "uploading"),
    "hidden page must invalidate an in-flight STT result before it can execute a command"
  );
  assert(
    !shouldCancelVoiceSessionForVisibility("visible", "recording"),
    "visible active recording should continue"
  );
}

function testForegroundRequiresExplicitAssistResume() {
  const paused = transitionAssistSessionLifecycle("active", "background");
  assert(paused === "paused", "backgrounding should privacy-pause the assist session");
  assert(
    transitionAssistSessionLifecycle(paused, "foreground") === "paused",
    "foregrounding alone must not reopen camera, GPS or microphone"
  );
  assert(
    transitionAssistSessionLifecycle(paused, "user_resume") === "active",
    "an explicit user action should make the assist session active again"
  );
}

function testActiveRiskBlocksAndInterruptsVoiceRecognition() {
  assert(shouldBlockVoiceRecordingForSafety(true), "an active risk must block a new microphone session");
  assert(!shouldBlockVoiceRecordingForSafety(false), "voice recognition may start when no safety alert is active");
}

function testTactileLocalSpeechPrecedesOrdinaryTmapGuide() {
  const tactile = { prompt: "점자블록을 따라 직진하세요.", key: "tactile" };
  const guide = { prompt: "50m 앞 좌회전", key: "guide" };
  assert(
    resolveNavigationSpeechCandidate({
      reroute: null,
      routeStart: null,
      sensorPause: null,
      tactileLocal: tactile,
      tmapGuide: guide
    }) === tactile,
    "an admitted tactile local route must speak before an ordinary TMAP guide"
  );
}

function testSensorPausePrecedesRouteStartSpeech() {
  const sensorPause = { prompt: "GPS 정확도를 확인할 때까지 길안내를 일시 중지합니다.", key: "sensor-pause" };
  const routeStart = { prompt: "길안내를 시작합니다.", key: "route-start" };
  assert(
    resolveNavigationSpeechCandidate({
      reroute: null,
      routeStart,
      sensorPause,
      tactileLocal: null,
      tmapGuide: null
    }) === sensorPause,
    "a newly unsafe GPS state must suppress a retained route-start prompt"
  );
}

async function main() {
  testDestinationSearchKeepsActiveRouteUntilSelection();
  testDestinationSearchCancelDoesNotCancelExistingNavigation();
  testProductionNavigationButtonsInvokeTheirWiredActions();
  await testVoiceHookForwardsFullDestinationCancellation();
  await testHomeVoiceCancelExecutesFullProductionCancellation();
  testRiskVoiceHasPriorityOverNavigationGuidance();
  testNavigationVoicePlaysOnlyWhenNoRisk();
  testBlockedNavigationSpeechDoesNotConsumeCooldown();
  testSameNavigationSpeechKeySucceedsAfterPriorityBlockClears();
  testSpeechPriorityIsRiskThenInteractionThenNavigation();
  testBrowserTtsSchedulerSuppressesSttEchoAndPreemptsForRisk();
  testBrowserTtsFailurePublishesFallbackStatus();
  testBrowserTtsStartTimeoutReleasesSpeechPriority();
  testBrowserTtsTerminalTimeoutAndStaleCallbacksCannotOwnNewSpeech();
  testSpeechRecognitionStatePublishesOnlyRealTransitions();
  testTtsFailureRendersOrderedLiveFallback();
  testRiskStatusHasPriorityOverNavigationStatus();
  testNonMetricCameraAdvisoryIsVisibleAndKeepsItsAccessibleWording();
  testBackgroundTransitionCancelsRecordingAndPendingStt();
  testForegroundRequiresExplicitAssistResume();
  testActiveRiskBlocksAndInterruptsVoiceRecognition();
  testTactileLocalSpeechPrecedesOrdinaryTmapGuide();
  testSensorPausePrecedesRouteStartSpeech();
  testReportExportUrlSupportsFormats();
  testReportExportUrlIncludesFilters();
  testReportExportUrlOmitsEmptyFilters();
  testSpeedEstimateClampsOutlier();
  testSpeedEstimateUsesValidSample();
  testTenSecondBoundaryPromptUsesSecondsAndSteps();
  testSoonBoundaryBeatsPreparePrompt();
  testNowBoundaryBeatsSoonPrompt();
  testDefaultStepLengthPolicyUses065m();
  testNavigationStartRequiresKnownAccurateGps();
  testActiveTmapGuidanceRequiresAccurateGpsButNotHeading();
  testNextNavigationInstructionAnswersFromCurrentRouteState();
  testRouteRequestGateAllowsFirstManualRequest();
  testRouteRequestGateBlocksInFlightRequest();
  testRouteRequestGateBlocksCooldownRequest();
  testRouteRequestGateAllowsAfterCooldown();
  testRouteRequestGenerationRejectsStaleOrAbortedResponses();
  testAutoRerouteAllowsStableConfirmedOffRoute();
  testAutoRerouteBlocksPoorGpsAccuracy();
  testAutoRerouteBlocksUnknownGpsAccuracy();
  testAutoRerouteBlocksGpsJump();
  testAutoRerouteBlocksInFlightCooldownAndMaxCount();
  testTurnTypeFallbackUsesRightTurn();
  testUnknownTurnTypeFallsBackToInstruction();
  testMoveOnlyGuideIsNotSpoken();
  testOfflineTimingFixturesCoverActionBoundaries();
  testTmapProviderLabelAndFutureRoiMessages();
  console.log("navigation guidance, voice priority, and report export policy checks passed");
}

void main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
