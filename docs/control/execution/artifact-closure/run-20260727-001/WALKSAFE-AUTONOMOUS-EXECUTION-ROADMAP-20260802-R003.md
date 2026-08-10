# WalkSafe 자율 실행 로드맵 20260802 R003

## 0. 지위와 단일 실행 범위

```text
document_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R003
document_class = INTERNAL_EXECUTION_ROADMAP
review_status = PENDING_TWO_INTERNAL_REVIEWS
executable_scope = WP001_BOOTSTRAP_SOURCE_BUILD_ONLY
candidate_code_execution_allowed = false
network_allowed = false
live_repository_mutation_allowed = false
canonical_or_product_mutation_allowed = false
official_progress_delta = 0
artifact_completion_credit_delta = 0
release_claim = NOT_ELIGIBLE
```

R001과 R002 및 그 검수는 수정하지 않는 역사다. R002 structural review는
`PASS 0/0/0`이었지만 skeptical review는 `REVISION_REQUIRED 4/5/0`이었다.
따라서 R002의 candidate path에는 아무것도 만들지 않는다.

R003이 현재 허용하는 일은 저장소 밖 새 경로에 다음 단계용 **부트스트랩 소스
파일만 작성하고 detached seal로 봉인하는 것**이다. 이 소스는 별도 독립 검수 전
실행하지 않는다. lock 생성, pytest, npm, project checker, backup 추출, sandbox
실행, live apply는 모두 이번 범위 밖이다.

현재 사용자의 직접 지시가 이 살아 있는 세션의 로컬 source build 근거다. 이
roadmap이나 review 파일은 권한을 생성하지 않으며 새 세션에서 그 지시를 재생할
수 없다. 내부 review는 품질 점검이지 canonical/formal/owner 승인도 아니다.

## 1. R002 findings의 교정 원칙

| R002 skeptical finding | R003 교정 |
|---|---|
| B1 review identity 위조 | 파일 parser가 identity를 증명한다고 주장하지 않는다. root가 실제 orchestration completion event로 서로 다른 reviewer를 확인하며, 문서만으로 실행 권한을 재생할 수 없다. |
| B2 미래 input 선결속 | 현재 epoch는 이미 존재하는 roadmap/review/backup/tool identity와 새 source bytes만 봉인한다. 생성물은 다음 epoch에서 새 manifest를 만든 뒤 소비한다. |
| B3 live write 누출 | 현재 candidate 코드를 실행하지 않는다. 다음 epoch는 reviewed `bwrap` profile로 live repo를 namespace에서 보이지 않게 하고 backup read-only/work rw만 허용한다. |
| B4 manifest 자기참조 | `content-manifest.json`은 자신과 seal을 제외하고, `content-manifest.sha256`이 manifest를 외부에서 봉인한다. |
| M1 mutable network | 현재 network 0. wheel 취득, hash review, offline compile/install을 서로 다른 미래 epoch로 분리한다. |
| M2 self-certified checker | verifier/source를 먼저 봉인·독립 검수하고 다음 epoch에서 실행한다. 실행 receipt도 다시 독립 검수한다. |
| M3 stale fail-first | 미래 execution epoch에서 projection CAS 뒤 새로 실행하고 exact node set/exit/summary를 reviewed parser로 판정한다. |
| M4 genesis/resume 충돌 | 모든 candidate는 create-only one-shot이다. 중단된 경로는 이어 쓰거나 고치지 않고 보존하며 add-only successor path를 새로 검수한다. |
| M5 ancestor/copy ambiguity | 모든 조상을 lstat/owner/mode로 검사하고 leaf를 atomic `mkdir`로 한 번만 만든다. projection copy set은 검증된 backup 4개로 한정한다. |

추가 pre-execution 감사에서 R002 §5D의 DOC-01/DOC-05 설명도 잘못 단순화된
것을 확인했다. pretransition snapshot의 frozen object는 receipt의 posttransition
bytes 자체가 아니다. 미래 D checker는 기존 2026-07-22 test와 같은 fixed
`effective_at=2026-07-22T14:30:00+09:00` reconstruction을 실행해
`plan.canonical_outputs`에서 historical post bytes를 얻어야 한다. snapshot object를
곧바로 receipt binding이라고 주장하면 안 된다.

## 2. 고정 입력

| 입력 | SHA-256 |
|---|---|
| R002 | `d2a7b2c54e5b7ce7f7c1baebd94800f3404fea7429631612eebb39901dd35576` |
| R002 structural review | `f626fb5e5014510535d7ed3e259866eac1cd9137da70588404801fd7750b93af` |
| R002 skeptical review | `5a6f7dec64ef16b91c358aa58826af5615a67c7871c8103d279ee2ed9f60a9f7` |
| history bundle | `56a95dd2b43612d27b5140dddb2857830cd2ce82571cad3748f7886dade10751` |
| tracked patch | `69d2d378de56fd26a142f8220f2e527ff71e6126734664555f90a71a4f3ad558` |
| untracked archive | `2de0201890d73a7067d2c5c879a69ffd31bcba8bfcc7ffbf21d0fdae1a7fe398` |
| status gzip | `f3288d84b457cc5451c821572c2dea112016cfbde39bc00be3c5eb5654d3b6ca` |

