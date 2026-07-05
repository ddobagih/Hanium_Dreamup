import type {
  DetectionApproachState,
  DetectionDistanceSource,
  DetectV2ParserAudit,
  DetectV2ParserDropReason,
  DetectV2RequestAudit,
  GpsFixV2,
  NormalizedBBoxV2,
  TwoModelDetection,
  TwoModelKey
} from "@/types/inference-v2";

const DEFAULT_DETECT_API_BASE = "http://localhost:8000";
const DETECT_TIMEOUT_MS = 8000;
const DETECT_API_BASE = (process.env.NEXT_PUBLIC_API_BASE_URL ?? DEFAULT_DETECT_API_BASE).replace(/\/+$/, "");
const MODEL_KEYS = new Set<string>(["custom_tactile", "coco_general", "unified_walksafe"]);
const DISTANCE_SOURCES = new Set<string>(["sensor_depth", "manual_fixture", "model_estimate", "unknown"]);
const APPROACH_STATES = new Set<string>(["approaching", "stable", "receding", "unknown"]);
const MAX_REASONABLE_DISTANCE_M = 50;

export type ParseDetectFrameV2Result = { schema_version: "detect.v2"; detections: TwoModelDetection[]; audit: DetectV2ParserAudit };
export type DetectFrameV2Result = { schema_version: "detect.v2"; detections: TwoModelDetection[]; audit: DetectV2RequestAudit };

export type DetectV2Context = { captured_at?: string; gps: GpsFixV2 | null; heading: number | null };

type FetchWithTimeoutResult = {
  payload: unknown;
  requestId: string;
  latencyMs: number;
  status: number;
};

type DetectionPayloadParseResult =
  | { detection: TwoModelDetection; dropReason: null }
  | { detection: null; dropReason: DetectV2ParserDropReason };

export class DetectV2ApiError extends Error {
  status: number;
  code: string | null;
  reason: string | null;
  audit: DetectV2RequestAudit | null;

