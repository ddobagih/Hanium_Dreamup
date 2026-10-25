package kr.co.hanium.dreamup.walksafe

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityCompletion
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityController
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkLease
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkStartResult
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionConfirmationToken
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.session.WalkSessionMode
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessCollector
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessObservation
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessPlan
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Calls the real private Main entry points and real lifecycle. These early guard tests do not
 * execute fresh Android readiness collection, successful activation, native UI or physical TTS.
 * The uninitialized UI is intentional: an unexpected updateStatus call must fail this test.
 */
class MainActivityResumeAdmissionBehaviorTest {
    @Test
    fun buttonAndVoiceRejectForegroundLossAfterTheConfirmationWasPrepared() {
        ResumeMainEntry.entries.forEach { entry ->
            val fixture = ResumeMainBehaviorFixture()
            val before = fixture.lifecycle.snapshot()
            fixture.set("isActivityForeground", false)

            fixture.confirm(entry)

            fixture.assertNoAdmissionOrStatus(before, entry.name)
        }
    }

    @Test
    fun buttonAndVoiceCannotResumeAnAlreadyActiveWalk() {
        ResumeMainEntry.entries.forEach { entry ->
            val fixture = ResumeMainBehaviorFixture()
            val token = fixture.token()
            fixture.lifecycle.handle(
                WalkSessionEvent.ResumeConfirmationReceived(
                    kr.co.hanium.dreamup.walksafe.session.WalkSessionResumeConfirmation.START,
                    token,
                ),
            )
            assertEquals(WalkSessionState.ACTIVE, fixture.lifecycle.snapshot().state)
            val before = fixture.lifecycle.snapshot()

            fixture.confirm(entry, token)

            fixture.assertNoAdmissionOrStatus(before, entry.name)
        }
    }

    @Test
    fun buttonAndVoiceKeepTheRecheckRequiredStageAfterReadinessInvalidation() {
        ResumeMainEntry.entries.forEach { entry ->
            val fixture = ResumeMainBehaviorFixture()
            val oldToken = fixture.token()
            fixture.lifecycle.handle(WalkSessionEvent.RecheckRequested)
            val before = fixture.lifecycle.snapshot()
            assertNull(before.confirmationToken)

            fixture.confirm(entry, oldToken)

            fixture.assertNoAdmissionOrStatus(before, entry.name)
        }
    }

    @Test
    fun voiceCannotApplyAnOldEpochTokenToANewlyPreparedConfirmation() {
        val fixture = ResumeMainBehaviorFixture()
        val oldToken = fixture.token()
        fixture.lifecycle.handle(WalkSessionEvent.RecheckRequested)
        fixture.prepareResumeReadiness()
        val before = fixture.lifecycle.snapshot()
        assertFalse(oldToken.epoch == before.epoch)

        fixture.confirm(ResumeMainEntry.VOICE, oldToken)

        fixture.assertNoAdmissionOrStatus(before, "old epoch voice")
        assertSame(oldToken, fixture.field("walkSessionResumeConfirmationToken"))
    }

    @Test
    fun voiceCannotUseDifferentReadinessEvenWhenTheTokenEpochMatches() {
        val fixture = ResumeMainBehaviorFixture()
        val currentToken = fixture.token()
        val replacedToken = WalkSessionConfirmationToken(
            fixture.readiness(WalkSessionAction.RESUME_WALK, assessedAt = fixture.now - 1L),
        )
        assertEquals(currentToken.epoch, replacedToken.epoch)
        assertFalse(currentToken == replacedToken)
        val before = fixture.lifecycle.snapshot()

        fixture.confirm(ResumeMainEntry.VOICE, replacedToken)

        fixture.assertNoAdmissionOrStatus(before, "different readiness voice")
    }

    @Test
    fun voiceWithoutAStoredPromptTokenCannotConfirmTheCurrentPausedWalk() {
        val fixture = ResumeMainBehaviorFixture()
        val before = fixture.lifecycle.snapshot()
        fixture.set("walkSessionResumeConfirmationToken", null)

        fixture.invoke(
            "handleWalkSessionResumeRecognition",
            arrayOf(List::class.java, FloatArray::class.java),
            listOf("시작"), floatArrayOf(1f),
        )

        fixture.assertNoAdmissionOrStatus(before, "no voice token")
    }
}

internal enum class ResumeMainEntry { BUTTON, VOICE }

