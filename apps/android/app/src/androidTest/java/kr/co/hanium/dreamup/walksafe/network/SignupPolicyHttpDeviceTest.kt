package kr.co.hanium.dreamup.walksafe.network

import android.os.Bundle
import android.os.SystemClock
import java.net.SocketTimeoutException
import java.time.Instant
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.account.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual Android HTTP/JSON, synthetic loopback policy and account responses.
 * No real account/OTP/consent, Main UI, stored enrollment or external service is touched. */
class SignupPolicyHttpDeviceTest {
    @Test fun strictNativeJsonAcrossPolicyOtpAndAccountCreation() {
        val forms = listOf("valid", "whitespace", "extra-document", "extra-text", "unquoted", "single-quotes", "trailing-comma", "duplicate")
        for (kind in listOf("policy", "otp", "create")) for (form in forms) {
            val canonical = when (kind) { "policy" -> policy().toString(); "otp" -> otpReply().toString(); else -> CREATED }
            val response = when (form) {
                "whitespace" -> " \r\n$canonical\t "
                "extra-document" -> canonical + "{}"
                "extra-text" -> canonical + "private-trailing-fixture"
                "unquoted" -> canonical.replace("\"schema_version\":", "schema_version:")
                "single-quotes" -> canonical.replace('"', '\'')
                "trailing-comma" -> canonical.dropLast(1) + ",}"
                "duplicate" -> canonical.dropLast(1) + ",\"schem\\u0061_version\":" + JSONObject.quote(JSONObject(canonical).getString("schema_version")) + "}"
                else -> canonical
            }
            val status = when (kind) { "policy" -> 200; "otp" -> 202; else -> 201 }
            NativeServer(listOf(status to response)).use { server ->
                val result = runCatching {
                    val client = GatewayAccountClient()
                    when (kind) {
                        "policy" -> assertTrue(client.signupUnavailableOptionalDocuments(server.base).isEmpty())
                        "otp" -> assertEquals(HANDLE, requestOtp(server.base).enrollmentHandle)
                        else -> create(client, server.base, "Synthetic-R7!password")
                    }
                }
                if (form in listOf("valid", "whitespace")) result.getOrThrow()
                else {
                    val failure = result.exceptionOrNull()
                    assertTrue("$kind/$form accepted or wrong error: $failure", failure is GatewayAccountHttpException)
                    assertEquals(0, (failure as GatewayAccountHttpException).statusCode)
                    assertEquals(when (kind) { "policy" -> "invalid_signup_policy"; "otp" -> "invalid_email_otp_response"; else -> "invalid_account_create_response" }, failure.reason)
                    assertFalse(failure.message.orEmpty().contains("private-trailing-fixture"))
                }
                server.finished(); assertEquals(1, server.headers.size)
                report("strict_native_json=$kind/$form accepted=${result.isSuccess} synthetic_response_only=true")
            }
        }
    }

    private fun report(value:String)=InstrumentationRegistry.getInstrumentation().sendStatus(0,
        Bundle().apply{putString("stream","SIGNUP_POLICY $value\n")})
    private fun policy()=JSONObject().put("schema_version","walksafe.account-signup-policy.v1")
        .put("documents",JSONObject().also{docs -> SIGNUP_DOCUMENT_VERSIONS.entries.forEachIndexed { i,(key,version) ->
            docs.put(key,JSONObject().put("version",version).put("required",i<3)
                .put("equivalent_versions",JSONArray()).put("prior_versions",JSONArray()))
        }})
    private fun doc(p:JSONObject,key:String)=p.getJSONObject("documents").getJSONObject(key)
    private val all=SignupConsentSelections(true,true,true,true,true,true)
    private fun create(client:GatewayAccountClient,base:String,password:String,selections:SignupConsentSelections=all)=
        client.createAccount(base,HANDLE,"123456",password,selections)

