import assert from "node:assert/strict";
import {
  spawn,
  spawnSync,
  type ChildProcessWithoutNullStreams
} from "node:child_process";
import { once } from "node:events";
import {
  lstat,
  mkdir,
  mkdtemp,
  readFile,
  rename,
  rm,
  writeFile
} from "node:fs/promises";
import { createServer, type AddressInfo, type Server } from "node:net";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";
import { fileURLToPath } from "node:url";

import {
  ExclusiveFileLockBusyError,
  ExclusiveFileLockIntegrityError,
  withExclusiveFileLock,
  withExclusiveFileLockAsync
} from "../src/exclusive-file-lock.js";
import { writeTestStateKeyring } from "./state-encryption-fixture.js";

if (false) {
  // @ts-expect-error The synchronous API must reject Promise-returning actions.
  withExclusiveFileLock("/compile-time-only", async () => undefined);
}

type ChildResult = {
  code: number | null;
  signal: NodeJS.Signals | null;
  stdout: string;
  stderr: string;
};

function collectChild(child: ChildProcessWithoutNullStreams): Promise<ChildResult> {
  let stdout = "";
  let stderr = "";
  child.stdout.setEncoding("utf8");
  child.stderr.setEncoding("utf8");
  child.stdout.on("data", (chunk: string) => {
    stdout += chunk;
  });
  child.stderr.on("data", (chunk: string) => {
    stderr += chunk;
  });
  return once(child, "exit").then(([code, signal]) => ({
    code: code as number | null,
    signal: signal as NodeJS.Signals | null,
    stdout,
    stderr
  }));
}

function waitForOutput(
  child: ChildProcessWithoutNullStreams,
  marker: string
): Promise<void> {
  return new Promise((resolve, reject) => {
    let output = "";
    const onData = (chunk: string): void => {
      output += chunk;
      if (output.includes(marker)) {
        cleanup();
        resolve();
      }
    };
    const onError = (error: Error): void => {
      cleanup();
      reject(error);
    };
    const onExit = (code: number | null, signal: NodeJS.Signals | null): void => {
      cleanup();
      reject(
        new Error(
          `lock holder exited before ${marker}: ${String(code)}/${String(signal)}`
        )
      );
    };
    const cleanup = (): void => {
      child.stdout.off("data", onData);
      child.off("error", onError);
      child.off("exit", onExit);
    };
    child.stdout.on("data", onData);
    child.once("error", onError);
    child.once("exit", onExit);
  });
}

function spawnLockWorker(
  script: string,
  moduleUrl: string,
  lockPath: string
): ChildProcessWithoutNullStreams {
  return spawn(
    process.execPath,
    ["--input-type=module", "--eval", script, moduleUrl, lockPath],
    { env: process.env, stdio: ["pipe", "pipe", "pipe"] }
  );
}

