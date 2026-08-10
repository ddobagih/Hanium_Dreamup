/** Notifies the session gate when an authenticated gateway request expires. */
export const GATEWAY_SESSION_INVALID_EVENT = "walksafe-session-invalid";

export function notifyGatewaySessionInvalid(response: Pick<Response, "status">): void {
  if (response.status !== 401 || typeof window === "undefined") {
    return;
  }

  window.dispatchEvent(new Event(GATEWAY_SESSION_INVALID_EVENT));
}
