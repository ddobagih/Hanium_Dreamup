import {
  type GatewayFetch,
  proxyRequestHeaders,
  ACTOR_ID_HEADER,
  ACCOUNT_GENERATION_HEADER,
  ACTOR_ASSERTION_HEADER,
  backendUrl,
  fetchBackend,
  toBackendResponse
} from "./backend.js";
import { createPublicKey, timingSafeEqual } from "node:crypto";
import { GatewayStorageOutcomeUnknownError } from "./encrypted-json-store.js";
import { QueueAdmissionAbortedError, QueueAdmissionBusyError } from "./keyed-admission-queue.js";
import { readBoundedJsonBody } from "./request-body.js";
import {
  type IntegratedConsentItemVersions,
  type IntegratedConsentSelections,
  IntegratedConsentRequestError,
  type IntegratedConsentState,
  sha256,
  SHA256,
  type IntegratedConsentEvent,
  authoritativeEvent,
  CONSENT_KEYS,
  type AuthoritativeIntegratedConsentEvent,
  type IntegratedConsentConfirmation,
  INSTALLATION_ID,
  REQUEST_ID,
  equalItemVersions,
  INTEGRATED_CONSENT_ITEM_VERSIONS,
  equalSelections,
  eventAuditSha256,
  MAX_EVENTS,
  WITHDRAWAL_RESERVE_EVENTS,
  INTEGRATED_CONSENT_MAX_STATE_BYTES,
  NORMAL_MAX_STATE_BYTES,
  type IntegratedConsentEventV5,
  eventAuditRecord,
  objectValue,
  exactKeys,
  validWholeSecondInstant,
  itemVersionsOrNull,
  selectionsOrNull,
  hasCurrentIntegratedConsentItemVersions,
  type IntegratedConsentKey
} from "./integrated-consent-codec.js";
import {
  readState,
  withInstallationLock,
  archivedRequestState,
  archiveConsentHistory,
  writeState
} from "./integrated-consent-store.js";

// Owner decision R3 (2026-09-29): RAW and automatic reports are sent after the walk ends, a
// substantive change of both disclosures, so those items and the document are 1.2.0. A stored
// 1.1.0 confirmation no longer authorizes any request; the app asks again through bootstrap.
export const INTEGRATED_CONSENT_POLICY_VERSION = "FP-013-1.2.0";

export const INTEGRATED_CONSENT_CONTROL = "integrated-consent";

export const INTEGRATED_CONSENT_BOOTSTRAP_CONTROL =
  "integrated-consent-bootstrap";

export const CONSENT_INSTALLATION_HEADER = "x-walksafe-consent-installation-id";

export const CONSENT_POLICY_HEADER = "x-walksafe-consent-policy-version";

export const CONSENT_REVISION_HEADER = "x-walksafe-consent-revision";

export const CONSENT_RECEIPT_HEADER = "x-walksafe-consent-receipt-sha256";

export const CONSENT_CONTROL_SECRET_HEADER = "x-walksafe-consent-control-secret";

export const CONSENT_NETWORK_TRANSPORT_HEADER = "x-walksafe-network-transport";

const CONTROL_SECRET = /^[0-9a-f]{64}$/;

type ConsentRecordInput = {
  installationId: string;
  requestId: string;
  policyVersion: string;
  itemVersions: IntegratedConsentItemVersions;
  clientRevision: number;
  expectedPreviousBackendReceiptSha256: string | null;
  controlSecret: string;
  selections: IntegratedConsentSelections;
  fieldActorBindingId: string;
};

export type IntegratedConsentBackendContext = {
  accountGeneration: number;
  fieldActorBindingId: string;
  signal: AbortSignal;
  fetchImpl?: GatewayFetch;
  isCurrent?: () => boolean;
};

type PrivacyConsentReceiptV2 = {
  schema_version: "walksafe.privacy-consent-receipt.v2";
  request_id: string;
  client_revision: number;
  receipt_sha256: string;
  recorded_at: string;
};

export type IntegratedConsentBootstrap = {
  schema_version: "walksafe.integrated-consent-bootstrap.v1";
  status: "READY" | "RECONSENT_REQUIRED";
  source: "CURRENT_CONSENT" | "SIGNUP_CONSENT" | "NONE";
  installation_id: string;
  policy_version: string;
  item_versions: IntegratedConsentItemVersions;
  client_revision_floor: number;
  selections: IntegratedConsentSelections | null;
  source_receipt_sha256: string | null;
  expected_previous_backend_receipt_sha256: string | null;
};

function assertControlSecretFormat(controlSecret: string): void {
  if (!CONTROL_SECRET.test(controlSecret)) {
    throw new IntegratedConsentRequestError(
      401,
      "integrated_consent_control_authentication_required",
      "A valid consent control secret is required."
    );
  }
}

function verifyControlSecret(
  state: IntegratedConsentState,
  controlSecret: string,
  register: boolean
): void {
  assertControlSecretFormat(controlSecret);
  const digest = sha256(`integrated-consent-control\0${controlSecret}`);
  if (state.control_secret_sha256 === "" && register && state.events.length === 0) {
    state.control_secret_sha256 = digest;
    return;
  }
  if (
    !SHA256.test(state.control_secret_sha256) ||
    !timingSafeEqual(
      Buffer.from(state.control_secret_sha256, "hex"),
      Buffer.from(digest, "hex")
    )
  ) {
    throw new IntegratedConsentRequestError(
      403,
      "integrated_consent_control_forbidden",
      "Consent control authentication failed."
    );
  }
}

