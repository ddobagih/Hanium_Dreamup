import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdir, mkdtemp, open, readFile, rm, writeFile } from "node:fs/promises";
import { request as httpRequest, type IncomingMessage, type ServerResponse } from "node:http";
import { connect, type AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import { AccountDeletionOutboxWorker } from "../src/account-deletion-outbox-worker.js";
import { FieldLongSessionStorageUnavailableError } from "../src/field-long-session.js";
import {
  DEFAULT_MAX_LEDGER_BYTES,
  fieldWalkLedgerShardPaths,
  MAX_STORED_LEDGER_BYTES
} from "../src/field-walk-ledger.js";
import { fieldSessionErrorContractViolation } from "../src/field-session-errors.js";
import {
  createGatewayReadinessProbe,
  createGatewayServer,
  GATEWAY_REQUEST_DEADLINE_GRACE_MS,
  GATEWAY_REQUEST_DEADLINE_MS,
  gatewayRequestDeadlineMs,
  writeWebResponse
} from "../src/node-adapter.js";
import { BACKEND_SESSION_GATE_TIMEOUT_MS, type GatewayFetch } from "../src/backend.js";
import { establishBackendGatewaySession } from "../src/auth.js";
import { bindBackendActorGeneration } from "../src/privacy-rights.js";
import {
  assertFieldLongSessionConfiguration,
  assertTrustedClientIpConfiguration
} from "../src/config.js";
import { activePrivacyOperationCountForTests } from "../src/privacy-rights.js";
import {
  probeGatewayManagedStorageReady,
  saturatedGatewayStores
} from "../src/state-encryption-maintenance.js";
import { maxGatewayStateEnvelopeBytes } from "../src/encrypted-json-store.js";
import { PRIVACY_DELETION_V2_MAX_BYTES } from "../src/privacy-deletion-v2.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const ACTOR_ID = "direct-android";
const ACCOUNT_TOKEN = "direct-android-account-token-12345678901234567890";
const TEST_ENV_KEYS = [
  "NODE_ENV",
  "WALKSAFE_FIELD_TEST_TOKEN",
  "WALKSAFE_FIELD_ACCOUNTS_JSON",
  "WALKSAFE_GATEWAY_SESSION_SECRET",
  "WALKSAFE_GATEWAY_TRUSTED_IP_HEADER",
  "WALKSAFE_GATEWAY_RATE_LIMIT_DIR",
  "WALKSAFE_FIELD_WALK_LEDGER_PATH",
  "WALKSAFE_GATEWAY_STATE_KEYRING_FILE",
  "WALKSAFE_ADMIN_TOKEN",
  "WALKSAFE_ADMIN_ACCOUNTS_JSON"
] as const;
const originalEnvironment = new Map(TEST_ENV_KEYS.map(key => [key, process.env[key]]));
let stateDirectory = "";

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-android-gateway-http-test-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_FIELD_TEST_TOKEN: "direct-android-internal-token-12345678901234567890",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([{ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }]),
    WALKSAFE_GATEWAY_SESSION_SECRET: "direct-android-session-secret-123456789012345678901234",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "x-real-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    // R19-GB-01: per-file ledger so parallel test files never share the default lock.
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json")
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
  delete process.env.WALKSAFE_ADMIN_TOKEN;
  delete process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON;
});

after(async () => {
  for (const [key, value] of originalEnvironment) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
  await rm(stateDirectory, { recursive: true, force: true });
});

test("real loopback HTTP adapter supplies peer IP for direct Android login", async () => {
  const server = createGatewayServer();
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  try {
    const address = server.address() as AddressInfo;
    const origin = `http://127.0.0.1:${address.port}`;
    const health = await fetch(`${origin}/internal/health`);
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), { status: "ok" });
    assert.equal(health.headers.get("cache-control"), "no-store");

    const healthWithQuery = await fetch(`${origin}/internal/health?token=sensitive`);
    assert.equal(healthWithQuery.status, 404);
    const healthWrongMethod = await fetch(`${origin}/internal/health`, {
      method: "POST"
    });
    assert.equal(healthWrongMethod.status, 404);

    const login = await fetch(`${origin}/api/field-session`, {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }),
      redirect: "error"
    });
    assert.equal(login.status, 200);
    assert.deepEqual(await login.json(), { session_scope: "general" });
    assert.equal(login.headers.get("cache-control"), "no-store");
    const setCookie = login.headers.get("set-cookie") ?? "";
    assert.match(setCookie, /^walksafe_field_session=[^;]+; Path=\/; HttpOnly; SameSite=Strict; Max-Age=43200$/);
    assert.doesNotMatch(setCookie, /Secure|Domain=/i);
    const cookie = setCookie.split(";", 1)[0]!;

    const status = await fetch(`${origin}/api/field-session`, { headers: { cookie } });
    assert.equal(status.status, 200);
    assert.deepEqual(await status.json(), {
      required: true,
      authenticated: true,
      actor_id: ACTOR_ID,
      session_scope: "general"
    });
    assert.equal(status.headers.get("cache-control"), "no-store");

    const logout = await fetch(`${origin}/api/field-session`, {
      method: "DELETE",
      headers: { cookie }
    });
    assert.equal(logout.status, 204);
    const loggedOutStatus = await fetch(`${origin}/api/field-session`, { headers: { cookie } });
    assert.equal((await loggedOutStatus.json() as { authenticated: boolean }).authenticated, false);

    const unknown = await fetch(`${origin}/api/health`);
    assert.equal(unknown.status, 404);
    assert.equal(unknown.headers.get("cache-control"), "no-store");
    const wrongMethod = await fetch(`${origin}/api/reports/v2`, { method: "GET" });
    assert.equal(wrongMethod.status, 405);
    assert.equal(wrongMethod.headers.get("allow"), "POST");

    const invalidTarget = await new Promise<{ status: number; body: string }>((resolve, reject) => {
      const request = httpRequest(
        { host: "127.0.0.1", port: address.port, method: "GET", path: "//invalid-target" },
        (response) => {
          const chunks: Buffer[] = [];
          response.on("data", (chunk: Buffer) => chunks.push(chunk));
          response.on("end", () => resolve({
            status: response.statusCode ?? 0,
            body: Buffer.concat(chunks).toString("utf8")
          }));
        }
      );
      request.once("error", reject);
      request.end();
    });
    assert.equal(invalidTarget.status, 400);
    assert.equal(JSON.parse(invalidTarget.body).code, "gateway_invalid_request");
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
    });
  }
});

