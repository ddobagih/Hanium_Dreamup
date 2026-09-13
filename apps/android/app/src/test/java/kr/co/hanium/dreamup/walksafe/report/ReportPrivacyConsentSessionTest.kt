package kr.co.hanium.dreamup.walksafe.report

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.inference.AdaptiveInferencePacingPolicy
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.device.WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO
import kr.co.hanium.dreamup.walksafe.device.WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.navigation.AndroidDetectionSnapshotFrameIdentity
import kr.co.hanium.dreamup.walksafe.navigation.TactileProjectionContext
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkBinding
import kr.co.hanium.dreamup.walksafe.network.LocalHttpTestServer
import kr.co.hanium.dreamup.walksafe.network.awaitPeerDisconnect
import kr.co.hanium.dreamup.walksafe.network.writeStalledChunkedHeaders
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItemVersions
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSelections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportPrivacyConsentSessionTest {
    @Test
    fun mainActivityPauseInvalidatesPrivateFrameStateAndInFlightGenerationWithoutReplacingDetector() {
        val activity = MainActivity()
        val consent = MainActivity::class.java.getDeclaredField("reportPrivacyConsentSession").run {
            isAccessible = true
            get(activity) as ReportPrivacyConsentSession
        }
        consent.resetForNewEnrollment()
        val detectorField = MainActivity::class.java.getDeclaredField("frameDetector").apply {
            isAccessible = true
        }
        val detectorBeforePause = detectorField.get(activity)
        val pipelineBeforePause = activityField("objectDepthPipeline").get(activity)
        val generationBeforePause = 9
        val reportOutput = reportDepth()
        val reportGate = DeviceGateState(
            cameraPermissionGranted = true,
            arCoreSupported = true,
            depthSupported = true,
            tfliteConfigLoaded = true,
            detectorAvailable = true,
            arSessionRunning = true,
            freshDepthObject = true,
        )
        val detectionSnapshot = populatedDetectionSnapshot()
        activityField("isActivityForeground").setBoolean(activity, true)
        activityField("detectorGeneration").setInt(activity, generationBeforePause)
        activityField("latestDetectionSnapshot").set(activity, detectionSnapshot)
        activityField("latestExplicitReportOutput").set(activity, reportOutput)
        activityField("latestExplicitReportImage").set(activity, byteArrayOf(1, 2, 3))
        activityField("latestExplicitReportGateState").set(activity, reportGate)
        activityField("latestExplicitReportCapturedAtMs").setLong(activity, CAPTURED_AT_MS)
        activityField("latestReportCandidateStatus").set(activity, "reportCandidate=prepared")
        val pacing = activityField("adaptiveInferencePacing").get(activity) as AdaptiveInferencePacingPolicy
        pacing.complete(requireNotNull(pacing.tryStart(500L)), 1_000L, inferenceDurationMs = 500L)
        assertEquals(1L, pacing.snapshot().completedSamples)
        activityField("lastOverlayUpdateMs").setLong(activity, 600L)
        activityField("lastUiUpdateMs").setLong(activity, 700L)
        activityField("latestTrustedLocation").set(
            activity,
            TrustedLocation(37.0, 127.0, 5f, 1_000L),
        )
        val pendingCapture = activityField("frameCaptureRequested").get(activity) as AtomicBoolean
        pendingCapture.set(true)
        val cancelObserved = AtomicBoolean(false)
        val call = CancellableNetworkCall<Unit>(
            executeBlock = { Unit },
            cancelBlock = { cancelObserved.set(true) },
        )
        consent.grantFromServerConfirmedIntegratedConsent()
        assertSame(call, consent.trackIfGranted(call))

        activity.pauseWalkSafeRuntime()

        assertTrue(consent.isGranted())
        assertTrue(call.isCancelled())
        assertTrue(cancelObserved.get())
        assertSame(detectorBeforePause, detectorField.get(activity))
        assertNotSame(pipelineBeforePause, activityField("objectDepthPipeline").get(activity))
        assertFalse(activityField("isActivityForeground").getBoolean(activity))
        assertEquals(generationBeforePause + 1, activityField("detectorGeneration").getInt(activity))
        assertEmptyDetectionSnapshot(activity, "latestDetectionSnapshot")
        assertNull(activityField("latestExplicitReportOutput").get(activity))
        assertNull(activityField("latestExplicitReportImage").get(activity))
        assertNull(activityField("latestExplicitReportGateState").get(activity))
        assertEquals(0L, activityField("latestExplicitReportCapturedAtMs").getLong(activity))
        assertEquals(
            "reportCandidate=blocked:runtime_paused",
            activityField("latestReportCandidateStatus").get(activity),
        )
        assertFalse(pendingCapture.get())
        assertEquals(0L, pacing.snapshot().completedSamples)
        assertEquals(0L, activityField("lastOverlayUpdateMs").getLong(activity))
        assertEquals(0L, activityField("lastUiUpdateMs").getLong(activity))
        assertNull(activityField("latestTrustedLocation").get(activity))

        consent.grantFromServerConfirmedIntegratedConsent()
        activityField("isActivityForeground").setBoolean(activity, true)
        activityField("latestTrustedLocation").set(
            activity,
            TrustedLocation(37.0, 127.0, 5f, 2_000L),
        )
        assertFalse(
            activity.publishCurrentFrameReportState(
                frameGeneration = generationBeforePause,
                expectedRuntimeMetricGeneration = 0L,
                automaticReportOutput = reportOutput,
                reportGateState = reportGate,
                explicitReportOutput = reportOutput,
                explicitReportGateState = reportGate,
                reportImage = byteArrayOf(9, 8, 7),
                nowMs = CAPTURED_AT_MS + 100L,
                capturedAtMs = CAPTURED_AT_MS,
            ),
        )
        assertNull(
            activity.publishDetectionSnapshot(
                result = AndroidDetectionResult.empty(),
                generation = generationBeforePause,
                detectionIdentity = AndroidDetectionSnapshotFrameIdentity(
                    captureFrameId = FRAME_ID,
                    frameTimestampMs = CAPTURED_AT_MS,
                ),
                capturedAtMs = CAPTURED_AT_MS,
                startedAtMs = CAPTURED_AT_MS,
                completedAtMs = CAPTURED_AT_MS + 1L,
                imageWidth = 640,
                imageHeight = 480,
                reportImageJpeg = byteArrayOf(9, 8, 7),
                frameEvidence = detectionEvidence(),
            ),
        )
        assertNull(activityField("latestExplicitReportOutput").get(activity))
        assertNull(activityField("latestExplicitReportImage").get(activity))
        assertEmptyDetectionSnapshot(activity, "latestDetectionSnapshot")
    }

    @Test
    fun defaultsOffAndNeverQueuesAReportBeforeExplicitOptIn() {
        val consent = freshConsent()
        val executed = AtomicBoolean(false)
        val call = CancellableNetworkCall(executeBlock = {
            executed.set(true)
            Unit
        })

        assertFalse(consent.isGranted())
        assertNull(consent.trackIfGranted(call))
        assertTrue(call.isCancelled())
        assertThrows(CancellationException::class.java) { call.execute() }
        assertFalse(executed.get())
    }

    @Test
    fun withdrawalCancelsBothQueuedAndInFlightReportCalls() {
        val consent = freshConsent()
        consent.grantFromServerConfirmedIntegratedConsent()

        val queuedExecuted = AtomicBoolean(false)
        val queued = CancellableNetworkCall(executeBlock = {
            queuedExecuted.set(true)
            Unit
        })
        assertTrue(consent.trackIfGranted(queued) === queued)

        val started = CountDownLatch(1)
        val released = CountDownLatch(1)
        val cancelObserved = AtomicBoolean(false)
        val inFlight = CancellableNetworkCall(
            executeBlock = {
                started.countDown()
                check(released.await(5, TimeUnit.SECONDS))
                if (cancelObserved.get()) throw CancellationException("cancelled by consent withdrawal")
            },
            cancelBlock = {
                cancelObserved.set(true)
                released.countDown()
            },
        )
        assertTrue(consent.trackIfGranted(inFlight) === inFlight)
        val executor = Executors.newSingleThreadExecutor()
        val failure = AtomicReference<Throwable?>()
        try {
            val result = executor.submit {
                try {
                    inFlight.execute()
                } catch (error: Throwable) {
                    failure.set(error)
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))

            consent.withdraw()

            assertFalse(consent.isGranted())
            assertTrue(queued.isCancelled())
            assertThrows(CancellationException::class.java) { queued.execute() }
            assertFalse(queuedExecuted.get())
            assertTrue(cancelObserved.get())
            result.get(5, TimeUnit.SECONDS)
            assertTrue(failure.get() is CancellationException)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aNewOrWithdrawnSessionRequiresAnotherExplicitOptIn() {
        val first = freshConsent()
        first.grantFromServerConfirmedIntegratedConsent()
        assertTrue(first.isGranted())
        first.withdraw()
        assertFalse(first.isGranted())
        assertFalse(ReportPrivacyConsentSession.isGranted())
    }

    @Test
    fun lifecycleCancellationStopsActiveCallsWithoutWithdrawingPersistedConsent() {
        val consent = freshConsent()
        consent.grantFromServerConfirmedIntegratedConsent()
        val call = CancellableNetworkCall<Unit>(executeBlock = { Unit })
        assertSame(
            call,
            consent.trackIfGranted(call, ReportTransferPurpose.EXPLICIT),
        )

        consent.cancelActiveCalls()

        assertTrue(call.isCancelled())
        assertTrue(consent.isGranted())
    }

    @Test
    fun disablingAutomaticReportsDoesNotCancelAnExplicitReport() {
        val consent = freshConsent()
        consent.grantFromServerConfirmedIntegratedConsent()
        val automatic = CancellableNetworkCall<Unit>(executeBlock = { Unit })
        val explicit = CancellableNetworkCall<Unit>(executeBlock = { Unit })
        consent.trackIfGranted(automatic, ReportTransferPurpose.AUTOMATIC)
        consent.trackIfGranted(explicit, ReportTransferPurpose.EXPLICIT)

        consent.cancelActiveCalls(ReportTransferPurpose.AUTOMATIC)

        assertTrue(automatic.isCancelled())
        assertFalse(explicit.isCancelled())
        assertTrue(consent.isGranted())
    }

    @Test
    fun withdrawalDisconnectsAnActualActiveReportUploadBeforeItsReadTimeout() {
        val stalledResponseStarted = CountDownLatch(1)
        LocalHttpTestServer { _, socket ->
            socket.writeStalledChunkedHeaders()
            stalledResponseStarted.countDown()
            socket.awaitPeerDisconnect(3_000)
        }.use { server ->
            val consent = freshConsent()
            consent.grantFromServerConfirmedIntegratedConsent()
            val call = consent.trackIfGranted(
                AndroidReportUploader().uploadCall(
                    permit =
                        requireNotNull(
                            consent.issueUploadPermit(
                                ReportTransferPurpose.EXPLICIT,
                            ),
                        ),
                    session = GatewayFieldSession.verified(
                        gatewayBaseUrl = server.baseUrl,
                        actorId = "privacy-test-actor",
                        cookiePair = "walksafe_field_session=v2.test.session.cookie",
                        expiresAtEpochMs = Long.MAX_VALUE,
                    ),
                    consentConfirmation = IntegratedConsentConfirmation(
                        schemaVersion =
                            INTEGRATED_CONSENT_CONFIRMATION_SCHEMA_VERSION,
                        policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
                        installationId = "privacy-test-installation",
                        requestId = "privacy-test-request",
                        itemVersions = IntegratedConsentItemVersions(),
                        clientRevision = 1L,
                        revision = 1L,
                        selections = IntegratedConsentSelections(),
                        confirmedAt = "2026-07-25T12:00:00.000Z",
                        gatewayAuditRecordSha256 = "9".repeat(64),
                        backendConsentReceiptSha256 = "a".repeat(64),
                        controlSecret = "c".repeat(64),
                    ),
                    networkBinding = IntegratedConsentNetworkBinding.forTest(
                        IntegratedConsentNetworkTransport.WIFI,
                    ) { url -> url.openConnection() },
                    transferPurpose = ReportTransferPurpose.EXPLICIT,
                    metadataJson =
                        """{"source":"android","auto_reported":false,"trace_id":"privacy-test-trace"}""",
                    imageJpeg = byteArrayOf(1, 2, 3),
                    stableTraceId = "privacy-test-trace",
                    expectedGatewayActorId = "privacy-test-actor",
                ),
            )!!
            val executor = Executors.newSingleThreadExecutor()
            try {
                val future = executor.submit<ReportUploadResponse> { call.execute() }
                assertTrue(stalledResponseStarted.await(2, TimeUnit.SECONDS))

                consent.withdraw()

                val failure = assertThrows(ExecutionException::class.java) {
                    future.get(2, TimeUnit.SECONDS)
                }
                assertTrue(failure.cause is CancellationException)
                assertFalse(consent.isGranted())
            } finally {
                consent.complete(call)
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun disclosureStatesTheActualTransferAndRetentionBoundary() {
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains(WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains(WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains("직접 손상 점자블록 신고"))
        assertFalse(REPORT_PRIVACY_DISCLOSURE_KO.contains("위험 신고"))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains("최대 960px"))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains("현재 Android 앱은 320px"))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains("JPEG"))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains("메타데이터를 제거"))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains("모자이크하지 않습니다"))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains("정확한 GPS 좌표"))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains("GPS가 제공한 이동 heading"))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains("release 빌드의 신고 대기열을 열지 않습니다"))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains("WalkSafe 테스트 서버에만 전송"))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains("기관으로 자동 전송하지 않습니다"))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains("영상·이미지·정확한 위치를 수집하지 않는 별도 경로"))
        assertTrue(REPORT_PRIVACY_DISCLOSURE_KO.contains("직접 신고는 사용할 수 있습니다"))
        assertTrue(REPORT_EXPLICIT_CONFIRMATION_DISCLOSURE_KO.contains("이번 손상 점자블록 직접 신고"))
        assertTrue(REPORT_EXPLICIT_CONFIRMATION_DISCLOSURE_KO.contains("선택 원본·진단수집 raw v2나 학습 재사용에는"))
        assertFalse(REPORT_PRIVACY_DISCLOSURE_KO.contains("180일"))
        assertFalse(REPORT_EXPLICIT_CONFIRMATION_DISCLOSURE_KO.contains("180일"))
    }

    @Test
    fun permitValidationAndOpenerAreLinearizedAgainstWithdrawal() {
        val consent = freshConsent()
        consent.grantFromServerConfirmedIntegratedConsent()
        val permit =
            requireNotNull(consent.issueUploadPermit(ReportTransferPurpose.EXPLICIT))
        val tracked = CancellableNetworkCall<Unit>(executeBlock = { Unit })
        assertSame(
            tracked,
            consent.trackIfLive(permit, tracked, ReportTransferPurpose.EXPLICIT),
        )
        val openerEntered = CountDownLatch(1)
        val releaseOpener = CountDownLatch(1)
        val withdrawStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val opened = executor.submit<String> {
                consent.withLiveUploadPermit(
                    permit,
                    ReportTransferPurpose.EXPLICIT,
                ) {
                    openerEntered.countDown()
                    check(releaseOpener.await(5, TimeUnit.SECONDS))
                    "opened"
                }
            }
            assertTrue(openerEntered.await(5, TimeUnit.SECONDS))
            val withdrawn = executor.submit<Boolean> {
                withdrawStarted.countDown()
                consent.withdraw()
            }
            assertTrue(withdrawStarted.await(5, TimeUnit.SECONDS))
            assertFalse(withdrawn.isDone)
            releaseOpener.countDown()
            assertEquals("opened", opened.get(5, TimeUnit.SECONDS))
            assertTrue(withdrawn.get(5, TimeUnit.SECONDS))
            assertTrue(tracked.isCancelled())
        } finally {
            releaseOpener.countDown()
            executor.shutdownNow()
        }
    }

    private fun freshConsent(): ReportPrivacyConsentSession {
        ReportPrivacyConsentSession.resetForNewEnrollment()
        return ReportPrivacyConsentSession
    }

    private fun activityField(name: String) = MainActivity::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun assertEmptyDetectionSnapshot(activity: MainActivity, fieldName: String) {
        val snapshot = activityField(fieldName).get(activity) as MainActivity.DetectionSnapshot
        assertTrue(snapshot.detections.isEmpty())
        assertNull(snapshot.capturedAtMs)
        assertNull(snapshot.reportImage)
        assertNull(snapshot.frameEvidence)
    }

    private fun populatedDetectionSnapshot(): MainActivity.DetectionSnapshot = MainActivity.DetectionSnapshot(
        detections = emptyList(),
        identity = AndroidDetectionSnapshotFrameIdentity(
            captureFrameId = FRAME_ID,
            frameTimestampMs = CAPTURED_AT_MS,
        ),
        capturedAtMs = CAPTURED_AT_MS,
        startedAtMs = CAPTURED_AT_MS,
        completedAtMs = CAPTURED_AT_MS + 1L,
        imageWidth = 640,
        imageHeight = 480,
        detectDurationMs = 1L,
        detectorTiming = null,
        reportImage = byteArrayOf(1, 2, 3),
        partial = false,
        frameEvidence = null,
    )

    private fun detectionEvidence(): MainActivity.DetectionFrameEvidence = MainActivity.DetectionFrameEvidence(
        frameId = FRAME_ID,
        frameTimestampMs = CAPTURED_AT_MS,
        depthSnapshot = DepthFrameSnapshot(
            frameTimestampNs = FRAME_ID,
            rawDepth = null,
            rawConfidence = null,
            fullDepth = null,
        ),
        depthMapper = null,
        tactileContext = TactileProjectionContext(
            captureFrameId = FRAME_ID,
            expectedRouteId = null,
            routeProjection = null,
            trustedLocation = null,
            orientation = null,
            magneticDeclinationDeg = null,
            cameraFrame = null,
            detectionAgeMs = 100L,
            nowElapsedRealtimeMs = 1_000L,
        ),
        motionContext = MotionContext(),
        navigationActive = false,
        tmapOnRoute = false,
    )

    private fun reportDepth(): TrackedObjectDepth = TrackedObjectDepth(
        frameId = FRAME_ID,
        timestampMs = CAPTURED_AT_MS,
        trackId = "damage-1",
        className = AndroidReportCandidatePolicy.DAMAGED_TACTILE_BLOCK,
        detectionConfidence = 0.9f,
        bboxNorm = RectNorm(0.2f, 0.3f, 0.4f, 0.2f),
        polygonNorm = emptyList(),
        maskAreaNorm = 0.08f,
        centerNorm = Point2(0.4f, 0.4f),
        bottomContactNorm = null,
        source = DepthSource.ARCORE_RAW_DEPTH,
        zDistanceM = 1.1f,
        rayDistanceM = null,
        groundDistanceM = null,
        riskDistanceM = 1.0f,
        validSampleCount = 40,
        validSampleRatio = 0.8f,
        depthMedianM = 1.1f,
        depthP20M = 1.0f,
        depthIqrM = 0.1f,
        trend = Trend.STABLE,
        approachScore = 0f,
        approachSpeedMps = null,
        timeToCollisionMs = null,
        confidence = DepthConfidenceBreakdown(
            sourceQuality = 1f,
            sampleQuality = 1f,
            depthQuality = 1f,
            detectionQuality = 1f,
            trackingQuality = 1f,
            motionQuality = 1f,
            freshnessQuality = 1f,
            corridorQuality = 1f,
        ),
        userFacing = UserFacingDepth(null, MessageLevel.NONE, null),
        trackAgeFrames = 3,
        trackStableMs = 700L,
    )

    private companion object {
        const val FRAME_ID = 77L
        const val CAPTURED_AT_MS = 1_782_907_200_000L
    }
}
