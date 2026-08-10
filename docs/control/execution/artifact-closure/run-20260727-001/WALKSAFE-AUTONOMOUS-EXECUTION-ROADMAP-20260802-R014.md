# WalkSafe v2.4 순서 권위 및 역사-prefix 보정 로드맵 20260802 R014

## 0. 지위와 단일 결론

```text
document_id = WS-WALKSAFE-V2-4-CONTROL-CORRECTION-20260802-R014
document_class = INTERNAL_ADD_ONLY_REVIEWED_CONTROL_CORRECTION_ROADMAP
state = A0_FROZEN_PENDING_TWO_PLAN_REVIEWS
source_revision = R013_REJECTED
scope = SEQ39_HISTORICAL_PREFIX_VALIDATION_AND_ATOMIC_R022_ORDER_POINTER_CORRECTION
product_or_product_test_write_allowed = false
goal_materialization_allowed = false
goal_started_allowed = false
formal_or_device_or_gate_credit_delta = 0
artifact_completion_credit_delta = 0
release_status = NOT_ELIGIBLE
```

R013의 두 review는 각각 `REVISION_REQUIRED 7B/0M/0m`, `REVISION_REQUIRED 6B/0M/0m`이다.
따라서 R013은 checkpoint, Goal, product/test 또는 canonical write authority가 아니다. R014는 R013의
열린 leaf 구현을 축소해 현재 canonical 모순을 먼저 바로잡는 한 번의 control correction만 다룬다.
이 문서 아래에서는 FP-008 또는 FP-048 leaf를 만들거나 시작하거나 구현하지 않는다.

결정적인 선택 규칙은 r021의 stale 포인터가 아니라 immutable EPIC-03 Workstream이다. Workstream은
`FP-047 → FP-008 → FP-046 → NPC-SINGLE-ADMIN-RECOVERY → FP-048`를 고정한다. r021 Backlog의
`next_action_sequence` order 19~23과 `ordered_source_policy_ids`도 이 순서와 일치한다. 모순은 다음
세 포인터뿐이다.

- Gap r021 `reassessment_scope.next_adjacent_gap`이 `FP-048/GAP-057`을 order 20이라 주장한다.
- Backlog r021 `next_single_action`이 `FP-048/GAP-057`을 가리킨다.
- Backlog r021 EPIC-03 `current_status_reason`이 FP-048을 다음이라고 기술한다.

따라서 FP-048을 order 20으로 끌어올리지 않는다. 그 선택은 immutable Workstream/static priority를
바꾸는 successor package가 필요하다. R014의 좁은 보정은 order row를 전혀 바꾸지 않고 위 세 포인터를
`FP-008/GAP-017`, order 20으로 맞추는 atomic Gap/Backlog r022 successor다.

## 1. frozen predecessor와 review union

| input | SHA-256 | bytes | lines/result |
|---|---|---:|---:|
| R013 roadmap | `40e68ae0e808af3eb2038b672fb0545a8d35dd25a4d910f866b75dcd1c8f958a` | 13769 | 248 |
| R013 structural review | `73f623596999dd9c8e93e64bd457b6d453308d671bade6fd0c5be0bbf5de927e` | 11947 | 137, REVISION_REQUIRED 7B/0M/0m |
| R013 skeptical review | `ed03185753161ccd959b422080bc5cebdc27e4937fd3ad72e1d59c95c04c02c9` | 13082 | 151, REVISION_REQUIRED 6B/0M/0m |

raw 13개 finding은 stable ID를 잃지 않은 채 다음 7개 의미 군집으로 합쳐진다.

1. `U01` — plan review availability와 complete leaf plan barrier 불폐쇄
2. `U02` — bootstrap termination 재구성 및 P0 강제 실패
3. `U03` — current next authority 비유일성과 drift 재선택
4. `U04` — success/error/crash/retry 상태기계 비총체성
5. `U05` — staged 검증과 live canonical mutation 순서 역전
6. `U06` — leaf/product/test owned delta와 write scope 미동결
7. `U07` — 미지정 future leaf 권한 확장

