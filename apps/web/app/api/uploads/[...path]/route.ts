import { backendUrl, toBackendResponse } from "../../_backend";

export const runtime = "nodejs";

type RouteContext = {
  params: Promise<{ path: string[] }>;
};

export async function GET(_request: Request, context: RouteContext): Promise<Response> {
  const { path } = await context.params;
  const safePath = path.map((part) => encodeURIComponent(part)).join("/");
  const response = await fetch(backendUrl(`/uploads/${safePath}`), { cache: "no-store" });
  return toBackendResponse(response, "application/octet-stream");
}
