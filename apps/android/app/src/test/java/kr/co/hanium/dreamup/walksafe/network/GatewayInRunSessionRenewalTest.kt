package kr.co.hanium.dreamup.walksafe.network

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * login-F1/F4/F9 (owner decision 2, Root decision R1): decisions of the in-run renewal of the
 * Android long-lived Gateway login (GatewayInRunSessionRenewal.kt).
 */
class GatewayInRunSessionRenewalTest {
    @Before
    fun reset() {
        resetGatewayRefreshProofFenceForTests()
        GatewayCapacityProcessState.resetForTests()
    }

    @After
    fun resetAfter() = reset()

    @Test
    fun expiredLongLivedLoginIsRenewedNotCleared() {
        val session = longLived(accessExpiresAt = NOW + 900_000L)
        assertEquals(GatewayInRunSessionAction.USE, action(session, NOW))
        // Access expired (900 s example): before login-F1 this cleared the login.
        val expired = NOW + 900_000L
        assertFalse(session.isUsableFor(ACTOR, expired))
        assertEquals(GatewayInRunSessionAction.RENEW, action(session, expired))
        assertEquals(GatewayInRunSessionAction.RENEW, action(session, NOW + TEN_YEARS))
        // Proactive and unauthorized triggers renew a still usable login too.
        assertEquals(
            GatewayInRunSessionAction.RENEW,
            GatewayInRunRenewalPolicy.actionFor(session, GatewayInRunRenewalTrigger.PROACTIVE, NOW),
        )
        assertEquals(
            GatewayInRunSessionAction.RENEW,
            GatewayInRunRenewalPolicy.actionFor(session, GatewayInRunRenewalTrigger.UNAUTHORIZED, NOW),
        )
    }

    @Test
    fun nonRenewableSessionsStillClearAsBefore() {
        val expired = NOW + 900_000L
        // Short token session (v5 style) without a refresh value.
        val short = GatewayFieldSession.verified(ORIGIN, ACTOR, cookie("short"), NOW + 60_000L)
        assertEquals(GatewayInRunSessionAction.CLEAR, action(short, expired))
        assertEquals(
            GatewayInRunSessionAction.CLEAR,
            GatewayInRunRenewalPolicy.actionFor(short, GatewayInRunRenewalTrigger.UNAUTHORIZED, NOW),
        )
        assertEquals(
            GatewayInRunSessionAction.IGNORE,
            GatewayInRunRenewalPolicy.actionFor(short, GatewayInRunRenewalTrigger.PROACTIVE, NOW),
        )
        // Finite idle window passed: the long-lived login is no longer renewable.
        val idleBound = longLived(accessExpiresAt = NOW + 60_000L, idleExpiresAt = NOW + 120_000L,
            absoluteExpiresAt = NOW + 240_000L)
        assertEquals(GatewayInRunSessionAction.RENEW, action(idleBound, NOW + 60_000L))
        assertEquals(GatewayInRunSessionAction.CLEAR, action(idleBound, NOW + 120_000L))
        // An invalidated credential (logged out, replaced, unknown refresh outcome) is never renewed.
        val invalidated = longLived(accessExpiresAt = NOW + 60_000L).also { it.invalidate() }
        assertEquals(GatewayInRunSessionAction.CLEAR, action(invalidated, expired))
        // A restored, not yet verified login is the start path's business.
        val restored = requireNotNull(GatewayFieldSession.restore(
            requireNotNull(longLived(accessExpiresAt = NOW + 60_000L).persistenceSnapshotOrNull()),
            ORIGIN, ACTOR, DEVICE, NOW,
        ))
        assertEquals(GatewayInRunSessionAction.IGNORE, action(restored, expired))
        assertEquals(GatewayInRunSessionAction.IGNORE, action(null, expired))
    }

    @Test
    fun proactiveRenewalIsArmedAboutOneMinuteBeforeAccessExpiry() {
        val session = longLived(accessExpiresAt = NOW + 900_000L)
        assertEquals(840_000L, GatewayInRunRenewalPolicy.proactiveDelayMsOrNull(session, NOW))
        assertEquals(50_000L, GatewayInRunRenewalPolicy.proactiveDelayMsOrNull(session, NOW + 800_000L))
        assertEquals(1_000L, GatewayInRunRenewalPolicy.proactiveDelayMsOrNull(session, NOW + 898_500L))
        assertEquals(0L, GatewayInRunRenewalPolicy.proactiveDelayMsOrNull(session, NOW + 899_500L))
        assertEquals(0L, GatewayInRunRenewalPolicy.proactiveDelayMsOrNull(session, NOW + 5_000_000L))
        val short = GatewayFieldSession.verified(ORIGIN, ACTOR, cookie("short"), NOW + 900_000L)
        assertNull(GatewayInRunRenewalPolicy.proactiveDelayMsOrNull(short, NOW))
        assertNull(GatewayInRunRenewalPolicy.proactiveDelayMsOrNull(null, NOW))
    }

