# Walking Navigation

이 패키지는 위치 신뢰도, 보폭 추정, Gateway가 중계하는 목적지/도보 경로 계약과 경로 진행 상태를 담당한다.

## 구성

- `LocationPolicy`: 낮은 정확도와 비현실적인 GPS jump를 제외한다.
- `AndroidStepTracker`: step counter 우선, accelerometer peak fallback으로 보행 수를 센다.
- `StepLengthEstimator`: 검증 범위 안의 GPS 거리/보행 수로 보폭을 보정한다.
- `BackendWalkingRouteClient`: 인증된 Gateway의 `/api/navigation/destinations/search`와 `/api/navigation/walking`만 호출한다.
- `RouteNavigator`: 저장된 TMAP 경로의 안내 지점·진행량·이탈·도착 후보를 관리한다. 이탈 뒤 재탐색은 사용자 선택 후 실행하며, 도착은 연속 위치 표본과 체류 시간을 충족하면 자동으로 완료한다.

현재 구현은 GPS 필터·보폭 보정, Gateway 경로 client, 세 개 단위 음성 목적지 후보, 사용자 확인형 재탐색과 자동 도착 종료, 경로 진행 상태와 route-bound 점자블록 관측을 포함한다. 남은 거리와 이탈은 trusted GPS를 저장된 TMAP 경로에 투영해 계산하며 보폭 진행량은 도착 후보의 보조 일관성 검사에만 사용한다. GPS·권한·TMAP을 신뢰할 수 없으면 방향 안내를 중지하고 보폭으로 위치나 방향을 대신하지 않는다. gyroscope/Kalman filter/dead reckoning 기반 GPS 정밀 보정은 구현돼 있지 않다. TMAP key는 Android나 Gateway에 두지 않고 backend proxy가 소유한다.

TMAP 보행 경로가 전역 경로다. production `AndroidTactileRouteObservationSupplier`는 ARCore physical camera pose, 같은 capture frame의 metric depth, trusted GPS와 현재 TMAP route projection을 route/frame ID에 결합한다. 하나라도 없거나 불일치하면 observation을 버리고 TMAP 안내를 유지한다. 이 연결은 CODE·JVM AUTO 근거이며 camera calibration/EIS, GPS drift와 실외 local steering 품질은 아직 Field 검증하지 않았다.


## 경로 질문과 음성 전달의 책임

- `MainActivity`는 현재 화면·입력·출력·위험 판단을 지연 조회하는 `GuidanceSpeechPeerAdapter`를 조립한다. 어댑터는 기존 `SpeechOutputController`의 전달 계약을 안내 컨트롤러에 맞추며 별도 음성 상태나 큐를 만들지 않는다.
- `RouteGuidanceController`는 경로·보행 세션·출력 세대의 유효성, 위치/방향 안전 판단, 화면과 진동 효과를 책임진다.
- `RouteDecisionSpeechCoordinator`는 위치 재확인·새 경로 질문의 전달과 재시도만 책임진다. 현재 `Attempt` 한 개가 질문 토큰, 유효성 검사, 응답 세대와 재시도 작업을 소유한다. 마지막 실제 완료 토큰과 함께 읽기 전용 상태로 관찰할 수 있다. 자동 도착은 `RouteGuidanceController`가 별도 완료 소유자로 “도착했습니다. 안내를 종료합니다”를 전달하며, 제출 수락·START와 실제 DONE을 구분한다. DONE·ERROR·STOP 또는 제한 시간 종료 후에도 같은 보행 소유자가 유효할 때만 한 번 ENDED/HOME으로 마친다.

질문 전달은 `현재 질문 접수 → 출력이 바쁘면 재시도 대기 → 제출 → 실제 DONE 또는 실패`로 전이한다. 수락/START는 완료가 아니며, 실패는 동일 질문이 유효한 동안 기존 1,000ms 간격으로 재시도한다. 실제 DONE만 중복 안내를 억제하고 명시적인 반복 요청은 다시 전달한다.

취소는 현재 시도를 먼저 무효화하고 해당 재시도 작업을 제거한 뒤 자신이 소유한 응답 세대만 취소한다. 이전 작업을 강제로 실행하거나 늦은 완료/실패가 도착해도 새로운 질문을 변경하지 않는다. 현재 scope 또는 경로 질문 토큰이 달라지면 이전 시도는 폐기되며, 출력 소유자 회복 후 같은 미응답 질문을 다시 전달할 수 있다.

이 구조는 질문 수명주기의 **조정자(Coordinator)**와 안내/음성 계약의 **어댑터(Adapter)**를 적용한다. 질문별 State 하위 클래스나 범용 이벤트 시스템을 추가하지 않고 현재 시도 객체의 동일성으로 소유권을 표현한다. 센서 품질 기준, 경로 판단과 위험 출력 우선순위는 기존 소유자에 남는다.

관련 행동 시험은 `RouteDecisionSpeechCoordinatorTest`, `VoiceCompositionBehaviorTest`, `RouteDecisionOwnerRecoveryRegressionTest`, `MainActivityRouteDecisionSpeechBehaviorTest`, `GuidanceDecisionOwnerRecoveryReplayTest`, `GuidanceFullJourneyReplayTest`다. 실제 컨트롤러 연결과 제어한 시계/음성 콜백을 검증하며, 실제 스피커 청취와 야외 보행은 별도 검증 범위다.
