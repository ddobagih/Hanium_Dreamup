package kr.co.hanium.dreamup.walksafe.device

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The probe accepted any output above zero bytes, so a header with no samples behind it passed
 * and the device check reported speech the phone had not produced.
 *
 * Byte size cannot be the floor: the same sentence measured 81,384 B on Google's engine and
 * 99,884 B on Samsung's (SM-A716S, 2026-09-08), a 23 % spread from sample rate alone. Duration
 * is comparable across engines, and both engines cleared 1,694 ms for the fixed probe sentence.
 */
class KoreanTtsOutputDurationPolicyTest {
    @Test
    fun theFloorSitsWellBelowWhatBothMeasuredEnginesProduced() {
        assertEquals(
            KoreanTtsOutputDurationPolicy.MINIMUM_DURATION_MS,
            1_000L,
        )
    }

    @Test
    fun measuredOutputFromEitherEnginePasses() {
        // Google 24 kHz mono 16-bit, 1,694 ms; Samsung same format, 2,080 ms.
        assertEquals(
            KoreanTtsOutputDurationVerdict.SUFFICIENT,
            KoreanTtsOutputDurationPolicy.verify(wav(durationMs = 1_694)),
        )
        assertEquals(
            KoreanTtsOutputDurationVerdict.SUFFICIENT,
            KoreanTtsOutputDurationPolicy.verify(wav(durationMs = 2_080)),
        )
    }

    @Test
    fun aHeaderWithNoAudioBehindItIsRejected() {
        assertEquals(
            KoreanTtsOutputDurationVerdict.TOO_SHORT,
            KoreanTtsOutputDurationPolicy.verify(wav(durationMs = 0)),
        )
    }

    @Test
    fun aTruncatedUtteranceIsRejected() {
        assertEquals(
            KoreanTtsOutputDurationVerdict.TOO_SHORT,
            KoreanTtsOutputDurationPolicy.verify(wav(durationMs = 300)),
        )
    }

    @Test
    fun anEmptyFileIsRejectedWithoutParsing() {
        assertEquals(
            KoreanTtsOutputDurationVerdict.EMPTY,
            KoreanTtsOutputDurationPolicy.verify(ByteArray(0)),
        )
    }

    @Test
    fun bytesThatAreNotRiffAreNotGuessedAt() {
        assertEquals(
            KoreanTtsOutputDurationVerdict.UNREADABLE,
            KoreanTtsOutputDurationPolicy.verify("not a wav file at all".toByteArray()),
        )
    }

    @Test
    fun aSampleRateOfZeroCannotYieldADuration() {
        assertEquals(
            KoreanTtsOutputDurationVerdict.UNREADABLE,
            KoreanTtsOutputDurationPolicy.verify(wav(durationMs = 2_000, sampleRate = 0)),
        )
    }

    @Test
    fun aLowerSampleRateCarryingTheSameSecondsStillPasses() {
        // Duration, not size: 16 kHz holds the same utterance in two thirds of the bytes.
        assertEquals(
            KoreanTtsOutputDurationVerdict.SUFFICIENT,
            KoreanTtsOutputDurationPolicy.verify(wav(durationMs = 1_694, sampleRate = 16_000)),
        )
    }

    private fun wav(
        durationMs: Int,
        sampleRate: Int = 24_000,
        channels: Int = 1,
        bitsPerSample: Int = 16,
    ): ByteArray {
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val dataBytes = (byteRate.toLong() * durationMs / 1_000L).toInt()
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray())
        out.writeLe32(36 + dataBytes)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.writeLe32(16)
        out.writeLe16(1)
        out.writeLe16(channels)
        out.writeLe32(sampleRate)
        out.writeLe32(byteRate)
        out.writeLe16(channels * (bitsPerSample / 8))
        out.writeLe16(bitsPerSample)
        out.write("data".toByteArray())
        out.writeLe32(dataBytes)
        out.write(ByteArray(dataBytes))
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
