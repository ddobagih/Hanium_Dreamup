import {
  createCipheriv,
  createDecipheriv,
  createHash,
  randomBytes,
  timingSafeEqual
} from "node:crypto";
import { spawnSync } from "node:child_process";
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
  renameSync,
  rmSync,
  statSync,
  writeFileSync,
  writeSync
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import {
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  maxGatewayStateEnvelopeBytes
} from "./encrypted-json-store.js";

export const ACCOUNT_DELETION_CONTROL = "account-deletion";
export const ACCOUNT_DELETION_SECRET_HEADER =
  "x-walksafe-account-deletion-status-secret";
export const ACCOUNT_DELETION_REQUEST_SCHEMA =
  "walksafe.account-deletion-request.v1";

const LEDGER_SCHEMA_VERSION = 2;
export const PRIVACY_LEDGER_MAX_BYTES = 16 * 1024 * 1024;
const LOCK_WAIT_MS = 1_000;
const REQUEST_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{15,127}$/;
const OPERATION_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{15,127}$/;
const PRIVACY_ACTOR_ID = /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/;
const STATUS_SECRET = /^[0-9a-f]{64}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const BASE64URL = /^[A-Za-z0-9_-]+$/;
const sleepArray = new Int32Array(new SharedArrayBuffer(4));

function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || process.getuid() === uid;
}

export const ACCOUNT_DELETION_INVENTORY = Object.freeze([
  { key: "device_untransmitted_data", dueAfterMs: 24 * 60 * 60 * 1000 },
  { key: "server_originals", dueAfterMs: 7 * 24 * 60 * 60 * 1000 },
  { key: "server_quarantine", dueAfterMs: 7 * 24 * 60 * 60 * 1000 },
  { key: "server_copies", dueAfterMs: 7 * 24 * 60 * 60 * 1000 },
  { key: "report_records", dueAfterMs: 7 * 24 * 60 * 60 * 1000 },
  { key: "training_datasets", dueAfterMs: 30 * 24 * 60 * 60 * 1000 },
  { key: "training_labels", dueAfterMs: 30 * 24 * 60 * 60 * 1000 },
  { key: "derived_artifacts", dueAfterMs: 30 * 24 * 60 * 60 * 1000 },
  { key: "backups", dueAfterMs: 35 * 24 * 60 * 60 * 1000 }
] as const);

export type AccountDeletionItemKey =
  typeof ACCOUNT_DELETION_INVENTORY[number]["key"];

export type AccountDeletionItemStatus =
  | "PENDING"
  | "IN_PROGRESS"
  | "EXTERNAL_PENDING"
  | "RETRY_WAIT"
  | "LEGAL_HOLD"
  | "FAILED"
  | "COMPLETED"
  | "NOT_APPLICABLE";

type AccountDeletionOverallStatus =
  | "PROCESSING"
  | "RETRY_WAIT"
  | "RESTRICTED"
  | "FAILED"
  | "COMPLETED";

type LedgerEvent = {
  sequence: number;
  occurred_at: string;
  type: string;
  payload: Record<string, unknown>;
  previous_sha256: string | null;
  state_sha256: string;
  event_sha256: string;
};

type ActorLedger = {
  next_generation: number;
  active_generation: number | null;
  processing_epoch: number;
  tombstones: Array<{
    generation: number;
    request_id: string;
    tombstoned_at: string;
  }>;
};

type DeletionItem = {
  key: AccountDeletionItemKey;
  status: AccountDeletionItemStatus;
  item_revision: number;
  due_at: string;
  updated_at: string;
  evidence_ref?: string;
  retry_after?: string;
  restriction_reason?: string;
  legal_hold_review_at?: string;
  legal_hold_contact?: string;
  terminal_at?: string;
};

type RecordedOperation = {
  payload_sha256: string;
  request_revision: number;
  item_revision: number;
  item_key: AccountDeletionItemKey;
};

type ActorRecovery = {
  algorithm: "aes-256-gcm";
  iv_base64url: string;
  ciphertext_base64url: string;
  tag_base64url: string;
};

type DeletionRequest = {
  request_id: string;
  request_body_sha256: string;
  status_secret_sha256: string;
  actor_recovery: ActorRecovery;
  actor_sha256: string;
  account_generation: number;
  processing_epoch: number;
  client_revision: number;
  request_revision: number;
  accepted_at: string;
  updated_at: string;
  items: DeletionItem[];
  operations: Record<string, RecordedOperation>;
};

type PrivacyLedger = {
  schema_version: 2;
  actors: Record<string, ActorLedger>;
  requests: Record<string, DeletionRequest>;
  events: LedgerEvent[];
};

export type AccountDeletionRequestInput = {
  schema_version: typeof ACCOUNT_DELETION_REQUEST_SCHEMA;
  request_id: string;
  client_revision: number;
  confirmation: "DELETE_MY_ACCOUNT";
};

export type AccountDeletionStatus = {
  schema_version: "walksafe.account-deletion-status.v1";
  request_id: string;
  request_revision: number;
  client_revision: number;
  accepted_at: string;
  updated_at: string;
  account_generation: number;
  processing_epoch: number;
  overall_status: AccountDeletionOverallStatus;
  items: Array<{
    key: AccountDeletionItemKey;
    status: AccountDeletionItemStatus;
    item_revision: number;
    due_at: string;
    updated_at: string;
    evidence_ref?: string;
    retry_after?: string;
    restriction_reason?: string;
    legal_hold_review_at?: string;
    legal_hold_contact?: string;
    terminal_at?: string;
  }>;
};

export type PrivacyOperationLease = {
  actorId: string;
  actorSha256: string;
  accountGeneration: number;
  processingEpoch: number;
  controller: AbortController;
  token: string;
};

export type AccountDeletionTransition = {
  requestId: string;
  statusSecret: string;
  itemKey: AccountDeletionItemKey;
  operationId: string;
  expectedRequestRevision: number;
  expectedItemRevision: number;
  nextStatus: AccountDeletionItemStatus;
  evidenceRef?: string;
  retryAfter?: string;
  restrictionReason?: string;
  legalHoldReviewAt?: string;
  legalHoldContact?: string;
  terminalAt?: string;
};

