package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Wiring only. Behavior tests separately fail the same storage ports after valid consent. */
class MainActivitySensitiveWorkBoundaryStaticTest {
    private fun source(path: String) = File("src/main/java/kr/co/hanium/dreamup/walksafe/$path").readText()
    @Test fun cachedConsentCannotBypassBlockedStorageAtCollectionOrTransferEntryPoints() {
        val main = source("MainActivity.kt")
        val candidate = source("report/runtime/ReportCandidateController.kt")
        for (name in listOf("prepareReportCandidate", "processReportCandidate", "ensureExplicitReportAuthorityPreconditions",
            "currentExplicitReportConfirmationContextOrNull")) {
            val method = MainActivityRefactorSource.member(candidate, name)
            assertTrue(name, method.contains("authority.sensitiveStorageBlocked()"))
        }
        val queue = source("report/runtime/ReportQueueDrainController.kt")
        for (name in listOf("reportQueueDrainContext", "reportQueueRecoveryBaseGatesAvailable")) {
            assertTrue(MainActivityRefactorSource.member(queue, name).contains("sources.sensitiveStorageBlocked()"))
        }
        assertTrue(MainActivityRefactorSource.member(source("report/runtime/RawCollectionTransferController.kt"),
            "currentRawCollectionRuntimeContextOrNull").contains("runtime.sensitiveStorageBlocked()"))
        val consent = source("session/consent/IntegratedConsentController.kt")
        for (name in listOf("applyIntegratedConsentConfirmation", "persistIntegratedConsentDraft")) {
            assertTrue(MainActivityRefactorSource.member(consent, name).contains("sensitivePrefs.isBlocked()"))
        }
        assertEquals(3, Regex("""!this@MainActivity::sensitivePrefs\.isInitialized \|\| sensitivePrefs\.isBlocked\(\)""").findAll(main).count())
    }
}
