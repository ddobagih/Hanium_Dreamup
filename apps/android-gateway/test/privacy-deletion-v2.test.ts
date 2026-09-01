import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import {
  ACCOUNT_GENERATION_HEADER,
  ACTOR_ASSERTION_HEADER,
  ACTOR_ID_HEADER,
  DELETION_ACCESS_PRE_DIGEST_HEADER,
  DELETION_TOMBSTONE_HEADER,
  FIELD_TEST_TOKEN_HEADER,
  type GatewayFetch
} from "../src/backend.js";
import { withExclusiveFileLockAsync } from "../src/exclusive-file-lock.js";
import {
  gatewaySessionStatus
} from "../src/auth.js";
import {
  acceptOrReplayAccountDeletionV2,
  ACCOUNT_DELETION_INVENTORY_V2,
  ACCOUNT_DELETION_TERMINAL_CONFLICT_CODES_V2,
  DELETION_ACCESS_SECRET_HEADER,
  DEVICE_DELETION_EVIDENCE_SCHEMA_V2,
  deviceDeletionEvidenceSha256V2,
  drainAccountDeletionOutboxV2,
  forwardAccountDeletionRequestV2,
  parseDeviceDeletionEvidenceV2,
  privacyDeletionV2LedgerPathForTests,
  type AccountDeletionStatusV2,
  type DeviceDeletionEvidenceV2
} from "../src/privacy-deletion-v2.js";
import { handleGatewayRequest } from "../src/routes.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const ACTOR = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3030";
const ACTOR_TOKEN = "deletion-v2-actor-token-12345678901234567890";
const INTERNAL_TOKEN = "deletion-v2-internal-token-12345678901234567890";
const CAPABILITY = Buffer.alloc(32, 0x5a).toString("base64url");
const REQUEST_ID = "deletion_v2_request_0001";
const TOMBSTONE_ID = "5bde8fea-6919-4755-8fa9-f324299ba307";
const REQUEST_RECEIPT = "a".repeat(64);
const ACCEPTED_AT = "2026-08-09T00:00:00.000Z";
let stateDirectory = "";
let clientIp = 30;

test("terminal conflict contract is the exact frozen Android-facing set", () => {
  assert.deepEqual(ACCOUNT_DELETION_TERMINAL_CONFLICT_CODES_V2, [
    "account_deletion_request_conflict",
    "account_deletion_client_revision_invalid",
    "account_generation_tombstoned",
    "account_deletion_installation_inventory_missing",
    "account_deletion_operation_conflict",
    "account_deletion_already_completed",
    "device_deletion_installation_not_targeted",
    "device_deletion_installation_already_terminal",
    "account_deletion_upstream_conflict"
  ]);
});

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-deletion-v2-test-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN: INTERNAL_TOKEN,
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([
      { actor_id: ACTOR, token: ACTOR_TOKEN }
    ]),
    WALKSAFE_GATEWAY_SESSION_SECRET:
      "deletion-v2-session-secret-123456789012345678901234567890",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

async function holdExclusiveLock(lockPath: string): Promise<() => Promise<void>> {
  let release!: () => void;
  let signalAcquired!: () => void;
  let signalFailure!: (error: unknown) => void;
  const acquired = new Promise<void>((resolve, reject) => {
    signalAcquired = resolve;
    signalFailure = reject;
  });
  const holding = withExclusiveFileLockAsync(lockPath, async () => {
    signalAcquired();
    await new Promise<void>((resolve) => {
      release = resolve;
    });
  });
  void holding.catch(signalFailure);
  await acquired;
  return async () => {
    release();
    await holding;
  };
}

async function loginBackendDevice(
  deviceId: string,
  actorId = ACTOR
): Promise<string> {
  clientIp += 1;
  const response = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/field-session", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "cf-connecting-ip": `198.51.100.${clientIp}`
      },
      body: JSON.stringify({
        grant_type: "password",
        email: "deletion-v2-actor@example.org",
        password: "deletion-v2-password-must-not-leak",
        remember_me: false,
        device_id: deviceId
      })
    }),
    {
      fetchImpl: async () => Response.json({
        schema_version: "walksafe.account-authentication.v1",
        actor_id: actorId,
        account_generation: 1,
        auth_epoch: 1
      })
    }
  );
  assert.equal(response.status, 200);
  return (response.headers.get("set-cookie") ?? "").split(";", 1)[0]!;
}

