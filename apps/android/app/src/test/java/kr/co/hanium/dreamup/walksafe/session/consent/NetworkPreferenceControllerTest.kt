package kr.co.hanium.dreamup.walksafe.session.consent

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.MobileNetworkPreference
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionPolicy
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkPreferenceControllerTest {
    @Test
    fun constructionDoesNotReadPolicyPlatformOrPublishEffects() {
        val harness = Harness()
        assertTrue(harness.events.isEmpty())
        assertTrue(harness.statuses.isEmpty())
        assertTrue(harness.speeches.isEmpty())
        assertEquals(0, harness.networkGeneration)
    }

    @Test
    fun unchangedPairDoesNotMarkNotifyOrScheduleVoiceCancellationAgain() {
        val harness = Harness()
        harness.controller.refreshGatewayFeatureNetworkPolicy()
        assertEquals(
            listOf("destroyed", "policy", "probe-ready", "transport", "mark-voice", "report-ready", "report", "schedule-voice"),
            harness.events.toList(),
        )
        assertEquals(1, harness.networkGeneration)
        harness.events.clear()
        harness.controller.refreshGatewayFeatureNetworkPolicy()
        assertEquals(listOf("destroyed", "policy", "probe-ready", "transport"), harness.events.toList())
        assertEquals(1, harness.networkGeneration)
        assertEquals(1, harness.voiceChecks.size)
    }

    @Test
    fun changedPairMarksBeforeReportAndDelegatesLatestVoiceTicketCheckAfterReport() {
        val harness = Harness()
        harness.controller.refreshGatewayFeatureNetworkPolicy()
        harness.runVoiceChecks()
        harness.events.clear()
        harness.activeVoiceTicket = harness.networkGeneration
        harness.transport = ActiveNetworkTransport.CELLULAR
        harness.controller.refreshGatewayFeatureNetworkPolicy()
        assertEquals(listOf("destroyed", "policy", "probe-ready", "transport", "mark-voice", "report-ready", "report", "schedule-voice"), harness.events.toList())
        assertFalse(harness.events.contains("cancel-voice"))
        harness.runVoiceChecks()
        assertEquals("cancel-voice", harness.events.last())

        // A new interaction can replace the old one before the existing scheduled check runs.
        harness.events.clear()
        harness.transport = ActiveNetworkTransport.WIFI
        harness.controller.refreshGatewayFeatureNetworkPolicy()
        harness.activeVoiceTicket = harness.networkGeneration
        harness.runVoiceChecks()
        assertFalse(harness.events.contains("cancel-voice"))
    }

    @Test
    fun destroyedRefreshDoesNotReadPreferenceTransportOrEffects() {
        val harness = Harness()
        harness.destroyed = true
        harness.controller.refreshGatewayFeatureNetworkPolicy()
        assertEquals(listOf("destroyed"), harness.events.toList())
        assertEquals(0, harness.networkGeneration)
        // Destruction guards refresh only; the two original query methods keep their own behavior.
        assertTrue(harness.controller.isGatewayFeatureNetworkAllowed())
    }

    @Test
    fun absentReportControllerSkipsReportWithoutSkippingVoiceInvalidation() {
        val harness = Harness()
        harness.reportReady = false
        harness.controller.refreshGatewayFeatureNetworkPolicy()
        assertEquals(listOf("destroyed", "policy", "probe-ready", "transport", "mark-voice", "report-ready", "schedule-voice"), harness.events.toList())
    }

    @Test
    fun featureObservationReadsPreferenceBeforeTransportAndAdmissionDoesTheReverse() {
        val harness = Harness()
        assertEquals(MobileNetworkPreference.WIFI_ONLY to ActiveNetworkTransport.WIFI, harness.controller.gatewayFeatureNetworkState())
        assertEquals(listOf("policy", "probe-ready", "transport"), harness.events.toList())
        harness.events.clear()
        assertTrue(harness.controller.isGatewayNetworkAllowed("server_speech"))
        assertEquals(listOf("probe-ready", "transport", "policy"), harness.events.toList())
    }

    @Test
    fun policyInstanceReplacementIsReadAtEachOperationsOriginalSamplingPoint() {
        val harness = Harness()
        harness.transport = ActiveNetworkTransport.CELLULAR
        harness.onTransportRead = {
            harness.policy = policy(MobileNetworkPreference.ALLOW_CELLULAR)
        }
        assertEquals(MobileNetworkPreference.WIFI_ONLY to ActiveNetworkTransport.CELLULAR, harness.controller.gatewayFeatureNetworkState())
        assertTrue(harness.controller.isGatewayFeatureNetworkAllowed())
        harness.policy = policy(MobileNetworkPreference.WIFI_ONLY)
        assertTrue(harness.controller.isGatewayNetworkAllowed("server_speech"))
        assertTrue(harness.statuses.isEmpty())
        assertTrue(harness.speeches.isEmpty())
    }

    @Test
    fun samePairOnAReplacementPolicyDoesNotEmitAChangeButNewPreferenceDoes() {
        val harness = Harness()
        harness.controller.refreshGatewayFeatureNetworkPolicy()
        harness.events.clear()
        harness.policy = policy(MobileNetworkPreference.WIFI_ONLY)
        harness.controller.refreshGatewayFeatureNetworkPolicy()
        assertFalse(harness.events.contains("mark-voice"))
        harness.events.clear()
        harness.policy = policy(MobileNetworkPreference.ALLOW_CELLULAR)
        harness.controller.refreshGatewayFeatureNetworkPolicy()
        assertTrue(harness.events.contains("mark-voice"))
        assertEquals(2, harness.networkGeneration)
    }

    @Test
    fun uninitializedProbeReadsNoTransportAndUsesOfflineForBothMethods() {
        val harness = Harness()
        harness.probeReady = false
        assertEquals(MobileNetworkPreference.WIFI_ONLY to ActiveNetworkTransport.OFFLINE, harness.controller.gatewayFeatureNetworkState())
        assertEquals(listOf("policy", "probe-ready"), harness.events.toList())
        harness.events.clear()
        assertFalse(harness.controller.isGatewayNetworkAllowed("server_speech"))
        assertEquals(listOf("probe-ready", "policy", "debug-bypass", "surface", "main-thread", "status", "speech"), harness.events.toList())
        assertEquals("network=blocked reason=server_speech transport=offline preference=wifi_only", harness.statuses.single())
        assertEquals("인터넷 연결을 확인한 뒤 다시 시도하세요.", harness.speeches.single())
    }

    @Test
    fun transferTruthTableIsSharedWithoutApplyingDebugBypassToFeatureQueries() {
        for (preference in MobileNetworkPreference.entries) {
            for (transport in ActiveNetworkTransport.entries) {
                val harness = Harness()
                harness.policy = policy(preference)
                harness.transport = transport
                harness.debugBypass = true
                val expected = when (transport) {
                    ActiveNetworkTransport.OFFLINE -> false
                    ActiveNetworkTransport.CELLULAR -> preference == MobileNetworkPreference.ALLOW_CELLULAR
                    ActiveNetworkTransport.WIFI, ActiveNetworkTransport.OTHER -> true
                }
                assertEquals("$preference / $transport", expected, harness.controller.isGatewayFeatureNetworkAllowed())
                assertFalse(harness.events.contains("debug-bypass"))
                assertEquals("$preference / $transport", expected, harness.controller.isGatewayNetworkAllowed("server_speech", announce = false))
                if (expected) assertTrue(harness.statuses.isEmpty())
                assertTrue(harness.speeches.isEmpty())
            }
        }
    }

    @Test
    fun debugAdmissionBypassesOnlyExactSearchAndRouteReasonsAndNeverOffline() {
        for (reason in listOf("destination_search", "walking_route", "server_speech", "raw_collection", "Destination_search", "walking_route ")) {
            val harness = Harness()
            harness.debugBypass = true
            harness.transport = ActiveNetworkTransport.CELLULAR
            val allowed = reason == "destination_search" || reason == "walking_route"
            assertEquals(reason, allowed, harness.controller.isGatewayNetworkAllowed(reason, announce = false))
            assertEquals(if (allowed) 0 else 1, harness.statuses.size)
        }
        for (reason in listOf("destination_search", "walking_route")) {
            val harness = Harness()
            harness.debugBypass = true
            harness.transport = ActiveNetworkTransport.OFFLINE
            assertFalse(harness.controller.isGatewayNetworkAllowed(reason))
            assertEquals(1, harness.statuses.size)
        }
    }

    @Test
    fun blockedStatusAndSpeechArePostedWithTheCapturedAdmissionPair() {
        val harness = Harness()
        harness.transport = ActiveNetworkTransport.CELLULAR
        harness.mainThread = false
        assertFalse(harness.controller.isGatewayNetworkAllowed("server_speech"))
        assertTrue(harness.statuses.isEmpty())
        assertTrue(harness.speeches.isEmpty())
        assertEquals("post-ui", harness.events.last())
        harness.policy = policy(MobileNetworkPreference.ALLOW_CELLULAR)
        harness.transport = ActiveNetworkTransport.WIFI
        harness.runUiActions()
        assertEquals("network=blocked reason=server_speech transport=cellular preference=wifi_only", harness.statuses.single())
        assertEquals("이동통신망 사용이 꺼져 있어 서버 기능을 시작하지 않았습니다.", harness.speeches.single())
        assertEquals(listOf("status", "speech"), harness.events.takeLast(2))
    }

    @Test
    fun absentSurfacePublishesImmediatelyWithoutQueryingThreadAndSilenceKeepsStatus() {
        val harness = Harness()
        harness.probeReady = false
        harness.surfaceReady = false
        harness.mainThread = false
        assertFalse(harness.controller.isGatewayNetworkAllowed("walking_route", announce = false))
        assertFalse(harness.events.contains("main-thread"))
        assertFalse(harness.events.contains("post-ui"))
        assertEquals(1, harness.statuses.size)
        assertTrue(harness.speeches.isEmpty())
    }

    @Test
    fun pairIsStoredBeforeTheVoiceMarkPortCanReenterRefresh() {
        val harness = Harness()
        harness.onVoiceMark = { harness.controller.refreshGatewayFeatureNetworkPolicy() }
        harness.controller.refreshGatewayFeatureNetworkPolicy()
        assertEquals(1, harness.networkGeneration)
        assertEquals(1, harness.events.count { it == "report" })
        assertEquals(1, harness.events.count { it == "schedule-voice" })
    }

    @Test
    fun concurrentSamePairRefreshesInvalidateVoiceOnlyOnce() {
        val harness = Harness()
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val workers = List(8) {
            Thread {
                try {
                    start.await()
                    harness.controller.refreshGatewayFeatureNetworkPolicy()
                } finally {
                    done.countDown()
                }
            }.apply { start() }
        }
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        workers.forEach { it.join() }
        assertEquals(1, harness.networkGeneration)
        assertEquals(1, harness.events.count { it == "report" })
        assertEquals(1, harness.events.count { it == "schedule-voice" })
    }

    private class Harness : NetworkPreferenceController.Ports {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val statuses = mutableListOf<String>()
        val speeches = mutableListOf<String>()
        val uiActions = mutableListOf<() -> Unit>()
        val voiceChecks = mutableListOf<() -> Unit>()
        var policy = policy(MobileNetworkPreference.WIFI_ONLY)
        var transport = ActiveNetworkTransport.WIFI
        var probeReady = true
        var destroyed = false
        var reportReady = true
        var debugBypass = false
        var surfaceReady = true
        var mainThread = true
        var networkGeneration = 0
        var activeVoiceTicket: Int? = null
        var onTransportRead: () -> Unit = {}
        var onVoiceMark: () -> Unit = {}
        val controller = NetworkPreferenceController(
            permissionSessionPolicy = { events += "policy"; policy },
            ports = this,
        )

        override fun isDestroyed(): Boolean { events += "destroyed"; return destroyed }
        override fun isNetworkProbeReady(): Boolean { events += "probe-ready"; return probeReady }
        override fun currentTransport(): ActiveNetworkTransport {
            events += "transport"
            onTransportRead()
            return transport
        }
        override fun developmentGuidanceStartBypassEnabled(): Boolean { events += "debug-bypass"; return debugBypass }
        override fun isSurfaceInitialized(): Boolean { events += "surface"; return surfaceReady }
        override fun isMainThread(): Boolean { events += "main-thread"; return mainThread }
        override fun runOnUiThread(action: () -> Unit) { events += "post-ui"; uiActions += action }
        override fun markVoiceNetworkPolicyChanged() {
            events += "mark-voice"
            networkGeneration += 1
            onVoiceMark()
        }
        override fun isUserReportControllerReady(): Boolean { events += "report-ready"; return reportReady }
        override fun userReportNetworkPolicyChanged() { events += "report" }
        override fun cancelVoiceForNetworkPolicyIfNeeded() {
            events += "schedule-voice"
            voiceChecks += {
                val active = activeVoiceTicket
                if (active != null && active != networkGeneration) {
                    events += "cancel-voice"
                    activeVoiceTicket = null
                }
            }
        }
        override fun navigationStatus(message: String) { events += "status"; statuses += message }
        override fun speakInteraction(message: String) { events += "speech"; speeches += message }
        fun runUiActions() { uiActions.toList().also { uiActions.clear() }.forEach { it() } }
        fun runVoiceChecks() { voiceChecks.toList().also { voiceChecks.clear() }.forEach { it() } }
    }

    private companion object {
        fun policy(preference: MobileNetworkPreference) = PermissionSessionPolicy(
            PermissionSessionSnapshot(mobileNetworkPreference = preference),
        )
    }
}
