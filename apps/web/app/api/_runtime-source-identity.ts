import { readFile } from "node:fs/promises";
import { join } from "node:path";

const COMMIT = /^[0-9a-f]{40}$/;

type BuildIdCandidate = {
  exists: boolean;
  value: string | null;
};

async function readBuildId(path: string): Promise<BuildIdCandidate> {
  try {
    const value = (await readFile(path, "utf8")).trim().toLowerCase();
    return { exists: true, value: COMMIT.test(value) ? value : null };
  } catch (error) {
    return (error as NodeJS.ErrnoException).code === "ENOENT"
      ? { exists: false, value: null }
      : { exists: true, value: null };
  }
}

export async function verifiedRuntimeSourceCommit(
  cwd = process.cwd(),
  configuredValue = process.env.WALKSAFE_SOURCE_COMMIT,
  configuredDistDirectory = process.env.WALKSAFE_NEXT_DIST_DIR
): Promise<string | null> {
  const configuredCommit = (configuredValue ?? "").trim().toLowerCase();
  if (!COMMIT.test(configuredCommit)) return null;
  const distDirectory = (configuredDistDirectory ?? "").trim() || ".next";
  if (distDirectory !== ".next" && !/^\.next-[A-Za-z0-9._-]+$/.test(distDirectory)) return null;
  const candidates = await Promise.all([
    readBuildId(join(cwd, "BUILD_ID")),
    readBuildId(join(cwd, distDirectory, "BUILD_ID"))
  ]);
  const present = candidates.filter((candidate) => candidate.exists);
  return present.length > 0 && present.every((candidate) => candidate.value === configuredCommit)
    ? configuredCommit
    : null;
}
