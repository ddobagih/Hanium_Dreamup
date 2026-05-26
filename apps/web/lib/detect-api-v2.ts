import type { DetectionDistanceSource, GpsFixV2, NormalizedBBoxV2, TwoModelDetection, TwoModelKey } from "@/types/inference-v2";

const DEFAULT_DETECT_API_BASE = "http://localhost:8000";
const DETECT_TIMEOUT_MS = 8000;
const DETECT_API_BASE = (process.env.NEXT_PUBLIC_API_BASE_URL ?? DEFAULT_DETECT_API_BASE).replace(/\/+$/, "");
const MODEL_KEYS = new Set<string>(["custom_tactile", "coco_general"]);
const DISTANCE_SOURCES = new Set<string>(["sensor_depth", "manual_fixture", "model_estimate", "unknown"]);
const MAX_REASONABLE_DISTANCE_M = 50;

export type DetectFrameV2Result = { schema_version: "detect.v2"; detections: TwoModelDetection[] };

export type DetectV2Context = { captured_at?: string; gps: GpsFixV2 | null; heading: number | null };

export class DetectV2ApiError extends Error {
  status: number;
  code: string | null;
  reason: string | null;

  constructor(message: string, status: number, code: string | null = null, reason: string | null = null) {
    super(message);
    this.name = "DetectV2ApiError";
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

function toModelKey(value: unknown): TwoModelKey | null {
  return typeof value === "string" && MODEL_KEYS.has(value) ? (value as TwoModelKey) : null;
}

function toBbox(value: unknown): NormalizedBBoxV2 | null {
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
  if (x < 0 || y < 0 || width <= 0 || height <= 0 || x > 1 || y > 1 || width > 1 || height > 1) {
    return null;
  }
  if (x + width > 1 || y + height > 1) {
    return null;
  }

  return {
    x,
    y,
    width,
    height
  };
}

function toGps(value: unknown): GpsFixV2 | null {
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

function toDistanceM(value: unknown): number | null {
  if (value === null || value === undefined) {
    return null;
  }

  const distance = finiteNumber(value);
  return distance !== null && distance >= 0 && distance <= MAX_REASONABLE_DISTANCE_M ? distance : null;
}

function toDistanceSource(value: unknown): DetectionDistanceSource | null {
  return typeof value === "string" && DISTANCE_SOURCES.has(value) ? (value as DetectionDistanceSource) : null;
}

function toUnitConfidence(value: unknown): number | null {
  const confidence = finiteNumber(value);
  return confidence !== null ? clamp(confidence, 0, 1) : null;
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
  if (typeof navigator !== "undefined" && navigator.onLine === false) {
    throw new DetectV2ApiError("오프라인 상태입니다. 탐지 API 요청을 보내지 않습니다.", 0, "offline");
  }
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
      throw new DetectV2ApiError("서버 탐지 시간이 초과됐습니다.", 0, "timeout");
    }
    throw new DetectV2ApiError("탐지 서버에 연결할 수 없습니다.", 0, "network_error");
  } finally {
    window.clearTimeout(timeoutId);
  }

  const payload = await parseJson(response);
  if (!response.ok) {
    const parts = errorParts(payload);
    throw new DetectV2ApiError(detectErrorMessage(payload, `${fallbackMessage} (${response.status})`), response.status, parts.code, parts.reason);
  }

  return payload;
}

function detectionFromPayload(payload: unknown, fallbackContext: DetectV2Context): TwoModelDetection | null {
  if (!isRecord(payload) || payload.schema_version !== "detect.v2") {
    return null;
  }

  const modelKey = toModelKey(payload.model_key);
  const sourceModel = typeof payload.source_model === "string" ? payload.source_model : null;
  const modelClassId = finiteNumber(payload.model_class_id);
  const className = typeof payload.class_name === "string" ? payload.class_name : null;
  const category = typeof payload.category === "string" ? payload.category : null;
  const confidence = finiteNumber(payload.confidence);
  const bbox = toBbox(payload.bbox);
  const threshold = finiteNumber(payload.threshold_used);
  if (
    modelKey === null ||
    sourceModel === null ||
    modelClassId === null ||
    !Number.isInteger(modelClassId) ||
    className === null ||
    category === null ||
    confidence === null ||
    bbox === null ||
    threshold === null
  ) {
    return null;
  }

  const capturedAt = typeof payload.captured_at === "string" && payload.captured_at ? payload.captured_at : fallbackContext.captured_at ?? new Date().toISOString();
  const heading = finiteNumber(payload.heading) ?? fallbackContext.heading;

  return {
    schema_version: "detect.v2",
    model_key: modelKey,
    source_model: sourceModel,
    model_class_id: modelClassId,
    class_name: className,
    category,
    confidence: clamp(confidence, 0, 1),
    bbox,
    distance_m: toDistanceM(payload.distance_m),
    distance_source: toDistanceSource(payload.distance_source),
    distance_confidence: toUnitConfidence(payload.distance_confidence),
    threshold_used: clamp(threshold, 0, 1),
    captured_at: capturedAt,
    gps: toGps(payload.gps) ?? fallbackContext.gps,
    heading
  };
}

export function parseDetectFrameV2Payload(payload: unknown, fallbackContext: DetectV2Context): DetectFrameV2Result | null {
  if (!isRecord(payload) || payload.schema_version !== "detect.v2" || !Array.isArray(payload.detections)) {
    return null;
  }

  return {
    schema_version: "detect.v2",
    detections: payload.detections
      .map((detection) => detectionFromPayload(detection, fallbackContext))
      .filter((detection): detection is TwoModelDetection => detection !== null)
  };
}

export async function detectFrameV2(image: Blob, context: DetectV2Context): Promise<DetectFrameV2Result> {
  const capturedAt = context.captured_at ?? new Date().toISOString();
  const requestContext: DetectV2Context = {
    ...context,
    captured_at: capturedAt
  };
  const body = new FormData();
  body.append("context", JSON.stringify(requestContext));
  body.append("image", image, `walksafe-detect-v2-${Date.now()}.jpg`);

  const payload = await fetchWithTimeout(
    `${DETECT_API_BASE}/detect/v2`,
    {
      method: "POST",
      body
    },
    "서버 탐지 실패"
  );

  const parsed = parseDetectFrameV2Payload(payload, requestContext);
  if (!parsed) {
    throw new DetectV2ApiError("탐지 서버 응답을 확인해 주세요.", 0, "invalid_response");
  }

  return parsed;
}
