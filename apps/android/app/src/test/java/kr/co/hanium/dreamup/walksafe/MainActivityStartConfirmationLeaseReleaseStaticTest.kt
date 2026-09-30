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
    ).first { it.isFile }.readText().replace(Regex("\\s+"), "")

    @Test
    fun rejectedStartConfirmationReleasesTheAcquiredLease() {
        val activation = source.substringAfter("privatefuncompleteGatewayWalkActivation(")
            .substringBefore("privatefuncurrentGatewayWalkStartReadinessOrNull(")
        val start = activation.indexOf("transitionWalkSession(WalkSessionEvent.StartRequested(token))")
        val rejected = activation.indexOf(
            "if(walkSessionLifecycle.snapshot().state!=WalkSessionState.ACTIVE){",
            start,
        )
        val release = activation.indexOf("gatewayWalkAuthorityController.beginEnd(token.epoch)", rejected)
        val runtime = activation.indexOf("if(isWalkSessionRuntimeActive()){", rejected)
        assertTrue(start >= 0 && rejected > start && release > rejected && runtime > release)
    }
}
