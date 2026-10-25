import { type GatewayRouteMatch } from "./route-registry.js";
import { MIMEType } from "node:util";
import { readBoundedJsonBody } from "./request-body.js";
import {
  type GatewayFetch,
  authorizeProxyRequest,
  authorizedProxyDeviceBinding,
  proxyRequestHeaders,
  backendUrl,
  fetchBackend
} from "./backend.js";
import { finishPrivacyOperation } from "./privacy-rights.js";
import {
  type UserReportRoute,
  type UserReportCorrectionRequest,
  USER_REPORT_REQUEST_HISTORY_RESPONSE_LIMIT_BYTES,
  USER_REPORT_RESPONSE_LIMIT_BYTES,
  sanitizeUserReportResponse,
  USER_REPORT_BODY_LIMIT_BYTES,
  validUserRequestBody,
  validUserCorrectionBody
} from "./report-contract.js";
import { cancelUpstream, backendAccountSessionGate } from "./route-responses.js";
import { startPrivacyOperation, privacyOperationIsCurrent } from "./request-scope.js";

export function userReportRoute(matched: GatewayRouteMatch | null): UserReportRoute | null {
  if (matched?.family !== "user-report") return null;
  const { reportId, requestId } = matched.params;
  switch (matched.template) {
    case "/api/reports/mine": return { kind: "list" };
    case "/api/reports/mine/requests/history": return { kind: "request-history" };
    case "/api/reports/mine/deletions/{request_id}":
      return { kind: "deletion", requestId: requestId! };
    case "/api/reports/mine/{report_id}": return { kind: "detail", reportId: reportId! };
    case "/api/reports/mine/{report_id}/content":
      return { kind: "content", reportId: reportId! };
    case "/api/reports/mine/{report_id}/corrections":
      return { kind: "correction", reportId: reportId! };
    case "/api/reports/mine/{report_id}/requests":
      return { kind: "request", reportId: reportId! };
    case "/api/reports/mine/{report_id}/requests/{request_id}":
      return { kind: "request-detail", reportId: reportId!, requestId: requestId! };
    default: return null;
  }
}

export function userReportNotFound(): Response {
  return Response.json(
    { detail: { code: "report_not_found" } },
    { status: 404, headers: { "cache-control": "no-store" } }
  );
}

function userReportUnavailable(): Response {
  return Response.json(
    { detail: { code: "gateway_user_report_unavailable" } },
    { status: 502, headers: { "cache-control": "no-store" } }
  );
}

function validJsonMediaType(value: string | null): boolean {
  if (value === null) return false;
  try {
    return new MIMEType(value).essence === "application/json";
  } catch {
    return false;
  }
}

async function boundedUserReportPayload(
  response: Response,
  route: UserReportRoute,
  correctionRequest: UserReportCorrectionRequest | null,
  expectedHistoryReportId: string | null,
  historyRequestCursor: string | null,
  expectedHistoryLimit: number
): Promise<Record<string, unknown> | null> {
  const responseLimitBytes = route.kind === "request-history"
    ? USER_REPORT_REQUEST_HISTORY_RESPONSE_LIMIT_BYTES
    : USER_REPORT_RESPONSE_LIMIT_BYTES;
  const contentType = response.headers.get("content-type");
  const contentLength = response.headers.get("content-length");
  if (
    !validJsonMediaType(contentType) || response.body === null ||
    (contentLength !== null && (
      !/^\d+$/.test(contentLength) || Number(contentLength) > responseLimitBytes
    ))
  ) {
    cancelUpstream(response);
    return null;
  }
  const boundedRequest = new Request("http://gateway.invalid/internal/user-report", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: response.body,
    duplex: "half"
  } as RequestInit & { duplex: "half" });
  const bounded = await readBoundedJsonBody(
    boundedRequest, responseLimitBytes, 5_000
  );
  return bounded.error
    ? null
    : sanitizeUserReportResponse(
      bounded.value,
      route,
      correctionRequest,
      expectedHistoryReportId,
      historyRequestCursor,
      expectedHistoryLimit
    );
}

