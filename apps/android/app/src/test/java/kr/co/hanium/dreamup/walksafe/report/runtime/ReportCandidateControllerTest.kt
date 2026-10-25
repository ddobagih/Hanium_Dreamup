package kr.co.hanium.dreamup.walksafe.report.runtime

import java.util.ArrayDeque
import kr.co.hanium.dreamup.walksafe.report.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.device.WalkSessionDeviceResourceSnapshot
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.TactileSnapshotFrameResult
import kr.co.hanium.dreamup.walksafe.runtime.DetectionSnapshot
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test

class ReportCandidateControllerTest {
    @Before @After fun clearAuthority() {
        ReportPrivacyConsentSession.resetForNewEnrollment()
        ReportPrivacyConsentSession.withdraw()
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }
    @Test fun successfulExplicitQueueResultReleasesItsSharedPlaintextOwner() {
        val f = Fixture().ready()
        f.returnQueued = true
        val original = f.image.copyOf()
        f.stage(); f.beginDisclosure(); f.delivered!!.invoke(); f.runMain()
        f.controller.requestExplicitReport(ReportCandidateController.ExplicitReportRequestSource.ON_SCREEN)
        assertTrue(f.controller.status().startsWith("reportCandidate=queued"))
        assertThrows(IllegalStateException::class.java) { requireNotNull(f.returnedPayload).imageJpeg() }
        assertArrayEquals(original, f.image)
        assertTrue(f.ownedQueuedImage!!.all { it == 0.toByte() })
    }

