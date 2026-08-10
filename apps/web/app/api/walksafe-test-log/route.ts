/**
 * Stores explicitly consented field-test captures behind enable, session,
 * size, path, and retention gates. Production requires a separate field-only
 * opt-in and an authenticated gateway session.
 */
import { createHash } from "node:crypto";
import { chmod, lstat, mkdir, readFile, readdir, rm, stat, statfs, writeFile } from "node:fs/promises";
import path from "node:path";
import { readBoundedMultipartFormData } from "../_backend";
import { gatewaySessionActor, isFieldSessionAuthorized } from "../_gateway-auth";

export const runtime = "nodejs";

const MAX_IMAGE_BYTES = 4 * 1024 * 1024;
const MAX_METADATA_BYTES = 128 * 1024;
const MAX_MULTIPART_BYTES = 5 * 1024 * 1024;
const SAFE_LOG_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const VALID_VERDICTS = new Set(["snapshot", "correct", "wrong"]);
const RATE_WINDOW_MS = 60_000;
type RateState = { startedAt: number; count: number; lastSeenAt: number };
const rateStates = new Map<string, RateState>();
let writeQueue = Promise.resolve();

function boundedInteger(name: string, fallback: number, min: number, max: number): number {
  const parsed = Number(process.env[name] ?? String(fallback));
  return Number.isInteger(parsed) && parsed >= min && parsed <= max ? parsed : fallback;
}

function rateLimitPerMinute(): number {
  return boundedInteger("WALKSAFE_TEST_LOG_RATE_LIMIT_PER_MINUTE", 30, 1, 600);
}

function maxDailyEntries(): number {
  return boundedInteger("WALKSAFE_TEST_LOG_MAX_DAILY_ENTRIES", 1000, 1, 100_000);
}

function maxTotalBytes(): number {
  return boundedInteger("WALKSAFE_TEST_LOG_MAX_TOTAL_BYTES", 2 * 1024 * 1024 * 1024, 1024 * 1024, 1024 * 1024 * 1024 * 1024);
}

function minFreeBytes(): number {
  return boundedInteger("WALKSAFE_TEST_LOG_MIN_FREE_BYTES", 2 * 1024 * 1024 * 1024, 0, 1024 * 1024 * 1024 * 1024);
}

async function withWriteLock<T>(operation: () => Promise<T>): Promise<T> {
  const previous = writeQueue;
  let release: () => void = () => {};
  writeQueue = new Promise<void>((resolve) => {
    release = resolve;
  });
  await previous.catch(() => undefined);
  try {
    return await operation();
  } finally {
    release();
  }
}

function logRoot(): string {
  return process.env.WALKSAFE_TEST_LOG_DIR ?? path.join(/* turbopackIgnore: true */ process.cwd(), "walksafe-test-logs");
}

function datePart(iso: string): string {
  return iso.slice(0, 10);
}

function safeId(): string {
  return `${Date.now()}-${crypto.randomUUID()}`;
}

function hasConsentHeader(request: Request): boolean {
  return request.headers.get("x-test-capture-consent")?.toLowerCase() === "true";
}

function parseAuthToken(request: Request): boolean {
  const authHeader = request.headers.get("authorization");
  return authHeader === `Bearer ${process.env.WALKSAFE_TEST_LOG_AUTH_TOKEN}`;
}

function rateLimitResponse(request: Request, nowMs = Date.now()): Response | null {
  for (const [key, state] of rateStates) {
    if (nowMs - state.lastSeenAt > RATE_WINDOW_MS * 2) rateStates.delete(key);
  }
  const credential = `${request.headers.get("cookie") ?? ""}\n${request.headers.get("authorization") ?? ""}`;
  const key = createHash("sha256").update(credential).digest("hex");
  const previous = rateStates.get(key);
  const state =
    previous && nowMs - previous.startedAt < RATE_WINDOW_MS
      ? previous
      : { startedAt: nowMs, count: 0, lastSeenAt: nowMs };
  state.lastSeenAt = nowMs;
  const limit = rateLimitPerMinute();
  if (state.count >= limit) {
    return Response.json(
      { ok: false, code: "walksafe_test_log_rate_limited", message: "capture rate limit exceeded" },
      {
        status: 429,
        headers: { "cache-control": "no-store", "retry-after": String(Math.max(1, Math.ceil((state.startedAt + RATE_WINDOW_MS - nowMs) / 1000))) }
      }
    );
  }
  state.count += 1;
  rateStates.set(key, state);
  return null;
}

