package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCapacityMainActivityStaticTest {
    private val source = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    ).readText()
    private val capacitySource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayCapacity.kt",
    ).readText()
    private val sessionSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt",
    ).readText()

    @Test
    fun verifiedStartupAndNetworkReconnectUseOneCoalescedRefreshWithoutPolling() {
        val sessionChanged = functionBlock("private fun onGatewayProcessSessionChanged(")
        val register = functionBlock("private fun registerGatewayCapacityNetworkObserver()")
        val refresh = functionBlock("private fun requestGatewayCapacityRefresh(")
        val callback = source.substringAfter("private val gatewayCapacityNetworkCallback =")
            .substringBefore("private var currentDestination")

        assertTrue(sessionChanged.contains("requestGatewayCapacityRefresh(\"verified_session_startup\")"))
        assertTrue(sessionChanged.contains("fenceSessionGeneration(snapshot.generation)"))
        assertTrue(register.contains("registerDefaultNetworkCallback(gatewayCapacityNetworkCallback)"))
        assertTrue(callback.contains("override fun onAvailable(network: Network)"))
        assertTrue(callback.contains("requestGatewayCapacityRefresh(\"network_reconnected\")"))
        assertTrue(refresh.contains("GatewayCapacityProcessState.tryBeginRefresh()"))
        assertTrue(refresh.contains("capacitySessionGeneration = expectedSessionGeneration"))
        assertTrue(refresh.contains("if (GatewayCapacityProcessState.finishRefresh())"))
        assertTrue(refresh.contains("requestGatewayCapacityRefresh(\"coalesced_latest_session\")"))
        assertTrue(refresh.contains("gatewaySessionClient.revalidate("))
        assertTrue(refresh.contains("GatewayCapacityProcessState.finishRefresh()"))
        assertFalse(refresh.contains("postDelayed("))
        assertFalse(capacitySource.contains("Timer("))
        assertFalse(capacitySource.contains("scheduleAtFixedRate"))
    }

    @Test
    fun destroyAlwaysUnregistersTheNetworkObserverBeforeItsEarlyReturn() {
        val destroy = functionBlock("override fun onDestroy()")
        val unregister = destroy.indexOf("unregisterGatewayCapacityNetworkObserver()")
        val earlyBranch = destroy.indexOf("if (!privacyStartupInspectionComplete)")

        assertTrue(unregister >= 0)
        assertTrue(earlyBranch > unregister)
        assertTrue(
            functionBlock("private fun unregisterGatewayCapacityNetworkObserver()")
                .contains("unregisterNetworkCallback("),
        )
    }

    @Test
    fun revalidationUpdatesCapacityIndependentlyFromSessionBindingReadiness() {
        val revalidate = sessionFunctionBlock("fun revalidate(")
        val binding = revalidate.indexOf("if (!bindingReady)")
        val update = revalidate.indexOf("GatewayCapacityProcessState.apply(")
        val ready = revalidate.lastIndexOf("GatewaySessionRevalidationStatus.READY")

        assertTrue(binding >= 0)
        assertTrue(update < binding)
        assertTrue(ready > update)
        assertTrue(revalidate.contains("GatewayCapacityParser.fromSessionStatus(statusJson)"))
        assertTrue(revalidate.contains("capacityAvailability"))
    }

    @Test
    fun rawCapacityGateRunsOnlyImmediatelyBeforeAnewSessionStarts() {
        val toggle = functionBlock("private fun toggleFieldSessionLog()")
        val active = toggle.indexOf("if (fieldSessionLog.isActive())")
        val stop = toggle.indexOf("fieldSessionLog.stop()")
        val capacity = toggle.indexOf("GatewayCapacityProcessState.admission()")
        val start = toggle.indexOf("fieldSessionLog.startAfterUserConfirmation()")

        assertTrue(active >= 0)
        assertTrue(stop > active)
        assertTrue(capacity > stop)
        assertTrue(start > capacity)
        assertTrue(
            toggle.substring(stop, capacity)
                .contains("GatewayCapacityProcessState.fenceSessionGeneration("),
        )
        assertTrue(toggle.substring(capacity, start).contains("newRawCollectionSessionAllowed"))
        assertTrue(toggle.substring(capacity, start).contains("return"))
        assertFalse(toggle.substring(active, capacity).contains("newRawCollectionSessionAllowed"))
    }

    @Test
    fun capacityBlocksOnlyAutomaticCandidatesAndNeverExplicitSafetyReports() {
        val prepare = functionBlock("private fun prepareReportCandidate(")
        val process = functionBlock("private fun processReportCandidate(")
        val explicit = functionBlock("private fun requestExplicitReport()")
        val explicitPreparation = functionBlock(
            "private fun prepareExplicitReportCandidateIfCurrent(",
        )
        val capacityGate = prepare.indexOf("automaticReportCandidateAllowed")

        assertTrue(capacityGate >= 0)
        assertTrue(
            prepare.substring(0, capacityGate).contains("!explicitRequest"),
        )
        assertTrue(prepare.contains("reportCandidate=blocked:gateway_capacity"))
        assertTrue(explicitPreparation.contains("explicitRequest = true"))
        assertFalse(explicit.contains("GatewayCapacityProcessState"))
        val revalidation = process.indexOf("gatewaySessionClient.revalidate(")
        val refreshedCapacity = process.indexOf(
            "GatewayCapacityProcessState.admission()",
        )
        val automaticUploadGate = process.indexOf(
            "transferPurpose == ReportTransferPurpose.AUTOMATIC",
            revalidation,
        )
        val upload = process.indexOf("uploadCall.execute()")
        assertTrue(revalidation >= 0)
        assertTrue(automaticUploadGate > revalidation)
        assertTrue(refreshedCapacity > revalidation)
        assertTrue(upload > refreshedCapacity)
        assertTrue(
            process.substring(revalidation, refreshedCapacity)
                .contains("GatewayCapacityProcessState.fenceSessionGeneration("),
        )
        assertTrue(
            process.substring(automaticUploadGate, upload)
                .contains("transferPurpose == ReportTransferPurpose.AUTOMATIC"),
        )
        assertTrue(
            process.substring(automaticUploadGate, upload)
                .contains(".automaticReportCandidateAllowed"),
        )
        assertTrue(process.substring(automaticUploadGate, upload).contains("uploadCall.cancel()"))
        assertTrue(process.substring(automaticUploadGate, upload).contains("return@execute"))
        assertTrue(capacitySource.contains("explicitSafetyReportAllowed: Boolean = true"))
        assertTrue(capacitySource.contains("activeSafetyFeaturesAllowed: Boolean = true"))
        assertTrue(capacitySource.contains("activeRawSessionStopAllowed: Boolean = true"))
        assertTrue(
            prepare.substring(0, capacityGate)
                .contains("GatewayCapacityProcessState.fenceSessionGeneration("),
        )
    }

    @Test
    fun everyActivityRevalidationCarriesTheCapturedSessionGeneration() {
        val callMarker = "gatewaySessionClient.revalidate("
        val calls = source.split(callMarker).drop(1)

        assertTrue(calls.isNotEmpty())
        calls.forEach { suffix ->
            val call = suffix.substringBefore(")\n")
            assertTrue(
                "revalidation must carry the exact capacity session generation",
                call.contains("capacitySessionGeneration ="),
            )
        }
        assertTrue(
            functionBlock("private fun performDestinationSearch(")
                .contains("expectedGatewaySessionGeneration"),
        )
        assertTrue(
            functionBlock("private fun requestRoute(")
                .contains("expectedGatewaySessionGeneration"),
        )
    }

    @Test
    fun participantRestrictionRemainsASignalAndIsNotAnActivityAdmissionGate() {
        val toggle = functionBlock("private fun toggleFieldSessionLog()")
        val prepare = functionBlock("private fun prepareReportCandidate(")

        assertTrue(capacitySource.contains("participantAdmissionRestrictedSignal"))
        assertTrue(source.contains("participant_admission_restricted"))
        assertFalse(toggle.contains("participantAdmissionRestrictedSignal"))
        assertFalse(prepare.contains("participantAdmissionRestrictedSignal"))
    }

    private fun functionBlock(marker: String): String = block(source, marker)

    private fun sessionFunctionBlock(marker: String): String = block(sessionSource, marker)

    private fun block(text: String, marker: String): String {
        val start = text.indexOf(marker)
        assertTrue("missing source marker: $marker", start >= 0)
        val bodyStart = text.indexOf('{', start)
        assertTrue("missing function body: $marker", bodyStart >= 0)
        var depth = 0
        for (index in bodyStart until text.length) {
            when (text[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        throw AssertionError("unterminated function body: $marker")
    }
}