    @Test fun currentAndEquivalentPolicyRemainUsableOnNativeHttp() {
        val equivalent=policy();doc(equivalent,"terms_of_service").put("version","v2")
            .put("equivalent_versions",JSONArray(listOf(SIGNUP_DOCUMENT_VERSIONS.getValue("terms_of_service"))))
        NativeServer(listOf(200 to policy().toString(),200 to equivalent.toString())).use { server ->
            val client=GatewayAccountClient()
            repeat(2){assertTrue(client.signupUnavailableOptionalDocuments(server.base).isEmpty())}
            server.finished();assertTrue(server.headers.all{it.startsWith("GET /api/account-signup-policy ")})
            assertTrue(server.bodies.all{it.isEmpty()})
            report("current_and_equivalent_pass=true actual_native_http_gets=2")
        }
    }

    @Test fun knownOldOptionalChoicesAreRemovedFromActualSubmittedBody() {
        val p=policy();val unavailable=setOf("raw_original","training_reuse")
        for(key in unavailable) doc(p,key).put("version","v2").put("prior_versions",
            JSONArray(listOf(SIGNUP_DOCUMENT_VERSIONS.getValue(key))))
        NativeServer(listOf(200 to p.toString(),201 to CREATED)).use { server ->
            val client=GatewayAccountClient();val old=client.signupUnavailableOptionalDocuments(server.base)
            assertEquals(unavailable,old)
            val restricted=SignupPolicyPreflight.restrict(all,old)
            assertTrue(restricted.requiredGranted);assertTrue(restricted.automaticReporting)
            assertFalse(restricted.rawOriginal);assertFalse(restricted.trainingReuse)
            create(client,server.base,"Synthetic-R7!password",restricted);server.finished()
            assertTrue(server.headers[1].startsWith("POST /api/accounts "))
            val consent=JSONObject(server.bodies[1]).getJSONObject("consent")
            val sent=consent.getJSONObject("selections")
            assertFalse(sent.getBoolean("raw_original"));assertFalse(sent.getBoolean("training_reuse"))
            assertTrue(sent.getBoolean("automatic_reporting"))
            val versions=consent.getJSONObject("document_versions")
            SIGNUP_DOCUMENT_VERSIONS.forEach{(key,value)->assertEquals(value,versions.getString(key))}
            report("known_prior_optional_removed_from_actual_post=true required_preserved=true bundled_document_versions_preserved=true account_response_synthetic=true")
        }
    }

    @Test fun requiredMismatchMalformedPolicyAndServerFailureAreRejected() {
        val required=policy().also{doc(it,"terms_of_service").put("version","v2")}
        val unknownOptional=policy().also{doc(it,"training_reuse").put("version","v2")}
        val weakened=policy().also{doc(it,"privacy_notice").put("required",false)}
        val wrongType=policy().also{doc(it,"privacy_notice").put("required","true")}
        val missing=policy().also{it.getJSONObject("documents").remove("training_reuse")}
        val extra=policy().put("extra",true)
        val wrongArray=policy().also{doc(it,"raw_original").put("equivalent_versions",JSONArray().put(3))}
        val cases=listOf(Triple(200,required.toString(),"signup_policy_outdated"),
            Triple(200,unknownOptional.toString(),"signup_policy_outdated"),
            Triple(200,weakened.toString(),"invalid_signup_policy"),
            Triple(200,wrongType.toString(),"invalid_signup_policy"),
            Triple(200,missing.toString(),"invalid_signup_policy"),
            Triple(200,extra.toString(),"invalid_signup_policy"),
            Triple(200,wrongArray.toString(),"invalid_signup_policy"),
            Triple(503,"{}","signup_policy_unavailable"))
        cases.forEachIndexed { index,(code,body,reason) ->
            NativeServer(listOf(code to body)).use { server ->
                val error=assertThrows(GatewayAccountHttpException::class.java){
                    GatewayAccountClient().signupUnavailableOptionalDocuments(server.base)
                }
                assertEquals(reason,error.reason);server.finished()
                assertEquals(1,server.headers.size);assertTrue(server.headers.single().startsWith("GET "))
                if(reason=="signup_policy_outdated") assertEquals("signup_consent_outdated",error.serverCode)
            }
            report("rejected_policy_case=$index reason=$reason")
        }
    }

