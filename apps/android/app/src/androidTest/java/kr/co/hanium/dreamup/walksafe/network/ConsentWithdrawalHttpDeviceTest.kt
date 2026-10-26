package kr.co.hanium.dreamup.walksafe.network

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Native HTTP + real consent state machine; synthetic authority and loopback peer only.
 * No real account, consent preference, Main callback, or durable journal is modified. */
class ConsentWithdrawalHttpDeviceTest {
    private val raw = IntegratedConsentItem.RAW_SOURCE_COLLECTION
    private fun report(value:String) = InstrumentationRegistry.getInstrumentation().sendStatus(
        0, Bundle().apply { putString("stream", "CONSENT_DEVICE $value\n") })
    private fun session(base:String) = GatewayFieldSession.verified(base,
        "consent-device-fixture", "walksafe_field_session=v2.test.session.cookie", Long.MAX_VALUE)
    private fun confirmation(revision:Long=1, receipt:String="b".repeat(64),
        request:String="consent_device_initial_0001", selections:IntegratedConsentSelections=IntegratedConsentSelections(true,true,true,true)) =
        IntegratedConsentConfirmation(INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
            INTEGRATED_CONSENT_POLICY_VERSION, INSTALLATION, request, IntegratedConsentItemVersions(),
            revision, revision, selections, "2026-10-09T00:00:00.000Z", "a".repeat(64), receipt, SECRET)
    private fun body(c:IntegratedConsentConfirmation):String = JSONObject()
        .put("schema_version",c.schemaVersion).put("current",true).put("installation_id",c.installationId)
        .put("request_id",c.requestId).put("policy_version",c.policyVersion).put("item_versions",JSONObject(VERSIONS))
        .put("client_revision",c.clientRevision).put("revision",c.revision).put("selections",choices(c.selections))
        .put("confirmed_at",c.confirmedAt).put("gateway_audit_record_sha256",c.gatewayAuditRecordSha256)
        .put("backend_consent_receipt_sha256",c.backendConsentReceiptSha256).toString()
    private fun choices(s:IntegratedConsentSelections)=JSONObject()
        .put("raw_source_collection",s.rawSourceCollection).put("automatic_reporting",s.automaticReporting)
        .put("mobile_network_transfer",s.mobileNetworkTransfer).put("training_reuse",s.trainingReuse)
    private fun mutation(previous:IntegratedConsentConfirmation, desired:IntegratedConsentSelections)=
        PendingIntegratedConsentMutation(INSTALLATION,"a".repeat(64),"consent_device_withdraw_0002",
            INTEGRATED_CONSENT_POLICY_VERSION,previous.clientRevision+1,previous.revision,
            previous.backendConsentReceiptSha256,desired,setOf(raw),1000L)
    private fun read(client:AndroidIntegratedConsentClient,server:NativeServer)=checkNotNull(
        client.fetchCurrentCall(server.base,session(server.base),INSTALLATION,SECRET).execute())
    private fun save(client:AndroidIntegratedConsentClient,server:NativeServer,m:PendingIntegratedConsentMutation)=checkNotNull(
        client.saveCall(server.base,session(server.base),INSTALLATION,SECRET,m.requestId,m.clientRevision,
            m.desiredSelections,m.expectedPreviousBackendReceiptSha256).execute())

