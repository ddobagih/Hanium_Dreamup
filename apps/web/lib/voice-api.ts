const DEFAULT_VOICE_API_BASE = "http://127.0.0.1:9001";
const VOICE_STT_TIMEOUT_MS = 6000;

export const VOICE_API_BASE = (process.env.NEXT_PUBLIC_VOICE_API_BASE ?? DEFAULT_VOICE_API_BASE).replace(/\/+$/, "");

export type VoiceIntent =
  | "create_report"
  | "voice_on"
  | "voice_off"
  | "repeat_last"
  | "set_destination"
  | "select_destination_candidate"
  | "start_navigation"
  | "reroute_navigation"
  | "stop_navigation"
  | "get_current_location"
  | "unknown";

export type VoiceIntentAction = "execute" | "reprompt";

export type VoiceSttResponse = {
  transcript: string;
  normalized?: string;
  intent: VoiceIntent;
  confidence?: number;
  score?: number;
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
  "select_destination_candidate",
  "start_navigation",
  "reroute_navigation",
  "stop_navigation",
  "get_current_location",
  "unknown"
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function normalizeIntent(value: unknown): VoiceIntent {
  return typeof value === "string" && VOICE_INTENTS.has(value as VoiceIntent) ? (value as VoiceIntent) : "unknown";
}

export function speechConfidence(result: Pick<VoiceSttResponse, "confidence" | "score">): number {
  return result.confidence ?? result.score ?? 0;
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

export async function uploadSpeechStt(audio: Blob): Promise<VoiceSttResponse> {
  if (typeof navigator !== "undefined" && navigator.onLine === false) {
    throw new Error("오프라인 상태입니다. 음성 명령 서버로 전송하지 않습니다.");
  }
  const body = new FormData();
  const contentType = audio.type || "audio/webm";
  body.append("audio", audio, audioFileName(contentType));

  const abortController = new AbortController();
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
      throw new Error("음성 명령 시간이 초과됐습니다.");
    }
    throw new Error("음성 서버에 연결할 수 없습니다.");
  } finally {
    window.clearTimeout(timeoutId);
  }

  const payload = await parseVoiceJson(response);
  if (!response.ok) {
    throw new Error(errorMessageFromPayload(payload, `음성 명령 처리 실패 (${response.status})`));
  }

  if (!isRecord(payload)) {
    throw new Error("음성 서버 응답을 확인해 주세요.");
  }

  return {
    transcript: typeof payload.transcript === "string" ? payload.transcript : "",
    normalized: typeof payload.normalized === "string" ? payload.normalized : undefined,
    intent: normalizeIntent(payload.intent),
    confidence: typeof payload.confidence === "number" ? payload.confidence : undefined,
    score: typeof payload.score === "number" ? payload.score : undefined,
    slots: isRecord(payload.slots) ? payload.slots : {},
    action: payload.action === "execute" || payload.action === "reprompt" ? payload.action : undefined,
    should_execute: typeof payload.should_execute === "boolean" ? payload.should_execute : undefined,
    reason: typeof payload.reason === "string" || payload.reason === null ? payload.reason : undefined,
    prompt: typeof payload.prompt === "string" || payload.prompt === null ? payload.prompt : undefined,
    language: typeof payload.language === "string" || payload.language === null ? payload.language : undefined,
    duration_sec: typeof payload.duration_sec === "number" ? payload.duration_sec : undefined,
    model: typeof payload.model === "string" ? payload.model : undefined,
    segments: Array.isArray(payload.segments) ? payload.segments : undefined
  };
}
