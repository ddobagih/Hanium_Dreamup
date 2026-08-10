# r022 R002 적용 승인 runbook

- 작성일: 2026-07-30
- 상태: `PREPARED_REVIEWED_NOT_APPROVED_NOT_APPLIED`
- 후보: `WS-WALKSAFE-R022-GAP-BACKLOG-CANDIDATE-20260730-R002`
- 현재 정본: checkpoint sequence 39, Gap/Backlog r021, control v2.4
- 다음 행동:
  `BUILD_AND_REVIEW_NON_EFFECTIVE_V2_5_SUCCESSOR_CONTROL_CANDIDATE`

이 문서는 승인 요청의 범위와 검증 순서를 고정한다. 그 자체는 사용자 승인,
canonical 적용, checkpoint/Goal 전이, FP-008 materialization 또는 제품 구현
권한이 아니다.

## 1. 검토 완료 입력

| 역할 | SHA-256 | bytes |
|---|---|---:|
| exact68 ledger | `82ecdfbfc9a35d39f1f8648971df62c18faf861745ccc9a28f5b24d8d354493f` | 189,900 |
| Gap r022 candidate | `3dee2cccad7fb264077dc8858ac3940821af6b4cda0e7664c3e8809b69dc8595` | 535,938 |
| Backlog r022 candidate | `8e2c9cc565ffdd1039f7e03fbfefed348e568d8b62a7750ff06f989a0b20f098` | 58,007 |
| pair manifest | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| independent review | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |

- findings: `BLOCKING=0 MAJOR=0 MINOR=0`
- exact68: changed31 / carry37 / status change8
- 상태: `B5/C14/E4/M6/P39/I0`
- 다음 후보: `EPIC-03-FP008-ADMIN-REVIEW-DELIVERY`
- FP-008 상태: `PLANNED_NEXT_NOT_MATERIALIZED`

## 2. 분리 판정

- content:
  `V2_4_CONTENT_SCOPE_COMPATIBLE_CHANGED31_CARRY37`
- full-pair application:
  `VERSIONED_SUCCESSOR_CONTROL_REQUIRED_FOR_OPERATIONAL_DELTA`

assessment content는 v2.4 successor scope와 호환되지만, Backlog 운영 delta와
role-global normalization을 producer-less transaction으로 적용할 v2.4 경로가
없다. 따라서 v2.4 checker나 sequence 39 checkpoint를 제자리 수정하지 않는다.

## 3. 승인 전 계속할 수 있는 준비

별도 효력이 없는 v2.5 successor-control 후보, builder, positive/negative fixture,
checker와 독립검수 receipt는 add-only candidate 경로에서 준비할 수 있다.

이 단계에서는 다음을 하지 않는다.

- r022 canonical 파일 생성
- active v2.4 manifest/checker/checkpoint/history 변경
- Goal/event append 또는 FP-008 materialization
- 제품 코드·formal·실기기·외부 event 실행
- artifact·gate·release credit 증가

## 4. 이후 사용자 승인 1

v2.5 후보와 독립검수 findings 0이 준비된 뒤 다음 exact 묶음을 사용자에게 다시
제시하고 승인받는다.

1. v2.5 predecessor·activation plan hash
2. 이 R002 4개 입력과 review hash
3. r021 before / r022 after canonical pair
4. event·checkpoint after-projection
5. 허용 delta와 금지 delta
6. positive/negative checker 결과
7. 실패 시 비활성 후보 격리 절차

승인 1은 검토된 v2.5 activation과 r022 canonical application transaction에만
적용한다. 다른 해시·범위·제품 작업에 재사용하지 않는다.

## 5. 이후 사용자 승인 2

승인 1 적용·검증 뒤 ready frontier를 다시 계산한다. FP-008이 여전히 유일한
다음 leaf이면 그 exact Goal/work-item, dirty path binding과 full start gate를
제시해 별도 승인을 받는다. 그 전에는 Goal materialize/start와 제품 코드를
변경하지 않는다.

## 6. 현재 검사 해석

R002 생성 전 sequence 39 v2.4 quick check는 PASS였다. R002와 준비문서를
add-only로 만든 현재 live working snapshot에서는 frozen sequence 39 snapshot과
달라 다음 두 검사가 예상대로 FAIL한다.

- continuation: checkpoint projection / working snapshot content-set drift
- Goal graph: 위 continuation drift와 queue projection drift

checkpoint·r021 물리 hash는 불변이다. 이 예상 drift를 checkpoint 갱신 권한이나
과거 history 실패로 해석하지 않는다.

현재 상태는
`WAIT_FOR_NON_EFFECTIVE_V2_5_CANDIDATE_AND_FINDINGS_ZERO`이다.
