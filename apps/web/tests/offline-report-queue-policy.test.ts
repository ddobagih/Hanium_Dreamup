import {
  enqueueOfflineReport,
  markOfflineReportRetry,
  OFFLINE_REPORT_QUEUE_SCHEMA_VERSION,
  pruneOfflineReportQueue,
  removeOfflineReport
} from "../lib/offline-report-queue";

function assert(condition: boolean, message: string): asserts condition {
  if (!condition) {
    throw new Error(message);
  }
}

function testQueueRequiresOptInAndPayloadLimit() {
  let optInBlocked = false;
  try {
    enqueueOfflineReport([], { report: "x" }, { nowMs: 1000, optIn: false });
  } catch {
    optInBlocked = true;
  }
  assert(optInBlocked, "offline report queue should require explicit opt-in");

  let sizeBlocked = false;
  try {
    enqueueOfflineReport([], { report: "x".repeat(20) }, { nowMs: 1000, optIn: true, maxPayloadBytes: 8 });
  } catch {
    sizeBlocked = true;
  }
  assert(sizeBlocked, "offline report queue should enforce payload size limit");
}

function testTtlCapacityAndManualRetry() {
  const first = enqueueOfflineReport([], { class_name: "damaged_tactile_block" }, {
    nowMs: 1000,
    ttlMs: 500,
    maxItems: 2,
    optIn: true,
    idFactory: () => "first"
  }).queue;
  const second = enqueueOfflineReport(first, { class_name: "damaged_tactile_block" }, {
    nowMs: 1100,
    ttlMs: 500,
    maxItems: 2,
    optIn: true,
    idFactory: () => "second"
  }).queue;
  const thirdResult = enqueueOfflineReport(second, { class_name: "damaged_tactile_block" }, {
    nowMs: 1200,
    ttlMs: 500,
    maxItems: 2,
    optIn: true,
    idFactory: () => "third"
  });

  assert(thirdResult.queue.length === 2, "queue should keep maxItems newest active items");
  assert(!thirdResult.queue.some((item) => item.id === "first"), "oldest active item should be dropped over limit");
  assert(thirdResult.prune.dropped_over_limit.length === 1, "dropped over limit should be reported");
  assert(thirdResult.item.schema_version === OFFLINE_REPORT_QUEUE_SCHEMA_VERSION, "queue item should include schema version");

  const pruned = pruneOfflineReportQueue(thirdResult.queue, { nowMs: 2000, maxItems: 2 });
  assert(pruned.kept.length === 0, "expired items should not be kept");
  assert(pruned.expired.length === 2, "expired items should be reported");

  const retried = markOfflineReportRetry(thirdResult.queue, "third", { nowMs: 1300 });
  assert(retried.find((item) => item.id === "third")?.retry_count === 1, "manual retry should increment retry_count");
  const removed = removeOfflineReport(retried, "third");
  assert(!removed.some((item) => item.id === "third"), "manual retry success should remove item by id");
}

function main() {
  testQueueRequiresOptInAndPayloadLimit();
  testTtlCapacityAndManualRetry();
  console.log("offline report queue policy checks passed");
}

main();
