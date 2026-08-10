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
  openSync,
  readFileSync,
  realpathSync,
  renameSync,
  rmSync,
  writeFileSync
} from "node:fs";
import path from "node:path";

import {
  backendUrl,
  deletionBackendHeaders,
  fetchBackend,
  type GatewayFetch
} from "./backend.js";
import { resolveGatewayStateDirectory } from "./config.js";
import {
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  maxGatewayStateEnvelopeBytes
} from "./encrypted-json-store.js";
import {
  ExclusiveFileLockBusyError,
  ExclusiveFileLockIntegrityError,
  withExclusiveFileLock
} from "./exclusive-file-lock.js";

export const ACCOUNT_DELETION_REQUEST_SCHEMA_V2 =
  "walksafe.account-deletion-request.v2" as const;
export const ACCOUNT_DELETION_STATUS_SCHEMA_V2 =
  "walksafe.account-deletion-status.v2" as const;
export const DEVICE_DELETION_EVIDENCE_SCHEMA_V2 =
  "walksafe.device-deletion-evidence.v2" as const;
export const DELETION_ACCESS_SECRET_HEADER =
  "x-walksafe-deletion-access-secret" as const;
export const PRIVACY_DELETION_V2_MAX_BYTES = 4 * 1024 * 1024;

const LEDGER_SCHEMA = "walksafe.gateway-account-deletion-ledger.v2" as const;
const REQUEST_ID = /^[A-Za-z0-9_-]{16,128}$/;
const OPAQUE_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/;
const ACTOR_ID = /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const CAPABILITY = /^[A-Za-z0-9_-]{43}$/;
const BACKEND_CONFLICT_CODE = /^[a-z][a-z0-9_]{2,95}$/;
const MAX_BACKEND_RESPONSE_BYTES = 128 * 1024;
const MAX_OUTBOX_OPERATIONS_PER_REQUEST = 16;
const MAX_EVIDENCE_OPERATIONS_PER_REQUEST = 64;
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
  terminal_conflict_code?: string;
  terminal_at?: string;
};

type EvidenceOperation = {
  body_sha256: string;
  evidence_sha256: string;
  status: "PENDING" | "ACKNOWLEDGED" | "SUPERSEDED_REVISION" | "TERMINAL_CONFLICT";
};

type DeletionRecord = {
  request_id: string;
  actor_id: string;
  actor_sha256: string;
  account_generation: number;
  client_revision: number;
  request_body: string;
  request_body_sha256: string;
  gateway_fence_id: string;
  access_pre_digest: string;
  access_bound_digest: string | null;
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

type DeletionLedger = {
  schema_version: typeof LEDGER_SCHEMA;
  revision: number;
  actors: Record<string, ActorFence>;
  requests: Record<string, DeletionRecord>;
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
  secret: string
): string | null {
  const bytes = capabilityBytes(secret);
  if (!bytes) return null;
  try {
    return createHmac("sha256", capabilityHmacKey())
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
  return createHmac("sha256", capabilityHmacKey())
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
    secret
  );
  if (!pre) return false;
  if (record.access_bound_digest === null) {
    return equalHex(record.access_pre_digest, pre);
  }
  const bound = boundAccessDigest(record, pre);
  return bound !== null && equalHex(record.access_bound_digest, bound);
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
  return (pending || terminal) &&
    typeof operation.operation_id === "string" && OPAQUE_ID.test(operation.operation_id) &&
    (operation.kind === "REQUEST" || operation.kind === "DEVICE_EVIDENCE") &&
    typeof operation.body === "string" && operation.body.length > 1 && operation.body.length <= 16_384 &&
    typeof operation.body_sha256 === "string" && SHA256.test(operation.body_sha256) &&
    sha256(operation.body) === operation.body_sha256 &&
    Number.isSafeInteger(operation.attempts) && (operation.attempts as number) >= 0 &&
    validInstant(operation.next_attempt_at) &&
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
      "backend_status", "outbox", "evidence_operations"
    ]) ||
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
    Object.keys(record.evidence_operations as object).length > MAX_EVIDENCE_OPERATIONS_PER_REQUEST
  ) return false;
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
    if (
      !OPAQUE_ID.test(evidenceId) || !operation ||
      !exactKeys(operation, ["body_sha256", "evidence_sha256", "status"]) ||
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
  if (
    !ledger || !exactKeys(ledger, ["schema_version", "revision", "actors", "requests"]) ||
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
  return Object.keys(actors).length === Object.keys(requests).length;
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
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return emptyLedger();
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
  let directoryDescriptor: number | null = null;
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
    directoryDescriptor = openSync(
      stateDirectory(),
      fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW
    );
    fsyncSync(directoryDescriptor);
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    if (directoryDescriptor !== null) closeSync(directoryDescriptor);
    rmSync(temporary, { force: true });
  }
}

