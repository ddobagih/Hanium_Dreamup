# Walking Navigation

이 패키지는 위치 신뢰도, 보폭 추정, backend 목적지/도보 경로 계약과 경로 진행 상태를 담당한다.

## 구성

- `LocationPolicy`: 낮은 정확도와 비현실적인 GPS jump를 제외한다.
- `AndroidStepTracker`: step counter 우선, accelerometer peak fallback으로 보행 수를 센다.
- `StepLengthEstimator`: 검증 범위 안의 GPS 거리/보행 수로 보폭을 보정한다.
- `BackendWalkingRouteClient`: backend의 목적지 검색과 `/navigation/walking`만 호출한다.
- `RouteNavigator`: 안내 지점, 이탈 확인, 재탐색 cooldown과 도착을 관리한다. turn guide와 cooldown은 caller가 실제 발화를 acknowledge한 뒤에만 소비한다.

현재 구현은 GPS 필터와 보폭 보정이다. gyroscope/Kalman filter/dead reckoning 기반 GPS 정밀 보정은 구현돼 있지 않다. TMAP key는 Android에 두지 않고 backend proxy가 소유한다.

TMAP 보행 경로가 전역 경로다. production `AndroidTactileRouteObservationSupplier`는 ARCore physical camera pose, 같은 capture frame의 metric depth, trusted GPS와 현재 TMAP route projection을 route/frame ID에 결합한다. 하나라도 없거나 불일치하면 observation을 버리고 TMAP 안내를 유지한다. 이 연결은 CODE·JVM AUTO 근거이며 camera calibration/EIS, GPS drift와 실외 local steering 품질은 아직 Field 검증하지 않았다.
