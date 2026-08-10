/**
 * Persists short-lived, explicitly authenticated field-test telemetry as JSONL.
 * Raw camera/audio bytes are not accepted here; frame images remain an explicit
 * action handled by the separate test-capture endpoint.
 */
import { createHash } from "node:crypto";
import { constants as fsConstants, type Dirent } from "node:fs";
import {
  appendFile,
  chmod,
  lstat,
  mkdir,
  open,
  readdir,
  rename,
  rm,
  stat,
  statfs,
  writeFile
} from "node:fs/promises";
import path from "node:path";
import { gatewaySessionActor, gatewayUnauthorizedResponse, isFieldSessionAuthorized } from "../_gateway-auth";
import { readBoundedTextBody } from "../_request-body";
import { verifiedRuntimeSourceCommit } from "../_runtime-source-identity";

export const runtime = "nodejs";

const MAX_BODY_BYTES = 64 * 1024;
const MAX_CAPTURE_CLOCK_SKEW_MS = 10 * 60 * 1000;
const RATE_WINDOW_MS = 60 * 1000;
const POST_ACTOR_RATE_LIMIT = 90;
const POST_GLOBAL_RATE_LIMIT = 600;
const DELETE_ACTOR_RATE_LIMIT = 30;
const DELETE_GLOBAL_RATE_LIMIT = 300;
const MAX_RATE_STATES = 512;
const MAX_POSTS_IN_FLIGHT = 8;
const MAX_DELETES_IN_FLIGHT = 2;
const DEFAULT_MAX_TOTAL_BYTES = 256 * 1024 * 1024;
const DEFAULT_MAX_SESSIONS = 2_048;
const DEFAULT_MAX_FILES = 8_192;
const DEFAULT_MIN_FREE_BYTES = 512 * 1024 * 1024;
const DEFAULT_MIN_FREE_INODES = 4_096;
const CONSENT_VERSION = "walksafe.telemetry-consent.v1";
const SOURCE_IDENTITY_REQUIRED_ENVIRONMENTS = new Set(["field", "staging", "production"]);
const SAFE_SESSION_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const SAFE_PAYLOAD_KEY = /^[A-Za-z][A-Za-z0-9_]{0,63}$/;
const FORBIDDEN_MEDIA_KEY = /(?:^|_)(?:audio|video|image|frame|blob|bytes|base64|data_url|raw_media)(?:_|$)/i;
const BASE64_LIKE_VALUE = /^[A-Za-z0-9+/_=-]{128,}$/;
const ENVELOPE_KEYS = new Set(["schema_version", "session_id", "captured_at", "event_type", "payload"]);
const PAYLOAD_KEYS = new Set([
  "detector_mode",
  "camera_ready",
  "detector_busy",
  "detector_status",
  "detect_v2_audit",
  "detections",
  "primary_detection",
  "secondary_detection",
  "gps",
  "heading",
  "walking_speed_mps",
  "step_based_speed_mps",
  "step_length_m",
  "motion_stability",
  "risk_active",
  "risk_text",
  "non_metric_advisory_active",
  "non_metric_advisory_tier",
  "non_metric_advisory_direction",
  "non_metric_advisory_message",
  "non_metric_advisory_consecutive_frames",
  "non_metric_advisory_stable_ms",
  "non_metric_advisory_max_gps_accuracy_m",
  "non_metric_advisory_metric",
  "non_metric_advisory_tmap_authoritative",
  "non_metric_advisory_reports_allowed",
  "depth_status",
  "depth_detail",
  "navigation_active",
  "navigation_status",
  "navigation_instruction",
  "navigation_detail",
  "navigation_future_motion",
  "report_status",
  "report_message",
  "duplicate_report_count",
  "online",
  "visibility_state",
  "user_agent",
  "viewport"
]);
const NESTED_PAYLOAD_KEYS = new Set([
  "request_id",
  "latency_ms",
  "http_status",
  "raw_detection_count",
  "parsed_detection_count",
  "parser_drop_count",
  "parser_drop_reasons",
  "error_code",
  "error_message",
  "reason",
  "count",
  "schema_version",
  "model_key",
  "source_model",
  "model_class_id",
  "class_name",
  "category",
  "confidence",
  "bbox",
  "distance_m",
  "distance_source",
  "distance_confidence",
  "approach_state",
  "threshold_used",
  "captured_at",
  "gps",
  "heading",
  "track_id",
  "x",
  "y",
  "width",
  "height",
  "latitude",
  "longitude",
  "accuracy_m",
  "speed_mps",
  "horizonS",
  "projectedDistanceM",
  "projectedPoint",
  "bearingDeg",
  "routeBearingDeg",
  "headingDeg",
  "headingRouteDeltaDeg",
  "screenShiftX",
  "speedMps",
  "speedSource",
  "width",
  "height",
  "device_pixel_ratio"
]);
const EVENT_TYPES = new Set([
  "session_start",
  "heartbeat",
  "detection",
  "navigation",
  "report",
  "visibility",
  "error",
  "session_end"
]);