test("async acquisition yields before its action and retains exclusive ownership", async () => {
  const directory = await mkdtemp(
    path.join(os.tmpdir(), "exclusive-file-lock-async-yield-")
  );
  const lockPath = path.join(directory, "actor.lock");
  let returnedToCaller = false;
  let entered = false;
  try {
    const pending = withExclusiveFileLockAsync(lockPath, () => {
      assert.equal(returnedToCaller, true);
      entered = true;
      assert.throws(
        () => withExclusiveFileLock(lockPath, () => undefined),
        (error: unknown) => error instanceof ExclusiveFileLockBusyError
      );
      return "acquired";
    });
    assert.equal(entered, false);
    returnedToCaller = true;
    await Promise.resolve();
    assert.equal(entered, false);
    assert.equal(await pending, "acquired");
    assert.equal(entered, true);
    assert.equal(withExclusiveFileLock(lockPath, () => "released"), "released");
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("an exact live owner makes nested sync and async acquisitions busy", async () => {
  const directory = await mkdtemp(
    path.join(os.tmpdir(), "exclusive-file-lock-live-")
  );
  const lockPath = path.join(directory, "actor.lock");
  try {
    await withExclusiveFileLockAsync(lockPath, async () => {
      assert.throws(
        () => withExclusiveFileLock(lockPath, () => undefined),
        (error: unknown) =>
          error instanceof ExclusiveFileLockBusyError &&
          error.code === "exclusive_file_lock_busy"
      );
      await assert.rejects(
        withExclusiveFileLockAsync(lockPath, async () => undefined),
        (error: unknown) =>
          error instanceof ExclusiveFileLockBusyError &&
          error.code === "exclusive_file_lock_busy"
      );
    });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("sync API rejects thenables without releasing their critical section early", async () => {
  const directory = await mkdtemp(
    path.join(os.tmpdir(), "exclusive-file-lock-sync-thenable-")
  );
  const lockPath = path.join(directory, "actor.lock");
  let settle!: () => void;
  const pending = new Promise<void>((resolve) => {
    settle = resolve;
  });
  const unsafeSyncCall = withExclusiveFileLock as unknown as (
    target: string,
    action: () => unknown
  ) => unknown;
  try {
    assert.throws(
      () => unsafeSyncCall(lockPath, () => pending),
      (error: unknown) =>
        error instanceof ExclusiveFileLockIntegrityError &&
        /use withExclusiveFileLockAsync/.test(error.message)
    );
    assert.throws(
      () => withExclusiveFileLock(lockPath, () => "must-not-enter"),
      (error: unknown) => error instanceof ExclusiveFileLockBusyError
    );

    settle();
    await pending;
    await new Promise<void>((resolve) => setImmediate(resolve));
    assert.equal(
      withExclusiveFileLock(lockPath, () => ({ value: "released" as const }))
        .value,
      "released"
    );
  } finally {
    settle();
    await rm(directory, { recursive: true, force: true });
  }
});

test("release preserves one stable owned 0600 lock inode", async () => {
  const directory = await mkdtemp(
    path.join(os.tmpdir(), "exclusive-file-lock-persistent-")
  );
  const lockPath = path.join(directory, "actor.lock");
  try {
    withExclusiveFileLock(lockPath, () => undefined);
    const first = await lstat(lockPath, { bigint: true });
    assert.equal(first.isFile(), true);
    assert.equal(first.isSymbolicLink(), false);
    assert.equal(first.mode & 0o777n, 0o600n);
    assert.equal(first.nlink, 1n);

    await withExclusiveFileLockAsync(lockPath, async () => undefined);
    const second = await lstat(lockPath, { bigint: true });
    assert.equal(second.dev, first.dev);
    assert.equal(second.ino, first.ino);
    assert.equal(second.mode & 0o777n, 0o600n);
    assert.equal(second.nlink, 1n);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("three child processes cannot overlap one held critical section", async () => {
  const directory = await mkdtemp(
    path.join(os.tmpdir(), "exclusive-file-lock-process-")
  );
  const lockPath = path.join(directory, "actor.lock");
  const moduleUrl = new URL(
    "../src/exclusive-file-lock.js",
    import.meta.url
  ).href;
  const holderScript = `
    const [moduleUrl, lockPath] = process.argv.slice(1);
    const { writeSync } = await import("node:fs");
    const lock = await import(moduleUrl);
    await lock.withExclusiveFileLockAsync(lockPath, async () => {
      writeSync(1, "LOCKED\\n");
      await new Promise((resolve) => process.stdin.once("data", resolve));
    });
  `;
  const contenderScript = `
    const [moduleUrl, lockPath] = process.argv.slice(1);
    const { writeSync } = await import("node:fs");
    const lock = await import(moduleUrl);
    try {
      await lock.withExclusiveFileLockAsync(lockPath, async () => {
        writeSync(1, "ACQUIRED\\n");
      });
    } catch (error) {
      if (error instanceof lock.ExclusiveFileLockBusyError) {
        writeSync(1, "BUSY\\n");
      } else {
        throw error;
      }
    }
  `;
  const holder = spawnLockWorker(holderScript, moduleUrl, lockPath);
  const holderResult = collectChild(holder);
  try {
    await waitForOutput(holder, "LOCKED\n");
    const contenders = [
      spawnLockWorker(contenderScript, moduleUrl, lockPath),
      spawnLockWorker(contenderScript, moduleUrl, lockPath)
    ];
    const results = await Promise.all(contenders.map(collectChild));
    for (const result of results) {
      assert.equal(result.code, 0, result.stderr);
      assert.equal(result.signal, null);
      assert.equal(result.stdout, "BUSY\n");
    }
    holder.stdin.end("release\n");
    const released = await holderResult;
    assert.equal(released.code, 0, released.stderr);
    assert.equal(released.signal, null);
    assert.equal(released.stdout, "LOCKED\n");
    withExclusiveFileLock(lockPath, () => undefined);
  } finally {
    if (holder.exitCode === null && holder.signalCode === null) {
      holder.kill("SIGKILL");
    }
    await holderResult.catch(() => undefined);
    await rm(directory, { recursive: true, force: true });
  }
});

test("a process crash automatically releases the kernel lock", async () => {
  const directory = await mkdtemp(
    path.join(os.tmpdir(), "exclusive-file-lock-crash-")
  );
  const lockPath = path.join(directory, "actor.lock");
  const moduleUrl = new URL(
    "../src/exclusive-file-lock.js",
    import.meta.url
  ).href;
  const crashScript = `
    const [moduleUrl, lockPath] = process.argv.slice(1);
    const { writeSync } = await import("node:fs");
    const lock = await import(moduleUrl);
    await lock.withExclusiveFileLockAsync(lockPath, async () => {
      writeSync(1, "LOCKED\\n");
      process.kill(process.pid, "SIGKILL");
      await new Promise(() => undefined);
    });
  `;
  try {
    const crashed = spawnSync(
      process.execPath,
      ["--input-type=module", "--eval", crashScript, moduleUrl, lockPath],
      { encoding: "utf8", env: process.env }
    );
    assert.equal(crashed.status, null, crashed.stderr);
    assert.equal(crashed.signal, "SIGKILL");
    assert.equal(crashed.stdout, "LOCKED\n");

    let entered = false;
    await withExclusiveFileLockAsync(lockPath, async () => {
      entered = true;
    });
    assert.equal(entered, true);
    assert.equal((await lstat(lockPath)).isFile(), true);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test("release detects and never deletes a swapped replacement path", async () => {
  const directory = await mkdtemp(
    path.join(os.tmpdir(), "exclusive-file-lock-swap-")
  );
  const lockPath = path.join(directory, "actor.lock");
  const displacedPath = path.join(directory, "original.lock");
  const replacement = "replacement lock inode\n";
  try {
    await assert.rejects(
      withExclusiveFileLockAsync(lockPath, async () => {
        await rename(lockPath, displacedPath);
        await writeFile(lockPath, replacement, {
          encoding: "utf8",
          flag: "wx",
          mode: 0o600
        });
      }),
      (error: unknown) => error instanceof ExclusiveFileLockIntegrityError
    );
    assert.equal(await readFile(lockPath, "utf8"), replacement);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

type GatewayChild = {
  child: ChildProcessWithoutNullStreams;
  ready: Promise<void>;
  closed: Promise<ChildResult>;
};

async function availableGatewayPort(port = 0): Promise<number> {
  const probe = createServer();
  await new Promise<void>((resolve, reject) => {
    probe.once("error", reject);
    probe.listen(port, "127.0.0.1", resolve);
  });
  const address = probe.address() as AddressInfo;
  await new Promise<void>((resolve, reject) => {
    probe.close((error) => error ? reject(error) : resolve());
  });
  return address.port;
}

function spawnGatewayChild(
  port: number,
  stateDirectory: string,
  keyringPath: string,
  ledgerPath = path.join(stateDirectory, "field-walk-ledger.json")
): GatewayChild {
  const child = spawn(
    process.execPath,
    [fileURLToPath(new URL("../server.js", import.meta.url))],
    {
      env: {
        ...process.env,
        NODE_ENV: "test",
        WALKSAFE_ENVIRONMENT: "test",
        BACKEND_API_BASE_URL: "http://127.0.0.1:8000",
        WALKSAFE_FIELD_TEST_TOKEN: "lock-test-internal-token-1234567890",
        WALKSAFE_GATEWAY_SESSION_SECRET: "lock-test-session-secret-12345678901234567890",
        WALKSAFE_GATEWAY_TRUSTED_IP_HEADER: "cf-connecting-ip",
        WALKSAFE_FIELD_ACCOUNTS_JSON: "[]",
        WALKSAFE_GATEWAY_RATE_LIMIT_DIR: stateDirectory,
        WALKSAFE_GATEWAY_STATE_KEYRING_FILE: keyringPath,
        WALKSAFE_FIELD_WALK_LEDGER_PATH: ledgerPath,
        WALKSAFE_PRIVACY_RIGHTS_REQUEST_URL: "https://privacy.invalid/requests",
        WALKSAFE_ANDROID_GATEWAY_HOST: "127.0.0.1",
        WALKSAFE_ANDROID_GATEWAY_PORT: String(port),
        WALKSAFE_VOICE_ENABLED: "false",
        WALKSAFE_FIELD_LONG_LIVED_SESSIONS_ENABLED: "false",
        WALKSAFE_GATEWAY_STATE_MAINTENANCE_SERVICE_STOPPED: "false"
      }
    }
  );
  let stdout = "";
  let stderr = "";
  let readySettled = false;
  let resolveReady!: () => void;
  let rejectReady!: (error: Error) => void;
  const ready = new Promise<void>((resolve, reject) => {
    resolveReady = resolve;
    rejectReady = reject;
  });
  const marker = `walksafe-android-gateway listening on 127.0.0.1:${port}\n`;
  const closed = new Promise<ChildResult>((resolve, reject) => {
    const timeout = setTimeout(() => {
      child.kill("SIGKILL");
      const error = new Error(`gateway child timed out on port ${port}: ${stderr}`);
      if (!readySettled) rejectReady(error);
      reject(error);
    }, 30_000);
    child.stdout.on("data", (chunk: Buffer) => {
      stdout += chunk.toString("utf8");
      if (!readySettled && stdout.includes(marker)) {
        readySettled = true;
        resolveReady();
      }
    });
    child.stderr.on("data", (chunk: Buffer) => {
      stderr += chunk.toString("utf8");
    });
    child.once("error", (error) => {
      clearTimeout(timeout);
      if (!readySettled) rejectReady(error);
      reject(error);
    });
    child.once("close", (code, signal) => {
      clearTimeout(timeout);
      if (!readySettled) {
        rejectReady(new Error(`gateway exited before listen: ${code}/${signal}: ${stderr}`));
      }
      resolve({ code, signal, stdout, stderr });
    });
  });
  void ready.catch(() => undefined);
  void closed.catch(() => undefined);
  return { child, ready, closed };
}

async function stopGatewayChild(gateway: GatewayChild): Promise<void> {
  assert.equal(gateway.child.kill("SIGTERM"), true, "gateway SIGTERM delivery failed");
  const result = await gateway.closed;
  assert.equal(result.signal, null, result.stderr);
  assert.equal(result.code, 0, result.stderr);
}

test("independent Gateway processes exclude shared state roots or ledgers and release after shutdown or failed bind", async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), "gateway-instance-lock-"));
  const stateDirectory = path.join(directory, "state");
  const otherStateDirectory = path.join(directory, "other-state");
  const sharedLedgerPath = path.join(stateDirectory, "field-walk-ledger.json");
  const keyringPath = path.join(directory, "keyring.json");
  const children: GatewayChild[] = [];
  let occupiedPort: Server | undefined;
  try {
    await mkdir(stateDirectory, { mode: 0o700 });
    await mkdir(otherStateDirectory, { mode: 0o700 });
    await writeTestStateKeyring(keyringPath);
    const firstPort = await availableGatewayPort();
    let secondPort = await availableGatewayPort();
    while (secondPort === firstPort) secondPort = await availableGatewayPort();

    const first = spawnGatewayChild(firstPort, stateDirectory, keyringPath);
    children.push(first);
    await first.ready;

    const contender = spawnGatewayChild(secondPort, stateDirectory, keyringPath);
    children.push(contender);
    const rejected = await contender.closed;
    assert.equal(rejected.signal, null, rejected.stderr);
    assert.notEqual(rejected.code, 0, rejected.stderr);
    assert.doesNotMatch(rejected.stdout, /listening on/);
    assert.match(rejected.stderr, /exclusive file lock is busy/);
    assert.equal(await availableGatewayPort(secondPort), secondPort);

    const ledgerContender = spawnGatewayChild(
      secondPort, otherStateDirectory, keyringPath, sharedLedgerPath
    );
    children.push(ledgerContender);
    const ledgerRejected = await ledgerContender.closed;
    assert.equal(ledgerRejected.signal, null, ledgerRejected.stderr);
    assert.notEqual(ledgerRejected.code, 0, ledgerRejected.stderr);
    assert.doesNotMatch(ledgerRejected.stdout, /listening on/);
    assert.match(ledgerRejected.stderr, /exclusive file lock is busy/);
    assert.equal(await availableGatewayPort(secondPort), secondPort);

    await stopGatewayChild(first);

    const ledgerRestart = spawnGatewayChild(
      secondPort, otherStateDirectory, keyringPath, sharedLedgerPath
    );
    children.push(ledgerRestart);
    await ledgerRestart.ready;
    await stopGatewayChild(ledgerRestart);

    occupiedPort = createServer();
    await new Promise<void>((resolve, reject) => {
      occupiedPort!.once("error", reject);
      occupiedPort!.listen(firstPort, "127.0.0.1", resolve);
    });
    const failedBind = spawnGatewayChild(firstPort, stateDirectory, keyringPath);
    children.push(failedBind);
    const bindResult = await failedBind.closed;
    assert.equal(bindResult.signal, null, bindResult.stderr);
    assert.notEqual(bindResult.code, 0, bindResult.stderr);
    assert.doesNotMatch(bindResult.stdout, /listening on/);
    assert.match(bindResult.stderr, /EADDRINUSE/);

    const restarted = spawnGatewayChild(secondPort, stateDirectory, keyringPath);
    children.push(restarted);
    await restarted.ready;
    await stopGatewayChild(restarted);
  } finally {
    for (const gateway of children) {
      if (gateway.child.exitCode === null && gateway.child.signalCode === null) {
        gateway.child.kill("SIGKILL");
      }
    }
    await Promise.allSettled(children.map((gateway) => gateway.closed));
    if (occupiedPort?.listening) {
      await new Promise<void>((resolve) => occupiedPort!.close(() => resolve()));
    }
    await rm(directory, { recursive: true, force: true });
  }
});