/** Fixture owns only clock and existing Main state; it supplies no replacement Boolean gate. */
internal class ResumeMainBehaviorFixture {
    var now = 100L
    val main = MainActivity()
    val lifecycle = WalkSessionLifecycle(
        sessionIdFactory = { "resume-behavior-walk" },
        clock = { now },
        elapsedRealtimeClock = { now },
    )
    val authority = GatewayWalkAuthorityController(
        requestIdFactory = { "resume-behavior-request" }, elapsedClock = { now },
    )
    private val statusOwner = field("statusPresentationOwner")

    init {
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness(WalkSessionAction.START_WALK)))
        lifecycle.handle(WalkSessionEvent.StartRequested(token()))
        lifecycle.handle(WalkSessionEvent.EnteredBackground)
        lifecycle.handle(WalkSessionEvent.EnteredForeground)
        prepareResumeReadiness()
        val operation = requireNotNull(authority.beginStart(lifecycle.snapshot().epoch))
        val lease = GatewayWalkLease(
            result = "GRANTED", actorId = "resume-actor", deviceId = "resume-device",
            walkId = lifecycle.snapshot().epoch.walkSessionId, leaseId = "resume-lease",
            fencingToken = 1L, acquiredAtEpochMs = now, leaseExpiresAtEpochMs = 100_000L,
            serverTimeEpochMs = now, localDeadlineElapsedMs = 100_000L,
        )
        assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
            authority.completeStart(operation, GatewayWalkStartResult.Granted(lease)))
        set("walkSessionLifecycle", lifecycle)
        set("gatewayWalkAuthorityController", authority)
        set("isActivityForeground", true)
    }

    fun token(): WalkSessionConfirmationToken = requireNotNull(lifecycle.snapshot().confirmationToken)

    fun readiness(action: WalkSessionAction, assessedAt: Long = now): WalkSessionReadinessSnapshot {
        val epoch = lifecycle.snapshot().epoch
        val collector = WalkSessionReadinessCollector(epoch,
            WalkSessionReadinessPlan(action, WalkSessionMode.FULL))
        collector.plan.requiredRequirements.forEach { requirement ->
            collector.record(WalkSessionReadinessObservation(epoch, requirement,
                WalkSessionReadinessStatus.READY))
        }
        return collector.build(assessedAtElapsedRealtimeMs = assessedAt)
    }

    fun prepareResumeReadiness() {
        lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(readiness(WalkSessionAction.RESUME_WALK)))
    }

    fun confirm(entry: ResumeMainEntry, voiceToken: WalkSessionConfirmationToken? = null) {
        when (entry) {
            ResumeMainEntry.BUTTON -> invoke("confirmWalkSessionResumeFromButton")
            ResumeMainEntry.VOICE -> {
                set("walkSessionResumeConfirmationToken", voiceToken ?: token())
                set("walkSessionResumePromptPending", true)
                invoke("handleWalkSessionResumeRecognition",
                    arrayOf(List::class.java, FloatArray::class.java), listOf("시작"), floatArrayOf(1f))
            }
        }
    }

    fun complete(epoch: WalkRuntimeEpoch) = invoke("completePendingWalkSessionResumeActivation",
        arrayOf(WalkRuntimeEpoch::class.java), epoch)

    fun invoke(name: String, types: Array<Class<*>> = emptyArray(), vararg args: Any?): Any? =
        // getDeclaredMethod resolves every signature in this large Activity, including GL10
        // absent from the JVM test runtime. A targeted lookup resolves only this actual method.
        MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, name, MethodType.methodType(Void.TYPE, types.toList()))
            .bindTo(main).invokeWithArguments(args.toList())

    fun set(name: String, value: Any?) {
        MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
    }

    fun field(name: String): Any? = MainActivityControllerTestFixture.field(name)
        .apply { isAccessible = true }.get(main)

    fun assertNoAdmissionOrStatus(before: kr.co.hanium.dreamup.walksafe.session.WalkSessionSnapshot,
        description: String) {
        assertEquals(description, before, lifecycle.snapshot())
        assertNull(description, field("walkSessionResumeActivationToken"))
        assertNoStatusAndNoActuatorInstance(description)
    }

    fun assertNoStatusAndNoActuatorInstance(description: String) {
        // updateStatus changes this owner before touching any Android View; unchanged identity
        // directly rules out that status sink. This is not a physical UI/TTS assertion.
        assertSame(description, statusOwner, field("statusPresentationOwner"))
        // Instance absence is an additional field observation, not a native speech-call counter.
        // The success speech branch is ruled out only by production SOURCE call order:
        // completePendingWalkSessionResumeActivation calls updateStatus before speakInteraction.
        assertNull(description, field("feedbackActuator"))
        assertTrue(description, authority.tracksWalk(lifecycle.snapshot().epoch.walkSessionId))
    }
}