type FieldTelemetryEnvelope = {
  schema_version: "walksafe.field-telemetry.v1";
  session_id: string;
  captured_at: string;
  event_type: string;
  payload: Record<string, unknown>;
};

type RateState = { startedAt: number; count: number; lastSeenAt: number };
type FieldLogOperation = "post" | "delete";
type StorageLimits = {
  maxTotalBytes: number;
  maxSessions: number;
  maxFiles: number;
  minFreeBytes: number;
  minFreeInodes: number;
};
type StorageUsage = {
  totalBytes: number;
  fileCount: number;
  sessionIds: Set<string>;
  directories: Set<string>;
};
type SessionRevocation = { revoked: boolean; ownerBinding: string | null };
const rateStates = new Map<string, RateState>();
const sessionOperations = new Map<string, Promise<void>>();
let storageQueue = Promise.resolve();
let postsInFlight = 0;
let deletesInFlight = 0;

const serverSourceCommitPromise = verifiedRuntimeSourceCommit();

function logRoot(): string {
  return process.env.WALKSAFE_FIELD_LOG_DIR ?? path.join(process.cwd(), "walksafe-field-logs");
}

async function withSessionOperation<T>(sessionId: string, operation: () => Promise<T>): Promise<T> {
  const previous = sessionOperations.get(sessionId) ?? Promise.resolve();
  let release: () => void = () => {};
  const current = new Promise<void>((resolve) => {
    release = resolve;
  });
  const queued = previous.catch(() => undefined).then(() => current);
  sessionOperations.set(sessionId, queued);
  await previous.catch(() => undefined);
  try {
    return await operation();
  } finally {
    release();
    if (sessionOperations.get(sessionId) === queued) sessionOperations.delete(sessionId);
  }
}

async function withStorageOperation<T>(operation: () => Promise<T>): Promise<T> {
  const previous = storageQueue;
  let release: () => void = () => {};
  storageQueue = new Promise<void>((resolve) => {
    release = resolve;
  });
  await previous.catch(() => undefined);
  try {
    return await operation();
  } finally {
    release();
  }
}

function fieldLogResponse(status: number, code: string, retryAfterSeconds?: number): Response {
  const headers: Record<string, string> = { "cache-control": "no-store" };
  if (retryAfterSeconds !== undefined) headers["retry-after"] = String(Math.max(1, retryAfterSeconds));
  return Response.json({ code }, { status, headers });
}

function rateLimitResponse(actorId: string, operation: FieldLogOperation, now: number): Response | null {
  for (const [key, state] of rateStates) {
    if (now - state.lastSeenAt > RATE_WINDOW_MS * 2 || state.lastSeenAt > now) rateStates.delete(key);
  }
  const actorBinding = createHash("sha256").update(`walksafe-field-rate-v1\0${actorId}`).digest("hex");
  const limits: Array<[string, number]> = operation === "post"
    ? [["post:global", POST_GLOBAL_RATE_LIMIT], [`post:actor:${actorBinding}`, POST_ACTOR_RATE_LIMIT]]
    : [["delete:global", DELETE_GLOBAL_RATE_LIMIT], [`delete:actor:${actorBinding}`, DELETE_ACTOR_RATE_LIMIT]];
  const missingStates = limits.filter(([key]) => !rateStates.has(key)).length;
  if (rateStates.size + missingStates > MAX_RATE_STATES) {
    return fieldLogResponse(503, "field_log_rate_state_unavailable", 1);
  }
  for (const [key, limit] of limits) {
    const previous = rateStates.get(key);
    const state = previous && now - previous.startedAt < RATE_WINDOW_MS
      ? previous
      : { startedAt: now, count: 0, lastSeenAt: now };
    if (state.count >= limit) {
      const retryAfter = Math.ceil((state.startedAt + RATE_WINDOW_MS - now) / 1000);
      const code = key.endsWith(":global")
        ? "field_log_global_rate_limited"
        : "field_log_actor_rate_limited";
      return fieldLogResponse(429, code, retryAfter);
    }
  }
  for (const [key] of limits) {
    const previous = rateStates.get(key);
    const state = previous && now - previous.startedAt < RATE_WINDOW_MS
      ? previous
      : { startedAt: now, count: 0, lastSeenAt: now };
    state.count += 1;
    state.lastSeenAt = now;
    rateStates.set(key, state);
  }
  return null;
}

