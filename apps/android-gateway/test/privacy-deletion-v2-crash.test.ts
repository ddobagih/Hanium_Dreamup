import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";

import { configureTestStateEncryption } from "./state-encryption-fixture.js";

let stateDirectory = "";

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-deletion-crash-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_FIELD_TEST_TOKEN:
      "deletion-crash-internal-token-12345678901234567890",
    WALKSAFE_GATEWAY_SESSION_SECRET:
      "deletion-crash-session-secret-123456789012345678901234567890",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

function runWorker(phase: string) {
  return spawnSync(
    process.execPath,
    [path.join(process.cwd(), "dist/test/privacy-deletion-v2-crash-worker.js"), phase],
    {
      encoding: "utf8",
      env: { ...process.env },
      timeout: 15_000,
      killSignal: "SIGKILL"
    }
  );
}

test("a crashed process leaves a durable request outbox that a restart drains and ACKs", () => {
  const crashed = runWorker("enqueue-crash");
  assert.equal(crashed.status, null, crashed.stderr);
  assert.equal(crashed.signal, "SIGKILL");
  assert.equal(crashed.stdout, "QUEUED\n");

  const restarted = runWorker("drain");
  assert.equal(restarted.status, 0, restarted.stderr);
  assert.deepEqual(JSON.parse(restarted.stdout), {
    drained: { attempted: 1, succeeded: 1 },
    revision: 1
  });

  const verified = runWorker("verify");
  assert.equal(verified.status, 0, verified.stderr);
  assert.deepEqual(JSON.parse(verified.stdout), {
    drained: { attempted: 0, succeeded: 0 },
    backendCalls: 0,
    revision: 1
  });
});
