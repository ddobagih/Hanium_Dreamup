/**
 * S19-10: low-cost admission for the public refresh grant, separate from the login failure
 * limits. It runs after the body is parsed and before any session state file is read or
 * locked, so a flood of refresh requests (valid or not) cannot turn into unbounded state
 * decryption or lock waits.
 *
 * - per trusted client IP: 60 refresh requests per minute (429 when exceeded). In production
 *   the adapter refuses a request without one valid trusted address (S19-53); a request that
 *   reaches this point without one there still shares one fixed bucket instead of skipping
 *   the limit;
 * - per claimed (actor, refresh family): 30 refused refresh verdicts per minute (R12: only
 *   400/401/403/409 — invalid, expired, revoked, reused or conflicting proof). A successful
 *   rotation, a storage failure, a client cancellation (499) or a deadline (504) does not
 *   consume it. The family id is a server-chosen random value, so invalid proofs sent for an
 *   actor under guessed families never lock the families of that actor's real devices;
 *   repeated invalid proofs for one family stop before the state read;
 * - globally: 16 refreshes in progress at once (503 field_session_storage_busy, retryable).
 *
 * Counters live in process memory like the other in-memory limits of this single-process
 * Gateway and restart empty.
 */
export const FIELD_SESSION_REFRESH_PER_CLIENT_PER_MINUTE = 60;
export const FIELD_SESSION_REFRESH_FAILURES_PER_FAMILY_PER_MINUTE = 30;
export const FIELD_SESSION_REFRESH_MAX_CONCURRENT = 16;
const WINDOW_MS = 60_000;
/** Bounds the memory of each counter map; the oldest windows are dropped first. */
const MAX_TRACKED_KEYS = 16_384;
/** gw-F2: production requests without a usable trusted client address share this bucket. */
const UNBOUND_CLIENT_KEY = "unbound";
/** R12: the refresh verdicts that count as a refusal of the presented proof. */
const COUNTED_REFUSAL_STATUSES: ReadonlySet<number> = new Set([400, 401, 403, 409]);

type RefreshAdmissionLimits = {
  perClientPerMinute: number;
  failuresPerFamilyPerMinute: number;
  maxConcurrent: number;
};

type Window = { startedAtEpochMs: number; count: number };

let testLimits: RefreshAdmissionLimits | null = null;
let inFlight = 0;
const clientWindows = new Map<string, Window>();
const familyFailureWindows = new Map<string, Window>();

function limits(): RefreshAdmissionLimits {
  return testLimits ?? {
    perClientPerMinute: FIELD_SESSION_REFRESH_PER_CLIENT_PER_MINUTE,
    failuresPerFamilyPerMinute: FIELD_SESSION_REFRESH_FAILURES_PER_FAMILY_PER_MINUTE,
    maxConcurrent: FIELD_SESSION_REFRESH_MAX_CONCURRENT
  };
}

/** Tests only: other limits, or null for the defaults; always clears the counters. */
export function setFieldSessionRefreshAdmissionForTests(next: RefreshAdmissionLimits | null): void {
  if (process.env.NODE_ENV === "production") {
    throw new Error("refresh admission test limits are unavailable in production");
  }
  testLimits = next;
  inFlight = 0;
  clientWindows.clear();
  familyFailureWindows.clear();
}

function current(windows: Map<string, Window>, key: string, now: number): Window {
  const existing = windows.get(key);
  if (existing && now - existing.startedAtEpochMs < WINDOW_MS && now >= existing.startedAtEpochMs) {
    return existing;
  }
  const fresh = { startedAtEpochMs: now, count: 0 };
  windows.delete(key);
  windows.set(key, fresh);
  while (windows.size > MAX_TRACKED_KEYS) {
    const oldest = windows.keys().next().value;
    if (oldest === undefined) break;
    windows.delete(oldest);
  }
  return fresh;
}

function retryAfterSeconds(window: Window, now: number): number {
  return Math.max(1, Math.ceil((window.startedAtEpochMs + WINDOW_MS - now) / 1000));
}

function rateLimited(seconds: number): Response {
  return Response.json(
    {
      code: "field_session_refresh_rate_limited",
      message: "세션 갱신 요청이 너무 많습니다. 잠시 후 같은 요청을 다시 보내세요.",
      retryable: true
    },
    {
      status: 429,
      headers: { "cache-control": "no-store", "retry-after": String(seconds) }
    }
  );
}

function busy(): Response {
  return Response.json(
    {
      code: "field_session_storage_busy",
      message: "다른 현장 세션 갱신이 완료될 때까지 다시 시도해야 합니다.",
      retryable: true
    },
    { status: 503, headers: { "cache-control": "no-store", "retry-after": "1" } }
  );
}

export type FieldSessionRefreshAdmission =
  | { admitted: false; response: Response }
  | { admitted: true; finish: (status: number) => void };

/**
 * Admits one refresh. `clientIp` is the trusted client address: null when none is usable,
 * which outside production (no proxy) skips the per-client limit; in production the adapter
 * already refused such a request (S19-53), and one that gets here anyway (a direct
 * handleGatewayRequest caller) is counted in one fixed bucket. `actorId` and `familyId` are
 * the claimed (unverified) refresh family. The caller must call `finish` exactly once with
 * the response status.
 */
export function admitFieldSessionRefresh(
  clientIp: string | null,
  actorId: string,
  familyId: string,
  nowEpochMs = Date.now()
): FieldSessionRefreshAdmission {
  const active = limits();
  const familyKey = JSON.stringify([actorId, familyId]);
  const familyWindow = current(familyFailureWindows, familyKey, nowEpochMs);
  if (familyWindow.count >= active.failuresPerFamilyPerMinute) {
    return { admitted: false, response: rateLimited(retryAfterSeconds(familyWindow, nowEpochMs)) };
  }
  const clientKey = clientIp ?? (process.env.NODE_ENV === "production" ? UNBOUND_CLIENT_KEY : null);
  if (clientKey !== null) {
    const clientWindow = current(clientWindows, clientKey, nowEpochMs);
    if (clientWindow.count >= active.perClientPerMinute) {
      return { admitted: false, response: rateLimited(retryAfterSeconds(clientWindow, nowEpochMs)) };
    }
    clientWindow.count += 1;
  }
  if (inFlight >= active.maxConcurrent) return { admitted: false, response: busy() };
  inFlight += 1;
  let finished = false;
  return {
    admitted: true,
    finish: (status) => {
      if (finished) return;
      finished = true;
      inFlight = Math.max(0, inFlight - 1);
      if (COUNTED_REFUSAL_STATUSES.has(status)) {
        current(familyFailureWindows, familyKey, Date.now()).count += 1;
      }
    }
  };
}
