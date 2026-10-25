package kr.co.hanium.dreamup.walksafe.depth.unknown

import kr.co.hanium.dreamup.walksafe.inference.tracking.VisualMaterialPoint
import kr.co.hanium.dreamup.walksafe.inference.unknown.FastSamFrameToken

/** A measured point with the complete unrotated mask capture; a mask/track identity is not a point. */
data class UnknownMaterialPointObservation internal constructor(
    val token: FastSamFrameToken,
    val imageQuarterTurns: Int,
    val point: VisualMaterialPoint,
)
