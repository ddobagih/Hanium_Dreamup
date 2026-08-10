# WalkSafe 자율 실행 로드맵 20260802 R005

## 0. 지위와 권한

```text
document_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R005
document_class = INTERNAL_EXECUTION_ROADMAP
review_status = PENDING_TWO_INTERNAL_REVIEWS
current_executable_scope = WP001_BOOTSTRAP_DRAFT_AUTHORING_ONLY
draft_source_execution_allowed = false
network_allowed = false
live_product_or_canonical_mutation_allowed = false
official_progress_delta = 0
artifact_completion_credit_delta = 0
release_claim = NOT_ELIGIBLE
```

R001~R004와 각 review는 수정하지 않는 역사다. R004는 structural review
`3B/1M/0m`, skeptical review `2B/3M/0m`이므로 R004의 draft/evidence path는 만들지
않았다. R005는 그 finding과 별도 status 재현에서 찾은 branch-state 결함을 고친
add-only successor다.

이 문서와 review는 실행 권한을 만들지 않는다. 현재 동일한 live thread에서 사용자가
기록 확인, 전체 로드맵, 독립 검수, 추가 확인 없는 로컬 구현 지속을 직접 지시했고
active goal이 그 범위를 유지한다. E1 직전 root는 current goal이 active이고 이후
취소·대체 사용자 지시가 없음을 다시 확인한다. 이 ephemeral 확인은 파일로 재생할 수
없다. 전이식은 `LIVE_USER_AUTHORITY && TWO_R005_REVIEWS_ZERO`다. 배포, 유료 서비스,
비밀값, 실제 기관 제출, 데이터 삭제는 범위 밖이다.

## 1. 위협 모델과 exact write epoch

### 1.1 위협 모델

실수 path, preexisting path, symlink/hardlink/special, 선의의 input drift, tool update,
부분 실패, crash와 잘못된 resume는 in-scope다. kernel, bwrap, 현재 root orchestrator와
frozen executable은 trusted computing base다. 악의적인 동시 same-UID/root process와
kernel/tool compromise는 out-of-scope다. 선의의 drift라도 pre/use/post CAS가 다르면
성공 receipt를 만들지 않고 새 output을 `INCOMPLETE_UNTRUSTED`로 보존한다.

### 1.2 epoch별 exact path

repository 기준 directory는
`docs/control/execution/artifact-closure/run-20260727-001`이다.

| Epoch | exact write set |
|---|---|
| `E0_PLAN_AUTHOR` | `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R005.md` 하나 |
| `E0_PLAN_REVIEW` | §8의 R005 review 두 파일만; frozen R005 write 0 |
| `E1_DRAFT_AUTHOR` | §2 draft root의 exact 9개와 evidence root의 exact 3개만 |
| `E2_SOURCE_REVIEW_LOG` | §9 source review 두 파일과 `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715/daylog/2026-08-02.md`만 |
| `E_FAIL_SUCCESSOR` | review finding이 있으면 `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R006.md` 하나; R005 path 수정 0 |

local-memory service 기록은 E2 뒤 별도 외부 automation epoch이며 project/canonical
write로 계산하지 않는다. 어느 epoch든 열거되지 않은 repository path write는 실패다.
R005 review가 nonzero면 E1/E2 source path write는 0이고 E_FAIL만 시작한다.

## 2. one-shot roots와 E1 exact file set

```text
draft_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r005-r001
final_candidate_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r005-r001
evidence_root = /home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r005-r001
```

E1은 draft/evidence root만 만들고 final candidate root는 absent로 남긴다. 모든 기존
ancestor는 root fd부터 component별 `O_DIRECTORY|O_NOFOLLOW`로 열어 expected uid,
world-writable false, dev/ino/mode를 확인한다. 없는 `candidates`, `work`, `walksafe`와
leaf는 pinned parent dirfd에 literal basename으로 단일 `mkdirat(0700)`한다. `-p`,
`exist_ok`, rename-over-existing는 금지한다. EEXIST나 metadata drift면 중지한다.

draft exact 9개는 다음이다.

