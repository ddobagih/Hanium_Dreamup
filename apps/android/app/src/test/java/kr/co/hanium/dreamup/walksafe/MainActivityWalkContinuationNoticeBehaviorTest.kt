package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityCompletion
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityController
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkLease
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkStartResult
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.session.WalkSessionMode
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessCollector
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessObservation
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessPlan
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionResumeConfirmation
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Real Main text selection with real lifecycle/controller and controlled readiness/grants.
 * The private Android expiry callback, Views, server and native output are not executed here. */
class MainActivityWalkContinuationNoticeBehaviorTest {
    @Test fun sameWalkBeforeDeadlineAndStoppedWalkAtDeadlineHaveDifferentNextActions() {
        val f = Fixture()
        val original = f.lifecycle.snapshot().epoch
        f.now = f.lease.localDeadlineElapsedMs - 1L
        assertSame(f.lease, f.authority.activeLeaseOrNull(original))
        val paused = f.lifecycle.snapshot()
        assertTrue(f.message().contains("진행하던 보행을 이어갑니다"))
        assertFalse(f.message().contains("기존 보행은 끝났습니다"))
        assertEquals(paused, f.lifecycle.snapshot())

        f.now = f.lease.localDeadlineElapsedMs
        assertNull(f.authority.activeLeaseOrNull(original))
        // Actual Main expiry enters SafetyStopRequested; invoke that real reducer event here.
        // This is not execution of handleGatewayWalkAuthorityLost or its Handler callback.
        f.lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
        val stopped = f.lifecycle.snapshot()
        assertTrue(f.message().startsWith("원래 안전 중지 사유"))
        assertTrue(f.message().contains("기존 보행은 끝났습니다"))
        assertTrue(f.message().contains("준비 상태를 점검하고 시작을 확인하세요"))
        assertEquals(stopped, f.lifecycle.snapshot())
    }

