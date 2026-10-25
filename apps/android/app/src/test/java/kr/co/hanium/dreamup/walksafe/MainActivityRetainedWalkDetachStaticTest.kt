package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.session.WalkSessionEvent
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S03-19-F2: after handing its paused walk to the re-created Activity, the destroyed Activity no
 * longer references that lifecycle, so its already-queued callbacks (for example a renewal answer
 * that finds the gateway session replaced and calls handleGatewayWalkAuthorityLost) cannot stop it.
 */
class MainActivityRetainedWalkDetachStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun theDestroyedActivityDetachesFromTheRetainedLifecycleRightAfterHandingItOver() {
        val destroy = source.substringAfter("override fun onDestroy()").substringBefore("override fun onActivityResult(")
        val retain = destroy.indexOf("processWalkConfigurationChangeRetention.retain(")
        val detach = destroy.indexOf(
            "if (walkRetainedForConfigurationChange) {\n" +
                "            walkSessionLifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)\n" +
                "        }",
        )
        assertTrue(retain >= 0 && detach > retain)
        assertTrue(detach < destroy.indexOf("super.onDestroy()\n    }"))
        // The authority-lost path only stops walks of the lifecycle the Activity references now.
        val lost = functionBlock("private fun handleGatewayWalkAuthorityLost(")
        assertTrue(lost.contains("val snapshot = runtime.snapshot()"))
        assertTrue(lost.contains("if (snapshot.state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED)) {"))
    }

    @Test
    fun aPlaceholderLifecycleIsNeitherActiveNorTheRetainedEpoch() {
        val retained = WalkSessionLifecycle(sessionIdFactory = { "walk-retained" }, clock = { 1_000L })
        retained.handle(WalkSessionEvent.EnteredForeground)
        val placeholder = WalkSessionLifecycle(elapsedRealtimeClock = { 2_000L })
        assertNotEquals(retained.snapshot().epoch, placeholder.snapshot().epoch)
        val before = retained.snapshot()
        placeholder.handle(WalkSessionEvent.SafetyStopRequested)
        assertEquals(before, retained.snapshot())
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)
}
