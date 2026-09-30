package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityController
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkLease
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkStartResult
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.session.WalkSessionMode
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessCollector
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessObservation
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessPlan
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionRecoveryStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** S03-19: an Activity re-creation is not a process restart; a restart still gets a new walk. */
class WalkConfigurationChangeRetentionTest {
    private var elapsedMs = 10_000L

    @Test
    fun configurationRecreationContinuesTheSamePausedWalkAndLease() {
        val (lifecycle, authority, activeEpoch) = activeWalk()
        // onPause of the old Activity (existing path): background + lease rebind.
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        val pausedEpoch = lifecycle.snapshot().epoch
        assertTrue(authority.rebindEpoch(activeEpoch, pausedEpoch))

        val retention = WalkConfigurationChangeRetention(maxAgeMs = WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS)
        assertTrue(retention.retain(lifecycle, authority, elapsedMs))
        elapsedMs += 300L
        val retained = requireNotNull(retention.take(elapsedMs))
        val next = GatewayWalkAuthorityController(elapsedClock = { elapsedMs })
        assertTrue(next.adoptHeldLeaseFrom(retained.authority))

        val snapshot = retained.lifecycle.snapshot()
        assertSame(lifecycle, retained.lifecycle)
        assertEquals(activeEpoch.walkSessionId, snapshot.epoch.walkSessionId)
        assertTrue(snapshot.epoch.recoveryGeneration > activeEpoch.recoveryGeneration)
        assertEquals(WalkSessionState.PAUSED, snapshot.state)
        assertEquals(WalkSessionRecoveryStage.RECHECK_REQUIRED, snapshot.recoveryStage)
        assertNull("old evidence is never carried", snapshot.readiness)
        assertNotNull(next.activeLeaseOrNull(pausedEpoch))
        assertNull(authority.activeLeaseOrNull(pausedEpoch))
        assertTrue(next.tracksWalk(activeEpoch.walkSessionId))
        // Returning to the foreground still needs a recheck and an explicit confirmation.
        retained.lifecycle.handle(WalkSessionEvent.EnteredForeground)
        assertEquals(WalkSessionState.PAUSED, retained.lifecycle.snapshot().state)
        assertEquals(WalkSessionRecoveryStage.RECHECK_REQUIRED, retained.lifecycle.snapshot().recoveryStage)
        assertNull(retained.lifecycle.currentRuntimeEpochOrNull())
        // One-shot.
        assertNull(retention.take(elapsedMs))
    }

    @Test
    fun onlyAWalkWaitingForRecheckIsHandedOver() {
        val retention = WalkConfigurationChangeRetention(maxAgeMs = 30_000L)
        val ready = WalkSessionLifecycle(sessionIdFactory = { "walk-ready" }, clock = { elapsedMs })
        assertFalse(retention.retain(ready, authority(), elapsedMs))
        val (active, activeAuthority, _) = activeWalk()
        assertFalse("an ACTIVE walk is paused by onPause first", retention.retain(active, activeAuthority, elapsedMs))
        active.handle(WalkSessionEvent.EndRequested)
        assertFalse(retention.retain(active, activeAuthority, elapsedMs))
        assertNull(retention.take(elapsedMs))
    }

    @Test
    fun staleOrChangedHandOffIsDroppedSoTheExistingNewWalkPathRuns() {
        val retention = WalkConfigurationChangeRetention(maxAgeMs = 30_000L)
        val (lifecycle, authority, _) = activeWalk()
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        assertTrue(retention.retain(lifecycle, authority, 1_000L))
        assertNull(retention.take(31_001L))
        assertTrue(retention.retain(lifecycle, authority, 1_000L))
        assertNull("clock regression is not a valid hand-off", retention.take(999L))
        assertTrue(retention.retain(lifecycle, authority, 1_000L))
        lifecycle.handle(WalkSessionEvent.EndRequested)
        assertNull(retention.take(1_100L))
        assertTrue(retention.retain(activeWalk().first.also { it.handle(WalkSessionEvent.EnteredBackground) }, authority, 1_000L))
        retention.clear()
        assertNull(retention.take(1_100L))
    }

    @Test
    fun expiredLeaseIsNotAdopted() {
        val (lifecycle, authority, activeEpoch) = activeWalk()
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        authority.rebindEpoch(activeEpoch, lifecycle.snapshot().epoch)
        elapsedMs = 95_000L
        val next = GatewayWalkAuthorityController(elapsedClock = { elapsedMs })
        assertFalse(next.adoptHeldLeaseFrom(authority))
        assertFalse(next.adoptHeldLeaseFrom(next))
        assertNull(next.activeLeaseOrNull(lifecycle.snapshot().epoch))
    }

