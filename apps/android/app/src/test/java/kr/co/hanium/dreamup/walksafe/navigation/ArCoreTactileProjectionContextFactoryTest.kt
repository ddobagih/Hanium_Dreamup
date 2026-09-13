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
        assertFalse(instructionsBeforeFactory.map(::operation).any { it == "astore 1" || it == "lstore 2" })
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
        val onDrawFrame = compiledMethod(disassembly, "onDrawFrame")
        val drawInstructions = bytecodeInstructions(onDrawFrame)
        val sessionUpdate = "com/google/ar/core/Session.update:()Lcom/google/ar/core/Frame;"
        assertEquals(1, Regex(Regex.escape(sessionUpdate)).findAll(disassembly).count())
        val frameLoad = localLoad(onDrawFrame, "frame", "a")
        val providerLoad = localLoad(onDrawFrame, "provider", "a")
        val timestampLoad = localLoad(onDrawFrame, "timestampMs", "l")
        val elapsedLoad = localLoad(onDrawFrame, "elapsedRealtimeMs", "l")
        val snapshotLoad = localLoad(onDrawFrame, "snapshot", "a")
        val displayGeometryCall = "Frame.hasDisplayGeometryChanged:()Z"
        val displayIndex = drawInstructions.indexOfFirst { it.contains(displayGeometryCall) }
        assertTrue(displayIndex > 0)
        assertEquals(frameLoad, operation(drawInstructions[displayIndex - 1]))
        // Kotlin may copy the try-expression result through temporary locals. Follow those
        // copies back to Session.update instead of assuming a particular local slot.
        assertLocalOrigin(drawInstructions.take(displayIndex), frameLoad, sessionUpdate)
        assertTrue(
            onDrawFrame.substringAfter(sessionUpdate).substringBefore(displayGeometryCall)
                .contains("isArSessionLeaseCurrent"),
        )
        assertLocalNotReassigned(drawInstructions.drop(displayIndex), frameLoad)
        assertFrameTimestampReceivers(onDrawFrame, frameLoad)
        val timestampConversion = drawInstructions.windowed(4).single {
            it[0].contains(FRAME_TIMESTAMP_CALL) && it[1].contains("long 1000000l")
        }
        assertEquals(listOf("invokevirtual", "ldc2_w", "ldiv", "lstore"), timestampConversion.map(::opcode))
        assertEquals(timestampLoad.replaceFirst("load", "store"), operation(timestampConversion.last()))
        assertLocalNotReassigned(drawInstructions.dropWhile { it != timestampConversion.last() }.drop(1), timestampLoad)

        val depthCall = "ArCoreFrameProvider.acquireDepthSnapshot:(Lcom/google/ar/core/Frame;Z)Lkr/co/hanium/dreamup/walksafe/depth/DepthFrameSnapshot;"
        val depthArguments = bytecodeInstructions(onDrawFrame.substringBefore(depthCall)).takeLast(4)
        assertTrue(onDrawFrame.contains(depthCall))
        assertEquals(listOf(providerLoad, frameLoad), depthArguments.take(2).map(::operation))
        assertTrue(operation(depthArguments[2]).startsWith("iload"))
        val snapshotStore = bytecodeInstructions(onDrawFrame.substringAfter(depthCall)).first()
        assertEquals(snapshotLoad.replaceFirst("load", "store"), operation(snapshotStore))
        assertLocalNotReassigned(drawInstructions.dropWhile { it != snapshotStore }.drop(1), snapshotLoad)

        val gateCall = "updateRuntimeMetricOutputGate:"
        val duplicateRead = "Field lastRuntimeCameraFrameId:J"
        assertTrue("duplicate frames must still reach the elapsed-time quality gate", onDrawFrame.contains(duplicateRead))
        assertTrue(onDrawFrame.indexOf(gateCall) in 0 until onDrawFrame.indexOf(duplicateRead))
        assertTrue(onDrawFrame.indexOf(duplicateRead) < onDrawFrame.indexOf("captureVisualTrackingFrame:"))

        val scheduleCall = "scheduleDetectionIfDue:(Lkr/co/hanium/dreamup/walksafe/depth/ArCoreFrameProvider;Lcom/google/ar/core/Frame;JJJLkr/co/hanium/dreamup/walksafe/depth/DepthFrameSnapshot;ILkr/co/hanium/dreamup/walksafe/session/WalkRuntimeEpoch;JLkr/co/hanium/dreamup/walksafe/inference/tracking/VisualFrameKey;)V"
        assertTrue(onDrawFrame.contains(scheduleCall))
        val scheduleSetup = bytecodeInstructions(
            onDrawFrame.substringAfter("captureVisualTrackingFrame:").substringBefore(scheduleCall),
        )
        assertEquals(localLoad(onDrawFrame, "grayFrame", "a").replaceFirst("load", "store"), operation(scheduleSetup.first()))
        val scheduleArguments = scheduleSetup.drop(1)
        // The nullable visual key adds branches after the stable capture arguments;
        // do not count instructions backwards from the invocation.
        assertEquals(
            listOf(
                "aload 0", providerLoad, frameLoad, timestampLoad,
                localLoad(onDrawFrame, "nowMs", "l"), elapsedLoad, snapshotLoad,
                localLoad(onDrawFrame, "frameGeneration", "i"),
                localLoad(onDrawFrame, "walkEpoch", "a"),
                localLoad(onDrawFrame, "arLease", "a"),
            ),
            scheduleArguments.take(10).map(::operation),
        )
        assertTrue(scheduleArguments[10].contains("ArSessionLease.getGeneration:()J"))
        assertEquals(localLoad(onDrawFrame, "grayFrame", "a"), operation(scheduleArguments[11]))
        assertTrue(scheduleArguments.any { it.contains("GrayTrackingFrame.getKey:") })

        val schedule = compiledMethod(disassembly, "scheduleDetectionIfDue")
        val scheduledFrameLoad = localLoad(schedule, "frame", "a")
        val scheduledTimestampLoad = localLoad(schedule, "timestampMs", "l")
        val scheduledElapsedLoad = localLoad(schedule, "elapsedRealtimeMs", "l")
        val scheduledDepthLoad = localLoad(schedule, "capturedDepthSnapshot", "a")
        val cameraImageCall = "ArCoreFrameProvider.acquireCameraImageOrNull:(Lcom/google/ar/core/Frame;)Landroid/media/Image;"
        assertTrue(schedule.contains(cameraImageCall))
        assertEquals(
            listOf(localLoad(schedule, "provider", "a"), scheduledFrameLoad),
            bytecodeInstructions(schedule.substringBefore(cameraImageCall)).takeLast(3).take(2).map(::operation),
        )
        for (load in listOf(scheduledFrameLoad, scheduledTimestampLoad, scheduledElapsedLoad, scheduledDepthLoad)) {
            assertLocalNotReassigned(bytecodeInstructions(schedule), load)
        }
        assertFrameTimestampReceivers(schedule, scheduledFrameLoad)
        // takeIf on visualFrameKey can spill constructor arguments before `new`.
        // Start after the preceding identity construction, retaining their producers.
        val sourceEvidence = schedule.substringAfter("AndroidDetectionSnapshotFrameIdentity.\"<init>\"")
            .substringBefore("MainActivity\$DetectionFrameEvidence.\"<init>\"")
        assertEvidenceInputs(sourceEvidence, scheduledFrameLoad, scheduledTimestampLoad, scheduledElapsedLoad, scheduledDepthLoad)
        val currentEvidence = onDrawFrame.substringAfter("// class kr/co/hanium/dreamup/walksafe/MainActivity\$DetectionFrameEvidence")
            .substringBefore("MainActivity\$DetectionFrameEvidence.\"<init>\"")
        assertEvidenceInputs(currentEvidence, frameLoad, timestampLoad, elapsedLoad, snapshotLoad)
    }

    private fun assertEvidenceInputs(
        evidence: String,
        frameLoad: String,
        timestampLoad: String,
        elapsedLoad: String,
        snapshotLoad: String,
    ) {
        assertTrue(evidence.contains(FRAME_TIMESTAMP_CALL))
        val frameIdentity = bytecodeInstructions(evidence.substringAfter(FRAME_TIMESTAMP_CALL)).take(2)
        assertEquals(listOf(timestampLoad, snapshotLoad), frameIdentity.map(::operation))
        val mapperCall = "createFrozenDepthMapper:(Lcom/google/ar/core/Frame;JIILkr/co/hanium/dreamup/walksafe/depth/DepthFrameSnapshot;)Lkr/co/hanium/dreamup/walksafe/FrozenImageToTextureCoordinateMapper;"
        assertTrue(evidence.contains(mapperCall))
        val mapperArguments = bytecodeInstructions(evidence.substringAfter(FRAME_TIMESTAMP_CALL).substringBefore(mapperCall)).drop(2)
        assertEquals(listOf("aload 0", frameLoad, frameLoad), mapperArguments.take(3).map(::operation))
        assertTrue(mapperArguments[3].contains(FRAME_TIMESTAMP_CALL))
        assertEquals(snapshotLoad, operation(mapperArguments[mapperArguments.lastIndex - 1]))
        val projectionCall = "buildTactileProjectionContext:(Lcom/google/ar/core/Frame;J)Lkr/co/hanium/dreamup/walksafe/navigation/TactileProjectionContext;"
        assertEquals(1, Regex(Regex.escape(projectionCall)).findAll(evidence).count())
        assertEquals(
            listOf("aload 0", frameLoad, elapsedLoad),
            bytecodeInstructions(evidence.substringBefore(projectionCall)).takeLast(4).take(3).map(::operation),
        )
        val afterProjection = evidence.substringAfter(projectionCall)
        assertFalse(afterProjection.contains("Method kr/co/hanium/dreamup/walksafe/navigation/TactileProjectionContext."))
        val motionArguments = bytecodeInstructions(afterProjection).take(3)
        assertEquals(listOf("aload 0", elapsedLoad), motionArguments.take(2).map(::operation))
        assertTrue(motionArguments[2].contains("buildDepthMotionContext"))
    }

    private fun assertFrameTimestampReceivers(method: String, expectedFrameLoad: String) {
        val instructions = bytecodeInstructions(method)
        val calls = instructions.indices.filter { instructions[it].contains(FRAME_TIMESTAMP_CALL) }
        assertTrue("frame timestamps must be present", calls.isNotEmpty())
        for (index in calls) {
            assertEquals("timestamp receiver at ${instructions[index]}", expectedFrameLoad, operation(instructions[index - 1]))
        }
    }

    private fun assertLocalOrigin(instructions: List<String>, load: String, producer: String) {
        val store = load.replaceFirst("load", "store")
        val storeIndex = instructions.indexOfLast { operation(it) == store }
        assertTrue("missing assignment for $load", storeIndex > 0)
        val source = instructions[storeIndex - 1]
        if (source.contains(producer)) return
        assertTrue("$load must copy the session update result: $source", operation(source).startsWith("aload "))
        assertLocalOrigin(instructions.take(storeIndex), operation(source), producer)
    }

    private fun assertLocalNotReassigned(instructions: List<String>, load: String) {
        val store = load.replaceFirst("load", "store")
        assertFalse("captured value must not be replaced: $load", instructions.any { operation(it) == store })
    }

    private fun compiledMethod(disassembly: String, name: String): String {
        val header = Regex("(?m)^  [^\\n]* " + Regex.escape(name) + "\\([^\\n]*\\);$")
            .find(disassembly)
        assertTrue("compiled method missing: $name", header != null)
        return disassembly.substring(requireNotNull(header).range.last + 1)
            .lineSequence().takeWhile { !Regex("^  \\S").containsMatchIn(it) }.joinToString("\n")
    }

    private fun localLoad(method: String, name: String, type: String): String {
        val row = Regex("(?m)^\\s*\\d+\\s+\\d+\\s+(\\d+)\\s+" + Regex.escape(name) + "\\s+\\S+\\s*$")
            .find(method.substringAfter("LocalVariableTable:"))
        assertTrue("compiled local missing: $name", row != null)
        return "${type}load ${requireNotNull(row).groupValues[1]}"
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
            "-l",
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
        .replace(Regex("^([ailfd](?:load|store))_([0-3])$"), "$1 $2")

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
        const val FRAME_TIMESTAMP_CALL = "com/google/ar/core/Frame.getTimestamp:()J"
        const val FRAME_ID = 123_456_789L
        const val NOW_MS = 10_000L
        const val ROUTE_ID = "route-1"
        const val SQRT_HALF = 0.70710677f
        const val FLOAT_TOLERANCE = 0.0001f
    }
}
