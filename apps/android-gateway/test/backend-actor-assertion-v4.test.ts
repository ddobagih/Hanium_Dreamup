/** S19-52: every general Backend actor assertion is bound to its exact request (v4). */
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, readdir, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, beforeEach, test } from "node:test";
import { establishBackendGatewaySession } from "../src/auth.js";
import {
  ACCOUNT_GENERATION_HEADER, ACTOR_ASSERTION_HEADER, ACTOR_ID_HEADER,
  PENDING_BACKEND_ACTOR_ASSERTION, RAW_REQUEST_PROOF_HEADER,
  backendActorAssertionKeyId, backendServiceRequestHeaders,
  checkBackendAccountSessionCurrent, createBackendActorAssertionV4,
  createRawCollectionRequestProof, deletionBackendHeaders, fetchBackend,
  proxyRequestHeaders, rawCollectionBackendHeaders, type GatewayFetch
} from "../src/backend.js";
import {
  assertBackendActorAssertionV4, upstreamFormData
} from "./actor-assertion-v4-fixture.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const SECRET = "actor-assertion-v4-contract-secret-0123456789abcdef";
const TOKEN = "v4-internal-token-1234567890123456789012";
const ACTOR = "v4-gateway-actor";
// Shared with backend/tests/test_actor_assertion_v4.py.
const GOLDEN_TOKEN =
  "v4.2000000000.2000000030.4.5cJ6Zd4ib6A6VfBn.AAECAwQFBgcICQoLDA0ODw." +
  "JeJYNRh50Ir9V5hTp7lD1DhtNKyZb0-a4ilMcalpwiw";
const BACKEND = "http://127.0.0.1:8000";
let directory = "";

beforeEach(async () => {
  directory = await mkdtemp(path.join(tmpdir(), "walksafe-actor-v4-"));
  Object.assign(process.env, {
    NODE_ENV: "test", WALKSAFE_ENVIRONMENT: "test",
    WALKSAFE_GATEWAY_SESSION_SECRET: SECRET,
    WALKSAFE_FIELD_TEST_TOKEN: TOKEN,
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: directory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(directory, "walk.json")
  });
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  await configureTestStateEncryption(path.join(directory, "keyring.json"));
});
afterEach(async () => { await rm(directory, { recursive: true, force: true }); });

function sha256(value: string | Uint8Array): string {
  return createHash("sha256").update(value).digest("hex");
}

