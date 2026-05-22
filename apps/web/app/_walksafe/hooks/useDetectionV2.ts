"use client";

import { useEffect, type Dispatch, type RefObject, type SetStateAction } from "react";
import { selectAutoReportV2Detection } from "@/lib/auto-report-v2";
import { detectFrameV2 } from "@/lib/detect-api-v2";
import { createFakeTwoModelDetections, labelForTwoModelDetection } from "@/lib/detector-v2";
import { selectTwoModelPriorityDetections } from "@/lib/two-model-priority";
import type { DetectionEvent } from "@/types/inference";
import type { GpsFixV2, TwoModelDetection } from "@/types/inference-v2";
import {
  IS_FAKE_V2_MODE,
  IS_SERVER_V2_MODE,
  SERVER_DETECT_INTERVAL_MS,
  type AutoReportV2Status
} from "../config";

type ReportState = "idle" | "sending" | "sent" | "error";

type SubmitV2ReportOptions = {
  capturedAt?: string;
  image?: Blob;
  shouldAbort?: () => boolean;
};

type UseDetectionV2Options = {
  cameraReady: boolean;
  gps: GpsFixV2 | null;
  heading: number | null;
  captureFrame: () => Promise<Blob>;
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
  setReportState: Dispatch<SetStateAction<ReportState>>;
  setLastDuplicateCount: Dispatch<SetStateAction<number>>;
  setReportMessage: Dispatch<SetStateAction<string>>;
};

export function useDetectionV2({
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
}: UseDetectionV2Options) {
  useEffect(() => {
    if (!IS_FAKE_V2_MODE || !cameraReady) {
      return;
    }

    const runFakeV2Detection = () => {
      detectionIndexRef.current += 1;
      const result = createFakeTwoModelDetections(detectionIndexRef.current, gps, heading);
      setDetection(null);
      setV2Detections(result.detections);
      setV2Primary(result.primary);
      setV2Secondary(result.secondary ?? null);
      setDetectorMessage(result.primary ? `${labelForTwoModelDetection(result.primary)} two-model 데모 감지` : "two-model 데모 결과 없음");
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
    gps,
    heading,
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
    if (!IS_SERVER_V2_MODE || !cameraReady) {
      return;
    }

    let stopped = false;
    let inFlight = false;
    let intervalId: number | null = null;

    const clearServerV2Detection = (message: string) => {
      setDetection(null);
      setV2Detections([]);
      setV2Primary(null);
      setV2Secondary(null);
      setDetectorMessage(message);
      if (reportStateRef.current !== "sending") {
        setReportState("idle");
        setLastDuplicateCount(0);
        setReportMessage("자동 신고 대기");
      }
    };

    const runServerV2Detection = async () => {
      if (inFlight) {
        return;
      }

      inFlight = true;
      setDetectorBusy(true);
      try {
        const capturedAt = new Date().toISOString();
        const image = await captureFrame();
        const result = await detectFrameV2(image, { captured_at: capturedAt, gps, heading });
        if (stopped) {
          return;
        }

        const selection = selectTwoModelPriorityDetections(result.detections);
        setDetection(null);
        setV2Detections(result.detections);
        setV2Primary(selection.primary);
        setV2Secondary(selection.secondary ?? null);
        setDetectorMessage(
          selection.primary ? `${labelForTwoModelDetection(selection.primary)} two-model 서버 감지` : "two-model 서버 결과 없음"
        );

        const autoReportTarget = selectAutoReportV2Detection(result.detections);
        if (!autoReportTarget) {
          setAutoReportV2State("not_reportable");
          return;
        }

        await submitV2ReportTarget(autoReportTarget, "auto", {
          capturedAt,
          image,
          shouldAbort: () => stopped
        });
      } catch (error) {
        if (stopped) {
          return;
        }
        clearServerV2Detection(error instanceof Error ? error.message : "two-model 서버 탐지 실패");
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
    setAutoReportV2State,
    setDetection,
    setDetectorBusy,
    setDetectorMessage,
    setLastDuplicateCount,
    setReportMessage,
    setReportState,
    setV2Detections,
    setV2Primary,
    setV2Secondary,
    submitV2ReportTarget
  ]);
}
