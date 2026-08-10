# WalkSafe bootstrap 종료 및 v2.4 실행 피벗 로드맵 20260802 R013

## 0. 지위와 결론

```text
document_id = WS-WALKSAFE-BOOTSTRAP-TERMINATION-PIVOT-20260802-R013
document_class = INTERNAL_ADD_ONLY_TERMINATION_AND_EXECUTION_PIVOT_ROADMAP
state = S0_PENDING_TWO_R013_REVIEWS
bootstrap_lineage_disposition = IMMUTABLE_REJECTED_NOT_EXECUTED
bootstrap_candidate_execution_allowed = false
official_progress_delta = 0
artifact_completion_credit_delta = 0
formal_test_credit_delta = 0
release_gate_credit_delta = 0
release_status = NOT_ELIGIBLE
```

R012의 두 독립 review가 모두 terminal이고 어느 쪽도 PASS가 아니므로 R012 `F0_PLAN_REJECTED`가
성립한다. 이 R013은 finding마다 새 protocol을 추가하지 않는다. R008~R012는 격리 projection과
negative oracle의 안전성을 탐색한 내부 실험 계보로 보존하되 실행 제어면에서 제거한다. 검증된
backup과 현재 worktree는 그대로 보존하고, 프로젝트 실행은 이미 ACTIVE인 v2.4 Goal graph의 단일
제어면으로 복귀한다.

이 전환은 bootstrap, 제품, 산출물, 정식 시험, Gate 또는 release의 완료 공로가 아니다.

## 1. frozen 입력과 종료 경계

| input | SHA-256 | bytes | lines/result |
|---|---|---:|---:|
| R012 roadmap | `a7bd3d1732e4b99f6476702aceb89b7bce35603e9c8441059fee5e6a43d7e8a0` | 24456 | 446 |
| R012 structural review | `8722af358012a3f7085f5a5b3f240d87fb76697ade0391a3c285d6c386ad6181` | 16544 | 194, REVISION_REQUIRED 4B/4M/0m |
| R012 skeptical review | `9d7378d6054e3bc3164c6a71a63ca82ba5b57b5eb0a72b73728d3727b02dc5a7` | 16234 | 199, REVISION_REQUIRED 6B/5M/0m |

두 reviewer는 서로 다른 agent/session이며 같은 frozen R012를 서로 결과 없이 검수했다. raw 19개
finding은 모든 stable ID를 보존한 채 최고 severity 기준 다음 9개 의미 군집으로 합쳐진다.

1. `U01 B` — 상태기계 비총체성 및 crash terminal 부재
2. `U02 B` — selector genesis와 alternate-root 독점성 부재
3. `U03 B` — logical/physical authority row 모순
4. `U04 B` — exact30 case authority·attempt·mutation 결속 불완전
5. `U05 B` — source-independent output oracle 실패
6. `U06 B` — FD/path/ancestor/mount confinement 미증명
7. `U07 B` — signal·timeout·exec·stream 결과 비결정성
8. `U08 M` — source-input authority와 source-review target provenance 미결정
9. `U09 M` — external write·zero-delta·daylog/local-memory accounting 불폐쇄

원문 ID와 세부 근거의 정본은 위 두 review다. R013은 이 결함을 PASS로 재해석하거나 숨기지 않는다.

### 1.1 root inventory

- R008 draft root에는 exact 9, evidence root에는 exact 4가 있으며 두 root는 `0700`, 모든 file은
  regular `0600`, uid/gid `1000/1000`, nlink 1이다. R008 final root는 absent다.
- R008 source execution/import/bytecompile/pycompile은 0이다. R008 source review는
  `6B/4M/2m`, `7B/0M/0m`으로 모두 non-PASS다.
- R009, R010, R011, R012의 draft/final/evidence root는 모두 absent다.
- 위 root에는 create/update/repair/delete/resume/rerun/publish/execute가 앞으로도 0이다.