function deletionRequestBody(): string {
  return JSON.stringify({
    schema_version: "walksafe.account-deletion-request.v2",
    request_id: REQUEST_ID,
    client_revision: 1,
    confirmation: "DELETE_MY_ACCOUNT"
  });
}

function statusFixture(
  revision = 1,
  deviceEvidenceSha256: string | null = null
): AccountDeletionStatusV2 {
  const acceptedAtMs = Date.parse(ACCEPTED_AT);
  const updatedAt = revision === 1 ? ACCEPTED_AT : "2026-08-09T00:05:00.000Z";
  return {
    schema_version: "walksafe.account-deletion-status.v2",
    request_id: REQUEST_ID,
    client_revision: 1,
    revision,
    accepted_at: ACCEPTED_AT,
    updated_at: updatedAt,
    account_generation: 1,
    tombstone_id: TOMBSTONE_ID,
    request_receipt_sha256: REQUEST_RECEIPT,
    overall_status: "PARTIAL",
    items: ACCOUNT_DELETION_INVENTORY_V2.map((definition) => {
      const deviceCompleted = definition.key === "device_untransmitted_data" &&
        deviceEvidenceSha256 !== null;
      const external = [
        "device_untransmitted_data",
        "training_datasets",
        "training_labels",
        "derived_artifacts",
        "backups"
      ].includes(definition.key);
      return {
        key: definition.key,
        status: deviceCompleted
          ? "COMPLETED" as const
          : external ? "EXTERNAL_PENDING" as const : "PENDING" as const,
        item_revision: deviceCompleted ? 2 : 1,
        due_at: new Date(acceptedAtMs + definition.dueAfterMs).toISOString(),
        updated_at: deviceCompleted ? updatedAt : ACCEPTED_AT,
        evidence_sha256: deviceCompleted ? deviceEvidenceSha256 : null,
        disposition_basis: null,
        retry_after: null,
        restriction_reason: null,
        legal_hold_review_at: null,
        legal_hold_contact: null,
        terminal_at: deviceCompleted ? updatedAt : null
      };
    }),
    completion_receipt_sha256: null
  };
}

function terminalStatusFixture(
  revision: number,
  aggregateEvidenceSha256: string
): AccountDeletionStatusV2 {
  const acceptedAtMs = Date.parse(ACCEPTED_AT);
  const terminalAt = "2026-08-09T00:10:00.000Z";
  return {
    schema_version: "walksafe.account-deletion-status.v2",
    request_id: REQUEST_ID,
    client_revision: 1,
    revision,
    accepted_at: ACCEPTED_AT,
    updated_at: terminalAt,
    account_generation: 1,
    tombstone_id: TOMBSTONE_ID,
    request_receipt_sha256: REQUEST_RECEIPT,
    overall_status: "COMPLETED",
    items: ACCOUNT_DELETION_INVENTORY_V2.map((definition) => ({
      key: definition.key,
      status: "COMPLETED",
      item_revision: 2,
      due_at: new Date(acceptedAtMs + definition.dueAfterMs).toISOString(),
      updated_at: terminalAt,
      evidence_sha256: definition.key === "device_untransmitted_data"
        ? aggregateEvidenceSha256
        : "e".repeat(64),
      disposition_basis: null,
      retry_after: null,
      restriction_reason: null,
      legal_hold_review_at: null,
      legal_hold_contact: null,
      terminal_at: terminalAt
    })),
    completion_receipt_sha256: "c".repeat(64)
  };
}

function assertDeletionBackendHeaders(
  init: RequestInit | undefined,
  tombstoneExpected: boolean
): Headers {
  const headers = new Headers(init?.headers);
  assert.equal(headers.get(FIELD_TEST_TOKEN_HEADER), INTERNAL_TOKEN);
  assert.equal(headers.get(ACTOR_ID_HEADER), ACTOR);
  assert.equal(headers.get(ACCOUNT_GENERATION_HEADER), "1");
  assert.match(headers.get(DELETION_ACCESS_PRE_DIGEST_HEADER) ?? "", /^[0-9a-f]{64}$/);
  assert.match(headers.get(ACTOR_ASSERTION_HEADER) ?? "", /^v2\.\d+\.[A-Za-z0-9_-]+$/);
  assert.equal(headers.get(DELETION_ACCESS_SECRET_HEADER), null);
  assert.equal(
    headers.get(DELETION_TOMBSTONE_HEADER),
    tombstoneExpected ? TOMBSTONE_ID : null
  );
  return headers;
}

