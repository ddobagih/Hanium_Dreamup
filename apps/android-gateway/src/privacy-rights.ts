import { timingSafeEqual, randomBytes } from "node:crypto";
import { statSync } from "node:fs";
import { maxGatewayStateEnvelopeBytes } from "./encrypted-json-store.js";
import {
  type AccountDeletionItemKey,
  type AccountDeletionItemStatus,
  sha256,
  type DeletionItem,
  type DeletionRequest,
  PrivacyRightsLedgerError,
  type ActorLedger,
  appendEvent,
  safeInteger,
  nextAccountGeneration,
  REQUEST_ID,
  type AccountDeletionRequestInput,
  ACCOUNT_DELETION_INVENTORY,
  parseAccountDeletionRequest,
  accountDeletionRequestBodySha256,
  encryptActorRecovery,
  decryptActorRecovery,
  objectValue,
  OPERATION_ID,
  DELETION_ITEM_STATUSES,
  SHA256,
  validIsoInstant,
  canonicalJson,
  validEventChain
} from "./privacy-rights-codec.js";
import {
  mutateLedger,
  mutateLedgerAsync,
  type LedgerMutator,
  readLedger,
  ledgerPath,
  PRIVACY_LEDGER_MAX_BYTES
} from "./privacy-rights-store.js";

export const ACCOUNT_DELETION_CONTROL = "account-deletion";

export const ACCOUNT_DELETION_SECRET_HEADER =
  "x-walksafe-account-deletion-status-secret";

const PRIVACY_ACTOR_ID = /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/;

const STATUS_SECRET = /^[0-9a-f]{64}$/;

type AccountDeletionOverallStatus =
  | "PROCESSING"
  | "RETRY_WAIT"
  | "RESTRICTED"
  | "FAILED"
  | "COMPLETED";

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

const activeOperations = new Map<string, PrivacyOperationLease>();

function actorDigest(actorId: string): string {
  return sha256(`walksafe-privacy-actor\0${actorId}`);
}

function statusSecretDigest(secret: string): string {
  return sha256(`walksafe-account-deletion-status-secret\0${secret}`);
}

function isoInstantFromEpoch(value: number): string | null {
  if (!Number.isSafeInteger(value) || value < 0) return null;
  try {
    return new Date(value).toISOString();
  } catch {
    return null;
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

export function activateActorGeneration(
  actorId: string,
  nowEpochMs = Date.now()
): number {
  return mutateLedger(activateActorGenerationMutator(actorId, nowEpochMs));
}

/** S19-25: route-facing form that never blocks the event loop on lock contention. */
export async function activateActorGenerationAsync(
  actorId: string,
  nowEpochMs = Date.now()
): Promise<number> {
  return mutateLedgerAsync(activateActorGenerationMutator(actorId, nowEpochMs));
}

function activateActorGenerationMutator(
  actorId: string,
  nowEpochMs: number
): LedgerMutator<number> {
  const occurredAt = isoInstantFromEpoch(nowEpochMs);
  if (!PRIVACY_ACTOR_ID.test(actorId) || !occurredAt) {
    throw new PrivacyRightsLedgerError(
      "conflict",
      "actor generation input is invalid"
    );
  }
  return (ledger) => {
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
  };
}

export function bindBackendActorGeneration(
  actorId: string,
  accountGeneration: number,
  nowEpochMs = Date.now()
): number {
  return mutateLedger(bindBackendActorGenerationMutator(actorId, accountGeneration, nowEpochMs));
}

/** S19-25: route-facing form that never blocks the event loop on lock contention. */
export async function bindBackendActorGenerationAsync(
  actorId: string,
  accountGeneration: number,
  nowEpochMs = Date.now()
): Promise<number> {
  return mutateLedgerAsync(
    bindBackendActorGenerationMutator(actorId, accountGeneration, nowEpochMs)
  );
}

function bindBackendActorGenerationMutator(
  actorId: string,
  accountGeneration: number,
  nowEpochMs: number
): LedgerMutator<number> {
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
  return (ledger) => {
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
  };
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

export {
  ACCOUNT_DELETION_REQUEST_SCHEMA,
  ACCOUNT_DELETION_INVENTORY,
  type AccountDeletionItemKey,
  type AccountDeletionItemStatus,
  type AccountDeletionRequestInput,
  PrivacyRightsLedgerError,
  accountDeletionRequestBodySha256,
  validPrivacyLedgerStateForMaintenance,
  parseAccountDeletionRequest
} from "./privacy-rights-codec.js";
export {
  PRIVACY_LEDGER_MAX_BYTES,
  crashAfterAcquiringPrivacyLedgerLockForTests,
  holdPrivacyLedgerFlockForTests
} from "./privacy-rights-store.js";
