package kr.co.hanium.dreamup.walksafe.network

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.session.allowsPostWalkTransfer
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.URL
import java.net.URLConnection

enum class MobileNetworkPreference(val wireValue: String) {
    WIFI_ONLY("wifi_only"),
    ALLOW_CELLULAR("allow_cellular"),
    ;

    companion object {
        fun fromWireValue(value: String?): MobileNetworkPreference =
            entries.firstOrNull { it.wireValue == value } ?: WIFI_ONLY
    }
}

enum class ActiveNetworkTransport {
    OFFLINE,
    WIFI,
    CELLULAR,
    OTHER,
}

enum class ActivityOriginalMotionState {
    UNKNOWN,
    WALKING,
    STATIONARY,
}

data class ActivityOriginalStationarySnapshot(
    val stepCount: Int,
    val observedAtMs: Long,
)

enum class ActivityOriginalUploadDecision(val uploadAllowed: Boolean) {
    BLOCKED_WHILE_WALKING(false),
    WIFI_ALLOWED(true),
    APPROVED_CELLULAR_ALLOWED(true),
    QUEUED_UNTIL_WIFI(false),
    /** FP-035-DETAIL-04 interim (owner decision 3(b)): after the walk, waits until charging. */
    QUEUED_UNTIL_CHARGING(false),
    FAIL_CLOSED(false),
}

class ActivityOriginalMotionEvidence(
    private val stationaryConfirmationMs: Long = 5_000L,
) {
    private var observationStarted = false
    private var lastMovementAtMs: Long? = null
    private var lastStepCount: Int? = null

    init {
        require(stationaryConfirmationMs > 0L)
    }

    @Synchronized
    fun beginObservation() {
        observationStarted = true
        lastMovementAtMs = null
        lastStepCount = null
    }

    @Synchronized
    fun observeStepCount(stepCount: Int, observedAtMs: Long): Boolean {
        if (stepCount < 0 || observedAtMs < 0L) {
            reset()
            return true
        }
        if (!observationStarted) return true
        val previousStepCount = lastStepCount
        if (previousStepCount == null) {
            lastStepCount = stepCount
            lastMovementAtMs = observedAtMs
            return true
        }
        val previousMovementAtMs = lastMovementAtMs
        if (
            previousMovementAtMs == null ||
            observedAtMs < previousMovementAtMs ||
            stepCount < previousStepCount
        ) {
            lastStepCount = stepCount
            lastMovementAtMs = observedAtMs
            return true
        }
        lastStepCount = stepCount
        val movementObserved = stepCount > previousStepCount
        if (movementObserved) lastMovementAtMs = observedAtMs
        return movementObserved
    }

    @Synchronized
    fun motionStateAt(observedAtMs: Long): ActivityOriginalMotionState {
        if (!observationStarted) return ActivityOriginalMotionState.UNKNOWN
        val movementAtMs = lastMovementAtMs
            ?: return ActivityOriginalMotionState.UNKNOWN
        if (observedAtMs < movementAtMs) {
            return ActivityOriginalMotionState.UNKNOWN
        }
        return if (observedAtMs - movementAtMs >= stationaryConfirmationMs) {
            ActivityOriginalMotionState.STATIONARY
        } else {
            ActivityOriginalMotionState.WALKING
        }
    }

    @Synchronized
    fun stationarySnapshotAt(observedAtMs: Long): ActivityOriginalStationarySnapshot? {
        if (!observationStarted || observedAtMs < 0L) return null
        val stepCount = lastStepCount ?: return null
        val movementAtMs = lastMovementAtMs ?: return null
        if (
            observedAtMs < movementAtMs ||
            observedAtMs - movementAtMs < stationaryConfirmationMs
        ) {
            return null
        }
        return ActivityOriginalStationarySnapshot(stepCount, observedAtMs)
    }

    @Synchronized
    fun reset() {
        observationStarted = false
        lastMovementAtMs = null
        lastStepCount = null
    }
}

object AndroidActivityOriginalUploadPolicy {
    fun effectiveMotionState(
        sessionActive: Boolean,
        observedMotionState: ActivityOriginalMotionState,
    ): ActivityOriginalMotionState =
        if (sessionActive) observedMotionState else ActivityOriginalMotionState.UNKNOWN

