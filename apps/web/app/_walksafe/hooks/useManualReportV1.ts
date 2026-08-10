"use client";

/**
 * Submits the currently visible v1 detection as a user-requested report with a fresh capture snapshot.
 * Duplicate matching is performed atomically by the report creation endpoint.
 */
import { useCallback, useMemo, type Dispatch, type SetStateAction } from "react";
import { submitReport } from "@/lib/report-api";
import type { DetectionEvent, GpsFix } from "@/types/inference";
import { speak, vibrate } from "../feedback";

export type ReportState = "idle" | "sending" | "sent" | "error";

type UseManualReportV1Options = {
  detection: DetectionEvent | null;
  cameraReady: boolean;
  gps: GpsFix | null;
  heading: number | null;
  captureFrame: () => Promise<Blob>;
  speechEnabled: boolean;
  isV2Mode: boolean;
  reportState: ReportState;
  reportMessage: string;
  setReportState: Dispatch<SetStateAction<ReportState>>;
  setReportMessage: Dispatch<SetStateAction<string>>;
  setLastDuplicateCount: Dispatch<SetStateAction<number>>;
};

export function useManualReportV1({
  detection,
  cameraReady,
  gps,
  heading,
  captureFrame,
  speechEnabled,
  isV2Mode,
  reportState,
  reportMessage,
  setReportState,
  setReportMessage,
  setLastDuplicateCount
}: UseManualReportV1Options) {
  const canReport = !isV2Mode && Boolean(detection) && Boolean(gps) && cameraReady && reportState !== "sending";
  const reportDisabledReason = useMemo(() => {
    if (isV2Mode) {
      return "v2는 자동 신고 모드입니다. 수동 신고 버튼은 비활성화되어 있습니다.";
    }
    if (!cameraReady) {
      return "카메라가 준비되면 신고할 수 있습니다.";
    }
    if (!detection) {
      return "탐지된 위험이 없습니다. 위험이 감지되면 신고할 수 있습니다.";
    }
    if (!gps) {
      return "정확한 손상 위치를 확인할 GPS가 필요합니다.";
    }
    return "";
  }, [cameraReady, detection, gps, isV2Mode]);
  const reportHelpText = reportDisabledReason || reportMessage;

  const handleReport = useCallback(async () => {
    if (isV2Mode) {
      setReportState("idle");
      setReportMessage("자동 신고 모드 · 수동 신고 비활성");
      return;
    }

    if (!detection || !cameraReady || !gps) {
      setReportState("error");
      setReportMessage(reportDisabledReason || "신고할 탐지 결과가 없습니다.");
      return;
    }

    setReportState("sending");
    setLastDuplicateCount(0);
    setReportMessage("신고 전송 중");

    try {
      const reportSnapshot: DetectionEvent = {
        ...detection,
        captured_at: new Date().toISOString(),
        gps,
        heading
      };
      const image = await captureFrame();
      const response = await submitReport(reportSnapshot, image);
      const responseDuplicateCount = response.duplicate_count ?? 0;
      setLastDuplicateCount(responseDuplicateCount);
      setReportState("sent");
      setReportMessage(
        responseDuplicateCount > 0
          ? `신고 저장 완료 · 유사 신고 ${responseDuplicateCount}건`
          : `신고 저장 완료: ${response.id.slice(0, 8)}`
      );
      vibrate(responseDuplicateCount > 0 ? [90, 70, 90, 70, 180] : [80, 70, 180]);
      if (speechEnabled) {
        speak(responseDuplicateCount > 0 ? "신고 저장 완료. 유사 신고 있음." : "신고 저장 완료");
      }
    } catch (error) {
      setReportState("error");
      setReportMessage(error instanceof Error ? `${error.message} · 다시 신고 가능` : "신고 전송 실패 · 다시 신고 가능");
      vibrate([420, 160, 420]);
      if (speechEnabled) {
        speak("신고 실패. 다시 누르세요.");
      }
    }
  }, [cameraReady, captureFrame, detection, gps, heading, isV2Mode, reportDisabledReason, setLastDuplicateCount, setReportMessage, setReportState, speechEnabled]);

  return {
    canReport,
    reportDisabledReason,
    reportHelpText,
    handleReport
  };
}