test("production node adapter secures session cookies on internal HTTP requests", async () => {
  const previousNodeEnv = process.env.NODE_ENV;
  try {
    process.env.NODE_ENV = "production";
    const server = createGatewayServer();
    try {
      await new Promise<void>((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", resolve);
      });
      const port = (server.address() as AddressInfo).port;
      const origin = `http://127.0.0.1:${port}`;
      for (const forwardedProto of [undefined, "http"]) {
        // S19-53: in production the proxy sets the trusted client IP header.
        const headers: Record<string, string> = {
          "content-type": "application/json",
          "x-real-ip": "198.51.100.40"
        };
        if (forwardedProto) headers["x-forwarded-proto"] = forwardedProto;
        const login = await fetch(`${origin}/api/field-session`, {
          method: "POST",
          headers,
          body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
        });
        assert.equal(login.status, 200);
        const setCookie = login.headers.get("set-cookie") ?? "";
        assert.match(
          setCookie,
          /^walksafe_field_session=[^;]+; Path=\/; HttpOnly; SameSite=Strict; Max-Age=43200; Secure$/
        );

        const logout = await fetch(`${origin}/api/field-session`, {
          method: "DELETE",
          headers: { cookie: setCookie.split(";", 1)[0]!, "x-real-ip": "198.51.100.40" }
        });
        assert.equal(logout.status, 204);
        assert.match(logout.headers.get("set-cookie") ?? "", /(?:^|; )Secure(?:;|$)/);
      }
    } finally {
      if (server.listening) {
        await new Promise<void>((resolve, reject) => {
          server.close((error) => error ? reject(error) : resolve());
        });
      }
    }
  } finally {
    if (previousNodeEnv === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = previousNodeEnv;
  }
});


test("S19-53 production refuses a request without the proxy-set trusted client IP instead of using the loopback peer", async () => {
  const previousNodeEnv = process.env.NODE_ENV;
  try {
    process.env.NODE_ENV = "production";
    const server = createGatewayServer();
    try {
      await new Promise<void>((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", resolve);
      });
      const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
      const login = (headers: Record<string, string>) => fetch(`${origin}/api/field-session`, {
        method: "POST",
        headers: { "content-type": "application/json", ...headers },
        body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
      });
      const refused = await login({});
      assert.equal(refused.status, 503);
      const body = await refused.json() as Record<string, unknown>;
      assert.deepEqual(body, { code: "gateway_trusted_client_ip_unavailable" });
      assert.equal(fieldSessionErrorContractViolation(503, body), null);
      assert.equal(refused.headers.get("set-cookie"), null);
      // Loopback liveness stays available without the header.
      assert.equal((await fetch(`${origin}/internal/health`)).status, 200);
      assert.equal((await login({ "x-real-ip": "198.51.100.41" })).status, 200);
    } finally {
      if (server.listening) {
        await new Promise<void>((resolve, reject) => {
          server.close((error) => error ? reject(error) : resolve());
        });
      }
    }
  } finally {
    if (previousNodeEnv === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = previousNodeEnv;
  }
});

test("gw-F2 S19-53 production refuses a trusted IP header that is not exactly one valid address", async () => {
  const previousNodeEnv = process.env.NODE_ENV;
  try {
    process.env.NODE_ENV = "production";
    const server = createGatewayServer();
    try {
      await new Promise<void>((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", resolve);
      });
      const port = (server.address() as AddressInfo).port;
      // node:http sends every value as written (an array becomes duplicate header lines).
      const post = (body: Record<string, unknown>, trustedIp: string | string[]) =>
        new Promise<{ status: number; body: Record<string, unknown> }>((resolve, reject) => {
          const payload = JSON.stringify(body);
          const outgoing = httpRequest({
            host: "127.0.0.1",
            port,
            method: "POST",
            path: "/api/field-session",
            headers: {
              "content-type": "application/json",
              "content-length": Buffer.byteLength(payload),
              "x-real-ip": trustedIp
            }
          }, (incoming) => {
            let text = "";
            incoming.setEncoding("utf8");
            incoming.on("data", (chunk: string) => { text += chunk; });
            incoming.on("end", () => resolve({
              status: incoming.statusCode ?? 0,
              body: JSON.parse(text) as Record<string, unknown>
            }));
          });
          outgoing.once("error", reject);
          outgoing.end(payload);
        });
      const login = { actor_id: ACTOR_ID, token: ACCOUNT_TOKEN };
      const refresh = {
        grant_type: "refresh_token", actor_id: ACTOR_ID, device_id: "gw-f2-device-000001",
        family_id: "gw-f2-family-000001", rotation: 1, refresh_token: "f".repeat(64)
      };
      const unusable: Array<[string, string | string[]]> = [
        ["not an IP", "not-an-ip"],
        ["comma merged", "198.51.100.78, 203.0.113.9"],
        ["empty", ""],
        ["duplicate headers", ["198.51.100.79", "203.0.113.10"]]
      ];
      for (const [label, value] of unusable) {
        for (const [kind, body] of [["login", login], ["refresh", refresh]] as const) {
          const refused = await post(body, value);
          assert.equal(refused.status, 503, `${label} ${kind}`);
          assert.deepEqual(refused.body, { code: "gateway_trusted_client_ip_unavailable" });
          assert.equal(fieldSessionErrorContractViolation(503, refused.body), null);
        }
      }
      // gw-F2 probe shape: a refresh flood with an unusable address never reaches the handler.
      for (let index = 0; index < 70; index += 1) {
        assert.equal((await post({ ...refresh, actor_id: `gw-f2-flood-${index}` }, "not-an-ip")).status, 503);
      }
      assert.equal((await post(login, "198.51.100.42")).status, 200);
      assert.equal((await post(login, "2001:db8::42")).status, 200);
    } finally {
      if (server.listening) {
        await new Promise<void>((resolve, reject) => {
          server.close((error) => error ? reject(error) : resolve());
        });
      }
    }
  } finally {
    if (previousNodeEnv === undefined) delete process.env.NODE_ENV;
    else process.env.NODE_ENV = previousNodeEnv;
  }
});

test("S19-53 production startup requires the header its proxy overwrites", () => {
  const message = /WALKSAFE_GATEWAY_TRUSTED_IP_HEADER must be cf-connecting-ip or x-real-ip in production/;
  for (const value of [undefined, "", "x-forwarded-for", "forwarded", "true-client-ip"]) {
    const environment: NodeJS.ProcessEnv = { NODE_ENV: "production" };
    if (value !== undefined) environment.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER = value;
    assert.throws(() => assertTrustedClientIpConfiguration(environment), message);
    // Checked with the other field settings server.ts validates before listen.
    assert.throws(() => assertFieldLongSessionConfiguration(environment), message);
  }
  for (const value of ["x-real-ip", "CF-Connecting-IP"]) {
    const environment = { NODE_ENV: "production", WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: value };
    assert.doesNotThrow(() => assertTrustedClientIpConfiguration(environment));
    assert.doesNotThrow(() => assertFieldLongSessionConfiguration(environment));
  }
  // Development keeps the direct loopback connection (adb reverse) without the header.
  assert.doesNotThrow(() => assertTrustedClientIpConfiguration({ NODE_ENV: "development" }));
  assert.doesNotThrow(() => assertFieldLongSessionConfiguration({ NODE_ENV: "test" }));
});

test("S19-53 deploy templates name the header nginx overwrites and clear client aliases", async () => {
  const deploy = path.join(process.cwd(), "..", "..", "deploy");
  const example = await readFile(path.join(deploy, "config", "walksafe-android-gateway.env.example"), "utf8");
  const environment = Object.fromEntries(
    example.split("\n")
      .filter((line) => /^[A-Z0-9_]+=/.test(line))
      .map((line) => [line.slice(0, line.indexOf("=")), line.slice(line.indexOf("=") + 1)])
  ) as NodeJS.ProcessEnv;
  assert.equal(environment.NODE_ENV, "production");
  assert.equal(environment.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER, "x-real-ip");
  assert.doesNotThrow(() => assertTrustedClientIpConfiguration(environment));
  const nginx = await readFile(path.join(deploy, "nginx", "walksafe-android-gateway.conf.example"), "utf8");
  // The proxy sets the trusted header from the socket peer on every request, and removes the
  // aliases a client could send, so the Gateway never trusts a client-chosen address.
  assert.match(nginx, /^\s*proxy_set_header X-Real-IP \$remote_addr;$/m);
  for (const alias of ["CF-Connecting-IP", "Forwarded", "True-Client-IP", "X-Client-IP"]) {
    assert.match(nginx, new RegExp(`^\\s*proxy_set_header ${alias} "";$`, "m"), alias);
  }
  assert.doesNotMatch(nginx, /proxy_set_header X-Real-IP (?!\$remote_addr;)/);
});

async function listenGateway(
  dependencies: Parameters<typeof createGatewayServer>[0]
): Promise<{ origin: string; close: () => Promise<void> }> {
  const server = createGatewayServer(dependencies);
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  return {
    origin: `http://127.0.0.1:${(server.address() as AddressInfo).port}`,
    close: () => new Promise<void>((resolve, reject) => {
      server.closeAllConnections();
      server.close((error) => error ? reject(error) : resolve());
    })
  };
}

async function directLoginCookie(origin: string): Promise<string> {
  const login = await fetch(`${origin}/api/field-session`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
  });
  assert.equal(login.status, 200);
  await login.arrayBuffer();
  return (login.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
}

test("S19-59 a handler past its whole-request deadline is cancelled and answers 504", async () => {
  const upstreamSignals: AbortSignal[] = [];
  let mode: "abortable" | "ignores_abort" | "streams_after_deadline" = "abortable";
  let lateBodyCancelled = false;
  const fetchImpl: GatewayFetch = async (_url, init) => {
    const signal = init?.signal as AbortSignal;
    upstreamSignals.push(signal);
    if (mode === "abortable") {
      // Like a real fetch: pending until the signal aborts.
      return await new Promise<Response>((_resolve, reject) => {
        signal.addEventListener("abort", () => reject(signal.reason), { once: true });
      });
    }
    if (mode === "ignores_abort") {
      // A dependency that never observes the signal answers long after the deadline.
      await new Promise((resolve) => setTimeout(resolve, 3 * GATEWAY_REQUEST_DEADLINE_GRACE_MS));
      return new Response(new ReadableStream<Uint8Array>({
        cancel() { lateBodyCancelled = true; }
      }), { status: 200 });
    }
    // Headers are ready at once; the body arrives after the deadline would have passed.
    return new Response(new ReadableStream<Uint8Array>({
      async start(controller) {
        await new Promise((resolve) => setTimeout(resolve, 400));
        controller.enqueue(new TextEncoder().encode('{"items":[]}'));
        controller.close();
      }
    }), { status: 200, headers: { "content-type": "application/json" } });
  };
  const gateway = await listenGateway({ fetchImpl, requestDeadlineMs: () => 200 });
  const errors: unknown[] = [];
  const originalConsoleError = console.error;
  console.error = (...values: unknown[]) => { errors.push(values.join(" ")); };
  try {
    const cookie = await directLoginCookie(gateway.origin);
    const baselineOperations = activePrivacyOperationCountForTests();
    const search = () => fetch(`${gateway.origin}/api/navigation/destinations/search?query=test`, {
      headers: { cookie }
    });

    let startedAt = performance.now();
    const cancelled = await search();
    const cancelledElapsed = performance.now() - startedAt;
    assert.equal(cancelled.status, 504);
    const body = await cancelled.json() as Record<string, unknown>;
    assert.deepEqual(body, { code: "gateway_request_deadline_exceeded" });
    assert.equal(fieldSessionErrorContractViolation(504, body), null);
    assert.equal(cancelled.headers.get("cache-control"), "no-store");
    // The Backend call itself was aborted, not just abandoned, and the lease was released.
    assert.equal(upstreamSignals.length, 1);
    assert.equal(upstreamSignals[0]!.aborted, true);
    assert.ok(cancelledElapsed < 200 + GATEWAY_REQUEST_DEADLINE_GRACE_MS, `${cancelledElapsed} ms`);
    assert.equal(activePrivacyOperationCountForTests(), baselineOperations);

    mode = "ignores_abort";
    startedAt = performance.now();
    const forced = await search();
    const forcedElapsed = performance.now() - startedAt;
    assert.equal(forced.status, 504);
    assert.deepEqual(await forced.json(), { code: "gateway_request_deadline_exceeded" });
    assert.ok(forcedElapsed >= 190, `${forcedElapsed} ms`);
    assert.ok(forcedElapsed < 3 * GATEWAY_REQUEST_DEADLINE_GRACE_MS, `${forcedElapsed} ms`);
    assert.equal(upstreamSignals[1]!.aborted, true);
    // The late upstream answer is released instead of being streamed to nobody.
    const lateDeadline = Date.now() + 5_000;
    while (!lateBodyCancelled && Date.now() < lateDeadline) {
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    assert.equal(lateBodyCancelled, true);
    assert.equal(activePrivacyOperationCountForTests(), baselineOperations);

    // Navigation is complete only after the full upstream body, even if headers arrived.
    mode = "streams_after_deadline";
    const streamed = await search();
    assert.equal(streamed.status, 504);
    assert.deepEqual(await streamed.json(), { code: "gateway_request_deadline_exceeded" });
    assert.equal(upstreamSignals[2]!.aborted, true);
    assert.equal(
      errors.filter((value) => value === "gateway_request_deadline_exceeded").length,
      3
    );
  } finally {
    console.error = originalConsoleError;
    await gateway.close();
  }
});

test("S07-30 live HTTP navigation ends before Android's 10 s call and closes cancelled upstream work", async (t) => {
  bindBackendActorGeneration(ACTOR_ID, 1);
  const issued = establishBackendGatewaySession(
    new Request("http://127.0.0.1:8081/api/field-session"),
    { actorId: ACTOR_ID, accountGeneration: 1, authEpoch: 1, deviceId: "s07-30-http-device" },
    false
  );
  assert.equal(issued.status, 200);
  const accountCookie = (issued.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  assert.match(accountCookie, /^walksafe_field_session=v7\./);

  let closeRequestStarted!: () => void;
  const closeRequestReady = new Promise<void>((resolve) => { closeRequestStarted = resolve; });
  const abortedSignals: AbortSignal[] = [];
  let lateBodiesCancelled = 0;
  const fetchImpl: GatewayFetch = async (input, init) => {
    const upstreamUrl = String(input);
    if (upstreamUrl.endsWith("/accounts/session-current")) {
      await new Promise((resolve) => setTimeout(resolve, 4_000));
      return new Response(null, { status: 204 });
    }
    const signal = init!.signal!;
    if (upstreamUrl.includes("query=close")) closeRequestStarted();
    // Simulate an upstream implementation that ignores abort and returns a body late.
    return await new Promise<Response>((resolve) => {
      signal.addEventListener("abort", () => {
        abortedSignals.push(signal);
        setTimeout(() => resolve(new Response(new ReadableStream<Uint8Array>({
          cancel() { lateBodiesCancelled += 1; }
        }), { status: 200 })), 40);
      }, { once: true });
    });
  };
  const gateway = await listenGateway({ fetchImpl });
  try {
    const timed = async (method: "GET" | "POST", pathname: string) => {
      const started = performance.now();
      const response = await fetch(`${gateway.origin}${pathname}`, {
        method,
        headers: method === "POST"
          ? { cookie: accountCookie, "content-type": "application/json" }
          : { cookie: accountCookie },
        ...(method === "POST" ? { body: JSON.stringify({
          origin: { latitude: 37.5, longitude: 127.0 },
          destination: { latitude: 37.6, longitude: 127.1 }
        }) } : {})
      });
      return { response, elapsed: performance.now() - started };
    };
    const [walking, search] = await Promise.all([
      timed("POST", "/api/navigation/walking"),
      timed("GET", "/api/navigation/destinations/search?query=test")
    ]);
    for (const [name, result, outerMs] of [
      ["walking", walking, 8_000], ["search", search, 7_000]
    ] as const) {
      assert.equal(result.response.status, 504, name);
      assert.equal((await result.response.json() as { detail: { code: string } }).detail.code,
        "gateway_navigation_timeout", name);
      assert.ok(result.elapsed < outerMs, `${name} response ${result.elapsed} ms`);
      assert.ok(result.elapsed < 10_000, `${name} must answer before Android's call deadline`);
    }
    assert.equal(abortedSignals.length, 2);

    const oldCookie = await directLoginCookie(gateway.origin);
    const closeAbort = new AbortController();
    const closed = fetch(`${gateway.origin}/api/navigation/destinations/search?query=close`, {
      headers: { cookie: oldCookie }, signal: closeAbort.signal
    });
    await closeRequestReady;
    const closeStarted = performance.now();
    closeAbort.abort(new Error("caller closed the connection"));
    await assert.rejects(closed);
    const cleanupDeadline = Date.now() + 2_000;
    while ((abortedSignals.length < 3 || lateBodiesCancelled < 3) && Date.now() < cleanupDeadline) {
      await new Promise((resolve) => setTimeout(resolve, 20));
    }
    assert.equal(abortedSignals.length, 3, "caller close aborts the upstream request");
    assert.equal(lateBodiesCancelled, 3, "late upstream bodies are cancelled");
    t.diagnostic(
      `walking_elapsed_ms=${walking.elapsed.toFixed(1)} ` +
      `search_elapsed_ms=${search.elapsed.toFixed(1)} ` +
      `caller_close_cleanup_ms=${(performance.now() - closeStarted).toFixed(1)} ` +
      `upstream_aborted=${abortedSignals.length} late_bodies_cancelled=${lateBodiesCancelled}`
    );
  } finally {
    await gateway.close();
  }
});

test("S19-59 route deadlines stay above each route's own body and upstream limits", () => {
  const request = (method: string, pathname: string) =>
    new Request(`http://127.0.0.1:8081${pathname}`, { method });
  const uuid = "123e4567-e89b-42d3-a456-426614174000";
  // gw-F6: every route may first pass the Backend account-session gate (5 s), so the gate is
  // part of each sum: ordinary = gate 5 + JSON body 10 + Backend 15 + margin 5.
  assert.equal(BACKEND_SESSION_GATE_TIMEOUT_MS, 5_000);
  assert.equal(GATEWAY_REQUEST_DEADLINE_MS, 5_000 + 10_000 + 15_000 + 5_000);
  for (const [method, pathname] of [
    ["POST", "/api/field-session"],
    ["GET", "/api/field-walk"],
    ["PUT", "/privacy/rights"],
    ["POST", "/privacy/account-deletions"],
    ["GET", "/api/unknown"]
  ] as const) {
    assert.equal(gatewayRequestDeadlineMs(request(method, pathname)), 35_000, pathname);
  }
  assert.equal(gatewayRequestDeadlineMs(request("POST", "/api/navigation/walking")), 8_000);
  assert.equal(gatewayRequestDeadlineMs(request("GET", "/api/navigation/destinations/search")), 7_000);
  // Uploads and speech keep their own limits (unchanged) plus the gate and a 5 s margin.
  assert.equal(gatewayRequestDeadlineMs(request("POST", "/api/reports/v2")), 5_000 + 15_000 + 15_000 + 5_000);
  assert.equal(
    gatewayRequestDeadlineMs(request("PUT", `/api/raw-collections/${uuid}/objects/${uuid}/chunks/0`)),
    5_000 + 15_000 + 20_000 + 5_000
  );
  assert.equal(gatewayRequestDeadlineMs(request("POST", `/api/raw-collections/${uuid}/commit`)), 45_000);
  const previous = {
    stt: process.env.WALKSAFE_VOICE_STT_TIMEOUT_MS,
    tts: process.env.WALKSAFE_VOICE_TTS_TIMEOUT_MS
  };
  try {
    delete process.env.WALKSAFE_VOICE_STT_TIMEOUT_MS;
    delete process.env.WALKSAFE_VOICE_TTS_TIMEOUT_MS;
    assert.equal(gatewayRequestDeadlineMs(request("POST", "/api/speech/stt")), 5_000 + 10_000 + 35_000 + 5_000);
    assert.equal(gatewayRequestDeadlineMs(request("POST", "/api/speech/tts")), 5_000 + 10_000 + 65_000 + 5_000);
    // A configured Voice timeout moves the deadline with it, never below it.
    process.env.WALKSAFE_VOICE_STT_TIMEOUT_MS = "120000";
    process.env.WALKSAFE_VOICE_TTS_TIMEOUT_MS = "100000";
    assert.equal(gatewayRequestDeadlineMs(request("POST", "/api/speech/stt")), 140_000);
    assert.equal(gatewayRequestDeadlineMs(request("POST", "/api/speech/tts")), 120_000);
    // The relay's own bounds apply: an out-of-range value falls back to the default.
    process.env.WALKSAFE_VOICE_TTS_TIMEOUT_MS = "999";
    assert.equal(gatewayRequestDeadlineMs(request("POST", "/api/speech/tts")), 85_000);
    // A short configured Voice timeout never lowers the ordinary floor.
    process.env.WALKSAFE_VOICE_STT_TIMEOUT_MS = "2000";
    assert.equal(gatewayRequestDeadlineMs(request("POST", "/api/speech/stt")), 35_000);
  } finally {
    if (previous.stt === undefined) delete process.env.WALKSAFE_VOICE_STT_TIMEOUT_MS;
    else process.env.WALKSAFE_VOICE_STT_TIMEOUT_MS = previous.stt;
    if (previous.tts === undefined) delete process.env.WALKSAFE_VOICE_TTS_TIMEOUT_MS;
    else process.env.WALKSAFE_VOICE_TTS_TIMEOUT_MS = previous.tts;
  }
});

async function rawBodyRequest(
  port: number,
  method: string,
  pathname: string,
  headers: Record<string, string>,
  body: string
): Promise<{ status: number; body: string; cacheControl: string | undefined; allow: string | undefined }> {
  return new Promise((resolve, reject) => {
    const request = httpRequest(
      { host: "127.0.0.1", port, method, path: pathname, headers, agent: false },
      (response) => {
        const chunks: Buffer[] = [];
        response.on("data", (chunk: Buffer) => chunks.push(chunk));
        response.on("end", () => resolve({
          status: response.statusCode ?? 0,
          body: Buffer.concat(chunks).toString("utf8"),
          cacheControl: response.headers["cache-control"],
          allow: response.headers.allow
        }));
      }
    );
    request.setTimeout(2_000, () => request.destroy(new Error("fixture request timed out")));
    request.once("error", reject);
    request.end(body);
  });
}

for (const method of ["GET", "HEAD"]) {
  for (const framing of ["length", "chunked", "empty-chunked"]) {
    test(`real HTTP ${method} rejects ${framing} body framing before routing`, async () => {
      const server = createGatewayServer({
        fetchImpl: async () => { throw new Error("body-bearing read must not reach Backend"); }
      });
      await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
      try {
        const port = (server.address() as AddressInfo).port;
        const headers = framing === "length"
          ? { "content-length": "1" }
          : { "transfer-encoding": "chunked" };
        for (const pathname of ["/internal/health", "/api/reports/mine"]) {
          const response = await rawBodyRequest(
            port, method, pathname, headers, framing === "empty-chunked" ? "" : "x"
          );
          assert.equal(response.status, 400);
          assert.equal(response.cacheControl, "no-store");
          if (method === "GET") {
            assert.deepEqual(JSON.parse(response.body), { code: "gateway_invalid_request" });
          } else {
            assert.equal(response.body, "");
          }
        }
      } finally {
        await new Promise<void>((resolve, reject) => {
          server.close(error => error ? reject(error) : resolve());
        });
      }
    });
  }
}

test("real HTTP bodyless GET and HEAD keep their existing routing contract", async () => {
  const server = createGatewayServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const port = (server.address() as AddressInfo).port;
    for (const method of ["GET", "HEAD"]) {
      for (const headers of [{}, { "content-length": "0" }, { "content-type": "application/json" }]) {
        const response = await rawBodyRequest(port, method, "/internal/health", headers, "");
        assert.equal(response.status, method === "GET" ? 200 : 404);
        assert.equal(response.cacheControl, "no-store");
      }
    }
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve());
    });
  }
});

