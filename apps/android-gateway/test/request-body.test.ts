import assert from "node:assert/strict";
import { test } from "node:test";

import { readBoundedJsonBody } from "../src/request-body.js";

function jsonRequest(text: string): Request {
  const bytes = new TextEncoder().encode(text);
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(bytes);
      controller.close();
    }
  });
  return new Request("http://127.0.0.1/request-body", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body,
    duplex: "half"
  } as RequestInit & { duplex: "half" });
}

async function assertInvalidJson(text: string): Promise<void> {
  const result = await readBoundedJsonBody(jsonRequest(text), 4096);
  assert.ok(result.error);
  assert.equal(result.error.status, 400);
  assert.equal(result.error.headers.get("cache-control"), "no-store");
  assert.deepEqual(await result.error.json(), { code: "invalid_json_body" });
}

for (const [name, text] of [
  ["top-level duplicate", '{"action":"grant","action":"deny"}'],
  ["nested duplicate", '{"outer":{"id":1,"id":2}}'],
  ["array object duplicate", '{"items":[{"id":1,"id":2}]}'],
  ["escaped-equivalent duplicate", String.raw`{"mode":1,"m\u006fde":2}`],
  ["escaped quote duplicate", String.raw`{"a\"b":1,"a\u0022b":2}`]
] as const) {
  test(`rejects ${name}`, async () => {
    await assertInvalidJson(text);
  });
}

test("allows the same key in distinct objects and preserves parsed values", async () => {
  const result = await readBoundedJsonBody(
    jsonRequest('{"left":{"id":1},"right":{"id":2},"items":[{"id":3},{"id":4}]}'),
    4096
  );
  assert.equal(result.error, undefined);
  assert.deepEqual(result.value, {
    left: { id: 1 }, right: { id: 2 }, items: [{ id: 3 }, { id: 4 }]
  });
});

test("preserves escaped keys and scalar JSON values", async () => {
  const escaped = await readBoundedJsonBody(
    jsonRequest(String.raw`{"m\u006fde":"{not an object}","other":true}`),
    4096
  );
  assert.equal(escaped.error, undefined);
  assert.deepEqual(escaped.value, { mode: "{not an object}", other: true });

  const scalar = await readBoundedJsonBody(jsonRequest("false"), 4096);
  assert.equal(scalar.error, undefined);
  assert.equal(scalar.value, false);
});

test("malformed JSON stays a typed 400 without reflecting input", async () => {
  await assertInvalidJson('{"secret":"sensitive-value",');
});

test("retains the bounded byte rejection", async () => {
  const result = await readBoundedJsonBody(jsonRequest('{"id":1}'), 4);
  assert.ok(result.error);
  assert.equal(result.error.status, 413);
  assert.deepEqual(await result.error.json(), { code: "request_body_too_large" });
});