function isMonotoneWithdrawal(
  previous: IntegratedConsentEvent | undefined,
  selections: IntegratedConsentSelections,
  actorSha256: string
): boolean {
  return previous !== undefined && authoritativeEvent(previous) &&
    previous.field_actor_binding_sha256 === actorSha256 &&
    CONSENT_KEYS.every((key) => !selections[key] || previous.selections[key]) &&
    CONSENT_KEYS.some((key) => previous.selections[key] && !selections[key]);
}

function confirmation(
  installationId: string,
  event: AuthoritativeIntegratedConsentEvent
): IntegratedConsentConfirmation {
  return {
    schema_version: "walksafe.integrated-consent-confirmation.v2",
    current: true,
    installation_id: installationId,
    request_id: event.request_id,
    policy_version: event.policy_version,
    item_versions: { ...event.item_versions },
    client_revision: event.client_revision,
    revision: event.revision,
    selections: { ...event.selections },
    confirmed_at: event.confirmed_at,
    gateway_audit_record_sha256: event.gateway_audit_record_sha256,
    backend_consent_receipt_sha256: event.backend_consent_receipt_sha256
  };
}

function currentConfirmation(
  installationId: string,
  controlSecret: string,
  fieldActorBindingId: string
): IntegratedConsentConfirmation | null {
  if (!INSTALLATION_ID.test(installationId)) {
    throw new IntegratedConsentRequestError(400, "integrated_consent_installation_invalid", "Installation ID is invalid.");
  }
  assertControlSecretFormat(controlSecret);
  const state = readState(installationId);
  if (state.events.length === 0) return null;
  verifyControlSecret(state, controlSecret, false);
  const current = state.events.at(-1);
  if (current && !authoritativeEvent(current)) {
    throw new IntegratedConsentRequestError(
      409,
      "integrated_consent_reconsent_required",
      "A Backend-confirmed consent receipt is required."
    );
  }
  const binding = state.field_actor_binding ?? null;
  const actorSha256 = sha256(
    `integrated-consent-field-actor\0${fieldActorBindingId}`
  );
  if (
    current &&
    (!binding ||
      binding.consent_revision !== current.revision ||
      !timingSafeEqual(
        Buffer.from(binding.actor_sha256, "hex"),
        Buffer.from(actorSha256, "hex")
      ))
  ) {
    throw new IntegratedConsentRequestError(
      409,
      "integrated_consent_actor_reconsent_required",
      "Consent must be confirmed for the current field actor."
    );
  }
  return current ? confirmation(installationId, current) : null;
}

async function recordConsent(
  input: ConsentRecordInput,
  syncBackend: () => Promise<ConsentBackendSyncResult>,
  signal: AbortSignal,
  nowMs = Date.now()
): Promise<
  | {
      confirmation: IntegratedConsentConfirmation;
      created: boolean;
      backendStatus: 200 | 201;
      error?: never;
    }
  | {
      confirmation?: never;
      created?: never;
      backendStatus?: never;
      error: Response;
    }
