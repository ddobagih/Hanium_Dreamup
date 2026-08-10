import { authorizeProxyRequest, backendUrl, fetchBackend, proxyRequestHeaders, toBackendResponse } from "../../_backend";

export const runtime = "nodejs";

export async function GET(request: Request): Promise<Response> {
  const denied = authorizeProxyRequest(request, "admin");
  if (denied) return denied;
  const response = await fetchBackend(request, backendUrl("/reports/summary", request), {
    headers: proxyRequestHeaders(request, "admin", {
      "x-walksafe-read-purpose": "admin_report_summary"
    }),
    cache: "no-store"
  });
  return toBackendResponse(response);
}