function acquireAdmission(
  actorId: string,
  operation: FieldLogOperation,
  now = Date.now()
): { release: () => void; error?: never } | { release?: never; error: Response } {
  if (!Number.isFinite(now) || now < 0) {
    return { error: fieldLogResponse(503, "field_log_admission_unavailable", 1) };
  }
  const limited = rateLimitResponse(actorId, operation, now);
  if (limited) return { error: limited };
  const current = operation === "post" ? postsInFlight : deletesInFlight;
  const maximum = operation === "post" ? MAX_POSTS_IN_FLIGHT : MAX_DELETES_IN_FLIGHT;
  if (current >= maximum) {
    return { error: fieldLogResponse(503, "field_log_busy", 1) };
  }
  if (operation === "post") postsInFlight += 1;
  else deletesInFlight += 1;
  let released = false;
  return {
    release: () => {
      if (released) return;
      released = true;
      if (operation === "post") postsInFlight = Math.max(0, postsInFlight - 1);
      else deletesInFlight = Math.max(0, deletesInFlight - 1);
    }
  };
}

function revokedSessionPath(root: string, sessionId: string): string {
  return path.join(root, ".revoked", `${sessionId}.revoked`);
}

function sessionOwnerPath(root: string, sessionId: string): string {
  return path.join(root, ".owners", `${sessionId}.owner`);
}

function actorOwnerBinding(actorId: string): string {
  return createHash("sha256").update(`walksafe-field-owner-v1\0${actorId}`).digest("hex");
}

function configuredStorageInteger(
  name: string,
  fallback: number,
  minimum: number,
  maximum: number
): number | null {
  const raw = process.env[name];
  if (raw === undefined) return fallback;
  if (!/^(?:0|[1-9][0-9]*)$/.test(raw)) return null;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value >= minimum && value <= maximum ? value : null;
}

function storageLimits(): StorageLimits | null {
  const maxTotalBytes = configuredStorageInteger(
    "WALKSAFE_FIELD_LOG_MAX_TOTAL_BYTES",
    DEFAULT_MAX_TOTAL_BYTES,
    1,
    1024 * 1024 * 1024 * 1024
  );
  const maxSessions = configuredStorageInteger(
    "WALKSAFE_FIELD_LOG_MAX_SESSIONS",
    DEFAULT_MAX_SESSIONS,
    1,
    100_000
  );
  const maxFiles = configuredStorageInteger(
    "WALKSAFE_FIELD_LOG_MAX_FILES",
    DEFAULT_MAX_FILES,
    1,
    500_000
  );
  const minFreeBytes = configuredStorageInteger(
    "WALKSAFE_FIELD_LOG_MIN_FREE_BYTES",
    DEFAULT_MIN_FREE_BYTES,
    0,
    1024 * 1024 * 1024 * 1024
  );
  const minFreeInodes = configuredStorageInteger(
    "WALKSAFE_FIELD_LOG_MIN_FREE_INODES",
    DEFAULT_MIN_FREE_INODES,
    0,
    1_000_000_000
  );
  if (
    maxTotalBytes === null ||
    maxSessions === null ||
    maxFiles === null ||
    minFreeBytes === null ||
    minFreeInodes === null
  ) {
    return null;
  }
  return { maxTotalBytes, maxSessions, maxFiles, minFreeBytes, minFreeInodes };
}

async function ensureStorageRoot(root: string): Promise<void> {
  await mkdir(root, { recursive: true, mode: 0o700 });
  const metadata = await lstat(root);
  const currentUid = typeof process.getuid === "function" ? process.getuid() : metadata.uid;
  if (!metadata.isDirectory() || metadata.isSymbolicLink() || metadata.uid !== currentUid) {
    throw new Error("field telemetry storage root is unsafe");
  }
  await chmod(root, 0o700);
}

function sessionIdFromStoredFile(directoryName: string, fileName: string): string | null {
  const suffix = directoryName === ".owners"
    ? ".owner"
    : directoryName === ".revoked"
      ? ".revoked"
      : ".jsonl";
  if (!fileName.endsWith(suffix)) return null;
  const sessionId = fileName.slice(0, -suffix.length);
  return SAFE_SESSION_ID.test(sessionId) ? sessionId : null;
}

