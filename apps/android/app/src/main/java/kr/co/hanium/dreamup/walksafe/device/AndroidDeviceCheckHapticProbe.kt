package kr.co.hanium.dreamup.walksafe.device

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.Closeable

/** Requests a short real vibration; the user still confirms whether it was physically felt. */
class AndroidDeviceCheckHapticProbe(context: Context) : Closeable {
    private val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    fun requestTestVibration(): Boolean {
        val available = vibrator?.takeIf(Vibrator::hasVibrator) ?: return false
        return runCatching {
            available.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0L, 140L, 90L, 140L), -1),
            )
            true
        }.getOrDefault(false)
    }

    override fun close() {
        runCatching { vibrator?.cancel() }
    }
}
