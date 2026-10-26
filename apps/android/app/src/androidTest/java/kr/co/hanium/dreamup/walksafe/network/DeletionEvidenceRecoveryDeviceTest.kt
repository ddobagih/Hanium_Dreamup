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

    @Test fun nativeDeletionStatusRejectsAmbiguousJsonWithoutChangingTheJournal() {
        val valid = validStatusJson()
        val malformed = listOf(valid + " {}", valid + "junk", valid.replaceFirst("{", "{\"revision\":2,"),
            valid.replaceFirst("{", "{\"\\u0072evision\":2,"), valid.replace("\"revision\":1", "revision:1"),
            valid.replaceFirst("\"schema_version\"", "'schema_version'"), valid.dropLast(1) + ",}")
        val accepted = mutableListOf<Int>()
        for ((index, body) in malformed.withIndex()) NativeServer(listOf(200 to body)).use { server ->
            val machine = AccountDeletionStateMachine()
            assertTrue(machine.requestConfirmation()); assertTrue(machine.begin(journal(server.base)))
            val before = machine.snapshotOrNull()
            val result = runCatching { AndroidPrivacyDeletionClient()
                .fetchDeletionStatusCall(checkNotNull(before), server.base, SECRET).execute() }
            if (result.isSuccess) accepted += index
            else assertTrue(result.exceptionOrNull() is AccountDeletionProtocolException)
            assertEquals(before, machine.snapshotOrNull()); assertTrue(machine.processingBlocked())
            server.finished()
        }
        report("status_document_cases=${malformed.size} unexpectedly_accepted=$accepted no_real_deletion=true")
        assertEquals(emptyList<Int>(), accepted)
    }

    @Test fun nativeDeletionErrorsCannotUseAmbiguousJsonForTerminalOrReauthenticationActions() {
        val accepted = mutableListOf<String>()
        for ((status, valid) in listOf(
            409 to "{\"code\":\"account_deletion_already_completed\"}",
            401 to "{\"code\":\"account_deletion_reauthentication_required\",\"reauthentication_required\":true}")) {
            val malformed = listOf(valid + " {}", valid + "junk", valid.replaceFirst("{", "{\"code\":\"other\","),
                valid.replaceFirst("{", "{\"\\u0063ode\":\"other\","), valid.replace("\"code\"", "code"),
                valid.replace("\"code\"", "'code'"), valid.dropLast(1) + ",}")
            for ((index, body) in malformed.withIndex()) NativeServer(listOf(status to body)).use { server ->
                val error = assertThrows(AccountDeletionHttpException::class.java) {
                    AndroidPrivacyDeletionClient().fetchDeletionStatusCall(journal(server.base), server.base, SECRET).execute()
                }
                if (accountDeletionHttpFailureDisposition(error.statusCode, error.errorBody) !=
                    AccountDeletionHttpFailureDisposition.Retry("http_$status")) accepted += "$status:$index"
                server.finished()
            }
        }
        report("error_document_cases=14 unexpectedly_actionable=$accepted")
        assertEquals(emptyList<String>(), accepted)
    }

    @Test fun nativeDeletionResponseFramingAndUtf8AreStrictOnSuccessAndFailure() {
        val accepted = mutableListOf<String>()
        for (status in listOf(200, 409)) {
            val body = if (status == 200) validStatusJson() else "{\"code\":\"account_deletion_already_completed\"}"
            val bytes = body.toByteArray()
            val cases = listOf(
                "short" to ("Content-Length: ${bytes.size + 20}\r\n" to bytes),
                "duplicate_length" to ("Content-Length: ${bytes.size}\r\nContent-Length: ${bytes.size}\r\n" to bytes),
                "conflicting_framing" to ("Content-Length: ${bytes.size}\r\nTransfer-Encoding: chunked\r\n" to chunk(bytes)),
                "encoding" to ("Content-Length: ${bytes.size}\r\nContent-Encoding: gzip\r\n" to bytes),
                "invalid_utf8" to ("" to (bytes.copyOfRange(0, bytes.size - 1) +
                    ",\"ignored\":\"".toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) + "\"}".toByteArray()))
            )
            for ((name, wire) in cases) NativeServer(listOf(status to body), wireResponse = { _, _ ->
                response(status, wire.first, wire.second)
            }).use { server ->
                val result = runCatching { AndroidPrivacyDeletionClient()
                    .fetchDeletionStatusCall(journal(server.base), server.base, SECRET).execute() }
                if (result.exceptionOrNull() !is NetworkResponseProtocolException) accepted += "$status:$name"
                server.finished()
            }
        }
        report("strict_wire_cases=10 missing_transport_rejection=$accepted")
        assertEquals(emptyList<String>(), accepted)
    }

    @Test fun nativeDeletionValidFramingAndErrorCodesRemainUsable() {
        val bytes = validStatusJson().toByteArray()
        for ((name, wire) in listOf(
            "length" to ("Content-Length: ${bytes.size}\r\n" to bytes),
            "chunked" to ("Transfer-Encoding: chunked\r\n" to chunk(bytes)),
            "close" to ("" to bytes))) NativeServer(listOf(200 to ""), wireResponse = { _, _ ->
                response(200, wire.first, wire.second)
            }).use { server ->
                val value = AndroidPrivacyDeletionClient().fetchDeletionStatusCall(journal(server.base), server.base, SECRET).execute()
                assertEquals(REQUEST, value.requestId); server.finished()
                assertTrue("identity requested for $name", server.headers.single().contains("Accept-Encoding: identity", true))
            }
        for ((status, body) in listOf(409 to "{\"code\":\"account_deletion_already_completed\"}",
            401 to "{\"code\":\"account_deletion_reauthentication_required\",\"reauthentication_required\":true}")) {
            NativeServer(listOf(status to body)).use { server ->
                val error = assertThrows(AccountDeletionHttpException::class.java) { AndroidPrivacyDeletionClient()
                    .fetchDeletionStatusCall(journal(server.base), server.base, SECRET).execute() }
                assertEquals(body, error.errorBody); assertEquals(status, error.statusCode); server.finished()
                assertEquals(if (status == 409) AccountDeletionHttpFailureDisposition.TerminalConflict("account_deletion_already_completed")
                    else AccountDeletionHttpFailureDisposition.Retry(ACCOUNT_DELETION_REAUTHENTICATION_RETRY_CODE),
                    accountDeletionHttpFailureDisposition(status, body))
            }
        }
        report("valid_wire_formats=3 original_error_actions=2")
    }

    @Test fun allFiveDeletionEntryPointsShareTheStrictResponseBoundary() {
        for (entry in listOf("initial", "recovery", "replay", "status", "evidence")) {
            val code = if (entry == "initial") 202 else 200
            val valid = validStatusJson()
            for (kind in listOf("valid", "extra_json", "short_body")) {
                val body = if (kind == "extra_json") "$valid {}" else valid
                NativeServer(listOf(code to body), wireResponse = { _, text ->
                    val bytes = text.toByteArray()
                    response(code, "Content-Length: ${bytes.size + if (kind == "short_body") 8 else 0}\r\n", bytes)
                }).use { server ->
                    val client = AndroidPrivacyDeletionClient()
                    val machine = AccountDeletionStateMachine()
                    assertTrue(machine.requestConfirmation()); assertTrue(machine.begin(journal(server.base)))
                    assertEquals(AccountDeletionApplyResult.APPLIED, machine.apply(checkNotNull(
                        validatedAccountDeletionStatusOrNull(valid, INSTALLATION, REQUEST))))
                    val saved = checkNotNull(machine.snapshotOrNull())
                    val call = when (entry) {
                        "initial" -> client.requestDeletionCall(server.base, server.base, GatewayFieldSession.verified(
                            server.base, "synthetic-deletion-actor", "walksafe_field_session=" + "a".repeat(64),
                            System.currentTimeMillis() + 60_000), INSTALLATION, SECRET, REQUEST)
                        "recovery" -> client.recoverDeletionRequestAcceptOrReplayCall(server.base, server.base,
                            GatewayFieldSession.legacyVerified(gatewayBaseUrl = server.base, actorId = "synthetic-deletion-actor",
                                deviceId = "synthetic-deletion-device", cookiePair = "walksafe_field_session=" + "b".repeat(64),
                                expiresAtEpochMs = System.currentTimeMillis() + 60_000,
                                sessionScope = GatewaySessionScope.ACCOUNT_DELETION_RECOVERY), INSTALLATION, SECRET, REQUEST)
                        "replay" -> client.replayDeletionRequestCall(saved, server.base, SECRET)
                        "status" -> client.fetchDeletionStatusCall(saved, server.base, SECRET)
                        else -> { val proof = evidence(saved); client.submitDeviceDeletionEvidenceCall(saved, server.base, SECRET, proof) }
                    }
                    when (kind) {
                        "valid" -> assertEquals(REQUEST, call.execute().requestId)
                        "extra_json" -> assertThrows(AccountDeletionProtocolException::class.java) { call.execute() }
                        else -> assertThrows(NetworkResponseProtocolException::class.java) { call.execute() }
                    }
                    server.finished()
                    assertTrue(server.headers.single().contains("Accept-Encoding: identity", true))
                    assertEquals(saved, machine.snapshotOrNull()); assertTrue(machine.processingBlocked())
                }
            }
        }
        report("entry_points=5 valid_and_extra_json_and_short_body_cases=15 actual_accounts_untouched=true")
    }

    private fun chunk(bytes: ByteArray) = "${bytes.size.toString(16)}\r\n".toByteArray() + bytes + "\r\n0\r\n\r\n".toByteArray()

    @Test fun nativeRegisteredKeySignsFailedEvidenceAndKeepsIdenticalRetryBytes() {
        withIsolatedEvidenceKey { installation, key, alias, cacheDigests ->
            NativeServer(listOf(200 to consentBody(installation), 204 to "",
                200 to validStatusJson(), 200 to validStatusJson())).use { server ->
                val session = GatewayFieldSession.verified(server.base, "synthetic-native-evidence-actor",
                    "walksafe_field_session=v2.synthetic.evidence", Long.MAX_VALUE)
                val consent = AndroidIntegratedConsentClient(key::publicKey)
                    .fetchCurrentCall(server.base, session, installation, "b".repeat(64)).execute()
                assertNotNull(consent)
                val machine = AccountDeletionStateMachine()
                assertTrue(machine.requestConfirmation())
                assertTrue(machine.begin(AccountDeletionJournal.pending(server.base, installation, REQUEST, "2026-07-25T12:00:00Z")))
                assertEquals(AccountDeletionApplyResult.APPLIED, machine.apply(checkNotNull(
                    validatedAccountDeletionStatusOrNull(validStatusJson(), installation, REQUEST))))
                val saved = checkNotNull(machine.snapshotOrNull())
                // These are failed, synthetic deletion results. No application data is erased.
                val unsigned = evidence(saved).copy(result = "FAILED", completedAt = "2026-07-25T12:00:05Z", evidenceSha256 = "0".repeat(64))
                val proof = unsigned.copy(evidenceSha256 = deviceDeletionEvidenceSha256(unsigned))
                cacheDigests.add(proof.evidenceSha256)
                val client = AndroidPrivacyDeletionClient(key::signIfPresent)
                repeat(2) { assertEquals("PROCESSING", client.submitDeviceDeletionEvidenceCall(saved, server.base, SECRET, proof).execute().overallStatus.name) }
                server.finished()
                val registration = JSONObject(server.bodies[1])
                assertEquals(installation, registration.getString("installation_id"))
                assertTrue(server.headers[1].startsWith("PUT /privacy/rights?control=installation-evidence-key "))
                assertTrue(server.headers[1].contains("Cookie: walksafe_field_session=v2.synthetic.evidence", true))
                val publicKey = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(
                    java.util.Base64.getUrlDecoder().decode(registration.getString("public_key"))))
                assertNull(KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null).encoded)
                assertEquals(server.bodies[2], server.bodies[3])
                val sent = JSONObject(server.bodies[2])
                assertEquals(installation, sent.getString("installation_id")); assertEquals("FAILED", sent.getString("result"))
                assertEquals(proof.evidenceSha256, sent.getString("evidence_sha256"))
                val signature = java.util.Base64.getUrlDecoder().decode(sent.getString("installation_signature"))
                fun verifies(digest: String) = java.security.Signature.getInstance("SHA256withECDSA").run {
                    initVerify(publicKey); update(("walksafe/device-deletion-evidence-signature/v1\u0000" + digest).toByteArray(Charsets.US_ASCII)); verify(signature)
                }
                assertTrue(verifies(proof.evidenceSha256))
                val other = proof.copy(installationId = installation + "-other")
                assertFalse(verifies(deviceDeletionEvidenceSha256(other)))
                assertEquals(saved, machine.snapshotOrNull()); assertTrue(machine.processingBlocked())
                report("native_keystore_registration=true private_key_exportable=false signed_failed_evidence_http=true exact_retry=true other_installation_digest_rejected=true")
            }
        }
    }

    @Test fun nativeEvidenceKeyRegistrationConflictRequiresExplicitRetryWithoutKeyReplacement() {
        withIsolatedEvidenceKey { installation, key, _, _ ->
            NativeServer(listOf(200 to consentBody(installation), 409 to "{\"detail\":{\"code\":\"installation_evidence_key_conflict\"}}",
                200 to consentBody(installation), 204 to "")).use { server ->
                val session = GatewayFieldSession.verified(server.base, "synthetic-native-evidence-actor",
                    "walksafe_field_session=v2.synthetic.evidence", Long.MAX_VALUE)
                val client = AndroidIntegratedConsentClient(key::publicKey)
                val failure = assertThrows(IntegratedConsentHttpException::class.java) {
                    client.fetchCurrentCall(server.base, session, installation, "b".repeat(64)).execute()
                }
                assertEquals(409, failure.statusCode)
                assertEquals(2, server.headers.size)
                val before = key.publicKey(installation)
                assertNotNull(client.fetchCurrentCall(server.base, session, installation, "b".repeat(64)).execute())
                server.finished()
                assertEquals(4, server.headers.size)
                assertEquals(before, key.publicKey(installation))
                assertEquals(server.bodies[1], server.bodies[3])
                report("registration_conflict_not_success=true no_implicit_retry=true same_native_public_key_on_explicit_retry=true")
            }
        }
    }

    private fun withIsolatedEvidenceKey(test: (String, AndroidInstallationEvidenceKey, String, MutableSet<String>) -> Unit) {
        val installation = "instrumentation-wire-evidence-" + java.util.UUID.randomUUID()
        val alias = "walkmate.deletion-evidence." + hash(installation)
        val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cacheDigests = mutableSetOf<String>()
        assertFalse(keys.containsAlias(alias))
        try { test(installation, AndroidInstallationEvidenceKey(app), alias, cacheDigests) }
        finally {
            keys.deleteEntry(alias); assertFalse(keys.containsAlias(alias))
            // Only this test's digest-specific cache is removed; never call the app-wide reset.
            for (digest in cacheDigests) for (suffix in listOf("", ".bak", ".new")) {
                val name = "deletion-signature-" + hash("$installation:$digest")
                val file = File(app.noBackupFilesDir, name + suffix)
                if (file.exists()) assertTrue(file.delete())
            }
        }
    }

    private fun consentBody(installation: String): String = JSONObject()
        .put("schema_version", INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION).put("current", true)
        .put("installation_id", installation).put("request_id", "native_evidence_consent_0001")
        .put("policy_version", INTEGRATED_CONSENT_POLICY_VERSION)
        .put("item_versions", JSONObject().put("raw_source_collection", INTEGRATED_CONSENT_RAW_ITEM_VERSION)
            .put("automatic_reporting", INTEGRATED_CONSENT_AUTOMATIC_ITEM_VERSION)
            .put("mobile_network_transfer", INTEGRATED_CONSENT_MOBILE_ITEM_VERSION)
            .put("training_reuse", INTEGRATED_CONSENT_TRAINING_ITEM_VERSION))
        .put("client_revision", 1).put("revision", 1)
        .put("selections", JSONObject().put("raw_source_collection", false).put("automatic_reporting", false)
            .put("mobile_network_transfer", false).put("training_reuse", false))
        .put("confirmed_at", "2026-10-25T00:00:00.000Z").put("gateway_audit_record_sha256", "a".repeat(64))
        .put("backend_consent_receipt_sha256", "b".repeat(64)).toString()

    private fun response(status: Int, headers: String, bytes: ByteArray) =
        "HTTP/1.1 $status Test\r\nContent-Type: application/json\r\n${headers}Connection: close\r\n\r\n".toByteArray() + bytes

    private class NativeServer(responses:List<Pair<Int,String>>,requestedPort:Int=0,
        wireResponse: ((Int, String) -> ByteArray)? = null):AutoCloseable {
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