function sessionRequest(authEpoch = 4): Request {
  const login = new Request("https://gateway.invalid/api/field-session");
  const response = establishBackendGatewaySession(login, {
    actorId: ACTOR, accountGeneration: 7, authEpoch, deviceId: "v4-device"
  }, false);
  assert.equal(response.status, 200);
  const cookie = (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  return new Request("https://gateway.invalid/api/navigation/walking", {
    headers: { cookie, [ACTOR_ASSERTION_HEADER]: "client-spoof", [ACTOR_ID_HEADER]: "forged" }
  });
}

type Captured = { input: string; init: RequestInit };
function capture(status = 200): { calls: Captured[]; fetchImpl: GatewayFetch } {
  const calls: Captured[] = [];
  return {
    calls,
    fetchImpl: async (input, init) => {
      calls.push({ input: String(input), init: init ?? {} });
      return status === 204 ? new Response(null, { status }) : Response.json({ ok: true }, { status });
    }
  };
}

const EXPECTED = { actorId: ACTOR, accountGeneration: 7, authEpoch: 4 };

test("v4 matches the cross-language wire contract and rejects non-canonical input", () => {
  assert.equal(backendActorAssertionKeyId(SECRET), "5cJ6Zd4ib6A6VfBn");
  const input = {
    actorId: "v4-contract-actor", accountGeneration: 7, authEpoch: 4,
    method: "POST", target: "/navigation/walking?probe=1", bodySha256: sha256("{\"a\":1}")
  };
  assert.equal(createBackendActorAssertionV4(input, 2_000_000_000, "AAECAwQFBgcICQoLDA0ODw"), GOLDEN_TOKEN);
  const fresh = createBackendActorAssertionV4(input)!;
  assert.match(fresh, /^v4\.\d+\.\d+\.4\.5cJ6Zd4ib6A6VfBn\.[A-Za-z0-9_-]{22}\.[A-Za-z0-9_-]{43}$/);
  assert.notEqual(fresh.split(".")[5], createBackendActorAssertionV4(input)!.split(".")[5]);
  for (const change of [
    { method: "post" }, { method: "" }, { target: "navigation/walking" },
    { target: "/navigation/walking?a b" }, { target: "/navigation/\nwalking" },
    { bodySha256: "A".repeat(64) }, { bodySha256: "a".repeat(63) },
    { authEpoch: 0 }, { authEpoch: 1.5 }, { accountGeneration: 0 },
    { actorId: "bad actor" }
  ]) {
    assert.equal(createBackendActorAssertionV4({ ...input, ...change }, 2_000_000_000), null,
      JSON.stringify(change));
  }
  assert.equal(createBackendActorAssertionV4(input, 2_000_000_000, "short"), null);
  process.env.WALKSAFE_GATEWAY_SESSION_SECRET = "too-short-secret";
  assert.equal(createBackendActorAssertionV4(input, 2_000_000_000), null);
});

test("proxy headers carry only a non-verifiable pending marker until fetchBackend signs", async () => {
  const request = sessionRequest();
  const headers = proxyRequestHeaders(request, { "content-type": "application/json" });
  assert.equal(headers.get(ACTOR_ID_HEADER), ACTOR);
  assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "7");
  assert.equal(headers.get(ACTOR_ASSERTION_HEADER), PENDING_BACKEND_ACTOR_ASSERTION);
  assert.equal(proxyRequestHeaders(request, undefined, 8).has(ACTOR_ASSERTION_HEADER), false);

  const { calls, fetchImpl } = capture();
  const body = "{\"origin\":{\"lat\":37.5,\"lng\":127}}";
  const init: RequestInit = { method: "POST", headers, body, cache: "no-store" };
  await fetchBackend(request, `${BACKEND}/navigation/walking?probe=1`, init, 15_000, fetchImpl);
  await fetchBackend(request, `${BACKEND}/navigation/walking?probe=1`, init, 15_000, fetchImpl);
  assert.equal(calls.length, 2);
  const first = assertBackendActorAssertionV4(calls[0]!.input, calls[0]!.init, EXPECTED);
  const second = assertBackendActorAssertionV4(calls[1]!.input, calls[1]!.init, EXPECTED);
  assert.equal(first.target, "/navigation/walking?probe=1");
  assert.equal(first.bodySha256, sha256(body));
  assert.notEqual(first.jti, second.jti, "each Backend call gets a single-use identifier");
  assert.equal(calls[0]!.init.body, body, "string bodies are forwarded unchanged");
  assert.equal(headers.get(ACTOR_ASSERTION_HEADER), PENDING_BACKEND_ACTOR_ASSERTION,
    "the caller's headers are not mutated, so a retry signs again");

  const signed = calls[0]!;
  for (const [input, changed] of [
    [signed.input, { ...signed.init, method: "PUT" }],
    [signed.input, { ...signed.init, body: body.replace("37.5", "37.6") }],
    [`${BACKEND}/navigation/walking`, signed.init],
    [`${BACKEND}/navigation/walking?probe=2`, signed.init],
    [`${BACKEND}/reports/v2?probe=1`, signed.init]
  ] as Array<[string, RequestInit]>) {
    assert.throws(() => assertBackendActorAssertionV4(input, changed, EXPECTED),
      "a v4 assertion must not verify for another method, target or body");
  }
});

test("GET, Buffer, legacy-epoch and query-bearing calls are each bound", async () => {
  const { calls, fetchImpl } = capture();
  const request = sessionRequest();
  await fetchBackend(request, `${BACKEND}/reports/mine/requests/history?limit=10&cursor=a%2Fb`, {
    method: "GET", headers: proxyRequestHeaders(request)
  }, 15_000, fetchImpl);
  const bytes = Buffer.from([0, 1, 2, 250]);
  await fetchBackend(request, `${BACKEND}/navigation/walking`, {
    method: "POST", headers: proxyRequestHeaders(request), body: bytes
  }, 15_000, fetchImpl);
  const listed = assertBackendActorAssertionV4(calls[0]!.input, calls[0]!.init, EXPECTED);
  assert.equal(listed.target, "/reports/mine/requests/history?limit=10&cursor=a%2Fb");
  assert.equal(listed.bodySha256, sha256(""));
  assert.equal(listed.method, "GET");
  assert.equal(assertBackendActorAssertionV4(calls[1]!.input, calls[1]!.init, EXPECTED).bodySha256,
    sha256(bytes));
});

