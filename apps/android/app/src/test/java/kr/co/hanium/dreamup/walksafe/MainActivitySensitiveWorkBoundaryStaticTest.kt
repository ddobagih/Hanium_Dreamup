package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring checks only. Actual KeyStore/lifecycle behavior still needs a device. */
class MainActivitySensitiveWorkBoundaryStaticTest {
    private val source = listOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first(File::isFile).readText()

    @Test
    fun cachedConsentCannotBypassBlockedStorageAtCollectionOrTransferEntryPoints() {
        for (name in listOf(
            "currentRawCollectionRuntimeContextOrNull", "reportQueueDrainContext",
            "reportQueueRecoveryBaseGatesAvailable", "prepareReportCandidate", "processReportCandidate",
            "ensureExplicitReportAuthorityPreconditions", "currentExplicitReportConfirmationContextOrNull",
            "applyIntegratedConsentConfirmation", "persistIntegratedConsentDraft",
        )) assertTrue(name, method(name).contains("sensitivePrefs.isBlocked()"))
        val raw = method("currentRawCollectionRuntimeContextOrNull")
        assertTrue(raw.substringAfter("gateway.session?.takeUnless").substringBefore("val transport")
            .contains("sensitivePrefs.isBlocked()"))
        assertTrue(method("reportQueueDrainContext").substringAfter("val consentAllowed")
            .substringBefore("val currentGateway").contains("!sensitivePrefs.isBlocked()"))
        for (name in listOf("prepareReportCandidate", "processReportCandidate")) {
            val body = method(name)
            assertTrue(body.indexOf("sensitivePrefs.isBlocked()") < body.indexOf("reportPermissionsAllowWork()"))
            assertTrue(body.contains("reportCandidate=blocked:sensitive_storage"))
        }
    }

    private fun method(name: String): String {
        val start = source.indexOf("private fun $name(")
        check(start >= 0)
        val end = source.indexOf("\n    private ", start + 1).takeIf { it >= 0 } ?: source.length
        return source.substring(start, end)
    }
}