  constructor(
    message: string,
    status: number,
    code: string | null = null,
    reason: string | null = null,
    audit: DetectV2RequestAudit | null = null
  ) {
    super(message);
    this.name = "DetectV2ApiError";
    this.status = status;
    this.code = code;
    this.reason = reason;
    this.audit = audit;
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
  const speed = value.speed_mps;
  return {
    latitude,
    longitude,
    accuracy_m: accuracy === null || accuracy === undefined ? null : finiteNumber(accuracy),
    speed_mps: speed === null || speed === undefined ? null : finiteNumber(speed)
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

function toApproachState(value: unknown): DetectionApproachState | null {
  return typeof value === "string" && APPROACH_STATES.has(value) ? (value as DetectionApproachState) : null;
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

function createDetectV2RequestId() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return `detect-v2-${crypto.randomUUID()}`;
  }
  return `detect-v2-${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
}

function nowMs() {
  return typeof performance !== "undefined" ? performance.now() : Date.now();
}

function elapsedMs(startMs: number) {
  return Math.max(0, Math.round(nowMs() - startMs));
}

function rawDetectionCount(payload: unknown): number | null {
  return isRecord(payload) && Array.isArray(payload.detections) ? payload.detections.length : null;
}

function summarizeParserDropReasons(reasons: DetectV2ParserDropReason[]) {
  const counts = new Map<DetectV2ParserDropReason, number>();
  for (const reason of reasons) {
    counts.set(reason, (counts.get(reason) ?? 0) + 1);
  }

  return Array.from(counts, ([reason, count]) => ({ reason, count }));
}

function requestAudit(
  requestId: string,
  latencyMs: number,
  httpStatus: number | null,
  parserAudit: DetectV2ParserAudit | null,
  errorCode: string | null,
  errorMessage: string | null,
  fallbackRawDetectionCount: number | null = null
): DetectV2RequestAudit {
  return {
    request_id: requestId,
    latency_ms: latencyMs,
    http_status: httpStatus,
    raw_detection_count: parserAudit?.raw_detection_count ?? fallbackRawDetectionCount,
    parsed_detection_count: parserAudit?.parsed_detection_count ?? 0,
    parser_drop_count: parserAudit?.parser_drop_count ?? 0,
    parser_drop_reasons: parserAudit?.parser_drop_reasons ?? [],
    error_code: errorCode,
    error_message: errorMessage
  };
}

function requestIdFromResponse(response: Response, fallbackRequestId: string) {
  return response.headers.get("x-request-id") ?? response.headers.get("x-correlation-id") ?? fallbackRequestId;
}

async function parseJson(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    return response.json();
  }

  const text = await response.text();
  return text ? { message: text } : null;
}

async function fetchWithTimeout(url: string, init: RequestInit, fallbackMessage: string, requestId: string): Promise<FetchWithTimeoutResult> {
  const startMs = nowMs();
  if (typeof navigator !== "undefined" && navigator.onLine === false) {
    const message = "오프라인 상태입니다. 탐지 API 요청을 보내지 않습니다.";
    throw new DetectV2ApiError(message, 0, "offline", null, requestAudit(requestId, elapsedMs(startMs), null, null, "offline", message));
  }
  const abortController = new AbortController();
  const timeoutId = globalThis.setTimeout(() => abortController.abort(), DETECT_TIMEOUT_MS);
  let response: Response;

  try {
    response = await fetch(url, {
      ...init,
      cache: "no-store",
      signal: abortController.signal
    });
  } catch (error) {
    const latencyMs = elapsedMs(startMs);
    const isAbortError =
      (typeof DOMException !== "undefined" && error instanceof DOMException && error.name === "AbortError") ||
      (error instanceof Error && error.name === "AbortError");
    if (isAbortError) {
      const message = "서버 탐지 시간이 초과됐습니다.";
      throw new DetectV2ApiError(message, 0, "timeout", null, requestAudit(requestId, latencyMs, null, null, "timeout", message));
    }
    const message = "탐지 서버에 연결할 수 없습니다.";
    throw new DetectV2ApiError(message, 0, "network_error", null, requestAudit(requestId, latencyMs, null, null, "network_error", message));
  } finally {
    globalThis.clearTimeout(timeoutId);
  }

  const responseRequestId = requestIdFromResponse(response, requestId);
  let payload: unknown;
  try {
    payload = await parseJson(response);
  } catch {
    const message = "탐지 서버 응답 JSON을 읽을 수 없습니다.";
    throw new DetectV2ApiError(
      message,
      response.status,
      "invalid_json",
      null,
      requestAudit(responseRequestId, elapsedMs(startMs), response.status, null, "invalid_json", message)
    );
  }

  const latencyMs = elapsedMs(startMs);
  if (!response.ok) {
    const parts = errorParts(payload);
    const errorCode = parts.code ?? "http_error";
    const message = detectErrorMessage(payload, `${fallbackMessage} (${response.status})`);
    throw new DetectV2ApiError(
      message,
      response.status,
      errorCode,
      parts.reason,
      requestAudit(responseRequestId, latencyMs, response.status, null, errorCode, message, rawDetectionCount(payload))
    );
  }

  return {
    payload,
    requestId: responseRequestId,
    latencyMs,
    status: response.status
  };
}

function parseDetectionPayload(payload: unknown, fallbackContext: DetectV2Context): DetectionPayloadParseResult {
  if (!isRecord(payload) || payload.schema_version !== "detect.v2") {
    return { detection: null, dropReason: "invalid_detection_schema" };
  }

  const modelKey = toModelKey(payload.model_key);
  const sourceModel = typeof payload.source_model === "string" ? payload.source_model : null;
  const modelClassId = finiteNumber(payload.model_class_id);
  const className = typeof payload.class_name === "string" ? payload.class_name : null;
  const category = typeof payload.category === "string" ? payload.category : null;
  const confidence = finiteNumber(payload.confidence);
  const bbox = toBbox(payload.bbox);
  const threshold = finiteNumber(payload.threshold_used);
  if (modelKey === null) {
    return { detection: null, dropReason: "invalid_model_key" };
  }
  if (sourceModel === null) {
    return { detection: null, dropReason: "missing_source_model" };
  }
  if (modelClassId === null || !Number.isInteger(modelClassId)) {
    return { detection: null, dropReason: "invalid_model_class_id" };
  }
  if (className === null) {
    return { detection: null, dropReason: "missing_class_name" };
  }
  if (category === null) {
    return { detection: null, dropReason: "missing_category" };
  }
  if (confidence === null) {
    return { detection: null, dropReason: "invalid_confidence" };
  }
  if (bbox === null) {
    return { detection: null, dropReason: "invalid_bbox" };
  }
  if (threshold === null) {
    return { detection: null, dropReason: "invalid_threshold" };
  }

  const capturedAt = typeof payload.captured_at === "string" && payload.captured_at ? payload.captured_at : fallbackContext.captured_at ?? new Date().toISOString();
  const heading = finiteNumber(payload.heading) ?? fallbackContext.heading;

  return {
    detection: {
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
      approach_state: toApproachState(payload.approach_state),
      threshold_used: clamp(threshold, 0, 1),
      captured_at: capturedAt,
      gps: toGps(payload.gps) ?? fallbackContext.gps,
      heading
    },
    dropReason: null
  };
}

export function parseDetectFrameV2Payload(payload: unknown, fallbackContext: DetectV2Context): ParseDetectFrameV2Result | null {
  if (!isRecord(payload) || payload.schema_version !== "detect.v2" || !Array.isArray(payload.detections)) {
    return null;
  }

  const detections: TwoModelDetection[] = [];
  const dropReasons: DetectV2ParserDropReason[] = [];
  for (const item of payload.detections) {
    const parsed = parseDetectionPayload(item, fallbackContext);
    if (parsed.detection) {
      detections.push(parsed.detection);
    } else {
      dropReasons.push(parsed.dropReason);
    }
  }

  return {
    schema_version: "detect.v2",
    detections,
    audit: {
      raw_detection_count: payload.detections.length,
      parsed_detection_count: detections.length,
      parser_drop_count: dropReasons.length,
      parser_drop_reasons: summarizeParserDropReasons(dropReasons)
    }
  };
}

export async function detectFrameV2(image: Blob, context: DetectV2Context): Promise<DetectFrameV2Result> {
  const capturedAt = context.captured_at ?? new Date().toISOString();
  const requestId = createDetectV2RequestId();
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
    "서버 탐지 실패",
    requestId
  );

  const parsed = parseDetectFrameV2Payload(payload.payload, requestContext);
  if (!parsed) {
    const message = "탐지 서버 응답을 확인해 주세요.";
    throw new DetectV2ApiError(
      message,
      payload.status,
      "invalid_response",
      null,
      requestAudit(payload.requestId, payload.latencyMs, payload.status, null, "invalid_response", message, rawDetectionCount(payload.payload))
    );
  }

  return {
    ...parsed,
    audit: requestAudit(payload.requestId, payload.latencyMs, payload.status, parsed.audit, null, null)
  };
}
