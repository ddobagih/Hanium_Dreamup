# WalkSafe 자율 실행 로드맵 20260802 R004

## 0. 지위, 권한, 현재 실행 범위

```text
document_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R004
document_class = INTERNAL_EXECUTION_ROADMAP
review_status = PENDING_TWO_INTERNAL_REVIEWS
current_executable_scope = WP001_BOOTSTRAP_DRAFT_AUTHORING_ONLY
draft_code_execution_allowed = false
network_allowed = false
live_product_or_canonical_mutation_allowed = false
official_progress_delta = 0
artifact_completion_credit_delta = 0
release_claim = NOT_ELIGIBLE
```

R001~R003과 각 review는 수정하지 않는 역사다. R003은 structural review
`2B/1M/0m`, skeptical review `3B/3M/0m`이므로 source를 작성하지 않았다.
R004는 두 review의 finding을 통합한 add-only successor다.

이 문서와 review는 실행 권한을 만들지 않는다. 현재 권한은 동일한 살아 있는
사용자 thread에서 사용자가 기록 확인, 전체 로드맵 작성, 독립 검수, 추가 확인 없는
로컬 구현 지속을 직접 지시했고 이를 active goal로 유지하고 있다는 사실에서만 온다.
root는 draft 작성 직전에 다음을 모두 확인한다.

1. goal thread가 현재 thread와 같고 status가 `active`다.
2. 최신 사용자 지시가 위 로컬 범위를 포함하며 이후 취소·대체 지시가 없다.
3. 배포, 유료 서비스, 비밀값 사용, 실제 기관 제출, 데이터 삭제는 범위 밖이다.

이 확인은 현재 orchestration의 일회성 gate다. 파일 receipt가 권한을 재생하지
않는다. 새 세션, 문맥 단절, 사용자 취소가 있으면 review가 PASS여도 실행하지 않는다.
따라서 전이식은 `LIVE_USER_AUTHORITY && TWO_REVIEWS_ZERO`이며 review 단독으로는
항상 false다. 현재 지시는 재확인 없이 진행하라는 것이므로 사용자에게 다시 묻지
않는다.

## 1. 위협 모델과 세 쓰기 epoch

### 1.1 위협 모델

보호 대상은 live repository, 네 backup 파일, canonical/checkpoint/product bytes다.
실수로 잘못된 path를 쓰는 일, 기존 path·symlink·hardlink, 입력 drift, 부분 실패,
중단 후 잘못된 resume는 in-scope다. kernel, 검수된 host tool, 현재 root
orchestrator는 trusted computing base다. 악의적인 동시 same-UID/root process와
kernel/tool compromise는 out-of-scope다. 이 제외 없이는 현재 권한으로 pathname
authoring의 원자성을 정직하게 증명할 수 없다. 선의의 동시 변경도 pre/post digest가
달라지면 fail-closed한다.

이 계약은 전체 host filesystem write 0을 주장하지 않는다. 아래 exact protected
set과 writable path만 계상한다.

### 1.2 epoch와 허용 write

| Epoch | 허용 write | 완료 조건 |
|---|---|---|
| `E0_CONTROL_REVIEW` | 이 R004와 §7의 R004 review 두 파일 | 기존 문서 변경 0, product/canonical/test delta 0 |
| `E1_DRAFT_AUTHOR` | §2의 새 draft root와 evidence root만 | protected pre/post subject digest 동일, exact draft/evidence allowlist |
| `E2_SOURCE_REVIEW_LOG` | §8의 source review 두 파일과 종료 시 daylog 한 파일 | E1 밖에서 별도 계상, product/canonical delta 0 |

E1 baseline은 E0 review 두 개가 모두 끝난 뒤 새로 잡는다. 따라서 E0의 control
artifact write와 E1의 repository-delta-zero predicate가 충돌하지 않는다. daylog도
E1 completion 뒤 E2에서만 쓴다.

E1 protected set은 다음이다.

- live repository의 `.git` pointer 자체와 `.git`을 제외한 전체 physical tree
- §3의 backup root와 정확한 네 파일
- frozen R004와 그 review 두 파일

