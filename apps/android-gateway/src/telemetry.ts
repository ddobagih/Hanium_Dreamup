import { randomUUID } from "node:crypto";

import {
  SERVER_CAPACITY_LEVELS,
  type ServerCapacityTelemetryLevel
} from "./server-capacity.js";
import {
  GATEWAY_PUBLIC_ROUTE_TEMPLATES, matchGatewayRoute,
  type GatewayRouteTemplate
} from "./route-registry.js";

export const GATEWAY_TELEMETRY_SCHEMA = "walksafe.gateway-telemetry.v3";
export const GATEWAY_REQUEST_ID_HEADER = "x-request-id";
// Above the longest relay deadline (TTS 65 s) so slow responses are not clipped.
export const GATEWAY_TELEMETRY_MAX_LATENCY_MS = 180_000;

export const GATEWAY_ROUTE_TEMPLATES = GATEWAY_PUBLIC_ROUTE_TEMPLATES;
export type { GatewayRouteTemplate };

/**
 * S19-56: "prepared" is recorded when the Response object exists; "finished" only
 * when its body was fully handed to the transport (completed), the consumer
 * cancelled it (aborted, e.g. client socket closed), or the body failed (stream_error).
 */
export type GatewayRequestOutcome = "prepared" | "completed" | "aborted" | "stream_error";

export type GatewayTelemetryEvent = Readonly<{
  schema_version: typeof GATEWAY_TELEMETRY_SCHEMA;
  event_name: "walksafe.gateway.request.prepared" | "walksafe.gateway.request.finished";
  outcome: GatewayRequestOutcome;
  correlation_id: string;
  route_template: GatewayRouteTemplate;
  http_status: number;
  latency_ms: number;
  first_byte_ms: number | null;
  is_5xx: boolean;
  capacity_level: ServerCapacityTelemetryLevel;
}>;

export type GatewayTelemetrySink = (event: GatewayTelemetryEvent) => void;

const CANONICAL_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
export function gatewayRouteTemplate(request: Request): GatewayRouteTemplate | null {
  return matchGatewayRoute(new URL(request.url))?.template ?? null;
}

export function gatewayCorrelationId(request: Request): string {
  const inbound = request.headers.get(GATEWAY_REQUEST_ID_HEADER) ?? "";
  return CANONICAL_UUID.test(inbound) ? inbound : randomUUID();
}

export function withGatewayRequestId(response: Response, correlationId: string): Response {
  const headers = new Headers(response.headers);
  headers.set(GATEWAY_REQUEST_ID_HEADER, correlationId);
  return new Response(response.body, {
    status: response.status,
    statusText: response.statusText,
    headers
  });
}

function boundedLatencyMs(elapsedMs: number): number {
  if (!Number.isFinite(elapsedMs)) return GATEWAY_TELEMETRY_MAX_LATENCY_MS;
  return Math.max(
    0,
    Math.min(GATEWAY_TELEMETRY_MAX_LATENCY_MS, Math.round(elapsedMs))
  );
}

function boundedCapacityLevel(
  capacityLevel: ServerCapacityTelemetryLevel
): ServerCapacityTelemetryLevel {
  return capacityLevel === "UNKNOWN" ||
    SERVER_CAPACITY_LEVELS.includes(capacityLevel)
    ? capacityLevel
    : "UNKNOWN";
}

function emit(
  sink: GatewayTelemetrySink | undefined,
  event: Omit<GatewayTelemetryEvent, "schema_version" | "is_5xx" | "capacity_level" | "latency_ms" | "first_byte_ms"> & {
    elapsedMs: number;
    firstByteMs: number | null;
    capacityLevel: ServerCapacityTelemetryLevel;
  }
): void {
  if (!sink) return;
  const { elapsedMs, firstByteMs, capacityLevel, ...fields } = event;
  const frozen: GatewayTelemetryEvent = Object.freeze({
    schema_version: GATEWAY_TELEMETRY_SCHEMA,
    event_name: fields.event_name,
    outcome: fields.outcome,
    correlation_id: fields.correlation_id,
    route_template: fields.route_template,
    http_status: fields.http_status,
    latency_ms: boundedLatencyMs(elapsedMs),
    first_byte_ms: firstByteMs === null ? null : boundedLatencyMs(firstByteMs),
    is_5xx: fields.http_status >= 500 && fields.http_status <= 599,
    capacity_level: boundedCapacityLevel(capacityLevel)
  });
  try {
    sink(frozen);
  } catch {
    // Telemetry must never change the gateway response.
  }
}