function mutateLedger<T>(action: (ledger: DeletionLedger) => { value: T; changed: boolean }): T {
  ensureStateDirectory();
  try {
    const criticalSection = (): unknown => {
      const ledger = readLedger();
      const result = action(ledger);
      if (result.changed) {
        ledger.revision += 1;
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
  const fence = readLedger().actors[actorDigest(actorId)];
  return !!fence && fence.account_generation === generation;
}

export type AcceptDeletionResultV2 =
  | { kind: "accepted"; requestId: string; actorId: string; accountGeneration: number }
  | { kind: "replay"; requestId: string; status: AccountDeletionStatusV2 | null }
  | { kind: "not_found" }
  | { kind: "conflict" }
  | { kind: "inactive" };

export function acceptOrReplayAccountDeletionV2(
  actorId: string | null,
  accountGeneration: number | null,
  input: AccountDeletionRequestV2,
  accessSecret: string,
  nowEpochMs = Date.now()
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
          status: existing.backend_status
        } as const,
        changed: false
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
    const pre = preAccessDigest(digest, accountGeneration, input.request_id, accessSecret);
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
  });
}

export function authorizedAccountDeletionStatusV2(
  requestId: string,
  accessSecret: string
): AccountDeletionStatusV2 | null | undefined {
  if (!REQUEST_ID.test(requestId) || !validDeletionAccessSecretV2(accessSecret)) return undefined;
  const record = readLedger().requests[requestId];
  if (!record || !capabilityMatches(record, accessSecret)) return undefined;
  return record.backend_status;
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
    if (Object.keys(record.evidence_operations).length >= MAX_EVIDENCE_OPERATIONS_PER_REQUEST) {
      throw new PrivacyDeletionV2Error("integrity", "device evidence operation ceiling exceeded");
    }
    if (record.outbox.length >= MAX_OUTBOX_OPERATIONS_PER_REQUEST) {
      throw new PrivacyDeletionV2Error("busy", "device evidence outbox is full");
    }
    const operationId = evidence.evidence_id;
    record.evidence_operations[evidence.evidence_id] = {
      body_sha256: bodySha256,
      evidence_sha256: evidence.evidence_sha256,
      status: "PENDING"
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
  });
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
    if (operationTerminalConflictCode(operation) !== null) {
      return { value: 1, changed: false };
    }
    operation.attempts += 1;
    const delay = retryDelayMs(operation.attempts);
    operation.next_attempt_at = new Date(nowEpochMs + delay).toISOString();
    return { value: Math.max(1, Math.ceil(delay / 1_000)), changed: true };
  });
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
  });
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
  });
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
      if (evidence) evidence.status = "ACKNOWLEDGED";
    }
    return { value: status, changed: true };
  });
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
    return {
      value: { status: record.backend_status, superseded: true },
      changed: before !== record.outbox.length || statusChanged
    };
  });
}