snapshot은 symlink를 따라가지 않고 정렬된 relative path별 type, mode, uid, gid,
nlink, size, dev, ino, mtime_ns, ctime_ns와 regular-file SHA 또는 symlink target을
canonical JSONL로 만든다. subject digest는 canonical JSONL의 SHA-256이다. atime과
snapshot 생성 시각은 subject digest에서 제외한다. `protected-pre.json`과
`protected-post.json`은 evidence root에 저장하며 두 subject digest가 같아야 한다.
불일치하면 rollback하지 않고 모든 새 path를 `INCOMPLETE_UNTRUSTED`로 보존한다.

## 2. exact one-shot path와 draft publication 구조

```text
draft_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r004-r001
final_candidate_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r004-r001
evidence_root = /home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r004-r001
live_repository = /home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715
```

E1은 draft/evidence root만 만든다. final candidate root는 R004에서 반드시 absent로
남으며 source review 뒤 successor execution plan에서만 만든다.

각 기존 ancestor는 직전마다 `lstat`해 directory, non-symlink, expected uid,
world-writable false를 확인하고 dev/ino/mode를 기록한다. 없는 parent와 leaf는 umask
`077`, 단일 `mkdir`, mode `0700`으로 한 단계씩 만든다. `mkdir -p`, `exist_ok`,
overwrite, rename-over-existing는 금지한다. EEXIST나 metadata drift는 즉시 실패다.
실패·중단된 path는 수정·삭제·resume하지 않고, 별도 successor가 새 suffix를 쓴다.

handcrafted source는 `apply_patch`의 `Add File`로 draft에만 작성한다. 호출 직전마다
root inode와 target ABSENT를 재검사하고, 직후 regular/non-symlink, owner uid/gid
1000, `nlink=1`, expected mode와 SHA를 확인한다. `Update File`, `Delete File`, rename,
truncate는 금지한다. `apply_patch`가 `O_EXCL`을 제공한다고 주장하지 않으며,
E1 authoring은 §1.1의 single-writer operational control이다.

최종 candidate publication은 E1에서 실행하지 않는다. review된
`publish-source.py`를 successor의 bwrap에서 실행해, pinned candidate directory fd에
대해 `O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC`으로만 발행한다. 즉 hand-authored edit와
final no-replace publication을 분리한다.

## 3. 고정 backup과 Git selector

backup root는
`/home/ddobagi/.codex/backups/walksafe/20260802T0125KST-rc2-pre-roadmap`이며 directory
mode `0755`, uid/gid `1000/1000`, non-symlink다. 정확히 다음 네 regular file만 있다.

| basename | bytes | mode | SHA-256 |
|---|---:|---:|---|
| `repository-history.bundle` | 430056267 | 0600 | `56a95dd2b43612d27b5140dddb2857830cd2ce82571cad3748f7886dade10751` |
| `tracked-working-tree.patch` | 1441785 | 0600 | `69d2d378de56fd26a142f8220f2e527ff71e6126734664555f90a71a4f3ad558` |
| `untracked-files.tar.gz` | 360037403 | 0600 | `2de0201890d73a7067d2c5c879a69ffd31bcba8bfcc7ffbf21d0fdae1a7fe398` |
| `git-status-porcelain-v2.z` | 7703 | 0600 | `f3288d84b457cc5451c821572c2dea112016cfbde39bc00be3c5eb5654d3b6ca` |

각 파일은 uid/gid `1000/1000`, `nlink=1`이어야 한다. status gzip decode는 36,965
bytes, SHA `9745807cee2906c0a24d1d6d58bc192d05c8b6de0946f8311c30f570ba13a59d`,
NUL record 375개와 trailing NUL이다. archive는 regular member 정확히 2,550개이며
absolute/`..`/duplicate/link/special member는 0이다.

bundle 전체 ref 27개를 유일하다고 가정하지 않는다. 다음 두 keyed selector가 각각
정확히 한 번 존재하고 같은 OID여야 한다.

