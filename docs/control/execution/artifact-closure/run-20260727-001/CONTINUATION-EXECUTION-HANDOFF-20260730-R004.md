# WalkSafe 새 터미널 실행 준비 인계 R004

- 문서 ID: `WS-CONTINUATION-EXECUTION-HANDOFF-20260730-R004`
- 상태: `DESIGN_REVIEWED_SOURCE_DRIFT_BLOCKED_RESTART_HANDOFF`
- 작성일: `2026-07-30`
- predecessor:
  `CONTINUATION-PLAN-HANDOFF-20260729-R003.md`
- 저장소:
  `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715`
- branch: `codex/walksafe-rc2-hardening-20260715`
- HEAD: `a3ad7eead6b5d834d3e0675422475a9aad351e3d`

## 1. 목적과 한 줄 상태

새 터미널에서 Codex를 다시 호출할 때 이 문서를 부트스트랩 포인터로 전달한다.
R003 이후의 exact68, FP-008 준비, R002~R007 제어설계와 source drift 조사 결과를
다시 만들지 않고 이어가기 위한 add-only 인계다.

현재 상태는 다음 한 줄이다.

```text
R007_DESIGN_REVIEWED_FINDINGS_ZERO_SOURCE_DRIFT_BLOCKED_WAITING_FOR_EXPLICIT_SOURCE_STRATEGY_CHOICE
```

R007 설계는 독립검수 findings 0이지만 적용 가능한 candidate나 승인 receipt가
아니다. 현재 활성 제어는 계속 v2.4 / sequence 39 / canonical r021이다. 이 문서는
source 변경, P/M candidate build, 사용자 승인, canonical 전환, Goal 시작, FP-008,
제품 코드, formal·실기기·event·artifact·gate·release 권한이 아니다.

이번 작업의 프로젝트 상태 delta는 모두 0이다.

```text
CHECKPOINT_SEQUENCE_DELTA=0
CANONICAL_GAP_BACKLOG_SWITCH_DELTA=0
GOAL_EVENT_DELTA=0
PRODUCT_CODE_CHANGE_DELTA=0
FORMAL_RUN_DELTA=0
ACTUAL_DEVICE_OR_EXTERNAL_EVENT_DELTA=0
ARTIFACT_CREDIT_DELTA=0
RELEASE_CREDIT_DELTA=0
```

## 2. 활성 정본과 불변 수치

| 역할 | 경로 | SHA-256 | bytes |
|---|---|---:|---:|
| active checkpoint | `docs/control/walksafe-project-continuation-checkpoint.json` | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| v2.4 static | `docs/control/goals/walksafe-completion-graph-v2-4/static-plan-manifest-v2.4.0.json` | `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07` | 39,534 |
| canonical Gap r021 | `docs/control/audits/walksafe-implementation-gap-analysis-20260726-r021.json` | `f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a` | 488,160 |
| canonical Backlog r021 | `docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r021.json` | `bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0` | 59,266 |

- artifact complete-equivalent `126/257`, open `131`
- formal `0/279`, actual-device `0`, actual external event `0`
- closed release gate `0/5`, release `NOT_ELIGIBLE`
- ready frontier `WS-GOAL-EPIC-03`, `WS-GOAL-EPIC-12`
- canonical r022, physical P/M candidate와 active v2.5: 부재

## 3. 검수 완료된 준비 입력

### 3.1 exact68 r022 데이터 후보 R002

`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/`
아래 6개 파일은
`PASS_REVIEWED_NON_EFFECTIVE_STAGED_CANDIDATE`, findings `0/0/0`이다.

| 파일 | SHA-256 | bytes |
|---|---:|---:|
| `ACTIVATION-APPROVAL-RUNBOOK-R002.md` | `71e322de393027d3cefaf20c2e7b20e64b94d0106b044f6f1cc4a26f0e5b8bd7` | 3,868 |
| `INDEPENDENT-REVIEW-R001.md` | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |
| `exact68-reassessment-ledger.json` | `82ecdfbfc9a35d39f1f8648971df62c18faf861745ccc9a28f5b24d8d354493f` | 189,900 |
| `gap-backlog-pair-manifest.json` | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| `implementation-backlog-r022.candidate.json` | `8e2c9cc565ffdd1039f7e03fbfefed348e568d8b62a7750ff06f989a0b20f098` | 58,007 |
| `implementation-gap-r022.candidate.json` | `3dee2cccad7fb264077dc8858ac3940821af6b4cda0e7664c3e8809b69dc8595` | 535,938 |

