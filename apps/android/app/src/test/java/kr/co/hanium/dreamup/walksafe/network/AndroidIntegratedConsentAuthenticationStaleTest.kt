package kr.co.hanium.dreamup.walksafe.network

import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSelections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AndroidIntegratedConsentAuthenticationStaleTest {
    @Test
    fun onlyCurrentConsentGetTreatsNotFoundAsAbsenceBeforeParsing() {
        LocalHttpTestServer { _, socket -> socket.writeFixedResponse(404, "not-json".toByteArray()) }.use { server ->
            val session = GatewayFieldSession.verified(server.baseUrl, "fixture-actor", "fixture=session", Long.MAX_VALUE)
            assertNull(AndroidIntegratedConsentClient().fetchCurrentCall(
                server.baseUrl, session, INSTALLATION_ID, "b".repeat(64),
            ).execute())
            for (save in listOf(false, true)) {
                val error = assertThrows(IntegratedConsentHttpException::class.java) { consentCall(session, save).execute() }
                assertEquals(404, error.statusCode)
                assertEquals("not-json", error.errorBody)
            }
        }
    }

    @Test
    fun bootstrapCurrentStaleResponseClearsOnce() = exercise(false, StaleAuthenticationResponseTiming.CURRENT)

    @Test
    fun bootstrapRetainsCreationGenerationAcrossDelayedExecution() =
        exercise(false, StaleAuthenticationResponseTiming.AFTER_CREATION)

    @Test
    fun bootstrapLateResponseCannotClearReplacementSession() =
        exercise(false, StaleAuthenticationResponseTiming.IN_FLIGHT_REPLACEMENT)

    @Test
    fun saveCurrentStaleResponseClearsOnce() = exercise(true, StaleAuthenticationResponseTiming.CURRENT)

    @Test
    fun saveRetainsCreationGenerationAcrossDelayedExecution() =
        exercise(true, StaleAuthenticationResponseTiming.AFTER_CREATION)

    @Test
    fun saveLateResponseCannotClearReplacementSession() =
        exercise(true, StaleAuthenticationResponseTiming.IN_FLIGHT_REPLACEMENT)

    @Test
    fun cancelledBootstrapResponseCannotClearSession() =
        exercise(false, StaleAuthenticationResponseTiming.CANCELLED)

    @Test
    fun cancelledSaveResponseCannotClearSession() =
        exercise(true, StaleAuthenticationResponseTiming.CANCELLED)

    @Test
    fun localConsentLedgerGetIsExcluded() {
        exerciseStaleAuthenticationHttpCall(
            timing = StaleAuthenticationResponseTiming.CURRENT,
            errorType = IntegratedConsentHttpException::class.java,
            clearExpected = false,
        ) { session ->
            AndroidIntegratedConsentClient().fetchCurrentCall(
                gatewayBaseUrl = session.gatewayBaseUrl,
                session = session,
                installationId = INSTALLATION_ID,
                controlSecret = "b".repeat(64),
            )
        }
    }

    @Test
    fun otherAuthenticationAndBusyResponsesKeepTheirOriginalBodyAndCode() {
        val body = """{"detail":{"code":"field_session_storage_busy"}}"""
        listOf(401, 403, 503).forEach { status ->
            val error = exerciseStaleAuthenticationHttpCall(
                timing = StaleAuthenticationResponseTiming.CURRENT,
                errorType = IntegratedConsentHttpException::class.java,
                statusCode = status,
                body = body,
                clearExpected = false,
            ) { consentCall(it, save = true) } as IntegratedConsentHttpException
            assertEquals(status, error.statusCode)
            assertEquals(body, error.errorBody)
            assertEquals("field_session_storage_busy", error.serverCode)
        }
    }

    private fun exercise(save: Boolean, timing: StaleAuthenticationResponseTiming) {
        exerciseStaleAuthenticationHttpCall(timing, IntegratedConsentHttpException::class.java) {
            consentCall(it, save)
        }
    }

    private fun consentCall(session: GatewayFieldSession, save: Boolean): CancellableNetworkCall<*> {
        val client = AndroidIntegratedConsentClient()
        return if (save) {
            client.saveCall(
                gatewayBaseUrl = session.gatewayBaseUrl,
                session = session,
                installationId = INSTALLATION_ID,
                controlSecret = "b".repeat(64),
                requestId = "integrated_consent_request_0001",
                clientRevision = 1L,
                selections = IntegratedConsentSelections(),
                expectedPreviousBackendReceiptSha256 = null,
            )
        } else {
            client.fetchBootstrapCall(
                gatewayBaseUrl = session.gatewayBaseUrl,
                session = session,
                installationId = INSTALLATION_ID,
            )
        }
    }

    private companion object {
        const val INSTALLATION_ID = "501e3ad4-e74f-4433-820f-72ac2fdd42ad"
    }
}