```text
HEAD = a3ad7eead6b5d834d3e0675422475a9aad351e3d
refs/heads/codex/walksafe-rc2-hardening-20260715 = a3ad7eead6b5d834d3e0675422475a9aad351e3d
```

clone은 exact branch를 명시하고, checkout 뒤 HEAD가 위 commit과 다르면 실패한다.

## 4. 좁은 runtime closure와 ambient 차단

R004에서는 source를 실행하지 않는다. 다만 static review가 다음 execution 환경을
완전히 알 수 있도록 `runtime-closure.json`에 아래 identity와 mount를 고정한다.

핵심 executable은 다음이다.

| path | bytes | mode | SHA-256 | version |
|---|---:|---:|---|---|
| `/usr/bin/bwrap` | 80424 | 0755 | `0abea81db798ebf6b4742ac0664802d97521547a353c2a0dbdc21d76cbbfd2c0` | 0.11.1 |
| `/usr/bin/git` | 4547768 | 0755 | `5516c9f362c29376ab9a499a33082f9f611941d8c75930c880e30ad109e39c9a` | 2.53.0 |
| `/usr/lib/git-core/git` | 4547768 | 0755 | `5516c9f362c29376ab9a499a33082f9f611941d8c75930c880e30ad109e39c9a` | 2.53.0 |
| `/usr/bin/bash` | 1540520 | 0755 | `3efccc187bafa75ff1e37d246270ab3e7aa559f242c7a52bf3ec2a1b5450bdbd` | 5.3.9(1) |
| `/usr/bin/tar` | 452624 | 0755 | `708692f9457a16a95a51fd881551b55b4ce72200d16b8c6d2bacbea857cfd9cc` | 1.35 |
| `/usr/bin/gzip` | 97560 | 0755 | `2ba6fd35db5f0b5561e56b97124a08b067464b795e8e08278367f23dc539d549` | 1.14 |
| `/home/ddobagi/.local/share/hanium-dreamup/python-3.12.13+20260510/bin/python3.12` | 102286096 | 0775 | `f7014f68e3c8f180811740735cf1dd5c28be6cff84db11d0ced2a8cd039670a0` | 3.12.13 |

Python stdlib root는 같은 prefix의 `lib/python3.12`이며 canonical tree digest는
`251d8ad591e07a6f6337f9fc5ef23897ac7df07b1c64712530771ca5d7734e6a`다. 알고리즘은
timestamp를 제외한 sorted JSONL `(type,path,mode,uid,gid,nlink,size,sha-or-target)`다.
현재 관찰값은 directory 181, regular 1,787, link/special 0, regular bytes
32,865,921이다.

runtime은 host 전체 `/usr`나 `/etc`를 bind하지 않는다. synthetic `/usr` 아래 exact
executable, `/usr/lib/git-core/git`, loader와 다음 direct library basename만
source=destination read-only bind한다.

```text
libpcre2-8.so.0 libz.so.1 libc.so.6 libpthread.so.0 libdl.so.2
libutil.so.1 librt.so.1 libm.so.6 libacl.so.1 libselinux.so.1
libcap.so.2 libtinfo.so.6 ld-linux-x86-64.so.2
```

`/bin -> usr/bin`, `/lib -> usr/lib`, `/lib64 -> usr/lib64`만 만든다. exact Python
binary와 stdlib, bootstrap source, backup, work/candidate leaf만 목적에 따라 bind한다.
`/dev`, `/proc`, tmpfs `/tmp` 외 실제 home, live repository, `/etc`, network는 보이지
않는다. 이 closure로 bundle selector 확인, clone, HEAD 확인, patch check, tar member
2,550 확인과 Python stdlib import가 실제 `exit 0`이었다. successor는 모든 bind
source의 SHA/type/mode와 stdlib tree digest를 실행 직전에 다시 검사한다.

`bwrap`은 항상 다음 prefix를 사용한다.

```text
--die-with-parent --new-session --unshare-all
--dev /dev --proc /proc --tmpfs /tmp
--clearenv
```

`--clearenv` 뒤 env는 다음 exact map이다.

