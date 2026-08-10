# WalkSafe 프로젝트 기술 재개 인계서 20260731 R001

- 문서 ID: `WS-WALKSAFE-PROJECT-TECHNICAL-HANDOFF-20260731-R001`
- 상태: `HANDOFF_CANDIDATE_NON_EFFECTIVE`
- 작성일: `2026-07-31`
- 저장소:
  `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715`
- branch: `codex/walksafe-rc2-hardening-20260715`
- HEAD/base: `a3ad7eead6b5d834d3e0675422475a9aad351e3d`
- 정책 기준선: `PB-WALKSAFE-FEATURE-POLICY-1.0.1`
- 이 문서 자체의 독립 물리검수:
  `NOT_YET_BOUND_AT_TARGET_FREEZE`; 실제 adjacent 검토서는 이 target 밖에서
  확인
- 효력: 기술 재개와 계획 설명 전용
- 금지: canonical, Goal, checkpoint, artifact, formal, device, Gate,
  production, release 상태 변경 또는 완료 credit 생성

## 0. 효력과 기존 종합 인계서와의 관계

이 문서는 다음 종합 인계서를 폐기하지 않는다.

| 역할 | 경로 | SHA-256 / bytes | 판정 |
|---|---|---|---|
| 종합 인계서 | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-PROJECT-MASTER-HANDOFF-20260730-R001.md` | `5b60ca48becd3634410c68ea6de5863f45881972eb597f0625c8556a4f03a48b` / `29,672` | `HANDOFF_CANDIDATE_NON_EFFECTIVE` |
| 종합 인계 독립검수 | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-PROJECT-MASTER-HANDOFF-20260730-R001-independent-review-r001.md` | `a7a25764a7c43b03b62c4ab5daf2e406e5d9fcc232510217630c63e1082c18a9` / `4,145` | `BLOCKING/MAJOR/MINOR=0/0/0` |

이 문서가 종합 인계서보다 새 사실로 대체하는 범위는 다음뿐이다.

1. 종합 인계서 §3의 runner 5줄 source drift는 권고 전략
   `CHECKPOINT_PROJECTED_SOURCE_RESTORE`로 종결됐다.
2. 종합 인계서 §5의 `S0`은 완료됐다.
3. 종합 인계서 §7과 §11의 “전략 A/B를 고르고 5줄을 복원하라”는 다음
   행동은 더 이상 실행하지 않는다.
4. 종합 인계서 §5 `S1`의 selector-preflight 목적은 유지하지만, 그 절의
   exact seq39→40/P17 실행 경로, §7.3의 후속 P 준비 순서와 §11의 old
   P-build prompt는 history-only다. accepted replacement는 아직 없다.
   §7.3의 frozen PRE-P R007과 두 physical review는 모두 `18/4/0`으로
   rejected/deferred이므로 실행 successor가 아니며 `P17_BUILD_DEFERRED`다.

프로젝트 목적, 공식 상태의 의미, S1 selector-preflight 목적, S2~S9의
장기 목적·경계, 승인·중단 경계와 257개 산출물 경계는 종합 인계서를 계속
따른다. Master S1의 구체 실행 sequence는 history-only이고 §7.3은 거절된
후보의 결함 provenance다. 이 문서는 2026-07-31의 검증된 기술 delta와 새
차단점을 보충한다.

충돌 시 우선순위는 다음과 같다.

```text
live v2.4 checkpoint와 대상별 유효 findings-zero receipt
> 승인된 정책 기준선
> 이 기술 인계서의 좁은 S0 완료·S1 거절/deferred 사실
> 20260730 종합 인계서의 나머지 범위
> 오래된 계획·실패 인계 이력
```

이 우선순위는 새 효력을 만드는 규칙이 아니다. 이미 유효한 정본을 읽는
규칙이다.

## 1. 약 60초 재개법

새 Codex는 첫 write 전에 아래 순서만 수행한다.

