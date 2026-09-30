import assert from "node:assert/strict";
import { test } from "node:test";

import {
  KeyedAdmissionQueue,
  QueueAdmissionAbortedError,
  QueueAdmissionBusyError
} from "../src/keyed-admission-queue.js";

function deferred(): { promise: Promise<void>; resolve: () => void } {
  let resolve!: () => void;
  const promise = new Promise<void>((done) => { resolve = done; });
  return { promise, resolve };
}

test("32 waiters fit; the next is busy and never starts its action", async () => {
  const queue = new KeyedAdmissionQueue(32, 1_000);
  const hold = deferred();
  const first = queue.run("a", async () => hold.promise);
  let calls = 0;
  const controllers = Array.from({ length: 32 }, () => new AbortController());
  const waiters = controllers.map((controller) => queue.run("a", async () => {
    calls += 1;
  }, controller.signal));
  await assert.rejects(queue.run("a", async () => { calls += 1; }),
    QueueAdmissionBusyError);
  controllers.forEach((controller) => controller.abort());
  await Promise.all(waiters.map((waiter) => assert.rejects(
    waiter, QueueAdmissionAbortedError
  )));
  assert.equal(calls, 0);
  hold.resolve();
  await first;
  await queue.run("a", async () => { calls += 1; });
  assert.equal(calls, 1);
});

test("a waiter times out by its admission deadline and is removed", async () => {
  const queue = new KeyedAdmissionQueue(1, 20);
  const hold = deferred();
  const first = queue.run("a", async () => hold.promise);
  let calls = 0;
  await assert.rejects(queue.run("a", async () => { calls += 1; }),
    QueueAdmissionBusyError);
  assert.equal(calls, 0);
  hold.resolve();
  await first;
  await queue.run("a", async () => { calls += 1; });
  assert.equal(calls, 1);
});

test("pre-aborted and waiting signals never invoke actions", async () => {
  const queue = new KeyedAdmissionQueue(1, 1_000);
  const alreadyAborted = new AbortController();
  alreadyAborted.abort();
  let calls = 0;
  await assert.rejects(queue.run("a", async () => { calls += 1; },
    alreadyAborted.signal), QueueAdmissionAbortedError);
  const hold = deferred();
  const first = queue.run("a", async () => hold.promise);
  const controller = new AbortController();
  const waiting = queue.run("a", async () => { calls += 1; }, controller.signal);
  controller.abort();
  await assert.rejects(waiting, QueueAdmissionAbortedError);
  assert.equal(calls, 0);
  hold.resolve();
  await first;
});

test("same-key admission is FIFO without bypass; another key runs independently", async () => {
  const queue = new KeyedAdmissionQueue(3, 1_000);
  const hold = deferred();
  const order: string[] = [];
  const first = queue.run("a", async () => {
    order.push("first");
    await hold.promise;
  });
  const second = queue.run("a", async () => { order.push("second"); });
  const third = queue.run("a", async () => { order.push("third"); });
  await queue.run("b", async () => { order.push("other"); });
  assert.deepEqual(order, ["first", "other"]);
  hold.resolve();
  await Promise.all([first, second, third]);
  assert.deepEqual(order, ["first", "other", "second", "third"]);
});

test("a rejected started action releases its permit after settlement", async () => {
  const queue = new KeyedAdmissionQueue(1, 1_000);
  const hold = deferred();
  const first = queue.run("a", async () => {
    await hold.promise;
    throw new Error("action failed");
  });
  let called = false;
  const second = queue.run("a", async () => { called = true; });
  assert.equal(called, false);
  hold.resolve();
  await assert.rejects(first, /action failed/);
  await second;
  assert.equal(called, true);
});

test("priority revocation evicts a saturated ordinary queue and bars new or duplicate work", async () => {
  const queue = new KeyedAdmissionQueue(32, 1_000);
  const activeHold = deferred();
  const priorityHold = deferred();
  const priorityStarted = deferred();
  const order: string[] = [];
  const active = queue.run("a", async () => {
    order.push("active");
    await activeHold.promise;
  });
  const ordinary = Array.from({ length: 32 }, (_, index) =>
    queue.run("a", async () => { order.push(`ordinary-${index}`); })
      .then(() => null, (error: unknown) => error)
  );
  const priority = queue.runPriority("a", async () => {
    order.push("priority");
    priorityStarted.resolve();
    await priorityHold.promise;
  });
  assert.ok((await Promise.all(ordinary)).every((error) => error instanceof QueueAdmissionBusyError));
  await assert.rejects(queue.run("a", async () => { order.push("late"); }), QueueAdmissionBusyError);
  await assert.rejects(queue.runPriority("a", async () => { order.push("duplicate"); }),
    QueueAdmissionBusyError);
  await queue.run("b", async () => { order.push("other-actor"); });
  assert.deepEqual(order, ["active", "other-actor"]);

  activeHold.resolve();
  await active;
  await priorityStarted.promise;
  await assert.rejects(queue.run("a", async () => { order.push("during-priority"); }),
    QueueAdmissionBusyError);
  assert.deepEqual(order, ["active", "other-actor", "priority"]);
  priorityHold.resolve();
  await priority;
  await queue.run("a", async () => { order.push("after-priority"); });
  assert.deepEqual(order, ["active", "other-actor", "priority", "after-priority"]);
});

test("priority admission times out without bypassing the active operation", async () => {
  const queue = new KeyedAdmissionQueue(1, 20);
  const hold = deferred();
  let priorityRan = false;
  const active = queue.run("a", async () => hold.promise);
  const ordinary = queue.run("a", async () => { throw new Error("evicted action ran"); })
    .then(() => null, (error: unknown) => error);
  const priority = queue.runPriority("a", async () => { priorityRan = true; });
  assert.ok(await ordinary instanceof QueueAdmissionBusyError);
  await assert.rejects(priority, QueueAdmissionBusyError);
  assert.equal(priorityRan, false);
  const resumed = queue.run("a", async () => "resumed");
  hold.resolve();
  await active;
  assert.equal(await resumed, "resumed");
});
