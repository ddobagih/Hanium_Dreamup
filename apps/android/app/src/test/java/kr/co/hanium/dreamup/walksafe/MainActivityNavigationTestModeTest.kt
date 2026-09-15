package kr.co.hanium.dreamup.walksafe

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityTier
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupRequirement
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteSummary
import kr.co.hanium.dreamup.walksafe.navigation.positioning.GnssPositionObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositionQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningCoordinator
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PositioningSnapshot
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

class MainActivityNavigationTestModeTest {
    @Before
    fun requireNavigationTestBuild() {
        assumeTrue(BuildConfig.DEBUG && BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
    }

    @Test
    fun testNavigationDoesNotLetAnOldCapabilityFailureVetoTheRealVoiceEngine() {
        val activity = MainActivity()
        val unavailable = WalkSafeStartupCapabilityDecision(
            tier = WalkSafeStartupCapabilityTier.BLOCKED,
            unavailableRequirements = listOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS),
            pendingRequirements = emptyList(),
            noticeKo = "이전 음성 점검 실패",
        )
        setField(activity, "startupCapabilityDecision", unavailable)
        assertFalse(booleanResult(activity, "navigationSpeechCapabilityAllowed"))
        setBooleanField(activity, "developmentGuidanceStartRequested", true)
        assertTrue(booleanResult(activity, "navigationSpeechCapabilityAllowed"))
        assertTrue(field(activity, "startupCapabilityDecision") === unavailable)
        assertFalse(booleanResult(activity, "isWalkSessionRuntimeActive"))
    }

    @Test
    fun stoppedRouteCancelsItsPendingLocationStatusResponse() {
        val activity = MainActivity()
        setField(activity, "commandSpeechResponseGeneration", 7L)
        setField(activity, "routeLocationStatusResponseGeneration", 7L)
        setBooleanField(activity, "voiceCommandPromptPending", true)

        invoke(activity, "cancelRouteLocationStatusSpeech", Void.TYPE)

        assertEquals(8L, field(activity, "commandSpeechResponseGeneration"))
        assertNull(field(activity, "routeLocationStatusResponseGeneration"))
        assertFalse(field(activity, "voiceCommandPromptPending") as Boolean)
        invoke(activity, "cancelRouteLocationStatusSpeech", Void.TYPE)
        assertEquals(8L, field(activity, "commandSpeechResponseGeneration"))
    }

    @Test
    fun stoppedRouteDoesNotCancelANewerIndependentCommandResponse() {
        val activity = MainActivity()
        setField(activity, "commandSpeechResponseGeneration", 8L)
        setField(activity, "routeLocationStatusResponseGeneration", 7L)
        setBooleanField(activity, "voiceCommandPromptPending", true)

        invoke(activity, "cancelRouteLocationStatusSpeech", Void.TYPE)

        assertEquals(8L, field(activity, "commandSpeechResponseGeneration"))
        assertNull(field(activity, "routeLocationStatusResponseGeneration"))
        assertTrue(field(activity, "voiceCommandPromptPending") as Boolean)
    }

    @Test
    fun freshActivityCanReadBypassAndOnboardingWithoutRecursiveStartupFailure() {
        val activity = MainActivity()

        assertFalse(booleanResult(activity, "getDevelopmentGuidanceStartBypassEnabled"))
        assertFalse(booleanResult(activity, "firstRunOnboardingComplete"))
        assertFalse(booleanResult(activity, "getDevelopmentGuidanceStartBypassEnabled"))
    }

    @Test
    fun explicitTestStartAllowsRouteMutationEvenWithPendingLocationDecision() {
        val activity = MainActivity()
        val navigator = RouteNavigator()
        val destination = RoutePoint(37.001, 127.0, "목적지")
        navigator.setRoute(
            WalkingRoute(
                priority = "STAIR_AVOID",
                summary = WalkingRouteSummary(distanceM = 120, durationS = 100),
                polyline = listOf(RoutePoint(37.0, 127.0), destination),
                guidePoints = emptyList(),
            ),
            destination,
        )
        navigator.onUntrustedLocation()
        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, navigator.pendingUserDecision())
        setField(activity, "routeNavigator", navigator)
        setBooleanField(activity, "developmentGuidanceStartRequested", true)

