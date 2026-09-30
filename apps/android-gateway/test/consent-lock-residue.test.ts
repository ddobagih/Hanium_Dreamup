// S19-22-F1 (w22 review): a residual legacy consent-lock recovery link is reported before
// the service starts (and before maintenance), not only as per-user 503s at request time.
import assert from "node:assert/strict";
import { link, mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import test, { after, before } from "node:test";
import {
  assertGatewayManagedStateReady,
  runGatewayStateMaintenance
} from "../src/state-encryption-maintenance.js";
import { configureTestStateEncryption } from "./state-encryption-fixture.js";

let keyringDirectory = "";
const saved = {
  root: process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR,
  walk: process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH
};
before(async () => {
  keyringDirectory = await mkdtemp(path.join(tmpdir(), "consent-lock-residue-keyring-"));
  await configureTestStateEncryption(path.join(keyringDirectory, "state-keyring.json"));
});
after(async () => {
  for (const [key, value] of [
    ["WALKSAFE_GATEWAY_RATE_LIMIT_DIR", saved.root],
    ["WALKSAFE_FIELD_WALK_LEDGER_PATH", saved.walk]
  ] as const) {
    if (value === undefined) delete process.env[key];
    else process.env[key] = value;
  }
  await rm(keyringDirectory, { recursive: true, force: true });
});

test("S19-22-F1 a residual .reaper link refuses start with the removal procedure until it is removed", async () => {
  const root = await mkdtemp(path.join(tmpdir(), "consent-lock-residue-root-"));
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = root;
  process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH = path.join(root, "field-walk-ledger.json");
  try {
    const consent = path.join(root, "integrated-consent");
    await mkdir(consent, { mode: 0o700 });
    const lock = path.join(consent, `${"a".repeat(64)}.json.lock`);
    await writeFile(lock, "", { mode: 0o600 });
    // A clean lock file (one link) is normal state and never blocks start.
    assertGatewayManagedStateReady();
    // Crash of the legacy PID-lock recovery after it created its hard link.
    const reaper = `${lock}.reaper`;
    await link(lock, reaper);
    assert.throws(
      () => assertGatewayManagedStateReady(),
      (error: unknown) =>
        error instanceof Error &&
        (error as { code?: string }).code === "integrated_consent_lock_residue" &&
        error.message.includes(reaper) &&
        error.message.includes(lock) &&
        /service stopped/.test(error.message)
    );
    await assert.rejects(
      runGatewayStateMaintenance("inspect", {
        ...process.env,
        WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED: "true"
      }),
      (error: unknown) => (error as { code?: string }).code === "integrated_consent_lock_residue"
    );
    // The documented operator step (service stopped): delete the .reaper link.
    await rm(reaper);
    assertGatewayManagedStateReady();
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
