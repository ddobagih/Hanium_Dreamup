package kr.co.hanium.dreamup.walksafe.fieldlog

import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupRequirement
import kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentPreflightPhase
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.*
import org.junit.Test

class GuidanceDiagnosticMetadataTest {
    @Test fun magneticRejectionRecordsTheSpecificCheckWithoutRecordingRawSensorVectors() {
        val event = requireNotNull(GuidanceDiagnosticMetadata.structured("route_compass_state_changed", mapOf(
            "reason" to "MAGNETIC_FIELD_ANOMALY",
            "compass_rejected_check" to "MAGNETIC_FIELD_INCLINATION",
            "magnetic_x" to 23.45,
        )))
        val safe = requireNotNull(GuidanceDiagnosticPolicy.sanitize(event.name, event.fields))
        assertEquals("MAGNETIC_FIELD_INCLINATION", safe["gate_reason"])
        assertEquals("MAGNETIC_FIELD_ANOMALY", safe["reason"])
        assertFalse(safe.containsKey("magnetic_x"))
    }

    @Test fun nominalTrendClockKeepsItsKindWithoutInventingReportedAngularAccuracy() {
        val event = requireNotNull(GuidanceDiagnosticMetadata.structured("route_guidance_generated", mapOf(
            "compass_source" to "GRAVITY_MAGNETIC",
            "expression_level" to "CLOCK",
            "clock_reference_kind" to "NOMINAL_TREND_CLOCK",
            "heading_accuracy_deg" to null,
        )))
        val safe = requireNotNull(GuidanceDiagnosticPolicy.sanitize(event.name, event.fields))
        assertEquals("NOMINAL_TREND_CLOCK", safe["authorization_kind"])
        assertEquals("GRAVITY_MAGNETIC", safe["source"])
        assertEquals("CLOCK", safe["expression_level"])
        assertNull(safe["heading_accuracy_deg"])
    }

    @Test fun uiTextDoesNotCarryDestinationCoordinatesOrTokensIntoMetadata() {
        val text = "navigation=direction_paused reason=position_quality destination=집 lat=37.52 Cookie=secret"
        val mapped = requireNotNull(GuidanceDiagnosticMetadata.navigationStatus(text))
        assertEquals(mapOf("stage" to "direction_paused", "reason" to "position_quality"), mapped.fields)
        assertNull(GuidanceDiagnosticMetadata.navigationStatus("집, 경북 구미시 37.52,127.0"))
    }

    @Test fun generatedCueUsesOnlyFixedMetadataAndPlaybackValidationIsSeparate() {
        val raw = mapOf("cue_id" to "1:2", "elapsed_realtime_ms" to 1_000L,
            "compass_reason" to "VALID", "expression_level" to "CLOCK_RANGE",
            "message" to "주소에 도착했습니다", "latitude" to 37.5, "Cookie" to "secret")
        val generated = requireNotNull(GuidanceDiagnosticMetadata.structured("route_guidance_generated", raw))
        val safe = requireNotNull(GuidanceDiagnosticPolicy.sanitize(generated.name, generated.fields))
        assertEquals("GENERATED", safe["stage"])
        assertFalse(safe.containsKey("message")); assertFalse(safe.containsKey("latitude"))
        assertFalse(safe.containsKey("Cookie")); assertFalse(safe.containsKey("current"))
        val started = requireNotNull(GuidanceDiagnosticMetadata.structured("route_guidance_started",
            mapOf("cue_id" to "1:2", "result" to "revoked")))
        assertEquals(false, started.fields["current"])
        assertNull(GuidanceDiagnosticMetadata.structured("raw_camera_frame", raw))
    }

