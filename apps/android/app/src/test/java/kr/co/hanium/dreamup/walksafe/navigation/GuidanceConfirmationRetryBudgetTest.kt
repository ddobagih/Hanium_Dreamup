package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceConfirmationRetryBudgetTest {
    @Test fun repeatedSilenceCannotLoop() {
        val budget = GuidanceConfirmationRetryBudget()
        budget.beginExplicitAttempt()
        assertTrue(budget.allowAutomaticRetry())
        repeat(100) { assertFalse(budget.allowAutomaticRetry()) }
    }
    @Test fun explicitUserRetryRestoresOneAttempt() {
        val budget = GuidanceConfirmationRetryBudget()
        assertTrue(budget.allowAutomaticRetry())
        assertFalse(budget.allowAutomaticRetry())
        budget.beginExplicitAttempt()
        assertTrue(budget.allowAutomaticRetry())
        assertFalse(budget.allowAutomaticRetry())
    }
}