R008 marker anchor는 seal file `db763531df2bee5f5a581bc06f7a9a367eb36c5dd1d965afdeb0f4e8d6a12c51`,
draft-post `a305729aa86beefb979a81f7e25cf5aa8fa92857967c3646d8cebddd4b80300d`, observation
`883f37dd072ee00ef45576efc2f3578ad120a8b7a3cf056a7db379c328465f87`, marker
`d25da936614ee2152724c15a4ede106f56aa8d889eb2c257836f5282310565c4`다.

```text
TERMINATION_OK =
  R008 frozen roots/anchors unchanged
  and R008 final absent
  and R009-R012 all roots absent
  and bootstrap source execution/import/compile/publish count 0
  and bootstrap-origin official/product/canonical/formal/device/Gate/release delta 0
```

## 2. 보존된 작업트리와 유일한 실행 제어면

보존 backup은
`/home/ddobagi/.codex/backups/walksafe/20260802T0125KST-rc2-pre-roadmap`다. 독립 검산한 핵심
identity는 다음과 같다.

- repository bundle: `56a95dd2b43612d27b5140dddb2857830cd2ce82571cad3748f7886dade10751`
- tracked patch: `69d2d378de56fd26a142f8220f2e527ff71e6126734664555f90a71a4f3ad558`
- untracked tar: `2de0201890d73a7067d2c5c879a69ffd31bcba8bfcc7ffbf21d0fdae1a7fe398`, exact 2550 files
- status gzip: `f3288d84b457cc5451c821572c2dea112016cfbde39bc00be3c5eb5654d3b6ca`

현재 branch는 `codex/walksafe-rc2-hardening-20260715`, HEAD는
`a3ad7eead6b5d834d3e0675422475a9aad351e3d`다. dirty worktree는 사용자 작업이므로 reset/clean/
삭제/광범위 overwrite하지 않는다.

bootstrap 계보가 종료된 뒤 유일한 실행 제어면은 다음 실제 v2.4 상태다.

- checkpoint: `docs/control/walksafe-project-continuation-checkpoint.json`, SHA-256
  `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c`, 1,329,415 bytes,
  31,453 lines, schema `1.25.0`
- package/activation: `ACTIVE/ACTIVE`
- transition tail: sequence 39, `CANONICAL_BINDINGS_UPDATED`, event SHA-256
  `c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a`
- focus: `WS-GOAL-EPIC-03` (`READY`), focus Work Item은 없음
- ready frontier: exact `WS-GOAL-EPIC-03`, `WS-GOAL-EPIC-12`
- current canonical Gap/Backlog: r021
  `f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a` /
  `bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0`
- current next internal policy/Gap: `FP-048 / GAP-057`
- artifact state: 126/257 closed-equivalent, 131 open
- formal: 0/279 executed; device/event: 0/0; release gates: 0/5; release: `NOT_ELIGIBLE`;
  project: `NOT_COMPLETE`

과거 event의 repository snapshot은 새 event에 재사용하지 않는다. 각 새 event는 바로 직전 실제
snapshot과 새 전용 receipt를 결속한다.

## 3. R013 독립 review barrier

exact review files는 다음 두 개다.

- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R013-independent-structural-review-r001.md`
- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R013-independent-skeptical-review-r001.md`

서로 다른 fresh agent 두 명이 같은 frozen R013 SHA/bytes/lines를 서로 결과 없이 검수한다.
structural review는 frozen identity, root inventory, v2.4 current facts, 종료와 피벗 상태기계, 아래
stage gate를 검수한다. skeptical review는 bootstrap 실행이 암묵적으로 살아나지 않는지, 제품 gate를
우회하지 않는지, official credit 과장·dirty-worktree 손상·외부 권한 확대가 없는지 공격한다.

각 reviewer task의 terminal 여부와 expected path inventory를 먼저 capture한다. 그 뒤 file 존재·
regular 0664·uid/gid 1000/1000·nlink1, assigned agent/session, target identity, independence,
verdict/severity를 total validation한다. missing/malformed/wrong/duplicate/non-independent/nonzero finding은
모두 `R013_REJECTED`; 둘 다 VALID `PASS 0/0/0`만 `PIVOT_PLAN_OK`다. user authority가 취소·대체되면
write 없이 `AUTHORITY_STOPPED`다.

