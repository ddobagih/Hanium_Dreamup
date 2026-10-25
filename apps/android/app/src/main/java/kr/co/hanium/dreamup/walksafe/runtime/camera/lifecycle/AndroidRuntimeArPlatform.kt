package kr.co.hanium.dreamup.walksafe.runtime.camera.lifecycle

import android.app.Activity
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Session
import kr.co.hanium.dreamup.walksafe.CameraFallbackTestOverride
import kr.co.hanium.dreamup.walksafe.depth.ArCoreFrameProvider

internal interface RuntimeArDisplayGeometry {
    val textureId: Int
    val width: Int
    val height: Int
    fun rotation(): Int
}

/** The Activity is an ARCore SDK input only; no activity-specific field or method is exposed. */
internal class AndroidRuntimeArPlatform(
    private val activity: () -> Activity,
    private val display: RuntimeArDisplayGeometry,
) : RuntimeArPlatform<Session, ArCoreFrameProvider> {
    override fun availability() = ArCoreApk.getInstance().checkAvailability(activity())
    override fun availabilityAsync(callback: (ArCoreApk.Availability) -> Unit) {
        ArCoreApk.getInstance().checkAvailabilityAsync(activity()) { callback(it) }
    }
    override fun requestInstall(userRequested: Boolean) = ArCoreApk.getInstance().requestInstall(activity(), userRequested)
    override fun consumeForcedCameraFallback() = CameraFallbackTestOverride.consumeForceCameraNonMetricFallback(activity().intent)
    override fun createSession() = Session(activity())
    override fun createProvider(session: Session) = ArCoreFrameProvider(session)
    override fun configureDepth(provider: ArCoreFrameProvider) = provider.configureDepthMode()
    override fun closeSession(session: Session) = session.close()
    override fun closeProvider(provider: ArCoreFrameProvider) = provider.close()
    override fun pauseSession(session: Session) = session.pause()
    override fun resumeBeforePublish(session: Session): Boolean {
        val textureBound = display.textureId != 0
        if (textureBound) session.setCameraTextureNames(intArrayOf(display.textureId))
        if (display.width > 0 && display.height > 0)
            session.setDisplayGeometry(display.rotation(), display.width, display.height)
        session.resume()
        return textureBound
    }
}
