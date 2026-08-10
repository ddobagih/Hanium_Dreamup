export const GPS_FIX_MAX_AGE_MS = 10_000;
export const GPS_FIX_MAX_FUTURE_SKEW_MS = 1_000;
export const HEADING_FIX_MAX_AGE_MS = 3_000;

export function gpsFixExpiryDelayMs(observedAtMs: number, nowMs: number): number {
  if (!Number.isFinite(observedAtMs) || !Number.isFinite(nowMs)) return 0;
  if (observedAtMs > nowMs + GPS_FIX_MAX_FUTURE_SKEW_MS) return 0;
  return Math.max(0, GPS_FIX_MAX_AGE_MS - Math.max(0, nowMs - observedAtMs));
}

export function isGpsFixFresh(observedAtMs: number, nowMs: number): boolean {
  return gpsFixExpiryDelayMs(observedAtMs, nowMs) > 0;
}

export function headingFixExpiryDelayMs(observedAtMs: number, nowMs: number): number {
  if (!Number.isFinite(observedAtMs) || !Number.isFinite(nowMs)) return 0;
  if (observedAtMs > nowMs + GPS_FIX_MAX_FUTURE_SKEW_MS) return 0;
  return Math.max(0, HEADING_FIX_MAX_AGE_MS - Math.max(0, nowMs - observedAtMs));
}

export function isHeadingFixFresh(observedAtMs: number | null, nowMs: number): boolean {
  return observedAtMs !== null && headingFixExpiryDelayMs(observedAtMs, nowMs) > 0;
}
