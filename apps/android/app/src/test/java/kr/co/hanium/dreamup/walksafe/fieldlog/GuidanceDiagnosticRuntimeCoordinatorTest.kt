package kr.co.hanium.dreamup.walksafe.fieldlog

import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionVerificationState
import org.junit.Assert.*
import org.junit.Test

class GuidanceDiagnosticRuntimeCoordinatorTest {
    @Test fun constructionIsPureAndRecordIoRunsOnlyOnDedicatedWorker() {
        val f = Fixture()
        assertEquals(0, f.factoryCalls); assertEquals(0, f.calls.size); assertEquals(0, f.worker.tasks.size)
        f.runtime.publishAuthority(f.authority(network = false))
        f.runtime.startGuidanceSession()
        f.runtime.record("route_terminal", mapOf("reason" to "timeout", "token" to "secret", "message" to "address"))
        assertEquals(0, f.factoryCalls)
        f.worker.runAll()
        assertEquals(0, f.factoryCalls) // No preference receipt: memory only, never persist.
        f.runtime.publishAuthority(f.authority())
        f.worker.runAll()
        assertEquals(1, f.factoryCalls)
        assertEquals(2, f.uploaded.flatMap { it.events }.size)
        assertEquals(mapOf("reason" to "timeout"), f.uploaded.flatMap { it.events }.last().fields)
    }

    @Test fun preferenceDelayPreservesOriginalObservationTimestamp() {
        val f = Fixture()
        f.runtime.publishAuthority(f.authority(network = false))
        f.runtime.record("direction_grace", mapOf("grace_remaining_ms" to 900))
        f.wall += 8_000; f.elapsed += 8_000
        f.runtime.publishAuthority(f.authority()); f.worker.runAll()
        val event = f.uploaded.single().events.single()
        assertEquals(1_000L, event.recordedAtMs)
        assertEquals(200L, event.elapsedRealtimeMs)
    }

    @Test fun accountRetirementDuringClockCaptureRejectsOldActorRecord() {
        val f = Fixture()
        f.runtime.publishAuthority(f.authority(network = false))
        f.duringClock = {
            f.duringClock = null
            f.runtime.publishAuthority(f.authority(session("bob", 2), network = false))
        }
        assertFalse(f.runtime.record("route_terminal", mapOf("reason" to "old_actor")))
        assertEquals(0, f.runtime.state().pending)
    }

    @Test fun absentAuthorizationUsesPostAndExplicitServerDisabledNeverReenables() {
        val f = Fixture()
        f.remote = GuidanceDiagnosticPreference(true, false, 4)
        f.runtime.publishAuthority(f.authority())
        f.runtime.startGuidanceSession()
        f.worker.runAll(); f.runtime.tick(); f.worker.runAll()
        assertEquals(listOf("POST"), f.calls)
        assertEquals(1, f.factoryCalls) // Disabled receipt purges any previous process's own partition.
        assertEquals(1, f.queue.purged.distinct().size)
        assertFalse(f.runtime.state().authorized)
        assertFalse(f.runtime.record("tts", mapOf("tts_state" to "queued")))
    }

    @Test fun offlineRecordsAreBoundedAt100AndRecoverWithSameGuidanceUuid() {
        val f = Fixture()
        f.runtime.publishAuthority(f.authority(network = false))
        f.runtime.startGuidanceSession()
        repeat(99) { assertTrue(f.runtime.record("position_quality", mapOf("accuracy_m" to 3))) }
        assertFalse(f.runtime.record("position_quality"))
        assertEquals(100, f.runtime.state().pending)
        assertEquals(1, f.worker.tasks.size)
        f.worker.runAll()
        f.runtime.publishAuthority(f.authority()); f.worker.runAll()
        assertEquals(100, f.uploaded.single().events.size)
        assertEquals(1, f.uploaded.single().events.map { it.sessionId }.distinct().size)
    }

    @Test fun gatewayRenewalPreservesUuidAndOldQueueButRevalidatesPreference() {
        val f = Fixture()
        f.failUpload = true
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        val id = f.runtime.state().sessionId
        val owner = f.queue.events.keys.single()
        val renewed = session("alice", 1)
        f.runtime.publishAuthority(f.authority(renewed, gateway = 2)); f.worker.runAll()
        assertEquals(id, f.runtime.state().sessionId)
        assertEquals(1, f.calls.count { it == "POST" }); assertEquals(1, f.calls.count { it == "GET" })
        assertTrue(f.queue.purged.isEmpty())
        assertEquals(1, f.queue.events[owner]!!.size)
        f.failUpload = false; f.elapsed += 5_500; f.runtime.tick(); f.worker.runAll()
        assertEquals(0, f.queue.events[owner]!!.size)
    }

    @Test fun networkWithdrawalCancelsUploadAndPreservesQueueForRecovery() {
        val f = Fixture()
        f.duringUpload = { f.runtime.publishAuthority(f.authority(network = false)) }
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        assertEquals(1, f.queue.events.values.flatten().size)
        assertTrue(f.cancelled > 0)
        assertTrue(f.queue.purged.isEmpty())
        f.duringUpload = null
        f.elapsed += 5_500
        f.runtime.publishAuthority(f.authority()); f.worker.runAll()
        assertEquals(0, f.queue.events.values.flatten().size)
    }

    @Test fun frequentTicksBatchRecordsAndRespectServerUploadQuota() {
        val f = Fixture()
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        assertEquals(1, f.uploaded.size)
        repeat(15) {
            f.runtime.record("route_request", mapOf("stage" to "started"))
            f.runtime.tick(); f.worker.runAll()
        }
        assertEquals(1, f.uploaded.size)
        assertEquals(15, f.queue.events.values.flatten().size)
        f.elapsed += 5_500
        f.runtime.tick(); f.worker.runAll()
        assertEquals(2, f.uploaded.size)
        assertEquals(15, f.uploaded.last().events.size)
    }

    @Test fun offPurgesExactScopeAndCreatesDisabledReceiptWithRevisionZero() {
        val f = Fixture()
        f.remote = GuidanceDiagnosticPreference(false, false, 0)
        f.runtime.publishAuthority(f.authority(network = false)); f.worker.runAll()
        f.runtime.setLocalEnabled(false)
        assertFalse(f.runtime.record("tts"))
        f.runtime.publishAuthority(f.authority(network = true, local = false)); f.worker.runAll()
        assertEquals(listOf("GET", "PUT:false:0"), f.calls)
        assertEquals(GuidanceDiagnosticPreference(true, false, 1), f.remote)
        assertEquals(1, f.queue.purged.distinct().size)
        f.queue.events["another_account"] = mutableListOf()
        assertFalse(f.queue.purged.contains("another_account"))
    }