1. 이 문서 §0, §3, §4, §6, §7, §8, §10을 읽는다.
2. 이 문서의 adjacent 독립 물리검수가 실제로 존재하면 대상 SHA-256,
   bytes와 `0/0/0`을 확인한다. 없으면 검수된 재개 기준으로도 사용하지 않는다.
   검수가 있어도 그것만으로 실행 권한이 생기지는 않는다.
3. 종합 인계서와 그 독립검수, live checkpoint, 이 날 daylog를 읽는다.
4. 아래 v2.4 Quick2를 읽기 전용으로 실행한다.
5. §7의 `PENDING_FINAL_BINDING`이 남아 있으면 설계·후보·정본 write를
   시작하지 않고 현재 사실만 보고한다.

```bash
cd /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715

python3 -B scripts/check_walksafe_project_continuation_v2_4.py \
  --root . \
  --checkpoint docs/control/walksafe-project-continuation-checkpoint.json

python3 -B scripts/check_walksafe_goal_graph_v2_4.py \
  --root . \
  --checkpoint docs/control/walksafe-project-continuation-checkpoint.json
```

기대 관찰은 두 명령 모두 `PASS`다. 이것은 repository 재개 무결성만
확인한다. 제품 완성, 전체 테스트 성공, formal 시험, 실제 기기, Gate 또는
출시 PASS를 뜻하지 않는다.

## 2. 프로젝트 목적과 쉬운 설명

WalkSafe는 시각장애인의 도심 보행을 보조하는 Android 제품을 완성하려는
프로젝트다. 사용자 Android 앱, 별도 비공개 관리자 Android 앱, Android
Gateway, Backend, 온디바이스 모델과 운영·시험 증거가 함께 제품 경계를
이룬다. Web/PWA는 `LEGACY_REFERENCE_ONLY`다.

비전공자에게 보여 줄 쉬운 설명은 다음 문서를 우선 사용한다.

[비전공자용 요약 `docs/walksafe-overview.md`](../../../../walksafe-overview.md)

이 문서도 공식 승인이나 출시 판단 문서가 아니다. 안전시험 전에는
WalkSafe가 흰지팡이·안내견·보호자를 대체하거나 보행 안전을 보장한다고
주장하지 않는다.

## 3. 현재 공식 상태

### 3.1 실행 정본

| 항목 | 현재 값 |
|---|---|
| control package | v2.4 `ACTIVE` |
| checkpoint tail | sequence `39`, `CANONICAL_BINDINGS_UPDATED` |
| canonical Gap / Backlog | r021 / r021 |
| Goal focus | `WS-GOAL-EPIC-03`, Workstream `READY` |
| ready frontier | `EPIC-03`, `EPIC-12` |
| materialized focus leaf | 없음 |
| canonical r022 | 없음 |
| active v2.5 | 없음 |
| P/M physical candidate | 없음 |

정본 물리 결속은 다음과 같다.

| 경로 | SHA-256 | bytes |
|---|---|---:|
| `docs/control/walksafe-project-continuation-checkpoint.json` | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | `1,329,415` |
| `docs/control/goals/walksafe-completion-graph-v2-4/static-plan-manifest-v2.4.0.json` | `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07` | `39,534` |
| `docs/control/audits/walksafe-implementation-gap-analysis-20260726-r021.json` | `f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a` | `488,160` |
| `docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r021.json` | `bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0` | `59,266` |

### 3.2 제품·증거 축

| 축 | 공식 현재값 | 정확한 의미 |
|---|---:|---|
| artifact closed-equivalent | `126/257` | seq39에 보존된 artifact projection. 전체 프로젝트 진척률이 아님 |
| artifact open | `131/257` | 내용·결정·실행·사건 근거가 더 필요함 |
| formal test | PASS `0/279` | 279개 전부 `NOT_RUN` |
| actual device / real event | `0 / 0` | 내부 테스트로 대신할 수 없음 |
| release Gate | `0/5` | 전부 `NOT_RUN`, 전부 `waived=false` |
| production deployment | `0` | deploy·canary·rollback 증거 없음 |
| release | `NOT_ELIGIBLE` | 프로젝트 완료·출시 아님 |

