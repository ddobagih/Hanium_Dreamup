import { authorizeProxyRequest, backendUrl, fetchBackend, proxyRequestHeaders, toBackendResponse } from "../../../../_backend";

export const runtime = "nodejs";

export async function GET(request: Request): Promise<Response> {
  const denied = authorizeProxyRequest(request, "field");
  if (denied) return denied;
  const response = await fetchBackend(request, backendUrl("/navigation/destinations/search/health"), {
    headers: proxyRequestHeaders(request, "field"),
    cache: "no-store"
  });
  return toBackendResponse(response);
}
