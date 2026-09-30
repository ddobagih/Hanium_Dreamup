import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import {
  gatewayFieldLongSessionBinding,
  type GatewayFieldLongSessionBinding
} from "../src/auth.js";
import {
  RAW_CONSENT_RECEIPT_SHA256_HEADER,
  RAW_MANIFEST_SHA256_HEADER,
  RAW_PURPOSE_HEADER,
  RAW_WALK_ID_HEADER,
  type GatewayFetch
} from "../src/backend.js";
import * as ledger from "../src/field-walk-ledger.js";
import {
  commandFieldWalk,
  parseFieldWalkCommand
} from "../src/field-walk-ledger.js";
import {
  CONSENT_CONTROL_SECRET_HEADER,
  CONSENT_INSTALLATION_HEADER,
  CONSENT_NETWORK_TRANSPORT_HEADER,
  CONSENT_POLICY_HEADER,
  CONSENT_REVISION_HEADER,
  INTEGRATED_CONSENT_ITEM_VERSIONS,
  INTEGRATED_CONSENT_POLICY_VERSION,
  type IntegratedConsentConfirmation
} from "../src/integrated-consent.js";
import { resetRawCollectionRelayForTests } from "../src/raw-collection-relay.js";
import { handleGatewayRequest } from "../src/routes.js";
import {
  configureTestStateEncryption,
  decryptTestStateFile
} from "./state-encryption-fixture.js";

/*
 * Owner decision 3(b) (2026-09-29, sweep/owner-decisions-20260929.md): RAW segments and queued
 * reports are sent only after the walk has ended. The Gateway admits a RAW write for a walk that
 * this account and device held and that ended within the phone's 30-day RAW retention, under the
 * walk's original id, and refuses every write while any walk of the account is active.
 */

type TestClock = { wallEpochMs: () => number; monotonicMs: () => number };
const hooks = ledger as unknown as {
  setFieldWalkLedgerTestClock?: (clock: TestClock | null) => void;
  FIELD_WALK_ENDED_HISTORY_MAX_PER_ACCOUNT?: number;
};

const SESSION_SECRET = "raw-ended-walk-session-secret-1234567890123456789012345";
const INTERNAL_TOKEN = "raw-ended-walk-internal-token-12345678901234567890";
const ACTOR_ID = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3240";
const DEVICE_ID = "android-raw-ended-device";
const INSTALLATION_ID = "raw-ended-installation-0001";
const CONTROL_SECRET = "d".repeat(64);
const DAY_MS = 24 * 60 * 60 * 1000;
const clock = { wall: Date.now(), mono: 1_000_000 };

let stateDirectory = "";
let sessionCookie = "";
let binding: GatewayFieldLongSessionBinding | null = null;
let confirmation: IntegratedConsentConfirmation;
let requestSequence = 0;

function walkId(n: number): string {
  return `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
}

function advance(ms: number): void {
  clock.wall += ms;
  clock.mono += ms;
}

function nextRequestId(prefix: string): string {
  requestSequence += 1;
  return `${prefix}-${String(requestSequence).padStart(6, "0")}`;
}

function start(holder: GatewayFieldLongSessionBinding, walk: string) {
  const result = commandFieldWalk(holder, parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1",
    request_id: nextRequestId("raw-ended-start"),
    action: "start",
    walk_id: walk
  })!);
  assert.equal(result.status, 200);
  return { leaseId: String(result.body.lease_id), fence: Number(result.body.fencing_token) };
}

function end(holder: GatewayFieldLongSessionBinding, walk: string, lease: { leaseId: string; fence: number }) {
  const result = commandFieldWalk(holder, parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1",
    request_id: nextRequestId("raw-ended-end"),
    action: "end",
    walk_id: walk,
    lease_id: lease.leaseId,
    fencing_token: lease.fence
  })!);
  assert.equal(result.status, 200);
  assert.equal(result.body.result, "ENDED");
}

function manifestRequest(walk: string): Request {
  const body = "{}";
  return new Request(
    `https://gateway.invalid/api/raw-collections/${walkId(9_999)}/manifest`,
    {
      method: "PUT",
      headers: {
        "content-type": "application/json",
        "content-length": String(Buffer.byteLength(body)),
        cookie: sessionCookie,
        [RAW_PURPOSE_HEADER]: "GENERAL_RAW",
        [RAW_WALK_ID_HEADER]: walk,
        [RAW_MANIFEST_SHA256_HEADER]: "a".repeat(64),
        [RAW_CONSENT_RECEIPT_SHA256_HEADER]: confirmation.backend_consent_receipt_sha256,
        [CONSENT_INSTALLATION_HEADER]: INSTALLATION_ID,
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET,
        [CONSENT_POLICY_HEADER]: confirmation.policy_version,
        [CONSENT_REVISION_HEADER]: String(confirmation.revision),
        [CONSENT_NETWORK_TRANSPORT_HEADER]: "wifi"
      },
      body
    }
  );
}

