import { mkdir, readdir, rm, stat, writeFile } from "node:fs/promises";
import path from "node:path";

export const runtime = "nodejs";

const MAX_IMAGE_BYTES = 4 * 1024 * 1024;
const MAX_METADATA_BYTES = 128 * 1024;
const SAFE_LOG_ID_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const VALID_VERDICTS = new Set(["snapshot", "correct", "wrong"]);

function logRoot(): string {
  return process.env.WALKSAFE_TEST_LOG_DIR ?? path.join(/*turbopackIgnore: true*/ process.cwd(), "walksafe-test-logs");
}

function datePart(iso: string): string {
  return iso.slice(0, 10);
}

function safeId(): string {
  return `${Date.now()}-${crypto.randomUUID()}`;
}

function hasConsentHeader(request: Request): boolean {
  if (process.env.WALKSAFE_TEST_LOG_REQUIRE_CONSENT === "false") {
    return true;
  }
  return request.headers.get("x-test-capture-consent")?.toLowerCase() === "true";
}

function parseAuthToken(request: Request): boolean {
  const authHeader = request.headers.get("authorization");
  return authHeader === `Bearer ${process.env.WALKSAFE_TEST_LOG_AUTH_TOKEN}`;
}

function isEnabled(request: Request): Response | null {
  if (process.env.NODE_ENV === "production") {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_production_blocked",
        message: "walksafe test log endpoint is not available in production",
      },
      { status: 403 }
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
  if (!process.env.WALKSAFE_TEST_LOG_AUTH_TOKEN) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_auth_token_not_configured",
        message: "auth token is required when walksafe test log endpoint is enabled",
      },
      { status: 403 }
    );
  }
  if (!hasConsentHeader(request)) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_consent_required",
        message: "consent header required",
      },
      { status: 401 }
    );
  }
  if (!parseAuthToken(request)) {
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
  const date = new Date(name);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  return date.getTime();
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
      const folderTime = normalizeDateFolder(entry.name);
      if (folderTime === null || folderTime > cutoff) {
        return;
      }
      await rm(path.join(root, entry.name), { recursive: true, force: true });
    })
  );
}

async function deleteEntryById(root: string, id: string): Promise<boolean> {
  const entries = await readdir(root, { withFileTypes: true });
  let deleted = false;
  for (const entry of entries) {
    if (!entry.isDirectory()) {
      continue;
    }
    const directory = path.join(root, entry.name);
    const candidates = [`${id}.json`, `${id}.jsonl`, `${id}.jpg`];
    for (const candidate of candidates) {
      const filePath = path.join(directory, candidate);
      try {
        const info = await stat(filePath);
        if (info.isFile()) {
          await rm(filePath, { force: true });
          deleted = true;
        }
      } catch {
        continue;
      }
    }
    if (deleted) {
      return true;
    }
  }
  return false;
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
  if (!isJpeg(bytes)) {
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
  await writeFile(path.join(directory, fileName), bytes);
  return fileName;
}

export async function POST(request: Request): Promise<Response> {
  const enabledResponse = isEnabled(request);
  if (enabledResponse) {
    return enabledResponse;
  }
  const receivedAt = new Date().toISOString();
  const rootDirectory = logRoot();
  await mkdir(rootDirectory, { recursive: true });
  const formData = await request.formData();
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
  const id = safeId();
  const directory = path.join(rootDirectory, datePart(receivedAt));
  await mkdir(directory, { recursive: true });
  await removeExpiredDateDirectories(rootDirectory);
  const imageFile = await saveImageIfPresent(formData, directory, id);
  if (imageFile instanceof Response) {
    return imageFile;
  }

  const entry = {
    id,
    server_received_at: receivedAt,
    user_agent: request.headers.get("user-agent"),
    image_file: imageFile,
    metadata
  };
  await writeFile(path.join(directory, `${id}.json`), JSON.stringify(entry, null, 2), "utf-8");
  await writeFile(path.join(directory, `${id}.jsonl`), `${JSON.stringify(entry)}\n`, "utf-8");

  return Response.json({ ok: true, id, image_file: imageFile, log_date: datePart(receivedAt) });
}

export async function DELETE(request: Request): Promise<Response> {
  const enabledResponse = isEnabled(request);
  if (enabledResponse) {
    return enabledResponse;
  }
  const url = new URL(request.url);
  const id = url.searchParams.get("id");
  if (!id) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_delete_id_required",
        message: "query id is required",
      },
      { status: 400 }
    );
  }
  if (!SAFE_LOG_ID_RE.test(id) || id.includes("..")) {
    return Response.json(
      {
        ok: false,
        code: "walksafe_test_log_invalid_id",
        message: "query id is invalid",
      },
      { status: 400 }
    );
  }
  const rootDirectory = logRoot();
  await mkdir(rootDirectory, { recursive: true });
  const deleted = await deleteEntryById(rootDirectory, id);
  if (!deleted) {
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
}
