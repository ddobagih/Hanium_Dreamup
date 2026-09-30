import {
  createHash,
  createHmac,
  randomBytes,
  timingSafeEqual
} from "node:crypto";
import {
  chmodSync,
  closeSync,
  constants as fsConstants,
  fstatSync,
  fsyncSync,
  lstatSync,
  mkdirSync,
  opendirSync,
  openSync,
  readFileSync,
  realpathSync,
  renameSync,
  rmSync,
  writeFileSync
} from "node:fs";
import path from "node:path";

import { revokeFieldSessionsForSecurityEvent } from "./auth.js";
import {
  backendUrl,
  deletionBackendHeaders,
  fetchBackend,
  type GatewayFetch
} from "./backend.js";
import { resolveGatewayStateDirectory } from "./config.js";
import {
  createDeletionCapabilityHmac,
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  GatewayStateEncryptionError,
  gatewayDeletionCapabilityKeyId,
  maxGatewayStateEnvelopeBytes,
  syncGatewayStateDirectoryAfterRename
} from "./encrypted-json-store.js";
import {
  ExclusiveFileLockBusyError,
  ExclusiveFileLockIntegrityError,
  withExclusiveFileLock
} from "./exclusive-file-lock.js";
import {
  registerRequiredGatewayStateFile,
  requiredGatewayStateFileWasLost
} from "./state-init-marker.js";

export const ACCOUNT_DELETION_REQUEST_SCHEMA_V2 =
  "walksafe.account-deletion-request.v2" as const;
export const ACCOUNT_DELETION_STATUS_SCHEMA_V2 =
  "walksafe.account-deletion-status.v2" as const;
export const DEVICE_DELETION_EVIDENCE_SCHEMA_V2 =
  "walksafe.device-deletion-evidence.v2" as const;
export const DELETION_ACCESS_SECRET_HEADER =
  "x-walksafe-deletion-access-secret" as const;
export const PRIVACY_DELETION_V2_MAX_BYTES = 4 * 1024 * 1024;
export const ACCOUNT_DELETION_OUTBOX_OPERATION_TIMEOUT_MS = 20_000;

const LEDGER_SCHEMA = "walksafe.gateway-account-deletion-ledger.v2" as const;
const ARCHIVE_VERSION = 1 as const;
export const ARCHIVE_ACTOR_MAX_BYTES = 16 * 1024;
/**
 * S19-36: once the active ledger plaintext passes half of its 4 MiB ceiling, COMPLETED
 * requests (no outbox work, no pending evidence) move to the per-request archive together
 * with their actor fence, so finished deletions stop consuming the active budget.
 */
const ARCHIVE_TRIGGER_BYTES = Math.floor(PRIVACY_DELETION_V2_MAX_BYTES / 2);
let archiveTriggerBytesForTests: number | null = null;
let archiveFaultForTests: "after_archive_write" | null = null;

export function setPrivacyDeletionV2ArchiveForTests(options: {
  triggerBytes: number | null;
  fault?: "after_archive_write" | null;
}): void {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("privacy deletion archive test controls are test-only");
  }
  archiveTriggerBytesForTests = options.triggerBytes;
  archiveFaultForTests = options.fault ?? null;
}
const ARCHIVE_FILE = /^[0-9a-f]{64}\.json$/;
const REQUEST_ID = /^[A-Za-z0-9_-]{16,128}$/;
const OPAQUE_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/;
const ACTOR_ID = /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const CAPABILITY = /^[A-Za-z0-9_-]{43}$/;
const BACKEND_CONFLICT_CODE = /^[a-z][a-z0-9_]{2,95}$/;
const PAUSE_ID = /^[0-9a-f]{32}$/;
const KNOWN_CONTRACT_422_CODES = new Set([
  "account_deletion_request_invalid",
  "account_deletion_binding_invalid",
  "account_deletion_request_mismatch",
  "device_deletion_evidence_digest_invalid"
]);
const MAX_BACKEND_RESPONSE_BYTES = 128 * 1024;
const MAX_OUTBOX_OPERATIONS_PER_REQUEST = 16;
/** Evidence that can still change the Backend (PENDING, ACKNOWLEDGED, TERMINAL_CONFLICT). */
const MAX_EVIDENCE_OPERATIONS_PER_REQUEST = 64;
/**
 * S19-41: revision-superseded evidence is replay-only history kept outside the 64 budget,
 * so repeated revision-conflict recovery cannot exhaust it. The oldest are evicted first;
 * a replay of an evicted id finds no entry and its stale expected revision is refused as
 * a conflict, so it is never queued again.
 */
const MAX_SUPERSEDED_EVIDENCE_OPERATIONS_PER_REQUEST = 256;

type BudgetOperation = { status: unknown; sequence?: unknown; installation_id?: unknown };
/**
 * S19-41 (w25): replay-only history is revision-superseded evidence plus acknowledged
 * evidence that a newer acknowledged evidence of the same installation replaced. Only the
 * newest acknowledged evidence per installation (and pending or terminal-conflict work)
 * counts toward the 64 budget, so repeated FAILED -> resubmit -> ACK cycles on one device
 * never exhaust it. Operations written without `installation_id` keep counting as active.
 */
function replayOnlyEvidenceIds(operations: Record<string, BudgetOperation>): string[] {
  const newestAck = new Map<string, number>();
  for (const operation of Object.values(operations)) {
    if (operation.status === "ACKNOWLEDGED" && typeof operation.installation_id === "string") {
      newestAck.set(
        operation.installation_id,
        Math.max(newestAck.get(operation.installation_id) ?? 0, Number(operation.sequence ?? 0))
      );
    }
  }
  return Object.keys(operations).filter((id) => {
    const operation = operations[id]!;
    if (operation.status === "SUPERSEDED_REVISION") return true;
    return operation.status === "ACKNOWLEDGED" &&
      typeof operation.installation_id === "string" &&
      Number(operation.sequence ?? 0) < (newestAck.get(operation.installation_id) ?? 0);
  });
}
function evidenceBudgetCounts(
  operations: Record<string, BudgetOperation>
): { active: number; superseded: number } {
  const superseded = replayOnlyEvidenceIds(operations).length;
  return { active: Object.keys(operations).length - superseded, superseded };
}
const INITIAL_RETRY_DELAY_MS = 5_000;
const MAX_RETRY_DELAY_MS = 60 * 60 * 1_000;
const MAX_UNRECOGNIZED_CONFLICT_ATTEMPTS = 3;
const EMPTY_BODY_SHA256 =
  "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

/** Frozen Android-facing permanent conflict contract. Revision conflicts are recoverable. */
export const ACCOUNT_DELETION_TERMINAL_CONFLICT_CODES_V2 = Object.freeze([
  "account_deletion_request_conflict",
  "account_deletion_client_revision_invalid",
  "account_generation_tombstoned",
  "account_deletion_installation_inventory_missing",
  "account_deletion_operation_conflict",
  "account_deletion_already_completed",
  "device_deletion_installation_not_targeted",
  "device_deletion_installation_already_terminal",
  "account_deletion_upstream_conflict"
] as const);
const ACCOUNT_DELETION_TERMINAL_CONFLICT_CODE_SET = new Set<string>(
  ACCOUNT_DELETION_TERMINAL_CONFLICT_CODES_V2
);

export const ACCOUNT_DELETION_INVENTORY_V2 = Object.freeze([
  { key: "device_untransmitted_data", dueAfterMs: 24 * 60 * 60 * 1_000 },
  { key: "server_originals", dueAfterMs: 7 * 24 * 60 * 60 * 1_000 },
  { key: "server_quarantine", dueAfterMs: 7 * 24 * 60 * 60 * 1_000 },
  { key: "server_copies", dueAfterMs: 7 * 24 * 60 * 60 * 1_000 },
  { key: "report_records", dueAfterMs: 7 * 24 * 60 * 60 * 1_000 },
  { key: "training_datasets", dueAfterMs: 30 * 24 * 60 * 60 * 1_000 },
  { key: "training_labels", dueAfterMs: 30 * 24 * 60 * 60 * 1_000 },
  { key: "derived_artifacts", dueAfterMs: 30 * 24 * 60 * 60 * 1_000 },
  { key: "backups", dueAfterMs: 35 * 24 * 60 * 60 * 1_000 }
] as const);

export type AccountDeletionItemKeyV2 =
  typeof ACCOUNT_DELETION_INVENTORY_V2[number]["key"];
export type AccountDeletionItemStatusV2 =
  | "PENDING"
  | "IN_PROGRESS"
  | "EXTERNAL_PENDING"
  | "RETRY_WAIT"
  | "LEGAL_HOLD"
  | "FAILED"
  | "COMPLETED"
  | "NOT_APPLICABLE";
export type AccountDeletionOverallStatusV2 =
  | "PROCESSING"
  | "PARTIAL"
  | "RETRY_WAIT"
  | "RESTRICTED"
  | "FAILED"
  | "COMPLETED";

export type AccountDeletionRequestV2 = {
  schema_version: typeof ACCOUNT_DELETION_REQUEST_SCHEMA_V2;
  request_id: string;
  client_revision: number;
  confirmation: "DELETE_MY_ACCOUNT";
};

export type AccountDeletionItemV2 = {
  key: AccountDeletionItemKeyV2;
  status: AccountDeletionItemStatusV2;
  item_revision: number;
  due_at: string;
  updated_at: string;
  evidence_sha256: string | null;
  disposition_basis: string | null;
  retry_after: string | null;
  restriction_reason: string | null;
  legal_hold_review_at: string | null;
  legal_hold_contact: string | null;
  terminal_at: string | null;
};