    @Test fun unicodePasswordBoundariesPreserveOriginalBytesAndInvalidInputNeverDispatches() {
        val accepted=listOf("😀".repeat(12),"가".repeat(126)+"e\u0301")
        val invalid=listOf("😀".repeat(11),"😀".repeat(129),"TestPassword\u0000A")
        NativeServer(List(accepted.size){201 to CREATED}).use { server ->
            val transport=CountingTransport();val client=GatewayAccountClient(transport)
            invalid.forEach { password ->
                val error=assertThrows(GatewayAccountHttpException::class.java){create(client,server.base,password)}
                assertEquals("invalid_account_create_input",error.reason)
                assertFalse(error.message.orEmpty().contains(password))
            }
            assertEquals(0,transport.posts)
            accepted.forEach{create(client,server.base,it)};server.finished()
            assertEquals(2,transport.posts)
            accepted.forEachIndexed{index,password -> assertEquals(password,JSONObject(server.bodies[index]).getString("password"))}
            assertEquals(12,accepted[0].codePointCount(0,accepted[0].length))
            assertEquals(128,accepted[1].codePointCount(0,accepted[1].length))
            report("native_unicode_serialization_preserved=true valid_codepoint_lengths=12,128 invalid_11_129_nul_dispatches=0 backend_strength_policy_not_tested=true")
        }
    }

    private fun otpReply(now:Long=System.currentTimeMillis())=JSONObject()
        .put("schema_version","walksafe.account-enrollment-email-otp-response.v1")
        .put("enrollment_handle",HANDLE)
        .put("expires_at",Instant.ofEpochSecond(now/1000+300).toString())
        .put("resend_available_at",Instant.ofEpochSecond(now/1000+30).toString())
    private fun requestOtp(base:String)=GatewayAccountClient().requestEmailOtp(
        base,"synthetic@example.invalid","2000-01-01","synthetic-request-0001")

    @Test(timeout=35_000) fun otpAcceptsResponseBeyondNormalReadTimeout() {
        val reply=otpReply()
        NativeServer(listOf(202 to reply.toString()),responseDelayMs=13_000).use { server ->
            val started=SystemClock.elapsedRealtime()
            val received=requestOtp(server.base)
            val elapsed=SystemClock.elapsedRealtime()-started
            server.finished()
            assertTrue("Real response must wait 13 seconds",elapsed>=13_000)
            assertEquals(HANDLE,received.enrollmentHandle)
            assertEquals(Instant.parse(reply.getString("expires_at")).toEpochMilli(),received.expiresAtEpochMs)
            assertEquals(1,server.headers.size)
            assertTrue(server.headers.single().startsWith("POST /api/account-enrollments/email-otp "))
            assertEquals("synthetic-request-0001",JSONObject(server.bodies.single()).getString("request_id"))
            report("otp_delayed_response_accepted=true elapsed_ms=$elapsed native_http=true full_75s_boundary_not_tested=true")
        }
    }

    @Test(timeout=35_000) fun ordinaryRequestStillTimesOutBeforeSameDelayedResponse() {
        NativeServer(listOf(200 to policy().toString()),responseDelayMs=13_000,
            allowClientDisconnect=true).use { server ->
            val started=SystemClock.elapsedRealtime()
            assertThrows(SocketTimeoutException::class.java){
                GatewayAccountClient().signupUnavailableOptionalDocuments(server.base)
            }
            val elapsed=SystemClock.elapsedRealtime()-started
            server.finished()
            assertTrue("Timeout must not be an immediate fixture failure",elapsed>=11_500)
            assertTrue("Timeout must precede delayed response",elapsed<13_000)
            assertEquals(1,server.headers.size)
            assertTrue(server.headers.single().startsWith("GET /api/account-signup-policy "))
            report("ordinary_request_native_socket_timeout=true elapsed_ms=$elapsed delayed_response_ms=13000")
        }
    }