    @Test fun longAfterExpiryOnlyExplicitNewWalkAndStartCanConsumeANewGrant() {
        val f = Fixture()
        val old = f.lifecycle.snapshot().epoch
        val oldToken = requireNotNull(f.lifecycle.snapshot().confirmationToken)
        f.now = f.lease.localDeadlineElapsedMs + 300_000L
        assertNull(f.authority.activeLeaseOrNull(old))
        f.lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
        val stopped = f.lifecycle.snapshot()
        assertTrue(f.message().contains("기존 보행은 끝났습니다"))
        assertFalse(f.lifecycle.handle(WalkSessionEvent.ResumeConfirmationReceived(
            WalkSessionResumeConfirmation.START, oldToken)).changed)
        assertEquals(stopped, f.lifecycle.snapshot())
        assertNull(f.authority.beginRenew(old))

        f.lifecycle.handle(WalkSessionEvent.NewWalkRequested)
        val next = f.lifecycle.snapshot().epoch
        assertNotEquals(old.walkSessionId, next.walkSessionId)
        assertEquals(WalkSessionState.READY, f.lifecycle.snapshot().state)
        f.lifecycle.handle(WalkSessionEvent.EnteredForeground)
        f.ready(WalkSessionAction.START_WALK)
        val operation = requireNotNull(f.authority.beginStart(next))
        val newLease = f.grant(next.walkSessionId, "new-lease", f.now + 100_000L)
        assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
            f.authority.completeStart(operation, GatewayWalkStartResult.Granted(newLease)))
        assertEquals(WalkSessionState.READY, f.lifecycle.snapshot().state)
        val token = requireNotNull(f.lifecycle.snapshot().confirmationToken)
        f.lifecycle.handle(WalkSessionEvent.StartRequested(token))
        assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        assertSame(newLease, f.authority.activeLeaseOrNull(next))
        assertEquals("기존 진행 상태", f.message())
    }

    @Test fun stoppedOwnerReasonIsKeptAndOtherStateMessagesRemainUnchanged() {
        val f = Fixture()
        f.lifecycle.handle(WalkSessionEvent.SafetyStopRequested)
        val stopped = f.lifecycle.snapshot()
        val owned = f.message("카메라 상태를 확인할 수 없어 중지했습니다.")
        assertTrue(owned.startsWith("보행 안전 중지\n카메라 상태를 확인할 수 없어 중지했습니다."))
        assertTrue(owned.contains("준비 상태를 점검하고 시작을 확인하세요"))
        assertEquals(stopped, f.lifecycle.snapshot())
        f.lifecycle.handle(WalkSessionEvent.EndRequested)
        assertEquals("보행을 종료했습니다.\n\n기존 경로 개괄", f.message())
    }

    @Test fun sourceKeepsLeaseExpiryAndExplicitResumeOrNewStartInTheirExistingCallers() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val revalidate = MainActivityRefactorSource.function(source, "private fun revalidateGatewayWalkAfterForegroundReturn()")
        assertTrue(revalidate.contains("authority.activeLeaseOrNull(snapshot.epoch) == null"))
        assertTrue(revalidate.indexOf("handleGatewayWalkAuthorityLost(") < revalidate.indexOf("requestGatewayWalkRenewal()"))
        val lost = MainActivityRefactorSource.function(source, "private fun handleGatewayWalkAuthorityLost(")
        assertTrue(lost.contains("authority.invalidate(snapshot.epoch)"))
        assertTrue(lost.contains("runtime.safetyStop(reason)"))
        assertTrue(lost.contains("\$detailKo\\n\\n\$restartNoticeKo"))
        val gatewayBinding = MainActivityRefactorSource.composition(source, "gatewayWalkSessionController")
        assertTrue(gatewayBinding.contains("authority = gatewayWalkAuthorityController"))
        assertTrue(gatewayBinding.contains("override fun safetyStop(reason: String) = enterWalkSessionSafetyStopAndCancelOutputs(reason)"))
        assertTrue(gatewayBinding.contains("restartNoticeKo = WALK_RESTART_NOTICE_KO"))
        val resume = MainActivityRefactorSource.function(source, "private fun requestWalkSessionResumeConfirmation()")
        assertTrue(resume.contains("snapshot.state != WalkSessionState.PAUSED"))
        assertTrue(resume.contains("재점검 뒤 진행하던 보행을 이어갈까요? 시작 또는 취소라고 말해 주세요"))
        val readiness = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DeviceCheckPresenter.kt").readText()
        assertTrue(ReportStaticSourceInspector.functionBlock(readiness, "fun startupPresentation").contains("preparesNewWalk -> \"새 보행 준비\""))
        assertTrue(source.contains("deviceCheckPresenter.renderStartupCapability("))
    }

    private class Fixture {
        var now = 100L
        val main = MainActivity()
        val lifecycle = WalkSessionLifecycle(clock = { now }, elapsedRealtimeClock = { now })
        val authority = GatewayWalkAuthorityController(elapsedClock = { now })
        lateinit var lease: GatewayWalkLease

        init {
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            ready(WalkSessionAction.START_WALK)
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            lifecycle.handle(WalkSessionEvent.EnteredBackground)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            ready(WalkSessionAction.RESUME_WALK)
            val epoch = lifecycle.snapshot().epoch
            val operation = requireNotNull(authority.beginStart(epoch))
            lease = grant(epoch.walkSessionId, "held-lease", 100_000L)
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
                authority.completeStart(operation, GatewayWalkStartResult.Granted(lease)))
        }

        fun ready(action: WalkSessionAction) {
            val epoch = lifecycle.snapshot().epoch
            val collector = WalkSessionReadinessCollector(epoch, WalkSessionReadinessPlan(action, WalkSessionMode.FULL))
            collector.plan.requiredRequirements.forEach {
                collector.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY))
            }
            lifecycle.handle(if (action == WalkSessionAction.START_WALK)
                WalkSessionEvent.InitialCheckCompleted(collector.build(now))
            else WalkSessionEvent.RecoveryRecheckCompleted(collector.build(now)))
        }

        fun grant(walkId: String, leaseId: String, deadline: Long) = GatewayWalkLease(
            result = "GRANTED", actorId = "notice-actor", deviceId = "notice-device",
            walkId = walkId, leaseId = leaseId, fencingToken = 1L,
            acquiredAtEpochMs = now, leaseExpiresAtEpochMs = deadline,
            serverTimeEpochMs = now, localDeadlineElapsedMs = deadline)

        fun message(summary: String? = null) = main.currentStateSpeechMessage(lifecycle.snapshot(), summary,
            { "기존 경로 개괄" }, { "기존 진행 상태" }, { "원래 일시정지 사유" }, { "원래 안전 중지 사유" })
    }
}