test("multipart reports are serialized once and the sent bytes are signed", async () => {
  const { calls, fetchImpl } = capture(201);
  const request = sessionRequest();
  const form = new FormData();
  form.set("metadata", "{\"source\":\"android\",\"auto_reported\":false}");
  form.set("image", new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: "image/jpeg" }), "report.jpg");
  const response = await fetchBackend(request, `${BACKEND}/reports/v2`, {
    method: "POST", headers: proxyRequestHeaders(request), body: form
  }, 15_000, fetchImpl);
  assert.equal(response.status, 201);
  const sent = calls[0]!;
  assert.ok(ArrayBuffer.isView(sent.init.body), "multipart is forwarded as the exact signed bytes");
  assertBackendActorAssertionV4(sent.input, sent.init, EXPECTED);
  const parsed = await upstreamFormData(sent.init);
  assert.equal(parsed.get("metadata"), "{\"source\":\"android\",\"auto_reported\":false}");
  const image = parsed.get("image");
  assert.ok(image instanceof Blob);
  assert.equal(image.type, "image/jpeg");
  assert.deepEqual(new Uint8Array(await image.arrayBuffer()), new Uint8Array([0xff, 0xd8, 0xff]));
});

test("a body the Gateway cannot hash fails closed before any Backend I/O", async () => {
  const { calls, fetchImpl } = capture();
  const request = sessionRequest();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) { controller.enqueue(new Uint8Array([1])); controller.close(); }
  });
  const response = await fetchBackend(request, `${BACKEND}/navigation/walking`, {
    method: "POST", headers: proxyRequestHeaders(request), body: stream,
    duplex: "half"
  } as RequestInit, 15_000, fetchImpl);
  // Reuses the registered transport-failure code: no request was sent unsigned.
  assert.equal(response.status, 502);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.equal(((await response.json()) as { detail: { code: string } }).detail.code,
    "gateway_upstream_unavailable");
  assert.equal(calls.length, 0);
});

test("raw collection keeps its request proof and adds a v4 actor assertion per call", async () => {
  const { calls, fetchImpl } = capture(201);
  const request = sessionRequest();
  const input = {
    actorId: ACTOR, accountGeneration: 7, authEpoch: 4,
    operation: "PUT_CHUNK" as const, method: "PUT" as const,
    path: "/raw-collections/aaaaaaaa-1111-4111-8111-111111111111/objects/" +
      "cccccccc-3333-4333-8333-333333333333/chunks/0",
    purpose: "GENERAL_RAW" as const,
    walkId: "bbbbbbbb-2222-4222-8222-222222222222",
    manifestSha256: "a".repeat(64), consentReceiptSha256: "b".repeat(64),
    chunkSha256: sha256(Buffer.from("raw-bytes")), commitSha256: null
  };
  const headers = rawCollectionBackendHeaders(input, {
    "content-type": "application/octet-stream", "content-length": "9"
  }, 2_000_000_000)!;
  assert.equal(headers.get(RAW_REQUEST_PROOF_HEADER), createRawCollectionRequestProof(input, 2_000_000_000));
  assert.equal(headers.get(ACTOR_ASSERTION_HEADER), PENDING_BACKEND_ACTOR_ASSERTION);
  await fetchBackend(request, `${BACKEND}${input.path}`, {
    method: "PUT", headers, body: Buffer.from("raw-bytes")
  }, 20_000, fetchImpl);
  await fetchBackend(request, `${BACKEND}${input.path}`, { method: "HEAD", headers }, 20_000, fetchImpl);
  const put = assertBackendActorAssertionV4(calls[0]!.input, calls[0]!.init, EXPECTED);
  assert.equal(put.bodySha256, input.chunkSha256);
  const head = assertBackendActorAssertionV4(calls[1]!.input, calls[1]!.init, EXPECTED);
  assert.equal(head.method, "HEAD");
  assert.equal(new Headers(calls[0]!.init.headers).get(RAW_REQUEST_PROOF_HEADER),
    headers.get(RAW_REQUEST_PROOF_HEADER));
});