> {
  if (!INSTALLATION_ID.test(input.installationId) || !REQUEST_ID.test(input.requestId)) {
    throw new IntegratedConsentRequestError(400, "integrated_consent_request_invalid", "Consent request identity is invalid.");
  }
  if (input.policyVersion !== INTEGRATED_CONSENT_POLICY_VERSION) {
    throw new IntegratedConsentRequestError(409, "integrated_consent_reconsent_required", "The current consent policy version is required.");
  }
  if (!equalItemVersions(input.itemVersions, INTEGRATED_CONSENT_ITEM_VERSIONS)) {
    throw new IntegratedConsentRequestError(
      409,
      "integrated_consent_reconsent_required",
      "The current item policy versions are required."
    );
  }
  if (!Number.isSafeInteger(input.clientRevision) || input.clientRevision <= 0) {
    throw new IntegratedConsentRequestError(
      400,
      "integrated_consent_client_revision_invalid",
      "Client revision must be a positive safe integer."
    );
  }
  if (
    input.expectedPreviousBackendReceiptSha256 !== null &&
    !SHA256.test(input.expectedPreviousBackendReceiptSha256)
  ) {
    throw new IntegratedConsentRequestError(
      400,
      "integrated_consent_expected_previous_receipt_invalid",
      "Expected previous Backend receipt must be null or a lowercase SHA-256."
    );
  }
  if (
    input.fieldActorBindingId.trim().length === 0 ||
    input.fieldActorBindingId.length > 128
  ) {
    throw new IntegratedConsentRequestError(
      401,
      "integrated_consent_field_session_required",
      "A live field session is required to record consent."
    );
  }
  if (!Number.isSafeInteger(nowMs) || nowMs <= 0) {
    throw new IntegratedConsentRequestError(503, "integrated_consent_clock_unavailable", "Consent clock is unavailable.");
  }
  return withInstallationLock(input.installationId, async () => {
    const actorSha256 = sha256(
      `integrated-consent-field-actor\0${input.fieldActorBindingId}`
    );
    const state = readState(input.installationId, { requestId: input.requestId, actorSha256 });
    verifyControlSecret(state, input.controlSecret, true);
    const binding = state.field_actor_binding ?? null;
    const prior = archivedRequestState.get(state)?.prior ?? state.events.find((event) =>
      event.request_id === input.requestId &&
      (!authoritativeEvent(event) ||
        event.field_actor_binding_sha256 === actorSha256)
    );
    if (prior) {
      if (!authoritativeEvent(prior)) {
        throw new IntegratedConsentRequestError(
          409,
          "integrated_consent_reconsent_required",
          "A new Backend-confirmed consent decision is required."
        );
      }
      if (binding && !timingSafeEqual(
        Buffer.from(binding.actor_sha256, "hex"),
        Buffer.from(actorSha256, "hex")
      )) {
        throw new IntegratedConsentRequestError(
          409,
          "integrated_consent_actor_reconsent_required",
          "Consent must be confirmed for the current field actor."
        );
      }
      if (
        prior.policy_version !== input.policyVersion ||
        prior.client_revision !== input.clientRevision ||
        !("expected_previous_backend_receipt_sha256" in prior) ||
        prior.expected_previous_backend_receipt_sha256 !==
          input.expectedPreviousBackendReceiptSha256 ||
        !equalItemVersions(prior.item_versions, input.itemVersions) ||
        !equalSelections(prior.selections, input.selections)
      ) {
        throw new IntegratedConsentRequestError(409, "integrated_consent_request_conflict", "Request ID was already used.");
      }
      if (state.events.at(-1) === undefined ||
        eventAuditSha256(state.events.at(-1)!) !== prior.gateway_audit_record_sha256) {
        throw new IntegratedConsentRequestError(
          409,
          "integrated_consent_request_stale",
          "The idempotent request is no longer current."
        );
      }
      const synced = await syncBackend();
      if (synced.error) return { error: synced.error };
      if (
        synced.receipt.receipt_sha256 !== prior.backend_consent_receipt_sha256 ||
        synced.receipt.recorded_at !== prior.backend_recorded_at
      ) {
        throw new IntegratedConsentRequestError(
          502,
          "integrated_consent_backend_receipt_mismatch",
          "The consent Backend returned a different receipt for an existing request."
        );
      }
      return {
        confirmation: confirmation(input.installationId, prior),
        created: false,
        backendStatus: synced.status
      };
    }
    // S19-29: an all-false request narrows every permission whatever the last event is
    // (legacy, reconsent, or another actor's), so it may use the withdrawal reserve too.
    const withdrawal = isMonotoneWithdrawal(
      state.events.at(-1), input.selections, actorSha256
    ) || CONSENT_KEYS.every((key) => !input.selections[key]);
    // Check the actor floor before moving its newest event out of the current tail.
    const currentClientRevision = state.events.findLast((event) =>
      authoritativeEvent(event) && event.field_actor_binding_sha256 === actorSha256
    )?.client_revision ?? archivedRequestState.get(state)?.actorFloor ?? 0;
    if (input.clientRevision <= currentClientRevision) {
      throw new IntegratedConsentRequestError(409, "integrated_consent_client_revision_stale",
        "A newer consent decision is already stored.");
    }
    // Archival changes storage only, and commits before any upstream grant. On archive
    // failure only a narrowing decision can use the existing bounded withdrawal reserve.
    const archivedSuccessfully = archiveConsentHistory(state);
    const tail = state.events;
    const projectedBytes = Buffer.byteLength(JSON.stringify({ ...state, events: tail }), "utf8") + 4096;
    if (
      tail.length >= MAX_EVENTS + WITHDRAWAL_RESERVE_EVENTS ||
      projectedBytes > INTEGRATED_CONSENT_MAX_STATE_BYTES ||
      (!withdrawal && (!archivedSuccessfully || tail.length >= MAX_EVENTS ||
        projectedBytes > NORMAL_MAX_STATE_BYTES))
    ) {
      throw new IntegratedConsentRequestError(503, "integrated_consent_capacity_unavailable", "Consent ledger capacity is unavailable.");
    }
    const previousAuditRecord = state.events.at(-1)
      ? eventAuditSha256(state.events.at(-1)!)
      : null;
    const synced = await syncBackend();
    if (synced.error) return { error: synced.error };
    const eventWithoutAudit: Omit<IntegratedConsentEventV5, "gateway_audit_record_sha256"> = {
      request_id: input.requestId,
      policy_version: input.policyVersion,
      item_versions: { ...input.itemVersions },
      client_revision: input.clientRevision,
      revision: (state.revision ?? state.events.length) + 1,
      selections: { ...input.selections },
      confirmed_at: new Date(nowMs).toISOString(),
      previous_gateway_audit_record_sha256: previousAuditRecord,
      backend_consent_receipt_sha256: synced.receipt.receipt_sha256,
      backend_recorded_at: synced.receipt.recorded_at,
      field_actor_binding_sha256: actorSha256,
      expected_previous_backend_receipt_sha256:
        input.expectedPreviousBackendReceiptSha256
    };
    const event: IntegratedConsentEventV5 = {
      ...eventWithoutAudit,
      gateway_audit_record_sha256: eventAuditRecord(
        input.installationId,
        eventWithoutAudit
      )
    };
    if (state.schema_version !== 7) state.schema_version = 6;
    state.events.push(event);
    if (state.schema_version === 7) state.revision = event.revision;
    state.field_actor_binding = {
      actor_sha256: actorSha256,
      consent_revision: event.revision,
      bound_at: new Date(nowMs).toISOString()
    };
    try { writeState(state); } catch (error) {
      // Backend already accepted this decision, even if local rename did not happen.
      // Retry keeps the same upstream idempotency key and adopts its existing receipt.
      throw error instanceof GatewayStorageOutcomeUnknownError ? error
        : new GatewayStorageOutcomeUnknownError("integrated-consent", { cause: error });
    }
    return {
      confirmation: confirmation(input.installationId, event),
      created: true,
      backendStatus: synced.status
    };
  }, signal);
}

type ConsentBackendSyncResult =
  | { receipt: PrivacyConsentReceiptV2; status: 200 | 201; error?: never }
  | { receipt?: never; status?: never; error: Response };

