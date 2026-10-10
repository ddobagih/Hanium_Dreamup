import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
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
  readdirSync,
  realpathSync,
  renameSync,
  rmSync,
  writeFileSync
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import {
  ACCOUNT_GENERATION_HEADER,
  ACTOR_ASSERTION_HEADER,
  ACTOR_ID_HEADER,
  backendUrl,
  fetchBackend,
  proxyRequestHeaders,
  toBackendResponse,
  type GatewayFetch
} from "./backend.js";
import { readBoundedJsonBody } from "./request-body.js";
import {
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  GatewayStorageOutcomeUnknownError,
  maxGatewayStateEnvelopeBytes,
  syncGatewayStateDirectoryAfterRename
} from "./encrypted-json-store.js";
import {
  KeyedAdmissionQueue,
  QueueAdmissionAbortedError,
  QueueAdmissionBusyError
} from "./keyed-admission-queue.js";
import {
  acquireExclusiveFileLockLeaseAsync,
  ExclusiveFileLockBusyError,
  type FileLockLease
} from "./exclusive-file-lock.js";

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

const INSTALLATION_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/;
const REQUEST_ID = /^[A-Za-z0-9_-]{16,128}$/;
const SHA256 = /^[0-9a-f]{64}$/;
const CONTROL_SECRET = /^[0-9a-f]{64}$/;
const POLICY_VERSION = /^FP-013-[1-9][0-9]*\.[0-9]+\.[0-9]+$/;
const WHOLE_SECOND_INSTANT =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/;
const CONSENT_KEYS = [
  "raw_source_collection",
  "automatic_reporting",
  "mobile_network_transfer",
  "training_reuse"
] as const;
const MAX_EVENTS = 4096;
const NORMAL_MAX_STATE_BYTES = 4 * 1024 * 1024;
const WITHDRAWAL_RESERVE_EVENTS = CONSENT_KEYS.length;
// Existing full ledgers retain their audit chain; only monotone withdrawals
// may consume the bounded reserve, never a new grant or an actor change.
export const INTEGRATED_CONSENT_MAX_STATE_BYTES = NORMAL_MAX_STATE_BYTES + 16 * 1024;
const FILE_LOCK_RETRY_MS = 25;
const FILE_LOCK_MAX_ATTEMPTS = 100;
const installationAdmission = new KeyedAdmissionQueue(32, 1_000);

export type IntegratedConsentKey = typeof CONSENT_KEYS[number];

export type IntegratedConsentItemVersions = {
  raw_source_collection: string;
  automatic_reporting: string;
  mobile_network_transfer: string;
  training_reuse: string;
};

export const INTEGRATED_CONSENT_ITEM_VERSIONS: IntegratedConsentItemVersions =
  Object.freeze({
    raw_source_collection: "FP-013-RAW-1.2.0",
    automatic_reporting: "FP-013-AUTO-1.2.0",
    mobile_network_transfer: "FP-013-MOBILE-1.0.0",
    training_reuse: "FP-013-TRAINING-1.1.0"
  });

const ITEM_VERSION_PATTERNS: Record<IntegratedConsentKey, RegExp> = {
  raw_source_collection: /^FP-013-RAW-[1-9][0-9]*\.[0-9]+\.[0-9]+$/,
  automatic_reporting: /^FP-013-AUTO-[1-9][0-9]*\.[0-9]+\.[0-9]+$/,
  mobile_network_transfer: /^FP-013-MOBILE-[1-9][0-9]*\.[0-9]+\.[0-9]+$/,
  training_reuse: /^FP-013-TRAINING-[1-9][0-9]*\.[0-9]+\.[0-9]+$/
};

export type IntegratedConsentSelections = {
  raw_source_collection: boolean;
  automatic_reporting: boolean;
  mobile_network_transfer: boolean;
  training_reuse: boolean;
};

export type IntegratedConsentConfirmation = {
  schema_version: "walksafe.integrated-consent-confirmation.v2";
  current: true;
  installation_id: string;
  request_id: string;
  policy_version: string;
  item_versions: IntegratedConsentItemVersions;
  client_revision: number;
  revision: number;
  selections: IntegratedConsentSelections;
  confirmed_at: string;
  gateway_audit_record_sha256: string;
  backend_consent_receipt_sha256: string;
};

type LegacyIntegratedConsentEvent = {
  request_id: string;
  policy_version: string;
  item_versions: IntegratedConsentItemVersions;
  client_revision: number;
  revision: number;
  selections: IntegratedConsentSelections;
  confirmed_at: string;
  previous_receipt_sha256: string | null;
  receipt_sha256: string;
};

type IntegratedConsentEventV4 = {
  request_id: string;
  policy_version: string;
  item_versions: IntegratedConsentItemVersions;
  client_revision: number;
  revision: number;
  selections: IntegratedConsentSelections;
  confirmed_at: string;
  previous_gateway_audit_record_sha256: string | null;
  gateway_audit_record_sha256: string;
  backend_consent_receipt_sha256: string;
  backend_recorded_at: string;
  field_actor_binding_sha256: string;
};

type IntegratedConsentEventV5 = IntegratedConsentEventV4 & {
  expected_previous_backend_receipt_sha256: string | null;
};

