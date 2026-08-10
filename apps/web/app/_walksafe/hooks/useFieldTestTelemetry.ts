"use client";

/** Sends consent-bound, metadata-only field snapshots and tears them down with the active session. */
import { useEffect, useRef } from "react";

export type FieldTelemetrySnapshot = Record<string, unknown>;
export const FIELD_TELEMETRY_CONSENT_VERSION = "walksafe.telemetry-consent.v1";
export const FIELD_TELEMETRY_CONSENT_STORAGE_KEY = "walksafe-field-telemetry-consent-v1";
const FIELD_TELEMETRY_SESSION_STORAGE_KEY = "walksafe-field-session-id";
export const FIELD_TELEMETRY_CONSENT_MAX_AGE_MS = 12 * 60 * 60 * 1000;
const FIELD_TELEMETRY_REQUEST_TIMEOUT_MS = 5_000;
let telemetryTransportGeneration = 0;
let activeTelemetryAbortController: AbortController | null = null;
const pendingTelemetryRequests = new Set<Promise<void>>();

type StoredConsent = {
  version: string;
  actor_id: string;
  accepted_at: string;
  expires_at: string;
};

type StoredSession = {
  actor_id: string;
  session_id: string;
};

function validActorId(actorId: string | null | undefined): actorId is string {
  return typeof actorId === "string" && /^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$/.test(actorId);
}

export function isFieldTelemetryConsentRecordValid(
  value: unknown,
  actorId: string | null | undefined,
  nowMs = Date.now()
): boolean {
  if (!validActorId(actorId) || typeof value !== "object" || value === null || Array.isArray(value)) return false;
  const stored = value as Partial<StoredConsent>;
  const acceptedAtMs = typeof stored.accepted_at === "string" ? Date.parse(stored.accepted_at) : Number.NaN;
  const expiresAtMs = typeof stored.expires_at === "string" ? Date.parse(stored.expires_at) : Number.NaN;
  return (
    stored.version === FIELD_TELEMETRY_CONSENT_VERSION &&
    stored.actor_id === actorId &&
    Number.isFinite(acceptedAtMs) &&
    Number.isFinite(expiresAtMs) &&
    acceptedAtMs <= nowMs + 60_000 &&
    expiresAtMs > nowMs &&
    expiresAtMs <= acceptedAtMs + FIELD_TELEMETRY_CONSENT_MAX_AGE_MS
  );
}

export function hasStoredFieldTelemetryConsent(actorId: string | null | undefined, nowMs = Date.now()): boolean {
  if (typeof window === "undefined") return false;
  if (!validActorId(actorId)) return false;
  try {
    const stored = JSON.parse(window.localStorage.getItem(FIELD_TELEMETRY_CONSENT_STORAGE_KEY) ?? "null") as unknown;
    return isFieldTelemetryConsentRecordValid(stored, actorId, nowMs);
  } catch {
    return false;
  }
}

export function storeFieldTelemetryConsent(actorId: string): void {
  if (!validActorId(actorId)) throw new Error("valid actor id is required for telemetry consent");
  const acceptedAt = new Date();
  window.localStorage.setItem(
    FIELD_TELEMETRY_CONSENT_STORAGE_KEY,
    JSON.stringify({
      version: FIELD_TELEMETRY_CONSENT_VERSION,
      actor_id: actorId,
      accepted_at: acceptedAt.toISOString(),
      expires_at: new Date(acceptedAt.getTime() + FIELD_TELEMETRY_CONSENT_MAX_AGE_MS).toISOString()
    } satisfies StoredConsent)
  );
}

function storedSessionForActor(actorId: string | null | undefined): StoredSession | null {
  if (!validActorId(actorId)) return null;
  try {
    const decoded = JSON.parse(window.sessionStorage.getItem(FIELD_TELEMETRY_SESSION_STORAGE_KEY) ?? "null") as Partial<StoredSession> | null;
    return decoded?.actor_id === actorId && typeof decoded.session_id === "string"
      ? { actor_id: actorId, session_id: decoded.session_id }
      : null;
  } catch {
    return null;
  }
}

async function stopFieldTelemetryTransport(): Promise<void> {
  telemetryTransportGeneration += 1;
  activeTelemetryAbortController?.abort();
  activeTelemetryAbortController = null;
  await Promise.allSettled([...pendingTelemetryRequests]);
}

export async function withdrawFieldTelemetryConsent(actorId: string | null | undefined): Promise<boolean> {
  await stopFieldTelemetryTransport();
  const sessionId = storedSessionForActor(actorId)?.session_id ?? null;
  let deleted = false;
  try {
    if (sessionId) {
      const response = await fetch(`/api/walksafe-field-log?session_id=${encodeURIComponent(sessionId)}`, {
        method: "DELETE",
        credentials: "same-origin",
        cache: "no-store"
      });
      if (!response.ok) throw new Error("field telemetry deletion failed");
      const result = (await response.json()) as { deleted?: boolean };
      deleted = result.deleted === true;
    }
    return deleted;
  } finally {
    window.localStorage.removeItem(FIELD_TELEMETRY_CONSENT_STORAGE_KEY);
    window.sessionStorage.removeItem(FIELD_TELEMETRY_SESSION_STORAGE_KEY);
  }
}