test("S19-54 real HTTP route matrix follows OpenAPI methods with concealed and query variants", async () => {
  const contract = JSON.parse(await readFile(path.join(process.cwd(), "openapi.json"), "utf8")) as {
    paths: Record<string, Record<string, unknown>>;
  };
  const id = "3f2b8c1e-4d5a-4b6c-8d7e-9f0a1b2c3d4e";
  const sample = (template: string): string => template
    .replace("/privacy/account-deletions/{request_id}",
      "/privacy/account-deletions/probe_request_000001")
    .replaceAll("{session_id}", id)
    .replaceAll("{report_id}", id)
    .replaceAll("{collection_id}", id)
    .replaceAll("{object_id}", id)
    .replaceAll("{index}", "2047")
    .replaceAll("{request_id}", id);
  const concealed = new Set([
    "/api/reports/v2/{report_id}/status", "/api/reports/mine",
    "/api/reports/mine/requests/history", "/api/reports/mine/deletions/{request_id}",
    "/api/reports/mine/{report_id}", "/api/reports/mine/{report_id}/content",
    "/api/reports/mine/{report_id}/corrections",
    "/api/reports/mine/{report_id}/requests",
    "/api/reports/mine/{report_id}/requests/{request_id}",
    "/privacy/account-deletions", "/privacy/account-deletions/{request_id}/status",
    "/privacy/account-deletions/{request_id}/device-evidence"
  ]);
  const upstreamPaths: string[] = [];
  const server = createGatewayServer({
    fetchImpl: async (input) => {
      const pathname = new URL(String(input)).pathname;
      upstreamPaths.push(pathname);
      if (pathname === "/account-signup-policy") {
        return Response.json({ schema_version: "walksafe.account-signup-policy.v1",
          documents: Object.fromEntries(["terms_of_service", "privacy_notice", "location_terms", "raw_original", "automatic_reporting", "training_reuse"].map((key, index) => [key, {
            version: "1.2.0", required: index < 3, equivalent_versions: ["1.1.1"], prior_versions: []
          }])) });
      }
      if (pathname === "/navigation/walking") {
        return Response.json({
          schema_version: "walksafe.walking_route.v1", provider: "tmap_pedestrian",
          priority: "STAIR_AVOID", summary: { distance_m: 12, duration_s: 10 },
          polyline: [
            { latitude: 37.5, longitude: 127.0 },
            { latitude: 37.6, longitude: 127.1 }
          ],
          steps: [], guide_points: []
        });
      }
      throw new Error("route matrix must not reach an unexpected Backend path");
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const port = (server.address() as AddressInfo).port;
    for (const [template, operations] of Object.entries(contract.paths)) {
      const declared = new Set(Object.keys(operations).map(method => method.toUpperCase()));
      for (const method of ["GET", "HEAD", "POST", "PUT", "DELETE", "PATCH"]) {
        const target = sample(template) +
          (template === "/privacy/rights" && method === "PUT"
            ? "?control=integrated-consent" : "");
        const response = await rawBodyRequest(port, method, target,
          { "content-length": "0" }, "");
        if (concealed.has(template) && declared.has(method)) {
          assert.equal(response.status, 404, `${template} ${method} keeps auth/capability concealment`);
        } else if (declared.has(method) ||
            (template === "/privacy/rights" && method === "HEAD")) {
          assert.notEqual(response.status, 405, `${template} ${method} is routed`);
        } else {
          assert.equal(response.status, 405, `${template} ${method} is not declared`);
        }
        assert.equal(response.cacheControl, "no-store", `${template} ${method}`);
      }
    }
    const rawChunk = `/api/raw-collections/${id}/objects/${id}/chunks/`;
    assert.equal((await rawBodyRequest(port, "PUT", `${rawChunk}2047`, {}, "")).status, 422);
    assert.equal((await rawBodyRequest(port, "PUT", `${rawChunk}2048`, {}, "")).status, 404);
    assert.equal((await rawBodyRequest(port, "PUT", `${rawChunk}%32`, {}, "")).status, 404);
    assert.equal((await rawBodyRequest(port, "POST", "/privacy/rights?control=account-deletion", {}, "")).status, 410);
    assert.equal((await rawBodyRequest(port, "PUT", "/privacy/rights", {}, "")).status, 405);
    assert.equal((await rawBodyRequest(port, "POST", "/privacy/rights?control=integrated-consent-bootstrap", {}, "")).status, 401);
    assert.deepEqual(upstreamPaths, ["/account-signup-policy"], "anonymous matrix reaches only public policy metadata");

    const origin = `http://127.0.0.1:${port}`;
    const login = await fetch(`${origin}/api/field-session`, {
      method: "POST", headers: { "content-type": "application/json" },
      body: JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN })
    });
    assert.equal(login.status, 200);
    const cookie = (login.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
    assert.match(cookie, /^walksafe_field_session=/);
    const bootstrap = await rawBodyRequest(port, "POST",
      "/privacy/rights?control=integrated-consent-bootstrap", { cookie }, "");
    assert.equal(bootstrap.status, 400, "bootstrap validates method after field authentication");
    assert.equal(JSON.parse(bootstrap.body).detail.code,
      "integrated_consent_bootstrap_query_invalid");
    assert.equal(JSON.parse(bootstrap.body).detail.required_policy_version,
      "FP-013-1.2.0");
    const integrated = await rawBodyRequest(port, "POST",
      "/privacy/rights?control=integrated-consent", { cookie }, "");
    assert.equal(integrated.status, 405);
    assert.equal(integrated.allow, "GET, PUT");
    assert.equal((await rawBodyRequest(port, "GET", "/api/reports/mine?unexpected=1",
      { cookie }, "")).status, 400);
    assert.equal((await rawBodyRequest(port, "GET", "/api/field-walk?unexpected=1",
      { cookie }, "")).status, 400);
    assert.equal((await rawBodyRequest(port, "GET", "/api/navigation/destinations/search?unexpected=1",
      { cookie }, "")).status, 400);
    const routeBody = JSON.stringify({
      origin: { latitude: 37.5, longitude: 127.0 },
      destination: { latitude: 37.6, longitude: 127.1 }
    });
    const walking = await rawBodyRequest(port, "POST", "/api/navigation/walking", {
      cookie, "content-type": "application/json",
      "content-length": String(Buffer.byteLength(routeBody))
    }, routeBody);
    assert.equal(walking.status, 200);
    const walkingWithIgnoredQuery = await rawBodyRequest(port, "POST",
      "/api/navigation/walking?legacy_client_query=1", {
        cookie, "content-type": "application/json",
        "content-length": String(Buffer.byteLength(routeBody))
      }, routeBody);
    assert.equal(walkingWithIgnoredQuery.status, 200);
    assert.deepEqual(upstreamPaths, ["/account-signup-policy", "/navigation/walking", "/navigation/walking"]);
  } finally {
    await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
});

test("real HTTP adapter separates malformed requests from handler failures", async () => {
  let handlerFailure: Error = new TypeError("private handler detail");
  const server = createGatewayServer({
    get fetchImpl(): never {
      throw handlerFailure;
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const originalConsoleError = console.error;
  const diagnostics: unknown[][] = [];
  console.error = (...args: unknown[]) => { diagnostics.push(args); };
  try {
    const port = (server.address() as AddressInfo).port;
    for (const failure of [new TypeError("private handler detail"), new Error("private handler detail")]) {
      handlerFailure = failure;
      const response = await rawBodyRequest(port, "GET", "/api/field-session", {}, "");
      assert.equal(response.status, 500);
      assert.equal(response.cacheControl, "no-store");
      assert.deepEqual(JSON.parse(response.body), { code: "gateway_internal_error" });
      assert.doesNotMatch(response.body, /private handler detail/);
    }
    assert.deepEqual([...diagnostics], [
      ["gateway_handler_internal_error"],
      ["gateway_handler_internal_error"]
    ]);

    console.error = () => { throw new Error("diagnostic sink failure"); };
    handlerFailure = new TypeError("private handler detail");
    const failedLog = await rawBodyRequest(port, "GET", "/api/field-session", {}, "");
    assert.equal(failedLog.status, 500);
    assert.equal(failedLog.cacheControl, "no-store");
    assert.deepEqual(JSON.parse(failedLog.body), { code: "gateway_internal_error" });
    assert.doesNotMatch(failedLog.body, /private handler detail|diagnostic sink failure/);

    diagnostics.length = 0;
    console.error = (...args: unknown[]) => { diagnostics.push(args); };

    const invalidTarget = await rawBodyRequest(port, "GET", "//invalid-target", {}, "");
    assert.equal(invalidTarget.status, 400);
    assert.equal(invalidTarget.cacheControl, "no-store");
    assert.deepEqual(JSON.parse(invalidTarget.body), { code: "gateway_invalid_request" });
    assert.deepEqual(diagnostics, []);
  } finally {
    console.error = originalConsoleError;
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve());
    });
  }
});

test("real HTTP input validation keeps its client-error classification", async (t) => {
  let backendCalls = 0;
  const server = createGatewayServer({
    fetchImpl: async () => {
      backendCalls += 1;
      throw new Error("invalid input must not reach Backend");
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const originalConsoleError = console.error;
  const diagnostics: unknown[][] = [];
  console.error = (...args: unknown[]) => { diagnostics.push(args); };
  try {
    const port = (server.address() as AddressInfo).port;
    const cases = [
      { name: "malformed JSON", body: '{"token":"private-request-detail",',
        status: 400, code: "invalid_json_body" },
      { name: "duplicate JSON key", body: '{"token":"private-request-detail","token":"duplicate"}',
        status: 400, code: "invalid_json_body" },
      { name: "null JSON payload", body: "null",
        status: 400, code: "field_session_request_invalid" },
      { name: "array JSON payload", body: '["private-request-detail"]',
        status: 400, code: "field_session_request_invalid" },
      { name: "invalid typed grant field", body: '{"grant_type":{"token":"private-request-detail"}}',
        status: 400, code: "field_session_request_invalid" },
      { name: "unsupported content type", body: "private-request-detail", contentType: "text/plain",
        status: 415, code: "json_content_type_required" },
      { name: "oversized body", body: "private-request-detail".padEnd(4097, "x"),
        status: 413, code: "request_body_too_large" }
    ];
    for (const fixture of cases) {
      await t.test(fixture.name, async () => {
        const response = await rawBodyRequest(port, "POST", "/api/field-session", {
          "content-type": fixture.contentType ?? "application/json",
          "content-length": String(Buffer.byteLength(fixture.body))
        }, fixture.body);
        assert.equal(response.status, fixture.status);
        assert.equal(response.cacheControl, "no-store");
        assert.deepEqual(JSON.parse(response.body), { code: fixture.code });
        assert.doesNotMatch(response.body, /private-request-detail|TypeError|SyntaxError|stack/);
      });
    }
    const unauthorized = await rawBodyRequest(port, "POST", "/api/navigation/walking", {
      "content-type": "application/json", "content-length": "2"
    }, "{}");
    assert.equal(unauthorized.status, 401);
    assert.equal(unauthorized.cacheControl, "no-store");
    assert.equal(JSON.parse(unauthorized.body).code, "gateway_auth_required");
    const concealed = await rawBodyRequest(port, "GET", "/api/reports/mine", {}, "");
    assert.equal(concealed.status, 404);
    assert.equal(concealed.cacheControl, "no-store");
    assert.equal(backendCalls, 0);
    assert.deepEqual(diagnostics, []);
    const health = await rawBodyRequest(port, "GET", "/internal/health", {}, "");
    assert.equal(health.status, 200);
    assert.deepEqual(JSON.parse(health.body), { status: "ok" });
  } finally {
    console.error = originalConsoleError;
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve());
    });
  }
});

test("real HTTP adapter preserves a typed storage failure as 503", async () => {
  const server = createGatewayServer({
    get fetchImpl(): never {
      throw new FieldLongSessionStorageUnavailableError();
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const originalConsoleError = console.error;
  const diagnostics: unknown[][] = [];
  console.error = (...args: unknown[]) => { diagnostics.push(args); };
  try {
    const port = (server.address() as AddressInfo).port;
    const response = await fetch(`http://127.0.0.1:${port}/api/field-session`);
    assert.equal(response.status, 503);
    assert.equal(response.headers.get("cache-control"), "no-store");
    assert.equal(response.headers.get("retry-after"), "1");
    assert.deepEqual(await response.json(), {
      code: "field_session_storage_unavailable",
      message: "현장 세션 상태를 안전하게 저장할 수 없습니다.",
      // S19-11: a read failure committed nothing, so the credential stays valid.
      retryable: true
    });
    assert.deepEqual(diagnostics, []);
  } finally {
    console.error = originalConsoleError;
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve());
    });
  }
});

test("raw HTTP backslash target is rejected before handler routing", async () => {
  let handlerCalls = 0;
  const server = createGatewayServer({
    get fetchImpl(): never {
      handlerCalls += 1;
      throw new Error("handler must not run");
    }
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const port = (server.address() as AddressInfo).port;
    const sendRawTarget = (target: string): Promise<string> => new Promise((resolve, reject) => {
      const socket = connect(port, "127.0.0.1");
      let output = "";
      socket.setTimeout(2_000, () => socket.destroy(new Error("raw HTTP fixture timed out")));
      socket.once("connect", () => socket.write(
        `GET ${target} HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n`
      ));
      socket.on("data", chunk => { output += chunk.toString("utf8"); });
      socket.once("end", () => resolve(output));
      socket.once("error", reject);
    });

    const invalid = await sendRawTarget("/\\outside.example/api/field-session");
    assert.match(invalid, /^HTTP\/1\.1 400 /);
    assert.match(invalid, /cache-control: no-store/i);
    assert.match(invalid, /"code":"gateway_invalid_request"/);
    assert.equal(handlerCalls, 0);

    const encoded = await sendRawTarget("/%5Coutside.example/api/field-session");
    assert.match(encoded, /^HTTP\/1\.1 404 /);
    assert.match(encoded, /"code":"gateway_route_not_found"/);
    assert.equal(handlerCalls, 0);
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve());
    });
  }
});