    @Test fun constructorIsPureAndStatusHasOneOwner() {
        val f = Fixture()
        assertTrue(f.events.isEmpty())
        assertEquals("reportCandidate=blocked", f.controller.status())
        f.controller.setStatus("reportCandidate=blocked:paused")
        assertEquals("reportCandidate=blocked:paused", f.controller.status())
    }
    @Test fun disabledQueueCannotCaptureDiscloseOrStore() {
        val f = Fixture(false)
        f.controller.requestExplicitReport(ReportCandidateController.ExplicitReportRequestSource.ON_SCREEN)
        assertEquals("reportCandidate=blocked:queue_disabled", f.controller.status())
        assertFalse(f.events.contains("capture"))
        assertFalse(f.events.contains("queue"))
    }
    @Test fun disclosureCompletionIsRequiredAndFirstActionNeverEnqueues() {
        val f = Fixture().ready()
        f.stage()
        f.beginDisclosure()
        assertFalse(f.armed)
        f.controller.requestExplicitReport(ReportCandidateController.ExplicitReportRequestSource.ON_SCREEN)
        assertEquals("reportCandidate=awaiting:explicit_transfer_disclosure", f.controller.status())
        assertFalse(f.events.contains("queue"))
        f.delivered!!.invoke(); f.runMain()
        assertTrue(f.armed)
    }
    @Test fun confirmedRequestUsesTheFrozenBytesAfterNewCameraBytesArrive() {
        val f = Fixture().ready()
        val original = f.image.copyOf()
        f.stage(); f.beginDisclosure()
        f.image.fill(0)
        f.delivered!!.invoke(); f.runMain()
        f.controller.requestExplicitReport(ReportCandidateController.ExplicitReportRequestSource.ON_SCREEN)
        assertArrayEquals(original, f.queuedImage)
        assertEquals(1, f.events.count { it == "queue" })
        assertTrue(f.ownedQueuedImage!!.all { it == 0.toByte() })
        assertTrue(f.ownedQueuedMetadata!!.all { it == 0.toByte() })
    }
    @Test fun changedGatewayIdentityBeforeDisclosureCompletionCannotArmOrSubmit() {
        val f = Fixture().ready(); f.stage(); f.beginDisclosure()
        f.publish()
        f.delivered!!.invoke(); f.runMain()
        assertFalse(f.armed)
        assertFalse(f.events.contains("queue"))
    }
    @Test fun cancelledDisclosureCannotBeRevivedByItsLateCompletion() {
        val f = Fixture().ready(); f.stage(); f.beginDisclosure()
        val delayed = f.delivered!!
        f.cancelled!!.invoke(); f.runMain()
        delayed.invoke(); f.runMain()
        assertFalse(f.armed)
        assertFalse(f.events.contains("queue"))
    }
    @Test fun olderDisclosureFailureDoesNotRetireTheNewPendingPayload() {
        val f = Fixture().ready(); f.stage(); f.beginDisclosure()
        val oldFailure = f.failed!!
        f.stage(); f.beginDisclosure()
        oldFailure.invoke(); f.runMain()
        f.delivered!!.invoke(); f.runMain()
        assertTrue(f.armed)
    }
    @Test fun disclosureTimeoutUsesTwoMinutesAndConfirmationUsesThirtySeconds() {
        val f = Fixture().ready(); f.stage(); f.beginDisclosure()
        assertEquals(EXPLICIT_REPORT_DISCLOSURE_VALIDITY_MS + 1L, f.timers.last().second)
        f.delivered!!.invoke(); f.runMain()
        assertEquals(EXPLICIT_REPORT_CONFIRMATION_VALIDITY_MS + 1L, f.timers.last().second)
        f.elapsed += EXPLICIT_REPORT_CONFIRMATION_VALIDITY_MS + 1L
        f.timers.last().first.invoke()
        assertEquals("reportCandidate=blocked:confirmation_expired", f.controller.status())
        assertFalse(f.armed)
    }
    @Test fun permissionWithdrawalBeforeSecondActionZeroizesWithoutQueueing() {
        val f = Fixture().ready(); f.stage(); f.beginDisclosure()
        f.delivered!!.invoke(); f.runMain()
        f.permission = false
        f.controller.requestExplicitReport(ReportCandidateController.ExplicitReportRequestSource.ON_SCREEN)
        assertFalse(f.events.contains("queue"))
        assertFalse(f.controller.cancelExplicitReportIfOpen())
    }
    @Test fun queueFailureStillClosesOwnedPayloadBytes() {
        val f = Fixture().ready(); f.stage(); f.beginDisclosure()
        f.delivered!!.invoke(); f.runMain()
        f.queueThrows = true
        assertThrows(IllegalStateException::class.java) {
            f.controller.requestExplicitReport(ReportCandidateController.ExplicitReportRequestSource.ON_SCREEN)
        }
        assertTrue(f.ownedQueuedImage!!.all { it == 0.toByte() })
        assertTrue(f.ownedQueuedMetadata!!.all { it == 0.toByte() })
    }
    @Test fun staleFrameGenerationDoesNotInvokeMetricGuardOrPublish() {
        val f = Fixture().ready()
        f.frameCurrent = false
        assertFalse(f.controller.publishCurrentFrameReportState(1, 1L, null, DeviceGateState(), null, DeviceGateState(), null, 1L, 1L))
        assertFalse(f.events.contains("metric"))
    }
    @Test fun unavailableMetricGenerationCannotReplaceTheLastCandidateStatus() {
        val f = Fixture().ready()
        f.metricCurrent = false
        f.controller.setStatus("previous")
        assertFalse(f.controller.publishCurrentFrameReportState(1, 1L, null, DeviceGateState(), null, DeviceGateState(), null, 1L, 1L))
        assertEquals("previous", f.controller.status())
    }

    @Test fun onScreenReportWithoutMetricReadinessExplainsRefusalWithoutCapturing() {
        assertMetricReadinessRefusal(ReportCandidateController.ExplicitReportRequestSource.ON_SCREEN)
    }

    @Test fun voiceReportWithoutMetricReadinessExplainsRefusalWithoutCapturing() {
        assertMetricReadinessRefusal(ReportCandidateController.ExplicitReportRequestSource.VOICE)
    }

    @Test fun metricRefusalMessageDoesNotChangeWhenAnotherFrameReplacesCandidateStatus() {
        val f = Fixture().ready()
        f.metricCurrent = false
        f.onStatus = { status ->
            if (status == "reportCandidate=blocked:runtime_metric_unavailable") {
                f.controller.setStatus("reportCandidate=blocked:no_depth_object")
            }
        }

        f.controller.requestExplicitReport(ReportCandidateController.ExplicitReportRequestSource.ON_SCREEN)

        assertEquals(listOf("speech:$METRIC_UNAVAILABLE_NOTICE"), f.events.filter { it.startsWith("speech:") })
        assertEquals(METRIC_UNAVAILABLE_NOTICE, f.idleMessage)
        assertFalse(f.events.contains("queue"))
    }