type AuthoritativeIntegratedConsentEvent =
  | IntegratedConsentEventV4
  | IntegratedConsentEventV5;

type IntegratedConsentEvent =
  | LegacyIntegratedConsentEvent
  | AuthoritativeIntegratedConsentEvent;

type IntegratedConsentState = {
  schema_version: 2 | 3 | 4 | 5 | 6 | 7;
  installation_id: string;
  control_secret_sha256: string;
  field_actor_binding?: {
    actor_sha256: string;
    consent_revision: number;
    bound_at: string;
  } | null;
  events: IntegratedConsentEvent[];
  revision?: number;
  audit_archive?: ConsentAuditArchive;
};

type ConsentAuditArchive = {
  segment_count: number;
  archived_events: number;
  last_segment_sha256: string;
};

type ConsentAuditSegment = {
  schema_version: "walksafe.integrated-consent-audit.v1";
  installation_id: string;
  sequence: number;
  previous_segment_sha256: string | null;
  events: IntegratedConsentEvent[];
};

type ConsentHistoryCursor = {
  revision: number;
  audit: string | null;
  previousClientRevision: number;
  actorRevisions: Map<string, number>;
  authoritative: boolean;
};
type ConsentHistoryQuery = { requestId: string; actorSha256: string };
const archivedRequestState = new WeakMap<IntegratedConsentState, {
  prior: IntegratedConsentEvent | undefined; actorFloor: number;
}>();

type ConsentStorageFaultStage = "archive_open" | "archive_file_fsync" |
  "archive_rename" | "archive_directory_fsync" | "current_file_fsync" | "current_rename";
let storageFaultForTests: ConsentStorageFaultStage | null = null;

/** Test seam for the separate durable archive/current commits; never enabled in production. */
export function setIntegratedConsentStorageFaultForTests(stage: ConsentStorageFaultStage | null): void {
  if (process.env.NODE_ENV === "production") throw new Error("consent storage test faults are disabled");
  storageFaultForTests = stage;
}

function storageFault(stage: ConsentStorageFaultStage): void {
  if (storageFaultForTests === stage) throw new Error(`injected consent ${stage} failure`);
}

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

class IntegratedConsentRequestError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string
  ) {
    super(message);
    this.name = "IntegratedConsentRequestError";
  }
}

function exactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length && actual.every((key, index) => key === wanted[index]);
}

function objectValue(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function selectionsOrNull(value: unknown): IntegratedConsentSelections | null {
  const payload = objectValue(value);
  if (!payload || !exactKeys(payload, CONSENT_KEYS)) return null;
  if (CONSENT_KEYS.some((key) => typeof payload[key] !== "boolean")) return null;
  return {
    raw_source_collection: payload.raw_source_collection as boolean,
    automatic_reporting: payload.automatic_reporting as boolean,
    mobile_network_transfer: payload.mobile_network_transfer as boolean,
    training_reuse: payload.training_reuse as boolean
  };
}

function itemVersionsOrNull(value: unknown): IntegratedConsentItemVersions | null {
  const payload = objectValue(value);
  if (!payload || !exactKeys(payload, CONSENT_KEYS)) return null;
  if (CONSENT_KEYS.some((key) => (
    typeof payload[key] !== "string" ||
    !ITEM_VERSION_PATTERNS[key].test(payload[key] as string)
  ))) {
    return null;
  }
  return {
    raw_source_collection: payload.raw_source_collection as string,
    automatic_reporting: payload.automatic_reporting as string,
    mobile_network_transfer: payload.mobile_network_transfer as string,
    training_reuse: payload.training_reuse as string
  };
}

function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== "object") {
    const encoded = JSON.stringify(value);
    if (encoded === undefined) throw new TypeError("unsupported canonical JSON value");
    return encoded;
  }
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const payload = value as Record<string, unknown>;
  return `{${Object.keys(payload).sort().map(
    (key) => `${JSON.stringify(key)}:${canonicalJson(payload[key])}`
  ).join(",")}}`;
}

function sha256(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

function eventReceipt(
  installationId: string,
  event: Omit<LegacyIntegratedConsentEvent, "receipt_sha256">
): string {
  return sha256(canonicalJson({
    installation_id: installationId,
    ...event
  }));
}

function eventAuditRecord(
  installationId: string,
  event:
    | Omit<IntegratedConsentEventV4, "gateway_audit_record_sha256">
    | Omit<IntegratedConsentEventV5, "gateway_audit_record_sha256">
): string {
  return sha256(canonicalJson({
    installation_id: installationId,
    ...event
  }));
}

function authoritativeEvent(
  event: IntegratedConsentEvent
): event is AuthoritativeIntegratedConsentEvent {
  return "gateway_audit_record_sha256" in event;
}

function eventAuditSha256(event: IntegratedConsentEvent): string {
  return authoritativeEvent(event)
    ? event.gateway_audit_record_sha256
    : event.receipt_sha256;
}

function validIsoInstant(value: unknown): value is string {
  if (typeof value !== "string") return false;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) && new Date(parsed).toISOString() === value;
}

