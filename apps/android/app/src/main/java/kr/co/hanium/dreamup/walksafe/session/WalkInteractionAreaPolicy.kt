package kr.co.hanium.dreamup.walksafe.session

/** UI/voice ownership derived from session facts, never from the visible page. */
internal object WalkInteractionAreaPolicy {
    enum class Area { MAIN, WALK }
    fun resolve(state: WalkSessionState, destinationSelected: Boolean,
                preparationPending: Boolean, ending: Boolean): Area = when {
        ending -> Area.WALK
        state in setOf(WalkSessionState.ACTIVE, WalkSessionState.PAUSED, WalkSessionState.SAFE_STOP) -> Area.WALK
        destinationSelected || preparationPending -> Area.WALK
        else -> Area.MAIN
    }
}
