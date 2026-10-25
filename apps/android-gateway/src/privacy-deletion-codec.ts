import { createHash, createHmac, timingSafeEqual } from "node:crypto";
import { createDeletionCapabilityHmac, gatewayDeletionCapabilityKeyId } from "./encrypted-json-store.js";

export const ACCOUNT_DELETION_REQUEST_SCHEMA_V2 =
  "walksafe.account-deletion-request.v2" as const;

export const ACCOUNT_DELETION_STATUS_SCHEMA_V2 =
  "walksafe.account-deletion-status.v2" as const;

export const DEVICE_DELETION_EVIDENCE_SCHEMA_V2 =
  "walksafe.device-deletion-evidence.v2" as const;

export const LEDGER_SCHEMA = "walksafe.gateway-account-deletion-ledger.v2" as const;

export const ARCHIVE_VERSION = 1 as const;

export const REQUEST_ID = /^[A-Za-z0-9_-]{16,128}$/;

export const OPAQUE_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/;

export const ACTOR_ID = /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/;

export const SHA256 = /^[0-9a-f]{64}$/;

const CAPABILITY = /^[A-Za-z0-9_-]{43}$/;

export const PAUSE_ID = /^[0-9a-f]{32}$/;

export const KNOWN_CONTRACT_422_CODES = new Set([
  "account_deletion_request_invalid",
  "account_deletion_binding_invalid",
  "account_deletion_request_mismatch",
  "device_deletion_evidence_digest_invalid"
]);

export const MAX_OUTBOX_OPERATIONS_PER_REQUEST = 16;

/** Evidence that can still change the Backend (PENDING, ACKNOWLEDGED, TERMINAL_CONFLICT). */
export const MAX_EVIDENCE_OPERATIONS_PER_REQUEST = 64;

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

export function evidenceBudgetCounts(
  operations: Record<string, BudgetOperation>
): { active: number; superseded: number } {
  const superseded = replayOnlyEvidenceIds(operations).length;
  return { active: Object.keys(operations).length - superseded, superseded };
}

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

export const ACCOUNT_DELETION_TERMINAL_CONFLICT_CODE_SET = new Set<string>(
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
  installation_signature?: string;
};

