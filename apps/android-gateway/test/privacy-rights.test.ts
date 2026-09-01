import assert from "node:assert/strict";
import { execFile, spawn, type ChildProcess } from "node:child_process";
import { readFile, rm, stat, mkdtemp, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";
import { promisify } from "node:util";

import {
  ACCOUNT_DELETION_REQUEST_SCHEMA,
  ACCOUNT_DELETION_SECRET_HEADER,
  ACCOUNT_DELETION_INVENTORY,
  PrivacyRightsLedgerError,
  acceptAccountDeletionRequest,
  accountDeletionStatus,
  activateActorGeneration,
  assertPrivacyLedgerSecurityForTests,
  currentActorGeneration,
  transitionAccountDeletionItem
} from "../src/privacy-rights.js";
import {
  CONSENT_CONTROL_SECRET_HEADER,
  CONSENT_INSTALLATION_HEADER,
  CONSENT_NETWORK_TRANSPORT_HEADER,
  CONSENT_POLICY_HEADER,
  CONSENT_RECEIPT_HEADER,
  CONSENT_REVISION_HEADER,
  INTEGRATED_CONSENT_ITEM_VERSIONS,
  INTEGRATED_CONSENT_POLICY_VERSION,
  type IntegratedConsentConfirmation
} from "../src/integrated-consent.js";
import { handleGatewayRequest } from "../src/routes.js";
import type { GatewayFetch } from "../src/backend.js";
import {
  configureTestStateEncryption,
  decryptTestStateFile,
  encryptTestStateFile
} from "./state-encryption-fixture.js";

const ACTOR = "privacy-tester";
const RACE_ACTOR = "privacy-race-tester";
const TOKEN = "privacy-test-token-that-is-long-enough";
const RACE_TOKEN = "privacy-race-token-that-is-long-enough";
const SECRET = "a".repeat(64);
const RACE_SECRET = "b".repeat(64);
const CONSENT_SECRET = "f".repeat(64);
const PRIVACY_LEDGER_MAX_PLAINTEXT_BYTES = 16 * 1024 * 1024;
let stateDirectory = "";
let clientCounter = 20;
const execFileAsync = promisify(execFile);

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-privacy-test-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_GATEWAY_SESSION_SECRET: "d".repeat(64),
    WALKSAFE_FIELD_TEST_TOKEN: "privacy-internal-test-token-only",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([
      { actor_id: ACTOR, token: TOKEN },
      { actor_id: RACE_ACTOR, token: RACE_TOKEN }
    ])
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

function nextIp(): string {
  clientCounter += 1;
  return `198.51.100.${clientCounter}`;
}

async function login(actorId: string, token: string): Promise<string> {
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": nextIp()
      },
      body: JSON.stringify({ actor_id: actorId, token })
    })
  );
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { session_scope: "general" });
  const cookie = response.headers.get("set-cookie");
  assert.ok(cookie);
  return cookie.split(";", 1)[0]!;
}

function deletionBody(requestId: string, clientRevision = 1): string {
  return JSON.stringify({
    schema_version: ACCOUNT_DELETION_REQUEST_SCHEMA,
    request_id: requestId,
    client_revision: clientRevision,
    confirmation: "DELETE_MY_ACCOUNT"
  });
}

async function submitDeletion(
  requestId: string,
  secret: string,
  cookie: string | null,
  clientRevision = 1
): Promise<Response> {
  const headers: Record<string, string> = {
    "content-type": "application/json",
    [ACCOUNT_DELETION_SECRET_HEADER]: secret
  };
  if (cookie) headers.cookie = cookie;
  return handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/privacy/rights?control=account-deletion",
      {
        method: "POST",
        headers,
        body: deletionBody(requestId, clientRevision)
      }
    )
  );
}

