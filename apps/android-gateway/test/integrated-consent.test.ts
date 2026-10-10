import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import { link, lstat, mkdir, mkdtemp, readFile, rename, rm, writeFile } from "node:fs/promises";
import { request as httpRequest, type ClientRequest } from "node:http";
import { syncBuiltinESMExports } from "node:module";
import { type AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import {
  authorizeIntegratedConsentRequest,
  CONSENT_CONTROL_SECRET_HEADER,
  CONSENT_INSTALLATION_HEADER,
  CONSENT_NETWORK_TRANSPORT_HEADER,
  CONSENT_POLICY_HEADER,
  CONSENT_RECEIPT_HEADER,
  CONSENT_REVISION_HEADER,
  INTEGRATED_CONSENT_ITEM_VERSIONS,
  INTEGRATED_CONSENT_MAX_STATE_BYTES,
  INTEGRATED_CONSENT_POLICY_VERSION,
  hasCurrentIntegratedConsentItemVersions,
  handleIntegratedConsentRequest,
  integratedConsentAuditSegmentPaths,
  withIntegratedConsentStateFileLockForMaintenance,
  type IntegratedConsentConfirmation
} from "../src/integrated-consent.js";
import {
  ACCOUNT_GENERATION_HEADER,
  ACTOR_ASSERTION_HEADER,
  ACTOR_ID_HEADER,
  FIELD_TEST_TOKEN_HEADER,
  type GatewayFetch
} from "../src/backend.js";
import { handleGatewayRequest } from "../src/routes.js";
import { setGatewayStateCommitFaultForTests } from "../src/encrypted-json-store.js";
import { createGatewayServer } from "../src/node-adapter.js";
import { clearGatewaySession } from "../src/auth.js";
import {
  configureTestStateEncryption,
  decryptTestStateFile,
  encryptTestStateFile
} from "./state-encryption-fixture.js";
import { assertBackendActorAssertionV4 } from "./actor-assertion-v4-fixture.js";

let stateDirectory = "";
const CONTROL_SECRET = "a".repeat(64);
const PRIMARY_ACTOR = "consent-test-actor-a";
const PRIMARY_TOKEN = "consent-test-token-a-12345678901234567890";
const SECONDARY_ACTOR = "consent-test-actor-b";
const SECONDARY_TOKEN = "consent-test-token-b-12345678901234567890";
const INTERNAL_TOKEN = "consent-backend-token-12345678901234567890";
const PRIMARY_BINDING = `${PRIMARY_ACTOR}\0generation:1`;
const SECONDARY_BINDING = `${SECONDARY_ACTOR}\0generation:1`;
let primaryCookie = "";
let secondaryCookie = "";
const backendEvents = new Map<string, string>();
const expectedReceiptByInstallation = new Map<string, string>();
const expectedReceiptByRequest = new Map<string, string | null>();

function canonicalJson(value: unknown): string {
  if (value === null || typeof value !== "object") return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(canonicalJson).join(",")}]`;
  const payload = value as Record<string, unknown>;
  return `{${Object.keys(payload).sort().map(
    (key) => `${JSON.stringify(key)}:${canonicalJson(payload[key])}`
  ).join(",")}}`;
}

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-consent-test-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    // R19-GB-01: per-file ledger so parallel test files never share the default lock.
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json"),
    WALKSAFE_FIELD_TEST_TOKEN: INTERNAL_TOKEN,
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([
      { actor_id: PRIMARY_ACTOR, token: PRIMARY_TOKEN },
      { actor_id: SECONDARY_ACTOR, token: SECONDARY_TOKEN }
    ]),
    WALKSAFE_GATEWAY_SESSION_SECRET:
      "consent-session-secret-123456789012345678901234567890",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip"
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
  primaryCookie = await login(PRIMARY_ACTOR, PRIMARY_TOKEN, "198.51.100.10");
  secondaryCookie = await login(SECONDARY_ACTOR, SECONDARY_TOKEN, "198.51.100.11");
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
  await assert.rejects(lstat(stateDirectory), { code: "ENOENT" });
  console.log(JSON.stringify({
    probe: "S19-22",
    observation: "suite-fixture-cleanup",
    temporary_directory: stateDirectory,
    removed: true
  }));
});

async function login(actorId: string, token: string, ip: string): Promise<string> {
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": ip
      },
      body: JSON.stringify({ actor_id: actorId, token })
    })
  );
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { session_scope: "general" });
  return (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
}

const consentBackendFetch: GatewayFetch = async (input, init) => {
  assert.equal(String(input), "http://127.0.0.1:8000/privacy/consent-events");
  assert.equal(init?.method, "POST");
  const headers = new Headers(init?.headers);
  assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
  assert.ok([PRIMARY_ACTOR, SECONDARY_ACTOR].includes(
    headers.get(ACTOR_ID_HEADER) ?? ""
  ));
  assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "1");
  assertBackendActorAssertionV4(input, init, {
    actorId: headers.get(ACTOR_ID_HEADER) ?? "", accountGeneration: 1, authEpoch: null
  });
  const rawBody = String(init?.body ?? "");
  const body = JSON.parse(rawBody) as Record<string, unknown>;
  assert.deepEqual(Object.keys(body), [
    "schema_version",
    "installation_id",
    "request_id",
    "client_revision",
    "expected_previous_backend_receipt_sha256",
    "policy_version",
    "item_versions",
    "raw_source_collection",
    "automatic_reporting",
    "mobile_network_transfer",
    "training_reuse"
  ]);
  assert.equal(body.schema_version, "walksafe.privacy-consent-event.v2");
  const requestId = body.request_id as string;
  const prior = backendEvents.get(requestId);
  if (prior !== undefined && prior !== rawBody) {
    return Response.json({
      detail: {
        code: "privacy_consent_request_conflict",
        message: "Request ID was already used."
      }
    }, { status: 409 });
  }
  backendEvents.set(requestId, rawBody);
  return Response.json({
    schema_version: "walksafe.privacy-consent-receipt.v2",
    request_id: requestId,
    client_revision: body.client_revision,
    receipt_sha256: createHash("sha256").update(rawBody).digest("hex"),
    recorded_at: "2026-08-09T12:05:00Z"
  }, { status: prior === undefined ? 201 : 200 });
};

async function save(
  installationId: string,
  requestId: string,
  clientRevision: number,
  selections: Record<string, boolean>,
  cookie = primaryCookie
): Promise<{ status: number; confirmation: IntegratedConsentConfirmation }> {
  const expectedPrevious = expectedReceiptByRequest.has(requestId)
    ? expectedReceiptByRequest.get(requestId)!
    : expectedReceiptByInstallation.get(installationId) ?? null;
  expectedReceiptByRequest.set(requestId, expectedPrevious);
  const response = await handleGatewayRequest(
    consentPutRequest(
      installationId,
      requestId,
      clientRevision,
      selections,
      cookie,
      expectedPrevious
    ),
    { fetchImpl: consentBackendFetch }
  );
  const result = {
    status: response.status,
    confirmation: await response.json() as IntegratedConsentConfirmation
  };
  if (result.status === 200 || result.status === 201) {
    expectedReceiptByInstallation.set(
      installationId,
      result.confirmation.backend_consent_receipt_sha256
    );
  }
  return result;
}

function consentPutRequest(
  installationId: string,
  requestId: string,
  clientRevision: number,
  selections: Record<string, boolean>,
  cookie = primaryCookie,
  expectedPreviousBackendReceiptSha256: string | null = null
): Request {
  return new Request(
    "http://127.0.0.1:8081/privacy/rights?control=integrated-consent",
    {
      method: "PUT",
      headers: {
        cookie,
        "content-type": "application/json",
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
      },
      body: JSON.stringify({
        schema_version: "walksafe.integrated-consent-request.v1",
        installation_id: installationId,
        request_id: requestId,
        policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision: clientRevision,
        expected_previous_backend_receipt_sha256:
          expectedPreviousBackendReceiptSha256,
        selections
      })
    }
  );
}

/** S19-31: Backend bootstrap fixture whose latest subject receipt is `latestReceipt`. */
function backendAuthorityFetch(
  installationId: string,
  latestReceipt: string,
  selections: Record<string, boolean>,
  calls: { count: number } = { count: 0 },
  status: "READY" | "RECONSENT_REQUIRED" = "READY"
): GatewayFetch {
  return async (input, init) => {
    calls.count += 1;
    assert.equal(
      String(input),
      "http://127.0.0.1:8000/privacy/consent-bootstrap" +
        `?installation_id=${installationId}` +
        `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`
    );
    assert.equal(init?.method, "GET");
    const ready = status === "READY";
    return Response.json({
      schema_version: "walksafe.integrated-consent-bootstrap.v1",
      status,
      source: ready ? "CURRENT_CONSENT" : "NONE",
      installation_id: installationId,
      policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
      item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
      client_revision_floor: 1,
      selections: ready ? selections : null,
      source_receipt_sha256: ready ? latestReceipt : null,
      expected_previous_backend_receipt_sha256: latestReceipt
    });
  };
}

function receiptHeaders(confirmation: IntegratedConsentConfirmation): Headers {
  return new Headers({
    [CONSENT_INSTALLATION_HEADER]: confirmation.installation_id,
    [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET,
    [CONSENT_NETWORK_TRANSPORT_HEADER]: "wifi",
    [CONSENT_POLICY_HEADER]: confirmation.policy_version,
    [CONSENT_REVISION_HEADER]: String(confirmation.revision),
    [CONSENT_RECEIPT_HEADER]: confirmation.backend_consent_receipt_sha256
  });
}

test("S19-23 consent unlock failure releases same-installation admission", async () => {
  const selections = {
    raw_source_collection: true,
    automatic_reporting: false,
    mobile_network_transfer: false,
    training_reuse: false
  };
  const normalInstallation = "s19_23_normal_installation";
  assert.equal((await save(
    normalInstallation, "s19_23_normal_consent_first", 1, selections
  )).status, 201);
  assert.equal((await save(
    normalInstallation, "s19_23_normal_consent_next", 2, selections
  )).status, 201);

  const installationId = "s19_23_unlock_failure_installation";
  const lockPath = path.join(
    stateDirectory,
    "integrated-consent",
    `${createHash("sha256").update(`integrated-consent\0${installationId}`).digest("hex")}.json.lock`
  );
  let backendEntered!: () => void;
  let releaseBackend!: () => void;
  const backendStarted = new Promise<void>((resolve) => { backendEntered = resolve; });
  const backendHeld = new Promise<void>((resolve) => { releaseBackend = resolve; });
  let firstReceipt = "";
  const firstRequest = consentPutRequest(
    installationId, "s19_23_unlock_failure_first", 1, selections
  );
  const first = handleIntegratedConsentRequest(firstRequest, {
    accountGeneration: 1,
    fieldActorBindingId: PRIMARY_BINDING,
    signal: firstRequest.signal,
    fetchImpl: async (input, init) => {
      firstReceipt = createHash("sha256").update(String(init?.body)).digest("hex");
      backendEntered();
      await backendHeld;
      return consentBackendFetch(input, init);
    }
  });
  let injected = false;
  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    await backendStarted;
    let nextBackendCalls = 0;
    const nextRequest = consentPutRequest(
      installationId,
      "s19_23_unlock_failure_next",
      2,
      selections,
      primaryCookie,
      firstReceipt
    );
    const next = handleIntegratedConsentRequest(nextRequest, {
      accountGeneration: 1,
      fieldActorBindingId: PRIMARY_BINDING,
      signal: nextRequest.signal,
      fetchImpl: async (input, init) => {
        nextBackendCalls += 1;
        return consentBackendFetch(input, init);
      }
    });
    await new Promise<void>((resolve) => setImmediate(resolve));
    assert.equal(nextBackendCalls, 0, "same-installation work must wait for the first lock");

    // S19-22 kernel lock: the unlock failure is the release integrity check. Replacing the
    // held lock path with another inode makes the first release throw; the queue must still
    // admit the next same-installation request, which locks the new inode.
    const replacementPath = `${lockPath}.replacement`;
    await writeFile(replacementPath, "", { mode: 0o600, flag: "wx" });
    await rename(replacementPath, lockPath);
    injected = true;
    releaseBackend();

    const [failed, admitted] = await Promise.race([
      Promise.all([first, next]),
      new Promise<never>((_, reject) => {
        timeout = setTimeout(() => reject(new Error(
          "same-installation consent request did not settle after unlock failure"
        )), 3_000);
      })
    ]);
    assert.equal(injected, true);
    assert.equal(failed.status, 503);
    assert.equal(
      (await failed.json() as { detail: { code: string } }).detail.code,
      "integrated_consent_store_unavailable"
    );
    assert.equal(admitted.status, 201);
    assert.equal(nextBackendCalls, 1);
  } finally {
    if (timeout) clearTimeout(timeout);
    releaseBackend();
  }
});

test("S19-24 consent admission bounds Backend writes and report recheck", async () => {
  const installationId = "s19_24_consent_admission_installation";
  const selections = {
    raw_source_collection: true,
    automatic_reporting: true,
    mobile_network_transfer: false,
    training_reuse: false
  };
  let entered!: () => void;
  let release!: () => void;
  const started = new Promise<void>((resolve) => { entered = resolve; });
  const held = new Promise<void>((resolve) => { release = resolve; });
  let backendCalls = 0;
  const activeRequest = consentPutRequest(
    installationId, "s19_24_active_consent_request", 1, selections
  );
  const active = handleIntegratedConsentRequest(activeRequest, {
    accountGeneration: 1,
    fieldActorBindingId: PRIMARY_BINDING,
    signal: activeRequest.signal,
    fetchImpl: async (input, init) => {
      backendCalls += 1;
      entered();
      await held;
      return consentBackendFetch(input, init);
    }
  });
  try {
    await started;
    const controllers = Array.from({ length: 32 }, () => new AbortController());
    const queued = controllers.map((controller, index) => {
      const request = new Request(consentPutRequest(
        installationId, `s19_24_queued_consent_${index.toString().padStart(4, "0")}`,
        index + 2, selections
      ), { signal: controller.signal });
      return handleIntegratedConsentRequest(request, {
        accountGeneration: 1,
        fieldActorBindingId: PRIMARY_BINDING,
        signal: request.signal,
        fetchImpl: async () => {
          backendCalls += 1;
          throw new Error("rejected waiter must not reach Backend");
        }
      });
    });
    await new Promise<void>((resolve) => setImmediate(resolve));

    const overflowRequest = consentPutRequest(
      installationId, "s19_24_overflow_consent_request", 34, selections
    );
    const overflow = await handleIntegratedConsentRequest(overflowRequest, {
      accountGeneration: 1,
      fieldActorBindingId: PRIMARY_BINDING,
      signal: overflowRequest.signal,
      fetchImpl: async () => {
        backendCalls += 1;
        throw new Error("overflow must not reach Backend");
      }
    });
    assert.equal(overflow.status, 503);
    assert.equal((await overflow.json() as { detail: { code: string } }).detail.code,
      "integrated_consent_store_busy");

    const reportRequest = new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: {
        [CONSENT_INSTALLATION_HEADER]: installationId,
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET,
        [CONSENT_POLICY_HEADER]: INTEGRATED_CONSENT_POLICY_VERSION,
        [CONSENT_REVISION_HEADER]: "1",
        [CONSENT_RECEIPT_HEADER]: "a".repeat(64)
      }
    });
    const reportBusy = await authorizeIntegratedConsentRequest(
      reportRequest, ["automatic_reporting"], PRIMARY_BINDING
    );
    assert.equal(reportBusy.error?.status, 503);
    assert.equal((await reportBusy.error!.json() as { detail: { code: string } }).detail.code,
      "integrated_consent_store_busy");

    controllers[0]!.abort();
    const cancelledReport = new AbortController();
    const cancelledRequest = new Request(reportRequest, {
      signal: cancelledReport.signal
    });
    const reportPending = authorizeIntegratedConsentRequest(
      cancelledRequest, ["automatic_reporting"], PRIMARY_BINDING
    );
    cancelledReport.abort();
    const reportCancelled = await reportPending;
    assert.equal(reportCancelled.error?.status, 499);
    assert.equal((await reportCancelled.error!.json() as { detail: { code: string } }).detail.code,
      "gateway_client_closed");

    controllers.slice(1).forEach((controller) => controller.abort());
    const cancelled = await Promise.all(queued);
    assert.ok(cancelled.every((response) => response.status === 499));
    assert.equal(backendCalls, 1);
    release();
    const saved = await active;
    assert.equal(saved.status, 201);
    const confirmation = await saved.json() as IntegratedConsentConfirmation;
    const admitted = await authorizeIntegratedConsentRequest(
      new Request("http://127.0.0.1:8081/api/reports/v2", {
        headers: receiptHeaders(confirmation)
      }),
      ["raw_source_collection", "automatic_reporting"],
      PRIMARY_BINDING
    );
    assert.equal(admitted.error, undefined);
    assert.equal(backendCalls, 1);
  } finally {
    release();
  }
});

test("S19-24 real HTTP disconnect frees a waiting installation slot", async () => {
  const installationId = "s19_24_http_disconnect_installation";
  const firstId = "s19_24_http_first_consent_request";
  const replacementId = "s19_24_http_replacement_consent_request";
  const selections = {
    raw_source_collection: true,
    automatic_reporting: false,
    mobile_network_transfer: false,
    training_reuse: false
  };
  let entered!: () => void;
  let release!: () => void;
  const backendStarted = new Promise<void>((resolve) => { entered = resolve; });
  const backendHeld = new Promise<void>((resolve) => { release = resolve; });
  let firstReceipt = "";
  const backendRequestIds: string[] = [];
  const server = createGatewayServer({
    fetchImpl: async (input, init) => {
      const body = String(init?.body);
      const event = JSON.parse(body) as { request_id: string };
      backendRequestIds.push(event.request_id);
      if (event.request_id === firstId) {
        firstReceipt = createHash("sha256").update(body).digest("hex");
        entered();
        await backendHeld;
      }
      return consentBackendFetch(input, init);
    }
  });
  const serverCloseResolvers = new Map<string, () => void>();
  server.on("request", (request, response) => {
    const requestId = request.headers["x-fixture-request-id"];
    if (typeof requestId !== "string") return;
    response.once("close", () => {
      serverCloseResolvers.get(requestId)?.();
      serverCloseResolvers.delete(requestId);
    });
  });
  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(0, "127.0.0.1", resolve);
  });
  type HttpCall = {
    client: ClientRequest;
    result: Promise<{ status: number; body: string }>;
    sent: Promise<void>;
    serverClosed: Promise<void>;
    responded: () => boolean;
  };
  const calls: HttpCall[] = [];
  const port = (server.address() as AddressInfo).port;
  const send = async (
    requestId: string,
    revision: number,
    expectedPreviousReceipt: string | null = null
  ): Promise<HttpCall> => {
    const body = await consentPutRequest(
      installationId, requestId, revision, selections,
      primaryCookie, expectedPreviousReceipt
    ).text();
    const serverClosed = new Promise<void>((resolve) => {
      serverCloseResolvers.set(requestId, resolve);
    });
    let client!: ClientRequest;
    let responseSeen = false;
    const result = new Promise<{ status: number; body: string }>((resolve, reject) => {
      client = httpRequest({
        hostname: "127.0.0.1",
        port,
        method: "PUT",
        path: "/privacy/rights?control=integrated-consent",
        agent: false,
        headers: {
          cookie: primaryCookie,
          "content-type": "application/json",
          "content-length": Buffer.byteLength(body),
          "x-fixture-request-id": requestId,
          [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
        }
      }, (response) => {
        responseSeen = true;
        const chunks: Buffer[] = [];
        response.on("data", (chunk: Buffer) => chunks.push(chunk));
        response.once("end", () => resolve({
          status: response.statusCode ?? 0,
          body: Buffer.concat(chunks).toString("utf8")
        }));
        response.once("error", reject);
      });
      client.setTimeout(3_000, () => client.destroy(new Error("fixture HTTP timeout")));
      client.once("error", reject);
      client.end(body);
    });
    void result.catch(() => undefined);
    const sent = new Promise<void>((resolve, reject) => {
      client.once("finish", resolve);
      client.once("error", reject);
    });
    void sent.catch(() => undefined);
    const call = { client, result, sent, serverClosed, responded: () => responseSeen };
    calls.push(call);
    return call;
  };
  const awaitServerClosed = async (targets: HttpCall[]): Promise<void> => {
    let timeout: ReturnType<typeof setTimeout> | undefined;
    try {
      await Promise.race([
        Promise.all(targets.map((call) => call.serverClosed)),
        new Promise<never>((_, reject) => {
          timeout = setTimeout(() => reject(new Error(
            "server did not observe fixture HTTP disconnect"
          )), 3_000);
        })
      ]);
    } finally {
      if (timeout) clearTimeout(timeout);
    }
  };

  try {
    const first = await send(firstId, 1);
    await Promise.race([
      backendStarted,
      first.result.then((response) => {
        throw new Error(`first consent completed before Backend hold: ${response.status}`);
      })
    ]);
    // Client finish is not server queue registration; this earlier timestamp is conservative.
    const waitersStartedAt = performance.now();
    const waiters = await Promise.all(Array.from({ length: 32 }, (_, index) =>
      send(`s19_24_http_waiter_${index.toString().padStart(4, "0")}`, index + 2)
    ));
    await Promise.all(waiters.map((waiter) => waiter.sent));
    await new Promise<void>((resolve) => setImmediate(resolve));

    const overflowAt = performance.now();
    const overflow = await (await send("s19_24_http_overflow_request", 34)).result;
    assert.equal(overflow.status, 503);
    assert.equal(JSON.parse(overflow.body).detail.code, "integrated_consent_store_busy");
    assert.ok(performance.now() - overflowAt < 1_000, "overflow must not time out in the queue");

    const disconnected = waiters[0]!;
    const closed = assert.rejects(disconnected.result, /fixture waiter disconnected/);
    disconnected.client.destroy(new Error("fixture waiter disconnected"));
    await closed;
    await awaitServerClosed([disconnected]);
    assert.equal(disconnected.responded(), false, "closed client must not receive a 499 response");

    const replacement = await send(replacementId, 2, firstReceipt);
    await replacement.sent;
    await new Promise<void>((resolve) => setImmediate(resolve));
    const fullAgain = await (await send("s19_24_http_full_again_request", 35)).result;
    assert.equal(fullAgain.status, 503);
    assert.equal(JSON.parse(fullAgain.body).detail.code, "integrated_consent_store_busy");
    assert.ok(performance.now() - waitersStartedAt < 900,
      "replacement and full-again overflow must complete before any original waiter can time out");

    const remaining = waiters.slice(1).map((waiter) =>
      assert.rejects(waiter.result, /fixture waiter cleanup/)
    );
    waiters.slice(1).forEach((waiter) =>
      waiter.client.destroy(new Error("fixture waiter cleanup"))
    );
    await Promise.all(remaining);
    await awaitServerClosed(waiters.slice(1));
    assert.ok(waiters.every((waiter) => !waiter.responded()));
    assert.deepEqual(backendRequestIds, [firstId]);

    release();
    assert.equal((await first.result).status, 201);
    const replacementResponse = await replacement.result;
    assert.equal(replacementResponse.status, 201, replacementResponse.body);
    assert.deepEqual(backendRequestIds, [firstId, replacementId]);
    const recordId = `${createHash("sha256")
      .update(`integrated-consent\0${installationId}`).digest("hex")}.json`;
    const state = decryptTestStateFile<{ events: Array<{ request_id: string }> }>(
      { kind: "integrated-consent", recordId },
      await readFile(path.join(stateDirectory, "integrated-consent", recordId), "utf8"),
      INTEGRATED_CONSENT_MAX_STATE_BYTES
    );
    assert.deepEqual(state.events.map((event) => event.request_id), [firstId, replacementId]);
  } finally {
    calls.forEach((call) => call.client.destroy());
    server.closeAllConnections();
    release();
    let timeout: ReturnType<typeof setTimeout> | undefined;
    try {
      await Promise.race([
        new Promise<void>((resolve, reject) => {
          server.close((error) => error ? reject(error) : resolve());
        }),
        new Promise<never>((_, reject) => {
          timeout = setTimeout(() => reject(new Error("fixture server cleanup timed out")), 3_000);
        })
      ]);
    } finally {
      if (timeout) clearTimeout(timeout);
      await Promise.allSettled(calls.map((call) => call.result));
    }
  }
});

test("S19-29 saturated consent ledger permits withdrawal then Backend-confirmed reconsent without discarding audit", async () => {
  const installationId = "consent-capacity-regression-installation";
  const selections = {
    raw_source_collection: true,
    automatic_reporting: true,
    mobile_network_transfer: true,
    training_reuse: true
  };
  const first = await save(installationId, "capacity_initial_request", 1, selections);
  assert.equal(first.status, 201);
  const recordId = `${createHash("sha256").update(`integrated-consent\0${installationId}`).digest("hex")}.json`;
  const filePath = path.join(stateDirectory, "integrated-consent", recordId);
  const state = decryptTestStateFile<{
    events: Array<Record<string, unknown>>;
    field_actor_binding: { consent_revision: number };
  }>({ kind: "integrated-consent", recordId }, await readFile(filePath, "utf8"), INTEGRATED_CONSENT_MAX_STATE_BYTES);
  const template = state.events[0]!;
  state.events = [];
  let previous: string | null = null;
  for (let index = 0; index < 4096; index += 1) {
    const { gateway_audit_record_sha256: _oldHash, ...fields } = template;
    const event: Record<string, unknown> = {
      ...fields,
      request_id: `capacity_${String(index).padStart(8, "0")}`,
      client_revision: index + 1,
      revision: index + 1,
      previous_gateway_audit_record_sha256: previous
    };
    previous = createHash("sha256").update(canonicalJson({ installation_id: installationId, ...event })).digest("hex");
    state.events.push({ ...event, gateway_audit_record_sha256: previous });
  }
  state.field_actor_binding.consent_revision = 4096;
  const originalFirst = state.events[0]!.gateway_audit_record_sha256;
  const originalEvents = structuredClone(state.events);
  await writeFile(filePath, encryptTestStateFile(
    { kind: "integrated-consent", recordId }, state, INTEGRATED_CONSENT_MAX_STATE_BYTES
  ));
  let revision = 4096;
  for (const key of Object.keys(selections) as Array<keyof typeof selections>) {
    selections[key] = false;
    revision += 1;
    const response = await save(installationId, `capacity_withdrawal_${revision}`, revision, selections);
    assert.equal(response.status, 201, JSON.stringify(response.confirmation));
    assert.equal(response.confirmation.revision, revision);
  }
  const countBeforeDenied = backendEvents.size;
  const denied = await save(installationId, "capacity_regrant_denied", 4101, { ...selections, automatic_reporting: true });
  assert.equal(denied.status, 201);
  assert.equal(backendEvents.size, countBeforeDenied + 1);
  const finalState = decryptTestStateFile<{ events: Array<Record<string, unknown>> }>(
    { kind: "integrated-consent", recordId }, await readFile(filePath, "utf8"), INTEGRATED_CONSENT_MAX_STATE_BYTES
  );
  assert.equal(finalState.events.length, 6);
  const segments = integratedConsentAuditSegmentPaths().filter((entry) => entry.statePath === filePath);
  assert.equal(segments.length, 1);
  const archive = decryptTestStateFile<{ events: Array<Record<string, unknown>> }>(
    { kind: "integrated-consent", recordId: path.basename(segments[0]!.segmentPath) },
    await readFile(segments[0]!.segmentPath, "utf8"), INTEGRATED_CONSENT_MAX_STATE_BYTES
  );
  const history = [...archive.events, ...finalState.events];
  assert.equal(history.length, 4101);
  assert.equal(history[0]!.gateway_audit_record_sha256, originalFirst);
  assert.deepEqual(history.slice(0, 4096), originalEvents);
  assert.equal(denied.confirmation.revision, 4101);
  const authorized = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/reports/v2", { headers: receiptHeaders(denied.confirmation) }),
    ["automatic_reporting"], PRIMARY_BINDING
  );
  assert.equal(authorized.error, undefined);
});

test("S19-29 a saturated ledger records an all-false withdrawal after another actor's event", async () => {
  const installationId = "s19-29-capacity-other-actor-installation";
  const selections = {
    raw_source_collection: true,
    automatic_reporting: true,
    mobile_network_transfer: true,
    training_reuse: true
  };
  const first = await save(installationId, "s19_29_initial_request", 1, selections);
  assert.equal(first.status, 201);
  const recordId = `${createHash("sha256").update(`integrated-consent\0${installationId}`).digest("hex")}.json`;
  const filePath = path.join(stateDirectory, "integrated-consent", recordId);
  const state = decryptTestStateFile<{
    events: Array<Record<string, unknown>>;
    field_actor_binding: { actor_sha256: string; consent_revision: number };
  }>({ kind: "integrated-consent", recordId }, await readFile(filePath, "utf8"), INTEGRATED_CONSENT_MAX_STATE_BYTES);
  const template = state.events[0]!;
  const otherActor = "b".repeat(64);
  state.events = [];
  let previous: string | null = null;
  for (let index = 0; index < 4096; index += 1) {
    const { gateway_audit_record_sha256: _oldHash, ...fields } = template;
    const event: Record<string, unknown> = {
      ...fields,
      request_id: `s19_29_capacity_${String(index).padStart(8, "0")}`,
      client_revision: index + 1,
      revision: index + 1,
      previous_gateway_audit_record_sha256: previous,
      // The newest event belongs to another actor (account switch on this installation).
      ...(index === 4095 ? { field_actor_binding_sha256: otherActor } : {})
    };
    previous = createHash("sha256").update(canonicalJson({ installation_id: installationId, ...event })).digest("hex");
    state.events.push({ ...event, gateway_audit_record_sha256: previous });
  }
  state.field_actor_binding.actor_sha256 = otherActor;
  state.field_actor_binding.consent_revision = 4096;
  await writeFile(filePath, encryptTestStateFile(
    { kind: "integrated-consent", recordId }, state, INTEGRATED_CONSENT_MAX_STATE_BYTES
  ));
  const allFalse = {
    raw_source_collection: false,
    automatic_reporting: false,
    mobile_network_transfer: false,
    training_reuse: false
  };
  const withdrawn = await save(installationId, "s19_29_all_false_withdrawal", 4097, allFalse);
  assert.equal(withdrawn.status, 201, JSON.stringify(withdrawn.confirmation));
  assert.deepEqual(withdrawn.confirmation.selections, allFalse);
  assert.equal(withdrawn.confirmation.revision, 4097);
  const denied = await save(installationId, "s19_29_regrant_after", 4098, { ...allFalse, raw_source_collection: true });
  assert.equal(denied.status, 201);
});

test("logout while consent body is pending prevents the backend mutation", async () => {
  const source = consentPutRequest("consent-stale-session-installation", "consent_stale_session_request", 1, {
    raw_source_collection: true, automatic_reporting: false,
    mobile_network_transfer: false, training_reuse: false
  });
  const bytes = new TextEncoder().encode(await source.text());
  let release!: () => void;
  let started!: () => void;
  const pendingBody = new Promise<void>((resolve) => { release = resolve; });
  const bodyStarted = new Promise<void>((resolve) => { started = resolve; });
  const body = new ReadableStream<Uint8Array>({
    async pull(controller) {
      started();
      await pendingBody;
      controller.enqueue(bytes);
      controller.close();
    }
  });
  let upstreamCalls = 0;
  const request = new Request(source.url, {
    method: "PUT", headers: source.headers, body, duplex: "half"
  } as RequestInit & { duplex: "half" });
  const responsePromise = handleGatewayRequest(request, { fetchImpl: async (input, init) => {
    upstreamCalls += 1;
    return consentBackendFetch(input, init);
  } });
  try {
    await bodyStarted;
    assert.equal(clearGatewaySession(new Request("http://127.0.0.1:8081/api/field-session", {
      headers: { cookie: primaryCookie }
    })).status, 204);
    release();
    const response = await responsePromise;
    assert.equal(response.status, 401);
    assert.equal(upstreamCalls, 0);
  } finally {
    release();
    primaryCookie = await login(PRIMARY_ACTOR, PRIMARY_TOKEN, "198.51.100.12");
  }
});

function s19ConsentLockPaths(installationId: string): {
  statePath: string;
  lockPath: string;
  replacementPath: string;
} {
  const digest = createHash("sha256")
    .update("integrated-consent\0" + installationId).digest("hex");
  const statePath = path.join(stateDirectory, "integrated-consent", digest + ".json");
  const lockPath = statePath + ".lock";
  return { statePath, lockPath, replacementPath: lockPath + ".replacement" };
}

async function cleanupS19ConsentLockFixture(
  observation: string,
  files: readonly string[]
): Promise<void> {
  for (const file of files) {
    await rm(file, { force: true });
    await assert.rejects(lstat(file), { code: "ENOENT" });
  }
  console.log(JSON.stringify({
    probe: "S19-22",
    observation,
    fixture_files_removed: true,
    removed_file_count: files.length
  }));
}

// S19-22: the consent lock is the shared kernel lock. These tests replace the PID-record
// expectations (malformed/live-PID residual records failing closed, release deleting only
// its own record) with kernel-lock meaning: content is ignored, the kernel releases a dead
// holder, a replaced inode or an extra link fails the integrity check.
const S19_22_EMPTY_SELECTIONS = {
  raw_source_collection: false, automatic_reporting: false,
  mobile_network_transfer: false, training_reuse: false
};

for (const [label, contents] of [
  ["empty", ""],
  ["partial-json", "{"],
  // PID reuse: a legacy record naming a live PID (this process) is not an owner.
  ["legacy-live-pid", JSON.stringify({
    pid: process.pid,
    token: "b".repeat(32),
    acquired_at: "2000-01-01T00:00:00.000Z"
  }) + "\n"]
] as const) {
  test("S19-22 residual lock content is ignored by the kernel lock: " + label, async () => {
    const installationId = "s19_22_residual_content_installation_" + label.replace(/-/g, "_");
    const { statePath, lockPath } = s19ConsentLockPaths(installationId);
    let backendCalls = 0;
    await mkdir(path.dirname(statePath), { recursive: true, mode: 0o700 });
    try {
      await writeFile(lockPath, contents, { mode: 0o600, flag: "wx" });
      const response = await handleGatewayRequest(consentPutRequest(
        installationId, "s19_22_residual_" + label.replace(/-/g, "_") + "_request_0001",
        1, S19_22_EMPTY_SELECTIONS
      ), { fetchImpl: async (input, init) => {
        backendCalls += 1;
        return consentBackendFetch(input, init);
      } });
      assert.equal(response.status, 201, JSON.stringify(await response.clone().json()));
      assert.equal(backendCalls, 1);
      assert.equal(await readFile(lockPath, "utf8"), contents, "lock bytes are never read or rewritten");
      const kept = await lstat(lockPath, { bigint: true });
      assert.equal(kept.nlink, 1n);
      assert.equal(kept.mode & 0o777n, 0o600n);
      await lstat(statePath);
      console.log(JSON.stringify({
        probe: "S19-22",
        observation: "residual-" + label,
        status: response.status,
        backend_calls: backendCalls,
        lock_bytes_preserved: true,
        consent_state_written: true
      }));
    } finally {
      await cleanupS19ConsentLockFixture(label, [lockPath, statePath]);
    }
  });
}

for (const replacement of ["content", "inode"] as const) {
  test("S19-22 kernel lock release keeps the lock file and rejects a replaced inode: " + replacement, async () => {
    const { statePath, lockPath, replacementPath } = s19ConsentLockPaths(
      "s19_22_release_replacement_installation_" + replacement
    );
    try {
      const normal = await withIntegratedConsentStateFileLockForMaintenance(
        statePath,
        () => "normal-release-control"
      );
      assert.equal(normal, "normal-release-control");
      const kept = await lstat(lockPath, { bigint: true });
      assert.equal(kept.nlink, 1n);
      assert.equal(kept.mode & 0o777n, 0o600n);
      assert.equal(
        await withIntegratedConsentStateFileLockForMaintenance(statePath, () => "reacquired"),
        "reacquired"
      );
      const replacementBytes = "replacement-" + replacement + "\n";
      let replacedIdentity: { dev: bigint; ino: bigint } | null = null;
      const run = withIntegratedConsentStateFileLockForMaintenance(statePath, async () => {
        if (replacement === "content") {
          await writeFile(lockPath, replacementBytes);
        } else {
          await writeFile(replacementPath, replacementBytes, { mode: 0o600, flag: "wx" });
          await rename(replacementPath, lockPath);
        }
        const replaced = await lstat(lockPath, { bigint: true });
        replacedIdentity = { dev: replaced.dev, ino: replaced.ino };
        return "action-completed";
      });
      if (replacement === "content") {
        assert.equal(await run, "action-completed");
      } else {
        await assert.rejects(run, (error: unknown) => {
          // The held (now unlinked) inode fails its link-count check before the path check.
          assert.ok(error instanceof Error);
          assert.equal(error.name, "ExclusiveFileLockIntegrityError");
          assert.equal(
            error.message,
            "exclusive file lock must be an owned regular file with mode 0600 and one link"
          );
          return true;
        });
      }
      const afterRelease = await lstat(lockPath, { bigint: true });
      assert.ok(replacedIdentity);
      assert.equal(afterRelease.ino, (replacedIdentity as { ino: bigint }).ino);
      assert.equal(await readFile(lockPath, "utf8"), replacementBytes);
      await assert.rejects(lstat(statePath), { code: "ENOENT" });
      console.log(JSON.stringify({
        probe: "S19-22",
        observation: "release-" + replacement,
        lock_file_kept_after_release: true,
        replacement_preserved: true,
        release_rejected: replacement === "inode"
      }));
    } finally {
      await cleanupS19ConsentLockFixture(
        "release-" + replacement, [lockPath, replacementPath]
      );
    }
  });
}

function waitForLine(child: ReturnType<typeof spawn>, expected: string): Promise<void> {
  return new Promise((resolve, reject) => {
    let buffered = "";
    let stderr = "";
    child.stderr?.on("data", (chunk: Buffer) => { stderr += chunk.toString("utf8"); });
    child.stdout?.on("data", (chunk: Buffer) => {
      buffered += chunk.toString("utf8");
      if (buffered.split(/\r?\n/).includes(expected)) resolve();
    });
    child.once("error", reject);
    child.once("exit", (code) => {
      if (!buffered.split(/\r?\n/).includes(expected)) {
        reject(new Error(`lock holder exited ${code} before ${expected}: ${stderr}`));
      }
    });
  });
}

test("S19-22 a live kernel holder keeps consent busy and SIGKILL releases it immediately", async () => {
  const installationId = "s19_22_kernel_holder_installation";
  const { statePath, lockPath } = s19ConsentLockPaths(installationId);
  let backendCalls = 0;
  const fetchImpl: GatewayFetch = async (input, init) => {
    backendCalls += 1;
    return consentBackendFetch(input, init);
  };
  await mkdir(path.dirname(statePath), { recursive: true, mode: 0o700 });
  await writeFile(lockPath, "", { mode: 0o600, flag: "wx" });
  const holder = spawn(process.execPath, [
    "--input-type=module",
    "-e",
    [
      "const [url, lockPath] = process.argv.slice(1);",
      "const lock = await import(url);",
      "lock.acquireExclusiveFileLockLease(lockPath);",
      "process.stdout.write('locked\\n');",
      "setInterval(() => {}, 1000);"
    ].join("\n"),
    new URL("../src/exclusive-file-lock.js", import.meta.url).href,
    lockPath
  ], { stdio: ["ignore", "pipe", "pipe"] });
  try {
    await waitForLine(holder, "locked");
    const busy = await handleGatewayRequest(consentPutRequest(
      installationId, "s19_22_kernel_holder_request_0001", 1, S19_22_EMPTY_SELECTIONS
    ), { fetchImpl });
    assert.equal(busy.status, 503);
    assert.equal(
      (await busy.json() as { detail: { code: string } }).detail.code,
      "integrated_consent_store_busy"
    );
    assert.equal(backendCalls, 0);
    await assert.rejects(lstat(statePath), { code: "ENOENT" });
    const exited = new Promise<void>((resolve) => holder.once("exit", () => resolve()));
    holder.kill("SIGKILL");
    await exited;
    const started = performance.now();
    const admitted = await handleGatewayRequest(consentPutRequest(
      installationId, "s19_22_kernel_holder_request_0002", 1, S19_22_EMPTY_SELECTIONS
    ), { fetchImpl });
    const elapsedMs = performance.now() - started;
    assert.equal(admitted.status, 201, JSON.stringify(await admitted.clone().json()));
    assert.equal(backendCalls, 1);
    assert.ok(elapsedMs < 1_000, `recovery after SIGKILL took ${elapsedMs} ms`);
    console.log(JSON.stringify({
      probe: "S19-22",
      observation: "kernel-holder-sigkill",
      busy_status: 503,
      recovered_status: admitted.status,
      recovery_ms: Math.round(elapsedMs)
    }));
  } finally {
    if (holder.exitCode === null && holder.signalCode === null) holder.kill("SIGKILL");
    await cleanupS19ConsentLockFixture("kernel-holder", [lockPath, statePath]);
  }
});

test("S19-22 a residual .reaper hard link fails closed until the operator removes it", async () => {
  const installationId = "s19_22_reaper_residual_installation";
  const { statePath, lockPath } = s19ConsentLockPaths(installationId);
  const reaperPath = `${lockPath}.reaper`;
  let backendCalls = 0;
  const fetchImpl: GatewayFetch = async (input, init) => {
    backendCalls += 1;
    return consentBackendFetch(input, init);
  };
  await mkdir(path.dirname(statePath), { recursive: true, mode: 0o700 });
  try {
    await writeFile(lockPath, JSON.stringify({
      pid: process.pid, token: "c".repeat(32), acquired_at: "2000-01-01T00:00:00.000Z"
    }) + "\n", { mode: 0o600, flag: "wx" });
    await link(lockPath, reaperPath);
    const closed = await handleGatewayRequest(consentPutRequest(
      installationId, "s19_22_reaper_request_0001", 1, S19_22_EMPTY_SELECTIONS
    ), { fetchImpl });
    assert.equal(closed.status, 503);
    assert.equal(
      (await closed.json() as { detail: { code: string } }).detail.code,
      "integrated_consent_store_unavailable"
    );
    assert.equal(backendCalls, 0);
    await assert.rejects(lstat(statePath), { code: "ENOENT" });
    // Operator step with the service stopped: remove the residual link.
    await rm(reaperPath);
    const recovered = await handleGatewayRequest(consentPutRequest(
      installationId, "s19_22_reaper_request_0002", 1, S19_22_EMPTY_SELECTIONS
    ), { fetchImpl });
    assert.equal(recovered.status, 201);
    assert.equal(backendCalls, 1);
  } finally {
    await cleanupS19ConsentLockFixture("reaper-residual", [reaperPath, lockPath, statePath]);
  }
});

test("S19-23 an extra link on the held lock makes release throw the integrity error", async () => {
  const { statePath, lockPath } = s19ConsentLockPaths(
    "s19_23_release_error_installation"
  );
  const extraLink = `${lockPath}.reaper`;
  try {
    await assert.rejects(
      withIntegratedConsentStateFileLockForMaintenance(statePath, async () => {
        await link(lockPath, extraLink);
      }),
      (error: unknown) => {
        assert.ok(error instanceof Error);
        assert.equal(
          error.message,
          "exclusive file lock must be an owned regular file with mode 0600 and one link"
        );
        return true;
      }
    );
  } finally {
    await rm(extraLink, { force: true });
    await rm(lockPath, { force: true });
  }
});

test("S19-22 lock release failure settles queued consent requests and permits recovery", async () => {
  const installationId = "consent_release_failure_installation";
  const digest = createHash("sha256")
    .update(`integrated-consent\0${installationId}`).digest("hex");
  const lockPath = path.join(stateDirectory, "integrated-consent", `${digest}.json.lock`);
  const replacementPath = `${lockPath}.replacement`;
  let entered!: () => void;
  const started = new Promise<void>(resolve => { entered = resolve; });
  let unblock!: () => void;
  const blocked = new Promise<void>(resolve => { unblock = resolve; });
  let deadline: ReturnType<typeof setTimeout> | undefined;
  const first = handleGatewayRequest(consentPutRequest(
    installationId, "consent_release_failure_request_0001", 1, S19_22_EMPTY_SELECTIONS
  ), { fetchImpl: async () => {
    // Replace the held lock inode so the first release fails its integrity check.
    await writeFile(replacementPath, "{}\n", { mode: 0o600, flag: "wx" });
    await rename(replacementPath, lockPath);
    entered();
    await blocked;
    return Response.json({ detail: { code: "fixture_unavailable" } }, { status: 503 });
  } });
  try {
    await started;
    const queued = handleGatewayRequest(consentPutRequest(
      installationId, "consent_release_failure_request_0002", 2, S19_22_EMPTY_SELECTIONS
    ), { fetchImpl: consentBackendFetch });
    await new Promise<void>(resolve => setImmediate(resolve));
    unblock();
    const [failed, next] = await Promise.race([
      Promise.all([first, queued]),
      new Promise<never>((_resolve, reject) => {
        deadline = setTimeout(() => reject(new Error("consent queue remained blocked")), 2_000);
      })
    ]);
    assert.equal(failed.status, 503);
    // The queued request locks the replacement inode and commits without operator action.
    assert.equal(next.status, 201);
    assert.equal((await next.json() as IntegratedConsentConfirmation).revision, 1);
  } finally {
    unblock();
    if (deadline !== undefined) clearTimeout(deadline);
    await cleanupS19ConsentLockFixture("release-error-control", [lockPath, replacementPath]);
  }
});

test("S19-30 Gateway write failure after Backend commit is reconciled by the same request", async () => {
  const installationId = "s19_30_backend_commit_installation";
  const requestId = "s19_30_backend_commit_request_0001";
  const selections = {
    raw_source_collection: true, automatic_reporting: false,
    mobile_network_transfer: false, training_reuse: false
  };
  const { statePath } = s19ConsentLockPaths(installationId);
  let backendCalls = 0;
  const backendReceipts: string[] = [];
  const fetchImpl: GatewayFetch = async (input, init) => {
    backendCalls += 1;
    const response = await consentBackendFetch(input, init);
    backendReceipts.push(
      (await response.clone().json() as { receipt_sha256: string }).receipt_sha256
    );
    return response;
  };
  const originalRenameSync = fs.renameSync;
  let injected = false;
  let failed: Response;
  fs.renameSync = (from, to) => {
    if (!injected && String(to) === statePath) {
      injected = true;
      throw Object.assign(new Error("injected consent state rename failure"), { code: "EIO" });
    }
    return originalRenameSync(from, to);
  };
  syncBuiltinESMExports();
  try {
    failed = await handleGatewayRequest(
      consentPutRequest(installationId, requestId, 1, selections), { fetchImpl }
    );
  } finally {
    fs.renameSync = originalRenameSync;
    syncBuiltinESMExports();
  }
  // Backend committed, Gateway did not: no local confirmation is exposed.
  assert.equal(injected, true);
  assert.equal(backendCalls, 1);
  assert.equal(failed.status, 503);
  assert.equal(
    (await failed.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_outcome_unknown"
  );
  await assert.rejects(lstat(statePath), { code: "ENOENT" });

  // The client's durable retry of the same request adopts the Backend receipt.
  const retried = await handleGatewayRequest(
    consentPutRequest(installationId, requestId, 1, selections), { fetchImpl }
  );
  assert.equal(retried.status, 200);
  const confirmation = await retried.json() as IntegratedConsentConfirmation;
  assert.equal(backendCalls, 2);
  assert.equal(backendReceipts[1], backendReceipts[0]);
  assert.equal(confirmation.request_id, requestId);
  assert.equal(confirmation.revision, 1);
  assert.equal(confirmation.backend_consent_receipt_sha256, backendReceipts[0]);
  const authorized = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/reports/v2", { headers: receiptHeaders(confirmation) }),
    ["raw_source_collection"],
    PRIMARY_BINDING
  );
  assert.equal(authorized.error, undefined);
  assert.equal(authorized.confirmation?.backend_consent_receipt_sha256, backendReceipts[0]);
});

test("S19-27 a consent write whose rename happened is outcome unknown and the same request confirms it", async () => {
  const installationId = "s19_27_outcome_unknown_installation";
  const requestId = "s19_27_outcome_unknown_request_0001";
  const selections = {
    raw_source_collection: true, automatic_reporting: false,
    mobile_network_transfer: false, training_reuse: false
  };
  const { statePath } = s19ConsentLockPaths(installationId);
  const receipts: string[] = [];
  const fetchImpl: GatewayFetch = async (input, init) => {
    const response = await consentBackendFetch(input, init);
    receipts.push((await response.clone().json() as { receipt_sha256: string }).receipt_sha256);
    return response;
  };
  setGatewayStateCommitFaultForTests("integrated-consent");
  let unknown: Response;
  try {
    unknown = await handleGatewayRequest(
      consentPutRequest(installationId, requestId, 1, selections), { fetchImpl }
    );
  } finally {
    setGatewayStateCommitFaultForTests(null);
  }
  assert.equal(unknown.status, 503);
  assert.equal(unknown.headers.get("retry-after"), "1");
  const detail = (await unknown.json() as { detail: { code: string; retry: string } }).detail;
  assert.equal(detail.code, "integrated_consent_outcome_unknown");
  assert.equal(detail.retry, "same_request_id");
  // Unlike the not-committed case (S19-30), the renamed local state exists.
  await lstat(statePath);
  const retried = await handleGatewayRequest(
    consentPutRequest(installationId, requestId, 1, selections), { fetchImpl }
  );
  assert.equal(retried.status, 200);
  const confirmation = await retried.json() as IntegratedConsentConfirmation;
  assert.equal(confirmation.request_id, requestId);
  assert.equal(confirmation.revision, 1);
  assert.equal(confirmation.backend_consent_receipt_sha256, receipts[0]);
});

test("four selections are independent, versioned, idempotent, and current", async () => {
  const installationId = "501e3ad4-e74f-4433-820f-72ac2fdd42ad";
  const selections = {
    raw_source_collection: true,
    automatic_reporting: false,
    mobile_network_transfer: true,
    training_reuse: false
  };
  const first = await save(
    installationId,
    "integrated_consent_request_0001",
    1,
    selections
  );
  assert.equal(first.status, 201);
  assert.equal(first.confirmation.revision, 1);
  assert.deepEqual(first.confirmation.selections, selections);
  assert.deepEqual(Object.keys(first.confirmation), [
    "schema_version",
    "current",
    "installation_id",
    "request_id",
    "policy_version",
    "item_versions",
    "client_revision",
    "revision",
    "selections",
    "confirmed_at",
    "gateway_audit_record_sha256",
    "backend_consent_receipt_sha256"
  ]);
  assert.equal(
    first.confirmation.schema_version,
    "walksafe.integrated-consent-confirmation.v2"
  );
  assert.match(first.confirmation.gateway_audit_record_sha256, /^[0-9a-f]{64}$/);
  assert.match(first.confirmation.backend_consent_receipt_sha256, /^[0-9a-f]{64}$/);
  assert.notEqual(
    first.confirmation.gateway_audit_record_sha256,
    first.confirmation.backend_consent_receipt_sha256
  );
  const gatewayAuditHeaders = receiptHeaders(first.confirmation);
  gatewayAuditHeaders.set(
    CONSENT_RECEIPT_HEADER,
    first.confirmation.gateway_audit_record_sha256
  );
  const gatewayAuditRejected = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: gatewayAuditHeaders
    }),
    ["raw_source_collection"],
    PRIMARY_BINDING
  );
  assert.equal(gatewayAuditRejected.error?.status, 409);

  const retry = await save(
    installationId,
    "integrated_consent_request_0001",
    1,
    selections
  );
  assert.equal(retry.status, 200);
  assert.deepEqual(retry.confirmation, first.confirmation);

  const currentResponse = await handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/privacy/rights" +
        "?control=integrated-consent" +
        `&installation_id=${installationId}` +
        `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
      {
        headers: {
          cookie: primaryCookie,
          [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
        }
      }
    ),
    {
      fetchImpl: backendAuthorityFetch(
        installationId,
        first.confirmation.backend_consent_receipt_sha256,
        selections
      )
    }
  );
  assert.equal(currentResponse.status, 200);
  assert.deepEqual(await currentResponse.json(), first.confirmation);
  assert.equal(hasCurrentIntegratedConsentItemVersions(first.confirmation), true);
  assert.equal(
    hasCurrentIntegratedConsentItemVersions({
      item_versions: {
        ...first.confirmation.item_versions,
        raw_source_collection: "FP-013-RAW-1.0.0"
      }
    }),
    false
  );
});