```text
PATH=/usr/bin:/bin
HOME=/tmp/home
XDG_CONFIG_HOME=/tmp/xdg
TMPDIR=/tmp
LANG=C
LC_ALL=C
TZ=UTC0
GIT_CONFIG_NOSYSTEM=1
GIT_CONFIG_SYSTEM=/dev/null
GIT_CONFIG_GLOBAL=/dev/null
GIT_CONFIG_COUNT=0
GIT_ATTR_NOSYSTEM=1
GIT_EXEC_PATH=/usr/lib/git-core
GIT_TEMPLATE_DIR=/tmp/template
GIT_ALLOW_PROTOCOL=file
GIT_TERMINAL_PROMPT=0
GIT_OPTIONAL_LOCKS=0
PYTHONDONTWRITEBYTECODE=1
PYTHONNOUSERSITE=1
PYTHONHASHSEED=0
PYTHONUTF8=1
PYTHONCOERCECLOCALE=0
PYTHONIOENCODING=utf-8
```

`HOME`, XDG와 empty Git template는 tmpfs에 만든다. stdin은 명시 입력 외 닫고 umask
`077`이다. clone argv는 다음으로 고정한다.

```text
git clone --quiet --template=/tmp/template --no-hardlinks --no-checkout
  --single-branch --branch codex/walksafe-rc2-hardening-20260715
  -- /inputs/repository-history.bundle /work/projection/repo
```

이후 `git -C ... checkout --quiet --detach <expected-commit>`와
`git -C ... apply --check /inputs/tracked-working-tree.patch`를 먼저 실행한다. 모든 Git
호출에 `-c core.hooksPath=/dev/null`을 적용한다. network namespace는 없다.

## 5. draft exact file set과 source 계약

draft root는 정확히 다음 9개 regular file만 가진다.

```text
README.md
publish-source.py
build-projection.py
run-readonly-sandbox.sh
verify-bootstrap.py
runtime-closure.json
source-input-manifest.json
draft-content-manifest.json
draft-content-manifest.sha256
```

앞의 7개 payload만 handcrafted다. `draft-content-manifest.json`은 frozen payload에서
결정론적으로 생성하고 path, SHA, bytes, mode, uid, gid, type, `nlink=1`을 정렬해
담는다. 자신과 seal은 포함하지 않는다. `draft-content-manifest.sha256`은 manifest
SHA와 basename 한 줄만 담는다. 기본 mode는 0600이고 Python/shell source는 0700,
directory는 0700이다.

`publish-source.py`는 successor에서만 실행한다. draft root를 read-only dirfd로,
empty final root를 writable dirfd로 열고 양쪽 expected dev/ino/uid/mode를 확인한다.
manifest/seal과 exact 7 payload를 fd-relative `O_NOFOLLOW`로 열어 fstat-hash-fstat한다.
destination은 고정 basename만 `O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC`으로
생성해 write-all, fsync한다. final `content-manifest.json`과 detached seal도 같은
primitive로 생성한다. 어느 target이 존재하거나 bytes/metadata가 다르면 abort하고
기존 파일을 truncate/chmod/repair하지 않는다. 파일과 directory를 fsync한다.

`build-projection.py`도 successor에서만 실행한다. §3 입력을 재검증하고 exact branch를
no-hardlink clone/checkout한다. patch를 적용한 뒤 archive member를 먼저 전부
검사한다. absolute, empty, `.`, `..`, duplicate, link, special을 거부하고 regular
exact 2,550만, dirfd-relative `O_EXCL|O_NOFOLLOW`로 복원한다. archive owner는 무시하고
safe permission만 적용한다. target collision은 실패다. 복원된 porcelain-v2-z bytes는
status gzip decode와 byte-identical이어야 한다. `.git` 밖 tracked+untracked present
file은 regular/non-symlink, `nlink=1`; missing tracked는 exact 4여야 한다. 결과 manifest에
각 path/hash/bytes/mode와 Git set membership을 기록한다. live repository path는 입력에
없다.

