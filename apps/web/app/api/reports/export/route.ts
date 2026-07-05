import { backendUrl, toBackendResponse } from "../../_backend";

export const runtime = "nodejs";

export async function GET(request: Request): Promise<Response> {
  const response = await fetch(backendUrl("/reports/export", request), { cache: "no-store" });
  return toBackendResponse(response, "text/csv; charset=utf-8");
}
