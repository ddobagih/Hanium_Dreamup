import { fileURLToPath } from "node:url";
import path from "node:path";

import { resolveGatewayServiceRuntimeLockPath } from "./config.js";
import { initializeGatewayStateEncryption } from "./encrypted-json-store.js";
import { acquireExclusiveFileLockLease } from "./exclusive-file-lock.js";
import {
  listPausedAccountDeletionOutboxV2,
  resumePausedAccountDeletionOutboxV2
} from "./privacy-deletion-v2.js";

const SHA256 = /^[0-9a-f]{64}$/;
const PAUSE_ID = /^[0-9a-f]{32}$/;

export function runPrivacyDeletionOutboxOperator(args: readonly string[]):
  | { command: "list"; paused: ReturnType<typeof listPausedAccountDeletionOutboxV2> }
  | { command: "resume"; result: "resumed" | "stale" | "not_found" } {
  const list = args.length === 1 && args[0] === "list";
  const resume = args.length === 5 && args[0] === "resume" &&
    SHA256.test(args[1] ?? "") && SHA256.test(args[2] ?? "") &&
    PAUSE_ID.test(args[3] ?? "") && args[4] === "--corrected";
  if (!list && !resume) {
    throw new Error(
      "usage: privacy-deletion-outbox-operator list | resume <request_ref> <operation_ref> <pause_id> --corrected"
    );
  }
  // The live gateway holds a shared lease for its entire lifetime. Operator
  // inspection and mutation require the gateway to be stopped first.
  const runtimeLock = acquireExclusiveFileLockLease(resolveGatewayServiceRuntimeLockPath());
  try {
    if (list) return { command: "list", paused: listPausedAccountDeletionOutboxV2() };
    return {
      command: "resume",
      result: resumePausedAccountDeletionOutboxV2(args[1]!, args[2]!, args[3]!)
    };
  } finally {
    runtimeLock.release();
  }
}

async function main(): Promise<void> {
  initializeGatewayStateEncryption();
  const result = runPrivacyDeletionOutboxOperator(process.argv.slice(2));
  process.stdout.write(`${JSON.stringify(result)}\n`);
  if (result.command === "resume" && result.result !== "resumed") process.exitCode = 2;
}

if (
  process.argv[1] &&
  path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))
) {
  void main().catch(() => {
    process.stderr.write(`${JSON.stringify({
      event_name: "walksafe.gateway.account_deletion_operator.failed",
      code: "operator_command_failed"
    })}\n`);
    process.exitCode = 1;
  });
}
