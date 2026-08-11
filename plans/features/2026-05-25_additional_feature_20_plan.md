# 2026-05-25 추가 기능 20개 세부 구현 계획

## 기준

- 실폰/Android/TalkBack/목걸이 착용 검증은 이 계획의 완료 조건에서 제외한다.
- 외부 API live 호출, 배포, 삭제, 비용 발생, secret 노출은 별도 승인 없이는 하지 않는다.
- 구현은 `mock/local/static`으로 먼저 닫고, `live/field`는 분리한다.
- 사용자는 장소 이름을 말하고, 앱은 내부적으로 좌표 변환 후 길안내를 요청한다.

## 팀 구성

| 팀 | 담당 범위 | 이번 안전 slice |
|---|---|---|
| Navigation 팀 | 목적지 검색, 후보 선택, 경로 상태, 이탈/재탐색, 보폭 | TMAP POI 검색 계약/파서/프론트 client, off-route 순수 함수 계획 |
| Risk 팀 | 위험 안내 문구, 방향/거리/행동, ROI/접근 판단 | bbox 방향/행동/보폭 문구 helper와 fixture 테스트 |
| Ops/Admin 팀 | detect→report→export, GeoJSON, fake/demo 분리, workflow | trace/GeoJSON 현황 정리, fake 분리 UI/API 첫 slice |
| Voice/Settings 팀 | 긴급 연락처, 초기 설정 UI, TTS cache smoke, NLU fallback | localStorage 설정 UI, 보폭 연결, TTS HTTP cache smoke 도구 |
| 통합/PM 팀 | 우선순위, 문서, daylog, 검증 | 전체 검증 명령과 남은 live/field 분리 |

## 20개 기능별 세부 계획

### 1. 목적지 이름 → 좌표 검색/geocoding

- 목표: “서울역으로 안내해” 같은 이름을 위도/경도로 변환한다.
- 첫 slice: `GET /navigation/destinations/search?query=...` 계약, TMAP POI 응답 파서, frontend client.
- 완료 기준: 후보 `id/name/address/point/provider`가 mock 응답에서 정규화된다.
- 검증: `backend/tests/test_navigation_routes.py`, `apps/web` typecheck.
- 막힘: 실제 검색 품질 확인은 TMAP live/quota 필요.

### 2. TMAP 검색 후보 선택

- 목표: 동명이인 장소가 여러 개일 때 안전하게 후보를 고르게 한다.
- 첫 slice: 후보 0/1/N 정책 함수와 후보 목록 타입.
- 진행 상태: 완료. `navigation-destination.ts`, 후보 선택 UI, “1번/두 번째 선택” 음성 명령을 추가했다.
- 완료 기준: 0개는 재질문, 1개는 선택 가능, N개는 자동 길안내 금지.
- 검증: `scripts/check_frontend_navigation_destination_policy_20260525.sh`, `apps/web` typecheck/lint.
- 막힘: 실제 후보 품질은 live 검색 필요.

### 3. 목적지 설정 후 길안내 자동 시작 정책

- 목표: “안내해/가자”처럼 사용자가 명시한 경우만 경로 요청으로 이어간다.
- 첫 slice: intent/slot 정책 문서화와 상태 메시지 구분.
- 진행 상태: 완료. 목적지 검색/선택은 저장만 하고, `start_navigation` 또는 시작 버튼 흐름에서만 route 요청한다.
- 완료 기준: 단순 “목적지 저장”은 저장만, “안내해” 계열은 별도 시작 가능 상태.
- 검증: voice intent test, navigation policy smoke.
- 주의: 자동 route 요청은 TMAP quota에 영향이 있어 live 호출과 분리.

### 4. 경로 이탈 감지

- 목표: 현재 GPS가 경로에서 벗어났는지 보수적으로 판단한다.
- 첫 slice: route projection 함수가 `distanceToRouteM`를 반환.
- 진행 상태: 완료. `route-progress.ts`에서 `distanceToRouteM`, `distanceFromStartM`, `progressRatio`, `on_route/off_route_candidate/off_route/unknown`을 산출한다.
- 완료 기준: fixture에서 경로 위/근처/이탈 케이스가 분리된다.
- 검증: `scripts/check_frontend_route_progress_policy_20260525.sh`, navigation policy smoke.
- 막힘: 실제 GPS 튐 보정은 field가 필요하므로 제외.

### 5. 재탐색 상태/음성 안내

- 목표: 경로 이탈 시 “경로를 벗어났습니다. 다시 재탐색합니다.”를 안내한다.
- 첫 slice: `off_route_candidate/rerouting` 상태와 TTS prompt만 추가, 실제 재요청은 분리.
- 진행 상태: 완료. 경로 이탈 확정 시 `rerouting` 상태/음성 prompt를 만들고, GPS 정확도가 나쁘거나 단일 이탈 샘플이면 후보 상태로 보류한다. 실제 route 재요청은 명시적 `startNavigation`/음성 재탐색 명령 시점으로 제한한다.
- 추가 gate: `scripts/check_navigation_reroute_gate_20260525.py`로 `fetchWalkingRoute`가 명시적 `startNavigation` 밖에서 호출되지 않는지, in-flight/cooldown guard가 route 요청 전에 실행되는지 확인한다.
- 완료 기준: 위험 active이면 재탐색 안내도 위험 안내보다 낮은 우선순위.
- 검증: voice priority policy test, reroute gate script.
- 막힘: 실제 재탐색 live 품질 검증은 TMAP live 호출 필요.

