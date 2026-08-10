# 2026-05-25 남은 추가 기능 세부 실행 계획

## 범위 기준

- 실폰/Android/TalkBack/목걸이 착용 field 검증은 제외한다.
- 외부 지도 SDK, 배포, secret 출력, 실제 긴급 연락/SMS, 실제 LLM/Cloud NLU 호출은 하지 않는다.
- TMAP live 호출은 사용자가 별도 지시한 smoke 외에는 자동 반복하지 않는다.
- 구현은 `local/static/mock/dry-run`으로 먼저 닫고, live/field/운영 작업은 명확히 gate 뒤에 둔다.

## 팀 구성과 소유 범위

| 팀 | 담당 기능 | 소유 파일/영역 | 이번 목표 | 검증 |
|---|---:|---|---|---|
| Admin/Ops 팀 | #14, #15 | `apps/web/app/admin/page.tsx`, `apps/web/lib/report-api.ts`, admin CSS/test | 외부 지도 없이 위치 cluster summary, fake/demo quick filter/문구 강화 | admin summary policy, typecheck/lint |
| Risk 팀 | #7 | `risk-guidance.ts`, `useRiskFeedback.ts`, risk policy test | 거리값이 있을 때 자동 보폭 기반 “약 N보 앞” 문구, 거리값 없으면 과장 금지 | risk evaluator policy |
| Trace/Voice/Navigation 팀 | #12, #19, #5 | `scripts/check_*`, navigation policy 문서 | DB/TTS/TMAP live 전 안전 gate, dry-run과 BLOCKED 구분 | py_compile, dry-run/BLOCKED smoke |
| 통합/PM 팀 | 전체 | `plans/features`, `daylog` | 작업 현황 통합, 남은 live/field 리스크 분리 | 전체 검증 요약 |

## 기능별 세분화

### #14 Admin 신고 지도/히트맵/클러스터 뷰

1. 위치 통계 helper
   - 입력: report list
   - 출력: `locatedCount`, `missingLocationCount`, `clusters[]`
   - cluster key: 위도/경도 소수 3자리 수준 격자 또는 약 100m 단위 근사
   - 검증: 같은 격자 묶임, 위치 없는 신고 제외
2. Admin UI
   - 상단 summary 카드: 위치 있는 신고/없는 신고/상위 cluster
   - 외부 지도 SDK 금지, 좌표 grid list로 표시
3. 완료 기준
   - 신고 목록만으로 공간 집중 구역을 볼 수 있음
   - 지도 API key 없이 동작

### #15 fake/demo 신고 데이터 운영 분리

1. fake source summary
   - fake/server/onnx count를 명확히 표시
   - fake가 있으면 운영/성능 근거로 쓰지 말라는 경고 표시
2. quick filter
   - fake만 보기
   - fake 제외 보기
3. export 주의
   - fake 포함 상태에서 export 시 주의 문구 표시
4. 완료 기준
   - fake/demo 데이터가 실제 운영 데이터와 UI상 혼동되지 않음

### #12 Stage1 detect payload → report → export trace

1. dry-run policy
   - DB 없이 `damaged_tactile_block` 저장 대상, `tactile_damage_area/general` 거부 대상 확인
2. DB trace gate
   - DB dependency/connection/migration 불가 시 `BLOCKED`로 종료
   - DB 가능 시 detect/report/status/export CSV/JSON/GeoJSON 확인
3. 완료 기준
   - 실패/차단/성공이 명확히 구분됨

### #7 거리/깊이 기반 “약 N보 앞” 위험 안내

1. distance source contract
   - 현재 실제 depth source 없음
   - `distanceM`이 명시적으로 들어온 경우에만 보폭 문구 사용
2. step length 연결
   - hard-coded 평균 보폭 대신 자동 보폭 추정값 옵션 사용
3. 완료 기준
   - 거리 없음: “전방 보행자. 피하세요.” 수준
   - 거리 있음: “전방 약 5보 앞 보행자. 피하세요.” 수준
   - 가짜 거리 생성 금지

### #19 TTS HTTP cache header 자동 확인

1. loopback gate
   - `localhost/127.0.0.1`만 허용
   - 서버 없음은 `BLOCKED`
2. cache 검증
   - 2회 호출
   - 두 번째 `X-Voice-Cached: true`
   - fallback 발생 시 cache 검증은 `BLOCKED`
3. 완료 기준
   - 서버가 켜진 상태에서는 PASS/FAIL, 서버가 없으면 BLOCKED

### #5 실제 재탐색 연결 전 안전 gate

1. 현재 상태
   - 경로 이탈 확정 시 TTS: “경로를 벗어났습니다. 다시 재탐색합니다.”
   - 실제 TMAP 재요청은 아직 자동 연결하지 않음
2. gate script
   - rerouting prompt 존재 확인
   - `fetchWalkingRoute`가 `startNavigation` 외부에서 자동 호출되지 않는지 확인
3. 완료 기준
   - live TMAP 재탐색 연결 전까지 자동 과금/반복 호출 없음

## 오늘 진행 순서

1. Trace/Voice/Navigation gate를 직접 구현한다.
2. Admin/Ops worker가 #14/#15를 구현한다.
3. Risk worker가 #7을 구현한다.
4. 각 worker 결과를 통합하고 충돌을 확인한다.
5. 최소 검증을 실행한다.
6. daylog에 변경 파일/검증/남은 리스크를 기록한다.

## 남은 live/field 리스크

- 실제 재탐색은 TMAP live 호출이므로 별도 승인/쿨다운/중복 방지 없이 자동 연결하지 않는다.
- 실제 depth/거리값은 아직 모델/센서 소스가 없다.
- TTS cache PASS는 voice server가 켜져 있어야 가능하다.
- 실제 실보행/실폰 센서 검증은 사용자가 나중에 한다고 한 범위라 제외한다.
