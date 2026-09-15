# 휴대폰 나침반 기반 길안내 방향 보완

## 사용자 관측과 확인 범위

사용자는 직전 APK(3029f867…)를 야외에서 설치·시험한 뒤 현재 방향을 인식하지 못한다고 보고했다. 실제 순간의 센서 로그와 휴대폰 자세는 확보하지 못했으므로 단일 원인을 확정하지 않는다. 코드를 통해 거부 가능한 조건을 찾고 같은 입력에서 개선을 확인했다.

## 원인과 단계별 개선

1. 기존에도 TYPE_ROTATION_VECTOR와 지자기 센서를 사용했다. 하지만 길안내가 가슴 고정 PDR용 ChestMountedHeading 판정을 거쳐 휴대폰 윗부분의 수직 정렬·전용 자기장 샘플의 품질/동기화/자기장 비교를 모두 요구했다. 실제 코드 시험에서 동일한 45도 기울기의 동쪽 방위 행렬은 UNSUPPORTED_POSTURE로 거부됐다.
2. 새 RouteCompassHeading은 Android가 센서를 융합한 회전 행렬에서 후면 −Z축의 수평 동·북 성분을 추출하고 현재 위치의 편각을 더한다. 윗부분이 수직이어야 한다는 조건을 없앴다. 별도 자기장 샘플은 길안내 나침반의 추가 필수 조건으로 쓰지 않으며 기존 PDR/AR의 strict 경로는 그대로 둔다.
3. 후면 축의 수평 성분이 0.5 이상인 자세에서만 방위를 계산한다. 이는 수평 전방에서 위/아래 약60도까지이며, 후면이 거의 바닥·하늘을 향하는 경우는 방향을 임의로 만들지 않는다. 기존 사용자 조건인 후면 카메라가 걷는 방향을 향하는 사용을 전제로 하며, 휴대폰 윗부분 방향을 사용자 방향으로 몰래 대체하지 않는다.
4. 센서 시간 500ms·MEDIUM/HIGH 품질·보고된 방위 오차0~30도·유효 회전 행렬 조건을 확인한다. 기울기에서 불확실성이 커지는 것은 rawError/horizontalProjection으로 보수화해 최종30도 이내에서만 쓴다. 이 보정값은 검증된95% 확률 범위가 아닌 분류용 보수적 추정이다. 없는 방위 오차를 지어내지 않는다.
5. Tracker가 센서 없음/등록 실패, 값 대기, 센서의 오차 정보 미제공, 품질 불량, 행렬 이상을 구분해 보존한다. Main은 상태 변화 이유만 DEBUG/기존 로컬 로그에 기록하고 안내 상태 카드에 나침반 방위 또는 구체적인 대기 이유를 표시한다.
6. 음성에서 나침반 자체의 실패, GPS/경로 방위 불확실, 나침반은 정상이나 앞뒤·좌우 경계가 모호한 상황을 구분한다. 공통 관측 검증을 재사용해 stale/future 관측을 정상 나침반으로 잘못 설명하지 않는다. 이유 문장이 바뀌면 이전 미예약 토큰을 폐기하며 진행 중 같은 안내 지점 문장은 유지한다.

## 공식 API 확인

find-docs로 Android 문서를 조회했으나 해당 세부 검색 결과가 없어 공식 문서를 직접 확인했다.

- [Android position sensors](https://developer.android.com/develop/sensors-and-location/sensors/sensors_position): 일반 나침반 방위와 기기 축, 좌표계 변환, 자력계를 사용하지 않는 GAME_ROTATION_VECTOR의 차이.
- [AOSP rotation vector](https://source.android.com/docs/core/interaction/sensors/sensor-types#rotation_vector): 가속도계·자력계·자이로의 센서 융합, 자기 북쪽 기준, 방위 오차 보고.

## 검증

- 신규 나침반 정책 직접 JVM15건 통과. 같은45도 기울기 행렬에서 기존 strict 정책의 거부와 새 정책의 정확한90도 반환을 동시에 확인했다. 이 숫자는 물리 센서 실측 결과가 아니다.
- Main/Tracker/Navigator 연결 후 Android 선택 회귀 **159 suites / 1,516 tests / 실패0 / 오류0 / skip0**, APK 빌드 성공. 기존 무관 정적 불일치12개는 종전 목록으로 제외했으며 전체 저장소 PASS를 뜻하지 않는다.
- 추가 기능 시험: 정상 나침반의 방향 경계를 센서 실패로 설명하지 않음, UNKNOWN 이유가 바뀔 때 이전 문장의 예약 거부, GPS가 부정확할 때 나침반 실패로 설명하지 않음. 기울기/roll/남북동서/편각 wrap/센서 만료/품질/오차 누락 검증 포함.
- 실행 근거: `work/route-compass-20260915/integration/`. 정책 직접 시험: `work/facing-route-guidance-20260915/facing-policy/compass/`.
- 독립 검토: 실제 정책 소스를 재컴파일하여 쿼터니언 직접 회전 oracle 10,000개와 대조했다. 유효8,681개·수직거부1,319개 일치, 최대 계산 차이0.00000283도. 이는 알고리즘 수학 대조이며 물리 나침반의 측정 정확도를 의미하지 않는다. Tracker/Main/Navigator 연결의 독립 소스 검토도 추가 확정 P1/P2 없음. 실제 야외 자력계 교란·센서 이벤트·스피커 출력·사용자가 겪은 순간의 원인은 미검증이다.

## APK

- 원본 앱: `apps/android/app/build/outputs/apk/debug/app-debug.apk`
- SHA256: `bbc7786244436db7f112c6bdb83b67830d6a2d9d873997dfdaf59ba03a50a21f`
- 크기:453805096bytes. 외부 HEAD200·파일크기·APK 헤더 확인 완료.
- 다운로드: https://advisors-skirt-editing-assessing.trycloudflare.com/WalkSafe-compass-20260915.apk