/** Sends a manifest write; the upstream HEAD preflight answers 503 so no body is ever relayed. */
async function manifestWrite(walk: string): Promise<{
  status: number;
  code: string | null;
  upstreamCalls: number;
  bodyUsed: boolean;
}> {
  resetRawCollectionRelayForTests();
  let upstreamCalls = 0;
  const request = manifestRequest(walk);
  const fetchImpl: GatewayFetch = async (input, init) => {
    if (new URL(String(input)).pathname === "/accounts/session-current") {
      return new Response(null, { status: 204 });
    }
    upstreamCalls += 1;
    assert.equal(init?.method, "HEAD");
    assert.equal(new Headers(init?.headers).get(RAW_WALK_ID_HEADER), walk);
    return Response.json({ detail: { code: "raw_ingest_disabled", message: "off" } }, { status: 503 });
  };
  const response = await handleGatewayRequest(request, {
    fieldLongSessionBindingResolver: () => (binding ? { ...binding } : null),
    fetchImpl
  });
  const payload = await response.json() as { detail?: { code?: string } };
  return {
    status: response.status,
    code: payload.detail?.code ?? null,
    upstreamCalls,
    bodyUsed: request.bodyUsed
  };
}

function assertAdmitted(result: Awaited<ReturnType<typeof manifestWrite>>): void {
  // Admitted by every Gateway gate: the upstream preflight ran and its 503 was projected.
  assert.equal(result.upstreamCalls, 1);
  assert.equal(result.status, 503);
  assert.equal(result.code, "raw_ingest_disabled");
  assert.equal(result.bodyUsed, false);
}

