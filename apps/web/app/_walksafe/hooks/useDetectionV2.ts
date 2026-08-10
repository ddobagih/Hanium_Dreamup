"use client";

/**
 * Runs either fake-v2 presentation or serialized server-v2 frame inference and publishes one shared state.
 * Server results expire quickly, while fake results never trigger persistent report submission.
 */
import { useCallback, useEffect, useRef, type Dispatch, type RefObject, type SetStateAction } from "react";
import { advanceAutoReportV2Gate, type AutoReportV2GateState } from "@/lib/auto-report-v2";
import { DETECT_V2_SAFETY_DEADLINE_MS, DetectV2ApiError, detectFrameV2 } from "@/lib/detect-api-v2";
import { createFakeTwoModelDetections, labelForTwoModelDetection } from "@/lib/detector-v2";
import { selectTwoModelPriorityDetections } from "@/lib/two-model-priority";
import type { DetectionEvent } from "@/types/inference";
import type { DetectV2RequestAudit, GpsFixV2, TwoModelDetection } from "@/types/inference-v2";
import type { DepthEstimateResult } from "../depth-estimator";
import type { DetectionAvailability } from "../detection-availability";
import { CameraFrameUnavailableError } from "../camera-policy";
import { advanceDetectionPresence, type DetectionPresenceState } from "../detection-presence-policy";
import {
  isServerV2FrameProcessingAllowed,
  isServerV2ReportStorageAllowed,
  type ServerV2PrivacyConsent
} from "../server-v2-privacy";
import {
  IS_FAKE_V2_MODE,
  IS_SERVER_V2_MODE,
  SERVER_DETECT_INTERVAL_MS,
  type AutoReportV2Status
} from "../config";

type ReportState = "idle" | "sending" | "sent" | "error";
export type DetectionV2AuditCallback = (audit: DetectV2RequestAudit) => void;

type SubmitV2ReportOptions = {
  capturedAt?: string;
  image?: Blob;
  gps?: GpsFixV2 | null;
  heading?: number | null;
  signal?: AbortSignal;
  shouldAbort?: () => boolean;
};

export type UseDetectionV2Options = {
  cameraReady: boolean;
  serverV2FrameProcessingAllowed: boolean;
  serverV2ReportStorageAllowed: boolean;
  serverV2PrivacyConsentRef: RefObject<ServerV2PrivacyConsent>;
  gps: GpsFixV2 | null;
  heading: number | null;
  captureFrame: () => Promise<Blob>;
  estimateDepthForDetection?: (detection: TwoModelDetection) => DepthEstimateResult | null;
  onDetectionV2Audit?: DetectionV2AuditCallback;
  onInferenceFrame?: (detections: TwoModelDetection[], capturedAt: string) => void;
  onDetectionFrame?: (
    detections: TwoModelDetection[],
    image: Blob,
    capturedAt: string,
    gps: GpsFixV2 | null,
    heading: number | null
  ) => void;
  detectionIndexRef: RefObject<number>;
  reportStateRef: RefObject<ReportState>;
  submitV2ReportTarget: (
    target: TwoModelDetection | null,
    trigger: "auto",
    options: SubmitV2ReportOptions
  ) => Promise<boolean>;
  setAutoReportV2State: (status: AutoReportV2Status, message?: string) => void;
  setDetection: Dispatch<SetStateAction<DetectionEvent | null>>;
  setV2Detections: Dispatch<SetStateAction<TwoModelDetection[]>>;
  setV2Primary: Dispatch<SetStateAction<TwoModelDetection | null>>;
  setV2Secondary: Dispatch<SetStateAction<TwoModelDetection | null>>;
  setDetectorMessage: Dispatch<SetStateAction<string>>;
  setDetectorBusy: Dispatch<SetStateAction<boolean>>;
  setDetectionAvailability: Dispatch<SetStateAction<DetectionAvailability>>;
  setReportState: Dispatch<SetStateAction<ReportState>>;
  setLastDuplicateCount: Dispatch<SetStateAction<number>>;
  setReportMessage: Dispatch<SetStateAction<string>>;
};