test("S19-31 consent GET is current only while the Backend latest receipt matches", async () => {
  const installationId = "s19_31_backend_authority_installation";
  const selections = {
    raw_source_collection: true, automatic_reporting: false,
    mobile_network_transfer: false, training_reuse: false
  };
  const saved = await save(installationId, "s19_31_backend_authority_request_0001", 1, selections);
  assert.equal(saved.status, 201);
  const receipt = saved.confirmation.backend_consent_receipt_sha256;
  const read = (fetchImpl: GatewayFetch) => handleGatewayRequest(new Request(
    "http://127.0.0.1:8081/privacy/rights" +
      "?control=integrated-consent" +
      `&installation_id=${installationId}` +
      `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
    { headers: { cookie: primaryCookie, [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET } }
  ), { fetchImpl });
  const calls = { count: 0 };
  const matching = await read(backendAuthorityFetch(installationId, receipt, selections, calls));
  assert.equal(matching.status, 200);
  assert.deepEqual(await matching.json(), saved.confirmation);
  assert.equal(calls.count, 1, "GET must consult the Backend authority");

  // Another device (or path) of the account recorded a newer consent on the Backend.
  const changed = await read(backendAuthorityFetch(installationId, "e".repeat(64), {
    ...selections, raw_source_collection: false
  }));
  assert.equal(changed.status, 409);
  assert.equal(
    (await changed.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_reconsent_required"
  );
  // The Backend requires reconsent (for example after withdrawal or reset).
  const reconsent = await read(backendAuthorityFetch(
    installationId, receipt, selections, { count: 0 }, "RECONSENT_REQUIRED"
  ));
  assert.equal(reconsent.status, 409);
  // The Backend authority is unavailable: fail closed, never the local cache.
  const unavailable = await read(async () => Response.json(
    { detail: { code: "fixture_unavailable" } }, { status: 503 }
  ));
  assert.notEqual(unavailable.status, 200);
  assert.ok(unavailable.status >= 500);
  const invalid = await read(async () => Response.json({ schema_version: "wrong" }));
  assert.equal(invalid.status, 502);
});

test("S19-31-F1 two installations with the same consent stay current without re-consent ping-pong", async () => {
  const first = "s19_31_f1_installation_a";
  const second = "s19_31_f1_installation_b";
  const selections = {
    raw_source_collection: true, automatic_reporting: true,
    mobile_network_transfer: false, training_reuse: false
  };
  const savedA = await save(first, "s19_31_f1_request_a_0001", 1, selections);
  assert.equal(savedA.status, 201);
  // The second installation of the same account records the same selections later, so
  // the Backend's account-wide latest receipt is now the second installation's receipt.
  const savedB = await save(second, "s19_31_f1_request_b_0001", 1, selections);
  assert.equal(savedB.status, 201);
  const latest = savedB.confirmation.backend_consent_receipt_sha256;
  assert.notEqual(latest, savedA.confirmation.backend_consent_receipt_sha256);
  const read = (installationId: string, fetchImpl: GatewayFetch) => handleGatewayRequest(new Request(
    "http://127.0.0.1:8081/privacy/rights" +
      "?control=integrated-consent" +
      `&installation_id=${installationId}` +
      `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
    { headers: { cookie: primaryCookie, [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET } }
  ), { fetchImpl });
  // Several cold starts of each device in turn: both stay current and nothing is re-recorded.
  for (let round = 0; round < 3; round += 1) {
    const readA = await read(first, backendAuthorityFetch(first, latest, selections));
    assert.equal(readA.status, 200, "identical consent re-recorded elsewhere keeps A current");
    assert.deepEqual(await readA.json(), savedA.confirmation);
    const readB = await read(second, backendAuthorityFetch(second, latest, selections));
    assert.equal(readB.status, 200);
    assert.deepEqual(await readB.json(), savedB.confirmation);
  }
  // A different selection recorded by the other installation still requires bootstrap.
  const changed = await read(first, backendAuthorityFetch(first, latest, {
    ...selections, automatic_reporting: false
  }));
  assert.equal(changed.status, 409);
  assert.equal(
    (await changed.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_reconsent_required"
  );
  const reconsent = await read(first, backendAuthorityFetch(
    first, latest, selections, { count: 0 }, "RECONSENT_REQUIRED"
  ));
  assert.equal(reconsent.status, 409);
});

test("authenticated bootstrap strictly projects the Backend consent source", async () => {
  const installationId = "consent-bootstrap-installation-0001";
  const receipt = "d".repeat(64);
  const url =
    "http://127.0.0.1:8081/privacy/rights" +
    "?control=integrated-consent-bootstrap" +
    `&installation_id=${installationId}` +
    `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`;
  let backendCalls = 0;
  const fetchImpl: GatewayFetch = async (input, init) => {
    backendCalls += 1;
    assert.equal(
      String(input),
      "http://127.0.0.1:8000/privacy/consent-bootstrap" +
        `?installation_id=${installationId}` +
        `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`
    );
    assert.equal(init?.method, "GET");
    assert.equal(init?.cache, "no-store");
    assert.equal(init?.body, undefined);
    const headers = new Headers(init?.headers);
    assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
    assert.equal(headers.get(ACTOR_ID_HEADER), PRIMARY_ACTOR);
    assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "1");
    assert.equal(headers.get(ACTOR_ASSERTION_HEADER)?.startsWith("v4."), true);
    assertBackendActorAssertionV4(input, init, {
      actorId: PRIMARY_ACTOR, accountGeneration: 1, authEpoch: null
    });
    return Response.json({
      schema_version: "walksafe.integrated-consent-bootstrap.v1",
      status: "READY",
      source: "CURRENT_CONSENT",
      installation_id: installationId,
      policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
      item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
      client_revision_floor: 3,
      selections: {
        raw_source_collection: true,
        automatic_reporting: false,
        mobile_network_transfer: false,
        training_reuse: true
      },
      source_receipt_sha256: receipt,
      expected_previous_backend_receipt_sha256: receipt
    });
  };

  const unauthenticated = await handleGatewayRequest(
    new Request(url),
    { fetchImpl }
  );
  assert.equal(unauthenticated.status, 401);
  assert.equal(backendCalls, 0);

  const response = await handleGatewayRequest(
    new Request(url, { headers: { cookie: primaryCookie } }),
    { fetchImpl }
  );
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.deepEqual(await response.json(), {
    schema_version: "walksafe.integrated-consent-bootstrap.v1",
    status: "READY",
    source: "CURRENT_CONSENT",
    installation_id: installationId,
    policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
    item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
    client_revision_floor: 3,
    selections: {
      raw_source_collection: true,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: true
    },
    source_receipt_sha256: receipt,
    expected_previous_backend_receipt_sha256: receipt
  });
  assert.equal(backendCalls, 1);
});

test("bootstrap rejects malformed Backend state and impossible nullable CAS", async () => {
  const installationId = "consent-bootstrap-installation-0002";
  const response = await handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/privacy/rights" +
        "?control=integrated-consent-bootstrap" +
        `&installation_id=${installationId}` +
        `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
      { headers: { cookie: primaryCookie } }
    ),
    {
      fetchImpl: async () => Response.json({
        schema_version: "walksafe.integrated-consent-bootstrap.v1",
        status: "RECONSENT_REQUIRED",
        source: "NONE",
        installation_id: installationId,
        policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision_floor: 2,
        selections: null,
        source_receipt_sha256: null,
        expected_previous_backend_receipt_sha256: null
      })
    }
  );
  assert.equal(response.status, 502);
  assert.equal(
    (await response.json() as { detail: { code: string } }).detail.code,
    "gateway_upstream_protocol_invalid"
  );
});

test("withdrawal advances revision and makes the prior receipt stale", async () => {
  const installationId = "960c62f3-7e70-4ae7-bd03-60c2bfe907dd";
  const granted = await save(
    installationId,
    "integrated_consent_request_1001",
    1,
    {
      raw_source_collection: true,
      automatic_reporting: true,
      mobile_network_transfer: false,
      training_reuse: true
    }
  );
  const withdrawn = await save(
    installationId,
    "integrated_consent_request_1002",
    2,
    {
      raw_source_collection: true,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: true
    }
  );
  assert.equal(withdrawn.confirmation.revision, 2);

  const stale = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(granted.confirmation)
    }),
    ["raw_source_collection"],
    PRIMARY_BINDING
  );
  assert.equal(stale.error?.status, 409);

  const current = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(withdrawn.confirmation)
    }),
    ["raw_source_collection"],
    PRIMARY_BINDING
  );
  assert.equal(current.error, undefined);
  assert.equal(current.confirmation?.selections.automatic_reporting, false);
});

test("all-denied selection is valid and wrong policy requires reconsent", async () => {
  const installationId = "fcb23c5c-dd9b-487b-ad40-b839d7e2def7";
  const denied = await save(
    installationId,
    "integrated_consent_request_2001",
    1,
    {
      raw_source_collection: false,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: false
    }
  );
  assert.equal(denied.status, 201);

  const blocked = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(denied.confirmation)
    }),
    ["raw_source_collection"],
    PRIMARY_BINDING
  );
  assert.equal(blocked.error?.status, 403);

  const wrongVersion = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/privacy/rights?control=integrated-consent", {
      method: "PUT",
      headers: {
        cookie: primaryCookie,
        "content-type": "application/json",
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
      },
      body: JSON.stringify({
        schema_version: "walksafe.integrated-consent-request.v1",
        installation_id: installationId,
        request_id: "integrated_consent_request_2002",
        policy_version: "FP-013-0.9.0",
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision: 2,
        expected_previous_backend_receipt_sha256: null,
        selections: denied.confirmation.selections
      })
    }),
    { fetchImpl: consentBackendFetch }
  );
  assert.equal(wrongVersion.status, 409);
  const body = await wrongVersion.json() as { detail: { required_policy_version: string } };
  assert.equal(body.detail.required_policy_version, INTEGRATED_CONSENT_POLICY_VERSION);
});

test("control secret protects read and write access", async () => {
  const installationId = "fcb23c5c-dd9b-487b-ad40-b839d7e2def8";
  const selections = {
    raw_source_collection: false,
    automatic_reporting: false,
    mobile_network_transfer: false,
    training_reuse: false
  };
  await save(
    installationId,
    "integrated_consent_request_3001",
    1,
    selections
  );
  const deniedRead = await handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/privacy/rights" +
        "?control=integrated-consent" +
        `&installation_id=${installationId}` +
        `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
      { headers: { cookie: primaryCookie } }
    )
  );
  assert.equal(deniedRead.status, 401);
  const deniedWrite = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/privacy/rights?control=integrated-consent", {
      method: "PUT",
      headers: {
        cookie: primaryCookie,
        "content-type": "application/json",
        [CONSENT_CONTROL_SECRET_HEADER]: "b".repeat(64)
      },
      body: JSON.stringify({
        schema_version: "walksafe.integrated-consent-request.v1",
        installation_id: installationId,
        request_id: "integrated_consent_request_3002",
        policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision: 2,
        expected_previous_backend_receipt_sha256: null,
        selections
      })
    }),
    { fetchImpl: consentBackendFetch }
  );
  assert.equal(deniedWrite.status, 403);
});

