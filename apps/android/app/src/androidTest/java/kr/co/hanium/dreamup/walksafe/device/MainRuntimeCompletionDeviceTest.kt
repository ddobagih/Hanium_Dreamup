package kr.co.hanium.dreamup.walksafe.device

import android.os.Build
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.ref.WeakReference
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.CameraRuntimeHandoff
import kr.co.hanium.dreamup.walksafe.MainActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** No onCreate, account, camera, store, or native model is started by this isolated callback probe. */
@RunWith(AndroidJUnit4::class)
class MainRuntimeCompletionDeviceTest {
    @Test(timeout = 15_000L)
    fun aPendingGlobalReleaseCannotKeepADiscardedMainScreenAlive() {
        assumeTrue(BuildConfig.DEBUG && InstrumentationRegistry.getArguments()
            .getString("allowMainRuntimeCompletionTests") == "true")
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.contains("sdk_gphone"))
        val claim = requireNotNull(CameraRuntimeHandoff.begin()) { "An unrelated owner is still active" }
        try {
            val discarded = discardObservedScreen()
            val deadline = SystemClock.elapsedRealtime() + 5_000L
            while (discarded.get() != null && SystemClock.elapsedRealtime() < deadline) {
                Runtime.getRuntime().gc()
                System.runFinalization()
                Thread.sleep(25L)
            }
            assertNull("The global release callback retained a discarded MainActivity", discarded.get())
            assertFalse("Collection does not imply native or journal release", claim.released.isDone)
        } finally { claim.complete() }
    }

    private fun discardObservedScreen(): WeakReference<MainActivity> {
        // Only constructor-free callback state is used. This cannot execute Main's startup flow.
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        val owner = unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(unsafe, MainActivity::class.java) as MainActivity
        val callbacks = mutableMapOf<Long, (Throwable?) -> Unit>()
        val type = MainActivity::class.java
        type.getDeclaredField("runtimeUiCompletions").apply { isAccessible = true }.set(owner, callbacks)
        val deferred = type.getDeclaredMethod("deferPrimaryForCameraRelease", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(owner, false)
        assertEquals(true, deferred)
        // Mirrors the first two onDestroy statements without invoking unrelated Activity teardown.
        type.getDeclaredField("runtimeUiCompletionsClosed").apply { isAccessible = true }.setBoolean(owner, true)
        callbacks.clear()
        return WeakReference(owner)
    }
}
