import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import {
  mkdir,
  mkdtemp,
  readFile,
  rm,
  writeFile
} from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, test } from "node:test";

import {
  SHORT_DEVICE_SESSION_LIMIT,
  SHORT_SESSION_MAX_PLAINTEXT_BYTES,
  validShortSessionStateForMaintenance
} from "../src/auth.js";
import { resolveGatewayServiceRuntimeLockPath } from "../src/config.js";
import {
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  GatewayStateEncryptionError,
  inspectGatewayStateEnvelope,
  resetGatewayStateEncryptionForTests,
  type GatewayStateContext
} from "../src/encrypted-json-store.js";
import {
  acquireSharedFileLock,
  ExclusiveFileLockBusyError,
  withExclusiveFileLockAsync
} from "../src/exclusive-file-lock.js";
import { FIELD_LONG_SESSION_MAX_STATE_FILE_BYTES } from "../src/field-long-session.js";
import { DEFAULT_MAX_LEDGER_BYTES } from "../src/field-walk-ledger.js";
import { INTEGRATED_CONSENT_MAX_STATE_BYTES } from "../src/integrated-consent.js";
import { PRIVACY_DELETION_V2_MAX_BYTES } from "../src/privacy-deletion-v2.js";
import { PRIVACY_LEDGER_MAX_BYTES } from "../src/privacy-rights.js";
import { SERVER_CAPACITY_MAX_PLAINTEXT_BYTES } from "../src/server-capacity.js";
import {
  assertGatewayManagedStateReady,
  runGatewayStateMaintenance
} from "../src/state-encryption-maintenance.js";
import {
  configureTestStateEncryption,
  writeTestStateKeyring
} from "./state-encryption-fixture.js";

type FixtureState = {
  filePath: string;
  context: GatewayStateContext;
  maxPlaintextBytes: number;
  value: unknown;
};

const temporaryDirectories: string[] = [];

async function createMaintenanceEnvironment(): Promise<{
  directory: string;
  root: string;
  keyringPath: string;
  fieldWalkLedgerPath: string;
}> {
  const directory = await mkdtemp(path.join(tmpdir(), "gateway-state-maintenance-"));
  temporaryDirectories.push(directory);
  const root = path.join(directory, "state");
  const keyringPath = path.join(directory, "external-keyring.json");
  const fieldWalkLedgerPath = path.join(root, "field-walk-ledger.json");
  await mkdir(root, { recursive: true, mode: 0o700 });
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: root,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: fieldWalkLedgerPath,
    WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED: "true"
  });
  await configureTestStateEncryption(keyringPath, [
    { keyId: "old-key", status: "active", fillByte: 0x31 }
  ]);
  return { directory, root, keyringPath, fieldWalkLedgerPath };
}

function digest(value: string): string {
  return createHash("sha256").update(value).digest("hex");
}