function requestOwner(request: Request): { actorId: string | null; binding: string } | null {
  const actorId = gatewaySessionActor(request, "field");
  if (actorId) {
    return {
      actorId,
      binding: createHash("sha256").update(`walksafe-test-capture-owner-v1\0actor\0${actorId}`).digest("hex")
    };
  }
  if (parseAuthToken(request)) {
    return {
      actorId: null,
      binding: createHash("sha256")
        .update(`walksafe-test-capture-owner-v1\0token\0${process.env.WALKSAFE_TEST_LOG_AUTH_TOKEN ?? ""}`)
        .digest("hex")
    };
  }
  return null;
}

function isEnabled(request: Request, requireConsent = true): Response | null {
  const productionFieldCapture =
    process.env.NODE_ENV === "production" &&
    process.env.WALKSAFE_TEST_LOG_ALLOW_PRODUCTION_FIELD === "true";
  if (process.env.NODE_ENV === "production" && !productionFieldCapture) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_production_blocked",
        message: "walksafe test log endpoint is not available in production",
      },
      { status: 403 }
    );
  }
  const configuredRetentionDays = Number(process.env.WALKSAFE_TEST_LOG_RETENTION_DAYS ?? "0");
  if (
    productionFieldCapture &&
    (!Number.isInteger(configuredRetentionDays) || configuredRetentionDays < 1 || configuredRetentionDays > 30)
  ) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_retention_not_configured",
        message: "production field capture requires a 1..30 day retention policy",
      },
      { status: 503 }
    );
  }
  if (process.env.WALKSAFE_TEST_LOG_ENABLED !== "true") {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_disabled",
        message: "walksafe test log endpoint is disabled",
      },
      { status: 403 }
    );
  }
  const gatewayAuthorized = isFieldSessionAuthorized(request);
  if (productionFieldCapture && !gatewayAuthorized) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_field_session_required",
        message: "authenticated field session required",
      },
      { status: 401 }
    );
  }
  if (!gatewayAuthorized && !process.env.WALKSAFE_TEST_LOG_AUTH_TOKEN) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_auth_token_not_configured",
        message: "auth token is required when walksafe test log endpoint is enabled",
      },
      { status: 403 }
    );
  }
  if (requireConsent && !hasConsentHeader(request)) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_consent_required",
        message: "consent header required",
      },
      { status: 401 }
    );
  }
  if (!gatewayAuthorized && !parseAuthToken(request)) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_auth_required",
        message: "authorization header required",
      },
      { status: 401 }
    );
  }
  return null;
}

function normalizeDateFolder(name: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(name)) return null;
  const startMs = Date.parse(`${name}T00:00:00.000Z`);
  return Number.isFinite(startMs) ? startMs + 24 * 60 * 60 * 1000 : null;
}

function retentionCutoffMs(): number | null {
  const retentionDays = Number(process.env.WALKSAFE_TEST_LOG_RETENTION_DAYS ?? "0");
  if (!Number.isFinite(retentionDays) || retentionDays <= 0) {
    return null;
  }
  return Date.now() - retentionDays * 24 * 60 * 60 * 1000;
}

async function removeExpiredDateDirectories(root: string): Promise<void> {
  const cutoff = retentionCutoffMs();
  if (cutoff === null) {
    return;
  }
  const entries = await readdir(root, { withFileTypes: true });
  await Promise.all(
    entries.map(async (entry) => {
      if (!entry.isDirectory()) {
        return;
      }
      const folderExpiresAt = normalizeDateFolder(entry.name);
      if (folderExpiresAt === null || folderExpiresAt > cutoff) {
        return;
      }
      await rm(path.join(/* turbopackIgnore: true */ root, entry.name), { recursive: true, force: true });
    })
  );
}

