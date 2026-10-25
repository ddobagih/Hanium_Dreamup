package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HorizontalUncertaintyKind
import kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalGeodesy
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionMaxAxisSigmaM
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ReportedAccuracyRadius68M
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class RouteUncertaintySemanticsTest {
    @Test
    fun routeConversionsPreserveExplicitKindsAndNeverGuessLegacyOrInvalidScalars() {
        HorizontalUncertaintyKind.entries.forEach { kind ->
            val source = FilteredRoutePosition(point(50.0), 3.0, 1_000L, uncertaintyKind = kind)
            val converted = source.toTrustedLocation(point(60.0))
            assertEquals(kind, converted.uncertaintyKind)
            assertEquals(point(60.0).longitude, converted.longitude, 0.0)
            assertEquals(3f, converted.accuracyM, 0f)
            assertEquals(1_000L, converted.elapsedRealtimeMs)
            assertEquals(if (kind == HorizontalUncertaintyKind.ANDROID_RADIUS_68) ReportedAccuracyRadius68M(3.0) else null,
                source.reportedAccuracyRadius68M)
            assertEquals(source.reportedAccuracyRadius68M, converted.reportedAccuracyRadius68M)
            assertEquals(if (kind == HorizontalUncertaintyKind.FILTER_MAX_AXIS_SIGMA) PositionMaxAxisSigmaM(3.0) else null,
                source.positionMaxAxisSigmaM)
            assertEquals(source.positionMaxAxisSigmaM, converted.positionMaxAxisSigmaM)
            listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { invalid ->
                val invalidSource = source.copy(horizontalAccuracyM = invalid)
                assertNull(invalidSource.reportedAccuracyRadius68M)
                assertNull(invalidSource.positionMaxAxisSigmaM)
                assertNull(invalidSource.toTrustedLocation().reportedAccuracyRadius68M)
                assertNull(invalidSource.toTrustedLocation().positionMaxAxisSigmaM)
            }
        }
    }

    @Test
    fun rawFallbackKeepsItsAndroidRadiusMeaningInTheActualNavigator() {
        val navigator = navigator()
        navigator.update(raw(HorizontalUncertaintyKind.ANDROID_RADIUS_68), 1_000L, false, facingObservation = facing())
        val sample = requireNotNull(field(navigator, "latestLocationSample"))
        val filtered = field(sample, "filteredPosition") as FilteredRoutePosition
        assertEquals(HorizontalUncertaintyKind.ANDROID_RADIUS_68, filtered.uncertaintyKind)
        assertEquals(ReportedAccuracyRadius68M(3.0), filtered.reportedAccuracyRadius68M)
        assertNull(filtered.positionMaxAxisSigmaM)
        val context = requireNotNull(field(navigator, "latestGuidanceContext"))
        val guidance = field(context, "guidanceLocation") as TrustedLocation
        assertEquals(HorizontalUncertaintyKind.ANDROID_RADIUS_68, guidance.uncertaintyKind)
    }

    @Test
    fun matchedGuidanceKeepsTheFilterSigmaSeparateFromTheRawRadius() {
        val navigator = navigator()
        navigator.update(raw(HorizontalUncertaintyKind.ANDROID_RADIUS_68), 1_000L, false,
            filteredPosition = filtered(HorizontalUncertaintyKind.FILTER_MAX_AXIS_SIGMA), facingObservation = facing())
        assertNotNull(navigator.currentAcceptedRouteMatchFor(1_000L))
        val context = requireNotNull(field(navigator, "latestGuidanceContext"))
        val guidance = field(context, "guidanceLocation") as TrustedLocation
        val raw = field(context, "rawLocation") as TrustedLocation
        assertEquals(PositionMaxAxisSigmaM(3.0), guidance.positionMaxAxisSigmaM)
        assertNull(guidance.reportedAccuracyRadius68M)
        assertEquals(ReportedAccuracyRadius68M(3.0), raw.reportedAccuracyRadius68M)
        assertNull(raw.positionMaxAxisSigmaM)
    }

    @Test
    fun changingOnlyUncertaintyMetadataKeepsTheExistingDuplicateAndSpeechRetryPath() {
        val navigator = navigator()
        navigator.update(raw(HorizontalUncertaintyKind.LEGACY_UNSPECIFIED), 1_000L, false,
            filteredPosition = filtered(HorizontalUncertaintyKind.LEGACY_UNSPECIFIED), facingObservation = facing())
        val previousSample = field(navigator, "latestLocationSample")
        val repeated = navigator.update(raw(HorizontalUncertaintyKind.ANDROID_RADIUS_68), 1_100L, false,
            filteredPosition = filtered(HorizontalUncertaintyKind.FILTER_MAX_AXIS_SIGMA), facingObservation = facing())
        assertNotEquals("location_sample_not_newer", repeated.reason)
        assertFalse(repeated.cancelStaleNavigationSpeech)
        assertSame(previousSample, field(navigator, "latestLocationSample"))
        assertNotNull(navigator.currentAcceptedRouteMatchFor(1_000L))
    }

    @Test
    fun newFilteredMeasurementCanRetainTheSameRawMeasurementWhenOnlyItsKindIsClarified() {
        val navigator = navigator()
        navigator.update(raw(HorizontalUncertaintyKind.LEGACY_UNSPECIFIED), 1_000L, false,
            filteredPosition = filtered(HorizontalUncertaintyKind.LEGACY_UNSPECIFIED), facingObservation = facing())
        val update = navigator.update(raw(HorizontalUncertaintyKind.ANDROID_RADIUS_68), 2_000L, false,
            filteredPosition = filtered(HorizontalUncertaintyKind.FILTER_MAX_AXIS_SIGMA).copy(elapsedRealtimeMs = 2_000L),
            facingObservation = facing().copy(observedAtElapsedRealtimeMs = 2_000L))
        assertNotEquals("location_sample_not_newer", update.reason)
        assertNotNull(navigator.currentAcceptedRouteMatchFor(2_000L))
    }

    private fun navigator() = RouteNavigator().apply {
        setRoute(WalkingRoute("TEST", WalkingRouteSummary(500, 500), listOf(point(0.0), point(500.0)),
            listOf(WalkingRouteGuidePoint(0, point(400.0), null, 400, 100, turnType = 13))), destination = point(500.0))
    }

    private fun raw(kind: HorizontalUncertaintyKind) = TrustedLocation(
        point(50.0).latitude, point(50.0).longitude, 3f, 1_000L, uncertaintyKind = kind,
    )
    private fun filtered(kind: HorizontalUncertaintyKind) = FilteredRoutePosition(
        point(50.0), 3.0, 1_000L, uncertaintyKind = kind,
    )
    private fun facing() = RouteFacingObservation(90.0, 5.0, 1_000L)
    private fun point(east: Double) = RoutePoint(0.0, east / LocalGeodesy.METERS_PER_DEGREE)
    private fun field(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)
}
