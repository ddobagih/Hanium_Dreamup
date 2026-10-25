import {
  defaultImageUploadAdmission,
  type ImageUploadAdmissionController
} from "./image-upload-admission.js";
import { type GatewayRouteMatch } from "./route-registry.js";
import {
  type GatewayFetch,
  authorizeProxyRequest,
  authorizedProxyActor,
  proxyRequestHeaders,
  type ImageUploadAdmission,
  readBoundedMultipartFormData,
  IMAGE_MULTIPART_LIMIT_BYTES,
  fetchBackend,
  backendUrl,
  toBackendResponse
} from "./backend.js";
import {
  gatewayUnauthorizedResponse,
  gatewaySessionAccountGeneration,
  gatewayUnavailableResponse
} from "./auth.js";
import { finishPrivacyOperation, consentActorBindingId } from "./privacy-rights.js";
import { CONSENT_NETWORK_TRANSPORT_HEADER, authorizeIntegratedConsentRequest } from "./integrated-consent.js";
import { duplicateJsonObjectKeysError, readBoundedJsonBody } from "./request-body.js";
import { CANONICAL_REPORT_UUID, LOWER_SHA256, exactObjectKeys } from "./report-contract.js";
import { walkOwnershipGate } from "./field-walk-routes.js";
import {
  startPrivacyOperation,
  privacyOperationIsCurrent,
  privacyOperationInactive,
  responseWithPrivacyOperation
} from "./request-scope.js";
import { objectPayload, cancelUpstream, backendAccountSessionGate } from "./route-responses.js";

export const REPORT_PURPOSE_HEADER = "x-walksafe-report-purpose";

export const REPORT_ID_HEADER = "x-walksafe-report-id";

export const REPORT_PAYLOAD_SHA256_HEADER = "x-walksafe-report-payload-sha256";

export const REPORT_PAYLOAD_BYTES_HEADER = "x-walksafe-report-payload-bytes";

const REPORT_TRANSPORT_STATUS_BODY_LIMIT_BYTES = 4096;

const POSITIVE_DECIMAL = /^[1-9][0-9]{0,18}$/;

const MAX_POSTGRES_BIGINT = 9_223_372_036_854_775_807n;

type ReportTransportHeaders = Readonly<{
  reportId: string;
  payloadSha256: string;
  payloadBytes: string;
}>;

function reportTransportHeaders(request: Request):
  | { value: ReportTransportHeaders; error?: never }
  | { value?: never; error: Response } {
  const names = [
    REPORT_ID_HEADER,
    REPORT_PAYLOAD_SHA256_HEADER,
    REPORT_PAYLOAD_BYTES_HEADER
  ] as const;
  const present = names.filter(name => request.headers.has(name));
  if (present.length !== names.length) {
    return {
      error: Response.json(
        { detail: { code: "report_transport_headers_incomplete" } },
        { status: 422, headers: { "cache-control": "no-store" } }
      )
    };
  }
  const reportId = request.headers.get(REPORT_ID_HEADER) ?? "";
  const payloadSha256 = request.headers.get(REPORT_PAYLOAD_SHA256_HEADER) ?? "";
  const payloadBytes = request.headers.get(REPORT_PAYLOAD_BYTES_HEADER) ?? "";
  let payloadByteCount = 0n;
  try {
    payloadByteCount = POSITIVE_DECIMAL.test(payloadBytes)
      ? BigInt(payloadBytes)
      : 0n;
  } catch {
    payloadByteCount = 0n;
  }
  if (
    !CANONICAL_REPORT_UUID.test(reportId) ||
    !LOWER_SHA256.test(payloadSha256) ||
    payloadByteCount < 1n ||
    payloadByteCount > MAX_POSTGRES_BIGINT
  ) {
    return {
      error: Response.json(
        { detail: { code: "report_transport_headers_invalid" } },
        { status: 422, headers: { "cache-control": "no-store" } }
      )
    };
  }
  return { value: { reportId, payloadSha256, payloadBytes } };
}

