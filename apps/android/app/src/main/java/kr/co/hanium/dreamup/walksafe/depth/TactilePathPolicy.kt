package kr.co.hanium.dreamup.walksafe.depth

/**
 * Retained as a fail-closed compatibility seam for depth-only callers. Camera geometry alone may
 * not authorize path guidance; the active runtime uses navigation.TactileRoutePolicy instead.
 */
@Deprecated("Use navigation.TactileRoutePolicy with active TMAP route evidence")
class TactilePathPolicy {
    @Suppress("UNUSED_PARAMETER")
    fun buildGuidance(geometry: ObjectGeometry, stepLengthM: Float = 0.65f): UserFacingDepth {
        return UserFacingDepth(stepsAhead = null, messageLevel = MessageLevel.NONE, message = null)
    }
}
