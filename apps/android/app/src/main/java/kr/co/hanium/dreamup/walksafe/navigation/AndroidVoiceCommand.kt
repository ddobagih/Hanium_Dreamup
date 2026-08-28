package kr.co.hanium.dreamup.walksafe.navigation

import java.util.Locale

sealed interface AndroidVoiceCommand {
    data object CreateReport : AndroidVoiceCommand
    data class SetDestination(val placeName: String) : AndroidVoiceCommand
    data class SelectDestinationCandidate(val oneBasedIndex: Int) : AndroidVoiceCommand
    data object HearMoreDestinationCandidates : AndroidVoiceCommand
    data object CancelDestination : AndroidVoiceCommand
    data object NextNavigationInstruction : AndroidVoiceCommand
    data object RequestReroute : AndroidVoiceCommand
    data object RecheckLocation : AndroidVoiceCommand
    data object ConfirmArrival : AndroidVoiceCommand
    data object RejectArrival : AndroidVoiceCommand
    data object StopNavigation : AndroidVoiceCommand
}

sealed interface AndroidVoiceAction {
    data object CreateReport : AndroidVoiceAction
    data class SearchDestination(val query: String) : AndroidVoiceAction
    data class SelectDestinationCandidate(val oneBasedIndex: Int) : AndroidVoiceAction
    data object HearMoreDestinationCandidates : AndroidVoiceAction
    data object CancelDestination : AndroidVoiceAction
    data object SpeakNextNavigationInstruction : AndroidVoiceAction
    data object RequestReroute : AndroidVoiceAction
    data object RecheckLocation : AndroidVoiceAction
    data object ConfirmArrival : AndroidVoiceAction
    data object RejectArrival : AndroidVoiceAction
    data object StopNavigation : AndroidVoiceAction
}

sealed interface DestinationSearchVoiceCommand {
    data object HearMore : DestinationSearchVoiceCommand
    data class SelectCandidate(val oneBasedIndex: Int) : DestinationSearchVoiceCommand
}

data class DestinationSearchVoiceTransition(
    val state: DestinationSearchVoiceState,
    val accepted: Boolean,
    val selectedOneBasedIndex: Int? = null,
    val selectedResult: DestinationSearchResult? = null,
)

data class DestinationSearchVoiceState(
    val query: String,
    val results: List<DestinationSearchResult>,
    val pageIndex: Int = 0,
    val moreResultsAvailable: Boolean = false,
) {
    init {
        val lastPageIndex = if (results.isEmpty()) 0 else (results.size - 1) / DESTINATION_VOICE_PAGE_SIZE
        require(pageIndex in 0..lastPageIndex) { "pageIndex is outside the destination results" }
    }

    private val pageOffset: Int
        get() = pageIndex * DESTINATION_VOICE_PAGE_SIZE

    val currentPageResults: List<DestinationSearchResult>
        get() = results.drop(pageOffset).take(DESTINATION_VOICE_PAGE_SIZE)

    val hasMoreResults: Boolean
        get() = pageOffset + currentPageResults.size < results.size

    val canHearMore: Boolean
        get() = hasMoreResults || moreResultsAvailable

    fun voicePrompt(): String {
        val safeQuery = query.toVoiceLabel(maxLength = 40, fallback = "요청한")
        if (results.isEmpty()) return "${safeQuery} 목적지 검색 결과가 없습니다."

        val candidates = currentPageResults.mapIndexed { index, result ->
            val name = result.name.toVoiceLabel(maxLength = 40, fallback = "이름 미상")
            val address = (result.roadAddress?.takeIf(String::isNotBlank) ?: result.address)
                .toVoiceLabel(maxLength = 60, fallback = "주소 미상")
            "${pageOffset + index + 1}번 ${name}, ${address}, ${formatDestinationDistance(result.distanceM)}"
        }.joinToString(". ")
        val moreInstruction = if (canHearMore) {
            " 더 들으려면 더 듣기라고 말씀해 주세요."
        } else {
            ""
        }
        return "${safeQuery} 목적지 후보가 ${results.size}곳 있습니다. " +
            "${candidates}.${moreInstruction} 원하는 번호를 말씀해 주세요."
    }

    fun onCommand(command: DestinationSearchVoiceCommand): DestinationSearchVoiceTransition {
        return when (command) {
            DestinationSearchVoiceCommand.HearMore -> {
                if (hasMoreResults) {
                    DestinationSearchVoiceTransition(copy(pageIndex = pageIndex + 1), accepted = true)
                } else {
                    DestinationSearchVoiceTransition(this, accepted = false)
                }
            }
            is DestinationSearchVoiceCommand.SelectCandidate -> {
                val zeroBasedIndex = command.oneBasedIndex - 1
                val pageEndExclusive = pageOffset + currentPageResults.size
                if (zeroBasedIndex in pageOffset until pageEndExclusive) {
                    DestinationSearchVoiceTransition(
                        state = this,
                        accepted = true,
                        selectedOneBasedIndex = command.oneBasedIndex,
                        selectedResult = results[zeroBasedIndex],
                    )
                } else {
                    DestinationSearchVoiceTransition(this, accepted = false)
                }
            }
        }
    }
}

