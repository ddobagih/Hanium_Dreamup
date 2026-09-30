// S19-25: privacy ledger lock contention must not stall the Node event loop.
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import * as rights from "../src/privacy-rights.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

const asyncApi = rights as unknown as {
  activateActorGenerationAsync?: (actorId: string) => Promise<number>;
};
// Pre-change builds only have the synchronous form (used for the red run).
const activate = (actorId: string): Promise<number> =>
  asyncApi.activateActorGenerationAsync
    ? asyncApi.activateActorGenerationAsync(actorId)
    : Promise.resolve().then(() => rights.activateActorGeneration(actorId));

let stateDirectory = "";

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-privacy-async-lock-"));
  process.env.NODE_ENV = "test";
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = stateDirectory;
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

async function measureLoopGaps<T>(work: () => Promise<T>): Promise<{ result: PromiseSettledResult<T>; maxGapMs: number }> {
  let last = performance.now();
  let maxGapMs = 0;
  const timer = setInterval(() => {
    const now = performance.now();
    maxGapMs = Math.max(maxGapMs, now - last);
    last = now;
  }, 5);
  try {
    const [result] = await Promise.allSettled([work()]);
    const now = performance.now();
    maxGapMs = Math.max(maxGapMs, now - last);
    return { result: result!, maxGapMs };
  } finally {
    clearInterval(timer);
  }
}

test("S19-25 uncontended async ledger mutation keeps the existing semantics", async () => {
  assert.equal(await activate("privacy-async-uncontended"), 1);
  assert.equal(await activate("privacy-async-uncontended"), 1);
  assert.equal(rights.currentActorGeneration("privacy-async-uncontended"), 1);
  // Interleaves correctly with the synchronous path on the same lock.
  assert.equal(rights.activateActorGeneration("privacy-sync-after-async"), 1);
});

test("S19-25 an external lock holder no longer stalls the event loop; the request still fails busy", async () => {
  // Make sure the ledger directory and lock file exist before the holder opens them.
  assert.equal(await activate("privacy-async-prepare"), 1);
  const moduleUrl = new URL("../src/privacy-rights.js", import.meta.url).href;
  const holder = spawn(process.execPath, [
    "--input-type=module",
    "--eval",
    `const m=await import(${JSON.stringify(moduleUrl)});m.holdPrivacyLedgerFlockForTests();`
  ], { env: process.env, stdio: ["ignore", "pipe", "pipe"] });
  try {
    await new Promise<void>((resolve, reject) => {
      let ready = "";
      let stderr = "";
      holder.stdout.setEncoding("utf8");
      holder.stderr.setEncoding("utf8");
      holder.stderr.on("data", (chunk: string) => { stderr += chunk; });
      holder.stdout.on("data", (chunk: string) => {
        ready += chunk;
        if (ready.includes("privacy-ledger-flock-ready")) resolve();
      });
      holder.once("error", reject);
      holder.once("exit", (code, signal) => {
        if (!ready.includes("privacy-ledger-flock-ready")) {
          reject(new Error(`lock holder exited ${code}/${signal}: ${stderr}`));
        }
      });
    });
    const started = performance.now();
    const { result, maxGapMs } = await measureLoopGaps(() => activate("privacy-async-contended"));
    const elapsedMs = performance.now() - started;
    console.log(JSON.stringify({
      probe: "S19-25",
      contended_elapsed_ms: Math.round(elapsedMs),
      max_timer_gap_ms: Math.round(maxGapMs)
    }));
    assert.equal(result.status, "rejected");
    const reason = (result as PromiseRejectedResult).reason as { code?: string };
    assert.equal(reason.code, "busy");
    assert.ok(elapsedMs >= 900, `busy must still wait about 1 s (${elapsedMs} ms)`);
    assert.ok(maxGapMs < 100, `event loop stalled for ${maxGapMs} ms`);
  } finally {
    const exited = new Promise<void>((resolve) => holder.once("exit", () => resolve()));
    holder.kill("SIGKILL");
    await exited;
  }
  // The kernel releases the dead holder's lock immediately.
  assert.equal(await activate("privacy-async-after-holder"), 1);
});
