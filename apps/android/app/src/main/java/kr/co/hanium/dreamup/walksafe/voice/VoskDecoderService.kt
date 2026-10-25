package kr.co.hanium.dreamup.walksafe.voice

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import java.io.File
import org.vosk.Model
import org.vosk.Recognizer

/** Holds only native resources. AudioRecord, account authority and UI never live here. */
class VoskDecoderService : Service() {
    private val lifecycleLock = Any()
    private val decoders = mutableSetOf<DecoderBinder>()
    private var destroyed = false

    private val factory = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != VoskDecoderProtocol.OPEN) return super.onTransact(code, data, reply, flags)
            enforceOwner()
            data.enforceInterface(VoskDecoderProtocol.FACTORY_DESCRIPTOR)
            val modelPath = requireNotNull(data.readString())
            val endpointOption = data.readInt()
            require(endpointOption == 0 || endpointOption == 1)
            require(data.dataAvail() == 0)
            val output = requireNotNull(reply)
            // Main already verified/repaired the bundled model. Recheck the installed copy here;
            // the private protocol cannot select an arbitrary model or write an installation.
            val directory = checkNotNull(BundledVoskModelInstaller.installedModelOrNull(this@VoskDecoderService))
            require(File(modelPath).canonicalFile == directory.canonicalFile)
            val decoder = DecoderBinder(directory, endpointOption == 1)
            val admitted = synchronized(lifecycleLock) {
                if (destroyed) false else decoders.add(decoder)
            }
            if (!admitted) {
                decoder.closeNative()
                error("Decoder service was destroyed during preparation")
            }
            output.writeNoException()
            output.writeStrongBinder(decoder)
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = factory

    override fun onDestroy() {
        val owned = synchronized(lifecycleLock) {
            destroyed = true
            decoders.toList().also { decoders.clear() }
        }
        owned.forEach { decoder -> runCatching { decoder.closeNative() } }
        super.onDestroy()
    }

    private fun enforceOwner() {
        if (Binder.getCallingUid() != Process.myUid()) throw SecurityException("Decoder caller UID mismatch")
    }

    private inner class DecoderBinder(directory: File, homeWakeEndpointing: Boolean) : Binder() {
        private val decoderLock = Any()
        private val model = Model(directory.absolutePath)
        private val recognizer = try {
            val opened = Recognizer(model, 16_000f)
            try {
                opened.apply {
                    setWords(true)
                    setPartialWords(false)
                    setMaxAlternatives(0)
                    setEndpointerMode(Recognizer.EndpointerMode.SHORT)
                    if (homeWakeEndpointing) {
                        // The same HOME/bounded wake endpoint configuration, in seconds.
                        setEndpointerDelays(5.0f, 0.35f, 5.0f)
                    }
                }
            } catch (failure: Exception) {
                runCatching { opened.close() }
                throw failure
            }
        } catch (failure: Exception) {
            runCatching { model.close() }
            throw failure
        }
        private var closed = false

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in VoskDecoderProtocol.ACCEPT..VoskDecoderProtocol.CLOSE) {
                return super.onTransact(code, data, reply, flags)
            }
            enforceOwner()
            data.enforceInterface(VoskDecoderProtocol.DECODER_DESCRIPTOR)
            val samples = if (code == VoskDecoderProtocol.ACCEPT) {
                val values = requireNotNull(data.createIntArray())
                require(values.isNotEmpty() && values.size <= VoskDecoderProtocol.MAX_CHUNK_SAMPLES)
                require(values.all { it in Short.MIN_VALUE..Short.MAX_VALUE })
                ShortArray(values.size) { values[it].toShort() }
            } else null
            require(data.dataAvail() == 0)
            val output = requireNotNull(reply)
            synchronized(decoderLock) {
                if (code == VoskDecoderProtocol.CLOSE) {
                    closeNative()
                    output.writeNoException()
                } else {
                    check(!closed) { "Decoder is closed" }
                    when (code) {
                        VoskDecoderProtocol.ACCEPT -> {
                            val pcm = checkNotNull(samples)
                            val endpoint = recognizer.acceptWaveForm(pcm, pcm.size)
                            output.writeNoException()
                            output.writeInt(if (endpoint) 1 else 0)
                        }
                        VoskDecoderProtocol.RESULT -> {
                            val result = recognizer.result
                            output.writeNoException()
                            output.writeString(result)
                        }
                        VoskDecoderProtocol.PARTIAL -> {
                            val result = recognizer.partialResult
                            output.writeNoException()
                            output.writeString(result)
                        }
                        VoskDecoderProtocol.RESET -> {
                            recognizer.reset()
                            output.writeNoException()
                        }
                    }
                }
            }
            return true
        }

        fun closeNative() {
            synchronized(decoderLock) {
                if (closed) return
                closed = true
                try {
                    recognizer.close()
                } finally {
                    try {
                        model.close()
                    } finally {
                        synchronized(lifecycleLock) { decoders.remove(this) }
                    }
                }
            }
        }
    }
}
