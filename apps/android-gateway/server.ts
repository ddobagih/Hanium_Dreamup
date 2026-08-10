import cluster from "node:cluster";

import {
  assertGatewayStateStorageConfiguration,
  resolveBindAddress,
  resolveGatewayServiceRuntimeLockPath,
  resolvePrivacyRightsRequestUrl
} from "./src/config.js";
import { initializeGatewayStateEncryption } from "./src/encrypted-json-store.js";
import { acquireSharedFileLock } from "./src/exclusive-file-lock.js";
import { createGatewayServer } from "./src/node-adapter.js";
import { drainAccountDeletionOutboxV2 } from "./src/privacy-deletion-v2.js";
import { assertGatewayManagedStateReady } from "./src/state-encryption-maintenance.js";

if (!cluster.isPrimary) {
  throw new Error("walksafe-android-gateway must run as one process without cluster workers");
}

assertGatewayStateStorageConfiguration();
const bind = resolveBindAddress();
resolvePrivacyRightsRequestUrl();
const runtimeLock = acquireSharedFileLock(resolveGatewayServiceRuntimeLockPath());
let server: ReturnType<typeof createGatewayServer>;
try {
  initializeGatewayStateEncryption();
  assertGatewayManagedStateReady();
  server = createGatewayServer();
} catch (error) {
  runtimeLock.release();
  throw error;
}

let deletionDrainInFlight = false;
async function drainDeletionOutbox(): Promise<void> {
  if (deletionDrainInFlight) return;
  deletionDrainInFlight = true;
  try {
    await drainAccountDeletionOutboxV2();
  } catch {
    process.stderr.write("walksafe account deletion outbox drain failed\n");
  } finally {
    deletionDrainInFlight = false;
  }
}

server.listen(bind.port, bind.host, () => {
  process.stdout.write(`walksafe-android-gateway listening on ${bind.host}:${bind.port}\n`);
  void drainDeletionOutbox();
});
const deletionDrainTimer = setInterval(() => {
  void drainDeletionOutbox();
}, 5_000);
deletionDrainTimer.unref();

let shuttingDown = false;
function shutdown(): void {
  if (shuttingDown) return;
  shuttingDown = true;
  clearInterval(deletionDrainTimer);
  server.close((error) => {
    runtimeLock.release();
    if (error) {
      process.stderr.write("walksafe-android-gateway shutdown failed\n");
      process.exitCode = 1;
    }
  });
}

process.once("SIGTERM", shutdown);
process.once("SIGINT", shutdown);