function validWholeSecondInstant(value: unknown): value is string {
  if (typeof value !== "string" || !WHOLE_SECOND_INSTANT.test(value)) return false;
  const parsed = Date.parse(value);
  return Number.isFinite(parsed) &&
    new Date(parsed).toISOString() === `${value.slice(0, -1)}.000Z`;
}

function equalSelections(
  left: IntegratedConsentSelections,
  right: IntegratedConsentSelections
): boolean {
  return CONSENT_KEYS.every((key) => left[key] === right[key]);
}

function equalItemVersions(
  left: IntegratedConsentItemVersions,
  right: IntegratedConsentItemVersions
): boolean {
  return CONSENT_KEYS.every((key) => left[key] === right[key]);
}

export function hasCurrentIntegratedConsentItemVersions(
  confirmationValue: Pick<IntegratedConsentConfirmation, "item_versions">
): boolean {
  return equalItemVersions(
    confirmationValue.item_versions,
    INTEGRATED_CONSENT_ITEM_VERSIONS
  );
}

function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || process.getuid() === uid;
}

function stateParentDirectory(environment: NodeJS.ProcessEnv): string {
  const configured = environment.WALKSAFE_GATEWAY_RATE_LIMIT_DIR?.trim() ?? "";
  const production = process.env.NODE_ENV === "production" || environment.NODE_ENV === "production";
  if (!configured && production) {
    throw new Error("WALKSAFE_GATEWAY_RATE_LIMIT_DIR is required in production");
  }
  const directory = configured || path.join(tmpdir(), "walksafe-gateway-rate-limit-v1");
  if (!path.isAbsolute(directory)) throw new Error("gateway state directory must be absolute");
  if (production && path.resolve(directory) !== directory) {
    throw new Error("gateway state directory must be one canonical absolute path");
  }
  return path.resolve(directory);
}

function secureDirectory(directory: string, context: string): string {
  mkdirSync(directory, { recursive: true, mode: 0o700 });
  const metadata = lstatSync(directory);
  const resolved = realpathSync(directory);
  if (!metadata.isDirectory() || metadata.isSymbolicLink() || resolved !== directory) {
    throw new Error(`${context} must be a real directory without symlinks`);
  }
  if (!ownedByCurrentProcess(metadata.uid) || (metadata.mode & 0o077) !== 0) {
    throw new Error(`${context} must be owned by this process and mode 0700`);
  }
  return directory;
}

function stateDirectory(environment: NodeJS.ProcessEnv = process.env): string {
  const parent = secureDirectory(stateParentDirectory(environment), "gateway state directory");
  return secureDirectory(path.join(parent, "integrated-consent"), "integrated consent directory");
}

function statePath(installationId: string): string {
  const digest = sha256(`integrated-consent\0${installationId}`);
  return path.join(stateDirectory(), `${digest}.json`);
}

/**
 * S19-22: the per-installation consent lock is the shared kernel lock
 * (exclusive-file-lock.ts): flock on the same `.lock` path, released by the kernel when
 * the descriptor closes or the process dies. The file content is never read, so an
 * empty/partial legacy PID record cannot wedge recovery, and a PID can never be mistaken
 * for the owner. A residual legacy `.reaper` hard link leaves the lock with two links and
 * fails closed (503 store_unavailable) until an operator removes it with the service stopped.
 */
type ConsentFileLock = FileLockLease;

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

async function acquireConsentFileLockAtPath(
  lockPath: string
): Promise<ConsentFileLock> {
  for (let attempt = 0; attempt < FILE_LOCK_MAX_ATTEMPTS; attempt += 1) {
    try {
      return await acquireExclusiveFileLockLeaseAsync(lockPath);
    } catch (error) {
      if (!(error instanceof ExclusiveFileLockBusyError)) throw error;
      await delay(FILE_LOCK_RETRY_MS);
    }
  }
  throw new IntegratedConsentRequestError(
    503,
    "integrated_consent_store_busy",
    "Consent storage is busy."
  );
}

async function acquireConsentFileLock(
  installationId: string
): Promise<ConsentFileLock> {
  return acquireConsentFileLockAtPath(`${statePath(installationId)}.lock`);
}

function releaseConsentFileLock(lock: ConsentFileLock): void {
  lock.release();
}

export async function withIntegratedConsentStateFileLockForMaintenance<T>(
  filePath: string,
  action: () => Promise<T> | T,
  environment: NodeJS.ProcessEnv = process.env
): Promise<T> {
  const resolved = path.resolve(filePath);
  if (
    resolved !== filePath ||
    path.dirname(resolved) !== stateDirectory(environment) ||
    !/^[0-9a-f]{64}\.json$/.test(path.basename(resolved))
  ) {
    throw new Error("integrated consent maintenance state path is invalid");
  }
  const lock = await acquireConsentFileLockAtPath(`${resolved}.lock`);
  try {
    return await action();
  } finally {
    releaseConsentFileLock(lock);
  }
}