    @Test
    fun aRestartedProcessStillCreatesANewWalk() {
        val first = WalkSessionLifecycle(elapsedRealtimeClock = { elapsedMs })
        val restarted = WalkSessionLifecycle(elapsedRealtimeClock = { elapsedMs })
        assertNotEquals(first.snapshot().epoch.walkSessionId, restarted.snapshot().epoch.walkSessionId)
        // A new process has an empty process-scoped hand-off.
        assertNull(WalkConfigurationChangeRetention(maxAgeMs = 30_000L).take(elapsedMs))
    }

    @Test
    fun activityHandsOverOnlyForConfigurationChangesAndSkipsTheRestartNotice() {
        val source = sequenceOf(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
            File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        ).first { it.isFile }.readText()
        val create = source.substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("override fun onStart()")
        val fresh = create.indexOf("walkSessionLifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)")
        val take = create.indexOf("processWalkConfigurationChangeRetention.take(SystemClock.elapsedRealtime())")
        val adopt = create.indexOf("gatewayWalkAuthorityController.adoptHeldLeaseFrom(retained.authority)")
        assertTrue(fresh >= 0 && take > fresh && adopt > take)
        assertTrue(create.indexOf("gatewayWalkAuthorityController.reset()") in (fresh + 1) until take)
        assertTrue(create.contains("walkSessionLifecycle = retained.lifecycle"))
        assertTrue(
            create.contains(
                "previousProcessHadInterruptedWalk = !walkContinuedAfterConfigurationChange &&",
            ),
        )
        assertTrue(create.contains("interruptedAtStartup = previousProcessHadInterruptedWalk"))

        val destroy = source.substringAfter("override fun onDestroy()").substringBefore("override fun onActivityResult(")
        val retain = destroy.substringAfter("val walkRetainedForConfigurationChange = isChangingConfigurations &&")
            .substringBefore("gatewayWalkStartConfirmationToken = null")
        assertTrue(retain.contains("processWalkConfigurationChangeRetention.retain("))
        assertTrue(retain.contains("if (!walkRetainedForConfigurationChange) gatewayWalkAuthorityController.reset()"))
        // Large-text changes must still re-create the Activity (FP-030); no configChanges opt-out.
        val manifest = sequenceOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
            .first { it.isFile }.readText()
        assertFalse(manifest.contains("android:configChanges"))
    }

    private fun activeWalk(): Triple<WalkSessionLifecycle, GatewayWalkAuthorityController, WalkRuntimeEpoch> {
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "walk-0001" }, clock = { 1_000L })
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(ready(lifecycle.snapshot().epoch)))
        val token = requireNotNull(lifecycle.snapshot().confirmationToken)
        val authority = authority()
        val operation = requireNotNull(authority.beginStart(token.epoch))
        authority.completeStart(operation, GatewayWalkStartResult.Granted(lease()))
        lifecycle.handle(WalkSessionEvent.StartRequested(token))
        assertEquals(WalkSessionState.ACTIVE, lifecycle.snapshot().state)
        return Triple(lifecycle, authority, lifecycle.snapshot().epoch)
    }

    private fun authority() = GatewayWalkAuthorityController(
        requestIdFactory = { "request-00000001" },
        elapsedClock = { elapsedMs },
    )

    private fun ready(epoch: WalkRuntimeEpoch): WalkSessionReadinessSnapshot {
        val plan = WalkSessionReadinessPlan(action = WalkSessionAction.START_WALK, mode = WalkSessionMode.FULL)
        val collector = WalkSessionReadinessCollector(epoch, plan)
        plan.requiredRequirements.forEach { requirement ->
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = requirement,
                    status = WalkSessionReadinessStatus.READY,
                ),
            )
        }
        return collector.build(assessedAtElapsedRealtimeMs = 1_000L)
    }

    private fun lease(): GatewayWalkLease = GatewayWalkLease(
        result = "ACQUIRED",
        actorId = "actor-test",
        deviceId = "device-0001",
        walkId = "walk-0001",
        leaseId = "lease-0001",
        fencingToken = 3L,
        acquiredAtEpochMs = 1_000_000L,
        leaseExpiresAtEpochMs = 1_090_000L,
        serverTimeEpochMs = 1_000_000L,
        localDeadlineElapsedMs = 95_000L,
    )
}
