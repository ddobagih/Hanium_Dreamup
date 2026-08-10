# WalkSafe 자율 실행 로드맵 20260802 R007

## 0. 지위와 frozen base

```text
document_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R007
document_class = INTERNAL_EXECUTION_ROADMAP_ADD_ONLY_CORRIGENDUM
review_status = PENDING_TWO_INTERNAL_REVIEWS
current_executable_scope = WP001_BOOTSTRAP_DRAFT_AUTHORING_ONLY
draft_source_execution_allowed = false
network_allowed = false
live_product_or_canonical_mutation_allowed = false
official_progress_delta = 0
artifact_completion_credit_delta = 0
release_claim = NOT_ELIGIBLE
```

R007의 frozen normative base는 다음 세 파일이다.

| input | SHA-256 | bytes | lines |
|---|---|---:|---:|
| R006 roadmap | `bc505ad98dc6e6cf62b55b7e0ef8d9d976a0875c09287613e3a1b25f75de2e06` | 10274 | 204 |
| R006 structural review | `8b90befdfe402c215d71f7dd08e53ef8da07d8213a0ce56d07e6e8e263797221` | 3557 | 63 |
| R006 skeptical review | `1fc729225e719d8b94c100841eb399f1e017f32362fc184cf251854787cb6e42` | 3425 | 59 |

R006 structural review는 `REVISION_REQUIRED 1B/0M/0m`, skeptical review는
`REVISION_REQUIRED 0B/0M/1m`이다. 두 review는 같은 재현 가능한 결함 하나만 지적했다.
R006 §3의 E2 행은 exact source-review filename이 있는 §6 대신 §8을 참조한다. R006의
세 one-shot root에는 아무것도 만들지 않는다.

R006이 상속한 R005 본문과 R006 §0~§8은 아래 exact replacement를 적용한 뒤 모두
R007의 normative body다. 충돌하면 R007이 우선한다. 이 문서가 명시하지 않은 계약,
위협 모델, schema, command, acceptance oracle은 새로 해석하거나 바꾸지 않는다.

## 1. 권한과 path replacement

R006 §1을 상속하되 전이식의 `TWO_R006_REVIEWS_ZERO`를
`TWO_R007_REVIEWS_ZERO`로 교체한다. R006과 두 R006 review는 rejected history이며
실행 권한을 만들지 않는다. E1 직전 live goal과 사용자 취소·대체 부재를 다시
확인한다.

R006 §2를 다음으로 교체한다.

```text
draft_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r007-r001
final_candidate_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r007-r001
evidence_root = /home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r007-r001
```

R007 review 전 세 path와 leaf는 모두 absent여야 한다. E1은 draft/evidence만 만들고
final은 absent로 남긴다. R005/R006의 ancestor/openat/mkdirat, no-resume,
mode/owner/type/nlink, confined apply-patch, fd-bound bwrap, backup/runtime,
branch/status, tar restore와 negative oracle은 그대로다.

## 2. exact epoch replacement

R006 §3을 다음 표로 전부 교체한다.

| Epoch | exact write set |
|---|---|
| `E0_PLAN_AUTHOR` | `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R007.md` 하나 |
| `E0_PLAN_REVIEW` | §6의 R007 review 두 파일만; frozen R007 write 0 |
| `E1_DRAFT_AUTHOR` | §1 draft root의 exact 9개와 §1 evidence root의 R006 §4 exact 4개만 |
| `E2_SOURCE_REVIEW_LOG` | `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R007-R001-independent-boundary-review-r001.md`, `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R007-R001-independent-recovery-review-r001.md`, `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715/daylog/2026-08-02.md`만 |
| `E_FAIL_SUCCESSOR` | finding이 있으면 `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R008.md` 하나 |

R007 review가 어느 severity든 nonzero면 E1/E2 source path write는 0이다.
local-memory 기록은 E2 뒤 별도 automation epoch다.

## 3. evidence identity replacement

R006 §4의 exact four-file protocol, creation order, canonical bytes와 crash boundary를
모두 상속한다. 단, §4.1 `authorization-gate.json`의 plan target과 review identity는
R007 및 §6의 두 R007 review이고 R006 identity는 rejected history input이다.

R006 §5의 첫 acceptance 항목은 다음으로 교체한다.

- live authority와 §6의 R007 review 두 개가 `PASS 0/0/0`

R006 §5의 나머지 acceptance 논리곱과 세 negative crash fixture는 그대로다.
`E1.COMPLETE`는 여전히 자기 SHA, 자기 생성 invocation 결과 또는 미래 관찰을 주장하지
않는다.

## 4. defect closure

R006 두 review의 유일한 공통 결함을 다음처럼 닫는다.

| review finding | R007 closure |
|---|---|
| structural B-01 / skeptical m-01 | E2 exact write set이 source-review exact filenames를 열거한 이 문서 §5를 참조한다. |

다른 R006 문구나 계약은 바꾸지 않는다. 특히 terminal marker, read-only recovery,
no-repair/no-resume, source command 0, network 0, live repository/backup/canonical/product
write 0은 그대로다.

## 5. source input과 source-review filenames

R006 §6을 다음으로 교체한다. `source-input-manifest.json`은 R007과 §6의 두 R007
plan review를 current input으로 결속한다. R006, 두 R006 review, R005와 두 R005 review는
rejected/history input으로 유지한다. 미래 draft seal, E1 evidence, source review,
publication/projection output은 input으로 넣지 않는다.

source review exact files는 다음 두 개다.

- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R007-R001-independent-boundary-review-r001.md`
- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R007-R001-independent-recovery-review-r001.md`

두 source review가 모두 `PASS 0/0/0`이어도 별도 publication/projection plan과 그 독립
review 전에는 draft source를 실행하지 않는다.

## 6. R007 independent review gate

서로 다른 새 agent 두 명이 frozen R007의 같은 SHA/bytes/lines를 서로의 결과 없이
검수한다. exact files는 다음이다.

- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R007-independent-structural-review-r001.md`
- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R007-independent-skeptical-review-r001.md`

각 file은 review_id/type, reviewer_agent/session, independence_attestation,
target_sha256/bytes/lines, verdict, blocking/major/minor를 정확히 한 번 둔다. root는
orchestration completion으로 reviewer가 실제로 다른지 확인한다. 어느 severity든
nonzero면 E1은 0이고 §2의 E_FAIL만 수행한다. 둘 다 `PASS 0/0/0`이고 §1 authority도
true일 때만 E1을 수행한다.

## 7. next action

다음 단일 행동은 R007을 freeze하고 서로 다른 두 새 agent에게 R005/R006 frozen chain과
이 delta를 함께 독립 review시키는 것이다. finding이 하나라도 있으면 R007의 세 root를
모두 absent로 유지한다.
