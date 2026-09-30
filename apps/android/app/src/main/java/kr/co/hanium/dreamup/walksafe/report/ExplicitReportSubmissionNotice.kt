package kr.co.hanium.dreamup.walksafe.report

/**
 * S05-34 (originals/05.txt:325-328): a recognized report command, a saved report and a server
 * acceptance are different states. After the second (confirming) action the report is only saved
 * on the phone; owner decision 3(b) sends reports after the walk has explicitly ended, so the user
 * is told it is not yet accepted. "신고가 접수되었습니다." is reserved for the server CREATED result.
 */
internal object ExplicitReportSubmissionNotice {
    const val SAVED_NOT_SUBMITTED_KO =
        "방금 확인한 신고를 휴대전화에 저장했습니다. 아직 접수되지 않았으며, 보행을 마친 뒤 전송합니다."
}