test("session-current is a v4 body-free POST bound to its path", async () => {
  const { calls, fetchImpl } = capture(204);
  const result = await checkBackendAccountSessionCurrent(
    { actorId: ACTOR, accountGeneration: 7, authEpoch: 4 }, fetchImpl
  );
  assert.equal(result, "current");
  const sent = calls[0]!;
  assert.equal(sent.input, `${BACKEND}/accounts/session-current`);
  assert.equal(sent.init.method, "POST");
  assert.equal(sent.init.body, undefined);
  assert.equal(sent.init.redirect, "error");
  assert.equal(assertBackendActorAssertionV4(sent.input, sent.init, EXPECTED).bodySha256, sha256(""));
});

test("service-only and account-deletion requests are not re-signed", async () => {
  const { calls, fetchImpl } = capture();
  const request = new Request("https://gateway.invalid/api/accounts");
  await fetchBackend(request, `${BACKEND}/accounts`, {
    method: "POST", headers: backendServiceRequestHeaders()!, body: "{}"
  }, 15_000, fetchImpl);
  assert.equal(new Headers(calls[0]!.init.headers).has(ACTOR_ASSERTION_HEADER), false);
  const deletion = deletionBackendHeaders({
    actorId: ACTOR, accountGeneration: 7, requestId: "deletion-request-0001",
    tombstoneId: null, accessPreDigest: "c".repeat(64), method: "POST",
    path: "/privacy/account-deletions", bodySha256: sha256("{}")
  })!;
  const scoped = deletion.get(ACTOR_ASSERTION_HEADER);
  assert.match(scoped ?? "", /^v2\.\d+\.[A-Za-z0-9_-]{43}$/);
  await fetchBackend(request, `${BACKEND}/privacy/account-deletions`, {
    method: "POST", headers: deletion, body: "{}"
  }, 15_000, fetchImpl);
  assert.equal(new Headers(calls[1]!.init.headers).get(ACTOR_ASSERTION_HEADER), scoped);
});

test("general actor assertions have a single issuance point in the Gateway source", async () => {
  const sourceDir = path.join(process.cwd(), "src");
  const files = (await readdir(sourceDir)).filter((name) => name.endsWith(".ts"));
  assert.ok(files.includes("backend.ts"));
  for (const name of files) {
    const text = await readFile(path.join(sourceDir, name), "utf8");
    const sets = text.match(/\.set\(\s*ACTOR_ASSERTION_HEADER\b/g)?.length ?? 0;
    const literals = text.match(/["']x-walksafe-actor-assertion["']/g)?.length ?? 0;
    const legacyCalls = (text.match(/\bcreateBackendActorAssertion\(/g)?.length ?? 0) -
      (text.match(/function createBackendActorAssertion\(/g)?.length ?? 0);
    assert.equal(legacyCalls, 0, `${name} must not issue v2/v3 actor assertions`);
    if (name === "backend-request-signing.ts") {
      // pending marker (bindBackendActor), v4 signature (signBackendActorRequest),
      // and the separate request-bound account-deletion assertion.
      assert.equal(sets, 3, "backend-request-signing.ts assertion issuance points");
      assert.equal(literals, 0, "the signer uses the shared header constant");
    } else if (name === "backend-contract.ts") {
      assert.equal(sets, 0, "the wire contract never issues assertions");
      assert.equal(literals, 1, "only the ACTOR_ASSERTION_HEADER constant names the header");
    } else {
      assert.equal(sets, 0, `${name} must not set the actor assertion header`);
      assert.equal(literals, 0, `${name} must not name the actor assertion header`);
    }
    if (name !== "backend.ts" && name !== "node-adapter.ts" && name !== "speech-relay.ts" &&
        name !== "server-capacity.ts") {
      assert.doesNotMatch(text, /\bfetchImpl\(\s*backendUrl\(/,
        `${name} must reach the Backend through fetchBackend`);
      assert.doesNotMatch(text, /globalThis\.fetch\(/, `${name} must not call fetch directly`);
    }
  }
});
