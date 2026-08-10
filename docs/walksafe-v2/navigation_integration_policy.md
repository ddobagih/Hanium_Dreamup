# WalkSafe v2 TMAP walking navigation integration policy

- 기준일: 2026-07-11 KST
- 현재 유효 정책: `PB-WALKSAFE-FEATURE-POLICY-1.0.1`
- 현행 제품 경계: Android 사용자 앱 → 별도 Android API Gateway → `backend/app/api/navigation.py` → `backend/app/services/tmap_pedestrian.py` → TMAP
- 외부 근거: TMAP 보행자 경로안내 공식 문서 <https://tmap-skopenapi.readme.io/reference/%EB%B3%B4%ED%96%89%EC%9E%90-%EA%B2%BD%EB%A1%9C%EC%95%88%EB%82%B4>

## 2026-06-02 현재 우선순위 보정

- 2026-06-02 당시 Web/PWA를 주 사용자 앱 경로로 적은 기록은 historical only이며 현재 제품 경계가 아니다.
- Android 사용자 앱과 별도 Android API Gateway가 현행 제품 경로다. Web/PWA는 레거시 개발 참고일 뿐 runtime fallback, release evidence 또는 Android Device evidence가 아니다.
- Android report upload, TTS/haptic, navigation/search 코드는 연결되어 있지만 bbox/depth 좌표 정합, 보행 중 route timing, TTS/진동 체감은 아직 Device PASS가 아니다.

## 2026-07-11 현재 보정

- backend `GET /navigation/destinations/search`와 Android 목적지 검색 UI는 연결되어 있다.
- Web/PWA의 `NEXT_PUBLIC_WALKSAFE_DESTINATION_LAT/LNG/NAME` 목적지는 개발·테스트 fallback으로만 본다. 실제 사용자 경로는 목적지 검색과 후보 선택을 사용한다.
- Android native voice로 목적지 설정, 후보 선택, 길안내 시작까지 완성된 실사용 UX는 아직 후속이다.

## 1. 목적

WalkSafe의 길안내는 지도 서비스를 대체하는 완전한 내비게이션이 아니라, **보행 경로 + 카메라 기반 보행 안전 레이어**를 섞는 기능이다.

- TMAP 보행자 경로안내 API: 출발지/목적지/경유지 기반 보행 경로 geometry, 거리/시간, 보행 구간 정보 제공
- WalkSafe: server sampled 카메라 탐지, 점자블록 근거리 보조 안내, 보행 위험 TTS/진동, 손상 점자블록 자동 신고

현재 제품의 목적지 검색과 보행 경로 provider는 **TMAP으로 고정**한다. 다른 provider 설정과 응답은 거부하며, 장애 시 자동 또는 수동 전환하지 않는다.

## 2. 역할 분리

| 영역 | 책임 |
|---|---|
| TMAP provider | 보행 경로, 전체 거리, 예상 시간, GeoJSON LineString/road segment |
| WalkSafe backend | TMAP appKey 보호, provider 응답 정규화, provider 오류를 앱 오류로 변환 |
| WalkSafe frontend | 현재 GPS에서 경로 요청, 길안내 상태 표시, TTS 중재 |
| WalkSafe detector | 정상 점자블록/손상 점자블록/일반 객체 탐지 |
| Auto report | `damaged_tactile_block`만 조용히 `/reports/v2` 저장 |

## 3. 경로 옵션 정책

WalkSafe route priority는 TMAP `searchOption`으로 변환한다.

| WalkSafe priority | TMAP `searchOption` | 의미 |
|---|---:|---|
| `RECOMMEND` | `0` | 추천 |
| `MAIN_STREET` | `4` | 추천 + 대로 우선 |
| `DISTANCE` | `10` | 최단 |
| `STAIR_AVOID` | `30` | 최단거리 + 계단 제외 |

현재 프론트 기본 요청은 시각장애인 보행 안전을 우선해 `STAIR_AVOID`로 둔다. 오탐/우회가 심하면 `MAIN_STREET` 또는 `RECOMMEND`로 바꿔 비교한다.

## 4. 안내 우선순위

사용자가 듣는 TTS 우선순위는 다음 순서다.

