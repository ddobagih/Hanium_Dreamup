package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Actual tracker publication core after SDK registration admission, without Android sensors. */
class RouteCompassObservationPublisherTest {
    @Test fun onlyANewOriginalSensorTimestampPublishesANewSample() {
        val publisher = RouteCompassObservationPublisher().apply { reset(3) }
        val heading = good(1_000L)
        val event = requireNotNull(publisher.sample(3, RouteCompassStream.ROTATION_VECTOR, 1_000_001_000L, 1_020_000_000L, heading))
        assertEquals(1_000_001_000L, event.sensorTimestampNanos)
        assertEquals(1_020L, event.receivedAtMs)
        assertSame(heading, event.heading)
        assertFalse(event.invalidated)
        assertNull(publisher.sample(3, event.stream, 1_000_001_000L, 1_040_000_000L, heading))
        assertNull(publisher.sample(3, event.stream, 999_000_000L, 1_040_000_000L, heading))
        assertNotNull(publisher.sample(3, event.stream, 1_030_000_000L, 1_040_000_000L, good(1_030L)))
    }
    @Test fun oldRegistrationFutureAndNegativeSamplesCannotPublish() {
        val publisher = RouteCompassObservationPublisher().apply { reset(2) }
        assertNull(publisher.sample(1, RouteCompassStream.ROTATION_VECTOR, 1_000L, 2_000L, good(0L)))
        assertNull(publisher.sample(2, RouteCompassStream.ROTATION_VECTOR, 2_001L, 2_000L, good(0L)))
        assertNull(publisher.sample(2, RouteCompassStream.ROTATION_VECTOR, -1L, 2_000L, good(0L)))
        publisher.reset(3)
        assertNull(publisher.accuracy(2, RouteCompassStream.ROTATION_VECTOR, 2L, bad()))
    }
    @Test fun accuracyInvalidationNeverInventsADirectionConfirmationSample() {
        val publisher = RouteCompassObservationPublisher().apply { reset(2) }
        val event = requireNotNull(publisher.accuracy(2, RouteCompassStream.ROTATION_VECTOR, 1_100L, bad()))
        assertTrue(event.invalidated)
        assertNull(event.sensorTimestampNanos)
        assertNull(event.heading.observation)
        assertNull(publisher.accuracy(2, RouteCompassStream.ROTATION_VECTOR, 1_101L, good(1_000L)))
        assertNotNull(publisher.sample(2, RouteCompassStream.ROTATION_VECTOR, 1_000_000_000L, 1_102_000_000L, good(1_000L)))
    }
    @Test fun separateStreamsKeepTheirOwnOriginalSampleSequence() {
        val publisher = RouteCompassObservationPublisher().apply { reset(2) }
        assertNotNull(publisher.sample(2, RouteCompassStream.ROTATION_VECTOR, 1_100L, 2_000L, good(0L)))
        assertNotNull(publisher.sample(2, RouteCompassStream.MAGNETIC_FIELD, 1_000L, 2_000L, bad()))
    }
    @Test fun actualTrackerPublishesAfterItsMonitorAndKeepsTheRegistrationAndRawTimeGuards() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidEarthOrientationTracker.kt").readText()
        val listener = source.substringAfter("private fun createListener(").substringBefore("fun stop()")
        assertTrue(listener.contains("!isCurrentRegistration(listener, generation) || event.timestamp < registrationStartedAtNanos"))
        assertTrue(listener.contains("routeCompassState.onSensorCallback(generation, stream, event.timestamp, receivedAtNanos)"))
        assertTrue(listener.contains("accepted = publishAdmittedRouteCompassSample(generation, stream, event.timestamp, receivedAtNanos)"))
        val publication = listener.substringAfter("internal fun publishAdmittedRouteCompassSample(")
        assertTrue(publication.contains("routeObservationPublisher.sample(generation, stream, sensorTimestampNanos, receivedAtNanos, heading)"))
        assertEquals(2, Regex("observation\\?\\.let \\{ routeObservationListener\\?\\.invoke\\(it\\) }").findAll(listener).count())
        assertFalse(listener.contains("latestRouteCompassHeading("))
        assertFalse(listener.contains("reregisterStalledSensors("))
        assertTrue(source.contains("SensorManager.SENSOR_DELAY_GAME"))
    }
    private fun good(at: Long) = RouteCompassHeadingResult(RouteFacingObservation(90.0, 3.0, at), RouteCompassHeadingReason.VALID)
    private fun bad() = RouteCompassHeadingResult(null, RouteCompassHeadingReason.QUALITY_LOW)
}
