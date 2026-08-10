# WalkSafe 자율 실행 로드맵 20260802 R009

## 0. 지위와 frozen predecessor

```text
document_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R009
document_class = INTERNAL_EXECUTION_ROADMAP_ADD_ONLY_SOURCE_CORRECTION
review_status = PENDING_TWO_INTERNAL_REVIEWS
current_executable_scope = R009_PLAN_REVIEW_ONLY
draft_source_execution_allowed = false
network_allowed = false
live_product_or_canonical_mutation_allowed = false
official_progress_delta = 0
artifact_completion_credit_delta = 0
release_claim = NOT_ELIGIBLE
```

R009는 다음 frozen identity를 predecessor로 삼는다.

| input | SHA-256 | bytes | lines/status |
|---|---|---:|---:|
| R008 roadmap | `af375aac6e3a7d0849103cf073e75e9ce96a57ec1dbcb0a69be8e828584b8ed4` | 6834 | 129 |
| R008 structural review | `61b5d084c7f03f2a658f4b86de3c0aed3b5d047a7a1e47ddd88f559804eb9736` | 3778 | 60, PASS 0/0/0 |
| R008 skeptical review | `dce101e834cd2eb02aa4d2716ada7530b0bc615ab112e19905cfe6da769b0da9` | 4312 | 64, PASS 0/0/0 |
| R008 boundary source review | `ce5b13ebff278f41534a19d5a797707fad0b7a0ab2fe0656c9819d546cd355ed` | 14661 | 130, 6B/4M/2m |
| R008 recovery source review | `e4914ca2951993953901a9c19ffb6907f5a8a74a1963c40ade4f035496a6e698` | 13034 | 203, 7B/0M/0m |

R008 draft의 manifest/seal-file/draft-post/observation/marker SHA-256은 차례로
`a7a64ddc93dace6cd4116fd58821ab240b639a55156989bb1450bf0feecc0397`,
`db763531df2bee5f5a581bc06f7a9a367eb36c5dd1d965afdeb0f4e8d6a12c51`,
`a305729aa86beefb979a81f7e25cf5aa8fa92857967c3646d8cebddd4b80300d`,
`883f37dd072ee00ef45576efc2f3578ad120a8b7a3cf056a7db379c328465f87`,
`d25da936614ee2152724c15a4ede106f56aa8d889eb2c257836f5282310565c4`다.
R008 draft exact 9와 evidence exact 4는 immutable rejected predecessor이며 final root는
absent다. 그 세 root에 write, chmod, delete, repair, rerun, resume하지 않는다.

## 1. 권한과 one-shot 경로

R009 plan 작성 권한은 live 사용자 goal과 R008 `E_FAIL_SUCCESSOR`, 위 두 source review의
nonzero finding을 함께 재확인한 현재 호출에만 있다. R009 source 작성 권한은 live goal이
유지되고 서로 다른 두 새 agent의 R009 plan review가 같은 frozen R009를 각각
`PASS 0/0/0`으로 판정한 뒤에만 생긴다.

```text
draft_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r009-r001
final_candidate_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r009-r001
evidence_root = /home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r009-r001
```

R009 review 전에 세 path와 leaf는 모두 absent여야 한다. E1은 draft/evidence만 만들고
final은 absent로 남긴다. E1 실패나 source-review finding이 생기면 그 root를 그대로
보존하고 새 suffix만 사용한다.

## 2. exact epoch와 write set

모든 basename은 repository의
`docs/control/execution/artifact-closure/run-20260727-001` 아래 literal file이다.

| Epoch | exact write set |
|---|---|
| `E0_PLAN_AUTHOR` | `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R009.md` 하나 |
| `E0_PLAN_REVIEW` | §7의 R009 plan review 두 파일만; frozen R009 write 0 |
| `E1_CORRECTED_DRAFT_AUTHOR` | §1 draft root의 R008과 같은 exact 9개와 evidence root의 R006 exact 4개만 |
| `E2_SOURCE_REVIEW_LOG` | `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R009-R001-independent-boundary-review-r001.md`, `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R009-R001-independent-recovery-review-r001.md`, `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715/daylog/2026-08-02.md`만 |
| `E_FAIL_R010` | finding이 있으면 `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R010.md` 하나 |

기존 daylog가 있으면 E2에서 R009 block 하나만 append하고 기존 bytes를 보존한다.
local-memory 기록은 E2 뒤 별도 automation epoch다.

## 3. 안전 상속과 명시적 replacement

R008에서 다음만 상속한다.

