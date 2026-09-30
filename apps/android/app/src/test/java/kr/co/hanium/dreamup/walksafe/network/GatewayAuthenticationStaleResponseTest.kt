package kr.co.hanium.dreamup.walksafe.network

import java.io.Closeable
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayAuthenticationStaleResponseTest {
    @Test
    fun exactDetailStringClearsCurrentGenerationOnce() = StaleAuthenticationTestState().use { state ->
        val session = state.session()
        val before = state.publish(session)
        val request = captureGatewayAuthenticationRequest(session)
        assertNotNull(consumeGatewayAuthenticationStaleResponse(request, session, staleResponse()))
        state.assertCleared(before)
        assertNull(consumeGatewayAuthenticationStaleResponse(request, session, staleResponse()))
        assertEquals(1, state.notifications.get())
    }

    @Test
    fun sameActorDifferentSessionCannotBeCapturedOrSubstituted() = StaleAuthenticationTestState().use { state ->
        val current = state.session()
        val before = state.publish(current)
        val other = state.session()
        assertEquals(current.actorId, other.actorId)
        assertNull(captureGatewayAuthenticationRequest(other))
        assertNull(consumeGatewayAuthenticationStaleResponse(before, other, staleResponse()))
        state.assertUnchanged(before)
    }

    @Test
    fun lateResponseCannotClearReplacementSession() = StaleAuthenticationTestState().use { state ->
        val old = state.session()
        val request = state.publish(old)
        val replacement = state.publish(state.session())
        assertNull(consumeGatewayAuthenticationStaleResponse(request, old, staleResponse()))
        state.assertUnchanged(replacement)
    }

    @Test
    fun lateResponseCannotClearRepublishedGenerationOfSameSession() = StaleAuthenticationTestState().use { state ->
        val session = state.session()
        val request = state.publish(session)
        val replacement = state.publish(session)
        assertNull(consumeGatewayAuthenticationStaleResponse(request, session, staleResponse()))
        state.assertUnchanged(replacement)
    }

    @Test
    fun missingCaptureDoesNotAcquireCurrentGeneration() = StaleAuthenticationTestState().use { state ->
        val session = state.session()
        val before = state.publish(session)
        assertNull(consumeGatewayAuthenticationStaleResponse(null, session, staleResponse()))
        assertNull(consumeGatewayAuthenticationStaleResponse(before, null, staleResponse()))
        state.assertUnchanged(before)
    }

    @Test
    fun rootOnlyCodeIsNotConsumed() {
        assertIgnored(listOf(BoundedHttpResponse(401, """{"code":"account_authentication_stale"}""")))
    }

    @Test
    fun codeIsNotTrimmedOrCaseFolded() {
        assertIgnored(listOf(
            """{"detail":{"code":" account_authentication_stale"}}""",
            """{"detail":{"code":"account_authentication_stale "}}""",
            """{"detail":{"code":"Account_authentication_stale"}}""",
        ).map { BoundedHttpResponse(401, it) })
    }

    @Test
    fun codeMustBeAnActualString() {
        assertIgnored(listOf(
            """{"detail":{"code":401}}""",
            """{"detail":{"code":true}}""",
            """{"detail":{"code":null}}""",
            """{"detail":{"code":["account_authentication_stale"]}}""",
            """{"detail":{"code":{"value":"account_authentication_stale"}}}""",
        ).map { BoundedHttpResponse(401, it) })
    }

    @Test
    fun detailMustBeAnActualObject() {
        assertIgnored(listOf(
            """{"detail":"account_authentication_stale"}""",
            """{"detail":[{"code":"account_authentication_stale"}]}""",
            """{"detail":null,"code":"account_authentication_stale"}""",
        ).map { BoundedHttpResponse(401, it) })
    }

    @Test
    fun malformedAndUnknownCodesAreNotConsumed() {
        assertIgnored(listOf(
            "",
            "{",
            """{"detail":{"code":"account_authentication_stale_extra"}}""",
            """{"detail":{"message":"account_authentication_stale"}}""",
        ).map { BoundedHttpResponse(401, it) })
    }

    @Test
    fun exactCodeOnOtherStatusesIsNotConsumed() {
        assertIgnored(listOf(200, 403, 429, 502, 503).map {
            BoundedHttpResponse(it, STALE_AUTHENTICATION_BODY)
        })
    }

    @Test
    fun busyAndOtherAuthenticationErrorsKeepTheSession() {
        assertIgnored(listOf(
            BoundedHttpResponse(503, """{"detail":{"code":"field_session_storage_busy"}}"""),
            BoundedHttpResponse(401, """{"detail":{"code":"invalid_account_credentials"}}"""),
            BoundedHttpResponse(401, """{"detail":{"code":"gateway_upstream_auth_failed"}}"""),
        ))
    }

    @Test
    fun unquotedStaleTokenIsNotAJsonString() {
        assertIgnored(listOf(BoundedHttpResponse(
            401,
            """{"detail":{"code":account_authentication_stale}}""",
        )))
    }

    @Test
    fun trailingGarbageAfterStaleObjectIsRejected() {
        assertIgnored(listOf(BoundedHttpResponse(
            401,
            """{"detail":{"code":"account_authentication_stale"}}garbage""",
        )))
    }

    @Test
    fun standardJsonWhitespaceIsAccepted() {
        assertAccepted(
            " \t\r\n{ \n\"detail\"\t: { \"code\" : \"account_authentication_stale\" }\r}\n\t ",
        )
    }

    @Test
    fun escapedCodeIsDecodedBeforeExactComparison() {
        assertAccepted("""{"detail":{"code":"account_authentication_\u0073tale"}}""")
    }

    @Test
    fun escapedDetailAndCodeKeysAreAccepted() {
        assertAccepted("""{"\u0064etail":{"co\u0064e":"account_authentication_stale"}}""")
    }

    @Test
    fun exponentNumbersInUnknownFieldsAreValidJson() {
        assertAccepted(
            """{"detail":{"code":"account_authentication_stale"},"positive":1e2,"negative":-2.5E-3,"zero":0E+0}""",
        )
    }

    @Test
    fun unknownNestedContainersDoNotInheritAdminDepthLimit() {
        assertAccepted(
            """{"detail":{"code":"account_authentication_stale"},"extra":""" +
                "[".repeat(32) + """{"valid":true}""" + "]".repeat(32) + "}",
        )
    }

    @Test
    fun duplicateCodeKeysAreRejectedRegardlessOfOrder() {
        assertIgnored(listOf(
            """{"detail":{"code":"other","code":"account_authentication_stale"}}""",
            """{"detail":{"code":"account_authentication_stale","code":"other"}}""",
            """{"detail":{"code":"account_authentication_stale","code":"account_authentication_stale"}}""",
        ).map { BoundedHttpResponse(401, it) })
    }

    @Test
    fun duplicateDetailKeysAreRejectedRegardlessOfOrder() {
        assertIgnored(listOf(
            """{"detail":null,"detail":{"code":"account_authentication_stale"}}""",
            """{"detail":{"code":"account_authentication_stale"},"detail":null}""",
        ).map { BoundedHttpResponse(401, it) })
    }

    @Test
    fun escapedDuplicateCodeKeyIsRejected() {
        assertIgnored(listOf(BoundedHttpResponse(
            401,
            """{"detail":{"code":"account_authentication_stale","co\u0064e":"account_authentication_stale"}}""",
        )))
    }

    @Test
    fun escapedDuplicateDetailKeyIsRejected() {
        assertIgnored(listOf(BoundedHttpResponse(
            401,
            """{"detail":{"code":"account_authentication_stale"},"\u0064etail":{"code":"account_authentication_stale"}}""",
        )))
    }

    @Test
    fun duplicateKeysInUnknownNestedObjectsAreAlsoRejected() {
        assertIgnored(listOf(BoundedHttpResponse(
            401,
            """{"detail":{"code":"account_authentication_stale"},"extra":[{"id":1,"\u0069d":2}]}""",
        )))
    }

    @Test
    fun documentRootMustBeAnObject() {
        assertIgnored(listOf(
            """[{"detail":{"code":"account_authentication_stale"}}]""",
            """"account_authentication_stale"""",
            "null",
            "true",
            "401",
        ).map { BoundedHttpResponse(401, it) })
    }

    @Test
    fun consecutiveJsonDocumentsAreRejected() {
        assertIgnored(listOf("{}", "[]", "null", STALE_AUTHENTICATION_BODY).map {
            BoundedHttpResponse(401, STALE_AUTHENTICATION_BODY + " " + it)
        })
    }

    @Test
    fun objectTrailingCommaIsRejectedAtEveryLevel() {
        assertIgnored(listOf(
            """{"detail":{"code":"account_authentication_stale",}}""",
            """{"detail":{"code":"account_authentication_stale"},}""",
            """{"detail":{"code":"account_authentication_stale"},"extra":{"id":1,}}""",
        ).map { BoundedHttpResponse(401, it) })
    }

    @Test
    fun arrayTrailingCommaIsRejected() {
        assertIgnored(listOf(
            """{"detail":{"code":"account_authentication_stale"},"extra":[1,]}""",
            """{"detail":{"code":"account_authentication_stale"},"extra":[{"id":1},]}""",
        ).map { BoundedHttpResponse(401, it) })
    }

    @Test
    fun repeatedOrMissingSeparatorsAreRejected() {
        assertIgnored(listOf(
            """{"detail":{"code":"account_authentication_stale",,"id":1}}""",
            """{"detail"::{"code":"account_authentication_stale"}}""",
            """{"detail":{"code":"account_authentication_stale"},"extra":[1,,2]}""",
            """{"detail":{"code":"account_authentication_stale"},"extra":[,]}""",
            """{"detail":{"code":"account_authentication_stale"},"extra":[1 2]}""",
            """{"detail":{"code":"account_authentication_stale"} "extra":1}""",
        ).map { BoundedHttpResponse(401, it) })
    }

    @Test
    fun singleQuotedKeysAndValuesAreRejected() {
        assertIgnored(listOf(
            """{'detail':{'code':'account_authentication_stale'}}""",
            """{"detail":{"code":'account_authentication_stale'}}""",
            """{"detail":{"code":"account_authentication_stale"},"extra":'ignored'}""",
        ).map { BoundedHttpResponse(401, it) })
    }

    @Test
    fun commentsAreRejectedOutsideStrings() {
        assertIgnored(listOf(
            "/* prefix */" + STALE_AUTHENTICATION_BODY,
            STALE_AUTHENTICATION_BODY + "// suffix",
            """{"detail":/* comment */{"code":"account_authentication_stale"}}""",
            """{"detail":{"code":"account_authentication_stale"},"extra":[1,/* gap */2]}""",
            STALE_AUTHENTICATION_BODY + "# suffix",
        ).map { BoundedHttpResponse(401, it) })
    }

    @Test
    fun invalidNumberTokensInUnknownFieldsAreRejected() {
        assertIgnored(listOf("01", "-01", "+1", ".1", "1.", "1e", "1e+", "--1", "NaN", "Infinity").map {
            BoundedHttpResponse(
                401,
                """{"detail":{"code":"account_authentication_stale"},"extra":""" + it + "}",
            )
        })
    }

    @Test
    fun invalidEscapesAndNonAsciiUnicodeEscapeDigitsAreRejected() {
        assertIgnored(listOf(
            """{"detail":{"code":"account_authentication_stale"},"extra":"\q"}""",
            """{"detail":{"code":"account_authentication_stale"},"extra":"\u12G4"}""",
            """{"detail":{"code":"account_authentication_stale"},"extra":"\u123"}""",
            "{\"detail\":{\"code\":\"account_authentication_stale\"},\"extra\":\"\\u" +
                "\uFF10".repeat(4) + "\"}",
        ).map { BoundedHttpResponse(401, it) })
    }

    @Test
    fun rawControlCharactersInsideStringsAreRejected() {
        assertIgnored(listOf('\n', '\r', '\t', '\u0000').map {
            BoundedHttpResponse(
                401,
                "{\"detail\":{\"code\":\"account_authentication_stale\"},\"extra\":\"a" + it + "b\"}",
            )
        })
    }

    @Test
    fun onlyTheFourJsonWhitespaceCharactersAreAccepted() {
        assertIgnored(listOf('\u000b', '\u000c', '\u00a0').map {
            BoundedHttpResponse(401, it + STALE_AUTHENTICATION_BODY)
        })
    }

    @Test
    fun validStringEscapesAndUnknownScalarValuesAreAccepted() {
        assertAccepted(
            """{"detail":{"code":"account_authentication_stale"},"extra":{"text":"quote:\" slash:\/ backslash:\\ tab:\t // /* text */","yes":true,"no":false,"none":null,"object":{},"array":[]}}""",
        )
    }

    private fun assertAccepted(body: String) = StaleAuthenticationTestState().use { state ->
        val session = state.session()
        val before = state.publish(session)
        assertNotNull(consumeGatewayAuthenticationStaleResponse(
            captureGatewayAuthenticationRequest(session),
            session,
            BoundedHttpResponse(401, body),
        ))
        state.assertCleared(before)
    }

    private fun assertIgnored(responses: List<BoundedHttpResponse>) = StaleAuthenticationTestState().use { state ->
        val session = state.session()
        val before = state.publish(session)
        val request = captureGatewayAuthenticationRequest(session)
        responses.forEach {
            assertNull(consumeGatewayAuthenticationStaleResponse(request, session, it))
            state.assertUnchanged(before)
        }
    }

    private fun staleResponse() = BoundedHttpResponse(401, STALE_AUTHENTICATION_BODY)
}

