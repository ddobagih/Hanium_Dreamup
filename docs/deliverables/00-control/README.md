# WalkSafe 257개 산출물 통제 현황

> **현행 상태는 [DOC-01 현행 상태 안내 R001](artifact-register-current-notice-20260728-r001.md)에서 먼저 확인합니다.** 이 README와 기존 HTML의 129개 승인 표시는 2026-07-22 `PRE_READY25_APPROVAL_SNAPSHOT_ONLY`이며 scope45·Ready25 현행 개정의 승인이나 완료를 뜻하지 않습니다.

아래 `Approved/Baselined 102개, Active 27개, Draft pending 53개, Planned/NOT_RUN 75개` 표는 승인 당시 기준선 설명입니다. 구현·시험·배포·운영·인수·종료 완료를 뜻하지 않습니다.

## 먼저 볼 파일

- [DOC-01 현행 상태 안내 R001](artifact-register-current-notice-20260728-r001.md): current 정본 선택과 과대해석 금지 경계
- [DOC-01 JSON](artifact-register.json): self-digest와 current authority 조건을 만족할 때의 기계용 정본
- [레거시 관리대장 HTML](artifact-register.html): 2026-07-22 승인 당시 `PRE_READY25_APPROVAL_SNAPSHOT_ONLY` 사람용 화면
- [DOC-05 JSON](artifact-change-log.json): 변경 사건과 승인 적용 기록
- [문서 통제 규정](document-control-manual.md): 명명·검토·승인·버전 규칙
- [정책 1.0.1 manifest](../../control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json): 정책 1.0.0과 정확한 FP-035 오버레이의 합성 기준선
- [불변 승인 기록](../../control/baselines/walksafe-artifact-baseline-approval-20260722-r001.json): 사용자의 정확한 승인문과 승인 범위
- [COMMITTED receipt](../../control/baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json): 이번 상태 전환의 단일 효력 표지

## 상태별 의미

- `APPROVED_BASELINED` 102개: 승인 당시 내용과 SHA-256을 버전 1.0.0 기준선으로 고정했습니다.
- `ACTIVE` 27개: 승인된 최초 snapshot을 보존하면서 사건 발생 때 계속 갱신합니다.
- `DRAFT` 53개: 외부값·실행근거가 없어 승인하지 않았습니다.
- `PLANNED` 75개: 실제 시험·배포·서명·운영·종료를 실행하지 않아 `NOT_RUN`입니다.

| 범주 | 전체 | Baselined | Active | Draft pending | Planned/NOT_RUN |
|---|---:|---:|---:|---:|---:|
| DOC | 5 | 3 | 2 | 0 | 0 |
| MGT | 18 | 12 | 6 | 0 | 0 |
| DSC | 15 | 7 | 3 | 2 | 3 |
| REQ | 19 | 16 | 3 | 0 | 0 |
| DES | 27 | 19 | 1 | 7 | 0 |
| DEV | 21 | 6 | 1 | 9 | 5 |
| TST | 23 | 3 | 5 | 0 | 15 |
| SEC | 19 | 9 | 3 | 2 | 5 |
| AIML | 26 | 5 | 0 | 9 | 12 |
| REL | 22 | 5 | 0 | 6 | 11 |
| OPS | 24 | 9 | 3 | 9 | 3 |
| WS | 22 | 8 | 0 | 2 | 12 |
| CLS | 16 | 0 | 0 | 7 | 9 |

## 변하지 않은 안전 경계

- 남은 gate 5개: 모두 `NOT_RUN`, 면제 없음
- 출시 상태: `NOT_ELIGIBLE`
- FP-035 시험 4개: 정책 승인 대기만 해소됐고 실제 시험은 여전히 `NOT_RUN`
- Web/PWA: 재승인 전 레거시 참고 범위이며 WS-16은 비활성
- 기존 정책 1.0.0, 이전 미승인 후보와 승인 당시 66개 파일 바이트는 덮어쓰지 않습니다.

관리대장 내용 지문: `cbbd0225432e559ba09cef1c9c3fec1b7777268e7ad00dbde1e94a8111f52ceb`
