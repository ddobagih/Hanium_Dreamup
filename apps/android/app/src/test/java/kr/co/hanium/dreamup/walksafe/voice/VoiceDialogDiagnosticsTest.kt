package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.diagnostics.RuntimeDiagnosticDomain
import kr.co.hanium.dreamup.walksafe.diagnostics.RuntimeDiagnosticOrigin
import kr.co.hanium.dreamup.walksafe.diagnostics.formatRuntimeDiagnosticState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceDialogDiagnosticsTest {
    @Test
    fun originUsesOnlyTheKnownInternalCallerLabels() {
        assertEquals(
            RuntimeDiagnosticOrigin.MICROPHONE_PATH,
            classifyVoiceDialogDiagnosticOrigin("device_stt"),
        )
        assertEquals(
            RuntimeDiagnosticOrigin.SYNTHETIC_FINAL,
            classifyVoiceDialogDiagnosticOrigin("synthetic_platform_zero_fixture"),
        )
        for (source in listOf(null, "", "synthetic_unrecognized_fixture", "private-free-text")) {
            assertEquals(RuntimeDiagnosticOrigin.UNKNOWN, classifyVoiceDialogDiagnosticOrigin(source))
        }
    }

    @Test
    fun failedRecognitionWithAValidDialogIsDifferentFromMissingContext() {
        val retained = formatRuntimeDiagnosticState(
            RuntimeDiagnosticDomain.VOICE_DIALOG,
            VoiceDialogDiagnosticStage.INPUT_ERROR,
            VoiceDialogDiagnosticReason.NO_MATCH,
            VoiceDialogDiagnosticContext.VALID,
            pageIndex = 2,
        )
        val missing = formatRuntimeDiagnosticState(
            RuntimeDiagnosticDomain.VOICE_DIALOG,
            VoiceDialogDiagnosticStage.RETRY_SKIPPED,
            VoiceDialogDiagnosticReason.NO_DIALOG_CONTEXT,
            VoiceDialogDiagnosticContext.ABSENT_OR_STALE,
        )
        assertTrue(retained.contains("reason=NO_MATCH context=VALID"))
        assertTrue(retained.contains("page_index=2"))
        assertTrue(missing.contains("stage=RETRY_SKIPPED reason=NO_DIALOG_CONTEXT"))
        assertTrue(missing.contains("page_index=NA"))
    }

    @Test
    fun metadataKeepsSourceUnknownUnlessItWasExplicitlyClassified() {
        val line = formatRuntimeDiagnosticState(
            RuntimeDiagnosticDomain.VOICE_DIALOG,
            VoiceDialogDiagnosticStage.FINAL_RECEIVED,
            context = VoiceDialogDiagnosticContext.VALID,
            pageIndex = 0,
        )
        assertTrue(line.contains("origin=UNKNOWN"))
        assertFalse(line.contains("device_stt"))
        assertFalse(line.contains("synthetic_platform_zero_fixture"))
    }

    @Test
    fun unboundedPageNumbersAndNonCanonicalEnumNamesAreNotWritten() {
        for (page in listOf(-1, 10_000, Int.MAX_VALUE)) {
            val line = formatRuntimeDiagnosticState(
                RuntimeDiagnosticDomain.VOICE_DIALOG,
                NonCanonical.not_canonical,
                pageIndex = page,
            )
            assertTrue(line.contains("stage=NA"))
            assertTrue(line.contains("page_index=NA"))
            assertFalse(line.contains("not_canonical"))
            assertFalse(line.contains(page.toString()))
        }
    }

    private enum class NonCanonical { not_canonical }
}
