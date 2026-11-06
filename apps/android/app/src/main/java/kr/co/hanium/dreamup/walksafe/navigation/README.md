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

## 방향 음성 안내 간격

`RouteNavigator`는 신뢰 가능한 시계 방향을 안내한 음성의 실제 완료 뒤 기본 최소2초 후 변경된 안내를 전달하고, 같은 안내는5초 후 반복한다. 기존 수동 요청·근접·안전 안내의 즉시 처리 예외는 유지한다. 이전에 말한 방향으로 다시 돌아와도 마지막으로 전달한 방향과 다르면 변경으로 처리하되, 이 중복 해제에는 최소간격을 적용한다. 재생 중인 일반 방향 문장은 기존 방식대로 마치며, 센서 품질·방향 안정화·오래된 관측 차단과 위험 안내 우선순위는 유지한다. 위치·방향 재확인 설명, 경로 옆 안내, 도착 등 다른 문구에는 기존6초/10초 간격과 즉시 안내 예외를 유지한다. 실제 재생 시작은 음성 출력 상태와 다음 관측/갱신 시점에 따라 늦어질 수 있다. 홈의 동서남북 나침반 표시는 이 경로의 자동 음성이 아니다.


## 경로 경계와 복구

- 자동 도착은 정확도·연속 관측·체류 시간뿐 아니라 남은 보호 안내 지점의 통과를 요구한다. 횡단보도·회전·육교·지하보도·경사로·엘리베이터를 지나지 않은 상태에서는 목적지 반경에 들어와도 종료하지 않는다. 도착 소유권은 설치된 경로와 현재 보행 세션에 결합하며, 검색 화면의 임시 목적지 선택과 분리한다. 새 경로 요청 중에는 기존 경로의 도착 완료를 보류한다.
- 위치 복구는 두 개의 새 관측 사이 경로상 이동 거리와 실제 좌표 이동 거리를 검사한다. 선분 번호가 달라도 도달 가능한 이동이면 복구하므로 촘촘한 좌표로 구성된 경로에서 멈춰 설 필요가 없다. 평행한 다른 구간으로의 경로상 큰 도약은 허용하지 않는다.
- 위치 콜백과 300ms 방향 재평가는 같은 초기 위치 준비 판단을 사용한다. 준비 중 허용되는 현재 raw GPS의 기존 정확도·신선도·권한·시간 제한을 동일하게 적용한다.
- 초기 경로의 일시적 통신 실패는 첫 실패를 포함해 최대 5회, 첫 실패부터 60초까지만 복구한다. 제한 시간에는 GPS 대기와 진행 중 HTTP 요청도 포함한다. 한도에 이르면 해당 요청을 취소하고 선택한 목적지를 유지한 채 사용자가 다시 안내 시작을 선택하도록 알린다. 잘못된 안전 정보·좌표 응답은 일시적 통신 장애로 분류하지 않는다.
- 안내를 실제 재생하기 직전에 현재 안내 지점·거리·경로 문맥을 다시 확인한다. 이미 재생을 시작한 일반 안내는 이후의 정상적인 거리 변화 때문에 완료 처리를 잃지 않는다. 시설 이름과 다음 행동은 접근 거리 문구와 구분한다.
- 목적지 검색어 정규화는 반복 적용해도 결과가 같으며, 장소명 없는 명령은 요청 전에 거절한다. 요청 생성 실패도 검색 상태를 복원하고, 억제된 음성도 소유한 명령의 실패 완료를 한 번 전달한다.

관련 검증은 기존 `RouteNavigatorDestinationApproachTest`, `RouteNavigatorAmbiguousRouteRecoveryRegressionTest`, `RouteNavigatorClockDistanceGuidanceTest`, `RouteGuidanceControllerTest`, `RouteAcquisitionControllerTest`, `InitialRouteRetryEpisodeTest`, `RouteWarmingProjectionTest`, `DestinationSearchControllerTest`, `BackendWalkingRouteClientTest`, `SpeechOutputControllerBehaviorTest`에 포함한다. 제어된 위치·시계·응답 검증과 실제 야외 보행 품질은 구분한다.