`run-readonly-sandbox.sh`는 §4 profile을 순서까지 동일하게 materialize하는 launcher다.
projection은 원래 repository absolute path에 read-only bind하고, 새 work output leaf만
RW bind한다. 실제 live repository와 나머지 home은 namespace에 노출하지 않는다.

`verify-bootstrap.py`는 manifest/seal, ancestor 및 fd identity, backup, selector,
archive, status와 output allowlist를 독립 재계산한다. path traversal, duplicate,
symlink, hardlink, special, hash drift, status drift, extra output, preexisting final file
negative fixture는 각각 nonzero여야 한다. 현재 R004에서는 어느 source도 실행하지
않는다.

`source-input-manifest.json`은 R004와 두 R004 review, R003 및 두 R003 review,
backup 네 파일, §4 runtime identity만 결속한다. 미래 projection/wheel/result를
input으로 가장하지 않는다.

## 6. metadata CAS와 외부 anchor

manifest가 자신이나 filesystem metadata를 영구 봉인한다고 주장하지 않는다. E1
post receipt는 draft directory와 9개 file의 dev, ino, ctime_ns, mode, uid, gid, nlink,
size, SHA를 candidate 밖 evidence root에 기록한다. §8의 두 source review는 같은
draft seal SHA와 이 receipt SHA를 target으로 적어 외부 anchor가 된다.

successor consumer는 실행 직전에 각 ancestor와 root를 `O_DIRECTORY|O_NOFOLLOW`로
열고 exact 9-name set을 확인한다. 각 file을 dirfd-relative `O_NOFOLLOW`로 열어
fstat-hash-fstat하고 path `lstat`의 dev/ino와 비교한다. review target, receipt,
manifest/seal, bytes와 metadata가 모두 일치할 때만 fd를 유지한 채 read-only bind한다.
drift, extra, missing, link, `nlink!=1`이면 실행 0이며 기존 draft를 고치지 않는다.

## 7. R004 독립 review gate

서로 다른 새 agent 두 명이 frozen R004의 같은 SHA/bytes/lines를 독립 검수한다.
한 명은 authority/epoch/path/runtime 구조를, 다른 한 명은 공격·복구·oracle과 전체
로드맵을 본다. reviewer는 root author를 겸하지 않고 서로의 결과를 기다리지 않는다.

review 파일은 다음 exact two다.

- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R004-independent-structural-review-r001.md`
- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R004-independent-skeptical-review-r001.md`

각 파일에는 review_id/type, reviewer_agent/session, independence_attestation,
target_sha256/bytes/lines, verdict, blocking/major/minor를 정확히 한 번 둔다. root는
실제 orchestration event로 서로 다른 reviewer completion을 확인한다. review 파일의
identity 문자열은 권한 증거가 아니다.

어느 severity든 nonzero면 E1 write는 0이며 R004를 고치지 않고 successor roadmap을
만든다. 둘 다 `PASS 0/0/0`이고 §0 live authority gate도 true일 때만 E1을 수행한다.

## 8. draft source 독립 review

E1이 `DRAFT_SOURCE_PREPARED_NOT_EXECUTED`로 끝나면 구현에 참여하지 않은 새 reviewer
두 명이 같은 draft seal과 E1 post receipt를 검수한다. 한 명은 publisher/path/sandbox,
다른 한 명은 projection/recovery/negative oracle을 정적으로 본다. source를 실행하지
않는다. 결과는 E2의 다음 exact files다.

- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R004-R001-independent-boundary-review-r001.md`
- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R004-R001-independent-recovery-review-r001.md`

finding이 하나라도 있으면 draft를 수정하지 않고 successor path와 plan을 만든다.
둘 다 `PASS 0/0/0`이어도 그 결과가 실행 권한은 아니다. 별도 successor execution
plan이 exact draft seal/reviews/runtime/preflight/bwrap argv를 결속하고 독립 review를
통과해야 publication이나 projection build를 한 번 실행할 수 있다.

## 9. 전체 후속 로드맵과 수용 기준

