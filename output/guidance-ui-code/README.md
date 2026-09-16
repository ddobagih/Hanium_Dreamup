# 길안내 UI 코드 재현

현재 MainActivity.kt의 뷰 구성, 스타일 함수, 상태 분기를 읽어 외부 렌더러로 재현했습니다. Android 런타임 스크린샷이 아닙니다. 코드의 레이아웃 구조와 시각 속성을 반영했지만 실제 기기의 TextView 폰트 패딩, 줄바꿈, 테마, 스크롤 표시까지 동일하다고 보장하지 않습니다.

- 기준 콘텐츠 너비 412dp, 글자 배율 1배. 상태바·내비게이션바·키보드 제외.
- 기본 화면 외곽 20dp/24dp, 시작 확인 화면 16dp.
- 개별 이미지는 긴 화면의 전체 콘텐츠를 표시. 모음 이미지는 위쪽 780dp 영역.
- 구미역·거리·경로 지시·장애물 메시지는 동적 입력 예시. 실제 검색이나 감지 결과가 아닙니다.
- 안내 화면 주요 버튼은 최신 세로 배치 적용. 시작 확인 본문은 최신 코드에서 GONE.
- 미리보기 전용 도착/이탈 상태를 실사용 화면으로 오인하지 않도록 이 모음에는 포함하지 않았습니다.

## 근거 함수
MainActivity.kt: requestNativeGuidanceStartConfirmation, styleGuidanceScreen, nativeGuidanceStatusMessage, nativeGuidancePreflightSummary, currentNativeGuidancePresentation, updateDestinationSearchUi, applyWsButtonStyle, applyNativePreviewStyles 관련 스타일 블록.
DestinationGuidancePresentationPolicy.kt: 상태 제목 및 위치·경로 메시지 구성.

## 이미지별 조건

- 01-search.png — 목적지 검색: 검색 가능, 검색어 없음. 키보드·시스템 UI 제외.
- 02-results.png — 검색 결과: 목적지·주소·거리 값은 샘플. 결과 1개, 더보기 숨김. 전체 스크롤 콘텐츠.
- 03-destination-confirm.png — 목적지 확인 뷰: DESTINATION_CONFIRM 뷰 구성. 실제 진입 경로별 노출 여부는 별도이며 구미역은 샘플.
- 04-preparing.png — 위치·장착 확인 중: 준비 상태, GPS 미확인·흔들림 조건. 다시 듣기/일시정지 숨김.
- 05-start-confirm.png — 길안내 시작 확인: 최신 본문 제거 코드. 제목+시작/다시 듣기/취소.
- 06-active.png — 안내 진행 중: 목적지·경로 지시·남은 거리만 샘플 값. 활성 경로, 재시도 없음.
- 07-paused.png — 일시정지: 일시정지 및 경로 준비 메시지 조건 예시. 실제 원인에 따라 내용/다시 시도 노출 변동.
- 08-obstacle.png — 장애물 경고: 유효한 currentGuidanceRisk가 있는 상태. FeedbackAction.message는 예시이며 객체별로 달라짐.
- 09-preflight-failure.png — 준비 점검 실패: 점검 실패+위치 요청 실패+렌즈 가림 조건 예시.
- 10-location-wait.png — 안내 중 위치 대기: 세션 ACTIVE, 신뢰 위치 없음, 카메라 출력 불가, 준비 작업 없음.