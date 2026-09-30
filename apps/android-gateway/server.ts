import cluster from "node:cluster";
import { writeSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

import { AccountDeletionOutboxWorker } from "./src/account-deletion-outbox-worker.js";

import {
  assertFieldLongSessionConfiguration,
  assertGatewayStateStorageConfiguration,
  resolveBindAddress,
  resolveGatewayServiceRuntimeLockPath,
  resolvePrivacyRightsRequestUrl,
  resolveVoiceServiceConfig
} from "./src/config.js";
import { initializeGatewayStateEncryption } from "./src/encrypted-json-store.js";
import {
  acquireExclusiveFileLockLease,
  acquireSharedFileLock,
  type FileLockLease
} from "./src/exclusive-file-lock.js";
import { createGatewayReadinessProbe, createGatewayServer } from "./src/node-adapter.js";
import {
  accountDeletionOutboxHasPausedOperations,
  ACCOUNT_DELETION_OUTBOX_OPERATION_TIMEOUT_MS,
  drainAccountDeletionOutboxV2
} from "./src/privacy-deletion-v2.js";
import {
  assertGatewayManagedStateReady,
  probeGatewayManagedStorageReady
} from "./src/state-encryption-maintenance.js";

if (!cluster.isPrimary) {
  throw new Error("walksafe-android-gateway must run as one process without cluster workers");
}

assertGatewayStateStorageConfiguration();
const bind = resolveBindAddress();
resolvePrivacyRightsRequestUrl();
resolveVoiceServiceConfig();
assertFieldLongSessionConfiguration();
const runtimeLockPath = resolveGatewayServiceRuntimeLockPath();
const runtimeLock = acquireSharedFileLock(runtimeLockPath);
let instanceLock: FileLockLease | undefined;
let ledgerInstanceLock: FileLockLease | undefined;

function resolveLedgerPath(): string {
  const configured = process.env.WALKSAFE_FIELD_WALK_LEDGER_PATH?.trim();
  if (configured && !path.isAbsolute(configured)) {
    throw new Error("field walk ledger path must be absolute");
  }
  const ledgerPath = path.resolve(
    configured || path.join(tmpdir(), "walksafe-android-gateway", "field-walk-ledger.json")
  );
  return ledgerPath;
}

function releaseLocks(): void {
  let releaseError: unknown;
  for (const lock of [ledgerInstanceLock, instanceLock, runtimeLock]) {
    try {
      lock?.release();
    } catch (error) {
      releaseError ??= error;
    }
  }
  if (releaseError !== undefined) throw releaseError;
}

function failStartup(error: unknown): never {
  try {
    releaseLocks();
  } catch (releaseError) {
    throw new AggregateError([error, releaseError], "gateway startup and lock release failed");
  }
  throw error;
}

let server: ReturnType<typeof createGatewayServer>;
let deletionWorker: AccountDeletionOutboxWorker;
try {
  // Separate from the shared maintenance lock: only one service may use this state root.
  instanceLock = acquireExclusiveFileLockLease(
    path.join(path.dirname(runtimeLockPath), "gateway-service-instance.lock")
  );
  const ledgerPath = resolveLedgerPath();
  ledgerInstanceLock = acquireExclusiveFileLockLease(`${ledgerPath}.gateway-service-instance.lock`);
  initializeGatewayStateEncryption();
  assertGatewayManagedStateReady();
  deletionWorker = new AccountDeletionOutboxWorker(
    (signal) => drainAccountDeletionOutboxV2(undefined, Date.now(), signal),
    () => { process.stderr.write("walksafe account deletion outbox drain failed\n"); },
    accountDeletionOutboxHasPausedOperations
  );
  server = createGatewayServer({
    readinessProbe: createGatewayReadinessProbe(
      () => probeGatewayManagedStorageReady(ledgerPath),
      () => deletionWorker.isReady()
    )
  });
  server.once("error", failStartup);
  server.listen(bind.port, bind.host, () => {
    process.stdout.write(`walksafe-android-gateway listening on ${bind.host}:${bind.port}\n`);
    deletionWorker.start();
  });
} catch (error) {
  failStartup(error);
}

let shuttingDown = false;
async function shutdown(): Promise<void> {
  if (shuttingDown) return;
  shuttingDown = true;
  const deadline = setTimeout(() => {
    // A pending drain may still write. Termination releases kernel locks only
    // after those writers are gone; its durable outbox remains for restart.
    try {
      writeSync(2, "walksafe-android-gateway shutdown deadline exceeded\n");
    } finally {
      process.exit(1);
    }
  }, ACCOUNT_DELETION_OUTBOX_OPERATION_TIMEOUT_MS);
  const closed = new Promise<void>((resolve, reject) => {
    server.close((error) => error ? reject(error) : resolve());
  });
  const results = await Promise.allSettled([closed, deletionWorker.stop()]);
  clearTimeout(deadline);
  let failed = results.some((result) => result.status === "rejected");
  try {
    releaseLocks();
  } catch {
    failed = true;
  }
  if (failed) {
    process.stderr.write("walksafe-android-gateway shutdown failed\n");
    process.exitCode = 1;
  }
}

process.once("SIGTERM", () => { void shutdown(); });
process.once("SIGINT", () => { void shutdown(); });
