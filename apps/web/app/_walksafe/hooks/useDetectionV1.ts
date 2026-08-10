"use client";

/**
 * Runs the configured legacy detector mode and clears v2 state so both contracts cannot appear active.
 * Server polling allows one request at a time and ignores results after effect teardown.
 */
import { useEffect, useRef } from "react";
import { DETECT_V1_SAFETY_DEADLINE_MS, detectFrame, fetchDetectHealth } from "@/lib/detect-api";
import { createFakeDetection } from "@/lib/detector";
import { CLASS_LABELS, type DetectionEvent, type GpsFix } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";
import { DETECTOR_MODE, SERVER_DETECT_INTERVAL_MS } from "../config";
import type { DetectionAvailability } from "../detection-availability";
import { CameraFrameUnavailableError } from "../camera-policy";

export type DetectionV1ReportState = "idle" | "sending" | "sent" | "error";

type WritableRef<T> = {
  current: T;
};

type SetValue<T> = (value: T) => void;

export type UseDetectionV1Props = {
  cameraReady: boolean;
  gps: GpsFix | null;
  heading: number | null;
  captureFrame: () => Promise<Blob>;
  reportStateRef: WritableRef<DetectionV1ReportState>;
  detectionIndexRef?: WritableRef<number>;
  setDetection: SetValue<DetectionEvent | null>;
  setV2Detections: SetValue<TwoModelDetection[]>;
  setV2Primary: SetValue<TwoModelDetection | null>;
  setV2Secondary: SetValue<TwoModelDetection | null>;
  setDetectorMessage: SetValue<string>;
  setDetectorBusy: SetValue<boolean>;
  setDetectionAvailability: SetValue<DetectionAvailability>;
  setReportState: SetValue<DetectionV1ReportState>;
  setLastDuplicateCount: SetValue<number>;
  setReportMessage: SetValue<string>;
};

function selectBestDetection(detections: DetectionEvent[]) {
  return detections.reduce<DetectionEvent | null>(
    (bestDetection, currentDetection) => (!bestDetection || currentDetection.confidence > bestDetection.confidence ? currentDetection : bestDetection),
    null
  );
}

export function useDetectionV1({
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
}: UseDetectionV1Props) {
  const fallbackDetectionIndexRef = useRef(0);
  const activeDetectionIndexRef = detectionIndexRef ?? fallbackDetectionIndexRef;
  const latestGpsRef = useRef(gps);
  const latestHeadingRef = useRef(heading);
  useEffect(() => {
    latestGpsRef.current = gps;
    latestHeadingRef.current = heading;
  }, [gps, heading]);

  useEffect(() => {
    if (DETECTOR_MODE !== "fake" || !cameraReady) {
      return;
    }

    setDetectionAvailability("checking");

    const intervalId = window.setInterval(() => {
      activeDetectionIndexRef.current += 1;
      const nextDetection = createFakeDetection(activeDetectionIndexRef.current, latestGpsRef.current, latestHeadingRef.current);
      setDetection(nextDetection);
      setV2Detections([]);
      setV2Primary(null);
      setV2Secondary(null);
      setDetectorMessage(`${CLASS_LABELS[nextDetection.class_name]} 데모 감지`);
      setDetectionAvailability("available");
      if (reportStateRef.current !== "sending") {
        setReportState("idle");
        setLastDuplicateCount(0);
        setReportMessage(`${CLASS_LABELS[nextDetection.class_name]} 신고 가능`);
      }
    }, 2800);

    return () => window.clearInterval(intervalId);
  }, [
    activeDetectionIndexRef,
    cameraReady,
    reportStateRef,
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
    if (DETECTOR_MODE !== "server" || !cameraReady) {
      return;
    }

    let stopped = false;
    let inFlight = false;
    let intervalId: number | null = null;
    let staleTimerId: number | null = null;
    const requestAbortController = new AbortController();
    setDetectionAvailability("checking");

    const setIdleReportMessage = (message: string) => {
      if (reportStateRef.current !== "sending") {
        setReportState("idle");
        setLastDuplicateCount(0);
        setReportMessage(message);
      }
    };

    const clearV2State = () => {
      setV2Detections([]);
      setV2Primary(null);
      setV2Secondary(null);
    };

    const clearServerDetection = (message: string) => {
      if (staleTimerId !== null) {
        window.clearTimeout(staleTimerId);
        staleTimerId = null;
      }
      setDetection(null);
      clearV2State();
      setDetectorMessage(message);
      setIdleReportMessage(message);
    };

    const runServerDetection = async () => {
      if (inFlight) {
        return;
      }

      inFlight = true;
      setDetectorBusy(true);
      try {
        const health = await fetchDetectHealth(requestAbortController.signal);
        if (stopped) {
          return;
        }
        if (health.model_status !== "ready") {
          setDetectionAvailability("unavailable");
          clearServerDetection(health.reason ? `서버 모델 미준비: ${health.reason}` : "서버 모델 미준비");
          return;
        }

        const image = await captureFrame();
        const capturedAt = new Date().toISOString();
        const result = await detectFrame(
          image,
          { captured_at: capturedAt, gps: latestGpsRef.current, heading: latestHeadingRef.current },
          requestAbortController.signal
        );
        if (stopped) {
          return;
        }
        if (Date.now() - Date.parse(capturedAt) > DETECT_V1_SAFETY_DEADLINE_MS) {
          setDetectionAvailability("paused");
          clearServerDetection("오래된 탐지 응답 제외 · 다음 프레임 대기");
          return;
        }

        const nextDetection = selectBestDetection(result.detections);
        if (staleTimerId !== null) {
          window.clearTimeout(staleTimerId);
        }
        staleTimerId = window.setTimeout(() => {
          if (!stopped) {
            setDetectionAvailability("paused");
            clearServerDetection("탐지 결과가 오래되어 새 프레임을 기다립니다.");
          }
        }, DETECT_V1_SAFETY_DEADLINE_MS);
        setDetectionAvailability("available");
        clearV2State();
        setDetection(nextDetection);

        if (nextDetection) {
          const message = `${CLASS_LABELS[nextDetection.class_name]} 서버 감지`;
          setDetectorMessage(message);
          setIdleReportMessage(`${CLASS_LABELS[nextDetection.class_name]} 신고 가능`);
        } else {
          const message = `서버 연결됨 · 위험 없음 (${result.model_version})`;
          setDetectorMessage(message);
          setIdleReportMessage("서버 탐지 결과 없음");
        }
      } catch (error) {
        if (stopped) {
          return;
        }
        setDetectionAvailability(error instanceof CameraFrameUnavailableError ? "paused" : "error");
        clearServerDetection(error instanceof Error ? error.message : "서버 탐지 실패");
      } finally {
        inFlight = false;
        if (!stopped) {
          setDetectorBusy(false);
        }
      }
    };

    void runServerDetection();
    intervalId = window.setInterval(() => {
      void runServerDetection();
    }, SERVER_DETECT_INTERVAL_MS);

    return () => {
      stopped = true;
      requestAbortController.abort();
      if (intervalId !== null) {
        window.clearInterval(intervalId);
      }
      if (staleTimerId !== null) {
        window.clearTimeout(staleTimerId);
      }
    };
  }, [
    cameraReady,
    captureFrame,
    reportStateRef,
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
}