    @Test fun explicitReenableUsesCurrentRevisionAndAutomaticPublishCannotPutTrue() {
        val f = Fixture()
        f.remote = GuidanceDiagnosticPreference(true, false, 6)
        f.runtime.publishAuthority(f.authority(local = false)); f.worker.runAll()
        f.runtime.setLocalEnabled(true) // No explicit human action, ignored.
        f.worker.runAll(); assertFalse(f.runtime.state().enabled)
        f.runtime.publishAuthority(f.authority()); f.worker.runAll()
        assertEquals(listOf("GET", "GET"), f.calls)
        assertFalse(f.runtime.state().authorized)
        f.runtime.setLocalEnabled(true, explicitUserAction = true); f.worker.runAll()
        assertEquals("PUT:true:6", f.calls.last())
        assertTrue(f.runtime.state().authorized)
    }

    @Test fun actorAndAccountGenerationChangesPurgeOnlyRetiredPartitions() {
        val f = Fixture()
        f.failUpload = true
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        val aliceId = f.runtime.state().sessionId
        val aliceOwner = f.queue.events.keys.single()
        val bob = session("bob", 1)
        f.runtime.publishAuthority(f.authority(bob)); f.runtime.startGuidanceSession(); f.worker.runAll()
        val bobOwner = f.queue.events.keys.single()
        assertTrue(f.queue.purged.contains(aliceOwner))
        assertNotEquals(aliceId, f.runtime.state().sessionId)
        f.runtime.publishAuthority(f.authority(session("bob", 2))); f.worker.runAll()
        assertTrue(f.queue.purged.contains(bobOwner))
        assertEquals(2, f.queue.purged.distinct().size)
    }

    @Test fun deletionCancelsAdmissionAndStorageBlockDefersAllQueueIo() {
        val f = Fixture()
        f.failUpload = true
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        val io = f.queue.operations
        f.runtime.publishAuthority(f.authority().copy(deletionBlocked = true, storageBlocked = true))
        assertFalse(f.runtime.state().authorized)
        assertFalse(f.runtime.record("tts"))
        f.worker.runAll()
        assertEquals(io, f.queue.operations)
        f.runtime.publishAuthority(f.authority().copy(deletionBlocked = true)); f.worker.runAll()
        assertEquals(1, f.queue.purged.distinct().size)
        assertEquals(0, f.queue.events.values.flatten().size)
    }

    @Test fun transientAdmissionClosurePreservesQueueAndRevalidatesOnRecovery() {
        val f = Fixture()
        f.failUpload = true
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        val id = f.runtime.state().sessionId
        f.runtime.publishAuthority(f.authority().copy(admitted = false)); f.worker.runAll()
        assertTrue(f.queue.purged.isEmpty())
        f.runtime.publishAuthority(f.authority()); f.worker.runAll()
        assertEquals(id, f.runtime.state().sessionId)
        assertEquals(1, f.calls.count { it == "POST" }); assertEquals(1, f.calls.count { it == "GET" })
    }

    @Test fun latePreferenceResultCannotAuthorizeRetiredActor() {
        val f = Fixture()
        val bob = session("bob", 2)
        f.duringPost = {
            f.duringPost = null
            f.runtime.publishAuthority(f.authority(bob, network = false))
        }
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        assertFalse(f.runtime.state().authorized)
        assertEquals(0, f.runtime.state().pending)
        assertEquals(0, f.queue.events.values.flatten().size)
        f.runtime.publishAuthority(f.authority(bob)); f.runtime.startGuidanceSession(); f.worker.runAll()
        assertTrue(f.runtime.state().authorized)
        assertEquals(1, f.uploaded.single().events.size)
    }

    @Test fun noDebugApprovalInvalidAccountOrNonGeneralSessionCannotAuthorize() {
        for (snapshot in listOf(
            Fixture(false), Fixture(session = session("alice", 0)),
            Fixture(session = session("alice", 1, GatewaySessionScope.ACCOUNT_DELETION_RECOVERY)),
            Fixture(session = session("alice", 1).also {
                GatewayFieldSession::class.java.getDeclaredField("verificationState").apply { isAccessible = true }
                    .set(it, GatewaySessionVerificationState.RESTORED_UNVERIFIED)
            }),
        )) {
            snapshot.runtime.publishAuthority(snapshot.authority()); snapshot.runtime.startGuidanceSession(); snapshot.worker.runAll()
            assertFalse(snapshot.runtime.state().authorized)
            assertFalse(snapshot.runtime.record("tts"))
            assertTrue(snapshot.calls.isEmpty()); assertEquals(0, snapshot.factoryCalls)
            if (!snapshot.approved) {
                snapshot.runtime.publishAuthority(snapshot.authority(session("bob", 2))); snapshot.worker.runAll()
                assertTrue(snapshot.calls.isEmpty()); assertEquals(0, snapshot.factoryCalls)
            }
        }
        val f = Fixture()
        f.runtime.publishAuthority(f.authority().copy(accountGeneration = 9)); f.worker.runAll()
        assertTrue(f.calls.isEmpty()); assertEquals(0, f.factoryCalls)
    }

    @Test fun failedPurgeCannotReopenOrUploadPreviouslyWithdrawnDiagnostics() {
        val f = Fixture()
        f.failUpload = true
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        val uploaded = f.uploaded.size
        f.queue.failPurge = true
        f.runtime.setLocalEnabled(false); f.worker.runAll()
        f.runtime.setLocalEnabled(true, explicitUserAction = true); f.worker.runAll()
        assertFalse(f.runtime.state().authorized)
        assertFalse(f.runtime.record("tts"))
        assertEquals(uploaded, f.uploaded.size)
        assertEquals(1, f.queue.events.values.flatten().size)
        f.queue.failPurge = false
        f.runtime.tick(); f.worker.runAll()
        assertEquals(0, f.queue.events.values.flatten().size)
        assertTrue(f.runtime.state().authorized)
    }

    @Test fun closeCancelsLateCallsAndPreservesEncryptedQueueForNextActivity() {
        val f = Fixture()
        f.failUpload = true
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        f.runtime.close(); f.worker.runAll()
        assertFalse(f.runtime.state().authorized)
        assertFalse(f.runtime.record("tts"))
        assertEquals(1, f.queue.events.values.flatten().size)
        assertTrue(f.queue.purged.isEmpty())
    }

    @Test fun serverOff409PurgesOldScopeAndRefreshesWithoutAutomaticReenable() {
        val f = Fixture()
        f.duringUpload = { f.duringUpload = null; f.remote = GuidanceDiagnosticPreference(true, false, 2) }
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        assertEquals(listOf("POST", "GET"), f.calls)
        assertFalse(f.runtime.state().authorized)
        assertFalse(f.runtime.record("tts"))
        assertEquals(0, f.queue.events.values.flatten().size)
        assertEquals(0, f.queue.acknowledgements)
        assertTrue(f.uploaded.isEmpty())
    }

    @Test fun remoteOffThenOnRevisionChangeCannotResurrectOldEventsAfter409() {
        val f = Fixture()
        f.duringUpload = { f.duringUpload = null; f.remote = GuidanceDiagnosticPreference(true, true, 3) }
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        assertTrue(f.runtime.state().authorized)
        assertEquals(0, f.queue.events.values.flatten().size)
        assertTrue(f.uploaded.isEmpty())
        f.elapsed += 5_500
        assertTrue(f.runtime.record("route_terminal", mapOf("reason" to "current")))
        f.worker.runAll()
        assertEquals(listOf("route_terminal"), f.uploaded.single().events.map { it.name })
    }

