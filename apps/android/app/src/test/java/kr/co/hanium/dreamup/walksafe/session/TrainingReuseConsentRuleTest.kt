package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** S02-22: per-material training-reuse truth table, identical in both Android consent views. */
class TrainingReuseConsentRuleTest {
    @Test
    fun walkRawNeedsRawCollectionAndTrainingConsent() {
        assertTable(
            TrainingSourceKind.WALK_RAW_DIAGNOSTIC,
            mapOf(
                (false to false) to false,
                (false to true) to false,
                (true to false) to false,
                (true to true) to true,
            ),
        )
    }

    @Test
    fun reportOriginalNeedsOnlyTheSeparateTrainingConsent() {
        assertTable(
            TrainingSourceKind.REPORT_ORIGINAL,
            mapOf(
                (false to false) to false,
                (false to true) to true,
                (true to false) to false,
                (true to true) to true,
            ),
        )
    }

    @Test
    fun snapshotAndIntegratedSelectionsGiveTheSameAnswer() {
        for (kind in TrainingSourceKind.values()) {
            for (raw in listOf(false, true)) {
                for (training in listOf(false, true)) {
                    val snapshot = PermissionSessionSnapshot(
                        actorId = "actor-A",
                        authentication = AuthenticationState.ACTIVE,
                        rawCollectionConsentGranted = raw,
                        trainingReuseConsentGranted = training,
                    )
                    val selections = IntegratedConsentSelections(
                        rawSourceCollection = raw,
                        trainingReuse = training,
                    )
                    assertEquals(
                        "$kind raw=$raw training=$training",
                        selections.mayReuseForTraining(kind),
                        snapshot.mayReuseForTraining(kind),
                    )
                }
            }
        }
    }

    @Test
    fun otherConsentsDoNotWidenTrainingReuse() {
        val selections = IntegratedConsentSelections(
            rawSourceCollection = true,
            automaticReporting = true,
            mobileNetworkTransfer = true,
            trainingReuse = false,
        )
        for (kind in TrainingSourceKind.values()) {
            assertEquals(false, selections.mayReuseForTraining(kind))
        }
    }

    private fun assertTable(kind: TrainingSourceKind, expected: Map<Pair<Boolean, Boolean>, Boolean>) {
        expected.forEach { (key, allowed) ->
            val (raw, training) = key
            assertEquals(
                "$kind raw=$raw training=$training",
                allowed,
                TrainingReuseConsentRule.allows(kind, raw, training),
            )
        }
    }
}
