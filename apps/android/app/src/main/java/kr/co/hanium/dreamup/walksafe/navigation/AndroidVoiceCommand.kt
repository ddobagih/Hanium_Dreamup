package kr.co.hanium.dreamup.walksafe.navigation

import java.util.Locale

sealed interface AndroidVoiceCommand {
    data object CreateReport : AndroidVoiceCommand
    data object ListLocalQuarantinedReports : AndroidVoiceCommand
    data class SelectLocalQuarantinedReport(val number: Int) : AndroidVoiceCommand
    data object ConfirmLocalQuarantinedReportDelete : AndroidVoiceCommand
    data object OpenSettings : AndroidVoiceCommand
    data class SetDestination(val placeName: String) : AndroidVoiceCommand
    data class SelectDestinationCandidate(val oneBasedIndex: Int) : AndroidVoiceCommand
    data object RepeatDestinationCandidates : AndroidVoiceCommand
    data object HearMoreDestinationCandidates : AndroidVoiceCommand
    /** S06-38/S05-14: "이전 후보" re-reads the previous page of the same fetched list. */
    data object PreviousDestinationCandidates : AndroidVoiceCommand
    data object CancelDestination : AndroidVoiceCommand
    /**
     * S05-12/S06-21: a bare "취소" closes only the dialog open now (candidate dialog, running search,
     * walking selection); it never removes the destination or ends guidance.
     */
    data object CancelDialog : AndroidVoiceCommand
    data object StartNavigation : AndroidVoiceCommand
    data object RepeatGuidance : AndroidVoiceCommand
    /** Read the current state without requesting another navigation instruction. */
    data object CurrentState : AndroidVoiceCommand
    data object Help : AndroidVoiceCommand
    data object OpenHelp : AndroidVoiceCommand
    data class DestinationCandidateDetail(val oneBasedIndex: Int) : AndroidVoiceCommand
    data object NextNavigationInstruction : AndroidVoiceCommand
    data object RequestReroute : AndroidVoiceCommand
    data object RecheckLocation : AndroidVoiceCommand
    data object ConfirmArrival : AndroidVoiceCommand
    data object RejectArrival : AndroidVoiceCommand
    data object StopNavigation : AndroidVoiceCommand
}

sealed interface AndroidVoiceAction {
    data object CreateReport : AndroidVoiceAction
    data object ListLocalQuarantinedReports : AndroidVoiceAction
    data class SelectLocalQuarantinedReport(val number: Int) : AndroidVoiceAction
    data object ConfirmLocalQuarantinedReportDelete : AndroidVoiceAction
    data object OpenSettings : AndroidVoiceAction
    data class SearchDestination(val query: String) : AndroidVoiceAction
    data class SelectDestinationCandidate(val oneBasedIndex: Int) : AndroidVoiceAction
    data object RepeatDestinationCandidates : AndroidVoiceAction
    data object HearMoreDestinationCandidates : AndroidVoiceAction
    data object PreviousDestinationCandidates : AndroidVoiceAction
    data object CancelDestination : AndroidVoiceAction
    data object CancelDialog : AndroidVoiceAction
    data object StartNavigation : AndroidVoiceAction
    data object SpeakCurrentGuidance : AndroidVoiceAction
    data object SpeakCurrentState : AndroidVoiceAction
    data object SpeakVoiceHelp : AndroidVoiceAction
    data object OpenHelp : AndroidVoiceAction
    data class SpeakDestinationCandidateDetail(val oneBasedIndex: Int) : AndroidVoiceAction
    data object SpeakNextNavigationInstruction : AndroidVoiceAction
    data object RequestReroute : AndroidVoiceAction
    data object RecheckLocation : AndroidVoiceAction
    data object ConfirmArrival : AndroidVoiceAction
    data object RejectArrival : AndroidVoiceAction
    data object StopNavigation : AndroidVoiceAction
}

sealed interface DestinationSearchVoiceCommand {
    data object RepeatPage : DestinationSearchVoiceCommand
    data object HearMore : DestinationSearchVoiceCommand
    data object PreviousPage : DestinationSearchVoiceCommand
    data class SelectCandidate(val oneBasedIndex: Int) : DestinationSearchVoiceCommand
}

data class DestinationSearchVoiceTransition(
    val state: DestinationSearchVoiceState,
    val accepted: Boolean,
    val selectedOneBasedIndex: Int? = null,
    val selectedResult: DestinationSearchResult? = null,
)

