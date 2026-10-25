import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { afterEach, beforeEach, test } from "node:test";
import {
  ACTOR_ASSERTION_HEADER,
  ACTOR_ID_HEADER,
  ACCOUNT_GENERATION_HEADER,
  FIELD_TEST_TOKEN_HEADER,
  checkBackendAccountSessionCurrent,
  type GatewayFetch
} from "../src/backend.js";
import { assertBackendActorAssertionV4 } from "./actor-assertion-v4-fixture.js";

const SECRET = "session-current-secret-123456789012345678901234";
const TOKEN = "session-current-service-token-123456789012345";
const BINDING = { actorId: "stored-actor", accountGeneration: 7, authEpoch: 4 };

let priorSecret: string | undefined;
let priorToken: string | undefined;

beforeEach(() => {
  priorSecret = process.env.WALKSAFE_GATEWAY_SESSION_SECRET;
  priorToken = process.env.WALKSAFE_FIELD_TEST_TOKEN;
  process.env.WALKSAFE_GATEWAY_SESSION_SECRET = SECRET;
  process.env.WALKSAFE_FIELD_TEST_TOKEN = TOKEN;
});

afterEach(() => {
  if (priorSecret === undefined) delete process.env.WALKSAFE_GATEWAY_SESSION_SECRET;
  else process.env.WALKSAFE_GATEWAY_SESSION_SECRET = priorSecret;
  if (priorToken === undefined) delete process.env.WALKSAFE_FIELD_TEST_TOKEN;
  else process.env.WALKSAFE_FIELD_TEST_TOKEN = priorToken;
});

test("204 means current and the assertion comes only from the stored binding", async () => {
  let calls = 0;
  const transport: GatewayFetch = async (input, init) => {
    calls += 1;
    assert.equal(input, "http://127.0.0.1:8000/accounts/session-current");
    assert.equal(init?.method, "POST");
    assert.equal(init?.redirect, "error");
    assert.equal(init?.body, undefined);
    assert.ok(init?.signal);
    const headers = new Headers(init?.headers);
    assert.deepEqual([...headers.keys()].sort(), [
      ACCOUNT_GENERATION_HEADER,
      ACTOR_ASSERTION_HEADER,
      ACTOR_ID_HEADER,
      FIELD_TEST_TOKEN_HEADER
    ].sort());
    assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), TOKEN);
    assert.equal(headers.get(ACTOR_ID_HEADER), BINDING.actorId);
    assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "7");
    // S19-52: v4 bound to POST /accounts/session-current with an empty body.
    const verified = assertBackendActorAssertionV4(input, init, {
      ...BINDING, secret: SECRET, nowSeconds: Math.floor(Date.now() / 1000)
    });
    assert.ok(Math.abs(verified.issuedAt - Math.floor(Date.now() / 1000)) <= 1);
    assert.equal(verified.target, "/accounts/session-current");
    assert.equal(verified.bodySha256, createHash("sha256").update("").digest("hex"));
    return new Response(null, { status: 204 });
  };
  assert.equal(await checkBackendAccountSessionCurrent(BINDING, transport), "current");
  assert.equal(calls, 1);
});

test("401 is denied, while all other Backend responses are unavailable", async () => {
  for (const [status, expected] of [
    [401, "denied"],
    [503, "unavailable"],
    [200, "unavailable"]
  ] as const) {
    const transport: GatewayFetch = async () => new Response(null, { status });
    assert.equal(await checkBackendAccountSessionCurrent(BINDING, transport), expected);
  }
});

test("network failure and timeout are unavailable", async () => {
  const networkFailure: GatewayFetch = async () => {
    throw new Error("network unavailable");
  };
  assert.equal(await checkBackendAccountSessionCurrent(BINDING, networkFailure), "unavailable");

  let sawAbort = false;
  const timeout: GatewayFetch = async (_input, init) => new Promise((_resolve, reject) => {
    assert.ok(init?.signal);
    init.signal.addEventListener("abort", () => {
      sawAbort = true;
      reject(init.signal?.reason);
    }, { once: true });
  });
  let watchdogTimer: ReturnType<typeof setTimeout> | undefined;
  try {
    const result = await Promise.race([
      checkBackendAccountSessionCurrent(BINDING, timeout, 1),
      new Promise<never>((_resolve, reject) => {
        watchdogTimer = setTimeout(() => reject(new Error("timed out waiting for transport abort")), 5_000);
      })
    ]);
    assert.equal(sawAbort, true);
    assert.equal(result, "unavailable");
  } finally {
    if (watchdogTimer !== undefined) clearTimeout(watchdogTimer);
  }
});

