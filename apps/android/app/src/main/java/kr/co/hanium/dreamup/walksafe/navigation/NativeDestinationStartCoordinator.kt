package kr.co.hanium.dreamup.walksafe.navigation

/** Owns one native start approval until the route request or its explicit location wait accepts it. */
internal class NativeDestinationStartCoordinator(
    private val onCleared: () -> Unit,
) {
    private class Pending(
        val destination: DestinationSearchResult,
        val isCurrent: () -> Boolean,
    ) {
        var posted = false
        var handingOff = false
    }

    private var pending: Pending? = null
    var isDispatching: Boolean = false
        private set

    fun clear() {
        pending = null
        onCleared()
    }

    /** Captures a new owner only when no approval is already pending. */
    fun retain(
        destination: DestinationSearchResult,
        captureOwner: () -> (() -> Boolean)?,
    ): Boolean {
        pending?.let { existing ->
            if (existing.destination === destination && existing.isCurrent()) return true
            clear()
            return false
        }
        val current = captureOwner() ?: return false
        pending = Pending(destination, current)
        return true
    }

    fun consumableDestination(): DestinationSearchResult? = pending?.let {
        it.destination.takeUnless { _ -> it.posted || it.handingOff || isDispatching }
    }

    fun post(
        post: (Runnable) -> Unit,
        admitted: () -> Boolean,
        start: () -> Unit,
    ) {
        val owner = pending ?: return
        if (owner.posted || owner.handingOff || isDispatching) return
        owner.posted = true
        post(Runnable {
            if (pending !== owner) return@Runnable
            owner.posted = false
            if (!owner.isCurrent()) {
                clear()
                return@Runnable
            }
            if (admitted()) {
                isDispatching = true
                try { start() } finally { isDispatching = false }
            }
        })
    }

    fun handOff(
        prepareIfNeeded: () -> Unit,
        admitted: () -> Boolean,
        selectAndCheckExplicitWait: () -> Boolean,
        refreshUi: () -> Unit,
    ) {
        val owner = pending ?: return
        if (owner.handingOff) return
        owner.handingOff = true
        try {
            prepareIfNeeded()
            if (!owner.isCurrent()) {
                clear()
                return
            }
            if (!admitted()) return
            val handedOff = selectAndCheckExplicitWait()
            // Only closed admission retains this approval; an admitted ordinary failure is terminal.
            if (handedOff || !owner.isCurrent() || admitted()) {
                if (pending === owner) clear()
            }
        } finally {
            // A reentrant render cannot post the same approval while this handoff is finishing.
            try { refreshUi() } finally { owner.handingOff = false }
        }
    }
}
