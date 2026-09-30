package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S03-5: required Korean speech output must not be reported READY while the capability decision
 * limits OFFLINE_KOREAN_TTS. Source structure only; not evidence of physical speech output.
 */
class MainActivityVoiceOutputReadinessStaticTest {
    private val source = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt"),
    ).first { it.isFile }.readText()

    @Test
    fun limitedKoreanSpeechOutputIsNeverReportedReady() {
        val capture = method("captureWalkSessionReadiness")
        val branch = capture
            .substringAfter("WalkSessionReadinessRequirement.VOICE_OUTPUT ->", "")
            .substringBefore("WalkSessionReadinessRequirement.ROUTE_SERVICE ->")
        assertTrue("missing VOICE_OUTPUT readiness branch", branch.isNotBlank())
        val compactBranch = compact(branch)
        assertTrue(
            "S03-5: VOICE_OUTPUT must report the OFFLINE_KOREAN_TTS capability status",
            compactBranch.contains(
                "startupRequirementCheck(effectiveDecision,setOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS),)",
            ),
        )
        assertFalse(
            "S03-5: a limited speech output must not be substituted with READY",
            compactBranch.contains("WalkSessionReadinessStatus.READY"),
        )
        assertFalse(
            "S03-5: VOICE_OUTPUT must not skip the TTS check when the requirement is not allowed",
            compactBranch.contains("allowsRequirement("),
        )
    }

    @Test
    fun ttsStatusMapsUnavailableAndPendingExplicitly() {
        val check = compact(method("startupRequirementCheck"))
        assertBefore(
            check,
            "decision.unavailableRequirements.filter(requirements::contains)",
            "returnWalkSessionReadinessStatus.UNAVAILABLEto",
            "unavailable TTS must map to UNAVAILABLE",
        )
        assertBefore(
            check,
            "decision.pendingRequirements.filter(requirements::contains)",
            "returnWalkSessionReadinessStatus.PENDINGto",
            "pending TTS must map to PENDING",
        )
        assertBefore(
            check,
            "returnWalkSessionReadinessStatus.UNAVAILABLEto",
            "returnWalkSessionReadinessStatus.PENDINGto",
            "unavailable takes precedence over pending",
        )
    }

    @Test
    fun missingVoiceDataBeforeAWalkBlocksStartWithoutAFalseInterruption() {
        val advance = compact(method("maybeAdvanceWalkSessionAfterCapabilityCheck"))
        // Mixed with feature-limiting items (e.g. METRIC_DISTANCE) the gap still applies;
        // only an unsupported Android version keeps the fail-closed transition.
        val gap = "valinstallableGapBeforeWalk=session.state==WalkSessionState.READY&&" +
            "decision.unavailableRequirements.any(USER_INSTALLABLE_REQUIREMENTS::contains)&&" +
            "WalkSafeStartupRequirement.ANDROID_VERSION!indecision.unavailableRequirements"
        assertTrue("installable gap before a walk must be recognised", advance.contains(gap))
        assertBefore(
            advance,
            gap,
            "decision.unavailableRequirements.isNotEmpty()&&!installableGapBeforeWalk",
            "the fail-closed transition must skip the pre-walk installable gap",
        )
        assertBefore(
            advance,
            "!installableGapBeforeWalk",
            "persistWalkSessionInterruptionMarker()",
            "no interruption marker is written for a walk that never started",
        )
        // A paused walk that loses speech output still fails closed.
        assertFalse(advance.contains("session.state==WalkSessionState.PAUSED&&decision.unavailableRequirements.all("))
    }

    @Test
    fun startBlockReasonStillRejectsLimitedKoreanSpeech() {
        val block = compact(method("walkSessionReadinessBlockReason"))
        assertTrue(
            block.contains(
                "!postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.VOICE_GUIDANCE)||" +
                    "!decision.allowsRequirement(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS)",
            ),
        )
        assertTrue(block.contains("returnKOREAN_TTS_WALK_BLOCK_DETAIL"))
    }

    private fun assertBefore(text: String, first: String, second: String, message: String) {
        val firstIndex = text.indexOf(first)
        val secondIndex = text.indexOf(second)
        assertTrue(message, firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex)
    }

    private fun compact(text: String): String = text.replace(Regex("\\s+"), "")

    private fun method(name: String): String = blockAt(source, "private fun $name(")

    private fun blockAt(text: String, marker: String): String {
        val markerIndex = text.indexOf(marker)
        check(markerIndex >= 0) { "missing block marker: $marker" }
        val open = text.indexOf('{', markerIndex)
        check(open >= 0) { "missing opening brace: $marker" }
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(markerIndex, index + 1)
                }
            }
        }
        error("unterminated block: $marker")
    }
}
