import { DETECT_API_BASE } from "@/lib/detect-api";
import type { DetectionClassName } from "@/types/inference";

function optionalNumber(value: string | undefined, min: number, max: number): number | null {
  if (!value) {
    return null;
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < min || parsed > max) {
    return null;
  }
  return parsed;
}

export const DETECTOR_MODE = process.env.NEXT_PUBLIC_DETECTOR_MODE ?? "fake";
export const SERVER_DETECT_INTERVAL_MS = 2800;
export const SPEECH_COOLDOWN_MS = 6000;
export const WALKSAFE_DEFAULT_STEP_LENGTH_M = optionalNumber(process.env.NEXT_PUBLIC_WALKSAFE_STEP_LENGTH_M, 0.3, 1.2) ?? 0.65;
export const WALKSAFE_MIN_WALKING_SPEED_MPS = 0.2;
export const WALKSAFE_MAX_WALKING_SPEED_MPS = 2.2;
export const WALKSAFE_GPS_SAMPLE_MIN_INTERVAL_MS = 800;
export const WALKSAFE_GPS_SAMPLE_MAX_INTERVAL_MS = 15000;
export const WALKSAFE_GPS_SAMPLE_MAX_DISTANCE_M = 35;
export const WALKSAFE_OFF_ROUTE_THRESHOLD_M = optionalNumber(process.env.NEXT_PUBLIC_WALKSAFE_OFF_ROUTE_THRESHOLD_M, 5, 100) ?? 25;
export const WALKSAFE_DESTINATION_MAX_DISTANCE_M =
  optionalNumber(process.env.NEXT_PUBLIC_WALKSAFE_DESTINATION_MAX_DISTANCE_M, 100, 100000) ?? 30000;
export const WALKSAFE_DESTINATION_SEARCH_DEBOUNCE_MS =
  optionalNumber(process.env.NEXT_PUBLIC_WALKSAFE_DESTINATION_SEARCH_DEBOUNCE_MS, 0, 2000) ?? 250;
export const WALKSAFE_AUTO_REROUTE_COOLDOWN_MS =
  optionalNumber(process.env.NEXT_PUBLIC_WALKSAFE_AUTO_REROUTE_COOLDOWN_MS, 10000, 120000) ?? 30000;
export const WALKSAFE_AUTO_REROUTE_MAX_COUNT =
  optionalNumber(process.env.NEXT_PUBLIC_WALKSAFE_AUTO_REROUTE_MAX_COUNT, 1, 5) ?? 2;
export const WALKSAFE_REROUTE_MAX_ACCURACY_M =
  optionalNumber(process.env.NEXT_PUBLIC_WALKSAFE_REROUTE_MAX_ACCURACY_M, 5, 100) ?? 35;
export const WALKSAFE_REROUTE_MAX_GPS_JUMP_M =
  optionalNumber(process.env.NEXT_PUBLIC_WALKSAFE_REROUTE_MAX_GPS_JUMP_M, 10, 200) ?? 50;
export const WALKSAFE_ARRIVAL_RADIUS_M = optionalNumber(process.env.NEXT_PUBLIC_WALKSAFE_ARRIVAL_RADIUS_M, 3, 30) ?? 8;
export const WALKSAFE_GUIDE_TURN_RADIUS_M = 4;
export const WALKSAFE_GUIDE_SOON_RADIUS_M = 8;
export const WALKSAFE_GUIDE_COOLDOWN_MS = 12000;
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

const defaultDestinationLatitude = optionalNumber(process.env.NEXT_PUBLIC_WALKSAFE_DESTINATION_LAT, -90, 90);
const defaultDestinationLongitude = optionalNumber(process.env.NEXT_PUBLIC_WALKSAFE_DESTINATION_LNG, -180, 180);

export const WALKSAFE_DEFAULT_DESTINATION =
  defaultDestinationLatitude === null || defaultDestinationLongitude === null
    ? null
    : {
        latitude: defaultDestinationLatitude,
        longitude: defaultDestinationLongitude,
        name: process.env.NEXT_PUBLIC_WALKSAFE_DESTINATION_NAME || "설정된 목적지"
      };

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
