import { verifiedRuntimeSourceCommit } from "../_runtime-source-identity";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

export async function GET(): Promise<Response> {
  const sourceCommit = await verifiedRuntimeSourceCommit();
  const ready = sourceCommit !== null;
  return Response.json(
    {
      schema_version: "walksafe.web-release-identity.v1",
      status: ready ? "ready" : "not_ready",
      ...(ready ? { source_commit: sourceCommit } : {})
    },
    {
      status: ready ? 200 : 503,
      headers: { "Cache-Control": "no-store" }
    }
  );
}