fun AndroidVoiceCommand.toAction(): AndroidVoiceAction {
    return when (this) {
        AndroidVoiceCommand.CreateReport -> AndroidVoiceAction.CreateReport
        is AndroidVoiceCommand.SetDestination -> AndroidVoiceAction.SearchDestination(placeName)
        is AndroidVoiceCommand.SelectDestinationCandidate -> AndroidVoiceAction.SelectDestinationCandidate(oneBasedIndex)
        AndroidVoiceCommand.HearMoreDestinationCandidates -> AndroidVoiceAction.HearMoreDestinationCandidates
        AndroidVoiceCommand.CancelDestination -> AndroidVoiceAction.CancelDestination
        AndroidVoiceCommand.NextNavigationInstruction -> AndroidVoiceAction.SpeakNextNavigationInstruction
        AndroidVoiceCommand.RequestReroute -> AndroidVoiceAction.RequestReroute
        AndroidVoiceCommand.RecheckLocation -> AndroidVoiceAction.RecheckLocation
        AndroidVoiceCommand.ConfirmArrival -> AndroidVoiceAction.ConfirmArrival
        AndroidVoiceCommand.RejectArrival -> AndroidVoiceAction.RejectArrival
        AndroidVoiceCommand.StopNavigation -> AndroidVoiceAction.StopNavigation
    }
}

/** Executes only the recognizer's top hypothesis; lower alternatives must never override it. */
fun selectAndroidVoiceAction(
    phrases: List<String>,
    confidenceScores: FloatArray? = null,
    minimumConfidence: Float = MIN_VOICE_CONFIDENCE,
): AndroidVoiceAction? {
    val topPhrase = phrases.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
    val topConfidence = confidenceScores?.getOrNull(0)
    if (topConfidence != null && (!topConfidence.isFinite() || topConfidence < minimumConfidence)) return null
    return parseAndroidVoiceCommand(topPhrase)?.toAction()
}

/** Reads enough context for a voice-only user to choose a numbered TMAP result safely. */
fun formatDestinationSearchVoicePrompt(
    query: String,
    results: List<DestinationSearchResult>,
): String = DestinationSearchVoiceState(query = query, results = results).voicePrompt()

fun parseDestinationSearchVoiceCommand(text: String): DestinationSearchVoiceCommand? {
    val compact = text
        .trim()
        .lowercase(Locale.KOREAN)
        .replace(PUNCTUATION, "")
        .replace(WHITESPACE, "")
    if (compact.isBlank() || NEGATION_MARKERS.any(compact::contains)) return null
    if (compact == "더듣기") return DestinationSearchVoiceCommand.HearMore
    val oneBasedIndex = DESTINATION_PAGE_SELECTION_NUMBER.matchEntire(compact)
        ?.groupValues
        ?.get(1)
        ?.toIntOrNull()
        ?: return null
    return DestinationSearchVoiceCommand.SelectCandidate(oneBasedIndex)
}