backup root는
`/home/ddobagi/.codex/backups/walksafe/20260802T0125KST-rc2-pre-roadmap`다.
bundle/patch/archive/status는 모두 regular, non-symlink이며 기존 검증은 PASS했다.
부트스트랩 source는 이 파일을 읽는 경로와 기대 hash를 코드 상수로만 넣고 이번
epoch에서 backup을 추출하거나 project bytes를 실행하지 않는다.

필요한 host primitive는 `/usr/bin/bwrap` 0.11.1, `/usr/bin/git`, `/usr/bin/tar`,
`/usr/bin/gzip`, CPython 3.12.13이다. source manifest는 각 executable의 resolved
path, SHA-256, bytes, mode와 `--version` raw output hash를 기록한다. 이는 현재
도구 identity 기록일 뿐 다음 epoch의 실행 PASS가 아니다.

## 3. roadmap 독립 검수

서로 다른 두 agent가 같은 frozen R003 SHA를 structural/skeptical 관점에서
검수한다. 작성자·후보 구현자는 root이고 reviewer는 이를 겸임하지 않는다.
reviewer는 서로의 verdict를 받기 전에 작업한다.

파일은 다음 두 개다.

- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R003-independent-structural-review-r001.md`
- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R003-independent-skeptical-review-r001.md`

각 파일에는 review ID/type, reviewer agent/session, independence attestation,
target SHA/bytes/lines, verdict와 blocking/major/minor count를 정확히 한 번 둔다.
root는 regular/non-symlink, target 동일성과 두 reviewer가 실제로 다른 agent라는
orchestrator completion event를 함께 확인한다. 파일의 identity 문자열만으로
독립성을 증명하거나 실행 권한을 만들었다고 주장하지 않는다.

어느 severity든 nonzero면 source build는 0이며 R003을 고치지 않고 add-only
R004 roadmap을 만든다. 같은 target에 두 실제 reviewer가 모두 `PASS 0/0/0`인
경우에만 §4를 수행한다.

## 4. WP001 bootstrap source build

### 4.1 고정 경로와 create-only genesis

```text
candidate_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r001
```

현재 이 path와 모든 leaf는 absent여야 한다. `/home`, `/home/ddobagi`,
`/home/ddobagi/.codex`부터 각 기존 ancestor를 `lstat`해 directory/non-symlink,
expected owner, world-writable false를 확인한다. 없는 `candidates`, `walksafe`와
leaf는 umask `077`, mode `0700`으로 부모부터 `os.mkdir`한다. `exist_ok`, `-p`,
overwrite, rename-over-existing는 쓰지 않는다. 어느 단계든 실패하면 즉시 멈추고
그 path는 재사용하지 않는다.

source 파일 작성은 `apply_patch`만 사용한다. 허용 경로는 다음 여덟 개뿐이다.

```text
README.md
build-projection.py
run-readonly-sandbox.sh
verify-bootstrap.py
sandbox-profile.json
source-input-manifest.json
content-manifest.json
content-manifest.sha256
```

### 4.2 source 계약

`build-projection.py`는 다음 epoch에서만 실행될 create-only builder다.

- 고정 backup 네 파일을 lstat하고 exact hash를 검사한다.
- tar member는 absolute/`..`/duplicate/symlink/hardlink/special을 모두 거부하고
  regular entry exact 2,550만 허용한다.
- 새 work leaf와 `projection/repo`가 모두 absent일 때만 시작한다.
- bundle에서 exact branch/HEAD를 clone하고 tracked patch를 적용한 뒤 untracked
  archive를 `keep-old-files` semantics로 복원한다.
- 복원 status bytes는 gzip 안의 porcelain-v2-z bytes와 byte-identical이어야 한다.
- worktree manifest는 Git tracked+untracked exact path set, missing tracked 4개,
  각 present regular file의 SHA/bytes/mode/nlink를 기록한다.
- projection worktree symlink/special file과 present file `nlink!=1`을 거부한다.
- live repository path는 코드에 입력으로 받지 않고 열지 않는다.

`run-readonly-sandbox.sh`는 다음 epoch의 유일한 project-command launcher다.

- `/usr/bin/bwrap --die-with-parent --unshare-all`을 사용한다.
- projection repo만 원래 repository path에 `--ro-bind`한다.
- reviewed work/candidate output leaf만 `--bind`하고 backup은 `--ro-bind`한다.
- `/usr`, `/bin`, 필요한 exact tool roots와 최소 `/etc`만 read-only로 보인다.
- 실제 `/home/ddobagi/Code`와 backup 외 홈 경로는 namespace에 노출하지 않는다.
- 기본은 `--unshare-net`; network acquisition 전용 profile은 별도 미래 review 없이는
  활성화하지 않는다.
- caller command, cwd, env allowlist를 고정하고 ambient Python/pytest/npm 옵션을
  제거한다.

`verify-bootstrap.py`는 source seal과 다음 epoch projection을 독립 재계산한다.
정상 1개와 path traversal, symlink, hardlink, extra member, backup hash drift,
status drift, output allowlist drift, manifest/seal drift negative fixture를 지원한다.
이번 epoch에서는 작성·봉인만 하고 실행하지 않는다.

