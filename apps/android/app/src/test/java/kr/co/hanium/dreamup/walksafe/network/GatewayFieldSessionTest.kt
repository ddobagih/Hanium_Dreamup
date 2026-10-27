package kr.co.hanium.dreamup.walksafe.network

import java.time.Instant
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayFieldSessionTest {
    @Before
    fun resetProcessRefreshFence() {
        resetGatewayRefreshProofFenceForTests()
        GatewayCapacityProcessState.resetForTests()
    }

    @Test
    fun namedLoginBindsActorAndExposesOnlyTheDerivedCookie() {
        val transport = FakeTransport()
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)

        val session = client.login(
            gatewayBaseUrl = "https://field.example",
            actorId = "tester-01",
            token = ACCOUNT_TOKEN,
            nowEpochMs = 1_000L,
        )

        assertEquals("https://field.example/api/field-session", transport.postedUrl)
        assertEquals("tester-01", JSONObject(transport.postedBody!!).getString("actor_id"))
        assertEquals(ACCOUNT_TOKEN, JSONObject(transport.postedBody!!).getString("token"))
        assertFalse(JSONObject(transport.postedBody!!).has("device_id"))
        assertFalse(JSONObject(transport.postedBody!!).has("purpose"))
        assertEquals(setOf(GatewayFieldSession.COOKIE_HEADER), transport.statusHeaders!!.keys)
        assertEquals("tester-01", session.actorId)
        assertEquals(GatewaySessionScope.GENERAL, session.sessionScope)
        assertTrue(session.isUsableFor("tester-01", nowEpochMs = 2_000L))
        assertFalse(session.isUsableFor("attacker", nowEpochMs = 2_000L))
        assertEquals(setOf(GatewayFieldSession.COOKIE_HEADER), session.requestHeaders(2_000L).keys)
        assertTrue(session.requestHeaders(2_000L).getValue(GatewayFieldSession.COOKIE_HEADER).startsWith("walksafe_field_session="))
        assertFalse(session.requestHeaders(2_000L).values.any { it.contains(ACCOUNT_TOKEN) })
        assertNull(session.requestHeaders(2_000L)["x-walksafe-field-test-token"])
        assertNull(session.requestHeaders(2_000L)["x-walksafe-actor-id"])
        assertNull(session.persistenceSnapshotOrNull())

        val cookieHeaders = session.requestHeaders(2_000L)
        client.logout(session)
        assertEquals(cookieHeaders, transport.deletedHeaders)
        assertFalse(session.isUsableFor("tester-01", nowEpochMs = 2_000L))
        assertThrows(IllegalStateException::class.java) { session.requestHeaders() }
    }

    @Test
    fun passwordLoginPreservesRateLimitAndRetryAfterWithoutFollowupRequests() {
        val transport = FakeTransport(
            loginResponse = GatewayHttpResponse(
                statusCode = 429,
                responseBody = """{"code":"gateway_login_rate_limited"}""",
                headers = mapOf("Retry-After" to "25"),
            ),
        )

        val error = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true).loginWithPassword(
                gatewayBaseUrl = "http://127.0.0.1:8081",
                email = "walker@example.test",
                password = "Valid-password-123!",
                rememberMe = false,
                deviceId = DEVICE_ID,
                nowEpochMs = NOW,
            )
        }

        assertEquals(429, error.statusCode)
        assertEquals("account_password_login_failed", error.reason)
        assertEquals(25_000L, error.retryAfterMs)
        assertEquals("http://127.0.0.1:8081/api/field-session", transport.postedUrl)
        assertEquals("password", JSONObject(transport.postedBody!!).getString("grant_type"))
        assertEquals(1, transport.postedBodies.size)
        assertEquals(0, transport.getCount)
        assertEquals(0, transport.deleteCount)
    }

    @Test
    fun passwordLoginBoundsMaximumServerTtlAtResponseTimeWithClockSkew() {
        val requestNow = 1_000_000L
        val responseElapsedMs = 1_000L
        val serverClockAheadMs = 300_000L
        val maximumExpiresAtSeconds =
            (requestNow + responseElapsedMs + serverClockAheadMs) / 1_000L + 43_200L
        val actorId = "123e4567-e89b-42d3-a456-426614174000"
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val encodedActor = encoder.encodeToString(actorId.toByteArray(Charsets.UTF_8))
        val encodedDevice = encoder.encodeToString(DEVICE_ID.toByteArray(Charsets.UTF_8))

        fun transportFor(expiresAtSeconds: Long): FakeTransport {
            val cookie = listOf(
                "v7", encodedActor, "1", "1", encodedDevice, "general",
                expiresAtSeconds.toString(), "s".repeat(32), "h".repeat(43),
            ).joinToString(".")
            return FakeTransport(
                loginResponse = GatewayHttpResponse(
                    statusCode = 200,
                    responseBody = """{"session_scope":"general"}""",
                    headers = mapOf(
                        "Set-Cookie" to
                            "walksafe_field_session=$cookie; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200",
                    ),
                ),
                statusBody =
                    """{"required":true,"authenticated":true,"actor_id":"$actorId","session_scope":"general"}""",
            )
        }

        fun clientFor(transport: FakeTransport): GatewayFieldSessionClient {
            val clock = listOf(0L, responseElapsedMs * 1_000_000L).iterator()
            return GatewayFieldSessionClient(transport, elapsedRealtimeNanos = { clock.next() }, allowDebugAccountOrigin = true)
        }

        val acceptedTransport = transportFor(maximumExpiresAtSeconds)
        val session = clientFor(acceptedTransport).loginWithPassword(
            gatewayBaseUrl = "http://127.0.0.1:8081",
            email = "walker@example.test",
            password = "Valid-password-123!",
            rememberMe = true,
            deviceId = DEVICE_ID,
            nowEpochMs = requestNow,
        )

        assertEquals(maximumExpiresAtSeconds * 1_000L, session.accessExpiresAtEpochMs)
        assertTrue(session.isUsableFor(actorId, requestNow + responseElapsedMs))
        assertEquals(1, acceptedTransport.getCount)

        val rejectedTransport = transportFor(maximumExpiresAtSeconds + 1L)
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            clientFor(rejectedTransport).loginWithPassword(
                gatewayBaseUrl = "http://127.0.0.1:8081",
                email = "walker@example.test",
                password = "Valid-password-123!",
                rememberMe = true,
                deviceId = DEVICE_ID,
                nowEpochMs = requestNow,
            )
        }
        assertEquals("invalid_gateway_cookie", error.reason)
        assertEquals(0, rejectedTransport.getCount)
    }

    @Test
    fun longLivedLoginSeparatesAccessAndRefreshCredentialsAndBindsTheDevice() {
        val transport = FakeTransport(
            loginResponse = longLivedResponse(
                rotation = 0L,
                accessToken = ACCESS_TOKEN_0,
                refreshToken = REFRESH_TOKEN_0,
            ),
        )
        val session = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true).login(
            gatewayBaseUrl = "https://field.example",
            actorId = "tester-01",
            token = ACCOUNT_TOKEN,
            nowEpochMs = NOW,
            enableLongLivedSession = true,
            deviceId = DEVICE_ID,
        )

        val request = JSONObject(transport.postedBodies.single())
        assertEquals(setOf("actor_id", "token", "device_id"), jsonKeys(request))
        assertEquals(DEVICE_ID, request.getString("device_id"))
        assertEquals(0, transport.getCount)
        assertEquals(GatewaySessionVerificationState.VERIFIED, session.verificationState)
        assertEquals(DEVICE_ID, session.deviceId)
        assertEquals(FAMILY_ID, session.familyId)
        assertEquals(0L, session.rotation)
        assertEquals(ACCESS_EXPIRES_AT, session.accessExpiresAtEpochMs)
        assertEquals(IDLE_EXPIRES_AT, session.idleExpiresAtEpochMs)
        assertEquals(ABSOLUTE_EXPIRES_AT, session.absoluteExpiresAtEpochMs)
        assertTrue(session.isUsableFor("tester-01", NOW + 1L))
        val headers = session.requestHeaders(NOW + 1L)
        assertTrue(headers.getValue(GatewayFieldSession.COOKIE_HEADER).contains(ACCESS_TOKEN_0))
        assertFalse(headers.values.any { it.contains(REFRESH_TOKEN_0) })
        val persisted = session.persistenceSnapshotOrNull()!!
        assertFalse(persisted.toString().contains(ACCESS_TOKEN_0))
        assertFalse(persisted.toString().contains(REFRESH_TOKEN_0))
    }

    @Test
    fun longLivedLoginBoundsMaximumServerTtlAtResponseTime() {
        val requestNow = 1_000_000L
        val responseElapsedMs = 1_000L
        val maximumServerExpiry = requestNow + responseElapsedMs + 3_600_000L + 300_000L
        val idleExpiry = maximumServerExpiry + 1_000L
        val absoluteExpiry = idleExpiry + 1_000L

        fun loginFor(serverExpiry: Long): GatewayFieldSession {
            val clock = listOf(0L, responseElapsedMs * 1_000_000L).iterator()
            val client = GatewayFieldSessionClient(
                FakeTransport(
                    loginResponse = longLivedResponse(
                        rotation = 0L,
                        accessToken = ACCESS_TOKEN_0,
                        refreshToken = REFRESH_TOKEN_0,
                        accessExpiresAtEpochMs = serverExpiry,
                        idleExpiresAtEpochMs = idleExpiry,
                        absoluteExpiresAtEpochMs = absoluteExpiry,
                        cookieMaxAgeSeconds = 3_600L,
                    ),
                ),
                elapsedRealtimeNanos = { clock.next() }, allowDebugAccountOrigin = true)
            return client.login(
                gatewayBaseUrl = "https://field.example",
                actorId = "tester-01",
                token = ACCOUNT_TOKEN,
                nowEpochMs = requestNow,
                enableLongLivedSession = true,
                deviceId = DEVICE_ID,
            )
        }

        val accepted = loginFor(maximumServerExpiry)
        assertEquals(maximumServerExpiry, accepted.serverAccessExpiresAtEpochMs)
        assertEquals(requestNow + responseElapsedMs + 3_600_000L, accepted.accessExpiresAtEpochMs)
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            loginFor(maximumServerExpiry + 1L)
        }
        assertEquals("gateway_session_binding_failed", error.reason)
    }

    @Test
    fun longLivedLoginIsOptInAndDefaultLoginDoesNotSendADeviceIdentifier() {
        val transport = FakeTransport(
            loginResponse = longLivedResponse(
                rotation = 0L,
                accessToken = ACCESS_TOKEN_0,
                refreshToken = REFRESH_TOKEN_0,
            ),
        )

        val error = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true).login(
                gatewayBaseUrl = "https://field.example",
                actorId = "tester-01",
                token = ACCOUNT_TOKEN,
                nowEpochMs = NOW,
            )
        }

        assertEquals("gateway_long_lived_login_not_enabled", error.reason)
        assertFalse(JSONObject(transport.postedBodies.single()).has("device_id"))
    }

    @Test
    fun longLivedLoginRequiresAnExplicitInstallScopedDeviceIdentifier() {
        val transport = FakeTransport(
            loginResponse = longLivedResponse(
                rotation = 0L,
                accessToken = ACCESS_TOKEN_0,
                refreshToken = REFRESH_TOKEN_0,
            ),
        )

        val error = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true).login(
                gatewayBaseUrl = "https://field.example",
                actorId = "tester-01",
                token = ACCOUNT_TOKEN,
                nowEpochMs = NOW,
                enableLongLivedSession = true,
            )
        }

        assertEquals("invalid_gateway_device", error.reason)
        assertTrue(transport.postedBodies.isEmpty())
    }

    @Test
    fun deletionRecoveryLoginSendsExactPurposeAndBindsExactServerScope() {
        val transport = FakeTransport(
            loginResponse = GatewayHttpResponse(
                statusCode = 200,
                responseBody =
                    """{"session_scope":"account_deletion_recovery"}""",
                headers = mapOf("Set-Cookie" to COOKIE_ATTRIBUTES),
            ),
            statusBody =
                """{"required":true,"authenticated":true,"actor_id":"tester-01","session_scope":"account_deletion_recovery"}""",
        )

        val session = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true).login(
            gatewayBaseUrl = "https://field.example",
            actorId = "tester-01",
            token = ACCOUNT_TOKEN,
            nowEpochMs = 1_000L,
            sessionScope = GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
        )

        val request = JSONObject(requireNotNull(transport.postedBody))
        assertEquals(setOf("actor_id", "token", "purpose"), jsonKeys(request))
        assertEquals(
            GatewaySessionScope.ACCOUNT_DELETION_RECOVERY.wireValue,
            request.getString("purpose"),
        )
        assertEquals(GatewaySessionScope.ACCOUNT_DELETION_RECOVERY, session.sessionScope)
        assertNull(session.persistenceSnapshotOrNull())
    }

    @Test
    fun newShortLoginRejectsAResponseScopeThatDoesNotExactlyMatch() {
        val transport = FakeTransport(
            loginResponse = GatewayHttpResponse(
                statusCode = 200,
                responseBody = """{"session_scope":"general"}""",
                headers = mapOf("Set-Cookie" to COOKIE_ATTRIBUTES),
            ),
        )

        val error = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true).login(
                gatewayBaseUrl = "https://field.example",
                actorId = "tester-01",
                token = ACCOUNT_TOKEN,
                sessionScope = GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
            )
        }

        assertEquals("gateway_session_scope_binding_failed", error.reason)
        assertEquals(0, transport.getCount)
    }

    @Test
    fun deletionRecoveryLoginRejectsMissingOrDifferentServerScopeAndClearsCookie() {
        listOf(
            """{"required":true,"authenticated":true,"actor_id":"tester-01"}""",
            """{"required":true,"authenticated":true,"actor_id":"tester-01","session_scope":"general"}""",
        ).forEach { statusBody ->
            val transport = FakeTransport(statusBody = statusBody)

            val error = assertThrows(GatewaySessionHttpException::class.java) {
                GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true).login(
                    gatewayBaseUrl = "https://field.example",
                    actorId = "tester-01",
                    token = ACCOUNT_TOKEN,
                    sessionScope = GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
                )
            }

            assertEquals("gateway_actor_binding_failed", error.reason)
            assertEquals(1, transport.deleteCount)
        }
    }

    @Test
    fun deletionRecoveryScopeCannotRequestLongLivedCredentials() {
        val transport = FakeTransport()

        val error = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true).login(
                gatewayBaseUrl = "https://field.example",
                actorId = "tester-01",
                token = ACCOUNT_TOKEN,
                enableLongLivedSession = true,
                deviceId = DEVICE_ID,
                sessionScope = GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
            )
        }

        assertEquals("gateway_recovery_long_lived_not_allowed", error.reason)
        assertTrue(transport.postedBodies.isEmpty())
    }

    @Test
    fun deletionRecoveryRevalidationFailsClosedWhenServerScopeDrifts() {
        val transport = FakeTransport(
            statusBody =
                """{"required":true,"authenticated":true,"actor_id":"tester-01","session_scope":"account_deletion_recovery"}""",
        )
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val session = client.login(
            gatewayBaseUrl = "https://field.example",
            actorId = "tester-01",
            token = ACCOUNT_TOKEN,
            nowEpochMs = 1_000L,
            sessionScope = GatewaySessionScope.ACCOUNT_DELETION_RECOVERY,
        )
        transport.statusBody =
            """{"required":true,"authenticated":true,"actor_id":"tester-01","session_scope":"general"}"""

        val result = client.revalidate(session, "tester-01", nowEpochMs = 2_000L)

        assertEquals(GatewaySessionRevalidationStatus.NOT_READY, result.status)
    }

    @Test
    fun rejectsCookieWhenStatusActorDoesNotMatchAndClearsItRemotely() {
        val transport = FakeTransport(
            statusBody = """{"required":true,"authenticated":true,"actor_id":"other-actor"}""",
        )
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true).login("https://field.example", "tester-01", ACCOUNT_TOKEN)
        }

        assertEquals("gateway_actor_binding_failed", error.reason)
        assertEquals(1, transport.deleteCount)
    }

    @Test
    fun httpsLoginRequiresSecureBoundedCookieAndNamedCredential() {
        val insecureCookie = FakeTransport(
            setCookie = COOKIE_ATTRIBUTES.replace("; Secure", ""),
        )
        val cookieError = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(insecureCookie, allowDebugAccountOrigin = true).login("https://field.example", "tester-01", ACCOUNT_TOKEN)
        }

        assertEquals("invalid_gateway_cookie", cookieError.reason)
        val reflectedCredential = FakeTransport(
            setCookie = "walksafe_field_session=$ACCOUNT_TOKEN; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200; Secure",
        )
        val reflectedError = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(reflectedCredential, allowDebugAccountOrigin = true).login("https://field.example", "tester-01", ACCOUNT_TOKEN)
        }
        assertEquals("raw_credential_in_gateway_cookie", reflectedError.reason)
        assertNull(GatewayCredentialPolicy.normalizedActorIdOrNull("field-shared"))
        assertNull(GatewayCredentialPolicy.normalizedActorIdOrNull("anonymous"))
        assertNull(GatewayCredentialPolicy.normalizedTokenOrNull("short"))
        assertNull(
            GatewayCredentialPolicy.normalizedFamilyIdOrNull(
                "family.identifier.with.dots",
            ),
        )
        assertNull(
            GatewayCredentialPolicy.normalizedRefreshTokenOrNull(
                "r".repeat(47),
            ),
        )
        assertEquals(
            "r".repeat(48),
            GatewayCredentialPolicy.normalizedRefreshTokenOrNull(
                "r".repeat(48),
            ),
        )
    }

    @Test
    fun revalidationUsesTheExistingStatusEndpointAndExactActorBinding() {
        val transport = FakeTransport()
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val session = client.login(
            "https://field.example",
            "tester-01",
            ACCOUNT_TOKEN,
            nowEpochMs = 1_000L,
        )
        transport.getCount = 0

        val result = client.revalidate(session, "tester-01", nowEpochMs = 2_000L)

        assertEquals(GatewaySessionRevalidationStatus.READY, result.status)
        assertEquals(1, transport.getCount)
        assertEquals("https://field.example/api/field-session", transport.lastGetUrl)
        assertTrue(session.isUsableFor("tester-01", nowEpochMs = 2_000L))
    }

    @Test
    fun revalidationUpdatesCapacityWithoutCouplingItToSessionReadiness() {
        val transport = FakeTransport(
            statusBody = capacityStatus(
                version = 7L,
                observedAt = "2026-08-25T00:00:00Z",
                expiresAt = "2026-08-25T01:00:00Z",
                level = GatewayCapacityLevel.HOLD_NEW_RAW_COLLECTION_SESSIONS,
            ),
        )
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val session = client.login(
            "https://field.example",
            "tester-01",
            ACCOUNT_TOKEN,
            nowEpochMs = CAPACITY_NOW_MS,
        )

        val result = client.revalidate(
            session,
            "tester-01",
            nowEpochMs = CAPACITY_NOW_MS + 1L,
        )

        assertEquals(GatewaySessionRevalidationStatus.READY, result.status)
        assertEquals(GatewayCapacityAvailability.AVAILABLE, result.capacityAvailability)
        assertEquals(
            GatewayCapacityUpdateDisposition.ACCEPTED,
            result.capacityUpdate?.disposition,
        )
        assertEquals(
            7L,
            GatewayCapacityProcessState.admission(
                Instant.ofEpochMilli(CAPACITY_NOW_MS + 1L),
            ).snapshot?.version,
        )
        assertTrue(session.isUsableFor("tester-01", CAPACITY_NOW_MS + 1L))
    }

    @Test
    fun missingMalformedAndExpiredCapacityKeepTheVerifiedSessionReady() {
        val cases = listOf(
            """{"required":true,"authenticated":true,"actor_id":"tester-01"}""" to
                GatewayCapacityAvailability.MISSING,
            capacityStatus(
                version = 1L,
                observedAt = "2026-08-25T00:00:00Z",
                expiresAt = "2026-08-25T01:00:00Z",
                level = GatewayCapacityLevel.NORMAL,
                versionOverride = "\"1\"",
            ) to GatewayCapacityAvailability.MALFORMED,
            capacityStatus(
                version = 2L,
                observedAt = "2026-08-24T23:00:00Z",
                expiresAt = "2026-08-25T00:05:00Z",
                level = GatewayCapacityLevel.NORMAL,
            ) to GatewayCapacityAvailability.EXPIRED,
        )

        cases.forEach { (statusBody, expectedAvailability) ->
            GatewayCapacityProcessState.resetForTests()
            val transport = FakeTransport(statusBody = statusBody)
            val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
            val session = client.login(
                "https://field.example",
                "tester-01",
                ACCOUNT_TOKEN,
                nowEpochMs = CAPACITY_NOW_MS,
            )

            val result = client.revalidate(
                session,
                "tester-01",
                nowEpochMs = CAPACITY_NOW_MS + 1L,
            )

            assertEquals(GatewaySessionRevalidationStatus.READY, result.status)
            assertEquals(expectedAvailability, result.capacityAvailability)
            assertTrue(session.isUsableFor("tester-01", CAPACITY_NOW_MS + 1L))
            val admission = GatewayCapacityProcessState.admission(
                Instant.ofEpochMilli(CAPACITY_NOW_MS + 1L),
            )
            assertFalse(admission.newRawCollectionSessionAllowed)
            assertFalse(admission.automaticReportCandidateAllowed)
            assertTrue(admission.explicitSafetyReportAllowed)
            assertTrue(admission.activeSafetyFeaturesAllowed)
        }
    }

    @Test
    fun localInvalidSessionDoesNotContactTheGatewayOrMutateTheSharedSession() {
        val transport = FakeTransport()
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val session = client.login(
            "https://field.example",
            "tester-01",
            ACCOUNT_TOKEN,
            nowEpochMs = 1_000L,
        )
        transport.getCount = 0

        val result = client.revalidate(session, "other-actor", nowEpochMs = 2_000L)

        assertEquals(GatewaySessionRevalidationStatus.NOT_READY, result.status)
        assertEquals(0, transport.getCount)
        assertTrue(session.isUsableFor("tester-01", nowEpochMs = 2_000L))
    }

    @Test
    fun failedRemoteBindingReturnsAResultForTheLeaseOwnerToApply() {
        val transport = FakeTransport()
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val session = client.login(
            "https://field.example",
            "tester-01",
            ACCOUNT_TOKEN,
            nowEpochMs = 1_000L,
        )
        transport.statusBody =
            """{"required":true,"authenticated":true,"actor_id":"other-actor"}"""

        val result = client.revalidate(session, "tester-01", nowEpochMs = 2_000L)

        assertEquals(GatewaySessionRevalidationStatus.NOT_READY, result.status)
        assertTrue(session.isUsableFor("tester-01", nowEpochMs = 2_000L))
    }

    @Test
    fun transientOrMalformedStatusRemainsPendingWithoutDiscardingTheSession() {
        val transport = FakeTransport()
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val session = client.login(
            "https://field.example",
            "tester-01",
            ACCOUNT_TOKEN,
            nowEpochMs = 1_000L,
        )
        transport.statusCode = 503

        val unavailable = client.revalidate(session, "tester-01", nowEpochMs = 2_000L)
        assertEquals(GatewaySessionRevalidationStatus.PENDING, unavailable.status)
        assertTrue(session.isUsableFor("tester-01", nowEpochMs = 2_000L))

        transport.statusCode = 200
        transport.statusBody = "not-json"
        val malformed = client.revalidate(session, "tester-01", nowEpochMs = 2_000L)
        assertEquals(GatewaySessionRevalidationStatus.PENDING, malformed.status)
        assertTrue(session.isUsableFor("tester-01", nowEpochMs = 2_000L))
    }

    @Test
    fun connectionLossDuringRevalidationKeepsTheLoginAndCanRecover() {
        val transport = FakeTransport()
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val session = client.login(
            "https://field.example", "tester-01", ACCOUNT_TOKEN, nowEpochMs = 1_000L,
        )
        transport.statusFailure = IOException("connection unavailable")

        val pending = client.revalidate(session, "tester-01", nowEpochMs = 2_000L)

        assertEquals(GatewaySessionRevalidationStatus.PENDING, pending.status)
        assertTrue(session.isUsableFor("tester-01", nowEpochMs = 2_000L))
        transport.statusFailure = null
        assertEquals(GatewaySessionRevalidationStatus.READY,
            client.revalidate(session, "tester-01", nowEpochMs = 2_000L).status)
    }

    @Test
    fun renewalUsesTheCurrentProofOnceAndAdvancesExactlyOneRotation() {
        val transport = FakeTransport(
            loginResponse = longLivedResponse(
                rotation = 0L,
                accessToken = ACCESS_TOKEN_0,
                refreshToken = REFRESH_TOKEN_0,
            ),
            refreshResponse = longLivedResponse(
                rotation = 1L,
                accessToken = ACCESS_TOKEN_1,
                refreshToken = REFRESH_TOKEN_1,
                accessExpiresAtEpochMs = ACCESS_EXPIRES_AT + 10_000L,
                idleExpiresAtEpochMs = IDLE_EXPIRES_AT + 10_000L,
            ),
        )
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val original = loginLongLived(client)
        val originalLease = original.lease

        val renewal = client.renew(original, "tester-01", nowEpochMs = NOW + 1L)

        assertEquals(originalLease, renewal.sourceLease)
        assertFalse(original.isUsableFor("tester-01", NOW + 1L))
        assertFalse(original.isRenewableFor("tester-01", NOW + 1L))
        assertEquals(1L, renewal.session.rotation)
        assertEquals(GatewaySessionVerificationState.VERIFIED, renewal.session.verificationState)
        assertTrue(renewal.session.isUsableFor("tester-01", NOW + 1L))
        assertTrue(
            renewal.session.requestHeaders(NOW + 1L)
                .getValue(GatewayFieldSession.COOKIE_HEADER)
                .contains(ACCESS_TOKEN_1),
        )
        val refresh = JSONObject(transport.postedBodies.last())
        assertEquals(
            setOf(
                "grant_type",
                "actor_id",
                "device_id",
                "family_id",
                "rotation",
                "refresh_token",
            ),
            jsonKeys(refresh),
        )
        assertEquals("refresh_token", refresh.getString("grant_type"))
        assertEquals("tester-01", refresh.getString("actor_id"))
        assertEquals(DEVICE_ID, refresh.getString("device_id"))
        assertEquals(FAMILY_ID, refresh.getString("family_id"))
        assertEquals(0L, refresh.getLong("rotation"))
        assertEquals(REFRESH_TOKEN_0, refresh.getString("refresh_token"))

        val postCount = transport.postedBodies.size
        val duplicate = assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(original, "tester-01", nowEpochMs = NOW + 2L)
        }
        assertEquals("gateway_refresh_not_allowed", duplicate.reason)
        assertEquals(postCount, transport.postedBodies.size)
    }

    @Test
    fun renewalBoundsMaximumServerTtlAtResponseTime() {
        val requestNow = 1_000_000L
        val responseElapsedMs = 1_000L
        val maximumServerExpiry = requestNow + responseElapsedMs + 3_600_000L + 300_000L
        val idleExpiry = maximumServerExpiry + 10_000L
        val absoluteExpiry = idleExpiry + 10_000L

        fun clientAndSourceFor(serverExpiry: Long): Pair<GatewayFieldSessionClient, GatewayFieldSession> {
            val clock = listOf(0L, 0L, 2_000_000_000L, 3_000_000_000L).iterator()
            val client = GatewayFieldSessionClient(
                FakeTransport(
                    loginResponse = longLivedResponse(
                        rotation = 0L,
                        accessToken = ACCESS_TOKEN_0,
                        refreshToken = REFRESH_TOKEN_0,
                        idleExpiresAtEpochMs = idleExpiry,
                        absoluteExpiresAtEpochMs = absoluteExpiry,
                    ),
                    refreshResponse = longLivedResponse(
                        rotation = 1L,
                        accessToken = ACCESS_TOKEN_1,
                        refreshToken = REFRESH_TOKEN_1,
                        accessExpiresAtEpochMs = serverExpiry,
                        idleExpiresAtEpochMs = idleExpiry,
                        absoluteExpiresAtEpochMs = absoluteExpiry,
                        cookieMaxAgeSeconds = 3_600L,
                    ),
                ),
                elapsedRealtimeNanos = { clock.next() }, allowDebugAccountOrigin = true)
            return client to loginLongLived(client)
        }

        val (acceptedClient, acceptedSource) = clientAndSourceFor(maximumServerExpiry)
        val accepted = acceptedClient.renew(acceptedSource, "tester-01", nowEpochMs = requestNow).session
        assertEquals(maximumServerExpiry, accepted.serverAccessExpiresAtEpochMs)
        assertEquals(requestNow + responseElapsedMs + 3_600_000L, accepted.accessExpiresAtEpochMs)

        resetGatewayRefreshProofFenceForTests()
        val (rejectedClient, rejectedSource) = clientAndSourceFor(maximumServerExpiry + 1L)
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            rejectedClient.renew(rejectedSource, "tester-01", nowEpochMs = requestNow)
        }
        assertEquals("gateway_session_binding_failed", error.reason)
        assertFalse(rejectedSource.isRenewableFor("tester-01", requestNow))
    }

    @Test
    fun separatelyRestoredCopiesCannotSendTheSameRefreshProofTwice() {
        val transport = FakeTransport(
            loginResponse = longLivedResponse(
                rotation = 0L,
                accessToken = ACCESS_TOKEN_0,
                refreshToken = REFRESH_TOKEN_0,
            ),
            refreshResponse = longLivedResponse(
                rotation = 1L,
                accessToken = ACCESS_TOKEN_1,
                refreshToken = REFRESH_TOKEN_1,
            ),
        )
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val persisted = loginLongLived(client).persistenceSnapshotOrNull()!!
        val first = GatewayFieldSession.restore(
            persisted,
            expectedGatewayBaseUrl = "https://field.example",
            expectedActorId = "tester-01",
            expectedDeviceId = DEVICE_ID,
            nowEpochMs = NOW + 1L,
        )!!
        val second = GatewayFieldSession.restore(
            persisted,
            expectedGatewayBaseUrl = "https://field.example",
            expectedActorId = "tester-01",
            expectedDeviceId = DEVICE_ID,
            nowEpochMs = NOW + 1L,
        )!!

        client.renew(first, "tester-01", nowEpochMs = NOW + 1L)
        val postCount = transport.postedBodies.size
        val duplicate = assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(second, "tester-01", nowEpochMs = NOW + 1L)
        }

        assertEquals("gateway_refresh_proof_already_attempted", duplicate.reason)
        assertEquals(postCount, transport.postedBodies.size)
        assertTrue(second.isRenewableFor("tester-01", NOW + 1L))
    }

    @Test
    fun concurrentSameSessionLoserCannotInvalidateTheWinningRefresh() {
        val refreshStarted = CountDownLatch(1)
        val releaseRefresh = CountDownLatch(1)
        val transport = FakeTransport(
            loginResponse = longLivedResponse(
                rotation = 0L,
                accessToken = ACCESS_TOKEN_0,
                refreshToken = REFRESH_TOKEN_0,
            ),
            refreshResponse = longLivedResponse(
                rotation = 1L,
                accessToken = ACCESS_TOKEN_1,
                refreshToken = REFRESH_TOKEN_1,
            ),
            refreshStarted = refreshStarted,
            releaseRefresh = releaseRefresh,
        )
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val source = loginLongLived(client)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val winner = executor.submit<GatewaySessionRenewal> {
                client.renew(source, "tester-01", nowEpochMs = NOW + 1L)
            }
            assertTrue(refreshStarted.await(5, TimeUnit.SECONDS))

            val loser = assertThrows(GatewaySessionHttpException::class.java) {
                client.renew(source, "tester-01", nowEpochMs = NOW + 1L)
            }
            assertEquals("gateway_refresh_proof_already_attempted", loser.reason)
            assertTrue(source.isRenewableFor("tester-01", NOW + 1L))

            releaseRefresh.countDown()
            val renewed = winner.get(5, TimeUnit.SECONDS).session
            assertTrue(renewed.isUsableFor("tester-01", NOW + 1L))
            assertFalse(source.isRenewableFor("tester-01", NOW + 1L))
            assertEquals(2, transport.postedBodies.size)
        } finally {
            releaseRefresh.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun refreshErrorCarriesOnlyTheWhitelistedServerSecurityCode() {
        val transport = FakeTransport(
            loginResponse = longLivedResponse(
                rotation = 0L,
                accessToken = ACCESS_TOKEN_0,
                refreshToken = REFRESH_TOKEN_0,
            ),
            refreshResponse = GatewayHttpResponse(
                401,
                """{"code":"refresh_token_reuse_detected","detail":"redacted"}""",
            ),
        )
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val session = loginLongLived(client)

        val error = assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(session, "tester-01", nowEpochMs = NOW + 1L)
        }

        assertEquals("gateway_refresh_failed", error.reason)
        assertEquals("refresh_token_reuse_detected", error.serverCode)
        assertFalse(error.message.orEmpty().contains("detail"))
    }

    @Test
    fun unknownServerErrorCodeIsNotExposed() {
        val client = GatewayFieldSessionClient(
            FakeTransport(
                loginResponse = longLivedResponse(
                    rotation = 0L,
                    accessToken = ACCESS_TOKEN_0,
                    refreshToken = REFRESH_TOKEN_0,
                ),
                refreshResponse = GatewayHttpResponse(
                    401,
                    """{"code":"private_internal_secret_marker"}""",
                ),
            ), allowDebugAccountOrigin = true)

        val error = assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(
                loginLongLived(client),
                "tester-01",
                nowEpochMs = NOW + 1L,
            )
        }

        assertNull(error.serverCode)
        assertFalse(error.message.orEmpty().contains("private_internal_secret_marker"))
    }

    @Test
    fun longLivedAccessDeadlineIsBoundedByCookieMaxAgeAndClockSkew() {
        val accepted = GatewayFieldSessionClient(
            FakeTransport(
                loginResponse = longLivedResponse(
                    rotation = 0L,
                    accessToken = ACCESS_TOKEN_0,
                    refreshToken = REFRESH_TOKEN_0,
                    accessExpiresAtEpochMs = NOW + 120_000L,
                    idleExpiresAtEpochMs = NOW + 180_000L,
                    absoluteExpiresAtEpochMs = NOW + 240_000L,
                ),
            ), allowDebugAccountOrigin = true).login(
            gatewayBaseUrl = "https://field.example",
            actorId = "tester-01",
            token = ACCOUNT_TOKEN,
            nowEpochMs = NOW,
            enableLongLivedSession = true,
            deviceId = DEVICE_ID,
        )
        assertEquals(NOW + 60_000L, accepted.accessExpiresAtEpochMs)

        val rejected = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(
                FakeTransport(
                    loginResponse = longLivedResponse(
                        rotation = 0L,
                        accessToken = ACCESS_TOKEN_0,
                        refreshToken = REFRESH_TOKEN_0,
                        accessExpiresAtEpochMs = NOW + 360_001L,
                        idleExpiresAtEpochMs = NOW + 400_000L,
                        absoluteExpiresAtEpochMs = NOW + 500_000L,
                    ),
                ), allowDebugAccountOrigin = true).login(
                gatewayBaseUrl = "https://field.example",
                actorId = "tester-01",
                token = ACCOUNT_TOKEN,
                nowEpochMs = NOW,
                enableLongLivedSession = true,
                deviceId = DEVICE_ID,
            )
        }
        assertEquals("gateway_session_binding_failed", rejected.reason)
    }

    @Test
    fun longLivedStatusBindsServerExpiryWithoutExtendingLocalCookieDeadline() {
        val serverExpiry = NOW + 60_050L
        val transport = FakeTransport(
            loginResponse = longLivedResponse(
                rotation = 0L,
                accessToken = ACCESS_TOKEN_0,
                refreshToken = REFRESH_TOKEN_0,
                accessExpiresAtEpochMs = serverExpiry,
            ),
        )
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val session = loginLongLived(client)
        val localDeadline = NOW + 60_000L

        assertEquals(localDeadline, session.accessExpiresAtEpochMs)
        assertEquals(serverExpiry, session.serverAccessExpiresAtEpochMs)
        transport.statusBody = longLivedStatus(serverExpiry, rotation = 0L)
        assertEquals(
            GatewaySessionRevalidationStatus.READY,
            client.revalidate(session, "tester-01", nowEpochMs = NOW + 1L).status,
        )
        assertTrue(session.isUsableFor("tester-01", localDeadline - 1L))
        assertFalse(session.isUsableFor("tester-01", localDeadline))

        val persisted = session.persistenceSnapshotOrNull()!!
        assertEquals(localDeadline, persisted.accessExpiresAtEpochMs)
        assertEquals(serverExpiry, persisted.serverAccessExpiresAtEpochMs)
        val restored = GatewayFieldSession.restore(
            snapshot = persisted,
            expectedGatewayBaseUrl = "https://field.example",
            expectedActorId = "tester-01",
            nowEpochMs = NOW + 1L,
        )!!
        assertEquals(localDeadline, restored.accessExpiresAtEpochMs)
        assertEquals(serverExpiry, restored.serverAccessExpiresAtEpochMs)
        assertFalse(restored.isUsableFor("tester-01", NOW + 1L))
        assertTrue(restored.isRenewableFor("tester-01", NOW + 1L))

        val equalTransport = FakeTransport(
            loginResponse = longLivedResponse(
                rotation = 0L,
                accessToken = ACCESS_TOKEN_0,
                refreshToken = REFRESH_TOKEN_0,
                accessExpiresAtEpochMs = localDeadline,
            ),
            statusBody = longLivedStatus(localDeadline, rotation = 0L),
        )
        val equalClient = GatewayFieldSessionClient(equalTransport, allowDebugAccountOrigin = true)
        val equalSession = loginLongLived(equalClient)
        assertEquals(localDeadline, equalSession.serverAccessExpiresAtEpochMs)
        assertEquals(
            GatewaySessionRevalidationStatus.READY,
            equalClient.revalidate(equalSession, "tester-01", nowEpochMs = NOW + 1L).status,
        )
    }

    @Test
    fun renewedSessionAlsoBindsServerExpiryInsteadOfItsEarlierCookieDeadline() {
        val renewedServerExpiry = NOW + 1L + 60_050L
        val transport = FakeTransport(
            loginResponse = longLivedResponse(
                rotation = 0L,
                accessToken = ACCESS_TOKEN_0,
                refreshToken = REFRESH_TOKEN_0,
            ),
            refreshResponse = longLivedResponse(
                rotation = 1L,
                accessToken = ACCESS_TOKEN_1,
                refreshToken = REFRESH_TOKEN_1,
                accessExpiresAtEpochMs = renewedServerExpiry,
            ),
        )
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val renewed = client.renew(loginLongLived(client), "tester-01", nowEpochMs = NOW + 1L).session

        assertEquals(NOW + 1L + 60_000L, renewed.accessExpiresAtEpochMs)
        assertEquals(renewedServerExpiry, renewed.serverAccessExpiresAtEpochMs)
        transport.statusBody = longLivedStatus(renewedServerExpiry, rotation = 1L)
        assertEquals(
            GatewaySessionRevalidationStatus.READY,
            client.revalidate(renewed, "tester-01", nowEpochMs = NOW + 2L).status,
        )
    }

    @Test
    fun renewalRejectsActorDeviceFamilyAndRotationDrift() {
        val mismatches = listOf(
            longLivedResponse(
                actorId = "other-actor",
                rotation = 1L,
                accessToken = ACCESS_TOKEN_1,
                refreshToken = REFRESH_TOKEN_1,
            ),
            longLivedResponse(
                deviceId = "device-installation-00000002",
                rotation = 1L,
                accessToken = ACCESS_TOKEN_1,
                refreshToken = REFRESH_TOKEN_1,
            ),
            longLivedResponse(
                familyId = "family-session-00000002",
                rotation = 1L,
                accessToken = ACCESS_TOKEN_1,
                refreshToken = REFRESH_TOKEN_1,
            ),
            longLivedResponse(
                rotation = 2L,
                accessToken = ACCESS_TOKEN_1,
                refreshToken = REFRESH_TOKEN_1,
            ),
            longLivedResponse(
                rotation = 1L,
                accessToken = ACCESS_TOKEN_0,
                refreshToken = REFRESH_TOKEN_1,
            ),
            longLivedResponse(
                rotation = 1L,
                accessToken = ACCESS_TOKEN_1,
                refreshToken = REFRESH_TOKEN_1,
                absoluteExpiresAtEpochMs = ABSOLUTE_EXPIRES_AT + 1L,
            ),
        )

        mismatches.forEach { response ->
            resetGatewayRefreshProofFenceForTests()
            val transport = FakeTransport(
                loginResponse = longLivedResponse(
                    rotation = 0L,
                    accessToken = ACCESS_TOKEN_0,
                    refreshToken = REFRESH_TOKEN_0,
                ),
                refreshResponse = response,
            )
            val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
            val original = loginLongLived(client)

            val error = assertThrows(GatewaySessionHttpException::class.java) {
                client.renew(original, "tester-01", nowEpochMs = NOW + 1L)
            }

            assertEquals("gateway_session_binding_failed", error.reason)
            assertFalse(original.isUsableFor("tester-01", NOW + 1L))
            assertFalse(original.isRenewableFor("tester-01", NOW + 1L))
        }
    }

    @Test
    fun accessExpiryStopsRequestsWhileIdleAndAbsoluteExpiryStopRenewal() {
        val expiredAccess = GatewayFieldSession.longLivedSession(
            gatewayBaseUrl = "https://field.example",
            actorId = "tester-01",
            deviceId = DEVICE_ID,
            familyId = FAMILY_ID,
            rotation = 4L,
            cookiePair = "${GatewayFieldSession.COOKIE_NAME}=$ACCESS_TOKEN_0",
            refreshToken = REFRESH_TOKEN_0,
            accessExpiresAtEpochMs = NOW,
            idleExpiresAtEpochMs = NOW + 10_000L,
            absoluteExpiresAtEpochMs = NOW + 20_000L,
        )

        assertFalse(expiredAccess.isUsableFor("tester-01", NOW))
        assertTrue(expiredAccess.isRenewableFor("tester-01", NOW))
        assertThrows(IllegalStateException::class.java) {
            expiredAccess.requestHeaders(NOW)
        }

        val idleExpired = GatewayFieldSession.restore(
            snapshot = GatewayFieldSessionPersistence(
                gatewayBaseUrl = "https://field.example",
                actorId = "tester-01",
                accessCookiePair = "${GatewayFieldSession.COOKIE_NAME}=$ACCESS_TOKEN_0",
                refreshToken = REFRESH_TOKEN_0,
                deviceId = DEVICE_ID,
                familyId = FAMILY_ID,
                rotation = 4L,
                accessExpiresAtEpochMs = NOW,
                idleExpiresAtEpochMs = NOW,
                absoluteExpiresAtEpochMs = NOW + 20_000L,
            ),
            expectedGatewayBaseUrl = "https://field.example",
            expectedActorId = "tester-01",
            nowEpochMs = NOW,
        )
        assertNull(idleExpired)
    }

    @Test
    fun ambiguousRefreshFailureInvalidatesTheSourceLeaseWithoutGuessingRotation() {
        val transport = FakeTransport(
            loginResponse = longLivedResponse(
                rotation = 0L,
                accessToken = ACCESS_TOKEN_0,
                refreshToken = REFRESH_TOKEN_0,
            ),
            failRefreshWithNetworkError = true,
        )
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val original = loginLongLived(client)

        val error = assertThrows(GatewaySessionHttpException::class.java) {
            client.renew(original, "tester-01", nowEpochMs = NOW + 1L)
        }

        assertEquals("gateway_refresh_outcome_unknown", error.reason)
        assertFalse(original.isUsableFor("tester-01", NOW + 1L))
        assertFalse(original.isRenewableFor("tester-01", NOW + 1L))
    }

    @Test
    fun restoredOrAccessExpiredSessionLogsOutWithRefreshProofInTheDeleteBody() {
        val transport = FakeTransport(
            loginResponse = longLivedResponse(
                rotation = 0L,
                accessToken = ACCESS_TOKEN_0,
                refreshToken = REFRESH_TOKEN_0,
            ),
        )
        val client = GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true)
        val persisted = loginLongLived(client).persistenceSnapshotOrNull()!!
        val restored = GatewayFieldSession.restore(
            snapshot = persisted,
            expectedGatewayBaseUrl = "https://field.example",
            expectedActorId = "tester-01",
            nowEpochMs = ACCESS_EXPIRES_AT + 1L,
        )!!
        restored.invalidate()

        client.logout(restored)

        assertNull(transport.deletedHeaders)
        val proof = JSONObject(transport.deletedJsonBody!!)
        assertEquals(
            setOf(
                "grant_type",
                "actor_id",
                "device_id",
                "family_id",
                "rotation",
                "refresh_token",
            ),
            jsonKeys(proof),
        )
        assertEquals(REFRESH_TOKEN_0, proof.getString("refresh_token"))
        assertEquals(DEVICE_ID, proof.getString("device_id"))
        assertEquals(FAMILY_ID, proof.getString("family_id"))
        assertEquals(0L, proof.getLong("rotation"))
        assertFalse(restored.isRenewableFor("tester-01", ACCESS_EXPIRES_AT + 1L))
    }

    @Test
    fun pendingRevocationCanRetryLogoutWithoutAnAccessSession() {
        val transport = FakeTransport()
        val pending = GatewayPendingRevocation(
            operationId = "logout_operation_00000001",
            version = GatewaySessionVersion(
                gatewayBaseUrl = "https://field.example",
                actorId = "tester-01",
                deviceId = DEVICE_ID,
                familyId = FAMILY_ID,
                rotation = 7L,
            ),
            refreshToken = REFRESH_TOKEN_0,
        )

        GatewayFieldSessionClient(transport, allowDebugAccountOrigin = true).logout(pending)

        val proof = JSONObject(transport.deletedJsonBody!!)
        assertEquals("refresh_token", proof.getString("grant_type"))
        assertEquals("tester-01", proof.getString("actor_id"))
        assertEquals(DEVICE_ID, proof.getString("device_id"))
        assertEquals(FAMILY_ID, proof.getString("family_id"))
        assertEquals(7L, proof.getLong("rotation"))
        assertEquals(REFRESH_TOKEN_0, proof.getString("refresh_token"))
        assertFalse(pending.toString().contains(REFRESH_TOKEN_0))
    }

    @Test
    fun httpResponseStringRepresentationRedactsCredentialBodiesAndHeaderValues() {
        val response = GatewayHttpResponse(
            statusCode = 200,
            responseBody = """{"refresh_token":"$REFRESH_TOKEN_0"}""",
            headers = mapOf("Set-Cookie" to "walksafe_field_session=$ACCESS_TOKEN_0"),
        )

        val printable = response.toString()

        assertTrue(printable.contains("statusCode=200"))
        assertTrue(printable.contains("Set-Cookie"))
        assertFalse(printable.contains(REFRESH_TOKEN_0))
        assertFalse(printable.contains(ACCESS_TOKEN_0))
    }

    @Test
    fun persistedSessionRestoresUnverifiedOnlyForTheSameActorOriginAndRefreshLifetime() {
        val session = GatewayFieldSessionClient(
            FakeTransport(
                loginResponse = longLivedResponse(
                    rotation = 0L,
                    accessToken = ACCESS_TOKEN_0,
                    refreshToken = REFRESH_TOKEN_0,
                ),
            ), allowDebugAccountOrigin = true).login(
            gatewayBaseUrl = "https://field.example",
            actorId = "tester-01",
            token = ACCOUNT_TOKEN,
            nowEpochMs = NOW,
            enableLongLivedSession = true,
            deviceId = DEVICE_ID,
        )
        val persisted = session.persistenceSnapshotOrNull()!!

        val restored = GatewayFieldSession.restore(
            snapshot = persisted,
            expectedGatewayBaseUrl = "https://field.example",
            expectedActorId = "tester-01",
            nowEpochMs = NOW + 1L,
        )

        assertEquals(GatewaySessionVerificationState.RESTORED_UNVERIFIED, restored?.verificationState)
        assertFalse(restored!!.isUsableFor("tester-01", nowEpochMs = NOW + 1L))
        assertTrue(restored.isRenewableFor("tester-01", nowEpochMs = NOW + 1L))
        assertThrows(IllegalStateException::class.java) {
            restored.requestHeaders(NOW + 1L)
        }
        assertNull(
            GatewayFieldSession.restore(
                persisted,
                expectedGatewayBaseUrl = "https://other.example",
                expectedActorId = "tester-01",
                nowEpochMs = NOW + 1L,
            ),
        )
        assertNull(
            GatewayFieldSession.restore(
                persisted,
                expectedGatewayBaseUrl = "https://field.example",
                expectedActorId = "other-actor",
                nowEpochMs = NOW + 1L,
            ),
        )
        assertNull(
            GatewayFieldSession.restore(
                persisted,
                expectedGatewayBaseUrl = "https://field.example",
                expectedActorId = "tester-01",
                nowEpochMs = persisted.idleExpiresAtEpochMs,
            ),
        )
    }

    private fun loginLongLived(client: GatewayFieldSessionClient): GatewayFieldSession {
        return client.login(
            gatewayBaseUrl = "https://field.example",
            actorId = "tester-01",
            token = ACCOUNT_TOKEN,
            nowEpochMs = NOW,
            enableLongLivedSession = true,
            deviceId = DEVICE_ID,
        )
    }

    private fun jsonKeys(json: JSONObject): Set<String> {
        val result = mutableSetOf<String>()
        val keys = json.keys()
        while (keys.hasNext()) result += keys.next()
        return result
    }

    private fun capacityStatus(
        version: Long,
        observedAt: String,
        expiresAt: String,
        level: GatewayCapacityLevel,
        versionOverride: String? = null,
    ): String {
        val versionJson = versionOverride ?: version.toString()
        return """{"required":true,"authenticated":true,"actor_id":"tester-01","capacity":{"version":$versionJson,"observed_at":"$observedAt","expires_at":"$expiresAt","level":"${level.name}","reason":"STORAGE_UTILIZATION"}}"""
    }

    private fun longLivedStatus(accessExpiresAtEpochMs: Long, rotation: Long): String =
        JSONObject()
            .put("required", true)
            .put("authenticated", true)
            .put("actor_id", "tester-01")
            .put("session_scope", GatewaySessionScope.GENERAL.wireValue)
            .put("session_mode", "long_lived")
            .put("device_id", DEVICE_ID)
            .put("family_id", FAMILY_ID)
            .put("rotation", rotation)
            .put("access_expires_at_epoch_ms", accessExpiresAtEpochMs)
            .put("idle_expires_at_epoch_ms", IDLE_EXPIRES_AT)
            .put("absolute_expires_at_epoch_ms", ABSOLUTE_EXPIRES_AT)
            .toString()

    private class FakeTransport(
        private val setCookie: String = COOKIE_ATTRIBUTES,
        statusBody: String = """{"required":true,"authenticated":true,"actor_id":"tester-01"}""",
        private val loginResponse: GatewayHttpResponse? = null,
        private val refreshResponse: GatewayHttpResponse? = null,
        private val failRefreshWithNetworkError: Boolean = false,
        private val refreshStarted: CountDownLatch? = null,
        private val releaseRefresh: CountDownLatch? = null,
    ) : GatewaySessionTransport {
        var postedUrl: String? = null
        var postedBody: String? = null
        val postedBodies = mutableListOf<String>()
        var statusHeaders: Map<String, String>? = null
        var deletedHeaders: Map<String, String>? = null
        var deletedJsonBody: String? = null
        var deleteCount = 0
        var getCount = 0
        var lastGetUrl: String? = null
        var statusCode = 200
        var statusFailure: Exception? = null
        var statusBody = statusBody

        override fun postJson(url: String, body: String): GatewayHttpResponse {
            postedUrl = url
            postedBody = body
            postedBodies += body
            val isRefresh = runCatching {
                JSONObject(body).optString("grant_type") == "refresh_token"
            }.getOrDefault(false)
            if (isRefresh && failRefreshWithNetworkError) {
                throw IllegalStateException("simulated connection loss")
            }
            if (isRefresh) {
                refreshStarted?.countDown()
                releaseRefresh?.await(5, TimeUnit.SECONDS)
                return refreshResponse
                    ?: GatewayHttpResponse(500, """{"error":"missing_refresh_fixture"}""")
            }
            return loginResponse
                ?: GatewayHttpResponse(204, "", mapOf("Set-Cookie" to setCookie))
        }

        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse {
            getCount += 1
            statusFailure?.let { throw it }
            lastGetUrl = url
            statusHeaders = headers
            return GatewayHttpResponse(statusCode, statusBody)
        }

        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse {
            deleteCount += 1
            deletedHeaders = headers
            return GatewayHttpResponse(204, "")
        }

        override fun deleteJson(url: String, body: String): GatewayHttpResponse {
            deleteCount += 1
            deletedJsonBody = body
            return GatewayHttpResponse(204, "")
        }
    }

    private companion object {
        const val ACCOUNT_TOKEN = "named-account-token-for-android-tests-123456"
        const val DEVICE_ID = "device-installation-00000001"
        val FAMILY_ID = "f".repeat(32)
        const val ACCESS_TOKEN_0 = "access-token-rotation-0000000000000000"
        const val ACCESS_TOKEN_1 = "access-token-rotation-1111111111111111"
        val REFRESH_TOKEN_0 = "r".repeat(64)
        val REFRESH_TOKEN_1 = "s".repeat(64)
        const val NOW = 1_000L
        val CAPACITY_NOW_MS = Instant.parse("2026-08-25T00:10:00Z").toEpochMilli()
        const val ACCESS_EXPIRES_AT = 61_000L
        const val IDLE_EXPIRES_AT = 121_000L
        const val ABSOLUTE_EXPIRES_AT = 241_000L
        const val COOKIE_ATTRIBUTES =
            "walksafe_field_session=v2.dGVzdGVyLTAx.1234567890.signature; Path=/; HttpOnly; SameSite=Strict; Max-Age=43200; Secure"

        fun longLivedResponse(
            actorId: String = "tester-01",
            deviceId: String = DEVICE_ID,
            familyId: String = FAMILY_ID,
            rotation: Long,
            accessToken: String,
            refreshToken: String,
            accessExpiresAtEpochMs: Long = ACCESS_EXPIRES_AT,
            idleExpiresAtEpochMs: Long = IDLE_EXPIRES_AT,
            absoluteExpiresAtEpochMs: Long = ABSOLUTE_EXPIRES_AT,
            cookieMaxAgeSeconds: Long = 60L,
        ): GatewayHttpResponse {
            // Same 9 fields as the Gateway sessionResponse (field-long-session.ts).
            val body = JSONObject()
                .put("session_scope", GatewaySessionScope.GENERAL.wireValue)
                .put("actor_id", actorId)
                .put("device_id", deviceId)
                .put("family_id", familyId)
                .put("rotation", rotation)
                .put("refresh_token", refreshToken)
                .put("access_expires_at_epoch_ms", accessExpiresAtEpochMs)
                .put("idle_expires_at_epoch_ms", idleExpiresAtEpochMs)
                .put("absolute_expires_at_epoch_ms", absoluteExpiresAtEpochMs)
            val cookie =
                "walksafe_field_session=$accessToken; Path=/; HttpOnly; SameSite=Strict; Max-Age=$cookieMaxAgeSeconds; Secure"
            return GatewayHttpResponse(
                statusCode = 200,
                responseBody = body.toString(),
                headers = mapOf("Set-Cookie" to cookie),
            )
        }
    }
}
