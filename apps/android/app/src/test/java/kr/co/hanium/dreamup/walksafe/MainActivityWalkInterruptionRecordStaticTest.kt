package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S03-16: MainActivity writes and reads the versioned interruption record. */
class MainActivityWalkInterruptionRecordStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun startupReadsTheVersionedRecordBehindTheBooleanMarker() {
        val create = block("override fun onCreate(savedInstanceState: Bundle?)")
        assertTrue(create.contains("previousWalkInterruptionRecord = WalkInterruptionRecord.restore("))
        assertTrue(create.contains("getString(PREF_WALK_SESSION_INTERRUPTION_RECORD, null)"))
        assertTrue(create.contains("legacyInterrupted = previousProcessHadInterruptedWalk"))
        assertTrue(create.contains("editor.putWalkInterruptionRecord(currentRecord)"))
        assertTrue(source.contains("const val PREF_WALK_SESSION_INTERRUPTION_RECORD = \"walk_session_interruption_v2\""))
    }

    @Test
    fun persistenceRecordsStateAndTheNoticeUsesIt() {
        val persist = block("private fun persistWalkSessionInterruptionMarker()")
        val current = block("private fun currentWalkInterruptionRecordOrNull()")
        val notice = block("private fun showPreviousWalkInterruptionNoticeIfNeeded()")

        assertTrue(persist.contains("putWalkInterruptionRecord(currentRecord)"))
        assertTrue(
            persist.indexOf("putWalkInterruptionRecord(currentRecord)") <
                persist.indexOf(".putBoolean(PREF_WALK_SESSION_INTERRUPTED, interrupted).commit()"),
        )
        assertTrue(current.contains("state = snapshot.state"))
        assertTrue(current.contains("walkSessionId = snapshot.epoch.walkSessionId"))
        assertTrue(current.contains("actorId = currentReporterUserId()"))
        assertTrue(notice.contains(".noticeMessageKo(currentActorId = currentReporterUserId())"))
        assertFalse(notice.contains("이전 보행은 앱 종료로 중단되었습니다."))
    }

    private fun block(signature: String): String {
        val start = source.indexOf(signature)
        check(start >= 0) { "missing: $signature" }
        var depth = 0
        var opened = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> { depth += 1; opened = true }
                '}' -> { depth -= 1; if (opened && depth == 0) return source.substring(start, index + 1) }
            }
        }
        error("unterminated: $signature")
    }
}
