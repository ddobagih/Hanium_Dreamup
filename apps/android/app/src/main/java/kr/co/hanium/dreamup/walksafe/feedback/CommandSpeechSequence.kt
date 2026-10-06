package kr.co.hanium.dreamup.walksafe.feedback

/** Advance only on this part's terminal callback, never on the shared speaker's busy flag. */
internal class CommandSpeechSequence(
    private val parts: List<String>,
    private val isCurrent: () -> Boolean,
    private val speak: (String, () -> Unit, () -> Unit) -> Boolean,
    private val onPartCompleted: (Int) -> Unit,
    private val onCompleted: () -> Unit,
    private val onFailed: () -> Unit,
) {
    private var next = 0
    private var ended = false
    private var started = false

    fun start(): Boolean {
        if (started || ended) return false
        started = true
        if (parts.isEmpty()) { fail(); return false }
        return dispatch()
    }

    private fun fail() {
        if (ended) return
        ended = true
        onFailed()
    }

    private fun dispatch(): Boolean {
        if (!isCurrent()) { fail(); return false }
        val index = next
        val accepted = speak(parts[index], done@{
            if (ended || next != index) return@done
            if (!isCurrent()) { fail(); return@done }
            next++
            onPartCompleted(index)
            if (!isCurrent()) { fail(); return@done }
            if (next == parts.size) {
                ended = true
                onCompleted()
            } else dispatch()
        }, failed@{
            if (!ended && next == index) fail()
        })
        if (!accepted && next == index) fail()
        return accepted
    }
}