    private fun assertMetricReadinessRefusal(source: ReportCandidateController.ExplicitReportRequestSource) {
        val f = Fixture().ready()
        f.metricCurrent = false
        val message = METRIC_UNAVAILABLE_NOTICE

        f.controller.requestExplicitReport(source)

        assertEquals("reportCandidate=blocked:runtime_metric_unavailable", f.controller.status())
        assertEquals(listOf("speech:$message"), f.events.filter { it.startsWith("speech:") })
        assertEquals(message, f.idleMessage)
        assertFalse(f.armed)
        assertFalse(f.events.contains("capture"))
        assertFalse(f.events.contains("queue"))
        assertFalse(f.controller.cancelExplicitReportIfOpen())
    }

    private class Fixture(val enabled: Boolean = true) : ReportCandidateController.Queue, ReportCandidateController.Authority,
        ReportCandidateController.FrameEvidence, ReportCandidateController.Models, ReportCandidateController.Resources,
        ReportCandidateController.Location, ReportCandidateController.Ui {
        val events = mutableListOf<String>()
        val main = ArrayDeque<() -> Unit>()
        val timers = mutableListOf<Pair<() -> Unit, Long>>()
        val consent = IntegratedConsentSession()
        val policy = PermissionSessionPolicy()
        var elapsed = 1_000L
        var permission = true
        var safety = true
        var frameCurrent = true
        var metricCurrent = true
        var armed = false
        var idleMessage: String? = null
        var onStatus: ((String) -> Unit)? = null
        var queueThrows = false
        var returnQueued = false
        var returnedPayload: FrozenReportPayload? = null
        var delivered: (() -> Unit)? = null
        var failed: (() -> Unit)? = null
        var cancelled: (() -> Unit)? = null
        var queuedImage: ByteArray? = null
        var ownedQueuedImage: ByteArray? = null
        var ownedQueuedMetadata: ByteArray? = null
        val image = jpeg()
        val epoch = WalkRuntimeEpoch(WALK, 2L)
        var actor = ACTOR
        val controller = ReportCandidateController(consent, { events.add("policy"); policy }, ReportPrivacyConsentSession,
            { error("automatic attempt not expected") }, enabled, "동일 신고 전송 안내", this, this, this, this, this, this, this,
            Any(), Any(), { 1_000L }, { elapsed })
        fun ready(): Fixture {
            publish()
            assertTrue(consent.accept(confirmation()))
            ReportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent()
            events.clear()
            return this
        }
        fun publish() {
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            val session = GatewayFieldSession.verified(ORIGIN, ACTOR, "walksafe_field_session=candidate-test-cookie-0000000001", Long.MAX_VALUE)
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(operation, session, FirstRunOnboardingPolicy.initialEmailAccount(7L)))
        }
        fun context() = ExplicitReportConfirmationContext(WALK, 2L, ACTOR, "a".repeat(64), GatewaySessionProcessCoordinator.snapshot().generation)
        fun stage() {
            val method = ReportCandidateController::class.java.getDeclaredMethod("processReportCandidate", AndroidReportCandidate::class.java,
                ByteArray::class.java, Boolean::class.javaPrimitiveType, WalkRuntimeEpoch::class.java, String::class.java,
                Long::class.javaPrimitiveType, ExplicitReportConfirmationContext::class.java, java.lang.Long::class.java)
            method.isAccessible = true
            val result = method.invoke(controller, AndroidReportCandidate(JSONObject().put("reporter_user_id", ACTOR).put("frame", 1), "explicit"),
                image, true, epoch, ACTOR, 1_000L, context(), elapsed)
            assertEquals("reportCandidate=awaiting:explicit_transfer_confirmation", result)
        }
        fun beginDisclosure() {
            val method = ReportCandidateController::class.java.getDeclaredMethod("beginExplicitReportConfirmationDisclosure", ExplicitReportConfirmationContext::class.java,
                ReportCandidateController.ExplicitReportRequestSource::class.java)
            method.isAccessible = true
            method.invoke(controller, context(), ReportCandidateController.ExplicitReportRequestSource.VOICE)
        }
        fun runMain() { while (main.isNotEmpty()) main.removeFirst().invoke() }
        override fun enqueue(expectedReporterActorId: String, metadataUtf8: ByteArray, imageJpeg: ByteArray, priority: ReportQueuePriority,
            walkSessionId: String, consentReceiptSha256: String, accountGeneration: Long?): QueuedReport? {
            events.add("queue"); queuedImage = imageJpeg.copyOf(); ownedQueuedImage = imageJpeg; ownedQueuedMetadata = metadataUtf8
            if (queueThrows) throw IllegalStateException("storage failed")
            if (!returnQueued) return null
            val payload = requireNotNull(FrozenReportPayload.freeze(WALK, metadataUtf8, imageJpeg))
            returnedPayload = payload
            return QueuedReport(payload, priority, expectedReporterActorId, walkSessionId, consentReceiptSha256, 1_000L, 2_000L, accountGeneration)
        }
        override fun reportPermissionsAllowWork() = permission
        override fun currentRuntimeEpochOrNull() = epoch
        override fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch) = this.epoch == epoch
        override fun currentReporterUserId() = actor
        override fun walkSafetyOutputsAllowed() = safety
        override fun requireReporterUserId(reason: String) = actor == ACTOR
        override fun approvedQueueOriginOrNull(origin: String) = origin.takeIf { it == ORIGIN }
        override fun isCurrentFrameGeneration(generation: Int, expectedWalkEpoch: WalkRuntimeEpoch?) = frameCurrent
        override fun runtimeMetricAllowsWork(expectedGeneration: Long?): Boolean { events.add("metric"); return metricCurrent }
        override fun runtimeMetricGeneration() = 1L
        override fun buildDeviceGateState(bestOutput: TrackedObjectDepth?, staleReason: String?, expectedRuntimeMetricGeneration: Long) = DeviceGateState()
        override fun buildFrozenReportCandidateInput(output: TrackedObjectDepth, trustedLocation: TrustedLocation, modelKey: String,
            sourceModel: String, threshold: Float, capturedAtMs: Long, requestElapsedRealtimeMs: Long, nowMs: Long,
            trigger: String, reporterId: String, gateState: DeviceGateState, reportImage: ByteArray?, frozenReportCapture: Pair<DetectionSnapshot, TactileSnapshotFrameResult>?): AndroidReportCandidateInput = error("capture not expected")
        override fun loadedReportModel(): String? = null
        override fun runtimeConfig(): TwoModelRuntimeConfig? = null
        override fun sourceModel(modelKey: String): String? = null
        override fun threshold(modelKey: String, className: String): Float? = null
        override fun current() = WalkSessionDeviceResourceSnapshot(true, true, true)
        override fun freshTrustedLocationOrNull(nowElapsedRealtimeMs: Long?): TrustedLocation? = null
        override fun ensurePermission() { events.add("permission") }
        override fun startServicesIfNeeded() { events.add("location") }
        override fun runOnUiThread(action: () -> Unit) { main.add(action) }
        override fun status(message: String) { events.add(message); onStatus?.invoke(message) }
        override fun speakInteraction(message: String) { events.add("speech:$message") }
        override fun renderDisclosure(armed: Boolean) { this.armed = armed }
        override fun renderIdle(message: String?) { armed = false; idleMessage = message }
        override fun deliverDisclosureAfterDraw(onDelivered: () -> Unit): Boolean { delivered = onDelivered; return true }
        override fun speakDisclosure(message: String, onCompleted: () -> Unit, onFailed: () -> Unit, onCancelled: () -> Unit): NavigationSpeechDispatchResult {
            delivered = onCompleted; failed = onFailed; cancelled = onCancelled; return NavigationSpeechDispatchResult.ACCEPTED
        }
        override fun postDelayed(action: () -> Unit, delayMs: Long) { timers.add(action to delayMs) }
    }
    private companion object {
        const val METRIC_UNAVAILABLE_NOTICE =
            "거리 측정이 준비되지 않아 신고를 저장하지 않았습니다. 기기 점검을 완료한 뒤 다시 신고해 주세요."
        const val ACTOR = "walker-1"
        const val ORIGIN = "https://gateway.example.test"
        const val WALK = "11111111-1111-4111-8111-111111111111"
        fun confirmation() = IntegratedConsentConfirmation(INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION, INTEGRATED_CONSENT_POLICY_VERSION,
            "installation_candidate_test", "candidate_report_request_0001", IntegratedConsentItemVersions(), 1L, 1L,
            IntegratedConsentSelections(), "2026-10-06T00:00:00.000Z", "d".repeat(64), "a".repeat(64), "e".repeat(64))
        fun jpeg() = intArrayOf(0xff,0xd8,0xff,0xc0,0,0x0b,8,0,1,0,1,1,1,0x11,0,0xff,0xda,0,8,1,1,0,0,0x3f,0,1,0xff,0xd9).map { it.toByte() }.toByteArray()
    }
}