function fixtureStates(root: string, fieldWalkLedgerPath: string): FixtureState[] {
  const actorId = "field-operator";
  const installationId = "installation-0001";
  return [
    {
      filePath: path.join(
        root,
        "sessions",
        `field-${digest(`field\0${actorId}`)}.json`
      ),
      context: {
        kind: "short-session",
        recordId: `field-${digest(`field\0${actorId}`)}.json`
      },
      maxPlaintextBytes: SHORT_SESSION_MAX_PLAINTEXT_BYTES,
      value: {
        actorId,
        sessionId: "s".repeat(32),
        expiresAtSeconds: 2_000_000_000,
        sessionScope: "general"
      }
    },
    {
      filePath: path.join(
        root,
        "field-long-sessions",
        `${digest(`field-long-session\0${actorId}`)}.json`
      ),
      context: {
        kind: "field-long-session",
        recordId: `${digest(`field-long-session\0${actorId}`)}.json`
      },
      maxPlaintextBytes: FIELD_LONG_SESSION_MAX_STATE_FILE_BYTES,
      value: { schema_version: 1, actor_id: actorId, families: [] }
    },
    {
      filePath: fieldWalkLedgerPath,
      context: { kind: "field-walk-ledger", recordId: path.basename(fieldWalkLedgerPath) },
      maxPlaintextBytes: DEFAULT_MAX_LEDGER_BYTES,
      value: {
        schemaVersion: "walksafe.field-walk-ledger.v1",
        accounts: {},
        audit: []
      }
    },
    {
      filePath: path.join(root, "privacy-rights", "ledger.json"),
      context: { kind: "privacy-rights-ledger", recordId: "ledger.json" },
      maxPlaintextBytes: PRIVACY_LEDGER_MAX_BYTES,
      value: { schema_version: 2, actors: {}, requests: {}, events: [] }
    },
    {
      filePath: path.join(root, "privacy-deletion-v2", "ledger.json"),
      context: { kind: "privacy-deletion-v2", recordId: "ledger.json" },
      maxPlaintextBytes: PRIVACY_DELETION_V2_MAX_BYTES,
      value: {
        schema_version: "walksafe.gateway-account-deletion-ledger.v2",
        revision: 0,
        actors: {},
        requests: {}
      }
    },
    {
      filePath: path.join(
        root,
        "integrated-consent",
        `${digest(`integrated-consent\0${installationId}`)}.json`
      ),
      context: {
        kind: "integrated-consent",
        recordId: `${digest(`integrated-consent\0${installationId}`)}.json`
      },
      maxPlaintextBytes: INTEGRATED_CONSENT_MAX_STATE_BYTES,
      value: {
        schema_version: 3,
        installation_id: installationId,
        control_secret_sha256: "a".repeat(64),
        field_actor_binding: null,
        events: []
      }
    },
    {
      filePath: path.join(root, "server-capacity", "snapshot.json"),
      context: { kind: "server-capacity", recordId: "snapshot.json" },
      maxPlaintextBytes: SERVER_CAPACITY_MAX_PLAINTEXT_BYTES,
      value: {
        version: 1,
        observed_at: "2026-08-25T00:00:00Z",
        expires_at: "2026-08-25T00:05:00Z",
        level: "NORMAL",
        reason: "STORAGE_UTILIZATION"
      }
    }
  ];
}

async function writePlaintextFixtures(states: readonly FixtureState[]): Promise<void> {
  for (const state of states) {
    await mkdir(path.dirname(state.filePath), { recursive: true, mode: 0o700 });
    await writeFile(state.filePath, `${JSON.stringify(state.value)}\n`, { mode: 0o600 });
  }
}

async function stateBytes(
  states: readonly FixtureState[]
): Promise<Map<string, Buffer>> {
  const bytes = new Map<string, Buffer>();
  for (const state of states) {
    bytes.set(state.filePath, await readFile(state.filePath));
  }
  return bytes;
}

async function assertStateBytesUnchanged(
  states: readonly FixtureState[],
  before: ReadonlyMap<string, Buffer>
): Promise<void> {
  for (const state of states) {
    assert.deepEqual(await readFile(state.filePath), before.get(state.filePath));
  }
}

async function prepareRotation(
  states: readonly FixtureState[],
  keyringPath: string
): Promise<void> {
  await writePlaintextFixtures(states);
  const migrated = await runGatewayStateMaintenance("migrate-plaintext");
  assert.equal(migrated.length, 7);
  await writeTestStateKeyring(keyringPath, [
    { keyId: "new-key", status: "active", fillByte: 0x32 },
    { keyId: "old-key", status: "decrypt-only", fillByte: 0x31 }
  ]);
  resetGatewayStateEncryptionForTests();
}

function finalManagedState(states: readonly FixtureState[]): FixtureState {
  const state = [...states]
    .sort((left, right) => left.filePath.localeCompare(right.filePath))
    .at(-1);
  assert.ok(state);
  return state;
}

afterEach(async () => {
  process.env.NODE_ENV = "test";
  resetGatewayStateEncryptionForTests();
  delete process.env.WALKSAFE_GATEWAY_STATE_KEYRING_FILE;
  delete process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR;
  delete process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH;
  delete process.env.WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED;
  for (const directory of temporaryDirectories.splice(0)) {
    await rm(directory, { recursive: true, force: true });
  }
});

