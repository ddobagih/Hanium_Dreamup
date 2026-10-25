package kr.co.hanium.dreamup.walksafe.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.DeadObjectException
import android.os.Looper
import android.os.Parcel
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Synchronous PCM backpressure without loading libvosk in the microphone/UI process. */
internal class RemoteVoskDecoder private constructor(
    private val context: Context,
    private val connection: Connection,
    private val binder: IBinder,
) : VoskDecoder {
    private val closed = AtomicBoolean(false)

    override fun acceptWaveForm(samples: ShortArray, length: Int): Boolean {
        require(length == samples.size && length in 1..VoskDecoderProtocol.MAX_CHUNK_SAMPLES)
        return transact(VoskDecoderProtocol.ACCEPT, {
            writeIntArray(IntArray(length) { samples[it].toInt() })
        }) { readInt() != 0 }
    }

    override val result: String
        get() = transact(VoskDecoderProtocol.RESULT) { checkNotNull(readString()) }
    override val partialResult: String
        get() = transact(VoskDecoderProtocol.PARTIAL) { checkNotNull(readString()) }

    override fun reset() = transact(VoskDecoderProtocol.RESET) { Unit }

    private fun <T> transact(
        code: Int,
        write: Parcel.() -> Unit = {},
        read: Parcel.() -> T,
    ): T {
        check(!closed.get()) { "Decoder is closed" }
        return decoderTransaction(binder, VoskDecoderProtocol.DECODER_DESCRIPTOR, code, write, read)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        connection.stopObservingDeath()
        try {
            decoderTransaction(binder, VoskDecoderProtocol.DECODER_DESCRIPTOR, VoskDecoderProtocol.CLOSE, {}, { Unit })
        } finally {
            connection.unbind(context)
        }
    }

    private class Connection(private val onFailure: () -> Unit) : ServiceConnection {
        val connected = CountDownLatch(1)
        @Volatile var factory: IBinder? = null
        @Volatile var failed = false
        private val intentionalClose = AtomicBoolean(false)
        private val failureReported = AtomicBoolean(false)
        private val bindingReleased = AtomicBoolean(false)
        private var decoder: IBinder? = null
        private val deathRecipient = IBinder.DeathRecipient { fail() }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            factory = service
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName) = fail()
        override fun onBindingDied(name: ComponentName) = fail()
        override fun onNullBinding(name: ComponentName) = fail()

        private fun fail() {
            failed = true
            connected.countDown()
            if (!intentionalClose.get() && failureReported.compareAndSet(false, true)) onFailure()
        }

        fun observeDeath(value: IBinder) {
            decoder = value
            value.linkToDeath(deathRecipient, 0)
        }

        fun reportPreparationDeath() {
            failed = true
            if (failureReported.compareAndSet(false, true)) onFailure()
        }

        fun stopObservingDeath() {
            intentionalClose.set(true)
            decoder?.let { runCatching { it.unlinkToDeath(deathRecipient, 0) } }
        }

        fun unbind(context: Context) {
            stopObservingDeath()
            if (bindingReleased.compareAndSet(false, true)) context.unbindService(this)
        }
    }

    companion object {
        fun open(
            context: Context,
            modelDirectory: File,
            homeWakeEndpointing: Boolean,
            onFailure: () -> Unit,
        ): RemoteVoskDecoder {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Decoder preparation cannot block Main" }
            val app = context.applicationContext
            val connection = Connection(onFailure)
            try {
                check(app.bindService(Intent(app, VoskDecoderService::class.java), connection, Context.BIND_AUTO_CREATE)) {
                    "Decoder service binding was rejected"
                }
                check(connection.connected.await(5, TimeUnit.SECONDS) && !connection.failed) { "Decoder service did not connect" }
                val factory = checkNotNull(connection.factory)
                val decoder = decoderTransaction(factory, VoskDecoderProtocol.FACTORY_DESCRIPTOR, VoskDecoderProtocol.OPEN, {
                    writeString(modelDirectory.absolutePath)
                    writeInt(if (homeWakeEndpointing) 1 else 0)
                }) { checkNotNull(readStrongBinder()) }
                val proxy = RemoteVoskDecoder(app, connection, decoder)
                try {
                    connection.observeDeath(decoder)
                    check(!connection.failed) { "Decoder service died during preparation" }
                } catch (failure: Exception) {
                    runCatching { proxy.close() }
                    throw failure
                }
                return proxy
            } catch (failure: Exception) {
                // The synchronous OPEN can observe death before the main-thread callback.
                // Preserve that cause even when failed proxy preparation already unbound.
                if (failure is DeadObjectException) connection.reportPreparationDeath()
                // An early failure has no proxy to close; release only this caller's binding.
                runCatching { connection.unbind(app) }
                throw failure
            }
        }
    }
}

private fun <T> decoderTransaction(
    binder: IBinder,
    descriptor: String,
    code: Int,
    write: Parcel.() -> Unit,
    read: Parcel.() -> T,
): T {
    val data = Parcel.obtain()
    val reply = Parcel.obtain()
    try {
        data.writeInterfaceToken(descriptor)
        data.write()
        check(binder.transact(code, data, reply, 0)) { "Decoder transaction was rejected" }
        reply.readException()
        return reply.read()
    } finally {
        reply.recycle()
        data.recycle()
    }
}
