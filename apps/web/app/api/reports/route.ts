import { backendUrl, toBackendResponse } from "../_backend";

export const runtime = "nodejs";

export async function GET(request: Request): Promise<Response> {
  const response = await fetch(backendUrl("/reports", request), { cache: "no-store" });
  return toBackendResponse(response);
}

export async function POST(request: Request): Promise<Response> {
  const formData = await request.formData();
  const response = await fetch(backendUrl("/reports"), {
    method: "POST",
    body: formData,
    cache: "no-store"
  });
  return toBackendResponse(response);
}