```text
README.md
publish-source.py
build-projection.py
run-readonly-sandbox.py
verify-bootstrap.py
runtime-closure.json
source-input-manifest.json
draft-content-manifest.json
draft-content-manifest.sha256
```

evidence exact 3개와 생성 순서는 다음이다.

```text
1 authorization-gate.json
2 draft-post.json
3 e1-result.json
```

모든 file은 regular, non-symlink, uid/gid `1000/1000`, mode `0600`, `nlink=1`이다.
두 root는 mode `0700`이다. `e1-result.json`은 마지막 terminal marker이며 status는
정확히 `DRAFT_SOURCE_PREPARED_NOT_EXECUTED`다. marker가 없거나 exact set이 아니면
E1은 incomplete다. 실패 path는 고치거나 삭제하거나 resume하지 않고 successor가
새 suffix를 쓴다.

canonical JSON은 UTF-8, duplicate key 없음, `sort_keys=true`, compact separator
`(',', ':')`, terminal LF다. mode는 네 자리 문자열로 쓴다. receipt는 자기 SHA,
inode, ctime을 포함하지 않으며 후속 review가 receipt SHA를 외부 anchor로 고정한다.

## 3. E1 writer confinement

handcrafted payload 7개와 생성된 manifest/seal, evidence 3개는 모두 `apply_patch Add
File`로만 쓴다. `Update/Delete/rename/truncate/chmod repair`는 금지한다. host의 resolved
apply-patch executable은 다음 exact regular file이다.

```text
source = /home/ddobagi/.npm-global/lib/node_modules/@openai/codex/node_modules/@openai/codex-linux-x64/vendor/x86_64-unknown-linux-musl/bin/codex
mount_destination = /usr/bin/apply_patch
mode = 0775
uid_gid = 1000/1000
bytes = 311001136
sha256 = 2e863156ed35ecc5253b1e2f907a9143077b9f7cb51942070c61996471ff6e04
linkage = static-pie
```

controller는 source ancestor를 component별 no-follow로 열고 executable을
`O_RDONLY|O_NONBLOCK|O_NOFOLLOW`로 연 뒤 fstat-hash-fstat한다. draft/evidence root도
pinned directory fd로 연다. 고정 fd 번호로 `dup2(..., inheritable=true)`하고 Python
`subprocess.run(..., pass_fds=...)`에서 다음 bwrap primitive만 사용한다.

```text
/usr/bin/bwrap --die-with-parent --new-session --unshare-all
  --dir /usr --dir /usr/bin
  --ro-bind-fd <tool-fd> /usr/bin/apply_patch
  --dir /draft --bind-fd <draft-fd> /draft
  --clearenv --setenv PATH /usr/bin --chdir /draft
  -- /usr/bin/apply_patch
```

evidence write 때는 마지막 bind만 pinned evidence fd와 `/evidence`, chdir로 바꾼다.
host umask는 subprocess 전에 `077`로 고정한다. patch bytes는 stdin으로만 전달하고
shell redirection으로 host file을 만들지 않는다. sandbox에는 해당 한 root 외 host
filesystem, live repository, backup, home, network가 보이지 않는다. 잘못된 absolute나
`..` patch가 있더라도 host의 다른 path를 쓸 수 없다. 각 invocation 전 target은
ABSENT, 뒤에는 exact name set의 허용 prefix와 file metadata/hash를 controller가
dirfd-relative로 검사한다. tool fd도 사용 뒤 같은 fstat/hash인지 확인한다.

payload 7개는 첫 invocation, 그 bytes에서 계산한 manifest/seal은 두 번째 invocation,
evidence 3개는 각 순서의 별도 confined invocation으로 만든다. 호출 사이에 중단되면
resume하지 않는다.

## 4. fixed backup, branch와 status contract

backup root
`/home/ddobagi/.codex/backups/walksafe/20260802T0125KST-rc2-pre-roadmap`는 실제 mode
`0775`, uid/gid `1000/1000`, non-symlink directory이며 exact children은 네 개다.