test("real HTTP adapter treats unexpected conversion TypeError as internal error", async () => {
  const server = createGatewayServer();
  server.prependListener("request", (request: IncomingMessage) => {
    Object.defineProperty(request, "url", {
      get() { throw new TypeError("private conversion detail"); }
    });
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const originalConsoleError = console.error;
  const diagnostics: unknown[][] = [];
  console.error = (...args: unknown[]) => { diagnostics.push(args); };
  try {
    const port = (server.address() as AddressInfo).port;
    const response = await rawBodyRequest(port, "GET", "/internal/health", {}, "");
    assert.equal(response.status, 500);
    assert.equal(response.cacheControl, "no-store");
    assert.deepEqual(JSON.parse(response.body), { code: "gateway_internal_error" });
    assert.doesNotMatch(response.body, /private conversion detail/);
    assert.deepEqual(diagnostics, [["gateway_conversion_internal_error"]]);
  } finally {
    console.error = originalConsoleError;
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve());
    });
  }
});

test("real HTTP response stream failures use a fixed 500 or close a partial response", async () => {
  const server = createGatewayServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const originalResponseJson = Response.json;
  const originalConsoleError = console.error;
  const diagnostics: unknown[][] = [];
  let mode: "first" | "partial" = "first";
  Response.json = (data, init) => {
    if (typeof data === "object" && data !== null && "status" in data && data.status === "ok") {
      let pulls = 0;
      const body = new ReadableStream<Uint8Array>({
        async pull(controller) {
          if (mode === "partial" && pulls++ === 0) {
            controller.enqueue(Buffer.from("partial-before-error"));
            return;
          }
          if (mode === "partial") await new Promise<void>(resolve => setTimeout(resolve, 20));
          controller.error(new Error("private-stream-marker"));
        }
      }, { highWaterMark: 0 });
      return new Response(body, {
        status: 200,
        headers: mode === "first"
          ? { "cache-control": "no-store", "x-private-response": "private-stream-marker" }
          : { "cache-control": "no-store" }
      });
    }
    return originalResponseJson(data, init);
  };
  console.error = (...args: unknown[]) => { diagnostics.push(args); };
  try {
    const port = (server.address() as AddressInfo).port;
    const origin = `http://127.0.0.1:${port}`;
    const first = await fetch(`${origin}/internal/health`);
    assert.equal(first.status, 500);
    assert.equal(first.headers.get("x-private-response"), null);
    assert.equal(first.headers.get("cache-control"), "no-store");
    const firstBody = await first.text();
    assert.deepEqual(JSON.parse(firstBody), { code: "gateway_internal_error" });
    assert.doesNotMatch(firstBody, /private-stream-marker/);

    mode = "partial";
    const partial = await new Promise<string>((resolve, reject) => {
      const socket = connect(port, "127.0.0.1");
      let output = "";
      let failure: Error | null = null;
      socket.setTimeout(2_000, () => {
        failure = new Error("partial response timed out");
        socket.destroy();
      });
      socket.once("connect", () => socket.write(
        "GET /internal/health HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n"
      ));
      socket.on("data", chunk => { output += chunk.toString("utf8"); });
      socket.once("error", (error: Error) => { if (output === "") failure = error; });
      socket.once("close", () => failure ? reject(failure) : resolve(output));
    });
    assert.match(partial, /^HTTP\/1\.1 200 /);
    assert.match(partial, /partial-before-error/);
    assert.doesNotMatch(partial, /gateway_internal_error|HTTP\/1\.1 500/);
    assert.deepEqual([...diagnostics], [
      ["gateway_response_write_internal_error"],
      ["gateway_response_write_internal_error"]
    ]);

    mode = "first";
    console.error = () => { throw new Error("diagnostic sink failure"); };
    const failedLog = await fetch(`${origin}/internal/health`);
    assert.equal(failedLog.status, 500);
    assert.equal(failedLog.headers.get("x-private-response"), null);
    assert.deepEqual(await failedLog.json(), { code: "gateway_internal_error" });
  } finally {
    Response.json = originalResponseJson;
    console.error = originalConsoleError;
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve());
    });
  }
});

