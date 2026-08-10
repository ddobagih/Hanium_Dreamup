# WalkSafe 자율 실행 로드맵 R025 — 단순화된 r002 후보 완결

- 상태: `PRE_REVIEW`
- 작성일: `2026-08-02`
- 범위: 비효력 v2.5 `r002` 후보의 소스 교정, 생성, 검증, 독립 검수, 폐쇄
- 권한: 활성화·canonical/checkpoint·Goal·제품·배포 권한 없음

## 1. 목적과 기준선

R025의 유일한 구현 목적은 현재 검증된 R016 소스를 최소 수정해 잘못 봉인된
`v2-5-control-candidate-r001`은 그대로 보존하고, 새
`v2-5-control-candidate-r002`를 add-only로 생성·검수하는 것이다.

다음은 시작 시 다시 확인해야 하는 기준이다.

| 역할 | path | SHA-256 | bytes |
|---|---|---|---:|
| C0 | `docs/control/walksafe-project-continuation-checkpoint.json` | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| reviewed R002 pair | `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r002-reviewed-pair.json` | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| accepted R016 | `docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R016.md` | `49ca083c68d0f2693fa74a7369f46995dbd9e697fcaeb5917c33f1973b8dd8c2` | 12,690 |
| core S0 | `scripts/walksafe_v2_5_candidate_validation.py` | `e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f` | 180,432 |
| builder S0 | `scripts/build_walksafe_v2_5_control_candidate_20260730.py` | `d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175` | 51,153 |
| test S0 | `tests/test_walksafe_v2_5_control_candidate_20260730.py` | `00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523` | 69,685 |
| continuation wrapper | `scripts/check_walksafe_project_continuation_v2_5_candidate.py` | `1a2dc4f1d8c28c5163cae8306a160e8f942f962ea2ec190a625b64dc688ece5c` | 2,513 |
| Goal wrapper | `scripts/check_walksafe_goal_graph_v2_5_candidate.py` | `98d656e8c3c54e122a419eee9d2c3b3285f2594f4abd3abbb5597d8e9470feda` | 2,434 |

R016만 실행 가능한 선행 설계다. R017~R024와 각 review는 모두 불합격
historical evidence이며 권한은 `NONE`이다. R024 target은
`356fb4308542b129e0d2ef5957328cfd0fdebc57bd8dbf297b0856675eb21a92 /
26,522`, structural review는
`40ff2e4cd20b49d34e90df55bf0af34e3a734639e89e67ba4adcb42b05e1c04d /
9,087`, skeptical review는
`4abcccdd066f608bd92854f3ec0794ccd3118d4046d58988222460b1f7bb03b4 /
8,568`이다. R017~R023의 exact pins는 R024 §1의 immutable historical table을
재사용한다.