test("older client revision cannot reverse a newer withdrawal", async () => {
  const installationId = "fcb23c5c-dd9b-487b-ad40-b839d7e2def9";
  const withdrawn = await save(
    installationId,
    "integrated_consent_request_4002",
    2,
    {
      raw_source_collection: false,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: false
    }
  );
  assert.equal(withdrawn.status, 201);
  const staleGrant = await save(
    installationId,
    "integrated_consent_request_4001",
    1,
    {
      raw_source_collection: true,
      automatic_reporting: true,
      mobile_network_transfer: true,
      training_reuse: true
    }
  );
  assert.equal(staleGrant.status, 409);
});

test("cellular authorization requires the independent mobile selection", async () => {
  const installationId = "fcb23c5c-dd9b-487b-ad40-b839d7e2defa";
  const saved = await save(
    installationId,
    "integrated_consent_request_5001",
    1,
    {
      raw_source_collection: true,
      automatic_reporting: true,
      mobile_network_transfer: false,
      training_reuse: false
    }
  );
  const cellularHeaders = receiptHeaders(saved.confirmation);
  cellularHeaders.set(CONSENT_NETWORK_TRANSPORT_HEADER, "cellular");
  const cellular = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: cellularHeaders
    }),
    ["raw_source_collection", "automatic_reporting", "mobile_network_transfer"],
    PRIMARY_BINDING
  );
  assert.equal(cellular.error?.status, 403);

  const wifi = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(saved.confirmation)
    }),
    ["raw_source_collection", "automatic_reporting"],
    PRIMARY_BINDING
  );
  assert.equal(wifi.error, undefined);
});