export type AccountDeletionStatusV2 = {
  schema_version: typeof ACCOUNT_DELETION_STATUS_SCHEMA_V2;
  request_id: string;
  client_revision: number;
  revision: number;
  accepted_at: string;
  updated_at: string;
  account_generation: number;
  tombstone_id: string;
  request_receipt_sha256: string;
  overall_status: AccountDeletionOverallStatusV2;
  items: AccountDeletionItemV2[];
  completion_receipt_sha256: string | null;
};

export type DeviceDeletionEvidenceResultV2 =
  | "DELETED"
  | "NOT_FOUND"
  | "FAILED";

export type DeviceDeletionEvidenceV2 = {
  schema_version: typeof DEVICE_DELETION_EVIDENCE_SCHEMA_V2;
  request_id: string;
  tombstone_id: string;
  request_receipt_sha256: string;
  installation_id: string;
  evidence_id: string;
  client_revision: number;
  expected_status_revision: number;
  item: "device_untransmitted_data";
  result: DeviceDeletionEvidenceResultV2;
  completed_at: string;
  evidence_sha256: string;
};

type OutboxOperation = {
  operation_id: string;
  kind: "REQUEST" | "DEVICE_EVIDENCE";
  body: string;
  body_sha256: string;
  attempts: number;
  next_attempt_at: string;
  paused?: OutboxPause;
  terminal_conflict_code?: string;
  terminal_at?: string;
};

type OutboxPause = {
  pause_id: string;
  reason: "credentials" | "contract";
  http_status: number | null;
  cause_code: string;
  paused_at: string;
};

export type PausedAccountDeletionOutboxOperationV2 = Readonly<{
  request_ref: string;
  operation_ref: string;
  operation_kind: OutboxOperation["kind"];
  pause_id: string;
  reason: OutboxPause["reason"];
  http_status: number | null;
  cause_code: string;
  paused_at: string;
}>;

type EvidenceOperation = {
  body_sha256: string;
  evidence_sha256: string;
  status: "PENDING" | "ACKNOWLEDGED" | "SUPERSEDED_REVISION" | "TERMINAL_CONFLICT";
  /**
   * S19-41-F1 (w22 review): per-request submission order. Object key order is not
   * submission order after a JSON round trip (all-digit ids sort first), so eviction of the
   * oldest superseded ids uses this. Absent on operations written before it existed.
   */
  sequence?: number;
  /** S19-41 (w25): the submitting installation; absent on operations written before it. */
  installation_id?: string;
};
/** Evicts the oldest replay-only history (by submission sequence) beyond its cap. */
function trimReplayOnlyEvidence(
  record: { evidence_operations: Record<string, EvidenceOperation> },
  protectedId: string | null
): void {
  const history = replayOnlyEvidenceIds(record.evidence_operations).sort((left, right) =>
    (record.evidence_operations[left]?.sequence ?? 0) -
    (record.evidence_operations[right]?.sequence ?? 0)
  );
  for (
    let excess = history.length - MAX_SUPERSEDED_EVIDENCE_OPERATIONS_PER_REQUEST;
    excess > 0;
    excess -= 1
  ) {
    const oldest = history.shift();
    if (oldest === undefined || oldest === protectedId) break;
    delete record.evidence_operations[oldest];
  }
}

type DeletionRecord = {
  request_id: string;
  actor_id: string;
  actor_sha256: string;
  account_generation: number;
  client_revision: number;
  request_body: string;
  request_body_sha256: string;
  gateway_fence_id: string;
  // Immutable Backend receipt binding, including after legacy capability upgrade.
  access_pre_digest: string;
  access_bound_digest: string | null;
  capability_key?: { version: 1; key_id: string; verifier_sha256: string };
  tombstone_id: string | null;
  accepted_at: string | null;
  request_receipt_sha256: string | null;
  backend_status: AccountDeletionStatusV2 | null;
  outbox: OutboxOperation[];
  evidence_operations: Record<string, EvidenceOperation>;
};

type ActorFence = {
  account_generation: number;
  request_id: string;
  gateway_fence_id: string;
};

/**
 * S19-36 (w22 review): what the active ledger keeps about archived requests. Present only
 * once something was archived, so a ledger that never archived keeps its earlier bytes.
 */
type ArchiveIndex = {
  /**
   * S19-36-F2: registration of every archived actor fence file, as the sorted concatenation
   * of the first 16 hex digits of each actor digest (8 bytes per archived deletion, so the
   * active ledger stays small and cheap to read). A listed actor whose archive file is
   * missing fails closed; an unlisted actor has no archived fence and needs no file read.
   */
  actor_prefixes: string;
  /**
   * S19-36-F1: request digests of archived records that still hold outbox operations
   * (normally empty). The outbox drain and the paused list read only these archive files.
   * It is a superset: a digest is added before the archive write and removed after it.
   */
  outbox_request_refs: string[];
};

type DeletionLedger = {
  schema_version: typeof LEDGER_SCHEMA;
  revision: number;
  actors: Record<string, ActorFence>;
  requests: Record<string, DeletionRecord>;
  archive_index?: ArchiveIndex;
};

type ArchivedRequest = { version: typeof ARCHIVE_VERSION; record: DeletionRecord };
type ArchivedActor = {
  version: typeof ARCHIVE_VERSION;
  actor_sha256: string;
  fence: ActorFence;
};

export class PrivacyDeletionV2Error extends Error {
  constructor(
    readonly code: "busy" | "integrity" | "conflict" | "not_found" | "inactive",
    message: string
  ) {
    super(message);
    this.name = "PrivacyDeletionV2Error";
  }
}

const forwardingOperations = new Set<string>();

function objectValue(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function exactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length &&
    actual.every((key, index) => key === wanted[index]);
}

function safePositiveInteger(value: unknown): value is number {
  return Number.isSafeInteger(value) && (value as number) > 0;
}

function validInstant(value: unknown): value is string {
  if (typeof value !== "string" || value.length < 20 || value.length > 40) return false;
  if (!/(?:Z|[+-][0-9]{2}:[0-9]{2})$/.test(value)) return false;
  return Number.isFinite(Date.parse(value));
}

function validEvidenceInstant(value: unknown): value is string {
  if (
    typeof value !== "string" ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/.test(value)
  ) return false;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) &&
    new Date(parsed).toISOString() === `${value.slice(0, -1)}.000Z`;
}

function nullableString(value: unknown, pattern?: RegExp): value is string | null {
  return value === null || (
    typeof value === "string" &&
    value.length > 0 &&
    value.length <= 2_000 &&
    (pattern === undefined || pattern.test(value))
  );
}

function sha256(value: string | Buffer): string {
  return createHash("sha256").update(value).digest("hex");
}

function actorDigest(actorId: string): string {
  return sha256(`walksafe/deletion-actor/v2\0${actorId}`);
}

function requestDigest(requestId: string): string {
  return sha256(`walksafe/deletion-archive-request/v1\0${requestId}`);
}

function operationDigest(operationId: string): string {
  return sha256(`walksafe/deletion-outbox-operation/v1\0${operationId}`);
}

function capabilityBytes(secret: string): Buffer | null {
  if (!CAPABILITY.test(secret)) return null;
  const decoded = Buffer.from(secret, "base64url");
  return decoded.length === 32 && decoded.toString("base64url") === secret
    ? decoded
    : null;
}

function capabilityHmacKey(): string {
  const secret = process.env.WALKSAFE_GATEWAY_SESSION_SECRET?.trim() ?? "";
  if (secret.length < 32) {
    throw new PrivacyDeletionV2Error(
      "integrity",
      "account deletion capability HMAC key is unavailable"
    );
  }
  return secret;
}

function preAccessDigest(
  actorSha256: string,
  accountGeneration: number,
  requestId: string,
  secret: string,
  keyId?: string
): string | null {
  const bytes = capabilityBytes(secret);
  if (!bytes) return null;
  try {
    const hmac = keyId === undefined
      ? createHmac("sha256", capabilityHmacKey())
      : createDeletionCapabilityHmac(keyId);
    return hmac
      .update("walksafe/delete-access/pre/v2\0", "utf8")
      .update(actorSha256, "utf8")
      .update("\0", "utf8")
      .update(String(accountGeneration), "utf8")
      .update("\0", "utf8")
      .update(requestId, "utf8")
      .update("\0", "utf8")
      .update(bytes)
      .digest("hex");
  } finally {
    bytes.fill(0);
  }
}

function boundAccessDigest(record: DeletionRecord, preDigest: string): string | null {
  if (record.tombstone_id === null) return null;
  const hmac = record.capability_key === undefined
    ? createHmac("sha256", capabilityHmacKey())
    : createDeletionCapabilityHmac(record.capability_key.key_id);
  return hmac
    .update("walksafe/delete-access/bound/v2\0", "utf8")
    .update(record.actor_sha256, "utf8")
    .update("\0", "utf8")
    .update(String(record.account_generation), "utf8")
    .update("\0", "utf8")
    .update(record.request_id, "utf8")
    .update("\0", "utf8")
    .update(record.tombstone_id, "utf8")
    .update("\0", "utf8")
    .update(preDigest, "utf8")
    .digest("hex");
}

function equalHex(left: string, right: string): boolean {
  return SHA256.test(left) && SHA256.test(right) && timingSafeEqual(
    Buffer.from(left, "hex"),
    Buffer.from(right, "hex")
  );
}

