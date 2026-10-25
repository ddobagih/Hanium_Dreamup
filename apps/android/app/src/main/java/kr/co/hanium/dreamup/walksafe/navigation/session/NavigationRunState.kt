package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint

/** One authority shared by acquisition and guidance; it never reads Android or starts work. */
internal class NavigationRunState(val navigator: RouteNavigator) {
    var destination: RoutePoint? = null
        private set
    @Volatile var active = false
        private set
    @Volatile var onRoute = false
        private set
    var directionPauseReason: String? = null
        private set
    var startStepCount: Int? = null
        private set

    fun selectDestination(destination: RoutePoint?) { this.destination = destination }
    fun selectPendingDestination(destination: RoutePoint) {
        this.destination = destination
        active = false
        onRoute = false
    }

    fun markRequestActive() { active = true }
    fun markInactive() { active = false }
    fun updateAcceptedRouteMatch(onRoute: Boolean) { this.onRoute = onRoute }
    /** A decision or explicit recheck invalidates the previous accepted route match. */
    fun pauseDirectionUntilRouteMatch(reason: String) {
        onRoute = false
        directionPauseReason = reason
    }

    fun pauseDirection(reason: String) { directionPauseReason = reason }
    fun clearDirectionPause() { directionPauseReason = null }
    fun recordRouteStartStep(step: Int?) { startStepCount = step }
}
