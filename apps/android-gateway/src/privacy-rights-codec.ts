import { createHash, randomBytes, createCipheriv, createDecipheriv } from "node:crypto";

export const ACCOUNT_DELETION_REQUEST_SCHEMA =
  "walksafe.account-deletion-request.v1";

export const LEDGER_SCHEMA_VERSION = 2;

export const REQUEST_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{15,127}$/;

export const OPERATION_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{15,127}$/;

export const SHA256 = /^[0-9a-f]{64}$/;

const BASE64URL = /^[A-Za-z0-9_-]+$/;

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

type LedgerEvent = {
  sequence: number;
  occurred_at: string;
  type: string;
  payload: Record<string, unknown>;
  previous_sha256: string | null;
  state_sha256: string;
  event_sha256: string;
};

export type ActorLedger = {
  next_generation: number;
  active_generation: number | null;
  processing_epoch: number;
  tombstones: Array<{
    generation: number;
    request_id: string;
    tombstoned_at: string;
  }>;
};

export type DeletionItem = {
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

export type DeletionRequest = {
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

export type PrivacyLedger = {
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

export class PrivacyRightsLedgerError extends Error {
  constructor(
    readonly code: "busy" | "integrity" | "inactive" | "conflict" | "not_found",
    message: string
  ) {
    super(message);
  }
}

export function sha256(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

function actorRecoveryKey(secret: string): Buffer {
  return createHash("sha256")
    .update(`walksafe-account-deletion-actor-recovery\0${secret}`)
    .digest();
}

export function encryptActorRecovery(actorId: string, secret: string): ActorRecovery {
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

export function decryptActorRecovery(recovery: ActorRecovery, secret: string): string {
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

export function canonicalJson(value: unknown): string {
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

export function safeInteger(value: unknown, minimum = 0): value is number {
  return Number.isSafeInteger(value) && (value as number) >= minimum;
}

export function nextAccountGeneration(generation: number): number {
  return generation === Number.MAX_SAFE_INTEGER ? generation : generation + 1;
}

export function objectValue(value: unknown): Record<string, unknown> | null {
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

export function validIsoInstant(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const epoch = Date.parse(value);
  return Number.isFinite(epoch) && new Date(epoch).toISOString() === value;
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

export function validEventChain(events: unknown): events is LedgerEvent[] {
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

export const DELETION_ITEM_STATUSES = new Set<AccountDeletionItemStatus>([
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

export function validLedger(value: unknown): value is PrivacyLedger {
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

export function appendEvent(
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

