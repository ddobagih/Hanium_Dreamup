package kr.co.hanium.dreamup.walksafe.voice

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Visible same-UID anchor for the debug-only foreground-service device probe. */
class WalkVoiceForegroundServiceTestHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Voice service device test" })
    }
}