### 6. 보폭 자동 측정

- 목표: “약 N보 앞” 계산의 보폭을 사용자 입력이 아니라 GPS 이동거리와 움직임 센서 step event로 자동 추정한다.
- 첫 slice: GPS 유효 보행거리 + DeviceMotion peak step count 기반 추정값을 길안내 step 계산에 반영하고, 근거 부족 시 기본값 `0.65m`를 쓴다.
- 진행 상태: 완료. 초기 설정 UI에서 수동 보폭 입력을 제거하고 자동 보폭 측정 상태를 표시한다.
- 완료 기준: 충분한 GPS/움직임 근거가 있으면 자동 추정 보폭이 guide prompt step 계산에 반영된다.
- 검증: step length policy test, typecheck.

### 7. 실제 거리/깊이 기반 “약 N보 앞” 위험 안내

- 목표: 위험 객체까지 거리값이 있으면 보폭 문구를 만든다.
- 첫 slice: `distanceM + stepLengthM -> 약 N보 앞` helper.
- 진행 상태: 안전 slice 보강 완료. `detect.v2` 응답/프론트 타입에 optional `distance_m` 계약을 추가했고, 위험 안내 helper/hook은 `stepLengthM` 옵션을 받을 수 있다. 거리값이 명시적으로 있을 때만 “약 N보 앞”을 붙인다.
- 완료 기준: 거리 없음이면 방향/행동만, 거리 있음이면 보수적 보폭 문구 포함.
- 검증: backend detect v2 test, risk guidance fixture test.
- 막힘: 실제 depth 값 생성은 아직 별도 모델/센서 필요. 현재 slice는 bbox 크기 등으로 가짜 거리를 추정하지 않는다.

### 8. 위험 객체 방향 안내

- 목표: bbox 위치로 왼쪽/오른쪽/전방을 짧게 말한다.
- 첫 slice: bbox center x 기준 방향 helper.
- 완료 기준: left/center/right fixture가 각각 문구로 나온다.
- 검증: risk guidance fixture test.

### 9. 위험 행동 안내

- 목표: 위험 유형별로 “멈추세요/피하세요/천천히 이동하세요”를 붙인다.
- 첫 slice: `risk_type/risk_level` 기반 action phrase helper.
- 완료 기준: 접근 중 객체는 멈춤, 경로 차단은 회피/천천히, 표면 위험은 발밑 주의.
- 검증: risk guidance fixture test.

### 10. IMU/ROI 기반 충돌 가능 영역 필터

- 목표: 화면 내 모든 객체가 아니라 진행 방향 ROI에 있는 객체만 우선 경고한다.
- 첫 slice: 센서 없는 fixture ROI polygon/center-band 계산.
- 진행 상태: 완료. bbox center/lower-half 기반 `risk-roi.ts` helper와 evaluator fixture를 추가했다.
- 완료 기준: ROI 안/밖 객체 필터 결과가 재현된다.
- 검증: `scripts/check_frontend_risk_evaluator_policy_20260523.sh`.
- 막힘: 실제 DeviceMotion 로그는 field라 제외.

### 11. bbox 변화 기반 접근 중 객체 판단

- 목표: bbox가 안정적으로 커지는 객체를 접근 중으로 판단한다.
- 현재 상태: `buildBBoxHistoryRiskContext()`가 기본 구현됨.
- 첫 slice: 경계값 fixture 보강, 접근/비접근 회귀 테스트.
- 완료 기준: 3프레임 안정 추적 + 면적 증가에서만 alert.
- 검증: `risk-evaluator-policy.test.ts`.

### 12. Stage1 detector payload → report → export trace

- 목표: 실제 detector payload가 신고 저장과 export까지 이어지는지 검증한다.
- 현재 상태: trace script 초안 존재.
- 첫 slice: payload fixture 옵션과 CSV/JSON/GeoJSON 포함 여부 자동 확인.
- 진행 상태: 보강 완료. DB/ASGI dependency 없이 저장/거부/export 기대 정책을 확인하는 `--dry-run-policy`와, DB 차단을 실패로 처리하는 `--require-db` gate를 추가했다. 현재 로컬 DB에서는 실제 trace도 PASS.
- 완료 기준: `damaged_tactile_block` 저장, `tactile_damage_area/general` 거부.
- 검증: `scripts/check_detect_report_export_trace_20260524.py --dry-run-policy`, DB 환경에서는 `--require-db`.
- 막힘: 다른 환경에서는 disposable DB 접근이 필요할 수 있다.

### 13. Admin GeoJSON export UI

