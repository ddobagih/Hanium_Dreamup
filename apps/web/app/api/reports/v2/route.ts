import { backendUrl, toBackendResponse } from "../../_backend";

export const runtime = "nodejs";

export async function POST(request: Request): Promise<Response> {
  const formData = await request.formData();
  const response = await fetch(backendUrl("/reports/v2"), {
    method: "POST",
    body: formData,
    cache: "no-store"
  });
  return toBackendResponse(response);
}
