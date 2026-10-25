package kr.co.hanium.dreamup.walksafe.fieldlog

import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import org.junit.Assert.*
import org.junit.Test

class GuidanceDiagnosticLogTest {
    private fun session(actor: String = "test-actor", scope: GatewaySessionScope = GatewaySessionScope.GENERAL) = GatewayFieldSession.legacyVerified(
        "https://gateway.invalid", actor, "test-device", "walksafe_field_session=diagnostic-test-cookie", 100_000L, scope)
    private fun binding(actor: String = "test-actor", revision: Long = 1) = GuidanceDiagnosticBinding(
        session(actor), 2, 7, revision, UUID.randomUUID().toString(), "abcdef123456")
    private fun event(owner: GuidanceDiagnosticBinding, at: Long = 1_000) = GuidanceDiagnosticEvent(
        UUID.randomUUID().toString(), owner.diagnosticSessionId, owner.buildId, at, 800,
        "direction_grace", mapOf("mode" to "GRACE_RETAINED", "age_ms" to 300L))
    private class MemoryQueue : GuidanceDiagnosticQueue {
        val events = mutableMapOf<String, MutableList<GuidanceDiagnosticEvent>>()
        private val revisions = mutableMapOf<String, Long>()
        override fun bindPreference(ownerKey: String, revision: Long): Boolean {
            val old = revisions.put(ownerKey, revision)
            val dropped = old != null && old != revision
            if (dropped) events.remove(ownerKey)
            return dropped
        }
        override fun append(ownerKey: String, event: GuidanceDiagnosticEvent, nowMs: Long): Int = events.getOrPut(ownerKey) { mutableListOf() }.run { add(event); size }
        override fun peek(ownerKey: String, nowMs: Long, limit: Int) = events[ownerKey].orEmpty().take(limit)
        override fun acknowledge(ownerKey: String, eventIds: Set<String>): Int = events[ownerKey].orEmpty().filter { it.id !in eventIds }.toMutableList().also { events[ownerKey] = it }.size
        override fun purge(ownerKey: String) { events.remove(ownerKey) }
    }
    private class Fixture {
        var current: GuidanceDiagnosticBinding? = null
        var allowed = false
        var networkFails = false
        var malformedAck = false
        var beforeAck: (() -> Unit)? = null
        var executeCount = 0
        var cancelCount = 0
        var probes = 0
        var now = 1_000L
        val tasks = ArrayDeque<Runnable>()
        val queue = MemoryQueue()
        val admission = object : GuidanceDiagnosticAdmission {
            override fun currentBinding(): GuidanceDiagnosticBinding? { probes++; return current }
            override fun sameBindingCurrent(binding: GuidanceDiagnosticBinding): Boolean { probes++; return binding === current }
            override fun uploadAllowed(binding: GuidanceDiagnosticBinding): Boolean { probes++; return allowed && binding === current }
        }
        val transport = object : GuidanceDiagnosticTransport {
            override fun upload(binding: GuidanceDiagnosticBinding, batch: GuidanceDiagnosticBatch) = CancellableNetworkCall(
                executeBlock = {
                    executeCount++
                    if (networkFails) error("isolated network failure")
                    beforeAck?.invoke()
                    GuidanceDiagnosticAck(if (malformedAck) UUID.randomUUID().toString() else batch.id, batch.events.size)
                }, cancelBlock = { cancelCount++ }, cancelledFailure = { IllegalStateException("cancelled") }, rejectLateResult = true)
        }
        val log = GuidanceDiagnosticLog(admission, queue, transport, Executor { tasks.add(it) },
            { probes++; now }, { probes++; 800L })
        fun run() { tasks.removeFirst().run() }
    }