### 4.3 manifest와 detached seal

`source-input-manifest.json`은 R003, 그 두 review, §2 입력과 host primitive만
열거한다. 생성되지 않은 projection, wheel, checker 결과를 input으로 가장하지
않는다.

`content-manifest.json`은 위 allowlist 중 자신과
`content-manifest.sha256`을 제외한 정확히 여섯 payload의 path, SHA-256, bytes,
mode를 정렬해 담는다. `content-manifest.sha256`은 정확히 다음 한 줄이다.

```text
<content-manifest.json SHA-256><two spaces>content-manifest.json\n
```

manifest는 자기 SHA를 포함하지 않는다. seal도 자기 SHA를 요구하지 않는다.
모든 candidate file은 regular/non-symlink, `nlink=1`; directory mode는 0700,
file mode는 기본 0600이고 shell launcher만 0700이다.

### 4.4 완료 판정

다음을 모두 만족할 때만 `BOOTSTRAP_SOURCE_PREPARED_NOT_EXECUTED`다.

- roadmap review 두 개가 같은 R003에서 실제 `PASS 0/0/0`
- candidate root가 create-only로 한 번 생성됨
- 정확한 8-file allowlist, symlink/special/hardlink/extra 0
- input manifest가 기존 input만 결속하고 모든 hash/type/mode가 일치
- content manifest 6 payload와 detached seal이 독립 재계산과 일치
- candidate source 또는 project command 실행 0, network 0
- live repository, backup, canonical, product write 0
- formal/device/Gate/release/official progress delta 0

중단되거나 한 조건이라도 실패하면 그 경로는 `INCOMPLETE_UNTRUSTED`다. 상태를
기록하려고 기존 path에 이어 쓰지 않으며 daylog에 path와 판정만 남긴다.

## 5. source 독립 검수 — 다음 안전 단계

bootstrap source가 준비되면 구현에 참여하지 않은 새 reviewer 두 명이 같은
`content-manifest.json`과 detached seal을 검수한다. 한 명은 path/sandbox/write
경계, 다른 한 명은 manifest/recovery/negative-oracle을 본다. 둘은 source를
실행하지 않고 정적 검수와 자체 read-only hash 재계산만 한다.

finding이 하나라도 있으면 기존 candidate를 수정하지 않고 새 source candidate
path를 만든다. 둘 다 `0/0/0`이어도 실행 권한이 생기는 것은 아니다. 같은 살아
있는 세션의 사용자 지시가 계속 유효하고, 별도 execution R004가 exact source
seal과 bwrap argv를 고정한 경우에만 builder를 한 번 실행한다.

## 6. 전체 후속 로드맵 — 현재 비실행

1. **R004 projection build** — reviewed source를 network 없이 bwrap에서 실행하고
   backup snapshot을 새 inode projection으로 복구한다. crash/resume는 없으며
   failure path는 보존하고 새 successor만 허용한다.
2. **R005 wheel acquisition** — network 전용 격리 epoch가 Python tool/project
   wheel을 취득해 full URL/hash/index/config/env receipt를 만든다. 소비하지 않고
   독립 review한다.
3. **R006 offline A** — reviewed wheelhouse를 `--unshare-net` sandbox에서만 사용해
   two-build lock, exact Pillow-only delta와 clean hashed install을 검증한다.
4. **R007 B/C/D** — 각 lane에 새 input/output epoch를 만들고 reviewed verifier로
   실행한다. B는 132/132 및 negative routing, C는 exact4/exact5, D는 위 fixed-time
   historical reconstruction/current self-seal을 검증한다.
5. **candidate output review** — receipt raw bytes와 command result를 제3자가
   재실행·대조한다. manifest/result 작성자와 reviewer를 분리한다.
6. **control repair candidate** — live apply가 아닌 versioned candidate만 만든다.
   exact allowed delta, crash/CAS oracle, active-package-derived full gate와 별도
   권한 없이는 canonical을 바꾸지 않는다.
7. **단일 제품 leaf와 artifact lane** — live frontier 재계산 뒤 한 leaf씩
   fail-first/최소 구현/회귀/독립 review한다. 내부 artifact와 외부
   owner/attestation/real-event lane을 분리한다.
8. **formal/device/Gates/release** — 동일 immutable candidate에 대한 실제 권한자
   행위 전까지 `0/279`, `0/0`, `0/5`, `NOT_ELIGIBLE`, `NOT_COMPLETE`를 유지한다.

각 단계는 직전 실제 output이 생긴 후 별도 작은 plan과 독립 review를 거친다.
고정 `19/19` 또는 이전-session receipt를 재사용하지 않는다.

## 7. 다음 단일 행동

R003을 freeze하고 실제로 서로 다른 두 agent에게 같은 SHA의 structural/skeptical
검수를 동시에 요청한다. 둘 다 findings-zero이면 §4의 여덟 source 파일만 작성·
봉인하고, 그 source를 실행하지 않은 채 새 두 reviewer에게 넘긴다.