function capabilityMatches(record: DeletionRecord, secret: string): boolean {
  const pre = preAccessDigest(
    record.actor_sha256,
    record.account_generation,
    record.request_id,
    secret,
    record.capability_key?.key_id
  );
  if (!pre) return false;
  const verifier = record.capability_key?.verifier_sha256 ?? record.access_pre_digest;
  if (!equalHex(verifier, pre)) return false;
  if (record.access_bound_digest === null) return true;
  const bound = boundAccessDigest(record, record.access_pre_digest);
  return bound !== null && equalHex(record.access_bound_digest, bound);
}

/** Only call after authenticating the presented capability with its recorded key. */
function refreshCapabilityKey(record: DeletionRecord, secret: string): boolean {
  const keyId = gatewayDeletionCapabilityKeyId();
  if (record.capability_key?.key_id === keyId) return false;
  const verifier = preAccessDigest(
    record.actor_sha256, record.account_generation, record.request_id, secret, keyId
  );
  if (!verifier) throw new PrivacyDeletionV2Error("integrity", "capability upgrade failed");
  record.capability_key = { version: 1, key_id: keyId, verifier_sha256: verifier };
  record.access_bound_digest = boundAccessDigest(record, record.access_pre_digest);
  return true;
}

function validCapabilityKey(value: unknown): boolean {
  const key = objectValue(value);
  if (!key || !exactKeys(key, ["version", "key_id", "verifier_sha256"]) ||
      key.version !== 1 || typeof key.key_id !== "string" ||
      typeof key.verifier_sha256 !== "string" || !SHA256.test(key.verifier_sha256)) {
    return false;
  }
  try {
    return gatewayDeletionCapabilityKeyId(key.key_id) === key.key_id;
  } catch {
    return false;
  }
}

export function validDeletionAccessSecretV2(secret: string): boolean {
  const bytes = capabilityBytes(secret);
  if (!bytes) return false;
  bytes.fill(0);
  return true;
}

export function parseAccountDeletionRequestV2(
  value: unknown
): AccountDeletionRequestV2 | null {
  const input = objectValue(value);
  if (
    !input ||
    !exactKeys(input, ["schema_version", "request_id", "client_revision", "confirmation"]) ||
    input.schema_version !== ACCOUNT_DELETION_REQUEST_SCHEMA_V2 ||
    typeof input.request_id !== "string" ||
    !REQUEST_ID.test(input.request_id) ||
    !safePositiveInteger(input.client_revision) ||
    input.confirmation !== "DELETE_MY_ACCOUNT"
  ) return null;
  return {
    schema_version: ACCOUNT_DELETION_REQUEST_SCHEMA_V2,
    request_id: input.request_id,
    client_revision: input.client_revision,
    confirmation: "DELETE_MY_ACCOUNT"
  };
}

export function accountDeletionRequestBodyV2(input: AccountDeletionRequestV2): string {
  return JSON.stringify({
    schema_version: ACCOUNT_DELETION_REQUEST_SCHEMA_V2,
    request_id: input.request_id,
    client_revision: input.client_revision,
    confirmation: "DELETE_MY_ACCOUNT"
  });
}

function evidenceStatement(input: Omit<DeviceDeletionEvidenceV2, "evidence_sha256">): string {
  return JSON.stringify({
    schema_version: DEVICE_DELETION_EVIDENCE_SCHEMA_V2,
    request_id: input.request_id,
    tombstone_id: input.tombstone_id,
    request_receipt_sha256: input.request_receipt_sha256,
    installation_id: input.installation_id,
    evidence_id: input.evidence_id,
    client_revision: input.client_revision,
    expected_status_revision: input.expected_status_revision,
    item: "device_untransmitted_data",
    result: input.result,
    completed_at: input.completed_at
  });
}

export function deviceDeletionEvidenceSha256V2(
  input: Omit<DeviceDeletionEvidenceV2, "evidence_sha256">
): string {
  return sha256(`walksafe.device-deletion-evidence.v2\0${evidenceStatement(input)}`);
}

export function deviceDeletionEvidenceBodyV2(input: DeviceDeletionEvidenceV2): string {
  return JSON.stringify({
    schema_version: DEVICE_DELETION_EVIDENCE_SCHEMA_V2,
    request_id: input.request_id,
    tombstone_id: input.tombstone_id,
    request_receipt_sha256: input.request_receipt_sha256,
    installation_id: input.installation_id,
    evidence_id: input.evidence_id,
    client_revision: input.client_revision,
    expected_status_revision: input.expected_status_revision,
    item: "device_untransmitted_data",
    result: input.result,
    completed_at: input.completed_at,
    evidence_sha256: input.evidence_sha256
  });
}

export function parseDeviceDeletionEvidenceV2(
  value: unknown
): DeviceDeletionEvidenceV2 | null {
  const input = objectValue(value);
  if (
    !input ||
    !exactKeys(input, [
      "schema_version", "request_id", "tombstone_id", "request_receipt_sha256",
      "installation_id", "evidence_id", "client_revision", "expected_status_revision",
      "item", "result", "completed_at", "evidence_sha256"
    ]) ||
    input.schema_version !== DEVICE_DELETION_EVIDENCE_SCHEMA_V2 ||
    typeof input.request_id !== "string" || !REQUEST_ID.test(input.request_id) ||
    typeof input.tombstone_id !== "string" || !OPAQUE_ID.test(input.tombstone_id) ||
    typeof input.request_receipt_sha256 !== "string" || !SHA256.test(input.request_receipt_sha256) ||
    typeof input.installation_id !== "string" || !OPAQUE_ID.test(input.installation_id) ||
    typeof input.evidence_id !== "string" || !OPAQUE_ID.test(input.evidence_id) ||
    !safePositiveInteger(input.client_revision) ||
    !safePositiveInteger(input.expected_status_revision) ||
    input.item !== "device_untransmitted_data" ||
    !["DELETED", "NOT_FOUND", "FAILED"].includes(String(input.result)) ||
    !validEvidenceInstant(input.completed_at) ||
    typeof input.evidence_sha256 !== "string" || !SHA256.test(input.evidence_sha256)
  ) return null;
  const parsed: DeviceDeletionEvidenceV2 = {
    schema_version: DEVICE_DELETION_EVIDENCE_SCHEMA_V2,
    request_id: input.request_id,
    tombstone_id: input.tombstone_id,
    request_receipt_sha256: input.request_receipt_sha256,
    installation_id: input.installation_id,
    evidence_id: input.evidence_id,
    client_revision: input.client_revision,
    expected_status_revision: input.expected_status_revision,
    item: "device_untransmitted_data",
    result: input.result as DeviceDeletionEvidenceResultV2,
    completed_at: input.completed_at,
    evidence_sha256: input.evidence_sha256
  };
  const { evidence_sha256: _ignored, ...statement } = parsed;
  return equalHex(parsed.evidence_sha256, deviceDeletionEvidenceSha256V2(statement))
    ? parsed
    : null;
}

function derivedOverall(items: readonly AccountDeletionItemV2[]): AccountDeletionOverallStatusV2 {
  if (items.every((item) => item.status === "COMPLETED" || item.status === "NOT_APPLICABLE")) {
    return "COMPLETED";
  }
  if (items.some((item) => item.status === "LEGAL_HOLD")) return "RESTRICTED";
  if (items.some((item) => item.status === "FAILED")) return "FAILED";
  if (items.some((item) => item.status === "RETRY_WAIT")) return "RETRY_WAIT";
  if (items.some((item) =>
    item.status === "EXTERNAL_PENDING" ||
    item.status === "COMPLETED" ||
    item.status === "NOT_APPLICABLE"
  )) return "PARTIAL";
  return "PROCESSING";
}

function parseStatusItem(
  value: unknown,
  definition: typeof ACCOUNT_DELETION_INVENTORY_V2[number],
  acceptedAtMs: number
): AccountDeletionItemV2 | null {
  const item = objectValue(value);
  if (
    !item ||
    !exactKeys(item, [
      "key", "status", "item_revision", "due_at", "updated_at", "evidence_sha256",
      "disposition_basis", "retry_after", "restriction_reason", "legal_hold_review_at", "legal_hold_contact",
      "terminal_at"
    ]) ||
    item.key !== definition.key ||
    typeof item.status !== "string" ||
    ![
      "PENDING", "IN_PROGRESS", "EXTERNAL_PENDING", "RETRY_WAIT", "LEGAL_HOLD",
      "FAILED", "COMPLETED", "NOT_APPLICABLE"
    ].includes(item.status) ||
    !safePositiveInteger(item.item_revision) ||
    !validInstant(item.due_at) ||
    Date.parse(item.due_at) > acceptedAtMs + definition.dueAfterMs ||
    !validInstant(item.updated_at) ||
    !nullableString(item.evidence_sha256, SHA256) ||
    !nullableString(item.disposition_basis) ||
    !nullableString(item.retry_after) ||
    !nullableString(item.restriction_reason) ||
    !nullableString(item.legal_hold_review_at) ||
    !nullableString(item.legal_hold_contact) ||
    !nullableString(item.terminal_at)
  ) return null;
  const status = item.status as AccountDeletionItemStatusV2;
  const terminal = ["COMPLETED", "NOT_APPLICABLE"].includes(status);
  if (
    (terminal && (
      typeof item.evidence_sha256 !== "string" ||
      !validInstant(item.terminal_at)
    )) ||
    (!terminal && item.terminal_at !== null)
  ) return null;
  if (
    (status === "NOT_APPLICABLE" && typeof item.disposition_basis !== "string") ||
    (status !== "NOT_APPLICABLE" && item.disposition_basis !== null)
  ) return null;
  if (
    (status === "RETRY_WAIT" && !validInstant(item.retry_after)) ||
    (status !== "RETRY_WAIT" && item.retry_after !== null)
  ) return null;
  if (status === "LEGAL_HOLD") {
    if (
      typeof item.restriction_reason !== "string" ||
      !validInstant(item.legal_hold_review_at) ||
      typeof item.legal_hold_contact !== "string"
    ) return null;
  } else if (
    item.restriction_reason !== null ||
    item.legal_hold_review_at !== null ||
    item.legal_hold_contact !== null
  ) return null;
  return item as unknown as AccountDeletionItemV2;
}