결과는 exact68, changed31, byte-equivalent carry37, status change8이며 상태 수는
`B5/C14/E4/M6/P39/I0`이다. canonical 적용이나 v2.5 활성화 권한은 없다.

### 3.2 FP-008 preparation

| 파일 | SHA-256 | bytes | 상태 |
|---|---:|---:|---|
| `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/FP008-NEXT-LEAF-PREPARATION-R001.md` | `83f806e4596833811eaf8fc563df647eceed44e242f994a4d734b498a4b62ac0` | 81,321 | preparation only |
| `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/FP008-NEXT-LEAF-PREPARATION-R001-independent-review-r001.md` | `4b9ffac9f263d9e65d5f20f5de6b2cda76f5430fb66c706a691c80e6d21027bb` | 3,677 | findings 0/0/0 |

판정은 `PASS_FOR_PREPARATION_ONLY_NOT_MATERIALIZED_NOT_AUTHORIZED`다. R007도
`FP008_AUTHORIZATION=ABSENT_DENY_ALL`을 유지한다.

### 3.3 제어설계 succession

공통 prefix는
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/`이다.

| revision | design SHA / bytes | review SHA / bytes | 판정 |
|---|---|---|---|
| R002 | `718e1c06549eb286d9b55abf8648b46b8831af4ef862b258de6290994987c47c` / 29,640 | `0da1597b0480bbde76b1bf58958d1c87018fe705423d77db9da9ed021028962d` / 8,252 | FAIL, successor required |
| R003 | `aa60c7788779fd86746af4b82e9e62a56f33e34553d39e1c48a55b649ac9c908` / 74,328 | `bc3628500e5f560d06b63b9fb38b39312f4aeecdf4b46f932821c930f2040220` / 8,588 | FAIL, successor required |
| R004 | `f44085550511eb346b8ddebc5db88cd5bb69160cc93b10bf78e50049fb555fcc` / 50,508 | `d8335e91787bb6ca496813efb1804b619514cb7b4b3ecac7a6d4c369f96b801d` / 6,105 | FAIL, successor R005 |
| R005 | `4cfcd51904038d38b5ab82b097da375c09e953e0182be47669a147e5c99bef67` / 53,409 | `c7bdf87e5ffb4deae8ad81f81bacb221e37c7fd322c8aee37fb5f1fae1f5e1a4` / 10,005 | FAIL, successor R006 |
| R006 | `06b6d7f16f54b1ce034aee7d89addd54796fcc4a5053fc5d436c8df3eb50238a` / 72,003 | `f74f1c893f9bb9bcb3dbca2497c84051ec6e601b17aff28db4c254505243645f` / 5,240 | FAIL 0/2/0, successor R007 |
| R007 | `2c0eb19da8b9a2df77cb43b2ffd169583591f0a23150d9ef5eff00cf39327610` / 77,365 | `31c7b160c2f94de8f1b9b47e05a8a8a28b03bb93296ecb5878da2ba1175c71c9` / 4,867 | PASS design-only, source drift blocked |

R007 review 판정은 `PASS_FOR_DESIGN_ONLY_SOURCE_DRIFT_BLOCKED`, findings
`BLOCKING=0 MAJOR=0 MINOR=0`이다. P17, M15, external A0/R0/L/B, one-use P/M
approval, crash recovery, full15 REF DAG와 candidate-independent ACTIVE inventory
계약을 설계 수준에서 닫는다. 구현·발행·승인·적용은 하지 않았다.

`r022-candidate-r002/`는 데이터 후보이고
`R022-CONTROL-MIGRATION-CANDIDATE-R002.md`는 실패한 제어설계다. 이름이 같아도
서로 다른 물리 객체이므로 혼동하지 않는다.

## 4. 실행을 막는 source snapshot drift

공식 읽기 전용 재계산 결과:

```text
file_count=603
path_set_sha256=e445b7ccd8b76ef476248894e3d2f84eba5d2b3ba90e2be198b07c37e2d767b1
expected_content_set_sha256=69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a
live_content_set_sha256=51fa51b966219dc4d8c1ff8317e7209323726298365b4ec413b2520bdc90bf1b
```

차이는 신뢰성 있게 한 파일로 격리됐다.

| 상태 | 경로 | SHA-256 | bytes | mode |
|---|---|---:|---:|---:|
| checkpoint 기대 | `scripts/run_walksafe_test_layers_20260711.sh` | `4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d` | 15,588 | target metadata는 별도 보존 |
| live | `scripts/run_walksafe_test_layers_20260711.sh` | `4280dabbaa60ec098c8c52dc278f95b178ac5b2a6aa7dc2a1feae419f81f5b0f` | 15,889 | `0775` |

live에는 다음 테스트 등록 5줄이 먼저 들어갔다.

```text
tests/test_walksafe_w3_engineering_evidence_20260726.py
tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
tests/test_walksafe_phase1_exact257_successor_r011_20260729.py
tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py
tests/test_walksafe_v2_5_control_candidate_20260730.py
```

기대 bytes와 동일한 read-only 증거 복제본은 다음 두 경로에 있으며 둘 다
`4f75501a...b42d`, 15,588 bytes다. 복제본 mode `0664`는 target metadata의
권위가 아니다.

```text
docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-001/gitleaks-current-tree-input/scripts/run_walksafe_test_layers_20260711.sh
docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-002/gitleaks-current-tree-input/scripts/run_walksafe_test_layers_20260711.sh
```

나머지 602개 live hash를 유지하고 이 한 파일만 expected hash로 대입하면 aggregate가
정확히 `69464310...230a`로 돌아간다. 현재 v2.4 continuation과 Goal quick은 이
drift 때문에 의도대로 rc=1 FAIL이다. 과거 R003의 quick PASS를 현재 결과로
재사용하지 않는다.

### 사용자가 선택해야 할 두 전략

1. `CHECKPOINT_PROJECTED_SOURCE_RESTORE`

   현재 5줄 delta를 add-only recovery artifact에 exact 보존한 뒤 target bytes만
   before `4280...`에서 expected `4f755...`로 CAS 복원한다. target mode `0775`와
   metadata를 보존하고 snapshot `694643...`, v2.4 quick 두 개 PASS를 확인한 뒤
   정식 P candidate에서 필요한 5줄을 다시 검토한다. 변경을 덮을 수 있으므로
   정확한 경로·before/after·보존 artifact를 포함한 새 사용자 지시 전에는 실행하지
   않는다. 현재 증거상 더 작고 권장되는 전략이다.

2. `REVIEWED_ADD_ONLY_EXACT_SNAPSHOT_ACCEPTANCE`

   live `4280...`을 유지하려면 단순 checkpoint hash 교체가 아니라 runner와 5개
   untracked test의 exact bytes, Git index/status, tracked/untracked/ignored
   inventory, old/new aggregate와 역대입 증명을 담은 새 source-snapshot manifest,
   독립검수와 exact 사용자 승인이 필요하다. R007의 clean-seq39/P index-10 CAS
   전제도 successor 설계로 다시 검수해야 한다.

일반적인 “계속 진행”, 과거 승인 또는 R007 findings-zero는 어느 전략도 선택하지
않는다.

## 5. 물리적으로 부재하거나 권한이 없는 것

다음은 모두 부재해야 한다.

```text
docs/control/audits/walksafe-implementation-gap-analysis-20260726-r022.json
docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r022.json
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-4-selector-preflight-r007
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r007
docs/control/goals/walksafe-completion-graph-v2-5
docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-4-SELECTOR-PREFLIGHT-20260730-001
docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-001
```

preinstalled privileged A0/R0/B service와 candidate/review-bound L_P/L_M은 현재
검증되지 않았다. repository 안에서 synthetic key, ACL, journal, approval request
또는 receipt를 만들어 대체하지 않는다.

다음 local prototypes는 존재하지만 R007 candidate가 아니며 출판·적용 근거가
아니다.

| 파일 | 관찰 SHA-256 | bytes |
|---|---:|---:|
| `scripts/build_walksafe_v2_5_control_candidate_20260730.py` | `31e66d3ba43764e3d4cc5987813f39d5a70ad9796873690e37875191458d30f3` | 48,501 |
| `scripts/walksafe_v2_5_candidate_validation.py` | `423a60a3195313b11f65789c91ba772bb3bc3213c63bba4ec23c30f3e69434d5` | 170,867 |
| `scripts/check_walksafe_project_continuation_v2_5_candidate.py` | `1a2dc4f1d8c28c5163cae8306a160e8f942f962ea2ec190a625b64dc688ece5c` | 2,513 |
| `scripts/check_walksafe_goal_graph_v2_5_candidate.py` | `98d656e8c3c54e122a419eee9d2c3b3285f2594f4abd3abbb5597d8e9470feda` | 2,434 |
| `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `905eae38d7d24977be2b04fc1c7b6d83c432776c7129939f3d2af1de527cc31f` | 43,318 |

