package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Reads the generated variant class; runner expectations must be supplied independently. */
class BuildVariantIsolationTest {
    @Test
    fun generatedVariantContainsOnlyTheExpectedDevelopmentFeatures() {
        val fields = BuildConfig::class.java.fields.associateBy { it.name }
        val expected = linkedMapOf(
            "DEVELOPMENT_QUICK_START" to "walksafe.expectedDevelopmentQuickStart",
            "WALKSAFE_REPORT_QUEUE_ENABLED" to "walksafe.expectedReportQueue",
            "WALKSAFE_DEBUG_GUIDANCE_START_BYPASS" to "walksafe.expectedGuidanceStartBypass",
        )
        expected.forEach { (field, property) ->
            val configured = requireNotNull(System.getProperty(property)) { "missing matrix expectation $property" }
            require(configured == "true" || configured == "false")
            assertEquals(field, configured.toBoolean(), fields.getValue(field).getBoolean(null))
        }
        assertFalse("DEVELOPMENT_QUICK_START_EMAIL" in fields)
        assertFalse("DEVELOPMENT_QUICK_START_PASSWORD" in fields)
    }
}