1. 즉시 충돌/접근/경로 차단 위험 경고(`risk`)
2. 음성 명령의 확인·재질문·오류 같은 상호작용 안내(`interaction`)
3. 회전·재탐색·도착과 정상 점자블록 보조 같은 길안내(`navigation`)

녹음 중에는 일반 상호작용·길안내 TTS를 보류한다. 새 위험 경고는 이보다 우선하며 진행 중 녹음과 부분 업로드를 취소할 수 있다.

사용자에게 들리는 위험 경고는 장애물, 보행자에게 접근하는 객체, 경로를 막는 객체/상황 중심으로 제한한다. `낙상 위험`은 현재 MVP에서 별도 사용자 경고 카테고리로 쓰지 않는다.
자동 신고 성공/실패는 기본적으로 말하지 않는다. 사용자가 음성으로 신고를 요청한 경우만 완료/실패를 짧게 말한다.

## 5. 점자블록과 길안내 결합 정책

| detection | 길안내 중 동작 |
|---|---|
| `normal_tactile_block` | TMAP polyline 진행 방향과 camera future ROI가 정렬되고 위험이 없을 때만 근거리 보조 안내 가능 |
| `damaged_tactile_block` | 길안내 TTS로 읽지 않고 조용히 자동 신고. 사용자 TTS 기본 없음. 위험 객체 경고와 별도 처리 |
| `tactile_damage_area` | 사용자 안내용이 아니라 운영/디버그 보조 정보 |
| COCO/general 객체 | 신고하지 않음. 충돌/접근/경로 차단 위험일 때만 경고 |

TMAP은 점자블록 경로망을 제공하지 않는다. 따라서 점자블록 탐지를 출발지부터 목적지까지 이어진 실제 route graph로 사용하지 않고, 현재 TMAP 경로 방향의 약 3~5초 근거리 camera ROI를 보조하는 관측으로만 사용한다. 단일 frame 탐지나 화면 밖 점자블록으로 경로를 만들지 않는다.

## 6. 현재 구현 범위

### Backend

- `GET /navigation/walking/health`
  - 현재 provider와 key 설정 여부를 반환한다.
  - 고정 provider: `tmap_pedestrian`
- `POST /navigation/walking`
  - 앱 전용 `WalkingRouteRequest`를 받아 TMAP 보행자 경로안내 API를 호출한다.
  - TMAP GeoJSON 응답을 `walksafe.walking_route.v1` schema로 정규화한다.
  - TMAP appKey는 서버 env에만 둔다.
  - 제품 설정은 `WALKING_ROUTE_PROVIDER=tmap_pedestrian` 고정이며 장애 시 다른 provider로 전환하지 않는다.

### Frontend

- `useNavigationGuidance`
  - 현재 GPS와 설정된 목적지 좌표로 `/navigation/walking`을 호출한다.
  - 기본 priority는 `STAIR_AVOID`다.
  - 경로 이탈이 연속 샘플로 확인되면 자동 재탐색 코드 경로를 실행할 수 있다.
  - 자동 재탐색은 GPS 정확도, GPS jump, in-flight, cooldown, 길안내 세션 누적 max count gate를 모두 통과해야 한다. 재탐색 성공으로 횟수를 초기화하지 않는다.
  - 경로 이탈은 GPS 정확도를 알 수 없으면 확정하지 않으며, 도착은 정확도 상한을 통과한 연속 2개 샘플로만 확정한다.
  - 응답의 `provider`가 `tmap_pedestrian`이 아니면 현재 제품 계약 위반으로 거부한다.
  - 길안내 상태 카드를 `AssistPanel`에 표시한다.
  - 경로 시작 TTS와 정상 점자블록 follow 안내를 `useRiskFeedback`에 낮은 우선순위 prompt로 넘긴다.
- `useRiskFeedback`
  - 위험 경고가 active일 때는 길안내 prompt를 말하지 않는다.
  - 길안내 prompt는 risk TTS보다 낮은 우선순위로 cooldown을 적용한다.
- `useVoiceCommands`
  - 목적지 설정·변경, 후보 선택, 길안내 시작·중지, 목적지 취소와 다음 안내 질의를 navigation hook callback과 연결한다.
  - 목적지 변경 명령에 장소 slot이 없으면 임의 변경하지 않고 목적지를 다시 묻는다. 취소는 현재 목적지와 활성 길안내를 함께 정리한다.
  - “다음 경로 뭐야?”류 질의는 활성 route의 다음 guide point를 답하며, 활성 경로가 없으면 없다고 안내한다.

