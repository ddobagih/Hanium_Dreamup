import path from "node:path";
import { resolveGatewayStateDirectory } from "./config.js";
import {
  mkdirSync,
  chmodSync,
  lstatSync,
  realpathSync,
  openSync,
  constants as fsConstants,
  fstatSync,
  readFileSync,
  closeSync,
  opendirSync,
  writeFileSync,
  fsyncSync,
  renameSync,
  rmSync
} from "node:fs";
import {
  maxGatewayStateEnvelopeBytes,
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  syncGatewayStateDirectoryAfterRename
} from "./encrypted-json-store.js";
import { randomBytes } from "node:crypto";
import { requiredGatewayStateFileWasLost, registerRequiredGatewayStateFile } from "./state-init-marker.js";
import {
  withExclusiveFileLock,
  ExclusiveFileLockBusyError,
  ExclusiveFileLockIntegrityError
} from "./exclusive-file-lock.js";
import {
  type DeletionLedger,
  LEDGER_SCHEMA,
  PrivacyDeletionV2Error,
  type ActorFence,
  objectValue,
  exactKeys,
  ARCHIVE_VERSION,
  SHA256,
  safePositiveInteger,
  REQUEST_ID,
  OPAQUE_ID,
  type DeletionRecord,
  requestDigest,
  validRecord,
  findArchiveActorPrefix,
  type ArchiveIndex,
  ARCHIVE_ACTOR_PREFIX_HEX,
  archiveActorPrefix,
  type ArchivedRequest,
  type ArchivedActor,
  validPrivacyDeletionV2StateForMaintenance,
  type AccountDeletionStatusV2,
  validDeletionAccessSecretV2,
  capabilityMatches,
  refreshCapabilityKey
} from "./privacy-deletion-codec.js";

export const PRIVACY_DELETION_V2_MAX_BYTES = 4 * 1024 * 1024;

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

export function assertArchiveFence(record: DeletionRecord, fence: ActorFence | null): asserts fence is ActorFence {
  if (
    fence === null || fence.account_generation !== record.account_generation ||
    fence.request_id !== record.request_id || fence.gateway_fence_id !== record.gateway_fence_id
  ) throw new PrivacyDeletionV2Error("integrity", "privacy deletion archive fence is missing or mismatched");
}

export function readArchivedRequestByDigest(digest: string): DeletionRecord | null {
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

export function assertSameImmutableRequest(active: DeletionRecord, archived: DeletionRecord): void {
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

export function recordFromStores(ledger: DeletionLedger, requestId: string): DeletionRecord | undefined {
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

export function fenceFromStores(ledger: DeletionLedger, digest: string): ActorFence | undefined {
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

export function archiveEntryDigests(kind: "requests" | "actors"): string[] {
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

export function readLedger(): DeletionLedger {
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

export function mutateLedger<T>(
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

export function privacyDeletionV2LedgerPathForTests(): string {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("privacy deletion v2 ledger path is test-only");
  }
  return ledgerPath();
}

