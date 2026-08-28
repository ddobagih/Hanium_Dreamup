package kr.co.hanium.dreamup.walksafe.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingSpeechQueueTest {
    @Test
    fun keepsLatestMessagePerPriorityAndFlushesRiskFirst() {
        val queue = PendingSpeechQueue()

        assertTrue(queue.offer("첫 길안내", SpeechPriority.NAVIGATION))
        assertTrue(queue.offer("두 번째 길안내", SpeechPriority.NAVIGATION))
        assertFalse(queue.offer("두 번째 길안내", SpeechPriority.NAVIGATION))
        assertTrue(queue.offer("사용자 응답", SpeechPriority.INTERACTION))
        assertTrue(queue.offer("위험 경고", SpeechPriority.RISK))

        assertEquals(
            listOf("위험 경고", "사용자 응답", "두 번째 길안내"),
            queue.drainPriorityOrder().map(PendingSpeech::message),
        )
        assertTrue(queue.drainPriorityOrder().isEmpty())
    }

    @Test
    fun boundedQueueOnlyEvictsForAHigherPriority() {
        val queue = PendingSpeechQueue(capacity = 2)
        queue.offer("길안내", SpeechPriority.NAVIGATION)
        queue.offer("상호작용", SpeechPriority.INTERACTION)

        assertTrue(queue.offer("위험", SpeechPriority.RISK))

        assertEquals(
            listOf(SpeechPriority.RISK, SpeechPriority.INTERACTION),
            queue.drainPriorityOrder().map(PendingSpeech::priority),
        )
    }

    @Test
    fun initializationKeepsTheHighestRiskUntilTtsIsReady() {
        val queue = PendingSpeechQueue()

        assertTrue(queue.offer("주의", SpeechPriority.RISK, riskRank = 3))
        assertTrue(queue.offer("정지", SpeechPriority.RISK, riskRank = 5))
        assertFalse(queue.offer("다른 주의", SpeechPriority.RISK, riskRank = 4))

        assertEquals("정지", queue.drainPriorityOrder().single().message)
    }

    @Test
    fun clearDropsInitializationMessagesAfterFailureOrClose() {
        val queue = PendingSpeechQueue()
        queue.offer("위험", SpeechPriority.RISK)

        queue.clear()

        assertFalse(queue.hasPriority(SpeechPriority.RISK))
        assertTrue(queue.drainPriorityOrder().isEmpty())
    }

    @Test
    fun removingNavigationPreservesRiskAndInteraction() {
        val queue = PendingSpeechQueue()
        queue.offer("길안내", SpeechPriority.NAVIGATION)
        queue.offer("확인", SpeechPriority.INTERACTION)
        queue.offer("정지", SpeechPriority.RISK)

        assertTrue(queue.removePriority(SpeechPriority.NAVIGATION))
        assertEquals(
            listOf(SpeechPriority.RISK, SpeechPriority.INTERACTION),
            queue.drainPriorityOrder().map(PendingSpeech::priority),
        )
    }
}