function attachReportTransportHeaders(
  headers: Headers,
  transport: ReportTransportHeaders
): void {
  headers.set(REPORT_ID_HEADER, transport.reportId);
  headers.set(REPORT_PAYLOAD_SHA256_HEADER, transport.payloadSha256);
  headers.set(REPORT_PAYLOAD_BYTES_HEADER, transport.payloadBytes);
}

function reportTransportOutcomeUnknown(): Response {
  return Response.json(
    {
      detail: {
        code: "report_transport_outcome_unknown",
        message: "The report result is unknown; verify its status before retrying.",
        retry: "status_query_before_retry"
      }
    },
    { status: 409, headers: { "cache-control": "no-store" } }
  );
}

export async function reportV2(
  request: Request,
  match: GatewayRouteMatch,
  fetchImpl?: GatewayFetch,
  admissionController: ImageUploadAdmissionController = defaultImageUploadAdmission
): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) return denied;
  const fieldActorId = authorizedProxyActor(request);
  if (!fieldActorId) return gatewayUnauthorizedResponse();
  const walkOwned = walkOwnershipGate(request, { holderMayProceed: false });
  if (walkOwned) return walkOwned;
  const transport = reportTransportHeaders(request);
  if (transport.error) return transport.error;
  const operation = startPrivacyOperation(
    fieldActorId,
    gatewaySessionAccountGeneration(request)
  );
  if (operation.error) return operation.error;
  const headers = proxyRequestHeaders(
    request,
    undefined,
    operation.lease.accountGeneration
  );
  attachReportTransportHeaders(headers, transport.value);
  // S19-32: rate limits first (no slot); the single global upload slot is taken only after
  // the first consent check, and still before the body is opened.
  const rateLimited = admissionController.imageUploadRateLimitedResponse(request, headers);
  if (rateLimited) {
    finishPrivacyOperation(operation.lease);
    return rateLimited;
  }
  let admission: ImageUploadAdmission | null = null;
  let responseOwnsOperation = false;
  try {
    const networkTransport =
      request.headers.get(CONSENT_NETWORK_TRANSPORT_HEADER)?.trim() ?? "";
    if (networkTransport !== "wifi" && networkTransport !== "cellular") {
      return Response.json(
        {
          detail: {
            code: "integrated_consent_network_transport_required",
            message: "A supported current network transport is required."
          }
        },
        { status: 428, headers: { "cache-control": "no-store" } }
      );
    }
    const reportPurpose = request.headers.get(REPORT_PURPOSE_HEADER)?.trim() ?? "";
    if (reportPurpose !== "explicit" && reportPurpose !== "automatic") {
      return Response.json(
        {
          detail: {
            code: "integrated_consent_report_purpose_invalid",
            message: "x-walksafe-report-purpose must be explicit or automatic."
          }
        },
        { status: 422, headers: { "cache-control": "no-store" } }
      );
    }
    const initialRequired: Array<
      "raw_source_collection" | "automatic_reporting" | "mobile_network_transfer"
    > = [];
    if (reportPurpose === "automatic") initialRequired.push("automatic_reporting");
    if (networkTransport === "cellular") initialRequired.push("mobile_network_transfer");
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    const consentBindingId = consentActorBindingId(operation.lease);
    const consent = await authorizeIntegratedConsentRequest(
      request,
      initialRequired,
      consentBindingId
    );
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    if (consent.error) return consent.error;
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    const acquired = admissionController.acquireImageUploadAdmission(request, headers);
    if (acquired.error) return acquired.error;
    admission = acquired;
    if (match.body !== "multipart") {
      return gatewayUnavailableResponse();
    }
    const multipart = await readBoundedMultipartFormData(request, IMAGE_MULTIPART_LIMIT_BYTES);
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    if (multipart.error) return multipart.error;
    const metadataText = multipart.formData.get("metadata");
    try {
      const metadata = typeof metadataText === "string"
        ? objectPayload(JSON.parse(metadataText) as unknown)
        : null;
      if (typeof metadataText === "string") {
        const duplicateError = duplicateJsonObjectKeysError(metadataText);
        if (duplicateError) return duplicateError;
      }
      if (
        !metadata ||
        typeof metadata.auto_reported !== "boolean" ||
        metadata.auto_reported !== (reportPurpose === "automatic")
      ) {
        throw new Error();
      }
    } catch {
      return Response.json(
        {
          detail: {
            code: "integrated_consent_report_purpose_invalid",
            message: "Multipart metadata must match x-walksafe-report-purpose."
          }
        },
        { status: 422, headers: { "cache-control": "no-store" } }
      );
    }
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    const currentConsent = await authorizeIntegratedConsentRequest(
      request,
      initialRequired,
      consentBindingId
    );
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    if (currentConsent.error) return currentConsent.error;
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    const init: RequestInit = {
      method: "POST",
      headers,
      body: multipart.formData,
      cache: "no-store",
      signal: operation.lease.controller.signal
    };
    if (!privacyOperationIsCurrent(operation.lease)) return privacyOperationInactive();
    const response = fetchImpl
      ? await fetchBackend(request, backendUrl("/reports/v2"), init, 15_000, fetchImpl)
      : await fetchBackend(request, backendUrl("/reports/v2"), init);
    // A 2xx report or a server-side failure can follow a durable commit. A later
    // local consent check cannot roll that commit back; the queued report must use
    // its stable ID and the existing status route before any retry.
    const backendOutcomeUncertain = response.ok || response.status >= 500;
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return backendOutcomeUncertain
        ? reportTransportOutcomeUnknown() : privacyOperationInactive();
    }
    const finalConsent = await authorizeIntegratedConsentRequest(
      request,
      initialRequired,
      consentBindingId
    );
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return backendOutcomeUncertain
        ? reportTransportOutcomeUnknown() : privacyOperationInactive();
    }
    if (finalConsent.error) {
      cancelUpstream(response);
      return backendOutcomeUncertain
        ? reportTransportOutcomeUnknown() : finalConsent.error;
    }
    const protectedResponse = responseWithPrivacyOperation(
      await toBackendResponse(response),
      operation.lease,
      request.signal
    );
    responseOwnsOperation = true;
    return protectedResponse;
  } finally {
    if (!responseOwnsOperation) finishPrivacyOperation(operation.lease);
    admission?.release?.();
  }
}

