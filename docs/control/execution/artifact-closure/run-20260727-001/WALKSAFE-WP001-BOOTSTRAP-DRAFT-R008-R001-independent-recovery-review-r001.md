# WalkSafe WP001 bootstrap draft R008 independent recovery review R001

```text
review_id = WS-WALKSAFE-WP001-BOOTSTRAP-DRAFT-R008-R001-INDEPENDENT-RECOVERY-REVIEW-R001
review_type = INDEPENDENT_RECOVERY_SOURCE_REVIEW
reviewer_agent = /root/wp001_recovery_review
reviewer_session = 019fbf21-03ef-7fb3-ba49-7f6ee38686b4
reviewed_at = 2026-08-02 Asia/Seoul
verdict = REVISION_REQUIRED
blocking = 7
major = 0
minor = 0
```

## Independence and target

이 reviewer는 R008 draft 작성에 참여하지 않았고, 동시 수행 중인 boundary review 또는
다른 WP001 source-review 결과를 읽지 않았다. 검토 중 candidate source를 실행, import,
`py_compile`하지 않았으며 candidate/evidence bytes를 수정하지 않았다.

```text
draft_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r008-r001
target_draft_seal_file_sha256 = db763531df2bee5f5a581bc06f7a9a367eb36c5dd1d965afdeb0f4e8d6a12c51
sealed_manifest_sha256 = a7a64ddc93dace6cd4116fd58821ab240b639a55156989bb1450bf0feecc0397
target_draft_post_sha256 = a305729aa86beefb979a81f7e25cf5aa8fa92857967c3646d8cebddd4b80300d
target_verifier_sha256 = b9fcec59c4499ac25a7e5072c371c840c904bf018dc4f1e9c1f5f3cb10796245
```

세 target identity는 read-only SHA-256 재계산과 일치했다. seal bytes도 위 manifest
SHA를 `draft-content-manifest.json`에 결속한다.

## Method

- exact draft/evidence child set, file size/hash/mode와 manifest/seal 결속을 read-only로
  대조했다.
- 네 Python source를 줄 번호 기준으로 전부 읽고 `ast.parse`만 수행했다. 네 파일 모두
  parse PASS였고 candidate module body는 평가하지 않았다.
- projection 복원, raw USTAR/gzip, bundle selector, attached branch/upstream/raw status,
  physical/Git inventory, publisher crash boundary, E1 marker recovery, negative rerun oracle를
  producer와 verifier 양쪽에서 역추적했다.
- 실제 결속 control file mode는 read-only `stat`으로 확인했고, Git의 optional-lock 및
  fsmonitor 동작은 설치된 매뉴얼과 대조했다. 아래 closure의 실행 검증은 이 review에서
  수행하지 않았으며 후속 승인 epoch에만 허용한다.

## Findings

### B-01 — control identity verifier가 정상 `0664` 문서를 무조건 거부한다

`verify-bootstrap.py:151-157`의 공용 `regular()`는 모든 입력에 mode `0600`을
강제한다. `verify_controls()`는 이 함수를 current/history control 문서에 그대로
사용한다(`verify-bootstrap.py:186-198`). 그러나 결속된 R004~R008 repository control
문서는 실제 regular `0664`, uid/gid `1000/1000`, nlink 1이다. 따라서 현재 exact
control fd를 주는 `verify-draft`와 `verify-e1`은 내용 검증 전 항상 `METADATA`로
실패한다. 이는 알려진 의심을 독립 재현한 blocking false negative다.

Successor-only closure predicate: draft/evidence/final의 `0600` 정책과 repository
control의 frozen physical mode 정책을 서로 다른 검사기로 분리하고, exact R008
current/history inputs가 기대 mode까지 통과해야 한다.

Validation: successor source에서 call-site별 metadata policy를 정적으로 확인하고, 별도
승인 실행에서 현재 `0664` control fd로 `verify-draft`와 `verify-e1` 정상 경로를 각각
PASS시킨다.

### B-02 — host fixed-fd remap 방향이 뒤집혀 sandbox를 시작할 수 없다

`open_runtime()`/`open_backups()`는 `{fixed_fd: opened_fd}`를 만든다
(`run-readonly-sandbox.py:318-345`). `run()`도 같은 방향으로 source/output/preflight를
합친다(`run-readonly-sandbox.py:675-678`). 그러나 `duplicate_map()`은 mapping의 key를
source fd로 복제하고 value를 destination으로 사용한다
(`run-readonly-sandbox.py:544-547`). fork child는 이를 그대로 호출한다
(`run-readonly-sandbox.py:549-560`). 보통 열린 low fd 대신 아직 열리지 않은 200~224를
먼저 `F_DUPFD_CLOEXEC`하므로 bwrap fd-exec 전에 실패한다. 우연히 그 번호가 열려 있어도
잘못된 object를 low fd 위에 복제한다.

