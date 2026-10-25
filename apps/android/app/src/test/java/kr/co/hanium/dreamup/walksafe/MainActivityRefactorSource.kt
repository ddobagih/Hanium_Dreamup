package kr.co.hanium.dreamup.walksafe

import java.io.File

/**
 * Structural source inspection only: resolves direct forwarding wrappers to their source owner.
 * Source text cannot prove runtime invocation, ordering or lifecycle behavior; those need behavior tests.
 */
internal object MainActivityRefactorSource {
    private const val ROOT = "src/main/java/kr/co/hanium/dreamup/walksafe/"
    val speech: String get() = File(ROOT + "voice/output/SpeechOutputController.kt").readText()
    val destination: String get() = File(ROOT + "navigation/DestinationSearchController.kt").readText()
    val email: String get() = File(ROOT + "session/account/EmailAccountController.kt").readText()
    val camera: String get() = File(ROOT + "runtime/camera/CameraFallbackController.kt").readText()
    val device: String get() = File(ROOT + "device/PostLoginDeviceCheckController.kt").readText()
    val deviceProbes: String get() = File(ROOT + "device/postlogin/AndroidPostLoginDeviceProbes.kt").readText()

    private data class Domain(val property: String, val path: String, val factory: String? = null, val constructor: String, val strictConstructor: Boolean = false) {
        val source get() = File(ROOT + path).readText()
        fun verifyComposition(main: String) {
            val assembled = assembledOwners[property]
            if (assembled != null) {
                assembled.verify(main, property)
                return
            }
            val composed = composedOwners[property]
            if (composed != null) {
                composed.verify(main, property, constructor)
                return
            }
            val delegateHolder = when (property) {
                "frameSynthesisOwner" -> "frameSynthesisHolder"
                "frameRecognition" -> "frameRecognitionHolder"
                else -> property
            }
            if (delegateHolder != property) check(MainActivityRefactorSource.property(main, property).contains("by $delegateHolder"))
            val binding = if (factory != null) {
                if (strictConstructor) {
                    val declaration = MainActivityRefactorSource.property(main, delegateHolder)
                    val initializer = Regex("(?s)^\\s*(?:private\\s+)?val\\s+" + Regex.escape(delegateHolder) +
                        "(?:\\s*:\\s*[^\\n]+?)?\\s+by\\s+lazy(?:\\([^)]*\\))?\\s*\\{\\s*" +
                        Regex.escape(factory) + "\\(\\)\\s*}\\s*$")
                    check(initializer.matches(declaration)) { "Main lost actual factory initializer: $property" }
                    MainActivityRefactorSource.factory(main, factory)
                } else member(main, factory)
            } else property(main, delegateHolder)
            val ownsConstructor = if (strictConstructor) Regex("\\b" + Regex.escape(constructor) + "\\s*\\(").containsMatchIn(binding)
                else binding.contains(constructor + "(")
            check(ownsConstructor) { "Main lost $constructor composition through $property" }
            val childComposition = when (property) {
                "voiceInteractionControllers.commands" -> "voice/interaction/VoiceInteractionControllers.kt" to "VoiceCommandInteractionController"
                "voiceInteractionControllers.destinations" -> "voice/interaction/VoiceInteractionControllers.kt" to "DestinationVoiceDialogController"
                "riskFeedbackComponents.delivery" -> "feedback/delivery/RiskFeedbackComponents.kt" to "RiskFeedbackDeliveryController"
                else -> null
            }
            if (childComposition != null) {
                check(MainActivityRefactorSource.property(File(ROOT + childComposition.first).readText(), property.substringAfterLast('.'))
                    .contains(childComposition.second + "(")) { "Lost actual child composition: $property" }
            }
            if (property in setOf("voiceInputController.gateway", "voiceInputController.wake", "voiceInputController.handsFreeRuntime")) {
                val core = File(ROOT + "voice/input/VoiceInputSessionController.kt").readText()
                val child = property.substringAfterLast('.')
                val childType = when (child) { "gateway" -> "GatewayVoiceCaptureController"; "handsFreeRuntime" -> "HandsFreeRuntimeSession"; else -> "ForegroundWakeInputController" }
                check(MainActivityRefactorSource.property(core, child).contains(childType + "(")) {
                    "VoiceInputSessionController lost actual $child composition"
                }
            }
        }
    }
    private data class ComposedOwner(
        val holder: String, val assembly: String, val factory: String,
        val path: String, val assemblyType: String, val child: String, val alias: String,
    ) {
        fun verify(main: String, owner: String, constructor: String) {
            val holderDeclaration = Regex("(?s)^\\s*(?:private\\s+)?val\\s+" + Regex.escape(holder) +
                "(?:\\s*:\\s*[^=\\n]+)?\\s+" + Regex.escape(alias) + "\\s*$")
            check(holderDeclaration.matches(directDeclaration(MainActivityRefactorSource.property(main, holder)))) { "Main lost actual Lazy identity: $owner" }
            if (holder != owner) check(Regex("(?m)^\\s*private val " + Regex.escape(owner) +
                "\\s*:[^\\n]+\\bby\\s+" + Regex.escape(holder) + "\\s*$").containsMatchIn(main)) { "Main lost owner delegation: $owner" }
            val assemblyDeclaration = Regex("(?s)^\\s*(?:private\\s+)?val\\s+" + Regex.escape(assembly) +
                "(?:\\s*:\\s*[^=\\n]+)?\\s*=\\s*" + Regex.escape(factory) + "\\(\\)\\s*$")
            check(assemblyDeclaration.matches(directDeclaration(MainActivityRefactorSource.property(main, assembly)))) { "Main lost assembly instance: $owner" }
            check(Regex("\\b" + Regex.escape(assemblyType) + "\\s*\\(").containsMatchIn(MainActivityRefactorSource.factory(main, factory))) { "Main lost assembly factory: $owner" }
            check(Regex("\\b" + Regex.escape(constructor) + "\\s*\\(").containsMatchIn(body())) { "Assembly lost actual child: $owner" }
        }
        fun body() = MainActivityRefactorSource.property(File(ROOT + path).readText(), child)
    }
    private fun directDeclaration(text: String): String {
        val end = text.indexOf('\n').let { if (it < 0) text.length else it }
        var index = end
        var commentDepth = 0
        while (index < text.length) {
            when {
                text.startsWith("/*", index) -> { commentDepth += 1; index += 2 }
                commentDepth > 0 && text.startsWith("*/", index) -> { commentDepth -= 1; index += 2 }
                commentDepth > 0 -> index += 1
                text[index].isWhitespace() -> index += 1
                text.startsWith("//", index) -> index = text.indexOf('\n', index).let { if (it < 0) text.length else it }
                else -> error("Main owner initializer has a continued expression")
            }
        }
        check(commentDepth == 0) { "Unterminated owner declaration comment" }
        return text.substring(0, end)
    }
    private data class AssemblyOwner(
        val assembly: String, val mainFactory: String, val assemblyType: String, val path: String,
        val child: String, val initializer: String, val constructor: String,
        val adapter: String? = null, val lazy: Boolean = false,
    ) {
        fun verify(main: String, owner: String) {
            fun exactDeclaration(name: String, expression: String) {
                val pattern = Regex("(?s)^\\s*(?:private\\s+)?val\\s+" + Regex.escape(name) +
                    "(?:\\s*:\\s*[^=\\n]+?)?\\s+" + Regex.escape(expression) + "\\s*$")
                check(pattern.matches(directDeclaration(MainActivityRefactorSource.property(main, name)))) { "Main lost graph alias: $owner" }
            }
            exactDeclaration(owner, initializer)
            exactDeclaration(assembly, "= $mainFactory()")
            fun checkedFactory(source: String, name: String): String {
                val declaration = MainActivityRefactorSource.factory(source, name)
                val end = source.indexOf(declaration) + declaration.length
                val next = Regex("(?m)^(?:    )?(?:private |internal |public |override |lateinit )*(?:fun |val |var |class |data class |companion object)")
                    .find(source, end)?.range?.first ?: source.length
                directDeclaration("factory\n" + source.substring(end, next))
                return declaration
            }
            fun returnedValue(declaration: String): String {
                val parametersEnd = scopeEnd(declaration, declaration.indexOf('('), expression = false)
                val assignment = declaration.indexOf('=', parametersEnd)
                check(assignment >= 0) { "Graph factory must return its actual owner: $owner" }
                return declaration.substring(assignment + 1).trim()
            }
            fun callEnd(value: String, name: String): Int {
                val call = Regex("^" + Regex.escape(name) + "(?:\\s*\\(|\\s*\\{)").find(value)
                    ?: error("Graph lost direct returned call: $owner")
                var end = scopeEnd(value, call.range.last, expression = false)
                if (value[call.range.last] == '(') {
                    val trailing = value.substring(end).trimStart()
                    if (trailing.startsWith("{")) {
                        end = scopeEnd(value, value.indexOf('{', end), expression = false)
                    }
                }
                return end
            }
            val mainValue = returnedValue(checkedFactory(main, mainFactory))
            check(mainValue.substring(callEnd(mainValue, assemblyType)).isBlank()) { "Main wrapped graph factory: $owner" }
            if (adapter != null) {
                val expression = returnedValue(checkedFactory(main, adapter))
                check(expression.substring(callEnd(expression, assembly + "." + child)).isBlank()) { "Main wrapped graph owner: $owner" }
            }
            val owned = if (assembly == "privacyAuthority") checkedFactory(File(ROOT + path).readText(), child) else body()
            if (lazy) check(Regex("=\\s*lazy(?:\\(LazyThreadSafetyMode.NONE\\))?\\s*\\{").containsMatchIn(owned)) { "Graph lost lazy creation: $owner" }
            val creation = if (constructor.contains('.')) owned.contains(constructor) else
                Regex("\\b" + Regex.escape(constructor) + "\\s*\\(").containsMatchIn(owned)
            check(creation) { "Graph lost actual constructor: $owner" }
            if (assembly == "navigationSessions" && lazy) {
                val value = owned.substring(owned.indexOf('=') + 1).trim()
                val start = Regex("^lazy(?:\\(LazyThreadSafetyMode.NONE\\))?\\s*\\{").find(value)
                    ?: error("Graph lost direct original Lazy: $owner")
                val end = scopeEnd(value, start.range.last, expression = false)
                directDeclaration("initializer\n" + value.substring(end))
            }
            if (assembly == "privacyAuthority") {
                val value = returnedValue(owned)
                val end = if (lazy) {
                    val start = Regex("^lazy(?:\\(LazyThreadSafetyMode.NONE\\))?\\s*\\{").find(value)
                        ?: error("Graph lost original Lazy: $owner")
                    scopeEnd(value, start.range.last, expression = false)
                } else if (constructor.endsWith(".also")) value.indexOf(".also") else callEnd(value, constructor)
                check(value.substring(end).trim() == ".also { $owner = it }") { "Graph lost same owner registration: $owner" }
            }
        }
        fun body(): String {
            val source = File(ROOT + path).readText()
            return if (assembly == "privacyAuthority") MainActivityRefactorSource.factory(source, child)
                else MainActivityRefactorSource.property(source, child)
        }
    }
    private val assembledOwners = mapOf(
        "navigationRunState" to AssemblyOwner("navigationSessions", "createNavigationSessions", "NavigationSessionComposition",
            "navigation/session/NavigationSessionComposition.kt", "run", "get() = navigationSessions.run", "NavigationRunState", lazy = false),
        "positioningSessionController" to AssemblyOwner("navigationSessions", "createNavigationSessions", "NavigationSessionComposition",
            "navigation/session/NavigationSessionComposition.kt", "positioning", "by navigationSessions.positioning", "PositioningSessionController", lazy = true),
        "continuousGpsSession" to AssemblyOwner("navigationSessions", "createNavigationSessions", "NavigationSessionComposition",
            "navigation/session/NavigationSessionComposition.kt", "gps", "by navigationSessions.gps", "ContinuousGpsSession", lazy = true),
        "routeGuidanceDiagnostics" to AssemblyOwner("navigationSessions", "createNavigationSessions", "NavigationSessionComposition",
            "navigation/session/NavigationSessionComposition.kt", "diagnostics", "by navigationSessions.diagnostics", "resources.diagnostics()", lazy = true),
        "routeGuidanceController" to AssemblyOwner("navigationSessions", "createNavigationSessions", "NavigationSessionComposition",
            "navigation/session/NavigationSessionComposition.kt", "guidance", "by navigationSessions.guidance", "RouteGuidanceController", lazy = true),
        "routeCompassSession" to AssemblyOwner("navigationSessions", "createNavigationSessions", "NavigationSessionComposition",
            "navigation/session/NavigationSessionComposition.kt", "compass", "by navigationSessions.compass", "RouteCompassSession", lazy = true),
        "routeArchiveController" to AssemblyOwner("navigationSessions", "createNavigationSessions", "NavigationSessionComposition",
            "navigation/session/NavigationSessionComposition.kt", "archive", "by navigationSessions.archive", "RouteArchiveController", lazy = true),
        "routeAcquisitionController" to AssemblyOwner("navigationSessions", "createNavigationSessions", "NavigationSessionComposition",
            "navigation/session/NavigationSessionComposition.kt", "acquisition", "by navigationSessions.acquisition", "RouteAcquisitionController", lazy = true),
        "reportPrivacyConsentSession" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createReportPrivacyConsentSession", "= privacyAuthority.createReportPrivacyConsentSession()", "ReportPrivacyConsentSession.also", lazy = false),
        "integratedConsentSession" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createIntegratedConsentSession", "= privacyAuthority.createIntegratedConsentSession()", "IntegratedConsentSession", lazy = false),
        "accountDeletionOperationAuthority" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createAccountDeletionOperationAuthority", "= privacyAuthority.createAccountDeletionOperationAuthority()", "AccountDeletionOperationAuthority", lazy = false),
        "accountDeletionResetStorage" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createAccountDeletionResetStorage", "= privacyAuthority.createAccountDeletionResetStorage()", "AccountDeletionResetStorage", lazy = false),
        "accountDeletionInstallationResetController" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createAccountDeletionInstallationResetController", "= privacyAuthority.createAccountDeletionInstallationResetController()", "AccountDeletionInstallationResetController", lazy = false),
        "accountDeletionRecoveryStore" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createAccountDeletionRecoveryStore", "= privacyAuthority.createAccountDeletionRecoveryStore()", "AccountDeletionRecoveryStore", lazy = false),
        "accountDeletionWorkerStages" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createAccountDeletionWorkerStages", "= privacyAuthority.createAccountDeletionWorkerStages()", "AccountDeletionWorkerStages", lazy = false),
        "accountDeletionNetworkController" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createAccountDeletionNetworkController", "by privacyAuthority.createAccountDeletionNetworkController()", "AccountDeletionNetworkController", lazy = true),
        "accountDeletionEvidenceController" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createAccountDeletionEvidenceController", "by privacyAuthority.createAccountDeletionEvidenceController()", "AccountDeletionEvidenceController", lazy = true),
        "accountDeletionProgressController" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createAccountDeletionProgressController", "by privacyAuthority.createAccountDeletionProgressController()", "AccountDeletionProgressController", lazy = true),
        "privacyStartupRestoreCoordinator" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createPrivacyStartupRestoreCoordinator", "by privacyAuthority.createPrivacyStartupRestoreCoordinator()", "PrivacyStartupRestoreCoordinator", lazy = true),
        "speechOutputStorageCoordinator" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createSpeechOutputStorageCoordinator", "= createPrivacySpeechStorage()", "SpeechOutputStorageCoordinator", adapter = "createPrivacySpeechStorage", lazy = false),
        "accountDeletionRecoveryActorController" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createAccountDeletionRecoveryActorController", "= createPrivacyRecoveryActor()", "AccountDeletionRecoveryActorController", adapter = "createPrivacyRecoveryActor", lazy = false),
        "accountDeletionPreparationController" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createAccountDeletionPreparationController", "= createPrivacyPreparation()", "AccountDeletionPreparationController", adapter = "createPrivacyPreparation", lazy = false),
        "legacyPendingReportQueuePurgeController" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createLegacyPendingReportQueuePurgeController", "= createPrivacyLegacyPurge()", "LegacyPendingReportQueuePurgeController", adapter = "createPrivacyLegacyPurge", lazy = false),
        "accountDeletionRuntimeFenceController" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createAccountDeletionRuntimeFenceController", "by createPrivacyRuntimeFence()", "AccountDeletionRuntimeFenceController", adapter = "createPrivacyRuntimeFence", lazy = true),
        "accountDeletionLocalDataPurger" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createAccountDeletionLocalDataPurger", "by createPrivacyLocalPurge()", "AccountDeletionLocalDataPurger", adapter = "createPrivacyLocalPurge", lazy = true),
        "integratedConsentController" to AssemblyOwner("privacyAuthority", "createPrivacyAuthorityComposition", "PrivacyAuthorityComposition",
            "session/privacy/PrivacyAuthorityComposition.kt", "createIntegratedConsentController", "by createPrivacyConsent()", "IntegratedConsentController", adapter = "createPrivacyConsent", lazy = true),
    )
    private val composedOwners = mapOf(
        "unknownCaptureCoordinator" to ComposedOwner("unknownCaptureCoordinatorLazy", "visionUnknownComposition", "createVisionUnknownComposition",
            "runtime/vision/composition/VisionUnknownComposition.kt", "VisionUnknownComposition", "capture", "= visionUnknownComposition.capture"),
        "unknownServiceOwner" to ComposedOwner("unknownServiceOwnerLazy", "visionUnknownComposition", "createVisionUnknownComposition",
            "runtime/vision/composition/VisionUnknownComposition.kt", "VisionUnknownComposition", "services", "= visionUnknownComposition.services"),
        "primaryDetectorOwner" to ComposedOwner("primaryDetectorOwner", "visionPrimaryComposition", "createVisionPrimaryComposition",
            "runtime/vision/composition/VisionPrimaryComposition.kt", "VisionPrimaryComposition", "detector", "by visionPrimaryComposition.detector"),
    )
    private val domains = listOf(
        Domain("routeAcquisitionController", "navigation/session/RouteAcquisitionController.kt", constructor = "RouteAcquisitionController"),
        Domain("routeArchiveController", "navigation/session/RouteArchiveController.kt", constructor = "RouteArchiveController"),
        Domain("nativeGuidanceCalibrationSession", "navigation/start/NativeGuidanceCalibrationSession.kt", constructor = "NativeGuidanceCalibrationSession"),
        Domain("explicitReportPresentation", "ui/AndroidExplicitReportPresentation.kt", constructor = "AndroidExplicitReportPresentation"),
        Domain("voiceInputController", "voice/input/VoiceInputSessionController.kt", "createVoiceInputController", "VoiceInputSessionController"),
        Domain("voiceInputController.gateway", "voice/input/GatewayVoiceCaptureController.kt", "createVoiceInputController", "VoiceInputSessionController"),
        Domain("voiceInputController.handsFreeRuntime", "voice/input/HandsFreeRuntimeSession.kt", "createVoiceInputController", "VoiceInputSessionController"),
        Domain("voiceInputController.wake", "voice/input/ForegroundWakeInputController.kt", "createVoiceInputController", "VoiceInputSessionController"),
        Domain("gatewaySessionController", "session/gateway/GatewaySessionController.kt", "createGatewaySessionController", "GatewaySessionController"),
        Domain("officialEnvironmentEvidenceStore", "device/environment/OfficialEnvironmentEvidenceStore.kt", constructor = "OfficialEnvironmentEvidenceStore"),
        Domain("officialEnvironmentPreflightController", "device/environment/OfficialEnvironmentPreflightController.kt", constructor = "OfficialEnvironmentPreflightController"),
        Domain("officialEnvironmentRuntimeController", "device/environment/OfficialEnvironmentRuntimeController.kt", constructor = "OfficialEnvironmentRuntimeController"),
        Domain("officialEnvironmentController", "device/environment/OfficialEnvironmentController.kt", constructor = "OfficialEnvironmentController"),
        Domain("officialEnvironmentCameraObserver", "device/environment/OfficialEnvironmentCameraObserver.kt", constructor = "OfficialEnvironmentCameraObserver"),
        Domain("unknownCaptureCoordinator", "runtime/vision/unknown/UnknownCaptureCoordinator.kt", constructor = "UnknownCaptureCoordinator"),
        Domain("unknownServiceOwner", "runtime/vision/unknown/UnknownRuntimeServiceOwner.kt", constructor = "UnknownRuntimeServiceOwner"),
        Domain("nativeWorkflowController", "ui/workflow/NativeWorkflowController.kt", constructor = "NativeWorkflowController"),
        Domain("positioningSessionController", "navigation/session/PositioningSessionController.kt", constructor = "PositioningSessionController"),
        Domain("continuousGpsSession", "navigation/session/ContinuousGpsSession.kt", constructor = "ContinuousGpsSession"),
        Domain("routeGuidanceController", "navigation/session/RouteGuidanceController.kt", constructor = "RouteGuidanceController"),
        Domain("voiceInteractionControllers.commands", "voice/interaction/VoiceCommandInteractionController.kt", "createVoiceInteractionControllers", "VoiceInteractionControllers"),
        Domain("voiceInteractionControllers.destinations", "voice/interaction/DestinationVoiceDialogController.kt", "createVoiceInteractionControllers", "VoiceInteractionControllers"),
        Domain("riskFeedbackComponents.delivery", "feedback/delivery/RiskFeedbackDeliveryController.kt", "createRiskFeedbackComponents", "RiskFeedbackComponents"),
        Domain("educationInteractionController", "ui/workflow/EducationInteractionController.kt", constructor = "EducationInteractionController"),
        Domain("gatewayWalkSessionController", "session/runtime/GatewayWalkSessionController.kt", "createGatewayWalkSessionController", constructor = "GatewayWalkSessionController"),
        Domain("walkSessionResumeController", "session/runtime/WalkSessionResumeController.kt", "createWalkSessionResumeController", constructor = "WalkSessionResumeController"),
        Domain("walkSessionTransitionController", "session/runtime/WalkSessionTransitionController.kt", "createWalkSessionTransitionController", constructor = "WalkSessionTransitionController"),
        Domain("walkSessionCleanupController", "session/runtime/WalkSessionCleanupController.kt", "createWalkSessionCleanupController", constructor = "WalkSessionCleanupController"),
        Domain("walkSessionAdmissionController", "session/runtime/WalkSessionAdmissionController.kt", "createWalkSessionAdmissionController", constructor = "WalkSessionAdmissionController"),
        Domain("walkRuntimeActivationController", "session/runtime/WalkRuntimeActivationController.kt", "createWalkRuntimeActivationController", constructor = "WalkRuntimeActivationController"),
        Domain("runtimeMetricSession", "runtime/metric/RuntimeMetricSessionController.kt", constructor = "RuntimeMetricSessionController"),
        Domain("fieldDiagnosticRecording", "fieldlog/diagnostics/FieldDiagnosticRecordingPipeline.kt", constructor = "FieldDiagnosticRecordingPipeline"),
        Domain("positionFieldDiagnostics", "fieldlog/diagnostics/PositionFieldDiagnosticRecorder.kt", constructor = "PositionFieldDiagnosticRecorder"),
        Domain("firstRunOnboardingController", "session/onboarding/FirstRunOnboardingController.kt", constructor = "FirstRunOnboardingController"),
        Domain("priorityUserProfileController", "session/onboarding/PriorityUserProfileController.kt", constructor = "PriorityUserProfileController"),
        Domain("accountDeletionPreparationController", "session/deletion/runtime/AccountDeletionPreparationController.kt", "createAccountDeletionPreparationController", constructor = "AccountDeletionPreparationController"),
        Domain("accountDeletionNetworkController", "session/deletion/runtime/AccountDeletionNetworkController.kt", "createAccountDeletionNetworkController", constructor = "AccountDeletionNetworkController"),
        Domain("reportCandidateController", "report/runtime/ReportCandidateController.kt", "createReportCandidateController", constructor = "ReportCandidateController"),
        Domain("rawCollectionTransferController", "report/runtime/RawCollectionTransferController.kt", "createRawCollectionTransferController", constructor = "RawCollectionTransferController"),
        Domain("postWalkTransferController", "report/runtime/PostWalkTransferController.kt", "createPostWalkTransferController", constructor = "PostWalkTransferController"),
        Domain("reportQueueDrainController", "report/runtime/ReportQueueDrainController.kt", "createReportQueueDrainController", constructor = "ReportQueueDrainController"),
        Domain("guidancePreparationController", "navigation/start/GuidancePreparationController.kt", constructor = "GuidancePreparationController"),
        Domain("nativePrewalkController", "navigation/start/NativePrewalkController.kt", constructor = "NativePrewalkController"),
        Domain("nativeStartAdmission", "navigation/start/NativeDestinationStartAdmission.kt", constructor = "NativeDestinationStartAdmission"),
        Domain("nativeGuidanceEntryController", "navigation/start/NativeGuidanceEntryController.kt", constructor = "NativeGuidanceEntryController"),
        Domain("accountDeletionEvidenceController", "session/deletion/runtime/AccountDeletionEvidenceController.kt", "createAccountDeletionEvidenceController", constructor = "AccountDeletionEvidenceController"),
        Domain("accountDeletionProgressController", "session/deletion/runtime/AccountDeletionProgressController.kt", "createAccountDeletionProgressController", constructor = "AccountDeletionProgressController"),
        Domain("legacyPendingReportQueuePurgeController", "session/deletion/runtime/LegacyPendingReportQueuePurgeController.kt", "createLegacyPendingReportQueuePurgeController", constructor = "LegacyPendingReportQueuePurgeController"),
        Domain("startupCapabilityCoordinator", "startup/capability/StartupCapabilityCoordinator.kt", constructor = "StartupCapabilityCoordinator"),
        Domain("officialEnvironmentPresentation", "startup/capability/OfficialEnvironmentPresentation.kt", constructor = "OfficialEnvironmentPresentation"),
        Domain("prewalkGuidanceProgress", "startup/capability/PrewalkGuidanceProgress.kt", constructor = "PrewalkGuidanceProgress"),
        Domain("actorSessionLifecycleCoordinator", "session/gateway/ActorSessionLifecycleCoordinator.kt", constructor = "ActorSessionLifecycleCoordinator"),
        Domain("privacyStartupRestoreCoordinator", "session/gateway/PrivacyStartupRestoreCoordinator.kt", constructor = "PrivacyStartupRestoreCoordinator"),
        Domain("accountDeletionLocalDataPurger", "session/deletion/runtime/AccountDeletionLocalDataPurger.kt", "createAccountDeletionLocalDataPurger", constructor = "AccountDeletionLocalDataPurger"),
        Domain("accountDeletionRuntimeFenceController", "session/deletion/runtime/AccountDeletionRuntimeFenceController.kt", "createAccountDeletionRuntimeFenceController", constructor = "AccountDeletionRuntimeFenceController"),
        Domain("speechOutputStorageCoordinator", "feedback/SpeechOutputStorageCoordinator.kt", "createSpeechOutputStorageCoordinator", constructor = "SpeechOutputStorageCoordinator"),
        Domain("developmentQuickStartController", "startup/development/DevelopmentQuickStartController.kt", constructor = "DevelopmentQuickStartController"),
        Domain("userReportInteractionOwner", "report/interaction/UserReportInteractionOwner.kt", "createUserReportInteractionOwner", constructor = "UserReportInteractionOwner"),
        Domain("phoneMountingSession", "session/mounting/PhoneMountingSessionController.kt", constructor = "PhoneMountingSessionController"),
        Domain("phoneMountingInteractionController", "session/mounting/PhoneMountingInteractionController.kt", "createPhoneMountingInteractionController", constructor = "PhoneMountingInteractionController"),
        Domain("walkRuntimeCameraAdmissionController", "session/runtime/WalkRuntimeCameraAdmissionController.kt", "createWalkRuntimeCameraAdmissionController", constructor = "WalkRuntimeCameraAdmissionController"),
        Domain("networkPreferenceController", "session/consent/NetworkPreferenceController.kt", "createNetworkPreferenceController", "NetworkPreferenceController"),
        Domain("walkStopDelivery", "session/safety/WalkRuntimeStopDeliveryController.kt", constructor = "WalkRuntimeStopDeliveryController"),
        Domain("framePipelineController", "runtime/vision/frame/FramePipelineController.kt", constructor = "FramePipelineController"),
        Domain("walkReadinessCoordinator", "session/readiness/WalkReadinessCoordinator.kt", constructor = "WalkReadinessCoordinator"),
        Domain("walkReadinessBlockController", "session/readiness/WalkReadinessBlockController.kt", "createWalkReadinessBlockController", constructor = "WalkReadinessBlockController"),
        Domain("runtimeMetricInvalidation", "runtime/metric/RuntimeMetricInvalidationController.kt", constructor = "RuntimeMetricInvalidationController"),
        Domain("frameSynthesisOwner", "runtime/vision/frame/FrameSynthesisController.kt", constructor = "FrameSynthesisController"),
        Domain("frameRecognition", "runtime/vision/frame/ArFrameRecognitionCapture.kt", constructor = "ArFrameRecognitionCapture"),
        Domain("framePresentation", "runtime/vision/frame/FramePresentationController.kt", constructor = "FramePresentationController"),
        Domain("primaryDetectorInvocationWorker", "runtime/vision/primary/PrimaryDetectorInvocationWorker.kt", constructor = "PrimaryDetectorInvocationWorker"),
        Domain("primaryRuntimeCalibrationOwner", "runtime/vision/primary/PrimaryRuntimeCalibrationOwner.kt", constructor = "PrimaryRuntimeCalibrationOwner"),
        Domain("permissionFlowController", "session/permission/PermissionFlowController.kt", constructor = "PermissionFlowController<FirstRunAsyncLease>"),
        Domain("permissionFlowUi", "session/permission/AndroidPermissionUi.kt", constructor = "AndroidPermissionUi"),
        Domain("primaryDetectorLifecycleController", "runtime/vision/primary/PrimaryDetectorLifecycleController.kt", constructor = "PrimaryDetectorLifecycleController"),
        Domain("runtimeCameraLifecycle", "runtime/camera/lifecycle/RuntimeCameraLifecycleCoordinator.kt", constructor = "RuntimeCameraLifecycleCoordinator"),
        Domain("accountAccessInteractionOwner", "session/account/AccountAccessInteractionOwner.kt", "createAccountAccessInteractionOwner", constructor = "AccountAccessInteractionOwner"),
        Domain("integratedConsentController", "session/consent/IntegratedConsentController.kt", constructor = "IntegratedConsentController"),
        Domain("accountConsentSpeechOwner", "session/account/AccountConsentSpeechOwner.kt", "createAccountConsentSpeechOwner", constructor = "AccountConsentSpeechOwner"),
        Domain("firstRunPresentationCoordinator", "startup/presentation/FirstRunPresentationCoordinator.kt", constructor = "FirstRunPresentationCoordinator"),
        Domain("walkReadinessPresentationController", "startup/presentation/WalkReadinessPresentationController.kt", constructor = "WalkReadinessPresentationController"),
        Domain("startupCapabilityFactsRenderer", "startup/presentation/StartupCapabilityFactsRenderer.kt", constructor = "StartupCapabilityFactsRenderer"),
        Domain("tactileCapture", "navigation/capture/TactileFrameCaptureController.kt", constructor = "TactileFrameCaptureController"),
        Domain("tactileDotDispatch", "navigation/capture/TactileDotReferenceDispatchController.kt", constructor = "TactileDotReferenceDispatchController"),
        Domain("nativePagePresentationController", "ui/NativePagePresentationController.kt", constructor = "NativePagePresentationController"),
        Domain("primaryRuntimeCalibrationCoordinator", "runtime/vision/primary/PrimaryRuntimeCalibrationCoordinator.kt", constructor = "PrimaryRuntimeCalibrationCoordinator"),
        Domain("runtimeMetricPreflight", "runtime/metric/RuntimeMetricPreflightCoordinator.kt", constructor = "RuntimeMetricPreflightCoordinator", strictConstructor = true),
        Domain("runtimeMetricPreflightStore", "runtime/metric/RuntimeMetricPreflightResultStore.kt", constructor = "RuntimeMetricPreflightResultStore", strictConstructor = true),
        Domain("frameMotionEvidence", "runtime/vision/capture/FrameMotionEvidenceComposer.kt", constructor = "FrameMotionEvidenceComposer", strictConstructor = true),
        Domain("nativeRegistrationComposition", "ui/NativeRegistrationScreenComposition.kt", constructor = "NativeRegistrationScreenComposition", strictConstructor = true),
        Domain("startupConfirmationInteraction", "startup/interaction/StartupConfirmationInteractionController.kt", "createStartupConfirmationInteraction", constructor = "StartupConfirmationInteractionController", strictConstructor = true),
        Domain("postLoginDeviceInteraction", "startup/interaction/PostLoginDeviceInteractionController.kt", "createPostLoginDeviceInteraction", constructor = "PostLoginDeviceInteractionController", strictConstructor = true),
        Domain("startupResourceSafetyAdmission", "startup/interaction/StartupResourceSafetyAdmission.kt", "createStartupResourceSafetyAdmission", constructor = "StartupResourceSafetyAdmission", strictConstructor = true),
        Domain("voiceDataSettingsRecovery", "startup/interaction/VoiceDataSettingsRecoveryController.kt", "createVoiceDataSettingsRecovery", constructor = "VoiceDataSettingsRecoveryController", strictConstructor = true),
        Domain("cameraAdvisoryDelivery", "feedback/delivery/CameraNonMetricAdvisoryDeliveryController.kt", constructor = "CameraNonMetricAdvisoryDeliveryController", strictConstructor = true),
        Domain("destinationSearchController", "navigation/DestinationSearchController.kt", constructor = "DestinationSearchController", strictConstructor = true),
        Domain("primaryDetectorOwner", "runtime/vision/primary/PrimaryDetectorExecutionOwner.kt", constructor = "PrimaryDetectorExecutionOwner", strictConstructor = true),
    )

