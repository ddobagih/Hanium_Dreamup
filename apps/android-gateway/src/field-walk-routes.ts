import { resolveFieldWalkV1CommandMode } from "./config.js";
import {
  FieldWalkLedgerStorageOutcomeUnknownError,
  getFieldWalk,
  parseFieldWalkCommand,
  commandFieldWalk
} from "./field-walk-ledger.js";
import { type GatewayRouteMatch } from "./route-registry.js";
import {
  type GatewayFieldLongSessionBinding,
  gatewayFieldLongSessionBinding,
  gatewayUnavailableResponse,
  gatewayFieldLongSessionBindingMatches,
  gatewaySessionActor,
  gatewaySessionAccountGeneration
} from "./auth.js";
import { type GatewayFetch } from "./backend.js";
import { readBoundedJsonBody } from "./request-body.js";
import { backendAccountSessionGate } from "./route-responses.js";
import { withFieldActorOperation } from "./request-scope.js";

function fieldWalkRequestInvalid(): Response {
  return Response.json(
    {
      schema_version: "walksafe.field-walk-response.v1",
      code: "field_walk_request_invalid"
    },
    { status: 400, headers: { "cache-control": "no-store" } }
  );
}

function fieldWalkLedgerUnavailable(): Response {
  return Response.json(
    {
      schema_version: "walksafe.field-walk-response.v1",
      code: "field_walk_ledger_unavailable"
    },
    { status: 503, headers: { "cache-control": "no-store" } }
  );
}

/**
 * S19-27: storage failed after the atomic rename, so the command may be committed.
 * Retrying the same request_id is safe (it replays the stored result); a new
 * request_id is not.
 */
function fieldWalkLedgerOutcomeUnknown(): Response {
  return Response.json(
    {
      schema_version: "walksafe.field-walk-response.v1",
      code: "field_walk_ledger_outcome_unknown",
      retry: "same_request_id"
    },
    { status: 503, headers: { "cache-control": "no-store", "retry-after": "1" } }
  );
}

/** S19-17: v1 intake is an explicit opt-in; an unreadable setting fails closed. */
function fieldWalkV1Accepted(): boolean {
  try {
    return resolveFieldWalkV1CommandMode() === "accept";
  } catch {
    return false;
  }
}

/** S19-17: v1 commands (no issue time) are retired by default; nothing was executed. */
function fieldWalkCommandVersionRetired(): Response {
  return Response.json(
    {
      schema_version: "walksafe.field-walk-response.v1",
      code: "walk_command_version_retired",
      supported_schema_versions: ["walksafe.field-walk-command.v2"]
    },
    { status: 409, headers: { "cache-control": "no-store" } }
  );
}

function fieldWalkLedgerFailure(error: unknown): Response {
  return error instanceof FieldWalkLedgerStorageOutcomeUnknownError
    ? fieldWalkLedgerOutcomeUnknown()
    : fieldWalkLedgerUnavailable();
}

function fieldWalkUnauthorized(): Response {
  return Response.json(
    {
      schema_version: "walksafe.field-walk-response.v1",
      code: "gateway_unauthorized"
    },
    { status: 401, headers: { "cache-control": "no-store" } }
  );
}

export async function fieldWalk(
  request: Request,
  match: GatewayRouteMatch,
  resolveBinding: (
    request: Request
  ) => GatewayFieldLongSessionBinding | null = gatewayFieldLongSessionBinding,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  if (match.body !== "by-method") return gatewayUnavailableResponse();
  const url = new URL(request.url);
  if (match.query === "none" && [...url.searchParams].length > 0) {
    return fieldWalkRequestInvalid();
  }
  const binding = resolveBinding(request);
  if (!binding) return fieldWalkUnauthorized();
  const backendGate = await backendAccountSessionGate(request, fetchImpl);
  if (backendGate) return backendGate;
  const execute = (): Response => {
    try {
      const result = getFieldWalk(binding);
      return Response.json(result.body, { status: result.status });
    } catch (error) {
      return fieldWalkLedgerFailure(error);
    }
  };
  if (request.method === "GET") {
    return withFieldActorOperation(
      binding.actorId,
      execute,
      binding.accountGeneration ?? null
    );
  }
  const bounded = await readBoundedJsonBody(request, 4 * 1024);
  if (bounded.error) return bounded.error;
  const bindingIsCurrent = resolveBinding === gatewayFieldLongSessionBinding
    ? gatewayFieldLongSessionBindingMatches(request, binding)
    : (() => {
        const current = resolveBinding(request);
        return current !== null &&
          current.actorId === binding.actorId &&
          current.accountId === binding.accountId &&
          current.deviceId === binding.deviceId &&
          current.familyId === binding.familyId &&
          current.sessionRotation === binding.sessionRotation &&
          (current.accountGeneration ?? null) ===
            (binding.accountGeneration ?? null);
      })();
  if (!bindingIsCurrent) return fieldWalkUnauthorized();
  const command = parseFieldWalkCommand(bounded.value);
  if (!command) return fieldWalkRequestInvalid();
  if (command.schema_version === "walksafe.field-walk-command.v1" && !fieldWalkV1Accepted()) {
    return fieldWalkCommandVersionRetired();
  }
  return withFieldActorOperation(binding.actorId, () => {
    try {
      const result = commandFieldWalk(binding, command);
      return Response.json(result.body, { status: result.status });
    } catch (error) {
      return fieldWalkLedgerFailure(error);
    }
  }, binding.accountGeneration ?? null);
}

/**
 * FP-012-DETAIL-02 interim: while one device holds the account's walk lease, route and
 * report requests from any other session are refused; with no active walk nothing
 * changes. Unreadable walk state fails closed (FP-012/FP-047: never skip the check).
 * Owner decision 3(b) (2026-09-29): reports are sent only after the walk has ended, so
 * `holderMayProceed: false` also refuses the holder's own session during its walk.
 */
export function walkOwnershipGate(
  request: Request,
  { holderMayProceed }: { holderMayProceed: boolean } = { holderMayProceed: true }
): Response | null {
  const actorId = gatewaySessionActor(request);
  if (!actorId) return null;
  let binding: GatewayFieldLongSessionBinding | null;
  try {
    binding = gatewayFieldLongSessionBinding(request);
  } catch {
    return fieldWalkLedgerUnavailable();
  }
  const accountGeneration = gatewaySessionAccountGeneration(request);
  // Sessions without a device binding (v5/v6) can never be the walk holder.
  const probe: GatewayFieldLongSessionBinding = binding ?? {
    actorId,
    accountId: accountGeneration === null ? actorId : `${actorId}:generation:${accountGeneration}`,
    deviceId: "",
    familyId: "",
    sessionRotation: 0,
    accountGeneration
  };
  let result: ReturnType<typeof getFieldWalk>;
  try {
    result = getFieldWalk(probe);
  } catch {
    return fieldWalkLedgerUnavailable();
  }
  if (result.status !== 200) return fieldWalkLedgerUnavailable();
  if (result.body.result !== "ACTIVE") return null;
  if (result.body.held_by_current_device === true) {
    if (holderMayProceed) return null;
    return Response.json(
      {
        detail: {
          code: "report_walk_in_progress",
          message: "Reports are sent after this device's walk has ended."
        }
      },
      { status: 409, headers: { "cache-control": "no-store" } }
    );
  }
  return Response.json(
    {
      detail: {
        code: "walk_owned_by_other_device",
        message: "Another device holds this account's active walk."
      }
    },
    { status: 409, headers: { "cache-control": "no-store" } }
  );
}