1. **WP001 bootstrap draft** — R004 review 2개가 0/0/0이면 E1에서 source만 작성·봉인한다. 검증: protected pre/post 동일, draft exact 9, source 실행·network 0.
2. **WP002 source publication/projection** — source review와 successor review 뒤 final candidate를 O_EXCL 발행하고 backup에서 새 inode projection을 복구한다. 검증: exact status bytes, tracked/untracked manifest, live repo·backup delta 0.
3. **WP003 wheel acquisition** — network 전용 격리 epoch에서 exact Python/pip/pip-tools/setuptools wheelhouse와 URL/hash receipt만 만든다. 검증: output allowlist와 독립 hash review; 이 epoch에서 project source 실행 0.
4. **WP004 offline lock A** — reviewed wheelhouse를 network 없는 sandbox에서 two-build한다. 검증: 두 output byte-identical, 유일 version delta Pillow 12.2.0→12.3.0, hash install/pip-check/fail-first node PASS.
5. **WP005 runner B** — exact orphan 5개를 active 1/historical 2/staged 2에 배정한다. 검증: discovered=assigned=132, duplicate/orphan=0, logical pytest call exact 4, default 순서와 negative fixtures PASS.
6. **WP006 Gateway C** — historical exact4와 current exact5 checker를 분리한다. 검증: 기존 Node 62와 Python 34 회귀, product behavior delta 0.
7. **WP007 artifact D** — fixed `2026-07-22T14:30:00+09:00` reconstruction의 `plan.canonical_outputs`로 historical post bytes를 검증하고 current self-seal을 별도 검사한다. 검증: targeted fail-first가 각 기대 원인으로 실패 후 모두 PASS.
8. **WP008 output review/control repair candidate** — A~D output을 독립 재실행·검수하고 live apply가 아닌 versioned repair candidate만 만든다. 검증: exact allowed delta, crash/CAS negative oracle, official credit 0.
9. **WP009 active leaf materialization** — v2.4 frontier에서 exact 한 leaf를 정식 생성·시작한 뒤에만 fail-first 최소 구현을 한다. 우선 후보는 FP-048 server report-object encryption이나 frontier 재계산이 최종 결정한다.
10. **WP010 product/artifact loop** — leaf별 targeted/component/full 회귀와 257 artifact queue를 함께 갱신한다. 내부 증거와 외부 owner/real-event lane을 분리한다.
11. **WP011 formal/device/Gates/release** — 동일 immutable candidate에서 실제 권한자와 실기기·현장 증거가 생길 때만 formal 279, device/event, 5 Gate를 올린다. 그 전에는 `0/279`, `0/0`, `0/5`, `NOT_ELIGIBLE`, `NOT_COMPLETE`를 유지한다.

각 WP는 직전 실제 output을 입력으로 하는 작은 plan과 독립 review를 거친다. 외부
조건이 막힌 lane은 상태를 과장하지 않고 멈추되 독립 내부 lane은 계속한다.

## 10. R003 finding closure와 다음 단일 행동

| R003 finding | R004 closure |
|---|---|
| structural B1 / skeptical B-02 | E0/E1/E2와 exact write allowlist를 분리; E1만 repository delta 0 주장 |
| structural B2 / skeptical B-03 | explicit threat model, draft-only apply_patch, reviewed dirfd/O_EXCL publisher, protected pre/post oracle |
| structural M1 | 네 absolute filename, bytes/hash/mode 및 exact ref/commit selector 고정 |
| skeptical B-01 | live user authority와 review quality를 독립 conjunct로 고정; review는 권한 아님 |
| skeptical M-01 | 영구 metadata seal 주장 제거; external receipt/review anchor와 consume-time fd CAS |
| skeptical M-02 | 실제 PASS한 narrow mount closure, exact Python stdlib tree digest와 runtime identity 고정 |
| skeptical M-03 | empty home/XDG/template, Git/Python env와 argv, hooks/network 차단 고정 |

다음 단일 행동은 이 R004를 freeze하고 서로 다른 두 새 agent에게 같은 target의
structural/skeptical review를 동시에 요청하는 것이다. 두 결과 중 하나라도 nonzero면
draft root와 evidence root를 만들지 않는다.
