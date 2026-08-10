import {
  acquireImageUploadAdmission,
  authorizeProxyRequest,
  backendUrl,
  fetchBackend,
  IMAGE_MULTIPART_LIMIT_BYTES,
  proxyRequestHeaders,
  readBoundedMultipartFormData,
  toBackendResponse
} from "../../_backend";

export const runtime = "nodejs";

export async function POST(request: Request): Promise<Response> {
  const denied = authorizeProxyRequest(request, "field");
  if (denied) return denied;
  const headers = proxyRequestHeaders(request, "field");
  const admission = acquireImageUploadAdmission(request, headers);
  if (admission.error) return admission.error;
  try {
    const multipart = await readBoundedMultipartFormData(request, IMAGE_MULTIPART_LIMIT_BYTES);
    if (multipart.error) return multipart.error;
    const response = await fetchBackend(request, backendUrl("/detect/v2"), {
      method: "POST",
      headers,
      body: multipart.formData,
      cache: "no-store"
    });
    return toBackendResponse(response);
  } finally {
    admission.release();
  }
}
