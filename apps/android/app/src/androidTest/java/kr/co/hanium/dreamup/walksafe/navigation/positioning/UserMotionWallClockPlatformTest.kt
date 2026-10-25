package kr.co.hanium.dreamup.walksafe.navigation.positioning

import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicitly opted-in component runner on the parent's exclusively owned private AVD only.
 * Changes the actual OS wall clock; never changes automatic time, timezone, permissions or boot.
 * The admitted step is a public-API input with actual elapsed timestamps, not a physical sensor event.
 * A timeout/process kill cannot guarantee finally: the executor must independently verify recovery.
 */
@RunWith(AndroidJUnit4::class)
class UserMotionWallClockPlatformTest {
    @Test(timeout = 60_000L)
    fun actualWallClockChangesDoNotRenewMonotonicStepEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        check(InstrumentationRegistry.getArguments().getString("allowPrivateAvdWallClockMutation") == "true") {
            "OS_MUTATION_NOT_AUTHORIZED: explicit private-AVD executor opt-in is required"
        }
        check(Build.VERSION.SDK_INT >= 34) { "ENV_RWE_REQUIRES_API_34: no clock command was issued" }
        check(Build.HARDWARE.lowercase(Locale.ROOT) in setOf("ranchu", "goldfish")) {
            "OS_MUTATION_NOT_AUTHORIZED: this fixture must not run on a physical device"
        }
        val resolver = instrumentation.targetContext.contentResolver
        val originalAutoTime = Settings.Global.getString(resolver, Settings.Global.AUTO_TIME)
        val originalAutoZone = Settings.Global.getString(resolver, Settings.Global.AUTO_TIME_ZONE)
        val originalZone = TimeZone.getDefault().id
        val originalBootCount = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT)
        assertEquals("ENV_ORIGINAL_AUTO_TIME", "1", originalAutoTime)
        assertEquals("ENV_ORIGINAL_AUTO_TIME_ZONE", "1", originalAutoZone)
        assertEquals("ENV_ORIGINAL_TIMEZONE", "Asia/Seoul", originalZone)
        assertTrue("ENV_ORIGINAL_BOOT_COUNT", originalBootCount >= 0)

        val config = UserMotionStateConfig()
        val motion = UserMotionStateEstimator(config)
        val coordinator = PositioningCoordinator(userMotionEstimator = motion)
        assertTrue(coordinator.restoreProfile(PersistedPedestrianMotionProfile(0.7, 1.0, 0.64, 12L)))
        val profile = coordinator.currentProfile()
        val generation = coordinator.currentInputGeneration()
        val owner = UserMotionStateOwner(1L, WalkRuntimeEpoch("wallclock-${UUID.randomUUID()}", 0L),
            "android-boot-count:$originalBootCount", UUID.randomUUID().toString())
        val io = Executors.newFixedThreadPool(2)
        val report: (String) -> Unit = { text ->
            instrumentation.sendStatus(0, Bundle().apply { putString("wallclockBoundary", text) })
        }
        // Rwe is API 34: stdout, stdin, stderr. Both readers share one elapsed command budget.
        val shell: (String) -> Pair<String, String> = { command ->
            val startedAt = SystemClock.elapsedRealtime()
            val pipes = instrumentation.uiAutomation.executeShellCommandRwe(command)
            var stdout: Future<String>? = null
            var stderr: Future<String>? = null
            var commandFailure: Throwable? = null
            try {
                check(pipes.size == 3) { "ENV_RWE_DESCRIPTOR_COUNT" }
                pipes[1].close() // These commands need no stdin.
                val stdoutReader = io.submit(Callable {
                    ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).bufferedReader(Charsets.UTF_8).use { it.readText() }
                })
                stdout = stdoutReader
                val stderrReader = io.submit(Callable {
                    ParcelFileDescriptor.AutoCloseInputStream(pipes[2]).bufferedReader(Charsets.UTF_8).use { it.readText() }
                })
                stderr = stderrReader
                val remaining: () -> Long = {
                    (config.maximumStepAgeMs - (SystemClock.elapsedRealtime() - startedAt)).also {
                        check(it > 0L) { "ENV_CLOCK_COMMAND_EXCEEDED_EXISTING_TTL_BUDGET: $command" }
                    }
                }
                stdoutReader.get(remaining(), TimeUnit.MILLISECONDS) to stderrReader.get(remaining(), TimeUnit.MILLISECONDS)
            } catch (failure: Throwable) {
                commandFailure = failure
                throw failure
            } finally {
                stdout?.cancel(true)
                stderr?.cancel(true)
                var closeFailure: Throwable? = null
                pipes.forEach { descriptor ->
                    try { descriptor.close() } catch (failure: Throwable) {
                        if (closeFailure == null) closeFailure = failure else closeFailure?.addSuppressed(failure)
                    }
                }
                closeFailure?.let { failure ->
                    commandFailure?.let { failure.addSuppressed(it) }
                    throw failure
                }
            }
        }

        var anchorWallMs: Long? = null
        var anchorElapsedMs: Long? = null
        var originalBootId: String? = null
        var setterAttempted = false
        var primaryFailure: Throwable? = null
        try {
            val bootId = shell("cat /proc/sys/kernel/random/boot_id").first.trim()
            originalBootId = bootId
            check(bootId.isNotEmpty()) { "ENV_KERNEL_BOOT_ID_UNAVAILABLE" }
            assertEquals(originalZone, shell("getprop persist.sys.timezone").first.trim())
            val wall = System.currentTimeMillis()
            val elapsed = SystemClock.elapsedRealtime()
            anchorWallMs = wall
            anchorElapsedMs = elapsed
            // Delivered before any intentional mutation so an external executor can recover even after a kill.
            report("CLOCK_ANCHOR wallMs=$wall elapsedMs=$elapsed bootCount=$originalBootCount " +
                "bootId=$originalBootId autoTime=$originalAutoTime autoZone=$originalAutoZone zone=$originalZone")

            assertTrue(coordinator.beginUserMotionEpoch(owner, SystemClock.elapsedRealtime()))
            val measuredAt = SystemClock.elapsedRealtime()
            val receivedAt = SystemClock.elapsedRealtime()
            motion.observeSteps(1, PdrStepQuality.HIGH, measuredAt, receivedAt,
                expectedGeneration = generation, expectedOwner = owner)
            val before = coordinator.currentUserMotion(SystemClock.elapsedRealtime())
            assertEquals(UserMovementState.MOVING, before.movement)
            assertEquals(UserMotionEvidenceHealth.VALID, before.evidenceHealth)
            assertEquals(owner, before.owner)
            assertEquals(generation, before.generation)
            assertEquals(measuredAt, before.stepObservedAtElapsedRealtimeMs)
            val expiresAt = requireNotNull(before.expiresAtElapsedRealtimeMs)
            assertEquals(measuredAt + config.maximumStepAgeMs, expiresAt)
            val originalRef = before.evidenceRefs.single()
            assertEquals(UserMotionEvidenceSource.UNKNOWN_STEP_SOURCE, originalRef.source)
            assertEquals(measuredAt, originalRef.measuredAtElapsedRealtimeMs)
            assertEquals(receivedAt, originalRef.receivedAtElapsedRealtimeMs)
            assertEquals(1, originalRef.stepEvidence?.stepCount)
            assertEquals(PdrStepQuality.HIGH, originalRef.stepEvidence?.quality)

            // This is an OS stimulus derived from the unchanged TTL, not a new product threshold.
            val shiftMs = config.maximumStepAgeMs + 1L
            for (direction in listOf(1L, -1L)) {
                val requestedAt = SystemClock.elapsedRealtime()
                assertTrue("ENV_LIVE_CONTROL_EXPIRED_BEFORE_CLOCK_COMMAND", requestedAt <= expiresAt)
                val target = wall + (requestedAt - elapsed) + direction * shiftMs
                setterAttempted = true // Mark before IPC, including a command that fails or times out.
                val output = shell("cmd alarm set-time $target")
                val observedWall = System.currentTimeMillis()
                val observedElapsed = SystemClock.elapsedRealtime()
                report("CLOCK_CHANGE direction=$direction targetWallMs=$target commandStartElapsedMs=$requestedAt " +
                    "observedWallMs=$observedWall observedElapsedMs=$observedElapsed " +
                    "stdout=${output.first.trim()} stderr=${output.second.trim()}")
                assertTrue("ENV_CLOCK_MUTATION_NOT_OBSERVED: $output",
                    observedWall in target..(target + observedElapsed - requestedAt))
                val unchangedWall = wall + (observedElapsed - elapsed)
                assertTrue("ENV_CLOCK_DIRECTION_NOT_OBSERVED",
                    if (direction > 0L) observedWall > unchangedWall else observedWall < unchangedWall)
                assertTrue("ENV_COMMAND_LATENCY_EXPIRED_LIVE_CONTROL", observedElapsed <= expiresAt)
                val after = coordinator.currentUserMotion(observedElapsed)
                assertEquals(UserMovementState.MOVING, after.movement)
                assertEquals(UserMotionEvidenceHealth.VALID, after.evidenceHealth)
                assertEquals(before.stateSinceElapsedRealtimeMs, after.stateSinceElapsedRealtimeMs)
                assertEquals(before.expiresAtElapsedRealtimeMs, after.expiresAtElapsedRealtimeMs)
                assertEquals(before.evidenceRefs, after.evidenceRefs)
                assertEquals(measuredAt, after.stepObservedAtElapsedRealtimeMs)
                assertEquals(owner, after.owner)
                assertEquals(generation, after.generation)
                assertEquals(profile, coordinator.currentProfile())
            }

            var now = SystemClock.elapsedRealtime()
            while (now <= expiresAt) {
                Thread.sleep(expiresAt - now + 1L)
                now = SystemClock.elapsedRealtime()
            }
            val expiredWall = System.currentTimeMillis()
            assertTrue("ENV_BACKWARD_CLOCK_AUTOMATICALLY_CORRECTED_BEFORE_EXPIRY",
                expiredWall < wall + (SystemClock.elapsedRealtime() - elapsed))
            val expired = coordinator.currentUserMotion(now)
            assertEquals(UserMovementState.UNKNOWN, expired.movement)
            assertEquals(UserMotionEvidenceHealth.EXPIRED, expired.evidenceHealth)
            assertEquals(UserMotionStateReason.EXPIRED_EVIDENCE, expired.reason)
            assertNull(expired.stepObservedAtElapsedRealtimeMs)
            assertNull(expired.expiresAtElapsedRealtimeMs)
            assertTrue(expired.evidenceRefs.isEmpty())
            assertFalse(expired.zeroVelocityUpdateAllowed)
            assertEquals(owner, expired.owner)
            assertEquals(generation, expired.generation)
            assertEquals(generation, coordinator.currentInputGeneration())
            assertEquals(profile, coordinator.currentProfile())
            report("LIVE_EXPIRED originalExpiryMs=$expiresAt observedElapsedMs=$now observedWallMs=$expiredWall")
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            // Clear an interrupt while attempting recovery; retain it afterwards. An external kill can still prevent this.
            val interrupted = Thread.interrupted()
            var cleanupFailure: Throwable? = null
            try {
                if (setterAttempted) {
                    val restoreAt = SystemClock.elapsedRealtime()
                    val target = requireNotNull(anchorWallMs) + (restoreAt - requireNotNull(anchorElapsedMs))
                    val output = shell("cmd alarm set-time $target")
                    val restoredWall = System.currentTimeMillis()
                    val restoredElapsed = SystemClock.elapsedRealtime()
                    report("CLOCK_RESTORE targetWallMs=$target commandStartElapsedMs=$restoreAt " +
                        "observedWallMs=$restoredWall observedElapsedMs=$restoredElapsed " +
                        "commandDurationMs=${restoredElapsed - restoreAt} " +
                        "stdout=${output.first.trim()} stderr=${output.second.trim()}")
                    assertTrue("CLOCK_RESTORATION_AMBIGUOUS_COMMAND_DURATION: external executor recovery is required",
                        restoredElapsed - restoreAt < config.maximumStepAgeMs + 1L)
                    assertTrue("CLOCK_RESTORATION_NOT_OBSERVED: $output",
                        restoredWall in target..(target + restoredElapsed - restoreAt))
                }
                assertEquals(originalAutoTime, Settings.Global.getString(resolver, Settings.Global.AUTO_TIME))
                assertEquals(originalAutoZone, Settings.Global.getString(resolver, Settings.Global.AUTO_TIME_ZONE))
                assertEquals(originalZone, TimeZone.getDefault().id)
                assertEquals(originalZone, shell("getprop persist.sys.timezone").first.trim())
                assertEquals(originalBootCount, Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT))
                originalBootId?.let { assertEquals(it, shell("cat /proc/sys/kernel/random/boot_id").first.trim()) }
                report("PRESERVED autoTime=$originalAutoTime autoZone=$originalAutoZone " +
                    "zone=$originalZone bootCount=$originalBootCount setterAttempted=$setterAttempted")
            } catch (failure: Throwable) {
                cleanupFailure = failure
            } finally {
                io.shutdownNow()
                try {
                    check(io.awaitTermination(config.maximumStepAgeMs, TimeUnit.MILLISECONDS)) {
                        "CLOCK_COMMAND_READER_NOT_TERMINATED: external executor recovery is required"
                    }
                } catch (failure: Throwable) {
                    if (cleanupFailure == null) cleanupFailure = failure else cleanupFailure?.addSuppressed(failure)
                }
                if (interrupted) Thread.currentThread().interrupt()
            }
            cleanupFailure?.let { failure ->
                // Cleanup failure remains primary; product/control failure is preserved, never masked as a skip.
                primaryFailure?.let { failure.addSuppressed(it) }
                throw failure
            }
        }
    }
}