R014는 U03을 실제 canonical correction으로 닫고, U01/U02/U04/U05를 아래 전이와 검수로 닫는다.
U06/U07은 leaf/product/test write authority 자체를 0으로 두어 닫는다. FP-008 complete leaf bytes와
제품 범위는 R014 성공 뒤 새 successor roadmap 및 독립 review에서만 다룬다.

## 2. bootstrap 종료의 complete nofollow inventory

다음 세 literal R008 root만 predecessor physical inventory다.

```text
draft_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r008-r001
final_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r008-r001
evidence_root = /home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r008-r001
```

`draft_root`는 directory `0700`, uid/gid `1000/1000`, nlink 2이며 바로 아래 exact 9개뿐이다.
모든 entry는 regular `0600`, uid/gid `1000/1000`, nlink 1이고 하위 directory/link/special은 0이다.

| basename | bytes | SHA-256 |
|---|---:|---|
| `README.md` | 1333 | `7ebb7bdf0dbefed6257e6707a35f886e6834255df1836bc5f8fb12356119505e` |
| `build-projection.py` | 29633 | `062a571fdbda42bf0a4abe360f4d50375abd31ed4c3cf86ba4654e7282df563b` |
| `draft-content-manifest.json` | 1357 | `a7a64ddc93dace6cd4116fd58821ab240b639a55156989bb1450bf0feecc0397` |
| `draft-content-manifest.sha256` | 94 | `db763531df2bee5f5a581bc06f7a9a367eb36c5dd1d965afdeb0f4e8d6a12c51` |
| `publish-source.py` | 11408 | `ff980cb9996b72491e1294c2fa6d75cefffda49e68cd1db47325bdf789ad8de2` |
| `run-readonly-sandbox.py` | 39854 | `58938c8e6c0d9a3d9d4e4bcd5bdfee07c8ad27e6aa654dfa8d6ba4fa7647b5c2` |
| `runtime-closure.json` | 8406 | `68130236789c435308726d29fe944646567179dfec03cce114cef27bd08cae1f` |
| `source-input-manifest.json` | 5769 | `cf9f98be0ec0f875d444e92ef401b60867224e2bb96429795b9b97cd7b3a08de` |
| `verify-bootstrap.py` | 31865 | `b9fcec59c4499ac25a7e5072c371c840c904bf018dc4f1e9c1f5f3cb10796245` |

`evidence_root`는 directory `0700`, uid/gid `1000/1000`, nlink 2이며 바로 아래 exact 4개뿐이다.
모든 entry는 regular `0600`, uid/gid `1000/1000`, nlink 1이고 하위 directory/link/special은 0이다.

| basename | bytes | SHA-256 |
|---|---:|---|
| `E1.COMPLETE` | 294 | `d25da936614ee2152724c15a4ede106f56aa8d889eb2c257836f5282310565c4` |
| `authorization-gate.json` | 1407 | `ad7bb193e73402f7e97256f42ced659205046b4f6ebacb21b2f89f9661c7a2e5` |
| `draft-post.json` | 2944 | `a305729aa86beefb979a81f7e25cf5aa8fa92857967c3646d8cebddd4b80300d` |
| `e1-observation.json` | 6371 | `883f37dd072ee00ef45576efc2f3578ad120a8b7a3cf056a7db379c328465f87` |

`final_root`는 absent다. 다음 R009~R012 literal 12개 root도 모두 absent다.

```text
/home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r009-r001
/home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r009-r001
/home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r009-r001
/home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r010-r001
/home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r010-r001
/home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r010-r001
/home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r011-r001
/home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r011-r001
/home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r011-r001
/home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r012-r001
/home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r012-r001
/home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r012-r001
```

P0는 위 complete manifest를 `lstat/scandir(follow_symlinks=false)/SHA-256`으로 재구성한다. 어느 root,
entry, metadata, bytes 또는 absence가 다르면 `I_P0_BOOTSTRAP_DRIFT`; repair/chmod/delete/resume/rerun,
candidate 실행/import/compile 및 repository write는 0이다.