test("device evidence uses the cross-language whole-second canonical hash vector", () => {
  const statement = {
    schema_version: DEVICE_DELETION_EVIDENCE_SCHEMA_V2,
    request_id: "account_delete_request_0001",
    tombstone_id: "tombstone-00000001",
    request_receipt_sha256: "1".repeat(64),
    installation_id: "501e3ad4-e74f-4433-820f-72ac2fdd42ad",
    evidence_id: "device-evidence-0001",
    client_revision: 1,
    expected_status_revision: 3,
    item: "device_untransmitted_data" as const,
    result: "DELETED" as const,
    completed_at: "2026-07-25T12:05:00Z"
  };
  assert.equal(
    deviceDeletionEvidenceSha256V2(statement),
    "26de7610980e8cd30277b6864db213758e873d139e654eb447d40dd35496d335"
  );
  for (const completedAt of [
    "2026-07-25T12:05:00.123Z",
    "2026-07-25T12:05:00+00:00",
    "2026-07-25T21:05:00+09:00",
    "2026-02-31T12:05:00Z",
    "2026-07-25T24:00:00Z",
    "2026-07-25T23:59:60Z"
  ]) {
    const nonCanonical = { ...statement, completed_at: completedAt };
    assert.equal(parseDeviceDeletionEvidenceV2({
      ...nonCanonical,
      evidence_sha256: deviceDeletionEvidenceSha256V2(nonCanonical)
    }), null);
  }
});

test("v2 deletion outbox retries v7 session revocation after a storage lock clears", async () => {
  const actorId = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3031";
  const requestId = "deletion_v2_lock_retry_0001";
  const capability = Buffer.alloc(32, 0x31).toString("base64url");
  const cookie = await loginBackendDevice("deletion-lock-device", actorId);
  const recordId = `field-${createHash("sha256")
    .update(`field\0${actorId}`)
    .digest("hex")}.json`;
  const statePath = path.join(stateDirectory, "sessions", recordId);
  const body = JSON.stringify({
    schema_version: "walksafe.account-deletion-request.v2",
    request_id: requestId,
    client_revision: 1,
    confirmation: "DELETE_MY_ACCOUNT"
  });
  const backendStatus = { ...statusFixture(), request_id: requestId };
  let backendCalls = 0;
  const fetchImpl: GatewayFetch = async () => {
    backendCalls += 1;
    return Response.json(backendStatus, { status: 202 });
  };
  const deletionRequest = (): Request => new Request(
    "http://127.0.0.1:8081/privacy/account-deletions",
    {
      method: "POST",
      headers: {
        cookie,
        "content-type": "application/json",
        [DELETION_ACCESS_SECRET_HEADER]: capability
      },
      body
    }
  );

  const before = await readFile(statePath, "utf8");
  const releaseLock = await holdExclusiveLock(`${statePath}.lock`);
  try {
    const pending = await handleGatewayRequest(deletionRequest(), { fetchImpl });
    assert.equal(pending.status, 503);
    assert.equal(pending.headers.get("retry-after"), "5");
    assert.deepEqual(await pending.json(), {
      code: "account_deletion_backend_pending"
    });
    assert.equal(backendCalls, 0);
    assert.equal(await readFile(statePath, "utf8"), before);
    const fencedSpeech = await handleGatewayRequest(new Request(
      "http://127.0.0.1:8081/api/speech/tts",
      {
        method: "POST",
        headers: {
          cookie,
          "content-type": "application/json",
          "cf-connecting-ip": "198.51.100.250"
        },
        body: JSON.stringify({ text: "fenced account must not reach voice" })
      }
    ), { fetchImpl });
    assert.equal(fencedSpeech.status, 409);
    assert.deepEqual(await fencedSpeech.json(), {
      detail: {
        code: "account_generation_inactive",
        message: "The account generation is no longer active."
      }
    });
    assert.equal(backendCalls, 0);
    const pendingStatus = await handleGatewayRequest(new Request(
      `http://127.0.0.1:8081/privacy/account-deletions/${requestId}/status`,
      { headers: { [DELETION_ACCESS_SECRET_HEADER]: capability } }
    ), { fetchImpl });
    assert.equal(pendingStatus.status, 503);
    assert.deepEqual(await pendingStatus.json(), {
      code: "account_deletion_backend_pending"
    });
    assert.equal(backendCalls, 0);
    assert.equal(await readFile(statePath, "utf8"), before);
    assert.deepEqual(
      await drainAccountDeletionOutboxV2(
        fetchImpl,
        Date.now() + 24 * 60 * 60 * 1_000
      ),
      { attempted: 1, succeeded: 0 }
    );
    assert.equal(backendCalls, 0);
  } finally {
    await releaseLock();
  }

  assert.deepEqual(
    await drainAccountDeletionOutboxV2(
      fetchImpl,
      Date.now() + 24 * 60 * 60 * 1_000
    ),
    { attempted: 1, succeeded: 1 }
  );
  assert.equal(backendCalls, 1);
  await assert.rejects(
    readFile(statePath, "utf8"),
    (error: NodeJS.ErrnoException) => error.code === "ENOENT"
  );
  assert.equal(
    (await gatewaySessionStatus(new Request(
      "https://gateway.invalid/api/field-session",
      { headers: { cookie } }
    )).json() as { authenticated: boolean }).authenticated,
    false
  );
});

