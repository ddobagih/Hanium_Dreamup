import {
  FIELD_TEST_TOKEN_HEADER,
  backendUrl,
  fetchBackend,
  toBackendResponse,
  type GatewayFetch
} from "./backend.js";
import { gatewayTokenForBackend } from "./auth.js";

/**
 * FP-010 첫 실행 4·5·7·8단계 프록시.
 *
 * 이 네 단계는 계정이 생기기 **전에** 일어나므로 field session 을 요구하지 않는다. 요구하면
 * 세션이 계정을 요구하고 계정이 이 단계들을 요구하는 순환에 걸린다.
 *
 * 대신 Backend 가 배포 환경에서 이 경로를 스스로 503 으로 닫는다. 이메일은 출시 전까지의 한시적
 * 대체이고 메일 발송 수단이 없기 때문이며(product/decisions.md, 2026-08-30), 그 판정은 한 곳에만
 * 두어 Gateway 는 통과시키고 Backend 가 결정한다.
 */

const MAX_BODY_BYTES = 4096;

const ROUTES: ReadonlyMap<string, string> = new Map([
  ["/api/first-run/signups", "/first-run/signups"],
  ["/api/first-run/signups/verify", "/first-run/signups/verify"],
  ["/api/first-run/signups/activate", "/first-run/signups/activate"],
  ["/api/first-run/signups/login", "/first-run/signups/login"]
]);

export const FIRST_RUN_ROUTE_PATHS: readonly string[] = Object.freeze([...ROUTES.keys()]);

export function isFirstRunRoute(pathname: string): boolean {
  return ROUTES.has(pathname);
}

function json(status: number, code: string, message: string): Response {
  return Response.json(
    { detail: { code, message } },
    { status, headers: { "cache-control": "no-store" } }
  );
}

export async function proxyFirstRunRequest(
  request: Request,
  fetchImpl?: GatewayFetch
): Promise<Response> {
  const backendPath = ROUTES.get(new URL(request.url).pathname);
  if (!backendPath) return json(404, "gateway_route_not_found", "Unknown route.");
  if (request.method !== "POST") {
    return json(405, "gateway_method_not_allowed", "Use POST.");
  }

  const body = await request.text();
  if (body.length > MAX_BODY_BYTES) {
    return json(413, "gateway_request_too_large", "Request body is too large.");
  }

  const headers = new Headers({ "content-type": "application/json" });
  // 세션은 없지만 Backend 는 내부 호출자만 받아야 하므로 service token 은 붙인다.
  const token = gatewayTokenForBackend();
  if (token) headers.set(FIELD_TEST_TOKEN_HEADER, token);

  const upstream = await fetchBackend(
    request,
    backendUrl(backendPath),
    { method: "POST", headers, body },
    undefined,
    fetchImpl ?? globalThis.fetch
  );
  return toBackendResponse(upstream);
}