    fun decide(
        motionState: ActivityOriginalMotionState,
        preference: MobileNetworkPreference,
        transport: ActiveNetworkTransport,
    ): ActivityOriginalUploadDecision {
        when (motionState) {
            ActivityOriginalMotionState.UNKNOWN ->
                return ActivityOriginalUploadDecision.FAIL_CLOSED
            ActivityOriginalMotionState.WALKING ->
                return ActivityOriginalUploadDecision.BLOCKED_WHILE_WALKING
            ActivityOriginalMotionState.STATIONARY -> Unit
        }
        return when (transport) {
            ActiveNetworkTransport.WIFI ->
                ActivityOriginalUploadDecision.WIFI_ALLOWED
            ActiveNetworkTransport.CELLULAR ->
                if (preference == MobileNetworkPreference.ALLOW_CELLULAR) {
                    ActivityOriginalUploadDecision.APPROVED_CELLULAR_ALLOWED
                } else {
                    ActivityOriginalUploadDecision.QUEUED_UNTIL_WIFI
                }
            ActiveNetworkTransport.OFFLINE,
            ActiveNetworkTransport.OTHER,
            -> ActivityOriginalUploadDecision.FAIL_CLOSED
        }
    }

    /**
     * Owner decision 3(b): the admission rule for DEBUG activity originals. A walk in progress blocks
     * (FP-035-DETAIL-01 interim), then consent, charging (unknown = not charging) and the network:
     * transfer-F4, originals and training material after the walk go on Wi-Fi only
     * (FP-035-DETAIL-04 interim, same as RAW), so cellular waits for Wi-Fi even when the user
     * allowed mobile data for other transfers.
     */
    fun decideAfterWalk(
        walkState: WalkSessionState,
        consentAllowed: Boolean,
        charging: () -> Boolean,
        preference: MobileNetworkPreference,
        transport: ActiveNetworkTransport,
    ): ActivityOriginalUploadDecision {
        if (!walkState.allowsPostWalkTransfer) return ActivityOriginalUploadDecision.BLOCKED_WHILE_WALKING
        if (!consentAllowed) return ActivityOriginalUploadDecision.FAIL_CLOSED
        if (!runCatching(charging).getOrDefault(false)) {
            return ActivityOriginalUploadDecision.QUEUED_UNTIL_CHARGING
        }
        if (transport == ActiveNetworkTransport.CELLULAR) return ActivityOriginalUploadDecision.QUEUED_UNTIL_WIFI
        return decide(ActivityOriginalMotionState.STATIONARY, preference, transport)
    }

    fun shouldCancelInFlight(
        previous: ActivityOriginalMotionState,
        current: ActivityOriginalMotionState,
    ): Boolean = when {
        current == ActivityOriginalMotionState.STATIONARY -> false
        previous == ActivityOriginalMotionState.STATIONARY -> true
        else -> true
    }
}

/**
 * Owner decision 3(b) (2026-09-29) / ADD-MOTION-043: DEBUG activity originals (frame and metadata
 * captures) follow the RAW rule. A transfer is admitted only after the walk (ENDED or READY),
 * while charging (FP-035-DETAIL-04 interim; unknown = not charging), on Wi-Fi only (transfer-F4). Once the walk is over no
 * stillness evidence is consulted: the positioning estimator has no input then, and the physical
 * stillness gate would fail every post-walk transfer closed. A walk that becomes ACTIVE or PAUSED
 * again closes admission and cancels what is in flight ([onWalkStateChanged]).
 */
class ActivityOriginalUploadAdmissionController {
    private val lock = Any()
    private var admissionClosed = true
    private var admissionGeneration = 0L

    fun onWalkStateChanged(
        state: WalkSessionState,
        cancelActiveUploads: () -> Unit,
    ) = synchronized(lock) {
        if (!state.allowsPostWalkTransfer) closeAndCancelLocked(cancelActiveUploads)
    }

    fun isAllowed(
        walkState: WalkSessionState,
        consentAllowed: Boolean,
        charging: () -> Boolean,
        preference: MobileNetworkPreference,
        transport: ActiveNetworkTransport,
    ): Boolean = synchronized(lock) {
        decisionLocked(walkState, consentAllowed, charging, preference, transport).uploadAllowed
    }