function gatewayConsentBackendError(
  status: number,
  code: string,
  message: string
): Response {
  return Response.json(
    { detail: { code, message } },
    { status, headers: { "cache-control": "no-store" } }
  );
}

async function boundedConsentBackendJson(response: Response): Promise<unknown | null> {
  const maxBytes = 16 * 1024;
  const declared = response.headers.get("content-length");
  if (declared !== null) {
    const length = Number(declared);
    if (!Number.isSafeInteger(length) || length < 0 || length > maxBytes) {
      void response.body?.cancel("consent backend response is too large").catch(() => undefined);
      return null;
    }
  }
  if (!response.body) return null;
  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const chunk = await reader.read();
      if (chunk.done) break;
      total += chunk.value.byteLength;
      if (total > maxBytes) {
        await reader.cancel("consent backend response is too large");
        return null;
      }
      chunks.push(chunk.value);
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    return JSON.parse(
      new TextDecoder("utf-8", { fatal: true }).decode(bytes)
    ) as unknown;
  } catch {
    return null;
  } finally {
    try { reader.releaseLock(); } catch { /* already cancelled */ }
  }
}

function privacyConsentReceiptOrNull(
  value: unknown,
  input: ConsentRecordInput
): PrivacyConsentReceiptV2 | null {
  const receipt = objectValue(value);
  if (
    !receipt ||
    !exactKeys(receipt, [
      "schema_version",
      "request_id",
      "client_revision",
      "receipt_sha256",
      "recorded_at"
    ]) ||
    receipt.schema_version !== "walksafe.privacy-consent-receipt.v2" ||
    receipt.request_id !== input.requestId ||
    receipt.client_revision !== input.clientRevision ||
    typeof receipt.receipt_sha256 !== "string" ||
    !SHA256.test(receipt.receipt_sha256) ||
    !validWholeSecondInstant(receipt.recorded_at)
  ) {
    return null;
  }
  return {
    schema_version: "walksafe.privacy-consent-receipt.v2",
    request_id: input.requestId,
    client_revision: input.clientRevision,
    receipt_sha256: receipt.receipt_sha256,
    recorded_at: receipt.recorded_at
  };
}

function integratedConsentBootstrapOrNull(
  value: unknown,
  installationId: string
): IntegratedConsentBootstrap | null {
  const payload = objectValue(value);
  if (
    !payload ||
    !exactKeys(payload, [
      "schema_version",
      "status",
      "source",
      "installation_id",
      "policy_version",
      "item_versions",
      "client_revision_floor",
      "selections",
      "source_receipt_sha256",
      "expected_previous_backend_receipt_sha256"
    ]) ||
    payload.schema_version !== "walksafe.integrated-consent-bootstrap.v1" ||
    (payload.status !== "READY" && payload.status !== "RECONSENT_REQUIRED") ||
    (payload.source !== "CURRENT_CONSENT" &&
      payload.source !== "SIGNUP_CONSENT" &&
      payload.source !== "NONE") ||
    payload.installation_id !== installationId ||
    payload.policy_version !== INTEGRATED_CONSENT_POLICY_VERSION ||
    !Number.isSafeInteger(payload.client_revision_floor) ||
    (payload.client_revision_floor as number) < 0
  ) {
    return null;
  }
  const itemVersions = itemVersionsOrNull(payload.item_versions);
  if (!itemVersions || !equalItemVersions(itemVersions, INTEGRATED_CONSENT_ITEM_VERSIONS)) {
    return null;
  }
  const selections = payload.selections === null
    ? null
    : selectionsOrNull(payload.selections);
  if (payload.selections !== null && selections === null) return null;
  const sourceReceipt = payload.source_receipt_sha256;
  const expectedPrevious = payload.expected_previous_backend_receipt_sha256;
  if (
    (sourceReceipt !== null &&
      (typeof sourceReceipt !== "string" || !SHA256.test(sourceReceipt))) ||
    (expectedPrevious !== null &&
      (typeof expectedPrevious !== "string" || !SHA256.test(expectedPrevious))) ||
    (expectedPrevious === null && payload.client_revision_floor !== 0) ||
    (payload.status === "READY" &&
      (payload.source === "NONE" || selections === null)) ||
    (payload.status === "RECONSENT_REQUIRED" &&
      (payload.source !== "NONE" || selections !== null)) ||
    (payload.source === "NONE" && sourceReceipt !== null) ||
    (payload.source !== "NONE" && sourceReceipt === null)
  ) {
    return null;
  }
  return {
    schema_version: "walksafe.integrated-consent-bootstrap.v1",
    status: payload.status,
    source: payload.source,
    installation_id: installationId,
    policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
    item_versions: { ...itemVersions },
    client_revision_floor: payload.client_revision_floor as number,
    selections,
    source_receipt_sha256: sourceReceipt as string | null,
    expected_previous_backend_receipt_sha256: expectedPrevious as string | null
  };
}

