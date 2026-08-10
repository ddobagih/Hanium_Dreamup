import { createHash, randomBytes, timingSafeEqual } from "node:crypto";
import {
  chmodSync,
  closeSync,
  constants as fsConstants,
  fstatSync,
  fsyncSync,
  linkSync,
  lstatSync,
  mkdirSync,
  openSync,
  readFileSync,
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
  maxGatewayStateEnvelopeBytes
} from "./encrypted-json-store.js";

export const INTEGRATED_CONSENT_POLICY_VERSION = "FP-013-1.0.0";
export const INTEGRATED_CONSENT_CONTROL = "integrated-consent";
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
export const INTEGRATED_CONSENT_MAX_STATE_BYTES = 4 * 1024 * 1024;
const FILE_LOCK_RETRY_MS = 25;
const FILE_LOCK_MAX_ATTEMPTS = 100;
const installationLocks = new Map<string, Promise<void>>();

export type IntegratedConsentKey = typeof CONSENT_KEYS[number];

export type IntegratedConsentItemVersions = {
  raw_source_collection: string;
  automatic_reporting: string;
  mobile_network_transfer: string;
  training_reuse: string;
};

export const INTEGRATED_CONSENT_ITEM_VERSIONS: IntegratedConsentItemVersions =
  Object.freeze({
    raw_source_collection: "FP-013-RAW-1.0.0",
    automatic_reporting: "FP-013-AUTO-1.0.0",
    mobile_network_transfer: "FP-013-MOBILE-1.0.0",
    training_reuse: "FP-013-TRAINING-1.0.0"
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
  schema_version: "walksafe.integrated-consent-confirmation.v1";
  current: true;
  installation_id: string;
  request_id: string;
  policy_version: string;
  item_versions: IntegratedConsentItemVersions;
  client_revision: number;
  revision: number;
  selections: IntegratedConsentSelections;
  confirmed_at: string;
  receipt_sha256: string;
};

type IntegratedConsentEvent = {
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

type IntegratedConsentState = {
  schema_version: 2 | 3;
  installation_id: string;
  control_secret_sha256: string;
  field_actor_binding?: {
    actor_sha256: string;
    consent_revision: number;
    bound_at: string;
  } | null;
  events: IntegratedConsentEvent[];
};

type ConsentRecordInput = {
  installationId: string;
  requestId: string;
  policyVersion: string;
  itemVersions: IntegratedConsentItemVersions;
  clientRevision: number;
  controlSecret: string;
  selections: IntegratedConsentSelections;
  fieldActorBindingId: string;
};

export type IntegratedConsentBackendContext = {
  accountGeneration: number;
  fieldActorBindingId: string;
  signal: AbortSignal;
  fetchImpl?: GatewayFetch;
};

type PrivacyConsentReceiptV2 = {
  schema_version: "walksafe.privacy-consent-receipt.v2";
  request_id: string;
  client_revision: number;
  receipt_sha256: string;
  recorded_at: string;
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
  event: Omit<IntegratedConsentEvent, "receipt_sha256">
): string {
  return sha256(canonicalJson({
    installation_id: installationId,
    ...event
  }));
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

function stateParentDirectory(): string {
  const configured = process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR?.trim() ?? "";
  if (!configured && process.env.NODE_ENV === "production") {
    throw new Error("WALKSAFE_GATEWAY_RATE_LIMIT_DIR is required in production");
  }
  const directory = configured || path.join(tmpdir(), "walksafe-gateway-rate-limit-v1");
  if (!path.isAbsolute(directory)) throw new Error("gateway state directory must be absolute");
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

function stateDirectory(): string {
  const parent = secureDirectory(stateParentDirectory(), "gateway state directory");
  return secureDirectory(path.join(parent, "integrated-consent"), "integrated consent directory");
}

function statePath(installationId: string): string {
  const digest = sha256(`integrated-consent\0${installationId}`);
  return path.join(stateDirectory(), `${digest}.json`);
}

type ConsentFileLock = {
  descriptor: number;
  path: string;
  token: string;
  device: bigint;
  inode: bigint;
};

type ConsentFileLockRecord = {
  pid: number;
  token: string;
  acquired_at: string;
};

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, milliseconds));
}

function lockRecord(
  lockPath: string,
  expectedDevice: bigint,
  expectedInode: bigint
): ConsentFileLockRecord {
  let descriptor: number | null = null;
  try {
    descriptor = openSync(lockPath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor, { bigint: true });
    if (metadata.dev !== expectedDevice || metadata.ino !== expectedInode) {
      throw new Error("integrated consent lock changed during inspection");
    }
    const payload = objectValue(JSON.parse(readFileSync(descriptor, "utf8")) as unknown);
    if (
      !payload ||
      !exactKeys(payload, ["pid", "token", "acquired_at"]) ||
      !Number.isSafeInteger(payload.pid) ||
      (payload.pid as number) <= 0 ||
      typeof payload.token !== "string" ||
      !/^[0-9a-f]{32}$/.test(payload.token) ||
      !validIsoInstant(payload.acquired_at)
    ) {
      throw new Error("integrated consent lock record is invalid");
    }
    return {
      pid: payload.pid as number,
      token: payload.token,
      acquired_at: payload.acquired_at
    };
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

function processIsAlive(pid: number): boolean {
  try {
    process.kill(pid, 0);
    return true;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ESRCH") return false;
    return true;
  }
}

async function acquireConsentFileLockAtPath(
  lockPath: string
): Promise<ConsentFileLock> {
  const reaperPath = `${lockPath}.reaper`;
  for (let attempt = 0; attempt < FILE_LOCK_MAX_ATTEMPTS; attempt += 1) {
    try {
      const descriptor = openSync(
        lockPath,
        fsConstants.O_WRONLY |
          fsConstants.O_CREAT |
          fsConstants.O_EXCL |
          fsConstants.O_NOFOLLOW,
        0o600
      );
      const token = randomBytes(16).toString("hex");
      try {
        writeFileSync(
          descriptor,
          `${JSON.stringify({
            pid: process.pid,
            token,
            acquired_at: new Date().toISOString()
          })}\n`,
          "utf8"
        );
        fsyncSync(descriptor);
        const metadata = fstatSync(descriptor, { bigint: true });
        rmSync(reaperPath, { force: true });
        return {
          descriptor,
          path: lockPath,
          token,
          device: metadata.dev,
          inode: metadata.ino
        };
      } catch (error) {
        closeSync(descriptor);
        rmSync(lockPath, { force: true });
        throw error;
      }
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
      try {
        const metadata = lstatSync(lockPath);
        if (
          !metadata.isFile() ||
          metadata.isSymbolicLink() ||
          !ownedByCurrentProcess(metadata.uid) ||
          (metadata.mode & 0o077) !== 0
        ) {
          throw new Error("integrated consent lock is not an owned mode-0600 file");
        }
        const metadataBigInt = lstatSync(lockPath, { bigint: true });
        const record = lockRecord(lockPath, metadataBigInt.dev, metadataBigInt.ino);
        if (!processIsAlive(record.pid)) {
          try {
            linkSync(lockPath, reaperPath);
          } catch (reaperError) {
            const code = (reaperError as NodeJS.ErrnoException).code;
            if (code === "ENOENT") continue;
            if (code === "EEXIST") {
              await delay(FILE_LOCK_RETRY_MS);
              continue;
            }
            throw reaperError;
          }
          try {
            const current = lstatSync(lockPath, { bigint: true });
            const reaper = lstatSync(reaperPath, { bigint: true });
            if (
              current.dev !== metadataBigInt.dev ||
              current.ino !== metadataBigInt.ino ||
              reaper.dev !== metadataBigInt.dev ||
              reaper.ino !== metadataBigInt.ino
            ) {
              throw new Error("integrated consent lock changed during recovery");
            }
            rmSync(lockPath);
          } finally {
            rmSync(reaperPath, { force: true });
          }
          continue;
        }
      } catch (inspectionError) {
        if ((inspectionError as NodeJS.ErrnoException).code === "ENOENT") continue;
        throw inspectionError;
      }
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
  try {
    const current = lstatSync(lock.path, { bigint: true });
    const record = lockRecord(lock.path, current.dev, current.ino);
    if (
      current.dev === lock.device &&
      current.ino === lock.inode &&
      record.pid === process.pid &&
      record.token === lock.token
    ) {
      rmSync(lock.path);
    }
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
  } finally {
    closeSync(lock.descriptor);
  }
}

export async function withIntegratedConsentStateFileLockForMaintenance<T>(
  filePath: string,
  action: () => Promise<T> | T
): Promise<T> {
  const resolved = path.resolve(filePath);
  if (
    resolved !== filePath ||
    path.dirname(resolved) !== stateDirectory() ||
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

function validState(value: unknown, installationId: string): value is IntegratedConsentState {
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
      (state.schema_version === 3 && exactKeys(state, currentKeys))
    ) ||
    state.installation_id !== installationId ||
    typeof state.control_secret_sha256 !== "string" ||
    !SHA256.test(state.control_secret_sha256) ||
    !Array.isArray(state.events) ||
    state.events.length > MAX_EVENTS
  ) {
    return false;
  }
  if (state.schema_version === 3 && state.field_actor_binding !== null) {
    const binding = objectValue(state.field_actor_binding);
    if (
      !binding ||
      !exactKeys(binding, ["actor_sha256", "consent_revision", "bound_at"]) ||
      typeof binding.actor_sha256 !== "string" ||
      !SHA256.test(binding.actor_sha256) ||
      !Number.isSafeInteger(binding.consent_revision) ||
      binding.consent_revision !== state.events.length ||
      !validIsoInstant(binding.bound_at)
    ) {
      return false;
    }
  }
  let previousReceipt: string | null = null;
  let previousClientRevision = 0;
  for (let index = 0; index < state.events.length; index += 1) {
    const rawEvent = objectValue(state.events[index]);
    if (
      !rawEvent ||
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
      typeof rawEvent.request_id !== "string" ||
      !REQUEST_ID.test(rawEvent.request_id) ||
      typeof rawEvent.policy_version !== "string" ||
      !POLICY_VERSION.test(rawEvent.policy_version) ||
      !Number.isSafeInteger(rawEvent.client_revision) ||
      (rawEvent.client_revision as number) <= previousClientRevision ||
      rawEvent.revision !== index + 1 ||
      !validIsoInstant(rawEvent.confirmed_at) ||
      rawEvent.previous_receipt_sha256 !== previousReceipt ||
      typeof rawEvent.receipt_sha256 !== "string" ||
      !SHA256.test(rawEvent.receipt_sha256)
    ) {
      return false;
    }
    const selections = selectionsOrNull(rawEvent.selections);
    const itemVersions = itemVersionsOrNull(rawEvent.item_versions);
    if (!selections || !itemVersions) return false;
    const eventWithoutReceipt: Omit<IntegratedConsentEvent, "receipt_sha256"> = {
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
    previousReceipt = rawEvent.receipt_sha256;
    previousClientRevision = rawEvent.client_revision as number;
  }
  return true;
}

export function validIntegratedConsentStateForMaintenance(
  value: unknown,
  recordId: string
): boolean {
  const state = objectValue(value);
  const installationId = state?.installation_id;
  if (typeof installationId !== "string" || !validState(value, installationId)) return false;
  return recordId === `${sha256(`integrated-consent\0${installationId}`)}.json`;
}

function readState(installationId: string): IntegratedConsentState {
  const target = statePath(installationId);
  let descriptor: number | null = null;
  try {
    descriptor = openSync(target, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() ||
      !ownedByCurrentProcess(metadata.uid) ||
      metadata.nlink !== 1 ||
      (metadata.mode & 0o777) !== 0o600 ||
      metadata.size > maxGatewayStateEnvelopeBytes(INTEGRATED_CONSENT_MAX_STATE_BYTES)
    ) {
      throw new Error("integrated consent state must be an owned regular file with mode 0600");
    }
    const decoded = decryptGatewayStateJson(
      { kind: "integrated-consent", recordId: path.basename(target) },
      readFileSync(descriptor, "utf8"),
      INTEGRATED_CONSENT_MAX_STATE_BYTES
    ).value;
    if (!validState(decoded, installationId)) throw new Error("invalid integrated consent state");
    return decoded;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") {
      return {
        schema_version: 3,
        installation_id: installationId,
        control_secret_sha256: "",
        field_actor_binding: null,
        events: []
      };
    }
    throw error;
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
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
  const plaintext = `${JSON.stringify(state)}\n`;
  if (Buffer.byteLength(plaintext, "utf8") > INTEGRATED_CONSENT_MAX_STATE_BYTES) {
    throw new Error("integrated consent state capacity exceeded");
  }
  const target = statePath(state.installation_id);
  const serialized = encryptGatewayStateJson(
    { kind: "integrated-consent", recordId: path.basename(target) },
    state,
    INTEGRATED_CONSENT_MAX_STATE_BYTES
  );
  const temporary = `${target}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
  let descriptor: number | null = null;
  let directoryDescriptor: number | null = null;
  try {
    descriptor = openSync(
      temporary,
      fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW,
      0o600
    );
    writeFileSync(descriptor, serialized, "utf8");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    chmodSync(temporary, 0o600);
    renameSync(temporary, target);
    directoryDescriptor = openSync(
      path.dirname(target),
      fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW
    );
    fsyncSync(directoryDescriptor);
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    if (directoryDescriptor !== null) closeSync(directoryDescriptor);
    rmSync(temporary, { force: true });
  }
}

async function withInstallationLock<T>(
  installationId: string,
  action: () => Promise<T> | T
): Promise<T> {
  const previous = installationLocks.get(installationId) ?? Promise.resolve();
  let release!: () => void;
  const hold = new Promise<void>((resolve) => {
    release = resolve;
  });
  const tail = previous.then(() => hold);
  installationLocks.set(installationId, tail);
  await previous;
  let fileLock: ConsentFileLock | null = null;
  try {
    fileLock = await acquireConsentFileLock(installationId);
    return await action();
  } finally {
    if (fileLock !== null) releaseConsentFileLock(fileLock);
    release();
    if (installationLocks.get(installationId) === tail) installationLocks.delete(installationId);
  }
}

function confirmation(
  installationId: string,
  event: IntegratedConsentEvent
): IntegratedConsentConfirmation {
  return {
    schema_version: "walksafe.integrated-consent-confirmation.v1",
    current: true,
    installation_id: installationId,
    request_id: event.request_id,
    policy_version: event.policy_version,
    item_versions: { ...event.item_versions },
    client_revision: event.client_revision,
    revision: event.revision,
    selections: { ...event.selections },
    confirmed_at: event.confirmed_at,
    receipt_sha256: event.receipt_sha256
  };
}

function currentConfirmation(
  installationId: string,
  controlSecret: string
): IntegratedConsentConfirmation | null {
  if (!INSTALLATION_ID.test(installationId)) {
    throw new IntegratedConsentRequestError(400, "integrated_consent_installation_invalid", "Installation ID is invalid.");
  }
  assertControlSecretFormat(controlSecret);
  const state = readState(installationId);
  if (state.events.length === 0) return null;
  verifyControlSecret(state, controlSecret, false);
  const current = state.events.at(-1);
  return current ? confirmation(installationId, current) : null;
}

async function recordConsent(
  input: ConsentRecordInput,
  syncBackend: () => Promise<ConsentBackendSyncResult>,
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
    const state = readState(input.installationId);
    verifyControlSecret(state, input.controlSecret, true);
    const actorSha256 = sha256(
      `integrated-consent-field-actor\0${input.fieldActorBindingId}`
    );
    const binding = state.field_actor_binding ?? null;
    const prior = state.events.find((event) => event.request_id === input.requestId);
    if (prior) {
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
        !equalItemVersions(prior.item_versions, input.itemVersions) ||
        !equalSelections(prior.selections, input.selections)
      ) {
        throw new IntegratedConsentRequestError(409, "integrated_consent_request_conflict", "Request ID was already used.");
      }
      if (state.events.at(-1)?.receipt_sha256 !== prior.receipt_sha256) {
        throw new IntegratedConsentRequestError(
          409,
          "integrated_consent_request_stale",
          "The idempotent request is no longer current."
        );
      }
      const synced = await syncBackend();
      if (synced.error) return { error: synced.error };
      if (!binding) {
        state.schema_version = 3;
        state.field_actor_binding = {
          actor_sha256: actorSha256,
          consent_revision: prior.revision,
          bound_at: new Date(nowMs).toISOString()
        };
        writeState(state);
      }
      return {
        confirmation: confirmation(input.installationId, prior),
        created: false,
        backendStatus: synced.status
      };
    }
    if (state.events.length >= MAX_EVENTS) {
      throw new IntegratedConsentRequestError(503, "integrated_consent_capacity_unavailable", "Consent ledger capacity is unavailable.");
    }
    const currentClientRevision = state.events.at(-1)?.client_revision ?? 0;
    if (input.clientRevision <= currentClientRevision) {
      throw new IntegratedConsentRequestError(
        409,
        "integrated_consent_client_revision_stale",
        "A newer consent decision is already stored."
      );
    }
    const previousReceipt = state.events.at(-1)?.receipt_sha256 ?? null;
    const eventWithoutReceipt: Omit<IntegratedConsentEvent, "receipt_sha256"> = {
      request_id: input.requestId,
      policy_version: input.policyVersion,
      item_versions: { ...input.itemVersions },
      client_revision: input.clientRevision,
      revision: state.events.length + 1,
      selections: { ...input.selections },
      confirmed_at: new Date(nowMs).toISOString(),
      previous_receipt_sha256: previousReceipt
    };
    const event: IntegratedConsentEvent = {
      ...eventWithoutReceipt,
      receipt_sha256: eventReceipt(input.installationId, eventWithoutReceipt)
    };
    const synced = await syncBackend();
    if (synced.error) return { error: synced.error };
    state.schema_version = 3;
    state.events.push(event);
    state.field_actor_binding = {
      actor_sha256: actorSha256,
      consent_revision: event.revision,
      bound_at: new Date(nowMs).toISOString()
    };
    writeState(state);
    return {
      confirmation: confirmation(input.installationId, event),
      created: true,
      backendStatus: synced.status
    };
  });
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

async function syncConsentToBackend(
  request: Request,
  input: ConsentRecordInput,
  context: IntegratedConsentBackendContext
): Promise<ConsentBackendSyncResult> {
  const body = JSON.stringify({
    schema_version: "walksafe.privacy-consent-event.v2",
    installation_id: input.installationId,
    request_id: input.requestId,
    client_revision: input.clientRevision,
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
  const known = error instanceof IntegratedConsentRequestError
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

export async function handleIntegratedConsentRequest(
  request: Request,
  backendContext?: IntegratedConsentBackendContext
): Promise<Response> {
  const url = new URL(request.url);
  const queryKeys = [...url.searchParams.keys()];
  try {
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
      const current = currentConfirmation(
        url.searchParams.get("installation_id") ?? "",
        request.headers.get(CONSENT_CONTROL_SECRET_HEADER)?.trim() ?? ""
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
        "selections"
      ]) ||
      payload.schema_version !== "walksafe.integrated-consent-request.v1" ||
      typeof payload.installation_id !== "string" ||
      typeof payload.request_id !== "string" ||
      typeof payload.policy_version !== "string" ||
      typeof payload.client_revision !== "number"
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
      controlSecret:
        request.headers.get(CONSENT_CONTROL_SECRET_HEADER)?.trim() ?? "",
      selections,
      fieldActorBindingId: backendContext.fieldActorBindingId
    };
    const result = await recordConsent(
      input,
      () => syncConsentToBackend(request, input, backendContext)
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
      const current = currentEvent
        ? confirmation(installationId, currentEvent)
        : null;
      const receiptMatches = current !== null && timingSafeEqual(
        Buffer.from(current.receipt_sha256, "hex"),
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
      if (binding && !timingSafeEqual(
        Buffer.from(binding.actor_sha256, "hex"),
        Buffer.from(actorSha256, "hex")
      )) {
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
      if (!binding) {
        state.schema_version = 3;
        state.field_actor_binding = {
          actor_sha256: actorSha256,
          consent_revision: current.revision,
          bound_at: new Date().toISOString()
        };
        writeState(state);
      }
      return { confirmation: current };
    });
  } catch (error) {
    return { error: errorResponse(error) };
  }
}