async function inspectStorage(root: string): Promise<StorageUsage> {
  const usage: StorageUsage = {
    totalBytes: 0,
    fileCount: 0,
    sessionIds: new Set<string>(),
    directories: new Set<string>()
  };
  const currentUid = typeof process.getuid === "function" ? process.getuid() : null;
  const rootEntries = await readdir(root, { withFileTypes: true });
  for (const rootEntry of rootEntries) {
    if (rootEntry.isSymbolicLink() || !rootEntry.isDirectory()) {
      throw new Error("field telemetry storage contains an unsafe root entry");
    }
    const parsedDate = Date.parse(`${rootEntry.name}T00:00:00.000Z`);
    const isDateDirectory =
      /^\d{4}-\d{2}-\d{2}$/.test(rootEntry.name) &&
      Number.isFinite(parsedDate) &&
      new Date(parsedDate).toISOString().slice(0, 10) === rootEntry.name;
    if (!isDateDirectory && rootEntry.name !== ".owners" && rootEntry.name !== ".revoked") {
      throw new Error("field telemetry storage contains an unknown directory");
    }
    const directoryPath = path.join(root, rootEntry.name);
    const directoryMetadata = await lstat(directoryPath);
    if (
      !directoryMetadata.isDirectory() ||
      directoryMetadata.isSymbolicLink() ||
      (currentUid !== null && directoryMetadata.uid !== currentUid) ||
      (directoryMetadata.mode & 0o077) !== 0
    ) {
      throw new Error("field telemetry storage directory is unsafe");
    }
    usage.directories.add(rootEntry.name);
    const files = await readdir(directoryPath, { withFileTypes: true });
    for (const file of files) {
      if (file.isSymbolicLink() || !file.isFile()) {
        throw new Error("field telemetry storage contains an unsafe file entry");
      }
      const sessionId = sessionIdFromStoredFile(rootEntry.name, file.name);
      if (!sessionId) throw new Error("field telemetry storage contains an unknown file");
      const metadata = await lstat(path.join(directoryPath, file.name));
      if (
        !metadata.isFile() ||
        metadata.isSymbolicLink() ||
        metadata.nlink !== 1 ||
        (currentUid !== null && metadata.uid !== currentUid) ||
        (metadata.mode & 0o077) !== 0 ||
        !Number.isSafeInteger(metadata.size) ||
        metadata.size < 0
      ) {
        throw new Error("field telemetry storage file is unsafe");
      }
      usage.fileCount += 1;
      usage.totalBytes += metadata.size;
      if (!Number.isSafeInteger(usage.totalBytes)) {
        throw new Error("field telemetry storage size is unsafe");
      }
      usage.sessionIds.add(sessionId);
    }
  }
  return usage;
}

async function storageGuardResponse(
  root: string,
  usage: StorageUsage,
  additions: { bytes: number; files: number; sessions: number; inodes: number },
  allowExistingOverage = false
): Promise<Response | null> {
  const limits = storageLimits();
  if (!limits) return fieldLogResponse(503, "field_log_storage_config_invalid", 1);
  const values = Object.values(additions);
  if (values.some((value) => !Number.isSafeInteger(value) || value < 0)) {
    return fieldLogResponse(503, "field_log_storage_unavailable", 1);
  }
  const fileSystem = await statfs(root);
  const availableBytes = fileSystem.bavail * fileSystem.bsize;
  const freeInodes = fileSystem.ffree;
  if (!Number.isSafeInteger(availableBytes) || availableBytes < 0 || !Number.isSafeInteger(freeInodes) || freeInodes < 0) {
    return fieldLogResponse(503, "field_log_storage_unavailable", 1);
  }
  if (additions.bytes > 0 && availableBytes - additions.bytes < limits.minFreeBytes) {
    return fieldLogResponse(507, "field_log_storage_low_disk");
  }
  if (additions.inodes > 0 && freeInodes - additions.inodes < limits.minFreeInodes) {
    return fieldLogResponse(507, "field_log_storage_low_inodes");
  }
  if ((!allowExistingOverage || additions.bytes > 0) && usage.totalBytes + additions.bytes > limits.maxTotalBytes) {
    return fieldLogResponse(507, "field_log_storage_total_quota");
  }
  if ((!allowExistingOverage || additions.files > 0) && usage.fileCount + additions.files > limits.maxFiles) {
    return fieldLogResponse(507, "field_log_storage_file_quota");
  }
  if ((!allowExistingOverage || additions.sessions > 0) && usage.sessionIds.size + additions.sessions > limits.maxSessions) {
    return fieldLogResponse(507, "field_log_storage_session_quota");
  }
  return null;
}

