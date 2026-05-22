import { useCallback, useRef, useState } from "react";
import { autoReportV2CooldownKey, canAutoReportV2, selectAutoReportV2Detection } from "@/lib/auto-report-v2";
import { submitReportV2, type ReportV2Trigger } from "@/lib/report-api-v2";
import type { GpsFixV2, TwoModelDetection } from "@/types/inference-v2";
import { AUTO_REPORT_V2_MESSAGES, type AutoReportV2Status } from "../config";
import { speak, vibrate } from "../feedback";

type ReportState = "idle" | "sending" | "sent" | "error";

type SubmitV2ReportOptions = {
  bypassCooldown?: boolean;
  capturedAt?: string;
  image?: Blob;
  shouldAbort?: () => boolean;
};

type UseAutoReportV2Options = {
  captureFrame: () => Promise<Blob>;
  gps: GpsFixV2 | null;
  heading: number | null;
  speechEnabled: boolean;
  v2Detections: TwoModelDetection[];
  isServerV2Mode: boolean;
  setReportState: (state: ReportState) => void;
  setReportMessage: (message: string) => void;
  setLastDuplicateCount: (count: number) => void;
  setVoiceMessage: (message: string) => void;
};

export function useAutoReportV2({
  captureFrame,
  gps,
  heading,
  speechEnabled,
  v2Detections,
  isServerV2Mode,
  setReportState,
  setReportMessage,
  setLastDuplicateCount,
  setVoiceMessage
}: UseAutoReportV2Options) {
  const autoReportCooldownsRef = useRef<Map<string, number>>(new Map());
  const autoReportInFlightRef = useRef(false);
  const [autoReportStatus, setAutoReportStatus] = useState<AutoReportV2Status>("idle");

  const setAutoReportV2State = useCallback(
    (status: AutoReportV2Status, message?: string) => {
      setAutoReportStatus(status);
      setReportMessage(message ?? AUTO_REPORT_V2_MESSAGES[status]);
      if (status === "sending") {
        setReportState("sending");
      } else if (status === "sent") {
        setReportState("sent");
      } else if (status === "failed") {
        setReportState("error");
      } else {
        setReportState("idle");
      }
    },
    [setReportMessage, setReportState]
  );

  const submitV2ReportTarget = useCallback(
    async (
      target: TwoModelDetection | null,
      trigger: ReportV2Trigger,
      options: SubmitV2ReportOptions = {}
    ) => {
      if (!target) {
        setAutoReportV2State("not_reportable");
        return false;
      }

      if (autoReportInFlightRef.current) {
        setAutoReportV2State("sending");
        return false;
      }

      if (trigger === "auto" && !gps) {
        setAutoReportV2State("waiting_location");
        return false;
      }

      const cooldownKey = gps ? autoReportV2CooldownKey(target, gps) : null;
      if (!options.bypassCooldown && gps && !canAutoReportV2(autoReportCooldownsRef.current, target, gps)) {
        setAutoReportV2State("cooldown");
        return false;
      }

      autoReportInFlightRef.current = true;
      setLastDuplicateCount(0);
      setAutoReportV2State("sending", trigger === "auto" ? "자동 신고 전송 중" : "음성 요청 신고 전송 중");

      try {
        const capturedAt = options.capturedAt ?? new Date().toISOString();
        const image = options.image ?? (await captureFrame());
        const reportSnapshot: TwoModelDetection = {
          ...target,
          captured_at: capturedAt,
          gps,
          heading
        };
        const response = await submitReportV2(reportSnapshot, image, trigger);
        if (options.shouldAbort?.()) {
          return false;
        }

        if (cooldownKey) {
          autoReportCooldownsRef.current.set(cooldownKey, Date.now());
        }

        const duplicateCount = response.duplicate_report_ids.length;
        setLastDuplicateCount(duplicateCount);
        setAutoReportV2State(
          "sent",
          duplicateCount > 0
            ? `${trigger === "auto" ? "자동 신고" : "음성 요청 신고"} 완료 · 유사 신고 ${duplicateCount}건`
            : `${trigger === "auto" ? "자동 신고" : "음성 요청 신고"} 완료: ${response.id.slice(0, 8)}`
        );
        vibrate(duplicateCount > 0 ? [80, 70, 80, 70, 160] : [80, 70, 160]);
        if (speechEnabled && trigger === "voice") {
          speak(duplicateCount > 0 ? "요청한 신고 완료. 유사 신고 있음." : "요청한 신고 완료");
        }
        return true;
      } catch (error) {
        if (options.shouldAbort?.()) {
          return false;
        }
        setAutoReportV2State(
          "failed",
          error instanceof Error ? `${trigger === "auto" ? "자동 신고" : "음성 요청 신고"} 실패 · ${error.message}` : "신고 실패"
        );
        vibrate([260, 120, 260]);
        if (speechEnabled && trigger === "voice") {
          speak("요청한 신고 실패");
        }
        return false;
      } finally {
        autoReportInFlightRef.current = false;
      }
    },
    [captureFrame, gps, heading, setAutoReportV2State, setLastDuplicateCount, speechEnabled]
  );

  const handleVoiceReportV2 = useCallback(async () => {
    const voiceTarget = selectAutoReportV2Detection(v2Detections);
    if (!voiceTarget) {
      setAutoReportV2State("not_reportable", "음성 요청 신고 불가 · 타일 손상 대상 없음");
      setVoiceMessage("현재 신고할 타일 손상이 없습니다.");
      vibrate([120, 80, 120]);
      if (speechEnabled) {
        speak("현재 신고할 타일 손상이 없습니다.");
      }
      return;
    }

    if (!isServerV2Mode) {
      setAutoReportV2State("idle", "음성 요청 신고 대기 · 서버 v2 모드 필요");
      setVoiceMessage("데모 모드에서는 실제 신고를 저장하지 않습니다.");
      vibrate([120, 80, 120]);
      if (speechEnabled) {
        speak("데모 모드에서는 실제 신고를 저장하지 않습니다.");
      }
      return;
    }

    setVoiceMessage("음성 명령: 타일 손상 신고");
    await submitV2ReportTarget(voiceTarget, "voice", { bypassCooldown: true });
  }, [isServerV2Mode, setAutoReportV2State, setVoiceMessage, speechEnabled, submitV2ReportTarget, v2Detections]);

  return {
    autoReportStatus,
    setAutoReportV2State,
    submitV2ReportTarget,
    handleVoiceReportV2
  };
}
