package kr.co.hanium.dreamup.walksafe

import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteSummary
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityNavigationCompositionTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()

    @Test
    fun offRouteAndArrivalCandidatesNeverTriggerAutomaticNetworkOrShutdown() {
        val guidance = functionBlock("private fun updateRouteGuidance(")

        assertFalse(guidance.contains("requestRoute("))
        assertFalse(
            Regex("""if\s*\(update\.arrived\)\s*\{[^}]*isRouteActive\s*=\s*false""")
                .containsMatchIn(guidance),
        )
    }

    @Test
    fun trustedGpsRecoveryDoesNotAutomaticallyRequestANewRoute() {
        val locationUpdate = functionBlock("private fun handleLocationUpdate(")

        assertFalse(locationUpdate.contains("requestRoute("))
    }

    @Test
    fun routeGuidanceUsesStrideOnlyAsAuxiliaryProgressEvidence() {
        val guidance = functionBlock("private fun updateRouteGuidance(")

        assertTrue(guidance.contains("stepProgressM = routeStepProgressMOrNull()"))
        assertFalse(guidance.contains("latitude = step"))
        assertFalse(guidance.contains("longitude = step"))
    }

    @Test
    fun voicePagingIsThreeAtATimeAndOnlySelectsFromTheCurrentPage() {
        val hearMore = functionBlock("private fun hearMoreVoiceDestinationCandidates(")
        val selection = functionBlock("private fun selectVoiceDestinationCandidate(")

        assertTrue(source.contains("const val DESTINATION_SEARCH_PAGE_SIZE = 3"))
        assertTrue(hearMore.contains("DestinationSearchVoiceCommand.HearMore"))
        assertTrue(hearMore.contains("performDestinationSearch(reset = false)"))
        assertTrue(selection.contains("DestinationSearchVoiceCommand.SelectCandidate(oneBasedIndex)"))
    }

    @Test
    fun voiceDecisionCommandsAreBoundToTheRouteRevisionAtRecognitionStart() {
        val recognitionStart = functionBlock("private fun startVoiceCommandRecognition(")
        val commandHandler = functionBlock("private fun handleVoiceCommandPhrases(")

        assertTrue(recognitionStart.contains("expectedNavigationDecisionToken"))
        assertTrue(commandHandler.contains("routeNavigator.pendingDecisionToken()"))
        assertTrue(commandHandler.contains("voice=navigation_decision_stale"))
    }

    @Test
    fun rejectingArrivalDoesNotRestoreTmapTrustWithoutFreshGpsEvidence() {
        val rejection = functionBlock("private fun rejectArrivalFromVoice(")

        assertFalse(rejection.contains("latestTmapOnRoute = true"))
    }

    @Test
    fun androidSourceDoesNotContainATmapProviderKey() {
        val androidMain = File("src/main").walkTopDown()
            .filter(File::isFile)
            .filter { it.extension in setOf("kt", "java", "xml") }
            .joinToString("\n") { it.readText() }

        assertFalse(Regex("(?i)tmap[_-]?app[_-]?key|\\\"appKey\\\"").containsMatchIn(androidMain))
    }

    @Test
    fun destinationSearchPreservesRequestsUntilActualCandidateSelectionCancelsBoth() {
        val activity = MainActivity()
        val routeCancels = AtomicInteger()
        val searchCancels = AtomicInteger()
        val route = activity.trackRouteRequest(recordingCall(routeCancels))
        val search = activity.trackDestinationSearchRequest(recordingCall(searchCancels))

        assertFalse(route.isCancelled())
        assertFalse(search.isCancelled())

        setReporterUserId(activity)
        val entryFailure = invokeEntry(
            activity = activity,
            methodName = "onDestinationSelected",
            parameterTypes = arrayOf(DestinationSearchResult::class.java),
            arguments = arrayOf(
                DestinationSearchResult(
                    id = "destination-1",
                    name = "서울역",
                    point = RoutePoint(37.5547, 126.9707, "서울역"),
                    address = null,
                    roadAddress = null,
                    category = null,
                    distanceM = null,
                ),
            ),
        )

        assertTrue("selection entry failed before cancellation: $entryFailure", route.isCancelled())
        assertTrue("selection entry failed before cancellation: $entryFailure", search.isCancelled())
        assertEquals(1, routeCancels.get())
        assertEquals(1, searchCancels.get())
    }

    @Test
    fun actualPauseAndDestroyEntriesEachCancelTheMainActivityActiveRouteAndSearchCalls() {
        listOf("onPause", "onDestroy").forEach { lifecycleMethod ->
            val activity = MainActivity()
            setBooleanField(activity, "privacyStartupInspectionComplete", true)
            val route = activity.trackRouteRequest(recordingCall(AtomicInteger()))
            val search = activity.trackDestinationSearchRequest(recordingCall(AtomicInteger()))

            val entryFailure = invokeEntry(activity, lifecycleMethod)

            assertTrue("$lifecycleMethod failed before cancellation: $entryFailure", route.isCancelled())
            assertTrue("$lifecycleMethod failed before cancellation: $entryFailure", search.isCancelled())
        }
    }

    @Test
    fun lateOldCompletionCannotUnregisterTheReplacementRouteCall() {
        val activity = MainActivity()
        val oldRoute = activity.trackRouteRequest(recordingCall(AtomicInteger()))
        activity.cancelNavigationRequestsForDestinationSelection()
        val replacement = activity.trackRouteRequest(recordingCall(AtomicInteger()))

        activity.completeRouteRequest(oldRoute)
        val paused = activity.cancelNavigationRequestsForPause()

        assertTrue(paused.routeCancelled)
        assertTrue(replacement.isCancelled())
    }

    @Test
    fun lateOldCompletionCannotUnregisterTheReplacementDestinationSearchCall() {
        val activity = MainActivity()
        val oldSearch = activity.trackDestinationSearchRequest(recordingCall(AtomicInteger()))
        activity.cancelNavigationRequestsForDestinationSelection()
        val replacement = activity.trackDestinationSearchRequest(recordingCall(AtomicInteger()))

        activity.completeDestinationSearchRequest(oldSearch)
        val destroyed = activity.cancelNavigationRequestsForDestroy()

        assertTrue(destroyed.destinationSearchCancelled)
        assertTrue(replacement.isCancelled())
    }

    @Test
    fun pauseCancelsRerouteAndRequiresFreshRouteWhileRetainingDestinationProposal() {
        val activity = MainActivity()
        val destination = RoutePoint(37.001, 127.0, "목적지")
        val navigator = field(activity, "routeNavigator") as RouteNavigator
        navigator.setRoute(
            WalkingRoute(
                priority = "STAIR_AVOID",
                summary = WalkingRouteSummary(distanceM = 120, durationS = 100),
                polyline = listOf(RoutePoint(37.0, 127.0), destination),
                guidePoints = emptyList(),
            ),
            destination = destination,
        )
        setBooleanField(activity, "isRouteActive", true)
        MainActivity::class.java.getDeclaredField("currentDestination").apply {
            isAccessible = true
            set(activity, destination)
        }
        (field(activity, "routeRequestInFlight") as AtomicBoolean).set(true)
        val rerouteCall = activity.trackRouteRequest(recordingCall(AtomicInteger()))
        setBooleanField(activity, "privacyStartupInspectionComplete", true)

        invokeEntry(activity, "onPause")

        assertTrue(rerouteCall.isCancelled())
        assertFalse(MainActivity::class.java.getDeclaredField("isRouteActive").run {
            isAccessible = true
            getBoolean(activity)
        })
        assertFalse(navigator.hasRoute())
        assertEquals(destination, field(activity, "currentDestination"))
        assertFalse((field(activity, "routeRequestInFlight") as AtomicBoolean).get())
    }

    private fun recordingCall(cancelCount: AtomicInteger): CancellableNetworkCall<Unit> {
        return CancellableNetworkCall(
            executeBlock = {},
            cancelBlock = { cancelCount.incrementAndGet() },
        )
    }

    private fun setReporterUserId(activity: MainActivity) {
        MainActivity::class.java.getDeclaredField("reporterUserId").apply {
            isAccessible = true
            set(activity, "test-user")
        }
    }

    private fun field(activity: MainActivity, name: String): Any? {
        return MainActivity::class.java.getDeclaredField(name).run {
            isAccessible = true
            get(activity)
        }
    }

    private fun setBooleanField(activity: MainActivity, name: String, value: Boolean) {
        MainActivity::class.java.getDeclaredField(name).apply {
            isAccessible = true
            setBoolean(activity, value)
        }
    }

    private fun invokeEntry(
        activity: MainActivity,
        methodName: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        arguments: Array<Any> = emptyArray(),
    ): Throwable? {
        return runCatching {
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, methodName, MethodType.methodType(Void.TYPE, parameterTypes.toList()))
                .bindTo(activity)
                .invokeWithArguments(arguments.toList())
        }.exceptionOrNull()
    }

    private fun functionBlock(marker: String): String {
        val markerIndex = source.indexOf(marker)
        require(markerIndex >= 0) { "missing function marker: $marker" }
        val openingBrace = source.indexOf('{', markerIndex)
        require(openingBrace >= 0) { "missing opening brace: $marker" }
        var depth = 0
        for (index in openingBrace until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(markerIndex, index + 1)
                }
            }
        }
        error("missing closing brace: $marker")
    }
}