async function readBoundedSidecar(filePath: string): Promise<string | null> {
  let handle: Awaited<ReturnType<typeof open>> | null = null;
  try {
    handle = await open(filePath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = await handle.stat();
    const currentUid = typeof process.getuid === "function" ? process.getuid() : metadata.uid;
    if (
      !metadata.isFile() ||
      metadata.nlink !== 1 ||
      metadata.uid !== currentUid ||
      (metadata.mode & 0o077) !== 0 ||
      metadata.size < 1 ||
      metadata.size > 256
    ) {
      throw new Error("field telemetry sidecar is unsafe");
    }
    const buffer = Buffer.alloc(257);
    const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
    if (bytesRead > 256) throw new Error("field telemetry sidecar is oversized");
    return buffer.subarray(0, bytesRead).toString("utf8").trim();
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    throw error;
  } finally {
    await handle?.close();
  }
}

async function readSessionOwner(root: string, sessionId: string): Promise<string | null> {
  const owner = await readBoundedSidecar(sessionOwnerPath(root, sessionId));
  if (owner !== null && !/^[0-9a-f]{64}$/.test(owner)) {
    throw new Error("field telemetry owner binding is invalid");
  }
  return owner;
}

async function persistedSessionActors(root: string, sessionId: string): Promise<Set<string>> {
  const actors = new Set<string>();
  const entries = await readdir(root, { withFileTypes: true });
  for (const entry of entries) {
    if (!entry.isDirectory() || !/^\d{4}-\d{2}-\d{2}$/.test(entry.name)) continue;
    let handle: Awaited<ReturnType<typeof open>> | null = null;
    try {
      handle = await open(path.join(root, entry.name, `${sessionId}.jsonl`), "r");
      const buffer = Buffer.alloc(64 * 1024);
      const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
      const firstLine = buffer.subarray(0, bytesRead).toString("utf8").split("\n", 1)[0];
      const record = JSON.parse(firstLine) as { actor_id?: unknown };
      if (typeof record.actor_id === "string" && record.actor_id) actors.add(record.actor_id);
      else actors.add("__unknown__");
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "ENOENT") actors.add("__unknown__");
    } finally {
      await handle?.close();
    }
  }
  return actors;
}

async function claimSessionOwner(root: string, sessionId: string, actorId: string): Promise<boolean> {
  const ownerBinding = actorOwnerBinding(actorId);
  const existingOwner = await readSessionOwner(root, sessionId);
  if (existingOwner) return existingOwner === ownerBinding;

  const persistedActors = await persistedSessionActors(root, sessionId);
  if (persistedActors.size > 0 && (persistedActors.size !== 1 || !persistedActors.has(actorId))) {
    return false;
  }

  const directory = path.join(root, ".owners");
  await mkdir(directory, { recursive: true, mode: 0o700 });
  await chmod(directory, 0o700);
  try {
    await writeFile(sessionOwnerPath(root, sessionId), `${ownerBinding}\n`, {
      encoding: "utf8",
      mode: 0o600,
      flag: "wx"
    });
    return true;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
    return (await readSessionOwner(root, sessionId)) === ownerBinding;
  }
}

async function readSessionRevocation(root: string, sessionId: string): Promise<SessionRevocation> {
  const value = await readBoundedSidecar(revokedSessionPath(root, sessionId));
  if (value === null) return { revoked: false, ownerBinding: null };
  if (/^[0-9a-f]{64}$/.test(value)) return { revoked: true, ownerBinding: value };
  if (Number.isFinite(Date.parse(value))) return { revoked: true, ownerBinding: null };
  throw new Error("field telemetry revocation binding is invalid");
}

async function revokeSession(
  root: string,
  sessionId: string,
  ownerBinding: string,
  revocation: SessionRevocation,
  existingOwner: string | null
): Promise<void> {
  const directory = path.join(root, ".revoked");
  await mkdir(directory, { recursive: true, mode: 0o700 });
  await chmod(directory, 0o700);
  const revokedPath = revokedSessionPath(root, sessionId);
  if (!revocation.revoked && existingOwner) {
    await rename(sessionOwnerPath(root, sessionId), revokedPath);
    await chmod(revokedPath, 0o600);
    return;
  }
  if (!revocation.revoked) {
    try {
      await writeFile(revokedPath, `${ownerBinding}\n`, {
        encoding: "utf8",
        mode: 0o600,
        flag: "wx"
      });
      return;
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error;
      const raced = await readSessionRevocation(root, sessionId);
      if (raced.ownerBinding !== ownerBinding) throw new Error("field telemetry revocation owner mismatch");
      return;
    }
  }
  if (revocation.ownerBinding === null) {
    const handle = await open(
      revokedPath,
      fsConstants.O_WRONLY | fsConstants.O_NOFOLLOW
    );
    try {
      const metadata = await handle.stat();
      const currentUid = typeof process.getuid === "function" ? process.getuid() : metadata.uid;
      if (
        !metadata.isFile() ||
        metadata.nlink !== 1 ||
        metadata.uid !== currentUid ||
        (metadata.mode & 0o077) !== 0
      ) {
        throw new Error("field telemetry revocation is unsafe");
      }
      await handle.truncate(0);
      await handle.writeFile(`${ownerBinding}\n`, { encoding: "utf8" });
      await handle.sync();
    } finally {
      await handle.close();
    }
  }
  if (existingOwner) await rm(sessionOwnerPath(root, sessionId));
}

