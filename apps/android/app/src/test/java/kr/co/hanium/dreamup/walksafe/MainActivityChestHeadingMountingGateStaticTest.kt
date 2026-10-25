package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S09-32 (originals/09.txt:87): with a mounting mismatch the chest heading output is the most
 * conservative one (none). It is produced only for a CHEST_FORWARD confirmation of this walk while the
 * mounting outputs are allowed, and a runtime mounting fault revokes those outputs at once. The time
 * mismatch part (|ω|·Δt added to the accuracy) is covered by ChestMountedHeadingRotationSkewTest.
 */
class MainActivityChestHeadingMountingGateStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun chestHeadingNeedsAConfirmedSuitableChestMountOfThisWalk() {
        MainActivityRefactorSource.composition(source, "positioningSessionController")
        val owner = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt").readText()
        val chest = MainActivityRefactorSource.member(owner, "chestMountedHeadingForStep", 1)
        assertTrue(chest.contains(
            "val existingChestMount = phoneMountingOutputsAllowed &&\n" +
                "            phoneMountingRuntimeState?.epoch == walkEpoch &&\n" +
                "            phoneMountingUserConfirmation?.epoch == walkEpoch &&\n" +
                "            phoneMountingUserConfirmation?.method == PhoneMountingMethod.CHEST_FORWARD",
        ))
        assertTrue(chest.contains("if (!explicitFieldConfirmation && !existingChestMount) return null"))
        // A camera mounting fault in the running walk revokes mounting outputs immediately.
        val mounting = File("src/main/java/kr/co/hanium/dreamup/walksafe/session/mounting/PhoneMountingSessionController.kt").readText()
        MainActivityRefactorSource.composition(source, "phoneMountingSession")
        val commit = MainActivityRefactorSource.member(mounting, "commitCameraAssessment")
        val fault = commit.indexOf("mountingFault && phoneMountingRuntimeState?.epoch == epoch")
        assertTrue(fault >= 0)
        assertTrue(commit.substring(fault, fault + 200).contains("phoneMountingOutputsAllowed = false"))
    }
}