    fun admit(
        walkState: WalkSessionState,
        consentAllowed: Boolean,
        charging: () -> Boolean,
        preference: MobileNetworkPreference,
        transport: ActiveNetworkTransport,
        action: () -> Unit,
    ): Boolean = synchronized(lock) {
        val decision = decisionLocked(walkState, consentAllowed, charging, preference, transport)
        if (!decision.uploadAllowed) {
            admissionClosed = true
            return@synchronized false
        }
        val admittedGeneration = admissionGeneration
        admissionClosed = false
        action()
        !admissionClosed && admissionGeneration == admittedGeneration
    }

    private fun decisionLocked(
        walkState: WalkSessionState,
        consentAllowed: Boolean,
        charging: () -> Boolean,
        preference: MobileNetworkPreference,
        transport: ActiveNetworkTransport,
    ): ActivityOriginalUploadDecision = AndroidActivityOriginalUploadPolicy.decideAfterWalk(
        walkState = walkState,
        consentAllowed = consentAllowed,
        // Read only after the walk and consent gates passed (no battery query per walk frame).
        charging = charging,
        preference = preference,
        transport = transport,
    )

    private fun closeAndCancelLocked(cancelActiveUploads: () -> Unit) {
        admissionClosed = true
        admissionGeneration += 1L
        cancelActiveUploads()
    }
}

object AndroidNetworkTransferPolicy {
    fun isAllowed(
        preference: MobileNetworkPreference,
        transport: ActiveNetworkTransport,
    ): Boolean = when (transport) {
        ActiveNetworkTransport.OFFLINE -> false
        ActiveNetworkTransport.CELLULAR ->
            preference == MobileNetworkPreference.ALLOW_CELLULAR
        ActiveNetworkTransport.WIFI,
        ActiveNetworkTransport.OTHER,
        -> true
    }
}

class IntegratedConsentNetworkBinding private constructor(
    val transport: IntegratedConsentNetworkTransport,
    private val networkIdentity: Any,
    private val connectionOpener: (URL) -> URLConnection,
) {
    internal fun openConnection(url: URL): URLConnection = connectionOpener(url)

    internal fun isSameNetworkBinding(other: IntegratedConsentNetworkBinding): Boolean =
        transport == other.transport && networkIdentity === other.networkIdentity

    companion object {
        internal fun fromNetwork(
            network: Network,
            transport: IntegratedConsentNetworkTransport,
        ): IntegratedConsentNetworkBinding =
            IntegratedConsentNetworkBinding(transport, network, network::openConnection)

        internal fun forTest(
            transport: IntegratedConsentNetworkTransport,
            connectionOpener: (URL) -> URLConnection,
        ): IntegratedConsentNetworkBinding =
            IntegratedConsentNetworkBinding(transport, Any(), connectionOpener)
    }
}

class AndroidNetworkStateProbe(context: Context) {
    private val connectivityManager =
        context.getSystemService(ConnectivityManager::class.java)

    fun currentTransport(): ActiveNetworkTransport {
        return currentNetwork()?.second ?: ActiveNetworkTransport.OFFLINE
    }

    fun currentIntegratedConsentBinding(): IntegratedConsentNetworkBinding? {
        val (network, transport) = currentNetwork() ?: return null
        val consentTransport = when (transport) {
            ActiveNetworkTransport.WIFI -> IntegratedConsentNetworkTransport.WIFI
            ActiveNetworkTransport.CELLULAR -> IntegratedConsentNetworkTransport.CELLULAR
            ActiveNetworkTransport.OFFLINE,
            ActiveNetworkTransport.OTHER,
            -> return null
        }
        return IntegratedConsentNetworkBinding.fromNetwork(network, consentTransport)
    }

    private fun currentNetwork(): Pair<Network, ActiveNetworkTransport>? {
        val manager = connectivityManager ?: return null
        val network = manager.activeNetwork ?: return null
        val capabilities =
            manager.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return null
        }
        val transport = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                ActiveNetworkTransport.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                ActiveNetworkTransport.CELLULAR
            else -> ActiveNetworkTransport.OTHER
        }
        return network to transport
    }
}