function validState(
  value: unknown,
  installationId: string,
  fullHistory = false,
  eventOffset = 0,
  initialAudit: string | null = null,
  cursor?: ConsentHistoryCursor
): value is IntegratedConsentState {
  const state = objectValue(value);
  const legacyKeys = [
    "schema_version",
    "installation_id",
    "control_secret_sha256",
    "events"
  ];
  const currentKeys = [...legacyKeys, "field_actor_binding"];
  if (
    !state ||
    !(
      (state.schema_version === 2 && exactKeys(state, legacyKeys)) ||
      ((state.schema_version === 3 ||
        state.schema_version === 4 ||
        state.schema_version === 5 ||
        state.schema_version === 6) &&
        exactKeys(state, currentKeys))
    ) ||
    state.installation_id !== installationId ||
    typeof state.control_secret_sha256 !== "string" ||
    !SHA256.test(state.control_secret_sha256) ||
    !Array.isArray(state.events) ||
    (!fullHistory && state.events.length > MAX_EVENTS + WITHDRAWAL_RESERVE_EVENTS)
  ) {
    return false;
  }
  if (state.schema_version !== 2 && state.field_actor_binding !== null) {
    const binding = objectValue(state.field_actor_binding);
    if (
      !binding ||
      !exactKeys(binding, ["actor_sha256", "consent_revision", "bound_at"]) ||
      typeof binding.actor_sha256 !== "string" ||
      !SHA256.test(binding.actor_sha256) ||
      !Number.isSafeInteger(binding.consent_revision) ||
      binding.consent_revision !== eventOffset + state.events.length ||
      !validIsoInstant(binding.bound_at)
    ) {
      return false;
    }
  }
  let previousAuditRecord: string | null = cursor?.audit ?? initialAudit;
  let previousClientRevision = cursor?.previousClientRevision ?? 0;
  const previousClientRevisionByActor = cursor?.actorRevisions ?? new Map<string, number>();
  let foundAuthoritativeEvent = cursor?.authoritative ?? false;
  for (let index = 0; index < state.events.length; index += 1) {
    const rawEvent = objectValue(state.events[index]);
    if (
      !rawEvent ||
      typeof rawEvent.request_id !== "string" ||
      !REQUEST_ID.test(rawEvent.request_id) ||
      typeof rawEvent.policy_version !== "string" ||
      !POLICY_VERSION.test(rawEvent.policy_version) ||
      !Number.isSafeInteger(rawEvent.client_revision) ||
      (rawEvent.client_revision as number) <= 0 ||
      rawEvent.revision !== eventOffset + index + 1 ||
      !validIsoInstant(rawEvent.confirmed_at)
    ) {
      return false;
    }
    const selections = selectionsOrNull(rawEvent.selections);
    const itemVersions = itemVersionsOrNull(rawEvent.item_versions);
    if (!selections || !itemVersions) return false;
    if ("receipt_sha256" in rawEvent) {
      if (
        foundAuthoritativeEvent ||
        (rawEvent.client_revision as number) <= previousClientRevision ||
        !exactKeys(rawEvent, [
          "request_id",
          "policy_version",
          "item_versions",
          "client_revision",
          "revision",
          "selections",
          "confirmed_at",
          "previous_receipt_sha256",
          "receipt_sha256"
        ]) ||
        rawEvent.previous_receipt_sha256 !== previousAuditRecord ||
        typeof rawEvent.receipt_sha256 !== "string" ||
        !SHA256.test(rawEvent.receipt_sha256)
      ) return false;
      const eventWithoutReceipt: Omit<LegacyIntegratedConsentEvent, "receipt_sha256"> = {
        request_id: rawEvent.request_id,
        policy_version: rawEvent.policy_version,
        item_versions: itemVersions,
        client_revision: rawEvent.client_revision as number,
        revision: rawEvent.revision,
        selections,
        confirmed_at: rawEvent.confirmed_at,
        previous_receipt_sha256:
          rawEvent.previous_receipt_sha256 as string | null
      };
      if (eventReceipt(installationId, eventWithoutReceipt) !== rawEvent.receipt_sha256) {
        return false;
      }
      previousAuditRecord = rawEvent.receipt_sha256;
    } else {
      const hasExpectedPrevious =
        "expected_previous_backend_receipt_sha256" in rawEvent;
      const eventKeys = [
        "request_id",
        "policy_version",
        "item_versions",
        "client_revision",
        "revision",
        "selections",
        "confirmed_at",
        "previous_gateway_audit_record_sha256",
        "gateway_audit_record_sha256",
        "backend_consent_receipt_sha256",
        "backend_recorded_at",
        "field_actor_binding_sha256",
        ...(hasExpectedPrevious
          ? ["expected_previous_backend_receipt_sha256"]
          : [])
      ];
      if (
        (state.schema_version !== 4 &&
          state.schema_version !== 5 &&
          state.schema_version !== 6) ||
        (hasExpectedPrevious &&
          state.schema_version !== 5 &&
          state.schema_version !== 6) ||
        !exactKeys(rawEvent, eventKeys) ||
        rawEvent.previous_gateway_audit_record_sha256 !== previousAuditRecord ||
        typeof rawEvent.gateway_audit_record_sha256 !== "string" ||
        !SHA256.test(rawEvent.gateway_audit_record_sha256) ||
        typeof rawEvent.backend_consent_receipt_sha256 !== "string" ||
        !SHA256.test(rawEvent.backend_consent_receipt_sha256) ||
        !validWholeSecondInstant(rawEvent.backend_recorded_at) ||
        typeof rawEvent.field_actor_binding_sha256 !== "string" ||
        !SHA256.test(rawEvent.field_actor_binding_sha256) ||
        (hasExpectedPrevious &&
          rawEvent.expected_previous_backend_receipt_sha256 !== null &&
          (typeof rawEvent.expected_previous_backend_receipt_sha256 !== "string" ||
            !SHA256.test(rawEvent.expected_previous_backend_receipt_sha256)))
      ) return false;
      const actorClientRevision = previousClientRevisionByActor.get(
        rawEvent.field_actor_binding_sha256
      ) ?? 0;
      if (
        (state.schema_version === 6 &&
          (rawEvent.client_revision as number) <= actorClientRevision) ||
        (state.schema_version !== 6 &&
          (rawEvent.client_revision as number) <= previousClientRevision)
      ) return false;
      const eventWithoutAudit = {
        request_id: rawEvent.request_id,
        policy_version: rawEvent.policy_version,
        item_versions: itemVersions,
        client_revision: rawEvent.client_revision as number,
        revision: rawEvent.revision,
        selections,
        confirmed_at: rawEvent.confirmed_at,
        previous_gateway_audit_record_sha256:
          rawEvent.previous_gateway_audit_record_sha256 as string | null,
        backend_consent_receipt_sha256: rawEvent.backend_consent_receipt_sha256,
        backend_recorded_at: rawEvent.backend_recorded_at,
        field_actor_binding_sha256: rawEvent.field_actor_binding_sha256,
        ...(hasExpectedPrevious
          ? {
              expected_previous_backend_receipt_sha256:
                rawEvent.expected_previous_backend_receipt_sha256 as string | null
            }
          : {})
      };
      if (eventAuditRecord(installationId, eventWithoutAudit) !== rawEvent.gateway_audit_record_sha256) {
        return false;
      }
      previousAuditRecord = rawEvent.gateway_audit_record_sha256;
      previousClientRevisionByActor.set(
        rawEvent.field_actor_binding_sha256,
        rawEvent.client_revision as number
      );
      foundAuthoritativeEvent = true;
    }
    previousClientRevision = rawEvent.client_revision as number;
  }
  if (
    state.schema_version !== 4 &&
    state.schema_version !== 5 &&
    state.schema_version !== 6 &&
    foundAuthoritativeEvent
  ) return false;
  const currentEvent = state.events.at(-1);
  if (
    (state.schema_version === 4 ||
      state.schema_version === 5 ||
      state.schema_version === 6) &&
    currentEvent &&
    authoritativeEvent(currentEvent)
  ) {
    const binding = objectValue(state.field_actor_binding);
    if (
      !binding ||
      binding.actor_sha256 !== currentEvent.field_actor_binding_sha256 ||
      binding.consent_revision !== currentEvent.revision
    ) return false;
  }
  if (cursor) {
    cursor.revision = eventOffset + state.events.length;
    cursor.audit = previousAuditRecord;
    cursor.previousClientRevision = previousClientRevision;
    cursor.authoritative = foundAuthoritativeEvent;
  }
  return true;
}

