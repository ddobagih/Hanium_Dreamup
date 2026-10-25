package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source mutation coverage only; these assertions do not execute Main or prove runtime effects. */
class MainActivityRefactorSourceDelegationTest {
    private val main get() = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val name = "cancelNativePendingFeatureEntry"
    private val signature = "private fun $name("
    private val call = "nativeWorkflowController.$name()"

    @Test fun directExpressionStillResolvesTheActualOwner() {
        val resolved = inspect("    private fun $name() = $call\n\n")
        assertFalse(resolved.contains(call))
        assertTrue(resolved.contains("fun $name("))
    }

    @Test fun directBlockStillResolvesTheActualOwner() {
        val resolved = inspect("    private fun $name() { $call }\n\n")
        assertFalse(resolved.contains(call))
        assertTrue(resolved.contains("fun $name("))
    }

    @Test fun singleLineFalseGuardCannotStandInForTheOwnerBody() = retained(
        "    private fun $name() { if (false) $call }\n\n",
    )

    @Test fun multilineFalseGuardCannotStandInForTheOwnerBody() = retained(
        "    private fun $name() {\n        if (false) { $call }\n    }\n\n",
    )

    @Test fun conditionalExpressionCannotStandInForTheOwnerBody() = retained(
        "    private fun $name() = if (false) $call else Unit\n\n",
    )

    @Test fun uninvokedLambdaCannotStandInForTheOwnerBody() = retained(
        "    private fun $name() { val later = { $call } }\n\n",
    )

    @Test fun commentCannotStandInForTheOwnerBody() = retained(
        "    private fun $name() { /* $call */ }\n\n",
    )

    @Test fun extraCallerEffectsRemainVisibleToStructuralTests() = retained(
        "    private fun $name() { $call; error(\"caller effect\") }\n\n",
    )

    private fun inspect(replacement: String): String {
        val source = main
        val original = MainActivityRefactorSource.member(source, name)
        return MainActivityRefactorSource.function(source.replace(original, replacement), signature)
    }

    private fun retained(replacement: String) {
        assertEquals("A guarded or indirect call must remain caller text, not owner behavior", replacement, inspect(replacement))
    }
}