function createSessionId(): string {
  const randomPart =
    typeof crypto !== "undefined" && typeof crypto.randomUUID === "function"
      ? crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`;
  return `web-${new Date().toISOString().slice(0, 10)}-${randomPart}`;
}

function sessionIdForActor(actorId: string): string {
  const stored = storedSessionForActor(actorId);
  if (stored) return stored.session_id;
  const sessionId = createSessionId();
  window.sessionStorage.setItem(
    FIELD_TELEMETRY_SESSION_STORAGE_KEY,
    JSON.stringify({ actor_id: actorId, session_id: sessionId } satisfies StoredSession)
  );
  return sessionId;
}

async function sendTelemetry(
  sessionId: string,
  eventType: "session_start" | "heartbeat" | "visibility" | "session_end",
  payload: FieldTelemetrySnapshot,
  generation: number,
  signal: AbortSignal
): Promise<void> {
  if (generation !== telemetryTransportGeneration || signal.aborted) return;
  if (eventType === "heartbeat" && pendingTelemetryRequests.size > 0) return;
  const requestAbortController = new AbortController();
  const abortFromSession = () => requestAbortController.abort();
  signal.addEventListener("abort", abortFromSession, { once: true });
  const timeoutId = window.setTimeout(
    () => requestAbortController.abort(),
    FIELD_TELEMETRY_REQUEST_TIMEOUT_MS
  );
  const request = fetch("/api/walksafe-field-log", {
    method: "POST",
    credentials: "same-origin",
    cache: "no-store",
    keepalive: true,
    signal: requestAbortController.signal,
    headers: {
      "content-type": "application/json",
      "x-walksafe-telemetry-consent": FIELD_TELEMETRY_CONSENT_VERSION
    },
    body: JSON.stringify({
      schema_version: "walksafe.field-telemetry.v1",
      session_id: sessionId,
      captured_at: new Date().toISOString(),
      event_type: eventType,
      payload
    })
  })
    .then((response) => {
      if (!response.ok && response.status !== 401) {
        throw new Error(`field telemetry failed: ${response.status}`);
      }
    })
    .finally(() => {
      window.clearTimeout(timeoutId);
      signal.removeEventListener("abort", abortFromSession);
    });
  pendingTelemetryRequests.add(request);
  void request.then(
    () => pendingTelemetryRequests.delete(request),
    () => pendingTelemetryRequests.delete(request)
  );
  await request;
}

export function useFieldTestTelemetry(
  snapshot: FieldTelemetrySnapshot,
  enabled = true,
  actorId: string | null = null
): void {
  const snapshotRef = useRef(snapshot);
  useEffect(() => {
    snapshotRef.current = snapshot;
  }, [snapshot]);

  useEffect(() => {
    if (!enabled || !validActorId(actorId)) return;
    telemetryTransportGeneration += 1;
    const generation = telemetryTransportGeneration;
    activeTelemetryAbortController?.abort();
    const abortController = new AbortController();
    activeTelemetryAbortController = abortController;
    const sessionId = sessionIdForActor(actorId);

    const currentPayload = () => ({
      ...snapshotRef.current,
      online: navigator.onLine,
      visibility_state: document.visibilityState,
      user_agent: navigator.userAgent,
      viewport: {
        width: window.innerWidth,
        height: window.innerHeight,
        device_pixel_ratio: window.devicePixelRatio
      }
    });
    void sendTelemetry(sessionId, "session_start", currentPayload(), generation, abortController.signal).catch(() => undefined);
    const intervalId = window.setInterval(() => {
      void sendTelemetry(sessionId, "heartbeat", currentPayload(), generation, abortController.signal).catch(() => undefined);
    }, 1000);
    const onVisibilityChange = () => {
      void sendTelemetry(sessionId, "visibility", currentPayload(), generation, abortController.signal).catch(() => undefined);
    };
    const onPageHide = () => {
      void sendTelemetry(sessionId, "session_end", currentPayload(), generation, abortController.signal).catch(() => undefined);
    };
    document.addEventListener("visibilitychange", onVisibilityChange);
    window.addEventListener("pagehide", onPageHide);
    return () => {
      window.clearInterval(intervalId);
      document.removeEventListener("visibilitychange", onVisibilityChange);
      window.removeEventListener("pagehide", onPageHide);
      if (telemetryTransportGeneration === generation) {
        telemetryTransportGeneration += 1;
        abortController.abort();
        if (activeTelemetryAbortController === abortController) {
          activeTelemetryAbortController = null;
        }
      }
    };
  }, [actorId, enabled]);

}
