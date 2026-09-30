import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import {
  chmodSync,
  lstatSync,
  mkdtempSync,
  realpathSync,
  rmSync,
  symlinkSync,
  writeFileSync
} from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";

type BoundaryCase = "fifo" | "regular" | "symlink";

// Parent-only safety deadline; not a gateway timeout or a successful rejection.
const CHILD_SAFETY_TIMEOUT_MS = 5_000;
const STORE_MODULE_URL = new URL("../src/encrypted-json-store.js", import.meta.url).href;
const CHILD_SOURCE = `
import assert from "node:assert/strict";

const {
  GatewayStateEncryptionError,
  initializeGatewayStateEncryption,
  encryptGatewayStateJson,
  decryptGatewayStateJson
} = await import(process.argv[1]);
const boundaryCase = process.argv[2];

if (boundaryCase === "regular") {
  initializeGatewayStateEncryption();
  const context = { kind: "short-session", recordId: "open-boundary-record" };
  const value = { fixture: "synthetic-only", sequence: 1 };
  const encrypted = encryptGatewayStateJson(context, value, 1_024);
  assert.deepEqual(decryptGatewayStateJson(context, encrypted, 1_024), {
    value,
    keyId: "open-boundary-test",
    keyStatus: "active"
  });
} else {
  assert.ok(boundaryCase === "fifo" || boundaryCase === "symlink");
  assert.throws(
    () => initializeGatewayStateEncryption(),
    (error) => {
      assert.ok(error instanceof GatewayStateEncryptionError);
      assert.equal(error.code, "configuration");
      if (boundaryCase === "fifo") {
        assert.equal(
          error.message,
          "gateway state keyring must be one canonical regular file with one link"
        );
      } else {
        assert.equal(error.cause?.code, "ELOOP");
      }
      return true;
    }
  );
}
process.stdout.write("keyring-open-boundary:" + boundaryCase + ":ok\\n");
`;

async function runFreshChild(
  directory: string,
  keyringPath: string,
  boundaryCase: BoundaryCase
): Promise<void> {
  const child = spawn(
    process.execPath,
    ["--input-type=module", "--eval", CHILD_SOURCE, STORE_MODULE_URL, boundaryCase],
    {
      cwd: directory,
      env: {
        NODE_ENV: "test",
        WALKSAFE_GATEWAY_STATE_KEYRING_FILE: keyringPath
      },
      stdio: ["ignore", "pipe", "pipe"]
    }
  );
  let stdout = "";
  let stderr = "";
  let spawnError: Error | undefined;
  let expired = false;
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", (chunk: string) => { stdout += chunk; });
  child.stderr.on("data", (chunk: string) => { stderr += chunk; });
  child.once("error", (error: Error) => { spawnError = error; });
  const closed = new Promise<{ code: number | null; signal: NodeJS.Signals | null }>((resolve) => {
    child.once("close", (code, signal) => { resolve({ code, signal }); });
  });
  const watchdog = setTimeout(() => {
    expired = true;
    child.kill("SIGKILL");
  }, CHILD_SAFETY_TIMEOUT_MS);
  try {
    const result = await closed;
    assert.equal(expired, false, boundaryCase + " child exceeded its safety deadline: " + stderr);
    assert.ifError(spawnError);
    assert.equal(result.signal, null, stderr);
    assert.equal(result.code, 0, stderr);
    assert.equal(stdout, "keyring-open-boundary:" + boundaryCase + ":ok\n");
  } finally {
    clearTimeout(watchdog);
    if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
    await closed;
  }
}

async function checkBoundary(boundaryCase: BoundaryCase): Promise<void> {
  const directory = mkdtempSync(path.join(realpathSync(tmpdir()), "walksafe-keyring-open-boundary-"));
  const keyringPath = path.join(directory, "keyring.json");
  try {
    if (boundaryCase === "fifo") {
      const created = spawnSync("mkfifo", ["-m", "600", "--", keyringPath], {
        cwd: directory,
        env: { PATH: "/usr/bin:/bin" },
        encoding: "utf8",
        timeout: CHILD_SAFETY_TIMEOUT_MS,
        killSignal: "SIGKILL"
      });
      assert.ifError(created.error);
      assert.equal(created.signal, null, created.stderr);
      assert.equal(created.status, 0, created.stderr);
      assert.ok(lstatSync(keyringPath).isFIFO());
      // No writer is ever opened: a blocking O_RDONLY open must fail the watchdog.
    } else {
      const regularPath = boundaryCase === "symlink"
        ? path.join(directory, "regular-keyring.json")
        : keyringPath;
      writeFileSync(regularPath, JSON.stringify({
        schema_version: "walksafe.gateway-state-keyring.v1",
        keys: [{
          key_id: "open-boundary-test",
          status: "active",
          key_base64url: Buffer.alloc(32, 0x5a).toString("base64url")
        }]
      }), { encoding: "utf8", mode: 0o600, flag: "wx" });
      chmodSync(regularPath, 0o600);
      if (boundaryCase === "symlink") {
        symlinkSync(regularPath, keyringPath);
        assert.ok(lstatSync(keyringPath).isSymbolicLink());
      }
    }
    await runFreshChild(directory, keyringPath, boundaryCase);
  } finally {
    // runFreshChild waits for close, including after watchdog kill, before removal.
    rmSync(directory, { recursive: true, force: true });
  }
}

test("FIFO keyring without a writer is rejected as configuration in a fresh child", async () => {
  await checkBoundary("fifo");
});

test("regular keyring initializes and round-trips state in a fresh child", async () => {
  await checkBoundary("regular");
});

test("symlink keyring is rejected without following its target in a fresh child", async () => {
  await checkBoundary("symlink");
});
