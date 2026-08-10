/**
 * Enforces the detect.v2 transport/schema boundary and records request plus parser audit evidence.
 * Invalid items are audited, then the complete frame fails closed before safety state is published.
 */
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
import { notifyGatewaySessionInvalid } from "./gateway-session-client";

// A response older than this is not safe to publish. Transport cancellation must use the same
// deadline so one slow request cannot block every newer camera frame for several seconds.
export const DETECT_V2_SAFETY_DEADLINE_MS = 1800;
const DETECT_API_BASE = "/api";
const MODEL_KEYS = new Set<string>(["custom_tactile", "coco_general", "unified_walksafe"]);
const DISTANCE_SOURCES = new Set<string>(["sensor_depth", "manual_fixture", "model_estimate", "unknown"]);
const APPROACH_STATES = new Set<string>(["approaching", "stable", "receding", "unknown"]);
const MAX_REASONABLE_DISTANCE_M = 50;
const MAX_MODEL_CLASS_ID = 4095;
const SOURCE_MODEL_IDENTIFIER_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._ +/@:-]*$/;
const NON_PRODUCTION_MODEL_IDENTIFIER_PATTERN = /(^|[._ +/@:-])(fake|demo|mock)(?=$|[._ +/@:-])/i;
const UNIFIED_CLASS_CONTRACT = [
  { className: "person", category: "vulnerable_road_user" },
  { className: "bicycle", category: "vulnerable_road_user" },
  { className: "car", category: "vehicle" },
  { className: "motorcycle", category: "vehicle" },
  { className: "bus", category: "vehicle" },
  { className: "truck", category: "vehicle" },
  { className: "traffic light", category: "traffic_signal" },
  { className: "normal_tactile_block", category: "tactile_normal" },
  { className: "damaged_tactile_block", category: "tactile_damage" },
  { className: "crosswalk", category: "path_guidance" },
  { className: "curb_step", category: "surface_hazard" },
  { className: "uneven_sidewalk", category: "surface_hazard" },
  { className: "e_scooter_obstruction", category: "obstruction" }
] as const;

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
  if (latitude === null || longitude === null || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
    return null;
  }

  const accuracy = value.accuracy_m;
  const speed = value.speed_mps;
  const parsedAccuracy = accuracy === null || accuracy === undefined ? null : finiteNumber(accuracy);
  const parsedSpeed = speed === null || speed === undefined ? null : finiteNumber(speed);
  if (
    (accuracy !== null && accuracy !== undefined && parsedAccuracy === null) ||
    (speed !== null && speed !== undefined && parsedSpeed === null) ||
    (parsedAccuracy !== null && parsedAccuracy < 0) ||
    (parsedSpeed !== null && parsedSpeed < 0)
  ) {
    return null;
  }
  return {
    latitude,
    longitude,
    accuracy_m: parsedAccuracy,
    speed_mps: parsedSpeed
  };
}

function toHeading(value: unknown): number | null {
  const heading = finiteNumber(value);
  return heading !== null && heading >= 0 && heading < 360 ? heading : null;
}

