import assert from "node:assert/strict";
import { test } from "node:test";
import { toBackendResponse } from "../src/backend.js";

const STALE = "account_authentication_stale";
const PRIVATE = "private-upstream-message-and-credential";

test("only a confirmed stale account response exposes a fixed no-store 401", async () => {
  const upstream = Response.json({ detail: { code: STALE, message: PRIVATE }, secret: PRIVATE }, {
    status: 401, headers: {
      "set-cookie": PRIVATE, "www-authenticate": PRIVATE,
      "x-walksafe-actor-id": PRIVATE, "x-request-id": PRIVATE
    }
  });
  const response = await toBackendResponse(upstream);
  assert.equal(response.status, 401);
  assert.equal(response.headers.get("cache-control"), "no-store");
  for (const name of ["set-cookie", "www-authenticate", "x-walksafe-actor-id", "x-request-id"]) {
    assert.equal(response.headers.get(name), null);
  }
  assert.deepEqual(await response.json(), { detail: {
    code: STALE, message: "The account session is no longer current."
  } });
});

test("other 401 and all 403 responses remain concealed upstream failures", async () => {
  for (const [status, body] of [
    [401, { detail: { code: "service_token_invalid", message: PRIVATE } }],
    [401, { detail: PRIVATE }], [401, { detail: [{ code: STALE }] }],
    [403, { detail: { code: STALE, message: PRIVATE } }],
    [403, { detail: { code: "service_token_invalid", message: PRIVATE } }]
  ] as const) {
    const response = await toBackendResponse(Response.json(body, { status }));
    assert.equal(response.status, 502);
    const text = await response.text();
    assert.match(text, /gateway_upstream_auth_failed/);
    assert.ok(!text.includes(PRIVATE));
  }
});

test("malformed, non-JSON and oversized auth bodies never expose upstream data", async () => {
  for (const [body, headers] of [
    ["not-json", { "content-type": "application/json" }],
    [JSON.stringify({ detail: { code: STALE } }), { "content-type": "text/html" }],
    ["{}", { "content-type": "application/json", "content-length": "99999999" }],
    [JSON.stringify({ detail: { code: STALE }, secret: PRIVATE.repeat(200) }),
      { "content-type": "application/json" }]
  ] as const) {
    const response = await toBackendResponse(new Response(body, { status: 401, headers }));
    assert.equal(response.status, 502);
    assert.ok(!(await response.text()).includes(PRIVATE));
  }
});

test("invalid UTF-8 and errored auth streams fail closed", async () => {
  const responses = [
    new Response(new Uint8Array([0xff]), {
      status: 401, headers: { "content-type": "application/json" }
    }),
    new Response(new ReadableStream<Uint8Array>({
      start(controller) { controller.error(new Error(PRIVATE)); }
    }), { status: 401, headers: { "content-type": "application/json" } })
  ];
  for (const upstream of responses) {
    const response = await toBackendResponse(upstream);
    assert.equal(response.status, 502);
    assert.ok(!(await response.text()).includes(PRIVATE));
  }
});

test("stalled auth body has a bounded deadline even when cancellation never settles", async () => {
  let cancelled = false;
  let deadline: ReturnType<typeof setTimeout> | undefined;
  const upstream = new Response(new ReadableStream<Uint8Array>({
    pull() { return new Promise<void>(() => undefined); },
    cancel() { cancelled = true; return new Promise<void>(() => undefined); }
  }), { status: 401, headers: { "content-type": "application/json" } });
  try {
    const response = await Promise.race([
      toBackendResponse(upstream),
      new Promise<never>((_resolve, reject) => {
        deadline = setTimeout(() => reject(new Error("auth response deadline was not enforced")), 12_000);
      })
    ]);
    assert.equal(response.status, 502);
    assert.equal(cancelled, true);
  } finally {
    if (deadline !== undefined) clearTimeout(deadline);
  }
});

test("non-auth responses retain their existing status and response contract", async () => {
  for (const status of [200, 409, 429, 503]) {
    const response = await toBackendResponse(Response.json({ detail: { code: STALE } }, {
      status, headers: { "retry-after": "600" }
    }));
    assert.equal(response.status, status);
    assert.equal(response.headers.get("retry-after"), "600");
    assert.deepEqual(await response.json(), { detail: { code: STALE } });
  }
});