export class PrivacyRightsLedgerError extends Error {
  constructor(
    readonly code: "busy" | "integrity" | "inactive" | "conflict" | "not_found",
    message: string
  ) {
    super(message);
  }
}

const activeOperations = new Map<string, PrivacyOperationLease>();

function sha256(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

function actorDigest(actorId: string): string {
  return sha256(`walksafe-privacy-actor\0${actorId}`);
}

function statusSecretDigest(secret: string): string {
  return sha256(`walksafe-account-deletion-status-secret\0${secret}`);
}

function actorRecoveryKey(secret: string): Buffer {
  return createHash("sha256")
    .update(`walksafe-account-deletion-actor-recovery\0${secret}`)
    .digest();
}

function encryptActorRecovery(actorId: string, secret: string): ActorRecovery {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", actorRecoveryKey(secret), iv);
  const ciphertext = Buffer.concat([
    cipher.update(actorId, "utf8"),
    cipher.final()
  ]);
  return {
    algorithm: "aes-256-gcm",
    iv_base64url: iv.toString("base64url"),
    ciphertext_base64url: ciphertext.toString("base64url"),
    tag_base64url: cipher.getAuthTag().toString("base64url")
  };
}

function decryptActorRecovery(recovery: ActorRecovery, secret: string): string {
  try {
    const decipher = createDecipheriv(
      "aes-256-gcm",
      actorRecoveryKey(secret),
      Buffer.from(recovery.iv_base64url, "base64url")
    );
    decipher.setAuthTag(Buffer.from(recovery.tag_base64url, "base64url"));
    return Buffer.concat([
      decipher.update(Buffer.from(recovery.ciphertext_base64url, "base64url")),
      decipher.final()
    ]).toString("utf8");
  } catch {
    throw new PrivacyRightsLedgerError(
      "integrity",
      "account deletion recovery data cannot be authenticated"
    );
  }
}

function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) {
    return `[${value.map((entry) => canonicalJson(entry)).join(",")}]`;
  }
  const object = value as Record<string, unknown>;
  return `{${Object.keys(object).sort().map((key) =>
    `${JSON.stringify(key)}:${canonicalJson(object[key])}`
  ).join(",")}}`;
}

function ledgerStateDigest(
  ledger: Pick<PrivacyLedger, "actors" | "requests">
): string {
  return sha256(
    `walksafe-privacy-ledger-state\0${canonicalJson({
      actors: ledger.actors,
      requests: ledger.requests
    })}`
  );
}

export function accountDeletionRequestBodySha256(
  input: AccountDeletionRequestInput
): string {
  return sha256(`walksafe-account-deletion-request\0${canonicalJson(input)}`);
}

function stateDirectory(): string {
  const root = process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR?.trim()
    || path.join(tmpdir(), "walksafe-android-gateway");
  return path.join(root, "privacy-rights");
}

function ledgerPath(): string {
  return path.join(stateDirectory(), "ledger.json");
}

function ensureDirectory(): void {
  const directory = stateDirectory();
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const metadata = lstatSync(directory);
  if (!metadata.isDirectory() || metadata.isSymbolicLink()) {
    throw new PrivacyRightsLedgerError("integrity", "privacy ledger directory is unsafe");
  }
  if ((metadata.mode & 0o077) !== 0) chmodSync(directory, 0o700);
}

function emptyLedger(): PrivacyLedger {
  return {
    schema_version: LEDGER_SCHEMA_VERSION,
    actors: {},
    requests: {},
    events: []
  };
}

function readSecureFile(file: string, maxBytes: number): string {
  const descriptor = openSync(
    file,
    fsConstants.O_RDONLY | (fsConstants.O_NOFOLLOW ?? 0)
  );
  try {
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() ||
      metadata.nlink !== 1 ||
      !ownedByCurrentProcess(metadata.uid) ||
      metadata.size > maxBytes ||
      (metadata.mode & 0o777) !== 0o600
    ) {
      throw new PrivacyRightsLedgerError("integrity", "privacy ledger file is unsafe");
    }
    return readFileSync(descriptor, "utf8");
  } finally {
    closeSync(descriptor);
  }
}

function safeInteger(value: unknown, minimum = 0): value is number {
  return Number.isSafeInteger(value) && (value as number) >= minimum;
}

function nextAccountGeneration(generation: number): number {
  return generation === Number.MAX_SAFE_INTEGER ? generation : generation + 1;
}