    @Test fun orphanWithdrawalUsesFreshHttpReceiptAndRequiresExactReply() {
        val original=confirmation()
        val fresh=confirmation(2,"c".repeat(64))
        val expected=mutation(fresh,fresh.selections.copy(rawSourceCollection=false))
        val acknowledged=confirmation(3,"d".repeat(64),expected.requestId,expected.desiredSelections)
        NativeServer(listOf(200 to body(fresh),201 to body(acknowledged.copy(requestId="consent_wrong_reply_0003")),
            201 to body(acknowledged))).use { server ->
            val state=IntegratedConsentSession();assertTrue(state.accept(original));assertTrue(state.withdrawImmediately(raw))
            state.invalidate();assertNull(state.withdrawalRecoverySelectionsOrNull());assertFalse(state.isAllowed(raw))
            val client=AndroidIntegratedConsentClient();val received=read(client,server)
            assertEquals(IntegratedConsentApplyResult.APPLIED,state.applyConfirmation(received))
            assertFalse(state.isAllowed(raw));assertEquals(setOf(raw),state.localWithdrawalItems())
            val pending=mutation(received,checkNotNull(state.withdrawalRecoverySelectionsOrNull()))
            assertEquals(expected,pending);assertTrue(state.restorePendingMutation(pending,retry=true))
            assertFalse(state.applyExactPendingConfirmation(save(client,server,pending)))
            assertEquals(pending,state.pendingMutationOrNull());assertFalse(state.isAllowed(raw))
            assertTrue(state.applyExactPendingConfirmation(save(client,server,pending)))
            assertEquals(PurposeConsentSyncState.CONFIRMED_DENIED,state.status(raw))
            assertTrue(state.localWithdrawalItems().isEmpty());assertTrue(state.isAllowed(IntegratedConsentItem.AUTOMATIC_REPORTING))
            server.finished();val sent=JSONObject(server.bodies[1])
            assertEquals(fresh.backendConsentReceiptSha256,sent.getString("expected_previous_backend_receipt_sha256"))
            assertFalse(sent.getJSONObject("selections").getBoolean("raw_source_collection"))
            assertEquals(server.bodies[1],server.bodies[2]);assertTrue(server.headers[1].startsWith("PUT "))
            report("orphan_fresh_receipt=true stale_grant_blocked=true wrong_request_rejected=true exact_denial_confirmed=true same_retry_bytes=true")
        }
    }

    @Test fun httpConflictPreservesWithdrawalAndDoesNotExpandOtherChoices() {
        val original=confirmation().copy(selections=IntegratedConsentSelections(true,true,true,false))
        val rejected=mutation(original,original.selections.copy(rawSourceCollection=false,trainingReuse=true))
        val backendChoices=original.selections.copy(automaticReporting=false)
        val freshReceipt="e".repeat(64)
        val bootstrap=JSONObject().put("schema_version","walksafe.integrated-consent-bootstrap.v1")
            .put("status","READY").put("source","CURRENT_CONSENT").put("installation_id",INSTALLATION)
            .put("policy_version",INTEGRATED_CONSENT_POLICY_VERSION).put("item_versions",JSONObject(VERSIONS))
            .put("client_revision_floor",1).put("selections",choices(backendChoices))
            .put("source_receipt_sha256",freshReceipt).put("expected_previous_backend_receipt_sha256",freshReceipt)
        NativeServer(listOf(409 to "{\"detail\":{\"code\":\"integrated_consent_previous_receipt_conflict\"}}",
            200 to bootstrap.toString())).use { server ->
            val state=IntegratedConsentSession();assertTrue(state.accept(original))
            assertTrue(state.restorePendingMutation(rejected,retry=true));val client=AndroidIntegratedConsentClient()
            assertEquals(409,assertThrows(IntegratedConsentHttpException::class.java){save(client,server,rejected)}.statusCode)
            assertEquals(rejected,state.pendingMutationOrNull());assertFalse(state.isAllowed(raw))
            state.invalidateBackendAuthority()
            val latest=client.fetchBootstrapCall(server.base,session(server.base),INSTALLATION).execute()
            val successor=rejected.successorAfterRejectedCas(checkNotNull(latest.selections),
                checkNotNull(latest.expectedPreviousBackendReceiptSha256),latest.clientRevisionFloor,2L,rejected.withdrawalItems,2000L)
            // Check candidate only: no pretend persistence or Main dispatch success.
            assertFalse(successor.desiredSelections.rawSourceCollection)
            assertFalse(successor.desiredSelections.automaticReporting)
            assertFalse(successor.desiredSelections.trainingReuse)
            assertEquals(freshReceipt,successor.expectedPreviousBackendReceiptSha256)
            assertEquals(rejected,state.pendingMutationOrNull());assertFalse(state.isAllowed(raw))
            server.finished();assertEquals(2,server.bodies.size)
            report("native_http_conflict=409 original_withdrawal_retained=true fresh_cas_candidate_nonexpanding=true not_persisted_or_sent=true")
        }
    }