async function regularFileExists(filePath: string): Promise<boolean> {
  try {
    const metadata = await lstat(filePath);
    if (!metadata.isFile() || metadata.isSymbolicLink()) {
      throw new Error("field telemetry path is not a regular file");
    }
    return true;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return false;
    throw error;
  }
}

function retentionDays(): number {
  const parsed = Number(
    process.env.WALKSAFE_FIELD_LOG_RETENTION_DAYS ?? "7"
  );
  return Number.isFinite(parsed) && parsed > 0 ? Math.min(30, Math.floor(parsed)) : 7;
}

async function removeExpiredDirectories(root: string): Promise<void> {
  const cutoff = Date.now() - retentionDays() * 24 * 60 * 60 * 1000;
  const entries = await readdir(root, { withFileTypes: true });
  await Promise.all(
    entries.map(async (entry) => {
      if (!entry.isDirectory() || !/^\d{4}-\d{2}-\d{2}$/.test(entry.name)) return;
      const folderStartMs = Date.parse(`${entry.name}T00:00:00Z`);
      const folderExpiresAtMs = folderStartMs + 24 * 60 * 60 * 1000;
      if (Number.isFinite(folderStartMs) && folderExpiresAtMs <= cutoff) {
        await rm(path.join(root, entry.name), { recursive: true, force: true });
      }
    })
  );
  const sidecarCutoff = cutoff - 24 * 60 * 60 * 1000;
  for (const [directoryName, suffix] of [[".owners", ".owner"], [".revoked", ".revoked"]] as const) {
    const directory = path.join(root, directoryName);
    let sidecars: Dirent[];
    try {
      sidecars = await readdir(directory, { withFileTypes: true });
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") continue;
      throw error;
    }
    await Promise.all(
      sidecars.map(async (entry) => {
        if (!entry.isFile() || !entry.name.endsWith(suffix)) return;
        const sidecarPath = path.join(directory, entry.name);
        if ((await stat(sidecarPath)).mtimeMs <= sidecarCutoff) {
          await rm(sidecarPath);
        }
      })
    );
  }
}

function roundLocationCoordinate(key: string, value: number): number {
  return key === "latitude" || key === "longitude" ? Number(value.toFixed(5)) : value;
}

function sanitizePayloadValue(value: unknown, key: string, depth: number, budget: { nodes: number }): unknown {
  budget.nodes += 1;
  if (budget.nodes > 500 || depth > 5) throw new Error("payload_complexity");
  if (value === null || typeof value === "boolean") return value;
  if (typeof value === "number") {
    if (!Number.isFinite(value)) throw new Error("payload_number");
    if (key === "latitude" && (value < -90 || value > 90)) throw new Error("payload_latitude");
    if (key === "longitude" && (value < -180 || value > 180)) throw new Error("payload_longitude");
    return roundLocationCoordinate(key, value);
  }
  if (typeof value === "string") {
    const maxLength = key === "user_agent" ? 256 : 512;
    if (value.length > maxLength || value.startsWith("data:") || BASE64_LIKE_VALUE.test(value)) {
      throw new Error("payload_string");
    }
    return value;
  }
  if (Array.isArray(value)) {
    if (value.length > 40) throw new Error("payload_array");
    return value.map((entry) => sanitizePayloadValue(entry, key, depth + 1, budget));
  }
  if (typeof value !== "object") throw new Error("payload_type");
  const entries = Object.entries(value as Record<string, unknown>);
  if (entries.length > 40) throw new Error("payload_object");
  const result: Record<string, unknown> = {};
  for (const [childKey, childValue] of entries) {
    if (
      !SAFE_PAYLOAD_KEY.test(childKey) ||
      !NESTED_PAYLOAD_KEYS.has(childKey) ||
      FORBIDDEN_MEDIA_KEY.test(childKey)
    ) {
      throw new Error("payload_key");
    }
    result[childKey] = sanitizePayloadValue(childValue, childKey, depth + 1, budget);
  }
  return result;
}