async function fetchIntegratedConsentBootstrap(
  request: Request,
  installationId: string,
  context: IntegratedConsentBackendContext
): Promise<Response> {
  if (!INSTALLATION_ID.test(installationId)) {
    throw new IntegratedConsentRequestError(
      400,
      "integrated_consent_installation_invalid",
      "Installation ID is invalid."
    );
  }
  const headers = proxyRequestHeaders(
    request,
    { accept: "application/json" },
    context.accountGeneration
  );
  if (
    !headers.get(ACTOR_ID_HEADER) ||
    headers.get(ACCOUNT_GENERATION_HEADER) !== String(context.accountGeneration) ||
    !headers.get(ACTOR_ASSERTION_HEADER)
  ) {
    return gatewayConsentBackendError(
      503,
      "gateway_backend_assertion_unavailable",
      "The backend actor assertion is unavailable."
    );
  }
  const query = new URLSearchParams({
    installation_id: installationId,
    policy_version: INTEGRATED_CONSENT_POLICY_VERSION
  });
  const init: RequestInit = {
    method: "GET",
    headers,
    cache: "no-store",
    signal: context.signal
  };
  const target = backendUrl(`/privacy/consent-bootstrap?${query.toString()}`);
  const response = context.fetchImpl
    ? await fetchBackend(request, target, init, 15_000, context.fetchImpl)
    : await fetchBackend(request, target, init);
  if (response.status !== 200) return toBackendResponse(response);
  const bootstrap = integratedConsentBootstrapOrNull(
    await boundedConsentBackendJson(response),
    installationId
  );
  if (!bootstrap) {
    return gatewayConsentBackendError(
      502,
      "gateway_upstream_protocol_invalid",
      "The consent Backend returned an invalid bootstrap response."
    );
  }
  return Response.json(bootstrap, {
    status: 200,
    headers: { "cache-control": "no-store" }
  });
}

async function syncConsentToBackend(
  request: Request,
  input: ConsentRecordInput,
  context: IntegratedConsentBackendContext
): Promise<ConsentBackendSyncResult> {
  if (context.signal.aborted || context.isCurrent?.() === false) {
    return { error: gatewayConsentBackendError(
      401, "integrated_consent_field_session_required", "A live field session is required."
    ) };
  }
  const body = JSON.stringify({
    schema_version: "walksafe.privacy-consent-event.v2",
    installation_id: input.installationId,
    request_id: input.requestId,
    client_revision: input.clientRevision,
    expected_previous_backend_receipt_sha256:
      input.expectedPreviousBackendReceiptSha256,
    policy_version: input.policyVersion,
    item_versions: input.itemVersions,
    raw_source_collection: input.selections.raw_source_collection,
    automatic_reporting: input.selections.automatic_reporting,
    mobile_network_transfer: input.selections.mobile_network_transfer,
    training_reuse: input.selections.training_reuse
  });
  const headers = proxyRequestHeaders(
    request,
    { accept: "application/json", "content-type": "application/json" },
    context.accountGeneration
  );
  if (
    !headers.get(ACTOR_ID_HEADER) ||
    headers.get(ACCOUNT_GENERATION_HEADER) !== String(context.accountGeneration) ||
    !headers.get(ACTOR_ASSERTION_HEADER)
  ) {
    return {
      error: gatewayConsentBackendError(
        503,
        "gateway_backend_assertion_unavailable",
        "The backend actor assertion is unavailable."
      )
    };
  }
  const init: RequestInit = {
    method: "POST",
    headers,
    body,
    cache: "no-store",
    signal: context.signal
  };
  const response = context.fetchImpl
    ? await fetchBackend(
        request,
        backendUrl("/privacy/consent-events"),
        init,
        15_000,
        context.fetchImpl
      )
    : await fetchBackend(
        request,
        backendUrl("/privacy/consent-events"),
        init
      );
  if (context.signal.aborted || context.isCurrent?.() === false) {
    void response.body?.cancel().catch(() => undefined);
    return { error: gatewayConsentBackendError(
      401, "integrated_consent_field_session_required", "A live field session is required."
    ) };
  }
  if (response.status !== 200 && response.status !== 201) {
    return { error: await toBackendResponse(response) };
  }
  const receipt = privacyConsentReceiptOrNull(
    await boundedConsentBackendJson(response),
    input
  );
  if (!receipt) {
    return {
      error: gatewayConsentBackendError(
        502,
        "gateway_upstream_protocol_invalid",
        "The consent backend returned an invalid receipt."
      )
    };
  }
  return { receipt, status: response.status };
}

function errorResponse(error: unknown): Response {
  // S19-27: the local write may be durable. Resending the same request_id is safe: a
  // committed event is replayed, otherwise the Backend's idempotent receipt is adopted.
  if (error instanceof GatewayStorageOutcomeUnknownError) {
    return Response.json(
      {
        detail: {
          code: "integrated_consent_outcome_unknown",
          message: "Consent storage outcome is unknown; resend the same request.",
          required_policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
          retry: "same_request_id"
        }
      },
      { status: 503, headers: { "cache-control": "no-store", "retry-after": "1" } }
    );
  }
  if (error instanceof QueueAdmissionAbortedError) {
    return Response.json(
      { detail: { code: "gateway_client_closed", message: "client request was cancelled" } },
      { status: 499, headers: { "cache-control": "no-store" } }
    );
  }
  const known = error instanceof QueueAdmissionBusyError
    ? new IntegratedConsentRequestError(
        503, "integrated_consent_store_busy", "Consent storage is busy."
      )
    : error instanceof IntegratedConsentRequestError
    ? error
    : new IntegratedConsentRequestError(
        503,
        "integrated_consent_store_unavailable",
        "Consent storage is unavailable."
      );
  return Response.json(
    {
      detail: {
        code: known.code,
        message: known.message,
        required_policy_version: INTEGRATED_CONSENT_POLICY_VERSION
      }
    },
    { status: known.status, headers: { "cache-control": "no-store" } }
  );
}