async function deleteEntryById(
  root: string,
  id: string,
  ownerBinding: string
): Promise<"deleted" | "not_found" | "forbidden"> {
  const entries = await readdir(root, { withFileTypes: true });
  for (const entry of entries) {
    if (!entry.isDirectory() || normalizeDateFolder(entry.name) === null) {
      continue;
    }
    const directory = path.join(/* turbopackIgnore: true */ root, entry.name);
    const recordPath = path.join(/* turbopackIgnore: true */ directory, `${id}.json`);
    let record: { owner_binding?: unknown };
    try {
      const metadata = await lstat(recordPath);
      if (!metadata.isFile() || metadata.isSymbolicLink()) return "forbidden";
      record = JSON.parse(await readFile(recordPath, "utf8")) as { owner_binding?: unknown };
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code === "ENOENT") continue;
      return "forbidden";
    }
    if (record.owner_binding !== ownerBinding) return "forbidden";
    const candidates = [`${id}.json`, `${id}.jsonl`, `${id}.jpg`];
    for (const candidate of candidates) {
      const filePath = path.join(/* turbopackIgnore: true */ directory, candidate);
      try {
        const info = await lstat(filePath);
        if (info.isFile() && !info.isSymbolicLink()) {
          await rm(filePath, { force: true });
        }
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "ENOENT") return "forbidden";
      }
    }
    return "deleted";
  }
  return "not_found";
}

async function deleteEntriesByOwner(root: string, ownerBinding: string): Promise<number> {
  const entries = await readdir(root, { withFileTypes: true });
  let deletedCount = 0;
  for (const entry of entries) {
    if (!entry.isDirectory() || normalizeDateFolder(entry.name) === null) continue;
    const directory = path.join(/* turbopackIgnore: true */ root, entry.name);
    const files = await readdir(directory, { withFileTypes: true });
    for (const file of files) {
      if (!file.isFile() || file.isSymbolicLink() || !file.name.endsWith(".json")) continue;
      const id = file.name.slice(0, -".json".length);
      if (!SAFE_LOG_ID_RE.test(id)) continue;
      let record: { owner_binding?: unknown };
      try {
        record = JSON.parse(await readFile(path.join(/* turbopackIgnore: true */ directory, file.name), "utf8")) as {
          owner_binding?: unknown;
        };
      } catch {
        continue;
      }
      if (record.owner_binding !== ownerBinding) continue;
      if ((await deleteEntryById(root, id, ownerBinding)) === "deleted") deletedCount += 1;
    }
  }
  return deletedCount;
}

function validateMetadata(metadata: unknown): Response | null {
  if (typeof metadata !== "object" || metadata === null || Array.isArray(metadata)) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_invalid_metadata",
        message: "metadata must be a JSON object",
      },
      { status: 400 }
    );
  }
  const value = metadata as Record<string, unknown>;
  if (value.schema_version !== "walksafe.phone-test.v1") {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_invalid_metadata",
        message: "schema_version must be walksafe.phone-test.v1",
      },
      { status: 400 }
    );
  }
  if (typeof value.captured_at !== "string" || Number.isNaN(Date.parse(value.captured_at))) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_invalid_metadata",
        message: "captured_at must be an ISO datetime string",
      },
      { status: 400 }
    );
  }
  if (typeof value.verdict !== "string" || !VALID_VERDICTS.has(value.verdict)) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_invalid_metadata",
        message: "verdict must be snapshot, correct, or wrong",
      },
      { status: 400 }
    );
  }
  if (typeof value.expected_target !== "string" || value.expected_target.length > 80) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_invalid_metadata",
        message: "expected_target must be a short string",
      },
      { status: 400 }
    );
  }
  if (typeof value.note === "string" && value.note.length > 2000) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_invalid_metadata",
        message: "note is too long",
      },
      { status: 400 }
    );
  }
  if (
    value.expected_distance_m !== null &&
    value.expected_distance_m !== undefined &&
    (typeof value.expected_distance_m !== "number" ||
      !Number.isFinite(value.expected_distance_m) ||
      value.expected_distance_m < 0 ||
      value.expected_distance_m > 50)
  ) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_invalid_metadata",
        message: "expected_distance_m must be null or 0..50",
      },
      { status: 400 }
    );
  }
  return null;
}

function isJpeg(bytes: Buffer): boolean {
  return bytes.length >= 4 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[bytes.length - 2] === 0xff && bytes[bytes.length - 1] === 0xd9;
}