export function validateEnvelope(value: unknown, now = Date.now()): FieldTelemetryEnvelope | null {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return null;
  const envelopeKeys = Object.keys(value);
  if (envelopeKeys.length !== ENVELOPE_KEYS.size || envelopeKeys.some((key) => !ENVELOPE_KEYS.has(key))) {
    return null;
  }
  const envelope = value as Partial<FieldTelemetryEnvelope>;
  if (envelope.schema_version !== "walksafe.field-telemetry.v1") return null;
  if (typeof envelope.session_id !== "string" || !SAFE_SESSION_ID.test(envelope.session_id)) return null;
  if (
    typeof envelope.captured_at !== "string" ||
    Number.isNaN(Date.parse(envelope.captured_at)) ||
    Math.abs(now - Date.parse(envelope.captured_at)) > MAX_CAPTURE_CLOCK_SKEW_MS ||
    typeof envelope.event_type !== "string" ||
    !EVENT_TYPES.has(envelope.event_type)
  ) {
    return null;
  }
  if (typeof envelope.payload !== "object" || envelope.payload === null || Array.isArray(envelope.payload)) {
    return null;
  }
  const payloadEntries = Object.entries(envelope.payload);
  if (payloadEntries.some(([key]) => !PAYLOAD_KEYS.has(key))) return null;
  try {
    const budget = { nodes: 0 };
    const payload = Object.fromEntries(
      payloadEntries.map(([key, payloadValue]) => [key, sanitizePayloadValue(payloadValue, key, 0, budget)])
    );
    return {
      schema_version: envelope.schema_version,
      session_id: envelope.session_id,
      captured_at: envelope.captured_at,
      event_type: envelope.event_type,
      payload
    };
  } catch {
    return null;
  }
}

export async function POST(request: Request): Promise<Response> {
  if (!isFieldSessionAuthorized(request)) return gatewayUnauthorizedResponse();
  const actorId = gatewaySessionActor(request, "field");
  if (!actorId) return gatewayUnauthorizedResponse();
  if (request.headers.get("x-walksafe-telemetry-consent") !== CONSENT_VERSION) {
    return fieldLogResponse(403, "field_log_consent_required");
  }
  const receivedAt = new Date();
  const admission = acquireAdmission(actorId, "post", receivedAt.getTime());
  if (admission.error) return admission.error;
  try {
    const bounded = await readBoundedTextBody(request, MAX_BODY_BYTES);
    if (bounded.error) return bounded.error;
    let decoded: unknown;
    try {
      decoded = JSON.parse(bounded.text);
    } catch {
      return fieldLogResponse(400, "field_log_invalid_json");
    }
    const envelope = validateEnvelope(decoded, receivedAt.getTime());
    if (!envelope) return fieldLogResponse(422, "field_log_invalid_envelope");
    const serverSourceCommit = await serverSourceCommitPromise;
    if (
      SOURCE_IDENTITY_REQUIRED_ENVIRONMENTS.has((process.env.WALKSAFE_ENVIRONMENT ?? "").trim().toLowerCase()) &&
      serverSourceCommit === null
    ) {
      return fieldLogResponse(503, "field_log_source_identity_not_ready", 1);
    }

    return await withSessionOperation(envelope.session_id, () => withStorageOperation(async () => {
      if (!storageLimits()) return fieldLogResponse(503, "field_log_storage_config_invalid", 1);
      const root = logRoot();
      try {
        await ensureStorageRoot(root);
        await removeExpiredDirectories(root);
        const usage = await inspectStorage(root);
        const ownerBinding = actorOwnerBinding(actorId);
        const revocation = await readSessionRevocation(root, envelope.session_id);
        if (revocation.ownerBinding && revocation.ownerBinding !== ownerBinding) {
          return fieldLogResponse(403, "field_log_session_owner_mismatch");
        }
        if (revocation.revoked) return fieldLogResponse(409, "field_log_consent_withdrawn");
        const existingOwner = await readSessionOwner(root, envelope.session_id);
        const persistedActors = existingOwner ? null : await persistedSessionActors(root, envelope.session_id);
        if (
          (existingOwner && existingOwner !== ownerBinding) ||
          (persistedActors && (persistedActors.size !== 0) &&
            (persistedActors.size !== 1 || !persistedActors.has(actorId)))
        ) {
          return fieldLogResponse(403, "field_log_session_owner_mismatch");
        }
        const dateName = receivedAt.toISOString().slice(0, 10);
        const dateDirectory = path.join(root, dateName);
        const record = {
          ...envelope,
          actor_id: actorId,
          consent_version: CONSENT_VERSION,
          server_source_commit: serverSourceCommit,
          received_at: receivedAt.toISOString()
        };
        const recordLine = `${JSON.stringify(record)}\n`;
        const logExists = await regularFileExists(path.join(dateDirectory, `${envelope.session_id}.jsonl`));
        const ownerFileAddition = existingOwner ? 0 : 1;
        const logFileAddition = logExists ? 0 : 1;
        const storageDenied = await storageGuardResponse(root, usage, {
          bytes: Buffer.byteLength(recordLine, "utf8") + ownerFileAddition * 65,
          files: ownerFileAddition + logFileAddition,
          sessions: usage.sessionIds.has(envelope.session_id) ? 0 : 1,
          inodes:
            ownerFileAddition +
            logFileAddition +
            (usage.directories.has(".owners") ? 0 : 1) +
            (usage.directories.has(dateName) ? 0 : 1)
        });
        if (storageDenied) return storageDenied;
        if (!(await claimSessionOwner(root, envelope.session_id, actorId))) {
          return fieldLogResponse(403, "field_log_session_owner_mismatch");
        }
        await mkdir(dateDirectory, { recursive: true, mode: 0o700 });
        await chmod(dateDirectory, 0o700);
        await appendFile(path.join(dateDirectory, `${envelope.session_id}.jsonl`), recordLine, {
          encoding: "utf8",
          mode: 0o600
        });
        return Response.json(
          { ok: true, session_id: envelope.session_id, event_type: envelope.event_type },
          { status: 201, headers: { "cache-control": "no-store" } }
        );
      } catch {
        return fieldLogResponse(503, "field_log_storage_unsafe", 1);
      }
    }));
  } finally {
    admission.release();
  }
}