test("retired v1 route is explicit while the legacy ledger remains readable", async () => {
  const cookie = await login(ACTOR, TOKEN);
  const requestId = "privacy_deletion_request_0001";
  const retired = await submitDeletion(requestId, SECRET, cookie);
  assert.equal(retired.status, 410);
  assert.deepEqual(await retired.json(), {
    code: "account_deletion_v1_retired",
    replacement: "/privacy/account-deletions"
  });
  const accepted = acceptAccountDeletionRequest(
    ACTOR,
    {
      schema_version: ACCOUNT_DELETION_REQUEST_SCHEMA,
      request_id: requestId,
      client_revision: 1,
      confirmation: "DELETE_MY_ACCOUNT"
    },
    SECRET
  );
  assert.equal(accepted.kind, "accepted");
  if (accepted.kind !== "accepted") throw new Error("legacy deletion was not seeded");
  const status = accepted.status;
  assert.equal(status.account_generation, 1);
  assert.equal(status.overall_status, "PROCESSING");
  assert.equal(status.items.length, 9);
  assert.deepEqual(
    status.items.map((item) => item.key),
    ACCOUNT_DELETION_INVENTORY.map((item) => item.key)
  );
  assert.ok(status.items.every((item) => item.status === "EXTERNAL_PENDING"));

  const replay = acceptAccountDeletionRequest(
    ACTOR,
    {
      schema_version: ACCOUNT_DELETION_REQUEST_SCHEMA,
      request_id: requestId,
      client_revision: 1,
      confirmation: "DELETE_MY_ACCOUNT"
    },
    SECRET
  );
  assert.equal(replay.kind, "replay");
  if (replay.kind !== "replay") throw new Error("legacy replay failed");
  assert.deepEqual(replay.status, status);
  assert.deepEqual(accountDeletionStatus(requestId, SECRET), status);
  assert.equal(accountDeletionStatus(requestId, "c".repeat(64)), null);

  let upstreamCalls = 0;
  const blocked = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/walking", {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: "{}"
    }),
    {
      fetchImpl: async () => {
        upstreamCalls += 1;
        return Response.json({});
      }
    }
  );
  assert.ok(blocked.status === 401 || blocked.status === 409);
  assert.equal(upstreamCalls, 0);

  const relogin = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": nextIp()
      },
      body: JSON.stringify({ actor_id: ACTOR, token: TOKEN })
    })
  );
  assert.equal(relogin.status, 409);

  const ledgerFile = path.join(stateDirectory, "privacy-rights", "ledger.json");
  const ledgerText = await readFile(ledgerFile, "utf8");
  assert.equal(ledgerText.includes(SECRET), false);
  assert.equal((await stat(ledgerFile)).mode & 0o077, 0);
  assertPrivacyLedgerSecurityForTests();
});