- live authority와 서로 다른 두 독립 plan-review gate
- rootfd부터 `O_NOFOLLOW` ancestor 검증, pinned parent의 literal `mkdirat`
- one-shot, create-only, no repair/delete/rerun/resume
- root `0700`; candidate/evidence/final regular `0600`, uid/gid `1000/1000`, nlink 1
- confined `apply_patch Add File`, canonical manifest/seal, prior-only observation과
  자기·미래 주장이 없는 marker
- fixed backup bytes/hash, attached branch/OID/no-upstream/raw status
- create-only two-pass tar restore의 path/type/mount 방어
- source/network/project/backup command 0과 모든 공식·제품·canonical delta 0

R008의 FD map, `libm` mode, projection canonical JSON, Git argv/env, verifier read helper,
source/runtime semantic validation, publication/projection oracle, E1 spot-check와 negative oracle는
상속하지 않고 §4 C01~C13으로 전부 교체한다.

## 4. source finding closure

두 source review의 원문 `13B/4M/2m` 19개를 누락 없이 아래 13개 cluster에 정확히 한 번
매핑한다. 중복 병합 뒤 최고 severity는 `10B/1M/2m`다.

| ID | mapped finding | successor-only closure predicate | validation |
|---|---|---|---|
| C01 | boundary B-01; recovery B-02 | collision-safe `opened_fd -> fixed_fd`; bwrap exec 직전 role별 fstat/hash 일치와 ambient 200~224 배제; payload 진입 직전 publish FD exact `{0,1,2,10,11}`, projection exact `{0,1,2,10,11,12,13,14,15}`이며 original mount/script/temp FD 0 | static FD map/close audit; later clean·hostile-FD sandbox |
| C02 | boundary B-02 | code, runtime closure, R005 table과 pinned host의 `libm.so.6` mode를 모두 `0644`로 고정 | static identity; later positive/wrong-mode negative |
| C03 | boundary B-03 | producer/controller/verifier가 모두 UTF-8 `ensure_ascii=false`, sorted compact JSON+LF를 사용하고 fixed Korean path에서도 byte-equal | static canonicalizer; later non-ASCII positive/opposite-policy negative |
| C04 | boundary B-04; recovery B-01 | repository controls는 literal frozen `0664`, candidate/evidence/final/backup은 각 `0600`; surrogate copy나 chmod 금지 | static call-site policy; later literal-control positive/substitution negatives |
| C05 | boundary B-05 | 한 bounded read가 bytes/hash/full pre-post stat을 함께 만들고 끝에 nofollow pathname identity를 재검사 | static same-read CAS; later in-place/atomic-replacement drift |
| C06 | boundary B-06; recovery B-04 | publication expected payload/manifest/seal은 reviewed input anchor에서 받고 output 자기 값을 기대값으로 쓰지 않음; projection은 필수 repository-tree digest와 tar path/content/mode, Git path set, physical rows/type/mode/nlink/hash를 controller와 independent verifier가 전부 재계산 | static field-to-oracle coverage; later content/path/type/empty-dir/mount mutation negatives |
| C07 | boundary M-01; recovery B-03 | Git은 literal `/inputs/...`와 `/work/projection/repo`만 사용하고 closure/source/observation argv가 byte-equal | static argv template; later exact command observation |
| C08 | boundary M-02 | backup, apply-patch, runtime mounts/FD map/env/Git/output/prohibitions의 exact nested schema/value와 physical identity 전부 검증 | static schema coverage; later one-field mutation negatives |
| C09 | boundary M-03; recovery B-07 | authorization/post/observation/marker exact keys/types/values; 모든 command/delta 0, execution false, creation roles와 physical snapshot 완전 결속, self/future claim 0 | static evidence coverage; later field mutation+marker regeneration와 3 crash cases |
| C10 | boundary M-04; recovery B-06 | finite case registry가 case→reviewed producer/seal/argv/env/FD/fault/timeout/first·second result/output delta에 1:1 결속; 임의 argv/env/expected/allowlist 금지; 두 실행 뒤 sentinel/pass-FD/preexisting/root 불변; fault-disabled retry가 producer 진입 전 `ONE_SHOT_CONSUMED` | static registry; later exact full negative suite |
| C11 | boundary m-01 | initialized FD만 close하고 manifest open/write/fsync/fstat/close 오류는 frozen `E_MANIFEST`로 fail-closed | static error path; later I/O fault injection |
| C12 | boundary m-02 | publication의 모든 output I/O 오류는 frozen `PUBLISH_IO` exact JSON/RC로 fail-closed | static error path; later syscall fault injection |
| C13 | recovery B-05 | producer/verifier 공통 env에 `GIT_OPTIONAL_LOCKS=0`; argv에 `--no-optional-locks`, `core.hooksPath=/dev/null`, `core.fsmonitor=false`, `core.untrackedCache=false`; exact builtin allowlist·timeout·read-only sandbox와 `.git` 포함 repository pre/post identity equality | static Git confinement; later index-refresh/hostile-fsmonitor negative |