test("response backpressure settles and cancels the body when the client closes", async () => {
  let cancelledReason: unknown;
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.enqueue(new Uint8Array([1, 2, 3]));
    },
    cancel(reason) {
      cancelledReason = reason;
    }
  });
  class BackpressuredResponse extends EventEmitter {
    statusCode = 0;
    statusMessage = "";
    destroyed = false;
    writableEnded = false;
    setHeader(): void {}
    write(): boolean { return false; }
    end(): void { this.writableEnded = true; }
  }
  const output = new BackpressuredResponse();
  const pending = writeWebResponse(
    new Response(body),
    output as unknown as ServerResponse
  );
  await new Promise<void>((resolve) => setImmediate(resolve));
  assert.equal(output.listenerCount("drain"), 1);
  output.destroyed = true;
  output.emit("close");
  await pending;
  assert.equal(cancelledReason, "client response socket closed");
  assert.equal(output.listenerCount("drain"), 0);
  assert.equal(output.listenerCount("close"), 0);
  assert.equal(output.listenerCount("error"), 0);
});

test("internal readiness is loopback-only, fail-closed, and separate from liveness", async () => {
  let ready = true;
  const server = createGatewayServer({ readinessProbe: () => ready });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    const success = await fetch(`${origin}/internal/ready`);
    assert.equal(success.status, 200);
    assert.deepEqual(await success.json(), { status: "ready" });
    assert.equal(success.headers.get("cache-control"), "no-store");

    ready = false;
    const failure = await fetch(`${origin}/internal/ready`);
    assert.equal(failure.status, 503);
    assert.deepEqual(await failure.json(), { status: "not_ready" });
    assert.equal(failure.headers.get("cache-control"), "no-store");
    const live = await fetch(`${origin}/internal/health`);
    assert.equal(live.status, 200);
    assert.deepEqual(await live.json(), { status: "ok" });
    assert.equal((await fetch(`${origin}/internal/ready?detail=1`)).status, 404);
    assert.equal((await fetch(`${origin}/internal/ready`, { method: "POST" })).status, 404);
  } finally {
    await new Promise<void>((resolve, reject) => {
      server.close(error => error ? reject(error) : resolve());
    });
  }
});

