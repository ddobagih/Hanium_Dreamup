package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S09-43 (originals/09.txt:116) and S09-56 (09.txt:154), "연결 검증": the activity passes the
 * current usage condition (enum names only), keeps the profile load status in the field log, and
 * resets stored step profiles on account deletion (the explicit reset path).
 */
class MainActivityPedestrianProfileStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun everyStepCarriesItsUsageConditionBeforeItReachesTheCoordinator() {
        val step = block("private fun handlePositioningStepEvent(")
        val condition = step.indexOf("binding.coordinator.setCurrentCalibrationCondition(")
        val observe = step.indexOf("val snapshot = binding.coordinator.observeStep(")
        assertTrue(condition in 0 until observe)
        // w25 review pos-F6: a calibration sample completed by this step records this step's condition.
        val calibrationSegments = step.indexOf("walkingCalibrationSegments.onStep(")
        assertTrue(condition in 0 until calibrationSegments)
        val arguments = step.substring(condition, calibrationSegments)
        assertTrue(arguments.contains("stepInput = event.source.name"))
        // Only this walk's mounting confirmation (the same epoch rule as chestMountedHeadingForStep).
        assertTrue(step.contains("val currentWalkEpoch = walkSessionLifecycle.currentRuntimeEpochOrNull()"))
        assertTrue(
            arguments.contains("mounting = phoneMountingUserConfirmation?.takeIf { it.epoch == currentWalkEpoch }?.method?.name"),
        )
        assertTrue(step.indexOf("binding.coordinator.setCurrentCalibrationCondition(", condition + 1) < 0)
    }

    @Test
    fun profileLoadStatusIsRecordedAndDefaultOrFailureAreDistinguishable() {
        val load = block("private fun ensurePositioningProfileForCurrentActor(")
        assertTrue(load.contains("pedestrianProfileStore?.loadResult(id)"))
        assertTrue(load.contains("?.also(::recordPedestrianProfileLoad)?.profile"))
        val record = block("private fun recordPedestrianProfileLoad(")
        assertTrue(record.contains("fieldSessionLog.recordEvent(\"pedestrian_profile_loaded\", mapOf(\"result\" to result.status.name))"))
        val fieldLog = File("src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionLog.kt").readText()
        assertTrue(fieldLog.substringAfter("val ALLOWED_EVENT_FIELDS = setOf(").substringBefore("\n        )\n").contains("\"result\""))
    }

    @Test
    fun accountDeletionResetsStoredStepProfilesAndGatesItsEvidenceOnIt() {
        val purge = block("private fun purgeAccountDeletionLocalDataOnCleanupThread(")
        assertTrue(purge.contains("?.resetAllProfiles() == true"))
        assertTrue(purge.contains("AccountDeletionLocalStore.PEDESTRIAN_PROFILES to"))
        assertTrue(purge.contains("return inventory.purge().isEmpty()"))
    }

    /** w25 review pos-F2: the reset is ordered after queued and in-flight saves on the profile I/O thread. */
    @Test
    fun accountDeletionResetRunsBehindTheProfileWriterBarrierAndReadsAVolatileStore() {
        val purge = block("private fun purgeAccountDeletionLocalDataOnCleanupThread(")
        val barrier = purge.indexOf("SerialPedestrianProfileWriter.shared")
        val clear = purge.indexOf(".clearPendingAndRun {")
        val reset = purge.indexOf("?.resetAllProfiles() == true")
        assertTrue(barrier in 0 until clear)
        assertTrue(clear in 0 until reset)
        assertTrue(!purge.substring(0, clear).contains("resetAllProfiles"))
        assertTrue(source.contains("@Volatile\n    private var pedestrianProfileStore: AndroidPedestrianProfileStore? = null"))
    }

    /** S09-56 remaining: a profile loaded before the field session started is repeated at its start. */
    @Test
    fun theLastProfileLoadStatusIsRecordedWhenAFieldSessionStarts() {
        val record = block("private fun recordPedestrianProfileLoad(")
        assertTrue(record.contains("latestPedestrianProfileLoadStatus = result.status.name"))
        val load = block("private fun ensurePositioningProfileForCurrentActor(")
        assertTrue(load.indexOf("latestPedestrianProfileLoadStatus = null") in 0 until load.indexOf("loadResult(id)"))
        val start = source.indexOf("fieldSessionLog.startAfterUserConfirmation()")
        assertTrue(start >= 0)
        val afterStart = source.substring(start, source.indexOf("startNavigationServicesIfNeeded()", start))
        assertTrue(afterStart.contains("latestPedestrianProfileLoadStatus?.let { status ->"))
        assertTrue(afterStart.contains("fieldSessionLog.recordEvent(\"pedestrian_profile_loaded\", mapOf(\"result\" to status))"))
    }

    private fun block(marker: String): String {
        val start = source.indexOf(marker)
        require(start >= 0) { "missing $marker" }
        val open = source.indexOf('{', source.indexOf(')', start))
        var depth = 0
        for (index in open until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return source.substring(start, index + 1)
                }
            }
        }
        error("unterminated $marker")
    }
}