export function stripJpegMetadataSegments(bytes: Buffer): Buffer | null {
  if (!isJpeg(bytes)) return null;
  const parts = [bytes.subarray(0, 2)];
  let offset = 2;
  while (offset < bytes.length - 2) {
    if (bytes[offset] !== 0xff) return null;
    const markerStart = offset;
    while (offset < bytes.length && bytes[offset] === 0xff) offset += 1;
    const marker = bytes[offset];
    offset += 1;
    if (marker === 0xda) {
      parts.push(bytes.subarray(markerStart));
      return Buffer.concat(parts);
    }
    if (marker === 0xd9) {
      parts.push(bytes.subarray(markerStart, offset));
      return Buffer.concat(parts);
    }
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      parts.push(bytes.subarray(markerStart, offset));
      continue;
    }
    if (offset + 2 > bytes.length) return null;
    const segmentLength = bytes.readUInt16BE(offset);
    if (segmentLength < 2 || offset + segmentLength > bytes.length) return null;
    const segmentEnd = offset + segmentLength;
    const isMetadata = (marker >= 0xe0 && marker <= 0xef) || marker === 0xfe;
    if (!isMetadata) parts.push(bytes.subarray(markerStart, segmentEnd));
    offset = segmentEnd;
  }
  parts.push(bytes.subarray(bytes.length - 2));
  return Buffer.concat(parts);
}

async function saveImageIfPresent(formData: FormData, directory: string, id: string): Promise<string | Response | null> {
  const image = formData.get("image");
  if (!(image instanceof File) || image.size <= 0) {
    return null;
  }
  if (image.size > MAX_IMAGE_BYTES) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_image_too_large",
        message: `image size must be <= ${MAX_IMAGE_BYTES} bytes`,
      },
      { status: 413 }
    );
  }
  if (image.type !== "image/jpeg") {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_invalid_image",
        message: "image must be image/jpeg",
      },
      { status: 415 }
    );
  }

  const bytes = Buffer.from(await image.arrayBuffer());
  const sanitizedBytes = stripJpegMetadataSegments(bytes);
  if (!sanitizedBytes) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_invalid_image",
        message: "image must have JPEG SOI/EOI markers",
      },
      { status: 415 }
    );
  }
  const fileName = `${id}.jpg`;
  await writeFile(path.join(/* turbopackIgnore: true */ directory, fileName), sanitizedBytes, { mode: 0o600 });
  return fileName;
}

async function storageUsage(root: string, dateDirectory: string): Promise<{ totalBytes: number; dailyEntries: number }> {
  let rootEntries;
  try {
    rootEntries = await readdir(root, { withFileTypes: true });
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return { totalBytes: 0, dailyEntries: 0 };
    throw error;
  }
  let totalBytes = 0;
  let dailyEntries = 0;
  for (const rootEntry of rootEntries) {
    if (!rootEntry.isDirectory() || !/^\d{4}-\d{2}-\d{2}$/.test(rootEntry.name)) continue;
    const directory = path.join(/* turbopackIgnore: true */ root, rootEntry.name);
    const files = await readdir(directory, { withFileTypes: true });
    for (const file of files) {
      if (!file.isFile()) continue;
      const info = await stat(path.join(/* turbopackIgnore: true */ directory, file.name));
      totalBytes += info.size;
      if (directory === dateDirectory && file.name.endsWith(".json")) dailyEntries += 1;
    }
  }
  return { totalBytes, dailyEntries };
}

async function storageGuardResponse(
  root: string,
  dateDirectory: string,
  estimatedWriteBytes: number
): Promise<Response | null> {
  const fileSystem = await statfs(root);
  const availableBytes = fileSystem.bavail * fileSystem.bsize;
  if (availableBytes - estimatedWriteBytes < minFreeBytes()) {
    return Response.json(
      { ok: false, code: "walksafe_test_log_low_disk", message: "capture storage free-space floor reached" },
      { status: 507, headers: { "cache-control": "no-store" } }
    );
  }
  const usage = await storageUsage(root, dateDirectory);
  if (usage.dailyEntries >= maxDailyEntries()) {
    return Response.json(
      { ok: false, code: "walksafe_test_log_daily_quota", message: "daily capture quota reached" },
      { status: 507, headers: { "cache-control": "no-store" } }
    );
  }
  if (usage.totalBytes + estimatedWriteBytes > maxTotalBytes()) {
    return Response.json(
      { ok: false, code: "walksafe_test_log_total_quota", message: "total capture quota reached" },
      { status: 507, headers: { "cache-control": "no-store" } }
    );
  }
  return null;
}