Successor-only closure predicate: collision-safe remapper가 명시적으로 `opened_fd ->
fixed_fd`를 수행하고, exec 직전 200~224의 각 fstat/hash가 preflight role과 일치하며
그 외 fd는 닫혀 있어야 한다.

Validation: 정적으로 mapping 방향과 keep-set을 확인한 뒤, 후속 승인 실행에서 publish와
projection profile 모두 child mount identity와 fd-exec 진입을 증명한다.

### B-03 — frozen Git argv contract와 producer의 실제 argv가 다르다

`runtime-closure.json:1`은 clone source/destination을
`/inputs/repository-history.bundle`, `/work/projection/repo`로, status `-C`도
`/work/projection/repo`로 고정한다. 상속된 exact command도 같은 literal path다. 반면
producer는 `fdpath()`를 정의하고(`build-projection.py:115`), clone에
`/proc/self/fd/<bundle-fd>`와 동적으로 열린 projection fd 경로를 쓴다
(`build-projection.py:226-231`). 모든 repo Git command도 동적 repo fd를 `-C`에 쓴다
(`build-projection.py:160-162`). 따라서 command observation은 sealed closure의 exact
argv와 일치할 수 없다.

Successor-only closure predicate: successor가 literal mount-path argv 또는 명시적으로
동결한 fd-path argv 중 하나만 선택하고, runtime closure, source, inherited roadmap과
receipt oracle이 byte-for-byte 같은 argv 계약을 가져야 한다.

Validation: 정적으로 closure JSON과 AST-derived argv template를 비교하고, 후속 승인
실행에서는 관찰된 모든 Git argv가 그 template의 exact instance임을 확인한다.

### B-04 — publication/projection success oracle이 입력과 복원 contents를 독립 결속하지 않는다

publish controller는 output seal의 SHA를 output 자체에서 읽어 다시 expected 값으로
넘긴다(`run-readonly-sandbox.py:595-596,701-705`). 따라서 internally self-consistent한
다른 payload를 reviewed draft와 비교하지 않는다. projection controller는 manifest의
`repository_tree_digest`가 **있을 때만** 비교한다
(`run-readonly-sandbox.py:598-613`). producer manifest에는 그 field가 없다
(`build-projection.py:491-525`). 별도 `verify_projection()`도 branch/OID/upstream/raw
status와 Git set 일부만 검사하고(`verify-bootstrap.py:446-478`), tar path set,
per-file content hash/mode/type/nlink 및 manifest의 physical/tar inventories를 현재 repo와
비교하지 않는다. 예를 들어 기존 untracked file bytes를 같은 pathname에서 바꾸면
`-unormal` raw status와 untracked count는 그대로여서 PASS할 수 있다.

Successor-only closure predicate: final output의 payload/manifest는 reviewed draft의
anchored identities와 직접 같아야 하고, projection은 backup tar의 exact path/content/mode
oracle 및 tracked worktree oracle을 현재 physical tree와 전부 비교해야 한다. repository
tree digest를 쓰면 producer가 필수로 기록하고 verifier가 독립 재계산해야 한다.

Validation: 정적으로 모든 manifest field-to-oracle 소비 관계를 확인하고, 후속 승인
실행에서 untracked content 변경, path 교체, symlink/FIFO/hardlink, tracked-content 변경을
각각 주입해 controller와 independent verifier가 모두 거부함을 보인다.

### B-05 — recovery Git 검사가 read-only도 local-config-safe도 아니다

producer의 fixed environment에는 `GIT_OPTIONAL_LOCKS=0`이 있다
(`build-projection.py:27-38`). 독립 verifier의 Git environment에는 이 값과
`core.fsmonitor` 차단이 없다(`verify-bootstrap.py:434-443`). Git 문서상 기본
`git status`는 optional index refresh를 할 수 있고, repository-local
`core.fsmonitor=<pathname>`은 hook command를 실행할 수 있다. `-c
core.hooksPath=/dev/null`만으로 후자를 제거하지 못한다. 즉 read-only recovery verifier가
projection을 변경하거나 untrusted `.git/config`가 지정한 command를 host에서 실행할 수
있다.

Successor-only closure predicate: verification Git argv/env는 optional writes를
금지하고 executable config surface를 명시적으로 무력화하며, repository 전체 pre/post
identity가 같아야 한다. verifier 자체도 reviewed read-only confinement 안에서만
동작해야 한다.

Validation: 정적으로 exact env/config overrides를 확인하고, 후속 승인 실행에서 index
refresh 필요 상태와 hostile `core.fsmonitor` fixture 모두에 대해 command 실행 0 및 repo
byte/metadata delta 0을 확인한다.