internal const val STALE_AUTHENTICATION_BODY =
    """{"detail":{"code":"account_authentication_stale","message":"fixed test message"}}"""

internal enum class StaleAuthenticationResponseTiming {
    CURRENT,
    AFTER_CREATION,
    IN_FLIGHT_REPLACEMENT,
    CANCELLED,
}

/** Test-only coordinator ownership; it never attaches an Android activity. */
internal class StaleAuthenticationTestState : Closeable {
    private val owner = Any()
    val notifications = AtomicInteger(0)

    init {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
        GatewaySessionProcessCoordinator.attach(owner) { notifications.incrementAndGet() }
        notifications.set(0)
    }

    fun session(baseUrl: String = "https://gateway.example.test"): GatewayFieldSession =
        GatewayFieldSession.verified(
            gatewayBaseUrl = baseUrl,
            actorId = "stale-auth-tester",
            cookiePair = "walksafe_field_session=v2.test.session.cookie",
            expiresAtEpochMs = Long.MAX_VALUE,
        )

    fun publish(session: GatewayFieldSession): GatewaySessionProcessSnapshot {
        val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
        check(GatewaySessionProcessCoordinator.publishVerified(
            operation,
            session,
            FirstRunOnboardingPolicy.initial(),
        ))
        notifications.set(0)
        return GatewaySessionProcessCoordinator.snapshot()
    }

