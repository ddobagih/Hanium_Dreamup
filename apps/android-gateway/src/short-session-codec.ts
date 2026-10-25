import { createHash } from "node:crypto";

export const SESSION_MAX_AGE_SECONDS = 12 * 60 * 60;

export const ACTOR_ID = /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/;

export const DEVICE_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;

export const SHORT_DEVICE_SESSION_LIMIT = 32;

export type BackendAccountSessionBinding = Readonly<{
  actorId: string;
  accountGeneration: number;
  authEpoch: number;
  deviceId?: string | null;
}>;

export type GatewaySessionScope = "general" | "account_deletion_recovery";

export type LegacyGatewaySessionIdentity = {
  actorId: string;
  sessionId: string;
  expiresAtSeconds: number;
  sessionScope: GatewaySessionScope;
};

export type BackendGatewaySessionIdentity = LegacyGatewaySessionIdentity & {
  sessionKind: "backend_account";
  accountGeneration: number;
  authEpoch: number;
};

export type BackendDeviceGatewaySessionIdentity = LegacyGatewaySessionIdentity & {
  sessionKind: "backend_account_device";
  accountGeneration: number;
  authEpoch: number;
  deviceId: string;
};

export type GatewaySessionIdentity =
  | LegacyGatewaySessionIdentity
  | BackendGatewaySessionIdentity
  | BackendDeviceGatewaySessionIdentity;

// The exact three-key shape adds one v6 slot without a second file or lock.
// Existing singleton, device-ledger and watermark shapes remain readable.
// Older readers reject the combined shape; mixed-version writers and rollback
// deployment are not supported by this internal format extension.
// The existing device cap counts v7 entries; the v6 slot remains singular.
export type BackendDeviceGatewaySessionLedger = {
  actorId: string;
  sessions: BackendDeviceGatewaySessionIdentity[];
  compatibilitySession?: BackendGatewaySessionIdentity;
};

export type BackendGatewaySessionWatermark = {
  actorId: string;
  accountGeneration: number;
  authEpoch: number;
};

export type GatewaySessionState =
  | GatewaySessionIdentity
  | BackendDeviceGatewaySessionLedger
  | BackendGatewaySessionWatermark;

export class GatewaySessionDeviceCapacityError extends Error {
  constructor() {
    super("gateway short-session device capacity is unavailable");
  }
}

export class GatewaySessionRollbackError extends Error {
  constructor() {
    super("gateway account session generation or authentication epoch would roll back");
  }
}

export function validGatewaySessionScope(value: unknown): value is GatewaySessionScope {
  return value === "general" || value === "account_deletion_recovery";
}

