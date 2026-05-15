import {
  DETECTION_CLASS_NAMES,
  classNameForId,
  type ClassId,
  type DetectionClassName,
  type DetectionEvent,
  type GpsFix,
  type NormalizedBBox
} from "@/types/inference";

const DEFAULT_DETECT_API_BASE = "http://localhost:8000";
const DETECT_TIMEOUT_MS = 8000;
const CLASS_IDS = new Set<number>([0, 1, 2, 3]);

export const DETECT_API_BASE = (process.env.NEXT_PUBLIC_API_BASE_URL ?? DEFAULT_DETECT_API_BASE).replace(/\/+$/, "");

export type DetectHealthResponse = {
  model_status: "ready" | "unavailable";
  model_version: string | null;
  reason: string | null;
};

export type DetectFrameResult = {
  model_version: string;
  detections: DetectionEvent[];
};

type DetectContext = {
  captured_at?: string;
  gps: GpsFix | null;
  heading: number | null;
};

export class DetectApiError extends Error {
  status: number;
  code: string | null;
  reason: string | null;

  constructor(message: string, status: number, code: string | null = null, reason: string | null = null) {
    super(message);
    this.name = "DetectApiError";
    this.status = status;
    this.code = code;
    this.reason = reason;
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function finiteNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function clamp(value: number, min: number, max: number) {
  return Math.min(Math.max(value, min), max);
}

function toClassId(value: unknown): ClassId | null {
  if (typeof value !== "number" || !Number.isInteger(value) || !CLASS_IDS.has(value)) {
    return null;
  }
  return value as ClassId;
}

function toClassName(value: unknown): DetectionClassName | null {
  return typeof value === "string" && (DETECTION_CLASS_NAMES as readonly string[]).includes(value) ? (value as DetectionClassName) : null;
}

function toBbox(value: unknown): NormalizedBBox | null {
  if (!isRecord(value)) {
    return null;
  }

  const x = finiteNumber(value.x);
  const y = finiteNumber(value.y);
  const width = finiteNumber(value.width);
  const height = finiteNumber(value.height);
  if (x === null || y === null || width === null || height === null) {
    return null;
  }

  return {
    x: clamp(x, 0, 1),
    y: clamp(y, 0, 1),
    width: clamp(width, 0, 1),
    height: clamp(height, 0, 1)
  };
}

function toGps(value: unknown): GpsFix | null {
  if (!isRecord(value)) {
    return null;
  }

  const latitude = finiteNumber(value.latitude);
  const longitude = finiteNumber(value.longitude);
  if (latitude === null || longitude === null) {
    return null;
  }

  const accuracy = value.accuracy_m;
  return {
    latitude,
    longitude,
    accuracy_m: accuracy === null || accuracy === undefined ? null : finiteNumber(accuracy)
  };
}

function errorParts(payload: unknown): { code: string | null; reason: string | null; message: string | null } {
  if (!isRecord(payload)) {
    return { code: null, reason: null, message: null };
  }

  const detail = payload.detail;
  const source = isRecord(detail) ? detail : payload;
  const code = typeof source.code === "string" ? source.code : null;
  const reason = typeof source.reason === "string" ? source.reason : null;
  const message = typeof source.message === "string" ? source.message : typeof detail === "string" ? detail : null;
  return { code, reason, message };
}

function detectErrorMessage(payload: unknown, fallback: string) {
  const { code, reason, message } = errorParts(payload);
  if (code === "model_unavailable") {
    return reason ? `서버 모델 미준비: ${reason}` : "서버 모델 미준비";
  }
  return message || reason || code || fallback;
}

async function parseJson(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    return response.json();
  }

  const text = await response.text();
  return text ? { message: text } : null;
}

async function fetchWithTimeout(url: string, init: RequestInit, fallbackMessage: string): Promise<unknown> {
  const abortController = new AbortController();
  const timeoutId = window.setTimeout(() => abortController.abort(), DETECT_TIMEOUT_MS);
  let response: Response;

  try {
    response = await fetch(url, {
      ...init,
      cache: "no-store",
      signal: abortController.signal
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      throw new DetectApiError("서버 탐지 시간이 초과됐습니다.", 0, "timeout");
    }
    throw new DetectApiError("탐지 서버에 연결할 수 없습니다.", 0, "network_error");
  } finally {
    window.clearTimeout(timeoutId);
  }

  const payload = await parseJson(response);
  if (!response.ok) {
    const parts = errorParts(payload);
    throw new DetectApiError(detectErrorMessage(payload, `${fallbackMessage} (${response.status})`), response.status, parts.code, parts.reason);
  }

  return payload;
}

function detectionFromPayload(payload: unknown, fallbackContext: DetectContext): DetectionEvent | null {
  if (!isRecord(payload)) {
    return null;
  }

  const classId = toClassId(payload.class_id);
  const confidence = finiteNumber(payload.confidence);
  const bbox = toBbox(payload.bbox);
  if (classId === null || confidence === null || bbox === null) {
    return null;
  }

  const className = toClassName(payload.class_name) ?? classNameForId(classId);
  const capturedAt = typeof payload.captured_at === "string" && payload.captured_at ? payload.captured_at : fallbackContext.captured_at ?? new Date().toISOString();
  const heading = finiteNumber(payload.heading) ?? fallbackContext.heading;

  return {
    class_id: classId,
    class_name: className,
    confidence: clamp(confidence, 0, 1),
    bbox,
    captured_at: capturedAt,
    source: "server",
    gps: toGps(payload.gps) ?? fallbackContext.gps,
    heading
  };
}

export async function fetchDetectHealth(): Promise<DetectHealthResponse> {
  const payload = await fetchWithTimeout(`${DETECT_API_BASE}/detect/health`, {}, "탐지 상태 확인 실패");
  if (!isRecord(payload)) {
    throw new DetectApiError("탐지 서버 상태 응답을 확인해 주세요.", 0, "invalid_response");
  }

  return {
    model_status: payload.model_status === "ready" ? "ready" : "unavailable",
    model_version: typeof payload.model_version === "string" ? payload.model_version : null,
    reason: typeof payload.reason === "string" ? payload.reason : null
  };
}

export async function detectFrame(image: Blob, context: DetectContext): Promise<DetectFrameResult> {
  const capturedAt = context.captured_at ?? new Date().toISOString();
  const requestContext: DetectContext = {
    ...context,
    captured_at: capturedAt
  };
  const body = new FormData();
  body.append("context", JSON.stringify(requestContext));
  body.append("image", image, `walksafe-detect-${Date.now()}.jpg`);

  const payload = await fetchWithTimeout(
    `${DETECT_API_BASE}/detect`,
    {
      method: "POST",
      body
    },
    "서버 탐지 실패"
  );

  if (!isRecord(payload) || !Array.isArray(payload.detections)) {
    throw new DetectApiError("탐지 서버 응답을 확인해 주세요.", 0, "invalid_response");
  }

  return {
    model_version: typeof payload.model_version === "string" ? payload.model_version : "server",
    detections: payload.detections
      .map((detection) => detectionFromPayload(detection, requestContext))
      .filter((detection): detection is DetectionEvent => detection !== null)
  };
}