    private val speechNames = setOf("speakNavigation", "dispatchNavigationSpeech", "speakInteraction",
        "speakStatusExplanation", "captureCommandSpeechResponseOwner", "speakCommandResponse",
        "dispatchCommandSpeechResponse", "announceForTalkBack", "recordAcceptedTalkBackAnnouncement",
        "scheduleTalkBackInteraction", "reconcileTalkBackRiskTracks", "ensureFeedbackActuator",
        "processOfflineSpeechReady", "handleOfflineSpeechReady", "rememberCommandSpeechReadyContinuation",
        "clearCommandSpeechReadyContinuationIfOwned", "clearCommandSpeechReadyContinuation",
        "resumeCurrentCommandSpeechAfterReady", "externalFallbackSpeechOutputActive",
        "retireCommandSpeechResponseIfOwned", "cancelCommandSpeechResponse")
    private val destinationNames = mapOf("performDestinationSearch" to "search",
        "requestDestinationSearchLocation" to "requestLocation", "cancelDestinationSearchLocation" to "cancelLocation",
        "destinationSearchCanLoadMore" to "canLoadMore", "isDestinationSearchLeaseCurrent" to "isLeaseCurrent",
        "cancelDestinationSearch" to "cancel", "clearDestinationSearchState" to "clearRequest")
    private val emailNames = setOf("requestEmailAccountOtp", "createEmailAccount", "loginEmailAccount",
        "restoreEmailEnrollmentPartialAtStartup", "emailOtpPartialStillValid", "emailOtpEnrollmentReceipt",
        "accountStateBinding", "currentEmailOtpRetryPayload", "postAccountFailure")
    private val deviceNames = mapOf(
        "prepareStoredMetricDepthRefresh" to "prepareStoredMetricRefresh",
        "clearStoredMetricDepthRefresh" to "clearStoredMetricRefresh",
        "isStoredMetricDepthRefreshContextCurrent" to "isStoredMetricRefreshCurrent",
        "maybeStartStoredMetricDepthRefresh" to "maybeStartStoredMetricRefresh",
        "completeStoredMetricDepthRefresh" to "completeStoredMetricRefresh",
        "postLoginDeviceCheckPassesFeatureGate" to "passesFeatureGate",
        "revalidateCompletedPostLoginDeviceCheckPrerequisites" to "revalidateCompletedPrerequisites",
        "bindPostLoginDeviceCheckSession" to "bindSession",
        "startPostLoginDeviceCheckRuntime" to "startRuntime",
        "maybeContinuePostLoginDeviceCheck" to "maybeContinue",
        "evaluatePostLoginDeviceCheck" to "evaluate",
        "currentPostLoginDeviceCheckObservation" to "observation",
        "failPostLoginDeviceCheckForSessionChange" to "failForSessionChange",
        "cancelPostLoginDeviceCheckRuntime" to "cancel",
        "isPostLoginDeviceCheckBindingCurrent" to "isRunningBindingCurrent",
        "startPostLoginDeviceCheckFromPrimaryAction" to "beginFromPrimaryAction",
        "handlePostLoginDeviceCheckPrimaryAction" to "handlePrimaryAction",
        "beginPostLoginDeviceCheckFromUserAction" to "beginFromUserAction",
        "handlePostLoginDeviceCheckPermissionResult" to "handlePermissionResult",
        "completePostLoginDeviceCheckPermissionObservation" to "completePermissionObservation",
        "currentPostLoginDeviceCheckSessionBinding" to "currentSessionBinding",
        "isPostLoginDeviceCheckSessionBindingLive" to "isSessionBindingLive",
        "isPostLoginDeviceCheckSnapshotBindingLive" to "isSnapshotBindingLive",
    )
    private val probeNames = mapOf("startPostLoginLocationFixProbe" to "startLocation",
        "cancelPostLoginLocationFixProbe" to "cancelLocation", "startPostLoginCameraPipelinePreflight" to "startCamera",
        "stopPostLoginCameraPipelinePreflight" to "stopCamera", "cancelPostLoginDeviceCheckFunctionalProbes" to "cancelFunctionalProbes")

