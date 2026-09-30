package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S02-4: the policy's default remote-evidence verifier always rejects. Production code must never
 * rely on that default; every completion or restore call names the verifier it trusts, so a call
 * that silently falls back to the fail-closed default fails this test instead of the release.
 */
class FirstRunEvidenceVerifierWiringStaticTest {
    private val mainRoot = File("src/main/java")
    private val policyFile = "session/FirstRunOnboardingPolicy.kt"

    @Test
    fun everyProductionCompletionAndRestoreNamesItsVerifier() {
        val calls = listOf(
            "FirstRunOnboardingPolicy.completeAttempt(",
            "FirstRunOnboardingPolicy.restoreVerifiedReceiptPrefix(",
            "FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(",
        )
        val found = calls.associateWith { 0 }.toMutableMap()
        productionSources().forEach { (path, text) ->
            calls.forEach { call ->
                var offset = text.indexOf(call)
                while (offset >= 0) {
                    val arguments = balancedArguments(text, offset + call.length - 1)
                    assertTrue(
                        "$path:${lineOf(text, offset)} relies on the default verifier",
                        arguments.contains("verifier", ignoreCase = true),
                    )
                    found[call] = found.getValue(call) + 1
                    offset = text.indexOf(call, offset + call.length)
                }
            }
        }
        // Guard against a vacuous pass if the call sites are renamed.
        assertTrue(found.getValue(calls[0]) >= 2)
        assertTrue(found.getValue(calls[1]) >= 1)
        assertTrue(found.getValue(calls[2]) >= 2)
    }

    @Test
    fun theFailClosedDefaultIsNotWiredIntoProductionCode() {
        val references = productionSources().filter { (path, text) ->
            !path.endsWith(policyFile) &&
                (text.contains("productionEvidenceVerifier") ||
                    text.contains("productionEvidenceAvailable"))
        }.map { it.first }
        assertEquals(emptyList<String>(), references)
    }

    private fun productionSources(): List<Pair<String, String>> =
        mainRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.invariantSeparatorsPath to it.readText() }
            .toList()
            .also { assertTrue("main sources not found from ${File(".").absolutePath}", it.isNotEmpty()) }

    private fun balancedArguments(text: String, openParenthesis: Int): String {
        check(text[openParenthesis] == '(')
        var depth = 0
        for (index in openParenthesis until text.length) {
            when (text[index]) {
                '(' -> depth += 1
                ')' -> {
                    depth -= 1
                    if (depth == 0) return text.substring(openParenthesis + 1, index)
                }
            }
        }
        error("Unbalanced call arguments")
    }

    private fun lineOf(text: String, offset: Int): Int =
        text.substring(0, offset).count { it == '\n' } + 1
}