### B-06 — `negative`는 exact negative oracle이 아니라 신뢰되지 않은 host recipe runner다

`verify_negative()`는 scenario가 제공한 `argv`, `environment`, expected class,
output allowlist를 그대로 신뢰해 host에서 timeout 없이 실행한다
(`verify-bootstrap.py:514-540`). `--case`는 scenario와 매핑되지 않고 `producer_fd`도
identity 또는 argv 사용 여부를 검사하지 않는다(`verify-bootstrap.py:543-564`). 따라서
producer를 전혀 호출하지 않는 `/bin/false` 같은 recipe도 PASS를 만들 수 있으며,
상속된 symlink/regular-parent/mount/`../`/duplicate/hardlink/FIFO/hash/status/extra/
preexisting/crash exact cases를 증명하지 못한다. 또한 sandbox failure JSON은 `category`를
쓰지만(`run-readonly-sandbox.py:741-747`) parser는 `failure_class` 또는 text pattern만
인식한다(`verify-bootstrap.py:501-512`). 첫 실행 뒤만 sentinel을 검사하고 두 번째 실행
뒤에는 재검사하지 않으며, preexisting target의 before/after 불변도 요구하지 않는다
(`verify-bootstrap.py:518-545`). `before-first` crash는 같은 fault가 재발해 output이
그대로인 것만으로 no-resume를 거짓 PASS할 수 있다.

Successor-only closure predicate: exact case id가 reviewed producer/seal, fixed sandbox
argv/env/fd map, fault point, expected first/second class와 exact output delta에 일대일로
결속되어야 한다. 두 실행 모두 timeout/confinement, sentinel pre/post, preexisting target
불변, success receipt 0을 검사하고, no-resume 재실행은 fault 재발이 아니라 one-shot
거부를 구분해야 한다.

Validation: 정적으로 case table과 임의 argv/env 제거를 확인하고, 후속 승인 실행에서
상속된 모든 fixture 및 publication 각 crash boundary를 fresh scratch별로 실행한다.

### B-07 — E1 recovery verifier가 marker가 결속한 evidence 의미를 충분히 검증하지 않는다

`verify_e1()`은 auth에서 일부 SHA/authority boolean, draft-post에서 일부 file field,
observation에서 status/command-count와 invocation의 일부 field만 검사한다
(`verify-bootstrap.py:227-277`). auth의 status/scope/delta/review verdict, draft-post의
schema/status/root/mtime/name-NUL digest와 file-list uniqueness, observation의 schema,
creation order/roles/argv·stdin·stdout hashes, deltas, draft/evidence sets,
`candidate_source_executed`, source static checks 등은 검증하지 않는다. marker는 현재
observation SHA를 재계산할 뿐이므로, 예를 들어 explicit nonzero delta 또는
`candidate_source_executed=true`를 둔 canonical observation과 그 새 marker가 나머지
부분 조건을 만족하면 recovery PASS가 가능하다.

Successor-only closure predicate: auth/draft-post/observation은 exact schema/key/type/value
allowlist로 전 필드를 검증하고, source/network/project/backup command 0, 모든 delta 0,
candidate execution false, exact creation prefix/invocation roles와 physical snapshot 전체를
서로 및 외부 frozen anchors에 결속해야 한다.

Validation: 정적으로 schema-to-check coverage를 완전 열거하고, 후속 승인 실행에서 각
보안 관련 field 단독 변조와 marker 재생성을 모두 거부시킨 뒤 marker-before,
marker-partial, exact-marker-after-write의 세 recovery fixture를 확인한다.

## Adversarially checked controls without a separate finding

`publish-source.py:164-211`의 create-only 순서, file fsync, final directory fsync,
source post-check와 partial-root no-resume 형태는 정적으로 일관됐다.
`build-projection.py:93-113,177-203,261-413,426-489,554-595`는 exact compressed input
size/hash, status gzip single-stream identity, keyed bundle selectors, attached branch/OID와
upstream 제거, raw status equality, raw USTAR checksum/type/path/two-zero/aligned trailer,
two-pass header/content identity, exact missing/untracked/physical set을 producer 내부에서
검사한다. 이 positive control은 B-02~B-06의 controller/verifier 결함을 상쇄하지 않는다.

## Verdict

Blocking finding이 7건이므로 이 sealed R008 draft는 publication/projection 실행이나 E1
recovery acceptance에 사용할 수 없다. successor source와 새 seal/draft-post가 위 closure
predicate를 모두 만족하고 독립 static review를 다시 통과하기 전 상태는
`REVISION_REQUIRED`이다. 본 review는 실행 권한, artifact completion credit, release 또는
official progress를 만들지 않는다.
