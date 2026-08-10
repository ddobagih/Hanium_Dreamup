import { createHash } from "node:crypto";
import { chmod, mkdir, writeFile } from "node:fs/promises";
import path from "node:path";

import {
  decryptGatewayStateJson,
  encryptGatewayStateJson,
  GATEWAY_STATE_KEYRING_SCHEMA,
  initializeGatewayStateEncryption,
  resetGatewayStateEncryptionForTests,
  type GatewayStateContext,
  type GatewayStateKeyStatus
} from "../src/encrypted-json-store.js";

export type TestStateKey = {
  keyId: string;
  status: GatewayStateKeyStatus;
  fillByte?: number;
};

export const TEST_ACTIVE_KEY_ID = "test-state-active";

export async function writeTestStateKeyring(
  filePath: string,
  keys: readonly TestStateKey[] = [
    { keyId: TEST_ACTIVE_KEY_ID, status: "active", fillByte: 0x41 }
  ]
): Promise<void> {
  await mkdir(path.dirname(filePath), { recursive: true, mode: 0o700 });
  try {
    await chmod(filePath, 0o600);
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code !== "ENOENT") throw error;
  }
  const payload = {
    schema_version: GATEWAY_STATE_KEYRING_SCHEMA,
    keys: keys.map((entry, index) => entry.status === "compromised"
      ? {
          key_id: entry.keyId,
          status: entry.status,
          key_sha256: createHash("sha256")
            .update(Buffer.alloc(32, entry.fillByte ?? index + 1))
            .digest("hex")
        }
      : {
          key_id: entry.keyId,
          status: entry.status,
          key_base64url: Buffer.alloc(32, entry.fillByte ?? index + 1).toString("base64url")
        })
  };
  await writeFile(filePath, `${JSON.stringify(payload)}\n`, { encoding: "utf8", mode: 0o600 });
  await chmod(filePath, 0o400);
}

export async function configureTestStateEncryption(
  filePath: string,
  keys?: readonly TestStateKey[]
): Promise<void> {
  process.env.NODE_ENV = "test";
  process.env.WALKSAFE_GATEWAY_STATE_KEYRING_FILE = filePath;
  await writeTestStateKeyring(filePath, keys);
  resetGatewayStateEncryptionForTests();
  initializeGatewayStateEncryption();
}

export function decryptTestStateFile<T>(
  context: GatewayStateContext,
  raw: string,
  maxPlaintextBytes: number
): T {
  return decryptGatewayStateJson(context, raw, maxPlaintextBytes).value as T;
}

export function encryptTestStateFile(
  context: GatewayStateContext,
  value: unknown,
  maxPlaintextBytes: number
): string {
  return encryptGatewayStateJson(context, value, maxPlaintextBytes);
}
