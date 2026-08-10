import {
  createCipheriv,
  createDecipheriv,
  createHash,
  randomBytes
} from "node:crypto";
import {
  closeSync,
  constants as fsConstants,
  fstatSync,
  lstatSync,
  openSync,
  readFileSync,
  realpathSync
} from "node:fs";
import type { Stats } from "node:fs";
import path from "node:path";

export const GATEWAY_STATE_ENVELOPE_SCHEMA =
  "walksafe.gateway-state-envelope.v1" as const;
export const GATEWAY_STATE_KEYRING_SCHEMA =
  "walksafe.gateway-state-keyring.v1" as const;

const KEYRING_MAX_BYTES = 64 * 1024;
const KEYRING_MAX_KEYS = 16;
const KEY_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const RECORD_ID = /^[A-Za-z0-9][A-Za-z0-9._-]{0,255}$/;
const BASE64URL = /^[A-Za-z0-9_-]+$/;

export type GatewayStateKind =
  | "short-session"
  | "field-long-session"
  | "field-walk-ledger"
  | "privacy-rights-ledger"
  | "privacy-deletion-v2"
  | "integrated-consent";

export type GatewayStateContext = {
  kind: GatewayStateKind;
  recordId: string;
};

export type GatewayStateKeyStatus =
  | "active"
  | "decrypt-only"
  | "compromised";

type UsableKeyStatus = Exclude<GatewayStateKeyStatus, "compromised">;

type UsableGatewayStateKey =
  | {
      keyId: string;
      status: "active";
      key: Buffer;
    }
  | {
      keyId: string;
      status: "decrypt-only";
      key: Buffer;
    };

type GatewayStateKey =
  | UsableGatewayStateKey
  | {
      keyId: string;
      status: "compromised";
      fingerprint: string;
    };

type LoadedKeyring = {
  path: string;
  active: Extract<UsableGatewayStateKey, { status: "active" }>;
  keys: ReadonlyMap<string, GatewayStateKey>;
};

type StateEnvelope = {
  schema_version: typeof GATEWAY_STATE_ENVELOPE_SCHEMA;
  algorithm: "aes-256-gcm";
  key_id: string;
  iv_base64url: string;
  ciphertext_base64url: string;
  tag_base64url: string;
};

export type GatewayStateEnvelopeMetadata = {
  keyId: string;
  keyStatus: GatewayStateKeyStatus | "unknown";
};

export class GatewayStateEncryptionError extends Error {
  constructor(
    readonly code:
      | "configuration"
      | "integrity"
      | "plaintext_rejected"
      | "unknown_key"
      | "compromised_key",
    message: string,
    options?: ErrorOptions
  ) {
    super(message, options);
    this.name = "GatewayStateEncryptionError";
  }
}

let loadedKeyring: LoadedKeyring | null = null;

function objectValue(value: unknown): Record<string, unknown> | null {
  return typeof value === "object" && value !== null && !Array.isArray(value)
    ? value as Record<string, unknown>
    : null;
}

function exactKeys(value: Record<string, unknown>, expected: readonly string[]): boolean {
  const actual = Object.keys(value).sort();
  const wanted = [...expected].sort();
  return actual.length === wanted.length && actual.every((key, index) => key === wanted[index]);
}