| basename | bytes | mode | SHA-256 |
|---|---:|---:|---|
| `repository-history.bundle` | 430056267 | 0600 | `56a95dd2b43612d27b5140dddb2857830cd2ce82571cad3748f7886dade10751` |
| `tracked-working-tree.patch` | 1441785 | 0600 | `69d2d378de56fd26a142f8220f2e527ff71e6126734664555f90a71a4f3ad558` |
| `untracked-files.tar.gz` | 360037403 | 0600 | `2de0201890d73a7067d2c5c879a69ffd31bcba8bfcc7ffbf21d0fdae1a7fe398` |
| `git-status-porcelain-v2.z` | 7703 | 0600 | `f3288d84b457cc5451c821572c2dea112016cfbde39bc00be3c5eb5654d3b6ca` |

모두 regular, uid/gid `1000/1000`, `nlink=1`이다. decoded status는 36,965 bytes,
SHA `9745807cee2906c0a24d1d6d58bc192d05c8b6de0946f8311c30f570ba13a59d`,
NUL records 375, trailing NUL이다. archive는 regular exact 2,550, max depth 23이며
absolute/empty/`.`/`..`/duplicate/link/special은 0이다.

bundle의 전체 ref 27개를 유일하다고 가정하지 않는다. keyed `HEAD`와
`refs/heads/codex/walksafe-rc2-hardening-20260715`가 각각 exact one이고 둘 다
`a3ad7eead6b5d834d3e0675422475a9aad351e3d`여야 한다.

clone은 branch를 유지한다. detached checkout을 하지 않는다.

```text
git -c core.hooksPath=/dev/null clone --quiet --template=/tmp/template
  --no-hardlinks --single-branch
  --branch codex/walksafe-rc2-hardening-20260715 --
  /inputs/repository-history.bundle /work/projection/repo
```

clone 직후 symbolic HEAD와 OID를 확인하고 clone이 만든 upstream을
`git -C ... branch --unset-upstream`으로 제거한다. upstream이 빈 값인지 재확인한다.
patch check/apply와 archive 복원 뒤에도 branch/OID가 같아야 한다. status argv는
정확히 다음이며 decode/normalize/reassembly 없이 raw bytes가 expected와 같아야 한다.

```text
git -c core.hooksPath=/dev/null -C /work/projection/repo status
  --porcelain=v2 --branch -z --untracked-files=normal
```

`--untracked-files=all`과 detached HEAD는 금지한다. expected 첫 두 record는 exact
branch OID/head이고 upstream record는 없다. missing tracked exact 4 paths는 다음이다.

```text
apps/web/app/api/field-session/route.ts
apps/web/app/api/navigation/destinations/search/route.ts
apps/web/app/api/navigation/walking/route.ts
apps/web/app/api/reports/v2/route.ts
```

## 5. exact fd-bound runtime closure

future source publication/projection은 `run-readonly-sandbox.py`가 host input을 먼저
열고 검증한 **같은 fd**를 고정 번호로 상속한다. bwrap 0.11.1의
`--ro-bind-fd FD DEST`와 writable output의 `--bind-fd FD DEST`만 사용하며 pathname
bind는 금지한다. child는 mount된 dev/ino/hash를 preflight receipt와 다시 비교한다.
controller는 child 종료 뒤에도 같은 open fd를 fstat-hash-fstat한다. regular input
drift나 Python stdlib post tree-digest drift면 output이 있어도 success receipt는 0이다.

다음은 source canonical path에서 resolved regular bytes를 destination에 mount하는
exact list다.

