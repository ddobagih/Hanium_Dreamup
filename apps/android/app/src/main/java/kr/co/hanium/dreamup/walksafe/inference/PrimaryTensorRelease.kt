package kr.co.hanium.dreamup.walksafe.inference

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/** A factory may open a CPU replacement after GPU death; only its terminal seal closes admission. */
internal class PrimaryTensorRelease {
    private val slots = mutableSetOf<Slot>()
    private var sealed = false
    private val released = CompletableFuture<Unit>()
    val actualReleasedFuture: CompletionStage<Unit> get() = released

    internal class Slot internal constructor(internal val completion: CompletableFuture<Unit> = CompletableFuture())

    @Synchronized fun beforeOpen(): Slot {
        check(!sealed) { "primary tensor factory is closed" }
        return Slot().also { slot ->
            slots += slot
            slot.completion.thenRun(::completeIfReleased)
        }
    }

    fun follow(slot: Slot, physicalReceipt: CompletionStage<*>) {
        synchronized(this) { require(slot in slots) { "foreign primary release receipt" } }
        physicalReceipt.whenComplete { _, failure ->
            // A semantic/close failure is not evidence that native resources were reclaimed.
            if (failure == null) slot.completion.complete(Unit)
        }
    }

    fun seal() { synchronized(this) { sealed = true }; completeIfReleased() }

    private fun completeIfReleased() {
        val complete = synchronized(this) { sealed && slots.all { it.completion.isDone } }
        if (complete) released.complete(Unit)
    }
}