test("bounded managed storage probe rejects missing roots, keyring, and full ledger", async () => {
  const ledgerPath = path.join(stateDirectory, "readiness-ledger.json");
  assert.equal(await probeGatewayManagedStorageReady(ledgerPath), true);

  const blocker = path.join(stateDirectory, "not-a-directory");
  await writeFile(blocker, "blocker");
  assert.equal(await probeGatewayManagedStorageReady(ledgerPath, {
    ...process.env,
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: blocker
  }), false);
  assert.equal(await probeGatewayManagedStorageReady(
    path.join(blocker, "ledger.json")
  ), false);
  assert.equal(await probeGatewayManagedStorageReady(ledgerPath, {
    ...process.env,
    WALKSAFE_GATEWAY_STATE_KEYRING_FILE: path.join(stateDirectory, "missing-keyring.json")
  }), false);

  const ledger = await open(ledgerPath, "wx", 0o600);
  try {
    await ledger.truncate(MAX_STORED_LEDGER_BYTES);
  } finally {
    await ledger.close();
  }
  try {
    assert.equal(await probeGatewayManagedStorageReady(ledgerPath), false);
  } finally {
    await rm(ledgerPath);
  }
});

test("S19-57 readiness reports a saturated shared ledger as not ready from metadata only", async () => {
  const diagnostics: string[] = [];
  const originalStderrWrite = process.stderr.write;
  process.stderr.write = ((chunk: string | Uint8Array, ...rest: unknown[]) => {
    const text = String(chunk);
    if (text.startsWith("walksafe gateway readiness:")) {
      diagnostics.push(text);
      return true;
    }
    return (originalStderrWrite as (...values: unknown[]) => boolean).call(process.stderr, chunk, ...rest);
  }) as typeof process.stderr.write;
  try {
    await saturatedLedgerReadinessScenario();
  } finally {
    process.stderr.write = originalStderrWrite;
  }
  // The reason is logged by store name once per change, not on every probe.
  assert.deepEqual(diagnostics, [
    "walksafe gateway readiness: saturated stores field-walk-ledger\n",
    "walksafe gateway readiness: saturated stores privacy-deletion-v2\n",
    "walksafe gateway readiness: store saturation cleared\n"
  ]);
});

