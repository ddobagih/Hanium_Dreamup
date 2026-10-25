package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityCameraAdmissionStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun walkingCallsUseAdmissionAndTheDevicePreflightUsesItsOwnCurrentBindingFence() {
        val invocation = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorInvocationWorker.kt").readText(), "invoke")
        assertEquals(1, Regex(Regex.escape("CameraDetectorAdmissionPolicy.admit(")).findAll(invocation).count())
        assertTrue(ReportStaticSourceInspector.appearsInOrder(invocation,
            "CameraDetectorAdmissionPolicy.admit(", "copyOwnedFrame() ?: return@admit",
            "detectorOwner.beginNativeFlight(", "detectorOwner.detectOriented("))
        val ar = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryArDetectorWorker.kt").readText(), "run")
        val fallback = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryCameraFallbackDetectorWorker.kt").readText(), "analyze")
        // Two walking invocations share one actual admission owner; the third is device preflight.
        for (worker in listOf(ar, fallback)) {
            assertEquals(1, Regex(Regex.escape("invocationWorker.invoke(")).findAll(worker).count())
            assertTrue(worker.contains("if (observed.admission?.detectorInvocationAllowed != true)"))
        }
        for ((property, factory) in listOf("primaryArDetectorWorker" to "createPrimaryArDetectorWorker",
            "primaryCameraFallbackDetectorWorker" to "createPrimaryCameraFallbackDetectorWorker")) {
            assertTrue(MainActivityRefactorSource.property(source, property).contains("by lazy { $factory() }"))
            assertTrue(MainActivityRefactorSource.factory(source, factory).contains("primaryDetectorOwner, primaryDetectorInvocationWorker"))
        }
        assertTrue(MainActivityRefactorSource.member(source, "scheduleDetectionIfDue").contains("primaryArSubmissionBridge.schedule("))
        assertTrue(MainActivityRefactorSource.member(source, "analyzeCameraFallbackFrame").contains("primaryCameraFallbackDetectorWorker.analyze("))
        assertTrue(MainActivityRefactorSource.member(source, "startPostLoginCameraPipelinePreflight").contains("postLoginDeviceProbes.startCamera(binding)"))
        assertTrue(MainActivityRefactorSource.property(source, "postLoginDeviceProbes").contains("postLoginDeviceProbesHolder.value"))
        assertTrue(MainActivityRefactorSource.property(source, "postLoginDeviceProbesHolder").contains("AndroidPostLoginDeviceProbes.android("))
        val preflight = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/device/postlogin/AndroidPostLoginDeviceProbes.kt").readText(), "startCamera")
        assertEquals(1, Regex(Regex.escape("detector().detect(")).findAll(preflight).count())
        assertTrue(ReportStaticSourceInspector.appearsInOrder(preflight,
            "if (!cameraCurrent(binding, generation) || !frameClaimed.compareAndSet(false, true)) { image.close(); return@image }",
            "image.copyOwnedFrame()", "image.close()", "detector().detect("))
    }

    @Test
    fun arAdmissionUsesCaptureTimeAndCurrentElapsedTimeSeparately() {
        val ar = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryArDetectorWorker.kt").readText(), "run")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(ar,
            "val admittedAt = elapsedRealtimeMs()", "work.capturedAtElapsedRealtimeMs, admittedAt"))
        val invocation = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorInvocationWorker.kt").readText(), "invoke")
        assertTrue(invocation.contains("nowElapsedRealtimeMs = request.admissionAtElapsedRealtimeMs,"))
        val capture = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/ArFrameRecognitionCapture.kt").readText(), "copyFrameRecognitionInput")
        assertTrue(capture.contains("observedAtElapsedRealtimeMs = capturedAtElapsedRealtimeMs,"))
        assertTrue(MainActivityRefactorSource.factory(source, "createPrimaryArDetectorWorker").contains("SystemClock::elapsedRealtime"))
    }

    @Test
    fun walkingAdmissionsUseMeasuredLumaAndMountingSensorsWithTheActiveProfile() {
        val measurement = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/device/environment/AndroidOfficialEnvironmentPreflightSources.kt").readText(), "build")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(measurement,
            "CameraLumaMeasurementPolicy.measure(", "mountingMeasurement()"))
        assertTrue(MainActivityRefactorSource.member(source, "cameraFrameQualityObservation").contains("phoneMountingSensorProbe.latestNow()"))
        val fallbackAccess = ReportStaticSourceInspector.blockAfter(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/camera/CameraFallbackController.kt").readText(),
            "override fun <T> inspectLuma(block: (ByteBuffer?, Int?, Int?) -> T): T")
        assertTrue(fallbackAccess.contains("val plane = proxy.planes.firstOrNull()"))
        val fallback = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryCameraFallbackDetectorWorker.kt").readText(), "analyze")
        assertTrue(fallback.contains("frameCapture.inspectLuma"))
        for (factory in listOf("createPrimaryArDetectorWorker", "createPrimaryCameraFallbackDetectorWorker"))
            assertTrue(MainActivityRefactorSource.factory(source, factory).contains("override fun approvedProfile() = activeCameraFrameQualityProfile"))
        val ar = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/frame/ArFrameRecognitionCapture.kt").readText(), "copyFrameRecognitionInput")
        assertTrue(ar.contains("authority.cameraQualityObservation("))
        assertTrue(MainActivityRefactorSource.property(source, "frameRecognitionHolder").contains("= cameraFrameQualityObservation(epoch,"))
        val invocation = MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorInvocationWorker.kt").readText(), "invoke")
        assertTrue(invocation.contains("approvedProfile = request.approvedProfile,"))
        assertTrue(source.contains("WalkSafeEnvironmentProfiles.active(allowTestCandidate = BuildConfig.DEBUG)"))
    }
}