`126/257`은 약 49%라는 산술 표현으로 바꿔 전체 완성률처럼 말하지 않는다.
Feature, Artifact, 내부 검증, Formal, Device/Event, Gate, Release는 서로
다른 축이다.

2026-07-31 작업으로 제품 경로, checkpoint, canonical r021, Goal event,
artifact 수치, formal, device/event, Gate, production 또는 release 상태는
오르지 않았다.

## 4. S0 source convergence 완료와 남은 runner 기술부채

### 4.1 완료된 최소 복원

`CHECKPOINT_PROJECTED_SOURCE_RESTORE`로 다음 한 파일의 확인된 테스트 등록
5줄만 제거했다.

`scripts/run_walksafe_test_layers_20260711.sh`

| 시점 | SHA-256 | bytes | mode |
|---|---|---:|---:|
| 복원 전 | `4280dabbaa60ec098c8c52dc278f95b178ac5b2a6aa7dc2a1feae419f81f5b0f` | `15,889` | `0775` |
| 복원 후 | `4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d` | `15,588` | `0775` |

복원 후 bytes는 checkpoint와 두 sealed evidence copy에 exact 일치한다.
제거된 등록 5줄이 가리키던 테스트 파일은 삭제하거나 수정하지 않았다.

```text
tests/test_walksafe_w3_engineering_evidence_20260726.py
tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
tests/test_walksafe_phase1_exact257_successor_r011_20260729.py
tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py
tests/test_walksafe_v2_5_control_candidate_20260730.py
```

managed snapshot은 다음 값으로 수렴했다.

| 항목 | 현재·checkpoint expected |
|---|---|
| file count | `603` |
| path-set SHA-256 | `e445b7ccd8b76ef476248894e3d2f84eba5d2b3ba90e2be198b07c37e2d767b1` |
| content-set SHA-256 | `69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a` |

v2.4 continuation과 v2.4 Goal graph Quick2는 모두 PASS했다.

### 4.2 Quick2와 live runner는 다른 검사다

Quick2는 checkpoint가 관리하는 seq39 repository projection을 검사한다.
반면 live layer runner는 현재 작업트리에서 발견되는 모든 `test_*.py`를
layer에 배정했는지 검사한다.

현재 live 관찰은 다음과 같다.

```text
discovered Python tests = 132
assigned to runner layers = 127
unassigned = 5
runner validate rc = 2
first reported unassigned =
  tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
```

따라서 다음 두 문장은 동시에 참이다.

- seq39 repository 재개 무결성은 복원돼 Quick2가 PASS한다.
- 현재 live layer runner는 5개 테스트가 미배정이라 실행 전에 rc 2로
  중단된다.

runner를 다시 고치거나 checkpoint snapshot을 바꾸는 일은 별도 successor
설계·검수·권한 없이 수행하지 않는다.

## 5. 2026-07-31 준비 문서와 검수 경계

오늘 생성한 문서는 작업 준비와 이해도를 높였지만 공식 상태를 바꾸지 않는다.

