import {
  clearGatewaySession,
  establishGatewaySession,
  gatewayLoginBusyResponse,
  gatewayLoginRateLimitResponse,
  gatewaySessionStatus,
  gatewayUnavailableResponse,
  isGatewayAccessConfigured,
  recordGatewayLoginAttempt,
  withGatewayLoginLock,
  verifyGatewayCredential
} from "../_gateway-auth";
import { readBoundedJsonBody } from "../_request-body";

export const runtime = "nodejs";

export async function GET(request: Request): Promise<Response> {
  return gatewaySessionStatus("admin", request);
}

export async function POST(request: Request): Promise<Response> {
  if (!isGatewayAccessConfigured("admin")) return gatewayUnavailableResponse();
  const admission = await withGatewayLoginLock(async () => {
    const rateLimited = await gatewayLoginRateLimitResponse("admin", request);
    if (rateLimited) return rateLimited;
    await recordGatewayLoginAttempt("admin", request);
    return null;
  }, gatewayLoginBusyResponse);
  if (admission) return admission;
  const bounded = await readBoundedJsonBody(request, 4 * 1024);
  if (bounded.error) return bounded.error;
  const payload = bounded.value;
  const token = typeof payload === "object" && payload !== null && "token" in payload ? String(payload.token) : "";
  const actorId =
    typeof payload === "object" && payload !== null && "actor_id" in payload ? String(payload.actor_id) : "";
  const verifiedActor = token.length <= 512 ? verifyGatewayCredential("admin", actorId, token) : null;
  if (!verifiedActor) return Response.json({ code: "invalid_admin_token" }, { status: 401 });
  return establishGatewaySession("admin", request, verifiedActor);
}

export async function DELETE(request: Request): Promise<Response> {
  return clearGatewaySession("admin", request);
}
