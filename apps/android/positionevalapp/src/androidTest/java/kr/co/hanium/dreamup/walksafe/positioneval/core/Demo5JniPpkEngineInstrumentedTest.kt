package kr.co.hanium.dreamup.walksafe.positioneval.core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Demo5JniPpkEngineInstrumentedTest {
    @Test
    fun testPinnedNativeEngineProducesUtcLlhAndFixedEpochs() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val testRoot = File(instrumentation.targetContext.cacheDir, "rtklib-golden-${System.nanoTime()}")
        check(testRoot.mkdirs())
        try {
            val roverObservation = copyAsset("rtklib/07590920.05o", File(testRoot, "rover.05o"))
            val baseObservation = copyAsset("rtklib/30400920.05o", File(testRoot, "base.05o"))
            val navigation = copyAsset("rtklib/30400920.05n", File(testRoot, "broadcast.05n"))
            val output = File(testRoot, "solution.pos")
            val engine = Demo5JniPpkEngine()

            assertTrue(engine.status.detail, engine.status.available)
            val roverSummary = engine.validateObservation(roverObservation)
            assertTrue(roverSummary.epochCount > 0)
            assertTrue(roverSummary.satelliteCount > 0)
            assertTrue(roverSummary.pseudorangeCount > 0)
            assertTrue(roverSummary.carrierPhaseCount > 0)
            engine.run(
                PpkRequest(
                    roverObservation = roverObservation,
                    roverNavigation = navigation,
                    baseObservations = listOf(baseObservation),
                    baseNavigations = listOf(navigation),
                    outputPosition = output,
                ),
            )

            val parsed = PpkPosParser.parse(output.readText(Charsets.US_ASCII))
            assertEquals("UTC", parsed.timeSystem)
            assertTrue("golden data must include at least one Q=1 epoch", parsed.fixedCount > 0)
        } finally {
            testRoot.deleteRecursively()
        }
    }

    private fun copyAsset(name: String, destination: File): File {
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            destination.outputStream().use { output -> input.copyTo(output) }
        }
        return destination
    }
}
