package kr.co.hanium.dreamup.walksafe.startup.interaction

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.tts.TextToSpeech

internal class AndroidStartupSettingsNative(private val context: Context) : StartupSettingsNative<Intent> {
    override fun installVoiceData() = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
    override fun chooseVoiceEngine() = Intent("com.android.settings.TTS_SETTINGS")
    override fun applicationSettings() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.fromParts("package", context.packageName, null)
    }
    override fun locationSettings() = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    override fun developmentSettings() = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    override fun start(intent: Intent) { context.startActivity(intent) }
}