test("v2 deletion fence replaces an in-flight speech response", async () => {
  const actorId = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3032";
  const requestId = "deletion_v2_speech_race_0001";
  const capability = Buffer.alloc(32, 0x32).toString("base64url");
  const cookie = await loginBackendDevice("deletion-speech-device", actorId);
  Object.assign(process.env, {
    WALKSAFE_VOICE_ENABLED: "true",
    WALKSAFE_VOICE_API_BASE_URL: "http://127.0.0.1:9001",
    WALKSAFE_VOICE_SERVICE_TOKEN: "deletion-race-voice-token-1234567890"
  });
  let signalSpeechStarted!: () => void;
  let releaseSpeech!: (response: Response) => void;
  const speechStarted = new Promise<void>((resolve) => {
    signalSpeechStarted = resolve;
  });
  const heldSpeech = new Promise<Response>((resolve) => {
    releaseSpeech = resolve;
  });
  try {
    const speech = handleGatewayRequest(new Request(
      "http://127.0.0.1:8081/api/speech/tts",
      {
        method: "POST",
        headers: {
          cookie,
          "content-type": "application/json",
          "cf-connecting-ip": "198.51.100.251"
        },
        body: JSON.stringify({
          schema_version: "walksafe.speech-tts-request.v1",
          text: "삭제된 세션의 응답은 반환되면 안 됩니다.",
          request_id: "018f2b63-8fb8-7cc2-98a1-4a4fd27c3002"
        })
      }
    ), {
      fetchImpl: async () => {
        signalSpeechStarted();
        return heldSpeech;
      }
    });
    await speechStarted;

    const deletion = await handleGatewayRequest(new Request(
      "http://127.0.0.1:8081/privacy/account-deletions",
      {
        method: "POST",
        headers: {
          cookie,
          "content-type": "application/json",
          [DELETION_ACCESS_SECRET_HEADER]: capability
        },
        body: JSON.stringify({
          schema_version: "walksafe.account-deletion-request.v2",
          request_id: requestId,
          client_revision: 1,
          confirmation: "DELETE_MY_ACCOUNT"
        })
      }
    ), {
      fetchImpl: async () => Response.json(
        { ...statusFixture(), request_id: requestId },
        { status: 202 }
      )
    });
    assert.equal(deletion.status, 202);

    releaseSpeech(new Response("invalid wav", {
      headers: { "content-type": "audio/wav" }
    }));
    const fenced = await speech;
    assert.equal(fenced.status, 409);
    assert.deepEqual(await fenced.json(), {
      detail: {
        code: "account_generation_inactive",
        message: "The account generation is no longer active."
      }
    });
  } finally {
    releaseSpeech(new Response("cancelled", { status: 503 }));
    delete process.env.WALKSAFE_VOICE_ENABLED;
    delete process.env.WALKSAFE_VOICE_API_BASE_URL;
    delete process.env.WALKSAFE_VOICE_SERVICE_TOKEN;
  }
});

