import {
  AUDIO_MULTIPART_LIMIT_BYTES,
  acquireVoiceSttUploadAdmission,
  authorizeProxyRequest,
  fetchBackend,
  readBoundedMultipartFormData,
  toBackendResponse,
  voiceProxyRequestHeaders,
  voiceServiceUnavailableResponse,
  voiceUrl
} from "../../_backend";

export const runtime = "nodejs";

export async function POST(request: Request): Promise<Response> {
  const denied = authorizeProxyRequest(request, "field");
  if (denied) return denied;
  const voiceHeaders = voiceProxyRequestHeaders(request);
  if (!voiceHeaders) return voiceServiceUnavailableResponse();
  const admission = acquireVoiceSttUploadAdmission(voiceHeaders);
  if (admission.error) return admission.error;
  try {
    const multipart = await readBoundedMultipartFormData(request, AUDIO_MULTIPART_LIMIT_BYTES);
    if (multipart.error) return multipart.error;
    const response = await fetchBackend(request, voiceUrl("/speech/stt"), {
      method: "POST",
      headers: voiceHeaders,
      body: multipart.formData,
      cache: "no-store",
      redirect: "error"
    });
    return toBackendResponse(response);
  } finally {
    admission.release();
  }
}
