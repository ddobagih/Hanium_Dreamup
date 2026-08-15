# Walking Navigation

이 패키지는 위치 신뢰도, 보폭 추정, Gateway가 중계하는 목적지/도보 경로 계약과 경로 진행 상태를 담당한다.

## 구성

- `LocationPolicy`: 낮은 정확도와 비현실적인 GPS jump를 제외한다.
- `AndroidStepTracker`: step counter 우선, accelerometer peak fallback으로 보행 수를 센다.
- `StepLengthEstimator`: 검증 범위 안의 GPS 거리/보행 수로 보폭을 보정한다.
- `BackendWalkingRouteClient`: 인증된 Gateway의 `/api/navigation/destinations/search`와 `/api/navigation/walking`만 호출한다.
- `RouteNavigator`: 저장된 TMAP 경로의 안내 지점·진행량·이탈·도착 후보를 관리한다. 이탈 뒤 재탐색과 도착 확정은 사용자 선택 전에는 실행하지 않는다.

현재 구현은 GPS 필터·보폭 보정, Gateway 경로 client, 세 개 단위 음성 목적지 후보, 사용자 확인형 재탐색·도착, 경로 진행 상태와 route-bound 점자블록 관측을 포함한다. 남은 거리와 이탈은 trusted GPS를 저장된 TMAP 경로에 투영해 계산하며 보폭 진행량은 도착 후보의 보조 일관성 검사에만 사용한다. GPS·권한·TMAP을 신뢰할 수 없으면 방향 안내를 중지하고 보폭으로 위치나 방향을 대신하지 않는다. gyroscope/Kalman filter/dead reckoning 기반 GPS 정밀 보정은 구현돼 있지 않다. TMAP key는 Android나 Gateway에 두지 않고 backend proxy가 소유한다.

TMAP 보행 경로가 전역 경로다. production `AndroidTactileRouteObservationSupplier`는 ARCore physical camera pose, 같은 capture frame의 metric depth, trusted GPS와 현재 TMAP route projection을 route/frame ID에 결합한다. 하나라도 없거나 불일치하면 observation을 버리고 TMAP 안내를 유지한다. 이 연결은 CODE·JVM AUTO 근거이며 camera calibration/EIS, GPS drift와 실외 local steering 품질은 아직 Field 검증하지 않았다.
