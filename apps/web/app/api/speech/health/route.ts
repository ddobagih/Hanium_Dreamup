import {
  authorizeProxyRequest,
  fetchBackend,
  toBackendResponse,
  voiceProxyRequestHeaders,
  voiceServiceUnavailableResponse,
  voiceUrl
} from "../../_backend";

export const runtime = "nodejs";

export async function GET(request: Request): Promise<Response> {
  const denied = authorizeProxyRequest(request, "field");
  if (denied) return denied;
  const voiceHeaders = voiceProxyRequestHeaders(request);
  if (!voiceHeaders) return voiceServiceUnavailableResponse();
  const response = await fetchBackend(request, voiceUrl("/health"), {
    headers: voiceHeaders,
    cache: "no-store",
    redirect: "error"
  });
  return toBackendResponse(response);
}
