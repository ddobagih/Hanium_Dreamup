package kr.co.hanium.dreamup.walksafe.network

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * FP-010 첫 실행 4·5·7·8단계 클라이언트.
 *
 * 이메일은 출시 전까지 휴대전화 확인을 대신하는 한시적 대체다(product/decisions.md, 2026-08-30).
 * 이 네 단계는 계정이 생기기 전에 일어나므로 Gateway 세션을 붙이지 않는다.
 *
 * 앱이 보관하는 값은 서버가 발급한 불투명 핸들과 영수증뿐이다. 이메일 주소와 인증코드는 요청에만
 * 싣고 저장하지 않으며, 핸들을 그것들에서 파생하지 않는다. 그래서 나중에 채널이 SMS 로 바뀌어도
 * 이 계약은 그대로다.
 */

private const val MAX_RESPONSE_BYTES = 4096
private const val CONNECT_TIMEOUT_MS = 8_000
private const val READ_TIMEOUT_MS = 12_000

private val SUBMISSION_HANDLE = Regex("^onb_[0-9a-f]{32}$")
private val ACTOR_BINDING = Regex("^actor_[0-9a-f]{32}$")
private val RECEIPT_SHA256 = Regex("^[0-9a-f]{64}$")

data class FirstRunSubmissionResult(
    val submissionHandle: String,
    val receiptSha256: String,
    /** 메일 발송 수단이 없는 개발 환경에서만 서버가 함께 돌려준다. */
    val verificationCode: String?,
)

data class FirstRunStageResult(
    val receiptSha256: String,
    val actorBinding: String?,
)

class FirstRunSignupHttpException(val statusCode: Int, val code: String?) :
    RuntimeException("first-run signup failed with $statusCode ${code.orEmpty()}")

class FirstRunSignupProtocolException : RuntimeException("first-run signup response is invalid")

class AndroidFirstRunSignupClient {

    fun submitEmailCall(
        gatewayBaseUrl: String,
        email: String,
    ): CancellableNetworkCall<FirstRunSubmissionResult> =
        call(gatewayBaseUrl, "/api/first-run/signups", JSONObject().put("email", email)) { body ->
            val handle = body.optString("submission_handle")
            val receipt = body.optString("receipt_sha256")
            if (!SUBMISSION_HANDLE.matches(handle) || !RECEIPT_SHA256.matches(receipt)) {
                throw FirstRunSignupProtocolException()
            }
            FirstRunSubmissionResult(
                submissionHandle = handle,
                receiptSha256 = receipt,
                verificationCode = body.optString("verification_code").takeIf { it.isNotEmpty() },
            )
        }

    fun verifyEmailCall(
        gatewayBaseUrl: String,
        submissionHandle: String,
        code: String,
    ): CancellableNetworkCall<FirstRunStageResult> =
        stageCall(
            gatewayBaseUrl,
            "/api/first-run/signups/verify",
            JSONObject().put("submission_handle", submissionHandle).put("code", code),
            requireActorBinding = false,
        )

    fun activateCall(
        gatewayBaseUrl: String,
        submissionHandle: String,
    ): CancellableNetworkCall<FirstRunStageResult> =
        stageCall(
            gatewayBaseUrl,
            "/api/first-run/signups/activate",
            JSONObject().put("submission_handle", submissionHandle),
            requireActorBinding = false,
        )

    fun loginCall(
        gatewayBaseUrl: String,
        submissionHandle: String,
    ): CancellableNetworkCall<FirstRunStageResult> =
        stageCall(
            gatewayBaseUrl,
            "/api/first-run/signups/login",
            JSONObject().put("submission_handle", submissionHandle),
            requireActorBinding = true,
        )

    private fun stageCall(
        gatewayBaseUrl: String,
        path: String,
        requestBody: JSONObject,
        requireActorBinding: Boolean,
    ): CancellableNetworkCall<FirstRunStageResult> =
        call(gatewayBaseUrl, path, requestBody) { body ->
            val receipt = body.optString("receipt_sha256")
            if (!RECEIPT_SHA256.matches(receipt)) throw FirstRunSignupProtocolException()
            val binding = body.optString("actor_binding").takeIf { it.isNotEmpty() }
            if (requireActorBinding && (binding == null || !ACTOR_BINDING.matches(binding))) {
                throw FirstRunSignupProtocolException()
            }
            FirstRunStageResult(receiptSha256 = receipt, actorBinding = binding)
        }

    private fun <T> call(
        gatewayBaseUrl: String,
        path: String,
        requestBody: JSONObject,
        parse: (JSONObject) -> T,
    ): CancellableNetworkCall<T> = cancellableHttpCall { cancellation ->
        val endpoint = gatewayBaseUrl.trimEnd('/') + path
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        cancellation.attach(connection)
        try {
            connection.apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doInput = true
                doOutput = true
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Connection", "close")
            }
            connection.outputStream.use { output ->
                cancellation.attach(output)
                try {
                    output.write(requestBody.toString().toByteArray(Charsets.UTF_8))
                    output.flush()
                } finally {
                    cancellation.detach(output)
                }
            }
            val response = connection.readBoundedResponse(MAX_RESPONSE_BYTES, cancellation)
            if (response.statusCode !in 200..299) {
                throw FirstRunSignupHttpException(
                    response.statusCode,
                    runCatching {
                        JSONObject(response.body).optJSONObject("detail")?.optString("code")
                    }.getOrNull(),
                )
            }
            parse(runCatching { JSONObject(response.body) }.getOrElse {
                throw FirstRunSignupProtocolException()
            })
        } finally {
            cancellation.detach(connection)
            connection.disconnect()
        }
    }
}
