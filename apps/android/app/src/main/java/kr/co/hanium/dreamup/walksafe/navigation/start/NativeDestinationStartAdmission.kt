package kr.co.hanium.dreamup.walksafe.navigation.start

import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage

/** Captures a one-use native start's live authority; the workflow still owns the pending approval. */
internal class NativeDestinationStartAdmission(
    private val workflow: NativeWorkflowController,
    private val scope: Scope,
    private val source: PositioningSource,
) {
    class GatewayOwner(val generation: Long, val sessionIdentity: Any?)
    class Binding(
        val identity: Any,
        val actorId: String?,
        val gatewaySessionIdentity: Any?,
        val gatewayGeneration: Long,
        val currentCoordinatorAndCollection: () -> Boolean,
    )

    fun capture(destination: DestinationSearchResult): (() -> Boolean)? {
        if (workflow.pendingUiDestination !== destination || !scope.homeAvailable() || !scope.runtimeActive()) return null
        val epoch = scope.epoch()
        val gateway = scope.gatewayOwner()
        val actorId = scope.reporterId()
        val sensorRequest = workflow.nativePhoneMountingCheckRequest
        val permissionGate = scope.permissionRecoveryIdentity()
        val routeGeneration = scope.routeGeneration()
        var expectedBinding = source.binding()
        var expectedLocationGeneration = source.generation()
        fun currentSource(): Boolean {
            val binding = source.binding()
            if (expectedBinding == null) {
                if (binding == null) return source.generation() == expectedLocationGeneration
                if (source.generation() != expectedLocationGeneration + 1 || binding.actorId != actorId ||
                    binding.gatewaySessionIdentity !== gateway.sessionIdentity || binding.gatewayGeneration != gateway.generation) return false
                expectedBinding = binding
                expectedLocationGeneration = source.generation()
            }
            return binding?.identity === expectedBinding?.identity && source.generation() == expectedLocationGeneration &&
                binding != null && binding.currentCoordinatorAndCollection()
        }
        workflow.pendingNativeUiPage = NativeUiPage.DESTINATION_CONFIRM
        return {
            workflow.pendingNativeUiPage == NativeUiPage.DESTINATION_CONFIRM &&
                workflow.nativeUiPage in setOf(NativeUiPage.HOME, NativeUiPage.DESTINATION_CONFIRM, NativeUiPage.GUIDANCE) &&
                workflow.pendingUiDestination === destination && scope.homeAvailable() && scope.reporterId() == actorId &&
                workflow.nativePhoneMountingCheckRequest === sensorRequest && scope.permissionRecoveryIdentity() === permissionGate &&
                scope.epoch() == epoch && scope.gatewayOwner().let { it.generation == gateway.generation && it.sessionIdentity === gateway.sessionIdentity } &&
                scope.routeGeneration() == routeGeneration && scope.runtimeActive() && currentSource()
        }
    }

    interface Scope {
        fun homeAvailable(): Boolean
        fun runtimeActive(): Boolean
        fun epoch(): WalkRuntimeEpoch
        fun gatewayOwner(): GatewayOwner
        fun reporterId(): String?
        fun permissionRecoveryIdentity(): Any?
        fun routeGeneration(): Int
    }
    interface PositioningSource {
        fun binding(): Binding?
        fun generation(): Int
    }
}
