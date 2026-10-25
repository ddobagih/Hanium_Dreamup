import { createHash } from "node:crypto";

export const ACTOR_ID = /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/;

export const DEVICE_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$/;

export const OPAQUE_ID = /^[A-Za-z0-9_-]{24,128}$/;

const SHA256_DIGEST = /^[0-9a-f]{64}$/;

// S19-7/S01-25: refresh tokens carry a per-family HMAC proof of (family, rotation), so a
// consumed token is recognized cryptographically instead of by an ever-growing digest list.
const REFRESH_PROOF_KEY = /^[A-Za-z0-9_-]{43}$/;

export const MAX_STORED_FAMILIES = 64;

export const MAX_REFRESH_ROTATIONS = 4096;

// Owner decision 2 (S19-8): with an unlimited absolute lifetime the 4096-rotation cap would
// be a hidden maximum login age (4096 x access TTL), so it applies only to finite lifetimes.
// This bound only keeps stored and parsed rotation numbers finite.
export const MAX_REFRESH_ROTATION_NUMBER = 2_147_483_647;

export type FieldSessionRevokeReason =
  | "login_replaced"
  | "logout"
  | "selective_revoke"
  | "account_lock"
  | "security_incident"
  | "refresh_token_reuse"
  | "refresh_idle_expired"
  | "refresh_absolute_expired"
  | "refresh_rotation_limit"
  | "backend_binding_denied"
  // Root decision R2 (login-F2): with an unlimited idle window the least recently rotated
  // device family makes room for a new login at the 32-device cap.
  | "capacity_evicted";

export type BackendEmailAccountBinding = {
  actorId: string;
  accountGeneration: number;
  authEpoch: number;
  deviceId: string;
};

export type FieldSessionFamily = {
  device_id: string;
  family_id: string;
  rotation: number;
  current_refresh_digest_sha256: string | null;
  /** Legacy digest history only; frozen once refresh_proof_key_b64url exists. */
  consumed_refresh_digests_sha256: string[];
  /** Per-family key for refresh-token proofs; absent only on pre-S19-7 families. */
  refresh_proof_key_b64url?: string;
  current_access_digest_sha256: string | null;
  access_expires_at_epoch_ms: number;
  /** null = no idle expiry (owner decision 2: `unlimited`). */
  idle_expires_at_epoch_ms: number | null;
  /** null = no absolute expiry (owner decision 2: `unlimited`). */
  absolute_expires_at_epoch_ms: number | null;
  created_at_epoch_ms: number;
  last_rotated_at_epoch_ms: number;
  revoked_at_epoch_ms: number | null;
  revoke_reason: FieldSessionRevokeReason | null;
};

export type BackendFieldSessionFamily = FieldSessionFamily & {
  binding: BackendEmailAccountBinding;
};

export type FieldSessionActorState = {
  schema_version: 1;
  actor_id: string;
  families: FieldSessionFamily[];
  backend_families?: BackendFieldSessionFamily[];
  /**
   * login-F3: when a refresh-token reuse last ended every device of the actor. Written in the
   * same atomic state write as the family revocation, so short sessions (v5/v6/v7) issued at
   * or before it are rejected by auth.ts even if their own store could not be updated.
   */
  reuse_revoked_at_epoch_ms?: number;
};

export function isSafeEpoch(value: unknown): value is number {
  return Number.isSafeInteger(value) && Number(value) > 0;
}

function isRevokeReason(value: unknown): value is FieldSessionRevokeReason | null {
  return value === null || [
    "login_replaced",
    "logout",
    "selective_revoke",
    "account_lock",
    "security_incident",
    "refresh_token_reuse",
    "refresh_idle_expired",
    "refresh_absolute_expired",
    "refresh_rotation_limit",
    "backend_binding_denied",
    "capacity_evicted"
  ].includes(String(value));
}

export function validBackendBinding(value: unknown): value is BackendEmailAccountBinding {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const binding = value as Partial<BackendEmailAccountBinding>;
  return typeof binding.actorId === "string" && ACTOR_ID.test(binding.actorId) &&
    typeof binding.deviceId === "string" && DEVICE_ID.test(binding.deviceId) &&
    Number.isSafeInteger(binding.accountGeneration) && Number(binding.accountGeneration) >= 1 &&
    Number.isSafeInteger(binding.authEpoch) && Number(binding.authEpoch) >= 1;
}

export function backendFamilies(state: Pick<FieldSessionActorState, "backend_families">): BackendFieldSessionFamily[] {
  return state.backend_families ?? [];
}

