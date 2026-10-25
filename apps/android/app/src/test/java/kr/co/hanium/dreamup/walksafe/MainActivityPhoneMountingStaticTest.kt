package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityPhoneMountingStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val panelSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/OnboardingReadinessPanel.kt").readText()
    private val presenterSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/FirstRunOnboardingPresenter.kt").readText()

    private val nativePresenterSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeScreenPresenter.kt").readText()

    private val devicePresenterSource =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DeviceCheckPresenter.kt").readText()

    @Test
    fun mountingStageVisibilityUsesCallerFactsWithoutChangingReadinessPolicy() {
        val update = MainActivityRefactorSource.function(source, "private fun updateFirstRunOnboardingUi()")
        val presentation = functionBlock("fun renderPreparation(", presenterSource)
        assertTrue(update.contains("val showPhoneMountingPreparation = showWalkPreparation && facts.cameraAnalysisEnabled()"))
        assertTrue(update.contains("showPhoneMountingPreparation = showPhoneMountingPreparation"))
        assertTrue(update.contains("environmentStatusReady = facts.published(NativeUiPublication.Target.OFFICIAL_ENVIRONMENT_STATUS_TEXT)"))
        assertTrue(update.contains("effects.renderPreparation {"))
        assertTrue(MainActivityRefactorSource.composition(source, "firstRunPresentationCoordinator").contains("firstRunOnboardingPresenter.renderPreparation(facts()"))
        assertTrue(presentation.contains("if (facts.environmentStatusReady)"))
        assertTrue(presentation.contains("phoneMountingStatusText.visibility ="))
        assertTrue(presentation.contains("facts.showWalkPreparation && !facts.developmentGuidanceStartBypassEnabled"))
        assertTrue(presentation.contains("facts.showPhoneMountingPreparation && !facts.developmentGuidanceStartBypassEnabled"))
        assertTrue(presentation.contains("!facts.developmentQuickStartEnabled || facts.walkState != WalkSessionState.READY"))
        assertTrue(presentation.contains("phoneMountingNecklaceConfirmButton.visibility = View.GONE"))
        assertFalse(presenterSource.contains("PhoneMountingPolicy.assess("))
        assertFalse(presenterSource.contains("phoneMountingUserConfirmation ="))
    }

    @Test
    fun fixedFrontMountingGuidanceStaysInTheMinimalStartupGate() {
        val content = MainActivityRefactorSource.function(source, "private fun buildContentView(): FrameLayout")
        val registrationSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativeRegistrationScreenComposition.kt").readText()
        val registrationBind = MainActivityRefactorSource.member(registrationSource, "bindRegistration")
        val readinessPublication = MainActivityRefactorSource.member(registrationSource, "publishEducationAndReadiness")
        assertTrue(MainActivityRefactorSource.property(source, "nativeRegistrationComposition").contains("NativeRegistrationScreenComposition(this, viewStyle, nativeUiPublication)"))
        assertTrue(content.contains("nativeRegistrationComposition.bindRegistration(onboardingPanel)"))
        assertTrue(content.contains("nativeRegistrationComposition.publishEducationAndReadiness()"))
        val statusView = blockStartingAt(
            panelSource,
            "phoneMountingStatusText = TextView(context).apply",
        )
        val overlay = blockStartingAt(
            panelSource,
            "walkReadinessControls = LinearLayout(context).apply",
        )
        val nativePanel = functionBlock("private fun buildNativeDeviceCheckPanel()", panelSource)
        val nativePanelBinding = MainActivityRefactorSource.function(source, "private fun buildNativeDeviceCheckPanel(panel:")
        val nativePresentation = MainActivityRefactorSource.function(source, "private fun applyNativePreviewPresentation(")
        val nativePreparation = functionBlock("fun renderPreviewAccountAndPreparation(", nativePresenterSource)
        val nativeReadiness = functionBlock("fun renderPreviewReadiness(", nativePresenterSource)

        assertTrue(statusView.contains("text = \"휴대전화 장착 상태를 확인하는 중입니다.\""))
        assertTrue(statusView.contains("contentDescription = text"))
        assertTrue(statusView.contains("View.IMPORTANT_FOR_ACCESSIBILITY_YES"))
        assertTrue(statusView.contains("View.ACCESSIBILITY_LIVE_REGION_NONE"))
        assertTrue(panelSource.contains("ViewCompat.setAccessibilityHeading(phoneMountingStatusText"))
        assertTrue(panelSource.contains("phoneMountingChestConfirmButton ="))
        assertTrue(panelSource.contains("label = \"휴대전화 정면 고정 확인\""))
        assertTrue(panelSource.contains("onClick = actions.readiness.onForwardMountConfirm"))
        assertTrue(content.contains("onForwardMountConfirm = {"))
        assertTrue(content.contains("val previousMethod = phoneMountingUserConfirmation?.method"))
        assertTrue(
            content.contains(
                "it == PhoneMountingMethod.CHEST_FORWARD || it == PhoneMountingMethod.NECKLACE_FORWARD",
            ),
        )
        assertTrue(content.contains("confirmPhoneMounting(method)"))
        assertTrue(panelSource.contains("phoneMountingNecklaceConfirmButton ="))
        assertTrue(panelSource.contains("onClick = actions.readiness.onNecklaceMountConfirm"))
        assertTrue(content.contains("onNecklaceMountConfirm = {"))
        assertTrue(content.contains("confirmPhoneMounting(PhoneMountingMethod.NECKLACE_FORWARD)"))
        assertTrue(readinessPublication.contains("publication.publish(NativeUiPublication.Target.PHONE_MOUNTING_STATUS_TEXT)"))
        assertTrue(readinessPublication.contains("publication.publish(NativeUiPublication.Target.PHONE_MOUNTING_CHEST_CONFIRM_BUTTON)"))
        assertTrue(readinessPublication.contains("publication.publish(NativeUiPublication.Target.PHONE_MOUNTING_NECKLACE_CONFIRM_BUTTON)"))
        assertTrue(content.contains("nativeUiPublication.publish(NativeUiPublication.Target.WALK_READINESS_CONTROLS)"))
        assertTrue(registrationBind.contains("firstRun = FirstRunOnboardingPresenter(context, style, panel)"))
        assertTrue(content.contains("onboardingPanel"))
        assertTrue(readinessPublication.contains("firstRun.publishReadinessTargets()"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "publishReadinessTargets").contains("publishedReadiness = onboarding"))
        assertTrue(MainActivityRefactorSource.member(presenterSource, "readinessBinding").contains("publishedReadiness"))
        assertTrue(content.contains("nativeDeviceCheckPanel = buildNativeDeviceCheckPanel(onboardingPanel)"))
        assertInOrder(nativePanelBinding, "nativeDeviceCheckValues.clear()",
            "nativeDeviceCheckValues.putAll(panel.nativeDeviceCheckValues)", "return panel.nativeDeviceCheckPanel")
        assertTrue(overlay.contains("addView(officialEnvironmentStatusText)"))
        assertTrue(overlay.contains("addView(officialEnvironmentConfirmButton)"))
        assertTrue(overlay.contains("addView(phoneMountingStatusText)"))
        assertTrue(overlay.contains("addView(phoneMountingChestConfirmButton)"))
        assertTrue(overlay.contains("addView(phoneMountingNecklaceConfirmButton)"))
        assertTrue(content.contains("addView(firstRunOnboardingPresenter.readinessBinding().walkReadinessControls)"))
        assertTrue(nativePresentation.contains("NativePreviewPresentationReader.read("))
        val previewPolicy = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/NativePreviewPresentationPolicy.kt").readText()
        assertTrue(previewPolicy.contains("val guidanceVisible = input.homeAvailable && input.page == NativeUiPage.GUIDANCE"))
        assertTrue(previewPolicy.contains("!guidanceVisible && input.homeAvailable && preparing"))
        assertTrue(previewPolicy.contains("val mounting = plan.physicalPreparation(preparing, deviceScreen) && queries.cameraEnabled()"))
        assertTrue(previewPolicy.contains("showPhysicalPreparation = physicalPreparation(preparing, deviceScreen)"))
        assertTrue(previewPolicy.contains("showMountingPreparation = mounting"))
        assertTrue(nativePresentation.contains("nativeScreenPresenter.renderPreviewAccountAndPreparation(preview,"))
        assertTrue(nativePreparation.contains("show(officialEnvironmentStatusText, facts.showPhysicalPreparation)"))
        assertTrue(nativePreparation.contains("show(phoneMountingChestConfirmButton, facts.showMountingPreparation)"))
        assertTrue(nativePreparation.contains("show(phoneMountingNecklaceConfirmButton, false)"))
        val guidanceAssessment = MainActivityRefactorSource.function(source, "private fun currentNativeGuidancePresentation()")
        assertTrue(guidanceAssessment.contains("phoneMountingFailureSpeech(mounting, environment)"))
        assertTrue(guidanceAssessment.contains("currentPhoneMountingAssessment("))
        // The original and current caller both delegate speech selection to this helper.
        val speechBinding = MainActivityRefactorSource.member(source, "phoneMountingFailureSpeech")
        assertTrue(speechBinding.contains("officialEnvironmentPresentation.phoneMountingFailureSpeech(assessment, environment)"))
        val speechOwnerSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/startup/capability/OfficialEnvironmentPresentation.kt").readText()
        val speechSelection = MainActivityRefactorSource.member(speechOwnerSource, "phoneMountingFailureSpeech")
        assertInOrder(speechSelection, "val cameraAction = officialEnvironmentFailureSpeech(",
            "if (cameraAction.isBlank()) return assessment.spokenGuidanceKo", "return if (assessment.phase == PhoneMountingAssessmentPhase.ACTIVE")
        assertTrue(speechSelection.contains("assessment.status == PhoneMountingStatus.CORRECTION_REQUIRED"))
        assertTrue(speechSelection.contains("else cameraAction"))

        val epoch = kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch("mounting-speech", 1L)
        val reason = kr.co.hanium.dreamup.walksafe.device.PhoneMountingReason.CAMERA_EVIDENCE_MISSING
        val mounting = kr.co.hanium.dreamup.walksafe.device.PhoneMountingAssessment(
            kr.co.hanium.dreamup.walksafe.device.PhoneMountingAssessmentPhase.PREFLIGHT,
            kr.co.hanium.dreamup.walksafe.device.PhoneMountingStatus.CORRECTION_REQUIRED,
            reason, null, kr.co.hanium.dreamup.walksafe.device.PhoneMountingPolicy.initialState(epoch, null), false,
        )
        // This real policy getter supplies both cause and action, rather than only a searched literal.
        assertTrue(mounting.spokenGuidanceKo.startsWith(mounting.accessibleReasonKo))
        assertTrue(mounting.spokenGuidanceKo.endsWith(mounting.accessibleActionKo))
        org.junit.Assert.assertEquals(reason.accessibleReasonKo + " " + reason.accessibleActionKo, mounting.spokenGuidanceKo)
        val environment = kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentAssessment(
            epoch, 10L, null,
            kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor.entries.associateWith {
                kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus.PASS
            },
        )
        val evidence = kr.co.hanium.dreamup.walksafe.device.environment.OfficialEnvironmentEvidenceStore(
            Any(), kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate(),
            kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate(),
        )
        val reader = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(kr.co.hanium.dreamup.walksafe.startup.capability.OfficialEnvironmentDisplayReader::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getPreflightActive" -> false
                "getCameraProfile" -> null
                else -> throw AssertionError("Unexpected speech-only reader query: " + method.name)
            }
        } as kr.co.hanium.dreamup.walksafe.startup.capability.OfficialEnvironmentDisplayReader
        val view = java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(kr.co.hanium.dreamup.walksafe.startup.capability.OfficialEnvironmentDisplayView::class.java),
        ) { _, method, _ -> throw AssertionError("Speech formatting must not update a view: " + method.name) }
            as kr.co.hanium.dreamup.walksafe.startup.capability.OfficialEnvironmentDisplayView
        val formatter = kr.co.hanium.dreamup.walksafe.startup.capability.OfficialEnvironmentPresentation(
            reader, { evidence }, view, "support", "confirm",
        )
        org.junit.Assert.assertEquals(mounting.spokenGuidanceKo, formatter.phoneMountingFailureSpeech(mounting, environment))
        evidence.markCameraFailure(kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE.name)
        val cameraBlocked = environment.copy(factorStatuses = environment.factorStatuses +
            (kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor.CAMERA_QUALITY to
                kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus.FAIL))
        val cameraAction = "카메라: 앞을 가리는 물건이 있는지 확인해 주세요."
        org.junit.Assert.assertEquals(cameraAction, formatter.phoneMountingFailureSpeech(mounting, cameraBlocked))
        assertFalse(formatter.phoneMountingFailureSpeech(mounting, cameraBlocked).contains(mounting.accessibleReasonKo))
        val activeCorrection = mounting.copy(phase = kr.co.hanium.dreamup.walksafe.device.PhoneMountingAssessmentPhase.ACTIVE)
        org.junit.Assert.assertEquals(cameraAction + " 화면에서 장착 교정을 확인하세요.",
            formatter.phoneMountingFailureSpeech(activeCorrection, cameraBlocked))
        org.junit.Assert.assertEquals(activeCorrection.spokenGuidanceKo, formatter.phoneMountingFailureSpeech(activeCorrection, environment))
        assertTrue(activeCorrection.spokenGuidanceKo.contains("화면에서 장착 교정을 확인하세요."))
        assertFalse(activeCorrection.spokenGuidanceKo.endsWith(mounting.accessibleActionKo))
        assertTrue(guidanceAssessment.contains("val cameraOutputAvailable = cameraEngineStarted && currentFeedbackDeviceGateAllowsAlerts()"))
        assertFalse(guidanceAssessment.contains("PhoneMountingUserConfirmation("))
        assertFalse(guidanceAssessment.contains("phoneMountingOutputsAllowed = true"))
        assertTrue(nativePanel.contains("ARCore Depth"))
        assertFalse(nativePanel.contains("진동"))
        assertTrue(nativePresentation.contains("val phonePostureNotice = nativePhonePostureNotice"))
        assertTrue(nativePresentation.contains("phonePostureNotice = phonePostureNotice"))
        assertTrue(nativePresentation.contains("nativeScreenPresenter.renderPreviewReadiness(preview,"))
        assertTrue(nativeReadiness.contains("firstRunPhonePostureText.text = readiness.phonePostureNotice"))
        assertTrue(source.contains("get() = PriorityUserEducationPresentation.phonePostureNoticeKo"))
        val scopeNotice = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/PriorityUserEducationPresentation.kt")
            .readText().substringAfter("const val phonePostureNoticeKo =")
            .substringBefore("fun speechText(")
        assertTrue(scopeNotice.contains("몸 앞에 세로로 흔들리지 않게 고정"))
        assertTrue(scopeNotice.contains("카메라 앞 시야를 가리지 마세요"))
        assertFalse(scopeNotice.contains("가슴"))
        assertFalse(scopeNotice.contains("목걸이"))
        assertTrue(scopeNotice.contains("현재 장소가 안전하다는 증명이 아닙니다"))
        val acknowledge = MainActivityRefactorSource.function(source, "private fun acknowledgeNativePhonePosture()")
        assertFalse(acknowledge.contains("PhoneMountingUserConfirmation("))
        assertFalse(acknowledge.contains("SystemClock.elapsedRealtime()"))
        assertTrue(nativeReadiness.contains("show(postLoginDeviceCheckHapticQuestionText, false)"))
        assertTrue(nativeReadiness.contains("show(postLoginDeviceCheckHapticConfirmButton, false)"))
    }

    @Test
    fun confirmationIsMemoryOnlyEpochBoundAndExplicitAfterAFault() {
        val confirmation = MainActivityRefactorSource.function(source, "private fun confirmPhoneMounting(")

        val mountingSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/mounting/PhoneMountingSessionController.kt").readText()
        val explicit = MainActivityRefactorSource.member(mountingSource, "confirmExplicit")
        val mountingBinding = MainActivityRefactorSource.composition(source, "phoneMountingSession")
        val sharedConstructor = Regex(
            """PhoneMountingSessionController\(\s*phoneMountingObservationLock\s*,\s*officialEnvironmentEvidenceStore\s*,\s*""" +
                """MountingPreflightEvidence\s*\{[^}]*}\s*,\s*\{\s*walkSessionLifecycle\s*}\s*,""",
        )
        assertTrue("The owner must borrow Main's exact mount observation lock and live lifecycle supplier",
            sharedConstructor.containsMatchIn(mountingBinding))
        assertTrue(mountingBinding.contains("by lazy(LazyThreadSafetyMode.SYNCHRONIZED)"))
        assertTrue(explicit.contains("synchronized(observationLock)"))
        assertFalse("A new per-owner lock must fail the shared-lock binding contract",
            sharedConstructor.containsMatchIn(mountingBinding.replaceFirst("phoneMountingObservationLock,", "Any(),")))
        assertFalse("A retained or replacement lifecycle must fail the live lifecycle binding contract",
            sharedConstructor.containsMatchIn(mountingBinding.replaceFirst("{ walkSessionLifecycle }", "{ retainedLifecycle }")))
        assertTrue(MainActivityRefactorSource.composition(source, "phoneMountingSession").contains("SystemClock::elapsedRealtime"))
        assertTrue(confirmation.contains("session.confirmExplicit(snapshot.epoch, method, activeCorrection)"))
        assertTrue(explicit.contains("PhoneMountingUserConfirmation("))
        assertTrue(confirmation.contains("admission.officialReadiness(snapshot.epoch).first !="))
        assertFalse(confirmation.contains("officialEnvironmentUserConfirmation?.epoch"))
        assertTrue(confirmation.contains("session.confirmExplicit(snapshot.epoch,"))
        assertTrue(explicit.contains("PhoneMountingUserConfirmation(epoch, value,"))
        assertTrue(explicit.contains("value, method, activeCorrection)"))
        assertTrue(explicit.contains("method, activeCorrection)"))
        assertTrue(confirmation.contains("correctionRequiredSinceElapsedRealtimeMs"))
        assertFalse(confirmation.contains("phoneMountingOutputsAllowed = true"))
        assertFalse(confirmation.contains("WalkSessionEvent.Resume"))
        assertFalse(confirmation.contains("activateWalkSessionRuntime()"))
        assertFalse(confirmation.contains("stepLengthPrefs"))
        assertFalse(source.contains("PREF_PHONE_MOUNTING"))
        assertFalse(confirmation.contains("runtimeRetryRequested = true"))
        assertInOrder(explicit,
            "synchronized(observationLock)", "val value = clock()", "val confirmation = PhoneMountingUserConfirmation(",
            "phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs = value", "phoneMountingAppliedFaultSequence = maxOf(")
        assertInOrder(confirmation,
            "session.confirmExplicit(snapshot.epoch, method, activeCorrection)", "session.consumeConfirmation(mountObservation)",
            "schedulePhoneMountingRuntimeRetryTimeout(", "새 카메라 검사를 진행합니다.", "return\n        } else {")
    }

    @Test
    fun mountingReadinessAndRuntimeActivationFailClosed() {
        val readiness = MainActivityRefactorSource.function(source, "private fun phoneMountingReadiness(")
        val readinessSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/readiness/WalkReadinessCoordinator.kt").readText()
        val capture = MainActivityRefactorSource.member(readinessSource, "capture")
        val producerSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/readiness/WalkReadinessProducers.kt").readText()
        val producer = MainActivityRefactorSource.member(producerSource, "evaluate", occurrence = 5)
        assertTrue(MainActivityRefactorSource.member(source, "captureWalkSessionReadiness").contains("walkReadinessCoordinator.capture(decision, action, recordResourceDiagnostics, checkEntryPermissions)"))
        assertTrue(MainActivityRefactorSource.property(source, "walkReadinessCoordinator").contains("WalkReadinessCoordinator("))
        val blockReason = MainActivityRefactorSource.function(source, "private fun walkSessionReadinessBlockReason(")
        val refresh = MainActivityRefactorSource.function(source, "private fun refreshStartupCapabilityUi()")
        val activation = MainActivityRefactorSource.function(source, "private fun activateWalkSessionRuntime()")
        val startAfterRelease =
            MainActivityRefactorSource.function(source, "private fun startWalkSessionRuntimeAfterCameraRelease(")

        assertTrue(readiness.contains("activePhoneMountingProfile"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.UNAVAILABLE"))
        assertTrue(readiness.contains("PhoneMountingStatus.SUITABLE"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.READY"))
        assertTrue(readiness.contains("PhoneMountingStatus.CORRECTION_REQUIRED"))
        assertTrue(readiness.contains("WalkSessionReadinessStatus.PENDING"))
        assertTrue(readiness.contains("PhoneMountingStatus.UNUSABLE"))
        assertTrue(
            capture.contains(
                "WalkSessionReadinessRequirement.PHONE_MOUNTING",
            ),
        )
        assertTrue(producer.contains("mounting.readiness(epoch, mountingAssessment)"))
        assertTrue(capture.contains("mountingAssessment?.readinessEvidence.orEmpty()"))
        assertTrue(capture.contains("activeRuntimePolicy -> emptyList()"))
        assertTrue(blockReason.contains("phoneMountingBlockReason()?.let { return it }"))
        assertTrue(
            blockReason.indexOf("phoneMountingBlockReason()") <
                blockReason.indexOf("sources.missingRequiredPermissions(action)"),
        )
        assertTrue(refresh.contains("val phoneMountingReady ="))
        assertInOrder(refresh, "val phoneMountingReady =", "ui.mountingReadiness(lifecycle().snapshot().epoch)", "ui.render(decision, confirmed, priorityUserDecision, officialEnvironmentReady, phoneMountingReady)")
        val confirmation = functionBlock("fun startupPresentation(", devicePresenterSource)
        val rendered = functionBlock("fun renderStartupCapability(", devicePresenterSource)
        assertTrue(confirmation.contains("capability.officialEnvironmentReady && capability.phoneMountingReady"))
        assertTrue(confirmation.contains("!capability.phoneMountingReady -> \"휴대전화 장착 확인 필요\""))
        assertInOrder(rendered, "val presentation = startupPresentation(facts)",
            "presentation.confirmationAction?.let { action ->", "panel.startupCapabilityConfirmButton.isEnabled = action.enabled")
        assertTrue(activation.contains("services.activatePhoneMounting()"))
        assertTrue(
            activation.indexOf("services.activatePhoneMounting()") <
                activation.indexOf("camera.startRuntimeAfterRelease(runtimeEpoch)"),
        )
        assertTrue(startAfterRelease.contains("effects.startNavigation()"))
        assertTrue(MainActivityRefactorSource.composition(source, "startupCapabilityCoordinator").contains("override fun mountingReadiness(epoch: WalkRuntimeEpoch) = phoneMountingReadiness(epoch).first"))
        assertTrue(MainActivityRefactorSource.composition(source, "startupCapabilityFactsRenderer").contains("deviceCheckPresenter.renderStartupCapability(facts)"))
        assertTrue(MainActivityRefactorSource.composition(source, "walkRuntimeActivationController").contains("override fun activatePhoneMounting() = activatePhoneMountingRuntime()"))
        assertTrue(MainActivityRefactorSource.composition(source, "walkRuntimeCameraAdmissionController").contains("override fun startNavigation() = startNavigationServicesIfNeeded()"))
    }

    @Test
    fun mountingConsumesTheSharedCameraAssessmentWithoutNewThresholds() {
        val observation = MainActivityRefactorSource.function(source, "private fun observeOfficialEnvironmentCameraFrame(")
        val currentAssessment = MainActivityRefactorSource.function(source, "private fun currentPhoneMountingAssessment(")
        val runtimeActivation = MainActivityRefactorSource.function(source, "private fun activatePhoneMountingRuntime()")
        val runtimeReassessment =
            delegatedOwner("applyCurrentPhoneMountingRuntimeAssessment", "phoneMountingSession", "session/mounting/PhoneMountingSessionController.kt", "applyCurrentPhoneMountingRuntimeAssessment", "{ return phoneMountingSession.applyCurrentPhoneMountingRuntimeAssessment(runtimeRetryRequested, cameraFrameQualityOverride, observationSequence, observationGeneration, observationIsFault) }")
        val mountingSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/mounting/PhoneMountingSessionController.kt").readText()
        val cameraField = "latestPhoneMountingCameraAssessment"
        val cameraCommit = MainActivityRefactorSource.member(mountingSource, "commitCameraAssessment")
        assertTrue(MainActivityRefactorSource.property(mountingSource, cameraField).contains("CameraFrameQualityAssessment?"))
        assertTrue(MainActivityRefactorSource.composition(source, "phoneMountingSession").contains("PhoneMountingSessionController("))

        assertTrue(observation.contains("CameraFrameQualityPolicy.assess("))
        assertTrue(
            "the camera frame must be assessed once and shared",
            observation.countOccurrences("CameraFrameQualityPolicy.assess(") == 1,
        )
        assertTrue(cameraCommit.contains("$cameraField ="))
        assertTrue(observation.contains(".asMeasuredEnvironmentEvidence()"))
        assertTrue(currentAssessment.contains("PhoneMountingPolicy.assess("))
        assertTrue(
            currentAssessment.contains(
                "phase: PhoneMountingAssessmentPhase = PhoneMountingAssessmentPhase.PREFLIGHT",
            ),
        )
        assertTrue(runtimeActivation.contains("phase = PhoneMountingAssessmentPhase.ACTIVE"))
        assertTrue(runtimeActivation.contains("sensor.start()"))
        val sensorFailure = blockStartingAt(
            runtimeActivation,
            "if (!sensor.start())",
        )
        assertInOrder(
            sensorFailure,
            "phoneMountingRuntimeState = PhoneMountingPolicy.initialState(",
            "effects.observeUnavailableCamera(",
            "epoch, clock()",
            "return lifecycle().isRuntimeEpochCurrent(epoch)",
        )
        assertTrue(sensorFailure.substringAfter("effects.observeUnavailableCamera(")
            .contains("epoch, clock()"))
        assertFalse(sensorFailure.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertFalse(sensorFailure.contains("cancelActiveRouteRequest()"))
        assertFalse(sensorFailure.contains("cancelDestinationSearch()"))
        assertFalse(sensorFailure.contains("return false"))
        val binding = MainActivityRefactorSource.composition(source, "phoneMountingSession")
        assertTrue(binding.contains("observeOfficialEnvironmentCameraFrame("))
        assertTrue(binding.contains("frameAvailable = false"))
        assertTrue(binding.contains("phoneMountingSensorProbe.start()"))
        assertInOrder(
            runtimeActivation.substringAfter("val initialState = PhoneMountingPolicy.initialState("),
            "applyPhoneMountingRuntimeAssessment(",
            "return lifecycle().isRuntimeEpochCurrent(epoch)",
        )
        assertFalse(runtimeActivation.contains("if (!usable) phoneMountingSensorProbe.stop()"))
        assertTrue(runtimeReassessment.contains("phase = PhoneMountingAssessmentPhase.ACTIVE"))
        assertTrue(
            currentAssessment.contains(
                "cameraFrameQualityOverride ?: $cameraField",
            ),
        )
        assertTrue(currentAssessment.contains("cameraFrameQuality = cameraFrameQuality"))
        assertTrue(currentAssessment.contains("activePhoneMountingProfile"))

        val mountingIntegration = listOf(
            MainActivityRefactorSource.function(source, "private fun confirmPhoneMounting("),
            currentAssessment,
            MainActivityRefactorSource.function(source, "private fun phoneMountingReadiness("),
            delegatedOwner("applyCurrentPhoneMountingRuntimeAssessment", "phoneMountingSession", "session/mounting/PhoneMountingSessionController.kt", "applyCurrentPhoneMountingRuntimeAssessment", "{ return phoneMountingSession.applyCurrentPhoneMountingRuntimeAssessment(runtimeRetryRequested, cameraFrameQualityOverride, observationSequence, observationGeneration, observationIsFault) }"),
        ).joinToString("\n")
        assertFalse(mountingIntegration.contains("ApprovedCameraFrameQualityProfile("))
        listOf(
            "minimumNormalizedBrightness",
            "maximumOccludedFraction",
            "maximumAngularShakeDegreesPerSecond",
            "minimumMountPitchDegrees",
            "maximumMountPitchDegrees",
        ).forEach { threshold ->
            assertFalse(
                "duplicated camera threshold: $threshold",
                mountingIntegration.contains(threshold),
            )
        }
    }

    @Test
    fun sessionIdentityBoundariesInvalidateMountingEvidence() {
        val manualReporterInput = MainActivityRefactorSource.function(source, "private fun persistReporterUserFromInput()")
        assertTrue(
            manualReporterInput.contains(
                "updateNavigationStatus(\"login=blocked manual_reporter_id_disallowed\")",
            ),
        )
        assertFalse(manualReporterInput.contains("reporterUserId ="))

        val invalidations = listOf(
            "internal fun pauseWalkSafeRuntime()" to "app_paused",
            "override fun onDestroy()" to "app_destroyed",
            "private fun bindFirstRunVerifiedActorForTraining()" to "first_run_actor_bound",
            "private fun onAccountLogoutClicked()" to "priority_user_account_logged_out",
            "private fun resetPriorityUserTraining()" to "priority_user_training_reset",
            "private fun startFreshWalk(" to "new_walk:",
            "private fun enterWalkSessionSafetyStopAndCancelOutputs(" to "safety_stop:",
            "private fun applyRuntimeReadinessIfActive(" to "runtime_readiness_changed",
        )
        invalidations.forEach { (signature, reason) ->
            assertTrue(
                "$signature must invalidate mounting evidence for $reason",
                (if (signature == "private fun enterWalkSessionSafetyStopAndCancelOutputs(") MainActivityRefactorSource.function(source, "private fun performWalkSessionSafetyStopCleanup(") else if (signature == "private fun startFreshWalk(") delegatedOwner("startFreshWalk", "walkSessionCleanupController", "session/runtime/WalkSessionCleanupController.kt", "startFreshWalk", "= this@MainActivity.walkSessionCleanupController.startFreshWalk(reason)") else MainActivityRefactorSource.function(source, signature)).contains(when (signature) {
                    "private fun bindFirstRunVerifiedActorForTraining()", "private fun resetPriorityUserTraining()" -> "effects.invalidateMounting(\"$reason"
                    "private fun onAccountLogoutClicked()" -> "runtime.invalidatePhoneMounting(\"$reason"
                    "private fun startFreshWalk(", "private fun enterWalkSessionSafetyStopAndCancelOutputs(" -> "admission.invalidatePhoneMounting(\"$reason"
                    "private fun applyRuntimeReadinessIfActive(" -> "runtime.invalidateMounting(\"$reason"
                    else -> "invalidatePhoneMountingEvidence(\"$reason"
                }),
            )
        }

        val invalidate = MainActivityRefactorSource.function(source, "private fun invalidatePhoneMountingEvidence(")
        assertTrue(invalidate.contains("phoneMountingObservationGeneration += 1L"))
        assertTrue(invalidate.contains("phoneMountingWatchdogGeneration += 1L"))
        assertTrue(invalidate.contains("phoneMountingUserConfirmation = null"))
        assertTrue(invalidate.contains("latestPhoneMountingCameraAssessment = null"))
        assertTrue(invalidate.contains("phoneMountingRuntimeState = null"))
        assertTrue(invalidate.contains("phoneMountingOutputsAllowed = false"))
        assertTrue(MainActivityRefactorSource.composition(source, "firstRunOnboardingController").contains("invalidatePhoneMountingEvidence(reason)"))
        assertTrue(MainActivityRefactorSource.composition(source, "accountAccessInteractionOwner").contains("invalidatePhoneMountingEvidence(reason)"))
        assertTrue(MainActivityRefactorSource.composition(source, "walkSessionCleanupController").contains("invalidatePhoneMountingEvidence(reason)"))
        assertTrue(MainActivityRefactorSource.composition(source, "startupCapabilityCoordinator").contains("invalidatePhoneMountingEvidence(reason)"))
    }

    @Test
    fun runtimeFaultSuppressesOutputsRequiresExplicitRetryAndCannotAutoResume() {
        val mountingSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/mounting/PhoneMountingSessionController.kt").readText()
        val binding = MainActivityRefactorSource.composition(source, "phoneMountingSession")
        val correctionEffect = ReportStaticSourceInspector.functionBlock(binding, "override fun correction")
        val unusableEffect = ReportStaticSourceInspector.functionBlock(binding, "override fun unusable")
        val revokeEffect = ReportStaticSourceInspector.functionBlock(binding, "override fun revokeReportOutputs")
        val cameraCommit = MainActivityRefactorSource.member(mountingSource, "commitCameraAssessment")
        val cameraBridge = MainActivityRefactorSource.member(mountingSource, "applyRuntimeCameraAssessment")
        val finishRetry = MainActivityRefactorSource.member(mountingSource, "finishRuntimeRetryIfCurrent")
        val currentRuntime =
            delegatedOwner("applyCurrentPhoneMountingRuntimeAssessment", "phoneMountingSession", "session/mounting/PhoneMountingSessionController.kt", "applyCurrentPhoneMountingRuntimeAssessment", "{ return phoneMountingSession.applyCurrentPhoneMountingRuntimeAssessment(runtimeRetryRequested, cameraFrameQualityOverride, observationSequence, observationGeneration, observationIsFault) }")
        val reportRevocation =
            MainActivityRefactorSource.member(mountingSource, "revokeReportOutputLease")
        val runtime = currentRuntime +
            "\n" +
            MainActivityRefactorSource.function(source, "private fun applyPhoneMountingRuntimeAssessment(")
        val correction = runtime
            .substringAfter("PhoneMountingStatus.CORRECTION_REQUIRED ->")
            .substringBefore("PhoneMountingStatus.UNUSABLE ->")
        val unusable = runtime.substringAfter("PhoneMountingStatus.UNUSABLE ->")
        val watchdog = MainActivityRefactorSource.member(mountingSource, "schedulePhoneMountingEvidenceWatchdog")
        val retryTimeout =
            MainActivityRefactorSource.function(source, "private fun schedulePhoneMountingRuntimeRetryTimeout(")
        val confirmation = MainActivityRefactorSource.function(source, "private fun confirmPhoneMounting(")
        val observation = MainActivityRefactorSource.function(source, "private fun observeOfficialEnvironmentCameraFrame(")

        assertTrue(currentRuntime.contains("runtimeRetryRequested = runtimeRetryRequested"))
        assertTrue(currentRuntime.contains("revokeReportOutputLease()"))
        assertTrue(reportRevocation.contains("synchronized(observationLock)"))
        assertTrue(reportRevocation.contains("phoneMountingOutputsAllowed = false"))
        assertTrue(revokeEffect.contains("synchronized(reportUploadSafetyLock)"))
        assertTrue(revokeEffect.contains("reportUploadSafetyGeneration += 1L"))
        assertTrue(revokeEffect.contains("reportPrivacyConsentSession.cancelActiveCalls()"))
        assertTrue(correctionEffect.contains("invalidatePhoneMountingDetectionOutputs(epoch)"))
        assertFalse(correctionEffect.contains("cancelActiveRouteRequest()"))
        assertFalse(correctionEffect.contains("cancelDestinationSearch()"))
        assertTrue(correction.contains("phoneMountingWatchdogGeneration += 1L"))
        assertTrue(correctionEffect.contains("playPhoneMountingCorrectionVibration()"))
        assertInOrder(correction, "phoneMountingOutputsAllowed = false", "effects.correction(")
        assertInOrder(correctionEffect, "cancelVoiceCommandRecognition()", "speakStatusExplanation(")
        assertFalse(correction.contains("phoneMountingOutputsAllowed = true"))
        assertFalse(correctionEffect.contains("speakInteraction("))
        assertFalse(correctionEffect.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertFalse(correctionEffect.contains("stopCameraFallbackSession("))
        assertFalse(correctionEffect.contains("stopDepthSession("))
        assertTrue(unusable.contains("phoneMountingOutputsAllowed = false"))
        assertFalse(unusableEffect.contains("enterWalkSessionSafetyStopAndCancelOutputs("))
        assertFalse(unusableEffect.contains("cancelActiveRouteRequest()"))
        assertFalse(unusableEffect.contains("cancelDestinationSearch()"))
        assertTrue(unusableEffect.contains("invalidatePhoneMountingDetectionOutputs(epoch)"))
        assertTrue(unusable.contains("effects.updateMountingUi()"))
        assertTrue(unusableEffect.contains("playPhoneMountingSafetyStopVibration()"))
        assertInOrder(unusable, "phoneMountingOutputsAllowed = false", "effects.unusable(")
        assertInOrder(unusableEffect, "invalidatePhoneMountingDetectionOutputs(epoch)", "speakStatusExplanation(")
        assertFalse(unusable.contains("phoneMountingOutputsAllowed = true"))
        assertFalse(unusableEffect.contains("playPhoneMountingCorrectionVibration()"))
        assertTrue(watchdog.contains("phoneMountingWatchdogGeneration"))
        assertTrue(watchdog.contains("lifecycle().isRuntimeEpochCurrent(epoch)"))
        assertTrue(watchdog.contains("runtimeRetryRequested = false"))
        assertFalse(confirmation.contains("runtimeRetryRequested = true"))
        assertTrue(cameraBridge.contains("applyCurrentPhoneMountingRuntimeAssessment(retryRequested, assessment, sequence, generation, isFault)"))
        assertTrue(observation.contains("assessment.observedAtElapsedRealtimeMs"))
        assertTrue(observation.contains("it > armedAt"))
        assertTrue(retryTimeout.contains("runtimeRetryRequested = true"))
        assertTrue(retryTimeout.contains("phoneMountingRuntimeRetryGeneration"))
        assertFalse(observation.contains("phoneMountingOutputsAllowed = true"))
        assertFalse(observation.contains("WalkSessionEvent.Resume"))
        assertTrue(observation.contains("mounting.observationGeneration()"))
        assertTrue(cameraCommit.contains("phoneMountingOutputsAllowed = false"))
        assertTrue(cameraBridge.contains("retryRequested, assessment, sequence, generation, isFault"))
        assertTrue(cameraCommit.contains("phoneMountingPendingFaultSequence"))
        assertTrue(cameraCommit.contains("phoneMountingAppliedFaultSequence"))
        assertTrue(cameraCommit.contains("currentAt <= armedAt"))
        assertTrue(cameraCommit.contains("currentAt >= latestAt"))
        assertTrue(observation.contains("applyRuntimeCameraAssessment(retry, assessment, sequence, observationGeneration, mountingFault)"))
        assertTrue(cameraBridge.contains("sequence: Long, generation: Long, isFault: Boolean"))
        assertTrue(Regex("observationSequence\\s*<=\\s*phoneMountingAppliedFaultSequence").containsMatchIn(currentRuntime))
        assertInOrder(observation.substringAfter("if (retry && applied)"),
            "mounting.finishRuntimeRetryIfCurrent(armedAt)", "effects.updateMountingUi()")
        assertTrue(finishRetry.contains("phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs = null"))
        assertTrue(binding.contains("override fun updateMountingUi() { updatePhoneMountingUi() }"))
        assertInOrder(
            currentRuntime,
            "phoneMountingPendingFaultSequence >",
            "currentPhoneMountingAssessment(",
            "revokeReportOutputLease()",
            "phoneMountingRuntimeState = assessment.nextState",
        )
        val fallback = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryCameraFallbackDetectorWorker.kt").readText(), "analyze")
        val fallbackBinding = MainActivityRefactorSource.member(source, "createPrimaryCameraFallbackDetectorWorker")
        assertTrue(MainActivityRefactorSource.member(source, "analyzeCameraFallbackFrame").contains("primaryCameraFallbackDetectorWorker.analyze(frameCapture, expectedWalkEpoch, expectedFallbackGeneration)"))
        assertTrue(fallbackBinding.contains("observeOfficialEnvironmentCameraFrame("))
        assertTrue(fallbackBinding.contains("override fun cameraOutputsAllowed() = cameraEnvironmentOutputsAllowed()"))
        assertInOrder(fallback, "observation.luma(", "if (!authority.cameraOutputsAllowed()) return", "invocationWorker.invoke(")
        assertTrue(MainActivityRefactorSource.function(source, "private fun createPrimaryCameraFallbackDetectorWorker(").contains("primaryDetectorInvocationWorker"))
        val invocation = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectorInvocationWorker.kt").readText(), "invoke")
        assertTrue(invocation.contains("detectorOwner.detectOriented(ownedFrame, request.timestampMs, request.quarterTurns)"))
        val afterEvaluation = fallback.substringAfter("advisoryPolicy.evaluate(")
        assertTrue(afterEvaluation.contains("authority.completionIsCurrent("))
        assertTrue(afterEvaluation.contains("!authority.completionIsCurrent("))
        assertTrue(afterEvaluation.contains("advisoryPolicy.reset()"))
        assertInOrder(
            afterEvaluation,
            "advisoryPolicy.reset()",
            "presentation.advisory(",
        )
        val advisorySource = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/delivery/CameraNonMetricAdvisoryDeliveryController.kt").readText()
        val fallbackEmitter = MainActivityRefactorSource.member(advisorySource, "dispatch")
        val advisoryBinding = MainActivityRefactorSource.property(source, "cameraAdvisoryDelivery")
        assertTrue(advisoryBinding.contains("CameraNonMetricAdvisoryDeliveryController("))
        assertTrue(advisoryBinding.contains("policy = nonMetricAdvisoryPolicy"))
        assertTrue(advisoryBinding.contains("elapsedRealtimeMs = SystemClock::elapsedRealtime"))
        assertTrue(MainActivityRefactorSource.member(source, "createPrimaryCameraFallbackDetectorWorker").contains("cameraAdvisoryDelivery.dispatch(action, epoch, generation, deliveryOwner)"))
        val fallbackStart = fallbackEmitter.substringBefore("delivered = {")
        assertTrue(fallbackStart.contains("stillDeliverable("))
        assertTrue(fallbackStart.contains("policy.isDeliveryCurrent(action)"))
        val fallbackDone = fallbackEmitter.substringAfter("delivered = {")
        assertTrue(fallbackDone.contains("val confirmed = complete(action, nowMs)"))
        // The actual DONE owns completion; an expired start lease must not discard heard speech.
        assertFalse(fallbackDone.contains("stillDeliverable("))
        val publication = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectionPublisher.kt").readText(), "publish")
        assertTrue(publication.contains("event.expectedEpoch != null && !authority.epochAllowsPublication(event.expectedEpoch)"))
        val publicationBinding = MainActivityRefactorSource.property(source, "primaryDetectionPublisher")
        assertTrue(publicationBinding.contains("walkSessionLifecycle.isRuntimeEpochCurrent(epoch) && cameraEnvironmentOutputsAllowed()"))
        assertTrue(MainActivityRefactorSource.member(source, "publishDetectionSnapshot").contains("primaryDetectionPublisher.publish(PrimarySnapshotPublication("))
    }

    @Test
    fun asynchronousWalkOutputsUseTheirFeatureGateAndReportsRequireBoth() {
        fun navigationInput(startMarker: String, endMarker: String): String {
            val factory = MainActivityRefactorSource.factory(source, "createNavigationSessions")
            assertTrue(Regex(Regex.escape(startMarker)).findAll(factory).count() == 1)
            assertTrue(Regex(Regex.escape(endMarker)).findAll(factory).count() == 1)
            val start = factory.indexOf(startMarker)
            val end = factory.indexOf(endMarker, start + startMarker.length)
            assertTrue(start >= 0 && end > start)
            return factory.substring(start, end)
        }

        val combinedGate = declarationRegion("private fun walkSafetyOutputsAllowed()")
        val navigationGate = declarationRegion("private fun navigationEnvironmentOutputsAllowed()")
        val cameraGate = declarationRegion("private fun cameraEnvironmentOutputsAllowed()")
        val feedbackCompletion = MainActivityRefactorSource.function(source, "private fun confirmFeedbackDelivery(")
        val navigationSpeech = MainActivityRefactorSource.function(source, "private fun dispatchNavigationSpeech(")
        val routeGuidance = delegatedOwner("updateRouteGuidance", "routeGuidanceController", "navigation/session/RouteGuidanceController.kt", "updateRouteGuidance", "{ routeGuidanceController.updateRouteGuidance(location, rawLocation) recordDirectionGraceDiagnostic() }")

        assertTrue(combinedGate.contains("navigationEnvironmentOutputsAllowed() && cameraEnvironmentOutputsAllowed()"))
        assertTrue(navigationGate.contains("officialEnvironmentOutputsAllowed"))
        assertTrue(navigationGate.contains("navigationOutputsAllowed == true"))
        assertFalse(navigationGate.contains("phoneMountingOutputsAllowed"))
        assertFalse(navigationGate.contains("cameraEnvironmentOutputsAllowed()"))
        assertTrue(cameraGate.contains("officialEnvironmentOutputsAllowed"))
        assertTrue(cameraGate.contains("cameraOutputsAllowed == true"))
        assertTrue(cameraGate.contains("phoneMountingOutputsAllowed"))
        assertFalse(cameraGate.contains("navigationEnvironmentOutputsAllowed()"))
        assertTrue(
            MainActivityRefactorSource.function(source, "private fun currentNavigationCollectionAllowsWork()")
                .contains("currentStepTrackingCollectionAllowsWork()"),
        )
        val observations = MainActivityRefactorSource.function(source, "private fun currentStepTrackingCollectionAllowsWork()")
        assertTrue(observations.contains("if (!firstRunOnboardingComplete()) return false"))
        assertTrue(observations.contains("if (!isWalkSessionRuntimeActive()) return false"))
        assertTrue(observations.contains("if (!isStartupCapabilityConfirmed()) return false"))
        assertTrue(observations.contains("if (!postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)) return false"))
        assertTrue(observations.contains("if (!hasLocationPermission()) return false"))
        assertFalse(observations.contains("navigationEnvironmentOutputsAllowed()"))
        assertFalse(observations.contains("cameraEnvironmentOutputsAllowed()"))
        assertFalse(observations.contains("phoneMountingOutputsAllowed"))
        listOf(
            "private fun currentNavigationCollectionAllowsWork()",
            "private fun dispatchNavigationSpeech(",
            "private fun maybePlayProgressBeep(",
            "private fun isDestinationSearchLeaseCurrent(",
        ).forEach { signature ->
            val output = when (signature) {
                "private fun dispatchNavigationSpeech(" -> {
                    assertTrue(MainActivityRefactorSource.function(source, signature).contains("reader.navigationOwnerIsCurrent(owner)"))
                    MainActivityRefactorSource.member(source, "createSpeechOutputController")
                }
                "private fun isDestinationSearchLeaseCurrent(" -> {
                    assertTrue(MainActivityRefactorSource.function(source, signature).contains("facts.isEpochAllowed(expectedWalkEpoch)"))
                    source.substringAfter("private val destinationSearchController by lazy {")
                        .substringBefore("private val destinationSearchInFlight")
                }
                else -> MainActivityRefactorSource.function(source, signature)
            }
            assertTrue("$signature must use the navigation gate", output.contains("navigationEnvironmentOutputsAllowed()"))
            assertFalse(output.contains("walkSafetyOutputsAllowed()"))
            assertFalse(output.contains("phoneMountingOutputsAllowed"))
            assertFalse(output.contains("cameraEnvironmentOutputsAllowed()"))
        }
        val request = MainActivityRefactorSource.function(source, "private fun requestRoute(")
        val requestOwner = request.substringAfter("fun isCurrentRouteOwner(): Boolean")
            .substringBefore("fun isCurrentRouteRequest(): Boolean")
        val requestOutput = request.substringAfter("fun isCurrentRouteRequest(): Boolean")
            .substringBefore("fun showCurrentUnstartedRequestFailure(")
        assertTrue(requestOwner.contains("isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId)"))
        assertTrue(requestOwner.contains("currentSession.generation == expectedGatewaySessionGeneration"))
        assertTrue(requestOwner.contains("currentSession.session === gatewaySession"))
        assertTrue(requestOwner.contains("facts.actorId == gatewaySession.actorId"))
        assertTrue(requestOutput.contains("facts.environmentOutputsAllowed"))
        assertInOrder(requestOutput, "if (!isCurrentRouteOwner()) return false", "routeResponseEnvironmentCurrent(",
            "outputsAllowed = !facts.runtimeActive || facts.environmentOutputsAllowed")
        assertTrue(navigationInput("routes = AcquisitionAssemblyBindings(", "resources = NavigationAssemblyResources(").contains("override val environmentOutputsAllowed get() = navigationEnvironmentOutputsAllowed()"))
        val acquisitionAssembly = MainActivityRefactorSource.composition(source, "routeAcquisitionController")
        assertTrue(acquisitionAssembly.contains("val activityFacts = routes.facts()"))
        assertTrue(acquisitionAssembly.contains("override val environmentOutputsAllowed: Boolean get() = activityFacts.environmentOutputsAllowed"))
        listOf(
            "private fun currentRuntimeMetricOutputAllowsWork(",
            "private fun analyzeCameraFallbackFrame(",
            "internal fun publishDetectionSnapshot(",
            "private fun isCameraFallbackAdvisoryOwnerCurrent(",
            "private fun currentFeedbackDeviceGateAllowsAlerts()",
        ).forEach { signature ->
            val output = when (signature) {
                "private fun currentRuntimeMetricOutputAllowsWork(" -> {
                    assertTrue(MainActivityRefactorSource.function(source, signature).contains("scope.cameraOutputsAllowed"))
                    MainActivityRefactorSource.composition(source, "runtimeMetricSession")
                }
                "private fun analyzeCameraFallbackFrame(" -> {
                    assertTrue(MainActivityRefactorSource.member(source, "analyzeCameraFallbackFrame").contains("primaryCameraFallbackDetectorWorker.analyze("))
                    MainActivityRefactorSource.member(source, "createPrimaryCameraFallbackDetectorWorker")
                }
                "internal fun publishDetectionSnapshot(" -> {
                    assertTrue(MainActivityRefactorSource.member(source, "publishDetectionSnapshot").contains("primaryDetectionPublisher.publish(PrimarySnapshotPublication("))
                    val publication = MainActivityRefactorSource.member(File("src/main/java/kr/co/hanium/dreamup/walksafe/runtime/vision/primary/PrimaryDetectionPublisher.kt").readText(), "publish")
                    assertTrue(publication.contains("!authority.epochAllowsPublication(event.expectedEpoch)"))
                    MainActivityRefactorSource.property(source, "primaryDetectionPublisher")
                }
                else -> MainActivityRefactorSource.function(source, signature)
            }
            assertTrue("$signature must use the camera gate", output.contains("cameraEnvironmentOutputsAllowed()"))
            assertFalse(output.contains("walkSafetyOutputsAllowed()"))
            assertFalse(output.contains("navigationEnvironmentOutputsAllowed()"))
        }
        assertTrue(feedbackCompletion.contains("isFeedbackActionStillDeliverable(action)"))
        assertTrue(feedbackCompletion.contains("synchronized(phoneMountingObservationLock)"))
        assertTrue(feedbackCompletion.contains("if (!admission.phoneMountingOutputsAllowed())"))
        val advisorySource = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/delivery/CameraNonMetricAdvisoryDeliveryController.kt").readText()
        val fallbackConfirmation = MainActivityRefactorSource.member(advisorySource, "dispatch")
        val advisoryBinding = MainActivityRefactorSource.property(source, "cameraAdvisoryDelivery")
        assertTrue(advisoryBinding.contains("CameraNonMetricAdvisoryDeliveryController("))
        assertTrue(advisoryBinding.contains("isCameraFallbackAdvisoryOwnerCurrent("))
        assertTrue(advisoryBinding.contains("mountingLock = { phoneMountingObservationLock }"))
        assertTrue(advisoryBinding.contains("override fun mountingAllowed() = phoneMountingOutputsAllowed"))
        assertTrue(advisoryBinding.contains("elapsedRealtimeMs = SystemClock::elapsedRealtime"))
        assertTrue(
            fallbackConfirmation.contains(
                "authority.ownerCurrent(epoch, fallbackGeneration, lifecycleGeneration, nowMs)",
            ),
        )
        assertTrue(fallbackConfirmation.contains("complete(action, nowMs)"))
        val fallbackCompletion = MainActivityRefactorSource.member(advisorySource, "complete")
        assertTrue(MainActivityRefactorSource.member(source, "completeCameraFallbackAdvisory").contains("cameraAdvisoryDelivery.complete(action, nowMs, ownerCurrent)"))
        assertTrue(fallbackCompletion.contains("synchronized(mountingLock())"))
        assertTrue(fallbackCompletion.contains("!ownerCurrent() || !authority.mountingAllowed()"))
        val navigationCurrent = navigationSpeech.substringAfter("fun outputCurrent(): Boolean =")
            .substringBefore("fun failOnce()")
        assertTrue(navigationCurrent.contains("isFeedbackLifecycleCurrent(generation)"))
        assertTrue(navigationCurrent.contains("owner.epoch != null"))
        assertTrue(navigationCurrent.contains("reader.navigationOwnerIsCurrent(owner)"))
        val navigationOwnerCurrent = MainActivityRefactorSource.member(source, "createSpeechOutputController")
            .substringAfter("override fun navigationOwnerIsCurrent(owner: NavigationSpeechOwner): Boolean =")
            .substringBefore("override fun homeFeatureContextAvailable()")
        assertTrue(navigationOwnerCurrent.contains("navigationEnvironmentOutputsAllowed()"))
        assertTrue(navigationOwnerCurrent.contains("isWalkSessionRuntimeActive()"))
        assertTrue(navigationOwnerCurrent.contains("owner.epoch != null"))
        assertTrue(navigationOwnerCurrent.contains("walkSessionLifecycle.currentRuntimeEpochOrNull() == owner.epoch"))
        assertFalse(navigationCurrent.contains("isInstructionStillCurrent()"))
        assertFalse(navigationOwnerCurrent.contains("isInstructionStillCurrent()"))
        val navigationCompletion = blockStartingAt(navigationSpeech, "onCompleted = {")
        assertInOrder(
            navigationCompletion,
            "if (outputCurrent() && terminal.compareAndSet(false, true))",
            "onCompleted?.invoke()",
        )
        assertTrue(
            navigationSpeech.contains(
                "isStillValidAtStart = { outputCurrent() && isInstructionStillCurrent() }",
            ),
        )
        assertTrue(routeGuidance.contains("tryDispatchRouteInstruction(update, expectedWalkEpoch, compass)"))
        val delivery = MainActivityRefactorSource.function(source, "private fun tryDispatchRouteInstruction(")
        assertInOrder(
            delivery.substringAfter("onActionCompleted = {"),
            "isRouteSpeechCurrent(expectedWalkEpoch, requestGeneration, lifecycleGeneration)",
            "routeNavigator.acknowledgeInstruction(",
        )
        val routeSpeech = MainActivityRefactorSource.function(source, "private fun isRouteSpeechCurrent(")
        assertTrue(routeSpeech.contains("scope.runtimeActive && scope.navigationAllowed"))
        assertTrue(navigationInput("guides = GuidanceAssemblyBindings(", "bearings = CompassAssemblyBindings(").contains("override val navigationAllowed get() = navigationEnvironmentOutputsAllowed()"))

        val report = MainActivityRefactorSource.function(source, "private fun processReportCandidate(")
        assertInOrder(
            report,
            "authority.isRuntimeEpochCurrent(expectedWalkEpoch)",
            "!authority.walkSafetyOutputsAllowed()",
            "integratedConsentSession.currentConfirmationOrNull()",
            "queue.enqueue(",
        )
        val explicitReport = MainActivityRefactorSource.function(source, "private fun submitFrozenExplicitReportAfterConfirmation(")
        assertInOrder(
            explicitReport,
            "!ensureExplicitReportAuthorityPreconditions()",
            "!authority.walkSafetyOutputsAllowed()",
            "currentExplicitReportConfirmationContextOrNull() != confirmed.context",
            "queue.enqueue(",
        )
        assertFalse(report.contains("uploadCall("))
        assertTrue(
            report.contains(
                "!explicitRequest && !consentConfirmation.selections.automaticReporting",
            ),
        )
        val drain = MainActivityRefactorSource.function(source, "private fun drainInitialExactReportQueue(")
        assertInOrder(
            drain,
            "val context = reportQueueDrainContext(trigger)",
            "if (!context.allRequiredBaseGatesAllowed()) break",
            "reportQueueDrainCoordinator.startNextWithResult(",
            "call.execute()",
        )
        val drainContext = MainActivityRefactorSource.function(source, "private fun reportQueueDrainContext(")
        assertTrue(drainContext.contains("runtime.triggerCurrent && this.runtime.isForeground()"))
        assertTrue(drainContext.contains("currentConfirmation == trigger.consentConfirmation"))
        assertTrue(drainContext.contains("currentGateway.session === trigger.gatewaySession"))
        assertTrue(MainActivityRefactorSource.composition(source, "reportQueueDrainController").contains("override fun isForeground() = isActivityForeground"))
        val consentOwnerSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/consent/IntegratedConsentController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(source, "updateIntegratedConsentDraft").contains("integratedConsentController.updateIntegratedConsentDraft(item, granted)"))
        val automaticConsent = MainActivityRefactorSource.member(consentOwnerSource, "updateIntegratedConsentDraft")
        assertTrue(
            automaticConsent.contains("applyImmediateConsentWithdrawals(setOf(item))"),
        )
        val immediateConsentWithdrawals = MainActivityRefactorSource.member(consentOwnerSource, "applyImmediateConsentWithdrawals")
        assertTrue(
            immediateConsentWithdrawals.contains("reports.cancelAutomaticCalls()"),
        )
        assertTrue(immediateConsentWithdrawals.contains("reports.cancelDrain()"))
        assertTrue(
            immediateConsentWithdrawals.contains(
                "recordAutomaticCleanup(previousReceipt, integratedConsentConfirmedActorSha256)",
            ),
        )
        val cleanup = MainActivityRefactorSource.member(consentOwnerSource, "recordAutomaticCleanup")
        assertTrue(cleanup.contains("saveAutomaticCleanup(pending + AutomaticCleanup(ownerSha256, receipt))"))
        assertTrue(cleanup.contains("integratedConsentSession.failClosed()"))
        assertTrue(cleanup.contains("retryPendingAutomaticReportCleanup()"))
        val retryCleanup = MainActivityRefactorSource.member(consentOwnerSource, "retryPendingAutomaticReportCleanup")
        assertTrue(retryCleanup.contains("current.backendAccountGeneration == session.backendAccountGeneration"))
        assertTrue(retryCleanup.contains("reports.automaticRevoked(record.receipt, session.actorId, session.backendAccountGeneration)"))
        val consentBinding = MainActivityRefactorSource.factory(source, "createPrivacyConsent")
        assertTrue(MainActivityRefactorSource.property(source, "integratedConsentController").contains("by createPrivacyConsent()"))
        val consentAssembly = MainActivityRefactorSource.composition(source, "integratedConsentController")
        assertTrue(consentAssembly.contains("IntegratedConsentController("))
        assertTrue(consentAssembly.contains("reports = ports.reports"))
        assertTrue(consentBinding.contains("privacyAuthority.createIntegratedConsentController"))
        assertTrue(consentBinding.contains("override fun cancelDrain() = cancelReportQueueDrain()"))
        assertTrue(consentBinding.contains("automaticReportUploadSafetyGeneration += 1L"))
        val automaticCancelStart = "override fun cancelAutomaticCalls() {"
        val automaticCancelEnd = "override fun scheduleLegacyPurge()"
        assertTrue(Regex(Regex.escape(automaticCancelStart)).findAll(consentBinding).count() == 1)
        assertTrue(Regex(Regex.escape(automaticCancelEnd)).findAll(consentBinding).count() == 1)
        val automaticCancelStartAt = consentBinding.indexOf(automaticCancelStart)
        val automaticCancelEndAt = consentBinding.indexOf(automaticCancelEnd, automaticCancelStartAt)
        assertTrue(automaticCancelStartAt >= 0 && automaticCancelEndAt > automaticCancelStartAt)
        val automaticCancel = consentBinding.substring(automaticCancelStartAt, automaticCancelEndAt)
        assertInOrder(automaticCancel, "synchronized(reportUploadSafetyLock)",
            "automaticReportUploadSafetyGeneration += 1L", "cancelActiveCalls(ReportTransferPurpose.AUTOMATIC)")
        assertTrue(consentBinding.contains("reportQueueDrainController.onAutomaticReportingRevoked(receipt, reporterActorId, accountGeneration)"))
        assertTrue(MainActivityRefactorSource.function(source, "private fun onAutomaticReportingRevoked(").contains("reportQueueDrainCoordinator.onAutomaticReportingRevoked"))
        assertFalse(automaticConsent.contains("\n                reportUploadSafetyGeneration += 1L"))
    }

    private fun phoneMountingCameraAssessmentField(): String {
        val match = Regex(
            """private var ([A-Za-z0-9_]*PhoneMounting[A-Za-z0-9_]*): """ +
                """CameraFrameQualityAssessment\?""",
        ).find(source)
        checkNotNull(match) { "missing phone mounting CameraFrameQualityAssessment field" }
        return match.groupValues[1]
    }

    private fun phoneMountingWatchdogBlock(): String {
        val match = Regex(
            """private fun (schedulePhoneMounting[A-Za-z0-9_]*Watchdog)\(""",
        ).find(source)
        checkNotNull(match) { "missing phone mounting evidence watchdog" }
        return functionBlock("private fun ${match.groupValues[1]}(")
    }

    private fun functionBlock(signature: String, text: String = source): String {
        val start = text.indexOf(signature)
        check(start >= 0) { "missing function: $signature" }
        var parameterDepth = 0
        for (index in start until text.length) {
            when (text[index]) {
                '(' -> parameterDepth += 1
                ')' -> parameterDepth -= 1
                '{' -> if (parameterDepth == 0) {
                    // A default lambda inside the parameter list is not the function body.
                    return text.substring(start, index) + balancedBlock(text, index)
                }
            }
        }
        error("missing function body: $signature")
    }

    private fun declarationRegion(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing declaration: $signature" }
        val end = listOf(
            "\n    private fun ",
            "\n    internal fun ",
            "\n    override fun ",
        ).map { marker -> source.indexOf(marker, start + signature.length) }
            .filter { it >= 0 }
            .minOrNull()
            ?: source.length
        return source.substring(start, end)
    }

    private fun blockStartingAt(text: String, marker: String): String {
        val start = text.indexOf(marker)
        check(start >= 0) { "missing block: $marker" }
        return balancedBlock(text, start)
    }

    private fun balancedBlock(text: String, start: Int): String {
        var braceDepth = 0
        var sawOpeningBrace = false
        for (index in start until text.length) {
            when (text[index]) {
                '{' -> {
                    braceDepth += 1
                    sawOpeningBrace = true
                }
                '}' -> if (sawOpeningBrace) {
                    braceDepth -= 1
                    if (braceDepth == 0) return text.substring(start, index + 1)
                }
            }
        }
        error("unterminated block at offset $start")
    }

    private fun assertInOrder(text: String, vararg fragments: String) {
        var previous = -1
        fragments.forEach { fragment ->
            val index = text.indexOf(fragment)
            assertTrue("missing or out of order: $fragment", index > previous)
            previous = index
        }
    }

    private fun String.countOccurrences(fragment: String): Int =
        windowed(fragment.length).count { it == fragment }

    // Structural wiring and owner policy are checked separately; this does not execute Main.
    private fun delegatedOwner(name: String, owner: String, path: String, target: String, expectedBody: String): String {
        val caller = MainActivityRefactorSource.factory(source, name)
        // Match the whole caller, including additional effects; a guarded call or lambda is different.
        val declaration = caller.filterNot(Char::isWhitespace)
        val body = Regex.escape(expectedBody.filterNot(Char::isWhitespace))
        val expected = Regex("""^(?:private|internal|public|override)*fun$name\([^{}]*\)(?::[\w?<>]+)?$body$""")
        assertTrue("Main caller changed for $owner.$target: $caller", expected.matches(declaration))
        assertTrue(expectedBody.contains("$owner.$target("))
        MainActivityRefactorSource.composition(source, owner)
        return MainActivityRefactorSource.member(
            File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText(), target,
        )
    }
}
