package kr.co.hanium.dreamup.walksafe.ui

import android.graphics.Rect
import android.os.SystemClock
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import kr.co.hanium.dreamup.walksafe.device.RuntimeCalibrationTestHostActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 실제 카드의 접근성 노드와 활성화 동작을 확인한다. Main·음성·계정은 열지 않는다. */
@RunWith(AndroidJUnit4::class)
class HomePanelCardAvailabilityAndroidTest {
    @Test(timeout = 30_000L)
    fun accessibleCardsFollowEntryAvailabilityAcrossRuntimeChanges() {
        ActivityScenario.launch(RuntimeCalibrationTestHostActivity::class.java).use { scenario ->
            lateinit var panel: HomePanel
            lateinit var voice: Button
            lateinit var search: Button
            var voiceOpens = 0
            var searchOpens = 0
            scenario.onActivity { host ->
                panel = HomePanel(host, WalkSafeViewStyle(host),
                    HomePanel.Props(showCameraTest = true, initialRouteStatus = ""),
                    HomePanel.Actions(onCamera = {}, onVoice = { voiceOpens++ },
                        onDestinationSearch = { searchOpens++ }, onSettings = {}))
                host.setContentView(panel.homeCardGrid, ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                val cards = (0 until panel.homeCardGrid.childCount).map {
                    panel.homeCardGrid.getChildAt(it) as Button
                }
                voice = cards.single { it.text == "음성 명령" }
                search = cards.single { it.text == "길라잡이" }
            }
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            var visible = false
            while (SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { host ->
                    visible = host.hasWindowFocus() && voice.getGlobalVisibleRect(Rect()) &&
                        search.getGlobalVisibleRect(Rect())
                }
                if (visible) break
                SystemClock.sleep(20L)
            }
            assertTrue("카드가 실제 창에 표시되어야 합니다", visible)
            scenario.onActivity {
                panel.renderAvailability(runtimeActive = false, voiceAvailable = false, searchAvailable = false)
                assertAccessibleCard(voice, "음성 명령. 음성으로 앱 기능을 사용합니다", enabled = true)
                assertAccessibleCard(search, "길라잡이. 목적지를 찾고 경로 안내를 시작합니다", enabled = true)
                assertTrue(voice.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertTrue(search.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(1, voiceOpens)
                assertEquals(1, searchOpens)

                panel.renderAvailability(runtimeActive = true, voiceAvailable = false, searchAvailable = false)
                assertAccessibleCard(voice, "음성 명령. 음성으로 앱 기능을 사용합니다. 잠김. 앱 내의 기능 사용 제한. 기기 장착 상태와 기기 상태를 확인해주세요.", enabled = false)
                assertAccessibleCard(search, "길라잡이. 목적지를 찾고 경로 안내를 시작합니다. 잠김. 앱 내의 기능 사용 제한. 기기 장착 상태와 기기 상태를 확인해주세요.", enabled = false)
                assertFalse(voice.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertFalse(search.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(1, voiceOpens)
                assertEquals(1, searchOpens)

                panel.renderAvailability(runtimeActive = true, voiceAvailable = true, searchAvailable = true)
                assertAccessibleCard(voice, "음성 명령. 음성으로 앱 기능을 사용합니다", enabled = true)
                assertAccessibleCard(search, "길라잡이. 목적지를 찾고 경로 안내를 시작합니다", enabled = true)
                assertTrue(voice.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertTrue(search.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null))
                assertEquals(2, voiceOpens)
                assertEquals(2, searchOpens)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun assertAccessibleCard(card: Button, description: String, enabled: Boolean) {
        val node = card.createAccessibilityNodeInfo()
        try {
            assertEquals(enabled, node.isEnabled)
            assertEquals(description, node.contentDescription.toString())
            assertEquals(enabled, card.isEnabled)
        } finally { node.recycle() }
    }
}
