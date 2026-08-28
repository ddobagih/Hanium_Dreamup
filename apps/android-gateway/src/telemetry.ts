import { randomUUID } from "node:crypto";

import {
  SERVER_CAPACITY_LEVELS,
  type ServerCapacityTelemetryLevel
} from "./server-capacity.js";

export const GATEWAY_TELEMETRY_SCHEMA = "walksafe.gateway-telemetry.v2";
export const GATEWAY_REQUEST_ID_HEADER = "x-request-id";
export const GATEWAY_TELEMETRY_MAX_LATENCY_MS = 60_000;

export const GATEWAY_ROUTE_TEMPLATES = Object.freeze([
  "/api/field-session",
  "/api/field-walk",
  "/api/navigation/walking",
  "/api/navigation/destinations/search",
  "/api/reports/v2",
  "/privacy/rights",
  "/privacy/account-deletions",
  "/privacy/account-deletions/{request_id}/status",
  "/privacy/account-deletions/{request_id}/device-evidence"
] as const);

export type GatewayRouteTemplate = typeof GATEWAY_ROUTE_TEMPLATES[number];

export type GatewayTelemetryEvent = Readonly<{
  schema_version: typeof GATEWAY_TELEMETRY_SCHEMA;
  event_name: "walksafe.gateway.request.completed";
  correlation_id: string;
  route_template: GatewayRouteTemplate;
  http_status: number;
  latency_ms: number;
  is_5xx: boolean;
  capacity_level: ServerCapacityTelemetryLevel;
}>;

export type GatewayTelemetrySink = (event: GatewayTelemetryEvent) => void;

const CANONICAL_UUID =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const ACCOUNT_DELETION_STATUS_ROUTE =
  /^\/privacy\/account-deletions\/[A-Za-z0-9_-]{16,128}\/status$/;
const ACCOUNT_DELETION_EVIDENCE_ROUTE =
  /^\/privacy\/account-deletions\/[A-Za-z0-9_-]{16,128}\/device-evidence$/;
const STATIC_ROUTE_TEMPLATES = new Set<GatewayRouteTemplate>(
  GATEWAY_ROUTE_TEMPLATES.filter(route => !route.includes("{request_id}"))
);

export function gatewayRouteTemplate(request: Request): GatewayRouteTemplate | null {
  const pathname = new URL(request.url).pathname;
  if (STATIC_ROUTE_TEMPLATES.has(pathname as GatewayRouteTemplate)) {
    return pathname as GatewayRouteTemplate;
  }
  if (ACCOUNT_DELETION_STATUS_ROUTE.test(pathname)) {
    return "/privacy/account-deletions/{request_id}/status";
  }
  if (ACCOUNT_DELETION_EVIDENCE_ROUTE.test(pathname)) {
    return "/privacy/account-deletions/{request_id}/device-evidence";
  }
  return null;
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

export function recordGatewayRequestCompleted(
  correlationId: string,
  routeTemplate: GatewayRouteTemplate,
  response: Response,
  elapsedMs: number,
  capacityLevel: ServerCapacityTelemetryLevel,
  sink: GatewayTelemetrySink | undefined
): void {
  if (!sink) return;
  const httpStatus = response.status;
  const event: GatewayTelemetryEvent = Object.freeze({
    schema_version: GATEWAY_TELEMETRY_SCHEMA,
    event_name: "walksafe.gateway.request.completed",
    correlation_id: correlationId,
    route_template: routeTemplate,
    http_status: httpStatus,
    latency_ms: boundedLatencyMs(elapsedMs),
    is_5xx: httpStatus >= 500 && httpStatus <= 599,
    capacity_level: boundedCapacityLevel(capacityLevel)
  });
  try {
    sink(event);
  } catch {
    // Telemetry must never change the gateway response.
  }
}

export function writeGatewayTelemetry(event: GatewayTelemetryEvent): void {
  console.error(JSON.stringify(event));
}
