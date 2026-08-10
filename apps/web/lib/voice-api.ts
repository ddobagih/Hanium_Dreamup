/**
 * Uploads recorded audio to the voice service and normalizes its STT/intent response boundary.
 * Unsupported intents become unknown, and offline/timeout failures remain explicit to the caller.
 */
import { notifyGatewaySessionInvalid } from "./gateway-session-client";

const VOICE_STT_TIMEOUT_MS = 6000;
const VOICE_EXECUTION_CONFIDENCE_MIN = 0.7;

export const VOICE_API_BASE = "/api";

export type VoiceIntent =
  | "create_report"
  | "voice_on"
  | "voice_off"
  | "repeat_last"
  | "set_destination"
  | "cancel_destination"
  | "select_destination_candidate"
  | "start_navigation"
  | "reroute_navigation"
  | "stop_navigation"
  | "next_navigation_instruction"
  | "get_current_location"
  | "unknown";

export type VoiceIntentAction = "execute" | "reprompt";

export type VoiceSttResponse = {
  transcript: string;
  normalized?: string;
  intent: VoiceIntent;
  confidence?: number;
  score?: number;
  acoustic_confidence?: number | null;
  avg_logprob?: number | null;
  no_speech_probability?: number | null;
  acoustic_execution_allowed?: boolean | null;
  slots: Record<string, unknown>;
  action?: VoiceIntentAction;
  should_execute?: boolean;
  reason?: string | null;
  prompt?: string | null;
  language?: string | null;
  duration_sec?: number;
  model?: string;
  segments?: unknown[];
};

const VOICE_INTENTS = new Set<VoiceIntent>([
  "create_report",
  "voice_on",
  "voice_off",
  "repeat_last",
  "set_destination",
  "cancel_destination",
  "select_destination_candidate",
  "start_navigation",
  "reroute_navigation",
  "stop_navigation",
  "next_navigation_instruction",
  "get_current_location",
  "unknown"
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function normalizeIntent(value: unknown): VoiceIntent {
  return typeof value === "string" && VOICE_INTENTS.has(value as VoiceIntent) ? (value as VoiceIntent) : "unknown";
}

function unitNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) && value >= 0 && value <= 1 ? value : null;
}

export function speechConfidence(result: Pick<VoiceSttResponse, "confidence" | "score">): number {
  return unitNumber(result.confidence ?? result.score) ?? 0;
}

export function voiceExecutionAllowed(result: VoiceSttResponse): boolean {
  return (
    result.should_execute === true &&
    result.action === "execute" &&
    result.intent !== "unknown" &&
    speechConfidence(result) >= VOICE_EXECUTION_CONFIDENCE_MIN &&
    result.acoustic_execution_allowed === true
  );
}

function boundedText(value: unknown, maxLength: number): string | null {
  return typeof value === "string" && value.trim() !== "" && value.length <= maxLength ? value : null;
}

function audioFileName(mimeType: string) {
  if (mimeType.includes("webm")) {
    return "command.webm";
  }
  if (mimeType.includes("mp4") || mimeType.includes("m4a")) {
    return "command.m4a";
  }
  if (mimeType.includes("wav") || mimeType.includes("wave")) {
    return "command.wav";
  }
  if (mimeType.includes("ogg")) {
    return "command.ogg";
  }
  return "command.audio";
}

function errorMessageFromPayload(payload: unknown, fallback: string) {
  if (!isRecord(payload)) {
    return fallback;
  }

  const detail = payload.detail;
  if (isRecord(detail)) {
    const code = typeof detail.code === "string" ? detail.code : "";
    const message = typeof detail.message === "string" ? detail.message : "";
    if (code && message) {
      return `${code}: ${message}`;
    }
    if (message) {
      return message;
    }
    if (code) {
      return code;
    }
  }

  if (typeof detail === "string" && detail) {
    return detail;
  }

  const code = typeof payload.code === "string" ? payload.code : "";
  const message = typeof payload.message === "string" ? payload.message : "";
  if (code && message) {
    return `${code}: ${message}`;
  }
  if (message) {
    return message;
  }
  if (code) {
    return code;
  }

  return fallback;
}

async function parseVoiceJson(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    return response.json();
  }

  const text = await response.text();
  return text ? { message: text } : null;
}