## 3. exact repository authority와 source anchors

```text
repository_root = /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
root_dev = 66306
root_inode = 16649068
root_type_mode = directory 0775
root_uid_gid = 1000/1000
branch = codex/walksafe-rc2-hardening-20260715
HEAD = a3ad7eead6b5d834d3e0675422475a9aad351e3d
```

| path | SHA-256 | bytes/mode/nlink |
|---|---|---|
| `docs/control/walksafe-project-continuation-checkpoint.json` | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1329415 / 0644 / 1 |
| `docs/control/audits/walksafe-implementation-gap-analysis-20260726-r021.json` | `f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a` | 488160 / 0664 / 1 |
| `docs/control/audits/walksafe-implementation-gap-analysis-20260726-r021.md` | `08fb2014e9bb3017a6990e63d3d69247d5504aafb91bf6d1f8be7d1b9c6a09d5` | 727 / 0664 / 1 |
| `docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r021.json` | `bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0` | 59266 / 0664 / 1 |
| `docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r021.md` | `18c79903b38ed5b86b95cfc04624a88a0135007895a1f7b2b222a1566f1b9bcb` | 381 / 0664 / 1 |
| `docs/control/goals/walksafe-completion-graph-v2-2/workstreams/epic-03-account-admin-security.md` | `7d2dfb7fe89bd30fc1f6113acb0b533a9fd91a6106cc2ab4798091763beae832` | 3602 / 0664 / immutable |
| `docs/control/goals/walksafe-completion-graph-v2-4/static-plan-manifest-v2.4.0.json` | `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07` | 39534 / 0600 / immutable |
| `scripts/check_walksafe_project_continuation_v2_4.py` | `0fec0201a8a105258fb29c23c9418c9756fad39d40ca2612d2f5517282305146` | 157875 / 0664 / 3 |
| `scripts/check_walksafe_goal_graph_v2_4.py` | `d2a4d1233e14f1ebcec3a9beb3f59feb8bf179c9cd02db916b56da141501d922` | 332332 / 0664 / 1 |
| `tests/test_walksafe_project_continuation_v2_4.py` | `cf8d981cf1e703ad3b0d94bcd47c907a609572b300ef25a3de0a22e04285546a` | 41608 / 0664 / 1 |
| `tests/test_walksafe_goal_graph_v2_4.py` | `4ebc437267b8c42abcefaa5772a146ac4c3bad6c05d63e37767e6eb0133c138d` | 155620 / 0664 / 1 |

checkpoint는 schema `1.25.0`, `ACTIVE/ACTIVE`, tail sequence 39
`CANONICAL_BINDINGS_UPDATED`, tail hash
`c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a`다. focus는 READY
EPIC-03이며 IN_PROGRESS Goal과 focus Work Item은 없다. artifact 126/257, formal 0/279,
device/event 0/0, release gate 0/5, release `NOT_ELIGIBLE`, project `NOT_COMPLETE`다.

continuation checker의 inode `16647362`는 다음 exact 세 hard link를 가진다.

```text
/home/ddobagi/.cache/walksafe-fp011-seq7-v24-g8rctyj2/scripts/check_walksafe_project_continuation_v2_4.py
/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715/scripts/check_walksafe_project_continuation_v2_4.py
/home/ddobagi/Code/walksafe-v24-tests-stage-j22XpI/scripts/check_walksafe_project_continuation_v2_4.py
```

세 path 모두 dev/inode `66306/16647362`, regular `0664`, uid/gid `1000/1000`, nlink 3,
157875 bytes와 위 SHA다. live 파일을 in-place truncate하면 두 historical stage도 훼손되므로 금지한다.
설치 직전 동일 bytes의 nlink1 sibling을 만들고 source anchor를 다시 확인한 뒤 atomic replace로 live link만
분리한다. 두 peer의 inode/hash/metadata는 전후 동일해야 한다. 분리 또는 검증 실패는
`I_LINK_ISOLATION_STOP`; 강한 삭제/재시도/peer write는 0이다.

