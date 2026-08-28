import { randomBytes } from "node:crypto";
import {
  chmodSync,
  closeSync,
  constants as fsConstants,
  fstatSync,
  fsyncSync,
  openSync,
  readFileSync,
  renameSync,
  rmSync,
  writeFileSync
} from "node:fs";
import path from "node:path";

import { gatewayTokenForBackend } from "./auth.js";
import {
  backendUrl,
  fetchBackend,
  FIELD_TEST_TOKEN_HEADER,
  type GatewayFetch
} from "./backend.js";
import { resolveGatewayStateDirectory } from "./config.js";
import {
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  maxGatewayStateEnvelopeBytes,
  parsePlaintextGatewayStateForMaintenance
} from "./encrypted-json-store.js";
import { withExclusiveFileLock } from "./exclusive-file-lock.js";

export const SERVER_CAPACITY_MAX_PLAINTEXT_BYTES = 4 * 1024;
export const SERVER_CAPACITY_STATE_RECORD_ID = "snapshot.json";
export const SERVER_CAPACITY_LEVELS = Object.freeze([
  "NORMAL",
  "ADMIN_ONLY_WARNING",
  "PAUSE_NEW_FIELD_TEST_PARTICIPANTS",
  "HOLD_NEW_RAW_COLLECTION_SESSIONS",
  "HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES"
] as const);

const SERVER_CAPACITY_RESPONSE_MAX_BYTES = 4 * 1024;
const SERVER_CAPACITY_FETCH_TIMEOUT_MS = 1_000;
const MIN_FIELD_TOKEN_LENGTH = 24;
const UTC_RFC3339 = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,6}))?Z$/;
const CAPACITY_KEYS = [
  "version",
  "observed_at",
  "expires_at",
  "level",
  "reason"
] as const;
let capacityFetchInFlight: Promise<ServerCapacitySnapshot | null> | null = null;
let telemetryCapacitySnapshot: ServerCapacitySnapshot | null = null;

export type ServerCapacityLevel = typeof SERVER_CAPACITY_LEVELS[number];
export type ServerCapacityTelemetryLevel = ServerCapacityLevel | "UNKNOWN";

export type ServerCapacitySnapshot = {
  version: number;
  observed_at: string;
  expires_at: string;
  level: ServerCapacityLevel;
  reason: "STORAGE_UTILIZATION";
};

