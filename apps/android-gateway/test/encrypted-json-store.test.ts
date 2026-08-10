import assert from "node:assert/strict";
import { chmod, link, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, test } from "node:test";

import { assertGatewayStateStorageConfiguration } from "../src/config.js";
import {
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  GatewayStateEncryptionError,
  GATEWAY_STATE_ENVELOPE_SCHEMA,
  initializeGatewayStateEncryption,
  inspectGatewayStateEnvelope,
  parsePlaintextGatewayStateForMaintenance,
  resetGatewayStateEncryptionForTests,
  type GatewayStateContext
} from "../src/encrypted-json-store.js";
import {
  configureTestStateEncryption,
  writeTestStateKeyring
} from "./state-encryption-fixture.js";

const MAX_PLAINTEXT_BYTES = 16 * 1024;
const CONTEXT: GatewayStateContext = {
  kind: "short-session",
  recordId: `field-${"a".repeat(64)}.json`
};
const temporaryDirectories: string[] = [];

async function configuredKeyring(): Promise<string> {
  const directory = await mkdtemp(path.join(tmpdir(), "gateway-state-crypto-"));
  temporaryDirectories.push(directory);
  const keyringPath = path.join(directory, "keyring.json");
  await configureTestStateEncryption(keyringPath, [
    { keyId: "old-key", status: "active", fillByte: 0x11 }
  ]);
  return keyringPath;
}

function encryptionError(code: GatewayStateEncryptionError["code"]): (error: unknown) => boolean {
  return (error: unknown) =>
    error instanceof GatewayStateEncryptionError && error.code === code;
}

afterEach(async () => {
  process.env.NODE_ENV = "test";
  resetGatewayStateEncryptionForTests();
  delete process.env.WALKSAFE_GATEWAY_STATE_KEYRING_FILE;
  for (const directory of temporaryDirectories.splice(0)) {
    await rm(directory, { recursive: true, force: true });
  }
});

test("AES-256-GCM envelope has an exact schema and hides plaintext", async () => {
  await configuredKeyring();
  const value = {
    actorId: "field-operator",
    sessionId: "session-secret-value-that-must-not-leak",
    expiresAtSeconds: 2_000_000_000
  };
  const raw = encryptGatewayStateJson(CONTEXT, value, MAX_PLAINTEXT_BYTES);
  const envelope = JSON.parse(raw) as Record<string, unknown>;
  assert.deepEqual(Object.keys(envelope).sort(), [
    "algorithm",
    "ciphertext_base64url",
    "iv_base64url",
    "key_id",
    "schema_version",
    "tag_base64url"
  ]);
  assert.equal(envelope.schema_version, GATEWAY_STATE_ENVELOPE_SCHEMA);
  assert.equal(envelope.algorithm, "aes-256-gcm");
  assert.equal(envelope.key_id, "old-key");
  assert.equal(raw.includes(value.sessionId), false);
  assert.deepEqual(
    decryptGatewayStateJson(CONTEXT, raw, MAX_PLAINTEXT_BYTES),
    { value, keyId: "old-key", keyStatus: "active" }
  );
});

test("ciphertext tampering and wrong AAD fail authentication", async () => {
  await configuredKeyring();
  const raw = encryptGatewayStateJson(CONTEXT, { secret: "hidden" }, MAX_PLAINTEXT_BYTES);
  const envelope = JSON.parse(raw) as Record<string, string>;
  const ciphertext = envelope.ciphertext_base64url!;
  envelope.ciphertext_base64url = `${ciphertext[0] === "A" ? "B" : "A"}${ciphertext.slice(1)}`;
  assert.throws(
    () => decryptGatewayStateJson(CONTEXT, JSON.stringify(envelope), MAX_PLAINTEXT_BYTES),
    encryptionError("integrity")
  );
  assert.throws(
    () => decryptGatewayStateJson(
      { ...CONTEXT, recordId: `field-${"b".repeat(64)}.json` },
      raw,
      MAX_PLAINTEXT_BYTES
    ),
    encryptionError("integrity")
  );
  assert.throws(
    () => decryptGatewayStateJson(
      { kind: "integrated-consent", recordId: CONTEXT.recordId },
      raw,
      MAX_PLAINTEXT_BYTES
    ),
    encryptionError("integrity")
  );
});

