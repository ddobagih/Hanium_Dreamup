# EPIC-02 Phase A FP-017 보행 세션 생명주기 구현 기록

- 기록 ID: `WS-EPIC-02-PHASE-A-WALK-SESSION-LIFECYCLE-IMPLEMENTATION-20260723-001`
- 버전: `0.1.0`
- EPIC 상태: `IN_PROGRESS`
- GAP-026 판정: `PARTIAL`
- 집중 구현 경로: `13`개
- snapshot 지문: `98d28ae4398aa66ea9579ae8db047225dbc9ebb6b9ad83d4c1b2b19bf1f5e401`

## 구현 경계

- 초기 준비 확인 뒤 보행 시작
- background 진입 즉시 `PAUSED` 및 runtime 차단
- 복귀 뒤 fresh recheck
- `보행 안내를 다시 시작할까요? 시작 또는 취소라고 말해 주세요`의 정확한 `시작` 확인 전 재개 금지
- process 재시작 뒤 이전 `ACTIVE` 보행 자동복원 금지

가입·통합동의·운영 인증, 일반 원본수집 동의, 인증된 Gateway 로그인 readiness,
필수 서버, 저장공간·배터리·발열 aggregate와 실제 기기·정식 시험은 남아 있어
EPIC-02는 `IN_PROGRESS`, GAP-026은 `PARTIAL`이다.

## 출시 경계

- 정식 시험: `0/279`, `279`개 `NOT_RUN`
- 실제 기기: `NOT_RUN`
- 5개 gate: `NOT_RUN`, 미면제
- 출시: `NOT_ELIGIBLE`
- 다음 작업: `EPIC-02-FP018-WALK-STATE-RECOVERY` / `FP-018` / `GAP-027`