    @Test fun constructorAndStateDoNotReadAdmissionClockStorageOrStartNetwork() {
        val f = Fixture()
        assertEquals(0, f.probes); assertTrue(f.tasks.isEmpty()); assertEquals(0, f.executeCount)
        assertEquals(GuidanceDiagnosticState(0, false, null), f.log.state())
        assertEquals(0, f.probes)
    }
    @Test fun disabledOrMissingScopeCannotRecordOrUpload() {
        val f = Fixture()
        assertFalse(f.log.record("tts")); assertFalse(f.log.drainIfAdmitted())
        assertTrue(f.queue.events.isEmpty()); assertEquals(0, f.executeCount)
    }
    @Test fun privateKeysAndUnknownEventAreExcludedButOriginalMeasurementsSurvive() {
        val f = Fixture(); val b = binding(); f.current = b
        assertTrue(f.log.record("tts", mapOf("latitude" to 37.1, "address" to "private", "token" to "private", "message" to "private", "tts_state" to "START", "age_ms" to 300L, "accuracy_m" to 5.0, "rate_hz" to Double.NaN)))
        val e = f.queue.events[b.ownerKey]!!.single()
        assertEquals(mapOf("tts_state" to "START", "age_ms" to 300L, "accuracy_m" to 5.0), e.fields)
        assertEquals(1_000L, e.recordedAtMs); assertEquals(800L, e.elapsedRealtimeMs)
        assertFalse(f.log.record("free_text_event", mapOf("message" to "private")))
        assertEquals(1, f.queue.events[b.ownerKey]!!.size)
    }
    @Test fun offlineQueueSurvivesFailureAndRetriesWithoutDuplicateAck() {
        val f = Fixture(); val b = binding(); f.current = b
        assertTrue(f.log.record("route_request", mapOf("stage" to "request")))
        assertFalse(f.log.drainIfAdmitted()); assertEquals(1, f.queue.events[b.ownerKey]!!.size)
        f.allowed = true; f.networkFails = true
        assertTrue(f.log.drainIfAdmitted()); assertFalse(f.log.drainIfAdmitted()); f.run()
        assertEquals("upload_failed", f.log.state().outcome); assertEquals(1, f.queue.events[b.ownerKey]!!.size)
        f.networkFails = false; assertTrue(f.log.drainIfAdmitted()); f.run()
        assertEquals(2, f.executeCount); assertEquals(0, f.log.state().queued)
        assertFalse(f.log.drainIfAdmitted())
    }
    @Test fun actorChangeBeforeWorkerPreventsAnyTransportExecution() {
        val f = Fixture(); val b = binding(); f.current = b; f.allowed = true
        assertTrue(f.log.record("navigation_start")); assertTrue(f.log.drainIfAdmitted())
        f.current = binding("another-actor"); f.run()
        assertEquals(0, f.executeCount); assertEquals(1, f.queue.events[b.ownerKey]!!.size)
    }
    @Test fun actorOrPreferenceRevisionChangeAfterNetworkCannotAcknowledgeOldQueue() {
        val f = Fixture(); val b = binding(); f.current = b; f.allowed = true
        assertTrue(f.log.record("tts")); assertTrue(f.log.drainIfAdmitted())
        f.beforeAck = { f.current = binding(revision = 2) }; f.run()
        assertEquals(1, f.executeCount); assertEquals(1, f.queue.events[b.ownerKey]!!.size)
    }
    @Test fun deletionCancelsPendingAndPurgeCannotTouchOtherActor() {
        val f = Fixture(); val b = binding(); val other = binding("another-actor")
        f.current = other; assertTrue(f.log.record("tts")); f.current = b; f.allowed = true
        assertTrue(f.log.record("tts")); assertTrue(f.log.drainIfAdmitted())
        f.log.blockForDeletion(); assertEquals(1, f.cancelCount); f.run()
        assertEquals(0, f.executeCount); assertFalse(f.log.record("tts")); assertFalse(f.log.drainIfAdmitted())
        assertTrue(f.log.purge(b.ownerKey)); assertFalse(f.queue.events.containsKey(b.ownerKey))
        assertEquals(1, f.queue.events[other.ownerKey]!!.size)
    }
    @Test fun wrongAckCannotEraseStoredEvents() {
        val f = Fixture(); val b = binding(); f.current = b; f.allowed = true; f.malformedAck = true
        assertTrue(f.log.record("tts")); assertTrue(f.log.drainIfAdmitted()); f.run()
        assertEquals("invalid_ack", f.log.state().outcome); assertEquals(1, f.queue.events[b.ownerKey]!!.size)
    }
    @Test fun realAeadQueueReopensEncryptedAndExpiresAfterThirtyDays() {
        val root = Files.createTempDirectory("diagnostic-queue-test").toFile()
        try {
            val aead = TestFieldAead().aead
            val queue = EncryptedGuidanceDiagnosticQueue(File(root, "queue"), aead) { true }
            assertFalse(File(root, "queue").exists())
            val b = binding(); val e = event(b)
            assertFalse(queue.bindPreference(b.ownerKey, b.preferenceRevision))
            assertEquals(1, queue.append(b.ownerKey, e, 1_000))
            val bytes = File(root, "queue/${b.ownerKey}.aead").readText()
            assertFalse(bytes.contains("GRACE_RETAINED")); assertFalse(bytes.contains(b.diagnosticSessionId))
            val reopened = EncryptedGuidanceDiagnosticQueue(File(root, "queue"), aead) { true }
            assertFalse(reopened.bindPreference(b.ownerKey, b.preferenceRevision))
            assertEquals(listOf(e), reopened.peek(b.ownerKey, 1_001, 100))
            assertTrue(reopened.peek(b.ownerKey, 1_001 + GuidanceDiagnosticPolicy.QUEUE_RETENTION_MS, 100).isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun corruptCiphertextAndLostKeyFailClosedWithoutPlaintextFallback() {
        val root = Files.createTempDirectory("diagnostic-corrupt-test").toFile()
        try {
            val keys = TestFieldAead(); val b = binding()
            val queue = EncryptedGuidanceDiagnosticQueue(root, keys.aead) { true }
            queue.bindPreference(b.ownerKey, b.preferenceRevision)
            queue.append(b.ownerKey, event(b), 1_000)
            keys.deleteKey()
            assertThrows(IllegalStateException::class.java) { queue.peek(b.ownerKey, 1_001, 100) }
            File(root, "${b.ownerKey}.aead").writeText("unencrypted-private-input")
            assertThrows(IllegalStateException::class.java) { queue.peek(b.ownerKey, 1_001, 100) }
        } finally { root.deleteRecursively() }
    }
    @Test fun httpConstructionAndCancellationDoNotQueryCookieClockOrConnect() {
        var clocks = 0; var opened = 0
        val transport = GuidanceDiagnosticHttpTransport({ clocks++; 1_000L }) { opened++; error("not expected") }
        val b = binding()
        val call = transport.upload(b, GuidanceDiagnosticBatch(UUID.randomUUID().toString(), listOf(event(b))))
        assertEquals(0, clocks); assertEquals(0, opened)
        call.cancel(); assertThrows(IllegalStateException::class.java) { call.execute() }
        assertEquals(0, clocks); assertEquals(0, opened)
    }
    @Test fun httpUsesCurrentSessionCookieAndNeverForwardsActorTokenOrLocationHeaders() {
        val b = binding(); val batch = GuidanceDiagnosticBatch(UUID.randomUUID().toString(), listOf(event(b)))
        lateinit var channel: TestConnection
        val transport = GuidanceDiagnosticHttpTransport({ 1_000 }) { url -> TestConnection(url, """{"batch_id":"${batch.id}","accepted":1}""").also { channel = it } }
        assertEquals(GuidanceDiagnosticAck(batch.id, 1), transport.upload(b, batch).execute())
        assertEquals("https://gateway.invalid/api/field-diagnostics/sessions/${b.diagnosticSessionId}/batches", channel.url.toString())
        assertEquals(b.session.requestHeaders(1_000)["Cookie"], channel.getRequestProperty("Cookie"))
        assertNull(channel.getRequestProperty("x-walksafe-actor-id")); assertNull(channel.getRequestProperty("x-walksafe-admin-token"))
        assertFalse(channel.payload.toString().contains("test-actor")); assertFalse(channel.payload.toString().contains("diagnostic-test-cookie"))
        assertTrue(channel.disconnected)
    }
    @Test fun recoveryScopedOrRetiredSessionCannotReachNetwork() {
        var opened = 0
        val transport = GuidanceDiagnosticHttpTransport({ 1_000 }) { opened++; error("not expected") }
        assertThrows(IllegalStateException::class.java) { transport.preference(session(scope = GatewaySessionScope.ACCOUNT_DELETION_RECOVERY)).execute() }
        val retired = session(); retired.invalidate()
        assertThrows(IllegalStateException::class.java) { transport.preference(retired).execute() }
        assertEquals(0, opened)
    }
    @Test fun persistentPreferenceSameRevisionPreservesQueueByteForByteAfterRestart() {
        val root = Files.createTempDirectory("diagnostic-revision-same").toFile()
        try {
            val keys = TestFieldAead(); val b = binding()
            val queue = EncryptedGuidanceDiagnosticQueue(root, keys.aead) { true }
            assertFalse(queue.bindPreference(b.ownerKey, 1))
            val original = event(b); queue.append(b.ownerKey, original, 1_000)
            val before = File(root, "${b.ownerKey}.aead").readBytes()
            val restarted = EncryptedGuidanceDiagnosticQueue(root, keys.aead) { true }
            assertFalse(restarted.bindPreference(b.ownerKey, 1))
            assertArrayEquals(before, File(root, "${b.ownerKey}.aead").readBytes())
            assertEquals(listOf(original), restarted.peek(b.ownerKey, 1_001, 100))
        } finally { root.deleteRecursively() }
    }
    @Test fun remoteOffThenOnRevisionAfterRestartCannotResurrectOriginalQueue() {
        val root = Files.createTempDirectory("diagnostic-revision-new").toFile()
        try {
            val keys = TestFieldAead(); val b = binding()
            val queue = EncryptedGuidanceDiagnosticQueue(root, keys.aead) { true }
            queue.bindPreference(b.ownerKey, 1); val old = event(b); queue.append(b.ownerKey, old, 1_000)
            // Remote OFF revision2 and later explicit ON revision3: this process never saw OFF.
            val restarted = EncryptedGuidanceDiagnosticQueue(root, keys.aead) { true }
            assertTrue(restarted.bindPreference(b.ownerKey, 3))
            assertTrue(restarted.peek(b.ownerKey, 1_001, 100).isEmpty())
            val fresh = event(b, 1_001); restarted.append(b.ownerKey, fresh, 1_001)
            val afterAck = EncryptedGuidanceDiagnosticQueue(root, keys.aead) { true }
            assertFalse(afterAck.bindPreference(b.ownerKey, 3))
            assertEquals(listOf(fresh), afterAck.peek(b.ownerKey, 1_002, 100))
            assertEquals(0, afterAck.acknowledge(b.ownerKey, setOf(fresh.id)))
            val finalQueue = EncryptedGuidanceDiagnosticQueue(root, keys.aead) { true }
            assertFalse(finalQueue.bindPreference(b.ownerKey, 3))
            assertTrue(finalQueue.peek(b.ownerKey, 1_003, 100).isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun authenticatedLegacyQueueWithoutPreferenceReceiptIsDiscarded() {
        val root = Files.createTempDirectory("diagnostic-revision-legacy").toFile()
        try {
            val keys = TestFieldAead(); val b = binding(); val e = event(b)
            val document = org.json.JSONObject().put("schema", "guidance-diagnostic.queue.v1")
                .put("events", org.json.JSONArray().put(EncryptedGuidanceDiagnosticQueue.toJson(e)))
            val sealed = keys.aead.seal(document.toString().toByteArray(),
                "walksafe.guidance-diagnostic.queue.v1|owner=${b.ownerKey}".toByteArray(),
                kr.co.hanium.dreamup.walksafe.security.AeadLimits(1_048_576, 1_048_592, 1_500_000)) as kr.co.hanium.dreamup.walksafe.security.AeadSealResult.Sealed
            File(root, "${b.ownerKey}.aead").writeText(sealed.envelope)
            val restarted = EncryptedGuidanceDiagnosticQueue(root, keys.aead) { true }
            assertTrue(restarted.bindPreference(b.ownerKey, 3))
            assertTrue(restarted.peek(b.ownerKey, 1_001, 100).isEmpty())
            assertFalse(restarted.bindPreference(b.ownerKey, 3))
        } finally { root.deleteRecursively() }
    }
    private class TestConnection(url: URL, private val response: String) : HttpURLConnection(url) {
        val payload = ByteArrayOutputStream(); var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getOutputStream() = payload
        override fun getResponseCode() = 200
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray())
    }
}