test("item transitions use secret authorization, operation idempotency, and revision CAS", async () => {
  const retryAfter = new Date(Date.now() + 60_000).toISOString();
  const first = transitionAccountDeletionItem({
    requestId: "privacy_deletion_request_0001",
    statusSecret: SECRET,
    itemKey: "server_originals",
    operationId: "privacy_transition_operation_0001",
    expectedRequestRevision: 1,
    expectedItemRevision: 1,
    nextStatus: "RETRY_WAIT",
    retryAfter
  });
  assert.equal(first.request_revision, 2);
  assert.equal(first.overall_status, "RETRY_WAIT");

  const replay = transitionAccountDeletionItem({
    requestId: "privacy_deletion_request_0001",
    statusSecret: SECRET,
    itemKey: "server_originals",
    operationId: "privacy_transition_operation_0001",
    expectedRequestRevision: 1,
    expectedItemRevision: 1,
    nextStatus: "RETRY_WAIT",
    retryAfter
  });
  assert.deepEqual(replay, first);

  assert.throws(
    () => transitionAccountDeletionItem({
      requestId: "privacy_deletion_request_0001",
      statusSecret: SECRET,
      itemKey: "server_originals",
      operationId: "privacy_transition_operation_0002",
      expectedRequestRevision: 1,
      expectedItemRevision: 1,
      nextStatus: "COMPLETED",
      evidenceRef: "evidence://durable-proof"
    }),
    (error: unknown) =>
      error instanceof PrivacyRightsLedgerError && error.code === "conflict"
  );

  const privacyDirectory = path.join(stateDirectory, "privacy-rights");
  const ledgerFile = path.join(privacyDirectory, "ledger.json");
  const beforeInvalidTransitions = await readFile(ledgerFile, "utf8");
  for (const invalid of [
    {
      nextStatus: "LEGAL_HOLD" as const,
      operationId: "privacy_invalid_transition_legal",
      restrictionReason: "court order"
    },
    {
      nextStatus: "RETRY_WAIT" as const,
      operationId: "privacy_invalid_transition_retry"
    },
    {
      nextStatus: "COMPLETED" as const,
      operationId: "privacy_invalid_transition_terminal",
      evidenceRef: "not-a-sha256",
      terminalAt: new Date().toISOString()
    }
  ]) {
    assert.throws(
      () => transitionAccountDeletionItem({
        requestId: "privacy_deletion_request_0001",
        statusSecret: SECRET,
        itemKey: "server_copies",
        expectedRequestRevision: 2,
        expectedItemRevision: 1,
        ...invalid
      }),
      (error: unknown) =>
        error instanceof PrivacyRightsLedgerError && error.code === "conflict"
    );
  }
  assert.equal(await readFile(ledgerFile, "utf8"), beforeInvalidTransitions);

  const moduleUrl = new URL("../src/privacy-rights.js", import.meta.url).href;
  const childExit = (
    child: ChildProcess
  ): Promise<{ code: number | null; signal: NodeJS.Signals | null }> =>
    new Promise((resolve, reject) => {
      child.once("error", reject);
      child.once("exit", (code, signal) => resolve({ code, signal }));
    });
  const immediateCrash = spawn(
    process.execPath,
    [
      "--input-type=module",
      "--eval",
      `const m=await import(${JSON.stringify(moduleUrl)});m.crashAfterAcquiringPrivacyLedgerLockForTests();`
    ],
    { env: process.env, stdio: ["ignore", "ignore", "pipe"] }
  );
  assert.equal((await childExit(immediateCrash)).signal, "SIGKILL");
  await execFileAsync(
    process.execPath,
    [
      "--input-type=module",
      "--eval",
      `const m=await import(${JSON.stringify(moduleUrl)});` +
        `if(m.activateActorGeneration("privacy-after-acquire-crash")!==1)process.exit(2);`
    ],
    { env: process.env }
  );

  const criticalCrash = spawn(
    process.execPath,
    [
      "--input-type=module",
      "--eval",
      `const m=await import(${JSON.stringify(moduleUrl)});m.holdPrivacyLedgerFlockForTests();`
    ],
    { env: process.env, stdio: ["ignore", "pipe", "pipe"] }
  );
  const criticalExit = childExit(criticalCrash);
  await new Promise<void>((resolve, reject) => {
    let ready = "";
    let stderr = "";
    criticalCrash.stdout.setEncoding("utf8");
    criticalCrash.stderr.setEncoding("utf8");
    criticalCrash.stderr.on("data", (chunk: string) => {
      stderr += chunk;
    });
    criticalCrash.stdout.on("data", (chunk: string) => {
      ready += chunk;
      if (ready.includes("privacy-ledger-flock-ready")) resolve();
    });
    criticalCrash.once("error", reject);
    criticalCrash.once("exit", (code, signal) => {
      if (!ready.includes("privacy-ledger-flock-ready")) {
        reject(new Error(`critical lock holder exited ${code}/${signal}: ${stderr}`));
      }
    });
  });
  criticalCrash.kill("SIGKILL");
  assert.equal((await criticalExit).signal, "SIGKILL");
  await execFileAsync(
    process.execPath,
    [
      "--input-type=module",
      "--eval",
      `const m=await import(${JSON.stringify(moduleUrl)});` +
        `if(m.activateActorGeneration("privacy-after-critical-crash")!==1)process.exit(2);`
    ],
    { env: process.env }
  );

  const racers = Array.from({ length: 8 }, (_, index) => {
    const actorId = `privacy-lock-racer-${index}`;
    return execFileAsync(
      process.execPath,
      [
        "--input-type=module",
        "--eval",
        `const m=await import(${JSON.stringify(moduleUrl)});` +
          `const deadline=Date.now()+10000;` +
          `for(;;){try{` +
          `if(m.activateActorGeneration(${JSON.stringify(actorId)})!==1)process.exit(2);` +
          `break;` +
          `}catch(error){` +
          `if(!(error instanceof m.PrivacyRightsLedgerError)||` +
          `error.code!=="busy"||Date.now()>=deadline)throw error;` +
          `await new Promise(resolve=>setTimeout(resolve,25));` +
          `}}`
      ],
      { env: process.env }
    );
  });
  const racerOutcomes = await Promise.allSettled(racers);
  const rejectedRacer = racerOutcomes.find(
    (outcome): outcome is PromiseRejectedResult => outcome.status === "rejected"
  );
  if (rejectedRacer) throw rejectedRacer.reason;
  for (let index = 0; index < racers.length; index += 1) {
    assert.equal(currentActorGeneration(`privacy-lock-racer-${index}`), 1);
  }
  assertPrivacyLedgerSecurityForTests();

  const originalLedger = await readFile(ledgerFile, "utf8");
  try {
    const tampered = decryptTestStateFile<{
      requests: Record<string, { items: Array<{ status: string }> }>;
    }>(
      { kind: "privacy-rights-ledger", recordId: path.basename(ledgerFile) },
      originalLedger,
      PRIVACY_LEDGER_MAX_PLAINTEXT_BYTES
    );
    tampered.requests["privacy_deletion_request_0001"]!.items[0]!.status = "FAILED";
    await writeFile(
      ledgerFile,
      encryptTestStateFile(
        { kind: "privacy-rights-ledger", recordId: path.basename(ledgerFile) },
        tampered,
        PRIVACY_LEDGER_MAX_PLAINTEXT_BYTES
      ),
      "utf8"
    );
    assert.throws(
      () => accountDeletionStatus("privacy_deletion_request_0001", SECRET),
      (error: unknown) =>
        error instanceof PrivacyRightsLedgerError && error.code === "integrity"
    );
  } finally {
    await writeFile(ledgerFile, originalLedger, "utf8");
  }
  assertPrivacyLedgerSecurityForTests();
});

