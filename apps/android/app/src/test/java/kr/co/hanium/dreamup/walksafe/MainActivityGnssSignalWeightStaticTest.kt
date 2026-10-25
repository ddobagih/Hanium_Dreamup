package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityGnssSignalWeightStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun signalWeightIsPassedSeparatelyFromTheReportedAccuracy() {
        val handler = NavigationPolicySource.observation(source)
            .substringBefore("expectedGeneration = binding.inputGeneration,")
        val observation = handler.substringAfter("GnssPositionObservation(")
        assertTrue(handler.contains("val accuracy = location.horizontalAccuracyM?.toFloat()"))
        assertTrue(observation.contains("horizontalAccuracyM = accuracy?.toDouble(),"))
        assertTrue(observation.contains("measurementNoiseMultiplier = measurementNoiseMultiplier,"))
        assertFalse(observation.contains("horizontalAccuracyM = effectiveAccuracyM"))
    }
    @Test fun observationRejectsIndirectMainDelegation() {
        NavigationPolicySource.observation(source)
        rejectsMaskedCalls(source,
            "positioningSessionController.handleLocationUpdate(location, owner, locationGeneration, binding, receivedAtElapsedRealtimeMs)") {
            NavigationPolicySource.observation(it)
        }
    }

    @Test fun observationRejectsIndirectSdkPolicyDispatch() {
        val owner = read("navigation/session/PositioningSessionController.kt")
        NavigationPolicySource.assertObservationSdkBoundary(owner)
        val call = """|handleLocationObservation(
            |            observation, owner, locationGeneration, binding,
            |            updateOrientationReference = { updateRouteOrientationReference(location) },
            |            appendGnssTrace = { snapshot, matchedEvidenceCurrent ->
            |                effects.appendGnssTrace(snapshot, location, matchedEvidenceCurrent)
            |            },
            |        )""".trimMargin()
        rejectsMaskedCalls(owner, call) {
            NavigationPolicySource.assertObservationSdkBoundary(it)
        }
    }

    @Test fun sdkLineCommentCannotHideTheOwnershipGuard() {
        val owner = read("navigation/session/PositioningSessionController.kt")
        NavigationPolicySource.assertObservationSdkBoundary(owner)
        val guard = "        if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return"
        NavigationPolicySource.assertObservationSdkBoundary(owner.replace(guard, "    $guard"))
        val comment = "// Reject a retired callback before reading any SDK values."
        assertTrue(owner.contains("$comment\n$guard"))
        rejected("line comment swallowed the ownership guard") {
            NavigationPolicySource.assertObservationSdkBoundary(owner.replace("$comment\n$guard", "$comment $guard"))
        }
    }

    @Test fun headingAndCalibrationRejectIndirectSdkPolicyDispatch() {
        val owner = read("navigation/session/PositioningSessionController.kt")
        NavigationPolicySource.assertHeadingSdkBoundary(owner)
        rejectsMaskedCalls(owner,
            "updatePositioningHeadingInputs(location.gnssSampleFingerprint(sources.mock(location)),\n            elapsedRealtimeMs, binding, receivedAtElapsedRealtimeMs)") {
            NavigationPolicySource.assertHeadingSdkBoundary(it)
        }
        NavigationPolicySource.assertCalibrationSdkBoundary(owner)
        rejectsMaskedCalls(owner,
            "attemptStepCalibration(location.gnssSampleFingerprint(sources.mock(location)), snapshot, receivedAtElapsedRealtimeMs)") {
            NavigationPolicySource.assertCalibrationSdkBoundary(it)
        }
    }

    @Test fun guidanceRejectsIndirectDispatchAndChangedEffectOrder() {
        NavigationPolicySource.guidance(source)
        val call = "routeGuidanceController.updateRouteGuidance(location, rawLocation)"
        rejectsMaskedCalls(source, call) { NavigationPolicySource.guidance(it) }
        rejected("reordered Main effects") {
            NavigationPolicySource.guidance(source.replace(
                "$call\n        recordDirectionGraceDiagnostic()",
                "recordDirectionGraceDiagnostic()\n        $call"))
        }
    }

    @Test fun locationRecheckRejectsIndirectMainCallerAndPeerDispatch() {
        NavigationPolicySource.locationRecheck(source)
        rejectsMaskedCalls(source,
            "voiceInteractionControllers.commands.recheckLocationFromVoice(expectedToken)") {
            NavigationPolicySource.locationRecheck(it)
        }
        val commands = read("voice/interaction/VoiceCommandInteractionController.kt")
        val caller = MainActivityRefactorSource.member(commands, "recheckLocationFromVoice", 1)
        NavigationPolicySource.assertRecheckCallerBoundary(caller)
        rejectsMaskedCalls(caller, "route.requestLocationRecheck(expectedToken)", booleanResult = true) {
            NavigationPolicySource.assertRecheckCallerBoundary(it)
        }
        val peer = read("voice/composition/VoiceControllerPeerAdapters.kt")
        NavigationPolicySource.assertRecheckPeerBoundary(peer)
        rejectsMaskedCalls(peer,
            "guidance().requestLocationRecheck(expectedToken, effects::startNavigationServicesIfNeeded)",
            booleanResult = true) { NavigationPolicySource.assertRecheckPeerBoundary(it) }
    }

    private fun read(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/" + path).readText()

    private fun rejectsMaskedCalls(
        source: String, call: String, booleanResult: Boolean = false, inspect: (String) -> Unit,
    ) {
        assertTrue("Mutation anchor missing: $call", source.contains(call))
        val masked = if (booleanResult) listOf(
            "if (false) $call else false", "run { val neverRun = { $call }; false }", "false /* $call */",
        ) else listOf("if (false) { $call } else Unit", "run { val neverRun = { $call }; Unit }", "Unit /* $call */")
        masked.forEach { replacement -> rejected(replacement) { inspect(source.replace(call, replacement)) } }
    }

    private fun rejected(label: String, inspect: () -> Unit) {
        var accepted = true
        try { inspect() } catch (_: AssertionError) { accepted = false }
        assertFalse("Disconnected boundary was accepted: $label", accepted)
    }

}

