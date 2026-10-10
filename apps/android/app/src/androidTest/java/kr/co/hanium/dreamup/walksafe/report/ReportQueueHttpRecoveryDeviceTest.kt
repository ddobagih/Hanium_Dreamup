package kr.co.hanium.dreamup.walksafe.report

import android.graphics.Bitmap
import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidKeyStoreAead
import kr.co.hanium.dreamup.walksafe.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real ART/Keystore/disk/HTTP; synthetic authority and loopback receipts, never actual reports.
 * Store recreation is tested, not Android process death. Retry time advances only an injected clock.
 */
class ReportQueueHttpRecoveryDeviceTest {
    @Test fun consentWithdrawalCancelsNativeQueueWhileServerHoldsResponse() {
        Fixture().use { f -> Server().use { server ->
            f.enqueue()
            server.postEntered = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.releasePost = release
            server.dropPostResponse = true
            val coordinator = ReportQueueDrainCoordinator(f.reopen())
            val call = f.start(server, coordinator = coordinator)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<ReportQueueDrainOutcome> { call.execute() }
                assertTrue("Native POST must be waiting on the fixture response", server.postEntered!!.await(10, TimeUnit.SECONDS))
                f.context.set(f.context.get().copy(consentAllowed = false))
                val began = android.os.SystemClock.elapsedRealtime()
                assertTrue(ReportPrivacyConsentSession.withdraw())
                assertTrue(coordinator.onConsentRevoked(RECEIPT))
                // startNext returns a mapped handle; cancellation is owned by the underlying drain.
                // Assert the actual outcome and native unblock, not the wrapper cancellation flag.
                // The server has not replied or closed its socket: cancellation must unblock native HTTP.
                assertEquals(ReportQueueDrainOutcome.CANCELLED, result.get(5, TimeUnit.SECONDS))
                val elapsed = android.os.SystemClock.elapsedRealtime() - began
                assertEquals(1L, release.count)
                assertNull(ReportPrivacyConsentSession.issueUploadPermit(ReportTransferPurpose.EXPLICIT))
                f.inspect(f.reopen()) { assertTrue(it.isEmpty()) }
                assertEquals(1, server.requests.count { it.method == "POST" })
                emit("consent_withdrawal=true native_response_wait_cancelled=true cancellation_ms=$elapsed queue_purged=true new_permit_blocked=true authority=SYNTHETIC main_consent_ui=false")
            } finally {
                call.cancel()
                release.countDown()
                executor.shutdown()
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
            }
        } }
    }

    @Test fun repeatedSameConsentAuthorityDoesNotCancelNativeQueueUpload() {
        Fixture().use { f -> Server().use { server ->
            f.enqueue()
            server.postEntered = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.releasePost = release
            val call = f.start(server)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<ReportQueueDrainOutcome> { call.execute() }
                assertTrue(server.postEntered!!.await(10, TimeUnit.SECONDS))
                repeat(3) {
                    assertFalse("Same authority must not create a new permit generation",
                        ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent())
                }
                assertFalse("Duplicate confirmation must not finish the in-flight request", result.isDone)
                release.countDown()
                assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD, result.get(10, TimeUnit.SECONDS))
                f.inspect(f.reopen()) { assertTrue(it.isEmpty()) }
                assertEquals(1, server.requests.count { it.method == "POST" })
                emit("same_authority_repeated=3 native_upload_preserved=true receipt_accepted=true post_count=1 authority=SYNTHETIC main_consent_ui=false")
            } finally {
                call.cancel()
                release.countDown()
                executor.shutdown()
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
            }
        } }
    }

    @Test fun deletionWaitsForNativeHttpReceiptOwnerAndLateReceiptCannotRestoreFiles() {
        Fixture().use { f -> Server().use { server ->
            f.enqueue()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            val coordinator = ReportQueueDrainCoordinator(f.reopen())
            var held: QueuedReport? = null
            val call = f.start(server, coordinator = coordinator, afterUpload = {
                held = it
                entered.countDown()
                check(release.await(15, TimeUnit.SECONDS))
            })
            try {
                val result = executor.submit<ReportQueueDrainOutcome> { call.execute() }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                assertEquals(1, server.requests.count { it.method == "POST" })
                assertFalse("HTTP completed but its worker still owns plaintext", coordinator.onAccountDeleted())
                val recreated = ReportQueueDrainCoordinator(f.reopen())
                assertFalse("New screen coordinator must see the previous worker", recreated.onAccountDeleted())
                f.inspect(f.reopen()) { assertTrue(it.isEmpty()) }
                assertTrue(requireNotNull(held).payload.imageJpeg().isNotEmpty())
                release.countDown()
                assertEquals(ReportQueueDrainOutcome.CANCELLED, result.get(10, TimeUnit.SECONDS))
                assertThrows(IllegalStateException::class.java) { requireNotNull(held).payload.imageJpeg() }
                assertTrue(coordinator.onAccountDeleted())
                assertTrue(recreated.onAccountDeleted())
                f.inspect(f.reopen()) { assertTrue(it.isEmpty()) }
                emit("S1617-46 native_http=true delayed_callback=INJECTED late_receipt_rejected=true plaintext_released=true files_remain_absent=true")
            } finally {
                release.countDown()
                executor.shutdown()
                assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))
            }
        } }
    }
    /** Host invokes write/read in separate instrumentation processes with the same unique token. */
    @Test fun restartPhasesPreserveQuarantineAndRetryState() {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("queueRestartPhase")
        org.junit.Assume.assumeTrue(phase in listOf("write", "read"))
        val token = requireNotNull(args.getString("queueRestartToken"))
        require(Regex("[a-zA-Z0-9-]{8,64}").matches(token))
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val marker = File(app.cacheDir, "queue-restart-$token.pid")
        try {
            Fixture(token, reopenExisting = phase == "read").use { f -> Server().use { server ->
                if (phase == "write") {
                    f.enqueue()
                    f.enqueue()
                    server.status = 422
                    assertEquals(ReportQueueDrainOutcome.QUARANTINED, f.drain(server))
                    server.status = 429
                    assertEquals(ReportQueueDrainOutcome.RETRY_DEFERRED, f.drain(server))
                    f.inspect(f.reopen()) { rows ->
                        assertEquals(1, rows.count { it.quarantined })
                        assertEquals(1, rows.count { !it.quarantined && it.retryNotBeforeEpochMs == 1_600_000L })
                    }
                    marker.writeText(android.os.Process.myPid().toString())
                    f.keepStateForRestart = true
                    emit("restart_phase=write encrypted_queue_persisted=true")
                } else {
                    assertNotEquals("Must use a different Android process", marker.readText().toInt(), android.os.Process.myPid())
                    f.inspect(f.reopen()) { rows ->
                        assertEquals(2, rows.size)
                        assertEquals(1, rows.count { it.quarantined })
                        assertEquals(1_600_000L, rows.single { !it.quarantined }.retryNotBeforeEpochMs)
                    }
                    assertNull(f.reopen().nextForRecoveryDrain(ACTOR, GENERATION))
                    f.now = 1_600_000L
                    assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD, f.drain(server, f.reopen()))
                    f.inspect(f.reopen()) { rows ->
                        assertEquals(1, rows.size)
                        assertTrue(rows.single().quarantined)
                    }
                    emit("restart_phase=read process_changed=true retry_restored=true quarantine_preserved=true eligible_report_uploaded=true")
                }
            } }
        } finally {
            if (phase == "read") marker.delete()
        }
    }

    @Test fun authenticationErrorsPreservePayloadAndLaterReceiptDeletesOnlyItsItem() {
        for ((status, outcome) in listOf(401 to ReportQueueDrainOutcome.AUTHENTICATION_REQUIRED,
            403 to ReportQueueDrainOutcome.PERMISSION_BLOCKED)) {
            Fixture().use { f -> Server().use { server ->
                val first = f.enqueue()
                val second = f.enqueue()
                server.status = status
                assertEquals(outcome, f.drain(server))
                val reopened = f.reopen()
                f.inspect(reopened) { rows ->
                    assertEquals(2, rows.size)
                    val preserved = rows.single { it.payload.reportId == first }
                    assertFalse(preserved.quarantined)
                    assertEquals(0L, preserved.retryNotBeforeEpochMs)
                }
                // A synthetic successful response is not a real password reauthentication test.
                server.status = 201
                assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD, f.drain(server, reopened))
                f.inspect(f.reopen()) { assertEquals(listOf(second), it.map { row -> row.payload.reportId }) }
                assertEquals(listOf("GET", "POST", "GET", "POST"), server.requests.map { it.method })
                val posts = server.requests.filter { it.method == "POST" }
                assertEquals(posts[0].headers[REPORT_PAYLOAD_SHA256_HEADER], posts[1].headers[REPORT_PAYLOAD_SHA256_HEADER])
                emit("auth_status=$status payload_retained=true later_receipt_deleted_only_owner=true")
            } }
        }
    }

    @Test fun permanentFailureIsQuarantinedAndNextReportStillUploads() {
        Fixture().use { f -> Server().use { server ->
            val rejected = f.enqueue()
            val next = f.enqueue()
            server.status = 422
            assertEquals(ReportQueueDrainOutcome.QUARANTINED, f.drain(server))
            val restored = f.reopen()
            server.status = 201
            assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD, f.drain(server, restored))
            f.inspect(f.reopen()) {
                assertEquals(1, it.size)
                assertEquals(rejected, it.single().payload.reportId)
                assertTrue(it.single().quarantined)
            }
            assertNull(f.reopen().nextForRecoveryDrain(ACTOR, GENERATION))
            assertEquals(listOf(rejected, next), server.requests.filter { it.method == "POST" }.map { it.headers[REPORT_ID_HEADER] })
            emit("permanent_422_quarantined=true next_report_uploaded=true")
        } }
    }

    @Test fun retryAfterSurvivesStoreRecreationWithoutBlockingNextReport() {
        for (status in listOf(429, 503)) Fixture().use { f -> Server().use { server ->
            val delayed = f.enqueue()
            val next = f.enqueue()
            server.status = status
            val sentAt = f.now
            assertEquals(ReportQueueDrainOutcome.RETRY_DEFERRED, f.drain(server))
            val restored = f.reopen()
            f.inspect(restored) {
                assertEquals(sentAt + 600_000L, it.single { row -> row.payload.reportId == delayed }.retryNotBeforeEpochMs)
            }
            server.status = 201
            assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD, f.drain(server, restored))
            assertEquals(next, server.requests.last().headers[REPORT_ID_HEADER])
            f.now = sentAt + 599_999L
            assertNull(f.reopen().nextForRecoveryDrain(ACTOR, GENERATION))
            f.now++
            assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_UPLOAD, f.drain(server, f.reopen()))
            f.inspect(f.reopen()) { assertTrue(it.isEmpty()) }
            emit("retry_status=$status persisted_delay_ms=600000 next_report_not_blocked=true clock=INJECTED")
        } }
    }

    @Test fun lostPostResponseRecoversFromStatusWithoutASecondPost() {
        Fixture().use { f -> Server().use { server ->
            f.enqueue()
            server.dropPostResponse = true
            assertEquals(ReportQueueDrainOutcome.RETRY_DEFERRED, f.drain(server))
            f.inspect(f.reopen()) {
                assertEquals(1, it.size)
                assertFalse(it.single().quarantined)
                f.now = it.single().retryNotBeforeEpochMs
            }
            server.dropPostResponse = false
            assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS, f.drain(server, f.reopen()))
            assertEquals(listOf("GET", "POST", "GET"), server.requests.map { it.method })
            f.inspect(f.reopen()) { assertTrue(it.isEmpty()) }
            emit("post_response_dropped=true restored_by_status=true post_count=1")
        } }
    }

    @Test fun malformedSuccessReceiptsRetainPayloadAndStatusRecoveryAvoidsDuplicatePost() {
        val edits=listOf<Pair<String,(JSONObject)->Unit>>(
            "wrong_report_id" to {it.put("report_id","123e4567-e89b-42d3-a456-426614174099");Unit},
            "wrong_payload_hash" to {it.put("payload_sha256","f".repeat(64));Unit},
            "wrong_byte_count" to {it.put("payload_bytes",it.getLong("payload_bytes")+1);Unit},
            "wrong_marker" to {it.put("marker","unknown");Unit},
            "invalid_persistence_id" to {it.put("persistence_marker","invalid");Unit},
            "extra_field" to {it.put("extra",true);Unit},
            "missing_field" to {it.remove("payload_sha256");Unit})
        for((name,edit) in edits) Fixture().use { f -> Server().use { server ->
            val id=f.enqueue();val second=f.enqueue()
            var hash="";var bytes=0L
            f.inspect(f.reopen()){rows->val row=rows.single{it.payload.reportId==id};hash=row.payload.payloadSha256;bytes=row.payload.payloadBytes}
            server.postReceiptEdit=edit
            assertEquals("Malformed POST must return a visible failure outcome: $name",
                ReportQueueDrainOutcome.RECEIPT_REJECTED,f.drain(server))
            fun assertRetained()=f.inspect(f.reopen()){rows->
                assertEquals(2,rows.size)
                val row=rows.single{it.payload.reportId==id}
                assertEquals(hash,row.payload.payloadSha256);assertEquals(bytes,row.payload.payloadBytes)
                assertFalse(row.quarantined)
            }
            assertRetained()
            server.postReceiptEdit=null;server.statusReceiptEdit=edit
            assertEquals("Malformed GET must also return a visible failure outcome: $name",
                ReportQueueDrainOutcome.RECEIPT_REJECTED,f.drain(server,f.reopen()))
            assertRetained()
            server.statusReceiptEdit=null
            assertEquals(ReportQueueDrainOutcome.DELETED_AFTER_STATUS,f.drain(server,f.reopen()))
            f.inspect(f.reopen()){assertEquals(listOf(second),it.map{row->row.payload.reportId})}
            assertEquals(listOf("GET","POST","GET","GET"),server.requests.map{it.method})
            assertEquals(1,server.requests.count{it.method=="POST"})
            emit("malformed_receipt=$name post_and_status_rejected=true exact_payload_retained=true later_status_deletes_owner_only=true posts=1 authority=SYNTHETIC actual_main_ui_not_tested=true")
        } }
    }

    @Test fun lateReceiptCannotDeleteAfterAccountSessionOrConsentChanges() {
        for (boundary in listOf("account", "session", "consent")) Fixture().use { f -> Server().use { server ->
            val id = f.enqueue()
            server.postEntered = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.releasePost = release
            val executor = Executors.newSingleThreadExecutor()
            try {
                val result = executor.submit<ReportQueueDrainOutcome> { f.drain(server) }
                assertTrue(server.postEntered!!.await(10, TimeUnit.SECONDS))
                val old = f.context.get()
                f.context.set(when (boundary) {
                    "account" -> old.copy(accountGeneration = GENERATION + 1)
                    "session" -> old.copy(sessionGeneration = old.sessionGeneration + 1)
                    else -> old.copy(consentAllowed = false)
                })
                release.countDown()
                assertEquals(ReportQueueDrainOutcome.CANCELLED, result.get(10, TimeUnit.SECONDS))
                f.inspect(f.reopen()) { assertEquals(listOf(id), it.map { row -> row.payload.reportId }) }
                assertEquals(1, server.requests.count { it.method == "POST" })
                emit("late_receipt_boundary=$boundary stale_result_cancelled=true queue_retained=true authority=INJECTED")
            } finally {
                release.countDown()
                executor.shutdown()
                assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
            }
        } }
    }

    private class Fixture(token: String = UUID.randomUUID().toString(), reopenExisting: Boolean = false) : Closeable {
        private val app = InstrumentationRegistry.getInstrumentation().targetContext
        private val root = File(app.noBackupFilesDir.canonicalFile, "report-http-probe-$token")
        private val policy = AeadKeyPolicy("walksafe.instrumentation.queue-http.$token.v", 1, setOf(1))
        private val aead = AndroidKeyStoreAead(policy)
        var keepStateForRestart = false
        var now = 1_000_000L
        private var sequence = 1L
        private val capacity = ApprovedReportQueueCapacityProfile(8, 4096, 32768L, 131072L, 6, 98304L)
        val context = AtomicReference(ReportQueueDrainContext(WalkSessionState.ENDED,
            true, true, true, true, true, true, ACTOR, WALK, RECEIPT, 1L, GENERATION, 1L))
        private val store: AndroidReportQueueStore
        private val image: ByteArray
        init {
            check(if (reopenExisting) root.isDirectory else root.mkdirs())
            store = reopen()
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            image = try { ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }.toByteArray() }
                finally { bitmap.recycle() }
            ReportPrivacyConsentSession.resetForNewEnrollment()
            check(ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent())
        }
        fun reopen() = AndroidReportQueueStore(root, capacity, aead,
            idFactory = { UUID(0L, sequence++) }, nowMillis = { now })
        fun enqueue(): String = requireNotNull(store.enqueue(ACTOR,
            """{"reporter_user_id":"$ACTOR","trigger":"voice","auto_reported":false}""".toByteArray(),
            image, ReportQueuePriority.EXPLICIT, WALK, RECEIPT, GENERATION)).use { it.payload.reportId }
        fun inspect(store: AndroidReportQueueStore, check: (List<QueuedReport>) -> Unit) {
            val rows = store.queuedReports()
            try { check(rows) } finally { rows.forEach { it.close() } }
        }
        fun drain(server: Server, source: AndroidReportQueueStore = store): ReportQueueDrainOutcome {
            return start(server, source).execute()
        }
        fun start(server: Server, source: AndroidReportQueueStore = store,
            coordinator: ReportQueueDrainCoordinator = ReportQueueDrainCoordinator(source),
            afterUpload: ((QueuedReport) -> Unit)? = null): CancellableNetworkCall<ReportQueueDrainOutcome> {
            val origin = requireNotNull(approvedReportQueueGatewayOriginOrNull(BuildConfig.WALKSAFE_REPORT_QUEUE_TEST_ORIGIN))
            val session = GatewayFieldSession.backendAccountDeviceSession(origin,
                BackendAccountDeviceCookieBinding(ACTOR, GENERATION, 1L, "device-http-probe", WALK, Long.MAX_VALUE),
                "walksafe_field_session=v7.test.queue.cookie", Long.MAX_VALUE)
            val confirmation = IntegratedConsentConfirmation(INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
                INTEGRATED_CONSENT_POLICY_VERSION, "501e3ad4-e74f-4433-820f-72ac2fdd42ad", "integrated_consent_request_0001",
                IntegratedConsentItemVersions(), 1L, 1L, IntegratedConsentSelections(),
                "2026-10-06T00:00:00.000Z", "9".repeat(64), RECEIPT, "b".repeat(64))
            val binding = IntegratedConsentNetworkBinding.forTest(IntegratedConsentNetworkTransport.WIFI) { url ->
                // Test-only transport mapping; the product's allowed-origin checks remain unchanged.
                check(url.toString().startsWith(origin + "/"))
                URL(server.baseUrl + url.file).openConnection()
            }
            val native = AndroidReportQueueTransport(AndroidReportUploader(), session, confirmation, binding,
                ReportPrivacyConsentSession::issueUploadPermit)
            val transport = if (afterUpload == null) native else object : ReportQueueTransport by native {
                override fun uploadResultCall(report: QueuedReport): CancellableNetworkCall<ReportQueueUploadResult> {
                    val call = native.uploadResultCall(report)
                    return CancellableNetworkCall(executeBlock = {
                        call.execute().also { afterUpload(report) }
                    }, cancelBlock = call::cancel)
                }
            }
            return requireNotNull(coordinator.startNext(context::get, transport))
        }
        override fun close() {
            ReportPrivacyConsentSession.resetForNewEnrollment()
            image.fill(0)
            if (keepStateForRestart) return
            check(root.deleteRecursively())
            check(aead.destroyKnownVersions())
            KeyStore.getInstance("AndroidKeyStore").apply { load(null); deleteEntry(policy.alias(1) + ".generation") }
        }
    }

    private data class Request(val method: String, val headers: Map<String, String>)
    private class Server : Closeable {
        private val socket = ServerSocket(0)
        val baseUrl = "http://127.0.0.1:${socket.localPort}"
        val requests = Collections.synchronizedList(mutableListOf<Request>())
        @Volatile var status = 201
        @Volatile var postReceiptEdit: ((JSONObject)->Unit)? = null
        @Volatile var statusReceiptEdit: ((JSONObject)->Unit)? = null
        @Volatile var dropPostResponse = false
        @Volatile var postEntered: CountDownLatch? = null
        @Volatile var releasePost: CountDownLatch? = null
        private val persisted = mutableMapOf<String, JSONObject>()
        private val failure = AtomicReference<Throwable?>()
        private val worker = thread(name = "queue-http-native-test", isDaemon = true) {
            try {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    client.use {
                        it.soTimeout = 10_000
                        val request = readRequest(it)
                        requests.add(request)
                        val id = request.headers.getValue(REPORT_ID_HEADER)
                        var code: Int
                        var body: String
                        if (request.method == "GET") {
                            code = if (id in persisted) 200 else 404
                            body = persisted[id]?.let { receipt -> JSONObject().put("persistence_state", "PERSISTED")
                                .put("user_status", "RECEIVED").put("transport_receipt", JSONObject(receipt.toString()).also{statusReceiptEdit?.invoke(it)}).toString() } ?: "{}"
                        } else {
                            check(request.method == "POST")
                            postEntered?.countDown()
                            check(releasePost?.await(10, TimeUnit.SECONDS) != false)
                            code = status
                            body = """{"detail":{"code":"queue_device_test_failure"}}"""
                            if (code in 200..299) {
                                val receipt = JSONObject().put("marker", REPORT_RECEIPT_MARKER).put("report_id", id)
                                    .put("payload_sha256", request.headers.getValue(REPORT_PAYLOAD_SHA256_HEADER))
                                    .put("payload_bytes", request.headers.getValue(REPORT_PAYLOAD_BYTES_HEADER).toLong())
                                    .put("persistence_marker", "123e4567-e89b-42d3-a456-426614174002")
                                persisted[id] = receipt
                                body = JSONObject().put("transport_receipt", JSONObject(receipt.toString()).also{postReceiptEdit?.invoke(it)}).toString()
                                if (dropPostResponse) return@use // Actual socket EOF after synthetic server persistence.
                            }
                        }
                        val bytes = body.toByteArray()
                        it.getOutputStream().apply {
                            write(("HTTP/1.1 $code Fixture\r\nContent-Type: application/json\r\nRetry-After: 600\r\n" +
                                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            write(bytes); flush()
                        }
                    }
                }
            } catch (error: Throwable) { if (!socket.isClosed) failure.set(error) }
        }
        override fun close() {
            releasePost?.countDown()
            socket.close()
            worker.join(15_000L)
            check(!worker.isAlive)
            failure.get()?.let { throw AssertionError("Loopback fixture failed", it) }
        }
        private fun readRequest(socket: Socket): Request {
            val input = socket.getInputStream()
            fun line(): String {
                val out = ByteArrayOutputStream()
                while (true) {
                    val next = input.read()
                    check(next >= 0 && out.size() < 16_384)
                    if (next == 13) { check(input.read() == 10); return out.toString("US-ASCII") }
                    out.write(next)
                }
            }
            val method = line().substringBefore(' ')
            val headers = mutableMapOf<String, String>()
            while (true) {
                val value = line()
                if (value.isEmpty()) break
                headers[value.substringBefore(':').lowercase()] = value.substringAfter(':').trim()
            }
            check("transfer-encoding" !in headers)
            val size = headers["content-length"]?.toInt() ?: 0
            check(size in 0..8192)
            repeat(size) { check(input.read() >= 0) }
            return Request(method, headers)
        }
    }
    private fun emit(message: String) = InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply { putString("stream", "NATIVE_QUEUE $message real_keystore=true external_report=false\n") })
    private companion object {
        const val ACTOR = "device-queue-tester"
        const val GENERATION = 7L
        const val WALK = "123e4567-e89b-42d3-a456-426614174001"
        val RECEIPT = "c".repeat(64)
    }
}
