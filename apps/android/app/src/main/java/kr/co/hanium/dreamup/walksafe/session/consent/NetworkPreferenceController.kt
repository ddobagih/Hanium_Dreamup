package kr.co.hanium.dreamup.walksafe.session.consent

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.network.ActiveNetworkTransport
import kr.co.hanium.dreamup.walksafe.network.AndroidNetworkTransferPolicy
import kr.co.hanium.dreamup.walksafe.network.MobileNetworkPreference
import kr.co.hanium.dreamup.walksafe.session.PermissionSessionPolicy

/** Owns network-policy change detection; the caller retains platform and voice interaction owners. */
internal class NetworkPreferenceController(
    private val permissionSessionPolicy: () -> PermissionSessionPolicy,
    private val ports: Ports,
) {
    interface Ports {
        fun isDestroyed(): Boolean
        fun isNetworkProbeReady(): Boolean
        fun currentTransport(): ActiveNetworkTransport
        fun developmentGuidanceStartBypassEnabled(): Boolean
        fun isSurfaceInitialized(): Boolean
        fun isMainThread(): Boolean
        fun runOnUiThread(action: () -> Unit)
        fun markVoiceNetworkPolicyChanged()
        fun isUserReportControllerReady(): Boolean
        fun userReportNetworkPolicyChanged()
        fun cancelVoiceForNetworkPolicyIfNeeded()
        fun navigationStatus(message: String)
        fun speakInteraction(message: String)
    }

    private val gatewayFeatureNetworkLock = Any()
    private var lastGatewayFeatureNetworkState:
        Pair<MobileNetworkPreference, ActiveNetworkTransport>? = null

    fun gatewayFeatureNetworkState(): Pair<MobileNetworkPreference, ActiveNetworkTransport> =
        permissionSessionPolicy().snapshot().mobileNetworkPreference to
            if (ports.isNetworkProbeReady()) ports.currentTransport()
            else ActiveNetworkTransport.OFFLINE

    fun isGatewayFeatureNetworkAllowed(): Boolean {
        val (preference, transport) = gatewayFeatureNetworkState()
        return AndroidNetworkTransferPolicy.isAllowed(preference, transport)
    }

    fun refreshGatewayFeatureNetworkPolicy() {
        if (ports.isDestroyed()) return
        val changed = synchronized(gatewayFeatureNetworkLock) {
            val current = gatewayFeatureNetworkState()
            if (lastGatewayFeatureNetworkState == current) {
                false
            } else {
                lastGatewayFeatureNetworkState = current
                ports.markVoiceNetworkPolicyChanged()
                true
            }
        }
        if (!changed) return
        if (ports.isUserReportControllerReady()) ports.userReportNetworkPolicyChanged()
        // The existing voice controller schedules its own latest-ticket check on the UI thread.
        ports.cancelVoiceForNetworkPolicyIfNeeded()
    }

    fun isGatewayNetworkAllowed(reason: String, announce: Boolean = true): Boolean {
        // Admission deliberately reads transport before preference, unlike feature-state observation.
        val transport = if (ports.isNetworkProbeReady()) {
            ports.currentTransport()
        } else {
            ActiveNetworkTransport.OFFLINE
        }
        val preference = permissionSessionPolicy().snapshot().mobileNetworkPreference
        if (AndroidNetworkTransferPolicy.isAllowed(preference, transport)) return true
        if (ports.developmentGuidanceStartBypassEnabled() &&
            reason in setOf("destination_search", "walking_route") &&
            transport != ActiveNetworkTransport.OFFLINE
        ) return true
        val publishBlockedState = {
            ports.navigationStatus(
                "network=blocked reason=$reason transport=${transport.name.lowercase(Locale.US)} " +
                    "preference=${preference.wireValue}",
            )
            if (announce) {
                ports.speakInteraction(
                    if (transport == ActiveNetworkTransport.CELLULAR) {
                        "이동통신망 사용이 꺼져 있어 서버 기능을 시작하지 않았습니다."
                    } else {
                        "인터넷 연결을 확인한 뒤 다시 시도하세요."
                    },
                )
            }
        }
        if (!ports.isSurfaceInitialized() || ports.isMainThread()) {
            publishBlockedState()
        } else {
            ports.runOnUiThread { publishBlockedState() }
        }
        return false
    }
}
