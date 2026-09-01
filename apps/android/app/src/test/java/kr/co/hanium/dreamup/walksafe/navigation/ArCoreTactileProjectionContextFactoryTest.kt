package kr.co.hanium.dreamup.walksafe.navigation

import com.google.ar.core.Camera
import com.google.ar.core.CameraIntrinsics
import com.google.ar.core.Frame
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ArCoreTactileProjectionContextFactoryTest {
    @Test
    fun compiledMainActivityProjectionBuilderUsesTheTestedArCoreFactory() {
        val disassembly = disassembleMainActivity()
        val builderSignature = "buildTactileProjectionContext(com.google.ar.core.Frame, long);"
        assertTrue(disassembly.contains(builderSignature))
        val builder = disassembly
            .substringAfter(builderSignature)
            .substringBefore("private final void attemptStepCalibration")
        val factoryInstance = "Field kr/co/hanium/dreamup/walksafe/navigation/ArCoreTactileProjectionContextFactory.INSTANCE:Lkr/co/hanium/dreamup/walksafe/navigation/ArCoreTactileProjectionContextFactory;"
        val factoryCall = "ArCoreTactileProjectionContextFactory.create:(Lcom/google/ar/core/Frame;JLjava/lang/String;Lkr/co/hanium/dreamup/walksafe/navigation/ActiveRouteProjection;Lkr/co/hanium/dreamup/walksafe/navigation/TrustedLocation;Lkr/co/hanium/dreamup/walksafe/navigation/DeviceEarthOrientation;Ljava/lang/Float;)Lkr/co/hanium/dreamup/walksafe/navigation/TactileProjectionContext;"
        assertTrue(builder.contains(factoryInstance))
        assertTrue(builder.contains(factoryCall))
        val instructionsBeforeFactory = bytecodeInstructions(builder.substringBefore(factoryCall))
        assertFalse(instructionsBeforeFactory.map(::operation).any { it == "astore_1" || it == "astore 1" || it == "lstore_2" || it == "lstore 2" })
        val factoryArguments = bytecodeInstructions(
            builder.substringAfter(factoryInstance).substringBefore(factoryCall),
        )
        assertEquals(listOf("aload_1", "lload_2"), factoryArguments.take(2).map(::opcode))
        val afterFactoryCall = builder.substringAfter(factoryCall)
        val opcodesAfterFactory = bytecodeOpcodes(afterFactoryCall)
        assertEquals(listOf("areturn"), opcodesAfterFactory)
    }

    @Test
    fun compiledDrawAndDetectionChainPassesSessionFrameIntoFrameEvidence() {
        val disassembly = disassembleMainActivity()
        val drawSignature = "public void onDrawFrame(javax.microedition.khronos.opengles.GL10);"
        assertTrue(disassembly.contains(drawSignature))
        val onDrawFrame = disassembly
            .substringAfter(drawSignature)
            .substringBefore("private final void handleRuntimeMetricPreflightFrame")
        val sessionUpdate = "com/google/ar/core/Session.update:()Lcom/google/ar/core/Frame;"
        assertTrue(onDrawFrame.contains(sessionUpdate))
        assertEquals(1, Regex(Regex.escape(sessionUpdate)).findAll(onDrawFrame).count())
        assertEquals(1, Regex(Regex.escape(sessionUpdate)).findAll(disassembly).count())
        val updateResultStore = operation(bytecodeInstructions(onDrawFrame.substringAfter(sessionUpdate)).first())
        assertTrue(updateResultStore.startsWith("astore"))
        val displayGeometryCall = "Frame.hasDisplayGeometryChanged:()Z"
        assertTrue(onDrawFrame.contains(displayGeometryCall))
        val displayInstructions = bytecodeInstructions(onDrawFrame.substringBefore(displayGeometryCall)).takeLast(2)
        assertEquals("invokevirtual", opcode(displayInstructions[1]))
        val frameLoad = operation(displayInstructions[0])
        assertTrue(frameLoad.startsWith("aload"))
        assertTrue(
            onDrawFrame.substringAfter(sessionUpdate).substringBefore(displayGeometryCall)
                .contains("isArSessionLeaseCurrent"),
        )
        val frameTimestampCall = "com/google/ar/core/Frame.getTimestamp:()J"
        assertEquals(1, Regex(Regex.escape(frameTimestampCall)).findAll(onDrawFrame).count())
        val timestampInput = bytecodeInstructions(onDrawFrame.substringBefore(frameTimestampCall)).takeLast(2)
        assertEquals(frameLoad, operation(timestampInput[0]))
        assertEquals("invokevirtual", opcode(timestampInput[1]))
        val timestampConversion = bytecodeInstructions(onDrawFrame.substringAfter(frameTimestampCall)).take(3)
        assertEquals(listOf("ldc2_w", "ldiv", "lstore"), timestampConversion.map(::opcode))
        assertTrue(timestampConversion[0].contains("long 1000000l"))
        val timestampStore = operation(timestampConversion[2])
        val depthCall = "ArCoreFrameProvider.acquireDepthBundle:(Lcom/google/ar/core/Frame;)Lkr/co/hanium/dreamup/walksafe/depth/ArCoreDepthBundle;"
        assertTrue(onDrawFrame.contains(depthCall))
        val depthArguments = bytecodeInstructions(onDrawFrame.substringBefore(depthCall)).takeLast(3)
        val providerLoad = operation(depthArguments[0])
        assertTrue(providerLoad.startsWith("aload"))
        assertEquals(frameLoad, operation(depthArguments[1]))
        assertEquals("invokevirtual", opcode(depthArguments[2]))
        val snapshotStore = bytecodeInstructions(onDrawFrame.substringAfter(depthCall))
            .first { opcode(it) == "astore" }
            .let(::operation)
        val scheduleCall = "scheduleDetectionIfDue:(Lkr/co/hanium/dreamup/walksafe/depth/ArCoreFrameProvider;Lcom/google/ar/core/Frame;JJJLkr/co/hanium/dreamup/walksafe/depth/DepthFrameSnapshot;ILkr/co/hanium/dreamup/walksafe/session/WalkRuntimeEpoch;J)V"
        assertTrue(onDrawFrame.contains(scheduleCall))
        val timestampStoresBeforeSchedule = bytecodeInstructions(
            onDrawFrame.substringAfter(frameTimestampCall).substringBefore(scheduleCall),
        ).map(::operation).count { it == timestampStore }
        assertEquals(1, timestampStoresBeforeSchedule)
        val drawInstructionsBeforeSchedule = bytecodeInstructions(onDrawFrame.substringBefore(scheduleCall))
        val scheduleArguments = drawInstructionsBeforeSchedule.takeLast(12)
        assertEquals(
            listOf(
                "aload_0",
                "aload",
                "aload",
                "lload",
                "lload",
                "lload",
                "aload",
                "iload_2",
                "aload",
                "aload_3",
                "invokevirtual",
                "invokespecial",
            ),
            scheduleArguments.map(::opcode),
        )
        assertEquals(providerLoad, operation(scheduleArguments[1]))
        assertEquals(frameLoad, operation(scheduleArguments[2]))
        assertEquals(snapshotStore.replaceFirst("astore", "aload"), operation(scheduleArguments[6]))
        assertTrue(scheduleArguments[10].contains("ArSessionLease.getGeneration:()J"))
        val afterSessionFrameAssignment = bytecodeInstructions(
            onDrawFrame.substringAfter(displayGeometryCall).substringBefore(scheduleCall),
        )
        val frameStore = frameLoad.replaceFirst("aload", "astore")
        assertFalse(afterSessionFrameAssignment.map(::operation).any { it == frameStore })

        val scheduleSignature = "scheduleDetectionIfDue(kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider, com.google.ar.core.Frame, long, long, long, kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot, int, kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch, long);"
        assertTrue(disassembly.contains(scheduleSignature))
        val schedule = disassembly
            .substringAfter(scheduleSignature)
            .substringBefore("private final byte[] encodeDebugFrameJpeg")
        val cameraImageCall = "kr/co/hanium/dreamup/walksafe/depth/ArCoreFrameProvider.acquireCameraImageOrNull:(Lcom/google/ar/core/Frame;)Landroid/media/Image;"
        assertTrue(schedule.contains(cameraImageCall))
        val cameraImageArguments = bytecodeInstructions(schedule.substringBefore(cameraImageCall)).takeLast(3)
        assertEquals(listOf("aload_1", "aload_2", "invokevirtual"), cameraImageArguments.map(::opcode))
        val evidenceStart = "MainActivity\$DetectionFrameEvidence"
        assertTrue(schedule.contains(evidenceStart))
        val evidenceConstruction = schedule
            .substringAfter("// class kr/co/hanium/dreamup/walksafe/$evidenceStart")
            .substringBefore("$evidenceStart.\"<init>\"")
        val projectionCall = "buildTactileProjectionContext:(Lcom/google/ar/core/Frame;J)Lkr/co/hanium/dreamup/walksafe/navigation/TactileProjectionContext;"
        assertTrue(evidenceConstruction.contains(projectionCall))
        val scheduleTimestampCalls = Regex(Regex.escape(frameTimestampCall)).findAll(schedule).toList()
        assertEquals(3, scheduleTimestampCalls.size)
        for (timestampCall in scheduleTimestampCalls) {
            val input = bytecodeInstructions(schedule.substring(0, timestampCall.range.first)).takeLast(2)
            assertEquals(listOf("aload_2", "invokevirtual"), input.map(::opcode))
        }
        assertEquals(2, Regex(Regex.escape(frameTimestampCall)).findAll(evidenceConstruction).count())
        val firstEvidenceTimestamp = bytecodeInstructions(evidenceConstruction.substringBefore(frameTimestampCall)).takeLast(2)
        assertEquals(listOf("aload_2", "invokevirtual"), firstEvidenceTimestamp.map(::opcode))
        assertEquals("lload_3", bytecodeOpcodes(evidenceConstruction.substringAfter(frameTimestampCall)).first())
        val frozenMapperCall = "createFrozenDepthMapper:(Lcom/google/ar/core/Frame;JIILkr/co/hanium/dreamup/walksafe/depth/DepthFrameSnapshot;)Lkr/co/hanium/dreamup/walksafe/FrozenImageToTextureCoordinateMapper;"
        assertTrue(evidenceConstruction.contains(frozenMapperCall))
        val frozenMapperArguments = bytecodeInstructions(evidenceConstruction.substringBefore(frozenMapperCall)).takeLast(8)
        assertEquals(listOf("aload_0", "aload_2"), frozenMapperArguments.take(2).map(::opcode))
        assertEquals("invokespecial", opcode(frozenMapperArguments.last()))
        val scheduleInstructionsBeforeProjection = bytecodeInstructions(schedule.substringBefore(projectionCall))
        assertFalse(
            scheduleInstructionsBeforeProjection.map(::operation).any {
                it == "astore_2" || it == "astore 2" || it == "lstore_3" || it == "lstore 3" || it == "lstore 7"
            },
        )
        val projectionArguments = bytecodeInstructions(evidenceConstruction.substringBefore(projectionCall)).takeLast(4)
        assertEquals(listOf("aload_0", "aload_2", "lload", "invokespecial"), projectionArguments.map(::opcode))
        assertEquals("lload 7", operation(projectionArguments[2]))
        val afterProjectionCall = evidenceConstruction.substringAfter(projectionCall)
        assertFalse(afterProjectionCall.contains("Method kr/co/hanium/dreamup/walksafe/navigation/TactileProjectionContext."))
        val instructionsAfterProjection = bytecodeInstructions(afterProjectionCall)
        assertEquals(listOf("aload_0", "lload", "invokespecial"), instructionsAfterProjection.take(3).map(::opcode))
        assertTrue(instructionsAfterProjection[2].contains("buildDepthMotionContext"))
    }

    @Test
    fun trackingArCoreFrameBuildsSameFrameProjectionContext() {
        val location = TrustedLocation(
            latitude = 37.0,
            longitude = 127.0,
            accuracyM = 2f,
            elapsedRealtimeMs = NOW_MS - 50L,
        )
        val routeProjection = ActiveRouteProjection(
            routeId = ROUTE_ID,
            segmentIndex = 2,
            segmentStart = RoutePoint(37.0, 127.0),
            segmentEnd = RoutePoint(37.001, 127.0),
            distanceToRouteM = 1.5,
            bearingDeg = 0f,
        )
        val orientation = DeviceEarthOrientation(
            deviceToMagneticEnu = RotationMatrix3(
                1f, 0f, 0f,
                0f, 1f, 0f,
                0f, 0f, 1f,
            ),
            observedAtElapsedRealtimeMs = NOW_MS,
            headingErrorDeg = 4f,
            accuracy = EarthOrientationAccuracy.HIGH,
        )
        val frame = TestFrame(
            timestamp = FRAME_ID,
            camera = TestCamera(
                trackingState = TrackingState.TRACKING,
                pose = testCameraPose(),
                intrinsics = testCameraIntrinsics(),
            ),
            androidSensorPose = testAndroidSensorPose(),
        )

        val context = ArCoreTactileProjectionContextFactory.create(
            frame = frame,
            elapsedRealtimeMs = NOW_MS,
            expectedRouteId = ROUTE_ID,
            routeProjection = routeProjection,
            trustedLocation = location,
            orientation = orientation,
            magneticDeclinationDeg = 2.5f,
        )

        assertEquals(FRAME_ID, context.captureFrameId)
        assertEquals(ROUTE_ID, context.expectedRouteId)
        assertSame(routeProjection, context.routeProjection)
        assertSame(location, context.trustedLocation)
        assertSame(orientation, context.orientation)
        assertEquals(2.5f, requireNotNull(context.magneticDeclinationDeg), 0f)
        assertNull(context.detectionAgeMs)
        assertEquals(NOW_MS, context.nowElapsedRealtimeMs)

        val cameraFrame = requireNotNull(context.cameraFrame)
        assertEquals(FRAME_ID, cameraFrame.frameId)
        assertEquals(NOW_MS, cameraFrame.observedAtElapsedRealtimeMs)
        assertEquals(true, cameraFrame.tracking)
        assertEquals(641, cameraFrame.intrinsics.widthPx)
        assertEquals(479, cameraFrame.intrinsics.heightPx)
        assertEquals(503.25f, cameraFrame.intrinsics.focalLengthXPx, 0f)
        assertEquals(497.75f, cameraFrame.intrinsics.focalLengthYPx, 0f)
        assertEquals(311.5f, cameraFrame.intrinsics.principalPointXPx, 0f)
        assertEquals(233.75f, cameraFrame.intrinsics.principalPointYPx, 0f)
        assertRotationEquals(
            expected = RotationMatrix3(
                0f, 0f, -1f,
                -1f, 0f, 0f,
                0f, 1f, 0f,
            ),
            actual = cameraFrame.cameraToAndroidSensor,
        )
    }

    @Test
    fun nonTrackingArCoreFrameOmitsCameraGeometry() {
        for (trackingState in listOf(TrackingState.PAUSED, TrackingState.STOPPED)) {
            val context = ArCoreTactileProjectionContextFactory.create(
                frame = TestFrame(
                    timestamp = FRAME_ID,
                    camera = TestCamera(
                        trackingState = trackingState,
                        pose = testCameraPose(),
                        intrinsics = testCameraIntrinsics(),
                    ),
                    androidSensorPose = testAndroidSensorPose(),
                ),
                elapsedRealtimeMs = NOW_MS,
                expectedRouteId = null,
                routeProjection = null,
                trustedLocation = null,
                orientation = null,
                magneticDeclinationDeg = null,
            )

            assertEquals(FRAME_ID, context.captureFrameId)
            assertEquals(NOW_MS, context.nowElapsedRealtimeMs)
            assertNull(context.cameraFrame)
        }
    }

    private fun assertRotationEquals(expected: RotationMatrix3, actual: RotationMatrix3) {
        assertEquals(expected.m00, actual.m00, FLOAT_TOLERANCE)
        assertEquals(expected.m01, actual.m01, FLOAT_TOLERANCE)
        assertEquals(expected.m02, actual.m02, FLOAT_TOLERANCE)
        assertEquals(expected.m10, actual.m10, FLOAT_TOLERANCE)
        assertEquals(expected.m11, actual.m11, FLOAT_TOLERANCE)
        assertEquals(expected.m12, actual.m12, FLOAT_TOLERANCE)
        assertEquals(expected.m20, actual.m20, FLOAT_TOLERANCE)
        assertEquals(expected.m21, actual.m21, FLOAT_TOLERANCE)
        assertEquals(expected.m22, actual.m22, FLOAT_TOLERANCE)
    }

    private fun testCameraPose(): Pose = Pose(
        floatArrayOf(7f, -5f, 11f),
        floatArrayOf(SQRT_HALF, 0f, 0f, SQRT_HALF),
    )

    private fun testAndroidSensorPose(): Pose = Pose(
        floatArrayOf(-13f, 17f, 19f),
        floatArrayOf(0f, 0f, SQRT_HALF, SQRT_HALF),
    )

    private fun testCameraIntrinsics(): TestCameraIntrinsics = TestCameraIntrinsics(
        dimensions = intArrayOf(641, 479),
        focalLength = floatArrayOf(503.25f, 497.75f),
        principalPoint = floatArrayOf(311.5f, 233.75f),
    )

    private fun disassembleMainActivity(): String {
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
        return disassembly
    }

    private fun bytecodeInstructions(disassembly: String): List<String> = disassembly
        .lineSequence()
        .map(String::trimStart)
        .filter {
            val offset = it.substringBefore(':')
            offset.isNotEmpty() && offset.all(Char::isDigit) && ':' in it
        }
        .toList()

    private fun bytecodeOpcodes(disassembly: String): List<String> =
        bytecodeInstructions(disassembly).map(::opcode)

    private fun opcode(instruction: String): String =
        instruction.substringAfter(':').trimStart().substringBefore(' ')

    private fun operation(instruction: String): String = instruction
        .substringAfter(':')
        .substringBefore("//")
        .trim()
        .replace(Regex("\\s+"), " ")

    private class TestFrame(
        private val timestamp: Long,
        private val camera: Camera,
        private val androidSensorPose: Pose,
    ) : Frame() {
        override fun getTimestamp(): Long = timestamp

        override fun getCamera(): Camera = camera

        override fun getAndroidSensorPose(): Pose = androidSensorPose
    }

    private class TestCamera(
        private val trackingState: TrackingState,
        private val pose: Pose,
        private val intrinsics: CameraIntrinsics,
    ) : Camera() {
        override fun getTrackingState(): TrackingState = trackingState

        override fun getPose(): Pose = pose

        override fun getImageIntrinsics(): CameraIntrinsics = intrinsics
    }

    private class TestCameraIntrinsics(
        private val dimensions: IntArray = intArrayOf(1, 1),
        private val focalLength: FloatArray = floatArrayOf(1f, 1f),
        private val principalPoint: FloatArray = floatArrayOf(0.5f, 0.5f),
    ) : CameraIntrinsics() {
        override fun getImageDimensions(): IntArray = dimensions

        override fun getFocalLength(): FloatArray = focalLength

        override fun getPrincipalPoint(): FloatArray = principalPoint
    }

    private companion object {
        const val FRAME_ID = 123_456_789L
        const val NOW_MS = 10_000L
        const val ROUTE_ID = "route-1"
        const val SQRT_HALF = 0.70710677f
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