export async function handleIntegratedConsentBootstrapRequest(
  request: Request,
  backendContext: IntegratedConsentBackendContext
): Promise<Response> {
  const url = new URL(request.url);
  const queryKeys = [...url.searchParams.keys()];
  try {
    if (
      request.method !== "GET" ||
      queryKeys.length !== 3 ||
      !queryKeys.includes("control") ||
      !queryKeys.includes("installation_id") ||
      !queryKeys.includes("policy_version") ||
      url.searchParams.get("control") !== INTEGRATED_CONSENT_BOOTSTRAP_CONTROL
    ) {
      throw new IntegratedConsentRequestError(
        400,
        "integrated_consent_bootstrap_query_invalid",
        "Consent bootstrap query is invalid."
      );
    }
    if (url.searchParams.get("policy_version") !== INTEGRATED_CONSENT_POLICY_VERSION) {
      throw new IntegratedConsentRequestError(
        409,
        "integrated_consent_reconsent_required",
        "The current consent policy version is required."
      );
    }
    return await fetchIntegratedConsentBootstrap(
      request,
      url.searchParams.get("installation_id") ?? "",
      backendContext
    );
  } catch (error) {
    return errorResponse(error);
  }
}

function validInstallationEvidencePublicKey(value: unknown): value is string {
  if (typeof value !== "string" || !/^[A-Za-z0-9_-]{79,122}$/.test(value)) return false;
  const der = Buffer.from(value, "base64url");
  if (der.toString("base64url") !== value) return false;
  try {
    const key = createPublicKey({ key: der, format: "der", type: "spki" });
    return key.asymmetricKeyType === "ec" && key.asymmetricKeyDetails?.namedCurve === "prime256v1" &&
      key.export({ format: "der", type: "spki" }).equals(der);
  } catch {
    return false;
  }
}