    @Test
    fun concurrentCallersShareOneRenewalAndAllWaitersRunOnce() {
        val gate = GatewayInRunRenewalGate()
        val lease = longLived(accessExpiresAt = NOW).lease
        val started = AtomicInteger()
        val joined = AtomicInteger()
        val ran = ConcurrentLinkedQueue<Int>()
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val done = CountDownLatch(CALLERS)
        repeat(CALLERS) { caller ->
            pool.execute {
                go.await()
                when (gate.tryBegin(lease, 10L) { ran += caller }) {
                    GatewayInRunRenewalGate.Admission.STARTED -> started.incrementAndGet()
                    GatewayInRunRenewalGate.Admission.JOINED -> joined.incrementAndGet()
                    GatewayInRunRenewalGate.Admission.DEFERRED -> error("not deferred")
                }
                done.countDown()
            }
        }
        go.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(1, started.get())
        assertEquals(CALLERS - 1, joined.get())
        assertTrue(gate.isInFlight())
        assertTrue(gate.joinIfInFlight { ran += -1 })
        // Only the owner's lease ends the flight; a foreign lease changes nothing.
        assertTrue(gate.finish(longLived(accessExpiresAt = NOW).lease).isEmpty())
        assertTrue(gate.isInFlight())
        val waiters = gate.finish(lease)
        assertEquals(CALLERS + 1, waiters.size)
        waiters.forEach { it() }
        assertEquals((listOf(-1) + (0 until CALLERS)).sorted(), ran.toList().sorted())
        assertFalse(gate.isInFlight())
        assertFalse(gate.joinIfInFlight { error("no renewal is running") })
        assertEquals(GatewayInRunRenewalGate.Admission.STARTED, gate.tryBegin(lease, 20L))
    }

    @Test
    fun busyOrOfflineDefersTheNextAttemptWithoutClearing() {
        val gate = GatewayInRunRenewalGate()
        val lease = longLived(accessExpiresAt = NOW).lease
        assertEquals(GatewayInRunRenewalGate.Admission.STARTED, gate.tryBegin(lease, 1_000L))
        assertTrue(gate.finish(lease, retryNotBeforeElapsedMs = 16_000L).isEmpty())
        assertEquals(15_000L, gate.deferralRemainingMs(1_000L))
        assertEquals(GatewayInRunRenewalGate.Admission.DEFERRED, gate.tryBegin(lease, 15_999L))
        assertEquals(0L, gate.deferralRemainingMs(16_000L))
        assertEquals(GatewayInRunRenewalGate.Admission.STARTED, gate.tryBegin(lease, 16_000L))
    }

    @Test
    fun renewalFailureCodesMapAsBefore() {
        val lease = longLived(accessExpiresAt = NOW).lease
        assertSame(
            GatewayRenewalFailureAction.DuplicateAttempt,
            GatewayInRunRenewalPolicy.failureAction(
                GatewaySessionHttpException(0, "gateway_refresh_proof_already_attempted"), lease,
            ),
        )
        val busy = GatewaySessionHttpException(503, "gateway_refresh_failed", serverCode = "field_session_storage_busy")
        busy.refreshRetryReservation = GatewayRefreshRetryReservation(lease, 1_000L, "fingerprint", "attempt")
        assertEquals(
            GatewayRenewalFailureAction.RetryAfterRollback(busy.refreshRetryReservation!!),
            GatewayInRunRenewalPolicy.failureAction(busy, lease),
        )
        // A reservation for another lease is not this renewal's retry.
        val other = longLived(accessExpiresAt = NOW).lease
        assertEquals(
            GatewayRenewalFailureAction.Abandon(null),
            GatewayInRunRenewalPolicy.failureAction(busy, other),
        )
        for (code in listOf("refresh_token_reuse_detected", "field_session_revoked_reuse_detected")) {
            assertEquals(
                GatewayRenewalFailureAction.Abandon(GatewayRenewalFailureNotice.REUSE_DETECTED),
                GatewayInRunRenewalPolicy.failureAction(
                    GatewaySessionHttpException(401, "gateway_refresh_failed", serverCode = code), lease,
                ),
            )
        }
        assertEquals(
            GatewayRenewalFailureAction.Abandon(GatewayRenewalFailureNotice.OUTCOME_UNKNOWN),
            GatewayInRunRenewalPolicy.failureAction(
                GatewaySessionHttpException(0, "gateway_refresh_outcome_unknown",
                    nextAction = GatewaySessionNextAction.OUTCOME_UNKNOWN),
                lease,
            ),
        )
        assertEquals(
            GatewayRenewalFailureAction.Abandon(null),
            GatewayInRunRenewalPolicy.failureAction(
                GatewaySessionHttpException(401, "gateway_refresh_failed", serverCode = "field_session_revoked"),
                lease,
            ),
        )
        assertEquals(
            GatewayRenewalFailureAction.Abandon(null),
            GatewayInRunRenewalPolicy.failureAction(IllegalStateException("lease mismatch"), lease),
        )
    }