export function recordGatewayRequestPrepared(
  correlationId: string,
  routeTemplate: GatewayRouteTemplate,
  response: Response,
  elapsedMs: number,
  capacityLevel: ServerCapacityTelemetryLevel,
  sink: GatewayTelemetrySink | undefined
): void {
  emit(sink, {
    event_name: "walksafe.gateway.request.prepared",
    outcome: "prepared",
    correlation_id: correlationId,
    route_template: routeTemplate,
    http_status: response.status,
    elapsedMs,
    firstByteMs: null,
    capacityLevel
  });
}

export function recordGatewayRequestFinished(
  correlationId: string,
  routeTemplate: GatewayRouteTemplate,
  response: Response,
  outcome: Exclude<GatewayRequestOutcome, "prepared">,
  elapsedMs: number,
  firstByteMs: number | null,
  capacityLevel: ServerCapacityTelemetryLevel,
  sink: GatewayTelemetrySink | undefined
): void {
  emit(sink, {
    event_name: "walksafe.gateway.request.finished",
    outcome,
    correlation_id: correlationId,
    route_template: routeTemplate,
    http_status: response.status,
    elapsedMs,
    firstByteMs,
    capacityLevel
  });
}

/**
 * Returns an equivalent Response whose body reports how delivery ended. The wrapper
 * pulls on demand (no extra buffering) and forwards cancellation to the source.
 */
export function observeGatewayResponseDelivery(
  response: Response,
  startedAt: number,
  onFinished: (
    outcome: Exclude<GatewayRequestOutcome, "prepared">,
    elapsedMs: number,
    firstByteMs: number | null
  ) => void,
  now: () => number = () => performance.now()
): Response {
  let settled = false;
  let firstByteMs: number | null = null;
  const settle = (outcome: Exclude<GatewayRequestOutcome, "prepared">): void => {
    if (settled) return;
    settled = true;
    try {
      onFinished(outcome, now() - startedAt, firstByteMs);
    } catch {
      // Telemetry must never change the gateway response.
    }
  };
  if (!response.body) {
    settle("completed");
    return response;
  }
  const reader = response.body.getReader();
  const body = new ReadableStream<Uint8Array>({
    async pull(controller) {
      let chunk: ReadableStreamReadResult<Uint8Array>;
      try {
        chunk = await reader.read();
      } catch (error) {
        settle("stream_error");
        controller.error(error);
        return;
      }
      if (chunk.done) {
        settle("completed");
        controller.close();
        return;
      }
      if (firstByteMs === null && chunk.value.byteLength > 0) firstByteMs = now() - startedAt;
      controller.enqueue(chunk.value);
    },
    async cancel(reason) {
      settle("aborted");
      await reader.cancel(reason);
    }
  }, { highWaterMark: 0 });
  return new Response(body, {
    status: response.status,
    statusText: response.statusText,
    headers: response.headers
  });
}

export function writeGatewayTelemetry(event: GatewayTelemetryEvent): void {
  console.error(JSON.stringify(event));
}

/**
 * S19-20 (w22 review): state events that are not tied to one request. They carry only
 * clock arithmetic (no account, device, or request identifiers).
 */
export const GATEWAY_STATE_TELEMETRY_SCHEMA = "walksafe.gateway-state-telemetry.v1";

export type GatewayClockTelemetryMode =
  | "reanchor_after_window"
  | "slew_after_window"
  | "reanchor_clamp_after_window";

export type GatewayStateTelemetryEvent = Readonly<{
  schema_version: typeof GATEWAY_STATE_TELEMETRY_SCHEMA;
  event_name:
    | "walksafe.gateway.clock.step_detected"
    | "walksafe.gateway.clock.lease_clamped";
  /** Signed wall-clock step (ms) against the monotonic clock; null for a lease clamp. */
  step_ms: number | null;
  /** Absorbed skew (wall - server, ms) right after the event. */
  skew_ms: number;
  mode: GatewayClockTelemetryMode | null;
  detected_at: "runtime" | "restart";
}>;

export type GatewayStateTelemetrySink = (event: GatewayStateTelemetryEvent) => void;

let stateTelemetrySinkForTests: GatewayStateTelemetrySink | null = null;

export function setGatewayStateTelemetrySinkForTests(sink: GatewayStateTelemetrySink | null): void {
  if (process.env.NODE_ENV === "production") {
    throw new Error("gateway state telemetry sink override is unavailable in production");
  }
  stateTelemetrySinkForTests = sink;
}

export function recordGatewayStateEvent(
  fields: Omit<GatewayStateTelemetryEvent, "schema_version">
): void {
  const sink: GatewayStateTelemetrySink | null = stateTelemetrySinkForTests ??
    (process.env.NODE_ENV === "test" ? null : (event) => console.error(JSON.stringify(event)));
  if (!sink) return;
  try {
    sink(Object.freeze({ schema_version: GATEWAY_STATE_TELEMETRY_SCHEMA, ...fields }));
  } catch {
    // Telemetry must never change ledger behavior.
  }
}
