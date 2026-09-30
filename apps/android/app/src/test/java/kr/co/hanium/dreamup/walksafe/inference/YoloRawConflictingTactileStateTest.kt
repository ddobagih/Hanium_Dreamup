package kr.co.hanium.dreamup.walksafe.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S10-9 / S10-17: the single-label parser never turns a rejected damaged-tactile top score into a
 * normal tactile block, and class-aware NMS keeps both conflicting tactile states for downstream
 * blocker-first handling instead of silently choosing the normal one.
 */
class YoloRawConflictingTactileStateTest {
    private val names = listOf("linear_tactile_paving", "damaged_linear_tactile_paving", "bollard")
    private val thresholds = mapOf(
        "linear_tactile_paving" to 0.25f,
        "damaged_linear_tactile_paving" to 0.30f,
        "bollard" to 0.35f,
    )
    private val parser = YoloRawOutputParser(
        inputSize = INPUT,
        classCount = names.size,
        classNameForId = names::getOrNull,
        thresholdForClass = { thresholds.getValue(it) },
    )

    @Test
    fun rejectedDamagedTopScoreIsNotReplacedByTheNormalRunnerUp() {
        val values = tensor()
        put(values, anchor = 0, scores = floatArrayOf(0.28f, 0.29f, 0f))

        // Promoting the 0.28 normal block would claim a followable path for a likely damaged one.
        assertTrue(parser.parse(values).isEmpty())
    }

    @Test
    fun acceptedDamagedTopScoreIsKeptAsDamaged() {
        val values = tensor()
        put(values, anchor = 0, scores = floatArrayOf(0.28f, 0.31f, 0f))

        assertEquals(listOf("damaged_linear_tactile_paving"), parser.parse(values).map { it.className })
    }

    @Test
    fun sameAreaNormalAndDamagedStatesAreBothKeptByClassAwareSuppression() {
        val values = tensor()
        put(values, anchor = 0, scores = floatArrayOf(0.80f, 0f, 0f))
        put(values, anchor = 1, scores = floatArrayOf(0f, 0.70f, 0f))

        val classes = parser.parse(values).map { it.className }.toSet()
        assertEquals(setOf("linear_tactile_paving", "damaged_linear_tactile_paving"), classes)
    }

    private fun tensor() = FloatArray((4 + names.size) * ANCHORS)

    private fun put(values: FloatArray, anchor: Int, scores: FloatArray) {
        // Same box for every anchor used: center (16, 16), 8 x 8 pixels.
        values[anchor] = 16f
        values[ANCHORS + anchor] = 16f
        values[2 * ANCHORS + anchor] = 8f
        values[3 * ANCHORS + anchor] = 8f
        scores.forEachIndexed { index, score -> values[(4 + index) * ANCHORS + anchor] = score }
    }

    private companion object {
        const val INPUT = 32
        const val ANCHORS = 4 * 4 + 2 * 2 + 1
    }
}