function assertRefused(result: Awaited<ReturnType<typeof manifestWrite>>, code: string): void {
  assert.equal(result.status, 409);
  assert.equal(result.code, code);
  assert.equal(result.upstreamCalls, 0, "a refused write never reaches the Backend");
  assert.equal(result.bodyUsed, false, "a refused write never reads the body");
}

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-raw-ended-walk-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: INTERNAL_TOKEN,
    WALKSAFE_GATEWAY_SESSION_SECRET: SESSION_SECRET,
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json")
  });
  delete process.env.WALKSAFE_FIELD_ACCOUNTS_JSON;
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
  // Only the ledger time moves in this file; sessions and consent keep the real clock.
  hooks.setFieldWalkLedgerTestClock?.({
    wallEpochMs: () => clock.wall,
    monotonicMs: () => clock.mono
  });
  const login = await handleGatewayRequest(new Request(
    "https://gateway.invalid/api/field-session",
    {
      method: "POST",
      headers: { "content-type": "application/json", "cf-connecting-ip": "198.51.100.91" },
      body: JSON.stringify({
        grant_type: "password",
        email: "raw-ended-walk@example.org",
        password: "raw-ended-walk-password-must-not-leak",
        remember_me: false,
        device_id: DEVICE_ID
      })
    }
  ), {
    fetchImpl: async () => Response.json({
      schema_version: "walksafe.account-authentication.v1",
      actor_id: ACTOR_ID,
      account_generation: 1,
      auth_epoch: 3
    })
  });
  assert.equal(login.status, 200);
  sessionCookie = (login.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
  binding = gatewayFieldLongSessionBinding(new Request(
    "https://gateway.invalid/api/field-walk",
    { headers: { cookie: sessionCookie } }
  ));
  assert.ok(binding);
  const saved = await handleGatewayRequest(new Request(
    "https://gateway.invalid/privacy/rights?control=integrated-consent",
    {
      method: "PUT",
      headers: {
        cookie: sessionCookie,
        "content-type": "application/json",
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
      },
      body: JSON.stringify({
        schema_version: "walksafe.integrated-consent-request.v1",
        installation_id: INSTALLATION_ID,
        request_id: "raw_ended_walk_consent_0001",
        policy_version: INTEGRATED_CONSENT_POLICY_VERSION,
        item_versions: INTEGRATED_CONSENT_ITEM_VERSIONS,
        client_revision: 1,
        expected_previous_backend_receipt_sha256: null,
        selections: {
          raw_source_collection: true,
          automatic_reporting: true,
          mobile_network_transfer: false,
          training_reuse: false
        }
      })
    }
  ), {
    fetchImpl: async (input, init) => {
      if (new URL(String(input)).pathname === "/accounts/session-current") {
        return new Response(null, { status: 204 });
      }
      const rawBody = String(init?.body);
      const body = JSON.parse(rawBody) as Record<string, unknown>;
      return Response.json({
        schema_version: "walksafe.privacy-consent-receipt.v2",
        request_id: body.request_id,
        client_revision: body.client_revision,
        receipt_sha256: createHash("sha256").update(rawBody).digest("hex"),
        recorded_at: "2026-09-29T00:00:06Z"
      }, { status: 201 });
    }
  });
  assert.equal(saved.status, 201);
  confirmation = await saved.json() as IntegratedConsentConfirmation;
});

after(async () => {
  hooks.setFieldWalkLedgerTestClock?.(null);
  await rm(stateDirectory, { recursive: true, force: true });
});

test("결정 3(b) a RAW write for the walk that is still active is refused before any body read", async () => {
  const walk = walkId(1);
  const lease = start(binding!, walk);
  try {
    assertRefused(await manifestWrite(walk), "raw_collection_walk_in_progress");
  } finally {
    end(binding!, walk, lease);
  }
});

test("결정 3(b) the ended walk is admitted under its original walk id", async () => {
  assertAdmitted(await manifestWrite(walkId(1)));
});

test("결정 3(b) while any walk of the account is active an earlier ended walk is refused", async () => {
  const current = walkId(2);
  const lease = start(binding!, current);
  try {
    assertRefused(await manifestWrite(walkId(1)), "raw_collection_walk_in_progress");
  } finally {
    end(binding!, current, lease);
  }
  assertAdmitted(await manifestWrite(walkId(1)));
  assertAdmitted(await manifestWrite(current));
});

test("결정 3(b) unknown walks, another account's walk and another device's walk are not uploadable", async () => {
  assertRefused(await manifestWrite(walkId(3)), "raw_collection_walk_not_uploadable");
  const otherAccount: GatewayFieldLongSessionBinding = {
    ...binding!,
    actorId: "018f2b63-8fb8-4cc2-98a1-4a4fd27c3241",
    accountId: "018f2b63-8fb8-4cc2-98a1-4a4fd27c3241:generation:1"
  };
  const otherAccountWalk = walkId(4);
  end(otherAccount, otherAccountWalk, start(otherAccount, otherAccountWalk));
  assertRefused(await manifestWrite(otherAccountWalk), "raw_collection_walk_not_uploadable");
  const otherDevice: GatewayFieldLongSessionBinding = {
    ...binding!,
    deviceId: "android-raw-other-device",
    familyId: "raw-ended-other-family"
  };
  const otherDeviceWalk = walkId(5);
  end(otherDevice, otherDeviceWalk, start(otherDevice, otherDeviceWalk));
  assertRefused(await manifestWrite(otherDeviceWalk), "raw_collection_walk_not_uploadable");
});