export function reportTransportStatusNotFound(): Response {
  return Response.json(
    { detail: { code: "report_transport_status_not_found" } },
    { status: 404, headers: { "cache-control": "no-store" } }
  );
}

function reportTransportStatusUnavailable(): Response {
  return Response.json(
    { detail: { code: "gateway_report_transport_status_unavailable" } },
    { status: 502, headers: { "cache-control": "no-store" } }
  );
}

function reportTransportStatusPayload(
  value: unknown,
  reportId: string
): Record<string, unknown> | null {
  const payload = objectPayload(value);
  if (!payload || !exactObjectKeys(
    payload,
    ["persistence_state", "user_status", "transport_receipt"]
  )) return null;
  const receipt = objectPayload(payload.transport_receipt);
  if (!receipt || !exactObjectKeys(
    receipt,
    [
      "marker",
      "report_id",
      "persistence_marker",
      "payload_sha256",
      "payload_bytes"
    ]
  )) return null;
  if (
    payload.persistence_state !== "PERSISTED" ||
    typeof payload.user_status !== "string" ||
    !["RECEIVED", "IN_REVIEW", "COMPLETED"].includes(payload.user_status) ||
    receipt.marker !== "DATABASE_AND_ENCRYPTED_IMAGE_STORE" ||
    receipt.report_id !== reportId ||
    typeof receipt.persistence_marker !== "string" ||
    !CANONICAL_REPORT_UUID.test(receipt.persistence_marker) ||
    typeof receipt.payload_sha256 !== "string" ||
    !LOWER_SHA256.test(receipt.payload_sha256) ||
    typeof receipt.payload_bytes !== "number" ||
    !Number.isSafeInteger(receipt.payload_bytes) ||
    Number(receipt.payload_bytes) < 1
  ) return null;
  return {
    persistence_state: payload.persistence_state,
    user_status: payload.user_status,
    transport_receipt: {
      marker: receipt.marker,
      report_id: receipt.report_id,
      persistence_marker: receipt.persistence_marker,
      payload_sha256: receipt.payload_sha256,
      payload_bytes: receipt.payload_bytes
    }
  };
}