test("receipt binds to the first field actor until explicit reconsent", async () => {
  const installationId = "fcb23c5c-dd9b-487b-ad40-b839d7e2defb";
  const selections = {
    raw_source_collection: true,
    automatic_reporting: true,
    mobile_network_transfer: false,
    training_reuse: false
  };
  const saved = await save(
    installationId,
    "integrated_consent_request_6001",
    1,
    selections
  );
  const firstActor = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(saved.confirmation)
    }),
    ["raw_source_collection", "automatic_reporting"],
    PRIMARY_BINDING
  );
  assert.equal(firstActor.error, undefined);

  const otherActor = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(saved.confirmation)
    }),
    ["raw_source_collection", "automatic_reporting"],
    SECONDARY_BINDING
  );
  assert.equal(otherActor.error?.status, 409);

  const reconfirmed = await save(
    installationId,
    "integrated_consent_request_6002",
    1,
    selections,
    secondaryCookie
  );
  const rebound = await authorizeIntegratedConsentRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      headers: receiptHeaders(reconfirmed.confirmation)
    }),
    ["raw_source_collection", "automatic_reporting"],
    SECONDARY_BINDING
  );
  assert.equal(rebound.error, undefined);
});

test("account switch hides local consent and commits an actor successor only after Backend success", async () => {
  const installationId = "consent-account-switch-installation-0001";
  const first = await save(
    installationId,
    "consent_account_switch_request_0001",
    1,
    {
      raw_source_collection: true,
      automatic_reporting: true,
      mobile_network_transfer: false,
      training_reuse: true
    }
  );
  assert.equal(first.status, 201);
  const currentUrl =
    "http://127.0.0.1:8081/privacy/rights" +
    "?control=integrated-consent" +
    `&installation_id=${installationId}` +
    `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`;
  const switchedRead = await handleGatewayRequest(new Request(currentUrl, {
    headers: {
      cookie: secondaryCookie,
      [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
    }
  }));
  assert.equal(switchedRead.status, 409);
  assert.equal(
    (await switchedRead.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_actor_reconsent_required"
  );

  const recordId = `${createHash("sha256")
    .update(`integrated-consent\0${installationId}`)
    .digest("hex")}.json`;
  const filePath = path.join(stateDirectory, "integrated-consent", recordId);
  const before = await readFile(filePath);
  const successorSelections = {
    raw_source_collection: false,
    automatic_reporting: false,
    mobile_network_transfer: false,
    training_reuse: false
  };
  const failedSuccessor = await handleGatewayRequest(
    consentPutRequest(
      installationId,
      "consent_account_switch_request_0002",
      1,
      successorSelections,
      secondaryCookie,
      first.confirmation.backend_consent_receipt_sha256
    ),
    {
      fetchImpl: async () => Response.json({
        detail: {
          code: "privacy_consent_previous_receipt_conflict",
          message: "The previous backend consent receipt does not match the latest event."
        }
      }, { status: 409 })
    }
  );
  assert.equal(failedSuccessor.status, 409);
  assert.deepEqual(await readFile(filePath), before);

  const successor = await save(
    installationId,
    "consent_account_switch_request_0002",
    1,
    successorSelections,
    secondaryCookie
  );
  assert.equal(successor.status, 201);
  const newActorRead = await handleGatewayRequest(new Request(currentUrl, {
    headers: {
      cookie: secondaryCookie,
      [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
    }
  }), {
    // S19-31: the plain GET confirms the Backend's latest receipt for the new actor.
    fetchImpl: backendAuthorityFetch(
      installationId,
      successor.confirmation.backend_consent_receipt_sha256,
      successorSelections
    )
  });
  assert.equal(newActorRead.status, 200);
  assert.deepEqual(
    (await newActorRead.json() as IntegratedConsentConfirmation).selections,
    successorSelections
  );
  const oldActorRead = await handleGatewayRequest(new Request(currentUrl, {
    headers: {
      cookie: primaryCookie,
      [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
    }
  }));
  assert.equal(oldActorRead.status, 409);
  assert.equal(
    (await oldActorRead.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_actor_reconsent_required"
  );
});

test("backend failure or malformed receipt never exposes a local confirmation", async () => {
  const selections = {
    raw_source_collection: true,
    automatic_reporting: false,
    mobile_network_transfer: false,
    training_reuse: false
  };
  const unavailableInstallation = "consent-backend-unavailable-installation";
  const unavailableRequest = "consent_backend_unavailable_request_0001";
  const unavailable = await handleGatewayRequest(
    consentPutRequest(
      unavailableInstallation,
      unavailableRequest,
      1,
      selections
    ),
    {
      fetchImpl: async () => Response.json({
        detail: {
          code: "privacy_lifecycle_store_unavailable",
          message: "Privacy lifecycle storage is unavailable."
        }
      }, { status: 503, headers: { "retry-after": "5" } })
    }
  );
  assert.equal(unavailable.status, 503);
  assert.equal(unavailable.headers.get("retry-after"), "5");
  assert.deepEqual(await unavailable.json(), {
    detail: {
      code: "privacy_lifecycle_store_unavailable",
      message: "Privacy lifecycle storage is unavailable."
    }
  });
  const unavailableRead = await handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/privacy/rights" +
        "?control=integrated-consent" +
        `&installation_id=${unavailableInstallation}` +
        `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
      {
        headers: {
          cookie: primaryCookie,
          [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
        }
      }
    )
  );
  assert.equal(unavailableRead.status, 404);

  for (const [suffix, mutate] of [
    ["fraction", (receipt: Record<string, unknown>) => {
      receipt.recorded_at = "2026-08-09T12:05:00.000Z";
    }],
    ["extra", (receipt: Record<string, unknown>) => {
      receipt.extra = true;
    }],
    ["hash", (receipt: Record<string, unknown>) => {
      receipt.receipt_sha256 = "A".repeat(64);
    }]
  ] as const) {
    const installationId = `consent-invalid-receipt-installation-${suffix}`;
    const requestId = `consent_invalid_receipt_request_${suffix}_0001`;
    const response = await handleGatewayRequest(
      consentPutRequest(installationId, requestId, 1, selections),
      {
        fetchImpl: async (_input, init) => {
          const event = JSON.parse(String(init?.body)) as {
            request_id: string;
            client_revision: number;
          };
          const receipt: Record<string, unknown> = {
            schema_version: "walksafe.privacy-consent-receipt.v2",
            request_id: event.request_id,
            client_revision: event.client_revision,
            receipt_sha256: "a".repeat(64),
            recorded_at: "2026-08-09T12:05:00Z"
          };
          mutate(receipt);
          return Response.json(receipt, { status: 201 });
        }
      }
    );
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), {
      detail: {
        code: "gateway_upstream_protocol_invalid",
        message: "The consent backend returned an invalid receipt."
      }
    });
    const hidden = await handleGatewayRequest(
      new Request(
        "http://127.0.0.1:8081/privacy/rights" +
          "?control=integrated-consent" +
          `&installation_id=${installationId}` +
          `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
        {
          headers: {
            cookie: primaryCookie,
            [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
          }
        }
      )
    );
    assert.equal(hidden.status, 404);
  }
});

test("Backend CAS failure leaves the local ledger byte-identical and retry CAS is bound", async () => {
  const installationId = "consent-backend-cas-installation-0001";
  const requestId = "consent_backend_cas_request_0002";
  const selections = {
    raw_source_collection: true,
    automatic_reporting: false,
    mobile_network_transfer: false,
    training_reuse: true
  };
  const first = await save(
    installationId,
    "consent_backend_cas_request_0001",
    1,
    selections
  );
  assert.equal(first.status, 201);
  const recordId = `${createHash("sha256")
    .update(`integrated-consent\0${installationId}`)
    .digest("hex")}.json`;
  const filePath = path.join(stateDirectory, "integrated-consent", recordId);
  const before = await readFile(filePath);
  const wrongExpected = "f".repeat(64);
  const rejected = await handleGatewayRequest(
    consentPutRequest(
      installationId,
      requestId,
      2,
      selections,
      primaryCookie,
      wrongExpected
    ),
    {
      fetchImpl: async () => Response.json({
        detail: {
          code: "privacy_consent_cas_conflict",
          message: "The expected previous consent receipt is stale."
        }
      }, { status: 409 })
    }
  );
  assert.equal(rejected.status, 409);
  assert.deepEqual(await readFile(filePath), before);

  const accepted = await save(installationId, requestId, 2, selections);
  assert.equal(accepted.status, 201);
  const state = decryptTestStateFile<{
    schema_version: number;
    events: Array<{
      request_id: string;
      expected_previous_backend_receipt_sha256?: string | null;
    }>;
  }>(
    { kind: "integrated-consent", recordId },
    await readFile(filePath, "utf8"),
    INTEGRATED_CONSENT_MAX_STATE_BYTES
  );
  assert.equal(state.schema_version, 6);
  assert.equal(state.events.at(-1)?.request_id, requestId);
  assert.equal(
    state.events.at(-1)?.expected_previous_backend_receipt_sha256,
    first.confirmation.backend_consent_receipt_sha256
  );

  let backendCalls = 0;
  const conflictingReplay = await handleGatewayRequest(
    consentPutRequest(
      installationId,
      requestId,
      2,
      selections,
      primaryCookie,
      wrongExpected
    ),
    {
      fetchImpl: async () => {
        backendCalls += 1;
        throw new Error("conflicting replay must not reach Backend");
      }
    }
  );
  assert.equal(conflictingReplay.status, 409);
  assert.equal(
    (await conflictingReplay.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_request_conflict"
  );
  assert.equal(backendCalls, 0);
});

test("idempotent replay fails closed when Backend changes its authoritative receipt", async () => {
  const installationId = "consent-backend-replay-mismatch-installation";
  const requestId = "consent_backend_replay_mismatch_request_0001";
  const selections = {
    raw_source_collection: true,
    automatic_reporting: false,
    mobile_network_transfer: false,
    training_reuse: false
  };
  let replay = false;
  const fetchImpl: GatewayFetch = async (_input, init) => {
    const event = JSON.parse(String(init?.body)) as {
      request_id: string;
      client_revision: number;
    };
    return Response.json({
      schema_version: "walksafe.privacy-consent-receipt.v2",
      request_id: event.request_id,
      client_revision: event.client_revision,
      receipt_sha256: (replay ? "b" : "a").repeat(64),
      recorded_at: "2026-08-09T12:05:00Z"
    }, { status: replay ? 200 : 201 });
  };
  const first = await handleGatewayRequest(
    consentPutRequest(installationId, requestId, 1, selections),
    { fetchImpl }
  );
  assert.equal(first.status, 201);
  replay = true;
  const second = await handleGatewayRequest(
    consentPutRequest(installationId, requestId, 1, selections),
    { fetchImpl }
  );
  assert.equal(second.status, 502);
  assert.equal(
    (await second.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_backend_receipt_mismatch"
  );
});

test("audit-chain corruption of the bound Backend receipt fails closed", async () => {
  const installationId = "consent-audit-corruption-installation";
  const saved = await save(
    installationId,
    "consent_audit_corruption_request_0001",
    1,
    {
      raw_source_collection: true,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: false
    }
  );
  assert.equal(saved.status, 201);
  const recordId = `${createHash("sha256")
    .update(`integrated-consent\0${installationId}`)
    .digest("hex")}.json`;
  const filePath = path.join(stateDirectory, "integrated-consent", recordId);
  const state = decryptTestStateFile<{
    events: Array<{ backend_consent_receipt_sha256: string }>;
  }>(
    { kind: "integrated-consent", recordId },
    await readFile(filePath, "utf8"),
    INTEGRATED_CONSENT_MAX_STATE_BYTES
  );
  state.events.at(-1)!.backend_consent_receipt_sha256 = "c".repeat(64);
  await writeFile(
    filePath,
    encryptTestStateFile(
      { kind: "integrated-consent", recordId },
      state,
      INTEGRATED_CONSENT_MAX_STATE_BYTES
    )
  );
  const response = await handleGatewayRequest(new Request(
    "http://127.0.0.1:8081/privacy/rights" +
      "?control=integrated-consent" +
      `&installation_id=${installationId}` +
      `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
    {
      headers: {
        cookie: primaryCookie,
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
      }
    }
  ));
  assert.equal(response.status, 503);
});

test("legacy schema v3 local receipt is readable only as reconsent-required", async () => {
  const installationId = "consent-legacy-v3-installation";
  const eventWithoutReceipt = {
    request_id: "consent_legacy_v3_request_0001",
    policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
    item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
    client_revision: 1,
    revision: 1,
    selections: {
      raw_source_collection: true,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: false
    },
    confirmed_at: "2026-08-09T12:05:00.000Z",
    previous_receipt_sha256: null
  };
  const receipt = createHash("sha256").update(canonicalJson({
    installation_id: installationId,
    ...eventWithoutReceipt
  })).digest("hex");
  const recordId = `${createHash("sha256")
    .update(`integrated-consent\0${installationId}`)
    .digest("hex")}.json`;
  const directory = path.join(stateDirectory, "integrated-consent");
  await mkdir(directory, { recursive: true, mode: 0o700 });
  await writeFile(
    path.join(directory, recordId),
    encryptTestStateFile(
      { kind: "integrated-consent", recordId },
      {
        schema_version: 3,
        installation_id: installationId,
        control_secret_sha256: createHash("sha256")
          .update(`integrated-consent-control\0${CONTROL_SECRET}`)
          .digest("hex"),
        field_actor_binding: null,
        events: [{ ...eventWithoutReceipt, receipt_sha256: receipt }]
      },
      INTEGRATED_CONSENT_MAX_STATE_BYTES
    ),
    { mode: 0o600 }
  );
  const response = await handleGatewayRequest(new Request(
    "http://127.0.0.1:8081/privacy/rights" +
      "?control=integrated-consent" +
      `&installation_id=${installationId}` +
      `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
    {
      headers: {
        cookie: primaryCookie,
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
      }
    }
  ));
  assert.equal(response.status, 409);
  assert.equal(
    (await response.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_reconsent_required"
  );
});

test("old-policy schema v4 GET requires reconsent and current PUT appends floor plus one", async () => {
  const installationId = "consent-old-policy-v4-installation";
  const actorSha256 = createHash("sha256")
    .update(`integrated-consent-field-actor\0${PRIMARY_BINDING}`)
    .digest("hex");
  const oldBackendReceipt = "9".repeat(64);
  const eventWithoutAudit = {
    request_id: "consent_old_policy_v4_request_0001",
    policy_version: "FP-013-1.0.0",
    item_versions: {
      raw_source_collection: "FP-013-RAW-1.0.0",
      automatic_reporting: "FP-013-AUTO-1.0.0",
      mobile_network_transfer: "FP-013-MOBILE-1.0.0",
      training_reuse: "FP-013-TRAINING-1.0.0"
    },
    client_revision: 1,
    revision: 1,
    selections: {
      raw_source_collection: true,
      automatic_reporting: false,
      mobile_network_transfer: false,
      training_reuse: false
    },
    confirmed_at: "2026-08-09T12:05:00.000Z",
    previous_gateway_audit_record_sha256: null,
    backend_consent_receipt_sha256: oldBackendReceipt,
    backend_recorded_at: "2026-08-09T12:05:00Z",
    field_actor_binding_sha256: actorSha256
  };
  const gatewayAudit = createHash("sha256").update(canonicalJson({
    installation_id: installationId,
    ...eventWithoutAudit
  })).digest("hex");
  const recordId = `${createHash("sha256")
    .update(`integrated-consent\0${installationId}`)
    .digest("hex")}.json`;
  const directory = path.join(stateDirectory, "integrated-consent");
  await mkdir(directory, { recursive: true, mode: 0o700 });
  await writeFile(
    path.join(directory, recordId),
    encryptTestStateFile(
      { kind: "integrated-consent", recordId },
      {
        schema_version: 4,
        installation_id: installationId,
        control_secret_sha256: createHash("sha256")
          .update(`integrated-consent-control\0${CONTROL_SECRET}`)
          .digest("hex"),
        field_actor_binding: {
          actor_sha256: actorSha256,
          consent_revision: 1,
          bound_at: "2026-08-09T12:05:00.000Z"
        },
        events: [{
          ...eventWithoutAudit,
          gateway_audit_record_sha256: gatewayAudit
        }]
      },
      INTEGRATED_CONSENT_MAX_STATE_BYTES
    ),
    { mode: 0o600 }
  );

  const oldRead = await handleGatewayRequest(new Request(
    "http://127.0.0.1:8081/privacy/rights" +
      "?control=integrated-consent" +
      `&installation_id=${installationId}` +
      `&policy_version=${INTEGRATED_CONSENT_POLICY_VERSION}`,
    {
      headers: {
        cookie: primaryCookie,
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
      }
    }
  ));
  assert.equal(oldRead.status, 409);
  assert.equal(
    (await oldRead.json() as { detail: { code: string } }).detail.code,
    "integrated_consent_reconsent_required"
  );

  const updatedResponse = await handleGatewayRequest(
    consentPutRequest(
      installationId,
      "consent_old_policy_v4_request_0002",
      2,
      eventWithoutAudit.selections,
      primaryCookie,
      oldBackendReceipt
    ),
    { fetchImpl: consentBackendFetch }
  );
  assert.equal(updatedResponse.status, 201);
  const updated = await updatedResponse.json() as IntegratedConsentConfirmation;
  assert.equal(updated.client_revision, 2);
  assert.equal(updated.revision, 2);
  assert.equal(updated.policy_version, INTEGRATED_CONSENT_POLICY_VERSION);
});

test("installation key registration requires the same actor and installation control secret", async () => {
  const installation = "installation_key_binding_test";
  const saved = await save(installation, "installation_key_consent_request_01", 1, {
    raw_source_collection: false, automatic_reporting: false, mobile_network_transfer: false, training_reuse: false
  });
  assert.equal(saved.status, 201);
  let calls = 0;
  const fetchImpl: GatewayFetch = async (url, init) => {
    calls++;
    assert.equal(String(url), "http://127.0.0.1:8000/privacy/installation-evidence-key");
    assert.equal(new Headers(init?.headers).get(ACTOR_ID_HEADER), PRIMARY_ACTOR);
    assert.equal(new Headers(init?.headers).get(ACCOUNT_GENERATION_HEADER), "1");
    assert.ok(new Headers(init?.headers).get(ACTOR_ASSERTION_HEADER));
    return new Response(null, {status: 204});
  };
  function request(cookie: string, secret: string, id = installation): Request {
    return new Request("http://localhost/privacy/rights?control=installation-evidence-key", {
      method: "PUT", headers: {cookie, "content-type": "application/json", [CONSENT_CONTROL_SECRET_HEADER]: secret},
      body: JSON.stringify({installation_id: id, public_key: "a".repeat(122)})
    });
  }
  assert.equal((await handleGatewayRequest(request(primaryCookie, CONTROL_SECRET), {fetchImpl})).status, 204);
  assert.notEqual((await handleGatewayRequest(request(primaryCookie, "b".repeat(64)), {fetchImpl})).status, 204);
  assert.notEqual((await handleGatewayRequest(request(secondaryCookie, CONTROL_SECRET), {fetchImpl})).status, 204);
  assert.notEqual((await handleGatewayRequest(request(primaryCookie, CONTROL_SECRET, "unconfirmed_installation"), {fetchImpl})).status, 204);
  assert.equal(calls, 1);
});