function auditSegmentPath(filePath: string, sequence: number): string {
  if (!Number.isSafeInteger(sequence) || sequence < 1 || sequence > 99_999_999) {
    throw new Error("consent audit segment sequence is exhausted");
  }
  return `${filePath}.audit-${String(sequence).padStart(8, "0")}`;
}

function auditSegmentDigest(segment: ConsentAuditSegment): string {
  return sha256(canonicalJson(segment));
}

/** Archives use the installation's encryption kind, with distinct authenticated record IDs. */
export function integratedConsentAuditSegmentPaths(
  environment: NodeJS.ProcessEnv = process.env
): Array<{ segmentPath: string; statePath: string }> {
  const directory = stateDirectory(environment);
  const result: Array<{ segmentPath: string; statePath: string }> = [];
  for (const name of readdirSync(directory)) {
    // A killed writer can leave its exclusive temporary file before rename. It is not a
    // sealed archive and is preserved for diagnostics, without blocking unrelated stores.
    if (/^[0-9a-f]{64}\.json\.audit-[0-9]{8}\.[0-9]+\.[0-9a-f]{16}\.tmp$/.test(name)) continue;
    if (!name.includes(".audit-")) continue;
    const match = /^([0-9a-f]{64}\.json)\.audit-([0-9]{8})$/.exec(name);
    if (!match || Number(match[2]) === 0) {
      throw new Error("invalid integrated consent archive path");
    }
    result.push({ segmentPath: path.join(directory, name), statePath: path.join(directory, match[1]!) });
  }
  return result.sort((left, right) => left.segmentPath.localeCompare(right.segmentPath));
}

