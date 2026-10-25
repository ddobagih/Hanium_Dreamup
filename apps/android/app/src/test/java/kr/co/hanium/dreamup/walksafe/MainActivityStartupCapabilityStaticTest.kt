package kr.co.hanium.dreamup.walksafe

import android.speech.SpeechRecognizer
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityStartupCapabilityStaticTest {
    private val activity = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val panel = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt",
    ).readText()
    private val presenter = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/DeviceCheckPresenter.kt",
    ).readText()
    private val probe = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidStartupCapabilityProbe.kt",
    ).readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()
    private val strings = File("src/main/res/values/strings.xml").readText()
    private val capability = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapability.kt",
    ).readText()

    @Test
    fun presentationReceivesCapturedFactsAfterTheMainCapabilityAndReadinessEffects() {
        val refresh = method("refreshStartupCapabilityUi")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            refresh,
            "evidence().record(decision)",
            "ui.persist(decision)",
            "applyRuntimeReadinessIfActive(decision)",
            "ui.priority(decision)",
            "ui.updateOfficialEnvironment()",
            "ui.render(",
            "ui.updatePrewalk(",
            "ui.updatePriority(decision)",
            "ui.updateFirstRun()",
        ))
        assertTrue(MainActivityRefactorSource.composition(activity, "startupCapabilityFactsRenderer").contains("renderAwaitingExplicitResumeControl()"))
        val registrationSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val bindRegistration = MainActivityRefactorSource.member(registrationSource, "bindRegistration")
        assertTrue(bindRegistration.contains("deviceCheck = DeviceCheckPresenter(panel, style)"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(bindRegistration,
            "deviceCheck = DeviceCheckPresenter(panel, style)", "firstRun.publishRegistrationTargets()"))
        assertTrue(activity.contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        val registrationWire = MainActivityRefactorSource.property(activity, "nativeRegistrationComposition")
        assertTrue(registrationWire.contains("by lazy(LazyThreadSafetyMode.NONE)"))
        assertTrue(registrationWire.contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication)"))
        assertTrue(MainActivityRefactorSource.property(activity, "deviceCheckPresenter").contains("nativeRegistrationComposition.devicePresenter()"))
        assertTrue(MainActivityRefactorSource.member(registrationSource, "devicePresenter").contains("= deviceCheck"))
        assertTrue(MainActivityRefactorSource.composition(activity, "startupCapabilityFactsRenderer").contains("deviceCheckPresenter.renderStartupCapability(facts)"))
        assertTrue(presenter.contains("data class StartupFacts("))
        assertTrue(presenter.contains("fun renderStartupCapability(facts: StartupFacts): Boolean"))
        listOf("startupCapabilityProbe", "walkSessionLifecycle", "transitionWalkSession(", "persistStartupCapabilityDecision(")
            .forEach { authority -> assertFalse("UI must not own $authority", presenter.contains(authority)) }
    }

    @Test
    fun launcherKeepsProductIdentityAndOnboardingPrecedesReadinessAndRuntime() {
        assertTrue(manifest.contains("android:label=\"@string/app_name\""))
        assertTrue(strings.contains("WalkSafe(워크세이프)"))
        assertTrue(capability.contains("시각장애인의 도심 보행"))
        assertTrue(capability.contains("안드로이드 보행 보조 서비스"))

        val overlay = activity.substringAfter("val overlay = LinearLayout(this).apply")
            .substringBefore("val controlsScroll = ScrollView(this).apply")
        val readiness = panel
            .substringAfter("walkReadinessControls = LinearLayout(context).apply")
            .substringBefore("fun setAccountContent(")
        val onboarding = overlay.indexOf("addView(onboardingPanel.firstRunOnboardingControls)")
        val readinessGroup = overlay.indexOf("addView(firstRunOnboardingPresenter.readinessBinding().walkReadinessControls)")
        val runtime = overlay.indexOf("addView(nativeScreenPresenter.searchBinding().runtimeControls)")
        val capability = readiness.indexOf("addView(startupCapabilityText)")
        val confirmation = readiness.indexOf("addView(startupCapabilityConfirmButton)")
        assertTrue(onboarding >= 0)
        assertTrue(onboarding < readinessGroup)
        assertTrue(readinessGroup < runtime)
        assertTrue(capability >= 0)
        assertTrue(capability < confirmation)
        assertTrue(activity.contains("addView(onboardingPanel.firstRunOnboardingControls)"))
        assertTrue(activity.contains("firstRunOnboardingPresenter.readinessBinding().walkReadinessControls"))
        assertTrue(activity.contains("firstRunOnboardingPresenter.readinessBinding().startupCapabilityText"))
        assertTrue(activity.contains("firstRunOnboardingPresenter.readinessBinding().startupCapabilityConfirmButton"))
        assertTrue(panel.contains("setOnClickListener { actions.readiness.onStartupConfirmed() }"))
        assertTrue(activity.contains("onStartupConfirmed = { handleStartupCapabilityConfirmAction() }"))
    }

    @Test
    fun capabilityProbeChecksEveryRequiredDeviceFunctionFailClosed() {
        assertTrue(probe.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S"))
        assertTrue(probe.contains("PackageManager.FEATURE_CAMERA_ANY"))
        assertTrue(probe.contains("PackageManager.FEATURE_LOCATION_GPS"))
        assertTrue(probe.contains("PackageManager.FEATURE_MICROPHONE"))
        assertTrue(probe.contains("hasVibrator() == true"))
        assertTrue(probe.contains("BundledVoskModelInstaller.bundledModelAvailable"))
        assertTrue(probe.contains("AndroidKoreanTextToSpeechSynthesisProbe"))
        assertFalse(probe.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"))
        assertFalse(probe.contains("voice.isNetworkConnectionRequired"))
        assertFalse(probe.contains("ARCORE_DEPTH_FEATURE"))
        assertTrue(probe.contains("resolveRuntimeMetricDistanceAvailability"))
        assertTrue(probe.contains("Only a live ARCore Session"))
        assertTrue(probe.contains("ApprovedDeviceProfileMatcher.match"))
        assertTrue(probe.contains("approvedDesignatedDeviceProfile = approvedDeviceProfileMatch.approved"))
        assertTrue(probe.contains("designatedDeviceProfileVersion = approvedDeviceProfileMatch.profileVersion"))
        assertTrue(activity.contains("approvedDeviceProfileRequired = false"))
        assertTrue(capability.contains("APPROVED_DEVICE_PROFILE"))
        assertTrue(capability.contains("designatedDeviceProfileVersion"))
    }

    @Test
    fun noRuntimeStartIsEnabledBeforeTheExactCapabilityDecisionIsConfirmed() {
        val permissionStart = method("ensurePermissionsThenStart")
        val depthStart = method("startDepthSession")
        val actionGate = method("applyActionButtonState")
        val automaticStart =
            method("maybeAdvanceWalkSessionAfterCapabilityCheck")

        assertTrue(permissionStart.contains("if (!requireStartupCapabilityConfirmation()) return"))
        assertTrue(depthStart.contains("if (!admission.requireStartupConfirmation()) return"))
        assertTrue(actionGate.contains("isStartupCapabilityConfirmed()"))
        assertTrue(automaticStart.contains("if (!readiness.isConfirmed())"))
        assertFalse(activity.contains("confirmedStartupCapabilityDecision = decision\n        startDepthSession()"))
    }

    @Test
    fun runtimeDistanceDowngradeRequiresLimitedDecisionBeforeExistingFallbackStarts() {
        val fallbackRequest = method("requestCameraFallbackStart")
        val arCoreStart = method("continueDepthSessionStart")

        assertTrue(fallbackRequest.contains("metric.restoreCapability(false)"))
        assertTrue(fallbackRequest.contains("readiness.refreshUi()"))
        assertTrue(
            fallbackRequest.contains(
                "if (automaticTransitionReady || admission.startupConfirmed())",
            ),
        )
        assertTrue(fallbackRequest.contains("WALKSAFE_LIMITED_DISTANCE_NOTICE_KO"))
        assertTrue(arCoreStart.contains("requestCameraFallbackStart("))
        assertFalse(arCoreStart.contains("startCameraFallbackSession("))
    }

    @Test
    fun postLoginFeatureRestrictionsCannotReopenACapabilityBlockedStart() {
        val restrictions = method("applyPostLoginDeviceFeatureRestrictions")
        val tierCondition = restrictions.substringAfter("val tier = if (")
            .substringBefore("WalkSafeStartupCapabilityTier.BLOCKED")
        assertTrue(
            tierCondition.contains(
                "WalkSafeStartupCapabilityResolver.blocksWalkStart(decision.unavailableRequirements)",
            ),
        )
        val transcriberFailure = ReportStaticSourceInspector.functionBlock(
            MainActivityRefactorSource.composition(activity, "voiceInputController"), "override fun limitPersistentHandsFreeRecognition",
        )
        assertTrue(method("handleHandsFreeVoiceTerminalFailure").contains("applicationEffects.limitPersistentHandsFreeRecognition()"))
        assertTrue(
            transcriberFailure.contains(
                "val walkStartStillAllowed = startupCapabilityDecision?.mayConfirmAndStart == true",
            ),
        )
        assertTrue(transcriberFailure.contains("보행을 시작하거나 계속할 수 없습니다"))
    }

    @Test
    fun homeCommandsPreferInstalledKoreanPlatformAndWalkFlowsRequireVosk() {
        val voiceStart = method("startVoiceCommandRecognition")
        val platform = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/input/AndroidVoiceInputPlatform.kt").readText()
        assertTrue(MainActivityRefactorSource.composition(activity, "voiceInputController").contains("AndroidVoiceInputPlatform(this)"))
        assertTrue(MainActivityRefactorSource.member(platform, "createRecognizer").contains("PreferredOfflineSpeechRecognizer(context, modelDirectory)"))
        val preferred = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/voice/PreferredOfflineSpeechRecognizer.kt",
        ).readText()

        assertTrue(
            voiceStart.contains(
                "val preferPlatform = purpose == VoiceRecognitionPurpose.COMMAND &&",
            ),
        )
        assertTrue(voiceStart.contains("environment.homeFeatureContextAvailable()"))
        assertTrue(
            voiceStart.contains(
                "if (handsFreeRuntime.modelDirectory == null && !preferPlatform)",
            ),
        )
        assertTrue(
            voiceStart.contains(
                "platform.createRecognizer { handsFreeRuntime.modelDirectory }",
            ),
        )
        assertFalse(activity.contains("SpeechRecognizer.isOnDeviceRecognitionAvailable"))
        assertFalse(activity.contains("SpeechRecognizer.createOnDeviceSpeechRecognizer"))
        assertFalse(activity.contains("SpeechRecognizer.createSpeechRecognizer"))
        assertFalse(voiceStart.contains("postLoginDeviceFeatureEnabled"))
        assertFalse(voiceStart.contains("WalkSafeStartupRequirement.ON_DEVICE_STT"))
        assertTrue(voiceStart.contains("if (!environment.microphoneGranted())"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                voiceStart,
                "if (handsFreeRuntime.modelDirectory == null) prepareHandsFreeVoiceModel()",
                "if (handsFreeRuntime.modelDirectory == null && !preferPlatform)",
                "stopHandsFreeVoiceService()",
                "platform.createRecognizer { handsFreeRuntime.modelDirectory }",
                "recognizer.setRecognitionListener",
                "recognizer.startListening(",
                "preferPlatform = preferPlatform",
                "isRequestCurrent = {",
            ),
        )
        assertTrue(preferred.contains("SpeechRecognizer.createOnDeviceSpeechRecognizer(context)"))
        val platformAdapter = preferred.substringAfter("private class AndroidPlatformSpeechBackend")
        val queryPlatform = ReportStaticSourceInspector.functionBlock(
            preferred,
            "private fun queryPlatform",
        )
        assertTrue(platformAdapter.contains(
            "onInstalledLanguages { recognitionSupport.installedOnDeviceLanguages }",
        ))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(
            queryPlatform,
            "if (!accepts(current) || current.selected) return@support",
            "installedLanguages().any",
            "tag == \"ko\" || tag.startsWith(\"ko-\")",
            "select(current, installed)",
        ))
        assertTrue(preferred.contains("OfflineSpeechSelection.VOSK ->"))
        assertFalse(preferred.contains("SpeechRecognizer.createSpeechRecognizer("))
    }

    @Test
    fun limitationMustBeDeliveredBeforeConfirmationAndResultIsPersistedWithProvenance() {
        val confirmation = method("confirmStartupCapabilityDecision")
        val completion = method("completeStartupCapabilityConfirmation")
        val persistenceOwner = method("persistStartupCapabilityDecision")
        assertTrue(persistenceOwner.contains("storage.write(decision, profileMatch)"))
        assertTrue(MainActivityRefactorSource.composition(activity, "startupConfirmationInteraction").contains("storage = AndroidStartupDecisionStorage { stepLengthPrefs }"))
        val persistenceSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/interaction/AndroidStartupDecisionStorage.kt").readText()
        val persistence = MainActivityRefactorSource.member(persistenceSource, "write")
        assertTrue(persistenceOwner.contains("lastPersisted == decision"))
        assertTrue(persistenceOwner.indexOf("lastPersisted == decision") < persistenceOwner.indexOf("storage.write(decision, profileMatch)"))
        assertTrue(persistence.contains("preferences().edit()"))

        assertTrue(confirmation.contains("deliverStartupCapabilityNotice("))
        assertTrue(confirmation.contains("onDelivered = { completeStartupCapabilityConfirmation(decision) }"))
        assertFalse(confirmation.contains("confirmedStartupCapabilityDecision = decision"))
        assertFalse("same decision must not be confirmed before the notice is delivered",
            Regex("\\bconfirmed\\s*=\\s*decision\\b").containsMatchIn(confirmation))
        assertTrue(completion.contains("confirmed = expected"))
        assertTrue(activity.contains("startupCapabilityConfirmationPending"))
        val notice = method("deliverStartupCapabilityNotice")
        assertTrue(notice.contains("onCompleted = deliveredOnMain"))
        val confirmationWire = MainActivityRefactorSource.composition(activity, "startupConfirmationInteraction")
        assertTrue(confirmationWire.contains("statusExplanation = true, additionalCurrent = additionalCurrent"))
        assertTrue(persistence.contains("startup_capability_tier"))
        assertTrue(persistence.contains("startup_capability_device_model"))
        assertTrue(persistence.contains("startup_capability_android_version"))
        assertTrue(persistence.contains("startup_capability_app_version"))
        assertTrue(persistence.contains("startup_capability_source_commit"))
        assertTrue(persistence.contains("startup_capability_device_profile_status"))
        assertTrue(persistence.contains("startup_capability_device_profile_version"))
        assertTrue(persistence.contains("startup_capability_evaluated_at_epoch_ms"))
    }

    @Test
    fun runtimeTtsFailureBlocksWalkAndSafetyStopsOnlyTheActiveSession() {
        val failure = method("handleRuntimeSpeechCapabilityFailure")
        val oneShotFailure = method("handleOneShotSpeechRecognitionFailure")

        assertTrue(failure.contains("offlineKoreanTextToSpeechCapabilityOverride = false"))
        assertFalse(failure.contains("onDeviceSpeechRecognitionCapabilityOverride = false"))
        assertFalse(failure.contains("WalkSafeStartupRequirement.ON_DEVICE_STT"))
        assertTrue(failure.contains("한국어 음성 필요 · 보행 시작/재개 불가"))
        assertTrue(failure.contains("기기 점검을 다시 실행"))
        assertFalse(failure.contains("다른 기능은 계속"))
        assertTrue(failure.contains("WalkSessionState.ACTIVE"))
        assertTrue(failure.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertFalse(failure.contains("stopDepthSession("))
        assertFalse(failure.contains("stopLocationUpdates()"))
        assertTrue(failure.contains("refreshStartupCapabilityUi()"))
        assertTrue(oneShotFailure.contains("oneShotSpeechRecognitionLimited = true"))
        assertFalse(oneShotFailure.contains("applicationEffects.limitPersistentHandsFreeRecognition()"))
        assertFalse(oneShotFailure.contains("stopHandsFreeVoiceService()"))
        assertFalse(oneShotFailure.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertTrue(oneShotFailure.contains("scheduleHandsFreeVoiceRestart()"))

        val navigationStart = method("startNavigationServicesIfNeeded")
        val navigationGate = method("currentNavigationCollectionAllowsWork")
        val stepTrackingGate = method("currentStepTrackingCollectionAllowsWork")
        val locationUpdate = locationObservationPolicy(activity)
        assertTrue(navigationStart.contains("if (!currentStepTrackingCollectionAllowsWork())"))
        assertTrue(stepTrackingGate.contains("if (!isStartupCapabilityConfirmed()) return false"))
        assertTrue(
            navigationGate.contains(
                "postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)",
            ),
        )
        assertFalse(navigationGate.contains("currentRuntimeMetricOutputAllowsWork()"))
        assertTrue(locationUpdate.contains("!scope.navigationAllowed"))
    }

    @Test
    fun structuralSpeechRecognitionErrorsFailClosedButNoMatchCanRetry() {
        val listener = method("buildVoiceCommandRecognitionListener")

        assertTrue(listener.contains("SpeechRecognizer.ERROR_NO_MATCH"))
        assertTrue(listener.contains("SpeechRecognizer.ERROR_SPEECH_TIMEOUT"))
        assertTrue(listener.contains("speechRecognizer?.destroy()"))
        assertTrue(listener.contains("handleOneShotSpeechRecognitionFailure("))
        assertFalse(listener.contains("applicationEffects.limitPersistentHandsFreeRecognition()"))
        assertTrue(
            listener.contains(
                "VoiceRecognitionPurpose.WALK_SESSION_RESUME ->\n" +
                    "                        commands.resumeRecognizerNotStarted()",
            ),
        )
        assertTrue(listener.contains("permanentlyLimit = false"))
        assertFalse(listener.contains("permanentlyLimit = true"))
        assertFalse(listener.contains("shouldPermanentlyLimitOneShotSpeechRecognition(error)"))
    }

    @Test
    fun standaloneBackendFailuresRemainRetryableEvenThoughLegacyLanguagePolicyIsSticky() {
        assertTrue(
            shouldPermanentlyLimitOneShotSpeechRecognition(
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            ),
        )
        listOf(
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            Int.MIN_VALUE,
        ).forEach { error ->
            assertFalse(shouldPermanentlyLimitOneShotSpeechRecognition(error))
        }

        val voiceStart = method("startVoiceCommandRecognition")
        assertFalse(voiceStart.contains("permanentlyLimit = true"))
        assertTrue(voiceStart.contains("permanentlyLimit = false"))
        assertTrue(
            ReportStaticSourceInspector.appearsInOrder(
                voiceStart,
                "if (handsFreeRuntime.modelDirectory == null) prepareHandsFreeVoiceModel()",
                "if (handsFreeRuntime.modelDirectory == null && !preferPlatform)",
                "oneShotSpeechRecognitionLimited = false",
                "platform.createRecognizer { handsFreeRuntime.modelDirectory }",
            ),
        )
    }
    private fun method(name: String): String = MainActivityRefactorSource.function(activity, "private fun $name(")


    private fun locationObservationPolicy(
        mainSource: String,
        ownerSource: String = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt").readText(),
    ): String {
        MainActivityRefactorSource.composition(mainSource, "positioningSessionController")
        val main = MainActivityRefactorSource.factory(mainSource, "handleLocationUpdate")
        // Whole declaration comparison preserves guards, argument forwarding and effect order.
        val expectedMain = """
private fun handleLocationUpdate(
        location: Location,
        owner: LocationCollectionOwner,
        locationGeneration: Int,
        binding: PositioningCollectionBinding,
        receivedAtElapsedRealtimeMs: Long,
    ) {
        positioningSessionController.handleLocationUpdate(location, owner, locationGeneration, binding, receivedAtElapsedRealtimeMs)
    }
        """.trimIndent()
        org.junit.Assert.assertEquals("Main location callback must directly invoke its actual owner", expectedMain.filterNot(Char::isWhitespace),
            main.filterNot(Char::isWhitespace))
        val owner = ownerSource
        val update = MainActivityRefactorSource.factory(owner, "handleLocationUpdate")
        // Whole declaration comparison preserves guards, argument forwarding and effect order.
        val expectedUpdate = """
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
        """.trimIndent()
        // Keep line boundaries: joining a // comment with the next line can disable its guard.
        org.junit.Assert.assertEquals("SDK callback must reject retired observations before mapping and invoking the observation policy",
            expectedUpdate.lines().map(String::trim), update.lines().map(String::trim))
        assertTrue(update.contains("if (!isLocationObservationCurrent(owner, locationGeneration, binding)) return"))
        assertTrue(update.indexOf("if (!isLocationObservationCurrent") < update.indexOf("location.toPositioningObservation"))
        assertTrue(update.contains("handleLocationObservation("))
        return MainActivityRefactorSource.member(owner, "handleLocationObservation")
    }


    @Test
    fun locationObservationPolicyRejectsNonExecutingMainCallers() {
        val mainSource = activity
        locationObservationPolicy(mainSource)
        val caller = MainActivityRefactorSource.factory(mainSource, "handleLocationUpdate")
        val call = "positioningSessionController.handleLocationUpdate(location, owner, locationGeneration, binding, receivedAtElapsedRealtimeMs)"
        org.junit.Assert.assertTrue(caller.contains(call))
        val variants = linkedMapOf(
            "conditional" to caller.replace(call, "if (false) { $call }"),
            "uninvoked lambda" to caller.replace(call, "{ $call }"),
            "comment" to caller.replace(call, "/* $call */"),
            "additional effect" to caller.replace(call, "$call\nunexpectedEffect()"),
            "omitted" to caller.replace(call, ""),
            "early return" to caller.replace(call, "return\n$call"),
        )

        val accepted = variants.filter { (_, changed) ->
            val mainSource = mainSource.replace(caller, changed)
            runCatching { locationObservationPolicy(mainSource) }.isSuccess
        }.keys
        org.junit.Assert.assertTrue("Non-equivalent Main callers accepted: $accepted", accepted.isEmpty())
    }


    @Test
    fun locationObservationPolicyRejectsNonExecutingSdkObservationCalls() {
        val mainSource = activity
        val owner = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt").readText()
        locationObservationPolicy(mainSource, owner)
        val caller = MainActivityRefactorSource.factory(owner, "handleLocationUpdate")
        val start = caller.indexOf("        handleLocationObservation(")
        org.junit.Assert.assertTrue(start >= 0)
        val call = caller.substring(start, caller.lastIndexOf("\n    }")).trim()
        val commentBoundary = "// Reject a retired callback before reading any SDK values.\n"
        org.junit.Assert.assertTrue(caller.contains(commentBoundary))
        val variants = mapOf(
            "line comment swallows guard" to caller.replace(commentBoundary, commentBoundary.trimEnd() + " "),
            "conditional" to caller.replace(call, "if (false) { $call }"),
            "uninvoked lambda" to caller.replace(call, "{ $call }"),
            "comment" to caller.replace(call, "/* $call */"),
            "additional effect" to caller.replace(call, "$call\nunexpectedEffect()"),
            "omitted" to caller.replace(call, ""),
            "early return" to caller.replace(call, "return\n$call"),
        )
        val accepted = variants.filter { (_, changed) ->
            runCatching { locationObservationPolicy(mainSource, owner.replace(caller, changed)) }.isSuccess
        }.keys
        org.junit.Assert.assertTrue("Non-equivalent SDK callers accepted: $accepted", accepted.isEmpty())
    }

}