## 4. R014 plan review barrier

exact review task와 output은 다음 둘뿐이다.

| role | assigned task | exact output |
|---|---|---|
| structural | `/root/r014_structural_review` | `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R014-independent-structural-review-r001.md` |
| skeptical | `/root/r014_skeptical_review` | `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R014-independent-skeptical-review-r001.md` |

dispatch 전에 두 output은 absent여야 한다. 두 fresh task는 subagent를 만들지 않고 서로의 task/file/
결과/메시지/존재 여부를 읽거나 전달받지 않는다. frozen R014 전체의 동일 SHA/bytes/lines만 검수한다.
각 output은 assigned agent가 만든 단일 Add File, regular `0664`, uid/gid `1000/1000`, nlink 1이며 다음
metadata key를 정확히 한 번 가진다.

```text
review_id type reviewer_agent reviewer_session independence_attestation
target_sha256 target_bytes target_lines verdict blocking major minor
```

root operator는 두 task가 모두 terminal이고 child 0인 뒤 딱 한 번 availability/expected-path inventory를
capture한다. 그 capture 전에는 A1 read-only다. deadline, interrupt, cancel, nonterminal, unexpected child,
missing/extra/wrong output, metadata/target/identity/independence 오류 또는 nonzero finding은 모두
`I_PLAN_REVIEW_REJECTED`; bootstrap/canonical/checker/Goal/product write와 retry/resume은 0이다. exact 두
`PASS 0/0/0`만 `PLAN_REVIEW_OK`다.

## 5. 보정 내용과 literal write allowlist

### 5.1 seq39 historical-prefix checker correction

다음 네 기존 파일만 control source/test update가 허용된다.

```text
scripts/check_walksafe_project_continuation_v2_4.py
tests/test_walksafe_project_continuation_v2_4.py
scripts/check_walksafe_goal_graph_v2_4.py
tests/test_walksafe_goal_graph_v2_4.py
```

continuation checker는 seq39 event SHA
`c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a`를 새 trust anchor로 고정한다.
seq39 matching은 `sequence == 39 OR event_id == exact ID`의 합집합이 정확히 하나이고 history index 38,
sequence 39, exact ID여야 한다. seq39가 tail인 len39 경로는 기존 reverse/project/source checkpoint/
working snapshot/live exact-five 검사를 byte-for-byte 의미상 유지한다. suffix가 있으면 current checkpoint를
seq39로 reverse/project하지 않고 stored event hash와 재계산 hash가 둘 다 trust anchor인지, seq39 snapshot과
역사 r021 exact paths가 고정됐는지 확인한다. suffix와 최종 state/current canonical은 generic replay가
검증한다. re-seal tamper, duplicate ID/sequence, 위치 이동, broken previous hash는 모두 거부한다.

Goal checker의 FP-047 completion validator는 현재 top-level `IMPLEMENTATION_GAP/BACKLOG`이 영원히
r021이어야 한다고 요구하지 않는다. sequence 37 FP-047 canonical update의 frozen
`canonical_binding_snapshot_after`에서 exact r021 두 binding을 가져와 r021 physical bytes와 FP-047
receipt를 검증한다. latest canonical snapshot과 live top-level binding은 일반 replay가 별도로 검증한다.
역사 snapshot, r021 file 또는 pair digest tamper는 계속 거부한다.

fail-first test ID는 정확히 다음이다.

```text
test_seq39_exact_prefix_accepts_valid_suffix
test_seq39_exact_prefix_resealed_tamper_is_rejected
test_seq39_exact_prefix_position_and_uniqueness_are_enforced
test_fp047_completion_uses_historical_r021_after_valid_successor_binding
test_fp047_historical_r021_snapshot_tamper_is_rejected
```

첫 번째와 네 번째는 source checker에서 각각 `seq39 checkpoint tail differs`/historical FP-047 false
boundary로 FAIL하고 다른 기존 targeted case는 PASS해야 한다. patch 뒤 다섯 case와 기존 seq39/FP047
회귀가 모두 PASS해야 한다.