    @Test
    fun expiredLoginRenewsThroughTheClientAndTheResultIsTheSameLogin() {
        val session = login()
        val later = NOW + TEN_YEARS
        assertEquals(GatewayInRunSessionAction.RENEW, action(session, later))
        val renewedBody = sessionBody().put("rotation", 1L).put("refresh_token", "s".repeat(64))
            .put("access_expires_at_epoch_ms", later + 900_000L)
        val renewal = GatewayFieldSessionClient(
            Transport(GatewayHttpResponse(200, renewedBody.toString(), mapOf("Set-Cookie" to setCookie("renewed")))),
            elapsedRealtimeNanos = { 0L },
        ).renew(session, ACTOR, later)
        assertTrue(renewal.session.isUsableFor(ACTOR, later))
        assertTrue(GatewayInRunRenewalPolicy.isRenewedContinuation(session, renewal.session))
        assertEquals(GatewayInRunSessionAction.USE, action(renewal.session, later))
    }

    @Test
    fun revokedFamilyAnswerGivesTheReuseReasonToARunningDevice() {
        // login-F4: a running device refreshes once after a rejected access credential.
        val session = login()
        assertEquals(
            GatewayInRunSessionAction.RENEW,
            GatewayInRunRenewalPolicy.actionFor(session, GatewayInRunRenewalTrigger.UNAUTHORIZED, NOW),
        )
        val error = assertThrows(GatewaySessionHttpException::class.java) {
            GatewayFieldSessionClient(
                Transport(GatewayHttpResponse(401, JSONObject()
                    .put("code", "field_session_revoked_reuse_detected")
                    .put("reauthentication_required", true).toString())),
                elapsedRealtimeNanos = { 0L },
            ).renew(session, ACTOR, NOW)
        }
        assertEquals(
            GatewayRenewalFailureAction.Abandon(GatewayRenewalFailureNotice.REUSE_DETECTED),
            GatewayInRunRenewalPolicy.failureAction(error, session.lease),
        )
        // The rejected login can no longer be renewed, so no loop can follow.
        assertEquals(GatewayInRunSessionAction.CLEAR, action(session, NOW + 900_000L))
    }

    @Test
    fun unauthorizedProbeIsBoundedPerFamily() {
        val probe = GatewayUnauthorizedRenewalProbe(intervalMs = 300_000L)
        assertTrue(probe.tryAcquire("family-a", 1_000L))
        assertFalse(probe.tryAcquire("family-a", 2_000L))
        assertFalse(probe.tryAcquire("family-a", 300_999L))
        assertTrue(probe.tryAcquire("family-b", 3_000L))
        assertTrue(probe.tryAcquire("family-a", 400_000L))
        assertFalse(probe.tryAcquire(null, 900_000L))
    }

    @Test
    fun onlyAnInRunRenewalOfTheSameLoginKeepsTheWalkAuthority() {
        val session = longLived(accessExpiresAt = NOW)
        val renewed = longLived(accessExpiresAt = NOW + 900_000L, rotation = 1L, refresh = "s".repeat(64))
        assertTrue(GatewayInRunRenewalPolicy.isRenewedContinuation(session, renewed))
        assertFalse(GatewayInRunRenewalPolicy.isRenewedContinuation(session, session))
        assertFalse(GatewayInRunRenewalPolicy.isRenewedContinuation(renewed, session))
        assertFalse(GatewayInRunRenewalPolicy.isRenewedContinuation(session, null))
        assertFalse(GatewayInRunRenewalPolicy.isRenewedContinuation(
            session, longLived(accessExpiresAt = NOW, rotation = 1L, family = "v".repeat(32)),
        ))
        assertFalse(GatewayInRunRenewalPolicy.isRenewedContinuation(
            session, longLived(accessExpiresAt = NOW, rotation = 1L, actor = "tester-02"),
        ))
        assertFalse(GatewayInRunRenewalPolicy.isRenewedContinuation(
            session, GatewayFieldSession.verified(ORIGIN, ACTOR, cookie("short"), NOW + 60_000L),
        ))
    }

