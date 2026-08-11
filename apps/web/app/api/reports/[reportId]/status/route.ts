import { backendUrl, toBackendResponse } from "../../../_backend";

export const runtime = "nodejs";

type RouteContext = {
  params: Promise<{ reportId: string }>;
};

export async function PATCH(request: Request, context: RouteContext): Promise<Response> {
  const { reportId } = await context.params;
  const body = await request.text();
  const response = await fetch(backendUrl(`/reports/${encodeURIComponent(reportId)}/status`), {
    method: "PATCH",
    headers: {
      "content-type": request.headers.get("content-type") ?? "application/json"
    },
    body,
    cache: "no-store"
  });
  return toBackendResponse(response);
}
