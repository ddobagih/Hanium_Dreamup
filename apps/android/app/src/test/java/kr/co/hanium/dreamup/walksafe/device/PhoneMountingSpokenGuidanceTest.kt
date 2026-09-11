package kr.co.hanium.dreamup.walksafe.device

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Twenty-one mounting failures each carried their own sentence, averaging 84 characters spoken.
 * Two groups did not earn that: four reasons clear themselves on the next valid camera frame, so
 * naming the internal ordering rule spends 92 characters to say "hold still", and five say the
 * stored result belongs to an earlier walk, which the walker answers the same way every time.
 *
 * The reasons stay — logs, tests and the screen still distinguish them. What is spoken mid-walk
 * does not.
 */
class PhoneMountingSpokenGuidanceTest {
    private val prefix = "휴대전화 장착을 교정하세요. "

    /** Resolved by the next valid camera frame; the walker only has to keep the phone steady. */
    private val selfResolving = listOf(
        PhoneMountingReason.CAMERA_EVIDENCE_STALE,
        PhoneMountingReason.POST_CHECK_CAMERA_EVIDENCE_REQUIRED,
        PhoneMountingReason.POST_CONFIRMATION_CAMERA_EVIDENCE_REQUIRED,
        PhoneMountingReason.POST_FAULT_CAMERA_EVIDENCE_REQUIRED,
    )

    /** Evidence bound to an earlier walk; only a new walk clears any of them. */
    private val needsNewWalk = listOf(
        PhoneMountingReason.STATE_EPOCH_MISMATCH,
        PhoneMountingReason.CHECK_REQUEST_EPOCH_MISMATCH,
        PhoneMountingReason.USER_CONFIRMATION_EPOCH_MISMATCH,
        PhoneMountingReason.CAMERA_EPOCH_MISMATCH,
        PhoneMountingReason.SAFETY_STOP_LATCHED,
    )

    private val shortened = selfResolving + needsNewWalk

    @Test
    fun selfResolvingReasonsAskOnlyForStillness() {
        selfResolving.forEach { reason ->
            assertEquals(reason.name, "자세를 유지하면 다시 확인합니다.", reason.accessibleActionKo)
        }
    }

    @Test
    fun everyStaleEpochReasonAsksForTheSameNewWalk() {
        needsNewWalk.forEach { reason ->
            assertEquals(reason.name, "새 보행으로 다시 시작하세요.", reason.accessibleActionKo)
        }
    }

    @Test
    fun theShortenedReasonsDoNotSpeakWhyTheyFailed() {
        shortened.forEach { reason ->
            assertFalse(reason.name, reason.speakReason)
            assertEquals(reason.name, reason.accessibleActionKo, reason.spokenGuidanceKo)
        }
    }

    @Test
    fun theShortenedAnnouncementsFitInOneBreath() {
        shortened.forEach { reason ->
            val spoken = prefix + reason.spokenGuidanceKo
            assertTrue("${reason.name} (${spoken.length}자): $spoken", spoken.length <= 40)
        }
    }

    @Test
    fun theRemainingReasonsStillExplainThemselves() {
        // Shortening is for the two groups the walker cannot act differently on; a wrong mount or
        // an unusable device still has to say what is wrong.
        (PhoneMountingReason.entries - shortened.toSet()).forEach { reason ->
            assertTrue(reason.name, reason.speakReason)
            assertTrue(reason.name, reason.spokenGuidanceKo.startsWith(reason.accessibleReasonKo))
        }
    }

    @Test
    fun theScreenKeepsEveryDistinctReason() {
        // The screen is read on demand, not mid-stride, so it pays no interruption cost.
        val reasons = PhoneMountingReason.entries.map { it.accessibleReasonKo }

        assertEquals(reasons.size, reasons.distinct().size)
    }

    @Test
    fun theSpokenPathUsesTheShortenedGuidance() {
        val source = File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
        ).readText()
        val mountingSpeech = Regex("speakStatusExplanation\\([^)]*\\)")
            .findAll(source)
            .map { it.value }
            .filter { it.contains("장착") || it.contains("assessment.") }
            .toList()

        assertTrue("장착 발화 호출 ${mountingSpeech.size}건", mountingSpeech.isNotEmpty())
        mountingSpeech.forEach { call ->
            assertFalse(call, call.contains("accessibleReasonKo"))
        }
    }
}