    @Test fun malformedNativeConfirmationNeverReplacesWithdrawal() {
        val mutations=listOf("installation_id" to "other_installation_0001", "confirmed_at" to "not-a-time",
            "revision" to -1, "backend_consent_receipt_sha256" to "invalid")
        for((key,value) in mutations) {
            val state=IntegratedConsentSession();assertTrue(state.accept(confirmation()));state.withdrawImmediately(raw)
            val before=state.cachedConfirmationOrNull()
            NativeServer(listOf(200 to JSONObject(body(confirmation(2))).put(key,value).toString())).use { server ->
                assertThrows(IntegratedConsentProtocolException::class.java){read(AndroidIntegratedConsentClient(),server)}
                server.finished();assertEquals(before,state.cachedConfirmationOrNull());assertFalse(state.isAllowed(raw))
                assertEquals(setOf(raw),state.localWithdrawalItems())
            }
            report("invalid_field=$key protocol_rejected=true withdrawal_retained=true")
        }
    }

    @Test fun cancelledInFlightWithdrawalDoesNotAcceptDelayedSuccess() {
        val original=confirmation()
        val pending=mutation(original,original.selections.copy(rawSourceCollection=false))
        val response=confirmation(2,"d".repeat(64),pending.requestId,pending.desiredSelections)
        val gate=CountDownLatch(1)
        NativeServer(listOf(201 to body(response)),responseGate=gate).use { server ->
            val state=IntegratedConsentSession();assertTrue(state.accept(original))
            assertTrue(state.restorePendingMutation(pending,retry=true))
            val call=AndroidIntegratedConsentClient().saveCall(server.base,session(server.base),INSTALLATION,SECRET,
                pending.requestId,pending.clientRevision,pending.desiredSelections,pending.expectedPreviousBackendReceiptSha256)
            val worker=Executors.newSingleThreadExecutor()
            try {
                val result=worker.submit<Throwable?> {
                    try { val reply=checkNotNull(call.execute());state.applyExactPendingConfirmation(reply);null }
                    catch(t:Exception){t}
                }
                assertTrue("Actual request must reach the peer before cancellation",server.received.await(5,TimeUnit.SECONDS))
                call.cancel();assertTrue(call.isCancelled());gate.countDown()
                val failure=result.get(5,TimeUnit.SECONDS)
                assertTrue("Cancellation must produce a cancellation exception, not a parse or fixture error",
                    failure is java.util.concurrent.CancellationException)
                assertEquals(pending,state.pendingMutationOrNull());assertFalse(state.isAllowed(raw))
                assertEquals(PurposeConsentSyncState.WITHDRAWAL_RETRY,state.status(raw))
                // A disconnect may make the peer write fail. Only this expected native I/O failure is accepted.
                try { server.finished() } catch(e:java.util.concurrent.ExecutionException) {
                    assertTrue(e.cause is java.io.IOException)
                }
                report("request_received_before_cancel=true delayed_success_not_applied=true withdrawal_retry_preserved=true")
            } finally {gate.countDown();call.cancel();worker.shutdownNow()}
        }
    }

    private fun bootstrapBody() = JSONObject().put("schema_version", "walksafe.integrated-consent-bootstrap.v1")
        .put("status", "READY").put("source", "CURRENT_CONSENT").put("installation_id", INSTALLATION)
        .put("policy_version", INTEGRATED_CONSENT_POLICY_VERSION).put("item_versions", JSONObject(VERSIONS))
        .put("client_revision_floor", 1).put("selections", choices(confirmation().selections))
        .put("source_receipt_sha256", "b".repeat(64)).put("expected_previous_backend_receipt_sha256", "b".repeat(64)).toString()