async function boundedReportTransportStatusPayload(
  response: Response,
  reportId: string
): Promise<Record<string, unknown> | null> {
  const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
  const contentLength = response.headers.get("content-length");
  if (
    !contentType.startsWith("application/json") ||
    response.body === null ||
    (contentLength !== null && (
      !/^\d+$/.test(contentLength) ||
      Number(contentLength) > REPORT_TRANSPORT_STATUS_BODY_LIMIT_BYTES
    ))
  ) {
    cancelUpstream(response);
    return null;
  }
  const headers = new Headers({ "content-type": contentType });
  if (contentLength !== null) headers.set("content-length", contentLength);
  const boundedRequest = new Request("http://gateway.invalid/internal/report-status", {
    method: "POST",
    headers,
    body: response.body,
    duplex: "half"
  } as RequestInit & { duplex: "half" });
  const bounded = await readBoundedJsonBody(
    boundedRequest,
    REPORT_TRANSPORT_STATUS_BODY_LIMIT_BYTES,
    5_000
  );
  if (bounded.error) {
    void boundedRequest.body?.cancel().catch(() => undefined);
    return null;
  }
  return reportTransportStatusPayload(bounded.value, reportId);
}

export async function reportTransportStatus(
  request: Request,
  reportId: string,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const denied = authorizeProxyRequest(request);
  if (denied) {
    return denied.status === 401 || denied.status === 403
      ? reportTransportStatusNotFound()
      : denied;
  }
  const actorId = authorizedProxyActor(request);
  if (!actorId) return reportTransportStatusNotFound();
  const backendGate = await backendAccountSessionGate(request, fetchImpl);
  if (backendGate) return backendGate.status === 401
    ? reportTransportStatusNotFound() : backendGate;
  const operation = startPrivacyOperation(
    actorId,
    gatewaySessionAccountGeneration(request)
  );
  if (operation.error) return operation.error.status === 409
    ? reportTransportStatusNotFound() : operation.error;
  try {
    if (!privacyOperationIsCurrent(operation.lease)) {
      return reportTransportStatusNotFound();
    }
    const init: RequestInit = {
      method: "GET",
      headers: proxyRequestHeaders(
        request,
        undefined,
        operation.lease.accountGeneration
      ),
      cache: "no-store",
      signal: operation.lease.controller.signal
    };
    const path = `/reports/v2/${reportId}/status`;
    const response = fetchImpl
      ? await fetchBackend(request, backendUrl(path), init, 15_000, fetchImpl)
      : await fetchBackend(request, backendUrl(path), init);
    if (!privacyOperationIsCurrent(operation.lease)) {
      cancelUpstream(response);
      return reportTransportStatusNotFound();
    }
    if ([401, 403, 404].includes(response.status)) {
      cancelUpstream(response);
      return reportTransportStatusNotFound();
    }
    if (response.status !== 200) {
      cancelUpstream(response);
      return reportTransportStatusUnavailable();
    }
    const payload = await boundedReportTransportStatusPayload(response, reportId);
    if (!privacyOperationIsCurrent(operation.lease)) {
      return reportTransportStatusNotFound();
    }
    if (!payload) return reportTransportStatusUnavailable();
    return Response.json(payload, {
      status: 200,
      headers: { "cache-control": "no-store" }
    });
  } finally {
    finishPrivacyOperation(operation.lease);
  }
}

