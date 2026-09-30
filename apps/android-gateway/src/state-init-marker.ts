import { randomUUID } from "node:crypto";
import {
  closeSync,
  constants as fsConstants,
  fstatSync,
  fsyncSync,
  lstatSync,
  openSync,
  readFileSync,
  renameSync,
  rmSync,
  writeFileSync
} from "node:fs";
import path from "node:path";

import { resolveGatewayStateDirectory } from "./config.js";
import { withExclusiveFileLock } from "./exclusive-file-lock.js";

/**
 * S19-26: distinguishes a genuine first run from a lost state file.
 *
 * The service start (assertGatewayManagedStateReady) creates this marker in the state root
 * when it is absent, listing the required ledgers that already exist. A ledger created
 * later registers itself after its first durable write. Once a ledger is listed, a missing
 * file is never treated as an empty ledger again: reads fail closed and the next start is
 * refused until an operator restores the file (or deliberately edits the marker with the
 * service stopped).
 *
 * The marker holds only absolute paths and a timestamp, so it is plain strict JSON (mode
 * 0600) and is not part of state-key rotation. Its name has no `.json` suffix so managed
 * JSON directory scans never see it. An empty or wrong state volume loses the marker too,
 * so production (NODE_ENV=production) requires the marker by default: a missing marker
 * refuses start unless the operator sets WALKSAFE_GATEWAY_STATE_INIT=first-run for the one
 * first installation start (owner decision 7, 2026-09-29). Outside production the marker
 * stays optional unless WALKSAFE_GATEWAY_STATE_INIT_MARKER=required.
 */
const MARKER_SCHEMA = "walksafe.gateway-state-init-marker.v1";
export const GATEWAY_STATE_INIT_MARKER_BASENAME = "state-init-marker.v1";
export const GATEWAY_STATE_INIT_MARKER_ENV = "WALKSAFE_GATEWAY_STATE_INIT_MARKER";
export const GATEWAY_STATE_INIT_ENV = "WALKSAFE_GATEWAY_STATE_INIT";
const MAX_MARKER_BYTES = 64 * 1024;
const MAX_REQUIRED_FILES = 64;

export class GatewayStateFileMissingError extends Error {
  readonly code = "state_file_missing";

  constructor(readonly filePath: string) {
    super(`required gateway state file is missing: ${filePath}`);
    this.name = "GatewayStateFileMissingError";
  }
}

export class GatewayStateInitMarkerError extends Error {
  readonly code = "state_init_marker_invalid";

  constructor(message: string) {
    super(message);
    this.name = "GatewayStateInitMarkerError";
  }
}

type Marker = {
  schema_version: typeof MARKER_SCHEMA;
  initialized_at_epoch_ms: number;
  required_files: string[];
};

function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid !== "function" || uid === process.getuid();
}

export function gatewayStateInitMarkerPath(
  environment: NodeJS.ProcessEnv = process.env
): string {
  return path.join(resolveGatewayStateDirectory(environment), GATEWAY_STATE_INIT_MARKER_BASENAME);
}

/**
 * "first-run" creates a missing marker once; with a marker present the flag must be gone
 * again, so a flag left in the service environment cannot re-initialize a lost volume later.
 */
function markerMode(environment: NodeJS.ProcessEnv): "required" | "optional" | "first-run" {
  const init = environment[GATEWAY_STATE_INIT_ENV]?.trim() ?? "";
  if (init !== "" && init !== "first-run") {
    throw new GatewayStateInitMarkerError(`${GATEWAY_STATE_INIT_ENV} must be first-run or unset`);
  }
  const value = environment[GATEWAY_STATE_INIT_MARKER_ENV]?.trim() ?? "";
  if (value !== "" && value !== "optional" && value !== "required") {
    throw new GatewayStateInitMarkerError(
      `${GATEWAY_STATE_INIT_MARKER_ENV} must be required or optional`
    );
  }
  if (environment.NODE_ENV === "production" && value === "optional") {
    throw new GatewayStateInitMarkerError(
      `${GATEWAY_STATE_INIT_MARKER_ENV}=optional is not allowed in production; ` +
      `use ${GATEWAY_STATE_INIT_ENV}=first-run for the first installation start`
    );
  }
  if (init === "first-run") return "first-run";
  if (value === "required" || environment.NODE_ENV === "production") return "required";
  return "optional";
}

function validMarker(value: unknown): value is Marker {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const marker = value as Record<string, unknown>;
  const keys = Object.keys(marker).sort();
  const files = marker.required_files;
  return keys.length === 3 &&
    keys[0] === "initialized_at_epoch_ms" &&
    keys[1] === "required_files" &&
    keys[2] === "schema_version" &&
    marker.schema_version === MARKER_SCHEMA &&
    Number.isSafeInteger(marker.initialized_at_epoch_ms) &&
    Number(marker.initialized_at_epoch_ms) > 0 &&
    Array.isArray(files) &&
    files.length <= MAX_REQUIRED_FILES &&
    files.every((file, index) =>
      typeof file === "string" &&
      file.length > 0 &&
      file.length <= 4096 &&
      path.isAbsolute(file) &&
      path.resolve(file) === file &&
      (index === 0 || String(files[index - 1]) < file)
    );
}