    @Test fun allConsentEntryPointsRejectAmbiguousDocumentsAndRetainLocalWithdrawal() {
        val accepted = mutableListOf<String>()
        for (entry in listOf("bootstrap", "current", "save")) {
            val valid = if (entry == "bootstrap") bootstrapBody() else body(confirmation(2))
            val malformed = listOf(valid + " {}", valid + "junk",
                valid.replaceFirst("{", "{\"installation_id\":\"other\","),
                valid.replaceFirst("{", "{\"\\u0069nstallation_id\":\"other\","),
                valid.replace("\"installation_id\"", "installation_id"),
                valid.replace("\"installation_id\"", "'installation_id'"), valid.dropLast(1) + ",}")
            for ((index, text) in malformed.withIndex()) NativeServer(listOf(200 to text)).use { server ->
                val state = IntegratedConsentSession(); assertTrue(state.accept(confirmation())); assertTrue(state.withdrawImmediately(raw))
                val before = state.cachedConfirmationOrNull()
                val client = AndroidIntegratedConsentClient()
                val result = runCatching { when (entry) {
                    "bootstrap" -> client.fetchBootstrapCall(server.base, session(server.base), INSTALLATION).execute()
                    "current" -> read(client, server)
                    else -> save(client, server, mutation(confirmation(), confirmation().selections.copy(rawSourceCollection=false)))
                } }
                if (result.isSuccess) accepted += "$entry:$index"
                else assertTrue(result.exceptionOrNull() is IntegratedConsentProtocolException)
                server.finished(); assertEquals(before, state.cachedConfirmationOrNull()); assertFalse(state.isAllowed(raw))
            }
        }
        report("ambiguous_document_cases=21 unexpectedly_accepted=$accepted local_withdrawal_retained=true")
        assertEquals(emptyList<String>(), accepted)
    }

    @Test fun consentErrorMetadataRejectsAmbiguousDocuments() {
        val valid = "{\"detail\":{\"code\":\"integrated_consent_policy_version_mismatch\",\"required_policy_version\":\"next-policy\"}}"
        val malformed = listOf(valid + " {}", valid + "junk", valid.replaceFirst("{", "{\"detail\":{},"),
            valid.replaceFirst("{", "{\"\\u0064etail\":{},"), valid.replace("\"detail\"", "detail"),
            valid.replace("\"detail\"", "'detail'"), valid.dropLast(1) + ",}")
        val actionable = mutableListOf<Int>()
        for ((index, text) in malformed.withIndex()) NativeServer(listOf(409 to text)).use { server ->
            val error = assertThrows(IntegratedConsentHttpException::class.java) { read(AndroidIntegratedConsentClient(), server) }
            if (error.serverCode != null || error.requiredPolicyVersion != null) actionable += index
            assertEquals(409, error.statusCode); server.finished()
        }
        report("error_document_cases=7 unexpectedly_actionable=$actionable")
        assertEquals(emptyList<Int>(), actionable)
    }