test("short-session maintenance requires an exact signed session scope", () => {
  const actorId = "field-operator";
  const recordId = `field-${digest(`field\0${actorId}`)}.json`;
  const state = {
    actorId,
    sessionId: "s".repeat(32),
    expiresAtSeconds: 2_000_000_000,
    sessionScope: "account_deletion_recovery"
  };
  assert.equal(validShortSessionStateForMaintenance(state, recordId), true);
  assert.equal(
    validShortSessionStateForMaintenance({ ...state, sessionScope: "general" }, recordId),
    true
  );
  assert.equal(
    validShortSessionStateForMaintenance({ ...state, sessionScope: "admin" }, recordId),
    false
  );
  const { sessionScope: _omitted, ...withoutScope } = state;
  assert.equal(validShortSessionStateForMaintenance(withoutScope, recordId), true);
  assert.equal(
    validShortSessionStateForMaintenance({ ...state, extra: true }, recordId),
    false
  );
});

test("short-session maintenance rejects malformed, duplicate, and mixed v7 ledgers", () => {
  const actorId = "018f2b63-8fb8-4cc2-98a1-4a4fd27c3040";
  const recordId = `field-${digest(`field\0${actorId}`)}.json`;
  const session = (
    index: number,
    override: Record<string, unknown> = {}
  ): Record<string, unknown> => ({
    actorId,
    accountGeneration: 1,
    authEpoch: 1,
    deviceId: `maintenance-device-${index}`,
    expiresAtSeconds: 2_000_000_000,
    sessionId: String(index).padStart(32, "0"),
    sessionKind: "backend_account_device",
    sessionScope: "general",
    ...override
  });
  const first = session(0);
  const valid = { actorId, sessions: [first] };
  assert.equal(validShortSessionStateForMaintenance(valid, recordId), true);
  assert.equal(validShortSessionStateForMaintenance({
    actorId,
    accountGeneration: 1,
    authEpoch: 1
  }, recordId), true);
  for (const invalid of [
    { actorId, sessions: [] },
    { actorId, sessions: [first, session(1, { deviceId: first.deviceId })] },
    { actorId, sessions: [first, session(1, { sessionId: first.sessionId })] },
    { actorId, sessions: [first, session(1, { accountGeneration: 2 })] },
    { actorId, sessions: [first, session(1, { authEpoch: 2 })] },
    { actorId, sessions: [session(0, { sessionScope: "account_deletion_recovery" })] },
    { actorId, sessions: [{ ...first, unexpected: true }] },
    { actorId, accountGeneration: 1, authEpoch: 0 },
    { actorId, accountGeneration: 1, authEpoch: 1, unexpected: true },
    {
      actorId,
      sessions: Array.from(
        { length: SHORT_DEVICE_SESSION_LIMIT + 1 },
        (_, index) => session(index)
      )
    }
  ]) {
    assert.equal(validShortSessionStateForMaintenance(invalid, recordId), false);
  }
});

test("startup accepts a known legacy v3 state for safe session invalidation", async () => {
  const { root, fieldWalkLedgerPath } = await createMaintenanceEnvironment();
  const state = fixtureStates(root, fieldWalkLedgerPath)[0]!;
  const scopedValue = state.value as Record<string, unknown>;
  const { sessionScope: _omitted, ...legacyValue } = scopedValue;
  await mkdir(path.dirname(state.filePath), { recursive: true, mode: 0o700 });
  await writeFile(
    state.filePath,
    encryptGatewayStateJson(
      state.context,
      legacyValue,
      state.maxPlaintextBytes
    ),
    { mode: 0o600 }
  );

  const results = assertGatewayManagedStateReady();
  assert.equal(results.length, 1);
  assert.equal(results[0]?.statusBefore, "encrypted-active");
});