fun parseAndroidVoiceCommand(text: String): AndroidVoiceCommand? {
    val normalized = text
        .trim()
        .lowercase(Locale.KOREAN)
        .replace(PUNCTUATION, "")
        .replace(WHITESPACE, " ")
    val compact = normalized.replace(" ", "")
    if (compact.isBlank() || NEGATION_MARKERS.any(compact::contains)) return null

    if (compact in DESTINATION_CANCEL_COMMANDS) return AndroidVoiceCommand.CancelDestination
    if (compact == "더듣기") return AndroidVoiceCommand.HearMoreDestinationCandidates
    if (compact in NEXT_NAVIGATION_COMMANDS) return AndroidVoiceCommand.NextNavigationInstruction
    if (compact in REROUTE_COMMANDS) return AndroidVoiceCommand.RequestReroute
    if (compact in LOCATION_RECHECK_COMMANDS) return AndroidVoiceCommand.RecheckLocation
    if (compact in ARRIVAL_CONFIRM_COMMANDS) return AndroidVoiceCommand.ConfirmArrival
    if (compact in ARRIVAL_REJECT_COMMANDS) return AndroidVoiceCommand.RejectArrival
    if (compact in STOP_NAVIGATION_COMMANDS) return AndroidVoiceCommand.StopNavigation
    if (isReportCommand(compact)) return AndroidVoiceCommand.CreateReport
    destinationCandidateIndex(compact)?.let { return AndroidVoiceCommand.SelectDestinationCandidate(it) }

    destinationFromPrefixCommand(normalized)?.let { return AndroidVoiceCommand.SetDestination(it) }
    destinationFromSuffixCommand(normalized)?.let { return AndroidVoiceCommand.SetDestination(it) }
    return null
}

private fun destinationFromPrefixCommand(text: String): String? {
    val match = DESTINATION_PREFIX.matchEntire(text) ?: return null
    return normalizeDestination(match.groupValues[1])
}

private fun destinationFromSuffixCommand(text: String): String? {
    val match = DESTINATION_SUFFIX.matchEntire(text) ?: return null
    return normalizeDestination(match.groupValues[1], removeFinalParticle = true)
}

private fun normalizeDestination(value: String, removeFinalParticle: Boolean = false): String? {
    var destination = value.trim()
    destination = when {
        destination.endsWith("으로") -> destination.dropLast(2).trim()
        destination.endsWith("로로") -> destination.dropLast(1).trim()
        removeFinalParticle && destination.endsWith("로") -> destination.dropLast(1).trim()
        else -> destination
    }
    return destination.takeIf { it.isNotBlank() && it.length <= MAX_DESTINATION_LENGTH }
}

private fun isReportCommand(compact: String): Boolean {
    return REPORT_COMMAND.matches(compact)
}

private fun destinationCandidateIndex(compact: String): Int? {
    val numeric = DESTINATION_CANDIDATE_NUMBER.matchEntire(compact)?.groupValues?.get(1)?.toIntOrNull()
    if (numeric != null) return numeric.takeIf { it in 1..MAX_DESTINATION_CANDIDATES }
    val ordinalCommand = compact.removePrefix("목적지")
    return DESTINATION_ORDINALS.entries.firstOrNull { (phrase) ->
        ordinalCommand in setOf(
            "${phrase}선택",
            "${phrase}선택해",
            "${phrase}선택해줘",
            "${phrase}목적지선택",
            "${phrase}목적지선택해",
        )
    }?.value
}