    @Test fun consentHttpFramingIsStrictForBootstrapCurrentAndErrorResponses() {
        val accepted = mutableListOf<String>()
        for (entry in listOf("bootstrap", "current")) for (status in listOf(200, 409)) {
            val text = if (status == 409) "{\"detail\":{\"code\":\"integrated_consent_previous_receipt_conflict\"}}"
                else if (entry == "bootstrap") bootstrapBody() else body(confirmation())
            val bytes = text.toByteArray()
            val malformed = listOf(
                "short" to ("Content-Length: ${bytes.size + 8}\r\n" to bytes),
                "duplicate" to ("Content-Length: ${bytes.size}\r\nContent-Length: ${bytes.size}\r\n" to bytes),
                "conflicting" to ("Content-Length: ${bytes.size}\r\nTransfer-Encoding: chunked\r\n" to chunk(bytes)),
                "utf8" to ("" to (bytes.copyOfRange(0, bytes.size-1) + ",\"ignored\":\"".toByteArray() + byteArrayOf(0xc3.toByte(),0x28) + "\"}".toByteArray()))
            )
            for ((name, wire) in malformed) NativeServer(listOf(status to text), wireResponse = { _, _ -> response(status, wire.first, wire.second) }).use { server ->
                val client = AndroidIntegratedConsentClient()
                val result = runCatching { if (entry == "bootstrap") client.fetchBootstrapCall(server.base,session(server.base),INSTALLATION).execute() else read(client,server) }
                if (result.exceptionOrNull() !is NetworkResponseProtocolException) accepted += "$entry:$status:$name"
                server.finished()
            }
        }
        report("strict_wire_cases=16 missing_transport_rejection=$accepted")
        assertEquals(emptyList<String>(), accepted)
    }

    @Test fun evidenceKeyRegistrationCannotAcceptAnIncomplete204Response() {
        NativeServer(listOf(200 to body(confirmation()), 204 to ""), wireResponse = { code, text ->
            response(code, "Content-Length: ${if (code == 204) 1 else text.toByteArray().size}\r\n", text.toByteArray())
        }).use { server ->
            val client = AndroidIntegratedConsentClient { "synthetic-public-key-transport-only" }
            assertThrows(NetworkResponseProtocolException::class.java) { read(client, server) }
            server.finished(); assertEquals(2,server.headers.size)
        }
    }

    @Test fun validConsentFramingAndEvidenceKeyRegistrationRemainUsable() {
        for (entry in listOf("bootstrap", "current", "save")) for (framing in listOf("length", "chunked", "close")) {
            val pending = mutation(confirmation(), confirmation().selections.copy(rawSourceCollection = false))
            val expected = if (entry == "save") confirmation(2, request = pending.requestId, selections = pending.desiredSelections) else confirmation()
            val text = if (entry == "bootstrap") bootstrapBody() else body(expected)
            NativeServer(listOf(200 to text), wireResponse = { code, body ->
                val bytes = body.toByteArray()
                when (framing) {
                    "length" -> response(code, "Content-Length: ${bytes.size}\r\n", bytes)
                    "chunked" -> response(code, "Transfer-Encoding: chunked\r\n", chunk(bytes))
                    else -> response(code, "", bytes)
                }
            }).use { server ->
                val client = AndroidIntegratedConsentClient()
                when (entry) {
                    "bootstrap" -> assertNotNull(client.fetchBootstrapCall(server.base, session(server.base), INSTALLATION).execute())
                    "current" -> assertEquals(confirmation(), read(client, server))
                    else -> assertEquals(expected, save(client, server, pending))
                }
                server.finished()
                assertTrue(server.headers.single().contains("Accept-Encoding: identity", true))
            }
        }
        NativeServer(listOf(200 to body(confirmation()), 204 to "")).use { server ->
            assertEquals(confirmation(), read(AndroidIntegratedConsentClient { "synthetic-public-key-transport-only" }, server))
            server.finished()
            assertEquals(2, server.headers.size)
            assertTrue(server.headers.last().startsWith("PUT /privacy/rights?control=installation-evidence-key "))
            assertTrue(server.headers.all { it.contains("Accept-Encoding: identity", true) })
        }
        report("valid_document_and_framing_cases=9 valid_registration_204=true")
    }