| source | destination | bytes | mode | SHA-256 |
|---|---|---:|---:|---|
| `/usr/bin/git` | `/usr/bin/git` | 4547768 | 0755 | `5516c9f362c29376ab9a499a33082f9f611941d8c75930c880e30ad109e39c9a` |
| `/usr/lib/git-core/git` | `/usr/lib/git-core/git` | 4547768 | 0755 | `5516c9f362c29376ab9a499a33082f9f611941d8c75930c880e30ad109e39c9a` |
| `/home/ddobagi/.local/share/hanium-dreamup/python-3.12.13+20260510/bin/python3.12` | same path | 102286096 | 0775 | `f7014f68e3c8f180811740735cf1dd5c28be6cff84db11d0ced2a8cd039670a0` |
| `/usr/lib/x86_64-linux-gnu/libpcre2-8.so.0.14.0` | `/usr/lib/x86_64-linux-gnu/libpcre2-8.so.0` | 699056 | 0644 | `4a6b6b2078685b074869d75cb068664d7da6b3036df7bca0442b1bbbd4ac7e65` |
| `/usr/lib/x86_64-linux-gnu/libz.so.1.3.1` | `/usr/lib/x86_64-linux-gnu/libz.so.1` | 121272 | 0644 | `fbf56b0e59287033b6579bbbeae2f9de2fe86ad5bf2bd44d44aad67a15109318` |
| `/usr/lib/x86_64-linux-gnu/libc.so.6` | same path | 2186512 | 0755 | `a3947513a02831ec692ebf13053c07614882ab54a2101fb91a1b15724062ed0c` |
| `/usr/lib/x86_64-linux-gnu/libpthread.so.0` | same path | 14408 | 0644 | `f93acb6e78dcf0213c8a85f922d21916249148e24de426079c40b6304c42085d` |
| `/usr/lib/x86_64-linux-gnu/libdl.so.2` | same path | 14408 | 0644 | `7d293f8361fcead4f9691561adc0413f724f3607b959abe0d4fb243072956079` |
| `/usr/lib/x86_64-linux-gnu/libutil.so.1` | same path | 14408 | 0644 | `ad58c7fed81a532338c68548b5e4df9762b83bef541390761e1220f17e4bd47a` |
| `/usr/lib/x86_64-linux-gnu/librt.so.1` | same path | 14624 | 0644 | `5df2508a1ef33bd8024d77271e2a4a2607cb63e59dd753dd46f8e7a2ed44962d` |
| `/usr/lib/x86_64-linux-gnu/libm.so.6` | same path | 1198376 | 0644 | `beea4eeacfcfa2cd96011b959a826c97cf4a774017e214f6a34d7eea3d49cd88` |
| `/usr/lib/x86_64-linux-gnu/libacl.so.1.1.2302` | `/usr/lib/x86_64-linux-gnu/libacl.so.1` | 38984 | 0644 | `534d062c6b873081a5f5623627bdeb7d8339087c434be758493ad87c87e257ab` |
| `/usr/lib/x86_64-linux-gnu/libselinux.so.1` | same path | 199120 | 0644 | `7538ee77765a27966563c0d22437a3080dcdb346ff76cc16d71a3ce3379a716a` |
| `/usr/lib/x86_64-linux-gnu/libcap.so.2.75` | `/usr/lib/x86_64-linux-gnu/libcap.so.2` | 51616 | 0644 | `c5b463a8136dabb152bad8b04e89bf67bd2049ef49777ed0b96bdbc6cb5b9565` |
| `/usr/lib/x86_64-linux-gnu/libtinfo.so.6.6` | `/usr/lib/x86_64-linux-gnu/libtinfo.so.6` | 212504 | 0644 | `085dbbe5dc38619276bdd0af37ae588969b1308baded09a2990ce8824a602912` |
| `/usr/lib/x86_64-linux-gnu/ld-linux-x86-64.so.2` | `/usr/lib64/ld-linux-x86-64.so.2` | 254864 | 0755 | `c5e80a563850d6ab5c2f2482e4202d9c1b71fbf44854b8c399e63527202c64e1` |

Python stdlib source/destination은 exact
`/home/ddobagi/.local/share/hanium-dreamup/python-3.12.13+20260510/lib/python3.12`다.
canonical sorted JSONL tree digest는
`251d8ad591e07a6f6337f9fc5ef23897ac7df07b1c64712530771ca5d7734e6a`,
directories 181, regular 1,787, link/special 0, regular bytes 32,865,921이다.

backup은 root directory를 통째로 pathname-bind하지 않는다. rootfd에서 검증한 네
regular file fd를 `/inputs/<exact-basename>`에 각각 `--ro-bind-fd`한다. draft도 검증한
directory fd, final/work도 생성 직후 pinned directory fd를 bind한다. 고정 fd 번호와
mount argv는 successor plan에서 source receipt와 함께 freeze한다.