    fun function(main: String, signature: String): String {
        val name = Regex("fun\\s+(\\w+)").find(signature)?.groupValues?.get(1) ?: error("Missing function name: $signature")
        return when {
            name in speechNames -> {
                check(member(main, name).contains("speechOutputController.$name(")) { "Main lost output delegation: $name" }
                member(speech, name)
            }
            name in destinationNames -> {
                val target = destinationNames.getValue(name)
                val caller = memberOrNull(main, name) ?: if (name == "performDestinationSearch") member(main, "buildContentView") else error("Missing search caller: $name")
                check(caller.contains("destinationSearchController.$target(")) { "Main lost search delegation: $name" }
                domains.single { it.property == "destinationSearchController" }.verifyComposition(main)
                member(destination, target)
            }
            name in emailNames -> {
                if (name in setOf("requestEmailAccountOtp", "createEmailAccount", "loginEmailAccount", "restoreEmailEnrollmentPartialAtStartup")) {
                    check(member(main, name).contains("emailAccountController.$name(")) { "Main lost account delegation: $name" }
                } else {
                    check(member(main, "requestEmailAccountOtp").contains("emailAccountController.requestEmailAccountOtp("))
                    check(member(main, "createEmailAccountController").contains("EmailAccountController("))
                }
                member(email, name)
            }
            name in deviceNames -> {
                val target = deviceNames.getValue(name)
                check(member(main, name).contains("postLoginDeviceCheckController.$target(")) { "Main lost device delegation: $name" }
                member(device, target)
            }
            name in probeNames -> {
                val target = probeNames.getValue(name)
                check(member(main, name).contains("postLoginDeviceProbes.$target(")) { "Main lost native probe delegation: $name" }
                member(deviceProbes, target)
            }
            signature.startsWith("override fun ") && name in setOf("onError", "onResults", "onReadyForSpeech") -> {
                val domain = domains.single { it.property == "voiceInputController" }
                domain.verifyComposition(main)
                val listener = member(domain.source, "buildVoiceCommandRecognitionListener")
                ReportStaticSourceInspector.functionBlock(listener, "override fun $name")
            }
            name == "requestRoute" -> {
                val domain = domains.single { it.property == "routeAcquisitionController" }
                domain.verifyComposition(main)
                check(member(main, name).contains("routeAcquisitionController.requestRoute("))
                check(member(main, name, occurrence = 1).contains("destinationCoordinateEvidence, connectionRecoveryAttempted)"))
                member(domain.source, name, occurrence = 1)
            }
            else -> {
                val signatureOffset = main.indexOf(signature)
                val earlier = if (signatureOffset < 0) 0 else Regex("(?m)^(?:    )?(?:private |internal |public |override |inline |suspend |tailrec )*fun\\s+" +
                    Regex.escape(name) + "\\s*\\(").findAll(main).count { it.range.first < signatureOffset - 4 }
                val original = memberOrNull(main, name, earlier)
                if (original != null) {
                    val calls = domains.mapNotNull { domain ->
                        val target = Regex(Regex.escape(domain.property) + "\\.(\\w+)\\s*\\(").find(original)?.groupValues?.get(1)
                        target?.let { domain to it }
                    }
                    // Expand only a single direct call, never a guard, lambda or mixed Main effects.
                    if (calls.size == 1 && original.length < 1_200 &&
                        isDirectForwarder(original, calls.single().first.property, calls.single().second)
                    ) {
                        val (domain, target) = calls.single()
                        domain.verifyComposition(main)
                        resolveForwarded(domain, target, earlier)
                    } else original
                } else {
                    val candidates = domains.filter { memberOrNull(it.source, name) != null }
                    val owners = candidates.filterNot { child -> candidates.any { parent ->
                        child.property.startsWith(parent.property + ".") &&
                            member(parent.source, name).contains(child.property.removePrefix(parent.property + ".") + ".$name(")
                    } }
                    check(owners.size == 1) { "Expected one actual source owner for $name, found ${owners.map { it.property }}" }
                    val domain = owners.single()
                    domain.verifyComposition(main)
                    resolveForwarded(domain, name, 0)
                }
            }
        }
    }

