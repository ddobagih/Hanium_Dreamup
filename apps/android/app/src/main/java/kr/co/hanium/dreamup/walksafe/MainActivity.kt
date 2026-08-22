package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.RectF
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.GeomagneticField
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.InputType
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.util.Size
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.Session
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import kr.co.hanium.dreamup.walksafe.device.AndroidLocalTactileCapability
import kr.co.hanium.dreamup.walksafe.device.AndroidLocalTactileCapabilityInput
import kr.co.hanium.dreamup.walksafe.device.AndroidLocalTactileTier
import kr.co.hanium.dreamup.walksafe.device.AndroidStartupCapabilityProbe
import kr.co.hanium.dreamup.walksafe.device.AndroidWalkSessionResourceProbe
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityPolicy
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingAssessment
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingAssessmentPhase
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingMethod
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingPolicy
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingRuntimeState
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingStatus
import kr.co.hanium.dreamup.walksafe.device.PhoneMountingUserConfirmation
import kr.co.hanium.dreamup.walksafe.device.ApprovedDeviceProfileMatch
import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricDepthSupport
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricFrameEvidence
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightPolicy
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightResult
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightSession
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightStatus
import kr.co.hanium.dreamup.walksafe.device.WALKSAFE_LIMITED_DISTANCE_NOTICE_KO
import kr.co.hanium.dreamup.walksafe.device.WALKSAFE_DEVICE_PROFILE_POLICY_STATUS
import kr.co.hanium.dreamup.walksafe.device.WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO
import kr.co.hanium.dreamup.walksafe.device.WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO
import kr.co.hanium.dreamup.walksafe.device.WalkSafeApprovedDeviceProfiles
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityTier
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupRequirement
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider
import kr.co.hanium.dreamup.walksafe.depth.CameraIntrinsics
import kr.co.hanium.dreamup.walksafe.depth.CoordinateMapper
import kr.co.hanium.dreamup.walksafe.depth.DetectionCandidate
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.depth.ImageSize
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.MotionContext
import kr.co.hanium.dreamup.walksafe.depth.ObjectDepthRuntimePipeline
import kr.co.hanium.dreamup.walksafe.depth.Point2
import kr.co.hanium.dreamup.walksafe.depth.RectNorm
import kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth
import kr.co.hanium.dreamup.walksafe.depth.UserFacingDepth
import kr.co.hanium.dreamup.walksafe.depth.Vec3
import kr.co.hanium.dreamup.walksafe.depth.debugSummaryText
import kr.co.hanium.dreamup.walksafe.depth.toSnapshotAndClose
import kr.co.hanium.dreamup.walksafe.debuglog.DebugFrameCaptureUploaderFactory
import kr.co.hanium.dreamup.walksafe.debuglog.DebugMetadataLogUploaderFactory
import kr.co.hanium.dreamup.walksafe.debuglog.FrameCaptureUploader
import kr.co.hanium.dreamup.walksafe.debuglog.MetadataLogUploader
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.AndroidNonMetricObstacleAdvisoryPolicy
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackAction
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.feedback.NonMetricAdvisoryGate
import kr.co.hanium.dreamup.walksafe.feedback.NonMetricObstacleAdvisoryAction
import kr.co.hanium.dreamup.walksafe.feedback.WalkSafeFeedbackPolicy
import kr.co.hanium.dreamup.walksafe.feedback.dispatchNavigationSpeech
import kr.co.hanium.dreamup.walksafe.feedback.shouldSuppressFeedbackDuringVoiceRecognition
import kr.co.hanium.dreamup.walksafe.feedback.utteranceTerminalTimeoutMs
import kr.co.hanium.dreamup.walksafe.fieldlog.CameraNonMetricFieldSample
import kr.co.hanium.dreamup.walksafe.fieldlog.FieldRuntimeSnapshot
import kr.co.hanium.dreamup.walksafe.fieldlog.FieldSessionDeviceInfo
import kr.co.hanium.dreamup.walksafe.fieldlog.FieldSessionLog
import kr.co.hanium.dreamup.walksafe.fieldlog.NoopFieldSessionLog
import kr.co.hanium.dreamup.walksafe.fieldlog.PersistentFieldSessionLog
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectionResult
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorTiming
import kr.co.hanium.dreamup.walksafe.inference.AndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.ArgbImage
import kr.co.hanium.dreamup.walksafe.inference.DetectorRuntimeFailureAction
import kr.co.hanium.dreamup.walksafe.inference.DetectorRuntimeSupervisor
import kr.co.hanium.dreamup.walksafe.inference.NoopAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector
import kr.co.hanium.dreamup.walksafe.inference.TwoModelRuntimeConfig
import kr.co.hanium.dreamup.walksafe.inference.YuvImagePreprocessor
import kr.co.hanium.dreamup.walksafe.navigation.AndroidStepTracker
import kr.co.hanium.dreamup.walksafe.navigation.AndroidEarthOrientationTracker
import kr.co.hanium.dreamup.walksafe.navigation.AndroidDetectionSnapshotFrameIdentity
import kr.co.hanium.dreamup.walksafe.navigation.AndroidTactileEvidenceFrameIdentity
import kr.co.hanium.dreamup.walksafe.navigation.AndroidTactileFrameIdentity
import kr.co.hanium.dreamup.walksafe.navigation.AndroidTactileFrameInput
import kr.co.hanium.dreamup.walksafe.navigation.ArCoreTactileProjectionContextFactory
import kr.co.hanium.dreamup.walksafe.navigation.TactileFrameFeedbackActuator
import kr.co.hanium.dreamup.walksafe.navigation.TactileFrameFeedbackDispatch
import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.navigation.BackendWalkingRouteClient
import kr.co.hanium.dreamup.walksafe.navigation.ConsecutiveTmapFailureGuard
import kr.co.hanium.dreamup.walksafe.navigation.EncryptedRouteSnapshotStore
import kr.co.hanium.dreamup.walksafe.navigation.FrozenImageToDepthTransform
import kr.co.hanium.dreamup.walksafe.navigation.GatewayProxyHttpException
import kr.co.hanium.dreamup.walksafe.navigation.LocationTrustPolicy
import kr.co.hanium.dreamup.walksafe.navigation.NavigationBackendErrorKind
import kr.co.hanium.dreamup.walksafe.navigation.haversineMeters
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUpdate
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision
import kr.co.hanium.dreamup.walksafe.navigation.RouteDeviationChoice
import kr.co.hanium.dreamup.walksafe.navigation.ROUTE_SNAPSHOT_TTL_MS
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceCommand
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState
import kr.co.hanium.dreamup.walksafe.navigation.EarthOrientationAccuracy
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.StepLengthEstimator
import kr.co.hanium.dreamup.walksafe.navigation.StepCalibrationSample
import kr.co.hanium.dreamup.walksafe.navigation.TactileProjectionContext
import kr.co.hanium.dreamup.walksafe.navigation.TactileRouteGuidanceResult
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRouteRequest
import kr.co.hanium.dreamup.walksafe.navigation.formatDestinationDistance
import kr.co.hanium.dreamup.walksafe.navigation.reliableMovementHeadingDegrees
import kr.co.hanium.dreamup.walksafe.navigation.classifyNavigationBackendFailure
import kr.co.hanium.dreamup.walksafe.navigation.selectAndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.navigation.createProductionAndroidTactileFrameCoordinator
import kr.co.hanium.dreamup.walksafe.network.AndroidNavigationCancellation
import kr.co.hanium.dreamup.walksafe.network.AndroidNavigationRequestCoordinator
import kr.co.hanium.dreamup.walksafe.network.AndroidNavigationRequestEvent
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.ActivityOriginalUploadAdmissionController
import kr.co.hanium.dreamup.walksafe.network.AndroidGatewaySessionStore
import kr.co.hanium.dreamup.walksafe.network.AndroidNetworkStateProbe
import kr.co.hanium.dreamup.walksafe.network.AndroidNetworkTransferPolicy
import kr.co.hanium.dreamup.walksafe.network.AndroidIntegratedConsentClient
import kr.co.hanium.dreamup.walksafe.network.AndroidPrivacyDeletionClient
import kr.co.hanium.dreamup.walksafe.network.AccountDeletionCallExecution
import kr.co.hanium.dreamup.walksafe.network.AccountDeletionHttpException
import kr.co.hanium.dreamup.walksafe.network.AccountDeletionHttpFailureDisposition
import kr.co.hanium.dreamup.walksafe.network.AccountDeletionReauthenticationRequiredException
import kr.co.hanium.dreamup.walksafe.network.AccountDeletionRev0RecoveryBinding
import kr.co.hanium.dreamup.walksafe.network.accountDeletionHttpFailureDisposition
import kr.co.hanium.dreamup.walksafe.network.executeAccountDeletionCall
import kr.co.hanium.dreamup.walksafe.network.exactAccountDeletionRev0RecoveryBindingOrNull
import kr.co.hanium.dreamup.walksafe.network.exactLegacyAccountDeletionRev0ActorBindingOrNull
import kr.co.hanium.dreamup.walksafe.network.exactLegacyAccountDeletionRev0ActorCandidateOrNull
import kr.co.hanium.dreamup.walksafe.network.exactLegacyAccountDeletionRev0MarkerBindingOrNull
import kr.co.hanium.dreamup.walksafe.network.isExactAccountDeletionRev0RecoverySessionReady
import kr.co.hanium.dreamup.walksafe.network.isExactAcceptedRev0RecoverySessionRetirement
import kr.co.hanium.dreamup.walksafe.network.runGatewayActivityCallbackIfCurrent
import kr.co.hanium.dreamup.walksafe.network.DeviceDeletionEvidence
import kr.co.hanium.dreamup.walksafe.network.deviceDeletionEvidenceSha256
import kr.co.hanium.dreamup.walksafe.network.validatedAccountDeletionStatusOrNull
import kr.co.hanium.dreamup.walksafe.network.CancellableNetworkCall
import kr.co.hanium.dreamup.walksafe.network.GatewayCredentialPolicy
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.network.GatewayEndpointPolicy
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSessionClient
import kr.co.hanium.dreamup.walksafe.network.GatewayPendingRevocation
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionOperation
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessSnapshot
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionRevalidationStatus
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionScope
import kr.co.hanium.dreamup.walksafe.network.IntegratedConsentNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionHttpException
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionStoreResult
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionVerificationState
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionVersion
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityCompletion
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityController
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityOperation
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkSessionClient
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkStartResult
import kr.co.hanium.dreamup.walksafe.network.GatewayWalkTakeoverConfirmation
import kr.co.hanium.dreamup.walksafe.network.RestoredGatewayLoginBundle
import kr.co.hanium.dreamup.walksafe.network.MobileNetworkPreference
import kr.co.hanium.dreamup.walksafe.network.newNavigationRequestExecutor
import kr.co.hanium.dreamup.walksafe.report.AndroidReportCandidateInput
import kr.co.hanium.dreamup.walksafe.report.AndroidReportCandidate
import kr.co.hanium.dreamup.walksafe.report.AndroidReportCandidatePolicy
import kr.co.hanium.dreamup.walksafe.report.AndroidReportAttemptBlockReason
import kr.co.hanium.dreamup.walksafe.report.AndroidReportAttemptResult
import kr.co.hanium.dreamup.walksafe.report.AndroidReportAttemptStore
import kr.co.hanium.dreamup.walksafe.report.AndroidAccountDeletionFallbackMarker
import kr.co.hanium.dreamup.walksafe.report.dispatchAndroidAccountDeletionResume
import kr.co.hanium.dreamup.walksafe.report.AndroidReportCooldownPolicy
import kr.co.hanium.dreamup.walksafe.report.AndroidPendingReportStore
import kr.co.hanium.dreamup.walksafe.report.AndroidReportSuccessfulCooldown
import kr.co.hanium.dreamup.walksafe.report.AndroidReportUploader
import kr.co.hanium.dreamup.walksafe.report.ReportUploadHttpException
import kr.co.hanium.dreamup.walksafe.report.ReportUploadProtocolException
import kr.co.hanium.dreamup.walksafe.report.ReportUploadReceiptOutcome
import kr.co.hanium.dreamup.walksafe.report.REPORT_PRIVACY_DISCLOSURE_KO
import kr.co.hanium.dreamup.walksafe.report.ReportPrivacyConsentSession
import kr.co.hanium.dreamup.walksafe.report.ReportTransferPurpose
import kr.co.hanium.dreamup.walksafe.report.reportRetryDelayMs
import kr.co.hanium.dreamup.walksafe.security.AeadKeyPolicy
import kr.co.hanium.dreamup.walksafe.security.AndroidSensitivePreferenceStore
import kr.co.hanium.dreamup.walksafe.security.SensitivePreferenceSpec
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionConfirmationToken
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.session.WalkSessionMode
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessCollector
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessObservation
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessPlan
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessRequirement
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessSnapshot
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus
import kr.co.hanium.dreamup.walksafe.session.WalkSessionRecoveryStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionResumeConfirmation
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.session.AuthenticationState
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.FirstRunAgeBand
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingAttemptRequest
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidence
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingEvidenceVerifier
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.session.FirstRunReceiptHash
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_DISCLOSURE_KO
import kr.co.hanium.dreamup.walksafe.session.INTEGRATED_CONSENT_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentConfirmation
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentApplyResult
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItem
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSelections
import kr.co.hanium.dreamup.walksafe.session.IntegratedConsentSession
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionApplyResult
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStartupRestoreResult
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionActivityLease
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionAggregateAuthorityState
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionAuthoritySnapshot
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionConfirmationDecision
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionDualAuthority
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionIntentFence
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionIntentFenceState
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionIntentAuthorityState
import kr.co.hanium.dreamup.walksafe.session.FileAccountDeletionIntentAuthority
import kr.co.hanium.dreamup.walksafe.session.PreparedAccountDeletionConfirmationRecovery
import kr.co.hanium.dreamup.walksafe.session.PreparedConfirmationRecoveryResult
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionJournal
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionNetworkCallLease
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionPhase
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStateMachine
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionStatus
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionTerminalCleanupCoordinator
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionTerminalMarkerCleanupResult
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionWorkerAttempt
import kr.co.hanium.dreamup.walksafe.session.AccountDeletionWorkerStageResult
import kr.co.hanium.dreamup.walksafe.session.DeletionInventoryItem
import kr.co.hanium.dreamup.walksafe.session.DeletionItemState
import kr.co.hanium.dreamup.walksafe.session.DeletionItemStatus
import kr.co.hanium.dreamup.walksafe.session.dispatchAccountDeletionNetworkEntry
import kr.co.hanium.dreamup.walksafe.session.dispatchAccountDeletionWorkerStage
import kr.co.hanium.dreamup.walksafe.session.accountDeletionStartupAuthorityDecision
import kr.co.hanium.dreamup.walksafe.session.isConfirmedTerminalAccountDeletion
import kr.co.hanium.dreamup.walksafe.session.PendingIntegratedConsentMutation
import kr.co.hanium.dreamup.walksafe.session.PurposeConsentSyncState
import kr.co.hanium.dreamup.walksafe.session.GpsQualityObservation
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentAssessment
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentPolicy
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentRuntimeAction
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentRuntimeGuard
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentSupport
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentUserConfirmation
import kr.co.hanium.dreamup.walksafe.session.ObservedPermission
import kr.co.hanium.dreamup.walksafe.session.ObservedPermissionSnapshot
import kr.co.hanium.dreamup.walksafe.session.PermissionDependentFeature
import kr.co.hanium.dreamup.walksafe.session.PermissionDependencyPolicy
import kr.co.hanium.dreamup.walksafe.session.PermissionRecoveryGate
import kr.co.hanium.dreamup.walksafe.session.PermissionRecoveryGateState
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionPolicy
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionSnapshot
import kr.co.hanium.dreamup.walksafe.session.PRIORITY_USER_TRAINING_POLICY_VERSION
import kr.co.hanium.dreamup.walksafe.session.PriorityUserAgeBand
import kr.co.hanium.dreamup.walksafe.session.PriorityUserOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.PriorityUserOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPractice
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPracticeAttemptToken
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPracticeDeliverySignal
import kr.co.hanium.dreamup.walksafe.session.PriorityUserSupportEnvironment
import kr.co.hanium.dreamup.walksafe.session.WalkStartPermissionPolicy
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.util.Locale
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import org.json.JSONArray
import org.json.JSONObject

private fun DetectionCandidate.isTactileDetection(): Boolean {
    return className.lowercase(Locale.US).contains("tactile") || className.contains("점자")
}

/**
 * Android runtime composition root.
 *
 * The GL thread owns each ARCore Frame and depth acquisition. ARCore results enter the metric
 * pipeline only through its device gate. CameraX fallback detections stay in a separate non-metric
 * advisory policy and can never become route or report evidence.
 */
class MainActivity : Activity(), GLSurfaceView.Renderer {
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var cameraFallbackPreviewView: PreviewView
    private lateinit var productPurposeText: TextView
    private lateinit var firstRunOnboardingControls: LinearLayout
    private lateinit var firstRunOnboardingStatusText: TextView
    private lateinit var firstRunPurposeButton: Button
    private val firstRunAgeButtons = mutableMapOf<FirstRunAgeBand, Button>()
    private lateinit var firstRunIntegratedConsentDisclosureText: TextView
    private val firstRunIntegratedConsentButtons =
        mutableMapOf<IntegratedConsentItem, Button>()
    private lateinit var firstRunIntegratedConsentSaveButton: Button
    private lateinit var startupCapabilityText: TextView
    private lateinit var startupMetricPreflightButton: Button
    private lateinit var startupCapabilityConfirmButton: Button
    private lateinit var priorityUserOnboardingControls: LinearLayout
    private lateinit var priorityUserOnboardingStatusText: TextView
    private lateinit var priorityUserEducationButton: Button
    private lateinit var priorityUserSafePlaceButton: Button
    private lateinit var priorityUserResetButton: Button
    private lateinit var officialEnvironmentStatusText: TextView
    private lateinit var officialEnvironmentConfirmButton: Button
    private lateinit var phoneMountingStatusText: TextView
    private lateinit var phoneMountingChestConfirmButton: Button
    private lateinit var phoneMountingNecklaceConfirmButton: Button
    private val priorityUserAgeButtons = mutableMapOf<PriorityUserAgeBand, Button>()
    private val priorityUserPracticeButtons = mutableMapOf<PriorityUserPractice, Button>()
    private lateinit var runtimeControls: LinearLayout
    private lateinit var controlsScroll: ScrollView
    private lateinit var walkSafetyOverlay: LinearLayout
    private lateinit var safetySummaryText: TextView
    private lateinit var statusText: TextView
    private lateinit var detailText: TextView
    private lateinit var permissionDenialPanel: LinearLayout
    private lateinit var permissionDenialSummaryText: TextView
    private lateinit var permissionDenialConfirmButton: Button
    private lateinit var permissionDenialSettingsButton: Button
    private lateinit var actionButton: Button
    private lateinit var debugUploadButton: Button
    private lateinit var debugFrameCaptureButton: Button
    private lateinit var fieldSessionLogButton: Button
    private lateinit var routeButton: Button
    private lateinit var backendUrlInput: EditText
    private lateinit var backendFieldTokenInput: EditText
    private lateinit var backendAuthApplyButton: Button
    private lateinit var destinationQueryInput: EditText
    private lateinit var destinationSearchButton: Button
    private lateinit var destinationCancelButton: Button
    private lateinit var destinationResetButton: Button
    private lateinit var destinationMoreButton: Button
    private lateinit var progressBeepToggleButton: Button
    private lateinit var progressBeepVolumeButton: Button
    private lateinit var destinationSearchResultsContainer: LinearLayout
    private lateinit var destinationLatInput: EditText
    private lateinit var destinationLngInput: EditText
    private lateinit var navigationStatusText: TextView
    private lateinit var routeDeviationActions: LinearLayout
    private lateinit var routeDeviationNewRouteButton: Button
    private lateinit var routeDeviationRecheckButton: Button
    private lateinit var routeDeviationEndButton: Button
    private lateinit var debugBboxOverlay: DebugBboxOverlayView
    private lateinit var loginUserIdInput: EditText
    private lateinit var loginSaveButton: Button
    private lateinit var accountLogoutButton: Button
    private lateinit var reportPrivacyDisclosureText: TextView
    private lateinit var privacyConsentStatusText: TextView
    private lateinit var firstRunNoticeToggleButton: Button
    private var firstRunNoticeExpandedByUser = false
    private lateinit var firstRunProgressBar: LinearLayout
    private val firstRunProgressSegments = mutableListOf<View>()
    private lateinit var privacyControls: LinearLayout
    private lateinit var reportPrivacyConsentButton: Button
    private lateinit var automaticReportConsentButton: Button
    private lateinit var mobileNetworkPreferenceButton: Button
    private lateinit var trainingReuseConsentButton: Button
    private lateinit var privacyRightsButton: Button
    private lateinit var accountDeletionStatusText: TextView
    private lateinit var accountDeletionRequestButton: Button
    private lateinit var accountDeletionConfirmButton: Button
    private lateinit var accountDeletionCancelButton: Button
    private lateinit var accountDeletionRefreshButton: Button
    private val accountDeletionItemTexts =
        mutableMapOf<DeletionInventoryItem, TextView>()
    private lateinit var explicitReportButton: Button
    private lateinit var voiceReportButton: Button

    private var installRequested = false
    private var session: Session? = null
    private var frameProvider: ArCoreFrameProvider? = null
    @Volatile
    private var arSessionPurpose = ArSessionPurpose.NONE
    @Volatile
    private var arSessionGeneration = 0L
    private var cameraFallbackProvider: ProcessCameraProvider? = null
    private var cameraFallbackAnalysis: ImageAnalysis? = null
    private lateinit var cameraFallbackLifecycleOwner: CameraFallbackLifecycleOwner
    @Volatile
    private var cameraFallbackRequested = false
    @Volatile
    private var cameraFallbackRunning = false
    @Volatile
    private var cameraFallbackGeneration = 0
    @Volatile
    private var lastCameraFallbackAnalysisMs = 0L
    @Volatile
    private var cameraFallbackFrameAnalyzedGeneration = -1
    private var cameraFallbackStartReason: CameraFallbackStartReason? = null
    private var cameraFallbackAvailability: ArCoreApk.Availability? = null
    private val nonMetricAdvisoryPolicy = AndroidNonMetricObstacleAdvisoryPolicy()
    private var objectDepthPipeline = ObjectDepthRuntimePipeline()
    private val backgroundRenderer = CameraBackgroundRenderer()
    @Volatile
    private var frameDetector: AndroidFrameDetector = NoopAndroidFrameDetector()
    private val detectorRuntimeSupervisor = DetectorRuntimeSupervisor()
    private val captureLog = MetadataCaptureLog()
    private val tactileOverlayStabilizer = TactileOverlayStabilizer()
    private var feedbackActuator: AndroidFeedbackActuator? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var voiceRecognitionActive = false
    private var voiceRecognitionGeneration = 0
    private var voiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND
    private var walkSessionResumePromptPending = false
    private var walkSessionResumeRetryRequiresUserAction = false
    private var walkSessionResumeConfirmationToken: WalkSessionConfirmationToken? = null
    private var gatewayWalkTakeoverPromptPending = false
    private var gatewayWalkTakeoverPromptOperationId: String? = null
    private var gatewayWalkStartConfirmationToken: WalkSessionConfirmationToken? = null
    private var gatewayWalkResumeRevalidationPending = false
    private var walkExitConfirmationDialog: AlertDialog? = null
    private lateinit var walkBackDispatcher: OnBackPressedDispatcher
    private val walkScreenBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (handleWalkScreenBackPressed()) return
            isEnabled = false
            try {
                walkBackDispatcher.onBackPressed()
            } finally {
                isEnabled = true
            }
        }
    }
    private var walkSessionPermissionRequestInFlight = false
    private var walkSessionPermissionRequestAttempted = false
    private var walkSessionPermissionRequestCode: Int? = null
    private val permissionRequestLeases = mutableMapOf<Int, PermissionRequestLease>()
    private val permissionRequestGenerationByPurpose =
        mutableMapOf<PermissionRequestPurpose, Long>()
    private var nextPermissionRequestCode = PERMISSION_REQUEST_CODE_MIN
    private val feedbackPolicy = WalkSafeFeedbackPolicy()
    internal var tactileFrameFeedbackDelivery: (TactileFrameFeedbackDispatch) -> Unit =
        ::emitTactileFrameFeedback
    private val tactileFrameCoordinator = createProductionAndroidTactileFrameCoordinator(
        feedbackPolicy = feedbackPolicy,
        feedbackActuator = TactileFrameFeedbackActuator { dispatch ->
            tactileFrameFeedbackDelivery(dispatch)
        },
    )
    private var lastRiskAnnouncementMs = 0L
    private var lastRiskAnnouncement = ""
    private var lastRiskAnnouncementTrackId = ""
    private var lastRiskAnnouncementRank = -1
    private var riskAnnouncementHoldUntilMs = 0L
    private var lastNavigationAnnouncementMs = 0L
    private var lastNavigationAnnouncement = ""
    private var navigationAnnouncementHoldUntilMs = 0L
    private var lastAdvisoryAnnouncementMs = 0L
    private var lastAdvisoryAnnouncement = ""
    private var lastInteractionAnnouncementMs = 0L
    private var lastInteractionAnnouncement = ""
    private var pendingTalkBackInteraction: Runnable? = null
    @Volatile
    private var latestFeedbackDeliveryState = FeedbackDeliveryState()
    private var pendingFeedbackTerminalResolution: PendingFeedbackTerminalResolution? = null
    private var progressBeepEnabled = true
    private var progressBeepVolumePercent = 20
    private var lastProgressBeepAtMs = 0L
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var stepTracker: AndroidStepTracker
    private val activityOriginalUploadAdmission =
        ActivityOriginalUploadAdmissionController()
    private lateinit var earthOrientationTracker: AndroidEarthOrientationTracker
    private val stepLengthEstimator = StepLengthEstimator()
    private lateinit var stepLengthPrefs: SharedPreferences
    private lateinit var routeSnapshotStore: EncryptedRouteSnapshotStore
    private lateinit var sensitivePrefs: AndroidSensitivePreferenceStore
    private lateinit var gatewaySessionStore: AndroidGatewaySessionStore
    private lateinit var accountDeletionResetCoordinator: AccountDeletionResetCoordinator
    private var accountDeletionResetJournalStateAtStartup:
        AccountDeletionResetJournalState = AccountDeletionResetJournalState.Absent
    private lateinit var networkStateProbe: AndroidNetworkStateProbe
    private var permissionSessionPolicy = PermissionSessionPolicy()
    private var permissionRecoveryGate = PermissionRecoveryGate()
    @Volatile
    private lateinit var firstRunOnboardingSnapshot: FirstRunOnboardingSnapshot
    private lateinit var priorityUserOnboardingPolicy: PriorityUserOnboardingPolicy
    private var priorityUserOnboardingActorId: String? = null
    private val priorityUserStorageBlockedActorHashes = mutableSetOf<String>()
    private var priorityUserEducationInFlight = false
    private var priorityUserPracticeInFlight: PriorityUserPractice? = null
    private var priorityUserTrainingGeneration = 0L
    private lateinit var walkSessionLifecycle: WalkSessionLifecycle
    private lateinit var walkSessionResourceProbe: AndroidWalkSessionResourceProbe
    private var walkSessionResourceProbeStarted = false
    private var applyingRuntimeReadiness = false
    private var previousProcessHadInterruptedWalk = false
    private val walkingRouteClient = BackendWalkingRouteClient()
    private val reportUploader = AndroidReportUploader()
    private val gatewaySessionClient = GatewayFieldSessionClient()
    private val gatewayWalkSessionClient = GatewayWalkSessionClient()
    private val gatewayWalkAuthorityController = GatewayWalkAuthorityController()
    private lateinit var gatewayWalkRenewalHandler: Handler
    private val gatewayWalkRenewalRunnable = Runnable {
        requestGatewayWalkRenewal()
    }
    private val routeNavigator = RouteNavigator()
    private val tmapFailureGuard = ConsecutiveTmapFailureGuard(safetyStopThreshold = 2)
    private val reportUploaderExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-report-uploader").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val reportCleanupExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-report-cleanup").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val reportCleanupCallbackHandler: Handler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Handler(Looper.getMainLooper())
    }
    private enum class PrivacyStartupInspectionResult {
        READY,
        WAIT_FOR_ACCOUNT_DELETION_RESET,
        FINISH,
    }

    private val privacyStartupInspectionLock = Any()
    private val privacyStartupResourceLock = Any()
    private var privacyStartupInspectionGeneration = 0L
    @Volatile
    private var privacyStartupInspectionComplete = false
    @Volatile
    private var privacyStartupInspectionDestroyed = false
    private var privacyStartupResourcesClosed = false
    private var privacyStartupResourcesCloseScheduled = false
    private var privacyStartupResetHandoffScheduled = false
    private var pendingPrivacyStartupReadyAction: (() -> Unit)? = null
    private lateinit var privacyStartupInspectionStatus: TextView
    private val reportCandidatePolicy = AndroidReportCandidatePolicy()
    private val reportPrivacyConsentSession = ReportPrivacyConsentSession
    private val integratedConsentSession = IntegratedConsentSession()
    private val integratedConsentClient = AndroidIntegratedConsentClient()
    private val accountDeletionClient = AndroidPrivacyDeletionClient()
    private val accountDeletionProcessCoordinator =
        AccountDeletionProcessCoordinator.shared
    private val accountDeletionCleanupExecutor
        get() = accountDeletionProcessCoordinator.executor
    private val accountDeletionStateMachine: AccountDeletionStateMachine
        get() = accountDeletionProcessCoordinator.stateMachine
    private lateinit var accountDeletionActivityLease: AccountDeletionActivityLease
    private lateinit var accountDeletionIntentFence: AccountDeletionIntentFence
    private lateinit var accountDeletionDualAuthority: AccountDeletionDualAuthority
    private lateinit var accountDeletionPreparedConfirmationRecovery:
        PreparedAccountDeletionConfirmationRecovery
    private var accountDeletionAuthorityAtStartup = AccountDeletionAuthoritySnapshot(
        preferenceState = AccountDeletionIntentFenceState.UNAVAILABLE,
        fileState = AccountDeletionIntentAuthorityState.Unavailable,
        aggregateState = AccountDeletionAggregateAuthorityState.UNAVAILABLE,
    )
    private val integratedConsentLock = Any()
    private var integratedConsentDraft = IntegratedConsentSelections()
    private var integratedConsentClientRevision = 0L
    private var pendingIntegratedConsentMutation: PendingIntegratedConsentMutation? = null
    private var integratedConsentRequestGeneration = 0L
    private var integratedConsentRequestInFlight = false
    private var integratedConsentCall: CancellableNetworkCall<IntegratedConsentConfirmation?>? =
        null
    private val accountDeletionLock = Any()
    private enum class AccountDeletionCallPurpose {
        INITIAL,
        CAPABILITY_REPLAY,
        RECOVERY_ACCEPT_OR_REPLAY,
        STATUS,
        EVIDENCE,
    }

    private sealed class AccountDeletionRecoveryMarkerPreparation {
        data class Ready(
            val record: AndroidAccountDeletionFallbackMarker.Record,
        ) : AccountDeletionRecoveryMarkerPreparation()

        data class RetryablePresent(
            val record: AndroidAccountDeletionFallbackMarker.Record,
        ) : AccountDeletionRecoveryMarkerPreparation()

        object Absent : AccountDeletionRecoveryMarkerPreparation()

        object Blocked : AccountDeletionRecoveryMarkerPreparation()
    }

    private data class AccountDeletionAuthorityPreparation(
        val confirmed: Boolean,
        val retryable: Boolean,
        val failureReason: String?,
    )

    private sealed class AccountDeletionPendingJournalPreparation {
        data class Ready(
            val journal: AccountDeletionJournal,
        ) : AccountDeletionPendingJournalPreparation()

        object Retry : AccountDeletionPendingJournalPreparation()

        object Conflict : AccountDeletionPendingJournalPreparation()
    }

    private var accountDeletionRequestGeneration = 0L
    private var accountDeletionRequestInFlight = false
    private var accountDeletionCall: CancellableNetworkCall<AccountDeletionStatus>? = null
    @Volatile
    private var accountDeletionRev0ReauthenticationRequestId: String? = null
    @Volatile
    private var accountDeletionLegacyRev0GeneralReauthenticationRequestId: String? = null
    private val accountDeletionTerminalCleanupCoordinator =
        AccountDeletionTerminalCleanupCoordinator()
    private val accountDeletionTerminalCleanupComplete: Boolean
        get() = accountDeletionStateMachine.snapshotOrNull()
            ?.let(accountDeletionTerminalCleanupCoordinator::isResetEnabled) == true
    private val reportUploadSafetyLock = Any()
    private var reportUploadSafetyGeneration = 0L
    private var automaticReportUploadSafetyGeneration = 0L
    private val routeRequestInFlight = AtomicBoolean(false)
    private val navigationRequests = AndroidNavigationRequestCoordinator()
    private val gatewaySessionOwner = Any()
    private val reportLocationStartScheduled = AtomicBoolean(false)
    private val gatewaySessionExecutor
        get() = GatewaySessionProcessCoordinator.executor
    @Volatile
    private var latestTrustedLocation: TrustedLocation? = null
    private val gatewayFieldSession: GatewayFieldSession?
        get() = GatewaySessionProcessCoordinator.snapshot().let { snapshot ->
            snapshot.session.takeUnless { snapshot.deletionRecoveryOnly }
        }
    private val gatewaySessionGeneration: Long
        get() = GatewaySessionProcessCoordinator.snapshot().generation
    private val gatewaySessionStorageBlocked: Boolean
        get() = GatewaySessionProcessCoordinator.snapshot().storageBlocked
    private var gatewayCapacityConnectivityManager: ConnectivityManager? = null
    private var gatewayCapacityNetworkObserverRegistered = false
    private val gatewayCapacityNetworkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                requestGatewayCapacityRefresh("network_reconnected")
            }
        }
    private var currentDestination: RoutePoint? = null
    @Volatile
    private var latestStepCount: Int = 0
    private var routeStartStepCount: Int? = null
    private var stepTrackingEpoch: WalkRuntimeEpoch? = null
    @Volatile
    private var latestReportCandidateStatus = "reportCandidate=blocked"
    @Volatile
    private var latestHeadingDeg: Float? = null
    @Volatile
    private var reporterUserId: String? = null
    @Volatile
    private var latestExplicitReportOutput: TrackedObjectDepth? = null
    @Volatile
    private var latestExplicitReportImage: ByteArray? = null
    @Volatile
    private var latestExplicitReportGateState: DeviceGateState? = null
    @Volatile
    private var latestExplicitReportCapturedAtMs: Long = 0L
    @Volatile
    private var isRouteActive = false
    @Volatile
    private var latestTmapOnRoute = false
    private var directionGuidancePauseReason: String? = null
    private var lastAnnouncedRouteDecisionToken: RouteNavigatorDecisionToken? = null
    private var routeDeviationHapticDecision: RouteNavigatorUserDecision? = null
    private var routeSnapshotPurgeFailed = false
    private var routeSnapshotExpiryRunnable: Runnable? = null
    @Volatile
    private var latestTactileRouteState = "localRoute=tmap:navigation_inactive"
    private var navigationPermissionsRequestedForRoute = false
    private var navigationPermissionsRequestedForReport = false
    private var destinationSearchInFlight = false
    private var destinationSearchGeneration = 0
    private var destinationSearchQuery = ""
    private var destinationSearchPage = 1
    private val destinationSearchResults = mutableListOf<DestinationSearchResult>()
    private var pendingVoiceDestinationQuery: String? = null
    private var pendingVoiceDestinationPageIndex: Int? = null
    private var destinationSearchVoiceState: DestinationSearchVoiceState? = null
    private var routeRequestGeneration = 0
    private val routeExecutor = newNavigationRequestExecutor()
    private var reportRuntimeConfig: TwoModelRuntimeConfig? = null
    private var reportModelConfigSha256: String? = null
    private var reportApkSha256: String? = null
    private var reportAttemptStore = AndroidReportAttemptStore()
    private val legacyPendingReportQueuePurgeLock = Any()
    private var legacyPendingReportQueuePurgeInFlight = false
    private var legacyPendingReportQueuePurgeSucceeded = false
    private val legacyPendingReportQueuePurgeWaiters =
        mutableListOf<LegacyPendingReportQueuePurgeWaiter>()
    private val reportCleanupActivityToken = UUID.randomUUID().toString()
    private var reportCleanupGeneration = 0L
    @Volatile
    private var reportCleanupDestroyed = false
    private val accountDeletionFallbackMarker by lazy {
        AndroidAccountDeletionFallbackMarker(applicationContext)
    }
    private var accountDeletionFallbackPresentAtStartup = false
    @Volatile
    private var accountDeletionStartupFallbackState:
        AndroidAccountDeletionFallbackMarker.State =
            AndroidAccountDeletionFallbackMarker.State.Absent
    private var accountDeletionRemoteResumeBlocked = false
    private var accountDeletionLocalPurgeInFlightRequestId: String? = null
    private val reportAttemptStateLock = Any()
    private var reportAttemptStateActorHash: String? = null
    private var reportAttemptStateStorageBlocked = false
    private val persistedReportAttemptStates =
        mutableMapOf<String, PersistedReportAttemptState>()
    private val reportAttemptStorageBlockedActors = mutableSetOf<String>()

    private data class PersistedReportAttemptState(
        val consecutiveFailures: Int,
        val retryNotBeforeMs: Long,
        val terminalStatus: Int?,
        val terminalUntilMs: Long,
    )

    private data class LegacyPendingReportQueuePurgeWaiter(
        val activityToken: String,
        val generation: Long,
        val callback: (Boolean) -> Unit,
    )
    private lateinit var metadataLogUploader: MetadataLogUploader
    private lateinit var frameCaptureUploader: FrameCaptureUploader
    private lateinit var fieldSessionLog: FieldSessionLog
    private val frameCapturePreprocessor = YuvImagePreprocessor()
    private val detectorExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "walksafe-tflite-detector").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val detectionInFlight = AtomicBoolean(false)
    private val frameCaptureRequested = AtomicBoolean(false)
    private val frameStateLock = Any()
    @Volatile
    private var latestNavigationState = "navigation=not_started"

    private var cameraTextureId = 0
    private var cameraTextureBound = false
    private var lastUiUpdateMs = 0L
    private var lastOverlayUpdateMs = 0L
    @Volatile
    private var lastDetectionRunMs = 0L
    @Volatile
    private var latestDetectionSnapshot = DetectionSnapshot.empty()
    @Volatile
    private var latestTactileOverlaySnapshot = DetectionSnapshot.empty()
    @Volatile
    private var lastNonEmptyOverlaySnapshot = DetectionSnapshot.empty()
    @Volatile
    private var detectorGeneration = 0
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var pendingSessionStartAfterInstall = false
    private var arCoreAvailabilityRequestGeneration = 0
    private var arCoreAvailabilityCheckPending = false
    private var actionMode = ActionMode.START
    private var actionRequestedEnabled = true
    private lateinit var startupCapabilityProbe: AndroidStartupCapabilityProbe
    private var startupCapabilityProbeStarted = false
    @Volatile
    private var startupCapabilityDecision: WalkSafeStartupCapabilityDecision? = null
    @Volatile
    private var confirmedStartupCapabilityDecision: WalkSafeStartupCapabilityDecision? = null
    private var startupCapabilityConfirmationPending = false
    private var startupCapabilityRetryRequiresUserAction = false
    @Volatile
    private var officialEnvironmentUserConfirmation: OfficialEnvironmentUserConfirmation? = null
    @Volatile
    private var officialEnvironmentGpsEvidence: MeasuredEnvironmentEvidence? = null
    @Volatile
    private var officialEnvironmentCameraEvidence: MeasuredEnvironmentEvidence? = null
    private var officialEnvironmentGpsCancellation: CancellationTokenSource? = null
    private var officialEnvironmentPreflightGeneration = 0L
    private var officialEnvironmentRuntimeGuard: OfficialEnvironmentRuntimeGuard? = null
    @Volatile
    private var officialEnvironmentOutputsAllowed = false
    private var officialEnvironmentWatchdogGeneration = 0L
    @Volatile
    private var phoneMountingUserConfirmation: PhoneMountingUserConfirmation? = null
    private val phoneMountingObservationLock = Any()
    @Volatile
    private var latestPhoneMountingCameraAssessment: CameraFrameQualityAssessment? = null
    @Volatile
    private var phoneMountingRuntimeState: PhoneMountingRuntimeState? = null
    @Volatile
    private var phoneMountingOutputsAllowed = false
    @Volatile
    private var phoneMountingObservationGeneration = 0L
    private var phoneMountingObservationSequence = 0L
    private var phoneMountingPendingFaultSequence = 0L
    private var phoneMountingAppliedFaultSequence = 0L
    private var phoneMountingWatchdogGeneration = 0L
    private var phoneMountingRuntimeRetryGeneration = 0L
    private var phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs: Long? = null
    private var metricDistanceCapabilityOverride: Boolean? = null
    private val runtimeMetricStateLock = Any()
    @Volatile
    private var runtimeMetricPreflightGeneration = 0L
    @Volatile
    private var metricPreflightLifecycleGeneration = 0
    private var metricPreflightFirstRunLease: FirstRunAsyncLease? = null
    private var metricPreflightArSessionGeneration = 0L
    private var pendingMetricPreflightPermissionGeneration: Long? = null
    @Volatile
    private var runtimeMetricPreflightSession: RuntimeMetricPreflightSession? = null
    @Volatile
    private var metricPreflightTerminalDispatchedGeneration = 0L
    @Volatile
    private var runtimeMetricOutputAllowed = false
    @Volatile
    private var runtimeMetricActivationSession: RuntimeMetricPreflightSession? = null
    @Volatile
    private var runtimeMetricLastValidFrameAtMs = 0L
    @Volatile
    private var runtimeMetricLastFrameTimestampNanos = 0L
    private var runtimeMetricInitialNavigationStartPending = false
    private var onDeviceSpeechRecognitionCapabilityOverride: Boolean? = null
    private var offlineKoreanTextToSpeechCapabilityOverride: Boolean? = null
    private var lastPersistedStartupCapabilityDecision: WalkSafeStartupCapabilityDecision? = null
    private var pendingCameraFallbackStart: PendingCameraFallbackStart? = null
    @Volatile
    private var isActivityForeground = false
    @Volatile
    private var feedbackLifecycleGeneration = 0
    private var arCoreSupported = false
    private var depthSupported = false
    @Volatile
    private var detectorAvailable = false
    private var detectorConfigLoaded = false
    private var detectorModelKeyForReports: String? = null
    @Volatile
    private var detectorLoadedModelKey: String? = null
    private var detectorModelFallbackUsed: Boolean = false
    @Volatile
    private var detectorLoadReason: String? = null
    private var detectorLoadAttempted = false
    @Volatile
    private var detectorStatusText = "detector=load_pending_after_camera_gate"
    private var locationCallback: LocationCallback? = null
    private var locationCallbackGeneration = 0
    private var lastCalibrationLocation: TrustedLocation? = null
    private var lastCalibrationStepCount: Int = 0
    private var lastCalibrationAtMs: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        accountDeletionActivityLease = accountDeletionProcessCoordinator.attach()
        gatewayWalkRenewalHandler = Handler(Looper.getMainLooper())
        walkBackDispatcher = OnBackPressedDispatcher { finishAfterTransition() }
        walkBackDispatcher.addCallback(walkScreenBackCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            walkBackDispatcher.setOnBackInvokedDispatcher(onBackInvokedDispatcher)
        }
        firstRunOnboardingSnapshot = FirstRunOnboardingPolicy.initial(
            epoch = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L),
        )
        stepLengthPrefs = getSharedPreferences("walksafe", MODE_PRIVATE)
        routeSnapshotStore = EncryptedRouteSnapshotStore(
            getSharedPreferences("walksafe_route_snapshot", MODE_PRIVATE),
        )
        val routeSnapshotNowEpochMs = System.currentTimeMillis()
        routeSnapshotPurgeFailed = !runCatching {
            routeSnapshotStore.purgeExpired(routeSnapshotNowEpochMs)
        }.getOrDefault(false)
        if (!routeSnapshotPurgeFailed) {
            runCatching { routeSnapshotStore.load(routeSnapshotNowEpochMs) }
                .getOrNull()
                ?.let { snapshot ->
                    scheduleEncryptedRouteSnapshotExpiry(
                        walkSessionId = snapshot.walkSessionId,
                        expiresAtEpochMs = snapshot.expiresAtEpochMs,
                    )
                }
        }
        accountDeletionIntentFence = AccountDeletionIntentFence(
            read = {
                runCatching {
                    val values = stepLengthPrefs.all
                    if (!values.containsKey(PREF_ACCOUNT_DELETION_INTENT_FENCE)) {
                        false
                    } else {
                        (values[PREF_ACCOUNT_DELETION_INTENT_FENCE] as? Boolean)
                            ?.takeIf { it }
                    }
                }.getOrNull()
            },
            store = run {
                @SuppressLint("UseKtx")
                val commitFence = {
                    stepLengthPrefs.edit()
                        .putBoolean(PREF_ACCOUNT_DELETION_INTENT_FENCE, true)
                        .commit()
                }
                commitFence
            },
            clear = run {
                @SuppressLint("UseKtx")
                val clearFence = {
                    stepLengthPrefs.edit()
                        .remove(PREF_ACCOUNT_DELETION_INTENT_FENCE)
                        .commit()
                }
                clearFence
            },
        )
        fieldSessionLog = NoopFieldSessionLog()
        networkStateProbe = AndroidNetworkStateProbe(this)
        walkSessionLifecycle = WalkSessionLifecycle()
        gatewayWalkAuthorityController.reset()
        walkSessionResourceProbe = AndroidWalkSessionResourceProbe(this)
        cameraFallbackLifecycleOwner = CameraFallbackLifecycleOwner().also {
            it.moveTo(Lifecycle.State.CREATED)
        }
        setContentView(buildPrivacyStartupInspectionView())
        startPrivacyStartupInspection(
            inspect = inspection@{
                val noBackupRoot = noBackupFilesDir
                val fileAccountDeletionIntentAuthority =
                    FileAccountDeletionIntentAuthority(
                        File(noBackupRoot, "account_deletion_intent_authority"),
                    )
                accountDeletionPreparedConfirmationRecovery =
                    fileAccountDeletionIntentAuthority
                accountDeletionDualAuthority = AccountDeletionDualAuthority(
                    preferenceFence = accountDeletionIntentFence,
                    fileAuthority = fileAccountDeletionIntentAuthority,
                )
                accountDeletionResetCoordinator =
                    createAccountDeletionResetCoordinator(noBackupRoot)
                accountDeletionAuthorityAtStartup =
                    accountDeletionDualAuthority.startupState()
                accountDeletionResetJournalStateAtStartup =
                    accountDeletionResetCoordinator.journalState()
                val legacyResetIntentAtStartup = legacyAccountDeletionResetIntentState()
                sensitivePrefs = AndroidSensitivePreferenceStore(
                    preferences = stepLengthPrefs,
                    spec = SENSITIVE_PREF_SPEC,
                    initiallyBlockedForExternalReset =
                        accountDeletionResetJournalStateAtStartup !is
                            AccountDeletionResetJournalState.Absent ||
                            legacyResetIntentAtStartup != LegacyResetIntentState.ABSENT,
                )
                gatewaySessionStore = AndroidGatewaySessionStore(stepLengthPrefs)
                readAccountDeletionFallbackMarkerAtStartup()
                previousProcessHadInterruptedWalk =
                    stepLengthPrefs.getBoolean(PREF_WALK_SESSION_INTERRUPTED, false)
                stepLengthPrefs.edit()
                    .putBoolean(PREF_WALK_SESSION_INTERRUPTED, false)
                    .commit()
                restorePermissionRecoveryGateFromPrefs()
                metadataLogUploader = DebugMetadataLogUploaderFactory.create(this)
                frameCaptureUploader = DebugFrameCaptureUploaderFactory.create(this)
                prepareReportMetadataContext()
                fieldSessionLog = if (BuildConfig.DEBUG) {
                    PersistentFieldSessionLog(
                        rootDirectory = File(filesDir, "field_sessions"),
                        deviceInfo = FieldSessionDeviceInfo(
                            model = Build.MODEL,
                            androidVersion = Build.VERSION.RELEASE,
                            appVersionName = BuildConfig.VERSION_NAME,
                            sourceCommit = BuildConfig.WALKSAFE_SOURCE_COMMIT,
                            apkSha256 = reportApkSha256,
                            modelConfigSha256 = reportModelConfigSha256,
                        ),
                        initiallyBlockedForAccountDeletion =
                            accountDeletionFallbackPresentAtStartup ||
                                accountDeletionMarkerPresentAtStartup() ||
                                accountDeletionAuthorityAtStartup.blocksPrivacy,
                        initiallyBlockedForRawSourceCollection =
                            stepLengthPrefs.getBoolean(
                                PREF_RAW_SOURCE_FIELD_LOG_BLOCKED,
                                false,
                            ),
                    )
                } else {
                    NoopFieldSessionLog()
                }
                if (accountDeletionStartupResetHandoffPending) {
                    if (
                        !accountDeletionStateMachine.activityLeaseIsCurrent(
                            accountDeletionActivityLease,
                        )
                    ) return@inspection PrivacyStartupInspectionResult.FINISH
                    val resetAccepted =
                        accountDeletionStateMachine.resetForNewEnrollment(
                            accountDeletionActivityLease,
                        )
                    if (
                        !resetAccepted &&
                        accountDeletionStateMachine.phase() !=
                        AccountDeletionPhase.IDLE
                    ) return@inspection PrivacyStartupInspectionResult.FINISH
                    accountDeletionStartupResetHandoffPending = false
                    reportPrivacyConsentSession.resetForNewEnrollment()
                }
                when (reconcileAccountDeletionResetAtStartup()) {
                    AccountDeletionResetResult.BLOCKED ->
                        if (!blockForAccountDeletionResetFailure()) {
                            return@inspection PrivacyStartupInspectionResult.FINISH
                        }
                    AccountDeletionResetResult.COMPLETED_ELSEWHERE ->
                        return@inspection PrivacyStartupInspectionResult.FINISH
                    AccountDeletionResetResult.COMPLETED -> {
                        accountDeletionStartupResetHandoffPending = true
                        accountDeletionAuthorityAtStartup =
                            accountDeletionDualAuthority.startupState()
                        if (
                            accountDeletionAuthorityAtStartup.aggregateState ==
                            AccountDeletionAggregateAuthorityState.ABSENT
                        ) {
                            val resetAccepted =
                                accountDeletionStateMachine.resetForNewEnrollment(
                                    accountDeletionActivityLease,
                                )
                            if (
                                !resetAccepted &&
                                (
                                    !accountDeletionStateMachine.activityLeaseIsCurrent(
                                        accountDeletionActivityLease,
                                    ) ||
                                        accountDeletionStateMachine.phase() !=
                                        AccountDeletionPhase.IDLE
                                    )
                            ) {
                                return@inspection PrivacyStartupInspectionResult.FINISH
                            }
                            accountDeletionStartupResetHandoffPending = false
                            reportPrivacyConsentSession.resetForNewEnrollment()
                        } else {
                            if (!blockForAccountDeletionResetFailure()) {
                                return@inspection PrivacyStartupInspectionResult.FINISH
                            }
                        }
                    }
                    AccountDeletionResetResult.NO_PENDING -> Unit
                }
                restoreReportCooldownsFromPrefs()
                restoreStepLengthFromPrefs()
                restoreProgressBeepPrefs()
                purgeUnownedLegacyPriorityUserOnboardingPrefs()
                restorePermissionSessionStateFromPrefs()
                if (!restorePrivacyControlStateFromPrefs()) {
                    return@inspection PrivacyStartupInspectionResult.FINISH
                }
                restorePriorityUserOnboardingFromPrefs()
                if (permissionRecoveryGate.blocksAutomaticResourceStart) {
                    fieldSessionLog.blockActiveSessionRestore()
                }
                fieldSessionLog.recordEvent("app_created")
                if (previousProcessHadInterruptedWalk) {
                    fieldSessionLog.recordEvent(
                        "previous_walk_interrupted_fresh_walk_created",
                    )
                }
                PrivacyStartupInspectionResult.READY
            },
            onReady = {
                scheduleLegacyPendingReportQueuePurge()
                reconcileAccountDeletionFallbackMarkerAtStartup()
                syncActiveSessionScreenPolicy()
                if (accountDeletionStateMachine.processingBlocked()) {
                    applyAccountDeletionRuntimeFence()
                }
                if (accountDeletionFallbackPresentAtStartup) {
                    applyAccountDeletionRuntimeFence()
                    startAccountDeletionFallbackRecoveryAfterRestore()
                }
                GatewaySessionProcessCoordinator.attach(
                    gatewaySessionOwner,
                    ::onGatewayProcessSessionChanged,
                )
                registerGatewayCapacityNetworkObserver()
                scheduleGatewaySessionRestoreAfterPrivacyStartupInspection()
                enforcePriorityUserAccountEligibility()
                fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
                stepTracker = AndroidStepTracker(
                    context = this,
                    onStepCountChanged = { steps ->
                        val expectedEpoch = stepTrackingEpoch
                        if (
                            expectedEpoch != null &&
                            walkSessionLifecycle.isRuntimeEpochCurrent(expectedEpoch)
                        ) {
                            latestStepCount = steps
                        }
                    },
                    onTrackingStarted = {
                        activityOriginalUploadAdmission.onTrackingStarted(
                            observedAtMs = SystemClock.elapsedRealtime(),
                            cancelActiveUploads = ::cancelActivityOriginalUploads,
                        )
                    },
                    onMotionSensorSample = { steps ->
                        activityOriginalUploadAdmission.onSensorSample(
                            stepCount = steps,
                            observedAtMs = SystemClock.elapsedRealtime(),
                            cancelActiveUploads = ::cancelActivityOriginalUploads,
                        )
                    },
                )
                earthOrientationTracker = AndroidEarthOrientationTracker(this)
                setContentView(buildContentView())
                updatePermissionRecoveryUi()
                if (accountDeletionStateMachine.processingBlocked()) {
                    resumePrivacyControlOperations()
                } else {
                    schedulePrivacyControlOperationsAfterStartupInspection()
                }
                startupCapabilityProbe = AndroidStartupCapabilityProbe(this) {
                    refreshStartupCapabilityUi()
                }
                maybeStartFirstRunDeviceCheckProbes()
                refreshStartupCapabilityUi()
                updateRouteButtonText()
                updateStatus(
                    status = "ARCore Depth 대기",
                    detail = "목적지 없이 위험 인식 모드를 시작할 수 있습니다. TFLite는 카메라 권한과 기기 기능 확인 후 로드합니다.",
                )
            },
        )
    }

    private fun buildPrivacyStartupInspectionView(): View {
        privacyStartupInspectionStatus = TextView(this).apply {
            text = "개인정보 보호 상태를 확인하고 있습니다."
            textSize = 18f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        return FrameLayout(this).apply {
            addView(
                privacyStartupInspectionStatus,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    private fun startPrivacyStartupInspection(
        inspect: () -> PrivacyStartupInspectionResult,
        onReady: () -> Unit,
    ) {
        val generation = synchronized(privacyStartupInspectionLock) {
            privacyStartupInspectionGeneration += 1L
            privacyStartupInspectionGeneration
        }
        try {
            reportCleanupExecutor.execute {
                val result = synchronized(PRIVACY_STARTUP_PROCESS_LOCK) {
                    synchronized(privacyStartupResourceLock) {
                        if (
                            privacyStartupResourcesClosed ||
                            !privacyStartupInspectionIsCurrent(generation)
                        ) {
                            null
                        } else if (
                            accountDeletionStateMachine.phase() ==
                            AccountDeletionPhase.RESET_PENDING
                        ) {
                            PrivacyStartupInspectionResult
                                .WAIT_FOR_ACCOUNT_DELETION_RESET
                        } else {
                            runCatching(inspect).getOrNull()
                        }
                    }
                }
                val workerCurrent = privacyStartupInspectionIsCurrent(generation)
                if (result != PrivacyStartupInspectionResult.READY || !workerCurrent) {
                    closePrivacyStartupResourcesOnWorker()
                }
                val callbackPosted = reportCleanupCallbackHandler.post {
                    val current = synchronized(privacyStartupInspectionLock) {
                        !privacyStartupInspectionDestroyed &&
                            generation == privacyStartupInspectionGeneration
                    }
                    if (!current) {
                        schedulePrivacyStartupResourcesClose()
                        return@post
                    }
                    if (
                        !accountDeletionStateMachine.activityLeaseIsCurrent(
                            accountDeletionActivityLease,
                        )
                    ) {
                        schedulePrivacyStartupResourcesClose()
                        return@post
                    }
                    if (result == PrivacyStartupInspectionResult.FINISH) {
                        finish()
                        return@post
                    }
                    if (
                        result ==
                        PrivacyStartupInspectionResult
                            .WAIT_FOR_ACCOUNT_DELETION_RESET
                    ) {
                        schedulePrivacyStartupResetHandoff(generation)
                        return@post
                    }
                    if (result != PrivacyStartupInspectionResult.READY) {
                        privacyStartupInspectionStatus.text =
                            "개인정보 보호 상태를 확인할 수 없어 기능을 시작하지 않았습니다."
                        return@post
                    }
                    pendingPrivacyStartupReadyAction = onReady
                    completePrivacyStartupReadyIfForeground()
                }
                if (!callbackPosted) schedulePrivacyStartupResourcesClose()
            }
        } catch (_: RejectedExecutionException) {
            schedulePrivacyStartupResourcesClose()
            privacyStartupInspectionStatus.text =
                "개인정보 보호 상태를 확인할 수 없어 기능을 시작하지 않았습니다."
        }
    }

    private fun privacyStartupInspectionIsCurrent(generation: Long): Boolean =
        synchronized(privacyStartupInspectionLock) {
            !privacyStartupInspectionDestroyed &&
                generation == privacyStartupInspectionGeneration
        } &&
            accountDeletionStateMachine.activityLeaseIsCurrent(
                accountDeletionActivityLease,
            )

    private fun schedulePrivacyStartupResourcesClose() {
        val shouldSchedule = synchronized(privacyStartupResourceLock) {
            if (
                privacyStartupResourcesClosed ||
                privacyStartupResourcesCloseScheduled
            ) {
                false
            } else {
                privacyStartupResourcesCloseScheduled = true
                true
            }
        }
        if (!shouldSchedule) return
        try {
            accountDeletionCleanupExecutor.execute(
                ::closePrivacyStartupResourcesOnWorker,
            )
        } catch (_: RejectedExecutionException) {
            synchronized(privacyStartupResourceLock) {
                privacyStartupResourcesCloseScheduled = false
            }
        }
    }

    private fun schedulePrivacyStartupResetHandoff(generation: Long) {
        if (privacyStartupResetHandoffScheduled) return
        privacyStartupResetHandoffScheduled = true
        privacyStartupInspectionStatus.text =
            "계정 삭제 완료 상태를 안전하게 정리하고 있습니다."
        try {
            accountDeletionCleanupExecutor.execute {
                val resetStillPending =
                    accountDeletionStateMachine.phase() ==
                    AccountDeletionPhase.RESET_PENDING
                reportCleanupCallbackHandler.post {
                    if (!privacyStartupInspectionIsCurrent(generation)) return@post
                    privacyStartupResetHandoffScheduled = false
                    if (resetStillPending) {
                        privacyStartupInspectionStatus.text =
                            "계정 삭제 초기화를 완료하지 못해 기능을 시작하지 않았습니다."
                    } else {
                        recreate()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            privacyStartupResetHandoffScheduled = false
            privacyStartupInspectionStatus.text =
                "계정 삭제 초기화를 완료하지 못해 기능을 시작하지 않았습니다."
        }
    }

    private fun closePrivacyStartupResourcesOnWorker() {
        synchronized(privacyStartupResourceLock) {
            privacyStartupResourcesCloseScheduled = false
            if (privacyStartupResourcesClosed) return
            privacyStartupResourcesClosed = true
            if (::metadataLogUploader.isInitialized) {
                runCatching { metadataLogUploader.close() }
            }
            if (::frameCaptureUploader.isInitialized) {
                runCatching { frameCaptureUploader.close() }
            }
            if (::fieldSessionLog.isInitialized) {
                runCatching { fieldSessionLog.close() }
            }
        }
    }

    private fun completePrivacyStartupReadyIfForeground() {
        if (
            privacyStartupInspectionDestroyed ||
            privacyStartupInspectionComplete ||
            !isActivityForeground
        ) return
        val onReady = pendingPrivacyStartupReadyAction ?: return
        pendingPrivacyStartupReadyAction = null
        privacyStartupInspectionComplete = true
        onReady()
        if (isActivityForeground) {
            resumeWalkSafeRuntimeAfterPrivacyStartupInspection()
        }
    }

    private fun scheduleGatewaySessionRestoreAfterPrivacyStartupInspection() {
        try {
            reportCleanupExecutor.execute {
                if (
                    privacyStartupInspectionComplete &&
                    !privacyStartupInspectionDestroyed
                ) {
                    restoreGatewaySessionFromPrefs()
                }
            }
        } catch (_: RejectedExecutionException) {
            permissionSessionPolicy.authenticationExpired()
        }
    }

    private fun schedulePrivacyControlOperationsAfterStartupInspection() {
        try {
            reportCleanupExecutor.execute {
                if (
                    !privacyStartupInspectionComplete ||
                    privacyStartupInspectionDestroyed
                ) return@execute
                if (accountDeletionStateMachine.processingBlocked()) {
                    reportCleanupCallbackHandler.post {
                        if (!privacyStartupInspectionDestroyed) {
                            resumePrivacyControlOperations()
                        }
                    }
                    return@execute
                }
                resumePrivacyControlOperations()
            }
        } catch (_: RejectedExecutionException) {
            integratedConsentSession.failClosed()
        }
    }

    private fun restoreStepLengthFromPrefs() {
        val savedStepLength = stepLengthPrefs.getFloat(PREF_STEP_LENGTH_KEY, StepLengthEstimator.DEFAULT_STEP_LENGTH_M)
        stepLengthEstimator.setStepLengthM(savedStepLength)
        objectDepthPipeline.setUserStepLength(savedStepLength)
    }

    private fun persistStepLength() {
        stepLengthPrefs.edit().putFloat(PREF_STEP_LENGTH_KEY, stepLengthEstimator.stepLengthM).apply()
    }

    private fun restoreProgressBeepPrefs() {
        progressBeepEnabled = stepLengthPrefs.getBoolean(PREF_PROGRESS_BEEP_ENABLED_KEY, true)
        progressBeepVolumePercent = stepLengthPrefs.getInt(PREF_PROGRESS_BEEP_VOLUME_KEY, DEFAULT_PROGRESS_BEEP_VOLUME_PERCENT)
            .coerceIn(0, 100)
    }

    private fun persistProgressBeepPrefs() {
        stepLengthPrefs.edit()
            .putBoolean(PREF_PROGRESS_BEEP_ENABLED_KEY, progressBeepEnabled)
            .putInt(PREF_PROGRESS_BEEP_VOLUME_KEY, progressBeepVolumePercent)
            .apply()
    }

    private fun purgeUnownedLegacyPriorityUserOnboardingPrefs() {
        stepLengthPrefs.edit()
            .remove(LEGACY_PREF_PRIORITY_USER_POLICY_VERSION)
            .remove(LEGACY_PREF_PRIORITY_USER_AGE_BAND)
            .remove(LEGACY_PREF_PRIORITY_USER_GUARDIAN_VERIFIED)
            .remove(LEGACY_PREF_PRIORITY_USER_EDUCATION_REVIEWED)
            .remove(LEGACY_PREF_PRIORITY_USER_SAFE_PLACE_CONFIRMED)
            .remove(LEGACY_PREF_PRIORITY_USER_COMPLETED_PRACTICES)
            .commit()
    }

    private fun priorityUserActorSha256(actorId: String): String =
        sha256Hex(actorId.toByteArray())

    private fun priorityUserProfileKey(actorId: String): String =
        "$PREF_PRIORITY_USER_PROFILE_PREFIX${priorityUserActorSha256(actorId)}"

    private fun priorityUserProfileInvalidKey(actorId: String): String =
        "$PREF_PRIORITY_USER_PROFILE_INVALID_PREFIX${priorityUserActorSha256(actorId)}"

    private fun restorePriorityUserOnboardingFromPrefs() {
        val actorId = reporterUserId
        priorityUserOnboardingActorId = actorId
        if (actorId == null) {
            priorityUserOnboardingPolicy = PriorityUserOnboardingPolicy()
            return
        }
        val actorSha256 = priorityUserActorSha256(actorId)
        if (sensitivePrefs.isBlocked()) {
            priorityUserStorageBlockedActorHashes.add(actorSha256)
            priorityUserOnboardingPolicy = PriorityUserOnboardingPolicy()
            return
        }
        val profileKey = priorityUserProfileKey(actorId)
        val profileInvalid = actorSha256 in priorityUserStorageBlockedActorHashes ||
            runCatching {
                sensitivePrefs.getBoolean(
                    priorityUserProfileInvalidKey(actorId),
                    false,
                )
            }.getOrDefault(true)
        if (profileInvalid) {
            priorityUserOnboardingPolicy = PriorityUserOnboardingPolicy()
            return
        }
        val profilePresent = sensitivePrefs.contains(profileKey)
        val rawProfile = runCatching {
            sensitivePrefs.getString(profileKey, null)
        }.getOrNull()
        val snapshot = rawProfile?.let { raw ->
            runCatching {
                val profile = JSONObject(raw)
                check(profile.getInt("storage_version") == PRIORITY_USER_PROFILE_STORAGE_VERSION)
                check(profile.getString("actor_id_sha256") == actorSha256)
                check(
                    profile.getString("policy_version") ==
                        PRIORITY_USER_TRAINING_POLICY_VERSION,
                )
                val practices = profile.getJSONArray("completed_practices")
                val completedPractices = buildList {
                    for (index in 0 until practices.length()) {
                        add(PriorityUserPractice.valueOf(practices.getString(index)))
                    }
                }
                check(
                    completedPractices ==
                        PriorityUserPractice.entries.take(completedPractices.size),
                )
                PriorityUserOnboardingSnapshot(
                    ageBand = PriorityUserAgeBand.valueOf(
                        profile.getString("age_band"),
                    ),
                    guardianVerified = profile.getBoolean("guardian_verified"),
                    educationReviewed = profile.getBoolean("education_reviewed"),
                    safePracticePlaceConfirmed =
                        profile.getBoolean("safe_practice_place_confirmed"),
                    completedPractices = completedPractices.toSet(),
                )
            }.getOrNull()
        }
        if (profilePresent && snapshot == null) {
            sensitivePrefs.edit().remove(profileKey).commit()
            priorityUserStorageBlockedActorHashes.add(actorSha256)
        }
        priorityUserOnboardingPolicy = PriorityUserOnboardingPolicy(
            snapshot ?: PriorityUserOnboardingSnapshot(),
        )
    }

    private fun persistPriorityUserOnboarding(): Boolean {
        val actorId = priorityUserOnboardingActorId ?: return false
        if (actorId != reporterUserId) return false
        if (sensitivePrefs.isBlocked()) return false
        val invalidKey = priorityUserProfileInvalidKey(actorId)
        if (!runCatching { sensitivePrefs.getBoolean(invalidKey, false) }.getOrDefault(false)) {
            return false
        }
        val snapshot = priorityUserOnboardingPolicy.snapshot()
        val profile = JSONObject()
            .put("storage_version", PRIORITY_USER_PROFILE_STORAGE_VERSION)
            .put("actor_id_sha256", priorityUserActorSha256(actorId))
            .put("policy_version", snapshot.policyVersion)
            .put("age_band", snapshot.ageBand.name)
            .put("guardian_verified", snapshot.guardianVerified)
            .put("education_reviewed", snapshot.educationReviewed)
            .put(
                "safe_practice_place_confirmed",
                snapshot.safePracticePlaceConfirmed,
            )
            .put(
                "completed_practices",
                JSONArray(
                    PriorityUserPractice.entries
                        .filter(snapshot.completedPractices::contains)
                        .map(PriorityUserPractice::name),
                ),
            )
        val committed = sensitivePrefs.edit()
            .putString(priorityUserProfileKey(actorId), profile.toString())
            .remove(invalidKey)
            .commit()
        if (committed) {
            priorityUserStorageBlockedActorHashes.remove(
                priorityUserActorSha256(actorId),
            )
        }
        return committed
    }

    private fun beginPriorityUserProfileMutationOrFailClosed(): Boolean {
        val actorId = priorityUserOnboardingActorId ?: return false
        if (actorId != reporterUserId) return false
        val actorSha256 = priorityUserActorSha256(actorId)
        val invalidKey = priorityUserProfileInvalidKey(actorId)
        val invalidated = !sensitivePrefs.isBlocked() &&
            sensitivePrefs.edit().putBoolean(invalidKey, true).commit()
        if (invalidated) {
            priorityUserStorageBlockedActorHashes.remove(actorSha256)
        } else {
            priorityUserStorageBlockedActorHashes.add(actorSha256)
            priorityUserOnboardingPolicy = PriorityUserOnboardingPolicy()
        }
        return invalidated
    }

    private fun enforcePriorityUserAccountEligibility() {
        if (
            priorityUserOnboardingActorId == reporterUserId &&
            priorityUserOnboardingPolicy.accountBlockReason() == null
        ) {
            return
        }
        clearGatewaySession(logoutRemote = true)
    }

    private fun restorePermissionSessionStateFromPrefs() {
        reporterUserId = null
        stepLengthPrefs.edit().remove(PREF_REPORTER_USER_ID_KEY).commit()
        if (sensitivePrefs.isBlocked()) {
            integratedConsentClientRevision = 0L
            integratedConsentDraft = IntegratedConsentSelections()
            integratedConsentSession.failClosed()
            permissionSessionPolicy = PermissionSessionPolicy(
                PermissionSessionSnapshot(
                    actorId = null,
                    authentication = AuthenticationState.SIGNED_OUT,
                ),
            )
            return
        }
        integratedConsentClientRevision =
            sensitivePrefs.getLong(
                PREF_INTEGRATED_CONSENT_CLIENT_REVISION,
                0L,
            ).coerceAtLeast(0L)
        integratedConsentDraft =
            if (
                sensitivePrefs.getString(PREF_INTEGRATED_CONSENT_POLICY_VERSION, null) ==
                INTEGRATED_CONSENT_POLICY_VERSION
            ) {
                IntegratedConsentSelections(
                    rawSourceCollection =
                        sensitivePrefs.getBoolean(PREF_REPORT_PRIVACY_CONSENT_KEY, false),
                    automaticReporting =
                        sensitivePrefs.getBoolean(
                            PREF_AUTOMATIC_REPORT_CONSENT_KEY,
                            false,
                        ),
                    mobileNetworkTransfer =
                        MobileNetworkPreference.fromWireValue(
                            sensitivePrefs.getString(
                                PREF_MOBILE_NETWORK_PREFERENCE_KEY,
                                null,
                            ),
                        ) == MobileNetworkPreference.ALLOW_CELLULAR,
                    trainingReuse =
                        sensitivePrefs.getBoolean(
                            PREF_TRAINING_REUSE_CONSENT_KEY,
                            false,
                        ),
                )
            } else {
                IntegratedConsentSelections()
            }
        permissionSessionPolicy = PermissionSessionPolicy(
            PermissionSessionSnapshot(
                actorId = reporterUserId,
                authentication = AuthenticationState.SIGNED_OUT,
            ),
        )
    }

    private fun restorePermissionRecoveryGateFromPrefs() {
        val persistedState = stepLengthPrefs
            .getString(PREF_PERMISSION_RECOVERY_GATE_STATE, null)
            ?.let { value ->
                runCatching { PermissionRecoveryGateState.valueOf(value) }.getOrNull()
            }
        if (
            persistedState == null ||
            persistedState == PermissionRecoveryGateState.CLEAR
        ) {
            permissionRecoveryGate = PermissionRecoveryGate()
            return
        }
        val affected = stepLengthPrefs
            .getString(PREF_PERMISSION_RECOVERY_GATE_ITEMS, "")
            .orEmpty()
            .split(',')
            .mapNotNull { value ->
                runCatching { ObservedPermission.valueOf(value) }.getOrNull()
            }
            .toSet()
        permissionRecoveryGate = PermissionRecoveryGate.restoredBlocked(affected)
    }

    private fun restorePrivacyControlStateFromPrefs(): Boolean {
        if (!gatewayActivityCallbackAllowed(accountDeletionActivityLease)) return false
        if (sensitivePrefs.isBlocked()) {
            val startupDecision = accountDeletionStartupAuthorityDecision(
                snapshot = accountDeletionAuthorityAtStartup,
                journalPresent = false,
            )
            val preparedMarkerRecovery =
                preparedAccountDeletionRecoveryCandidateOrNull() != null &&
                    accountDeletionAuthorityAtStartup.fileState !is
                    AccountDeletionIntentAuthorityState.FailClosed
            val unreadableMarker =
                accountDeletionStartupFallbackState is
                    AndroidAccountDeletionFallbackMarker.State.Corrupt ||
                    accountDeletionStartupFallbackState is
                    AndroidAccountDeletionFallbackMarker.State.Unavailable
            if (
                startupDecision.durableConfirmationRecoveryRequired ||
                preparedMarkerRecovery
            ) {
                if (
                    !accountDeletionStateMachine.enterDurableConfirmationRecovery(
                        accountDeletionActivityLease,
                    )
                ) return false
                integratedConsentSession.blockForAccountDeletion()
            } else if (
                unreadableMarker &&
                accountDeletionAuthorityAtStartup.fileState !is
                AccountDeletionIntentAuthorityState.FailClosed
            ) {
                accountDeletionRemoteResumeBlocked = true
                integratedConsentSession.blockForAccountDeletion()
            } else {
                val reason = startupDecision.failClosedReason
                    ?: "sensitive_storage_blocked"
                integratedConsentSession.failClosed()
                if (
                    accountDeletionAuthorityAtStartup.fileState is
                    AccountDeletionIntentAuthorityState.FailClosed
                ) {
                    if (
                        !accountDeletionStateMachine.enforceFailClosedAuthority(
                            accountDeletionActivityLease,
                            reason,
                        )
                    ) return false
                } else {
                    if (
                        !accountDeletionStateMachine.failClosed(
                            accountDeletionActivityLease,
                            reason,
                        )
                    ) return false
                }
                if (!gatewayActivityCallbackAllowed(accountDeletionActivityLease)) return false
                accountDeletionDualAuthority.failClosed(reason)
            }
            reportPrivacyConsentSession.blockForAccountDeletion()
            stepLengthPrefs.edit()
                .putBoolean(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED, true)
                .commit()
            return true
        }
        val legacyFloorRestored =
            restoreLegacyIntegratedConsentRevisionFloor()
        sensitivePrefs.getString(PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION, null)
            ?.let { raw ->
                val confirmation = integratedConsentConfirmationOrNull(raw)
                val installationId =
                    gatewaySessionStore.getOrCreateInstallDeviceId()
                val controlSecret = existingIntegratedConsentControlSecretOrNull()
                val legacyPolicyVersion =
                    sensitivePrefs.getString(
                        PREF_INTEGRATED_CONSENT_POLICY_VERSION,
                        null,
                    )
                val legacyRevision =
                    sensitivePrefs.getLong(PREF_INTEGRATED_CONSENT_REVISION, 0L)
                val legacyClientRevision =
                    sensitivePrefs.getLong(
                        PREF_INTEGRATED_CONSENT_CLIENT_REVISION,
                        0L,
                    )
                val legacySelections = persistedIntegratedConsentSelections()
                val legacyReceipt =
                    sensitivePrefs.getString(
                        PREF_INTEGRATED_CONSENT_RECEIPT_SHA256,
                        null,
                    )
                if (
                    !legacyFloorRestored ||
                    confirmation == null ||
                    confirmation.installationId != installationId ||
                    confirmation.policyVersion != legacyPolicyVersion ||
                    confirmation.revision != legacyRevision ||
                    confirmation.clientRevision != legacyClientRevision ||
                    confirmation.selections != legacySelections ||
                    confirmation.receiptSha256 != legacyReceipt ||
                    confirmation.controlSecret != controlSecret ||
                    !integratedConsentSession.restoreCurrentConfirmation(confirmation)
                ) {
                    integratedConsentSession.failClosed()
                } else {
                    integratedConsentDraft = confirmation.selections
                    integratedConsentClientRevision =
                        maxOf(
                            integratedConsentClientRevision,
                            confirmation.clientRevision,
                        )
                    permissionSessionPolicy.applyIntegratedConsentSelections(
                        confirmation.selections,
                    )
                }
            }
        sensitivePrefs.getString(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED, null)
            ?.let { raw ->
                val items = localWithdrawalMarkerItemsOrNull(raw)
                if (items == null) {
                    integratedConsentSession.failClosed()
                } else {
                    applyImmediateConsentWithdrawals(items)
                    integratedConsentSession.failClosed()
                }
            }
        sensitivePrefs.getString(PREF_PENDING_INTEGRATED_CONSENT_MUTATION, null)
            ?.let { raw ->
                val mutation = pendingIntegratedConsentMutationOrNull(raw)
                if (mutation == null) {
                    integratedConsentSession.failClosed()
                } else {
                    integratedConsentSession.restorePendingMutation(mutation, retry = true)
                    if (integratedConsentSession.pendingMutationOrNull() != mutation) {
                        integratedConsentSession.failClosed()
                    } else {
                        pendingIntegratedConsentMutation = mutation
                        integratedConsentClientRevision =
                            maxOf(
                                integratedConsentClientRevision,
                                mutation.clientRevision,
                            )
                        integratedConsentDraft = mutation.desiredSelections
                    }
                }
            }
        sensitivePrefs.getString(PREF_ACCOUNT_DELETION_JOURNAL, null)
            ?.let { raw ->
                val journal = accountDeletionJournalOrNull(raw)
                if (journal == null) {
                    if (
                        !forceAccountDeletionAuthorityFailClosed(
                            "account_deletion_journal_corrupt",
                        )
                    ) return false
                    integratedConsentSession.blockForAccountDeletion()
                    reportPrivacyConsentSession.blockForAccountDeletion()
                } else {
                    when (
                        accountDeletionStateMachine.restoreAtStartup(
                            journal,
                            accountDeletionActivityLease,
                        )
                    ) {
                        AccountDeletionStartupRestoreResult.RESTORED,
                        AccountDeletionStartupRestoreResult
                            .RETAINED_MONOTONIC_PROCESS_SUCCESSOR,
                        -> Unit
                        AccountDeletionStartupRestoreResult.STALE_ACTIVITY,
                        AccountDeletionStartupRestoreResult.CONFLICT,
                        -> return false
                    }
                    integratedConsentSession.blockForAccountDeletion()
                    reportPrivacyConsentSession.blockForAccountDeletion()
                }
            }
        if (
            sensitivePrefs.getBoolean(
                PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                false,
            ) &&
            accountDeletionStateMachine.snapshotOrNull() == null
        ) {
            val persistedReason = sensitivePrefs.getString(
                PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                null,
            )?.takeIf(ACCOUNT_DELETION_FAIL_CLOSED_REASON::matches)
            if (
                !forceAccountDeletionAuthorityFailClosed(
                    persistedReason ?: "account_deletion_blocked_marker",
                )
            ) return false
            integratedConsentSession.blockForAccountDeletion()
            reportPrivacyConsentSession.blockForAccountDeletion()
            if (::fieldSessionLog.isInitialized) {
                fieldSessionLog.blockForAccountDeletion()
            }
        }
        if (legacyAccountDeletionResetIntentState() != LegacyResetIntentState.ABSENT) {
            if (
                !forceAccountDeletionAuthorityFailClosed(
                    "account_deletion_reset_pending",
                )
            ) return false
            integratedConsentSession.blockForAccountDeletion()
            reportPrivacyConsentSession.blockForAccountDeletion()
        }
        return reconcileAccountDeletionAuthorityAfterJournalRestore()
    }

    private fun reconcileAccountDeletionAuthorityAfterJournalRestore(): Boolean {
        if (!gatewayActivityCallbackAllowed(accountDeletionActivityLease)) return false
        val restoredJournal = accountDeletionStateMachine.snapshotOrNull()
        var authority = accountDeletionDualAuthority.startupState()

        if (
            restoredJournal != null &&
            authority.fileState !is AccountDeletionIntentAuthorityState.FailClosed
        ) {
            val migrated = if (
                restoredJournal.phase == AccountDeletionPhase.FAIL_CLOSED
            ) {
                val upgrade =
                    accountDeletionStateMachine.beginLegacyFailClosedUpgradeAttempt(
                        restoredJournal,
                        accountDeletionActivityLease,
                    )
                val result = upgrade?.let {
                    accountDeletionStateMachine.runLegacyFailClosedUpgradeStageIfCurrent(
                        upgrade,
                    ) { terminalJournal ->
                        val reason = terminalJournal.lastErrorCode
                            ?: "account_deletion_fail_closed"
                        accountDeletionDualAuthority.failClosed(reason).durableSuccess
                    }
                }
                val afterUpgrade = accountDeletionDualAuthority.startupState()
                result == AccountDeletionWorkerStageResult.SUCCEEDED ||
                    (
                        afterUpgrade.fileState is
                            AccountDeletionIntentAuthorityState.FailClosed &&
                            afterUpgrade.fileState.reason ==
                            restoredJournal.lastErrorCode
                        )
            } else if (
                authority.fileState == AccountDeletionIntentAuthorityState.Absent
            ) {
                val attempt = accountDeletionStateMachine.beginWorkerAttempt(
                    restoredJournal,
                    accountDeletionActivityLease,
                )
                val result = attempt?.let {
                    runAccountDeletionWorkerDurableStage(it) {
                        accountDeletionDualAuthority.confirm().decision ==
                            AccountDeletionConfirmationDecision.ACCEPTED
                    }
                }
                result == true ||
                    accountDeletionDualAuthority.startupState().fileState ==
                    AccountDeletionIntentAuthorityState.Confirmed
            } else {
                true
            }
            authority = accountDeletionDualAuthority.startupState()
            if (!migrated) {
                if (!gatewayActivityCallbackAllowed(accountDeletionActivityLease)) {
                    return false
                }
                if (
                    !forceAccountDeletionAuthorityFailClosed(
                        "deletion_intent_authority_migration_failed",
                    )
                ) return false
                authority = accountDeletionDualAuthority.startupState()
            }
        }

        val startupDecision = accountDeletionStartupAuthorityDecision(
            snapshot = authority,
            journalPresent = restoredJournal != null,
        )
        val preparedMarkerRecovery =
            restoredJournal == null &&
                preparedAccountDeletionRecoveryCandidateOrNull() != null &&
                authority.fileState !is AccountDeletionIntentAuthorityState.FailClosed
        if (
            startupDecision.durableConfirmationRecoveryRequired ||
            preparedMarkerRecovery
        ) {
            if (
                !accountDeletionStateMachine.enterDurableConfirmationRecovery(
                    accountDeletionActivityLease,
                )
            ) return false
        } else {
            startupDecision.failClosedReason?.let { reason ->
                if (authority.fileState is AccountDeletionIntentAuthorityState.FailClosed) {
                    if (
                        !accountDeletionStateMachine.enforceFailClosedAuthority(
                            accountDeletionActivityLease,
                            reason,
                        )
                    ) return false
                    persistAccountDeletionTerminalStateOrHandle(
                        accountDeletionStateMachine.snapshotOrNull(),
                        reason,
                    )
                } else if (!forceAccountDeletionAuthorityFailClosed(reason)) {
                    return false
                }
            }
        }
        if (startupDecision.blocksPrivacy || preparedMarkerRecovery) {
            blockPrivacyForAccountDeletionAuthority()
        }
        accountDeletionAuthorityAtStartup = accountDeletionDualAuthority.startupState()
        return true
    }

    private fun forceAccountDeletionAuthorityFailClosed(reason: String): Boolean {
        if (
            !accountDeletionStateMachine.failClosed(
                accountDeletionActivityLease,
                reason,
            )
        ) return false
        if (
            !persistAccountDeletionTerminalState(
                accountDeletionStateMachine.snapshotOrNull(),
                reason,
            )
        ) {
            accountDeletionDualAuthority.failClosed(reason)
        }
        return true
    }

    private fun blockPrivacyForAccountDeletionAuthority() {
        integratedConsentSession.blockForAccountDeletion()
        reportPrivacyConsentSession.blockForAccountDeletion()
        if (::fieldSessionLog.isInitialized) {
            fieldSessionLog.blockForAccountDeletion()
        }
    }

    private fun accountDeletionMarkerPresentAtStartup(): Boolean =
        sensitivePrefs.isBlocked() ||
            sensitivePrefs.contains(PREF_ACCOUNT_DELETION_JOURNAL) ||
            sensitivePrefs.getBoolean(
                PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                false,
            ) ||
            accountDeletionAuthorityAtStartup.blocksPrivacy ||
            accountDeletionResetCoordinator.hasPendingOrCorruptJournal() ||
            legacyAccountDeletionResetIntentState() != LegacyResetIntentState.ABSENT

    private fun restoreLegacyIntegratedConsentRevisionFloor(): Boolean {
        if (sensitivePrefs.isBlocked()) return false
        val revision =
            sensitivePrefs.getLong(PREF_INTEGRATED_CONSENT_REVISION, 0L)
        if (revision <= 0L) return false
        val policyVersion =
            sensitivePrefs.getString(
                PREF_INTEGRATED_CONSENT_POLICY_VERSION,
                null,
            )
        val clientRevision =
            sensitivePrefs.getLong(PREF_INTEGRATED_CONSENT_CLIENT_REVISION, 0L)
        val receipt =
            sensitivePrefs.getString(
                PREF_INTEGRATED_CONSENT_RECEIPT_SHA256,
                null,
            )
        val installationId = gatewaySessionStore.getOrCreateInstallDeviceId()
        if (
            policyVersion != INTEGRATED_CONSENT_POLICY_VERSION ||
            clientRevision <= 0L ||
            receipt?.let(INTEGRATED_CONSENT_RECEIPT_SHA256::matches) != true ||
            installationId == null ||
            !integratedConsentSession.restoreServerRevisionFloor(
                installationId = installationId,
                policyVersion = policyVersion,
                revision = revision,
            )
        ) {
            integratedConsentSession.failClosed()
            return false
        }
        integratedConsentClientRevision =
            maxOf(integratedConsentClientRevision, clientRevision)
        integratedConsentDraft = persistedIntegratedConsentSelections()
        permissionSessionPolicy.applyIntegratedConsentSelections(
            integratedConsentDraft,
        )
        if (!integratedConsentDraft.rawSourceCollection) {
            fieldSessionLog.blockForRawSourceWithdrawal()
            if (
                !stepLengthPrefs.edit()
                    .putBoolean(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED, true)
                    .commit()
            ) {
                integratedConsentSession.failClosed()
                return false
            }
        }
        return true
    }

    private fun resumePrivacyControlOperations() {
        if (accountDeletionStateMachine.processingBlocked()) {
            applyAccountDeletionFenceAndPurgeLocal()
            if (
                accountDeletionStateMachine.phase() !in setOf(
                    AccountDeletionPhase.COMPLETED,
                    AccountDeletionPhase.FAIL_CLOSED,
                    AccountDeletionPhase.RESET_PENDING,
                    AccountDeletionPhase.REENROLLMENT_REQUIRED,
                )
            ) {
                refreshAccountDeletionStatus()
            }
            updateAccountDeletionUi()
            return
        }
        if (pendingIntegratedConsentMutation != null) {
            retryPendingIntegratedConsentMutation()
        } else {
            refreshIntegratedConsentFromServer()
        }
    }

    private fun integratedConsentConfirmationJson(
        confirmation: IntegratedConsentConfirmation,
    ): String =
        JSONObject()
            .put("schema_version", confirmation.schemaVersion)
            .put("policy_version", confirmation.policyVersion)
            .put("installation_id", confirmation.installationId)
            .put("request_id", confirmation.requestId)
            .put(
                "item_versions",
                JSONObject()
                    .put(
                        "raw_source_collection",
                        confirmation.itemVersions.rawSourceCollection,
                    )
                    .put(
                        "automatic_reporting",
                        confirmation.itemVersions.automaticReporting,
                    )
                    .put(
                        "mobile_network_transfer",
                        confirmation.itemVersions.mobileNetworkTransfer,
                    )
                    .put(
                        "training_reuse",
                        confirmation.itemVersions.trainingReuse,
                    ),
            )
            .put("client_revision", confirmation.clientRevision)
            .put("revision", confirmation.revision)
            .put(
                "selections",
                integratedConsentSelectionsJson(confirmation.selections),
            )
            .put("confirmed_at", confirmation.confirmedAt)
            .put("receipt_sha256", confirmation.receiptSha256)
            .put("control_secret", confirmation.controlSecret)
            .toString()

    private fun integratedConsentConfirmationOrNull(
        raw: String,
    ): IntegratedConsentConfirmation? = runCatching {
        val root = JSONObject(raw)
        if (
            root.jsonKeySet() != setOf(
                "schema_version",
                "policy_version",
                "installation_id",
                "request_id",
                "item_versions",
                "client_revision",
                "revision",
                "selections",
                "confirmed_at",
                "receipt_sha256",
                "control_secret",
            )
        ) return null
        val itemVersions = root.getJSONObject("item_versions")
        if (
            itemVersions.jsonKeySet() != setOf(
                "raw_source_collection",
                "automatic_reporting",
                "mobile_network_transfer",
                "training_reuse",
            )
        ) return null
        val selections =
            integratedConsentSelectionsOrNull(root.getJSONObject("selections"))
                ?: return null
        IntegratedConsentConfirmation(
            schemaVersion = root.getString("schema_version"),
            policyVersion = root.getString("policy_version"),
            installationId = root.getString("installation_id"),
            requestId = root.getString("request_id"),
            itemVersions =
                kr.co.hanium.dreamup.walksafe.session.IntegratedConsentItemVersions(
                    rawSourceCollection =
                        itemVersions.getString("raw_source_collection"),
                    automaticReporting =
                        itemVersions.getString("automatic_reporting"),
                    mobileNetworkTransfer =
                        itemVersions.getString("mobile_network_transfer"),
                    trainingReuse =
                        itemVersions.getString("training_reuse"),
                ),
            clientRevision = root.getLong("client_revision"),
            revision = root.getLong("revision"),
            selections = selections,
            confirmedAt = root.getString("confirmed_at"),
            receiptSha256 = root.getString("receipt_sha256"),
            controlSecret = root.getString("control_secret"),
        )
    }.getOrNull()

    private fun pendingIntegratedConsentMutationJson(
        mutation: PendingIntegratedConsentMutation,
    ): String =
        JSONObject()
            .put("schema_version", PENDING_CONSENT_MUTATION_SCHEMA_VERSION)
            .put("installation_id", mutation.installationId)
            .put("request_id", mutation.requestId)
            .put("policy_version", mutation.policyVersion)
            .put("client_revision", mutation.clientRevision)
            .put("previous_server_revision", mutation.previousServerRevision)
            .put(
                "desired_selections",
                integratedConsentSelectionsJson(mutation.desiredSelections),
            )
            .put(
                "withdrawal_items",
                JSONArray(mutation.withdrawalItems.map(IntegratedConsentItem::wireValue)),
            )
            .put("created_at_epoch_ms", mutation.createdAtEpochMs)
            .toString()

    private fun pendingIntegratedConsentMutationOrNull(
        raw: String,
    ): PendingIntegratedConsentMutation? = runCatching {
        val root = JSONObject(raw)
        if (
            root.jsonKeySet() != setOf(
                "schema_version",
                "installation_id",
                "request_id",
                "policy_version",
                "client_revision",
                "previous_server_revision",
                "desired_selections",
                "withdrawal_items",
                "created_at_epoch_ms",
            ) ||
            root.getString("schema_version") != PENDING_CONSENT_MUTATION_SCHEMA_VERSION
        ) return null
        val withdrawals = root.getJSONArray("withdrawal_items")
        val items = mutableSetOf<IntegratedConsentItem>()
        for (index in 0 until withdrawals.length()) {
            val wireValue = withdrawals.getString(index)
            val item = IntegratedConsentItem.entries
                .singleOrNull { it.wireValue == wireValue } ?: return null
            if (!items.add(item)) return null
        }
        PendingIntegratedConsentMutation(
            installationId = root.getString("installation_id"),
            requestId = root.getString("request_id"),
            policyVersion = root.getString("policy_version"),
            clientRevision = root.getLong("client_revision"),
            previousServerRevision = root.getLong("previous_server_revision"),
            desiredSelections =
                integratedConsentSelectionsOrNull(root.getJSONObject("desired_selections"))
                    ?: return null,
            withdrawalItems = items,
            createdAtEpochMs = root.getLong("created_at_epoch_ms"),
        )
    }.getOrNull()

    private fun integratedConsentSelectionsJson(
        selections: IntegratedConsentSelections,
    ): JSONObject =
        JSONObject()
            .put("raw_source_collection", selections.rawSourceCollection)
            .put("automatic_reporting", selections.automaticReporting)
            .put("mobile_network_transfer", selections.mobileNetworkTransfer)
            .put("training_reuse", selections.trainingReuse)

    private fun integratedConsentSelectionsOrNull(
        value: JSONObject,
    ): IntegratedConsentSelections? {
        if (
            value.jsonKeySet() != setOf(
                "raw_source_collection",
                "automatic_reporting",
                "mobile_network_transfer",
                "training_reuse",
            )
        ) return null
        return runCatching {
            IntegratedConsentSelections(
                rawSourceCollection = value.getBoolean("raw_source_collection"),
                automaticReporting = value.getBoolean("automatic_reporting"),
                mobileNetworkTransfer = value.getBoolean("mobile_network_transfer"),
                trainingReuse = value.getBoolean("training_reuse"),
            )
        }.getOrNull()
    }

    private fun localWithdrawalMarkerItemsOrNull(
        raw: String,
    ): Set<IntegratedConsentItem>? = runCatching {
        val array = JSONArray(raw)
        if (array.length() == 0) return null
        val items = mutableSetOf<IntegratedConsentItem>()
        for (index in 0 until array.length()) {
            val wireValue = array.getString(index)
            val item =
                IntegratedConsentItem.entries.singleOrNull { it.wireValue == wireValue }
                    ?: return null
            if (!items.add(item)) return null
        }
        items
    }.getOrNull()

    private fun onAccountDeletionPrimaryClicked() {
        if (accountDeletionStateMachine.phase() == AccountDeletionPhase.COMPLETED) {
            resetAfterConfirmedAccountDeletion()
            return
        }
        if (
            !accountDeletionStateMachine.requestConfirmation(
                accountDeletionActivityLease,
            )
        ) return
        updateAccountDeletionUi()
        speakInteraction(
            "계정 삭제는 새 개인정보 처리를 즉시 막고 단말, 서버, 학습자료와 백업 삭제를 요청합니다. 확인 버튼을 다시 누르세요.",
        )
    }

    private fun onAccountDeletionCancelClicked() {
        if (
            !accountDeletionStateMachine.cancelConfirmation(
                accountDeletionActivityLease,
            )
        ) {
            if (accountDeletionStateMachine.durableConfirmationRecoveryRequired()) {
                keepAccountDeletionPrivacyFenceClosed()
                speakInteraction(
                    "이미 저장된 삭제 확인은 취소할 수 없습니다. 삭제 요청 준비를 다시 시도하세요.",
                )
            }
            return
        }
        updateAccountDeletionUi()
        speakInteraction("계정 삭제 확인을 취소했습니다.")
    }

    private fun onAccountDeletionConfirmClicked() {
        if (
            accountDeletionStateMachine.phase() !=
            AccountDeletionPhase.CONFIRM_REQUIRED
        ) return
        val recoveringDurableConfirmation =
            accountDeletionStateMachine.durableConfirmationRecoveryRequired()
        val capturedSession = if (recoveringDurableConfirmation) {
            accountDeletionRecoveryGatewaySessionOrNull()
        } else {
            gatewaySessionOrNull(reason = "account_deletion", speak = false)
        }
        val capturedOrigin = configuredGatewayOriginOrNull()
        val capturedActorId = if (recoveringDurableConfirmation) {
            accountDeletionRecoveryActorIdOrNull()
        } else {
            currentReporterUserId()
        }
        if (
            capturedSession == null ||
            capturedOrigin == null ||
            capturedSession.gatewayBaseUrl != capturedOrigin ||
            capturedActorId == null ||
            capturedSession.actorId != capturedActorId
        ) {
            updateNavigationStatus(
                if (recoveringDurableConfirmation) {
                    "accountDeletion=recovery_required:verified_login_required"
                } else {
                    "accountDeletion=not_accepted:verified_login_required"
                },
            )
            if (recoveringDurableConfirmation) applyAccountDeletionRuntimeFence()
            updateAccountDeletionUi()
            speakInteraction(
                if (recoveringDurableConfirmation) {
                    "삭제 확인은 보존됐습니다. 현장 게이트웨이에 다시 로그인한 뒤 재시도하세요."
                } else {
                    "현장 게이트웨이에 먼저 로그인하세요."
                },
            )
            return
        }
        if (sensitivePrefs.isBlocked()) {
            if (recoveringDurableConfirmation) {
                applyAccountDeletionRuntimeFence()
                updateNavigationStatus(
                    "accountDeletion=recovery_required:sensitive_storage_blocked",
                )
                updateAccountDeletionUi()
                speakInteraction(
                    "삭제 확인은 보존됐지만 보호 저장소를 사용할 수 없습니다. 저장소를 복구한 뒤 다시 시도하세요.",
                )
            } else {
                updateNavigationStatus(
                    "accountDeletion=not_accepted:sensitive_storage_blocked",
                )
                updateAccountDeletionUi()
                speakInteraction("보호 저장소를 사용할 수 없어 삭제 요청을 확인하지 않았습니다.")
            }
            return
        }
        applyAccountDeletionRuntimeFence()
        val confirmationAttempt =
            accountDeletionStateMachine.beginPreparationWorkerAttempt(
                accountDeletionActivityLease,
            ) ?: run {
                if (recoveringDurableConfirmation) {
                    keepAccountDeletionPrivacyFenceClosed()
                }
                return
            }
        startAccountDeletionConfirmationPreparation(
            trustedGatewayOrigin = capturedOrigin,
            session = capturedSession,
            actorId = capturedActorId,
            recoveringDurableConfirmation = recoveringDurableConfirmation,
            workerAttempt = confirmationAttempt,
        )
    }

    private fun startAccountDeletionConfirmationPreparation(
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        actorId: String,
        recoveringDurableConfirmation: Boolean,
        workerAttempt: AccountDeletionWorkerAttempt,
    ) {
        val generation = synchronized(accountDeletionLock) {
            accountDeletionRequestGeneration += 1L
            accountDeletionRequestInFlight = true
            accountDeletionRequestGeneration
        }
        postAccountDeletionUiRefresh()
        try {
            accountDeletionCleanupExecutor.execute {
                var preparation: AccountDeletionRecoveryMarkerPreparation =
                    AccountDeletionRecoveryMarkerPreparation.Blocked
                val prepared = runAccountDeletionWorkerDurableStage(workerAttempt) {
                    preparation = prepareAccountDeletionRecoveryMarker(
                        trustedGatewayOrigin = trustedGatewayOrigin,
                        session = session,
                        actorId = actorId,
                    )
                    preparation is AccountDeletionRecoveryMarkerPreparation.Ready
                }
                if (prepared == null) {
                    completeAccountDeletionPreparation(generation)
                    return@execute
                }
                postAccountDeletionRecoveryMarkerPreparation(
                    generation = generation,
                    trustedGatewayOrigin = trustedGatewayOrigin,
                    session = session,
                    recoveringDurableConfirmation = recoveringDurableConfirmation,
                    workerAttempt = workerAttempt,
                    preparation = preparation,
                )
            }
        } catch (_: RejectedExecutionException) {
            completeAccountDeletionPreparation(generation)
            if (recoveringDurableConfirmation) {
                keepAccountDeletionPrivacyFenceClosed()
            }
            updateNavigationStatus(
                if (recoveringDurableConfirmation) {
                    "accountDeletion=recovery_required:cleanup_executor_rejected"
                } else {
                    "accountDeletion=not_accepted:cleanup_executor_rejected"
                },
            )
            updateAccountDeletionUi()
        }
    }

    private fun postAccountDeletionRecoveryMarkerPreparation(
        generation: Long,
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        recoveringDurableConfirmation: Boolean,
        workerAttempt: AccountDeletionWorkerAttempt,
        preparation: AccountDeletionRecoveryMarkerPreparation,
    ) {
        val activityToken = reportCleanupActivityToken
        reportCleanupCallbackHandler.post {
            if (
                reportCleanupDestroyed ||
                activityToken != reportCleanupActivityToken ||
                !isCurrentAccountDeletionPreparation(generation) ||
                !accountDeletionWorkerStageAllowed(workerAttempt)
            ) return@post
            when (preparation) {
                is AccountDeletionRecoveryMarkerPreparation.Ready -> {
                    if (
                        !accountDeletionStateMachine
                            .retainDurableConfirmationRecovery(workerAttempt)
                    ) {
                        completeAccountDeletionPreparation(generation)
                        keepAccountDeletionPrivacyFenceClosed()
                        return@post
                    }
                    applyAccountDeletionRuntimeFence()
                    accountDeletionRemoteResumeBlocked = false
                    val recoveryAttempt =
                        accountDeletionStateMachine.beginPreparationWorkerAttempt(
                            accountDeletionActivityLease,
                        ) ?: run {
                            completeAccountDeletionPreparation(generation)
                            keepAccountDeletionPrivacyFenceClosed()
                            return@post
                        }
                    startPreparedAccountDeletionAuthorityConfirmation(
                        generation = generation,
                        trustedGatewayOrigin = trustedGatewayOrigin,
                        session = session,
                        markerRecord = preparation.record,
                        workerAttempt = recoveryAttempt,
                    )
                }
                is AccountDeletionRecoveryMarkerPreparation.RetryablePresent -> {
                    completeAccountDeletionPreparation(generation)
                    accountDeletionStartupFallbackState =
                        AndroidAccountDeletionFallbackMarker.State.Present(
                            preparation.record,
                        )
                    accountDeletionFallbackPresentAtStartup = true
                    applyAccountDeletionRuntimeFence()
                    retainAccountDeletionPreparationRecovery(
                        reason = "fallback_marker_durability_retry",
                        workerAttempt = workerAttempt,
                        announcement =
                            "삭제 복구 정보가 보존됐지만 저장소 동기화를 다시 확인해야 합니다. 다시 시도하세요.",
                    )
                }
                AccountDeletionRecoveryMarkerPreparation.Absent -> {
                    completeAccountDeletionPreparation(generation)
                    if (recoveringDurableConfirmation) {
                        retainAccountDeletionPreparationRecovery(
                            reason = "fallback_marker_storage",
                            workerAttempt = workerAttempt,
                            announcement =
                                "삭제 확인은 보존됐지만 복구 정보를 저장하지 못했습니다. 다시 시도하세요.",
                        )
                    } else {
                        if (
                            !accountDeletionStateMachine.abortPreparationRecovery(
                                accountDeletionActivityLease,
                            )
                        ) {
                            keepAccountDeletionPrivacyFenceClosed()
                            return@post
                        }
                        integratedConsentSession.resetForNewEnrollment()
                        reportPrivacyConsentSession.resetForNewEnrollment()
                        permissionSessionPolicy.applyIntegratedConsentSelections(
                            integratedConsentDraft,
                        )
                        schedulePrivacyControlOperationsAfterStartupInspection()
                        updateNavigationStatus(
                            "accountDeletion=not_accepted:fallback_marker_storage",
                        )
                        updateAccountDeletionUi()
                        speakInteraction(
                            "삭제 요청 복구 정보를 저장하지 못해 삭제 요청을 확인하지 않았습니다.",
                        )
                    }
                }
                AccountDeletionRecoveryMarkerPreparation.Blocked -> {
                    completeAccountDeletionPreparation(generation)
                    accountDeletionRemoteResumeBlocked = true
                    applyAccountDeletionRuntimeFence()
                    if (recoveringDurableConfirmation) {
                        retainAccountDeletionPreparationRecovery(
                            reason = "fallback_marker_storage",
                            workerAttempt = workerAttempt,
                            announcement =
                                "삭제 확인은 보존됐지만 복구 저장소를 신뢰할 수 없습니다. 저장소를 복구한 뒤 다시 시도하세요.",
                        )
                    } else {
                        updateNavigationStatus(
                            "accountDeletion=blocked:fallback_marker_storage",
                        )
                        updateAccountDeletionUi()
                        speakInteraction(
                            "삭제 복구 저장소를 신뢰할 수 없어 개인정보 기능을 잠갔습니다.",
                        )
                    }
                }
            }
        }
    }

    private fun prepareAccountDeletionRecoveryMarker(
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        actorId: String,
    ): AccountDeletionRecoveryMarkerPreparation {
        if (
            session.actorId != actorId ||
            session.gatewayBaseUrl != trustedGatewayOrigin
        ) return AccountDeletionRecoveryMarkerPreparation.Blocked
        val actorHash = sha256Hex(actorId.toByteArray(Charsets.UTF_8))
        val installationId =
            gatewaySessionStore.getOrCreateInstallDeviceId()
                ?: return AccountDeletionRecoveryMarkerPreparation.Blocked
        var durabilityVerified = false
        val expected = when (val markerState = accountDeletionFallbackMarker.read()) {
            is AndroidAccountDeletionFallbackMarker.State.Absent -> {
                val requestRandom = ByteArray(32)
                val capabilityRandom = ByteArray(32)
                SecureRandom().nextBytes(requestRandom)
                SecureRandom().nextBytes(capabilityRandom)
                val requestId = "account_delete_" +
                    requestRandom.joinToString("") {
                        "%02x".format(Locale.US, it.toInt() and 0xff)
                    }
                requestRandom.fill(0)
                val record = AndroidAccountDeletionFallbackMarker.Record(
                    identity = AndroidAccountDeletionFallbackMarker.Identity(
                        requestId = requestId,
                        actorHash = actorHash,
                    ),
                    recoveryActorId = actorId,
                    gatewayOrigin = trustedGatewayOrigin,
                    installationId = installationId,
                    accessSecret = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(capabilityRandom),
                    clientRevision = 1L,
                    phase = AndroidAccountDeletionFallbackMarker.Phase.PREPARED,
                )
                capabilityRandom.fill(0)
                durabilityVerified = accountDeletionFallbackMarker.create(record)
                record
            }
            is AndroidAccountDeletionFallbackMarker.State.Present -> {
                val current = markerState.record.takeIf {
                    it.identity.actorHash == actorHash &&
                        it.gatewayOrigin == trustedGatewayOrigin &&
                        it.installationId == installationId &&
                        it.phase == AndroidAccountDeletionFallbackMarker.Phase.PREPARED &&
                        (it.recoveryActorId == null || it.recoveryActorId == actorId)
                } ?: return AccountDeletionRecoveryMarkerPreparation.Blocked
                if (current.recoveryActorId == actorId) {
                    current.also {
                        durabilityVerified = accountDeletionFallbackMarker.create(it)
                    }
                } else {
                    current.copy(recoveryActorId = actorId).also {
                        durabilityVerified = accountDeletionFallbackMarker.update(it)
                    }
                }
            }
            is AndroidAccountDeletionFallbackMarker.State.Corrupt,
            is AndroidAccountDeletionFallbackMarker.State.Unavailable,
            -> return AccountDeletionRecoveryMarkerPreparation.Blocked
        }
        val readback = accountDeletionFallbackMarker.read()
        if (
            durabilityVerified &&
            readback is AndroidAccountDeletionFallbackMarker.State.Present &&
            readback.record == expected
        ) {
            accountDeletionStartupFallbackState =
                AndroidAccountDeletionFallbackMarker.State.Present(expected)
            accountDeletionFallbackPresentAtStartup = true
            return AccountDeletionRecoveryMarkerPreparation.Ready(expected)
        }
        if (
            readback is AndroidAccountDeletionFallbackMarker.State.Present &&
            readback.record == expected
        ) {
            return AccountDeletionRecoveryMarkerPreparation.RetryablePresent(expected)
        }
        return if (readback is AndroidAccountDeletionFallbackMarker.State.Absent) {
            AccountDeletionRecoveryMarkerPreparation.Absent
        } else {
            AccountDeletionRecoveryMarkerPreparation.Blocked
        }
    }

    private fun startPreparedAccountDeletionAuthorityConfirmation(
        generation: Long,
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        markerRecord: AndroidAccountDeletionFallbackMarker.Record,
        workerAttempt: AccountDeletionWorkerAttempt,
    ) {
        try {
            accountDeletionCleanupExecutor.execute {
                var preparation = AccountDeletionAuthorityPreparation(
                    confirmed = false,
                    retryable = true,
                    failureReason = null,
                )
                val confirmed = runAccountDeletionWorkerDurableStage(workerAttempt) {
                    preparation = prepareAccountDeletionIntentAuthority()
                    preparation.confirmed
                }
                if (confirmed == null) {
                    completeAccountDeletionPreparation(generation)
                    return@execute
                }
                val terminalFailureReason = if (
                    !preparation.confirmed && !preparation.retryable
                ) {
                    preparation.failureReason
                        ?: "deletion_intent_prepared_recovery_rejected"
                } else {
                    null
                }
                if (
                    terminalFailureReason != null &&
                    runAccountDeletionWorkerTerminalStage(
                        attempt = workerAttempt,
                        errorCode = terminalFailureReason,
                    ) != true
                ) {
                    completeAccountDeletionPreparation(generation)
                    return@execute
                }
                val activityToken = reportCleanupActivityToken
                reportCleanupCallbackHandler.post {
                    if (
                        reportCleanupDestroyed ||
                        activityToken != reportCleanupActivityToken ||
                        !isCurrentAccountDeletionPreparation(generation) ||
                        (
                            terminalFailureReason == null &&
                                !accountDeletionWorkerStageAllowed(workerAttempt)
                            ) ||
                        (
                            terminalFailureReason != null &&
                                !accountDeletionStateMachine.activityLeaseIsCurrent(
                                    accountDeletionActivityLease,
                                )
                            )
                    ) return@post
                    completeAccountDeletionPreparation(generation)
                    when {
                        preparation.confirmed -> {
                            startAccountDeletionPreparation(
                                trustedGatewayOrigin = trustedGatewayOrigin,
                                session = session,
                                markerRecord = markerRecord,
                                workerAttempt = workerAttempt,
                            )
                        }
                        preparation.retryable -> {
                            retainAccountDeletionPreparationRecovery(
                                reason = "deletion_intent_authority_retry",
                                workerAttempt = workerAttempt,
                                announcement =
                                    "삭제 요청 신원은 보존됐지만 삭제 확인 저장을 완료하지 못했습니다. 다시 시도하세요.",
                            )
                        }
                        else -> {
                            accountDeletionTerminalCleanupCoordinator.invalidate()
                            integratedConsentSession.failClosed()
                            reportPrivacyConsentSession.blockForAccountDeletion()
                            accountDeletionRemoteResumeBlocked = true
                            updateNavigationStatus(
                                "accountDeletion=fail_closed:$terminalFailureReason",
                            )
                            updateAccountDeletionUi()
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            completeAccountDeletionPreparation(generation)
            retainAccountDeletionPreparationRecovery(
                reason = "cleanup_executor_rejected",
                workerAttempt = workerAttempt,
                announcement =
                    "삭제 확인은 보존됐지만 저장 작업을 시작하지 못했습니다. 다시 시도하세요.",
            )
        }
    }

    private fun prepareAccountDeletionIntentAuthority():
        AccountDeletionAuthorityPreparation {
        return when (
            runCatching {
                accountDeletionPreparedConfirmationRecovery
                    .recoverPreparedConfirmation()
            }.getOrDefault(PreparedConfirmationRecoveryResult.RETRY_REQUIRED)
        ) {
            PreparedConfirmationRecoveryResult.RECOVERED_CONFIRMED -> {
                runCatching { accountDeletionIntentFence.engage() }
                val snapshot = accountDeletionDualAuthority.startupState()
                accountDeletionAuthorityAtStartup = snapshot
                AccountDeletionAuthorityPreparation(
                    confirmed =
                        snapshot.fileState ==
                            AccountDeletionIntentAuthorityState.Confirmed,
                    retryable =
                        snapshot.fileState !is
                            AccountDeletionIntentAuthorityState.FailClosed,
                    failureReason =
                        (snapshot.fileState as?
                            AccountDeletionIntentAuthorityState.FailClosed)?.reason,
                )
            }
            PreparedConfirmationRecoveryResult.RECONFIRM_REQUIRED -> {
                val confirmation = accountDeletionDualAuthority.confirm()
                accountDeletionAuthorityAtStartup = confirmation.snapshot
                val fileState = confirmation.snapshot.fileState
                AccountDeletionAuthorityPreparation(
                    confirmed =
                        confirmation.decision ==
                            AccountDeletionConfirmationDecision.ACCEPTED,
                    retryable =
                        fileState !is AccountDeletionIntentAuthorityState.FailClosed,
                    failureReason =
                        (fileState as? AccountDeletionIntentAuthorityState.FailClosed)
                            ?.reason,
                )
            }
            PreparedConfirmationRecoveryResult.RETRY_REQUIRED ->
                AccountDeletionAuthorityPreparation(
                    confirmed = false,
                    retryable = true,
                    failureReason = null,
                )
            PreparedConfirmationRecoveryResult.REJECTED -> {
                val snapshot = accountDeletionDualAuthority.startupState()
                accountDeletionAuthorityAtStartup = snapshot
                val durableReason =
                    (snapshot.fileState as?
                        AccountDeletionIntentAuthorityState.FailClosed)?.reason
                AccountDeletionAuthorityPreparation(
                    confirmed = false,
                    retryable = false,
                    failureReason =
                        durableReason
                            ?: "deletion_intent_prepared_recovery_rejected",
                )
            }
        }
    }

    private fun startAccountDeletionPreparation(
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        markerRecord: AndroidAccountDeletionFallbackMarker.Record,
        workerAttempt: AccountDeletionWorkerAttempt,
    ) {
        val generation = synchronized(accountDeletionLock) {
            accountDeletionRequestGeneration += 1L
            accountDeletionRequestInFlight = true
            accountDeletionRequestGeneration
        }
        postAccountDeletionUiRefresh()
        try {
            accountDeletionCleanupExecutor.execute {
                if (!accountDeletionWorkerStageAllowed(workerAttempt)) {
                    completeAccountDeletionPreparation(generation)
                    return@execute
                }
                var preparation: AccountDeletionPendingJournalPreparation =
                    AccountDeletionPendingJournalPreparation.Retry
                val pendingPersisted =
                    runAccountDeletionWorkerDurableStage(workerAttempt) {
                        preparation = prepareAccountDeletionPendingJournal(
                            trustedGatewayOrigin = trustedGatewayOrigin,
                            session = session,
                            markerRecord = markerRecord,
                        )
                        preparation is AccountDeletionPendingJournalPreparation.Ready
                    }
                if (pendingPersisted == null) {
                    completeAccountDeletionPreparation(generation)
                    return@execute
                }
                if (!pendingPersisted) {
                    postAccountDeletionPreparationFailure(
                        workerAttempt,
                        generation,
                        markerRecord.identity.requestId,
                        if (
                            preparation is
                            AccountDeletionPendingJournalPreparation.Conflict
                        ) {
                            "pending_journal_conflict"
                        } else {
                            "pending_journal_storage"
                        },
                    )
                    return@execute
                }
                val pendingJournal =
                    (preparation as?
                        AccountDeletionPendingJournalPreparation.Ready)
                        ?.journal
                        ?: run {
                            postAccountDeletionPreparationFailure(
                                workerAttempt,
                                generation,
                                markerRecord.identity.requestId,
                                "pending_journal_storage",
                            )
                            return@execute
                        }
                if (!accountDeletionWorkerStageAllowed(workerAttempt)) {
                    completeAccountDeletionPreparation(generation)
                    return@execute
                }
                postAccountDeletionPendingJournalReady(
                    workerAttempt = workerAttempt,
                    generation = generation,
                    trustedGatewayOrigin = trustedGatewayOrigin,
                    session = session,
                    pendingJournal = pendingJournal,
                    markerRecord = markerRecord,
                )
            }
        } catch (_: RejectedExecutionException) {
            postAccountDeletionPreparationFailure(
                workerAttempt,
                generation,
                "unknown_account_deletion_request",
                "cleanup_executor_rejected",
            )
        }
    }

    private fun prepareAccountDeletionPendingJournal(
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        markerRecord: AndroidAccountDeletionFallbackMarker.Record,
    ): AccountDeletionPendingJournalPreparation {
        if (
            session.gatewayBaseUrl != trustedGatewayOrigin ||
            markerRecord.gatewayOrigin != trustedGatewayOrigin
        ) return AccountDeletionPendingJournalPreparation.Conflict
        return try {
            val rawJournal = sensitivePrefs.getString(
                PREF_ACCOUNT_DELETION_JOURNAL,
                null,
            )
            val failClosedMarker = sensitivePrefs.getBoolean(
                PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                false,
            )
            val actorHash = sensitivePrefs.getString(
                PREF_ACCOUNT_DELETION_ACTOR_HASH,
                null,
            )
            val terminalReason = sensitivePrefs.getString(
                PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                null,
            )
            if (rawJournal != null) {
                val current = accountDeletionJournalOrNull(rawJournal)
                    ?: return AccountDeletionPendingJournalPreparation.Conflict
                val expected = AccountDeletionJournal.pending(
                    gatewayOrigin = trustedGatewayOrigin,
                    installationId = markerRecord.installationId,
                    requestId = markerRecord.identity.requestId,
                    requestedAt = current.requestedAt,
                    clientRevision = markerRecord.clientRevision,
                )
                return if (
                    current == expected &&
                    rawJournal == accountDeletionJournalJson(current) &&
                    failClosedMarker &&
                    terminalReason == null &&
                    actorHash == markerRecord.identity.actorHash
                ) {
                    AccountDeletionPendingJournalPreparation.Ready(current)
                } else {
                    AccountDeletionPendingJournalPreparation.Conflict
                }
            }
            if (
                failClosedMarker ||
                actorHash != null ||
                terminalReason != null
            ) {
                return AccountDeletionPendingJournalPreparation.Conflict
            }
            val pending = AccountDeletionJournal.pending(
                gatewayOrigin = trustedGatewayOrigin,
                installationId = markerRecord.installationId,
                requestId = markerRecord.identity.requestId,
                requestedAt = java.time.Instant.now().toString(),
                clientRevision = markerRecord.clientRevision,
            )
            val serialized = accountDeletionJournalJson(pending)
            val committed = sensitivePrefs.edit()
                .putString(PREF_ACCOUNT_DELETION_JOURNAL, serialized)
                .putBoolean(PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER, true)
                .putString(
                    PREF_ACCOUNT_DELETION_ACTOR_HASH,
                    markerRecord.identity.actorHash,
                )
                .remove(PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON)
                .commit()
            val exactReadback =
                sensitivePrefs.getString(
                    PREF_ACCOUNT_DELETION_JOURNAL,
                    null,
                ) == serialized &&
                    sensitivePrefs.getBoolean(
                        PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                        false,
                    ) &&
                    sensitivePrefs.getString(
                        PREF_ACCOUNT_DELETION_ACTOR_HASH,
                        null,
                    ) == markerRecord.identity.actorHash &&
                    !sensitivePrefs.contains(
                        PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                    )
            if (committed && exactReadback) {
                AccountDeletionPendingJournalPreparation.Ready(pending)
            } else {
                AccountDeletionPendingJournalPreparation.Retry
            }
        } catch (_: RuntimeException) {
            AccountDeletionPendingJournalPreparation.Retry
        }
    }

    private fun postAccountDeletionPendingJournalReady(
        workerAttempt: AccountDeletionWorkerAttempt,
        generation: Long,
        trustedGatewayOrigin: String,
        session: GatewayFieldSession,
        pendingJournal: AccountDeletionJournal,
        markerRecord: AndroidAccountDeletionFallbackMarker.Record,
    ) {
        if (!accountDeletionWorkerStageAllowed(workerAttempt)) {
            completeAccountDeletionPreparation(generation)
            return
        }
        val activityToken = reportCleanupActivityToken
        reportCleanupCallbackHandler.post {
            if (
                reportCleanupDestroyed ||
                activityToken != reportCleanupActivityToken ||
                !isCurrentAccountDeletionPreparation(generation) ||
                !accountDeletionWorkerStageAllowed(workerAttempt)
            ) return@post
            if (!accountDeletionStateMachine.begin(pendingJournal, workerAttempt)) {
                completeAccountDeletionPreparation(generation)
                retainAccountDeletionPreparationRecovery(
                    reason = "pending_journal_conflict",
                    workerAttempt = workerAttempt,
                    announcement =
                        "삭제 요청 준비가 중단됐습니다. 상태를 확인한 뒤 다시 시도하세요.",
                )
                return@post
            }
            updateAccountDeletionUi()
            if (!completeAccountDeletionPreparation(generation)) return@post
            startAccountDeletionCall(
                callFactory = {
                    accountDeletionClient.requestDeletionCall(
                        gatewayBaseUrl = pendingJournal.gatewayOrigin,
                        trustedGatewayOrigin = trustedGatewayOrigin,
                        session = session,
                        installationId = pendingJournal.installationId,
                        accessSecret = markerRecord.accessSecret,
                        requestId = pendingJournal.requestId,
                        clientRevision = pendingJournal.clientRevision,
                    )
                },
                purpose = AccountDeletionCallPurpose.INITIAL,
            )
        }
    }

    private fun purgeAccountDeletionLocalDataOnCleanupThread(): Boolean {
        val legacyQueuePurged = purgeLegacyPendingReportQueueOnCleanupThread()
        val profileKeys = sensitivePrefs.keys().filter { key ->
            key == PREF_REPORT_COOLDOWNS_KEY ||
                key == PREF_REPORT_ATTEMPT_STATE_V2 ||
                key == PREF_REPORTER_USER_ID_KEY ||
                key.startsWith(PREF_PRIORITY_USER_PROFILE_PREFIX) ||
                key.startsWith(PREF_PRIORITY_USER_PROFILE_INVALID_PREFIX)
        }
        val editor = sensitivePrefs.edit()
        profileKeys.forEach(editor::remove)
        val preferencesPurged = editor.commit()
        val fieldSessionsPurged =
            if (::fieldSessionLog.isInitialized) {
                fieldSessionLog.blockForAccountDeletion()
                fieldSessionLog.purgeAll()
            } else {
                true
            }
        return legacyQueuePurged && preferencesPurged && fieldSessionsPurged
    }

    private fun isCurrentAccountDeletionPreparation(generation: Long): Boolean =
        synchronized(accountDeletionLock) {
            generation == accountDeletionRequestGeneration &&
                accountDeletionRequestInFlight &&
                accountDeletionCall == null
        }

    private fun completeAccountDeletionPreparation(generation: Long): Boolean =
        synchronized(accountDeletionLock) {
            if (
                generation != accountDeletionRequestGeneration ||
                accountDeletionCall != null
            ) {
                false
            } else {
                accountDeletionRequestInFlight = false
                true
            }
        }

    private fun postAccountDeletionPreparationFailure(
        workerAttempt: AccountDeletionWorkerAttempt,
        generation: Long,
        @Suppress("UNUSED_PARAMETER") requestId: String,
        reason: String,
    ) {
        if (!accountDeletionWorkerStageAllowed(workerAttempt)) {
            completeAccountDeletionPreparation(generation)
            return
        }
        val activityToken = reportCleanupActivityToken
        reportCleanupCallbackHandler.post {
            if (
                reportCleanupDestroyed ||
                activityToken != reportCleanupActivityToken ||
                !isCurrentAccountDeletionPreparation(generation) ||
                !accountDeletionWorkerStageAllowed(workerAttempt)
            ) return@post
            completeAccountDeletionPreparation(generation)
            retainAccountDeletionPreparationRecovery(
                reason = reason,
                workerAttempt = workerAttempt,
                announcement =
                    "삭제 요청 준비를 완료하지 못했습니다. 삭제 확인은 보존됐으니 다시 시도하세요.",
            )
        }
    }

    private fun refreshAccountDeletionStatus(
        workerAttempt: AccountDeletionWorkerAttempt?,
    ) {
        val journal = accountDeletionStateMachine.snapshotOrNull() ?: return
        val currentAttempt =
            workerAttempt ?: accountDeletionWorkerAttemptOrNull(journal) ?: return
        if (!accountDeletionWorkerStageAllowed(currentAttempt)) return
        try {
            accountDeletionCleanupExecutor.execute {
                if (!accountDeletionWorkerStageAllowed(currentAttempt)) return@execute
                dispatchAccountDeletionNetworkEntry(
                    journal = journal,
                    keepPrivacyFenceClosed = ::keepAccountDeletionPrivacyFenceClosed,
                    dispatch = {
                        refreshAccountDeletionStatusAllowed(journal, currentAttempt)
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            keepAccountDeletionPrivacyFenceClosed()
        }
    }

    private fun refreshAccountDeletionStatus() {
        refreshAccountDeletionStatus(workerAttempt = null)
    }

    private fun refreshAccountDeletionStatusAllowed(
        journal: AccountDeletionJournal,
        workerAttempt: AccountDeletionWorkerAttempt,
    ) {
        if (
            accountDeletionRequestInFlight ||
            accountDeletionRemoteResumeBlocked ||
            accountDeletionLocalPurgeInFlightRequestId == journal.requestId
        ) return
        val trustedGatewayOrigin = configuredGatewayOriginOrNull()
        if (
            trustedGatewayOrigin == null ||
            journal.gatewayOrigin != trustedGatewayOrigin
        ) {
            runAccountDeletionWorkerTerminalStage(
                workerAttempt,
                "account_deletion_gateway_origin_conflict",
            ) ?: return
            integratedConsentSession.failClosed()
            postAccountDeletionUiRefresh()
            return
        }
        var markerState: AndroidAccountDeletionFallbackMarker.State =
            AndroidAccountDeletionFallbackMarker.State.Unavailable
        runAccountDeletionWorkerDurableStage(workerAttempt) {
            markerState = accountDeletionFallbackMarker.read()
            true
        } ?: return
        val marker = markerState
            as? AndroidAccountDeletionFallbackMarker.State.Present
        if (
            marker == null ||
            marker.record.identity.requestId != journal.requestId ||
            marker.record.gatewayOrigin != journal.gatewayOrigin ||
            marker.record.installationId != journal.installationId
        ) {
            runAccountDeletionWorkerTerminalStage(
                workerAttempt,
                "account_deletion_fallback_marker_conflict",
            ) ?: return
            integratedConsentSession.failClosed()
            accountDeletionRemoteResumeBlocked = true
            postAccountDeletionUiRefresh()
            return
        }
        if (journal.serverRevision == 0L) {
            val recoveryBinding =
                accountDeletionRev0RecoveryBindingOrNull()
                    ?.takeIf { it.journal == journal }
            val recoverySession = recoveryBinding?.let {
                accountDeletionRecoveryGatewaySessionOrNull()
            }
            if (recoveryBinding != null && recoverySession != null) {
                startAccountDeletionCall(
                    callFactory = {
                        accountDeletionClient.recoverDeletionRequestAcceptOrReplayCall(
                            gatewayBaseUrl = journal.gatewayOrigin,
                            trustedGatewayOrigin = trustedGatewayOrigin,
                            session = recoverySession,
                            installationId = journal.installationId,
                            accessSecret = marker.record.accessSecret,
                            requestId = journal.requestId,
                            clientRevision = journal.clientRevision,
                        )
                    },
                    purpose = AccountDeletionCallPurpose.RECOVERY_ACCEPT_OR_REPLAY,
                )
            } else {
                startAccountDeletionCall(
                    callFactory = {
                        accountDeletionClient.replayDeletionRequestCall(
                            journal = journal,
                            trustedGatewayOrigin = trustedGatewayOrigin,
                            accessSecret = marker.record.accessSecret,
                        )
                    },
                    purpose = AccountDeletionCallPurpose.CAPABILITY_REPLAY,
                )
            }
        } else {
            startAccountDeletionCall(
                callFactory = {
                    accountDeletionClient.fetchDeletionStatusCall(
                        journal = journal,
                        trustedGatewayOrigin = trustedGatewayOrigin,
                        accessSecret = marker.record.accessSecret,
                    )
                },
                purpose = AccountDeletionCallPurpose.STATUS,
            )
        }
    }

    private fun startAccountDeletionCall(
        callFactory: () -> CancellableNetworkCall<AccountDeletionStatus>,
        purpose: AccountDeletionCallPurpose,
    ) {
        val journal = accountDeletionStateMachine.snapshotOrNull()
        dispatchAccountDeletionNetworkEntry(
            journal = journal,
            keepPrivacyFenceClosed = ::keepAccountDeletionPrivacyFenceClosed,
            dispatch = {
                startAccountDeletionCallAllowed(callFactory(), purpose)
            },
        )
    }

    private fun startAccountDeletionCallAllowed(
        call: CancellableNetworkCall<AccountDeletionStatus>,
        purpose: AccountDeletionCallPurpose,
    ) {
        val journal = accountDeletionStateMachine.snapshotOrNull()
        val workerAttempt = journal?.let(::accountDeletionWorkerAttemptOrNull)
        if (workerAttempt == null) {
            call.cancel()
            return
        }
        val networkLease = accountDeletionStateMachine.registerNetworkCall(
            workerAttempt,
            call::cancel,
        ) ?: run {
            call.cancel()
            keepAccountDeletionPrivacyFenceClosed()
            return
        }
        val generation = synchronized(accountDeletionLock) {
            accountDeletionCall?.cancel()
            accountDeletionRequestGeneration += 1L
            accountDeletionCall = call
            accountDeletionRequestInFlight = true
            accountDeletionRequestGeneration
        }
        postAccountDeletionUiRefresh()
        try {
            gatewaySessionExecutor.execute {
                if (!accountDeletionStateMachine.beginNetworkCall(networkLease)) {
                    accountDeletionStateMachine.releaseNetworkCall(networkLease)
                    call.cancel()
                    completeCurrentAccountDeletionCall(generation, call)
                    return@execute
                }
                val execution = executeAccountDeletionCall(call) {
                    val stateReleased =
                        accountDeletionStateMachine.releaseNetworkCall(networkLease)
                    val localReleased =
                        completeCurrentAccountDeletionCall(generation, call)
                    stateReleased && localReleased
                }
                when (execution) {
                    is AccountDeletionCallExecution.Succeeded -> {
                        val status = execution.value
                        scheduleAccountDeletionNetworkCompletion(
                            networkLease = networkLease,
                            generation = generation,
                            call = call,
                            completion = completion@{
                            val applyResult =
                                accountDeletionStateMachine.completeNetworkCall(
                                    networkLease,
                                    status,
                                    acknowledgesDeviceEvidence =
                                        purpose == AccountDeletionCallPurpose.EVIDENCE,
                                )
                            if (!completeCurrentAccountDeletionCall(generation, call)) {
                                return@completion
                            }
                            if (applyResult == null) {
                                keepAccountDeletionPrivacyFenceClosed()
                                return@completion
                            }
                            when (applyResult) {
                                AccountDeletionApplyResult.APPLIED,
                                AccountDeletionApplyResult.DUPLICATE,
                                -> {
                                    val snapshot =
                                        accountDeletionStateMachine.snapshotOrNull()
                                            ?: return@completion
                                    val journalPersisted =
                                        runAccountDeletionWorkerDurableStage(
                                            workerAttempt,
                                        ) {
                                            it == snapshot &&
                                                persistAccountDeletionJournal(snapshot)
                                        } ?: return@completion
                                    if (!journalPersisted) {
                                        runAccountDeletionWorkerTerminalStage(
                                            workerAttempt,
                                            "account_deletion_journal_storage",
                                        ) ?: return@completion
                                        integratedConsentSession.failClosed()
                                    } else {
                                        completeAccountDeletionRev0ReauthenticationAfterAcceptance(
                                            snapshot,
                                        )
                                        scheduleAccountDeletionProgressAfterStatus(snapshot)
                                    }
                                }
                                AccountDeletionApplyResult.STALE_IGNORED -> Unit
                                AccountDeletionApplyResult.CONFLICT_FAIL_CLOSED,
                                AccountDeletionApplyResult.IDENTITY_MISMATCH_FAIL_CLOSED,
                                -> {
                                    runAccountDeletionWorkerTerminalStage(
                                        workerAttempt,
                                        accountDeletionStateMachine.failureReasonOrNull(),
                                    ) ?: return@completion
                                    integratedConsentSession.failClosed()
                                }
                            }
                            postAccountDeletionUiRefresh()
                            },
                        )
                    }
                    AccountDeletionCallExecution.Cancelled -> Unit
                    is AccountDeletionCallExecution.Failed -> {
                        val error = execution.error
                        scheduleAccountDeletionNetworkCompletion(
                            networkLease = networkLease,
                            generation = generation,
                            call = call,
                            completion = completion@{
                            val stateReleased =
                                accountDeletionStateMachine.releaseNetworkCall(networkLease)
                            val localReleased =
                                completeCurrentAccountDeletionCall(generation, call)
                            if (!stateReleased || !localReleased) {
                                return@completion
                            }
                            if (!accountDeletionWorkerStageAllowed(workerAttempt)) {
                                return@completion
                            }
                            if (
                                purpose == AccountDeletionCallPurpose.CAPABILITY_REPLAY &&
                                error is AccountDeletionHttpException &&
                                error.statusCode == 404 &&
                                (
                                    activateAccountDeletionRev0Reauthentication(
                                        workerAttempt,
                                    ) ||
                                        bindLegacyAccountDeletionRev0RecoveryActor(
                                            workerAttempt,
                                        ) ||
                                        handleLegacyAccountDeletionRev0NotFound(
                                            workerAttempt,
                                        )
                                    )
                            ) {
                                postAccountDeletionUiRefresh()
                                if (accountDeletionRecoveryGatewaySessionOrNull() != null) {
                                    refreshAccountDeletionStatus(workerAttempt)
                                }
                                return@completion
                            }
                            if (
                                purpose ==
                                AccountDeletionCallPurpose.RECOVERY_ACCEPT_OR_REPLAY &&
                                error is AccountDeletionHttpException &&
                                (
                                    error is
                                    AccountDeletionReauthenticationRequiredException ||
                                        error.statusCode == 401 ||
                                        error.statusCode == 403
                                    ) &&
                                retainAccountDeletionRev0ReauthenticationAfterSessionFailure(
                                    workerAttempt,
                                )
                            ) {
                                clearAccountDeletionRev0RecoverySessionIfCurrent()
                                postAccountDeletionUiRefresh()
                                return@completion
                            }
                            if (
                                purpose == AccountDeletionCallPurpose.EVIDENCE &&
                                error is AccountDeletionHttpException &&
                                error.statusCode == 409 &&
                                handleAccountDeletionEvidenceRevisionConflict(
                                    error.errorBody,
                                    workerAttempt,
                                )
                            ) {
                                return@completion
                            }
                            val disposition = if (error is AccountDeletionHttpException) {
                                accountDeletionHttpFailureDisposition(
                                    statusCode = error.statusCode,
                                    body = error.errorBody,
                                )
                            } else {
                                AccountDeletionHttpFailureDisposition.Retry(
                                    "request_failed",
                                )
                            }
                            when (disposition) {
                                is AccountDeletionHttpFailureDisposition.TerminalConflict ->
                                    postAccountDeletionProgressFailure(
                                        "terminal_conflict:${disposition.code}",
                                        workerAttempt,
                                    )
                                is AccountDeletionHttpFailureDisposition.Retry ->
                                    markAccountDeletionRetry(
                                        disposition.code,
                                        workerAttempt,
                                    )
                            }
                            },
                        )
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            val stateReleased =
                accountDeletionStateMachine.releaseNetworkCall(networkLease)
            call.cancel()
            val localReleased = completeCurrentAccountDeletionCall(generation, call)
            if (stateReleased && localReleased) {
                markAccountDeletionRetry("executor_rejected", workerAttempt)
            } else {
                keepAccountDeletionPrivacyFenceClosed()
            }
        }
    }

    private fun scheduleAccountDeletionNetworkCompletion(
        networkLease: AccountDeletionNetworkCallLease,
        generation: Long,
        call: CancellableNetworkCall<AccountDeletionStatus>,
        completion: () -> Unit,
    ) {
        try {
            accountDeletionCleanupExecutor.execute(completion)
        } catch (_: RejectedExecutionException) {
            val stateReleased =
                accountDeletionStateMachine.releaseNetworkCall(networkLease)
            val localReleased = completeCurrentAccountDeletionCall(generation, call)
            if (stateReleased && localReleased) {
                keepAccountDeletionPrivacyFenceClosed()
            }
        }
    }

    private fun postAccountDeletionUiRefresh() {
        reportCleanupCallbackHandler.post {
            if (
                !privacyStartupInspectionDestroyed &&
                accountDeletionStateMachine.activityLeaseIsCurrent(
                    accountDeletionActivityLease,
                )
            ) updateAccountDeletionUi()
        }
    }

    private fun handleAccountDeletionEvidenceRevisionConflict(
        responseBody: String,
        workerAttempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        val before = accountDeletionStateMachine.snapshotOrNull() ?: return false
        val status = validatedAccountDeletionStatusOrNull(
            body = responseBody,
            expectedInstallationId = before.installationId,
            expectedRequestId = before.requestId,
        ) ?: return false
        when (accountDeletionStateMachine.apply(workerAttempt, status)) {
            AccountDeletionApplyResult.APPLIED,
            AccountDeletionApplyResult.DUPLICATE,
            AccountDeletionApplyResult.STALE_IGNORED,
            -> Unit
            AccountDeletionApplyResult.CONFLICT_FAIL_CLOSED,
            AccountDeletionApplyResult.IDENTITY_MISMATCH_FAIL_CLOSED,
            -> {
                runAccountDeletionWorkerTerminalStage(
                    workerAttempt,
                    accountDeletionStateMachine.failureReasonOrNull(),
                ) ?: return true
                integratedConsentSession.failClosed()
                postAccountDeletionUiRefresh()
                return true
            }
        }
        val current = accountDeletionStateMachine.snapshotOrNull() ?: return false
        val journalPersisted =
            runAccountDeletionWorkerDurableStage(workerAttempt) {
                it == current && persistAccountDeletionJournal(current)
            } ?: return true
        if (!journalPersisted) {
            postAccountDeletionProgressFailure(
                "device_evidence_rebase_storage",
                workerAttempt,
            )
            return true
        }
        if (current.deviceEvidenceAcknowledged) {
            scheduleAccountDeletionProgressAfterStatus(current)
            postAccountDeletionUiRefresh()
            return true
        }
        val priorExpected = current.deviceEvidenceExpectedStatusRevision
            ?: return false
        if (current.serverRevision <= priorExpected) return false
        scheduleAccountDeletionEvidenceRebase(current)
        postAccountDeletionUiRefresh()
        return true
    }

    private fun scheduleAccountDeletionEvidenceRebase(
        journal: AccountDeletionJournal,
    ) {
        val workerAttempt = accountDeletionWorkerAttemptOrNull(journal) ?: return
        try {
            accountDeletionCleanupExecutor.execute {
                if (!accountDeletionWorkerStageAllowed(workerAttempt)) return@execute
                var markerState: AndroidAccountDeletionFallbackMarker.State =
                    AndroidAccountDeletionFallbackMarker.State.Unavailable
                runAccountDeletionWorkerDurableStage(workerAttempt) {
                    markerState = accountDeletionFallbackMarker.read()
                    true
                } ?: return@execute
                val record =
                    (markerState
                        as? AndroidAccountDeletionFallbackMarker.State.Present)
                        ?.record
                if (
                    record == null ||
                    record.identity.requestId != journal.requestId ||
                    record.installationId != journal.installationId ||
                    record.phase !=
                    AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_PENDING ||
                    record.evidenceExpectedStatusRevision == null ||
                    journal.serverRevision <= record.evidenceExpectedStatusRevision ||
                    record.evidenceResult == null ||
                    record.evidenceCompletedAt == null
                ) {
                    postAccountDeletionProgressFailure(
                        "device_evidence_rebase_linkage",
                        workerAttempt,
                    )
                    return@execute
                }
                val evidenceWithoutHash = DeviceDeletionEvidence(
                    requestId = journal.requestId,
                    tombstoneId = journal.tombstoneId!!,
                    requestReceiptSha256 = journal.requestReceiptSha256!!,
                    installationId = journal.installationId,
                    evidenceId = newAccountDeletionEvidenceId(),
                    clientRevision = journal.clientRevision,
                    expectedStatusRevision = journal.serverRevision,
                    result = record.evidenceResult,
                    completedAt = record.evidenceCompletedAt,
                    evidenceSha256 = "0".repeat(64),
                )
                val evidence = evidenceWithoutHash.copy(
                    evidenceSha256 =
                        deviceDeletionEvidenceSha256(evidenceWithoutHash),
                )
                val next = record.copy(
                    statusRevision = journal.serverRevision,
                    evidenceId = evidence.evidenceId,
                    evidenceSha256 = evidence.evidenceSha256,
                    evidenceExpectedStatusRevision =
                        evidence.expectedStatusRevision,
                )
                val markerUpdated =
                    runAccountDeletionWorkerDurableStage(workerAttempt) {
                        accountDeletionFallbackMarker.update(next)
                    } ?: return@execute
                if (
                    !accountDeletionWorkerStageAllowed(workerAttempt) ||
                    !markerUpdated ||
                    !accountDeletionStateMachine.rebaseDeviceEvidence(
                        workerAttempt,
                        evidenceId = evidence.evidenceId,
                        evidenceSha256 = evidence.evidenceSha256,
                        expectedStatusRevision = evidence.expectedStatusRevision,
                        result = evidence.result,
                        completedAt = evidence.completedAt,
                    )
                ) {
                    postAccountDeletionProgressFailure(
                        "device_evidence_rebase_storage",
                        workerAttempt,
                    )
                    return@execute
                }
                val current = accountDeletionStateMachine.snapshotOrNull()
                    ?: return@execute
                val journalPersisted =
                    runAccountDeletionWorkerDurableStage(workerAttempt) {
                        it == current && persistAccountDeletionJournal(current)
                    } ?: return@execute
                if (!journalPersisted) {
                    postAccountDeletionProgressFailure(
                        "device_evidence_rebase_storage",
                        workerAttempt,
                    )
                    return@execute
                }
                submitPendingDeviceDeletionEvidenceAllowed(next, current)
            }
        } catch (_: RejectedExecutionException) {
            postAccountDeletionProgressFailure(
                "cleanup_executor_rejected",
                workerAttempt,
            )
        }
    }

    private fun scheduleAccountDeletionActorBindingCleanup(
        journal: AccountDeletionJournal,
    ) {
        val workerAttempt = accountDeletionWorkerAttemptOrNull(journal) ?: return
        val cleanupAttempt =
            accountDeletionTerminalCleanupCoordinator.begin(journal)
        if (cleanupAttempt == null) {
            postAccountDeletionProgressFailure(
                "terminal_marker_cleanup",
                workerAttempt,
            )
            return
        }
        postAccountDeletionUiRefresh()
        try {
            accountDeletionCleanupExecutor.execute {
                if (!accountDeletionWorkerStageAllowed(workerAttempt)) return@execute
                var actorHashReadSucceeded = false
                var actorHash: String? = null
                var actorBindingPresent = true
                runAccountDeletionWorkerDurableStage(workerAttempt) {
                    val read = runCatching {
                        sensitivePrefs.getString(
                            PREF_ACCOUNT_DELETION_ACTOR_HASH,
                            null,
                        )
                    }
                    actorHashReadSucceeded = read.isSuccess
                    actorHash = read.getOrNull()
                    actorBindingPresent = runCatching {
                        sensitivePrefs.contains(
                            PREF_ACCOUNT_DELETION_ACTOR_HASH,
                        )
                    }.getOrDefault(true)
                    true
                } ?: return@execute
                val markerCleanup = if (!actorHashReadSucceeded) {
                    AccountDeletionTerminalMarkerCleanupResult.FAILED
                } else {
                    var markerState: AndroidAccountDeletionFallbackMarker.State =
                        AndroidAccountDeletionFallbackMarker.State.Unavailable
                    runAccountDeletionWorkerDurableStage(workerAttempt) {
                        markerState = accountDeletionFallbackMarker.read()
                        true
                    } ?: return@execute
                    when (val state = markerState) {
                        is AndroidAccountDeletionFallbackMarker.State.Absent ->
                            AccountDeletionTerminalMarkerCleanupResult.ALREADY_ABSENT
                        is AndroidAccountDeletionFallbackMarker.State.Present -> {
                            val identity = actorHash?.let {
                                AndroidAccountDeletionFallbackMarker.Identity(
                                    requestId = journal.requestId,
                                    actorHash = it,
                                )
                            }
                            val removed = if (
                                identity != null && state.identity == identity
                            ) {
                                runAccountDeletionTerminalCleanupStage(workerAttempt) {
                                    accountDeletionFallbackMarker.clear(identity) &&
                                        accountDeletionFallbackMarker.read() is
                                        AndroidAccountDeletionFallbackMarker.State.Absent
                                } ?: return@execute
                            } else {
                                false
                            }
                            if (removed) {
                                AccountDeletionTerminalMarkerCleanupResult.REMOVED
                            } else {
                                AccountDeletionTerminalMarkerCleanupResult.FAILED
                            }
                        }
                        is AndroidAccountDeletionFallbackMarker.State.Corrupt,
                        is AndroidAccountDeletionFallbackMarker.State.Unavailable,
                        -> AccountDeletionTerminalMarkerCleanupResult.FAILED
                    }
                }
                val actorBindingAbsent =
                    markerCleanup != AccountDeletionTerminalMarkerCleanupResult.FAILED &&
                        if (actorHash == null) {
                            !actorBindingPresent
                        } else {
                            runAccountDeletionTerminalCleanupStage(workerAttempt) {
                                sensitivePrefs.edit()
                                    .remove(PREF_ACCOUNT_DELETION_ACTOR_HASH)
                                    .commit() &&
                                    runCatching {
                                        !sensitivePrefs.contains(
                                            PREF_ACCOUNT_DELETION_ACTOR_HASH,
                                        )
                                    }.getOrDefault(false)
                            } ?: return@execute
                        }
                if (!accountDeletionWorkerStageAllowed(workerAttempt)) return@execute
                val activityToken = reportCleanupActivityToken
                reportCleanupCallbackHandler.post {
                    if (
                        reportCleanupDestroyed ||
                        activityToken != reportCleanupActivityToken ||
                        !accountDeletionWorkerStageAllowed(workerAttempt)
                    ) return@post
                    val current = accountDeletionStateMachine.snapshotOrNull()
                        ?: return@post
                    when (
                        accountDeletionTerminalCleanupCoordinator.complete(
                            attempt = cleanupAttempt,
                            journal = current,
                            markerCleanup = markerCleanup,
                            actorBindingAbsent = actorBindingAbsent,
                        )
                    ) {
                        null -> return@post
                        false -> {
                            postAccountDeletionProgressFailure(
                                "terminal_marker_cleanup",
                                workerAttempt,
                            )
                            return@post
                        }
                        true -> updateAccountDeletionUi()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            postAccountDeletionProgressFailure(
                "cleanup_executor_rejected",
                workerAttempt,
            )
        }
    }

    private fun retainAccountDeletionPreparationRecovery(
        reason: String,
        workerAttempt: AccountDeletionWorkerAttempt,
        announcement: String,
    ): Boolean {
        if (
            !accountDeletionStateMachine.retainDurableConfirmationRecovery(
                workerAttempt,
            )
        ) {
            keepAccountDeletionPrivacyFenceClosed()
            return false
        }
        integratedConsentSession.blockForAccountDeletion()
        reportPrivacyConsentSession.blockForAccountDeletion()
        updateNavigationStatus("accountDeletion=recovery_required:$reason")
        updateAccountDeletionUi()
        speakInteraction(announcement)
        return true
    }

    private fun completeCurrentAccountDeletionCall(
        generation: Long,
        call: CancellableNetworkCall<AccountDeletionStatus>,
    ): Boolean = synchronized(accountDeletionLock) {
        if (
            generation != accountDeletionRequestGeneration ||
            accountDeletionCall !== call
        ) {
            false
        } else {
            accountDeletionCall = null
            accountDeletionRequestInFlight = false
            true
        }
    }

    private fun markAccountDeletionRetry(
        errorCode: String,
        workerAttempt: AccountDeletionWorkerAttempt,
    ) {
        try {
            accountDeletionCleanupExecutor.execute {
                if (!accountDeletionStateMachine.markRetry(workerAttempt, errorCode)) {
                    keepAccountDeletionPrivacyFenceClosed()
                    return@execute
                }
                val current = accountDeletionStateMachine.snapshotOrNull() ?: return@execute
                val persisted = runAccountDeletionWorkerDurableStage(workerAttempt) {
                    it == current && persistAccountDeletionJournal(current)
                } ?: return@execute
                if (!persisted) {
                    postAccountDeletionProgressFailure(
                        "retry_journal_storage",
                        workerAttempt,
                    )
                    return@execute
                }
                postAccountDeletionUiRefresh()
            }
        } catch (_: RejectedExecutionException) {
            keepAccountDeletionPrivacyFenceClosed()
        }
    }

    private fun keepAccountDeletionPrivacyFenceClosed() {
        runOnUiThread {
            if (
                privacyStartupInspectionDestroyed ||
                !accountDeletionStateMachine.activityLeaseIsCurrent(
                    accountDeletionActivityLease,
                ) ||
                !accountDeletionStateMachine.processingBlocked()
            ) return@runOnUiThread
            applyAccountDeletionRuntimeFence()
            updateAccountDeletionUi()
        }
    }

    private fun accountDeletionWorkerStageAllowed(
        attempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        return dispatchAccountDeletionWorkerStage(
            stateMachine = accountDeletionStateMachine,
            attempt = attempt,
            keepPrivacyFenceClosed = ::keepAccountDeletionPrivacyFenceClosed,
            dispatch = {},
        )
    }

    private fun runAccountDeletionWorkerDurableStage(
        attempt: AccountDeletionWorkerAttempt,
        action: (AccountDeletionJournal?) -> Boolean,
    ): Boolean? {
        return when (
            accountDeletionStateMachine.runWorkerMonotonicDurableIoIfCurrent(
                attempt = attempt,
                action = action,
            )
        ) {
            AccountDeletionWorkerStageResult.STALE -> {
                keepAccountDeletionPrivacyFenceClosed()
                null
            }
            AccountDeletionWorkerStageResult.SUCCEEDED -> true
            AccountDeletionWorkerStageResult.FAILED -> false
        }
    }

    private fun runAccountDeletionWorkerTerminalStage(
        attempt: AccountDeletionWorkerAttempt,
        errorCode: String? = null,
    ): Boolean? {
        return when (
            accountDeletionStateMachine.runWorkerMonotonicTerminalDurableIoIfCurrent(
                attempt = attempt,
                errorCode = errorCode,
                action = { terminalJournal ->
                    persistAccountDeletionTerminalState(
                        journal = terminalJournal,
                        fallbackReason = errorCode,
                    )
                },
            )
        ) {
            AccountDeletionWorkerStageResult.STALE -> {
                keepAccountDeletionPrivacyFenceClosed()
                null
            }
            AccountDeletionWorkerStageResult.SUCCEEDED -> true
            AccountDeletionWorkerStageResult.FAILED -> {
                handleAccountDeletionTerminalPersistenceFailure(errorCode)
                null
            }
        }
    }

    private fun runAccountDeletionTerminalCleanupStage(
        attempt: AccountDeletionWorkerAttempt,
        action: () -> Boolean,
    ): Boolean? {
        return when (
            accountDeletionStateMachine.runWorkerTerminalCleanupIoIfCurrent(
                attempt = attempt,
                action = { action() },
            )
        ) {
            AccountDeletionWorkerStageResult.STALE -> {
                keepAccountDeletionPrivacyFenceClosed()
                null
            }
            AccountDeletionWorkerStageResult.SUCCEEDED -> true
            AccountDeletionWorkerStageResult.FAILED -> false
        }
    }

    private fun persistAccountDeletionTerminalState(
        journal: AccountDeletionJournal?,
        fallbackReason: String?,
    ): Boolean {
        val terminalJournal = journal?.takeIf {
            it.phase == AccountDeletionPhase.FAIL_CLOSED
        }
        val reason = terminalJournal?.lastErrorCode
            ?: fallbackReason
            ?: journal?.lastErrorCode
            ?: "account_deletion_fail_closed"
        val authorityPersisted =
            accountDeletionDualAuthority.failClosed(reason).durableSuccess
        val serializedJournal = terminalJournal?.let(::accountDeletionJournalJson)
        val preferencePersisted = runCatching {
            val editor = sensitivePrefs.edit()
                .putBoolean(PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER, true)
                .putString(PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON, reason)
            if (serializedJournal != null) {
                editor.putString(PREF_ACCOUNT_DELETION_JOURNAL, serializedJournal)
            }
            editor.commit() &&
                sensitivePrefs.getBoolean(
                    PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                    false,
                ) &&
                sensitivePrefs.getString(
                    PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                    null,
                ) == reason &&
                (
                    serializedJournal == null ||
                        sensitivePrefs.getString(
                            PREF_ACCOUNT_DELETION_JOURNAL,
                            null,
                        ) == serializedJournal
                )
        }.getOrDefault(false)
        return authorityPersisted && preferencePersisted
    }

    private fun persistAccountDeletionTerminalStateOrHandle(
        journal: AccountDeletionJournal?,
        fallbackReason: String,
    ): Boolean {
        if (persistAccountDeletionTerminalState(journal, fallbackReason)) {
            return true
        }
        handleAccountDeletionTerminalPersistenceFailure(fallbackReason)
        return false
    }

    private fun handleAccountDeletionTerminalPersistenceFailure(
        fallbackReason: String?,
    ) {
        val reason = accountDeletionStateMachine.failureReasonOrNull()
            ?: fallbackReason
            ?: "account_deletion_terminal_storage"
        accountDeletionDualAuthority.failClosed(reason)
        accountDeletionTerminalCleanupCoordinator.invalidate()
        integratedConsentSession.failClosed()
        reportPrivacyConsentSession.blockForAccountDeletion()
        accountDeletionRemoteResumeBlocked = true
        if (
            accountDeletionStateMachine.activityLeaseIsCurrent(
                accountDeletionActivityLease,
            )
        ) {
            runOnUiThread {
                if (
                    !accountDeletionStateMachine.activityLeaseIsCurrent(
                        accountDeletionActivityLease,
                    )
                ) return@runOnUiThread
                updateNavigationStatus("accountDeletion=fail_closed:$reason")
                updateAccountDeletionUi()
            }
        }
    }

    private fun accountDeletionWorkerAttemptOrNull(
        journal: AccountDeletionJournal,
    ): AccountDeletionWorkerAttempt? =
        accountDeletionStateMachine.beginWorkerAttempt(
            journal,
            accountDeletionActivityLease,
        ).also { attempt ->
            if (attempt == null) keepAccountDeletionPrivacyFenceClosed()
        }

    private fun applyAccountDeletionFenceAndPurgeLocal() {
        applyAccountDeletionRuntimeFence()
        resumeAccountDeletionFromMarker()
    }

    private fun scheduleAccountDeletionProgressAfterStatus(
        journal: AccountDeletionJournal,
    ) {
        val workerAttempt = accountDeletionWorkerAttemptOrNull(journal) ?: return
        try {
            accountDeletionCleanupExecutor.execute {
                if (!accountDeletionWorkerStageAllowed(workerAttempt)) return@execute
                var state: AndroidAccountDeletionFallbackMarker.State =
                    AndroidAccountDeletionFallbackMarker.State.Unavailable
                runAccountDeletionWorkerDurableStage(workerAttempt) {
                    state = accountDeletionFallbackMarker.read()
                    true
                } ?: return@execute
                val present = state as? AndroidAccountDeletionFallbackMarker.State.Present
                val current = present?.record
                if (
                    current == null ||
                    current.identity.requestId != journal.requestId ||
                    current.installationId != journal.installationId ||
                    journal.acceptedAt == null ||
                    journal.accountGeneration == null ||
                    journal.tombstoneId == null ||
                    journal.requestReceiptSha256 == null
                ) {
                    postAccountDeletionProgressFailure(
                        "fallback_status_linkage",
                        workerAttempt,
                    )
                    return@execute
                }
                val nextPhase = when {
                    journal.phase == AccountDeletionPhase.COMPLETED &&
                        journal.deviceEvidenceAcknowledged ->
                        AndroidAccountDeletionFallbackMarker.Phase.TERMINAL_RECEIPT
                    journal.deviceEvidenceAcknowledged ->
                        AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_ACKED
                    current.phase < AndroidAccountDeletionFallbackMarker.Phase.ACCEPTED ->
                        AndroidAccountDeletionFallbackMarker.Phase.ACCEPTED
                    else -> current.phase
                }
                val next = current.copy(
                    recoveryActorId = null,
                    phase = nextPhase,
                    acceptedAt = journal.acceptedAt,
                    accountGeneration = journal.accountGeneration,
                    tombstoneId = journal.tombstoneId,
                    requestReceiptSha256 = journal.requestReceiptSha256,
                    statusRevision = journal.serverRevision,
                    completionReceiptSha256 = journal.receiptSha256,
                )
                val markerUpdated =
                    runAccountDeletionWorkerDurableStage(workerAttempt) {
                        accountDeletionFallbackMarker.update(next)
                    } ?: return@execute
                if (!markerUpdated) {
                    postAccountDeletionProgressFailure(
                        "fallback_status_storage",
                        workerAttempt,
                    )
                    return@execute
                }
                accountDeletionStartupFallbackState =
                    AndroidAccountDeletionFallbackMarker.State.Present(next)
                if (!accountDeletionWorkerStageAllowed(workerAttempt)) return@execute
                val activityToken = reportCleanupActivityToken
                reportCleanupCallbackHandler.post {
                    if (
                        reportCleanupDestroyed ||
                        activityToken != reportCleanupActivityToken ||
                        !accountDeletionWorkerStageAllowed(workerAttempt)
                    ) return@post
                    continueAccountDeletionFromMarker(next, workerAttempt)
                }
            }
        } catch (_: RejectedExecutionException) {
            postAccountDeletionProgressFailure(
                "cleanup_executor_rejected",
                workerAttempt,
            )
        }
    }

    private fun resumeAccountDeletionFromMarker() {
        if (accountDeletionRequestInFlight || accountDeletionLocalPurgeInFlightRequestId != null) {
            return
        }
        val restoredJournal = accountDeletionStateMachine.snapshotOrNull()
        if (restoredJournal?.phase == AccountDeletionPhase.FAIL_CLOSED) {
            applyAccountDeletionRuntimeFence()
            updateAccountDeletionUi()
            return
        }
        val workerAttempt = restoredJournal
            ?.let(::accountDeletionWorkerAttemptOrNull)
            ?: return
        try {
            accountDeletionCleanupExecutor.execute {
                if (!accountDeletionWorkerStageAllowed(workerAttempt)) return@execute
                var markerState: AndroidAccountDeletionFallbackMarker.State =
                    AndroidAccountDeletionFallbackMarker.State.Unavailable
                runAccountDeletionWorkerDurableStage(workerAttempt) {
                    markerState = accountDeletionFallbackMarker.read()
                    true
                } ?: return@execute
                when (val state = markerState) {
                    is AndroidAccountDeletionFallbackMarker.State.Present ->
                        continueAccountDeletionFromMarker(state.record, workerAttempt)
                    is AndroidAccountDeletionFallbackMarker.State.Absent -> {
                        if (isConfirmedAccountDeletionJournal(restoredJournal)) {
                            scheduleAccountDeletionActorBindingCleanup(restoredJournal)
                        } else {
                            postAccountDeletionProgressFailure(
                                "fallback_marker_missing",
                                workerAttempt,
                            )
                        }
                    }
                    is AndroidAccountDeletionFallbackMarker.State.Corrupt,
                    is AndroidAccountDeletionFallbackMarker.State.Unavailable,
                    -> postAccountDeletionProgressFailure(
                        "fallback_marker_unavailable",
                        workerAttempt,
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            keepAccountDeletionPrivacyFenceClosed()
        }
    }

    private fun continueAccountDeletionFromMarker(
        record: AndroidAccountDeletionFallbackMarker.Record,
        workerAttempt: AccountDeletionWorkerAttempt? = null,
    ) {
        val journal = accountDeletionStateMachine.snapshotOrNull() ?: return
        val currentAttempt =
            workerAttempt ?: accountDeletionWorkerAttemptOrNull(journal) ?: return
        if (!accountDeletionWorkerStageAllowed(currentAttempt)) return
        val identityMatches =
            record.identity.requestId == journal.requestId &&
            record.installationId == journal.installationId &&
            record.gatewayOrigin == journal.gatewayOrigin

        val dispatched = dispatchAndroidAccountDeletionResume(
            journal = journal,
            markerPhase = record.phase,
            identityMatches = identityMatches,
            rejectIdentityConflict = {
                postAccountDeletionProgressFailure(
                    "fallback_identity_conflict",
                    currentAttempt,
                )
            },
            replayPreparedRequest = {
                refreshAccountDeletionStatus(currentAttempt)
            },
            purgeAcceptedLocalData = {
                scheduleAcceptedAccountDeletionLocalPurge(record, journal)
            },
            rejectMissingLocalEvidence = {
                postAccountDeletionProgressFailure(
                    "local_evidence_missing",
                    currentAttempt,
                )
            },
            submitPendingEvidence = {
                submitPendingDeviceDeletionEvidence(record, journal)
            },
            fetchAcknowledgedStatus = {
                refreshAccountDeletionStatus(currentAttempt)
            },
            cleanupTerminalBinding = {
                scheduleAccountDeletionActorBindingCleanup(journal)
            },
        )
        if (!dispatched) {
            applyAccountDeletionRuntimeFence()
            postAccountDeletionUiRefresh()
        }
    }

    private fun scheduleAcceptedAccountDeletionLocalPurge(
        record: AndroidAccountDeletionFallbackMarker.Record,
        journal: AccountDeletionJournal,
    ) {
        if (accountDeletionLocalPurgeInFlightRequestId != null) return
        val workerAttempt = accountDeletionWorkerAttemptOrNull(journal) ?: return
        accountDeletionLocalPurgeInFlightRequestId = journal.requestId
        try {
            accountDeletionCleanupExecutor.execute {
                if (!accountDeletionWorkerStageAllowed(workerAttempt)) return@execute
                val purgeSucceeded =
                    runAccountDeletionWorkerDurableStage(workerAttempt) {
                        purgeAccountDeletionLocalDataOnCleanupThread()
                    } ?: return@execute
                val completedAt = java.time.Instant.now()
                    .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                    .toString()
                val evidenceWithoutHash = DeviceDeletionEvidence(
                    requestId = journal.requestId,
                    tombstoneId = journal.tombstoneId!!,
                    requestReceiptSha256 = journal.requestReceiptSha256!!,
                    installationId = journal.installationId,
                    evidenceId = newAccountDeletionEvidenceId(),
                    clientRevision = journal.clientRevision,
                    expectedStatusRevision = journal.serverRevision,
                    result = if (purgeSucceeded) "DELETED" else "FAILED",
                    completedAt = completedAt,
                    evidenceSha256 = "0".repeat(64),
                )
                val evidenceSha256 = deviceDeletionEvidenceSha256(evidenceWithoutHash)
                val evidence = evidenceWithoutHash.copy(evidenceSha256 = evidenceSha256)
                val next = record.copy(
                    phase = AndroidAccountDeletionFallbackMarker.Phase.EVIDENCE_PENDING,
                    evidenceId = evidence.evidenceId,
                    evidenceSha256 = evidence.evidenceSha256,
                    evidenceExpectedStatusRevision = evidence.expectedStatusRevision,
                    evidenceResult = evidence.result,
                    evidenceCompletedAt = evidence.completedAt,
                )
                val markerUpdated =
                    runAccountDeletionWorkerDurableStage(workerAttempt) {
                        accountDeletionFallbackMarker.update(next)
                    } ?: return@execute
                accountDeletionLocalPurgeInFlightRequestId = null
                if (
                    !accountDeletionWorkerStageAllowed(workerAttempt) ||
                    !markerUpdated ||
                    !accountDeletionStateMachine.recordDeviceEvidence(
                        workerAttempt,
                        evidenceId = evidence.evidenceId,
                        evidenceSha256 = evidence.evidenceSha256,
                        expectedStatusRevision = evidence.expectedStatusRevision,
                        result = evidence.result,
                        completedAt = evidence.completedAt,
                    )
                ) {
                    postAccountDeletionProgressFailure(
                        "device_evidence_storage",
                        workerAttempt,
                    )
                    return@execute
                }
                val current = accountDeletionStateMachine.snapshotOrNull()
                    ?: return@execute
                val journalPersisted =
                    runAccountDeletionWorkerDurableStage(workerAttempt) {
                        it == current && persistAccountDeletionJournal(current)
                    } ?: return@execute
                if (!journalPersisted) {
                    postAccountDeletionProgressFailure(
                        "device_evidence_storage",
                        workerAttempt,
                    )
                    return@execute
                }
                submitPendingDeviceDeletionEvidenceAllowed(next, current)
            }
        } catch (_: RejectedExecutionException) {
            accountDeletionLocalPurgeInFlightRequestId = null
            postAccountDeletionProgressFailure(
                "cleanup_executor_rejected",
                workerAttempt,
            )
        }
    }

    private fun newAccountDeletionEvidenceId(): String {
        val random = ByteArray(24)
        SecureRandom().nextBytes(random)
        return "device_evidence_" +
            Base64.getUrlEncoder().withoutPadding().encodeToString(random)
    }

    private fun submitPendingDeviceDeletionEvidence(
        record: AndroidAccountDeletionFallbackMarker.Record,
        journal: AccountDeletionJournal,
    ) {
        try {
            accountDeletionCleanupExecutor.execute {
                dispatchAccountDeletionNetworkEntry(
                    journal = accountDeletionStateMachine.snapshotOrNull(),
                    keepPrivacyFenceClosed = ::keepAccountDeletionPrivacyFenceClosed,
                    dispatch = {
                        submitPendingDeviceDeletionEvidenceAllowed(record, journal)
                    },
                )
            }
        } catch (_: RejectedExecutionException) {
            keepAccountDeletionPrivacyFenceClosed()
        }
    }

    private fun submitPendingDeviceDeletionEvidenceAllowed(
        record: AndroidAccountDeletionFallbackMarker.Record,
        journal: AccountDeletionJournal,
    ) {
        val currentAtEntry = accountDeletionStateMachine.snapshotOrNull() ?: return
        val workerAttempt =
            accountDeletionWorkerAttemptOrNull(currentAtEntry) ?: return
        val trustedGatewayOrigin = configuredGatewayOriginOrNull()
        val markerEvidenceMatchesJournal =
            journal.deviceEvidenceId == record.evidenceId &&
                journal.deviceEvidenceSha256 == record.evidenceSha256 &&
                journal.deviceEvidenceExpectedStatusRevision ==
                record.evidenceExpectedStatusRevision &&
                journal.deviceEvidenceResult == record.evidenceResult &&
                journal.deviceEvidenceCompletedAt == record.evidenceCompletedAt
        val linkedJournal = when {
            markerEvidenceMatchesJournal -> journal
            journal.deviceEvidenceId == null &&
                record.evidenceId != null &&
                record.evidenceSha256 != null &&
                record.evidenceExpectedStatusRevision != null &&
                record.evidenceResult != null &&
                record.evidenceCompletedAt != null &&
                accountDeletionStateMachine.recordDeviceEvidence(
                    workerAttempt,
                    evidenceId = record.evidenceId,
                    evidenceSha256 = record.evidenceSha256,
                    expectedStatusRevision = record.evidenceExpectedStatusRevision,
                    result = record.evidenceResult,
                    completedAt = record.evidenceCompletedAt,
                ) -> {
                val current = accountDeletionStateMachine.snapshotOrNull()
                    ?: return
                val persisted =
                    runAccountDeletionWorkerDurableStage(workerAttempt) {
                        it == current && persistAccountDeletionJournal(current)
                    } ?: return
                current.takeIf { persisted }
            }
            !journal.deviceEvidenceAcknowledged &&
                journal.deviceEvidenceExpectedStatusRevision != null &&
                record.evidenceId != null &&
                record.evidenceSha256 != null &&
                record.evidenceExpectedStatusRevision != null &&
                record.evidenceExpectedStatusRevision >
                journal.deviceEvidenceExpectedStatusRevision &&
                record.evidenceResult != null &&
                record.evidenceCompletedAt != null &&
                accountDeletionStateMachine.rebaseDeviceEvidence(
                    workerAttempt,
                    evidenceId = record.evidenceId,
                    evidenceSha256 = record.evidenceSha256,
                    expectedStatusRevision = record.evidenceExpectedStatusRevision,
                    result = record.evidenceResult,
                    completedAt = record.evidenceCompletedAt,
                ) -> {
                val current = accountDeletionStateMachine.snapshotOrNull()
                    ?: return
                val persisted =
                    runAccountDeletionWorkerDurableStage(workerAttempt) {
                        it == current && persistAccountDeletionJournal(current)
                    } ?: return
                current.takeIf { persisted }
            }
            else -> null
        }
        if (trustedGatewayOrigin == null || linkedJournal == null) {
            postAccountDeletionProgressFailure(
                "device_evidence_linkage",
                workerAttempt,
            )
            return
        }
        val evidence = runCatching {
            DeviceDeletionEvidence(
                requestId = linkedJournal.requestId,
                tombstoneId = linkedJournal.tombstoneId!!,
                requestReceiptSha256 = linkedJournal.requestReceiptSha256!!,
                installationId = linkedJournal.installationId,
                evidenceId = record.evidenceId!!,
                clientRevision = linkedJournal.clientRevision,
                expectedStatusRevision = record.evidenceExpectedStatusRevision!!,
                result = record.evidenceResult!!,
                completedAt = record.evidenceCompletedAt!!,
                evidenceSha256 = record.evidenceSha256!!,
            )
        }.getOrNull()
        if (evidence == null) {
            postAccountDeletionProgressFailure(
                "device_evidence_linkage",
                workerAttempt,
            )
            return
        }
        startAccountDeletionCall(
            callFactory = {
                accountDeletionClient.submitDeviceDeletionEvidenceCall(
                    journal = linkedJournal,
                    trustedGatewayOrigin = trustedGatewayOrigin,
                    accessSecret = record.accessSecret,
                    evidence = evidence,
                )
            },
            purpose = AccountDeletionCallPurpose.EVIDENCE,
        )
    }

    private fun postAccountDeletionProgressFailure(
        reason: String,
        workerAttempt: AccountDeletionWorkerAttempt,
    ) {
        if (!accountDeletionWorkerStageAllowed(workerAttempt)) return
        try {
            accountDeletionCleanupExecutor.execute {
                if (!accountDeletionWorkerStageAllowed(workerAttempt)) return@execute
                runAccountDeletionWorkerTerminalStage(
                    attempt = workerAttempt,
                    errorCode = reason,
                ) ?: return@execute
                accountDeletionTerminalCleanupCoordinator.invalidate()
                integratedConsentSession.failClosed()
                accountDeletionRemoteResumeBlocked = true
                reportCleanupCallbackHandler.post {
                    if (
                        privacyStartupInspectionDestroyed ||
                        !accountDeletionStateMachine.activityLeaseIsCurrent(
                            accountDeletionActivityLease,
                        )
                    ) return@post
                    updateNavigationStatus("accountDeletion=fail_closed:$reason")
                    updateAccountDeletionUi()
                }
            }
        } catch (_: RejectedExecutionException) {
            keepAccountDeletionPrivacyFenceClosed()
        }
    }

    private fun postAccountDeletionStartupFailure(reason: String) {
        val expectedLease = accountDeletionActivityLease
        val expectedJournal = accountDeletionStateMachine.snapshotOrNull()
        try {
            accountDeletionCleanupExecutor.execute {
                if (
                    !accountDeletionStateMachine.activityLeaseIsCurrent(
                        expectedLease,
                    ) ||
                    accountDeletionStateMachine.snapshotOrNull() !== expectedJournal ||
                    !accountDeletionStateMachine.failClosed(expectedLease, reason)
                ) return@execute
                val terminalJournal = accountDeletionStateMachine.snapshotOrNull()
                if (
                    accountDeletionStateMachine.snapshotOrNull() !== terminalJournal ||
                    accountDeletionStateMachine.failureReasonOrNull() != reason
                ) return@execute
                if (
                    !persistAccountDeletionTerminalState(
                        terminalJournal,
                        reason,
                    )
                ) {
                    handleAccountDeletionTerminalPersistenceFailure(reason)
                    return@execute
                }
                accountDeletionTerminalCleanupCoordinator.invalidate()
                integratedConsentSession.failClosed()
                accountDeletionRemoteResumeBlocked = true
                postAccountDeletionUiRefresh()
            }
        } catch (_: RejectedExecutionException) {
            keepAccountDeletionPrivacyFenceClosed()
        }
    }

    private fun scheduleLegacyPendingReportQueuePurge(
        onComplete: (Boolean) -> Unit = {},
    ): Boolean {
        if (PERSISTENT_REPORT_QUEUE_ENABLED) {
            val token = reportCleanupActivityToken
            val generation = reportCleanupGeneration
            reportCleanupCallbackHandler.post {
                if (
                    !reportCleanupDestroyed &&
                    token == reportCleanupActivityToken &&
                    generation == reportCleanupGeneration
                ) {
                    onComplete(false)
                }
            }
            return false
        }
        val executionGeneration = synchronized(legacyPendingReportQueuePurgeLock) {
            if (reportCleanupDestroyed) return false
            if (legacyPendingReportQueuePurgeSucceeded) {
                val token = reportCleanupActivityToken
                val generation = reportCleanupGeneration
                reportCleanupCallbackHandler.post {
                    if (
                        !reportCleanupDestroyed &&
                        token == reportCleanupActivityToken &&
                        generation == reportCleanupGeneration
                    ) {
                        onComplete(true)
                    }
                }
                return true
            }
            if (legacyPendingReportQueuePurgeInFlight) {
                legacyPendingReportQueuePurgeWaiters +=
                    LegacyPendingReportQueuePurgeWaiter(
                        activityToken = reportCleanupActivityToken,
                        generation = reportCleanupGeneration,
                        callback = onComplete,
                    )
                return false
            }
            legacyPendingReportQueuePurgeInFlight = true
            reportCleanupGeneration += 1L
            legacyPendingReportQueuePurgeWaiters +=
                LegacyPendingReportQueuePurgeWaiter(
                    activityToken = reportCleanupActivityToken,
                    generation = reportCleanupGeneration,
                    callback = onComplete,
                )
            reportCleanupGeneration
        }
        try {
            reportCleanupExecutor.execute {
                val succeeded = purgeLegacyPendingReportQueueOnCleanupThread()
                completeLegacyPendingReportQueuePurge(
                    generation = executionGeneration,
                    succeeded = succeeded,
                )
            }
        } catch (_: RejectedExecutionException) {
            completeLegacyPendingReportQueuePurge(
                generation = executionGeneration,
                succeeded = false,
            )
        }
        return false
    }

    private fun purgeLegacyPendingReportQueueOnCleanupThread(): Boolean {
        for (attempt in 0 until LEGACY_REPORT_CLEANUP_MAX_ATTEMPTS) {
            if (attempt > 0) {
                try {
                    Thread.sleep(
                        LEGACY_REPORT_CLEANUP_INITIAL_BACKOFF_MS *
                            (1L shl (attempt - 1)),
                    )
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
            val succeeded =
                try {
                    AndroidPendingReportStore.purgeAllWithoutLoading(
                        applicationContext,
                    )
                } catch (_: RuntimeException) {
                    false
                }
            if (succeeded) return true
        }
        return false
    }

    private fun completeLegacyPendingReportQueuePurge(
        generation: Long,
        succeeded: Boolean,
    ) {
        val waiters = synchronized(legacyPendingReportQueuePurgeLock) {
            if (
                reportCleanupDestroyed ||
                generation != reportCleanupGeneration
            ) {
                return
            }
            legacyPendingReportQueuePurgeSucceeded = succeeded
            legacyPendingReportQueuePurgeInFlight = false
            legacyPendingReportQueuePurgeWaiters.toList().also {
                legacyPendingReportQueuePurgeWaiters.clear()
            }
        }
        reportCleanupCallbackHandler.post {
            waiters.forEach { waiter ->
                if (
                    !reportCleanupDestroyed &&
                    waiter.activityToken == reportCleanupActivityToken &&
                    waiter.generation == reportCleanupGeneration
                ) {
                    waiter.callback(succeeded)
                }
            }
        }
    }

    private fun readAccountDeletionFallbackMarkerAtStartup() {
        val state =
            try {
                accountDeletionFallbackMarker.read()
            } catch (_: RuntimeException) {
                AndroidAccountDeletionFallbackMarker.State.Unavailable
            }
        accountDeletionStartupFallbackState = state
        accountDeletionFallbackPresentAtStartup =
            state !is AndroidAccountDeletionFallbackMarker.State.Absent
    }

    private fun preparedAccountDeletionRecoveryRecordOrNull():
        AndroidAccountDeletionFallbackMarker.Record? {
        val record = preparedAccountDeletionRecoveryCandidateOrNull() ?: return null
        val actorId = record.recoveryActorId ?: return null
        return record.takeIf {
            sha256Hex(actorId.toByteArray(Charsets.UTF_8)) ==
                record.identity.actorHash
        }
    }

    private fun preparedAccountDeletionRecoveryCandidateOrNull():
        AndroidAccountDeletionFallbackMarker.Record? {
        val record =
            (accountDeletionStartupFallbackState
                as? AndroidAccountDeletionFallbackMarker.State.Present)
                ?.record
                ?: return null
        return record.takeIf {
            it.phase == AndroidAccountDeletionFallbackMarker.Phase.PREPARED &&
                it.gatewayOrigin == configuredGatewayOriginOrNull()
        }
    }

    private fun reconcileAccountDeletionFallbackMarkerAtStartup() {
        val state = accountDeletionStartupFallbackState
        if (!accountDeletionFallbackPresentAtStartup) return
        applyAccountDeletionRuntimeFence()
        when (state) {
            is AndroidAccountDeletionFallbackMarker.State.Absent -> Unit
            is AndroidAccountDeletionFallbackMarker.State.Present -> {
                val rawJournal = sensitivePrefs.getString(
                    PREF_ACCOUNT_DELETION_JOURNAL,
                    null,
                )
                val journalRequestId =
                    rawJournal?.let { raw ->
                        runCatching {
                            JSONObject(raw).optString("request_id")
                        }.getOrNull()
                    }
                val persistedActorHash =
                    sensitivePrefs.getString(
                        PREF_ACCOUNT_DELETION_ACTOR_HASH,
                        null,
                    )
                val exactTuple =
                    journalRequestId == state.identity.requestId &&
                        persistedActorHash == state.identity.actorHash
                val durablePreJournalRecovery =
                    state.record == preparedAccountDeletionRecoveryCandidateOrNull() &&
                        accountDeletionAuthorityAtStartup.fileState !is
                        AccountDeletionIntentAuthorityState.FailClosed &&
                        rawJournal == null &&
                        persistedActorHash == null
                accountDeletionRemoteResumeBlocked =
                    !exactTuple && !durablePreJournalRecovery
            }
            is AndroidAccountDeletionFallbackMarker.State.Corrupt,
            is AndroidAccountDeletionFallbackMarker.State.Unavailable,
            -> {
                accountDeletionRemoteResumeBlocked = true
            }
        }
    }

    private fun startAccountDeletionFallbackRecoveryAfterRestore() {
        val restoredJournal = accountDeletionStateMachine.snapshotOrNull()
        if (restoredJournal?.phase == AccountDeletionPhase.FAIL_CLOSED) {
            applyAccountDeletionRuntimeFence()
            updateAccountDeletionUi()
            return
        }
        if (restoredJournal != null && restoredJournal.serverRevision > 0L) {
            completeAccountDeletionRev0ReauthenticationAfterAcceptance(restoredJournal)
        }
        when (val state = accountDeletionStartupFallbackState) {
            is AndroidAccountDeletionFallbackMarker.State.Absent -> Unit
            is AndroidAccountDeletionFallbackMarker.State.Present -> {
                if (
                    accountDeletionStateMachine
                        .durableConfirmationRecoveryRequired() &&
                    state.record.phase ==
                    AndroidAccountDeletionFallbackMarker.Phase.PREPARED &&
                    !accountDeletionRemoteResumeBlocked
                ) {
                    applyAccountDeletionRuntimeFence()
                    updateAccountDeletionUi()
                    return
                }
                val persistedActorHash =
                    sensitivePrefs.getString(
                        PREF_ACCOUNT_DELETION_ACTOR_HASH,
                        null,
                    )
                val exactTuple =
                    restoredJournal?.requestId == state.identity.requestId &&
                        persistedActorHash == state.identity.actorHash &&
                        !accountDeletionRemoteResumeBlocked
                if (exactTuple) {
                    continueAccountDeletionFromMarker(state.record)
                } else {
                    val workerAttempt = restoredJournal
                        ?.let(::accountDeletionWorkerAttemptOrNull)
                    if (workerAttempt != null) {
                        postAccountDeletionProgressFailure(
                            "fallback_identity_conflict",
                            workerAttempt,
                        )
                    } else {
                        postAccountDeletionStartupFailure(
                            "fallback_identity_conflict",
                        )
                    }
                }
            }
            is AndroidAccountDeletionFallbackMarker.State.Corrupt,
            is AndroidAccountDeletionFallbackMarker.State.Unavailable,
            -> {
                accountDeletionRemoteResumeBlocked = true
                applyAccountDeletionRuntimeFence()
                updateNavigationStatus(
                    "accountDeletion=recovery_blocked:fallback_marker_storage",
                )
                updateAccountDeletionUi()
            }
        }
    }

    private fun applyAccountDeletionRuntimeFence() {
        if (::fieldSessionLog.isInitialized) {
            fieldSessionLog.blockNewProcessingForAccountDeletion()
        }
        integratedConsentSession.blockForAccountDeletion()
        reportPrivacyConsentSession.blockForAccountDeletion()
        synchronized(reportUploadSafetyLock) {
            reportUploadSafetyGeneration += 1L
            automaticReportUploadSafetyGeneration += 1L
        }
        permissionSessionPolicy.applyIntegratedConsentSelections(
            IntegratedConsentSelections(),
        )
        if (::metadataLogUploader.isInitialized) metadataLogUploader.setEnabled(false)
        invalidateFrameStateForPause()
        reportAttemptStore = AndroidReportAttemptStore()
        synchronized(reportAttemptStateLock) {
            reportAttemptStateActorHash = null
            reportAttemptStateStorageBlocked = false
            persistedReportAttemptStates.clear()
            reportAttemptStorageBlockedActors.clear()
        }
        if (::surfaceView.isInitialized && isWalkSessionRuntimeActive()) {
            enterWalkSessionSafetyStopAndCancelOutputs(
                "account_deletion_requested",
                persistInterruptionMarker = false,
            )
        }
    }

    private fun persistCurrentAccountDeletionJournal(): Boolean {
        val journal = accountDeletionStateMachine.snapshotOrNull() ?: return false
        return persistAccountDeletionJournal(journal)
    }

    private fun persistAccountDeletionJournal(
        journal: AccountDeletionJournal,
    ): Boolean =
        sensitivePrefs.edit()
            .putString(
                PREF_ACCOUNT_DELETION_JOURNAL,
                accountDeletionJournalJson(journal),
            )
            .commit()

    private fun accountDeletionJournalJson(
        journal: AccountDeletionJournal,
    ): String {
        val items = JSONArray()
        DeletionInventoryItem.entries.forEach { item ->
            val status = journal.items.getValue(item)
            items.put(
                JSONObject()
                    .put("key", item.wireValue)
                    .put("status", status.state.wireValue)
                    .put("item_revision", status.itemRevision)
                    .put("due_at", status.dueAt)
                    .put("updated_at", status.updatedAt)
                    .put("evidence_sha256", status.evidenceSha256 ?: JSONObject.NULL)
                    .put("disposition_basis", status.dispositionBasis ?: JSONObject.NULL)
                    .put("retry_after", status.nextRetryAt ?: JSONObject.NULL)
                    .put("restriction_reason", status.reasonCode ?: JSONObject.NULL)
                    .put(
                        "legal_hold_review_at",
                        status.legalHoldReviewAt ?: JSONObject.NULL,
                    )
                    .put("legal_hold_contact", status.contactUrl ?: JSONObject.NULL)
                    .put("terminal_at", status.terminalAt ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("schema_version", ACCOUNT_DELETION_JOURNAL_SCHEMA_VERSION)
            .put("gateway_origin", journal.gatewayOrigin)
            .put("installation_id", journal.installationId)
            .put("request_id", journal.requestId)
            .put("requested_at", journal.requestedAt)
            .put("phase", journal.phase.name)
            .put("client_revision", journal.clientRevision)
            .put("server_revision", journal.serverRevision)
            .put("accepted_at", journal.acceptedAt ?: JSONObject.NULL)
            .put("account_generation", journal.accountGeneration ?: JSONObject.NULL)
            .put("tombstone_id", journal.tombstoneId ?: JSONObject.NULL)
            .put(
                "request_receipt_sha256",
                journal.requestReceiptSha256 ?: JSONObject.NULL,
            )
            .put("items", items)
            .put(
                "completion_receipt_sha256",
                journal.receiptSha256 ?: JSONObject.NULL,
            )
            .put("last_error_code", journal.lastErrorCode ?: JSONObject.NULL)
            .put("device_evidence_id", journal.deviceEvidenceId ?: JSONObject.NULL)
            .put(
                "device_evidence_sha256",
                journal.deviceEvidenceSha256 ?: JSONObject.NULL,
            )
            .put(
                "device_evidence_expected_status_revision",
                journal.deviceEvidenceExpectedStatusRevision ?: JSONObject.NULL,
            )
            .put(
                "device_evidence_result",
                journal.deviceEvidenceResult ?: JSONObject.NULL,
            )
            .put(
                "device_evidence_completed_at",
                journal.deviceEvidenceCompletedAt ?: JSONObject.NULL,
            )
            .put(
                "device_evidence_acknowledged",
                journal.deviceEvidenceAcknowledged,
            )
            .toString()
    }

    private fun accountDeletionJournalOrNull(
        raw: String,
    ): AccountDeletionJournal? = runCatching {
        val root = JSONObject(raw)
        if (
            root.jsonKeySet() != setOf(
                "schema_version",
                "gateway_origin",
                "installation_id",
                "request_id",
                "requested_at",
                "phase",
                "client_revision",
                "server_revision",
                "accepted_at",
                "account_generation",
                "tombstone_id",
                "request_receipt_sha256",
                "items",
                "completion_receipt_sha256",
                "last_error_code",
                "device_evidence_id",
                "device_evidence_sha256",
                "device_evidence_expected_status_revision",
                "device_evidence_result",
                "device_evidence_completed_at",
                "device_evidence_acknowledged",
            ) ||
            root.getString("schema_version") != ACCOUNT_DELETION_JOURNAL_SCHEMA_VERSION
        ) return null
        val array = root.getJSONArray("items")
        if (array.length() != DeletionInventoryItem.entries.size) return null
        val items = mutableMapOf<DeletionInventoryItem, DeletionItemStatus>()
        for (index in 0 until array.length()) {
            val value = array.getJSONObject(index)
            if (
                value.jsonKeySet() != setOf(
                    "key",
                    "status",
                    "item_revision",
                    "due_at",
                    "updated_at",
                    "evidence_sha256",
                    "disposition_basis",
                    "retry_after",
                    "restriction_reason",
                    "legal_hold_review_at",
                    "legal_hold_contact",
                    "terminal_at",
                )
            ) return null
            val item = DeletionInventoryItem.entries[index]
            if (value.getString("key") != item.wireValue) return null
            val state =
                DeletionItemState.fromWireValue(value.getString("status"))
                    ?: return null
            items[item] = DeletionItemStatus(
                item = item,
                state = state,
                itemRevision = value.strictJsonLong("item_revision"),
                dueAt = value.getString("due_at"),
                updatedAt = value.getString("updated_at"),
                evidenceSha256 = value.nullableJsonString("evidence_sha256"),
                dispositionBasis = value.nullableJsonString("disposition_basis"),
                nextRetryAt = value.nullableJsonString("retry_after"),
                reasonCode = value.nullableJsonString("restriction_reason"),
                legalHoldReviewAt = value.nullableJsonString("legal_hold_review_at"),
                contactUrl = value.nullableJsonString("legal_hold_contact"),
                terminalAt = value.nullableJsonString("terminal_at"),
            )
        }
        AccountDeletionJournal(
            gatewayOrigin = root.getString("gateway_origin"),
            installationId = root.getString("installation_id"),
            requestId = root.getString("request_id"),
            requestedAt = root.getString("requested_at"),
            phase = AccountDeletionPhase.valueOf(root.getString("phase")),
            clientRevision = root.strictJsonLong("client_revision"),
            serverRevision = root.strictJsonLong("server_revision"),
            items = items,
            acceptedAt = root.nullableJsonString("accepted_at"),
            accountGeneration = root.nullableJsonLong("account_generation"),
            tombstoneId = root.nullableJsonString("tombstone_id"),
            requestReceiptSha256 = root.nullableJsonString("request_receipt_sha256"),
            receiptSha256 = root.nullableJsonString("completion_receipt_sha256"),
            lastErrorCode = root.nullableJsonString("last_error_code"),
            deviceEvidenceId = root.nullableJsonString("device_evidence_id"),
            deviceEvidenceSha256 = root.nullableJsonString("device_evidence_sha256"),
            deviceEvidenceExpectedStatusRevision =
                root.nullableJsonLong("device_evidence_expected_status_revision"),
            deviceEvidenceResult = root.nullableJsonString("device_evidence_result"),
            deviceEvidenceCompletedAt =
                root.nullableJsonString("device_evidence_completed_at"),
            deviceEvidenceAcknowledged =
                root.strictJsonBoolean("device_evidence_acknowledged"),
        )
    }.getOrNull()

    private fun resetAfterConfirmedAccountDeletion() {
        val journal = accountDeletionStateMachine.snapshotOrNull() ?: return
        if (!accountDeletionTerminalCleanupComplete) {
            updateAccountDeletionUi()
            return
        }
        if (!isConfirmedAccountDeletionJournal(journal)) {
            val reason = "account_deletion_reset_not_confirmed"
            if (
                !accountDeletionStateMachine.failClosed(
                    accountDeletionActivityLease,
                    reason,
                )
            ) return
            val terminalJournal = accountDeletionStateMachine.snapshotOrNull()
            updateAccountDeletionUi()
            scheduleAccountDeletionTerminalPersistence(
                reason = reason,
                expectedJournal = terminalJournal,
            )
            return
        }
        val workerAttempt = accountDeletionWorkerAttemptOrNull(journal) ?: return
        gatewayFieldSession?.invalidate()
        val resetReservation =
            accountDeletionStateMachine.reserveWorkerResetIfCurrent(workerAttempt)
                ?: run {
                    keepAccountDeletionPrivacyFenceClosed()
                    return
                }
        updateAccountDeletionUi()
        try {
            accountDeletionCleanupExecutor.execute {
                val resetResult = try {
                    accountDeletionResetCoordinator.beginAndReconcile()
                } catch (_: Exception) {
                    accountDeletionStateMachine.rollbackWorkerReset(resetReservation)
                    val reason = accountDeletionStateMachine.failureReasonOrNull()
                        ?: "account_deletion_reset_failed"
                    persistAccountDeletionTerminalStateOrHandle(
                        accountDeletionStateMachine.snapshotOrNull(),
                        reason,
                    )
                    postAccountDeletionResetFailure()
                    return@execute
                }
                val resetCompleted = resetResult in setOf(
                    AccountDeletionResetResult.COMPLETED,
                    AccountDeletionResetResult.COMPLETED_ELSEWHERE,
                )
                if (
                    resetCompleted &&
                    !accountDeletionStateMachine.commitWorkerReset(resetReservation)
                ) {
                    val reason = accountDeletionStateMachine.failureReasonOrNull()
                        ?: "account_deletion_reset_commit_conflict"
                    persistAccountDeletionTerminalStateOrHandle(
                        accountDeletionStateMachine.snapshotOrNull(),
                        reason,
                    )
                    postAccountDeletionResetFailure()
                    return@execute
                }
                if (!resetCompleted) {
                    accountDeletionStateMachine.rollbackWorkerReset(resetReservation)
                    val reason = accountDeletionStateMachine.failureReasonOrNull()
                        ?: "account_deletion_reset_failed"
                    persistAccountDeletionTerminalStateOrHandle(
                        accountDeletionStateMachine.snapshotOrNull(),
                        reason,
                    )
                    postAccountDeletionResetFailure()
                    return@execute
                }
                reportPrivacyConsentSession.resetForNewEnrollment()
                reportCleanupCallbackHandler.post {
                    if (
                        privacyStartupInspectionDestroyed ||
                        !accountDeletionStateMachine.activityLeaseIsCurrent(
                            accountDeletionActivityLease,
                        )
                    ) return@post
                    when (resetResult) {
                        AccountDeletionResetResult.COMPLETED ->
                            resetAccountDeletionRuntimeForNewEnrollment()
                        AccountDeletionResetResult.COMPLETED_ELSEWHERE -> finish()
                        AccountDeletionResetResult.BLOCKED,
                        AccountDeletionResetResult.NO_PENDING,
                        -> Unit
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            accountDeletionStateMachine.rollbackWorkerReset(resetReservation)
            keepAccountDeletionPrivacyFenceClosed()
            updateAccountDeletionUi()
        }
    }

    private fun scheduleAccountDeletionTerminalPersistence(
        reason: String,
        expectedJournal: AccountDeletionJournal?,
    ) {
        try {
            accountDeletionCleanupExecutor.execute {
                if (
                    accountDeletionStateMachine.snapshotOrNull() !== expectedJournal ||
                    accountDeletionStateMachine.failureReasonOrNull() != reason
                ) return@execute
                persistAccountDeletionTerminalStateOrHandle(
                    expectedJournal,
                    reason,
                )
                reportCleanupCallbackHandler.post {
                    if (
                        !privacyStartupInspectionDestroyed &&
                        accountDeletionStateMachine.activityLeaseIsCurrent(
                            accountDeletionActivityLease,
                        )
                    ) updateAccountDeletionUi()
                }
            }
        } catch (_: RejectedExecutionException) {
            keepAccountDeletionPrivacyFenceClosed()
        }
    }

    private fun postAccountDeletionResetFailure() {
        reportCleanupCallbackHandler.post {
            if (
                privacyStartupInspectionDestroyed ||
                !accountDeletionStateMachine.activityLeaseIsCurrent(
                    accountDeletionActivityLease,
                )
            ) return@post
            keepAccountDeletionPrivacyFenceClosed()
            updateAccountDeletionUi()
        }
    }

    private fun resetAccountDeletionRuntimeForNewEnrollment() {
        accountDeletionTerminalCleanupCoordinator.invalidate()
        pendingIntegratedConsentMutation = null
        integratedConsentDraft = IntegratedConsentSelections()
        integratedConsentClientRevision = 0L
        integratedConsentSession.resetForNewEnrollment()
        reporterUserId = null
        priorityUserOnboardingActorId = null
        priorityUserOnboardingPolicy = PriorityUserOnboardingPolicy()
        permissionSessionPolicy.explicitLogout()
        firstRunOnboardingSnapshot = FirstRunOnboardingPolicy.initial(
            epoch = SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L),
        )
        updateIntegratedConsentUi()
        updateAccountDeletionUi()
        updateFirstRunOnboardingUi()
        speakInteraction("계정 삭제 완료를 확인했습니다. 새 계정은 새 동의로 등록합니다.")
    }

    private fun createAccountDeletionResetCoordinator(
        noBackupRoot: File,
    ): AccountDeletionResetCoordinator =
        AccountDeletionResetCoordinator(
            journal = FileAccountDeletionResetJournal(
                File(noBackupRoot, "account_deletion_reset"),
            ),
            storeLegacyIntent = {
                stepLengthPrefs.edit()
                    .putBoolean(PREF_ACCOUNT_DELETION_RESET_REQUIRED, true)
                    .commit() &&
                    legacyAccountDeletionResetIntentState() == LegacyResetIntentState.EXACT
            },
            resetGateway = {
                if (!::gatewaySessionStore.isInitialized) {
                    false
                } else {
                    val generation = GatewaySessionProcessCoordinator.snapshot().generation
                    GatewaySessionProcessCoordinator.clear(generation) != null &&
                        gatewaySessionStore.resetInstallationAfterConfirmedAccountDeletion()
                }
            },
            resetSensitivePreferences = {
                ::sensitivePrefs.isInitialized && sensitivePrefs.destroyAndClear()
            },
            resetPlainPreferences = ::clearAccountDeletionPlainResetArtifacts,
            resetFieldStorage = {
                ::fieldSessionLog.isInitialized &&
                    fieldSessionLog.purgeAll() && fieldSessionLog.resetForNewEnrollment()
            },
            clearDeletionIntentFence = ::clearAccountDeletionIntentFence,
        )

    private fun clearAccountDeletionPlainResetArtifacts(): Boolean {
        val keys = setOf(
            PREF_RAW_SOURCE_FIELD_LOG_BLOCKED,
            PREF_ACCOUNT_DELETION_RESET_REQUIRED,
            PREF_REPORTER_USER_ID_KEY,
            PREF_ACCOUNT_DELETION_COMPLETION_RECEIPT,
        )
        val committed = stepLengthPrefs.edit().also { editor ->
            keys.forEach(editor::remove)
        }.commit()
        return committed && runCatching {
            val remaining = stepLengthPrefs.all.keys
            keys.none(remaining::contains)
        }.getOrDefault(false)
    }

    private fun clearAccountDeletionIntentFence(): Boolean =
        accountDeletionDualAuthority.clear().cleared

    private fun reconcileAccountDeletionResetAtStartup(): AccountDeletionResetResult {
        val journalPresent = accountDeletionResetCoordinator.hasPendingOrCorruptJournal()
        val legacyIntent = legacyAccountDeletionResetIntentState()
        if (!journalPresent && legacyIntent == LegacyResetIntentState.ABSENT) {
            return accountDeletionResetCoordinator.reconcilePending()
        }
        if (!journalPresent) {
            return if (legacyIntent == LegacyResetIntentState.EXACT) {
                accountDeletionResetCoordinator.beginAndReconcile()
            } else {
                AccountDeletionResetResult.BLOCKED
            }
        }
        val resumed = accountDeletionResetCoordinator.reconcilePending()
        if (
            resumed == AccountDeletionResetResult.COMPLETED ||
            resumed == AccountDeletionResetResult.COMPLETED_ELSEWHERE
        ) return resumed
        val confirmedJournal = sensitivePrefs
            .getString(PREF_ACCOUNT_DELETION_JOURNAL, null)
            ?.let(::accountDeletionJournalOrNull)
            ?.takeIf(::isConfirmedAccountDeletionJournal)
        return if (
            legacyIntent == LegacyResetIntentState.EXACT || confirmedJournal != null
        ) {
            accountDeletionResetCoordinator.beginAndReconcile()
        } else {
            AccountDeletionResetResult.BLOCKED
        }
    }

    private fun blockForAccountDeletionResetFailure(): Boolean {
        if (!gatewayActivityCallbackAllowed(accountDeletionActivityLease)) return false
        if (
            !accountDeletionStateMachine.failClosed(
                accountDeletionActivityLease,
                "account_deletion_reset_failed",
            )
        ) return false
        sensitivePrefs.failClosed()
        accountDeletionDualAuthority.failClosed("account_deletion_reset_failed")
        integratedConsentSession.blockForAccountDeletion()
        reportPrivacyConsentSession.blockForAccountDeletion()
        fieldSessionLog.blockForAccountDeletion()
        stepLengthPrefs.edit()
            .putBoolean(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED, true)
            .commit()
        return true
    }

    private fun legacyAccountDeletionResetIntentState(): LegacyResetIntentState {
        val present = runCatching {
            stepLengthPrefs.contains(PREF_ACCOUNT_DELETION_RESET_REQUIRED)
        }.getOrElse { return LegacyResetIntentState.CORRUPT }
        if (!present) return LegacyResetIntentState.ABSENT
        val value = runCatching {
            stepLengthPrefs.all[PREF_ACCOUNT_DELETION_RESET_REQUIRED]
        }.getOrElse { return LegacyResetIntentState.CORRUPT }
        return if (value is Boolean && value) {
            LegacyResetIntentState.EXACT
        } else {
            LegacyResetIntentState.CORRUPT
        }
    }

    private fun isConfirmedAccountDeletionJournal(journal: AccountDeletionJournal): Boolean =
        isConfirmedTerminalAccountDeletion(journal)

    private enum class LegacyResetIntentState {
        ABSENT,
        EXACT,
        CORRUPT,
    }

    private fun JSONObject.jsonKeySet(): Set<String> {
        val result = mutableSetOf<String>()
        val iterator = keys()
        while (iterator.hasNext()) result += iterator.next()
        return result
    }

    private fun JSONObject.nullableJsonString(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun JSONObject.nullableJsonLong(name: String): Long? =
        if (isNull(name)) null else strictJsonLong(name)

    private fun JSONObject.strictJsonLong(name: String): Long =
        when (val value = get(name)) {
            is Byte -> value.toLong()
            is Short -> value.toLong()
            is Int -> value.toLong()
            is Long -> value
            else -> throw IllegalArgumentException("$name must be an integer")
        }

    private fun JSONObject.strictJsonBoolean(name: String): Boolean =
        get(name) as? Boolean
            ?: throw IllegalArgumentException("$name must be a boolean")

    private fun configuredGatewayOriginOrNull(): String? {
        val configured = stepLengthPrefs.getString(
            PREF_GATEWAY_ORIGIN_KEY,
            BuildConfig.WALKSAFE_GATEWAY_ORIGIN,
        )
        return if (BuildConfig.DEBUG) {
            GatewayEndpointPolicy.debugOriginOrNull(configured)
        } else {
            GatewayEndpointPolicy.approvedReleaseOriginOrNull(
                raw = configured,
                approvedOrigin = BuildConfig.WALKSAFE_GATEWAY_ORIGIN,
            )
        }
    }

    private fun onGatewayProcessSessionChanged(
        snapshot: GatewaySessionProcessSnapshot,
    ) {
        if (
            privacyStartupInspectionDestroyed ||
            !accountDeletionStateMachine.activityLeaseIsCurrent(
                accountDeletionActivityLease,
            )
        ) return
        GatewayCapacityProcessState.fenceSessionGeneration(snapshot.generation)
        val deletionJournal = accountDeletionStateMachine.snapshotOrNull()
        if (
            snapshot.session != null &&
            deletionJournal != null &&
            !isConfirmedAccountDeletionJournal(deletionJournal) &&
            accountDeletionLocalPurgeInFlightRequestId == null &&
            !accountDeletionRemoteResumeBlocked &&
            !accountDeletionRequestInFlight
        ) {
            dispatchAccountDeletionNetworkEntry(
                journal = deletionJournal,
                keepPrivacyFenceClosed = ::keepAccountDeletionPrivacyFenceClosed,
                dispatch = ::refreshAccountDeletionStatus,
            )
        }
        val session = snapshot.session
        val firstRun = snapshot.restoredFirstRunSnapshot
        val actorId = firstRun?.reporterActorBinding?.value
        if (
            session != null &&
            firstRun != null &&
            firstRun.isComplete &&
            actorId != null &&
            actorId == session.actorId
        ) {
            firstRunOnboardingSnapshot = firstRun
            reporterUserId = actorId
            permissionSessionPolicy.rememberActor(actorId)
            if (
                ::priorityUserOnboardingPolicy.isInitialized &&
                priorityUserOnboardingActorId != actorId
            ) {
                restorePriorityUserOnboardingFromPrefs()
            }
            if (
                !snapshot.storageBlocked &&
                session.verificationState == GatewaySessionVerificationState.VERIFIED &&
                session.isUsableFor(actorId) &&
                priorityUserOnboardingActorId == actorId &&
                priorityUserOnboardingPolicy.accountBlockReason() == null
            ) {
                permissionSessionPolicy.authenticated(actorId)
            }
        } else {
            permissionSessionPolicy.authenticationExpired()
        }
        if (
            session != null &&
            !snapshot.deletionRecoveryOnly &&
            !snapshot.storageBlocked &&
            session.verificationState == GatewaySessionVerificationState.VERIFIED &&
            session.isUsableFor(session.actorId)
        ) {
            requestGatewayCapacityRefresh("verified_session_startup")
        }
        if (::backendAuthApplyButton.isInitialized) {
            runOnUiThread {
                if (
                    privacyStartupInspectionDestroyed ||
                    !accountDeletionStateMachine.activityLeaseIsCurrent(
                        accountDeletionActivityLease,
                    )
                ) return@runOnUiThread
                updateBackendAuthButtonText()
                updateReportPrivacyConsentUi()
                updateAccountDeletionUi()
            }
        }
    }

    private fun registerGatewayCapacityNetworkObserver() {
        if (gatewayCapacityNetworkObserverRegistered) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            manager.registerDefaultNetworkCallback(gatewayCapacityNetworkCallback)
        }.onSuccess {
            gatewayCapacityConnectivityManager = manager
            gatewayCapacityNetworkObserverRegistered = true
        }
    }

    private fun unregisterGatewayCapacityNetworkObserver() {
        if (!gatewayCapacityNetworkObserverRegistered) return
        runCatching {
            gatewayCapacityConnectivityManager?.unregisterNetworkCallback(
                gatewayCapacityNetworkCallback,
            )
        }
        gatewayCapacityConnectivityManager = null
        gatewayCapacityNetworkObserverRegistered = false
    }

    private fun requestGatewayCapacityRefresh(trigger: String) {
        if (privacyStartupInspectionDestroyed) return
        val snapshot = GatewaySessionProcessCoordinator.snapshot()
        val session = snapshot.session?.takeUnless { snapshot.deletionRecoveryOnly } ?: return
        val expectedSessionGeneration = snapshot.generation
        if (!isGatewayCapacityRefreshSessionCurrent(session, expectedSessionGeneration)) return
        GatewayCapacityProcessState.fenceSessionGeneration(expectedSessionGeneration)
        if (!GatewayCapacityProcessState.tryBeginRefresh()) return
        val expectedActivityLease = accountDeletionActivityLease
        try {
            gatewaySessionExecutor.execute {
                try {
                    if (
                        !isGatewayCapacityRefreshSessionCurrent(
                            session,
                            expectedSessionGeneration,
                        )
                    ) return@execute
                    val revalidation = gatewaySessionClient.revalidate(
                        session = session,
                        actorId = session.actorId,
                        capacitySessionGeneration = expectedSessionGeneration,
                    )
                    val capacityUpdate = revalidation.capacityUpdate
                    val adminWarning =
                        capacityUpdate?.signals?.adminWarningOneShot == true
                    val participantRestricted =
                        capacityUpdate?.signals
                            ?.participantAdmissionRestricted == true
                    if (adminWarning || participantRestricted) {
                        postGatewayActivityCallback(expectedActivityLease) {
                            if (
                                !isGatewayCapacityRefreshSessionCurrent(
                                    session,
                                    expectedSessionGeneration,
                                )
                            ) {
                                return@postGatewayActivityCallback
                            }
                            if (::fieldSessionLog.isInitialized) fieldSessionLog.recordEvent(
                                "gateway_capacity_signal",
                                mapOf(
                                    "trigger" to trigger,
                                    "version" to
                                        capacityUpdate?.admission
                                            ?.snapshot?.version,
                                    "admin_warning" to adminWarning,
                                    "participant_admission_restricted" to
                                        participantRestricted,
                                ),
                            )
                        }
                    }
                    if (
                        revalidation.status ==
                        GatewaySessionRevalidationStatus.NOT_READY
                    ) {
                        postGatewayActivityCallback(expectedActivityLease) {
                            clearGatewaySession(
                                logoutRemote = false,
                                expectedSession = session,
                            )
                        }
                    }
                } finally {
                    if (GatewayCapacityProcessState.finishRefresh()) {
                        requestGatewayCapacityRefresh("coalesced_latest_session")
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            if (GatewayCapacityProcessState.finishRefresh()) {
                requestGatewayCapacityRefresh("coalesced_latest_session")
            }
        }
    }

    private fun isGatewayCapacityRefreshSessionCurrent(
        session: GatewayFieldSession,
        expectedGeneration: Long? = null,
    ): Boolean {
        val snapshot = GatewaySessionProcessCoordinator.snapshot()
        return !snapshot.deletionRecoveryOnly &&
            !snapshot.storageBlocked &&
            snapshot.session === session &&
            (expectedGeneration == null || snapshot.generation == expectedGeneration) &&
            session.verificationState == GatewaySessionVerificationState.VERIFIED &&
            session.isUsableFor(session.actorId)
    }

    private fun gatewayActivityCallbackAllowed(
        expectedLease: AccountDeletionActivityLease,
    ): Boolean =
        !privacyStartupInspectionDestroyed &&
            accountDeletionStateMachine.activityLeaseIsCurrent(expectedLease)

    private fun postGatewayActivityCallback(
        expectedLease: AccountDeletionActivityLease,
        callback: () -> Unit,
    ) {
        runOnUiThread {
            runGatewayActivityCallbackIfCurrent(
                activityDestroyed = privacyStartupInspectionDestroyed,
                expectedLeaseIsCurrent =
                    accountDeletionStateMachine.activityLeaseIsCurrent(expectedLease),
                callback = callback,
            )
        }
    }

    private fun restoreGatewaySessionFromPrefs() {
        val processSnapshot = GatewaySessionProcessCoordinator.snapshot()
        if (
            processSnapshot.session != null ||
            processSnapshot.inFlightOperationId != null
        ) {
            return
        }
        val operation = GatewaySessionProcessCoordinator.beginOperation() ?: return
        permissionSessionPolicy.authenticationExpired()
        val expectedOrigin = configuredGatewayOriginOrNull() ?: run {
            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            return
        }
        val recovered = gatewaySessionStore.recoverRenewingToPendingRevocation()
        if (
            recovered == GatewaySessionStoreResult.BLOCKED ||
            recovered == GatewaySessionStoreResult.STORAGE_FAILURE
        ) {
            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            return
        }
        gatewaySessionStore.restorePendingRevocation(expectedOrigin)?.let { pending ->
            val pendingOperation =
                GatewaySessionProcessCoordinator.publishPendingRevocation(operation)
                    ?: return
            drainPendingGatewayRevocation(pending, pendingOperation)
            return
        }
        val restored = gatewaySessionStore.restoreActive(
            expectedGatewayBaseUrl = expectedOrigin,
        )
        if (restored == null) {
            gatewaySessionStore.restorePendingRevocation(expectedOrigin)?.let { pending ->
                val pendingOperation =
                    GatewaySessionProcessCoordinator.publishPendingRevocation(operation)
                        ?: return
                drainPendingGatewayRevocation(pending, pendingOperation)
                return
            }
        }
        if (!BuildConfig.WALKSAFE_LONG_LIVED_LOGIN_ENABLED) {
            if (restored == null) {
                if (gatewaySessionStore.purgeDisabled()) {
                    GatewaySessionProcessCoordinator.clear(operation)
                } else {
                    GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                }
                return
            }
            stageRestoredGatewayRevocation(restored, operation)
            return
        }
        if (restored == null) {
            GatewaySessionProcessCoordinator.clear(operation)
            return
        }
        if (
            restored.session.verificationState !=
            GatewaySessionVerificationState.RESTORED_UNVERIFIED
        ) {
            restored.session.invalidate()
            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            return
        }
        val actorId = restored.firstRunSnapshot.reporterActorBinding?.value
        if (actorId == null || actorId != restored.session.actorId) {
            restored.session.invalidate()
            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            return
        }
        firstRunOnboardingSnapshot = restored.firstRunSnapshot
        reporterUserId = actorId
        permissionSessionPolicy.rememberActor(actorId)
        restorePriorityUserOnboardingFromPrefs()
        if (
            priorityUserOnboardingActorId != actorId ||
            priorityUserOnboardingPolicy.accountBlockReason() != null
        ) {
            stageRestoredGatewayRevocation(restored, operation)
            return
        }
        val renewalOperation =
            GatewaySessionProcessCoordinator.publishRestoredUnverified(
                operation = operation,
                session = restored.session,
                firstRunSnapshot = restored.firstRunSnapshot,
            ) ?: return
        renewRestoredGatewaySession(restored, renewalOperation)
    }

    private fun stageRestoredGatewayRevocation(
        restored: RestoredGatewayLoginBundle,
        operation: GatewaySessionOperation,
    ) {
        val staged = gatewaySessionStore.moveActiveToPendingRevocation(
            expectedVersion = restored.version,
            operationId = operation.operationId,
        )
        restored.session.invalidate()
        permissionSessionPolicy.authenticationExpired()
        if (
            staged != GatewaySessionStoreResult.COMMITTED &&
            staged != GatewaySessionStoreResult.ALREADY_COMMITTED
        ) {
            if (
                staged == GatewaySessionStoreResult.BLOCKED ||
                staged == GatewaySessionStoreResult.STORAGE_FAILURE
            ) {
                GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            } else {
                GatewaySessionProcessCoordinator.clear(operation)
            }
            return
        }
        val pending = gatewaySessionStore.restorePendingRevocation(
            restored.version.gatewayBaseUrl,
        )
        val pendingOperation =
            GatewaySessionProcessCoordinator.publishPendingRevocation(operation)
                ?: return
        if (pending == null) {
            GatewaySessionProcessCoordinator.markStorageBlocked(pendingOperation)
            return
        }
        drainPendingGatewayRevocation(pending, pendingOperation)
    }

    private fun drainPendingGatewayRevocation(
        pending: GatewayPendingRevocation,
        operation: GatewaySessionOperation,
        result: ((Boolean) -> Unit)? = null,
        expectedActivityLease: AccountDeletionActivityLease =
            accountDeletionActivityLease,
    ) {
        try {
            gatewaySessionExecutor.execute {
                val remoteRevoked = runCatching {
                    gatewaySessionClient.logout(pending)
                    true
                }.getOrDefault(false)
                val completed = if (remoteRevoked) {
                    gatewaySessionStore.completePendingRevocation(
                        expectedVersion = pending.version,
                        operationId = pending.operationId,
                    )
                } else {
                    null
                }
                val fullyCompleted =
                    remoteRevoked && completed == GatewaySessionStoreResult.COMMITTED
                when {
                    fullyCompleted || !remoteRevoked ->
                        GatewaySessionProcessCoordinator.clear(operation)
                    else ->
                        GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                }
                if (result != null) {
                    postGatewayActivityCallback(expectedActivityLease) {
                        result(fullyCompleted)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            GatewaySessionProcessCoordinator.clear(operation)
            if (result != null) {
                postGatewayActivityCallback(expectedActivityLease) { result(false) }
            }
        }
    }

    private fun restoreReportCooldownsFromPrefs() {
        if (sensitivePrefs.isBlocked()) {
            reportAttemptStateStorageBlocked = true
            return
        }
        val raw = sensitivePrefs.getString(PREF_REPORT_COOLDOWNS_KEY, null) ?: return
        val cooldowns = runCatching {
            val payload = JSONArray(raw)
            buildList {
                for (index in 0 until minOf(payload.length(), AndroidReportCooldownPolicy.MAX_TRACKED_STATES)) {
                    val item = payload.optJSONObject(index) ?: continue
                    val scope = AndroidReportCooldownPolicy.spatialScopeOrNull(
                        actorId = item.optString("actor_id"),
                        className = item.optString("class_name"),
                        location = TrustedLocation(
                            latitude = item.getDouble("latitude"),
                            longitude = item.getDouble("longitude"),
                            accuracyM = 0f,
                            elapsedRealtimeMs = 0L,
                        ),
                    ) ?: continue
                    add(
                        AndroidReportSuccessfulCooldown(
                            scope = scope,
                            lastUploadedAtMs = item.getLong("last_uploaded_at_ms"),
                        ),
                    )
                }
            }
        }.getOrElse {
            sensitivePrefs.failClosed()
            reportAttemptStateStorageBlocked = true
            return
        }
        reportAttemptStore.restoreSuccessfulCooldowns(cooldowns, System.currentTimeMillis())
        persistReportCooldowns()
    }

    private fun persistReportCooldowns(): Boolean {
        if (sensitivePrefs.isBlocked()) return false
        val payload = JSONArray()
        reportAttemptStore.successfulCooldowns(System.currentTimeMillis()).forEach { cooldown ->
            payload.put(
                JSONObject()
                    .put("actor_id", cooldown.scope.actorId)
                    .put("class_name", cooldown.scope.className)
                    .put("latitude", cooldown.scope.latitude)
                    .put("longitude", cooldown.scope.longitude)
                    .put("last_uploaded_at_ms", cooldown.lastUploadedAtMs),
            )
        }
        return sensitivePrefs.edit()
            .putString(PREF_REPORT_COOLDOWNS_KEY, payload.toString())
            .commit()
    }

    private fun persistReportCooldownForSuccess(
        cooldown: AndroidReportSuccessfulCooldown,
    ): Boolean {
        if (sensitivePrefs.isBlocked()) return false
        val cooldowns =
            reportAttemptStore.successfulCooldowns(System.currentTimeMillis())
                .filterNot { it.scope == cooldown.scope } + cooldown
        val payload = JSONArray()
        cooldowns.takeLast(AndroidReportCooldownPolicy.MAX_TRACKED_STATES)
            .forEach { item ->
                payload.put(
                    JSONObject()
                        .put("actor_id", item.scope.actorId)
                        .put("class_name", item.scope.className)
                        .put("latitude", item.scope.latitude)
                        .put("longitude", item.scope.longitude)
                        .put("last_uploaded_at_ms", item.lastUploadedAtMs),
                )
            }
        return sensitivePrefs.edit()
            .putString(PREF_REPORT_COOLDOWNS_KEY, payload.toString())
            .commit()
    }

    private fun ensureReportAttemptStateActor(actorId: String): Boolean =
        synchronized(reportAttemptStateLock) {
            if (sensitivePrefs.isBlocked()) {
                reportAttemptStateStorageBlocked = true
                return@synchronized false
            }
            val actorHash = sha256Hex(actorId.toByteArray(Charsets.UTF_8))
            if (reportAttemptStateActorHash == actorHash) {
                return@synchronized !reportAttemptStateStorageBlocked
            }
            reportAttemptStateStorageBlocked = false
            reportAttemptStorageBlockedActors.remove(actorHash)
            if (
                reportAttemptStateActorHash != null &&
                reportAttemptStateActorHash != actorHash
            ) {
                persistedReportAttemptStates.clear()
                if (
                    !sensitivePrefs.edit()
                        .remove(PREF_REPORT_ATTEMPT_STATE_V2)
                        .commit()
                ) {
                    reportAttemptStateStorageBlocked = true
                    return@synchronized false
                }
            }
            reportAttemptStateActorHash = actorHash
            persistedReportAttemptStates.clear()
            val raw =
                sensitivePrefs.getString(PREF_REPORT_ATTEMPT_STATE_V2, null)
                    ?: return@synchronized true
            val restored = runCatching {
                val payload = JSONObject(raw)
                check(payload.getString("schema") == REPORT_ATTEMPT_STATE_SCHEMA_V2)
                check(payload.getString("actor_hash") == actorHash)
                val entries = payload.getJSONArray("entries")
                check(entries.length() <= REPORT_ATTEMPT_STATE_MAX_ENTRIES)
                for (index in 0 until entries.length()) {
                    val item = entries.getJSONObject(index)
                    val key = item.getString("key")
                    check(REPORT_ATTEMPT_STATE_KEY.matches(key))
                    val failures = item.getInt("failures")
                    check(failures in 0..REPORT_ATTEMPT_MAX_FAILURES)
                    persistedReportAttemptStates[key] =
                        PersistedReportAttemptState(
                            consecutiveFailures = failures,
                            retryNotBeforeMs = item.getLong("retry_not_before_ms"),
                            terminalStatus =
                                if (item.isNull("terminal_status")) null
                                else item.getInt("terminal_status"),
                            terminalUntilMs = item.getLong("terminal_until_ms"),
                        )
                }
            }.isSuccess
            if (restored) {
                true
            } else {
                persistedReportAttemptStates.clear()
                sensitivePrefs.failClosed()
                reportAttemptStateStorageBlocked = true
                false
            }
        }

    private fun reportAttemptStateKey(
        actorId: String,
        className: String,
        latitude: Double,
        longitude: Double,
        transferPurpose: ReportTransferPurpose,
    ): String =
        sha256Hex(
            "$actorId|$className|$latitude|$longitude|${transferPurpose.name}"
                .toByteArray(Charsets.UTF_8),
        )

    private fun persistedAutomaticReportBlockStatus(
        stateKey: String,
        nowMs: Long,
    ): String? = synchronized(reportAttemptStateLock) {
        if (
            reportAttemptStateStorageBlocked ||
            reportAttemptStateActorHash?.let(
                reportAttemptStorageBlockedActors::contains,
            ) == true
        ) {
            return@synchronized "reportCandidate=blocked:attempt_state_storage"
        }
        val state = persistedReportAttemptStates[stateKey] ?: return@synchronized null
        if (state.terminalUntilMs > nowMs) {
            return@synchronized(
                "reportCandidate=terminal_backoff status=${state.terminalStatus ?: "protocol"} " +
                    "remainingMs=${state.terminalUntilMs - nowMs}"
            )
        }
        if (state.retryNotBeforeMs > nowMs) {
            return@synchronized(
                "reportCandidate=backoff_persisted remainingMs=" +
                    "${state.retryNotBeforeMs - nowMs}"
            )
        }
        null
    }

    private fun persistTransientReportAttemptFailure(
        stateKey: String,
        nowMs: Long,
        statusCode: Int? = null,
        retryAfterMs: Long? = null,
    ): Long = synchronized(reportAttemptStateLock) {
        val previous = persistedReportAttemptStates[stateKey]
        val failures =
            ((previous?.consecutiveFailures ?: 0) + 1)
                .coerceAtMost(REPORT_ATTEMPT_MAX_FAILURES)
        val delayMs =
            if (statusCode == null) {
                reportRetryDelayMs(failures)
            } else {
                reportRetryDelayMs(failures, statusCode, retryAfterMs)
            }
        if (
            previous == null &&
            persistedReportAttemptStates.size >= REPORT_ATTEMPT_STATE_MAX_ENTRIES
        ) {
            reportAttemptStateStorageBlocked = true
            return@synchronized delayMs
        }
        persistedReportAttemptStates[stateKey] =
            PersistedReportAttemptState(
                consecutiveFailures = failures,
                retryNotBeforeMs = nowMs + delayMs,
                terminalStatus = null,
                terminalUntilMs = 0L,
            )
        if (!persistReportAttemptStatesLocked()) {
            reportAttemptStateStorageBlocked = true
        }
        delayMs
    }

    private fun persistTerminalReportAttemptState(
        stateKey: String,
        statusCode: Int?,
        nowMs: Long,
    ): Boolean = synchronized(reportAttemptStateLock) {
        val previous = persistedReportAttemptStates[stateKey]
        if (
            previous == null &&
            persistedReportAttemptStates.size >= REPORT_ATTEMPT_STATE_MAX_ENTRIES
        ) {
            reportAttemptStateStorageBlocked = true
            return@synchronized false
        }
        persistedReportAttemptStates[stateKey] =
            PersistedReportAttemptState(
                consecutiveFailures = previous?.consecutiveFailures ?: 0,
                retryNotBeforeMs = 0L,
                terminalStatus = statusCode,
                terminalUntilMs = nowMs + REPORT_TERMINAL_STATE_TTL_MS,
            )
        persistReportAttemptStatesLocked().also {
            if (!it) reportAttemptStateStorageBlocked = true
        }
    }

    private fun blockReportAttemptStorageForActor(actorId: String) {
        synchronized(reportAttemptStateLock) {
            reportAttemptStorageBlockedActors +=
                sha256Hex(actorId.toByteArray(Charsets.UTF_8))
        }
    }

    private fun clearPersistedReportAttemptState(stateKey: String): Boolean =
        synchronized(reportAttemptStateLock) {
            persistedReportAttemptStates.remove(stateKey)
            persistReportAttemptStatesLocked().also {
                if (!it) {
                    reportAttemptStateStorageBlocked = true
                } else {
                    reportAttemptStateActorHash?.let(
                        reportAttemptStorageBlockedActors::remove,
                    )
                }
            }
        }

    private fun persistReportAttemptStatesLocked(): Boolean {
        if (sensitivePrefs.isBlocked()) return false
        val actorHash = reportAttemptStateActorHash ?: return false
        val entries = JSONArray()
        persistedReportAttemptStates.forEach { (key, state) ->
            entries.put(
                JSONObject()
                    .put("key", key)
                    .put("failures", state.consecutiveFailures)
                    .put("retry_not_before_ms", state.retryNotBeforeMs)
                    .put(
                        "terminal_status",
                        state.terminalStatus ?: JSONObject.NULL,
                    )
                    .put("terminal_until_ms", state.terminalUntilMs),
            )
        }
        return sensitivePrefs.edit()
            .putString(
                PREF_REPORT_ATTEMPT_STATE_V2,
                JSONObject()
                    .put("schema", REPORT_ATTEMPT_STATE_SCHEMA_V2)
                    .put("actor_hash", actorHash)
                    .put("entries", entries)
                    .toString(),
            )
            .commit()
    }

    private fun newStableReportTraceId(
        actorId: String,
        sessionGeneration: Long,
    ): String {
        val namespace =
            sha256Hex(
                "$actorId|$sessionGeneration".toByteArray(Charsets.UTF_8),
            ).take(24)
        return "android:$namespace:${UUID.randomUUID()}".take(128)
    }

    private fun persistReporterUserFromInput() {
        loginUserIdInput.text?.clear()
        updateStatus(
            "수동 로그인 ID 사용 불가",
            "화면에 입력한 ID는 검증된 로그인 신원을 대신할 수 없습니다. 첫 실행 등록의 운영 로그인 증거만 사용합니다.",
        )
        updateNavigationStatus("login=blocked manual_reporter_id_disallowed")
        speakInteraction("수동 로그인 아이디는 사용할 수 없습니다.")
    }

    private fun onAccountLogoutClicked() {
        if (accountDeletionStateMachine.durableConfirmationRecoveryRequired()) {
            applyAccountDeletionRuntimeFence()
            updateNavigationStatus("login=blocked account_deletion_recovery")
            speakInteraction(
                "삭제 확인이 보존된 동안에는 계정 신원을 지울 수 없습니다. 삭제 요청 준비를 먼저 완료하세요.",
            )
            return
        }
        val walkWasActive = isWalkSessionRuntimeActive()
        invalidateOfficialEnvironmentEvidence("priority_user_account_logged_out")
        invalidatePhoneMountingEvidence("priority_user_account_logged_out")
        cancelPendingPriorityUserTrainingFeedback()
        val currentSession = gatewayFieldSession
        clearGatewaySession(
            logoutRemote = currentSession != null,
            expectedSession = currentSession,
        )
        reporterUserId = null
        priorityUserOnboardingActorId = null
        priorityUserOnboardingPolicy = PriorityUserOnboardingPolicy()
        permissionSessionPolicy.explicitLogout()
        stepLengthPrefs.edit().remove(PREF_REPORTER_USER_ID_KEY).commit()
        if (::loginUserIdInput.isInitialized) loginUserIdInput.text?.clear()
        if (walkWasActive) {
            enterWalkSessionSafetyStopAndCancelOutputs(
                "priority_user_account_logged_out",
            )
        }
        firstRunOnboardingSnapshot = FirstRunOnboardingPolicy.initial(
            epoch = if (firstRunOnboardingSnapshot.epoch < Long.MAX_VALUE) {
                firstRunOnboardingSnapshot.epoch + 1L
            } else {
                SystemClock.elapsedRealtimeNanos().coerceAtLeast(1L)
            },
        )
        updateLoginButtonText()
        updateBackendAuthButtonText()
        updateReportPrivacyConsentUi()
        updatePriorityUserOnboardingUi()
        updateFirstRunOnboardingUi()
        updateNavigationStatus("login=logged_out independent_preferences_retained")
        speakInteraction("로그아웃했습니다. 운영체제 권한과 신고 동의, 통신 설정은 그대로 유지됩니다.")
        if (::startupCapabilityProbe.isInitialized) refreshStartupCapabilityUi()
    }

    private fun onReportPrivacyConsentButtonClicked() {
        if (integratedConsentDraft.rawSourceCollection) {
            withdrawReportPrivacyConsent(reason = "user_withdrew", announce = true)
            return
        }
        updateIntegratedConsentDraft(
            IntegratedConsentItem.RAW_SOURCE_COLLECTION,
            granted = true,
        )
        persistIntegratedConsentDraft(announce = true)
    }

    private fun withdrawReportPrivacyConsent(reason: String, announce: Boolean = false) {
        updateIntegratedConsentDraft(
            IntegratedConsentItem.RAW_SOURCE_COLLECTION,
            granted = false,
        )
        latestReportCandidateStatus = "reportCandidate=blocked:privacy_consent_required"
        updateNavigationStatus("reportPrivacy=withdrawn reason=$reason")
        if (announce) {
            speakInteraction("원본 수집 동의를 즉시 철회했습니다. 서버 선택을 갱신합니다.")
        }
        persistIntegratedConsentDraft(announce = announce)
    }

    private fun onAutomaticReportConsentButtonClicked() {
        updateIntegratedConsentDraft(
            IntegratedConsentItem.AUTOMATIC_REPORTING,
            granted = !integratedConsentDraft.automaticReporting,
        )
        persistIntegratedConsentDraft(announce = true)
    }

    private fun onMobileNetworkPreferenceButtonClicked() {
        updateIntegratedConsentDraft(
            IntegratedConsentItem.MOBILE_NETWORK_TRANSFER,
            granted = !integratedConsentDraft.mobileNetworkTransfer,
        )
        persistIntegratedConsentDraft(announce = true)
    }

    private fun onTrainingReuseConsentButtonClicked() {
        updateIntegratedConsentDraft(
            IntegratedConsentItem.TRAINING_REUSE,
            granted = !integratedConsentDraft.trainingReuse,
        )
        persistIntegratedConsentDraft(announce = true)
    }

    private fun updateIntegratedConsentDraft(
        item: IntegratedConsentItem,
        granted: Boolean,
    ) {
        integratedConsentDraft = integratedConsentDraft.withDecision(item, granted)
        if (!granted) {
            if (sensitivePrefs.isBlocked()) {
                applyImmediateConsentWithdrawals(setOf(item))
                integratedConsentSession.failClosed()
                return
            }
            val existing =
                sensitivePrefs.getString(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED, null)
                    ?.let(::localWithdrawalMarkerItemsOrNull)
                    .orEmpty()
            val marker = JSONArray(
                (existing + item).map(IntegratedConsentItem::wireValue),
            ).toString()
            val rawFencePersisted =
                item != IntegratedConsentItem.RAW_SOURCE_COLLECTION ||
                    stepLengthPrefs.edit()
                        .putBoolean(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED, true)
                        .commit()
            val markerPersisted = rawFencePersisted &&
                sensitivePrefs.edit()
                    .putString(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED, marker)
                    .commit()
            applyImmediateConsentWithdrawals(setOf(item))
            cancelIntegratedConsentControlCall()
            if (!markerPersisted) {
                integratedConsentSession.failClosed()
            }
        }
        postIntegratedConsentUiRefresh()
    }

    private fun postIntegratedConsentUiRefresh() {
        runOnUiThread {
            if (!privacyStartupInspectionDestroyed && ::privacyConsentStatusText.isInitialized) {
                updateIntegratedConsentUi()
            }
        }
    }

    private fun cancelIntegratedConsentControlCall() {
        synchronized(integratedConsentLock) {
            integratedConsentRequestGeneration += 1L
            integratedConsentCall?.cancel()
            integratedConsentCall = null
            integratedConsentRequestInFlight = false
        }
    }

    private fun integratedConsentHttpBlocked(): Boolean =
        accountDeletionStateMachine.processingBlocked() ||
            IntegratedConsentItem.entries.any { item ->
                integratedConsentSession.status(item) ==
                    PurposeConsentSyncState.FAIL_CLOSED
            }

    private fun applyImmediateConsentWithdrawals(
        items: Set<IntegratedConsentItem>,
    ) {
        items.forEach { item ->
            integratedConsentSession.withdrawImmediately(item)
            when (item) {
                IntegratedConsentItem.RAW_SOURCE_COLLECTION -> {
                    synchronized(reportUploadSafetyLock) {
                        reportUploadSafetyGeneration += 1L
                        reportPrivacyConsentSession.withdraw()
                    }
                    scheduleLegacyPendingReportQueuePurge()
                    permissionSessionPolicy.setRawCollectionConsent(false)
                    if (::metadataLogUploader.isInitialized) {
                        metadataLogUploader.setEnabled(false)
                    }
                    if (::fieldSessionLog.isInitialized) {
                        fieldSessionLog.blockForRawSourceWithdrawal()
                    }
                }
                IntegratedConsentItem.AUTOMATIC_REPORTING -> {
                    synchronized(reportUploadSafetyLock) {
                        automaticReportUploadSafetyGeneration += 1L
                        reportPrivacyConsentSession.cancelActiveCalls(
                            ReportTransferPurpose.AUTOMATIC,
                        )
                    }
                    scheduleLegacyPendingReportQueuePurge()
                    permissionSessionPolicy.setAutomaticReportConsent(false)
                }
                IntegratedConsentItem.MOBILE_NETWORK_TRANSFER -> {
                    permissionSessionPolicy.setMobileNetworkPreference(
                        MobileNetworkPreference.WIFI_ONLY,
                    )
                    if (
                        ::networkStateProbe.isInitialized &&
                        networkStateProbe.currentTransport() ==
                        ActiveNetworkTransport.CELLULAR
                    ) {
                        cancelGatewayNetworkCalls()
                        synchronized(reportUploadSafetyLock) {
                            reportUploadSafetyGeneration += 1L
                            reportPrivacyConsentSession.cancelActiveCalls()
                        }
                    }
                }
                IntegratedConsentItem.TRAINING_REUSE ->
                    permissionSessionPolicy.setTrainingReuseConsent(false)
            }
        }
        postIntegratedConsentUiRefresh()
    }

    private fun refreshIntegratedConsentFromServer() {
        if (integratedConsentHttpBlocked()) return
        val gatewayOrigin = configuredGatewayOriginOrNull() ?: return
        val installationId = gatewaySessionStore.getOrCreateInstallDeviceId() ?: return
        val controlSecret = getOrCreateIntegratedConsentControlSecret() ?: return
        startIntegratedConsentRequest(
            call = integratedConsentClient.fetchCurrentCall(
                gatewayBaseUrl = gatewayOrigin,
                installationId = installationId,
                controlSecret = controlSecret,
            ),
            expectedSelections = null,
            expectedClientRevision = null,
            completeOnboarding = false,
            announce = false,
        )
    }

    private fun getOrCreateIntegratedConsentControlSecret(): String? {
        if (sensitivePrefs.isBlocked()) return null
        val existing =
            sensitivePrefs.getString(
                PREF_INTEGRATED_CONSENT_CONTROL_SECRET,
                null,
            )
        if (existing != null && INTEGRATED_CONSENT_CONTROL_SECRET.matches(existing)) {
            return existing
        }
        if (sensitivePrefs.contains(PREF_INTEGRATED_CONSENT_CONTROL_SECRET)) {
            sensitivePrefs.failClosed()
            integratedConsentSession.failClosed()
            return null
        }
        val random = ByteArray(32)
        SecureRandom().nextBytes(random)
        val generated = random.joinToString(separator = "") { byte ->
            "%02x".format(Locale.US, byte.toInt() and 0xff)
        }
        return if (
            sensitivePrefs.edit()
                .putString(PREF_INTEGRATED_CONSENT_CONTROL_SECRET, generated)
                .commit()
        ) {
            generated
        } else {
            null
        }
    }

    private fun existingIntegratedConsentControlSecretOrNull(): String? =
        sensitivePrefs.getString(
            PREF_INTEGRATED_CONSENT_CONTROL_SECRET,
            null,
        )?.takeIf(INTEGRATED_CONSENT_CONTROL_SECRET::matches)

    private fun persistIntegratedConsentDraft(announce: Boolean) {
        if (sensitivePrefs.isBlocked()) {
            integratedConsentSession.failClosed()
            updateNavigationStatus("integratedConsent=blocked:sensitive_storage")
            return
        }
        if (integratedConsentHttpBlocked()) {
            updateNavigationStatus("integratedConsent=blocked:account_deletion")
            return
        }
        val gatewayOrigin = configuredGatewayOriginOrNull() ?: run {
            updateNavigationStatus("integratedConsent=blocked:gateway_origin_unavailable")
            if (announce) {
                speakInteraction("동의 서버가 구성되지 않아 선택을 허용 상태로 바꾸지 않았습니다.")
            }
            return
        }
        val gatewaySession = gatewaySessionOrNull(
            reason = "integrated_consent",
            speak = announce,
        ) ?: run {
            updateNavigationStatus("integratedConsent=blocked:gateway_session_required")
            return
        }
        val installationId = gatewaySessionStore.getOrCreateInstallDeviceId() ?: run {
            updateNavigationStatus("integratedConsent=blocked:installation_storage")
            if (announce) {
                speakInteraction("기기 식별 상태를 안전하게 저장하지 못해 동의를 진행하지 않습니다.")
            }
            return
        }
        val controlSecret = getOrCreateIntegratedConsentControlSecret() ?: run {
            updateNavigationStatus("integratedConsent=blocked:control_secret_storage")
            if (announce) {
                speakInteraction("동의 제어 비밀을 안전하게 저장하지 못해 선택을 전송하지 않습니다.")
            }
            return
        }
        if (integratedConsentClientRevision == Long.MAX_VALUE) {
            updateNavigationStatus("integratedConsent=blocked:client_revision_exhausted")
            return
        }
        val clientRevision = integratedConsentClientRevision + 1L
        val selections = integratedConsentDraft
        val requestId = "consent_" + sha256Hex(
            (
                "FP013|$installationId|$clientRevision|" +
                    selections.toString()
            ).toByteArray(),
        )
        val confirmedSelections = persistedIntegratedConsentSelections()
        val withdrawalItems = IntegratedConsentItem.entries
            .filterTo(mutableSetOf()) { item ->
                confirmedSelections.isGranted(item) && !selections.isGranted(item)
            }
        val mutation = PendingIntegratedConsentMutation(
            installationId = installationId,
            requestId = requestId,
            policyVersion = INTEGRATED_CONSENT_POLICY_VERSION,
            clientRevision = clientRevision,
            previousServerRevision =
                sensitivePrefs.getLong(PREF_INTEGRATED_CONSENT_REVISION, 0L)
                    .coerceAtLeast(0L),
            desiredSelections = selections,
            withdrawalItems = withdrawalItems,
            createdAtEpochMs = System.currentTimeMillis().coerceAtLeast(1L),
        )
        val mutationStored = sensitivePrefs.edit()
            .putLong(PREF_INTEGRATED_CONSENT_CLIENT_REVISION, clientRevision)
            .putString(
                PREF_PENDING_INTEGRATED_CONSENT_MUTATION,
                pendingIntegratedConsentMutationJson(mutation),
            )
            .remove(PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED)
            .commit()
        if (!mutationStored) {
            applyImmediateConsentWithdrawals(withdrawalItems)
            integratedConsentSession.failClosed()
            updateNavigationStatus("integratedConsent=blocked:mutation_storage")
            if (announce) {
                speakInteraction("철회 상태를 안전하게 저장하지 못해 개인정보 기능을 잠갔습니다.")
            }
            return
        }
        synchronized(integratedConsentLock) {
            integratedConsentRequestGeneration += 1L
            integratedConsentCall?.cancel()
            integratedConsentCall = null
            integratedConsentRequestInFlight = false
        }
        integratedConsentClientRevision = clientRevision
        pendingIntegratedConsentMutation = mutation
        integratedConsentSession.restorePendingMutation(mutation)
        applyImmediateConsentWithdrawals(withdrawalItems)
        startIntegratedConsentRequest(
            call = integratedConsentClient.saveCall(
                gatewayBaseUrl = gatewayOrigin,
                session = gatewaySession,
                installationId = installationId,
                controlSecret = controlSecret,
                requestId = requestId,
                clientRevision = clientRevision,
                selections = selections,
            ),
            expectedSelections = selections,
            expectedClientRevision = clientRevision,
            completeOnboarding =
                firstRunOnboardingSnapshot.stage ==
                    FirstRunOnboardingStage.INTEGRATED_CONSENT,
            announce = announce,
        )
    }

    private fun retryPendingIntegratedConsentMutation(announce: Boolean = false) {
        if (integratedConsentHttpBlocked()) return
        val mutation = pendingIntegratedConsentMutation ?: return
        val gatewayOrigin = configuredGatewayOriginOrNull() ?: run {
            integratedConsentSession.markPendingRetry()
            return
        }
        val gatewaySession = gatewaySessionOrNull(
            reason = "integrated_consent_retry",
            speak = false,
        ) ?: run {
            integratedConsentSession.markPendingRetry()
            return
        }
        val controlSecret = existingIntegratedConsentControlSecretOrNull() ?: run {
            integratedConsentSession.failClosed()
            return
        }
        integratedConsentDraft = mutation.desiredSelections
        integratedConsentClientRevision =
            maxOf(integratedConsentClientRevision, mutation.clientRevision)
        integratedConsentSession.restorePendingMutation(mutation, retry = true)
        applyImmediateConsentWithdrawals(mutation.withdrawalItems)
        startIntegratedConsentRequest(
            call = integratedConsentClient.saveCall(
                gatewayBaseUrl = gatewayOrigin,
                session = gatewaySession,
                installationId = mutation.installationId,
                controlSecret = controlSecret,
                requestId = mutation.requestId,
                clientRevision = mutation.clientRevision,
                selections = mutation.desiredSelections,
            ),
            expectedSelections = mutation.desiredSelections,
            expectedClientRevision = mutation.clientRevision,
            completeOnboarding = false,
            announce = announce,
        )
    }

    private fun startIntegratedConsentRequest(
        call: CancellableNetworkCall<IntegratedConsentConfirmation?>,
        expectedSelections: IntegratedConsentSelections?,
        expectedClientRevision: Long?,
        completeOnboarding: Boolean,
        announce: Boolean,
    ) {
        if (integratedConsentHttpBlocked()) {
            call.cancel()
            return
        }
        val generation = synchronized(integratedConsentLock) {
            integratedConsentCall?.cancel()
            integratedConsentRequestGeneration += 1L
            integratedConsentCall = call
            integratedConsentRequestInFlight = true
            integratedConsentRequestGeneration
        }
        postIntegratedConsentUiRefresh()
        try {
            gatewaySessionExecutor.execute {
                try {
                    val confirmation = call.execute()
                    if (!claimIntegratedConsentCallCompletion(generation, call)) {
                        return@execute
                    }
                    if (
                        expectedClientRevision == null &&
                        integratedConsentClientRevision > 0L &&
                        (
                            confirmation == null ||
                                confirmation.clientRevision <
                                integratedConsentClientRevision ||
                                (
                                    confirmation.clientRevision ==
                                        integratedConsentClientRevision &&
                                    confirmation.selections != integratedConsentDraft
                                )
                        )
                    ) {
                        postIntegratedConsentUiRefresh()
                        if (pendingIntegratedConsentMutation != null) {
                            retryPendingIntegratedConsentMutation()
                        } else {
                            persistIntegratedConsentDraft(announce = false)
                        }
                        return@execute
                    }
                    val applied =
                        confirmation != null &&
                            (
                                expectedClientRevision == null ||
                                    confirmation.clientRevision == expectedClientRevision
                                ) &&
                            (
                                expectedSelections == null ||
                                    confirmation.selections == expectedSelections
                                ) &&
                            applyIntegratedConsentConfirmation(confirmation)
                    val appliedConfirmation = confirmation.takeIf { applied }
                    runOnUiThread {
                        if (
                            privacyStartupInspectionDestroyed ||
                            !accountDeletionStateMachine.activityLeaseIsCurrent(
                                accountDeletionActivityLease,
                            ) ||
                            synchronized(integratedConsentLock) {
                                generation != integratedConsentRequestGeneration
                            }
                        ) return@runOnUiThread
                        if (appliedConfirmation == null) {
                            updateIntegratedConsentUi()
                            if (announce) {
                                speakInteraction(
                                    "서버에서 현재 동의 선택을 확인하지 못해 기능을 열지 않습니다.",
                                )
                            }
                            return@runOnUiThread
                        }
                        if (completeOnboarding) {
                            completeFirstRunIntegratedConsentIfReady(
                                appliedConfirmation,
                            )
                        }
                        updateNavigationStatus(
                            "integratedConsent=confirmed " +
                                "version=${appliedConfirmation.policyVersion} " +
                                "revision=${appliedConfirmation.revision}",
                        )
                        if (announce) {
                            speakInteraction("네 가지 동의 선택을 서버에 저장하고 확인했습니다.")
                        }
                    }
                } catch (_: CancellationException) {
                    if (claimIntegratedConsentCallCompletion(generation, call)) {
                        postIntegratedConsentUiRefresh()
                    }
                } catch (_: Exception) {
                    val current = claimIntegratedConsentCallCompletion(generation, call)
                    if (!current) return@execute
                    if (pendingIntegratedConsentMutation != null) {
                        integratedConsentSession.markPendingRetry()
                    }
                    runOnUiThread {
                        if (
                            privacyStartupInspectionDestroyed ||
                            !accountDeletionStateMachine.activityLeaseIsCurrent(
                                accountDeletionActivityLease,
                            ) ||
                            synchronized(integratedConsentLock) {
                                generation != integratedConsentRequestGeneration
                            }
                        ) return@runOnUiThread
                        updateIntegratedConsentUi()
                        updateNavigationStatus(
                            "integratedConsent=blocked:server_confirmation_failed",
                        )
                        if (announce) {
                            speakInteraction("동의 선택 저장에 실패해 새 허용 항목을 열지 않습니다.")
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            call.cancel()
            claimIntegratedConsentCallCompletion(generation, call)
            postIntegratedConsentUiRefresh()
        }
    }

    private fun claimIntegratedConsentCallCompletion(
        generation: Long,
        call: CancellableNetworkCall<IntegratedConsentConfirmation?>,
    ): Boolean = synchronized(integratedConsentLock) {
        if (
            generation != integratedConsentRequestGeneration ||
            integratedConsentCall !== call
        ) {
            false
        } else {
            integratedConsentCall = null
            integratedConsentRequestInFlight = false
            true
        }
    }

    private fun applyIntegratedConsentConfirmation(
        confirmation: IntegratedConsentConfirmation,
    ): Boolean {
        if (sensitivePrefs.isBlocked()) return false
        if (confirmation.clientRevision < integratedConsentClientRevision) return false
        val pending = pendingIntegratedConsentMutation
        val exactPending =
            pending != null && pending.isExactNewerConfirmation(confirmation)
        val evaluation =
            if (exactPending) {
                integratedConsentSession.evaluateExactPendingConfirmation(confirmation)
            } else {
                integratedConsentSession.evaluateConfirmation(confirmation)
            }
        if (
            evaluation !in setOf(
                IntegratedConsentApplyResult.APPLIED,
                IntegratedConsentApplyResult.DUPLICATE,
            )
        ) {
            if (
                evaluation ==
                IntegratedConsentApplyResult.IDENTITY_CONFLICT_FAIL_CLOSED ||
                evaluation ==
                IntegratedConsentApplyResult.REVISION_CONFLICT_FAIL_CLOSED
            ) {
                integratedConsentSession.failClosed()
            }
            return false
        }
        if (
            !confirmation.selections.rawSourceCollection &&
            !stepLengthPrefs.edit()
                .putBoolean(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED, true)
                .commit()
        ) {
            integratedConsentSession.failClosed()
            return false
        }
        val editor = sensitivePrefs.edit()
            .putString(
                PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION,
                integratedConsentConfirmationJson(confirmation),
            )
            .putString(
                PREF_INTEGRATED_CONSENT_POLICY_VERSION,
                confirmation.policyVersion,
            )
            .putLong(PREF_INTEGRATED_CONSENT_REVISION, confirmation.revision)
            .putLong(
                PREF_INTEGRATED_CONSENT_CLIENT_REVISION,
                confirmation.clientRevision,
            )
            .putString(
                PREF_INTEGRATED_CONSENT_RECEIPT_SHA256,
                confirmation.receiptSha256,
            )
            .putBoolean(
                PREF_REPORT_PRIVACY_CONSENT_KEY,
                confirmation.selections.rawSourceCollection,
            )
            .putBoolean(
                PREF_AUTOMATIC_REPORT_CONSENT_KEY,
                confirmation.selections.automaticReporting,
            )
            .putString(
                PREF_MOBILE_NETWORK_PREFERENCE_KEY,
                if (confirmation.selections.mobileNetworkTransfer) {
                    MobileNetworkPreference.ALLOW_CELLULAR.wireValue
                } else {
                    MobileNetworkPreference.WIFI_ONLY.wireValue
                },
            )
            .putBoolean(
                PREF_TRAINING_REUSE_CONSENT_KEY,
                confirmation.selections.trainingReuse,
            )
        if (exactPending) editor.remove(PREF_PENDING_INTEGRATED_CONSENT_MUTATION)
        if (!editor.commit()) {
            integratedConsentSession.failClosed()
            return false
        }
        if (
            confirmation.selections.rawSourceCollection &&
            exactPending &&
            !stepLengthPrefs.edit().remove(PREF_RAW_SOURCE_FIELD_LOG_BLOCKED).commit()
        ) {
            integratedConsentSession.failClosed()
            return false
        }
        if (exactPending) {
            check(integratedConsentSession.applyExactPendingConfirmation(confirmation))
            pendingIntegratedConsentMutation = null
        } else {
            val applied = integratedConsentSession.applyConfirmation(confirmation)
            if (
                applied != IntegratedConsentApplyResult.APPLIED &&
                applied != IntegratedConsentApplyResult.DUPLICATE
            ) return false
        }
        integratedConsentClientRevision = confirmation.clientRevision
        if (exactPending && confirmation.selections.rawSourceCollection) {
            fieldSessionLog.resetRawSourceAfterConfirmedConsent()
        }
        val effectiveSelections = if (pending != null && !exactPending) {
            pending.withdrawalItems.fold(confirmation.selections) { selections, item ->
                selections.withDecision(item, false)
            }
        } else {
            confirmation.selections
        }
        if (
            !effectiveSelections.rawSourceCollection ||
            !effectiveSelections.automaticReporting
        ) {
            scheduleLegacyPendingReportQueuePurge()
        }
        if (!effectiveSelections.rawSourceCollection) {
            fieldSessionLog.blockForRawSourceWithdrawal()
        }
        synchronized(reportUploadSafetyLock) {
            reportUploadSafetyGeneration += 1L
            automaticReportUploadSafetyGeneration += 1L
            reportPrivacyConsentSession.cancelActiveCalls()
            if (effectiveSelections.rawSourceCollection) {
                reportPrivacyConsentSession.grantFromServerConfirmedIntegratedConsent()
            } else {
                reportPrivacyConsentSession.withdraw()
            }
        }
        permissionSessionPolicy.applyIntegratedConsentSelections(
            effectiveSelections,
        )
        integratedConsentDraft =
            if (pending != null && !exactPending) pending.desiredSelections
            else confirmation.selections
        if (
            !effectiveSelections.rawSourceCollection &&
            ::metadataLogUploader.isInitialized
        ) {
            metadataLogUploader.setEnabled(false)
        }
        postIntegratedConsentUiRefresh()
        return true
    }

    private fun persistedIntegratedConsentSelections(): IntegratedConsentSelections =
        if (
            !sensitivePrefs.isBlocked() &&
            sensitivePrefs.getString(PREF_INTEGRATED_CONSENT_POLICY_VERSION, null) ==
            INTEGRATED_CONSENT_POLICY_VERSION
        ) {
            IntegratedConsentSelections(
                rawSourceCollection =
                    sensitivePrefs.getBoolean(PREF_REPORT_PRIVACY_CONSENT_KEY, false),
                automaticReporting =
                    sensitivePrefs.getBoolean(PREF_AUTOMATIC_REPORT_CONSENT_KEY, false),
                mobileNetworkTransfer =
                    MobileNetworkPreference.fromWireValue(
                        sensitivePrefs.getString(PREF_MOBILE_NETWORK_PREFERENCE_KEY, null),
                    ) == MobileNetworkPreference.ALLOW_CELLULAR,
                trainingReuse =
                    sensitivePrefs.getBoolean(PREF_TRAINING_REUSE_CONSENT_KEY, false),
            )
        } else {
            IntegratedConsentSelections()
        }

    private fun completeFirstRunIntegratedConsentIfReady(
        confirmation: IntegratedConsentConfirmation,
    ): Boolean {
        if (
            firstRunOnboardingSnapshot.stage !=
            FirstRunOnboardingStage.INTEGRATED_CONSENT ||
            integratedConsentSession.currentConfirmationOrNull() != confirmation
        ) return false
        val started = FirstRunOnboardingPolicy.beginAttempt(
            snapshot = firstRunOnboardingSnapshot,
            request = firstRunLocalRequest("integrated_consent"),
        )
        val token = started.current.pendingAttempt ?: return false
        val evidence = FirstRunOnboardingEvidence.IntegratedConsent(
            FirstRunReceiptHash.fromSha256Hex(confirmation.receiptSha256),
        )
        val completed = FirstRunOnboardingPolicy.completeAttempt(
            snapshot = started.current,
            token = token,
            evidence = evidence,
            verifier = FirstRunOnboardingEvidenceVerifier { presentedToken, presentedEvidence ->
                presentedToken === token &&
                    presentedEvidence === evidence &&
                    integratedConsentSession.currentConfirmationOrNull() == confirmation
            },
        )
        if (!completed.accepted) return false
        firstRunOnboardingSnapshot = completed.current
        onFirstRunOnboardingStateChanged(
            "네 가지 동의 선택의 서버 저장을 확인했습니다. 가입 검증 단계로 이동합니다.",
        )
        return true
    }

    private fun cancelActivityOriginalUploads() {
        if (::metadataLogUploader.isInitialized) {
            metadataLogUploader.cancelActiveUpload()
        }
        if (::frameCaptureUploader.isInitialized) {
            frameCaptureUploader.cancelActiveUpload()
        }
    }

    private fun sensitiveDebugTransferAllowed(): Boolean {
        if (
            !integratedConsentSession.isAllowed(
                IntegratedConsentItem.RAW_SOURCE_COLLECTION,
            ) ||
            !::networkStateProbe.isInitialized
        ) return false
        return activityOriginalUploadAdmission.isAllowed(
            consentAllowed = true,
            preference = permissionSessionPolicy.snapshot().mobileNetworkPreference,
            transport = networkStateProbe.currentTransport(),
            observedAtMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun runIfActivityOriginalUploadAllowed(action: () -> Unit): Boolean {
        if (!::networkStateProbe.isInitialized) return false
        return activityOriginalUploadAdmission.admit(
            consentAllowed =
                integratedConsentSession.isAllowed(
                    IntegratedConsentItem.RAW_SOURCE_COLLECTION,
                ),
            preference = permissionSessionPolicy.snapshot().mobileNetworkPreference,
            transport = networkStateProbe.currentTransport(),
            observedAtMs = SystemClock.elapsedRealtime(),
            action = action,
        )
    }

    private fun isGatewayNetworkAllowed(
        reason: String,
        announce: Boolean = true,
    ): Boolean {
        val transport = if (::networkStateProbe.isInitialized) {
            networkStateProbe.currentTransport()
        } else {
            ActiveNetworkTransport.OFFLINE
        }
        val preference = permissionSessionPolicy.snapshot().mobileNetworkPreference
        if (AndroidNetworkTransferPolicy.isAllowed(preference, transport)) return true
        val publishBlockedState = {
            updateNavigationStatus(
                "network=blocked reason=$reason transport=${transport.name.lowercase(Locale.US)} " +
                    "preference=${preference.wireValue}",
            )
            if (announce) {
                speakInteraction(
                    if (transport == ActiveNetworkTransport.CELLULAR) {
                        "이동통신망 사용이 꺼져 있어 서버 기능을 시작하지 않았습니다."
                    } else {
                        "인터넷 연결을 확인한 뒤 다시 시도하세요."
                    },
                )
            }
        }
        if (!::surfaceView.isInitialized || Looper.myLooper() == Looper.getMainLooper()) {
            publishBlockedState()
        } else {
            runOnUiThread { publishBlockedState() }
        }
        return false
    }

    private fun openPrivacyRightsPage() {
        val origin = gatewayFieldSession?.gatewayBaseUrl ?: if (BuildConfig.DEBUG) {
            GatewayEndpointPolicy.debugOriginOrNull(backendUrlInput.text?.toString())
        } else {
            GatewayEndpointPolicy.approvedReleaseOriginOrNull(
                raw = BuildConfig.WALKSAFE_GATEWAY_ORIGIN,
                approvedOrigin = BuildConfig.WALKSAFE_GATEWAY_ORIGIN,
            )
        }
        if (origin == null) {
            updateNavigationStatus("privacyRights=unavailable gateway_origin_invalid")
            speakInteraction("개인정보 권리 요청 주소를 확인할 수 없습니다.")
            return
        }
        try {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("$origin/privacy/rights"),
                ),
            )
            updateNavigationStatus("privacyRights=opened external_no_login")
        } catch (_: ActivityNotFoundException) {
            updateNavigationStatus("privacyRights=unavailable no_browser")
            speakInteraction("웹 브라우저를 열 수 없습니다.")
        }
    }

    private fun onGatewaySessionButtonClicked() {
        val expectedActivityLease = accountDeletionActivityLease
        val processSnapshot = GatewaySessionProcessCoordinator.snapshot()
        val deletionRecoverySurface =
            accountDeletionRecoveryLoginRequired() ||
                processSnapshot.deletionRecoveryOnly
        if (!BuildConfig.DEBUG && !deletionRecoverySurface) return
        if (processSnapshot.deletionRecoveryOnly) {
            if (
                accountDeletionStateMachine.processingBlocked() &&
                !accountDeletionRecoveryLoginRequired()
            ) {
                updateNavigationStatus("gateway=account_deletion_session_reserved")
                speakInteraction("계정 삭제 처리 전용 로그인이 유지되고 있습니다.")
                return
            }
            if (
                accountDeletionRecoveryGatewaySessionOrNull(
                    processSnapshot.session,
                ) != null
            ) {
                updateNavigationStatus("gateway=deletion_recovery_session_ready")
                speakInteraction("삭제 요청 복구 로그인이 유지되고 있습니다.")
                return
            }
            if (
                GatewaySessionProcessCoordinator.clear(
                    processSnapshot.generation,
                ) == null
            ) return
        }
        val currentSession = gatewayFieldSession
        if (currentSession != null) {
            updateNavigationStatus("gateway=remote_revoke_pending")
            speakInteraction("로컬 로그아웃을 완료했습니다. 서버 해제를 확인 중입니다.")
            clearGatewaySession(
                logoutRemote = true,
                expectedSession = currentSession,
                remoteLogoutResult = { confirmed ->
                    if (confirmed) {
                        updateNavigationStatus("gateway=remote_revoke_confirmed")
                        speakInteraction("서버의 기기 로그인 해제를 확인했습니다.")
                    } else {
                        updateNavigationStatus("gateway=remote_revoke_unconfirmed")
                        speakInteraction("로컬 로그아웃은 완료했지만 서버 해제는 확인하지 못했습니다.")
                    }
                },
                expectedActivityLease = expectedActivityLease,
            )
            return
        }
        if (!isGatewayNetworkAllowed(reason = "gateway_login")) return
        val baseUrl = if (BuildConfig.DEBUG) {
            GatewayEndpointPolicy.debugOriginOrNull(backendUrlInput.text?.toString())
        } else {
            GatewayEndpointPolicy.approvedReleaseOriginOrNull(
                raw = BuildConfig.WALKSAFE_GATEWAY_ORIGIN,
                approvedOrigin = BuildConfig.WALKSAFE_GATEWAY_ORIGIN,
            )
        }
        val actorId = gatewayLoginActorIdOrNull()
        val token = if (::backendFieldTokenInput.isInitialized) {
            backendFieldTokenInput.text?.toString()
        } else {
            null
        }
        if (::backendFieldTokenInput.isInitialized) {
            backendFieldTokenInput.text?.clear()
        }
        if (baseUrl == null || actorId == null || GatewayCredentialPolicy.normalizedTokenOrNull(token) == null) {
            updateBackendAuthButtonText()
            updateNavigationStatus("gateway=login_input_invalid named_actor_and_account_token_required")
            speakInteraction("게이트웨이 주소, 로그인 아이디와 계정 토큰을 확인하세요.")
            return
        }
        val deletionRecoveryTarget =
            isAccountDeletionRecoveryLoginTarget(actorId, baseUrl)
        val operation = GatewaySessionProcessCoordinator.beginOperation() ?: return
        val deviceId = if (
            BuildConfig.WALKSAFE_LONG_LIVED_LOGIN_ENABLED &&
            !deletionRecoveryTarget
        ) {
            gatewaySessionStore.getOrCreateInstallDeviceId()
        } else {
            null
        }
        if (
            BuildConfig.WALKSAFE_LONG_LIVED_LOGIN_ENABLED &&
            !deletionRecoveryTarget &&
            deviceId == null
        ) {
            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            updateBackendAuthButtonText()
            updateNavigationStatus("gateway=login_failed storage_blocked")
            speakInteraction("기기 로그인 저장소를 사용할 수 없어 로그인하지 않았습니다.")
            return
        }
        val feedbackGeneration = feedbackLifecycleGeneration
        updateBackendAuthButtonText()
        updateNavigationStatus(
            if (deletionRecoveryTarget) {
                "gateway=login_pending purpose=account_deletion_recovery"
            } else {
                "gateway=login_pending purpose=general"
            },
        )
        try {
            gatewaySessionExecutor.execute {
                if (
                    !gatewayActivityCallbackAllowed(expectedActivityLease) ||
                    GatewaySessionProcessCoordinator.snapshot()
                        .inFlightOperationId != operation.operationId
                ) {
                    GatewaySessionProcessCoordinator.clear(operation)
                    return@execute
                }
                try {
                    val session = gatewaySessionClient.login(
                        gatewayBaseUrl = baseUrl,
                        actorId = actorId,
                        token = token,
                        enableLongLivedSession =
                            BuildConfig.WALKSAFE_LONG_LIVED_LOGIN_ENABLED &&
                                !deletionRecoveryTarget,
                        deviceId = deviceId,
                        sessionScope =
                            if (deletionRecoveryTarget) {
                                GatewaySessionScope.ACCOUNT_DELETION_RECOVERY
                            } else {
                                GatewaySessionScope.GENERAL
                            },
                    )
                    if (!gatewayActivityCallbackAllowed(expectedActivityLease)) {
                        runCatching { gatewaySessionClient.logout(session) }
                        GatewaySessionProcessCoordinator.clear(operation)
                        return@execute
                    }
                    val deletionRecoveryLogin =
                        isAccountDeletionRecoveryLoginTarget(
                            actorId = session.actorId,
                            gatewayBaseUrl = session.gatewayBaseUrl,
                        )
                    val boundToCurrentProfile =
                        gatewayActivityCallbackAllowed(expectedActivityLease) &&
                            GatewaySessionProcessCoordinator.snapshot()
                            .inFlightOperationId == operation.operationId &&
                            gatewayLoginActorIdOrNull() == session.actorId &&
                            (
                                deletionRecoveryLogin ||
                                    priorityUserOnboardingActorId == session.actorId
                                )
                    if (!boundToCurrentProfile) {
                        runCatching { gatewaySessionClient.logout(session) }
                        GatewaySessionProcessCoordinator.clear(operation)
                        return@execute
                    }
                    val accepted = commitLoggedInGatewaySession(
                        session = session,
                        operation = operation,
                        expectedGatewayBaseUrl = baseUrl,
                        expectedActorId = actorId,
                        expectedActivityLease = expectedActivityLease,
                    )
                    if (!accepted) {
                        runCatching { gatewaySessionClient.logout(session) }
                        GatewaySessionProcessCoordinator.clear(operation)
                        return@execute
                    }
                    if (!deletionRecoveryLogin) {
                        stepLengthPrefs.edit()
                            .putString(PREF_GATEWAY_ORIGIN_KEY, session.gatewayBaseUrl)
                            .apply()
                        reportAttemptStore.resetFailures()
                    }
                    postGatewayActivityCallback(expectedActivityLease) {
                        if (
                            !isCurrentGatewaySession(session) &&
                            accountDeletionRecoveryGatewaySessionOrNull(session) !== session
                        ) return@postGatewayActivityCallback
                        updateReportPrivacyConsentUi()
                        updateAccountDeletionUi()
                        updateNavigationStatus(
                            if (deletionRecoveryLogin) {
                                "gateway=session_ready purpose=account_deletion_recovery"
                            } else {
                                "gateway=session_ready purpose=general"
                            },
                        )
                        if (isFeedbackLifecycleCurrent(feedbackGeneration)) {
                            speakInteraction("현장 게이트웨이 로그인이 완료되었습니다.")
                        }
                    }
                } catch (error: GatewaySessionHttpException) {
                    val attemptCurrent =
                        gatewayActivityCallbackAllowed(expectedActivityLease) &&
                            GatewaySessionProcessCoordinator.snapshot()
                            .inFlightOperationId == operation.operationId &&
                            gatewayLoginActorIdOrNull() == actorId
                    GatewaySessionProcessCoordinator.clear(operation)
                    postGatewayActivityCallback(expectedActivityLease) {
                        if (!attemptCurrent) return@postGatewayActivityCallback
                        updateNavigationStatus("gateway=login_failed status=${error.statusCode} reason=${error.reason}")
                        if (isFeedbackLifecycleCurrent(feedbackGeneration)) {
                            speakInteraction("현장 게이트웨이 로그인에 실패했습니다.")
                        }
                    }
                } catch (_: Exception) {
                    val attemptCurrent =
                        gatewayActivityCallbackAllowed(expectedActivityLease) &&
                            GatewaySessionProcessCoordinator.snapshot()
                            .inFlightOperationId == operation.operationId &&
                            gatewayLoginActorIdOrNull() == actorId
                    GatewaySessionProcessCoordinator.clear(operation)
                    postGatewayActivityCallback(expectedActivityLease) {
                        if (!attemptCurrent) return@postGatewayActivityCallback
                        updateNavigationStatus("gateway=login_failed network")
                        if (isFeedbackLifecycleCurrent(feedbackGeneration)) {
                            speakInteraction("현장 게이트웨이 로그인에 실패했습니다.")
                        }
                    }
                } finally {
                    postGatewayActivityCallback(expectedActivityLease) {
                        updateBackendAuthButtonText()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            GatewaySessionProcessCoordinator.clear(operation)
            if (gatewayActivityCallbackAllowed(expectedActivityLease)) {
                updateBackendAuthButtonText()
                updateNavigationStatus("gateway=login_failed executor_rejected")
            }
        }
    }

    private fun renewRestoredGatewaySession(
        restored: RestoredGatewayLoginBundle,
        operation: GatewaySessionOperation,
    ) {
        val expectedActivityLease = accountDeletionActivityLease
        val reserved = gatewaySessionStore.reserveRenewal(
            expectedVersion = restored.version,
            operationId = operation.operationId,
        )
        if (
            reserved != GatewaySessionStoreResult.COMMITTED &&
            reserved != GatewaySessionStoreResult.ALREADY_COMMITTED
        ) {
            if (
                reserved == GatewaySessionStoreResult.BLOCKED ||
                reserved == GatewaySessionStoreResult.STORAGE_FAILURE
            ) {
                GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            } else {
                GatewaySessionProcessCoordinator.clear(operation)
            }
            return
        }
        try {
            gatewaySessionExecutor.execute {
                try {
                    val renewal = gatewaySessionClient.renew(
                        session = restored.session,
                        actorId = restored.session.actorId,
                    )
                    if (renewal.sourceLease != restored.session.lease) {
                        renewal.session.invalidate()
                        throw IllegalStateException("gateway_session_renewal_lease_mismatch")
                    }
                    val stored = gatewaySessionStore.commitRenewal(
                        expectedVersion = restored.version,
                        operationId = operation.operationId,
                        renewedSession = renewal.session,
                        firstRunSnapshot = restored.firstRunSnapshot,
                    )
                    if (stored != GatewaySessionStoreResult.COMMITTED) {
                        runCatching { gatewaySessionClient.logout(renewal.session) }
                        if (stored == GatewaySessionStoreResult.STORAGE_FAILURE) {
                            GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                        } else {
                            GatewaySessionProcessCoordinator.clear(operation)
                        }
                        return@execute
                    }
                    val published = GatewaySessionProcessCoordinator.publishVerified(
                        operation = operation,
                        session = renewal.session,
                        firstRunSnapshot = restored.firstRunSnapshot,
                    )
                    if (!published) {
                        runCatching { gatewaySessionClient.logout(renewal.session) }
                        return@execute
                    }
                    postGatewayActivityCallback(expectedActivityLease) {
                        if (!isCurrentGatewaySession(renewal.session)) {
                            return@postGatewayActivityCallback
                        }
                        updateBackendAuthButtonText()
                        updateReportPrivacyConsentUi()
                        updateNavigationStatus(
                            "gateway=session_restored_verified purpose=general",
                        )
                    }
                } catch (error: RuntimeException) {
                    if (
                        error is GatewaySessionHttpException &&
                        error.reason == "gateway_refresh_proof_already_attempted"
                    ) {
                        return@execute
                    }
                    val abandoned = gatewaySessionStore.abandonRenewal(
                        expectedVersion = restored.version,
                        operationId = operation.operationId,
                    )
                    val pending = gatewaySessionStore.restorePendingRevocation(
                        restored.version.gatewayBaseUrl,
                    )
                    val pendingOperation =
                        GatewaySessionProcessCoordinator.publishPendingRevocation(operation)
                    if (
                        abandoned == GatewaySessionStoreResult.COMMITTED &&
                        pending != null &&
                        pendingOperation != null
                    ) {
                        drainPendingGatewayRevocation(pending, pendingOperation)
                    } else if (pendingOperation != null) {
                        GatewaySessionProcessCoordinator.markStorageBlocked(pendingOperation)
                    }
                    if (
                        error is GatewaySessionHttpException &&
                        error.serverCode == "refresh_token_reuse_detected"
                    ) {
                        postGatewayActivityCallback(expectedActivityLease) {
                            updateNavigationStatus("gateway=refresh_reuse_detected reauthentication_required")
                            speakInteraction("기기 로그인 재사용이 감지되어 다시 로그인해야 합니다.")
                        }
                    }
                } finally {
                    postGatewayActivityCallback(expectedActivityLease) {
                        updateBackendAuthButtonText()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            val abandoned = gatewaySessionStore.abandonRenewal(
                expectedVersion = restored.version,
                operationId = operation.operationId,
            )
            if (abandoned == GatewaySessionStoreResult.COMMITTED) {
                GatewaySessionProcessCoordinator.publishPendingRevocation(operation)
                    ?.let(GatewaySessionProcessCoordinator::clear)
            } else {
                GatewaySessionProcessCoordinator.markStorageBlocked(operation)
            }
        }
    }

    private fun commitLoggedInGatewaySession(
        session: GatewayFieldSession,
        operation: GatewaySessionOperation,
        expectedGatewayBaseUrl: String,
        expectedActorId: String,
        expectedActivityLease: AccountDeletionActivityLease,
    ): Boolean {
        if (!gatewayActivityCallbackAllowed(expectedActivityLease)) return false
        val actorId = gatewayLoginActorIdOrNull()
        val deletionRecoveryLogin =
            isAccountDeletionRecoveryLoginTarget(
                actorId = expectedActorId,
                gatewayBaseUrl = expectedGatewayBaseUrl,
            )
        if (
            GatewaySessionProcessCoordinator.snapshot().inFlightOperationId !=
            operation.operationId ||
            session.verificationState != GatewaySessionVerificationState.VERIFIED ||
            !session.isUsableFor(actorId) ||
            actorId != session.actorId ||
            actorId != expectedActorId ||
            session.gatewayBaseUrl != expectedGatewayBaseUrl ||
            (!deletionRecoveryLogin && priorityUserOnboardingActorId != session.actorId)
        ) {
            return false
        }
        if (deletionRecoveryLogin) {
            if (!gatewayActivityCallbackAllowed(expectedActivityLease)) return false
            return GatewaySessionProcessCoordinator.publishDeletionRecoveryVerified(
                operation = operation,
                session = session,
                expectedActorId = expectedActorId,
                expectedGatewayBaseUrl = expectedGatewayBaseUrl,
            )
        }
        if (BuildConfig.WALKSAFE_LONG_LIVED_LOGIN_ENABLED) {
            val stored = gatewaySessionStore.saveInitialIfAbsent(
                session = session,
                firstRunSnapshot = firstRunOnboardingSnapshot,
                expectedGatewayBaseUrl = expectedGatewayBaseUrl,
            )
            if (
                stored != GatewaySessionStoreResult.COMMITTED &&
                stored != GatewaySessionStoreResult.ALREADY_COMMITTED
            ) {
                if (stored == GatewaySessionStoreResult.STORAGE_FAILURE) {
                    GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                } else {
                    GatewaySessionProcessCoordinator.clear(operation)
                }
                session.invalidate()
                handleGatewaySessionDowngrade(
                    "gateway_session_storage_blocked",
                    expectedActivityLease,
                )
                return false
            }
        }
        if (!gatewayActivityCallbackAllowed(expectedActivityLease)) return false
        val published = GatewaySessionProcessCoordinator.publishVerified(
            operation = operation,
            session = session,
            firstRunSnapshot = firstRunOnboardingSnapshot,
        )
        if (!published) {
            handleGatewaySessionDowngrade(
                "gateway_session_storage_blocked",
                expectedActivityLease,
            )
        }
        return published
    }

    private fun clearGatewaySession(
        logoutRemote: Boolean,
        expectedSession: GatewayFieldSession? = null,
        remoteLogoutResult: ((Boolean) -> Unit)? = null,
        expectedActivityLease: AccountDeletionActivityLease =
            accountDeletionActivityLease,
    ): Long? {
        val before = GatewaySessionProcessCoordinator.snapshot()
        if (
            before.deletionRecoveryOnly &&
            accountDeletionStateMachine.durableConfirmationRecoveryRequired()
        ) return null
        val previous = before.session
        if (expectedSession != null && previous !== expectedSession) return null
        val shouldDowngradeWalk =
            previous != null || permissionSessionPolicy.snapshot().mayUseProtectedServerFeature
        val operation = GatewaySessionProcessCoordinator.beginOperation() ?: return null
        val version = previous?.versionOrNull
        val pending = if (version != null && ::gatewaySessionStore.isInitialized) {
            val staged = gatewaySessionStore.moveActiveToPendingRevocation(
                expectedVersion = version,
                operationId = operation.operationId,
            )
            if (
                staged != GatewaySessionStoreResult.COMMITTED &&
                staged != GatewaySessionStoreResult.ALREADY_COMMITTED
            ) {
                GatewaySessionProcessCoordinator.markStorageBlocked(operation)
                if (logoutRemote) {
                    try {
                        gatewaySessionExecutor.execute {
                            val confirmed = runCatching {
                                gatewaySessionClient.logout(previous)
                                true
                            }.getOrDefault(false)
                            if (remoteLogoutResult != null) {
                                postGatewayActivityCallback(expectedActivityLease) {
                                    remoteLogoutResult(confirmed)
                                }
                            }
                        }
                    } catch (_: RejectedExecutionException) {
                        if (remoteLogoutResult != null) {
                            postGatewayActivityCallback(expectedActivityLease) {
                                remoteLogoutResult(false)
                            }
                        }
                    }
                }
                return GatewaySessionProcessCoordinator.snapshot().generation
            }
            gatewaySessionStore.restorePendingRevocation(version.gatewayBaseUrl)
        } else {
            null
        }
        val pendingOperation =
            GatewaySessionProcessCoordinator.publishPendingRevocation(operation)
                ?: return null
        permissionSessionPolicy.authenticationExpired()
        if (shouldDowngradeWalk) {
            handleGatewaySessionDowngrade(
                "gateway_session_cleared",
                expectedActivityLease,
            )
        }
        synchronized(reportUploadSafetyLock) {
            reportUploadSafetyGeneration += 1L
            reportPrivacyConsentSession.cancelActiveCalls()
        }
        cancelGatewayNetworkCalls()
        val clearCredentialUi = {
            if (::backendFieldTokenInput.isInitialized) backendFieldTokenInput.text?.clear()
            clearGatewayNavigationState()
            updateBackendAuthButtonText()
            updateReportPrivacyConsentUi()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (gatewayActivityCallbackAllowed(expectedActivityLease)) {
                clearCredentialUi()
            }
        } else {
            postGatewayActivityCallback(expectedActivityLease, clearCredentialUi)
        }
        if (!logoutRemote) {
            GatewaySessionProcessCoordinator.clear(pendingOperation)
            return pendingOperation.generation
        }
        if (pending != null) {
            drainPendingGatewayRevocation(
                pending = pending,
                operation = pendingOperation,
                result = remoteLogoutResult,
                expectedActivityLease = expectedActivityLease,
            )
            return pendingOperation.generation
        }
        if (previous == null) {
            GatewaySessionProcessCoordinator.clear(pendingOperation)
            if (logoutRemote && remoteLogoutResult != null) {
                postGatewayActivityCallback(expectedActivityLease) {
                    remoteLogoutResult(false)
                }
            }
            return pendingOperation.generation
        }
        try {
            gatewaySessionExecutor.execute {
                val confirmed = runCatching {
                    gatewaySessionClient.logout(previous)
                    true
                }.getOrDefault(false)
                GatewaySessionProcessCoordinator.clear(pendingOperation)
                if (remoteLogoutResult != null) {
                    postGatewayActivityCallback(expectedActivityLease) {
                        remoteLogoutResult(confirmed)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            GatewaySessionProcessCoordinator.clear(pendingOperation)
            if (remoteLogoutResult != null) {
                postGatewayActivityCallback(expectedActivityLease) {
                    remoteLogoutResult(false)
                }
            }
        }
        return pendingOperation.generation
    }

    private fun handleGatewaySessionDowngrade(
        reason: String,
        expectedActivityLease: AccountDeletionActivityLease =
            accountDeletionActivityLease,
    ) {
        val downgrade = downgrade@{
            if (!gatewayActivityCallbackAllowed(expectedActivityLease)) {
                return@downgrade
            }
            walkSessionResumeConfirmationToken = null
            if (::walkSessionLifecycle.isInitialized) {
                when (walkSessionLifecycle.snapshot().state) {
                    WalkSessionState.ACTIVE ->
                        enterWalkSessionSafetyStopAndCancelOutputs(reason)
                    WalkSessionState.READY,
                    WalkSessionState.PAUSED ->
                        enterWalkSessionForegroundRecheckAndCancelOutputs(reason)
                    WalkSessionState.SAFE_STOP,
                    WalkSessionState.ENDED -> Unit
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (gatewayActivityCallbackAllowed(expectedActivityLease)) downgrade()
        } else {
            postGatewayActivityCallback(expectedActivityLease, downgrade)
        }
    }

    internal fun <T> trackRouteRequest(call: CancellableNetworkCall<T>): CancellableNetworkCall<T> {
        return navigationRequests.trackRoute(call)
    }

    internal fun <T> trackDestinationSearchRequest(
        call: CancellableNetworkCall<T>,
    ): CancellableNetworkCall<T> {
        return navigationRequests.trackDestinationSearch(call)
    }

    internal fun completeRouteRequest(call: CancellableNetworkCall<*>) {
        navigationRequests.completeRoute(call)
    }

    internal fun completeDestinationSearchRequest(call: CancellableNetworkCall<*>) {
        navigationRequests.completeDestinationSearch(call)
    }

    private fun handleNavigationRequestEvent(
        event: AndroidNavigationRequestEvent,
    ): AndroidNavigationCancellation {
        return navigationRequests.handle(event)
    }

    internal fun cancelNavigationRequestsForDestinationSelection(): AndroidNavigationCancellation {
        return handleNavigationRequestEvent(AndroidNavigationRequestEvent.DESTINATION_SELECTED)
    }

    internal fun cancelNavigationRequestsForPause(): AndroidNavigationCancellation {
        return handleNavigationRequestEvent(AndroidNavigationRequestEvent.ACTIVITY_PAUSED)
    }

    internal fun cancelNavigationRequestsForDestroy(): AndroidNavigationCancellation {
        return handleNavigationRequestEvent(AndroidNavigationRequestEvent.ACTIVITY_DESTROYED)
    }

    private fun cancelGatewayNetworkCalls() {
        handleNavigationRequestEvent(AndroidNavigationRequestEvent.GATEWAY_SESSION_CLEARED)
    }

    private fun clearGatewayNavigationState() {
        routeRequestGeneration += 1
        routeRequestInFlight.set(false)
        destinationSearchGeneration += 1
        destinationSearchInFlight = false
        pendingVoiceDestinationQuery = null
        pendingVoiceDestinationPageIndex = null
        destinationSearchVoiceState = null
        isRouteActive = false
        latestTmapOnRoute = false
        latestTactileRouteState = "localRoute=tmap:navigation_inactive"
        currentDestination = null
        navigationPermissionsRequestedForRoute = false
        routeNavigator.clear()
        routeStartStepCount = null
        destinationSearchResults.clear()
        updateDestinationSearchUi()
        updateRouteButtonText()
    }

    private fun isGatewaySessionReadyForCurrentActor(
        session: GatewayFieldSession? = gatewayFieldSession,
    ): Boolean {
        val actorId = currentReporterUserId()
        val processSnapshot = GatewaySessionProcessCoordinator.snapshot()
        return !processSnapshot.storageBlocked &&
            session != null &&
            processSnapshot.session === session &&
            session.verificationState == GatewaySessionVerificationState.VERIFIED &&
            session.isUsableFor(actorId) &&
            permissionSessionPolicy.isAuthenticatedFor(actorId)
    }

    private fun isCurrentGatewaySession(session: GatewayFieldSession): Boolean =
        isGatewaySessionReadyForCurrentActor(session)

    private fun gatewayFailureUiGuardOrNull(
        session: GatewayFieldSession,
        expectedAuthenticationFailure: Boolean,
    ): GatewayFailureUiGuard? {
        if (expectedAuthenticationFailure) {
            val clearedGeneration = clearGatewaySession(
                logoutRemote = false,
                expectedSession = session,
            ) ?: return null
            return GatewayFailureUiGuard(
                generation = clearedGeneration,
                expectedAuthenticationFailure = true,
            )
        }
        return if (!isGatewaySessionReadyForCurrentActor(session)) {
            null
        } else {
            GatewayFailureUiGuard(
                generation = gatewaySessionGeneration,
                expectedAuthenticationFailure = false,
            )
        }
    }

    private fun isGatewayFailureUiGuardCurrent(
        session: GatewayFieldSession,
        guard: GatewayFailureUiGuard,
    ): Boolean {
        return if (gatewaySessionGeneration != guard.generation) {
            false
        } else if (guard.expectedAuthenticationFailure) {
            gatewayFieldSession == null
        } else {
            isGatewaySessionReadyForCurrentActor(session)
        }
    }

    private fun prepareReportMetadataContext() {
        reportApkSha256 = computeApkSha256()
        reportRuntimeConfig = loadReportRuntimeConfig()
    }

    private fun loadReportRuntimeConfig(): TwoModelRuntimeConfig? {
        return try {
            val runtimeJson = assets.open("model-config/two_model_runtime.json").bufferedReader().use { it.readText() }
            reportModelConfigSha256 = sha256Hex(runtimeJson.toByteArray())
            TwoModelRuntimeConfig.load(this)
        } catch (_: Exception) {
            null
        }
    }

    private fun computeApkSha256(): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(applicationInfo.sourceDir).use { inputStream ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = inputStream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
            hexEncoded(digest.digest())
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        return hexEncoded(MessageDigest.getInstance("SHA-256").digest(bytes))
    }

    private fun hexEncoded(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun resolveReportSourceModel(modelKey: String?): String? {
        val runtimeSourceModel = reportRuntimeConfig?.sourceModelForReportModel(modelKey)
        return runtimeSourceModel ?: modelKey?.let { "android/$it" }
    }

    private fun resolveReportThreshold(modelKey: String?, className: String): Float? {
        return reportRuntimeConfig?.thresholdForReportClass(modelKey, className)
    }

    override fun onResume() {
        super.onResume()
        feedbackLifecycleGeneration += 1
        isActivityForeground = true
        if (!privacyStartupInspectionComplete) {
            completePrivacyStartupReadyIfForeground()
            return
        }
        resumeWalkSafeRuntimeAfterPrivacyStartupInspection()
    }

    private fun resumeWalkSafeRuntimeAfterPrivacyStartupInspection() {
        if (permissionRecoveryGate.blocksAutomaticResourceStart) {
            permissionRecoveryGate = permissionRecoveryGate.recheckRequired()
            persistPermissionRecoveryGate()
        }
        handleWalkSessionForegroundReturn()
        revalidateGatewayWalkAfterForegroundReturn()
        applyObservedPermissionStateChange("app_resumed")
        syncActiveSessionScreenPolicy()
        refreshStartupCapabilityUi()
        updateIntegratedConsentUi()
        updateReportPrivacyConsentUi()
        completePermissionRecoveryRecheckIfPossible()
        if (::fieldSessionLog.isInitialized) fieldSessionLog.recordEvent("app_resumed")
        if (
            session == null &&
            actionMode == ActionMode.OPEN_SETTINGS &&
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        ) {
            setActionButton("ARCore Depth 시작", ActionMode.START, enabled = true)
            updateStatus("ARCore Depth 대기", "카메라 권한이 허용되었습니다. ARCore Depth를 시작할 수 있습니다.")
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        walkBackDispatcher.onBackPressed()
    }

    private fun handleWalkScreenBackPressed(): Boolean {
        val state = walkSessionLifecycle.snapshot().state
        if (state != WalkSessionState.ACTIVE && state != WalkSessionState.PAUSED) return false
        enterWalkSessionForegroundRecheckAndCancelOutputs("system_back_exit_confirmation")
        walkSessionResumeRetryRequiresUserAction = true
        syncActiveSessionScreenPolicy()
        showWalkExitConfirmationDialog()
        return true
    }

    private fun showWalkExitConfirmationDialog() {
        if (walkExitConfirmationDialog?.isShowing == true) return
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.walk_exit_confirmation_title)
            .setMessage(R.string.walk_exit_confirmation_message)
            .setPositiveButton(R.string.walk_exit_confirmation_positive) { _, _ ->
                transitionWalkSession(WalkSessionEvent.EndRequested)
                persistWalkSessionInterruptionMarker()
                cancelWalkSessionOutputs("system_back_exit_confirmed")
                finish()
            }
            .setNegativeButton(R.string.walk_exit_confirmation_negative) { current, _ ->
                walkSessionResumeRetryRequiresUserAction = true
                syncActiveSessionScreenPolicy()
                current.dismiss()
            }
            .setOnCancelListener {
                walkSessionResumeRetryRequiresUserAction = true
                syncActiveSessionScreenPolicy()
            }
            .create()
        walkExitConfirmationDialog = dialog
        dialog.setOnDismissListener {
            if (walkExitConfirmationDialog === dialog) {
                walkExitConfirmationDialog = null
            }
        }
        dialog.show()
    }

    override fun onPause() {
        if (!privacyStartupInspectionComplete) {
            isActivityForeground = false
            feedbackLifecycleGeneration += 1
            super.onPause()
            return
        }
        pauseWalkSafeRuntime()
        if (::cameraFallbackLifecycleOwner.isInitialized) {
            cameraFallbackLifecycleOwner.moveTo(Lifecycle.State.CREATED)
        }
        super.onPause()
    }

    internal fun pauseWalkSafeRuntime() {
        cancelPendingPriorityUserTrainingFeedback()
        if (::walkSessionLifecycle.isInitialized) {
            transitionWalkSession(WalkSessionEvent.EnteredBackground)
            persistWalkSessionInterruptionMarker()
        }
        invalidateOfficialEnvironmentEvidence("app_paused")
        invalidatePhoneMountingEvidence("app_paused")
        isActivityForeground = false
        feedbackLifecycleGeneration += 1
        if (::surfaceView.isInitialized) surfaceView.onPause()
        invalidateRuntimeMetricEvidence("app_paused")
        confirmedStartupCapabilityDecision = null
        startupCapabilityConfirmationPending = false
        walkSessionResumePromptPending = false
        walkSessionResumeRetryRequiresUserAction = false
        walkSessionResumeConfirmationToken = null
        gatewayWalkResumeRevalidationPending = false
        cancelPendingGatewayWalkStart()
        invalidateArCoreAvailabilityRecheck()
        cancelVoiceCommandRecognition()
        synchronized(reportUploadSafetyLock) {
            reportUploadSafetyGeneration += 1L
            reportPrivacyConsentSession.cancelActiveCalls()
        }
        invalidateFrameStateForPause()
        val navigationCancellation = cancelNavigationRequestsForPause()
        suspendNavigationForRecovery(navigationCancellation)
        syncActiveSessionScreenPolicy()
        if (::statusText.isInitialized) pendingTalkBackInteraction?.let(statusText::removeCallbacks)
        pendingTalkBackInteraction = null
        cancelPendingFeedbackTerminalResolution()
        latestFeedbackDeliveryState = FeedbackDeliveryState()
        lastRiskAnnouncementRank = -1
        lastRiskAnnouncementTrackId = ""
        riskAnnouncementHoldUntilMs = 0L
        navigationAnnouncementHoldUntilMs = 0L
        lastAdvisoryAnnouncementMs = 0L
        lastAdvisoryAnnouncement = ""
        if (::fieldSessionLog.isInitialized) fieldSessionLog.recordEvent("app_paused")
        if (
            ::fieldSessionLog.isInitialized &&
            permissionRecoveryGate.blocksAutomaticResourceStart
        ) {
            fieldSessionLog.blockActiveSessionRestore()
        }
        stopLocationUpdates()
        stopStepTracking()
        if (::earthOrientationTracker.isInitialized) earthOrientationTracker.stop()
        feedbackPolicy.cancelPendingFeedbackDeliveries()
        nonMetricAdvisoryPolicy.reset()
        feedbackActuator?.close()
        feedbackActuator = null
        session?.pause()
    }

    private fun invalidateFrameStateForPause() {
        synchronized(frameStateLock) {
            detectorGeneration += 1
            latestDetectionSnapshot = DetectionSnapshot.empty()
            latestTactileOverlaySnapshot = DetectionSnapshot.empty()
            lastNonEmptyOverlaySnapshot = DetectionSnapshot.empty()
            latestExplicitReportOutput = null
            latestExplicitReportImage = null
            latestExplicitReportGateState = null
            latestExplicitReportCapturedAtMs = 0L
            latestReportCandidateStatus = "reportCandidate=blocked:runtime_paused"
            frameCaptureRequested.set(false)
            reportLocationStartScheduled.set(false)
            lastDetectionRunMs = 0L
            lastOverlayUpdateMs = 0L
            lastUiUpdateMs = 0L
            latestTactileRouteState = "localRoute=tmap:tactile_not_visible"
            objectDepthPipeline = ObjectDepthRuntimePipeline().also { pipeline ->
                pipeline.setUserStepLength(stepLengthEstimator.stepLengthM)
            }
            tactileOverlayStabilizer.clear()
            captureLog.clear()
        }
        if (::debugBboxOverlay.isInitialized) runOnUiThread { debugBboxOverlay.clear() }
        if (::debugFrameCaptureButton.isInitialized) runOnUiThread { updateFrameCaptureButton() }
    }

    override fun onDestroy() {
        synchronized(privacyStartupInspectionLock) {
            privacyStartupInspectionDestroyed = true
            privacyStartupInspectionGeneration += 1L
        }
        unregisterGatewayCapacityNetworkObserver()
        cancelEncryptedRouteSnapshotExpirySchedule()
        pendingPrivacyStartupReadyAction = null
        if (!privacyStartupInspectionComplete) {
            if (::accountDeletionActivityLease.isInitialized) {
                accountDeletionProcessCoordinator.detach(accountDeletionActivityLease)
            }
            schedulePrivacyStartupResourcesClose()
            reportCleanupExecutor.shutdown()
            if (::cameraFallbackLifecycleOwner.isInitialized) {
                cameraFallbackLifecycleOwner.moveTo(Lifecycle.State.DESTROYED)
            }
            routeExecutor.shutdownNow()
            reportUploaderExecutor.shutdownNow()
            super.onDestroy()
            return
        }
        if (::accountDeletionActivityLease.isInitialized) {
            accountDeletionProcessCoordinator.detach(accountDeletionActivityLease)
        }
        synchronized(legacyPendingReportQueuePurgeLock) {
            reportCleanupDestroyed = true
            reportCleanupGeneration += 1L
            legacyPendingReportQueuePurgeInFlight = false
            legacyPendingReportQueuePurgeWaiters.clear()
        }
        reportCleanupExecutor.shutdownNow()
        synchronized(integratedConsentLock) {
            integratedConsentRequestGeneration += 1L
            integratedConsentCall?.cancel()
            integratedConsentCall = null
            integratedConsentRequestInFlight = false
        }
        synchronized(accountDeletionLock) {
            accountDeletionRequestGeneration += 1L
            accountDeletionCall?.cancel()
            accountDeletionCall = null
            accountDeletionRequestInFlight = false
        }
        GatewaySessionProcessCoordinator.detach(gatewaySessionOwner)
        if (::gatewayWalkRenewalHandler.isInitialized) {
            gatewayWalkRenewalHandler.removeCallbacksAndMessages(null)
        }
        gatewayWalkAuthorityController.reset()
        gatewayWalkStartConfirmationToken = null
        gatewayWalkTakeoverPromptPending = false
        gatewayWalkTakeoverPromptOperationId = null
        gatewayWalkResumeRevalidationPending = false
        cancelNavigationRequestsForDestroy()
        if (::surfaceView.isInitialized) surfaceView.onPause()
        isActivityForeground = false
        invalidateRuntimeMetricEvidence("app_destroyed")
        invalidateOfficialEnvironmentEvidence("app_destroyed")
        invalidatePhoneMountingEvidence("app_destroyed")
        invalidateArCoreAvailabilityRecheck()
        cancelPendingFeedbackTerminalResolution()
        latestFeedbackDeliveryState = FeedbackDeliveryState()
        syncActiveSessionScreenPolicy()
        if (::cameraFallbackLifecycleOwner.isInitialized) {
            cameraFallbackLifecycleOwner.moveTo(Lifecycle.State.DESTROYED)
        }
        stopCameraFallbackSession()
        stopDepthSession(closeSession = true)
        stopLocationUpdates()
        stopStepTracking()
        if (::earthOrientationTracker.isInitialized) earthOrientationTracker.stop()
        schedulePrivacyStartupResourcesClose()
        feedbackPolicy.cancelPendingFeedbackDeliveries()
        feedbackActuator?.close()
        feedbackActuator = null
        cancelVoiceCommandRecognition()
        speechRecognizer?.destroy()
        speechRecognizer = null
        if (::startupCapabilityProbe.isInitialized) {
            startupCapabilityProbe.close()
        }
        if (::walkSessionResourceProbe.isInitialized) {
            walkSessionResourceProbe.close()
        }
        permissionRequestLeases.clear()
        permissionRequestGenerationByPurpose.clear()
        synchronized(reportUploadSafetyLock) {
            reportUploadSafetyGeneration += 1L
            reportPrivacyConsentSession.cancelActiveCalls()
        }
        routeExecutor.shutdownNow()
        reportUploaderExecutor.shutdownNow()
        closeDetectorAsync()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val lease = permissionRequestLeases.remove(requestCode) ?: return
        if (
            lease.purpose == PermissionRequestPurpose.WALK_SESSION &&
            walkSessionPermissionRequestCode == requestCode
        ) {
            walkSessionPermissionRequestCode = null
            walkSessionPermissionRequestInFlight = false
        }
        if (!isPermissionRequestLeaseCurrent(lease)) return
        val observed = currentObservedPermissionSnapshot()
        val missingRequested = lease.requestedPermissions
            .filterNot(observed::isGranted)
            .toSet()
        if (lease.purpose == PermissionRequestPurpose.WALK_SESSION) {
            if (missingRequested.isNotEmpty()) {
                enterPermissionRecoveryBarrier(
                    missingPermissions = missingRequested,
                    reason = "permission_result_walk_session",
                )
            } else {
                permissionRecoveryGate = permissionRecoveryGate.recheckRequired()
                persistPermissionRecoveryGate()
                completePermissionRecoveryRecheckIfPossible()
            }
        }
        when (lease.purpose) {
            PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA ->
                handleMetricPreflightCameraPermissionResult()
            PermissionRequestPurpose.WALK_SESSION -> handleWalkSessionPermissionResult()
            PermissionRequestPurpose.RUNTIME_CAMERA -> handleCameraPermissionResult()
            PermissionRequestPurpose.NAVIGATION -> handleNavigationPermissionResult()
            PermissionRequestPurpose.VOICE_COMMAND -> handleVoicePermissionResult()
        }
        applyObservedPermissionStateChange(
            "permission_result_${lease.purpose.name.lowercase(Locale.US)}",
        )
    }

    private fun requestPermissionsWithLease(
        permissions: Array<String>,
        purpose: PermissionRequestPurpose,
    ): Int? {
        if (!firstRunPermissionRequestAllowed(purpose)) return null
        val epoch = walkSessionLifecycle.snapshot().epoch
        val requestedPermissions = permissions.mapNotNull { it.toObservedPermission() }.toSet()
        if (purpose == PermissionRequestPurpose.WALK_SESSION) {
            permissionRecoveryGate = permissionRecoveryGate.requestPending(requestedPermissions)
            persistPermissionRecoveryGate()
            if (::fieldSessionLog.isInitialized) {
                fieldSessionLog.blockActiveSessionRestore()
            }
        }
        val generation = (permissionRequestGenerationByPurpose[purpose] ?: 0L) + 1L
        permissionRequestGenerationByPurpose[purpose] = generation
        var requestCode = nextPermissionRequestCode
        while (permissionRequestLeases.containsKey(requestCode)) {
            requestCode += 1
            if (requestCode > PERMISSION_REQUEST_CODE_MAX) {
                requestCode = PERMISSION_REQUEST_CODE_MIN
            }
            check(requestCode != nextPermissionRequestCode) {
                "No permission request code is available"
            }
        }
        nextPermissionRequestCode = requestCode + 1
        if (nextPermissionRequestCode > PERMISSION_REQUEST_CODE_MAX) {
            nextPermissionRequestCode = PERMISSION_REQUEST_CODE_MIN
        }
        permissionRequestLeases[requestCode] = PermissionRequestLease(
            purpose = purpose,
            epoch = epoch,
            generation = generation,
            firstRunLease = currentFirstRunAsyncLease(),
            requestedPermissions = requestedPermissions,
        )
        try {
            requestPermissions(permissions, requestCode)
        } catch (error: RuntimeException) {
            permissionRequestLeases.remove(requestCode)
            if (purpose == PermissionRequestPurpose.WALK_SESSION) {
                enterPermissionRecoveryBarrier(
                    missingPermissions = requestedPermissions,
                    reason = "permission_request_failed",
                )
            }
            throw error
        }
        return requestCode
    }

    private fun isPermissionRequestLeaseCurrent(
        lease: PermissionRequestLease,
    ): Boolean {
        if (!isFirstRunAsyncLeaseCurrent(lease.firstRunLease)) return false
        if (!firstRunPermissionRequestAllowed(lease.purpose)) return false
        val snapshot = walkSessionLifecycle.snapshot()
        if (snapshot.epoch != lease.epoch) return false
        if (
            permissionRequestGenerationByPurpose[lease.purpose] !=
            lease.generation
        ) return false
        return when (lease.purpose) {
            PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA,
            PermissionRequestPurpose.WALK_SESSION,
            -> snapshot.isForeground &&
                snapshot.state in setOf(WalkSessionState.READY, WalkSessionState.PAUSED)
            PermissionRequestPurpose.RUNTIME_CAMERA,
            PermissionRequestPurpose.NAVIGATION,
            PermissionRequestPurpose.VOICE_COMMAND,
            -> walkSessionLifecycle.isRuntimeEpochCurrent(lease.epoch)
        }
    }

    private fun handleMetricPreflightCameraPermissionResult() {
        val generation = pendingMetricPreflightPermissionGeneration ?: return
        pendingMetricPreflightPermissionGeneration = null
        if (!isRuntimeMetricPreflightAttemptCurrent(generation, metricPreflightLifecycleGeneration)) return
        if (hasCameraPermission()) {
            startRuntimeMetricPreflight(generation, metricPreflightLifecycleGeneration)
        } else {
            refreshStartupCapabilityUi()
            val detail = if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                "실제 미터 거리 기능을 확인하려면 카메라 권한이 필요합니다."
            } else {
                "Android 앱 설정에서 카메라 권한을 허용한 뒤 다시 확인하세요."
            }
            updateStatus("카메라 권한 필요", detail)
        }
    }

    private fun handleWalkSessionPermissionResult() {
        walkSessionPermissionRequestInFlight = false
        if (!isActivityForeground) return
        val decision = startupCapabilityDecision ?: return
        val action = if (
            walkSessionLifecycle.snapshot().state == WalkSessionState.PAUSED
        ) {
            WalkSessionAction.RESUME_WALK
        } else {
            WalkSessionAction.START_WALK
        }
        val missingRequired = missingRequiredWalkSessionPermissions(action)
        val missing = missingWalkSessionPermissions(decision)
        refreshStartupCapabilityUi()
        if (missingRequired.isNotEmpty()) {
            updateStatus(
                "필수 권한 필요",
                "보행 안내를 시작하지 않았습니다. 필요한 권한: " +
                    missingRequired.joinToString(", ") { it.permissionLabelKo() },
            )
        } else if (missing.isNotEmpty()) {
            updateStatus(
                "일부 기능 권한 없음",
                "허용된 기능으로 계속합니다. 사용할 때 다시 확인할 권한: " +
                    missing.joinToString(", ") { it.permissionLabelKo() },
            )
        }
    }

    private fun handleCameraPermissionResult() {
        if (permissionRecoveryGate.blocksAutomaticResourceStart) return
        if (hasCameraPermission()) {
            setActionButton("ARCore Depth 시작", ActionMode.START, enabled = true)
            startConfirmedRuntimeAfterCameraPermission()
        } else {
            if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                setActionButton("카메라 권한 다시 요청", ActionMode.START, enabled = true)
                updateStatus("카메라 권한 필요", "ARCore Depth를 시작하려면 카메라 권한을 허용해야 합니다.")
            } else {
                setActionButton("앱 설정 열기", ActionMode.OPEN_SETTINGS, enabled = true)
                updateStatus("카메라 권한 차단됨", "Android 앱 설정에서 카메라 권한을 허용한 뒤 다시 시작하세요.")
            }
        }
    }

    private fun handleNavigationPermissionResult() {
        if (permissionRecoveryGate.blocksAutomaticResourceStart) return
        if (hasLocationPermission()) {
            if (isRouteActive || navigationPermissionsRequestedForRoute || navigationPermissionsRequestedForReport || isFieldSessionActive()) {
                startLocationUpdatesIfAllowed(forceRestart = true)
            }
        } else if (!hasLocationPermission()) {
            updateNavigationStatus("navigation=gps_permission_missing")
        }
        if (hasActivityRecognitionPermission()) {
            if (isRouteActive || navigationPermissionsRequestedForRoute || isFieldSessionActive()) {
                startStepTrackingIfAllowed()
            }
        } else if (isRouteActive || navigationPermissionsRequestedForRoute || isFieldSessionActive()) {
            updateNavigationStatus("navigation=activity_recognition_permission_missing step_fallback_wait")
        }
    }

    private fun handleVoicePermissionResult() {
        if (permissionRecoveryGate.blocksAutomaticResourceStart) return
        if (hasRecordAudioPermission()) {
            startVoiceCommandRecognition()
        } else {
            updateNavigationStatus("voice=record_audio_permission_missing")
            speakInteraction("음성 명령을 사용하려면 마이크 권한이 필요합니다.")
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        backgroundRenderer.createOnGlThread()
        cameraTextureBound = false
        cameraTextureId = createExternalCameraTexture()
        bindCameraTextureIfReady()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
        updateDisplayGeometryIfReady()
    }

    /** Keeps preview, coordinate mapping and depth acquisition on the same ARCore frame. */
    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val frameGeneration = synchronized(frameStateLock) {
            if (!isActivityForeground) return
            detectorGeneration
        }

        val arLease = synchronized(runtimeMetricStateLock) {
            val currentSession = session ?: return
            val currentProvider = frameProvider ?: return
            ArSessionLease(
                session = currentSession,
                provider = currentProvider,
                purpose = arSessionPurpose,
                generation = arSessionGeneration,
            )
        }
        bindCameraTextureIfReady()
        if (!cameraTextureBound) return
        if (!isArSessionLeaseCurrent(arLease)) return
        val frameWalkEpoch = walkSessionLifecycle.snapshot().epoch

        val frame = try {
            arLease.session.update()
        } catch (error: CameraNotAvailableException) {
            handleRuntimeMetricFrameFailure(
                "카메라 사용 불가",
                error.message ?: "ARCore frame update 실패",
                arLease.purpose,
                arLease.generation,
                frameWalkEpoch,
            )
            return
        } catch (error: RuntimeException) {
            handleRuntimeMetricFrameFailure(
                "ARCore frame 대기",
                error.message ?: "frame update 준비 중",
                arLease.purpose,
                arLease.generation,
                frameWalkEpoch,
            )
            return
        }
        if (!isArSessionLeaseCurrent(arLease)) return
        if (frame.hasDisplayGeometryChanged()) {
            updateDisplayGeometryIfReady()
        }

        val provider = arLease.provider
        backgroundRenderer.draw(frame, cameraTextureId)
        val timestampMs = frame.timestamp / 1_000_000L
        val nowMs = System.currentTimeMillis()
        val elapsedRealtimeMs = SystemClock.elapsedRealtime()
        val snapshot = provider.acquireDepthBundle(frame).toSnapshotAndClose()
        if (!isArSessionLeaseCurrent(arLease)) return
        if (arLease.purpose == ArSessionPurpose.PREFLIGHT) {
            handleRuntimeMetricPreflightFrame(
                frame = frame,
                snapshot = snapshot,
                observedAtElapsedRealtimeMs = elapsedRealtimeMs,
                expectedArSessionGeneration = arLease.generation,
                expectedWalkEpoch = frameWalkEpoch,
            )
            return
        }
        if (arLease.purpose != ArSessionPurpose.RUNTIME || !isStartupCapabilityConfirmed()) {
            runtimeMetricOutputAllowed = false
            return
        }
        val walkEpoch = walkSessionLifecycle.currentRuntimeEpochOrNull()
        if (walkEpoch == null) {
            runtimeMetricOutputAllowed = false
            return
        }
        updateRuntimeMetricOutputGate(
            frame = frame,
            snapshot = snapshot,
            observedAtElapsedRealtimeMs = elapsedRealtimeMs,
            expectedArSessionGeneration = arLease.generation,
            expectedWalkEpoch = walkEpoch,
        )
        if (!runtimeMetricOutputAllowed) return
        if (!currentRuntimeMetricOutputAllowsWork(arLease.generation)) return
        val detectionSnapshot = latestDetectionSnapshot
        val overlaySelection = synchronized(frameStateLock) {
            if (!isCurrentFrameGeneration(frameGeneration, walkEpoch)) return
            selectOverlayDetections(detectionSnapshot, nowMs, timestampMs)
        }
        val overlaySnapshot = overlaySelection.mappingSnapshot
        val overlayDetections = overlaySelection.detections
        var overlayDebugState = overlaySelection.debugState
        if (!currentRuntimeMetricOutputAllowsWork(arLease.generation)) return
        scheduleDetectionIfDue(
            provider,
            frame,
            timestampMs,
            nowMs,
            elapsedRealtimeMs,
            snapshot,
            frameGeneration,
            walkEpoch,
        )
        val preparedTactileFrame = this.prepareTactileFrameDispatch(
            detectionSnapshot = detectionSnapshot,
            nowMs = nowMs,
            currentFrameTimestampMs = timestampMs,
            maxDetectionSourceAgeMs = MAX_DEPTH_DETECTION_SOURCE_AGE_MS,
            maxDetectionFrameDeltaMs = MAX_DEPTH_DETECTION_FRAME_DELTA_MS,
            detectionAgeMs = detectionSnapshot.sourceAgeMs(nowMs),
        ) { evidence, depthDetections ->
            synchronized(frameStateLock) {
                if (!isCurrentFrameGeneration(frameGeneration, walkEpoch)) {
                    emptyList()
                } else {
                    objectDepthPipeline.process(
                        snapshot = evidence.depthSnapshot,
                        frameId = evidence.frameId,
                        timestampMs = evidence.frameTimestampMs,
                        detections = depthDetections,
                        detectionSequenceId = evidence.frameId,
                        detectionCompleted = true,
                        mapper = requireNotNull(evidence.depthMapper),
                        motionContext = evidence.motionContext,
                    )
                }
            }
        }
        val tactileFrame = preparedTactileFrame.frame
        synchronized(frameStateLock) {
            if (!isCurrentFrameGeneration(frameGeneration, walkEpoch)) return
            latestTactileRouteState =
                "localRoute=${tactileFrame.guidance.decision.mode.wireValue}:${tactileFrame.guidance.decision.reason}"
        }
        val matchedEvidence = tactileFrame.matchedEvidence
        val depthDetections = tactileFrame.depthDetections
        val depthMapper = matchedEvidence?.depthMapper
        val depthInputDetections = if (depthMapper == null) emptyList() else depthDetections
        val guidanceOutputs = tactileFrame.guidance.outputs
        val bestOutput = AndroidRiskSelectionPolicy.selectBestOutput(guidanceOutputs)
        val reportOutput = AndroidRiskSelectionPolicy.selectReportOutput(guidanceOutputs)
        val automaticReportOutput = AndroidRiskSelectionPolicy.selectAutomaticReportOutput(guidanceOutputs)
        val prioritizedFeedbackOutputs = AndroidRiskSelectionPolicy.prioritizedFeedbackOutputs(guidanceOutputs)
        val feedbackGateOutput = prioritizedFeedbackOutputs.firstOrNull { output ->
            output.source.metric && output.confidence.finalScore >= FEEDBACK_MIN_DEPTH_CONFIDENCE
        }
        val shouldUpdateOverlay = synchronized(frameStateLock) {
            if (!isCurrentFrameGeneration(frameGeneration, walkEpoch)) return
            elapsedRealtimeMs - lastOverlayUpdateMs >= OVERLAY_UPDATE_INTERVAL_MS || overlayDetections.isEmpty()
        }
        if (shouldUpdateOverlay) {
            val overlayBoxes = buildDebugOverlayBoxes(frame, overlayDetections, bestOutput, overlaySnapshot)
            val stabilizedOverlay = synchronized(frameStateLock) {
                if (!isCurrentFrameGeneration(frameGeneration, walkEpoch)) return
                lastOverlayUpdateMs = elapsedRealtimeMs
                tactileOverlayStabilizer.update(overlayBoxes, elapsedRealtimeMs)
            }
            overlayDebugState = overlayDebugState.withStabilizer(stabilizedOverlay)
            runOnUiThread {
                if (isCurrentFrameGeneration(frameGeneration, walkEpoch)) {
                    debugBboxOverlay.updateMapped(stabilizedOverlay.boxes)
                }
            }
        }
        if (!isCurrentFrameGeneration(frameGeneration, walkEpoch)) return
        val captureLogEntry = buildCaptureLogEntry(
            frameTimestampMs = timestampMs,
            nowMs = nowMs,
            detectionSnapshot = detectionSnapshot,
            overlayDebugState = overlayDebugState,
            detectionsUsedForDepth = depthInputDetections,
            snapshot = matchedEvidence?.depthSnapshot ?: snapshot,
            bestOutput = bestOutput,
            depthTransformPath = if (depthMapper == null) "unavailable" else "frozen_capture_image_to_texture_normalized",
            depthFallbackReason = when {
                matchedEvidence == null -> "matched_detection_frame_evidence_unavailable"
                depthMapper == null -> "frozen_depth_mapper_unavailable"
                depthDetections.isNotEmpty() && depthInputDetections.isEmpty() -> "depth_input_suppressed"
                else -> null
            },
        )
        synchronized(frameStateLock) {
            if (
                !isCurrentFrameGeneration(frameGeneration, walkEpoch) ||
                !currentRuntimeMetricOutputAllowsWork(arLease.generation)
            ) return
            captureLog.append(captureLogEntry)
            runIfActivityOriginalUploadAllowed {
                if (metadataLogUploader.isEnabled()) {
                    metadataLogUploader.enqueue(captureLogEntry)
                }
            }
        }
        val deviceGateState = buildDeviceGateState(
            bestOutput = feedbackGateOutput,
            staleReason = captureLogEntry.staleReason,
            expectedRuntimeMetricGeneration = arLease.generation,
        )
        val reportGateState = buildDeviceGateState(
            bestOutput = automaticReportOutput,
            staleReason = captureLogEntry.staleReason,
            expectedRuntimeMetricGeneration = arLease.generation,
        )
        val explicitReportGateState = buildDeviceGateState(
            bestOutput = reportOutput,
            staleReason = captureLogEntry.staleReason,
            expectedRuntimeMetricGeneration = arLease.generation,
        )
        val freshLocation = freshTrustedLocationOrNull()
        val locationAgeMs = freshLocation?.let { trusted ->
            (SystemClock.elapsedRealtime() - trusted.elapsedRealtimeMs).coerceAtLeast(0L)
        }
        synchronized(frameStateLock) {
            if (
                !isCurrentFrameGeneration(frameGeneration, walkEpoch) ||
                !currentRuntimeMetricOutputAllowsWork(arLease.generation)
            ) return
            fieldSessionLog.appendTelemetry(
                entry = captureLogEntry,
                runtime = FieldRuntimeSnapshot(
                    stepCount = latestStepCount,
                    stepLengthM = stepLengthEstimator.stepLengthM,
                    routeActive = isRouteActive,
                    navigationState = latestNavigationState,
                    deviceGateAllowsAlerts = deviceGateState.alertsAllowed,
                    deviceGateAllowsReports = reportGateState.reportCandidatesAllowed,
                    reportCandidateState = latestReportCandidateStatus,
                    trustedLocationAvailable = freshLocation != null,
                    locationAccuracyM = freshLocation?.accuracyM,
                    locationAgeMs = locationAgeMs,
                    headingDeg = latestHeadingDeg,
                ),
            )
        }
        if (
            !publishCurrentFrameReportState(
                frameGeneration = frameGeneration,
                expectedRuntimeMetricGeneration = arLease.generation,
                automaticReportOutput = automaticReportOutput,
                reportGateState = reportGateState,
                explicitReportOutput = reportOutput,
                explicitReportGateState = explicitReportGateState,
                reportImage = detectionSnapshot.reportImage,
                nowMs = nowMs,
                capturedAtMs = detectionSnapshot.capturedAtMs ?: nowMs,
                expectedWalkEpoch = walkEpoch,
            )
        ) return
        if (!currentRuntimeMetricOutputAllowsWork(arLease.generation)) return
        preparedTactileFrame.dispatchFeedback(
            stale = captureLogEntry.staleReason != null,
            deviceGateAllowsAlerts = deviceGateState.alertsAllowed,
            nowMs = elapsedRealtimeMs,
        )
        val shouldUpdateUi = synchronized(frameStateLock) {
            if (!isCurrentFrameGeneration(frameGeneration, walkEpoch)) return
            if (elapsedRealtimeMs - lastUiUpdateMs < UI_UPDATE_INTERVAL_MS) {
                false
            } else {
                lastUiUpdateMs = elapsedRealtimeMs
                true
            }
        }
        if (!shouldUpdateUi) return

        runOnUiThread {
            if (!isCurrentFrameGeneration(frameGeneration, walkEpoch)) return@runOnUiThread
            val gateText = deviceGateState.statusText()
            if (bestOutput == null) {
                updateStatus(
                    status = if (depthSupported) "카메라/Depth 수신 중" else "Depth 미지원 fallback 대기",
                    detail = "${snapshot.statusTextForNoDetection()}\n" +
                        "${detectionSnapshot.debugStatusText(nowMs, timestampMs, MAX_OVERLAY_DETECTION_SOURCE_AGE_MS, MAX_OVERLAY_DETECTION_FRAME_DELTA_MS)}\n" +
                        "$gateText · $latestTactileRouteState · ${feedbackActuatorStatusText()} · $latestReportCandidateStatus · steps=$latestStepCount stepLength=${meters(stepLengthEstimator.stepLengthM)}\n" +
                        "$detectorStatusText\n" +
                        "${metadataLogUploader.statusText()}\n" +
                        captureLog.summaryText(),
                )
            } else {
                updateStatus(
                    status = "객체별 depth 수신 중",
                    detail = "${bestOutput.debugSummaryText()}\n" +
                        "${detectionSnapshot.debugStatusText(nowMs, timestampMs, MAX_OVERLAY_DETECTION_SOURCE_AGE_MS, MAX_OVERLAY_DETECTION_FRAME_DELTA_MS)}\n" +
                        "${bestOutput.debugGeometryText()}\n" +
                        "$gateText · $latestTactileRouteState · ${feedbackActuatorStatusText()} · $latestReportCandidateStatus · steps=$latestStepCount stepLength=${meters(stepLengthEstimator.stepLengthM)}\n" +
                        "$detectorStatusText\n" +
                        "${metadataLogUploader.statusText()}\n" +
                        captureLog.summaryText(),
                )
            }
            updateDebugUploadButton()
        }
    }

    private fun handleRuntimeMetricPreflightFrame(
        frame: Frame,
        snapshot: DepthFrameSnapshot,
        observedAtElapsedRealtimeMs: Long,
        expectedArSessionGeneration: Long,
        expectedWalkEpoch: WalkRuntimeEpoch,
    ) {
        val evaluator = runtimeMetricPreflightSession ?: return
        if (
            arSessionPurpose != ArSessionPurpose.PREFLIGHT ||
            arSessionGeneration != expectedArSessionGeneration ||
            evaluator.generation != runtimeMetricPreflightGeneration ||
            walkSessionLifecycle.snapshot().epoch != expectedWalkEpoch
        ) {
            return
        }
        if (firstRunOnboardingComplete()) {
            observeOfficialEnvironmentCameraFrame(
                epoch = expectedWalkEpoch,
                observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
                frameAvailable =
                    frame.camera.trackingState == TrackingState.TRACKING,
            )
        }
        val validSamples = snapshot.runtimeMetricValidSampleCount()
        val result = evaluator.observe(
            RuntimeMetricFrameEvidence(
                generation = evaluator.generation,
                frameTimestampNanos = frame.timestamp,
                observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
                tracking = frame.camera.trackingState == TrackingState.TRACKING,
                metricDepthAvailable = snapshot.hasMetricRawDepth || snapshot.hasFullDepth,
                validMetricSamplesInRange = validSamples,
            ),
        )
        if (
            result.status != RuntimeMetricPreflightStatus.IN_PROGRESS &&
            metricPreflightTerminalDispatchedGeneration != result.generation
        ) {
            metricPreflightTerminalDispatchedGeneration = result.generation
            val lifecycleGeneration = metricPreflightLifecycleGeneration
            runOnUiThread {
                finishRuntimeMetricPreflight(
                    result,
                    expectedArSessionGeneration = expectedArSessionGeneration,
                    expectedLifecycleGeneration = lifecycleGeneration,
                )
            }
        }
    }

    private fun handleRuntimeMetricFrameFailure(
        status: String,
        detail: String,
        expectedPurpose: ArSessionPurpose,
        expectedArSessionGeneration: Long,
        expectedWalkEpoch: WalkRuntimeEpoch,
    ) {
        if (
            arSessionPurpose != expectedPurpose ||
            arSessionGeneration != expectedArSessionGeneration ||
            walkSessionLifecycle.snapshot().epoch != expectedWalkEpoch
        ) return
        runtimeMetricOutputAllowed = false
        if (
            expectedPurpose == ArSessionPurpose.RUNTIME &&
            firstRunOnboardingComplete()
        ) {
            observeOfficialEnvironmentCameraFrame(
                epoch = expectedWalkEpoch,
                observedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                frameAvailable = false,
            )
        }
        val expectedLifecycleGeneration = metricPreflightLifecycleGeneration
        when (expectedPurpose) {
            ArSessionPurpose.PREFLIGHT -> {
                val active = runtimeMetricPreflightSession ?: return
                val result = active.failTransiently(SystemClock.elapsedRealtime())
                if (metricPreflightTerminalDispatchedGeneration == result.generation) return
                metricPreflightTerminalDispatchedGeneration = result.generation
                runOnUiThread {
                    finishRuntimeMetricPreflight(
                        result,
                        expectedArSessionGeneration = expectedArSessionGeneration,
                        expectedLifecycleGeneration = expectedLifecycleGeneration,
                    )
                }
            }
            ArSessionPurpose.RUNTIME -> runOnUiThread {
                handleRuntimeMetricLoss(expectedArSessionGeneration)
            }
            ArSessionPurpose.NONE -> runOnUiThread { updateStatus(status, detail) }
        }
    }

    private fun isArSessionLeaseCurrent(lease: ArSessionLease): Boolean =
        synchronized(runtimeMetricStateLock) {
            session === lease.session &&
                frameProvider === lease.provider &&
                arSessionPurpose == lease.purpose &&
                arSessionGeneration == lease.generation
        }

    private fun currentRuntimeMetricOutputAllowsWork(expectedGeneration: Long? = null): Boolean =
        synchronized(runtimeMetricStateLock) {
            firstRunOnboardingComplete() &&
                isWalkSessionRuntimeActive() &&
                isActivityForeground &&
                officialEnvironmentOutputsAllowed &&
                phoneMountingOutputsAllowed &&
                arSessionPurpose == ArSessionPurpose.RUNTIME &&
                (expectedGeneration == null || arSessionGeneration == expectedGeneration) &&
                session != null &&
                frameProvider != null &&
                runtimeMetricOutputAllowed &&
                startupCapabilityDecision?.tier == WalkSafeStartupCapabilityTier.FULL &&
                isStartupCapabilityConfirmed()
        }

    private fun updateRuntimeMetricOutputGate(
        frame: Frame,
        snapshot: DepthFrameSnapshot,
        observedAtElapsedRealtimeMs: Long,
        expectedArSessionGeneration: Long,
        expectedWalkEpoch: WalkRuntimeEpoch,
    ) {
        val activation = synchronized(runtimeMetricStateLock) {
            if (
                !isWalkSessionRuntimeActive() ||
                !walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) ||
                startupCapabilityDecision?.tier != WalkSafeStartupCapabilityTier.FULL ||
                arSessionPurpose != ArSessionPurpose.RUNTIME ||
                arSessionGeneration != expectedArSessionGeneration ||
                !isActivityForeground ||
                !isStartupCapabilityConfirmed()
            ) {
                runtimeMetricOutputAllowed = false
                return
            }
            runtimeMetricActivationSession
        }
        observeOfficialEnvironmentCameraFrame(
            epoch = expectedWalkEpoch,
            observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
            frameAvailable = frame.camera.trackingState == TrackingState.TRACKING,
        )
        val validSamples = snapshot.runtimeMetricValidSampleCount()
        val frameEvidence = RuntimeMetricFrameEvidence(
            generation = expectedArSessionGeneration,
            frameTimestampNanos = frame.timestamp,
            observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
            tracking = frame.camera.trackingState == TrackingState.TRACKING,
            metricDepthAvailable = snapshot.hasMetricRawDepth || snapshot.hasFullDepth,
            validMetricSamplesInRange = validSamples,
        )
        if (activation != null) {
            val result = activation.observe(frameEvidence)
            var activated = false
            var lost = false
            synchronized(runtimeMetricStateLock) {
                val stillCurrent =
                    isWalkSessionRuntimeActive() &&
                        arSessionPurpose == ArSessionPurpose.RUNTIME &&
                        arSessionGeneration == expectedArSessionGeneration &&
                        runtimeMetricActivationSession === activation &&
                        isActivityForeground &&
                        startupCapabilityDecision?.tier == WalkSafeStartupCapabilityTier.FULL &&
                        isStartupCapabilityConfirmed()
                if (!stillCurrent) {
                    runtimeMetricOutputAllowed = false
                } else if (result.status == RuntimeMetricPreflightStatus.AVAILABLE) {
                    runtimeMetricOutputAllowed = frameEvidence.tracking &&
                        frameEvidence.metricDepthAvailable &&
                        frameEvidence.validMetricSamplesInRange >=
                        RuntimeMetricPreflightPolicy.MIN_VALID_SAMPLES_PER_PASSING_FRAME
                    runtimeMetricActivationSession = null
                    runtimeMetricLastFrameTimestampNanos = frame.timestamp
                    if (runtimeMetricOutputAllowed) {
                        runtimeMetricLastValidFrameAtMs = observedAtElapsedRealtimeMs
                        activated = runtimeMetricInitialNavigationStartPending
                    }
                } else {
                    runtimeMetricOutputAllowed = false
                    lost = result.status == RuntimeMetricPreflightStatus.UNKNOWN
                }
            }
            if (activated) {
                runOnUiThread {
                    startInitialNavigationServicesIfCurrent(expectedArSessionGeneration)
                }
            } else if (lost) {
                runOnUiThread { handleRuntimeMetricLoss(expectedArSessionGeneration) }
            }
            return
        }

        val samplesAreValid = frameEvidence.tracking &&
            frameEvidence.metricDepthAvailable &&
            frameEvidence.validMetricSamplesInRange >=
            RuntimeMetricPreflightPolicy.MIN_VALID_SAMPLES_PER_PASSING_FRAME
        var shouldHandleLoss = false
        var shouldStartNavigation = false
        synchronized(runtimeMetricStateLock) {
            val stillCurrent =
                arSessionPurpose == ArSessionPurpose.RUNTIME &&
                    arSessionGeneration == expectedArSessionGeneration &&
                    runtimeMetricActivationSession == null &&
                    isActivityForeground &&
                    startupCapabilityDecision?.tier == WalkSafeStartupCapabilityTier.FULL &&
                    isStartupCapabilityConfirmed()
            if (!stillCurrent) {
                runtimeMetricOutputAllowed = false
                return
            }
            val frameOrderValid = frame.timestamp > runtimeMetricLastFrameTimestampNanos
            val currentFrameValid = samplesAreValid && frameOrderValid
            if (frameOrderValid) {
                runtimeMetricLastFrameTimestampNanos = frame.timestamp
            }
            shouldStartNavigation = currentFrameValid && runtimeMetricInitialNavigationStartPending
            runtimeMetricOutputAllowed = currentFrameValid
            if (currentFrameValid) {
                runtimeMetricLastValidFrameAtMs = observedAtElapsedRealtimeMs
            } else {
                shouldHandleLoss = !frameOrderValid ||
                    observedAtElapsedRealtimeMs - runtimeMetricLastValidFrameAtMs >=
                    RUNTIME_METRIC_STALE_TIMEOUT_MS
            }
        }
        if (!runtimeMetricOutputAllowed) clearExplicitReportFrameState("runtime_metric_frame_invalid")
        if (shouldStartNavigation) {
            runOnUiThread {
                startInitialNavigationServicesIfCurrent(expectedArSessionGeneration)
            }
        }
        if (shouldHandleLoss) {
            runOnUiThread { handleRuntimeMetricLoss(expectedArSessionGeneration) }
        }
    }

    private fun startInitialNavigationServicesIfCurrent(expectedArSessionGeneration: Long) {
        synchronized(runtimeMetricStateLock) {
            if (
                !runtimeMetricInitialNavigationStartPending ||
                !currentRuntimeMetricOutputAllowsWork(expectedArSessionGeneration)
            ) return
            startNavigationServicesIfNeeded()
            runtimeMetricInitialNavigationStartPending = false
        }
    }

    private fun handleRuntimeMetricLoss(expectedArSessionGeneration: Long) {
        if (
            arSessionPurpose != ArSessionPurpose.RUNTIME ||
            arSessionGeneration != expectedArSessionGeneration ||
            startupCapabilityDecision?.tier != WalkSafeStartupCapabilityTier.FULL
        ) {
            return
        }
        invalidateRuntimeMetricEvidence("runtime_metric_lost")
        updateStatus(
            "미터 거리 기능 중단",
            "실제 거리 프레임을 더 이상 확인할 수 없어 보행 출력을 중단했습니다. 다시 기기 기능을 확인하세요.",
        )
    }

    private fun clearExplicitReportFrameState(reason: String) {
        synchronized(frameStateLock) {
            latestExplicitReportOutput = null
            latestExplicitReportImage = null
            latestExplicitReportGateState = null
            latestExplicitReportCapturedAtMs = 0L
            latestReportCandidateStatus = "reportCandidate=blocked:$reason"
        }
    }

    private fun DepthFrameSnapshot.runtimeMetricValidSampleCount(): Int {
        val raw = rawDepth
        val confidence = rawConfidence
        var rawCount = 0
        if (
            raw != null &&
            confidence != null &&
            raw.width == confidence.width &&
            raw.height == confidence.height
        ) {
            for (index in 0 until raw.width * raw.height) {
                val distanceMeters = raw.millimeters[index] / 1_000.0
                val confidenceValue = (confidence.values[index].toInt() and 0xff) / 255.0
                if (
                    confidenceValue >= RUNTIME_METRIC_MIN_RAW_CONFIDENCE &&
                    RuntimeMetricPreflightPolicy.isValidMetricDistanceMeters(distanceMeters)
                ) {
                    rawCount += 1
                }
            }
        }
        val fullCount = fullDepth?.millimeters?.count { millimeters ->
            RuntimeMetricPreflightPolicy.isValidMetricDistanceMeters(millimeters / 1_000.0)
        } ?: 0
        return maxOf(rawCount, fullCount)
    }

    private fun buildContentView(): FrameLayout {
        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(this@MainActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            preserveEGLContextOnPause = true
        }
        cameraFallbackPreviewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            visibility = View.GONE
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        fun accessiblePriorityUserButton(
            label: String,
            emphasis: Boolean = false,
            onClick: () -> Unit,
        ) = Button(this).apply {
            id = View.generateViewId()
            text = label
            contentDescription = label
            isAllCaps = false
            setSingleLine(false)
            ellipsize = null
            minimumHeight = (48f * resources.displayMetrics.density).roundToInt()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            val density = resources.displayMetrics.density
            textSize = 17f
            setTextColor(WS_COLOR_BUTTON_TEXT)
            if (emphasis) {
                // 이 단계의 주 행동. 더 큰 터치 영역과 강조 테두리로 구분한다.
                minimumHeight = (WS_TOUCH_PRIMARY_DP * density).roundToInt()
                setTypeface(typeface, Typeface.BOLD)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = WS_CORNER_RADIUS_DP * density
                setColor(WS_COLOR_BUTTON_FILL)
                setStroke(
                    ((if (emphasis) 2f else 1f) * density).roundToInt(),
                    if (emphasis) WS_COLOR_EMPHASIS else WS_COLOR_LINE,
                )
            }
            setPadding(
                (20f * density).roundToInt(),
                (12f * density).roundToInt(),
                (20f * density).roundToInt(),
                (12f * density).roundToInt(),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = (WS_CONTROL_GAP_DP * density).roundToInt()
            }
            setOnClickListener { onClick() }
        }

        productPurposeText = TextView(this).apply {
            text = "$WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO\n앱 버전: ${BuildConfig.VERSION_NAME}"
            textSize = 18f
            setTextColor(WS_COLOR_NOTICE_TEXT)
            setLineSpacing(0f, 1.45f)
            val density = resources.displayMetrics.density
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = WS_CORNER_RADIUS_DP * density
                setColor(WS_COLOR_NOTICE_FILL)
                setStroke((1f * density).roundToInt(), WS_COLOR_LINE)
            }
            setPadding(
                (16f * density).roundToInt(),
                (16f * density).roundToInt(),
                (16f * density).roundToInt(),
                (16f * density).roundToInt(),
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = (WS_SECTION_GAP_DP * density).roundToInt()
            }
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        firstRunNoticeToggleButton = accessiblePriorityUserButton(
            label = "안전 고지 다시 보기",
            onClick = {
                firstRunNoticeExpandedByUser = !firstRunNoticeExpandedByUser
                refreshFirstRunNoticeUi()
            },
        )
        firstRunProgressSegments.clear()
        firstRunProgressBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // 같은 정보를 아래 heading 문장이 낭독하므로 접근성 트리에서 제외한다.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val density = resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (WS_SECTION_GAP_DP * density).roundToInt() }
            repeat(FIRST_RUN_VISIBLE_STAGE_COUNT) { index ->
                val segment = View(this@MainActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        0,
                        (3f * density).roundToInt(),
                        1f,
                    ).apply {
                        if (index > 0) marginStart = (6f * density).roundToInt()
                    }
                    setBackgroundColor(WS_COLOR_LINE)
                }
                firstRunProgressSegments += segment
                addView(segment)
            }
        }
        firstRunOnboardingStatusText = TextView(this).apply {
            id = View.generateViewId()
            text = "첫 실행 등록 상태를 확인하는 중입니다."
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xffffe8bd.toInt())
            letterSpacing = 0.0f
            setLineSpacing(0f, 1.35f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = (WS_SECTION_GAP_DP * resources.displayMetrics.density).roundToInt()
                bottomMargin = (WS_CONTROL_GAP_DP * resources.displayMetrics.density).roundToInt()
            }
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ViewCompat.setAccessibilityHeading(firstRunOnboardingStatusText, true)
        firstRunPurposeButton = accessiblePriorityUserButton(
            label = "목적과 안전 한계 확인",
            emphasis = true,
            onClick = ::acknowledgeFirstRunPurposeAndSafety,
        )
        firstRunAgeButtons.clear()
        listOf(
            FirstRunAgeBand.ADULT_18_PLUS,
            FirstRunAgeBand.AGE_14_TO_17,
            FirstRunAgeBand.UNDER_14,
        ).forEach { ageBand ->
            val label = when (ageBand) {
                FirstRunAgeBand.ADULT_18_PLUS -> "만 18세 이상"
                FirstRunAgeBand.AGE_14_TO_17 -> "만 14세 이상 18세 미만"
                FirstRunAgeBand.UNDER_14 -> "만 14세 미만"
            }
            firstRunAgeButtons[ageBand] = accessiblePriorityUserButton(
                label = "가입 연령: $label",
                onClick = { selectFirstRunAgeBand(ageBand) },
            )
        }
        firstRunIntegratedConsentDisclosureText = TextView(this).apply {
            id = View.generateViewId()
            text = INTEGRATED_CONSENT_DISCLOSURE_KO
            textSize = 16f
            setTextColor(0xffffffff.toInt())
            setLineSpacing(0f, 1.45f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                bottomMargin = (WS_CONTROL_GAP_DP * resources.displayMetrics.density).roundToInt()
            }
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        firstRunIntegratedConsentButtons.clear()
        IntegratedConsentItem.entries.forEach { item ->
            firstRunIntegratedConsentButtons[item] = accessiblePriorityUserButton(
                label = item.name,
                onClick = {
                    updateIntegratedConsentDraft(
                        item,
                        !integratedConsentDraft.isGranted(item),
                    )
                },
            )
        }
        firstRunIntegratedConsentSaveButton = accessiblePriorityUserButton(
            label = "네 가지 선택을 서버에 저장하고 확인",
            emphasis = true,
            onClick = { persistIntegratedConsentDraft(announce = true) },
        )
        firstRunOnboardingControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(firstRunProgressBar)
            addView(firstRunOnboardingStatusText)
            addView(firstRunPurposeButton)
            listOf(
                FirstRunAgeBand.ADULT_18_PLUS,
                FirstRunAgeBand.AGE_14_TO_17,
                FirstRunAgeBand.UNDER_14,
            ).forEach { ageBand -> addView(firstRunAgeButtons.getValue(ageBand)) }
            addView(firstRunIntegratedConsentDisclosureText)
            IntegratedConsentItem.entries.forEach { item ->
                addView(firstRunIntegratedConsentButtons.getValue(item))
            }
            addView(firstRunIntegratedConsentSaveButton)
        }
        priorityUserOnboardingStatusText = TextView(this).apply {
            id = View.generateViewId()
            text = "최초 보행 전 교육 상태를 확인하는 중입니다."
            textSize = 18f
            setTextColor(0xffffffff.toInt())
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ViewCompat.setAccessibilityHeading(priorityUserOnboardingStatusText, true)
        priorityUserAgeButtons.clear()
        listOf(
            PriorityUserAgeBand.ADULT_18_PLUS,
            PriorityUserAgeBand.AGE_14_TO_17,
            PriorityUserAgeBand.UNDER_14,
        ).forEach { ageBand ->
            priorityUserAgeButtons[ageBand] = accessiblePriorityUserButton(
                label = "연령: ${ageBand.labelKo}",
                onClick = { selectPriorityUserAgeBand(ageBand) },
            )
        }
        priorityUserEducationButton = accessiblePriorityUserButton(
            label = "1. 안전 제한 안내 듣기",
            onClick = ::reviewPriorityUserSafetyEducation,
        )
        priorityUserSafePlaceButton = accessiblePriorityUserButton(
            label = "2. 안전한 연습 장소 확인",
            onClick = ::confirmPriorityUserSafePracticePlace,
        )
        priorityUserPracticeButtons.clear()
        PriorityUserPractice.entries.forEachIndexed { index, practice ->
            priorityUserPracticeButtons[practice] = accessiblePriorityUserButton(
                label = "${index + 3}. ${practice.actionLabelKo}",
                onClick = { performPriorityUserPractice(practice) },
            )
        }
        priorityUserResetButton = accessiblePriorityUserButton(
            label = "교육과 연습 다시 시작",
            onClick = ::resetPriorityUserTraining,
        )
        officialEnvironmentStatusText = TextView(this).apply {
            id = View.generateViewId()
            text = "공식 사용환경을 확인하는 중입니다."
            textSize = 18f
            setTextColor(0xffffe8bd.toInt())
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ViewCompat.setAccessibilityHeading(officialEnvironmentStatusText, true)
        officialEnvironmentConfirmButton = accessiblePriorityUserButton(
            label = OFFICIAL_ENVIRONMENT_CONFIRM_ACTION_KO,
            onClick = ::confirmOfficialEnvironmentConditions,
        )
        phoneMountingStatusText = TextView(this).apply {
            id = View.generateViewId()
            text = "휴대전화 장착 상태를 확인하는 중입니다."
            textSize = 18f
            setTextColor(0xffffe8bd.toInt())
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ViewCompat.setAccessibilityHeading(phoneMountingStatusText, true)
        phoneMountingChestConfirmButton = accessiblePriorityUserButton(
            label = "가슴형 정면 장착 확인",
            onClick = { confirmPhoneMounting(PhoneMountingMethod.CHEST_FORWARD) },
        )
        phoneMountingNecklaceConfirmButton = accessiblePriorityUserButton(
            label = "목걸이형 정면 장착 확인",
            onClick = { confirmPhoneMounting(PhoneMountingMethod.NECKLACE_FORWARD) },
        )
        startupCapabilityText = TextView(this).apply {
            text = "기기 기능을 확인하는 중입니다."
            textSize = 16f
            setTextColor(0xffffe8bd.toInt())
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        startupMetricPreflightButton = Button(this).apply {
            text = "기기 거리 기능 확인"
            isEnabled = false
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener { beginRuntimeMetricPreflight() }
        }
        startupCapabilityConfirmButton = Button(this).apply {
            text = "기기 기능 확인 중"
            isEnabled = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setOnClickListener {
                if (resumePermissionRecoveryFromExplicitUserAction()) {
                    return@setOnClickListener
                }
                val session = walkSessionLifecycle.snapshot()
                startupCapabilityRetryRequiresUserAction = false
                if (
                    session.state == WalkSessionState.SAFE_STOP ||
                    session.state == WalkSessionState.ENDED
                ) {
                    startFreshWalk("user_requested_after_${session.state.name.lowercase(Locale.US)}")
                    refreshStartupCapabilityUi()
                } else if (session.state == WalkSessionState.PAUSED) {
                    walkSessionResumePromptPending = false
                    walkSessionResumeRetryRequiresUserAction = false
                    if (isStartupCapabilityConfirmed()) {
                        refreshStartupCapabilityUi()
                    } else {
                        confirmStartupCapabilityDecision()
                    }
                } else {
                    confirmStartupCapabilityDecision()
                }
            }
        }
        safetySummaryText = TextView(this).apply {
            text = getString(R.string.walk_safety_preparing)
            textSize = 20f
            setTextColor(0xffffffff.toInt())
            contentDescription = text
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ViewCompat.setAccessibilityHeading(safetySummaryText, true)
        statusText = TextView(this).apply {
            textSize = 20f
            setTextColor(0xffffffff.toInt())
            contentDescription = "WalkSafe 상태"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            // This text changes every 500 ms. Safety speech uses explicit announcements below.
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_NONE
        }
        detailText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xffd7e0ff.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        navigationStatusText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xffd7ffd9.toInt())
            text = "navigation=destination_none hazard_only"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        loginUserIdInput = EditText(this).apply {
            hint = "교육 대상 로그인 ID"
            textSize = 16f
            setSingleLine(true)
            setText(reporterUserId.orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        loginSaveButton = Button(this).apply {
            setOnClickListener { persistReporterUserFromInput() }
        }
        accountLogoutButton = Button(this).apply {
            text = "사용자 로그아웃"
            setOnClickListener { onAccountLogoutClicked() }
        }
        updateLoginButtonText()
        priorityUserOnboardingControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(priorityUserOnboardingStatusText)
            addView(loginUserIdInput)
            addView(loginSaveButton)
            addView(accountLogoutButton)
            listOf(
                PriorityUserAgeBand.ADULT_18_PLUS,
                PriorityUserAgeBand.AGE_14_TO_17,
                PriorityUserAgeBand.UNDER_14,
            ).forEach { ageBand -> addView(priorityUserAgeButtons.getValue(ageBand)) }
            addView(priorityUserEducationButton)
            addView(priorityUserSafePlaceButton)
            PriorityUserPractice.entries.forEach { practice ->
                addView(priorityUserPracticeButtons.getValue(practice))
            }
            addView(priorityUserResetButton)
        }
        linkPriorityUserAccessibilityTraversal()
        updatePriorityUserOnboardingUi()
        permissionDenialPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            permissionDenialSummaryText = TextView(this@MainActivity).apply {
                id = View.generateViewId()
                isFocusable = true
                isFocusableInTouchMode = true
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
            }
            permissionDenialConfirmButton = Button(this@MainActivity).apply {
                id = View.generateViewId()
                text = "확인"
                accessibilityTraversalAfter = permissionDenialSummaryText.id
                setOnClickListener { onPermissionDenialConfirmed() }
            }
            permissionDenialSettingsButton = Button(this@MainActivity).apply {
                id = View.generateViewId()
                text = "앱 설정 열기"
                accessibilityTraversalAfter = permissionDenialConfirmButton.id
                setOnClickListener { openAppSettings() }
            }
            addView(permissionDenialSummaryText)
            addView(permissionDenialConfirmButton)
            addView(permissionDenialSettingsButton)
        }
        actionButton = Button(this).apply {
            text = "ARCore Depth 시작"
            isEnabled = false
            setOnClickListener {
                when (actionMode) {
                    ActionMode.START -> {
                        if (!resumePermissionRecoveryFromExplicitUserAction()) {
                            ensurePermissionsThenStart()
                        }
                    }
                    ActionMode.OPEN_SETTINGS -> openAppSettings()
                }
            }
        }
        if (BuildConfig.DEBUG) {
        debugUploadButton = Button(this).apply {
            setOnClickListener {
                if (!sensitiveDebugTransferAllowed()) {
                    metadataLogUploader.setEnabled(false)
                    updateDebugUploadButton()
                    speakInteraction("현재 원본 수집 동의와 네트워크 전송 조건을 서버에서 확인해야 합니다.")
                    return@setOnClickListener
                }
                metadataLogUploader.setEnabled(!metadataLogUploader.isEnabled())
                updateDebugUploadButton()
            }
        }
        updateDebugUploadButton()
        debugFrameCaptureButton = Button(this).apply {
            text = "이미지 1장 캡쳐"
            setOnClickListener {
                if (!sensitiveDebugTransferAllowed()) {
                    frameCaptureRequested.set(false)
                    updateFrameCaptureButton()
                    speakInteraction("현재 원본 수집 동의와 네트워크 전송 조건을 서버에서 확인해야 합니다.")
                    return@setOnClickListener
                }
                frameCaptureRequested.set(true)
                updateFrameCaptureButton()
            }
        }
        updateFrameCaptureButton()
        fieldSessionLogButton = Button(this).apply {
            setOnClickListener { toggleFieldSessionLog() }
        }
        updateFieldSessionLogButton()
        }
        privacyConsentStatusText = TextView(this).apply {
            id = View.generateViewId()
            textSize = 16f
            setTextColor(0xffffe8bd.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ViewCompat.setAccessibilityHeading(privacyConsentStatusText, true)
        reportPrivacyDisclosureText = TextView(this).apply {
            text = REPORT_PRIVACY_DISCLOSURE_KO
            textSize = 13f
            setTextColor(0xffffe8bd.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        reportPrivacyConsentButton = Button(this).apply {
            setOnClickListener { onReportPrivacyConsentButtonClicked() }
        }
        updateReportPrivacyConsentUi()
        automaticReportConsentButton = Button(this).apply {
            setOnClickListener { onAutomaticReportConsentButtonClicked() }
        }
        updateAutomaticReportConsentUi()
        mobileNetworkPreferenceButton = Button(this).apply {
            setOnClickListener { onMobileNetworkPreferenceButtonClicked() }
        }
        updateMobileNetworkPreferenceUi()
        trainingReuseConsentButton = Button(this).apply {
            setOnClickListener { onTrainingReuseConsentButtonClicked() }
        }
        updateTrainingReuseConsentUi()
        privacyRightsButton = Button(this).apply {
            text = "개인정보 열람·동의 철회·서버 자료 삭제 요청"
            setOnClickListener { openPrivacyRightsPage() }
        }
        accountDeletionStatusText = TextView(this).apply {
            id = View.generateViewId()
            textSize = 16f
            setTextColor(0xffffe8bd.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        ViewCompat.setAccessibilityHeading(accountDeletionStatusText, true)
        accountDeletionRequestButton = accessiblePriorityUserButton(
            label = "계정 삭제 요청",
            onClick = ::onAccountDeletionPrimaryClicked,
        )
        accountDeletionConfirmButton = accessiblePriorityUserButton(
            label = "계정과 개인정보 삭제 요청 확인",
            onClick = ::onAccountDeletionConfirmClicked,
        )
        accountDeletionCancelButton = accessiblePriorityUserButton(
            label = "계정 삭제 취소",
            onClick = ::onAccountDeletionCancelClicked,
        )
        accountDeletionRefreshButton = accessiblePriorityUserButton(
            label = "삭제 진행 상태 새로고침",
            onClick = ::refreshAccountDeletionStatus,
        )
        accountDeletionItemTexts.clear()
        DeletionInventoryItem.entries.forEach { item ->
            accountDeletionItemTexts[item] = TextView(this).apply {
                id = View.generateViewId()
                textSize = 14f
                setTextColor(0xffffffff.toInt())
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
        }
        updateAccountDeletionUi()
        explicitReportButton = Button(this).apply {
            text = "손상 점자블록 신고 요청"
            setOnClickListener { requestExplicitReport() }
        }
        voiceReportButton = Button(this).apply {
            text = "음성 명령"
            setOnClickListener { ensureVoicePermissionThenListen() }
        }
        debugBboxOverlay = DebugBboxOverlayView(this).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        if (BuildConfig.DEBUG) {
        backendUrlInput = EditText(this).apply {
            setText(gatewayFieldSession?.gatewayBaseUrl ?: configuredGatewayOriginOrNull().orEmpty())
            hint = "WalkSafe Gateway URL"
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isEnabled = BuildConfig.DEBUG
            isFocusable = BuildConfig.DEBUG
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        }
        backendFieldTokenInput = EditText(this).apply {
            hint = "현장 named-account token (저장 안 함)"
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        backendAuthApplyButton = Button(this).apply {
            setOnClickListener { onGatewaySessionButtonClicked() }
        }
        updateBackendAuthButtonText()
        destinationQueryInput = EditText(this).apply {
            hint = "목적지 검색"
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        destinationSearchButton = Button(this).apply {
            text = "목적지 검색"
            setOnClickListener {
                performDestinationSearch(reset = true)
            }
        }
        destinationCancelButton = Button(this).apply {
            text = "검색 취소"
            isEnabled = false
            setOnClickListener {
                cancelDestinationSearch()
            }
        }
        destinationSearchResultsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        destinationMoreButton = Button(this).apply {
            text = "결과 더보기"
            isEnabled = false
            visibility = View.GONE
            setOnClickListener {
                performDestinationSearch(reset = false)
            }
        }
        if (BuildConfig.DEBUG) {
        destinationLatInput = EditText(this).apply {
            hint = "목적지 위도"
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        destinationLngInput = EditText(this).apply {
            hint = "목적지 경도"
            textSize = 12f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        }
        routeButton = Button(this).apply {
            text = "경로 시작"
            setOnClickListener { onRouteButtonClicked() }
        }
        routeDeviationNewRouteButton = Button(this).apply {
            text = "새 경로 요청"
            contentDescription = text
            setOnClickListener { requestRerouteFromVoice() }
        }
        routeDeviationRecheckButton = Button(this).apply {
            text = "위치 다시 확인"
            contentDescription = text
            setOnClickListener { recheckLocationFromVoice() }
        }
        routeDeviationEndButton = Button(this).apply {
            text = "길안내 종료"
            contentDescription = text
            setOnClickListener { endNavigationAfterDeviation() }
        }
        routeDeviationActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(routeDeviationNewRouteButton)
            addView(routeDeviationRecheckButton)
            addView(routeDeviationEndButton)
        }
        destinationResetButton = Button(this).apply {
            text = "경로 초기화"
            setOnClickListener {
                if (!blockRouteMutationWhileDeviationChoicePending()) resetRouteState()
            }
        }
        progressBeepToggleButton = Button(this).apply {
            setOnClickListener {
                progressBeepEnabled = !progressBeepEnabled
                persistProgressBeepPrefs()
                updateProgressBeepButtons()
            }
        }
        progressBeepVolumeButton = Button(this).apply {
            setOnClickListener {
                progressBeepVolumePercent = nextProgressBeepVolume(progressBeepVolumePercent)
                persistProgressBeepPrefs()
                updateProgressBeepButtons()
            }
        }
        updateProgressBeepButtons()

        privacyControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(privacyConsentStatusText)
            addView(reportPrivacyDisclosureText)
            addView(reportPrivacyConsentButton)
            addView(automaticReportConsentButton)
            addView(mobileNetworkPreferenceButton)
            addView(trainingReuseConsentButton)
            addView(privacyRightsButton)
            addView(accountDeletionStatusText)
            addView(accountDeletionRequestButton)
            addView(accountDeletionConfirmButton)
            addView(accountDeletionCancelButton)
            addView(accountDeletionRefreshButton)
            addView(backendFieldTokenInput)
            addView(backendAuthApplyButton)
            DeletionInventoryItem.entries.forEach { item ->
                addView(accountDeletionItemTexts.getValue(item))
            }
        }
        runtimeControls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusText)
            addView(detailText)
            addView(navigationStatusText)
            addView(routeDeviationActions)
            addView(actionButton)
            if (BuildConfig.DEBUG) {
                addView(debugUploadButton)
                addView(debugFrameCaptureButton)
            }
            addView(explicitReportButton)
            addView(voiceReportButton)
            if (BuildConfig.DEBUG) {
                addView(backendUrlInput)
            }
            addView(destinationQueryInput)
            addView(destinationSearchButton)
            addView(destinationCancelButton)
            addView(destinationSearchResultsContainer)
            addView(destinationMoreButton)
            if (BuildConfig.DEBUG) {
                addView(destinationLatInput)
                addView(destinationLngInput)
            }
            addView(routeButton)
            addView(destinationResetButton)
            addView(progressBeepToggleButton)
            addView(progressBeepVolumeButton)
        }
        val overlayBottomPaddingPx =
            (OVERLAY_BOTTOM_PADDING_DP * resources.displayMetrics.density).toInt()
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(32, 48, 32, overlayBottomPaddingPx)
            setBackgroundColor(0x66000000)
            addView(productPurposeText)
            addView(firstRunNoticeToggleButton)
            addView(permissionDenialPanel)
            addView(firstRunOnboardingControls)
            addView(priorityUserOnboardingControls)
            addView(officialEnvironmentStatusText)
            addView(officialEnvironmentConfirmButton)
            addView(phoneMountingStatusText)
            addView(phoneMountingChestConfirmButton)
            addView(phoneMountingNecklaceConfirmButton)
            addView(startupCapabilityText)
            addView(startupMetricPreflightButton)
            addView(startupCapabilityConfirmButton)
            if (BuildConfig.DEBUG) {
                addView(fieldSessionLogButton)
            }
            addView(privacyControls)
            addView(runtimeControls)
        }
        walkSafetyOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(32, 48, 32, overlayBottomPaddingPx)
            setBackgroundColor(0x66000000)
            visibility = View.GONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            addView(safetySummaryText)
        }
        linkFirstRunAccessibilityTraversal()
        updateFirstRunOnboardingUi()

        controlsScroll = ScrollView(this).apply {
            isFillViewport = false
            isVerticalScrollBarEnabled = true
            addView(
                overlay,
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }

        return FrameLayout(this).apply {
            addView(surfaceView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(cameraFallbackPreviewView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(debugBboxOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(
                controlsScroll,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.TOP),
            )
            addView(
                walkSafetyOverlay,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP),
            )
        }
    }

    /**
     * 안전 고지는 1단계에서 전문이 펼쳐진 채로 확인 버튼보다 앞에 온다. 순서 자체가
     * 게이트이므로 스크롤 위치 같은 시각 전용 조건은 걸지 않는다. 사용자가 확인한 뒤에는
     * 접히되 삭제되지 않고, 접힌 줄은 정책 상수를 그대로 발췌한다.
     */
    /**
     * 단계 문장 앞머리("첫 실행 N단계.")는 eyebrow 로 작고 흐리게, 나머지는 제목으로
     * 크고 굵게 보인다. 한 TextView 안에서 처리하므로 text 와 contentDescription 은
     * 전체 문장 그대로 남고 접근성 heading 계약이 유지된다.
     */
    private fun applyStageHeadingStyle(message: String) {
        if (!::firstRunOnboardingStatusText.isInitialized) return
        val separator = message.indexOf('.')
        if (separator <= 0 || separator + 1 >= message.length) {
            firstRunOnboardingStatusText.text = message
            return
        }
        val eyebrowEnd = separator + 1
        // 표시용으로만 줄을 나눈다. contentDescription 은 원문 그대로이므로 낭독은 변하지 않는다.
        val display = message.substring(0, eyebrowEnd) + "\n" +
            message.substring(eyebrowEnd).trimStart()
        val styled = SpannableString(display)
        styled.setSpan(
            RelativeSizeSpan(0.62f),
            0,
            eyebrowEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        styled.setSpan(
            ForegroundColorSpan(WS_COLOR_NOTICE_TEXT),
            0,
            eyebrowEnd,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        styled.setSpan(
            StyleSpan(Typeface.BOLD),
            eyebrowEnd + 1,
            display.length,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        firstRunOnboardingStatusText.text = styled
    }

    private fun refreshFirstRunNoticeUi() {
        if (!::productPurposeText.isInitialized || !::firstRunNoticeToggleButton.isInitialized) return
        val acknowledged = !::firstRunOnboardingSnapshot.isInitialized ||
            firstRunOnboardingSnapshot.stage != FirstRunOnboardingStage.PURPOSE_AND_SAFETY
        val expanded = !acknowledged || firstRunNoticeExpandedByUser
        val version = "앱 버전: ${BuildConfig.VERSION_NAME}"
        productPurposeText.text = if (expanded) {
            "$WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO\n$version"
        } else {
            "안전 제한: $WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO"
        }
        productPurposeText.contentDescription = productPurposeText.text
        firstRunNoticeToggleButton.visibility = if (acknowledged) View.VISIBLE else View.GONE
        firstRunNoticeToggleButton.text =
            if (expanded) "안전 고지 접기" else "안전 고지 다시 보기"
        firstRunNoticeToggleButton.contentDescription = firstRunNoticeToggleButton.text
    }

    private fun linkFirstRunAccessibilityTraversal() {
        val controls = buildList<View> {
            add(firstRunOnboardingStatusText)
            add(firstRunPurposeButton)
            listOf(
                FirstRunAgeBand.ADULT_18_PLUS,
                FirstRunAgeBand.AGE_14_TO_17,
                FirstRunAgeBand.UNDER_14,
            ).forEach { ageBand -> add(firstRunAgeButtons.getValue(ageBand)) }
            add(firstRunIntegratedConsentDisclosureText)
            IntegratedConsentItem.entries.forEach { item ->
                add(firstRunIntegratedConsentButtons.getValue(item))
            }
            add(firstRunIntegratedConsentSaveButton)
        }
        controls.forEach { view ->
            if (view.id == View.NO_ID) view.id = View.generateViewId()
        }
        controls.zipWithNext().forEach { (previous, current) ->
            current.accessibilityTraversalAfter = previous.id
        }
        controls.filter(View::isFocusable).zipWithNext().forEach { (previous, current) ->
            previous.nextFocusForwardId = current.id
            previous.nextFocusDownId = current.id
            current.nextFocusUpId = previous.id
        }
    }

    private fun firstRunOnboardingComplete(): Boolean =
        ::firstRunOnboardingSnapshot.isInitialized &&
            firstRunOnboardingSnapshot.mayEnterWalk &&
            !accountDeletionStateMachine.processingBlocked()

    private fun firstRunDeviceCheckAllowsPreflight(): Boolean =
        ::firstRunOnboardingSnapshot.isInitialized &&
            (
                firstRunOnboardingSnapshot.stage ==
                    FirstRunOnboardingStage.DEVICE_CHECK ||
                    firstRunOnboardingComplete()
            )

    private fun maybeStartFirstRunDeviceCheckProbes() {
        if (!firstRunDeviceCheckAllowsPreflight()) return
        if (
            ::walkSessionResourceProbe.isInitialized &&
            !walkSessionResourceProbeStarted
        ) {
            walkSessionResourceProbeStarted = true
            walkSessionResourceProbe.start { refreshStartupCapabilityUi() }
        }
        if (
            ::startupCapabilityProbe.isInitialized &&
            !startupCapabilityProbeStarted
        ) {
            startupCapabilityProbeStarted = true
            startupCapabilityProbe.start()
        }
    }

    private fun currentFirstRunAsyncLease(): FirstRunAsyncLease =
        FirstRunAsyncLease(
            epoch = firstRunOnboardingSnapshot.epoch,
            revision = firstRunOnboardingSnapshot.revision,
            stage = firstRunOnboardingSnapshot.stage,
        )

    private fun isFirstRunAsyncLeaseCurrent(lease: FirstRunAsyncLease): Boolean =
        ::firstRunOnboardingSnapshot.isInitialized &&
            lease == currentFirstRunAsyncLease()

    private fun firstRunPermissionRequestAllowed(
        purpose: PermissionRequestPurpose,
    ): Boolean = when (purpose) {
        PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA ->
            firstRunDeviceCheckAllowsPreflight()
        PermissionRequestPurpose.WALK_SESSION,
        PermissionRequestPurpose.RUNTIME_CAMERA,
        PermissionRequestPurpose.NAVIGATION,
        PermissionRequestPurpose.VOICE_COMMAND,
        -> firstRunOnboardingComplete()
    }

    private fun requireFirstRunOnboardingComplete(
        reason: String,
        announce: Boolean = true,
    ): Boolean {
        if (firstRunOnboardingComplete()) return true
        val stage = if (::firstRunOnboardingSnapshot.isInitialized) {
            firstRunOnboardingSnapshot.stage.name.lowercase(Locale.US)
        } else {
            "uninitialized"
        }
        updateNavigationStatus("firstRun=blocked reason=$reason stage=$stage")
        updateStatus(
            "첫 실행 등록 필요",
            "목적·연령·동의·본인 확인·로그인·권한·기기점검·안전교육을 순서대로 완료해야 합니다.",
        )
        if (announce && !isScreenReaderActive()) {
            speakInteraction("첫 실행 등록과 안전교육을 먼저 완료하세요.")
        }
        return false
    }

    private fun firstRunLocalRequest(
        action: String,
    ): FirstRunOnboardingAttemptRequest {
        val snapshot = firstRunOnboardingSnapshot
        val requestIdentity = sha256Hex(
            (
                "FP010|request|$action|${snapshot.epoch}|${snapshot.revision}|" +
                    snapshot.stage.name
            ).toByteArray(),
        )
        val attemptIdentity = sha256Hex(
            (
                "FP010|attempt|$action|${snapshot.epoch}|${snapshot.revision}|" +
                    snapshot.stage.name
            ).toByteArray(),
        )
        return FirstRunOnboardingAttemptRequest.forSnapshot(
            snapshot = snapshot,
            requestId = "req_$requestIdentity",
            attemptId = "att_$attemptIdentity",
        )
    }

    private fun firstRunLocalReceipt(action: String): FirstRunReceiptHash {
        val snapshot = firstRunOnboardingSnapshot
        return FirstRunReceiptHash.fromSha256Hex(
            sha256Hex(
                "FP010|$action|${snapshot.epoch}|${snapshot.revision}|${snapshot.stage.name}"
                    .toByteArray(),
            ),
        )
    }

    private fun acknowledgeFirstRunPurposeAndSafety() {
        if (
            firstRunOnboardingSnapshot.stage !=
            FirstRunOnboardingStage.PURPOSE_AND_SAFETY
        ) return
        val transition = FirstRunOnboardingPolicy.acknowledgePurposeAndSafety(
            snapshot = firstRunOnboardingSnapshot,
            request = firstRunLocalRequest("purpose_and_safety"),
            receiptHash = firstRunLocalReceipt("purpose_and_safety"),
        )
        if (!transition.accepted) return
        firstRunOnboardingSnapshot = transition.current
        onFirstRunOnboardingStateChanged(
            announcement = "목적과 안전 한계를 확인했습니다. 연령 구간을 선택하세요.",
        )
    }

    private fun selectFirstRunAgeBand(ageBand: FirstRunAgeBand) {
        if (
            firstRunOnboardingSnapshot.stage !=
            FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED
        ) return
        val transition = FirstRunOnboardingPolicy.recordAgeAndGuardianNeed(
            snapshot = firstRunOnboardingSnapshot,
            request = firstRunLocalRequest("age_and_guardian_need_${ageBand.name}"),
            ageBand = ageBand,
            receiptHash = firstRunLocalReceipt("age_and_guardian_need_${ageBand.name}"),
        )
        if (!transition.accepted) return
        firstRunOnboardingSnapshot = transition.current
        val announcement = if (
            transition.current.stage ==
            FirstRunOnboardingStage.BLOCKED_UNDER_14
        ) {
            "만 14세 미만은 가입하거나 보행 기능을 사용할 수 없습니다."
        } else {
            "연령 구간을 확인했습니다. 네 가지 동의 항목을 각각 선택하고 서버 저장을 확인하세요."
        }
        onFirstRunOnboardingStateChanged(announcement)
    }

    private fun onFirstRunOnboardingStateChanged(announcement: String) {
        val rawWalkWasActive =
            ::walkSessionLifecycle.isInitialized &&
                walkSessionLifecycle.snapshot().state == WalkSessionState.ACTIVE
        invalidateRuntimeMetricEvidence("first_run_stage_changed")
        if (!firstRunOnboardingComplete()) {
            if (::gatewaySessionStore.isInitialized) {
                clearGatewaySession(logoutRemote = true)
            }
            if (rawWalkWasActive) {
                enterWalkSessionSafetyStopAndCancelOutputs(
                    "first_run_onboarding_incomplete",
                )
            }
        }
        bindFirstRunVerifiedActorForTraining()
        maybeStartFirstRunDeviceCheckProbes()
        updateFirstRunOnboardingUi()
        if (::startupCapabilityProbe.isInitialized) refreshStartupCapabilityUi()
        if (!isScreenReaderActive()) {
            speakInteraction(announcement)
        }
    }

    private fun bindFirstRunVerifiedActorForTraining() {
        val actorId =
            firstRunOnboardingSnapshot.verifiedActorBinding?.value ?: return
        if (
            reporterUserId == actorId &&
            priorityUserOnboardingActorId == actorId
        ) return
        invalidateOfficialEnvironmentEvidence("first_run_actor_bound")
        invalidatePhoneMountingEvidence("first_run_actor_bound")
        clearGatewaySession(logoutRemote = true)
        reporterUserId = actorId
        permissionSessionPolicy.rememberActor(actorId)
        restorePriorityUserOnboardingFromPrefs()
        val verifiedAgeBand = when (firstRunOnboardingSnapshot.ageBand) {
            FirstRunAgeBand.AGE_14_TO_17 -> PriorityUserAgeBand.AGE_14_TO_17
            FirstRunAgeBand.ADULT_18_PLUS -> PriorityUserAgeBand.ADULT_18_PLUS
            FirstRunAgeBand.UNDER_14,
            null,
            -> return
        }
        val restored = priorityUserOnboardingPolicy.snapshot()
        val compatible = restored.ageBand == verifiedAgeBand
        priorityUserOnboardingPolicy = PriorityUserOnboardingPolicy(
            PriorityUserOnboardingSnapshot(
                ageBand = verifiedAgeBand,
                guardianVerified =
                    verifiedAgeBand == PriorityUserAgeBand.AGE_14_TO_17 &&
                        FirstRunOnboardingStage.GUARDIAN_APPROVAL in
                        firstRunOnboardingSnapshot.completedReceiptHashes,
                educationReviewed = compatible && restored.educationReviewed,
                safePracticePlaceConfirmed =
                    compatible && restored.safePracticePlaceConfirmed,
                completedPractices = if (compatible) {
                    restored.completedPractices
                } else {
                    emptySet()
                },
            ),
        )
    }

    private fun updateFirstRunOnboardingUi() {
        if (
            !::firstRunOnboardingSnapshot.isInitialized ||
            !::firstRunOnboardingStatusText.isInitialized
        ) return
        val snapshot = firstRunOnboardingSnapshot
        val message = when (snapshot.stage) {
            FirstRunOnboardingStage.PURPOSE_AND_SAFETY ->
                "첫 실행 1단계. WalkSafe의 목적과 안전 한계를 읽고 확인하세요."
            FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED ->
                "첫 실행 2단계. 정확한 생년월일을 저장하지 않고 연령 구간만 확인합니다."
            FirstRunOnboardingStage.INTEGRATED_CONSENT ->
                "첫 실행 3단계. 네 가지 동의 항목을 각각 허용하거나 거부한 뒤 서버 저장 확인을 완료하세요."
            FirstRunOnboardingStage.LOCAL_CREDENTIAL_PHONE_SUBMISSION ->
                "첫 실행 4단계. 비밀번호나 전화번호를 앱에 저장하지 않고 운영 공급자의 불투명 제출 증거만 허용합니다."
            FirstRunOnboardingStage.VERIFIED_SMS ->
                "첫 실행 5단계. 운영 SMS 검증 증거를 기다립니다."
            FirstRunOnboardingStage.GUARDIAN_APPROVAL ->
                "첫 실행 6단계. 미성년 사용자에게 필요한 보호자 확인 증거를 기다립니다."
            FirstRunOnboardingStage.ACCOUNT_ACTIVATION ->
                "첫 실행 7단계. 운영 계정 활성화 증거를 기다립니다."
            FirstRunOnboardingStage.VERIFIED_LOGIN ->
                "첫 실행 8단계. 검증된 로그인 증거를 기다립니다."
            FirstRunOnboardingStage.JIT_PERMISSION_OBSERVATION ->
                "첫 실행 9단계. 기능 사용 직전에 현재 운영체제 권한 상태를 확인합니다."
            FirstRunOnboardingStage.DEVICE_CHECK ->
                "첫 실행 10단계. 보행 출력 없이 격리된 기기 기능 점검만 진행할 수 있습니다."
            FirstRunOnboardingStage.FP004_TRAINING ->
                "첫 실행 11단계. 기존 최초 보행 전 안전교육과 네 가지 조작 연습을 완료하세요."
            FirstRunOnboardingStage.COMPLETE ->
                "첫 실행 12단계 완료. 현재 권한과 안전 상태를 다시 확인한 뒤 보행 기능을 사용할 수 있습니다."
            FirstRunOnboardingStage.BLOCKED_UNDER_14 ->
                "가입 차단. 만 14세 미만은 계정을 만들거나 WalkSafe 보행 기능을 사용할 수 없습니다."
        }
        firstRunOnboardingStatusText.text = message
        firstRunOnboardingStatusText.contentDescription = message
        applyStageHeadingStyle(message)
        firstRunPurposeButton.visibility =
            if (snapshot.stage == FirstRunOnboardingStage.PURPOSE_AND_SAFETY) {
                View.VISIBLE
            } else {
                View.GONE
            }
        firstRunPurposeButton.isEnabled =
            snapshot.stage == FirstRunOnboardingStage.PURPOSE_AND_SAFETY
        firstRunAgeButtons.forEach { (_, button) ->
            button.visibility =
                if (snapshot.stage == FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
            button.isEnabled =
                snapshot.stage == FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED
        }
        val integratedConsentVisible =
            snapshot.stage == FirstRunOnboardingStage.INTEGRATED_CONSENT
        if (::firstRunIntegratedConsentDisclosureText.isInitialized) {
            firstRunIntegratedConsentDisclosureText.visibility =
                if (integratedConsentVisible) View.VISIBLE else View.GONE
        }
        firstRunIntegratedConsentButtons.values.forEach { button ->
            button.visibility =
                if (integratedConsentVisible) View.VISIBLE else View.GONE
        }
        if (::firstRunIntegratedConsentSaveButton.isInitialized) {
            firstRunIntegratedConsentSaveButton.visibility =
                if (integratedConsentVisible) View.VISIBLE else View.GONE
        }
        updateIntegratedConsentUi()

        val mayTrain =
            snapshot.stage == FirstRunOnboardingStage.FP004_TRAINING ||
                firstRunOnboardingComplete()
        val mayCheckDevice =
            snapshot.stage == FirstRunOnboardingStage.DEVICE_CHECK ||
                firstRunOnboardingComplete()
        val mayUseWalk = firstRunOnboardingComplete()
        if (::priorityUserOnboardingControls.isInitialized) {
            priorityUserOnboardingControls.visibility =
                if (mayTrain) View.VISIBLE else View.GONE
            priorityUserAgeButtons.values.forEach { button ->
                button.visibility = View.GONE
                button.isEnabled = false
            }
            loginUserIdInput.visibility = View.GONE
            loginSaveButton.visibility = View.GONE
            accountLogoutButton.visibility =
                if (firstRunOnboardingComplete()) View.VISIBLE else View.GONE
        }
        if (::officialEnvironmentStatusText.isInitialized) {
            officialEnvironmentStatusText.visibility =
                if (mayUseWalk) View.VISIBLE else View.GONE
            officialEnvironmentConfirmButton.visibility =
                if (mayUseWalk) View.VISIBLE else View.GONE
            phoneMountingStatusText.visibility =
                if (mayUseWalk) View.VISIBLE else View.GONE
            phoneMountingChestConfirmButton.visibility =
                if (mayUseWalk) View.VISIBLE else View.GONE
            phoneMountingNecklaceConfirmButton.visibility =
                if (mayUseWalk) View.VISIBLE else View.GONE
        }
        if (::startupCapabilityText.isInitialized) {
            startupCapabilityText.visibility =
                if (mayCheckDevice) View.VISIBLE else View.GONE
            startupMetricPreflightButton.visibility =
                if (mayCheckDevice) View.VISIBLE else View.GONE
            startupCapabilityConfirmButton.visibility =
                if (mayUseWalk) View.VISIBLE else View.GONE
        }
        if (::fieldSessionLogButton.isInitialized) {
            fieldSessionLogButton.visibility =
                if (mayUseWalk) View.VISIBLE else View.GONE
        }
        if (::runtimeControls.isInitialized && !mayUseWalk) {
            runtimeControls.visibility = View.GONE
        }
        refreshFirstRunNoticeUi()
        if (::firstRunProgressBar.isInitialized) {
            val completedStages = when (snapshot.stage) {
                FirstRunOnboardingStage.PURPOSE_AND_SAFETY -> 1
                FirstRunOnboardingStage.AGE_AND_GUARDIAN_NEED -> 2
                FirstRunOnboardingStage.INTEGRATED_CONSENT -> 3
                else -> FIRST_RUN_VISIBLE_STAGE_COUNT
            }
            firstRunProgressSegments.forEachIndexed { index, segment ->
                segment.setBackgroundColor(
                    if (index < completedStages) 0xffffe8bd.toInt() else WS_COLOR_LINE,
                )
            }
            firstRunProgressBar.visibility =
                if (firstRunOnboardingComplete()) View.GONE else View.VISIBLE
        }
        if (::privacyControls.isInitialized) {
            // 온보딩 중에는 설정·동의·계정 섹션을 접근성 트리에서 제거한다. 단계와 무관한
            // 컨트롤이 낭독 순서를 채우고, 안전 고지를 읽기 전에 동의 초안이 기록되는 것을 막는다.
            // 계정 삭제 복구 로그인은 온보딩 완료 전에도 필요하므로 예외로 둔다.
            privacyControls.visibility =
                if (firstRunOnboardingComplete() || accountDeletionRecoveryLoginRequired()) {
                    View.VISIBLE
                } else {
                    View.GONE
                }
        }
    }

    private fun linkPriorityUserAccessibilityTraversal() {
        val controls = buildList<View> {
            add(priorityUserOnboardingStatusText)
            add(loginUserIdInput)
            add(loginSaveButton)
            add(accountLogoutButton)
            listOf(
                PriorityUserAgeBand.ADULT_18_PLUS,
                PriorityUserAgeBand.AGE_14_TO_17,
                PriorityUserAgeBand.UNDER_14,
            ).forEach { ageBand -> add(priorityUserAgeButtons.getValue(ageBand)) }
            add(priorityUserEducationButton)
            add(priorityUserSafePlaceButton)
            PriorityUserPractice.entries.forEach { practice ->
                add(priorityUserPracticeButtons.getValue(practice))
            }
            add(priorityUserResetButton)
        }
        controls.forEach { view ->
            if (view.id == View.NO_ID) view.id = View.generateViewId()
        }
        controls.zipWithNext().forEach { (previous, current) ->
            current.accessibilityTraversalAfter = previous.id
        }
        val focusableControls = controls.filter(View::isFocusable)
        focusableControls.zipWithNext().forEach { (previous, current) ->
            previous.nextFocusForwardId = current.id
            previous.nextFocusDownId = current.id
            current.nextFocusUpId = previous.id
        }
        loginUserIdInput.contentDescription = "교육 대상 로그인 ID 입력"
        loginSaveButton.contentDescription = "교육 대상 로그인 ID 확인"
        accountLogoutButton.contentDescription = "사용자 로그아웃"
    }

    private fun currentPriorityUserSupportEnvironment(
        decision: WalkSafeStartupCapabilityDecision? = startupCapabilityDecision,
    ): PriorityUserSupportEnvironment {
        val pending = decision?.pendingRequirements.orEmpty()
        val unavailable = decision?.unavailableRequirements.orEmpty()
        fun isAvailable(requirement: WalkSafeStartupRequirement): Boolean =
            decision != null && requirement !in pending && requirement !in unavailable
        return PriorityUserSupportEnvironment(
            screenReaderActive = isScreenReaderActive(),
            largeTextEnabled = resources.configuration.fontScale >= 1.3f,
            highContrastEnabled = Settings.Secure.getInt(
                contentResolver,
                "high_text_contrast_enabled",
                0,
            ) == 1,
            offlineKoreanVoiceAvailable = isAvailable(
                WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS,
            ),
            vibrationAvailable = isAvailable(WalkSafeStartupRequirement.VIBRATION),
        )
    }

    private fun updatePriorityUserOnboardingUi(
        capabilityDecision: WalkSafeStartupCapabilityDecision? = startupCapabilityDecision,
    ) {
        if (
            !::priorityUserOnboardingPolicy.isInitialized ||
            !::priorityUserOnboardingStatusText.isInitialized
        ) return
        val environment = currentPriorityUserSupportEnvironment(capabilityDecision)
        val snapshot = priorityUserOnboardingPolicy.snapshot()
        val decision = priorityUserOnboardingPolicy.evaluate(
            environment = environment,
            walkIsActive = isWalkSessionRuntimeActive(),
        )
        val accountBound =
            reporterUserId != null &&
                priorityUserOnboardingActorId == reporterUserId
        val trainingDeliveryInFlight =
            priorityUserEducationInFlight || priorityUserPracticeInFlight != null
        val accessibilityModes = buildList {
            if (environment.screenReaderActive) add("화면읽기 사용 중")
            if (environment.largeTextEnabled) add("큰 글자 사용 중")
            if (environment.highContrastEnabled) add("고대비 사용 중")
        }.ifEmpty { listOf("기본 표시 설정") }
        val remaining = decision.remainingPractices.joinToString(", ") {
            it.actionLabelKo.removeSuffix(" 연습")
        }
        val message = buildString {
            append("전맹·저시력 사용자를 같은 우선순위로 지원합니다.\n")
            append("연령: ${snapshot.ageBand.labelKo} · ${accessibilityModes.joinToString(" · ")}\n")
            if (accountBound) {
                append(decision.noticeKo)
            } else {
                append("먼저 교육 대상 로그인 ID를 입력하고 확인하세요.")
            }
            if (trainingDeliveryInFlight) {
                append("\n음성 재생과 진동 연습 확인이 끝날 때까지 기다리세요.")
            }
            if (remaining.isNotBlank()) append("\n남은 연습: $remaining")
        }
        priorityUserOnboardingStatusText.text = message
        priorityUserOnboardingStatusText.contentDescription = message
        priorityUserOnboardingStatusText.setTextColor(0xffffffff.toInt())
        priorityUserOnboardingControls.setBackgroundColor(
            if (environment.highContrastEnabled) {
                0xff000000.toInt()
            } else {
                0xcc000000.toInt()
            },
        )
        priorityUserAgeButtons.forEach { (ageBand, button) ->
            button.isEnabled = accountBound && !trainingDeliveryInFlight
            button.text = "연령: ${ageBand.labelKo}" +
                if (snapshot.ageBand == ageBand) " · 선택됨" else ""
            button.contentDescription = button.text
        }
        val accountEligible = accountBound && decision.mayActivateAccount
        priorityUserEducationButton.isEnabled =
            accountEligible && !trainingDeliveryInFlight
        priorityUserSafePlaceButton.isEnabled =
            accountEligible &&
                snapshot.educationReviewed &&
                !trainingDeliveryInFlight
        priorityUserPracticeButtons.forEach { (practice, button) ->
            button.isEnabled =
                accountEligible &&
                    snapshot.safePracticePlaceConfirmed &&
                    environment.offlineKoreanVoiceAvailable &&
                    environment.vibrationAvailable &&
                    snapshot.nextRequiredPractice == practice &&
                    !trainingDeliveryInFlight
            val completed = practice in snapshot.completedPractices
            val current = snapshot.nextRequiredPractice == practice
            button.text = practice.actionLabelKo +
                when {
                    completed -> " · 완료"
                    current -> " · 다음"
                    else -> " · 순서 대기"
                }
            button.contentDescription = button.text
        }
        priorityUserResetButton.isEnabled =
            accountBound &&
                !trainingDeliveryInFlight &&
                (
                    snapshot.educationReviewed ||
                        snapshot.safePracticePlaceConfirmed ||
                        snapshot.completedPractices.isNotEmpty()
                )
        loginUserIdInput.isEnabled = !trainingDeliveryInFlight
        loginSaveButton.isEnabled = !trainingDeliveryInFlight
        updateLoginButtonText()
    }

    private fun selectPriorityUserAgeBand(ageBand: PriorityUserAgeBand) {
        if (firstRunOnboardingSnapshot.ageBand != null) {
            updateStatus(
                "가입 연령 변경 불가",
                "첫 실행 등록에서 확인한 연령 구간을 안전교육 화면에서 바꿀 수 없습니다.",
            )
            return
        }
        if (
            reporterUserId == null ||
            priorityUserOnboardingActorId != reporterUserId
        ) {
            updateStatus(
                "교육 대상 ID 확인 필요",
                "연령대를 선택하기 전에 교육 대상 로그인 ID를 입력하고 확인하세요.",
            )
            return
        }
        cancelPendingPriorityUserTrainingFeedback()
        if (!beginPriorityUserProfileMutationOrFailClosed()) {
            updateStatus(
                "교육 상태 변경 실패",
                "변경 전 안전 표식을 저장하지 못해 현재 실행에서는 계정과 보행 기능을 열지 않습니다.",
            )
            enforcePriorityUserAccountEligibility()
            updatePriorityUserOnboardingUi()
            return
        }
        priorityUserOnboardingPolicy.selectAgeBand(ageBand)
        if (!persistPriorityUserOnboardingOrFailClosed()) {
            updateStatus(
                "교육 상태 저장 실패",
                "앱 전용 저장소를 확인할 수 없어 계정과 보행 기능을 열지 않습니다.",
            )
        }
        enforcePriorityUserAccountEligibility()
        updatePriorityUserOnboardingUi()
        updateLoginButtonText()
        updateBackendAuthButtonText()
        val notice = priorityUserOnboardingPolicy.evaluate(
            currentPriorityUserSupportEnvironment(),
        ).noticeKo
        speakInteraction(notice)
        if (::startupCapabilityProbe.isInitialized) refreshStartupCapabilityUi()
    }

    private fun reviewPriorityUserSafetyEducation() {
        val accountBlock = priorityUserOnboardingPolicy.accountBlockReason()
        val actorId = reporterUserId
        if (
            actorId == null ||
            priorityUserOnboardingActorId != actorId ||
            accountBlock != null
        ) {
            val notice = accountBlock?.noticeKo
                ?: "먼저 교육 대상 로그인 ID를 입력하고 확인하세요."
            speakInteraction(notice)
            return
        }
        if (priorityUserEducationInFlight || priorityUserPracticeInFlight != null) {
            updateStatus(
                "안전 제한 안내 재생 중",
                "현재 안내가 끝날 때까지 기다리세요.",
            )
            return
        }
        val policy = priorityUserOnboardingPolicy
        val generation = ++priorityUserTrainingGeneration
        priorityUserEducationInFlight = true
        updatePriorityUserOnboardingUi()
        updateStatus(
            "안전 제한 안내 재생 중",
            "오프라인 한국어 음성이 끝난 뒤에만 안내 완료를 기록합니다.",
        )
        val completed = {
            runOnUiThread {
                if (
                    !isPriorityUserTrainingDeliveryCurrent(
                        generation = generation,
                        actorId = actorId,
                        policy = policy,
                    ) ||
                    !priorityUserEducationInFlight
                ) {
                    return@runOnUiThread
                }
                if (!beginPriorityUserProfileMutationOrFailClosed()) {
                    priorityUserTrainingGeneration += 1L
                    priorityUserEducationInFlight = false
                    updateStatus(
                        "안전 제한 안내 저장 실패",
                        "변경 전 안전 표식을 저장하지 못해 현재 실행에서는 계정과 보행 기능을 열지 않습니다.",
                    )
                    updatePriorityUserOnboardingUi()
                    if (::startupCapabilityProbe.isInitialized) {
                        refreshStartupCapabilityUi()
                    }
                    return@runOnUiThread
                }
                policy.reviewSafetyEducation(
                    voicePlaybackCompleted = true,
                )
                priorityUserTrainingGeneration += 1L
                priorityUserEducationInFlight = false
                if (!persistPriorityUserOnboardingOrFailClosed()) {
                    updateStatus(
                        "안전 제한 안내 저장 실패",
                        "교육 완료를 저장하지 못해 보행 기능을 열지 않습니다.",
                    )
                }
                updatePriorityUserOnboardingUi()
                if (::startupCapabilityProbe.isInitialized) {
                    refreshStartupCapabilityUi()
                }
            }
        }
        val failed = {
            failPriorityUserTrainingDelivery(
                generation = generation,
                actorId = actorId,
                policy = policy,
                token = null,
                detail = "음성 재생이 끝나지 않아 안내 완료를 기록하지 않았습니다. 같은 버튼을 다시 누르세요.",
            )
        }
        val dispatch = ensureFeedbackActuator().speakPriorityUserTraining(
            message = "WalkSafe는 흰지팡이와 안내견을 대신하지 않습니다. " +
                "위험 안내를 들으면 멈추세요. 실제 도로가 아닌 안전한 장소에서 먼저 연습하세요.",
            onCompleted = completed,
            onFailed = failed,
        )
        if (dispatch != NavigationSpeechDispatchResult.ACCEPTED) {
            failed()
        }
    }

    private fun confirmPriorityUserSafePracticePlace() {
        val before = priorityUserOnboardingPolicy.snapshot()
        if (
            before.safePracticePlaceConfirmed ||
            !before.educationReviewed ||
            priorityUserOnboardingPolicy.accountBlockReason() != null
        ) {
            val notice = priorityUserOnboardingPolicy.evaluate(
                currentPriorityUserSupportEnvironment(),
            ).noticeKo
            speakInteraction(notice)
            return
        }
        if (!beginPriorityUserProfileMutationOrFailClosed()) {
            updateStatus(
                "안전한 연습 장소 저장 실패",
                "변경 전 안전 표식을 저장하지 못해 현재 실행에서는 계정과 보행 기능을 열지 않습니다.",
            )
            updatePriorityUserOnboardingUi()
            return
        }
        priorityUserOnboardingPolicy.confirmSafePracticePlace()
        val persisted = persistPriorityUserOnboardingOrFailClosed()
        if (!persisted) {
            updateStatus(
                "안전한 연습 장소 저장 실패",
                "확인 상태를 저장하지 못해 연습과 보행 기능을 열지 않습니다.",
            )
        }
        updatePriorityUserOnboardingUi()
        if (persisted) {
            speakInteraction("안전한 장소를 확인했습니다. 위험 안내 연습 버튼을 누르세요.")
        }
    }

    private fun performPriorityUserPractice(practice: PriorityUserPractice) {
        val actorId = reporterUserId
        val environment = currentPriorityUserSupportEnvironment()
        val before = priorityUserOnboardingPolicy.snapshot()
        val currentDecision = priorityUserOnboardingPolicy.evaluate(environment)
        if (
            actorId == null ||
            priorityUserOnboardingActorId != actorId ||
            currentDecision.accountBlockReason != null ||
            !before.educationReviewed ||
            !before.safePracticePlaceConfirmed ||
            !environment.offlineKoreanVoiceAvailable ||
            !environment.vibrationAvailable ||
            priorityUserEducationInFlight ||
            priorityUserPracticeInFlight != null
        ) {
            speakInteraction(
                if (actorId == null || priorityUserOnboardingActorId != actorId) {
                    "먼저 교육 대상 로그인 ID를 입력하고 확인하세요."
                } else {
                    currentDecision.noticeKo
                },
            )
            return
        }
        val policy = priorityUserOnboardingPolicy
        val token = policy.beginPractice(practice)
        if (token == null) {
            val next = before.nextRequiredPractice
            speakInteraction(
                next?.let { "먼저 ${it.actionLabelKo} 버튼을 누르세요." }
                    ?: "필수 조작 연습을 이미 완료했습니다.",
            )
            return
        }
        val generation = ++priorityUserTrainingGeneration
        priorityUserPracticeInFlight = practice
        updatePriorityUserOnboardingUi()
        updateStatus(
            "${practice.actionLabelKo} 진행 중",
            "직접 누른 연습 동작, TTS 재생 완료, 진동 요청 패턴 시간이 모두 확인돼야 기록합니다.",
        )
        val failed = {
            failPriorityUserTrainingDelivery(
                generation = generation,
                actorId = actorId,
                policy = policy,
                token = token,
                detail = "음성 재생 또는 진동 요청 패턴을 끝까지 확인하지 못해 연습을 기록하지 않았습니다.",
            )
        }
        val actuator = ensureFeedbackActuator()
        val vibrationAccepted = actuator.playPriorityUserTrainingVibration(
            onWindowElapsed = {
                recordPriorityUserPracticeDelivery(
                    generation = generation,
                    actorId = actorId,
                    policy = policy,
                    token = token,
                    signal =
                        PriorityUserPracticeDeliverySignal.VIBRATION_REQUEST_WINDOW_ELAPSED,
                )
            },
            onFailed = failed,
        )
        if (!vibrationAccepted) {
            failed()
            return
        }
        val speech = actuator.speakPriorityUserTraining(
            message = practice.instructionKo,
            onCompleted = {
                recordPriorityUserPracticeDelivery(
                    generation = generation,
                    actorId = actorId,
                    policy = policy,
                    token = token,
                    signal =
                        PriorityUserPracticeDeliverySignal.SPEECH_PLAYBACK_COMPLETED,
                )
            },
            onFailed = failed,
        )
        if (speech != NavigationSpeechDispatchResult.ACCEPTED) {
            failed()
        }
    }

    private fun recordPriorityUserPracticeDelivery(
        generation: Long,
        actorId: String,
        policy: PriorityUserOnboardingPolicy,
        token: PriorityUserPracticeAttemptToken,
        signal: PriorityUserPracticeDeliverySignal,
    ) {
        runOnUiThread {
            if (
                !isPriorityUserTrainingDeliveryCurrent(
                    generation = generation,
                    actorId = actorId,
                    policy = policy,
                )
            ) {
                return@runOnUiThread
            }
            val before = policy.snapshot()
            if (!beginPriorityUserProfileMutationOrFailClosed()) {
                priorityUserTrainingGeneration += 1L
                priorityUserPracticeInFlight = null
                policy.cancelPractice(token)
                feedbackActuator?.cancelPriorityUserTrainingFeedback()
                updateStatus(
                    "연습 결과 저장 실패",
                    "변경 전 안전 표식을 저장하지 못해 현재 실행에서는 계정과 보행 기능을 열지 않습니다.",
                )
                updatePriorityUserOnboardingUi()
                if (::startupCapabilityProbe.isInitialized) {
                    refreshStartupCapabilityUi()
                }
                return@runOnUiThread
            }
            val after = policy.recordPracticeDelivery(token, signal)
            val persisted = persistPriorityUserOnboardingOrFailClosed()
            if (!persisted) {
                priorityUserTrainingGeneration += 1L
                priorityUserPracticeInFlight = null
                policy.cancelPractice(token)
                feedbackActuator?.cancelPriorityUserTrainingFeedback()
                updateStatus(
                    "연습 결과 저장 실패",
                    "연습 상태를 안전하게 저장하지 못해 계정과 보행 기능을 열지 않습니다.",
                )
                updatePriorityUserOnboardingUi()
                if (::startupCapabilityProbe.isInitialized) {
                    refreshStartupCapabilityUi()
                }
                return@runOnUiThread
            }
            if (after.completedPractices == before.completedPractices) {
                return@runOnUiThread
            }
            val completedPractice = priorityUserPracticeInFlight
            priorityUserTrainingGeneration += 1L
            priorityUserPracticeInFlight = null
            updateStatus(
                "연습 완료",
                "${completedPractice?.actionLabelKo ?: "필수 조작 연습"}을 완료했습니다.",
            )
            updatePriorityUserOnboardingUi()
            val firstRunCompleted =
                after.trainingComplete &&
                    completeFirstRunFp004TrainingIfReady(actorId)
            if (after.trainingComplete && !firstRunCompleted) {
                speakInteraction("최초 보행 전 교육과 네 가지 조작 연습을 완료했습니다.")
            }
            if (::startupCapabilityProbe.isInitialized) refreshStartupCapabilityUi()
        }
    }

    private fun completeFirstRunFp004TrainingIfReady(actorId: String): Boolean {
        if (
            firstRunOnboardingSnapshot.stage !=
            FirstRunOnboardingStage.FP004_TRAINING
        ) return false
        if (firstRunOnboardingSnapshot.verifiedActorBinding?.value != actorId) return false
        if (priorityUserOnboardingActorId != actorId) return false
        if (!priorityUserOnboardingPolicy.snapshot().trainingComplete) return false
        val receipt = firstRunLocalReceipt("fp004_training_complete")
        val started = FirstRunOnboardingPolicy.beginAttempt(
            snapshot = firstRunOnboardingSnapshot,
            request = firstRunLocalRequest("fp004_training_complete"),
        )
        val token = started.current.pendingAttempt ?: return false
        val evidence = FirstRunOnboardingEvidence.Fp004Training(receipt)
        val completed = FirstRunOnboardingPolicy.completeAttempt(
            snapshot = started.current,
            token = token,
            evidence = evidence,
            verifier = FirstRunOnboardingEvidenceVerifier {
                    presentedToken,
                    presentedEvidence,
                ->
                presentedToken === token &&
                    presentedEvidence === evidence &&
                    priorityUserOnboardingActorId == actorId &&
                    priorityUserOnboardingPolicy.snapshot().trainingComplete
            },
        )
        if (!completed.accepted) return false
        firstRunOnboardingSnapshot = completed.current
        onFirstRunOnboardingStateChanged(
            "첫 실행 등록과 안전교육을 완료했습니다. 현재 권한과 기기 상태를 다시 확인하세요.",
        )
        return true
    }

    private fun failPriorityUserTrainingDelivery(
        generation: Long,
        actorId: String,
        policy: PriorityUserOnboardingPolicy,
        token: PriorityUserPracticeAttemptToken?,
        detail: String,
    ) {
        runOnUiThread {
            if (
                !isPriorityUserTrainingDeliveryCurrent(
                    generation = generation,
                    actorId = actorId,
                    policy = policy,
                )
            ) {
                return@runOnUiThread
            }
            priorityUserTrainingGeneration += 1L
            priorityUserEducationInFlight = false
            priorityUserPracticeInFlight = null
            policy.cancelPractice(token)
            feedbackActuator?.cancelPriorityUserTrainingFeedback()
            updateStatus("교육·연습 다시 필요", detail)
            updatePriorityUserOnboardingUi()
        }
    }

    private fun isPriorityUserTrainingDeliveryCurrent(
        generation: Long,
        actorId: String,
        policy: PriorityUserOnboardingPolicy,
    ): Boolean =
        generation == priorityUserTrainingGeneration &&
            actorId == reporterUserId &&
            actorId == priorityUserOnboardingActorId &&
            policy === priorityUserOnboardingPolicy

    private fun cancelPendingPriorityUserTrainingFeedback() {
        priorityUserTrainingGeneration += 1L
        priorityUserEducationInFlight = false
        priorityUserPracticeInFlight = null
        if (::priorityUserOnboardingPolicy.isInitialized) {
            priorityUserOnboardingPolicy.cancelPractice()
        }
        feedbackActuator?.cancelPriorityUserTrainingFeedback()
        if (::priorityUserOnboardingStatusText.isInitialized) {
            updatePriorityUserOnboardingUi()
        }
    }

    private fun persistPriorityUserOnboardingOrFailClosed(): Boolean {
        if (persistPriorityUserOnboarding()) return true
        priorityUserOnboardingActorId?.let { actorId ->
            priorityUserStorageBlockedActorHashes.add(
                priorityUserActorSha256(actorId),
            )
        }
        priorityUserOnboardingPolicy = PriorityUserOnboardingPolicy()
        return false
    }

    private fun resetPriorityUserTraining() {
        cancelPendingPriorityUserTrainingFeedback()
        invalidateOfficialEnvironmentEvidence("priority_user_training_reset")
        invalidatePhoneMountingEvidence("priority_user_training_reset")
        val actorId = priorityUserOnboardingActorId
        if (actorId == null || actorId != reporterUserId) {
            speakInteraction("먼저 교육 대상 로그인 ID를 입력하고 확인하세요.")
            return
        }
        val persisted = if (beginPriorityUserProfileMutationOrFailClosed()) {
            priorityUserOnboardingPolicy.resetTraining()
            persistPriorityUserOnboardingOrFailClosed()
        } else {
            false
        }
        if (!persisted) {
            updateStatus(
                "교육 재실행 저장 실패",
                "안전한 재실행 상태를 저장하지 못해 현재 실행에서는 계정과 보행 기능을 열지 않습니다.",
            )
        }
        val firstRunRestart =
            FirstRunOnboardingPolicy.restartFp004Training(
                firstRunOnboardingSnapshot,
            )
        if (firstRunRestart.accepted) {
            firstRunOnboardingSnapshot = firstRunRestart.current
            onFirstRunOnboardingStateChanged(
                "안전교육을 다시 완료해야 합니다. 완료 전까지 보행 기능을 사용하지 않습니다.",
            )
        } else if (isWalkSessionRuntimeActive()) {
            enterWalkSessionSafetyStopAndCancelOutputs(
                "priority_user_training_reset",
            )
            updateStatus(
                "교육 재실행 · 안전 중지",
                "교육과 네 가지 조작 연습을 다시 완료한 뒤 새 보행을 시작하세요.",
            )
        }
        updatePriorityUserOnboardingUi()
        if (!firstRunRestart.accepted) {
            speakInteraction("안전 제한 안내부터 다시 시작합니다.")
        }
        if (::startupCapabilityProbe.isInitialized) refreshStartupCapabilityUi()
    }

    private fun confirmOfficialEnvironmentConditions() {
        if (!isActivityForeground || !::walkSessionLifecycle.isInitialized) return
        if (!requireFirstRunOnboardingComplete("official_environment_confirmation")) return
        val snapshot = walkSessionLifecycle.snapshot()
        if (
            snapshot.state !in setOf(
                WalkSessionState.READY,
                WalkSessionState.PAUSED,
            )
        ) {
            updateStatus(
                "공식 사용환경 확인 불가",
                "새 보행 준비 또는 일시중지 재검사 상태에서 다시 확인하세요.",
            )
            return
        }
        officialEnvironmentUserConfirmation = OfficialEnvironmentUserConfirmation(
            epoch = snapshot.epoch,
            brightTime = EnvironmentEvidenceStatus.PASS,
            dryWeather = EnvironmentEvidenceStatus.PASS,
            noDenseFog = EnvironmentEvidenceStatus.PASS,
            ordinaryUrbanSidewalk = EnvironmentEvidenceStatus.PASS,
            noConstruction = EnvironmentEvidenceStatus.PASS,
            noSevereCrowding = EnvironmentEvidenceStatus.PASS,
            supportLimitsNoticeAcknowledged = EnvironmentEvidenceStatus.PASS,
        )
        officialEnvironmentGpsEvidence = null
        officialEnvironmentCameraEvidence = CameraFrameQualityPolicy.assess(
            currentEpoch = snapshot.epoch,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            observation = null,
            approvedProfile = CameraFrameQualityPolicy.productionProfile,
        ).asMeasuredEnvironmentEvidence()
        val decision = startupCapabilityDecision
        if (!hasLocationPermission()) {
            if (decision != null) maybeRequestMissingWalkSessionPermissions(decision)
            updateStatus(
                "정확한 위치 권한 필요",
                "환경 위치 품질을 확인하려면 정확한 위치 권한을 허용한 뒤 공식 사용환경을 다시 확인하세요.",
            )
        } else {
            requestOfficialEnvironmentGpsPreflight(snapshot.epoch)
        }
        updateOfficialEnvironmentUi()
        refreshStartupCapabilityUi()
        speakInteraction(
            "$OFFICIAL_ENVIRONMENT_SUPPORT_NOTICE_KO " +
                "현재 조건 확인을 기록했습니다. 위치와 카메라 품질도 승인 기준을 통과해야 합니다.",
        )
    }

    @SuppressLint("MissingPermission")
    private fun requestOfficialEnvironmentGpsPreflight(epoch: WalkRuntimeEpoch) {
        officialEnvironmentGpsCancellation?.cancel()
        officialEnvironmentGpsCancellation = null
        val generation = ++officialEnvironmentPreflightGeneration
        if (
            !isActivityForeground ||
            !hasLocationPermission() ||
            walkSessionLifecycle.snapshot().epoch != epoch ||
            walkSessionLifecycle.snapshot().state !in setOf(
                WalkSessionState.READY,
                WalkSessionState.PAUSED,
            )
        ) {
            return
        }
        val cancellation = CancellationTokenSource()
        officialEnvironmentGpsCancellation = cancellation
        fusedLocationClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY,
            cancellation.token,
        ).addOnSuccessListener(ContextCompat.getMainExecutor(this)) { location ->
            if (!isOfficialEnvironmentGpsPreflightCurrent(epoch, generation, cancellation)) {
                return@addOnSuccessListener
            }
            officialEnvironmentGpsCancellation = null
            val nowMs = SystemClock.elapsedRealtime()
            val observedAtMs = location?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                    it.elapsedRealtimeNanos / 1_000_000L
                } else {
                    nowMs
                }
            } ?: nowMs
            val accuracy = location?.takeIf(Location::hasAccuracy)?.accuracy
            val trusted = location?.let {
                LocationTrustPolicy.trustedOrNull(
                    latitude = it.latitude,
                    longitude = it.longitude,
                    accuracyM = accuracy,
                    elapsedRealtimeMs = observedAtMs,
                )
            }?.let { LocationTrustPolicy.freshOrNull(it, nowMs) }
            officialEnvironmentGpsEvidence = OfficialEnvironmentPolicy.assessGpsQuality(
                currentEpoch = epoch,
                nowElapsedRealtimeMs = nowMs,
                observation = GpsQualityObservation(
                    epoch = epoch,
                    observedAtElapsedRealtimeMs = observedAtMs,
                    trustedFixAvailable = trusted != null,
                    horizontalAccuracyMeters = accuracy?.toDouble(),
                ),
                approvedProfile = OfficialEnvironmentPolicy.productionProfile,
            )
            updateOfficialEnvironmentUi()
            refreshStartupCapabilityUi()
        }.addOnFailureListener(ContextCompat.getMainExecutor(this)) {
            if (!isOfficialEnvironmentGpsPreflightCurrent(epoch, generation, cancellation)) {
                return@addOnFailureListener
            }
            officialEnvironmentGpsCancellation = null
            officialEnvironmentGpsEvidence = OfficialEnvironmentPolicy.assessGpsQuality(
                currentEpoch = epoch,
                nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                observation = null,
                approvedProfile = OfficialEnvironmentPolicy.productionProfile,
            )
            updateOfficialEnvironmentUi()
            refreshStartupCapabilityUi()
        }
    }

    private fun isOfficialEnvironmentGpsPreflightCurrent(
        epoch: WalkRuntimeEpoch,
        generation: Long,
        cancellation: CancellationTokenSource,
    ): Boolean {
        val snapshot = walkSessionLifecycle.snapshot()
        return officialEnvironmentGpsCancellation === cancellation &&
            generation == officialEnvironmentPreflightGeneration &&
            snapshot.epoch == epoch &&
            snapshot.isForeground &&
            snapshot.state in setOf(WalkSessionState.READY, WalkSessionState.PAUSED) &&
            isActivityForeground
    }

    private fun currentOfficialEnvironmentAssessment(
        epoch: WalkRuntimeEpoch = walkSessionLifecycle.snapshot().epoch,
        nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ): OfficialEnvironmentAssessment = OfficialEnvironmentPolicy.assess(
        currentEpoch = epoch,
        nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        gpsQuality = officialEnvironmentGpsEvidence,
        cameraQuality = officialEnvironmentCameraEvidence,
        userConfirmation = officialEnvironmentUserConfirmation,
        approvedProfile = OfficialEnvironmentPolicy.productionProfile,
    )

    private fun officialEnvironmentReadiness(
        epoch: WalkRuntimeEpoch,
    ): Pair<WalkSessionReadinessStatus, String> {
        val snapshot = walkSessionLifecycle.snapshot()
        val runtimeGuard = officialEnvironmentRuntimeGuard
        if (
            snapshot.state == WalkSessionState.ACTIVE &&
            runtimeGuard != null &&
            runtimeGuard.epoch == epoch &&
            walkSessionLifecycle.isRuntimeEpochCurrent(epoch)
        ) {
            return when (runtimeGuard.decision().action) {
                OfficialEnvironmentRuntimeAction.CONTINUE,
                OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY,
                -> WalkSessionReadinessStatus.READY to ""
                OfficialEnvironmentRuntimeAction.SAFE_STOP ->
                    WalkSessionReadinessStatus.UNAVAILABLE to
                        "official_environment:runtime_retry_exhausted"
            }
        }
        val assessment = currentOfficialEnvironmentAssessment(epoch)
        if (OfficialEnvironmentPolicy.productionProfile == null) {
            return WalkSessionReadinessStatus.UNAVAILABLE to
                "official_environment:approved_profile_unavailable"
        }
        return when (assessment.support) {
            OfficialEnvironmentSupport.SUPPORTED ->
                WalkSessionReadinessStatus.READY to ""
            OfficialEnvironmentSupport.LIMITED ->
                WalkSessionReadinessStatus.PENDING to
                    "official_environment:unknown:" +
                    assessment.blockingFactors.joinToString(",") { it.name.lowercase(Locale.US) }
            OfficialEnvironmentSupport.UNSUPPORTED ->
                WalkSessionReadinessStatus.UNAVAILABLE to
                    "official_environment:unsupported:" +
                    assessment.blockingFactors.joinToString(",") { it.name.lowercase(Locale.US) }
        }
    }

    private fun officialEnvironmentBlockReason(
        epoch: WalkRuntimeEpoch = walkSessionLifecycle.snapshot().epoch,
    ): String? {
        val (status, _) = officialEnvironmentReadiness(epoch)
        if (status == WalkSessionReadinessStatus.READY) return null
        return officialEnvironmentStatusMessage(currentOfficialEnvironmentAssessment(epoch))
    }

    private fun updateOfficialEnvironmentUi() {
        if (
            !::officialEnvironmentStatusText.isInitialized ||
            !::officialEnvironmentConfirmButton.isInitialized ||
            !::walkSessionLifecycle.isInitialized
        ) return
        val snapshot = walkSessionLifecycle.snapshot()
        val assessment = currentOfficialEnvironmentAssessment(snapshot.epoch)
        val message = officialEnvironmentStatusMessage(assessment)
        officialEnvironmentStatusText.text = message
        officialEnvironmentStatusText.contentDescription = message
        officialEnvironmentConfirmButton.apply {
            isEnabled =
                OfficialEnvironmentPolicy.productionProfile != null &&
                isActivityForeground &&
                    snapshot.state in setOf(WalkSessionState.READY, WalkSessionState.PAUSED)
            text = when {
                OfficialEnvironmentPolicy.productionProfile == null ->
                    "승인된 환경 프로필 없음"
                officialEnvironmentUserConfirmation?.epoch == snapshot.epoch ->
                    "환경 조건 다시 확인"
                else -> OFFICIAL_ENVIRONMENT_CONFIRM_ACTION_KO
            }
            contentDescription = text
        }
    }

    private fun officialEnvironmentStatusMessage(
        assessment: OfficialEnvironmentAssessment,
    ): String {
        if (OfficialEnvironmentPolicy.productionProfile == null) {
            return "$OFFICIAL_ENVIRONMENT_SUPPORT_NOTICE_KO\n사용 불가\n" +
                "원인: 실제 기기·현장시험으로 승인된 카메라·환경 품질 프로필이 없습니다.\n" +
                "다음 행동: 승인 기록이 생길 때까지 WalkSafe 보행 안내를 시작하지 마세요."
        }
        val blocking = assessment.blockingFactors.joinToString(", ") { it.labelKo() }
        return when (assessment.support) {
            OfficialEnvironmentSupport.SUPPORTED ->
                "$OFFICIAL_ENVIRONMENT_SUPPORT_NOTICE_KO\n지원\n" +
                    "원인: 밝고 건조하며 짙은 안개가 없는 일반 도심 보도와 위치·카메라 품질을 확인했습니다.\n" +
                    "다음 행동: 신호와 차량 등 주변 안전을 계속 직접 확인하세요."
            OfficialEnvironmentSupport.LIMITED ->
                "$OFFICIAL_ENVIRONMENT_SUPPORT_NOTICE_KO\n제한\n" +
                    "원인: ${blocking.ifBlank { "공식 환경 조건" }}을 확인하지 못했습니다.\n" +
                    "다음 행동: 현재 조건을 직접 확인하고 환경 확인 버튼을 누른 뒤 위치·카메라 품질을 다시 검사하세요."
            OfficialEnvironmentSupport.UNSUPPORTED ->
                "$OFFICIAL_ENVIRONMENT_SUPPORT_NOTICE_KO\n사용 불가\n" +
                    "원인: ${blocking.ifBlank { "현재 환경" }}이 공식 지원 조건과 다릅니다.\n" +
                    "다음 행동: WalkSafe 보행 안내를 시작하지 말고 밝고 건조한 일반 도심 보도로 이동하세요."
        }
    }

    private fun OfficialEnvironmentFactor.labelKo(): String = when (this) {
        OfficialEnvironmentFactor.GPS_QUALITY -> "위치 품질"
        OfficialEnvironmentFactor.CAMERA_QUALITY -> "카메라 품질"
        OfficialEnvironmentFactor.BRIGHT_TIME -> "밝은 시간"
        OfficialEnvironmentFactor.DRY_WEATHER -> "비·눈 없음"
        OfficialEnvironmentFactor.NO_DENSE_FOG -> "짙은 안개 없음"
        OfficialEnvironmentFactor.ORDINARY_URBAN_SIDEWALK -> "일반 도심 보도"
        OfficialEnvironmentFactor.NO_CONSTRUCTION -> "공사구간 아님"
        OfficialEnvironmentFactor.NO_SEVERE_CROWDING -> "심한 혼잡 없음"
        OfficialEnvironmentFactor.SUPPORT_LIMITS_NOTICE_ACKNOWLEDGED ->
            "지원범위와 제한사항 고지 확인"
    }

    private fun invalidateOfficialEnvironmentEvidence(reason: String) {
        officialEnvironmentPreflightGeneration += 1L
        officialEnvironmentWatchdogGeneration += 1L
        officialEnvironmentGpsCancellation?.cancel()
        officialEnvironmentGpsCancellation = null
        officialEnvironmentUserConfirmation = null
        officialEnvironmentGpsEvidence = null
        officialEnvironmentCameraEvidence = null
        officialEnvironmentRuntimeGuard = null
        officialEnvironmentOutputsAllowed = false
        if (::fieldSessionLog.isInitialized) {
            fieldSessionLog.recordEvent(
                "official_environment_evidence_invalidated",
                mapOf("reason" to reason),
            )
        }
        if (::officialEnvironmentStatusText.isInitialized) {
            updateOfficialEnvironmentUi()
        }
    }

    private fun confirmPhoneMounting(method: PhoneMountingMethod) {
        if (!isActivityForeground || !::walkSessionLifecycle.isInitialized) return
        if (!requireFirstRunOnboardingComplete("phone_mounting_confirmation")) return
        val snapshot = walkSessionLifecycle.snapshot()
        val runtimeState = phoneMountingRuntimeState
        val activeCorrection =
            snapshot.state == WalkSessionState.ACTIVE &&
                runtimeState?.epoch == snapshot.epoch &&
                runtimeState.correctionRequiredSinceElapsedRealtimeMs != null &&
                walkSessionLifecycle.isRuntimeEpochCurrent(snapshot.epoch)
        if (
            snapshot.state !in setOf(
                WalkSessionState.READY,
                WalkSessionState.PAUSED,
            ) &&
            !activeCorrection
        ) {
            updateStatus(
                "휴대전화 장착 확인 불가",
                "새 보행 준비, 일시중지 재검사 또는 장착 교정 상태에서 다시 확인하세요.",
            )
            return
        }
        if (
            activeCorrection &&
            phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs != null
        ) {
            updateStatus(
                "휴대전화 장착 재검사 중",
                "교정 확인 뒤의 새 카메라 결과를 기다리고 있습니다.",
            )
            return
        }
        val confirmedAtMs = if (activeCorrection) {
            synchronized(phoneMountingObservationLock) {
                val value = SystemClock.elapsedRealtime()
                phoneMountingUserConfirmation = PhoneMountingUserConfirmation(
                    epoch = snapshot.epoch,
                    confirmedAtElapsedRealtimeMs = value,
                    method = method,
                    postFaultCorrectionConfirmed = true,
                )
                phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs = value
                phoneMountingAppliedFaultSequence = maxOf(
                    phoneMountingAppliedFaultSequence,
                    phoneMountingPendingFaultSequence,
                )
                value
            }
        } else {
            SystemClock.elapsedRealtime().also { value ->
                phoneMountingUserConfirmation = PhoneMountingUserConfirmation(
                    epoch = snapshot.epoch,
                    confirmedAtElapsedRealtimeMs = value,
                    method = method,
                    postFaultCorrectionConfirmed = false,
                )
            }
        }
        if (activeCorrection) {
            schedulePhoneMountingRuntimeRetryTimeout(
                epoch = snapshot.epoch,
                armedAtElapsedRealtimeMs = confirmedAtMs,
            )
            updatePhoneMountingUi()
            updateStatus(
                "휴대전화 장착 재검사 중",
                "교정 확인 뒤의 새 카메라 결과를 기다리고 있습니다.",
            )
            speakInteraction(
                "장착 교정을 확인했습니다. 새 카메라 검사를 진행합니다.",
            )
            return
        } else {
            updatePhoneMountingUi()
            refreshStartupCapabilityUi()
        }
        speakInteraction(
            "휴대전화가 앞을 향하도록 고정된 상태를 기록했습니다. " +
                "카메라 품질과 승인된 장착 기준도 통과해야 하며, " +
                "흰지팡이와 안내견을 대신하지 않습니다.",
        )
    }

    private fun currentPhoneMountingAssessment(
        epoch: WalkRuntimeEpoch = walkSessionLifecycle.snapshot().epoch,
        nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
        phase: PhoneMountingAssessmentPhase = PhoneMountingAssessmentPhase.PREFLIGHT,
        previousState: PhoneMountingRuntimeState? = null,
        cameraFrameQualityOverride: CameraFrameQualityAssessment? = null,
        runtimeRetryRequested: Boolean = false,
    ): PhoneMountingAssessment {
        val cameraFrameQuality =
            cameraFrameQualityOverride ?: latestPhoneMountingCameraAssessment
        return PhoneMountingPolicy.assess(
            phase = phase,
            currentEpoch = epoch,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            userConfirmation = phoneMountingUserConfirmation,
            cameraFrameQuality = cameraFrameQuality,
            previousState = previousState ?: PhoneMountingPolicy.initialState(
                epoch = epoch,
                approvedProfile = PhoneMountingPolicy.productionProfile,
            ),
            runtimeRetryRequested = runtimeRetryRequested,
        )
    }

    private fun phoneMountingReadiness(
        epoch: WalkRuntimeEpoch,
    ): Pair<WalkSessionReadinessStatus, String> {
        val snapshot = walkSessionLifecycle.snapshot()
        val runtimeState = phoneMountingRuntimeState
        if (
            snapshot.state == WalkSessionState.ACTIVE &&
            runtimeState != null &&
            runtimeState.epoch == epoch &&
            walkSessionLifecycle.isRuntimeEpochCurrent(epoch)
        ) {
            val runtimeAssessment = currentPhoneMountingAssessment(
                epoch = epoch,
                phase = PhoneMountingAssessmentPhase.ACTIVE,
                previousState = runtimeState,
            )
            return if (runtimeAssessment.requiresSafetyStop) {
                WalkSessionReadinessStatus.UNAVAILABLE to
                    "phone_mounting:${runtimeAssessment.reason.name.lowercase(Locale.US)}"
            } else {
                WalkSessionReadinessStatus.READY to ""
            }
        }
        val assessment = currentPhoneMountingAssessment(epoch)
        if (PhoneMountingPolicy.productionProfile == null) {
            return WalkSessionReadinessStatus.UNAVAILABLE to
                "phone_mounting:approved_profile_unavailable"
        }
        return when (assessment.status) {
            PhoneMountingStatus.SUITABLE ->
                WalkSessionReadinessStatus.READY to ""
            PhoneMountingStatus.CORRECTION_REQUIRED ->
                WalkSessionReadinessStatus.PENDING to
                    "phone_mounting:${assessment.reason.name.lowercase(Locale.US)}"
            PhoneMountingStatus.UNUSABLE ->
                WalkSessionReadinessStatus.UNAVAILABLE to
                    "phone_mounting:${assessment.reason.name.lowercase(Locale.US)}"
        }
    }

    private fun phoneMountingBlockReason(
        epoch: WalkRuntimeEpoch = walkSessionLifecycle.snapshot().epoch,
    ): String? {
        val (status, _) = phoneMountingReadiness(epoch)
        if (status == WalkSessionReadinessStatus.READY) return null
        return phoneMountingStatusMessage(currentPhoneMountingAssessment(epoch))
    }

    private fun updatePhoneMountingUi() {
        if (
            !::phoneMountingStatusText.isInitialized ||
            !::phoneMountingChestConfirmButton.isInitialized ||
            !::phoneMountingNecklaceConfirmButton.isInitialized ||
            !::walkSessionLifecycle.isInitialized
        ) return
        val snapshot = walkSessionLifecycle.snapshot()
        val runtimeState = phoneMountingRuntimeState
        val activeCorrection =
            snapshot.state == WalkSessionState.ACTIVE &&
                runtimeState?.epoch == snapshot.epoch &&
                runtimeState.correctionRequiredSinceElapsedRealtimeMs != null &&
                !runtimeState.safetyStopRequired
        val assessment = if (runtimeState?.epoch == snapshot.epoch) {
            currentPhoneMountingAssessment(
                epoch = snapshot.epoch,
                phase = PhoneMountingAssessmentPhase.ACTIVE,
                previousState = runtimeState,
            )
        } else {
            currentPhoneMountingAssessment(snapshot.epoch)
        }
        val message = phoneMountingStatusMessage(assessment)
        phoneMountingStatusText.text = message
        phoneMountingStatusText.contentDescription = message
        val buttonsEnabled =
            isActivityForeground &&
                (
                    snapshot.state in setOf(WalkSessionState.READY, WalkSessionState.PAUSED) ||
                        activeCorrection
                )
        updatePhoneMountingButton(
            button = phoneMountingChestConfirmButton,
            method = PhoneMountingMethod.CHEST_FORWARD,
            baseLabel = "가슴형 정면 장착",
            enabled = buttonsEnabled,
            activeCorrection = activeCorrection,
            epoch = snapshot.epoch,
        )
        updatePhoneMountingButton(
            button = phoneMountingNecklaceConfirmButton,
            method = PhoneMountingMethod.NECKLACE_FORWARD,
            baseLabel = "목걸이형 정면 장착",
            enabled = buttonsEnabled,
            activeCorrection = activeCorrection,
            epoch = snapshot.epoch,
        )
    }

    private fun updatePhoneMountingButton(
        button: Button,
        method: PhoneMountingMethod,
        baseLabel: String,
        enabled: Boolean,
        activeCorrection: Boolean,
        epoch: WalkRuntimeEpoch,
    ) {
        val runtimeRetryPending =
            activeCorrection &&
                phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs != null
        button.isEnabled = enabled && !runtimeRetryPending
        button.text = when {
            runtimeRetryPending ->
                "$baseLabel 카메라 재검사 중"
            activeCorrection -> "$baseLabel 교정 완료 후 재검사"
            phoneMountingUserConfirmation?.let {
                it.epoch == epoch && it.method == method
            } == true -> "$baseLabel 다시 확인"
            else -> "$baseLabel 확인"
        }
        button.contentDescription = button.text
    }

    private fun phoneMountingStatusMessage(
        assessment: PhoneMountingAssessment,
    ): String {
        val status = when (assessment.status) {
            PhoneMountingStatus.SUITABLE -> "적합"
            PhoneMountingStatus.CORRECTION_REQUIRED -> "교정 필요"
            PhoneMountingStatus.UNUSABLE -> "사용 불가"
        }
        return "가슴형 또는 목걸이형 거치대에 렌즈를 정면으로 고정하세요. " +
            "손에 들거나 주머니에 넣지 마세요. " +
            "WalkSafe는 흰지팡이·안내견 등 기존 보조수단을 대신하지 않습니다.\n" +
            "$status\n원인: ${assessment.accessibleReasonKo}\n" +
            "다음 행동: ${assessment.accessibleActionKo}"
    }

    private fun invalidatePhoneMountingEvidence(reason: String) {
        synchronized(phoneMountingObservationLock) {
            phoneMountingObservationGeneration += 1L
            latestPhoneMountingCameraAssessment = null
            phoneMountingObservationSequence = 0L
            phoneMountingPendingFaultSequence = 0L
            phoneMountingAppliedFaultSequence = 0L
            phoneMountingOutputsAllowed = false
            phoneMountingRuntimeRetryGeneration += 1L
            phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs = null
        }
        phoneMountingWatchdogGeneration += 1L
        phoneMountingUserConfirmation = null
        phoneMountingRuntimeState = null
        if (::fieldSessionLog.isInitialized) {
            fieldSessionLog.recordEvent(
                "phone_mounting_evidence_invalidated",
                mapOf("reason" to reason),
            )
        }
        if (::phoneMountingStatusText.isInitialized) {
            updatePhoneMountingUi()
        }
    }

    private fun observeOfficialEnvironmentCameraFrame(
        epoch: WalkRuntimeEpoch,
        observedAtElapsedRealtimeMs: Long,
        frameAvailable: Boolean?,
    ) {
        val observationGeneration = phoneMountingObservationGeneration
        if (walkSessionLifecycle.snapshot().epoch != epoch) return
        val previous = officialEnvironmentCameraEvidence
        val cameraAssessment = CameraFrameQualityPolicy.assess(
            currentEpoch = epoch,
            nowElapsedRealtimeMs = observedAtElapsedRealtimeMs,
            observation = CameraFrameQualityObservation(
                epoch = epoch,
                observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
                frameAvailable = frameAvailable,
                normalizedBrightness = null,
                occludedFraction = null,
                angularShakeDegreesPerSecond = null,
                mountPitchDegrees = null,
            ),
            approvedProfile = CameraFrameQualityPolicy.productionProfile,
        )
        val mountingProfile = PhoneMountingPolicy.productionProfile
        val mountingFault =
            cameraAssessment.status != EnvironmentEvidenceStatus.PASS ||
                mountingProfile == null ||
                cameraAssessment.profileId != mountingProfile.cameraFrameQualityProfileId
        val observationSequence = synchronized(phoneMountingObservationLock) {
            if (observationGeneration != phoneMountingObservationGeneration) {
                null
            } else {
                phoneMountingObservationSequence += 1L
                val sequence = phoneMountingObservationSequence
                val latestObservedAt =
                    latestPhoneMountingCameraAssessment?.observedAtElapsedRealtimeMs
                val currentObservedAt = cameraAssessment.observedAtElapsedRealtimeMs
                if (
                    currentObservedAt != null &&
                    (latestObservedAt == null || currentObservedAt >= latestObservedAt)
                ) {
                    latestPhoneMountingCameraAssessment = cameraAssessment
                }
                if (
                    mountingFault &&
                    phoneMountingRuntimeState?.epoch == epoch
                ) {
                    phoneMountingOutputsAllowed = false
                    val retryArmedAt =
                        phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs
                    if (
                        retryArmedAt != null &&
                        currentObservedAt != null &&
                        currentObservedAt <= retryArmedAt
                    ) {
                        phoneMountingAppliedFaultSequence = maxOf(
                            phoneMountingAppliedFaultSequence,
                            sequence,
                        )
                    } else {
                        phoneMountingPendingFaultSequence = maxOf(
                            phoneMountingPendingFaultSequence,
                            sequence,
                        )
                    }
                }
                sequence
            }
        }
        if (observationSequence == null || walkSessionLifecycle.snapshot().epoch != epoch) return
        val evidence = cameraAssessment.asMeasuredEnvironmentEvidence()
        officialEnvironmentCameraEvidence = evidence
        val shouldReassessRuntime =
            (
                evidence.status == EnvironmentEvidenceStatus.PASS &&
                    previous?.status != EnvironmentEvidenceStatus.PASS
            ) || (
                officialEnvironmentOutputsAllowed &&
                    evidence.status != EnvironmentEvidenceStatus.PASS
            )
        runOnUiThread {
            if (
                observationGeneration != phoneMountingObservationGeneration ||
                walkSessionLifecycle.snapshot().epoch != epoch
            ) return@runOnUiThread
            if (isWalkSessionRuntimeActive()) {
                val retryArmedAt =
                    phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs
                val runtimeRetryRequested =
                    retryArmedAt != null &&
                        cameraAssessment.observedAtElapsedRealtimeMs?.let {
                            it > retryArmedAt
                        } == true
                val assessment = applyCurrentPhoneMountingRuntimeAssessment(
                    runtimeRetryRequested = runtimeRetryRequested,
                    cameraFrameQualityOverride = cameraAssessment,
                    observationSequence = observationSequence,
                    observationGeneration = observationGeneration,
                    observationIsFault = mountingFault,
                )
                if (runtimeRetryRequested && assessment != null) {
                    synchronized(phoneMountingObservationLock) {
                        if (
                            phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs ==
                            retryArmedAt
                        ) {
                            phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs = null
                            phoneMountingRuntimeRetryGeneration += 1L
                        }
                    }
                    updatePhoneMountingUi()
                }
                if (mountingFault && assessment != null) {
                    synchronized(phoneMountingObservationLock) {
                        if (observationGeneration == phoneMountingObservationGeneration) {
                            phoneMountingAppliedFaultSequence = maxOf(
                                phoneMountingAppliedFaultSequence,
                                observationSequence,
                            )
                        }
                    }
                }
                if (shouldReassessRuntime && walkSessionLifecycle.isRuntimeEpochCurrent(epoch)) {
                    applyCurrentOfficialEnvironmentRuntimeAssessment()
                }
            } else {
                updatePhoneMountingUi()
                refreshStartupCapabilityUi()
            }
        }
    }

    private fun recordOfficialEnvironmentGpsObservation(
        epoch: WalkRuntimeEpoch,
        observedAtElapsedRealtimeMs: Long,
        trustedFixAvailable: Boolean?,
        horizontalAccuracyMeters: Double?,
    ) {
        if (walkSessionLifecycle.snapshot().epoch != epoch) return
        val previous = officialEnvironmentGpsEvidence
        val evidence = OfficialEnvironmentPolicy.assessGpsQuality(
            currentEpoch = epoch,
            nowElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            observation = GpsQualityObservation(
                epoch = epoch,
                observedAtElapsedRealtimeMs = observedAtElapsedRealtimeMs,
                trustedFixAvailable = trustedFixAvailable,
                horizontalAccuracyMeters = horizontalAccuracyMeters,
            ),
            approvedProfile = OfficialEnvironmentPolicy.productionProfile,
        )
        officialEnvironmentGpsEvidence = evidence
        val shouldReassessRuntime =
            (
                evidence.status == EnvironmentEvidenceStatus.PASS &&
                    previous?.status != EnvironmentEvidenceStatus.PASS
            ) || (
                officialEnvironmentOutputsAllowed &&
                    evidence.status != EnvironmentEvidenceStatus.PASS
            )
        if (isWalkSessionRuntimeActive() && shouldReassessRuntime) {
            applyCurrentOfficialEnvironmentRuntimeAssessment()
        }
    }

    private fun activateOfficialEnvironmentRuntime(): Boolean {
        val epoch = walkSessionLifecycle.currentRuntimeEpochOrNull() ?: return false
        val guard = OfficialEnvironmentRuntimeGuard(
            epoch = epoch,
            approvedProfile = OfficialEnvironmentPolicy.productionProfile,
        )
        officialEnvironmentRuntimeGuard = guard
        val nowMs = SystemClock.elapsedRealtime()
        val runtimeDecision = guard.onAssessment(
            assessment = currentOfficialEnvironmentAssessment(epoch, nowMs),
            nowElapsedRealtimeMs = nowMs,
        )
        applyOfficialEnvironmentRuntimeDecision(runtimeDecision)
        return runtimeDecision.action != OfficialEnvironmentRuntimeAction.SAFE_STOP
    }

    private fun activatePhoneMountingRuntime(): Boolean {
        val epoch = walkSessionLifecycle.currentRuntimeEpochOrNull() ?: return false
        val initialState = PhoneMountingPolicy.initialState(
            epoch = epoch,
            approvedProfile = PhoneMountingPolicy.productionProfile,
        )
        phoneMountingRuntimeState = initialState
        val assessment = currentPhoneMountingAssessment(
            epoch = epoch,
            phase = PhoneMountingAssessmentPhase.ACTIVE,
            previousState = initialState,
        )
        phoneMountingRuntimeState = assessment.nextState
        applyPhoneMountingRuntimeAssessment(
            assessment = assessment,
            announceCorrection =
                assessment.status == PhoneMountingStatus.CORRECTION_REQUIRED,
            outputsAlreadyRevoked = false,
        )
        return assessment.status != PhoneMountingStatus.UNUSABLE
    }

    private fun applyCurrentPhoneMountingRuntimeAssessment(
        runtimeRetryRequested: Boolean,
        cameraFrameQualityOverride: CameraFrameQualityAssessment? = null,
        observationSequence: Long? = null,
        observationGeneration: Long? = null,
        observationIsFault: Boolean = false,
    ): PhoneMountingAssessment? {
        val previousState = phoneMountingRuntimeState ?: return null
        if (!walkSessionLifecycle.isRuntimeEpochCurrent(previousState.epoch)) return null
        val transition = synchronized(phoneMountingObservationLock) {
            if (phoneMountingRuntimeState !== previousState) return@synchronized null
            if (observationSequence != null) {
                if (
                    observationGeneration != phoneMountingObservationGeneration ||
                    (
                        observationIsFault &&
                            observationSequence <= phoneMountingAppliedFaultSequence
                    ) ||
                    (
                        !observationIsFault &&
                            (
                                phoneMountingPendingFaultSequence >
                                    phoneMountingAppliedFaultSequence ||
                                    observationSequence <=
                                    phoneMountingAppliedFaultSequence
                            )
                    )
                ) {
                    return@synchronized null
                }
            }
            val assessment = currentPhoneMountingAssessment(
                epoch = previousState.epoch,
                phase = PhoneMountingAssessmentPhase.ACTIVE,
                previousState = previousState,
                cameraFrameQualityOverride = cameraFrameQualityOverride,
                runtimeRetryRequested = runtimeRetryRequested,
            )
            val firstFault =
                previousState.correctionRequiredSinceElapsedRealtimeMs == null &&
                    assessment.nextState.correctionRequiredSinceElapsedRealtimeMs != null
            val outputsRevoked = assessment.status != PhoneMountingStatus.SUITABLE
            if (outputsRevoked) revokePhoneMountingReportOutputLease()
            phoneMountingRuntimeState = assessment.nextState
            Triple(assessment, firstFault, outputsRevoked)
        } ?: return null
        val (assessment, firstFault, outputsRevoked) = transition
        applyPhoneMountingRuntimeAssessment(
            assessment = assessment,
            announceCorrection = firstFault || runtimeRetryRequested,
            outputsAlreadyRevoked = outputsRevoked,
        )
        return assessment
    }

    private fun revokePhoneMountingReportOutputLease() {
        synchronized(phoneMountingObservationLock) {
            phoneMountingOutputsAllowed = false
            synchronized(reportUploadSafetyLock) {
                reportUploadSafetyGeneration += 1L
                reportPrivacyConsentSession.cancelActiveCalls()
            }
        }
    }

    private fun applyPhoneMountingRuntimeAssessment(
        assessment: PhoneMountingAssessment,
        announceCorrection: Boolean,
        outputsAlreadyRevoked: Boolean,
    ) {
        val epoch = assessment.nextState.epoch
        if (!walkSessionLifecycle.isRuntimeEpochCurrent(epoch)) return
        if (
            assessment.status != PhoneMountingStatus.SUITABLE &&
            !outputsAlreadyRevoked
        ) {
            revokePhoneMountingReportOutputLease()
        }
        when (assessment.status) {
            PhoneMountingStatus.SUITABLE -> {
                val outputEnabled = synchronized(phoneMountingObservationLock) {
                    if (
                        phoneMountingPendingFaultSequence >
                        phoneMountingAppliedFaultSequence
                    ) {
                        false
                    } else {
                        phoneMountingOutputsAllowed = true
                        true
                    }
                }
                if (!outputEnabled) return
                schedulePhoneMountingEvidenceWatchdog(epoch)
                updatePhoneMountingUi()
            }
            PhoneMountingStatus.CORRECTION_REQUIRED -> {
                phoneMountingOutputsAllowed = false
                phoneMountingWatchdogGeneration += 1L
                if (announceCorrection) {
                    invalidatePhoneMountingDetectionOutputs(epoch)
                }
                clearExplicitReportFrameState("phone_mounting_correction")
                cancelActiveRouteRequest()
                cancelDestinationSearch()
                latestFeedbackDeliveryState = FeedbackDeliveryState()
                feedbackPolicy.cancelPendingFeedbackDeliveries()
                if (announceCorrection) {
                    cancelVoiceCommandRecognition()
                    feedbackLifecycleGeneration += 1
                    feedbackActuator?.close()
                    feedbackActuator = null
                    updateStatus(
                        "휴대전화 장착 교정 필요",
                        "${assessment.accessibleReasonKo} ${assessment.accessibleActionKo} " +
                            "교정 뒤 화면에서 장착 상태를 명시적으로 다시 확인하세요.",
                    )
                    ensureFeedbackActuator().playPhoneMountingCorrectionVibration()
                    speakInteraction(
                        "휴대전화 장착을 교정하세요. " +
                            "${assessment.accessibleReasonKo} ${assessment.accessibleActionKo}",
                    )
                }
                updatePhoneMountingUi()
            }
            PhoneMountingStatus.UNUSABLE -> {
                phoneMountingOutputsAllowed = false
                enterWalkSessionSafetyStopAndCancelOutputs(
                    "phone_mounting_retry_exhausted",
                )
                val detail =
                    "${assessment.accessibleReasonKo} ${assessment.accessibleActionKo} " +
                        "전체 상태를 다시 확인하고 새 보행을 시작하세요."
                updateStatus("휴대전화 장착 · 안전 중지", detail)
                ensureFeedbackActuator().playPhoneMountingSafetyStopVibration()
                speakInteraction(detail)
            }
        }
    }

    private fun invalidatePhoneMountingDetectionOutputs(epoch: WalkRuntimeEpoch) {
        synchronized(frameStateLock) {
            if (!walkSessionLifecycle.isRuntimeEpochCurrent(epoch)) return
            detectorGeneration += 1
            latestDetectionSnapshot = DetectionSnapshot.empty()
            latestTactileOverlaySnapshot = DetectionSnapshot.empty()
            lastNonEmptyOverlaySnapshot = DetectionSnapshot.empty()
            latestExplicitReportOutput = null
            latestExplicitReportImage = null
            latestExplicitReportGateState = null
            latestExplicitReportCapturedAtMs = 0L
            lastDetectionRunMs = 0L
            lastOverlayUpdateMs = 0L
            lastUiUpdateMs = 0L
            tactileOverlayStabilizer.clear()
            captureLog.clear()
            objectDepthPipeline = ObjectDepthRuntimePipeline().also { pipeline ->
                pipeline.setUserStepLength(stepLengthEstimator.stepLengthM)
            }
        }
        nonMetricAdvisoryPolicy.reset()
        if (::debugBboxOverlay.isInitialized) {
            debugBboxOverlay.clear()
        }
    }

    private fun schedulePhoneMountingEvidenceWatchdog(epoch: WalkRuntimeEpoch) {
        val state = phoneMountingRuntimeState ?: return
        val profile = state.approvedProfile ?: return
        if (
            state.epoch != epoch ||
            !phoneMountingOutputsAllowed ||
            !walkSessionLifecycle.isRuntimeEpochCurrent(epoch)
        ) return
        val confirmation = phoneMountingUserConfirmation
        val camera = latestPhoneMountingCameraAssessment
        val nowMs = SystemClock.elapsedRealtime()
        val expiryDelays = buildList {
            if (confirmation?.epoch == epoch) {
                add(
                    remainingEvidenceLifetimeMs(
                        observedAtMs = confirmation.confirmedAtElapsedRealtimeMs,
                        nowMs = nowMs,
                        maximumAgeMs = profile.maximumEvidenceAgeMs,
                    ),
                )
            }
            if (
                camera?.epoch == epoch &&
                camera.observedAtElapsedRealtimeMs != null &&
                camera.maximumEvidenceAgeMs != null
            ) {
                add(
                    remainingEvidenceLifetimeMs(
                        observedAtMs = checkNotNull(camera.observedAtElapsedRealtimeMs),
                        nowMs = nowMs,
                        maximumAgeMs = minOf(
                            profile.maximumEvidenceAgeMs,
                            checkNotNull(camera.maximumEvidenceAgeMs),
                        ),
                    ),
                )
            }
        }
        val delayMs = expiryDelays.minOrNull() ?: 1L
        val generation = ++phoneMountingWatchdogGeneration
        phoneMountingStatusText.postDelayed(
            {
                if (
                    generation == phoneMountingWatchdogGeneration &&
                    walkSessionLifecycle.isRuntimeEpochCurrent(epoch)
                ) {
                    applyCurrentPhoneMountingRuntimeAssessment(
                        runtimeRetryRequested = false,
                    )
                }
            },
            delayMs,
        )
    }

    private fun schedulePhoneMountingRuntimeRetryTimeout(
        epoch: WalkRuntimeEpoch,
        armedAtElapsedRealtimeMs: Long,
    ) {
        val profile = phoneMountingRuntimeState?.approvedProfile ?: return
        val generation = ++phoneMountingRuntimeRetryGeneration
        val delayMs = remainingEvidenceLifetimeMs(
            observedAtMs = armedAtElapsedRealtimeMs,
            nowMs = SystemClock.elapsedRealtime(),
            maximumAgeMs = profile.maximumEvidenceAgeMs,
        )
        phoneMountingStatusText.postDelayed(
            {
                if (!walkSessionLifecycle.isRuntimeEpochCurrent(epoch)) {
                    return@postDelayed
                }
                val timeoutClaimed = synchronized(phoneMountingObservationLock) {
                    if (
                        generation != phoneMountingRuntimeRetryGeneration ||
                        phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs !=
                        armedAtElapsedRealtimeMs
                    ) {
                        false
                    } else {
                        phoneMountingRuntimeRetryArmedAtElapsedRealtimeMs = null
                        phoneMountingRuntimeRetryGeneration += 1L
                        true
                    }
                }
                if (!timeoutClaimed) return@postDelayed
                applyCurrentPhoneMountingRuntimeAssessment(
                    runtimeRetryRequested = true,
                )
            },
            delayMs,
        )
    }

    private fun remainingEvidenceLifetimeMs(
        observedAtMs: Long,
        nowMs: Long,
        maximumAgeMs: Long,
    ): Long {
        if (observedAtMs < 0L || nowMs < observedAtMs) return 1L
        val remainingMs = maximumAgeMs - (nowMs - observedAtMs)
        return when {
            remainingMs < 1L -> 1L
            remainingMs == Long.MAX_VALUE -> Long.MAX_VALUE
            else -> remainingMs + 1L
        }
    }

    private fun walkSafetyOutputsAllowed(): Boolean =
        firstRunOnboardingComplete() &&
            officialEnvironmentOutputsAllowed &&
            phoneMountingOutputsAllowed

    private fun applyCurrentOfficialEnvironmentRuntimeAssessment() {
        val guard = officialEnvironmentRuntimeGuard ?: return
        if (!walkSessionLifecycle.isRuntimeEpochCurrent(guard.epoch)) return
        val nowMs = SystemClock.elapsedRealtime()
        applyOfficialEnvironmentRuntimeDecision(
            guard.onAssessment(
                assessment = currentOfficialEnvironmentAssessment(guard.epoch, nowMs),
                nowElapsedRealtimeMs = nowMs,
            ),
        )
    }

    private fun applyOfficialEnvironmentRuntimeDecision(
        decision: kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentRuntimeDecision,
    ) {
        if (!walkSessionLifecycle.isRuntimeEpochCurrent(decision.epoch)) return
        when (decision.action) {
            OfficialEnvironmentRuntimeAction.CONTINUE -> {
                officialEnvironmentOutputsAllowed = true
                scheduleOfficialEnvironmentRuntimeWatchdog(decision.epoch)
            }
            OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY -> {
                val newlySuppressed =
                    officialEnvironmentOutputsAllowed ||
                        decision.consecutiveDegradations == 1
                officialEnvironmentOutputsAllowed = false
                clearExplicitReportFrameState("official_environment_retry")
                synchronized(reportUploadSafetyLock) {
                    reportUploadSafetyGeneration += 1L
                    reportPrivacyConsentSession.cancelActiveCalls()
                }
                latestFeedbackDeliveryState = FeedbackDeliveryState()
                feedbackPolicy.cancelPendingFeedbackDeliveries()
                if (newlySuppressed) {
                    cancelVoiceCommandRecognition()
                    feedbackLifecycleGeneration += 1
                    feedbackActuator?.close()
                    feedbackActuator = null
                    updateStatus(
                        "환경 품질 재확인 중",
                        "위치 또는 카메라 품질을 신뢰할 수 없어 경로·위험·신고 출력을 멈췄습니다. 센서를 제한된 횟수로 다시 확인합니다.",
                    )
                    speakInteraction(
                        "환경 품질을 신뢰할 수 없어 보행 출력을 멈췄습니다. 휴대전화를 안정적으로 유지하고 열린 하늘이 보이는 안전한 곳에서 기다리세요.",
                    )
                }
                scheduleOfficialEnvironmentRuntimeWatchdog(decision.epoch)
            }
            OfficialEnvironmentRuntimeAction.SAFE_STOP -> {
                officialEnvironmentOutputsAllowed = false
                enterWalkSessionSafetyStopAndCancelOutputs(
                    "official_environment_quality_persisted",
                )
                val detail =
                    "위치 또는 카메라 품질 저하가 제한된 재확인 뒤에도 계속되어 모든 보행 기능을 안전 중지했습니다. 전체 상태를 다시 확인하고 새 보행을 시작하세요."
                updateStatus("공식 사용환경 이탈 · 안전 중지", detail)
                speakInteraction(detail)
            }
        }
    }

    private fun scheduleOfficialEnvironmentRuntimeWatchdog(epoch: WalkRuntimeEpoch) {
        val profile = OfficialEnvironmentPolicy.productionProfile ?: return
        if (!walkSessionLifecycle.isRuntimeEpochCurrent(epoch)) return
        val generation = ++officialEnvironmentWatchdogGeneration
        val nowMs = SystemClock.elapsedRealtime()
        val delayMs = if (officialEnvironmentOutputsAllowed) {
            listOfNotNull(
                officialEnvironmentGpsEvidence,
                officialEnvironmentCameraEvidence,
            ).filter { evidence ->
                evidence.epoch == epoch &&
                    evidence.observedAtElapsedRealtimeMs != null &&
                    evidence.maximumEvidenceAgeMs != null
            }.minOfOrNull { evidence ->
                val observedAtMs = checkNotNull(evidence.observedAtElapsedRealtimeMs)
                val evidenceMaximumAgeMs = checkNotNull(evidence.maximumEvidenceAgeMs)
                val effectiveMaximumAgeMs =
                    minOf(profile.maximumMeasuredEvidenceAgeMs, evidenceMaximumAgeMs)
                if (observedAtMs < 0L || nowMs < observedAtMs) {
                    1L
                } else {
                    val remainingMs = effectiveMaximumAgeMs - (nowMs - observedAtMs)
                    when {
                        remainingMs < 1L -> 1L
                        remainingMs == Long.MAX_VALUE -> Long.MAX_VALUE
                        else -> remainingMs + 1L
                    }
                }
            } ?: 1L
        } else {
            profile.maximumMeasuredEvidenceAgeMs.coerceAtLeast(1L)
        }
        statusText.postDelayed(
            {
                if (
                    generation == officialEnvironmentWatchdogGeneration &&
                    walkSessionLifecycle.isRuntimeEpochCurrent(epoch)
                ) {
                    applyCurrentOfficialEnvironmentRuntimeAssessment()
                }
            },
            delayMs,
        )
    }

    private fun refreshStartupCapabilityUi() {
        if (!::startupCapabilityProbe.isInitialized || !::startupCapabilityText.isInitialized) return
        val previousDecision = startupCapabilityDecision
        val decision = startupCapabilityProbe.decision(
            metricDistanceOverride = metricDistanceCapabilityOverride,
            onDeviceSpeechRecognitionOverride = onDeviceSpeechRecognitionCapabilityOverride,
            offlineKoreanTextToSpeechOverride = offlineKoreanTextToSpeechCapabilityOverride,
        )
        startupCapabilityDecision = decision
        persistStartupCapabilityDecision(decision)
        if (confirmedStartupCapabilityDecision != decision) {
            confirmedStartupCapabilityDecision = null
        }
        if (previousDecision != null && previousDecision != decision) {
            startupCapabilityConfirmationPending = false
            startupCapabilityRetryRequiresUserAction = false
        }
        applyRuntimeReadinessIfActive(decision)
        val confirmed = isStartupCapabilityConfirmed()
        val priorityUserDecision = priorityUserOnboardingPolicy.evaluate(
            currentPriorityUserSupportEnvironment(decision),
        )
        updateOfficialEnvironmentUi()
        updatePhoneMountingUi()
        val officialEnvironmentReady =
            officialEnvironmentReadiness(walkSessionLifecycle.snapshot().epoch).first ==
                WalkSessionReadinessStatus.READY
        val phoneMountingReady =
            phoneMountingReadiness(walkSessionLifecycle.snapshot().epoch).first ==
                WalkSessionReadinessStatus.READY
        val capabilityMessage = buildString {
            append(decision.noticeKo)
            if (confirmed) append("\n확인 완료: WalkSafe 기능을 시작할 수 있습니다.")
            append("\n${priorityUserDecision.noticeKo}")
        }
        startupCapabilityText.text = capabilityMessage
        startupCapabilityText.contentDescription = capabilityMessage
        val preflightActive = arSessionPurpose == ArSessionPurpose.PREFLIGHT &&
            runtimeMetricPreflightSession?.result()?.status == RuntimeMetricPreflightStatus.IN_PROGRESS
        val preflightEligible = canBeginRuntimeMetricPreflight()
        startupMetricPreflightButton.apply {
            visibility = if (preflightEligible || preflightActive) View.VISIBLE else View.GONE
            isEnabled = preflightEligible && !preflightActive
            text = when {
                preflightActive -> "미터 거리 기능 확인 중"
                pendingMetricPreflightPermissionGeneration != null -> "카메라 권한 확인 중"
                else -> "기기 거리 기능 확인"
            }
            contentDescription = text
        }
        startupCapabilityConfirmButton.apply {
            val walkSession = walkSessionLifecycle.snapshot()
            val paused = walkSession.state == WalkSessionState.PAUSED
            val awaitingExplicitResume =
                permissionRecoveryGate.state ==
                    PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME
            val mayConfirmSession = walkSession.state in setOf(
                WalkSessionState.READY,
                WalkSessionState.PAUSED,
                WalkSessionState.SAFE_STOP,
                WalkSessionState.ENDED,
            )
            if (awaitingExplicitResume) {
                renderAwaitingExplicitResumeControl()
            } else {
                isEnabled = firstRunOnboardingComplete() &&
                    decision.mayConfirmAndStart &&
                    priorityUserDecision.mayStartWalk &&
                    officialEnvironmentReady &&
                    phoneMountingReady &&
                    mayConfirmSession &&
                    !startupCapabilityConfirmationPending &&
                    !walkSessionResumePromptPending &&
                    (!confirmed || paused)
                text = when {
                    !firstRunOnboardingComplete() -> "첫 실행 등록 완료 필요"
                    !priorityUserDecision.mayStartWalk -> "교육과 연습 완료 필요"
                    !officialEnvironmentReady -> "공식 사용환경 확인 필요"
                    !phoneMountingReady -> "휴대전화 장착 확인 필요"
                    paused && walkSessionResumePromptPending -> "보행 재개 음성 확인 중"
                    paused -> "보행 안내 재개 확인"
                    walkSession.state == WalkSessionState.SAFE_STOP -> "새 보행 준비"
                    walkSession.state == WalkSessionState.ENDED -> "새 보행 준비"
                    confirmed && decision.tier == WalkSafeStartupCapabilityTier.FULL -> "목적과 안전 제한 확인 완료"
                    confirmed -> "거리 제한 확인 완료"
                    startupCapabilityConfirmationPending -> "안전 제한 음성 안내 중"
                    decision.tier == WalkSafeStartupCapabilityTier.FULL -> "목적과 안전 제한 확인 후 계속"
                    decision.tier == WalkSafeStartupCapabilityTier.LIMITED -> "거리 제한 확인 후 계속"
                    decision.pendingRequirements.isNotEmpty() -> "기기 기능 확인 중"
                    else -> "이 휴대폰에서 시작할 수 없음"
                }
            }
        }
        updatePriorityUserOnboardingUi(decision)
        runtimeControls.visibility =
            if (
                BuildConfig.DEBUG &&
                firstRunOnboardingComplete() &&
                confirmed &&
                isWalkSessionRuntimeActive() &&
                !permissionRecoveryGate.blocksAutomaticResourceStart
            ) {
                View.VISIBLE
            } else {
                View.GONE
            }
        updateFirstRunOnboardingUi()
        updateFieldSessionLogButton()
        applyActionButtonState()
        maybeAdvanceWalkSessionAfterCapabilityCheck()
    }

    private fun applyRuntimeReadinessIfActive(
        decision: WalkSafeStartupCapabilityDecision,
    ) {
        if (applyingRuntimeReadiness) return
        val current = walkSessionLifecycle.snapshot()
        if (current.state != WalkSessionState.ACTIVE) return
        applyingRuntimeReadiness = true
        try {
            val readiness = captureWalkSessionReadiness(
                decision = decision,
                action = WalkSessionAction.START_WALK,
            )
            val transition = transitionWalkSession(
                WalkSessionEvent.RuntimeReadinessChanged(readiness),
            )
            if (
                transition.previous.state == WalkSessionState.ACTIVE &&
                transition.current.state == WalkSessionState.SAFE_STOP
            ) {
                persistWalkSessionInterruptionMarker()
                walkSessionResumePromptPending = false
                walkSessionResumeRetryRequiresUserAction = false
                walkSessionResumeConfirmationToken = null
                invalidateOfficialEnvironmentEvidence("runtime_readiness_changed")
                invalidatePhoneMountingEvidence("runtime_readiness_changed")
                cancelWalkSessionOutputs("runtime_readiness_changed")
                stopCameraFallbackSession(updateUi = false)
                stopDepthSession(closeSession = true)
                val reason = readiness.blockingReasons.joinToString(", ").ifBlank {
                    "필수 기능 상태가 바뀌어 새 보행 준비가 필요합니다."
                }
                updateStatus("필수 기능 변경 · 안전 중지", reason)
                speakInteraction(
                    "보행 기능을 안전 중지했습니다. $reason",
                )
            }
        } finally {
            applyingRuntimeReadiness = false
        }
    }

    private fun handleWalkSessionForegroundReturn() {
        if (!::walkSessionLifecycle.isInitialized) return
        transitionWalkSession(WalkSessionEvent.EnteredForeground)
        if (::fieldSessionLog.isInitialized) {
            val snapshot = walkSessionLifecycle.snapshot()
            fieldSessionLog.recordEvent(
                "walk_session_foreground_returned",
                mapOf(
                    "state" to snapshot.state.name,
                    "recovery_stage" to (snapshot.recoveryStage?.name ?: "none"),
                ),
            )
        }
    }

    private fun transitionWalkSession(event: WalkSessionEvent): kr.co.hanium.dreamup.walksafe.session.WalkSessionTransition {
        val before = walkSessionLifecycle.snapshot()
        val gatewaySessionForEnd = if (event == WalkSessionEvent.EndRequested) {
            gatewayFieldSession
        } else {
            null
        }
        val endOperation = if (event == WalkSessionEvent.EndRequested) {
            gatewayWalkAuthorityController.beginEnd(before.epoch)
        } else {
            null
        }
        val transition = walkSessionLifecycle.handle(event)
        if (
            transition.changed &&
            (
                transition.previous.state == WalkSessionState.ACTIVE ||
                    transition.current.state == WalkSessionState.ACTIVE
            )
        ) {
            activityOriginalUploadAdmission.onSessionActiveChanged(
                active = transition.current.state == WalkSessionState.ACTIVE,
                observedAtMs = SystemClock.elapsedRealtime(),
                cancelActiveUploads = ::cancelActivityOriginalUploads,
            )
        }
        if (
            transition.changed &&
            transition.current.state == WalkSessionState.PAUSED &&
            transition.previous.epoch != transition.current.epoch
        ) {
            gatewayWalkAuthorityController.rebindEpoch(
                transition.previous.epoch,
                transition.current.epoch,
            )
            scheduleGatewayWalkRenewal()
        }
        if (
            transition.changed &&
            transition.current.state == WalkSessionState.SAFE_STOP
        ) {
            gatewayWalkRenewalHandler.removeCallbacks(gatewayWalkRenewalRunnable)
            gatewayWalkAuthorityController.invalidate(transition.previous.epoch)
            gatewayWalkResumeRevalidationPending = false
        }
        if (transition.changed && ::fieldSessionLog.isInitialized) {
            fieldSessionLog.recordEvent(
                "walk_session_transition",
                mapOf(
                    "walk_session_id" to transition.current.epoch.walkSessionId,
                    "recovery_generation" to transition.current.epoch.recoveryGeneration,
                    "previous_state" to transition.previous.state.name,
                    "current_state" to transition.current.state.name,
                    "previous_mode" to transition.previous.mode.name,
                    "current_mode" to transition.current.mode.name,
                    "reason" to transition.reason.name,
                    "occurred_at_epoch_ms" to transition.occurredAtEpochMs,
                ),
            )
        }
        if (endOperation != null && gatewaySessionForEnd != null) {
            gatewayWalkRenewalHandler.removeCallbacks(gatewayWalkRenewalRunnable)
            endGatewayWalkBestEffort(gatewaySessionForEnd, endOperation)
        }
        return transition
    }

    private fun maybeAdvanceWalkSessionAfterCapabilityCheck() {
        if (permissionRecoveryGate.blocksAutomaticResourceStart) {
            completePermissionRecoveryRecheckIfPossible()
            if (permissionRecoveryGate.blocksAutomaticResourceStart) return
        }
        if (
            !::walkSessionLifecycle.isInitialized ||
            !isActivityForeground ||
            startupCapabilityConfirmationPending ||
            startupCapabilityRetryRequiresUserAction ||
            walkSessionResumePromptPending ||
            walkSessionResumeRetryRequiresUserAction ||
            gatewayWalkResumeRevalidationPending
        ) return

        val decision = startupCapabilityDecision ?: return
        var session = walkSessionLifecycle.snapshot()
        if (
            session.state == WalkSessionState.ACTIVE ||
            session.state == WalkSessionState.SAFE_STOP ||
            session.state == WalkSessionState.ENDED
        ) return
        if (
            session.state == WalkSessionState.PAUSED &&
            gatewayWalkAuthorityController.activeLeaseOrNull(session.epoch) == null
        ) {
            handleGatewayWalkAuthorityLost("walk_lease_missing_on_resume")
            return
        }

        val blockReason = walkSessionReadinessBlockReason(decision)
        if (blockReason != null) {
            if (
                decision.tier == WalkSafeStartupCapabilityTier.BLOCKED &&
                decision.pendingRequirements.isEmpty() &&
                decision.unavailableRequirements.isNotEmpty()
            ) {
                val action = if (session.state == WalkSessionState.PAUSED) {
                    WalkSessionAction.RESUME_WALK
                } else {
                    WalkSessionAction.START_WALK
                }
                val readiness = captureWalkSessionReadiness(decision, action)
                val event = if (session.state == WalkSessionState.PAUSED) {
                    WalkSessionEvent.RecoveryRecheckCompleted(readiness)
                } else {
                    WalkSessionEvent.InitialCheckCompleted(readiness)
                }
                transitionWalkSession(event)
                persistWalkSessionInterruptionMarker()
            } else {
                maybeRequestMissingWalkSessionPermissions(decision)
            }
            updateStatus("보행 시작 대기", blockReason)
            return
        }

        when {
            session.state == WalkSessionState.READY -> {
                if (session.readiness == null) {
                    transitionWalkSession(
                        WalkSessionEvent.InitialCheckCompleted(
                            captureWalkSessionReadiness(
                                decision = decision,
                                action = WalkSessionAction.START_WALK,
                            ),
                        ),
                    )
                    session = walkSessionLifecycle.snapshot()
                }
                val token = session.confirmationToken
                if (token == null) {
                    updateStatus(
                        "보행 시작 대기",
                        session.readiness?.blockingReasons?.joinToString()
                            ?: "필수 기능 점검을 완료하지 못했습니다.",
                    )
                    return
                }
                if (!isStartupCapabilityConfirmed()) {
                    updateStatus(
                        "보행 시작 확인 필요",
                        "필수 기능 점검을 통과했습니다. 안전 제한 안내를 확인한 뒤 시작하세요.",
                    )
                    return
                }
                requestGatewayWalkStart(token)
            }
            session.state == WalkSessionState.PAUSED &&
                session.recoveryStage == WalkSessionRecoveryStage.RECHECK_REQUIRED -> {
                transitionWalkSession(
                    WalkSessionEvent.RecoveryRecheckCompleted(
                        captureWalkSessionReadiness(
                            decision = decision,
                            action = WalkSessionAction.RESUME_WALK,
                        ),
                    ),
                )
                session = walkSessionLifecycle.snapshot()
                if (
                    session.recoveryStage ==
                    WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION &&
                    isStartupCapabilityConfirmed()
                ) {
                    requestWalkSessionResumeConfirmation()
                }
            }
            session.state == WalkSessionState.PAUSED &&
                session.recoveryStage == WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION -> {
                if (isStartupCapabilityConfirmed()) {
                    requestWalkSessionResumeConfirmation()
                } else {
                    updateStatus(
                        "보행 재개 확인 필요",
                        "재검사를 통과했습니다. 안전 제한 안내를 확인한 뒤 재개 여부를 말해 주세요.",
                    )
                }
            }
        }
    }

    private fun requestGatewayWalkStart(token: WalkSessionConfirmationToken) {
        val snapshot = walkSessionLifecycle.snapshot()
        if (
            snapshot.state != WalkSessionState.READY ||
            snapshot.epoch != token.epoch ||
            snapshot.confirmationToken != token
        ) return
        val gatewaySession = gatewaySessionOrNull("walk_start") ?: return
        val operation = gatewayWalkAuthorityController.beginStart(snapshot.epoch) ?: return
        gatewayWalkStartConfirmationToken = token
        updateStatus("보행 권한 확인", "계정의 활성 보행 권한을 확인하고 있습니다.")
        try {
            gatewaySessionExecutor.execute {
                val outcome = runCatching {
                    gatewayWalkSessionClient.start(
                        session = gatewaySession,
                        walkId = operation.epoch.walkSessionId,
                        requestId = operation.requestId,
                        localNowElapsedMs = SystemClock.elapsedRealtime(),
                    )
                }
                runOnUiThread {
                    if (!isGatewayWalkOperationCurrent(operation, token, gatewaySession)) {
                        gatewayWalkAuthorityController.cancel(operation)
                        return@runOnUiThread
                    }
                    outcome.fold(
                        onSuccess = { result ->
                            handleGatewayWalkStartResult(
                                operation = operation,
                                token = token,
                                gatewaySession = gatewaySession,
                                result = result,
                            )
                        },
                        onFailure = {
                            gatewayWalkAuthorityController.cancel(operation)
                            gatewayWalkStartConfirmationToken = null
                            updateStatus(
                                "보행 시작 대기",
                                "서버 보행 권한을 확인하지 못해 시작하지 않았습니다.",
                            )
                            speakInteraction("서버 보행 권한을 확인하지 못해 보행 안내를 시작하지 않았습니다.")
                        },
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            gatewayWalkAuthorityController.cancel(operation)
            gatewayWalkStartConfirmationToken = null
            updateStatus("보행 시작 대기", "보행 권한 확인 작업을 시작하지 못했습니다.")
        }
    }

    private fun handleGatewayWalkStartResult(
        operation: GatewayWalkAuthorityOperation,
        token: WalkSessionConfirmationToken,
        gatewaySession: GatewayFieldSession,
        result: GatewayWalkStartResult,
    ) {
        when (gatewayWalkAuthorityController.completeStart(operation, result)) {
            GatewayWalkAuthorityCompletion.LEASE_ACTIVE ->
                completeGatewayWalkActivation(token)
            GatewayWalkAuthorityCompletion.CONFLICT -> {
                val conflict =
                    gatewayWalkAuthorityController.currentConflictOrNull(operation.epoch)
                        ?: return
                requestGatewayWalkTakeoverConfirmation(
                    operation = operation,
                    token = token,
                    gatewaySession = gatewaySession,
                    activeDeviceId = conflict.activeDeviceId,
                )
            }
            GatewayWalkAuthorityCompletion.STALE,
            GatewayWalkAuthorityCompletion.INVALID,
            -> {
                gatewayWalkStartConfirmationToken = null
                updateStatus("보행 시작 대기", "보행 권한 응답이 현재 시작 요청과 일치하지 않습니다.")
            }
        }
    }

    private fun requestGatewayWalkTakeoverConfirmation(
        operation: GatewayWalkAuthorityOperation,
        token: WalkSessionConfirmationToken,
        gatewaySession: GatewayFieldSession,
        activeDeviceId: String,
    ) {
        if (
            !isCurrentGatewaySession(gatewaySession) ||
            walkSessionLifecycle.snapshot().epoch != operation.epoch ||
            walkSessionLifecycle.snapshot().confirmationToken != token
        ) {
            gatewayWalkAuthorityController.clearConflict(operation.epoch)
            gatewayWalkStartConfirmationToken = null
            return
        }
        gatewayWalkTakeoverPromptPending = true
        gatewayWalkTakeoverPromptOperationId = operation.operationId
        val prompt = "다른 기기에서 보행 중입니다. 이전 보행을 종료하고 이 기기에서 시작할까요? 예 또는 아니요라고 말해 주세요."
        val delivered = {
            runOnUiThread {
                if (
                    gatewayWalkTakeoverPromptPending &&
                    gatewayWalkTakeoverPromptOperationId == operation.operationId &&
                    gatewayWalkAuthorityController.currentConflictOrNull(operation.epoch) != null
                ) {
                    if (
                        !startVoiceCommandRecognition(
                            purpose = VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER,
                            expectedGatewayWalkOperationId = operation.operationId,
                        )
                    ) {
                        cancelGatewayWalkTakeoverConfirmation(
                            operation.epoch,
                            "takeover_voice_not_started",
                        )
                    }
                }
            }
        }
        val failed = {
            runOnUiThread {
                cancelGatewayWalkTakeoverConfirmation(
                    operation.epoch,
                    "takeover_prompt_not_delivered",
                )
            }
        }
        updateStatus(
            "다른 기기에서 보행 중",
            "기기 $activeDeviceId 의 이전 보행 종료 여부를 음성으로 확인합니다.",
        )
        val accepted = if (isScreenReaderActive()) {
            announceForTalkBack(
                message = prompt,
                priority = TalkBackAnnouncementPriority.INTERACTION,
                onDelivered = delivered,
            )
        } else {
            ensureFeedbackActuator().speakInteraction(
                message = prompt,
                onCompleted = delivered,
                onFailed = failed,
            ) == NavigationSpeechDispatchResult.ACCEPTED
        }
        if (!accepted) failed()
    }

    private fun handleGatewayWalkTakeoverRecognition(phrases: List<String>) {
        val token = gatewayWalkStartConfirmationToken ?: return
        val epoch = token.epoch
        gatewayWalkTakeoverPromptPending = false
        gatewayWalkTakeoverPromptOperationId = null
        when (
            GatewayWalkTakeoverConfirmation.fromRecognizedText(
                phrases.firstOrNull { it.isNotBlank() },
            )
        ) {
            GatewayWalkTakeoverConfirmation.YES -> {
                val operation =
                    gatewayWalkAuthorityController.beginTakeover(epoch)
                        ?: run {
                            cancelGatewayWalkTakeoverConfirmation(
                                epoch,
                                "takeover_conflict_stale",
                            )
                            return
                        }
                executeGatewayWalkTakeover(operation, token)
            }
            GatewayWalkTakeoverConfirmation.NO -> {
                gatewayWalkAuthorityController.clearConflict(epoch)
                gatewayWalkStartConfirmationToken = null
                updateStatus("보행 시작 취소", "이전 기기의 보행을 유지합니다.")
                speakInteraction("이전 기기의 보행을 유지합니다. 이 기기에서는 시작하지 않았습니다.")
            }
            GatewayWalkTakeoverConfirmation.NO_RESPONSE,
            GatewayWalkTakeoverConfirmation.UNRECOGNIZED,
            -> cancelGatewayWalkTakeoverConfirmation(
                epoch,
                "takeover_voice_unconfirmed",
            )
        }
    }

    private fun executeGatewayWalkTakeover(
        operation: GatewayWalkAuthorityOperation,
        token: WalkSessionConfirmationToken,
    ) {
        val gatewaySession = gatewaySessionOrNull("walk_takeover") ?: run {
            gatewayWalkAuthorityController.cancel(operation, clearConflict = true)
            gatewayWalkStartConfirmationToken = null
            return
        }
        val conflict = operation.sourceConflict ?: run {
            gatewayWalkAuthorityController.cancel(operation, clearConflict = true)
            gatewayWalkStartConfirmationToken = null
            return
        }
        updateStatus("보행 권한 이전", "이전 보행 종료와 새 권한 발급을 요청하고 있습니다.")
        try {
            gatewaySessionExecutor.execute {
                val outcome = runCatching {
                    gatewayWalkSessionClient.takeover(
                        session = gatewaySession,
                        walkId = operation.epoch.walkSessionId,
                        requestId = operation.requestId,
                        conflict = conflict,
                        localNowElapsedMs = SystemClock.elapsedRealtime(),
                    )
                }
                runOnUiThread {
                    if (!isGatewayWalkOperationCurrent(operation, token, gatewaySession)) {
                        gatewayWalkAuthorityController.cancel(
                            operation,
                            clearConflict = true,
                        )
                        return@runOnUiThread
                    }
                    outcome.fold(
                        onSuccess = { lease ->
                            if (
                                gatewayWalkAuthorityController.completeTakeover(
                                    operation,
                                    lease,
                                ) == GatewayWalkAuthorityCompletion.LEASE_ACTIVE
                            ) {
                                completeGatewayWalkActivation(token)
                            } else {
                                gatewayWalkStartConfirmationToken = null
                                updateStatus(
                                    "보행 시작 대기",
                                    "권한 이전 응답이 현재 요청과 일치하지 않습니다.",
                                )
                            }
                        },
                        onFailure = {
                            gatewayWalkAuthorityController.cancel(
                                operation,
                                clearConflict = true,
                            )
                            gatewayWalkStartConfirmationToken = null
                            updateStatus(
                                "보행 시작 대기",
                                "이전 보행 종료와 권한 이전을 확인하지 못했습니다.",
                            )
                            speakInteraction("보행 권한을 이전하지 못해 이 기기에서는 시작하지 않았습니다.")
                        },
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            gatewayWalkAuthorityController.cancel(operation, clearConflict = true)
            gatewayWalkStartConfirmationToken = null
            updateStatus("보행 시작 대기", "보행 권한 이전 작업을 시작하지 못했습니다.")
        }
    }

    private fun completeGatewayWalkActivation(token: WalkSessionConfirmationToken) {
        val snapshot = walkSessionLifecycle.snapshot()
        if (
            permissionRecoveryGate.blocksAutomaticResourceStart ||
            snapshot.state != WalkSessionState.READY ||
            snapshot.epoch != token.epoch ||
            snapshot.confirmationToken != token ||
            gatewayWalkAuthorityController.activeLeaseOrNull(snapshot.epoch) == null
        ) {
            gatewayWalkAuthorityController.invalidate(token.epoch)
            gatewayWalkStartConfirmationToken = null
            return
        }
        gatewayWalkStartConfirmationToken = null
        transitionWalkSession(WalkSessionEvent.StartRequested(token))
        if (isWalkSessionRuntimeActive()) {
            scheduleGatewayWalkRenewal()
            activateWalkSessionRuntime()
        }
    }

    private fun isGatewayWalkOperationCurrent(
        operation: GatewayWalkAuthorityOperation,
        token: WalkSessionConfirmationToken,
        gatewaySession: GatewayFieldSession,
    ): Boolean {
        val snapshot = walkSessionLifecycle.snapshot()
        return gatewayWalkAuthorityController.isCurrent(operation) &&
            snapshot.epoch == operation.epoch &&
            snapshot.state == WalkSessionState.READY &&
            snapshot.confirmationToken == token &&
            gatewayWalkStartConfirmationToken == token &&
            isCurrentGatewaySession(gatewaySession)
    }

    private fun scheduleGatewayWalkRenewal() {
        gatewayWalkRenewalHandler.removeCallbacks(gatewayWalkRenewalRunnable)
        val snapshot = walkSessionLifecycle.snapshot()
        if (snapshot.state !in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)) return
        val renewDelayMs = gatewayWalkAuthorityController.nextRenewDelayMs(
            snapshot.epoch,
            GATEWAY_WALK_RENEW_INTERVAL_MS,
        ) ?: run {
            handleGatewayWalkAuthorityLost("walk_lease_local_expiry")
            return
        }
        gatewayWalkRenewalHandler.postDelayed(
            gatewayWalkRenewalRunnable,
            renewDelayMs,
        )
    }

    private fun requestGatewayWalkRenewal() {
        gatewayWalkRenewalHandler.removeCallbacks(gatewayWalkRenewalRunnable)
        val snapshot = walkSessionLifecycle.snapshot()
        if (snapshot.state !in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)) return
        val operation = gatewayWalkAuthorityController.beginRenew(snapshot.epoch)
            ?: run {
                if (
                    gatewayWalkAuthorityController.activeLeaseOrNull(snapshot.epoch) == null
                ) {
                    handleGatewayWalkAuthorityLost("walk_lease_local_expiry")
                }
                return
            }
        val lease = operation.sourceLease ?: run {
            handleGatewayWalkAuthorityLost("walk_lease_missing")
            return
        }
        val gatewaySession = gatewaySessionOrNull("walk_renew", speak = false) ?: run {
            gatewayWalkAuthorityController.cancel(operation)
            handleGatewayWalkAuthorityLost("walk_lease_gateway_session_missing")
            return
        }
        try {
            gatewaySessionExecutor.execute {
                val outcome = runCatching {
                    gatewayWalkSessionClient.renew(
                        session = gatewaySession,
                        lease = lease,
                        requestId = operation.requestId,
                        localNowElapsedMs = SystemClock.elapsedRealtime(),
                    )
                }
                runOnUiThread {
                    val callbackSnapshot = walkSessionLifecycle.snapshot()
                    if (
                        callbackSnapshot.epoch != operation.epoch ||
                        callbackSnapshot.state !in
                        setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)
                    ) return@runOnUiThread
                    when (gatewayWalkAuthorityController.currentOperationStatus(operation)) {
                        GatewayWalkAuthorityCompletion.INVALID -> {
                            handleGatewayWalkAuthorityLost(
                                "walk_lease_expired_during_renew",
                            )
                            return@runOnUiThread
                        }
                        GatewayWalkAuthorityCompletion.LEASE_ACTIVE -> Unit
                        else -> return@runOnUiThread
                    }
                    val renewed = outcome.getOrNull()
                    if (
                        renewed == null ||
                        gatewayWalkAuthorityController.completeRenew(
                            operation,
                            renewed,
                        ) != GatewayWalkAuthorityCompletion.LEASE_ACTIVE
                    ) {
                        gatewayWalkAuthorityController.cancel(operation)
                        handleGatewayWalkAuthorityLost("walk_lease_renew_failed")
                        return@runOnUiThread
                    }
                    gatewayWalkResumeRevalidationPending = false
                    scheduleGatewayWalkRenewal()
                    if (
                        walkSessionLifecycle.snapshot().state == WalkSessionState.PAUSED &&
                        isActivityForeground
                    ) {
                        refreshStartupCapabilityUi()
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            gatewayWalkAuthorityController.cancel(operation)
            handleGatewayWalkAuthorityLost("walk_lease_renew_rejected")
        }
    }

    private fun revalidateGatewayWalkAfterForegroundReturn() {
        val snapshot = walkSessionLifecycle.snapshot()
        if (snapshot.state != WalkSessionState.PAUSED) return
        if (gatewayWalkAuthorityController.activeLeaseOrNull(snapshot.epoch) == null) {
            handleGatewayWalkAuthorityLost("walk_lease_expired_while_backgrounded")
            return
        }
        gatewayWalkResumeRevalidationPending = true
        requestGatewayWalkRenewal()
    }

    private fun handleGatewayWalkAuthorityLost(reason: String) {
        gatewayWalkRenewalHandler.removeCallbacks(gatewayWalkRenewalRunnable)
        gatewayWalkResumeRevalidationPending = false
        gatewayWalkTakeoverPromptPending = false
        gatewayWalkTakeoverPromptOperationId = null
        gatewayWalkStartConfirmationToken = null
        val snapshot = walkSessionLifecycle.snapshot()
        gatewayWalkAuthorityController.invalidate(snapshot.epoch)
        if (snapshot.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)) {
            enterWalkSessionSafetyStopAndCancelOutputs(reason)
        }
        updateStatus(
            "보행 권한 만료 · 안전 중지",
            "서버의 단일 활성 보행 권한을 확인하지 못했습니다.",
        )
    }

    private fun cancelGatewayWalkTakeoverConfirmation(
        epoch: WalkRuntimeEpoch,
        reason: String,
    ) {
        gatewayWalkTakeoverPromptPending = false
        gatewayWalkTakeoverPromptOperationId = null
        gatewayWalkStartConfirmationToken = null
        gatewayWalkAuthorityController.clearConflict(epoch)
        updateStatus("보행 시작 대기", "이전 보행 종료를 확인하지 않아 이 기기에서는 시작하지 않습니다.")
        updateNavigationStatus("walk=takeover_not_confirmed reason=$reason")
    }

    private fun cancelPendingGatewayWalkStart() {
        val token = gatewayWalkStartConfirmationToken
        gatewayWalkStartConfirmationToken = null
        gatewayWalkTakeoverPromptPending = false
        gatewayWalkTakeoverPromptOperationId = null
        if (token != null) gatewayWalkAuthorityController.invalidate(token.epoch)
    }

    private fun endGatewayWalkBestEffort(
        gatewaySession: GatewayFieldSession,
        operation: GatewayWalkAuthorityOperation,
    ) {
        val lease = operation.sourceLease ?: return
        try {
            gatewaySessionExecutor.execute {
                runCatching {
                    gatewayWalkSessionClient.end(
                        session = gatewaySession,
                        lease = lease,
                        requestId = operation.requestId,
                    )
                }
            }
        } catch (_: RejectedExecutionException) {
            // The server lease expires after its bounded lifetime.
        }
    }

    private fun captureWalkSessionReadiness(
        decision: WalkSafeStartupCapabilityDecision,
        action: WalkSessionAction,
    ): WalkSessionReadinessSnapshot {
        val epoch = walkSessionLifecycle.snapshot().epoch
        val plan = WalkSessionReadinessPlan(
            action = action,
            mode = decision.effectiveWalkSessionMode(),
        )
        if (
            firstRunOnboardingComplete() &&
            plan.requires(WalkSessionReadinessRequirement.MODEL) &&
            hasCameraPermission() &&
            WalkSafeStartupRequirement.CAMERA !in decision.pendingRequirements &&
            WalkSafeStartupRequirement.CAMERA !in decision.unavailableRequirements
        ) {
            loadDetectorAfterCameraGate()
        }
        val deviceResources = walkSessionResourceProbe.snapshot()
        val collector = WalkSessionReadinessCollector(epoch = epoch, plan = plan)
        plan.requiredRequirements.forEach { requirement ->
            val (status, reason) = when (requirement) {
                WalkSessionReadinessRequirement.FIRST_RUN_ONBOARDING -> {
                    if (firstRunOnboardingComplete()) {
                        WalkSessionReadinessStatus.READY to ""
                    } else {
                        WalkSessionReadinessStatus.UNAVAILABLE to
                            "first_run_onboarding_incomplete:" +
                            firstRunOnboardingSnapshot.stage.name.lowercase(Locale.US)
                    }
                }
                WalkSessionReadinessRequirement.PRIORITY_USER_ONBOARDING -> {
                    val onboarding = priorityUserOnboardingPolicy.evaluate(
                        environment = currentPriorityUserSupportEnvironment(decision),
                        walkIsActive = isWalkSessionRuntimeActive(),
                    )
                    if (
                        onboarding.mayStartWalk &&
                        currentReporterUserId() != null
                    ) {
                        WalkSessionReadinessStatus.READY to ""
                    } else {
                        WalkSessionReadinessStatus.UNAVAILABLE to
                            "priority_user_onboarding:" +
                            (
                                onboarding.walkBlockReason?.name?.lowercase(Locale.US)
                                    ?: "account_profile_unbound"
                            )
                    }
                }
                WalkSessionReadinessRequirement.OFFICIAL_ENVIRONMENT ->
                    officialEnvironmentReadiness(epoch)
                WalkSessionReadinessRequirement.PHONE_MOUNTING ->
                    phoneMountingReadiness(epoch)
                WalkSessionReadinessRequirement.PERMISSIONS -> {
                    WalkSessionReadinessStatus.READY to ""
                }
                WalkSessionReadinessRequirement.CAMERA -> combineReadinessChecks(
                    startupRequirementCheck(
                        decision,
                        setOf(WalkSafeStartupRequirement.CAMERA),
                    ),
                    if (hasCameraPermission()) {
                        WalkSessionReadinessStatus.READY to ""
                    } else {
                        WalkSessionReadinessStatus.UNAVAILABLE to "camera_permission_missing"
                    },
                )
                WalkSessionReadinessRequirement.LOCATION -> combineReadinessChecks(
                    startupRequirementCheck(
                        decision,
                        setOf(WalkSafeStartupRequirement.GPS),
                    ),
                    if (hasLocationPermission()) {
                        WalkSessionReadinessStatus.READY to ""
                    } else {
                        WalkSessionReadinessStatus.UNAVAILABLE to "precise_location_permission_missing"
                    },
                )
                WalkSessionReadinessRequirement.METRIC_DISTANCE ->
                    startupRequirementCheck(
                        decision,
                        setOf(
                            WalkSafeStartupRequirement.METRIC_DISTANCE,
                            WalkSafeStartupRequirement.APPROVED_DEVICE_PROFILE,
                        ),
                    )
                WalkSessionReadinessRequirement.MODEL -> if (detectorAvailable) {
                    WalkSessionReadinessStatus.READY to ""
                } else {
                    WalkSessionReadinessStatus.UNAVAILABLE to
                        "model_unavailable:${detectorLoadReason ?: "not_loaded"}"
                }
                WalkSessionReadinessRequirement.VOICE_INPUT -> combineReadinessChecks(
                    startupRequirementCheck(
                        decision,
                        setOf(
                            WalkSafeStartupRequirement.MICROPHONE,
                            WalkSafeStartupRequirement.ON_DEVICE_STT,
                        ),
                    ),
                    if (hasRecordAudioPermission()) {
                        WalkSessionReadinessStatus.READY to ""
                    } else {
                        WalkSessionReadinessStatus.UNAVAILABLE to "microphone_permission_missing"
                    },
                )
                WalkSessionReadinessRequirement.VOICE_OUTPUT ->
                    startupRequirementCheck(
                        decision,
                        setOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS),
                    )
                WalkSessionReadinessRequirement.ROUTE_SERVICE -> {
                    val endpointReady = if (BuildConfig.DEBUG) {
                        GatewayEndpointPolicy.debugOriginOrNull(
                            BuildConfig.WALKSAFE_GATEWAY_ORIGIN,
                        ) != null
                    } else {
                        GatewayEndpointPolicy.approvedReleaseOriginOrNull(
                            raw = BuildConfig.WALKSAFE_GATEWAY_ORIGIN,
                            approvedOrigin = BuildConfig.WALKSAFE_GATEWAY_ORIGIN,
                        ) != null
                    }
                    if (endpointReady) {
                        WalkSessionReadinessStatus.READY to ""
                    } else {
                        WalkSessionReadinessStatus.UNAVAILABLE to "route_service_endpoint_unavailable"
                    }
                }
                WalkSessionReadinessRequirement.GATEWAY -> {
                    if (isGatewaySessionReadyForCurrentActor()) {
                        WalkSessionReadinessStatus.READY to ""
                    } else {
                        WalkSessionReadinessStatus.UNAVAILABLE to "gateway_session_unavailable"
                    }
                }
                WalkSessionReadinessRequirement.DEVICE_RESOURCES -> combineReadinessChecks(
                    startupRequirementCheck(
                        decision,
                        setOf(
                            WalkSafeStartupRequirement.ANDROID_VERSION,
                            WalkSafeStartupRequirement.VIBRATION,
                        ),
                    ),
                    deviceResources.readinessStatus to deviceResources.reason,
                )
            }
            collector.record(
                WalkSessionReadinessObservation(
                    epoch = epoch,
                    requirement = requirement,
                    status = status,
                    reason = reason,
                ),
            )
        }
        return collector.build(assessedAtEpochMs = SystemClock.elapsedRealtime())
    }

    private fun startupRequirementCheck(
        decision: WalkSafeStartupCapabilityDecision,
        requirements: Set<WalkSafeStartupRequirement>,
    ): Pair<WalkSessionReadinessStatus, String> {
        val unavailable = decision.unavailableRequirements.filter(requirements::contains)
        if (unavailable.isNotEmpty()) {
            return WalkSessionReadinessStatus.UNAVAILABLE to
                "startup_unavailable:${unavailable.joinToString(",") { it.name }}"
        }
        val pending = decision.pendingRequirements.filter(requirements::contains)
        if (pending.isNotEmpty()) {
            return WalkSessionReadinessStatus.PENDING to
                "startup_pending:${pending.joinToString(",") { it.name }}"
        }
        return WalkSessionReadinessStatus.READY to ""
    }

    private fun combineReadinessChecks(
        vararg checks: Pair<WalkSessionReadinessStatus, String>,
    ): Pair<WalkSessionReadinessStatus, String> {
        val unavailable = checks.filter { it.first == WalkSessionReadinessStatus.UNAVAILABLE }
        if (unavailable.isNotEmpty()) {
            return WalkSessionReadinessStatus.UNAVAILABLE to
                unavailable.map(Pair<WalkSessionReadinessStatus, String>::second)
                    .filter(String::isNotBlank)
                    .joinToString(",")
        }
        val pending = checks.filter { it.first == WalkSessionReadinessStatus.PENDING }
        if (pending.isNotEmpty()) {
            return WalkSessionReadinessStatus.PENDING to
                pending.map(Pair<WalkSessionReadinessStatus, String>::second)
                    .filter(String::isNotBlank)
                    .joinToString(",")
        }
        return WalkSessionReadinessStatus.READY to ""
    }

    private fun walkSessionReadinessBlockReason(
        decision: WalkSafeStartupCapabilityDecision,
    ): String? {
        if (!firstRunOnboardingComplete()) {
            return "첫 실행 등록과 안전교육이 완료될 때까지 보행 안내를 시작하지 않습니다."
        }
        if (!decision.mayConfirmAndStart) return decision.noticeKo
        val onboarding = priorityUserOnboardingPolicy.evaluate(
            currentPriorityUserSupportEnvironment(decision),
        )
        if (!onboarding.mayStartWalk) return onboarding.noticeKo
        if (currentReporterUserId() == null) return "로그인이 완료될 때까지 보행 안내를 시작하지 않습니다."
        if (!isGatewaySessionReadyForCurrentActor()) {
            return "게이트웨이 로그인을 확인한 뒤 보행 안내를 시작할 수 있습니다."
        }
        officialEnvironmentBlockReason()?.let { return it }
        phoneMountingBlockReason()?.let { return it }
        val action = if (
            walkSessionLifecycle.snapshot().state == WalkSessionState.PAUSED
        ) {
            WalkSessionAction.RESUME_WALK
        } else {
            WalkSessionAction.START_WALK
        }
        val missingPermissions = missingRequiredWalkSessionPermissions(action)
        if (missingPermissions.isNotEmpty()) {
            return "필수 권한이 필요합니다: ${missingPermissions.joinToString(", ") { it.permissionLabelKo() }}"
        }
        return null
    }

    private fun missingRequiredWalkSessionPermissions(
        action: WalkSessionAction,
    ): List<String> = buildList {
        if (!hasCameraPermission()) add(Manifest.permission.CAMERA)
        if (!hasLocationPermission()) {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (!hasRecordAudioPermission()) {
            add(Manifest.permission.RECORD_AUDIO)
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            !hasActivityRecognitionPermission()
        ) {
            add(Manifest.permission.ACTIVITY_RECOGNITION)
        }
    }

    private fun missingWalkSessionPermissions(
        decision: WalkSafeStartupCapabilityDecision,
    ): List<String> {
        val missing = mutableListOf<String>()
        if (!hasCameraPermission()) missing += Manifest.permission.CAMERA
        if (!hasRecordAudioPermission()) missing += Manifest.permission.RECORD_AUDIO
        if (!hasLocationPermission()) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (!hasActivityRecognitionPermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            missing += Manifest.permission.ACTIVITY_RECOGNITION
        }
        return missing.distinct()
    }

    private fun maybeRequestMissingWalkSessionPermissions(
        decision: WalkSafeStartupCapabilityDecision,
    ) {
        if (
            !firstRunOnboardingComplete() ||
            !decision.mayConfirmAndStart ||
            currentReporterUserId() == null ||
            walkSessionPermissionRequestInFlight ||
            walkSessionPermissionRequestAttempted
        ) return
        val missing = missingWalkSessionPermissions(decision)
        if (missing.isEmpty()) return
        walkSessionPermissionRequestAttempted = true
        walkSessionPermissionRequestInFlight = true
        walkSessionPermissionRequestCode = try {
            requestPermissionsWithLease(
                missing.toTypedArray(),
                PermissionRequestPurpose.WALK_SESSION,
            )
        } catch (error: RuntimeException) {
            walkSessionPermissionRequestInFlight = false
            throw error
        }
    }

    private fun String.permissionLabelKo(): String = when (this) {
        Manifest.permission.CAMERA -> "카메라"
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        -> "정확한 위치"
        Manifest.permission.RECORD_AUDIO -> "마이크"
        Manifest.permission.ACTIVITY_RECOGNITION -> "보행 센서"
        else -> this
    }

    private fun WalkSafeStartupCapabilityDecision.toWalkSessionMode(): WalkSessionMode = when (tier) {
        WalkSafeStartupCapabilityTier.FULL -> WalkSessionMode.FULL
        WalkSafeStartupCapabilityTier.LIMITED -> WalkSessionMode.DISTANCE_LIMITED
        WalkSafeStartupCapabilityTier.BLOCKED -> WalkSessionMode.UNAVAILABLE
    }

    private fun WalkSafeStartupCapabilityDecision.effectiveWalkSessionMode(): WalkSessionMode =
        toWalkSessionMode()

    private fun isWalkSessionRuntimeActive(): Boolean {
        if (!::walkSessionLifecycle.isInitialized) return false
        val snapshot = walkSessionLifecycle.snapshot()
        return firstRunOnboardingComplete() &&
            !permissionRecoveryGate.blocksAutomaticResourceStart &&
            isActivityForeground &&
            snapshot.state == WalkSessionState.ACTIVE &&
            snapshot.isForeground &&
            gatewayWalkAuthorityController.activeLeaseOrNull(snapshot.epoch) != null
    }

    @SuppressLint("ApplySharedPref")
    private fun persistWalkSessionInterruptionMarker() {
        if (!::stepLengthPrefs.isInitialized || !::walkSessionLifecycle.isInitialized) return
        val state = walkSessionLifecycle.snapshot().state
        val interrupted = state == WalkSessionState.ACTIVE ||
            state == WalkSessionState.PAUSED ||
            state == WalkSessionState.SAFE_STOP
        stepLengthPrefs.edit().putBoolean(PREF_WALK_SESSION_INTERRUPTED, interrupted).commit()
    }

    private fun activateWalkSessionRuntime() {
        if (!isWalkSessionRuntimeActive()) return handleGatewayWalkAuthorityLost(
            "walk_lease_missing_before_runtime",
        )
        scheduleGatewayWalkRenewal()
        persistWalkSessionInterruptionMarker()
        walkSessionResumePromptPending = false
        walkSessionResumeRetryRequiresUserAction = false
        if (!activateOfficialEnvironmentRuntime()) return
        if (!activatePhoneMountingRuntime()) return
        if (::cameraFallbackLifecycleOwner.isInitialized) {
            cameraFallbackLifecycleOwner.moveTo(Lifecycle.State.RESUMED)
        }
        if (::surfaceView.isInitialized) surfaceView.onResume()
        if (::earthOrientationTracker.isInitialized) earthOrientationTracker.start()
        syncActiveSessionScreenPolicy()
        if (cameraFallbackRequested) {
            if (!cameraFallbackRunning) bindCameraFallbackSession()
        } else if (session == null) {
            if (pendingSessionStartAfterInstall) pendingSessionStartAfterInstall = false
            ensurePermissionsThenStart()
        }
        startNavigationServicesIfNeeded()
        refreshStartupCapabilityUi()
    }

    private fun requestWalkSessionResumeConfirmation() {
        if (!::walkSessionLifecycle.isInitialized || walkSessionResumePromptPending) return
        val snapshot = walkSessionLifecycle.snapshot()
        if (
            snapshot.state != WalkSessionState.PAUSED ||
            snapshot.recoveryStage != WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION ||
            !snapshot.isForeground ||
            !isStartupCapabilityConfirmed()
        ) return

        val confirmationToken = snapshot.confirmationToken ?: return
        val expectedWalkEpoch = snapshot.epoch
        walkSessionResumeConfirmationToken = confirmationToken
        walkSessionResumePromptPending = true
        val prompt = "보행 안내를 다시 시작할까요? 시작 또는 취소라고 말해 주세요"
        val generation = feedbackLifecycleGeneration
        val delivered = {
            runOnUiThread {
                if (
                    isFeedbackLifecycleCurrent(generation) &&
                    walkSessionResumePromptPending &&
                    walkSessionLifecycle.snapshot().epoch == expectedWalkEpoch &&
                    walkSessionLifecycle.snapshot().confirmationToken == confirmationToken
                ) {
                    if (
                        !startVoiceCommandRecognition(
                            VoiceRecognitionPurpose.WALK_SESSION_RESUME,
                        )
                    ) {
                        handleWalkSessionResumeRecognizerNotStarted()
                    }
                }
            }
        }
        val failed = {
            runOnUiThread {
                val currentSnapshot = walkSessionLifecycle.snapshot()
                if (
                    feedbackLifecycleGeneration == generation &&
                    currentSnapshot.epoch == expectedWalkEpoch &&
                    currentSnapshot.confirmationToken == confirmationToken &&
                    walkSessionResumeConfirmationToken == confirmationToken
                ) {
                    walkSessionResumePromptPending = false
                    walkSessionResumeConfirmationToken = null
                    handleRuntimeSpeechCapabilityFailure(
                        WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS,
                        "재개 확인 음성을 전달할 수 없어 보행 기능을 중지했습니다.",
                    )
                }
            }
        }
        val accepted = if (isScreenReaderActive()) {
            announceForTalkBack(
                message = prompt,
                priority = TalkBackAnnouncementPriority.INTERACTION,
                onDelivered = delivered,
            )
        } else {
            ensureFeedbackActuator().speakInteraction(
                message = prompt,
                onCompleted = delivered,
                onFailed = failed,
            ) == NavigationSpeechDispatchResult.ACCEPTED
        }
        if (!accepted) failed()
    }

    private fun handleWalkSessionResumeRecognizerNotStarted() {
        val snapshot = walkSessionLifecycle.snapshot()
        if (
            snapshot.state != WalkSessionState.PAUSED ||
            snapshot.recoveryStage != WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION
        ) return
        walkSessionResumePromptPending = false
        walkSessionResumeRetryRequiresUserAction = true
        walkSessionResumeConfirmationToken = null
        updateStatus(
            "보행 안내 일시중지",
            "재개 응답을 들을 준비가 되지 않아 일시중지를 유지합니다. 재개 확인을 다시 눌러 주세요.",
        )
        if (::startupCapabilityConfirmButton.isInitialized) {
            startupCapabilityConfirmButton.isEnabled = true
            startupCapabilityConfirmButton.text = "보행 안내 재개 확인"
        }
    }

    private fun handleWalkSessionResumeRecognition(phrases: List<String>) {
        val confirmationToken = walkSessionResumeConfirmationToken ?: return
        walkSessionResumePromptPending = false
        walkSessionResumeConfirmationToken = null
        val response = WalkSessionResumeConfirmation.fromRecognizedText(phrases.firstOrNull())
        transitionWalkSession(
            WalkSessionEvent.ResumeConfirmationReceived(
                confirmation = response,
                token = confirmationToken,
            ),
        )
        walkSessionResumeRetryRequiresUserAction =
            response != WalkSessionResumeConfirmation.START
        persistWalkSessionInterruptionMarker()
        if (isWalkSessionRuntimeActive()) {
            activateWalkSessionRuntime()
            updateStatus("보행 안내 재개", "필수 기능 재검사와 사용자 확인을 마쳐 새 판단으로 재개했습니다.")
        } else {
            val detail = when (response) {
                WalkSessionResumeConfirmation.CANCEL -> "사용자가 취소해 일시중지를 유지합니다."
                WalkSessionResumeConfirmation.NO_RESPONSE -> "응답이 없어 일시중지를 유지합니다."
                WalkSessionResumeConfirmation.UNRECOGNIZED -> "재개 응답을 확인하지 못해 일시중지를 유지합니다."
                WalkSessionResumeConfirmation.START -> "재검사 상태가 바뀌어 일시중지를 유지합니다."
            }
            updateStatus("보행 안내 일시중지", detail)
            if (::startupCapabilityConfirmButton.isInitialized) {
                startupCapabilityConfirmButton.isEnabled = true
                startupCapabilityConfirmButton.text = "보행 안내 재개 확인"
            }
        }
    }

    private fun canBeginRuntimeMetricPreflight(): Boolean {
        if (!::startupCapabilityProbe.isInitialized || !isActivityForeground) return false
        if (!firstRunDeviceCheckAllowsPreflight()) return false
        val walkSession = walkSessionLifecycle.snapshot()
        if (
            !walkSession.isForeground ||
            walkSession.state !in setOf(WalkSessionState.READY, WalkSessionState.PAUSED)
        ) return false
        if (arSessionPurpose != ArSessionPurpose.NONE || session != null) return false
        if (::fieldSessionLog.isInitialized && isFieldSessionActive()) return false
        val input = startupCapabilityProbe.snapshot()
        val coreAvailable = listOf(
            input.androidVersionSupported,
            input.cameraAvailable,
            input.gpsAvailable,
            input.microphoneAvailable,
            input.vibrationAvailable,
            input.onDeviceSpeechRecognitionAvailable,
            input.offlineKoreanTextToSpeechAvailable,
        ).all { it == true }
        val effectiveMetricDistance = metricDistanceCapabilityOverride ?: input.metricDistanceAvailable
        return coreAvailable && effectiveMetricDistance == null
    }

    private fun beginRuntimeMetricPreflight() {
        if (!canBeginRuntimeMetricPreflight()) return
        metricPreflightFirstRunLease = currentFirstRunAsyncLease()
        confirmedStartupCapabilityDecision = null
        startupCapabilityConfirmationPending = false
        val generation = ++runtimeMetricPreflightGeneration
        metricPreflightLifecycleGeneration = feedbackLifecycleGeneration
        pendingMetricPreflightPermissionGeneration = generation
        runtimeMetricOutputAllowed = false
        runtimeMetricInitialNavigationStartPending = false
        refreshStartupCapabilityUi()
        if (!hasCameraPermission()) {
            requestPermissionsWithLease(
                arrayOf(Manifest.permission.CAMERA),
                PermissionRequestPurpose.METRIC_PREFLIGHT_CAMERA,
            )
            return
        }
        pendingMetricPreflightPermissionGeneration = null
        startRuntimeMetricPreflight(generation, metricPreflightLifecycleGeneration)
    }

    private fun startRuntimeMetricPreflight(
        generation: Long,
        lifecycleGeneration: Int,
    ) {
        if (!isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) return
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (shouldRecheckArCoreAvailability(availability)) {
            startupMetricPreflightButton.isEnabled = false
            startupMetricPreflightButton.text = "ARCore 지원 확인 중"
            ArCoreApk.getInstance().checkAvailabilityAsync(this) { resolved ->
                runOnUiThread {
                    if (isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) {
                        continueRuntimeMetricPreflightStart(resolved, generation, lifecycleGeneration)
                    }
                }
            }
            return
        }
        continueRuntimeMetricPreflightStart(availability, generation, lifecycleGeneration)
    }

    private fun continueRuntimeMetricPreflightStart(
        availability: ArCoreApk.Availability,
        generation: Long,
        lifecycleGeneration: Int,
    ) {
        if (!isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) return
        when (resolveArCoreStartGate(availability)) {
            ArCoreStartGate.CAMERA_FALLBACK -> {
                finishRuntimeMetricPreflightWithoutSession(
                    generation = generation,
                    lifecycleGeneration = lifecycleGeneration,
                    depthSupport = RuntimeMetricDepthSupport.UNSUPPORTED,
                )
                return
            }
            ArCoreStartGate.RETRY_LATER -> {
                finishRuntimeMetricPreflightWithoutSession(
                    generation = generation,
                    lifecycleGeneration = lifecycleGeneration,
                    depthSupport = RuntimeMetricDepthSupport.UNKNOWN,
                )
                return
            }
            ArCoreStartGate.READY -> Unit
        }

        var candidateSession: Session? = null
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    invalidateRuntimeMetricEvidence("arcore_install_requested")
                    updateStatus(
                        "ARCore 설치 필요",
                        "Google Play Services for AR 설치 후 기기 거리 기능 확인을 다시 눌러 주세요.",
                    )
                    refreshStartupCapabilityUi()
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }
            val preflightSession = Session(this).also { candidateSession = it }
            val provider = ArCoreFrameProvider(preflightSession)
            if (!provider.configureDepthMode()) {
                preflightSession.close()
                candidateSession = null
                finishRuntimeMetricPreflightWithoutSession(
                    generation = generation,
                    lifecycleGeneration = lifecycleGeneration,
                    depthSupport = RuntimeMetricDepthSupport.UNSUPPORTED,
                )
                return
            }
            startRuntimeMetricPreflightSession(
                preflightSession = preflightSession,
                provider = provider,
                generation = generation,
                lifecycleGeneration = lifecycleGeneration,
            )
            candidateSession = null
        } catch (_: UnavailableDeviceNotCompatibleException) {
            runCatching { candidateSession?.close() }
            finishRuntimeMetricPreflightWithoutSession(
                generation = generation,
                lifecycleGeneration = lifecycleGeneration,
                depthSupport = RuntimeMetricDepthSupport.UNSUPPORTED,
            )
        } catch (_: Exception) {
            runCatching { candidateSession?.close() }
            finishRuntimeMetricPreflightWithoutSession(
                generation = generation,
                lifecycleGeneration = lifecycleGeneration,
                depthSupport = RuntimeMetricDepthSupport.UNKNOWN,
            )
        }
    }

    private fun startRuntimeMetricPreflightSession(
        preflightSession: Session,
        provider: ArCoreFrameProvider,
        generation: Long,
        lifecycleGeneration: Int,
    ) {
        if (!isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) {
            preflightSession.close()
            return
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        val evaluator = RuntimeMetricPreflightSession(
            generation = generation,
            startedAtElapsedRealtimeMs = startedAtMs,
            depthSupport = RuntimeMetricDepthSupport.SUPPORTED,
        )
        val resumeRenderer = pauseRendererForSessionClose()
        try {
            val textureBound = resumeArSessionBeforePublish(preflightSession)
            synchronized(runtimeMetricStateLock) {
                session = preflightSession
                frameProvider = provider
                arSessionPurpose = ArSessionPurpose.PREFLIGHT
                metricPreflightArSessionGeneration = ++arSessionGeneration
                runtimeMetricPreflightSession = evaluator
                metricPreflightTerminalDispatchedGeneration = 0L
                runtimeMetricOutputAllowed = false
                runtimeMetricInitialNavigationStartPending = false
                runtimeMetricLastFrameTimestampNanos = 0L
                cameraTextureBound = textureBound
            }
        } finally {
            resumeRendererAfterSessionClose(resumeRenderer)
        }
        arCoreSupported = true
        depthSupported = true
        syncActiveSessionScreenPolicy()
        updateStatus(
            "미터 거리 기능 확인 중",
            "보행 기능과 신고는 시작하지 않고 실제 Depth 프레임만 확인합니다.",
        )
        refreshStartupCapabilityUi()
        startupMetricPreflightButton.postDelayed(
            {
                val active = runtimeMetricPreflightSession
                if (
                    active != null &&
                    active.generation == generation &&
                    arSessionPurpose == ArSessionPurpose.PREFLIGHT
                ) {
                    val result = active.expire(SystemClock.elapsedRealtime())
                    if (result.status != RuntimeMetricPreflightStatus.IN_PROGRESS) {
                        finishRuntimeMetricPreflight(
                            result,
                            expectedArSessionGeneration = metricPreflightArSessionGeneration,
                            expectedLifecycleGeneration = lifecycleGeneration,
                        )
                    }
                }
            },
            RuntimeMetricPreflightPolicy.MAX_DURATION_MS,
        )
    }

    private fun finishRuntimeMetricPreflightWithoutSession(
        generation: Long,
        lifecycleGeneration: Int,
        depthSupport: RuntimeMetricDepthSupport,
    ) {
        if (!isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) return
        val evaluator = RuntimeMetricPreflightSession(
            generation = generation,
            startedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
            depthSupport = depthSupport,
        )
        val result = if (depthSupport == RuntimeMetricDepthSupport.UNKNOWN) {
            evaluator.failTransiently(SystemClock.elapsedRealtime())
        } else {
            evaluator.result()
        }
        finishRuntimeMetricPreflight(
            result,
            expectedArSessionGeneration = null,
            expectedLifecycleGeneration = lifecycleGeneration,
        )
    }

    private fun isRuntimeMetricPreflightAttemptCurrent(
        generation: Long,
        lifecycleGeneration: Int,
    ): Boolean =
        metricPreflightFirstRunLease?.let(::isFirstRunAsyncLeaseCurrent) == true &&
            firstRunDeviceCheckAllowsPreflight() &&
            generation == runtimeMetricPreflightGeneration &&
            lifecycleGeneration == feedbackLifecycleGeneration &&
            lifecycleGeneration == metricPreflightLifecycleGeneration &&
            isActivityForeground &&
            !isFinishing &&
            !isDestroyed

    private fun finishRuntimeMetricPreflight(
        result: RuntimeMetricPreflightResult,
        expectedArSessionGeneration: Long?,
        expectedLifecycleGeneration: Int,
    ) {
        val currentSession = synchronized(runtimeMetricStateLock) {
            if (!isRuntimeMetricPreflightAttemptCurrent(result.generation, expectedLifecycleGeneration)) return
            if (
                expectedArSessionGeneration != null &&
                (
                    arSessionPurpose != ArSessionPurpose.PREFLIGHT ||
                        arSessionGeneration != expectedArSessionGeneration
                    )
            ) {
                return
            }
            val ownedSession = session
            session = null
            frameProvider = null
            arSessionPurpose = ArSessionPurpose.NONE
            arSessionGeneration += 1
            runtimeMetricPreflightGeneration += 1
            runtimeMetricPreflightSession = null
            runtimeMetricActivationSession = null
            runtimeMetricOutputAllowed = false
            runtimeMetricInitialNavigationStartPending = false
            runtimeMetricLastValidFrameAtMs = 0L
            runtimeMetricLastFrameTimestampNanos = 0L
            metricPreflightArSessionGeneration = 0L
            pendingMetricPreflightPermissionGeneration = null
            metricPreflightFirstRunLease = null
            ownedSession
        }
        val resumeRenderer = pauseRendererForSessionClose()
        if (currentSession != null) {
            runCatching { currentSession.pause() }
            currentSession.close()
        }
        cameraTextureBound = false
        arCoreSupported = false
        depthSupported = false
        syncActiveSessionScreenPolicy()
        resumeRendererAfterSessionClose(resumeRenderer)

        metricDistanceCapabilityOverride = result.metricDistanceAvailable
        val profileMatch = startupCapabilityProbe.deviceProfileMatch()
        persistRuntimeMetricPreflightResult(result, profileMatch)
        refreshStartupCapabilityUi()
        val detail = when (result.status) {
            RuntimeMetricPreflightStatus.AVAILABLE -> if (profileMatch.approved) {
                "미터 거리와 승인 기기 프로필 ${profileMatch.profileId}/${profileMatch.profileVersion}을 확인했습니다."
            } else {
                "미터 거리는 확인했지만 승인된 지정 기기 프로필이 없어 거리 제한 확인이 필요합니다."
            }
            RuntimeMetricPreflightStatus.UNSUPPORTED -> WALKSAFE_LIMITED_DISTANCE_NOTICE_KO
            RuntimeMetricPreflightStatus.UNKNOWN,
            RuntimeMetricPreflightStatus.IN_PROGRESS,
            -> "거리 기능을 확정할 수 없어 시작을 차단했습니다. 기기 상태를 확인한 뒤 다시 검사하세요."
        }
        updateStatus("기기 거리 기능 확인 완료", detail)
    }

    private fun invalidateRuntimeMetricEvidence(reason: String) {
        val runtimeFailureRequiresSafetyStop =
            reason.startsWith("runtime_") &&
                ::walkSessionLifecycle.isInitialized &&
                walkSessionLifecycle.snapshot().state == WalkSessionState.ACTIVE
        if (runtimeFailureRequiresSafetyStop) {
            transitionWalkSession(WalkSessionEvent.SafetyStopRequested)
            persistWalkSessionInterruptionMarker()
        }
        val invalidated = synchronized(runtimeMetricStateLock) {
            val wasPreflight = arSessionPurpose == ArSessionPurpose.PREFLIGHT
            val wasRuntime = arSessionPurpose == ArSessionPurpose.RUNTIME
            val snapshot = RuntimeMetricInvalidation(
                wasPreflight = wasPreflight,
                wasRuntime = wasRuntime,
                hadAvailableEvidence = metricDistanceCapabilityOverride == true,
                evaluator = runtimeMetricPreflightSession,
                sessionToClose = session.takeIf { wasPreflight || wasRuntime },
            )
            runtimeMetricPreflightGeneration += 1
            pendingMetricPreflightPermissionGeneration = null
            metricPreflightFirstRunLease = null
            runtimeMetricPreflightSession = null
            metricPreflightTerminalDispatchedGeneration = 0L
            metricPreflightArSessionGeneration = 0L
            runtimeMetricActivationSession = null
            runtimeMetricOutputAllowed = false
            runtimeMetricInitialNavigationStartPending = false
            runtimeMetricLastValidFrameAtMs = 0L
            runtimeMetricLastFrameTimestampNanos = 0L
            if (wasPreflight || wasRuntime) {
                session = null
                frameProvider = null
                arSessionPurpose = ArSessionPurpose.NONE
                arSessionGeneration += 1
            }
            snapshot
        }
        invalidated.evaluator?.cancelForLifecycle(SystemClock.elapsedRealtime())
        if (
            invalidated.wasRuntime ||
            invalidated.hadAvailableEvidence ||
            runtimeFailureRequiresSafetyStop
        ) {
            feedbackLifecycleGeneration += 1
            invalidateArCoreAvailabilityRecheck()
            metricDistanceCapabilityOverride = null
            confirmedStartupCapabilityDecision = null
            startupCapabilityConfirmationPending = false
            startupCapabilityRetryRequiresUserAction = false
            pendingCameraFallbackStart = null
            cancelVoiceCommandRecognition()
            synchronized(reportUploadSafetyLock) {
                reportUploadSafetyGeneration += 1L
                reportPrivacyConsentSession.cancelActiveCalls()
            }
            val navigationCancellation = cancelNavigationRequestsForPause()
            clearActiveRouteRequestState(navigationCancellation.routeCancelled)
            clearDestinationSearchState(navigationCancellation.destinationSearchCancelled)
            resetRouteState()
            invalidateFrameStateForPause()
            clearExplicitReportFrameState("runtime_metric_unavailable")
            stopCameraFallbackSession(updateUi = false)
            stopLocationUpdates()
            stopStepTracking()
            if (::earthOrientationTracker.isInitialized) earthOrientationTracker.stop()
            feedbackPolicy.cancelPendingFeedbackDeliveries()
            if (::statusText.isInitialized) {
                pendingTalkBackInteraction?.let(statusText::removeCallbacks)
            }
            pendingTalkBackInteraction = null
            cancelPendingFeedbackTerminalResolution()
            latestFeedbackDeliveryState = FeedbackDeliveryState()
            feedbackActuator?.close()
            feedbackActuator = null
            if (runtimeFailureRequiresSafetyStop) {
                if (::cameraFallbackLifecycleOwner.isInitialized) {
                    cameraFallbackLifecycleOwner.moveTo(Lifecycle.State.CREATED)
                }
                if (::surfaceView.isInitialized) surfaceView.onPause()
            }
            if (::fieldSessionLog.isInitialized && !invalidated.wasPreflight) {
                fieldSessionLog.recordEvent("runtime_metric_evidence_invalidated", mapOf("reason" to reason))
            }
        }
        val resumeRenderer = if (invalidated.sessionToClose != null) pauseRendererForSessionClose() else false
        invalidated.sessionToClose?.let { sessionToClose ->
            runCatching { sessionToClose.pause() }
            sessionToClose.close()
        }
        if (invalidated.sessionToClose != null) {
            cameraTextureBound = false
            arCoreSupported = false
            depthSupported = false
            syncActiveSessionScreenPolicy()
        }
        resumeRendererAfterSessionClose(resumeRenderer)

        if (::startupCapabilityText.isInitialized && isActivityForeground) {
            refreshStartupCapabilityUi()
        }
    }

    private fun pauseRendererForSessionClose(): Boolean {
        val shouldResume = isActivityForeground && ::surfaceView.isInitialized
        if (shouldResume) surfaceView.onPause()
        return shouldResume
    }

    private fun resumeRendererAfterSessionClose(shouldResume: Boolean) {
        if (shouldResume && isWalkSessionRuntimeActive() && !isFinishing && !isDestroyed) {
            surfaceView.onResume()
        }
    }

    private fun confirmStartupCapabilityDecision() {
        val decision = startupCapabilityDecision ?: return
        if (!decision.mayConfirmAndStart || startupCapabilityConfirmationPending) return
        val blockReason = walkSessionReadinessBlockReason(decision)
        if (blockReason != null) {
            maybeRequestMissingWalkSessionPermissions(decision)
            updateStatus("보행 시작 대기", blockReason)
            return
        }
        if (walkSessionLifecycle.snapshot().confirmationToken == null) {
            updateStatus(
                "보행 시작 대기",
                "같은 보행 점검 세대의 필수 기능 확인이 끝나지 않았습니다.",
            )
            return
        }
        startupCapabilityConfirmationPending = true
        refreshStartupCapabilityUi()
        val accepted = deliverStartupCapabilityNotice(
            decision = decision,
            onDelivered = { completeStartupCapabilityConfirmation(decision) },
            onFailed = { failStartupCapabilityConfirmation(decision) },
        )
        if (!accepted) failStartupCapabilityConfirmation(decision)
    }

    private fun deliverStartupCapabilityNotice(
        decision: WalkSafeStartupCapabilityDecision,
        onDelivered: () -> Unit,
        onFailed: () -> Unit,
    ): Boolean {
        if (!isActivityForeground || startupCapabilityDecision != decision) return false
        val generation = feedbackLifecycleGeneration
        val deliveredOnMain = {
            runOnUiThread {
                if (isFeedbackLifecycleCurrent(generation)) onDelivered()
            }
        }
        val failedOnMain = {
            runOnUiThread {
                if (feedbackLifecycleGeneration == generation) onFailed()
            }
        }
        return if (isScreenReaderActive()) {
            announceForTalkBack(
                message = decision.noticeKo,
                priority = TalkBackAnnouncementPriority.INTERACTION,
                onDelivered = deliveredOnMain,
            )
        } else {
            ensureFeedbackActuator().speakInteraction(
                message = decision.noticeKo,
                onCompleted = deliveredOnMain,
                onFailed = failedOnMain,
            ) == NavigationSpeechDispatchResult.ACCEPTED
        }
    }

    private fun completeStartupCapabilityConfirmation(expected: WalkSafeStartupCapabilityDecision) {
        if (!isActivityForeground || startupCapabilityDecision != expected) {
            startupCapabilityConfirmationPending = false
            refreshStartupCapabilityUi()
            return
        }
        startupCapabilityConfirmationPending = false
        startupCapabilityRetryRequiresUserAction = false
        confirmedStartupCapabilityDecision = expected
        refreshStartupCapabilityUi()
    }

    private fun failStartupCapabilityConfirmation(expected: WalkSafeStartupCapabilityDecision) {
        if (startupCapabilityDecision != expected || !startupCapabilityConfirmationPending) return
        startupCapabilityConfirmationPending = false
        confirmedStartupCapabilityDecision = null
        startupCapabilityRetryRequiresUserAction = true
        refreshStartupCapabilityUi()
        updateStatus(
            "안전 제한 안내 확인 필요",
            "필수 안내가 전달되지 않았습니다. 음성 기능을 확인한 뒤 다시 시도하세요.",
        )
    }

    private fun persistStartupCapabilityDecision(decision: WalkSafeStartupCapabilityDecision) {
        if (lastPersistedStartupCapabilityDecision == decision) return
        lastPersistedStartupCapabilityDecision = decision
        val profileMatch = startupCapabilityProbe.deviceProfileMatch()
        stepLengthPrefs.edit()
            .putString("startup_capability_tier", decision.tier.name)
            .putString(
                "startup_capability_unavailable",
                decision.unavailableRequirements.joinToString(",") { it.name },
            )
            .putString(
                "startup_capability_pending",
                decision.pendingRequirements.joinToString(",") { it.name },
            )
            .putString("startup_capability_device_model", Build.MODEL)
            .putString("startup_capability_android_version", Build.VERSION.RELEASE)
            .putString("startup_capability_app_version", BuildConfig.VERSION_NAME)
            .putString("startup_capability_source_commit", BuildConfig.WALKSAFE_SOURCE_COMMIT)
            .putString(
                "startup_capability_device_profile_status",
                if (profileMatch.approved) {
                    "APPROVED_DESIGNATED_DEVICE_PROFILE"
                } else {
                    WALKSAFE_DEVICE_PROFILE_POLICY_STATUS
                },
            )
            .putString("startup_capability_device_profile_id", profileMatch.profileId)
            .putString(
                "startup_capability_device_profile_version",
                profileMatch.profileVersion,
            )
            .putString("startup_capability_device_profile_reason", profileMatch.reason.name)
            .putLong("startup_capability_evaluated_at_epoch_ms", System.currentTimeMillis())
            .apply()
    }

    private fun persistRuntimeMetricPreflightResult(
        result: RuntimeMetricPreflightResult,
        profileMatch: ApprovedDeviceProfileMatch,
    ) {
        stepLengthPrefs.edit()
            .putString("runtime_metric_preflight_policy", RUNTIME_METRIC_PREFLIGHT_POLICY_VERSION)
            .putString("runtime_metric_preflight_status", result.status.name)
            .putString("runtime_metric_preflight_reason", result.reason.name)
            .putLong("runtime_metric_preflight_generation", result.generation)
            .putInt("runtime_metric_preflight_distinct_frames", result.distinctFrameCount)
            .putInt("runtime_metric_preflight_passing_frames", result.passingFrameCount)
            .putLong("runtime_metric_preflight_observation_span_ms", result.observationSpanMs)
            .putLong(
                "runtime_metric_preflight_completed_at_elapsed_ms",
                result.completedAtElapsedRealtimeMs ?: -1L,
            )
            .putLong("runtime_metric_preflight_recorded_at_epoch_ms", System.currentTimeMillis())
            .putString("runtime_metric_preflight_app_version", BuildConfig.VERSION_NAME)
            .putString("runtime_metric_preflight_source_commit", BuildConfig.WALKSAFE_SOURCE_COMMIT)
            .putString("runtime_metric_preflight_device_manufacturer", Build.MANUFACTURER)
            .putString("runtime_metric_preflight_device_model", Build.MODEL)
            .putString("runtime_metric_preflight_device_code", Build.DEVICE)
            .putInt("runtime_metric_preflight_android_sdk", Build.VERSION.SDK_INT)
            .putBoolean("runtime_metric_preflight_profile_approved", profileMatch.approved)
            .putString("runtime_metric_preflight_profile_id", profileMatch.profileId)
            .putString("runtime_metric_preflight_profile_version", profileMatch.profileVersion)
            .putString("runtime_metric_preflight_profile_reason", profileMatch.reason.name)
            .putString(
                "runtime_metric_preflight_profile_registry_id",
                WalkSafeApprovedDeviceProfiles.REGISTRY_ID,
            )
            .putString(
                "runtime_metric_preflight_profile_registry_revision",
                WalkSafeApprovedDeviceProfiles.REGISTRY_REVISION,
            )
            .putString(
                "runtime_metric_preflight_profile_registry_sha256",
                WalkSafeApprovedDeviceProfiles.registryContentSha256,
            )
            .apply()
    }

    private fun isStartupCapabilityConfirmed(): Boolean {
        val decision = startupCapabilityDecision ?: return false
        return decision.mayConfirmAndStart && confirmedStartupCapabilityDecision == decision
    }

    private fun requireStartupCapabilityConfirmation(): Boolean {
        refreshStartupCapabilityUi()
        if (isStartupCapabilityConfirmed()) return true
        return false
    }

    private fun ensurePermissionsThenStart() {
        if (!requireFirstRunOnboardingComplete("runtime_start")) return
        if (!requireStartupCapabilityConfirmation()) return
        if (!requireReporterUserId("login_required_depth_start")) return
        val missing = missingCameraPermissions()
        if (missing.isEmpty()) {
            startConfirmedRuntimeAfterCameraPermission()
            return
        }
        requestPermissionsWithLease(
            missing.toTypedArray(),
            PermissionRequestPurpose.RUNTIME_CAMERA,
        )
    }

    private fun startConfirmedRuntimeAfterCameraPermission() {
        if (!isWalkSessionRuntimeActive()) return
        if (!requireStartupCapabilityConfirmation()) return
        val pendingFallback = pendingCameraFallbackStart
        if (pendingFallback != null) {
            pendingCameraFallbackStart = null
            startCameraFallbackSession(pendingFallback.reason, pendingFallback.availability)
            return
        }
        if (startupCapabilityDecision?.tier == WalkSafeStartupCapabilityTier.LIMITED) {
            val availability = runCatching {
                ArCoreApk.getInstance().checkAvailability(this)
            }.getOrDefault(ArCoreApk.Availability.UNKNOWN_ERROR)
            startCameraFallbackSession(CameraFallbackStartReason.CAPABILITY_DISTANCE_UNAVAILABLE, availability)
            return
        }
        startDepthSession()
    }

    private fun currentReporterUserId(): String? {
        if (!firstRunOnboardingComplete()) return null
        val verifiedActorId =
            firstRunOnboardingSnapshot.reporterActorBinding?.value ?: return null
        if (!::priorityUserOnboardingPolicy.isInitialized) return null
        return reporterUserId.takeIf {
            it == verifiedActorId &&
            priorityUserOnboardingActorId == it &&
                priorityUserOnboardingPolicy.accountBlockReason() == null
        }
    }

    private fun accountDeletionRev0RecoveryBindingOrNull(
        requireReauthentication: Boolean = true,
    ): AccountDeletionRev0RecoveryBinding? {
        if (!::sensitivePrefs.isInitialized || !::stepLengthPrefs.isInitialized) {
            return null
        }
        return runCatching {
            exactAccountDeletionRev0RecoveryBindingOrNull(
                journal = accountDeletionStateMachine.snapshotOrNull(),
                markerState = accountDeletionStartupFallbackState,
                configuredGatewayOrigin = configuredGatewayOriginOrNull(),
                persistedActorHash = sensitivePrefs.getString(
                    PREF_ACCOUNT_DELETION_ACTOR_HASH,
                    null,
                ),
                authorityConfirmed =
                    accountDeletionAuthorityAtStartup.aggregateState ==
                    AccountDeletionAggregateAuthorityState.CONFIRMED &&
                        accountDeletionAuthorityAtStartup.fileState ==
                        AccountDeletionIntentAuthorityState.Confirmed,
                sensitiveFenceConfirmed =
                    sensitivePrefs.getBoolean(
                        PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                        false,
                    ) &&
                        !sensitivePrefs.contains(
                            PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                        ),
                remoteResumeBlocked = accountDeletionRemoteResumeBlocked,
                reauthenticationRequestId =
                    accountDeletionRev0ReauthenticationRequestId,
                requireReauthentication = requireReauthentication,
            )
        }.getOrNull()
    }

    private fun accountDeletionRecoveryLoginRequired(): Boolean =
        accountDeletionStateMachine.durableConfirmationRecoveryRequired() ||
            accountDeletionRev0RecoveryBindingOrNull() != null ||
            accountDeletionLegacyRev0ActorCandidateOrNull() != null

    private fun activateAccountDeletionRev0Reauthentication(
        workerAttempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        val binding =
            accountDeletionRev0RecoveryBindingOrNull(
                requireReauthentication = false,
            ) ?: return false
        if (
            workerAttempt.gatewayOrigin != binding.journal.gatewayOrigin ||
            workerAttempt.installationId != binding.journal.installationId ||
            workerAttempt.requestId != binding.journal.requestId
        ) return false
        accountDeletionRev0ReauthenticationRequestId = binding.journal.requestId
        return accountDeletionRev0RecoveryBindingOrNull() == binding
    }

    private fun bindLegacyAccountDeletionRev0RecoveryActor(
        workerAttempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        if (!::sensitivePrefs.isInitialized || !::stepLengthPrefs.isInitialized) {
            return false
        }
        val processSnapshot = GatewaySessionProcessCoordinator.snapshot()
        val binding = runCatching {
            exactLegacyAccountDeletionRev0ActorBindingOrNull(
                journal = accountDeletionStateMachine.snapshotOrNull(),
                markerState = accountDeletionStartupFallbackState,
                configuredGatewayOrigin = configuredGatewayOriginOrNull(),
                persistedActorHash = sensitivePrefs.getString(
                    PREF_ACCOUNT_DELETION_ACTOR_HASH,
                    null,
                ),
                authorityConfirmed =
                    accountDeletionAuthorityAtStartup.aggregateState ==
                    AccountDeletionAggregateAuthorityState.CONFIRMED &&
                        accountDeletionAuthorityAtStartup.fileState ==
                        AccountDeletionIntentAuthorityState.Confirmed,
                sensitiveFenceConfirmed =
                    sensitivePrefs.getBoolean(
                        PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                        false,
                    ) &&
                        !sensitivePrefs.contains(
                            PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                        ),
                remoteResumeBlocked = accountDeletionRemoteResumeBlocked,
                liveActorId = currentReporterUserId(),
                session = processSnapshot.session,
                processSession = processSnapshot.session,
                deletionRecoveryOnly = processSnapshot.deletionRecoveryOnly,
                storageBlocked = processSnapshot.storageBlocked,
            )
        }.getOrNull() ?: return false
        if (
            workerAttempt.gatewayOrigin != binding.journal.gatewayOrigin ||
            workerAttempt.installationId != binding.journal.installationId ||
            workerAttempt.requestId != binding.journal.requestId
        ) return false
        val boundRecord = binding.markerRecord.copy(
            recoveryActorId = binding.actorId,
        )
        val persisted = runAccountDeletionWorkerDurableStage(workerAttempt) {
            accountDeletionFallbackMarker.update(boundRecord) &&
                accountDeletionFallbackMarker.read() ==
                AndroidAccountDeletionFallbackMarker.State.Present(boundRecord)
        } ?: return false
        if (!persisted) return false
        accountDeletionStartupFallbackState =
            AndroidAccountDeletionFallbackMarker.State.Present(boundRecord)
        accountDeletionLegacyRev0GeneralReauthenticationRequestId = null
        accountDeletionRev0ReauthenticationRequestId = binding.journal.requestId
        val recovered = accountDeletionRev0RecoveryBindingOrNull() ?: return false
        val exactRecovered = recovered.journal == binding.journal &&
            recovered.markerRecord == boundRecord &&
            recovered.actorId == binding.actorId
        if (exactRecovered && processSnapshot.session != null) {
            clearGatewaySession(
                logoutRemote = true,
                expectedSession = processSnapshot.session,
                expectedActivityLease = accountDeletionActivityLease,
            )
        }
        return exactRecovered
    }

    private fun handleLegacyAccountDeletionRev0NotFound(
        workerAttempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        val markerBinding = accountDeletionLegacyRev0MarkerBindingOrNull()
            ?: return false
        if (
            workerAttempt.gatewayOrigin != markerBinding.journal.gatewayOrigin ||
            workerAttempt.installationId != markerBinding.journal.installationId ||
            workerAttempt.requestId != markerBinding.journal.requestId
        ) return false
        val actorBinding = accountDeletionLegacyRev0ActorCandidateOrNull(
            requireReauthentication = false,
        )
        if (actorBinding != null) {
            accountDeletionLegacyRev0GeneralReauthenticationRequestId =
                actorBinding.journal.requestId
            return accountDeletionLegacyRev0ActorCandidateOrNull() == actorBinding
        }
        accountDeletionLegacyRev0GeneralReauthenticationRequestId = null
        runAccountDeletionWorkerTerminalStage(
            workerAttempt,
            "account_deletion_legacy_reauthentication_identity_unavailable",
        ) ?: return true
        integratedConsentSession.failClosed()
        postAccountDeletionUiRefresh()
        return true
    }

    private fun accountDeletionLegacyRev0MarkerBindingOrNull() =
        if (!::sensitivePrefs.isInitialized || !::stepLengthPrefs.isInitialized) {
            null
        } else {
            runCatching {
                exactLegacyAccountDeletionRev0MarkerBindingOrNull(
                    journal = accountDeletionStateMachine.snapshotOrNull(),
                    markerState = accountDeletionStartupFallbackState,
                    configuredGatewayOrigin = configuredGatewayOriginOrNull(),
                    persistedActorHash = sensitivePrefs.getString(
                        PREF_ACCOUNT_DELETION_ACTOR_HASH,
                        null,
                    ),
                    authorityConfirmed =
                        accountDeletionAuthorityAtStartup.aggregateState ==
                        AccountDeletionAggregateAuthorityState.CONFIRMED &&
                            accountDeletionAuthorityAtStartup.fileState ==
                            AccountDeletionIntentAuthorityState.Confirmed,
                    sensitiveFenceConfirmed =
                        sensitivePrefs.getBoolean(
                            PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                            false,
                        ) &&
                            !sensitivePrefs.contains(
                                PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                            ),
                    remoteResumeBlocked = accountDeletionRemoteResumeBlocked,
                )
            }.getOrNull()
        }

    private fun accountDeletionLegacyRev0ActorCandidateOrNull(
        requireReauthentication: Boolean = true,
    ) = accountDeletionLegacyRev0MarkerBindingOrNull()?.let { markerBinding ->
        if (
            requireReauthentication &&
            accountDeletionLegacyRev0GeneralReauthenticationRequestId !=
            markerBinding.journal.requestId
        ) return@let null
        exactLegacyAccountDeletionRev0ActorCandidateOrNull(
            journal = markerBinding.journal,
            markerState = AndroidAccountDeletionFallbackMarker.State.Present(
                markerBinding.markerRecord,
            ),
            configuredGatewayOrigin = configuredGatewayOriginOrNull(),
            persistedActorHash = markerBinding.markerRecord.identity.actorHash,
            authorityConfirmed = true,
            sensitiveFenceConfirmed = true,
            remoteResumeBlocked = false,
            liveActorId = currentReporterUserId(),
        )
    }

    private fun retainAccountDeletionRev0ReauthenticationAfterSessionFailure(
        workerAttempt: AccountDeletionWorkerAttempt,
    ): Boolean {
        val binding = accountDeletionRev0RecoveryBindingOrNull() ?: return false
        return workerAttempt.gatewayOrigin == binding.journal.gatewayOrigin &&
            workerAttempt.installationId == binding.journal.installationId &&
            workerAttempt.requestId == binding.journal.requestId
    }

    private fun clearAccountDeletionRev0RecoverySessionIfCurrent() {
        val binding = accountDeletionRev0RecoveryBindingOrNull() ?: return
        val snapshot = GatewaySessionProcessCoordinator.snapshot()
        val session = snapshot.session ?: return
        if (
            snapshot.deletionRecoveryOnly &&
            session.sessionScope == GatewaySessionScope.ACCOUNT_DELETION_RECOVERY &&
            session.actorId == binding.actorId &&
            session.gatewayBaseUrl == binding.journal.gatewayOrigin
        ) {
            GatewaySessionProcessCoordinator.clear(snapshot.generation)
        }
    }

    private fun completeAccountDeletionRev0ReauthenticationAfterAcceptance(
        journal: AccountDeletionJournal,
    ) {
        if (journal.serverRevision <= 0L) return
        val snapshot = GatewaySessionProcessCoordinator.snapshot()
        val exactRecoverySession = isExactAcceptedRev0RecoverySessionRetirement(
            journal = journal,
            markerState = accountDeletionStartupFallbackState,
            persistedActorHash = sensitivePrefs.getString(
                PREF_ACCOUNT_DELETION_ACTOR_HASH,
                null,
            ),
            session = snapshot.session,
            processSession = snapshot.session,
            deletionRecoveryOnly = snapshot.deletionRecoveryOnly,
        )
        if (
            accountDeletionRev0ReauthenticationRequestId != journal.requestId &&
            !exactRecoverySession
        ) return
        accountDeletionRev0ReauthenticationRequestId = null
        accountDeletionLegacyRev0GeneralReauthenticationRequestId = null
        if (exactRecoverySession) {
            GatewaySessionProcessCoordinator.clear(snapshot.generation)
        }
    }

    private fun accountDeletionRecoveryActorIdOrNull(): String? {
        accountDeletionLegacyRev0ActorCandidateOrNull()?.let { return it.actorId }
        accountDeletionRev0RecoveryBindingOrNull()?.let { return it.actorId }
        if (!accountDeletionStateMachine.durableConfirmationRecoveryRequired()) {
            return null
        }
        val preparedRecord =
            (accountDeletionStartupFallbackState
                as? AndroidAccountDeletionFallbackMarker.State.Present)
                ?.record
                ?.takeIf {
                    it.phase == AndroidAccountDeletionFallbackMarker.Phase.PREPARED &&
                        it.gatewayOrigin == configuredGatewayOriginOrNull()
                }
        preparedRecord?.recoveryActorId?.let { actorId ->
            if (
                sha256Hex(actorId.toByteArray(Charsets.UTF_8)) ==
                preparedRecord.identity.actorHash
            ) return actorId
        }
        if (
            ::firstRunOnboardingSnapshot.isInitialized &&
            firstRunOnboardingSnapshot.isComplete &&
            ::priorityUserOnboardingPolicy.isInitialized
        ) {
            val liveActorId = firstRunOnboardingSnapshot.reporterActorBinding?.value
            if (
                liveActorId != null &&
                reporterUserId == liveActorId &&
                priorityUserOnboardingActorId == liveActorId &&
                priorityUserOnboardingPolicy.accountBlockReason() == null &&
                (
                    preparedRecord == null ||
                        sha256Hex(liveActorId.toByteArray(Charsets.UTF_8)) ==
                        preparedRecord.identity.actorHash
                    )
            ) return liveActorId
        }
        return null
    }

    private fun gatewayLoginActorIdOrNull(): String? =
        if (accountDeletionRecoveryLoginRequired()) {
            accountDeletionRecoveryActorIdOrNull()
        } else {
            currentReporterUserId()
        }

    private fun isAccountDeletionRecoveryLoginTarget(
        actorId: String,
        gatewayBaseUrl: String,
    ): Boolean {
        if (accountDeletionLegacyRev0ActorCandidateOrNull() != null) return false
        return accountDeletionStateMachine.processingBlocked() &&
            accountDeletionRecoveryLoginRequired() &&
            accountDeletionRecoveryActorIdOrNull() == actorId &&
            configuredGatewayOriginOrNull() == gatewayBaseUrl
    }

    private fun accountDeletionRecoveryGatewaySessionOrNull(
        session: GatewayFieldSession? = GatewaySessionProcessCoordinator.snapshot().session,
        speak: Boolean = false,
    ): GatewayFieldSession? {
        val rev0Recovery = accountDeletionRev0RecoveryBindingOrNull()
        val durableConfirmationRecovery =
            accountDeletionStateMachine.durableConfirmationRecoveryRequired()
        val actorId = accountDeletionRecoveryActorIdOrNull()
        val processSnapshot = GatewaySessionProcessCoordinator.snapshot()
        val trustedOrigin = configuredGatewayOriginOrNull()
        val ready =
            accountDeletionStateMachine.processingBlocked() &&
                (durableConfirmationRecovery || rev0Recovery != null) &&
            actorId != null &&
                !processSnapshot.storageBlocked &&
                session != null &&
                processSnapshot.session === session &&
                (
                    if (rev0Recovery != null) {
                        isExactAccountDeletionRev0RecoverySessionReady(
                            binding = rev0Recovery,
                            session = session,
                            processSession = processSnapshot.session,
                            deletionRecoveryOnly =
                                processSnapshot.deletionRecoveryOnly,
                            storageBlocked = processSnapshot.storageBlocked,
                        )
                    } else {
                        (
                            processSnapshot.deletionRecoveryOnly &&
                                session.sessionScope ==
                                GatewaySessionScope.ACCOUNT_DELETION_RECOVERY
                            ) ||
                            (
                            !processSnapshot.deletionRecoveryOnly &&
                                session.sessionScope == GatewaySessionScope.GENERAL &&
                                currentReporterUserId() == actorId
                            )
                    }
                    ) &&
                session.verificationState == GatewaySessionVerificationState.VERIFIED &&
                session.isUsableFor(actorId) &&
                session.gatewayBaseUrl == trustedOrigin
        if (ready) return session
        if (speak) {
            updateNavigationStatus(
                "gateway=session_required reason=account_deletion_recovery",
            )
            speakInteraction("현장 게이트웨이에 다시 로그인하세요.")
        }
        return null
    }

    private fun requireReporterUserId(reason: String): Boolean {
        if (currentReporterUserId() != null) return true
        updateNavigationStatus("login=required reason=$reason")
        speakInteraction("로그인이 필요합니다.")
        return false
    }

    private fun gatewaySessionOrNull(reason: String, speak: Boolean = true): GatewayFieldSession? {
        val session = gatewayFieldSession
        if (isGatewaySessionReadyForCurrentActor(session)) return session
        if (session != null) clearGatewaySession(logoutRemote = false, expectedSession = session)
        if (speak) {
            updateNavigationStatus("gateway=session_required reason=$reason")
            speakInteraction("현장 게이트웨이에 먼저 로그인하세요.")
        }
        return null
    }

    private fun ensureNavigationPermissions(requireActivityRecognition: Boolean) {
        val missing = mutableListOf<String>()
        if (!hasLocationPermission()) {
            missing += Manifest.permission.ACCESS_FINE_LOCATION
            missing += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && requireActivityRecognition && !hasActivityRecognitionPermission()) {
            missing += Manifest.permission.ACTIVITY_RECOGNITION
        }
        if (missing.isEmpty()) return
        requestPermissionsWithLease(
            missing.toTypedArray(),
            PermissionRequestPurpose.NAVIGATION,
        )
    }

    private fun ensureNavigationPermissionForRouteOrStep(): Boolean {
        navigationPermissionsRequestedForRoute = true
        if (isRouteLocationPermissionReady()) return true
        ensureNavigationPermissions(requireActivityRecognition = true)
        return false
    }

    private fun ensureNavigationPermissionForReport(): Boolean {
        navigationPermissionsRequestedForReport = true
        if (hasLocationPermission()) return true
        ensureNavigationPermissions(requireActivityRecognition = false)
        return false
    }

    private fun startNavigationServicesIfNeeded() {
        if (!currentLocationCollectionAllowsWork()) {
            stopLocationUpdates()
            stopStepTracking()
            return
        }
        startLocationUpdatesIfAllowed()
        if (!currentNavigationCollectionAllowsWork()) {
            stopStepTracking()
            return
        }
        val fieldSessionActive = isFieldSessionActive()
        if (isRouteActive || navigationPermissionsRequestedForRoute || navigationPermissionsRequestedForReport || fieldSessionActive) {
            if (isRouteActive || navigationPermissionsRequestedForRoute || fieldSessionActive) {
                startStepTrackingIfAllowed()
            }
        }
    }

    private fun currentLocationCollectionAllowsWork(): Boolean {
        if (!firstRunOnboardingComplete()) return false
        if (!isWalkSessionRuntimeActive() || !isStartupCapabilityConfirmed()) return false
        val guard = officialEnvironmentRuntimeGuard ?: return false
        return walkSessionLifecycle.isRuntimeEpochCurrent(guard.epoch)
    }

    private fun currentNavigationCollectionAllowsWork(): Boolean {
        if (!firstRunOnboardingComplete()) return false
        if (!isWalkSessionRuntimeActive()) return false
        if (!isStartupCapabilityConfirmed()) return false
        if (!officialEnvironmentOutputsAllowed) return false
        if (!phoneMountingOutputsAllowed) return false
        return startupCapabilityDecision?.tier == WalkSafeStartupCapabilityTier.FULL &&
            currentRuntimeMetricOutputAllowsWork()
    }

    private fun missingCameraPermissions(): List<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA.takeIf { !hasCameraPermission() },
        )
        return permissions.filterNotNull()
    }

    private fun currentObservedPermissionSnapshot(): ObservedPermissionSnapshot =
        ObservedPermissionSnapshot(
            cameraGranted = hasCameraPermission(),
            preciseLocationGranted = hasLocationPermission(),
            microphoneGranted = hasRecordAudioPermission(),
            activityRecognitionGranted = hasActivityRecognitionPermission(),
        )

    private fun requiredStartWalkObservedPermissions(): Set<ObservedPermission> =
        WalkStartPermissionPolicy.required(
            activityRecognitionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )

    private fun String.toObservedPermission(): ObservedPermission? = when (this) {
        Manifest.permission.CAMERA -> ObservedPermission.CAMERA
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        -> ObservedPermission.PRECISE_LOCATION
        Manifest.permission.RECORD_AUDIO -> ObservedPermission.MICROPHONE
        Manifest.permission.ACTIVITY_RECOGNITION -> ObservedPermission.ACTIVITY_RECOGNITION
        else -> null
    }

    private fun ObservedPermission.labelKo(): String = when (this) {
        ObservedPermission.CAMERA -> "카메라"
        ObservedPermission.PRECISE_LOCATION -> "정확한 위치"
        ObservedPermission.MICROPHONE -> "마이크"
        ObservedPermission.ACTIVITY_RECOGNITION -> "보행 센서"
    }

    private fun ObservedPermission.denialReasonKo(): String = when (this) {
        ObservedPermission.CAMERA -> "장애물 인식과 신고 전송을 사용할 수 없습니다."
        ObservedPermission.PRECISE_LOCATION -> "거리 측정, 길안내와 신고 전송을 사용할 수 없습니다."
        ObservedPermission.MICROPHONE -> "음성 명령과 음성 재개 확인을 사용할 수 없습니다."
        ObservedPermission.ACTIVITY_RECOGNITION -> "걸음 수 추적을 사용할 수 없습니다."
    }

    private fun persistPermissionRecoveryGate() {
        val editor = stepLengthPrefs.edit()
        if (permissionRecoveryGate.state == PermissionRecoveryGateState.CLEAR) {
            editor
                .remove(PREF_PERMISSION_RECOVERY_GATE_STATE)
                .remove(PREF_PERMISSION_RECOVERY_GATE_ITEMS)
        } else {
            editor
                .putString(
                    PREF_PERMISSION_RECOVERY_GATE_STATE,
                    permissionRecoveryGate.state.name,
                )
                .putString(
                    PREF_PERMISSION_RECOVERY_GATE_ITEMS,
                    permissionRecoveryGate.affectedPermissions
                        .sortedBy(ObservedPermission::ordinal)
                        .joinToString(",") { it.name },
                )
        }
        editor.commit()
    }

    private fun enterPermissionRecoveryBarrier(
        missingPermissions: Set<ObservedPermission>,
        reason: String,
    ) {
        permissionRecoveryGate = permissionRecoveryGate.blocked(missingPermissions)
        persistPermissionRecoveryGate()
        if (::fieldSessionLog.isInitialized) {
            fieldSessionLog.blockActiveSessionRestore()
        }
        showPermissionDenialPanel(
            missingPermissions = missingPermissions,
            reason = reason,
        )
    }

    private fun completePermissionRecoveryRecheckIfPossible() {
        if (!permissionRecoveryGate.blocksAutomaticResourceStart) return
        val observed = currentObservedPermissionSnapshot()
        val missing = WalkStartPermissionPolicy.missing(
            observed = observed,
            activityRecognitionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )
        if (missing.isNotEmpty()) {
            permissionRecoveryGate = permissionRecoveryGate.blocked(missing)
            persistPermissionRecoveryGate()
            updatePermissionRecoveryUi()
            return
        }
        val decision = startupCapabilityDecision ?: run {
            updatePermissionRecoveryUi()
            return
        }
        val allPrerequisitesReady =
            firstRunOnboardingComplete() &&
                currentReporterUserId() != null &&
                isGatewaySessionReadyForCurrentActor() &&
                decision.mayConfirmAndStart &&
                walkSessionReadinessBlockReason(decision) == null
        permissionRecoveryGate = permissionRecoveryGate.completeFullRecheck(
            observed = observed,
            activityRecognitionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            allPrerequisitesReady = allPrerequisitesReady,
        )
        persistPermissionRecoveryGate()
        if (
            permissionRecoveryGate.state ==
            PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME
        ) {
            renderAwaitingExplicitResumeControl()
        }
        updatePermissionRecoveryUi()
    }

    private fun updatePermissionRecoveryUi() {
        if (!::permissionDenialPanel.isInitialized) return
        when (permissionRecoveryGate.state) {
            PermissionRecoveryGateState.CLEAR,
            PermissionRecoveryGateState.REQUEST_PENDING,
            -> permissionDenialPanel.visibility = View.GONE
            PermissionRecoveryGateState.BLOCKED,
            PermissionRecoveryGateState.SETTINGS_PENDING,
            -> showPermissionDenialPanel(
                missingPermissions = permissionRecoveryGate.affectedPermissions,
                reason = "permission_recovery_blocked",
            )
            PermissionRecoveryGateState.RECHECK_REQUIRED -> {
                permissionDenialPanel.visibility = View.VISIBLE
                permissionDenialSummaryText.text =
                    "가입, 동의, 권한과 필수 기능 전체 상태를 다시 확인하고 있습니다. 자동으로 보행, 수집 또는 전송을 시작하지 않습니다."
                permissionDenialSummaryText.contentDescription =
                    permissionDenialSummaryText.text
                permissionDenialConfirmButton.text = "전체 상태 확인 중"
                permissionDenialConfirmButton.isEnabled = false
                permissionDenialSettingsButton.visibility = View.VISIBLE
            }
            PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME -> {
                permissionDenialPanel.visibility = View.VISIBLE
                permissionDenialSummaryText.text =
                    "가입, 동의, 권한과 필수 기능 전체 재확인을 마쳤습니다. 확인하기 전에는 보행, 수집 또는 전송을 시작하지 않습니다."
                permissionDenialSummaryText.contentDescription =
                    permissionDenialSummaryText.text
                permissionDenialConfirmButton.text = "재검사 결과 확인"
                permissionDenialConfirmButton.isEnabled = true
                permissionDenialSettingsButton.visibility = View.GONE
            }
        }
    }

    private fun showPermissionDenialPanel(
        missingPermissions: Set<ObservedPermission>,
        reason: String,
    ) {
        if (!::permissionDenialPanel.isInitialized) return
        val ordered = ObservedPermission.entries.filter(missingPermissions::contains)
        val itemSummary = if (ordered.isEmpty()) {
            "현재 필요한 권한 상태를 확인할 수 없습니다."
        } else {
            ordered.joinToString(" ") { permission ->
                "${permission.labelKo()} 권한: ${permission.denialReasonKo()}"
            }
        }
        val message =
            "필수 권한이 거부되었거나 철회되었습니다. $itemSummary 확인하거나 앱 설정으로 이동하세요."
        permissionDenialPanel.visibility = View.VISIBLE
        permissionDenialSummaryText.text = message
        permissionDenialSummaryText.contentDescription = message
        permissionDenialConfirmButton.text = when (permissionRecoveryGate.state) {
            PermissionRecoveryGateState.BLOCKED -> "확인하고 앱 끝내기"
            else -> "확인"
        }
        permissionDenialConfirmButton.isEnabled = true
        permissionDenialSettingsButton.visibility = View.VISIBLE
        permissionDenialSummaryText.post {
            if (permissionDenialPanel.visibility == View.VISIBLE) {
                permissionDenialSummaryText.requestFocus()
                permissionDenialSummaryText.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                    null,
                )
            }
        }
    }

    private fun onPermissionDenialConfirmed() {
        when (permissionRecoveryGate.state) {
            PermissionRecoveryGateState.BLOCKED -> {
                if (::fieldSessionLog.isInitialized) {
                    fieldSessionLog.blockActiveSessionRestore()
                }
                persistPermissionRecoveryGate()
                finishAndRemoveTask()
            }
            PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME -> {
                permissionDenialPanel.visibility = View.GONE
                renderAwaitingExplicitResumeControl()
                startupCapabilityConfirmButton.post {
                    if (
                        permissionRecoveryGate.state ==
                        PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME &&
                        startupCapabilityConfirmButton.visibility == View.VISIBLE &&
                        startupCapabilityConfirmButton.isEnabled
                    ) {
                        startupCapabilityConfirmButton.requestFocus()
                        startupCapabilityConfirmButton.performAccessibilityAction(
                            android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                            null,
                        )
                    }
                }
                updateStatus(
                    "권한 재개 대기",
                    "차단 상태를 유지합니다. 보행 안내 시작 또는 재개를 다시 선택하세요.",
                )
            }
            PermissionRecoveryGateState.SETTINGS_PENDING,
            PermissionRecoveryGateState.RECHECK_REQUIRED,
            PermissionRecoveryGateState.REQUEST_PENDING,
            PermissionRecoveryGateState.CLEAR,
            -> {
                permissionDenialPanel.visibility = View.GONE
            }
        }
    }

    private fun renderAwaitingExplicitResumeControl() {
        startupCapabilityConfirmButton.visibility = View.VISIBLE
        startupCapabilityConfirmButton.isEnabled = true
        startupCapabilityConfirmButton.text = "보행 안내 다시 시작"
        runtimeControls.visibility = View.GONE
    }

    private fun resumePermissionRecoveryFromExplicitUserAction(): Boolean {
        if (
            permissionRecoveryGate.state !=
            PermissionRecoveryGateState.AWAITING_EXPLICIT_RESUME
        ) {
            return false
        }
        permissionRecoveryGate = permissionRecoveryGate.acknowledgeExplicitResume()
        persistPermissionRecoveryGate()
        ensurePermissionsThenStart()
        return true
    }

    private fun applyObservedPermissionStateChange(reason: String) {
        if (!::walkSessionLifecycle.isInitialized) return
        val decision = PermissionDependencyPolicy.evaluate(
            currentObservedPermissionSnapshot(),
        )
        val sessionSnapshot = walkSessionLifecycle.snapshot()
        if (
            decision.requiresWholeWalkSafetyStop &&
            sessionSnapshot.state in setOf(
                WalkSessionState.ACTIVE,
                WalkSessionState.PAUSED,
            )
        ) {
            val cameraRevoked = !hasCameraPermission()
            val locationRevoked = !hasLocationPermission()
            val safetyStopReason = when {
                cameraRevoked && locationRevoked -> "camera_and_location_permissions_revoked"
                cameraRevoked -> "camera_permission_revoked"
                else -> "location_permission_revoked"
            }
            val missing = requiredStartWalkObservedPermissions()
                .filterNot(currentObservedPermissionSnapshot()::isGranted)
                .toSet()
            enterPermissionRecoveryBarrier(missing, safetyStopReason)
            val detail = when {
                cameraRevoked && locationRevoked ->
                    "안전 필수 카메라와 정확한 위치 권한을 사용할 수 없어 보행 안내를 중지했습니다."
                cameraRevoked ->
                    "안전 필수 카메라 기능을 사용할 수 없어 보행 안내를 중지했습니다."
                else ->
                    "공식 사용환경의 위치 품질을 확인할 수 없어 보행 안내를 중지했습니다."
            }
            enterWalkSessionSafetyStopAndCancelOutputs(safetyStopReason)
            updateStatus(
                "필수 권한 없음 · 안전 중지",
                "$detail 권한을 다시 허용하고 전체 상태를 재확인한 뒤 새 보행을 시작하세요.",
            )
            speakInteraction(
                "$detail 권한을 다시 허용하고 전체 상태를 재확인한 뒤 새 보행을 시작하세요.",
            )
            return
        }
        if (!hasLocationPermission()) {
            navigationPermissionsRequestedForReport = false
            stopLocationUpdates()
            if (sessionSnapshot.state == WalkSessionState.ACTIVE && isRouteActive) {
                navigationRequests.cancelRoute()
                resetRouteState()
                updateNavigationStatus("navigation=stopped location_permission_revoked reason=$reason")
            }
        }
        if (!hasRecordAudioPermission()) cancelVoiceCommandRecognition()
        if (!hasActivityRecognitionPermission()) stopStepTracking()
        if (
            sessionSnapshot.state == WalkSessionState.ACTIVE &&
            (!hasRecordAudioPermission() || !hasActivityRecognitionPermission())
        ) {
            val stoppedOnly = setOfNotNull(
                ObservedPermission.MICROPHONE.takeIf { !hasRecordAudioPermission() },
                ObservedPermission.ACTIVITY_RECOGNITION.takeIf {
                    !hasActivityRecognitionPermission()
                },
            )
            showPermissionDenialPanel(stoppedOnly, reason)
        }
    }

    private fun isRouteLocationPermissionReady(): Boolean = hasLocationPermission()

    private fun hasCameraPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    /** Route arrival and damage coordinates require precise location; approximate-only is insufficient. */
    private fun hasLocationPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasActivityRecognitionPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasRecordAudioPermission(): Boolean {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    private fun startDepthSession() {
        if (!firstRunOnboardingComplete()) return
        if (!isWalkSessionRuntimeActive()) return
        if (!requireStartupCapabilityConfirmation()) return
        if (session != null) {
            updateStatus("ARCore Depth 실행 중", "이미 session이 시작돼 있습니다.")
            return
        }
        arCoreAvailabilityCheckPending = false
        val requestGeneration = ++arCoreAvailabilityRequestGeneration
        val lifecycleGeneration = feedbackLifecycleGeneration
        val availability = ArCoreApk.getInstance().checkAvailability(this)
        if (shouldRecheckArCoreAvailability(availability)) {
            requestArCoreAvailabilityRecheck(requestGeneration, lifecycleGeneration)
            return
        }
        continueDepthSessionStart(availability)
    }

    private fun requestArCoreAvailabilityRecheck(
        requestGeneration: Int,
        lifecycleGeneration: Int,
    ) {
        arCoreSupported = false
        arCoreAvailabilityCheckPending = true
        setActionButton("ARCore 지원 확인 중", ActionMode.START, enabled = false)
        updateStatus("ARCore 지원 확인 중", "기기 지원 상태를 다시 확인하고 있습니다.")
        ArCoreApk.getInstance().checkAvailabilityAsync(this) { availability ->
            if (isArCoreAvailabilityCallbackCurrent(requestGeneration, lifecycleGeneration)) {
                arCoreAvailabilityCheckPending = false
                when {
                    session != null || cameraFallbackRequested -> Unit
                    !hasCameraPermission() -> {
                        setActionButton("ARCore Depth 시작", ActionMode.START, enabled = true)
                        updateStatus("카메라 권한 필요", "ARCore Depth를 시작하려면 카메라 권한을 허용해야 합니다.")
                    }
                    !requireReporterUserId("login_required_depth_start") -> {
                        setActionButton("ARCore Depth 시작", ActionMode.START, enabled = true)
                    }
                    else -> continueDepthSessionStart(availability)
                }
            }
        }
    }

    private fun isArCoreAvailabilityCallbackCurrent(
        requestGeneration: Int,
        lifecycleGeneration: Int,
    ): Boolean {
        return arCoreAvailabilityCheckPending &&
            requestGeneration == arCoreAvailabilityRequestGeneration &&
            lifecycleGeneration == feedbackLifecycleGeneration &&
            isWalkSessionRuntimeActive() &&
            !isFinishing &&
            !isDestroyed
    }

    private fun invalidateArCoreAvailabilityRecheck() {
        val wasPending = arCoreAvailabilityCheckPending
        arCoreAvailabilityRequestGeneration += 1
        arCoreAvailabilityCheckPending = false
        if (wasPending && ::actionButton.isInitialized) {
            setActionButton("ARCore Depth 시작", ActionMode.START, enabled = true)
        }
    }

    private fun continueDepthSessionStart(availability: ArCoreApk.Availability) {
        if (!firstRunOnboardingComplete()) return
        if (!isWalkSessionRuntimeActive()) return
        val gate = resolveArCoreStartGate(availability)
        if (
            gate == ArCoreStartGate.READY &&
            CameraFallbackTestOverride.consumeForceCameraNonMetricFallback(intent)
        ) {
            arCoreSupported = false
            requestCameraFallbackStart(CameraFallbackStartReason.DEBUG_FORCED_SUPPORTED, availability)
            return
        }
        when (gate) {
            ArCoreStartGate.READY -> Unit
            ArCoreStartGate.CAMERA_FALLBACK -> {
                arCoreSupported = false
                requestCameraFallbackStart(CameraFallbackStartReason.ARCORE_AVAILABILITY_UNSUPPORTED, availability)
                return
            }
            ArCoreStartGate.RETRY_LATER -> {
                arCoreSupported = false
                invalidateRuntimeMetricEvidence("runtime_arcore_availability_unknown")
                setActionButton("ARCore 다시 확인", ActionMode.START, enabled = true)
                updateStatus(
                    "ARCore 지원 확인 실패",
                    "네트워크 또는 Google Play Services for AR 상태를 확인한 뒤 다시 시도하세요.",
                )
                return
            }
        }
        arCoreSupported = true
        stopCameraFallbackSession(updateUi = false)

        var candidateSession: Session? = null
        try {
            when (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    installRequested = true
                    pendingSessionStartAfterInstall = false
                    invalidateRuntimeMetricEvidence("runtime_arcore_install_required")
                    setActionButton("기기 거리 기능 다시 확인", ActionMode.START, enabled = true)
                    updateStatus(
                        "ARCore 설치 필요",
                        "Google Play Services for AR 설치 후 기기 거리 기능을 다시 확인해 주세요.",
                    )
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            val newSession = Session(this).also { candidateSession = it }
            val provider = ArCoreFrameProvider(newSession)
            depthSupported = provider.configureDepthMode()
            loadDetectorAfterCameraGate()
            if (!detectorAvailable) {
                newSession.close()
                candidateSession = null
                invalidateRuntimeMetricEvidence("runtime_detector_start_unavailable")
                updateStatus(
                    "객체 탐지 사용 불가 · 안전 중지",
                    "탐지 모델을 사용할 수 없어 보행 기능을 시작하지 않았습니다.",
                )
                return
            }
            if (!depthSupported) {
                newSession.close()
                candidateSession = null
                requestCameraFallbackStart(CameraFallbackStartReason.ARCORE_DEPTH_UNSUPPORTED, availability)
                return
            }
            val runtimeStartedAtMs = SystemClock.elapsedRealtime()
            val resumeRenderer = pauseRendererForSessionClose()
            val runtimeGeneration = try {
                val textureBound = resumeArSessionBeforePublish(newSession)
                synchronized(runtimeMetricStateLock) {
                    session = newSession
                    frameProvider = provider
                    arSessionPurpose = ArSessionPurpose.RUNTIME
                    val generation = ++arSessionGeneration
                    runtimeMetricOutputAllowed = false
                    runtimeMetricInitialNavigationStartPending = true
                    runtimeMetricLastValidFrameAtMs = runtimeStartedAtMs
                    runtimeMetricLastFrameTimestampNanos = 0L
                    runtimeMetricActivationSession = RuntimeMetricPreflightSession(
                        generation = generation,
                        startedAtElapsedRealtimeMs = runtimeStartedAtMs,
                        depthSupport = RuntimeMetricDepthSupport.SUPPORTED,
                    )
                    cameraTextureBound = textureBound
                    generation
                }
            } finally {
                resumeRendererAfterSessionClose(resumeRenderer)
            }
            candidateSession = null
            syncActiveSessionScreenPolicy()
            setActionButton("ARCore Depth 실행 중", ActionMode.START, enabled = false)
            updateStatus(
                status = if (depthSupported) "ARCore 미터 거리 재확인 중" else "ARCore Depth 미지원",
                detail = if (depthSupported) {
                    "새 ARCore 세션의 실제 미터 거리 프레임을 다시 확인한 뒤 보행 출력을 시작합니다."
                } else {
                    "이 기기는 ARCore DepthMode.AUTOMATIC을 지원하지 않아 metric depth가 제한됩니다."
                },
            )
            surfaceView.postDelayed(
                {
                    val activation = runtimeMetricActivationSession
                    if (
                        activation != null &&
                        activation.generation == runtimeGeneration &&
                        arSessionPurpose == ArSessionPurpose.RUNTIME &&
                        arSessionGeneration == runtimeGeneration
                    ) {
                        val result = activation.expire(SystemClock.elapsedRealtime())
                        if (result.status == RuntimeMetricPreflightStatus.UNKNOWN) {
                            handleRuntimeMetricLoss(runtimeGeneration)
                        }
                    }
                },
                RuntimeMetricPreflightPolicy.MAX_DURATION_MS,
            )
            fieldSessionLog.recordEvent(
                "arcore_session_started",
                mapOf(
                    "depth_supported" to depthSupported,
                    "detector_available" to detectorAvailable,
                    "loaded_model" to (detectorLoadedModelKey ?: "none"),
                    "model_fallback_used" to detectorModelFallbackUsed,
                ),
            )
        } catch (_: UnavailableDeviceNotCompatibleException) {
            runCatching { candidateSession?.close() }
            arCoreSupported = false
            stopDepthSession(closeSession = true)
            requestCameraFallbackStart(CameraFallbackStartReason.ARCORE_SESSION_INCOMPATIBLE, availability)
        } catch (error: Exception) {
            runCatching { candidateSession?.close() }
            updateStatus("ARCore 시작 실패", error.message ?: error::class.java.simpleName)
            invalidateRuntimeMetricEvidence("runtime_session_start_failed")
        }
    }

    private fun stopDepthSession(closeSession: Boolean = false) {
        val sessionToStop = synchronized(runtimeMetricStateLock) {
            val ownedSession = session
            session = null
            frameProvider = null
            arSessionPurpose = ArSessionPurpose.NONE
            arSessionGeneration += 1
            runtimeMetricOutputAllowed = false
            runtimeMetricInitialNavigationStartPending = false
            runtimeMetricActivationSession = null
            runtimeMetricLastValidFrameAtMs = 0L
            runtimeMetricLastFrameTimestampNanos = 0L
            ownedSession
        }
        val hadSession = sessionToStop != null
        val resumeRenderer = if (hadSession) pauseRendererForSessionClose() else false
        runCatching { sessionToStop?.pause() }
        if (closeSession) {
            sessionToStop?.close()
        }
        syncActiveSessionScreenPolicy()
        cameraTextureBound = false
        arCoreSupported = false
        depthSupported = false
        latestTactileRouteState = "localRoute=tmap:tactile_not_visible"
        latestReportCandidateStatus = "reportCandidate=blocked"
        detectorGeneration += 1
        latestDetectionSnapshot = DetectionSnapshot.empty()
        latestTactileOverlaySnapshot = DetectionSnapshot.empty()
        lastNonEmptyOverlaySnapshot = DetectionSnapshot.empty()
        tactileOverlayStabilizer.clear()
        lastDetectionRunMs = 0L
        captureLog.clear()
        if (::metadataLogUploader.isInitialized) {
            metadataLogUploader.setEnabled(false)
        }
        if (::debugBboxOverlay.isInitialized) {
            runOnUiThread { debugBboxOverlay.clear() }
        }
        if (::actionButton.isInitialized) setActionButton("ARCore Depth 시작", ActionMode.START, enabled = true)
        if (::debugUploadButton.isInitialized) updateDebugUploadButton()
        if (hadSession && ::fieldSessionLog.isInitialized) {
            fieldSessionLog.recordEvent("arcore_session_stopped", mapOf("closed" to closeSession))
        }
        resumeRendererAfterSessionClose(resumeRenderer)
    }

    private fun bindCameraTextureIfReady() {
        val currentSession = session ?: return
        if (cameraTextureId == 0 || cameraTextureBound) return
        currentSession.setCameraTextureNames(intArrayOf(cameraTextureId))
        cameraTextureBound = true
    }

    private fun resumeArSessionBeforePublish(currentSession: Session): Boolean {
        val textureBound = cameraTextureId != 0
        if (textureBound) {
            currentSession.setCameraTextureNames(intArrayOf(cameraTextureId))
        }
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            currentSession.setDisplayGeometry(displayRotation(), surfaceWidth, surfaceHeight)
        }
        currentSession.resume()
        return textureBound
    }

    private fun updateDisplayGeometryIfReady() {
        val currentSession = session ?: return
        if (surfaceWidth <= 0 || surfaceHeight <= 0) return
        currentSession.setDisplayGeometry(displayRotation(), surfaceWidth, surfaceHeight)
    }

    private fun displayRotation(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
    }

    private fun requestCameraFallbackStart(
        reason: CameraFallbackStartReason,
        availability: ArCoreApk.Availability,
    ) {
        if (
            ::walkSessionLifecycle.isInitialized &&
            walkSessionLifecycle.snapshot().state == WalkSessionState.ACTIVE
        ) {
            enterWalkSessionForegroundRecheckAndCancelOutputs(
                reason = "runtime_mode_changed_to_distance_limited",
            )
        }
        pendingCameraFallbackStart = PendingCameraFallbackStart(reason, availability)
        metricDistanceCapabilityOverride = false
        refreshStartupCapabilityUi()
        if (isStartupCapabilityConfirmed()) {
            pendingCameraFallbackStart = null
            startCameraFallbackSession(reason, availability)
            return
        }
        setActionButton("거리 제한 모드 시작", ActionMode.START, enabled = true)
        updateStatus("거리 제한 확인 필요", WALKSAFE_LIMITED_DISTANCE_NOTICE_KO)
    }

    private fun startCameraFallbackSession(
        reason: CameraFallbackStartReason,
        availability: ArCoreApk.Availability,
    ) {
        if (!firstRunOnboardingComplete()) return
        if (!isWalkSessionRuntimeActive()) return
        if (!hasCameraPermission()) return
        cameraFallbackStartReason = reason
        cameraFallbackAvailability = availability
        loadDetectorAfterCameraGate()
        latestExplicitReportOutput = null
        latestExplicitReportImage = null
        latestExplicitReportGateState = null
        latestExplicitReportCapturedAtMs = 0L
        if (!detectorAvailable) {
            enterWalkSessionSafetyStopAndCancelOutputs("detector_start_unavailable")
            cameraFallbackRequested = false
            cameraFallbackStartReason = null
            cameraFallbackAvailability = null
            latestTactileRouteState = "localRoute=tmap:tactile_not_visible"
            latestReportCandidateStatus = "reportCandidate=blocked:camera_non_metric_unavailable"
            setActionButton("카메라 보조 경고 사용 불가", ActionMode.START, enabled = false)
            updateStatus(
                status = "카메라 보조 경고 사용 불가",
                detail = "탐지기를 사용할 수 없어 보행 기능을 안전 중지했습니다.",
            )
            return
        }
        startNavigationServicesIfNeeded()
        cameraFallbackRequested = true
        latestTactileRouteState = "localRoute=disabled:camera_non_metric_advisory"
        latestReportCandidateStatus = "reportCandidate=blocked:camera_non_metric_tier"
        setActionButton("카메라 보조 경고 실행 중", ActionMode.START, enabled = false)
        updateStatus(
            status = "카메라 보조 경고 · 목적지 없이 사용 가능",
            detail = "카메라·탐지기·IMU가 준비되면 화면 기준 왼쪽/가운데/오른쪽 후보만 안내합니다. " +
                "거리·걸음 수·안전 경로·자동 신고는 사용하지 않습니다.",
        )
        bindCameraFallbackSession()
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    private fun bindCameraFallbackSession() {
        val expectedWalkEpoch = walkSessionLifecycle.currentRuntimeEpochOrNull() ?: return
        if (!cameraFallbackRequested || cameraFallbackRunning || !detectorAvailable) return
        val startReason = cameraFallbackStartReason ?: return
        val availabilityState = cameraFallbackAvailability?.name ?: return
        val generation = ++cameraFallbackGeneration
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener(
            {
                if (
                    !isCameraFallbackLeaseCurrent(expectedWalkEpoch, generation) ||
                    isDestroyed
                ) {
                    return@addListener
                }
                try {
                    val provider = providerFuture.get()
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(CAMERA_FALLBACK_WIDTH, CAMERA_FALLBACK_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                            ),
                        )
                        .build()
                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setTargetRotation(displayRotation())
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setOutputImageRotationEnabled(true)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    val preview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .setTargetRotation(displayRotation())
                        .build()
                    preview.setSurfaceProvider(cameraFallbackPreviewView.surfaceProvider)
                    analysis.setAnalyzer(detectorExecutor) { imageProxy ->
                        analyzeCameraFallbackFrame(
                            imageProxy,
                            expectedWalkEpoch,
                            generation,
                        )
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        cameraFallbackLifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                    cameraFallbackProvider = provider
                    cameraFallbackAnalysis = analysis
                    cameraFallbackRunning = true
                    syncActiveSessionScreenPolicy()
                    cameraFallbackPreviewView.visibility = View.VISIBLE
                    lastCameraFallbackAnalysisMs = 0L
                    cameraFallbackFrameAnalyzedGeneration = -1
                    fieldSessionLog.recordEvent(
                        "camera_non_metric_session_started",
                        mapOf(
                            "loaded_model" to (detectorLoadedModelKey ?: "none"),
                            "model_fallback_used" to detectorModelFallbackUsed,
                            "metric" to false,
                            "reports_allowed" to false,
                            "reason" to startReason.fieldValue,
                            "state" to availabilityState,
                        ),
                    )
                    updateStatus(
                        status = "카메라 보조 경고 · 목적지 없이 사용 가능",
                        detail = "카메라·탐지기·IMU가 유효할 때만 3개 연속 프레임에서 확인된 화면 기준 후보를 안내합니다. " +
                            "거리·걸음 수·안전 경로·자동 신고는 사용하지 않습니다.",
                    )
                } catch (error: Exception) {
                    if (!isCameraFallbackLeaseCurrent(expectedWalkEpoch, generation)) {
                        return@addListener
                    }
                    cameraFallbackRunning = false
                    enterWalkSessionSafetyStopAndCancelOutputs("camera_fallback_bind_failed")
                    stopCameraFallbackSession(updateUi = false)
                    syncActiveSessionScreenPolicy()
                    latestReportCandidateStatus = "reportCandidate=blocked:camera_non_metric_unavailable"
                    setActionButton("카메라 보조 경고 다시 시도", ActionMode.START, enabled = true)
                    updateStatus(
                        "카메라 보조 경고 실패",
                        error.message ?: error::class.java.simpleName,
                    )
                }
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    private fun isCameraFallbackLeaseCurrent(
        expectedWalkEpoch: WalkRuntimeEpoch,
        expectedFallbackGeneration: Int,
        requireRunning: Boolean = false,
    ): Boolean =
        firstRunOnboardingComplete() &&
            walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) &&
            cameraFallbackRequested &&
            expectedFallbackGeneration == cameraFallbackGeneration &&
            (!requireRunning || cameraFallbackRunning)

    private fun stopCameraFallbackSession(updateUi: Boolean = true) {
        val wasRequested = cameraFallbackRequested
        cameraFallbackRequested = false
        cameraFallbackRunning = false
        syncActiveSessionScreenPolicy()
        cameraFallbackGeneration += 1
        cameraFallbackAnalysis?.clearAnalyzer()
        cameraFallbackProvider?.unbindAll()
        cameraFallbackAnalysis = null
        cameraFallbackProvider = null
        if (::cameraFallbackPreviewView.isInitialized) {
            cameraFallbackPreviewView.visibility = View.GONE
        }
        lastCameraFallbackAnalysisMs = 0L
        cameraFallbackFrameAnalyzedGeneration = -1
        nonMetricAdvisoryPolicy.reset()
        if (wasRequested && ::fieldSessionLog.isInitialized) {
            fieldSessionLog.recordEvent("camera_non_metric_session_stopped")
        }
        if (updateUi && ::actionButton.isInitialized) {
            setActionButton("ARCore Depth 시작", ActionMode.START, enabled = true)
        }
        cameraFallbackStartReason = null
        cameraFallbackAvailability = null
    }

    @androidx.annotation.OptIn(markerClass = [ExperimentalGetImage::class])
    private fun analyzeCameraFallbackFrame(
        imageProxy: ImageProxy,
        expectedWalkEpoch: WalkRuntimeEpoch,
        expectedFallbackGeneration: Int,
    ) {
        try {
            val nowMs = SystemClock.elapsedRealtime()
            if (
                !isActivityForeground ||
                !isCameraFallbackLeaseCurrent(
                    expectedWalkEpoch,
                    expectedFallbackGeneration,
                    requireRunning = true,
                )
            ) return
            if (nowMs - lastCameraFallbackAnalysisMs < CAMERA_FALLBACK_ANALYSIS_INTERVAL_MS) return
            lastCameraFallbackAnalysisMs = nowMs
            val lifecycleGeneration = feedbackLifecycleGeneration
            val detectorFrameGeneration = detectorGeneration
            val mediaImage = imageProxy.image ?: return
            observeOfficialEnvironmentCameraFrame(
                epoch = expectedWalkEpoch,
                observedAtElapsedRealtimeMs = nowMs,
                frameAvailable = true,
            )
            if (!walkSafetyOutputsAllowed()) return
            var detectorSucceeded = false
            val result = try {
                frameDetector.detect(
                    cameraImage = mediaImage,
                    timestampMs = imageProxy.imageInfo.timestamp / NANOS_PER_MILLISECOND,
                ).also {
                    if (
                        isCurrentFrameGeneration(
                            detectorFrameGeneration,
                            expectedWalkEpoch,
                        )
                    ) {
                        detectorRuntimeSupervisor.onSuccess()
                    }
                    detectorSucceeded = true
                }
            } catch (error: RuntimeException) {
                handleDetectorRuntimeFailure(
                    error,
                    detectorFrameGeneration,
                    expectedWalkEpoch,
                )
                AndroidDetectionResult.empty()
            }
            if (
                !isCameraFallbackLeaseCurrent(
                    expectedWalkEpoch,
                    expectedFallbackGeneration,
                    requireRunning = true,
                ) ||
                lifecycleGeneration != feedbackLifecycleGeneration ||
                !isCurrentFrameGeneration(
                    detectorFrameGeneration,
                    expectedWalkEpoch,
                ) ||
                !walkSafetyOutputsAllowed() ||
                !isActivityForeground
            ) return
            if (detectorSucceeded) {
                recordCameraFallbackFrameAnalyzedOnce(
                    expectedWalkEpoch,
                    expectedFallbackGeneration,
                    lifecycleGeneration,
                )
            }
            val advisoryGate = currentCameraFallbackAdvisoryGate(nowMs)
            val tier = resolveAndroidLocalTactileTier(advisoryGate)
            if (detectorSucceeded) {
                recordCameraFallbackInferenceSample(result, tier, advisoryGate, nowMs)
            }
            val action = nonMetricAdvisoryPolicy.evaluate(
                detections = result.detections,
                gate = advisoryGate,
                frameId = imageProxy.imageInfo.timestamp,
                nowMs = nowMs,
            )
            if (
                !isCameraFallbackLeaseCurrent(
                    expectedWalkEpoch,
                    expectedFallbackGeneration,
                    requireRunning = true,
                ) ||
                lifecycleGeneration != feedbackLifecycleGeneration ||
                !isCurrentFrameGeneration(
                    detectorFrameGeneration,
                    expectedWalkEpoch,
                ) ||
                !walkSafetyOutputsAllowed() ||
                !isActivityForeground
            ) {
                nonMetricAdvisoryPolicy.reset()
                return
            }
            if (tier == AndroidLocalTactileTier.CAMERA_IMU_NON_METRIC && action != null) {
                runOnUiThread {
                    emitCameraFallbackAdvisory(
                        action,
                        expectedWalkEpoch,
                        expectedFallbackGeneration,
                        lifecycleGeneration,
                    )
                }
            }
        } finally {
            imageProxy.close()
        }
    }

    private fun recordCameraFallbackFrameAnalyzedOnce(
        expectedWalkEpoch: WalkRuntimeEpoch,
        fallbackGeneration: Int,
        lifecycleGeneration: Int,
    ) {
        if (
            cameraFallbackFrameAnalyzedGeneration == fallbackGeneration ||
            !isCameraFallbackLeaseCurrent(
                expectedWalkEpoch,
                fallbackGeneration,
                requireRunning = true,
            ) ||
            lifecycleGeneration != feedbackLifecycleGeneration ||
            !isActivityForeground ||
            !cameraFallbackRunning
        ) return
        cameraFallbackFrameAnalyzedGeneration = fallbackGeneration
        fieldSessionLog.recordEvent(
            "camera_non_metric_frame_analyzed",
            mapOf(
                "loaded_model" to (detectorLoadedModelKey ?: "none"),
                "model_fallback_used" to detectorModelFallbackUsed,
                "metric" to false,
                "reports_allowed" to false,
                "state" to "detector_succeeded",
            ),
        )
    }

    private fun recordCameraFallbackInferenceSample(
        result: AndroidDetectionResult,
        tier: AndroidLocalTactileTier,
        advisoryGate: NonMetricAdvisoryGate,
        nowMs: Long,
    ) {
        fieldSessionLog.appendCameraNonMetricSample(
            CameraNonMetricFieldSample(
                elapsedRealtimeMs = nowMs,
                inferenceMs = result.timing.totalMs ?: return,
                detectionCount = result.detections.size,
                capabilityTier = tier.name,
                cameraPermissionGranted = advisoryGate.cameraPermissionGranted,
                cameraFallbackRunning = advisoryGate.cameraFallbackRunning,
                detectorAvailable = advisoryGate.detectorAvailable,
                imuFresh = advisoryGate.imuFresh,
                tmapRouteActive = advisoryGate.tmapRouteActive,
            ),
        )
    }

    private fun emitCameraFallbackAdvisory(
        action: NonMetricObstacleAdvisoryAction,
        expectedWalkEpoch: WalkRuntimeEpoch,
        fallbackGeneration: Int,
        lifecycleGeneration: Int,
    ) {
        if (
            !isCameraFallbackAdvisoryStillDeliverable(
                action,
                expectedWalkEpoch,
                fallbackGeneration,
                lifecycleGeneration,
            )
        ) {
            nonMetricAdvisoryPolicy.rejectDelivery(action)
            return
        }
        if (!nonMetricAdvisoryPolicy.claimDelivery(action)) return
        if (
            !isCameraFallbackAdvisoryStillDeliverable(
                action,
                expectedWalkEpoch,
                fallbackGeneration,
                lifecycleGeneration,
            ) ||
            !nonMetricAdvisoryPolicy.isDeliveryCurrent(action)
        ) {
            nonMetricAdvisoryPolicy.rejectDelivery(action)
            return
        }
        val speechAccepted = speakAdvisory(
            action = action,
            expectedWalkEpoch = expectedWalkEpoch,
            fallbackGeneration = fallbackGeneration,
            lifecycleGeneration = lifecycleGeneration,
            onDelivered = {
                runOnUiThread {
                    if (
                        isCameraFallbackAdvisoryStillDeliverable(
                            action,
                            expectedWalkEpoch,
                            fallbackGeneration,
                            lifecycleGeneration,
                        ) &&
                        nonMetricAdvisoryPolicy.isDeliveryCurrent(action)
                    ) {
                        confirmCameraFallbackAdvisoryDelivery(
                            action = action,
                            expectedWalkEpoch = expectedWalkEpoch,
                            fallbackGeneration = fallbackGeneration,
                            lifecycleGeneration = lifecycleGeneration,
                        )
                    } else {
                        nonMetricAdvisoryPolicy.rejectDelivery(action)
                    }
                }
            },
            onFailed = { nonMetricAdvisoryPolicy.rejectDelivery(action) },
        )
        if (!speechAccepted) nonMetricAdvisoryPolicy.rejectDelivery(action)
    }

    private fun confirmCameraFallbackAdvisoryDelivery(
        action: NonMetricObstacleAdvisoryAction,
        expectedWalkEpoch: WalkRuntimeEpoch,
        fallbackGeneration: Int,
        lifecycleGeneration: Int,
    ) {
        if (
            !isCameraFallbackAdvisoryStillDeliverable(
                action,
                expectedWalkEpoch,
                fallbackGeneration,
                lifecycleGeneration,
            ) ||
            !nonMetricAdvisoryPolicy.isDeliveryCurrent(action)
        ) {
            nonMetricAdvisoryPolicy.rejectDelivery(action)
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        val confirmed = synchronized(phoneMountingObservationLock) {
            if (!phoneMountingOutputsAllowed) {
                nonMetricAdvisoryPolicy.rejectDelivery(action)
                false
            } else {
                nonMetricAdvisoryPolicy.confirmDelivery(action, nowMs)
            }
        }
        if (!confirmed) return
        fieldSessionLog.recordEvent(
            "camera_non_metric_advisory_emitted",
            mapOf(
                "direction" to action.direction.name,
                "loaded_model" to (detectorLoadedModelKey ?: "none"),
                "model_fallback_used" to detectorModelFallbackUsed,
                "metric" to false,
                "tmap_authoritative" to true,
                "tmap_route_active" to currentCameraFallbackAdvisoryGate(nowMs).tmapRouteActive,
                "reports_allowed" to false,
            ),
        )
    }

    private fun isCameraFallbackAdvisoryStillDeliverable(
        action: NonMetricObstacleAdvisoryAction,
        expectedWalkEpoch: WalkRuntimeEpoch,
        fallbackGeneration: Int,
        lifecycleGeneration: Int,
    ): Boolean {
        val nowMs = SystemClock.elapsedRealtime()
        return nowMs <= action.validUntilMs &&
            officialEnvironmentOutputsAllowed &&
            phoneMountingOutputsAllowed &&
            isCameraFallbackLeaseCurrent(
                expectedWalkEpoch,
                fallbackGeneration,
                requireRunning = true,
            ) &&
            lifecycleGeneration == feedbackLifecycleGeneration &&
            currentAndroidLocalTactileTier(nowMs) == AndroidLocalTactileTier.CAMERA_IMU_NON_METRIC
    }

    private fun currentAndroidLocalTactileTier(nowMs: Long): AndroidLocalTactileTier {
        return resolveAndroidLocalTactileTier(currentCameraFallbackAdvisoryGate(nowMs))
    }

    private fun currentCameraFallbackAdvisoryGate(nowMs: Long): NonMetricAdvisoryGate {
        return NonMetricAdvisoryGate(
            cameraPermissionGranted = hasCameraPermission(),
            cameraFallbackRunning = cameraFallbackRunning,
            detectorAvailable = detectorAvailable,
            imuFresh = hasFreshCameraFallbackImu(nowMs),
            tmapRouteActive = hasFreshTmapFallbackContext(nowMs),
        )
    }

    private fun resolveAndroidLocalTactileTier(
        advisoryGate: NonMetricAdvisoryGate,
    ): AndroidLocalTactileTier {
        return AndroidLocalTactileCapability.resolve(
            AndroidLocalTactileCapabilityInput(
                cameraPermissionGranted = advisoryGate.cameraPermissionGranted,
                detectorAvailable = advisoryGate.detectorAvailable,
                arCoreSupported = arCoreSupported,
                depthSupported = depthSupported,
                arSessionRunning = session != null,
                cameraFallbackRunning = advisoryGate.cameraFallbackRunning,
                imuFresh = advisoryGate.imuFresh,
                tmapRouteActive = advisoryGate.tmapRouteActive,
            ),
        )
    }

    private fun hasFreshCameraFallbackImu(nowMs: Long): Boolean {
        val orientation = earthOrientationTracker.latest() ?: return false
        val ageMs = nowMs - orientation.observedAtElapsedRealtimeMs
        return ageMs in 0L..CAMERA_FALLBACK_IMU_MAX_AGE_MS &&
            orientation.accuracy != EarthOrientationAccuracy.UNRELIABLE &&
            orientation.deviceToMagneticEnu.isOrthonormal()
    }

    private fun hasFreshTmapFallbackContext(nowMs: Long): Boolean {
        val location = LocationTrustPolicy.freshOrNull(latestTrustedLocation, nowMs) ?: return false
        return isRouteActive &&
            latestTmapOnRoute &&
            !routeRequestInFlight.get() &&
            routeNavigator.hasRoute() &&
            location.accuracyM.isFinite()
    }

    /**
     * Runs inference off the GL thread. Published snapshots retain both capture and completion
     * timestamps so a fast partial result cannot be mistaken for a fresh current-frame result.
     */
    private fun scheduleDetectionIfDue(
        provider: ArCoreFrameProvider,
        frame: Frame,
        timestampMs: Long,
        capturedAtMs: Long,
        elapsedRealtimeMs: Long,
        capturedDepthSnapshot: DepthFrameSnapshot,
        frameGeneration: Int,
        expectedWalkEpoch: WalkRuntimeEpoch,
    ) {
        val shouldCaptureFrame = synchronized(frameStateLock) {
            if (!isCurrentFrameGeneration(frameGeneration, expectedWalkEpoch)) return
            if (!detectorAvailable) return
            if (elapsedRealtimeMs - lastDetectionRunMs < DETECTION_INTERVAL_MS) return
            if (!detectionInFlight.compareAndSet(false, true)) return
            lastDetectionRunMs = elapsedRealtimeMs
            frameCaptureRequested.getAndSet(false)
        }
        val cameraImage = provider.acquireCameraImageOrNull(frame)
        if (cameraImage == null) {
            detectionInFlight.set(false)
            return
        }
        val generation = frameGeneration
        val imageWidth = cameraImage.width
        val imageHeight = cameraImage.height
        val detectionIdentity = AndroidDetectionSnapshotFrameIdentity(
            captureFrameId = frame.timestamp,
            frameTimestampMs = timestampMs,
        )
        val frameEvidence = DetectionFrameEvidence(
            frameId = frame.timestamp,
            frameTimestampMs = timestampMs,
            depthSnapshot = capturedDepthSnapshot,
            depthMapper = createFrozenDepthMapper(
                frame = frame,
                frameId = frame.timestamp,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                snapshot = capturedDepthSnapshot,
            ),
            tactileContext = buildTactileProjectionContext(frame, elapsedRealtimeMs),
            motionContext = buildDepthMotionContext(elapsedRealtimeMs),
            navigationActive = isRouteActive && !routeRequestInFlight.get(),
            tmapOnRoute = latestTmapOnRoute,
        )
        try {
            detectorExecutor.execute {
                if (!isCurrentFrameGeneration(generation, expectedWalkEpoch)) {
                    cameraImage.close()
                    detectionInFlight.set(false)
                    return@execute
                }
                val rawCollectionAllowed = integratedConsentSession.isAllowed(
                    IntegratedConsentItem.RAW_SOURCE_COLLECTION,
                )
                val reportJpeg = if (rawCollectionAllowed) {
                    encodeReportFrameJpeg(cameraImage)
                } else {
                    null
                }
                val captureJpeg = if (
                    shouldCaptureFrame &&
                    rawCollectionAllowed &&
                    sensitiveDebugTransferAllowed()
                ) {
                    encodeDebugFrameJpeg(cameraImage) ?: reportJpeg
                } else {
                    null
                }
                val startedAtMs = System.currentTimeMillis()
                var completedAtMs = startedAtMs
                val result = try {
                    cameraImage.use { image ->
                        frameDetector.detect(
                            image,
                            timestampMs = timestampMs,
                        ) { partialResult ->
                            this@MainActivity.publishDetectionSnapshot(
                                result = partialResult,
                                generation = generation,
                                detectionIdentity = detectionIdentity,
                                capturedAtMs = capturedAtMs,
                                startedAtMs = startedAtMs,
                                completedAtMs = System.currentTimeMillis(),
                                imageWidth = imageWidth,
                                imageHeight = imageHeight,
                                reportImageJpeg = reportJpeg,
                                frameEvidence = frameEvidence,
                                expectedWalkEpoch = expectedWalkEpoch,
                            )
                        }.also {
                            if (isCurrentFrameGeneration(generation, expectedWalkEpoch)) {
                                detectorRuntimeSupervisor.onSuccess()
                            }
                        }
                    }
                } catch (error: RuntimeException) {
                    handleDetectorRuntimeFailure(error, generation, expectedWalkEpoch)
                    AndroidDetectionResult.empty()
                } finally {
                    completedAtMs = System.currentTimeMillis()
                    detectionInFlight.set(false)
                }
                val published = this@MainActivity.publishDetectionSnapshot(
                    result = result,
                    generation = generation,
                    detectionIdentity = detectionIdentity,
                    capturedAtMs = capturedAtMs,
                    startedAtMs = startedAtMs,
                    completedAtMs = completedAtMs,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    reportImageJpeg = reportJpeg,
                    frameEvidence = frameEvidence,
                    expectedWalkEpoch = expectedWalkEpoch,
                )
                if (published != null) {
                    synchronized(frameStateLock) {
                        if (!isCurrentFrameGeneration(generation, expectedWalkEpoch)) {
                            return@synchronized
                        }
                        if (captureJpeg != null) {
                            runIfActivityOriginalUploadAllowed {
                                frameCaptureUploader.upload(
                                    imageJpeg = captureJpeg,
                                    metadataJson = buildFrameCaptureMetadataJson(
                                        frameTimestampMs = timestampMs,
                                        imageWidth = imageWidth,
                                        imageHeight = imageHeight,
                                        detectDurationMs = completedAtMs - startedAtMs,
                                        result = result,
                                        forcedCustom = shouldCaptureFrame,
                                    ),
                                )
                            }
                        }
                    }
                    if (shouldCaptureFrame) {
                        runOnUiThread {
                            if (isCurrentFrameGeneration(generation, expectedWalkEpoch)) {
                                updateFrameCaptureButton()
                            }
                        }
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            cameraImage.close()
            detectionInFlight.set(false)
        }
    }

    private fun encodeDebugFrameJpeg(cameraImage: android.media.Image): ByteArray? {
        return try {
            val argb = frameCapturePreprocessor.decode(cameraImage)
            argb.toScaledJpeg(maxDimension = DEBUG_FRAME_CAPTURE_MAX_DIMENSION, quality = DEBUG_FRAME_CAPTURE_JPEG_QUALITY)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun encodeReportFrameJpeg(cameraImage: android.media.Image): ByteArray? {
        return try {
            val argb = frameCapturePreprocessor.decode(cameraImage)
            argb.toScaledJpeg(
                maxDimension = REPORT_FRAME_CAPTURE_MAX_DIMENSION,
                quality = REPORT_FRAME_CAPTURE_JPEG_QUALITY,
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    internal fun publishDetectionSnapshot(
        result: AndroidDetectionResult,
        generation: Int,
        detectionIdentity: AndroidDetectionSnapshotFrameIdentity,
        capturedAtMs: Long,
        startedAtMs: Long,
        completedAtMs: Long,
        imageWidth: Int,
        imageHeight: Int,
        reportImageJpeg: ByteArray?,
        frameEvidence: DetectionFrameEvidence,
        expectedWalkEpoch: WalkRuntimeEpoch? = null,
    ): DetectionSnapshot? = synchronized(frameStateLock) {
        if (
            generation != detectorGeneration ||
            (
                expectedWalkEpoch != null &&
                    (
                        !walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) ||
                            !walkSafetyOutputsAllowed()
                    )
            )
        ) return@synchronized null
        DetectionSnapshot.published(
            result = result,
            identity = detectionIdentity,
            capturedAtMs = capturedAtMs,
            startedAtMs = startedAtMs,
            completedAtMs = completedAtMs,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            reportImageJpeg = reportImageJpeg,
            frameEvidence = frameEvidence,
        ).also { snapshot ->
            latestDetectionSnapshot = snapshot
            if (snapshot.hasTactileDetection()) {
                latestTactileOverlaySnapshot = snapshot.copy(detections = snapshot.tactileDetections())
            }
        }
    }

    // Frozen FP-017 trace markers; the live contract below additionally binds
    // every callback to the exact walk epoch and component generation.
    /*
private fun isCurrentFrameGeneration(generation: Int)
generation != cameraFallbackGeneration
    */
    private fun isCurrentFrameGeneration(
        generation: Int,
        expectedWalkEpoch: WalkRuntimeEpoch? = null,
    ): Boolean =
        firstRunOnboardingComplete() &&
            isWalkSessionRuntimeActive() &&
            generation == detectorGeneration &&
            (
                expectedWalkEpoch == null ||
                    walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch)
            )

    private fun selectOverlayDetections(
        detectionSnapshot: DetectionSnapshot,
        nowMs: Long,
        currentFrameTimestampMs: Long,
    ): OverlayDetectionSelection {
        val overlaySourceAgeLimitMs = if (detectionSnapshot.hasTactileDetection()) {
            MAX_TACTILE_OVERLAY_SOURCE_AGE_MS
        } else {
            MAX_OVERLAY_DETECTION_SOURCE_AGE_MS
        }
        val overlayFrameDeltaLimitMs = if (detectionSnapshot.hasTactileDetection()) {
            MAX_TACTILE_OVERLAY_FRAME_DELTA_MS
        } else {
            MAX_OVERLAY_DETECTION_FRAME_DELTA_MS
        }
        val freshOverlayDetections = detectionSnapshot.freshDetections(
            nowMs = nowMs,
            currentFrameTimestampMs = currentFrameTimestampMs,
            maxSourceAgeMs = overlaySourceAgeLimitMs,
            maxFrameDeltaMs = overlayFrameDeltaLimitMs,
        )
        val latestOverlayStaleReason = detectionSnapshot.staleReason(
            nowMs = nowMs,
            currentFrameTimestampMs = currentFrameTimestampMs,
            maxSourceAgeMs = overlaySourceAgeLimitMs,
            maxFrameDeltaMs = overlayFrameDeltaLimitMs,
        )
        if (freshOverlayDetections.isNotEmpty()) {
            lastNonEmptyOverlaySnapshot = detectionSnapshot
        }

        val tactileOverlaySnapshot = latestTactileOverlaySnapshot
        val heldTactileDetections = tactileOverlaySnapshot.freshDetections(
            nowMs = nowMs,
            currentFrameTimestampMs = currentFrameTimestampMs,
            maxSourceAgeMs = MAX_TACTILE_OVERLAY_HOLD_SOURCE_AGE_MS,
            maxFrameDeltaMs = MAX_TACTILE_OVERLAY_HOLD_FRAME_DELTA_MS,
        ).filter { it.isTactileDetection() }
        val mergedFreshDetections = mergeOverlayDetections(
            currentDetections = freshOverlayDetections,
            heldTactileDetections = heldTactileDetections,
        )
        if (mergedFreshDetections.isNotEmpty()) {
            val mappingSnapshot = when {
                detectionSnapshot.hasImageSize() -> detectionSnapshot
                tactileOverlaySnapshot.hasImageSize() -> tactileOverlaySnapshot
                else -> lastNonEmptyOverlaySnapshot
            }
            return OverlayDetectionSelection(
                detections = mergedFreshDetections,
                mappingSnapshot = mappingSnapshot,
                debugState = buildOverlayDebugState(
                    selection = if (freshOverlayDetections.size == mergedFreshDetections.size) {
                        "fresh_latest"
                    } else {
                        "fresh_plus_held_tactile"
                    },
                    detections = mergedFreshDetections,
                    mappingSnapshot = mappingSnapshot,
                    nowMs = nowMs,
                    currentFrameTimestampMs = currentFrameTimestampMs,
                    staleReason = latestOverlayStaleReason,
                    holdApplied = freshOverlayDetections.size != mergedFreshDetections.size,
                ),
            )
        }

        val overlaySnapshot = lastNonEmptyOverlaySnapshot
        val holdSourceAgeLimitMs = if (overlaySnapshot.hasTactileDetection()) {
            MAX_TACTILE_OVERLAY_HOLD_SOURCE_AGE_MS
        } else {
            MAX_OVERLAY_HOLD_SOURCE_AGE_MS
        }
        val holdFrameDeltaLimitMs = if (overlaySnapshot.hasTactileDetection()) {
            MAX_TACTILE_OVERLAY_HOLD_FRAME_DELTA_MS
        } else {
            MAX_OVERLAY_HOLD_FRAME_DELTA_MS
        }
        val heldOverlayDetections = overlaySnapshot.freshDetections(
            nowMs = nowMs,
            currentFrameTimestampMs = currentFrameTimestampMs,
            maxSourceAgeMs = holdSourceAgeLimitMs,
            maxFrameDeltaMs = holdFrameDeltaLimitMs,
        )
        return OverlayDetectionSelection(
            detections = heldOverlayDetections,
            mappingSnapshot = overlaySnapshot,
            debugState = buildOverlayDebugState(
                selection = if (heldOverlayDetections.isEmpty()) "empty" else "hold_last_non_empty",
                detections = heldOverlayDetections,
                mappingSnapshot = overlaySnapshot,
                nowMs = nowMs,
                currentFrameTimestampMs = currentFrameTimestampMs,
                staleReason = latestOverlayStaleReason ?: "latest_empty",
                holdApplied = heldOverlayDetections.isNotEmpty(),
            ),
        )
    }

    private fun buildOverlayDebugState(
        selection: String,
        detections: List<DetectionCandidate>,
        mappingSnapshot: DetectionSnapshot,
        nowMs: Long,
        currentFrameTimestampMs: Long,
        staleReason: String?,
        holdApplied: Boolean,
    ): OverlayDebugState {
        return OverlayDebugState(
            selection = selection,
            detectionCount = detections.size,
            tactileDetectionCount = detections.count { it.isTactileDetection() },
            sourceAgeMs = mappingSnapshot.sourceAgeMs(nowMs),
            frameDeltaMs = mappingSnapshot.frameDeltaMs(currentFrameTimestampMs),
            staleReason = staleReason,
            holdApplied = holdApplied && detections.isNotEmpty(),
            snapshotPartial = mappingSnapshot.partial,
        )
    }

    private fun mergeOverlayDetections(
        currentDetections: List<DetectionCandidate>,
        heldTactileDetections: List<DetectionCandidate>,
    ): List<DetectionCandidate> {
        if (heldTactileDetections.isEmpty()) return currentDetections
        if (currentDetections.any { it.isTactileDetection() }) return currentDetections
        return currentDetections + heldTactileDetections
    }

    private fun ArgbImage.toScaledJpeg(maxDimension: Int, quality: Int): ByteArray? {
        if (width <= 0 || height <= 0 || pixels.isEmpty()) return null
        val bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        val maxSide = maxOf(width, height).coerceAtLeast(1)
        val scale = (maxDimension / maxSide.toFloat()).coerceAtMost(1f)
        val outputBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (width * scale).toInt().coerceAtLeast(1),
                (height * scale).toInt().coerceAtLeast(1),
                true,
            )
        } else {
            bitmap
        }
        return try {
            ByteArrayOutputStream().use { output ->
                outputBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(1, 100), output)
                output.toByteArray()
            }
        } finally {
            if (outputBitmap !== bitmap) outputBitmap.recycle()
            bitmap.recycle()
        }
    }

    private fun buildFrameCaptureMetadataJson(
        frameTimestampMs: Long,
        imageWidth: Int,
        imageHeight: Int,
        detectDurationMs: Long,
        result: AndroidDetectionResult,
        forcedCustom: Boolean,
    ): String {
        val detections = result.detections
        val top = detections.maxByOrNull { it.detectionConfidence }
        val json = JSONObject()
            .put("frame_timestamp_ms", frameTimestampMs)
            .put("camera_image_width", imageWidth)
            .put("camera_image_height", imageHeight)
            .put("detect_duration_ms", detectDurationMs)
            .put("detection_count", detections.size)
            .put("capture_policy", "manual_jpeg_no_gps_no_depth_raw_full_detector")
            .put("force_custom_tactile", forcedCustom)
            .put("detector_completed_models", JSONArray(result.timing.completedModels))
            .put("detector_skipped_models", JSONArray(result.timing.skippedModels))
            .put("detector_partial", result.partial)
            .put("detector_loaded_model_key", detectorLoadedModelKey)
            .put("detector_model_fallback_used", detectorModelFallbackUsed)
            .put("detector_model_load_reason", detectorLoadReason)
        result.timing.customInferenceMs?.let { json.put("detector_custom_inference_ms", it) }
        result.timing.cocoInferenceMs?.let { json.put("detector_coco_inference_ms", it) }
        result.timing.modelKey?.let { json.put("detector_model_key", it) }
        result.timing.modelPreprocessMs?.let { json.put("detector_model_preprocess_ms", it) }
        result.timing.modelInferenceMs?.let { json.put("detector_model_inference_ms", it) }
        result.timing.modelParseMs?.let { json.put("detector_model_parse_ms", it) }
        json.put(
            "detections",
            JSONArray(
                detections
                    .sortedByDescending { it.detectionConfidence }
                    .take(MAX_FRAME_CAPTURE_DETECTIONS)
                    .map { detection ->
                        JSONObject()
                            .put("class_name", detection.className)
                            .put("confidence", detection.detectionConfidence)
                            .put("bbox", detection.bboxNorm.toJson())
                    },
            ),
        )
        if (top != null) {
            json.put("top_detection_class_name", top.className)
                .put("top_detection_confidence", top.detectionConfidence)
                .put("top_detection_bbox", top.bboxNorm.toJson())
            resolveReportThreshold(detectorModelKeyForReports, top.className)?.let { threshold ->
                json.put("top_detection_threshold_used", threshold)
            }
        }
        return json.toString()
    }

    private fun RectNorm.toJson(): JSONObject {
        return JSONObject()
            .put("x", x)
            .put("y", y)
            .put("width", width)
            .put("height", height)
    }

    private fun buildDebugOverlayBoxes(
        frame: Frame,
        detections: List<DetectionCandidate>,
        bestOutput: TrackedObjectDepth?,
        detectionSnapshot: DetectionSnapshot,
    ): List<DebugBboxOverlayView.DebugOverlayBox> {
        val imageWidth = detectionSnapshot.imageWidth
        val imageHeight = detectionSnapshot.imageHeight
        if (imageWidth == null || imageHeight == null) {
            return emptyList()
        }
        val mappedDetectionBoxes = detections
            .sortedByDescending { it.detectionConfidence }
            .take(MAX_OVERLAY_DETECTION_BOXES)
            .mapNotNull { detection ->
                val rect = detection.bboxNorm.toViewRect(frame, imageWidth, imageHeight) ?: return@mapNotNull null
                DebugBboxOverlayView.DebugOverlayBox(
                    rectPx = rect,
                    label = "${detection.className} ${percent(detection.detectionConfidence)}",
                    best = false,
                    className = detection.className,
                )
            }
        val bestBox = bestOutput?.let { output ->
            output.bboxNorm.toViewRect(frame, imageWidth, imageHeight)?.let { rect ->
                DebugBboxOverlayView.DebugOverlayBox(
                    rectPx = rect,
                    label = buildString {
                        append(output.className)
                        append(" ")
                        append(percent(output.detectionConfidence))
                        append(" · ")
                        append(output.source.name)
                        output.riskDistanceM?.let { distance -> append(" · ${meters(distance)}") }
                        append(" · samples ")
                        append(output.validSampleCount)
                    },
                    best = true,
                    className = output.className,
                )
            }
        }
        val boxes = if (bestBox == null) mappedDetectionBoxes else listOf(bestBox) + mappedDetectionBoxes
        return boxes
    }

    /** Freezes the current ARCore image-to-depth transform; the Frame is never retained. */
    private fun createFrozenDepthMapper(
        frame: Frame,
        frameId: Long,
        imageWidth: Int,
        imageHeight: Int,
        snapshot: DepthFrameSnapshot,
    ): FrozenImageToTextureCoordinateMapper? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val depthWidth = snapshot.rawDepth?.width ?: snapshot.fullDepth?.width
        val depthHeight = snapshot.rawDepth?.height ?: snapshot.fullDepth?.height
        if (depthWidth == null || depthHeight == null || depthWidth <= 0 || depthHeight <= 0) return null
        val input = floatArrayOf(
            0f, 0f,
            imageWidth.toFloat(), 0f,
            0f, imageHeight.toFloat(),
            imageWidth.toFloat(), imageHeight.toFloat(),
            imageWidth / 2f, imageHeight / 2f,
        )
        val output = FloatArray(input.size)
        return try {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                input,
                Coordinates2d.TEXTURE_NORMALIZED,
                output,
            )
            val points = output.toList().chunked(2).map { values -> Point2(values[0], values[1]) }
            val transform = FrozenImageToDepthTransform.create(
                frameId = frameId,
                mappedCorners = points.take(4),
                mappedCenter = points[4],
            ) ?: return null
            FrozenImageToTextureCoordinateMapper(
                frameId = frameId,
                transform = transform,
                depthSize = ImageSize(depthWidth, depthHeight),
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun RectNorm.toViewRect(frame: Frame, imageWidth: Int, imageHeight: Int): RectF? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val left = x.coerceIn(0f, 1f) * imageWidth
        val top = y.coerceIn(0f, 1f) * imageHeight
        val right = (x + width).coerceIn(0f, 1f) * imageWidth
        val bottom = (y + height).coerceIn(0f, 1f) * imageHeight
        val imageCorners = floatArrayOf(
            left, top,
            right, top,
            right, bottom,
            left, bottom,
        )
        val viewCorners = FloatArray(imageCorners.size)
        return try {
            frame.transformCoordinates2d(
                Coordinates2d.IMAGE_PIXELS,
                imageCorners,
                Coordinates2d.VIEW,
                viewCorners,
            )
            if (viewCorners.any { !it.isFinite() }) return null
            val xs = floatArrayOf(viewCorners[0], viewCorners[2], viewCorners[4], viewCorners[6])
            val ys = floatArrayOf(viewCorners[1], viewCorners[3], viewCorners[5], viewCorners[7])
            val maxWidth = surfaceWidth.takeIf { it > 0 }?.toFloat() ?: return null
            val maxHeight = surfaceHeight.takeIf { it > 0 }?.toFloat() ?: return null
            val mappedLeft = xs.minOrNull()?.coerceIn(0f, maxWidth) ?: return null
            val mappedTop = ys.minOrNull()?.coerceIn(0f, maxHeight) ?: return null
            val mappedRight = xs.maxOrNull()?.coerceIn(mappedLeft, maxWidth) ?: return null
            val mappedBottom = ys.maxOrNull()?.coerceIn(mappedTop, maxHeight) ?: return null
            RectF(mappedLeft, mappedTop, mappedRight, mappedBottom)
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun setActionButton(text: String, mode: ActionMode, enabled: Boolean) {
        actionMode = mode
        actionRequestedEnabled = enabled
        if (::actionButton.isInitialized) {
            actionButton.text = text
            applyActionButtonState()
        }
    }

    private fun applyActionButtonState() {
        if (!::actionButton.isInitialized) return
        actionButton.isEnabled = actionRequestedEnabled &&
            (
                actionMode != ActionMode.START ||
                    (
                        firstRunOnboardingComplete() &&
                            isStartupCapabilityConfirmed()
                    )
            )
    }

    private fun openAppSettings() {
        val missing = requiredStartWalkObservedPermissions()
            .filterNot(currentObservedPermissionSnapshot()::isGranted)
            .toSet()
        if (!permissionRecoveryGate.blocksAutomaticResourceStart) {
            enterPermissionRecoveryBarrier(missing, "settings_requested")
        }
        permissionRecoveryGate = permissionRecoveryGate.settingsPending()
        persistPermissionRecoveryGate()
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            ),
        )
    }

    /** Loads unified first and records an explicit legacy fallback reason for report provenance. */
    private fun loadDetectorAfterCameraGate() {
        if (!firstRunOnboardingComplete()) return
        if (detectorLoadAttempted) return
        detectorLoadAttempted = true
        val runtimeConfig = reportRuntimeConfig ?: loadReportRuntimeConfig().also { reportRuntimeConfig = it }
        val detectorLoad = runtimeConfig
            ?.let { TfliteAndroidFrameDetector.createWithStatus(this, it) }
            ?: TfliteAndroidFrameDetector.createWithStatus(this)
        detectorConfigLoaded = detectorLoad.configLoaded
        detectorAvailable = detectorLoad.detectorAvailable
        detectorLoadedModelKey = detectorLoad.modelKey
        detectorModelFallbackUsed = detectorLoad.fallbackUsed
        detectorLoadReason = detectorLoad.reason
        detectorModelKeyForReports = detectorLoad.modelKey?.toReportModelKey()
        detectorStatusText = "detector=${detectorLoad.reason} model=${detectorLoad.modelKey ?: "-"} fallback=${detectorLoad.fallbackUsed}"
        detectorLoad.detector?.let { detector -> frameDetector = detector }
    }

    private fun handleDetectorRuntimeFailure(
        error: RuntimeException,
        expectedDetectorGeneration: Int,
        expectedWalkEpoch: WalkRuntimeEpoch,
    ) {
        if (!isCurrentFrameGeneration(expectedDetectorGeneration, expectedWalkEpoch)) return
        val runtimeConfig = reportRuntimeConfig
        val legacyAvailable = runtimeConfig?.fallbackModelKey == TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY &&
            runtimeConfig.customTactile != null &&
            runtimeConfig.cocoGeneral != null
        when (detectorRuntimeSupervisor.onFailure(detectorLoadedModelKey, legacyAvailable)) {
            DetectorRuntimeFailureAction.KEEP_CURRENT -> {
                if (isCurrentFrameGeneration(expectedDetectorGeneration, expectedWalkEpoch)) {
                    detectorStatusText =
                        "detector=runtime_failure_${detectorRuntimeSupervisor.consecutiveFailures} error=${error::class.java.simpleName}"
                } else {
                    detectorRuntimeSupervisor.onSuccess()
                }
            }
            DetectorRuntimeFailureAction.LOAD_LEGACY -> {
                val legacyConfig = runtimeConfig?.let { config ->
                    config.copy(
                        primaryModelKey = TwoModelRuntimeConfig.LEGACY_TWO_MODEL_KEY,
                        unifiedWalksafe = config.unifiedWalksafe?.copy(enabled = false),
                    )
                }
                val load = legacyConfig?.let { TfliteAndroidFrameDetector.createWithStatus(this, it) }
                val fallback = load?.detector
                if (load?.detectorAvailable == true && fallback != null) {
                    val installed = synchronized(frameStateLock) {
                        if (
                            !isCurrentFrameGeneration(
                                expectedDetectorGeneration,
                                expectedWalkEpoch,
                            )
                        ) {
                            false
                        } else {
                            (frameDetector as? Closeable)?.close()
                            frameDetector = fallback
                            detectorGeneration += 1
                            detectorAvailable = true
                            detectorLoadedModelKey = load.modelKey
                            detectorModelFallbackUsed = true
                            detectorLoadReason = "unified_runtime_failed_legacy_loaded"
                            detectorModelKeyForReports = load.modelKey?.toReportModelKey()
                            detectorStatusText =
                                "detector=unified_runtime_failed_legacy_loaded model=${load.modelKey}"
                            detectorRuntimeSupervisor.onSuccess()
                            true
                        }
                    }
                    if (!installed) {
                        (fallback as? Closeable)?.close()
                        detectorRuntimeSupervisor.onSuccess()
                        return
                    }
                    clearDetectionStateAfterRuntimeTransition(expectedWalkEpoch)
                } else {
                    disableDetectorAfterRuntimeFailure(
                        error,
                        expectedDetectorGeneration,
                        expectedWalkEpoch,
                    )
                }
            }
            DetectorRuntimeFailureAction.DISABLE -> {
                disableDetectorAfterRuntimeFailure(
                    error,
                    expectedDetectorGeneration,
                    expectedWalkEpoch,
                )
            }
        }
    }

    private fun disableDetectorAfterRuntimeFailure(
        error: RuntimeException,
        expectedDetectorGeneration: Int,
        expectedWalkEpoch: WalkRuntimeEpoch,
    ) {
        val disabledGeneration = synchronized(frameStateLock) {
            if (
                !isCurrentFrameGeneration(
                    expectedDetectorGeneration,
                    expectedWalkEpoch,
                )
            ) {
                null
            } else {
                (frameDetector as? Closeable)?.close()
                frameDetector = NoopAndroidFrameDetector()
                detectorGeneration += 1
                detectorAvailable = false
                detectorLoadReason = "runtime_failed_unavailable"
                detectorStatusText =
                    "detector=runtime_failed_unavailable error=${error::class.java.simpleName}"
                detectorGeneration
            }
        }
        if (disabledGeneration == null) {
            detectorRuntimeSupervisor.onSuccess()
            return
        }
        clearDetectionStateAfterRuntimeTransition(expectedWalkEpoch)
        runOnUiThread {
            if (
                !isCurrentFrameGeneration(
                    disabledGeneration,
                    expectedWalkEpoch,
                )
            ) {
                return@runOnUiThread
            }
            enterWalkSessionSafetyStopAndCancelOutputs("detector_runtime_failed")
            stopCameraFallbackSession(updateUi = false)
            stopDepthSession(closeSession = true)
            latestReportCandidateStatus = "reportCandidate=blocked:camera_non_metric_unavailable"
            setActionButton("카메라 보조 경고 사용 불가", ActionMode.START, enabled = false)
            updateStatus(
                status = "객체 탐지 중지 · 안전 중지",
                detail = "detector 실행 오류로 보행 기능을 안전 중지했습니다.",
            )
        }
    }

    private fun clearDetectionStateAfterRuntimeTransition(
        expectedWalkEpoch: WalkRuntimeEpoch,
    ) {
        val expectedDetectorGeneration = synchronized(frameStateLock) {
            if (!walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch)) return
            latestDetectionSnapshot = DetectionSnapshot.empty()
            latestTactileOverlaySnapshot = DetectionSnapshot.empty()
            lastNonEmptyOverlaySnapshot = DetectionSnapshot.empty()
            detectorGeneration
        }
        runOnUiThread {
            if (
                isCurrentFrameGeneration(
                    expectedDetectorGeneration,
                    expectedWalkEpoch,
                )
            ) {
                debugBboxOverlay.clear()
            }
        }
    }

    private fun emitTactileFrameFeedback(dispatch: TactileFrameFeedbackDispatch) {
        latestFeedbackDeliveryState = FeedbackDeliveryState(
            activeFeedbackDeliveryKeys = dispatch.activeFeedbackDeliveryKeys,
            deviceGateAllowsAlerts = dispatch.deviceGateAllowsAlerts,
            observedAtMs = dispatch.policyEvaluatedAtMs,
        )
        val feedbackGeneration = feedbackLifecycleGeneration
        val activeRiskTrackIds = dispatch.activeRiskTrackIds
        val feedbackAction = dispatch.action
        val elapsedRealtimeMs = dispatch.policyEvaluatedAtMs
        runOnUiThread {
            if (!isFeedbackLifecycleCurrent(feedbackGeneration)) {
                feedbackAction?.let { action ->
                    feedbackPolicy.rejectUndeliveredFeedback(action.trackId, elapsedRealtimeMs)
                }
                return@runOnUiThread
            }
            reconcileTalkBackRiskTracks(activeRiskTrackIds)
            feedbackAction?.let { emitFeedbackAction(it, elapsedRealtimeMs) }
        }
    }

    /** Avoids duplicate app TTS while TalkBack owns speech, but preserves the risk haptic. */
    private fun emitFeedbackAction(action: FeedbackAction, policyEvaluatedAtMs: Long) {
        val generation = feedbackLifecycleGeneration
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread {
                if (!isFeedbackLifecycleCurrent(generation)) {
                    feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
                    return@runOnUiThread
                }
                emitFeedbackAction(action, policyEvaluatedAtMs)
            }
            return
        }
        if (!isFeedbackLifecycleCurrent(generation)) {
            feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
            return
        }
        if (!isFeedbackActionStillDeliverable(action)) {
            feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
            return
        }
        cancelPendingFeedbackTerminalResolution()
        if (!feedbackPolicy.claimFeedbackDelivery(action.trackId, policyEvaluatedAtMs)) return
        if (!isFeedbackActionStillDeliverable(action)) {
            feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
            return
        }
        val isRisk = action.level != kr.co.hanium.dreamup.walksafe.depth.MessageLevel.INFO
        if (isRisk && voiceRecognitionActive) cancelVoiceCommandRecognition()
        if (shouldSuppressFeedbackDuringVoiceRecognition(voiceRecognitionActive, isRisk)) {
            feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
            return
        }
        val actuator = ensureFeedbackActuator()
        val screenReaderActive = isScreenReaderActive()
        if (screenReaderActive) {
            if (isRisk) actuator.prepareForExternalRiskAnnouncement()
            val talkBackAccepted = announceForTalkBack(
                message = action.message,
                priority = if (isRisk) TalkBackAnnouncementPriority.RISK else TalkBackAnnouncementPriority.NAVIGATION,
                riskRank = action.level.ordinal,
                riskTrackId = action.trackId,
            )
            val vibrationAccepted = if (isRisk) actuator.vibrateRiskOnly(action) else false
            if (talkBackAccepted || vibrationAccepted) {
                scheduleFeedbackTerminalResolution(
                    action = action,
                    policyEvaluatedAtMs = policyEvaluatedAtMs,
                    generation = generation,
                    delayMs = maxOf(
                        if (talkBackAccepted) utteranceTerminalTimeoutMs(action.message.length) else 0L,
                        if (vibrationAccepted) vibrationDurationMs(action) else 0L,
                    ),
                )
            } else {
                feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
            }
        } else if (isRisk) {
            val speechGeneration = generation
            val feedbackStartedAtMs = SystemClock.elapsedRealtime()
            var vibrationAccepted = false
            val dispatch = actuator.emit(
                action = action,
                onSpeechCompleted = {
                    runOnUiThread {
                        if (isFeedbackLifecycleCurrent(speechGeneration)) {
                            confirmFeedbackDelivery(action, policyEvaluatedAtMs)
                        } else {
                            feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
                        }
                    }
                },
                onSpeechFailed = {
                    runOnUiThread {
                        if (!isFeedbackLifecycleCurrent(speechGeneration)) {
                            feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
                        } else if (vibrationAccepted) {
                            scheduleFeedbackTerminalResolution(
                                action = action,
                                policyEvaluatedAtMs = policyEvaluatedAtMs,
                                generation = speechGeneration,
                                delayMs = (
                                    feedbackStartedAtMs + vibrationDurationMs(action) -
                                        SystemClock.elapsedRealtime()
                                    ).coerceAtLeast(1L),
                            )
                        } else {
                            feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
                        }
                    }
                },
            )
            vibrationAccepted = dispatch.vibrationAccepted
            if (dispatch.speech != NavigationSpeechDispatchResult.ACCEPTED) {
                if (vibrationAccepted) {
                    scheduleFeedbackTerminalResolution(
                        action = action,
                        policyEvaluatedAtMs = policyEvaluatedAtMs,
                        generation = generation,
                        delayMs = vibrationDurationMs(action),
                    )
                } else {
                    feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
                }
            }
        } else {
            val speechGeneration = generation
            val speech = actuator.speakNavigation(
                message = action.message,
                onCompleted = {
                    runOnUiThread {
                        if (isFeedbackLifecycleCurrent(speechGeneration)) {
                            confirmFeedbackDelivery(action, policyEvaluatedAtMs)
                        } else {
                            feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
                        }
                    }
                },
                onFailed = {
                    feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
                },
            )
            if (speech != NavigationSpeechDispatchResult.ACCEPTED) {
                feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
            }
        }
    }

    private fun isFeedbackActionStillDeliverable(action: FeedbackAction): Boolean {
        val current = latestFeedbackDeliveryState
        val nowMs = SystemClock.elapsedRealtime()
        return nowMs <= action.validUntilMs &&
            action.deliveryKey in current.activeFeedbackDeliveryKeys &&
            current.deviceGateAllowsAlerts &&
            current.observedAtMs <= nowMs &&
            currentFeedbackDeviceGateAllowsAlerts()
    }

    private fun currentFeedbackDeviceGateAllowsAlerts(): Boolean {
        if (!firstRunOnboardingComplete()) return false
        val capabilityGate = when (startupCapabilityDecision?.tier) {
            WalkSafeStartupCapabilityTier.FULL ->
                arSessionPurpose == ArSessionPurpose.RUNTIME &&
                    runtimeMetricOutputAllowed &&
                    currentRuntimeMetricOutputAllowsWork()
            WalkSafeStartupCapabilityTier.LIMITED ->
                metricDistanceCapabilityOverride == false &&
                    cameraFallbackRequested &&
                    cameraFallbackRunning &&
                    isStartupCapabilityConfirmed()
            WalkSafeStartupCapabilityTier.BLOCKED,
            null,
            -> false
        }
        return isActivityForeground &&
            isWalkSessionRuntimeActive() &&
            officialEnvironmentOutputsAllowed &&
            phoneMountingOutputsAllowed &&
            capabilityGate &&
            hasCameraPermission() &&
            detectorConfigLoaded &&
            detectorAvailable &&
            latestFeedbackDeliveryState.deviceGateAllowsAlerts
    }

    private fun vibrationDurationMs(action: FeedbackAction): Long =
        action.vibrationPatternMs?.sum()?.coerceAtLeast(1L) ?: 1L

    private fun scheduleFeedbackTerminalResolution(
        action: FeedbackAction,
        policyEvaluatedAtMs: Long,
        generation: Int,
        delayMs: Long,
    ) {
        cancelPendingFeedbackTerminalResolution()
        if (!::statusText.isInitialized) {
            feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
            return
        }
        lateinit var resolution: Runnable
        resolution = Runnable {
            if (pendingFeedbackTerminalResolution?.runnable !== resolution) return@Runnable
            pendingFeedbackTerminalResolution = null
            if (isFeedbackLifecycleCurrent(generation)) {
                confirmFeedbackDelivery(action, policyEvaluatedAtMs)
            } else {
                feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
            }
        }
        pendingFeedbackTerminalResolution = PendingFeedbackTerminalResolution(
            action = action,
            policyEvaluatedAtMs = policyEvaluatedAtMs,
            runnable = resolution,
        )
        statusText.postDelayed(resolution, delayMs.coerceAtLeast(1L))
    }

    private fun cancelPendingFeedbackTerminalResolution() {
        val pending = pendingFeedbackTerminalResolution ?: return
        pendingFeedbackTerminalResolution = null
        if (::statusText.isInitialized) statusText.removeCallbacks(pending.runnable)
        feedbackPolicy.rejectUndeliveredFeedback(
            pending.action.trackId,
            pending.policyEvaluatedAtMs,
        )
    }

    private fun confirmFeedbackDelivery(action: FeedbackAction, policyEvaluatedAtMs: Long) {
        if (!isFeedbackActionStillDeliverable(action)) {
            feedbackPolicy.rejectUndeliveredFeedback(action.trackId, policyEvaluatedAtMs)
            return
        }
        val deliveredAtMs = SystemClock.elapsedRealtime()
        val confirmed = synchronized(phoneMountingObservationLock) {
            if (!phoneMountingOutputsAllowed) {
                feedbackPolicy.rejectUndeliveredFeedback(
                    action.trackId,
                    policyEvaluatedAtMs,
                )
                false
            } else {
                feedbackPolicy.confirmFeedbackDelivery(
                    action.trackId,
                    policyEvaluatedAtMs,
                    deliveredAtMs,
                )
            }
        }
        if (!confirmed) return
        fieldSessionLog.recordEvent(
            if (action.level == kr.co.hanium.dreamup.walksafe.depth.MessageLevel.INFO) {
                "path_guidance_emitted"
            } else {
                "risk_feedback_emitted"
            },
            mapOf("level" to action.level.name, "reason" to action.reason),
        )
    }

    private fun speakNavigation(message: String, onCompleted: (() -> Unit)? = null): Boolean {
        val generation = feedbackLifecycleGeneration
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread {
                if (!isFeedbackLifecycleCurrent(generation)) return@runOnUiThread
                speakNavigation(message, onCompleted)
            }
            return true
        }
        if (!isFeedbackLifecycleCurrent(generation)) return false
        if (!isWalkSessionRuntimeActive() || !walkSafetyOutputsAllowed()) return false
        if (shouldSuppressFeedbackDuringVoiceRecognition(voiceRecognitionActive, isRisk = false)) return false
        val mainThreadCompletion = onCompleted?.let { completion ->
            {
                runOnUiThread {
                    if (!isWalkSessionRuntimeActive() || !walkSafetyOutputsAllowed()) {
                        return@runOnUiThread
                    }
                    if (isFeedbackLifecycleCurrent(generation)) completion()
                }
            }
        }
        return dispatchNavigationSpeech(
            message = message,
            onCompleted = mainThreadCompletion,
            speakWithTts = { ttsMessage, onTtsCompleted, onTtsFailed ->
                ensureFeedbackActuator().speakNavigation(ttsMessage, onTtsCompleted, onTtsFailed)
            },
            fallBackToTalkBack = { fallbackMessage, onDelivered ->
                dispatchNavigationTalkBackFallback(fallbackMessage, onDelivered, generation)
            },
        )
    }

    private fun dispatchNavigationTalkBackFallback(
        message: String,
        onDelivered: (() -> Unit)?,
        generation: Int,
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread {
                if (!isFeedbackLifecycleCurrent(generation)) return@runOnUiThread
                dispatchNavigationTalkBackFallback(message, onDelivered, generation)
            }
            return true
        }
        if (!isFeedbackLifecycleCurrent(generation)) return false
        if (!isWalkSessionRuntimeActive() || !walkSafetyOutputsAllowed()) return false
        if (shouldSuppressFeedbackDuringVoiceRecognition(voiceRecognitionActive, isRisk = false)) return false
        if (!isScreenReaderActive()) return false
        return announceForTalkBack(
            message = message,
            priority = TalkBackAnnouncementPriority.NAVIGATION,
            onDelivered = onDelivered,
        )
    }

    private fun speakInteraction(message: String): Boolean {
        val generation = feedbackLifecycleGeneration
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread {
                if (!isFeedbackLifecycleCurrent(generation)) return@runOnUiThread
                speakInteraction(message)
            }
            return true
        }
        if (!isFeedbackLifecycleCurrent(generation)) return false
        if (shouldSuppressFeedbackDuringVoiceRecognition(voiceRecognitionActive, isRisk = false)) return false
        val screenReaderActive = isScreenReaderActive()
        val announced = announceForTalkBack(message = message, priority = TalkBackAnnouncementPriority.INTERACTION)
        if (!screenReaderActive) {
            return ensureFeedbackActuator().speakInteraction(message)
        }
        return announced
    }

    private fun speakAdvisory(
        action: NonMetricObstacleAdvisoryAction,
        expectedWalkEpoch: WalkRuntimeEpoch,
        fallbackGeneration: Int,
        lifecycleGeneration: Int,
        onDelivered: () -> Unit,
        onFailed: () -> Unit,
    ): Boolean {
        val generation = feedbackLifecycleGeneration
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread {
                if (!isFeedbackLifecycleCurrent(generation)) {
                    onFailed()
                    return@runOnUiThread
                }
                speakAdvisory(
                    action,
                    expectedWalkEpoch,
                    fallbackGeneration,
                    lifecycleGeneration,
                    onDelivered,
                    onFailed,
                )
            }
            return true
        }
        if (!isFeedbackLifecycleCurrent(generation)) {
            onFailed()
            return false
        }
        if (
            !isCameraFallbackAdvisoryStillDeliverable(
                action,
                expectedWalkEpoch,
                fallbackGeneration,
                lifecycleGeneration,
            ) ||
            !nonMetricAdvisoryPolicy.isDeliveryCurrent(action)
        ) {
            onFailed()
            return false
        }
        if (shouldSuppressFeedbackDuringVoiceRecognition(voiceRecognitionActive, isRisk = false)) {
            onFailed()
            return false
        }
        val accepted = if (isScreenReaderActive()) {
            val actuator = ensureFeedbackActuator()
            if (!actuator.isAppSpeechIdleForExternalAdvisory()) {
                false
            } else {
                announceForTalkBack(
                    message = action.message,
                    priority = TalkBackAnnouncementPriority.ADVISORY,
                    onDelivered = onDelivered,
                )
            }
        } else {
            ensureFeedbackActuator().speakAdvisory(
                message = action.message,
                validUntilMs = action.validUntilMs,
                isStillValid = {
                    isCameraFallbackAdvisoryStillDeliverable(
                        action,
                        expectedWalkEpoch,
                        fallbackGeneration,
                        lifecycleGeneration,
                    ) &&
                        nonMetricAdvisoryPolicy.isDeliveryCurrent(action)
                },
                onCompleted = onDelivered,
                onFailed = onFailed,
            )
        }
        if (!accepted) onFailed()
        return accepted
    }

    private fun isScreenReaderActive(): Boolean {
        val accessibilityManager = getSystemService(AccessibilityManager::class.java) ?: return false
        return accessibilityManager.isEnabled && accessibilityManager.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_SPOKEN,
        ).isNotEmpty()
    }

    private fun isFeedbackLifecycleCurrent(generation: Int): Boolean =
        isActivityForeground && feedbackLifecycleGeneration == generation

    private fun announceForTalkBack(
        message: String,
        priority: TalkBackAnnouncementPriority,
        onDelivered: (() -> Unit)? = null,
        riskRank: Int? = null,
        riskTrackId: String? = null,
    ): Boolean {
        if (message.isBlank()) return false
        if (
            shouldSuppressFeedbackDuringVoiceRecognition(
                voiceRecognitionActive,
                isRisk = priority == TalkBackAnnouncementPriority.RISK,
            )
        ) return false
        if (!::statusText.isInitialized) return false
        val nowMs = SystemClock.elapsedRealtime()
        when (priority) {
            TalkBackAnnouncementPriority.RISK -> {
                val rank = riskRank ?: 0
                val activeRiskHeld = nowMs < riskAnnouncementHoldUntilMs
                val preemptsActiveRisk = activeRiskHeld && rank > lastRiskAnnouncementRank
                if (activeRiskHeld && !preemptsActiveRisk) return false
                if (
                    !preemptsActiveRisk &&
                    message == lastRiskAnnouncement &&
                    nowMs - lastRiskAnnouncementMs < TALKBACK_RISK_DUP_WINDOW_MS
                ) return false
                lastRiskAnnouncement = message
                lastRiskAnnouncementTrackId = riskTrackId.orEmpty()
                lastRiskAnnouncementMs = nowMs
                lastRiskAnnouncementRank = rank
                riskAnnouncementHoldUntilMs = nowMs + utteranceTerminalTimeoutMs(message.length)
            }
            TalkBackAnnouncementPriority.INTERACTION -> {
                if (nowMs < riskAnnouncementHoldUntilMs) {
                    return scheduleTalkBackInteraction(
                        message,
                        riskAnnouncementHoldUntilMs - nowMs,
                        onDelivered,
                    )
                }
                if (
                    message == lastInteractionAnnouncement &&
                    nowMs - lastInteractionAnnouncementMs < TALKBACK_INTERACTION_DUP_WINDOW_MS
                ) return false
                lastInteractionAnnouncement = message
                lastInteractionAnnouncementMs = nowMs
            }
            TalkBackAnnouncementPriority.ADVISORY -> {
                if (nowMs < riskAnnouncementHoldUntilMs || nowMs < navigationAnnouncementHoldUntilMs) return false
                if (nowMs - lastInteractionAnnouncementMs < TALKBACK_INTERACTION_PRIORITY_WINDOW_MS) return false
                if (
                    message == lastAdvisoryAnnouncement &&
                    nowMs - lastAdvisoryAnnouncementMs < TALKBACK_ADVISORY_DUP_WINDOW_MS
                ) return false
                lastAdvisoryAnnouncement = message
                lastAdvisoryAnnouncementMs = nowMs
            }
            TalkBackAnnouncementPriority.NAVIGATION -> {
                if (nowMs < riskAnnouncementHoldUntilMs) return false
                if (nowMs - lastInteractionAnnouncementMs < TALKBACK_INTERACTION_PRIORITY_WINDOW_MS) return false
                if (
                    message == lastNavigationAnnouncement &&
                    nowMs - lastNavigationAnnouncementMs < TALKBACK_NAVIGATION_DUP_WINDOW_MS
                ) return false
                lastNavigationAnnouncement = message
                lastNavigationAnnouncementMs = nowMs
                navigationAnnouncementHoldUntilMs = nowMs + utteranceTerminalTimeoutMs(message.length)
            }
        }
        runOnUiThread {
            statusText.announceForAccessibility(message)
            onDelivered?.invoke()
        }
        return true
    }

    private fun scheduleTalkBackInteraction(
        message: String,
        delayMs: Long,
        onDelivered: (() -> Unit)? = null,
    ): Boolean {
        if (!::statusText.isInitialized) return false
        pendingTalkBackInteraction?.let(statusText::removeCallbacks)
        val delivery = Runnable {
            pendingTalkBackInteraction = null
            announceForTalkBack(
                message,
                TalkBackAnnouncementPriority.INTERACTION,
                onDelivered = onDelivered,
            )
        }
        pendingTalkBackInteraction = delivery
        statusText.postDelayed(delivery, delayMs.coerceAtLeast(1L))
        return true
    }

    private fun reconcileTalkBackRiskTracks(activeTrackIds: Set<String>) {
        if (lastRiskAnnouncementTrackId.isBlank() || lastRiskAnnouncementTrackId in activeTrackIds) return
        lastRiskAnnouncement = ""
        lastRiskAnnouncementMs = 0L
        lastRiskAnnouncementTrackId = ""
        lastRiskAnnouncementRank = -1
        riskAnnouncementHoldUntilMs = 0L
        if (activeTrackIds.isNotEmpty()) return
        val pendingInteraction = pendingTalkBackInteraction ?: return
        pendingTalkBackInteraction = null
        if (::statusText.isInitialized) statusText.removeCallbacks(pendingInteraction)
        pendingInteraction.run()
    }

    private fun ensureFeedbackActuator(): AndroidFeedbackActuator {
        val current = feedbackActuator
        if (current != null) return current
        return AndroidFeedbackActuator(this) {
            handleRuntimeSpeechCapabilityFailure(
                WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS,
                "오프라인 한국어 음성 안내를 시작할 수 없어 보행 기능을 중지했습니다.",
            )
        }.also { feedbackActuator = it }
    }

    private fun enterWalkSessionSafetyStopAndCancelOutputs(
        reason: String,
        persistInterruptionMarker: Boolean = true,
    ) {
        if (::walkSessionLifecycle.isInitialized) {
            transitionWalkSession(WalkSessionEvent.SafetyStopRequested)
            if (persistInterruptionMarker) persistWalkSessionInterruptionMarker()
        }
        invalidateOfficialEnvironmentEvidence("safety_stop:$reason")
        invalidatePhoneMountingEvidence("safety_stop:$reason")
        walkSessionResumePromptPending = false
        walkSessionResumeRetryRequiresUserAction = false
        walkSessionResumeConfirmationToken = null
        cancelWalkSessionOutputs(reason)
        stopCameraFallbackSession(updateUi = false)
        stopDepthSession(closeSession = true)
        if (::startupCapabilityProbe.isInitialized) refreshStartupCapabilityUi()
    }

    private fun startFreshWalk(reason: String) {
        transitionWalkSession(WalkSessionEvent.NewWalkRequested)
        persistWalkSessionInterruptionMarker()
        invalidateOfficialEnvironmentEvidence("new_walk:$reason")
        invalidatePhoneMountingEvidence("new_walk:$reason")
        walkSessionResumePromptPending = false
        walkSessionResumeRetryRequiresUserAction = false
        walkSessionResumeConfirmationToken = null
        cancelWalkSessionOutputs(reason)
        resetWalkTransientStateForNewWalk()
    }

    private fun resetWalkTransientStateForNewWalk() {
        latestStepCount = 0
        tmapFailureGuard.reset()
        if (::stepTracker.isInitialized) stepTracker.resetForNewWalk()
        clearTrustedLocation()
        pendingVoiceDestinationQuery = null
        feedbackPolicy.resetForNewWalk()
        nonMetricAdvisoryPolicy.reset()
        lastRiskAnnouncementMs = 0L
        lastRiskAnnouncement = ""
        lastRiskAnnouncementTrackId = ""
        lastRiskAnnouncementRank = -1
        riskAnnouncementHoldUntilMs = 0L
        lastNavigationAnnouncementMs = 0L
        lastNavigationAnnouncement = ""
        navigationAnnouncementHoldUntilMs = 0L
        lastAdvisoryAnnouncementMs = 0L
        lastAdvisoryAnnouncement = ""
        lastInteractionAnnouncementMs = 0L
        lastInteractionAnnouncement = ""
        lastProgressBeepAtMs = 0L
        latestNavigationState = "navigation=not_started"
    }

    private fun enterWalkSessionForegroundRecheckAndCancelOutputs(reason: String) {
        if (::walkSessionLifecycle.isInitialized) {
            transitionWalkSession(WalkSessionEvent.RecheckRequested)
            persistWalkSessionInterruptionMarker()
        }
        invalidateOfficialEnvironmentEvidence("foreground_recheck:$reason")
        invalidatePhoneMountingEvidence("foreground_recheck:$reason")
        walkSessionResumePromptPending = false
        walkSessionResumeRetryRequiresUserAction = false
        walkSessionResumeConfirmationToken = null
        cancelWalkSessionOutputs(reason)
        stopCameraFallbackSession(updateUi = false)
        stopDepthSession(closeSession = true)
    }

    private fun cancelWalkSessionOutputs(reason: String) {
        feedbackLifecycleGeneration += 1
        invalidateArCoreAvailabilityRecheck()
        confirmedStartupCapabilityDecision = null
        startupCapabilityConfirmationPending = false
        startupCapabilityRetryRequiresUserAction = false
        pendingCameraFallbackStart = null
        cancelVoiceCommandRecognition()
        synchronized(reportUploadSafetyLock) {
            reportUploadSafetyGeneration += 1L
            reportPrivacyConsentSession.cancelActiveCalls()
        }
        val navigationCancellation = cancelNavigationRequestsForPause()
        clearDestinationSearchState(navigationCancellation.destinationSearchCancelled)
        if (
            ::walkSessionLifecycle.isInitialized &&
            walkSessionLifecycle.snapshot().state == WalkSessionState.PAUSED
        ) {
            resetRouteState(purgeRouteSnapshot = false)
        } else {
            resetRouteState()
        }
        invalidateFrameStateForPause()
        stopLocationUpdates()
        stopStepTracking()
        if (::earthOrientationTracker.isInitialized) earthOrientationTracker.stop()
        feedbackPolicy.cancelPendingFeedbackDeliveries()
        if (::statusText.isInitialized) {
            pendingTalkBackInteraction?.let(statusText::removeCallbacks)
        }
        pendingTalkBackInteraction = null
        cancelPendingFeedbackTerminalResolution()
        latestFeedbackDeliveryState = FeedbackDeliveryState()
        feedbackActuator?.close()
        feedbackActuator = null
        if (::cameraFallbackLifecycleOwner.isInitialized) {
            cameraFallbackLifecycleOwner.moveTo(Lifecycle.State.CREATED)
        }
        if (::surfaceView.isInitialized) surfaceView.onPause()
        syncActiveSessionScreenPolicy()
        if (::fieldSessionLog.isInitialized) {
            fieldSessionLog.recordEvent(
                "walk_session_outputs_cancelled",
                mapOf("reason" to reason),
            )
        }
    }

    private fun handleRuntimeSpeechCapabilityFailure(
        requirement: WalkSafeStartupRequirement,
        detail: String,
    ) {
        when (requirement) {
            WalkSafeStartupRequirement.ON_DEVICE_STT -> {
                if (onDeviceSpeechRecognitionCapabilityOverride == false) return
                onDeviceSpeechRecognitionCapabilityOverride = false
            }
            WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS -> {
                if (offlineKoreanTextToSpeechCapabilityOverride == false) return
                offlineKoreanTextToSpeechCapabilityOverride = false
            }
            else -> return
        }
        enterWalkSessionSafetyStopAndCancelOutputs(
            reason = "runtime_speech_${requirement.name.lowercase(Locale.US)}",
        )
        confirmedStartupCapabilityDecision = null
        startupCapabilityConfirmationPending = false
        navigationPermissionsRequestedForReport = false
        reportLocationStartScheduled.set(false)
        stopCameraFallbackSession(updateUi = false)
        stopDepthSession(closeSession = true)
        stopLocationUpdates()
        stopStepTracking()
        if (::earthOrientationTracker.isInitialized) earthOrientationTracker.stop()
        feedbackPolicy.cancelPendingFeedbackDeliveries()
        refreshStartupCapabilityUi()
        updateStatus("필수 음성 기능 실패 · 안전 중지", detail)
        if (isScreenReaderActive()) startupCapabilityText.announceForAccessibility(detail)
    }

    private fun feedbackActuatorStatusText(): String {
        return feedbackActuator?.statusText() ?: "tts=idle"
    }

    private fun buildDeviceGateState(
        bestOutput: TrackedObjectDepth?,
        staleReason: String?,
        expectedRuntimeMetricGeneration: Long? = null,
    ): DeviceGateState {
        val runtimeMetricReady = currentRuntimeMetricOutputAllowsWork(expectedRuntimeMetricGeneration)
        return DeviceGateState(
            cameraPermissionGranted = hasCameraPermission(),
            arCoreSupported = arCoreSupported && runtimeMetricReady,
            depthSupported = depthSupported && runtimeMetricReady,
            tfliteConfigLoaded = detectorConfigLoaded,
            detectorAvailable = detectorAvailable,
            arSessionRunning = runtimeMetricReady,
            freshDepthObject = runtimeMetricReady &&
                bestOutput != null &&
                bestOutput.source.metric &&
                bestOutput.confidence.finalScore >= FEEDBACK_MIN_DEPTH_CONFIDENCE,
            staleReason = staleReason,
        )
    }

    private fun DeviceGateState.statusText(): String {
        val state = if (actuatorsAllowed) "deviceGate=READY" else "deviceGate=WAIT:${blockedReason()}"
        val alertState = if (alertsAllowed) "alertGate=PASS" else "alertGate=WAIT:${blockedReason()}"
        return "$state $alertState startupReady=$startupReady depth=$depthSupported detector=$detectorAvailable"
    }

    internal fun publishCurrentFrameReportState(
        frameGeneration: Int,
        expectedRuntimeMetricGeneration: Long,
        automaticReportOutput: TrackedObjectDepth?,
        reportGateState: DeviceGateState,
        explicitReportOutput: TrackedObjectDepth?,
        explicitReportGateState: DeviceGateState,
        reportImage: ByteArray?,
        nowMs: Long,
        capturedAtMs: Long,
        expectedWalkEpoch: WalkRuntimeEpoch? = null,
    ): Boolean = synchronized(frameStateLock) frameLock@ {
        if (!isCurrentFrameGeneration(frameGeneration, expectedWalkEpoch)) {
            return@frameLock false
        }
        synchronized(runtimeMetricStateLock) runtimeLock@ {
            if (!currentRuntimeMetricOutputAllowsWork(expectedRuntimeMetricGeneration)) {
                return@runtimeLock false
            }
            latestReportCandidateStatus = prepareReportCandidate(
                reportOutput = automaticReportOutput,
                gateState = reportGateState,
                reportImage = reportImage,
                nowMs = nowMs,
                capturedAtMs = capturedAtMs,
            )
            latestExplicitReportOutput = explicitReportOutput
            latestExplicitReportImage = reportImage
            latestExplicitReportGateState = explicitReportGateState
            latestExplicitReportCapturedAtMs = capturedAtMs
            true
        }
    }

    /**
     * Report creation is fail-closed: session consent, damage class, metric depth, device gate,
     * model provenance, trusted GPS, local reporter id and a captured JPEG must all remain available.
     */
    private fun prepareReportCandidate(
        reportOutput: TrackedObjectDepth?,
        gateState: DeviceGateState,
        reportImage: ByteArray?,
        nowMs: Long,
        capturedAtMs: Long,
        trigger: String = "auto",
        explicitRequest: Boolean = false,
    ): String {
        if (!reportPermissionsAllowWork()) {
            return "reportCandidate=blocked:permission_unavailable"
        }
        val expectedWalkEpoch = walkSessionLifecycle.currentRuntimeEpochOrNull()
        if (expectedWalkEpoch == null) {
            return "reportCandidate=blocked:walk_session_inactive"
        }
        if (
            !integratedConsentSession.isAllowed(
                IntegratedConsentItem.RAW_SOURCE_COLLECTION,
            )
        ) {
            return "reportCandidate=blocked:raw_collection_consent_required"
        }
        if (!reportPrivacyConsentSession.isGranted()) {
            return "reportCandidate=blocked:privacy_consent_required"
        }
        if (
            !explicitRequest &&
            (
                !permissionSessionPolicy.snapshot().mayCreateAutomaticReport ||
                    !integratedConsentSession.isAllowed(
                        IntegratedConsentItem.AUTOMATIC_REPORTING,
                    )
            )
        ) {
            return "reportCandidate=blocked:automatic_report_consent_required"
        }
        if (
            !explicitRequest &&
            run {
                GatewayCapacityProcessState.fenceSessionGeneration(
                    GatewaySessionProcessCoordinator.snapshot().generation,
                )
                !GatewayCapacityProcessState.admission(
                    java.time.Instant.ofEpochMilli(nowMs),
                ).automaticReportCandidateAllowed
            }
        ) {
            return "reportCandidate=blocked:gateway_capacity"
        }
        val reporterId = currentReporterUserId() ?: return "reportCandidate=blocked:login_required"
        val output = reportOutput ?: return "reportCandidate=blocked:no_depth_object"
        if (output.className != AndroidReportCandidatePolicy.DAMAGED_TACTILE_BLOCK) {
            return "reportCandidate=blocked:not_reportable_class"
        }
        val modelKey = detectorModelKeyForReports ?: return "reportCandidate=blocked:model_unavailable"
        if (modelKey !in AndroidReportCandidatePolicy.ALLOWED_MODEL_KEYS) {
            return "reportCandidate=blocked:model_not_allowed"
        }
        val sourceModel = resolveReportSourceModel(modelKey) ?: return "reportCandidate=blocked:source_model_missing"
        val threshold = resolveReportThreshold(modelKey, output.className) ?: return "reportCandidate=blocked:threshold_missing"
        if (!gateState.reportCandidatesAllowed) {
            return "reportCandidate=blocked:device_gate"
        }
        val deviceResources = walkSessionResourceProbe.snapshot()
        if (deviceResources.readinessStatus != WalkSessionReadinessStatus.READY) {
            return "reportCandidate=blocked:device_resources_${deviceResources.readinessStatus.name.lowercase(Locale.US)}"
        }
        val requestElapsedRealtimeMs = SystemClock.elapsedRealtime()
        val trustedLocation = freshTrustedLocationOrNull(requestElapsedRealtimeMs)
        if (trustedLocation == null) {
            if (explicitRequest) {
                ensureNavigationPermissionForReport()
                startNavigationServicesIfNeeded()
                speakInteraction("위치 정보가 필요합니다.")
            } else if (reportLocationStartScheduled.compareAndSet(false, true)) {
                runOnUiThread {
                    ensureNavigationPermissionForReport()
                    startNavigationServicesIfNeeded()
                }
            }
            return "reportCandidate=blocked:gps_missing"
        }
        val candidate = reportCandidatePolicy.prepare(
            AndroidReportCandidateInput(
                depth = output,
                location = trustedLocation,
                modelKey = modelKey,
                sourceModel = sourceModel,
                runtimeMode = "android",
                modelConfigSha256 = reportModelConfigSha256,
                modelVersion = BuildConfig.VERSION_NAME,
                sourceCommit = BuildConfig.WALKSAFE_SOURCE_COMMIT,
                threshold = threshold,
                capturedAtMs = capturedAtMs,
                requestElapsedRealtimeMs = requestElapsedRealtimeMs,
                trigger = trigger,
                reporterUserId = reporterId,
                traceId = "${output.frameId}-${output.trackId}",
                deviceGateAllowsReports = gateState.reportCandidatesAllowed,
                depthSampleCount = output.validSampleCount,
                depthValidSampleRatio = output.validSampleRatio,
                detectionAgeMs = (nowMs - capturedAtMs).coerceAtLeast(0L),
                coordinateGateStatus = "pass",
                heading = latestHeadingDeg,
                apkSha256 = reportApkSha256,
                fallbackUsed = detectorModelFallbackUsed,
                loadedModelKey = detectorLoadedModelKey,
                modelLoadReason = detectorLoadReason,
            ),
        )
        return if (candidate == null) {
            "reportCandidate=blocked"
        } else {
            processReportCandidate(
                candidate = candidate,
                output = output,
                reportImage = reportImage,
                trustedLocation = trustedLocation,
                explicitRequest = explicitRequest,
                expectedWalkEpoch = expectedWalkEpoch,
            )
        }
    }

    private fun isReportUploadTerminalCurrent(
        uploadCall: CancellableNetworkCall<*>,
        expectedWalkEpoch: WalkRuntimeEpoch,
        gatewaySession: GatewayFieldSession,
        expectedSafetyGeneration: Long,
        expectedAutomaticSafetyGeneration: Long,
    ): Boolean =
        reportPermissionsAllowWork() &&
            reportUploadSafetyGeneration == expectedSafetyGeneration &&
            (
                expectedAutomaticSafetyGeneration < 0L ||
                    automaticReportUploadSafetyGeneration ==
                    expectedAutomaticSafetyGeneration
            ) &&
            !uploadCall.isCancelled() &&
            walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) &&
            isCurrentGatewaySession(gatewaySession) &&
            reportPrivacyConsentSession.isGranted() &&
            integratedConsentSession.isAllowed(
                IntegratedConsentItem.RAW_SOURCE_COLLECTION,
            ) &&
            (
                expectedAutomaticSafetyGeneration < 0L ||
                    integratedConsentSession.isAllowed(
                        IntegratedConsentItem.AUTOMATIC_REPORTING,
                    )
            ) &&
            isWalkSessionRuntimeActive() &&
            walkSafetyOutputsAllowed()

    private fun runIfReportUploadTerminalCurrent(
        uploadCall: CancellableNetworkCall<*>,
        expectedWalkEpoch: WalkRuntimeEpoch,
        gatewaySession: GatewayFieldSession,
        expectedSafetyGeneration: Long,
        expectedAutomaticSafetyGeneration: Long,
        action: () -> Unit,
    ): Boolean {
        return synchronized(phoneMountingObservationLock) {
            synchronized(reportUploadSafetyLock) {
                if (
                    !isReportUploadTerminalCurrent(
                        uploadCall = uploadCall,
                        expectedWalkEpoch = expectedWalkEpoch,
                        gatewaySession = gatewaySession,
                        expectedSafetyGeneration = expectedSafetyGeneration,
                        expectedAutomaticSafetyGeneration =
                            expectedAutomaticSafetyGeneration,
                    )
                ) {
                    false
                } else {
                    action()
                    true
                }
            }
        }
    }

    private fun processReportCandidate(
        candidate: AndroidReportCandidate,
        output: TrackedObjectDepth,
        reportImage: ByteArray?,
        trustedLocation: TrustedLocation,
        explicitRequest: Boolean = false,
        expectedWalkEpoch: WalkRuntimeEpoch,
    ): String {
        if (!reportPermissionsAllowWork()) {
            return "reportCandidate=blocked:permission_unavailable"
        }
        if (!walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch)) {
            return "reportCandidate=blocked:walk_session_changed"
        }
        if (!officialEnvironmentOutputsAllowed) {
            return "reportCandidate=blocked:official_environment"
        }
        if (!phoneMountingOutputsAllowed) {
            return "reportCandidate=blocked:phone_mounting"
        }
        if (!reportPrivacyConsentSession.isGranted()) {
            return "reportCandidate=blocked:privacy_consent_required"
        }
        val consentConfirmation =
            integratedConsentSession.currentConfirmationOrNull()
                ?: return "reportCandidate=blocked:consent_confirmation_required"
        if (!consentConfirmation.selections.rawSourceCollection) {
            return "reportCandidate=blocked:raw_collection_consent_required"
        }
        if (
            !explicitRequest &&
            !consentConfirmation.selections.automaticReporting
        ) {
            return "reportCandidate=blocked:automatic_report_consent_required"
        }
        if (!isGatewayNetworkAllowed(reason = "report", announce = explicitRequest)) {
            return "reportCandidate=blocked:network_policy"
        }
        val gatewaySession = gatewaySessionOrNull(reason = "report", speak = explicitRequest)
            ?: return "reportCandidate=blocked:gateway_session_required"
        val gatewayProcessSnapshot = GatewaySessionProcessCoordinator.snapshot()
        if (
            gatewayProcessSnapshot.session !== gatewaySession ||
            gatewayProcessSnapshot.deletionRecoveryOnly ||
            gatewayProcessSnapshot.storageBlocked
        ) return "reportCandidate=blocked:gateway_session_changed"
        val expectedGatewaySessionGeneration = gatewayProcessSnapshot.generation
        GatewayCapacityProcessState.fenceSessionGeneration(
            expectedGatewaySessionGeneration,
        )
        val transferPurpose = if (explicitRequest) {
            ReportTransferPurpose.EXPLICIT
        } else {
            ReportTransferPurpose.AUTOMATIC
        }
        val spatialScope = AndroidReportCooldownPolicy.spatialScopeOrNull(
            actorId = gatewaySession.actorId,
            className = output.className,
            location = trustedLocation,
        ) ?: return "reportCandidate=blocked:spatial_key_invalid"
        val stateNowMs = System.currentTimeMillis()
        if (!ensureReportAttemptStateActor(gatewaySession.actorId)) {
            return "reportCandidate=blocked:attempt_state_storage"
        }
        val persistedStateKey =
            reportAttemptStateKey(
                actorId = gatewaySession.actorId,
                className = output.className,
                latitude = spatialScope.latitude,
                longitude = spatialScope.longitude,
                transferPurpose = transferPurpose,
            )
        if (!explicitRequest) {
            persistedAutomaticReportBlockStatus(
                stateKey = persistedStateKey,
                nowMs = stateNowMs,
            )?.let { return it }
        }
        val imageJpeg = reportImage ?: return "reportCandidate=blocked:no_report_image"
        val attemptResult = reportAttemptStore.acquire(
            scope = spatialScope,
            nowMs = stateNowMs,
            bypassAutomaticCooldown = explicitRequest,
        )
        if (attemptResult is AndroidReportAttemptResult.Blocked) {
            val blockedKeyToken = sha256Hex(attemptResult.storageKey.toByteArray()).take(12)
            val blockedStatus = when (attemptResult.reason) {
                AndroidReportAttemptBlockReason.IN_FLIGHT -> {
                    "reportCandidate=duplicate_inflight key=$blockedKeyToken count=${attemptResult.attemptCount}"
                }
                AndroidReportAttemptBlockReason.AUTOMATIC_COOLDOWN -> {
                    "reportCandidate=cooldown_spatial key=$blockedKeyToken remainingMs=${attemptResult.remainingMs} count=${attemptResult.attemptCount}"
                }
                AndroidReportAttemptBlockReason.RETRY_BACKOFF -> {
                    "reportCandidate=backoff key=$blockedKeyToken remainingMs=${attemptResult.remainingMs} count=${attemptResult.attemptCount}"
                }
                AndroidReportAttemptBlockReason.STATE_CAPACITY -> {
                    "reportCandidate=blocked:state_capacity key=$blockedKeyToken"
                }
            }
            if (explicitRequest && attemptResult.reason == AndroidReportAttemptBlockReason.IN_FLIGHT) {
                speakInteraction("이미 신고가 진행 중입니다.")
            }
            if (explicitRequest && attemptResult.reason == AndroidReportAttemptBlockReason.RETRY_BACKOFF) {
                speakInteraction("신고 재시도 대기 중입니다.")
            }
            return blockedStatus
        }
        val lease = (attemptResult as AndroidReportAttemptResult.Allowed).lease
        val keyToken = sha256Hex(lease.storageKey.toByteArray()).take(12)
        val attemptCount = lease.attemptCount
        val preparedStatus = "reportCandidate=prepared key=$keyToken count=$attemptCount"
        val stableTraceId =
            newStableReportTraceId(
                actorId = gatewaySession.actorId,
                sessionGeneration = gatewaySessionGeneration,
            )
        val metadataJson =
            JSONObject(candidate.metadata.toString())
                .put("trace_id", stableTraceId)
                .toString()
        if (!consentConfirmation.selections.automaticReporting) {
            if (transferPurpose == ReportTransferPurpose.AUTOMATIC) {
                reportAttemptStore.release(lease)
                return "reportCandidate=blocked:automatic_consent_required"
            }
        }
        val consentNetworkBinding =
            networkStateProbe.currentIntegratedConsentBinding()
                ?: run {
                    reportAttemptStore.release(lease)
                    return "reportCandidate=blocked:network_transport_untrusted"
                }
        if (
            consentNetworkBinding.transport ==
            IntegratedConsentNetworkTransport.CELLULAR &&
            !consentConfirmation.selections.mobileNetworkTransfer
        ) {
            reportAttemptStore.release(lease)
            return "reportCandidate=blocked:mobile_network_consent_required"
        }
        val uploadPermit =
            reportPrivacyConsentSession.issueUploadPermit(transferPurpose)
                ?: run {
                    reportAttemptStore.release(lease)
                    return "reportCandidate=blocked:privacy_consent_required"
                }
        val pendingUploadCall =
            try {
                reportUploader.uploadCall(
                    permit = uploadPermit,
                    session = gatewaySession,
                    consentConfirmation = consentConfirmation,
                    networkBinding = consentNetworkBinding,
                    transferPurpose = transferPurpose,
                    metadataJson = metadataJson,
                    imageJpeg = imageJpeg,
                    stableTraceId = stableTraceId,
                    expectedGatewayActorId = gatewaySession.actorId,
                )
            } catch (_: IllegalStateException) {
                reportAttemptStore.release(lease)
                return "reportCandidate=blocked:privacy_consent_changed"
            }
        val trackedUpload = synchronized(phoneMountingObservationLock) {
            synchronized(reportUploadSafetyLock) {
                if (
                    !walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) ||
                    !isCurrentGatewaySession(gatewaySession) ||
                    !reportPrivacyConsentSession.isGranted() ||
                    integratedConsentSession.currentConfirmationOrNull() !=
                        consentConfirmation ||
                    !consentConfirmation.selections.rawSourceCollection ||
                    (
                        transferPurpose == ReportTransferPurpose.AUTOMATIC &&
                            (
                                !permissionSessionPolicy.snapshot()
                                    .automaticReportConsentGranted ||
                                    !consentConfirmation.selections
                                        .automaticReporting
                            )
                    ) ||
                    !isWalkSessionRuntimeActive() ||
                    !walkSafetyOutputsAllowed()
                ) {
                    null
                } else {
                    val safetyGeneration = reportUploadSafetyGeneration
                    val automaticSafetyGeneration =
                        if (transferPurpose == ReportTransferPurpose.AUTOMATIC) {
                            automaticReportUploadSafetyGeneration
                        } else {
                            -1L
                        }
                    reportPrivacyConsentSession.trackIfLive(
                        uploadPermit,
                        pendingUploadCall,
                        transferPurpose,
                    )?.let {
                        Triple(
                            it,
                            safetyGeneration,
                            automaticSafetyGeneration,
                        )
                    }
                }
            }
        } ?: run {
            pendingUploadCall.cancel()
            reportAttemptStore.release(lease)
            return "reportCandidate=blocked:runtime_changed"
        }
        val (uploadCall, expectedSafetyGeneration, expectedAutomaticSafetyGeneration) =
            trackedUpload
        runOnUiThread {
            runIfReportUploadTerminalCurrent(
                uploadCall = uploadCall,
                expectedWalkEpoch = expectedWalkEpoch,
                gatewaySession = gatewaySession,
                expectedSafetyGeneration = expectedSafetyGeneration,
                expectedAutomaticSafetyGeneration = expectedAutomaticSafetyGeneration,
            ) {
                latestReportCandidateStatus = preparedStatus
            }
        }
        try {
            reportUploaderExecutor.execute {
                try {
                    if (
                        !walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) ||
                        !walkSafetyOutputsAllowed() ||
                        !runIfReportUploadTerminalCurrent(
                            uploadCall = uploadCall,
                            expectedWalkEpoch = expectedWalkEpoch,
                            gatewaySession = gatewaySession,
                            expectedSafetyGeneration = expectedSafetyGeneration,
                            expectedAutomaticSafetyGeneration =
                                expectedAutomaticSafetyGeneration,
                            action = {},
                        )
                    ) {
                        uploadCall.cancel()
                        return@execute
                    }
                    val deviceResources = walkSessionResourceProbe.snapshot()
                    if (deviceResources.readinessStatus != WalkSessionReadinessStatus.READY) {
                        uploadCall.cancel()
                        return@execute
                    }
                    val revalidation = gatewaySessionClient.revalidate(
                        gatewaySession,
                        gatewaySession.actorId,
                        capacitySessionGeneration =
                            expectedGatewaySessionGeneration,
                    )
                    if (
                        !walkSafetyOutputsAllowed() ||
                        !runIfReportUploadTerminalCurrent(
                            uploadCall = uploadCall,
                            expectedWalkEpoch = expectedWalkEpoch,
                            gatewaySession = gatewaySession,
                            expectedSafetyGeneration = expectedSafetyGeneration,
                            expectedAutomaticSafetyGeneration =
                                expectedAutomaticSafetyGeneration,
                            action = {},
                        )
                    ) {
                        uploadCall.cancel()
                        return@execute
                    }
                    if (revalidation.status != GatewaySessionRevalidationStatus.READY) {
                        uploadCall.cancel()
                        runOnUiThread {
                            if (
                                !walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) ||
                                !isCurrentGatewaySession(gatewaySession)
                            ) return@runOnUiThread
                            if (
                                revalidation.status ==
                                GatewaySessionRevalidationStatus.NOT_READY
                            ) {
                                clearGatewaySession(
                                    logoutRemote = false,
                                    expectedSession = gatewaySession,
                                )
                            }
                        }
                        return@execute
                    }
                    if (
                        transferPurpose == ReportTransferPurpose.AUTOMATIC &&
                        run {
                            GatewayCapacityProcessState.fenceSessionGeneration(
                                GatewaySessionProcessCoordinator.snapshot().generation,
                            )
                            !GatewayCapacityProcessState.admission()
                                .automaticReportCandidateAllowed
                        }
                    ) {
                        uploadCall.cancel()
                        return@execute
                    }
                    runOnUiThread {
                        runIfReportUploadTerminalCurrent(
                            uploadCall = uploadCall,
                            expectedWalkEpoch = expectedWalkEpoch,
                            gatewaySession = gatewaySession,
                            expectedSafetyGeneration = expectedSafetyGeneration,
                            expectedAutomaticSafetyGeneration =
                                expectedAutomaticSafetyGeneration,
                        ) {
                            latestReportCandidateStatus =
                                "reportCandidate=uploading key=$keyToken count=$attemptCount"
                        }
                    }
                    val response = uploadCall.execute()
                    if (
                        transferPurpose == ReportTransferPurpose.AUTOMATIC &&
                        response.receiptOutcome ==
                        ReportUploadReceiptOutcome.AUTOMATIC_COOLDOWN_AMBIGUOUS
                    ) {
                        val succeededAtMs = System.currentTimeMillis()
                        if (
                            !persistReportCooldownForSuccess(
                                AndroidReportSuccessfulCooldown(
                                    scope = spatialScope,
                                    lastUploadedAtMs = succeededAtMs,
                                ),
                            )
                        ) {
                            blockReportAttemptStorageForActor(
                                gatewaySession.actorId,
                            )
                            persistTerminalReportAttemptState(
                                persistedStateKey,
                                null,
                                succeededAtMs,
                            )
                            return@execute
                        }
                        runCatching {
                            reportAttemptStore.markSucceeded(lease, succeededAtMs)
                        }
                        val persisted =
                            clearPersistedReportAttemptState(persistedStateKey)
                        if (!persisted) {
                            persistTerminalReportAttemptState(
                                persistedStateKey,
                                null,
                                System.currentTimeMillis(),
                            )
                        }
                        runOnUiThread {
                            runIfReportUploadTerminalCurrent(
                                uploadCall = uploadCall,
                                expectedWalkEpoch = expectedWalkEpoch,
                                gatewaySession = gatewaySession,
                                expectedSafetyGeneration = expectedSafetyGeneration,
                                expectedAutomaticSafetyGeneration =
                                    expectedAutomaticSafetyGeneration,
                            ) {
                                latestReportCandidateStatus =
                                    "reportCandidate=suppressed_ambiguous key=$keyToken count=$attemptCount"
                            }
                        }
                        return@execute
                    }
                    if (response.receiptOutcome != ReportUploadReceiptOutcome.BOUND) {
                        persistTerminalReportAttemptState(
                            persistedStateKey,
                            null,
                            System.currentTimeMillis(),
                        )
                        return@execute
                    }
                    val succeededAtMs = System.currentTimeMillis()
                    if (
                        !persistReportCooldownForSuccess(
                            AndroidReportSuccessfulCooldown(
                                scope = spatialScope,
                                lastUploadedAtMs = succeededAtMs,
                            ),
                        )
                        ) {
                            blockReportAttemptStorageForActor(
                                gatewaySession.actorId,
                            )
                            persistTerminalReportAttemptState(
                                persistedStateKey,
                                null,
                                succeededAtMs,
                            )
                            runOnUiThread {
                            runIfReportUploadTerminalCurrent(
                                uploadCall = uploadCall,
                                expectedWalkEpoch = expectedWalkEpoch,
                                gatewaySession = gatewaySession,
                                expectedSafetyGeneration = expectedSafetyGeneration,
                                expectedAutomaticSafetyGeneration =
                                    expectedAutomaticSafetyGeneration,
                            ) {
                                latestReportCandidateStatus =
                                    "reportCandidate=blocked:cooldown_persistence"
                            }
                        }
                        return@execute
                    }
                    runCatching {
                        reportAttemptStore.markSucceeded(lease, succeededAtMs)
                    }
                    if (!clearPersistedReportAttemptState(persistedStateKey)) {
                        runOnUiThread {
                            runIfReportUploadTerminalCurrent(
                                uploadCall = uploadCall,
                                expectedWalkEpoch = expectedWalkEpoch,
                                gatewaySession = gatewaySession,
                                expectedSafetyGeneration = expectedSafetyGeneration,
                                expectedAutomaticSafetyGeneration =
                                    expectedAutomaticSafetyGeneration,
                            ) {
                                latestReportCandidateStatus =
                                    "reportCandidate=blocked:cooldown_persistence"
                            }
                        }
                        return@execute
                    }
                    val duplicateReportIds = response.duplicateReportIds()
                    if (!isCurrentGatewaySession(gatewaySession)) return@execute
                    runOnUiThread {
                        runIfReportUploadTerminalCurrent(
                            uploadCall = uploadCall,
                            expectedWalkEpoch = expectedWalkEpoch,
                            gatewaySession = gatewaySession,
                            expectedSafetyGeneration = expectedSafetyGeneration,
                            expectedAutomaticSafetyGeneration =
                                expectedAutomaticSafetyGeneration,
                        ) {
                            latestReportCandidateStatus = if (duplicateReportIds.isEmpty()) {
                                "reportCandidate=succeeded key=$keyToken count=$attemptCount"
                            } else {
                                "reportCandidate=succeeded_duplicate key=$keyToken duplicateIds=${duplicateReportIds.joinToString("|")} count=$attemptCount"
                            }
                            if (explicitRequest) {
                                speakInteraction(
                                    if (duplicateReportIds.isEmpty()) {
                                        "신고를 접수했습니다."
                                    } else {
                                        "이미 신고가 된 상태입니다."
                                    },
                                )
                            }
                        }
                    }
                } catch (_: ReportUploadProtocolException) {
                    persistTerminalReportAttemptState(
                        persistedStateKey,
                        null,
                        System.currentTimeMillis(),
                    )
                    runOnUiThread {
                        runIfReportUploadTerminalCurrent(
                            uploadCall = uploadCall,
                            expectedWalkEpoch = expectedWalkEpoch,
                            gatewaySession = gatewaySession,
                            expectedSafetyGeneration = expectedSafetyGeneration,
                            expectedAutomaticSafetyGeneration =
                                expectedAutomaticSafetyGeneration,
                        ) {
                            latestReportCandidateStatus =
                                "reportCandidate=failed_protocol_terminal key=$keyToken count=$attemptCount"
                            if (explicitRequest) {
                                speakInteraction("신고 응답을 확인하지 못했습니다.")
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    runOnUiThread {
                        runIfReportUploadTerminalCurrent(
                            uploadCall = uploadCall,
                            expectedWalkEpoch = expectedWalkEpoch,
                            gatewaySession = gatewaySession,
                            expectedSafetyGeneration = expectedSafetyGeneration,
                            expectedAutomaticSafetyGeneration =
                                expectedAutomaticSafetyGeneration,
                        ) {
                            latestReportCandidateStatus =
                                "reportCandidate=cancelled key=$keyToken count=$attemptCount"
                        }
                    }
                } catch (error: ReportUploadHttpException) {
                    val httpStatusCode = error.error.statusCode
                    val httpErrorBody = error.error.errorBody.toStatusToken(maxLength = 96)
                    var retryDelayMs: Long? = null
                    val transientFailure =
                        kr.co.hanium.dreamup.walksafe.report
                            .isTransientReportHttpStatus(httpStatusCode)
                    val failedAtMs = System.currentTimeMillis()
                    if (transientFailure) {
                        retryDelayMs =
                            persistTransientReportAttemptFailure(
                                stateKey = persistedStateKey,
                                nowMs = failedAtMs,
                                statusCode = httpStatusCode,
                                retryAfterMs = error.error.retryAfterMs,
                            )
                        reportAttemptStore.markFailed(
                            lease = lease,
                            nowMs = failedAtMs,
                        ) {
                            checkNotNull(retryDelayMs)
                        }
                    } else {
                        persistTerminalReportAttemptState(
                            stateKey = persistedStateKey,
                            statusCode = httpStatusCode,
                            nowMs = failedAtMs,
                        )
                    }
                    val retryStatus = retryDelayMs?.toString() ?: "none"
                    runOnUiThread {
                        val published = runIfReportUploadTerminalCurrent(
                            uploadCall = uploadCall,
                            expectedWalkEpoch = expectedWalkEpoch,
                            gatewaySession = gatewaySession,
                            expectedSafetyGeneration = expectedSafetyGeneration,
                            expectedAutomaticSafetyGeneration =
                                expectedAutomaticSafetyGeneration,
                        ) {
                            latestReportCandidateStatus =
                                "reportCandidate=failed_http status=$httpStatusCode body=$httpErrorBody retryMs=$retryStatus key=$keyToken count=$attemptCount"
                            if (explicitRequest) speakInteraction("신고 전송에 실패했습니다.")
                        }
                        if (published && (httpStatusCode == 401 || httpStatusCode == 403)) {
                            clearGatewaySession(
                                logoutRemote = false,
                                expectedSession = gatewaySession,
                            )
                        }
                    }
                } catch (_: RuntimeException) {
                    val failedAtMs = System.currentTimeMillis()
                    val retryDelayMs =
                        persistTransientReportAttemptFailure(
                            stateKey = persistedStateKey,
                            nowMs = failedAtMs,
                        )
                    reportAttemptStore.markFailed(
                        lease = lease,
                        nowMs = failedAtMs,
                    ) {
                        retryDelayMs
                    }
                    runOnUiThread {
                        runIfReportUploadTerminalCurrent(
                            uploadCall = uploadCall,
                            expectedWalkEpoch = expectedWalkEpoch,
                            gatewaySession = gatewaySession,
                            expectedSafetyGeneration = expectedSafetyGeneration,
                            expectedAutomaticSafetyGeneration =
                                expectedAutomaticSafetyGeneration,
                        ) {
                            latestReportCandidateStatus =
                                "reportCandidate=failed retryMs=$retryDelayMs key=$keyToken count=$attemptCount"
                            if (explicitRequest) speakInteraction("신고 전송에 실패했습니다.")
                        }
                    }
                } finally {
                    reportPrivacyConsentSession.complete(uploadCall)
                    reportAttemptStore.release(lease)
                }
            }
        } catch (_: RejectedExecutionException) {
            uploadCall.cancel()
            reportPrivacyConsentSession.complete(uploadCall)
            reportAttemptStore.release(lease)
            return preparedStatus
        }
        return preparedStatus
    }

    private fun requestExplicitReport() {
        if (!currentRuntimeMetricOutputAllowsWork()) {
            clearExplicitReportFrameState("runtime_metric_unavailable")
            updateNavigationStatus(latestReportCandidateStatus)
            return
        }
        if (!requireReporterUserId("login_required_explicit_report")) return
        if (!reportPrivacyConsentSession.isGranted()) {
            latestReportCandidateStatus = "reportCandidate=blocked:privacy_consent_required trigger=voice"
            updateNavigationStatus(latestReportCandidateStatus)
            speakInteraction("손상 점자블록 신고 데이터 전송과 180일 보관 동의가 필요합니다.")
            return
        }
        if (freshTrustedLocationOrNull() == null) {
            ensureNavigationPermissionForReport()
            startNavigationServicesIfNeeded()
            latestReportCandidateStatus = "reportCandidate=blocked:gps_missing trigger=voice"
            updateNavigationStatus(latestReportCandidateStatus)
            speakInteraction("위치 정보가 필요합니다.")
            return
        }
        val status = prepareExplicitReportCandidateIfCurrent(System.currentTimeMillis())
        latestReportCandidateStatus = status
        updateNavigationStatus(status)
        if (status == "reportCandidate=blocked:no_depth_object" || status.contains("blocked:not_reportable_class")) {
            speakInteraction("신고할 손상 점자블록이 없습니다.")
        }
    }

    private fun prepareExplicitReportCandidateIfCurrent(nowMs: Long): String =
        synchronized(frameStateLock) frameLock@ {
            synchronized(runtimeMetricStateLock) runtimeLock@ {
                if (!currentRuntimeMetricOutputAllowsWork()) {
                    return@runtimeLock "reportCandidate=blocked:runtime_metric_unavailable"
                }
                val output = latestExplicitReportOutput
                prepareReportCandidate(
                    reportOutput = output,
                    gateState = latestExplicitReportGateState ?: buildDeviceGateState(
                        bestOutput = output,
                        staleReason = null,
                        expectedRuntimeMetricGeneration = arSessionGeneration,
                    ),
                    reportImage = latestExplicitReportImage,
                    nowMs = nowMs,
                    capturedAtMs = latestExplicitReportCapturedAtMs.takeIf { it > 0L } ?: nowMs,
                    trigger = "voice",
                    explicitRequest = true,
                )
            }
        }

    private fun ensureVoicePermissionThenListen() {
        if (!requireFirstRunOnboardingComplete("voice_command")) return
        if (!isWalkSessionRuntimeActive()) return
        if (!requireReporterUserId("login_required_voice_command")) return
        if (!hasRecordAudioPermission()) {
            requestPermissionsWithLease(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                PermissionRequestPurpose.VOICE_COMMAND,
            )
            return
        }
        startVoiceCommandRecognition()
    }

    private fun startVoiceCommandRecognition(
        purpose: VoiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND,
        expectedGatewayWalkOperationId: String? = null,
    ): Boolean {
        val sessionSnapshot = walkSessionLifecycle.snapshot()
        val expectedWalkEpoch = sessionSnapshot.epoch
        val expectedResumeToken = if (purpose == VoiceRecognitionPurpose.WALK_SESSION_RESUME) {
            walkSessionResumeConfirmationToken
        } else {
            null
        }
        val expectedNavigationDecisionToken = if (purpose == VoiceRecognitionPurpose.COMMAND) {
            routeNavigator.pendingDecisionToken()
        } else {
            null
        }
        val mayListen = when (purpose) {
            VoiceRecognitionPurpose.COMMAND -> {
                walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) &&
                    requireStartupCapabilityConfirmation()
            }
            VoiceRecognitionPurpose.WALK_SESSION_RESUME -> {
                sessionSnapshot.state == WalkSessionState.PAUSED &&
                    sessionSnapshot.recoveryStage ==
                    WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION &&
                    sessionSnapshot.isForeground &&
                    walkSessionResumePromptPending &&
                    expectedResumeToken != null &&
                    sessionSnapshot.confirmationToken == expectedResumeToken &&
                    isStartupCapabilityConfirmed()
            }
            VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER -> {
                sessionSnapshot.state == WalkSessionState.READY &&
                    sessionSnapshot.isForeground &&
                    gatewayWalkTakeoverPromptPending &&
                    expectedGatewayWalkOperationId != null &&
                    gatewayWalkTakeoverPromptOperationId ==
                    expectedGatewayWalkOperationId &&
                    gatewayWalkStartConfirmationToken ==
                    sessionSnapshot.confirmationToken &&
                    gatewayWalkAuthorityController.currentConflictOrNull(
                        expectedWalkEpoch,
                    ) != null
            }
        }
        if (!mayListen) return false
        if (voiceRecognitionActive) return false
        if (!feedbackPolicy.canSpeakNavigation(SystemClock.elapsedRealtime())) {
            updateNavigationStatus("voice=blocked_by_active_feedback")
            return false
        }
        if (feedbackActuator?.prepareForSpeechRecognition() == false) {
            updateNavigationStatus("voice=blocked_by_risk_speech")
            return false
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            updateNavigationStatus("voice=recognizer_unavailable")
            if (purpose == VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER) {
                handleGatewayWalkTakeoverRecognition(emptyList())
                return false
            }
            handleRuntimeSpeechCapabilityFailure(
                WalkSafeStartupRequirement.ON_DEVICE_STT,
                "휴대폰 내부 음성 인식을 사용할 수 없어 보행 기능을 중지했습니다.",
            )
            return false
        }
        val onDeviceRecognitionAvailable =
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(this) }.getOrDefault(false)
        if (!onDeviceRecognitionAvailable) {
            updateNavigationStatus("voice=recognizer_unavailable")
            if (purpose == VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER) {
                handleGatewayWalkTakeoverRecognition(emptyList())
                return false
            }
            handleRuntimeSpeechCapabilityFailure(
                WalkSafeStartupRequirement.ON_DEVICE_STT,
                "휴대폰 내부 음성 인식을 사용할 수 없어 보행 기능을 중지했습니다.",
            )
            return false
        }
        val generation = ++voiceRecognitionGeneration
        val recognizer = speechRecognizer ?: runCatching {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        }.getOrElse {
            updateNavigationStatus("voice=on_device_recognizer_creation_failed")
            if (purpose == VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER) {
                handleGatewayWalkTakeoverRecognition(emptyList())
                return false
            }
            handleRuntimeSpeechCapabilityFailure(
                WalkSafeStartupRequirement.ON_DEVICE_STT,
                "휴대폰 내부 음성 인식을 시작할 수 없어 보행 기능을 중지했습니다.",
            )
            return false
        }.also {
            speechRecognizer = it
        }
        voiceRecognitionPurpose = purpose
        recognizer.setRecognitionListener(
            buildVoiceCommandRecognitionListener(
                generation = generation,
                purpose = purpose,
                expectedWalkEpoch = expectedWalkEpoch,
                expectedResumeToken = expectedResumeToken,
                expectedGatewayWalkOperationId = expectedGatewayWalkOperationId,
                expectedNavigationDecisionToken = expectedNavigationDecisionToken,
            ),
        )
        voiceRecognitionActive = true
        updateVoiceCommandButton(active = true)
        updateNavigationStatus(
            when (purpose) {
                VoiceRecognitionPurpose.COMMAND -> "voice=listening command"
                VoiceRecognitionPurpose.WALK_SESSION_RESUME ->
                    "voice=listening walk_session_resume"
                VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER ->
                    "voice=listening walk_session_takeover"
            },
        )
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        return runCatching {
            recognizer.startListening(intent)
            true
        }.getOrElse {
            voiceRecognitionActive = false
            voiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND
            updateVoiceCommandButton(active = false)
            if (purpose == VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER) {
                handleGatewayWalkTakeoverRecognition(emptyList())
                return false
            }
            handleRuntimeSpeechCapabilityFailure(
                WalkSafeStartupRequirement.ON_DEVICE_STT,
                "휴대폰 내부 음성 인식을 시작할 수 없어 보행 기능을 중지했습니다.",
            )
            false
        }
    }

    private fun cancelVoiceCommandRecognition() {
        voiceRecognitionGeneration += 1
        voiceRecognitionActive = false
        voiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND
        speechRecognizer?.cancel()
        updateVoiceCommandButton(active = false)
    }

    private fun buildVoiceCommandRecognitionListener(
        generation: Int,
        purpose: VoiceRecognitionPurpose,
        expectedWalkEpoch: WalkRuntimeEpoch,
        expectedResumeToken: WalkSessionConfirmationToken?,
        expectedGatewayWalkOperationId: String?,
        expectedNavigationDecisionToken: RouteNavigatorDecisionToken?,
    ): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onError(error: Int) {
                if (
                    !isVoiceRecognitionLeaseCurrent(
                        generation,
                        purpose,
                        expectedWalkEpoch,
                        expectedResumeToken,
                        expectedGatewayWalkOperationId,
                    )
                ) return
                voiceRecognitionGeneration += 1
                voiceRecognitionActive = false
                voiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND
                updateVoiceCommandButton(active = false)
                updateNavigationStatus("voice=recognition_failed code=$error")
                if (
                    error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) {
                    when (purpose) {
                        VoiceRecognitionPurpose.WALK_SESSION_RESUME ->
                            handleWalkSessionResumeRecognition(emptyList())
                        VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER ->
                            handleGatewayWalkTakeoverRecognition(emptyList())
                        VoiceRecognitionPurpose.COMMAND ->
                            speakInteraction("음성 명령을 인식하지 못했습니다.")
                    }
                    return
                }
                speechRecognizer?.destroy()
                speechRecognizer = null
                if (purpose == VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER) {
                    handleGatewayWalkTakeoverRecognition(emptyList())
                    return
                }
                handleRuntimeSpeechCapabilityFailure(
                    WalkSafeStartupRequirement.ON_DEVICE_STT,
                    "휴대폰 내부 음성 인식에 오류가 발생해 보행 기능을 중지했습니다.",
                )
            }

            override fun onResults(results: Bundle?) {
                if (
                    !isVoiceRecognitionLeaseCurrent(
                        generation,
                        purpose,
                        expectedWalkEpoch,
                        expectedResumeToken,
                        expectedGatewayWalkOperationId,
                    )
                ) return
                voiceRecognitionGeneration += 1
                voiceRecognitionActive = false
                voiceRecognitionPurpose = VoiceRecognitionPurpose.COMMAND
                updateVoiceCommandButton(active = false)
                val phrases = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                val confidenceScores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                when (purpose) {
                    VoiceRecognitionPurpose.WALK_SESSION_RESUME ->
                        handleWalkSessionResumeRecognition(phrases)
                    VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER ->
                        handleGatewayWalkTakeoverRecognition(phrases)
                    VoiceRecognitionPurpose.COMMAND ->
                        handleVoiceCommandPhrases(
                            phrases,
                            confidenceScores,
                            expectedNavigationDecisionToken,
                        )
                }
            }
        }
    }

    private fun isVoiceRecognitionLeaseCurrent(
        expectedGeneration: Int,
        expectedPurpose: VoiceRecognitionPurpose,
        expectedWalkEpoch: WalkRuntimeEpoch,
        expectedResumeToken: WalkSessionConfirmationToken?,
        expectedGatewayWalkOperationId: String?,
    ): Boolean {
        if (
            expectedGeneration != voiceRecognitionGeneration ||
            expectedPurpose != voiceRecognitionPurpose
        ) return false
        return when (expectedPurpose) {
            VoiceRecognitionPurpose.COMMAND ->
                walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch)
            VoiceRecognitionPurpose.WALK_SESSION_RESUME -> {
                val snapshot = walkSessionLifecycle.snapshot()
                expectedResumeToken != null &&
                    snapshot.epoch == expectedWalkEpoch &&
                    snapshot.state == WalkSessionState.PAUSED &&
                    snapshot.recoveryStage ==
                    WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION &&
                    snapshot.confirmationToken == expectedResumeToken &&
                    walkSessionResumeConfirmationToken == expectedResumeToken
            }
            VoiceRecognitionPurpose.WALK_SESSION_TAKEOVER -> {
                val snapshot = walkSessionLifecycle.snapshot()
                expectedGatewayWalkOperationId != null &&
                    snapshot.epoch == expectedWalkEpoch &&
                    snapshot.state == WalkSessionState.READY &&
                    gatewayWalkTakeoverPromptPending &&
                    gatewayWalkTakeoverPromptOperationId ==
                    expectedGatewayWalkOperationId &&
                    gatewayWalkStartConfirmationToken ==
                    snapshot.confirmationToken &&
                    gatewayWalkAuthorityController.currentConflictOrNull(
                        expectedWalkEpoch,
                    ) != null
            }
        }
    }

    private fun handleVoiceCommandPhrases(
        phrases: List<String>,
        confidenceScores: FloatArray?,
        expectedNavigationDecisionToken: RouteNavigatorDecisionToken?,
    ) {
        val recognized = phrases.firstOrNull { it.isNotBlank() }.orEmpty()
        val action = selectAndroidVoiceAction(phrases, confidenceScores)
        if (action == null) {
            updateNavigationStatus("voice=command_unmatched phrase=${recognized.toStatusToken(maxLength = 48)}")
            speakInteraction("명령을 이해하지 못했습니다. 다시 말씀해 주세요.")
            return
        }
        if (
            action in setOf(
                AndroidVoiceAction.RequestReroute,
                AndroidVoiceAction.RecheckLocation,
                AndroidVoiceAction.CancelDestination,
                AndroidVoiceAction.ConfirmArrival,
                AndroidVoiceAction.RejectArrival,
                AndroidVoiceAction.StopNavigation,
            ) && expectedNavigationDecisionToken != routeNavigator.pendingDecisionToken()
        ) {
            updateNavigationStatus("voice=navigation_decision_stale")
            speakInteraction("경로 상태가 바뀌어 이전 음성 결정을 적용하지 않았습니다. 다시 확인해 주세요.")
            return
        }
        executeVoiceAction(action)
    }

    private fun executeVoiceAction(action: AndroidVoiceAction) {
        when (action) {
            AndroidVoiceAction.CreateReport -> {
                updateNavigationStatus("voice=report_command_recognized")
                requestExplicitReport()
            }
            is AndroidVoiceAction.SearchDestination -> startVoiceDestinationSearch(action.query)
            is AndroidVoiceAction.SelectDestinationCandidate -> selectVoiceDestinationCandidate(action.oneBasedIndex)
            AndroidVoiceAction.HearMoreDestinationCandidates -> hearMoreVoiceDestinationCandidates()
            AndroidVoiceAction.CancelDestination -> cancelDestinationFromVoice()
            AndroidVoiceAction.SpeakNextNavigationInstruction -> speakNextNavigationInstruction()
            AndroidVoiceAction.RequestReroute -> requestRerouteFromVoice()
            AndroidVoiceAction.RecheckLocation -> recheckLocationFromVoice()
            AndroidVoiceAction.ConfirmArrival -> confirmArrivalFromVoice()
            AndroidVoiceAction.RejectArrival -> rejectArrivalFromVoice()
            AndroidVoiceAction.StopNavigation -> stopNavigationFromVoice()
        }
    }

    private fun startVoiceDestinationSearch(query: String) {
        if (destinationSearchInFlight) {
            cancelDestinationSearch()
        }
        destinationQueryInput.setText(query)
        pendingVoiceDestinationQuery = query
        pendingVoiceDestinationPageIndex = 0
        destinationSearchVoiceState = null
        if (performDestinationSearch(reset = true)) {
            updateNavigationStatus("voice=destination_search query=${query.toStatusToken(maxLength = 48)}")
            speakInteraction("${query} 목적지를 검색합니다.")
        } else {
            pendingVoiceDestinationQuery = null
            pendingVoiceDestinationPageIndex = null
        }
    }

    private fun hearMoreVoiceDestinationCandidates() {
        val state = destinationSearchVoiceState
        if (state == null) {
            speakInteraction("먼저 목적지를 검색해 주세요.")
            return
        }
        val transition = state.onCommand(DestinationSearchVoiceCommand.HearMore)
        if (transition.accepted) {
            destinationSearchVoiceState = transition.state
            speakInteraction(transition.state.voicePrompt())
            return
        }
        val canLoadMore = destinationSearchResults.size >= destinationSearchPage * DESTINATION_SEARCH_PAGE_SIZE &&
            destinationSearchResults.size < DESTINATION_SEARCH_MAX_RESULTS
        if (!canLoadMore || destinationSearchInFlight) {
            speakInteraction("더 안내할 목적지 후보가 없습니다.")
            return
        }
        pendingVoiceDestinationQuery = destinationSearchQuery
        pendingVoiceDestinationPageIndex = state.pageIndex + 1
        if (!performDestinationSearch(reset = false)) {
            pendingVoiceDestinationQuery = null
            pendingVoiceDestinationPageIndex = null
        }
    }

    private fun cancelDestinationFromVoice() {
        if (blockRouteMutationWhileDeviationChoicePending()) return
        val hadDestination = isRouteActive ||
            routeRequestInFlight.get() ||
            destinationSearchInFlight ||
            currentDestination != null ||
            destinationQueryInput.text?.isNotBlank() == true ||
            (::destinationLatInput.isInitialized && destinationLatInput.text?.isNotBlank() == true) ||
            (::destinationLngInput.isInitialized && destinationLngInput.text?.isNotBlank() == true)
        pendingVoiceDestinationQuery = null
        pendingVoiceDestinationPageIndex = null
        destinationSearchVoiceState = null
        if (destinationSearchInFlight) cancelDestinationSearch()
        resetRouteState()
        destinationSearchQuery = ""
        destinationQueryInput.setText("")
        if (::destinationLatInput.isInitialized) destinationLatInput.setText("")
        if (::destinationLngInput.isInitialized) destinationLngInput.setText("")
        updateDestinationSearchUi()
        val message = if (hadDestination) {
            "목적지와 진행 중인 경로를 취소했습니다."
        } else {
            "취소할 목적지가 없습니다."
        }
        updateNavigationStatus("voice=destination_cancelled hadDestination=$hadDestination")
        speakInteraction(message)
    }

    private fun selectVoiceDestinationCandidate(oneBasedIndex: Int) {
        if (!requireReporterUserId("login_required_voice_destination_select")) return
        val voiceSelection = destinationSearchVoiceState?.onCommand(
            DestinationSearchVoiceCommand.SelectCandidate(oneBasedIndex),
        )
        val selected = voiceSelection?.selectedResult
        if (selected == null) {
            val message = if (destinationSearchInFlight) {
                "목적지 검색이 끝난 뒤 후보 번호를 말씀해 주세요."
            } else {
                "선택할 ${oneBasedIndex}번 목적지 후보가 없습니다."
            }
            updateNavigationStatus("voice=destination_candidate_missing index=$oneBasedIndex")
            speakInteraction(message)
            return
        }
        pendingVoiceDestinationQuery = null
        pendingVoiceDestinationPageIndex = null
        destinationSearchVoiceState = null
        if (!onDestinationSelected(selected)) return
        updateNavigationStatus("voice=destination_candidate_selected index=$oneBasedIndex")
        speakInteraction("${selected.name} 목적지를 선택했습니다. TMAP 경로를 확인합니다.")
    }

    private fun requestRerouteFromVoice() {
        val destination = currentDestination
        if (
            destination == null ||
            !isRouteActive ||
            routeNavigator.pendingUserDecision() != RouteNavigatorUserDecision.REROUTE
        ) {
            speakInteraction("지금은 새 경로를 요청할 이탈 상태가 아닙니다.")
            return
        }
        val decision = routeNavigator.selectDeviationChoice(RouteDeviationChoice.NEW_ROUTE)
        updateRouteDeviationActions(decision.pendingUserDecision)
        updateNavigationStatus("navigation=off_route_reroute_user_confirmed")
        speakInteraction(requireNotNull(decision.instruction))
        requestRoute(destination, reason = "off_route")
        if (!routeRequestInFlight.get() && routeNavigator.hasRoute()) {
            retainRouteAfterRerouteFailure("TMAP 새 경로 요청을 시작할 수 없어 방향 안내를 중지했습니다.")
        }
    }

    private fun recheckLocationFromVoice() {
        if (
            !isRouteActive ||
            routeNavigator.pendingUserDecision() !in setOf(
                RouteNavigatorUserDecision.LOCATION_RECHECK,
                RouteNavigatorUserDecision.REROUTE,
            )
        ) {
            speakInteraction("지금은 위치를 다시 확인할 이탈 상태가 아닙니다.")
            return
        }
        val decision = routeNavigator.selectDeviationChoice(RouteDeviationChoice.RECHECK_LOCATION)
        updateRouteDeviationActions(decision.pendingUserDecision)
        latestTmapOnRoute = false
        directionGuidancePauseReason = "off_route_recheck"
        startNavigationServicesIfNeeded()
        updateNavigationStatus("navigation=off_route_location_recheck_requested auto_resume=false")
        speakInteraction(requireNotNull(decision.instruction))
    }

    private fun endNavigationAfterDeviation() {
        if (
            !isRouteActive ||
            routeNavigator.pendingUserDecision() != RouteNavigatorUserDecision.REROUTE
        ) {
            speakInteraction("지금은 종료 선택을 기다리는 경로 이탈 상태가 아닙니다.")
            return
        }
        val decision = routeNavigator.selectDeviationChoice(RouteDeviationChoice.END_NAVIGATION)
        if (decision.reason != "off_route_navigation_ended") return
        resetRouteState()
        if (routeSnapshotPurgeFailed) {
            val detail =
                "암호화 경로 기록을 삭제하지 못했습니다. 새 길안내를 시작하지 말고 앱 저장소를 확인해 주세요."
            updateStatus(
                "길안내 종료 저장소 확인 필요",
                detail,
            )
            updateNavigationStatus("navigation=route_snapshot_purge_failed")
            speakInteraction(detail)
        } else {
            updateNavigationStatus("navigation=off_route_navigation_ended_by_user")
            speakInteraction(requireNotNull(decision.instruction))
        }
    }

    private fun confirmArrivalFromVoice() {
        val decision = routeNavigator.confirmArrival()
        if (!decision.arrived) {
            speakInteraction("지금은 확인할 도착 후보가 없습니다.")
            return
        }
        resetRouteState()
        updateNavigationStatus("navigation=arrival_confirmed_by_user")
        speakInteraction(requireNotNull(decision.instruction))
    }

    private fun rejectArrivalFromVoice() {
        val decision = routeNavigator.rejectArrival()
        if (decision.reason != "arrival_rejected_route_retained") {
            speakInteraction("지금은 거절할 도착 후보가 없습니다.")
            return
        }
        updateNavigationStatus("navigation=arrival_rejected_route_retained")
        speakInteraction(requireNotNull(decision.instruction))
    }

    private fun speakNextNavigationInstruction() {
        val expectedWalkEpoch = walkSessionLifecycle.currentRuntimeEpochOrNull() ?: return
        val location = freshTrustedLocationOrNull()
        val routeInstruction = if (isRouteActive && latestTmapOnRoute && location != null) {
            routeNavigator.currentInstruction(location)
        } else {
            null
        }
        val message = when {
            !isRouteActive -> "진행 중인 길안내가 없습니다."
            !latestTmapOnRoute -> "현재 TMAP 경로를 다시 확인하고 있습니다. 안전한 위치에서 잠시 기다려 주세요."
            location == null -> "현재 위치를 확인한 뒤 다음 경로를 안내합니다."
            else -> routeInstruction ?: "다음 경로 안내를 확인할 수 없습니다."
        }
        updateNavigationStatus("voice=next_navigation_instruction active=$isRouteActive")
        if (routeInstruction == null) {
            speakInteraction(message)
        } else {
            val requestGeneration = routeRequestGeneration
            speakNavigation(message) {
                if (
                    walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) &&
                    requestGeneration == routeRequestGeneration
                ) {
                    routeNavigator.acknowledgeCurrentInstruction(SystemClock.elapsedRealtime())
                }
            }
        }
    }

    private fun stopNavigationFromVoice() {
        val hadActiveNavigation = isRouteActive || routeRequestInFlight.get()
        if (
            isRouteActive &&
            routeNavigator.pendingUserDecision() == RouteNavigatorUserDecision.REROUTE
        ) {
            endNavigationAfterDeviation()
            return
        }
        if (routeRequestInFlight.get()) {
            cancelActiveRouteRequest()
        } else if (isRouteActive) {
            resetRouteState()
        }
        val message = if (hadActiveNavigation) "길안내를 중지했습니다." else "진행 중인 길안내가 없습니다."
        updateNavigationStatus("voice=navigation_stopped hadActive=$hadActiveNavigation")
        speakInteraction(message)
    }

    private fun updateVoiceCommandButton(active: Boolean = voiceRecognitionActive) {
        if (!::voiceReportButton.isInitialized) return
        voiceReportButton.text = if (active) "음성 듣는 중" else "음성 명령"
        voiceReportButton.isEnabled = !active
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdatesIfAllowed(forceRestart: Boolean = false) {
        if (!currentLocationCollectionAllowsWork()) {
            stopLocationUpdates()
            return
        }
        if (!::fusedLocationClient.isInitialized || !hasLocationPermission()) {
            updateNavigationStatus("navigation=gps_permission_missing hazard_only")
            pauseDirectionGuidance(
                reason = "location_permission_missing",
                message = "정확한 위치 권한이 없어 방향 안내를 중지했습니다.",
            )
            return
        }
        val walkEpoch = walkSessionLifecycle.currentRuntimeEpochOrNull() ?: run {
            stopLocationUpdates()
            return
        }
        if (!forceRestart && locationCallback != null) return
        locationCallback?.let(fusedLocationClient::removeLocationUpdates)
        val generation = ++locationCallbackGeneration
        lateinit var callback: LocationCallback
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                if (!isLocationCallbackCurrent(walkEpoch, generation, callback)) return
                result.lastLocation?.let { location ->
                    handleLocationUpdate(location, walkEpoch, generation)
                }
            }

            override fun onLocationAvailability(availability: LocationAvailability) {
                if (!isLocationCallbackCurrent(walkEpoch, generation, callback)) return
                if (!availability.isLocationAvailable) {
                    recordOfficialEnvironmentGpsObservation(
                        epoch = walkEpoch,
                        observedAtElapsedRealtimeMs = SystemClock.elapsedRealtime(),
                        trustedFixAvailable = false,
                        horizontalAccuracyMeters = null,
                    )
                    val routeDecisionRequired = clearTrustedLocation()
                    updateNavigationStatus("navigation=gps_unavailable")
                    if (!routeDecisionRequired) {
                        pauseDirectionGuidance(
                            reason = "gps_unavailable",
                            message = "GPS 위치를 확인할 수 없어 방향 안내를 중지했습니다.",
                        )
                    }
                }
            }
        }
        locationCallback = callback
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(LOCATION_FASTEST_INTERVAL_MS)
            .build()
        fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    private fun stopLocationUpdates() {
        locationCallbackGeneration += 1
        val callback = locationCallback
        locationCallback = null
        if (::fusedLocationClient.isInitialized) {
            callback?.let(fusedLocationClient::removeLocationUpdates)
        }
        clearTrustedLocation()
    }

    private fun isLocationCallbackCurrent(
        walkEpoch: WalkRuntimeEpoch,
        generation: Int,
        callback: LocationCallback,
    ): Boolean =
        locationCallback === callback &&
            generation == locationCallbackGeneration &&
            walkSessionLifecycle.isRuntimeEpochCurrent(walkEpoch) &&
            currentLocationCollectionAllowsWork()

    private fun freshTrustedLocationOrNull(
        nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ): TrustedLocation? {
        val current = latestTrustedLocation
        val fresh = LocationTrustPolicy.freshOrNull(current, nowElapsedRealtimeMs)
        if (current != null && fresh == null) {
            clearLocationDerivedState()
            if (!handleRouteLocationUntrusted()) {
                pauseDirectionGuidance(
                    reason = "gps_stale",
                    message = "GPS 위치가 오래되어 방향 안내를 중지했습니다.",
                )
            }
        }
        return fresh
    }

    private fun clearTrustedLocation(): Boolean {
        latestTrustedLocation = null
        clearLocationDerivedState()
        return handleRouteLocationUntrusted()
    }

    private fun handleRouteLocationUntrusted(): Boolean {
        val update = routeNavigator.onUntrustedLocation() ?: return false
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyRouteDeviationSafetyUpdate(update)
        } else {
            val expectedToken = routeNavigator.pendingDecisionToken()
            runOnUiThread {
                if (expectedToken == routeNavigator.pendingDecisionToken()) {
                    applyRouteDeviationSafetyUpdate(update)
                }
            }
        }
        return true
    }

    private fun clearLocationDerivedState() {
        latestHeadingDeg = null
        lastCalibrationLocation = null
        lastCalibrationStepCount = 0
        lastCalibrationAtMs = 0L
    }

    private fun startStepTrackingIfAllowed() {
        if (!currentNavigationCollectionAllowsWork()) {
            stopStepTracking()
            return
        }
        if (!::stepTracker.isInitialized) return
        if (!hasActivityRecognitionPermission()) {
            updateNavigationStatus("navigation=activity_recognition_permission_missing step_fallback_wait")
            return
        }
        stepTrackingEpoch = walkSessionLifecycle.currentRuntimeEpochOrNull()
        stepTracker.start()
    }

    private fun stopStepTracking() {
        stepTrackingEpoch = null
        activityOriginalUploadAdmission.onTrackingStopped(
            ::cancelActivityOriginalUploads,
        )
        if (::stepTracker.isInitialized) stepTracker.stop()
    }

    /** Filters raw GPS fixes; this is not IMU/Kalman/dead-reckoning coordinate correction. */
    private fun handleLocationUpdate(
        location: Location,
        walkEpoch: WalkRuntimeEpoch,
        locationGeneration: Int,
    ) {
        if (
            !walkSessionLifecycle.isRuntimeEpochCurrent(walkEpoch) ||
            locationGeneration != locationCallbackGeneration ||
            !currentLocationCollectionAllowsWork()
        ) return
        val accuracy = if (location.hasAccuracy()) location.accuracy else null
        val elapsedMs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            location.elapsedRealtimeNanos / 1_000_000L
        } else {
            SystemClock.elapsedRealtime()
        }
        val previousTrusted = latestTrustedLocation
        val trusted = LocationTrustPolicy.trustedOrNull(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracyM = accuracy,
            elapsedRealtimeMs = elapsedMs,
            previous = latestTrustedLocation,
        )
        val freshTrusted = LocationTrustPolicy.freshOrNull(trusted, SystemClock.elapsedRealtime())
        recordOfficialEnvironmentGpsObservation(
            epoch = walkEpoch,
            observedAtElapsedRealtimeMs = elapsedMs,
            trustedFixAvailable = freshTrusted != null,
            horizontalAccuracyMeters = accuracy?.toDouble(),
        )
        if (freshTrusted == null) {
            // Keep the last accepted fix only as the jump-filter baseline. Freshness gates
            // continue to block it from reports and routing once it is older than maxAgeMs.
            clearLocationDerivedState()
            val routeDecisionRequired = handleRouteLocationUntrusted()
            updateNavigationStatus("navigation=gps_untrusted accuracy=${accuracy?.toInt() ?: "null"}m")
            if (!routeDecisionRequired) {
                pauseDirectionGuidance(
                    reason = "gps_untrusted",
                    message = "GPS 정확도를 신뢰할 수 없어 방향 안내를 중지했습니다.",
                )
            }
            return
        }
        latestTrustedLocation = freshTrusted
        if (!currentNavigationCollectionAllowsWork()) return
        reportLocationStartScheduled.set(false)
        latestHeadingDeg = updateHeadingFromLocation(previous = previousTrusted, location = location, trusted = freshTrusted)
        attemptStepCalibration(freshTrusted)
        updateNavigationStatus(
            "navigation=gps_trusted accuracy=${freshTrusted.accuracyM.toInt()}m steps=$latestStepCount heading=${latestHeadingDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "null"}",
        )
        if (isRouteActive && !routeRequestInFlight.get() && !routeNavigator.hasRoute()) {
            updateNavigationStatus("navigation=route_waiting user_route_decision_required trusted_gps_ready")
        }
        updateRouteGuidance(freshTrusted)
    }

    private fun updateHeadingFromLocation(
        previous: TrustedLocation?,
        location: Location,
        trusted: TrustedLocation,
    ): Float? {
        val bearingAccuracy = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && location.hasBearingAccuracy()) {
            location.bearingAccuracyDegrees
        } else {
            null
        }
        return reliableMovementHeadingDegrees(
            reportedBearingDeg = location.bearing.takeIf { location.hasBearing() },
            speedMps = location.speed.takeIf { location.hasSpeed() },
            bearingAccuracyDeg = bearingAccuracy,
            previous = previous,
            current = trusted,
        )
    }

    private fun buildDepthMotionContext(elapsedRealtimeMs: Long): MotionContext {
        // General obstacle confidence is independent of destination and route state. Route-bound
        // tactile guidance validates heading and corridor separately in TactileRoutePolicy.
        val freshness = freshTrustedLocationOrNull(elapsedRealtimeMs)?.let { trusted ->
            val ageMs = elapsedRealtimeMs - trusted.elapsedRealtimeMs
            when {
                ageMs < 0L -> 0.8f
                ageMs <= 2_500L -> 1f
                ageMs <= 7_500L -> 0.75f
                else -> 0.45f
            }
        } ?: 0.7f
        return MotionContext(
            freshnessQuality = freshness,
        )
    }

    internal fun processTactileSnapshotFrame(
        detectionSnapshot: DetectionSnapshot,
        nowMs: Long,
        currentFrameTimestampMs: Long,
        maxDetectionSourceAgeMs: Long,
        maxDetectionFrameDeltaMs: Long,
        detectionAgeMs: Long?,
        processDepth: (DetectionFrameEvidence, List<DetectionCandidate>) -> List<TrackedObjectDepth>,
    ): TactileSnapshotFrameResult = tactileFrameCoordinator.processSnapshot(
        detectionSnapshot = detectionSnapshot,
        nowMs = nowMs,
        currentFrameTimestampMs = currentFrameTimestampMs,
        maxDetectionSourceAgeMs = maxDetectionSourceAgeMs,
        maxDetectionFrameDeltaMs = maxDetectionFrameDeltaMs,
        detectionAgeMs = detectionAgeMs,
        navigationActiveNow = isRouteActive && !routeRequestInFlight.get(),
        tmapOnRouteNow = latestTmapOnRoute,
        activeRouteId = routeNavigator.currentRouteId(),
        processDepth = processDepth,
    )

    internal fun prepareTactileFrameDispatch(
        detectionSnapshot: DetectionSnapshot,
        nowMs: Long,
        currentFrameTimestampMs: Long,
        maxDetectionSourceAgeMs: Long,
        maxDetectionFrameDeltaMs: Long,
        detectionAgeMs: Long?,
        processDepth: (DetectionFrameEvidence, List<DetectionCandidate>) -> List<TrackedObjectDepth>,
    ): PreparedTactileFrameDispatch = ProductionPreparedTactileFrameDispatch(
        frame = this.processTactileSnapshotFrame(
            detectionSnapshot = detectionSnapshot,
            nowMs = nowMs,
            currentFrameTimestampMs = currentFrameTimestampMs,
            maxDetectionSourceAgeMs = maxDetectionSourceAgeMs,
            maxDetectionFrameDeltaMs = maxDetectionFrameDeltaMs,
            detectionAgeMs = detectionAgeMs,
            processDepth = processDepth,
        ),
    )

    internal fun evaluateTactileFrame(
        input: AndroidTactileFrameInput,
    ): TactileRouteGuidanceResult = tactileFrameCoordinator.evaluate(input)

    internal fun dispatchTactileFrameFeedback(
        frame: TactileSnapshotFrameResult,
        stale: Boolean,
        deviceGateAllowsAlerts: Boolean,
        nowMs: Long,
        feedbackActuatorOverride: TactileFrameFeedbackActuator? = null,
    ): TactileFrameFeedbackDispatch = tactileFrameCoordinator.dispatchFeedback(
        frame = frame,
        stale = stale,
        deviceGateAllowsAlerts = deviceGateAllowsAlerts,
        nowMs = nowMs,
        feedbackActuatorOverride = feedbackActuatorOverride,
    )

    internal fun <T> matchingFrameEvidenceOrNull(
        evidence: T?,
        identity: AndroidTactileFrameIdentity,
        context: TactileProjectionContext?,
        requireDepthMapper: Boolean,
    ): T? = tactileFrameCoordinator.matchingFrameEvidenceOrNull(
        evidence = evidence,
        identity = identity,
        context = context,
        requireDepthMapper = requireDepthMapper,
    )

    private fun buildTactileProjectionContext(
        frame: Frame,
        elapsedRealtimeMs: Long,
    ): TactileProjectionContext {
        val location = freshTrustedLocationOrNull(elapsedRealtimeMs)
        val expectedRouteId = routeNavigator.currentRouteId()
        val routeProjection = location?.let(routeNavigator::currentProjection)
        val magneticDeclinationDeg = location?.let { trusted ->
            runCatching {
                GeomagneticField(
                    trusted.latitude.toFloat(),
                    trusted.longitude.toFloat(),
                    0f,
                    System.currentTimeMillis(),
                ).declination
            }.getOrNull()
        }
        return ArCoreTactileProjectionContextFactory.create(
            frame = frame,
            elapsedRealtimeMs = elapsedRealtimeMs,
            expectedRouteId = expectedRouteId,
            routeProjection = routeProjection,
            trustedLocation = location,
            orientation = earthOrientationTracker.latest(),
            magneticDeclinationDeg = magneticDeclinationDeg,
        )
    }

    private fun attemptStepCalibration(trusted: TrustedLocation) {
        if (lastCalibrationLocation == null || lastCalibrationStepCount == 0) {
            lastCalibrationLocation = trusted
            lastCalibrationStepCount = latestStepCount
            lastCalibrationAtMs = trusted.elapsedRealtimeMs
            return
        }
        val deltaSteps = latestStepCount - lastCalibrationStepCount
        if (deltaSteps < 0) {
            lastCalibrationLocation = trusted
            lastCalibrationStepCount = latestStepCount
            lastCalibrationAtMs = trusted.elapsedRealtimeMs
            return
        }
        val elapsedMs = trusted.elapsedRealtimeMs - lastCalibrationAtMs
        val distanceM = haversineMeters(
            lastCalibrationLocation!!.latitude,
            lastCalibrationLocation!!.longitude,
            trusted.latitude,
            trusted.longitude,
        ).toFloat()
        if (deltaSteps >= MIN_STEP_CALIBRATION_STEPS && distanceM >= MIN_STEP_CALIBRATION_DISTANCE_M && elapsedMs >= MIN_STEP_CALIBRATION_DURATION_MS) {
            val calibrated = stepLengthEstimator.calibrate(
                StepCalibrationSample(
                    distanceM = distanceM,
                    steps = deltaSteps,
                    durationMs = elapsedMs,
                ),
            )
            if (calibrated) {
                persistStepLength()
                objectDepthPipeline.setUserStepLength(stepLengthEstimator.stepLengthM)
                val stepLengthText = String.format(Locale.US, "%.2f", stepLengthEstimator.stepLengthM)
                updateNavigationStatus("navigation=step_length_updated=${stepLengthText}m")
            }
            lastCalibrationLocation = trusted
            lastCalibrationStepCount = latestStepCount
            lastCalibrationAtMs = trusted.elapsedRealtimeMs
        } else if (elapsedMs >= MAX_STEP_CALIBRATION_GAP_MS) {
            lastCalibrationLocation = trusted
            lastCalibrationStepCount = latestStepCount
            lastCalibrationAtMs = trusted.elapsedRealtimeMs
        }
    }

    private fun onRouteButtonClicked() {
        if (!currentNavigationCollectionAllowsWork()) return
        if (!requireReporterUserId("login_required_route")) return
        if (blockRouteMutationWhileDeviationChoicePending()) return
        if (routeRequestInFlight.get()) {
            cancelActiveRouteRequest()
            return
        }
        if (isRouteActive) {
            resetRouteState()
            return
        }
        if (!isRouteLocationPermissionReady()) {
            if (!ensureNavigationPermissionForRouteOrStep()) {
                return
            }
        }
        if (!navigationPermissionsRequestedForRoute) {
            navigationPermissionsRequestedForRoute = true
        }
        startNavigationServicesIfNeeded()
        if (!isRouteLocationPermissionReady()) {
            updateNavigationStatus("navigation=gps_permission_missing")
            return
        }
        val destination = parseDestinationInput()
        if (destination == null) {
            updateNavigationStatus("navigation=destination_missing")
            return
        }
        if (destinationSearchInFlight || navigationRequests.hasActiveDestinationSearch()) cancelDestinationSearch()
        currentDestination = destination
        isRouteActive = true
        latestTmapOnRoute = false
        updateRouteButtonText()
        requestRoute(destination, reason = "user_destination")
    }

    private fun routeSnapshotPurgeFenceAllowsRoute(): Boolean {
        if (!routeSnapshotPurgeFailed) return true
        val detail =
            "암호화 경로 기록을 삭제하지 못해 새 길안내를 시작하지 않습니다. 저장소를 확인하고 새 보행을 시작해 주세요."
        enterWalkSessionSafetyStopAndCancelOutputs("route_snapshot_purge_failed")
        updateStatus("길안내 저장소 오류 · 안전 중지", detail)
        updateNavigationStatus("navigation=safety_stopped reason=route_snapshot_purge_failed")
        speakInteraction(detail)
        return false
    }

    private fun resetRouteState(purgeRouteSnapshot: Boolean = true) {
        navigationRequests.cancelRoute()
        isRouteActive = false
        latestTmapOnRoute = false
        latestTactileRouteState = "localRoute=tmap:navigation_inactive"
        routeRequestGeneration += 1
        routeRequestInFlight.set(false)
        currentDestination = null
        navigationPermissionsRequestedForRoute = false
        routeNavigator.clear()
        if (purgeRouteSnapshot) purgeEncryptedRouteSnapshot()
        lastAnnouncedRouteDecisionToken = null
        routeDeviationHapticDecision = null
        updateRouteDeviationActions(null)
        routeStartStepCount = null
        destinationSearchResults.clear()
        destinationSearchVoiceState = null
        updateDestinationSearchUi()
        updateRouteButtonText()
        updateNavigationStatus(
            if (routeSnapshotPurgeFailed) {
                "navigation=route_snapshot_purge_failed"
            } else {
                "navigation=destination_none hazard_only"
            },
        )
    }

    private fun purgeEncryptedRouteSnapshot(): Boolean {
        cancelEncryptedRouteSnapshotExpirySchedule()
        val purged = !::routeSnapshotStore.isInitialized || runCatching {
            routeSnapshotStore.clear()
        }.getOrDefault(false)
        routeSnapshotPurgeFailed = !purged
        return purged
    }

    private fun cancelEncryptedRouteSnapshotExpirySchedule() {
        routeSnapshotExpiryRunnable?.let(reportCleanupCallbackHandler::removeCallbacks)
        routeSnapshotExpiryRunnable = null
    }

    private fun scheduleEncryptedRouteSnapshotExpiry(
        walkSessionId: String,
        expiresAtEpochMs: Long,
    ) {
        cancelEncryptedRouteSnapshotExpirySchedule()
        lateinit var expiry: Runnable
        expiry = Runnable {
            if (routeSnapshotExpiryRunnable !== expiry) return@Runnable
            routeSnapshotExpiryRunnable = null
            val purged = purgeEncryptedRouteSnapshot()
            val activeSnapshotExpired = ::walkSessionLifecycle.isInitialized &&
                walkSessionLifecycle.currentRuntimeEpochOrNull()?.walkSessionId == walkSessionId &&
                isRouteActive
            if (activeSnapshotExpired) {
                enterWalkSessionSafetyStopAndCancelOutputs("route_snapshot_expired")
                val detail =
                    "암호화 경로 기록의 24시간 보관 시간이 끝나 길안내를 중지했습니다."
                updateStatus(
                    "길안내 안전 중지",
                    detail,
                )
                updateNavigationStatus(
                    "navigation=safety_stopped reason=route_snapshot_expired purge=$purged",
                )
                speakInteraction(detail)
            } else if (!purged) {
                updateNavigationStatus("navigation=route_snapshot_purge_failed")
            }
        }
        routeSnapshotExpiryRunnable = expiry
        val remainingMs = (expiresAtEpochMs - System.currentTimeMillis())
            .coerceIn(0L, ROUTE_SNAPSHOT_TTL_MS)
        reportCleanupCallbackHandler.postDelayed(expiry, remainingMs)
    }

    private fun cancelActiveRouteRequest() {
        clearActiveRouteRequestState(navigationRequests.cancelRoute())
    }

    private fun clearActiveRouteRequestState(
        transportWasActive: Boolean,
        preserveExistingRoute: Boolean = false,
    ) {
        if (!routeRequestInFlight.get() && !transportWasActive) return
        routeRequestGeneration += 1
        routeRequestInFlight.set(false)
        if (preserveExistingRoute && isRouteActive && routeNavigator.hasRoute()) {
            navigationPermissionsRequestedForRoute = false
            updateRouteButtonText()
            updateNavigationStatus("navigation=route_request_cancelled existing_route_retained")
            return
        }
        isRouteActive = false
        latestTmapOnRoute = false
        latestTactileRouteState = "localRoute=tmap:navigation_inactive"
        currentDestination = null
        navigationPermissionsRequestedForRoute = false
        routeNavigator.clear()
        purgeEncryptedRouteSnapshot()
        lastAnnouncedRouteDecisionToken = null
        routeDeviationHapticDecision = null
        updateRouteDeviationActions(null)
        routeStartStepCount = null
        updateRouteButtonText()
        updateNavigationStatus(
            if (routeSnapshotPurgeFailed) {
                "navigation=route_snapshot_purge_failed"
            } else {
                "navigation=route_request_cancelled hazard_only"
            },
        )
    }

    private fun suspendNavigationForRecovery(
        cancellation: AndroidNavigationCancellation,
    ) {
        val retainedDestination = currentDestination
        routeRequestGeneration += 1
        routeRequestInFlight.set(false)
        destinationSearchGeneration += 1
        destinationSearchInFlight = false
        pendingVoiceDestinationQuery = null
        pendingVoiceDestinationPageIndex = null
        destinationSearchVoiceState = null
        isRouteActive = false
        latestTmapOnRoute = false
        latestTactileRouteState = "localRoute=tmap:navigation_inactive"
        navigationPermissionsRequestedForRoute = false
        routeNavigator.clear()
        lastAnnouncedRouteDecisionToken = null
        routeDeviationHapticDecision = null
        updateRouteDeviationActions(null)
        routeStartStepCount = null
        currentDestination = retainedDestination
        destinationSearchResults.clear()
        updateDestinationSearchUi()
        updateRouteButtonText()
        updateNavigationStatus(
            "navigation=route_revalidation_required " +
                "route_cancelled=${cancellation.routeCancelled} " +
                "search_cancelled=${cancellation.destinationSearchCancelled}",
        )
    }

    private fun parseDestinationInput(): RoutePoint? {
        if (!::destinationLatInput.isInitialized || !::destinationLngInput.isInitialized) return null
        val latText = destinationLatInput.text?.toString()?.trim().orEmpty()
        val lngText = destinationLngInput.text?.toString()?.trim().orEmpty()
        if (latText.isBlank() || lngText.isBlank()) return null
        val latitude = latText.toDoubleOrNull() ?: return null
        val longitude = lngText.toDoubleOrNull() ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return RoutePoint(latitude = latitude, longitude = longitude, name = "목적지")
    }

    private fun performDestinationSearch(reset: Boolean): Boolean {
        if (!currentNavigationCollectionAllowsWork()) return false
        if (blockRouteMutationWhileDeviationChoicePending()) return false
        val expectedWalkEpoch = walkSessionLifecycle.currentRuntimeEpochOrNull() ?: return false
        if (!requireReporterUserId("login_required_destination_search")) return false
        if (!isGatewayNetworkAllowed(reason = "destination_search")) return false
        val gatewaySession = gatewaySessionOrNull("destination_search") ?: return false
        val gatewayProcessSnapshot = GatewaySessionProcessCoordinator.snapshot()
        if (
            gatewayProcessSnapshot.session !== gatewaySession ||
            gatewayProcessSnapshot.deletionRecoveryOnly ||
            gatewayProcessSnapshot.storageBlocked
        ) return false
        val expectedGatewaySessionGeneration = gatewayProcessSnapshot.generation
        GatewayCapacityProcessState.fenceSessionGeneration(
            expectedGatewaySessionGeneration,
        )
        if (destinationSearchInFlight || navigationRequests.hasActiveDestinationSearch()) return false
        if (!isRouteLocationPermissionReady() && !ensureNavigationPermissionForRouteOrStep()) {
            return false
        }
        val query = destinationQueryInput.text?.toString()?.trim().orEmpty()
        if (query.isBlank()) {
            updateNavigationStatus("navigation=destination_query_missing")
            return false
        }
        val requestId = destinationSearchGeneration + 1
        destinationSearchGeneration = requestId
        if (reset) {
            destinationSearchPage = 1
            destinationSearchResults.clear()
            destinationSearchQuery = query
            destinationSearchVoiceState = null
            updateDestinationSearchUi()
        } else if (destinationSearchQuery != query) {
            destinationSearchPage = 1
            destinationSearchResults.clear()
            destinationSearchQuery = query
            destinationSearchVoiceState = null
            updateDestinationSearchUi()
        } else {
            destinationSearchPage += 1
        }
        val queryLimit = DESTINATION_SEARCH_PAGE_SIZE * destinationSearchPage
        destinationSearchInFlight = true
        destinationSearchButton.isEnabled = false
        destinationMoreButton.isEnabled = false
        destinationCancelButton.isEnabled = true
        val origin = freshTrustedLocationOrNull()?.let { RoutePoint(it.latitude, it.longitude, "현재 위치") }
        val searchCall = trackDestinationSearchRequest(
            walkingRouteClient.searchDestinationsCall(
                session = gatewaySession,
                query = query,
                limit = queryLimit,
                origin = origin,
            ),
        )
        try {
            routeExecutor.execute {
                try {
                    if (
                        !isDestinationSearchLeaseCurrent(expectedWalkEpoch, requestId) ||
                        !isCurrentGatewaySession(gatewaySession)
                    ) {
                        completeDestinationSearchRequest(searchCall)
                        searchCall.cancel()
                        return@execute
                    }
                    val revalidation = gatewaySessionClient.revalidate(
                        gatewaySession,
                        currentReporterUserId(),
                        capacitySessionGeneration =
                            expectedGatewaySessionGeneration,
                    )
                    if (revalidation.status != GatewaySessionRevalidationStatus.READY) {
                        completeDestinationSearchRequest(searchCall)
                        searchCall.cancel()
                        runOnUiThread {
                            if (
                                !isDestinationSearchLeaseCurrent(
                                    expectedWalkEpoch,
                                    requestId,
                                )
                            ) return@runOnUiThread
                            if (
                                revalidation.status ==
                                GatewaySessionRevalidationStatus.NOT_READY
                            ) {
                                clearGatewaySession(
                                    logoutRemote = false,
                                    expectedSession = gatewaySession,
                                )
                            } else {
                                destinationSearchInFlight = false
                                destinationSearchButton.isEnabled = true
                                destinationCancelButton.isEnabled = false
                                updateDestinationSearchUi()
                            }
                            if (pendingVoiceDestinationQuery == query) {
                                pendingVoiceDestinationQuery = null
                                pendingVoiceDestinationPageIndex = null
                            }
                            updateNavigationStatus(
                                "navigation=destination_search_wait " +
                                    "gateway=${revalidation.status.name.lowercase(Locale.US)}",
                            )
                        }
                        return@execute
                    }
                    if (
                        !isDestinationSearchLeaseCurrent(expectedWalkEpoch, requestId) ||
                        !isCurrentGatewaySession(gatewaySession)
                    ) {
                        completeDestinationSearchRequest(searchCall)
                        searchCall.cancel()
                        return@execute
                    }
                    val result = searchCall.execute()
                    if (!isCurrentGatewaySession(gatewaySession)) {
                        completeDestinationSearchRequest(searchCall)
                        runOnUiThread {
                            if (
                                !isDestinationSearchLeaseCurrent(
                                    expectedWalkEpoch,
                                    requestId,
                                )
                            ) return@runOnUiThread
                            destinationSearchInFlight = false
                            destinationSearchButton.isEnabled = true
                            destinationCancelButton.isEnabled = false
                            updateDestinationSearchUi()
                            updateNavigationStatus("navigation=destination_search_cancelled session_changed")
                            if (pendingVoiceDestinationQuery == query) {
                                pendingVoiceDestinationQuery = null
                                pendingVoiceDestinationPageIndex = null
                            }
                        }
                        return@execute
                    }
                    completeDestinationSearchRequest(searchCall)
                    runOnUiThread {
                        if (
                            !isDestinationSearchLeaseCurrent(
                                expectedWalkEpoch,
                                requestId,
                            )
                        ) return@runOnUiThread
                        if (!isCurrentGatewaySession(gatewaySession)) {
                            destinationSearchInFlight = false
                            destinationSearchButton.isEnabled = true
                            destinationCancelButton.isEnabled = false
                            updateDestinationSearchUi()
                            updateNavigationStatus("navigation=destination_search_cancelled session_changed")
                            if (pendingVoiceDestinationQuery == query) {
                                pendingVoiceDestinationQuery = null
                                pendingVoiceDestinationPageIndex = null
                            }
                            return@runOnUiThread
                        }
                        destinationSearchInFlight = false
                        destinationSearchButton.isEnabled = true
                        destinationCancelButton.isEnabled = false
                        val merged = if (reset) result.results else {
                            destinationSearchResults + result.results
                        }
                        destinationSearchResults.clear()
                        destinationSearchResults.addAll(merged.distinctBy { "${it.id}:${it.point.latitude}:${it.point.longitude}" })
                        updateDestinationSearchUi()
                        updateNavigationStatus("navigation=search_results query=${result.query} count=${result.results.size}")
                        if (pendingVoiceDestinationQuery == query) {
                            pendingVoiceDestinationQuery = null
                            val requestedPageIndex = pendingVoiceDestinationPageIndex ?: 0
                            pendingVoiceDestinationPageIndex = null
                            val lastPageIndex = if (destinationSearchResults.isEmpty()) {
                                0
                            } else {
                                (destinationSearchResults.size - 1) / DESTINATION_SEARCH_PAGE_SIZE
                            }
                            if (requestedPageIndex <= lastPageIndex) {
                                destinationSearchVoiceState = DestinationSearchVoiceState(
                                    query = query,
                                    results = destinationSearchResults.toList(),
                                    pageIndex = requestedPageIndex,
                                    moreResultsAvailable =
                                        destinationSearchResults.size >=
                                            destinationSearchPage * DESTINATION_SEARCH_PAGE_SIZE &&
                                            destinationSearchResults.size < DESTINATION_SEARCH_MAX_RESULTS,
                                )
                                speakInteraction(requireNotNull(destinationSearchVoiceState).voicePrompt())
                            } else {
                                speakInteraction("더 안내할 목적지 후보가 없습니다.")
                            }
                        }
                    }
                } catch (_: CancellationException) {
                    completeDestinationSearchRequest(searchCall)
                    runOnUiThread {
                        if (
                            !isDestinationSearchLeaseCurrent(
                                expectedWalkEpoch,
                                requestId,
                            )
                        ) return@runOnUiThread
                        destinationSearchInFlight = false
                        destinationSearchButton.isEnabled = true
                        destinationCancelButton.isEnabled = false
                        updateDestinationSearchUi()
                        updateNavigationStatus("navigation=destination_search_cancelled")
                    }
                } catch (error: RuntimeException) {
                    completeDestinationSearchRequest(searchCall)
                    runOnUiThread {
                        if (
                            !isDestinationSearchLeaseCurrent(
                                expectedWalkEpoch,
                                requestId,
                            )
                        ) return@runOnUiThread
                        val failureGuard = gatewayFailureUiGuardOrNull(
                            session = gatewaySession,
                            expectedAuthenticationFailure =
                                error is GatewayProxyHttpException &&
                                    error.statusCode in setOf(401, 403),
                        )
                        destinationSearchInFlight = false
                        destinationSearchButton.isEnabled = true
                        destinationCancelButton.isEnabled = false
                        updateDestinationSearchUi()
                        val voiceRequest = pendingVoiceDestinationQuery == query
                        if (voiceRequest) {
                            pendingVoiceDestinationQuery = null
                            pendingVoiceDestinationPageIndex = null
                        }
                        if (failureGuard == null || !isGatewayFailureUiGuardCurrent(gatewaySession, failureGuard)) {
                            return@runOnUiThread
                        }
                        updateNavigationStatus("navigation=destination_search_failed ${error::class.java.simpleName}")
                        if (voiceRequest) {
                            speakInteraction("${query} 목적지 검색에 실패했습니다.")
                        }
                    }
                } finally {
                    completeDestinationSearchRequest(searchCall)
                }
            }
            return true
        } catch (_: RejectedExecutionException) {
            completeDestinationSearchRequest(searchCall)
            searchCall.cancel()
            if (!isDestinationSearchLeaseCurrent(expectedWalkEpoch, requestId)) {
                return false
            }
            destinationSearchInFlight = false
            destinationSearchButton.isEnabled = true
            destinationCancelButton.isEnabled = false
            destinationSearchResults.removeAll { true }
            updateDestinationSearchUi()
            updateNavigationStatus("navigation=destination_search_failed executor_rejected")
            if (pendingVoiceDestinationQuery == query) {
                pendingVoiceDestinationQuery = null
                pendingVoiceDestinationPageIndex = null
                speakInteraction("${query} 목적지 검색을 시작할 수 없습니다.")
            }
            return false
        }
    }

    private fun isDestinationSearchLeaseCurrent(
        expectedWalkEpoch: WalkRuntimeEpoch,
        expectedRequestId: Int,
    ): Boolean =
        walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) &&
            expectedRequestId == destinationSearchGeneration &&
            (!isWalkSessionRuntimeActive() || walkSafetyOutputsAllowed())

    private fun cancelDestinationSearch() {
        clearDestinationSearchState(navigationRequests.cancelDestinationSearch())
    }

    private fun clearDestinationSearchState(transportWasActive: Boolean) {
        if (!destinationSearchInFlight && !transportWasActive) return
        pendingVoiceDestinationQuery = null
        pendingVoiceDestinationPageIndex = null
        destinationSearchGeneration += 1
        destinationSearchInFlight = false
        destinationSearchButton.isEnabled = true
        destinationCancelButton.isEnabled = false
        destinationMoreButton.isEnabled = false
        destinationMoreButton.visibility = View.GONE
        updateDestinationSearchUi()
        updateNavigationStatus("navigation=destination_search_cancelled")
    }

    private fun onDestinationSelected(result: DestinationSearchResult): Boolean {
        if (blockRouteMutationWhileDeviationChoicePending()) return false
        val cancellation = cancelNavigationRequestsForDestinationSelection()
        clearDestinationSearchState(cancellation.destinationSearchCancelled)
        if (!currentNavigationCollectionAllowsWork()) return false
        if (!requireReporterUserId("login_required_route_select")) return false
        if (routeRequestInFlight.get() || cancellation.routeCancelled) {
            clearActiveRouteRequestState(cancellation.routeCancelled)
        } else if (isRouteActive) {
            resetRouteState()
        }
        if (::destinationLatInput.isInitialized) {
            destinationLatInput.setText(result.point.latitude.toString())
        }
        if (::destinationLngInput.isInitialized) {
            destinationLngInput.setText(result.point.longitude.toString())
        }
        currentDestination = result.point
        isRouteActive = true
        latestTmapOnRoute = false
        updateRouteButtonText()
        updateNavigationStatus("navigation=destination_selected ${result.name} ${formatDestinationDistance(result.distanceM)}")
        val routeRequestGenerationBefore = routeRequestGeneration
        requestRoute(result.point, reason = "user_destination")
        return routeRequestGeneration != routeRequestGenerationBefore &&
            routeRequestInFlight.get()
    }

    private fun updateDestinationSearchUi() {
        if (!::destinationSearchResultsContainer.isInitialized) return
        destinationSearchResultsContainer.removeAllViews()
        if (destinationSearchResults.isEmpty()) {
            destinationSearchResultsContainer.addView(
                TextView(this).apply {
                    text = if (destinationSearchQuery.isBlank()) "검색어를 입력하세요." else "검색 결과 없음"
                    textSize = 12f
                    setTextColor(0xffd7d7ff.toInt())
                },
            )
            destinationMoreButton.visibility = View.GONE
            return
        }
        destinationSearchResults.forEach { result ->
            destinationSearchResultsContainer.addView(
                Button(this).apply {
                    text = "${result.name} · ${result.address ?: "주소 없음"} · ${formatDestinationDistance(result.distanceM)}"
                    textSize = 11f
                    setOnClickListener {
                        onDestinationSelected(result)
                    }
                },
            )
        }
        val canLoadMore = destinationSearchResults.size >= destinationSearchPage * DESTINATION_SEARCH_PAGE_SIZE &&
            destinationSearchResults.size < DESTINATION_SEARCH_MAX_RESULTS
        destinationMoreButton.visibility = if (canLoadMore) View.VISIBLE else View.GONE
        destinationMoreButton.isEnabled = !destinationSearchInFlight
        destinationCancelButton.isEnabled = destinationSearchInFlight
    }

    private fun updateRouteButtonText() {
        if (!::routeButton.isInitialized) return
        syncActiveSessionScreenPolicy()
        routeButton.text = when {
            routeRequestInFlight.get() -> "경로 취소"
            isRouteActive -> "경로 정지"
            else -> "경로 시작"
        }
    }

    /** Serializes route requests so user start and off-route reroute cannot race each other. */
    private fun requestRoute(destination: RoutePoint, reason: String) {
        if (!currentNavigationCollectionAllowsWork()) return
        if (reason != "off_route" && blockRouteMutationWhileDeviationChoicePending()) return
        if (!routeSnapshotPurgeFenceAllowsRoute()) return
        val expectedWalkEpoch = walkSessionLifecycle.currentRuntimeEpochOrNull() ?: return
        val deviceResources = walkSessionResourceProbe.snapshot()
        if (deviceResources.readinessStatus != WalkSessionReadinessStatus.READY) {
            updateNavigationStatus(
                "navigation=route_blocked device_resources=" +
                    deviceResources.readinessStatus.name.lowercase(Locale.US),
            )
            return
        }
        val preserveExistingRoute = reason == "off_route" && isRouteActive
        if (!isGatewayNetworkAllowed(reason = "walking_route")) {
            if (!preserveExistingRoute) isRouteActive = false
            updateRouteButtonText()
            return
        }
        val gatewaySession = gatewaySessionOrNull("walking_route") ?: run {
            if (!preserveExistingRoute) isRouteActive = false
            latestTmapOnRoute = false
            if (!preserveExistingRoute) currentDestination = null
            updateRouteButtonText()
            return
        }
        val gatewayProcessSnapshot = GatewaySessionProcessCoordinator.snapshot()
        if (
            gatewayProcessSnapshot.session !== gatewaySession ||
            gatewayProcessSnapshot.deletionRecoveryOnly ||
            gatewayProcessSnapshot.storageBlocked
        ) return
        val expectedGatewaySessionGeneration = gatewayProcessSnapshot.generation
        GatewayCapacityProcessState.fenceSessionGeneration(
            expectedGatewaySessionGeneration,
        )
        val origin = freshTrustedLocationOrNull()
        if (origin == null) {
            latestTmapOnRoute = false
            if (!preserveExistingRoute && !isRouteLocationPermissionReady()) {
                isRouteActive = false
                currentDestination = null
            } else if (!preserveExistingRoute) {
                navigationPermissionsRequestedForRoute = true
                startNavigationServicesIfNeeded()
            }
            updateRouteButtonText()
            updateNavigationStatus(
                if (preserveExistingRoute) {
                    "navigation=reroute_blocked trusted_gps_missing existing_route_retained"
                } else if (isRouteActive) {
                    "navigation=route_waiting trusted_gps_missing"
                } else {
                    "navigation=route_blocked gps_permission_missing"
                },
            )
            return
        }
        if (navigationRequests.hasActiveRoute() || !routeRequestInFlight.compareAndSet(false, true)) {
            updateNavigationStatus("navigation=route_blocked request_in_flight")
            return
        }
        val requestId = ++routeRequestGeneration
        updateNavigationStatus("navigation=route_requesting reason=$reason priority=STAIR_AVOID")
        updateRouteButtonText()
        val routeCall = trackRouteRequest(
            walkingRouteClient.fetchRouteCall(
                session = gatewaySession,
                request = WalkingRouteRequest(
                    origin = RoutePoint(origin.latitude, origin.longitude, "현재 위치"),
                    destination = destination,
                ),
            ),
        )
        try {
            routeExecutor.execute {
                var routeProviderCallInProgress = false
                try {
                    if (
                        !isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId) ||
                        !isCurrentGatewaySession(gatewaySession)
                    ) {
                        completeRouteRequest(routeCall)
                        routeCall.cancel()
                        return@execute
                    }
                    val revalidation = gatewaySessionClient.revalidate(
                        gatewaySession,
                        currentReporterUserId(),
                        capacitySessionGeneration =
                            expectedGatewaySessionGeneration,
                    )
                    if (revalidation.status != GatewaySessionRevalidationStatus.READY) {
                        completeRouteRequest(routeCall)
                        routeCall.cancel()
                        runOnUiThread {
                            if (
                                !isRouteRequestLeaseCurrent(
                                    expectedWalkEpoch,
                                    requestId,
                                )
                            ) return@runOnUiThread
                            if (
                                revalidation.status ==
                                GatewaySessionRevalidationStatus.NOT_READY
                            ) {
                                clearGatewaySession(
                                    logoutRemote = false,
                                    expectedSession = gatewaySession,
                                )
                            } else if (preserveExistingRoute) {
                                routeRequestInFlight.set(false)
                                latestTmapOnRoute = false
                                retainRouteAfterRerouteFailure(
                                    "Gateway가 새 TMAP 경로를 아직 확인하지 못해 방향 안내를 중지했습니다.",
                                )
                                updateRouteButtonText()
                            } else {
                                routeRequestInFlight.set(false)
                                isRouteActive = false
                                latestTmapOnRoute = false
                                routeNavigator.clear()
                                routeStartStepCount = null
                                currentDestination = destination
                                updateRouteButtonText()
                            }
                            updateNavigationStatus(
                                "navigation=route_wait " +
                                    "gateway=${revalidation.status.name.lowercase(Locale.US)}",
                            )
                        }
                        return@execute
                    }
                    if (
                        !isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId) ||
                        !isCurrentGatewaySession(gatewaySession)
                    ) {
                        completeRouteRequest(routeCall)
                        routeCall.cancel()
                        return@execute
                    }
                    routeProviderCallInProgress = true
                    val route = routeCall.execute()
                    routeProviderCallInProgress = false
                    if (!isCurrentGatewaySession(gatewaySession)) {
                        completeRouteRequest(routeCall)
                        runOnUiThread {
                            if (
                                !isRouteRequestLeaseCurrent(
                                    expectedWalkEpoch,
                                    requestId,
                                )
                            ) return@runOnUiThread
                            isRouteActive = false
                            latestTmapOnRoute = false
                            currentDestination = null
                            routeRequestInFlight.set(false)
                            updateRouteButtonText()
                            updateNavigationStatus("navigation=route_cancelled session_changed")
                        }
                        return@execute
                    }
                    if (!isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId)) {
                        completeRouteRequest(routeCall)
                        return@execute
                    }
                    completeRouteRequest(routeCall)
                    runOnUiThread {
                        if (
                            !isRouteRequestLeaseCurrent(
                                expectedWalkEpoch,
                                requestId,
                            )
                        ) return@runOnUiThread
                        if (!isCurrentGatewaySession(gatewaySession)) {
                            isRouteActive = false
                            latestTmapOnRoute = false
                            currentDestination = null
                            routeRequestInFlight.set(false)
                            updateRouteButtonText()
                            updateNavigationStatus("navigation=route_cancelled session_changed")
                            return@runOnUiThread
                        }
                        if (!isRouteActive) return@runOnUiThread
                        if (!routeSnapshotPurgeFenceAllowsRoute()) return@runOnUiThread
                        val storedRouteSnapshot = runCatching {
                            val saved = routeSnapshotStore.saveFirstRoute(
                                walkSessionId = expectedWalkEpoch.walkSessionId,
                                route = route,
                                destination = destination,
                            )
                            if (!saved) null else routeSnapshotStore.load()
                        }.getOrNull()?.takeIf {
                            it.walkSessionId == expectedWalkEpoch.walkSessionId
                        }
                        if (storedRouteSnapshot == null) {
                            routeRequestInFlight.set(false)
                            enterWalkSessionSafetyStopAndCancelOutputs("route_snapshot_store_failed")
                            val detail =
                                "최초 TMAP 경로를 암호화해 저장하지 못해 보행 기능을 중지했습니다."
                            updateStatus(
                                "길안내 안전 중지",
                                detail,
                            )
                            updateNavigationStatus("navigation=safety_stopped reason=route_snapshot_store_failed")
                            speakInteraction(detail)
                            return@runOnUiThread
                        }
                        routeSnapshotPurgeFailed = false
                        scheduleEncryptedRouteSnapshotExpiry(
                            walkSessionId = storedRouteSnapshot.walkSessionId,
                            expiresAtEpochMs = storedRouteSnapshot.expiresAtEpochMs,
                        )
                        tmapFailureGuard.recordSuccess()
                        routeNavigator.setRoute(
                            route,
                            destination = destination,
                        )
                        routeStartStepCount = latestStepCount
                        directionGuidancePauseReason = null
                        latestTmapOnRoute = true
                        routeRequestInFlight.set(false)
                        updateRouteButtonText()
                        updateNavigationStatus(
                            "navigation=route_ready distance=${route.summary.distanceM}m priority=${route.priority}",
                        )
                    }
                } catch (_: CancellationException) {
                    completeRouteRequest(routeCall)
                    runOnUiThread {
                        if (
                            !isRouteRequestLeaseCurrent(
                                expectedWalkEpoch,
                                requestId,
                            )
                        ) return@runOnUiThread
                        routeRequestInFlight.set(false)
                        if (!preserveExistingRoute) isRouteActive = false
                        latestTmapOnRoute = false
                        if (!preserveExistingRoute) currentDestination = null
                        if (preserveExistingRoute) {
                            retainRouteAfterRerouteFailure("TMAP 새 경로 요청이 취소되어 방향 안내를 중지했습니다.")
                        }
                        updateRouteButtonText()
                        updateNavigationStatus("navigation=route_cancelled")
                    }
                } catch (error: Exception) {
                    completeRouteRequest(routeCall)
                    val navigationFailure = classifyNavigationBackendFailure(error)
                    val countableTmapFailure = routeProviderCallInProgress &&
                        navigationFailure.kind != NavigationBackendErrorKind.AUTHENTICATION
                    runOnUiThread {
                        if (
                            !isRouteRequestLeaseCurrent(
                                expectedWalkEpoch,
                                requestId,
                            )
                        ) return@runOnUiThread
                        val failureGuard = gatewayFailureUiGuardOrNull(
                            session = gatewaySession,
                            expectedAuthenticationFailure =
                                navigationFailure.kind == NavigationBackendErrorKind.AUTHENTICATION,
                        )
                        routeRequestInFlight.set(false)
                        updateRouteButtonText()
                        if (failureGuard == null || !isGatewayFailureUiGuardCurrent(gatewaySession, failureGuard)) {
                            return@runOnUiThread
                        }
                        if (
                            countableTmapFailure &&
                            tmapFailureGuard.recordFailure()
                        ) {
                            enterWalkSessionSafetyStopAndCancelOutputs("tmap_consecutive_failures")
                            val safetyStopDetail =
                                "${navigationFailure.kind.userMessage} " +
                                    "TMAP 경로 요청이 두 번 연속 실패해 모든 보행 기능을 중지했습니다. " +
                                    "새 보행을 직접 시작해 주세요."
                            updateStatus(
                                "길안내 안전 중지",
                                safetyStopDetail,
                            )
                            updateNavigationStatus(
                                "navigation=safety_stopped reason=tmap_consecutive_failures " +
                                    "kind=${navigationFailure.kind.statusToken}",
                            )
                            speakInteraction(safetyStopDetail)
                            return@runOnUiThread
                        }
                        if (!preserveExistingRoute) isRouteActive = false
                        latestTmapOnRoute = false
                        if (!preserveExistingRoute) currentDestination = null
                        updateRouteButtonText()
                        updateNavigationStatus(
                            if (preserveExistingRoute) {
                                "navigation=reroute_failed ${navigationFailure.kind.statusToken} existing_route_retained"
                            } else {
                                "navigation=route_failed ${navigationFailure.kind.statusToken}"
                            },
                        )
                        if (preserveExistingRoute) {
                            retainRouteAfterRerouteFailure(navigationFailure.kind.userMessage)
                        } else {
                            speakInteraction(navigationFailure.kind.userMessage)
                        }
                    }
                } finally {
                    completeRouteRequest(routeCall)
                }
            }
        } catch (_: RejectedExecutionException) {
            completeRouteRequest(routeCall)
            routeCall.cancel()
            if (!isRouteRequestLeaseCurrent(expectedWalkEpoch, requestId)) return
            routeRequestInFlight.set(false)
            if (!preserveExistingRoute) isRouteActive = false
            latestTmapOnRoute = false
            if (!preserveExistingRoute) currentDestination = null
            updateRouteButtonText()
            updateNavigationStatus(
                if (preserveExistingRoute) {
                    "navigation=reroute_failed executor_rejected existing_route_retained"
                } else {
                    "navigation=route_failed executor_rejected"
                },
            )
            if (preserveExistingRoute) {
                retainRouteAfterRerouteFailure("TMAP 새 경로를 확인할 수 없어 방향 안내를 중지했습니다.")
            } else {
                speakInteraction("TMAP 경로를 확인할 수 없어 길안내를 시작하지 않았습니다.")
            }
        }
    }

    private fun isRouteRequestLeaseCurrent(
        expectedWalkEpoch: WalkRuntimeEpoch,
        expectedRequestId: Int,
    ): Boolean =
        walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) &&
            expectedRequestId == routeRequestGeneration &&
            (!isWalkSessionRuntimeActive() || walkSafetyOutputsAllowed())

    private fun updateRouteGuidance(location: TrustedLocation) {
        val expectedWalkEpoch = walkSessionLifecycle.currentRuntimeEpochOrNull() ?: return
        if (directionGuidancePauseReason == "tmap_unavailable") {
            latestTmapOnRoute = false
            updateNavigationStatus("navigation=direction_paused reason=tmap_unavailable")
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        val update = routeNavigator.update(
            location = location,
            nowMs = nowMs,
            requestInFlight = routeRequestInFlight.get(),
            stepProgressM = routeStepProgressMOrNull(),
        )
        applyRouteDeviationSafetyUpdate(update)
        if (!update.userDecisionRequired && !update.offRoute && update.reason !in setOf("route_missing", "polyline_missing")) {
            directionGuidancePauseReason = null
        }
        latestTmapOnRoute = isRouteActive &&
            !routeRequestInFlight.get() &&
            update.reason != "route_missing" &&
            update.reason != "polyline_missing" &&
            !update.offRoute &&
            !update.shouldReroute &&
            !update.arrived &&
            !update.userDecisionRequired
        maybePlayProgressBeep(
            nowMs,
            offRoute = update.offRoute || update.userDecisionRequired,
            arrived = update.arrived,
        )
        updateNavigationStatus(
            "navigation=${update.reason} offRoute=${update.offRoute} " +
                "arrived=${update.arrived} decisionRequired=${update.userDecisionRequired}",
        )
        if (update.reason in setOf("route_missing", "polyline_missing")) {
            pauseDirectionGuidance(
                reason = "route_invalid",
                message = "저장된 TMAP 경로를 확인할 수 없어 방향 안내를 중지했습니다.",
            )
        }
        val instruction = update.instruction ?: return
        if (update.userDecisionRequired) return
        if (feedbackPolicy.canSpeakNavigation(nowMs)) {
            val requestGeneration = routeRequestGeneration
            speakNavigation(instruction) {
                runOnUiThread {
                    if (
                        !walkSessionLifecycle.isRuntimeEpochCurrent(expectedWalkEpoch) ||
                        !isWalkSessionRuntimeActive() ||
                        !walkSafetyOutputsAllowed() ||
                        !isRouteActive ||
                        requestGeneration != routeRequestGeneration
                    ) return@runOnUiThread
                    routeNavigator.acknowledgeInstruction(update, SystemClock.elapsedRealtime())
                }
            }
        }
    }

    private fun applyRouteDeviationSafetyUpdate(update: RouteNavigatorUpdate): Boolean {
        if (update.cancelStaleNavigationSpeech) {
            feedbackActuator?.cancelNavigationSpeech()
        }
        updateRouteDeviationActions(update.pendingUserDecision)
        maybePlayRouteDeviationHaptic(update.pendingUserDecision)
        if (!update.userDecisionRequired) {
            lastAnnouncedRouteDecisionToken = null
            return false
        }
        latestTmapOnRoute = false
        directionGuidancePauseReason = update.reason
        val token = routeNavigator.pendingDecisionToken()
        if (token != null && token != lastAnnouncedRouteDecisionToken) {
            lastAnnouncedRouteDecisionToken = token
            update.instruction?.let(::speakInteraction)
        }
        return true
    }

    private fun updateRouteDeviationActions(decision: RouteNavigatorUserDecision?) {
        if (!::routeDeviationActions.isInitialized) return
        val suspected = decision == RouteNavigatorUserDecision.LOCATION_RECHECK
        val confirmed = decision == RouteNavigatorUserDecision.REROUTE
        routeDeviationActions.visibility = if (suspected || confirmed) View.VISIBLE else View.GONE
        routeDeviationActions.contentDescription = when {
            confirmed -> "경로 이탈 확정. 새 경로 요청, 위치 다시 확인, 길안내 종료 중 선택"
            suspected -> "경로 이탈 의심. 위치 다시 확인 선택"
            else -> null
        }
        routeDeviationNewRouteButton.visibility = if (confirmed) View.VISIBLE else View.GONE
        routeDeviationRecheckButton.visibility = if (suspected || confirmed) View.VISIBLE else View.GONE
        routeDeviationEndButton.visibility = if (confirmed) View.VISIBLE else View.GONE
        if (::routeButton.isInitialized) {
            routeButton.visibility = if (suspected || confirmed) View.GONE else View.VISIBLE
        }
        if (::destinationResetButton.isInitialized) {
            destinationResetButton.visibility = if (suspected || confirmed) View.GONE else View.VISIBLE
        }
    }

    private fun routeDeviationChoicePending(): Boolean =
        routeNavigator.pendingUserDecision() in setOf(
            RouteNavigatorUserDecision.LOCATION_RECHECK,
            RouteNavigatorUserDecision.REROUTE,
        )

    private fun blockRouteMutationWhileDeviationChoicePending(): Boolean {
        if (!routeDeviationChoicePending()) return false
        updateNavigationStatus("navigation=route_deviation_choice_required")
        speakInteraction("현재 경로 상태에서 표시된 이탈 선택지를 먼저 골라 주세요.")
        return true
    }

    private fun maybePlayRouteDeviationHaptic(decision: RouteNavigatorUserDecision?) {
        if (decision == routeDeviationHapticDecision) return
        routeDeviationHapticDecision = decision
        when (decision) {
            RouteNavigatorUserDecision.LOCATION_RECHECK ->
                ensureFeedbackActuator().playRouteGuidancePausedVibration()
            RouteNavigatorUserDecision.REROUTE ->
                ensureFeedbackActuator().playRouteDeviationConfirmedVibration()
            else -> Unit
        }
    }

    private fun routeStepProgressMOrNull(): Double? {
        val baseline = routeStartStepCount ?: return null
        if (!isRouteActive || !routeNavigator.hasRoute()) return null
        val deltaSteps = latestStepCount - baseline
        if (deltaSteps < 0) return null
        return deltaSteps * stepLengthEstimator.stepLengthM.toDouble()
    }

    private fun retainRouteAfterRerouteFailure(message: String) {
        routeNavigator.rerouteRequestFailed()?.let(::applyRouteDeviationSafetyUpdate)
        pauseDirectionGuidance(reason = "tmap_unavailable", message = message)
    }

    private fun pauseDirectionGuidance(reason: String, message: String) {
        if (!isRouteActive) return
        latestTmapOnRoute = false
        updateNavigationStatus("navigation=direction_paused reason=$reason")
        if (isRouteActive && directionGuidancePauseReason != reason) {
            directionGuidancePauseReason = reason
            speakInteraction(message)
        }
    }

    private fun updateNavigationStatus(text: String) {
        val state = text.trim().substringBefore(' ').take(FIELD_STATUS_MAX_LENGTH).ifBlank { "navigation=unknown" }
        if (state != latestNavigationState) {
            latestNavigationState = state
            if (::fieldSessionLog.isInitialized) {
                fieldSessionLog.recordEvent("navigation_state_changed", mapOf("state" to state))
            }
        }
        if (::navigationStatusText.isInitialized) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                navigationStatusText.text = text
            } else {
                reportCleanupCallbackHandler.post {
                    if (
                        !privacyStartupInspectionDestroyed &&
                        ::navigationStatusText.isInitialized
                    ) navigationStatusText.text = text
                }
            }
        }
    }

    private fun updateProgressBeepButtons() {
        if (!::progressBeepToggleButton.isInitialized || !::progressBeepVolumeButton.isInitialized) return
        progressBeepToggleButton.text = if (progressBeepEnabled) "진행음 켜짐" else "진행음 꺼짐"
        progressBeepVolumeButton.text = "진행음 볼륨 ${progressBeepVolumePercent}%"
    }

    private fun updateLoginButtonText() {
        if (!::loginSaveButton.isInitialized) return
        val selectedUserId = reporterUserId
        loginSaveButton.text = when {
            selectedUserId == null -> "0. 교육 대상 로그인 ID 확인"
            currentReporterUserId() == null -> "교육 대상 ID 선택됨 · 조건 확인 필요"
            else -> "교육 대상 ID 확인됨"
        }
        loginSaveButton.contentDescription = loginSaveButton.text
        if (::accountLogoutButton.isInitialized) {
            accountLogoutButton.isEnabled =
                selectedUserId != null &&
                    !priorityUserEducationInFlight &&
                    priorityUserPracticeInFlight == null
        }
    }

    private fun updateReportPrivacyConsentUi() {
        if (!::reportPrivacyConsentButton.isInitialized) return
        val state = integratedConsentSession.status(
            IntegratedConsentItem.RAW_SOURCE_COLLECTION,
        )
        reportPrivacyConsentButton.text = when (state) {
            PurposeConsentSyncState.CONFIRMED_GRANTED ->
                "손상 점자블록 신고 데이터 동의 철회"
            PurposeConsentSyncState.GRANT_PENDING ->
                "신고 데이터 동의 서버 확인 중 (아직 활성 아님)"
            PurposeConsentSyncState.WITHDRAWAL_PENDING,
            PurposeConsentSyncState.WITHDRAWAL_RETRY,
            -> "신고 데이터 동의 철회 서버 확인 중"
            PurposeConsentSyncState.FAIL_CLOSED -> "신고 데이터 동의 처리 잠김"
            PurposeConsentSyncState.UNCONFIRMED,
            PurposeConsentSyncState.CONFIRMED_DENIED,
            -> "손상 점자블록 신고 데이터 전송·180일 보관 동의"
        }
        reportPrivacyConsentButton.isEnabled =
            !integratedConsentRequestInFlight &&
                state !in setOf(
                    PurposeConsentSyncState.GRANT_PENDING,
                    PurposeConsentSyncState.WITHDRAWAL_PENDING,
                    PurposeConsentSyncState.WITHDRAWAL_RETRY,
                    PurposeConsentSyncState.FAIL_CLOSED,
                )
    }

    private fun updateAutomaticReportConsentUi() {
        if (!::automaticReportConsentButton.isInitialized) return
        val state = integratedConsentSession.status(
            IntegratedConsentItem.AUTOMATIC_REPORTING,
        )
        automaticReportConsentButton.text = when (state) {
            PurposeConsentSyncState.CONFIRMED_GRANTED -> "자동신고 끄기"
            PurposeConsentSyncState.GRANT_PENDING ->
                "자동신고 서버 확인 중 (아직 활성 아님)"
            PurposeConsentSyncState.WITHDRAWAL_PENDING,
            PurposeConsentSyncState.WITHDRAWAL_RETRY,
            -> "자동신고 끄기 서버 확인 중"
            PurposeConsentSyncState.FAIL_CLOSED -> "자동신고 동의 처리 잠김"
            PurposeConsentSyncState.UNCONFIRMED,
            PurposeConsentSyncState.CONFIRMED_DENIED,
            -> "자동신고 켜기"
        }
        automaticReportConsentButton.isEnabled =
            !integratedConsentRequestInFlight &&
                state !in setOf(
                    PurposeConsentSyncState.GRANT_PENDING,
                    PurposeConsentSyncState.WITHDRAWAL_PENDING,
                    PurposeConsentSyncState.WITHDRAWAL_RETRY,
                    PurposeConsentSyncState.FAIL_CLOSED,
                )
    }

    private fun updateMobileNetworkPreferenceUi() {
        if (!::mobileNetworkPreferenceButton.isInitialized) return
        val state = integratedConsentSession.status(
            IntegratedConsentItem.MOBILE_NETWORK_TRANSFER,
        )
        mobileNetworkPreferenceButton.text =
            when (state) {
                PurposeConsentSyncState.CONFIRMED_GRANTED ->
                    "서버 통신: Wi-Fi + 이동통신"
                PurposeConsentSyncState.GRANT_PENDING ->
                    "서버 통신: Wi-Fi만 · 이동통신 허용 확인 중 (아직 활성 아님)"
                PurposeConsentSyncState.WITHDRAWAL_PENDING,
                PurposeConsentSyncState.WITHDRAWAL_RETRY,
                -> "서버 통신: Wi-Fi만 · 이동통신 철회 확인 중"
                PurposeConsentSyncState.FAIL_CLOSED ->
                    "서버 통신: 개인정보 처리 잠김"
                PurposeConsentSyncState.UNCONFIRMED,
                PurposeConsentSyncState.CONFIRMED_DENIED,
                -> "서버 통신: Wi-Fi만"
            }
        mobileNetworkPreferenceButton.isEnabled =
            !integratedConsentRequestInFlight &&
                state !in setOf(
                    PurposeConsentSyncState.GRANT_PENDING,
                    PurposeConsentSyncState.WITHDRAWAL_PENDING,
                    PurposeConsentSyncState.WITHDRAWAL_RETRY,
                    PurposeConsentSyncState.FAIL_CLOSED,
                )
    }

    private fun updateTrainingReuseConsentUi() {
        if (!::trainingReuseConsentButton.isInitialized) return
        val state = integratedConsentSession.status(
            IntegratedConsentItem.TRAINING_REUSE,
        )
        trainingReuseConsentButton.text = when (state) {
            PurposeConsentSyncState.CONFIRMED_GRANTED -> "학습 재사용 동의 철회"
            PurposeConsentSyncState.GRANT_PENDING ->
                "학습 재사용 동의 서버 확인 중 (아직 활성 아님)"
            PurposeConsentSyncState.WITHDRAWAL_PENDING,
            PurposeConsentSyncState.WITHDRAWAL_RETRY,
            -> "학습 재사용 동의 철회 서버 확인 중"
            PurposeConsentSyncState.FAIL_CLOSED -> "학습 재사용 동의 처리 잠김"
            PurposeConsentSyncState.UNCONFIRMED,
            PurposeConsentSyncState.CONFIRMED_DENIED,
            -> "학습 재사용 동의"
        }
        trainingReuseConsentButton.isEnabled =
            !integratedConsentRequestInFlight &&
                state !in setOf(
                    PurposeConsentSyncState.GRANT_PENDING,
                    PurposeConsentSyncState.WITHDRAWAL_PENDING,
                    PurposeConsentSyncState.WITHDRAWAL_RETRY,
                    PurposeConsentSyncState.FAIL_CLOSED,
                )
    }

    private fun updateIntegratedConsentUi() {
        updateReportPrivacyConsentUi()
        updateAutomaticReportConsentUi()
        updateMobileNetworkPreferenceUi()
        updateTrainingReuseConsentUi()
        if (::privacyConsentStatusText.isInitialized) {
            val summary = IntegratedConsentItem.entries.joinToString(separator = " · ") { item ->
                val label = when (item) {
                    IntegratedConsentItem.RAW_SOURCE_COLLECTION -> "원본 수집"
                    IntegratedConsentItem.AUTOMATIC_REPORTING -> "자동신고"
                    IntegratedConsentItem.MOBILE_NETWORK_TRANSFER -> "이동통신망 전송"
                    IntegratedConsentItem.TRAINING_REUSE -> "학습 재사용"
                }
                val state = when (integratedConsentSession.status(item)) {
                    PurposeConsentSyncState.UNCONFIRMED -> "서버 미확인"
                    PurposeConsentSyncState.CONFIRMED_DENIED -> "거부 확인"
                    PurposeConsentSyncState.CONFIRMED_GRANTED -> "허용 확인"
                    PurposeConsentSyncState.GRANT_PENDING -> "허용 확인 중"
                    PurposeConsentSyncState.WITHDRAWAL_PENDING -> "철회 저장 중"
                    PurposeConsentSyncState.WITHDRAWAL_RETRY -> "철회 재시도 대기"
                    PurposeConsentSyncState.FAIL_CLOSED -> "개인정보 처리 잠김"
                }
                "$label $state"
            }
            privacyConsentStatusText.text = "개인정보 동의 서버 확인 상태\n$summary"
            privacyConsentStatusText.contentDescription = privacyConsentStatusText.text
        }
        if (
            ::firstRunIntegratedConsentDisclosureText.isInitialized &&
            firstRunIntegratedConsentButtons.isNotEmpty()
        ) {
            IntegratedConsentItem.entries.forEach { item ->
                val button = firstRunIntegratedConsentButtons.getValue(item)
                val label = when (item) {
                    IntegratedConsentItem.RAW_SOURCE_COLLECTION -> "원본 수집"
                    IntegratedConsentItem.AUTOMATIC_REPORTING -> "자동신고"
                    IntegratedConsentItem.MOBILE_NETWORK_TRANSFER -> "이동통신망 전송"
                    IntegratedConsentItem.TRAINING_REUSE -> "학습 재사용"
                }
                val selected = integratedConsentDraft.isGranted(item)
                val state = integratedConsentSession.status(item)
                val syncState = when (state) {
                    PurposeConsentSyncState.UNCONFIRMED ->
                        if (selected) {
                            "허용 선택(서버 미확인·아직 활성 아님)"
                        } else {
                            "거부 선택(서버 미확인)"
                        }
                    PurposeConsentSyncState.CONFIRMED_DENIED -> "거부(서버 확인)"
                    PurposeConsentSyncState.CONFIRMED_GRANTED -> "허용(서버 확인)"
                    PurposeConsentSyncState.GRANT_PENDING ->
                        "허용 선택 저장 중(아직 활성 아님)"
                    PurposeConsentSyncState.WITHDRAWAL_PENDING ->
                        "거부 선택 저장 중(철회 확인 전)"
                    PurposeConsentSyncState.WITHDRAWAL_RETRY ->
                        "거부 선택 재시도 대기(철회 확인 전)"
                    PurposeConsentSyncState.FAIL_CLOSED -> "개인정보 처리 잠김"
                }
                button.text = "$label: $syncState"
                button.contentDescription = button.text
                button.isEnabled =
                    !integratedConsentRequestInFlight &&
                        state !in setOf(
                            PurposeConsentSyncState.GRANT_PENDING,
                            PurposeConsentSyncState.WITHDRAWAL_PENDING,
                            PurposeConsentSyncState.WITHDRAWAL_RETRY,
                            PurposeConsentSyncState.FAIL_CLOSED,
                        )
            }
        }
        if (::firstRunIntegratedConsentSaveButton.isInitialized) {
            firstRunIntegratedConsentSaveButton.text =
                if (integratedConsentRequestInFlight) {
                    "서버 저장 확인 중"
                } else {
                    "네 가지 선택을 서버에 저장하고 확인"
                }
            firstRunIntegratedConsentSaveButton.contentDescription =
                firstRunIntegratedConsentSaveButton.text
            firstRunIntegratedConsentSaveButton.isEnabled =
                !integratedConsentRequestInFlight &&
                    firstRunOnboardingSnapshot.stage ==
                    FirstRunOnboardingStage.INTEGRATED_CONSENT
        }
        if (::debugUploadButton.isInitialized) updateDebugUploadButton()
        if (::debugFrameCaptureButton.isInitialized) updateFrameCaptureButton()
    }

    private fun updateAccountDeletionUi() {
        if (!::accountDeletionStatusText.isInitialized) return
        val phase = accountDeletionStateMachine.phase()
        val failClosedReason = accountDeletionStateMachine.failureReasonOrNull()
        val durableConfirmationRecovery =
            accountDeletionStateMachine.durableConfirmationRecoveryRequired()
        val rev0ReauthenticationRequired =
            accountDeletionRev0RecoveryBindingOrNull() != null
        val durableRecoverySessionReady =
            (durableConfirmationRecovery || rev0ReauthenticationRequired) &&
                accountDeletionRecoveryGatewaySessionOrNull() != null
        accountDeletionStatusText.text = when (phase) {
            AccountDeletionPhase.IDLE -> "계정 삭제 요청 없음"
            AccountDeletionPhase.CONFIRM_REQUIRED -> if (durableConfirmationRecovery) {
                if (durableRecoverySessionReady) {
                    "삭제 확인이 안전하게 보존됐습니다. 현재 로그인 계정의 삭제 요청을 다시 확인하세요."
                } else {
                    "삭제 확인이 안전하게 보존됐습니다. 삭제 요청 복구를 위해 현장 게이트웨이 로그인이 필요합니다."
                }
            } else {
                "계정 삭제 범위와 기한을 확인하고 다시 확인하세요."
            }
            AccountDeletionPhase.REQUEST_PENDING ->
                if (rev0ReauthenticationRequired) {
                    if (durableRecoverySessionReady) {
                        "삭제 요청 복구 로그인이 확인됐습니다. 동일 요청을 다시 접수하고 있습니다."
                    } else {
                        "삭제 요청 접수 여부를 확인할 수 없어 삭제 복구 로그인이 필요합니다."
                    }
                } else {
                    "계정 삭제 요청을 서버에 확인 중입니다."
                }
            AccountDeletionPhase.IN_PROGRESS -> "계정과 개인정보 삭제를 처리 중입니다."
            AccountDeletionPhase.RETRY_WAIT ->
                if (rev0ReauthenticationRequired) {
                    "삭제 요청 복구를 위해 현장 게이트웨이에 다시 로그인해야 합니다."
                } else {
                    "삭제 상태 확인을 재시도해야 합니다."
                }
            AccountDeletionPhase.RESTRICTED ->
                "법적 보존 항목이 있어 삭제 완료가 아닙니다."
            AccountDeletionPhase.PARTIAL_FAILURE ->
                "삭제하지 못한 항목이 있어 완료가 아닙니다."
            AccountDeletionPhase.COMPLETED ->
                "모든 삭제 항목의 완료 또는 자료 없음이 확인됐습니다."
            AccountDeletionPhase.FAIL_CLOSED -> if (failClosedReason == null) {
                "삭제 상태를 신뢰할 수 없어 개인정보 기능을 잠갔습니다."
            } else {
                "삭제 상태를 신뢰할 수 없어 개인정보 기능을 잠갔습니다. " +
                    "오류: $failClosedReason"
            }
            AccountDeletionPhase.RESET_PENDING ->
                "새 등록을 위한 로컬 초기화를 완료하지 못했습니다."
            AccountDeletionPhase.REENROLLMENT_REQUIRED ->
                "새 계정과 새 동의로 등록해야 합니다."
        }
        accountDeletionStatusText.contentDescription = accountDeletionStatusText.text
        accountDeletionRequestButton.text =
            if (
                phase == AccountDeletionPhase.COMPLETED &&
                accountDeletionTerminalCleanupComplete
            ) {
                "삭제 완료 확인 후 새 계정 등록 시작"
            } else if (phase == AccountDeletionPhase.COMPLETED) {
                "로컬 삭제 마무리 확인 중"
            } else {
                "계정 삭제 요청"
            }
        accountDeletionRequestButton.contentDescription =
            accountDeletionRequestButton.text
        accountDeletionRequestButton.isEnabled =
            !accountDeletionRequestInFlight &&
                (
                    (
                        phase == AccountDeletionPhase.COMPLETED &&
                            accountDeletionTerminalCleanupComplete
                    ) ||
                        (
                            phase == AccountDeletionPhase.IDLE &&
                                gatewayFieldSession != null
                        )
                )
        val confirming = phase == AccountDeletionPhase.CONFIRM_REQUIRED
        accountDeletionConfirmButton.text =
            if (durableConfirmationRecovery) {
                if (durableRecoverySessionReady) {
                    "현재 로그인 계정 삭제 요청 다시 확인"
                } else {
                    "로그인 후 삭제 요청 준비 다시 시도"
                }
            } else {
                "계정 삭제 확인"
            }
        accountDeletionConfirmButton.visibility =
            if (confirming) View.VISIBLE else View.GONE
        accountDeletionCancelButton.visibility =
            if (confirming && !durableConfirmationRecovery) {
                View.VISIBLE
            } else {
                View.GONE
            }
        accountDeletionConfirmButton.isEnabled = !accountDeletionRequestInFlight
        accountDeletionCancelButton.isEnabled =
            !durableConfirmationRecovery && !accountDeletionRequestInFlight
        val refreshable =
            accountDeletionStateMachine.snapshotOrNull() != null &&
                phase !in setOf(
                    AccountDeletionPhase.COMPLETED,
                    AccountDeletionPhase.FAIL_CLOSED,
                    AccountDeletionPhase.RESET_PENDING,
                    AccountDeletionPhase.REENROLLMENT_REQUIRED,
                )
        accountDeletionRefreshButton.visibility =
            if (refreshable) View.VISIBLE else View.GONE
        accountDeletionRefreshButton.isEnabled =
            refreshable && !accountDeletionRequestInFlight
        val journal = accountDeletionStateMachine.snapshotOrNull()
        DeletionInventoryItem.entries.forEach { item ->
            val view = accountDeletionItemTexts.getValue(item)
            val status = journal?.items?.get(item)
            if (status == null) {
                view.visibility = View.GONE
            } else {
                val stateLabel = when (status.state) {
                    DeletionItemState.PENDING -> "삭제 요청 대기"
                    DeletionItemState.IN_PROGRESS -> "삭제 중"
                    DeletionItemState.EXTERNAL_PENDING -> "외부 시스템 삭제 대기"
                    DeletionItemState.RETRY_WAIT -> "재시도 예정"
                    DeletionItemState.FAILED -> "삭제 실패"
                    DeletionItemState.LEGAL_HOLD -> "법적 보존"
                    DeletionItemState.COMPLETED -> "삭제 완료"
                    DeletionItemState.NOT_APPLICABLE -> "자료 없음 확인"
                }
                val details = buildList {
                    add("${item.labelKo}: $stateLabel")
                    add("기한 ${status.dueAt}")
                    status.reasonCode?.let { add("사유 $it") }
                    status.nextRetryAt?.let { add("예정 $it") }
                    status.contactUrl?.let { add("문의 $it") }
                }.joinToString(", ")
                view.text = details
                view.contentDescription = details
                view.visibility = View.VISIBLE
            }
        }
        if (::backendAuthApplyButton.isInitialized) {
            updateBackendAuthButtonText()
        }
    }

    private fun updateBackendAuthButtonText() {
        if (!::backendAuthApplyButton.isInitialized) return
        val processSnapshot = GatewaySessionProcessCoordinator.snapshot()
        val requestInFlight = processSnapshot.inFlightOperationId != null
        val deletionRecoverySessionReady =
            processSnapshot.deletionRecoveryOnly &&
                accountDeletionRecoveryGatewaySessionOrNull(
                    processSnapshot.session,
                ) != null
        val deletionRecoverySessionReserved =
            processSnapshot.deletionRecoveryOnly &&
                accountDeletionStateMachine.processingBlocked() &&
                !accountDeletionRecoveryLoginRequired()
        val deletionRecoverySurface =
            accountDeletionRecoveryLoginRequired() ||
                processSnapshot.deletionRecoveryOnly
        val surfaceVisible = BuildConfig.DEBUG || deletionRecoverySurface
        backendFieldTokenInput.visibility =
            if (surfaceVisible) View.VISIBLE else View.GONE
        backendAuthApplyButton.visibility =
            if (surfaceVisible) View.VISIBLE else View.GONE
        if (!surfaceVisible) backendFieldTokenInput.text?.clear()
        backendAuthApplyButton.text = when {
            requestInFlight -> "Gateway 처리 중"
            deletionRecoverySessionReady -> "삭제 복구 로그인 완료"
            deletionRecoverySessionReserved -> "계정 삭제 처리 로그인 유지"
            processSnapshot.deletionRecoveryOnly -> "삭제 복구 다시 로그인"
            gatewayFieldSession != null -> "Gateway 로그아웃"
            else -> "Gateway 로그인"
        }
        backendAuthApplyButton.isEnabled =
            surfaceVisible &&
            !requestInFlight &&
                !deletionRecoverySessionReady &&
                !deletionRecoverySessionReserved
        backendFieldTokenInput.isEnabled = backendAuthApplyButton.isEnabled
    }

    private fun nextProgressBeepVolume(current: Int): Int {
        return when {
            current < 20 -> 20
            current < 40 -> 40
            current < 60 -> 60
            else -> 0
        }
    }

    private fun maybePlayProgressBeep(nowMs: Long, offRoute: Boolean, arrived: Boolean) {
        if (!isActivityForeground) return
        if (!isWalkSessionRuntimeActive() || !walkSafetyOutputsAllowed()) return
        if (!progressBeepEnabled || progressBeepVolumePercent <= 0) return
        if (!isRouteActive || offRoute || arrived) return
        if (shouldSuppressFeedbackDuringVoiceRecognition(voiceRecognitionActive, isRisk = false)) return
        if (!feedbackPolicy.canSpeakNavigation(nowMs)) return
        if (nowMs - lastProgressBeepAtMs < PROGRESS_BEEP_INTERVAL_MS) return
        ensureFeedbackActuator().playProgressBeep(progressBeepVolumePercent)
        lastProgressBeepAtMs = nowMs
    }

    private fun updateDebugUploadButton() {
        if (!::debugUploadButton.isInitialized || !::metadataLogUploader.isInitialized) return
        if (!BuildConfig.DEBUG) {
            debugUploadButton.visibility = View.GONE
            debugUploadButton.isEnabled = false
            return
        }
        debugUploadButton.visibility = View.VISIBLE
        debugUploadButton.text = if (metadataLogUploader.isEnabled()) {
            "서버 로그 끄기"
        } else {
            "서버 로그 켜기"
        }
        debugUploadButton.isEnabled = sensitiveDebugTransferAllowed()
    }

    private fun updateFrameCaptureButton() {
        if (!::debugFrameCaptureButton.isInitialized) return
        if (!BuildConfig.DEBUG) {
            debugFrameCaptureButton.visibility = View.GONE
            debugFrameCaptureButton.isEnabled = false
            return
        }
        debugFrameCaptureButton.text = if (frameCaptureRequested.get()) {
            "이미지 캡쳐 대기 중"
        } else {
            "이미지 1장 캡쳐"
        }
        debugFrameCaptureButton.isEnabled =
            !frameCaptureRequested.get() && sensitiveDebugTransferAllowed()
    }

    private fun toggleFieldSessionLog() {
        if (!BuildConfig.DEBUG) return
        if (fieldSessionLog.isActive()) {
            fieldSessionLog.stop()
            if (!isRouteActive && !navigationPermissionsRequestedForRoute && !navigationPermissionsRequestedForReport) {
                stopLocationUpdates()
                stopStepTracking()
            }
        } else {
            if (permissionRecoveryGate.blocksAutomaticResourceStart) {
                updateStatus(
                    "현장 로그 시작 보류",
                    "권한과 필수 기능 전체 상태를 다시 확인하고 명시적으로 재개한 뒤 시작하세요.",
                )
                updatePermissionRecoveryUi()
                return
            }
            if (!isWalkSessionRuntimeActive()) {
                updateStatus("현장 로그 시작 보류", "보행 안내가 활성 상태일 때만 새 현장 로그를 시작할 수 있습니다.")
                return
            }
            val missing = WalkStartPermissionPolicy.missing(
                observed = currentObservedPermissionSnapshot(),
                activityRecognitionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
            )
            if (missing.isNotEmpty()) {
                enterPermissionRecoveryBarrier(missing, "field_session_start")
                return
            }
            GatewayCapacityProcessState.fenceSessionGeneration(
                GatewaySessionProcessCoordinator.snapshot().generation,
            )
            val capacityAdmission = GatewayCapacityProcessState.admission()
            if (!capacityAdmission.newRawCollectionSessionAllowed) {
                updateStatus(
                    "현장 로그 시작 보류",
                    "서버 용량 상태를 확인한 뒤 새 원본 수집 세션을 시작할 수 있습니다.",
                )
                fieldSessionLog.recordEvent(
                    "field_log_start_blocked_gateway_capacity",
                    mapOf(
                        "availability" to capacityAdmission.availability.name,
                        "version" to capacityAdmission.snapshot?.version,
                    ),
                )
                return
            }
            fieldSessionLog.startAfterUserConfirmation()
            fieldSessionLog.recordEvent(
                "field_log_enabled",
                mapOf(
                    "camera_permission" to hasCameraPermission(),
                    "location_permission" to hasLocationPermission(),
                    "activity_permission" to hasActivityRecognitionPermission(),
                    "audio_permission" to hasRecordAudioPermission(),
                ),
            )
            if (startupCapabilityDecision?.tier == WalkSafeStartupCapabilityTier.FULL) {
                ensureNavigationPermissions(requireActivityRecognition = true)
            }
            startNavigationServicesIfNeeded()
        }
        syncActiveSessionScreenPolicy()
        if (::startupCapabilityProbe.isInitialized) {
            refreshStartupCapabilityUi()
        } else {
            updateFieldSessionLogButton()
        }
    }

    /** Active route/risk work keeps the foreground window awake; background collection stays disabled. */
    private fun syncActiveSessionScreenPolicy() {
        if (!::surfaceView.isInitialized) return
        val releaseActive = !BuildConfig.DEBUG && isWalkSessionRuntimeActive()
        if (::controlsScroll.isInitialized && ::walkSafetyOverlay.isInitialized) {
            controlsScroll.visibility = if (releaseActive) View.GONE else View.VISIBLE
            walkSafetyOverlay.visibility = if (releaseActive) View.VISIBLE else View.GONE
            val releasePaused = !BuildConfig.DEBUG &&
                walkSessionLifecycle.snapshot().state == WalkSessionState.PAUSED
            if (releasePaused) {
                controlsScroll.visibility = View.GONE
                walkSafetyOverlay.visibility = View.VISIBLE
            }
        }
        updateWalkSafetySummary()
        val activeForegroundSession = isWalkSessionRuntimeActive() &&
            (session != null || cameraFallbackRunning || isRouteActive || isFieldSessionActive())
        if (activeForegroundSession) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateWalkSafetySummary() {
        if (!::safetySummaryText.isInitialized || !::walkSessionLifecycle.isInitialized) return
        val summary = when (walkSessionLifecycle.snapshot().state) {
            WalkSessionState.ACTIVE -> getString(R.string.walk_safety_active)
            WalkSessionState.PAUSED -> getString(R.string.walk_safety_paused)
            WalkSessionState.SAFE_STOP -> getString(R.string.walk_safety_stopped)
            else -> getString(R.string.walk_safety_preparing)
        }
        if (safetySummaryText.text.toString() == summary) return
        safetySummaryText.text = summary
        safetySummaryText.contentDescription = summary
    }

    private fun isFieldSessionActive(): Boolean {
        return ::fieldSessionLog.isInitialized && fieldSessionLog.isActive()
    }

    private fun updateFieldSessionLogButton() {
        if (!::fieldSessionLogButton.isInitialized) return
        if (!BuildConfig.DEBUG) {
            fieldSessionLogButton.visibility = View.GONE
            fieldSessionLogButton.isEnabled = false
            return
        }
        fieldSessionLogButton.visibility = if (fieldSessionLog.isActive() || isStartupCapabilityConfirmed()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        fieldSessionLogButton.text = if (fieldSessionLog.isActive()) "현장 로그 종료" else "현장 로그 시작"
        fieldSessionLogButton.contentDescription = if (fieldSessionLog.isActive()) {
            "현장 테스트 메타데이터 로그 기록 중, 눌러서 종료"
        } else {
            "현장 테스트 메타데이터 로그 시작"
        }
        fieldSessionLogButton.isEnabled = true
    }

    private fun closeDetectorAsync() {
        val closeableDetector = frameDetector as? Closeable
        if (closeableDetector != null && !detectorExecutor.isShutdown) {
            try {
                detectorExecutor.execute { closeableDetector.close() }
            } catch (_: RejectedExecutionException) {
                closeableDetector.close()
            }
        }
        detectorExecutor.shutdown()
    }

    private fun createExternalCameraTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val textureId = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return textureId
    }

    private fun updateStatus(status: String, detail: String) {
        statusText.text = status
        detailText.text = detail
    }

    private fun kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot.statusTextForNoDetection(): String {
        return when {
            hasMetricRawDepth && detectorAvailable -> "카메라 preview와 Raw Depth를 수신 중입니다. detector 결과가 없으면 객체 안내는 내보내지 않습니다."
            hasMetricRawDepth -> "카메라 preview와 Raw Depth를 수신 중입니다. detector asset 연결 전이라 객체 안내는 대기합니다."
            hasFullDepth -> "카메라 preview와 Full Depth를 수신 중입니다. Raw Depth가 sparse하거나 아직 준비되지 않았습니다."
            depthSupported -> "카메라 preview는 실행 중입니다. ARCore tracking 초기화/feature point 부족으로 depth를 기다리는 중입니다."
            else -> "이 기기는 ARCore DepthMode.AUTOMATIC 미지원으로 metric depth 안내가 제한됩니다."
        }
    }

    private data class OverlayDetectionSelection(
        val detections: List<DetectionCandidate>,
        val mappingSnapshot: DetectionSnapshot,
        val debugState: OverlayDebugState,
    )

    private data class OverlayDebugState(
        val selection: String,
        val detectionCount: Int,
        val tactileDetectionCount: Int,
        val boxCount: Int = 0,
        val tactileBoxCount: Int = 0,
        val heldTactileBoxCount: Int = 0,
        val smoothedTactileBoxCount: Int = 0,
        val holdApplied: Boolean,
        val snapshotPartial: Boolean,
        val sourceAgeMs: Long?,
        val frameDeltaMs: Long?,
        val staleReason: String?,
    ) {
        fun withStabilizer(result: TactileOverlayStabilizer.Result): OverlayDebugState {
            return copy(
                boxCount = result.boxes.size,
                tactileBoxCount = result.tactileBoxCount,
                heldTactileBoxCount = result.heldTactileBoxCount,
                smoothedTactileBoxCount = result.smoothedTactileBoxCount,
                holdApplied = holdApplied || result.heldTactileBoxCount > 0,
            )
        }
    }

    private class TactileOverlayStabilizer {
        private val tracks = mutableListOf<Track>()

        fun update(
            boxes: List<DebugBboxOverlayView.DebugOverlayBox>,
            nowMs: Long,
        ): Result {
            val output = mutableListOf<DebugBboxOverlayView.DebugOverlayBox>()
            val matchedTracks = mutableSetOf<Track>()
            var smoothedCount = 0

            for (box in boxes) {
                if (!box.best && box.isTactileOverlayBox()) {
                    val track = findBestTrack(box)
                    if (track == null) {
                        val newTrack = Track(
                            className = box.className,
                            rectPx = RectF(box.rectPx),
                            label = box.label,
                            lastSeenMs = nowMs,
                        )
                        tracks += newTrack
                        matchedTracks += newTrack
                        output += box
                    } else {
                        val smoothedRect = smoothRect(track.rectPx, box.rectPx)
                        track.rectPx = RectF(smoothedRect)
                        track.label = box.label
                        track.lastSeenMs = nowMs
                        matchedTracks += track
                        smoothedCount += 1
                        output += box.copy(rectPx = smoothedRect, smoothed = true)
                    }
                } else {
                    output += box
                }
            }

            val heldBoxes = tracks
                .filter { it !in matchedTracks }
                .mapNotNull { track ->
                    val ageMs = nowMs - track.lastSeenMs
                    if (ageMs > TACTILE_OVERLAY_VISUAL_HOLD_MS) {
                        null
                    } else {
                        DebugBboxOverlayView.DebugOverlayBox(
                            rectPx = RectF(track.rectPx),
                            label = track.label,
                            best = false,
                            className = track.className,
                            held = true,
                            ageMs = ageMs,
                        )
                    }
                }
            tracks.removeAll { nowMs - it.lastSeenMs > TACTILE_OVERLAY_VISUAL_HOLD_MS }

            val stabilizedBoxes = output + heldBoxes
            return Result(
                boxes = stabilizedBoxes,
                tactileBoxCount = stabilizedBoxes.count { it.isTactileOverlayBox() },
                heldTactileBoxCount = heldBoxes.size,
                smoothedTactileBoxCount = smoothedCount,
            )
        }

        fun clear() {
            tracks.clear()
        }

        private fun DebugBboxOverlayView.DebugOverlayBox.isTactileOverlayBox(): Boolean {
            val name = className ?: label
            return name.lowercase(Locale.US).contains("tactile") || name.contains("점자")
        }

        private fun smoothRect(previous: RectF, current: RectF): RectF {
            val alpha = TACTILE_OVERLAY_SMOOTHING_ALPHA
            val inverse = 1f - alpha
            return RectF(
                previous.left * inverse + current.left * alpha,
                previous.top * inverse + current.top * alpha,
                previous.right * inverse + current.right * alpha,
                previous.bottom * inverse + current.bottom * alpha,
            )
        }

        private fun findBestTrack(box: DebugBboxOverlayView.DebugOverlayBox): Track? {
            return tracks
                .filter { it.className == box.className }
                .maxByOrNull { track -> track.matchScore(box.rectPx) }
                ?.takeIf { it.matchScore(box.rectPx) >= TACTILE_OVERLAY_MIN_MATCH_SCORE }
        }

        private fun Track.matchScore(rect: RectF): Float {
            val iou = rectPx.iou(rect)
            val distance = centerDistance(rectPx, rect)
            val maxSide = maxOf(rectPx.width(), rectPx.height(), rect.width(), rect.height()).coerceAtLeast(1f)
            val distanceScore = (1f - (distance / maxSide)).coerceIn(0f, 1f)
            return maxOf(iou, distanceScore)
        }

        private fun RectF.iou(other: RectF): Float {
            val left = maxOf(left, other.left)
            val top = maxOf(top, other.top)
            val right = minOf(right, other.right)
            val bottom = minOf(bottom, other.bottom)
            val intersection = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
            val union = width() * height() + other.width() * other.height() - intersection
            return if (union <= 0f) 0f else intersection / union
        }

        private fun centerDistance(first: RectF, second: RectF): Float {
            val dx = first.centerX() - second.centerX()
            val dy = first.centerY() - second.centerY()
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        private class Track(
            val className: String?,
            var rectPx: RectF,
            var label: String,
            var lastSeenMs: Long,
        )

        data class Result(
            val boxes: List<DebugBboxOverlayView.DebugOverlayBox>,
            val tactileBoxCount: Int,
            val heldTactileBoxCount: Int,
            val smoothedTactileBoxCount: Int,
        )
    }

    internal data class TactileSnapshotFrameResult(
        val matchedEvidence: DetectionFrameEvidence?,
        val depthDetections: List<DetectionCandidate>,
        val depthProcessingAttempted: Boolean,
        val guidance: TactileRouteGuidanceResult,
    )

    internal interface PreparedTactileFrameDispatch {
        val frame: TactileSnapshotFrameResult

        fun dispatchFeedback(
            stale: Boolean,
            deviceGateAllowsAlerts: Boolean,
            nowMs: Long,
        ): TactileFrameFeedbackDispatch
    }

    private inner class ProductionPreparedTactileFrameDispatch(
        override val frame: TactileSnapshotFrameResult,
    ) : PreparedTactileFrameDispatch {
        override fun dispatchFeedback(
            stale: Boolean,
            deviceGateAllowsAlerts: Boolean,
            nowMs: Long,
        ): TactileFrameFeedbackDispatch = tactileFrameCoordinator.dispatchFeedback(
            frame = frame,
            stale = stale,
            deviceGateAllowsAlerts = deviceGateAllowsAlerts,
            nowMs = nowMs,
        )
    }

    internal data class DetectionFrameEvidence(
        val frameId: Long,
        val frameTimestampMs: Long,
        val depthSnapshot: DepthFrameSnapshot,
        val depthMapper: FrozenImageToTextureCoordinateMapper?,
        val tactileContext: TactileProjectionContext,
        val motionContext: MotionContext,
        val navigationActive: Boolean,
        val tmapOnRoute: Boolean,
    ) {
        fun tactileFrameIdentity(): AndroidTactileEvidenceFrameIdentity = AndroidTactileEvidenceFrameIdentity(
            frameId = frameId,
            frameTimestampMs = frameTimestampMs,
            depthFrameId = depthSnapshot.frameTimestampNs,
            depthMapperFrameId = depthMapper?.frameId,
        )
    }

    internal data class DetectionSnapshot(
        val detections: List<DetectionCandidate>,
        val identity: AndroidDetectionSnapshotFrameIdentity,
        val capturedAtMs: Long?,
        val startedAtMs: Long?,
        val completedAtMs: Long?,
        val imageWidth: Int?,
        val imageHeight: Int?,
        val detectDurationMs: Long?,
        val detectorTiming: AndroidDetectorTiming?,
        val reportImage: ByteArray? = null,
        val partial: Boolean,
        val frameEvidence: DetectionFrameEvidence?,
    ) {
        val captureFrameId: Long? get() = identity.captureFrameId

        val frameTimestampMs: Long? get() = identity.frameTimestampMs

        fun freshDetections(
            nowMs: Long,
            currentFrameTimestampMs: Long,
            maxSourceAgeMs: Long,
            maxFrameDeltaMs: Long,
        ): List<DetectionCandidate> {
            return if (staleReason(nowMs, currentFrameTimestampMs, maxSourceAgeMs, maxFrameDeltaMs) == null) {
                detections
            } else {
                emptyList()
            }
        }

        fun sourceAgeMs(nowMs: Long): Long? = capturedAtMs?.let { nowMs - it }

        fun completedAgeMs(nowMs: Long): Long? = completedAtMs?.let { nowMs - it }

        fun hasTactileDetection(): Boolean {
            return detections.any { it.isTactileDetection() }
        }

        fun tactileDetections(): List<DetectionCandidate> = detections.filter { it.isTactileDetection() }

        fun hasImageSize(): Boolean {
            return imageWidth != null && imageHeight != null && imageWidth > 0 && imageHeight > 0
        }

        fun frameDeltaMs(currentFrameTimestampMs: Long): Long? {
            val frameDelta = rawFrameDeltaMs(currentFrameTimestampMs) ?: return null
            return frameDelta.takeIf { it >= 0L }
        }

        fun ageMs(nowMs: Long): Long? = sourceAgeMs(nowMs)

        fun staleReason(
            nowMs: Long,
            currentFrameTimestampMs: Long,
            maxSourceAgeMs: Long,
            maxFrameDeltaMs: Long,
        ): String? {
            val frameDelta = rawFrameDeltaMs(currentFrameTimestampMs)
            if (frameDelta != null) {
                if (frameDelta < -FRAME_TIMESTAMP_TOLERANCE_MS) return "frame_delta_negative"
                if (frameDelta > maxFrameDeltaMs) return "frame_delta>${maxFrameDeltaMs}ms"
            }
            val sourceAge = sourceAgeMs(nowMs) ?: return null
            return if (sourceAge > maxSourceAgeMs) "source_age>${maxSourceAgeMs}ms" else null
        }

        fun debugStatusText(
            nowMs: Long,
            currentFrameTimestampMs: Long,
            maxSourceAgeMs: Long,
            maxFrameDeltaMs: Long,
        ): String {
            if (completedAtMs == null) {
                return "detector: 아직 완료된 TFLite 결과 없음"
            }
            val sourceAgeMs = sourceAgeMs(nowMs) ?: 0L
            val completedAgeMs = completedAgeMs(nowMs) ?: 0L
            val frameDeltaMs = frameDeltaMs(currentFrameTimestampMs)
            val stale = staleReason(nowMs, currentFrameTimestampMs, maxSourceAgeMs, maxFrameDeltaMs)?.let { " · stale=$it depth입력 제외" } ?: ""
            val partialText = if (partial) "partial" else "complete"
            val top = detections.maxByOrNull { it.detectionConfidence }
            val topText = if (top == null) {
                "top=none"
            } else {
                val bbox = top.bboxNorm
                "top=${top.className} ${formatPercent(top.detectionConfidence)} " +
                    "c=(${formatDecimal(bbox.center.x)},${formatDecimal(bbox.center.y)}) " +
                    "wh=(${formatDecimal(bbox.width)},${formatDecimal(bbox.height)})"
            }
            val duration = detectDurationMs?.let { " · detect=${it}ms" } ?: ""
            val models = detectorTiming?.completedModels?.takeIf { it.isNotEmpty() }?.joinToString("+")
                ?.let { " · models=$it" } ?: ""
            val imageSize = if (imageWidth != null && imageHeight != null) " · image=${imageWidth}x$imageHeight" else ""
            val frameDeltaText = frameDeltaMs?.let { " frameDelta=${it}ms" } ?: ""
            return "detector: ${detections.size}개 $partialText · sourceAge=${sourceAgeMs}ms completedAge=${completedAgeMs}ms$frameDeltaText$duration$models$stale · frameTs=${frameTimestampMs ?: "-"}$imageSize · $topText"
        }

        companion object {
            fun published(
                result: AndroidDetectionResult,
                identity: AndroidDetectionSnapshotFrameIdentity,
                capturedAtMs: Long,
                startedAtMs: Long,
                completedAtMs: Long,
                imageWidth: Int,
                imageHeight: Int,
                reportImageJpeg: ByteArray?,
                frameEvidence: DetectionFrameEvidence,
            ): DetectionSnapshot = DetectionSnapshot(
                detections = result.detections,
                identity = identity,
                capturedAtMs = capturedAtMs,
                startedAtMs = startedAtMs,
                completedAtMs = completedAtMs,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                reportImage = reportImageJpeg,
                detectDurationMs = result.timing.totalMs ?: (completedAtMs - startedAtMs),
                detectorTiming = result.timing,
                partial = result.partial,
                frameEvidence = frameEvidence,
            )

            fun empty(): DetectionSnapshot = DetectionSnapshot(
                detections = emptyList(),
                identity = AndroidDetectionSnapshotFrameIdentity(
                    captureFrameId = null,
                    frameTimestampMs = null,
                ),
                capturedAtMs = null,
                startedAtMs = null,
                completedAtMs = null,
                imageWidth = null,
                imageHeight = null,
                detectDurationMs = null,
                detectorTiming = null,
                reportImage = null,
                partial = false,
                frameEvidence = null,
            )

            private fun formatPercent(value: Float): String = String.format(Locale.US, "%.0f%%", value * 100f)

            private fun formatDecimal(value: Float): String = String.format(Locale.US, "%.2f", value)
        }

        private fun rawFrameDeltaMs(currentFrameTimestampMs: Long): Long? {
            val detectorFrameTimestamp = frameTimestampMs ?: return null
            return currentFrameTimestampMs - detectorFrameTimestamp
        }
    }

    private fun buildCaptureLogEntry(
        frameTimestampMs: Long,
        nowMs: Long,
        detectionSnapshot: DetectionSnapshot,
        overlayDebugState: OverlayDebugState,
        detectionsUsedForDepth: List<DetectionCandidate>,
        snapshot: DepthFrameSnapshot,
        bestOutput: TrackedObjectDepth?,
        depthTransformPath: String,
        depthFallbackReason: String?,
    ): MetadataCaptureLogEntry {
        val top = detectionSnapshot.detections.maxByOrNull { it.detectionConfidence }
        val depthWidth = snapshot.rawDepth?.width ?: snapshot.fullDepth?.width
        val depthHeight = snapshot.rawDepth?.height ?: snapshot.fullDepth?.height
        return MetadataCaptureLogEntry(
            frameTimestampMs = frameTimestampMs,
            detectorFrameTimestampMs = detectionSnapshot.frameTimestampMs,
            detectorAgeMs = detectionSnapshot.ageMs(nowMs),
            detectorSourceAgeMs = detectionSnapshot.sourceAgeMs(nowMs),
            detectorCompletedAgeMs = detectionSnapshot.completedAgeMs(nowMs),
            detectorFrameDeltaMs = detectionSnapshot.frameDeltaMs(frameTimestampMs),
            detectDurationMs = detectionSnapshot.detectDurationMs,
            detectorYuvDecodeMs = detectionSnapshot.detectorTiming?.yuvDecodeMs,
            detectorModelKey = detectionSnapshot.detectorTiming?.modelKey,
            detectorLoadedModelKey = detectorLoadedModelKey,
            detectorModelFallbackUsed = detectorModelFallbackUsed,
            detectorModelLoadReason = detectorLoadReason,
            detectorModelPreprocessMs = detectionSnapshot.detectorTiming?.modelPreprocessMs,
            detectorModelInferenceMs = detectionSnapshot.detectorTiming?.modelInferenceMs,
            detectorModelParseMs = detectionSnapshot.detectorTiming?.modelParseMs,
            detectorCocoPreprocessMs = detectionSnapshot.detectorTiming?.cocoPreprocessMs,
            detectorCocoInferenceMs = detectionSnapshot.detectorTiming?.cocoInferenceMs,
            detectorCocoParseMs = detectionSnapshot.detectorTiming?.cocoParseMs,
            detectorCustomPreprocessMs = detectionSnapshot.detectorTiming?.customPreprocessMs,
            detectorCustomInferenceMs = detectionSnapshot.detectorTiming?.customInferenceMs,
            detectorCustomParseMs = detectionSnapshot.detectorTiming?.customParseMs,
            detectorCompletedModels = detectionSnapshot.detectorTiming?.completedModels ?: emptyList(),
            detectorSkippedModels = detectionSnapshot.detectorTiming?.skippedModels ?: emptyList(),
            detectorPartial = detectionSnapshot.partial,
            detectionCount = detectionSnapshot.detections.size,
            detectionsUsedForDepth = detectionsUsedForDepth.isNotEmpty(),
            overlaySelection = overlayDebugState.selection,
            overlayDetectionCount = overlayDebugState.detectionCount,
            overlayTactileDetectionCount = overlayDebugState.tactileDetectionCount,
            overlayBoxCount = overlayDebugState.boxCount,
            overlayTactileBoxCount = overlayDebugState.tactileBoxCount,
            overlayHeldTactileBoxCount = overlayDebugState.heldTactileBoxCount,
            overlaySmoothedTactileBoxCount = overlayDebugState.smoothedTactileBoxCount,
            overlayHoldApplied = overlayDebugState.holdApplied,
            overlaySnapshotPartial = overlayDebugState.snapshotPartial,
            overlaySourceAgeMs = overlayDebugState.sourceAgeMs,
            overlayFrameDeltaMs = overlayDebugState.frameDeltaMs,
            overlayStaleReason = overlayDebugState.staleReason,
            staleReason = detectionSnapshot.staleReason(
                nowMs = nowMs,
                currentFrameTimestampMs = frameTimestampMs,
                maxSourceAgeMs = MAX_DEPTH_DETECTION_SOURCE_AGE_MS,
                maxFrameDeltaMs = MAX_DEPTH_DETECTION_FRAME_DELTA_MS,
            ),
            topDetectionClassName = top?.className,
            topDetectionConfidence = top?.detectionConfidence,
            topDetectionBbox = top?.bboxNorm?.toMetadataRect(),
            bestDepthClassName = bestOutput?.className,
            bestDepthTrackId = bestOutput?.trackId,
            bestDepthSource = bestOutput?.source?.name,
            bestDepthDetectionConfidence = bestOutput?.detectionConfidence,
            bestDepthConfidenceScore = bestOutput?.confidence?.finalScore,
            bestDepthMedianM = bestOutput?.depthMedianM,
            bestDepthP20M = bestOutput?.depthP20M,
            bestDepthRiskDistanceM = bestOutput?.riskDistanceM,
            bestDepthIqrM = bestOutput?.depthIqrM,
            bestDepthValidSampleCount = bestOutput?.validSampleCount,
            bestDepthValidSampleRatio = bestOutput?.validSampleRatio,
            bestDepthBbox = bestOutput?.bboxNorm?.toMetadataRect(),
            previewWidth = surfaceWidth.takeIf { it > 0 },
            previewHeight = surfaceHeight.takeIf { it > 0 },
            cameraImageWidth = detectionSnapshot.imageWidth,
            cameraImageHeight = detectionSnapshot.imageHeight,
            displayRotation = displayRotation(),
            depthWidth = depthWidth,
            depthHeight = depthHeight,
            overlayTransformPath = "arcore_image_to_view",
            depthTransformPath = depthTransformPath,
            transformPath = depthTransformPath,
            fallbackReason = depthFallbackReason,
        )
    }

    private fun RectNorm.toMetadataRect(): MetadataRect {
        return MetadataRect(x = x, y = y, width = width, height = height)
    }

    private fun kr.co.hanium.dreamup.walksafe.depth.TrackedObjectDepth.debugGeometryText(): String {
        return "bbox: c=(${decimal(centerNorm.x)},${decimal(centerNorm.y)}) " +
            "wh=(${decimal(bboxNorm.width)},${decimal(bboxNorm.height)}) · " +
            "samples=$validSampleCount ratio=${percent(validSampleRatio)} median=${meters(depthMedianM)}"
    }

    private fun percent(value: Float): String = String.format(Locale.US, "%.0f%%", value * 100f)

    private fun decimal(value: Float): String = String.format(Locale.US, "%.2f", value)

    private fun meters(value: Float?): String = value?.let { String.format(Locale.US, "%.2fm", it) } ?: "-"

    private fun String.toReportModelKey(): String? {
        return when (this) {
            "unified_walksafe" -> "unified_walksafe"
            "legacy_two_model",
            "custom_tactile",
            -> "custom_tactile"
            else -> null
        }
    }

    private fun String.toStatusToken(maxLength: Int): String {
        return trim()
            .replace(Regex("\\s+"), "_")
            .take(maxLength)
            .ifBlank { "-" }
    }

    private fun reportPermissionsAllowWork(): Boolean =
        PermissionDependentFeature.REPORT_TRANSMISSION !in
            PermissionDependencyPolicy.evaluate(
                currentObservedPermissionSnapshot(),
            ).stoppedFeatures

    private companion object {
        /** 마지막 컨트롤 아래 확보할 여백. 화면 밀도에 맞춰 px 로 환산한다. */
        /** 실측 대비 기준 디자인 토큰. 흰 글자/회색 면 6.97:1, 테두리 4.08:1. */
        const val WS_COLOR_BUTTON_FILL = 0xff5a595b.toInt()
        const val WS_COLOR_BUTTON_TEXT = 0xffffffff.toInt()
        /** 상시 안전 고지. 카드 위 10.81:1 로 AAA 를 유지하면서 순백보다 한 단계 뒤로 물린다. */
        const val WS_COLOR_NOTICE_TEXT = 0xffc9c6c0.toInt()
        const val WS_COLOR_NOTICE_FILL = 0xff141414.toInt()
        const val WS_COLOR_LINE = 0xff6e6d70.toInt()
        const val WS_COLOR_EMPHASIS = 0xffffe8bd.toInt()
        const val WS_TOUCH_PRIMARY_DP = 56f
        const val WS_CORNER_RADIUS_DP = 10f
        const val FIRST_RUN_VISIBLE_STAGE_COUNT = 3
        const val WS_SECTION_GAP_DP = 24f
        const val WS_CONTROL_GAP_DP = 12f

        const val OVERLAY_BOTTOM_PADDING_DP = 24f

        val PRIVACY_STARTUP_PROCESS_LOCK = Any()
        var accountDeletionStartupResetHandoffPending = false
        const val PERMISSION_REQUEST_CODE_MIN = 3_201
        const val PERMISSION_REQUEST_CODE_MAX = 65_534
        const val RUNTIME_METRIC_STALE_TIMEOUT_MS = 2_000L
        const val RUNTIME_METRIC_MIN_RAW_CONFIDENCE = 0.35
        const val RUNTIME_METRIC_PREFLIGHT_POLICY_VERSION = "WS-RUNTIME-METRIC-PREFLIGHT-1.0.0"
        const val OFFICIAL_ENVIRONMENT_CONFIRM_ACTION_KO =
            "지원범위와 현재 환경 확인: 밝고 비·눈·짙은 안개 없음 · 일반 도심 보도 · 공사·심한 혼잡 없음"
        const val OFFICIAL_ENVIRONMENT_SUPPORT_NOTICE_KO =
            "공식 지원은 비·눈·짙은 안개가 없는 밝은 시간의 일반 도심 보도뿐입니다. " +
                "야간·악천후·공사구간·계단·등산로·차량도로·매우 혼잡한 곳은 지원하지 않습니다. " +
                "횡단보도 정보는 참고용이며 신호와 차량 등 주변 안전을 직접 확인해야 합니다."
        const val PREF_STEP_LENGTH_KEY = "step_length_m"
        const val PREF_PROGRESS_BEEP_ENABLED_KEY = "progress_beep_enabled"
        const val PREF_PROGRESS_BEEP_VOLUME_KEY = "progress_beep_volume_percent"
        const val PREF_REPORTER_USER_ID_KEY = "reporter_user_id"
        const val PREF_REPORT_PRIVACY_CONSENT_KEY = "report_privacy_consent_granted"
        const val PREF_AUTOMATIC_REPORT_CONSENT_KEY = "automatic_report_consent_granted"
        const val PERSISTENT_REPORT_QUEUE_ENABLED = false
        const val PREF_MOBILE_NETWORK_PREFERENCE_KEY = "mobile_network_preference"
        const val PREF_TRAINING_REUSE_CONSENT_KEY = "training_reuse_consent_granted"
        const val PREF_INTEGRATED_CONSENT_POLICY_VERSION =
            "integrated_consent_policy_version"
        const val PREF_INTEGRATED_CONSENT_REVISION =
            "integrated_consent_revision"
        const val PREF_INTEGRATED_CONSENT_CLIENT_REVISION =
            "integrated_consent_client_revision"
        const val PREF_INTEGRATED_CONSENT_RECEIPT_SHA256 =
            "integrated_consent_receipt_sha256"
        const val PREF_INTEGRATED_CONSENT_CONTROL_SECRET =
            "integrated_consent_control_secret"
        const val PREF_PENDING_INTEGRATED_CONSENT_MUTATION =
            "pending_integrated_consent_mutation_v1"
        const val PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION =
            "integrated_consent_server_confirmation_v1"
        const val PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED =
            "local_withdrawal_fail_closed_v1"
        const val PREF_RAW_SOURCE_FIELD_LOG_BLOCKED =
            "raw_source_field_log_blocked_v1"
        const val PENDING_CONSENT_MUTATION_SCHEMA_VERSION =
            "walksafe.pending-integrated-consent-mutation.v1"
        const val PREF_ACCOUNT_DELETION_JOURNAL =
            "account_deletion_journal_v2"
        const val PREF_ACCOUNT_DELETION_ACTOR_HASH =
            "account_deletion_actor_hash_v2"
        const val PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER =
            "account_deletion_fail_closed_v1"
        const val PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON =
            "account_deletion_fail_closed_reason_v1"
        const val PREF_ACCOUNT_DELETION_INTENT_FENCE =
            "account_deletion_intent_fence_v1"
        const val ACCOUNT_DELETION_JOURNAL_SCHEMA_VERSION =
            "walksafe.account-deletion-journal.v2"
        const val PREF_ACCOUNT_DELETION_RESET_REQUIRED =
            "account_deletion_reset_required_v1"
        const val PREF_ACCOUNT_DELETION_COMPLETION_RECEIPT =
            "account_deletion_completion_receipt_v1"
        val INTEGRATED_CONSENT_RECEIPT_SHA256 = Regex("^[0-9a-f]{64}$")
        val INTEGRATED_CONSENT_CONTROL_SECRET = Regex("^[0-9a-f]{64}$")
        val ACCOUNT_DELETION_FAIL_CLOSED_REASON =
            Regex("[a-z0-9](?:[a-z0-9_:-]{0,95})")
        const val PREF_GATEWAY_ORIGIN_KEY = "gateway_origin"
        const val PREF_REPORT_COOLDOWNS_KEY = "report_successful_cooldowns_v1"
        const val PREF_REPORT_ATTEMPT_STATE_V2 = "report_attempt_state_v2"
        const val REPORT_ATTEMPT_STATE_SCHEMA_V2 =
            "walksafe.report-attempt-state.v2"
        val REPORT_ATTEMPT_STATE_KEY = Regex("^[0-9a-f]{64}$")
        const val REPORT_ATTEMPT_STATE_MAX_ENTRIES = 64
        const val REPORT_ATTEMPT_MAX_FAILURES = 32
        const val REPORT_TERMINAL_STATE_TTL_MS = 24L * 60L * 60L * 1_000L
        const val LEGACY_REPORT_CLEANUP_MAX_ATTEMPTS = 3
        const val LEGACY_REPORT_CLEANUP_INITIAL_BACKOFF_MS = 250L
        const val PREF_WALK_SESSION_INTERRUPTED = "walk_session_interrupted"
        const val PREF_PERMISSION_RECOVERY_GATE_STATE =
            "permission_recovery_gate_state_v1"
        const val PREF_PERMISSION_RECOVERY_GATE_ITEMS =
            "permission_recovery_gate_items_v1"
        const val PRIORITY_USER_PROFILE_STORAGE_VERSION = 2
        const val PREF_PRIORITY_USER_PROFILE_PREFIX = "priority_user_snapshot_v2_"
        const val PREF_PRIORITY_USER_PROFILE_INVALID_PREFIX =
            "priority_user_snapshot_invalid_v2_"
        const val LEGACY_PREF_PRIORITY_USER_POLICY_VERSION = "priority_user_policy_version"
        const val LEGACY_PREF_PRIORITY_USER_AGE_BAND = "priority_user_age_band"
        const val LEGACY_PREF_PRIORITY_USER_GUARDIAN_VERIFIED =
            "priority_user_guardian_verified"
        const val LEGACY_PREF_PRIORITY_USER_EDUCATION_REVIEWED =
            "priority_user_education_reviewed"
        const val LEGACY_PREF_PRIORITY_USER_SAFE_PLACE_CONFIRMED =
            "priority_user_safe_place_confirmed"
        const val LEGACY_PREF_PRIORITY_USER_COMPLETED_PRACTICES =
            "priority_user_completed_practices"
        const val PREF_SENSITIVE_SNAPSHOT = "sensitive_preferences_encrypted_v1"
        const val PREF_SENSITIVE_SNAPSHOT_BLOCKED =
            "sensitive_preferences_fail_closed_v1"
        const val PREF_SENSITIVE_RESET_PENDING =
            "sensitive_preferences_key_reset_pending_v1"
        val SENSITIVE_PREF_SPEC = SensitivePreferenceSpec(
            storageKey = PREF_SENSITIVE_SNAPSHOT,
            blockedKey = PREF_SENSITIVE_SNAPSHOT_BLOCKED,
            resetPendingKey = PREF_SENSITIVE_RESET_PENDING,
            domainAad =
                "kr.co.hanium.dreamup.walksafe|USER|prefs|sensitive-snapshot|schema=1"
                    .toByteArray(Charsets.UTF_8),
            keyPolicy = AeadKeyPolicy(
                aliasPrefix = "walksafe.user.sensitive_preferences.aead.v",
                currentVersion = 1,
                readableVersions = setOf(1),
            ),
            exactKeys = setOf(
                PREF_REPORTER_USER_ID_KEY,
                PREF_REPORT_PRIVACY_CONSENT_KEY,
                PREF_AUTOMATIC_REPORT_CONSENT_KEY,
                PREF_MOBILE_NETWORK_PREFERENCE_KEY,
                PREF_TRAINING_REUSE_CONSENT_KEY,
                PREF_INTEGRATED_CONSENT_POLICY_VERSION,
                PREF_INTEGRATED_CONSENT_REVISION,
                PREF_INTEGRATED_CONSENT_CLIENT_REVISION,
                PREF_INTEGRATED_CONSENT_RECEIPT_SHA256,
                PREF_INTEGRATED_CONSENT_CONTROL_SECRET,
                PREF_PENDING_INTEGRATED_CONSENT_MUTATION,
                PREF_INTEGRATED_CONSENT_SERVER_CONFIRMATION,
                PREF_LOCAL_WITHDRAWAL_FAIL_CLOSED,
                PREF_ACCOUNT_DELETION_JOURNAL,
                PREF_ACCOUNT_DELETION_ACTOR_HASH,
                PREF_ACCOUNT_DELETION_FAIL_CLOSED_MARKER,
                PREF_ACCOUNT_DELETION_FAIL_CLOSED_REASON,
                PREF_ACCOUNT_DELETION_COMPLETION_RECEIPT,
                PREF_REPORT_COOLDOWNS_KEY,
                PREF_REPORT_ATTEMPT_STATE_V2,
                LEGACY_PREF_PRIORITY_USER_POLICY_VERSION,
                LEGACY_PREF_PRIORITY_USER_AGE_BAND,
                LEGACY_PREF_PRIORITY_USER_GUARDIAN_VERIFIED,
                LEGACY_PREF_PRIORITY_USER_EDUCATION_REVIEWED,
                LEGACY_PREF_PRIORITY_USER_SAFE_PLACE_CONFIRMED,
                LEGACY_PREF_PRIORITY_USER_COMPLETED_PRACTICES,
            ),
            keyPrefixes = setOf(
                PREF_PRIORITY_USER_PROFILE_PREFIX,
                PREF_PRIORITY_USER_PROFILE_INVALID_PREFIX,
            ),
        )
        const val DEFAULT_PROGRESS_BEEP_VOLUME_PERCENT = 20
        const val PROGRESS_BEEP_INTERVAL_MS = 3_000L
        const val MIN_STEP_CALIBRATION_STEPS = 8
        const val MIN_STEP_CALIBRATION_DISTANCE_M = 4.0f
        const val MIN_STEP_CALIBRATION_DURATION_MS = 8_000L
        const val MAX_STEP_CALIBRATION_GAP_MS = 45_000L
        const val DESTINATION_SEARCH_PAGE_SIZE = 3
        const val DESTINATION_SEARCH_MAX_RESULTS = 10
        const val UI_UPDATE_INTERVAL_MS = 500L
        const val TALKBACK_RISK_DUP_WINDOW_MS = 1_000L
        const val TALKBACK_INTERACTION_DUP_WINDOW_MS = 1_000L
        const val TALKBACK_INTERACTION_PRIORITY_WINDOW_MS = 750L
        const val TALKBACK_NAVIGATION_DUP_WINDOW_MS = 2_000L
        const val TALKBACK_ADVISORY_DUP_WINDOW_MS = 4_000L
        const val OVERLAY_UPDATE_INTERVAL_MS = 100L
        const val DETECTION_INTERVAL_MS = 250L
        const val CAMERA_FALLBACK_ANALYSIS_INTERVAL_MS = 500L
        const val CAMERA_FALLBACK_IMU_MAX_AGE_MS = 1_500L
        const val CAMERA_FALLBACK_WIDTH = 640
        const val CAMERA_FALLBACK_HEIGHT = 480
        const val NANOS_PER_MILLISECOND = 1_000_000L
        const val LOCATION_UPDATE_INTERVAL_MS = 1_500L
        const val LOCATION_FASTEST_INTERVAL_MS = 700L
        const val GATEWAY_WALK_RENEW_INTERVAL_MS = 30_000L
        const val FEEDBACK_MIN_DEPTH_CONFIDENCE = 0.55f
        const val MAX_OVERLAY_DETECTION_SOURCE_AGE_MS = 1_200L
        const val MAX_DEPTH_DETECTION_SOURCE_AGE_MS = 800L
        const val MAX_OVERLAY_DETECTION_FRAME_DELTA_MS = 1_200L
        const val MAX_DEPTH_DETECTION_FRAME_DELTA_MS = 800L
        const val MAX_OVERLAY_HOLD_SOURCE_AGE_MS = 1_500L
        const val MAX_OVERLAY_HOLD_FRAME_DELTA_MS = 1_500L
        const val MAX_TACTILE_OVERLAY_SOURCE_AGE_MS = 2_300L
        const val MAX_TACTILE_OVERLAY_FRAME_DELTA_MS = 2_300L
        const val MAX_TACTILE_OVERLAY_HOLD_SOURCE_AGE_MS = 3_000L
        const val MAX_TACTILE_OVERLAY_HOLD_FRAME_DELTA_MS = 3_000L
        const val TACTILE_OVERLAY_VISUAL_HOLD_MS = 700L
        const val TACTILE_OVERLAY_SMOOTHING_ALPHA = 0.65f
        const val TACTILE_OVERLAY_MIN_MATCH_SCORE = 0.20f
        const val FRAME_TIMESTAMP_TOLERANCE_MS = 100L
        const val MAX_OVERLAY_DETECTION_BOXES = 5
        const val MAX_FRAME_CAPTURE_DETECTIONS = 20
        const val DEBUG_FRAME_CAPTURE_MAX_DIMENSION = 640
        const val DEBUG_FRAME_CAPTURE_JPEG_QUALITY = 90
        const val FIELD_STATUS_MAX_LENGTH = 96
        const val REPORT_FRAME_CAPTURE_MAX_DIMENSION = 320
        const val REPORT_FRAME_CAPTURE_JPEG_QUALITY = 80
    }

    private enum class ActionMode {
        START,
        OPEN_SETTINGS,
    }

    private enum class VoiceRecognitionPurpose {
        COMMAND,
        WALK_SESSION_RESUME,
        WALK_SESSION_TAKEOVER,
    }

    private enum class PermissionRequestPurpose {
        METRIC_PREFLIGHT_CAMERA,
        WALK_SESSION,
        RUNTIME_CAMERA,
        NAVIGATION,
        VOICE_COMMAND,
    }

    private data class PermissionRequestLease(
        val purpose: PermissionRequestPurpose,
        val epoch: WalkRuntimeEpoch,
        val generation: Long,
        val firstRunLease: FirstRunAsyncLease,
        val requestedPermissions: Set<ObservedPermission>,
    )

    private data class FirstRunAsyncLease(
        val epoch: Long,
        val revision: Long,
        val stage: FirstRunOnboardingStage,
    )

    private enum class ArSessionPurpose {
        NONE,
        PREFLIGHT,
        RUNTIME,
    }

    private data class ArSessionLease(
        val session: Session,
        val provider: ArCoreFrameProvider,
        val purpose: ArSessionPurpose,
        val generation: Long,
    )

    private data class RuntimeMetricInvalidation(
        val wasPreflight: Boolean,
        val wasRuntime: Boolean,
        val hadAvailableEvidence: Boolean,
        val evaluator: RuntimeMetricPreflightSession?,
        val sessionToClose: Session?,
    )

    private enum class CameraFallbackStartReason(val fieldValue: String) {
        CAPABILITY_DISTANCE_UNAVAILABLE("capability_distance_unavailable"),
        ARCORE_AVAILABILITY_UNSUPPORTED("arcore_availability_unsupported"),
        ARCORE_DEPTH_UNSUPPORTED("arcore_depth_unsupported"),
        ARCORE_SESSION_INCOMPATIBLE("arcore_session_incompatible"),
        DEBUG_FORCED_SUPPORTED("debug_forced_supported"),
    }

    private data class PendingCameraFallbackStart(
        val reason: CameraFallbackStartReason,
        val availability: ArCoreApk.Availability,
    )

    private enum class TalkBackAnnouncementPriority {
        RISK,
        INTERACTION,
        NAVIGATION,
        ADVISORY,
    }

    private data class GatewayFailureUiGuard(
        val generation: Long,
        val expectedAuthenticationFailure: Boolean,
    )

    private data class FeedbackDeliveryState(
        val activeFeedbackDeliveryKeys: Set<String> = emptySet(),
        val deviceGateAllowsAlerts: Boolean = false,
        val observedAtMs: Long = 0L,
    )

    private data class PendingFeedbackTerminalResolution(
        val action: FeedbackAction,
        val policyEvaluatedAtMs: Long,
        val runnable: Runnable,
    )

}

private class CameraFallbackLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = registry

    fun moveTo(state: Lifecycle.State) {
        registry.currentState = state
    }
}

/**
 * Immutable adapter between detector image coordinates and the depth texture copied from the same
 * ARCore frame. No Frame reference crosses the capture boundary.
 */
internal class FrozenImageToTextureCoordinateMapper(
    val frameId: Long,
    private val transform: FrozenImageToDepthTransform,
    private val depthSize: ImageSize,
) : CoordinateMapper {
    override fun modelToImage(point: Point2): Point2 = normalize(point)

    override fun imageToModel(point: Point2): Point2 = normalize(point)

    override fun imageToDepth(point: Point2): Point2? {
        if (frameId != transform.frameId || depthSize.width <= 0 || depthSize.height <= 0) return null
        return transform.map(point)
    }

    override fun depthToImage(point: Point2): Point2? = null

    override fun imagePolygonToDepthPolygon(polygon: List<Point2>): List<Point2> {
        return polygon.map { point ->
            imageToDepth(point) ?: return emptyList()
        }
    }

    override fun depthPixelToCameraPoint(x: Int, y: Int, zM: Float, intrinsics: CameraIntrinsics): Vec3? {
        return kr.co.hanium.dreamup.walksafe.depth.depthPixelToCameraPoint(x, y, zM, intrinsics)
    }

    private fun normalize(point: Point2): Point2 {
        return Point2(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
    }
}