private val WHITESPACE = Regex("\\s+")
private val PUNCTUATION = Regex("[.,!?~。？！]+")
private val REPORT_COMMAND = Regex("(?:(?:이거|여기|위험)(?:을|를)?)?(?:신고|싱고)(?:해|해줘|해주세요|접수)?")
private val DESTINATION_CANDIDATE_NUMBER = Regex(
    "(?:목적지)?([1-9]\\d*)번(?:목적지)?(?:을|를)?(?:선택|골라)(?:해|해줘|해주세요)?",
)
private val DESTINATION_PAGE_SELECTION_NUMBER = Regex(
    "(?:목적지)?([1-9]\\d*)번(?:목적지)?(?:을|를)?선택(?:해|해줘|해주세요)?",
)
private val DESTINATION_ORDINALS = mapOf(
    "첫번째" to 1,
    "첫째" to 1,
    "두번째" to 2,
    "둘째" to 2,
    "세번째" to 3,
    "셋째" to 3,
    "네번째" to 4,
    "넷째" to 4,
    "다섯번째" to 5,
)
private val DESTINATION_PREFIX = Regex(
    "목적지(?:를|을)?\\s*(.+?)\\s*(?:설정(?:해|해줘|해주세요)?|지정(?:해|해줘|해주세요)?|변경(?:해|해줘|해주세요)?|바꿔(?:줘|주세요)?|바꿔)",
)
private val DESTINATION_SUFFIX = Regex(
    "(.+?(?:으로|로))\\s*(?:목적지(?:를|을)?\\s*)?(?:설정(?:해|해줘|해주세요)?|지정(?:해|해줘|해주세요)?|변경(?:해|해줘|해주세요)?|바꿔(?:줘|주세요)?|안내(?:해|해줘|해주세요)?|가자)",
)
private val NEGATION_MARKERS = listOf(
    "하지마",
    "하지말",
    "지마",
    "지말",
    "안해",
    "안할",
)
private const val DESTINATION_VOICE_PAGE_SIZE = 3

private fun String?.toVoiceLabel(maxLength: Int, fallback: String): String {
    val normalized = this?.replace(WHITESPACE, " ")?.trim().orEmpty()
    if (normalized.isEmpty()) return fallback
    return if (normalized.length <= maxLength) normalized else normalized.take(maxLength - 1) + "…"
}
private val DESTINATION_CANCEL_COMMANDS = setOf(
    "목적지취소",
    "목적지취소해",
    "목적지를취소",
    "목적지를취소해",
    "목적지삭제",
    "목적지삭제해",
    "목적지를삭제해",
    "목적지지워",
    "목적지없애",
)
private val NEXT_NAVIGATION_COMMANDS = setOf(
    "다음경로뭐야",
    "다음길뭐야",
    "다음안내뭐야",
    "다음회전뭐야",
    "다음경로알려줘",
    "다음경로말해줘",
    "다음길알려줘",
    "다음안내알려줘",
)
private val REROUTE_COMMANDS = setOf(
    "새경로찾아줘",
    "경로다시찾아줘",
    "재탐색해줘",
)
private val LOCATION_RECHECK_COMMANDS = setOf(
    "위치다시확인",
    "위치확인해줘",
    "위치다시확인해줘",
    "현재위치다시확인",
    "현재위치다시확인해줘",
)
private val ARRIVAL_CONFIRM_COMMANDS = setOf(
    "도착확인",
    "도착했어",
    "도착했습니다",
)
private val ARRIVAL_REJECT_COMMANDS = setOf(
    "도착아니야",
    "아직도착아니야",
    "도착하지않았어",
)
private val STOP_NAVIGATION_COMMANDS = setOf(
    "길안내종료",
    "길안내중지",
    "길안내취소",
    "길안내멈춰",
    "길안내멈춰줘",
    "길안내그만",
    "안내중지",
    "안내취소",
    "내비중지",
    "네비중지",
)
private const val MAX_DESTINATION_LENGTH = 80
private const val MAX_DESTINATION_CANDIDATES = 20
private const val MIN_VOICE_CONFIDENCE = 0.55f
