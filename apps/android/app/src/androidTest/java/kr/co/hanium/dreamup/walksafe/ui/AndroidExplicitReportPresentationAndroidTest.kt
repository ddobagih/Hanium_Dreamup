package kr.co.hanium.dreamup.walksafe.ui

import android.app.AlertDialog
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.device.RuntimeCalibrationTestHostActivity
import kr.co.hanium.dreamup.walksafe.report.REPORT_EXPLICIT_CONFIRMATION_DISCLOSURE_KO
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real ViewTreeObserver/scroll/window checks in the existing empty debug host.
 * No MainActivity, account, permission, camera, report payload or queue state is injected or opened.
 * Component delivery is separate from a user's second confirmation and report submission.
 */
@RunWith(AndroidJUnit4::class)
class AndroidExplicitReportPresentationAndroidTest {
    @Test(timeout = 30_000L)
    fun actualClippedNoticeIsRevealedBeforeItsSingleDrawReceipt() {
        ActivityScenario.launch(RuntimeCalibrationTestHostActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            scenario.onActivity { fixture = Fixture(it, belowFold = true) }
            await(scenario) { fixture.text.isAttachedToWindow && fixture.text.height > 0 && it.hasWindowFocus() }
            scenario.onActivity {
                assertFalse(fixture.text.getGlobalVisibleRect(Rect()))
                assertFalse(fixture.button.isEnabled)
            }
            val delivered = CountDownLatch(1)
            val count = AtomicInteger()
            scenario.onActivity {
                assertTrue(fixture.adapter.deliverAfterDraw { count.incrementAndGet(); delivered.countDown() })
                assertEquals(0, count.get())
            }
            assertTrue("Actual visible draw receipt missing", delivered.await(5, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                val bounds = Rect()
                assertTrue(fixture.text.isShown && fixture.text.getGlobalVisibleRect(bounds))
                assertFalse(bounds.isEmpty)
                assertTrue(fixture.scroll.scrollY > 0)
                assertEquals(1, count.get())
                assertFalse("The draw adapter cannot confirm storage itself", fixture.button.isEnabled)
                assertTrue(fixture.text.text.toString().contains(REPORT_EXPLICIT_CONFIRMATION_DISCLOSURE_KO))
                assertEquals(fixture.text.text.toString(), fixture.text.contentDescription.toString())
                fixture.text.invalidate()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(1, count.get())
        }
    }

    @Test(timeout = 30_000L)
    fun actualDialogAndVisibleIdleMessageCannotAcknowledgeTheOldDisclosure() {
        ActivityScenario.launch(RuntimeCalibrationTestHostActivity::class.java).use { scenario ->
            lateinit var fixture: Fixture
            lateinit var dialog: AlertDialog
            scenario.onActivity { host ->
                fixture = Fixture(host, belowFold = false)
                dialog = AlertDialog.Builder(host).setMessage("표시 검사").setPositiveButton("닫기", null).show()
            }
            await(scenario) { !it.hasWindowFocus() && dialog.window?.decorView?.hasWindowFocus() == true }
            val count = AtomicInteger()
            scenario.onActivity {
                assertTrue(fixture.text.isShown)
                assertFalse(fixture.adapter.deliverAfterDraw { count.incrementAndGet() })
                dialog.dismiss()
            }
            await(scenario) { it.hasWindowFocus() }
            scenario.onActivity {
                assertTrue(fixture.adapter.deliverAfterDraw { count.incrementAndGet() })
                // Cancel before the next native preDraw; the replacement message stays visible.
                fixture.adapter.renderIdle("신고 전송 안내 시간이 지났습니다.")
                assertEquals(View.VISIBLE, fixture.text.visibility)
                fixture.text.invalidate()
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertEquals(0, count.get())
            scenario.onActivity {
                assertEquals("신고 전송 안내 시간이 지났습니다.", fixture.text.text.toString())
                assertEquals(fixture.text.text.toString(), fixture.text.contentDescription.toString())
            }
        }
    }

    private fun await(
        scenario: ActivityScenario<RuntimeCalibrationTestHostActivity>,
        condition: (RuntimeCalibrationTestHostActivity) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(20)
        }
        fail("Native layout/window condition did not become ready")
    }

    private class Fixture(host: RuntimeCalibrationTestHostActivity, belowFold: Boolean) {
        val text = TextView(host).apply { textSize = 18f }
        val button = Button(host)
        val scroll = ScrollView(host)
        val adapter: AndroidExplicitReportPresentation

        init {
            val body = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                if (belowFold) addView(View(host), LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, host.resources.displayMetrics.heightPixels * 2,
                ))
                addView(text, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                ))
                addView(button)
            }
            scroll.addView(body)
            host.setContentView(scroll)
            val target = AndroidExplicitReportPresentation.ViewTarget(button, text, host::hasWindowFocus)
            adapter = AndroidExplicitReportPresentation(
                targets = { target }, isMainThread = { true }, dispatch = { host.runOnUiThread(it) },
                queueAvailable = { true }, disclosureText = REPORT_EXPLICIT_CONFIRMATION_DISCLOSURE_KO,
                summary = "표시 검사", refreshHomeCards = {},
            )
            adapter.renderDisclosure(false)
        }
    }
}
