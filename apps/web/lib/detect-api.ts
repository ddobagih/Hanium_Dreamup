/**
 * Adapts the legacy detect endpoint to the strict v1 client contract with timeout and offline gates.
 * A malformed detection invalidates the complete frame; transport failures never substitute a fake result.
 */
import {
  DETECTION_CLASS_NAMES,
  classNameForId,
  type ClassId,
  type DetectionClassName,
  type DetectionEvent,
  type GpsFix,
  type NormalizedBBox
} from "@/types/inference";
import { notifyGatewaySessionInvalid } from "./gateway-session-client";

export const DETECT_V1_SAFETY_DEADLINE_MS = 1800;
const CLASS_IDS = new Set<number>([0, 1, 2, 3]);

export const DETECT_API_BASE = "/api";

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
  if (x < 0 || y < 0 || width <= 0 || height <= 0 || x + width > 1 || y + height > 1) {
    return null;
  }

  return {
    x,
    y,
    width,
    height
  };
}

function toGps(value: unknown): GpsFix | null {
  if (!isRecord(value)) {
    return null;
  }

  const latitude = finiteNumber(value.latitude);
  const longitude = finiteNumber(value.longitude);
  if (latitude === null || longitude === null || latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) {
    return null;
  }

  const accuracy = value.accuracy_m;
  const parsedAccuracy = accuracy === null || accuracy === undefined ? null : finiteNumber(accuracy);
  if ((accuracy !== null && accuracy !== undefined && parsedAccuracy === null) || (parsedAccuracy !== null && parsedAccuracy < 0)) {
    return null;
  }
  return {
    latitude,
    longitude,
    accuracy_m: parsedAccuracy
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

function isValidContext(context: DetectContext): boolean {
  if (timestampMs(context.captured_at) === null) {
    return false;
  }
  if (context.gps && toGps(context.gps) === null) {
    return false;
  }
  if (context.gps?.speed_mps !== null && context.gps?.speed_mps !== undefined) {
    const speed = finiteNumber(context.gps.speed_mps);
    if (speed === null || speed < 0) {
      return false;
    }
  }
  return context.heading === null || toHeading(context.heading) !== null;
}

function gpsMatchesContext(value: unknown, expected: GpsFix | null): boolean {
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
    actual.accuracy_m === expected.accuracy_m
  );
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
  const externalSignal = init.signal;
  const abortFromCaller = () => abortController.abort();
  if (externalSignal?.aborted) abortController.abort();
  externalSignal?.addEventListener("abort", abortFromCaller, { once: true });
  const timeoutId = window.setTimeout(() => abortController.abort(), DETECT_V1_SAFETY_DEADLINE_MS);
  let response: Response;

  try {
    response = await fetch(url, {
      ...init,
      cache: "no-store",
      signal: abortController.signal
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") {
      if (externalSignal?.aborted) {
        throw new DetectApiError("탐지 요청이 취소됐습니다.", 0, "cancelled");
      }
      throw new DetectApiError("서버 탐지 시간이 초과됐습니다.", 0, "timeout");
    }
    throw new DetectApiError("탐지 서버에 연결할 수 없습니다.", 0, "network_error");
  } finally {
    window.clearTimeout(timeoutId);
    externalSignal?.removeEventListener("abort", abortFromCaller);
  }

  notifyGatewaySessionInvalid(response);
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
  if (
    classId === null ||
    confidence === null ||
    confidence < 0 ||
    confidence > 1 ||
    bbox === null ||
    payload.class_name !== classNameForId(classId) ||
    payload.source !== "server"
  ) {
    return null;
  }

  const className = toClassName(payload.class_name);
  const capturedAtMs = timestampMs(payload.captured_at);
  const expectedCapturedAtMs = timestampMs(fallbackContext.captured_at);
  const responseHeading = payload.heading === null || payload.heading === undefined ? null : toHeading(payload.heading);
  if (
    className === null ||
    capturedAtMs === null ||
    expectedCapturedAtMs === null ||
    capturedAtMs !== expectedCapturedAtMs ||
    !gpsMatchesContext(payload.gps, fallbackContext.gps) ||
    (payload.heading !== null && payload.heading !== undefined && responseHeading === null) ||
    responseHeading !== fallbackContext.heading
  ) {
    return null;
  }

  return {
    class_id: classId,
    class_name: className,
    confidence,
    bbox,
    captured_at: fallbackContext.captured_at as string,
    source: "server",
    gps: fallbackContext.gps,
    heading: fallbackContext.heading
  };
}

export function parseDetectFramePayload(payload: unknown, fallbackContext: DetectContext): DetectFrameResult | null {
  if (
    !isValidContext(fallbackContext) ||
    !isRecord(payload) ||
    payload.model_status !== "ready" ||
    typeof payload.model_version !== "string" ||
    payload.model_version.trim() === "" ||
    !Array.isArray(payload.detections)
  ) {
    return null;
  }

  const detections = payload.detections.map((detection) => detectionFromPayload(detection, fallbackContext));
  if (detections.some((detection) => detection === null)) {
    return null;
  }
  return {
    model_version: payload.model_version,
    detections: detections as DetectionEvent[]
  };
}

export function parseDetectHealthPayload(payload: unknown): DetectHealthResponse | null {
  if (!isRecord(payload) || (payload.model_status !== "ready" && payload.model_status !== "unavailable")) {
    return null;
  }
  const modelVersion = payload.model_version;
  const reason = payload.reason;
  if (modelVersion !== null && (typeof modelVersion !== "string" || modelVersion.trim() === "")) {
    return null;
  }
  if (payload.model_status === "ready") {
    return typeof modelVersion === "string" && reason === null
      ? { model_status: "ready", model_version: modelVersion, reason: null }
      : null;
  }
  return typeof reason === "string" && reason.trim() !== ""
    ? { model_status: "unavailable", model_version: modelVersion, reason }
    : null;
}

export async function fetchDetectHealth(signal?: AbortSignal): Promise<DetectHealthResponse> {
  const payload = await fetchWithTimeout(`${DETECT_API_BASE}/detect/health`, { signal }, "탐지 상태 확인 실패");
  const parsed = parseDetectHealthPayload(payload);
  if (!parsed) {
    throw new DetectApiError("탐지 서버 상태 응답을 확인해 주세요.", 0, "invalid_response");
  }

  return parsed;
}

export async function detectFrame(image: Blob, context: DetectContext, signal?: AbortSignal): Promise<DetectFrameResult> {
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
      body,
      signal
    },
    "서버 탐지 실패"
  );

  const parsed = parseDetectFramePayload(payload, requestContext);
  if (!parsed) {
    throw new DetectApiError("탐지 서버 응답을 확인해 주세요.", 0, "invalid_response");
  }

  return parsed;
}
