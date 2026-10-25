import { defaultGatewayRuntime, type GatewayRuntime } from "./gateway-runtime.js";
import {
  GATEWAY_ROUTE_TEMPLATES,
  type GatewayTelemetrySink,
  gatewayCorrelationId,
  gatewayRouteTemplate,
  withGatewayRequestId,
  writeGatewayTelemetry,
  recordGatewayRequestPrepared,
  observeGatewayResponseDelivery,
  recordGatewayRequestFinished
} from "./telemetry.js";
import { type GatewayFetch, authorizeProxyRequest, authorizedProxyActor } from "./backend.js";
import {
  type GatewayFieldLongSessionBinding,
  isGatewaySessionAuthorized,
  gatewaySessionAccountGeneration,
  gatewaySessionAuthEpoch,
  gatewayFieldLongSessionBinding,
  gatewaySessionScope,
  gatewayUnavailableResponse,
  gatewayUnauthorizedResponse,
  gatewaySessionActor
} from "./auth.js";
import { type GatewayRouteMatch, matchGatewayRoute } from "./route-registry.js";
import {
  INTEGRATED_CONSENT_POLICY_VERSION,
  handleIntegratedConsentBootstrapRequest,
  handleIntegratedConsentRequest
} from "./integrated-consent.js";
import { relayRawCollection } from "./raw-collection-relay.js";
import { consentActorBindingId, finishPrivacyOperation } from "./privacy-rights.js";
import { relayAccountEnrollment, relayAccountCreation, relayAccountSignupPolicy } from "./account-relay.js";
import { relaySpeechStt, relaySpeechTts } from "./speech-relay.js";
import {
  FieldLongSessionStorageUnavailableError,
  fieldLongSessionStorageUnavailableResponse
} from "./field-long-session.js";
import {
  methodNotAllowed,
  noStore,
  backendAccountSessionGate,
  requestHasEntityBody,
  cancelUpstream,
  routeNotFound,
  clientClosedResponse
} from "./route-responses.js";
import { userReportRoute, userReportNotFound, userReportProxy } from "./user-report-routes.js";
import { validUserReportRequestHistoryQuery, validUserReportQuery } from "./report-contract.js";
import { reportTransportStatusNotFound, reportTransportStatus, reportV2 } from "./report-upload-routes.js";
import {
  accountDeletionRouteV2,
  deletionNotFound,
  accountDeletionRequestV2,
  accountDeletionStatusV2,
  accountDeletionEvidenceV2
} from "./deletion-routes.js";
import {
  startPrivacyOperation,
  privacyOperationIsCurrent,
  privacyOperationState,
  privacyOperationInactive,
  privacyLedgerUnavailable,
  responseWithPrivacyOperation
} from "./request-scope.js";
import { privacyRightsPage } from "./privacy-page.js";
import { relayFieldDiagnostics } from "./diagnostic-routes.js";
import { fieldSession } from "./session-routes.js";
import { fieldWalk } from "./field-walk-routes.js";
import {
  walkingRoute,
  destinationSearch,
  navigationDeadlineResponse,
  NAVIGATION_WALKING_DEADLINE_MS,
  NAVIGATION_SEARCH_DEADLINE_MS
} from "./navigation-routes.js";

export const PUBLIC_GATEWAY_ROUTES = Object.freeze([
  ...GATEWAY_ROUTE_TEMPLATES
]);

export type GatewayDependencies = {
  runtime?: GatewayRuntime;
  fetchImpl?: GatewayFetch;
  privacyRightsRequestUrl?: string;
  telemetrySink?: GatewayTelemetrySink;
  fieldLongSessionBindingResolver?: (
    request: Request
  ) => GatewayFieldLongSessionBinding | null;
};

const ACCOUNT_DELETION_PATH = "/privacy/account-deletions";