    fun assertUnchanged(expected: GatewaySessionProcessSnapshot) {
        val actual = GatewaySessionProcessCoordinator.snapshot()
        assertSame(expected.session, actual.session)
        assertEquals(expected.generation, actual.generation)
        assertEquals(0, notifications.get())
        val session = requireNotNull(actual.session)
        assertTrue(session.isUsableFor(session.actorId))
    }

    fun assertCleared(before: GatewaySessionProcessSnapshot) {
        val actual = GatewaySessionProcessCoordinator.snapshot()
        assertNull(actual.session)
        assertEquals(before.generation + 1L, actual.generation)
        assertEquals(1, notifications.get())
        val session = requireNotNull(before.session)
        assertFalse(session.isUsableFor(session.actorId))
    }

    override fun close() {
        GatewaySessionProcessCoordinator.detach(owner)
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }
}

/** Existing local HTTP fixture, with deterministic generation changes before response bytes. */
internal fun exerciseStaleAuthenticationHttpCall(
    timing: StaleAuthenticationResponseTiming,
    errorType: Class<out Throwable>,
    statusCode: Int = 401,
    body: String = STALE_AUTHENTICATION_BODY,
    clearExpected: Boolean = timing == StaleAuthenticationResponseTiming.CURRENT,
    createCall: (GatewayFieldSession) -> CancellableNetworkCall<*>,
): Throwable = StaleAuthenticationTestState().use { state ->
    val expected = AtomicReference<GatewaySessionProcessSnapshot>()
    val activeCall = AtomicReference<CancellableNetworkCall<*>>()
    val origin = AtomicReference<String>()
    val requests = AtomicInteger(0)
    LocalHttpTestServer { _, socket ->
        requests.incrementAndGet()
        if (timing == StaleAuthenticationResponseTiming.IN_FLIGHT_REPLACEMENT) {
            expected.set(state.publish(state.session(origin.get())))
        }
        if (timing == StaleAuthenticationResponseTiming.CANCELLED) {
            activeCall.get().cancel()
        }
        socket.writeFixedResponse(
            statusCode,
            body.toByteArray(Charsets.UTF_8),
            headers = mapOf("Retry-After" to "7"),
        )
    }.use { server ->
        origin.set(server.baseUrl)
        val session = state.session(server.baseUrl)
        expected.set(state.publish(session))
        val call = createCall(session)
        activeCall.set(call)
        if (timing == StaleAuthenticationResponseTiming.AFTER_CREATION) {
            // Keep the credential usable so a real response exposes execute-time recapture.
            expected.set(state.publish(session))
        }
        val expectedType = if (timing == StaleAuthenticationResponseTiming.CANCELLED) {
            CancellationException::class.java
        } else {
            errorType
        }
        val failure = assertThrows(expectedType) { call.execute() }
        if (clearExpected) state.assertCleared(expected.get()) else state.assertUnchanged(expected.get())
        assertEquals(1, requests.get())
        failure
    }
}