    fun composition(main: String, property: String): String {
        val assembled = assembledOwners[property]
        if (assembled != null) {
            assembled.verify(main, property)
            return assembled.body()
        }
        val domain = domains.single { it.property == property }
        domain.verifyComposition(main)
        return composedOwners[property]?.body()
            ?: if (domain.factory != null) {
                if (domain.strictConstructor) factory(main, domain.factory) else member(main, domain.factory)
            } else MainActivityRefactorSource.property(main, property)
    }

    private fun resolveForwarded(domain: Domain, name: String, occurrence: Int): String {
        // The two Main tactile-dot overloads delegate to one renamed purpose-owned entry.
        val ownerOccurrence = if (domain.property == "tactileDotDispatch" && name == "maybeSpeak") {
            check(memberOrNull(domain.source, name, occurrence = 1) == null) { "Ambiguous tactile-dot owner entry: $name" }
            0
        } else occurrence
        val owned = member(domain.source, name, ownerOccurrence)
        if (domain.property == "voiceInputController") {
            val forwarded = Regex("(?:wake|gateway|handsFreeRuntime)\\.(\\w+)\\s*\\(").find(owned)
            if (forwarded != null && owned.length < 400 &&
                isDirectForwarder(owned, forwarded.value.substringBefore('.'), forwarded.groupValues[1])
            ) {
                val property = "voiceInputController." + forwarded.value.substringBefore('.')
                val child = property.substringAfterLast('.')
                val childType = when (child) { "gateway" -> "GatewayVoiceCaptureController"; "handsFreeRuntime" -> "HandsFreeRuntimeSession"; else -> "ForegroundWakeInputController" }
                check(MainActivityRefactorSource.property(domain.source, child).contains(childType + "("))
                return member(domains.single { it.property == property }.source, forwarded.groupValues[1])
            }
        }
        if (domain.property == "runtimeCameraLifecycle" && owned.length < 400) {
            val forwarded = Regex("transactions\\.(\\w+)\\s*\\(").find(owned)
            if (forwarded != null && isDirectForwarder(owned, "transactions", forwarded.groupValues[1])) {
                check(MainActivityRefactorSource.property(domain.source, "transactions").contains("RuntimeArLifecycleTransactions("))
                val transactions = File(ROOT + "runtime/camera/lifecycle/RuntimeArLifecycleTransactions.kt").readText()
                return member(transactions, forwarded.groupValues[1])
            }
        }
        return owned
    }

