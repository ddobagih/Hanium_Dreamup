package kr.co.hanium.dreamup.walksafe.feedback

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.fieldlog.TestFieldAead
import kr.co.hanium.dreamup.walksafe.security.AeadLimits
import kr.co.hanium.dreamup.walksafe.security.AeadOpenResult
import kr.co.hanium.dreamup.walksafe.security.AeadSealResult
import kr.co.hanium.dreamup.walksafe.security.LocalAead
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ConsentBoundSpeechOutputStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun encryptedFilesPreserveEveryExistingKindOutcomeAndOriginalTimeOnly() {
        val f = fixture()
        val binding = f.authority.binding()
        assertTrue(f.store.publish(binding))
        val events = SpeechOutputKind.values().flatMapIndexed { kindIndex, kind ->
            SpeechOutputOutcome.values().mapIndexed { outcomeIndex, outcome ->
                SpeechOutputEvent(kind, outcome, 41L + kindIndex * 1_000L + outcomeIndex,
                    if (outcome == SpeechOutputOutcome.ENGINE_ERROR) -3 else null)
            }
        }
        // Keep this vocabulary test independent of deliberate saturation tested below.
        events.forEachIndexed { index, event ->
            f.store.enqueue(binding, event)
            awaitCondition { f.storage.successes.get() == index + 1 }
        }
        val records = f.storage.records()
        assertEquals(events.size, records.size)
        records.zip(events).forEach { (record, event) ->
            assertEquals(setOf("metric", "state", "elapsed_realtime_ms", "result"),
                record.keys().asSequence().toSet())
            assertEquals(event.kind.name, record.getString("metric"))
            assertEquals(event.outcome.name, record.getString("state"))
            assertEquals(event.atElapsedRealtimeMs, record.getLong("elapsed_realtime_ms"))
            assertEquals(event.errorCode ?: "NONE", record.get("result"))
            assertTrue(record.getString("metric").length <= 160)
            assertTrue(record.getString("state").length <= 160)
        }
        val ciphertext = f.storage.file.readText()
        assertFalse(ciphertext.contains("elapsed_realtime_ms"))
        assertFalse(ciphertext.contains("ENGINE_ERROR"))
        assertFalse(ciphertext.contains("\"result\":"))
        assertNotEquals(records.joinToString("\n"), ciphertext)
        assertEquals(0L, f.store.droppedEventCount())
        assertEquals(setOf("walksafe-speech-output-store"), f.storage.ioThreads.toSet())
        // Optional test-only evidence, under the parent's assigned work folder.
        System.getenv("WALKSAFE_SPEECH_STORE_EVIDENCE")?.let { destination ->
            File(destination).apply { mkdirs() }.also { directory ->
                f.storage.file.copyTo(File(directory, "encrypted-events.jsonl"), overwrite = false)
                File(directory, "expected-fixed-events.jsonl")
                    .writeText(records.joinToString("\n", postfix = "\n") { it.toString() })
            }
        }
        f.store.close()
    }

    @Test
    fun noPublishedConsentAndPendingDenyWithdrawalDeletionCreateNoFile() {
        listOf("unpublished", "no-consent", "pending-deny", "withdrawal", "deletion")
            .forEach { boundary ->
                val f = fixture()
                val binding = f.authority.binding()
                when (boundary) {
                    "no-consent" -> f.authority.allowed.set(false)
                    "pending-deny" -> f.authority.pendingDeny.set(true)
                    "withdrawal" -> f.authority.withdrawn.set(true)
                    "deletion" -> f.authority.deletion.set(true)
                }
                if (boundary != "unpublished") assertFalse(f.store.publish(binding))
                f.store.enqueue(binding, event())
                assertEquals(1L, f.store.droppedEventCount())
                assertFalse(f.storage.file.exists())
                assertEquals(0, f.storage.sealCalls.get())
                f.store.close()
            }
    }

    @Test
    fun eachIdentityReplacementAfterEnqueueDropsOldAndAcceptsNewBinding() {
        listOf("receipt", "permission", "epoch", "session", "generation", "actor", "lifetime",
            "source", "window").forEach { boundary ->
            val gate = Gate()
            val f = fixture(sealGate = gate)
            val old = f.authority.binding()
            assertTrue(f.store.publish(old))
            f.store.enqueue(old, event(at = 10L))
            gate.awaitEntered()
            f.store.enqueue(old, event(at = 11L))
            f.authority.replace(boundary)
            val next = f.authority.binding()
            assertTrue(f.store.publish(next))
            f.store.enqueue(old, event(at = 12L))
            f.store.enqueue(next, event(at = 13L))
            gate.release()
            awaitCondition { f.storage.successes.get() == 1 && f.store.droppedEventCount() == 3L }
            assertEquals(listOf(13L), f.storage.records().map { it.getLong("elapsed_realtime_ms") })
            assertFalse(f.store.publish(old))
            f.store.close()
        }
    }

    @Test
    fun currentnessChangeDuringSealIsCheckedBeforeAnyDiskWrite() {
        listOf("receipt", "epoch", "actor", "source", "window", "session", "pending-deny",
            "withdrawal", "deletion").forEach { boundary ->
            val gate = Gate()
            val f = fixture(sealGate = gate)
            val binding = f.authority.binding()
            assertTrue(f.store.publish(binding))
            f.store.enqueue(binding, event())
            gate.awaitEntered()
            when (boundary) {
                "pending-deny" -> f.authority.pendingDeny.set(true)
                "withdrawal" -> f.authority.withdrawn.set(true)
                "deletion" -> f.authority.deletion.set(true)
                else -> f.authority.replace(boundary)
            }
            gate.release()
            awaitCondition { f.store.droppedEventCount() == 1L }
            assertFalse(f.storage.file.exists())
            assertEquals(0, f.storage.appendCalls.get())
            f.store.close()
        }
    }

    @Test
    fun recoveryToEngineReadyDoesNotDiscardAdmittedEngineError() {
        val gate = Gate()
        val f = fixture(sealGate = gate)
        val binding = f.authority.binding()
        assertTrue(f.store.publish(binding))
        f.authority.engineReady.set(false)
        f.store.enqueue(binding, event(SpeechOutputOutcome.ENGINE_ERROR, 123L, -4))
        gate.awaitEntered()
        f.authority.engineReady.set(true)
        gate.release()
        awaitCondition { f.storage.successes.get() == 1 }
        assertEquals("ENGINE_ERROR", f.storage.records().single().getString("state"))
        assertEquals(-4, f.storage.records().single().getInt("result"))
        assertEquals(123L, f.storage.records().single().getLong("elapsed_realtime_ms"))
        f.store.close()
    }

    @Test
    fun boundedQueue64RejectsOverflowWithoutObserverIoOrWaiting() {
        val gate = Gate()
        val f = fixture(sealGate = gate)
        val binding = f.authority.binding()
        assertTrue(f.store.publish(binding))
        f.store.enqueue(binding, event(at = 0L))
        gate.awaitEntered()
        repeat(64) { f.store.enqueue(binding, event(at = it + 1L)) }
        f.store.enqueue(binding, event(at = 65L))
        assertEquals(1L, f.store.droppedEventCount())
        assertFalse(f.storage.file.exists())
        assertEquals(1, f.storage.sealCalls.get())
        gate.release()
        awaitCondition { f.storage.successes.get() == 65 }
        assertEquals((0L..64L).toList(), f.storage.records().map { it.getLong("elapsed_realtime_ms") })
        assertEquals(setOf("walksafe-speech-output-store"), f.storage.ioThreads.toSet())
        f.store.close()
    }

    @Test
    fun shutdownDiscardsWaitingEventsAndRejectsNewEventsWithoutCallerIo() {
        val gate = Gate()
        val f = fixture(sealGate = gate)
        val binding = f.authority.binding()
        assertTrue(f.store.publish(binding))
        f.store.enqueue(binding, event())
        gate.awaitEntered()
        f.store.enqueue(binding, event(at = 2L))
        f.store.close()
        f.store.enqueue(binding, event(at = 3L))
        assertFalse(f.store.publish(f.authority.binding()))
        assertEquals(2L, f.store.droppedEventCount())
        gate.release()
        awaitCondition { f.store.droppedEventCount() == 3L }
        assertFalse(f.storage.file.exists())
        assertFalse(f.store.invalidateAndPurge().get(10, TimeUnit.SECONDS))
        assertEquals(0, f.storage.purgeCalls.get())
    }

    @Test
    fun sealBlockAppendFailureAndExceptionDropOnceWithoutRetry() {
        listOf("seal-block", "append-false", "append-throw", "seal-throw").forEach { failure ->
            val f = fixture(failure = failure)
            val binding = f.authority.binding()
            assertTrue(f.store.publish(binding))
            f.store.enqueue(binding, event())
            awaitCondition { f.store.droppedEventCount() == 1L }
            assertFalse(f.storage.file.exists())
            assertEquals(1, f.storage.sealCalls.get())
            assertEquals(if (failure.startsWith("append")) 1 else 0, f.storage.appendCalls.get())
            f.store.close()
        }
    }

    @Test
    fun throwingAndReentrantCurrentnessFailClosedOutsideAdmissionLock() {
        val storage = TestStorage(temporary.newFolder())
        val authority = Authority()
        val binding = authority.binding()
        val reenter = AtomicBoolean()
        val throwCheck = AtomicBoolean()
        val invalidationFinishedDuringCallback = AtomicBoolean()
        lateinit var store: ConsentBoundSpeechOutputStore
        store = ConsentBoundSpeechOutputStore(storage) { candidate ->
            if (throwCheck.get()) error("injected check failure")
            if (reenter.compareAndSet(true, false)) {
                // Another thread can invalidate while this observer is inside currentness.
                val finished = CountDownLatch(1)
                Thread { store.invalidate(candidate); finished.countDown() }.start()
                invalidationFinishedDuringCallback.set(finished.await(10, TimeUnit.SECONDS))
                check(invalidationFinishedDuringCallback.get())
            }
            authority.isCurrent(candidate)
        }
        assertTrue(store.publish(binding))
        reenter.set(true)
        store.enqueue(binding, event())
        // The fail-closed callback intentionally catches exceptions, so assert its observation
        // here too: a callback-lock deadlock/timeout must not look like a successful drop test.
        assertTrue(invalidationFinishedDuringCallback.get())
        assertEquals(1L, store.droppedEventCount())
        assertFalse(storage.file.exists())
        throwCheck.set(true)
        assertFalse(store.publish(authority.binding()))
        store.enqueue(authority.binding(), event())
        assertEquals(2L, store.droppedEventCount())
        store.close()
    }

    @Test
    fun lateOuterPublishCannotReplaceReentrantCurrentBindingOrBreakItsFileOutput() {
        val storage = TestStorage(temporary.newFolder())
        val authority = Authority()
        val old = authority.binding()
        lateinit var next: SpeechOutputStorageBinding
        val reenter = AtomicBoolean(true)
        val nestedAccepted = AtomicBoolean()
        lateinit var store: ConsentBoundSpeechOutputStore
        store = ConsentBoundSpeechOutputStore(storage) { candidate ->
            // Exactly the reviewed race: A was current when this answer was computed, but
            // B becomes current and is published before the outer A callback returns it.
            val wasCurrent = authority.isCurrent(candidate)
            if (candidate === old && reenter.compareAndSet(true, false)) {
                authority.replace("actor")
                next = authority.binding()
                nestedAccepted.set(store.publish(next))
            }
            wasCurrent
        }
        try {
            val outerAccepted = store.publish(old)
            store.enqueue(old, event(at = 201L))
            store.enqueue(next, event(at = 202L))
            awaitCondition { storage.successes.get() + store.droppedEventCount() == 2L }
            val clocks = if (storage.file.exists()) storage.records()
                .map { it.getLong("elapsed_realtime_ms") } else emptyList()
            System.getenv("WALKSAFE_SPEECH_STORE_EVIDENCE")?.let { destination ->
                File(destination).apply { mkdirs() }.also { directory ->
                    File(directory, "publish-reentry-observation.json").writeText(
                        JSONObject().put("outer_publish_accepted", outerAccepted)
                            .put("nested_current_publish_accepted", nestedAccepted.get())
                            .put("stored_events", storage.successes.get())
                            .put("dropped_events", store.droppedEventCount())
                            .put("event_file_exists", storage.file.exists())
                            .put("stored_original_clocks", org.json.JSONArray(clocks))
                            .toString(),
                    )
                }
            }
            assertTrue(nestedAccepted.get())
            assertEquals("current B must retain actual file output", 1, storage.successes.get())
            assertTrue(storage.file.exists())
            assertEquals(listOf(202L), clocks)
            assertFalse(outerAccepted)
            assertEquals(1L, store.droppedEventCount())
        } finally {
            store.close()
        }
    }

    @Test
    fun publishCommitFencesAbaWhenNestedPublishAndInvalidateReturnActiveToNull() {
        val storage = TestStorage(temporary.newFolder())
        val authority = Authority()
        val old = authority.binding()
        val reenter = AtomicBoolean(true)
        val nestedAccepted = AtomicBoolean()
        val transitionCompleted = AtomicBoolean()
        lateinit var store: ConsentBoundSpeechOutputStore
        store = ConsentBoundSpeechOutputStore(storage) { candidate ->
            val wasCurrent = authority.isCurrent(candidate)
            if (candidate === old && reenter.compareAndSet(true, false)) {
                authority.replace("actor")
                val middle = authority.binding()
                nestedAccepted.set(store.publish(middle))
                store.invalidate(middle)
                // Both the authority identities and active=null return to their old values.
                authority.restore(old)
                transitionCompleted.set(true)
            }
            wasCurrent
        }
        try {
            assertFalse(store.publish(old))
            assertTrue(nestedAccepted.get())
            assertTrue(transitionCompleted.get())
            store.enqueue(old, event(at = 301L))
            assertEquals(1L, store.droppedEventCount())
            assertFalse(storage.file.exists())
            val current = authority.binding()
            assertTrue(store.publish(current))
            store.enqueue(current, event(at = 302L))
            awaitCondition { storage.successes.get() == 1 }
            assertEquals(listOf(302L), storage.records().map { it.getLong("elapsed_realtime_ms") })
        } finally {
            store.close()
        }
    }

    @Test
    fun invalidatePurgeAndCloseDuringCurrentnessRejectLatePublishAndPurgeRevival() {
        listOf("invalidate", "purge", "close").forEach { boundary ->
            val storage = TestStorage(temporary.newFolder())
            val authority = Authority()
            val previous = authority.binding()
            val reenter = AtomicBoolean()
            var purge: java.util.concurrent.CompletableFuture<Boolean>? = null
            lateinit var store: ConsentBoundSpeechOutputStore
            store = ConsentBoundSpeechOutputStore(storage) { candidate ->
                val wasCurrent = authority.isCurrent(candidate)
                if (reenter.compareAndSet(true, false)) {
                    when (boundary) {
                        "invalidate" -> store.invalidate(previous)
                        "purge" -> purge = store.invalidateAndPurge()
                        "close" -> store.close()
                    }
                }
                wasCurrent
            }
            try {
                assertTrue(store.publish(previous))
                val pending = authority.binding()
                reenter.set(true)
                assertFalse(store.publish(pending))
                store.enqueue(pending, event(at = 401L))
                assertEquals(1L, store.droppedEventCount())
                assertFalse(storage.file.exists())
                if (boundary == "invalidate") {
                    val current = authority.binding()
                    assertTrue(store.publish(current))
                    store.enqueue(current, event(at = 402L))
                    awaitCondition { storage.successes.get() == 1 }
                    assertEquals(402L, storage.records().single().getLong("elapsed_realtime_ms"))
                } else {
                    purge?.let { assertTrue(it.get(10, TimeUnit.SECONDS)) }
                    // Verified purge completion cannot reopen this lifetime/store.
                    assertFalse(store.publish(authority.binding()))
                    store.enqueue(pending, event(at = 403L))
                    assertEquals(2L, store.droppedEventCount())
                    assertFalse(storage.file.exists())
                }
            } finally {
                store.close()
            }
        }
    }

    @Test
    fun providerReentrantObserverDoesNotHoldAdmissionLockAcrossIo() {
        val f = fixture()
        val binding = f.authority.binding()
        assertTrue(f.store.publish(binding))
        val reentered = AtomicBoolean()
        f.storage.onAppend = {
            if (reentered.compareAndSet(false, true)) {
                val finished = CountDownLatch(1)
                Thread { f.store.enqueue(binding, event(at = 2L)); finished.countDown() }.start()
                check(finished.await(10, TimeUnit.SECONDS))
            }
        }
        f.store.enqueue(binding, event(at = 1L))
        awaitCondition { f.storage.successes.get() == 2 }
        assertEquals(listOf(1L, 2L), f.storage.records().map { it.getLong("elapsed_realtime_ms") })
        assertEquals(0L, f.store.droppedEventCount())
        f.store.close()
    }

    @Test
    fun inFlightAppendThenSerialPurgeRemovesEverythingWithoutRecreationEvenWhenQueueFull() {
        val gate = Gate()
        val f = fixture(appendGate = gate)
        val binding = f.authority.binding()
        assertTrue(f.store.publish(binding))
        f.store.enqueue(binding, event())
        gate.awaitEntered()
        repeat(64) { f.store.enqueue(binding, event(at = it + 2L)) }
        val purge = f.store.invalidateAndPurge()
        assertFalse(purge.isDone)
        assertEquals(64L, f.store.droppedEventCount())
        assertFalse(f.store.publish(f.authority.binding()))
        f.store.enqueue(binding, event(at = 99L))
        f.store.close() // Closing cannot discard the required queued purge.
        gate.release()
        assertTrue(purge.get(10, TimeUnit.SECONDS))
        assertEquals(1, f.storage.successes.get())
        assertEquals(1, f.storage.purgeCalls.get())
        assertFalse(f.storage.file.exists())
        assertTrue(f.storage.root.listFiles().orEmpty().isEmpty())
        assertEquals(65L, f.store.droppedEventCount())
        assertTrue(purge === f.store.invalidateAndPurge())
    }

    @Test
    fun purgeFailureIsObservableKeepsPublicationBlockedAndDoesNotRetry() {
        val f = fixture(failure = "purge-false")
        val binding = f.authority.binding()
        assertTrue(f.store.publish(binding))
        f.store.enqueue(binding, event())
        awaitCondition { f.storage.successes.get() == 1 }
        val purge = f.store.invalidateAndPurge()
        assertFalse(purge.get(10, TimeUnit.SECONDS))
        assertTrue(f.storage.file.exists())
        assertFalse(f.store.publish(f.authority.binding()))
        f.store.enqueue(binding, event(at = 2L))
        assertEquals(1L, f.store.droppedEventCount())
        assertTrue(purge === f.store.invalidateAndPurge())
        assertEquals(1, f.storage.purgeCalls.get())
        f.store.close()
    }

    @Test
    fun invalidOriginalTimestampIsDroppedWithoutRetagging() {
        val f = fixture()
        val binding = f.authority.binding()
        assertTrue(f.store.publish(binding))
        f.store.enqueue(binding, event(at = -1L))
        assertEquals(1L, f.store.droppedEventCount())
        assertEquals(0, f.storage.sealCalls.get())
        assertFalse(f.storage.file.exists())
        f.store.close()
    }

    @Test
    fun closeReturnsWhileOriginalAdmittedSealIsStillInFlight() {
        val gate = Gate()
        val f = fixture(sealGate = gate)
        val binding = f.authority.binding()
        assertTrue(f.store.publish(binding))
        f.store.enqueue(binding, event(at = 501L))
        gate.awaitEntered()
        try {
            f.store.close()
            assertEquals(1, f.storage.sealCalls.get())
            assertEquals(0, f.storage.appendCalls.get())
            assertEquals(0, f.storage.purgeCalls.get())
            assertFalse(f.storage.file.exists())
            System.getenv("WALKSAFE_SPEECH_STORE_EVIDENCE")?.let { destination ->
                File(destination).apply { mkdirs() }.also { directory ->
                    File(directory, "close-observation.json").writeText(JSONObject()
                        .put("close_returned", true).put("entered_seal_gate_unreleased", true)
                        .put("seal_calls", f.storage.sealCalls.get())
                        .put("append_calls", f.storage.appendCalls.get())
                        .put("purge_calls", f.storage.purgeCalls.get())
                        .put("file_exists", f.storage.file.exists()).toString() + "\n")
                }
            }
        } finally { gate.release() }
        awaitCondition { f.store.droppedEventCount() == 1L }
        assertFalse(f.storage.file.exists())
    }

    @Test
    fun saturatedSlowSealCloseAndDrainIsIdempotentAndFinishesOnlyAfterOldSeal() {
        val gate = Gate(); val f = fixture(sealGate = gate)
        val binding = f.authority.binding()
        assertTrue(f.store.publish(binding)); f.store.enqueue(binding, event(at = 601L))
        gate.awaitEntered()
        try {
            repeat(64) { f.store.enqueue(binding, event(at = it + 602L)) }
            val drain = f.store.closeAndDrain()
            assertFalse(drain.isDone)
            assertEquals(64L, f.store.droppedEventCount())
            assertTrue(drain === f.store.closeAndDrain())
            f.store.close()
            assertTrue(drain === f.store.closeAndDrain())
            assertFalse(f.store.publish(f.authority.binding()))
            assertFalse(f.store.invalidateAndPurge().get(10, TimeUnit.SECONDS))
            f.store.enqueue(binding, event(at = 699L))
            gate.release()
            assertTrue(drain.get(10, TimeUnit.SECONDS))
            assertEquals(66L, f.store.droppedEventCount())
            assertEquals(1, f.storage.sealCalls.get())
            assertEquals(0, f.storage.appendCalls.get()); assertEquals(0, f.storage.purgeCalls.get())
            assertFalse(f.storage.file.exists())
            System.getenv("WALKSAFE_SPEECH_STORE_EVIDENCE")?.let { destination ->
                File(destination, "drain-observation.json").writeText(JSONObject()
                    .put("drain_pending_before_seal_release", true).put("drain_after_old_seal", true)
                    .put("waiting_events_discarded", 64).put("total_drops", f.store.droppedEventCount())
                    .put("append_calls", f.storage.appendCalls.get()).put("purge_calls", f.storage.purgeCalls.get())
                    .put("file_exists", f.storage.file.exists()).toString() + "\n")
            }
        } finally { gate.release(); f.store.close() }
    }

    @Test
    fun closeBeforeDrainQueryStillReturnsOneSerialAcknowledgement() {
        val gate = Gate(); val f = fixture(appendGate = gate)
        val binding = f.authority.binding()
        assertTrue(f.store.publish(binding)); f.store.enqueue(binding, event(at = 701L))
        gate.awaitEntered()
        try {
            f.store.close()
            val drain = f.store.closeAndDrain()
            assertFalse(drain.isDone)
            val references = ConcurrentLinkedQueue<java.util.concurrent.CompletableFuture<Boolean>>()
            val threads = (1..4).map { Thread { references.add(f.store.closeAndDrain()); f.store.close() } }
            threads.forEach(Thread::start); threads.forEach { it.join(10_000L); assertFalse(it.isAlive) }
            assertEquals(4, references.size); assertTrue(references.all { it === drain })
            gate.release()
            assertTrue(drain.get(10, TimeUnit.SECONDS))
            // This admitted append had already entered provider I/O: drain does not erase it.
            assertEquals(listOf(701L), f.storage.records().map { it.getLong("elapsed_realtime_ms") })
            assertEquals(0, f.storage.purgeCalls.get())
            assertFalse(f.store.invalidateAndPurge().get(10, TimeUnit.SECONDS))
        } finally { gate.release(); f.store.close() }
    }

    @Test
    fun drainWaitsForPreviouslyQueuedSlowPurgeAndReportsQuiescenceSeparatelyFromPurgeResult() {
        listOf("", "purge-false").forEach { failure ->
            val append = Gate(); val purgeGate = Gate()
            val f = fixture(appendGate = append, failure = failure, purgeGate = purgeGate)
            val binding = f.authority.binding()
            assertTrue(f.store.publish(binding)); f.store.enqueue(binding, event(at = 801L))
            append.awaitEntered()
            try {
                repeat(64) { f.store.enqueue(binding, event(at = it + 802L)) }
                val purge = f.store.invalidateAndPurge()
                val drain = f.store.closeAndDrain()
                assertFalse(drain.isDone); assertFalse(purge.isDone)
                append.release(); purgeGate.awaitEntered()
                assertFalse(drain.isDone); assertFalse(purge.isDone)
                purgeGate.release()
                assertEquals(failure.isEmpty(), purge.get(10, TimeUnit.SECONDS))
                assertTrue(drain.get(10, TimeUnit.SECONDS))
                assertTrue(purge === f.store.invalidateAndPurge())
                assertEquals(1, f.storage.purgeCalls.get())
                assertEquals(failure.isNotEmpty(), f.storage.file.exists())
                assertEquals(64L, f.store.droppedEventCount())
            } finally { append.release(); purgeGate.release(); f.store.close() }
        }
    }

    @Test
    fun drainRejectionAfterExternalShutdownFailsClosedWithoutAbandoningQueuedPurge() {
        val gate = Gate(); val f = fixture(appendGate = gate)
        val binding = f.authority.binding()
        assertTrue(f.store.publish(binding)); f.store.enqueue(binding, event(at = 901L))
        gate.awaitEntered()
        try {
            val purge = f.store.invalidateAndPurge()
            // Deterministic direct executor shutdown fault; no production retry/new executor.
            val field = ConsentBoundSpeechOutputStore::class.java.getDeclaredField("executor")
            field.isAccessible = true
            (field.get(f.store) as java.util.concurrent.ThreadPoolExecutor).shutdown()
            val drain = f.store.closeAndDrain()
            assertFalse(drain.get(10, TimeUnit.SECONDS))
            assertTrue(drain === f.store.closeAndDrain())
            assertFalse(purge.isDone)
            gate.release()
            assertTrue(purge.get(10, TimeUnit.SECONDS))
            assertEquals(1, f.storage.purgeCalls.get()); assertFalse(f.storage.file.exists())
            assertFalse(f.store.publish(f.authority.binding()))
        } finally { gate.release(); f.store.close() }
    }

    @Test
    fun observerReentryCanCloseAndDrainWithoutWaitingOrLockingProviderIo() {
        val f = fixture()
        val binding = f.authority.binding()
        val drain = AtomicReference<java.util.concurrent.CompletableFuture<Boolean>>()
        val callbackReturned = CountDownLatch(1)
        f.storage.onAppend = {
            val finished = CountDownLatch(1)
            val callback = Thread {
                drain.set(f.store.closeAndDrain())
                callbackReturned.countDown(); finished.countDown()
            }
            callback.start()
            check(finished.await(10, TimeUnit.SECONDS))
            assertFalse(drain.get().isDone)
        }
        assertTrue(f.store.publish(binding)); f.store.enqueue(binding, event(at = 1001L))
        assertTrue(callbackReturned.await(10, TimeUnit.SECONDS))
        assertTrue(drain.get().get(10, TimeUnit.SECONDS))
        assertEquals(1, f.storage.successes.get()); assertTrue(f.storage.file.exists())
        assertEquals(setOf("walksafe-speech-output-store"), f.storage.ioThreads.toSet())
        assertEquals(0, f.storage.purgeCalls.get())
        f.store.close()
    }

    @Test
    fun emptyStoreCloseAndDrainAcknowledgesShutdownWithoutStorageIo() {
        val f = fixture()
        val drain = f.store.closeAndDrain()
        assertTrue(drain.get(10, TimeUnit.SECONDS))
        assertTrue(drain === f.store.closeAndDrain())
        assertEquals(0, f.storage.sealCalls.get()); assertEquals(0, f.storage.appendCalls.get())
        assertEquals(0, f.storage.purgeCalls.get()); assertFalse(f.storage.file.exists())
    }

    @Test
    fun shutdownThrowWhileDrainWorkerStartsNeverPublishesSuccessfulAck() {
        val f = fixture()
        val entered = CountDownLatch(1); val finished = CountDownLatch(1)
        val shutdownFailed = AtomicBoolean()
        val executor = object : java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, java.util.concurrent.ArrayBlockingQueue<Runnable>(64),
            { task -> Thread(task, "walksafe-drain-shutdown-fault").apply { isDaemon = true } },
            java.util.concurrent.ThreadPoolExecutor.AbortPolicy(),
        ) {
            override fun beforeExecute(thread: Thread, task: Runnable) { entered.countDown() }
            override fun afterExecute(task: Runnable, failure: Throwable?) { finished.countDown() }
            override fun shutdown() {
                // Fault fixture only: the JUnit caller observes a worker already starting drain.
                check(entered.await(10, TimeUnit.SECONDS))
                shutdownFailed.set(true)
                throw SecurityException("injected shutdown rejection")
            }
            fun cleanup() { super.shutdown() }
        }
        val field = ConsentBoundSpeechOutputStore::class.java.getDeclaredField("executor")
        field.isAccessible = true
        val original = field.get(f.store) as java.util.concurrent.ThreadPoolExecutor
        field.set(f.store, executor)
        original.shutdown()
        try {
            val drain = f.store.closeAndDrain()
            assertTrue(shutdownFailed.get())
            assertFalse(drain.get(10, TimeUnit.SECONDS))
            assertTrue(finished.await(10, TimeUnit.SECONDS))
            assertFalse(drain.get(10, TimeUnit.SECONDS))
            assertTrue(drain === f.store.closeAndDrain())
            assertFalse(f.store.publish(f.authority.binding()))
            assertEquals(0, f.storage.sealCalls.get()); assertEquals(0, f.storage.appendCalls.get())
            assertEquals(0, f.storage.purgeCalls.get()); assertFalse(f.storage.file.exists())
            System.getenv("WALKSAFE_SPEECH_STORE_EVIDENCE")?.let { destination ->
                File(destination, "shutdown-failure-observation.json").writeText(JSONObject()
                    .put("drain_worker_started", true).put("shutdown_threw", shutdownFailed.get())
                    .put("drain_ack", drain.get(10, TimeUnit.SECONDS)).put("same_future", true)
                    .put("storage_io_calls", 0).toString() + "\n")
            }
        } finally { executor.cleanup() }
    }

    private fun fixture(sealGate: Gate? = null, appendGate: Gate? = null, failure: String = "",
        purgeGate: Gate? = null): Fixture {
        val authority = Authority()
        val storage = TestStorage(temporary.newFolder(), sealGate, appendGate, failure, purgeGate)
        return Fixture(authority, storage, ConsentBoundSpeechOutputStore(storage, authority::isCurrent))
    }

    private data class Fixture(
        val authority: Authority,
        val storage: TestStorage,
        val store: ConsentBoundSpeechOutputStore,
    )

    /** Fake immutable snapshots: real Main authority integration is deliberately not claimed. */
    private class Authority {
        private data class Identities(
            val receipt: Any = Any(), val permission: Any = Any(), val epoch: Any = Any(),
            val session: Any = Any(), val generation: Long = 1L, val actor: Any = Any(),
            val lifetime: Any = Any(), val source: Any = Any(), val window: Any = Any(),
        )
        private val current = AtomicReference(Identities())
        val allowed = AtomicBoolean(true)
        val pendingDeny = AtomicBoolean()
        val withdrawn = AtomicBoolean()
        val deletion = AtomicBoolean()
        val engineReady = AtomicBoolean(true)

        fun binding(): SpeechOutputStorageBinding = current.get().let {
            SpeechOutputStorageBinding(it.receipt, it.permission, it.epoch, it.session, it.generation,
                it.actor, it.lifetime, it.source, it.window)
        }

        fun isCurrent(binding: SpeechOutputStorageBinding): Boolean = current.get().let {
            allowed.get() && !pendingDeny.get() && !withdrawn.get() && !deletion.get() &&
                binding.rawConfirmation === it.receipt && binding.permissionAuthority === it.permission &&
                binding.walkEpoch === it.epoch && binding.gatewaySession === it.session &&
                binding.gatewayGeneration == it.generation && binding.actor === it.actor &&
                binding.feedbackLifetime === it.lifetime && binding.source === it.source &&
                binding.window === it.window
        }

        fun replace(boundary: String) {
            current.updateAndGet {
                when (boundary) {
                    "receipt" -> it.copy(receipt = Any())
                    "permission" -> it.copy(permission = Any())
                    "epoch" -> it.copy(epoch = Any())
                    "session" -> it.copy(session = Any())
                    "generation" -> it.copy(generation = it.generation + 1L)
                    "actor" -> it.copy(actor = Any())
                    "lifetime" -> it.copy(lifetime = Any())
                    "source" -> it.copy(source = Any())
                    "window" -> it.copy(window = Any())
                    else -> error("unknown test boundary")
                }
            }
        }

        fun restore(binding: SpeechOutputStorageBinding) {
            current.set(Identities(binding.rawConfirmation, binding.permissionAuthority,
                binding.walkEpoch, binding.gatewaySession, binding.gatewayGeneration,
                binding.actor, binding.feedbackLifetime, binding.source, binding.window))
        }
    }

    private class Gate {
        private val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        private val once = AtomicBoolean()
        fun blockOnce() {
            if (!once.compareAndSet(false, true)) return
            entered.countDown()
            check(released.await(10, TimeUnit.SECONDS))
        }
        fun awaitEntered() { assertTrue(entered.await(10, TimeUnit.SECONDS)) }
        fun release() { released.countDown() }
    }

    private class TestStorage(
        val root: File,
        private val sealGate: Gate? = null,
        private val appendGate: Gate? = null,
        private val failure: String = "",
        private val purgeGate: Gate? = null,
    ) : SpeechOutputEncryptedStorage {
        private val crypto = TestFieldAead()
        val file = File(root, "events.jsonl")
        val sealCalls = AtomicInteger()
        val appendCalls = AtomicInteger()
        val successes = AtomicInteger()
        val purgeCalls = AtomicInteger()
        val ioThreads = ConcurrentLinkedQueue<String>()
        var onAppend: (() -> Unit)? = null
        // Existing test field-record limits and crypto; no new production policy/alias.
        override val limits = AeadLimits(512 * 1_024, 512 * 1_024 + 16, 704 * 1_024)
        override val aead = object : LocalAead by crypto.aead {
            override fun seal(plaintext: ByteArray, domainAad: ByteArray, limits: AeadLimits): AeadSealResult {
                ioThreads.add(Thread.currentThread().name)
                sealCalls.incrementAndGet()
                sealGate?.blockOnce()
                if (failure == "seal-throw") error("injected seal failure")
                if (failure == "seal-block") {
                    crypto.deleteKey()
                }
                return crypto.aead.seal(plaintext, domainAad, limits)
            }
        }

        // Test-only AAD; the production AAD/domain must be supplied by its approved provider.
        override fun associatedData(): ByteArray = "walksafe-test-speech-output".toByteArray()

        override fun appendEncrypted(envelope: String): Boolean {
            ioThreads.add(Thread.currentThread().name)
            appendCalls.incrementAndGet()
            appendGate?.blockOnce()
            onAppend?.invoke()
            if (failure == "append-throw") error("injected append failure")
            if (failure == "append-false") return false
            file.appendText(envelope + "\n")
            successes.incrementAndGet()
            return true
        }

        override fun purgeAll(): Boolean {
            ioThreads.add(Thread.currentThread().name)
            purgeCalls.incrementAndGet()
            purgeGate?.blockOnce()
            if (failure == "purge-false") return false
            return root.listFiles().orEmpty().map(File::delete).all { it } &&
                root.listFiles().orEmpty().isEmpty()
        }

        fun records(): List<JSONObject> = file.readLines().map { envelope ->
            val opened = crypto.aead.open(envelope, associatedData(), limits) as AeadOpenResult.Opened
            try { JSONObject(String(opened.plaintext, Charsets.UTF_8)) }
            finally { opened.plaintext.fill(0) }
        }
    }

    private fun event(outcome: SpeechOutputOutcome = SpeechOutputOutcome.REQUESTED,
        at: Long = 1L, error: Int? = null): SpeechOutputEvent =
        SpeechOutputEvent(SpeechOutputKind.NAVIGATION, outcome, at, error)

    private fun awaitCondition(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10L)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for serial test worker" }
            Thread.yield()
        }
    }
}
