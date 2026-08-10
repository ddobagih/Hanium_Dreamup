package kr.co.hanium.dreamup.walksafe.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationSpeechDeliveryTest {
    @Test
    fun voiceRecognitionSuppressesNonRiskOutputButNotRiskFeedback() {
        assertTrue(shouldSuppressFeedbackDuringVoiceRecognition(voiceRecognitionActive = true, isRisk = false))
        assertFalse(shouldSuppressFeedbackDuringVoiceRecognition(voiceRecognitionActive = true, isRisk = true))
        assertFalse(shouldSuppressFeedbackDuringVoiceRecognition(voiceRecognitionActive = false, isRisk = false))
    }

    @Test
    fun acceptedPendingTtsThenDuplicateDoesNotFallbackOrAcknowledgeTwice() {
        var ttsCalls = 0
        var pendingCompletion: (() -> Unit)? = null
        var announcements = 0
        var acknowledgements = 0
        val speak = { _: String, onCompleted: (() -> Unit)?, _: (() -> Unit)? ->
            ttsCalls += 1
            if (ttsCalls == 1) {
                pendingCompletion = onCompleted
                NavigationSpeechDispatchResult.ACCEPTED
            } else {
                NavigationSpeechDispatchResult.SUPPRESSED
            }
        }
        val announce = { _: String, _: (() -> Unit)? ->
            announcements += 1
            true
        }

        assertTrue(
            dispatchNavigationSpeech("직진하세요", { acknowledgements += 1 }, speak, announce),
        )
        assertFalse(
            dispatchNavigationSpeech("직진하세요", { acknowledgements += 1 }, speak, announce),
        )
        assertEquals(0, announcements)
        assertEquals(0, acknowledgements)

        pendingCompletion?.invoke()
        pendingCompletion?.invoke()
        assertEquals(1, acknowledgements)
    }

    @Test
    fun asynchronousTtsFailureFallsBackAndCompletesOnlyAfterAnnouncementDispatch() {
        var pendingCompletion: (() -> Unit)? = null
        var pendingFailure: (() -> Unit)? = null
        var announcements = 0
        var acknowledgements = 0
        val accepted = dispatchNavigationSpeech(
            message = "우회전하세요",
            onCompleted = { acknowledgements += 1 },
            speakWithTts = { _, onCompleted, onFailed ->
                pendingCompletion = onCompleted
                pendingFailure = onFailed
                NavigationSpeechDispatchResult.ACCEPTED
            },
            fallBackToTalkBack = { _, onDelivered ->
                announcements += 1
                assertEquals(0, acknowledgements)
                onDelivered?.invoke()
                onDelivered?.invoke()
                true
            },
        )

        assertTrue(accepted)
        assertEquals(0, acknowledgements)
        pendingFailure?.invoke()
        pendingCompletion?.invoke()
        assertEquals(1, announcements)
        assertEquals(1, acknowledgements)
    }

    @Test
    fun immediatelyUnavailableTtsUsesOneTalkBackFallbackOnlyWhenActive() {
        var announcements = 0
        var acknowledgements = 0
        val unavailable = { _: String, _: (() -> Unit)?, _: (() -> Unit)? ->
            NavigationSpeechDispatchResult.UNAVAILABLE
        }
        val announce = { _: String, onDelivered: (() -> Unit)? ->
            announcements += 1
            onDelivered?.invoke()
            true
        }

        assertTrue(
            dispatchNavigationSpeech("도착했습니다", { acknowledgements += 1 }, unavailable, announce),
        )
        assertFalse(
            dispatchNavigationSpeech("도착했습니다", { acknowledgements += 1 }, unavailable) { _, _ -> false },
        )
        assertEquals(1, announcements)
        assertEquals(1, acknowledgements)
    }
}