function readConsentFile(filePath: string, environment: NodeJS.ProcessEnv = process.env): unknown {
  const descriptor = openSync(filePath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW | fsConstants.O_NONBLOCK);
  try {
    const metadata = fstatSync(descriptor);
    if (!metadata.isFile() || !ownedByCurrentProcess(metadata.uid) || metadata.nlink !== 1 ||
      (metadata.mode & 0o777) !== 0o600 || metadata.size < 2 ||
      metadata.size > maxGatewayStateEnvelopeBytes(INTEGRATED_CONSENT_MAX_STATE_BYTES)) {
      throw new Error("integrated consent file must be an owned regular file with mode 0600");
    }
    const raw = readFileSync(descriptor, "utf8");
    if (Buffer.byteLength(raw, "utf8") !== metadata.size) throw new Error("consent file changed during read");
    return decryptGatewayStateJson(
      { kind: "integrated-consent", recordId: path.basename(filePath) }, raw,
      INTEGRATED_CONSENT_MAX_STATE_BYTES, environment
    ).value;
  } finally {
    closeSync(descriptor);
  }
}

export function validIntegratedConsentAuditSegmentForMaintenance(value: unknown, recordId: string): boolean {
  const segment = objectValue(value);
  if (!segment || !exactKeys(segment, ["schema_version", "installation_id", "sequence",
    "previous_segment_sha256", "events"]) ||
    segment.schema_version !== "walksafe.integrated-consent-audit.v1" ||
    typeof segment.installation_id !== "string" || !INSTALLATION_ID.test(segment.installation_id) ||
    !Number.isSafeInteger(segment.sequence) || Number(segment.sequence) < 1 || Number(segment.sequence) > 99_999_999 ||
    (segment.previous_segment_sha256 !== null &&
      (typeof segment.previous_segment_sha256 !== "string" || !SHA256.test(segment.previous_segment_sha256))) ||
    (Number(segment.sequence) === 1) !== (segment.previous_segment_sha256 === null) ||
    !Array.isArray(segment.events) || segment.events.length === 0 || segment.events.length > MAX_EVENTS + WITHDRAWAL_RESERVE_EVENTS) {
    return false;
  }
  const expectedFile = `${sha256(`integrated-consent\0${segment.installation_id}`)}.json`;
  if (recordId !== path.basename(auditSegmentPath(expectedFile, Number(segment.sequence)))) return false;
  const events = segment.events as IntegratedConsentEvent[];
  const first = events[0];
  const last = events.at(-1);
  if (!first || !last || !Number.isSafeInteger(first.revision) || first.revision < 1 ||
    (Number(segment.sequence) === 1 && first.revision !== 1)) return false;
  const initialAudit = authoritativeEvent(first)
    ? first.previous_gateway_audit_record_sha256 : first.previous_receipt_sha256;
  return validState({
    schema_version: 6,
    installation_id: segment.installation_id,
    control_secret_sha256: "0".repeat(64),
    field_actor_binding: authoritativeEvent(last) ? {
      actor_sha256: last.field_actor_binding_sha256,
      consent_revision: last.revision,
      bound_at: last.confirmed_at
    } : null,
    events
  }, segment.installation_id, true, first.revision - 1, initialAudit);
}

function expandArchivedState(
  value: unknown, filePath: string, environment: NodeJS.ProcessEnv,
  query?: ConsentHistoryQuery
): IntegratedConsentState {
  const state = objectValue(value);
  const installationId = state?.installation_id;
  const pointer = objectValue(state?.audit_archive);
  if (!state || state.schema_version !== 7 || typeof installationId !== "string" ||
    !exactKeys(state, ["schema_version", "installation_id", "control_secret_sha256", "field_actor_binding", "events", "revision", "audit_archive"]) ||
    !pointer || !exactKeys(pointer, ["segment_count", "archived_events", "last_segment_sha256"]) ||
    !Number.isSafeInteger(pointer.segment_count) || Number(pointer.segment_count) < 1 || Number(pointer.segment_count) > 99_999_999 ||
    !Number.isSafeInteger(pointer.archived_events) || Number(pointer.archived_events) < Number(pointer.segment_count) ||
    typeof pointer.last_segment_sha256 !== "string" || !SHA256.test(pointer.last_segment_sha256) ||
    !Array.isArray(state.events) || state.events.length < 1 || state.events.length > MAX_EVENTS + WITHDRAWAL_RESERVE_EVENTS ||
    !Number.isSafeInteger(state.revision) || state.revision !== Number(pointer.archived_events) + state.events.length) {
    throw new Error("invalid integrated consent archive boundary");
  }
  // Keep only one segment in memory. A cursor verifies the entire retained chain without
  // rebuilding an ever-growing event array; a PUT retains only its matching historical ID.
  const cursor: ConsentHistoryCursor = { revision: 0, audit: null, previousClientRevision: 0,
    actorRevisions: new Map(), authoritative: false };
  let prior: IntegratedConsentEvent | undefined;
  let previous: string | null = null;
  for (let sequence = 1; sequence <= Number(pointer.segment_count); sequence += 1) {
    const segmentPath = auditSegmentPath(filePath, sequence);
    const decoded = readConsentFile(segmentPath, environment);
    if (!validIntegratedConsentAuditSegmentForMaintenance(decoded, path.basename(segmentPath))) {
      throw new Error("invalid integrated consent archive");
    }
    const segment = decoded as ConsentAuditSegment;
    if (segment.installation_id !== installationId || segment.sequence !== sequence ||
      segment.previous_segment_sha256 !== previous || segment.events[0]!.revision !== cursor.revision + 1) {
      throw new Error("integrated consent archive chain mismatch");
    }
    const last = segment.events.at(-1)!;
    if (!validState({ schema_version: 6, installation_id: installationId,
      control_secret_sha256: state.control_secret_sha256,
      field_actor_binding: authoritativeEvent(last) ? { actor_sha256: last.field_actor_binding_sha256,
        consent_revision: last.revision, bound_at: last.confirmed_at } : null,
      events: segment.events }, installationId, true, cursor.revision, cursor.audit, cursor)) {
      throw new Error("invalid integrated consent archived history");
    }
    if (query && !prior) prior = segment.events.find((event) => event.request_id === query.requestId &&
      (!authoritativeEvent(event) || event.field_actor_binding_sha256 === query.actorSha256));
    previous = auditSegmentDigest(segment);
  }
  if (cursor.revision !== pointer.archived_events || previous !== pointer.last_segment_sha256) {
    throw new Error("integrated consent archive boundary mismatch");
  }
  const actorFloor = query ? cursor.actorRevisions.get(query.actorSha256) ?? 0 : 0;
  if (!validState({ schema_version: 6, installation_id: installationId,
    control_secret_sha256: state.control_secret_sha256, field_actor_binding: state.field_actor_binding,
    events: state.events }, installationId, true, cursor.revision, cursor.audit, cursor)) {
    throw new Error("invalid integrated consent history");
  }
  const result = state as unknown as IntegratedConsentState;
  archivedRequestState.set(result, { prior, actorFloor });
  return result;
}