export async function DELETE(request: Request): Promise<Response> {
  if (!isFieldSessionAuthorized(request)) return gatewayUnauthorizedResponse();
  const actorId = gatewaySessionActor(request, "field");
  if (!actorId) return gatewayUnauthorizedResponse();
  const sessionId = new URL(request.url).searchParams.get("session_id") ?? "";
  if (!SAFE_SESSION_ID.test(sessionId)) {
    return fieldLogResponse(400, "field_log_invalid_session_id");
  }
  const admission = acquireAdmission(actorId, "delete");
  if (admission.error) return admission.error;
  try {
    return await withSessionOperation(sessionId, () => withStorageOperation(async () => {
      if (!storageLimits()) return fieldLogResponse(503, "field_log_storage_config_invalid", 1);
      const root = logRoot();
      try {
        await ensureStorageRoot(root);
        await removeExpiredDirectories(root);
        const usage = await inspectStorage(root);
        const ownerBinding = actorOwnerBinding(actorId);
        const revocation = await readSessionRevocation(root, sessionId);
        const existingOwner = await readSessionOwner(root, sessionId);
        const persistedActors = existingOwner ? null : await persistedSessionActors(root, sessionId);
        if (
          (revocation.ownerBinding && revocation.ownerBinding !== ownerBinding) ||
          (existingOwner && existingOwner !== ownerBinding) ||
          (persistedActors && persistedActors.size > 0 &&
            (persistedActors.size !== 1 || !persistedActors.has(actorId))) ||
          (revocation.revoked && revocation.ownerBinding === null && !existingOwner && persistedActors?.size === 0)
        ) {
          return fieldLogResponse(403, "field_log_session_owner_mismatch");
        }
        const createsTombstone = !revocation.revoked && !existingOwner;
        const storageDenied = await storageGuardResponse(
          root,
          usage,
          {
            bytes: createsTombstone ? 65 : 0,
            files: createsTombstone ? 1 : 0,
            sessions: createsTombstone && !usage.sessionIds.has(sessionId) ? 1 : 0,
            inodes:
              (createsTombstone ? 1 : 0) +
              (usage.directories.has(".revoked") ? 0 : 1)
          },
          true
        );
        if (storageDenied) return storageDenied;
        await revokeSession(root, sessionId, ownerBinding, revocation, existingOwner);
        const entries = await readdir(root, { withFileTypes: true });
        let deleted = false;
        await Promise.all(
          entries.map(async (entry) => {
            if (!entry.isDirectory() || !/^\d{4}-\d{2}-\d{2}$/.test(entry.name)) return;
            const filePath = path.join(root, entry.name, `${sessionId}.jsonl`);
            try {
              await rm(filePath);
              deleted = true;
            } catch (error) {
              if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
            }
          })
        );
        return Response.json({ ok: true, deleted }, { headers: { "cache-control": "no-store" } });
      } catch {
        return fieldLogResponse(503, "field_log_storage_unsafe", 1);
      }
    }));
  } finally {
    admission.release();
  }
}