## 6. 새 터미널 읽기 순서

이 R004를 전달 시 한 번 읽은 뒤 다음 repository 파일을 순서대로 읽는다.

1. `AGENTS.md`
2. `docs/control/walksafe-project-resumption-runbook.md`
3. `docs/control/goals/walksafe-completion-graph-v2-3/README.md`
4. `docs/control/goals/README.md`
5. `docs/control/goals/walksafe-completion-graph-v2-4/README.md`
6. `docs/control/goals/walksafe-completion-graph-v2-2/00-master-goal.md`
7. `docs/control/walksafe-project-continuation-checkpoint.json`
8. `docs/control/goals/walksafe-completion-graph-v2-4/static-plan-manifest-v2.4.0.json`
9. `CONTINUATION-PLAN-HANDOFF-20260729-R003.md`
10. `CONTINUATION-PLAN-HANDOFF-20260729-R003-independent-review-r001.md`
11. `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/INDEPENDENT-REVIEW-R001.md`
12. `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/FP008-NEXT-LEAF-PREPARATION-R001-independent-review-r001.md`
13. `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R006-independent-review-r001.md`
14. `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R007.md`
15. `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R007-independent-review-r001.md`
16. 이 R004의 add-only independent-review receipt

9~10의 공통 prefix는
`docs/control/execution/artifact-closure/run-20260727-001/`이다.

