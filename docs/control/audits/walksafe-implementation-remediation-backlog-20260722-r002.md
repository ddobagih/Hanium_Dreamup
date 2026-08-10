# WalkSafe 구현 수정 백로그 r002

- 백로그: `WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260722-002` v0.2.0
- EPIC-01: **IN_PROGRESS**
- 다른 EPIC: `PLANNED` 유지
- 정식 시험·gate·출시 상태: 변경 없음

## Phase B 반영

FP-003의 `GAP-012`는 내부 구현을 근거로 `PARTIAL`이다. 실제 운영 프로비저닝, 휴대전화 밖 암호화 복구자료, 실제 복구훈련은 남아 있다. EPIC-01 전체는 아직 `IMPLEMENTATION_READY`가 아니다.

## 다음 한 가지 작업

`EPIC-01-RUNTIME-METRIC-PREFLIGHT` — 실행 중 실제 미터 거리 frame과 승인된 지정 기기 프로필·비어 있지 않은 프로필 버전을 안전 기능 시작 전에 함께 검사하고, 하나라도 없으면 FULL을 금지한다.

내용 지문: `d3adcbf556e06349bcf834f3647441df82446e79fde633c7fc9f600d789af25c`
