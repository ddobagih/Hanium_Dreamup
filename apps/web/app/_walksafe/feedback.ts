/** Arbitrates browser speech and vibration so urgent risk feedback preempts lower-priority audio. */
import { labelForTwoModelDetection } from "@/lib/detector-v2";
import type { DetectionEvent } from "@/types/inference";
import type { TwoModelDetection } from "@/types/inference-v2";
import { RISK_ALERTS } from "./config";
import { shouldVibrateForRiskLevel, type RiskLevel } from "./risk-evaluator";
import { shouldBlockSpeechDuringRecognition, shouldPreemptSpeech, type SpeechPriority } from "./voice-priority";

export const WALKSAFE_URGENT_SPEECH_EVENT = "walksafe:urgent-speech";
export const WALKSAFE_SPEECH_OUTPUT_STATUS_EVENT = "walksafe:speech-output-status";
export const WALKSAFE_SPEECH_RECOGNITION_STATUS_EVENT = "walksafe:speech-recognition-status";
export const WALKSAFE_SPEECH_ARBITRATION_STATUS_EVENT = "walksafe:speech-arbitration-status";
export type SpeechOutputStatus = "unknown" | "available" | "unavailable" | "failed";
export type SpeechDeliveryCallbacks = {
  onStart?: () => void;
  onEnd?: () => void;
  onFailure?: () => void;
  onCancel?: () => void;
};

let activeSpeechPriority: SpeechPriority | null = null;
let speechSequence = 0;
let speechRecognitionActive = false;
let speechOutputStatus: SpeechOutputStatus = "unknown";
let speechStartDeadline: ReturnType<typeof setTimeout> | null = null;
let speechTerminalDeadline: ReturnType<typeof setTimeout> | null = null;
let activeSpeechFailure: (() => void) | null = null;
let activeSpeechCancellation: (() => void) | null = null;

function setActiveSpeechPriority(priority: SpeechPriority | null): void {
  if (activeSpeechPriority === priority) return;
  activeSpeechPriority = priority;
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(WALKSAFE_SPEECH_ARBITRATION_STATUS_EVENT));
  }
}

function clearSpeechStartDeadline(): void {
  if (speechStartDeadline === null) return;
  clearTimeout(speechStartDeadline);
  speechStartDeadline = null;
}

function clearSpeechTerminalDeadline(): void {
  if (speechTerminalDeadline === null) return;
  clearTimeout(speechTerminalDeadline);
  speechTerminalDeadline = null;
}

function clearSpeechDeadlines(): void {
  clearSpeechStartDeadline();
  clearSpeechTerminalDeadline();
}

function terminalDeadlineMs(message: string): number {
  return Math.min(120_000, Math.max(8_000, 5_000 + message.length * 250));
}

function failSpeech(sequence: number): void {
  if (speechSequence !== sequence) return;
  speechSequence += 1;
  setActiveSpeechPriority(null);
  clearSpeechDeadlines();
  const onFailure = activeSpeechFailure;
  activeSpeechFailure = null;
  activeSpeechCancellation = null;
  try {
    window.speechSynthesis.cancel();
  } catch {
    // The stale priority is already released; keep the accessible fallback live.
  }
  publishSpeechOutputStatus("failed");
  onFailure?.();
}

export function getSpeechOutputStatus(): SpeechOutputStatus {
  return speechOutputStatus;
}

export function getActiveSpeechPriority(): SpeechPriority | null {
  return activeSpeechPriority;
}

export function getSpeechRecognitionActive(): boolean {
  return speechRecognitionActive;
}

function publishSpeechOutputStatus(status: SpeechOutputStatus): void {
  if (speechOutputStatus === status) return;
  speechOutputStatus = status;
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(WALKSAFE_SPEECH_OUTPUT_STATUS_EVENT));
  }
}

export function setSpeechRecognitionActive(active: boolean): void {
  if (speechRecognitionActive === active) return;
  speechRecognitionActive = active;
  if (typeof window !== "undefined") {
    window.dispatchEvent(new Event(WALKSAFE_SPEECH_RECOGNITION_STATUS_EVENT));
  }
}

