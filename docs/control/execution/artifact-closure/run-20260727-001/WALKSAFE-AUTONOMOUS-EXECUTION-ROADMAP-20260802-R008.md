# WalkSafe 자율 실행 로드맵 20260802 R008

## 0. 지위와 frozen base

```text
document_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R008
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

R008의 frozen normative base는 다음 세 파일이다.

| input | SHA-256 | bytes | lines |
|---|---|---:|---:|
| R007 roadmap | `c7a00043d3dc450fc9b1c3e46ca32ce57183d51ebd434bbe6cf7d92fa1a21950` | 6419 | 129 |
| R007 structural review | `d6444deaaa69cf99d8532de54d8ac42d0bc99d69e2256f6676afacff12f3d16b` | 3807 | 65 |
| R007 skeptical review | `149fc146095080be5481a39168bb7b29bead2d43496558f0fb5ef091e0de10be` | 4003 | 61 |

R007 structural review는 `REVISION_REQUIRED 0B/0M/1m`, skeptical review는
`PASS 0B/0M/0m`이다. structural m-01은 실행 allowlist가 아닌 defect-closure 설명이
“§5를 참조한다”고 적은 문구 불일치 하나다. 실제 R007 §2는 이미 exact 두 basename을
직접 열거해 실행 경계는 결정적이었다. R007의 세 one-shot root에는 아무것도 만들지
않는다.

R007이 상속한 R005/R006 본문과 R007 §0~§7은 아래 exact replacement를 적용한 뒤 모두
R008의 normative body다. 충돌하면 R008이 우선한다. 이 문서가 명시하지 않은 계약,
위협 모델, schema, command, acceptance oracle은 새로 해석하거나 바꾸지 않는다.

## 1. 권한과 path replacement

R007 §1을 상속하되 전이식의 `TWO_R007_REVIEWS_ZERO`를
`TWO_R008_REVIEWS_ZERO`로 교체한다. R007과 두 R007 review는 rejected history이며
실행 권한을 만들지 않는다. E1 직전 live goal과 사용자 취소·대체 부재를 다시
확인한다.

R007 §1의 세 one-shot path를 다음으로 교체한다.

```text
draft_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r008-r001
final_candidate_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r008-r001
evidence_root = /home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r008-r001
```

R008 review 전 세 path와 leaf는 모두 absent여야 한다. E1은 draft/evidence만 만들고
final은 absent로 남긴다. R005/R006/R007의 ancestor/openat/mkdirat, no-resume,
mode/owner/type/nlink, confined apply-patch, fd-bound bwrap, backup/runtime,
branch/status, tar restore와 negative oracle은 그대로다.

## 2. exact epoch replacement

R007 §2를 다음 표로 전부 교체한다. 각 basename은 repository 기준 directory
`docs/control/execution/artifact-closure/run-20260727-001` 아래의 literal file이다.

| Epoch | exact write set |
|---|---|
| `E0_PLAN_AUTHOR` | `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R008.md` 하나 |
| `E0_PLAN_REVIEW` | §6의 R008 review 두 파일만; frozen R008 write 0 |
| `E1_DRAFT_AUTHOR` | §1 draft root의 exact 9개와 §1 evidence root의 R006 §4 exact 4개만 |
| `E2_SOURCE_REVIEW_LOG` | `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R008-R001-independent-boundary-review-r001.md`, `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R008-R001-independent-recovery-review-r001.md`, `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715/daylog/2026-08-02.md`만 |
| `E_FAIL_SUCCESSOR` | finding이 있으면 `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R009.md` 하나 |

E2 write set은 위 행의 두 source-review basename과 daylog path를 **직접 열거**하며,
어느 절 참조로 파일을 선택하지 않는다. R008 review가 어느 severity든 nonzero면 E1/E2
source path write는 0이다. local-memory 기록은 E2 뒤 별도 automation epoch다.

## 3. evidence identity replacement

R006 §4의 exact four-file protocol, creation order, canonical bytes와 crash boundary를
모두 상속한다. 단, `authorization-gate.json`의 plan target과 review identity는 R008과
§6의 두 R008 review이고 R005~R007 identity는 rejected history input이다.

R007 §3의 acceptance review 항목은 다음으로 교체한다.

- live authority와 §6의 R008 review 두 개가 `PASS 0/0/0`

나머지 acceptance 논리곱과 세 negative crash fixture는 그대로다. `E1.COMPLETE`는
자기 SHA, 자기 생성 invocation 결과 또는 미래 관찰을 주장하지 않는다.

## 4. defect closure

| review finding | R008 closure |
|---|---|
| R007 structural m-01 | §2 E2 행이 exact 두 source-review basename을 직접 열거한다. §5의 동일 목록은 source contract 설명이며 write-set 선택 참조가 아니다. |

R007 skeptical review에는 finding이 없다. 다른 계약은 바꾸지 않는다. 특히 terminal
marker, read-only recovery, no-repair/no-resume, source command 0, network 0, live
repository/backup/canonical/product write 0은 그대로다.

## 5. source input과 source-review filenames

R007 §5를 다음으로 교체한다. `source-input-manifest.json`은 R008과 §6의 두 R008 plan
review를 current input으로 결속한다. R007/R006/R005와 각 세대의 두 plan review는
rejected/history input으로 유지한다. 미래 draft seal, E1 evidence, source review,
publication/projection output은 input으로 넣지 않는다.

source review exact files는 다음 두 개이며 §2 E2 행의 두 basename과 byte-for-byte 같다.

- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R008-R001-independent-boundary-review-r001.md`
- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R008-R001-independent-recovery-review-r001.md`

두 source review가 모두 `PASS 0/0/0`이어도 별도 publication/projection plan과 그 독립
review 전에는 draft source를 실행하지 않는다.

## 6. R008 independent review gate

서로 다른 새 agent 두 명이 frozen R008의 같은 SHA/bytes/lines를 서로의 결과 없이
검수한다. exact files는 다음이다.

- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R008-independent-structural-review-r001.md`
- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R008-independent-skeptical-review-r001.md`

각 file은 review_id/type, reviewer_agent/session, independence_attestation,
target_sha256/bytes/lines, verdict, blocking/major/minor를 정확히 한 번 둔다. root는
orchestration completion으로 reviewer가 실제로 다른지 확인한다. 어느 severity든
nonzero면 E1은 0이고 §2의 E_FAIL만 수행한다. 둘 다 `PASS 0/0/0`이고 §1 authority도
true일 때만 E1을 수행한다.

## 7. next action

다음 단일 행동은 R008을 freeze하고 서로 다른 두 새 agent에게 frozen chain과 이 delta를
함께 독립 review시키는 것이다. finding이 하나라도 있으면 R008의 세 root를 모두
absent로 유지한다.