test("duplicate JSON members are rejected in keyrings, envelopes and migration input", async () => {
  const keyringPath = await configuredKeyring();
  resetGatewayStateEncryptionForTests();
  await chmod(keyringPath, 0o600);
  const key = Buffer.alloc(32, 0x11).toString("base64url");
  await writeFile(
    keyringPath,
    `{"schema_version":"walksafe.gateway-state-keyring.v1","keys":[{"key_id":"old-key","status":"compromised","status":"active","key_base64url":"${key}"}]}\n`,
    "utf8"
  );
  await chmod(keyringPath, 0o400);
  assert.throws(() => initializeGatewayStateEncryption(), encryptionError("configuration"));

  await writeTestStateKeyring(keyringPath, [
    { keyId: "old-key", status: "active", fillByte: 0x11 }
  ]);
  initializeGatewayStateEncryption();
  const envelope = encryptGatewayStateJson(CONTEXT, { secret: "hidden" }, MAX_PLAINTEXT_BYTES);
  const duplicateEnvelope = envelope.replace(
    '"algorithm":"aes-256-gcm"',
    '"algorithm":"aes-256-gcm","algorithm":"aes-256-gcm"'
  );
  assert.throws(
    () => decryptGatewayStateJson(CONTEXT, duplicateEnvelope, MAX_PLAINTEXT_BYTES),
    encryptionError("integrity")
  );
  assert.throws(
    () => parsePlaintextGatewayStateForMaintenance(
      '{"nested":{"key":1,"k\\u0065y":2}}',
      MAX_PLAINTEXT_BYTES
    ),
    encryptionError("integrity")
  );
});

test("envelope rejects noncanonical encodings and invalid IV or tag lengths", async () => {
  await configuredKeyring();
  const raw = encryptGatewayStateJson(CONTEXT, { secret: "hidden" }, MAX_PLAINTEXT_BYTES);
  const envelope = JSON.parse(raw) as Record<string, string>;
  for (const invalid of [
    { ...envelope, ciphertext_base64url: `${envelope.ciphertext_base64url}=` },
    { ...envelope, iv_base64url: Buffer.alloc(11).toString("base64url") },
    { ...envelope, tag_base64url: Buffer.alloc(15).toString("base64url") }
  ]) {
    assert.throws(
      () => decryptGatewayStateJson(CONTEXT, JSON.stringify(invalid), MAX_PLAINTEXT_BYTES),
      encryptionError("integrity")
    );
  }
});

test("keyring rejects multiple active keys, reused material and compromised material", async () => {
  const keyringPath = await configuredKeyring();
  resetGatewayStateEncryptionForTests();
  await writeTestStateKeyring(keyringPath, [
    { keyId: "active-a", status: "active", fillByte: 0x11 },
    { keyId: "active-b", status: "active", fillByte: 0x22 }
  ]);
  assert.throws(() => initializeGatewayStateEncryption(), encryptionError("configuration"));

  await writeTestStateKeyring(keyringPath, [
    { keyId: "active-a", status: "active", fillByte: 0x11 },
    { keyId: "old-a", status: "decrypt-only", fillByte: 0x11 }
  ]);
  assert.throws(() => initializeGatewayStateEncryption(), encryptionError("configuration"));

  await writeTestStateKeyring(keyringPath, [
    { keyId: "replacement-id", status: "active", fillByte: 0x33 },
    { keyId: "known-compromised-id", status: "compromised", fillByte: 0x33 }
  ]);
  assert.throws(() => initializeGatewayStateEncryption(), encryptionError("configuration"));

  await chmod(keyringPath, 0o600);
  await writeFile(
    keyringPath,
    `${JSON.stringify({
      schema_version: "walksafe.gateway-state-keyring.v1",
      keys: [
        {
          key_id: "active-a",
          status: "active",
          key_base64url: Buffer.alloc(32, 0x11).toString("base64url")
        },
        {
          key_id: "lost-a",
          status: "compromised",
          key_base64url: Buffer.alloc(32, 0x22).toString("base64url")
        }
      ]
    })}\n`,
    "utf8"
  );
  await chmod(keyringPath, 0o400);
  assert.throws(() => initializeGatewayStateEncryption(), encryptionError("configuration"));
});

test("keyring rejects symlinks, hard links and broad permissions", async () => {
  const keyringPath = await configuredKeyring();
  resetGatewayStateEncryptionForTests();
  const symbolicPath = `${keyringPath}.symlink`;
  await symlink(keyringPath, symbolicPath);
  process.env.WALKSAFE_GATEWAY_STATE_KEYRING_FILE = symbolicPath;
  assert.throws(() => initializeGatewayStateEncryption(), encryptionError("configuration"));

  const hardLinkPath = `${keyringPath}.hardlink`;
  await link(keyringPath, hardLinkPath);
  process.env.WALKSAFE_GATEWAY_STATE_KEYRING_FILE = keyringPath;
  assert.throws(() => initializeGatewayStateEncryption(), encryptionError("configuration"));
  await rm(hardLinkPath);

  await chmod(keyringPath, 0o644);
  assert.throws(() => initializeGatewayStateEncryption(), encryptionError("configuration"));
});

