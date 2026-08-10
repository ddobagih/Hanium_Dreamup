import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { mkdtemp, rm } from "node:fs/promises";
import { request as httpRequest, type ServerResponse } from "node:http";
import type { AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import { createGatewayServer, writeWebResponse } from "../src/node-adapter.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const ACTOR_ID = "direct-android";
const ACCOUNT_TOKEN = "direct-android-account-token-12345678901234567890";
let stateDirectory = "";

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-android-gateway-http-test-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_FIELD_TEST_TOKEN: "direct-android-internal-token-12345678901234567890",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([{ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }]),
    WALKSAFE_GATEWAY_SESSION_SECRET: "direct-android-session-secret-123456789012345678901234",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "x-real-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
  delete process.env.WALKSAFE_ADMIN_TOKEN;
  delete process.env.WALKSAFE_ADMIN_ACCOUNTS_JSON;
});

after(async () => {
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