    @Test fun lateOldAckAfterServerOffAndFreshStartCannotAcknowledgeWithdrawnQueue() {
        val f = Fixture()
        f.allowLateAck = true
        f.duringUpload = {
            f.duringUpload = null; f.remote = GuidanceDiagnosticPreference(true, false, 2)
            f.runtime.startGuidanceSession()
        }
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        assertFalse(f.runtime.state().authorized)
        assertEquals(0, f.queue.acknowledgements)
        assertEquals(0, f.queue.events.values.flatten().size)
        assertEquals(listOf("POST", "GET"), f.calls)
    }

    @Test fun ttlRefreshSameRevisionPreservesLostAckQueueAndChangedRevisionPurgesIt() {
        val f = Fixture()
        f.failUpload = true
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        val id = f.queue.events.values.flatten().single().id
        f.elapsed += 60_000; f.runtime.tick(); f.worker.runAll()
        assertEquals(id, f.queue.events.values.flatten().single().id)
        assertTrue(f.queue.purged.isEmpty())
        f.remote = GuidanceDiagnosticPreference(true, true, 3)
        f.elapsed += 60_000; f.runtime.tick(); f.worker.runAll()
        assertEquals(0, f.queue.events.values.flatten().size)
        assertEquals(listOf("POST", "GET", "GET"), f.calls)
    }

    @Test fun offlineTtlExpiryClosesDiskAdmissionButKeepsAtMost100MemoryRecords() {
        val f = Fixture()
        f.failUpload = true
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        f.runtime.publishAuthority(f.authority(network = false)); f.worker.runAll()
        val io = f.queue.operations
        f.elapsed += 60_000
        f.runtime.tick(); f.worker.runAll()
        assertFalse(f.runtime.state().authorized)
        repeat(100) { assertTrue(f.runtime.record("position_quality")) }
        assertFalse(f.runtime.record("tts")); f.worker.runAll()
        assertEquals(io, f.queue.operations)
        assertEquals(1, f.queue.events.values.flatten().size)
        f.remote = GuidanceDiagnosticPreference(true, false, 2)
        f.runtime.publishAuthority(f.authority()); f.worker.runAll()
        assertFalse(f.runtime.record("tts"))
        assertEquals(0, f.runtime.state().pending)
        assertEquals(0, f.queue.events.values.flatten().size)
    }

    @Test fun rejected401Or403RequiresFreshGeneralAuthorityBeforeAnyMoreRequests() {
        for (status in listOf(401, 403)) {
            val f = Fixture()
            f.uploadFailureStatus = status
            f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
            assertFalse(f.runtime.state().authorized); assertFalse(f.runtime.record("tts"))
            f.runtime.tick(); f.worker.runAll()
            assertEquals(listOf("POST"), f.calls)
            f.uploadFailureStatus = null
            f.runtime.publishAuthority(f.authority(session("alice", 1), gateway = 2)); f.worker.runAll()
            assertEquals(listOf("POST", "GET"), f.calls)
            assertTrue(f.runtime.state().authorized)
        }
    }

    @Test fun processRestartDiscardsDifferentPreferenceRevisionButRetriesSameRevision() {
        for (revision in listOf(1L, 3L)) {
            val original = Fixture()
            original.failUpload = true
            original.runtime.publishAuthority(original.authority()); original.runtime.startGuidanceSession(); original.worker.runAll()
            val old = original.queue.events.values.flatten().single()
            original.runtime.close(); original.worker.runAll()
            val restarted = Fixture(sharedQueue = original.queue)
            restarted.remote = GuidanceDiagnosticPreference(true, true, revision)
            restarted.runtime.publishAuthority(restarted.authority()); restarted.worker.runAll()
            if (revision == 1L) assertEquals(old.id, restarted.uploaded.single().events.single().id)
            else { assertTrue(restarted.uploaded.isEmpty()); assertEquals(0, restarted.queue.events.values.flatten().size) }
            assertEquals(revision, restarted.queue.revisions.values.single())
        }
    }

    @Test fun deletionBarrierAfterCloseCompletesOnlyAfterWorkerPurgeAndRejectsLateWrites() {
        val f = Fixture()
        f.failUpload = true
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        f.runtime.close()
        var called = false
        val completion = f.runtime.purgeForAccountDeletion {
            assertTrue(f.worker.running); called = true; f.queue.events.clear(); f.queue.revisions.clear(); true
        }
        assertFalse(completion.isDone)
        assertFalse(f.runtime.record("tts"))
        f.worker.runAll()
        assertTrue(called); assertTrue(completion.get(1, TimeUnit.SECONDS))
        f.runtime.tick(); f.worker.runAll()
        assertEquals(0, f.queue.events.values.flatten().size)
        assertSame(completion, f.runtime.purgeForAccountDeletion { error("duplicate callback") })
    }

    @Test fun failedDeletionBarrierReturnsFalseAndKeepsAdmissionClosed() {
        val f = Fixture()
        f.runtime.publishAuthority(f.authority()); f.worker.runAll()
        val completion = f.runtime.purgeForAccountDeletion { false }
        f.worker.runAll()
        assertFalse(completion.get(1, TimeUnit.SECONDS)); assertFalse(f.runtime.state().authorized)
        assertFalse(f.runtime.record("tts"))
    }

    @Test fun deletionBarrierWaitsForInFlightQueueWriteBeforeReturningDeleted() {
        val appendStarted = CountDownLatch(1)
        val finishAppend = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val steps = java.util.Collections.synchronizedList(mutableListOf<String>())
        val queueExists = java.util.concurrent.atomic.AtomicBoolean(false)
        val main = Thread.currentThread()
        val store = object : GuidanceDiagnosticQueue {
            override fun bindPreference(ownerKey: String, revision: Long) = false
            override fun append(ownerKey: String, event: GuidanceDiagnosticEvent, nowMs: Long): Int {
                assertNotSame(main, Thread.currentThread())
                appendStarted.countDown()
                check(finishAppend.await(3, TimeUnit.SECONDS))
                queueExists.set(true); steps.add("write_finished"); return 1
            }
            override fun peek(ownerKey: String, nowMs: Long, limit: Int) = emptyList<GuidanceDiagnosticEvent>()
            override fun acknowledge(ownerKey: String, eventIds: Set<String>) = error("no ACK after delete")
            override fun purge(ownerKey: String) { queueExists.set(false) }
        }
        val receipt = { CancellableNetworkCall(executeBlock = { GuidanceDiagnosticPreference(true, true, 1) }) }
        val runtime = GuidanceDiagnosticRuntimeCoordinator(true, "abcdef0", { store }, object : GuidanceDiagnosticTransport {
            override fun upload(binding: GuidanceDiagnosticBinding, batch: GuidanceDiagnosticBatch) = error("no upload after delete")
        }, { receipt() }, { receipt() }, { _, _, _ -> error("no PUT") }, { 1_000 }, { 200 }, worker = executor, ownsWorker = false)
        runtime.publishAuthority(GuidanceDiagnosticRuntimeCoordinator.Authority(session("alice", 1), 1, 1, true, true, true))
        runtime.startGuidanceSession()
        assertTrue(appendStarted.await(3, TimeUnit.SECONDS))
        val before = System.nanoTime()
        val completion = runtime.purgeForAccountDeletion {
            assertNotSame(main, Thread.currentThread())
            queueExists.set(false); steps.add("purged"); true
        }
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 100)
        assertFalse(completion.isDone)
        assertFalse(runtime.record("tts"))
        finishAppend.countDown()
        assertTrue(completion.get(3, TimeUnit.SECONDS))
        assertEquals(listOf("write_finished", "purged"), steps)
        assertFalse(queueExists.get())
        runtime.close(); executor.shutdown(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        assertFalse(queueExists.get())
    }