function readMarker(target: string): Marker | null {
  let descriptor: number | null = null;
  try {
    descriptor = openSync(target, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor);
    if (
      !metadata.isFile() ||
      metadata.nlink !== 1 ||
      !ownedByCurrentProcess(metadata.uid) ||
      (metadata.mode & 0o777) !== 0o600 ||
      metadata.size > MAX_MARKER_BYTES
    ) {
      throw new GatewayStateInitMarkerError("gateway state init marker file is unsafe");
    }
    let parsed: unknown;
    try {
      parsed = JSON.parse(readFileSync(descriptor, "utf8")) as unknown;
    } catch {
      throw new GatewayStateInitMarkerError("gateway state init marker is damaged");
    }
    if (!validMarker(parsed)) {
      throw new GatewayStateInitMarkerError("gateway state init marker is damaged");
    }
    return parsed;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return null;
    if (error instanceof GatewayStateInitMarkerError) throw error;
    throw new GatewayStateInitMarkerError("gateway state init marker cannot be read");
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

function writeMarker(target: string, marker: Marker): void {
  const directory = path.dirname(target);
  const temporary = path.join(directory, `.${path.basename(target)}.${randomUUID()}.tmp`);
  let descriptor: number | null = null;
  let directoryDescriptor: number | null = null;
  try {
    descriptor = openSync(
      temporary,
      fsConstants.O_WRONLY | fsConstants.O_CREAT | fsConstants.O_EXCL | fsConstants.O_NOFOLLOW,
      0o600
    );
    writeFileSync(descriptor, `${JSON.stringify(marker)}\n`, "utf8");
    fsyncSync(descriptor);
    closeSync(descriptor);
    descriptor = null;
    renameSync(temporary, target);
    directoryDescriptor = openSync(
      directory,
      fsConstants.O_RDONLY | fsConstants.O_DIRECTORY | fsConstants.O_NOFOLLOW
    );
    fsyncSync(directoryDescriptor);
  } finally {
    if (descriptor !== null) closeSync(descriptor);
    if (directoryDescriptor !== null) closeSync(directoryDescriptor);
    rmSync(temporary, { force: true });
  }
}

function fileExists(filePath: string): boolean {
  try {
    lstatSync(filePath);
    return true;
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return false;
    throw error;
  }
}

const registeredInMarker = new Map<string, Set<string>>();

/**
 * Called after a required ledger's durable write. A no-op while the state root has no
 * marker (tools and tests that never ran the service start keep first-run semantics).
 */
export function registerRequiredGatewayStateFile(
  filePath: string,
  environment: NodeJS.ProcessEnv = process.env
): void {
  const resolved = path.resolve(filePath);
  const target = gatewayStateInitMarkerPath(environment);
  if (registeredInMarker.get(target)?.has(resolved)) return;
  if (!fileExists(target)) return;
  withExclusiveFileLock(`${target}.lock`, () => {
    const marker = readMarker(target);
    if (marker === null) return;
    if (!marker.required_files.includes(resolved)) {
      if (marker.required_files.length >= MAX_REQUIRED_FILES) {
        throw new GatewayStateInitMarkerError("gateway state init marker is full");
      }
      writeMarker(target, {
        ...marker,
        required_files: [...marker.required_files, resolved].sort()
      });
    }
    const known = registeredInMarker.get(target) ?? new Set<string>();
    known.add(resolved);
    registeredInMarker.set(target, known);
  });
}

/**
 * For a ledger read that hit ENOENT: true when the file was registered, i.e. it was lost
 * after initialization and must not be recreated as an empty ledger. A damaged marker
 * throws, which also fails the read closed.
 */
export function requiredGatewayStateFileWasLost(
  filePath: string,
  environment: NodeJS.ProcessEnv = process.env
): boolean {
  const marker = readMarker(gatewayStateInitMarkerPath(environment));
  return marker !== null && marker.required_files.includes(path.resolve(filePath));
}

export type GatewayStateInitMarkerResult = {
  created: boolean;
  required_files: string[];
};

/** Service start: create the marker on a first run, otherwise require every listed file. */
export function assertGatewayStateInitMarker(
  candidates: readonly string[],
  environment: NodeJS.ProcessEnv = process.env,
  nowEpochMs: number = Date.now()
): GatewayStateInitMarkerResult {
  const mode = markerMode(environment);
  const target = gatewayStateInitMarkerPath(environment);
  return withExclusiveFileLock(`${target}.lock`, () => {
    const marker = readMarker(target);
    if (marker === null) {
      if (mode === "required") {
        throw new GatewayStateInitMarkerError(
          "gateway state init marker is required but missing; the state volume may be empty or wrong " +
          `(first installation only: start once with ${GATEWAY_STATE_INIT_ENV}=first-run)`
        );
      }
      const existing = [...new Set(candidates.map((file) => path.resolve(file)))]
        .filter(fileExists)
        .sort();
      writeMarker(target, {
        schema_version: MARKER_SCHEMA,
        initialized_at_epoch_ms: nowEpochMs,
        required_files: existing
      });
      registeredInMarker.set(target, new Set(existing));
      return { created: true, required_files: existing };
    }
    if (mode === "first-run") {
      throw new GatewayStateInitMarkerError(
        `gateway state is already initialized; remove ${GATEWAY_STATE_INIT_ENV}=first-run ` +
        "from the service environment"
      );
    }
    for (const file of marker.required_files) {
      if (!fileExists(file)) throw new GatewayStateFileMissingError(file);
    }
    // S19-26-F1: a required ledger that exists but is not listed (a crash between its first
    // durable write and its registration) is registered now, under the marker lock.
    const unlisted = [...new Set(candidates.map((file) => path.resolve(file)))]
      .filter((file) => !marker.required_files.includes(file) && fileExists(file));
    let requiredFiles = marker.required_files;
    if (unlisted.length > 0) {
      requiredFiles = [...marker.required_files, ...unlisted].sort();
      if (requiredFiles.length > MAX_REQUIRED_FILES) {
        throw new GatewayStateInitMarkerError("gateway state init marker is full");
      }
      writeMarker(target, { ...marker, required_files: requiredFiles });
    }
    registeredInMarker.set(target, new Set(requiredFiles));
    return { created: false, required_files: [...requiredFiles] };
  });
}