function objectValue(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function exactKeys(
  value: Record<string, unknown>,
  expected: readonly string[]
): boolean {
  const actual = Object.keys(value).sort();
  const required = [...expected].sort();
  return (
    actual.length === required.length &&
    actual.every((key, index) => key === required[index])
  );
}

function validIsoInstant(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const epoch = Date.parse(value);
  return Number.isFinite(epoch) && new Date(epoch).toISOString() === value;
}

function isoInstantFromEpoch(value: number): string | null {
  if (!Number.isSafeInteger(value) || value < 0) return null;
  try {
    return new Date(value).toISOString();
  } catch {
    return null;
  }
}

function validBase64Url(
  value: unknown,
  minimumBytes: number,
  maximumBytes: number
): value is string {
  if (typeof value !== "string" || !BASE64URL.test(value)) return false;
  const decoded = Buffer.from(value, "base64url");
  return (
    decoded.length >= minimumBytes &&
    decoded.length <= maximumBytes &&
    decoded.toString("base64url") === value
  );
}

function validActorRecovery(value: unknown): value is ActorRecovery {
  const recovery = objectValue(value);
  return Boolean(
    recovery &&
    exactKeys(recovery, [
      "algorithm",
      "iv_base64url",
      "ciphertext_base64url",
      "tag_base64url"
    ]) &&
    recovery.algorithm === "aes-256-gcm" &&
    validBase64Url(recovery.iv_base64url, 12, 12) &&
    validBase64Url(recovery.ciphertext_base64url, 1, 256) &&
    validBase64Url(recovery.tag_base64url, 16, 16)
  );
}

function validEventChain(events: unknown): events is LedgerEvent[] {
  if (!Array.isArray(events) || events.length > 200_000) return false;
  let previous: string | null = null;
  for (let index = 0; index < events.length; index += 1) {
    const event = objectValue(events[index]);
    if (
      !event ||
      event.sequence !== index + 1 ||
      typeof event.occurred_at !== "string" ||
      typeof event.type !== "string" ||
      !objectValue(event.payload) ||
      event.previous_sha256 !== previous ||
      typeof event.state_sha256 !== "string" ||
      !SHA256.test(event.state_sha256) ||
      typeof event.event_sha256 !== "string" ||
      !SHA256.test(event.event_sha256) ||
      !exactKeys(event, [
        "sequence",
        "occurred_at",
        "type",
        "payload",
        "previous_sha256",
        "state_sha256",
        "event_sha256"
      ]) ||
      !validIsoInstant(event.occurred_at)
    ) {
      return false;
    }
    const expected = sha256(
      `walksafe-privacy-ledger-event\0${canonicalJson({
        sequence: event.sequence,
        occurred_at: event.occurred_at,
        type: event.type,
        payload: event.payload,
        previous_sha256: event.previous_sha256,
        state_sha256: event.state_sha256
      })}`
    );
    if (expected !== event.event_sha256) return false;
    previous = expected;
  }
  return true;
}

const DELETION_ITEM_STATUSES = new Set<AccountDeletionItemStatus>([
  "PENDING",
  "IN_PROGRESS",
  "EXTERNAL_PENDING",
  "RETRY_WAIT",
  "LEGAL_HOLD",
  "FAILED",
  "COMPLETED",
  "NOT_APPLICABLE"
]);

function validDeletionItem(
  value: unknown,
  definition: typeof ACCOUNT_DELETION_INVENTORY[number],
  acceptedAt: string
): value is DeletionItem {
  const item = objectValue(value);
  if (
    !item ||
    !Object.keys(item).every((key) => [
      "key",
      "status",
      "item_revision",
      "due_at",
      "updated_at",
      "evidence_ref",
      "retry_after",
      "restriction_reason",
      "legal_hold_review_at",
      "legal_hold_contact",
      "terminal_at"
    ].includes(key)) ||
    !["key", "status", "item_revision", "due_at", "updated_at"].every(
      (key) => Object.hasOwn(item, key)
    ) ||
    item.key !== definition.key ||
    typeof item.status !== "string" ||
    !DELETION_ITEM_STATUSES.has(item.status as AccountDeletionItemStatus) ||
    !safeInteger(item.item_revision, 1) ||
    !validIsoInstant(item.due_at) ||
    item.due_at !== new Date(
      Date.parse(acceptedAt) + definition.dueAfterMs
    ).toISOString() ||
    !validIsoInstant(item.updated_at) ||
    ("evidence_ref" in item && (
      typeof item.evidence_ref !== "string" ||
      !SHA256.test(item.evidence_ref)
    )) ||
    ("retry_after" in item && !validIsoInstant(item.retry_after)) ||
    ("restriction_reason" in item && (
      typeof item.restriction_reason !== "string" ||
      item.restriction_reason.trim().length === 0
    )) ||
    ("legal_hold_review_at" in item && !validIsoInstant(item.legal_hold_review_at)) ||
    ("legal_hold_contact" in item && (
      typeof item.legal_hold_contact !== "string" ||
      item.legal_hold_contact.trim().length < 3 ||
      item.legal_hold_contact.length > 256
    )) ||
    ("terminal_at" in item && !validIsoInstant(item.terminal_at))
  ) {
    return false;
  }
  if (
    ["COMPLETED", "NOT_APPLICABLE", "FAILED"].includes(item.status) &&
    (!Object.hasOwn(item, "evidence_ref") || !Object.hasOwn(item, "terminal_at"))
  ) {
    return false;
  }
  if (item.status === "RETRY_WAIT" && !Object.hasOwn(item, "retry_after")) return false;
  if (
    item.status === "LEGAL_HOLD" &&
    (
      !Object.hasOwn(item, "restriction_reason") ||
      !Object.hasOwn(item, "legal_hold_review_at") ||
      !Object.hasOwn(item, "legal_hold_contact")
    )
  ) {
    return false;
  }
  if (
    !["COMPLETED", "NOT_APPLICABLE", "FAILED"].includes(item.status) &&
    (Object.hasOwn(item, "evidence_ref") || Object.hasOwn(item, "terminal_at"))
  ) {
    return false;
  }
  if (item.status !== "RETRY_WAIT" && Object.hasOwn(item, "retry_after")) return false;
  if (
    item.status !== "LEGAL_HOLD" &&
    (
      Object.hasOwn(item, "restriction_reason") ||
      Object.hasOwn(item, "legal_hold_review_at") ||
      Object.hasOwn(item, "legal_hold_contact")
    )
  ) {
    return false;
  }
  return true;
}

function validLedger(value: unknown): value is PrivacyLedger {
  const ledger = objectValue(value);
  if (
    !ledger ||
    ledger.schema_version !== LEDGER_SCHEMA_VERSION ||
    !objectValue(ledger.actors) ||
    !objectValue(ledger.requests) ||
    !validEventChain(ledger.events)
  ) {
    return false;
  }
  const actors = ledger.actors as Record<string, unknown>;
  const requests = ledger.requests as Record<string, unknown>;
  for (const [digest, actorValue] of Object.entries(
    actors
  )) {
    const actor = objectValue(actorValue);
    if (
      !SHA256.test(digest) ||
      !actor ||
      !exactKeys(actor, [
        "next_generation",
        "active_generation",
        "processing_epoch",
        "tombstones"
      ]) ||
      !safeInteger(actor.next_generation, 1) ||
      !(actor.active_generation === null || safeInteger(actor.active_generation, 1)) ||
      !safeInteger(actor.processing_epoch, 1) ||
      !Array.isArray(actor.tombstones) ||
      actor.tombstones.length > 1
    ) {
      return false;
    }
    for (const tombstoneValue of actor.tombstones) {
      const tombstone = objectValue(tombstoneValue);
      if (
        !tombstone ||
        !exactKeys(tombstone, ["generation", "request_id", "tombstoned_at"]) ||
        !safeInteger(tombstone.generation, 1) ||
        typeof tombstone.request_id !== "string" ||
        !REQUEST_ID.test(tombstone.request_id) ||
        !validIsoInstant(tombstone.tombstoned_at)
      ) {
        return false;
      }
    }
    if (actor.active_generation === null) {
      const tombstone = actor.tombstones[0] as Record<string, unknown> | undefined;
      if (
        !tombstone ||
        actor.next_generation !== nextAccountGeneration(tombstone.generation as number) ||
        actor.processing_epoch !== 2
      ) {
        return false;
      }
    } else if (
      actor.tombstones.length !== 0 ||
      actor.next_generation !== nextAccountGeneration(actor.active_generation) ||
      actor.processing_epoch !== 1
    ) {
      return false;
    }
  }
  for (const [requestId, requestValue] of Object.entries(
    requests
  )) {
    const request = objectValue(requestValue);
    if (
      !REQUEST_ID.test(requestId) ||
      !request ||
      !exactKeys(request, [
        "request_id",
        "request_body_sha256",
        "status_secret_sha256",
        "actor_recovery",
        "actor_sha256",
        "account_generation",
        "processing_epoch",
        "client_revision",
        "request_revision",
        "accepted_at",
        "updated_at",
        "items",
        "operations"
      ]) ||
      request.request_id !== requestId ||
      typeof request.request_body_sha256 !== "string" ||
      !SHA256.test(request.request_body_sha256) ||
      typeof request.status_secret_sha256 !== "string" ||
      !SHA256.test(request.status_secret_sha256) ||
      !validActorRecovery(request.actor_recovery) ||
      typeof request.actor_sha256 !== "string" ||
      !SHA256.test(request.actor_sha256) ||
      !safeInteger(request.account_generation, 1) ||
      !safeInteger(request.processing_epoch, 1) ||
      !safeInteger(request.client_revision, 1) ||
      !safeInteger(request.request_revision, 1) ||
      !validIsoInstant(request.accepted_at) ||
      !validIsoInstant(request.updated_at) ||
      !Array.isArray(request.items) ||
      request.items.length !== ACCOUNT_DELETION_INVENTORY.length ||
      !objectValue(request.operations)
    ) {
      return false;
    }
    if (!ACCOUNT_DELETION_INVENTORY.every((definition, index) =>
      validDeletionItem(
        (request.items as unknown[])[index],
        definition,
        request.accepted_at as string
      )
    )) {
      return false;
    }
    const operations = request.operations as Record<string, unknown>;
    if (
      Object.keys(operations).length !== (request.request_revision as number) - 1
    ) {
      return false;
    }
    const operationRevisions = new Set<number>();
    for (const [operationId, operationValue] of Object.entries(operations)) {
      const operation = objectValue(operationValue);
      if (
        !OPERATION_ID.test(operationId) ||
        !operation ||
        !exactKeys(operation, [
          "payload_sha256",
          "request_revision",
          "item_revision",
          "item_key"
        ]) ||
        typeof operation.payload_sha256 !== "string" ||
        !SHA256.test(operation.payload_sha256) ||
        !safeInteger(operation.request_revision, 2) ||
        operation.request_revision > (request.request_revision as number) ||
        !safeInteger(operation.item_revision, 2) ||
        !ACCOUNT_DELETION_INVENTORY.some(
          (definition) => definition.key === operation.item_key
        )
      ) {
        return false;
      }
      operationRevisions.add(operation.request_revision);
    }
    if (operationRevisions.size !== Object.keys(operations).length) return false;
    const actor = objectValue(actors[request.actor_sha256 as string]);
    const tombstone = actor?.tombstones as unknown[] | undefined;
    if (
      !actor ||
      actor.active_generation !== null ||
      actor.processing_epoch !== request.processing_epoch ||
      !tombstone?.some((entry) => {
        const record = objectValue(entry);
        return (
          record?.request_id === requestId &&
          record.generation === request.account_generation &&
          record.tombstoned_at === request.accepted_at
        );
      })
    ) {
      return false;
    }
  }
  for (const [digest, actorValue] of Object.entries(actors)) {
    const actor = actorValue as ActorLedger;
    for (const tombstone of actor.tombstones) {
      const request = objectValue(requests[tombstone.request_id]);
      if (
        !request ||
        request.actor_sha256 !== digest ||
        request.account_generation !== tombstone.generation
      ) {
        return false;
      }
    }
  }
  const events = ledger.events as LedgerEvent[];
  if (events.length === 0) {
    return Object.keys(actors).length === 0 && Object.keys(requests).length === 0;
  }
  if (
    events.at(-1)?.state_sha256 !== ledgerStateDigest({
      actors: actors as Record<string, ActorLedger>,
      requests: requests as Record<string, DeletionRequest>
    })
  ) {
    return false;
  }
  return true;
}

export function validPrivacyLedgerStateForMaintenance(value: unknown): boolean {
  return validLedger(value);
}

function readLedger(): PrivacyLedger {
  ensureDirectory();
  try {
    const target = ledgerPath();
    const decoded = decryptGatewayStateJson(
      { kind: "privacy-rights-ledger", recordId: path.basename(target) },
      readSecureFile(target, maxGatewayStateEnvelopeBytes(PRIVACY_LEDGER_MAX_BYTES)),
      PRIVACY_LEDGER_MAX_BYTES
    ).value;
    if (!validLedger(decoded)) {
      throw new PrivacyRightsLedgerError("integrity", "privacy ledger validation failed");
    }
    return decoded;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return emptyLedger();
    if (error instanceof PrivacyRightsLedgerError) throw error;
    throw new PrivacyRightsLedgerError("integrity", "privacy ledger cannot be read");
  }
}

function appendEvent(
  ledger: PrivacyLedger,
  type: string,
  occurredAt: string,
  payload: Record<string, unknown>
): void {
  const previous = ledger.events.at(-1)?.event_sha256 ?? null;
  const withoutDigest = {
    sequence: ledger.events.length + 1,
    occurred_at: occurredAt,
    type,
    payload,
    previous_sha256: previous,
    state_sha256: ledgerStateDigest(ledger)
  };
  ledger.events.push({
    ...withoutDigest,
    event_sha256: sha256(
      `walksafe-privacy-ledger-event\0${canonicalJson(withoutDigest)}`
    )
  });
}

function writeLedger(ledger: PrivacyLedger): void {
  ensureDirectory();
  const plaintext = `${JSON.stringify(ledger)}\n`;
  if (Buffer.byteLength(plaintext) > PRIVACY_LEDGER_MAX_BYTES) {
    throw new PrivacyRightsLedgerError("integrity", "privacy ledger size limit exceeded");
  }
  const target = ledgerPath();
  const encoded = encryptGatewayStateJson(
    { kind: "privacy-rights-ledger", recordId: path.basename(target) },
    ledger,
    PRIVACY_LEDGER_MAX_BYTES
  );
  const temporary = path.join(
    stateDirectory(),
    `.ledger-${process.pid}-${randomBytes(12).toString("hex")}.tmp`
  );
  let descriptor: number | null = null;
  try {
    descriptor = openSync(
      temporary,
      fsConstants.O_CREAT |
        fsConstants.O_EXCL |
        fsConstants.O_WRONLY |
        (fsConstants.O_NOFOLLOW ?? 0),
      0o600
    );
    writeFileSync(descriptor, encoded, "utf8");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    renameSync(temporary, target);
    const directoryDescriptor = openSync(
      stateDirectory(),
      fsConstants.O_RDONLY | (fsConstants.O_DIRECTORY ?? 0)
    );
    try {
      fsyncSync(directoryDescriptor);
    } finally {
      closeSync(directoryDescriptor);
    }
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    rmSync(temporary, { force: true });
  }
}

type LedgerLock = {
  descriptor: number;
};

function acquireLock(): LedgerLock {
  ensureDirectory();
  const file = path.join(stateDirectory(), "ledger.lock");
  let descriptor: number | null = null;
  try {
    descriptor = openSync(
      file,
      fsConstants.O_CREAT |
        fsConstants.O_RDWR |
        (fsConstants.O_NOFOLLOW ?? 0),
      0o600
    );
    const metadata = fstatSync(descriptor, { bigint: true });
    const pathname = lstatSync(file, { bigint: true });
    if (
      !metadata.isFile() ||
      !pathname.isFile() ||
      pathname.isSymbolicLink() ||
      metadata.dev !== pathname.dev ||
      metadata.ino !== pathname.ino ||
      (metadata.mode & 0o777n) !== 0o600n ||
      (pathname.mode & 0o777n) !== 0o600n
    ) {
      throw new PrivacyRightsLedgerError(
        "integrity",
        "privacy ledger lock inode is unsafe"
      );
    }
    const flock = spawnSync(
      "/usr/bin/flock",
      [
        "--exclusive",
        "--timeout",
        String(LOCK_WAIT_MS / 1_000),
        "3"
      ],
      {
        stdio: ["ignore", "ignore", "ignore", descriptor],
        timeout: LOCK_WAIT_MS + 1_000,
        killSignal: "SIGKILL"
      }
    );
    if (flock.error) {
      const code = (flock.error as NodeJS.ErrnoException).code;
      if (code === "ETIMEDOUT") {
        throw new PrivacyRightsLedgerError("busy", "privacy ledger is busy");
      }
      throw new PrivacyRightsLedgerError(
        "integrity",
        "required /usr/bin/flock lock helper is unavailable"
      );
    }
    if (flock.status !== 0) {
      if (flock.status === 1) {
        throw new PrivacyRightsLedgerError("busy", "privacy ledger is busy");
      }
      throw new PrivacyRightsLedgerError(
        "integrity",
        "privacy ledger flock acquisition failed"
      );
    }
    const lock = { descriptor };
    descriptor = null;
    return lock;
  } catch (error) {
    if (error instanceof PrivacyRightsLedgerError) throw error;
    throw new PrivacyRightsLedgerError(
      "integrity",
      "privacy ledger lock storage failed"
    );
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

function releaseLock(lock: LedgerLock): void {
  closeSync(lock.descriptor);
}

function assertLockTestHook(): void {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("privacy ledger lock test hook is unavailable");
  }
}

export function crashAfterAcquiringPrivacyLedgerLockForTests(): never {
  assertLockTestHook();
  acquireLock();
  process.kill(process.pid, "SIGKILL");
  while (true) Atomics.wait(sleepArray, 0, 0, 60_000);
}

export function holdPrivacyLedgerFlockForTests(): never {
  assertLockTestHook();
  acquireLock();
  writeSync(1, "privacy-ledger-flock-ready\n", null, "utf8");
  while (true) Atomics.wait(sleepArray, 0, 0, 60_000);
}

function mutateLedger<T>(mutator: (ledger: PrivacyLedger) => {
  value: T;
  changed: boolean;
}): T {
  const lock = acquireLock();
  try {
    const ledger = readLedger();
    const result = mutator(ledger);
    if (result.changed) {
      if (!validLedger(ledger)) {
        throw new PrivacyRightsLedgerError(
          "integrity",
          "next privacy ledger state failed validation"
        );
      }
      writeLedger(ledger);
    }
    return result.value;
  } finally {
    releaseLock(lock);
  }
}

function equalSecret(storedDigest: string, suppliedSecret: string): boolean {
  if (!STATUS_SECRET.test(suppliedSecret)) return false;
  const supplied = statusSecretDigest(suppliedSecret);
  return timingSafeEqual(
    Buffer.from(storedDigest, "hex"),
    Buffer.from(supplied, "hex")
  );
}

function overallStatus(items: readonly DeletionItem[]): AccountDeletionOverallStatus {
  if (items.every((item) =>
    item.status === "COMPLETED" || item.status === "NOT_APPLICABLE"
  )) {
    return "COMPLETED";
  }
  if (items.some((item) => item.status === "LEGAL_HOLD")) return "RESTRICTED";
  if (items.some((item) => item.status === "FAILED")) return "FAILED";
  if (items.some((item) => item.status === "RETRY_WAIT")) return "RETRY_WAIT";
  return "PROCESSING";
}

function statusView(request: DeletionRequest): AccountDeletionStatus {
  return {
    schema_version: "walksafe.account-deletion-status.v1",
    request_id: request.request_id,
    request_revision: request.request_revision,
    client_revision: request.client_revision,
    accepted_at: request.accepted_at,
    updated_at: request.updated_at,
    account_generation: request.account_generation,
    processing_epoch: request.processing_epoch,
    overall_status: overallStatus(request.items),
    items: request.items.map((item) => ({ ...item }))
  };
}

export function validAccountDeletionStatusSecret(secret: string): boolean {
  return STATUS_SECRET.test(secret);
}

export function parseAccountDeletionRequest(
  value: unknown
): AccountDeletionRequestInput | null {
  const input = objectValue(value);
  if (
    !input ||
    Object.keys(input).length !== 4 ||
    input.schema_version !== ACCOUNT_DELETION_REQUEST_SCHEMA ||
    typeof input.request_id !== "string" ||
    !REQUEST_ID.test(input.request_id) ||
    !safeInteger(input.client_revision, 1) ||
    input.confirmation !== "DELETE_MY_ACCOUNT"
  ) {
    return null;
  }
  return {
    schema_version: ACCOUNT_DELETION_REQUEST_SCHEMA,
    request_id: input.request_id,
    client_revision: input.client_revision,
    confirmation: "DELETE_MY_ACCOUNT"
  };
}

export function activateActorGeneration(
  actorId: string,
  nowEpochMs = Date.now()
): number {
  const occurredAt = isoInstantFromEpoch(nowEpochMs);
  if (!PRIVACY_ACTOR_ID.test(actorId) || !occurredAt) {
    throw new PrivacyRightsLedgerError(
      "conflict",
      "actor generation input is invalid"
    );
  }
  return mutateLedger((ledger) => {
    const digest = actorDigest(actorId);
    const existing = ledger.actors[digest];
    if (existing) {
      if (existing.active_generation !== null) {
        return { value: existing.active_generation, changed: false };
      }
      throw new PrivacyRightsLedgerError(
        "inactive",
        "a tombstoned actor cannot start a new generation"
      );
    }
    const generation = 1;
    const actor: ActorLedger = {
      next_generation: 1,
      active_generation: null,
      processing_epoch: 1,
      tombstones: []
    };
    actor.active_generation = generation;
    actor.next_generation = generation + 1;
    ledger.actors[digest] = actor;
    appendEvent(ledger, "ACTOR_GENERATION_ACTIVATED", occurredAt, {
      actor_sha256: digest,
      account_generation: generation,
      processing_epoch: actor.processing_epoch
    });
    return { value: generation, changed: true };
  });
}

export function bindBackendActorGeneration(
  actorId: string,
  accountGeneration: number,
  nowEpochMs = Date.now()
): number {
  const occurredAt = isoInstantFromEpoch(nowEpochMs);
  if (
    !PRIVACY_ACTOR_ID.test(actorId) ||
    !safeInteger(accountGeneration, 1) ||
    !occurredAt
  ) {
    throw new PrivacyRightsLedgerError(
      "conflict",
      "backend actor generation input is invalid"
    );
  }
  return mutateLedger((ledger) => {
    const digest = actorDigest(actorId);
    const existing = ledger.actors[digest];
    if (existing) {
      if (existing.active_generation === accountGeneration) {
        return { value: accountGeneration, changed: false };
      }
      throw new PrivacyRightsLedgerError(
        existing.active_generation === null ? "inactive" : "conflict",
        "backend account generation does not match the gateway ledger"
      );
    }
    ledger.actors[digest] = {
      next_generation: nextAccountGeneration(accountGeneration),
      active_generation: accountGeneration,
      processing_epoch: 1,
      tombstones: []
    };
    appendEvent(ledger, "BACKEND_ACTOR_GENERATION_BOUND", occurredAt, {
      actor_sha256: digest,
      account_generation: accountGeneration,
      processing_epoch: 1
    });
    return { value: accountGeneration, changed: true };
  });
}

export function currentActorGeneration(actorId: string): number | null {
  const actor = readLedger().actors[actorDigest(actorId)];
  return actor?.active_generation ?? null;
}

export function beginPrivacyOperation(actorId: string): PrivacyOperationLease | null {
  const digest = actorDigest(actorId);
  const actor = readLedger().actors[digest];
  if (!actor || actor.active_generation === null) return null;
  const lease: PrivacyOperationLease = {
    actorId,
    actorSha256: digest,
    accountGeneration: actor.active_generation,
    processingEpoch: actor.processing_epoch,
    controller: new AbortController(),
    token: randomBytes(24).toString("hex")
  };
  activeOperations.set(lease.token, lease);
  return lease;
}

export function revalidatePrivacyOperation(lease: PrivacyOperationLease): boolean {
  if (lease.controller.signal.aborted) return false;
  const actor = readLedger().actors[lease.actorSha256];
  return (
    actor?.active_generation === lease.accountGeneration &&
    actor.processing_epoch === lease.processingEpoch
  );
}

export function finishPrivacyOperation(lease: PrivacyOperationLease): void {
  activeOperations.delete(lease.token);
}

export function activePrivacyOperationCountForTests(): number {
  return activeOperations.size;
}

export function consentActorBindingId(lease: PrivacyOperationLease): string {
  return `${lease.actorId}\0generation:${lease.accountGeneration}`;
}

function abortActorOperations(actorSha256: string, generation: number): void {
  for (const lease of activeOperations.values()) {
    if (
      lease.actorSha256 === actorSha256 &&
      lease.accountGeneration === generation
    ) {
      lease.controller.abort(new Error("account_generation_tombstoned"));
    }
  }
}

export function abortPrivacyOperationsForAccountDeletion(
  actorId: string,
  accountGeneration: number
): void {
  abortActorOperations(actorDigest(actorId), accountGeneration);
}

export function probeAccountDeletionRequest(
  requestId: string,
  statusSecret: string,
  requestBodySha256: string
):
  | { kind: "new" }
  | { kind: "not_found" }
  | { kind: "conflict" }
  | { kind: "replay"; status: AccountDeletionStatus } {
  if (!REQUEST_ID.test(requestId) || !STATUS_SECRET.test(statusSecret)) {
    return { kind: "not_found" };
  }
  const request = readLedger().requests[requestId];
  if (!request) return { kind: "new" };
  if (!equalSecret(request.status_secret_sha256, statusSecret)) {
    return { kind: "not_found" };
  }
  if (request.request_body_sha256 !== requestBodySha256) {
    return { kind: "conflict" };
  }
  return { kind: "replay", status: statusView(request) };
}

export function acceptAccountDeletionRequest(
  actorId: string,
  input: AccountDeletionRequestInput,
  statusSecret: string,
  nowEpochMs = Date.now()
):
  | { kind: "accepted"; status: AccountDeletionStatus }
  | { kind: "replay"; status: AccountDeletionStatus }
  | { kind: "not_found" }
  | { kind: "conflict" }
  | { kind: "inactive" } {
  const occurredAt = isoInstantFromEpoch(nowEpochMs);
  const dueAt = ACCOUNT_DELETION_INVENTORY.map((definition) =>
    isoInstantFromEpoch(nowEpochMs + definition.dueAfterMs)
  );
  if (
    !PRIVACY_ACTOR_ID.test(actorId) ||
    !parseAccountDeletionRequest(input) ||
    !STATUS_SECRET.test(statusSecret) ||
    !occurredAt ||
    dueAt.some((value) => value === null)
  ) {
    throw new PrivacyRightsLedgerError(
      "conflict",
      "account deletion mutation input is invalid"
    );
  }
  const bodySha256 = accountDeletionRequestBodySha256(input);
  type MutationOutcome =
    | {
        kind: "accepted";
        status: AccountDeletionStatus;
        actorSha256: string;
        generation: number;
      }
    | { kind: "replay"; status: AccountDeletionStatus }
    | { kind: "not_found" }
    | { kind: "conflict" }
    | { kind: "inactive" };
  const outcome = mutateLedger<MutationOutcome>((ledger) => {
    const existing = ledger.requests[input.request_id];
    if (existing) {
      if (!equalSecret(existing.status_secret_sha256, statusSecret)) {
        return { value: { kind: "not_found" } as const, changed: false };
      }
      if (existing.request_body_sha256 !== bodySha256) {
        return { value: { kind: "conflict" } as const, changed: false };
      }
      return {
        value: { kind: "replay", status: statusView(existing) } as const,
        changed: false
      };
    }
    const digest = actorDigest(actorId);
    const actor = ledger.actors[digest];
    if (!actor || actor.active_generation === null) {
      return { value: { kind: "inactive" } as const, changed: false };
    }
    const generation = actor.active_generation;
    actor.active_generation = null;
    actor.processing_epoch += 1;
    actor.tombstones.push({
      generation,
      request_id: input.request_id,
      tombstoned_at: occurredAt
    });
    const request: DeletionRequest = {
      request_id: input.request_id,
      request_body_sha256: bodySha256,
      status_secret_sha256: statusSecretDigest(statusSecret),
      actor_recovery: encryptActorRecovery(actorId, statusSecret),
      actor_sha256: digest,
      account_generation: generation,
      processing_epoch: actor.processing_epoch,
      client_revision: input.client_revision,
      request_revision: 1,
      accepted_at: occurredAt,
      updated_at: occurredAt,
      items: ACCOUNT_DELETION_INVENTORY.map((definition, index) => ({
        key: definition.key,
        status: "EXTERNAL_PENDING",
        item_revision: 1,
        due_at: dueAt[index]!,
        updated_at: occurredAt
      })),
      operations: {}
    };
    ledger.requests[input.request_id] = request;
    appendEvent(ledger, "ACCOUNT_GENERATION_TOMBSTONED", occurredAt, {
      actor_sha256: digest,
      account_generation: generation,
      processing_epoch: actor.processing_epoch,
      request_id: input.request_id
    });
    appendEvent(ledger, "ACCOUNT_DELETION_ACCEPTED", occurredAt, {
      actor_sha256: digest,
      account_generation: generation,
      processing_epoch: actor.processing_epoch,
      request_id: input.request_id,
      request_revision: request.request_revision
    });
    return {
      value: {
        kind: "accepted",
        status: statusView(request),
        actorSha256: digest,
        generation
      } as const,
      changed: true
    };
  });
  if (outcome.kind === "accepted") {
    abortActorOperations(outcome.actorSha256, outcome.generation);
    return { kind: "accepted", status: outcome.status };
  }
  return outcome;
}

export function accountDeletionStatus(
  requestId: string,
  statusSecret: string
): AccountDeletionStatus | null {
  if (!REQUEST_ID.test(requestId) || !STATUS_SECRET.test(statusSecret)) return null;
  const request = readLedger().requests[requestId];
  if (!request || !equalSecret(request.status_secret_sha256, statusSecret)) return null;
  return statusView(request);
}

export function accountDeletionRecoveryActor(
  requestId: string,
  statusSecret: string
): string | null {
  if (!REQUEST_ID.test(requestId) || !STATUS_SECRET.test(statusSecret)) return null;
  const request = readLedger().requests[requestId];
  if (!request || !equalSecret(request.status_secret_sha256, statusSecret)) return null;
  const actorId = decryptActorRecovery(request.actor_recovery, statusSecret);
  if (actorDigest(actorId) !== request.actor_sha256) {
    throw new PrivacyRightsLedgerError(
      "integrity",
      "account deletion recovery actor does not match the ledger"
    );
  }
  return actorId;
}

function validateTransitionInput(
  transition: AccountDeletionTransition,
  nowEpochMs: number
): string {
  const input = objectValue(transition);
  const allowed = [
    "requestId",
    "statusSecret",
    "itemKey",
    "operationId",
    "expectedRequestRevision",
    "expectedItemRevision",
    "nextStatus",
    "evidenceRef",
    "retryAfter",
    "restrictionReason",
    "legalHoldReviewAt",
    "legalHoldContact",
    "terminalAt"
  ];
  const required = allowed.slice(0, 7);
  const occurredAt = isoInstantFromEpoch(nowEpochMs);
  if (
    !input ||
    !occurredAt ||
    !Object.keys(input).every((key) => allowed.includes(key)) ||
    !required.every((key) => Object.hasOwn(input, key)) ||
    typeof input.requestId !== "string" ||
    !REQUEST_ID.test(input.requestId) ||
    typeof input.statusSecret !== "string" ||
    !STATUS_SECRET.test(input.statusSecret) ||
    typeof input.itemKey !== "string" ||
    !ACCOUNT_DELETION_INVENTORY.some(
      (definition) => definition.key === input.itemKey
    ) ||
    typeof input.operationId !== "string" ||
    !OPERATION_ID.test(input.operationId) ||
    !safeInteger(input.expectedRequestRevision, 1) ||
    !safeInteger(input.expectedItemRevision, 1) ||
    typeof input.nextStatus !== "string" ||
    !DELETION_ITEM_STATUSES.has(input.nextStatus as AccountDeletionItemStatus)
  ) {
    throw new PrivacyRightsLedgerError(
      "conflict",
      "deletion transition input is invalid"
    );
  }
  const terminal = ["COMPLETED", "NOT_APPLICABLE", "FAILED"].includes(
    input.nextStatus
  );
  if (
    terminal !== (
      typeof input.evidenceRef === "string" &&
      SHA256.test(input.evidenceRef) &&
      validIsoInstant(input.terminalAt) &&
      Date.parse(input.terminalAt) <= nowEpochMs
    ) ||
    (!terminal && (
      Object.hasOwn(input, "evidenceRef") ||
      Object.hasOwn(input, "terminalAt")
    ))
  ) {
    throw new PrivacyRightsLedgerError(
      "conflict",
      "terminal deletion status requires evidence SHA-256 and terminal time"
    );
  }
  if (
    input.nextStatus === "RETRY_WAIT"
      ? !validIsoInstant(input.retryAfter) || Date.parse(input.retryAfter) <= nowEpochMs
      : Object.hasOwn(input, "retryAfter")
  ) {
    throw new PrivacyRightsLedgerError(
      "conflict",
      "retry wait requires a future retry time"
    );
  }
  if (input.nextStatus === "LEGAL_HOLD") {
    if (
      typeof input.restrictionReason !== "string" ||
      input.restrictionReason.trim().length === 0 ||
      input.restrictionReason.length > 2_000 ||
      !validIsoInstant(input.legalHoldReviewAt) ||
      Date.parse(input.legalHoldReviewAt) <= nowEpochMs ||
      typeof input.legalHoldContact !== "string" ||
      input.legalHoldContact.trim().length < 3 ||
      input.legalHoldContact.length > 256
    ) {
      throw new PrivacyRightsLedgerError(
        "conflict",
        "legal hold requires reason, future review, and contact"
      );
    }
  } else if (
    Object.hasOwn(input, "restrictionReason") ||
    Object.hasOwn(input, "legalHoldReviewAt") ||
    Object.hasOwn(input, "legalHoldContact")
  ) {
    throw new PrivacyRightsLedgerError(
      "conflict",
      "legal hold metadata is not valid for this status"
    );
  }
  return occurredAt;
}

export function transitionAccountDeletionItem(
  transition: AccountDeletionTransition,
  nowEpochMs = Date.now()
): AccountDeletionStatus {
  const occurredAt = validateTransitionInput(transition, nowEpochMs);
  return mutateLedger((ledger) => {
    const request = ledger.requests[transition.requestId];
    if (!request || !equalSecret(request.status_secret_sha256, transition.statusSecret)) {
      throw new PrivacyRightsLedgerError("not_found", "deletion request was not found");
    }
    const payloadSha256 = sha256(
      `walksafe-account-deletion-transition\0${canonicalJson(transition)}`
    );
    const prior = request.operations[transition.operationId];
    if (prior) {
      if (prior.payload_sha256 !== payloadSha256) {
        throw new PrivacyRightsLedgerError("conflict", "operation id was reused");
      }
      return { value: statusView(request), changed: false };
    }
    const item = request.items.find((candidate) => candidate.key === transition.itemKey);
    if (
      !item ||
      request.request_revision !== transition.expectedRequestRevision ||
      item.item_revision !== transition.expectedItemRevision
    ) {
      throw new PrivacyRightsLedgerError("conflict", "deletion item CAS failed");
    }
    item.status = transition.nextStatus;
    item.item_revision += 1;
    item.updated_at = occurredAt;
    if (transition.evidenceRef) item.evidence_ref = transition.evidenceRef;
    else delete item.evidence_ref;
    if (transition.retryAfter) item.retry_after = transition.retryAfter;
    else delete item.retry_after;
    if (transition.restrictionReason) {
      item.restriction_reason = transition.restrictionReason;
    } else {
      delete item.restriction_reason;
    }
    if (transition.legalHoldReviewAt) {
      item.legal_hold_review_at = transition.legalHoldReviewAt;
    } else {
      delete item.legal_hold_review_at;
    }
    if (transition.legalHoldContact) {
      item.legal_hold_contact = transition.legalHoldContact;
    } else {
      delete item.legal_hold_contact;
    }
    if (transition.terminalAt) item.terminal_at = transition.terminalAt;
    else delete item.terminal_at;
    request.request_revision += 1;
    request.updated_at = occurredAt;
    request.operations[transition.operationId] = {
      payload_sha256: payloadSha256,
      request_revision: request.request_revision,
      item_revision: item.item_revision,
      item_key: item.key
    };
    appendEvent(ledger, "ACCOUNT_DELETION_ITEM_TRANSITIONED", occurredAt, {
      request_id: request.request_id,
      request_revision: request.request_revision,
      item_key: item.key,
      item_revision: item.item_revision,
      status: item.status,
      operation_id: transition.operationId
    });
    return { value: statusView(request), changed: true };
  });
}

export function assertPrivacyLedgerSecurityForTests(): void {
  const ledger = readLedger();
  if (!validEventChain(ledger.events)) {
    throw new PrivacyRightsLedgerError("integrity", "privacy ledger hash chain failed");
  }
  const metadata = statSync(ledgerPath());
  if (
    (metadata.mode & 0o077) !== 0 ||
    metadata.size > maxGatewayStateEnvelopeBytes(PRIVACY_LEDGER_MAX_BYTES)
  ) {
    throw new PrivacyRightsLedgerError("integrity", "privacy ledger permissions failed");
  }
}
