package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * transfer-F7 (w24 review) wiring: a charger plugged in while the app is in the foreground after a
 * walk retries the post-walk transfer, a 409 *_walk_in_progress leads to one delayed retry after
 * the Gateway lease TTL, and capability callbacks restart transfers only on a relevant change.
 */
class MainActivityPostWalkRetryStaticTest {
    private val source = listOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first(File::isFile).readText()

    @Test
    fun capabilityCallbacksRestartTransfersOnlyWhenTheNetworkTransportOrValidationChanged() {
        val callback = source.substringAfter("override fun onCapabilitiesChanged(")
            .substringBefore("private var currentDestination")
        val filter = callback.indexOf("postWalkNetworkChangeFilter.changed(")
        assertTrue(filter >= 0)
        assertTrue(callback.contains("NetworkCapabilities.NET_CAPABILITY_VALIDATED"))
        assertTrue(callback.indexOf("onReportQueueDrainNetworkChanged()") > filter)
        val lost = source.substringAfter("override fun onLost(network: Network) {")
            .substringBefore("override fun onCapabilitiesChanged(")
        assertTrue(lost.contains("postWalkNetworkChangeFilter.reset()"))
        assertTrue(lost.contains("onReportQueueDrainNetworkChanged()"))
    }

    @Test
    fun aChargerPluggedInWhileInTheForegroundRetriesThePostWalkTransfer() {
        val receiver = source.substringAfter("private val postWalkPowerConnectedReceiver")
            .substringBefore("private var postWalkPowerConnectedReceiverRegistered")
        assertTrue(receiver.contains("Intent.ACTION_POWER_CONNECTED"))
        assertTrue(receiver.contains("onPostWalkChargingMaybeChanged()"))
        val register = functionBlock("private fun registerPostWalkPowerConnectedReceiver()")
        assertTrue(register.contains("IntentFilter(Intent.ACTION_POWER_CONNECTED)"))
        assertTrue(register.contains("onPostWalkChargingMaybeChanged()"))
        val resume = source.indexOf("registerPostWalkPowerConnectedReceiver()\n        schedulePostWalkTransfers()")
        assertTrue(resume >= 0)
        val pause = functionBlock("override fun onPause()")
        assertTrue(pause.contains("unregisterPostWalkPowerConnectedReceiver()"))
    }

    @Test
    fun aWalkInProgressRefusalSchedulesOneDelayedRetryAfterTheLeaseTtl() {
        val retry = functionBlock("private fun schedulePostWalkRetryAfterWalkInProgress()")
        assertTrue(retry.contains("if (postWalkWalkInProgressRetryWalkId == walkId) return@runOnUiThread"))
        assertTrue(retry.contains("POST_WALK_WALK_IN_PROGRESS_RETRY_DELAY_MS"))
        val delay = Regex("""const val POST_WALK_WALK_IN_PROGRESS_RETRY_DELAY_MS = 90_000L \+ (\d+)_000L""")
            .find(source)?.groupValues?.get(1)?.toInt()
        assertTrue(delay != null && delay in 5..30)
        val upload = functionBlock("private fun scheduleRawCollectionPostWalkUpload()")
        assertTrue(upload.contains("completed == RawCollectionUploadOutcome.WALK_IN_PROGRESS"))
        assertTrue(upload.contains("schedulePostWalkRetryAfterWalkInProgress()"))
        val drain = functionBlock("private fun drainInitialExactReportQueue(")
        assertTrue(drain.contains("if (result.walkInProgress) schedulePostWalkRetryAfterWalkInProgress()"))
        assertFalse(retry.contains("speakInteraction("))
    }

    private fun functionBlock(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing $signature" }
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
        error("unterminated $signature")
    }
}
