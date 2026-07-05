const BACKEND_API_BASE_URL = (process.env.BACKEND_API_BASE_URL ?? "http://127.0.0.1:8000").replace(/\/+$/, "");

export function backendUrl(path: string, request?: Request): string {
  const query = request ? new URL(request.url).search : "";
  return `${BACKEND_API_BASE_URL}${path}${query}`;
}

export async function toBackendResponse(response: Response, fallbackContentType = "application/json"): Promise<Response> {
  const headers = new Headers();
  const contentType = response.headers.get("content-type") ?? fallbackContentType;
  if (contentType) {
    headers.set("content-type", contentType);
  }

  const contentDisposition = response.headers.get("content-disposition");
  if (contentDisposition) {
    headers.set("content-disposition", contentDisposition);
  }

  const cacheControl = response.headers.get("cache-control");
  if (cacheControl) {
    headers.set("cache-control", cacheControl);
  }

  const requestId = response.headers.get("x-request-id") ?? response.headers.get("x-correlation-id");
  if (requestId) {
    headers.set("x-request-id", requestId);
  }

  return new Response(await response.arrayBuffer(), {
    status: response.status,
    headers
  });
}