    @Test
    fun reuseNoticeIsSpokenOnlyWhenTheLiveRegionDoesNotReadIt() {
        val message = GatewayRenewalFailureNotice.REUSE_DETECTED.speech
        // S13-30-F1: screen reader on, status shown and changed -> the live region reads it.
        assertTrue(GatewayInRunRenewalPolicy.liveRegionReadsNotice(true, true, null, message, message))
        assertTrue(GatewayInRunRenewalPolicy.liveRegionReadsNotice(true, true, "다시 로그인해 주세요.", message, message))
        // Unchanged text or a hidden view is silent, and without a screen reader the app speaks.
        assertFalse(GatewayInRunRenewalPolicy.liveRegionReadsNotice(true, true, message, message, message))
        assertFalse(GatewayInRunRenewalPolicy.liveRegionReadsNotice(true, false, null, message, message))
        assertFalse(GatewayInRunRenewalPolicy.liveRegionReadsNotice(false, true, null, message, message))
        assertFalse(GatewayInRunRenewalPolicy.liveRegionReadsNotice(true, true, null, "계정 요청을 처리하고 있습니다.", message))
    }

    private fun action(session: GatewayFieldSession?, nowEpochMs: Long): GatewayInRunSessionAction =
        GatewayInRunRenewalPolicy.actionFor(session, GatewayInRunRenewalTrigger.ON_DEMAND, nowEpochMs)

    private fun longLived(
        accessExpiresAt: Long,
        idleExpiresAt: Long = GATEWAY_UNLIMITED_EXPIRY_EPOCH_MS,
        absoluteExpiresAt: Long = GATEWAY_UNLIMITED_EXPIRY_EPOCH_MS,
        rotation: Long = 0L,
        refresh: String = REFRESH_TOKEN,
        family: String = FAMILY,
        actor: String = ACTOR,
    ): GatewayFieldSession = GatewayFieldSession.longLivedSession(
        ORIGIN, actor, DEVICE, family, rotation, cookie("rotation-$rotation"), refresh,
        accessExpiresAt, idleExpiresAt, absoluteExpiresAt,
    )

    private fun login(): GatewayFieldSession =
        GatewayFieldSessionClient(
            Transport(GatewayHttpResponse(200, sessionBody().toString(), mapOf("Set-Cookie" to setCookie("login")))),
            elapsedRealtimeNanos = { 0L },
        ).login(
            gatewayBaseUrl = ORIGIN,
            actorId = ACTOR,
            token = ACCOUNT_TOKEN,
            nowEpochMs = NOW,
            enableLongLivedSession = true,
            deviceId = DEVICE,
        )

    private fun cookie(label: String) = "${GatewayFieldSession.COOKIE_NAME}=access-token-in-run-$label-0000001"

    private fun setCookie(label: String) =
        "${cookie(label)}; Path=/; HttpOnly; SameSite=Strict; Max-Age=900; Secure"

    /** field-long-session.ts sessionResponse of an `unlimited` Gateway (access 900 s). */
    private fun sessionBody(): JSONObject = JSONObject()
        .put("session_scope", "general")
        .put("actor_id", ACTOR)
        .put("device_id", DEVICE)
        .put("family_id", FAMILY)
        .put("rotation", 0L)
        .put("refresh_token", REFRESH_TOKEN)
        .put("access_expires_at_epoch_ms", NOW + 900_000L)
        .put("idle_expires_at_epoch_ms", JSONObject.NULL)
        .put("absolute_expires_at_epoch_ms", JSONObject.NULL)

    private class Transport(private val post: GatewayHttpResponse) : GatewaySessionTransport {
        override fun postJson(url: String, body: String): GatewayHttpResponse = post
        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse = error("unexpected GET")
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse = GatewayHttpResponse(204, "")
        override fun deleteJson(url: String, body: String): GatewayHttpResponse = GatewayHttpResponse(204, "")
    }

    private companion object {
        const val ORIGIN = "https://field.example"
        const val ACTOR = "tester-01"
        const val DEVICE = "android-device-in-run-01"
        const val FAMILY = "uuuuuuuuuuuuuuuuuuuuuuuuuuuuuuuu"
        const val ACCOUNT_TOKEN = "field-account-token-in-run-0001"
        val REFRESH_TOKEN = "r".repeat(64)
        const val NOW = 1_000L
        const val TEN_YEARS = 10L * 365L * 86_400_000L
        const val CALLERS = 32
    }
}