## 7. fail-fast 재개 확인

아래는 현재 blocked state를 읽기 전용으로 확인한다. PASS 상태로 바꾸지 않는다.

```bash
(
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
set -euo pipefail

test "$(git branch --show-current)" = 'codex/walksafe-rc2-hardening-20260715'
test "$(git rev-parse HEAD)" = 'a3ad7eead6b5d834d3e0675422475a9aad351e3d'

ws_check_file() {
  local ws_sha="$1" ws_bytes="$2" ws_path="$3"
  test -f "$ws_path"
  test "$(sha256sum "$ws_path" | cut -d' ' -f1)" = "$ws_sha"
  test "$(wc -c < "$ws_path")" -eq "$ws_bytes"
}

while IFS=$'\t' read -r ws_sha ws_bytes ws_path; do
  ws_check_file "$ws_sha" "$ws_bytes" "$ws_path"
done <<'WS_PINS'
6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c	1329415	docs/control/walksafe-project-continuation-checkpoint.json
7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07	39534	docs/control/goals/walksafe-completion-graph-v2-4/static-plan-manifest-v2.4.0.json
f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a	488160	docs/control/audits/walksafe-implementation-gap-analysis-20260726-r021.json
bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0	59266	docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r021.json
71e322de393027d3cefaf20c2e7b20e64b94d0106b044f6f1cc4a26f0e5b8bd7	3868	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/ACTIVATION-APPROVAL-RUNBOOK-R002.md
f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7	2366	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/INDEPENDENT-REVIEW-R001.md
82ecdfbfc9a35d39f1f8648971df62c18faf861745ccc9a28f5b24d8d354493f	189900	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/exact68-reassessment-ledger.json
7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08	12972	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/gap-backlog-pair-manifest.json
8e2c9cc565ffdd1039f7e03fbfefed348e568d8b62a7750ff06f989a0b20f098	58007	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/implementation-backlog-r022.candidate.json
3dee2cccad7fb264077dc8858ac3940821af6b4cda0e7664c3e8809b69dc8595	535938	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r022-candidate-r002/implementation-gap-r022.candidate.json
83f806e4596833811eaf8fc563df647eceed44e242f994a4d734b498a4b62ac0	81321	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/FP008-NEXT-LEAF-PREPARATION-R001.md
4b9ffac9f263d9e65d5f20f5de6b2cda76f5430fb66c706a691c80e6d21027bb	3677	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/FP008-NEXT-LEAF-PREPARATION-R001-independent-review-r001.md
718e1c06549eb286d9b55abf8648b46b8831af4ef862b258de6290994987c47c	29640	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R002.md
0da1597b0480bbde76b1bf58958d1c87018fe705423d77db9da9ed021028962d	8252	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R002-independent-review-r001.md
aa60c7788779fd86746af4b82e9e62a56f33e34553d39e1c48a55b649ac9c908	74328	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R003.md
bc3628500e5f560d06b63b9fb38b39312f4aeecdf4b46f932821c930f2040220	8588	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R003-independent-review-r001.md
f44085550511eb346b8ddebc5db88cd5bb69160cc93b10bf78e50049fb555fcc	50508	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R004.md
d8335e91787bb6ca496813efb1804b619514cb7b4b3ecac7a6d4c369f96b801d	6105	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R004-independent-review-r001.md
4cfcd51904038d38b5ab82b097da375c09e953e0182be47669a147e5c99bef67	53409	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R005.md
c7bdf87e5ffb4deae8ad81f81bacb221e37c7fd322c8aee37fb5f1fae1f5e1a4	10005	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R005-independent-review-r001.md
06b6d7f16f54b1ce034aee7d89addd54796fcc4a5053fc5d436c8df3eb50238a	72003	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R006.md
f74f1c893f9bb9bcb3dbca2497c84051ec6e601b17aff28db4c254505243645f	5240	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R006-independent-review-r001.md
2c0eb19da8b9a2df77cb43b2ffd169583591f0a23150d9ef5eff00cf39327610	77365	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R007.md
31c7b160c2f94de8f1b9b47e05a8a8a28b03bb93296ecb5878da2ba1175c71c9	4867	plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R022-CONTROL-MIGRATION-CANDIDATE-R007-independent-review-r001.md
e9f129ef1113476c9b2982ce2ce7368878f1b9e79c02c5cfd20f6a23916ae040	16110	docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-PLAN-HANDOFF-20260729-R003.md
4138721659c32ec7ed904982d91b1bb567703ed73df80a699fb673f7770aa122	3387	docs/control/execution/artifact-closure/run-20260727-001/CONTINUATION-PLAN-HANDOFF-20260729-R003-independent-review-r001.md
WS_PINS

ws_snapshot="$(
  python3 -B scripts/check_walksafe_project_continuation_v2_4.py \
    --root . \
    --checkpoint docs/control/walksafe-project-continuation-checkpoint.json \
    --print-working-snapshot-hashes
)"
test "$(jq -r .base_commit <<<"$ws_snapshot")" = \
  'a3ad7eead6b5d834d3e0675422475a9aad351e3d'
test "$(jq -r .current_head <<<"$ws_snapshot")" = \
  'a3ad7eead6b5d834d3e0675422475a9aad351e3d'
test "$(jq -r .file_count <<<"$ws_snapshot")" -eq 603
test "$(jq -r .path_set_sha256 <<<"$ws_snapshot")" = \
  'e445b7ccd8b76ef476248894e3d2f84eba5d2b3ba90e2be198b07c37e2d767b1'
test "$(jq -r .content_set_sha256 <<<"$ws_snapshot")" = \
  '51fa51b966219dc4d8c1ff8317e7209323726298365b4ec413b2520bdc90bf1b'

ws_check_file \
  4280dabbaa60ec098c8c52dc278f95b178ac5b2a6aa7dc2a1feae419f81f5b0f \
  15889 scripts/run_walksafe_test_layers_20260711.sh
test "$(stat -c '%a' scripts/run_walksafe_test_layers_20260711.sh)" = 775

for ws_copy in \
  docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-001/gitleaks-current-tree-input/scripts/run_walksafe_test_layers_20260711.sh \
  docs/control/execution/artifact-remediation/20260726/w5/security-scan-execution/run-20260727-002/gitleaks-current-tree-input/scripts/run_walksafe_test_layers_20260711.sh
do
  ws_check_file \
    4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d \
    15588 "$ws_copy"
done

if ws_continuation="$(
  python3 -B scripts/check_walksafe_project_continuation_v2_4.py \
    --root . \
    --checkpoint docs/control/walksafe-project-continuation-checkpoint.json 2>&1
)"; then
  exit 1
fi
grep -Fq 'v2.4 seq39 checkpoint projection differs' <<<"$ws_continuation"
grep -Fq 'v2.4 working snapshot content-set SHA-256 differs' <<<"$ws_continuation"

if ws_goal="$(
  python3 -B scripts/check_walksafe_goal_graph_v2_4.py \
    --root . \
    --checkpoint docs/control/walksafe-project-continuation-checkpoint.json 2>&1
)"; then
  exit 1
fi
grep -Fq 'v2.4 seq39 checkpoint projection differs' <<<"$ws_goal"
grep -Fq 'v2.4 working snapshot content-set SHA-256 differs' <<<"$ws_goal"
grep -Fq 'v2.4 seq39 queue projection' <<<"$ws_goal"

for ws_absent in \
  docs/control/audits/walksafe-implementation-gap-analysis-20260726-r022.json \
  docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r022.json \
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-4-selector-preflight-r007 \
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r007 \
  docs/control/goals/walksafe-completion-graph-v2-5 \
  docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-4-SELECTOR-PREFLIGHT-20260730-001 \
  docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-001
do
  test ! -e "$ws_absent"
done
)
```