export async function handleIntegratedConsentRequest(
  request: Request,
  backendContext?: IntegratedConsentBackendContext
): Promise<Response> {
  const url = new URL(request.url);
  const queryKeys = [...url.searchParams.keys()];
  try {
    if (url.searchParams.get("control") === "installation-evidence-key") {
      if (request.method !== "PUT" || queryKeys.length !== 1 || !backendContext) {
        throw new IntegratedConsentRequestError(400, "installation_evidence_key_request_invalid", "Invalid registration request.");
      }
      const bounded = await readBoundedJsonBody(request, 1024);
      if (bounded.error) return bounded.error;
      const value = objectValue(bounded.value);
      if (!value || !exactKeys(value, ["installation_id", "public_key"]) ||
          typeof value.installation_id !== "string" || !INSTALLATION_ID.test(value.installation_id) ||
          !validInstallationEvidencePublicKey(value.public_key)) {
        throw new IntegratedConsentRequestError(400, "installation_evidence_key_request_invalid", "Invalid registration request.");
      }
      const signal = AbortSignal.any([request.signal, backendContext.signal]);
      return await withInstallationLock(value.installation_id, async () => {
        signal.throwIfAborted();
        const current = currentConfirmation(value.installation_id as string,
          request.headers.get(CONSENT_CONTROL_SECRET_HEADER)?.trim() ?? "", backendContext.fieldActorBindingId);
        if (!current || current.policy_version !== INTEGRATED_CONSENT_POLICY_VERSION ||
            !hasCurrentIntegratedConsentItemVersions(current)) {
          throw new IntegratedConsentRequestError(409, "integrated_consent_reconsent_required", "Confirmed installation consent is required.");
        }
        if (backendContext.isCurrent?.() === false) {
          throw new IntegratedConsentRequestError(401, "integrated_consent_field_session_required", "A live field session is required.");
        }
        const headers = proxyRequestHeaders(request,
          { accept: "application/json", "content-type": "application/json" }, backendContext.accountGeneration);
        if (!headers.get(ACTOR_ID_HEADER) || !headers.get(ACTOR_ASSERTION_HEADER) ||
            headers.get(ACCOUNT_GENERATION_HEADER) !== String(backendContext.accountGeneration)) {
          throw new IntegratedConsentRequestError(503, "gateway_backend_assertion_unavailable", "Actor assertion is unavailable.");
        }
        const response = await fetchBackend(request, backendUrl("/privacy/installation-evidence-key"),
          { method: "POST", headers, body: JSON.stringify(value), cache: "no-store", signal },
          15_000, backendContext.fetchImpl ?? globalThis.fetch);
        if (signal.aborted || backendContext.isCurrent?.() === false) {
          void response.body?.cancel().catch(() => undefined);
          throw new IntegratedConsentRequestError(401, "integrated_consent_field_session_required", "A live field session is required.");
        }
        if (response.status === 204) {
          return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
        }
        if (!response.body || response.headers.get("content-type")?.split(";", 1)[0]?.trim() !== "application/json") {
          void response.body?.cancel().catch(() => undefined);
          throw new IntegratedConsentRequestError(502, "gateway_upstream_protocol_invalid", "Invalid key registration response.");
        }
        const decoded = await readBoundedJsonBody(new Request("http://gateway.invalid/internal/installation-key-response", {
          method: "POST", headers: response.headers, body: response.body, signal, duplex: "half"
        } as RequestInit & { duplex: "half" }), 16 * 1024, 5_000);
        if (signal.aborted || backendContext.isCurrent?.() === false) {
          throw new IntegratedConsentRequestError(401, "integrated_consent_field_session_required", "A live field session is required.");
        }
        const payload = objectValue(decoded.value);
        const detail = objectValue(payload?.detail);
        const code = detail?.code;
        if (![400, 401, 403, 409, 422, 499, 502, 503, 504].includes(response.status) ||
            typeof code !== "string" || !/^[a-z][a-z0-9_]{0,63}$/.test(code)) {
          throw new IntegratedConsentRequestError(502, "gateway_upstream_protocol_invalid", "Invalid key registration response.");
        }
        return Response.json({ detail: { code } },
          { status: response.status, headers: { "cache-control": "no-store" } });
      }, signal);
    }
    if (request.method === "GET") {
      if (
        queryKeys.length !== 3 ||
        !queryKeys.includes("control") ||
        !queryKeys.includes("installation_id") ||
        !queryKeys.includes("policy_version") ||
        url.searchParams.get("control") !== INTEGRATED_CONSENT_CONTROL
      ) {
        throw new IntegratedConsentRequestError(400, "integrated_consent_query_invalid", "Consent query is invalid.");
      }
      if (url.searchParams.get("policy_version") !== INTEGRATED_CONSENT_POLICY_VERSION) {
        throw new IntegratedConsentRequestError(409, "integrated_consent_reconsent_required", "The current consent policy version is required.");
      }
      if (!backendContext) {
        throw new IntegratedConsentRequestError(
          401,
          "integrated_consent_field_session_required",
          "A live field session is required to read consent."
        );
      }
      const current = currentConfirmation(
        url.searchParams.get("installation_id") ?? "",
        request.headers.get(CONSENT_CONTROL_SECRET_HEADER)?.trim() ?? "",
        backendContext.fieldActorBindingId
      );
      if (!current) {
        throw new IntegratedConsentRequestError(404, "integrated_consent_not_found", "No consent selection is stored.");
      }
      if (
        current.policy_version !== INTEGRATED_CONSENT_POLICY_VERSION ||
        !hasCurrentIntegratedConsentItemVersions(current)
      ) {
        throw new IntegratedConsentRequestError(
          409,
          "integrated_consent_reconsent_required",
          "The current consent item policy versions are required."
        );
      }
      // S19-31: the local ledger is only a cache. `current: true` is returned only while the
      // Backend's current consent is what this confirmation says: the same latest receipt,
      // or (S19-31-F1) the same selections and item versions re-recorded by another
      // installation of the account, whose newer receipt must not force this one to
      // re-consent (and so re-record and invalidate the other one in turn). A different
      // selection, a reconsent requirement or a Backend reset still requires bootstrap.
      const authority = await fetchIntegratedConsentBootstrap(
        request,
        current.installation_id,
        backendContext
      );
      if (authority.status !== 200) return authority;
      const bootstrap = await authority.json() as IntegratedConsentBootstrap;
      const sameReceipt = bootstrap.expected_previous_backend_receipt_sha256 ===
        current.backend_consent_receipt_sha256;
      const sameConsent = bootstrap.selections !== null &&
        equalSelections(bootstrap.selections, current.selections) &&
        equalItemVersions(bootstrap.item_versions, current.item_versions);
      if (
        bootstrap.status !== "READY" ||
        bootstrap.source !== "CURRENT_CONSENT" ||
        !sameConsent ||
        (!sameReceipt && bootstrap.expected_previous_backend_receipt_sha256 === null)
      ) {
        throw new IntegratedConsentRequestError(
          409,
          "integrated_consent_reconsent_required",
          "The Backend consent state changed; bootstrap is required."
        );
      }
      return Response.json(current, { headers: { "cache-control": "no-store" } });
    }
    if (
      request.method !== "PUT" ||
      queryKeys.length !== 1 ||
      url.searchParams.get("control") !== INTEGRATED_CONSENT_CONTROL
    ) {
      return Response.json(
        { code: "gateway_method_not_allowed" },
        { status: 405, headers: { allow: "GET, PUT", "cache-control": "no-store" } }
      );
    }
    const bounded = await readBoundedJsonBody(request, 4 * 1024);
    if (bounded.error) return bounded.error;
    const payload = objectValue(bounded.value);
    if (
      !payload ||
      !exactKeys(payload, [
        "schema_version",
        "installation_id",
        "request_id",
        "policy_version",
        "item_versions",
        "client_revision",
        "expected_previous_backend_receipt_sha256",
        "selections"
      ]) ||
      payload.schema_version !== "walksafe.integrated-consent-request.v1" ||
      typeof payload.installation_id !== "string" ||
      typeof payload.request_id !== "string" ||
      typeof payload.policy_version !== "string" ||
      typeof payload.client_revision !== "number" ||
      (payload.expected_previous_backend_receipt_sha256 !== null &&
        (typeof payload.expected_previous_backend_receipt_sha256 !== "string" ||
          !SHA256.test(payload.expected_previous_backend_receipt_sha256)))
    ) {
      throw new IntegratedConsentRequestError(400, "integrated_consent_request_invalid", "Consent request is invalid.");
    }
    const selections = selectionsOrNull(payload.selections);
    const itemVersions = itemVersionsOrNull(payload.item_versions);
    if (!selections || !itemVersions) {
      throw new IntegratedConsentRequestError(400, "integrated_consent_selections_invalid", "Four independent consent selections are required.");
    }
    if (!backendContext) {
      throw new IntegratedConsentRequestError(
        401,
        "integrated_consent_field_session_required",
        "A live field session is required to record consent."
      );
    }
    const input: ConsentRecordInput = {
      installationId: payload.installation_id,
      requestId: payload.request_id,
      policyVersion: payload.policy_version,
      itemVersions,
      clientRevision: payload.client_revision,
      expectedPreviousBackendReceiptSha256:
        payload.expected_previous_backend_receipt_sha256 as string | null,
      controlSecret:
        request.headers.get(CONSENT_CONTROL_SECRET_HEADER)?.trim() ?? "",
      selections,
      fieldActorBindingId: backendContext.fieldActorBindingId
    };
    const result = await recordConsent(
      input,
      () => syncConsentToBackend(request, input, backendContext),
      request.signal
    );
    if (result.error) return result.error;
    return Response.json(result.confirmation, {
      status: result.backendStatus,
      headers: { "cache-control": "no-store" }
    });
  } catch (error) {
    return errorResponse(error);
  }
}

