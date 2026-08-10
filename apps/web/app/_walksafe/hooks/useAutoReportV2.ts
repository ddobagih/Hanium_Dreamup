/**
 * Serializes reportable v2 submissions behind location, in-flight and spatial cooldown gates.
 * A voice request may bypass only the cooldown; it still requires a server-mode target and valid GPS.
 */
import { useCallback, useRef, useState, type RefObject } from "react";
import {
  AUTO_REPORT_COOLDOWN_STORAGE_KEY,
  autoReportV2CooldownKey,
  canAutoReportV2,
  parseAutoReportV2Cooldowns,
  selectAutoReportV2Detection,
  serializeAutoReportV2Cooldowns,
  shouldEmitReportUserFeedback
} from "@/lib/auto-report-v2";
import { submitReportV2, type ReportV2Trigger } from "@/lib/report-api-v2";
import type { GpsFixV2, TwoModelDetection } from "@/types/inference-v2";
import { AUTO_REPORT_V2_MESSAGES, type AutoReportV2Status } from "../config";
import { speak, vibrate } from "../feedback";
import {
  isServerV2ReportStorageAllowed,
  type ServerV2PrivacyConsent
} from "../server-v2-privacy";

type ReportState = "idle" | "sending" | "sent" | "error";

type SubmitV2ReportOptions = {
  bypassCooldown?: boolean;
  capturedAt?: string;
  image?: Blob;
  gps?: GpsFixV2 | null;
  heading?: number | null;
  signal?: AbortSignal;
  shouldAbort?: () => boolean;
};

type UseAutoReportV2Options = {
  captureFrame: () => Promise<Blob>;
  gps: GpsFixV2 | null;
  heading: number | null;
  speechEnabled: boolean;
  v2Detections: TwoModelDetection[];
  isServerV2Mode: boolean;
  serverV2PrivacyConsentRef: RefObject<ServerV2PrivacyConsent>;
  setReportState: (state: ReportState) => void;
  setReportMessage: (message: string) => void;
  setLastDuplicateCount: (count: number) => void;
  setVoiceMessage: (message: string) => void;
};

type DetectionFrameSnapshot = {
  capturedAt: string;
  detections: TwoModelDetection[];
  image: Blob;
  capturedAtMs: number;
  gps: GpsFixV2 | null;
  heading: number | null;
};

const VOICE_REPORT_MAX_SNAPSHOT_AGE_MS = 3_000;
const REPORT_RESULT_ANNOUNCEMENT_MS = 5_000;

function loadSessionCooldowns(): Map<string, number> {
  if (typeof window === "undefined") {
    return new Map();
  }
  try {
    return parseAutoReportV2Cooldowns(window.sessionStorage.getItem(AUTO_REPORT_COOLDOWN_STORAGE_KEY));
  } catch {
    return new Map();
  }
}

function saveSessionCooldowns(cooldowns: ReadonlyMap<string, number>): void {
  try {
    window.sessionStorage.setItem(AUTO_REPORT_COOLDOWN_STORAGE_KEY, serializeAutoReportV2Cooldowns(cooldowns));
  } catch {
    // Reporting still works when storage is unavailable; server-side duplicate gates remain authoritative.
  }
}