| 문서 | 물리 결속 | 현재 disposition |
|---|---|---|
| `docs/walksafe-overview.md` | `bef1580449e96d2dae870037aba1433e0e6cfc190424372f32db032746edb630` / `6,390` bytes / `88` lines | 외부 비전공자용 설명, 비권한 |
| `docs/control/execution/artifact-audits/20260727/final-257/preparation-20260731/DLV-DEV-17-DLV-REL-22-license-evidence-map-r001.md` | `9aa78cf385868cdcf45f36430a64c8d4d7d9cd5d89f7641a29fa5dcf388aa327` / `24,318` / `302` | `NONCANONICAL_PREPARATION_ONLY`, license·attribution trace 준비 |
| `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R004-LANE-A-DLV-TST-18-21-FORMAL-ZERO-WORK-CONTRACT-R001.md` | `ff2d3c983d76ddc7be558ea743d86ff046f09fb8a77bebd64325a81b00d74c65` / `32,494` / `633` | `NONCANONICAL_DRAFT`, `EXECUTION_STARTED=false` |
| `docs/control/execution/artifact-audits/20260727/final-257/remaining-work-execution-plan-r004-b3-independent-qa-preverdict-r001.md` | `253cbd79b2e63cf53f5fd3f326d69387355e7750eefce32a5403e722062128b4` / `22,322` / `389` | `NONCANONICAL_BLOCKER_MEMO`, 실제 QA verdict 아님 |
| `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/W3-ENGINEERING-EVIDENCE-SUCCESSOR-WORK-CONTRACT-R001.md` | `e23e3107b19ecf1d554a35ce1d38e60bf9064941c75d1e685c7270938e3ecf3b` / `21,156` / `366` | plan-only, implementation authority 없음 |
| `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/R004-LANE-A-DLV-REL-16-INSTALLATION-CONTENT-WORK-CONTRACT-R001.md` | `b8f4063b7995aa34f1917af72866afdd09b44d9524ec15e51be8d7a4bca1ee67` / `21,544` / `394` | `NONCANONICAL_DRAFT`, `GO_PLAN_ONLY`, 실행 `NO-GO` |

이 중 adjacent 독립 물리검수 파일이 실제로 있는 것은 W3 계획뿐이다.

| 대상 | 물리 독립검수 | 판정 |
|---|---|---|
| W3 plan-only 계약 | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/W3-ENGINEERING-EVIDENCE-SUCCESSOR-WORK-CONTRACT-INDEPENDENT-REVIEW-R001.md` / `39899375b3dc9dfe3a4c3649bfe9b6572ec14b97afc4c1be126f436bb388f5a4` / `6,311` bytes / `169` lines | `0/0/0`, `GO_PLAN_ONLY / NO_GO_IMPLEMENTATION` |

나머지 문서는 작성 과정의 독립 검토 보고가 findings-zero였더라도 그 결과를
adjacent immutable physical review receipt로 과장하지 않는다. 특히 license,
formal-zero, B3, REL-16 문서는 artifact 승인, 실제 QA 판정, 실행 시작,
closure 또는 release credit를 만들지 않는다.

## 6. bounded regression 관찰

### 6.1 실행 범위와 claim ceiling

첫 bounded product/static suite의 관찰은 다음과 같다.

```text
total = 457
passed = 450
failed = 7
second planned suite = 241 tests, NOT_RUN
immutable raw execution receipt = NOT_MATERIALIZED
```

7개 실패는 네 원인군으로 수렴했다. 이 수치는 transient test observation이며
정식 시험 receipt, formal PASS/FAIL, artifact closure 또는 출시 근거가 아니다.
stop rule에 따라 두 번째 241개 suite는 실행하지 않았다.

### 6.2 네 기술부채 원인

| 원인군 | 현재 사실 | 필요한 successor 경계 |
|---|---|---|
| A. Python lock epoch | `backend/requirements.lock`과 hosted CPU lock은 Pillow `12.3.0`, `tests/requirements.lock`은 `12.2.0` | W5에서 누락된 local test projection을 결정적으로 재생성·검수. 기존 receipt 소급수정 금지 |
| B. runner epoch | preflight는 pytest 호출 `3`을 기대하지만 live runner는 `4`; discovery는 `132/127/5` | legacy runner를 직접 고치지 않는 add-only successor와 current/historical test 분류 |
| C. Gateway epoch | historical 경계는 public exact4, live source는 `/api/field-walk`가 추가된 exact5 | historical exact4와 current exact5를 분리 검증하는 successor |
| D. artifact baseline epoch | 20260722 event-time receipt는 `docs/deliverables/00-control/README.md`의 `b3dde3a3c3f74fd383979eb8ed3a8ac9d6727592ddad0ea8c86e8b5143e07fa3` / `2,630`; current는 `ed331dd2894a8ccd41b22df8c9272858a9e7ca89b090cd3a20942baf35d74c54` / `3,108` | historical replay와 current validation을 분리하고 둘 다 PASS하는 dual control |

현재 `P17_BUILD_DEFERRED`다. 위 실패를 즉석 수정하거나 테스트를 약화해
P17 시작조건을 만든 것으로 취급하지 않는다.

## 7. PRE-P 검증 수렴 계획 lineage

### 7.1 목적

이 lineage는 §6의 네 원인, deterministic candidate·environment, validation
journal, crash recovery와 apply 경계를 설계하기 위한 plan-only 작업이다.
설계 문서가 많아졌다는 사실은 제품이나 artifact 진척이 아니다.

### 7.2 실행 금지된 R001~R006

모든 대상과 검수는
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/` 아래에 있다.

