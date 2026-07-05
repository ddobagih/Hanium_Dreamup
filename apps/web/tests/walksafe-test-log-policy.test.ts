import { mkdir, mkdtemp, stat, writeFile } from "node:fs/promises";
import os from "node:os";
import path from "node:path";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function metadata(overrides: Record<string, unknown> = {}) {
  return {
    schema_version: "walksafe.phone-test.v1",
    captured_at: "2026-07-01T12:00:00.000Z",
    verdict: "snapshot",
    expected_target: "damaged_tactile_block",
    expected_distance_m: null,
    note: "",
    ...overrides
  };
}

function formDataWith(metadataValue: Record<string, unknown>, image?: File): FormData {
  const body = new FormData();
  body.append("metadata", JSON.stringify(metadataValue));
  if (image) {
    body.append("image", image, "frame.jpg");
  }
  return body;
}

function jpegFile(): File {
  return new File([new Uint8Array([0xff, 0xd8, 0xff, 0xd9])], "frame.jpg", { type: "image/jpeg" });
}

async function exists(filePath: string): Promise<boolean> {
  try {
    await stat(filePath);
    return true;
  } catch {
    return false;
  }
}

async function main() {
  const root = await mkdtemp(path.join(os.tmpdir(), "walksafe-test-log-"));
  process.env.WALKSAFE_TEST_LOG_DIR = root;

  const route = await import("../app/api/walksafe-test-log/route");

  const originalNodeEnv = process.env.NODE_ENV;
  Object.assign(process.env, { NODE_ENV: "production" });
  const productionBlocked = await route.POST(
    new Request("http://localhost/api/walksafe-test-log", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "x-test-capture-consent": "true" },
      body: formDataWith(metadata())
    })
  );
  assert(productionBlocked.status === 403, "endpoint should be hard-blocked in production");
  const productionBlockedBody = (await productionBlocked.json()) as { code: string };
  assert(
    productionBlockedBody.code === "walksafe_test_log_production_blocked",
    "production block should return a distinct code"
  );
  if (originalNodeEnv === undefined) {
    Reflect.deleteProperty(process.env, "NODE_ENV");
  } else {
    Object.assign(process.env, { NODE_ENV: originalNodeEnv });
  }

  delete process.env.WALKSAFE_TEST_LOG_ENABLED;
  delete process.env.WALKSAFE_TEST_LOG_AUTH_TOKEN;
  process.env.WALKSAFE_TEST_LOG_REQUIRE_CONSENT = "true";
  const disabled = await route.POST(
    new Request("http://localhost/api/walksafe-test-log", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "x-test-capture-consent": "true" },
      body: formDataWith(metadata())
    })
  );
  assert(disabled.status === 403, "endpoint should be disabled by default");

  process.env.WALKSAFE_TEST_LOG_ENABLED = "true";
  const missingToken = await route.POST(
    new Request("http://localhost/api/walksafe-test-log", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "x-test-capture-consent": "true" },
      body: formDataWith(metadata())
    })
  );
  assert(missingToken.status === 403, "enabled endpoint should fail closed when auth token is missing");

  process.env.WALKSAFE_TEST_LOG_AUTH_TOKEN = "test-token";

  const noAuth = await route.POST(
    new Request("http://localhost/api/walksafe-test-log", {
      method: "POST",
      headers: { "x-test-capture-consent": "true" },
      body: formDataWith(metadata())
    })
  );
  assert(noAuth.status === 401, "enabled endpoint should require authorization");

  const noConsent = await route.POST(
    new Request("http://localhost/api/walksafe-test-log", {
      method: "POST",
      headers: { authorization: "Bearer test-token" },
      body: formDataWith(metadata())
    })
  );
  assert(noConsent.status === 401, "enabled endpoint should require consent");

  const invalidMetadata = await route.POST(
    new Request("http://localhost/api/walksafe-test-log", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "x-test-capture-consent": "true" },
      body: formDataWith(metadata({ verdict: "maybe" }))
    })
  );
  assert(invalidMetadata.status === 400, "invalid metadata should be rejected");

  const oversizedMetadata = await route.POST(
    new Request("http://localhost/api/walksafe-test-log", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "x-test-capture-consent": "true" },
      body: formDataWith(metadata({ padding: "x".repeat(129 * 1024) }))
    })
  );
  assert(oversizedMetadata.status === 413, "oversized metadata should be rejected");

  const invalidImage = await route.POST(
    new Request("http://localhost/api/walksafe-test-log", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "x-test-capture-consent": "true" },
      body: formDataWith(metadata(), new File([new Uint8Array([1, 2, 3])], "frame.png", { type: "image/png" }))
    })
  );
  assert(invalidImage.status === 415, "non-jpeg image should be rejected");

  const invalidJpegMarkers = await route.POST(
    new Request("http://localhost/api/walksafe-test-log", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "x-test-capture-consent": "true" },
      body: formDataWith(metadata(), new File([new Uint8Array([1, 2, 3, 4])], "frame.jpg", { type: "image/jpeg" }))
    })
  );
  assert(invalidJpegMarkers.status === 415, "jpeg content should require SOI/EOI markers");

  const oversizedImage = await route.POST(
    new Request("http://localhost/api/walksafe-test-log", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "x-test-capture-consent": "true" },
      body: formDataWith(
        metadata(),
        new File([new Uint8Array(4 * 1024 * 1024 + 1)], "frame.jpg", { type: "image/jpeg" })
      )
    })
  );
  assert(oversizedImage.status === 413, "oversized image should be rejected before storing");

  const oldDir = path.join(root, "2020-01-01");
  await mkdir(oldDir, { recursive: true });
  await writeFile(path.join(oldDir, "old.json"), "{}", "utf-8");
  process.env.WALKSAFE_TEST_LOG_RETENTION_DAYS = "1";

  const created = await route.POST(
    new Request("http://localhost/api/walksafe-test-log", {
      method: "POST",
      headers: { authorization: "Bearer test-token", "x-test-capture-consent": "true" },
      body: formDataWith(metadata(), jpegFile())
    })
  );
  assert(created.status === 200, "valid test log should be stored");
  const createdBody = (await created.json()) as { id: string; image_file: string; log_date: string };
  assert(createdBody.image_file.endsWith(".jpg"), "stored image file should be jpg");
  assert(/^\d{4}-\d{2}-\d{2}$/.test(createdBody.log_date), "stored log date should be returned");

  const dateDir = path.join(root, createdBody.log_date);
  const jsonPath = path.join(dateDir, `${createdBody.id}.json`);
  const jsonlPath = path.join(dateDir, `${createdBody.id}.jsonl`);
  const jpgPath = path.join(dateDir, `${createdBody.id}.jpg`);
  assert(await exists(jsonPath), "json log should exist");
  assert(await exists(jsonlPath), "jsonl log should exist");
  assert(await exists(jpgPath), "jpg log should exist");
  assert(!(await exists(oldDir)), "retention should remove expired date directories on write");

  const invalidDelete = await route.DELETE(
    new Request("http://localhost/api/walksafe-test-log?id=../bad", {
      method: "DELETE",
      headers: { authorization: "Bearer test-token", "x-test-capture-consent": "true" }
    })
  );
  assert(invalidDelete.status === 400, "delete id should reject traversal");

  const deleted = await route.DELETE(
    new Request(`http://localhost/api/walksafe-test-log?id=${createdBody.id}`, {
      method: "DELETE",
      headers: { authorization: "Bearer test-token", "x-test-capture-consent": "true" }
    })
  );
  assert(deleted.status === 200, "delete should succeed for stored id");
  assert(!(await exists(jsonPath)), "delete should remove json");
  assert(!(await exists(jsonlPath)), "delete should remove jsonl");
  assert(!(await exists(jpgPath)), "delete should remove jpg");

  console.log("walksafe test log policy checks passed");
}

main().catch((error) => {
  console.error(error);
  process.exitCode = 1;
});