async function saturatedLedgerReadinessScenario(): Promise<void> {
  const ledgerPath = path.join(stateDirectory, "s19-57-ledger.json");
  assert.deepEqual(await saturatedGatewayStores(ledgerPath), []);
  assert.equal(await probeGatewayManagedStorageReady(ledgerPath), true);
  const sparse = async (filePath: string, size: number): Promise<void> => {
    const handle = await open(filePath, "w", 0o600);
    try {
      await handle.truncate(size);
    } finally {
      await handle.close();
    }
  };
  // A non-zero walk shard (formerly unchecked) near the normal ledger ceiling.
  const shard = fieldWalkLedgerShardPaths(ledgerPath)[5]!;
  await sparse(shard, Math.ceil(0.9 * maxGatewayStateEnvelopeBytes(DEFAULT_MAX_LEDGER_BYTES)));
  try {
    assert.deepEqual(await saturatedGatewayStores(ledgerPath), ["field-walk-ledger"]);
    assert.equal(await probeGatewayManagedStorageReady(ledgerPath), false);
  } finally {
    await rm(shard);
  }
  // The shared account-deletion ledger (formerly unchecked) near its 4 MiB ceiling.
  const deletionDirectory = path.join(stateDirectory, "privacy-deletion-v2");
  await mkdir(deletionDirectory, { recursive: true, mode: 0o700 });
  const deletionLedger = path.join(deletionDirectory, "ledger.json");
  await sparse(deletionLedger, Math.ceil(0.9 * maxGatewayStateEnvelopeBytes(PRIVACY_DELETION_V2_MAX_BYTES)));
  try {
    assert.deepEqual(await saturatedGatewayStores(ledgerPath), ["privacy-deletion-v2"]);
    assert.equal(await probeGatewayManagedStorageReady(ledgerPath), false);
    await sparse(deletionLedger, Math.floor(0.5 * maxGatewayStateEnvelopeBytes(PRIVACY_DELETION_V2_MAX_BYTES)));
    assert.deepEqual(await saturatedGatewayStores(ledgerPath), []);
    assert.equal(await probeGatewayManagedStorageReady(ledgerPath), true);
    assert.equal(await probeGatewayManagedStorageReady(ledgerPath), true);
  } finally {
    await rm(deletionLedger, { force: true });
  }
}