    private fun isDirectForwarder(declaration: String, owner: String, target: String): Boolean = runCatching {
        val parametersEnd = scopeEnd(declaration, declaration.indexOf('('), expression = false)
        val tail = declaration.substring(parametersEnd)
        val bodyStart = tail.indexOfFirst { it == '=' || it == '{' }
        if (bodyStart < 0) return@runCatching false
        val expression = tail[bodyStart] == '='
        val body = if (expression) tail.substring(bodyStart + 1) else {
            val bodyEnd = scopeEnd(tail, bodyStart, expression = false)
            // Reject continued expressions after the block as well as effects inside it.
            directDeclaration("body\n" + tail.substring(bodyEnd))
            tail.substring(bodyStart + 1, bodyEnd - 1)
        }
        val call = body.trim()
        val prefix = Regex("^(?:return\\s+)?" + Regex.escape(owner) + "\\." +
            Regex.escape(target) + "\\s*\\(").find(call) ?: return@runCatching false
        val callEnd = scopeEnd(call, prefix.range.last, expression = false)
        directDeclaration("call\n" + call.substring(callEnd).trimStart().removePrefix(";"))
        true
    }.getOrDefault(false)

    /** Reads exactly one factory without treating nested overrides as root declarations. */
    fun factory(source: String, name: String): String {
        val declaration = Regex("(?m)^(?:    )?(?:private |internal |public |override |inline |suspend |tailrec )*fun\\s+" +
            Regex.escape(name) + "\\s*\\(").find(source) ?: error("Missing factory: $name")
        val parametersEnd = scopeEnd(source, source.indexOf('(', declaration.range.first), expression = false)
        val assignment = source.indexOf('=', parametersEnd)
        val block = source.indexOf('{', parametersEnd)
        val body = if (assignment >= 0 && (block < 0 || assignment < block)) assignment + 1 else block
        check(body >= 0) { "Missing factory body: $name" }
        val end = scopeEnd(source, body, expression = body != block)
        return source.substring(declaration.range.first, end)
    }