    @Test fun cancellationDuringEvidenceKeyRegistrationCannotPublishConsent() {
        val registrationReceived = CountDownLatch(1)
        val release = CountDownLatch(1)
        NativeServer(listOf(200 to body(confirmation()), 204 to ""), wireResponse = { code, text ->
            if (code == 204) {
                registrationReceived.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            response(code, "Content-Length: ${text.toByteArray().size}\r\n", text.toByteArray())
        }).use { server ->
            val state = IntegratedConsentSession()
            assertTrue(state.accept(confirmation())); assertTrue(state.withdrawImmediately(raw))
            val call = AndroidIntegratedConsentClient { "synthetic-public-key-transport-only" }
                .fetchCurrentCall(server.base, session(server.base), INSTALLATION, SECRET)
            val worker = Executors.newSingleThreadExecutor()
            val published = java.util.concurrent.atomic.AtomicInteger()
            try {
                val result = worker.submit<Throwable?> {
                    try {
                        val reply = checkNotNull(call.execute())
                        published.incrementAndGet(); state.applyConfirmation(reply); null
                    } catch (error: Exception) { error }
                }
                assertTrue(registrationReceived.await(5, TimeUnit.SECONDS))
                call.cancel(); release.countDown()
                assertTrue(result.get(5, TimeUnit.SECONDS) is java.util.concurrent.CancellationException)
                assertEquals(0, published.get()); assertFalse(state.isAllowed(raw))
                try { server.finished() } catch (error: java.util.concurrent.ExecutionException) {
                    assertTrue(error.cause is java.io.IOException)
                }
                assertEquals(2, server.headers.size)
                report("registration_received=true delayed_204_not_published=true withdrawal_retained=true")
            } finally { release.countDown(); call.cancel(); worker.shutdownNow() }
        }
    }

    private fun chunk(bytes: ByteArray) = "${bytes.size.toString(16)}\r\n".toByteArray() + bytes + "\r\n0\r\n\r\n".toByteArray()
    private fun response(status: Int, headers: String, bytes: ByteArray) =
        "HTTP/1.1 $status Test\r\nContent-Type: application/json\r\n${headers}Connection: close\r\n\r\n".toByteArray() + bytes

    private class NativeServer(responses:List<Pair<Int,String>>,requestedPort:Int=0, private val responseGate:CountDownLatch?=null,
        wireResponse: ((Int, String) -> ByteArray)? = null):AutoCloseable {
        private val server=ServerSocket().apply {reuseAddress=true;bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),requestedPort));soTimeout=15_000}
        val port=server.localPort;val base="http://127.0.0.1:$port"
        val received=CountDownLatch(1)
        val bodies=mutableListOf<String>();val headers=mutableListOf<String>()
        private val executor=Executors.newSingleThreadExecutor()
        private val task=executor.submit {
            for((status,body) in responses) server.accept().use { c ->
                c.soTimeout=15_000;val input=c.getInputStream();val head=ByteArrayOutputStream()
                while(!head.toString("US-ASCII").endsWith("\r\n\r\n")){val b=input.read();check(b>=0&&head.size()<16_384);head.write(b)}
                val header=head.toString("US-ASCII");headers+=header
                val length=header.lineSequence().firstOrNull{it.startsWith("Content-Length:",true)}?.substringAfter(':')?.trim()?.toInt()?:0
                check(length in 0..1_000_000)
                val data=ByteArray(length);for(i in data.indices){val b=input.read();check(b>=0);data[i]=b.toByte()};bodies+=data.toString(Charsets.UTF_8)
                received.countDown();check(responseGate?.await(5,TimeUnit.SECONDS) != false)
                val bytes=body.toByteArray();c.getOutputStream().apply {
                    if (wireResponse != null) write(wireResponse(status, body))
                    else { write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes) }
                    flush()
                }
            }
        }
        fun finished(){task.get(20,TimeUnit.SECONDS)}
        override fun close(){server.close();executor.shutdownNow()}
    }

    private companion object {
        const val INSTALLATION="consent_device_installation_0001"
        val SECRET="b".repeat(64)
        val VERSIONS="""{"raw_source_collection":"FP-013-RAW-1.2.0","automatic_reporting":"FP-013-AUTO-1.2.0","mobile_network_transfer":"FP-013-MOBILE-1.0.0","training_reuse":"FP-013-TRAINING-1.1.0"}"""
    }
}