/** Explicit structural checks of each boundary; these are not SDK or runtime behavior tests. */
internal object NavigationPolicySource {
    private const val ROOT = "src/main/java/kr/co/hanium/dreamup/walksafe/"
    private val positioning get() = File(ROOT + "navigation/session/PositioningSessionController.kt").readText()

    fun observation(main: String): String {
        MainActivityRefactorSource.composition(main, "positioningSessionController")
        val sdk = MainActivityRefactorSource.member(positioning, "handleLocationUpdate")
        // Resolve only a real direct Main call; guarded/dead/comment-only calls remain caller text.
        assertEquals(sdk, MainActivityRefactorSource.function(main, "private fun handleLocationUpdate("))
        assertObservationSdkBoundary(positioning)
        assertTrue(ReportStaticSourceInspector.appearsInOrder(sdk,
            "if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return",
            "val observation = location.toPositioningObservation(receivedAtElapsedRealtimeMs, sources.mock(location))",
            "gnssQualityObserver?.snapshot(atNanos)",
            "handleLocationObservation(", "observation, owner, locationGeneration, binding,"))
        assertTrue(sdk.contains("updateOrientationReference = { updateRouteOrientationReference(location) }"))
        assertTrue(sdk.contains("effects.appendGnssTrace(snapshot, location, matchedEvidenceCurrent)"))
        val adapter = File(ROOT + "navigation/session/AndroidPositioningLocationObservation.kt").readText()
        assertTrue(ReportStaticSourceInspector.appearsInOrder(adapter,
            "val original = gnssSampleFingerprint(mock)", "sample = original",
            "receivedAtElapsedRealtimeMs = receivedAtElapsedRealtimeMs",
            "signalQuality = signalQualityAt(original.elapsedRealtimeNanos)"))
        assertFalse(adapter.contains("SystemClock"))
        val observationValue = File(ROOT + "navigation/session/PositioningLocationObservation.kt").readText()
        assertTrue(observationValue.contains("val sample: GnssSampleFingerprint"))
        assertTrue(observationValue.contains("val receivedAtElapsedRealtimeMs: Long"))
        assertTrue(observationValue.contains("val signalQuality: GnssQualitySnapshot?"))
        assertFalse(Regex("\\bvar\\s+").containsMatchIn(observationValue))
        val copy = File(ROOT + "navigation/positioning/AndroidGnssSampleFingerprint.kt").readText()
        for (field in listOf("elapsedRealtimeNanos = elapsedRealtimeNanos", "latitude = latitude",
            "longitude = longitude", "horizontalAccuracyM = if (hasAccuracy()) accuracy.toDouble() else null",
            "mock = mock", "speedMps = if (hasSpeed()) speed.toDouble() else null",
            "speedAccuracyMps = if (hasSpeedAccuracy()) speedAccuracyMetersPerSecond.toDouble() else null",
            "bearingDegrees = if (hasBearing()) bearing.toDouble() else null",
            "bearingAccuracyDegrees = if (hasBearingAccuracy()) bearingAccuracyDegrees.toDouble() else null")) {
            assertTrue(field, copy.contains(field))
        }
        assertFalse(copy.contains("SystemClock"))
        val guard = MainActivityRefactorSource.member(positioning, "isLocationObservationCurrent")
        assertTrue(guard.contains("locationCollectionOwner == owner && scope.currentOwner() == owner &&"))
        assertTrue(guard.contains("locationGeneration == locationCallbackGeneration && isPositioningCollectionCurrent(binding) &&"))
        assertTrue(guard.contains("scope.currentOwner() != null"))
        val policy = MainActivityRefactorSource.member(positioning, "handleLocationObservation")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(policy,
            "if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return",
            "val location = observation.sample",
            "val receivedAtElapsedRealtimeMs = observation.receivedAtElapsedRealtimeMs",
            "val elapsedMs = Math.floorDiv(location.elapsedRealtimeNanos, 1_000_000L)",
            "val currentGnssQualitySnapshot = observation.signalQuality",
            "binding.coordinator.observeGnss("))
        return policy
    }

    fun heading(main: String): String {
        val policy = observation(main)
        assertTrue(policy.contains("updatePositioningHeadingInputs(location, elapsedMs, binding, receivedAtElapsedRealtimeMs)"))
        assertHeadingSdkBoundary(positioning)
        val sdk = MainActivityRefactorSource.member(positioning, "updatePositioningHeadingInputs")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(sdk,
            "if (!isPositioningCollectionCurrent(binding)) return",
            "if (!binding.coordinator.isUserMotionInputCurrent(elapsedRealtimeMs, motionOwner)) return",
            "updatePositioningHeadingInputs(location.gnssSampleFingerprint(sources.mock(location)),"))
        assertTrue(sdk.contains("elapsedRealtimeMs, binding, receivedAtElapsedRealtimeMs)"))
        return MainActivityRefactorSource.member(positioning, "updatePositioningHeadingInputs", 1).also {
            assertTrue(it.contains("location: GnssSampleFingerprint"))
        }
    }

    fun calibration(main: String): String {
        val policy = observation(main)
        assertTrue(policy.contains("attemptStepCalibration(location, positioningSnapshot, receivedAtElapsedRealtimeMs)"))
        assertCalibrationSdkBoundary(positioning)
        val sdk = MainActivityRefactorSource.member(positioning, "attemptStepCalibration")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(sdk,
            "if (latestTrustedLocation?.navigationOnly == true) return",
            "attemptStepCalibration(location.gnssSampleFingerprint(sources.mock(location)), snapshot, receivedAtElapsedRealtimeMs)"))
        return MainActivityRefactorSource.member(positioning, "attemptStepCalibration", 1).also {
            assertTrue(it.contains("location: GnssSampleFingerprint"))
            assertTrue(it.contains("if (latestTrustedLocation?.navigationOnly == true) return"))
        }
    }

    fun guidance(main: String): String {
        MainActivityRefactorSource.composition(main, "routeGuidanceController")
        assertGuidanceMainBoundary(main)
        val wrapper = MainActivityRefactorSource.member(main, "updateRouteGuidance")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(wrapper,
            "routeGuidanceController.updateRouteGuidance(location, rawLocation)",
            "recordDirectionGraceDiagnostic()"))
        return MainActivityRefactorSource.member(
            File(ROOT + "navigation/session/RouteGuidanceController.kt").readText(), "updateRouteGuidance")
    }
    fun locationRecheck(main: String): String {
        MainActivityRefactorSource.composition(main, "voiceInteractionControllers.commands")
        MainActivityRefactorSource.composition(main, "routeGuidanceController")
        val commands = File(ROOT + "voice/interaction/VoiceCommandInteractionController.kt").readText()
        val caller = MainActivityRefactorSource.member(commands, "recheckLocationFromVoice", 1)
        assertEquals(caller, MainActivityRefactorSource.function(main,
            "private fun recheckLocationFromVoice(expectedToken: RouteNavigatorDecisionToken?)"))
        assertRecheckCallerBoundary(caller)
        assertTrue(ReportStaticSourceInspector.appearsInOrder(caller,
            "if (!route.navigationCollectionAllowsWork())", "return",
            "if (!route.requestLocationRecheck(expectedToken))", "explainRouteCommandUnavailable()"))
        val binding = MainActivityRefactorSource.factory(main, "createVoiceInteractionControllers")
        assertTrue(binding.contains("route = VoiceRoutePeerAdapter("))
        assertTrue(binding.contains("guidance = { routeGuidanceController }"))
        val adapter = File(ROOT + "voice/composition/VoiceControllerPeerAdapters.kt").readText()
        assertRecheckPeerBoundary(adapter)
        assertTrue(MainActivityRefactorSource.member(adapter, "requestLocationRecheck").contains(
            "guidance().requestLocationRecheck(expectedToken, effects::startNavigationServicesIfNeeded)"))
        return MainActivityRefactorSource.member(
            File(ROOT + "navigation/session/RouteGuidanceController.kt").readText(), "requestLocationRecheck")
    }

    // Exact boundary declarations are intentional: mixed effects cannot be flattened into a delegate.
    private fun assertDeclaration(actual: String, expected: String) {
        // Newlines terminate // comments and must remain part of the declaration contract.
        fun lines(text: String) = text.trim().lineSequence().joinToString("\n") { it.trim() }
        assertEquals(lines(expected), lines(actual))
    }

    fun assertObservationSdkBoundary(source: String) {
        assertDeclaration(MainActivityRefactorSource.factory(source, "handleLocationUpdate"), """
            fun handleLocationUpdate(
                    location: Location,
                    owner: LocationCollectionOwner,
                    locationGeneration: Int,
                    binding: PositioningCollectionBinding,
                    receivedAtElapsedRealtimeMs: Long,
                ) {
                    // Reject a retired callback before reading any SDK values.
                    if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return
                    val observation = location.toPositioningObservation(receivedAtElapsedRealtimeMs, sources.mock(location)) { atNanos ->
                        runCatching { gnssQualityObserver?.snapshot(atNanos) }.getOrNull()
                    }
                    handleLocationObservation(
                        observation, owner, locationGeneration, binding,
                        updateOrientationReference = { updateRouteOrientationReference(location) },
                        appendGnssTrace = { snapshot, matchedEvidenceCurrent ->
                            effects.appendGnssTrace(snapshot, location, matchedEvidenceCurrent)
                        },
                    )
                }
        """.trimIndent())
    }

    fun assertHeadingSdkBoundary(source: String) {
        assertDeclaration(MainActivityRefactorSource.factory(source, "updatePositioningHeadingInputs"), """
            fun updatePositioningHeadingInputs(
                    location: Location,
                    elapsedRealtimeMs: Long,
                    binding: PositioningCollectionBinding,
                    receivedAtElapsedRealtimeMs: Long,
                ) {
                    if (!isPositioningCollectionCurrent(binding)) return
                    val motionOwner = binding.coordinator.currentUserMotionOwner() ?: return
                    if (!binding.coordinator.isUserMotionInputCurrent(elapsedRealtimeMs, motionOwner)) return
                    if (latestGpsMotionInputs?.evidence?.observedAtElapsedRealtimeMs
                            ?.let { elapsedRealtimeMs <= it } == true
                    ) return
                    updatePositioningHeadingInputs(location.gnssSampleFingerprint(sources.mock(location)),
                        elapsedRealtimeMs, binding, receivedAtElapsedRealtimeMs)
                }
        """.trimIndent())
    }

    fun assertCalibrationSdkBoundary(source: String) {
        assertDeclaration(MainActivityRefactorSource.factory(source, "attemptStepCalibration"), """
            fun attemptStepCalibration(
                    location: Location,
                    snapshot: PositioningSnapshot,
                    receivedAtElapsedRealtimeMs: Long,
                ) {
                    if (latestTrustedLocation?.navigationOnly == true) return
                    attemptStepCalibration(location.gnssSampleFingerprint(sources.mock(location)), snapshot, receivedAtElapsedRealtimeMs)
                }
        """.trimIndent())
    }

    fun assertGuidanceMainBoundary(source: String) {
        assertDeclaration(MainActivityRefactorSource.factory(source, "updateRouteGuidance"), """
            private fun updateRouteGuidance(
                    location: TrustedLocation,
                    rawLocation: TrustedLocation = location,
                ) {
                    routeGuidanceController.updateRouteGuidance(location, rawLocation)
                    recordDirectionGraceDiagnostic()
                }
        """.trimIndent())
    }

    fun assertRecheckCallerBoundary(source: String) {
        assertDeclaration(MainActivityRefactorSource.factory(source, "recheckLocationFromVoice"), """
            fun recheckLocationFromVoice(expectedToken: RouteNavigatorDecisionToken?) {
                    if (!route.navigationCollectionAllowsWork()) {
                        route.explainNavigationFeatureUnavailable()
                        return
                    }
                    if (!route.requestLocationRecheck(expectedToken)) {
                        explainRouteCommandUnavailable()
                    }
                }
        """.trimIndent())
    }

    fun assertRecheckPeerBoundary(source: String) {
        assertDeclaration(MainActivityRefactorSource.factory(source, "requestLocationRecheck"), """
            override fun requestLocationRecheck(expectedToken: RouteNavigatorDecisionToken?) =
                    guidance().requestLocationRecheck(expectedToken, effects::startNavigationServicesIfNeeded)
        """.trimIndent())
    }

}