export function stopSpeaking() {
  const onCancel = activeSpeechCancellation;
  speechSequence += 1;
  setActiveSpeechPriority(null);
  clearSpeechDeadlines();
  activeSpeechFailure = null;
  activeSpeechCancellation = null;
  if ("speechSynthesis" in window) {
    try {
      window.speechSynthesis.cancel();
    } catch {
      // Lifecycle teardown must still release the in-memory speech owner.
    }
  }
  try {
    onCancel?.();
  } catch {
    // Cancellation ownership is already released.
  }
}

export function speak(
  message: string,
  priority: SpeechPriority = "interaction",
  callbacks: SpeechDeliveryCallbacks = {}
): boolean {
  if (!("speechSynthesis" in window) || typeof SpeechSynthesisUtterance === "undefined") {
    publishSpeechOutputStatus("unavailable");
    return false;
  }
  if (shouldBlockSpeechDuringRecognition(speechRecognitionActive, priority)) {
    return false;
  }
  if (!shouldPreemptSpeech(activeSpeechPriority, priority)) {
    return false;
  }

  let utterance: SpeechSynthesisUtterance;
  try {
    utterance = new SpeechSynthesisUtterance(message);
    utterance.lang = "ko-KR";
    utterance.rate = 0.95;
  } catch {
    publishSpeechOutputStatus("failed");
    callbacks.onFailure?.();
    return false;
  }
  const previousFailure = activeSpeechFailure;
  const sequence = speechSequence + 1;
  speechSequence = sequence;
  clearSpeechDeadlines();
  setActiveSpeechPriority(priority);
  activeSpeechFailure = callbacks.onFailure ?? null;
  activeSpeechCancellation = callbacks.onCancel ?? null;
  try {
    previousFailure?.();
  } catch {
    // A superseded callback must not strand the newly accepted speech owner.
  }
  try {
    speechStartDeadline = setTimeout(() => {
      speechStartDeadline = null;
      failSpeech(sequence);
    }, 1_500);
    utterance.onstart = () => {
      if (speechSequence !== sequence) return;
      clearSpeechStartDeadline();
      clearSpeechTerminalDeadline();
      speechTerminalDeadline = setTimeout(() => failSpeech(sequence), terminalDeadlineMs(message));
      publishSpeechOutputStatus("available");
      callbacks.onStart?.();
    };
    utterance.onend = () => {
      if (speechSequence !== sequence) return;
      clearSpeechDeadlines();
      publishSpeechOutputStatus("available");
      setActiveSpeechPriority(null);
      activeSpeechFailure = null;
      activeSpeechCancellation = null;
      callbacks.onEnd?.();
    };
    utterance.onerror = () => failSpeech(sequence);
    if (priority === "risk") {
      window.dispatchEvent(new Event(WALKSAFE_URGENT_SPEECH_EVENT));
    }
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(utterance);
    return true;
  } catch {
    failSpeech(sequence);
    return false;
  }
}

export function vibrate(pattern: VibratePattern | null) {
  if (pattern !== null && "vibrate" in navigator) {
    navigator.vibrate(pattern);
  }
}

export function vibrationForRiskLevel(riskLevel: RiskLevel, speechEnabled: boolean): VibratePattern | null {
  if (!shouldVibrateForRiskLevel(riskLevel)) {
    return null;
  }
  return speechEnabled ? [220, 90, 220] : [460, 140, 460];
}

export function alertForDetection(detection: DetectionEvent, speechEnabled: boolean, riskLevel: RiskLevel) {
  const alert = RISK_ALERTS[detection.class_name];
  if (riskLevel === "high") {
    return {
      speech: `${alert.speech} 멈추세요.`,
      vibration: vibrationForRiskLevel(riskLevel, speechEnabled)
    };
  }

  return {
    speech: alert.speech,
    vibration: null
  };
}

export function alertForTwoModelDetection(
  detection: TwoModelDetection,
  speechEnabled: boolean,
  riskLevel: RiskLevel
) {
  const label = labelForTwoModelDetection(detection);
  const speech = `${label}. 전방 주의.`;

  return {
    speech: riskLevel === "high" ? `${speech} 멈추세요.` : speech,
    vibration: vibrationForRiskLevel(riskLevel, speechEnabled)
  };
}
