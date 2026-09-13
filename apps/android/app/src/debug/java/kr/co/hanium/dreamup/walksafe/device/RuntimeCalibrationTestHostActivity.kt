package kr.co.hanium.dreamup.walksafe.device

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import kr.co.hanium.dreamup.walksafe.inference.TfliteAndroidFrameDetector

/** Debug-only, non-exported host. It never reads account, readiness or application preferences. */
class RuntimeCalibrationTestHostActivity : Activity() {
    private val detectorLock = Any()
    private var detector: TfliteAndroidFrameDetector? = null

    /** The instrumentation fixture owns registration and removal of this lifecycle callback. */
    var onHostUnavailable: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Runtime calibration device test" })
    }

    fun installDetector(initial: TfliteAndroidFrameDetector) = synchronized(detectorLock) {
        check(detector == null)
        detector = initial
    }

    fun borrowDetector(): TfliteAndroidFrameDetector = synchronized(detectorLock) { checkNotNull(detector) }

    /** Called on the fixture's existing detector executor inside selection.tryAdopt. */
    fun replaceDetector(expected: TfliteAndroidFrameDetector, replacement: TfliteAndroidFrameDetector): Boolean =
        synchronized(detectorLock) {
            if (detector !== expected) false else { detector = replacement; true }
        }

    fun detachDetector(): TfliteAndroidFrameDetector? = synchronized(detectorLock) {
        detector.also { detector = null }
    }

    override fun onPause() {
        onHostUnavailable?.invoke()
        super.onPause()
    }

    override fun onDestroy() {
        onHostUnavailable?.invoke()
        super.onDestroy()
    }
}
