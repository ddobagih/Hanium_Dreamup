package kr.co.hanium.dreamup.walksafe

import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kr.co.hanium.dreamup.walksafe.device.KoreanTtsOutputDurationPolicy
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Times what the app actually says, because a phrase's cost is how long it occupies the user's
 * ears, not how it reads on screen.
 *
 * The distinction that matters is when a phrase is heard. A hazard warning arrives while someone
 * is walking toward the hazard, so its length is part of the hazard: at a normal walking pace of
 * roughly 1.2 m/s, every second of speech is more than a metre travelled. A consent or account
 * error is heard standing still and can afford to be complete.
 */
@RunWith(AndroidJUnit4::class)
class SpokenPhraseDurationDeviceTest {

    @Test
    fun timeEveryPhraseTheUserHears() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ready = CountDownLatch(1)
        var initStatus = TextToSpeech.ERROR
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(instrumentation.targetContext) { status ->
            initStatus = status
            ready.countDown()
        }
        if (!ready.await(15L, TimeUnit.SECONDS) || initStatus != TextToSpeech.SUCCESS) {
            report("initialised=false status=$initStatus")
            return
        }
        tts.setLanguage(Locale.KOREAN)
        runCatching {
            tts.voices.orEmpty()
                .filter {
                    it.locale.language.equals(Locale.KOREAN.language, ignoreCase = true) &&
                        !it.isNetworkConnectionRequired
                }
                .sortedBy { it.name }
                .firstOrNull()
        }.getOrNull()?.let { tts.setVoice(it) }

        val lines = PHRASES.map { (context, phrase) ->
            val durationMs = synthesise(tts, phrase)
            val metresWalked = durationMs?.let { it * WALKING_SPEED_MM_PER_MS / 1000.0 }
            buildString {
                append(context).append(" | ")
                append(durationMs?.let { "${it}ms" } ?: "FAILED").append(" | ")
                append(metresWalked?.let { "%.1fm".format(it) } ?: "-").append(" | ")
                append(phrase)
            }
        }
        runCatching { tts.shutdown() }

        val report = lines.joinToString("\n")
        Log.i(LOG_TAG, report)
        report(report)
        assertTrue(report.isNotBlank())
    }

    private fun synthesise(tts: TextToSpeech, phrase: String): Long? {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File.createTempFile("walksafe-phrase-", ".wav", context.cacheDir)
        val done = CountDownLatch(1)
        var failed = false
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = done.countDown()
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                failed = true
                done.countDown()
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                failed = true
                done.countDown()
            }
        })
        tts.synthesizeToFile(phrase, null, output, "phrase")
        val completed = done.await(20L, TimeUnit.SECONDS)
        val durationMs = if (completed && !failed) {
            runCatching { KoreanTtsOutputDurationPolicy.durationMsOrNull(output.readBytes()) }
                .getOrNull()
        } else {
            null
        }
        runCatching { output.delete() }
        return durationMs
    }

    private fun report(text: String) {
        InstrumentationRegistry.getInstrumentation()
            .sendStatus(0, Bundle().apply { putString(STATUS_KEY, text) })
    }

    private companion object {
        /** Roughly 1.2 m/s, expressed so a millisecond duration converts without floats. */
        const val WALKING_SPEED_MM_PER_MS = 1.2

        const val LOG_TAG = "WalkSafePhraseDuration"
        const val STATUS_KEY = "walksafe_phrase_durations"

        /**
         * Verbatim from the source. WALKING phrases are heard mid-stride; STOPPED ones are not.
         */
        val PHRASES = listOf(
            // depth/MessagePolicy.kt — the shortest and most urgent thing the app says.
            "WALKING/hazard" to "전방 바로 앞 사람. 멈추세요. 주변을 확인하세요.",
            "WALKING/hazard" to "전방 약 3보 앞 자전거. 멈출 준비를 하세요.",
            // feedback/AndroidNonMetricObstacleAdvisoryPolicy.kt — phones without distance.
            "WALKING/hazard-nodist" to
                "카메라 보조 경고. 왼쪽에 전동 킥보드 후보가 보입니다. 주변을 확인하세요.",
            // session/PriorityUserOnboardingPolicy.kt — the safety-education wording.
            "WALKING/practice" to "위험 안내입니다. 멈추고 주변을 확인하세요.",
            // navigation/RouteNavigator.kt
            "WALKING/route" to "TMAP 경로 기준 전방 50m 안내 지점까지 이동하세요.",
            "WALKING/route-off" to
                "경로를 벗어나 현재 방향 안내를 중지했습니다. 사용자 선택이 필요합니다. " +
                "새 경로 요청, 위치 다시 확인, 길안내 종료 중에서 선택해 주세요.",
            "WALKING/control" to "보행 안내를 일시정지했습니다.",
            "WALKING/control" to "명령을 이해하지 못했습니다. 다시 말씀해 주세요.",
            // MainActivity.kt — heard before walking starts.
            "STOPPED/account" to "동의 제어 비밀을 안전하게 저장하지 못해 선택을 전송하지 않습니다.",
            "STOPPED/account" to
                "삭제 확인이 보존된 동안에는 계정 신원을 지울 수 없습니다. " +
                "삭제 요청 준비를 먼저 완료하세요.",
            "STOPPED/devicecheck" to
                "설정 > 위치 > 위치 서비스에서 Google 위치 정확도를 켠 뒤 다시 점검하세요. " +
                "실내에서는 이 기능이 꺼져 있으면 위치를 찾을 수 없습니다.",
        )
    }
}