    @Test fun prewalkPendingEnvironmentMountingAndCapabilityAreSeparateTypedGateFacts() {
        val events = prewalk(environment = WalkSessionReadinessStatus.READY, mounting = WalkSessionReadinessStatus.PENDING,
            pending = listOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS), mayStart = false)
        assertEquals(5, events.size)
        assertEquals(mapOf("stage" to "PREWALK_STATUS", "reason" to "WAIT_ENVIRONMENT_OR_MOUNTING",
            "mode" to "READY", "gate_reason" to "MEASURING"), events.first().fields)
        assertEquals("READY", events[1].fields["reason"]); assertEquals("PENDING", events[2].fields["reason"])
        assertEquals(mapOf("stage" to "PREWALK_CAPABILITY", "gate_reason" to "OFFLINE_KOREAN_TTS", "reason" to "PENDING"), events.last().fields)
        assertEquals("BLOCKED", events[3].fields["gate_reason"])
    }

    @Test fun absentPrewalkObservationsDoNotInventReadinessOrStartupDecision() {
        val events = prewalk(environment = null, mounting = null, mayStart = null)
        assertEquals(4, events.size)
        assertEquals("NOT_OBSERVED", events[1].fields["reason"]); assertEquals("NOT_OBSERVED", events[2].fields["reason"])
        assertEquals("DECISION_ABSENT", events[3].fields["gate_reason"])
        assertEquals(false, events[3].fields["current"]); assertEquals("UNCONFIRMED", events[3].fields["reason"])
        assertTrue(events.none { "navigation_allowed" in it.fields }) // This helper never decides admission.
    }

    @Test fun confirmationFlagsRemainIndependentWithoutPromotingPendingFactsToSuccess() {
        for (token in listOf(false, true)) for (posted in listOf(false, true))
            for (pending in listOf(false, true)) for (confirmed in listOf(false, true)) {
                val event = prewalk(token = token, posted = posted, confirmationPending = pending, confirmed = confirmed)[3]
                assertEquals(token, event.fields["current"])
                assertEquals(pending, event.fields["request_pending"])
                assertEquals(if (posted) "POSTED" else "NOT_POSTED", event.fields["mode"])
                assertEquals(if (confirmed) "CONFIRMED" else "UNCONFIRMED", event.fields["reason"])
                assertEquals("MAY_START", event.fields["gate_reason"])
            }
    }

    @Test fun duplicatedCapabilityInputIsBoundedAndUnavailableTakesPrecedence() {
        val requirements = WalkSafeStartupRequirement.entries
        val events = prewalk(pending = List(1_000) { requirements[it % requirements.size] }, unavailable = requirements)
        assertEquals(4 + requirements.size, events.size)
        val capabilities = events.drop(4)
        assertEquals(requirements.map { it.name }, capabilities.map { it.fields["gate_reason"] })
        assertTrue(capabilities.all { it.fields["reason"] == "UNAVAILABLE" })
    }

    @Test fun onlyFixedPrewalkStagesAcceptTypedFactsAndNoRawStatusOrPrivateFields() {
        for (stage in listOf("", "GUIDANCE_REQUESTED", "user@example.test", "WAIT_token_secret", "주소 37.5,127.0")) {
            assertTrue(prewalk(stage = stage).isEmpty())
        }
        for (stage in listOf("MEASUREMENT_REQUESTED", "OWNER_CANCELLED", "MEASUREMENT_FAILED_OR_TIMED_OUT",
            "WAIT_ENVIRONMENT_OR_MOUNTING", "WAIT_STARTUP_DECISION", "WAIT_CONFIRMATION_OR_READINESS", "POST_START_CONFIRMATION")) {
            val events = prewalk(stage = stage)
            assertEquals(stage, events.first().fields["reason"])
            for (event in events) {
                assertTrue(event.name in setOf("route_request", "route_terminal"))
                assertTrue(event.fields.keys.all { it in setOf("stage", "reason", "gate_reason", "mode", "current", "request_pending") })
                assertEquals(event.fields, GuidanceDiagnosticPolicy.sanitize(event.name, event.fields))
            }
        }
    }

    @Test fun allActualWalkPreflightAndReadinessEnumsSurviveTheExistingMetadataPolicy() {
        for (state in WalkSessionState.entries) for (phase in OfficialEnvironmentPreflightPhase.entries)
            for (readiness in WalkSessionReadinessStatus.entries) {
                val events = prewalk(state = state, phase = phase, environment = readiness, mounting = readiness)
                assertEquals(state.name, events[0].fields["mode"]); assertEquals(phase.name, events[0].fields["gate_reason"])
                assertEquals(readiness.name, events[1].fields["reason"]); assertEquals(readiness.name, events[2].fields["reason"])
                assertTrue(events.all { it.fields == GuidanceDiagnosticPolicy.sanitize(it.name, it.fields) })
            }
    }

    private fun prewalk(
        stage: String = "WAIT_ENVIRONMENT_OR_MOUNTING",
        state: WalkSessionState = WalkSessionState.READY,
        phase: OfficialEnvironmentPreflightPhase = OfficialEnvironmentPreflightPhase.MEASURING,
        environment: WalkSessionReadinessStatus? = WalkSessionReadinessStatus.READY,
        mounting: WalkSessionReadinessStatus? = WalkSessionReadinessStatus.READY,
        pending: Collection<WalkSafeStartupRequirement> = emptyList(),
        unavailable: Collection<WalkSafeStartupRequirement> = emptyList(),
        mayStart: Boolean? = true,
        token: Boolean = false,
        posted: Boolean = false,
        confirmationPending: Boolean = false,
        confirmed: Boolean = false,
    ) = GuidanceDiagnosticMetadata.prewalk(stage, state, phase, environment, mounting, pending, unavailable,
        mayStart, token, posted, confirmationPending, confirmed)

    @Test fun everyModeTransitionSurvivesHighRateSamplingWithoutWritingUnchangedFrames() {
        val filter = GuidanceDiagnosticTransitionFilter()
        assertTrue(filter.admit("direction", "NORMAL", 1_000))
        repeat(30) { assertFalse(filter.admit("direction", "NORMAL", 1_100L + it * 300)) }
        assertTrue(filter.admit("direction", "GRACE_RETAINED", 10_000))
        assertTrue(filter.admit("direction", "NORMAL", 10_050))
        assertFalse(filter.admit("direction", "NORMAL", 10_100))
        assertTrue(filter.admit("direction", "NORMAL", 20_050))
        assertTrue(filter.admit("direction", "PROBLEM", 20_051))
    }
}
