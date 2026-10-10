package kr.co.hanium.dreamup.walksafe.network

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.security.*
import kr.co.hanium.dreamup.walksafe.session.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Uses only synthetic deletion state, isolated encrypted preferences/key and loopback HTTP.
 * MainActivity is opened solely to invoke its production journal codec; its deletion state is untouched.
 * Server rejection and the future evidence timestamp are fixtures; the phone clock is never changed. */
class DeletionEvidenceRecoveryDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val app=instrumentation.targetContext
    private fun report(s:String)=instrumentation.sendStatus(0,Bundle().apply{putString("stream","DELETION_EVIDENCE $s\n")})
    private fun hash(s:String)=MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString(""){"%02x".format(it)}
    private fun journal(base:String)=AccountDeletionJournal.pending(base,INSTALLATION,REQUEST,"2026-07-25T12:00:00Z")
    private fun evidence(j:AccountDeletionJournal):DeviceDeletionEvidence {
        val value=DeviceDeletionEvidence(j.requestId,checkNotNull(j.tombstoneId),checkNotNull(j.requestReceiptSha256),
            j.installationId,j.deviceEvidenceId?:"device-evidence-fixture-0001",j.clientRevision,
            j.deviceEvidenceExpectedStatusRevision?:j.serverRevision,j.deviceEvidenceResult?:"DELETED",
            j.deviceEvidenceCompletedAt?:"2040-01-01T00:00:00Z","0".repeat(64))
        return value.copy(evidenceSha256=deviceDeletionEvidenceSha256(value))
    }
    private fun codec(activity:MainActivity,name:String,arg:Any):Any? {
        val m=MainActivity::class.java.declaredMethods.single{it.name==name&&it.parameterCount==1}.apply{isAccessible=true}
        var result:Any?=null
        instrumentation.runOnMainSync{result=m.invoke(activity,arg)}
        return result
    }
    private fun cleanup(name:String,policy:AeadKeyPolicy,root:File) {
        assertTrue(app.deleteSharedPreferences(name))
        assertTrue(AndroidKeyStoreAead(policy).destroyKnownVersions())
        val keys=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        keys.deleteEntry(policy.alias(1)+".generation");assertFalse(keys.containsAlias(policy.alias(1)))
        assertTrue(!root.exists()||root.deleteRecursively())
    }

    @Test fun rejectedEvidenceSurvivesRealProcessRestartWithoutChangingItsIdentity() {
        val args=InstrumentationRegistry.getArguments()
        val phase=checkNotNull(args.getString("evidencePhase"));require(phase in listOf("write","read"))
        val run=checkNotNull(args.getString("evidenceRun"));require(run.matches(Regex("[A-Za-z0-9-]{8,64}")))
        val name="deletion-evidence-$run"
        val root=File(app.noBackupFilesDir.canonicalFile,name)
        val policy=AeadKeyPolicy("walksafe.instrumentation.deletion.$run.v",1,setOf(1))
        val spec=SensitivePreferenceSpec("cipher","blocked","reset","deletion-evidence-fixture".toByteArray(),policy,setOf("journal"))
        val rawPrefs=app.getSharedPreferences(name,Context.MODE_PRIVATE)
        var succeeded=false
        val activity=instrumentation.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        try {
            if(phase=="write") {
                assertFalse(root.exists());assertTrue(rawPrefs.all.isEmpty());assertTrue(root.mkdir())
                NativeServer(listOf(200 to validStatusJson(),422 to REJECTION)).use { server ->
                    val machine=AccountDeletionStateMachine();assertTrue(machine.requestConfirmation());assertTrue(machine.begin(journal(server.base)))
                    val status=AndroidPrivacyDeletionClient().fetchDeletionStatusCall(checkNotNull(machine.snapshotOrNull()),server.base,SECRET).execute()
                    assertEquals(AccountDeletionApplyResult.APPLIED,machine.apply(status))
                    val outgoing=evidence(checkNotNull(machine.snapshotOrNull()))
                    assertTrue(machine.recordDeviceEvidence(outgoing.evidenceId,outgoing.evidenceSha256,outgoing.expectedStatusRevision,outgoing.result,outgoing.completedAt))
                    val before=checkNotNull(machine.snapshotOrNull())
                    val error=assertThrows(AccountDeletionHttpException::class.java){AndroidPrivacyDeletionClient().submitDeviceDeletionEvidenceCall(before,server.base,SECRET,outgoing).execute()}
                    assertEquals(422,error.statusCode)
                    val disposition=accountDeletionHttpFailureDisposition(error.statusCode,error.errorBody) as AccountDeletionHttpFailureDisposition.Retry
                    assertTrue(machine.markRetry(disposition.code));val saved=checkNotNull(machine.snapshotOrNull())
                    assertEquals(before.copy(phase=AccountDeletionPhase.RETRY_WAIT,lastErrorCode="http_422"),saved)
                    assertTrue(machine.processingBlocked());assertFalse(saved.deviceEvidenceAcknowledged)
                    val json=codec(activity,"accountDeletionJournalJson",saved) as String
                    val encrypted=AndroidSensitivePreferenceStore(rawPrefs,spec)
                    assertTrue(encrypted.edit().putString("journal",json).commit())
                    val envelope=checkNotNull(rawPrefs.getString("cipher",null))
                    assertFalse(envelope.contains(saved.deviceEvidenceId!!));assertFalse(envelope.contains("2040-01-01"))
                    server.finished()
                    assertEquals(deviceDeletionEvidenceBody(outgoing),server.bodies[1])
                    File(root,"marker.json").writeText(JSONObject().put("pid",Process.myPid()).put("port",server.port)
                        .put("journal_sha256",hash(json)).put("request_sha256",hash(server.bodies[1])).toString())
                    report("phase=write http=422 retry_wait=true evidence_ack=false encrypted=true pid=${Process.myPid()} system_clock_changed=false")
                }
            } else {
                val marker=JSONObject(File(root,"marker.json").readText())
                assertNotEquals(marker.getInt("pid"),Process.myPid())
                val encrypted=AndroidSensitivePreferenceStore(rawPrefs,spec);assertFalse(encrypted.isBlocked())
                val json=checkNotNull(encrypted.getString("journal",null));assertEquals(marker.getString("journal_sha256"),hash(json))
                val saved=checkNotNull(codec(activity,"accountDeletionJournalOrNull",json) as AccountDeletionJournal?)
                val machine=AccountDeletionStateMachine();assertTrue(machine.restore(saved));assertTrue(machine.processingBlocked())
                assertEquals(AccountDeletionPhase.RETRY_WAIT,machine.phase());assertFalse(saved.deviceEvidenceAcknowledged)
                assertEquals("2040-01-01T00:00:00Z",saved.deviceEvidenceCompletedAt)
                NativeServer(listOf(422 to REJECTION),marker.getInt("port")).use { server ->
                    assertEquals(saved.gatewayOrigin,server.base)
                    val outgoing=evidence(saved);assertEquals(saved.deviceEvidenceSha256,outgoing.evidenceSha256)
                    val error=assertThrows(AccountDeletionHttpException::class.java){AndroidPrivacyDeletionClient().submitDeviceDeletionEvidenceCall(saved,server.base,SECRET,outgoing).execute()}
                    assertEquals(422,error.statusCode);server.finished()
                    assertEquals(marker.getString("request_sha256"),hash(server.bodies.single()))
                    assertTrue(machine.markRetry((accountDeletionHttpFailureDisposition(error.statusCode,error.errorBody) as AccountDeletionHttpFailureDisposition.Retry).code))
                    assertEquals(saved,machine.snapshotOrNull());assertTrue(machine.processingBlocked())
                    report("phase=read http=422 actual_new_process=true same_request_bytes=true same_evidence=true processing_blocked=true pid=${Process.myPid()}")
                }
            }
            succeeded=true
        } finally {
            instrumentation.runOnMainSync{activity.finish()}
            if(phase=="read"||!succeeded) cleanup(name,policy,root)
        }
    }

    @Test fun nativeStatusViewCannotPromoteUnverifiedDevicesToComplete() {
        NativeServer(listOf(200 to statusWithView().toString())).use { server ->
            val machine=AccountDeletionStateMachine();assertTrue(machine.requestConfirmation());assertTrue(machine.begin(journal(server.base)))
            val status=AndroidPrivacyDeletionClient().fetchDeletionStatusCall(checkNotNull(machine.snapshotOrNull()),server.base,SECRET).execute()
            server.finished();assertTrue(server.headers.single().contains("X-WalkSafe-Deletion-Status-View: device-verification.v1",true))
            assertEquals(AccountDeletionApplyResult.APPLIED,machine.apply(status));val current=checkNotNull(machine.snapshotOrNull())
            assertTrue(status.deviceVerificationNoticeFor(current)!!.contains("전체 삭제 완료는 아닙니다"))
            assertTrue(machine.processingBlocked());assertEquals(AccountDeletionPhase.IN_PROGRESS,machine.phase())
            assertNull(status.deviceVerificationNoticeFor(current.copy(requestId="other_delete_request_0001")))
            assertNull(status.deviceVerificationNoticeFor(current.copy(phase=AccountDeletionPhase.RETRY_WAIT,lastErrorCode="http_503")))
            report("status_view=PARTIAL device_unverified=true no_false_completion=true view_header_sent=true synthetic_inventory=true")
        }
    }

    @Test fun nativeStatusRejectsMalformedAndContradictoryDeviceViews() {
        val cases=listOf("target_count" to 3,"pending_count" to -1,"deleted_count" to "1",
            "unverifiable_reason" to JSONObject.NULL,"follow_up" to "UNKNOWN","completion_view" to "COMPLETED",
            "evaluated_at" to "not-a-date","extra" to true)
        for((key,value) in cases) {
            val invalid=statusWithView();invalid.getJSONObject("device_verification").put(key,value)
            NativeServer(listOf(200 to invalid.toString())).use { server ->
                assertThrows("Invalid field $key",AccountDeletionProtocolException::class.java){
                    AndroidPrivacyDeletionClient().fetchDeletionStatusCall(journal(server.base),server.base,SECRET).execute()
                };server.finished()
            }
            report("malformed_field=$key native_http_response=REJECTED")
        }
    }

    private class NativeServer(responses:List<Pair<Int,String>>,requestedPort:Int=0):AutoCloseable {
        private val server=ServerSocket().apply {reuseAddress=true;bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"),requestedPort));soTimeout=15_000}
        val port=server.localPort;val base="http://127.0.0.1:$port"
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
                val bytes=body.toByteArray();c.getOutputStream().apply{write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
            }
        }
        fun finished(){task.get(20,TimeUnit.SECONDS)}
        override fun close(){server.close();executor.shutdownNow()}
    }
    private companion object {
        const val INSTALLATION="installation-test-0001"
        const val REQUEST="request_delete_test_0001"
        val SECRET=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        const val REJECTION="{\"detail\":{\"code\":\"device_deletion_evidence_digest_invalid\"}}"
        fun validStatusJson():String {
            val accepted=Instant.parse("2026-07-25T12:00:00Z")
            val items=DeletionInventoryItem.entries.joinToString(","){ item ->
                """{"key":"${item.wireValue}","status":"IN_PROGRESS","item_revision":1,"due_at":"${accepted.plusMillis(item.maximumSlaMs)}","updated_at":"${accepted.plusSeconds(10)}","evidence_sha256":null,"disposition_basis":null,"retry_after":null,"restriction_reason":null,"legal_hold_review_at":null,"legal_hold_contact":null,"terminal_at":null}"""
            }
            return """{"schema_version":"$ACCOUNT_DELETION_STATUS_SCHEMA_VERSION","request_id":"$REQUEST","client_revision":1,"revision":1,"accepted_at":"$accepted","updated_at":"${accepted.plusSeconds(10)}","account_generation":2,"tombstone_id":"tombstone-00000001","request_receipt_sha256":"${"1".repeat(64)}","overall_status":"PROCESSING","items":[$items],"completion_receipt_sha256":null}"""
        }
        fun statusWithView():JSONObject {
            val root=JSONObject(validStatusJson());val items=root.getJSONArray("items")
            for(i in 0 until items.length()) {val item=items.getJSONObject(i);if(item.getString("key")!="device_untransmitted_data")
                item.put("status","COMPLETED").put("evidence_sha256","b".repeat(64)).put("terminal_at",root.getString("updated_at"))}
            root.put("overall_status","PARTIAL")
            return root.put("device_verification",JSONObject("""{"schema_version":"walksafe.account-deletion-device-verification.v1","completion_view":"SERVER_COMPLETED_DEVICE_UNVERIFIED","target_count":2,"deleted_count":1,"not_found_count":0,"failed_count":0,"pending_count":0,"unverifiable_count":1,"unverifiable_reason":"SLA_ELAPSED_NO_CONTACT","follow_up":"DELETE_ON_NEXT_CONTACT_OR_REMOVE_APP","device_clock_anomaly_count":0,"device_due_at":"2026-07-26T12:00:00Z","evaluated_at":"2026-07-27T12:00:00Z"}"""))
        }
    }
}