export type IntegratedConsentAuthorization =
  | { confirmation: IntegratedConsentConfirmation; error?: never }
  | { confirmation?: never; error: Response };

export async function authorizeIntegratedConsentRequest(
  request: Request,
  requiredSelections: readonly IntegratedConsentKey[],
  fieldActorId: string
): Promise<IntegratedConsentAuthorization> {
  const installationId = request.headers.get(CONSENT_INSTALLATION_HEADER)?.trim() ?? "";
  const policyVersion = request.headers.get(CONSENT_POLICY_HEADER)?.trim() ?? "";
  const revisionText = request.headers.get(CONSENT_REVISION_HEADER)?.trim() ?? "";
  const receipt = request.headers.get(CONSENT_RECEIPT_HEADER)?.trim() ?? "";
  const controlSecret =
    request.headers.get(CONSENT_CONTROL_SECRET_HEADER)?.trim() ?? "";
  const revision = Number(revisionText);
  if (
    !INSTALLATION_ID.test(installationId) ||
    policyVersion !== INTEGRATED_CONSENT_POLICY_VERSION ||
    !Number.isSafeInteger(revision) ||
    revision <= 0 ||
    !SHA256.test(receipt) ||
    !CONTROL_SECRET.test(controlSecret) ||
    fieldActorId.trim().length === 0 ||
    fieldActorId.length > 128
  ) {
    return {
      error: Response.json(
        {
          detail: {
            code: "integrated_consent_confirmation_required",
            message: "A current server-confirmed consent receipt is required."
          }
        },
        { status: 428, headers: { "cache-control": "no-store" } }
      )
    };
  }
  try {
    return await withInstallationLock(installationId, () => {
      const state = readState(installationId);
      verifyControlSecret(state, controlSecret, false);
      const currentEvent = state.events.at(-1);
      if (currentEvent && !authoritativeEvent(currentEvent)) {
        throw new IntegratedConsentRequestError(
          409,
          "integrated_consent_reconsent_required",
          "A Backend-confirmed consent receipt is required."
        );
      }
      const current = currentEvent
        ? confirmation(installationId, currentEvent)
        : null;
      const receiptMatches = current !== null && timingSafeEqual(
        Buffer.from(current.backend_consent_receipt_sha256, "hex"),
        Buffer.from(receipt, "hex")
      );
      if (
        !current ||
        current.policy_version !== policyVersion ||
        current.revision !== revision ||
        !receiptMatches
      ) {
        return {
          error: Response.json(
            {
              detail: {
                code: "integrated_consent_confirmation_stale",
                message: "The consent receipt is not current.",
                required_policy_version: INTEGRATED_CONSENT_POLICY_VERSION
              }
            },
            { status: 409, headers: { "cache-control": "no-store" } }
          )
        };
      }
      if (!hasCurrentIntegratedConsentItemVersions(current)) {
        return {
          error: Response.json(
            {
              detail: {
                code: "integrated_consent_reconsent_required",
                message: "The current consent item policy versions are required.",
                required_policy_version: INTEGRATED_CONSENT_POLICY_VERSION
              }
            },
            { status: 409, headers: { "cache-control": "no-store" } }
          )
        };
      }
      const denied = requiredSelections.find((key) => current.selections[key] !== true);
      if (denied) {
        return {
          error: Response.json(
            {
              detail: {
                code: "integrated_consent_selection_denied",
                message: `Consent selection is denied: ${denied}.`
              }
            },
            { status: 403, headers: { "cache-control": "no-store" } }
          )
        };
      }
      const actorSha256 = sha256(`integrated-consent-field-actor\0${fieldActorId}`);
      const binding = state.field_actor_binding ?? null;
      const actorMatches = binding !== null && currentEvent !== undefined &&
        binding.consent_revision === currentEvent.revision &&
        timingSafeEqual(
          Buffer.from(binding.actor_sha256, "hex"),
          Buffer.from(actorSha256, "hex")
        ) &&
        timingSafeEqual(
          Buffer.from(currentEvent.field_actor_binding_sha256, "hex"),
          Buffer.from(actorSha256, "hex")
        );
      if (!actorMatches) {
        return {
          error: Response.json(
            {
              detail: {
                code: "integrated_consent_actor_reconsent_required",
                message: "Consent must be confirmed for the current field actor."
              }
            },
            { status: 409, headers: { "cache-control": "no-store" } }
          )
        };
      }
      return { confirmation: current };
    }, request.signal);
  } catch (error) {
    return { error: errorResponse(error) };
  }
}

export {
  INTEGRATED_CONSENT_MAX_STATE_BYTES,
  type IntegratedConsentKey,
  type IntegratedConsentItemVersions,
  INTEGRATED_CONSENT_ITEM_VERSIONS,
  type IntegratedConsentSelections,
  type IntegratedConsentConfirmation,
  hasCurrentIntegratedConsentItemVersions,
  validIntegratedConsentAuditSegmentForMaintenance
} from "./integrated-consent-codec.js";
export {
  setIntegratedConsentStorageFaultForTests,
  withIntegratedConsentStateFileLockForMaintenance,
  integratedConsentAuditSegmentPaths,
  validIntegratedConsentStateForMaintenance
} from "./integrated-consent-store.js";