        assertFalse(booleanResult(activity, "blockRouteMutationWhileDeviationChoicePending"))

        assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, navigator.pendingUserDecision())
        assertTrue(navigator.hasRoute())
    }

    @Test
    fun lowAndUnavailableEstimatesKeepTheirRealQualityWithoutPausingTestGuidance() {
        for ((accuracyM, expectedQuality) in listOf(10.0 to PositionQuality.LOW, 30.0 to PositionQuality.UNAVAILABLE)) {
            val activity = MainActivity()
            setBooleanField(activity, "developmentGuidanceStartRequested", true)
            setBooleanField(activity, "positionGuidancePaused", true)
            val snapshot = PositioningCoordinator().observeGnss(
                GnssPositionObservation(
                    latitude = 37.0,
                    longitude = 127.0,
                    horizontalAccuracyM = accuracyM,
                    elapsedRealtimeMs = 1_000L,
                ),
            )
            assertEquals(expectedQuality, snapshot.quality)
            assertEquals(expectedQuality, snapshot.confidence.quality)
            assertTrue(snapshot.confidence.guidancePaused)

            val interrupted = invoke(
                activity,
                "applyPositionConfidenceDecision",
                Boolean::class.javaPrimitiveType!!,
                listOf(PositioningSnapshot::class.java),
                listOf(snapshot),
            ) as Boolean

            assertFalse(interrupted)
            assertFalse(field(activity, "positionGuidancePaused") as Boolean)
            assertEquals(expectedQuality, snapshot.quality)
            assertEquals(expectedQuality, snapshot.confidence.quality)
            assertTrue(snapshot.confidence.guidancePaused)
            assertEquals(accuracyM, snapshot.raw?.reportedHorizontalAccuracyM)
        }
    }

    @Test
    fun explicitTestStartCannotSupplyMissingAuthenticationOrWalkAuthority() {
        val activity = MainActivity()
        setBooleanField(activity, "developmentGuidanceStartRequested", true)
        val unboundSession = GatewayFieldSession.longLivedSession(
            gatewayBaseUrl = "https://gateway.example.test",
            actorId = "actor-test",
            deviceId = "device-0001",
            familyId = "f".repeat(32),
            rotation = 1L,
            cookiePair = "${GatewayFieldSession.COOKIE_NAME}=${"a".repeat(32)}",
            refreshToken = "r".repeat(64),
            accessExpiresAtEpochMs = Long.MAX_VALUE,
            idleExpiresAtEpochMs = Long.MAX_VALUE,
            absoluteExpiresAtEpochMs = Long.MAX_VALUE,
        )
        assertTrue(unboundSession.isUsableFor("actor-test"))

        assertTrue(booleanResult(activity, "getDevelopmentGuidanceStartBypassEnabled"))
        assertFalse(booleanResult(activity, "firstRunOnboardingComplete"))
        assertFalse(
            invoke(
                activity,
                "isGatewaySessionReadyForCurrentActor",
                Boolean::class.javaPrimitiveType!!,
                listOf(GatewayFieldSession::class.java),
                listOf(unboundSession),
            ) as Boolean,
        )
        assertFalse(booleanResult(activity, "isWalkSessionRuntimeActive"))
    }

    private fun booleanResult(activity: MainActivity, name: String): Boolean =
        invoke(activity, name, Boolean::class.javaPrimitiveType!!) as Boolean

    private fun field(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(activity)
        }

    private fun setField(activity: MainActivity, name: String, value: Any) {
        MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(activity, value)
        }
    }

    private fun setBooleanField(activity: MainActivity, name: String, value: Boolean) {
        MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
            setBoolean(activity, value)
        }
    }

    private fun invoke(
        activity: MainActivity,
        name: String,
        returnType: Class<*>,
        parameterTypes: List<Class<*>> = emptyList(),
        arguments: List<Any> = emptyList(),
    ): Any? = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
        .findVirtual(MainActivity::class.java, name, MethodType.methodType(returnType, parameterTypes))
        .bindTo(activity)
        .invokeWithArguments(arguments)
}
