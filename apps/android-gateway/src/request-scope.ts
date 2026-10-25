import {
  type PrivacyOperationLease,
  beginPrivacyOperation,
  finishPrivacyOperation,
  revalidatePrivacyOperation
} from "./privacy-rights.js";
import { isAccountGenerationFencedV2 } from "./privacy-deletion-v2.js";
import { fieldLongSessionStorageOutcomeUnknownResponse } from "./field-long-session.js";
import { cancelUpstream } from "./route-responses.js";

export function privacyLedgerUnavailable(): Response {
  return Response.json(
    { code: "privacy_ledger_unavailable" },
    { status: 503, headers: { "cache-control": "no-store" } }
  );
}

export function privacyOperationInactive(): Response {
  return Response.json(
    {
      detail: {
        code: "account_generation_inactive",
        message: "The account generation is no longer active."
      }
    },
    { status: 409, headers: { "cache-control": "no-store" } }
  );
}

type GatewayPrivacyFaultPointForTests =
  | "after_lease"
  | "refresh_pre_operation"
  | "refresh_before_commit"
  | "refresh_post_operation";

let privacyFaultPointForTests: GatewayPrivacyFaultPointForTests | null = null;

export function setGatewayPrivacyFaultForTests(
  point: GatewayPrivacyFaultPointForTests | null
): void {
  if (process.env.NODE_ENV !== "test") {
    throw new Error("gateway privacy fault injection is test-only");
  }
  privacyFaultPointForTests = point;
}

function injectGatewayPrivacyFaultForTests(point: GatewayPrivacyFaultPointForTests): void {
  if (process.env.NODE_ENV !== "test" || privacyFaultPointForTests !== point) return;
  privacyFaultPointForTests = null;
  throw new Error(`injected gateway privacy ledger fault: ${point}`);
}

export function startPrivacyOperation(
  actorId: string,
  expectedAccountGeneration: number | null = null
):
  | { lease: PrivacyOperationLease; error?: never }
  | { lease?: never; error: Response } {
  let lease: PrivacyOperationLease | null = null;
  try {
    lease = beginPrivacyOperation(actorId);
    if (!lease) return { error: privacyOperationInactive() };
    injectGatewayPrivacyFaultForTests("after_lease");
    if (
      expectedAccountGeneration !== null &&
      lease.accountGeneration !== expectedAccountGeneration
    ) {
      finishPrivacyOperation(lease);
      return { error: privacyOperationInactive() };
    }
    if (isAccountGenerationFencedV2(actorId, lease.accountGeneration)) {
      finishPrivacyOperation(lease);
      return { error: privacyOperationInactive() };
    }
    return { lease };
  } catch {
    if (lease) finishPrivacyOperation(lease);
    return { error: privacyLedgerUnavailable() };
  }
}

export function privacyOperationState(
  lease: PrivacyOperationLease,
  faultPoint?: GatewayPrivacyFaultPointForTests
): "current" | "inactive" | "unavailable" {
  try {
    if (faultPoint) injectGatewayPrivacyFaultForTests(faultPoint);
    return revalidatePrivacyOperation(lease) &&
      !isAccountGenerationFencedV2(lease.actorId, lease.accountGeneration)
      ? "current" : "inactive";
  } catch {
    return "unavailable";
  }
}

export function privacyOperationIsCurrent(lease: PrivacyOperationLease): boolean {
  return privacyOperationState(lease) === "current";
}

export function responseWithPrivacyOperation(
  response: Response,
  lease: PrivacyOperationLease,
  requestSignal: AbortSignal
): Response {
  if (!response.body) {
    finishPrivacyOperation(lease);
    return response;
  }

  const reader = response.body.getReader();
  const signal = AbortSignal.any([lease.controller.signal, requestSignal]);
  let controller: ReadableStreamDefaultController<Uint8Array> | null = null;
  let terminal = false;
  let finished = false;
  let readPending = false;
  let readerReleased = false;
  const releaseReader = (): void => {
    if (readerReleased || readPending) return;
    try {
      reader.releaseLock();
      readerReleased = true;
    } catch { /* a pending cancellation can retain the lock */ }
  };
  const finish = (): void => {
    if (finished) return;
    finished = true;
    signal.removeEventListener("abort", abort);
    finishPrivacyOperation(lease);
  };
  const cancelReader = (reason: unknown): void => {
    void reader.cancel(reason).catch(() => undefined).finally(releaseReader);
  };
  const abort = (): void => {
    if (terminal) return;
    terminal = true;
    const reason = signal.reason ?? new Error("privacy operation was cancelled");
    try { controller?.error(reason); } catch { /* already terminal */ }
    finish();
    cancelReader(reason);
  };

  try {
    const guarded = new ReadableStream<Uint8Array>({
      start(streamController) {
        controller = streamController;
        signal.addEventListener("abort", abort, { once: true });
        if (signal.aborted) abort();
      },
      async pull(streamController) {
        if (terminal) return;
        readPending = true;
        try {
          const chunk = await reader.read();
          readPending = false;
          if (terminal) {
            releaseReader();
            return;
          }
          if (signal.aborted) {
            abort();
            releaseReader();
            return;
          }
          if (chunk.done) {
            terminal = true;
            streamController.close();
            releaseReader();
            finish();
            return;
          }
          streamController.enqueue(chunk.value);
        } catch (error) {
          readPending = false;
          if (!terminal) {
            terminal = true;
            try { streamController.error(error); } catch { /* already terminal */ }
            finish();
            cancelReader(error);
          }
          releaseReader();
        }
      },
      async cancel(reason) {
        if (terminal) return;
        terminal = true;
        finish();
        try {
          await reader.cancel(reason);
        } finally {
          releaseReader();
        }
      }
    }, { highWaterMark: 0 });
    return new Response(guarded, {
      status: response.status,
      statusText: response.statusText,
      headers: response.headers
    });
  } catch (error) {
    terminal = true;
    finish();
    cancelReader(error);
    throw error;
  }
}

export async function withFieldActorOperation(
  actorId: string,
  operation: (lease: PrivacyOperationLease) => Response | Promise<Response>,
  expectedAccountGeneration: number | null = null,
  refreshRotationCommitted?: () => boolean
): Promise<Response> {
  const privacy = startPrivacyOperation(actorId, expectedAccountGeneration);
  if (privacy.error) return privacy.error;
  try {
    const before = refreshRotationCommitted
      ? privacyOperationState(privacy.lease, "refresh_pre_operation")
      : privacyOperationIsCurrent(privacy.lease) ? "current" : "inactive";
    if (before !== "current") {
      return before === "unavailable"
        ? privacyLedgerUnavailable() : privacyOperationInactive();
    }
    const response = await operation(privacy.lease);
    const after = refreshRotationCommitted
      ? privacyOperationState(privacy.lease, "refresh_post_operation")
      : privacyOperationIsCurrent(privacy.lease) ? "current" : "inactive";
    if (after !== "current") {
      if (after === "unavailable" && response.status === 500) return response;
      cancelUpstream(response);
      if (after === "unavailable") {
        return refreshRotationCommitted?.()
          ? fieldLongSessionStorageOutcomeUnknownResponse()
          : privacyLedgerUnavailable();
      }
      return privacyOperationInactive();
    }
    return response;
  } finally {
    finishPrivacyOperation(privacy.lease);
  }
}

