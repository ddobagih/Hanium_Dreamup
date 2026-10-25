/** Public HTTP routing metadata. Keep matching and method policies in one place. */
export type GatewayAuthBoundary =
  | "public" | "session-lifecycle" | "field-session" | "field-device"
  | "field-concealed" | "consent-bound" | "raw-consent-device"
  | "deletion-session" | "deletion-capability" | "privacy-variant";
export type GatewayBodyPolicy =
  | "none" | "strict-none" | "json" | "multipart" | "stream" | "by-method";
export type GatewayQueryPolicy = "none" | "validated" | "session" | "privacy-control" | "ignored";
export type GatewayDeadlinePolicy = "ordinary" | "walking" | "search";

type RouteDefinition = Readonly<{
  template: string;
  path: string | RegExp;
  methods: readonly string[];
  auth: GatewayAuthBoundary;
  body: GatewayBodyPolicy;
  query: GatewayQueryPolicy;
  deadline: GatewayDeadlinePolicy;
  family: "static" | "report-status" | "user-report" | "raw" |
    "account-deletion" | "privacy";
  accepts?: (groups: Record<string, string>) => boolean;
}>;

const REPORT_ID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
const RAW_ID = REPORT_ID;
const DELETION_ID = "[A-Za-z0-9_-]{16,128}";
const INDEX = "(?:0|[1-9][0-9]{0,3})";
const pathPattern = (source: string): RegExp => new RegExp(`^${source}$`);

const routes: readonly RouteDefinition[] = [
  { template: "/api/account-signup-policy", path: "/api/account-signup-policy", methods: ["GET"], auth: "public", body: "strict-none", query: "none", deadline: "ordinary", family: "static" },
  { template: "/api/account-enrollments/email-otp", path: "/api/account-enrollments/email-otp", methods: ["POST"], auth: "public", body: "json", query: "none", deadline: "ordinary", family: "static" },
  { template: "/api/accounts", path: "/api/accounts", methods: ["POST"], auth: "public", body: "json", query: "none", deadline: "ordinary", family: "static" },
  { template: "/api/field-session", path: "/api/field-session", methods: ["GET", "POST", "DELETE"], auth: "session-lifecycle", body: "by-method", query: "session", deadline: "ordinary", family: "static" },
  { template: "/api/field-diagnostics/preference", path: "/api/field-diagnostics/preference", methods: ["GET", "POST", "PUT"], auth: "field-session", body: "by-method", query: "none", deadline: "ordinary", family: "static" },
  { template: "/api/field-diagnostics/sessions/{session_id}/batches", path: pathPattern(`/api/field-diagnostics/sessions/(?<sessionId>${REPORT_ID})/batches`), methods: ["POST"], auth: "field-session", body: "json", query: "none", deadline: "ordinary", family: "static" },
  { template: "/api/field-diagnostics/sessions/{session_id}", path: pathPattern(`/api/field-diagnostics/sessions/(?<sessionId>${REPORT_ID})`), methods: ["GET"], auth: "field-session", body: "none", query: "none", deadline: "ordinary", family: "static" },
  { template: "/api/field-walk", path: "/api/field-walk", methods: ["GET", "POST"], auth: "field-device", body: "by-method", query: "none", deadline: "ordinary", family: "static" },
  { template: "/api/speech/stt", path: "/api/speech/stt", methods: ["POST"], auth: "field-session", body: "stream", query: "none", deadline: "ordinary", family: "static" },
  { template: "/api/speech/tts", path: "/api/speech/tts", methods: ["POST"], auth: "field-session", body: "json", query: "none", deadline: "ordinary", family: "static" },
  { template: "/api/navigation/walking", path: "/api/navigation/walking", methods: ["POST"], auth: "field-session", body: "json", query: "ignored", deadline: "walking", family: "static" },
  { template: "/api/navigation/destinations/search", path: "/api/navigation/destinations/search", methods: ["GET"], auth: "field-session", body: "none", query: "validated", deadline: "search", family: "static" },
  { template: "/api/reports/v2", path: "/api/reports/v2", methods: ["POST"], auth: "consent-bound", body: "multipart", query: "ignored", deadline: "ordinary", family: "static" },
  { template: "/api/reports/v2/{report_id}/status", path: pathPattern(`/api/reports/v2/(?<reportId>${REPORT_ID})/status`), methods: ["GET"], auth: "field-concealed", body: "none", query: "none", deadline: "ordinary", family: "report-status" },
  { template: "/api/reports/mine", path: "/api/reports/mine", methods: ["GET"], auth: "field-concealed", body: "none", query: "validated", deadline: "ordinary", family: "user-report" },
  { template: "/api/reports/mine/requests/history", path: "/api/reports/mine/requests/history", methods: ["GET"], auth: "field-concealed", body: "none", query: "validated", deadline: "ordinary", family: "user-report" },
  { template: "/api/reports/mine/deletions/{request_id}", path: pathPattern(`/api/reports/mine/deletions/(?<requestId>${REPORT_ID})`), methods: ["GET"], auth: "field-concealed", body: "none", query: "none", deadline: "ordinary", family: "user-report" },
  { template: "/api/reports/mine/{report_id}", path: pathPattern(`/api/reports/mine/(?<reportId>${REPORT_ID})`), methods: ["GET"], auth: "field-concealed", body: "none", query: "none", deadline: "ordinary", family: "user-report" },
  { template: "/api/reports/mine/{report_id}/content", path: pathPattern(`/api/reports/mine/(?<reportId>${REPORT_ID})/content`), methods: ["GET"], auth: "field-concealed", body: "none", query: "none", deadline: "ordinary", family: "user-report" },
  { template: "/api/reports/mine/{report_id}/corrections", path: pathPattern(`/api/reports/mine/(?<reportId>${REPORT_ID})/corrections`), methods: ["POST"], auth: "field-concealed", body: "json", query: "none", deadline: "ordinary", family: "user-report" },
  { template: "/api/reports/mine/{report_id}/requests", path: pathPattern(`/api/reports/mine/(?<reportId>${REPORT_ID})/requests`), methods: ["POST"], auth: "field-concealed", body: "json", query: "none", deadline: "ordinary", family: "user-report" },
  { template: "/api/reports/mine/{report_id}/requests/{request_id}", path: pathPattern(`/api/reports/mine/(?<reportId>${REPORT_ID})/requests/(?<requestId>${REPORT_ID})`), methods: ["GET"], auth: "field-concealed", body: "none", query: "none", deadline: "ordinary", family: "user-report" },
  { template: "/api/raw-collections/{collection_id}/manifest", path: pathPattern(`/api/raw-collections/(?<collectionId>${RAW_ID})/manifest`), methods: ["PUT"], auth: "raw-consent-device", body: "json", query: "none", deadline: "ordinary", family: "raw" },
  { template: "/api/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}", path: pathPattern(`/api/raw-collections/(?<collectionId>${RAW_ID})/objects/(?<objectId>${RAW_ID})/chunks/(?<index>${INDEX})`), methods: ["PUT"], auth: "raw-consent-device", body: "stream", query: "none", deadline: "ordinary", family: "raw", accepts: groups => Number(groups.index) < 2_048 },
  { template: "/api/raw-collections/{collection_id}", path: pathPattern(`/api/raw-collections/(?<collectionId>${RAW_ID})`), methods: ["GET"], auth: "raw-consent-device", body: "strict-none", query: "none", deadline: "ordinary", family: "raw" },
  { template: "/api/raw-collections/{collection_id}/commit", path: pathPattern(`/api/raw-collections/(?<collectionId>${RAW_ID})/commit`), methods: ["POST"], auth: "raw-consent-device", body: "json", query: "none", deadline: "ordinary", family: "raw" },
  { template: "/privacy/rights", path: "/privacy/rights", methods: ["GET", "HEAD"], auth: "privacy-variant", body: "by-method", query: "privacy-control", deadline: "ordinary", family: "privacy" },
  { template: "/privacy/account-deletions", path: "/privacy/account-deletions", methods: ["POST"], auth: "deletion-session", body: "json", query: "none", deadline: "ordinary", family: "account-deletion" },
  { template: "/privacy/account-deletions/{request_id}/status", path: pathPattern(`/privacy/account-deletions/(?<requestId>${DELETION_ID})/status`), methods: ["GET"], auth: "deletion-capability", body: "none", query: "none", deadline: "ordinary", family: "account-deletion" },
  { template: "/privacy/account-deletions/{request_id}/device-evidence", path: pathPattern(`/privacy/account-deletions/(?<requestId>${DELETION_ID})/device-evidence`), methods: ["POST"], auth: "deletion-capability", body: "json", query: "none", deadline: "ordinary", family: "account-deletion" }
];

