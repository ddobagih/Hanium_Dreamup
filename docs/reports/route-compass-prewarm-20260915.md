# 안내 전 나침반 측정과 초기 상대 방향 안내 개선

## 요청과 성공 기준
- 로그인 후 위치 관측이 시작되면 방향도 계속 관측하고 안내 시작·카메라 전환에서 살아 있는 측정값을 초기화하지 않는다.
- 경로가 정해지면 현재 후면 방향과 경로 방향을 비교하여 앞·뒤·좌·우를 안내한다. GPS 이동 방향을 사용자 시선으로 대체하지 않는다.
- 숫자 방위 오차 미제공과 실제 방향 관측 불가를 구분한다. 대체 나침반은 AR/PDR의 엄격한 입력과 분리한다.
- 계정/권한/화면 수명주기, 오래된 센서·위치, 자기장 불량, 후면 수직 상태는 별도로 처리한다.

## 단계와 검증
1. 현재 거부 경로 분석 → 센서 등록/리셋, 방위 오차 미제공, 편각 기준 업데이트, 경로 방위 조건을 코드와 공식 API로 확인.
2. 안내 전 측정 유지 → 위치 소유권이 유지되는 안내 준비/카메라 변경에서 센서를 보존. 새 계정·일시 중단에는 이전 데이터 차단.
3. 나침반 대체 경로 → 중력/자력계의 신선도·품질·행렬을 검증하고 수치오차 없는 관측으로 명시. 기존 AR/PDR 입력 유지.
4. 초기 안내·표시 연결 → 메인에서 안내 전 방향 준비 상태를 확인하고 현재 방향을 첫 안내/재안내에 전달. 경로 방향 불확실성과 나침반 불확실성을 구별.
5. 회귀시험·독립 검수·APK → 위치 전처리 거부/센서 미지원/첫 경로/회전/중단 복귀를 검사. 빌드와 외부 다운로드 파일 검증. 실제 절대방위·야외 음성 결과는 사용자 기기 재시험 필요.

## 착수 시 확인한 문제
- 센서는 로그인 후 위치 수집 때 이미 등록하지만 cancelWalkSessionOutputs와 카메라 미사용 전환이 무조건 stop하여 측정값을 지운다.
- fresh raw 위치가 위치 필터에서 거부되면 안내 전 편각 기준 업데이트가 누락된다.
- TYPE_ROTATION_VECTOR의 values[4] 미제공(-1 포함)을 회전 데이터 전체 무효로 처리한다. 공식 API는 오차 정보 미제공을 허용한다.
- 기존 나침반 상태는 경로 활성화 후 안내 전용 화면에만 있어 메인에서 원인·준비 상태를 볼 수 없다.
- 경로 방위도 위치 정확도 15m 조건이 있으므로 센서가 정상이어도 상대 방향 안내가 생략될 수 있다. 구체 보완은 독립 분석 후 결정.

## 담당과 범위
- Root: MainActivity 수명주기·기준 위치·표시, 경로 통합, 검증 실행·기록·APK.
- facing_heading_analysis: route 전용 대체 나침반·관측 계약·해당 시험.
- navigation_voice_scenario_audit: 경로 방위 독립 진단 및 통합 검수.
- 휴대폰 미연결. 현장 원인은 아직 로그로 확인되지 않아 코드 결함과 구분한다.

## 적용된 설계
- 센서: 위치 소유자가 유지되는 안내 준비·카메라 종료는 센서 측정값을 보존한다. 위치 소유권 종료 시 예약 표시 갱신과 지자기 기준을 비우고, 기존 카메라용 센서 소유권은 별도 유지한다.
- 지자기 기준: 최근 10초 이내 비모의·유효 좌표를 PDR 필터 전에 사용한다. 이 기준 충족은 보행 위치 정확도나 경로 매칭 신뢰 상향이 아니다.
- 나침반: 보고된 수치 오차가 있는 회전 벡터 우선. 오차 미제공·센서 부재 시 중력/가속도계+자력계 대안. 오차 수치 없이 품질검사를 통과한 관측은 별도 source/quality로 구분하고 실제 오차값을 생성하지 않는다.
- 경로: 원래 위치 주변 `max(8m, 2×위치 오차 추정치)`의 모든 경로 선분 방향을 비교한다. 2배는 후보 탐색 여유이며 실제 위치의 포함 확률을 주장하지 않는다. 초기·수동·주기 재안내 때 새 나침반에 대한 모든 후보의 상대방향 합의를 요구한다.
- 촘촘한 직선의 구간 번호만 불확실한 경우 위치/거리 불확실성을 유지하면서 상대방향만 안내할 수 있게 한다. 역방향 가지·코너·종점 통과 여부가 불명확한 경우를 별도 시험한다.
- 표시: HOME·GUIDANCE의 별도 나침반 영역을 500ms마다 갱신한다. 자동 낭독을 하지 않아 음성 안내를 방해하지 않도록 한다.

