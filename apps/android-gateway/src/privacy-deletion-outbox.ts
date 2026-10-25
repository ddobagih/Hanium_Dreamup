import { randomBytes } from "node:crypto";
import { type GatewayFetch, deletionBackendHeaders, fetchBackend, backendUrl } from "./backend.js";
import { revokeFieldSessionsForSecurityEvent } from "./auth.js";
import { ExclusiveFileLockIntegrityError } from "./exclusive-file-lock.js";
import { GatewayStateEncryptionError } from "./encrypted-json-store.js";
import {
  type AccountDeletionStatusV2,
  type AccountDeletionStatusViewV2,
  objectValue,
  operationTerminalConflictCode,
  type DeletionRecord,
  type OutboxOperation,
  type PausedAccountDeletionOutboxOperationV2,
  requestDigest,
  operationDigest,
  type OutboxPause,
  PrivacyDeletionV2Error,
  ACCOUNT_DELETION_TERMINAL_CONFLICT_CODE_SET,
  trimReplayOnlyEvidence,
  boundAccessDigest,
  parseAccountDeletionStatusV2,
  parseAccountDeletionStatusViewV2,
  KNOWN_CONTRACT_422_CODES,
  type DeletionLedger,
  SHA256,
  PAUSE_ID
} from "./privacy-deletion-codec.js";
import {
  mutateLedger,
  readLedger,
  recordFromStores,
  authorizedAccountDeletionStatusV2,
  archiveEntryDigests,
  readArchivedRequestByDigest,
  assertSameImmutableRequest,
  assertArchiveFence
} from "./privacy-deletion-store.js";

export const ACCOUNT_DELETION_OUTBOX_OPERATION_TIMEOUT_MS = 20_000;

const BACKEND_CONFLICT_CODE = /^[a-z][a-z0-9_]{2,95}$/;

const MAX_BACKEND_RESPONSE_BYTES = 128 * 1024;

const INITIAL_RETRY_DELAY_MS = 5_000;

const MAX_RETRY_DELAY_MS = 60 * 60 * 1_000;

const MAX_UNRECOGNIZED_CONFLICT_ATTEMPTS = 3;

const EMPTY_BODY_SHA256 =
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

const forwardingOperations = new Set<string>();

type ForwardResult =
  | { kind: "ok"; status: AccountDeletionStatusViewV2; upstreamStatus: number }
  | { kind: "conflict"; status: AccountDeletionStatusV2 | null }
  | { kind: "terminal_conflict"; code: string; status: AccountDeletionStatusV2 | null }
  | { kind: "pending"; retryAfterSeconds: number }
  | { kind: "not_found" };

