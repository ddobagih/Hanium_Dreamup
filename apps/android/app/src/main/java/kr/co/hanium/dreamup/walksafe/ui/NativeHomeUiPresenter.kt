package kr.co.hanium.dreamup.walksafe.ui

import android.widget.LinearLayout
import android.widget.TextView

/** Uses the existing home panel, published at the original grid/compass/status binding points. */
internal class NativeHomeUiPresenter {
    private lateinit var gridPanel: HomePanel
    private lateinit var compassPanel: HomePanel
    private lateinit var routePanel: HomePanel
    fun publishGridTarget(panel: HomePanel) { gridPanel = panel }
    fun publishCompassTarget(panel: HomePanel) { compassPanel = panel }
    fun publishRouteTarget(panel: HomePanel) { routePanel = panel }
    fun gridTarget(): LinearLayout = gridPanel.homeCardGrid
    fun compassTarget(): TextView = compassPanel.nativeHomeCompassText
    fun routeTarget(): TextView = routePanel.nativeHomeRouteStatusText
    fun renderRouteStatus(message: String) {
        val target = routeTarget()
        if (target.text.toString() != message) target.text = message
    }
}