test("missing service authority or invalid epoch cannot make a Backend call", async () => {
  let calls = 0;
  const transport: GatewayFetch = async () => {
    calls += 1;
    return new Response(null, { status: 204 });
  };
  assert.equal(await checkBackendAccountSessionCurrent({ ...BINDING, authEpoch: 0 }, transport), "unavailable");
  delete process.env.WALKSAFE_GATEWAY_SESSION_SECRET;
  assert.equal(await checkBackendAccountSessionCurrent(BINDING, transport), "unavailable");
  assert.equal(calls, 0);
});

test("an already cancelled currentness check never starts transport", async () => {
  const controller = new AbortController();
  controller.abort(new Error("client disconnected"));
  let calls = 0;
  const transport: GatewayFetch = async () => {
    calls += 1;
    return new Response(null, { status: 204 });
  };
  assert.equal(await checkBackendAccountSessionCurrent(BINDING, transport, 1_000, controller.signal),
    "unavailable");
  assert.equal(calls, 0);
});

test("client cancellation aborts the in-flight currentness transport with its reason", async () => {
  const controller = new AbortController();
  const reason = new Error("client disconnected");
  let observedReason: unknown;
  const transport: GatewayFetch = async (_input, init) => new Promise((_resolve, reject) => {
    assert.ok(init?.signal);
    init.signal.addEventListener("abort", () => {
      observedReason = init.signal?.reason;
      reject(observedReason);
    }, { once: true });
    controller.abort(reason);
  });
  assert.equal(await checkBackendAccountSessionCurrent(BINDING, transport, 1_000, controller.signal),
    "unavailable");
  assert.equal(observedReason, reason);
});

test("late current and denied responses cannot override client cancellation", async () => {
  for (const status of [204, 401]) {
    const controller = new AbortController();
    const transport: GatewayFetch = async () => {
      controller.abort();
      return new Response(null, { status });
    };
    assert.equal(await checkBackendAccountSessionCurrent(BINDING, transport, 1_000, controller.signal),
      "unavailable");
  }
});

test("the currentness deadline remains active when a live request signal is supplied", async () => {
  const controller = new AbortController();
  let observedReason: unknown;
  const transport: GatewayFetch = async (_input, init) => new Promise((_resolve, reject) => {
    assert.ok(init?.signal);
    init.signal.addEventListener("abort", () => {
      observedReason = init.signal?.reason;
      reject(observedReason);
    }, { once: true });
  });
  const watchdog = setTimeout(() => controller.abort(new Error("deadline did not fire")), 1_000);
  try {
    assert.equal(await checkBackendAccountSessionCurrent(BINDING, transport, 1, controller.signal),
      "unavailable");
    assert.equal(controller.signal.aborted, false);
    assert.ok(observedReason instanceof DOMException);
    assert.equal(observedReason.name, "TimeoutError");
  } finally {
    clearTimeout(watchdog);
  }
});

test("every nonempty currentness response is cancelled without reading its body", async () => {
  for (const [status, expected] of [[401, "denied"], [503, "unavailable"], [200, "unavailable"]] as const) {
    let cancellations = 0;
    let reads = 0;
    const transport: GatewayFetch = async () => new Response(new ReadableStream({
      pull() { reads += 1; },
      cancel() { cancellations += 1; }
    }, { highWaterMark: 0 }), { status });
    assert.equal(await checkBackendAccountSessionCurrent(BINDING, transport), expected);
    assert.equal(cancellations, 1, `status ${status}`);
    assert.equal(reads, 0, `status ${status}`);
  }
});

test("a response arriving after client cancellation still releases its unread body", async () => {
  const controller = new AbortController();
  let cancellations = 0;
  const transport: GatewayFetch = async () => {
    controller.abort();
    return new Response(new ReadableStream({
      cancel() { cancellations += 1; }
    }, { highWaterMark: 0 }), { status: 401 });
  };
  assert.equal(await checkBackendAccountSessionCurrent(BINDING, transport, 1_000, controller.signal),
    "unavailable");
  assert.equal(cancellations, 1);
});

test("a rejected or stalled body cancellation does not replace the currentness decision", async () => {
  for (const stalled of [false, true]) {
    let cancellations = 0;
    const transport: GatewayFetch = async () => new Response(new ReadableStream({
      cancel() {
        cancellations += 1;
        return stalled ? new Promise<void>(() => {}) : Promise.reject(new Error("cleanup failed"));
      }
    }, { highWaterMark: 0 }), { status: 401 });
    assert.equal(await checkBackendAccountSessionCurrent(BINDING, transport), "denied");
    assert.equal(cancellations, 1);
  }
});
