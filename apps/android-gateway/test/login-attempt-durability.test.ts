import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { fstatSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { open } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { mock, test } from "node:test";

import { recordGatewayLoginAttempt, recordGatewayLoginFailure } from "../src/auth.js";

const CLIENT_IP = "192.0.2.44";

test("S19-28 login limit records are fsynced before rename and the directory after rename", async (t) => {
  const stateRoot = mkdtempSync(path.join(tmpdir(), "walksafe-login-durability-"));
  const previousDir = process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR;
  const previousHeader = process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER;
  process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = stateRoot;
  process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER = "x-real-ip";
  t.after(() => {
    if (previousDir === undefined) delete process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR;
    else process.env.WALKSAFE_GATEWAY_RATE_LIMIT_DIR = previousDir;
    if (previousHeader === undefined) delete process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER;
    else process.env.WALKSAFE_GATEWAY_TRUSTED_IP_HEADER = previousHeader;
    rmSync(stateRoot, { recursive: true, force: true });
  });
  const globalAttemptsPath = path.join(stateRoot, "global-attempts.log");
  const clientPath = path.join(
    stateRoot,
    "field-" + createHash("sha256").update("field:" + CLIENT_IP).digest("hex") + ".log"
  );
  const readLines = (target: string): number => {
    try {
      return readFileSync(target, "utf8").split("\n").filter(Boolean).length;
    } catch {
      return 0;
    }
  };
  const probe = await open(path.join(stateRoot, "probe"), "w");
  const fileHandlePrototype = Object.getPrototypeOf(probe) as { sync: () => Promise<void> };
  await probe.close();
  rmSync(path.join(stateRoot, "probe"));
  const events: Array<{ kind: "file" | "directory"; globalLines: number; clientLines: number }> = [];
  const originalSync = fileHandlePrototype.sync;
  const syncSpy = mock.method(fileHandlePrototype, "sync", function (this: { fd: number }) {
    events.push({
      kind: fstatSync(this.fd).isDirectory() ? "directory" : "file",
      globalLines: readLines(globalAttemptsPath),
      clientLines: readLines(clientPath)
    });
    return originalSync.call(this);
  });
  t.after(() => syncSpy.mock.restore());

  const request = new Request("https://gateway.invalid/api/field-session", {
    method: "POST",
    headers: { "x-real-ip": CLIENT_IP }
  });
  await recordGatewayLoginAttempt(request);
  assert.deepEqual(events, [
    // Temporary file content is durable while the target still holds the old record.
    { kind: "file", globalLines: 0, clientLines: 0 },
    // Directory entry is synced only after rename published the new record.
    { kind: "directory", globalLines: 1, clientLines: 0 }
  ]);
  events.length = 0;
  await recordGatewayLoginFailure(request);
  assert.deepEqual(events.map((event) => event.kind), ["file", "directory", "file", "directory"]);
  assert.equal(events[1]!.clientLines, 1);
  assert.equal(readLines(clientPath), 1);
  assert.equal(readLines(path.join(stateRoot, "global-failures.log")), 1);
  assert.equal(readLines(globalAttemptsPath), 1);
});