## 7. 현재 한계와 다음 결정

- 목적지 이름 검색은 backend `GET /navigation/destinations/search`와 Android UI에 연결되어 있다.
- Web/PWA는 개발·테스트 fallback으로 `NEXT_PUBLIC_WALKSAFE_DESTINATION_LAT/LNG/NAME` 목적지 좌표를 사용할 수 있지만 제품 기본 경로로 간주하지 않는다.
- Android native voice에서 목적지 slot을 검색 후보 선택과 길안내 시작까지 자연스럽게 잇는 UX는 아직 후속이다.
- 목적지 후보는 Android UI에서 동명 후보 이름/주소/거리 표시, 더 보기, 취소를 제공한다.
- `distance_m`이 있는 후보가 `NEXT_PUBLIC_WALKSAFE_DESTINATION_MAX_DISTANCE_M`를 넘으면 자동 선택/길안내를 막는다.
- TMAP 응답은 경로 geometry/요약/구간 설명 중심으로만 쓰고, 사용자 안내 문장은 WalkSafe가 보행 속도/보폭 기반으로 재가공한다.
- 길안내 음성은 거리(m) 단독 표현보다 시간/보폭 기반 표현을 우선한다. 예: “10초 뒤 좌회전 준비”, “약 15보 앞”, “지금 좌회전하세요”.
- 보폭 기본값은 임시 추정값으로 시작하되, 사용자 키/보폭 설정 또는 보행 캘리브레이션으로 개인화할 수 있어야 한다. 개인화 전에는 보폭 안내에 “약”을 붙인다.
- 현재 프론트 기본 보폭은 `NEXT_PUBLIC_WALKSAFE_STEP_LENGTH_M`로 조정할 수 있고, 미설정 시 `0.65m`를 사용한다.
- 보행 속도는 GPS/센서 기반 추정이라 신호 품질, 정지/재출발, 실내/도심 canyon 환경에서 오차가 크다. 따라서 임박 안내는 “지금”처럼 즉시성 중심으로 보정하고, TMAP turn-by-turn 문장 품질은 실폰 검증이 필요하다.
- 길안내 타이밍 검증은 별도 smoke 도구(`scripts/check_navigation_guidance_timing_20260524.py`)를 기준으로 둔다. 이 도구는 실제 TMAP route guide point와 speed/step length 입력으로 “10초 뒤/곧/지금” 안내 샘플을 출력하는 smoke 용도이며, 실폰 현장 검증을 대체하지 않는다.
- 실폰 보정은 필수 후속 작업이다. 보폭 기본값(`NEXT_PUBLIC_WALKSAFE_STEP_LENGTH_M`)과 GPS 기반 속도 추정만으로는 사용자별 보행 속도, 손에 든 폰/목걸이 착용, TalkBack/TTS 지연, 도심 GPS 튐을 충분히 보정할 수 없다. 현장에서는 최소 2~3개 짧은 경로를 걸으며 “준비 안내”, “지금 회전 안내”, 위험 경고와 길안내 TTS 중재 지연을 기록해야 한다.
- TMAP API 데이터 장기 저장은 약관 확인 전까지 하지 않는다. 경로 응답은 요청 시 임시 사용만 한다.

## 2026-05-25 live smoke 결과

- 실행: `python scripts/check_tmap_pedestrian_route_smoke_20260524.py --i-understand-live-tmap --timeout-seconds 12`
- 결과: PASS
- 요약: `distance_m=1090`, `duration_s=954`, `polyline_points=32`, `steps=14`, 첫 안내 `보행자도로, 63m`
- 주의: live 호출은 provider quota/약관 영향을 받을 수 있으므로 기본 실행은 dry-run이며, 실제 호출은 `--i-understand-live-tmap` 플래그가 필요하다. API key 값은 출력하지 않는다.
- 경계: 이 historical 단일 호출은 현재 후보의 API 계약·quota·Android 실기기·현장·출시 검증을 대신하지 않으며 해당 상태는 `UNKNOWN` 또는 `NOT_RUN`이다.

## 8. 다음 gap

