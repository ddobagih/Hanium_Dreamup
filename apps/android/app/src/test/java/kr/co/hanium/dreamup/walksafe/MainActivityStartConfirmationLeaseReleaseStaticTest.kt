package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S03-3 follow-up: when the lifecycle rejects the start confirmation after the server granted a
 * lease (for example the readiness TTL expired meanwhile), the acquired lease is released instead
 * of being held until it expires. Source ordering only.
 */
class MainActivityStartConfirmationLeaseReleaseStaticTest {
    private val source = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun rejectedStartConfirmationReleasesTheAcquiredLease() {
        val activation = MainActivityRefactorSource.function(source, "private fun completeGatewayWalkActivation(")
            .replace(Regex("\\s+"), "")
        val start = activation.indexOf("runtime.startRequested(token,currentReadiness)")
        val rejected = activation.indexOf(
            "if(runtime.snapshot().state!=WalkSessionState.ACTIVE){",
            start,
        )
        val release = activation.indexOf("authority.beginEnd(token.epoch)", rejected)
        val runtime = activation.indexOf("if(runtime.isActive()){", rejected)
        assertTrue(start >= 0 && rejected > start && release > rejected && runtime > release)
    }
}
