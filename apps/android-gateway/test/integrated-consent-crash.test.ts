// S19-30: the Gateway process is killed right after the Backend committed a consent event
// and before the Gateway wrote it locally. A restarted Gateway reconciles from the Backend
// receipt when the client resends the same request id and content.
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { createHash } from "node:crypto";
import { lstat, mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { after, before, test } from "node:test";
import { fileURLToPath } from "node:url";

import { configureTestStateEncryption } from "./state-encryption-fixture.js";

let stateDirectory = "";

before(async () => {
  stateDirectory = await mkdtemp(path.join(tmpdir(), "walksafe-consent-crash-"));
  Object.assign(process.env, {
    NODE_ENV: "test",
    WALKSAFE_ENVIRONMENT: "test",
    BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
    WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
    WALKSAFE_FIELD_WALK_LEDGER_PATH: path.join(stateDirectory, "field-walk-ledger.json"),
    WALKSAFE_FIELD_TEST_TOKEN: "consent-crash-internal-token-12345678901234567890",
    WALKSAFE_FIELD_ACCOUNTS_JSON: JSON.stringify([
      { actor_id: "consent-crash-actor", token: "consent-crash-token-12345678901234567890" }
    ]),
    WALKSAFE_GATEWAY_SESSION_SECRET: "consent-crash-session-secret-123456789012345678901234567890",
    WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip"
  });
  await configureTestStateEncryption(path.join(stateDirectory, "state-keyring.json"));
});

after(async () => {
  await rm(stateDirectory, { recursive: true, force: true });
});

function runWorker(phase: string) {
  return spawnSync(
    process.execPath,
    [fileURLToPath(new URL("./integrated-consent-crash-worker.js", import.meta.url)), phase],
    { encoding: "utf8", env: { ...process.env }, timeout: 20_000, killSignal: "SIGKILL" }
  );
}

test("S19-30 a Gateway killed after the Backend consent commit is reconciled by the same request after restart", async () => {
  const crashed = runWorker("commit-then-kill");
  assert.equal(crashed.signal, "SIGKILL", crashed.stderr);
  assert.equal(crashed.stdout, "BACKEND_COMMITTED\n");
  // The Backend holds the event; the Gateway never wrote its local consent state.
  const backend = JSON.parse(
    await readFile(path.join(stateDirectory, "fake-backend-consent.json"), "utf8")
  ) as Record<string, { receipt: string }>;
  const committedReceipt = backend.s19_30_crash_request_0001?.receipt;
  assert.match(committedReceipt ?? "", /^[0-9a-f]{64}$/);
  const digest = createHash("sha256")
    .update("integrated-consent\0s19_30_crash_installation").digest("hex");
  await assert.rejects(
    lstat(path.join(stateDirectory, "integrated-consent", `${digest}.json`)),
    { code: "ENOENT" }
  );

  const restarted = runWorker("restart-retry");
  assert.equal(restarted.status, 0, restarted.stderr);
  assert.deepEqual(JSON.parse(restarted.stdout), {
    status: 200,
    revision: 1,
    receipt: committedReceipt,
    backend_receipt: committedReceipt,
    backend_events: 1,
    authorized: true
  });
  await lstat(path.join(stateDirectory, "integrated-consent", `${digest}.json`));
});
