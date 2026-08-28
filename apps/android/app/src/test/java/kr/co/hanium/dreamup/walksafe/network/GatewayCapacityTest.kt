package kr.co.hanium.dreamup.walksafe.network

import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayCapacityTest {
    @Before
    fun resetState() {
        GatewayCapacityProcessState.resetForTests()
    }

    @After
    fun releaseRefreshFence() {
        GatewayCapacityProcessState.finishRefresh()
    }

    @Test
    fun parserAcceptsOnlyTheExactFiveFieldWireContract() {
        GatewayCapacityLevel.entries.forEachIndexed { index, level ->
            val parsed = GatewayCapacityParser.parse(
                capacityJson(
                    version = index.toLong() + 1L,
                    level = level,
                    observedAt = "2026-08-25T00:00:00.123456Z",
                    expiresAt = "2026-08-25T00:05:00Z",
                ),
            )

            assertTrue(parsed is GatewayCapacityParseResult.Valid)
            parsed as GatewayCapacityParseResult.Valid
            assertEquals(level, parsed.snapshot.level)
            assertEquals(
                GatewayCapacityReason.STORAGE_UTILIZATION,
                parsed.snapshot.reason,
            )
        }

        assertEquals(
            GatewayCapacityParseResult.Missing,
            GatewayCapacityParser.fromSessionStatus(JSONObject("{}")),
        )
        assertEquals(
            GatewayCapacityParseResult.Malformed,
            GatewayCapacityParser.fromSessionStatus(
                JSONObject("""{"capacity":null}"""),
            ),
        )
        assertEquals(
            GatewayCapacityParseResult.Malformed,
            GatewayCapacityParser.parse(capacityJson().put("extra", true)),
        )
        assertEquals(
            GatewayCapacityParseResult.Malformed,
            GatewayCapacityParser.parse(capacityJson().apply { remove("reason") }),
        )
        assertEquals(
            GatewayCapacityParseResult.Malformed,
            GatewayCapacityParser.parse(
                capacityJson().put("reason", "storage_utilization"),
            ),
        )
    }

    @Test
    fun versionMustBeAPositiveJsSafeJsonInteger() {
        listOf(
            "0",
            "-1",
            "9007199254740992",
            "1.0",
            "\"1\"",
            "true",
            "null",
        ).forEach { invalidVersion ->
            val json = JSONObject(
                validCapacityText().replace("\"version\":1", "\"version\":$invalidVersion"),
            )
            assertEquals(
                invalidVersion,
                GatewayCapacityParseResult.Malformed,
                GatewayCapacityParser.parse(json),
            )
        }

        val maximum = JSONObject(
            validCapacityText().replace(
                "\"version\":1",
                "\"version\":9007199254740991",
            ),
        )
        val parsed = GatewayCapacityParser.parse(maximum)
        assertTrue(parsed is GatewayCapacityParseResult.Valid)
        assertEquals(
            9_007_199_254_740_991L,
            (parsed as GatewayCapacityParseResult.Valid).snapshot.version,
        )
    }

    @Test
    fun timestampsRequireUtcZWithAtMostMicrosecondPrecisionAndForwardExpiry() {
        listOf(
            "2026-08-25 00:00:00Z",
            "2026-08-25T00:00:00+00:00",
            "2026-08-25T00:00:00z",
            "2026-08-25T00:00:60Z",
            "2026-08-25T00:00:00.1234567Z",
            "2026-08-25T00:00:00.12345678Z",
            "2026-08-25T00:00:00.123456789Z",
            "2026-02-30T00:00:00Z",
        ).forEach { invalidObservedAt ->
            assertEquals(
                invalidObservedAt,
                GatewayCapacityParseResult.Malformed,
                GatewayCapacityParser.parse(
                    capacityJson(observedAt = invalidObservedAt),
                ),
            )
        }

        assertEquals(
            GatewayCapacityParseResult.Malformed,
            GatewayCapacityParser.parse(
                capacityJson(
                    observedAt = "2026-08-25T00:05:00Z",
                    expiresAt = "2026-08-25T00:05:00Z",
                ),
            ),
        )
        listOf(
            "2026-08-25T00:00:00.1Z",
            "2026-08-25T00:00:00.000Z",
            "2026-08-25T00:00:00.123000Z",
            "2026-08-25T00:00:00.123456Z",
        ).forEach { validObservedAt ->
            assertTrue(
                validObservedAt,
                GatewayCapacityParser.parse(
                    capacityJson(observedAt = validObservedAt),
                ) is GatewayCapacityParseResult.Valid,
            )
        }
    }

    @Test
    fun thresholdAdmissionsAreCumulativeWithoutBlockingParticipantRegistration() {
        val expected = mapOf(
            GatewayCapacityLevel.NORMAL to Triple(true, true, true),
            GatewayCapacityLevel.ADMIN_ONLY_WARNING to Triple(true, true, true),
            GatewayCapacityLevel.PAUSE_NEW_FIELD_TEST_PARTICIPANTS to
                Triple(true, true, true),
            GatewayCapacityLevel.HOLD_NEW_RAW_COLLECTION_SESSIONS to
                Triple(false, true, true),
            GatewayCapacityLevel.HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES to
                Triple(false, false, false),
        )
        GatewayCapacityLevel.entries.forEachIndexed { index, level ->
            GatewayCapacityProcessState.resetForTests()
            val update = GatewayCapacityProcessState.apply(
                validSnapshot(index.toLong() + 1L, level),
                NOW,
            )
            val admission = update.admission
            val allowed = expected.getValue(level)

            assertEquals(GatewayCapacityAvailability.AVAILABLE, admission.availability)
            assertEquals(allowed.first, admission.newRawCollectionSessionAllowed)
            assertEquals(allowed.second, admission.learningCandidateAllowed)
            assertEquals(allowed.third, admission.automaticReportCandidateAllowed)
            assertEquals(
                level >= GatewayCapacityLevel.PAUSE_NEW_FIELD_TEST_PARTICIPANTS,
                admission.participantAdmissionRestrictedSignal,
            )
            assertTrue(admission.explicitSafetyReportAllowed)
            assertTrue(admission.activeSafetyFeaturesAllowed)
            assertTrue(admission.activeRawSessionStopAllowed)
        }
    }

    @Test
    fun missingMalformedAndExpiredFailClosedOnlyForNewRawAndAutomaticMvpWork() {
        listOf(
            GatewayCapacityProcessState.apply(GatewayCapacityParseResult.Missing, NOW).admission,
            GatewayCapacityProcessState.apply(GatewayCapacityParseResult.Malformed, NOW).admission,
            GatewayCapacityProcessState.apply(
                validSnapshot(
                    version = 1L,
                    level = GatewayCapacityLevel.NORMAL,
                    expiresAt = NOW,
                ),
                NOW,
            ).admission,
        ).forEach { admission ->
            assertFalse(admission.newRawCollectionSessionAllowed)
            assertFalse(admission.automaticReportCandidateAllowed)
            assertTrue(admission.learningCandidateAllowed)
            assertTrue(admission.explicitSafetyReportAllowed)
            assertTrue(admission.activeSafetyFeaturesAllowed)
            assertTrue(admission.activeRawSessionStopAllowed)
            assertFalse(admission.participantAdmissionRestrictedSignal)
        }
        assertEquals(
            GatewayCapacityAvailability.EXPIRED,
            GatewayCapacityProcessState.admission(NOW).availability,
        )
    }

    @Test
    fun warningAndParticipantSignalsAreOneShotForEachAcceptedVersion() {
        val first = GatewayCapacityProcessState.apply(
            validSnapshot(1L, GatewayCapacityLevel.ADMIN_ONLY_WARNING),
            NOW,
        )
        val same = GatewayCapacityProcessState.apply(
            validSnapshot(1L, GatewayCapacityLevel.ADMIN_ONLY_WARNING),
            NOW,
        )
        val participant = GatewayCapacityProcessState.apply(
            validSnapshot(2L, GatewayCapacityLevel.PAUSE_NEW_FIELD_TEST_PARTICIPANTS),
            NOW,
        )

        assertTrue(first.signals.adminWarningOneShot)
        assertFalse(first.signals.participantAdmissionRestricted)
        assertEquals(GatewayCapacityUpdateDisposition.IDEMPOTENT, same.disposition)
        assertFalse(same.signals.adminWarningOneShot)
        assertTrue(participant.signals.adminWarningOneShot)
        assertTrue(participant.signals.participantAdmissionRestricted)
    }

    @Test
    fun orderingFenceRejectsLowerConflictAndObservedTimeRollback() {
        val accepted = validSnapshot(10L, GatewayCapacityLevel.NORMAL)
        assertEquals(
            GatewayCapacityUpdateDisposition.ACCEPTED,
            GatewayCapacityProcessState.apply(accepted, NOW).disposition,
        )
        assertEquals(
            GatewayCapacityUpdateDisposition.IDEMPOTENT,
            GatewayCapacityProcessState.apply(accepted, NOW).disposition,
        )
        assertEquals(
            GatewayCapacityUpdateDisposition.REJECTED_LOWER_VERSION,
            GatewayCapacityProcessState.apply(
                validSnapshot(9L, GatewayCapacityLevel.NORMAL),
                NOW,
            ).disposition,
        )
        assertEquals(
            GatewayCapacityUpdateDisposition.REJECTED_EQUAL_VERSION_CONFLICT,
            GatewayCapacityProcessState.apply(
                validSnapshot(10L, GatewayCapacityLevel.ADMIN_ONLY_WARNING),
                NOW,
            ).disposition,
        )
        assertEquals(
            GatewayCapacityUpdateDisposition.REJECTED_OBSERVED_AT_ROLLBACK,
            GatewayCapacityProcessState.apply(
                validSnapshot(
                    version = 11L,
                    level = GatewayCapacityLevel.NORMAL,
                    observedAt = OBSERVED_AT.minusSeconds(1L),
                ),
                NOW,
            ).disposition,
        )
        assertEquals(10L, GatewayCapacityProcessState.admission(NOW).snapshot?.version)

        GatewayCapacityProcessState.apply(GatewayCapacityParseResult.Missing, NOW)
        assertEquals(
            GatewayCapacityUpdateDisposition.REJECTED_LOWER_VERSION,
            GatewayCapacityProcessState.apply(
                validSnapshot(8L, GatewayCapacityLevel.NORMAL),
                NOW,
            ).disposition,
        )
        assertEquals(
            GatewayCapacityAvailability.MISSING,
            GatewayCapacityProcessState.admission(NOW).availability,
        )
        assertEquals(
            GatewayCapacityUpdateDisposition.IDEMPOTENT,
            GatewayCapacityProcessState.apply(accepted, NOW).disposition,
        )
        assertEquals(
            GatewayCapacityAvailability.AVAILABLE,
            GatewayCapacityProcessState.admission(NOW).availability,
        )
    }

    @Test
    fun processSnapshotAndRefreshCoalescingAreThreadSafe() {
        val executor = Executors.newFixedThreadPool(8)
        try {
            val updates = (1L..200L).map { version ->
                executor.submit {
                    GatewayCapacityProcessState.apply(
                        validSnapshot(
                            version = version,
                            level = GatewayCapacityLevel.NORMAL,
                            observedAt = OBSERVED_AT.plusSeconds(version),
                            expiresAt = EXPIRES_AT.plusSeconds(version),
                        ),
                        NOW,
                    )
                }
            }
            updates.forEach { it.get(5L, TimeUnit.SECONDS) }
            assertEquals(
                200L,
                GatewayCapacityProcessState.admission(NOW).snapshot?.version,
            )
        } finally {
            executor.shutdownNow()
        }

        assertTrue(GatewayCapacityProcessState.tryBeginRefresh())
        assertFalse(GatewayCapacityProcessState.tryBeginRefresh())
        assertTrue(GatewayCapacityProcessState.finishRefresh())
        assertTrue(GatewayCapacityProcessState.tryBeginRefresh())
        assertFalse(GatewayCapacityProcessState.finishRefresh())
    }

    @Test
    fun newerSessionGenerationFencesLateCapacityAndStartsMissing() {
        GatewayCapacityProcessState.fenceSessionGeneration(10L)
        assertEquals(
            GatewayCapacityUpdateDisposition.ACCEPTED,
            GatewayCapacityProcessState.apply(
                validSnapshot(1L, GatewayCapacityLevel.NORMAL),
                NOW,
                expectedSessionGeneration = 10L,
            ).disposition,
        )

        GatewayCapacityProcessState.fenceSessionGeneration(11L)
        assertEquals(
            GatewayCapacityAvailability.MISSING,
            GatewayCapacityProcessState.admission(NOW).availability,
        )
        assertEquals(
            GatewayCapacityUpdateDisposition.REJECTED_SESSION_GENERATION,
            GatewayCapacityProcessState.apply(
                validSnapshot(2L, GatewayCapacityLevel.NORMAL),
                NOW,
                expectedSessionGeneration = 10L,
            ).disposition,
        )
        assertEquals(
            GatewayCapacityAvailability.MISSING,
            GatewayCapacityProcessState.admission(NOW).availability,
        )
        assertEquals(
            GatewayCapacityUpdateDisposition.ACCEPTED,
            GatewayCapacityProcessState.apply(
                validSnapshot(2L, GatewayCapacityLevel.ADMIN_ONLY_WARNING),
                NOW,
                expectedSessionGeneration = 11L,
            ).disposition,
        )
    }

    private fun validSnapshot(
        version: Long,
        level: GatewayCapacityLevel,
        observedAt: Instant = OBSERVED_AT,
        expiresAt: Instant = EXPIRES_AT,
    ): GatewayCapacityParseResult.Valid = GatewayCapacityParseResult.Valid(
        GatewayCapacitySnapshot(
            version = version,
            observedAt = observedAt,
            expiresAt = expiresAt,
            level = level,
            reason = GatewayCapacityReason.STORAGE_UTILIZATION,
        ),
    )

    private fun capacityJson(
        version: Long = 1L,
        level: GatewayCapacityLevel = GatewayCapacityLevel.NORMAL,
        observedAt: String = "2026-08-25T00:00:00Z",
        expiresAt: String = "2026-08-25T00:05:00Z",
    ): JSONObject = JSONObject()
        .put("version", version)
        .put("observed_at", observedAt)
        .put("expires_at", expiresAt)
        .put("level", level.name)
        .put("reason", GatewayCapacityReason.STORAGE_UTILIZATION.name)

    private fun validCapacityText(): String =
        """{"version":1,"observed_at":"2026-08-25T00:00:00Z","expires_at":"2026-08-25T00:05:00Z","level":"NORMAL","reason":"STORAGE_UTILIZATION"}"""

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-25T00:01:00Z")
        val OBSERVED_AT: Instant = Instant.parse("2026-08-25T00:00:00Z")
        val EXPIRES_AT: Instant = Instant.parse("2026-08-25T00:05:00Z")
    }
}
