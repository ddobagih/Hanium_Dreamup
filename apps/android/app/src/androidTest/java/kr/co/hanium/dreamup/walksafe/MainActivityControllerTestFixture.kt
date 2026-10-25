package kr.co.hanium.dreamup.walksafe

import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kr.co.hanium.dreamup.walksafe.ui.*
import kr.co.hanium.dreamup.walksafe.session.FirstRunAgeBand
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem
import kr.co.hanium.dreamup.walksafe.session.PriorityUserAgeBand
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPractice
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityController
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.reflect.Field
import java.lang.reflect.Proxy
import java.lang.reflect.Modifier
import java.io.DataInputStream
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.inference.DetectorRuntimeSupervisor
import kr.co.hanium.dreamup.walksafe.inference.DetectorWorkClaim
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputController
import kr.co.hanium.dreamup.walksafe.voice.input.VoiceInputSessionController
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.session.NavigationRunState
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningSessionController
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStateMachine
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionActivityLease
import kr.co.hanium.dreamup.walksafe.session.deletion.reset.AccountDeletionInstallationResetController
import kr.co.hanium.dreamup.walksafe.session.deletion.runtime.AccountDeletionWorkerStages

/** Injects the real state owner while tests continue to invoke the actual Main entry points. */
internal object MainActivityControllerTestFixture {
    private val speechFields = setOf(
        "feedbackActuator", "commandSpeechResponseCallbacks", "commandSpeechResponseGeneration",
        "processingSpeechReadyOwner", "completedSpeechReadyOwner", "pendingCommandSpeechReadyContinuation",
        "lastRiskAnnouncementMs", "lastRiskAnnouncement", "lastRiskAnnouncementTrackId",
        "lastRiskAnnouncementRank", "riskAnnouncementHoldUntilMs", "lastNavigationAnnouncementMs",
        "lastNavigationAnnouncement", "navigationAnnouncementHoldUntilMs", "lastAdvisoryAnnouncementMs",
        "lastAdvisoryAnnouncement", "lastInteractionAnnouncementMs", "lastInteractionAnnouncement",
        "pendingTalkBackInteraction",
    )
    private val searchFields = setOf(
        "destinationSearchInFlight", "destinationSearchLocationController", "destinationSearchLocationMessage",
        "destinationSearchGeneration", "destinationSearchQuery", "destinationSearchZeroResultQuery",
        "destinationSearchPage", "destinationSearchProviderHasMore", "destinationSearchOrigin",
        "destinationSearchResults", "pendingVoiceDestinationQuery", "pendingVoiceDestinationPageIndex",
        "destinationSearchVoiceState",
    )
    private val emailFields = setOf(
        "emailEnrollmentPartial", "emailEnrollmentOwnerBindingSha256", "emailEnrollmentStorageBlocked",
        "accountRequestFence", "emailOtpRequestRetryPolicy", "emailOtpDeadline",
    )
    private val cameraFields = mapOf(
        "cameraFallbackRequested" to "requested", "cameraFallbackRunning" to "running",
        "cameraFallbackGeneration" to "generation", "cameraFallbackCleanupPending" to "cleanupPending",
        "cameraXReleaseBlocked" to "releaseBlocked", "cameraFallbackAfterRelease" to "afterRelease",
        "cameraFallbackAfterReleaseFailure" to "afterReleaseFailure",
        "cameraFallbackFrameAnalyzedGeneration" to "frameAnalyzedGeneration",
    )
    private val deviceFields = mapOf(
        "postLoginDeviceCheckSnapshot" to "current",
        "postLoginCameraDependentChecksDeferred" to "cameraDependentChecksDeferred",
        "postLoginDeviceCheckPermissionRequestCode" to "permissionRequestCode",
        "postLoginDeviceCheckPendingPermissionBinding" to "pendingPermissionBinding",
        "postLoginDeviceCheckBackgroundFailurePending" to "backgroundFailurePending",
        "postLoginDeviceCheckOverallTimeout" to "overallTimeout",
        "postLoginResourceWaitThermal" to "resourceWaitThermal",
        "storedMetricDepthRefreshContext" to "storedMetricRefresh",
        "storedMetricDepthRefreshPending" to "storedMetricRefreshPending",
        "storedMetricDepthRefreshRetryAllowed" to "storedMetricRetryAllowed",
    )

    private val routeFields = setOf(
        "activeRouteRequestOwner",
        "activeRouteRequestReason",
        "disconnectedRerouteRecovery",
        "pendingExplicitRouteStart",
        "pendingExplicitRouteStartAfterFixMs",
        "pendingExplicitRouteStartRequestId",
        "pendingExplicitRouteStartTimeout",
        "routeRequestGeneration",
        "routeRequestInFlight",
        "suspendedNavigationResume",
        "tmapFailureGuard",
    )
    private val archiveFields = setOf(
        "routeSnapshotAccountBindingEstablished",
        "routeSnapshotAccountOwner",
        "routeSnapshotExpiryRunnable",
        "routeSnapshotIoLease",
        "routeSnapshotPreparationPending",
        "routeSnapshotPurgeFailed",
        "routeSnapshotResetParticipant",
        "routeSnapshotStore",
        "routeSnapshotWalkScope",
    )
    private val handsFreeRuntimeFields = setOf(
        "handsFreeTranscriberFailureCount",
        "handsFreeVoiceController",
        "handsFreeVoiceDestroyed",
        "handsFreeVoiceModelDirectory",
        "handsFreeVoiceModelGeneration",
        "handsFreeVoiceModelPreparationFailed",
        "handsFreeVoiceModelPreparing",
        "handsFreeVoiceRestartRunnable",
        "handsFreeVoiceServiceRequested",
        "handsFreeVoiceStartRecovery",
        "handsFreeVoiceStartRequestId",
        "pendingHandsFreeVoiceStartFailure",
    )
    private val voiceFields = setOf(
        "deferredVoiceRecognitionFailure",
        "deferredVoiceRecognitionStart",
        "handsFreeCommandWork",
        "handsFreeNavigationDecisionCapture",
        "handsFreeVoiceOwnsCommandPage",
        "oneShotSpeechRecognitionLimited",
        "speechRecognizer",
        "voiceCommandPromptPending",
        "voiceCommandPromptReadyGeneration",
        "voiceRecognitionActive",
        "voiceRecognitionGeneration",
        "voiceRecognitionPurpose",
    )
    private val wakeFields = setOf(
        "foregroundHomeWakeAcknowledgement",
        "foregroundHomeWakeAcknowledgementGeneration",
        "foregroundHomeWakeFailed",
        "foregroundHomeWakeGeneration",
        "foregroundHomeWakeInputGeneration",
        "foregroundHomeWakeOwnerIsCurrent",
        "foregroundHomeWakeProbe",
        "foregroundWakeHandoffInProgress",
    )
    private val gatewayVoiceFields = setOf(
        "activeGatewaySpeechInteraction",
        "gatewaySpeechCall",
        "gatewaySpeechInteractionGeneration",
        "gatewaySpeechNetworkGate",
        "gatewaySpeechPlayer",
        "gatewayVoiceRecorder",
        "gatewayVoiceUploadFile",
    )
    private val gatewaySessionFields = setOf(
        "backendDeviceRestorePending",
        "gatewayRenewalRetryRunnable",
        "inRunGatewayRenewalRunnable",
    )
    private val unknownCaptureFields = setOf(
        "latestUnknownFeedback",
        "unknownContextSequence",
        "unknownFrameCopyInFlight",
        "unknownFrameCopyOwner",
        "unknownProcessingContext",
    )
    private val unknownServiceFields = setOf(
        "latestUnknownLoadObservation",
        "unknownCalibration",
        "unknownLoadControl",
        "unknownLoadRecheckRecorded",
        "unknownLoadingService",
        "unknownRuntimeBinding",
        "unknownRuntimeClosing",
        "unknownRuntimeService",
        "unknownRuntimeStatus",
        "unknownServiceSequence",
    )
    private val runFields = mapOf(
        "routeNavigator" to "navigator",
        "currentDestination" to "destination",
        "isRouteActive" to "active",
        "latestTmapOnRoute" to "onRoute",
        "directionGuidancePauseReason" to "directionPauseReason",
        "routeStartStepCount" to "startStepCount",
    )
    private val evidenceFields = mapOf(
        "officialEnvironmentUserConfirmation" to "userConfirmation",
        "officialEnvironmentGpsEvidence" to "gpsEvidence",
        "officialEnvironmentCameraEvidence" to "cameraEvidence",
        "officialEnvironmentGpsDiagnosticAccuracyMeters" to "gpsDiagnosticAccuracyMeters",
        "officialEnvironmentGpsDiagnosticDetail" to "gpsDiagnosticDetail",
        "officialEnvironmentCameraDiagnosticObservation" to "cameraDiagnosticObservation",
        "officialEnvironmentCameraDiagnosticReason" to "cameraDiagnosticReason",
        "latestRuntimeRawCameraAssessment" to "latestRuntimeRawCameraAssessment",
    )
    private val preflightFields = mapOf(
        "officialEnvironmentCameraPreflightGeneration" to "cameraGeneration",
        "officialEnvironmentCameraPreflightActive" to "cameraActive",
        "officialEnvironmentCameraLastAnalysisMs" to "cameraLastAnalysisMs",
        "officialEnvironmentCameraPreflightLastAnalysisMs" to "cameraLastAnalysisMs",
        "officialEnvironmentCameraPreflightHasPassed" to "cameraHasPassed",
        "officialEnvironmentCameraTimeout" to "timeout",
        "officialEnvironmentCameraPreflightTimeout" to "timeout",
        "officialEnvironmentCameraCleanupPending" to "cameraCleanupPending",
        "officialEnvironmentAfterCameraRelease" to "afterCameraRelease",
        "officialEnvironmentCameraAfterRelease" to "afterCameraRelease",
        "officialEnvironmentCameraStabilityGate" to "stability",
        "officialEnvironmentPreflightPhase" to "phase",
        "officialEnvironmentCameraReadyGraceGeneration" to "readyGraceGeneration",
        "officialEnvironmentReadyGraceGeneration" to "readyGraceGeneration",
        "officialEnvironmentPreflightGeneration" to "gpsGeneration",
        "officialEnvironmentGpsRequest" to "gpsRequest",
    )
    private val environmentRuntimeFields = mapOf(
        "officialEnvironmentRuntimeGuard" to "guard",
        "officialEnvironmentOutputsAllowed" to "outputsAllowed",
        "lastOfficialEnvironmentRuntimeAction" to "lastAction",
        "lastOfficialEnvironmentUnavailableFactors" to "lastUnavailableFactors",
        "officialEnvironmentWatchdogGeneration" to "watchdogGeneration",
    )
    private val resetFields = mapOf(
        "accountDeletionResetCoordinator" to "coordinator",
        "accountDeletionResetJournalStateAtStartup" to "startupJournalState",
        "accountDeletionTerminalCleanupCoordinator" to "terminalCleanup",
    )

    private val nativeFields = mapOf(
        "nativeUiPage" to "nativeUiPage", "pendingNativeUiPage" to "pendingNativeUiPage",
        "pendingUiDestination" to "pendingUiDestination", "nativePrewalkStartEpoch" to "nativePrewalkStartEpoch",
        "nativePhoneMountingCheckRequest" to "nativePhoneMountingCheckRequest", "nativeUiRendering" to "nativeUiRendering",
        "nativeDestinationStartCoordinator" to "startCoordinator",
    )

