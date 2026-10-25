package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Current-walk interruption storage remains; the previous-walk notice has no entry point. */
class MainActivityWalkInterruptionRecordStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun startupCreatesAFreshWalkWithoutAnAcknowledgementGate() {
        val create = block("override fun onCreate(savedInstanceState: Bundle?)")
        assertTrue(create.contains("walkSessionLifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)"))
        assertTrue(create.contains("previous_walk_interrupted_fresh_walk_created"))
        for (removed in listOf("PreviousWalkInterruptionMarker", "previousWalkInterruptionNoticeScheduled",
            "showPreviousWalkInterruptionNoticeIfNeeded", "pendingFreshWalkActivationToken",
            "completePendingFreshWalkActivation", "이전 보행 중단")) assertFalse(removed, source.contains(removed))
        val start = block("private fun completeGatewayWalkActivation(")
        assertTrue(start.contains("runtime.currentStartReadinessOrNull(token)"))
        assertTrue(start.contains("runtime.startRequested(token, currentReadiness)"))
        assertTrue(start.contains("if (runtime.isActive())"))
        assertTrue(start.contains("runtime.activate()"))
        assertTrue(block("private fun startWalkSessionRuntimeAfterCameraRelease(")
            .contains("effects.completeResumeActivation(expectedEpoch)"))
        assertTrue(MainActivityRefactorSource.composition(source, "walkRuntimeCameraAdmissionController")
            .contains("override fun completeResumeActivation(epoch: WalkRuntimeEpoch) = completePendingWalkSessionResumeActivation(epoch)"))
    }

    @Test
    fun persistenceRecordsOnlyTheCurrentWalkAndRetainsCommitFailureSafety() {
        val persist = block("private fun persistWalkSessionInterruptionMarker()")
        val current = block("private fun currentWalkInterruptionRecordOrNull()")
        assertTrue(persist.contains("putWalkInterruptionRecord(currentRecord)"))
        assertTrue(persist.indexOf("putWalkInterruptionRecord(currentRecord)") <
            persist.indexOf(".putBoolean(PREF_WALK_SESSION_INTERRUPTED, interrupted).commit()"))
        assertTrue(current.contains("state = snapshot.state"))
        assertTrue(current.contains("walkSessionId = snapshot.epoch.walkSessionId"))
        assertTrue(current.contains("actorId = currentReporterUserId()"))
        for (state in listOf("ACTIVE", "PAUSED", "SAFE_STOP")) assertTrue(persist.contains("WalkSessionState.$state"))
        val activate = block("private fun activateWalkSessionRuntime()")
        assertTrue(activate.contains("if (!effects.persistInterruptionMarker())"))
        assertTrue(activate.contains("effects.foregroundRecheck(\"interruption_marker_write_failed\")"))
        assertTrue(source.contains("const val PREF_WALK_SESSION_INTERRUPTION_RECORD = \"walk_session_interruption_v2\""))
    }

    private fun block(signature: String): String = MainActivityRefactorSource.function(source, signature)
}