C10 exact case ID는 다음 25개뿐이다.

```text
parent-intermediate-symlink
parent-regular
parent-mount-crossing
tar-dotdot
json-duplicate-key
tar-duplicate-path
input-hardlink
input-fifo
input-hash-drift
status-drift
extra-output
preexisting-final
publish-crash-readme
publish-crash-publish-source
publish-crash-build-projection
publish-crash-run-readonly-sandbox
publish-crash-verify-bootstrap
publish-crash-runtime-closure
publish-crash-source-input-manifest
publish-crash-content-manifest
publish-crash-content-seal
e1-marker-before
e1-marker-partial
e1-marker-wrong
e1-marker-exact-after-write
```

`publish before-first`처럼 output만으로 소비를 증명할 수 없는 case는 등록하지 않는다.
각 case는 child spawn 전에 output 밖의 create-only attempt claim을 소비한다. 두 번째 호출은
fault를 제거하고 같은 claim 때문에 producer 진입 전 `ONE_SHOT_CONSUMED`여야 한다.

## 5. source input과 evidence

R009 `source-input-manifest.json`은 current input으로 R009와 §7의 두 R009 plan review를,
correction input으로 위 두 R008 source review를 결속한다. predecessor anchor로 R008 세 root
literal, manifest/seal/post/observation/marker identity와 final absent를 기록한다. R008
plan/review는 rejected history로 남긴다. backup/runtime/apply-patch exact identity도 결속한다.
R009 미래 seal/evidence/source review/publication/projection output은 input에서 제외한다.

Evidence exact 4와 생성 순서는 R006 replacement를 유지한다.

```text
authorization-gate.json
draft-post.json
e1-observation.json
E1.COMPLETE
```

생성 순서는 authorization → payload 7 → manifest/seal → draft-post → observation → marker다.
marker four-line bytes와 마지막 LF, prior-only observation, crash recovery 논리곱은 유지하되
R009 plan/review identity와 새 physical hashes를 사용한다.

## 6. acceptance formula

```text
PLAN_OK =
  LIVE_USER_AUTHORITY
  and two distinct independent R009 plan reviews
  and same frozen R009 SHA/bytes/lines
  and both PASS 0/0/0

E1_PHYSICAL_OK =
  PLAN_OK
  and R008 predecessor unchanged
  and R009 roots initially absent
  and draft exact9 and evidence exact4 and final absent
  and manifest/seal/post/observation/marker independent reconstruction PASS
  and extra/link/special/hardlink 0
  and candidate execution/import/pycompile 0
  and source/project/backup/network command 0
  and official/product/canonical/formal/device/Gate/release delta 0

R009_SOURCE_OK =
  E1_PHYSICAL_OK
  and C01 through C13 static closure PASS
  and two independent R009 source reviews PASS 0/0/0
  and dynamic validation NOT_RUN
  and candidate remains unexecuted
```

어느 severity든 plan-review finding이면 R009 root write는 0이고 `E_FAIL_R010`만 허용한다.
E1 실패나 source-review finding이면 existing R009 root를 고치지 않고 R010만 허용한다.
두 source review가 PASS여도 별도 publication/projection plan과 그 독립 review 전에는 source를
실행하지 않는다.

## 7. independent review gate와 next action

서로 다른 새 agent 두 명이 frozen R009의 같은 SHA/bytes/lines를 서로의 결과 없이 검수한다.

- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R009-independent-structural-review-r001.md`
- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R009-independent-skeptical-review-r001.md`

각 file은 review_id/type, reviewer_agent/session, independence_attestation,
target_sha256/bytes/lines, verdict, blocking/major/minor를 정확히 한 번 둔다. root는 두 agent가
실제로 다르고 target identity가 같은지 확인한다.

다음 단일 행동은 R009를 freeze하고 위 두 새 agent에게 독립 review시키는 것이다. 그 전에는
R009 root를 만들거나 R008/R009 source를 실행하지 않는다.