sandbox는 synthetic destination directories, `/bin -> usr/bin`, `/lib -> usr/lib`,
`/lib64 -> usr/lib64`, `/dev`, `/proc`, tmpfs `/tmp/home`, `/tmp/xdg`, empty
`/tmp/template`만 추가한다. actual `/etc`, live repository, other home, network는 없다.
env는 R004의 exact clearenv map을 유지한다. Git config/system attributes/hooks,
credential prompt와 ambient Python은 차단한다.

## 6. source implementation and safe restore contract

`publish-source.py`는 draft/final ancestor를 component fd로 열고 exact set을 검사한다.
각 source는 `O_RDONLY|O_NONBLOCK|O_NOFOLLOW`, fstat-hash-fstat와 path dev/ino equality를
통과해야 한다. final empty root에 payload 7개와 exact
`content-manifest.json`, `content-manifest.sha256`을 고정 순서로
`O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC`, write-all, fsync로 만든다. 실패하면
truncate/chmod/delete/resume하지 않는다. final directory fsync 뒤 external publication
receipt가 없으면 exact 9개처럼 보여도 untrusted다.

`build-projection.py`는 Git clone/patch 후 tar를 먼저 전부 검증하고 두 번째 pass에서만
복원한다. member는 regular exact 2,550이고 `.git` first component, absolute, empty,
NUL, `.`, `..`, duplicate, file-prefix conflict를 거부한다. `tarfile.extract*`는 쓰지
않는다.

각 archive parent component는 repo rootfd부터 한 단계씩
`O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC`로 연다. absent directory만 pinned parent에서
`mkdirat(0700)` 후 다시 열고, 각 fstat의 `st_dev`가 repo root와 같아야 한다. existing
regular/symlink/special parent와 mount crossing은 실패다. final leaf는 마지막 parent
fd에서 `O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC`로 만든다. archive owner와
special bits는 버리고 mode는 executable bit가 있으면 0700, 아니면 0600이다. exact
size write-all·hash·fsync 후 fstat한다.

status equality 뒤 Git tracked/untracked set과 `.git` 제외 physical tree를 독립
inventory한다. ignored extra, tracked/untracked 교집합, symlink/special, present
`nlink!=1`, missing exact4 불일치는 실패다. projection manifest는 repository 밖
`/work/projection/projection-manifest.json`에 O_EXCL로 쓴다.

`verify-bootstrap.py`는 publisher를 import하지 않고 독립 구현한다. 각 negative fixture는
새 one-shot scratch namespace에서 expected failure class와 nonzero를 모두 요구한다.
추가 postcondition은 outside/protected sentinel bytes와 metadata 불변, preexisting
target 불변, 허용 output exact subset, success receipt 0, terminal
`INCOMPLETE_UNTRUSTED`, 같은 path 재실행 거부다. fixtures는 intermediate symlink,
regular parent, nested mount crossing, `../`, duplicate JSON/tar, hardlink, FIFO, hash/status
drift, extra output, preexisting final과 injected crash after each publication step를
포함한다.

## 7. manifest, receipt와 E1 acceptance

`source-input-manifest.json`은 R005와 두 R005 review, R004와 두 R004 review, backup,
runtime, apply-patch tool identity만 결속한다. 미래 draft seal/receipt/final/projection을
input으로 넣지 않는다.

`draft-content-manifest.json`은 payload 7개만 포함하고 자신과 seal을 제외한다. 각
entry는 path/SHA/bytes/type/mode/uid/gid/nlink를 포함한다. seal은 exact
`<lowercase sha256>  draft-content-manifest.json\n`이다. self-reference는 없다.

external `draft-post.json`은 draft root metadata, exact child-name-list digest와 9개
file의 dev/ino/ctime_ns/mode/uid/gid/nlink/size/SHA를 담되 자신을 포함하지 않는다.
`e1-result.json`은 authorization observation, exact root/file sets, apply-patch tool
pre/post identity, draft manifest/seal와 draft-post SHA, source/network/project command
count 0, status와 official deltas를 담는다.