export function validIntegratedConsentStateForMaintenance(
  value: unknown,
  recordId: string,
  environment: NodeJS.ProcessEnv = process.env
): boolean {
  const state = objectValue(value);
  const installationId = state?.installation_id;
  if (typeof installationId !== "string") return false;
  if (state?.schema_version === 7) {
    try {
      expandArchivedState(value, path.join(stateDirectory(environment), recordId), environment);
    } catch { return false; }
  } else if (!validState(value, installationId)) return false;
  return recordId === `${sha256(`integrated-consent\0${installationId}`)}.json`;
}

function readState(installationId: string, query?: ConsentHistoryQuery): IntegratedConsentState {
  const target = statePath(installationId);
  let decoded: unknown;
  try {
    decoded = readConsentFile(target);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      if (integratedConsentAuditSegmentPaths().some((segment) => segment.statePath === target)) {
        throw new Error("integrated consent current state is missing beside retained audit");
      }
      return {
        schema_version: 6,
        installation_id: installationId,
        control_secret_sha256: "",
        field_actor_binding: null,
        events: []
      };
    }
    throw error;
  }
  if (objectValue(decoded)?.schema_version === 7) {
    if (objectValue(decoded)?.installation_id !== installationId) throw new Error("consent installation mismatch");
    return expandArchivedState(decoded, target, process.env, query);
  }
  if (!validState(decoded, installationId)) throw new Error("invalid integrated consent state");
  return decoded;
}

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

function writeState(state: IntegratedConsentState): void {
  const persisted = state.schema_version === 7 ? {
    ...state,
    revision: state.revision
  } : state;
  const plaintext = `${JSON.stringify(persisted)}\n`;
  if (Buffer.byteLength(plaintext, "utf8") > INTEGRATED_CONSENT_MAX_STATE_BYTES) {
    throw new Error("integrated consent state capacity exceeded");
  }
  const target = statePath(state.installation_id);
  const serialized = encryptGatewayStateJson(
    { kind: "integrated-consent", recordId: path.basename(target) },
    persisted,
    INTEGRATED_CONSENT_MAX_STATE_BYTES
  );
  const temporary = `${target}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
  let descriptor: number | null = null;
  try {
    descriptor = openSync(
      temporary,
      fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW,
      0o600
    );
    writeFileSync(descriptor, serialized, "utf8");
    storageFault("current_file_fsync");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    chmodSync(temporary, 0o600);
    storageFault("current_rename");
    renameSync(temporary, target);
    // S19-27: from here the new consent state may be durable (outcome unknown on failure).
    syncGatewayStateDirectoryAfterRename("integrated-consent", path.dirname(target));
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    rmSync(temporary, { force: true });
  }
}

function syncConsentDirectory(directory: string): void {
  const descriptor = openSync(directory, fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW);
  try { fsyncSync(descriptor); } finally { closeSync(descriptor); }
}

function sealAuditSegment(filePath: string, segment: ConsentAuditSegment): void {
  const temporary = `${filePath}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
  let descriptor: number | null = null;
  try {
    storageFault("archive_open");
    descriptor = openSync(temporary,
      fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW, 0o600);
    writeFileSync(descriptor, encryptGatewayStateJson(
      { kind: "integrated-consent", recordId: path.basename(filePath) }, segment,
      INTEGRATED_CONSENT_MAX_STATE_BYTES
    ), "utf8");
    storageFault("archive_file_fsync");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    storageFault("archive_rename");
    renameSync(temporary, filePath);
    storageFault("archive_directory_fsync");
    syncConsentDirectory(path.dirname(filePath));
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    rmSync(temporary, { force: true });
  }
}

