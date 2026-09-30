import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFile, mkdtemp, rm } from "node:fs/promises";
import { request as httpRequest, type ClientRequest } from "node:http";
import { type AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

import { setFieldLongSessionAdmissionHoldForTests } from "../src/field-long-session.js";
import { createGatewayServer } from "../src/node-adapter.js";
import {
  configureTestStateEncryption,
  decryptTestStateFile
} from "./state-encryption-fixture.js";

const ACTOR_ID = "http-disconnect-operator";
const ACCOUNT_TOKEN = "http-disconnect-token-1234567890123456";

type HttpResult = { status: number; body: string } | { closed: true };
type Client = { request: ClientRequest; sent: Promise<void>; result: Promise<HttpResult> };

function login(port: number, deviceId: string): Client {
  const body = JSON.stringify({ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN, device_id: deviceId });
  let resolveResult!: (result: HttpResult) => void;
  let resolveSent!: () => void;
  const result = new Promise<HttpResult>((resolve) => { resolveResult = resolve; });
  const sent = new Promise<void>((resolve) => { resolveSent = resolve; });
  const request = httpRequest({
    host: "127.0.0.1",
    port,
    path: "/api/field-session",
    method: "POST",
    agent: false,
    headers: {
      "content-type": "application/json",
      "content-length": Buffer.byteLength(body)
    }
  }, (response) => {
    const chunks: Buffer[] = [];
    response.on("data", (chunk: Buffer) => chunks.push(chunk));
    response.on("end", () => resolveResult({
      status: response.statusCode ?? 0,
      body: Buffer.concat(chunks).toString("utf8")
    }));
  });
  request.once("finish", resolveSent);
  request.once("error", () => resolveResult({ closed: true }));
  request.end(body);
  return { request, sent, result };
}

async function closeSocket(client: Client): Promise<void> {
  const socket = client.request.socket ?? await new Promise<NonNullable<ClientRequest["socket"]>>(
    (resolve) => client.request.once("socket", resolve)
  );
  socket.destroy();
  assert.deepEqual(await client.result, { closed: true });
}

test("account admission frees a queued slot when the real HTTP client socket closes", { timeout: 5_000 }, async () => {
  const stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-http-disconnect-"));
  const keyringPath = `${stateDirectory}-keyring.json`;
  const environment = {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:1",
    WALKSAFE_FIELD_TEST_TOKEN: "http-disconnect-internal-token-1234567890123456",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([{ actor_id: ACTOR_ID, token: ACCOUNT_TOKEN }]),
    WALKSAFE_GATEWAY_SESSION_SECRET: "http-disconnect-session-secret-12345678901234567890",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "60",
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "300",
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "3600"
  };
  const previous = new Map(Object.keys(environment).map((key) => [key, process.env[key]]));
  const previousKeyring = process.env.WALKSAFE_GATEWAY_STATE_KEYRING_FILE;
  Object.assign(process.env, environment);
  let releaseHold!: () => void;
  const hold = new Promise<void>((resolve) => { releaseHold = resolve; });
  const server = createGatewayServer();
  const clients: Client[] = [];

  try {
    await configureTestStateEncryption(keyringPath);
    setFieldLongSessionAdmissionHoldForTests(hold);
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const port = (server.address() as AddressInfo).port;
    const first = login(port, "first-device");
    clients.push(first);
    await first.sent;

    const waiting = Array.from({ length: 32 }, (_, index) => login(port, `waiting-${index}`));
    clients.push(...waiting);
    await Promise.all(waiting.map((client) => client.sent));
    const overflow = login(port, "overflow-device");
    clients.push(overflow);
    const full = await overflow.result;
    assert.ok("status" in full);
    assert.equal(full.status, 503);
    assert.equal(JSON.parse(full.body).code, "field_session_storage_busy");

    await closeSocket(waiting[0]!);
    const replacement = login(port, "replacement-device");
    clients.push(replacement);
    await replacement.sent;
    const early = await Promise.race([
      replacement.result.then(() => "responded"),
      new Promise<"pending">((resolve) => setTimeout(() => resolve("pending"), 50))
    ]);
    assert.equal(early, "pending", "a closed HTTP socket must free one queued slot");

    await Promise.all(waiting.slice(1).map(closeSocket));
    releaseHold();
    const [firstResult, replacementResult] = await Promise.all([first.result, replacement.result]);
    assert.ok("status" in firstResult);
    assert.ok("status" in replacementResult);
    assert.equal(firstResult.status, 200);
    assert.equal(replacementResult.status, 200);

    const recordId = `${createHash("sha256")
      .update(`field-long-session\0${ACTOR_ID}`)
      .digest("hex")}.json`;
    const persisted = decryptTestStateFile<{ families: Array<{ device_id: string }> }>(
      { kind: "field-long-session", recordId },
      await readFile(path.join(stateDirectory, "field-long-sessions", recordId), "utf8"),
      4 * 1024 * 1024
    );
    assert.deepEqual(
      persisted.families.map((family) => family.device_id).sort(),
      ["first-device", "replacement-device"]
    );
  } finally {
    releaseHold();
    setFieldLongSessionAdmissionHoldForTests(null);
    for (const client of clients) client.request.destroy();
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
    await rm(stateDirectory, { recursive: true, force: true });
    await rm(keyringPath, { force: true });
    for (const [key, value] of previous) {
      if (value === undefined) delete process.env[key];
      else process.env[key] = value;
    }
    if (previousKeyring === undefined) delete process.env.WALKSAFE_GATEWAY_STATE_KEYRING_FILE;
    else process.env.WALKSAFE_GATEWAY_STATE_KEYRING_FILE = previousKeyring;
  }
});
