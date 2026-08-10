# EPIC-01 Phase E Active 원장 successor overlay

- overlay: `WS-EPIC-01-PHASE-E-ACTIVE-LEDGER-OVERLAY-20260723-001` v0.1.0
- 상태: `ACTIVE_EVENT_OVERLAY`
- 승인·수명주기 상태 변경: 없음

이 파일은 Phase D overlay를 고치지 않고 그 사건만 predecessor로 이어가는 최소 append-only overlay다. 다음 범용 Active writer에서 중복 없이 canonical 새 revision으로 병합해야 한다.

## Active 사건

- `DOC-01` / `WS-EPIC01-PHASEE-ACTIVE-001` — Phase E 구현기록·r005·backlog r005·overlay의 8개 새 경로와 지문을 다음 정식 Active writer에서 등록한다.
- `DOC-05` / `WS-EPIC01-PHASEE-ACTIVE-002` — Phase D와 승인 정책을 고치지 않고 독립 Gateway 추출, zero allowlist, 8개 Gap 검토 사건을 추가한다.
- `DSC-14` / `WS-EPIC01-PHASEE-ACTIVE-003` — EPIC-01을 IN_PROGRESS로 유지하고 Gateway 추출 뒤 목적 표면 정합화를 다음 한 가지 행동으로 연결한다.
- `REQ-16` / `WS-EPIC01-PHASEE-ACTIVE-004` — FP-007·FP-009 직접 재평가와 FP-011·032·040·042·047·048 영향 확인을 내부 구현·미실행 경계에 역추적한다.
- `DES-06` / `WS-EPIC01-PHASEE-ACTIVE-005` — Node 단일 Gateway, 정확한 4 route, Android 8081, Legacy allowlist 0과 no-fallback 경계를 설계 증거에 연결한다.
- `DEV-15` / `WS-EPIC01-PHASEE-ACTIVE-006` — 41개 통제 경로와 제거된 Next route 4개, 내부 회귀를 기록하되 배포·실기기·정식시험 증거가 아님을 남긴다.
- `SEC-03` / `WS-EPIC01-PHASEE-ACTIVE-007` — 12시간 내부 세션이 장기 refresh를 대체하지 않고 draft TLS 배치가 실제 암호화 배포 증거가 아님을 잔여위험으로 유지한다.
- `TST-19` / `WS-EPIC01-PHASEE-ACTIVE-008` — Gateway·Android·경계 내부 회귀만 비정식 지표로 기록하고 정식 시험 PASS 수는 0으로 유지한다.
- `TST-21` / `WS-EPIC01-PHASEE-ACTIVE-009` — 배포·실기기·외부 URL·캐시 PWA·279개 시험·5개 gate NOT_RUN과 출시 NOT_ELIGIBLE을 유지한다.

`DEV-18`은 **DRAFT 유지**다. Gateway 저장소 추출은 내부 완료지만 배포·실기기·과거 외부 URL·정식 시험은 **NOT_RUN**이다.

정식 시험 279/279 `NOT_RUN`, 5개 gate `NOT_RUN`·미면제, 출시 `NOT_ELIGIBLE`을 유지한다.

내용 지문: `279c6d8e4181d98e72274d9818df17e6c77a8c3099ccee79872b6d169bf40f78`
