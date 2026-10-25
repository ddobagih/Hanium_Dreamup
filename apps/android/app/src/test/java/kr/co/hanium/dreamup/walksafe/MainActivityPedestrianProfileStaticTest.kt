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
        val step = MainActivityRefactorSource.function(source, "private fun handlePositioningStepEvent(")
        val condition = step.indexOf("binding.coordinator.setCurrentCalibrationCondition(")
        val observe = step.indexOf("val snapshot = binding.coordinator.observeStep(")
        assertTrue(condition in 0 until observe)
        val calibrationSegments = step.indexOf("walkingCalibrationSession.onStep(")
        assertTrue(condition in 0 until calibrationSegments)
        val arguments = step.substring(condition, calibrationSegments)
        assertTrue(arguments.contains("stepInput = event.source.name"))
        assertTrue(step.contains("val currentWalkEpoch = requireNotNull(scope.lifecycle).currentRuntimeEpochOrNull()"))
        assertTrue(arguments.contains("mounting = mounting.confirmation?.takeIf { it.epoch == currentWalkEpoch }?.method?.name"))
        assertTrue(step.indexOf("binding.coordinator.setCurrentCalibrationCondition(", condition + 1) < 0)
        val wiring = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(wiring.contains("override val lifecycle get() = if (::walkSessionLifecycle.isInitialized) walkSessionLifecycle else null"))
        assertTrue(wiring.contains("override val confirmation get() = phoneMountingUserConfirmation"))
    }

    @Test
    fun profileLoadStatusIsRecordedAndDefaultOrFailureAreDistinguishable() {
        val load = MainActivityRefactorSource.function(source, "private fun ensurePositioningProfileForCurrentActor(")
        assertTrue(load.contains("pedestrianProfileStore?.loadResult(id)"))
        assertTrue(load.contains("?.also(::recordPedestrianProfileLoad)?.profile"))
        val record = MainActivityRefactorSource.function(source, "private fun recordPedestrianProfileLoad(")
        assertTrue(record.contains("diagnostics.record(\"pedestrian_profile_loaded\", mapOf(\"result\" to result.status.name))"))
        val diagnostics = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val diagnosticsOwner = MainActivityRefactorSource.composition(source, "routeGuidanceDiagnostics")
        assertTrue(diagnosticsOwner.contains("lazy { resources.diagnostics() }"))
        assertTrue(diagnostics.contains("override fun record(event: String, values: Map<String, Any?>)"))
        assertTrue(diagnostics.contains("if (::fieldSessionLog.isInitialized) runCatching { fieldSessionLog.recordEvent(event, values) }"))
        val fieldLog = File("src/main/java/kr/co/hanium/dreamup/walksafe/fieldlog/FieldSessionLog.kt").readText()
        assertTrue(fieldLog.substringAfter("val ALLOWED_EVENT_FIELDS = setOf(").substringBefore("\n        )\n").contains("\"result\""))
    }

    @Test
    fun accountDeletionResetsStoredStepProfilesAndGatesItsEvidenceOnIt() {
        val purger = MainActivityRefactorSource.function(source, "fun purgeLocalDataOnWorker(")
        assertTrue(purger.contains("val pedestrianProfilesPurged = runCatching { participants.purgeSerialPedestrianProfiles() }.getOrDefault(false)"))
        assertTrue(purger.contains("outdoorPositionTestPurged && pedestrianProfilesPurged"))
        val wiring = MainActivityRefactorSource.factory(source, "createPrivacyLocalPurge")
        val purgerOwner = MainActivityRefactorSource.composition(source, "accountDeletionLocalDataPurger")
        assertTrue(purgerOwner.contains("AccountDeletionLocalDataPurger("))
        assertTrue(purgerOwner.contains("participants = participants({ speechOutputStorageCoordinator }, routeArchive),"))
        assertTrue(wiring.contains("override fun purgeSerialPedestrianProfiles()"))
        assertTrue(wiring.contains("?.resetAllProfiles() == true"))
    }

    /** w25 review pos-F2: the reset is ordered after queued and in-flight saves on the profile I/O thread. */
    @Test
    fun accountDeletionResetRunsBehindTheProfileWriterBarrierAndReadsAVolatileStore() {
        val purge = MainActivityRefactorSource.factory(source, "createPrivacyLocalPurge")
        val purgerOwner = MainActivityRefactorSource.composition(source, "accountDeletionLocalDataPurger")
        assertTrue(purgerOwner.contains("AccountDeletionLocalDataPurger("))
        assertTrue(purgerOwner.contains("participants = participants({ speechOutputStorageCoordinator }, routeArchive),"))
        val barrier = purge.indexOf("SerialPedestrianProfileWriter.shared")
        val clear = purge.indexOf(".clearPendingAndRun {")
        val reset = purge.indexOf("?.resetAllProfiles() == true")
        assertTrue(barrier in 0 until clear)
        assertTrue(clear in 0 until reset)
        assertTrue(!purge.substring(0, clear).contains("resetAllProfiles"))
        val positioning = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt").readText()
        assertTrue(positioning.contains("@Volatile\n    private var pedestrianProfileStore: AndroidPedestrianProfileStore? = null"))
        assertTrue(MainActivityRefactorSource.property(source, "pedestrianProfileStore").contains("positioningSessionController.profileStore"))
        assertTrue(MainActivityRefactorSource.function(source, "fun purgeLocalDataOnWorker(").contains("participants.purgeSerialPedestrianProfiles()"))
    }

    /** S09-56 remaining: a profile loaded before the field session started is repeated at its start. */
    @Test
    fun theLastProfileLoadStatusIsRecordedWhenAFieldSessionStarts() {
        val record = MainActivityRefactorSource.function(source, "private fun recordPedestrianProfileLoad(")
        assertTrue(record.contains("latestPedestrianProfileLoadStatus = result.status.name"))
        val load = MainActivityRefactorSource.function(source, "private fun ensurePositioningProfileForCurrentActor(")
        assertTrue(load.indexOf("latestPedestrianProfileLoadStatus = null") in 0 until load.indexOf("loadResult(id)"))
        val start = source.indexOf("fieldSessionLog.startAfterUserConfirmation()")
        assertTrue(start >= 0)
        val afterStart = source.substring(start, source.indexOf("startNavigationServicesIfNeeded()", start))
        assertTrue(afterStart.contains("latestPedestrianProfileLoadStatus?.let { status ->"))
        assertTrue(afterStart.contains("fieldSessionLog.recordEvent(\"pedestrian_profile_loaded\", mapOf(\"result\" to status))"))
        assertTrue(MainActivityRefactorSource.property(source, "latestPedestrianProfileLoadStatus").contains("positioningSessionController.profileLoadStatus"))
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