test("single-byte JSON values round-trip through the public helper", async () => {
  await configuredKeyring();
  const raw = encryptGatewayStateJson(CONTEXT, 0, MAX_PLAINTEXT_BYTES);
  assert.equal(decryptGatewayStateJson(CONTEXT, raw, MAX_PLAINTEXT_BYTES).value, 0);
});

test("runtime rejects plaintext and startup rejects a missing keyring", async () => {
  const keyringPath = await configuredKeyring();
  assert.throws(
    () => decryptGatewayStateJson(CONTEXT, JSON.stringify({ actorId: "field-operator" }), MAX_PLAINTEXT_BYTES),
    encryptionError("plaintext_rejected")
  );

  resetGatewayStateEncryptionForTests();
  process.env.WALKSAFE_GATEWAY_STATE_KEYRING_FILE = `${keyringPath}.missing`;
  assert.throws(
    () => initializeGatewayStateEncryption(),
    encryptionError("configuration")
  );
});

test("decrypt-only rotation works while compromised and unknown keys never decrypt", async () => {
  const keyringPath = await configuredKeyring();
  const oldEnvelope = encryptGatewayStateJson(
    CONTEXT,
    { secret: "old-key-state" },
    MAX_PLAINTEXT_BYTES
  );

  await writeTestStateKeyring(keyringPath, [
    { keyId: "new-key", status: "active", fillByte: 0x22 },
    { keyId: "old-key", status: "decrypt-only", fillByte: 0x11 }
  ]);
  resetGatewayStateEncryptionForTests();
  initializeGatewayStateEncryption();
  assert.deepEqual(
    inspectGatewayStateEnvelope(oldEnvelope, MAX_PLAINTEXT_BYTES),
    { keyId: "old-key", keyStatus: "decrypt-only" }
  );
  assert.deepEqual(
    decryptGatewayStateJson(CONTEXT, oldEnvelope, MAX_PLAINTEXT_BYTES).value,
    { secret: "old-key-state" }
  );
  assert.equal(
    inspectGatewayStateEnvelope(
      encryptGatewayStateJson(CONTEXT, { secret: "new" }, MAX_PLAINTEXT_BYTES),
      MAX_PLAINTEXT_BYTES
    ).keyId,
    "new-key"
  );

  await writeTestStateKeyring(keyringPath, [
    { keyId: "new-key", status: "active", fillByte: 0x22 },
    { keyId: "old-key", status: "compromised", fillByte: 0x11 }
  ]);
  resetGatewayStateEncryptionForTests();
  initializeGatewayStateEncryption();
  assert.deepEqual(
    inspectGatewayStateEnvelope(oldEnvelope, MAX_PLAINTEXT_BYTES),
    { keyId: "old-key", keyStatus: "compromised" }
  );
  assert.throws(
    () => decryptGatewayStateJson(CONTEXT, oldEnvelope, MAX_PLAINTEXT_BYTES),
    encryptionError("compromised_key")
  );

  const unknownEnvelope = JSON.parse(oldEnvelope) as Record<string, unknown>;
  unknownEnvelope.key_id = "removed-key";
  const unknownRaw = JSON.stringify(unknownEnvelope);
  assert.deepEqual(
    inspectGatewayStateEnvelope(unknownRaw, MAX_PLAINTEXT_BYTES),
    { keyId: "removed-key", keyStatus: "unknown" }
  );
  assert.throws(
    () => decryptGatewayStateJson(CONTEXT, unknownRaw, MAX_PLAINTEXT_BYTES),
    encryptionError("unknown_key")
  );
});

test("production startup requires explicit keyring and field-walk ledger paths", () => {
  assert.throws(
    () => assertGatewayStateStorageConfiguration({ NODE_ENV: "production" }),
    /WALKSAFE_GATEWAY_STATE_KEYRING_FILE is required/
  );
  assert.throws(
    () => assertGatewayStateStorageConfiguration({
      NODE_ENV: "production",
      WALKSAFE_GATEWAY_STATE_KEYRING_FILE: "/etc/walksafe/keyring.json"
    }),
    /WALKSAFE_FIELD_WALK_LEDGER_PATH is required/
  );
});
