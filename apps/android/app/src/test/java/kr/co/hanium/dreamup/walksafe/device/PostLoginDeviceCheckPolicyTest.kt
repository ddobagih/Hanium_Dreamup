package kr.co.hanium.dreamup.walksafe.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostLoginDeviceCheckPolicyTest {
    @Test
    fun loginIsRequiredAndBackgroundCannotStartAnAttempt() {
        val initial = PostLoginDeviceCheckPolicy.initial()

        val withoutLogin = PostLoginDeviceCheckPolicy.beginFromUserAction(
            initial,
            ACTOR,
            SESSION_GENERATION,
            foreground = true,
        )
        val bound = boundSnapshot()
        val backgrounded = PostLoginDeviceCheckPolicy.beginFromUserAction(
            bound,
            ACTOR,
            SESSION_GENERATION,
            foreground = false,
        )

        assertEquals(PostLoginDeviceCheckState.FAIL, withoutLogin.state)
        assertEquals(PostLoginDeviceCheckFailure.SESSION_CHANGED, withoutLogin.failure)
        assertEquals(0L, withoutLogin.attemptGeneration)
        assertFalse(withoutLogin.passesFeatureGate)
        assertEquals(PostLoginDeviceCheckState.FAIL, backgrounded.state)
        assertEquals(PostLoginDeviceCheckFailure.BACKGROUNDED, backgrounded.failure)
        assertEquals(0L, backgrounded.attemptGeneration)
        assertFalse(backgrounded.passesFeatureGate)
    }

    @Test
    fun onlyUserActionStartsAnAttemptAndStableMetricDepthYieldsFull() {
        val bound = boundSnapshot()

        assertEquals(PostLoginDeviceCheckState.NOT_RUN, bound.state)
        val requesting = PostLoginDeviceCheckPolicy.beginFromUserAction(
            bound,
            ACTOR,
            SESSION_GENERATION,
            foreground = true,
        )
        val binding = requireNotNull(requesting.bindingOrNull)
        val running = PostLoginDeviceCheckPolicy.beginRunning(
            requesting,
            binding,
            foreground = true,
        )
        val full = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(
                metricDepth = PostLoginMetricDepthState.AVAILABLE,
            ),
        )

        assertEquals(PostLoginDeviceCheckState.FULL, full.state)
        assertTrue(full.passesFeatureGate)
        assertTrue(full.disabledFeatures.isEmpty())
    }

    @Test
    fun unsupportedFeatureSetYieldsLimitedWithTheExactDisabledFeatures() {
        val (running, binding) = runningAttempt()
        val unsupported = setOf(
            PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE,
            PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK,
        )

        val limited = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(
                metricDepth = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED,
                unsupportedFeatures = unsupported,
            ),
        )

        assertEquals(PostLoginDeviceCheckState.LIMITED, limited.state)
        assertTrue(limited.passesFeatureGate)
        assertEquals(unsupported, limited.disabledFeatures)
        assertEquals(null, limited.failure)
    }

    @Test
    fun deniedRuntimePermissionDoesNotPersistAFeatureLimitOrFailTheHardwareCheck() {
        val (running, binding) = runningAttempt()

        val full = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(
                metricDepth = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED,
                cameraPipeline = PostLoginDeviceCheckSignal.UNAVAILABLE,
            ).copy(requiredPermissions = PostLoginDeviceCheckSignal.UNAVAILABLE),
        )

        assertEquals(PostLoginDeviceCheckState.FULL, full.state)
        assertTrue(full.disabledFeatures.isEmpty())
        assertEquals(null, full.failure)
        assertTrue(full.passesFeatureGate)
    }

    @Test
    fun blockingFailuresNeverPassAndNeverPublishDisabledFeatures() {
        listOf(
            PostLoginDeviceCheckFailure.MINIMUM_ANDROID_VERSION,
            PostLoginDeviceCheckFailure.DEPTH_UNKNOWN,
            PostLoginDeviceCheckFailure.DEPTH_TIMEOUT,
            PostLoginDeviceCheckFailure.CAMERA_PIPELINE_UNAVAILABLE,
        ).forEach { expectedFailure ->
            val (running, binding) = runningAttempt()

            val failed = PostLoginDeviceCheckPolicy.evaluate(
                running,
                binding,
                foreground = true,
                observation = readyObservation(
                    unsupportedFeatures = setOf(
                        PostLoginDeviceCheckFeature.OBSTACLE_DETECTION,
                    ),
                    blockingFailure = expectedFailure,
                ),
            )

            assertEquals(PostLoginDeviceCheckState.FAIL, failed.state)
            assertEquals(expectedFailure, failed.failure)
            assertFalse(failed.passesFeatureGate)
            assertTrue(failed.disabledFeatures.isEmpty())
        }
    }

    @Test
    fun everySupportedFeatureCanBeDisabledWithoutFailingTheWholeCheck() {
        PostLoginDeviceCheckFeature.entries.forEach { feature ->
            val (running, binding) = runningAttempt()
            val limited = PostLoginDeviceCheckPolicy.evaluate(
                running,
                binding,
                foreground = true,
                observation = readyObservation(unsupportedFeatures = setOf(feature)),
            )

            assertEquals(PostLoginDeviceCheckState.LIMITED, limited.state)
            assertTrue(limited.passesFeatureGate)
            assertEquals(setOf(feature), limited.disabledFeatures)
            assertEquals(null, limited.failure)
        }
    }

    @Test
    fun pendingSignalsRemainRunning() {
        val (running, binding) = runningAttempt()

        val pending = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation().copy(
                deviceResources = PostLoginDeviceCheckSignal.PENDING,
            ),
        )

        assertEquals(PostLoginDeviceCheckState.RUNNING, pending.state)
        assertFalse(pending.passesFeatureGate)
    }

    @Test
    fun everyAutomaticProbeMustFinishBeforeTheCheckBecomesTerminal() {
        listOf(
            readyObservation().copy(minimumAndroidVersion = PostLoginDeviceCheckSignal.PENDING),
            readyObservation().copy(requiredPermissions = PostLoginDeviceCheckSignal.PENDING),
            readyObservation().copy(voiceDisclosure = PostLoginDeviceCheckSignal.PENDING),
            readyObservation().copy(coreHardwareAndServices = PostLoginDeviceCheckSignal.PENDING),
            readyObservation().copy(locationService = PostLoginDeviceCheckSignal.PENDING),
            readyObservation().copy(deviceResources = PostLoginDeviceCheckSignal.PENDING),
            readyObservation().copy(detector = PostLoginDeviceCheckSignal.PENDING),
            readyObservation().copy(cameraPipeline = PostLoginDeviceCheckSignal.PENDING),
            readyObservation().copy(koreanTextToSpeech = PostLoginDeviceCheckSignal.PENDING),
            readyObservation(metricDepth = PostLoginMetricDepthState.PENDING),
        ).forEach { observation ->
            val (running, binding) = runningAttempt()

            val result = PostLoginDeviceCheckPolicy.evaluate(
                running,
                binding,
                foreground = true,
                observation = observation,
            )

            assertEquals(PostLoginDeviceCheckState.RUNNING, result.state)
            assertFalse(result.passesFeatureGate)
        }
    }

    @Test
    fun locationFixWakePhraseAndHapticConfirmationAreNotBlockingGates() {
        val (running, binding) = runningAttempt()
        val unsupported = setOf(
            PostLoginDeviceCheckFeature.LOCATION_GUIDANCE,
            PostLoginDeviceCheckFeature.HANDS_FREE_VOICE,
            PostLoginDeviceCheckFeature.HAPTIC_FEEDBACK,
        )

        val limited = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(unsupportedFeatures = unsupported).copy(
                locationFix = PostLoginDeviceCheckSignal.PENDING,
                wakePhraseRecognition = PostLoginDeviceCheckSignal.PENDING,
                hapticFeedback = PostLoginDeviceCheckSignal.PENDING,
            ),
        )

        assertEquals(PostLoginDeviceCheckState.LIMITED, limited.state)
        assertEquals(unsupported, limited.disabledFeatures)
        assertTrue(limited.passesFeatureGate)
    }

    @Test
    fun overallTimeoutFailsOnlyTheCurrentForegroundRunningAttempt() {
        val (running, binding) = runningAttempt()

        val timedOut = PostLoginDeviceCheckPolicy.timeout(running, binding, foreground = true)
        val stale = PostLoginDeviceCheckPolicy.timeout(running, binding.copy(attemptGeneration = 99L), true)

        assertEquals(PostLoginDeviceCheckState.FAIL, timedOut.state)
        assertEquals(PostLoginDeviceCheckFailure.CHECK_TIMEOUT, timedOut.failure)
        assertEquals(running, stale)
    }

    @Test
    fun duplicateUserActionsDoNotRestartActiveOrPassedAttemptsAndFailCanRetry() {
        val bound = boundSnapshot()
        val requesting = PostLoginDeviceCheckPolicy.beginFromUserAction(
            bound,
            ACTOR,
            SESSION_GENERATION,
            foreground = true,
        )
        val binding = requireNotNull(requesting.bindingOrNull)
        val running = PostLoginDeviceCheckPolicy.beginRunning(
            requesting,
            binding,
            foreground = true,
        )
        val full = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(),
        )
        val limited = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(
                unsupportedFeatures = setOf(PostLoginDeviceCheckFeature.VOICE_GUIDANCE),
            ),
        )

        listOf(requesting, running, full, limited).forEach { protected ->
            assertEquals(
                protected,
                PostLoginDeviceCheckPolicy.beginFromUserAction(
                    protected,
                    ACTOR,
                    SESSION_GENERATION,
                    foreground = true,
                ),
            )
        }

        val failed = PostLoginDeviceCheckPolicy.timeout(running, binding, foreground = true)
        val retry = PostLoginDeviceCheckPolicy.beginFromUserAction(
            failed,
            ACTOR,
            SESSION_GENERATION,
            foreground = true,
        )
        assertEquals(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS, retry.state)
        assertEquals(binding.attemptGeneration + 1L, retry.attemptGeneration)
        assertEquals(null, retry.failure)
        assertTrue(retry.disabledFeatures.isEmpty())
    }

    @Test
    fun lateCallbacksCannotRewriteFullLimitedOrFailEvenAfterBackgrounding() {
        val (running, binding) = runningAttempt()
        val full = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(),
        )
        val limited = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(
                unsupportedFeatures = setOf(
                    PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE,
                ),
            ),
        )
        val failed = PostLoginDeviceCheckPolicy.timeout(running, binding, foreground = true)
        val lateFailure = readyObservation(
            blockingFailure = PostLoginDeviceCheckFailure.CAMERA_PIPELINE_UNAVAILABLE,
        )

        listOf(full, limited, failed).forEach { terminal ->
            assertEquals(
                terminal,
                PostLoginDeviceCheckPolicy.evaluate(
                    terminal,
                    binding,
                    foreground = false,
                    observation = lateFailure,
                ),
            )
            assertEquals(
                terminal,
                PostLoginDeviceCheckPolicy.timeout(
                    terminal,
                    binding,
                    foreground = false,
                ),
            )
            assertEquals(
                terminal,
                PostLoginDeviceCheckPolicy.beginRunning(
                    terminal,
                    binding,
                    foreground = false,
                ),
            )
        }
    }

    @Test
    fun staleAttemptAndSessionCallbacksCannotChangeCurrentAttempt() {
        val (firstRunning, firstBinding) = runningAttempt()
        val firstFailed = PostLoginDeviceCheckPolicy.timeout(
            firstRunning,
            firstBinding,
            foreground = true,
        )
        val retryRequest = PostLoginDeviceCheckPolicy.beginFromUserAction(
            firstFailed,
            ACTOR,
            SESSION_GENERATION,
            foreground = true,
        )
        val retryBinding = requireNotNull(retryRequest.bindingOrNull)
        val retryRunning = PostLoginDeviceCheckPolicy.beginRunning(
            retryRequest,
            retryBinding,
            foreground = true,
        )

        val staleResult = PostLoginDeviceCheckPolicy.evaluate(
            retryRunning,
            firstBinding,
            foreground = true,
            observation = readyObservation(metricDepth = PostLoginMetricDepthState.AVAILABLE),
        )
        val changedSession = PostLoginDeviceCheckPolicy.bindSession(
            staleResult,
            actorId = "actor_b",
            sessionGeneration = SESSION_GENERATION + 1L,
        )

        assertEquals(retryRunning, staleResult)
        assertEquals(PostLoginDeviceCheckState.NOT_RUN, changedSession.state)
        assertEquals("actor_b", changedSession.actorId)
        assertFalse(changedSession.passesFeatureGate)
    }

    @Test
    fun logoutAndReloginRequireAnExplicitNewForegroundAttempt() {
        val (running, binding) = runningAttempt()
        val full = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(metricDepth = PostLoginMetricDepthState.AVAILABLE),
        )

        val loggedOut = PostLoginDeviceCheckPolicy.bindSession(
            full,
            actorId = null,
            sessionGeneration = null,
        )
        val reloggedIn = PostLoginDeviceCheckPolicy.bindSession(
            loggedOut,
            actorId = ACTOR,
            sessionGeneration = SESSION_GENERATION + 1L,
        )

        assertEquals(PostLoginDeviceCheckState.NOT_RUN, loggedOut.state)
        assertFalse(loggedOut.passesFeatureGate)
        assertEquals(PostLoginDeviceCheckState.NOT_RUN, reloggedIn.state)
        assertFalse(reloggedIn.passesFeatureGate)

        val recheck = PostLoginDeviceCheckPolicy.beginFromUserAction(
            reloggedIn,
            ACTOR,
            SESSION_GENERATION + 1L,
            foreground = true,
        )
        assertEquals(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS, recheck.state)
        assertEquals(binding.attemptGeneration + 1L, recheck.attemptGeneration)
        assertFalse(recheck.passesFeatureGate)
    }

    @Test
    fun leavingForegroundPreservesCompletedPassForCurrentBinding() {
        val (running, binding) = runningAttempt()
        val full = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(metricDepth = PostLoginMetricDepthState.AVAILABLE),
        )
        val limited = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(
                metricDepth = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED,
                unsupportedFeatures = setOf(
                    PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE,
                ),
            ),
        )

        listOf(full, limited).forEach { completed ->
            val backgrounded = PostLoginDeviceCheckPolicy.onBackground(
                completed,
                binding,
                ownsPermissionDialog = false,
            )

            assertEquals(completed, backgrounded)
            assertTrue(backgrounded.passesFeatureGate)
        }
    }

    @Test
    fun changedPrerequisiteInvalidatesOnlyACompletedGate() {
        val (running, binding) = runningAttempt()
        val full = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(metricDepth = PostLoginMetricDepthState.AVAILABLE),
        )

        val invalidated = PostLoginDeviceCheckPolicy.invalidatePassedGate(
            full,
            PostLoginDeviceCheckFailure.LOCATION_SERVICE_DISABLED,
        )
        val untouchedRunning = PostLoginDeviceCheckPolicy.invalidatePassedGate(
            running,
            PostLoginDeviceCheckFailure.LOCATION_SERVICE_DISABLED,
        )

        assertEquals(PostLoginDeviceCheckState.FAIL, invalidated.state)
        assertEquals(
            PostLoginDeviceCheckFailure.LOCATION_SERVICE_DISABLED,
            invalidated.failure,
        )
        assertEquals(full.bindingOrNull, invalidated.bindingOrNull)
        assertFalse(invalidated.passesFeatureGate)
        assertEquals(running, untouchedRunning)
    }

    @Test
    fun backgroundFailsExceptForOwnedPermissionDialog() {
        val bound = boundSnapshot()
        val requesting = PostLoginDeviceCheckPolicy.beginFromUserAction(
            bound,
            ACTOR,
            SESSION_GENERATION,
            foreground = true,
        )
        val binding = requireNotNull(requesting.bindingOrNull)

        val permissionDialogPause = PostLoginDeviceCheckPolicy.onBackground(
            requesting,
            binding,
            ownsPermissionDialog = true,
        )
        val ordinaryPause = PostLoginDeviceCheckPolicy.onBackground(
            requesting,
            binding,
            ownsPermissionDialog = false,
        )
        val running = PostLoginDeviceCheckPolicy.beginRunning(
            requesting,
            binding,
            foreground = true,
        )
        val runningPause = PostLoginDeviceCheckPolicy.onBackground(
            running,
            binding,
            ownsPermissionDialog = false,
        )

        assertEquals(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS, permissionDialogPause.state)
        assertEquals(PostLoginDeviceCheckState.FAIL, ordinaryPause.state)
        assertEquals(PostLoginDeviceCheckFailure.BACKGROUNDED, ordinaryPause.failure)
        assertEquals(PostLoginDeviceCheckState.FAIL, runningPause.state)
        assertEquals(PostLoginDeviceCheckFailure.BACKGROUNDED, runningPause.failure)
        assertFalse(runningPause.passesFeatureGate)
    }

    private fun runningAttempt(): Pair<PostLoginDeviceCheckSnapshot, PostLoginDeviceCheckBinding> {
        val requesting = PostLoginDeviceCheckPolicy.beginFromUserAction(
            boundSnapshot(),
            ACTOR,
            SESSION_GENERATION,
            foreground = true,
        )
        val binding = requireNotNull(requesting.bindingOrNull)
        return PostLoginDeviceCheckPolicy.beginRunning(
            requesting,
            binding,
            foreground = true,
        ) to binding
    }

    private fun boundSnapshot() = PostLoginDeviceCheckPolicy.bindSession(
        PostLoginDeviceCheckPolicy.initial(),
        ACTOR,
        SESSION_GENERATION,
    )

    private fun readyObservation(
        metricDepth: PostLoginMetricDepthState = PostLoginMetricDepthState.AVAILABLE,
        cameraPipeline: PostLoginDeviceCheckSignal = PostLoginDeviceCheckSignal.READY,
        unsupportedFeatures: Set<PostLoginDeviceCheckFeature> = emptySet(),
        blockingFailure: PostLoginDeviceCheckFailure? = null,
    ) = PostLoginDeviceCheckObservation(
        minimumAndroidVersion = PostLoginDeviceCheckSignal.READY,
        requiredPermissions = PostLoginDeviceCheckSignal.READY,
        voiceDisclosure = PostLoginDeviceCheckSignal.READY,
        coreHardwareAndServices = PostLoginDeviceCheckSignal.READY,
        locationService = PostLoginDeviceCheckSignal.READY,
        locationFix = PostLoginDeviceCheckSignal.READY,
        deviceResources = PostLoginDeviceCheckSignal.READY,
        detector = PostLoginDeviceCheckSignal.READY,
        cameraPipeline = cameraPipeline,
        koreanTextToSpeech = PostLoginDeviceCheckSignal.READY,
        wakePhraseRecognition = PostLoginDeviceCheckSignal.READY,
        hapticFeedback = PostLoginDeviceCheckSignal.READY,
        metricDepth = metricDepth,
        unsupportedFeatures = unsupportedFeatures,
        blockingFailure = blockingFailure,
    )

    private companion object {
        const val ACTOR = "actor_a"
        const val SESSION_GENERATION = 7L
    }
}
