"use client";

import { useEffect, useRef } from "react";
import { detectFrame, fetchDetectHealth } from "@/lib/detect-api";
import { createFakeDetection } from "@/lib/detector";
import { CLASS_LABELS, type DetectionEvent, type GpsFix } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";
import { DETECTOR_MODE, SERVER_DETECT_INTERVAL_MS } from "../config";

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
  setReportState,
  setLastDuplicateCount,
  setReportMessage
}: UseDetectionV1Props) {
  const fallbackDetectionIndexRef = useRef(0);
  const activeDetectionIndexRef = detectionIndexRef ?? fallbackDetectionIndexRef;

  useEffect(() => {
    if (DETECTOR_MODE !== "fake" || !cameraReady) {
      return;
    }

    const intervalId = window.setInterval(() => {
      activeDetectionIndexRef.current += 1;
      const nextDetection = createFakeDetection(activeDetectionIndexRef.current, gps, heading);
      setDetection(nextDetection);
      setV2Detections([]);
      setV2Primary(null);
      setV2Secondary(null);
      setDetectorMessage(`${CLASS_LABELS[nextDetection.class_name]} 데모 감지`);
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
    gps,
    heading,
    reportStateRef,
    setDetection,
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
        const health = await fetchDetectHealth();
        if (stopped) {
          return;
        }
        if (health.model_status !== "ready") {
          clearServerDetection(health.reason ? `서버 모델 미준비: ${health.reason}` : "서버 모델 미준비");
          return;
        }

        const capturedAt = new Date().toISOString();
        const image = await captureFrame();
        const result = await detectFrame(image, { captured_at: capturedAt, gps, heading });
        if (stopped) {
          return;
        }

        const nextDetection = selectBestDetection(result.detections);
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
      if (intervalId !== null) {
        window.clearInterval(intervalId);
      }
    };
  }, [
    cameraReady,
    captureFrame,
    gps,
    heading,
    reportStateRef,
    setDetection,
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