test("tombstone rejects old access and refresh before replay retries revocation", async () => {
  Object.assign(process.env, {
    WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "true",
    WALKSAFE_FIELD_ACCESS_TTL_SECONDS: "900",
    WALKSAFE_FIELD_REFRESH_IDLE_TTL_SECONDS: "2592000",
    WALKSAFE_FIELD_REFRESH_ABSOLUTE_TTL_SECONDS: "7776000"
  });
  const loginResponse = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": nextIp()
      },
      body: JSON.stringify({
        actor_id: RACE_ACTOR,
        token: RACE_TOKEN,
        device_id: "privacy-race-device-01"
      })
    })
  );
  assert.equal(loginResponse.status, 200);
  const setCookie = loginResponse.headers.get("set-cookie");
  assert.ok(setCookie);
  const cookie = setCookie.split(";", 1)[0]!;
  const credentials = await loginResponse.json() as {
    actor_id: string;
    device_id: string;
    family_id: string;
    rotation: number;
    refresh_token: string;
  };
  const installationId = "b5aa8a4b-04c2-42d2-916c-98ff8522ac91";
  const consentResponse = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/privacy/rights?control=integrated-consent", {
      method: "PUT",
      headers: {
        cookie,
        "content-type": "application/json",
        [CONSENT_CONTROL_SECRET_HEADER]: CONSENT_SECRET
      },
      body: JSON.stringify({
        schema_version: "walksafe.integrated-consent-request.v1",
        installation_id: installationId,
        request_id: "privacy_epoch_consent_request_0001",
        policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision: 1,
        expected_previous_backend_receipt_sha256: null,
        selections: {
          raw_source_collection: false,
          automatic_reporting: false,
          mobile_network_transfer: false,
          training_reuse: false
        }
      })
    }),
    {
      fetchImpl: async (input, init) => {
        assert.equal(
          String(input),
          "http://127.0.0.1:8000/privacy/consent-events"
        );
        const event = JSON.parse(String(init?.body)) as {
          request_id: string;
          client_revision: number;
        };
        return Response.json({
          schema_version: "walksafe.privacy-consent-receipt.v2",
          request_id: event.request_id,
          client_revision: event.client_revision,
          receipt_sha256: "e".repeat(64),
          recorded_at: "2026-08-09T12:05:00Z"
        }, { status: 201 });
      }
    }
  );
  assert.equal(consentResponse.status, 201);
  const consent = await consentResponse.json() as IntegratedConsentConfirmation;
  const reportHeaders = {
    cookie,
    "cf-connecting-ip": nextIp(),
    [CONSENT_INSTALLATION_HEADER]: installationId,
    [CONSENT_CONTROL_SECRET_HEADER]: CONSENT_SECRET,
    [CONSENT_NETWORK_TRANSPORT_HEADER]: "wifi",
    [CONSENT_POLICY_HEADER]: consent.policy_version,
    [CONSENT_REVISION_HEADER]: String(consent.revision),
    [CONSENT_RECEIPT_HEADER]: consent.backend_consent_receipt_sha256
  };
  const reportForm = (autoReported: boolean): FormData => {
    const value = new FormData();
    value.set(
      "metadata",
      JSON.stringify({ source: "android", auto_reported: autoReported })
    );
    value.set(
      "image",
      new Blob([new Uint8Array([0xff, 0xd8, 0xff])], { type: "image/jpeg" }),
      "report.jpg"
    );
    return value;
  };
  let preconditionFetches = 0;
  const automaticDenied = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      method: "POST",
      headers: {
        ...reportHeaders,
        "x-walksafe-report-purpose": "automatic"
      },
      body: reportForm(true)
    }),
    {
      fetchImpl: async () => {
        preconditionFetches += 1;
        return Response.json({});
      }
    }
  );
  assert.equal(automaticDenied.status, 403);
  const mismatchDenied = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      method: "POST",
      headers: {
        ...reportHeaders,
        "cf-connecting-ip": nextIp(),
        "x-walksafe-report-purpose": "explicit"
      },
      body: reportForm(true)
    }),
    {
      fetchImpl: async () => {
        preconditionFetches += 1;
        return Response.json({});
      }
    }
  );
  assert.equal(mismatchDenied.status, 422);
  assert.equal(preconditionFetches, 0);
  let startedResolve: (() => void) | undefined;
  const started = new Promise<void>((resolve) => {
    startedResolve = resolve;
  });
  let upstreamAborted = false;
  const fetchImpl: GatewayFetch = async (_input, init) => {
    startedResolve?.();
    return await new Promise<Response>((resolve) => {
      init?.signal?.addEventListener("abort", () => {
        upstreamAborted = true;
        resolve(Response.json({ stale: true }));
      }, { once: true });
    });
  };
  const reporting = handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/reports/v2", {
      method: "POST",
      headers: {
        ...reportHeaders,
        "cf-connecting-ip": nextIp(),
        "x-walksafe-report-purpose": "explicit"
      },
      body: reportForm(false)
    }),
    { fetchImpl }
  );
  await started;

  const requestId = "privacy_deletion_request_0002";
  const accepted = acceptAccountDeletionRequest(
    RACE_ACTOR,
    {
      schema_version: ACCOUNT_DELETION_REQUEST_SCHEMA,
      request_id: requestId,
      client_revision: 1,
      confirmation: "DELETE_MY_ACCOUNT"
    },
    RACE_SECRET
  );
  assert.equal(accepted.kind, "accepted");
  const stale = await reporting;
  assert.equal(stale.status, 409);
  assert.equal(upstreamAborted, true);

  const oldStatus = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      headers: { cookie }
    })
  );
  assert.equal(oldStatus.status, 409);

  const oldRefresh = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": nextIp()
      },
      body: JSON.stringify({
        grant_type: "refresh_token",
        actor_id: credentials.actor_id,
        device_id: credentials.device_id,
        family_id: credentials.family_id,
        rotation: credentials.rotation,
        refresh_token: credentials.refresh_token
      })
    })
  );
  assert.equal(oldRefresh.status, 409);

  const recovered = await submitDeletion(requestId, RACE_SECRET, null);
  assert.equal(recovered.status, 410);
});