실폰 없이 가능한 검증/문서화부터 진행한다. 실폰 경로 보정은 이 목록의 완료 조건에 넣지 않는다.

1. Stage1 image smoke
   - unified 13-class 후보 또는 legacy reviewed YOLO26s Stage1 후보 이미지 3~5장을 `/detect/v2`에 통과시켜 `damaged_tactile_block`/일반 객체 응답과 지연시간을 확인한다.
   - damage-positive 후보는 실제 payload의 `model_key=unified_walksafe`, `class_name=damaged_tactile_block`, `threshold_used`, bbox, confidence를 smoke 기준으로 기록한다. legacy fallback smoke에서는 `model_key=custom_tactile`도 허용한다.
   - 데모용 fake-v2가 아니라 `server-v2` 실제 provider 기준으로 확인하되, 실폰 field 성능 근거로 쓰지 않는다.
2. 실제 Stage1 detection payload → report → export trace
   - 손상 점자블록 감지 1건이 실제 Stage1 `/detect/v2` payload에서 `/reports/v2` 자동 신고 저장으로 이어지고, 같은 건이 `/reports/export` CSV/JSON/GeoJSON에 필요한 운영 필드와 함께 나타나는지 trace를 남긴다.
   - 일반 객체와 `tactile_damage_area`가 report로 저장되지 않는 것도 함께 확인한다.
3. Admin export URL 회귀
   - Admin export 버튼/링크가 현재 필터 조건을 보존한 `/reports/export` URL을 생성하고 CSV 기본 다운로드가 깨지지 않는지 확인한다.
   - JSON/GeoJSON 옵션을 추가해도 기존 필터 query와 CSV URL 회귀가 없도록 검증 기준에 포함한다.
4. Admin GeoJSON export UI
   - `/reports/export?format=geojson` backend를 운영자가 UI에서 선택/다운로드할 수 있는 최소 옵션을 추가한다.
   - 운영자가 export된 신고 위치와 길안내 route context를 함께 볼 수 있는 최소 필드 기준을 정한다. 단, TMAP 원본 응답 장기 저장은 약관 확인 전까지 하지 않는다.
5. Offline timing fixture
   - 실제 TMAP 호출 없이 고정 route guide point fixture로 “10초 뒤/곧/지금” 안내 경계값을 재현한다.
   - 온라인 TMAP provider smoke와 실폰 보정은 별도 후속 검증으로 분리한다.

## 9. 환경 변수

Backend secret:

```env
WALKING_ROUTE_PROVIDER=tmap_pedestrian
TMAP_APP_KEY=
TMAP_PEDESTRIAN_ROUTE_URL=https://apis.openapi.sk.com/tmap/routes/pedestrian
TMAP_PEDESTRIAN_API_VERSION=1
TMAP_TIMEOUT_SECONDS=4.0
TMAP_PEDESTRIAN_SPEED_KMH=4.0
```

Frontend test destination:

```env
NEXT_PUBLIC_WALKSAFE_DESTINATION_LAT=
NEXT_PUBLIC_WALKSAFE_DESTINATION_LNG=
NEXT_PUBLIC_WALKSAFE_DESTINATION_NAME=
NEXT_PUBLIC_WALKSAFE_STEP_LENGTH_M=0.65
NEXT_PUBLIC_WALKSAFE_DESTINATION_MAX_DISTANCE_M=30000
NEXT_PUBLIC_WALKSAFE_DESTINATION_SEARCH_DEBOUNCE_MS=250
NEXT_PUBLIC_WALKSAFE_AUTO_REROUTE_COOLDOWN_MS=30000
NEXT_PUBLIC_WALKSAFE_AUTO_REROUTE_MAX_COUNT=2
NEXT_PUBLIC_WALKSAFE_REROUTE_MAX_ACCURACY_M=35
NEXT_PUBLIC_WALKSAFE_REROUTE_MAX_GPS_JUMP_M=50
NEXT_PUBLIC_WALKSAFE_ARRIVAL_RADIUS_M=8
NEXT_PUBLIC_WALKSAFE_ARRIVAL_MAX_ACCURACY_M=20
```

주의: `NEXT_PUBLIC_*` 값은 브라우저 번들에 포함된다. API key는 절대 `NEXT_PUBLIC_*`에 넣지 않는다.

## 10. 검증