    private fun scopeEnd(source: String, start: Int, expression: Boolean): Int {
        val stack = mutableListOf<Char>()
        var mode = 0 // code, line comment, block comment, string, character, raw string
        var commentDepth = 0
        var seenExpression = false
        var index = start
        while (index < source.length) {
            val c = source[index]
            when (mode) {
                1 -> {
                    if (c == '\n') {
                        mode = 0
                        if (expression && seenExpression && stack.isEmpty()) return index
                    }
                    index += 1
                    continue
                }
                2 -> {
                    if (source.startsWith("/*", index)) { commentDepth += 1; index += 2 }
                    else if (source.startsWith("*/", index)) { commentDepth -= 1; index += 2; if (commentDepth == 0) mode = 0 }
                    else index += 1
                    continue
                }
                3, 4 -> {
                    if (c == '\\') index += 2
                    else { if (c == if (mode == 3) '"' else '\'') mode = 0; index += 1 }
                    continue
                }
                5 -> {
                    if (source.startsWith("\"\"\"", index)) { mode = 0; index += 3 } else index += 1
                    continue
                }
            }
            when {
                source.startsWith("//", index) -> { mode = 1; index += 2; continue }
                source.startsWith("/*", index) -> { mode = 2; commentDepth = 1; index += 2; continue }
                source.startsWith("\"\"\"", index) -> { mode = 5; seenExpression = true; index += 3; continue }
                c == '"' || c == '\'' -> { mode = if (c == '"') 3 else 4; seenExpression = true; index += 1; continue }
                c in "({[" -> { stack += c; seenExpression = true }
                c in ")}]" -> {
                    check(stack.isNotEmpty()) { "Unmatched factory delimiter at $index" }
                    val open = stack.removeAt(stack.lastIndex)
                    check((open == '(' && c == ')') || (open == '{' && c == '}') || (open == '[' && c == ']')) {
                        "Mismatched factory delimiter at $index"
                    }
                    if (!expression && stack.isEmpty()) return index + 1
                }
                c == '\n' && expression && seenExpression && stack.isEmpty() -> return index
                !c.isWhitespace() -> seenExpression = true
            }
            index += 1
        }
        check(expression && seenExpression && stack.isEmpty() && mode in setOf(0, 1)) { "Unterminated factory scope" }
        return source.length
    }