function parseJsonWithoutDuplicateMembers(raw: string): unknown {
  let offset = 0;

  const fail = (message: string): never => {
    throw new SyntaxError(message);
  };
  const skipWhitespace = (): void => {
    while (offset < raw.length && /[\t\n\r ]/.test(raw[offset]!)) offset += 1;
  };
  const parseString = (): string => {
    if (raw[offset] !== '"') fail("expected JSON string");
    const start = offset;
    offset += 1;
    while (offset < raw.length) {
      const character = raw[offset++]!;
      if (character === '"') {
        return JSON.parse(raw.slice(start, offset)) as string;
      }
      if (character === "\\") {
        if (offset >= raw.length) fail("unterminated JSON escape");
        const escape = raw[offset++]!;
        if (escape === "u") {
          const codePoint = raw.slice(offset, offset + 4);
          if (!/^[0-9a-fA-F]{4}$/.test(codePoint)) fail("invalid JSON unicode escape");
          offset += 4;
        } else if (!['"', "\\", "/", "b", "f", "n", "r", "t"].includes(escape)) {
          fail("invalid JSON escape");
        }
      } else if (character.charCodeAt(0) < 0x20) {
        fail("invalid JSON control character");
      }
    }
    return fail("unterminated JSON string");
  };
  const parseLiteral = (literal: string): void => {
    if (raw.slice(offset, offset + literal.length) !== literal) {
      fail("invalid JSON literal");
    }
    offset += literal.length;
  };
  const parseNumber = (): void => {
    const match = /^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?/.exec(
      raw.slice(offset)
    );
    if (match === null) throw new SyntaxError("invalid JSON number");
    offset += match[0].length;
  };
  const parseValue = (): void => {
    skipWhitespace();
    const token = raw[offset];
    if (token === "{") {
      offset += 1;
      const members = new Set<string>();
      skipWhitespace();
      if (raw[offset] === "}") {
        offset += 1;
        return;
      }
      while (offset < raw.length) {
        skipWhitespace();
        const member = parseString();
        if (members.has(member)) fail(`duplicate JSON member: ${member}`);
        members.add(member);
        skipWhitespace();
        if (raw[offset] !== ":") fail("expected JSON member separator");
        offset += 1;
        parseValue();
        skipWhitespace();
        if (raw[offset] === "}") {
          offset += 1;
          return;
        }
        if (raw[offset] !== ",") fail("expected JSON object separator");
        offset += 1;
      }
      fail("unterminated JSON object");
    }
    if (token === "[") {
      offset += 1;
      skipWhitespace();
      if (raw[offset] === "]") {
        offset += 1;
        return;
      }
      while (offset < raw.length) {
        parseValue();
        skipWhitespace();
        if (raw[offset] === "]") {
          offset += 1;
          return;
        }
        if (raw[offset] !== ",") fail("expected JSON array separator");
        offset += 1;
      }
      fail("unterminated JSON array");
    }
    if (token === '"') {
      parseString();
      return;
    }
    if (token === "t") return parseLiteral("true");
    if (token === "f") return parseLiteral("false");
    if (token === "n") return parseLiteral("null");
    parseNumber();
  };

  parseValue();
  skipWhitespace();
  if (offset !== raw.length) fail("unexpected trailing JSON content");
  return JSON.parse(raw) as unknown;
}

function ownedByCurrentProcess(uid: number): boolean {
  return typeof process.getuid === "function" && uid === process.getuid();
}

function validateKeyringPath(rawValue: string): string {
  const value = rawValue.trim();
  if (!value || !path.isAbsolute(value) || path.resolve(value) !== value) {
    throw new GatewayStateEncryptionError(
      "configuration",
      "WALKSAFE_GATEWAY_STATE_KEYRING_FILE must be one canonical absolute path"
    );
  }
  return value;
}