```text
S0_REVIEWING -> exact two review Add File only
PIVOT_PLAN_OK -> canonical v2.4 stage P1 only
R013_REJECTED -> bootstrap/checkpoint/product write 0; R014 one file only
AUTHORITY_STOPPED -> all further write/command 0
```

R013 review는 product code 변경, Goal 시작, formal/Gate/release credit를 직접 허가하지 않는다.

## 4. 전체 실행 로드맵

### P0 — bootstrap 종료 동결

- 행동: `TERMINATION_OK`를 nofollow/stat/hash로 read-only 재확인한다.
- 검증: R008 immutable, R009-R012 root absent, candidate execution 0.
- 실패: 해당 path를 고치지 않고 별도 보존 사고로 중단한다.

### P1 — v2.4 단일 제어면 재기준선

- 행동: 다음 Quick2를 실행한다.

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_4.py
python3 -B scripts/check_walksafe_goal_graph_v2_4.py
```

- 검증: 둘 다 rc0; package ACTIVE; tail seq39; focus Work Item 없음; ready frontier와 r021 binding이
  §2와 일치한다.
- drift: §2 값을 현장에서 덮어쓰지 않고 새 실제 상태에서 selection을 다시 계산한다.

### P2 — exact 한 FP-048/GAP-057 leaf materialization

- 행동: `policy-gap-work-item.md` template와 r021의 unique FP-048→GAP-057 mapping에서 다음 exact
  leaf 후보를 만든다.

```text
goal_id = WS-GOAL-EPIC-03-FP-048-R001
path = docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/epic-03-fp048-encryption-key-separation-rotation-audit-r001.md
work_item_id = EPIC-03-FP048-ENCRYPTION-KEY-SEPARATION-ROTATION-AUDIT
parent_goal_id = WS-GOAL-EPIC-03
work_item_type = POLICY_GAP_WORK
priority_rank = 23
target_completion_level = INTERNAL_POLICY_CONFORMANCE_REASSESSED
source_policy_ids = [FP-048]
gap_ids = [GAP-057]
start_requires = [WS-GOAL-EPIC-03-FP-047-R001]
completion_requires = [WS-GOAL-EPIC-03-FP-047-R001]
predecessor_goal_id = WS-GOAL-EPIC-03-FP-047-R001
predecessor_goal_content_sha256 = 2ff79dde64cb113d755855f05dafb6bab1393717f060b4db387bb2d48bc53b06
materialized_from_document_id = WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260726-021
materialized_from_sha256 = bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0
readiness_basis_completion_event_sha256 = aca93931b1cd8dcc06508f4078a7983d729df43f6a6690fceb1b3e875090152f
```

  정확히 한 정책·한 Gap만 소유한다. seq40 `GOAL_MATERIALIZED`는 PLANNED leaf만 추가하고, 별도
  seq41 `GOAL_READY`가 FP-047 completion과 blocker 0을 다시 확인해 READY로 바꾼다.
- 검증: 실제 Goal bytes, materialization event, readiness event와 staged checkpoint를 별도 경로에서
  먼저 v2.4 continuation/Goal checker로 검증한다. 정확한 predecessor SHA, r021 path/document/SHA,
  canonical roles, frontier arithmetic 또는 event hash가 하나라도 불일치하면 live file write는 0이다.
- 경계: materialization/READY는 구현 시작·완료 공로가 아니다.

### P3 — fresh implementation-start 19 gates

- 행동: `docs/control/README.md`의 v2.4 `2026-07-25.4` full contract, SHA-256
  `8c7e16f13a66398e5ba067cba0df9ba00258018f630256f6abc5b881b0467b7c`에 적힌 exact 19개 명령을 새
  event ID와 add-only raw-output directory에서 순서대로 실행한다. locked Node/Python을 사용한다.
- 검증: 19개 모두 rc0, exact roles·순서·command hash 일치, 마지막 `REPOSITORY_STATE`가 같은 event의
  post-gate snapshot과 일치한다.
- 실패: Goal을 시작하거나 product file을 쓰지 않는다. 원인을 분류하고 gate branch만 교정한다.

### P4 — GOAL_STARTED

- 행동: gate receipt와 동일 snapshot에 결속한 별도 `GOAL_STARTED` event로 FP-048 leaf 하나만
  `READY → IN_PROGRESS`로 바꾼다.
- 검증: plain Quick2와
  `check_walksafe_goal_graph_v2_4.py --current-work-session-id <event_id>`가 모두 rc0다.
- 경계: 이 검증 전 product/test implementation write는 0이다.

### P5 — fail-first 최소 구현

- 행동: FP-048 정책, RQ-FP-048-001, GAP-057, TC-FP-048-01~07을 역추적해 현재 평문-at-rest/
  key-separation 공백을 재현하는 테스트를 먼저 만든다. 그 뒤 한 leaf 범위의 최소 구현만 한다.
- 검증: pre-fix는 의도한 보안 단언으로 FAIL, post-fix targeted suite와 관련 backend/Gateway/security
  회귀는 PASS다. 새 secret, network, 배포, 실제 KMS/HSM, 실기기, 외부 데이터 사용은 0이다.
- 범위: 실제 KMS·production TLS·key custody/rotation·실기기·독립 보안/개인정보/법률 검토는
  내부 mock PASS로 대체하지 않는다.

### P6 — 내부 증거·독립 구현 검수·canonical successor

- 행동: `IMPLEMENTATION_RECORD → VERIFICATION_RESULT → 새 Gap/Backlog bytes → SUCCESSOR_TRACE →
  독립 review → completion receipt → CANONICAL_BINDINGS_UPDATED → GOAL_COMPLETED` 순서를 지킨다.
- 검증: 모든 file/path/hash/event/snapshot이 동일 Goal·실행 구간에 결속되고 v2.4 checker와 관련 회귀가
  PASS한다. 내부 결과와 정식 시험/Gate/release를 분리한다.
- 상태: 외부 근거가 남으면 GAP-057은 `PARTIAL` 또는 `EVIDENCE_MISSING`, formal은 `NOT_RUN`, release
  gates는 미완료, release는 `NOT_ELIGIBLE`을 유지한다.

### P7 — 다음 frontier와 외부 lane

- 행동: 완료 뒤 ready frontier를 다시 계산해 다음 internal leaf를 진행한다. 내부 branch가 남아 있으면
  외부 request가 선점하지 않는다.
- 외부: 실기기, 참여자, 유료 서비스, secret, production 배포, 기관 제출, 독립 전문검토, 실제 Gate/
  release 판단은 실제 권한·증거 전에는 실행하지 않는다. 필요할 때도 `ISSUED_NOT_EVIDENCE` packet은
  요청서일 뿐 PASS/완료 evidence가 아니다.

## 5. 성공 기준과 현재 단일 next action

```text
ROADMAP_READY = TERMINATION_OK and two R013 reviews VALID PASS 0/0/0
LEAF_START_READY = ROADMAP_READY and P1/P2 staged validation PASS and P3 19/19 PASS
IMPLEMENTATION_ALLOWED = LEAF_START_READY and P4 GOAL_STARTED/session checker PASS
INTERNAL_LEAF_OK = fail-first proof and minimal implementation and targeted/regression PASS
                   and independent implementation review PASS
```

현재 단일 next action은 이 R013을 freeze한 뒤 두 independent review를 병렬 수행하는 것이다. 그
barrier 전 bootstrap root, checkpoint, Goal, product/test implementation write는 0이다.

## 6. session-end 기록 분리

daylog와 local-memory는 각 작업 단계의 acceptance나 실행 authority가 아니다. session 종료 시 global
instruction에 따라 실제 prior facts, 변경 파일, 검증 결과만 한 번 기록한다. DB write 전 backup과
writer 상태를 확인한다. 기록 실패는 bootstrap repair, Goal replay, product retry 또는 공식 공로를
허가하지 않으며 별도 운영 문제로 보고한다.