    @Test fun failedRefreshSuspendsWithoutClockOrQueueAndPreservesPendingRecordsUntilFreshGet() {
        val f = Fixture()
        f.failUpload = true
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        val original = f.queue.events.values.flatten().single()
        val id = f.runtime.state().sessionId
        assertTrue(f.runtime.record("tts", mapOf("tts_state" to "queued")))
        val clockCalls = f.clockCalls
        val io = f.queue.operations
        val factoryCalls = f.factoryCalls
        f.runtime.suspendAuthority()
        assertFalse(f.runtime.state().authorized)
        assertFalse(f.runtime.record("route_terminal"))
        assertEquals(clockCalls, f.clockCalls)
        assertEquals(id, f.runtime.state().sessionId)
        repeat(5) { f.runtime.tick(); f.worker.runAll() }
        assertEquals(clockCalls, f.clockCalls)
        assertEquals(io, f.queue.operations)
        assertEquals(factoryCalls, f.factoryCalls)
        assertEquals(listOf(original.id), f.queue.events.values.flatten().map { it.id })
        assertTrue(f.queue.purged.isEmpty())
        assertEquals(2, f.runtime.state().pending)
        f.failUpload = false; f.elapsed += 5_500
        f.runtime.publishAuthority(f.authority()); f.worker.runAll()
        assertEquals(listOf("POST", "GET"), f.calls)
        assertTrue(f.runtime.state().authorized)
        assertEquals(id, f.runtime.state().sessionId)
        assertEquals(listOf("navigation_start", "tts"), f.uploaded.single().events.map { it.name })
        assertEquals(original.id, f.uploaded.single().events.first().id)
    }

    @Test fun suspendPreservesServerRevocationAndLocalOffGatesAfterFreshPublication() {
        val f = Fixture()
        f.remote = GuidanceDiagnosticPreference(true, false, 4)
        f.runtime.publishAuthority(f.authority()); f.worker.runAll()
        f.runtime.suspendAuthority(); f.runtime.tick(); f.worker.runAll()
        assertFalse(f.runtime.record("tts"))
        f.runtime.publishAuthority(f.authority()); f.worker.runAll()
        assertFalse(f.runtime.state().authorized)
        assertFalse(f.runtime.record("tts"))
        assertEquals(listOf("POST", "GET"), f.calls)
        f.runtime.setLocalEnabled(false); f.worker.runAll()
        f.runtime.suspendAuthority(); f.worker.runAll()
        f.runtime.publishAuthority(f.authority(local = false)); f.worker.runAll()
        assertFalse(f.runtime.state().enabled)
        assertFalse(f.runtime.record("tts"))
        assertTrue(f.calls.none { it.startsWith("PUT:true") })
    }

    @Test fun suspendPreservesDeletionScopeUntilExplicitFreshSnapshotAndExactPurge() {
        val f = Fixture()
        f.failUpload = true
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        f.runtime.publishAuthority(f.authority().copy(deletionBlocked = true, storageBlocked = true))
        f.runtime.suspendAuthority(); f.runtime.tick(); f.worker.runAll()
        assertEquals(1, f.queue.events.values.flatten().size)
        assertTrue(f.queue.purged.isEmpty())
        assertFalse(f.runtime.record("tts"))
        f.runtime.publishAuthority(f.authority().copy(deletionBlocked = true)); f.worker.runAll()
        assertEquals(0, f.queue.events.values.flatten().size)
        assertEquals(1, f.queue.purged.distinct().size)
        assertFalse(f.runtime.state().authorized)
    }