function validateKeyringMetadata(
  filePath: string,
  metadata: Stats,
  environment: NodeJS.ProcessEnv
): void {
  if (
    !metadata.isFile() ||
    metadata.nlink !== 1 ||
    metadata.size < 2 ||
    metadata.size > KEYRING_MAX_BYTES ||
    realpathSync(filePath) !== filePath
  ) {
    throw new GatewayStateEncryptionError(
      "configuration",
      "gateway state keyring must be one canonical regular file with one link"
    );
  }

  const mode = metadata.mode & 0o777;
  if (environment.NODE_ENV === "production") {
    const currentGid = typeof process.getgid === "function" ? process.getgid() : -1;
    if (
      metadata.uid !== 0 ||
      metadata.gid !== currentGid ||
      mode !== 0o640
    ) {
      throw new GatewayStateEncryptionError(
        "configuration",
        "production gateway state keyring must be root-owned, service-group-readable, and mode 0640"
      );
    }
    let ancestor = path.dirname(filePath);
    while (true) {
      const ancestorMetadata = lstatSync(ancestor);
      if (
        !ancestorMetadata.isDirectory() ||
        ancestorMetadata.isSymbolicLink() ||
        realpathSync(ancestor) !== ancestor ||
        ancestorMetadata.uid !== 0 ||
        (ancestorMetadata.mode & 0o022) !== 0
      ) {
        throw new GatewayStateEncryptionError(
          "configuration",
          "production gateway state keyring ancestors must be root-owned non-writable real directories"
        );
      }
      const parent = path.dirname(ancestor);
      if (parent === ancestor) break;
      ancestor = parent;
    }
  } else if (
    !ownedByCurrentProcess(metadata.uid) ||
    (mode & 0o400) === 0 ||
    (mode & 0o077) !== 0
  ) {
    throw new GatewayStateEncryptionError(
      "configuration",
      "non-production gateway state keyring must be owner-readable and inaccessible to group and others"
    );
  }
}

function canonicalBase64UrlBytes(
  value: unknown,
  expectedBytes: number
): Buffer | null {
  if (typeof value !== "string" || !BASE64URL.test(value)) return null;
  const decoded = Buffer.from(value, "base64url");
  return decoded.length === expectedBytes && decoded.toString("base64url") === value
    ? decoded
    : null;
}

function parseKeyring(raw: string, filePath: string): LoadedKeyring {
  let decoded: unknown;
  try {
    decoded = parseJsonWithoutDuplicateMembers(raw);
  } catch (error) {
    throw new GatewayStateEncryptionError(
      "configuration",
      "gateway state keyring is not valid JSON",
      { cause: error }
    );
  }
  const keyring = objectValue(decoded);
  if (
    !keyring ||
    !exactKeys(keyring, ["schema_version", "keys"]) ||
    keyring.schema_version !== GATEWAY_STATE_KEYRING_SCHEMA ||
    !Array.isArray(keyring.keys) ||
    keyring.keys.length < 1 ||
    keyring.keys.length > KEYRING_MAX_KEYS
  ) {
    throw new GatewayStateEncryptionError(
      "configuration",
      "gateway state keyring schema is invalid"
    );
  }

  const keys = new Map<string, GatewayStateKey>();
  const materialFingerprints = new Set<string>();
  for (const rawEntry of keyring.keys) {
    const entry = objectValue(rawEntry);
    if (
      !entry ||
      typeof entry.key_id !== "string" ||
      !KEY_ID.test(entry.key_id) ||
      typeof entry.status !== "string" ||
      !["active", "decrypt-only", "compromised"].includes(entry.status) ||
      keys.has(entry.key_id)
    ) {
      throw new GatewayStateEncryptionError(
        "configuration",
        "gateway state keyring entry is invalid or duplicated"
      );
    }
    if (entry.status === "compromised") {
      if (
        !exactKeys(entry, ["key_id", "status", "key_sha256"]) ||
        typeof entry.key_sha256 !== "string" ||
        !/^[0-9a-f]{64}$/.test(entry.key_sha256) ||
        materialFingerprints.has(entry.key_sha256)
      ) {
        throw new GatewayStateEncryptionError(
          "configuration",
          "compromised gateway state keys must retain one unique SHA-256 fingerprint and no key material"
        );
      }
      materialFingerprints.add(entry.key_sha256);
      keys.set(entry.key_id, {
        keyId: entry.key_id,
        status: "compromised",
        fingerprint: entry.key_sha256
      });
      continue;
    }
    if (!exactKeys(entry, ["key_id", "status", "key_base64url"])) {
      throw new GatewayStateEncryptionError(
        "configuration",
        "usable gateway state key entry schema is invalid"
      );
    }
    const key = canonicalBase64UrlBytes(entry.key_base64url, 32);
    const fingerprint = key === null
      ? null
      : createHash("sha256").update(key).digest("hex");
    if (!key || fingerprint === null || materialFingerprints.has(fingerprint)) {
      throw new GatewayStateEncryptionError(
        "configuration",
        "gateway state key material must be canonical, 256-bit, and unused across the full key history"
      );
    }
    materialFingerprints.add(fingerprint);
    if (entry.status === "active") {
      keys.set(entry.key_id, { keyId: entry.key_id, status: "active", key });
    } else {
      keys.set(entry.key_id, { keyId: entry.key_id, status: "decrypt-only", key });
    }
  }

  const activeKeys = [...keys.values()].filter(
    (entry): entry is Extract<UsableGatewayStateKey, { status: "active" }> => entry.status === "active"
  );
  if (activeKeys.length !== 1) {
    throw new GatewayStateEncryptionError(
      "configuration",
      "gateway state keyring must contain exactly one active key"
    );
  }
  return { path: filePath, active: activeKeys[0]!, keys };
}