test("v2 deletion revokes every v7 device session and uses one unforwarded capability", async () => {
  assert.equal(CAPABILITY.length, 43);
  const cookie = await loginBackendDevice("deletion-device-a");
  const secondDeviceCookie = await loginBackendDevice("deletion-device-b");
  for (const activeCookie of [cookie, secondDeviceCookie]) {
    assert.equal(
      (await gatewaySessionStatus(new Request(
        "https://gateway.invalid/api/field-session",
        { headers: { cookie: activeCookie } }
      )).json() as { authenticated: boolean }).authenticated,
      true
    );
  }
  let backendCalls = 0;
  let evidenceCalls = 0;
  let statusCalls = 0;
  let evidence: DeviceDeletionEvidenceV2 | null = null;
  let permanentEvidenceConflictCode: string | null = null;
  const fetchImpl: GatewayFetch = async (input, init) => {
    backendCalls += 1;
    const url = String(input);
    if (url.endsWith("/privacy/account-deletions")) {
      assert.equal(init?.method, "POST");
      assert.equal(init?.body, deletionRequestBody());
      assertDeletionBackendHeaders(init, false);
      return Response.json(statusFixture(), { status: 202 });
    }
    if (url.endsWith(`/${REQUEST_ID}/status`)) {
      statusCalls += 1;
      assert.equal(init?.method, "GET");
      assertDeletionBackendHeaders(init, true);
      return Response.json(
        statusCalls >= 3
          ? terminalStatusFixture(4, "f".repeat(64))
          : statusCalls === 2 ? statusFixture(2) : statusFixture()
      );
    }
    assert.equal(url, `http://127.0.0.1:8000/privacy/account-deletions/${REQUEST_ID}/device-evidence`);
    assert.equal(init?.method, "POST");
    assertDeletionBackendHeaders(init, true);
    assert.equal(init?.body, JSON.stringify(evidence));
    evidenceCalls += 1;
    if (permanentEvidenceConflictCode !== null) {
      return Response.json({
        detail: {
          code: permanentEvidenceConflictCode,
          message: "The evidence operation cannot be retried."
        }
      }, { status: 409 });
    }
    if (evidenceCalls === 1) {
      return Response.json({
        detail: {
          code: "account_deletion_revision_conflict",
          message: "The deletion status revision changed.",
          current_status_revision: 2,
          current_status: statusFixture(2)
        }
      }, { status: 409 });
    }
    assert.equal(evidence!.expected_status_revision, 2);
    assert.equal(evidence!.installation_id, "installation-v2-0001");
    return Response.json(statusFixture(3));
  };

  const accepted = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/privacy/account-deletions", {
      method: "POST",
      headers: {
        cookie,
        "content-type": "application/json",
        [DELETION_ACCESS_SECRET_HEADER]: CAPABILITY
      },
      body: deletionRequestBody()
    }),
    { fetchImpl }
  );
  assert.equal(accepted.status, 202);
  assert.deepEqual(await accepted.json(), statusFixture());
  assert.equal(backendCalls, 1);
  for (const revokedCookie of [cookie, secondDeviceCookie]) {
    assert.equal(
      (await gatewaySessionStatus(new Request(
        "https://gateway.invalid/api/field-session",
        { headers: { cookie: revokedCookie } }
      )).json() as { authenticated: boolean }).authenticated,
      false
    );
  }
  const recordId = `field-${createHash("sha256")
    .update(`field\0${ACTOR}`)
    .digest("hex")}.json`;
  await assert.rejects(
    readFile(path.join(stateDirectory, "sessions", recordId), "utf8"),
    (error: NodeJS.ErrnoException) => error.code === "ENOENT"
  );
  assert.equal(
    (await readFile(privacyDeletionV2LedgerPathForTests(), "utf8")).includes(CAPABILITY),
    false
  );

  const replay = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/privacy/account-deletions", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        [DELETION_ACCESS_SECRET_HEADER]: CAPABILITY
      },
      body: deletionRequestBody()
    }),
    { fetchImpl }
  );
  assert.equal(replay.status, 200);
  assert.deepEqual(await replay.json(), statusFixture());
  assert.equal(backendCalls, 1);

  const status = await handleGatewayRequest(
    new Request(
      `http://127.0.0.1:8081/privacy/account-deletions/${REQUEST_ID}/status`,
      { headers: { [DELETION_ACCESS_SECRET_HEADER]: CAPABILITY } }
    ),
    { fetchImpl }
  );
  assert.equal(status.status, 200);
  assert.equal(backendCalls, 2);

  const evidenceStatement = {
    schema_version: DEVICE_DELETION_EVIDENCE_SCHEMA_V2,
    request_id: REQUEST_ID,
    tombstone_id: TOMBSTONE_ID,
    request_receipt_sha256: REQUEST_RECEIPT,
    installation_id: "installation-v2-0001",
    evidence_id: "device-evidence-v2-0001",
    client_revision: 1,
    expected_status_revision: 1,
    item: "device_untransmitted_data" as const,
    result: "DELETED" as const,
    completed_at: "2026-08-09T00:05:00Z"
  };
  evidence = {
    ...evidenceStatement,
    evidence_sha256: deviceDeletionEvidenceSha256V2(evidenceStatement)
  };
  const evidenceResponse = await handleGatewayRequest(
    new Request(
      `http://127.0.0.1:8081/privacy/account-deletions/${REQUEST_ID}/device-evidence`,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          [DELETION_ACCESS_SECRET_HEADER]: CAPABILITY
        },
        body: JSON.stringify(evidence)
      }
    ),
    { fetchImpl }
  );
  assert.equal(evidenceResponse.status, 409);
  assert.deepEqual(await evidenceResponse.json(), statusFixture(2));
  assert.equal(backendCalls, 4);

  const evidenceReplay = await handleGatewayRequest(
    new Request(
      `http://127.0.0.1:8081/privacy/account-deletions/${REQUEST_ID}/device-evidence`,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          [DELETION_ACCESS_SECRET_HEADER]: CAPABILITY
        },
        body: JSON.stringify(evidence)
      }
    ),
    { fetchImpl }
  );
  assert.equal(evidenceReplay.status, 409);
  assert.deepEqual(await evidenceReplay.json(), statusFixture(2));
  assert.equal(backendCalls, 4);

  const rebasedStatement = {
    ...evidenceStatement,
    evidence_id: "device-evidence-v2-0002",
    expected_status_revision: 2
  };
  evidence = {
    ...rebasedStatement,
    evidence_sha256: deviceDeletionEvidenceSha256V2(rebasedStatement)
  };
  const rebasedResponse = await handleGatewayRequest(
    new Request(
      `http://127.0.0.1:8081/privacy/account-deletions/${REQUEST_ID}/device-evidence`,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          [DELETION_ACCESS_SECRET_HEADER]: CAPABILITY
        },
        body: JSON.stringify(evidence)
      }
    ),
    { fetchImpl }
  );
  assert.equal(rebasedResponse.status, 200);
  assert.deepEqual(await rebasedResponse.json(), statusFixture(3));
  assert.equal(backendCalls, 5);

  const rebasedReplay = await handleGatewayRequest(
    new Request(
      `http://127.0.0.1:8081/privacy/account-deletions/${REQUEST_ID}/device-evidence`,
      {
        method: "POST",
        headers: {
          "content-type": "application/json",
          [DELETION_ACCESS_SECRET_HEADER]: CAPABILITY
        },
        body: JSON.stringify(evidence)
      }
    ),
    { fetchImpl }
  );
  assert.equal(rebasedReplay.status, 200);
  assert.equal(backendCalls, 5);

  const terminal = await handleGatewayRequest(
    new Request(
      `http://127.0.0.1:8081/privacy/account-deletions/${REQUEST_ID}/status`,
      { headers: { [DELETION_ACCESS_SECRET_HEADER]: CAPABILITY } }
    ),
    { fetchImpl }
  );
  assert.equal(terminal.status, 200);
  assert.deepEqual(
    await terminal.json(),
    terminalStatusFixture(4, "f".repeat(64))
  );
  assert.equal(backendCalls, 6);

  for (const [index, conflictCode] of [
    "device_deletion_installation_already_terminal",
    "device_deletion_installation_not_targeted",
    "account_deletion_already_completed",
    "account_deletion_operation_conflict"
  ].entries()) {
    permanentEvidenceConflictCode = conflictCode;
    const terminalConflictStatement = {
      ...evidenceStatement,
      evidence_id: `device-evidence-terminal-${index + 1}`,
      expected_status_revision: 4
    };
    evidence = {
      ...terminalConflictStatement,
      evidence_sha256: deviceDeletionEvidenceSha256V2(terminalConflictStatement)
    };
    const callsBefore: number = backendCalls;
    const conflicted = await handleGatewayRequest(
      new Request(
        `http://127.0.0.1:8081/privacy/account-deletions/${REQUEST_ID}/device-evidence`,
        {
          method: "POST",
          headers: {
            "content-type": "application/json",
            [DELETION_ACCESS_SECRET_HEADER]: CAPABILITY
          },
          body: JSON.stringify(evidence)
        }
      ),
      { fetchImpl }
    );
    assert.equal(conflicted.status, 409);
    assert.deepEqual(await conflicted.json(), { code: conflictCode });
    assert.equal(backendCalls, callsBefore + 1);

    const replayedConflict = await handleGatewayRequest(
      new Request(
        `http://127.0.0.1:8081/privacy/account-deletions/${REQUEST_ID}/device-evidence`,
        {
          method: "POST",
          headers: {
            "content-type": "application/json",
            [DELETION_ACCESS_SECRET_HEADER]: CAPABILITY
          },
          body: JSON.stringify(evidence)
        }
      ),
      { fetchImpl }
    );
    assert.equal(replayedConflict.status, 409);
    assert.deepEqual(await replayedConflict.json(), { code: conflictCode });
    assert.equal(backendCalls, callsBefore + 1);
  }
  permanentEvidenceConflictCode = null;
  assert.deepEqual(
    await drainAccountDeletionOutboxV2(fetchImpl, Date.now() + 24 * 60 * 60 * 1_000),
    { attempted: 0, succeeded: 0 }
  );

  let ordinaryFetches = 0;
  const blocked = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/api/navigation/walking", {
      method: "POST",
      headers: { cookie, "content-type": "application/json" },
      body: "{}"
    }),
    { fetchImpl: async () => {
      ordinaryFetches += 1;
      return Response.json({});
    } }
  );
  assert.ok(blocked.status === 401 || blocked.status === 409);
  assert.equal(ordinaryFetches, 0);
});

