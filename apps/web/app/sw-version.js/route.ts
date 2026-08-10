import { verifiedRuntimeSourceCommit } from "../api/_runtime-source-identity";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const HEADERS = {
  "Cache-Control": "no-store",
  "Content-Type": "application/javascript; charset=utf-8"
};

export async function GET(): Promise<Response> {
  const sourceCommit = await verifiedRuntimeSourceCommit();
  if (sourceCommit === null) {
    return new Response('throw new Error("WalkSafe release identity is unavailable");\n', {
      status: 503,
      headers: HEADERS
    });
  }
  return new Response(`self.WALKSAFE_SW_SOURCE_COMMIT = ${JSON.stringify(sourceCommit)};\n`, {
    status: 200,
    headers: HEADERS
  });
}