function objectValue(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function exactKeys(value: Record<string, unknown>): boolean {
  const actual = Object.keys(value).sort();
  const expected = [...CAPACITY_KEYS].sort();
  return actual.length === expected.length &&
    actual.every((key, index) => key === expected[index]);
}

function utcRfc3339Microseconds(value: unknown): bigint | null {
  if (typeof value !== "string") return null;
  const match = UTC_RFC3339.exec(value);
  if (!match) return null;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const hour = Number(match[4]);
  const minute = Number(match[5]);
  const second = Number(match[6]);
  if (
    year < 1 ||
    month < 1 || month > 12 ||
    day < 1 || day > 31 ||
    hour > 23 || minute > 59 || second > 59
  ) return null;

  const timestamp = new Date(0);
  timestamp.setUTCFullYear(year, month - 1, day);
  timestamp.setUTCHours(hour, minute, second, 0);
  if (
    timestamp.getUTCFullYear() !== year ||
    timestamp.getUTCMonth() !== month - 1 ||
    timestamp.getUTCDate() !== day ||
    timestamp.getUTCHours() !== hour ||
    timestamp.getUTCMinutes() !== minute ||
    timestamp.getUTCSeconds() !== second
  ) return null;
  const fractionalMicros = BigInt((match[7] ?? "").padEnd(6, "0"));
  return BigInt(timestamp.getTime()) * 1_000n + fractionalMicros;
}

export function validServerCapacitySnapshotForMaintenance(
  value: unknown
): value is ServerCapacitySnapshot {
  const snapshot = objectValue(value);
  if (
    !snapshot ||
    !exactKeys(snapshot) ||
    !Number.isSafeInteger(snapshot.version) ||
    Number(snapshot.version) <= 0 ||
    typeof snapshot.level !== "string" ||
    !SERVER_CAPACITY_LEVELS.includes(snapshot.level as ServerCapacityLevel) ||
    snapshot.reason !== "STORAGE_UTILIZATION"
  ) return false;
  const observedAt = utcRfc3339Microseconds(snapshot.observed_at);
  const expiresAt = utcRfc3339Microseconds(snapshot.expires_at);
  return observedAt !== null && expiresAt !== null && observedAt < expiresAt;
}

function freshSnapshot(
  snapshot: ServerCapacitySnapshot | null,
  nowEpochMs: number
): ServerCapacitySnapshot | null {
  if (
    snapshot === null ||
    !Number.isSafeInteger(nowEpochMs) ||
    nowEpochMs < 0
  ) return null;
  const expiresAt = utcRfc3339Microseconds(snapshot.expires_at);
  return expiresAt !== null && expiresAt > BigInt(nowEpochMs) * 1_000n
    ? snapshot
    : null;
}

export function currentServerCapacityLevelForTelemetry(
  nowEpochMs = Date.now()
): ServerCapacityTelemetryLevel {
  if (!Number.isSafeInteger(nowEpochMs) || nowEpochMs < 0) return "UNKNOWN";
  return freshSnapshot(telemetryCapacitySnapshot, nowEpochMs)?.level ?? "UNKNOWN";
}

export function resetServerCapacityForTests(): void {
  telemetryCapacitySnapshot = null;
  capacityFetchInFlight = null;
}

function sameSnapshot(
  left: ServerCapacitySnapshot,
  right: ServerCapacitySnapshot
): boolean {
  return CAPACITY_KEYS.every((key) => left[key] === right[key]);
}

export function resolveServerCapacityStatePath(
  environment: NodeJS.ProcessEnv = process.env
): string {
  return path.join(
    resolveGatewayStateDirectory(environment),
    "server-capacity",
    SERVER_CAPACITY_STATE_RECORD_ID
  );
}

function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || uid === process.getuid();
}