### 5.2 atomic r022 pair correction

static plan의 `policy_gap_contract.gap_backlog_pair_is_atomic=true`에 따라 다음 네 output은 한 staged
candidate와 한 checkpoint transaction이다.

```text
docs/control/audits/walksafe-implementation-gap-analysis-20260726-r022.json
docs/control/audits/walksafe-implementation-gap-analysis-20260726-r022.md
docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r022.json
docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r022.md
```

Gap JSON은 r021 전체 68 assessment, 각 assessment hash, status/count/priority, source evidence,
formal/device/Gate/release 경계를 그대로 보존하고 다음만 바꾼다.

- metadata: report ID suffix `022`, version `0.22.0`, prepared_at
  `2026-08-02T09:10:00+09:00`, predecessor report ID r021.
- reassessment mode를 `ORDER_POINTER_CORRECTION_ONLY_WITH_R021_ASSESSMENTS_PRESERVED`로 하고 새 직접
  재평가·공로가 0임을 명시한다.
- `next_adjacent_gap`을 exact `GAP-017 / FP-008 / EPIC-03 deterministic execution order advances to order 20.`으로 바꾼다.
- predecessor는 r021 exact path/file SHA와 `revalidated_wholesale_in_r022=false`를 결속한다.
- correction record에 immutable Workstream/static manifest/r021 Gap/Backlog 네 source SHA, old/new pointer,
  unchanged order/status/credit를 기록한다.
- `report_content_sha256`은 자기 field를 제외한 canonical JSON object로 다시 계산한다.

Backlog JSON은 r021의 68 `next_action_sequence` row와 모든 order, EPIC-03
`ordered_source_policy_ids`, status/count/priority를 그대로 보존하고 다음만 바꾼다.

- metadata: backlog ID suffix `022`, version `0.22.0`, 같은 prepared_at, predecessor backlog ID r021.
- source predecessor를 r021 exact path/SHA에 결속한다.
- EPIC-03 reason을 FP-047 완료 뒤 order 20의 FP-008/GAP-017이 다음이고 이후 FP-046,
  NPC-SINGLE-ADMIN-RECOVERY, FP-048 순임을 말하도록 교정한다.
- `next_single_action`은 EPIC-03, `FP-008`, `GAP-017`, `PLANNED_NEXT`, 그리고 GAP-017의 exact
  remediation인 `사용자 앱과 별개 앱 식별값·서명·세션을 쓰는 Android 관리자 앱을 만들고 로그인, 검수, 기관 전달, 감사까지 관리자 흐름을 분리한다.`로 바꾼다.
- Gap content digest는 새 r022 Gap content digest와 일치한다.
- correction record와 `backlog_content_sha256`을 같은 방식으로 다시 계산한다.

두 Markdown은 r022 identity, r021 평가·공로 무변경, old/new pointer와 다음 FP-008/GAP-017만 간단히
기술한다. FP-008 materialization/implementation이나 FP-048 재정렬을 주장하지 않는다.

### 5.3 deterministic tool, receipt, seq40

다음 두 파일만 새 executable/test source로 추가한다.

```text
scripts/apply_walksafe_goal_graph_v2_4_seq40_order_correction_20260802.py
tests/test_walksafe_goal_graph_v2_4_seq40_order_correction_20260802.py
```

tool은 §3 모든 source anchor, R014 및 두 plan review identity, 네 r022 output absence, exact event/receipt
absence를 먼저 검증하고 위 변환만 메모리에서 결정적으로 만든다. JSON은 UTF-8, two-space indent,
terminal LF다. `--check`는 exact output bytes를 비교하고 `--write`는 O_EXCL temp/add-only output 뒤
checkpoint를 마지막에 `os.replace`한다. source checkpoint가 exact SHA/bytes가 아니면 write 0이다.