test("readiness checks authenticated Backend and enabled Voice with deadlines", async () => {
  const keys = [
    "WALKSAFE_VOICE_ENABLED",
    "WALKSAFE_VOICE_API_BASE_URL",
    "WALKSAFE_VOICE_SERVICE_TOKEN"
  ] as const;
  const previous = new Map(keys.map(key => [key, process.env[key]]));
  Object.assign(process.env, {
    WALKSAFE_VOICE_ENABLED: "true",
    WALKSAFE_VOICE_API_BASE_URL: "http://127.0.0.1:9001",
    WALKSAFE_VOICE_SERVICE_TOKEN: "dedicated-voice-readiness-token-1234567890"
  });
  let failedService = "";
  try {
    const probe = createGatewayReadinessProbe(
      async () => true,
      () => true,
      async (input, init) => {
        const url = String(input);
        assert.equal(init?.method, "GET");
        assert.equal(init?.cache, "no-store");
        assert.equal(init?.redirect, "error");
        const headers = new Headers(init?.headers);
        if (url === "http://127.0.0.1:8000/ready") {
          assert.equal(headers.get("x-walksafe-field-test-token"), process.env.WALKSAFE_FIELD_TEST_TOKEN);
        } else {
          assert.equal(url, "http://127.0.0.1:9001/ready");
          assert.equal(headers.get("x-walksafe-voice-service-token"), process.env.WALKSAFE_VOICE_SERVICE_TOKEN);
        }
        return url.includes(failedService) && failedService !== ""
          ? new Response(null, { status: 503 })
          : Response.json({ status: "ready" });
      },
      20
    );
    assert.equal(await probe(), true);
    failedService = ":8000";
    assert.equal(await probe(), false);
    failedService = ":9001";
    assert.equal(await probe(), false);

    const unavailable = createGatewayReadinessProbe(
      async () => true,
      () => true,
      async () => new Promise<Response>(() => {}),
      10
    );
    assert.equal(await unavailable(), false);
    assert.equal(await createGatewayReadinessProbe(async () => false, () => true)(), false);
    assert.equal(await createGatewayReadinessProbe(async () => true, () => false)(), false);
  } finally {
    for (const [key, value] of previous) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
  }
});

test("readiness response deadline aborts in-flight Backend and Voice probes", async () => {
  const keys = [
    "WALKSAFE_VOICE_ENABLED",
    "WALKSAFE_VOICE_API_BASE_URL",
    "WALKSAFE_VOICE_SERVICE_TOKEN"
  ] as const;
  const previous = new Map(keys.map(key => [key, process.env[key]]));
  Object.assign(process.env, {
    WALKSAFE_VOICE_ENABLED: "true",
    WALKSAFE_VOICE_API_BASE_URL: "http://127.0.0.1:9001",
    WALKSAFE_VOICE_SERVICE_TOKEN: "dedicated-voice-readiness-token-1234567890"
  });
  let stall = false;
  let healthyFetches = 0;
  const signals: AbortSignal[] = [];
  const aborted = new Set<AbortSignal>();
  const probe = createGatewayReadinessProbe(
    async () => {
      if (stall) await new Promise<void>((resolve) => setTimeout(resolve, 3_000));
      return true;
    },
    () => true,
    async (_input, init) => {
      if (!stall) {
        healthyFetches += 1;
        return Response.json({ status: "ready" });
      }
      const signal = init?.signal;
      assert.ok(signal);
      signals.push(signal);
      return new Promise<Response>((_resolve, reject) => {
        const onAbort = (): void => {
          aborted.add(signal);
          reject(new Error("probe aborted"));
        };
        if (signal.aborted) onAbort();
        else signal.addEventListener("abort", onAbort, { once: true });
      });
    },
    3_000
  );
  const server = createGatewayServer({ readinessProbe: probe });
  try {
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
    const healthy = await fetch(`${origin}/internal/ready`);
    assert.equal(healthy.status, 200);
    assert.deepEqual(await healthy.json(), { status: "ready" });
    assert.equal(healthyFetches, 2);

    stall = true;
    const startedAt = Date.now();
    const timedOut = await fetch(`${origin}/internal/ready`);
    const elapsed = Date.now() - startedAt;
    assert.equal(timedOut.status, 503);
    assert.deepEqual(await timedOut.json(), { status: "not_ready" });
    assert.equal(timedOut.headers.get("cache-control"), "no-store");
    assert.ok(elapsed < 5_900, `readiness response took ${elapsed}ms`);
    assert.equal(signals.length, 2);
    assert.equal(new Set(signals).size, 2);
    assert.equal(aborted.size, 2);
    assert.ok(signals.every((signal) => signal.aborted));

    const live = await fetch(`${origin}/internal/health`);
    assert.equal(live.status, 200);
    assert.deepEqual(await live.json(), { status: "ok" });
  } finally {
    for (const [key, value] of previous) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    await new Promise<void>((resolve, reject) => {
      server.close((error) => error ? reject(error) : resolve());
    });
  }
});

test("outbox readiness rejects failed, paused, and stale drains", async () => {
  const tick = (): Promise<void> => new Promise(resolve => setImmediate(resolve));
  const healthy = new AccountDeletionOutboxWorker(
    async () => ({ attempted: 0, succeeded: 0 }),
    () => {}
  );
  healthy.start();
  try {
    await tick();
    assert.equal(healthy.isReady(), true);
    assert.equal(healthy.isReady(Date.now() + 16_000), false);
  } finally {
    await healthy.stop();
  }

  const failed = new AccountDeletionOutboxWorker(
    async () => ({ attempted: 1, succeeded: 0 }),
    () => {}
  );
  failed.start();
  try {
    await tick();
    assert.equal(failed.isReady(), false);
  } finally {
    await failed.stop();
  }

  let paused = true;
  const pausedWorker = new AccountDeletionOutboxWorker(
    async () => ({ attempted: 0, succeeded: 0 }),
    () => {},
    () => paused
  );
  pausedWorker.start();
  try {
    await tick();
    assert.equal(pausedWorker.isReady(), false);
    paused = false;
    assert.equal(pausedWorker.isReady(), true);
  } finally {
    await pausedWorker.stop();
  }
});
