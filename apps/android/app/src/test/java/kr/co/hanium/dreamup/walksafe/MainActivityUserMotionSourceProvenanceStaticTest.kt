package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Original StepEvent provenance reaches the serialized consumer; no sensor/Activity claim. */
class MainActivityUserMotionSourceProvenanceStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val tracker = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidStepTracker.kt",
    ).readText()

    @Test
    fun realDetectorCounterAndFallbackEventsKeepTheirOwnerAndReceiptBeforeUiDelivery() {
        listOf(
            "STEP_DETECTOR" to "HIGH",
            "STEP_COUNTER" to "MEDIUM",
            "ACCELEROMETER_FALLBACK" to "LOW",
        ).forEach { (producer, confidence) ->
            assertTrue(
                "missing real $producer event with its original confidence",
                Regex(
                    """emitStepEvent\(\s*deltaSteps = [^,]+,\s*timestampNs = [^,]+,\s*""" +
                        """source = StepEventSource\.$producer,\s*confidence = StepEventConfidence\.$confidence,\s*receivedAtNs = receivedAtNs,\s*""" +
                        (if (producer == "STEP_COUNTER") """counterObservation = counterObservation,\s*""" else "") + """\)""",
                ).containsMatchIn(tracker),
            )
        }
        val emit = compact(braceBlock(tracker, "private fun emitStepEvent("))
        assertTrue(emit.contains("source=source,confidence=confidence"))
        val callback = compact(braceBlock(source, "onStepEvent = { event ->"))
        assertBefore(callback, "valbinding=positioningCollectionBinding", "runOnUiThread{")
        assertBefore(callback, "valreceivedAtMs=SystemClock.elapsedRealtime()", "runOnUiThread{")
        assertBefore(callback, "valmotionOwner=binding?.coordinator?.currentUserMotionOwner()", "runOnUiThread{")
        assertBefore(callback, "if(binding!=null&&motionOwner!=null)", "runOnUiThread{")
        assertTrue(callback.contains("runOnUiThread{handlePositioningStepEvent(event,binding,receivedAtMs,motionOwner)"))
    }

    @Test
    fun theSharedStepCallUsesOriginalSourceAndConfidenceAfterTheExistingOwnerAndTimeGuards() {
        val step = compact(MainActivityRefactorSource.function(source, "private fun handlePositioningStepEvent("))
        val observe = "binding.coordinator.observeStep("
        listOf(
            "isPositioningCollectionCurrent(binding)",
            "binding.coordinator.isUserMotionInputCurrent(event.timestampMs,motionOwner)",
            "event.timestampMs<=binding.startedAtElapsedRealtimeMs",
            "event.timestampMs>receivedAtElapsedRealtimeMs",
            "event.deltaSteps!in1..64",
            "event.source==StepEventSource.STEP_COUNTER&&!binding.counterBoundaryEstablished",
        ).forEach { assertBefore(step, it, observe) }
        assertTrue(step.contains("binding.counterBoundaryEstablished=truereturn"))
        assertEquals(1, Regex(Regex.escape(observe)).findAll(step).count())
        listOf(
            "stepCount=event.deltaSteps",
            "quality=quality",
            "receivedAtElapsedRealtimeMs=receivedAtElapsedRealtimeMs",
            "expectedGeneration=binding.inputGeneration",
            "expectedMotionOwner=motionOwner",
            "stepEventSource=event.source",
            "stepEventConfidence=event.confidence",
            "originalStepEvent=event",
        ).forEach { assertTrue("missing original step input: $it", step.contains(it)) }
        listOf("HIGH", "MEDIUM", "LOW").forEach {
            assertTrue(step.contains("StepEventConfidence.$it->PdrStepQuality.$it"))
        }
    }

    private fun compact(text: String): String = text.replace(Regex("\\s+"), "")

    private fun assertBefore(text: String, first: String, second: String) {
        val firstIndex = text.indexOf(first)
        val secondIndex = text.indexOf(second)
        assertTrue("$first must precede $second", firstIndex >= 0 && secondIndex > firstIndex)
    }

    private fun braceBlock(text: String, marker: String): String {
        val start = text.indexOf(marker)
        assertTrue("missing producer/consumer: $marker", start >= 0)
        val open = text.indexOf('{', start)
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '{' -> depth += 1
                '}' -> if (--depth == 0) return text.substring(start, index + 1)
            }
        }
        error("unterminated producer/consumer: $marker")
    }
}