branch, HEAD, pin, snapshot, expected FAIL reason 또는 absence가 하나라도 다르면
source 조정이나 실행을 시작하지 말고 drift 원인을 먼저 보고한다.

## 8. 다음 실행 DAG와 승인 경계

### 단계 0: source 전략 선택

사용자에게 §4의 두 전략, 정확한 단일 파일 before/after와 덮어쓰기 위험을 먼저
보고한다. 사용자가 exact 전략을 명시하기 전에는 source를 고치거나 checkpoint를
바꾸지 않는다. 선택 뒤에도 add-only 보존/manifest, 독립검수와 v2.4 quick 두 개
PASS를 먼저 확인한다.

### 단계 1: P selector-preflight

```text
drift 0
→ 별도 P build 지시
→ v2-4-selector-preflight-r007 deterministic build/freeze
→ candidate-bound independent review findings 0
→ verified external A0/R0/L_P/B
→ candidate-specific fresh SELECTOR_PREFLIGHT_ONLY challenge/approval
→ P apply/post-check/receipt
→ sequence 40 selector-ready
```

이 단계의 user response, nonce, claim과 receipt는 M에 재사용하지 않는다.

### 단계 2: M v2.5/r022 transition

```text
exact seq40 + P17 + P receipt
→ 별도 M build 지시
→ v2-5-control-candidate-r007 deterministic build/freeze
→ candidate-bound independent review findings 0
→ verified external A0/R0/L_M/B
→ candidate-specific fresh V25_MAIN_TRANSITION_ONLY challenge/approval
→ M apply/post-check/receipt
```

