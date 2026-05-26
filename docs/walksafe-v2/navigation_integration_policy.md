# WalkSafe v2 TMAP walking navigation integration policy

- 기준일: 2026-05-25 KST
- 구현 위치: `backend/app/api/navigation.py`, `backend/app/services/tmap_pedestrian.py`, `backend/app/services/kakao_mobility.py`, `apps/web/lib/navigation-api.ts`, `apps/web/app/_walksafe/hooks/useNavigationGuidance.ts`
- 외부 근거: TMAP 보행자 경로안내 공식 문서 <https://tmap-skopenapi.readme.io/reference/%EB%B3%B4%ED%96%89%EC%9E%90-%EA%B2%BD%EB%A1%9C%EC%95%88%EB%82%B4>

## 1. 목적

WalkSafe의 길안내는 지도 서비스를 대체하는 완전한 내비게이션이 아니라, **보행 경로 + 카메라 기반 보행 안전 레이어**를 섞는 기능이다.

- TMAP 보행자 경로안내 API: 출발지/목적지/경유지 기반 보행 경로 geometry, 거리/시간, 보행 구간 정보 제공
- WalkSafe: 실시간 카메라 탐지, 점자블록 보조 안내, 보행 위험 TTS/진동, 손상 점자블록 자동 신고

카카오 Mobility 도보 API는 기능적으로 적합하지만 현재 앱 키로는 권한이 없어 403 `permission denied`가 확인됐다. 따라서 현재 기본 provider는 **TMAP 보행자 경로안내**로 고정한다. Kakao provider 코드는 제휴/권한 확보 시에만 후순위 fallback 후보로 유지하며, 제품 문서에서 Kakao를 우선 provider로 쓰지 않는다.

## 2. 역할 분리

| 영역 | 책임 |
|---|---|
| TMAP provider | 보행 경로, 전체 거리, 예상 시간, GeoJSON LineString/road segment |
| WalkSafe backend | TMAP appKey 보호, provider 응답 정규화, provider 오류를 앱 오류로 변환 |
| WalkSafe frontend | 현재 GPS에서 경로 요청, 길안내 상태 표시, TTS 중재 |
| WalkSafe detector | 정상 점자블록/손상 점자블록/일반 객체 탐지 |
| Auto report | `damaged_tactile_block`만 조용히 `/reports/v2` 저장 |

## 3. 경로 옵션 정책

WalkSafe route priority는 provider별 옵션으로 변환한다.

| WalkSafe priority | TMAP `searchOption` | 의미 |
|---|---:|---|
| `RECOMMEND` | `0` | 추천 |
| `MAIN_STREET` | `4` | 추천 + 대로 우선 |
| `DISTANCE` | `10` | 최단 |
| `STAIR_AVOID` | `30` | 최단거리 + 계단 제외 |

현재 프론트 기본 요청은 시각장애인 보행 안전을 우선해 `STAIR_AVOID`로 둔다. 오탐/우회가 심하면 `MAIN_STREET` 또는 `RECOMMEND`로 바꿔 비교한다.

## 4. 안내 우선순위

사용자가 듣는 TTS 우선순위는 다음 순서다.

1. 즉시 충돌/접근/경로 차단 위험 경고
2. 길안내 시작/중요 상태
3. 정상 점자블록 감지 시 “점자블록을 따라 이동하세요” 보조 안내
4. 위치/상태 반복 요청
5. 자동 신고 bookkeeping

사용자에게 들리는 위험 경고는 장애물, 보행자에게 접근하는 객체, 경로를 막는 객체/상황 중심으로 제한한다. `낙상 위험`은 현재 MVP에서 별도 사용자 경고 카테고리로 쓰지 않는다.
자동 신고 성공/실패는 기본적으로 말하지 않는다. 사용자가 음성으로 신고를 요청한 경우만 완료/실패를 짧게 말한다.

## 5. 점자블록과 길안내 결합 정책

| detection | 길안내 중 동작 |
|---|---|
| `normal_tactile_block` | 위험이 없을 때 “점자블록을 따라 이동하세요” 보조 안내 가능 |
| `damaged_tactile_block` | 길안내 TTS로 읽지 않고 조용히 자동 신고. 사용자 TTS 기본 없음. 위험 객체 경고와 별도 처리 |
| `tactile_damage_area` | 사용자 안내용이 아니라 운영/디버그 보조 정보 |
| COCO/general 객체 | 신고하지 않음. 충돌/접근/경로 차단 위험일 때만 경고 |