| 대상 계획 파일 | 물리 검수 파일 | 판정 |
|---|---|---|
| `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R001.md` | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R001-independent-review-r001.md` | `REJECTED`, `5/2/0` |
| `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R002.md` | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R002-independent-review-r001.md` | `REJECTED`, `4/2/0` |
| `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R003.md` | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R003-independent-review-r001.md` | `REJECTED`, `4/2/0` |
| `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R004.md` | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R004-independent-review-r001.md`; `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R004-independent-skeptical-review-r001.md` | `REJECTED`, formal `1/2/1`, skeptical `4/2/0` |
| `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R005.md` | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R005-independent-review-r001.md`; `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R005-independent-skeptical-review-r001.md` | `REJECTED`, formal `5/0/0`, skeptical `7/2/0` |
| `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006.md` | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006-independent-review-r001.md`; `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006-independent-skeptical-review-r001.md` | `REJECTED`, formal `12/5/1`, skeptical `8/2/1` |

위 여섯 revision은 결함 추적과 provenance로만 보존한다. 부분적으로 좋은
문단을 골라 standalone 실행계약처럼 사용하지 않는다.

<!-- BEGIN FINAL_DEFERRED_BINDING: PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007 -->
### 7.3 `REJECTED_DEFERRED_NON_EFFECTIVE_PLAN_ONLY` — `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007.md`

- exact target:
  `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007.md`
- target physical:
  `02766312b1bbb00eb05e2789fe4d054cbf749407e6c5bd26dce62250e6dd98ef`
  / `259,476` bytes / `5,350` lines / mode `0664`
- formal physical review:
  `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007-independent-review-r001.md`
  - physical:
    `c2e6c226204d977db9706a58935125b472c829e5790c91ef83e3c866dde51269`
    / `24,036` bytes / `461` lines / mode `0664`
  - verdict: `REJECTED_DEFERRED_NON_EFFECTIVE_DRAFT`,
    `BLOCKING/MAJOR/MINOR=18/4/0`
- skeptical physical review:
  `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007-independent-skeptical-review-r001.md`
  - physical:
    `0bc4fb67ab80acaae69ae8024b7f39156bc5c98d6f6df2e9bfa508fb2c91fe44`
    / `22,102` bytes / `444` lines / mode `0664`
  - verdict: `REJECTED_NON_EFFECTIVE_PLAN_ONLY`,
    `BLOCKING/MAJOR/MINOR=18/4/0`
- combined disposition: `REJECTED_DEFERRED_DO_NOT_EXECUTE`
- 실행 판정: `NO_GO`
- 권한: `ABSENT_DENY_ALL`
- delta:
  `PRODUCT/CHECKPOINT/CANONICAL/GOAL/ARTIFACT/FORMAL/DEVICE_EVENT/GATE/PRODUCTION/RELEASE=0`
- 다음 read-only 행동:
  세 파일의 physical identity, v2.4 Quick2, runner와 managed snapshot을
  재검산하고 두 검수의 blocker를 다음 add-only 설계 입력으로만 보고한다.
- 금지:
  - 이 계획의 일부 문단을 standalone 실행계약으로 재사용
  - journal bootstrap 또는 Stage A/B/C/D/E/F/G 승인 질문
  - candidate build, P17, apply, checkpoint 또는 canonical write

다음 설계 작업은 blocker를 닫는 add-only successor plan과 그 successor에
대한 새 formal·skeptical 독립검수뿐이다.
<!-- END FINAL_DEFERRED_BINDING: PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007 -->