```bash
PYTHONPYCACHEPREFIX=/tmp/hanium-tmap-pycache python3 -m py_compile \
  backend/app/schemas.py \
  backend/app/config.py \
  backend/app/services/tmap_pedestrian.py \
  backend/app/api/navigation.py \
  backend/app/main.py \
  backend/tests/test_navigation_routes.py

PATH="$PWD/.venv/bin:$PATH" PYTHONPATH=. python3 -m pytest backend/tests/test_navigation_routes.py -q

# 기본은 외부 호출 없는 dry-run
python3 scripts/check_tmap_pedestrian_route_smoke_20260524.py

# TMAP_APP_KEY를 backend/.env에 넣고 quota/약관 승인 후 실제 provider smoke 1회
python3 scripts/check_tmap_pedestrian_route_smoke_20260524.py --i-understand-live-tmap --timeout-seconds 12

# 실제 TMAP route guide point 기반 길안내 타이밍 smoke
python3 scripts/check_navigation_guidance_timing_20260524.py --timeout-seconds 12

# TODO: offline timing fixture는 고정 route guide point로 별도 smoke를 추가한다.
```

## W9 현행 호출·endpoint 계약

| 단계 | 현행 endpoint·설정 | 책임·데이터 경계 | 상태 |
|---|---|---|---|
| Android → gateway 목적지 검색 | `/api/navigation/destinations/search` | 검색어와 위치를 최소 요청으로 전달하며 query 포함 access log를 남기지 않음 | 배포 `NOT_RUN` |
| Android → gateway 보행 경로 | `/api/navigation/walking` | 출발·목적·선택된 경로 우선순위를 전달 | 배포 `NOT_RUN` |
| backend → TMAP 검색 | `TMAP_POI_SEARCH_URL`로 통제되는 search endpoint | app key는 backend secret store에만 두고 문서·응답·로그에 기록하지 않음 | live 검증 `NOT_RUN` |
| backend → TMAP 보행 경로 | `TMAP_PEDESTRIAN_ROUTE_URL=https://apis.openapi.sk.com/tmap/routes/pedestrian` | route request/response를 `walksafe.walking_route.v1`로 정규화 | live 검증 `NOT_RUN` |

provider 호출 deadline은 `TMAP_TIMEOUT_SECONDS=4.0`초다. gateway나 Android의 별도 request deadline이 이 값을 무제한으로 늘리면 안 된다. endpoint 식별자는 계약 필드이고 credential 값은 아니다.

## 오류 taxonomy와 bounded retry

| 오류 class | 예 | 내부 판정·사용자 동작 | 자동 retry |
|---|---|---|---:|
| `CONFIGURATION_UNAVAILABLE` | provider 설정·app key 없음 | 새 검색·경로 시작 차단, 설정 원문 없이 기능 사용 불가 안내 | 0 |
| `AUTH_OR_CONTRACT_REJECTED` | provider 401/403·계약 거부 | 의존성 차단으로 escalation, 다른 provider로 자동 전환 금지 | 0 |
| `QUOTA_OR_RATE_LIMITED` | provider 429·quota 차단 | quota 상태 기록, 현재 방향안내 중지, 사용자가 나중에 다시 요청 | 0 |
| `PROVIDER_TIMEOUT` | 4초 deadline 초과 | 요청 취소, 오래된 회전안내 재사용 금지 | 0 |
| `PROVIDER_UNAVAILABLE` | network·5xx | 실패를 정상 빈 경로로 바꾸지 않고 offline/fallback 안내 | 0 |
| `INVALID_OR_OVERSIZED_RESPONSE` | schema·geometry·크기 제한 위반 | 응답 폐기, 마지막 성공 경로를 새 경로처럼 표시 금지 | 0 |
| `NO_ROUTE_OR_NO_RESULT` | 정상 응답이지만 후보·경로 없음 | 후보 없음 또는 경로 없음 안내, 임의 목적지·경로 생성 금지 | 0 |

자동 provider retry budget은 0이다. 사용자가 `새 경로 요청`을 다시 선택한 경우에만 새 요청 1건을 시작한다. provider `Retry-After`가 있더라도 사용자에게 무한 재시도를 예약하지 않는다.

