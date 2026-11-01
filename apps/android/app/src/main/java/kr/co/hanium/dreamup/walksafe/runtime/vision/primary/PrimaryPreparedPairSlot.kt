package kr.co.hanium.dreamup.walksafe.runtime.vision.primary

import java.util.concurrent.CompletionStage
import kr.co.hanium.dreamup.walksafe.inference.AndroidDetectorLoadResult
import kr.co.hanium.dreamup.walksafe.inference.PrimaryTensorRelease

/** Receiver reservation. A host-creation ACK cannot substitute for actual model release. */
internal class PrimaryPreparedPairSlot internal constructor(
    internal val ownerKey: Any, internal val request: Long, internal val claim: PrimaryDetectorLoadClaim,
    internal val current: () -> Boolean,
) {
    internal enum class Phase { RESERVED, CREATED, ADOPTING, ADOPTED, RETIRED }
    internal var phase = Phase.RESERVED
    internal var load: AndroidDetectorLoadResult? = null
    internal var creationFinished = false
    internal var creationFailed = false
    internal var rollbackStarted = false
    private val release = PrimaryTensorRelease()
    val actualReleasedFuture: CompletionStage<Unit> get() = release.actualReleasedFuture
    val expectedGeneration: Long get() = claim.generation

    internal fun retainPhysical(proof: CompletionStage<Unit>) { release.follow(release.beforeOpen(), proof) }
    internal fun retainUnproven() { release.beforeOpen() }
    internal fun sealCreation() { creationFinished = true; release.seal() }
}
