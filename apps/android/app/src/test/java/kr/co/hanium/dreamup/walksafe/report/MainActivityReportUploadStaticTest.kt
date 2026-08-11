package kr.co.hanium.dreamup.walksafe.report

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityReportUploadStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val manifest = File("src/main/AndroidManifest.xml").readText()

    @Test
    fun reportUploadStateTokensCoverPreparedUploadingSuccessFailureAndBackoff() {
        assertTrue(source.contains("reportCandidate=blocked:no_report_image"))
        assertTrue(source.contains("reportCandidate=prepared"))
        assertTrue(source.contains("reportCandidate=uploading"))
        assertTrue(source.contains("reportCandidate=succeeded"))
        assertTrue(source.contains("reportCandidate=failed_http"))
        assertTrue(source.contains("reportCandidate=failed key="))
        assertTrue(source.contains("reportCandidate=duplicate_inflight"))
        assertTrue(source.contains("reportCandidate=cooldown_"))
        assertTrue(source.contains("reportCandidate=succeeded_duplicate"))
        assertTrue(source.contains("duplicateReportIds"))
    }

    @Test
    fun explicitReportRequiresLoginGpsAndSpeaksDuplicateState() {
        assertTrue(source.contains("login_required_explicit_report"))
        assertTrue(source.contains("trigger = \"voice\""))
        assertTrue(source.contains("위치 정보가 필요합니다."))
        assertTrue(source.contains("이미 신고가 된 상태입니다."))
        assertTrue(source.contains("reporterUserId = reporterId"))
    }

    @Test
    fun voiceReportUsesSpeechRecognizerAndExplicitReportRoute() {
        assertTrue(manifest.contains("android.permission.RECORD_AUDIO"))
        assertTrue(source.contains("VOICE_PERMISSION_REQUEST"))
        assertTrue(source.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(source.contains("SpeechRecognizer.createSpeechRecognizer"))
        assertTrue(source.contains("RecognizerIntent.ACTION_RECOGNIZE_SPEECH"))
        assertTrue(source.contains("음성 신고"))
        assertTrue(source.contains("voice=report_command_recognized"))
        assertTrue(source.contains("requestExplicitReport()"))
        assertTrue(source.contains("신고 요청을 인식하지 못했습니다."))
    }
}