export function useAutoReportV2({
  captureFrame,
  gps,
  heading,
  speechEnabled,
  v2Detections,
  isServerV2Mode,
  serverV2PrivacyConsentRef,
  setReportState,
  setReportMessage,
  setLastDuplicateCount,
  setVoiceMessage
}: UseAutoReportV2Options) {
  const autoReportCooldownsRef = useRef<Map<string, number> | null>(null);
  if (autoReportCooldownsRef.current === null) {
    autoReportCooldownsRef.current = loadSessionCooldowns();
  }
  const autoReportInFlightRef = useRef(false);
  const submissionGenerationRef = useRef(0);
  const submissionAbortRef = useRef<AbortController | null>(null);
  const latestDetectionSnapshotRef = useRef<DetectionFrameSnapshot | null>(null);
  const terminalStatusHoldUntilRef = useRef(0);
  const [autoReportStatus, setAutoReportStatus] = useState<AutoReportV2Status>("idle");

  const setAutoReportV2State = useCallback(
    (status: AutoReportV2Status, message?: string) => {
      if (
        status !== "sending" &&
        status !== "sent" &&
        status !== "failed" &&
        Date.now() < terminalStatusHoldUntilRef.current
      ) {
        return;
      }
      if (status === "sent" || status === "failed") {
        terminalStatusHoldUntilRef.current = Date.now() + REPORT_RESULT_ANNOUNCEMENT_MS;
      }
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

  const cancelAutoReportV2 = useCallback((message = "자동 신고 대기") => {
    submissionGenerationRef.current += 1;
    submissionAbortRef.current?.abort();
    submissionAbortRef.current = null;
    autoReportInFlightRef.current = false;
    latestDetectionSnapshotRef.current = null;
    terminalStatusHoldUntilRef.current = 0;
    setAutoReportV2State("idle", message);
  }, [setAutoReportV2State]);

  const resetAutoReportV2Session = useCallback(() => {
    cancelAutoReportV2();
    autoReportCooldownsRef.current = new Map();
    try {
      window.sessionStorage.removeItem(AUTO_REPORT_COOLDOWN_STORAGE_KEY);
    } catch {
      // In-memory state is already reset when browser storage is unavailable.
    }
  }, [cancelAutoReportV2]);

  const submitV2ReportTarget = useCallback(
    async (
      target: TwoModelDetection | null,
      trigger: ReportV2Trigger,
      options: SubmitV2ReportOptions = {}
    ) => {
      if (isServerV2Mode && !isServerV2ReportStorageAllowed(serverV2PrivacyConsentRef.current)) {
        setAutoReportV2State("idle", "신고 중지 · 이미지·정확 위치 저장 동의 필요");
        if (trigger === "voice") {
          setVoiceMessage("신고 이미지 저장 동의 후 다시 요청해 주세요.");
          vibrate([120, 80, 120]);
          if (speechEnabled) {
            speak("신고 이미지 저장 동의 후 다시 요청해 주세요.");
          }
        }
        return false;
      }

      if (!target) {
        setAutoReportV2State("not_reportable");
        return false;
      }

      if (autoReportInFlightRef.current) {
        setAutoReportV2State("sending");
        return false;
      }

      const reportGps = Object.hasOwn(options, "gps") ? options.gps ?? null : gps;
      const reportHeading = Object.hasOwn(options, "heading") ? options.heading ?? null : heading;
      if (!reportGps) {
        setAutoReportV2State(
          "waiting_location",
          trigger === "auto" ? "자동 신고 대기 · 위치 확인 필요" : "음성 요청 신고 대기 · 위치 확인 필요"
        );
        if (trigger === "voice") {
          setVoiceMessage("위치 확인 후 다시 신고해 주세요.");
          vibrate([120, 80, 120]);
          if (speechEnabled) {
            speak("위치 확인 후 다시 신고해 주세요.");
          }
        }
        return false;
      }

      const cooldownKey = autoReportV2CooldownKey(target, reportGps);
      const cooldowns = autoReportCooldownsRef.current ?? new Map<string, number>();
      if (!options.bypassCooldown && !canAutoReportV2(cooldowns, target, reportGps)) {
        setAutoReportV2State("cooldown");
        return false;
      }

      autoReportInFlightRef.current = true;
      const submissionGeneration = ++submissionGenerationRef.current;
      const submissionController = new AbortController();
      submissionAbortRef.current = submissionController;
      const relayAbort = () => submissionController.abort();
      if (options.signal?.aborted) {
        relayAbort();
      } else {
        options.signal?.addEventListener("abort", relayAbort, { once: true });
      }
      setLastDuplicateCount(0);
      setAutoReportV2State("sending", trigger === "auto" ? "자동 신고 전송 중" : "음성 요청 신고 전송 중");

      try {
        const capturedAt = options.capturedAt ?? new Date().toISOString();
        const image = options.image ?? (await captureFrame());
        if (
          submissionGeneration !== submissionGenerationRef.current ||
          options.shouldAbort?.() ||
          (isServerV2Mode && !isServerV2ReportStorageAllowed(serverV2PrivacyConsentRef.current))
        ) {
          submissionController.abort();
          return false;
        }
        const reportSnapshot: TwoModelDetection = {
          ...target,
          captured_at: capturedAt,
          gps: reportGps,
          heading: reportHeading
        };
        const response = await submitReportV2(reportSnapshot, image, trigger, submissionController.signal);
        if (
          submissionGeneration !== submissionGenerationRef.current ||
          options.shouldAbort?.() ||
          (isServerV2Mode && !isServerV2ReportStorageAllowed(serverV2PrivacyConsentRef.current))
        ) {
          if (submissionGeneration === submissionGenerationRef.current) {
            terminalStatusHoldUntilRef.current = 0;
            setAutoReportV2State("idle", "자동 신고 대기");
          }
          return false;
        }

        cooldowns.set(cooldownKey, Date.now());
        autoReportCooldownsRef.current = cooldowns;
        saveSessionCooldowns(cooldowns);

        const duplicateCount = response.duplicate_count ?? 0;
        setLastDuplicateCount(duplicateCount);
        setAutoReportV2State(
          "sent",
          duplicateCount > 0
            ? `${trigger === "auto" ? "자동 신고" : "음성 요청 신고"} 완료 · 유사 신고 ${duplicateCount}건`
            : `${trigger === "auto" ? "자동 신고" : "음성 요청 신고"} 완료: ${response.id.slice(0, 8)}`
        );
        if (shouldEmitReportUserFeedback(trigger)) {
          vibrate(duplicateCount > 0 ? [80, 70, 80, 70, 160] : [80, 70, 160]);
          if (speechEnabled && trigger === "voice") {
            speak(duplicateCount > 0 ? "요청한 신고 완료. 유사 신고 있음." : "요청한 신고 완료");
          }
        }
        return true;
      } catch (error) {
        if (
          submissionGeneration !== submissionGenerationRef.current ||
          submissionController.signal.aborted ||
          options.shouldAbort?.() ||
          (isServerV2Mode && !isServerV2ReportStorageAllowed(serverV2PrivacyConsentRef.current))
        ) {
          if (submissionGeneration === submissionGenerationRef.current) {
            terminalStatusHoldUntilRef.current = 0;
            setAutoReportV2State("idle", "자동 신고 대기");
          }
          return false;
        }
        setAutoReportV2State(
          "failed",
          error instanceof Error ? `${trigger === "auto" ? "자동 신고" : "음성 요청 신고"} 실패 · ${error.message}` : "신고 실패"
        );
        if (shouldEmitReportUserFeedback(trigger)) {
          vibrate([260, 120, 260]);
          if (speechEnabled && trigger === "voice") {
            speak("요청한 신고 실패");
          }
        }
        return false;
      } finally {
        options.signal?.removeEventListener("abort", relayAbort);
        if (submissionGeneration === submissionGenerationRef.current) {
          autoReportInFlightRef.current = false;
          submissionAbortRef.current = null;
        }
      }
    },
    [
      captureFrame,
      gps,
      heading,
      isServerV2Mode,
      serverV2PrivacyConsentRef,
      setAutoReportV2State,
      setLastDuplicateCount,
      setVoiceMessage,
      speechEnabled
    ]
  );

  const recordV2DetectionSnapshot = useCallback(
    (
      detections: TwoModelDetection[],
      image: Blob,
      capturedAt: string,
      frameGps: GpsFixV2 | null,
      frameHeading: number | null
    ) => {
      if (!isServerV2ReportStorageAllowed(serverV2PrivacyConsentRef.current)) {
        latestDetectionSnapshotRef.current = null;
        return;
      }
      latestDetectionSnapshotRef.current = {
        capturedAt,
        detections,
        image,
        gps: frameGps ? { ...frameGps } : null,
        heading: frameHeading,
        capturedAtMs: Number.isFinite(Date.parse(capturedAt)) ? Date.parse(capturedAt) : Date.now()
      };
    },
    [serverV2PrivacyConsentRef]
  );

  const handleVoiceReportV2 = useCallback(async () => {
    if (isServerV2Mode && !isServerV2ReportStorageAllowed(serverV2PrivacyConsentRef.current)) {
      setAutoReportV2State("idle", "음성 요청 신고 중지 · 신고 이미지 저장 동의 필요");
      setVoiceMessage("신고 이미지 저장 동의 후 다시 요청해 주세요.");
      vibrate([120, 80, 120]);
      if (speechEnabled) {
        speak("신고 이미지 저장 동의 후 다시 요청해 주세요.");
      }
      return;
    }

    const snapshot = latestDetectionSnapshotRef.current;
    if (
      isServerV2Mode &&
      (!snapshot ||
        snapshot.capturedAtMs > Date.now() + 1_000 ||
        Date.now() - snapshot.capturedAtMs > VOICE_REPORT_MAX_SNAPSHOT_AGE_MS)
    ) {
      setAutoReportV2State("not_reportable", "음성 요청 신고 대기 · 최신 분석 프레임 필요");
      setVoiceMessage("최신 카메라 분석 결과를 기다린 뒤 다시 신고해 주세요.");
      vibrate([120, 80, 120]);
      if (speechEnabled) {
        speak("최신 카메라 분석 결과를 기다린 뒤 다시 신고해 주세요.");
      }
      return;
    }

    const voiceTarget = selectAutoReportV2Detection(isServerV2Mode ? snapshot?.detections ?? [] : v2Detections);
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
    await submitV2ReportTarget(voiceTarget, "voice", {
      bypassCooldown: true,
      capturedAt: snapshot?.capturedAt,
      image: snapshot?.image,
      gps: snapshot?.gps,
      heading: snapshot?.heading
    });
  }, [
    isServerV2Mode,
    serverV2PrivacyConsentRef,
    setAutoReportV2State,
    setVoiceMessage,
    speechEnabled,
    submitV2ReportTarget,
    v2Detections
  ]);

  return {
    autoReportStatus,
    cancelAutoReportV2,
    resetAutoReportV2Session,
    setAutoReportV2State,
    submitV2ReportTarget,
    recordV2DetectionSnapshot,
    handleVoiceReportV2
  };
}