test("maintenance inspects then explicitly migrates all seven plaintext stores", async () => {
  const { root, fieldWalkLedgerPath } = await createMaintenanceEnvironment();
  const states = fixtureStates(root, fieldWalkLedgerPath);
  assert.equal(states.length, 7);
  await writePlaintextFixtures(states);

  const inspected = await runGatewayStateMaintenance("inspect");
  assert.equal(inspected.length, 7);
  assert.ok(inspected.every((result) =>
    result.statusBefore === "plaintext" && result.action === "none"
  ));

  const migrated = await runGatewayStateMaintenance("migrate-plaintext");
  assert.equal(migrated.length, 7);
  assert.ok(migrated.every((result) => result.action === "migrated"));
  for (const state of states) {
    const raw = await readFile(state.filePath, "utf8");
    assert.deepEqual(
      inspectGatewayStateEnvelope(raw, state.maxPlaintextBytes),
      { keyId: "old-key", keyStatus: "active" }
    );
    assert.deepEqual(
      decryptGatewayStateJson(
        state.context,
        raw,
        state.maxPlaintextBytes
      ).value,
      state.value
    );
  }
});

test("startup preflight rejects plaintext and accepts every migrated managed store", async () => {
  const { root, fieldWalkLedgerPath } = await createMaintenanceEnvironment();
  const states = fixtureStates(root, fieldWalkLedgerPath);
  await writePlaintextFixtures(states);

  assert.throws(
    () => assertGatewayManagedStateReady(),
    (error: unknown) =>
      error instanceof GatewayStateEncryptionError && error.code === "plaintext_rejected"
  );

  await runGatewayStateMaintenance("migrate-plaintext");
  const results = assertGatewayManagedStateReady();
  assert.equal(results.length, states.length);
  assert.equal(results.every((result) => result.statusBefore === "encrypted-active"), true);
});

test("rotation rewrites all seven decrypt-only stores with the active key", async () => {
  const { root, keyringPath, fieldWalkLedgerPath } = await createMaintenanceEnvironment();
  const states = fixtureStates(root, fieldWalkLedgerPath);
  await prepareRotation(states, keyringPath);
  const inspected = await runGatewayStateMaintenance("inspect");
  assert.equal(inspected.length, 7);
  assert.ok(inspected.every((result) =>
    result.statusBefore === "encrypted-decrypt-only"
  ));

  const rotated = await runGatewayStateMaintenance("rotate");
  assert.equal(rotated.length, 7);
  assert.ok(rotated.every((result) => result.action === "rotated"));
  for (const state of states) {
    const raw = await readFile(state.filePath, "utf8");
    assert.deepEqual(
      inspectGatewayStateEnvelope(raw, state.maxPlaintextBytes),
      { keyId: "new-key", keyStatus: "active" }
    );
    assert.deepEqual(
      decryptGatewayStateJson(
        state.context,
        raw,
        state.maxPlaintextBytes
      ).value,
      state.value
    );
  }
});

for (const fault of [
  "authentication-tag",
  "ciphertext",
  "unknown-key",
  "invalid-schema"
] as const) {
  test(`rotation ${fault} failure in the final store leaves all seven stores unchanged`, async () => {
    const { root, keyringPath, fieldWalkLedgerPath } = await createMaintenanceEnvironment();
    const states = fixtureStates(root, fieldWalkLedgerPath);
    await prepareRotation(states, keyringPath);
    const finalState = finalManagedState(states);
    const raw = await readFile(finalState.filePath, "utf8");

    if (fault === "invalid-schema") {
      await writeFile(
        finalState.filePath,
        encryptGatewayStateJson(
          finalState.context,
          { invalid: true },
          finalState.maxPlaintextBytes
        )
      );
    } else {
      const envelope = JSON.parse(raw) as Record<string, string>;
      if (fault === "unknown-key") {
        envelope.key_id = "removed-key";
      } else {
        const field = fault === "authentication-tag"
          ? "tag_base64url"
          : "ciphertext_base64url";
        const encoded = envelope[field]!;
        envelope[field] =
          `${encoded[0] === "A" ? "B" : "A"}${encoded.slice(1)}`;
      }
      await writeFile(finalState.filePath, JSON.stringify(envelope));
    }
    const before = await stateBytes(states);
    const expectedCode = fault === "unknown-key" ? "unknown_key" : "integrity";

    await assert.rejects(
      runGatewayStateMaintenance("rotate"),
      (error: unknown) =>
        error instanceof GatewayStateEncryptionError && error.code === expectedCode
    );
    await assertStateBytesUnchanged(states, before);
  });
}