E1 PASS는 다음의 논리곱이다.

- live authority gate와 R005 두 review `PASS 0/0/0`
- final candidate absent
- draft/evidence exact set, extra/link/special/hardlink 0
- manifest/seal, draft-post, e1-result의 독립 재계산 일치
- 모든 confined apply-patch invocation rc0, stderr empty
- source 실행, project command, backup read, network 0
- live repository, backup, canonical, product write 0
- formal/device/Gate/release/official progress delta 0

## 8. R005 independent review gate

서로 다른 새 agent 두 명이 frozen R005의 같은 SHA/bytes/lines를 서로의 결과 없이
검수한다. review exact files는 다음이다.

- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R005-independent-structural-review-r001.md`
- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R005-independent-skeptical-review-r001.md`

각 file은 review_id/type, reviewer_agent/session, independence_attestation,
target_sha256/bytes/lines, verdict, blocking/major/minor를 정확히 한 번 둔다. root는
orchestration completion으로 reviewer가 실제로 다름을 확인한다. 어느 severity든
nonzero면 E1은 0이고 E_FAIL만 수행한다. 둘 다 0/0/0이어도 §0 live authority가
독립적으로 true여야 한다.

## 9. source review와 전체 후속 roadmap

E1 PASS 뒤 구현에 참여하지 않은 새 두 reviewer가 같은 draft seal과 draft-post를
정적으로 검수한다. exact files는 다음이다.

- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R005-R001-independent-boundary-review-r001.md`
- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R005-R001-independent-recovery-review-r001.md`

두 source review가 0/0/0이어도 실행 권한은 아니다. exact seal/reviews/fd map/bwrap
argv를 고정한 successor plan과 그 독립 review 전에는 publication/projection을 실행하지
않는다.

전체 순서는 다음이다.

1. R005 review → confined draft authoring → source static review.
2. reviewed O_EXCL publisher와 fd-bound sandbox로 final source 발행·projection 복구.
3. 별도 network epoch에서 tool/wheel 취득·hash review.
4. offline two-build lock A, runner B, Gateway C, artifact D를 각각 fail-first와 negative oracle로 수렴.
5. output 독립 검수 뒤 live apply가 아닌 control-repair candidate 작성.
6. v2.4 frontier를 재계산해 exact 한 active leaf를 정식 materialize/start.
7. leaf별 fail-first 최소 구현과 exact257 internal artifact queue를 반복.
8. 동일 immutable candidate에 실제 권한자·실기기·현장 증거가 생길 때만 formal279/device/event/5 Gates/release를 진행.

외부 조건 전까지 `0/279`, `0/0`, `0/5`, `NOT_ELIGIBLE`, `NOT_COMPLETE`를 유지한다.

## 10. finding closure와 next action

| finding | R005 closure |
|---|---|
| R004 structural B1 | backup root 실제 mode `0775`로 교정 |
| R004 structural B2 | apply-patch executable과 draft/evidence fd만 보이는 bwrap writer로 E1 자체를 confinement |
| R004 structural B3 / skeptical M-01 | E0A/E0B/E1/E2/E_FAIL exact path와 evidence exact 3개/순서/terminal marker |
| R004 structural M1 | loader/library absolute source/destination/bytes/mode/SHA exact table |
| R004 skeptical B-01 | native `--ro-bind-fd/--bind-fd`, fixed inherited fd, child/pre/post same-fd CAS |
| R004 skeptical B-02 | every parent chained dirfd, same-device, intermediate symlink/regular/mount fixtures |
| R004 skeptical M-02 | failure class와 sentinel/target/output/receipt/no-resume postcondition |
| R004 skeptical M-03 | same open fd pre/use/post와 stdlib pre/post tree digest, drift 시 success receipt 0 |
| root status reproduction | attached branch, no upstream, exact `--branch -z -unormal`; detached/`-uall` 금지 |

다음 단일 행동은 R005를 freeze하고 서로 다른 두 새 agent의 structural/skeptical review를
동시에 받는 것이다. 한 finding이라도 있으면 draft/evidence root를 만들지 않는다.