## 독립 검수 진행
- Main 수명주기 검수에서 POLITE 안내 상태에 각도 변경을 넣으면 TalkBack 자동 낭독이 반복되는 P2를 발견했다. 나침반을 NONE 전용 영역으로 분리했고 재검수에서 해결 확인했다.
- Main 관련 정적 회귀와 지리 기준 순수 정책 시험 13개 통과. Android Handler/실제 TalkBack·하드웨어 센서 시험과는 구분한다.
- 경로 독립 설계 검수에서 짧은 전체 경로 또는 종점이 위치 불확실성 범위에 들어오는 반례를 발견해 구현자에게 전달했다.
- 아래 최종 결과로 통합 및 독립 검수를 마무리했다.

## 공식 근거
- Android SensorEvent: https://developer.android.com/reference/android/hardware/SensorEvent
- Android position sensors: https://developer.android.com/develop/sensors-and-location/sensors/sensors_position
- AOSP SensorManager JNI: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/jni/android_hardware_SensorManager.cpp
- SensorManager rotation matrix: https://developer.android.com/reference/android/hardware/SensorManager

## 최종 결과
- 통합 Gradle: 162 suites / 1556 tests / 실패 0 / 오류 0 / skip 0, `assembleDebug` 통과. navigation·voice·feedback·session·MainActivity 선택 범위이다. 기존 별도 제외 12건은 동일하게 유지했으며 전체 저장소 무예외 통과를 의미하지 않는다.
- 첫 실행은 제품 컴파일·APK 성공, 기존 센서 등록 inline 코드 문자열을 기대하던 정적 검사 1건 실패였다. 회전 센서 OR 대체 센서 쌍, 등록 예외→false의 기능 계약을 확인하도록 갱신했고 제외 추가 없이 최종 통과했다.
- 경로의 동일 시험 156개 비교: 수정 전 새 회귀 8개 실패 / 현재 156개 통과. 기존 142개도 통과했다. GPS20·30·40m 직선 앞뒤, dense 직선의 위치불확실성 유지, 코너/반대가지/종점·오류입력, 재안내 때 사용자 회전, 발화 중단 방지를 포함한다.
- Compass/Facing 순수 시험 52개, 회전행렬 2,000 사례 대조, 실제 Tracker 코드에 Android 경계만 모사한 35개 검사 통과. 이 수치는 통합 선택 시험과 일부 겹치므로 합산하지 않는다.
- Main·경로·센서 별도 인스턴스 독립 검수 수행. TalkBack 갱신 P2와 RV 신규 불량 callback P2를 수정 후 재검수해 배정 범위의 미해결 P1/P2 없음 확인.
- APK: `/home/ddobagi/Hanium_Dreamup/work/apk-download-20260915/public/WalkSafe-compass-ready-20260915.apk`
- SHA-256: `23b08166a04f3349c0ec88a17cd80548dcaf5e820496ad78bef643c1c9e63234`
- 원본 패키지 `kr.co.hanium.dreamup.walksafe`, 이전 APK와 서명 동일. 외부 HEAD200·길이453805096·ZIP헤더를 확인했다.
- 다운로드: https://advisors-skirt-editing-assessing.trycloudflare.com/WalkSafe-compass-ready-20260915.apk
- 최종 USB 기기 없음: 설치하지 않았다. 실제 사용자 실패 당시 어느 센서 조건이 원인이었는지와 야외 절대방위·음성 결과는 아직 미확인이다.

## 남은 현장 확인과 한계
1. 업데이트 설치 후 로그인한 메인에서 후면을 걷는 쪽으로 들고 제자리 회전할 때 나침반 방향·각도가 따라 변하는지 확인. 안내 시작 전부터 표시해야 한다.
2. 긴 직선 경로를 선택하고 앞/뒤를 향해 시작할 때 상대방향이 달라지는지 확인. 시작 전 관측값이 안내 준비로 지워지지 않아야 한다.
3. 발화 종료 후 10초 주기 및 제자리 회전 후 재안내 확인. 나침반 표시 자체는 자동 낭독하지 않는다.
4. 센서 수치오차 없는 대안의 품질검사는 절대방위 오차의 통계적 보장이 아니다. 자기장 간섭·후면 수직·과대 위치 오차·코너/종점 불확실에서는 상대방향을 보수적으로 생략할 수 있다. 출발/종점이 가까운 루프도 해당한다. 안내 시작 자체를 차단하는 조건을 다시 활성화한 것은 아니다.

## 근거 위치
- 통합: `work/route-compass-prewarm-20260915/integration/`
- 제품 해시·공개 APK·독립 검수: `work/route-compass-prewarm-20260915/`
- 경로 비교: `work/facing-route-guidance-20260915/bearing-candidates/result.md`
- 센서 모사/직접 시험: `work/facing-route-guidance-20260915/compass-fallback/result.md`