test("busy final store lock leaves all seven stores unchanged", async () => {
  const { root, keyringPath, fieldWalkLedgerPath } = await createMaintenanceEnvironment();
  const states = fixtureStates(root, fieldWalkLedgerPath);
  await prepareRotation(states, keyringPath);
  const finalState = finalManagedState(states);
  const before = await stateBytes(states);

  await withExclusiveFileLockAsync(`${finalState.filePath}.lock`, async () => {
    await assert.rejects(
      runGatewayStateMaintenance("rotate"),
      ExclusiveFileLockBusyError
    );
  });
  await assertStateBytesUnchanged(states, before);
});

test("privacy-rights maintenance shares the writer ledger lock", async () => {
  const { root, keyringPath, fieldWalkLedgerPath } = await createMaintenanceEnvironment();
  const states = fixtureStates(root, fieldWalkLedgerPath);
  await prepareRotation(states, keyringPath);
  const privacy = states.find(
    (state) => state.context.kind === "privacy-rights-ledger"
  );
  assert.ok(privacy);
  const before = await stateBytes(states);

  await withExclusiveFileLockAsync(
    path.join(path.dirname(privacy.filePath), "ledger.lock"),
    async () => {
      await assert.rejects(
        runGatewayStateMaintenance("rotate"),
        ExclusiveFileLockBusyError
      );
    }
  );
  await assertStateBytesUnchanged(states, before);
});

test("compromised-key state is inspectable but cannot be decrypted or rotated", async () => {
  const { root, keyringPath, fieldWalkLedgerPath } = await createMaintenanceEnvironment();
  const state = fixtureStates(root, fieldWalkLedgerPath)[0]!;
  await mkdir(path.dirname(state.filePath), { recursive: true, mode: 0o700 });
  await writeFile(
    state.filePath,
    encryptGatewayStateJson(state.context, state.value, state.maxPlaintextBytes),
    { mode: 0o600 }
  );
  const before = await readFile(state.filePath, "utf8");
  await writeTestStateKeyring(keyringPath, [
    { keyId: "new-key", status: "active", fillByte: 0x32 },
    { keyId: "old-key", status: "compromised", fillByte: 0x31 }
  ]);
  resetGatewayStateEncryptionForTests();

  const inspected = await runGatewayStateMaintenance("inspect");
  assert.equal(inspected[0]?.statusBefore, "encrypted-compromised");
  await assert.rejects(
    runGatewayStateMaintenance("rotate"),
    (error: unknown) =>
      error instanceof GatewayStateEncryptionError && error.code === "compromised_key"
  );
  assert.equal(await readFile(state.filePath, "utf8"), before);
});

test("maintenance requires service stop acknowledgement and honors existing locks", async () => {
  const { root, fieldWalkLedgerPath } = await createMaintenanceEnvironment();
  const state = fixtureStates(root, fieldWalkLedgerPath)[0]!;
  await writePlaintextFixtures([state]);
  const before = await readFile(state.filePath, "utf8");

  delete process.env.WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED;
  await assert.rejects(
    runGatewayStateMaintenance("migrate-plaintext"),
    /SERVICE_STOPPED=true is required/
  );
  process.env.WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED = "true";

  const activeGatewayLock = acquireSharedFileLock(
    resolveGatewayServiceRuntimeLockPath()
  );
  try {
    await assert.rejects(
      runGatewayStateMaintenance("migrate-plaintext"),
      ExclusiveFileLockBusyError
    );
  } finally {
    activeGatewayLock.release();
  }

  await withExclusiveFileLockAsync(`${state.filePath}.lock`, async () => {
    await assert.rejects(
      runGatewayStateMaintenance("migrate-plaintext"),
      ExclusiveFileLockBusyError
    );
  });
  assert.equal(await readFile(state.filePath, "utf8"), before);
});
