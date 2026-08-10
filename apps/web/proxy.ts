import type { NextRequest } from "next/server";

export function proxy(_request: NextRequest): Response {
  return new Response("Legacy Web runtime is closed.\n", {
    status: 410,
    headers: {
      "cache-control": "no-store",
      "content-type": "text/plain; charset=utf-8",
      "x-walksafe-product-boundary": "legacy-web-closed"
    }
  });
}

export const config = {
  matcher: "/:path*"
};