P와 M을 한 request로 합치거나 과거의 “승인합니다”를 재사용하지 않는다.
full19는 M receipt 뒤의 non-authorizing validation evidence일 뿐이다.

### 단계 3: FP-008

R007은 FP-008을 deny-all한다. 별도 add-only successor design, 독립검수, 새
challenge/approval와 Goal materialization/start 전에는 제품 구현을 시작하지
않는다.

## 9. 금지선과 새 터미널 첫 보고

- frozen R002~R007, 그 review와 R003/R004 handoff를 제자리 수정하지 않는다.
- `git reset --hard`, `git checkout --`, `git clean`, 광범위 삭제를 하지 않는다.
- dirty/untracked 사용자 산출물을 정리·정본화·덮어쓰지 않는다.
- synthetic A0/R0/L/B, authorization request/response/receipt를 만들지 않는다.
- checkpoint hash만 live aggregate로 바꿔 과거 승인을 재사용하지 않는다.
- canonical r022, Goal/event, FP-008, product, formal/device, artifact/gate/release
  상태를 evidence 없이 변경하지 않는다.
- R007 findings-zero를 구현 PASS, 제품 QA 또는 release eligibility로 표현하지
  않는다.

새 터미널은 읽기와 fail-fast 확인 뒤 사용자에게 다음만 먼저 보고한다.

```text
R007 design review is findings-zero, but execution is blocked by one-file source drift.
The next user decision is CHECKPOINT_PROJECTED_SOURCE_RESTORE or
REVIEWED_ADD_ONLY_EXACT_SNAPSHOT_ACCEPTANCE.
No canonical, Goal, product, artifact, gate, or release state has changed.
```

이 R004의 최종 SHA-256/bytes와 handoff findings는 본문 밖의 add-only
`CONTINUATION-EXECUTION-HANDOFF-20260730-R004-independent-review-r001.md`가
결속한다. receipt가 없거나 target pin이 다르거나 findings가 0이 아니면 이
인계를 사용해 source 조정이나 실행을 시작하지 않는다.
