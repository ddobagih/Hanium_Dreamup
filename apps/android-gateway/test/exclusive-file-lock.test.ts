import assert from "node:assert/strict";
import {
  spawn,
  spawnSync,
  type ChildProcessWithoutNullStreams
} from "node:child_process";
import { once } from "node:events";
import {
  lstat,
  mkdtemp,
  readFile,
  rename,
  rm,
  writeFile
} from "node:fs/promises";
import os from "node:os";
import path from "node:path";
import { test } from "node:test";

import {
  ExclusiveFileLockBusyError,
  ExclusiveFileLockIntegrityError,
  withExclusiveFileLock,
  withExclusiveFileLockAsync
} from "../src/exclusive-file-lock.js";

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