function readPersistedSnapshot(target: string): ServerCapacitySnapshot | null {
  let descriptor: number | null = null;
  try {
    descriptor = openSync(target, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() ||
      metadata.nlink !== 1 ||
      !ownedByCurrentProcess(metadata.uid) ||
      (metadata.mode & 0o777) !== 0o600 ||
      metadata.size < 2 ||
      metadata.size > maxGatewayStateEnvelopeBytes(SERVER_CAPACITY_MAX_PLAINTEXT_BYTES)
    ) {
      throw new Error("server capacity state must be an owned regular file with mode 0600");
    }
    const value = decryptGatewayStateJson(
      { kind: "server-capacity", recordId: SERVER_CAPACITY_STATE_RECORD_ID },
      readFileSync(descriptor, "utf8"),
      SERVER_CAPACITY_MAX_PLAINTEXT_BYTES
    ).value;
    if (!validServerCapacitySnapshotForMaintenance(value)) {
      throw new Error("server capacity state is invalid");
    }
    return value;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    throw error;
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

function writePersistedSnapshot(
  target: string,
  snapshot: ServerCapacitySnapshot
): void {
  const encoded = encryptGatewayStateJson(
    { kind: "server-capacity", recordId: SERVER_CAPACITY_STATE_RECORD_ID },
    snapshot,
    SERVER_CAPACITY_MAX_PLAINTEXT_BYTES
  );
  const temporary = `${target}.${process.pid}.${randomBytes(8).toString("hex")}.tmp`;
  let descriptor: number | null = null;
  let directoryDescriptor: number | null = null;
  try {
    descriptor = openSync(
      temporary,
      fsConstants.O_WRONLY |
        fsConstants.O_CREAT |
        fsConstants.O_EXCL |
        fsConstants.O_NOFOLLOW,
      0o600
    );
    writeFileSync(descriptor, encoded, "utf8");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    chmodSync(temporary, 0o600);
    renameSync(temporary, target);
    directoryDescriptor = openSync(
      path.dirname(target),
      fsConstants.O_RDONLY |
        fsConstants.O_DIRECTORY |
        fsConstants.O_NOFOLLOW
    );
    fsyncSync(directoryDescriptor);
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    if (directoryDescriptor !== null) closeSync(directoryDescriptor);
    rmSync(temporary, { force: true });
  }
}

async function waitForSignal<T>(promise: Promise<T>, signal: AbortSignal): Promise<T> {
  if (signal.aborted) throw signal.reason;
  let rejectOnAbort: (reason?: unknown) => void = () => undefined;
  const aborted = new Promise<never>((_resolve, reject) => {
    rejectOnAbort = reject;
  });
  const onAbort = (): void => rejectOnAbort(signal.reason);
  signal.addEventListener("abort", onAbort, { once: true });
  try {
    return await Promise.race([promise, aborted]);
  } finally {
    signal.removeEventListener("abort", onAbort);
  }
}

async function readBoundedCapacityResponse(
  response: Response,
  deadline: AbortSignal
): Promise<ServerCapacitySnapshot | null> {
  if (!response.ok) {
    void response.body?.cancel("server capacity upstream response was not successful")
      .catch(() => undefined);
    return null;
  }
  const contentType = response.headers.get("content-type")
    ?.split(";", 1)[0]
    ?.trim()
    .toLowerCase();
  if (contentType !== "application/json") {
    void response.body?.cancel("server capacity response was not JSON")
      .catch(() => undefined);
    return null;
  }
  const declaredLength = response.headers.get("content-length");
  if (declaredLength !== null) {
    if (!/^\d+$/.test(declaredLength)) {
      void response.body?.cancel("server capacity content length was invalid")
        .catch(() => undefined);
      return null;
    }
    const parsedLength = Number(declaredLength);
    if (
      !Number.isSafeInteger(parsedLength) ||
      parsedLength < 2 ||
      parsedLength > SERVER_CAPACITY_RESPONSE_MAX_BYTES
    ) {
      void response.body?.cancel("server capacity response exceeded its byte ceiling")
        .catch(() => undefined);
      return null;
    }
  }
  if (!response.body) return null;

  const reader = response.body.getReader();
  const chunks: Uint8Array[] = [];
  let totalBytes = 0;
  try {
    while (true) {
      const chunk = await waitForSignal(reader.read(), deadline);
      if (chunk.done) break;
      totalBytes += chunk.value.byteLength;
      if (totalBytes > SERVER_CAPACITY_RESPONSE_MAX_BYTES) {
        void reader.cancel("server capacity response exceeded its byte ceiling")
          .catch(() => undefined);
        return null;
      }
      chunks.push(chunk.value);
    }
    if (totalBytes < 2) return null;
    const bytes = new Uint8Array(totalBytes);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    const raw = new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    const value = parsePlaintextGatewayStateForMaintenance(
      raw,
      SERVER_CAPACITY_RESPONSE_MAX_BYTES
    );
    return validServerCapacitySnapshotForMaintenance(value) ? value : null;
  } catch {
    void reader.cancel("server capacity response could not be read")
      .catch(() => undefined);
    return null;
  } finally {
    try {
      reader.releaseLock();
    } catch {
      // Cancellation can retain the lock until the upstream observes it.
    }
  }
}

async function fetchCapacitySnapshot(
  request: Request,
  fieldToken: string,
  fetchImpl: GatewayFetch,
  timeoutMs: number
): Promise<ServerCapacitySnapshot | null> {
  const boundedTimeoutMs = Number.isSafeInteger(timeoutMs)
    ? Math.max(1, Math.min(timeoutMs, SERVER_CAPACITY_FETCH_TIMEOUT_MS))
    : SERVER_CAPACITY_FETCH_TIMEOUT_MS;
  const deadlineController = new AbortController();
  const deadlineTimer = setTimeout(() => {
    deadlineController.abort(new Error("server capacity request exceeded its deadline"));
  }, boundedTimeoutMs);
  const deadline = deadlineController.signal;
  const headers = new Headers({
    accept: "application/json",
    [FIELD_TEST_TOKEN_HEADER]: fieldToken
  });
  try {
    const response = await waitForSignal(
      fetchBackend(
        request,
        backendUrl("/internal/capacity"),
        { method: "GET", headers, signal: deadline },
        boundedTimeoutMs,
        fetchImpl
      ),
      deadline
    );
    return await readBoundedCapacityResponse(response, deadline);
  } finally {
    clearTimeout(deadlineTimer);
  }
}

function fetchCapacitySnapshotSingleFlight(
  request: Request,
  fieldToken: string,
  fetchImpl: GatewayFetch,
  timeoutMs: number
): Promise<ServerCapacitySnapshot | null> {
  if (capacityFetchInFlight) return capacityFetchInFlight;
  const pending = fetchCapacitySnapshot(
    request,
    fieldToken,
    fetchImpl,
    timeoutMs
  ).finally(() => {
    if (capacityFetchInFlight === pending) capacityFetchInFlight = null;
  });
  capacityFetchInFlight = pending;
  return pending;
}

function cachedCapacity(nowEpochMs: number): ServerCapacitySnapshot | null {
  try {
    const target = resolveServerCapacityStatePath();
    return withExclusiveFileLock(`${target}.lock`, () =>
      freshSnapshot(readPersistedSnapshot(target), nowEpochMs)
    );
  } catch {
    return null;
  }
}

function acceptCapacitySnapshot(
  candidate: ServerCapacitySnapshot,
  nowEpochMs: number
): ServerCapacitySnapshot | null {
  const target = resolveServerCapacityStatePath();
  return withExclusiveFileLock(`${target}.lock`, () => {
    const persisted = readPersistedSnapshot(target);
    const fallback = freshSnapshot(persisted, nowEpochMs);
    if (freshSnapshot(candidate, nowEpochMs) === null) return fallback;
    if (persisted) {
      if (candidate.version < persisted.version) return fallback;
      if (candidate.version === persisted.version) {
        if (!sameSnapshot(candidate, persisted)) return fallback;
        return candidate;
      }
      const candidateObservedAt = utcRfc3339Microseconds(candidate.observed_at);
      const persistedObservedAt = utcRfc3339Microseconds(persisted.observed_at);
      if (
        candidateObservedAt === null ||
        persistedObservedAt === null ||
        candidateObservedAt < persistedObservedAt
      ) return fallback;
    }
    writePersistedSnapshot(target, candidate);
    return candidate;
  });
}

export async function serverCapacityForFieldSession(
  request: Request,
  fetchImpl: GatewayFetch = globalThis.fetch,
  nowEpochMs?: number,
  timeoutMs = SERVER_CAPACITY_FETCH_TIMEOUT_MS
): Promise<ServerCapacitySnapshot | null> {
  const fieldToken = gatewayTokenForBackend();
  let candidate: ServerCapacitySnapshot | null = null;
  if (fieldToken.length >= MIN_FIELD_TOKEN_LENGTH) {
    try {
      candidate = await fetchCapacitySnapshotSingleFlight(
        request,
        fieldToken,
        fetchImpl,
        timeoutMs
      );
    } catch {
      candidate = null;
    }
  }
  const now = nowEpochMs ?? Date.now();
  if (candidate) {
    try {
      const accepted = acceptCapacitySnapshot(candidate, now);
      telemetryCapacitySnapshot = accepted;
      return accepted;
    } catch {
      // Storage, encryption, replay, and lock failures never fail the session route.
    }
  }
  const cached = cachedCapacity(now);
  telemetryCapacitySnapshot = cached;
  return cached;
}

export async function mergeServerCapacityIntoFieldSessionResponse(
  request: Request,
  response: Response,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  if (response.status !== 200) return response;
  let body: Record<string, unknown> | null = null;
  try {
    body = objectValue(await response.clone().json());
  } catch {
    return response;
  }
  if (!body) return response;
  if (body.authenticated !== true) return response;
  try {
    const capacity = await serverCapacityForFieldSession(
      request,
      fetchImpl ?? globalThis.fetch
    );
    if (!capacity) return response;
    const headers = new Headers(response.headers);
    headers.delete("content-length");
    return Response.json(
      { ...body, capacity },
      {
        status: response.status,
        statusText: response.statusText,
        headers
      }
    );
  } catch {
    return response;
  }
}
