package kr.co.hanium.dreamup.walksafe.feedback

import java.util.EnumMap

/** Output channel derived from the utterance id prefix; never from the spoken text. */
enum class SpeechOutputKind {
    RISK,
    INTERACTION,
    NAVIGATION,
    ADVISORY,
    OTHER,
}

/** Bounded outcome vocabulary for release diagnostics (13.txt #38). */
enum class SpeechOutputOutcome {
    STARTED,
    DONE,
    STOPPED,
    INTERRUPTED,
    ENGINE_ERROR,
    TERMINAL_TIMEOUT,
    EXPIRED_BEFORE_START,
    FOCUS_DENIED,
    FOCUS_LOST,
    CANCELLED_BY_PRIORITY,
    CANCELLED_STALE_NAVIGATION,
}

/**
 * One output outcome. It carries no utterance id, text, destination or report content: only the
 * channel, the outcome, the monotonic time and the platform error code when there is one.
 */
data class SpeechOutputEvent(
    val kind: SpeechOutputKind,
    val outcome: SpeechOutputOutcome,
    val atElapsedRealtimeMs: Long,
    val errorCode: Int? = null,
)

internal fun speechOutputKindOf(utteranceId: String?): SpeechOutputKind = when {
    utteranceId == null -> SpeechOutputKind.OTHER
    utteranceId.startsWith("risk-") -> SpeechOutputKind.RISK
    utteranceId.startsWith("interaction-") -> SpeechOutputKind.INTERACTION
    utteranceId.startsWith("nav-") -> SpeechOutputKind.NAVIGATION
    utteranceId.startsWith("advisory-") -> SpeechOutputKind.ADVISORY
    else -> SpeechOutputKind.OTHER
}

/**
 * Small in-memory aggregate of [SpeechOutputEvent]s: counts per channel×outcome, counts per
 * platform error code (capped key set) and the last event time. A caller that holds the
 * required consent drains it into its own bounded channel; nothing here is persisted or sent.
 */
class SpeechOutputDiagnostics {
    private val counts = EnumMap<SpeechOutputKind, EnumMap<SpeechOutputOutcome, Int>>(
        SpeechOutputKind::class.java,
    )
    private val errorCodes = linkedMapOf<Int, Int>()
    private var lastEventAtElapsedRealtimeMs: Long? = null

    @Synchronized
    fun record(event: SpeechOutputEvent) {
        val byOutcome = counts.getOrPut(event.kind) {
            EnumMap<SpeechOutputOutcome, Int>(SpeechOutputOutcome::class.java)
        }
        byOutcome[event.outcome] = saturatingIncrement(byOutcome[event.outcome] ?: 0)
        event.errorCode?.let { code ->
            if (code in errorCodes || errorCodes.size < MAX_ERROR_CODES) {
                errorCodes[code] = saturatingIncrement(errorCodes[code] ?: 0)
            }
        }
        lastEventAtElapsedRealtimeMs = event.atElapsedRealtimeMs
    }

    @Synchronized
    fun count(kind: SpeechOutputKind, outcome: SpeechOutputOutcome): Int =
        counts[kind]?.get(outcome) ?: 0

    /** Non-zero entries only, e.g. `risk.focus_denied=1`, `error_code.-3=2`. */
    @Synchronized
    fun snapshot(): Map<String, Int> = buildMap {
        counts.forEach { (kind, byOutcome) ->
            byOutcome.forEach { (outcome, value) ->
                if (value > 0) put("${kind.name.lowercase()}.${outcome.name.lowercase()}", value)
            }
        }
        errorCodes.forEach { (code, value) -> put("error_code.$code", value) }
    }

    @Synchronized
    fun lastEventAtElapsedRealtimeMs(): Long? = lastEventAtElapsedRealtimeMs

    /** Returns the current aggregate and starts a new one (one capture window). */
    @Synchronized
    fun drain(): Map<String, Int> = snapshot().also {
        counts.clear()
        errorCodes.clear()
        lastEventAtElapsedRealtimeMs = null
    }

    private fun saturatingIncrement(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1

    private companion object {
        const val MAX_ERROR_CODES = 16
    }
}