    private val guidanceFields = setOf(
        "navigationSpeechRetryRunnable",
        "routeLocationStatusResponseGeneration",
        "positionGuidancePaused",
        "lastPositionConfidenceDiagnosticAtMs",
        "lastRouteAlignmentDiagnosticKey",
        "lastRouteAlignmentDiagnosticAtMs",
        "pendingRouteRecoverySpeech",
        "routeGuidanceResumePending",
        "lastAnnouncedRouteDecisionToken",
        "pendingRouteDecisionSpeechToken",
        "routeDecisionSpeechAttempt",
        "routeDecisionSpeechResponseGeneration",
        "routeDecisionSpeechRetryRunnable",
        "routeDeviationHapticDecision",
    )
    private val compassFields = setOf(
        "orientationLocationOwnerActive",
        "lastRouteCompassStatusKey",
        "latestRouteFacingUnavailableReason",
        "pendingRouteCompassRefresh",
        "routeCompassObservedSamples",
        "compassObservationOwner",
        "compassObservationLock",
        "queuedCompassObservation",
        "queuedCompassInvalidation",
        "queuedCompassConsumer",
        "compassSpeechEvidenceRevision",
        "compassInvalidationPending",
    )
    private val gpsFields = setOf(
        "continuousGpsSource",
        "gpsRecoveryRequest",
        "acceptedGnssSamples",
    )
    private val positioningFields = setOf(
        "positioningCoordinator",
        "positioningCollectionBinding",
        "locationCallback",
        "locationCallbackGeneration",
        "locationCollectionOwner",
        "lastLocationCleanupFailure",
        "fusedLocationCancellations",
        "latestTrustedLocation",
        "latestTrustedLocationCovariance",
        "latestRawRouteLocation",
        "latestNavigationTestLocation",
        "pedestrianMotionTracker",
        "gnssQualityObserver",
        "latestGnssQualitySnapshot",
        "pedestrianProfileStore",
        "latestPedestrianProfileLoadStatus",
        "positioningProfileActorId",
        "positioningProfileLoaded",
        "latestWalkingSpeedObservation",
        "latestGpsCourseObservation",
        "latestGpsMotionInputs",
        "gpsMotionInputHistory",
        "lastPositionZuptAtElapsedMs",
        "latestPositionStationaryState",
        "latestPositionStationarySinceMs",
        "latestHeadingDeg",
        "walkingCalibrationSegments",
        "walkingCalibrationScope",
        "latestWalkingCalibrationStep",
    )
    private val walkingCalibrationFields = mapOf(
        "walkingCalibrationSegments" to "segments",
        "walkingCalibrationScope" to "scope",
        "latestWalkingCalibrationStep" to "latestStep",
    )
    private val mountingFields = setOf(
        "explicitPositioningMountConfirmation",
        "latestPhoneMountingCameraAssessment",
        "phoneMountingAppliedFaultSequence",
        "phoneMountingObservationGeneration",
        "phoneMountingObservationSequence",
        "phoneMountingOutputsAllowed",
        "phoneMountingPendingFaultSequence",
        "phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs",
        "phoneMountingRuntimeRetryGeneration",
        "phoneMountingRuntimeState",
        "phoneMountingUserConfirmation",
        "phoneMountingWatchdogGeneration",
        "runtimeCameraHandoffEpoch",
        "runtimeCameraHandoffGeneration",
        "runtimeCameraHandoffStartedAtElapsedRealtimeMs",
    )
    private val walkStopFields = setOf(
        "walkRuntimeSafetyStopCleanup",
        "walkRuntimeSafetyStopCleanupEpoch",
        "walkRuntimeSafetyStopDestroyed",
        "walkRuntimeSafetyStopRetryRunnable",
    )
    private val consentFields = setOf(
        "integratedConsentBootstrapCall",
        "integratedConsentBootstrapReady",
        "integratedConsentCall",
        "integratedConsentClientRevision",
        "integratedConsentConfirmedActorSha256",
        "integratedConsentDraft",
        "integratedConsentExpectedPreviousBackendReceiptSha256",
        "integratedConsentLock",
        "integratedConsentRequestGeneration",
        "integratedConsentRequestInFlight",
        "lastIntegratedConsentAuthoritySnapshot",
        "pendingIntegratedConsentMutation",
    )
    private val networkFields = setOf(
        "gatewayFeatureNetworkLock",
        "lastGatewayFeatureNetworkState",
    )
    private val voiceCommandFields = setOf(
        "guidanceStopConfirmation",
        "guidanceStopConfirmationRouteGeneration",
        "guidanceStopConfirmationWalkEpoch",
        "voiceEndConfirmationPromptPending",
        "walkSessionVoiceControlPolicy",
    )
    private val dialogFields = setOf(
        "destinationOriginAgeNoticeOnce",
        "homeDestinationVoiceDialogLease",
        "walkingDestinationChange",
    )
    private val riskFields = setOf(
        "feedbackRejectionFieldSessionId",
        "lastFeedbackRejectionLifecycleGeneration",
        "lastFeedbackRejectionProducerScope",
        "lastFeedbackRejectionReasons",
        "latestFeedbackDeliveryState",
        "latestSpeechReadyRiskInput",
        "pendingFeedbackTerminalResolution",
    )
    private val referenceFields = setOf(
        "crosswalkReferenceDeliveryPolicy",
        "tactileDotReferenceDeliveryPolicy",
        "tactileDotReferenceSpeech",
    )
    private val startupConfirmationStateFields = mapOf(
        "confirmedStartupCapabilityDecision" to "confirmed",
        "startupCapabilityConfirmationPending" to "pending",
        "startupCapabilityRetryRequiresUserAction" to "retryRequiresUserAction",
        "lastPersistedStartupCapabilityDecision" to "lastPersisted",
    )
    private val voiceSettingsStateFields = mapOf(
        "voiceDataInstallRecheckPending" to "recheckPending",
        "voiceDataInstallRecheckInFlight" to "recheckInFlight",
    )
    private val postLoginInteractionStateFields = mapOf(
        "postLoginDeviceCheckLastLiveStatus" to "lastLiveStatus",
    )
    private val registrationPresenterFields = mapOf(
        "firstRunOnboardingPresenter" to "firstRun",
        "deviceCheckPresenter" to "deviceCheck",
    )
    private val primaryCompositionLazyProperties = setOf("primaryDetectorOwner",
        "primaryRuntimeCalibrationOwner", "primaryDetectorFrameSchedulingOwner", "primaryDetectorInvocationWorker")
    private val unknownCompositionLazyProperties = setOf("unknownServiceOwnerLazy", "unknownCaptureCoordinatorLazy")

    private val startupReadinessFields = mapOf(
        "startupCapabilityDecision" to "decision",
        "startupCapabilityDecisionObservedAtElapsedRealtimeMs" to "observedAtMs",
        "startupCapabilityDecisionEvidenceVersion" to "evidenceVersion",
    )
    private val riskCompositionFields = mapOf(
        "feedbackPolicy" to "policy", "tactileFrameCoordinator" to "frameCoordinator",
    )


    private val arFields = mapOf(
        "session" to "session", "frameProvider" to "provider",
        "arSessionPurpose" to "purpose", "arSessionGeneration" to "generation",
        "runtimeArSessionCleanupOwnership" to "cleanupOwnership",
        "runtimeArSessionCleanupOwnerToken" to "cleanupOwnerToken",
        "lastRuntimeArSessionCloseFailure" to "lastCloseFailure",
    )
    private val primaryFields = mapOf(
        "frameDetector" to "detector", "detectorGeneration" to "frameGeneration",
        "detectorLoadGeneration" to "loadGeneration", "detectorClosing" to "closing",
        "detectorAvailable" to "available", "detectionInFlight" to "workInFlight",
        "adaptiveInferencePacing" to "pacing", "detectorStallWatchdog" to "watchdog",
        "detectorExecutor" to "detectorExecutor", "deviceTuningExecutor" to "tuningExecutor",
    )
    private val deletionOperationFields = mapOf(
        "accountDeletionLock" to "lock", "accountDeletionRequestGeneration" to "generation",
        "accountDeletionRequestInFlight" to "inFlight", "accountDeletionCall" to "call",
    )
    private val deletionStoreFields = mapOf(
        "accountDeletionIntentFence" to "intentFence",
        "accountDeletionPreparedConfirmationRecovery" to "preparedConfirmationRecovery",
        "accountDeletionDualAuthority" to "dualAuthority",
        "accountDeletionAuthorityAtStartup" to "authorityAtStartup",
        "accountDeletionFallbackMarker\$delegate" to "fallbackMarker\$delegate",
        "accountDeletionFallbackPresentAtStartup" to "fallbackPresentAtStartup",
        "accountDeletionStartupFallbackState" to "startupFallbackState",
        "accountDeletionRemoteResumeBlocked" to "remoteResumeBlocked",
    )
    private val deletionActorFields = mapOf(
        "accountDeletionRev0ReauthenticationRequestId" to "reauthenticationRequestId",
        "accountDeletionLegacyRev0GeneralReauthenticationRequestId" to "legacyGeneralReauthenticationRequestId",
    )

    private val educationFields = mapOf(
        "priorityUserEducationInFlight" to "priorityUserEducationInFlight",
        "priorityUserEducationPlaybackIsPracticeNecessity" to "priorityUserEducationPlaybackIsPracticeNecessity",
        "priorityUserPracticeInFlight" to "priorityUserPracticeInFlight",
        "priorityUserPracticeAttemptToken" to "priorityUserPracticeAttemptToken",
        "priorityUserTrainingGeneration" to "priorityUserTrainingGeneration",
    )

    private val gatewayWalkFields = mapOf(
        "gatewayWalkTakeoverPromptPending" to "gatewayWalkTakeoverPromptPending",
        "gatewayWalkTakeoverPromptOperationId" to "gatewayWalkTakeoverPromptOperationId",
        "gatewayWalkStartConfirmationToken" to "gatewayWalkStartConfirmationToken",
        "gatewayWalkResumeRevalidationPending" to "gatewayWalkResumeRevalidationPending",
        "gatewayWalkRenewalRetries" to "gatewayWalkRenewalRetries",
        "gatewayWalkRenewalRunnable" to "gatewayWalkRenewalRunnable",
    )

    private val walkResumeFields = mapOf(
        "walkSessionResumePromptPending" to "walkSessionResumePromptPending",
        "walkSessionResumeRetryRequiresUserAction" to "walkSessionResumeRetryRequiresUserAction",
        "walkSessionResumeActivationToken" to "walkSessionResumeActivationToken",
        "walkSessionResumeConfirmationToken" to "walkSessionResumeConfirmationToken",
    )

    // These controls belong to the published readiness panel but have no Main publication flag.
    private val readinessPanelFields = setOf(
        "postLoginDeviceCheckWakePhraseInstructionText", "postLoginDeviceCheckHapticQuestionText",
        "postLoginDeviceCheckSettingsButton", "voiceDataInstallButton",
        "postLoginDeviceCheckWakePhraseStartButton", "postLoginDeviceCheckHapticConfirmButton",
        "postLoginDeviceCheckHapticRejectButton",
    )

    private val metricObservations = setOf(
        "runtimeMetricPreflightGeneration", "metricPreflightOwner", "metricPreflightPostLoginBinding",
    )

    private val metricFields = mapOf(
        "metricDistanceCapabilityOverride" to "metricDistanceCapabilityOverride",
        "metricPreflightLifecycleGeneration" to "metricPreflightLifecycleGeneration",
        "metricPreflightFirstRunLease" to "metricPreflightFirstRunLease",
        "metricPreflightArSessionGeneration" to "metricPreflightArSessionGeneration",
        "pendingMetricPreflightPermissionGeneration" to "pendingMetricPreflightPermissionGeneration",
        "runtimeMetricPreflightSession" to "runtimeMetricPreflightSession",
        "metricPreflightTerminalDispatchedGeneration" to "metricPreflightTerminalDispatchedGeneration",
        "runtimeMetricOutputAllowed" to "runtimeMetricOutputAllowed",
        "runtimeMetricActivationSession" to "runtimeMetricActivationSession",
        "runtimeMetricFrameDiagnosticAtMs" to "runtimeMetricFrameDiagnosticAtMs",
        "runtimeMetricLastValidFrameAtMs" to "runtimeMetricLastValidFrameAtMs",
        "runtimeMetricLastFrameTimestampNanos" to "runtimeMetricLastFrameTimestampNanos",
        "runtimeVisualDepthFrameTracking" to "runtimeVisualDepthFrameTracking",
        "runtimeMetricInitialNavigationStartPending" to "runtimeMetricInitialNavigationStartPending",
    )

    private val diagnosticFields = mapOf(
        "captureLog" to "captureLog",
        "fieldSessionHeartbeat" to "fieldSessionHeartbeat",
        "fieldHeartbeatDestroyed" to "fieldHeartbeatDestroyed",
        "fieldSessionHeartbeatExecutor" to "fieldSessionHeartbeatExecutor",
    )

    private val onboardingFields = mapOf(
        "firstRunOnboardingSnapshot" to "state",
    )

    private val profileFields = mapOf(
        "priorityUserOnboardingPolicy" to "currentPolicy",
        "priorityUserOnboardingActorId" to "actorId",
        "priorityUserStorageBlockedActorHashes" to "blockedActorHashes",
        "nativeCompletedFirstRunRecord" to "nativeCompletedRecord",
    )

    private val candidateFields = mapOf(
        "reportCandidatePolicy" to "reportCandidatePolicy",
        "explicitReportConfirmationPolicy" to "explicitReportConfirmationPolicy",
        "explicitReportConfirmationTimeoutGeneration" to "explicitReportConfirmationTimeoutGeneration",
        "reportLocationStartScheduled" to "reportLocationStartScheduled",
        "latestReportCandidateStatus" to "latestReportCandidateStatus",
        "latestExplicitReportOutput" to "latestExplicitReportOutput",
        "latestExplicitReportCaptureValidation" to "latestExplicitReportCaptureValidation",
        "latestExplicitReportImage" to "latestExplicitReportImage",
        "latestExplicitReportGateState" to "latestExplicitReportGateState",
        "latestExplicitReportCapturedAtMs" to "latestExplicitReportCapturedAtMs",
        "latestExplicitReportUnmeasuredDamageCapturedAtMs" to "latestExplicitReportUnmeasuredDamageCapturedAtMs",
        "latestExplicitReportConflictingStateCapturedAtMs" to "latestExplicitReportConflictingStateCapturedAtMs",
    )

    private val rawTransferFields = mapOf(
        "rawRuntimeWindow" to "rawRuntimeWindow",
        "rawDetectionDueButInFlightDropCount" to "rawDetectionDueButInFlightDropCount",
        "rawCollectionUploadWalkId" to "rawCollectionUploadWalkId",
        "lastLoggedRawCollectionOutcomes" to "lastLoggedRawCollectionOutcomes",
        "rawCollectionExecutor" to "executor",
    )