```text
event_sequence = 40
event_id = WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-R014-ORDER-CORRECTION-20260802-001
event_type = CANONICAL_BINDINGS_UPDATED
occurred_at = 2026-08-02T09:10:00+09:00
changed_binding_roles = [IMPLEMENTATION_GAP, IMPLEMENTATION_BACKLOG]
status_changes = {}
focus = WS-GOAL-EPIC-03 / READY
source_checkpoint_version = 1.25.0
```

seq40는 previous hash를 exact seq39 anchor에, source checkpoint를 §3 SHA/bytes에, changed bindings를
두 r022 JSON path/document/file SHA에, authorization을 다음 add-only receipt에 결속한다.

```text
docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-R014-ORDER-CORRECTION-20260802-001/control-correction-authorization.json
```

receipt는 R014 frozen identity와 두 PASS plan review, source anchors, exact before/after pointer, output
manifest, zero-credit boundary를 기록한다. canonical snapshot은 두 role만 r022로 바꾸고 나머지는 seq39와
같다. runtime/status/frontier/blocker/completion/artifact queue는 seq39와 같다. schema는 `1.25.0`을
유지한다. working snapshot은 기존 sorted 603 paths에 위 tool/test와 r022 네 files만 더한 exact sorted
609 paths이며 checkpoint와 `goal-gates/` receipt/review/roadmap은 포함하지 않는다. 새 path/content hash를
최종 bytes에서 계산한다.

## 6. staged candidate와 독립 exact-output review

staging root는 다음 하나다. 실행 전 root와 모든 descendant가 absent여야 한다.

```text
/home/ddobagi/.codex/work/walksafe/20260802-r014-control-correction-r001
```

PLAN_REVIEW_OK와 P0/P1 PASS 뒤 exact root를 `0700`, uid/gid `1000/1000`으로 한 번 만들고 그 아래
`repo`에 live tree를 reflink 가능한 독립 copy로 만든다. symlink/special/mount crossing은 거부한다.
stage continuation checker는 nlink 1이고 live/두 peer와 inode가 달라야 한다. stage에서만 fail-first
test를 먼저 추가·실행한 뒤 checker와 tool을 구현하고 tool `--write`로 r022/receipt/checkpoint 후보를
만든다.

```bash
PYTHONDONTWRITEBYTECODE=1 python3 -B -m unittest \
  tests.test_walksafe_project_continuation_v2_4 \
  tests.test_walksafe_goal_graph_v2_4 \
  tests.test_walksafe_goal_graph_v2_4_seq40_order_correction_20260802 -v
python3 -B scripts/check_walksafe_project_continuation_v2_4.py
python3 -B scripts/check_walksafe_goal_graph_v2_4.py
python3 -B scripts/apply_walksafe_goal_graph_v2_4_seq40_order_correction_20260802.py --check
```

모두 rc0이고 source r021/Workstream/static plan이 그대로이며 stage tail이 exact seq40일 때만
`STAGE_CANDIDATE_OK`다. candidate의 exact changed/added path inventory는 §5의 4 update + 2 source add +
4 r022 add + receipt add + checkpoint update뿐이다.

그 뒤 서로 다른 fresh task `/root/r014_correction_structural_review`와
`/root/r014_correction_skeptical_review`가 서로의 결과 없이 같은 stage root identity와 exact 12-output
tuple을 검수하고 다음 exact Add File만 만든다.

```text
WALKSAFE-R014-CONTROL-CORRECTION-independent-structural-review-r001.md
WALKSAFE-R014-CONTROL-CORRECTION-independent-skeptical-review-r001.md
```

§4와 같은 terminal/child0/availability/identity/independence/`PASS 0/0/0` total validation을 적용한다.
어느 nonzero finding이나 task/output 오류도 `I_CANDIDATE_REVIEW_REJECTED`; stage bytes는 보존하고 live
write/retry/resume은 0이다.

## 7. live checkpoint-last switch와 total state machine

각 success output만 다음 상태의 유일한 입력이다.