/**
 * Sealing is a separate transaction with no permission change. Archives become durable
 * before the compact current state references them. An archive-only crash leaves the old
 * main state authoritative; retry verifies and fsyncs the exact retained prefix, never
 * replaces an orphan. Failed sealing keeps the four-event withdrawal reserve available.
 */
function archiveConsentHistory(state: IntegratedConsentState): boolean {
  const archived = state.audit_archive?.archived_events ?? 0;
  const tail = state.events;
  if (tail.length < MAX_EVENTS && Buffer.byteLength(JSON.stringify({ ...state, events: tail }), "utf8") + 4096 <= NORMAL_MAX_STATE_BYTES) {
    return true;
  }
  if (tail.length < 2) return false;
  const target = statePath(state.installation_id);
  const sequence = (state.audit_archive?.segment_count ?? 0) + 1;
  let pointer: ConsentAuditArchive;
  try {
    const segmentPath = auditSegmentPath(target, sequence);
    let orphan: unknown;
    try { orphan = readConsentFile(segmentPath); } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
    }
    const eventCount = orphan === undefined ? Math.min(MAX_EVENTS - 1, tail.length - 1)
      : (objectValue(orphan)?.events as unknown[])?.length;
    if (!Number.isSafeInteger(eventCount) || !eventCount || eventCount >= tail.length) {
      throw new Error("consent orphan archive prefix is invalid");
    }
    const segment: ConsentAuditSegment = {
      schema_version: "walksafe.integrated-consent-audit.v1",
      installation_id: state.installation_id,
      sequence,
      previous_segment_sha256: state.audit_archive?.last_segment_sha256 ?? null,
      events: tail.slice(0, eventCount)
    };
    if (orphan !== undefined) {
      if (!validIntegratedConsentAuditSegmentForMaintenance(orphan, path.basename(segmentPath)) ||
        canonicalJson(orphan) !== canonicalJson(segment)) {
        throw new Error("consent orphan archive does not match retained audit history");
      }
      // A prior archive directory-fsync failure must be repaired before publishing its pointer.
      const descriptor = openSync(segmentPath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
      try { storageFault("archive_file_fsync"); fsyncSync(descriptor); } finally { closeSync(descriptor); }
      storageFault("archive_directory_fsync");
      syncConsentDirectory(path.dirname(segmentPath));
    } else {
      sealAuditSegment(segmentPath, segment);
    }
    pointer = { segment_count: sequence, archived_events: archived + eventCount,
      last_segment_sha256: auditSegmentDigest(segment) };
  } catch {
    return false;
  }
  const compacted: IntegratedConsentState = { ...state, schema_version: 7,
    revision: state.revision ?? state.events.length, audit_archive: pointer,
    events: tail.slice(pointer.archived_events - archived) };
  try { writeState(compacted); } catch (error) {
    if (error instanceof GatewayStorageOutcomeUnknownError) throw error;
    return false;
  }
  state.schema_version = 7;
  state.revision = compacted.revision!;
  state.audit_archive = pointer;
  state.events = compacted.events;
  return true;
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

async function withInstallationLock<T>(
  installationId: string,
  action: () => Promise<T> | T,
  signal?: AbortSignal
): Promise<T> {
  return installationAdmission.run(installationId, async () => {
    const fileLock = await acquireConsentFileLock(installationId);
    try {
      return await action();
    } finally {
      releaseConsentFileLock(fileLock);
    }
  }, signal);
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
          typeof value.installation_id !== "string" || typeof value.public_key !== "string" ||
          !/^[A-Za-z0-9_-]{79,122}$/.test(value.public_key)) {
        throw new IntegratedConsentRequestError(400, "installation_evidence_key_request_invalid", "Invalid registration request.");
      }
      return await withInstallationLock(value.installation_id, async () => {
        const current = currentConfirmation(value.installation_id as string,
          request.headers.get(CONSENT_CONTROL_SECRET_HEADER)?.trim() ?? "", backendContext.fieldActorBindingId);
        if (!current || backendContext.signal.aborted || backendContext.isCurrent?.() === false) {
          throw new IntegratedConsentRequestError(409, "integrated_consent_reconsent_required", "Confirmed installation consent is required.");
        }
        const headers = proxyRequestHeaders(request,
          { accept: "application/json", "content-type": "application/json" }, backendContext.accountGeneration);
        if (!headers.get(ACTOR_ID_HEADER) || !headers.get(ACTOR_ASSERTION_HEADER)) {
          throw new IntegratedConsentRequestError(503, "gateway_backend_assertion_unavailable", "Actor assertion is unavailable.");
        }
        const response = await fetchBackend(request, backendUrl("/privacy/installation-evidence-key"),
          { method: "POST", headers, body: JSON.stringify(value), cache: "no-store", signal: backendContext.signal },
          15_000, backendContext.fetchImpl ?? globalThis.fetch);
        return response.status === 204 ? new Response(null, {status: 204, headers: {"cache-control": "no-store"}})
          : toBackendResponse(response);
      }, request.signal);
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