class DestinationSearchVoiceState(
    val query: String,
    results: List<DestinationSearchResult>,
    val pageIndex: Int = 0,
    val moreResultsAvailable: Boolean = false,
    /** Where this list was measured from; null keeps the default rounding and no direction (S06-31). */
    val origin: DestinationSearchOrigin? = null,
) {
    val results: List<DestinationSearchResult> = java.util.Collections.unmodifiableList(results.toList())

    init {
        val lastPageIndex = if (results.isEmpty()) 0 else (results.size - 1) / DESTINATION_VOICE_PAGE_SIZE
        require(pageIndex in 0..lastPageIndex) { "pageIndex is outside the destination results" }
    }

    internal class VoicePromptAttempt

    private var pendingVoicePrompt: VoicePromptAttempt? = null
    private var voicePromptCompleted = false

    /** Page prompts already started for this exact state; >0 means the page is being re-heard. */
    private var voicePromptsStarted = 0

    internal fun beginVoicePrompt(): VoicePromptAttempt = VoicePromptAttempt().also {
        pendingVoicePrompt = it
        voicePromptCompleted = false
        voicePromptsStarted += 1
    }

    internal fun completeVoicePrompt(attempt: VoicePromptAttempt): Boolean {
        if (pendingVoicePrompt !== attempt) return false
        pendingVoicePrompt = null
        voicePromptCompleted = true
        return true
    }

    internal fun failVoicePrompt(attempt: VoicePromptAttempt) {
        if (pendingVoicePrompt === attempt) invalidateVoicePrompt()
    }

    internal fun invalidateVoicePrompt() {
        pendingVoicePrompt = null
        voicePromptCompleted = false
    }

    private val pageOffset: Int
        get() = pageIndex * DESTINATION_VOICE_PAGE_SIZE

    val currentPageResults: List<DestinationSearchResult>
        get() = results.drop(pageOffset).take(DESTINATION_VOICE_PAGE_SIZE)

    val hasMoreResults: Boolean
        get() = pageOffset + currentPageResults.size < results.size

    val canHearMore: Boolean
        get() = hasMoreResults || moreResultsAvailable

    /**
     * S06-34: the numbers the voice dialog is reading now, for the screen list header ("음성 안내 중:
     * 4번~6번"); the same global numbers as the buttons. Null when the page is empty.
     */
    fun voicePageRangeLabelKo(): String? {
        val page = currentPageResults
        if (page.isEmpty()) return null
        val first = pageOffset + 1
        val last = pageOffset + page.size
        return if (first == last) "음성 안내 중: ${first}번" else "음성 안내 중: ${first}번~${last}번"
    }

    fun copy(
        query: String = this.query,
        results: List<DestinationSearchResult> = this.results,
        pageIndex: Int = this.pageIndex,
        moreResultsAvailable: Boolean = this.moreResultsAvailable,
        origin: DestinationSearchOrigin? = this.origin,
    ): DestinationSearchVoiceState = DestinationSearchVoiceState(query, results, pageIndex, moreResultsAvailable, origin)

    /**
     * [originAgeNotice] (S06-31, voice-F6) is said next to the distance basis, after the page range, so a
     * direct answer spoken before the page stays first; the caller passes it once per list.
     */
    fun voicePrompt(originAgeNotice: String? = null): String {
        val safeQuery = query.toVoiceLabel(maxLength = 40, fallback = "요청한")
        if (results.isEmpty()) return "${safeQuery} 목적지 검색 결과가 없습니다. 다른 장소명으로 다시 말씀해 주세요."

        // FP-022-DETAIL-01 (S06-37): number, name, kind, rough direction and rough distance. The address
        // is read only when another candidate in this list has the same name (S06-36 labels keep the
        // road name / dong for that). Distances are no finer than the search origin (S06-31).
        val candidates = candidateVoiceParts().joinToString(". ")
        val firstNumber = pageOffset + 1
        val lastNumber = pageOffset + currentPageResults.size
        val pageRange = if (firstNumber == lastNumber) "${firstNumber}번" else "${firstNumber}번부터 ${lastNumber}번까지"
        val moreInstruction = if (canHearMore) {
            " 추가 후보가 더 있습니다. 더 들으려면 더 듣기라고 말씀해 주세요."
        } else {
            " 현재 결과에서 더 안내할 후보는 없습니다."
        }
        // S06-32: a re-heard page keeps its numbers and distances; say they are from the search.
        val distanceBasis = if (voicePromptsStarted > 0) "거리는 처음 검색한 위치 기준입니다. " else ""
        val ageNotice = originAgeNotice?.takeIf(String::isNotBlank)?.let { "$it " }.orEmpty()
        val originNotice = destinationOriginUncertaintyNoticeKo(origin)?.let { "$it " }.orEmpty()
        return "${safeQuery} 목적지 후보는 현재 ${pageRange}입니다. " + distanceBasis + ageNotice + originNotice +
            "${candidates}.${moreInstruction} 원하는 번호나 다시 듣기라고 말씀해 주세요."
    }

    private fun candidateVoiceParts(spokenAddresses: Boolean = false): List<String> {
        return currentPageResults.mapIndexed { index, result ->
            val name = result.name.toVoiceNameLabel(maxLength = 40, fallback = "이름 미상")
            val address = if (result.sharesNameWith(results)) {
                val raw = result.displayAddressOrNull()
                if (spokenAddresses) {
                    val expansion = raw?.let { spokenDestinationAddressKo(it).length - it.length } ?: 0
                    spokenDestinationAddressKo(raw.toVoiceAddressLabel(maxLength = (60 - expansion).coerceAtLeast(1), fallback = "주소 미상"))
                } else raw.toVoiceAddressLabel(maxLength = 60, fallback = "주소 미상")
            } else {
                null
            }
            val direction = approximateDestinationDirectionKo(origin, result.point)
            listOfNotNull(
                "${pageOffset + index + 1}번 ${name}",
                destinationCategoryLabelKo(result.category),
                address,
                // S06-30: the kind of distance is said with it; never a walking distance.
                destinationDirectionDistanceKo(direction, result.distanceM, result.distanceSource, origin?.accuracyM),
            ).joinToString(", ")
        }
    }

    /** One native utterance for the page header, each numbered candidate, and the answer hint. */
    fun voicePromptParts(originAgeNotice: String? = null): List<String> {
        val prompt = voicePrompt(originAgeNotice)
        val candidates = candidateVoiceParts()
        if (candidates.isEmpty()) return listOf(prompt)
        val joined = candidates.joinToString(". ")
        val start = prompt.indexOf(joined)
        if (start < 0) return listOf(prompt)
        return (listOf(prompt.substring(0, start).trim()) + candidateVoiceParts(spokenAddresses = true) +
            prompt.substring(start + joined.length).trim().trimStart('.').trim())
            .filter(String::isNotBlank)
    }

    /**
     * Why a correctly heard number was not accepted, with the numbers that can be chosen now (S06-8).
     * Numbers stay global: on the 4-6 page, "첫 번째" is 1번 and is explained, not remapped (S06-23).
     */
    fun selectionRejectionMessage(oneBasedIndex: Int): String {
        val page = currentPageResults
        if (page.isEmpty()) return "선택할 목적지 후보가 없습니다. 다른 장소명으로 다시 말씀해 주세요."
        val firstNumber = pageOffset + 1
        val lastNumber = pageOffset + page.size
        if (oneBasedIndex in firstNumber..lastNumber) {
            return "${oneBasedIndex}번 후보 안내가 아직 끝나지 않았습니다. 안내를 끝까지 들은 뒤 번호를 말씀해 주세요."
        }
        val choices = if (firstNumber == lastNumber) {
            "지금은 ${firstNumber}번만 선택할 수 있습니다."
        } else {
            "지금은 " + (firstNumber..lastNumber).joinToString(", ") { "${it}번" } + " 중에서 선택해 주세요."
        }
        return "${oneBasedIndex}번은 현재 안내 중인 후보가 아닙니다. $choices"
    }

    fun onCommand(command: DestinationSearchVoiceCommand): DestinationSearchVoiceTransition {
        return when (command) {
            DestinationSearchVoiceCommand.RepeatPage ->
                DestinationSearchVoiceTransition(this, accepted = currentPageResults.isNotEmpty())
            DestinationSearchVoiceCommand.HearMore -> {
                if (hasMoreResults) {
                    DestinationSearchVoiceTransition(copy(pageIndex = pageIndex + 1), accepted = true)
                } else {
                    DestinationSearchVoiceTransition(this, accepted = false)
                }
            }
            // S06-38: a new state for the earlier page, so its numbers are selectable only after it
            // is read in full again; the list, numbers and distances are the ones already fetched.
            DestinationSearchVoiceCommand.PreviousPage -> {
                if (pageIndex > 0) {
                    DestinationSearchVoiceTransition(copy(pageIndex = pageIndex - 1), accepted = true)
                } else {
                    DestinationSearchVoiceTransition(this, accepted = false)
                }
            }
            is DestinationSearchVoiceCommand.SelectCandidate -> {
                val zeroBasedIndex = command.oneBasedIndex - 1
                val pageEndExclusive = pageOffset + currentPageResults.size
                if (voicePromptCompleted && zeroBasedIndex in pageOffset until pageEndExclusive) {
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
        AndroidVoiceCommand.ListLocalQuarantinedReports -> AndroidVoiceAction.ListLocalQuarantinedReports
        is AndroidVoiceCommand.SelectLocalQuarantinedReport -> AndroidVoiceAction.SelectLocalQuarantinedReport(number)
        AndroidVoiceCommand.ConfirmLocalQuarantinedReportDelete -> AndroidVoiceAction.ConfirmLocalQuarantinedReportDelete
        AndroidVoiceCommand.OpenSettings -> AndroidVoiceAction.OpenSettings
        is AndroidVoiceCommand.SetDestination -> AndroidVoiceAction.SearchDestination(placeName)
        is AndroidVoiceCommand.SelectDestinationCandidate -> AndroidVoiceAction.SelectDestinationCandidate(oneBasedIndex)
        AndroidVoiceCommand.RepeatDestinationCandidates -> AndroidVoiceAction.RepeatDestinationCandidates
        AndroidVoiceCommand.HearMoreDestinationCandidates -> AndroidVoiceAction.HearMoreDestinationCandidates
        AndroidVoiceCommand.PreviousDestinationCandidates -> AndroidVoiceAction.PreviousDestinationCandidates
        AndroidVoiceCommand.CancelDestination -> AndroidVoiceAction.CancelDestination
        AndroidVoiceCommand.CancelDialog -> AndroidVoiceAction.CancelDialog
        AndroidVoiceCommand.StartNavigation -> AndroidVoiceAction.StartNavigation
        AndroidVoiceCommand.RepeatGuidance -> AndroidVoiceAction.SpeakCurrentGuidance
        AndroidVoiceCommand.CurrentState -> AndroidVoiceAction.SpeakCurrentState
        AndroidVoiceCommand.Help -> AndroidVoiceAction.SpeakVoiceHelp
        AndroidVoiceCommand.OpenHelp -> AndroidVoiceAction.OpenHelp
        is AndroidVoiceCommand.DestinationCandidateDetail -> AndroidVoiceAction.SpeakDestinationCandidateDetail(oneBasedIndex)
        AndroidVoiceCommand.NextNavigationInstruction -> AndroidVoiceAction.SpeakNextNavigationInstruction
        AndroidVoiceCommand.RequestReroute -> AndroidVoiceAction.RequestReroute
        AndroidVoiceCommand.RecheckLocation -> AndroidVoiceAction.RecheckLocation
        AndroidVoiceCommand.ConfirmArrival -> AndroidVoiceAction.ConfirmArrival
        AndroidVoiceCommand.RejectArrival -> AndroidVoiceAction.RejectArrival
        AndroidVoiceCommand.StopNavigation -> AndroidVoiceAction.StopNavigation
    }
}

/** One help text for the existing dialog and its spoken response. */
fun voiceCommandHelpKo(paused: Boolean): String = if (paused) {
    "일시정지 중에는 보행 재개 또는 보행 종료라고 말해 주세요. 종료할 때는 확인을 한 번 더 받습니다. " +
        "기기 보관 내역은 기기 신고 목록이라고 말씀해 주세요."
} else {
    "목적지는 서울역으로 안내해줘처럼 말해 주세요. 후보 안내 뒤에는 1번 또는 첫 번째라고 선택할 수 있습니다. " +
        "1번 상세처럼 말하면 현재 들은 후보의 주소와 거리를 읽어드립니다. " +
        "더 듣기, 이전 후보, 목적지 취소, 안내 시작, 설정을 사용할 수 있습니다. " +
        "보행 중에는 다음 안내 알려줘, 다시 말해줘, 경로 다시 찾아줘, 위치 다시 확인, " +
        "현재 상태, 남은 거리, 길안내 종료, 신고해, 보행 일시정지, 보행 종료를 말할 수 있습니다. " +
        "기기 신고 목록을 들은 뒤 기기 신고 1번 선택이라고 말씀해 주세요. 삭제하려면 선택한 안내를 듣고 기기 신고 삭제 확인이라고 말해 주세요. " +
        "신고와 보행 종료는 확인 안내를 따라 주세요."
}

/** Executes only the recognizer's top hypothesis; lower alternatives must never override it. */
fun selectAndroidVoiceAction(
    phrases: List<String>,
    confidenceScores: FloatArray? = null,
    minimumConfidence: Float = MIN_VOICE_CONFIDENCE,
    allowBareDestinationIndex: Boolean = false,
): AndroidVoiceAction? {
    val topPhrase = phrases.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
    if (confidenceScores == null || confidenceScores.size != phrases.size) return null
    val topConfidence = confidenceScores.getOrNull(0) ?: return null
    if (!minimumConfidence.isFinite() || minimumConfidence !in 0f..1f) return null
    if (!topConfidence.isFinite() || topConfidence !in 0f..1f || topConfidence < minimumConfidence) return null
    return parseAndroidVoiceCommand(topPhrase, allowBareDestinationIndex)?.toAction()
}

/** Reads enough context for a voice-only user to choose a numbered TMAP result safely. */
fun formatDestinationSearchVoicePrompt(
    query: String,
    results: List<DestinationSearchResult>,
): String = DestinationSearchVoiceState(query = query, results = results).voicePrompt()

fun parseDestinationSearchVoiceCommand(
    text: String,
    allowBareDestinationIndex: Boolean = false,
): DestinationSearchVoiceCommand? {
    val normalized = normalizedVoiceInputOrNull(text) ?: return null
    val compact = compactVoicePhrase(normalized)
    if (compact.isBlank() || NEGATED_COMMAND.containsMatchIn(normalized)) return null
    if (compact == "다시듣기") return DestinationSearchVoiceCommand.RepeatPage
    if (compact == "더듣기") return DestinationSearchVoiceCommand.HearMore
    if (compact in PREVIOUS_CANDIDATES_COMMANDS) return DestinationSearchVoiceCommand.PreviousPage
    if (hasAmbiguousCandidateNumber(normalized)) return null
    val oneBasedIndex = destinationCandidateIndex(compact)
        ?: (if (allowBareDestinationIndex) bareDestinationCandidateIndex(compact) else null)
        ?: return null
    return DestinationSearchVoiceCommand.SelectCandidate(oneBasedIndex)
}

/** Shared command normalization for recognizer spacing, punctuation and case. */
internal fun compactVoicePhrase(text: String): String = text
    .trim()
    .replace(PUNCTUATION, "")
    .replace(WHITESPACE, "")
    .lowercase(Locale.KOREAN)

fun parseAndroidVoiceCommand(
    text: String,
    allowBareDestinationIndex: Boolean = false,
): AndroidVoiceCommand? {
    val normalized = normalizedVoiceInputOrNull(text) ?: return null
    val compact = compactVoicePhrase(normalized)
    if (compact.isBlank() || NEGATED_COMMAND.containsMatchIn(normalized)) return null

    if (compact == "기기신고목록") return AndroidVoiceCommand.ListLocalQuarantinedReports
    if (compact == "기기신고삭제확인") return AndroidVoiceCommand.ConfirmLocalQuarantinedReportDelete
    if (!hasAmbiguousCandidateNumber(normalized)) {
        LOCAL_REPORT_SELECTION.matchEntire(compact)?.groupValues?.get(1)?.let { number ->
            bareDestinationCandidateIndex(number)?.let { return AndroidVoiceCommand.SelectLocalQuarantinedReport(it) }
        }
    }
    if (compact in OPEN_SETTINGS_COMMANDS) return AndroidVoiceCommand.OpenSettings
    if (compact in DESTINATION_CANCEL_COMMANDS) return AndroidVoiceCommand.CancelDestination
    if (compact in DIALOG_CANCEL_COMMANDS) return AndroidVoiceCommand.CancelDialog
    if (compact == "다시듣기") return AndroidVoiceCommand.RepeatDestinationCandidates
    if (compact == "더듣기") return AndroidVoiceCommand.HearMoreDestinationCandidates
    if (compact in PREVIOUS_CANDIDATES_COMMANDS) return AndroidVoiceCommand.PreviousDestinationCandidates
    if (compact in START_NAVIGATION_COMMANDS) return AndroidVoiceCommand.StartNavigation
    if (compact in setOf("다시말해줘", "다시안내해줘", "현재경로안내해줘")) return AndroidVoiceCommand.RepeatGuidance
    if (compact in setOf("현재상태", "남은거리", "남은거리알려줘")) return AndroidVoiceCommand.CurrentState
    if (compact in OPEN_HELP_COMMANDS) return AndroidVoiceCommand.OpenHelp
    if (compact in HELP_COMMANDS) return AndroidVoiceCommand.Help
    if (!hasAmbiguousCandidateNumber(normalized)) {
        DESTINATION_DETAIL.matchEntire(compact)?.groupValues?.get(1)?.let { number ->
            bareDestinationCandidateIndex(number)?.let { return AndroidVoiceCommand.DestinationCandidateDetail(it) }
        }
    }
    if (compact in NEXT_NAVIGATION_COMMANDS) return AndroidVoiceCommand.NextNavigationInstruction
    if (compact in REROUTE_COMMANDS) return AndroidVoiceCommand.RequestReroute
    if (compact in LOCATION_RECHECK_COMMANDS) return AndroidVoiceCommand.RecheckLocation
    if (compact in ARRIVAL_CONFIRM_COMMANDS) return AndroidVoiceCommand.ConfirmArrival
    if (compact in ARRIVAL_REJECT_COMMANDS) return AndroidVoiceCommand.RejectArrival
    if (compact in STOP_NAVIGATION_COMMANDS) return AndroidVoiceCommand.StopNavigation
    if (isReportCommand(compact)) return AndroidVoiceCommand.CreateReport
    if (!hasAmbiguousCandidateNumber(normalized)) {
        destinationCandidateIndex(compact)?.let { return AndroidVoiceCommand.SelectDestinationCandidate(it) }
    }
    if (allowBareDestinationIndex && !hasAmbiguousCandidateNumber(normalized)) {
        bareDestinationCandidateIndex(compact)?.let {
            return AndroidVoiceCommand.SelectDestinationCandidate(it)
        }
    }

    destinationFromPrefixCommand(normalized)?.let { return AndroidVoiceCommand.SetDestination(it) }
    destinationFromMarkedCommand(normalized)?.let { return AndroidVoiceCommand.SetDestination(it) }
    destinationFromSuffixCommand(normalized)?.let { return AndroidVoiceCommand.SetDestination(it) }
    destinationFromNaturalCommand(normalized)?.let { return AndroidVoiceCommand.SetDestination(it) }
    return null
}

private fun destinationFromPrefixCommand(text: String): String? {
    val match = DESTINATION_PREFIX.matchEntire(text) ?: return null
    return normalizeDestination(match.groupValues[1], removeFinalParticle = true)
}

private fun destinationFromSuffixCommand(text: String): String? {
    val match = DESTINATION_SUFFIX.matchEntire(text) ?: return null
    return normalizeDestination(match.groupValues[1], removeFinalParticle = true)
}

private fun destinationFromMarkedCommand(text: String): String? {
    val match = DESTINATION_MARKED_SUFFIX.matchEntire(text) ?: return null
    return normalizeDestination(match.groupValues[1])
}

private fun destinationFromNaturalCommand(text: String): String? {
    val match = DESTINATION_NATURAL_SUFFIX.matchEntire(text) ?: return null
    // A lookup preserves the name verbatim; a movement request may carry an explicit particle.
    return normalizeDestination(match.groupValues[1], removeFinalParticle = !match.groupValues[2].startsWith("찾아"))
}

private fun normalizeDestination(value: String, removeFinalParticle: Boolean = false): String? {
    var destination = value.trim()
    // Check the separate negative adverb before removing an explicit directional particle.
    if (destination == "안" || destination.endsWith(" 안")) return null
    // Inspect the extracted place only: command prefixes must not turn "말고개" into an exclusion.
    if (DESTINATION_ATTACHED_EXCLUSION.containsMatchIn(destination)) return null
    // Two actions in one utterance are never executed: ask for one sentence at a time.
    if (hasCommandClause(destination) || hasJoinedCommandClause(destination)) return null
    destination = when {
        removeFinalParticle && destination.endsWith("으로") -> destination.dropLast(2).trim()
        removeFinalParticle && destination.endsWith("로로") -> destination.dropLast(1).trim()
        removeFinalParticle && destination.endsWith(" 로") -> destination.dropLast(1).trim()
        else -> destination
    }
    val compact = destination.lowercase(Locale.KOREAN).replace(" ", "")
    if (compact in DESTINATION_CONTROL_WORDS ||
        isReportCommand(compact) ||
        DESTINATION_COMMAND_FRAGMENT.containsMatchIn(destination)
    ) return null
    return canonicalDestinationSearchQueryOrNull(destination)
}

private fun isReportCommand(compact: String): Boolean {
    return REPORT_COMMAND.matches(compact)
}

/**
 * voice-F7 (S04-19, 04.txt:256 "어떤 단어가 틀리면 동작이 바뀌는지 기준"): true when [text] is a fixed
 * command phrase - every parsed command except a destination search, and the walk session phrases.
 * Each of its words picks the action ("다른 경로 찾아줘", "보행 종료" vs "안내 종료", "목적지 취소" vs
 * "검색 취소"), so the recognizer requires each word at the threshold; only a destination's free
 * words stay under the command span average.
 */
internal fun isFixedVoiceCommandPhrase(text: String): Boolean {
    if (isWalkSessionVoicePhrase(text)) return true
    val command = parseAndroidVoiceCommand(text, allowBareDestinationIndex = true) ?: return false
    return command !is AndroidVoiceCommand.SetDestination
}

/** A conjunction alone is not a second action; a joined clause that is itself a command is. */
private fun hasCommandClause(destination: String): Boolean =
    CLAUSE_CONJUNCTION.containsMatchIn(destination) &&
        destination.split(CLAUSE_CONJUNCTION).any { clause ->
            // Bare numbers count too: in the candidate dialog "2번" alone is a selection.
            val command = parseAndroidVoiceCommand(clause, allowBareDestinationIndex = true)
            command != null && command !is AndroidVoiceCommand.SetDestination
        }

/**
 * S05-6: two commands joined without a conjunction - by a connective ending ("다시 말해주고 편의점",
 * "설정 열고 서울역") or as a second imperative ("다시 말해줘 편의점"). The joined part counts only when,
 * restored to its command form, it parses as a command of its own (a second search included); a
 * place word that merely ends in "고" or "하고" ("마포고", "커피하고 빵") never does.
 */
private fun hasJoinedCommandClause(destination: String): Boolean {
    // An over-long place is rejected anyway; this also bounds the nested parses below.
    if (canonicalDestinationSearchQueryOrNull(destination) == null) return false
    val words = destination.split(' ')
    for (end in 0 until words.size - 1) {
        val forms = joinedClauseCommandForms(words[end])
        for (form in forms) {
            for (start in 0..end) {
                val clause = (words.subList(start, end) + form).filter(String::isNotEmpty).joinToString(" ")
                if (clause.isNotEmpty() && parseAndroidVoiceCommand(clause, allowBareDestinationIndex = true) != null) {
                    return true
                }
            }
        }
    }
    return false
}

/** Command forms of a word that ends a joined clause; empty when the word does not end a clause. */
private fun joinedClauseCommandForms(word: String): List<String> = when {
    word.endsWith("줘") || word.endsWith("주세요") -> listOf(word)
    word.endsWith("주고") -> listOf(word.dropLast(2) + "줘")
    word.endsWith("하고") -> listOf(word.dropLast(2), word.dropLast(2) + "해줘")
    word.endsWith("듣고") -> listOf(word.dropLast(2) + "듣기")
    word.endsWith("열고") -> listOf(word.dropLast(2) + "열어줘")
    word.endsWith("찾고") -> listOf(word.dropLast(2) + "찾아줘")
    word.endsWith("멈추고") -> listOf(word.dropLast(3) + "멈춰줘")
    word.endsWith("했고") -> listOf(word.dropLast(2) + "했어")
    word.endsWith("가고") -> listOf(word.dropLast(2) + "가자")
    else -> emptyList()
}

/**
 * S05-10: "현재 위치 알려줘" asks for a description of where the user is, not for the re-measure
 * command ("위치 다시 확인"). The app has no spoken location description, so the caller says so.
 */
fun isLocationDescriptionRequest(text: String): Boolean {
    val normalized = normalizedVoiceInputOrNull(text) ?: return false
    if (NEGATED_COMMAND.containsMatchIn(normalized)) return false
    return compactVoicePhrase(normalized) in LOCATION_DESCRIPTION_REQUESTS
}

/** S05-10: what the app says for [isLocationDescriptionRequest]. */
const val LOCATION_DESCRIPTION_UNSUPPORTED_KO = "현재 위치를 말로 알려 드리는 기능은 없습니다."

private val LOCATION_DESCRIPTION_REQUESTS = setOf(
    "현재위치알려줘", "현재위치알려주세요", "내위치알려줘", "내위치알려주세요", "지금위치알려줘",
    "현재위치가어디야", "현재위치어디야", "내위치어디야", "여기어디야", "여기가어디야", "지금어디야",
)

/** S05-6: what the app says when [isCompoundVoiceCommand] refused a sentence. */
const val COMPOUND_VOICE_COMMAND_REFUSAL_KO = "두 가지 명령은 함께 실행하지 않습니다. 한 번에 한 가지씩 말씀해 주세요."

/** S05-6: true when [text] joins two commands; the parser refuses it and the caller asks for one at a time. */
fun isCompoundVoiceCommand(text: String): Boolean {
    if (parseAndroidVoiceCommand(text) != null) return false
    val normalized = normalizedVoiceInputOrNull(text) ?: return false
    if (NEGATED_COMMAND.containsMatchIn(normalized)) return false
    return listOf(DESTINATION_PREFIX, DESTINATION_MARKED_SUFFIX, DESTINATION_SUFFIX, DESTINATION_NATURAL_SUFFIX)
        .mapNotNull { pattern -> pattern.matchEntire(normalized)?.groupValues?.get(1)?.trim() }
        .any { place -> hasCommandClause(place) || hasJoinedCommandClause(place) }
}

private fun hasAmbiguousCandidateNumber(normalized: String): Boolean =
    // Sentence-ending punctuation is already removed; remaining punctuation can change a number.
    PUNCTUATION.containsMatchIn(normalized) || SPLIT_CANDIDATE_NUMBER.containsMatchIn(normalized)

private fun destinationCandidateIndex(compact: String): Int? {
    val number = DESTINATION_SELECTION.matchEntire(compact)?.groupValues?.get(1) ?: return null
    return bareDestinationCandidateIndex(number)
}

private fun bareDestinationCandidateIndex(compact: String): Int? {
    val numeric = DESTINATION_BARE_CANDIDATE_NUMBER.matchEntire(compact)
        ?.groupValues?.get(1)?.toIntOrNull()
    if (numeric != null) return numeric.takeIf { it in 1..MAX_DESTINATION_CANDIDATES }
    DESTINATION_ORDINALS[compact]?.let { return it }
    val spokenNumber = compact.removeSuffix("번").removeSuffix("번째")
    return DESTINATION_SPOKEN_NUMBERS[spokenNumber] ?: when (compact) {
        "한번" -> 1
        "두번" -> 2
        "세번" -> 3
        "네번" -> 4
        else -> null
    }
}

private val WHITESPACE = Regex("\\s+")
private fun normalizedVoiceInputOrNull(text: String): String? {
    if (text.any { (it.isISOControl() && !it.isWhitespace()) || Character.getType(it) == Character.FORMAT.toInt() }) return null
    return text.map { character ->
        when {
            character.isWhitespace() || Character.isSpaceChar(character) -> ' '
            character in '\uFF10'..'\uFF19' -> '0' + (character - '\uFF10')
            else -> character
        }
    }.joinToString("").trim().trimEnd('.', ',', '!', '?', '~', '。', '？', '！')
        .replace(WHITESPACE, " ").trim()
}
private val PUNCTUATION = Regex("[.,!?~。？！]+")
private val SPLIT_CANDIDATE_NUMBER = Regex("\\d[\\s.,!?~。？！]+\\d")
/**
 * S05-10: the report is an explicit damaged tactile paving report, so only that object is named
 * ("점자블록 신고해줘", "손상 신고해줘"); "신고 접수해줘" is the same request. Other objects
 * ("장애물 신고해줘") are not a report the app files.
 */
private val REPORT_COMMAND = Regex(
    "(?:(?:이거|여기|위험|점자블록|점자블록손상|손상된점자블록|손상)(?:을|를)?)?(?:신고|싱고)" +
        "(?:해|해줘|해주세요|접수(?:해|해줘|해주세요)?)?",
)
private val LOCAL_REPORT_SELECTION = Regex("기기신고(.+?)(?:을|를)?선택(?:해줘|해주세요)?")
private val OPEN_SETTINGS_COMMANDS = setOf(
    "설정", "설정열어줘", "설정으로이동",
    "설정열어주세요", "설정화면열어줘", "설정화면열어주세요",
)
private val OPEN_HELP_COMMANDS = setOf("도움말열기", "도움말열어줘", "도움말열어주세요", "도움말화면열어줘", "도움말화면열어주세요")
private val DESTINATION_DETAIL = Regex("(?:목적지|후보)?(.+?)(?:후보)?(?:상세|자세히)(?:알려줘|알려주세요|들려줘|들려주세요)?")
private val HELP_COMMANDS = setOf("도움말", "도움말알려줘", "도움말알려주세요")
private val DESTINATION_SELECTION = Regex(
    "(?:목적지)?(.+?)(?:목적지)?(?:을|를)?(?:선택(?:해|해줘|해주세요)?|골라(?:줘|주세요)?)",
)
private val DESTINATION_BARE_CANDIDATE_NUMBER = Regex("([1-9]\\d*)(?:번)?")
private val DESTINATION_SPOKEN_NUMBERS = mapOf(
    "일" to 1, "이" to 2, "삼" to 3, "사" to 4, "오" to 5,
    "육" to 6, "칠" to 7, "팔" to 8, "구" to 9, "십" to 10,
    "십일" to 11, "십이" to 12, "십삼" to 13, "십사" to 14, "십오" to 15,
    "십육" to 16, "십칠" to 17, "십팔" to 18, "십구" to 19, "이십" to 20,
    "하나" to 1, "둘" to 2, "셋" to 3, "넷" to 4, "다섯" to 5,
    "여섯" to 6, "일곱" to 7, "여덟" to 8, "아홉" to 9, "열" to 10,
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
private val DESTINATION_MARKED_SUFFIX = Regex(
    "(.+?)\\s*목적지로\\s*해\\s*(?:줘|주세요)",
)
private val DESTINATION_NATURAL_SUFFIX = Regex(
    "(.+?)\\s*(찾아\\s*(?:줘|주세요)|가고\\s*싶(?:어|어요|습니다)|안내\\s*해\\s*(?:줘|주세요))",
)
private val DESTINATION_CONTROL_WORDS = setOf(
    "목적지", "장소", "경로", "길안내", "안내", "취소", "정지", "중지", "신고", "싱고",
    "다음", "다시", "현재경로", "길", "다른경로",
)
private val DESTINATION_ATTACHED_EXCLUSION = Regex("\\S\\s+말고(?=\\S)")
private val CLAUSE_CONJUNCTION = Regex("\\s+(?:그리고(?:\\s*나서)?|그러고\\s*나서|그\\s*다음(?:에)?)\\s+")
private val DESTINATION_COMMAND_FRAGMENT = Regex(
    "(?:신고|싱고|취소|중지|정지|종료|설정|지정|변경|안내)\\s*(?:해\\s*(?:줘|주세요)?|하(?:고|지)|해주고)" +
        "|찾아\\s*(?:줘|주세요)|가고\\s*싶|설정\\s*(?:화면\\s*)?열어|(?:안내|길안내)\\s*시작하고",
)
private val NEGATED_COMMAND = Regex(
    "(?:하지|(?:안내|선택|찾|가|시작|중지|취소|신고)\\s*지)\\s*" +
        "(?:마(?:세요|요)?|말(?:아(?:줘|주세요|요)?|고|자|라)?)(?=\\s|$|[.,!?~。？！])" +
        "|(?:^|\\s)안\\s+(?:가|해|할|하)" +
        "|(?:^|\\s)안(?:가(?:고\\s*싶(?:어|어요|습니다)|요|자|고)?|해(?:요|줘|주세요|주고|주지)?|할|하(?:지|고|자|세요)?)" +
        "(?=\\s|$|[.,!?~。？！])|말고(?=\\s|$|[.,!?~。？！])",
)
private const val DESTINATION_VOICE_PAGE_SIZE = 3

private fun String?.toVoiceLabel(maxLength: Int, fallback: String): String {
    val normalized = this?.replace(WHITESPACE, " ")?.trim().orEmpty()
    if (normalized.isEmpty()) return fallback
    if (normalized.length <= maxLength) return normalized
    val end = graphemeCutBefore(normalized, maxLength) ?: return fallback
    return normalized.substring(0, end) + "…"
}

/** Cut only at a character boundary: a split surrogate pair or combining mark is not speakable. */
private fun graphemeCutBefore(text: String, maxLength: Int): Int? {
    if (maxLength <= 0) return null
    val boundaries = java.text.BreakIterator.getCharacterInstance(Locale.KOREAN).apply { setText(text) }
    return boundaries.preceding(maxLength.coerceAtMost(text.length)).takeIf { it > 0 }
}

/**
 * S06-36: a long place name keeps its last word (the branch, e.g. "역삼점") and drops the middle,
 * because the branch is what tells same-name candidates apart (FP-022-DETAIL-01).
 */
private fun String?.toVoiceNameLabel(maxLength: Int, fallback: String): String {
    val normalized = this?.replace(WHITESPACE, " ")?.trim().orEmpty()
    if (normalized.length <= maxLength) return normalized.ifEmpty { fallback }
    val tail = normalized.substringAfterLast(' ', missingDelimiterValue = "")
    val headBudget = maxLength - tail.length - 2
    if (tail.isEmpty() || tail.length > maxLength / 2 || headBudget < 1) {
        return normalized.toVoiceLabel(maxLength, fallback)
    }
    val head = normalized.substring(0, normalized.length - tail.length).trimEnd()
    val end = graphemeCutBefore(head, headBudget) ?: return normalized.toVoiceLabel(maxLength, fallback)
    return head.substring(0, end).trimEnd() + "… " + tail
}

/**
 * S06-36: a long address keeps the road name with its building number (or the dong with its lot
 * number) instead of the leading region, which rarely separates nearby candidates.
 */
private fun String?.toVoiceAddressLabel(maxLength: Int, fallback: String): String {
    val normalized = this?.replace(WHITESPACE, " ")?.trim().orEmpty()
    if (normalized.length <= maxLength) return normalized.ifEmpty { fallback }
    val core = (ROAD_ADDRESS_CORE.find(normalized) ?: LOT_ADDRESS_CORE.find(normalized))
        ?: return normalized.toVoiceLabel(maxLength, fallback)
    var start = core.range.first
    val end = core.range.last + 1
    val suffix = if (end < normalized.length) "…" else ""
    // Add preceding words (gu, dong) while the label still fits.
    while (start > 0) {
        val previousSpace = normalized.lastIndexOf(' ', start - 2)
        val candidateStart = previousSpace + 1
        val prefix = if (candidateStart > 0) "…" else ""
        if (prefix.length + (end - candidateStart) + suffix.length > maxLength) break
        start = candidateStart
    }
    val prefix = if (start > 0) "…" else ""
    val label = prefix + normalized.substring(start, end) + suffix
    return if (label.length <= maxLength) label else normalized.toVoiceLabel(maxLength, fallback)
}

private val ROAD_ADDRESS_CORE = Regex("[^\\s()]+(?:로|길)\\s?\\d+(?:-\\d+)?")
private val LOT_ADDRESS_CORE = Regex("[^\\s()]+(?:동|읍|면|리|가)\\s?(?:산\\s?)?\\d+(?:-\\d+)?")
private val DESTINATION_CANCEL_COMMANDS = setOf(
    "목적지취소해줘", "목적지취소해주세요",
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
/**
 * voice-F4 (05.txt:125·143·146 "중지·취소 우선", "동사별로 실제 사용하는 표현을 정의"): the stop and cancel
 * phrases are one table - target × verb × the endings that verb really takes ("취소해줘", "멈춰줘"; never
 * "멈춰해줘"). Only these exact phrases are built; a negation ("…하지 마", "… 안 해") is refused before
 * any set is consulted, so it never becomes a command.
 */
private val HADA_VERB_ENDINGS = listOf("", "해", "해줘", "해주세요")
private val STOP_VERB_ENDINGS = listOf("", "줘", "주세요")

private fun voicePhraseTable(targets: List<String>, verbs: Map<String, List<String>>): Set<String> =
    targets.flatMap { target -> verbs.flatMap { (verb, endings) -> endings.map { ending -> target + verb + ending } } }
        .toSet()

/**
 * S05-12/S06-21: exact phrases only. "목적지 취소" (destination) and "길안내 종료" (guidance) keep their
 * own explicit meaning; "취소하지 마" is a negation and never reaches this set. voice-F4: the search,
 * the candidates and the candidate choice ("목적지 선택 취소", "선택 취소" - S06-39) with every ending.
 */
private val DIALOG_CANCEL_COMMANDS = voicePhraseTable(
    targets = listOf(
        "", "검색", "검색을", "후보", "후보를", "후보선택", "후보선택을", "선택", "선택을", "목적지선택", "목적지선택을",
    ),
    verbs = mapOf("취소" to HADA_VERB_ENDINGS),
)
/** S06-38: exact phrases only; "이전" alone or "이전 경로" is not this command. */
private val PREVIOUS_CANDIDATES_COMMANDS = setOf("이전후보", "앞후보", "이전후보들려줘", "이전후보다시들려줘")
private val START_NAVIGATION_COMMANDS = setOf("안내시작", "길안내시작", "안내시작해줘", "안내시작해주세요", "길안내시작해줘")
private val NEXT_NAVIGATION_COMMANDS = setOf(
    "다음안내해줘",
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
    "다른경로찾아줘", "경로재탐색",
    "새경로요청",
    "새경로요청해줘",
    "새경로찾아줘",
    "경로다시찾아줘",
    "재탐색해줘",
)
private val LOCATION_RECHECK_COMMANDS = setOf(
    // S05-10: asking to check "my" location again is the same re-measure request.
    "내위치확인", "내위치확인해줘", "내위치다시확인", "내위치다시확인해줘",
    "위치다시확인",
    "위치확인해줘",
    "위치다시확인해줘",
    "현재위치다시확인",
    "현재위치다시확인해줘",
)
private val ARRIVAL_CONFIRM_COMMANDS = setOf(
    "도착했어요",
    "도착확인",
    "도착했어",
    "도착했습니다",
)
private val ARRIVAL_REJECT_COMMANDS = setOf(
    "아직도착안했어", "아직도착안했어요",
    "도착아니야",
    "아직도착아니야",
    "도착하지않았어",
)
/** voice-F4: 안내/길안내 × 종료/중지/취소 (하다 endings) and 멈춰 (its own endings), plus the fixed extras. */
private val STOP_NAVIGATION_COMMANDS = voicePhraseTable(
    targets = listOf("안내", "안내를", "길안내", "길안내를"),
    verbs = mapOf(
        "종료" to HADA_VERB_ENDINGS,
        "중지" to HADA_VERB_ENDINGS,
        "취소" to HADA_VERB_ENDINGS,
        "멈춰" to STOP_VERB_ENDINGS,
    ),
) + setOf("길안내그만", "내비중지", "네비중지")
private const val MAX_DESTINATION_CANDIDATES = 20
private const val MIN_VOICE_CONFIDENCE = 0.55f