    private fun memberOrNull(source: String, name: String, occurrence: Int = 0): String? {
        val matcher = Regex("(?m)^(?:    )?(?:private |internal |public |override |inline |suspend |tailrec )*fun\\s+" + Regex.escape(name) + "\\s*\\(")
        return if (matcher.findAll(source).drop(occurrence).any()) member(source, name, occurrence) else null
    }

    fun property(source: String, name: String): String {
        // The delegated owner may expose a differently named Lazy holder in Main.
        val actual = when (name) {
            "unknownCaptureCoordinator" -> "unknownCaptureCoordinatorLazy"
            "unknownServiceOwner" -> "unknownServiceOwnerLazy"
            else -> name
        }
        val start = Regex("(?m)^(?:    )?(?:@Volatile )?(?:private |internal |public |lateinit )*(?:val|var)\\s+" + Regex.escape(actual) + "\\b")
            .find(source)?.range?.first ?: error("Missing Main composition property: $actual")
        val end = Regex("(?m)^(?:    )?(?:@Volatile )?(?:private |internal |public |override |lateinit )*(?:fun |val |var |class |data class |enum class |companion object)")
            .find(source, start + 4)?.range?.first ?: source.length
        return source.substring(start, end)
    }

    fun member(source: String, name: String, occurrence: Int = 0): String {
        val start = Regex("(?m)^(?:    )?(?:private |internal |public |override |inline |suspend |tailrec )*fun\\s+" +
            Regex.escape(name) + "\\s*\\(").findAll(source).drop(occurrence).firstOrNull()?.range?.first ?: error("Missing owner function: $name")
        val next = Regex("(?m)^(?:    )?(?:private |internal |public |override |inline |suspend |tailrec )*(?:fun |val |var |class |data class |enum class |companion object)")
            .find(source, start + 4)?.range?.first ?: source.length
        return source.substring(start, next)
    }
}
