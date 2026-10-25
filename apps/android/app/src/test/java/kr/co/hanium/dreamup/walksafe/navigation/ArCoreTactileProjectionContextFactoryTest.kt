package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.depth.FrozenImageToTextureCoordinateMapper
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
        val owner = Class.forName("kr.co.hanium.dreamup.walksafe.runtime.vision.capture.FrameMotionEvidenceComposer", false, MainActivity::class.java.classLoader)
        val javap = File(System.getProperty("java.home"), "bin/javap")
        val process = ProcessBuilder(javap.absolutePath, "-classpath",
            File(owner.protectionDomain.codeSource.location.toURI()).absolutePath,
            "-p", "-c", "-l", owner.name).redirectErrorStream(true).start()
        val disassembly = process.inputStream.bufferedReader().readText()
        assertEquals(disassembly.take(400), 0, process.waitFor())
        val main = disassembleMainActivity()
        val constructor = "runtime/vision/capture/FrameMotionEvidenceComposer.\"<init>\":"
        assertEquals(1, Regex(Regex.escape(constructor)).findAll(main).count())
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val factory = kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.composition(source, "frameMotionEvidence")
        assertTrue(factory.contains("FrameMotionEvidenceComposer({ positioningSessionController }, { routeNavigator },"))
        val builderSignature = "buildTactileProjectionContext(com.google.ar.core.Frame, long);"
        assertTrue(disassembly.contains(builderSignature))
        val builder = compiledMethod(disassembly, "buildTactileProjectionContext")
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
        fun disassembleOwner(name: String): String {
            val owner = Class.forName(name, false, MainActivity::class.java.classLoader)
            val javap = File(System.getProperty("java.home"), "bin/javap")
            val process = ProcessBuilder(javap.absolutePath, "-classpath",
                File(owner.protectionDomain.codeSource.location.toURI()).absolutePath,
                "-p", "-c", "-l", owner.name).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().readText()
            assertEquals(text.take(400), 0, process.waitFor())
            return text
        }
        fun assertOperationsWindow(instructions: List<String>, expected: List<String>) {
            assertTrue("missing original inputs: $expected",
                instructions.map(::operation).windowed(expected.size).any { it == expected })
        }
        val namespace = "kr.co.hanium.dreamup.walksafe.runtime.vision.frame."
        val main = disassembleMainActivity()
        val renderer = disassembleOwner(namespace + "ArFrameRenderController")
        val recognition = disassembleOwner(namespace + "ArFrameRecognitionCapture")
        val pipeline = disassembleOwner(namespace + "FramePipelineController")
        val geometry = disassembleOwner(namespace + "ArFrameGeometryFreezer")
        val submission = disassembleOwner("kr.co.hanium.dreamup.walksafe.runtime.vision.primary.AndroidPrimaryArSubmissionBridge")
        val motion = disassembleOwner("kr.co.hanium.dreamup.walksafe.runtime.vision.capture.FrameMotionEvidenceComposer")
        assertTrue(compiledMethod(main, "onDrawFrame").contains("FramePipelineController.draw:()V"))
        assertTrue(compiledMethod(pipeline, "draw").contains("ArFrameRenderController.draw:"))
        val drawContinuation = compiledMethod(pipeline, "draw\$lambda\$1")
        assertTrue(drawContinuation.indexOf("qualify:") < drawContinuation.indexOf("synthesize:"))
        assertTrue(drawContinuation.contains("ifnonnull"))
        val draw = compiledMethod(renderer, "draw")
        val drawInstructions = bytecodeInstructions(draw)
        val sessionUpdate = "com/google/ar/core/Session.update:()Lcom/google/ar/core/Frame;"
        assertEquals(1, Regex(Regex.escape(sessionUpdate)).findAll(renderer).count())
        assertEquals(0, Regex(Regex.escape(sessionUpdate)).findAll(main).count())
        val frameLoad = localLoad(draw, "frame", "a")
        val leaseLoad = localLoad(draw, "lease", "a")
        val elapsedLoad = localLoad(draw, "elapsedMs", "l")
        val timestampLoad = localLoad(draw, "timestampMs", "l")
        val originalTimestampLoad = localLoad(draw, "originalFrameTimestampNs", "l")
        val snapshotLoad = localLoad(draw, "snapshot", "a")
        val displayGeometryCall = "Frame.hasDisplayGeometryChanged:()Z"
        val displayIndex = drawInstructions.indexOfFirst { it.contains(displayGeometryCall) }
        assertTrue(displayIndex > 0)
        assertEquals(frameLoad, operation(drawInstructions[displayIndex - 1]))
        assertLocalOrigin(drawInstructions.take(displayIndex), frameLoad, sessionUpdate)
        assertTrue(draw.substringAfter(sessionUpdate).substringBefore(displayGeometryCall)
            .contains("ArFrameRenderSource.leaseIsCurrent:"))
        assertLocalNotReassigned(drawInstructions.drop(displayIndex), frameLoad)
        assertFrameTimestampReceivers(draw, frameLoad)
        val conversion = drawInstructions.windowed(4).single {
            operation(it[0]) == originalTimestampLoad && it[1].contains("long 1000000l") && opcode(it[2]) == "ldiv"
        }
        assertEquals(listOf("lload", "ldc2_w", "ldiv", "lstore"), conversion.map(::opcode))
        assertEquals(timestampLoad.replaceFirst("load", "store"), operation(conversion.last()))
        assertLocalNotReassigned(drawInstructions.dropWhile { it != conversion.last() }.drop(1), timestampLoad)
        val recognitionCall = "ArFrameRecognitionCapture.acquireFrameRecognitionCapture\$app:"
        assertTrue(draw.contains(recognitionCall))
        val recognitionArguments = bytecodeInstructions(draw.substringBefore(recognitionCall))
        val providerIndex = recognitionArguments.indexOfLast { it.contains("FrameArResources.getProvider:") }
        assertTrue(providerIndex > 0)
        assertEquals(leaseLoad, operation(recognitionArguments[providerIndex - 1]))
        assertEquals(frameLoad, operation(recognitionArguments[providerIndex + 1]))
        val copyLambda = compiledMethod(renderer, "draw\$lambda\$2")
        assertTrue(copyLambda.contains("ArFrameRecognitionCapture.copyFrameRecognitionInput:"))
        assertOperationsWindow(bytecodeInstructions(copyLambda), listOf("aload 1", "lload 2", "iload 4", "aload 5"))
        val acquire = compiledMethod(recognition, "acquireFrameRecognitionCapture\$app")
        val acquireInstructions = bytecodeInstructions(acquire)
        val acquiredFrameLoad = localLoad(acquire, "frame", "a")
        val providerLoad = localLoad(acquire, "provider", "a")
        assertLocalNotReassigned(acquireInstructions, acquiredFrameLoad)
        val cameraCall = "ArCoreFrameProvider.acquireRecognitionCameraImageOrNull\$default:"
        assertEquals(listOf(providerLoad, acquiredFrameLoad),
            bytecodeInstructions(acquire.substringBefore(cameraCall)).takeLast(6).take(2).map(::operation))
        val depthCall = "ArCoreFrameProvider.acquireDepthSnapshot\$default:"
        assertTrue(acquire.contains(depthCall))
        assertOperationsWindow(bytecodeInstructions(acquire.substringAfter(cameraCall).substringBefore(depthCall)),
            listOf(providerLoad, acquiredFrameLoad))
        assertTrue(acquire.contains("RecognitionCameraImage.getTimestampEvidence:"))
        assertTrue(acquire.contains("AutoCloseableKt.closeFinally:"))
        assertTrue(draw.contains("FrameRecognitionCapture.getDepthSnapshot:"))
        val scopeConstructor = "ScopedArFrameCapture.\"<init>\":"
        assertOperationsWindow(bytecodeInstructions(draw.substringBefore(scopeConstructor)), listOf(
            frameLoad, leaseLoad, localLoad(draw, "frameGeneration", "i"), localLoad(draw, "epoch", "a"),
            originalTimestampLoad, timestampLoad, localLoad(draw, "wallClockMs", "l"), elapsedLoad,
            localLoad(draw, "captured", "a"), snapshotLoad,
        ))
        assertTrue(draw.substringAfter(scopeConstructor).contains("Function1.invoke:"))
        val qualify = compiledMethod(pipeline, "qualify")
        val nativeLoad = localLoad(qualify, "native", "a")
        assertLocalOrigin(bytecodeInstructions(qualify), nativeLoad, "ScopedArFrameCapture.getFrame:")
        val qualifiedInstructions = bytecodeInstructions(qualify)
        val nativeAssignment = qualifiedInstructions.indexOfFirst {
            operation(it) == nativeLoad.replaceFirst("load", "store")
        }
        assertTrue(nativeAssignment > 0)
        assertLocalNotReassigned(qualifiedInstructions.drop(nativeAssignment + 1), nativeLoad)
        assertTrue(qualify.indexOf("FrameRuntimePeer.updateMetricOutput:") < qualify.indexOf("FrameSynthesisController.admitRuntimeFrame:"))
        assertTrue(qualify.indexOf("FrameSynthesisController.admitRuntimeFrame:") < qualify.indexOf("FrameSynthesisController.captureVisualTrackingFrame:"))
        val scheduleCall = "FrameDetectionSchedulingPeer.scheduleNamed:"
        val scheduleArguments = bytecodeInstructions(qualify.substringBefore(scheduleCall))
        assertTrue(qualify.contains(scheduleCall))
        val scheduleFrame = scheduleArguments.indexOfLast { operation(it) == nativeLoad }
        assertTrue(scheduleFrame >= 0)
        val scheduleTail = scheduleArguments.drop(scheduleFrame + 1)
        assertTrue(scheduleTail[1].contains("ScopedArFrameCapture.getTimestampMs:"))
        assertTrue(scheduleTail[3].contains("ScopedArFrameCapture.getWallClockMs:"))
        assertTrue(scheduleTail[5].contains("ScopedArFrameCapture.getCapturedElapsedMs:"))
        assertTrue(scheduleTail[7].contains("ScopedArFrameCapture.getSnapshot:"))
        assertTrue(scheduleTail.any { it.contains("FrameArResources.getGeneration:") })
        val mainSchedule = compiledMethod(main, "scheduleDetectionIfDue")
        val borrowedFrame = localLoad(mainSchedule, "frame", "a")
        val borrowedTimestamp = localLoad(mainSchedule, "timestampMs", "l")
        val borrowedElapsed = localLoad(mainSchedule, "elapsedRealtimeMs", "l")
        val borrowedDepth = localLoad(mainSchedule, "capturedDepthSnapshot", "a")
        assertFalse(mainSchedule.contains(FRAME_TIMESTAMP_CALL))
        for (load in listOf(borrowedFrame, borrowedTimestamp, borrowedElapsed, borrowedDepth)) {
            assertLocalNotReassigned(bytecodeInstructions(mainSchedule), load)
        }
        val submissionCall = "AndroidPrimaryArSubmissionBridge.schedule:"
        assertTrue(mainSchedule.contains(submissionCall))
        assertOperationsWindow(bytecodeInstructions(mainSchedule.substringBefore(submissionCall)), listOf(
            borrowedFrame, borrowedTimestamp, localLoad(mainSchedule, "capturedAtMs", "l"), borrowedElapsed, borrowedDepth,
        ))
        val mainSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val bridgeFactory = kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.property(mainSource, "primaryArSubmissionBridge")
        assertTrue(bridgeFactory.contains("AndroidPrimaryArSubmissionBridge(frameStateLock, frameCaptureRequested,"))
        assertTrue(bridgeFactory.contains("frameRouteEvidence, { primaryArDetectorWorker }"))
        assertTrue(kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource.property(mainSource, "framePipelineController").contains("frameRouteEvidence"))
        val schedule = compiledMethod(submission, "schedule")
        val scheduledFrame = localLoad(schedule, "frame", "a")
        val scheduledTimestamp = localLoad(schedule, "timestampMs", "l")
        val scheduledElapsed = localLoad(schedule, "elapsedRealtimeMs", "l")
        val scheduledDepth = localLoad(schedule, "capturedDepthSnapshot", "a")
        for (load in listOf(scheduledFrame, scheduledTimestamp, scheduledElapsed, scheduledDepth)) {
            assertLocalNotReassigned(bytecodeInstructions(schedule), load)
        }
        assertFrameTimestampReceivers(schedule, scheduledFrame)
        val sourceEvidence = schedule.substringAfter("AndroidDetectionSnapshotFrameIdentity.\"<init>\"")
            .substringBefore("runtime/DetectionFrameEvidence.\"<init>\"")
        val defaultMapperCall = "createFrozenDepthMapper\$default:"
        assertTrue(sourceEvidence.contains(defaultMapperCall))
        val mapperArguments = bytecodeInstructions(sourceEvidence.substringBefore(defaultMapperCall))
        assertOperationsWindow(mapperArguments, listOf(scheduledFrame, scheduledFrame))
        assertEquals(listOf(scheduledDepth, "iconst_0", "bipush 32", "aconst_null"),
            mapperArguments.dropLast(1).takeLast(4).map(::operation))
        val projectionCall = "FrameRouteEvidencePeer.projectionContext:(Lcom/google/ar/core/Frame;J)Lkr/co/hanium/dreamup/walksafe/navigation/TactileProjectionContext;"
        assertEquals(1, Regex(Regex.escape(projectionCall)).findAll(sourceEvidence).count())
        assertEquals(listOf(scheduledFrame, scheduledElapsed),
            bytecodeInstructions(sourceEvidence.substringBefore(projectionCall)).dropLast(1).takeLast(2).map(::operation))
        val allDefaultInstructions = bytecodeInstructions(compiledMethod(geometry, "createFrozenDepthMapper\$default"))
        val defaultMaskStart = allDefaultInstructions.indexOfFirst { operation(it) == "iload 8" }
        assertTrue(defaultMaskStart >= 0)
        if (defaultMaskStart > 0) {
            assertEquals("aload 9", operation(allDefaultInstructions[0]))
            assertEquals("ifnull", opcode(allDefaultInstructions[1]))
            assertTrue(allDefaultInstructions[2].contains("UnsupportedOperationException"))
            assertEquals("athrow", opcode(allDefaultInstructions[defaultMaskStart - 1]))
        }
        val defaultInstructions = allDefaultInstructions.drop(defaultMaskStart)
        assertEquals(listOf("iload 8", "bipush 32", "iand"), defaultInstructions.take(3).map(::operation))
        assertEquals("ifeq", opcode(defaultInstructions[3]))
        assertEquals(listOf("iconst_0", "istore 7"), defaultInstructions.drop(4).take(2).map(::operation))
        assertEquals(listOf("aload 0", "aload 1", "lload 2", "iload 4", "iload 5", "aload 6", "iload 7"),
            defaultInstructions.drop(6).take(7).map(::operation))
        assertTrue(defaultInstructions[13].contains("createFrozenDepthMapper:"))
        val synthesize = compiledMethod(pipeline, "synthesize")
        val currentFrame = localLoad(synthesize, "native", "a")
        val currentSource = localLoad(synthesize, "source", "a")
        assertLocalOrigin(bytecodeInstructions(synthesize), currentFrame, "ScopedArFrameCapture.getFrame:")
        val synthesizedInstructions = bytecodeInstructions(synthesize)
        val currentAssignment = synthesizedInstructions.indexOfFirst {
            operation(it) == currentFrame.replaceFirst("load", "store")
        }
        assertTrue(currentAssignment > 0)
        assertLocalNotReassigned(synthesizedInstructions.drop(currentAssignment + 1), currentFrame)
        assertFrameTimestampReceivers(synthesize, currentFrame)
        val currentEvidence = synthesize.substringAfter("// class kr/co/hanium/dreamup/walksafe/runtime/DetectionFrameEvidence")
            .substringBefore("runtime/DetectionFrameEvidence.\"<init>\"")
        val currentMapper = "ArFrameGeometryFreezer.createFrozenDepthMapper:"
        assertTrue(currentEvidence.contains(currentMapper))
        assertOperationsWindow(bytecodeInstructions(currentEvidence.substringBefore(currentMapper)), listOf(currentFrame, currentFrame))
        val currentMapperTail = bytecodeInstructions(currentEvidence.substringBefore(currentMapper)).dropLast(1).takeLast(3)
        assertEquals(currentSource, operation(currentMapperTail[0]))
        assertTrue(currentMapperTail[1].contains("ScopedArFrameCapture.getSnapshot:"))
        assertEquals("iconst_1", operation(currentMapperTail[2]))
        val routeProjection = "FrameRouteEvidencePeer.projectionContext:"
        assertEquals(1, Regex(Regex.escape(routeProjection)).findAll(currentEvidence).count())
        val routeArguments = bytecodeInstructions(currentEvidence.substringBefore(routeProjection)).dropLast(1).takeLast(3)
        assertEquals(listOf(currentFrame, currentSource), routeArguments.take(2).map(::operation))
        assertTrue(routeArguments.last().contains("ScopedArFrameCapture.getCapturedElapsedMs:"))
        val bridges = Regex("""Method (kr/co/hanium/dreamup/walksafe/MainActivity\$[^\s.:]+)\."<init>":""")
            .findAll(main).map { it.groupValues[1].replace('/', '.') }.distinct()
            .filter { name -> Class.forName(name, false, MainActivity::class.java.classLoader)
                .interfaces.any { it.name == namespace + "FrameRouteEvidencePeer" } }
            .toList()
        assertEquals(1, bridges.size)
        assertTrue(main.contains(bridges.single().replace('.', '/') + ".\"<init>\":"))
        val routeBridge = compiledMethod(disassembleOwner(bridges.single()), "projectionContext")
        val ownedProjection = "FrameMotionEvidenceComposer.buildTactileProjectionContext:"
        assertTrue(routeBridge.contains("access\$getFrameMotionEvidence:"))
        assertTrue(routeBridge.contains(ownedProjection))
        assertEquals(listOf("aload_1", "lload_2"), bytecodeInstructions(routeBridge.substringBefore(ownedProjection)).dropLast(1).takeLast(2).map(::opcode))
        assertTrue(compiledMethod(motion, "buildTactileProjectionContext").contains("ArCoreTactileProjectionContextFactory.create:"))
        val geometryCapture = compiledMethod(geometry, "createFrozenDepthMapper")
        assertTrue(geometryCapture.contains("Frame.transformCoordinates2d:"))
        assertTrue(geometryCapture.contains("DepthFrameSnapshot.getRawDepth:"))
        assertTrue(geometryCapture.contains("DepthFrameSnapshot.getFullDepth:"))
        val geometryInstructions = bytecodeInstructions(geometryCapture)
        val allowMissing = geometryInstructions.indexOfFirst { operation(it) == "iload 7" }
        assertTrue(allowMissing >= 0)
        assertEquals("ifeq", opcode(geometryInstructions[allowMissing + 1]))
        assertEquals(listOf("iconst_1", "iconst_1"), geometryInstructions.drop(allowMissing + 4).take(2).map(::operation))
        assertTrue(geometryCapture.contains("Class java/lang/RuntimeException"))
        assertTrue(geometryCapture.contains("aconst_null"))
        assertTrue(geometryCapture.contains("FrozenImageToTextureCoordinateMapper.\"<init>\":"))
    }

    private fun assertEvidenceInputs(
        evidence: String,
        frameLoad: String,
        timestampLoad: String,
        elapsedLoad: String,
        snapshotLoad: String,
        allowMissingDepth: Boolean,
    ) {
        assertTrue(evidence.contains(FRAME_TIMESTAMP_CALL))
        // Constructor arguments may be spilled while optional visual evidence is built.
        assertTrue(
            bytecodeInstructions(evidence).map(::operation).windowed(2)
                .any { it == listOf(timestampLoad, snapshotLoad) },
        )
        val mapperCall = if (allowMissingDepth) {
            "createFrozenDepthMapper:(Lcom/google/ar/core/Frame;JIILkr/co/hanium/dreamup/walksafe/depth/DepthFrameSnapshot;Z)Lkr/co/hanium/dreamup/walksafe/FrozenImageToTextureCoordinateMapper;"
        } else {
            "createFrozenDepthMapper\$default:(Lkr/co/hanium/dreamup/walksafe/MainActivity;Lcom/google/ar/core/Frame;JIILkr/co/hanium/dreamup/walksafe/depth/DepthFrameSnapshot;ZILjava/lang/Object;)Lkr/co/hanium/dreamup/walksafe/FrozenImageToTextureCoordinateMapper;"
        }
        assertTrue(evidence.contains(mapperCall))
        val mapperArguments = bytecodeInstructions(evidence.substringBefore(mapperCall))
        assertTrue(mapperArguments.windowed(4).any {
            it.take(3).map(::operation) == listOf("aload 0", frameLoad, frameLoad) &&
                it[3].contains(FRAME_TIMESTAMP_CALL)
        })
        val expectedTail = if (allowMissingDepth) {
            listOf(snapshotLoad, "iconst_1")
        } else {
            listOf(snapshotLoad, "iconst_0", "bipush 32", "aconst_null")
        }
        assertEquals(
            expectedTail,
            mapperArguments.dropLast(1).takeLast(expectedTail.size).map(::operation),
        )
        val projectionCall = "buildTactileProjectionContext:(Lcom/google/ar/core/Frame;J)Lkr/co/hanium/dreamup/walksafe/navigation/TactileProjectionContext;"
        assertEquals(1, Regex(Regex.escape(projectionCall)).findAll(evidence).count())
        assertEquals(
            listOf("aload 0", frameLoad, elapsedLoad),
            bytecodeInstructions(evidence.substringBefore(projectionCall)).takeLast(4).take(3).map(::operation),
        )
        val afterProjection = evidence.substringAfter(projectionCall)
        assertFalse(afterProjection.contains("Method kr/co/hanium/dreamup/walksafe/navigation/TactileProjectionContext."))
        val motionInstructions = bytecodeInstructions(afterProjection)
        val motionIndex = motionInstructions.indexOfFirst { it.contains("buildDepthMotionContext") }
        assertTrue(motionIndex >= 2)
        assertEquals(
            listOf("aload 0", elapsedLoad),
            motionInstructions.subList(motionIndex - 2, motionIndex).map(::operation),
        )
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
