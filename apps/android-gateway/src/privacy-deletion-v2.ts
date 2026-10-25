import { gatewayDeletionCapabilityKeyId } from "./encrypted-json-store.js";
import { randomBytes } from "node:crypto";
import {
  ACTOR_ID,
  safePositiveInteger,
  actorDigest,
  type AccountDeletionStatusV2,
  type AccountDeletionRequestV2,
  parseAccountDeletionRequestV2,
  validDeletionAccessSecretV2,
  PrivacyDeletionV2Error,
  accountDeletionRequestBodyV2,
  sha256,
  capabilityMatches,
  refreshCapabilityKey,
  preAccessDigest,
  type DeletionRecord,
  type DeviceDeletionEvidenceV2,
  parseDeviceDeletionEvidenceV2,
  deviceDeletionEvidenceBodyV2,
  evidenceBudgetCounts,
  MAX_EVIDENCE_OPERATIONS_PER_REQUEST,
  MAX_OUTBOX_OPERATIONS_PER_REQUEST
} from "./privacy-deletion-codec.js";
import {
  fenceFromStores,
  readLedger,
  mutateLedger,
  authorizedAccountDeletionStatusV2
} from "./privacy-deletion-store.js";

export const DELETION_ACCESS_SECRET_HEADER =
  "x-walksafe-deletion-access-secret" as const;

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

export {
  ACCOUNT_DELETION_REQUEST_SCHEMA_V2,
  ACCOUNT_DELETION_STATUS_SCHEMA_V2,
  DEVICE_DELETION_EVIDENCE_SCHEMA_V2,
  ACCOUNT_DELETION_TERMINAL_CONFLICT_CODES_V2,
  ACCOUNT_DELETION_INVENTORY_V2,
  type AccountDeletionItemKeyV2,
  type AccountDeletionItemStatusV2,
  type AccountDeletionOverallStatusV2,
  type AccountDeletionRequestV2,
  type AccountDeletionItemV2,
  type AccountDeletionStatusV2,
  type DeviceDeletionEvidenceResultV2,
  type DeviceDeletionEvidenceV2,
  type PausedAccountDeletionOutboxOperationV2,
  PrivacyDeletionV2Error,
  validDeletionAccessSecretV2,
  parseAccountDeletionRequestV2,
  accountDeletionRequestBodyV2,
  deviceDeletionEvidenceSha256V2,
  deviceDeletionEvidenceBodyV2,
  parseDeviceDeletionEvidenceV2,
  parseAccountDeletionStatusV2,
  validPrivacyDeletionV2ArchiveRequestForMaintenance,
  validPrivacyDeletionV2ArchiveActorForMaintenance,
  validPrivacyDeletionV2StateForMaintenance
} from "./privacy-deletion-codec.js";
export {
  PRIVACY_DELETION_V2_MAX_BYTES,
  ARCHIVE_ACTOR_MAX_BYTES,
  setPrivacyDeletionV2ArchiveForTests,
  privacyDeletionV2ArchiveReadCountForTests,
  authorizedAccountDeletionStatusV2,
  privacyDeletionV2LedgerPathForTests
} from "./privacy-deletion-store.js";
export {
  ACCOUNT_DELETION_OUTBOX_OPERATION_TIMEOUT_MS,
  forwardAccountDeletionRequestV2,
  forwardDeviceDeletionEvidenceV2,
  refreshBackendAccountDeletionStatusV2,
  accountDeletionOutboxHasPausedOperations,
  drainAccountDeletionOutboxV2,
  listPausedAccountDeletionOutboxV2,
  resumePausedAccountDeletionOutboxV2
} from "./privacy-deletion-outbox.js";
