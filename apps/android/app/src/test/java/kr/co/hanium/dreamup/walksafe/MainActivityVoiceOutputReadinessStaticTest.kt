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
                "startupRequirementCheck(decision,setOf(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS))",
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
            "decision.unavailableRequirements.any(userInstallableRequirements::contains)&&" +
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
            "effects.persistInterruptionMarker()",
            "no interruption marker is written for a walk that never started",
        )
        // A paused walk that loses speech output still fails closed.
        assertFalse(advance.contains("session.state==WalkSessionState.PAUSED&&decision.unavailableRequirements.all("))
        val factory = compact(MainActivityRefactorSource.factory(source, "createWalkSessionAdmissionController"))
        assertTrue(factory.contains("userInstallableRequirements=USER_INSTALLABLE_REQUIREMENTS"))
        assertTrue(factory.contains("overridefunpersistInterruptionMarker()=persistWalkSessionInterruptionMarker()"))
    }

    @Test
    fun startBlockReasonStillRejectsLimitedKoreanSpeech() {
        val block = compact(method("walkSessionReadinessBlockReason"))
        assertTrue(
            block.contains(
                "!sources.voiceFeatureEnabled()||" + "!sources.offlineTtsAllowed(decision)",
            ),
        )
        assertTrue(block.contains("returnkoreanTtsBlockDetail"))
        val factory = compact(MainActivityRefactorSource.factory(source, "createWalkReadinessBlockController"))
        assertTrue(factory.contains("koreanTtsBlockDetail=KOREAN_TTS_WALK_BLOCK_DETAIL"))
        assertTrue(factory.contains("overridefunvoiceFeatureEnabled()=postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.VOICE_GUIDANCE)"))
        assertTrue(factory.contains("overridefunofflineTtsAllowed(decision:WalkSafeStartupCapabilityDecision)=decision.allowsRequirement(WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS)"))
    }

    @Test
    fun voiceDataRecheckRevokesTheEarlierOverrideBeforeStartingEitherProbePath() {
        val recheck = compact(method("recheckVoiceDataAfterSettingsIfNeeded"))
        assertBefore(
            recheck,
            "effects.offlineTtsOverride=null",
            "probe.recheck()",
            "the earlier success must not override a pending voice-data recheck",
        )
        assertBefore(
            recheck,
            "effects.offlineTtsOverride=null",
            "startReplacedProbe()",
            "a replacement probe must also begin without the earlier override",
        )
        val replacement = compact(MainActivityRefactorSource.member(File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/startup/interaction/VoiceDataSettingsRecoveryController.kt").readText(), "startReplacedProbe"))
        assertBefore(replacement, "probe.replace", "probe.start()", "the replacement starts only after replacing the old probe")
        val factory = compact(MainActivityRefactorSource.factory(source, "createVoiceDataSettingsRecovery"))
        assertTrue(factory.contains("overridevarofflineTtsOverride:Boolean?"))
        assertTrue(factory.contains("get()=offlineKoreanTextToSpeechCapabilityOverride"))
        assertTrue(factory.contains("set(value){offlineKoreanTextToSpeechCapabilityOverride=value}"))
        assertTrue(factory.contains("overridefunrecheck()=startupCapabilityProbe.recheckOfflineKoreanTextToSpeech()"))
        assertTrue(factory.contains("overridefunstart()=startupCapabilityProbe.start()"))
    }

    private fun assertBefore(text: String, first: String, second: String, message: String) {
        val firstIndex = text.indexOf(first)
        val secondIndex = text.indexOf(second)
        assertTrue(message, firstIndex >= 0 && secondIndex >= 0 && firstIndex < secondIndex)
    }

    private fun compact(text: String): String = text.replace(Regex("\\s+"), "")

    private fun method(name: String): String = when (name) {
        "captureWalkSessionReadiness" -> {
            val wrapper = MainActivityRefactorSource.member(source, name)
            assertTrue(wrapper.contains("walkReadinessCoordinator.capture(decision, action, recordResourceDiagnostics, checkEntryPermissions)"))
            val binding = MainActivityRefactorSource.property(source, "walkReadinessCoordinator")
            assertTrue(binding.contains("val producers = WalkReadinessProducers("))
            assertTrue(binding.contains("WalkReadinessCoordinator({ walkSessionLifecycle }, startupReadinessEvidence,"))
            assertTrue(binding.contains("official, mounting, producers,"))
            val coordinator = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/readiness/WalkReadinessCoordinator.kt").readText()
            assertTrue(MainActivityRefactorSource.member(coordinator, "capture")
                .contains("producers.evaluate(requirement, effectiveDecision, epoch, officialAssessment, mountingAssessment, deviceResources)"))
            MainActivityRefactorSource.member(
                File("src/main/java/kr/co/hanium/dreamup/walksafe/session/readiness/WalkReadinessProducers.kt").readText()
                    .substringAfter("internal class WalkReadinessProducers("), "evaluate")
        }
        "startupRequirementCheck" -> {
            assertTrue(MainActivityRefactorSource.member(source, name)
                .contains("return kr.co.hanium.dreamup.walksafe.session.readiness.startupRequirementCheck(decision, requirements)"))
            MainActivityRefactorSource.member(
                File("src/main/java/kr/co/hanium/dreamup/walksafe/session/readiness/WalkReadinessProducers.kt").readText(), name)
        }
        else -> MainActivityRefactorSource.function(source, "private fun $name(")
    }

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