function loadKeyring(environment: NodeJS.ProcessEnv): LoadedKeyring {
  const filePath = validateKeyringPath(
    environment.WALKSAFE_GATEWAY_STATE_KEYRING_FILE?.trim() ?? ""
  );
  let descriptor: number | null = null;
  try {
    descriptor = openSync(filePath, fsConstants.O_RDONLY | fsConstants.O_NOFOLLOW);
    const metadata = fstatSync(descriptor);
    validateKeyringMetadata(filePath, metadata, environment);
    const raw = readFileSync(descriptor, "utf8");
    if (Buffer.byteLength(raw, "utf8") > KEYRING_MAX_BYTES) {
      throw new GatewayStateEncryptionError(
        "configuration",
        "gateway state keyring exceeds its byte ceiling"
      );
    }
    return parseKeyring(raw, filePath);
  } catch (error) {
    if (error instanceof GatewayStateEncryptionError) throw error;
    throw new GatewayStateEncryptionError(
      "configuration",
      "gateway state keyring cannot be opened securely",
      { cause: error }
    );
  } finally {
    if (descriptor !== null) closeSync(descriptor);
  }
}

export function initializeGatewayStateEncryption(
  environment: NodeJS.ProcessEnv = process.env
): void {
  const requestedPath = validateKeyringPath(
    environment.WALKSAFE_GATEWAY_STATE_KEYRING_FILE?.trim() ?? ""
  );
  if (loadedKeyring) {
    if (loadedKeyring.path !== requestedPath) {
      throw new GatewayStateEncryptionError(
        "configuration",
        "gateway state keyring cannot change while the process is running"
      );
    }
    return;
  }
  loadedKeyring = loadKeyring(environment);
}

function keyring(): LoadedKeyring {
  initializeGatewayStateEncryption();
  return loadedKeyring!;
}

export function resetGatewayStateEncryptionForTests(): void {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("gateway state encryption reset is test-only");
  }
  if (loadedKeyring) {
    for (const entry of loadedKeyring.keys.values()) {
      if (entry.status !== "compromised") entry.key.fill(0);
    }
  }
  loadedKeyring = null;
}

function validateContext(context: GatewayStateContext): void {
  if (
    ![
      "short-session",
      "field-long-session",
      "field-walk-ledger",
      "privacy-rights-ledger",
      "privacy-deletion-v2",
      "integrated-consent"
    ].includes(context.kind) ||
    !RECORD_ID.test(context.recordId)
  ) {
    throw new GatewayStateEncryptionError(
      "configuration",
      "gateway state encryption context is invalid"
    );
  }
}

function associatedData(context: GatewayStateContext, keyId: string): Buffer {
  validateContext(context);
  return Buffer.from(
    `${GATEWAY_STATE_ENVELOPE_SCHEMA}\0${context.kind}\0${context.recordId}\0${keyId}`,
    "utf8"
  );
}

