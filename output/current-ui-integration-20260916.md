# Current + accessible UI local integration

- Base: origin/current `2f50e29` (fetched 2026-09-16).
- Preserved UI checkpoint: `ffef6db` on `feature/accessible-guidance-report-ui-20260915`.
- Integration branch: `integration/current-accessible-ui-20260916`.
- MainActivity conflicts resolved by combining dynamic home-card layout with current compass/status heights, and retaining current destination voice cleanup while opening the dedicated guidance screen.
- Current route heading, segmented guidance, camera/depth changes retained. RouteNavigator adds only the UI remaining-distance accessor compared with current.
- Includes four large guidance controls, black touch guard with continuous 3-second release, full-screen help/start confirmation, km formatting, bounded voice confirmation retry, and prior station-search correction.

## Validation

- Android assembleDebug: PASS with guidanceStartBypass=false and postLoginDeviceCheckRequired=true.
- Integrated Android unit tests: 3457 total, 3423 passed, 26 failed, 8 skipped.
- Original current in detached worktree: 3427 total, 3393 passed, 26 failed, 8 skipped.
- Failing test identifiers are identical between both runs; this is not a fully passing suite or proof of field safety.
- Five stale static UI assertions updated to check the current transcript visibility, contextual home voice pages, microphone recovery action, preparation diagnostics, and retained readiness gates. Added hidden-transcript accessibility assertion.
- Baseline harness used the same verified macOS aapt2 dependency checksum; baseline product/test source was unchanged.
- git diff --check: PASS.
- Backend station-search tests and physical-device/TalkBack/field checks were not rerun in this integration.
- Historical images in output describe their respective revisions and are not fresh screenshots of this integration.
- No push, server deployment, or phone installation performed.

## Remaining failing test identifiers (also fail on original current)

kr.co.hanium.dreamup.walksafe.MainActivityAccessibilityStaticTest.safetyCooldownsUseMonotonicTimeInsteadOfWallClock
kr.co.hanium.dreamup.walksafe.MainActivityDeviceCheckFeatureIsolationStaticTest.activeFeatureChangesRebindConfirmationAndNoCameraDevicesRemainInstallable
kr.co.hanium.dreamup.walksafe.MainActivityDeviceCheckRecoveryContractStaticTest.restoredFullOrLimitedResultAdvancesOnboardingWithoutRemeasurement
kr.co.hanium.dreamup.walksafe.MainActivityFp016StaticTest.activeAndPausedScreensUseTheSingleScrollableProductSurfaceInEveryBuild
kr.co.hanium.dreamup.walksafe.MainActivityInitialPermissionStaticTest.firstEntryRequestsEveryMissingRuntimePermissionInOneBatchOnlyOnce
kr.co.hanium.dreamup.walksafe.MainActivityInitialPermissionStaticTest.initialDenialIsRememberedAndRequiresExitWhenAnyRequiredPermissionIsMissing
kr.co.hanium.dreamup.walksafe.MainActivityNavigationCompositionTest.voicePagingIsThreeAtATimeAndOnlySelectsFromTheCurrentPage
kr.co.hanium.dreamup.walksafe.MainActivityPostLoginDeviceCheckStaticTest.cameraPipelineUsesBoundedDetectorAttemptsWithoutWalkOutputs
kr.co.hanium.dreamup.walksafe.MainActivityVoiceIntegrationStaticTest.candidateFollowUpKeepsDestinationResultsAndRetryControlsVisible
kr.co.hanium.dreamup.walksafe.MainActivityWalkScreenPortStaticTest.guidanceHasOneStatusWriterAndEntersBeforePreflight
kr.co.hanium.dreamup.walksafe.PermissionSessionLifecycleStaticTest.automaticRecoveryCallbacksAndRestoreCannotClearOrBypassTheGate
kr.co.hanium.dreamup.walksafe.RuntimeMetricMainActivityStaticTest.runtimeMetricEvidenceFailsClosedBeforeDetectorReportFeedbackAndQueuedDelivery
kr.co.hanium.dreamup.walksafe.navigation.ArCoreTactileProjectionContextFactoryTest.compiledDrawAndDetectionChainPassesSessionFrameIntoFrameEvidence
kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStoreTest.fileStorageCreatesZeroByteLockAndRejectsTampering
kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStoreTest.fileStorageDoesNotSealNewConsentUntilPurgeKeyLifecycleCompletes
kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStoreTest.fileStorageHoldsOsLockWhileOpeningQueuedPlaintext
kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStoreTest.fileStorageKeepsGlobalBarrierAfterKeyFailureUntilRetryCompletes
kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStoreTest.fileStorageKeepsWriteAndPurgeInOneLifecycleCriticalSection
kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStoreTest.fileStoragePersistsEveryAutomaticReceiptFenceAndClearsPendingAfterDelete
kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStoreTest.fileStorageRecoversPendingAccountDeletionBeforeConsentPurge
kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStoreTest.fileStorageSerializesCreateOnlyWriteAndFenceCheckAcrossInstances
kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStoreTest.fileStorageSerializesFinalByteAdmissionAcrossInstances
kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStoreTest.fileStorageSerializesFinalCapacityAdmissionAcrossInstances
kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStoreTest.fileStorageUsesFinalAtomicNamesAndScopedDeletion
kr.co.hanium.dreamup.walksafe.report.AndroidReportQueueStoreTest.unrecognizedRegularFilesConsumeCapacityAndUnsafeEntriesFailClosed
kr.co.hanium.dreamup.walksafe.report.ReportPrivacyConsentSessionTest.mainActivityPauseInvalidatesPrivateFrameStateAndInFlightGenerationWithoutReplacingDetector