export function parseAccountDeletionStatusV2(
  value: unknown,
  expectedRequestId?: string
): AccountDeletionStatusV2 | null {
  const status = objectValue(value);
  if (
    !status ||
    !exactKeys(status, [
      "schema_version", "request_id", "client_revision", "revision", "accepted_at",
      "updated_at", "account_generation", "tombstone_id", "request_receipt_sha256",
      "overall_status", "items", "completion_receipt_sha256"
    ]) ||
    status.schema_version !== ACCOUNT_DELETION_STATUS_SCHEMA_V2 ||
    typeof status.request_id !== "string" || !REQUEST_ID.test(status.request_id) ||
    (expectedRequestId !== undefined && status.request_id !== expectedRequestId) ||
    !safePositiveInteger(status.client_revision) ||
    !safePositiveInteger(status.revision) ||
    !validInstant(status.accepted_at) ||
    !validInstant(status.updated_at) ||
    !safePositiveInteger(status.account_generation) ||
    typeof status.tombstone_id !== "string" || !OPAQUE_ID.test(status.tombstone_id) ||
    typeof status.request_receipt_sha256 !== "string" || !SHA256.test(status.request_receipt_sha256) ||
    typeof status.overall_status !== "string" ||
    !["PROCESSING", "PARTIAL", "RETRY_WAIT", "RESTRICTED", "FAILED", "COMPLETED"]
      .includes(status.overall_status) ||
    !Array.isArray(status.items) ||
    status.items.length !== ACCOUNT_DELETION_INVENTORY_V2.length ||
    !nullableString(status.completion_receipt_sha256, SHA256)
  ) return null;
  const acceptedAtMs = Date.parse(status.accepted_at);
  const rawItems = status.items as unknown[];
  const items = ACCOUNT_DELETION_INVENTORY_V2.map((definition, index) =>
    parseStatusItem(rawItems[index], definition, acceptedAtMs)
  );
  if (items.some((item) => item === null)) return null;
  const parsedItems = items as AccountDeletionItemV2[];
  const overall = derivedOverall(parsedItems);
  if (status.overall_status !== overall) return null;
  if ((overall === "COMPLETED") !== (typeof status.completion_receipt_sha256 === "string")) {
    return null;
  }
  return {
    ...(status as unknown as AccountDeletionStatusV2),
    items: parsedItems
  };
}

function emptyLedger(): DeletionLedger {
  return { schema_version: LEDGER_SCHEMA, revision: 0, actors: {}, requests: {} };
}

function stateDirectory(): string {
  return path.join(resolveGatewayStateDirectory(), "privacy-deletion-v2");
}

function ledgerPath(): string {
  return path.join(stateDirectory(), "ledger.json");
}

function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || uid === process.getuid();
}

function ensureStateDirectory(): void {
  const directory = stateDirectory();
  try {
    mkdirSync(directory, { recursive: true, mode: 0o700 });
    chmodSync(directory, 0o700);
    const metadata = lstatSync(directory);
    if (
      !metadata.isDirectory() || metadata.isSymbolicLink() ||
      realpathSync(directory) !== directory ||
      !ownedByCurrentProcess(metadata.uid) ||
      (metadata.mode & 0o077) !== 0
    ) throw new Error();
  } catch {
    throw new PrivacyDeletionV2Error("integrity", "privacy deletion v2 directory is unsafe");
  }
}