## 8. 권한 상태와 분리 원칙

현재 권한은 모두 없다.

```text
CURRENT_AUTHORITY=ABSENT_DENY_ALL
JOURNAL_BOOTSTRAP_AUTHORITY=ABSENT_DENY_ALL
STAGE_A_AUTHORITY=ABSENT_DENY_ALL
STAGE_B_AUTHORITY=ABSENT_DENY_ALL
STAGE_C_AUTHORITY=ABSENT_DENY_ALL
STAGE_D_AUTHORITY=ABSENT_DENY_ALL
STAGE_E_AUTHORITY=ABSENT_DENY_ALL
STAGE_F_AUTHORITY=ABSENT_DENY_ALL
STAGE_G_AUTHORITY=ABSENT_DENY_ALL
P17_BUILD_AUTHORITY=ABSENT_DENY_ALL
CHECKPOINT_OR_CANONICAL_WRITE_AUTHORITY=ABSENT_DENY_ALL
PRODUCT_OR_RELEASE_AUTHORITY=ABSENT_DENY_ALL
```

다음 승인은 서로 대체할 수 없다.

| 권한 | 허용 범위 | 허용하지 않는 것 |
|---|---|---|
| 계획 freeze·review | exact 계획 subject를 검수 | journal, candidate, source, apply write |
| journal bootstrap 승인 | final 계획이 정한 complete authority root를 한 번 출판 | Stage A candidate/env/pack build |
| Stage A 승인 | exact attempt에 한정된 immutable candidate·environment·pack build와 그 lifecycle evidence | Stage B validation, Stage C apply |
| Stage B 승인 | frozen Stage A subject의 별도 validation | source/canonical/checkpoint apply |
| Stage C 승인 | 최종 계약의 fenced apply·exact post-check·receipt 범위 | 제품 기능 확장이나 release credit |
| Stage D 승인 | committed PRE-P 결과를 입력으로 한 P successor 설계·검수 | P candidate build·resolution·apply |
| Stage E 승인 | exact P candidate build와 candidate-bound review | P resolution·apply |
| Stage F 승인 | reviewed P candidate의 attempt-scoped resolution | P apply |
| Stage G 승인 | exact resolved P subject의 별도 fenced apply | 다른 제품 기능이나 release credit |
| P/M 또는 후속 제품 승인 | 각 exact subject에 적힌 별도 범위 | 앞 단계 승인·nonce·receipt 재사용 |

journal bootstrap 승인을 Stage A 승인으로 읽지 않는다. Stage A 승인을
bootstrap 승인으로 소급하지도 않는다. “계속 진행하라” 같은 일반 지시는
final exact subject, scope, nonce와 중단조건을 결속한 별도 승인 없이 위
권한으로 확장하지 않는다.

## 9. 다음 단계와 보류 항목

### 9.1 이미 끝난 단계

- S0 runner 5줄 복원
- seq39 managed snapshot 수렴
- v2.4 Quick2 PASS
- 쉬운 외부 요약 작성
- license, formal-zero, B3, W3, REL-16의 noncanonical 준비
- bounded regression의 7개 실패를 네 원인군으로 분류

### 9.2 승인 전 안전하게 할 수 있는 단계

1. `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007.md`는 §7.3의
   exact physical identity로 동결됐다.
2. formal·skeptical physical review는 모두 완료됐고 각각 `18/4/0`으로
   rejected/deferred다.
3. 두 검수의 blocker를 중복 없이 보존해 새 add-only successor plan의
   입력으로 만든다.
4. successor가 모든 blocker를 닫은 뒤 새 formal·skeptical 독립검수를
   수행한다.
5. 그 두 검수가 모두 `0/0/0`이 되기 전에는 실행 승인 문구를 준비하거나
   candidate build·apply를 시작하지 않는다.

### 9.3 별도 승인 뒤에만 가능한 단계

현재 별도로 승인할 수 있는 실행 단계는 없다. 아래 전체 downstream은 새
successor가 blocker를 모두 닫고 두 physical review가 각각 `0/0/0`이 될
때까지 `BLOCKED`다. 그 미래 조건 뒤에도 각 실행 권한은 별도다.