function timestampMs(value: unknown): number | null {
  if (typeof value !== "string" || value.trim() === "") {
    return null;
  }
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function isValidContext(context: DetectV2Context): boolean {
  if (timestampMs(context.captured_at) === null || (context.gps && toGps(context.gps) === null)) {
    return false;
  }
  return context.heading === null || toHeading(context.heading) !== null;
}

function gpsMatchesContext(value: unknown, expected: GpsFixV2 | null): boolean {
  if (expected === null) {
    return value === null || value === undefined;
  }
  const actual = toGps(value);
  if (!actual) {
    return false;
  }
  return (
    Math.abs(actual.latitude - expected.latitude) <= 1e-7 &&
    Math.abs(actual.longitude - expected.longitude) <= 1e-7 &&
    (actual.accuracy_m ?? null) === (expected.accuracy_m ?? null)
  );
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
  return confidence !== null && confidence >= 0 && confidence <= 1 ? confidence : null;
}

function nonEmptyContractText(value: unknown, maxLength = 160): string | null {
  return typeof value === "string" && value.trim() !== "" && value.length <= maxLength && !/[\u0000-\u001f]/.test(value)
    ? value
    : null;
}

function toSourceModel(value: unknown): string | null {
  const sourceModel = nonEmptyContractText(value);
  if (
    !sourceModel ||
    !SOURCE_MODEL_IDENTIFIER_PATTERN.test(sourceModel) ||
    NON_PRODUCTION_MODEL_IDENTIFIER_PATTERN.test(sourceModel)
  ) {
    return null;
  }
  if (
    sourceModel.startsWith("/") ||
    sourceModel.startsWith("\\") ||
    sourceModel.startsWith("~") ||
    sourceModel.includes("\\") ||
    sourceModel.includes("://") ||
    /^[A-Za-z]:/.test(sourceModel) ||
    sourceModel.split("/").some((part) => part === "" || part === "." || part === "..")
  ) {
    return null;
  }
  return sourceModel;
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
  const externalSignal = init.signal;
  const abortFromCaller = () => abortController.abort();
  if (externalSignal?.aborted) abortController.abort();
  externalSignal?.addEventListener("abort", abortFromCaller, { once: true });
  const timeoutId = globalThis.setTimeout(() => abortController.abort(), DETECT_V2_SAFETY_DEADLINE_MS);
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
      if (externalSignal?.aborted) {
        const message = "탐지 요청이 취소됐습니다.";
        throw new DetectV2ApiError(message, 0, "cancelled", null, requestAudit(requestId, latencyMs, null, null, "cancelled", message));
      }
      const message = "서버 탐지 시간이 초과됐습니다.";
      throw new DetectV2ApiError(message, 0, "timeout", null, requestAudit(requestId, latencyMs, null, null, "timeout", message));
    }
    const message = "탐지 서버에 연결할 수 없습니다.";
    throw new DetectV2ApiError(message, 0, "network_error", null, requestAudit(requestId, latencyMs, null, null, "network_error", message));
  } finally {
    globalThis.clearTimeout(timeoutId);
    externalSignal?.removeEventListener("abort", abortFromCaller);
  }

  notifyGatewaySessionInvalid(response);
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
  const sourceModel = toSourceModel(payload.source_model);
  const modelClassId = finiteNumber(payload.model_class_id);
  const className = nonEmptyContractText(payload.class_name);
  const category = nonEmptyContractText(payload.category);
  const confidence = finiteNumber(payload.confidence);
  const bbox = toBbox(payload.bbox);
  const threshold = finiteNumber(payload.threshold_used);
  if (modelKey === null) {
    return { detection: null, dropReason: "invalid_model_key" };
  }
  if (sourceModel === null) {
    return { detection: null, dropReason: "missing_source_model" };
  }
  if (modelClassId === null || !Number.isInteger(modelClassId) || modelClassId < 0 || modelClassId > MAX_MODEL_CLASS_ID) {
    return { detection: null, dropReason: "invalid_model_class_id" };
  }
  if (className === null) {
    return { detection: null, dropReason: "missing_class_name" };
  }
  if (category === null) {
    return { detection: null, dropReason: "missing_category" };
  }
  if (modelKey === "unified_walksafe") {
    const expectedClass = UNIFIED_CLASS_CONTRACT[modelClassId];
    if (!expectedClass || expectedClass.className !== className || expectedClass.category !== category) {
      return { detection: null, dropReason: "model_class_contract_mismatch" };
    }
  }
  if (confidence === null || confidence < 0 || confidence > 1) {
    return { detection: null, dropReason: "invalid_confidence" };
  }
  if (bbox === null) {
    return { detection: null, dropReason: "invalid_bbox" };
  }
  if (threshold === null || threshold < 0 || threshold > 1) {
    return { detection: null, dropReason: "invalid_threshold" };
  }
  if (confidence < threshold) {
    return { detection: null, dropReason: "confidence_below_threshold" };
  }

  const distance = toDistanceM(payload.distance_m);
  if (payload.distance_m !== null && payload.distance_m !== undefined && distance === null) {
    return { detection: null, dropReason: "invalid_distance" };
  }
  const distanceSource = toDistanceSource(payload.distance_source);
  if (payload.distance_source !== null && payload.distance_source !== undefined && distanceSource === null) {
    return { detection: null, dropReason: "invalid_distance_source" };
  }
  const distanceConfidence = toUnitConfidence(payload.distance_confidence);
  if (payload.distance_confidence !== null && payload.distance_confidence !== undefined && distanceConfidence === null) {
    return { detection: null, dropReason: "invalid_distance_confidence" };
  }
  const approachState = toApproachState(payload.approach_state);
  if (payload.approach_state !== null && payload.approach_state !== undefined && approachState === null) {
    return { detection: null, dropReason: "invalid_approach_state" };
  }
  const capturedAtMs = timestampMs(payload.captured_at);
  const expectedCapturedAtMs = timestampMs(fallbackContext.captured_at);
  if (capturedAtMs === null || expectedCapturedAtMs === null || capturedAtMs !== expectedCapturedAtMs) {
    return { detection: null, dropReason: "invalid_captured_at" };
  }
  const responseHeading = payload.heading === null || payload.heading === undefined ? null : toHeading(payload.heading);
  if (
    !gpsMatchesContext(payload.gps, fallbackContext.gps) ||
    (payload.heading !== null && payload.heading !== undefined && responseHeading === null) ||
    responseHeading !== fallbackContext.heading
  ) {
    return { detection: null, dropReason: "invalid_context" };
  }

  return {
    detection: {
      schema_version: "detect.v2",
      model_key: modelKey,
      source_model: sourceModel,
      model_class_id: modelClassId,
      class_name: className,
      category,
      confidence,
      bbox,
      distance_m: distance,
      distance_source: distanceSource,
      distance_confidence: distanceConfidence,
      approach_state: approachState,
      threshold_used: threshold,
      captured_at: fallbackContext.captured_at as string,
      gps: fallbackContext.gps,
      heading: fallbackContext.heading
    },
    dropReason: null
  };
}

export function parseDetectFrameV2Payload(payload: unknown, fallbackContext: DetectV2Context): ParseDetectFrameV2Result | null {
  if (!isValidContext(fallbackContext) || !isRecord(payload) || payload.schema_version !== "detect.v2" || !Array.isArray(payload.detections)) {
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

export function isCompleteDetectV2Frame(result: ParseDetectFrameV2Result): boolean {
  return (
    result.audit.parser_drop_count === 0 &&
    result.audit.raw_detection_count === result.audit.parsed_detection_count
  );
}

export async function detectFrameV2(image: Blob, context: DetectV2Context, signal?: AbortSignal): Promise<DetectFrameV2Result> {
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
      body,
      signal
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
  if (!isCompleteDetectV2Frame(parsed)) {
    const message = "형식이 잘못된 탐지 항목이 있어 현재 프레임 전체를 제외합니다.";
    throw new DetectV2ApiError(
      message,
      payload.status,
      "invalid_detection_items",
      null,
      requestAudit(payload.requestId, payload.latencyMs, payload.status, parsed.audit, "invalid_detection_items", message)
    );
  }

  return {
    ...parsed,
    audit: requestAudit(payload.requestId, payload.latencyMs, payload.status, parsed.audit, null, null)
  };
}