test("wrong capabilities are hidden and the v1 query boundary is retired", async () => {
  const wrong = Buffer.alloc(32, 0x4b).toString("base64url");
  const hidden = await handleGatewayRequest(
    new Request(
      `http://127.0.0.1:8081/privacy/account-deletions/${REQUEST_ID}/status`,
      { headers: { [DELETION_ACCESS_SECRET_HEADER]: wrong } }
    )
  );
  const unknown = await handleGatewayRequest(
    new Request(
      "http://127.0.0.1:8081/privacy/account-deletions/unknown_request_0001/status",
      { headers: { [DELETION_ACCESS_SECRET_HEADER]: wrong } }
    )
  );
  assert.equal(hidden.status, 404);
  assert.equal(unknown.status, 404);
  assert.equal(await hidden.text(), await unknown.text());

  const retired = await handleGatewayRequest(
    new Request("http://127.0.0.1:8081/privacy/rights?control=account-deletion")
  );
  assert.equal(retired.status, 410);
});

test("permanent request conflicts are durably quarantined and exposed without refetch", async () => {
  for (const [index, conflictCode] of [
    "account_deletion_request_conflict",
    "account_deletion_client_revision_invalid",
    "account_generation_tombstoned",
    "account_deletion_installation_inventory_missing"
  ].entries()) {
    const requestId = `deletion_conflict_request_000${index + 1}`;
    const capability = Buffer.alloc(32, index + 1).toString("base64url");
    const input = {
      schema_version: "walksafe.account-deletion-request.v2" as const,
      request_id: requestId,
      client_revision: 1,
      confirmation: "DELETE_MY_ACCOUNT" as const
    };
    assert.equal(
      acceptOrReplayAccountDeletionV2(
        `deletion-conflict-actor-${index + 1}`,
        1,
        input,
        capability
      ).kind,
      "accepted"
    );
    let calls = 0;
    const fetchImpl: GatewayFetch = async () => {
      calls += 1;
      return Response.json({
        detail: {
          code: conflictCode,
          message: "The request operation cannot be retried."
        }
      }, { status: 409 });
    };

    const first = await forwardAccountDeletionRequestV2(
      requestId,
      new Request("http://127.0.0.1/privacy/account-deletions"),
      fetchImpl
    );
    assert.equal(first.kind, "terminal_conflict");
    if (first.kind === "terminal_conflict") assert.equal(first.code, conflictCode);
    const replay = await forwardAccountDeletionRequestV2(
      requestId,
      new Request("http://127.0.0.1/privacy/account-deletions"),
      fetchImpl
    );
    assert.equal(replay.kind, "terminal_conflict");
    if (replay.kind === "terminal_conflict") assert.equal(replay.code, conflictCode);
    assert.equal(calls, 1);

    const gatewayReplay = await handleGatewayRequest(
      new Request("http://127.0.0.1:8081/privacy/account-deletions", {
        method: "POST",
        headers: {
          "content-type": "application/json",
          [DELETION_ACCESS_SECRET_HEADER]: capability
        },
        body: JSON.stringify(input)
      }),
      { fetchImpl }
    );
    assert.equal(gatewayReplay.status, 409);
    assert.deepEqual(await gatewayReplay.json(), { code: conflictCode });
    assert.equal(calls, 1);
  }

  const unknownRequestId = "deletion_unknown_conflict_request_0001";
  const unknownCapability = Buffer.alloc(32, 0x44).toString("base64url");
  const unknownInput = {
    schema_version: "walksafe.account-deletion-request.v2" as const,
    request_id: unknownRequestId,
    client_revision: 1,
    confirmation: "DELETE_MY_ACCOUNT" as const
  };
  assert.equal(
    acceptOrReplayAccountDeletionV2(
      "deletion-unknown-conflict-actor",
      1,
      unknownInput,
      unknownCapability
    ).kind,
    "accepted"
  );
  let unknownCalls = 0;
  const unknownFetch: GatewayFetch = async () => {
    unknownCalls += 1;
    return Response.json({
      detail: {
        code: "account_deletion_future_conflict",
        message: "A future backend conflict must not be assumed permanent."
      }
    }, { status: 409 });
  };
  for (let attempt = 1; attempt <= 2; attempt += 1) {
    const retried = await forwardAccountDeletionRequestV2(
      unknownRequestId,
      new Request("http://127.0.0.1/privacy/account-deletions"),
      unknownFetch
    );
    assert.equal(retried.kind, "pending");
  }
  const boundedFailure = await forwardAccountDeletionRequestV2(
    unknownRequestId,
    new Request("http://127.0.0.1/privacy/account-deletions"),
    unknownFetch
  );
  assert.equal(boundedFailure.kind, "terminal_conflict");
  if (boundedFailure.kind === "terminal_conflict") {
    assert.equal(boundedFailure.code, "account_deletion_upstream_conflict");
  }
  assert.equal(unknownCalls, 3);
  const boundedReplay = await forwardAccountDeletionRequestV2(
    unknownRequestId,
    new Request("http://127.0.0.1/privacy/account-deletions"),
    unknownFetch
  );
  assert.equal(boundedReplay.kind, "terminal_conflict");
  if (boundedReplay.kind === "terminal_conflict") {
    assert.equal(boundedReplay.code, "account_deletion_upstream_conflict");
  }
  assert.equal(unknownCalls, 3);

  assert.deepEqual(
    await drainAccountDeletionOutboxV2(undefined, Date.now() + 24 * 60 * 60 * 1_000),
    { attempted: 0, succeeded: 0 }
  );
});