```text
A0_FROZEN
  -> A1_TWO_PLAN_REVIEWS_TERMINAL
  -> A2_PLAN_REVIEW_OK
  -> A3_P0_TERMINATION_OK
  -> A4_P1_AUTHORITY_AND_QUICK2_OK
  -> A5_STAGE_ROOT_CREATED
  -> A6_FAIL_FIRST_EXACT_TWO_FAILURES_PROVED
  -> A7_STAGE_CANDIDATE_OK
  -> A8_TWO_CANDIDATE_REVIEWS_TERMINAL
  -> A9_CANDIDATE_REVIEW_OK
  -> A10_LIVE_PREIMAGE_AND_HARDLINK_ISOLATION_OK
  -> A11_LIVE_SOURCES_INSTALLED
  -> A12_LIVE_SEQ40_CHECKPOINT_LAST_SWITCHED
  -> A13_LIVE_QUICK2_AND_TOOL_CHECK_OK
  -> A14_R014_COMPLETE
```

P1은 literal root/dev/inode/branch/HEAD와 §3 모든 source anchor 및 다음 Quick2 rc0를 요구한다.

```bash
python3 -B scripts/check_walksafe_project_continuation_v2_4.py
python3 -B scripts/check_walksafe_goal_graph_v2_4.py
```

A10에서 backup identity와 live preimage를 다시 확인하고 continuation checker hardlink를 §3 방식으로
분리한다. A11은 reviewed stage의 4 updated checker/test와 2 new tool/test bytes만 live에 설치한다.
A12 tool은 r022 pair와 receipt를 add-only로 만든 뒤 checkpoint를 마지막에 한 번 교체한다. A13은
§6 세 명령과 Quick2를 live에서 다시 실행하고 tail seq40/current r022 pair/history r021/zero-credit를
검증한다. preexisting user delta와 allowlist 밖 delta는 보존하며 correction evidence에서 제외한다.
allowlist path의 source anchor가 다르거나 외부 delta가 충돌하면 clean/reset/delete/overwrite 없이
`I_PREIMAGE_CONFLICT_STOP`이다.

어느 단계의 command error, nonzero, timeout, malformed output, drift, collision, partial file, checker
failure, review failure, signal 또는 crash 관찰도 해당 `I_<STAGE>_PRESERVE_STOP` absorbing terminal이다.
그 terminal에서는 기존 bytes를 보존하고 repair/rollback/replay/retry/continued write는 0이다. 재개는
observed state를 새 frozen input으로 삼은 새 successor roadmap과 독립 review가 있을 때만 가능하다.
user authority가 취소·대체되면 즉시 `AUTHORITY_STOPPED`, 모든 후속 command/write 0이다.

## 8. R014 완료 경계와 다음 행동

```text
R014_COMPLETE =
  two plan reviews VALID PASS 0/0/0
  and P0 complete bootstrap inventory unchanged
  and exact repository authority unchanged at P1
  and fail-first proof observed in isolated stage
  and stage candidate Quick2/tool/tests PASS
  and two candidate reviews VALID PASS 0/0/0
  and live exact allowlist delta only
  and tail seq40/current atomic r022 pair/history prefix PASS
  and official/product/Goal/formal/device/Gate/release credit delta 0
```

R014_COMPLETE 뒤 허용되는 것은 ready frontier와 exact next candidate를 read-only 재계산해
`FP-008/GAP-017` 여부를 보고하는 것뿐이다. FP-008 leaf path/bytes, fail-first product scope, start gate와
implementation allowlist를 고정한 새 roadmap 및 두 독립 review 전에는 Goal/checkpoint/product/test
추가 write가 0이다. FP-048 조사 결과는 보존하되 FP-008보다 먼저 실행할 authority가 아니다.

## 9. session-end 기록 분리

daylog/local-memory는 acceptance나 실행 authority가 아니다. session 종료 때 실제 결과만 한 번 기록한다.
DB write 전 backup과 writer 상태를 확인한다. 기록 실패는 control correction replay, Goal/product write,
official credit 또는 release 승격을 허가하지 않는다.