export async function userReportProxy(
  request: Request,
  route: UserReportRoute,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) return denied.status === 401 || denied.status === 403
    ? userReportNotFound() : denied;
  const binding = authorizedProxyDeviceBinding(request);
  if (!binding) return userReportNotFound();
  const backendGate = await backendAccountSessionGate(request, fetchImpl);
  if (backendGate) return backendGate.status === 401
    ? userReportNotFound() : backendGate;
  const requestUrl = new URL(request.url);
  const expectedHistoryReportId = route.kind === "request-history"
    ? requestUrl.searchParams.get("report_id")
    : null;
  const historyRequestCursor = route.kind === "request-history"
    ? requestUrl.searchParams.get("cursor")
    : null;
  const expectedHistoryLimit = route.kind === "request-history"
    ? Number(requestUrl.searchParams.get("limit") ?? "10")
    : 0;
  const operation = startPrivacyOperation(
    binding.actorId,
    binding.accountGeneration
  );
  if (operation.error) return operation.error.status === 409
    ? userReportNotFound() : operation.error;
  try {
    if (!privacyOperationIsCurrent(operation.lease)) return userReportNotFound();
    let body: string | undefined;
    let correctionRequest: UserReportCorrectionRequest | null = null;
    const headers = proxyRequestHeaders(
      request,
      route.kind === "request" || route.kind === "correction"
        ? { "content-type": "application/json" }
        : undefined,
      operation.lease.accountGeneration
    );
    if (route.kind === "request" || route.kind === "correction") {
      const bounded = await readBoundedJsonBody(request, USER_REPORT_BODY_LIMIT_BYTES);
      if (bounded.error) return bounded.error;
      const payload = route.kind === "request"
        ? validUserRequestBody(bounded.value)
        : null;
      correctionRequest = route.kind === "correction"
        ? validUserCorrectionBody(bounded.value)
        : null;
      if (!payload && !correctionRequest) {
        return Response.json(
          {
            detail: {
              code: route.kind === "correction"
                ? "report_content_correction_invalid"
                : "report_request_invalid"
            }
          },
          { status: 422, headers: { "cache-control": "no-store" } }
        );
      }
      body = JSON.stringify(payload ?? correctionRequest!.body);
    }
    let backendPath: string;
    switch (route.kind) {
      case "list":
        backendPath = "/reports/mine";
        break;
      case "request-history":
        backendPath = "/reports/mine/requests/history";
        break;
      case "detail":
        backendPath = `/reports/mine/${route.reportId}`;
        break;
      case "request":
        backendPath = `/reports/mine/${route.reportId}/requests`;
        break;
      case "request-detail":
        backendPath = `/reports/mine/${route.reportId}/requests/${route.requestId}`;
        break;
      case "content":
        backendPath = `/reports/mine/${route.reportId}/content`;
        break;
      case "correction":
        backendPath = `/reports/mine/${route.reportId}/corrections`;
        break;
      case "deletion":
        backendPath = `/reports/mine/deletions/${route.requestId}`;
        break;
    }
    const init: RequestInit = {
      method: route.kind === "request" || route.kind === "correction" ? "POST" : "GET",
      headers,
      ...(body === undefined ? {} : { body }),
      cache: "no-store",
      signal: operation.lease.controller.signal
    };
    const upstreamUrl = backendUrl(
      backendPath,
      route.kind === "list" || route.kind === "request-history" ? request : undefined
    );
    const upstream = fetchImpl
      ? await fetchBackend(request, upstreamUrl, init, 15_000, fetchImpl)
      : await fetchBackend(request, upstreamUrl, init);
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(upstream);
      return userReportNotFound();
    }
    if ([401, 403, 404].includes(upstream.status)) {
      cancelUpstream(upstream);
      return userReportNotFound();
    }
    if (route.kind === "request-history" && upstream.status === 422) {
      cancelUpstream(upstream);
      return Response.json(
        { detail: { code: "report_request_history_query_invalid" } },
        { status: 422, headers: { "cache-control": "no-store" } }
      );
    }
    if (upstream.status === 429) {
      const headers: Record<string, string> = { "cache-control": "no-store" };
      const retryAfter = upstream.headers.get("retry-after");
      if (retryAfter && /^\d+$/.test(retryAfter)) {
        headers["retry-after"] = retryAfter;
      }
      cancelUpstream(upstream);
      return Response.json(
        { detail: { code: "report_rate_limited" } },
        { status: 429, headers }
      );
    }
    if ((route.kind === "request" || route.kind === "correction") && upstream.status === 409) {
      cancelUpstream(upstream);
      return Response.json(
        {
          detail: {
            code: route.kind === "correction"
              ? "report_content_conflict"
              : "report_request_intent_conflict"
          }
        },
        { status: 409, headers: { "cache-control": "no-store" } }
      );
    }
    if (
      (route.kind === "request" || route.kind === "correction") &&
      [413, 415, 422].includes(upstream.status)
    ) {
      const headers: Record<string, string> = { "cache-control": "no-store" };
      cancelUpstream(upstream);
      return Response.json(
        {
          detail: {
            code: route.kind === "correction"
              ? "report_content_correction_invalid"
              : "report_request_invalid"
          }
        },
        { status: upstream.status, headers }
      );
    }
    const expected = route.kind === "request" || route.kind === "correction"
      ? [200, 201]
      : [200];
    if (!expected.includes(upstream.status)) {
      cancelUpstream(upstream);
      return userReportUnavailable();
    }
    const payload = await boundedUserReportPayload(
      upstream,
      route,
      correctionRequest,
      expectedHistoryReportId,
      historyRequestCursor,
      expectedHistoryLimit
    );
    const stillCurrent = privacyOperationIsCurrent(operation.lease);
    if (!stillCurrent) return userReportNotFound();
    if (!payload) return userReportUnavailable();
    return Response.json(payload, {
      status: upstream.status,
      headers: { "cache-control": "no-store" }
    });
  } finally {
    finishPrivacyOperation(operation.lease);
  }
}

