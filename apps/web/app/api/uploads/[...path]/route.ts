import { authorizeProxyRequest, backendUploadPath, backendUrl, fetchBackend, proxyRequestHeaders, toBackendResponse } from "../../_backend";

export const runtime = "nodejs";

type RouteContext = {
  params: Promise<{ path: string[] }>;
};

export async function GET(request: Request, context: RouteContext): Promise<Response> {
  const denied = authorizeProxyRequest(request, "admin");
  if (denied) return denied;
  const { path } = await context.params;
  const uploadPath = backendUploadPath(path);
  if (!uploadPath) {
    return Response.json(
      { detail: { code: "invalid_upload_path", message: "upload filename is invalid" } },
      { status: 400, headers: { "cache-control": "no-store" } }
    );
  }
  const response = await fetchBackend(request, backendUrl(uploadPath), {
    headers: proxyRequestHeaders(request, "admin", {
      "x-walksafe-read-purpose": "admin_report_image"
    }),
    cache: "no-store"
  });
  return toBackendResponse(response, "application/octet-stream");
}