export type OutboxOperation = {
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

export type OutboxPause = {
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
export function trimReplayOnlyEvidence(
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

export type DeletionRecord = {
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

export type ActorFence = {
  account_generation: number;
  request_id: string;
  gateway_fence_id: string;
};

/**
 * S19-36 (w22 review): what the active ledger keeps about archived requests. Present only
 * once something was archived, so a ledger that never archived keeps its earlier bytes.
 */
export type ArchiveIndex = {
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

export type DeletionLedger = {
  schema_version: typeof LEDGER_SCHEMA;
  revision: number;
  actors: Record<string, ActorFence>;
  requests: Record<string, DeletionRecord>;
  archive_index?: ArchiveIndex;
};

export type ArchivedRequest = { version: typeof ARCHIVE_VERSION; record: DeletionRecord };

export type ArchivedActor = {
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

export function objectValue(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

export function exactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length &&
    actual.every((key, index) => key === wanted[index]);
}

export function safePositiveInteger(value: unknown): value is number {
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

export function sha256(value: string | Buffer): string {
  return createHash("sha256").update(value).digest("hex");
}

export function actorDigest(actorId: string): string {
  return sha256(`walksafe/deletion-actor/v2\0${actorId}`);
}

export function requestDigest(requestId: string): string {
  return sha256(`walksafe/deletion-archive-request/v1\0${requestId}`);
}

export function operationDigest(operationId: string): string {
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

export function preAccessDigest(
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

export function boundAccessDigest(record: DeletionRecord, preDigest: string): string | null {
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

export function capabilityMatches(record: DeletionRecord, secret: string): boolean {
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
export function refreshCapabilityKey(record: DeletionRecord, secret: string): boolean {
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
    evidence_sha256: input.evidence_sha256,
    ...(input.installation_signature === undefined ? {} : { installation_signature: input.installation_signature })
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
      "item", "result", "completed_at", "evidence_sha256",
      ...(Object.hasOwn(input, "installation_signature") ? ["installation_signature"] : [])
    ]) ||
    input.schema_version !== DEVICE_DELETION_EVIDENCE_SCHEMA_V2 ||
    (Object.hasOwn(input, "installation_signature") && (
      typeof input.installation_signature !== "string" ||
      !/^[A-Za-z0-9_-]{86,100}$/.test(input.installation_signature) ||
      Buffer.from(input.installation_signature, "base64url").toString("base64url") !== input.installation_signature
    )) ||
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
    evidence_sha256: input.evidence_sha256,
    ...(typeof input.installation_signature === "string" ? { installation_signature: input.installation_signature } : {})
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

/** Evaluated on each GET; deliberately excluded from the persisted canonical status. */
export type AccountDeletionDeviceVerificationV1 = {
  schema_version: "walksafe.account-deletion-device-verification.v1";
  completion_view: "COMPLETED" | "IN_PROGRESS" | "SERVER_COMPLETED_DEVICE_UNVERIFIED";
  target_count: number;
  deleted_count: number;
  not_found_count: number;
  failed_count: number;
  pending_count: number;
  unverifiable_count: number;
  unverifiable_reason: "SLA_ELAPSED_NO_CONTACT" | null;
  follow_up: "DELETE_ON_NEXT_CONTACT_OR_REMOVE_APP" | null;
  device_clock_anomaly_count: number;
  device_due_at: string;
  evaluated_at: string;
};

export type AccountDeletionStatusViewV2 = AccountDeletionStatusV2 & {
  device_verification?: AccountDeletionDeviceVerificationV1;
};

export function parseAccountDeletionStatusViewV2(
  value: unknown,
  expectedRequestId?: string
): AccountDeletionStatusViewV2 | null {
  const payload = objectValue(value);
  if (!payload) return null;
  const { device_verification: rawView, ...canonicalPayload } = payload;
  const canonical = parseAccountDeletionStatusV2(canonicalPayload, expectedRequestId);
  if (!canonical) return null;
  if (!Object.hasOwn(payload, "device_verification")) return canonical;
  const view = objectValue(rawView);
  const counts = ["target_count", "deleted_count", "not_found_count", "failed_count",
    "pending_count", "unverifiable_count", "device_clock_anomaly_count"];
  if (!view || !exactKeys(view, ["schema_version", "completion_view", ...counts,
      "unverifiable_reason", "follow_up", "device_due_at", "evaluated_at"]) ||
      view.schema_version !== "walksafe.account-deletion-device-verification.v1" ||
      !["COMPLETED", "IN_PROGRESS", "SERVER_COMPLETED_DEVICE_UNVERIFIED"].includes(String(view.completion_view)) ||
      counts.some(key => !Number.isSafeInteger(view[key]) || Number(view[key]) < 0) ||
      !validInstant(view.device_due_at) || !validInstant(view.evaluated_at)) return null;
  const typed = view as unknown as AccountDeletionDeviceVerificationV1;
  const total = typed.deleted_count + typed.not_found_count + typed.failed_count +
    typed.pending_count + typed.unverifiable_count;
  if (!Number.isSafeInteger(total) || total !== typed.target_count ||
      ((canonical.overall_status === "COMPLETED") !== (typed.completion_view === "COMPLETED")) ||
      typed.unverifiable_reason !== (typed.unverifiable_count > 0 ? "SLA_ELAPSED_NO_CONTACT" : null) ||
      typed.follow_up !== (typed.unverifiable_count > 0 ? "DELETE_ON_NEXT_CONTACT_OR_REMOVE_APP" : null)) return null;
  if (typed.completion_view === "COMPLETED" &&
      (typed.failed_count || typed.pending_count || typed.unverifiable_count)) return null;
  if (typed.completion_view === "SERVER_COMPLETED_DEVICE_UNVERIFIED" &&
      (!typed.unverifiable_count || typed.failed_count || typed.pending_count ||
        canonical.items.some(item => item.key !== "device_untransmitted_data" &&
          item.status !== "COMPLETED" && item.status !== "NOT_APPLICABLE"))) return null;
  return { ...canonical, device_verification: typed };
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

export function operationTerminalConflictCode(operation: OutboxOperation): string | null {
  return typeof operation.terminal_conflict_code === "string"
    ? operation.terminal_conflict_code
    : null;
}

export function validRecord(value: unknown, requestId: string): value is DeletionRecord {
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

export const ARCHIVE_ACTOR_PREFIX_HEX = 16;

const ARCHIVE_ACTOR_PREFIXES = /^(?:[0-9a-f]{16})*$/;

export function archiveActorPrefix(digest: string): string {
  return digest.slice(0, ARCHIVE_ACTOR_PREFIX_HEX);
}

/** Binary search in the sorted fixed-width prefix list; returns the insertion slot on a miss. */
export function findArchiveActorPrefix(prefixes: string, digest: string): { found: boolean; slot: number } {
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

