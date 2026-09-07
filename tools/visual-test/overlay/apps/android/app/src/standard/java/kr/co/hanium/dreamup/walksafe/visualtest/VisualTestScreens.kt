package kr.co.hanium.dreamup.walksafe.visualtest

import android.app.Activity
import android.widget.FrameLayout
import kr.co.hanium.dreamup.walksafe.DebugBboxOverlayView
import kr.co.hanium.dreamup.walksafe.navigation.TrustedLocation
import kr.co.hanium.dreamup.walksafe.navigation.WalkingRoute

/** Production/debug variants have no visual test UI, map assets or camera observers. */
@Suppress("UNUSED_PARAMETER")
class VisualTestScreens(activity: Activity, root: FrameLayout, onVisibilityChanged: () -> Unit, refresh: () -> Unit) {
    val active = false
    val modelVisible = false
    fun openMap() = Unit
    fun openModel() = Unit
    fun close() = false
    fun updateRoute(route: WalkingRoute?, location: TrustedLocation?, destinationName: String?, instruction: String, running: Boolean) = Unit
    fun updateModel(mapped: List<DebugBboxOverlayView.DebugOverlayBox>, detail: String, ageMs: Long?) = Unit
    fun modelUnavailable(reason: String) = Unit
}
