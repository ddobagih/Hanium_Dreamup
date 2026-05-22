import { labelForTwoModelDetection } from "@/lib/detector-v2";
import type { DetectionEvent } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";
import { RISK_ALERTS } from "./config";

export function speak(message: string) {
  if (!("speechSynthesis" in window)) {
    return;
  }

  const utterance = new SpeechSynthesisUtterance(message);
  utterance.lang = "ko-KR";
  utterance.rate = 0.95;
  window.speechSynthesis.cancel();
  window.speechSynthesis.speak(utterance);
}

export function vibrate(pattern: VibratePattern) {
  if ("vibrate" in navigator) {
    navigator.vibrate(pattern);
  }
}

export function alertForDetection(detection: DetectionEvent, speechEnabled: boolean) {
  const alert = RISK_ALERTS[detection.class_name];
  if (detection.confidence >= 0.9) {
    return {
      speech: `${alert.speech} 강한 위험 신호.`,
      vibration: speechEnabled ? [...(alert.vibration as number[]), 120, 360] : [...(alert.silentVibration as number[]), 160, 520]
    };
  }

  return {
    speech: alert.speech,
    vibration: speechEnabled ? alert.vibration : alert.silentVibration
  };
}

export function alertForTwoModelDetection(detection: TwoModelDetection, speechEnabled: boolean) {
  const label = labelForTwoModelDetection(detection);
  const isHighRisk = detection.category === "tactile_damage" || detection.category === "vehicle";
  const speech = `${label}. 전방 주의.`;

  return {
    speech: detection.confidence >= 0.9 ? `${speech} 강한 위험 신호.` : speech,
    vibration: speechEnabled
      ? isHighRisk
        ? [220, 90, 220]
        : [140, 80, 140]
      : isHighRisk
        ? [460, 140, 460]
        : [320, 120, 320]
  };
}