export function validShortSessionStateForMaintenance(
  value: unknown,
  recordId: string
): boolean {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const state = value as Record<string, unknown>;
  const keys = Object.keys(value).sort().join("\0");
  const legacyKeys = ["actorId", "expiresAtSeconds", "sessionId"].sort().join("\0");
  const scopedKeys = ["actorId", "expiresAtSeconds", "sessionId", "sessionScope"]
    .sort()
    .join("\0");
  const backendKeys = [
    "accountGeneration",
    "actorId",
    "authEpoch",
    "expiresAtSeconds",
    "sessionId",
    "sessionKind",
    "sessionScope"
  ].sort().join("\0");
  const backendDeviceKeys = [...backendKeys.split("\0"), "deviceId"]
    .sort().join("\0");
  const watermarkKeys = ["actorId", "accountGeneration", "authEpoch"]
    .sort().join("\0");
  if (keys === watermarkKeys) {
    if (
      typeof state.actorId !== "string" ||
      !ACTOR_ID.test(state.actorId) ||
      !Number.isSafeInteger(state.accountGeneration) ||
      Number(state.accountGeneration) < 1 ||
      !Number.isSafeInteger(state.authEpoch) ||
      Number(state.authEpoch) < 1
    ) return false;
    const digest = createHash("sha256")
      .update(`field\0${state.actorId}`)
      .digest("hex");
    return recordId === `field-${digest}.json`;
  }
  const ledgerKeys = ["actorId", "sessions"].sort().join("\0");
  const coexistenceKeys = ["actorId", "sessions", "compatibilitySession"]
    .sort().join("\0");
  if (keys === ledgerKeys || keys === coexistenceKeys) {
    if (
      typeof state.actorId !== "string" ||
      !ACTOR_ID.test(state.actorId) ||
      !Array.isArray(state.sessions) ||
      state.sessions.length === 0 ||
      state.sessions.length > SHORT_DEVICE_SESSION_LIMIT
    ) return false;
    const devices = new Set<string>();
    const sessionIds = new Set<string>();
    let accountGeneration: number | null = null;
    let authEpoch: number | null = null;
    for (const session of state.sessions) {
      if (
        typeof session !== "object" ||
        session === null ||
        Array.isArray(session)
      ) return false;
      const entry = session as Record<string, unknown>;
      if (
        Object.keys(entry).sort().join("\0") !== backendDeviceKeys ||
        entry.actorId !== state.actorId ||
        entry.sessionKind !== "backend_account_device" ||
        !Number.isSafeInteger(entry.accountGeneration) ||
        Number(entry.accountGeneration) < 1 ||
        !Number.isSafeInteger(entry.authEpoch) ||
        Number(entry.authEpoch) < 1 ||
        typeof entry.deviceId !== "string" ||
        !DEVICE_ID.test(entry.deviceId) ||
        typeof entry.sessionId !== "string" ||
        !/^[A-Za-z0-9_-]{32,128}$/.test(entry.sessionId) ||
        entry.sessionScope !== "general" ||
        !Number.isSafeInteger(entry.expiresAtSeconds) ||
        Number(entry.expiresAtSeconds) <= 0 ||
        devices.has(entry.deviceId) ||
        sessionIds.has(entry.sessionId)
      ) return false;
      accountGeneration ??= Number(entry.accountGeneration);
      authEpoch ??= Number(entry.authEpoch);
      if (
        entry.accountGeneration !== accountGeneration ||
        entry.authEpoch !== authEpoch
      ) return false;
      devices.add(entry.deviceId);
      sessionIds.add(entry.sessionId);
    }
    if (keys === coexistenceKeys) {
      const compatibility = state.compatibilitySession;
      if (
        typeof compatibility !== "object" ||
        compatibility === null ||
        Array.isArray(compatibility)
      ) return false;
      const entry = compatibility as Record<string, unknown>;
      if (
        Object.keys(entry).sort().join("\0") !== backendKeys ||
        entry.sessionKind !== "backend_account" ||
        entry.actorId !== state.actorId ||
        entry.accountGeneration !== accountGeneration ||
        entry.authEpoch !== authEpoch ||
        typeof entry.sessionId !== "string" ||
        sessionIds.has(entry.sessionId) ||
        !validShortSessionStateForMaintenance(entry, recordId)
      ) return false;
    }
    const digest = createHash("sha256")
      .update(`field\0${state.actorId}`)
      .digest("hex");
    return recordId === `field-${digest}.json`;
  }
  const legacyV3State = keys === legacyKeys;
  const backendAccountState = keys === backendKeys;
  const backendDeviceState = keys === backendDeviceKeys;
  if (
    (!legacyV3State && keys !== scopedKeys && !backendAccountState && !backendDeviceState) ||
    typeof state.actorId !== "string" ||
    !ACTOR_ID.test(state.actorId) ||
    typeof state.sessionId !== "string" ||
    !/^[A-Za-z0-9_-]{32,128}$/.test(state.sessionId) ||
    (!legacyV3State && !validGatewaySessionScope(state.sessionScope)) ||
    !Number.isSafeInteger(state.expiresAtSeconds) ||
    Number(state.expiresAtSeconds) <= 0
  ) {
    return false;
  }
  if ((backendAccountState || backendDeviceState) && (
    state.sessionKind !== (backendDeviceState
      ? "backend_account_device"
      : "backend_account") ||
    !Number.isSafeInteger(state.accountGeneration) ||
    Number(state.accountGeneration) < 1 ||
    !Number.isSafeInteger(state.authEpoch) ||
    Number(state.authEpoch) < 1
  )) return false;
  if (backendDeviceState && (
    typeof state.deviceId !== "string" ||
    !DEVICE_ID.test(state.deviceId) ||
    state.sessionScope !== "general"
  )) return false;
  const digest = createHash("sha256")
    .update(`field\0${state.actorId}`)
    .digest("hex");
  return recordId === `field-${digest}.json`;
}