async function forwardOperation(
  requestId: string,
  operationId: string,
  clientRequest: Request,
  fetchImpl?: GatewayFetch,
  nowEpochMs = Date.now()
): Promise<ForwardResult> {
  const inFlightKey = `${requestId}\0${operationId}`;
  if (forwardingOperations.has(inFlightKey)) {
    return { kind: "pending", retryAfterSeconds: 1 };
  }
  const ledger = readLedger();
  const record = ledger.requests[requestId];
  const operation = record?.outbox.find((candidate) => candidate.operation_id === operationId);
  if (!record || !operation) {
    return record?.backend_status
      ? { kind: "ok", status: record.backend_status, upstreamStatus: 200 }
      : { kind: "not_found" };
  }
  const terminalConflictCode = operationTerminalConflictCode(operation);
  if (terminalConflictCode !== null) {
    return {
      kind: "terminal_conflict",
      code: terminalConflictCode,
      status: record.backend_status
    };
  }
  const backendPath = operation.kind === "REQUEST"
    ? "/privacy/account-deletions"
    : `/privacy/account-deletions/${requestId}/device-evidence`;
  const headers = deletionBackendHeaders({
    actorId: record.actor_id,
    accountGeneration: record.account_generation,
    requestId: record.request_id,
    tombstoneId: operation.kind === "REQUEST" ? null : record.tombstone_id,
    accessPreDigest: record.access_pre_digest,
    method: "POST",
    path: backendPath,
    bodySha256: operation.body_sha256
  }, { "content-type": "application/json", accept: "application/json" });
  if (!headers) throw new PrivacyDeletionV2Error("integrity", "backend deletion assertion failed");
  forwardingOperations.add(inFlightKey);
  try {
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
        return {
          kind: "pending",
          retryAfterSeconds: markOperationRetry(requestId, operationId, nowEpochMs)
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
          retryAfterSeconds: markOperationRetry(requestId, operationId, nowEpochMs)
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
          nowEpochMs
        );
      }
      const concluded = quarantineOperationConflict(
        requestId,
        operationId,
        backendConflictCode,
        nowEpochMs
      );
      return {
        kind: "terminal_conflict",
        code: concluded.code,
        status: concluded.status
      };
    }
    void response.body?.cancel("account deletion upstream response not exposed").catch(() => undefined);
    return {
      kind: "pending",
      retryAfterSeconds: markOperationRetry(requestId, operationId, nowEpochMs)
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
  const record = readLedger().requests[requestId];
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
  const record = readLedger().requests[requestId];
  if (!record || !capabilityMatches(record, accessSecret)) return { kind: "not_found" };
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
  const record = readLedger().requests[requestId];
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

export async function drainAccountDeletionOutboxV2(
  fetchImpl?: GatewayFetch,
  nowEpochMs = Date.now()
): Promise<{ attempted: number; succeeded: number }> {
  const snapshot = readLedger();
  const due: Array<{ requestId: string; operationId: string }> = [];
  for (const record of Object.values(snapshot.requests)) {
    for (const operation of record.outbox) {
      if (
        operationTerminalConflictCode(operation) === null &&
        Date.parse(operation.next_attempt_at) <= nowEpochMs
      ) {
        due.push({ requestId: record.request_id, operationId: operation.operation_id });
      }
    }
  }
  let succeeded = 0;
  for (const operation of due.slice(0, 16)) {
    const synthetic = new Request("http://127.0.0.1/privacy-deletion-outbox", {
      signal: AbortSignal.timeout(20_000)
    });
    const result = await forwardOperation(
      operation.requestId,
      operation.operationId,
      synthetic,
      fetchImpl,
      nowEpochMs
    );
    if (result.kind === "ok") succeeded += 1;
  }
  return { attempted: Math.min(due.length, 16), succeeded };
}

export function privacyDeletionV2LedgerPathForTests(): string {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("privacy deletion v2 ledger path is test-only");
  }
  return ledgerPath();
}