export function maxGatewayStateEnvelopeBytes(maxPlaintextBytes: number): number {
  if (!Number.isSafeInteger(maxPlaintextBytes) || maxPlaintextBytes < 2) {
    throw new GatewayStateEncryptionError(
      "configuration",
      "gateway state plaintext byte ceiling is invalid"
    );
  }
  return Math.ceil(maxPlaintextBytes * 4 / 3) + 2_048;
}

function serializedPlaintext(value: unknown, maxPlaintextBytes: number): Buffer {
  let encoded: string | undefined;
  try {
    encoded = JSON.stringify(value);
  } catch (error) {
    throw new GatewayStateEncryptionError(
      "integrity",
      "gateway state cannot be serialized as JSON",
      { cause: error }
    );
  }
  if (encoded === undefined) {
    throw new GatewayStateEncryptionError(
      "integrity",
      "gateway state JSON value is unsupported"
    );
  }
  const plaintext = Buffer.from(encoded, "utf8");
  if (plaintext.byteLength > maxPlaintextBytes) {
    throw new GatewayStateEncryptionError(
      "integrity",
      "gateway state plaintext exceeds its byte ceiling"
    );
  }
  return plaintext;
}

export function encryptGatewayStateJson(
  context: GatewayStateContext,
  value: unknown,
  maxPlaintextBytes: number
): string {
  const plaintext = serializedPlaintext(value, maxPlaintextBytes);
  const active = keyring().active;
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", active.key, iv, { authTagLength: 16 });
  cipher.setAAD(associatedData(context, active.keyId), { plaintextLength: plaintext.byteLength });
  const ciphertext = Buffer.concat([cipher.update(plaintext), cipher.final()]);
  const envelope: StateEnvelope = {
    schema_version: GATEWAY_STATE_ENVELOPE_SCHEMA,
    algorithm: "aes-256-gcm",
    key_id: active.keyId,
    iv_base64url: iv.toString("base64url"),
    ciphertext_base64url: ciphertext.toString("base64url"),
    tag_base64url: cipher.getAuthTag().toString("base64url")
  };
  const encoded = `${JSON.stringify(envelope)}\n`;
  if (Buffer.byteLength(encoded, "utf8") > maxGatewayStateEnvelopeBytes(maxPlaintextBytes)) {
    throw new GatewayStateEncryptionError(
      "integrity",
      "gateway state envelope exceeds its byte ceiling"
    );
  }
  return encoded;
}

function parseEnvelope(raw: string, maxPlaintextBytes: number): StateEnvelope {
  if (Buffer.byteLength(raw, "utf8") > maxGatewayStateEnvelopeBytes(maxPlaintextBytes)) {
    throw new GatewayStateEncryptionError(
      "integrity",
      "gateway state envelope exceeds its byte ceiling"
    );
  }
  let decoded: unknown;
  try {
    decoded = parseJsonWithoutDuplicateMembers(raw);
  } catch (error) {
    throw new GatewayStateEncryptionError(
      "integrity",
      "gateway state envelope is not valid JSON",
      { cause: error }
    );
  }
  const envelope = objectValue(decoded);
  if (!envelope || envelope.schema_version !== GATEWAY_STATE_ENVELOPE_SCHEMA) {
    throw new GatewayStateEncryptionError(
      "plaintext_rejected",
      "unencrypted or unsupported gateway state is rejected at runtime"
    );
  }
  if (
    !exactKeys(envelope, [
      "schema_version",
      "algorithm",
      "key_id",
      "iv_base64url",
      "ciphertext_base64url",
      "tag_base64url"
    ]) ||
    envelope.algorithm !== "aes-256-gcm" ||
    typeof envelope.key_id !== "string" ||
    !KEY_ID.test(envelope.key_id) ||
    canonicalBase64UrlBytes(envelope.iv_base64url, 12) === null ||
    canonicalBase64UrlBytes(envelope.tag_base64url, 16) === null ||
    typeof envelope.ciphertext_base64url !== "string" ||
    !BASE64URL.test(envelope.ciphertext_base64url)
  ) {
    throw new GatewayStateEncryptionError(
      "integrity",
      "gateway state envelope schema is invalid"
    );
  }
  const ciphertext = Buffer.from(envelope.ciphertext_base64url, "base64url");
  if (
    ciphertext.byteLength < 1 ||
    ciphertext.byteLength > maxPlaintextBytes ||
    ciphertext.toString("base64url") !== envelope.ciphertext_base64url
  ) {
    throw new GatewayStateEncryptionError(
      "integrity",
      "gateway state ciphertext encoding or size is invalid"
    );
  }
  return envelope as unknown as StateEnvelope;
}