## 6. 현재 구현 범위

### Backend

- `GET /navigation/walking/health`
  - 현재 provider와 key 설정 여부를 반환한다.
  - 기본 provider: `tmap_pedestrian`
- `POST /navigation/walking`
  - 앱 전용 `WalkingRouteRequest`를 받아 TMAP 보행자 경로안내 API를 호출한다.
  - TMAP GeoJSON 응답을 `walksafe.walking_route.v1` schema로 정규화한다.
  - TMAP appKey는 서버 env에만 둔다.
  - `WALKING_ROUTE_PROVIDER=kakao_mobility`는 제휴/권한이 확보된 환경에서만 수동으로 쓰는 fallback 후보다. 현재 기본값이 아니며, Kakao fallback은 `DISTANCE`, `MAIN_STREET` priority만 지원한다.

### Frontend

- `useNavigationGuidance`
  - 현재 GPS와 설정된 목적지 좌표로 `/navigation/walking`을 호출한다.
  - 기본 priority는 `STAIR_AVOID`다.
  - 경로 이탈이 연속 샘플로 확인되면 자동 재탐색 코드 경로를 실행할 수 있다.
  - 자동 재탐색은 GPS 정확도, GPS jump, in-flight, cooldown, route당 max count gate를 모두 통과해야 한다.
  - 길안내 상태 카드를 `AssistPanel`에 표시한다.
  - 경로 시작 TTS와 정상 점자블록 follow 안내를 `useRiskFeedback`에 낮은 우선순위 prompt로 넘긴다.
- `useRiskFeedback`
  - 위험 경고가 active일 때는 길안내 prompt를 말하지 않는다.
  - 길안내 prompt는 risk TTS보다 낮은 우선순위로 cooldown을 적용한다.
- `useVoiceCommands`
  - `set_destination`, `start_navigation` intent를 navigation hook callback과 연결한다.

## 7. 현재 한계와 다음 결정

- 목적지 이름을 좌표로 바꾸는 geocoding/keyword search는 아직 붙이지 않았다.
- 프론트는 임시로 `NEXT_PUBLIC_WALKSAFE_DESTINATION_LAT/LNG/NAME`에 설정된 목적지 좌표를 사용한다.
- 실제 서비스에서는 목적지 검색 UI/음성 slot → 좌표 변환 provider를 추가해야 한다.
- 목적지 후보는 동명 후보 주소/거리 표시, 더 보기, 다시 검색, 취소를 제공한다.
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

## 8. 다음 gap

실폰 없이 가능한 검증/문서화부터 진행한다. 실폰 경로 보정은 이 목록의 완료 조건에 넣지 않는다.

1. Stage1 image smoke
   - reviewed YOLO26s Stage1 후보로 저장된 damage-positive 후보 이미지와 known-negative 이미지 3~5장을 `/detect/v2`에 통과시켜 `damaged_tactile_block`/`tactile_damage_area`/일반 객체 응답과 지연시간을 확인한다.
   - damage-positive 후보는 실제 payload의 `model_key=custom_tactile`, `class_name=damaged_tactile_block`, `threshold_used`, bbox, confidence를 smoke 기준으로 기록한다.
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

Kakao fallback:

```env
WALKING_ROUTE_PROVIDER=kakao_mobility
KAKAO_MOBILITY_REST_API_KEY=
KAKAO_MOBILITY_WALKING_DIRECTIONS_URL=https://apis-navi.kakaomobility.com/affiliate/walking/v1/directions
KAKAO_MOBILITY_SERVICE_NAME=walksafe
KAKAO_MOBILITY_TIMEOUT_SECONDS=4.0
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
```

주의: `NEXT_PUBLIC_*` 값은 브라우저 번들에 포함된다. API key는 절대 `NEXT_PUBLIC_*`에 넣지 않는다.

## 10. 검증

```bash
PYTHONPYCACHEPREFIX=/tmp/hanium-tmap-pycache python3 -m py_compile \
  backend/app/schemas.py \
  backend/app/config.py \
  backend/app/services/tmap_pedestrian.py \
  backend/app/services/kakao_mobility.py \
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

# frontend 안내 타이밍 경계값 정책 smoke
bash scripts/check_frontend_navigation_guidance_policy_20260524.sh
bash scripts/check_frontend_navigation_destination_policy_20260525.sh
bash scripts/check_frontend_route_progress_policy_20260525.sh

cd apps/web && npm run typecheck
```