function privacyUnsupportedMethod(match: GatewayRouteMatch, method: string): Response | null {
  if (match.methods.includes(method)) return null;
  if (match.unsupportedMethod === "bootstrap-validation") {
    return Response.json(
      { detail: {
        code: "integrated_consent_bootstrap_query_invalid",
        message: "Consent bootstrap query is invalid.",
        required_policy_version: INTEGRATED_CONSENT_POLICY_VERSION
      } },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  if (match.unsupportedMethod === "integrated-handler") {
    return methodNotAllowed(match.methods);
  }
  return null;
}

async function dispatchGatewayRequest(
  request: Request,
  correlationId: string,
  dependencies: GatewayDependencies = {}
): Promise<Response> {
  const runtime = dependencies.runtime ?? defaultGatewayRuntime;
  const requestUrl = new URL(request.url);
  const pathname = requestUrl.pathname;
  const matched = matchGatewayRoute(requestUrl);
  if (pathname === "/api/raw-collections" || pathname.startsWith("/api/raw-collections/")) {
    if (matched?.auth === "raw-consent-device" && isGatewaySessionAuthorized(request) &&
        (gatewaySessionAccountGeneration(request) !== null ||
          gatewaySessionAuthEpoch(request) !== null)) {
      // The null resolver stops after route, method, framing and header validation.
      const preflight = await relayRawCollection(request, {
        bindingResolver: () => null
      });
      if (preflight.status !== 401) return noStore(preflight);
      const backendGate = await backendAccountSessionGate(request, dependencies.fetchImpl);
      if (backendGate) return backendGate;
    }
    return noStore(await relayRawCollection(request, {
      admissionController: runtime.rawWrite,
      ...(dependencies.fetchImpl ? { fetchImpl: dependencies.fetchImpl } : {}),
      bindingResolver: dependencies.fieldLongSessionBindingResolver
        ?? gatewayFieldLongSessionBinding
    }));
  }
  const userReport = userReportRoute(matched);
  if (userReport) {
    const allowed = matched!.methods;
    if (!allowed.includes(request.method)) return methodNotAllowed(allowed);
    if (matched!.auth === "field-concealed" &&
        gatewaySessionScope(request) === "account_deletion_recovery") {
      return userReportNotFound();
    }
    if (matched!.query === "validated" && userReport.kind === "request-history") {
      if (!validUserReportRequestHistoryQuery(requestUrl) || requestHasEntityBody(request)) {
        return Response.json(
          { detail: { code: "report_request_history_query_invalid" } },
          { status: 422, headers: { "cache-control": "no-store" } }
        );
      }
    } else if (matched!.query === "validated" && userReport.kind === "list") {
      if (!validUserReportQuery(requestUrl) || requestHasEntityBody(request)) {
        return Response.json(
          { detail: { code: "report_query_invalid" } },
          { status: 400, headers: { "cache-control": "no-store" } }
        );
      }
    } else if (
      (matched!.query === "none" && [...requestUrl.searchParams].length > 0) ||
      (request.method === "GET" && matched!.body === "none" && requestHasEntityBody(request))
    ) {
      return userReportNotFound();
    }
    if (matched!.auth === "field-concealed") {
      const denied = authorizeProxyRequest(request);
      if (denied) return denied.status === 401 || denied.status === 403
        ? userReportNotFound() : denied;
    }
    if (request.method === "POST" && matched!.body !== "json") {
      return gatewayUnavailableResponse();
    }
    return noStore(await userReportProxy(request, userReport, dependencies.fetchImpl));
  }
  if (matched?.family === "report-status") {
    if (matched.query === "none" && [...requestUrl.searchParams].length > 0) {
      return reportTransportStatusNotFound();
    }
    if (!matched.methods.includes(request.method)) return methodNotAllowed(matched.methods);
    if (matched.body === "none" && requestHasEntityBody(request)) {
      return Response.json(
        { detail: { code: "report_transport_status_request_invalid" } },
        { status: 400, headers: { "cache-control": "no-store" } }
      );
    }
    if (matched.auth === "field-concealed" &&
        gatewaySessionScope(request) === "account_deletion_recovery") {
      return reportTransportStatusNotFound();
    }
    if (matched.auth === "field-concealed") {
      const denied = authorizeProxyRequest(request);
      if (denied) return denied.status === 401 || denied.status === 403
        ? reportTransportStatusNotFound() : denied;
    }
    return noStore(await reportTransportStatus(
      request,
      matched.params.reportId!,
      dependencies.fetchImpl
    ));
  }
  const deletionRoute = accountDeletionRouteV2(matched);
  if (deletionRoute) {
    if (matched!.query === "none" && [...requestUrl.searchParams].length > 0) {
      return deletionNotFound();
    }
    const allowed = matched!.methods;
    if (!allowed.includes(request.method)) return methodNotAllowed(allowed);
    if (matched!.body === "none" && requestHasEntityBody(request)) {
      return Response.json(
        { code: "account_deletion_status_request_invalid" },
        { status: 400, headers: { "cache-control": "no-store" } }
      );
    }
    if (request.method === "POST" && matched!.body !== "json") {
      return gatewayUnavailableResponse();
    }
    if (deletionRoute.kind === "request") {
      return noStore(await accountDeletionRequestV2(request, dependencies.fetchImpl));
    }
    if (deletionRoute.kind === "status") {
      return noStore(await accountDeletionStatusV2(
        request,
        deletionRoute.requestId,
        dependencies.fetchImpl
      ));
    }
    return noStore(await accountDeletionEvidenceV2(
      request,
      deletionRoute.requestId,
      dependencies.fetchImpl
    ));
  }
  if (matched?.family === "privacy") {
    if (matched.query !== "privacy-control") return gatewayUnavailableResponse();
    if (gatewaySessionScope(request) === "account_deletion_recovery") {
      return noStore(gatewayUnauthorizedResponse(true));
    }
    if (matched.unsupportedMethod === "retired") {
      return Response.json(
        {
          code: "account_deletion_v1_retired",
          replacement: ACCOUNT_DELETION_PATH
        },
        { status: 410, headers: { "cache-control": "no-store" } }
      );
    }
    if (matched.auth === "field-session" || matched.auth === "consent-bound") {
      const denied = authorizeProxyRequest(request);
      if (denied) return noStore(denied);
    }
    if (matched.privacyVariant === "bootstrap") {
      const actorId = authorizedProxyActor(request);
      if (!actorId) return noStore(gatewayUnauthorizedResponse());
      if (request.method === "GET") {
        const backendGate = await backendAccountSessionGate(request, dependencies.fetchImpl);
        if (backendGate) return backendGate;
      }
      const operation = startPrivacyOperation(
        actorId,
        gatewaySessionAccountGeneration(request)
      );
      if (operation.error) return noStore(operation.error);
      try {
        if (!privacyOperationIsCurrent(operation.lease)) {
          return noStore(privacyOperationInactive());
        }
        const unsupported = privacyUnsupportedMethod(matched, request.method);
        if (unsupported) return noStore(unsupported);
        if (matched.body !== "none") return noStore(gatewayUnavailableResponse());
        const response = await handleIntegratedConsentBootstrapRequest(request, {
          accountGeneration: operation.lease.accountGeneration,
          fieldActorBindingId: consentActorBindingId(operation.lease),
          signal: operation.lease.controller.signal,
          ...(dependencies.fetchImpl
            ? { fetchImpl: dependencies.fetchImpl }
            : {})
        });
        if (!privacyOperationIsCurrent(operation.lease)) {
          cancelUpstream(response);
          return noStore(privacyOperationInactive());
        }
        return noStore(response);
      } finally {
        finishPrivacyOperation(operation.lease);
      }
    }
    if (matched.privacyVariant === "integrated" || matched.privacyVariant === "installation-key") {
      const actorId = authorizedProxyActor(request);
      if (!actorId) return noStore(gatewayUnauthorizedResponse());
      if (request.method === "GET" || request.method === "PUT") {
        const backendGate = await backendAccountSessionGate(request, dependencies.fetchImpl);
        if (backendGate) return backendGate;
      }
      const operation = startPrivacyOperation(
        actorId,
        gatewaySessionAccountGeneration(request)
      );
      if (operation.error) return noStore(operation.error);
      try {
        if (!privacyOperationIsCurrent(operation.lease)) {
          return noStore(privacyOperationInactive());
        }
        const unsupported = privacyUnsupportedMethod(matched, request.method);
        if (unsupported) return noStore(unsupported);
        if (matched.body !== "by-method") return noStore(gatewayUnavailableResponse());
        const response = await handleIntegratedConsentRequest(request, {
          accountGeneration: operation.lease.accountGeneration,
          fieldActorBindingId: consentActorBindingId(operation.lease),
          signal: operation.lease.controller.signal,
          isCurrent: () => authorizedProxyActor(request) === actorId &&
            privacyOperationIsCurrent(operation.lease),
          ...(dependencies.fetchImpl
            ? { fetchImpl: dependencies.fetchImpl }
            : {})
        });
        if (!privacyOperationIsCurrent(operation.lease)) {
          cancelUpstream(response);
          return noStore(privacyOperationInactive());
        }
        return noStore(response);
      } finally {
        finishPrivacyOperation(operation.lease);
      }
    }
    const methods = matched.methods;
    if (!methods.includes(request.method)) {
      return methodNotAllowed(methods);
    }
    if (matched.body !== "none") return gatewayUnavailableResponse();
    const privacyRightsRequestUrl = dependencies.privacyRightsRequestUrl
      ?? process.env.WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL;
    if (!privacyRightsRequestUrl) {
      return Response.json(
        { code: "privacy_rights_channel_unavailable" },
        { status: 503, headers: { "cache-control": "no-store" } }
      );
    }
    return privacyRightsPage(
      privacyRightsRequestUrl,
      request.method === "HEAD"
    );
  }
  if (matched?.family !== "static") return routeNotFound();
  const methods = matched.methods;
  if (!methods.includes(request.method)) return methodNotAllowed(methods);
  const accountRelayRoute = pathname === "/api/account-enrollments/email-otp" ||
    pathname === "/api/accounts" || pathname === "/api/account-signup-policy";
  const exactNoQueryRoute = accountRelayRoute ||
    pathname === "/api/speech/stt" || pathname === "/api/speech/tts" ||
    pathname.startsWith("/api/field-diagnostics/");
  if (exactNoQueryRoute && matched.query === "none" && [...requestUrl.searchParams].length > 0) {
    return Response.json(
      { detail: { code: "account_request_query_invalid" } },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  if (matched.auth !== "session-lifecycle" && matched.auth !== "public" &&
      gatewaySessionScope(request) === "account_deletion_recovery") {
    return noStore(gatewayUnauthorizedResponse(true));
  }
  if (matched.auth !== "public" && matched.auth !== "session-lifecycle" &&
      matched.auth !== "field-device") {
    const backendGate = await backendAccountSessionGate(request, dependencies.fetchImpl);
    if (backendGate) return backendGate;
  }
  if (matched.auth === "field-session" || matched.auth === "consent-bound") {
    const denied = authorizeProxyRequest(request);
    if (denied) return noStore(denied);
  }

  let response: Response;
  if (pathname === "/api/account-signup-policy") {
    response = matched.body === "strict-none" && !requestHasEntityBody(request)
      ? await relayAccountSignupPolicy(request, dependencies.fetchImpl)
      : Response.json({ detail: { code: "account_request_body_invalid" } },
          { status: 400, headers: { "cache-control": "no-store" } });
  }
  else if (pathname === "/api/account-enrollments/email-otp") {
    response = matched.body === "json"
      ? await relayAccountEnrollment(request, dependencies.fetchImpl)
      : gatewayUnavailableResponse();
  }
  else if (pathname === "/api/accounts") {
    response = matched.body === "json"
      ? await relayAccountCreation(request, dependencies.fetchImpl)
      : gatewayUnavailableResponse();
  }
  else if (pathname.startsWith("/api/field-diagnostics/")) {
    response = await relayFieldDiagnostics(request, dependencies.fetchImpl);
  }
  else if (pathname === "/api/field-session") {
    response = await fieldSession(request, matched, dependencies.fetchImpl, runtime.refresh, runtime.capacity);
  }
  else if (pathname === "/api/field-walk") {
    response = await fieldWalk(
      request,
      matched,
      dependencies.fieldLongSessionBindingResolver
        ?? gatewayFieldLongSessionBinding,
      dependencies.fetchImpl
    );
  }
  else if (pathname === "/api/speech/stt" || pathname === "/api/speech/tts") {
    const relay = (scopedRequest: Request) => matched.body === "stream"
      ? relaySpeechStt(scopedRequest, correlationId, dependencies.fetchImpl, runtime.speech)
      : matched.body === "json"
      ? relaySpeechTts(scopedRequest, correlationId, dependencies.fetchImpl, runtime.speech)
      : gatewayUnavailableResponse();
    const actorId = gatewaySessionActor(request);
    const accountGeneration = gatewaySessionAccountGeneration(request);
    if (!actorId) {
      response = await relay(request);
    } else {
      const operation = startPrivacyOperation(actorId, accountGeneration);
      if (operation.error) return noStore(operation.error);
      let responseOwnsOperation = false;
      try {
        // The same lease owns upload reads, Voice fetch and response buffering.
        const scopedRequest = new Request(request, {
          signal: AbortSignal.any([request.signal, operation.lease.controller.signal])
        });
        const relayed = await relay(scopedRequest);
        const state = privacyOperationState(operation.lease);
        if (state === "current") {
          response = responseWithPrivacyOperation(relayed, operation.lease, request.signal);
          responseOwnsOperation = true;
        } else {
          cancelUpstream(relayed);
          response = state === "unavailable" ? privacyLedgerUnavailable() : privacyOperationInactive();
        }
      } finally {
        if (!responseOwnsOperation) finishPrivacyOperation(operation.lease);
      }
    }
  }
  else if (pathname === "/api/navigation/walking") {
    response = await walkingRoute(request, matched, dependencies.fetchImpl);
  } else if (pathname === "/api/navigation/destinations/search") {
    response = await destinationSearch(request, matched, dependencies.fetchImpl);
  } else {
    response = await reportV2(request, matched, dependencies.fetchImpl, runtime.imageUpload);
  }
  return noStore(response);
}

async function dispatchNavigationWithinDeadline(
  request: Request,
  correlationId: string,
  dependencies: GatewayDependencies,
  deadlineMs: number
): Promise<Response> {
  const deadlineAbort = new AbortController();
  const scopedRequest = new Request(request, {
    signal: AbortSignal.any([request.signal, deadlineAbort.signal])
  });
  const handled = dispatchGatewayRequest(scopedRequest, correlationId, dependencies);
  let timer: ReturnType<typeof setTimeout> | undefined;
  let onClientAbort: (() => void) | undefined;
  const deadline = new Promise<"deadline">((resolve) => {
    timer = setTimeout(() => {
      deadlineAbort.abort(new Error("navigation request deadline exceeded"));
      resolve("deadline");
    }, deadlineMs);
  });
  const clientClosed = new Promise<"client_closed">((resolve) => {
    onClientAbort = () => resolve("client_closed");
    if (request.signal.aborted) onClientAbort();
    else request.signal.addEventListener("abort", onClientAbort, { once: true });
  });
  try {
    const result = await Promise.race([handled, deadline, clientClosed]);
    if (result instanceof Response) return result;
    void handled.then(cancelUpstream, () => undefined);
    // The normal cancellation path releases privacy leases in its finally block.
    // Give it one event-loop turn to settle without trusting a stalled dependency.
    await Promise.race([
      handled.then(() => undefined, () => undefined),
      new Promise<void>((resolve) => setTimeout(resolve, 50))
    ]);
    return result === "deadline" ? navigationDeadlineResponse() : clientClosedResponse();
  } finally {
    if (timer !== undefined) clearTimeout(timer);
    if (onClientAbort) request.signal.removeEventListener("abort", onClientAbort);
  }
}

export async function handleGatewayRequest(
  request: Request,
  dependencies: GatewayDependencies = {}
): Promise<Response> {
  const startedAt = performance.now();
  const correlationId = gatewayCorrelationId(request);
  const routeTemplate = gatewayRouteTemplate(request);
  let response: Response;
  try {
    const route = matchGatewayRoute(new URL(request.url));
    const navigationDeadlineMs =
      request.method === "POST" && route?.deadline === "walking"
        ? NAVIGATION_WALKING_DEADLINE_MS
        : request.method === "GET" && route?.deadline === "search"
          ? NAVIGATION_SEARCH_DEADLINE_MS
          : null;
    response = navigationDeadlineMs === null
      ? await dispatchGatewayRequest(request, correlationId, dependencies)
      : await dispatchNavigationWithinDeadline(
          request, correlationId, dependencies, navigationDeadlineMs
        );
  } catch (error) {
    if (!(error instanceof FieldLongSessionStorageUnavailableError)) throw error;
    response = fieldLongSessionStorageUnavailableResponse();
  }
  const responseWithRequestId = withGatewayRequestId(response, correlationId);
  const sink = dependencies.telemetrySink
    ?? (process.env.NODE_ENV === "test" ? undefined : writeGatewayTelemetry);
  if (!routeTemplate || !sink) return responseWithRequestId;
  const capacityLevel = (dependencies.runtime ?? defaultGatewayRuntime).capacity
    .currentServerCapacityLevelForTelemetry();
  recordGatewayRequestPrepared(
    correlationId,
    routeTemplate,
    responseWithRequestId,
    performance.now() - startedAt,
    capacityLevel,
    sink
  );
  return observeGatewayResponseDelivery(
    responseWithRequestId,
    startedAt,
    (outcome, elapsedMs, firstByteMs) => recordGatewayRequestFinished(
      correlationId,
      routeTemplate,
      responseWithRequestId,
      outcome,
      elapsedMs,
      firstByteMs,
      capacityLevel,
      sink
    )
  );
}

export {
  REPORT_PURPOSE_HEADER,
  REPORT_ID_HEADER,
  REPORT_PAYLOAD_SHA256_HEADER,
  REPORT_PAYLOAD_BYTES_HEADER
} from "./report-upload-routes.js";
export { setGatewayPrivacyFaultForTests } from "./request-scope.js";
export { ACCOUNT_DELETION_REAUTHENTICATION_WINDOW_MS } from "./session-authentication.js";