async function boundedResponseJson(response: Response): Promise<unknown | null> {
  const declared = response.headers.get("content-length");
  if (declared !== null) {
    const length = Number(declared);
    if (!Number.isSafeInteger(length) || length < 0 || length > MAX_BACKEND_RESPONSE_BYTES) {
      void response.body?.cancel("account deletion backend response is too large").catch(() => undefined);
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
      if (total > MAX_BACKEND_RESPONSE_BYTES) {
        await reader.cancel("account deletion backend response is too large");
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
    return JSON.parse(new TextDecoder("utf-8", { fatal: true }).decode(bytes)) as unknown;
  } catch {
    return null;
  } finally {
    try { reader.releaseLock(); } catch { /* already cancelled */ }
  }
}

function backendPrivacyErrorCode(value: unknown): string | null {
  const envelope = objectValue(value);
  const detail = objectValue(envelope?.detail);
  return typeof detail?.code === "string" && BACKEND_CONFLICT_CODE.test(detail.code)
    ? detail.code
    : null;
}

function markOperationRetry(requestId: string, operationId: string, nowEpochMs: number): number {
  return mutateLedger((ledger) => {
    const operation = ledger.requests[requestId]?.outbox.find(
      (candidate) => candidate.operation_id === operationId
    );
    if (!operation) return { value: 1, changed: false };
    if (operationTerminalConflictCode(operation) !== null || operation.paused !== undefined) {
      return { value: 1, changed: false };
    }
    operation.attempts += 1;
    const delay = retryDelayMs(operation.attempts);
    operation.next_attempt_at = new Date(nowEpochMs + delay).toISOString();
    return { value: Math.max(1, Math.ceil(delay / 1_000)), changed: true };
  }, requestId);
}

function pausedOperationView(
  record: DeletionRecord,
  operation: OutboxOperation
): PausedAccountDeletionOutboxOperationV2 {
  const pause = operation.paused!;
  return {
    request_ref: requestDigest(record.request_id),
    operation_ref: operationDigest(operation.operation_id),
    operation_kind: operation.kind,
    pause_id: pause.pause_id,
    reason: pause.reason,
    http_status: pause.http_status,
    cause_code: pause.cause_code,
    paused_at: pause.paused_at
  };
}

function pauseOperation(
  requestId: string,
  operationId: string,
  reason: OutboxPause["reason"],
  httpStatus: number | null,
  causeCode: string,
  nowEpochMs: number
): void {
  const transitioned = mutateLedger<PausedAccountDeletionOutboxOperationV2 | null>((ledger) => {
    const record = ledger.requests[requestId];
    const operation = record?.outbox.find((candidate) => candidate.operation_id === operationId);
    if (!record || !operation) {
      throw new PrivacyDeletionV2Error("integrity", "deletion operation disappeared before pause");
    }
    if (operation.paused !== undefined || operationTerminalConflictCode(operation) !== null) {
      return { value: null, changed: false };
    }
    operation.paused = {
      pause_id: randomBytes(16).toString("hex"),
      reason,
      http_status: httpStatus,
      cause_code: causeCode,
      paused_at: new Date(nowEpochMs).toISOString()
    };
    return { value: pausedOperationView(record, operation), changed: true };
  }, requestId);
  if (transitioned !== null) {
    try {
      console.error(JSON.stringify({
        schema_version: "walksafe.gateway-deletion-operator-alert.v1",
        event_name: "walksafe.gateway.account_deletion_outbox.paused",
        ...transitioned
      }));
    } catch {
      // The encrypted pause remains visible to the local operator CLI.
    }
  }
}

function retryDelayMs(attempts: number): number {
  return Math.min(
    INITIAL_RETRY_DELAY_MS * (2 ** Math.min(attempts - 1, 10)),
    MAX_RETRY_DELAY_MS
  );
}

function retryOrQuarantineUnrecognizedConflict(
  requestId: string,
  operationId: string,
  nowEpochMs: number
):
  | { kind: "pending"; retryAfterSeconds: number }
  | { kind: "terminal_conflict"; code: string; status: AccountDeletionStatusV2 | null } {
  type Result =
    | { kind: "pending"; retryAfterSeconds: number }
    | { kind: "terminal_conflict"; code: string; status: AccountDeletionStatusV2 | null };
  return mutateLedger<Result>((ledger) => {
    const record = ledger.requests[requestId];
    const operation = record?.outbox.find(
      (candidate) => candidate.operation_id === operationId
    );
    if (!record || !operation) {
      throw new PrivacyDeletionV2Error(
        "integrity",
        "unrecognized deletion conflict disappeared before retry"
      );
    }
    const existingCode = operationTerminalConflictCode(operation);
    if (existingCode !== null) {
      return {
        value: {
          kind: "terminal_conflict" as const,
          code: existingCode,
          status: record.backend_status
        },
        changed: false
      };
    }
    operation.attempts += 1;
    if (operation.attempts >= MAX_UNRECOGNIZED_CONFLICT_ATTEMPTS) {
      operation.terminal_conflict_code = "account_deletion_upstream_conflict";
      operation.terminal_at = new Date(nowEpochMs).toISOString();
      const evidence = record.evidence_operations[operationId];
      if (evidence) evidence.status = "TERMINAL_CONFLICT";
      return {
        value: {
          kind: "terminal_conflict" as const,
          code: "account_deletion_upstream_conflict",
          status: record.backend_status
        },
        changed: true
      };
    }
    const delay = retryDelayMs(operation.attempts);
    operation.next_attempt_at = new Date(nowEpochMs + delay).toISOString();
    return {
      value: {
        kind: "pending" as const,
        retryAfterSeconds: Math.max(1, Math.ceil(delay / 1_000))
      },
      changed: true
    };
  }, requestId);
}

function quarantineOperationConflict(
  requestId: string,
  operationId: string,
  code: string,
  nowEpochMs: number
): { code: string; status: AccountDeletionStatusV2 | null } {
  if (!ACCOUNT_DELETION_TERMINAL_CONFLICT_CODE_SET.has(code)) {
    throw new PrivacyDeletionV2Error(
      "integrity",
      "unrecognized deletion conflict cannot be quarantined as permanent"
    );
  }
  return mutateLedger((ledger) => {
    const record = ledger.requests[requestId];
    const operation = record?.outbox.find(
      (candidate) => candidate.operation_id === operationId
    );
    if (!record || !operation) {
      throw new PrivacyDeletionV2Error(
        "integrity",
        "conflicted deletion operation disappeared before quarantine"
      );
    }
    const existingCode = operationTerminalConflictCode(operation);
    if (existingCode !== null) {
      if (existingCode !== code) {
        throw new PrivacyDeletionV2Error(
          "integrity",
          "terminal deletion conflict code changed"
        );
      }
      return {
        value: { code: existingCode, status: record.backend_status },
        changed: false
      };
    }
    operation.terminal_conflict_code = code;
    operation.terminal_at = new Date(nowEpochMs).toISOString();
    const evidence = record.evidence_operations[operationId];
    if (evidence) evidence.status = "TERMINAL_CONFLICT";
    return {
      value: { code, status: record.backend_status },
      changed: true
    };
  }, requestId);
}

function mergeBackendStatus(
  requestId: string,
  operationId: string | null,
  status: AccountDeletionStatusV2
): AccountDeletionStatusV2 {
  return mutateLedger((ledger) => {
    const record = ledger.requests[requestId];
    if (!record) throw new PrivacyDeletionV2Error("not_found", "deletion request disappeared");
    if (
      status.request_id !== record.request_id ||
      status.client_revision !== record.client_revision ||
      status.account_generation !== record.account_generation
    ) throw new PrivacyDeletionV2Error("integrity", "backend deletion identity mismatch");
    const current = record.backend_status;
    if (current && status.revision < current.revision) {
      return { value: current, changed: false };
    }
    if (current && status.revision === current.revision) {
      if (JSON.stringify(current) !== JSON.stringify(status)) {
        throw new PrivacyDeletionV2Error("integrity", "equal backend deletion revision conflicted");
      }
      if (operationId === null) return { value: current, changed: false };
      const before = record.outbox.length;
      record.outbox = record.outbox.filter((operation) => operation.operation_id !== operationId);
      const evidence = record.evidence_operations[operationId];
      const evidenceChanged = evidence !== undefined && evidence.status !== "ACKNOWLEDGED";
      if (evidence) evidence.status = "ACKNOWLEDGED";
      if (evidenceChanged) trimReplayOnlyEvidence(record, operationId);
      return {
        value: current,
        changed: record.outbox.length !== before || evidenceChanged
      };
    }
    if (record.tombstone_id !== null && record.tombstone_id !== status.tombstone_id) {
      throw new PrivacyDeletionV2Error("integrity", "backend tombstone changed");
    }
    record.tombstone_id = status.tombstone_id;
    record.accepted_at = status.accepted_at;
    record.request_receipt_sha256 = status.request_receipt_sha256;
    record.access_bound_digest = boundAccessDigest(record, record.access_pre_digest);
    if (record.access_bound_digest === null) {
      throw new PrivacyDeletionV2Error("integrity", "capability tombstone binding failed");
    }
    record.backend_status = status;
    if (operationId !== null) {
      record.outbox = record.outbox.filter((operation) => operation.operation_id !== operationId);
      const evidence = record.evidence_operations[operationId];
      if (evidence) {
        evidence.status = "ACKNOWLEDGED";
        trimReplayOnlyEvidence(record, operationId);
      }
    }
    return { value: status, changed: true };
  }, requestId);
}

function supersedeEvidenceOperation(
  requestId: string,
  operationId: string
): { status: AccountDeletionStatusV2; superseded: boolean } {
  return mutateLedger<{ status: AccountDeletionStatusV2; superseded: boolean }>((ledger) => {
    const record = ledger.requests[requestId];
    const evidence = record?.evidence_operations[operationId];
    if (!record || !record.backend_status || !evidence) {
      throw new PrivacyDeletionV2Error(
        "integrity",
        "stale evidence operation disappeared before supersession"
      );
    }
    if (evidence.status === "ACKNOWLEDGED") {
      return {
        value: { status: record.backend_status, superseded: false },
        changed: false
      };
    }
    const statusChanged = evidence.status !== "SUPERSEDED_REVISION";
    evidence.status = "SUPERSEDED_REVISION";
    const before = record.outbox.length;
    record.outbox = record.outbox.filter(
      (operation) => operation.operation_id !== operationId
    );
    // S19-41: keep the newest replay-only ids; evict the oldest beyond the cap.
    // S19-41-F1: oldest by submission sequence (operations without one are older still, in
    // key order; the sort is stable), never by object key order.
    trimReplayOnlyEvidence(record, operationId);
    return {
      value: { status: record.backend_status, superseded: true },
      changed: before !== record.outbox.length || statusChanged
    };
  }, requestId);
}

async function forwardOperation(
  requestId: string,
  operationId: string,
  clientRequest: Request,
  fetchImpl?: GatewayFetch,
  nowEpochMs = Date.now()
): Promise<ForwardResult> {
  // The supplied wall-clock anchor precedes preflight I/O as well as the fetch.
  const attemptStarted = performance.now();
  const retryNow = (): number => nowEpochMs + Math.max(0, performance.now() - attemptStarted);
  const inFlightKey = requestId;
  if (forwardingOperations.has(inFlightKey)) {
    return { kind: "pending", retryAfterSeconds: 1 };
  }
  const ledger = readLedger();
  const record = recordFromStores(ledger, requestId);
  const operation = record?.outbox.find((candidate) => candidate.operation_id === operationId);
  if (!record || !operation) {
    return record?.backend_status
      ? { kind: "ok", status: record.backend_status, upstreamStatus: 200 }
      : { kind: "not_found" };
  }
  const terminalConflictCode = operationTerminalConflictCode(operation);
  if (terminalConflictCode !== null) {
    return { kind: "terminal_conflict", code: terminalConflictCode, status: record.backend_status };
  }
  if (record.outbox.some((candidate) => candidate.paused !== undefined)) {
    return { kind: "pending", retryAfterSeconds: 3_600 };
  }
  const remaining = Date.parse(operation.next_attempt_at) - nowEpochMs;
  if (remaining > 0) {
    return { kind: "pending", retryAfterSeconds: Math.max(1, Math.ceil(remaining / 1_000)) };
  }
  if (clientRequest.signal.aborted) return { kind: "pending", retryAfterSeconds: 1 };
  forwardingOperations.add(inFlightKey);
  try {
    if (operation.kind === "REQUEST") {
      try {
        await revokeFieldSessionsForSecurityEvent(
          record.actor_id, "security_incident", nowEpochMs
        );
      } catch {
        return {
          kind: "pending",
          retryAfterSeconds: markOperationRetry(requestId, operationId, retryNow())
        };
      }
    }
    const backendPath = operation.kind === "REQUEST"
      ? "/privacy/account-deletions"
      : `/privacy/account-deletions/${requestId}/device-evidence`;
    let headers: Headers | null;
    try {
      headers = deletionBackendHeaders({
        actorId: record.actor_id,
        accountGeneration: record.account_generation,
        requestId: record.request_id,
        tombstoneId: operation.kind === "REQUEST" ? null : record.tombstone_id,
        accessPreDigest: record.access_pre_digest,
        method: "POST",
        path: backendPath,
        bodySha256: operation.body_sha256
      }, { "content-type": "application/json", accept: "application/json" });
    } catch {
      headers = null;
    }
    if (!headers) {
      pauseOperation(
        requestId, operationId, "credentials", null, "backend_assertion_unavailable", retryNow()
      );
      return { kind: "pending", retryAfterSeconds: 3_600 };
    }
    const response = fetchImpl
      ? await fetchBackend(clientRequest, backendUrl(backendPath), {
          method: "POST", headers, body: operation.body, cache: "no-store"
        }, 15_000, fetchImpl)
      : await fetchBackend(clientRequest, backendUrl(backendPath), {
          method: "POST", headers, body: operation.body, cache: "no-store"
        });
    if (response.status === 200 || response.status === 202) {
      const decoded = await boundedResponseJson(response);
      const status = parseAccountDeletionStatusV2(decoded, requestId);
      if (!status) {
        pauseOperation(
          requestId, operationId, "contract", response.status, "invalid_success_payload", retryNow()
        );
        return {
          kind: "pending",
          retryAfterSeconds: 3_600
        };
      }
      const merged = mergeBackendStatus(requestId, operationId, status);
      return { kind: "ok", status: merged, upstreamStatus: response.status };
    }
    if (response.status === 409) {
      const decoded = await boundedResponseJson(response);
      const legacyStatus = parseAccountDeletionStatusV2(decoded, requestId);
      if (legacyStatus) {
        const merged = mergeBackendStatus(
          requestId,
          operation.kind === "REQUEST" ? operationId : null,
          legacyStatus
        );
        if (operation.kind === "DEVICE_EVIDENCE") {
          const concluded = supersedeEvidenceOperation(requestId, operationId);
          return concluded.superseded
            ? { kind: "conflict", status: concluded.status }
            : { kind: "ok", status: concluded.status, upstreamStatus: 200 };
        }
        return {
          kind: "conflict",
          status: merged
        };
      }
      if (
        operation.kind === "DEVICE_EVIDENCE" &&
        backendPrivacyErrorCode(decoded) === "account_deletion_revision_conflict"
      ) {
        const refreshed = await refreshCanonicalBackendAccountDeletionStatusV2(
          requestId,
          clientRequest,
          fetchImpl
        );
        if (refreshed.kind === "ok") {
          const concluded = supersedeEvidenceOperation(requestId, operationId);
          return concluded.superseded
            ? { kind: "conflict", status: concluded.status }
            : { kind: "ok", status: concluded.status, upstreamStatus: 200 };
        }
        if (refreshed.kind === "not_found") return refreshed;
        if (refreshed.kind === "terminal_conflict") return refreshed;
        return {
          kind: "pending",
          retryAfterSeconds: markOperationRetry(requestId, operationId, retryNow())
        };
      }
      const backendConflictCode = backendPrivacyErrorCode(decoded);
      if (
        backendConflictCode === null ||
        !ACCOUNT_DELETION_TERMINAL_CONFLICT_CODE_SET.has(backendConflictCode)
      ) {
        return retryOrQuarantineUnrecognizedConflict(
          requestId,
          operationId,
          retryNow()
        );
      }
      const concluded = quarantineOperationConflict(
        requestId,
        operationId,
        backendConflictCode,
        retryNow()
      );
      return {
        kind: "terminal_conflict",
        code: concluded.code,
        status: concluded.status
      };
    }
    if (response.status === 400 || response.status === 401 || response.status === 403) {
      void response.body?.cancel("account deletion upstream response not exposed").catch(() => undefined);
      const credentials = response.status === 401 || response.status === 403;
      pauseOperation(
        requestId, operationId,
        credentials ? "credentials" : "contract",
        response.status,
        `backend_http_${response.status}`,
        retryNow()
      );
      return { kind: "pending", retryAfterSeconds: 3_600 };
    }
    if (response.status === 422) {
      const code = backendPrivacyErrorCode(await boundedResponseJson(response));
      if (code !== null && KNOWN_CONTRACT_422_CODES.has(code)) {
        pauseOperation(requestId, operationId, "contract", 422, code, retryNow());
        return { kind: "pending", retryAfterSeconds: 3_600 };
      }
      return {
        kind: "pending",
        retryAfterSeconds: markOperationRetry(requestId, operationId, retryNow())
      };
    }
    void response.body?.cancel("account deletion upstream response not exposed").catch(() => undefined);
    return {
      kind: "pending",
      retryAfterSeconds: markOperationRetry(requestId, operationId, retryNow())
    };
  } finally {
    forwardingOperations.delete(inFlightKey);
  }
}

function pendingOperation(record: DeletionRecord, kind: OutboxOperation["kind"]): OutboxOperation | null {
  return record.outbox.find((operation) => operation.kind === kind) ?? null;
}

export async function forwardAccountDeletionRequestV2(
  requestId: string,
  clientRequest: Request,
  fetchImpl?: GatewayFetch
): Promise<ForwardResult> {
  const record = recordFromStores(readLedger(), requestId);
  if (!record) return { kind: "not_found" };
  const operation = pendingOperation(record, "REQUEST");
  if (!operation) {
    return record.backend_status
      ? { kind: "ok", status: record.backend_status, upstreamStatus: 200 }
      : { kind: "pending", retryAfterSeconds: 1 };
  }
  return forwardOperation(requestId, operation.operation_id, clientRequest, fetchImpl);
}

export async function forwardDeviceDeletionEvidenceV2(
  requestId: string,
  operationId: string,
  clientRequest: Request,
  fetchImpl?: GatewayFetch
): Promise<ForwardResult> {
  return forwardOperation(requestId, operationId, clientRequest, fetchImpl);
}

export async function refreshBackendAccountDeletionStatusV2(
  requestId: string,
  accessSecret: string,
  clientRequest: Request,
  fetchImpl?: GatewayFetch
): Promise<ForwardResult> {
  if (authorizedAccountDeletionStatusV2(requestId, accessSecret) === undefined) {
    return { kind: "not_found" };
  }
  return refreshCanonicalBackendAccountDeletionStatusV2(
    requestId,
    clientRequest,
    fetchImpl
  );
}

async function refreshCanonicalBackendAccountDeletionStatusV2(
  requestId: string,
  clientRequest: Request,
  fetchImpl?: GatewayFetch
): Promise<ForwardResult> {
  const record = recordFromStores(readLedger(), requestId);
  if (!record) return { kind: "not_found" };
  if (record.backend_status === null || record.tombstone_id === null) {
    return forwardAccountDeletionRequestV2(requestId, clientRequest, fetchImpl);
  }
  const backendPath = `/privacy/account-deletions/${requestId}/status`;
  const includeDeviceVerification = clientRequest.headers.get("x-walksafe-deletion-status-view") === "device-verification.v1";
  const headers = deletionBackendHeaders({
    actorId: record.actor_id,
    accountGeneration: record.account_generation,
    requestId: record.request_id,
    tombstoneId: record.tombstone_id,
    accessPreDigest: record.access_pre_digest,
    method: "GET",
    path: backendPath,
    bodySha256: EMPTY_BODY_SHA256
  }, { accept: "application/json", ...(includeDeviceVerification
    ? { "x-walksafe-deletion-status-view": "device-verification.v1" } : {}) });
  if (!headers) throw new PrivacyDeletionV2Error("integrity", "backend status assertion failed");
  const response = fetchImpl
    ? await fetchBackend(clientRequest, backendUrl(backendPath), {
        method: "GET", headers, cache: "no-store"
      }, 15_000, fetchImpl)
    : await fetchBackend(clientRequest, backendUrl(backendPath), {
        method: "GET", headers, cache: "no-store"
      });
  if (response.status === 200) {
    const decoded = await boundedResponseJson(response);
    const status = includeDeviceVerification
      ? parseAccountDeletionStatusViewV2(decoded, requestId)
      : parseAccountDeletionStatusV2(decoded, requestId);
    if (status) {
      const { device_verification, ...canonical } = status as AccountDeletionStatusViewV2;
      const merged = mergeBackendStatus(requestId, null, canonical);
      return { kind: "ok", status: includeDeviceVerification && device_verification && merged.revision === canonical.revision
        ? { ...merged, device_verification } : merged, upstreamStatus: 200 };
    }
  }
  void response.body?.cancel("account deletion status upstream unavailable").catch(() => undefined);
  return { kind: "pending", retryAfterSeconds: 5 };
}

// Best-effort fairness within this process only; restarts and other workers do not share it.
/**
 * S19-36-F1: archived records that may still hold outbox operations. With the archive index
 * only the listed files are read (normally none), so a drain tick no longer decrypts the
 * whole archive. A ledger without an index (archive written before it existed and no write
 * since) falls back to the full scan until its next write creates the index.
 */
function archivedRecordsWithOutbox(snapshot: DeletionLedger): DeletionRecord[] {
  const digests = snapshot.archive_index
    ? snapshot.archive_index.outbox_request_refs
    : archiveEntryDigests("requests");
  const records: DeletionRecord[] = [];
  for (const digest of digests) {
    const record = readArchivedRequestByDigest(digest);
    if (!record) {
      throw new PrivacyDeletionV2Error("integrity", "privacy deletion archive entry disappeared");
    }
    const active = snapshot.requests[record.request_id];
    if (active) {
      assertSameImmutableRequest(active, record);
      continue;
    }
    const fence = snapshot.actors[record.actor_sha256];
    if (fence) assertArchiveFence(record, fence);
    records.push(record);
  }
  return records;
}

let nextOutboxDueIndex = 0;

let outboxDrainTail: Promise<void> = Promise.resolve();

let outboxHasPausedOperations = false;

export function accountDeletionOutboxHasPausedOperations(): boolean {
  return outboxHasPausedOperations;
}

export async function drainAccountDeletionOutboxV2(
  fetchImpl?: GatewayFetch,
  nowEpochMs = Date.now(),
  signal?: AbortSignal
): Promise<{ attempted: number; succeeded: number }> {
  const started = performance.now();
  const previousDrain = outboxDrainTail;
  let releaseDrain!: () => void;
  outboxDrainTail = new Promise<void>((resolve) => { releaseDrain = resolve; });
  await previousDrain;
  try {
  if (signal?.aborted) return { attempted: 0, succeeded: 0 };
  const dueNow = nowEpochMs + Math.floor(Math.max(0, performance.now() - started));
  let snapshot = readLedger();
  if (snapshot.archive_index === undefined && archiveEntryDigests("requests").length > 0) {
    // S19-36-F1: create the archive index once instead of scanning the archive every tick.
    mutateLedger(() => ({ value: null, changed: false }));
    snapshot = readLedger();
  }
  type DueOperation = { requestId: string; operationId: string };
  const dueRequests: Array<{ nextAttemptAt: number; operations: DueOperation[] }> = [];
  let pausedOperations = 0;
  const collectDue = (record: DeletionRecord): void => {
    pausedOperations += record.outbox.filter((operation) => operation.paused !== undefined).length;
    const operations: DueOperation[] = [];
    let firstDueAt: number | null = null;
    for (const operation of record.outbox) {
      const nextAttemptAt = Date.parse(operation.next_attempt_at);
      if (operationTerminalConflictCode(operation) !== null) {
        // Failed evidence cannot advance the Backend revision; a failed request still gates evidence.
        if (operation.kind === "REQUEST") break;
        continue;
      }
      if (operation.paused !== undefined || nextAttemptAt > dueNow) break;
      if (firstDueAt === null) firstDueAt = nextAttemptAt;
      operations.push({ requestId: record.request_id, operationId: operation.operation_id });
    }
    if (firstDueAt !== null) {
      dueRequests.push({
        nextAttemptAt: firstDueAt, operations
      });
    }
  };
  for (const record of Object.values(snapshot.requests)) collectDue(record);
  for (const record of archivedRecordsWithOutbox(snapshot)) collectDue(record);
  outboxHasPausedOperations = pausedOperations > 0;
  dueRequests.sort((left, right) => left.nextAttemptAt - right.nextAttemptAt);
  const selected: DueOperation[] = [];
  for (let round = 0; selected.length < 16; round += 1) {
    let added = false;
    for (let index = 0; index < dueRequests.length && selected.length < 16; index += 1) {
      const request = dueRequests[(nextOutboxDueIndex + index) % dueRequests.length]!;
      const operation = request.operations[round];
      if (!operation) continue;
      selected.push(operation);
      added = true;
    }
    if (!added) break;
  }
  const pending = selected.slice();
  const activeRequests = new Set<string>();
  const blockedRequests = new Set<string>();
  let attempted = 0;
  let succeeded = 0;
  let fatalError: Error | undefined;
  const run = async (): Promise<void> => {
    while (!signal?.aborted && fatalError === undefined) {
      const index = pending.findIndex((operation) =>
        !activeRequests.has(operation.requestId) && !blockedRequests.has(operation.requestId));
      if (index < 0) return;
      const [operation] = pending.splice(index, 1);
      if (!operation) return;
      activeRequests.add(operation.requestId);
      nextOutboxDueIndex = (nextOutboxDueIndex + 1) % dueRequests.length;
      const timeout = AbortSignal.timeout(ACCOUNT_DELETION_OUTBOX_OPERATION_TIMEOUT_MS);
      const synthetic = new Request("http://127.0.0.1/privacy-deletion-outbox", {
        signal: signal ? AbortSignal.any([signal, timeout]) : timeout
      });
      attempted += 1;
      try {
        const result = await forwardOperation(
          operation.requestId,
          operation.operationId,
          synthetic,
          fetchImpl,
          nowEpochMs + Math.floor(Math.max(0, performance.now() - started))
        );
        if (result.kind === "ok") succeeded += 1;
        else blockedRequests.add(operation.requestId);
      } catch (error) {
        blockedRequests.add(operation.requestId);
        if (
          (error instanceof PrivacyDeletionV2Error && error.code === "integrity") ||
          error instanceof ExclusiveFileLockIntegrityError ||
          error instanceof GatewayStateEncryptionError
        ) {
          fatalError = error;
        } else if (!(error instanceof PrivacyDeletionV2Error && error.code === "busy")) {
          process.stderr.write("walksafe account deletion outbox operation failed unexpectedly\n");
        }
      } finally {
        activeRequests.delete(operation.requestId);
      }
    }
  };
  await Promise.all(Array.from({ length: Math.min(3, selected.length) }, () => run()));
  if (fatalError !== undefined) throw fatalError;
  return { attempted, succeeded };
  } finally {
    releaseDrain();
  }
}

export function listPausedAccountDeletionOutboxV2(): PausedAccountDeletionOutboxOperationV2[] {
  const ledger = readLedger();
  const paused: PausedAccountDeletionOutboxOperationV2[] = [];
  const collect = (record: DeletionRecord): void => {
    for (const operation of record.outbox) {
      if (operation.paused !== undefined) paused.push(pausedOperationView(record, operation));
    }
  };
  for (const record of Object.values(ledger.requests)) collect(record);
  for (const record of archivedRecordsWithOutbox(ledger)) collect(record);
  return paused.sort((left, right) =>
    left.paused_at.localeCompare(right.paused_at) ||
    left.request_ref.localeCompare(right.request_ref) ||
    left.operation_ref.localeCompare(right.operation_ref)
  );
}

export function resumePausedAccountDeletionOutboxV2(
  requestRef: string,
  operationRef: string,
  pauseId: string,
  nowEpochMs = Date.now()
): "resumed" | "stale" | "not_found" {
  if (!SHA256.test(requestRef) || !SHA256.test(operationRef) || !PAUSE_ID.test(pauseId)) {
    throw new PrivacyDeletionV2Error("conflict", "invalid deletion outbox lookup reference");
  }
  const ledger = readLedger();
  const record = Object.values(ledger.requests).find(
    (candidate) => requestDigest(candidate.request_id) === requestRef
  ) ?? readArchivedRequestByDigest(requestRef);
  if (!record) return "not_found";
  return mutateLedger<"resumed" | "stale">((current) => {
    const live = current.requests[record.request_id];
    const operation = live?.outbox.find(
      (candidate) => operationDigest(candidate.operation_id) === operationRef
    );
    if (!operation || operation.paused?.pause_id !== pauseId) {
      return { value: "stale", changed: false };
    }
    delete operation.paused;
    operation.attempts = 0;
    operation.next_attempt_at = new Date(nowEpochMs).toISOString();
    return { value: "resumed", changed: true };
  }, record.request_id);
}