- 목표: 운영자가 GeoJSON을 UI에서 받을 수 있게 한다.
- 현재 상태: Admin에 CSV/JSON/GeoJSON export 링크가 존재한다.
- 첫 slice: 회귀 테스트로 필터 유지와 GeoJSON URL 보장.
- 완료 기준: 현재 필터 query가 export URL에 유지된다.
- 검증: frontend policy test.

### 14. Admin 신고 지도/히트맵/클러스터 뷰

- 목표: 누적 신고 위치를 운영자가 공간적으로 본다.
- 첫 slice: 외부 지도 SDK 없이 좌표 목록/간단 grid summary부터 표시.
- 진행 상태: 완료. Admin에 위치 있음/없음 수와 0.001° 정적 격자 Top cluster 요약을 표시한다.
- 완료 기준: 위치 있는 신고 수, 위치 없는 신고 수, 상위 격자 cluster가 표시된다.
- 검증: `scripts/check_frontend_admin_report_summary_policy_20260525.sh`, typecheck, lint.
- 막힘: 실제 지도 SDK는 외부 API/키가 필요하므로 후순위.

### 15. fake/demo 신고 데이터 운영 분리

- 목표: 데모 데이터가 실제 성능/운영 근거에 섞이지 않게 한다.
- 첫 slice: `review_flags`/source 필터 문구와 admin quick filter.
- 진행 상태: 완료. `source=fake`, `review_flags=fake_source`, fake/demo metadata를 fake/demo로 분류하고 Admin quick filter와 export 주의 문구를 표시한다. `/reports`와 `/reports/export`도 `demo_filter=all|only_fake|exclude_fake`를 지원한다.
- 완료 기준: fake source는 “데모 데이터”로 명확히 표시되고 export/필터에서 분리 가능.
- 검증: admin summary policy test, backend reports v2 test, typecheck, lint.
- 주의: 과거 데이터 정규화/migration은 하지 않았고 기존 JSONB metadata/source/review_flags 기준으로 판정한다.

### 16. 신고 검수 상태 workflow 강화

- 목표: 신규→검토중→처리완료 흐름을 운영자가 명확히 관리한다.
- 현재 상태: 상태 변경 버튼 존재.
- 첫 slice: 상태별 설명/next action 문구, resolved export 필터.
- 완료 기준: 상태 의미와 다음 조치가 UI에 보인다.
- 검증: typecheck.

### 17. 긴급 연락처 설정

- 목표: 보호자/긴급 연락처를 초기 설정에 저장한다.
- 첫 slice: localStorage name/phone 저장과 상태 표시만. 전화/SMS 발신 없음.
- 완료 기준: 새로고침 후 설정값 유지.
- 검증: typecheck.
- 주의: 전화번호는 개인정보라 외부 전송 금지.

### 18. 보호자/초기 설정 최소 UI

- 목표: 보호자와 사용자가 필수 설정을 앱 안에서 바꿀 수 있게 한다.
- 첫 slice: 보폭, 긴급 연락처, 음성 안내 설명을 접이식 설정 카드에 배치.
- 완료 기준: 화면 주시 최소, 큰 입력/버튼, aria-label 제공.
- 검증: typecheck + 수동 DOM 확인.

### 19. TTS HTTP cache header 자동 확인

- 목표: `/speech/tts` 2회 호출에서 두 번째 cache hit와 fallback header를 확인한다.
- 첫 slice: 서버가 없으면 BLOCKED로 종료하는 smoke script.
- 진행 상태: 완료. loopback URL만 허용한다. 사전 생성한 local cache 파일로 voice server를 짧게 띄워 2회 cache header smoke가 PASS임을 확인했다.
- 완료 기준: server reachable이면 `X-Voice-Cached`/`X-Voice-Fallback` 검증.
- 검증: `py_compile`, 서버 실행 시 script.
- 막힘: cache miss에서 실제 TTS 모델 로딩은 로컬 환경/GPU 상태 영향.

### 20. unknown/저신뢰 명령용 NLU fallback

- 목표: rule/fuzzy가 실패할 때만 비용 큰 NLU 후보를 붙일 수 있게 한다.
- 첫 slice: 외부 LLM/API 없는 no-op 확장 지점과 정책 문서.
- 완료 기준: fallback도 `should_execute=false` 기본, slot 검증 전 실행 금지.
- 검증: voice intent policy test.
- 막힘: 실제 LLM/Cloud API는 비용/개인정보/secret 이슈로 별도 승인 필요.

## 우선순위

1. 목적지 검색/geocoding 계약 + 후보 타입
2. 보폭 자동 측정/긴급 연락처 초기 설정 UI
3. 위험 방향/행동/보폭 안내 helper
4. fake/demo 분리와 report/export 회귀
5. TTS HTTP cache smoke
6. off-route/reroute 상태만 먼저, 실제 재탐색 live 호출은 나중
7. 지도/히트맵, NLU fallback 실제 모델/API는 후순위

## 이번 진행에서 제외

- 실폰/Android/TalkBack/목걸이 착용 검증
- 실제 긴급 전화/SMS 발신
- 실제 지도 SDK/외부 지도 화면
- 실제 NLU/LLM API 호출
- 반복 live TMAP 호출 또는 자동 경로 요청 부하 테스트
