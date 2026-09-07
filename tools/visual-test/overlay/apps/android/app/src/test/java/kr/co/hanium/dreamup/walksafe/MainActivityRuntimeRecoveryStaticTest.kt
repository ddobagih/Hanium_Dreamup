package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source-wiring checks only; these tests do not execute Android location callbacks. */
class MainActivityRuntimeRecoveryStaticTest {
    @Test
    fun locationCollectionKeepsItsOwnLeaseWithoutMetricOrFeedbackOutputGates() {
        val body = mainFunction("currentLocationCollectionAllowsWork")
        assertFalse(
            "Collecting recovery GPS must not require metric or feedback output approval",
            Regex(
                "\\b(?:runtimeMetricOutputAllowed|currentRuntimeMetricOutputAllowsWork|" +
                    "currentFeedback[A-Za-z0-9_]*)\\b",
            ).containsMatchIn(body),
        )
        assertContains(body, "if (currentPositionFieldCollectionAllowsWork()) return true")
        assertContains(body, "if (!firstRunOnboardingComplete()) return false")
        assertContains(body, "postLoginDeviceFeatureEnabled(")
        assertContains(body, "PostLoginDeviceCheckFeature.LOCATION_GUIDANCE")
        assertContains(
            body,
            "if (!isWalkSessionRuntimeActive() || !isStartupCapabilityConfirmed()) return false",
        )
        assertContains(body, "val guard = officialEnvironmentRuntimeGuard ?: return false")
        assertContains(body, "return walkSessionLifecycle.isRuntimeEpochCurrent(guard.epoch)")
    }

    @Test
    fun locationRegistrationObservesAsynchronousFailureWithoutRemovingIdempotence() {
        val body = mainFunction("startLocationUpdatesIfAllowed")
        assertContains(body, "if (!forceRestart && locationCallback != null) return")
        assertInOrder(
            body,
            "fusedLocationClient.requestLocationUpdates(",
            ".addOnFailureListener",
            "handleRuntimeLocationRequestFailure(",
        )
    }

    @Test
    fun registrationFailureRejectsOldRequestsBeforeClearingTheCurrentLease() {
        val body = mainFunction("handleRuntimeLocationRequestFailure")
        val stopIndex = body.indexOf("stopLocationUpdates()")
        assertTrue("A failed registration must release its callback slot", stopIndex >= 0)
        val fence = compact(body.substring(0, stopIndex))
        val delegatedFence = Regex("isLocationCallbackCurrent\\(([^)]*)\\)")
            .find(fence)
        if (delegatedFence != null) {
            val arguments = delegatedFence.groupValues[1]
            assertTrue(arguments.contains("walkEpoch"))
            assertTrue(arguments.contains("generation"))
            assertTrue(arguments.contains("callback"))
            assertTrue("A stale callback must return before cleanup", fence.contains("return"))
            val currentness = mainFunction("isLocationCallbackCurrent")
            assertContains(currentness, "locationCallback === callback")
            assertContains(currentness, "generation == locationCallbackGeneration")
            assertContains(currentness, "walkSessionLifecycle.isRuntimeEpochCurrent(walkEpoch)")
        } else {
            assertTrue("Cleanup must reject a different callback", fence.contains("locationCallback!==callback"))
            assertTrue(
                "Cleanup must reject a different callback generation",
                fence.contains("generation!=locationCallbackGeneration") ||
                    fence.contains("locationCallbackGeneration!=generation"),
            )
            assertTrue(
                "Cleanup must reject an old walk epoch",
                fence.contains("!walkSessionLifecycle.isRuntimeEpochCurrent(walkEpoch)"),
            )
            assertTrue("A stale callback must return before cleanup", fence.contains("return"))
        }
        assertInOrder(
            body,
            "stopLocationUpdates()",
            "recordOfficialEnvironmentGpsObservation(",
            "handlePositioningUnavailable(",
            "updateNavigationStatus(",
            "navigation=gps_request_failed",
            "pauseDirectionGuidance(",
        )
        assertContains(body, "trustedFixAvailable = false")
        assertContains(body, "horizontalAccuracyMeters = null")
        assertContains(mainFunction("stopLocationUpdates"), "clearTrustedLocation()")
    }

    @Test
    fun activeGuidanceRetryRestartsNavigationServicesBeforeSelectingTheDestination() {
        assertInOrder(
            mainFunction("startNativeDestinationGuidance"),
            "startNavigationServicesIfNeeded()",
            "onDestinationSelected(",
        )
    }

    private fun mainFunction(name: String): String {
        val start = Regex("(?m)^    private fun ${Regex.escape(name)}\\s*\\(")
            .find(mainSource)
        requireNotNull(start) { "Missing MainActivity function: $name" }
        val next = Regex(
            "(?m)^    (?:(?:private|protected|internal|public|override|suspend|inline)\\s+)*fun\\s+",
        ).find(mainSource, start.range.last + 1)
        return mainSource.substring(start.range.first, next?.range?.first ?: mainSource.length)
    }

    private fun assertContains(source: String, expected: String) {
        assertTrue("Missing source contract: $expected", compact(source).contains(compact(expected)))
    }

    private fun assertInOrder(source: String, vararg markers: String) {
        val normalized = compact(source)
        var cursor = 0
        for (marker in markers) {
            val expected = compact(marker)
            val index = normalized.indexOf(expected, cursor)
            assertTrue("Missing or out-of-order source contract: $marker", index >= 0)
            cursor = index + expected.length
        }
    }

    private fun compact(value: String): String = value.replace(Regex("\\s+"), "")

    companion object {
        private val mainSource: String by lazy {
            val relative = "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"
            val source = listOf(
                File(relative),
                File("app/$relative"),
                File("apps/android/app/$relative"),
            ).firstOrNull(File::isFile)
            requireNotNull(source) { "MainActivity source was not found from the test working directory" }
            source.readText()
        }
    }
}
