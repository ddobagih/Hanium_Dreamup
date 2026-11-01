package kr.co.hanium.dreamup.walksafe.runtime.vision.remote

import android.os.Parcel
import android.os.SharedMemory
import android.system.OsConstants
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One flight owns all mappings and FDs; no mapped buffer escapes the synchronous engine call. */
internal class VisionSharedMemory private constructor() : AutoCloseable {
    private val regions = mutableListOf<SharedMemory>()
    private val mappings = mutableListOf<ByteBuffer>()
    private val unmapped = java.util.IdentityHashMap<ByteBuffer, Boolean>()
    private val closed = java.util.IdentityHashMap<SharedMemory, Boolean>()
    var input: ByteBuffer? = null
        private set
    var outputs: Array<ByteBuffer> = emptyArray()
        private set

    fun write(parcel: Parcel) {
        parcel.writeInt(regions.size)
        regions.forEach { parcel.writeParcelable(it, 0) }
    }

    fun copyOutputs(targets: Array<ByteBuffer>) {
        require(targets.size == outputs.size)
        targets.indices.forEach { i ->
            require(!targets[i].isReadOnly && targets[i].capacity() == outputs[i].capacity())
            targets[i].duplicate().apply { clear(); put(outputs[i].duplicate().apply { clear() }) }
        }
    }

    /** Idempotent and retryable: an unmap/close failure never becomes a successful release. */
    @Synchronized override fun close() {
        var failure: Throwable? = null
        mappings.forEach { mapping -> if (unmapped[mapping] != true) try {
            SharedMemory.unmap(mapping); unmapped[mapping] = true
        } catch (error: Throwable) { failure = error } }
        if (mappings.all { unmapped[it] == true }) regions.forEach { region -> if (closed[region] != true) try {
            region.close(); closed[region] = true
        } catch (error: Throwable) { failure = error } }
        failure?.let { throw VisionRuntimeException("shared_memory_release_unconfirmed", it) }
    }

    companion object {
        fun validateSizes(inputSize: Int, outputSizes: IntArray) {
            require(inputSize in 1..VisionRuntimeProtocol.MAX_INPUT_BYTES)
            require(outputSizes.size in 1..2 && outputSizes.all { it > 0 })
            val output = outputSizes.fold(0, Math::addExact)
            require(output <= VisionRuntimeProtocol.MAX_OUTPUT_BYTES)
            require(Math.addExact(inputSize, output) <= VisionRuntimeProtocol.MAX_REQUEST_BYTES)
        }

        fun parent(input: ByteBuffer, targets: Array<ByteBuffer>, inputSize: Int, outputSizes: IntArray,
            register: (VisionSharedMemory) -> Unit): VisionSharedMemory {
            validateSizes(inputSize, outputSizes)
            require(input.capacity() == inputSize && input.remaining() == inputSize)
            require(targets.size == outputSizes.size && targets.indices.all {
                !targets[it].isReadOnly && targets[it].capacity() == outputSizes[it]
            })
            val value = VisionSharedMemory()
            register(value) // A partial allocation is retained by the same physical-release fence.
            with(value) {
                val source = SharedMemory.create("WalkSafeVisionInput", inputSize).also(regions::add)
                val writable = source.mapReadWrite()
                mappings += writable
                writable.put(input.duplicate())
                SharedMemory.unmap(writable); unmapped[writable] = true
                require(source.setProtect(OsConstants.PROT_READ))
                outputs = outputSizes.map { size ->
                    SharedMemory.create("WalkSafeVisionOutput", size).also(regions::add)
                        .mapReadWrite().order(ByteOrder.LITTLE_ENDIAN).also(mappings::add)
                }.toTypedArray()
                return value
            }
        }

        @Suppress("DEPRECATION") fun child(parcel: Parcel, inputSize: Int, outputSizes: IntArray): VisionSharedMemory {
            validateSizes(inputSize, outputSizes)
            require(parcel.readInt() == outputSizes.size + 1)
            val value = VisionSharedMemory()
            try {
                (listOf(inputSize) + outputSizes.toList()).forEach { size ->
                    val region = requireNotNull(parcel.readParcelable<SharedMemory>(SharedMemory::class.java.classLoader))
                    value.regions += region
                    require(region.size == size) { "vision_shared_memory_size_mismatch" }
                }
                value.input = value.regions.first().mapReadOnly().order(ByteOrder.LITTLE_ENDIAN).also(value.mappings::add)
                value.outputs = value.regions.drop(1).map { it.mapReadWrite().order(ByteOrder.LITTLE_ENDIAN).also(value.mappings::add) }.toTypedArray()
                return value
            } catch (error: Throwable) {
                runCatching { value.close() }
                throw error
            }
        }
    }
}
