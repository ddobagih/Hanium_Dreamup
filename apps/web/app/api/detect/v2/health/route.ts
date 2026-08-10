import { authorizeProxyRequest, backendUrl, fetchBackend, proxyRequestHeaders, toBackendResponse } from "../../../_backend";

export const runtime = "nodejs";

export async function GET(request: Request): Promise<Response> {
  const denied = authorizeProxyRequest(request, "admin");
  if (denied) return denied;
  const response = await fetchBackend(request, backendUrl("/detect/v2/health"), {
    headers: proxyRequestHeaders(request, "admin"),
    cache: "no-store"
  });
  return toBackendResponse(response);
}
