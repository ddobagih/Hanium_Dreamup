import { DETECT_API_BASE } from "@/lib/detect-api";
import type { DetectionClassName } from "@/types/inference";

export const DETECTOR_MODE = process.env.NEXT_PUBLIC_DETECTOR_MODE ?? "fake";
export const SERVER_DETECT_INTERVAL_MS = 2800;
export const SPEECH_COOLDOWN_MS = 6000;
export const VOICE_RECORDING_MAX_MS = 5000;
export const VOICE_INTENT_CONFIDENCE_THRESHOLD = 0.7;
export const IS_FAKE_V2_MODE = DETECTOR_MODE === "fake-v2";
export const IS_SERVER_V2_MODE = DETECTOR_MODE === "server-v2";
export const IS_V2_MODE = IS_FAKE_V2_MODE || IS_SERVER_V2_MODE;
export const INITIAL_DETECTOR_MESSAGE =
  DETECTOR_MODE === "server"
    ? `서버 ${DETECT_API_BASE}`
    : DETECTOR_MODE === "fake"
      ? "데모 탐지 대기"
      : IS_FAKE_V2_MODE
        ? "two-model 데모 탐지 대기"
        : IS_SERVER_V2_MODE
          ? `two-model 서버 ${DETECT_API_BASE}`
          : `탐지 모드 확인 필요: ${DETECTOR_MODE}`;

export type AutoReportV2Status = "idle" | "waiting_location" | "not_reportable" | "cooldown" | "sending" | "sent" | "failed";

export const AUTO_REPORT_V2_MESSAGES: Record<AutoReportV2Status, string> = {
  idle: "자동 신고 대기",
  waiting_location: "자동 신고 대기 · 위치 필요",
  not_reportable: "자동 신고 대기 · 대상 없음",
  cooldown: "자동 신고 대기 · 최근 신고됨",
  sending: "자동 신고 전송 중",
  sent: "자동 신고 완료",
  failed: "자동 신고 실패"
};

export const RISK_ALERTS: Record<DetectionClassName, { speech: string; vibration: VibratePattern; silentVibration: VibratePattern }> = {
  damaged_tactile_block: {
    speech: "점자블록 파손. 발밑 주의.",
    vibration: [180, 80, 180],
    silentVibration: [420, 120, 420, 120, 420]
  },
  parked_kickboard_bicycle: {
    speech: "전방 장애물. 천천히 이동.",
    vibration: [260, 120, 120],
    silentVibration: [500, 160, 260, 160, 260]
  },
  construction_obstacle: {
    speech: "공사 장애물. 우회하세요.",
    vibration: [300, 100, 300],
    silentVibration: [520, 140, 520, 140, 260]
  },
  pothole: {
    speech: "노면 파임. 발밑 주의.",
    vibration: [120, 70, 120, 70, 300],
    silentVibration: [300, 100, 300, 100, 520]
  }
};