```text
new add-only successor closes every R007 formal/skeptical blocker
→ successor formal review 0/0/0
→ successor skeptical review 0/0/0
→ 별도 journal bootstrap 승인
→ bootstrap-only 실행·검증·receipt
→ 별도 Stage A 승인
→ immutable candidate/environment/pack build
→ candidate-bound 독립검수
→ 별도 Stage B validation 승인과 실행
→ 별도 Stage C fenced apply 승인과 실행
→ exact post-check·application receipt
→ 별도 Stage D P-successor 설계·검수
→ 별도 Stage E P17 candidate build·review
→ 별도 Stage F P resolution
→ 별도 Stage G P apply
→ 그때의 live checkpoint에서 다음 frontier 재계산
```

P17 physical candidate는 아직 만들지 않았다. accepted successor와 필요한
별도 권한이 모두 성립하기 전에는 `P17_BUILD_DEFERRED`를 유지한다.

그 이후의 큰 흐름은 20260730 종합 인계서의 S2~S9다.

```text
M v2.5+r022 main transition
→ frontier 재계산과 단일 leaf
→ FP-008을 포함한 실제 제품 slice
→ EPIC coverage
→ 257개 산출물의 실제 내용·승인·실행·사건 근거
→ formal 279, 실제 기기·현장 검증
→ Gate 5개
→ 배포·canary·rollback·운영·이관
```

진행할 수 없는 external authority, 독립 QA, 실제 기기, 실제 사건, cloud,
서명·배포 항목은 상태를 올리지 않고 보류한다.

### 9.4 마지막에 사용자에게 받을 결정

현재 사용자에게 요청할 실행 승인은 없다. 특히 rejected R007을 근거로
journal bootstrap이나 Stage A 승인을 질문하지 않는다.

다음 행동은 blocker를 닫는 add-only successor 설계와 새 독립검수다. 그
successor의 두 검수가 모두 `0/0/0`이 된 뒤에만 첫 exact 실행 권한 질문을
별도로 준비한다.

## 10. 실행하지 않을 stale·실패 이력

### 10.1 stale pointer

- live r021 Backlog/checkpoint의 `current_work`가 가리키는
  `FP-048/GAP-057 준비`는 stale planning pointer다.
- `FP-048`을 지금 materialize하거나 start하지 않는다.
- authoritative next leaf는 유효한 transition 뒤 live frontier를 다시
  계산해서 결정한다.

### 10.2 repository `AGENTS.md`의 v2.3 시작 포인터

`AGENTS.md`의 안전, dirty-tree, 승인, 단일 leaf, daylog와 local-memory
원칙은 지킨다. 다만 시작 파일과 Quick check를 v2.3으로 고정한 부분은
현재 v2.4 ACTIVE sequence 39보다 오래됐다.

- v2.3을 복원하지 않는다.
- v2.3 checker의 예상 불일치를 source regression으로 고치지 않는다.
- 현행 재개 검사는 §1의 v2.4 Quick2다.

### 10.3 실패한 continuation 인계 R004~R009

`CONTINUATION-EXECUTION-HANDOFF-20260730-R004.md`부터
`CONTINUATION-EXECUTION-HANDOFF-20260730-R009.md`까지는 history-only다.
특히 R009 독립검수는 `BLOCKING/MAJOR/MINOR=0/1/0`으로 FAIL이다.
이들 execution block을 실행하거나 새 write의 권한으로 사용하지 않는다.

### 10.4 그 밖의 금지

- PRE-P R001~R006을 실행하지 않는다.
- pending PRE-P final target의 transient hash를 고정하지 않는다.
- r022, v2.5 또는 P/M physical candidate가 있다고 가정하지 않는다.
- W3 plan-only findings-zero를 implementation GO로 읽지 않는다.
- historical receipt를 current bytes로 고치지 않는다.
- Quick2 PASS를 full runner, formal, device, Gate 또는 release PASS로
  확장하지 않는다.