    private val postWalkFields = mapOf(
        "postWalkTransferCharging" to "postWalkTransferCharging",
        "postWalkPowerConnectedReceiverRegistered" to "postWalkPowerConnectedReceiverRegistered",
        "postWalkWalkInProgressRetryWalkId" to "postWalkWalkInProgressRetryWalkId",
        "postWalkWalkInProgressRetry" to "postWalkWalkInProgressRetry",
    )

    private val reportQueueFields = mapOf(
        "reportQueueDrainExecutor" to "reportQueueDrainExecutor",
        "reportQueueDrainLock" to "reportQueueDrainLock",
        "reportQueueDrainTrigger" to "reportQueueDrainTrigger",
        "reportQueueDrainMovementGeneration" to "reportQueueDrainMovementGeneration",
        "reportQueueDrainNetworkGeneration" to "reportQueueDrainNetworkGeneration",
        "reportQueueDrainWalkState" to "reportQueueDrainWalkState",
        "reportQueueDrainWalkSessionId" to "reportQueueDrainWalkSessionId",
    )

    private val guidancePreparationFields = mapOf(
        "pendingGuidancePreparationSpeech" to "pending",
        "nativeGuidancePreparationSpeechFailureOwner" to "failureOwner",
        "nativeGuidanceSpeechFailure" to "visibleFailure",
        "guidancePreparationRequestedAtMs" to "requestedAtMs",
        "guidancePreparationSpeechRunnable" to "poll",
        "installedRouteStartSpeechCoordinator" to "installedStart",
    )

    private val nativePrewalkFields = mapOf(
        "nativePreparationContinuationPosted" to "continuationPosted",
        "nativePrewalkActorId" to "actorId",
        "nativePrewalkConfirmationPosted" to "confirmationPosted",
        "nativePrewalkDiagnosticState" to "diagnosticState",
        "nativePhoneMountingCheckRequestId" to "sensorRequestId",
        "nativePrewalkFailureSpeech" to "failureSpeech",
        "nativePrewalkAdjustmentSpeech" to "adjustmentSpeech",
    )

    private val deletionProgressFields = mapOf(
        "accountDeletionLocalPurgeInFlightRequestId" to "localPurgeInFlightRequestId",
    )

    private val legacyCleanupFields = mapOf(
        "legacyPendingReportQueuePurgeLock" to "lock",
        "legacyPendingReportQueuePurgeInFlight" to "inFlight",
        "legacyPendingReportQueuePurgeSucceeded" to "succeeded",
        "legacyPendingReportQueuePurgeWaiters" to "waiters",
        "reportCleanupActivityToken" to "activityToken",
        "reportCleanupGeneration" to "generation",
        "reportCleanupDestroyed" to "destroyed",
    )

    private val startupCoordinatorFields = mapOf(
        "applyingRuntimeReadiness" to "applyingRuntimeReadiness",
    )

    private val prewalkProgressFields = mapOf(
        "prewalkGuidanceStage" to "prewalkGuidanceStage",
    )

    private val permissionFields = mapOf(
        "walkSessionPermissionRequestInFlight" to "walkSessionPermissionRequestInFlight",
        "walkSessionPermissionRequestAttempted" to "walkSessionPermissionRequestAttempted",
        "walkSessionPermissionRequestCode" to "walkSessionPermissionRequestCode",
        "permissionRequestLeases" to "permissionRequestLeases",
        "permissionRequestGenerationByPurpose" to "permissionRequestGenerationByPurpose",
        "nextPermissionRequestCode" to "nextPermissionRequestCode",
        "permissionRecoveryGate" to "permissionRecoveryGate",
        "initialAppPermissionExitRequired" to "initialAppPermissionExitRequired",
        "initialAppPermissionReRequestedThisLaunch" to "initialAppPermissionReRequestedThisLaunch",
    )

    private val permissionUiFields = mapOf(
        "initialAppPermissionExitDialog" to "requiredExitDialog",
    )

    private val primaryLifecycleFields = mapOf(
        "detectorConfigLoaded" to "configLoaded",
        "detectorModelKeyForReports" to "reportModelKey",
        "detectorLoadedModelKey" to "loadedModelKey",
        "detectorModelFallbackUsed" to "fallbackUsed",
        "runningDetectorVerification" to "verification",
        "detectorLoadReason" to "loadReason",
        "detectorLoadAttempted" to "loadAttempted",
        "detectorLoadInFlight" to "loadInFlight",
        "detectorStatusText" to "statusText",
        "fixedFixturePerformanceCheckRunning" to "fixedFixtureRunning",
    )

    private val runtimeCameraFields = mapOf(
        "installRequested" to "installRequested",
        "pendingSessionStartAfterInstall" to "pendingSessionStartAfterInstall",
        "arCoreAvailabilityRequestGeneration" to "arCoreAvailabilityRequestGeneration",
        "arCoreAvailabilityCheckPending" to "arCoreAvailabilityCheckPending",
        "pendingCameraFallbackStart" to "pendingCameraFallbackStart",
    )

    private val accountConsentSpeechFields = mapOf(
        "accountConsentSpeechResults" to "completedResults",
        "accountConsentSpeechResultContext" to "resultContext",
        "accountConsentSpeechKey" to "playingKey",
        "accountConsentSpeechGeneration" to "generation",
    )

    private val readinessPresentationFields = mapOf(
        "walkReadinessExpanded" to "expanded",
    )

    private val tactileCaptureFields = mapOf(
        "latestConsumedTactileResult" to "latestConsumedResult",
    )

