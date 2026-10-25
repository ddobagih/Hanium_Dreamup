import { gatewaySessionAuthenticatedAtEpochMs } from "./auth.js";
import { FieldLongSessionStorageUnavailableError } from "./field-long-session.js";

/** S19-44: a new account deletion needs a credential check within this window. */
export const ACCOUNT_DELETION_REAUTHENTICATION_WINDOW_MS = 5 * 60 * 1000;

const ACCOUNT_DELETION_AUTH_TIME_FUTURE_SKEW_MS = 60 * 1000;

/**
 * S19-44 / S19-4-F1: true when the credential login behind the session happened within the
 * sensitive-operation window. `onStorageFailure` decides a failed auth-time read: account
 * deletion counts it as "not recent" (GWBE-10), remote session revocation rethrows the
 * long-session storage error so it is answered as a storage failure, never as a pass.
 */
export function sessionRecentlyAuthenticated(
  request: Request,
  nowEpochMs: number,
  onStorageFailure: "not_recent" | "storage_error"
): boolean {
  let authenticatedAt: number | null;
  try {
    authenticatedAt = gatewaySessionAuthenticatedAtEpochMs(request, nowEpochMs);
  } catch (error) {
    if (
      onStorageFailure === "storage_error" &&
      error instanceof FieldLongSessionStorageUnavailableError
    ) throw error;
    return false;
  }
  return authenticatedAt !== null &&
    authenticatedAt <= nowEpochMs + ACCOUNT_DELETION_AUTH_TIME_FUTURE_SKEW_MS &&
    nowEpochMs - authenticatedAt <= ACCOUNT_DELETION_REAUTHENTICATION_WINDOW_MS;
}