function validFamily(value: unknown): value is FieldSessionFamily {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const family = value as Partial<FieldSessionFamily>;
  return (
    typeof family.device_id === "string" &&
    DEVICE_ID.test(family.device_id) &&
    typeof family.family_id === "string" &&
    OPAQUE_ID.test(family.family_id) &&
    Number.isSafeInteger(family.rotation) &&
    Number(family.rotation) >= 0 &&
    Number(family.rotation) <= MAX_REFRESH_ROTATION_NUMBER &&
    (family.current_refresh_digest_sha256 === null ||
      (typeof family.current_refresh_digest_sha256 === "string" &&
        SHA256_DIGEST.test(family.current_refresh_digest_sha256))) &&
    Array.isArray(family.consumed_refresh_digests_sha256) &&
    family.consumed_refresh_digests_sha256.length <= MAX_REFRESH_ROTATIONS &&
    family.consumed_refresh_digests_sha256.every(
      (digest) => typeof digest === "string" && SHA256_DIGEST.test(digest)
    ) &&
    new Set(family.consumed_refresh_digests_sha256).size ===
      family.consumed_refresh_digests_sha256.length &&
    (family.current_access_digest_sha256 === null ||
      (typeof family.current_access_digest_sha256 === "string" &&
        SHA256_DIGEST.test(family.current_access_digest_sha256))) &&
    (family.refresh_proof_key_b64url === undefined ||
      (typeof family.refresh_proof_key_b64url === "string" &&
        REFRESH_PROOF_KEY.test(family.refresh_proof_key_b64url))) &&
    isSafeEpoch(family.access_expires_at_epoch_ms) &&
    (family.idle_expires_at_epoch_ms === null || isSafeEpoch(family.idle_expires_at_epoch_ms)) &&
    (family.absolute_expires_at_epoch_ms === null ||
      isSafeEpoch(family.absolute_expires_at_epoch_ms)) &&
    // An unlimited idle window exists only under an unlimited absolute lifetime.
    (family.idle_expires_at_epoch_ms !== null || family.absolute_expires_at_epoch_ms === null) &&
    isSafeEpoch(family.created_at_epoch_ms) &&
    isSafeEpoch(family.last_rotated_at_epoch_ms) &&
    (family.revoked_at_epoch_ms === null || isSafeEpoch(family.revoked_at_epoch_ms)) &&
    isRevokeReason(family.revoke_reason) &&
    ((family.revoked_at_epoch_ms === null && family.revoke_reason === null) ||
      (family.revoked_at_epoch_ms !== null && family.revoke_reason !== null))
  );
}

export function validActorState(value: unknown, actorId: string): value is FieldSessionActorState {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const state = value as Partial<FieldSessionActorState>;
  if (
    state.schema_version !== 1 ||
    state.actor_id !== actorId ||
    !ACTOR_ID.test(actorId) ||
    !Array.isArray(state.families) ||
    state.families.length > MAX_STORED_FAMILIES ||
    !state.families.every(validFamily) ||
    (state.reuse_revoked_at_epoch_ms !== undefined &&
      !isSafeEpoch(state.reuse_revoked_at_epoch_ms)) ||
    (state.backend_families !== undefined && (
      !Array.isArray(state.backend_families) ||
      state.backend_families.length > MAX_STORED_FAMILIES ||
      !state.backend_families.every((family) =>
        validFamily(family) && validBackendBinding(family.binding) &&
        family.binding.actorId === actorId && family.binding.deviceId === family.device_id
      )
    ))
  ) {
    return false;
  }
  const familyIds = state.families.map((family) => family.family_id);
  const activeDevices = state.families
    .filter((family) => family.revoked_at_epoch_ms === null)
    .map((family) => family.device_id);
  const backend = backendFamilies(state);
  const backendFamilyIds = backend.map((family) => family.family_id);
  const activeBackendDevices = backend
    .filter((family) => family.revoked_at_epoch_ms === null)
    .map((family) => family.device_id);
  return (
    new Set(familyIds).size === familyIds.length &&
    new Set(activeDevices).size === activeDevices.length &&
    new Set(backendFamilyIds).size === backendFamilyIds.length &&
    new Set(activeBackendDevices).size === activeBackendDevices.length
  );
}

export function validFieldLongSessionStateForMaintenance(
  value: unknown,
  recordId: string
): boolean {
  if (typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const actorId = (value as Partial<FieldSessionActorState>).actor_id;
  if (typeof actorId !== "string" || !validActorState(value, actorId)) return false;
  const digest = createHash("sha256")
    .update(`field-long-session\0${actorId}`)
    .digest("hex");
  return recordId === `${digest}.json`;
}

