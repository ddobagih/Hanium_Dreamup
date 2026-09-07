package kr.co.hanium.dreamup.walksafe.session

import android.app.Activity
import android.app.Instrumentation
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

object DeviceTestAccountUiLogin {
    private enum class Stage {
        NOT_OBSERVED, ACTIVITY_UNAVAILABLE, WINDOW_UNFOCUSED, EMAIL_NOT_VISIBLE,
        PASSWORD_NOT_VISIBLE, LOGIN_NOT_VISIBLE, NEEDS_ACCOUNT_FIXTURE, LOGIN_DISABLED,
        WAITING_FOR_HOME, HOME_READY,
    }

    private enum class Outcome {
        DEADLINE_EXPIRED, HANDLER_POST_FAILED, HANDLER_TIMEOUT, OBSERVATION_FAILED, HOME_READY, TIMEOUT,
    }

    private fun report(outcome: Outcome, stage: Stage) {
        Log.i("WalkSafeDeviceTestLogin", "event=DEVICE_UI_LOGIN outcome=${outcome.name} stage=${stage.name}")
    }

    /** Call off the main thread; deadlineMs is an absolute elapsedRealtime deadline. */
    fun ensureLoggedIn(instrumentation: Instrumentation, activity: Activity, deadlineMs: Long) {
        if (InstrumentationRegistry.getArguments().getString("allowDeviceTestAccountUiLogin") != "true") return
        check(Looper.myLooper() != Looper.getMainLooper()) { "Device UI login requires the test thread." }
        val deadline = minOf(deadlineMs, SystemClock.elapsedRealtime() + 20_000L)
        var stage = Stage.NOT_OBSERVED
        if (SystemClock.elapsedRealtime() >= deadline) {
            report(Outcome.DEADLINE_EXPIRED, stage)
            return
        }
        val homeReady = activity.javaClass.getDeclaredMethod("nativeHomeFeatureContextAvailable")
            .apply { isAccessible = true }
        val main = Handler(Looper.getMainLooper())
        var credentials: Pair<String, String>? = null
        var submitted = false
        while (SystemClock.elapsedRealtime() < deadline) {
            val done = CountDownLatch(1)
            var ready = false
            var needsCredentials = false
            var observationFailed = false
            val observe = Runnable {
                try {
                    if (SystemClock.elapsedRealtime() >= deadline || activity.isFinishing || activity.isDestroyed) {
                        if (activity.isFinishing || activity.isDestroyed) stage = Stage.ACTIVITY_UNAVAILABLE
                        return@Runnable
                    }
                    ready = homeReady.invoke(activity) == true
                    if (ready || submitted || !activity.hasWindowFocus()) {
                        stage = when {
                            ready -> Stage.HOME_READY
                            submitted -> Stage.WAITING_FOR_HOME
                            else -> Stage.WINDOW_UNFOCUSED
                        }
                        return@Runnable
                    }
                    val views = descendants(activity.window.decorView).filter(::visible)
                    stage = Stage.EMAIL_NOT_VISIBLE
                    val email = views.filterIsInstance<EditText>().firstOrNull {
                        it.isEnabled && it.contentDescription?.toString() == "계정 이메일 입력"
                    } ?: return@Runnable
                    stage = Stage.PASSWORD_NOT_VISIBLE
                    val password = views.filterIsInstance<EditText>().firstOrNull {
                        it.isEnabled && it.contentDescription?.toString()?.startsWith("계정 비밀번호 입력") == true
                    } ?: return@Runnable
                    stage = Stage.LOGIN_NOT_VISIBLE
                    val login = views.filterIsInstance<Button>().firstOrNull {
                        it.text.toString() == "로그인"
                    } ?: return@Runnable
                    val account = credentials ?: run {
                        stage = Stage.NEEDS_ACCOUNT_FIXTURE
                        needsCredentials = true
                        return@Runnable
                    }
                    if (email.text.toString() != account.first) email.setText(account.first)
                    if (password.text.toString() != account.second) password.setText(account.second)
                    stage = Stage.LOGIN_DISABLED
                    if (login.isEnabled && visible(login) && SystemClock.elapsedRealtime() < deadline) {
                        submitted = true
                        stage = Stage.WAITING_FOR_HOME
                        login.performClick()
                    }
                } catch (_: Exception) {
                    observationFailed = true
                } finally {
                    done.countDown()
                }
            }
            if (!main.post(observe)) {
                report(Outcome.HANDLER_POST_FAILED, stage)
                return
            }
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            if (!done.await(remaining, TimeUnit.MILLISECONDS)) {
                main.removeCallbacks(observe)
                report(Outcome.HANDLER_TIMEOUT, stage)
                return
            }
            if (observationFailed) report(Outcome.OBSERVATION_FAILED, stage)
            check(!observationFailed) { "Device test login UI observation failed." }
            if (ready) {
                report(Outcome.HOME_READY, stage)
                return
            }
            if (needsCredentials && credentials == null) credentials = readAccount(instrumentation)
            val pause = minOf(100L, deadline - SystemClock.elapsedRealtime())
            if (pause > 0L) SystemClock.sleep(pause)
        }
        report(Outcome.TIMEOUT, stage)
    }

    private fun readAccount(instrumentation: Instrumentation): Pair<String, String> = try {
        val file = File(instrumentation.targetContext.cacheDir, "walksafe-device-test-account.json")
        val json = JSONObject(file.readText())
        val email = json.getString("email")
        val password = json.getString("password")
        check(email.isNotBlank() && password.isNotEmpty())
        email to password
    } catch (_: Exception) {
        throw IllegalStateException("Device test account fixture is unavailable or invalid.")
    }

    private fun descendants(view: View): List<View> {
        val result = mutableListOf(view)
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) result.addAll(descendants(view.getChildAt(index)))
        }
        return result
    }

    private fun visible(view: View): Boolean =
        view.isShown && view.windowVisibility == View.VISIBLE && view.getGlobalVisibleRect(Rect())
}