export async function POST(request: Request): Promise<Response> {
  const enabledResponse = isEnabled(request);
  if (enabledResponse) {
    return enabledResponse;
  }
  const owner = requestOwner(request);
  if (!owner) {
    return Response.json({ ok: false, code: "walksafe_test_log_owner_required" }, { status: 401 });
  }
  const limited = rateLimitResponse(request);
  if (limited) return limited;
  const multipart = await readBoundedMultipartFormData(request, MAX_MULTIPART_BYTES);
  if (multipart.error) return multipart.error;
  const receivedAt = new Date().toISOString();
  const rootDirectory = logRoot();
  await mkdir(rootDirectory, { recursive: true, mode: 0o700 });
  await chmod(rootDirectory, 0o700);
  const formData = multipart.formData;
  const metadataText = formData.get("metadata");
  if (typeof metadataText !== "string" || !metadataText) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_missing_metadata",
        message: "metadata is required",
      },
      { status: 400 }
    );
  }
  if (Buffer.byteLength(metadataText, "utf-8") > MAX_METADATA_BYTES) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_metadata_too_large",
        message: `metadata size must be <= ${MAX_METADATA_BYTES} bytes`,
      },
      { status: 413 }
    );
  }
  let metadata;
  try {
    metadata = JSON.parse(metadataText);
  } catch (error) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_invalid_metadata",
        message: `invalid metadata json: ${error instanceof Error ? error.message : "invalid json"}`,
      },
      { status: 400 }
    );
  }
  const metadataError = validateMetadata(metadata);
  if (metadataError) {
    return metadataError;
  }
  return withWriteLock(async () => {
    const id = safeId();
    const directory = path.join(/* turbopackIgnore: true */ rootDirectory, datePart(receivedAt));
    await mkdir(directory, { recursive: true, mode: 0o700 });
    await chmod(directory, 0o700);
    await removeExpiredDateDirectories(rootDirectory);
    const image = formData.get("image");
    const estimatedWriteBytes =
      Buffer.byteLength(metadataText, "utf8") * 2 +
      (image instanceof File ? image.size : 0) +
      16 * 1024;
    const storageDenied = await storageGuardResponse(rootDirectory, directory, estimatedWriteBytes);
    if (storageDenied) return storageDenied;
    const imageFile = await saveImageIfPresent(formData, directory, id);
    if (imageFile instanceof Response) {
      return imageFile;
    }

    const entry = {
      id,
      actor_id: owner.actorId,
      owner_binding: owner.binding,
      server_received_at: receivedAt,
      user_agent: request.headers.get("user-agent"),
      image_file: imageFile,
      metadata
    };
    await writeFile(path.join(/* turbopackIgnore: true */ directory, `${id}.json`), JSON.stringify(entry, null, 2), {
      encoding: "utf-8",
      mode: 0o600
    });
    await writeFile(path.join(/* turbopackIgnore: true */ directory, `${id}.jsonl`), `${JSON.stringify(entry)}\n`, {
      encoding: "utf-8",
      mode: 0o600
    });

    return Response.json({ ok: true, id, image_file: imageFile, log_date: datePart(receivedAt) });
  });
}

export async function DELETE(request: Request): Promise<Response> {
  const enabledResponse = isEnabled(request, false);
  if (enabledResponse) {
    return enabledResponse;
  }
  const owner = requestOwner(request);
  if (!owner) {
    return Response.json({ ok: false, code: "walksafe_test_log_owner_required" }, { status: 401 });
  }
  const url = new URL(request.url);
  const deleteAll = url.searchParams.get("all") === "true";
  const id = url.searchParams.get("id");
  if (!id && !deleteAll) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_delete_id_required",
        message: "query id is required",
      },
      { status: 400 }
    );
  }
  if (id && (!SAFE_LOG_ID_RE.test(id) || id.includes(".."))) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_invalid_id",
        message: "query id is invalid",
      },
      { status: 400 }
    );
  }
  return withWriteLock(async () => {
    const rootDirectory = logRoot();
    await mkdir(rootDirectory, { recursive: true, mode: 0o700 });
    await chmod(rootDirectory, 0o700);
    if (deleteAll) {
      const deletedCount = await deleteEntriesByOwner(rootDirectory, owner.binding);
      return Response.json({ ok: true, deleted_count: deletedCount });
    }
    if (!id) throw new Error("validated capture id is missing");
    const result = await deleteEntryById(rootDirectory, id, owner.binding);
    if (result === "forbidden") {
      return Response.json(
        { ok: false, code: "walksafe_test_log_owner_mismatch", message: "capture belongs to another actor" },
        { status: 403 }
      );
    }
    if (result === "not_found") {
      return Response.json(
        {
          ok: false,
          code: "walksafe_test_log_not_found",
          message: "requested id not found",
        },
        { status: 404 }
      );
    }
    return Response.json({ ok: true, id });
  });
}