현재 세션의 마지막 정상 경로는 세션 종료 또는 24시간 중 먼저 도달할 때까지 제한 cache로 둘 수 있다. cache는 현재 위치 비교와 실패 설명에만 쓰고, 이탈 의심·위치 신뢰 저하·만료 뒤에는 이전 회전안내를 계속 말하지 않는다. `stale route`를 정상 경로로 승격하거나 다른 provider 응답으로 위장하지 않는다.

## quota·비용·책임

| 항목 | 현재 값 | owner | review due |
|---|---|---|---|
| 계약 quota·burst·일일 한도 | `NOT_ESTABLISHED` | `OPERATIONS_OWNER_ROLE` | `BEFORE_LIVE_TMAP_OR_NAMED_RELEASE` |
| quota 비용·초과 정책 | `NOT_ESTABLISHED` | `PROJECT_OWNER_ROLE` | `BEFORE_LIVE_TMAP_OR_NAMED_RELEASE` |
| provider status·지원 연락 계약 | `NOT_ESTABLISHED` | `OPERATIONS_OWNER_ROLE` | `BEFORE_LIVE_TMAP_OR_NAMED_RELEASE` |
| quota 측정·차단 시험 | `NOT_RUN` | `QA_OWNER_ROLE` | `BEFORE_LIVE_TMAP_OR_NAMED_RELEASE` |

`NOT_ESTABLISHED`는 무제한이나 무료라는 뜻이 아니다. 가짜 quota, SLA, 계약 ID나 provider receipt를 만들지 않는다.

## offline·fallback·사용자 고지

1. network 또는 TMAP이 없으면 새 목적지 검색과 새 경로 생성을 시작하지 않는다.
2. 저장 경로가 있더라도 위치 정확도·연속 관측·경로 거리를 다시 확인하지 못하면 회전·도착 안내를 중지한다.
3. GPS를 보폭, camera나 과거 경로로 대체해 현재 위치를 꾸며 내지 않는다.
4. 사용자에게 `길안내를 현재 사용할 수 없음`, `안전한 장소에서 연결과 위치를 확인`, `길안내 종료`를 짧고 접근 가능하게 제공한다.
5. 온디바이스 위험 안내는 길안내와 독립된 입력·모델·TTS 상태가 유효할 때만 유지한다. 독립 안전성이 확인되지 않으면 전체 보행기능을 안전정지한다.
6. 다른 지도 provider, Web/PWA 또는 unsigned APK를 fallback으로 사용하지 않는다.

## monitoring·escalation·provider exit

운영 telemetry는 request count, latency, normalized error class, timeout, quota 차단, 마지막 성공 시각과 cache 만료만 기록한다. 검색어, 정확 좌표, route 원문, app key와 credential은 기록하지 않는다.

| trigger | 조치 | escalation |
|---|---|---|
| 연속 timeout·5xx | 새 경로 요청 중지, stale 안내 차단 | `OPERATIONS_OWNER_ROLE`와 `SAFETY_OWNER_ROLE` |
| 401/403·설정 오류 | provider 기능 차단, secret 원문 없는 구성 점검 | `SECURITY_OWNER_ROLE`와 `OPERATIONS_OWNER_ROLE` |
| 429·quota 차단 | quota 상태 기록, 신규 요청 보류 | `OPERATIONS_OWNER_ROLE`와 `PROJECT_OWNER_ROLE` |
| schema·약관 변경 | 응답 거부, 호환성·데이터 경계 재검토 | `TECHNICAL_OWNER_ROLE`, `PRIVACY_OWNER_ROLE` |

provider exit에는 대체 provider의 계약·데이터 처리, endpoint·quota, schema mapping, 경로 의미, 오류 taxonomy, offline 동작과 안전시험이 필요하다. 현재 alternate provider는 승인·설정되지 않았고 자동 전환하지 않는다. live TMAP, quota, deployment, provider exit와 alternate validation은 모두 `NOT_RUN`이며 release 상태는 `NOT_ELIGIBLE`이다.

```bash
# frontend 안내 타이밍 경계값 정책 smoke
bash scripts/check_frontend_navigation_guidance_policy_20260524.sh
bash scripts/check_frontend_navigation_destination_policy_20260525.sh
bash scripts/check_frontend_route_progress_policy_20260525.sh

cd apps/web && npm run typecheck
```
