import { authorizeProxyRequest, backendUrl, fetchBackend, proxyRequestHeaders, toBackendResponse } from "../../../_backend";
import { readBoundedTextBody } from "../../../_request-body";

export const runtime = "nodejs";

type RouteContext = {
  params: Promise<{ reportId: string }>;
};

export async function PATCH(request: Request, context: RouteContext): Promise<Response> {
  const denied = authorizeProxyRequest(request, "admin");
  if (denied) return denied;
  const { reportId } = await context.params;
  const bounded = await readBoundedTextBody(request, 4 * 1024);
  if (bounded.error) return bounded.error;
  const response = await fetchBackend(request, backendUrl(`/reports/${encodeURIComponent(reportId)}/status`), {
    method: "PATCH",
    headers: {
      ...Object.fromEntries(proxyRequestHeaders(request, "admin")),
      "content-type": request.headers.get("content-type") ?? "application/json"
    },
    body: bounded.text,
    cache: "no-store"
  });
  return toBackendResponse(response);
}