function envelopeKey(envelope: StateEnvelope): UsableGatewayStateKey {
  const key = keyring().keys.get(envelope.key_id);
  if (!key) {
    throw new GatewayStateEncryptionError(
      "unknown_key",
      "gateway state envelope references an unknown key"
    );
  }
  if (key.status === "compromised") {
    throw new GatewayStateEncryptionError(
      "compromised_key",
      "gateway state encrypted by a compromised key is blocked"
    );
  }
  return key;
}

export function inspectGatewayStateEnvelope(
  raw: string,
  maxPlaintextBytes: number
): GatewayStateEnvelopeMetadata {
  const envelope = parseEnvelope(raw, maxPlaintextBytes);
  const key = keyring().keys.get(envelope.key_id);
  return {
    keyId: envelope.key_id,
    keyStatus: key?.status ?? "unknown"
  };
}

export function decryptGatewayStateJson(
  context: GatewayStateContext,
  raw: string,
  maxPlaintextBytes: number
): { value: unknown; keyId: string; keyStatus: UsableKeyStatus } {
  const envelope = parseEnvelope(raw, maxPlaintextBytes);
  const key = envelopeKey(envelope);
  const iv = Buffer.from(envelope.iv_base64url, "base64url");
  const ciphertext = Buffer.from(envelope.ciphertext_base64url, "base64url");
  const tag = Buffer.from(envelope.tag_base64url, "base64url");
  let plaintext: Buffer;
  try {
    const decipher = createDecipheriv("aes-256-gcm", key.key, iv, { authTagLength: 16 });
    decipher.setAAD(associatedData(context, key.keyId), { plaintextLength: ciphertext.byteLength });
    decipher.setAuthTag(tag);
    plaintext = Buffer.concat([decipher.update(ciphertext), decipher.final()]);
  } catch (error) {
    throw new GatewayStateEncryptionError(
      "integrity",
      "gateway state envelope authentication failed",
      { cause: error }
    );
  }
  if (plaintext.byteLength > maxPlaintextBytes) {
    throw new GatewayStateEncryptionError(
      "integrity",
      "gateway state plaintext exceeds its byte ceiling"
    );
  }
  let value: unknown;
  try {
    value = parseJsonWithoutDuplicateMembers(
      new TextDecoder("utf-8", { fatal: true }).decode(plaintext)
    );
  } catch (error) {
    throw new GatewayStateEncryptionError(
      "integrity",
      "decrypted gateway state is not valid UTF-8 JSON",
      { cause: error }
    );
  } finally {
    plaintext.fill(0);
  }
  return { value, keyId: key.keyId, keyStatus: key.status };
}

export function parsePlaintextGatewayStateForMaintenance(
  raw: string,
  maxPlaintextBytes: number
): unknown {
  if (Buffer.byteLength(raw, "utf8") > maxPlaintextBytes) {
    throw new GatewayStateEncryptionError(
      "integrity",
      "plaintext gateway state exceeds its byte ceiling"
    );
  }
  try {
    return parseJsonWithoutDuplicateMembers(raw);
  } catch (error) {
    throw new GatewayStateEncryptionError(
      "integrity",
      "plaintext gateway state migration input is not valid JSON",
      { cause: error }
    );
  }
}
