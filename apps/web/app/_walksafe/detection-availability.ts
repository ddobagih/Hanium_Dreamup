export type DetectionAvailability = "unavailable" | "checking" | "available" | "paused" | "error";

export function detectionAvailabilityLabel(status: DetectionAvailability): string {
  switch (status) {
    case "available":
      return "위험 요소 없음";
    case "checking":
      return "탐지 확인 중";
    case "paused":
      return "탐지 일시 중지";
    case "error":
      return "탐지 오류";
    default:
      return "탐지 불가";
  }
}

export function detectionAvailabilitySpeech(status: DetectionAvailability): string | null {
  switch (status) {
    case "paused":
      return "실시간 장애물 탐지가 일시 중지됐습니다. 전방을 직접 확인해 주세요.";
    case "error":
      return "실시간 장애물 탐지 오류입니다. 전방을 직접 확인해 주세요.";
    case "unavailable":
      return "실시간 장애물 탐지를 사용할 수 없습니다. 전방을 직접 확인해 주세요.";
    default:
      return null;
  }
}
