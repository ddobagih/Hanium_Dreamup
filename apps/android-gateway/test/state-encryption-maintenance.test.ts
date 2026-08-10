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
    }
  ];
}

async function writePlaintextFixtures(states: readonly FixtureState[]): Promise<void> {
  for (const state of states) {
    await mkdir(path.dirname(state.filePath), { recursive: true, mode: 0o700 });
    await writeFile(state.filePath, `${JSON.stringify(state.value)}\n`, { mode: 0o600 });
  }
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

test("maintenance inspects then explicitly migrates all six plaintext stores", async () => {
  const { root, fieldWalkLedgerPath } = await createMaintenanceEnvironment();
  const states = fixtureStates(root, fieldWalkLedgerPath);
  await writePlaintextFixtures(states);

  const inspected = await runGatewayStateMaintenance("inspect");
  assert.equal(inspected.length, 6);
  assert.ok(inspected.every((result) =>
    result.statusBefore === "plaintext" && result.action === "none"
  ));

  const migrated = await runGatewayStateMaintenance("migrate-plaintext");
  assert.equal(migrated.length, 6);
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

test("rotation rewrites decrypt-only envelopes with the active key", async () => {
  const { root, keyringPath, fieldWalkLedgerPath } = await createMaintenanceEnvironment();
  const state = fixtureStates(root, fieldWalkLedgerPath)[0]!;
  await mkdir(path.dirname(state.filePath), { recursive: true, mode: 0o700 });
  await writeFile(
    state.filePath,
    encryptGatewayStateJson(state.context, state.value, state.maxPlaintextBytes),
    { mode: 0o600 }
  );

  await writeTestStateKeyring(keyringPath, [
    { keyId: "new-key", status: "active", fillByte: 0x32 },
    { keyId: "old-key", status: "decrypt-only", fillByte: 0x31 }
  ]);
  resetGatewayStateEncryptionForTests();
  const inspected = await runGatewayStateMaintenance("inspect");
  assert.equal(inspected[0]?.statusBefore, "encrypted-decrypt-only");

  const rotated = await runGatewayStateMaintenance("rotate");
  assert.equal(rotated[0]?.action, "rotated");
  assert.deepEqual(
    inspectGatewayStateEnvelope(
      await readFile(state.filePath, "utf8"),
      state.maxPlaintextBytes
    ),
    { keyId: "new-key", keyStatus: "active" }
  );
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
