package kr.co.hanium.dreamup.walksafe.network

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.co.hanium.dreamup.walksafe.testing.NativeLoopbackServer
import kr.co.hanium.dreamup.walksafe.testing.NativeHttpReply
import kr.co.hanium.dreamup.walksafe.testing.syntheticBoundaryContext

/** Native HTTP + real consent state machine; synthetic authority and loopback peer only.
 * No real account, consent preference, Main callback, or durable journal is modified. */
@RunWith(AndroidJUnit4::class)
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
    private fun read(client:AndroidIntegratedConsentClient,server:NativeLoopbackServer)=checkNotNull(
        client.fetchCurrentCall(server.baseUrl,session(server.baseUrl),INSTALLATION,SECRET).execute())
    private fun save(client:AndroidIntegratedConsentClient,server:NativeLoopbackServer,m:PendingIntegratedConsentMutation)=checkNotNull(
        client.saveCall(server.baseUrl,session(server.baseUrl),INSTALLATION,SECRET,m.requestId,m.clientRevision,
            m.desiredSelections,m.expectedPreviousBackendReceiptSha256).execute())

    @Test fun orphanWithdrawalUsesFreshHttpReceiptAndRequiresExactReply() {
        syntheticBoundaryContext()
        val original=confirmation()
        val fresh=confirmation(2,"c".repeat(64))
        val expected=mutation(fresh,fresh.selections.copy(rawSourceCollection=false))
        val acknowledged=confirmation(3,"d".repeat(64),expected.requestId,expected.desiredSelections)
        peer(listOf(200 to body(fresh),201 to body(acknowledged.copy(requestId="consent_wrong_reply_0003")),
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
            server.finished();val sent=JSONObject(server.requests[1].body)
            assertEquals(fresh.backendConsentReceiptSha256,sent.getString("expected_previous_backend_receipt_sha256"))
            assertFalse(sent.getJSONObject("selections").getBoolean("raw_source_collection"))
            assertEquals(server.requests[1].body,server.requests[2].body);assertTrue(server.requests[1].method == "PUT")
            report("orphan_fresh_receipt=true stale_grant_blocked=true wrong_request_rejected=true exact_denial_confirmed=true same_retry_bytes=true")
        }
    }

    @Test fun httpConflictPreservesWithdrawalAndDoesNotExpandOtherChoices() {
        syntheticBoundaryContext()
        val original=confirmation().copy(selections=IntegratedConsentSelections(true,true,true,false))
        val rejected=mutation(original,original.selections.copy(rawSourceCollection=false,trainingReuse=true))
        val backendChoices=original.selections.copy(automaticReporting=false)
        val freshReceipt="e".repeat(64)
        val bootstrap=JSONObject().put("schema_version","walksafe.integrated-consent-bootstrap.v1")
            .put("status","READY").put("source","CURRENT_CONSENT").put("installation_id",INSTALLATION)
            .put("policy_version",INTEGRATED_CONSENT_POLICY_VERSION).put("item_versions",JSONObject(VERSIONS))
            .put("client_revision_floor",1).put("selections",choices(backendChoices))
            .put("source_receipt_sha256",freshReceipt).put("expected_previous_backend_receipt_sha256",freshReceipt)
        peer(listOf(409 to "{\"detail\":{\"code\":\"integrated_consent_previous_receipt_conflict\"}}",
            200 to bootstrap.toString())).use { server ->
            val state=IntegratedConsentSession();assertTrue(state.accept(original))
            assertTrue(state.restorePendingMutation(rejected,retry=true));val client=AndroidIntegratedConsentClient()
            assertEquals(409,assertThrows(IntegratedConsentHttpException::class.java){save(client,server,rejected)}.statusCode)
            assertEquals(rejected,state.pendingMutationOrNull());assertFalse(state.isAllowed(raw))
            state.invalidateBackendAuthority()
            val latest=client.fetchBootstrapCall(server.baseUrl,session(server.baseUrl),INSTALLATION).execute()
            val successor=rejected.successorAfterRejectedCas(checkNotNull(latest.selections),
                checkNotNull(latest.expectedPreviousBackendReceiptSha256),latest.clientRevisionFloor,2L,rejected.withdrawalItems,2000L)
            // Check candidate only: no pretend persistence or Main dispatch success.
            assertFalse(successor.desiredSelections.rawSourceCollection)
            assertFalse(successor.desiredSelections.automaticReporting)
            assertFalse(successor.desiredSelections.trainingReuse)
            assertEquals(freshReceipt,successor.expectedPreviousBackendReceiptSha256)
            assertEquals(rejected,state.pendingMutationOrNull());assertFalse(state.isAllowed(raw))
            server.finished();assertEquals(2,server.requests.size)
            report("native_http_conflict=409 original_withdrawal_retained=true fresh_cas_candidate_nonexpanding=true not_persisted_or_sent=true")
        }
    }

    @Test fun malformedNativeConfirmationNeverReplacesWithdrawal() {
        syntheticBoundaryContext()
        val mutations=listOf("installation_id" to "other_installation_0001", "confirmed_at" to "not-a-time",
            "revision" to -1, "backend_consent_receipt_sha256" to "invalid")
        for((key,value) in mutations) {
            val state=IntegratedConsentSession();assertTrue(state.accept(confirmation()));state.withdrawImmediately(raw)
            val before=state.cachedConfirmationOrNull()
            peer(listOf(200 to JSONObject(body(confirmation(2))).put(key,value).toString())).use { server ->
                assertThrows(IntegratedConsentProtocolException::class.java){read(AndroidIntegratedConsentClient(),server)}
                server.finished();assertEquals(before,state.cachedConfirmationOrNull());assertFalse(state.isAllowed(raw))
                assertEquals(setOf(raw),state.localWithdrawalItems())
            }
            report("invalid_field=$key protocol_rejected=true withdrawal_retained=true")
        }
    }

    @Test fun cancelledInFlightWithdrawalDoesNotAcceptDelayedSuccess() {
        syntheticBoundaryContext()
        val original=confirmation()
        val pending=mutation(original,original.selections.copy(rawSourceCollection=false))
        val response=confirmation(2,"d".repeat(64),pending.requestId,pending.desiredSelections)
        val gate=CountDownLatch(1)
        peer(listOf(201 to body(response)),responseGate=gate).use { server ->
            val state=IntegratedConsentSession();assertTrue(state.accept(original))
            assertTrue(state.restorePendingMutation(pending,retry=true))
            val call=AndroidIntegratedConsentClient().saveCall(server.baseUrl,session(server.baseUrl),INSTALLATION,SECRET,
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

    private fun peer(responses: List<Pair<Int, String>>, responseGate: CountDownLatch? = null) =
        NativeLoopbackServer(responses.map { (status, body) -> NativeHttpReply(status, body, release = responseGate) })

    private companion object {
        const val INSTALLATION="consent_device_installation_0001"
        val SECRET="b".repeat(64)
        val VERSIONS="""{"raw_source_collection":"FP-013-RAW-1.2.0","automatic_reporting":"FP-013-AUTO-1.2.0","mobile_network_transfer":"FP-013-MOBILE-1.0.0","training_reuse":"FP-013-TRAINING-1.1.0"}"""
    }
}