function readSecureLedgerFile(): string {
  let descriptor: number | null = null;
  try {
    descriptor = openSync(ledgerPath(), fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() || metadata.nlink !== 1 ||
      !ownedByCurrentProcess(metadata.uid) ||
      (metadata.mode & 0o777) !== 0o600 ||
      metadata.size < 2 ||
      metadata.size > maxGatewayStateEnvelopeBytes(PRIVACY_DELETION_V2_MAX_BYTES)
    ) throw new Error();
    const raw = readFileSync(descriptor, "utf8");
    if (Buffer.byteLength(raw, "utf8") !== metadata.size) throw new Error();
    return raw;
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

function archiveDirectory(kind: "requests" | "actors", create: boolean): string | null {
  const root = path.join(stateDirectory(), "archive");
  const directory = path.join(root, kind);
  for (const current of [root, directory]) {
    try {
      if (create) {
        try {
          mkdirSync(current, { mode: 0o700 });
        } catch (error) {
          if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
        }
      }
      const metadata = lstatSync(current);
      if (
        !metadata.isDirectory() || metadata.isSymbolicLink() ||
        realpathSync(current) !== current ||
        !ownedByCurrentProcess(metadata.uid) ||
        (metadata.mode & 0o077) !== 0
      ) throw new Error();
    } catch (error) {
      if (!create && (error as NodeJS.ErrnoException).code === "ENOENT") return null;
      throw new PrivacyDeletionV2Error("integrity", "privacy deletion archive directory is unsafe");
    }
  }
  return directory;
}

let archiveReadsForTests = 0;

/** S19-36-F1: number of archive file reads so far (test-only regression bound). */
export function privacyDeletionV2ArchiveReadCountForTests(): number {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("privacy deletion archive read count is test-only");
  }
  return archiveReadsForTests;
}

function readArchiveJson(
  kind: "requests" | "actors",
  digest: string,
  maxPlaintextBytes: number
): unknown | null {
  archiveReadsForTests += 1;
  const directory = archiveDirectory(kind, false);
  if (directory === null) return null;
  const basename = `${digest}.json`;
  let descriptor: number | null = null;
  try {
    descriptor = openSync(path.join(directory, basename), fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() || metadata.nlink !== 1 ||
      !ownedByCurrentProcess(metadata.uid) ||
      (metadata.mode & 0o777) !== 0o600 ||
      metadata.size < 2 ||
      metadata.size > maxGatewayStateEnvelopeBytes(maxPlaintextBytes)
    ) throw new Error();
    const raw = readFileSync(descriptor, "utf8");
    if (Buffer.byteLength(raw, "utf8") !== metadata.size) throw new Error();
    return decryptGatewayStateJson(
      { kind: "privacy-deletion-v2", recordId: basename }, raw, maxPlaintextBytes
    ).value;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    throw new PrivacyDeletionV2Error("integrity", "privacy deletion archive cannot be read");
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

function readArchivedActor(digest: string): ActorFence | null {
  const value = readArchiveJson("actors", digest, ARCHIVE_ACTOR_MAX_BYTES);
  if (value === null) return null;
  const actor = objectValue(value);
  const fence = objectValue(actor?.fence);
  if (
    !actor || !exactKeys(actor, ["version", "actor_sha256", "fence"]) ||
    actor.version !== ARCHIVE_VERSION || actor.actor_sha256 !== digest ||
    !SHA256.test(digest) || !fence ||
    !exactKeys(fence, ["account_generation", "request_id", "gateway_fence_id"]) ||
    !safePositiveInteger(fence.account_generation) ||
    typeof fence.request_id !== "string" || !REQUEST_ID.test(fence.request_id) ||
    typeof fence.gateway_fence_id !== "string" || !OPAQUE_ID.test(fence.gateway_fence_id)
  ) throw new PrivacyDeletionV2Error("integrity", "privacy deletion archive actor is invalid");
  return fence as ActorFence;
}

function sameFence(left: ActorFence, right: ActorFence): boolean {
  return left.account_generation === right.account_generation &&
    left.request_id === right.request_id &&
    left.gateway_fence_id === right.gateway_fence_id;
}

function assertArchiveFence(record: DeletionRecord, fence: ActorFence | null): asserts fence is ActorFence {
  if (
    fence === null || fence.account_generation !== record.account_generation ||
    fence.request_id !== record.request_id || fence.gateway_fence_id !== record.gateway_fence_id
  ) throw new PrivacyDeletionV2Error("integrity", "privacy deletion archive fence is missing or mismatched");
}

function readArchivedRequestByDigest(digest: string): DeletionRecord | null {
  const value = readArchiveJson("requests", digest, PRIVACY_DELETION_V2_MAX_BYTES);
  if (value === null) return null;
  const archive = objectValue(value);
  const record = objectValue(archive?.record);
  if (
    !archive || !exactKeys(archive, ["version", "record"]) ||
    archive.version !== ARCHIVE_VERSION || !record ||
    typeof record.request_id !== "string" ||
    requestDigest(record.request_id) !== digest ||
    !validRecord(record, record.request_id)
  ) throw new PrivacyDeletionV2Error("integrity", "privacy deletion archive request is invalid");
  assertArchiveFence(record, readArchivedActor(record.actor_sha256));
  return record;
}

function assertSameImmutableRequest(active: DeletionRecord, archived: DeletionRecord): void {
  if (
    active.request_id !== archived.request_id ||
    active.actor_id !== archived.actor_id ||
    active.actor_sha256 !== archived.actor_sha256 ||
    active.account_generation !== archived.account_generation ||
    active.client_revision !== archived.client_revision ||
    active.request_body !== archived.request_body ||
    active.request_body_sha256 !== archived.request_body_sha256 ||
    active.gateway_fence_id !== archived.gateway_fence_id ||
    active.access_pre_digest !== archived.access_pre_digest
  ) throw new PrivacyDeletionV2Error("integrity", "active and archived deletion identities differ");
}

function recordFromStores(ledger: DeletionLedger, requestId: string): DeletionRecord | undefined {
  const active = ledger.requests[requestId];
  const archived = readArchivedRequestByDigest(requestDigest(requestId));
  if (archived) {
    const activeFence = ledger.actors[archived.actor_sha256];
    if (activeFence) {
      assertArchiveFence(archived, activeFence);
    }
    if (active) assertSameImmutableRequest(active, archived);
  }
  return active ?? archived ?? undefined;
}

function fenceFromStores(ledger: DeletionLedger, digest: string): ActorFence | undefined {
  const active = ledger.actors[digest];
  const registered = ledger.archive_index
    ? findArchiveActorPrefix(ledger.archive_index.actor_prefixes, digest).found
    : null;
  if (registered === false) {
    // With an index every archived fence is registered; an unlisted digest has no archived
    // fence (a crash between the archive write and the ledger write keeps it active).
    return active;
  }
  const archived = readArchivedActor(digest);
  if (archived) {
    const record = readArchivedRequestByDigest(requestDigest(archived.request_id));
    if (!record || record.actor_sha256 !== digest) {
      throw new PrivacyDeletionV2Error("integrity", "privacy deletion archive actor has no request");
    }
    assertArchiveFence(record, archived);
    if (active && !sameFence(active, archived)) {
      throw new PrivacyDeletionV2Error("integrity", "active and archived deletion fences differ");
    }
  } else if (registered === true && !active) {
    // S19-36-F2: a registered archived fence file is lost (deleted directory, partial
    // restore). Never read that as "no deletion": fail closed until it is restored.
    throw new PrivacyDeletionV2Error("integrity", "archived deletion fence is missing");
  }
  return active ?? archived ?? undefined;
}

function archiveIndex(ledger: DeletionLedger): ArchiveIndex {
  ledger.archive_index ??= { actor_prefixes: "", outbox_request_refs: [] };
  return ledger.archive_index;
}

function registerArchivedActor(ledger: DeletionLedger, digest: string): void {
  const index = archiveIndex(ledger);
  const { found, slot } = findArchiveActorPrefix(index.actor_prefixes, digest);
  if (found) return;
  const offset = slot * ARCHIVE_ACTOR_PREFIX_HEX;
  index.actor_prefixes = index.actor_prefixes.slice(0, offset) + archiveActorPrefix(digest) +
    index.actor_prefixes.slice(offset);
}

function setOutboxRef(ledger: DeletionLedger, ref: string, present: boolean): boolean {
  const index = archiveIndex(ledger);
  const listed = index.outbox_request_refs.includes(ref);
  if (listed === present) return false;
  index.outbox_request_refs = present
    ? [...index.outbox_request_refs, ref].sort()
    : index.outbox_request_refs.filter((candidate) => candidate !== ref);
  return true;
}

function archiveEntryDigests(kind: "requests" | "actors"): string[] {
  const directoryPath = archiveDirectory(kind, false);
  if (directoryPath === null) return [];
  const digests: string[] = [];
  const directory = opendirSync(directoryPath);
  try {
    let entry;
    while ((entry = directory.readSync()) !== null) {
      if (entry.name.startsWith(".") && entry.name.endsWith(".tmp")) continue;
      if (!ARCHIVE_FILE.test(entry.name) || !entry.isFile()) {
        throw new PrivacyDeletionV2Error("integrity", "privacy deletion archive entry is invalid");
      }
      digests.push(entry.name.slice(0, -5));
    }
  } finally {
    directory.closeSync();
  }
  return digests.sort();
}

/**
 * S19-36 (w22 review): one-time migration for an archive written before the index existed.
 * Under the ledger lock it lists every archived fence and every archived record that still
 * has outbox work. Returns true when the index was created and must be written.
 */
function ensureArchiveIndex(ledger: DeletionLedger): boolean {
  if (ledger.archive_index !== undefined) return false;
  const actorDigests = archiveEntryDigests("actors");
  const requestDigests = archiveEntryDigests("requests");
  if (actorDigests.length === 0 && requestDigests.length === 0) return false;
  const index: ArchiveIndex = { actor_prefixes: "", outbox_request_refs: [] };
  for (const digest of actorDigests) {
    if (ledger.actors[digest]) continue;
    // Verifies the archived fence against its archived request before registering it.
    if (!fenceFromStores(ledger, digest)) continue;
    const { found, slot } = findArchiveActorPrefix(index.actor_prefixes, digest);
    if (found) continue;
    const offset = slot * ARCHIVE_ACTOR_PREFIX_HEX;
    index.actor_prefixes = index.actor_prefixes.slice(0, offset) + archiveActorPrefix(digest) +
      index.actor_prefixes.slice(offset);
  }
  for (const digest of requestDigests) {
    const record = readArchivedRequestByDigest(digest);
    if (!record) {
      throw new PrivacyDeletionV2Error("integrity", "privacy deletion archive entry disappeared");
    }
    if (!ledger.requests[record.request_id] && record.outbox.length > 0) {
      index.outbox_request_refs.push(digest);
    }
  }
  // Archive files of requests that are still active are crash residue of an unfinished
  // move; the index is created by the move itself.
  if (index.actor_prefixes === "" && index.outbox_request_refs.length === 0) return false;
  ledger.archive_index = index;
  return true;
}

function writeArchivedRequest(record: DeletionRecord): void {
  if (!validRecord(record, record.request_id)) {
    throw new PrivacyDeletionV2Error("integrity", "next archived deletion request is invalid");
  }
  const archive: ArchivedRequest = { version: ARCHIVE_VERSION, record };
  writeArchiveFile(
    "requests",
    `${requestDigest(record.request_id)}.json`,
    archive,
    PRIVACY_DELETION_V2_MAX_BYTES
  );
}

function writeArchivedActor(digest: string, fence: ActorFence): void {
  const archive: ArchivedActor = { version: ARCHIVE_VERSION, actor_sha256: digest, fence };
  writeArchiveFile("actors", `${digest}.json`, archive, ARCHIVE_ACTOR_MAX_BYTES);
}

function writeArchiveFile(
  kind: "requests" | "actors",
  basename: string,
  value: unknown,
  maxPlaintextBytes: number
): void {
  const directory = archiveDirectory(kind, true);
  if (directory === null) throw new PrivacyDeletionV2Error("integrity", "archive directory is missing");
  const encoded = encryptGatewayStateJson(
    { kind: "privacy-deletion-v2", recordId: basename },
    value,
    maxPlaintextBytes
  );
  const temporary = path.join(directory, `.${basename}.${process.pid}.${randomBytes(12).toString("hex")}.tmp`);
  let descriptor: number | null = null;
  try {
    descriptor = openSync(
      temporary,
      fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_WRONLY | fsConstants.O_NOFOLLOW,
      0o600
    );
    writeFileSync(descriptor, encoded, "utf8");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    renameSync(temporary, path.join(directory, basename));
    // S19-27: after the rename a failure is outcome unknown, not "not written".
    syncGatewayStateDirectoryAfterRename("privacy-deletion-v2", directory);
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    rmSync(temporary, { force: true });
  }
}

function archivableRequest(record: DeletionRecord): boolean {
  return record.backend_status?.overall_status === "COMPLETED" &&
    record.outbox.length === 0 &&
    Object.values(record.evidence_operations).every(
      (operation) => operation.status === "ACKNOWLEDGED" ||
        operation.status === "SUPERSEDED_REVISION"
    );
}

/**
 * S19-36: crash-safe move inside the ledger lock. Order: archive request + actor fence
 * (each temp fsync, rename, directory fsync) -> re-read and compare -> drop them from the
 * in-memory active ledger -> the caller's writeLedger. A crash before the ledger write leaves
 * both copies; readers prefer the active copy after checking the immutable identity and the
 * fence, and the next write re-archives. The actor fence (re-signup block) is never dropped.
 */
function archiveCompletedRequests(ledger: DeletionLedger): void {
  const threshold = archiveTriggerBytesForTests ?? ARCHIVE_TRIGGER_BYTES;
  if (Buffer.byteLength(JSON.stringify(ledger), "utf8") <= threshold) return;
  const moved: string[] = [];
  for (const [requestId, record] of Object.entries(ledger.requests)) {
    if (!archivableRequest(record)) continue;
    const fence = ledger.actors[record.actor_sha256];
    if (!fence || fence.request_id !== requestId) continue;
    writeArchivedRequest(record);
    writeArchivedActor(record.actor_sha256, fence);
    const archived = readArchivedRequestByDigest(requestDigest(requestId));
    if (!archived || JSON.stringify(archived) !== JSON.stringify(record)) {
      throw new PrivacyDeletionV2Error("integrity", "archived deletion request did not verify");
    }
    const archivedFence = readArchivedActor(record.actor_sha256);
    if (!archivedFence || !sameFence(archivedFence, fence)) {
      throw new PrivacyDeletionV2Error("integrity", "archived deletion fence did not verify");
    }
    moved.push(requestId);
  }
  if (moved.length > 0 && archiveFaultForTests === "after_archive_write") {
    throw new Error("injected privacy deletion archive fault before the active ledger write");
  }
  for (const requestId of moved) {
    const record = ledger.requests[requestId]!;
    // S19-36-F2: the archived fence file is registered in the same ledger write.
    registerArchivedActor(ledger, record.actor_sha256);
    delete ledger.actors[record.actor_sha256];
    delete ledger.requests[requestId];
  }
}

export function validPrivacyDeletionV2ArchiveRequestForMaintenance(
  value: unknown,
  recordId: string
): boolean {
  const archive = objectValue(value);
  const record = objectValue(archive?.record);
  return !!archive && exactKeys(archive, ["version", "record"]) &&
    archive.version === ARCHIVE_VERSION && !!record &&
    typeof record.request_id === "string" &&
    recordId === `${requestDigest(record.request_id)}.json` &&
    validRecord(record, record.request_id);
}

export function validPrivacyDeletionV2ArchiveActorForMaintenance(
  value: unknown,
  recordId: string
): boolean {
  const actor = objectValue(value);
  const fence = objectValue(actor?.fence);
  return !!actor && exactKeys(actor, ["version", "actor_sha256", "fence"]) &&
    actor.version === ARCHIVE_VERSION &&
    typeof actor.actor_sha256 === "string" && SHA256.test(actor.actor_sha256) &&
    recordId === `${actor.actor_sha256}.json` && !!fence &&
    exactKeys(fence, ["account_generation", "request_id", "gateway_fence_id"]) &&
    safePositiveInteger(fence.account_generation) &&
    typeof fence.request_id === "string" && REQUEST_ID.test(fence.request_id) &&
    typeof fence.gateway_fence_id === "string" && OPAQUE_ID.test(fence.gateway_fence_id);
}

function validOutboxPause(value: unknown): value is OutboxPause {
  const pause = objectValue(value);
  if (
    !pause || !exactKeys(pause, [
      "pause_id", "reason", "http_status", "cause_code", "paused_at"
    ]) ||
    typeof pause.pause_id !== "string" || !PAUSE_ID.test(pause.pause_id) ||
    !validInstant(pause.paused_at)
  ) return false;
  if (pause.reason === "credentials") {
    return (
      (pause.http_status === null && pause.cause_code === "backend_assertion_unavailable") ||
      (pause.http_status === 401 && pause.cause_code === "backend_http_401") ||
      (pause.http_status === 403 && pause.cause_code === "backend_http_403")
    );
  }
  if (pause.reason === "contract") {
    return (
      (pause.http_status === 400 && pause.cause_code === "backend_http_400") ||
      (pause.http_status === 422 && typeof pause.cause_code === "string" &&
        KNOWN_CONTRACT_422_CODES.has(pause.cause_code)) ||
      ((pause.http_status === 200 || pause.http_status === 202) &&
        pause.cause_code === "invalid_success_payload")
    );
  }
  return false;
}

function validOutboxOperation(value: unknown): value is OutboxOperation {
  const operation = objectValue(value);
  if (!operation) return false;
  const pending = exactKeys(operation, [
    "operation_id", "kind", "body", "body_sha256", "attempts", "next_attempt_at"
  ]);
  const terminal = exactKeys(operation, [
    "operation_id", "kind", "body", "body_sha256", "attempts", "next_attempt_at",
    "terminal_conflict_code", "terminal_at"
  ]);
  const paused = exactKeys(operation, [
    "operation_id", "kind", "body", "body_sha256", "attempts", "next_attempt_at",
    "paused"
  ]);
  return (pending || terminal || paused) &&
    typeof operation.operation_id === "string" && OPAQUE_ID.test(operation.operation_id) &&
    (operation.kind === "REQUEST" || operation.kind === "DEVICE_EVIDENCE") &&
    typeof operation.body === "string" && operation.body.length > 1 && operation.body.length <= 16_384 &&
    typeof operation.body_sha256 === "string" && SHA256.test(operation.body_sha256) &&
    sha256(operation.body) === operation.body_sha256 &&
    Number.isSafeInteger(operation.attempts) && (operation.attempts as number) >= 0 &&
    validInstant(operation.next_attempt_at) &&
    (!paused || validOutboxPause(operation.paused)) &&
    (!terminal || (
      typeof operation.terminal_conflict_code === "string" &&
      ACCOUNT_DELETION_TERMINAL_CONFLICT_CODE_SET.has(
        operation.terminal_conflict_code
      ) &&
      validInstant(operation.terminal_at)
    ));
}

function operationTerminalConflictCode(operation: OutboxOperation): string | null {
  return typeof operation.terminal_conflict_code === "string"
    ? operation.terminal_conflict_code
    : null;
}

function validRecord(value: unknown, requestId: string): value is DeletionRecord {
  const record = objectValue(value);
  if (
    !record ||
    !exactKeys(record, [
      "request_id", "actor_id", "actor_sha256", "account_generation", "client_revision",
      "request_body", "request_body_sha256", "gateway_fence_id", "access_pre_digest",
      "access_bound_digest", "tombstone_id", "accepted_at", "request_receipt_sha256",
      "backend_status", "outbox", "evidence_operations",
      ...(Object.hasOwn(record, "capability_key") ? ["capability_key"] : [])
    ]) ||
    (Object.hasOwn(record, "capability_key") && !validCapabilityKey(record.capability_key)) ||
    record.request_id !== requestId || !REQUEST_ID.test(requestId) ||
    typeof record.actor_id !== "string" || !ACTOR_ID.test(record.actor_id) ||
    typeof record.actor_sha256 !== "string" || actorDigest(record.actor_id) !== record.actor_sha256 ||
    !safePositiveInteger(record.account_generation) ||
    !safePositiveInteger(record.client_revision) ||
    typeof record.request_body !== "string" ||
    typeof record.request_body_sha256 !== "string" ||
    sha256(record.request_body) !== record.request_body_sha256 ||
    typeof record.gateway_fence_id !== "string" || !OPAQUE_ID.test(record.gateway_fence_id) ||
    typeof record.access_pre_digest !== "string" || !SHA256.test(record.access_pre_digest) ||
    !nullableString(record.access_bound_digest, SHA256) ||
    !nullableString(record.tombstone_id, OPAQUE_ID) ||
    !nullableString(record.accepted_at) ||
    !nullableString(record.request_receipt_sha256, SHA256) ||
    !Array.isArray(record.outbox) || record.outbox.length > MAX_OUTBOX_OPERATIONS_PER_REQUEST ||
    !record.outbox.every(validOutboxOperation) ||
    !objectValue(record.evidence_operations) ||
    Object.keys(record.evidence_operations as object).length >
      MAX_EVIDENCE_OPERATIONS_PER_REQUEST + MAX_SUPERSEDED_EVIDENCE_OPERATIONS_PER_REQUEST
  ) return false;
  {
    const counts = evidenceBudgetCounts(
      record.evidence_operations as Record<string, { status: unknown }>
    );
    if (
      counts.active > MAX_EVIDENCE_OPERATIONS_PER_REQUEST ||
      counts.superseded > MAX_SUPERSEDED_EVIDENCE_OPERATIONS_PER_REQUEST
    ) return false;
  }
  let parsedBody: unknown;
  try { parsedBody = JSON.parse(record.request_body); } catch { return false; }
  const request = parseAccountDeletionRequestV2(parsedBody);
  if (
    !request || accountDeletionRequestBodyV2(request) !== record.request_body ||
    request.client_revision !== record.client_revision || request.request_id !== record.request_id
  ) return false;
  const status = record.backend_status === null
    ? null
    : parseAccountDeletionStatusV2(record.backend_status, requestId);
  if (record.backend_status !== null && status === null) return false;
  if (status !== null && (
    status.client_revision !== record.client_revision ||
    status.account_generation !== record.account_generation ||
    status.tombstone_id !== record.tombstone_id ||
    status.accepted_at !== record.accepted_at ||
    status.request_receipt_sha256 !== record.request_receipt_sha256 ||
    record.access_bound_digest === null
  )) return false;
  if ((record.tombstone_id === null) !== (record.access_bound_digest === null)) return false;
  for (const [evidenceId, rawOperation] of Object.entries(
    record.evidence_operations as Record<string, unknown>
  )) {
    const operation = objectValue(rawOperation);
    const sequenced = !!operation && Object.prototype.hasOwnProperty.call(operation, "sequence");
    const installed = !!operation && Object.prototype.hasOwnProperty.call(operation, "installation_id");
    if (
      !OPAQUE_ID.test(evidenceId) || !operation ||
      !exactKeys(
        operation,
        [
          "body_sha256", "evidence_sha256", "status",
          ...(sequenced ? ["sequence"] : []),
          ...(installed ? ["installation_id"] : [])
        ]
      ) ||
      (sequenced && !safePositiveInteger(operation.sequence)) ||
      (installed && (!sequenced || typeof operation.installation_id !== "string" ||
        !OPAQUE_ID.test(operation.installation_id))) ||
      typeof operation.body_sha256 !== "string" || !SHA256.test(operation.body_sha256) ||
      typeof operation.evidence_sha256 !== "string" || !SHA256.test(operation.evidence_sha256) ||
      !["PENDING", "ACKNOWLEDGED", "SUPERSEDED_REVISION", "TERMINAL_CONFLICT"].includes(
        String(operation.status)
      )
    ) return false;
    const queued = (record.outbox as OutboxOperation[]).filter(
      (candidate) =>
        candidate.kind === "DEVICE_EVIDENCE" &&
        candidate.operation_id === evidenceId
    );
    if (
      (["PENDING", "TERMINAL_CONFLICT"].includes(String(operation.status)) && (
        queued.length !== 1 || queued[0]!.body_sha256 !== operation.body_sha256
      )) ||
      (!["PENDING", "TERMINAL_CONFLICT"].includes(String(operation.status)) && queued.length !== 0) ||
      (operation.status === "PENDING" && operationTerminalConflictCode(queued[0]!) !== null) ||
      (operation.status === "TERMINAL_CONFLICT" && operationTerminalConflictCode(queued[0]!) === null)
    ) return false;
  }
  return true;
}

export function validPrivacyDeletionV2StateForMaintenance(
  value: unknown
): value is DeletionLedger {
  const ledger = objectValue(value);
  const hasArchiveIndex = !!ledger && Object.prototype.hasOwnProperty.call(ledger, "archive_index");
  if (
    !ledger ||
    !exactKeys(
      ledger,
      hasArchiveIndex
        ? ["schema_version", "revision", "actors", "requests", "archive_index"]
        : ["schema_version", "revision", "actors", "requests"]
    ) ||
    ledger.schema_version !== LEDGER_SCHEMA ||
    !Number.isSafeInteger(ledger.revision) || (ledger.revision as number) < 0 ||
    !objectValue(ledger.actors) || !objectValue(ledger.requests)
  ) return false;
  const requests = ledger.requests as Record<string, unknown>;
  if (!Object.entries(requests).every(([requestId, record]) => validRecord(record, requestId))) {
    return false;
  }
  const actors = ledger.actors as Record<string, unknown>;
  for (const [digest, rawFence] of Object.entries(actors)) {
    const fence = objectValue(rawFence);
    if (
      !SHA256.test(digest) || !fence ||
      !exactKeys(fence, ["account_generation", "request_id", "gateway_fence_id"]) ||
      !safePositiveInteger(fence.account_generation) ||
      typeof fence.request_id !== "string" || !REQUEST_ID.test(fence.request_id) ||
      typeof fence.gateway_fence_id !== "string" || !OPAQUE_ID.test(fence.gateway_fence_id)
    ) return false;
    const request = requests[fence.request_id] as DeletionRecord | undefined;
    if (
      !request || request.actor_sha256 !== digest ||
      request.account_generation !== fence.account_generation ||
      request.gateway_fence_id !== fence.gateway_fence_id
    ) return false;
  }
  if (hasArchiveIndex && !validArchiveIndex(ledger.archive_index, actors)) return false;
  return Object.keys(actors).length === Object.keys(requests).length;
}

const ARCHIVE_ACTOR_PREFIX_HEX = 16;
const ARCHIVE_ACTOR_PREFIXES = /^(?:[0-9a-f]{16})*$/;

function archiveActorPrefix(digest: string): string {
  return digest.slice(0, ARCHIVE_ACTOR_PREFIX_HEX);
}

/** Binary search in the sorted fixed-width prefix list; returns the insertion slot on a miss. */
function findArchiveActorPrefix(prefixes: string, digest: string): { found: boolean; slot: number } {
  const target = archiveActorPrefix(digest);
  let low = 0;
  let high = prefixes.length / ARCHIVE_ACTOR_PREFIX_HEX - 1;
  while (low <= high) {
    const middle = (low + high) >> 1;
    const value = prefixes.slice(
      middle * ARCHIVE_ACTOR_PREFIX_HEX,
      (middle + 1) * ARCHIVE_ACTOR_PREFIX_HEX
    );
    if (value === target) return { found: true, slot: middle };
    if (value < target) low = middle + 1;
    else high = middle - 1;
  }
  return { found: false, slot: low };
}

function validArchiveIndex(
  value: unknown,
  activeActors: Record<string, unknown>
): value is ArchiveIndex {
  const index = objectValue(value);
  const prefixes = index?.actor_prefixes;
  const refs = index?.outbox_request_refs;
  if (
    !index || !exactKeys(index, ["actor_prefixes", "outbox_request_refs"]) ||
    typeof prefixes !== "string" || !ARCHIVE_ACTOR_PREFIXES.test(prefixes) ||
    !Array.isArray(refs)
  ) return false;
  for (let offset = ARCHIVE_ACTOR_PREFIX_HEX; offset < prefixes.length; offset += ARCHIVE_ACTOR_PREFIX_HEX) {
    if (
      prefixes.slice(offset - ARCHIVE_ACTOR_PREFIX_HEX, offset) >=
        prefixes.slice(offset, offset + ARCHIVE_ACTOR_PREFIX_HEX)
    ) return false;
  }
  // An archived actor never has an active fence at the same time (acceptance is blocked).
  if (Object.keys(activeActors).some((digest) => findArchiveActorPrefix(prefixes, digest).found)) {
    return false;
  }
  return refs.every((ref, position) =>
    typeof ref === "string" && SHA256.test(ref) &&
    (position === 0 || String(refs[position - 1]) < ref)
  );
}

function readLedger(): DeletionLedger {
  ensureStateDirectory();
  try {
    const decoded = decryptGatewayStateJson(
      { kind: "privacy-deletion-v2", recordId: "ledger.json" },
      readSecureLedgerFile(),
      PRIVACY_DELETION_V2_MAX_BYTES
    ).value;
    if (!validPrivacyDeletionV2StateForMaintenance(decoded)) {
      throw new PrivacyDeletionV2Error("integrity", "privacy deletion v2 ledger is invalid");
    }
    return decoded;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      // S19-26: a lost ledger must not silently drop deletion fences and receipts.
      if (requiredGatewayStateFileWasLost(ledgerPath())) {
        throw new PrivacyDeletionV2Error("integrity", "privacy deletion v2 ledger is missing");
      }
      return emptyLedger();
    }
    if (error instanceof PrivacyDeletionV2Error) throw error;
    throw new PrivacyDeletionV2Error("integrity", "privacy deletion v2 ledger cannot be read");
  }
}

function writeLedger(ledger: DeletionLedger): void {
  if (!validPrivacyDeletionV2StateForMaintenance(ledger)) {
    throw new PrivacyDeletionV2Error("integrity", "next privacy deletion v2 state is invalid");
  }
  ensureStateDirectory();
  const encoded = encryptGatewayStateJson(
    { kind: "privacy-deletion-v2", recordId: "ledger.json" },
    ledger,
    PRIVACY_DELETION_V2_MAX_BYTES
  );
  const temporary = path.join(
    stateDirectory(),
    `.ledger-${process.pid}-${randomBytes(12).toString("hex")}.tmp`
  );
  let descriptor: number | null = null;
  try {
    descriptor = openSync(
      temporary,
      fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_WRONLY | fsConstants.O_NOFOLLOW,
      0o600
    );
    writeFileSync(descriptor, encoded, "utf8");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    renameSync(temporary, ledgerPath());
    // S19-27: after the rename a failure is outcome unknown, not "not written".
    syncGatewayStateDirectoryAfterRename("privacy-deletion-v2", stateDirectory());
    registerRequiredGatewayStateFile(ledgerPath());
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    rmSync(temporary, { force: true });
  }
}

function mutateLedger<T>(
  action: (ledger: DeletionLedger) => { value: T; changed: boolean },
  requestId?: string,
  actorSha256?: string
): T {
  ensureStateDirectory();
  try {
    const criticalSection = (): unknown => {
      const ledger = readLedger();
      const indexCreated = ensureArchiveIndex(ledger);
      let archivedOnly = false;
      let actorOverlay: string | null = null;
      if (requestId !== undefined) {
        const active = ledger.requests[requestId];
        const record = recordFromStores(ledger, requestId);
        if (record && !active) {
          const fence = fenceFromStores(ledger, record.actor_sha256) ?? null;
          assertArchiveFence(record, fence);
          ledger.requests[requestId] = record;
          ledger.actors[record.actor_sha256] = fence;
          archivedOnly = true;
        }
      }
      if (actorSha256 !== undefined && !ledger.actors[actorSha256]) {
        const fence = fenceFromStores(ledger, actorSha256);
        if (fence) {
          ledger.actors[actorSha256] = fence;
          actorOverlay = actorSha256;
        }
      }
      const result = action(ledger);
      if (archivedOnly) {
        // The archived record and its fence were only overlaid for `action`; the active
        // ledger is written (without them) only to keep the outbox index a superset.
        const record = ledger.requests[requestId!]!;
        delete ledger.requests[requestId!];
        delete ledger.actors[record.actor_sha256];
        if (actorOverlay !== null) delete ledger.actors[actorOverlay];
        const ref = requestDigest(record.request_id);
        let indexChanged = indexCreated;
        if (result.changed && record.outbox.length > 0) {
          indexChanged = setOutboxRef(ledger, ref, true) || indexChanged;
        }
        if (indexChanged) {
          ledger.revision += 1;
          writeLedger(ledger);
        }
        if (result.changed) {
          writeArchivedRequest(record);
          if (record.outbox.length === 0 && setOutboxRef(ledger, ref, false)) {
            ledger.revision += 1;
            writeLedger(ledger);
          }
        }
        return result.value;
      }
      if (result.changed && actorOverlay !== null) {
        throw new PrivacyDeletionV2Error("integrity", "archived actor cannot enter active ledger");
      }
      if (actorOverlay !== null) delete ledger.actors[actorOverlay];
      if (result.changed || indexCreated) {
        ledger.revision += 1;
        if (result.changed) archiveCompletedRequests(ledger);
        writeLedger(ledger);
      }
      return result.value;
    };
    return withExclusiveFileLock(
      path.join(stateDirectory(), "ledger.lock"),
      criticalSection
    ) as T;
  } catch (error) {
    if (error instanceof ExclusiveFileLockBusyError) {
      throw new PrivacyDeletionV2Error("busy", "privacy deletion v2 ledger is busy");
    }
    if (error instanceof ExclusiveFileLockIntegrityError) {
      throw new PrivacyDeletionV2Error("integrity", "privacy deletion v2 lock is unsafe");
    }
    throw error;
  }
}

export function isAccountGenerationFencedV2(actorId: string, generation: number): boolean {
  if (!ACTOR_ID.test(actorId) || !safePositiveInteger(generation)) return true;
  const fence = fenceFromStores(readLedger(), actorDigest(actorId));
  return !!fence && generation <= fence.account_generation;
}

export type AcceptDeletionResultV2 =
  | { kind: "accepted"; requestId: string; actorId: string; accountGeneration: number }
  | {
      kind: "replay";
      requestId: string;
      actorId: string;
      accountGeneration: number;
      status: AccountDeletionStatusV2 | null;
    }
  | { kind: "not_found" }
  | { kind: "conflict" }
  | { kind: "inactive" }
  | { kind: "reauthentication_required" };

/**
 * S19-44: `newAcceptanceAllowed` gates only the creation of a new request. An existing
 * request is replayed with its capability alone, so status and evidence stay reachable.
 */
export function acceptOrReplayAccountDeletionV2(
  actorId: string | null,
  accountGeneration: number | null,
  input: AccountDeletionRequestV2,
  accessSecret: string,
  nowEpochMs = Date.now(),
  newAcceptanceAllowed = true
): AcceptDeletionResultV2 {
  if (!parseAccountDeletionRequestV2(input) || !validDeletionAccessSecretV2(accessSecret)) {
    throw new PrivacyDeletionV2Error("conflict", "account deletion v2 input is invalid");
  }
  const requestBody = accountDeletionRequestBodyV2(input);
  const bodySha256 = sha256(requestBody);
  const occurredAt = new Date(nowEpochMs).toISOString();
  return mutateLedger<AcceptDeletionResultV2>((ledger) => {
    const existing = ledger.requests[input.request_id];
    if (existing) {
      if (!capabilityMatches(existing, accessSecret)) {
        return { value: { kind: "not_found" } as const, changed: false };
      }
      if (
        existing.request_body_sha256 !== bodySha256 ||
        existing.client_revision !== input.client_revision
      ) {
        return { value: { kind: "conflict" } as const, changed: false };
      }
      return {
        value: {
          kind: "replay",
          requestId: existing.request_id,
          actorId: existing.actor_id,
          accountGeneration: existing.account_generation,
          status: existing.backend_status
        } as const,
        changed: refreshCapabilityKey(existing, accessSecret)
      };
    }
    if (
      actorId === null || !ACTOR_ID.test(actorId) ||
      accountGeneration === null || !safePositiveInteger(accountGeneration)
    ) return { value: { kind: "not_found" } as const, changed: false };
    if (input.client_revision !== 1) {
      return { value: { kind: "conflict" } as const, changed: false };
    }
    const digest = actorDigest(actorId);
    if (ledger.actors[digest]) {
      return { value: { kind: "inactive" } as const, changed: false };
    }
    if (!newAcceptanceAllowed) {
      return { value: { kind: "reauthentication_required" } as const, changed: false };
    }
    const keyId = gatewayDeletionCapabilityKeyId();
    const pre = preAccessDigest(digest, accountGeneration, input.request_id, accessSecret, keyId);
    if (!pre) throw new PrivacyDeletionV2Error("integrity", "capability digest failed");
    const gatewayFenceId = `gateway_fence_${randomBytes(24).toString("base64url")}`;
    const operationId = `request_${randomBytes(24).toString("base64url")}`;
    const record: DeletionRecord = {
      request_id: input.request_id,
      actor_id: actorId,
      actor_sha256: digest,
      account_generation: accountGeneration,
      client_revision: input.client_revision,
      request_body: requestBody,
      request_body_sha256: bodySha256,
      gateway_fence_id: gatewayFenceId,
      access_pre_digest: pre,
      access_bound_digest: null,
      capability_key: { version: 1, key_id: keyId, verifier_sha256: pre },
      tombstone_id: null,
      accepted_at: null,
      request_receipt_sha256: null,
      backend_status: null,
      outbox: [{
        operation_id: operationId,
        kind: "REQUEST",
        body: requestBody,
        body_sha256: bodySha256,
        attempts: 0,
        next_attempt_at: occurredAt
      }],
      evidence_operations: {}
    };
    ledger.requests[input.request_id] = record;
    ledger.actors[digest] = {
      account_generation: accountGeneration,
      request_id: input.request_id,
      gateway_fence_id: gatewayFenceId
    };
    return {
      value: {
        kind: "accepted",
        requestId: input.request_id,
        actorId,
        accountGeneration
      } as const,
      changed: true
    };
  }, input.request_id, actorId !== null && ACTOR_ID.test(actorId) ? actorDigest(actorId) : undefined);
}

export function authorizedAccountDeletionStatusV2(
  requestId: string,
  accessSecret: string
): AccountDeletionStatusV2 | null | undefined {
  if (!REQUEST_ID.test(requestId) || !validDeletionAccessSecretV2(accessSecret)) return undefined;
  return mutateLedger<AccountDeletionStatusV2 | null | undefined>((ledger) => {
    const record = ledger.requests[requestId];
    if (!record || !capabilityMatches(record, accessSecret)) {
      return { value: undefined, changed: false };
    }
    return {
      value: record.backend_status,
      changed: refreshCapabilityKey(record, accessSecret)
    };
  }, requestId);
}

export type QueueEvidenceResultV2 =
  | { kind: "queued" | "replay"; operationId: string; status: AccountDeletionStatusV2 }
  | { kind: "superseded"; operationId: string; status: AccountDeletionStatusV2 }
  | { kind: "not_found" }
  | { kind: "conflict" };

export function queueDeviceDeletionEvidenceV2(
  evidence: DeviceDeletionEvidenceV2,
  accessSecret: string,
  nowEpochMs = Date.now()
): QueueEvidenceResultV2 {
  if (!parseDeviceDeletionEvidenceV2(evidence) || !validDeletionAccessSecretV2(accessSecret)) {
    throw new PrivacyDeletionV2Error("conflict", "device deletion evidence v2 is invalid");
  }
  if (authorizedAccountDeletionStatusV2(evidence.request_id, accessSecret) === undefined) {
    return { kind: "not_found" };
  }
  const body = deviceDeletionEvidenceBodyV2(evidence);
  const bodySha256 = sha256(body);
  return mutateLedger<QueueEvidenceResultV2>((ledger) => {
    const record = ledger.requests[evidence.request_id];
    if (!record || !capabilityMatches(record, accessSecret)) {
      return { value: { kind: "not_found" } as const, changed: false };
    }
    if (
      record.backend_status === null || record.tombstone_id !== evidence.tombstone_id ||
      record.request_receipt_sha256 !== evidence.request_receipt_sha256 ||
      record.client_revision !== evidence.client_revision
    ) return { value: { kind: "conflict" } as const, changed: false };
    const prior = record.evidence_operations[evidence.evidence_id];
    if (prior) {
      if (prior.body_sha256 !== bodySha256) {
        return { value: { kind: "conflict" } as const, changed: false };
      }
      const queued = record.outbox.find((operation) =>
        operation.kind === "DEVICE_EVIDENCE" && operation.body_sha256 === bodySha256
      );
      if (prior.status === "SUPERSEDED_REVISION") {
        return {
          value: {
            kind: "superseded",
            operationId: evidence.evidence_id,
            status: record.backend_status
          } as const,
          changed: false
        };
      }
      return {
        value: {
          kind: "replay",
          operationId: queued?.operation_id ?? evidence.evidence_id,
          status: record.backend_status
        } as const,
        changed: false
      };
    }
    if (record.backend_status.revision !== evidence.expected_status_revision) {
      return { value: { kind: "conflict" } as const, changed: false };
    }
    if (
      evidenceBudgetCounts(record.evidence_operations).active >=
        MAX_EVIDENCE_OPERATIONS_PER_REQUEST
    ) {
      throw new PrivacyDeletionV2Error("integrity", "device evidence operation ceiling exceeded");
    }
    if (record.outbox.length >= MAX_OUTBOX_OPERATIONS_PER_REQUEST) {
      throw new PrivacyDeletionV2Error("busy", "device evidence outbox is full");
    }
    const operationId = evidence.evidence_id;
    const sequence = Object.values(record.evidence_operations).reduce(
      (latest, operation) => Math.max(latest, operation.sequence ?? 0),
      0
    ) + 1;
    record.evidence_operations[evidence.evidence_id] = {
      body_sha256: bodySha256,
      evidence_sha256: evidence.evidence_sha256,
      status: "PENDING",
      sequence,
      installation_id: evidence.installation_id
    };
    record.outbox.push({
      operation_id: operationId,
      kind: "DEVICE_EVIDENCE",
      body,
      body_sha256: bodySha256,
      attempts: 0,
      next_attempt_at: new Date(nowEpochMs).toISOString()
    });
    return {
      value: { kind: "queued", operationId, status: record.backend_status } as const,
      changed: true
    };
  }, evidence.request_id);
}

type ForwardResult =
  | { kind: "ok"; status: AccountDeletionStatusV2; upstreamStatus: number }
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
  const headers = deletionBackendHeaders({
    actorId: record.actor_id,
    accountGeneration: record.account_generation,
    requestId: record.request_id,
    tombstoneId: record.tombstone_id,
    accessPreDigest: record.access_pre_digest,
    method: "GET",
    path: backendPath,
    bodySha256: EMPTY_BODY_SHA256
  }, { accept: "application/json" });
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
    const status = parseAccountDeletionStatusV2(decoded, requestId);
    if (status) return { kind: "ok", status: mergeBackendStatus(requestId, null, status), upstreamStatus: 200 };
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

export function privacyDeletionV2LedgerPathForTests(): string {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("privacy deletion v2 ledger path is test-only");
  }
  return ledgerPath();
}