test("결정 3(b) a walk that ended by lease expiry or takeover is uploadable after the account's walk ends", async () => {
  const expired = walkId(6);
  start(binding!, expired);
  advance(91_000);
  assertAdmitted(await manifestWrite(expired));

  const takenOver = walkId(7);
  const first = start(binding!, takenOver);
  const otherDevice: GatewayFieldLongSessionBinding = {
    ...binding!,
    deviceId: "android-raw-takeover-device",
    familyId: "raw-ended-takeover-family"
  };
  const takeoverWalk = walkId(8);
  const takeover = commandFieldWalk(otherDevice, parseFieldWalkCommand({
    schema_version: "walksafe.field-walk-command.v1",
    request_id: nextRequestId("raw-ended-takeover"),
    action: "takeover",
    walk_id: takeoverWalk,
    expected_active_walk_id: takenOver,
    expected_fencing_token: first.fence,
    confirmation: "voice_confirmed"
  })!);
  assert.equal(takeover.status, 200);
  try {
    assertRefused(await manifestWrite(takenOver), "raw_collection_walk_in_progress");
  } finally {
    end(otherDevice, takeoverWalk, {
      leaseId: String(takeover.body.lease_id),
      fence: Number(takeover.body.fencing_token)
    });
  }
  assertAdmitted(await manifestWrite(takenOver));
  assertRefused(await manifestWrite(takeoverWalk), "raw_collection_walk_not_uploadable");
});

test("결정 3(b) queued reports from the walk holder wait until its walk ends", async () => {
  const upstreamPaths: string[] = [];
  const fetchImpl: GatewayFetch = async (input) => {
    const pathname = new URL(String(input)).pathname;
    if (pathname === "/accounts/session-current") return new Response(null, { status: 204 });
    upstreamPaths.push(pathname);
    return Response.json({ ok: true });
  };
  const report = async () => {
    const response = await handleGatewayRequest(new Request(
      "https://gateway.invalid/api/reports/v2",
      { method: "POST", headers: { cookie: sessionCookie } }
    ), { fetchImpl });
    const body = await response.json() as { detail?: { code?: string } };
    return { status: response.status, code: body.detail?.code ?? null };
  };
  const walk = walkId(10);
  const lease = start(binding!, walk);
  try {
    assert.deepEqual(await report(), { status: 409, code: "report_walk_in_progress" });
    assert.deepEqual(upstreamPaths, []);
  } finally {
    end(binding!, walk, lease);
  }
  const afterEnd = await report();
  assert.notEqual(afterEnd.code, "report_walk_in_progress");
  assert.notEqual(afterEnd.code, "walk_owned_by_other_device");
});

test("결정 3(b) an ended walk older than the 30-day RAW retention is refused", async () => {
  const walk = walkId(11);
  end(binding!, walk, start(binding!, walk));
  advance(29 * DAY_MS);
  assertAdmitted(await manifestWrite(walk));
  advance(DAY_MS + 1);
  assertRefused(await manifestWrite(walk), "raw_collection_walk_not_uploadable");
  // Every earlier walk of this file ended more than 30 days ago as well.
  assertRefused(await manifestWrite(walkId(1)), "raw_collection_walk_not_uploadable");
});

test("transfer-F6 ended walks past the 30-day window are pruned when the account is read", async () => {
  const shard = ledger.fieldWalkLedgerShardPathForTests(
    process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH!,
    binding!.accountId
  );
  type StoredLedger = {
    accounts: Record<string, { endedWalks?: Array<{ walkId: string; endedAtEpochMs: number }> }>;
  };
  // No walk ended since the clock passed 30 days, yet the refused uploads above read the
  // account: the stored history keeps nothing older than the window.
  const history = decryptTestStateFile<StoredLedger>(
    { kind: "field-walk-ledger", recordId: path.basename(shard) },
    readFileSync(shard, "utf8"),
    ledger.MAX_STORED_LEDGER_BYTES
  ).accounts[binding!.accountId]?.endedWalks;
  assert.deepEqual(
    (history ?? []).filter((entry) => entry.endedAtEpochMs + 30 * DAY_MS <= clock.wall),
    []
  );
  assert.equal(history, undefined, "an emptied history drops the key like the earlier schema");
  assert.equal(ledger.FIELD_WALK_ENDED_HISTORY_MAX_PER_ACCOUNT, 256);
});