export const GATEWAY_PUBLIC_ROUTE_TEMPLATES = Object.freeze(routes.map(route => route.template));
export type GatewayRouteTemplate = typeof GATEWAY_PUBLIC_ROUTE_TEMPLATES[number];
export type GatewayRouteMatch = Readonly<Omit<RouteDefinition, "path" | "accepts"> & {
  params: Readonly<Record<string, string>>;
  privacyVariant?: "retired" | "bootstrap" | "integrated" | "installation-key" | "page";
  /** Some privacy variants preserve validation or retirement responses for other methods. */
  unsupportedMethod?: "bootstrap-validation" | "integrated-handler" | "retired";
}>;

export function matchGatewayRoute(url: URL): GatewayRouteMatch | null {
  for (const route of routes) {
    const result = typeof route.path === "string"
      ? (route.path === url.pathname ? {} : null)
      : route.path.exec(url.pathname)?.groups ?? null;
    if (result === null || (route.accepts && !route.accepts(result))) continue;
    const { path: _path, accepts: _accepts, ...metadata } = route;
    if (route.family !== "privacy") return { ...metadata, params: result };
    const control = url.searchParams.get("control");
    if (control === "account-deletion") {
      return { ...metadata, params: result, privacyVariant: "retired", methods: [],
        auth: "public", body: "none", unsupportedMethod: "retired" };
    }
    if (control === "integrated-consent-bootstrap") {
      return { ...metadata, params: result, privacyVariant: "bootstrap", methods: ["GET"],
        auth: "field-session", body: "none", unsupportedMethod: "bootstrap-validation" };
    }
    if (control === "integrated-consent") {
      return { ...metadata, params: result, privacyVariant: "integrated", methods: ["GET", "PUT"],
        auth: "consent-bound", body: "by-method", unsupportedMethod: "integrated-handler" };
    }
    if (control === "installation-evidence-key") {
      return { ...metadata, params: result, privacyVariant: "installation-key", methods: ["PUT"],
        auth: "consent-bound", body: "by-method", unsupportedMethod: "integrated-handler" };
    }
    return { ...metadata, params: result, privacyVariant: "page", auth: "public", body: "none" };
  }
  return null;
}

/** Node's Request cannot carry GET/HEAD bodies; this check runs before routing. */
export function gatewayTransportBodyForbidden(method: string): boolean {
  return method === "GET" || method === "HEAD";
}
