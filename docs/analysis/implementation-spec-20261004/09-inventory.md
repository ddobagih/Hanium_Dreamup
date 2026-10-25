# 화면·명령·설정·테스트 누락 대조

[전체 목차](README.md) · [전체 진입점 대응표](evidence/entrypoint-feature-map.csv) · [추가 선언 인벤토리](evidence/supplemental-inventory.csv) · [검수 범위](08-review.md)

고정 소스의 선언·파일을 분야별 명세와 대조한 보조 색인이다. 패턴 추출에는 동적 등록·문자열 합성·반사·간접 호출 누락 가능성이 있다. **같은 파일 연결은 후보 연결이며 해당 선언의 모든 분기를 설명했다는 보증이 아니다.** 기능 상세의 근거 범위와 독립 검토 범위를 함께 확인한다. 설정은 식별자만 수집해 비밀값을 복제하지 않았다.

## 대조한 목록

|목록|선언/파일 수|
|---|---:|
|CONFIG_IDENTIFIER|316|
|TEST_FILE|1438|
|SCREEN_MENU_DECLARATION|6|
|VOICE_COMMAND_OR_ACTION|38|
|NATIVE_UI_PAGE|6|

테스트 파일 중 기능에서 명시적으로 연결한 파일은 304개다. 나머지는 파일 인벤토리이며 기대 단언을 모두 검토하지 않았다. 이번 테스트 실행은 없다. 기능당 연결한 TEST_EXPECTATION과 과거 실행 기록은 상세 명세에서 구분한다.

## 사용자 앱 최상위 페이지

`NativeUiPage` 6개 enum 값의 주요 연결이다. 가입/교육/삭제/관리자/디버그의 별도 화면·dialog는 이 enum의 완전 목록이라고 간주하지 않으며, 추가 선언·UI listener 인벤토리와 각 분야 상세를 함께 참조한다.

|페이지|주요 기능|
|---|---|
|HOME|[F-D02-001](domains/D02.md#F-D02-001)|
|DESTINATION_SEARCH|[F-D03-019](domains/D03.md#F-D03-019)|
|DESTINATION_CONFIRM|[F-D03-023](domains/D03.md#F-D03-023)|
|GUIDANCE|[F-D03-030](domains/D03.md#F-D03-030)|
|VOICE_COMMAND|[F-D03-016](domains/D03.md#F-D03-016)|
|SETTINGS|[F-D01-022](domains/D01.md#F-D01-022)|

## Android 로컬 음성 명령·action

아래는 `AndroidVoiceCommand`와 `AndroidVoiceAction`의 선언값이다. command와action이 같은 이름이어도 별도 타입이다. 인식 문구·confidence·단일 명령·상태 제약은 [F-D03-013](domains/D03.md#F-D03-013), 보행 제어 우선 처리는 [F-D03-015](domains/D03.md#F-D03-015)로 연결한다. 서버 intent와 현재 앱의 로컬 dispatcher는 같은 실행 경로가 아니다.

|식별자|선언 줄|관련 기능|
|---|---:|---|
|CreateReport|6|[F-D03-013](domains/D03.md#F-D03-013)|
|OpenSettings|7|[F-D03-013](domains/D03.md#F-D03-013)|
|SetDestination|8|[F-D03-013](domains/D03.md#F-D03-013)|
|SelectDestinationCandidate|9|[F-D03-013](domains/D03.md#F-D03-013)|
|RepeatDestinationCandidates|10|[F-D03-013](domains/D03.md#F-D03-013)|
|HearMoreDestinationCandidates|11|[F-D03-013](domains/D03.md#F-D03-013)|
|PreviousDestinationCandidates|13|[F-D03-013](domains/D03.md#F-D03-013)|
|CancelDestination|14|[F-D03-013](domains/D03.md#F-D03-013)|
|CancelDialog|19|[F-D03-013](domains/D03.md#F-D03-013)|
|StartNavigation|20|[F-D03-013](domains/D03.md#F-D03-013)|
|RepeatGuidance|21|[F-D03-013](domains/D03.md#F-D03-013)|
|CurrentState|23|[F-D03-013](domains/D03.md#F-D03-013)|
|Help|24|[F-D03-013](domains/D03.md#F-D03-013)|
|NextNavigationInstruction|25|[F-D03-013](domains/D03.md#F-D03-013)|
|RequestReroute|26|[F-D03-013](domains/D03.md#F-D03-013)|
|RecheckLocation|27|[F-D03-013](domains/D03.md#F-D03-013)|
|ConfirmArrival|28|[F-D03-013](domains/D03.md#F-D03-013)|
|RejectArrival|29|[F-D03-013](domains/D03.md#F-D03-013)|
|StopNavigation|30|[F-D03-013](domains/D03.md#F-D03-013)|
|CreateReport|34|[F-D03-013](domains/D03.md#F-D03-013)|
|OpenSettings|35|[F-D03-013](domains/D03.md#F-D03-013)|
|SearchDestination|36|[F-D03-013](domains/D03.md#F-D03-013)|
|SelectDestinationCandidate|37|[F-D03-013](domains/D03.md#F-D03-013)|
|RepeatDestinationCandidates|38|[F-D03-013](domains/D03.md#F-D03-013)|
|HearMoreDestinationCandidates|39|[F-D03-013](domains/D03.md#F-D03-013)|
|PreviousDestinationCandidates|40|[F-D03-013](domains/D03.md#F-D03-013)|
|CancelDestination|41|[F-D03-013](domains/D03.md#F-D03-013)|
|CancelDialog|42|[F-D03-013](domains/D03.md#F-D03-013)|
|StartNavigation|43|[F-D03-013](domains/D03.md#F-D03-013)|
|SpeakCurrentGuidance|44|[F-D03-013](domains/D03.md#F-D03-013)|
|SpeakCurrentState|45|[F-D03-013](domains/D03.md#F-D03-013)|
|SpeakVoiceHelp|46|[F-D03-013](domains/D03.md#F-D03-013)|
|SpeakNextNavigationInstruction|47|[F-D03-013](domains/D03.md#F-D03-013)|
|RequestReroute|48|[F-D03-013](domains/D03.md#F-D03-013)|
|RecheckLocation|49|[F-D03-013](domains/D03.md#F-D03-013)|
|ConfirmArrival|50|[F-D03-013](domains/D03.md#F-D03-013)|
|RejectArrival|51|[F-D03-013](domains/D03.md#F-D03-013)|
|StopNavigation|52|[F-D03-013](domains/D03.md#F-D03-013)|

## API·센서·timer·lifecycle

[707개 선언 진입점 인벤토리](evidence/entrypoint-inventory.json)에 HTTP route·Gateway 경로·Android component/callback/UI listener·CLI main·systemd ExecStart/timer를 수록했다. Root는 미매핑된 debug 위치 시험 Activity/RAW GNSS 도구와 시험용 host를 담당 분야에 추가하도록 요청했다. 최종 잔여 판정은 [대조 결과](evidence/coverage-summary.json)와 [미매핑/인벤토리만 기록된 목록](evidence/entrypoint-unmapped.csv)을 따른다.

## 범위 제한

화면 이름·설정 식별자·테스트 파일 이름의 자동 추출은 본문 정독과 다르다. 대형 Main/helper, 역사 개발 스크립트, 제3자 vendor 수치 알고리즘, 테스트 fixture 전체 및 실운영 환경변수는 전수 의미 검토하지 않았다. 분야 `coverage.unreviewed`, D11의863파일 대응표, 독립 검토보고서에서 조사 수준과 남은 범위를 확인할 수 있다.
