package kr.co.hanium.dreamup.walksafe.voice

import java.io.Closeable

/** Native decoding is separate from the microphone owner and its input generations. */
internal interface VoskDecoder : Closeable {
    fun acceptWaveForm(samples: ShortArray, length: Int): Boolean
    val result: String
    val partialResult: String
    fun reset()
}

/** Private same-UID protocol; replies contain values and never call the microphone owner. */
internal object VoskDecoderProtocol {
    const val PROCESS_SUFFIX = ":vosk_decoder"
    const val FACTORY_DESCRIPTOR = "walksafe.vosk.DecoderFactory"
    const val DECODER_DESCRIPTOR = "walksafe.vosk.Decoder"
    const val OPEN = android.os.IBinder.FIRST_CALL_TRANSACTION
    const val ACCEPT = android.os.IBinder.FIRST_CALL_TRANSACTION
    const val RESULT = ACCEPT + 1
    const val PARTIAL = ACCEPT + 2
    const val RESET = ACCEPT + 3
    const val CLOSE = ACCEPT + 4
    const val MAX_CHUNK_SAMPLES = 4_096
}