    private data class UiBinding(val presenter: String, val slot: String, val child: String, val target: NativeUiPublication.Target?)
    private val uiBindings = mapOf(
        "firstRunNoticeToggleButton" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "firstRunNoticeToggleButton", NativeUiPublication.Target.FIRST_RUN_NOTICE_TOGGLE_BUTTON),
        "firstRunProgressBar" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "firstRunProgressBar", NativeUiPublication.Target.FIRST_RUN_PROGRESS_BAR),
        "firstRunIntegratedConsentDisclosureText" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "firstRunIntegratedConsentDisclosureText", NativeUiPublication.Target.FIRST_RUN_INTEGRATED_CONSENT_DISCLOSURE_TEXT),
        "firstRunDisclosureToggleButton" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "firstRunDisclosureToggleButton", NativeUiPublication.Target.FIRST_RUN_DISCLOSURE_TOGGLE_BUTTON),
        "firstRunConsentCountText" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "firstRunConsentCountText", NativeUiPublication.Target.FIRST_RUN_CONSENT_COUNT_TEXT),
        "firstRunConsentAllButton" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "firstRunConsentAllButton", NativeUiPublication.Target.FIRST_RUN_CONSENT_ALL_BUTTON),
        "firstRunIntegratedConsentSaveButton" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "firstRunIntegratedConsentSaveButton", NativeUiPublication.Target.FIRST_RUN_INTEGRATED_CONSENT_SAVE_BUTTON),
        "firstRunOnboardingControls" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "firstRunOnboardingControls", NativeUiPublication.Target.FIRST_RUN_ONBOARDING_CONTROLS),
        "priorityUserEducationAgreeButton" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "priorityUserEducationAgreeButton", NativeUiPublication.Target.PRIORITY_USER_EDUCATION_AGREE_BUTTON),
        "firstRunPhonePostureControls" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "firstRunPhonePostureControls", NativeUiPublication.Target.FIRST_RUN_PHONE_POSTURE_CONTROLS),
        "accountConsentAllCheck" to UiBinding("accountAccessPresenter", "panel", "accountConsentAllCheck", NativeUiPublication.Target.ACCOUNT_CONSENT_ALL_CHECK),
        "accountConsentSummaryText" to UiBinding("accountAccessPresenter", "panel", "accountConsentSummaryText", NativeUiPublication.Target.ACCOUNT_CONSENT_SUMMARY_TEXT),
        "accountConsentContinueButton" to UiBinding("accountAccessPresenter", "panel", "accountConsentContinueButton", NativeUiPublication.Target.ACCOUNT_CONSENT_CONTINUE_BUTTON),
        "accountRequestOtpButton" to UiBinding("accountAccessPresenter", "panel", "accountRequestOtpButton", NativeUiPublication.Target.ACCOUNT_REQUEST_OTP_BUTTON),
        "accountCreateButton" to UiBinding("accountAccessPresenter", "panel", "accountCreateButton", NativeUiPublication.Target.ACCOUNT_CREATE_BUTTON),
        "accountLoginButton" to UiBinding("accountAccessPresenter", "panel", "accountLoginButton", NativeUiPublication.Target.ACCOUNT_LOGIN_BUTTON),
        "accountSignupControls" to UiBinding("accountAccessPresenter", "panel", "accountSignupControls", NativeUiPublication.Target.ACCOUNT_SIGNUP_CONTROLS),
        "privacyConsentStatusText" to UiBinding("settingsPrivacyPresenter", "settings", "privacyConsentStatusText", NativeUiPublication.Target.PRIVACY_CONSENT_STATUS_TEXT),
        "reportPrivacyConsentButton" to UiBinding("settingsPrivacyPresenter", "settings", "reportPrivacyConsentButton", NativeUiPublication.Target.REPORT_PRIVACY_CONSENT_BUTTON),
        "automaticReportConsentButton" to UiBinding("settingsPrivacyPresenter", "settings", "automaticReportConsentButton", NativeUiPublication.Target.AUTOMATIC_REPORT_CONSENT_BUTTON),
        "trainingReuseConsentButton" to UiBinding("settingsPrivacyPresenter", "settings", "trainingReuseConsentButton", NativeUiPublication.Target.TRAINING_REUSE_CONSENT_BUTTON),
        "accountDeletionStatusText" to UiBinding("settingsPrivacyPresenter", "settings", "accountDeletionStatusText", NativeUiPublication.Target.ACCOUNT_DELETION_STATUS_TEXT),
        "privacyControls" to UiBinding("settingsPrivacyPresenter", "settings", "privacyControls", NativeUiPublication.Target.PRIVACY_CONTROLS),
        "nativeVoiceControls" to UiBinding("nativeScreenPresenter", "publishedSearch", "nativeVoiceControls", NativeUiPublication.Target.NATIVE_VOICE_CONTROLS),
        "nativeHomeRestrictionText" to UiBinding("nativeHomeUiPresenter", "gridPanel", "nativeHomeRestrictionText", NativeUiPublication.Target.NATIVE_HOME_RESTRICTION_TEXT),
        "accountAccessStatusText" to UiBinding("accountAccessPresenter", "publishedStatus", "accountAccessStatusText", NativeUiPublication.Target.ACCOUNT_ACCESS_STATUS_TEXT),
        "accountEmailInput" to UiBinding("accountAccessPresenter", "publishedInputs", "accountEmailInput", NativeUiPublication.Target.ACCOUNT_EMAIL_INPUT),
        "accountDateOfBirthInput" to UiBinding("accountAccessPresenter", "publishedInputs", "accountDateOfBirthInput", NativeUiPublication.Target.ACCOUNT_DATE_OF_BIRTH_INPUT),
        "accountPasswordInput" to UiBinding("accountAccessPresenter", "publishedInputs", "accountPasswordInput", NativeUiPublication.Target.ACCOUNT_PASSWORD_INPUT),
        "accountPasswordConfirmationInput" to UiBinding("accountAccessPresenter", "publishedInputs", "accountPasswordConfirmationInput", NativeUiPublication.Target.ACCOUNT_PASSWORD_CONFIRMATION_INPUT),
        "accountOtpInput" to UiBinding("accountAccessPresenter", "publishedInputs", "accountOtpInput", NativeUiPublication.Target.ACCOUNT_OTP_INPUT),
        "accountAccessControls" to UiBinding("accountAccessPresenter", "publishedRoot", "accountAccessControls", NativeUiPublication.Target.ACCOUNT_ACCESS_CONTROLS),
        "mobileNetworkPreferenceButton" to UiBinding("settingsPrivacyPresenter", "publishedMobilePreference", "mobileNetworkPreferenceButton", NativeUiPublication.Target.MOBILE_NETWORK_PREFERENCE_BUTTON),
        "backendFieldTokenInput" to UiBinding("settingsPrivacyPresenter", "publishedGatewayInput", "backendFieldTokenInput", NativeUiPublication.Target.BACKEND_FIELD_TOKEN_INPUT),
        "backendAuthApplyButton" to UiBinding("settingsPrivacyPresenter", "publishedGatewayAction", "backendAuthApplyButton", NativeUiPublication.Target.BACKEND_AUTH_APPLY_BUTTON),
        "nativeSettingsHomeButton" to UiBinding("settingsPrivacyPresenter", "publishedHomeAction", "nativeSettingsHomeButton", NativeUiPublication.Target.NATIVE_SETTINGS_HOME_BUTTON),
        "homeCardGrid" to UiBinding("nativeHomeUiPresenter", "gridPanel", "homeCardGrid", NativeUiPublication.Target.HOME_CARD_GRID),
        "nativeHomeCompassText" to UiBinding("nativeHomeUiPresenter", "compassPanel", "nativeHomeCompassText", NativeUiPublication.Target.NATIVE_HOME_COMPASS_TEXT),
        "nativeHomeRouteStatusText" to UiBinding("nativeHomeUiPresenter", "routePanel", "nativeHomeRouteStatusText", NativeUiPublication.Target.NATIVE_HOME_ROUTE_STATUS_TEXT),
        "productPurposeText" to UiBinding("firstRunOnboardingPresenter", "publishedRegistration", "productPurposeText", NativeUiPublication.Target.PRODUCT_PURPOSE_TEXT),
        "firstRunOnboardingStatusText" to UiBinding("firstRunOnboardingPresenter", "publishedRegistration", "firstRunOnboardingStatusText", NativeUiPublication.Target.FIRST_RUN_ONBOARDING_STATUS_TEXT),
        "firstRunWaitingCard" to UiBinding("firstRunOnboardingPresenter", "publishedRegistration", "firstRunWaitingCard", NativeUiPublication.Target.FIRST_RUN_WAITING_CARD),
        "priorityUserOnboardingStatusText" to UiBinding("firstRunOnboardingPresenter", "publishedEducation", "priorityUserOnboardingStatusText", NativeUiPublication.Target.PRIORITY_USER_ONBOARDING_STATUS_TEXT),
        // These real panel children have no separate publication target.
        "priorityUserEducationBodyText" to UiBinding("firstRunOnboardingPresenter", "publishedEducation", "priorityUserEducationBodyText", null),
        "priorityUserEducationProgressText" to UiBinding("firstRunOnboardingPresenter", "publishedEducation", "priorityUserEducationProgressText", null),
        "firstRunPhonePostureText" to UiBinding("firstRunOnboardingPresenter", "publishedEducation", "firstRunPhonePostureText", null),
        "priorityUserSafePlaceButton" to UiBinding("firstRunOnboardingPresenter", "publishedEducation", "priorityUserSafePlaceButton", null),
        "priorityUserEducationButton" to UiBinding("firstRunOnboardingPresenter", "publishedEducation", "priorityUserEducationButton", null),
        "priorityUserPracticeNecessityButton" to UiBinding("firstRunOnboardingPresenter", "publishedEducation", "priorityUserPracticeNecessityButton", null),
        "priorityUserResetButton" to UiBinding("firstRunOnboardingPresenter", "publishedEducation", "priorityUserResetButton", null),
        "firstRunPhonePostureButton" to UiBinding("firstRunOnboardingPresenter", "publishedEducation", "firstRunPhonePostureButton", NativeUiPublication.Target.FIRST_RUN_PHONE_POSTURE_BUTTON),
        "officialEnvironmentStatusText" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "officialEnvironmentStatusText", NativeUiPublication.Target.OFFICIAL_ENVIRONMENT_STATUS_TEXT),
        "officialEnvironmentConfirmButton" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "officialEnvironmentConfirmButton", NativeUiPublication.Target.OFFICIAL_ENVIRONMENT_CONFIRM_BUTTON),
        "phoneMountingStatusText" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "phoneMountingStatusText", NativeUiPublication.Target.PHONE_MOUNTING_STATUS_TEXT),
        "phoneMountingChestConfirmButton" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "phoneMountingChestConfirmButton", NativeUiPublication.Target.PHONE_MOUNTING_CHEST_CONFIRM_BUTTON),
        "phoneMountingNecklaceConfirmButton" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "phoneMountingNecklaceConfirmButton", NativeUiPublication.Target.PHONE_MOUNTING_NECKLACE_CONFIRM_BUTTON),
        "startupCapabilityText" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "startupCapabilityText", NativeUiPublication.Target.STARTUP_CAPABILITY_TEXT),
        "postLoginDeviceCheckLiveStatusText" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "postLoginDeviceCheckLiveStatusText", NativeUiPublication.Target.POST_LOGIN_DEVICE_CHECK_LIVE_STATUS_TEXT),
        "startupMetricPreflightButton" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "startupMetricPreflightButton", NativeUiPublication.Target.STARTUP_METRIC_PREFLIGHT_BUTTON),
        "startupCapabilityConfirmButton" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "startupCapabilityConfirmButton", NativeUiPublication.Target.STARTUP_CAPABILITY_CONFIRM_BUTTON),
        "priorityUserOnboardingControls" to UiBinding("firstRunOnboardingPresenter", "publishedEducation", "priorityUserOnboardingControls", NativeUiPublication.Target.PRIORITY_USER_ONBOARDING_CONTROLS),
        "permissionDenialSummaryText" to UiBinding("firstRunOnboardingPresenter", "publishedPermission", "permissionDenialSummaryText", NativeUiPublication.Target.PERMISSION_DENIAL_SUMMARY_TEXT),
        "permissionDenialConfirmButton" to UiBinding("firstRunOnboardingPresenter", "publishedPermission", "permissionDenialConfirmButton", NativeUiPublication.Target.PERMISSION_DENIAL_CONFIRM_BUTTON),
        "permissionDenialSettingsButton" to UiBinding("firstRunOnboardingPresenter", "publishedPermission", "permissionDenialSettingsButton", NativeUiPublication.Target.PERMISSION_DENIAL_SETTINGS_BUTTON),
        "permissionDenialPanel" to UiBinding("firstRunOnboardingPresenter", "publishedPermission", "permissionDenialPanel", NativeUiPublication.Target.PERMISSION_DENIAL_PANEL),
        "walkReadinessSummaryText" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "walkReadinessSummaryText", NativeUiPublication.Target.WALK_READINESS_SUMMARY_TEXT),
        "walkReadinessToggleButton" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "walkReadinessToggleButton", NativeUiPublication.Target.WALK_READINESS_TOGGLE_BUTTON),
        "walkReadinessControls" to UiBinding("firstRunOnboardingPresenter", "publishedReadiness", "walkReadinessControls", NativeUiPublication.Target.WALK_READINESS_CONTROLS),
        "voiceReportButton" to UiBinding("nativeScreenPresenter", "publishedVoice", "voiceReportButton", NativeUiPublication.Target.VOICE_REPORT_BUTTON),
        "gatewayVoiceStatusText" to UiBinding("nativeScreenPresenter", "publishedVoice", "gatewayVoiceStatusText", NativeUiPublication.Target.GATEWAY_VOICE_STATUS_TEXT),
        "destinationQueryInput" to UiBinding("nativeScreenPresenter", "publishedSearch", "destinationQueryInput", NativeUiPublication.Target.DESTINATION_QUERY_INPUT),
        "destinationSearchButton" to UiBinding("nativeScreenPresenter", "publishedSearch", "destinationSearchButton", NativeUiPublication.Target.DESTINATION_SEARCH_BUTTON),
        "destinationCancelButton" to UiBinding("nativeScreenPresenter", "publishedSearch", "destinationCancelButton", NativeUiPublication.Target.DESTINATION_CANCEL_BUTTON),
        "destinationSearchResultsContainer" to UiBinding("nativeScreenPresenter", "publishedSearch", "destinationSearchResultsContainer", NativeUiPublication.Target.DESTINATION_SEARCH_RESULTS_CONTAINER),
        "destinationMoreButton" to UiBinding("nativeScreenPresenter", "publishedSearch", "destinationMoreButton", NativeUiPublication.Target.DESTINATION_MORE_BUTTON),
        "routeButton" to UiBinding("nativeScreenPresenter", "publishedSearch", "routeButton", NativeUiPublication.Target.ROUTE_BUTTON),
        "routeDeviationNewRouteButton" to UiBinding("nativeScreenPresenter", "publishedSearch", "routeDeviationNewRouteButton", NativeUiPublication.Target.ROUTE_DEVIATION_NEW_ROUTE_BUTTON),
        "routeDeviationRecheckButton" to UiBinding("nativeScreenPresenter", "publishedSearch", "routeDeviationRecheckButton", NativeUiPublication.Target.ROUTE_DEVIATION_RECHECK_BUTTON),
        "routeDeviationEndButton" to UiBinding("nativeScreenPresenter", "publishedSearch", "routeDeviationEndButton", NativeUiPublication.Target.ROUTE_DEVIATION_END_BUTTON),
        "offRouteNoticeText" to UiBinding("nativeScreenPresenter", "publishedSearch", "offRouteNoticeText", NativeUiPublication.Target.OFF_ROUTE_NOTICE_TEXT),
        "routeDeviationActions" to UiBinding("nativeScreenPresenter", "publishedSearch", "routeDeviationActions", NativeUiPublication.Target.ROUTE_DEVIATION_ACTIONS),
        "nativeDestinationConfirmationText" to UiBinding("nativeScreenPresenter", "publishedGuidance", "nativeDestinationConfirmationText", NativeUiPublication.Target.NATIVE_DESTINATION_CONFIRMATION_TEXT),
        "nativeDestinationStartButton" to UiBinding("nativeScreenPresenter", "publishedGuidance", "nativeDestinationStartButton", NativeUiPublication.Target.NATIVE_DESTINATION_START_BUTTON),
        "nativeGuidanceStatusText" to UiBinding("nativeScreenPresenter", "publishedGuidance", "nativeGuidanceStatusText", NativeUiPublication.Target.NATIVE_GUIDANCE_STATUS_TEXT),
        "nativeGuidanceMeasurementText" to UiBinding("nativeScreenPresenter", "publishedGuidance", "nativeGuidanceMeasurementText", NativeUiPublication.Target.NATIVE_GUIDANCE_MEASUREMENT_TEXT),
        "nativeGuidanceRetryButton" to UiBinding("nativeScreenPresenter", "publishedGuidance", "nativeGuidanceRetryButton", NativeUiPublication.Target.NATIVE_GUIDANCE_RETRY_BUTTON),
        "nativeGuidanceCancelButton" to UiBinding("nativeScreenPresenter", "publishedGuidance", "nativeGuidanceCancelButton", NativeUiPublication.Target.NATIVE_GUIDANCE_CANCEL_BUTTON),
        "routeDeviationSection" to UiBinding("nativeScreenPresenter", "publishedSearch", "routeDeviationSection", NativeUiPublication.Target.ROUTE_DEVIATION_SECTION),
        "runtimeControls" to UiBinding("nativeScreenPresenter", "publishedSearch", "runtimeControls", NativeUiPublication.Target.RUNTIME_CONTROLS),
    )

    private fun uiPublication(main: MainActivity): NativeUiPublication =
        restorePureZeroArgument(main, "nativeUiPublication") as NativeUiPublication

    private fun uiPresenterSlot(main: MainActivity, name: String): Pair<Any, Field> {
        if (name in registrationPresenterFields) {
            val composition = lazyOwner(main, "nativeRegistrationComposition")
            return composition to raw(composition.javaClass, registrationPresenterFields.getValue(name))
        }
        return main to raw(MainActivity::class.java, name)
    }

    private fun uiSlot(main: MainActivity, binding: UiBinding): Pair<Any, Field> {
        val presenter = if (binding.presenter in setOf("nativeScreenPresenter", "accountAccessPresenter")) {
            lazyOwner(main, binding.presenter)
        } else requireNotNull(uiPresenterSlot(main, binding.presenter).let { (owner, slot) -> slot.get(owner) }) {
            "Actual UI presenter was not bound: ${binding.presenter}"
        }
        val panel = requireNotNull(raw(presenter.javaClass, binding.slot).get(presenter)) {
            "Actual UI purpose binding was not published: ${binding.presenter}.${binding.slot}"
        }
        return panel to raw(panel.javaClass, binding.child)
    }

    /** ART exposes canonical THE_ONE and a compatibility alias; JVM uses theUnsafe. */
    private fun fixtureUnsafe(): sun.misc.Unsafe {
        val type = sun.misc.Unsafe::class.java
        val canonicalName = if (System.getProperty("java.vm.name") == "Dalvik") "THE_ONE" else "theUnsafe"
        val canonicalField = type.getDeclaredField(canonicalName)
        check(canonicalField.type == type && Modifier.isStatic(canonicalField.modifiers) &&
            Modifier.isFinal(canonicalField.modifiers)) { "Unexpected Unsafe singleton contract: $canonicalName" }
        val canonical = canonicalField.apply { isAccessible = true }.get(null) as sun.misc.Unsafe
        check(type.declaredFields.filter { it.type == type && Modifier.isStatic(it.modifiers) }.all {
            it.apply { isAccessible = true }.get(null) === canonical
        }) { "Ambiguous Unsafe singleton identities" }
        return canonical
    }

    /** Preserve partial field-injection fixtures with real SDK views and actual purpose models. */
    private fun setUiView(main: MainActivity, binding: UiBinding, value: Any?) {
        if (value != null) {
            check(value is View) { "Expected the original SDK View for ${binding.child}" }
            val lazyPresenter = binding.presenter in setOf("nativeScreenPresenter", "accountAccessPresenter")
            val presenterReference = if (lazyPresenter) null else uiPresenterSlot(main, binding.presenter)
            val presenterSlot = presenterReference?.second
            val presenterOwner = presenterReference?.first
            var presenter = if (lazyPresenter) lazyOwner(main, binding.presenter) else presenterSlot!!.get(presenterOwner)
            if (presenter == null) {
                check(System.getProperty("java.vm.name") == "Dalvik") { "A real Android UI binding must be provided to this JVM fixture" }
                val context = value.context
                val style = lazyOwner(main, "viewStyle") as WalkSafeViewStyle
                val unsafe = fixtureUnsafe()
                presenter = when (binding.presenter) {
                    "firstRunOnboardingPresenter" -> {
                        val panel = unsafe.allocateInstance(OnboardingReadinessPanel::class.java) as OnboardingReadinessPanel
                        raw(panel.javaClass, "context").set(panel, context)
                        raw(panel.javaClass, "style").set(panel, style)
                        // Match every neutral backing initializer skipped by allocateInstance.
                        // Actual widgets still come from each purpose-view fixture; no readiness is published here.
                        raw(panel.javaClass, "progressSegments").set(panel, mutableListOf<View>())
                        raw(panel.javaClass, "ageButtons").set(panel, mutableMapOf<FirstRunAgeBand, Button>())
                        raw(panel.javaClass, "integratedConsentButtons").set(panel, mutableMapOf<IntegratedConsentItem, Button>())
                        raw(panel.javaClass, "consentCards").set(panel, mutableMapOf<IntegratedConsentItem, LinearLayout>())
                        raw(panel.javaClass, "consentClauseTexts").set(panel, mutableMapOf<IntegratedConsentItem, TextView>())
                        raw(panel.javaClass, "waitingDots").set(panel, mutableListOf<View>())
                        raw(panel.javaClass, "priorityAgeButtons").set(panel, mutableMapOf<PriorityUserAgeBand, Button>())
                        raw(panel.javaClass, "practiceButtons").set(panel, mutableMapOf<PriorityUserPractice, Button>())
                        raw(panel.javaClass, "deviceCheckValues").set(panel, linkedMapOf<String, TextView>())
                        check(presenterOwner is NativeRegistrationScreenComposition)
                        // Production bindRegistration creates both presenters for this same panel.
                        raw(presenterOwner.javaClass, "deviceCheck").set(presenterOwner, DeviceCheckPresenter(panel, style))
                        FirstRunOnboardingPresenter(context, style, panel)
                    }
                    else -> error("Supply the actual ${binding.presenter} before mutating its UI target")
                }
                presenterSlot!!.set(presenterOwner, presenter)
            }
            val purpose = raw(presenter.javaClass, binding.slot)
            if (purpose.get(presenter) == null) {
                if (presenter is FirstRunOnboardingPresenter) {
                    val publish = when (binding.slot) {
                        "publishedRegistration" -> "publishRegistrationTargets"
                        "publishedEducation" -> "publishEducationTargets"
                        "publishedPermission" -> "publishPermissionTargets"
                        else -> "publishReadinessTargets"
                    }
                    MethodHandles.privateLookupIn(presenter.javaClass, MethodHandles.lookup())
                        .findVirtual(presenter.javaClass, publish, MethodType.methodType(Void.TYPE)).invokeWithArguments(presenter)
                } else {
                    check(presenter is NativeScreenPresenter) { "Supply the original purpose panel for ${binding.presenter}" }
                    val unsafe = fixtureUnsafe()
                    val panel = unsafe.allocateInstance(DestinationGuidancePanel::class.java) as DestinationGuidancePanel
                    raw(panel.javaClass, "context").set(panel, value.context)
                    raw(panel.javaClass, "style").set(panel, lazyOwner(main, "viewStyle"))
                    val publish = "publish" + binding.slot.removePrefix("published") + "Targets"
                    MethodHandles.privateLookupIn(presenter.javaClass, MethodHandles.lookup())
                        .findVirtual(presenter.javaClass, publish, MethodType.methodType(Void.TYPE, DestinationGuidancePanel::class.java))
                        .invokeWithArguments(presenter, panel)
                }
            }
        }
        if (value != null || binding.target == null || uiPublication(main).isPublished(binding.target)) {
            val (panel, field) = uiSlot(main, binding)
            field.set(panel, value)
        }
        val publication = uiPublication(main)
        binding.target?.let { target ->
            if (value != null) publication.publish(target)
            else (raw(publication.javaClass, "published").get(publication) as BooleanArray)[target.ordinal] = false
        }
    }

    private val speechStorageFields = setOf(
        "speechOutputCollection",
        "speechOutputCollectionCreationLock",
        "speechOutputAccountPurgeVerified",
        "speechOutputResetParticipant",
        "speechOutputStorageAuthority",
        "speechOutputAdmissionToken",
        "speechOutputAccountDeletionIntent",
        "speechOutputAccountDeletionToken",
    )

    private val developmentQuickStartFields = setOf(
        "developmentQuickStartRequested",
        "developmentQuickStartDeadlineMs",
        "developmentQuickStartLoginAttempted",
        "developmentQuickStartAuthenticatedActorId",
        "developmentQuickStartPermissionsRequested",
        "developmentQuickStartConsentRequested",
        "developmentQuickStartRunnable",
    )

    private val userReportInteractionFields = setOf(
        "restoredUserReportStatusFilter",
        "userReportAuthorityExpiryRunnable",
    )

    private val frameSynthesisFields = mapOf(
        "visualFrameTracker" to "visualFrameTracker",
        "objectDepthPipeline" to "objectDepthPipeline",
        "visualGeometryVersion" to "visualGeometryVersion",
        "visualTrackingBudget" to "visualTrackingBudget",
        "currentVisualTrackingBudgetNs" to "currentVisualTrackingBudgetNs",
        "lastVisualFrameKey" to "lastVisualFrameKey",
        "lastRuntimeCameraFrameId" to "lastRuntimeCameraFrameId",
        "currentObjectDepthPipeline" to "currentObjectDepthPipeline",
        "latestVisualTrackingStatus" to "latestVisualTrackingStatus",
        "latestDetectionSnapshot" to "latestDetectionSnapshot",
    )

    private val framePresentationFields = mapOf(
        "lastCameraScreenFrameUiMs" to "lastCameraScreenFrameUiMs",
        "lastOverlayUpdateMs" to "lastOverlayUpdateMs",
        "lastUiUpdateMs" to "lastUiUpdateMs",
        "latestVisualOverlayFrameId" to "latestVisualOverlayFrameId",
    )

    private val frameRecognitionFields = mapOf(
        "runtimeRecognitionRejectDiagnosticAtMs" to "runtimeRecognitionRejectDiagnosticAtMs",
    )

    private val primarySchedulingFields = mapOf(
        "lastDetectionCaptureFrameId" to "lastCaptureFrameId",
        "lastDetectionCaptureGeneration" to "lastCaptureGeneration",
        "lastDetectionCaptureArSessionGeneration" to "lastCaptureArGeneration",
    )

    private val primaryCalibrationFields = mapOf(
        "runtimeCalibration" to "session",
        "runtimeTuningPreparing" to "preparing",
        "runtimeTuningGeneration" to "generation",
        "runtimeActiveCandidate" to "activeCandidate",
        "runtimeActiveProfile" to "activeProfile",
        "runtimeSelectionBinding" to "selectionBinding",
        "runtimeRestoreAttemptedBinding" to "restoreAttemptedBinding",
        "runtimeTuningSchedule" to "schedule",
        "detectorCloseAfterCalibration" to "detectorCloseAfterCalibration",
        "runtimeOldDetectorReleasePending" to "oldDetectorReleasePending",
        "runtimeAfterOldDetectorRelease" to "afterOldDetectorRelease",
    )

    fun field(name: String) = OwnerField(name)

    fun voiceInput(main: MainActivity): VoiceInputSessionController = voiceOwner(main) as VoiceInputSessionController

    fun positioning(main: MainActivity): PositioningSessionController =
        lazyOwner(main, "positioningSessionController") as PositioningSessionController

    fun replaceActuator(main: MainActivity, actuator: AndroidFeedbackActuator?) {
        controller(main, "speechOutputStorageCoordinator")
        val controller = speech(main)
        MethodHandles.privateLookupIn(SpeechOutputController::class.java, MethodHandles.lookup())
            .findVirtual(SpeechOutputController::class.java, "setFeedbackActuator",
                MethodType.methodType(Void.TYPE, AndroidFeedbackActuator::class.java))
            .bindTo(controller).invokeWithArguments(actuator)
    }

    /** Preserve the original setter's route and speech invalidation before snapshot publication. */
    fun publishDeviceSnapshot(main: MainActivity, snapshot: PostLoginDeviceCheckSnapshot) {
        controller(main, "speechOutputStorageCoordinator")
        MethodHandles.privateLookupIn(PostLoginDeviceCheckController::class.java, MethodHandles.lookup())
            .findVirtual(PostLoginDeviceCheckController::class.java, "publish",
                MethodType.methodType(Void.TYPE, PostLoginDeviceCheckSnapshot::class.java))
            .bindTo(device(main)).invokeWithArguments(snapshot)
    }

    private fun device(main: MainActivity): PostLoginDeviceCheckController {
        val slot = raw(MainActivity::class.java, "postLoginDeviceCheckController\$delegate")
        val holder = slot.get(main) as? Lazy<*>
        if (holder?.isInitialized() == true) return holder.value as PostLoginDeviceCheckController
        if (System.getProperty("java.vm.name") == "Dalvik" && holder != null)
            return holder.value as PostLoginDeviceCheckController
        // JVM fixtures cannot call Android Context getters. Keep the real
        // core constructor, actor reader and publication effects, with no native probes/resources.
        val facts = object : PostLoginDeviceCheckFactsReader {
            override fun actor() = invokeMain(main, "capturePostLoginDeviceCheckActorFacts",
                PostLoginDeviceCheckActorFacts::class.java) as PostLoginDeviceCheckActorFacts
            override fun hardware(): PostLoginDeviceCheckHardwareFacts = error("Native hardware was not supplied by this fixture")
            override fun resultBinding(actorId: String): PostLoginDeviceCheckResultBinding? =
                error("Native saved-result binding was not supplied by this fixture")
        }
        val effects = port(PostLoginDeviceCheckEffects::class.java) { name -> when (name) {
            "invalidateRouteResponseLease" -> invokeMain(main, "invalidateRouteSnapshotResponseLease", Void.TYPE)
            "invalidateSpeechStorageLease" -> invokeMain(main, "invalidateSpeechOutputStorageBinding", Void.TYPE)
            else -> error("Unexpected device effect in a passive JVM fixture: $name")
        } }
        val probes = port(PostLoginDeviceCheckProbeSuite::class.java) { name -> when (name) {
            "getSignals" -> PostLoginDeviceCheckProbeSignals(PostLoginDeviceCheckSignal.PENDING,
                PostLoginDeviceCheckSignal.PENDING, PostLoginDeviceCheckSignal.PENDING, PostLoginMetricDepthState.PENDING)
            "getMetricPreflightStarted", "getCapabilityProbeStarted", "getStoredBindingValidationPending" -> false
            else -> error("Unexpected native probe in a passive JVM fixture: $name")
        } }
        val controller = PostLoginDeviceCheckController(facts,
            port(PostLoginDeviceCheckSavedResults::class.java) { error("Unexpected saved-result access: $it") },
            probes, port(PostLoginDeviceCheckPermissionPort::class.java) { error("Unexpected permission effect: $it") },
            effects, object : PostLoginDeviceCheckScheduler {
                override fun schedule(delayMs: Long, action: () -> Unit): DeviceCheckScheduledTask =
                    error("Unexpected device timer in a passive JVM fixture")
            }, PostLoginDeviceCheckController.Config(
                developmentQuickStartEnabled = invokeMain(main, "getDevelopmentQuickStartEnabled", Boolean::class.javaPrimitiveType!!) as Boolean,
                developmentGateBypassed = invokeMain(main, "getDevelopmentPostLoginDeviceCheckBypassed", Boolean::class.javaPrimitiveType!!) as Boolean,
            ))
        slot.set(main, lazyOf(controller))
        return controller
    }

    private fun invokeMain(main: MainActivity, name: String, result: Class<*>): Any? =
        MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, name, MethodType.methodType(result))
            .bindTo(main).invokeWithArguments()

    @Suppress("UNCHECKED_CAST")
    private fun <T> port(type: Class<T>, answer: (String) -> Any?): T =
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, _ -> answer(method.name) } as T

    private fun speech(main: MainActivity): Any {
        val slot = raw(MainActivity::class.java, "speechOutputController")
        slot.get(main)?.let { return it }
        lazyHolder(main, "permissionFlowController")
        lazyHolder(main, "voiceSpeechPresentation")
        return MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, "createSpeechOutputController",
                MethodType.methodType(SpeechOutputController::class.java))
            .bindTo(main).invokeWithArguments().also { slot.set(main, it) }
    }

    private fun lazyOwner(main: MainActivity, property: String): Any = requireNotNull(lazyHolder(main, property).value)

    private val nonSynchronizedOwners = setOf("nativeRegistrationComposition", "accountAccessPresenter", "accountAccessInteractionOwner", "accountConsentSpeechOwner", "viewStyle", "nativeScreenPresenter", "nativePagePresentationController", "nativeWorkflowController", "voiceUiEffects", "voiceCallbacks", "voiceSpeechPresentation", "voiceInputPresentation", "permissionFlowUi", "permissionFlowController", "postWalkRetryHandler", "priorityUserProfileController", "firstRunOnboardingController", "educationInteractionController", "cameraReleaseHandler", "postLoginDeviceCheckController", "postLoginDeviceCheckBindingFactory", "officialEnvironmentEvidenceStore", "officialEnvironmentRuntimeController", "officialEnvironmentPreflightController", "officialEnvironmentController", "officialEnvironmentCameraObserver", "officialEnvironmentCameraSource", "officialEnvironmentGpsSource", "privacyStartupRestoreCoordinator", "actorSessionLifecycleCoordinator", "guidancePreparationController", "nativeStartAdmission", "nativePrewalkController", "nativeGuidanceEntryController")

    private data class PrivacyAnchor(val property: String, val factory: String, val onMain: Boolean, val isLazy: Boolean)
    private val privacyAnchors = listOf(
        PrivacyAnchor("accountDeletionResetStorage", "createAccountDeletionResetStorage", false, false),
        PrivacyAnchor("accountDeletionInstallationResetController", "createAccountDeletionInstallationResetController", false, false),
        PrivacyAnchor("speechOutputStorageCoordinator", "createPrivacySpeechStorage", true, false),
        PrivacyAnchor("reportPrivacyConsentSession", "createReportPrivacyConsentSession", false, false),
        PrivacyAnchor("integratedConsentSession", "createIntegratedConsentSession", false, false),
        PrivacyAnchor("accountDeletionWorkerStages", "createAccountDeletionWorkerStages", false, false),
        PrivacyAnchor("accountDeletionRecoveryStore", "createAccountDeletionRecoveryStore", false, false),
        PrivacyAnchor("integratedConsentController", "createPrivacyConsent", true, true),
        PrivacyAnchor("accountDeletionOperationAuthority", "createAccountDeletionOperationAuthority", false, false),
        PrivacyAnchor("accountDeletionRecoveryActorController", "createPrivacyRecoveryActor", true, false),
        PrivacyAnchor("accountDeletionPreparationController", "createPrivacyPreparation", true, false),
        PrivacyAnchor("accountDeletionNetworkController", "createAccountDeletionNetworkController", false, true),
        PrivacyAnchor("accountDeletionLocalDataPurger", "createPrivacyLocalPurge", true, true),
        PrivacyAnchor("accountDeletionRuntimeFenceController", "createPrivacyRuntimeFence", true, true),
        PrivacyAnchor("legacyPendingReportQueuePurgeController", "createPrivacyLegacyPurge", true, false),
        PrivacyAnchor("accountDeletionEvidenceController", "createAccountDeletionEvidenceController", false, true),
        PrivacyAnchor("accountDeletionProgressController", "createAccountDeletionProgressController", false, true),
        PrivacyAnchor("privacyStartupRestoreCoordinator", "createPrivacyStartupRestoreCoordinator", false, true),
    )
    private val navigationLazyProperties = mapOf(
        "positioningSessionController" to "positioning", "continuousGpsSession" to "gps",
        "routeGuidanceDiagnostics" to "diagnostics", "routeGuidanceController" to "guidance",
        "routeCompassSession" to "compass", "routeArchiveController" to "archive",
        "routeAcquisitionController" to "acquisition",
    )

    private fun actualComposition(main: MainActivity, property: String, factory: String): Any {
        val slot = raw(MainActivity::class.java, property)
        return slot.get(main) ?: requireNotNull(invokeMain(main, factory, slot.type)).also { slot.set(main, it) }
    }
    private fun privacyComposition(main: MainActivity) = actualComposition(main, "privacyAuthority", "createPrivacyAuthorityComposition")
    private fun navigationComposition(main: MainActivity) = actualComposition(main, "navigationSessions", "createNavigationSessions")

    private fun aliasBacking(property: String, isLazy: Boolean): Field? = try {
        raw(MainActivity::class.java, property + if (isLazy) "\$delegate" else "")
    } catch (failure: NoSuchFieldException) { if (!isLazy) throw failure else null }

    /** Link existing references only; a disagreement never replaces another actual owner. */
    private fun reconcileAlias(main: MainActivity, composition: Any, graphName: String, property: String, isLazy: Boolean): Any? {
        val graphSlot = raw(composition.javaClass, graphName)
        val mainSlot = aliasBacking(property, isLazy)
        val fromGraph = graphSlot.get(composition)
        val fromMain = mainSlot?.get(main)
        check(fromGraph == null || fromMain == null || fromGraph === fromMain) { "Different actual owners: $property" }
        val actual = fromGraph ?: fromMain
        if (actual != null) {
            if (fromGraph == null) graphSlot.set(composition, actual)
            if (mainSlot != null && fromMain == null) mainSlot.set(main, actual)
            if (isLazy && mainSlot == null) {
                val name = "get" + property.replaceFirstChar { it.uppercase() } + "\$delegate"
                val observed = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                    .findStatic(MainActivity::class.java, name, MethodType.methodType(Any::class.java, MainActivity::class.java))
                    .invokeWithArguments(main)
                check(observed === actual) { "Accessor lost the actual Lazy: $property" }
            }
        }
        return actual
    }

    private fun privacyAnchor(main: MainActivity, property: String): Any {
        val graph = privacyComposition(main)
        val index = privacyAnchors.indexOfFirst { it.property == property }
        check(index >= 0) { "Unknown privacy anchor: $property" }
        // Reject a target mismatch before restoring any earlier constructor anchor.
        val requested = privacyAnchors[index]
        reconcileAlias(main, graph, property, property, requested.isLazy)
        for (entry in privacyAnchors.take(index + 1)) {
            if (reconcileAlias(main, graph, entry.property, entry.property, entry.isLazy) != null) continue
            when (entry.property) {
                "speechOutputStorageCoordinator" -> speech(main)
                "integratedConsentSession" -> restorePureZeroArgument(main, "integratedConsentClient")
                "accountDeletionPreparationController" -> restorePureZeroArgument(main, "accountDeletionClient")
            }
            val slot = raw(graph.javaClass, entry.property)
            val value = if (entry.onMain) invokeMain(main, entry.factory, slot.type) else {
                MethodHandles.privateLookupIn(graph.javaClass, MethodHandles.lookup())
                    .findVirtual(graph.javaClass, entry.factory, MethodType.methodType(slot.type)).invokeWithArguments(graph)
            }
            check(slot.get(graph) === value) { "Factory must publish its same actual anchor: ${entry.property}" }
            reconcileAlias(main, graph, entry.property, entry.property, entry.isLazy)
        }
        return requireNotNull(raw(graph.javaClass, property).get(graph))
    }

    private fun navigationHolder(main: MainActivity, property: String): Lazy<*> {
        val graph = navigationComposition(main)
        // These seven actual Lazy are constructed together at the original eager graph anchor.
        for ((alias, actual) in navigationLazyProperties) reconcileAlias(main, graph, actual, alias, true)
        when (property) {
            "positioningSessionController", "routeGuidanceController", "routeCompassSession" -> {
                lazyHolder(main, "routeCallbackScheduler")
                lazyHolder(main, "guidancePreparationController")
            }
            "routeArchiveController" -> { lazyHolder(main, "routePresentation"); lazyHolder(main, "routeCallbackScheduler") }
            "routeAcquisitionController" -> {
                lazyHolder(main, "routePresentation"); lazyHolder(main, "routeCallbackScheduler")
                lazyHolder(main, "destinationSearchController")
            }
        }
        return raw(graph.javaClass, navigationLazyProperties.getValue(property)).get(graph) as Lazy<*>
    }

    /** Root slots remain actual graph references; a direct fixture injection changes both aliases. */
    private fun graphAlias(main: MainActivity, name: String): Pair<Any, Field>? {
        if (name == "navigationRunState") {
            val graph = navigationComposition(main)
            return graph to raw(graph.javaClass, "run")
        }
        val privacy = privacyAnchors.firstOrNull { (if (it.isLazy) it.property + "\$delegate" else it.property) == name }
        if (privacy != null) {
            val graph = privacyComposition(main)
            reconcileAlias(main, graph, privacy.property, privacy.property, privacy.isLazy)
            return graph to raw(graph.javaClass, privacy.property)
        }
        val navigation = navigationLazyProperties.entries.firstOrNull { it.key + "\$delegate" == name }
        if (navigation != null) {
            navigationHolder(main, navigation.key)
            val graph = navigationComposition(main)
            return graph to raw(graph.javaClass, navigation.value)
        }
        return null
    }
    private fun setGraphAlias(main: MainActivity, name: String, value: Any?): Boolean {
        val alias = graphAlias(main, name) ?: return false
        alias.second.set(alias.first, value)
        if (name != "navigationRunState") {
            val lazy = name.endsWith("\$delegate")
            val property = name.removeSuffix("\$delegate")
            aliasBacking(property, lazy)?.set(main, value)
        }
        return true
    }

    private fun lazyHolder(main: MainActivity, property: String): Lazy<*> {
        if (privacyAnchors.any { it.property == property && it.isLazy }) return privacyAnchor(main, property) as Lazy<*>
        if (property in navigationLazyProperties) return navigationHolder(main, property)
        if (property in primaryCompositionLazyProperties) return actualPrimaryCompositionLazy(main, property)
        if (property in unknownCompositionLazyProperties) return actualUnknownCompositionLazy(main, property)
        val slotName = when (property) {
            else -> if (property.endsWith("Holder") || property.endsWith("Lazy")) property else property + "\$delegate"
        }
        val slot = raw(MainActivity::class.java, slotName)
        val existing = slot.get(main) as? Lazy<*>
        if (existing != null) return existing
        // Install the actual captured factory before its dependency holders, without forcing values.
        // This preserves lazy cycles and the constructor's original source-read order.
        val restored = lazy(if (property in nonSynchronizedOwners) LazyThreadSafetyMode.NONE else LazyThreadSafetyMode.SYNCHRONIZED) { invokeCapturedInitializer(main, property) }
        slot.set(main, restored)
        when (property) {
            "nativeRegistrationComposition" -> {
                lazyHolder(main, "viewStyle")
                uiPublication(main)
            }
            "permissionFlowController" -> lazyHolder(main, "permissionFlowUi")
            "primaryDetectorLifecycleController" -> {
                lazyHolder(main, "primaryDetectorOwner")
                lazyHolder(main, "primaryRuntimeCalibrationOwner")
                restoreDetectorRuntimeSupervisor(main)
            }
            "runtimeCameraLifecycle" -> {
                lazyHolder(main, "arSessionOwner")
                lazyHolder(main, "runtimeCameraPlatform")
            }
            "voiceSpeechPresentation" -> {
                lazyHolder(main, "voiceUiEffects")
                lazyHolder(main, "voiceCallbacks")
            }
            "voiceInputPresentation" -> lazyHolder(main, "voiceUiEffects")
            "voiceInputController" -> {
                lazyHolder(main, "voiceInputPresentation")
                lazyHolder(main, "voiceCallbacks")
            }
            "nativeScreenPresenter", "accountAccessPresenter" -> lazyHolder(main, "viewStyle")
            "primaryDetectorFrameSchedulingOwner", "primaryDetectorInvocationWorker", "primaryRuntimeCalibrationOwner" -> lazyHolder(main, "primaryDetectorOwner")
            "primaryRuntimeCalibrationCoordinator" -> {
                lazyHolder(main, "primaryDetectorOwner")
                lazyHolder(main, "primaryRuntimeCalibrationOwner")
            }
            "reportCandidateController", "rawCollectionTransferController", "reportQueueDrainController" -> {
                privacyAnchor(main, "reportPrivacyConsentSession")
                privacyAnchor(main, "integratedConsentSession")
            }
            "guidancePreparationController", "nativePrewalkController", "nativeStartAdmission" -> lazyHolder(main, "nativeWorkflowController")
            "nativeGuidanceEntryController" -> {
                lazyHolder(main, "nativeWorkflowController")
                lazyHolder(main, "nativePrewalkController")
                lazyHolder(main, "guidancePreparationController")
            }
            "accountDeletionProgressController" -> {
                restoreDeletionFactoryDependencies(main)
                lazyHolder(main, "accountDeletionEvidenceController")
            }
            "accountDeletionEvidenceController" -> restoreDeletionFactoryDependencies(main)
            "firstRunOnboardingController" -> lazyHolder(main, "priorityUserProfileController")
            "educationInteractionController" -> {
                lazyHolder(main, "firstRunOnboardingController")
                lazyHolder(main, "priorityUserProfileController")
            }
            "gatewayWalkSessionController" -> {
                restoreGatewayWalkAuthority(main)
                restorePureZeroArgument(main, "gatewayWalkSessionClient")
            }
            "walkSessionTransitionController", "walkSessionCleanupController",
            "walkSessionAdmissionController", "walkRuntimeActivationController" -> {
                restoreGatewayWalkAuthority(main)
                lazyHolder(main, "gatewayWalkSessionController")
                lazyHolder(main, "walkSessionResumeController")
            }
            "runtimeMetricSession" -> lazyHolder(main, "arSessionOwner")
            "accountDeletionNetworkController" -> restoreDeletionFactoryDependencies(main)
            "cameraFallbackControllerHolder", "officialEnvironmentCameraSource", "postLoginDeviceProbesHolder" -> {
                lazyHolder(main, "arSessionOwner")
                lazyHolder(main, "primaryDetectorOwner")
            }
            "positioningSessionController", "routeGuidanceController", "routeCompassSession" -> {
                runState(main)
                lazyHolder(main, "routeGuidanceDiagnostics")
                lazyHolder(main, "routeCallbackScheduler")
                lazyHolder(main, "guidancePreparationController")
                lazyHolder(main, "positioningSessionController")
                lazyHolder(main, "routeGuidanceController")
                lazyHolder(main, "routeCompassSession")
                lazyHolder(main, "continuousGpsSession")
            }
            "phoneMountingSession" -> lazyHolder(main, "officialEnvironmentEvidenceStore")
            "walkStopDelivery" -> lazyHolder(main, "positioningSessionController")
            "voiceInteractionControllers" -> runState(main)

            "routeArchiveController" -> { lazyHolder(main, "routePresentation"); lazyHolder(main, "routeCallbackScheduler") }
            "routeAcquisitionController" -> {
                runState(main); lazyHolder(main, "routeArchiveController")
                lazyHolder(main, "destinationSearchController")
            }
            "officialEnvironmentRuntimeController" -> lazyHolder(main, "officialEnvironmentEvidenceStore")
            "officialEnvironmentPreflightController" -> {
                lazyHolder(main, "officialEnvironmentEvidenceStore")
                lazyHolder(main, "officialEnvironmentCameraSource"); lazyHolder(main, "officialEnvironmentGpsSource")
            }
            "officialEnvironmentController" -> {
                lazyHolder(main, "officialEnvironmentPreflightController")
                lazyHolder(main, "officialEnvironmentRuntimeController")
                restoreCapturedObject(main, "officialEnvironmentAssessmentFacts")
            }
            "unknownServiceOwnerLazy" -> {
                lazyHolder(main, "primaryDetectorOwner")
                restoreCapturedObject(main, "unknownCaptureClock")
            }
            "unknownCaptureCoordinatorLazy" -> {
                lazyHolder(main, "unknownServiceOwnerLazy")
                lazyHolder(main, "arSessionOwner")
                lazyHolder(main, "primaryDetectorOwner")
                restoreCapturedObject(main, "unknownCaptureClock")
            }
        }
        return restored
    }

    /** The real factory stores callbacks only; actual Lazy holders and cycles remain cold. */
    private fun restoreVisionComposition(main: MainActivity, property: String, factory: String): Any {
        val slot = raw(MainActivity::class.java, property)
        slot.get(main)?.let { return it }
        val composition = requireNotNull(invokeMain(main, factory, slot.type))
        slot.set(main, composition)
        // The product creates this late lock after the eager compositions. Neither factory reads it.
        restorePureZeroArgument(main, "frameStateLock")
        return composition
    }

    private fun actualPrimaryCompositionLazy(main: MainActivity, property: String): Lazy<*> {
        val composition = restoreVisionComposition(main, "visionPrimaryComposition", "createVisionPrimaryComposition")
        val actualName = when (property) {
            "primaryDetectorOwner" -> "detector"
            "primaryRuntimeCalibrationOwner" -> "calibration"
            "primaryDetectorFrameSchedulingOwner" -> "scheduling"
            "primaryDetectorInvocationWorker" -> "invocation"
            else -> error("Not a primary composition alias: $property")
        }
        val actual = raw(composition.javaClass, actualName).get(composition) as Lazy<*>
        val mainType = MainActivity::class.java
        val backing = try { raw(mainType, property + "\$delegate") } catch (_: NoSuchFieldException) { null }
        if (backing != null) {
            val previous = backing.get(main)
            check(previous == null || previous === actual) { "Main lost the actual primary holder: $property" }
            if (previous == null) backing.set(main, actual)
            return actual
        }
        // Kotlin may elide the backing slot and emit this accessor for an optimized delegate.
        val accessor = "get" + property.replaceFirstChar { it.uppercase() } + "\$delegate"
        val aliased = MethodHandles.privateLookupIn(mainType, MethodHandles.lookup())
            .findStatic(mainType, accessor, MethodType.methodType(Any::class.java, mainType))
            .invokeWithArguments(main) as Lazy<*>
        check(aliased === actual) { "Main accessor lost the actual primary holder: $property" }
        return aliased
    }

    private fun actualUnknownCompositionLazy(main: MainActivity, property: String): Lazy<*> {
        val composition = restoreVisionComposition(main, "visionUnknownComposition", "createVisionUnknownComposition")
        // Main and the composition must reference these same two mutually referring original holders.
        for ((slotName, actualName) in listOf("unknownServiceOwnerLazy" to "services", "unknownCaptureCoordinatorLazy" to "capture")) {
            val actual = raw(composition.javaClass, actualName).get(composition) as Lazy<*>
            val slot = raw(MainActivity::class.java, slotName)
            val previous = slot.get(main)
            check(previous == null || previous === actual) { "Main lost the actual unknown holder: $slotName" }
            if (previous == null) slot.set(main, actual)
        }
        lazyHolder(main, "arSessionOwner")
        actualPrimaryCompositionLazy(main, "primaryDetectorOwner")
        restoreCapturedObject(main, "unknownCaptureClock")
        return raw(MainActivity::class.java, property).get(main) as Lazy<*>
    }

    /** The supervisor owns the same composition's native work and invokes the exact Main admission. */
    private fun restoreDetectorRuntimeSupervisor(main: MainActivity) {
        val slot = raw(MainActivity::class.java, "detectorRuntimeSupervisor")
        if (slot.get(main) != null) return
        val composition = restoreVisionComposition(main, "visionPrimaryComposition", "createVisionPrimaryComposition")
        val admission = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, "isCurrentFrameGeneration", MethodType.methodType(
                Boolean::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
                kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch::class.java)).bindTo(main)
        val callback: (Int, kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch) -> Boolean = { generation, epoch ->
            admission.invokeWithArguments(generation, epoch) as Boolean
        }
        val supervisor = MethodHandles.privateLookupIn(composition.javaClass, MethodHandles.lookup())
            .findVirtual(composition.javaClass, "createSupervisor", MethodType.methodType(
                DetectorRuntimeSupervisor::class.java, kotlin.jvm.functions.Function2::class.java))
            .invokeWithArguments(composition, callback)
        slot.set(main, supervisor)
    }

    private fun restoreCapturedObject(main: MainActivity, property: String) {
        val slot = raw(MainActivity::class.java, property)
        if (slot.get(main) != null) return
        val type = Class.forName(MainActivity::class.java.name + "\$" + property + "\$1")
        check(slot.type.isAssignableFrom(type)) { "Unexpected Main adapter type for $property" }
        val constructor = type.declaredConstructors.single().apply { isAccessible = true }
        val arguments = when {
            constructor.parameterTypes.isEmpty() -> emptyArray<Any>()
            constructor.parameterTypes.contentEquals(arrayOf(MainActivity::class.java)) -> arrayOf(main)
            else -> error("Unexpected captured constructor for $property")
        }
        slot.set(main, constructor.newInstance(*arguments))
    }

    /** Find the real captured factory without resolving Main's unrelated GL callback methods on JVM. */
    private fun invokeCapturedInitializer(main: MainActivity, property: String): Any {
        val mainType = MainActivity::class.java
        val prefix = property + if (property.endsWith("Lazy") || property.endsWith("Holder")) "" else "_delegate"
        val namePattern = Regex(Regex.escape(prefix) + "\\\$lambda\\\$[0-9]+")
        val parameter = "(L" + mainType.name.replace('.', '/') + ";)L"
        val factories = if (System.getProperty("java.vm.name") == "Dalvik") {
            mainType.declaredMethods.filter {
                Modifier.isStatic(it.modifiers) && namePattern.matches(it.name) &&
                    it.parameterTypes.contentEquals(arrayOf(mainType)) && !it.returnType.isPrimitive
            }.map { it.name to it.returnType }
        } else {
            classFileMethods().filter { (name, type) -> namePattern.matches(name) && type.startsWith(parameter) && type.endsWith(";") }
                .map { (name, type) -> name to Class.forName(type.removePrefix(parameter).removeSuffix(";").replace('/', '.'), false, mainType.classLoader) }
        }
        check(factories.size == 1) { "Expected one actual Main initializer for $property, found $factories" }
        val (name, ownerType) = factories.single()
        return requireNotNull(MethodHandles.privateLookupIn(mainType, MethodHandles.lookup())
            .findStatic(mainType, name, MethodType.methodType(ownerType, mainType))
            .invokeWithArguments(main))
    }

    private fun classFileMethods(): List<Pair<String, String>> {
        val stream = requireNotNull(MainActivity::class.java.getResourceAsStream("MainActivity.class"))
        return DataInputStream(stream).use { input ->
            fun skip(count: Long) {
                var remaining = count
                while (remaining > 0L) {
                    val skipped = input.skipBytes(minOf(remaining, Int.MAX_VALUE.toLong()).toInt())
                    if (skipped == 0) { input.readByte(); remaining-- } else remaining -= skipped
                }
            }
            check(input.readInt() == 0xCAFEBABE.toInt()) { "Invalid Main class resource" }
            input.readUnsignedShort(); input.readUnsignedShort()
            val pool = arrayOfNulls<String>(input.readUnsignedShort())
            var index = 1
            while (index < pool.size) {
                when (val tag = input.readUnsignedByte()) {
                    1 -> pool[index] = input.readUTF()
                    3, 4 -> skip(4)
                    5, 6 -> { skip(8); index++ }
                    7, 8, 16, 19, 20 -> skip(2)
                    9, 10, 11, 12, 17, 18 -> skip(4)
                    15 -> skip(3)
                    else -> error("Unsupported class constant: $tag")
                }
                index++
            }
            skip(6)
            repeat(input.readUnsignedShort()) { skip(2) }
            fun skipAttributes() {
                repeat(input.readUnsignedShort()) {
                    input.readUnsignedShort()
                    skip(input.readInt().toLong().and(0xffffffffL))
                }
            }
            repeat(input.readUnsignedShort()) { skip(6); skipAttributes() }
            buildList {
                repeat(input.readUnsignedShort()) {
                    input.readUnsignedShort()
                    val name = requireNotNull(pool[input.readUnsignedShort()])
                    val type = requireNotNull(pool[input.readUnsignedShort()])
                    add(name to type)
                    skipAttributes()
                }
            }
        }
    }

    private fun runState(main: MainActivity): NavigationRunState =
        raw(navigationComposition(main).javaClass, "run").get(navigationComposition(main)) as NavigationRunState

    private fun reset(main: MainActivity): Any = privacyAnchor(main, "accountDeletionInstallationResetController")


    private fun restorePureZeroArgument(main: MainActivity, name: String): Any {
        val slot = raw(MainActivity::class.java, name)
        return slot.get(main) ?: slot.type.getDeclaredConstructor().newInstance().also { slot.set(main, it) }
    }

    private fun restoreGatewayWalkAuthority(main: MainActivity): GatewayWalkAuthorityController {
        val slot = raw(MainActivity::class.java, "gatewayWalkAuthorityController")
        return (slot.get(main) as? GatewayWalkAuthorityController) ?: GatewayWalkAuthorityController(
            elapsedClock = SystemClock::elapsedRealtime,
        ).also { slot.set(main, it) }
    }

    private fun restoreDeletionFactoryDependencies(main: MainActivity) {
        deletionOperation(main)
        deletionActor(main)
        restorePureZeroArgument(main, "accountDeletionClient")
    }

    fun controller(main: MainActivity, property: String): Any = when (property) {
        "speechOutputStorageCoordinator", "legacyPendingReportQueuePurgeController", "accountDeletionPreparationController" -> privacyAnchor(main, property)
        else -> lazyOwner(main, property)
    }

    private fun deletionOperation(main: MainActivity): Any = privacyAnchor(main, "accountDeletionOperationAuthority")

    private fun deletionStages(main: MainActivity): AccountDeletionWorkerStages =
        privacyAnchor(main, "accountDeletionWorkerStages") as AccountDeletionWorkerStages

    private fun deletionStore(main: MainActivity): Any = privacyAnchor(main, "accountDeletionRecoveryStore")

    private fun deletionActor(main: MainActivity): Any = privacyAnchor(main, "accountDeletionRecoveryActorController")

    fun handsFreeModelPreparing(main: MainActivity): Boolean =
        (ownerPath(main, "voiceInputController.handsFreeRuntime") as
            kr.co.hanium.dreamup.walksafe.voice.input.HandsFreeRuntimeSession).modelPreparing

    private fun voiceOwner(main: MainActivity): Any = lazyOwner(main, "voiceInputController")

    private fun ownerPath(main: MainActivity, path: String): Any {
        val parts = path.split('.')
        var owner = lazyOwner(main, parts.first())
        parts.drop(1).forEach { owner = requireNotNull(raw(owner.javaClass, it).get(owner)) }
        return owner
    }

    private fun target(receiver: Any?, name: String): Any? = if (receiver !is MainActivity) receiver else when (name) {
        in permissionFields -> lazyOwner(receiver, "permissionFlowController")
        in permissionUiFields -> lazyOwner(receiver, "permissionFlowUi")
        in primaryLifecycleFields -> lazyOwner(receiver, "primaryDetectorLifecycleController")
        in runtimeCameraFields -> lazyOwner(receiver, "runtimeCameraLifecycle")
        in accountConsentSpeechFields -> lazyOwner(receiver, "accountConsentSpeechOwner")
        in readinessPresentationFields -> lazyOwner(receiver, "walkReadinessPresentationController")
        in tactileCaptureFields -> lazyOwner(receiver, "tactileCapture")
        in speechStorageFields -> controller(receiver, "speechOutputStorageCoordinator")
        in developmentQuickStartFields -> lazyOwner(receiver, "developmentQuickStartController")
        in userReportInteractionFields -> lazyOwner(receiver, "userReportInteractionOwner")
        in educationFields -> lazyOwner(receiver, "educationInteractionController")
        in gatewayWalkFields -> lazyOwner(receiver, "gatewayWalkSessionController")
        in walkResumeFields -> lazyOwner(receiver, "walkSessionResumeController")
        in readinessPanelFields -> {
            val (owner, slot) = uiPresenterSlot(receiver, "firstRunOnboardingPresenter")
            val presenter = requireNotNull(slot.get(owner)) as FirstRunOnboardingPresenter
            presenter.readinessBinding()
        }
        in metricFields -> lazyOwner(receiver, "runtimeMetricSession")
        in diagnosticFields -> lazyOwner(receiver, "fieldDiagnosticRecording")
        in onboardingFields -> lazyOwner(receiver, "firstRunOnboardingController")
        in profileFields -> lazyOwner(receiver, "priorityUserProfileController")
        in candidateFields -> lazyOwner(receiver, "reportCandidateController")
        in rawTransferFields -> lazyOwner(receiver, "rawCollectionTransferController")
        in postWalkFields -> lazyOwner(receiver, "postWalkTransferController")
        in reportQueueFields -> lazyOwner(receiver, "reportQueueDrainController")
        in guidancePreparationFields -> lazyOwner(receiver, "guidancePreparationController")
        in nativePrewalkFields -> lazyOwner(receiver, "nativePrewalkController")
        in deletionProgressFields -> lazyOwner(receiver, "accountDeletionProgressController")
        in legacyCleanupFields -> controller(receiver, "legacyPendingReportQueuePurgeController")
        in startupCoordinatorFields -> lazyOwner(receiver, "startupCapabilityCoordinator")
        in prewalkProgressFields -> lazyOwner(receiver, "prewalkGuidanceProgress")
        in frameSynthesisFields -> lazyOwner(receiver, "frameSynthesisHolder")
        in framePresentationFields -> lazyOwner(receiver, "framePresentation")
        in frameRecognitionFields -> lazyOwner(receiver, "frameRecognitionHolder")
        in primarySchedulingFields -> lazyOwner(receiver, "primaryDetectorFrameSchedulingOwner")
        in primaryCalibrationFields -> lazyOwner(receiver, "primaryRuntimeCalibrationOwner")
        in arFields -> lazyOwner(receiver, "arSessionOwner")
        in primaryFields -> lazyOwner(receiver, "primaryDetectorOwner")
        in deletionOperationFields -> deletionOperation(receiver)
        in deletionStoreFields -> deletionStore(receiver)
        in deletionActorFields -> deletionActor(receiver)
        in speechFields -> speech(receiver)
        in searchFields -> lazyOwner(receiver, "destinationSearchController")
        in emailFields -> lazyOwner(receiver, "emailAccountController")
        in cameraFields -> lazyOwner(receiver, "cameraFallbackControllerHolder")
        in deviceFields -> device(receiver)
        in guidanceFields -> ownerPath(receiver, "routeGuidanceController")
        in compassFields -> ownerPath(receiver, "routeCompassSession")
        in gpsFields -> ownerPath(receiver, "continuousGpsSession")
        in walkingCalibrationFields -> ownerPath(receiver, "positioningSessionController.walkingCalibrationSession")
        in positioningFields -> ownerPath(receiver, "positioningSessionController")
        in mountingFields -> ownerPath(receiver, "phoneMountingSession")
        in walkStopFields -> ownerPath(receiver, "walkStopDelivery")
        in consentFields -> ownerPath(receiver, "integratedConsentController")
        in networkFields -> ownerPath(receiver, "networkPreferenceController")
        in voiceCommandFields -> ownerPath(receiver, "voiceInteractionControllers.commands")
        in dialogFields -> ownerPath(receiver, "voiceInteractionControllers.destinations")
        in riskFields -> ownerPath(receiver, "riskFeedbackComponents.delivery")
        in referenceFields -> ownerPath(receiver, "tactileReferenceDelivery")
        in startupConfirmationStateFields -> lazyOwner(receiver, "startupConfirmationInteraction")
        in voiceSettingsStateFields -> lazyOwner(receiver, "voiceDataSettingsRecovery")
        in postLoginInteractionStateFields -> lazyOwner(receiver, "postLoginDeviceInteraction")
        in registrationPresenterFields -> lazyOwner(receiver, "nativeRegistrationComposition")
        in startupReadinessFields -> lazyOwner(receiver, "startupReadinessEvidence")
        in riskCompositionFields -> lazyOwner(receiver, "riskFeedbackComponents")
        in nativeFields -> lazyOwner(receiver, "nativeWorkflowController")
        in runFields -> runState(receiver)
        in routeFields -> { runState(receiver); lazyOwner(receiver, "routeAcquisitionController") }
        in archiveFields -> lazyOwner(receiver, "routeArchiveController")
        in handsFreeRuntimeFields -> ownerPath(receiver, "voiceInputController.handsFreeRuntime")
        in voiceFields -> voiceOwner(receiver)
        in wakeFields -> raw(voiceOwner(receiver).javaClass, "wake").get(voiceOwner(receiver))
        in gatewayVoiceFields -> raw(voiceOwner(receiver).javaClass, "gateway").get(voiceOwner(receiver))
        in gatewaySessionFields -> lazyOwner(receiver, "gatewaySessionController")
        in evidenceFields -> lazyOwner(receiver, "officialEnvironmentEvidenceStore")
        in preflightFields -> lazyOwner(receiver, "officialEnvironmentPreflightController")
        in environmentRuntimeFields -> lazyOwner(receiver, "officialEnvironmentRuntimeController")
        in resetFields -> reset(receiver)
        in unknownCaptureFields -> lazyOwner(receiver, "unknownCaptureCoordinatorLazy")
        in unknownServiceFields -> lazyOwner(receiver, "unknownServiceOwnerLazy")
        else -> receiver
    }

    private fun raw(type: Class<*>, name: String): Field = type.getDeclaredField(name).apply { isAccessible = true }

    class OwnerField internal constructor(private val name: String) {
        var isAccessible: Boolean = true
        private fun field(receiver: Any?): Pair<Any?, Field> {
            if (receiver is MainActivity) graphAlias(receiver, name)?.let { return it }
            val owner = target(receiver, name)
            val fieldName = if (receiver !is MainActivity) name else when (name) {
                in permissionFields -> permissionFields.getValue(name)
                in permissionUiFields -> permissionUiFields.getValue(name)
                in primaryLifecycleFields -> primaryLifecycleFields.getValue(name)
                in runtimeCameraFields -> runtimeCameraFields.getValue(name)
                in accountConsentSpeechFields -> accountConsentSpeechFields.getValue(name)
                in readinessPresentationFields -> readinessPresentationFields.getValue(name)
                in tactileCaptureFields -> tactileCaptureFields.getValue(name)
                in educationFields -> educationFields.getValue(name)
                in gatewayWalkFields -> gatewayWalkFields.getValue(name)
                in walkResumeFields -> walkResumeFields.getValue(name)
                in metricFields -> metricFields.getValue(name)
                in diagnosticFields -> diagnosticFields.getValue(name)
                in onboardingFields -> onboardingFields.getValue(name)
                in profileFields -> profileFields.getValue(name)
                in candidateFields -> candidateFields.getValue(name)
                in rawTransferFields -> rawTransferFields.getValue(name)
                in postWalkFields -> postWalkFields.getValue(name)
                in reportQueueFields -> reportQueueFields.getValue(name)
                in guidancePreparationFields -> guidancePreparationFields.getValue(name)
                in nativePrewalkFields -> nativePrewalkFields.getValue(name)
                in deletionProgressFields -> deletionProgressFields.getValue(name)
                in legacyCleanupFields -> legacyCleanupFields.getValue(name)
                in startupCoordinatorFields -> startupCoordinatorFields.getValue(name)
                in prewalkProgressFields -> prewalkProgressFields.getValue(name)
                in frameSynthesisFields -> frameSynthesisFields.getValue(name)
                in framePresentationFields -> framePresentationFields.getValue(name)
                in frameRecognitionFields -> frameRecognitionFields.getValue(name)
                in primarySchedulingFields -> primarySchedulingFields.getValue(name)
                in primaryCalibrationFields -> primaryCalibrationFields.getValue(name)
                in arFields -> arFields.getValue(name)
                in primaryFields -> primaryFields.getValue(name)
                in deletionOperationFields -> deletionOperationFields.getValue(name)
                in deletionStoreFields -> deletionStoreFields.getValue(name)
                in deletionActorFields -> deletionActorFields.getValue(name)
                in cameraFields -> cameraFields.getValue(name)
                in deviceFields -> deviceFields.getValue(name)
                in startupConfirmationStateFields -> startupConfirmationStateFields.getValue(name)
                in voiceSettingsStateFields -> voiceSettingsStateFields.getValue(name)
                in postLoginInteractionStateFields -> postLoginInteractionStateFields.getValue(name)
                in registrationPresenterFields -> registrationPresenterFields.getValue(name)
                in startupReadinessFields -> startupReadinessFields.getValue(name)
                in riskCompositionFields -> riskCompositionFields.getValue(name)
                in nativeFields -> nativeFields.getValue(name)
                in runFields -> runFields.getValue(name)
                in evidenceFields -> evidenceFields.getValue(name)
                in preflightFields -> preflightFields.getValue(name)
                in environmentRuntimeFields -> environmentRuntimeFields.getValue(name)
                in resetFields -> resetFields.getValue(name)
                in walkingCalibrationFields -> walkingCalibrationFields.getValue(name)
                else -> name
            }
            val declaring = if (owner == null || owner is MainActivity) MainActivity::class.java else owner.javaClass
            return owner to raw(declaring, fieldName)
        }
        fun get(receiver: Any?): Any? {
            if (receiver is MainActivity && name in metricObservations) {
                val metric = lazyOwner(receiver, "runtimeMetricSession") as
                    kr.co.hanium.dreamup.walksafe.runtime.metric.RuntimeMetricSessionController<*, *, *>
                return when (name) {
                    "runtimeMetricPreflightGeneration" -> metric.preflightGeneration
                    "metricPreflightOwner" -> metric.preflightOwner
                    "metricPreflightPostLoginBinding" -> metric.preflightBinding
                    else -> error("Unknown metric observation: $name")
                }
            }
            if (receiver is MainActivity && name == "priorityUserPracticeButtons") {
                val (owner, slot) = uiPresenterSlot(receiver, "firstRunOnboardingPresenter")
                val presenter = requireNotNull(slot.get(owner)) as FirstRunOnboardingPresenter
                return presenter.educationBinding().priorityUserPracticeButtons
            }
            if (receiver is MainActivity && name in uiBindings) {
                val binding = uiBindings.getValue(name)
                if (binding.target != null && !uiPublication(receiver).isPublished(binding.target)) return null
                val (panel, field) = uiSlot(receiver, binding)
                return field.get(panel)
            }
            return field(receiver).let { (owner, field) -> field.get(owner) }
        }
        fun set(receiver: Any?, value: Any?) {
            check(receiver !is MainActivity || name !in metricObservations) {
                "Metric observation is read-only; use the real lifecycle operation: $name"
            }
            if (receiver is MainActivity && setGraphAlias(receiver, name, value)) return
            if (receiver is MainActivity && name in uiBindings) {
                setUiView(receiver, uiBindings.getValue(name), value)
                return
            }
            val (owner, field) = field(receiver)
            if (receiver is MainActivity && name == "nativeUiPage") {
                val actual = requireNotNull(owner)
                MethodHandles.privateLookupIn(actual.javaClass, MethodHandles.lookup())
                    .findVirtual(actual.javaClass, "setNativeUiPage", MethodType.methodType(Void.TYPE, field.type))
                    .bindTo(actual).invokeWithArguments(value)
            } else if (receiver is MainActivity && name == "firstRunOnboardingSnapshot") {
                val controller = owner as kr.co.hanium.dreamup.walksafe.session.onboarding.FirstRunOnboardingController
                val snapshot = value as kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
                check(controller.restoreSessionSnapshot(snapshot)) {
                    "Fixture onboarding restore was rejected by the current session snapshot"
                }
            } else if (receiver is MainActivity && name == "priorityUserOnboardingPolicy") {
                val actual = requireNotNull(owner)
                MethodHandles.privateLookupIn(actual.javaClass, MethodHandles.lookup())
                    .findVirtual(actual.javaClass, "setPolicy", MethodType.methodType(Void.TYPE, field.type))
                    .bindTo(actual).invokeWithArguments(value)
            } else field.set(owner, value)
            Unit
        }
        fun getBoolean(receiver: Any?) = get(receiver) as Boolean
        fun getInt(receiver: Any?) = get(receiver) as Int
        fun getLong(receiver: Any?) = get(receiver) as Long
        fun getFloat(receiver: Any?) = get(receiver) as Float
        fun getDouble(receiver: Any?) = get(receiver) as Double
        fun setBoolean(receiver: Any?, value: Boolean) = set(receiver, value)
        fun setInt(receiver: Any?, value: Int) = set(receiver, value)
        fun setLong(receiver: Any?, value: Long) = set(receiver, value)
        fun setFloat(receiver: Any?, value: Float) = set(receiver, value)
        fun setDouble(receiver: Any?, value: Double) = set(receiver, value)
    }
}
