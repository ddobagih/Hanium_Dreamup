package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.FrozenImageToTextureCoordinateMapper
import kr.co.hanium.dreamup.walksafe.depth.DepthConfidenceBreakdown
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class AndroidTactileRouteGuidanceTest {
    @Test
    fun surfaceCreationBindsTheExternalCameraTextureBeforeDrawing() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val surfaceCreated = source
            .substringAfter("override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?)")
            .substringBefore("override fun onSurfaceChanged")

        assertTrue(surfaceCreated.indexOf("cameraTextureBound = false") >= 0)
        assertTrue(
            surfaceCreated.indexOf("cameraTextureBound = false") <
                surfaceCreated.indexOf("cameraTextureId = createExternalCameraTexture()"),
        )
        assertTrue(
            surfaceCreated.indexOf("cameraTextureId = createExternalCameraTexture()") <
                surfaceCreated.indexOf("bindCameraTextureIfReady()"),
        )
    }

    @Test
    fun compiledOnDrawFrameDispatchesItsPreparedTactileFrame() {
        val javaHome = File(requireNotNull(System.getProperty("java.home")))
        val javap = File(javaHome, "bin/javap")
        val protectionDomain = requireNotNull(MainActivity::class.java.protectionDomain)
        val mainClasses = File(requireNotNull(protectionDomain.codeSource).location.toURI())
        val process = ProcessBuilder(
            javap.absolutePath,
            "-classpath",
            mainClasses.absolutePath,
            "-p",
            "-c",
            MainActivity::class.java.name,
        ).redirectErrorStream(true).start()
        val disassembly = process.inputStream.bufferedReader().use { it.readText() }

        assertEquals(disassembly, 0, process.waitFor())
        val onDrawSignature = "public void onDrawFrame(javax.microedition.khronos.opengles.GL10);"
        val nextMethodSignature = "private final android.widget.FrameLayout buildContentView"
        assertTrue(disassembly.contains(onDrawSignature))
        assertTrue(disassembly.contains(nextMethodSignature))
        val onDrawFrame = disassembly
            .substringAfter(onDrawSignature)
            .substringBefore(nextMethodSignature)
        assertTrue(onDrawFrame.contains("Method prepareTactileFrameDispatch"))
        assertTrue(
            onDrawFrame.contains("PreparedTactileFrameDispatch.dispatchFeedback"),
        )
    }

    @Test
    fun mainActivityPublishMethodPreservesDetectionIdentityAndFrameEvidence() {
        val activity = MainActivity()
        val detectionIdentity = AndroidDetectionSnapshotFrameIdentity(
            captureFrameId = FRAME_ID,
            frameTimestampMs = NOW_MS,
        )
        val evidence = detectionFrameEvidence(frameId = FRAME_ID + 1L, includeDepthMapper = false)

        val snapshot = activity.publishDetectionSnapshot(
            result = AndroidDetectionResult.empty(),
            generation = 0,
            detectionIdentity = detectionIdentity,
            capturedAtMs = NOW_MS,
            startedAtMs = NOW_MS + 1L,
            completedAtMs = NOW_MS + 2L,
            imageWidth = 640,
            imageHeight = 480,
            reportImageJpeg = byteArrayOf(1, 2, 3),
            frameEvidence = evidence,
        )

        val published = requireNotNull(snapshot)
        assertSame(detectionIdentity, published.identity)
        assertSame(evidence, published.frameEvidence)
        assertEquals(FRAME_ID, published.captureFrameId)
        assertEquals(FRAME_ID + 1L, published.frameEvidence?.frameId)
    }

    @Test
    fun mainActivitySnapshotProcessRejectsSelfAttestationAndMatcherFallback() {
        val activity = MainActivity()
        val evidenceFrameId = FRAME_ID + 1L
        val snapshot = publishSnapshot(
            activity = activity,
            detectionFrameId = FRAME_ID,
            evidence = detectionFrameEvidence(frameId = evidenceFrameId),
        )
        var depthCalls = 0
        configureLiveRouteState(activity, navigationActive = true, tmapOnRoute = true)

        val result = activity.processTactileSnapshotFrame(
            detectionSnapshot = snapshot,
            nowMs = NOW_MS,
            currentFrameTimestampMs = NOW_MS,
            maxDetectionSourceAgeMs = MAX_DETECTION_AGE_MS,
            maxDetectionFrameDeltaMs = MAX_DETECTION_AGE_MS,
            detectionAgeMs = 100L,
        ) { _, _ ->
            depthCalls += 1
            listOf(tactileOutput().copy(frameId = evidenceFrameId))
        }

        assertNull(result.matchedEvidence)
        assertEquals(false, result.depthProcessingAttempted)
        assertEquals(0, depthCalls)
        assertEquals(LocalRouteMode.TMAP, result.guidance.decision.mode)
        assertEquals(emptyList<TrackedObjectDepth>(), result.guidance.outputs)
    }

    @Test
    fun mainActivitySnapshotProcessAppliesCapturedAndLiveRouteGates() {
        val activity = MainActivity()
        val activeSnapshot = publishSnapshot(
            activity = activity,
            detectionFrameId = FRAME_ID,
            evidence = detectionFrameEvidence(frameId = FRAME_ID),
        )
        val inactiveAtCapture = publishSnapshot(
            activity = activity,
            detectionFrameId = FRAME_ID,
            evidence = detectionFrameEvidence(frameId = FRAME_ID, navigationActive = false),
        )
        val offRouteAtCapture = publishSnapshot(
            activity = activity,
            detectionFrameId = FRAME_ID,
            evidence = detectionFrameEvidence(frameId = FRAME_ID, tmapOnRoute = false),
        )
        fun process(
            snapshot: MainActivity.DetectionSnapshot,
            navigationActive: Boolean = true,
            tmapOnRoute: Boolean = true,
        ): MainActivity.TactileSnapshotFrameResult {
            configureLiveRouteState(activity, navigationActive, tmapOnRoute)
            return activity.processTactileSnapshotFrame(
                detectionSnapshot = snapshot,
                nowMs = NOW_MS,
                currentFrameTimestampMs = NOW_MS,
                maxDetectionSourceAgeMs = MAX_DETECTION_AGE_MS,
                maxDetectionFrameDeltaMs = MAX_DETECTION_AGE_MS,
                detectionAgeMs = 100L,
            ) { _, _ -> listOf(tactileOutput()) }
        }

        val active = process(activeSnapshot)
        assertSame(activeSnapshot.frameEvidence, active.matchedEvidence)
        assertEquals(true, active.depthProcessingAttempted)
        assertEquals(LocalRouteMode.TACTILE_LOCAL, active.guidance.decision.mode)

        val gated = listOf(
            process(inactiveAtCapture),
            process(offRouteAtCapture),
            process(activeSnapshot, navigationActive = false),
            process(activeSnapshot, tmapOnRoute = false),
        )
        gated.forEach { result ->
            assertEquals(LocalRouteMode.TMAP, result.guidance.decision.mode)
            assertEquals(MessageLevel.NONE, result.guidance.outputs.single().userFacing.messageLevel)
        }
    }

    @Test
    fun productionFrameCoordinatorDeliversLocalSteeringToNavigationSpeechActuator() {
        val activity = MainActivity()
        configureLiveRouteState(activity, navigationActive = true, tmapOnRoute = true)
        val snapshot = publishSnapshot(
            activity = activity,
            detectionFrameId = FRAME_ID,
            evidence = detectionFrameEvidence(frameId = FRAME_ID),
        )
        var depthDetections = emptyList<DetectionCandidate>()
        val actuator = RecordingTactileFrameFeedbackActuator()
        activity.tactileFrameFeedbackDelivery = actuator::emit
        val preparedFrame = activity.prepareTactileFrameDispatch(
            detectionSnapshot = snapshot,
            nowMs = NOW_MS,
            currentFrameTimestampMs = NOW_MS,
            maxDetectionSourceAgeMs = MAX_DETECTION_AGE_MS,
            maxDetectionFrameDeltaMs = MAX_DETECTION_AGE_MS,
            detectionAgeMs = snapshot.sourceAgeMs(NOW_MS),
        ) { _, detections ->
            depthDetections = detections
            listOf(tactileOutput())
        }
        val frame = preparedFrame.frame

        val dispatch = preparedFrame.dispatchFeedback(
            stale = false,
            deviceGateAllowsAlerts = true,
            nowMs = NOW_MS,
        )

        assertEquals(listOf("normal_tactile_block"), depthDetections.map(DetectionCandidate::className))
        assertEquals(LocalRouteMode.TACTILE_LOCAL, frame.guidance.decision.mode)
        assertEquals(MessageLevel.INFO, frame.guidance.outputs.single().userFacing.messageLevel)
        assertEquals(MessageLevel.INFO, dispatch.action?.level)
        assertEquals("fresh_metric_path_guidance", dispatch.action?.reason)
        assertEquals(1, actuator.dispatchCount)
        assertSame(dispatch, actuator.dispatches.single())
        assertEquals(listOf("전방 점자블록을 따라 이동하세요."), actuator.navigationSpeech)
    }

    @Test
    fun productionCoordinatorUsesItsConfiguredActuatorWithoutATestOverride() {
        val activity = MainActivity()
        configureLiveRouteState(activity, navigationActive = true, tmapOnRoute = true)
        val snapshot = publishSnapshot(
            activity = activity,
            detectionFrameId = FRAME_ID,
            evidence = detectionFrameEvidence(frameId = FRAME_ID),
        )
        val frame = activity.processTactileSnapshotFrame(
            detectionSnapshot = snapshot,
            nowMs = NOW_MS,
            currentFrameTimestampMs = NOW_MS,
            maxDetectionSourceAgeMs = MAX_DETECTION_AGE_MS,
            maxDetectionFrameDeltaMs = MAX_DETECTION_AGE_MS,
            detectionAgeMs = snapshot.sourceAgeMs(NOW_MS),
        ) { _, _ -> listOf(tactileOutput()) }
        val actuator = RecordingTactileFrameFeedbackActuator()
        val coordinator = createProductionAndroidTactileFrameCoordinator(
            feedbackPolicy = WalkSafeFeedbackPolicy(),
            feedbackActuator = actuator,
        )

        val dispatch = coordinator.dispatchFeedback(
            frame = frame,
            stale = false,
            deviceGateAllowsAlerts = true,
            nowMs = NOW_MS,
        )

        assertEquals(LocalRouteMode.TACTILE_LOCAL, frame.guidance.decision.mode)
        assertEquals(MessageLevel.INFO, dispatch.action?.level)
        assertEquals(1, actuator.dispatchCount)
        assertSame(dispatch, actuator.dispatches.single())
        assertEquals(listOf("전방 점자블록을 따라 이동하세요."), actuator.navigationSpeech)
    }

    @Test
    fun emptyStaleDamagedOrMissingDetectionFallsBackWithoutReplacingOrdinaryTmapRoute() {
        listOf("empty", "stale", "damaged", "missing").forEach { caseName ->
            val activity = MainActivity()
            configureLiveRouteState(activity, navigationActive = true, tmapOnRoute = true)
            val validSnapshot = publishSnapshot(
                activity = activity,
                detectionFrameId = FRAME_ID,
                evidence = detectionFrameEvidence(frameId = FRAME_ID),
            )
            val snapshot = when (caseName) {
                "empty" -> validSnapshot.copy(detections = emptyList())
                "stale" -> validSnapshot.copy(capturedAtMs = NOW_MS - STALE_TACTILE_AGE_MS)
                "damaged" -> validSnapshot.copy(
                    detections = listOf(
                        tactileDetectionCandidate(),
                        tactileDetectionCandidate(className = "damaged_tactile_block"),
                    ),
                )
                "missing" -> validSnapshot.copy(frameEvidence = null)
                else -> error("unknown case")
            }
            val depthOutputs = if (caseName == "damaged") {
                listOf(
                    tactileOutput(),
                    tactileOutput().copy(trackId = "damage-1", className = "damaged_tactile_block"),
                )
            } else {
                listOf(tactileOutput())
            }
            var depthCalls = 0
            val frame = activity.processTactileSnapshotFrame(
                detectionSnapshot = snapshot,
                nowMs = NOW_MS,
                currentFrameTimestampMs = NOW_MS,
                maxDetectionSourceAgeMs = MAX_DETECTION_AGE_MS,
                maxDetectionFrameDeltaMs = MAX_DETECTION_AGE_MS,
                detectionAgeMs = snapshot.sourceAgeMs(NOW_MS),
            ) { _, _ ->
                depthCalls += 1
                depthOutputs
            }
            val actuator = RecordingTactileFrameFeedbackActuator()
            val dispatch = activity.dispatchTactileFrameFeedback(
                frame = frame,
                stale = snapshot.staleReason(
                    nowMs = NOW_MS,
                    currentFrameTimestampMs = NOW_MS,
                    maxSourceAgeMs = MAX_DETECTION_AGE_MS,
                    maxFrameDeltaMs = MAX_DETECTION_AGE_MS,
                ) != null,
                deviceGateAllowsAlerts = true,
                nowMs = NOW_MS,
                feedbackActuatorOverride = actuator,
            )

            assertEquals(caseName, LocalRouteMode.TMAP, frame.guidance.decision.mode)
            assertFalse(
                caseName,
                frame.guidance.outputs.any { it.userFacing.messageLevel == MessageLevel.INFO },
            )
            assertNull(caseName, dispatch.action)
            assertEquals(caseName, 1, actuator.dispatchCount)
            assertTrue(caseName, actuator.navigationSpeech.isEmpty())
            assertEquals(caseName, if (caseName in setOf("stale", "damaged")) 1 else 0, depthCalls)

            val navigator = routeNavigator(activity)
            assertTrue(caseName, navigator.hasRoute())
            val tmapUpdate = navigator.update(
                location = TrustedLocation(37.0, 127.0, 2f, NOW_MS),
                nowMs = NOW_MS,
                requestInFlight = false,
            )
            assertNotNull(caseName, tmapUpdate.instruction)
        }
    }

    @Test
    fun mainActivityProductionEntryReachesLocalSteeringFromOneCaptureFrame() {
        val context = productionContext()

        val result = MainActivity().evaluateTactileFrame(
            productionFrameInput(context = context),
        )

        assertEquals(LocalRouteMode.TACTILE_LOCAL, result.decision.mode)
        assertEquals(TactileSteering.STRAIGHT, result.decision.steering)
        assertEquals("route-1", result.observation?.routeId)
        assertEquals(MessageLevel.INFO, result.outputs.single().userFacing.messageLevel)
    }

    @Test
    fun mainActivityProductionEntryRejectsEveryCrossFrameMutation() {
        val context = productionContext()
        val base = productionFrameInput(context = context)
        val cases = listOf(
            base.copy(identity = base.identity.copy(detectionCaptureFrameId = FRAME_ID + 1L)),
            base.copy(identity = base.identity.copy(evidenceFrameId = FRAME_ID + 1L)),
            base.copy(identity = base.identity.copy(evidenceFrameTimestampMs = NOW_MS + 1L)),
            base.copy(identity = base.identity.copy(depthFrameId = FRAME_ID + 1L)),
            base.copy(identity = base.identity.copy(depthMapperFrameId = null)),
            base.copy(identity = base.identity.copy(depthMapperFrameId = FRAME_ID + 1L)),
            base.copy(context = context.copy(captureFrameId = FRAME_ID + 1L)),
            base.copy(
                context = context.copy(
                    cameraFrame = requireNotNull(context.cameraFrame).copy(frameId = FRAME_ID + 1L),
                ),
            ),
            base.copy(outputs = listOf(tactileOutput().copy(frameId = FRAME_ID + 1L))),
        )
        val activity = MainActivity()

        cases.forEach { invalidInput ->
            val result = activity.evaluateTactileFrame(invalidInput)

            assertEquals(LocalRouteMode.TMAP, result.decision.mode)
            assertNull(result.observation)
            assertEquals(MessageLevel.NONE, result.outputs.single().userFacing.messageLevel)
        }
    }

    @Test
    fun mainActivityProductionEntryRequiresCapturedAndLiveNavigationAndTmapState() {
        val base = productionFrameInput(context = productionContext())
        val cases = listOf(
            base.copy(navigationActiveAtCapture = false),
            base.copy(navigationActiveNow = false),
            base.copy(tmapOnRouteAtCapture = false),
            base.copy(tmapOnRouteNow = false),
        )
        val activity = MainActivity()

        cases.forEach { inactiveInput ->
            val result = activity.evaluateTactileFrame(inactiveInput)

            assertEquals(LocalRouteMode.TMAP, result.decision.mode)
            assertEquals(MessageLevel.NONE, result.outputs.single().userFacing.messageLevel)
        }
    }

    @Test
    fun noDestinationPreservesGeneralHazardButSuppressesTactileRouteGuidance() {
        val generalHazard = tactileOutput().copy(
            trackId = "person-1",
            className = "person",
            userFacing = UserFacingDepth(
                stepsAhead = 2,
                messageLevel = MessageLevel.WARNING,
                message = "앞에 사람 후보가 있습니다.",
            ),
        )
        val result = AndroidTactileRouteGuidance().apply(
            outputs = listOf(generalHazard, tactileOutput()),
            context = null,
            navigationActive = false,
            tmapOnRoute = false,
            activeRouteId = null,
        )

        assertEquals(generalHazard, result.outputs[0])
        assertEquals(MessageLevel.NONE, result.outputs[1].userFacing.messageLevel)
        assertNull(result.outputs[1].userFacing.message)
    }

    @Test
    fun productionAssemblyReachesRealLocalSteeringWithCurrentTmapSegment() {
        val context = productionContext()
        val output = tactileOutput()

        val result = AndroidTactileRouteGuidance().apply(
            outputs = listOf(output),
            context = context,
            navigationActive = true,
            tmapOnRoute = true,
        )

        assertEquals(LocalRouteMode.TACTILE_LOCAL, result.decision.mode)
        assertEquals(TactileSteering.STRAIGHT, result.decision.steering)
        assertEquals("route-1", result.observation?.routeId)
        assertEquals(0, result.observation?.routeSegmentIndex)
        assertEquals("normal-1", result.observation?.candidateId)
        assertEquals(MessageLevel.INFO, result.outputs.single().userFacing.messageLevel)
        assertNotNull(result.outputs.single().userFacing.message)
    }

    @Test
    fun removingTheProductionSupplierFailsClosedToTmap() {
        val result = AndroidTactileRouteGuidance(observationSupplier = null).apply(
            outputs = listOf(tactileOutput()),
            context = productionContext(),
            navigationActive = true,
            tmapOnRoute = true,
        )

        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertEquals("tactile_not_visible", result.decision.reason)
        assertNull(result.observation)
        assertEquals(MessageLevel.NONE, result.outputs.single().userFacing.messageLevel)
        assertNull(result.outputs.single().userFacing.message)
    }

    @Test
    fun missingOrMismatchedCaptureFrameEvidenceFallsBackToTmap() {
        val context = productionContext()
        val cases = listOf(
            null,
            context.copy(captureFrameId = FRAME_ID + 1L),
            context.copy(
                cameraFrame = requireNotNull(context.cameraFrame).copy(frameId = FRAME_ID + 1L),
            ),
        )

        cases.forEach { invalidContext ->
            val result = AndroidTactileRouteGuidance().apply(
                outputs = listOf(tactileOutput()),
                context = invalidContext,
                navigationActive = true,
                tmapOnRoute = true,
                activeRouteId = context.expectedRouteId,
            )

            assertEquals(LocalRouteMode.TMAP, result.decision.mode)
            assertNull(result.observation)
            assertEquals(MessageLevel.NONE, result.outputs.single().userFacing.messageLevel)
        }
    }

    @Test
    fun frozenImageToDepthTransformMapsOnlyValidatedCaptureGeometry() {
        val transform = FrozenImageToDepthTransform.create(
            frameId = FRAME_ID,
            mappedCorners = listOf(
                Point2(1f, 0f),
                Point2(1f, 1f),
                Point2(0f, 0f),
                Point2(0f, 1f),
            ),
            mappedCenter = Point2(0.5f, 0.5f),
        )

        assertNotNull(transform)
        val mapped = requireNotNull(requireNotNull(transform).map(Point2(0.2f, 0.7f)))
        assertEquals(0.3f, mapped.x, 0.0001f)
        assertEquals(0.2f, mapped.y, 0.0001f)
        assertNull(
            FrozenImageToDepthTransform.create(
                frameId = FRAME_ID,
                mappedCorners = listOf(
                    Point2(1f, 0f),
                    Point2(1f, 1f),
                    Point2(0f, 0f),
                    Point2(0f, 1f),
                ),
                mappedCenter = Point2(0.6f, 0.5f),
            ),
        )
    }

    @Test
    fun staleHeadingRouteMismatchAndUntrustedDepthEachFallBack() {
        val context = productionContext()
        val normal = tactileOutput()
        val cases = listOf(
            context.copy(
                orientation = requireNotNull(context.orientation).copy(
                    observedAtElapsedRealtimeMs = context.nowElapsedRealtimeMs - 151L,
                ),
            ) to normal,
            context.copy(expectedRouteId = "route-2") to normal,
            context to normal.copy(source = DepthSource.POLYGON_TREND_PSEUDO_DEPTH),
            context.copy(cameraFrame = null) to normal,
            context.copy(
                cameraFrame = requireNotNull(context.cameraFrame).copy(
                    intrinsics = requireNotNull(context.cameraFrame).intrinsics.copy(focalLengthXPx = 0f),
                ),
            ) to normal,
            context.copy(
                routeProjection = requireNotNull(context.routeProjection).copy(
                    segmentStart = RoutePoint(37.0, 127.001),
                    segmentEnd = RoutePoint(37.001, 127.001),
                ),
            ) to normal,
        )

        cases.forEach { (invalidContext, invalidOutput) ->
            val result = AndroidTactileRouteGuidance().apply(
                outputs = listOf(invalidOutput),
                context = invalidContext,
                navigationActive = true,
                tmapOnRoute = true,
            )
            assertEquals(LocalRouteMode.TMAP, result.decision.mode)
            assertNull(result.observation)
            assertEquals(MessageLevel.NONE, result.outputs.single().userFacing.messageLevel)
        }
    }

    @Test
    fun credibleDamageImmediatelyBlocksNormalLocalSteering() {
        val normal = tactileOutput()
        val damaged = tactileOutput().copy(trackId = "damage-1", className = "damaged_tactile_block")

        val result = AndroidTactileRouteGuidance().apply(
            outputs = listOf(normal, damaged),
            context = productionContext(),
            navigationActive = true,
            tmapOnRoute = true,
        )

        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertEquals("tactile_not_traversable", result.decision.reason)
        assertEquals("damage-1", result.observation?.candidateId)
        assertEquals(MessageLevel.NONE, result.outputs.first().userFacing.messageLevel)
    }

    @Test
    fun cameraHeadingThatDoesNotMatchTheNearestSegmentFallsBack() {
        val context = productionContext().copy(
            // Sensor -Z (physical camera forward) points East instead of North.
            orientation = DeviceEarthOrientation(
                deviceToMagneticEnu = RotationMatrix3(
                    0f, 0f, -1f,
                    -1f, 0f, 0f,
                    0f, 1f, 0f,
                ),
                observedAtElapsedRealtimeMs = NOW_MS,
                headingErrorDeg = 5f,
                accuracy = EarthOrientationAccuracy.HIGH,
            ),
        )

        val result = AndroidTactileRouteGuidance().apply(
            outputs = listOf(tactileOutput()),
            context = context,
            navigationActive = true,
            tmapOnRoute = true,
        )

        assertEquals(LocalRouteMode.TMAP, result.decision.mode)
        assertEquals("route_heading_mismatch", result.decision.reason)
    }

    @Test
    fun routeWithoutProviderIdUsesStableGeometryFingerprint() {
        fun route(lastLatitude: Double): WalkingRoute = WalkingRoute(
            priority = "STAIR_AVOID",
            summary = WalkingRouteSummary(distanceM = 120, durationS = 100),
            polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(lastLatitude, 127.0)),
            guidePoints = emptyList(),
        )
        val first = RouteNavigator().apply { setRoute(route(37.001)) }.currentRouteId()
        val same = RouteNavigator().apply { setRoute(route(37.001)) }.currentRouteId()
        val changed = RouteNavigator().apply { setRoute(route(37.002)) }.currentRouteId()

        assertNotNull(first)
        assertEquals(true, first!!.startsWith("tmap-local:"))
        assertEquals(first, same)
        assertEquals(false, first == changed)

        val context = productionContext(providerRouteId = null)
        val result = AndroidTactileRouteGuidance().apply(
            outputs = listOf(tactileOutput()),
            context = context,
            navigationActive = true,
            tmapOnRoute = true,
        )
        assertEquals(LocalRouteMode.TACTILE_LOCAL, result.decision.mode)
        assertEquals(true, result.observation?.routeId?.startsWith("tmap-local:") == true)
    }

    private fun publishSnapshot(
        activity: MainActivity,
        detectionFrameId: Long,
        evidence: MainActivity.DetectionFrameEvidence,
        detections: List<DetectionCandidate> = listOf(tactileDetectionCandidate()),
    ): MainActivity.DetectionSnapshot = requireNotNull(
        activity.publishDetectionSnapshot(
            result = AndroidDetectionResult(detections = detections),
            generation = 0,
            detectionIdentity = AndroidDetectionSnapshotFrameIdentity(
                captureFrameId = detectionFrameId,
                frameTimestampMs = NOW_MS,
            ),
            capturedAtMs = NOW_MS,
            startedAtMs = NOW_MS + 1L,
            completedAtMs = NOW_MS + 2L,
            imageWidth = 640,
            imageHeight = 480,
            reportImageJpeg = null,
            frameEvidence = evidence,
        ),
    )

    private fun tactileDetectionCandidate(
        className: String = "normal_tactile_block",
    ): DetectionCandidate = DetectionCandidate(
        className = className,
        detectionConfidence = 0.9f,
        bboxNorm = RectNorm(0.4f, 0.5f, 0.2f, 0.3f),
        polygonNorm = listOf(
            Point2(0.4f, 0.5f),
            Point2(0.6f, 0.5f),
            Point2(0.6f, 0.8f),
            Point2(0.4f, 0.8f),
        ),
        maskAreaNorm = 0.06f,
    )

    private fun detectionFrameEvidence(
        frameId: Long,
        navigationActive: Boolean = true,
        tmapOnRoute: Boolean = true,
        includeDepthMapper: Boolean = true,
    ): MainActivity.DetectionFrameEvidence {
        val depthTransform = requireNotNull(
            FrozenImageToDepthTransform.create(
                frameId = frameId,
                mappedCorners = listOf(
                    Point2(0f, 0f),
                    Point2(1f, 0f),
                    Point2(0f, 1f),
                    Point2(1f, 1f),
                ),
                mappedCenter = Point2(0.5f, 0.5f),
            ),
        )
        return MainActivity.DetectionFrameEvidence(
            frameId = frameId,
            frameTimestampMs = NOW_MS,
            depthSnapshot = DepthFrameSnapshot(
                frameTimestampNs = frameId,
                rawDepth = null,
                rawConfidence = null,
                fullDepth = null,
            ),
            depthMapper = if (includeDepthMapper) {
                FrozenImageToTextureCoordinateMapper(
                    frameId = frameId,
                    transform = depthTransform,
                    depthSize = ImageSize(width = 2, height = 2),
                )
            } else {
                null
            },
            tactileContext = productionContextForFrame(frameId),
            motionContext = MotionContext(),
            navigationActive = navigationActive,
            tmapOnRoute = tmapOnRoute,
        )
    }

    private fun configureLiveRouteState(
        activity: MainActivity,
        navigationActive: Boolean,
        tmapOnRoute: Boolean,
    ) {
        MainActivity::class.java.getDeclaredField("isRouteActive").apply {
            isAccessible = true
            setBoolean(activity, navigationActive)
        }
        MainActivity::class.java.getDeclaredField("latestTmapOnRoute").apply {
            isAccessible = true
            setBoolean(activity, tmapOnRoute)
        }
        MainActivity::class.java.getDeclaredField("routeRequestInFlight").apply {
            isAccessible = true
            (get(activity) as AtomicBoolean).set(false)
        }
        MainActivity::class.java.getDeclaredField("routeNavigator").apply {
            isAccessible = true
            (get(activity) as RouteNavigator).setRoute(productionRoute())
        }
    }

    private fun routeNavigator(activity: MainActivity): RouteNavigator {
        return MainActivity::class.java.getDeclaredField("routeNavigator").run {
            isAccessible = true
            get(activity) as RouteNavigator
        }
    }

    private fun productionContext(providerRouteId: String? = "route-1"): TactileProjectionContext {
        val location = TrustedLocation(
            latitude = 37.0,
            longitude = 127.0,
            accuracyM = 2f,
            elapsedRealtimeMs = NOW_MS - 50L,
        )
        val navigator = RouteNavigator()
        navigator.setRoute(productionRoute(providerRouteId))
        navigator.update(location, nowMs = NOW_MS, requestInFlight = false)
        return TactileProjectionContext(
            captureFrameId = FRAME_ID,
            expectedRouteId = navigator.currentRouteId(),
            routeProjection = navigator.currentProjection(location),
            trustedLocation = location,
            orientation = DeviceEarthOrientation(
                // Device/camera +X -> East, +Y -> Up, -Z -> North.
                deviceToMagneticEnu = RotationMatrix3(
                    1f, 0f, 0f,
                    0f, 0f, -1f,
                    0f, 1f, 0f,
                ),
                observedAtElapsedRealtimeMs = NOW_MS,
                headingErrorDeg = 5f,
                accuracy = EarthOrientationAccuracy.HIGH,
            ),
            magneticDeclinationDeg = 0f,
            cameraFrame = TactileCameraFrame(
                frameId = FRAME_ID,
                cameraToAndroidSensor = RotationMatrix3(
                    1f, 0f, 0f,
                    0f, 1f, 0f,
                    0f, 0f, 1f,
                ),
                intrinsics = TactileCameraIntrinsics(
                    widthPx = 1_000,
                    heightPx = 1_000,
                    focalLengthXPx = 500f,
                    focalLengthYPx = 500f,
                    principalPointXPx = 500f,
                    principalPointYPx = 500f,
                ),
                observedAtElapsedRealtimeMs = NOW_MS,
                tracking = true,
            ),
            detectionAgeMs = 100L,
            nowElapsedRealtimeMs = NOW_MS,
        )
    }

    private fun productionRoute(providerRouteId: String? = "route-1"): WalkingRoute = WalkingRoute(
        priority = "STAIR_AVOID",
        summary = WalkingRouteSummary(distanceM = 120, durationS = 100),
        polyline = listOf(
            RoutePoint(36.9999, 127.0),
            RoutePoint(37.001, 127.0),
        ),
        guidePoints = emptyList(),
        providerRouteId = providerRouteId,
    )

    private fun productionContextForFrame(frameId: Long): TactileProjectionContext {
        val context = productionContext()
        return context.copy(
            captureFrameId = frameId,
            cameraFrame = requireNotNull(context.cameraFrame).copy(frameId = frameId),
        )
    }

    private fun tactileOutput(): TrackedObjectDepth {
        return TrackedObjectDepth(
            frameId = 1L,
            timestampMs = NOW_MS,
            trackId = "normal-1",
            className = "normal_tactile_block",
            detectionConfidence = 0.9f,
            bboxNorm = RectNorm(0.4f, 0.5f, 0.2f, 0.3f),
            polygonNorm = listOf(
                Point2(0.4f, 0.5f),
                Point2(0.6f, 0.5f),
                Point2(0.6f, 0.8f),
                Point2(0.4f, 0.8f),
            ),
            maskAreaNorm = 0.06f,
            centerNorm = Point2(0.5f, 0.65f),
            bottomContactNorm = Point2(0.5f, 0.7f),
            source = DepthSource.ARCORE_RAW_DEPTH,
            zDistanceM = 2f,
            rayDistanceM = null,
            groundDistanceM = null,
            riskDistanceM = 2f,
            validSampleCount = 80,
            validSampleRatio = 0.6f,
            depthMedianM = 2f,
            depthP20M = 1.9f,
            depthIqrM = 0.2f,
            trend = Trend.STABLE,
            approachScore = 0f,
            approachSpeedMps = null,
            timeToCollisionMs = null,
            confidence = DepthConfidenceBreakdown(
                sourceQuality = 0.95f,
                sampleQuality = 0.9f,
                depthQuality = 0.9f,
                detectionQuality = 0.9f,
                trackingQuality = 1f,
                motionQuality = 1f,
                freshnessQuality = 1f,
                corridorQuality = 1f,
                hardGate = 1f,
            ),
            userFacing = UserFacingDepth(stepsAhead = 3, messageLevel = MessageLevel.NONE, message = null),
            trackAgeFrames = 4,
            trackStableMs = 900L,
        )
    }

    private fun productionFrameInput(context: TactileProjectionContext): AndroidTactileFrameInput {
        return AndroidTactileFrameComposition.input(
            outputs = listOf(tactileOutput()),
            detectionIdentity = AndroidDetectionSnapshotFrameIdentity(
                captureFrameId = FRAME_ID,
                frameTimestampMs = NOW_MS,
            ),
            evidenceIdentity = AndroidTactileEvidenceFrameIdentity(
                frameId = FRAME_ID,
                frameTimestampMs = NOW_MS,
                depthFrameId = FRAME_ID,
                depthMapperFrameId = FRAME_ID,
            ),
            context = context,
            detectionAgeMs = 100L,
            navigationActiveAtCapture = true,
            tmapOnRouteAtCapture = true,
            navigationActiveNow = true,
            tmapOnRouteNow = true,
            activeRouteId = context.expectedRouteId,
        )
    }

    private class RecordingTactileFrameFeedbackActuator : TactileFrameFeedbackActuator {
        val navigationSpeech = mutableListOf<String>()
        val dispatches = mutableListOf<TactileFrameFeedbackDispatch>()
        var dispatchCount = 0

        override fun emit(dispatch: TactileFrameFeedbackDispatch) {
            dispatchCount += 1
            dispatches += dispatch
            dispatch.action
                ?.takeIf { action -> action.level == MessageLevel.INFO }
                ?.message
                ?.let(navigationSpeech::add)
        }
    }

    private companion object {
        const val FRAME_ID = 1L
        const val NOW_MS = 10_000L
        const val MAX_DETECTION_AGE_MS = 800L
        const val STALE_TACTILE_AGE_MS = 401L
    }
}
