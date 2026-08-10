# WalkSafe DOC-01 현행 상태 안내 R001

- 작성일: 2026-07-28
- 상태: CURRENT-STATUS ROUTING NOTICE
- 출시 상태: `NOT_ELIGIBLE`

## 먼저 확인할 경계

`artifact-register.html`은 2026-07-22 승인 당시의 사람용 화면입니다. 이 파일에 표시된 `129개 승인 적용 완료`, `REQUIRED 148`, `CONDITIONAL 109`와 개별 `APPROVED` 표시는 **PRE_READY25_APPROVAL_SNAPSHOT_ONLY**이며, 2026-07-28 scope45 결정이나 Ready25 현행 개정을 표시하지 않습니다.

현행 기계 판정은 다음 조건을 모두 만족한 `artifact-register.json`만 사용합니다.

1. 최상위 `content_sha256`이 현재 JSON projection과 일치한다.
2. `metadata.current_revision_authority`가 `ARTIFACT_REGISTER_JSON_SCOPE45_AND_READY25_CURRENT_FIELDS`다.
3. `scope45_current_scope`가 45건 전체를 `43 IN_SCOPE / 2 current-scope N/A`로 결속한다.
4. Ready25 exact9/13/3의 content-authoring packet과 receipt가 현재 DOC-01/DOC-05에 결속된다.

위 조건이 아직 충족되지 않았다면 current exact257 판정은 `phase1-exact257-successor-ledger-r007.json`과 R010 successor wrapper를 사용합니다.

## 과대해석 금지

- `DLV-DSC-04`, `DLV-WS-16`의 2건 credit은 현재 범위 N/A 종결만 뜻하며 전역 산출물 완료가 아닙니다.
- Ready25 exact25는 내부 content authored 상태만 다룹니다.
- exact25의 content acceptance, owner approval, execution, actual event, formal evidence, release credit은 모두 0입니다.
- 독립 QA는 미지정이며 기존 승인·검증 표시는 Ready25 현행 개정의 승인이 아닙니다.
- 5개 gate는 `NOT_RUN`·미면제이고 출시는 `NOT_ELIGIBLE`입니다.

## 정본 링크

- [DOC-01 JSON](artifact-register.json)
- [DOC-05 변경 이력](artifact-change-log.json)
- [역사 HTML](artifact-register.html) — PRE_READY25_APPROVAL_SNAPSHOT_ONLY
- [scope45 적용 정본](../../control/execution/artifact-closure/run-20260727-001/phase1-scope45-transition-application-r001.json)
- [current R007 ledger](../../control/execution/artifact-closure/run-20260727-001/phase1-exact257-successor-ledger-r007.json)
- [current R010 receipt](../../control/execution/artifact-closure/run-20260727-001/phase1-exact257-successor-check-receipt-r010.json)
