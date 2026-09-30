package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** S10-28: the best-output debug box carries the depth track id so another object is not tweened. */
class MainActivityDebugOverlayIdentityStaticTest {
    private val activity = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun bestOutputOverlayBoxCarriesTheTrackIdentity() {
        val builder = activity.substringAfter("private fun buildDebugOverlayBoxes(")
            .substringBefore("private fun createFrozenDepthMapper(")
        val bestBox = builder.substringAfter("val bestBox = bestOutput?.let { output ->")
        assertTrue(bestBox.contains("best = true,"))
        assertTrue(bestBox.contains("trackKey = output.trackId,"))
    }
}
