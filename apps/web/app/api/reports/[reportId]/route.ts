import { authorizeProxyRequest, backendUrl, fetchBackend, proxyRequestHeaders, toBackendResponse } from "../../_backend";

export const runtime = "nodejs";

type RouteContext = { params: Promise<{ reportId: string }> };

export async function GET(request: Request, context: RouteContext): Promise<Response> {
  const denied = authorizeProxyRequest(request, "admin");
  if (denied) return denied;
  const { reportId } = await context.params;
  const response = await fetchBackend(request, backendUrl(`/reports/${encodeURIComponent(reportId)}`), {
    headers: proxyRequestHeaders(request, "admin", {
      "x-walksafe-read-purpose": "admin_report_detail"
    }),
    cache: "no-store"
  });
  return toBackendResponse(response);
}