export function parseVoiceSttPayload(payload: unknown): VoiceSttResponse {
  if (!isRecord(payload)) {
    throw new Error("음성 서버 응답을 확인해 주세요.");
  }

  const transcript = boundedText(payload.transcript, 1000);
  const normalized = boundedText(payload.normalized, 1000);
  const intent = normalizeIntent(payload.intent);
  const confidence = unitNumber(payload.confidence);
  const score = unitNumber(payload.score);
  const acousticConfidence = payload.acoustic_confidence === null ? null : unitNumber(payload.acoustic_confidence);
  const avgLogprob =
    payload.avg_logprob === null
      ? null
      : typeof payload.avg_logprob === "number" && Number.isFinite(payload.avg_logprob)
        ? payload.avg_logprob
        : undefined;
  const noSpeechProbability =
    payload.no_speech_probability === null ? null : unitNumber(payload.no_speech_probability);
  const acousticExecutionAllowed =
    payload.acoustic_execution_allowed === null || typeof payload.acoustic_execution_allowed === "boolean"
      ? payload.acoustic_execution_allowed
      : undefined;
  const action = payload.action === "execute" || payload.action === "reprompt" ? payload.action : null;
  const shouldExecute = typeof payload.should_execute === "boolean" ? payload.should_execute : null;
  const reason = payload.reason === null ? null : boundedText(payload.reason, 300);
  const prompt = payload.prompt === null ? null : boundedText(payload.prompt, 300);
  const language = payload.language === null || payload.language === undefined ? payload.language : boundedText(payload.language, 32);
  const model = payload.model === undefined ? undefined : boundedText(payload.model, 160);
  const coherentPolicy =
    (action === "execute" &&
      shouldExecute === true &&
      intent !== "unknown" &&
      confidence !== null &&
      confidence >= VOICE_EXECUTION_CONFIDENCE_MIN &&
      acousticExecutionAllowed === true &&
      acousticConfidence !== null &&
      avgLogprob !== null &&
      avgLogprob !== undefined &&
      noSpeechProbability !== null) ||
    (action === "reprompt" && shouldExecute === false);
  const durationSec = payload.duration_sec === undefined ? undefined : typeof payload.duration_sec === "number" && Number.isFinite(payload.duration_sec) && payload.duration_sec >= 0 ? payload.duration_sec : null;
  if (
    transcript === null ||
    normalized === null ||
    payload.intent !== intent ||
    confidence === null ||
    score === null ||
    !isRecord(payload.slots) ||
    !coherentPolicy ||
    (action === "execute" && (reason !== null || prompt !== null)) ||
    (action === "reprompt" && (reason === null || prompt === null)) ||
    durationSec === null ||
    (payload.reason !== null && reason === null) ||
    (payload.prompt !== null && prompt === null) ||
    (payload.language !== null && payload.language !== undefined && language === null) ||
    (payload.model !== undefined && model === null) ||
    (payload.segments !== undefined && (!Array.isArray(payload.segments) || payload.segments.length > 200))
  ) {
    throw new Error("음성 서버 응답을 확인해 주세요.");
  }
  if (
    (action === "execute" && intent === "set_destination" &&
      boundedText(payload.slots.destination, 200) === null) ||
    (action === "execute" && intent === "select_destination_candidate" &&
      (typeof payload.slots.candidate_index !== "number" ||
        !Number.isInteger(payload.slots.candidate_index) ||
        payload.slots.candidate_index < 1))
  ) {
    throw new Error("음성 명령 슬롯 응답을 확인해 주세요.");
  }

  return {
    transcript,
    normalized,
    intent,
    confidence,
    score,
    acoustic_confidence: acousticConfidence,
    avg_logprob: avgLogprob,
    no_speech_probability: noSpeechProbability,
    acoustic_execution_allowed: acousticExecutionAllowed,
    slots: payload.slots,
    action: action as VoiceIntentAction,
    should_execute: shouldExecute as boolean,
    reason,
    prompt,
    language: language as string | null | undefined,
    duration_sec: durationSec,
    model: model as string | undefined,
    segments: payload.segments as unknown[] | undefined
  };
}

export async function uploadSpeechStt(audio: Blob, signal?: AbortSignal): Promise<VoiceSttResponse> {
  if (typeof navigator !== "undefined" && navigator.onLine === false) {
    throw new Error("오프라인 상태입니다. 음성 명령 서버로 전송하지 않습니다.");
  }
  const body = new FormData();
  const contentType = audio.type || "audio/webm";
  body.append("audio", audio, audioFileName(contentType));

  const abortController = new AbortController();
  const abortFromCaller = () => abortController.abort();
  if (signal?.aborted) abortController.abort();
  signal?.addEventListener("abort", abortFromCaller, { once: true });
  const timeoutId = window.setTimeout(() => abortController.abort(), VOICE_STT_TIMEOUT_MS);
  let response: Response;

  try {
    response = await fetch(`${VOICE_API_BASE}/speech/stt`, {
      method: "POST",
      body,
      cache: "no-store",
      signal: abortController.signal
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      if (signal?.aborted) {
        throw new Error("음성 명령이 취소됐습니다.");
      }
      throw new Error("음성 명령 시간이 초과됐습니다.");
    }
    throw new Error("음성 서버에 연결할 수 없습니다.");
  } finally {
    window.clearTimeout(timeoutId);
    signal?.removeEventListener("abort", abortFromCaller);
  }

  notifyGatewaySessionInvalid(response);
  const payload = await parseVoiceJson(response);
  if (!response.ok) {
    throw new Error(errorMessageFromPayload(payload, `음성 명령 처리 실패 (${response.status})`));
  }

  return parseVoiceSttPayload(payload);
}
