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
        val adapter = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/AndroidGatewayCapacityObserver.kt").readText()
        assertTrue(adapter.contains("NetworkCapabilities.NET_CAPABILITY_VALIDATED"))
        assertTrue(adapter.contains("events.capabilitiesChanged(network, transport,"))
        val network = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/gateway/GatewaySessionController.kt").readText()
        assertTrue(network.contains("if (networkEffects.networkCapabilitiesChanged(network, transport, validated)) networkEffects.reportDrainNetworkChanged()"))
        val lost = network.substringAfter("override fun lost() {").substringBefore("override fun capabilitiesChanged")
        assertTrue(lost.contains("networkEffects.resetNetworkChangeFilter()"))
        assertTrue(lost.contains("networkEffects.reportDrainNetworkChanged()"))
        val wiring = MainActivityRefactorSource.factory(source, "createGatewaySessionController")
        assertTrue(wiring.contains("postWalkNetworkChangeFilter.changed(network, transport, validated)"))
        assertTrue(wiring.contains("override fun reportDrainNetworkChanged() = onReportQueueDrainNetworkChanged()"))
    }

    @Test
    fun aChargerPluggedInWhileInTheForegroundRetriesThePostWalkTransfer() {
        val receiver = MainActivityRefactorSource.property(source, "postWalkPowerConnectedReceiver")
        assertTrue(receiver.contains("Intent.ACTION_POWER_CONNECTED"))
        assertTrue(receiver.contains("onPostWalkChargingMaybeChanged()"))
        val register = functionBlock("private fun registerPostWalkPowerConnectedReceiver()")
        assertTrue(MainActivityRefactorSource.factory(source, "createPostWalkTransferController").contains("IntentFilter(Intent.ACTION_POWER_CONNECTED)"))
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
            .find(File("src/main/java/kr/co/hanium/dreamup/walksafe/report/runtime/PostWalkTransferController.kt").readText())?.groupValues?.get(1)?.toInt()
        assertTrue(delay != null && delay in 5..30)
        val upload = functionBlock("private fun scheduleRawCollectionPostWalkUpload()")
        assertTrue(upload.contains("completed == RawCollectionUploadOutcome.WALK_IN_PROGRESS"))
        assertTrue(upload.contains("postWalk.scheduleWalkInProgressRetry()"))
        val drain = functionBlock("private fun drainInitialExactReportQueue(")
        assertTrue(drain.contains("if (result.walkInProgress) effects.schedulePostWalkRetryAfterWalkInProgress()"))
        assertFalse(retry.contains("speakInteraction("))
    }

    private fun functionBlock(signature: String): String = MainActivityRefactorSource.function(source, signature)
}