실행하지 않은 supervisor 실험
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/r025-content-cas-supervisor-r001/walksafe_r002_content_supervisor.py`
(`00a624e8264b1b6d65bba57e5f36f7d253e3871dca15baab7babf622e15c0dc4 /
35,695`)은 정상 경로·테스트 판정·write confinement·handoff 결함으로 거부한다.
R025에서 import 또는 실행하지 않으며 후계 supervisor도 만들지 않는다.

## 2. 명시적 위협 범위와 단순화

R025는 한 사용자가 소유한 로컬 유지보수 세션을 대상으로 한다. 다음은 위협
범위 밖이다.

- 같은 uid로 동시에 source/path를 의도적으로 바꾸는 적대 프로세스
- kernel, Python runtime 또는 filesystem 자체의 compromise
- 독립 검수자가 의도적으로 거짓 결과를 쓰는 경우

이는 R024 skeptical review가 제시한 명시적 대안이다. 대신 각 단계 전후에 정규화한
path, SHA-256, bytes, 파일 종류와 대상 부재를 다시 확인한다. 예상하지 않은 content
drift는 즉시 실패이며 자동 복구·덮어쓰기·삭제를 하지 않는다. inode와 timestamp는
권한 근거가 아니고 동일 bytes는 동등하다.

## 3. P1 — R025 독립 계획 검수

소스 수정 전에 두 독립 검수자가 이 문서의 같은 SHA/bytes를 읽는다.

1. structural review는 단계 순서, exact 대상, namespace, 성공 조건, zero-authority를
   검사한다.
2. skeptical review는 기존 r001 보존, 기존 target/race/partial failure, 테스트가 세는
   수만 맞추는 경우, 범위 밖 위협의 명시성을 검사한다.

Review exact verdict:

    status: PASS_FOR_NON_EFFECTIVE_R002_EXECUTION_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority_granted: NONE_FOR_ACTIVATION_GOAL_PRODUCT

둘 중 finding이 하나라도 있으면 P2 이후 write는 0이고 새 roadmap revision을 만든다.

## 4. P2 — preflight와 단일 source patch

P1 dual PASS 뒤 다음 논리곱을 확인한다.

- §1의 C0, reviewed pair, R016, S0 3개, wrapper 2개가 exact content와 일치한다.
- failed-r001 root는 directory이고 basename byte-sort exact six만 포함한다. R022 §2의
  six SHA/bytes, old test binding
  `5f1e80f3a22018191a753957bcefbcf620960017f47ccab55a00f031d8d46459 /
  68,995`, seq1-only와 `effective/approved/applied=false`가 유지된다.
- `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r002`
  및 두 r002 candidate review path와 closure path는 모두 absent다.
- staged index는 변경하지 않는다. 기존 dirty worktree와 무관한 파일은 수정하지 않는다.

그 뒤 한 `apply_patch`가 오직 core, builder, test 3개를 수정한다. wrapper 2개는
현재 bytes를 유지한다. 최소 변경은 다음뿐이다.

1. 모든 current candidate path, gate path, document/event ID를 `r002/R002/002`로
   교체한다. design lineage의 `R022`, plan root `r001`, review suffix `r001`,
   failed-r001 history는 의도적으로 유지한다.
2. R016은 accepted predecessor, R017~R024는 rejected history, R025 roadmap과 두 PASS
   review는 current execution provenance로 exact 결속한다.
3. 현재 사용자 지시는 exact UTF-8 bytes 840과 SHA
   `8b098810f7ed9161c27df31e0cdc8e8ea9b253d7bf2dea89a866a17167ac631a`
   로 유지하고 truthful local-session delegation만 기록한다.
4. P2 시작 뒤 실제 nonfuture Asia/Seoul microsecond 시각 하나를
   `P2_PREPARATION_STARTED_AT/PREPARED_AT`으로 봉인한다. 다른 fixture 시각은 그
   기준에서만 파생한다. 과거 roadmap/review보다 이른 synthetic 시각을 쓰지 않는다.
5. failed-r001은 content-only exact-six validator로 읽기만 한다. 교정·삭제·재사용하지
   않는다.
6. r002 target이 어떤 종류나 내용으로든 이미 있으면
   `NEW_REVISION_REQUIRED_EXISTING_R002_TARGET_AUTHORITY_ZERO`로 실패한다. exact-existing
   recovery를 제거한다. rename EEXIST도 동일 terminal이며 자기 staging만 정리한다.
   parent fsync 뒤 실패하면 공개 target을 지우지 않고 동일 revision 재시도도 terminal다.
7. build/check/wrapper가 r002 namespace와 zero-credit boundary를 검증하도록 기존 37개
   test method의 subcase만 갱신한다. 테스트 수를 부풀리는 새 method는 추가하지 않는다.

`DELEGATION_REQUIREMENT_ID`는
`WS-V25-R022-CURRENT-LIVE-SESSION-DELEGATION-20260802-R025-R002`,
`V25_CHECK_COMMAND_CONTRACT_VERSION`은 `2026-08-02.2`다. history/checkpoint/output/
active IDs와 seq1/2/3 event IDs는 모두 `R002` 또는 `002` suffix를 사용한다.

## 5. P3 — source 검증과 r002 add-only 생성

Runtime은 `/usr/bin/python3.14`, Python `3.14.4`, executable SHA
`b8d8288faefdd300201f43fcf00f6f539a27218eeed3a3dff5ab10b9c4c99700`
을 사용한다. 작업 경로는 repository root다.

Source patch 직후:

1. 세 변경 파일 AST parse, duplicate literal key scan, trailing whitespace scan을 통과한다.
2. exact test method 이름 집합이 patch 전과 같고 count가 37임을 AST로 확인한다.
3. `/usr/bin/python3.14 -B -m unittest -v tests.test_walksafe_v2_5_control_candidate_20260730`
   가 exit 0, `Ran 37 tests`, skipped 0, failure/error 0, final `OK`다.
4. C0, failed-r001 exact six, wrappers, R025 roadmap/reviews가 preflight content와 같다.

그 뒤 한 번만 다음 builder를 실행한다.

    /usr/bin/python3.14 -B scripts/build_walksafe_v2_5_control_candidate_20260730.py --root <repo-root>

유일한 성공은 exit 0, `publication_result=PUBLISHED_NEW`, r002 root exact six다. 실제
directory를 열거해 extra/missing 0, files regular/non-symlink/nlink1, SHA/bytes가
deterministic in-memory rebuild와 일치함을 확인한다. 실패 또는 partial target은
수정·삭제·resume하지 않고 새 revision으로 넘긴다.

## 6. P4 — postbuild gates와 candidate 독립 이중 검수

Publication 뒤 다음 네 gate를 모두 실행한다.

1. 동일 37-test command: exit 0, tests 37, skipped/failure/error 0, `OK`.
2. builder `--check`: exit 0, `mode=CHECK`,
   `publication_result=NOT_APPLICABLE`.
3. continuation wrapper `--mode CANDIDATE`: exit 0, exact PASS mode.
4. Goal wrapper `--mode CANDIDATE`: exit 0, exact PASS mode.

각 read-only gate 전후에 r002 root exact-six SHA/bytes와 C0/failed-r001/wrapper
bindings가 동일해야 한다.

그 뒤 서로 다른 두 검수자가 다음 고정 입력을 받아 candidate를 읽기 전용 검수한다.

- R025 roadmap 및 P1 review bindings
- core/builder/test S1 bindings와 unchanged wrapper bindings
- r002 root name digest 및 exact six bindings
- pre/post gate 결과 요약

각 candidate review는 target six binding, source binding, 시작/종료 equality와 다음
exact verdict를 포함한다.

    status: PASS_FOR_NON_EFFECTIVE_V25_CANDIDATE_ONLY
    findings: BLOCKING=0 MAJOR=0 MINOR=0
    authority: NONE_FOR_ACTIVATION_GOAL_PRODUCT

Finding이 있으면 candidate는 실패 evidence로 보존하고 활성화하지 않는다.

## 7. P5 — 단순 closure와 인계

P4 dual PASS일 때만 canonical JSON+LF closure를 add-only로 만든다.

    docs/control/execution/artifact-closure/run-20260727-001/
    walksafe-v25-r002-reviewed-closure-20260802-r025-r001.json

Closure는 exact keys `schema_version,closure_id,roadmap_binding,plan_reviews,
source_bindings,candidate_binding,candidate_reviews,gate_summary,authority_boundary,
closed_at`만 갖는다. 모든 binding은 `{path,sha256,bytes}`이고 candidate root는 exact-six
name digest와 six file bindings를 포함한다. `authority_boundary`는
effective/approved/applied/canonical/checkpoint/Goal/product write 모두 false,
evidence_only true다. `closed_at`은 두 candidate review 종료 뒤의 nonfuture KST
microsecond 시각이다.

Closure binding을 daylog와 local-memory에 기록하고 사용자 인계에 같은 binding을
표시한다. 이 기록은 외부 서명이나 활성화 권한을 뜻하지 않는다.

## 8. 금지 범위와 중단 조건

전 단계에서 다음은 0이다.

- canonical checkpoint 또는 active v2.5 write
- Goal graph/status/queue write
- Android/Web 제품 코드 write
- formal/device/event/release credit
- push, PR, deploy, 기관 제출, 유료 서비스, 비밀값 사용
- failed-r001 또는 기존 사용자 파일 삭제·덮어쓰기

예상 밖 content drift, 기존 r002 target, 테스트 실패, 검수 finding, partial publication,
외부 입력 필요 중 하나가 발생하면 현재 revision의 권한은 0으로 닫고 증거만 보존한다.

## 9. 성공 조건과 후속 순서

R025 성공은 다음 논리곱이다.

    R025_DUAL_PLAN_REVIEW_PASS
    AND S0_TO_S1_MINIMAL_PATCH
    AND PREBUILD_37_PASS_SKIP_ZERO
    AND R002_PUBLISHED_NEW_EXACT_SIX
    AND POSTBUILD_37_BUILDER_TWO_WRAPPERS_PASS
    AND R002_DUAL_CANDIDATE_REVIEW_PASS
    AND R025_CLOSURE_RECORDED
    AND C0_FAILED_R001_UNRELATED_WORKTREE_UNCHANGED
    AND CANONICAL_CHECKPOINT_GOAL_PRODUCT_DELTA_ZERO

결과 상태는
`REVIEWED_NON_EFFECTIVE_V25_CANDIDATE_R002_NOT_AUTHORIZED_NOT_APPLIED`다.

후속 roadmap 순서는 고정한다.

1. R026: exact R025 closure를 입력으로 한 activation transaction
2. R027: Goal replay/full19
3. R028: FP008 materialized/ready/`GOAL_STARTED`
4. R029: Android 최소 read-only slice

R026 전 canonical/checkpoint write, R027 전 Goal write, R028의 유효 start 전 제품 write는
허용되지 않는다.
