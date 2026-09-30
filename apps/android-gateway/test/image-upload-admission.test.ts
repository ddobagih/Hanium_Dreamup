// S19-49: per-actor upload admission and the copy-reduced multipart read.
import assert from "node:assert/strict";
import test, { afterEach } from "node:test";

import * as backend from "../src/backend.js";

const hooks = backend as unknown as {
  setImageUploadMaxConcurrencyForTests?: (value: number | null) => void;
};

afterEach(() => {
  hooks.setImageUploadMaxConcurrencyForTests?.(null);
  backend.resetImageUploadAdmissionForTests();
});

function admit(actorId: string, nowMs = 1_000_000) {
  return backend.acquireImageUploadAdmission(
    new Request("https://gateway.invalid/api/reports/v2", { method: "POST" }),
    new Headers({ [backend.ACTOR_ID_HEADER]: actorId }),
    nowMs
  );
}

test("S19-49 the conservative global default still admits one upload process-wide", async () => {
  const first = admit("upload-actor-a");
  assert.ok(first.release);
  const other = admit("upload-actor-b");
  assert.equal(other.error?.status, 503);
  assert.equal(
    (await other.error!.json() as { detail: { code: string } }).detail.code,
    "image_upload_busy"
  );
  first.release!();
  const next = admit("upload-actor-b");
  assert.ok(next.release);
  next.release!();
});

test("S19-49 one account cannot hold a second slot while other accounts are admitted", async () => {
  hooks.setImageUploadMaxConcurrencyForTests?.(3);
  const a1 = admit("upload-actor-a");
  assert.ok(a1.release);
  const a2 = admit("upload-actor-a");
  assert.equal(a2.error?.status, 503, "the same actor must not take another slot");
  assert.equal(a2.error?.headers.get("retry-after"), "1");
  const b1 = admit("upload-actor-b");
  assert.ok(b1.release, "another actor is admitted while slots remain");
  const c1 = admit("upload-actor-c");
  assert.ok(c1.release);
  const d1 = admit("upload-actor-d");
  assert.equal(d1.error?.status, 503, "the global limit still applies");
  a1.release!();
  a1.release!(); // idempotent
  const a3 = admit("upload-actor-a");
  assert.ok(a3.release, "the actor's slot is reusable after release");
  for (const lease of [a3, b1, c1]) lease.release!();
});

function multipartRequest(imageBytes: number): Request {
  const boundary = "walksafeadmissionboundary";
  const metadata = JSON.stringify({ auto_reported: false });
  const body = Buffer.concat([
    Buffer.from(
      `--${boundary}\r\nContent-Disposition: form-data; name="metadata"\r\n\r\n${metadata}\r\n` +
      `--${boundary}\r\nContent-Disposition: form-data; name="image"; filename="r.jpg"\r\n` +
      "Content-Type: image/jpeg\r\n\r\n"
    ),
    Buffer.alloc(imageBytes, 0x5a),
    Buffer.from(`\r\n--${boundary}--\r\n`)
  ]);
  let offset = 0;
  return new Request("https://gateway.invalid/api/reports/v2", {
    method: "POST",
    headers: {
      "content-type": `multipart/form-data; boundary=${boundary}`,
      "content-length": String(body.length)
    },
    body: new ReadableStream<Uint8Array>({
      pull(controller) {
        if (offset >= body.length) {
          controller.close();
          return;
        }
        const end = Math.min(body.length, offset + 64 * 1024);
        controller.enqueue(new Uint8Array(body.subarray(offset, end)));
        offset = end;
      }
    }),
    duplex: "half"
  } as RequestInit);
}

test("S19-49 multipart parsing is unchanged for small and maximum-size images", async () => {
  for (const size of [1, 256 * 1024, backend.REPORT_IMAGE_LIMIT_BYTES]) {
    const result = await backend.readBoundedMultipartFormData(
      multipartRequest(size),
      backend.IMAGE_MULTIPART_LIMIT_BYTES
    );
    assert.ok(result.formData, `size ${size}`);
    const image = result.formData.get("image");
    assert.ok(image instanceof Blob);
    assert.equal(image.size, size);
    const bytes = new Uint8Array(await image.arrayBuffer());
    assert.equal(bytes[0], 0x5a);
    assert.equal(bytes[bytes.length - 1], 0x5a);
    assert.equal(result.formData.get("metadata"), JSON.stringify({ auto_reported: false }));
  }
  const oversize = await backend.readBoundedMultipartFormData(
    multipartRequest(backend.REPORT_IMAGE_LIMIT_BYTES + 1),
    backend.IMAGE_MULTIPART_LIMIT_BYTES
  );
  assert.equal(oversize.error?.status, 413);
});
