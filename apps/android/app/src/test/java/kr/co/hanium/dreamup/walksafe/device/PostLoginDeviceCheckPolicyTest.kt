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
    }

    @Test
    fun explicitDepthUnsupportedNeedsOperationalFallbackForLimited() {
        val (running, binding) = runningAttempt()

        val pending = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(
                metricDepth = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED,
                cameraFallback = PostLoginDeviceCheckSignal.PENDING,
            ),
        )
        val limited = PostLoginDeviceCheckPolicy.evaluate(
            pending,
            binding,
            foreground = true,
            observation = readyObservation(
                metricDepth = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED,
                cameraFallback = PostLoginDeviceCheckSignal.READY,
            ),
        )

        assertEquals(PostLoginDeviceCheckState.RUNNING, pending.state)
        assertEquals(PostLoginDeviceCheckState.LIMITED, limited.state)
        assertTrue(limited.passesFeatureGate)
    }

    @Test
    fun unknownTimeoutAndUnavailableFallbackNeverPass() {
        listOf(
            readyObservation(metricDepth = PostLoginMetricDepthState.UNKNOWN) to
                PostLoginDeviceCheckFailure.DEPTH_UNKNOWN,
            readyObservation(metricDepth = PostLoginMetricDepthState.TIMED_OUT) to
                PostLoginDeviceCheckFailure.DEPTH_TIMEOUT,
            readyObservation(
                metricDepth = PostLoginMetricDepthState.EXPLICITLY_UNSUPPORTED,
                cameraFallback = PostLoginDeviceCheckSignal.UNAVAILABLE,
            ) to PostLoginDeviceCheckFailure.CAMERA_FALLBACK_UNAVAILABLE,
        ).forEach { (observation, expectedFailure) ->
            val (running, binding) = runningAttempt()

            val failed = PostLoginDeviceCheckPolicy.evaluate(
                running,
                binding,
                foreground = true,
                observation = observation,
            )

            assertEquals(PostLoginDeviceCheckState.FAIL, failed.state)
            assertEquals(expectedFailure, failed.failure)
            assertFalse(failed.passesFeatureGate)
        }
    }

    @Test
    fun everyUnavailableCoreSignalFailsClosed() {
        val unavailable = PostLoginDeviceCheckSignal.UNAVAILABLE
        val observations = listOf(
            readyObservation().copy(minimumAndroidVersion = unavailable),
            readyObservation().copy(requiredPermissions = unavailable),
            readyObservation().copy(coreHardwareAndServices = unavailable),
            readyObservation().copy(locationService = unavailable),
            readyObservation().copy(deviceResources = unavailable),
            readyObservation().copy(detector = unavailable),
        )

        observations.forEach { observation ->
            val (running, binding) = runningAttempt()
            val failed = PostLoginDeviceCheckPolicy.evaluate(
                running,
                binding,
                foreground = true,
                observation = observation,
            )

            assertEquals(PostLoginDeviceCheckState.FAIL, failed.state)
            assertFalse(failed.passesFeatureGate)
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
    fun staleAttemptAndSessionCallbacksCannotChangeCurrentAttempt() {
        val (firstRunning, firstBinding) = runningAttempt()
        val retryRequest = PostLoginDeviceCheckPolicy.beginFromUserAction(
            firstRunning,
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
    fun leavingForegroundInvalidatesAFullPassUntilUserRetries() {
        val (running, binding) = runningAttempt()
        val full = PostLoginDeviceCheckPolicy.evaluate(
            running,
            binding,
            foreground = true,
            observation = readyObservation(metricDepth = PostLoginMetricDepthState.AVAILABLE),
        )

        val backgrounded = PostLoginDeviceCheckPolicy.onBackground(
            full,
            binding,
            ownsPermissionDialog = false,
        )

        assertEquals(PostLoginDeviceCheckState.FAIL, backgrounded.state)
        assertEquals(PostLoginDeviceCheckFailure.BACKGROUNDED, backgrounded.failure)
        assertFalse(backgrounded.passesFeatureGate)

        val retry = PostLoginDeviceCheckPolicy.beginFromUserAction(
            backgrounded,
            ACTOR,
            SESSION_GENERATION,
            foreground = true,
        )
        assertEquals(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS, retry.state)
        assertFalse(retry.passesFeatureGate)
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

        assertEquals(PostLoginDeviceCheckState.REQUESTING_PERMISSIONS, permissionDialogPause.state)
        assertEquals(PostLoginDeviceCheckState.FAIL, ordinaryPause.state)
        assertEquals(PostLoginDeviceCheckFailure.BACKGROUNDED, ordinaryPause.failure)
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
        cameraFallback: PostLoginDeviceCheckSignal = PostLoginDeviceCheckSignal.READY,
    ) = PostLoginDeviceCheckObservation(
        minimumAndroidVersion = PostLoginDeviceCheckSignal.READY,
        requiredPermissions = PostLoginDeviceCheckSignal.READY,
        coreHardwareAndServices = PostLoginDeviceCheckSignal.READY,
        locationService = PostLoginDeviceCheckSignal.READY,
        deviceResources = PostLoginDeviceCheckSignal.READY,
        detector = PostLoginDeviceCheckSignal.READY,
        metricDepth = metricDepth,
        cameraFallback = cameraFallback,
    )

    private companion object {
        const val ACTOR = "actor_a"
        const val SESSION_GENERATION = 7L
    }
}