- 큰 dirty working tree에 `git reset --hard`, `git checkout --`,
  `git clean` 또는 광범위 삭제를 하지 않는다.

## 11. 다음 터미널용 read-only 지시문

다음 Codex에는 아래 문장을 우선 전달한다.

```text
먼저 읽기 전용으로 WalkSafe를 재개해. 저장소는
/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715 이고 branch는
codex/walksafe-rc2-hardening-20260715, base HEAD는
a3ad7eead6b5d834d3e0675422475a9aad351e3d야.

다음 순서로 읽어:
1. AGENTS.md. 단 v2.3 시작 포인터는 stale이고 나머지 안전 규칙만 유효해.
2. WALKSAFE-PROJECT-TECHNICAL-HANDOFF-20260731-R001.md와 실제 adjacent
   독립검수. 대상 hash/bytes와 findings 0/0/0이 맞는지 확인해.
3. WALKSAFE-PROJECT-MASTER-HANDOFF-20260730-R001.md와 독립검수.
4. docs/control/walksafe-project-continuation-checkpoint.json.
5. 기술 인계서 §7.3의
   PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007.md와 두 exact
   physical review를 읽고 formal 18/4/0, skeptical 18/4/0과
   REJECTED/DEFERRED를 확인해.
6. daylog/2026-07-31.md.

그 다음 아무 파일도 고치지 말고 v2.4 continuation/Goal Quick2, runner
hash/mode, managed snapshot 603/path/content hash를 재계산해. 공식 상태,
오늘의 preparation delta, bounded regression 450/7과 네 원인,
R007의 두 rejected verdict와 다음 add-only design-only 행동만 보고해.

R004~R009 continuation block, PRE-P R001~R006, stale FP-048 pointer를
실행하지 마. rejected R007도 실행하지 말고 지금 journal bootstrap이나
Stage A 승인을 질문하지 마. Stage A/B/C/D/E/F/G, P17, checkpoint,
canonical, product 또는 release write를 하지 마.
```

## 12. 인계서 freeze·검증 조건과 claim ceiling

이 기술 인계서를 다음 터미널의 검수된 기준으로 쓰려면 모두 충족해야 한다.

- 이 파일의 SHA-256, bytes, lines, mode, LF-only와 NUL 0을 계산함
- adjacent 독립 물리검수가 exact 이 파일을 결속함
- 독립검수 findings가 `BLOCKING/MAJOR/MINOR=0/0/0`임
- 종합 인계서와 review hash가 §0과 일치함
- checkpoint와 정본 네 파일 hash가 §3과 일치함
- runner가 `4f7550...32b42d / 15,588 / 0775`임
- managed snapshot이 `603`과 §4의 두 hash에 일치함
- v2.4 Quick2가 둘 다 PASS함
- §7.3의 rejected/deferred binding과 두 `18/4/0` review를 확인하고
  실행 `NO-GO`를 유지함
- regression raw receipt가 없다는 사실과 두 번째 241개 `NOT_RUN`을 보존함

이 문서 작성으로 생기는 공식 delta는 모두 0이다.

```text
PRODUCT_DELTA=0
CHECKPOINT_DELTA=0
CANONICAL_DELTA=0
GOAL_EVENT_DELTA=0
ARTIFACT_CREDIT_DELTA=0
FORMAL_CREDIT_DELTA=0
DEVICE_EVENT_CREDIT_DELTA=0
GATE_DELTA=0
PRODUCTION_DELTA=0
RELEASE_DELTA=0
```

이 문서의 가장 중요한 결론은 “준비가 끝났다”가 아니다.

```text
S0 source convergence는 끝났다.
live runner와 regression에는 네 원인군의 기술부채가 남았다.
PRE-P R007은 frozen이지만 두 검수 모두 18/4/0으로 rejected/deferred다.
모든 실행 권한은 absent다.
따라서 다음 안전 행동은 blocker를 닫는 add-only successor plan과
그 successor의 새 독립검수이며, candidate build나 apply가 아니다.
```