function withDepthEstimate(
  detection: TwoModelDetection,
  estimateDepthForDetection?: (detection: TwoModelDetection) => DepthEstimateResult | null
): TwoModelDetection {
  const estimate = estimateDepthForDetection?.(detection);
  if (!estimate) {
    return detection;
  }

  if (detection.distance_source === "sensor_depth" && (detection.distance_confidence ?? 0) >= estimate.confidence) {
    return detection;
  }

  return {
    ...detection,
    distance_m: estimate.distance_m,
    distance_source: estimate.source,
    distance_confidence: estimate.confidence,
    approach_state: estimate.approach_state
  };
}

export function useDetectionV2({
  cameraReady,
  serverV2FrameProcessingAllowed,
  serverV2ReportStorageAllowed,
  serverV2PrivacyConsentRef,
  gps,
  heading,
  captureFrame,
  estimateDepthForDetection,
  onDetectionV2Audit,
  onInferenceFrame,
  onDetectionFrame,
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
  setDetectionAvailability,
  setReportState,
  setLastDuplicateCount,
  setReportMessage
}: UseDetectionV2Options) {
  const autoReportGateStateRef = useRef<AutoReportV2GateState | null>(null);
  const latestGpsRef = useRef(gps);
  const latestHeadingRef = useRef(heading);
  const latestDepthEstimatorRef = useRef(estimateDepthForDetection);
  const latestAuditCallbackRef = useRef(onDetectionV2Audit);
  const latestInferenceFrameCallbackRef = useRef(onInferenceFrame);
  const latestFrameCallbackRef = useRef(onDetectionFrame);
  const latestSubmitReportRef = useRef(submitV2ReportTarget);
  const cancelServerV2LoopRef = useRef<(() => void) | null>(null);

  const cancelServerV2ReportScheduling = useCallback(() => {
    autoReportGateStateRef.current = null;
  }, []);

  const cancelServerV2Processing = useCallback(() => {
    cancelServerV2ReportScheduling();
    cancelServerV2LoopRef.current?.();
  }, [cancelServerV2ReportScheduling]);

  useEffect(() => {
    latestGpsRef.current = gps;
    latestHeadingRef.current = heading;
    latestDepthEstimatorRef.current = estimateDepthForDetection;
    latestAuditCallbackRef.current = onDetectionV2Audit;
    latestInferenceFrameCallbackRef.current = onInferenceFrame;
    latestFrameCallbackRef.current = onDetectionFrame;
    latestSubmitReportRef.current = submitV2ReportTarget;
  }, [estimateDepthForDetection, gps, heading, onDetectionFrame, onDetectionV2Audit, onInferenceFrame, submitV2ReportTarget]);

  useEffect(() => {
    if (!IS_SERVER_V2_MODE || !cameraReady || !serverV2ReportStorageAllowed) {
      autoReportGateStateRef.current = null;
    }
  }, [cameraReady, serverV2ReportStorageAllowed]);

  useEffect(() => {
    if (!IS_SERVER_V2_MODE) {
      return;
    }
    if (!serverV2FrameProcessingAllowed) {
      autoReportGateStateRef.current = null;
      setDetection(null);
      setV2Detections([]);
      setV2Primary(null);
      setV2Secondary(null);
      setDetectorBusy(false);
      setDetectorMessage("서버 탐지 중지 · 프레임·정확 위치 전송 동의 필요");
      setDetectionAvailability("paused");
      setLastDuplicateCount(0);
      setAutoReportV2State("idle", "자동 신고 중지 · 서버 탐지 처리 동의 필요");
      return;
    }
    if (!serverV2ReportStorageAllowed) {
      autoReportGateStateRef.current = null;
      setLastDuplicateCount(0);
      setAutoReportV2State("idle", "자동 신고 중지 · 신고 이미지 저장 동의 필요");
    }
  }, [
    serverV2FrameProcessingAllowed,
    serverV2ReportStorageAllowed,
    setAutoReportV2State,
    setDetection,
    setDetectionAvailability,
    setDetectorBusy,
    setDetectorMessage,
    setLastDuplicateCount,
    setV2Detections,
    setV2Primary,
    setV2Secondary
  ]);

  useEffect(() => {
    if (!IS_FAKE_V2_MODE || !cameraReady) {
      return;
    }

    setDetectionAvailability("checking");

    const runFakeV2Detection = () => {
      detectionIndexRef.current += 1;
      const result = createFakeTwoModelDetections(detectionIndexRef.current, latestGpsRef.current, latestHeadingRef.current);
      const depthDetections = result.detections.map((item) => withDepthEstimate(item, latestDepthEstimatorRef.current));
      latestInferenceFrameCallbackRef.current?.(depthDetections, depthDetections[0]?.captured_at ?? new Date().toISOString());
      const depthSelection = selectTwoModelPriorityDetections(depthDetections);
      setDetection(null);
      setV2Detections(depthDetections);
      setV2Primary(depthSelection.primary);
      setV2Secondary(depthSelection.secondary ?? null);
      setDetectorMessage(depthSelection.primary ? `${labelForTwoModelDetection(depthSelection.primary)} unified-v2 데모 감지` : "unified-v2 데모 결과 없음");
      setDetectionAvailability("available");
      setReportState("idle");
      setLastDuplicateCount(0);
      setReportMessage("자동 신고 데모 · 저장 없음");
    };

    const startTimer = window.setTimeout(runFakeV2Detection, 0);
    const intervalId = window.setInterval(runFakeV2Detection, 2800);

    return () => {
      window.clearTimeout(startTimer);
      window.clearInterval(intervalId);
    };
  }, [
    cameraReady,
    detectionIndexRef,
    setDetection,
    setDetectionAvailability,
    setDetectorMessage,
    setLastDuplicateCount,
    setReportMessage,
    setReportState,
    setV2Detections,
    setV2Primary,
    setV2Secondary
  ]);

  useEffect(() => {
    if (!IS_SERVER_V2_MODE || !cameraReady || !serverV2FrameProcessingAllowed) {
      return;
    }

    let stopped = false;
    let inFlight = false;
    let intervalId: number | null = null;
    let staleTimerId: number | null = null;
    let publishedPresence: DetectionPresenceState<TwoModelDetection> = {
      detections: [],
      consecutiveEmptyFrames: 0
    };
    const requestAbortController = new AbortController();
    setDetectionAvailability("checking");

    const stopServerV2Loop = () => {
      if (stopped) {
        return;
      }
      stopped = true;
      requestAbortController.abort();
      if (intervalId !== null) {
        window.clearInterval(intervalId);
        intervalId = null;
      }
      if (staleTimerId !== null) {
        window.clearTimeout(staleTimerId);
        staleTimerId = null;
      }
    };
    cancelServerV2LoopRef.current = stopServerV2Loop;

    const clearServerV2Detection = (message: string, availability: DetectionAvailability) => {
      if (staleTimerId !== null) {
        window.clearTimeout(staleTimerId);
        staleTimerId = null;
      }
      setDetection(null);
      publishedPresence = { detections: [], consecutiveEmptyFrames: 0 };
      setV2Detections([]);
      setV2Primary(null);
      setV2Secondary(null);
      setDetectorMessage(message);
      setDetectionAvailability(availability);
      if (reportStateRef.current !== "sending") {
        setLastDuplicateCount(0);
        setAutoReportV2State("idle", "자동 신고 대기");
      }
    };

    const runServerV2Detection = async () => {
      if (inFlight || !isServerV2FrameProcessingAllowed(serverV2PrivacyConsentRef.current)) {
        return;
      }

      inFlight = true;
      setDetectorBusy(true);
      setDetectorMessage("새 프레임 분석 중 · 이전 결과 유지");
      try {
        const frameGps = latestGpsRef.current ? { ...latestGpsRef.current } : null;
        const frameHeading = latestHeadingRef.current;
        const image = await captureFrame();
        if (stopped || !isServerV2FrameProcessingAllowed(serverV2PrivacyConsentRef.current)) {
          return;
        }
        const capturedAt = new Date().toISOString();
        const result = await detectFrameV2(
          image,
          { captured_at: capturedAt, gps: frameGps, heading: frameHeading },
          requestAbortController.signal
        );
        if (stopped || !isServerV2FrameProcessingAllowed(serverV2PrivacyConsentRef.current)) {
          return;
        }
        if (Date.now() - Date.parse(capturedAt) > DETECT_V2_SAFETY_DEADLINE_MS) {
          autoReportGateStateRef.current = null;
          clearServerV2Detection("오래된 탐지 응답 제외 · 다음 프레임 대기", "paused");
          return;
        }
        latestAuditCallbackRef.current?.(result.audit);

        const depthDetections = result.detections.map((item) => withDepthEstimate(item, latestDepthEstimatorRef.current));
        latestInferenceFrameCallbackRef.current?.(depthDetections, capturedAt);
        latestFrameCallbackRef.current?.(depthDetections, image, capturedAt, frameGps, frameHeading);
        publishedPresence = advanceDetectionPresence(publishedPresence, depthDetections);
        const publishedDetections = publishedPresence.detections;
        const selection = selectTwoModelPriorityDetections(publishedDetections);
        if (staleTimerId !== null) {
          window.clearTimeout(staleTimerId);
        }
        staleTimerId = window.setTimeout(() => {
          if (!stopped) {
            clearServerV2Detection("새 프레임 분석 중", "paused");
          }
        }, DETECT_V2_SAFETY_DEADLINE_MS);
        setDetection(null);
        setDetectionAvailability("available");
        setV2Detections(publishedDetections);
        setV2Primary(selection.primary);
        setV2Secondary(selection.secondary ?? null);
        setDetectorMessage(
          selection.primary
            ? publishedPresence.consecutiveEmptyFrames > 0
              ? `${labelForTwoModelDetection(selection.primary)} 일시 미검출 · 다음 프레임 확인 중`
              : `${labelForTwoModelDetection(selection.primary)} unified-v2 서버 감지`
            : "unified-v2 서버 결과 없음"
        );

        if (!isServerV2ReportStorageAllowed(serverV2PrivacyConsentRef.current)) {
          autoReportGateStateRef.current = null;
          return;
        }

        const autoReportGate = advanceAutoReportV2Gate(autoReportGateStateRef.current, depthDetections, frameGps);
        autoReportGateStateRef.current = autoReportGate.state;
        if (!autoReportGate.target) {
          if (autoReportGate.reason === "gps_quality") {
            setAutoReportV2State("waiting_location", "자동 신고 대기 · GPS 정확도 15m 이내 필요");
          } else if (autoReportGate.reason === "gps_continuity") {
            setAutoReportV2State("waiting_location", "자동 신고 대기 · GPS 위치 연속 확인 필요");
          } else if (autoReportGate.reason === "confidence") {
            setAutoReportV2State("not_reportable", "자동 신고 대기 · 신뢰도 70% 이상 필요");
          } else if (autoReportGate.reason === "stabilizing") {
            setAutoReportV2State(
              "not_reportable",
              `자동 신고 대기 · 연속 확인 ${autoReportGate.state?.consecutiveFrames ?? 0}/3`
            );
          } else {
            setAutoReportV2State("not_reportable");
          }
          return;
        }

        autoReportGateStateRef.current = null;
        void latestSubmitReportRef.current(autoReportGate.target, "auto", {
          capturedAt,
          image,
          gps: frameGps,
          heading: frameHeading,
          signal: requestAbortController.signal,
          shouldAbort: () => stopped
        });
      } catch (error) {
        if (stopped) {
          return;
        }
        if (error instanceof DetectV2ApiError && error.audit) {
          latestAuditCallbackRef.current?.(error.audit);
        }
        autoReportGateStateRef.current = null;
        clearServerV2Detection(
          error instanceof Error ? error.message : "unified-v2 서버 탐지 실패",
          error instanceof CameraFrameUnavailableError ? "paused" : "error"
        );
      } finally {
        inFlight = false;
        if (!stopped) {
          setDetectorBusy(false);
        }
      }
    };

    void runServerV2Detection();
    intervalId = window.setInterval(() => {
      void runServerV2Detection();
    }, SERVER_DETECT_INTERVAL_MS);

    return () => {
      stopServerV2Loop();
      if (cancelServerV2LoopRef.current === stopServerV2Loop) {
        cancelServerV2LoopRef.current = null;
      }
    };
  }, [
    cameraReady,
    captureFrame,
    reportStateRef,
    serverV2FrameProcessingAllowed,
    serverV2PrivacyConsentRef,
    setAutoReportV2State,
    setDetection,
    setDetectionAvailability,
    setDetectorBusy,
    setDetectorMessage,
    setLastDuplicateCount,
    setReportMessage,
    setReportState,
    setV2Detections,
    setV2Primary,
    setV2Secondary
  ]);

  return { cancelServerV2Processing, cancelServerV2ReportScheduling };
}
