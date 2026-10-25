package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustPolicy
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HorizontalUncertaintyKind
import kr.co.hanium.dreamup.walksafe.navigation.positioning.LocalEnuPosition
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionCoordinate
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionMaxAxisSigmaM
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningFilteredFix
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ReportedAccuracyRadius68M
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Main scalar conversion plus source wiring; no Android location or GNSS sensor is run. */
class MainActivityPositionUncertaintySemanticsTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun filteredFixConversionPreservesTheCoordinatesTimeAndScalarAsPosteriorSigma() {
        val fix = filteredFix(2.75)

        val trusted = convertWithActualMain(fix)

        assertEquals(fix.coordinate.latitude, trusted.latitude, 0.0)
        assertEquals(fix.coordinate.longitude, trusted.longitude, 0.0)
        assertEquals(fix.elapsedRealtimeMs, trusted.elapsedRealtimeMs)
        assertEquals(fix.horizontalUncertaintyM.toFloat(), trusted.accuracyM, 0f)
        assertEquals(HorizontalUncertaintyKind.FILTER_MAX_AXIS_SIGMA, trusted.uncertaintyKind)
        assertEquals(PositionMaxAxisSigmaM(2.75), trusted.positionMaxAxisSigmaM)
        assertNull(trusted.reportedAccuracyRadius68M)
        assertEquals(PositionMaxAxisSigmaM(2.75), fix.positionMaxAxisSigmaM)
    }

    @Test
    fun namingAnInvalidFilteredScalarDoesNotCreateTypedEvidenceOrPassExistingTrustAdmission() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).forEach { sigma ->
            val fix = filteredFix(sigma)

            val trusted = convertWithActualMain(fix)

            assertEquals(fix.coordinate.latitude, trusted.latitude, 0.0)
            assertEquals(fix.coordinate.longitude, trusted.longitude, 0.0)
            assertEquals(fix.elapsedRealtimeMs, trusted.elapsedRealtimeMs)
            assertEquals(sigma.toFloat().toBits(), trusted.accuracyM.toBits())
            assertEquals(HorizontalUncertaintyKind.FILTER_MAX_AXIS_SIGMA, trusted.uncertaintyKind)
            assertNull(trusted.positionMaxAxisSigmaM)
            assertNull(trusted.reportedAccuracyRadius68M)
            assertNull(
                LocationTrustPolicy.trustedOrNull(
                    trusted.latitude, trusted.longitude, trusted.accuracyM, trusted.elapsedRealtimeMs,
                ),
            )
        }
    }

    @Test
    fun anUnreportedAndroidAccuracyKeepsTheLegacyScalarWithoutInventingARadius() {
        val trusted = convertAndroidRawWithActualMain(null)

        assertEquals(37.5, trusted.latitude, 0.0)
        assertEquals(127.0, trusted.longitude, 0.0)
        assertEquals(1_234L, trusted.elapsedRealtimeMs)
        assertEquals(Float.MAX_VALUE, trusted.accuracyM, 0f)
        assertEquals(HorizontalUncertaintyKind.LEGACY_UNSPECIFIED, trusted.uncertaintyKind)
        assertNull(trusted.reportedAccuracyRadius68M)
        assertNull(trusted.positionMaxAxisSigmaM)
    }

    @Test
    fun invalidAndroidAccuracyKeepsItsBitsWithoutClaimingAReportedRadius() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f).forEach { accuracy ->
            val trusted = convertAndroidRawWithActualMain(accuracy)

            assertEquals(37.5, trusted.latitude, 0.0)
            assertEquals(127.0, trusted.longitude, 0.0)
            assertEquals(1_234L, trusted.elapsedRealtimeMs)
            assertEquals(accuracy.toRawBits(), trusted.accuracyM.toRawBits())
            assertEquals(HorizontalUncertaintyKind.LEGACY_UNSPECIFIED, trusted.uncertaintyKind)
            assertNull(trusted.reportedAccuracyRadius68M)
            assertNull(trusted.positionMaxAxisSigmaM)
        }
    }

    @Test
    fun reportedAndroidRadiiKeepTheirKindIncludingZeroAndTheMaximumFiniteValue() {
        listOf(0f, 3f, Float.MAX_VALUE).forEach { accuracy ->
            val trusted = convertAndroidRawWithActualMain(accuracy)

            assertEquals(37.5, trusted.latitude, 0.0)
            assertEquals(127.0, trusted.longitude, 0.0)
            assertEquals(1_234L, trusted.elapsedRealtimeMs)
            assertEquals(accuracy.toRawBits(), trusted.accuracyM.toRawBits())
            assertEquals(HorizontalUncertaintyKind.ANDROID_RADIUS_68, trusted.uncertaintyKind)
            assertEquals(ReportedAccuracyRadius68M(accuracy.toDouble()), trusted.reportedAccuracyRadius68M)
            assertNull(trusted.positionMaxAxisSigmaM)
        }
    }

    @Test
    fun sourceOnlyBothAndroidRawCallersPassTheirOriginalAccuracyAndTimeToTheActualHelper() {
        val locationUpdate = NavigationPolicySource.observation(source)
        assertTrue(locationUpdate.contains("val navigationTestFix ="))
        val navigationTest = locationUpdate.substringAfter("val navigationTestFix =")
            .substringBefore("latestNavigationTestLocation =")
        assertTrue(navigationTest.contains("rawAndroidRouteLocation("))
        assertTrue(navigationTest.contains("location.latitude, location.longitude, accuracy, elapsedMs"))
        assertFalse(navigationTest.contains("Float.MAX_VALUE"))

        assertTrue(locationUpdate.contains("val rawRouteLocation = warmingSelection?.location ?: rawAndroidRouteLocation("))
        val routeRaw = locationUpdate.substringAfter("val rawRouteLocation = warmingSelection?.location ?: rawAndroidRouteLocation(")
            .substringBefore("latestTrustedLocation =")
        assertTrue(routeRaw.contains("latitude = location.latitude"))
        assertTrue(routeRaw.contains("longitude = location.longitude"))
        assertTrue(routeRaw.contains("reportedAccuracyM = accuracy"))
        assertTrue(routeRaw.contains("elapsedRealtimeMs = elapsedMs"))
        val rawConversion = functionBlock("private fun rawAndroidRouteLocation(")
        assertTrue(rawConversion.contains("accuracyM = reportedAccuracyM ?: Float.MAX_VALUE"))
    }

    @Test
    fun sourceOnlyRouteHandoffKeepsTheKindAndScalarAndDiagnosticsNameTheFilteredSigma() {
        val guidance = NavigationPolicySource.guidance(source)
        val routePosition = guidance.substringAfter("filteredPosition = FilteredRoutePosition(")
            .substringBefore("recordRouteAlignmentDiagnostic(")
        assertTrue(routePosition.contains("horizontalAccuracyM = location.accuracyM.toDouble()"))
        assertTrue(routePosition.contains("elapsedRealtimeMs = location.elapsedRealtimeMs"))
        assertTrue(routePosition.contains("uncertaintyKind = location.uncertaintyKind"))
        assertFalse(routePosition.contains("nominalMeasurementAxisStdDevM"))

        val locationUpdate = NavigationPolicySource.observation(source)
        assertTrue(locationUpdate.contains("publishPositioningQualityStatus(positioningSnapshot, filteredTrusted)"))
        val diagnostics = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt").readText(), "publishPositioningQualityStatus")
        assertTrue(diagnostics.contains("max_axis_sigma=\${filteredTrusted.accuracyM.toInt()}m"))
        assertFalse(diagnostics.contains("accuracy=\${filteredTrusted.accuracyM.toInt()}m"))
    }

    private fun filteredFix(sigma: Double) = PositioningFilteredFix(
        coordinate = PositionCoordinate(37.5, 127.0),
        localEnu = LocalEnuPosition(4.0, 8.0),
        horizontalUncertaintyM = sigma,
        velocityEastMps = 0.5,
        velocityNorthMps = 0.25,
        elapsedRealtimeMs = 1_234L,
    )

    private fun convertWithActualMain(fix: PositioningFilteredFix): TrustedLocation {
        val owner = MainActivityControllerTestFixture.controller(MainActivity(), "positioningSessionController") as
            kr.co.hanium.dreamup.walksafe.navigation.session.PositioningSessionController
        return with(owner) { fix.toTrustedLocation() }
    }

    private fun convertAndroidRawWithActualMain(reportedAccuracyM: Float?): TrustedLocation {
        val method = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(
                MainActivity::class.java,
                "rawAndroidRouteLocation",
                MethodType.methodType(
                    TrustedLocation::class.java,
                    Double::class.javaPrimitiveType,
                    Double::class.javaPrimitiveType,
                    Float::class.javaObjectType,
                    Long::class.javaPrimitiveType,
                ),
            )
        return method.bindTo(MainActivity())
            .invokeWithArguments(listOf(37.5, 127.0, reportedAccuracyM, 1_234L)) as TrustedLocation
    }

    private fun functionBlock(signature: String): String {
        return MainActivityRefactorSource.function(source, signature)
    }
}