    @Test(timeout=100_000) fun otpTimesOutAtRealLimitAndSameRequestCanRetry() {
        val reply=otpReply().toString()
        NativeServer(listOf(202 to reply,202 to reply),responseDelayMs=77_000,
            allowClientDisconnect=true,delayFirstOnly=true).use { server ->
            val started=SystemClock.elapsedRealtime()
            assertThrows(SocketTimeoutException::class.java){requestOtp(server.base)}
            val elapsed=SystemClock.elapsedRealtime()-started
            assertTrue("Actual OTP timeout must approach 75 seconds",elapsed>=74_500)
            assertTrue("Timeout must precede the 77-second response",elapsed<77_000)
            val recovered=requestOtp(server.base)
            server.finished()
            assertEquals(HANDLE,recovered.enrollmentHandle)
            assertEquals(2,server.headers.size)
            assertEquals(server.bodies[0],server.bodies[1])
            report("otp_native_75s_timeout=true elapsed_ms=$elapsed explicit_same_request_retry_accepted=true automatic_ui_retry_not_tested=true")
        }
    }

    @Test fun malformedOrExpiredOtpResponsesAreRejectedOnNativeHttp() {
        val now=System.currentTimeMillis()
        val cases=listOf(
            "invalid_handle" to otpReply(now).put("enrollment_handle","invalid"),
            "noncanonical_handle" to otpReply(now).put("enrollment_handle",HANDLE.dropLast(1)+"B"),
            "expired_beyond_skew" to otpReply(now).put("expires_at",Instant.ofEpochSecond(now/1000-180).toString())
                .put("resend_available_at",Instant.ofEpochSecond(now/1000-240).toString()),
            "ttl_above_cap_and_skew" to otpReply(now).put("expires_at",Instant.ofEpochSecond(now/1000+2100).toString()),
            "resend_after_expiry" to otpReply(now).put("resend_available_at",Instant.ofEpochSecond(now/1000+600).toString()),
            "invalid_instant" to otpReply(now).put("expires_at","not-a-time"),
            "noncontract_fractional_time" to otpReply(now).put("expires_at",Instant.ofEpochSecond(now/1000+300).toString().replace("Z",".000Z")),
            "wrong_schema" to otpReply(now).put("schema_version","unknown"),
            "unexpected_field" to otpReply(now).put("extra",true))
        for((name,reply) in cases) NativeServer(listOf(202 to reply.toString())).use { server ->
            val error=assertThrows(GatewayAccountHttpException::class.java){requestOtp(server.base)}
            assertEquals("invalid_email_otp_response",error.reason)
            server.finished();assertEquals(1,server.headers.size)
            report("otp_response_rejected=$name")
        }
    }

    private class CountingTransport:GatewaySessionTransport {
        private val actual=HttpUrlConnectionGatewaySessionTransport()
        var posts=0
        override fun postJson(url:String,body:String):GatewayHttpResponse {posts++;return actual.postJson(url,body)}
        override fun postJson(url:String,headers:Map<String,String>,body:String):GatewayHttpResponse {posts++;return actual.postJson(url,headers,body)}
        override fun get(url:String,headers:Map<String,String>)=actual.get(url,headers)
        override fun delete(url:String,headers:Map<String,String>)=actual.delete(url,headers)
        override fun deleteJson(url:String,body:String)=actual.deleteJson(url,body)
    }

    private class NativeServer(responses:List<Pair<Int,String>>,requestedPort:Int=0,
        responseDelayMs:Long=0,allowClientDisconnect:Boolean=false,delayFirstOnly:Boolean=false):AutoCloseable {
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
                if(responseDelayMs>0 && (!delayFirstOnly || headers.size==1)) Thread.sleep(responseDelayMs)
                try { val bytes=body.toByteArray();c.getOutputStream().apply{write("HTTP/1.1 $status Test\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(bytes);flush()}
                } catch(e:java.io.IOException) {if(!allowClientDisconnect) throw e}
            }
        }
        fun finished(){task.get(90,TimeUnit.SECONDS)}
        override fun close(){server.close();executor.shutdownNow()}
    }

    private companion object {
        val HANDLE=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        val CREATED="""{"schema_version":"walksafe.account.v1","actor_id":"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa","account_generation":1,"signup_receipt_sha256":"${"a".repeat(64)}"}"""
    }
}
