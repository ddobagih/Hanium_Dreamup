import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { GET } from "../app/sw-version.js/route";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

async function withRuntimeBuild(
  sourceCommit: string,
  buildId: string | null,
  distDirectory: string,
  check: () => Promise<void>
): Promise<void> {
  const originalCwd = process.cwd();
  const originalSourceCommit = process.env.WALKSAFE_SOURCE_COMMIT;
  const originalDistDirectory = process.env.WALKSAFE_NEXT_DIST_DIR;
  const root = await mkdtemp(join(tmpdir(), "walksafe-sw-version-"));
  try {
    if (buildId !== null) {
      await mkdir(join(root, distDirectory));
      await writeFile(join(root, distDirectory, "BUILD_ID"), `${buildId}\n`, "utf8");
    }
    process.chdir(root);
    process.env.WALKSAFE_SOURCE_COMMIT = sourceCommit;
    process.env.WALKSAFE_NEXT_DIST_DIR = distDirectory;
    await check();
  } finally {
    process.chdir(originalCwd);
    if (originalSourceCommit === undefined) delete process.env.WALKSAFE_SOURCE_COMMIT;
    else process.env.WALKSAFE_SOURCE_COMMIT = originalSourceCommit;
    if (originalDistDirectory === undefined) delete process.env.WALKSAFE_NEXT_DIST_DIR;
    else process.env.WALKSAFE_NEXT_DIST_DIR = originalDistDirectory;
    await rm(root, { recursive: true, force: true });
  }
}

async function testVerifiedReleaseScript(): Promise<void> {
  const sourceCommit = "a".repeat(40);
  for (const distDirectory of [".next", ".next-pwa-release-test"]) {
    await withRuntimeBuild(sourceCommit, sourceCommit, distDirectory, async () => {
      const response = await GET();
      assert(response.status === 200, "a build-bound release script should be available");
      assert(response.headers.get("cache-control") === "no-store", "the release script must bypass HTTP caches");
      assert(
        response.headers.get("content-type")?.startsWith("application/javascript") === true,
        "the release identity must be served as JavaScript"
      );
      assert(
        (await response.text()) === `self.WALKSAFE_SW_SOURCE_COMMIT = ${JSON.stringify(sourceCommit)};\n`,
        "the imported release script must bind the worker to the verified build commit"
      );
    });
  }
}

async function testUnverifiedReleaseScriptFailsClosed(): Promise<void> {
  const sourceCommit = "a".repeat(40);
  for (const buildId of [null, "b".repeat(40)]) {
    await withRuntimeBuild(sourceCommit, buildId, ".next", async () => {
      const response = await GET();
      assert(response.status === 503, "missing or mismatched build identity must reject worker installation");
      assert((await response.text()).includes("throw new Error"), "the rejected script must also fail closed if evaluated");
    });
  }
  await withRuntimeBuild(sourceCommit, sourceCommit, "unsafe", async () => {
    const response = await GET();
    assert(response.status === 503, "an unsafe runtime build directory must reject worker installation");
  });
}

async function main(): Promise<void> {
  await testVerifiedReleaseScript();
  await testUnverifiedReleaseScriptFailsClosed();
  console.log("pwa release version policy checks passed");
}

void main().catch((error: unknown) => {
  console.error(error);
  process.exitCode = 1;
});
