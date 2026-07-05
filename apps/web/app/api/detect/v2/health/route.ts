import { backendUrl, toBackendResponse } from "../../../_backend";

export const runtime = "nodejs";

export async function GET(): Promise<Response> {
  const response = await fetch(backendUrl("/detect/v2/health"), { cache: "no-store" });
  return toBackendResponse(response);
}