    @Test fun suspendCancelsRealBlockingUploadAndFreshAuthorityRetriesSameEventsAfterGet() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val suspended = CountDownLatch(1)
        val uploaded = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val user = session("alice", 1)
        val items = java.util.Collections.synchronizedList(mutableListOf<GuidanceDiagnosticEvent>())
        val io = java.util.concurrent.atomic.AtomicInteger()
        val uploads = java.util.concurrent.atomic.AtomicInteger()
        val gets = java.util.concurrent.atomic.AtomicInteger()
        val elapsed = java.util.concurrent.atomic.AtomicLong(200)
        val retryIds = java.util.concurrent.atomic.AtomicReference<List<String>>()
        val store = object : GuidanceDiagnosticQueue {
            override fun bindPreference(ownerKey: String, revision: Long): Boolean { io.incrementAndGet(); return false }
            override fun append(ownerKey: String, event: GuidanceDiagnosticEvent, nowMs: Long): Int {
                io.incrementAndGet(); items.add(event); return items.size
            }
            override fun peek(ownerKey: String, nowMs: Long, limit: Int): List<GuidanceDiagnosticEvent> {
                io.incrementAndGet(); return items.toList()
            }
            override fun acknowledge(ownerKey: String, eventIds: Set<String>): Int {
                io.incrementAndGet(); items.removeAll { it.id in eventIds }; uploaded.countDown(); return items.size
            }
            override fun purge(ownerKey: String) = error("suspension must preserve queued diagnostics")
        }
        val receipt = { CancellableNetworkCall(executeBlock = { GuidanceDiagnosticPreference(true, true, 1) }) }
        val runtime = GuidanceDiagnosticRuntimeCoordinator(true, "abcdef0", { store }, object : GuidanceDiagnosticTransport {
            override fun upload(binding: GuidanceDiagnosticBinding, batch: GuidanceDiagnosticBatch) = CancellableNetworkCall(executeBlock = {
                if (uploads.incrementAndGet() == 1) {
                    started.countDown(); check(release.await(3, TimeUnit.SECONDS))
                } else retryIds.set(batch.events.map { it.id })
                GuidanceDiagnosticAck(batch.id, batch.events.size)
            }, cancelBlock = { cancelled.countDown(); release.countDown() })
        }, { gets.incrementAndGet(); receipt() }, { receipt() }, { _, _, _ -> error("no preference PUT") },
            { 1_000 }, { elapsed.get() }, onState = { if (!it.enabled) suspended.countDown() }, worker = executor, ownsWorker = false)
        try {
            val authority = GuidanceDiagnosticRuntimeCoordinator.Authority(user, 1, 1, true, true, true)
            runtime.publishAuthority(authority); runtime.startGuidanceSession()
            assertTrue(started.await(3, TimeUnit.SECONDS))
            val id = runtime.state().sessionId
            val queuedIds = items.map { it.id }
            val getsBeforeSuspend = gets.get()
            val before = System.nanoTime()
            runtime.suspendAuthority()
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 100)
            assertTrue(cancelled.await(1, TimeUnit.SECONDS))
            assertFalse(runtime.record("tts"))
            assertTrue(suspended.await(3, TimeUnit.SECONDS))
            val blockedIo = io.get()
            val ticksFinished = CountDownLatch(1)
            repeat(5) { runtime.tick() }
            executor.execute { ticksFinished.countDown() }
            assertTrue(ticksFinished.await(3, TimeUnit.SECONDS))
            assertEquals(blockedIo, io.get()); assertEquals(1, uploads.get()); assertEquals(getsBeforeSuspend, gets.get())
            assertEquals(queuedIds, items.map { it.id }); assertEquals(id, runtime.state().sessionId)
            elapsed.addAndGet(5_500)
            runtime.publishAuthority(authority)
            assertTrue(uploaded.await(3, TimeUnit.SECONDS))
            assertEquals(getsBeforeSuspend + 1, gets.get()); assertEquals(queuedIds, retryIds.get())
            assertEquals(id, runtime.state().sessionId)
        } finally {
            runtime.close(); release.countDown(); executor.shutdown()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
        }
    }

    @Test fun persistedLocalOffAfterRestartSynchronizesFreshEnabledReceiptOnlyOnce() {
        val f = Fixture()
        f.remote = GuidanceDiagnosticPreference(true, true, 7)
        val snapshot = f.authority(local = false)
        f.runtime.publishAuthority(snapshot); f.worker.runAll()
        assertEquals(listOf("GET", "PUT:false:7"), f.calls)
        assertEquals(GuidanceDiagnosticPreference(true, false, 8), f.remote)
        assertFalse(f.runtime.state().authorized); assertFalse(f.runtime.record("tts"))
        repeat(6) { f.runtime.publishAuthority(snapshot); f.runtime.tick(); f.worker.runAll() }
        f.elapsed += 60_000; f.runtime.tick(); f.worker.runAll()
        assertEquals(1, f.calls.count { it.startsWith("PUT") })
        assertTrue(f.calls.none { it == "POST" || it.startsWith("PUT:true") })
    }

    @Test fun implicitOffSyncCannotWriteAbsentDisabledOrInvalidPreferenceReceipts() {
        for (remote in listOf(
            GuidanceDiagnosticPreference(false, false, 0), GuidanceDiagnosticPreference(true, false, 7),
            GuidanceDiagnosticPreference(true, true, 0), GuidanceDiagnosticPreference(false, true, 0),
            GuidanceDiagnosticPreference(true, true, -1),
        )) {
            val f = Fixture(); f.remote = remote
            f.runtime.publishAuthority(f.authority(local = false)); f.worker.runAll()
            assertEquals(listOf("GET"), f.calls)
            assertFalse(f.runtime.state().authorized); assertFalse(f.runtime.record("tts"))
        }
    }

    @Test fun implicitOffSyncWaitsForNetworkStorageDeletionAndSuccessfulAuthentication() {
        val f = Fixture()
        f.remote = GuidanceDiagnosticPreference(true, true, 7)
        for (snapshot in listOf(f.authority(local = false, network = false),
            f.authority(local = false).copy(storageBlocked = true),
            f.authority(local = false).copy(deletionBlocked = true))) {
            f.runtime.publishAuthority(snapshot); f.worker.runAll()
            assertTrue(f.calls.isEmpty()); assertFalse(f.runtime.record("tts"))
        }
        f.preferenceFailureStatus = 401
        f.runtime.publishAuthority(f.authority(local = false)); f.worker.runAll()
        assertEquals(listOf("GET"), f.calls)
        assertEquals(GuidanceDiagnosticPreference(true, true, 7), f.remote)
        f.preferenceFailureStatus = null
        f.runtime.tick(); f.worker.runAll()
        assertEquals(listOf("GET", "GET", "PUT:false:7"), f.calls)
        assertFalse(f.remote.enabled)
    }

    @Test fun implicitOffSyncRetriesCasWithFreshRevisionAndRejectsRetiredActorReceipt() {
        val f = Fixture()
        f.remote = GuidanceDiagnosticPreference(true, true, 7)
        f.beforePreferenceWrite = {
            f.beforePreferenceWrite = null; f.remote = GuidanceDiagnosticPreference(true, true, 9)
        }
        f.runtime.publishAuthority(f.authority(local = false)); f.worker.runAll()
        assertTrue(f.remote.enabled); assertEquals(9L, f.remote.revision)
        f.runtime.tick(); f.worker.runAll()
        assertEquals(listOf("GET", "PUT:false:7", "GET", "PUT:false:9"), f.calls)
        assertFalse(f.remote.enabled)
        val retired = Fixture()
        retired.remote = GuidanceDiagnosticPreference(true, true, 7)
        val replacement = session("replacement", 8)
        retired.duringGet = {
            retired.duringGet = null
            retired.runtime.publishAuthority(retired.authority(replacement, local = false, network = false))
        }
        retired.runtime.publishAuthority(retired.authority(local = false)); retired.worker.runAll()
        assertEquals(listOf("GET"), retired.calls)
        assertTrue(retired.preferenceWriteActors.isEmpty())
        retired.runtime.publishAuthority(retired.authority(replacement, local = false)); retired.worker.runAll()
        assertEquals(listOf("replacement"), retired.preferenceWriteActors)
        assertEquals(listOf("GET", "GET", "PUT:false:7"), retired.calls)
    }

    @Test fun coldOfflineOffPurgesBeforeIdempotentExplicitOnCanReuseOldRevision() {
        val f = Fixture(session = session("alice", 7)); f.remote = GuidanceDiagnosticPreference(true, true, 7)
        val old = diagnosticEvent(f.session, 7)
        val owner = ownerKey(f.session, 7)
        f.queue.events[owner] = mutableListOf(old); f.queue.revisions[owner] = 7
        f.runtime.publishAuthority(f.authority(local = false, network = false))
        f.runtime.setLocalEnabled(true, explicitUserAction = true)
        assertFalse(f.runtime.record("tts"))
        f.runtime.publishAuthority(f.authority()); f.worker.runAll()
        assertTrue(f.queue.purged.contains(owner)); assertTrue(f.uploaded.isEmpty())
        assertEquals(7L, f.remote.revision) // Real backend true -> true is idempotent.
        assertTrue(f.runtime.record("route_terminal", mapOf("reason" to "current")))
        f.worker.runAll()
        assertEquals("route_terminal", f.uploaded.single().events.single().name)
        assertNotEquals(old.id, f.uploaded.single().events.single().id)
    }

    @Test fun failedColdOffPurgeKeepsExplicitOnClosedUntilOldDataRemoved() {
        val f = Fixture(session = session("alice", 7)); f.remote = GuidanceDiagnosticPreference(true, true, 7)
        val old = diagnosticEvent(f.session, 7); val owner = ownerKey(f.session, 7)
        f.queue.events[owner] = mutableListOf(old); f.queue.revisions[owner] = 7; f.queue.failPurge = true
        f.runtime.publishAuthority(f.authority(local = false, network = false)); f.worker.runAll()
        f.runtime.setLocalEnabled(true, explicitUserAction = true)
        f.runtime.publishAuthority(f.authority()); f.worker.runAll()
        assertFalse(f.runtime.state().authorized); assertFalse(f.runtime.record("tts"))
        assertTrue(f.uploaded.isEmpty()); assertEquals(listOf(old), f.queue.events[owner])
        f.queue.failPurge = false; f.runtime.tick(); f.worker.runAll()
        assertTrue(f.runtime.state().authorized); assertEquals(7L, f.remote.revision)
        assertTrue(f.runtime.record("tts", mapOf("tts_state" to "DONE"))); f.worker.runAll()
        assertNotEquals(old.id, f.uploaded.single().events.single().id)
    }

    @Test fun coldOffRequiresTrustedScopeAndNeverPurgesAnotherOwner() {
        val recovery = session("fixture", 7, GatewaySessionScope.ACCOUNT_DELETION_RECOVERY)
        val unverified = session("fixture", 7).also {
            GatewayFieldSession::class.java.getDeclaredField("verificationState").apply { isAccessible = true }
                .set(it, GatewaySessionVerificationState.RESTORED_UNVERIFIED)
        }
        val normal = session("fixture", 7)
        for (authority in listOf(
            GuidanceDiagnosticRuntimeCoordinator.Authority(recovery, 1, 7, true, false, false),
            GuidanceDiagnosticRuntimeCoordinator.Authority(unverified, 1, 7, true, false, false),
            GuidanceDiagnosticRuntimeCoordinator.Authority(normal, 1, 8, true, false, false),
            GuidanceDiagnosticRuntimeCoordinator.Authority(normal, 1, 7, false, false, false),
            GuidanceDiagnosticRuntimeCoordinator.Authority(normal, 1, 7, true, false, false, storageBlocked = true),
        )) {
            val f = Fixture(); val other = session("other", 9); val key = ownerKey(other, 9)
            val record = diagnosticEvent(other, 9); f.queue.events[key] = mutableListOf(record)
            f.runtime.publishAuthority(authority); f.worker.runAll()
            assertTrue(f.queue.purged.isEmpty()); assertEquals(listOf(record), f.queue.events[key])
            assertFalse(f.runtime.state().authorized)
        }
        val f = Fixture(); val other = session("other", 9); val key = ownerKey(other, 9)
        val record = diagnosticEvent(other, 9); f.queue.events[key] = mutableListOf(record)
        f.runtime.publishAuthority(f.authority(local = false, network = false).copy(deletionBlocked = true)); f.worker.runAll()
        assertEquals(listOf(record), f.queue.events[key]); assertFalse(f.queue.purged.contains(key))
    }

    @Test fun oldActor401CannotPreventFreshVerifiedActorColdOffPurge() {
        val f = Fixture(); f.uploadFailureStatus = 401
        f.runtime.publishAuthority(f.authority()); f.runtime.startGuidanceSession(); f.worker.runAll()
        assertFalse(f.runtime.state().enabled)
        val replacement = session("replacement", 8); val key = ownerKey(replacement, 8)
        f.queue.events[key] = mutableListOf(diagnosticEvent(replacement, 8)); f.queue.revisions[key] = 1
        val other = session("other", 9); val otherKey = ownerKey(other, 9)
        val preserved = diagnosticEvent(other, 9); f.queue.events[otherKey] = mutableListOf(preserved)
        f.runtime.publishAuthority(f.authority(replacement, local = false, network = false)); f.worker.runAll()
        assertTrue(f.queue.purged.contains(key)); assertFalse(f.queue.events.containsKey(key))
        assertEquals(listOf(preserved), f.queue.events[otherKey]); assertFalse(f.queue.purged.contains(otherKey))
    }

    @Test fun restoredOffIntentWaitsForSameOwnerVerificationAndEnableCommitIsCurrentOnly() {
        val restored = session("alice", 7).also {
            GatewayFieldSession::class.java.getDeclaredField("verificationState").apply { isAccessible = true }
                .set(it, GatewaySessionVerificationState.RESTORED_UNVERIFIED)
        }
        val f = Fixture(session = restored); f.remote = GuidanceDiagnosticPreference(true, true, 7)
        val owner = ownerKey(restored, 7); val old = diagnosticEvent(restored, 7)
        f.queue.events[owner] = mutableListOf(old); f.queue.revisions[owner] = 7
        f.runtime.publishAuthority(f.authority(local = false, network = false).copy(admitted = false)); f.worker.runAll()
        assertEquals(0, f.factoryCalls); assertTrue(f.queue.purged.isEmpty())
        f.runtime.setLocalEnabled(true, explicitUserAction = true); f.worker.runAll()
        assertTrue(f.runtime.state().requestedLocalEnabled); assertFalse(f.runtime.state().enabled)
        assertTrue(f.runtime.authorityLocalEnabled(restored, 7, false))
        assertFalse(f.runtime.consumePendingEnableCommit(restored, 1, 7))
        val verified = session("alice", 7)
        f.runtime.publishAuthority(f.authority(verified, gateway = 2,
            local = f.runtime.authorityLocalEnabled(verified, 7, false)))
        assertFalse(f.runtime.record("tts")); f.worker.runAll()
        assertTrue(f.queue.purged.contains(owner)); assertTrue(f.uploaded.isEmpty())
        assertEquals(7L, f.remote.revision); assertEquals(listOf("GET", "PUT:true:7"), f.calls)
        assertFalse(f.runtime.consumePendingEnableCommit(restored, 2, 7))
        assertFalse(f.runtime.consumePendingEnableCommit(verified, 1, 7))
        assertFalse(f.runtime.consumePendingEnableCommit(verified, 2, 8))
        f.elapsed += 60_000
        assertFalse(f.runtime.consumePendingEnableCommit(verified, 2, 7))
        f.runtime.tick(); f.worker.runAll()
        assertTrue(f.runtime.consumePendingEnableCommit(verified, 2, 7))
        assertFalse(f.runtime.consumePendingEnableCommit(verified, 2, 7))
        assertFalse(f.runtime.authorityLocalEnabled(verified, 7, false))
        assertTrue(f.runtime.authorityLocalEnabled(verified, 7, true))
    }

    @Test fun pendingEnableCannotTransferUntrustedOffToAnotherActorOrGeneration() {
        for (replacement in listOf(session("bob", 7), session("alice", 8))) {
            val restored = session("alice", 7).also {
                GatewayFieldSession::class.java.getDeclaredField("verificationState").apply { isAccessible = true }
                    .set(it, GatewaySessionVerificationState.RESTORED_UNVERIFIED)
            }
            val f = Fixture(session = restored)
            val owner = ownerKey(restored, 7); val old = diagnosticEvent(restored, 7)
            f.queue.events[owner] = mutableListOf(old); f.queue.revisions[owner] = 7
            f.runtime.publishAuthority(f.authority(local = false, network = false).copy(admitted = false))
            f.runtime.setLocalEnabled(true, explicitUserAction = true)
            assertFalse(f.runtime.authorityLocalEnabled(replacement, replacement.backendAccountGeneration, false))
            f.runtime.publishAuthority(f.authority(replacement, network = false, local = false)); f.worker.runAll()
            assertFalse(f.runtime.state().requestedLocalEnabled)
            assertFalse(f.runtime.authorityLocalEnabled(restored, 7, false))
            assertFalse(f.runtime.consumePendingEnableCommit(replacement, 1, replacement.backendAccountGeneration))
            assertEquals(listOf(old), f.queue.events[owner]); assertFalse(f.queue.purged.contains(owner))
            assertTrue(f.calls.isEmpty())
        }
    }

    @Test fun explicitEnableWithoutCurrentGeneralOwnerCannotEnableFutureLogin() {
        val f = Fixture()
        f.runtime.setLocalEnabled(true, explicitUserAction = true)
        assertFalse(f.runtime.state().requestedLocalEnabled)
        assertFalse(f.runtime.authorityLocalEnabled(f.session, 1, false))
        f.runtime.publishAuthority(f.authority(local = false, network = false)); f.worker.runAll()
        assertFalse(f.runtime.state().requestedLocalEnabled)
        for (blocked in listOf(
            f.authority(session("alice", 1, GatewaySessionScope.ACCOUNT_DELETION_RECOVERY), local = false),
            f.authority(local = false).copy(deletionBlocked = true),
        )) {
            f.runtime.publishAuthority(blocked); f.worker.runAll()
            f.runtime.setLocalEnabled(true, explicitUserAction = true); f.worker.runAll()
            assertFalse(f.runtime.state().requestedLocalEnabled)
            assertFalse(f.runtime.authorityLocalEnabled(f.session, 1, false))
            assertFalse(f.runtime.consumePendingEnableCommit(f.session, 1, 1))
        }
        assertTrue(f.calls.none { it == "POST" || it.startsWith("PUT:true") })
    }

    @Test fun explicitOffCloseAndDeletionDiscardPendingEnableCommit() {
        for (finish in listOf<(Fixture) -> Unit>(
            { it.runtime.setLocalEnabled(false) }, { it.runtime.close() },
            { it.runtime.publishAuthority(it.authority().copy(deletionBlocked = true)) },
            { it.runtime.purgeForAccountDeletion { true } },
        )) {
            val f = Fixture()
            f.runtime.publishAuthority(f.authority(local = false, network = false))
            f.runtime.setLocalEnabled(true, explicitUserAction = true)
            f.runtime.publishAuthority(f.authority()); f.worker.runAll()
            assertTrue(f.runtime.state().authorized)
            finish(f); f.worker.runAll()
            assertFalse(f.runtime.authorityLocalEnabled(f.session, 1, false))
            assertFalse(f.runtime.consumePendingEnableCommit(f.session, 1, 1))
        }
    }

    @Test fun suspendedPendingEnableCannotCommitUntilFreshGeneralPreferenceRevalidation() {
        val f = Fixture()
        f.runtime.publishAuthority(f.authority(local = false, network = false))
        f.runtime.setLocalEnabled(true, explicitUserAction = true)
        f.runtime.publishAuthority(f.authority()); f.worker.runAll()
        val gets = f.calls.count { it == "GET" }; val io = f.queue.operations
        f.runtime.suspendAuthority(); f.worker.runAll()
        assertFalse(f.runtime.consumePendingEnableCommit(f.session, 1, 1))
        assertTrue(f.runtime.state().requestedLocalEnabled); assertFalse(f.runtime.record("tts"))
        f.runtime.tick(); f.worker.runAll(); assertEquals(io, f.queue.operations)
        f.runtime.publishAuthority(f.authority(local = f.runtime.authorityLocalEnabled(f.session, 1, false)))
        assertFalse(f.runtime.consumePendingEnableCommit(f.session, 1, 1)); f.worker.runAll()
        assertEquals(gets + 1, f.calls.count { it == "GET" })
        assertTrue(f.runtime.consumePendingEnableCommit(f.session, 1, 1))
    }

    @Test fun failedPurgeAndSecondRestartKeepPersistedOffUntilActivationProof() {
        val first = Fixture(session = session("alice", 7)); first.remote = GuidanceDiagnosticPreference(true, true, 7)
        val owner = ownerKey(first.session, 7); val old = diagnosticEvent(first.session, 7)
        first.queue.events[owner] = mutableListOf(old); first.queue.revisions[owner] = 7; first.queue.failPurge = true
        var persistedEnabled = false
        first.runtime.publishAuthority(first.authority(local = persistedEnabled, network = false)); first.worker.runAll()
        first.runtime.setLocalEnabled(true, explicitUserAction = true)
        first.runtime.publishAuthority(first.authority(local = first.runtime.authorityLocalEnabled(first.session, 7, persistedEnabled)))
        first.worker.runAll()
        if (first.runtime.consumePendingEnableCommit(first.session, 1, 7)) persistedEnabled = true
        assertFalse(persistedEnabled); assertFalse(first.runtime.record("tts")); assertTrue(first.uploaded.isEmpty())
        first.runtime.close(); first.worker.runAll(); first.queue.failPurge = false
        val restarted = Fixture(session = session("alice", 7), sharedQueue = first.queue)
        restarted.remote = first.remote
        restarted.runtime.publishAuthority(restarted.authority(local = persistedEnabled)); restarted.worker.runAll()
        assertFalse(restarted.runtime.state().authorized); assertTrue(restarted.uploaded.isEmpty())
        assertFalse(restarted.queue.events.containsKey(owner)); assertFalse(restarted.remote.enabled)
        restarted.runtime.setLocalEnabled(true, explicitUserAction = true); restarted.worker.runAll()
        assertTrue(restarted.runtime.consumePendingEnableCommit(restarted.session, 1, 7)); persistedEnabled = true
        assertTrue(persistedEnabled)
        assertTrue(restarted.runtime.record("tts", mapOf("tts_state" to "DONE"))); restarted.worker.runAll()
        assertNotEquals(old.id, restarted.uploaded.single().events.single().id)
    }

    @Test fun blockingPreferenceDoesNotBlockUiAdmissionAndMemoryIsBounded() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val user = session("alice", 1)
        val remoteCall = CancellableNetworkCall(executeBlock = {
            started.countDown()
            check(release.await(3, TimeUnit.SECONDS))
            GuidanceDiagnosticPreference(true, true, 1)
        }, cancelBlock = { release.countDown() }, rejectLateResult = true)
        val runtime = GuidanceDiagnosticRuntimeCoordinator(true, "abcdef0", { error("disabled scope must not initialize queue") },
            object : GuidanceDiagnosticTransport {
                override fun upload(binding: GuidanceDiagnosticBinding, batch: GuidanceDiagnosticBatch) = error("disabled scope cannot upload")
            }, { remoteCall }, { remoteCall }, { _, _, _ -> error("no PUT") }, { 1_000 }, { 200 },
            onState = { if (!it.enabled) finished.countDown() }, worker = executor, ownsWorker = false)
        runtime.publishAuthority(GuidanceDiagnosticRuntimeCoordinator.Authority(user, 1, 1, true, true, true))
        assertTrue(started.await(3, TimeUnit.SECONDS))
        repeat(100) { assertTrue(runtime.record("position_quality")) }
        assertFalse(runtime.record("tts"))
        val before = System.nanoTime()
        runtime.publishAuthority(GuidanceDiagnosticRuntimeCoordinator.Authority(user, 1, 1, false, false, true))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before) < 100)
        assertFalse(runtime.state().authorized)
        assertTrue(finished.await(3, TimeUnit.SECONDS))
        runtime.close(); executor.shutdown(); assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
    }

    private class ManualWorker : Executor {
        val tasks = ArrayDeque<Runnable>()
        var running = false
        override fun execute(command: Runnable) { tasks.add(command) }
        fun runAll() {
            var count = 0
            while (tasks.isNotEmpty()) {
                check(++count < 1_000) { "unbounded retry loop" }
                running = true
                try { tasks.removeFirst().run() } finally { running = false }
            }
        }
    }
    private class MemoryQueue(var worker: ManualWorker) : GuidanceDiagnosticQueue {
        val events = linkedMapOf<String, MutableList<GuidanceDiagnosticEvent>>()
        val purged = mutableListOf<String>()
        val revisions = mutableMapOf<String, Long>()
        var acknowledgements = 0
        var operations = 0
        var failPurge = false
        private fun io() { assertTrue("queue I/O must run on worker", worker.running); operations++ }
        override fun bindPreference(ownerKey: String, revision: Long): Boolean {
            io()
            val discarded = events.containsKey(ownerKey) && revisions[ownerKey] != revision
            if (discarded) events.remove(ownerKey)
            revisions[ownerKey] = revision
            return discarded
        }
        override fun append(ownerKey: String, event: GuidanceDiagnosticEvent, nowMs: Long): Int {
            io(); val own = events.getOrPut(ownerKey) { mutableListOf() }; own.add(event); return own.size
        }
        override fun peek(ownerKey: String, nowMs: Long, limit: Int): List<GuidanceDiagnosticEvent> {
            io(); val own = events[ownerKey].orEmpty(); val first = own.firstOrNull() ?: return emptyList()
            return own.filter { it.sessionId == first.sessionId }.take(limit)
        }
        override fun acknowledge(ownerKey: String, eventIds: Set<String>): Int {
            io(); acknowledgements++; val own = events[ownerKey]!!; own.removeAll { it.id in eventIds }; return own.size
        }
        override fun purge(ownerKey: String) { io(); check(!failPurge); purged.add(ownerKey); events.remove(ownerKey); revisions.remove(ownerKey) }
    }
    private class Fixture(val approved: Boolean = true, val session: GatewayFieldSession = session("alice", 1), sharedQueue: MemoryQueue? = null) {
        val worker = ManualWorker()
        val queue = (sharedQueue ?: MemoryQueue(worker)).also { it.worker = worker }
        var factoryCalls = 0
        var remote = GuidanceDiagnosticPreference(true, true, 1)
        var failUpload = false
        var uploadFailureStatus: Int? = null
        var allowLateAck = false
        var elapsed = 200L
        var wall = 1_000L
        var clockCalls = 0
        var duringUpload: (() -> Unit)? = null
        var duringPost: (() -> Unit)? = null
        var duringClock: (() -> Unit)? = null
        var duringGet: (() -> Unit)? = null
        var beforePreferenceWrite: (() -> Unit)? = null
        var preferenceFailureStatus: Int? = null
        val preferenceWriteActors = mutableListOf<String>()
        var cancelled = 0
        val calls = mutableListOf<String>()
        val uploaded = mutableListOf<GuidanceDiagnosticBatch>()
        private fun request(label: String, action: () -> GuidanceDiagnosticPreference): CancellableNetworkCall<GuidanceDiagnosticPreference> =
            CancellableNetworkCall(executeBlock = { assertTrue(worker.running); calls.add(label); action() }, cancelBlock = { cancelled++ })
        val runtime = GuidanceDiagnosticRuntimeCoordinator(approved, "abcdef0", {
            assertTrue(worker.running); factoryCalls++; queue
        }, object : GuidanceDiagnosticTransport {
            override fun upload(binding: GuidanceDiagnosticBinding, batch: GuidanceDiagnosticBatch) = CancellableNetworkCall(executeBlock = {
                assertTrue(worker.running); duringUpload?.invoke()
                uploadFailureStatus?.let { throw GuidanceDiagnosticHttpFailure(it) }
                if (!allowLateAck && remote.revision != binding.preferenceRevision) throw GuidanceDiagnosticHttpFailure(409)
                if (failUpload) error("offline")
                uploaded.add(batch); GuidanceDiagnosticAck(batch.id, batch.events.size)
            }, cancelBlock = { cancelled++ })
        }, { request("GET") {
            duringGet?.invoke(); preferenceFailureStatus?.let { throw GuidanceDiagnosticHttpFailure(it) }; remote
        } }, { request("POST") { duringPost?.invoke(); remote } }, { user, enabled, revision ->
            request("PUT:$enabled:$revision") {
                preferenceWriteActors.add(user.actorId); beforePreferenceWrite?.invoke()
                if (revision != remote.revision) throw GuidanceDiagnosticHttpFailure(409)
                val nextRevision = if (!remote.present || remote.enabled != enabled) revision + 1 else revision
                GuidanceDiagnosticPreference(true, enabled, nextRevision).also { remote = it }
            }
        }, { clockCalls++; duringClock?.invoke(); wall }, { clockCalls++; elapsed }, worker = worker, ownsWorker = false)
        fun authority(user: GatewayFieldSession = session, gateway: Long = 1, network: Boolean = true, local: Boolean = true) =
            GuidanceDiagnosticRuntimeCoordinator.Authority(user, gateway, user.backendAccountGeneration, true, network, local)
    }
    companion object {
        private fun ownerKey(session: GatewayFieldSession, generation: Long) = GuidanceDiagnosticBinding(session, 1, generation,
            7, java.util.UUID.randomUUID().toString(), "abcdef0").ownerKey
        private fun diagnosticEvent(session: GatewayFieldSession, generation: Long): GuidanceDiagnosticEvent {
            val binding = GuidanceDiagnosticBinding(session, 1, generation, 7, java.util.UUID.randomUUID().toString(), "abcdef0")
            return GuidanceDiagnosticEvent(java.util.UUID.randomUUID().toString(), binding.diagnosticSessionId, binding.buildId,
                1_000, 200, "tts", mapOf("tts_state" to "DONE"))
        }
        // Session cookies are never sent: this fixture exercises the runtime authority fields only.
        private fun session(actor: String, generation: Long, scope: GatewaySessionScope = GatewaySessionScope.GENERAL) =
            GatewayFieldSession.legacyVerified("http://127.0.0.1", actor, "fixture-device", "walksafe_field_session=fixture", Long.MAX_VALUE, scope).also {
                GatewayFieldSession::class.java.getDeclaredField("backendAccountGeneration").apply { isAccessible = true }.set(it, generation)
            }
    }
}
