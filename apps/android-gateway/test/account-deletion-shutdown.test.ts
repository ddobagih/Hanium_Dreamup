import assert from "node:assert/strict";
import { spawn, type ChildProcess } from "node:child_process";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { createServer, type AddressInfo } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import { resolveGatewayServiceRuntimeLockPath } from "../src/config.js";
import { acquireExclusiveFileLockLease, ExclusiveFileLockBusyError } from "../src/exclusive-file-lock.js";
import { PRIVACY_DELETION_V2_MAX_BYTES } from "../src/privacy-deletion-v2.js";
import { configureTestStateEncryption, decryptTestStateFile } from "./state-encryption-fixture.js";

type Ledger = { requests: Record<string, { outbox: Array<{ attempts: number; next_attempt_at: string }> }> };
type Exit = { code: number | null; signal: NodeJS.Signals | null };
type Child = { process: ChildProcess; exited: Promise<Exit>; stdout: () => string; stderr: () => string };

async function waitFor(condition: () => boolean | Promise<boolean>, failure: string, limitMs = 3_000): Promise<void> {
  const end = performance.now() + limitMs;
  while (!await condition()) {
    assert.ok(performance.now() < end, failure);
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}

async function availablePort(): Promise<number> {
  const server = createServer();
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const port = (server.address() as AddressInfo).port;
  await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  return port;
}

function launch(directory: string, port: number, mode: string): Child {
  const child = spawn(process.execPath, [
    fileURLToPath(new URL("./account-deletion-shutdown-worker.js", import.meta.url)), mode
  ], {
    stdio: ["pipe", "pipe", "pipe", "ipc"],
    env: {
      ...process.env, NODE_ENV: "test", WALKSAFE_ENVIRONMENT: "test",
      BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
      WALKSAFE_FIELD_TEST_TOKEN: "shutdown-internal-token-1234567890123456",
      WALKSAFE_GATEWAY_SESSION_SECRET: "shutdown-session-secret-123456789012345678901234",
      WALKSAFE_FIELD_ACCOUNTS_JSON: "[]",
      WALKSAFE_GATEWAY_RATE_LIMIT_DIR: directory,
      WALKSAFE_GATEWAY_STATE_KEYRING_FILE: path.join(directory, "keyring.json"),
      WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(directory, "walk.json"),
      WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL: "https://privacy.invalid/requests",
      WALKSAFE_ANDROID_GATEWAY_HOST: "127.0.0.1",
      WALKSAFE_ANDROID_GATEWAY_PORT: String(port),
      WALKSAFE_VOICE_ENABLED: "false",
      WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "false"
    }
  });
  let stdout = "", stderr = "";
  child.stdout!.on("data", (chunk: Buffer) => { stdout += chunk.toString("utf8"); });
  child.stderr!.on("data", (chunk: Buffer) => { stderr += chunk.toString("utf8"); });
  const exited = new Promise<Exit>((resolve, reject) => {
    child.once("error", reject);
    child.once("close", (code, signal) => resolve({ code, signal }));
  });
  return { process: child, exited, stdout: () => stdout, stderr: () => stderr };
}

async function ledger(directory: string): Promise<Ledger> {
  return decryptTestStateFile<Ledger>(
    { kind: "privacy-deletion-v2", recordId: "ledger.json" },
    await readFile(path.join(directory, "privacy-deletion-v2", "ledger.json"), "utf8"),
    PRIVACY_DELETION_V2_MAX_BYTES
  );
}

for (const mode of ["success", "failure", "uncooperative"] as const) {
  test(`shutdown ${mode} settles or terminates before runtime lock release`, { timeout: 30_000 }, async (t) => {
    const directory = await mkdtemp(path.join(tmpdir(), "walksafe-shutdown-"));
    const oldDirectory = process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR;
    process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = directory;
    await configureTestStateEncryption(path.join(directory, "keyring.json"));
    const runtimeLock = resolveGatewayServiceRuntimeLockPath();
    const children: Child[] = [];
    try {
      const child = launch(directory, await availablePort(), mode);
      children.push(child);
      await waitFor(() => (child.stdout().match(/^FETCH /gm) ?? []).length === 3,
        `three outbox requests did not start: ${child.stderr()}`);
      assert.equal(child.process.kill("SIGTERM"), true);
      await waitFor(() => (child.stdout().match(/^ABORT /gm) ?? []).length === 3,
        "shutdown must cancel all three running requests");
      assert.throws(() => acquireExclusiveFileLockLease(runtimeLock), ExclusiveFileLockBusyError);
      const before = await ledger(directory);
      assert.ok(Object.values(before.requests).every((record) => record.outbox.length === 1));
      const stoppedAt = performance.now();
      if (mode !== "uncooperative") child.process.send("release");
      const result = await Promise.race([
        child.exited,
        new Promise<never>((_resolve, reject) => {
          const timer = setTimeout(() => reject(new Error("shutdown exceeded the outbox deadline")), 22_000);
          child.exited.finally(() => clearTimeout(timer));
        })
      ]);
      assert.equal(result.signal, null, child.stderr());
      assert.equal(result.code, mode === "uncooperative" ? 1 : 0, child.stderr());
      assert.equal((child.stdout().match(/^FETCH /gm) ?? []).length, 3,
        "the fourth queued request must not start after shutdown");
      const after = await ledger(directory);
      const records = Object.values(after.requests);
      if (mode === "success") {
        assert.equal(records.filter((record) => record.outbox.length === 0).length, 3);
      } else if (mode === "failure") {
        assert.equal(records.filter((record) => record.outbox[0]?.attempts === 1).length, 3);
        assert.ok(records.filter((record) => record.outbox[0]?.attempts === 1)
          .every((record) => Date.parse(record.outbox[0]!.next_attempt_at) > Date.now()));
      } else {
        assert.deepEqual(after, before, "unsettled work must remain durably queued on forced exit");
        assert.match(child.stderr(), /shutdown deadline exceeded/);
        t.diagnostic(JSON.stringify({ deadlineExitMs: Math.round(performance.now() - stoppedAt) }));
      }
      acquireExclusiveFileLockLease(runtimeLock).release();
      if (mode === "uncooperative") {
        const recovery = launch(directory, await availablePort(), "recovery");
        children.push(recovery);
        await waitFor(async () => Object.values((await ledger(directory)).requests)
          .every((record) => record.outbox.length === 0), "restart did not acknowledge queued work");
        assert.equal((recovery.stdout().match(/^FETCH /gm) ?? []).length, 4);
        assert.equal(recovery.process.kill("SIGTERM"), true);
        assert.deepEqual(await recovery.exited, { code: 0, signal: null }, recovery.stderr());
        acquireExclusiveFileLockLease(runtimeLock).release();
      }
    } finally {
      for (const child of children) {
        if (child.process.exitCode === null && child.process.signalCode === null) child.process.kill("SIGKILL");
        await child.exited;
      }
      if (oldDirectory === undefined) delete process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR;
      else process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = oldDirectory;
      await rm(directory, { recursive: true, force: true });
    }
  });
}
