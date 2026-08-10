import { randomUUID } from "node:crypto";

export const GATEWAY_TELEMETRY_SCHEMA = "walksafe.gateway-telemetry.v1";

export type GatewayTelemetryEvent = Readonly<{
  schema_version: typeof GATEWAY_TELEMETRY_SCHEMA;
  event_name: "walksafe.gateway.proxy.failed" | "walksafe.gateway.privacy.failed";
  severity: "WARN" | "ERROR";
  correlation_id: string;
  route: string;
  method: string;
  http_status: number;
  failure_code: string;
}>;

export type GatewayTelemetrySink = (event: GatewayTelemetryEvent) => void;

const PROXY_ROUTES = new Set([
  "/api/navigation/walking",
  "/api/navigation/destinations/search",
  "/api/reports/v2"
]);

function failureCode(domain: "proxy" | "privacy", status: number): string {
  if (domain === "privacy") {
    if (status === 409) return "privacy_operation_inactive";
    return "privacy_service_unavailable";
  }
  if (status === 499) return "client_cancelled";
  if (status === 502) return "upstream_failure";
  if (status === 503) return "proxy_service_unavailable";
  if (status === 504) return "upstream_timeout";
  return "proxy_internal_failure";
}

export function recordGatewayFailure(
  request: Request,
  response: Response,
  sink: GatewayTelemetrySink | undefined
): void {
  if (!sink) return;
  const route = new URL(request.url).pathname;
  let domain: "proxy" | "privacy" | null = null;
  if (route === "/privacy/rights" && response.status >= 500) {
    domain = "privacy";
  } else if (PROXY_ROUTES.has(route) && response.status === 409) {
    domain = "privacy";
  } else if (
    PROXY_ROUTES.has(route) &&
    (response.status === 499 || response.status >= 500)
  ) {
    domain = "proxy";
  }
  if (!domain) return;

  const event: GatewayTelemetryEvent = Object.freeze({
    schema_version: GATEWAY_TELEMETRY_SCHEMA,
    event_name: domain === "proxy"
      ? "walksafe.gateway.proxy.failed"
      : "walksafe.gateway.privacy.failed",
    severity: response.status >= 500 ? "ERROR" : "WARN",
    correlation_id: randomUUID(),
    route,
    method: request.method,
    http_status: response.status,
    failure_code: failureCode(domain, response.status)
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
