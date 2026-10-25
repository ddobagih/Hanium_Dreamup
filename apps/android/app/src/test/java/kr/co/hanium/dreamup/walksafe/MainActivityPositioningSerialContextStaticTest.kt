package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S09-58 (originals/09.txt:158, "연결 검증"): every positioning input reaches the coordinator on the main
 * looper (one serial context), the coordinator is synchronized, and off-main code only peeks. Ordering
 * by measurement time is covered separately by the estimator replay tests.
 */
class MainActivityPositioningSerialContextStaticTest {
    private val main = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val coordinator = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/positioning/PositioningCoordinator.kt").readText()
    private val motionTracker =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/positioning/AndroidPedestrianMotionTracker.kt").readText()
    private val stepTracker = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidStepTracker.kt").readText()

    @Test
    fun everyInputFeedIsDeliveredOnTheMainLooper() {
        // Location fixes: the fused provider calls back on the main looper.
        val sources = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt").readText()
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt").readText()
        MainActivityRefactorSource.composition(main, "positioningSessionController")
        assertTrue(MainActivityRefactorSource.factory(main, "createNavigationSessions").contains("sources = { createNavigationAndroidSources() }"))
        assertTrue(sources.contains("source.requestUpdates(request, Looper.getMainLooper(), Executor { runOnUiThread(it) })"))
        // Step events from the sensor callback are posted to the UI thread before touching the coordinator.
        val stepFeed = main.substringAfter("onStepEvent = { event ->").substringBefore("earthOrientationTracker = ")
        assertTrue(stepFeed.contains("runOnUiThread {\n                                handlePositioningStepEvent(event, binding, receivedAtMs, motionOwner)"))
        // Motion and step sensors are registered without a Handler: SensorManager delivers on the main looper.
        assertEquals(1, Regex("""sensorManager\.registerListener\(listener, it, SensorManager\.SENSOR_DELAY_GAME\)""").findAll(motionTracker).count())
        assertTrue(motionTracker.contains("registration.register(listener, Sensor.TYPE_ACCELEROMETER)"))
        assertTrue(motionTracker.contains("registration.register(listener, Sensor.TYPE_GYROSCOPE)"))
        assertTrue(stepTracker.contains("sensorManager.registerListener(listener, it, delay)"))
        assertFalse(motionTracker.contains("Handler("))
        assertFalse(stepTracker.contains("Handler("))
        // GNSS signal status is delivered on the main executor.
        assertTrue(sources.contains("AndroidGnssObservationSource(manager, ContextCompat.getMainExecutor(context))"))
        // The motion callback is the only place that feeds user motion and ZUPT.
        assertEquals(1, Regex("""binding\.coordinator\.observeUserMotion\(""").findAll(owner).count())
        assertEquals(1, Regex("""binding\.coordinator\.observeZupt\(""").findAll(owner).count())
        val motionCallback = MainActivityRefactorSource.member(owner, "startPositioningObservationSources")
        assertTrue(motionCallback.contains("sources.pedestrianTracker motion@{ decision ->"))
        assertTrue(sources.contains("AndroidPedestrianMotionTracker(context, onDecision = onMotion)"))
        assertTrue(motionCallback.contains("binding.coordinator.observeUserMotion("))
        assertTrue(motionCallback.contains("binding.coordinator.observeZupt("))
    }

    @Test
    fun theCoordinatorIsSynchronizedAndOffMainReadersOnlyPeek() {
        val body = coordinator.substringAfter("internal class PositioningCoordinator(")
        val publicMethods = Regex("""\n    (@Synchronized\n    )?fun (\w+)""").findAll(body).toList()
        assertTrue(publicMethods.size >= 15)
        publicMethods.forEach { assertTrue("unsynchronized ${it.groupValues[2]}", it.groupValues[1].isNotEmpty()) }
        // The RAW metadata path (camera/detector thread) reads the motion state without publishing.
        val capture = MainActivityRefactorSource.factory(main, "createRawCollectionTransferController")
        assertTrue(capture.contains("runCatching { positioningCoordinator.peekUserMotion(nowElapsedRealtimeMs) }"))
        val record = MainActivityRefactorSource.function(main, "private fun recordRawCollectionDetectionMetadata(")
        assertTrue(record.contains("capture.peekMotion(elapsedRealtimeMs())"))
        assertFalse(record.contains(".currentUserMotion("))
        assertTrue(coordinator.contains("S09-58"))
    }
}