test("R3 a 1.1.0 consent can neither authorize a RAW write nor be recorded again", async () => {
  resetRawCollectionRelayForTests();
  const walk = walkId(12);
  end(binding!, walk, start(binding!, walk));
  let upstreamCalls = 0;
  const staleHeader = manifestRequest(walk);
  staleHeader.headers.set(CONSENT_POLICY_HEADER, "FP-013-1.1.0");
  const refused = await handleGatewayRequest(staleHeader, {
    fieldLongSessionBindingResolver: () => (binding ? { ...binding } : null),
    fetchImpl: async (input) => {
      if (new URL(String(input)).pathname === "/accounts/session-current") {
        return new Response(null, { status: 204 });
      }
      upstreamCalls += 1;
      return Response.json({}, { status: 500 });
    }
  });
  assert.equal(refused.status, 428);
  assert.equal(
    ((await refused.json()) as { detail?: { code?: string } }).detail?.code,
    "integrated_consent_confirmation_required"
  );
  assert.equal(upstreamCalls, 0);
  assert.equal(staleHeader.bodyUsed, false);
  const stalePut = await handleGatewayRequest(new Request(
    "https://gateway.invalid/privacy/rights?control=integrated-consent",
    {
      method: "PUT",
      headers: {
        cookie: sessionCookie,
        "content-type": "application/json",
        [CONSENT_CONTROL_SECRET_HEADER]: CONTROL_SECRET
      },
      body: JSON.stringify({
        schema_version: "walksafe.integrated-consent-request.v1",
        installation_id: INSTALLATION_ID,
        request_id: "raw_ended_walk_consent_v11_0002",
        policy_version: "FP-013-1.1.0",
        item_versions: {
          ...INTEGRATED_CONSENT_ITEM_VERSIONS,
          raw_source_collection: "FP-013-RAW-1.1.0",
          automatic_reporting: "FP-013-AUTO-1.1.0"
        },
        client_revision: 2,
        expected_previous_backend_receipt_sha256: confirmation.backend_consent_receipt_sha256,
        selections: {
          raw_source_collection: true,
          automatic_reporting: true,
          mobile_network_transfer: false,
          training_reuse: false
        }
      })
    }
  ), {
    fetchImpl: async (input) => {
      if (new URL(String(input)).pathname === "/accounts/session-current") {
        return new Response(null, { status: 204 });
      }
      upstreamCalls += 1;
      return Response.json({}, { status: 500 });
    }
  });
  assert.equal(stalePut.status, 409);
  assert.equal(
    ((await stalePut.json()) as { detail?: { code?: string } }).detail?.code,
    "integrated_consent_reconsent_required"
  );
  assert.equal(upstreamCalls, 0, "a 1.1.0 decision never reaches the Backend");
  // The current (1.2.0) confirmation still authorizes the same ended walk.
  assert.equal(confirmation.policy_version, "FP-013-1.2.0");
  assertAdmitted(await manifestWrite(walk));
});

test("결정 3(b) the ended-walk history is bounded per account and drops the oldest walk first", async () => {
  const limit = hooks.FIELD_WALK_ENDED_HISTORY_MAX_PER_ACCOUNT ?? 64;
  const first = walkId(100);
  end(binding!, first, start(binding!, first));
  for (let n = 1; n < limit; n += 1) {
    const walk = walkId(100 + n);
    end(binding!, walk, start(binding!, walk));
  }
  assertAdmitted(await manifestWrite(first));
  const overflow = walkId(100 + limit);
  end(binding!, overflow, start(binding!, overflow));
  assertRefused(await manifestWrite(first), "raw_collection_walk_not_uploadable");
  assertAdmitted(await manifestWrite(walkId(101)));
  assertAdmitted(await manifestWrite(overflow));
});
