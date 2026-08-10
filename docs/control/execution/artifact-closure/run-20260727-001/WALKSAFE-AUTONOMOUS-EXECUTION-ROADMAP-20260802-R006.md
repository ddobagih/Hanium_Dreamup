# WalkSafe 자율 실행 로드맵 20260802 R006

## 0. 지위와 frozen base

```text
document_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R006
document_class = INTERNAL_EXECUTION_ROADMAP_ADD_ONLY_CORRIGENDUM
review_status = PENDING_TWO_INTERNAL_REVIEWS
current_executable_scope = WP001_BOOTSTRAP_DRAFT_AUTHORING_ONLY
draft_source_execution_allowed = false
network_allowed = false
live_product_or_canonical_mutation_allowed = false
official_progress_delta = 0
artifact_completion_credit_delta = 0
release_claim = NOT_ELIGIBLE
```

R006의 frozen normative base는 다음 세 파일이다.

| input | SHA-256 | bytes | lines |
|---|---|---:|---:|
| R005 roadmap | `75b7b2742a898ddae05f44ee2fb6378ce5c00f8982c7c4a9e74b0e7809d692c0` | 21066 | 362 |
| R005 structural review | `220405e381d330034f3b89f8ab7d09d82570e9bbb3d348219164364294bcf510` | 4139 | 64 |
| R005 skeptical review | `52da04c82e4c9acbdec6f34fe6e6bb797beeaec2a9312adf376498d318c97288` | 3602 | 59 |

R005 structural review는 `PASS 0/0/0`, skeptical review는 `REVISION_REQUIRED
1B/0M/0m`이다. R005 draft/evidence/final path에는 아무것도 만들지 않는다. R006은
skeptical B-01의 terminal receipt crash cycle만 교정한다.

R005의 §0~§10은 아래 exact replacement/override를 적용한 뒤 모두 R006의 normative
body다. 충돌하면 R006이 우선한다. 그 밖의 문구를 재해석하거나 작성자가 새 값을
선택할 수 없다.

## 1. 권한은 그대로 독립 gate다

R005 §0의 live-user-authority 계약을 그대로 상속한다. R006과 review는 실행 권한을
만들지 않는다. E1 직전 현재 goal active와 이후 사용자 취소·대체 부재를 확인한다.
전이식은 `LIVE_USER_AUTHORITY && TWO_R006_REVIEWS_ZERO`다. 배포, 유료 서비스,
비밀값, 실제 기관 제출, 데이터 삭제는 계속 범위 밖이다.

## 2. exact path replacement

R005의 path에서 `r005-r001`은 모두 `r006-r001`로 바꾼다.

```text
draft_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-draft-r006-r001
final_candidate_root = /home/ddobagi/.codex/candidates/walksafe/20260802-wp001-bootstrap-source-r006-r001
evidence_root = /home/ddobagi/.codex/work/walksafe/20260802-wp001-bootstrap-r006-r001
```

R006 review 전 세 path와 leaf는 모두 absent여야 한다. E1은 draft/evidence만 만들고
final은 absent로 남긴다. ancestor/openat/mkdirat, no-resume, mode/owner/type/nlink,
confined apply-patch, fd-bound bwrap, backup/runtime, branch/status, tar restore와 negative
oracle은 R005 그대로다.

## 3. exact epoch replacement

R005 §1.2 표를 다음으로 전부 교체한다. repository 기준 directory는 R005와 같다.

| Epoch | exact write set |
|---|---|
| `E0_PLAN_AUTHOR` | `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R006.md` 하나 |
| `E0_PLAN_REVIEW` | §7의 R006 review 두 파일만; frozen R006 write 0 |
| `E1_DRAFT_AUTHOR` | §2 draft root의 exact 9개와 §4 evidence root의 exact 4개만 |
| `E2_SOURCE_REVIEW_LOG` | §8 source review 두 파일과 `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715/daylog/2026-08-02.md`만 |
| `E_FAIL_SUCCESSOR` | finding이 있으면 `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R007.md` 하나 |

R006 review가 nonzero면 E1/E2 source path write는 0이다. local-memory 기록은 E2 뒤
별도 automation epoch다.

## 4. evidence protocol replacement

R005의 evidence exact 3개, `e1-result.json`, terminal-marker 설명과 그것을 참조하는
모든 acceptance 문구를 폐기하고 다음 exact 4개/순서로 교체한다.

```text
1 authorization-gate.json
2 draft-post.json
3 e1-observation.json
4 E1.COMPLETE
```

앞의 세 파일은 canonical JSON 규칙과 mode `0600`을 따른다. `E1.COMPLETE`도 regular,
non-symlink, uid/gid `1000/1000`, mode `0600`, `nlink=1`이며 UTF-8 text다. extra entry는
0이어야 한다.

### 4.1 `authorization-gate.json`

현재 live authority의 일회성 관찰, R006 review target identity와 scope exclusion을
기록한다. 권한을 재생하지 않으며 자기 SHA/metadata를 포함하지 않는다. 이 파일의
confined apply-patch 호출 결과는 parent가 rc/stdout/stderr와 tool post-identity를
관찰한 뒤 다음 단계로 간다.

### 4.2 `draft-post.json`

R005 §7의 draft-post schema를 그대로 쓴다. draft root/child-list와 exact 9 file의
metadata/hash를 담고 자신은 포함하지 않는다. 생성 호출 결과와 tool post-identity를
parent가 관찰한 뒤 다음 단계로 간다.

### 4.3 `e1-observation.json`

이 파일은 자신보다 **앞선** 호출과 관찰만 기록한다.

- authorization file, payload 7개, draft manifest/seal, draft-post의 creation order
- 각 prior confined apply-patch argv digest, stdin patch digest, rc, stdout/stderr digest
- prior invocation마다 expected rc `0`, stderr bytes `0`, tool pre/post identity 동일
- draft/evidence current exact prefix, draft manifest/seal와 draft-post SHA
- source/project/backup command와 network count `0`
- live repository/backup/canonical/product/official progress delta `0`
- status `READY_FOR_E1_COMMIT_NOT_TERMINAL`

자기 생성 invocation의 rc/stdout/stderr/post-tool identity나 미래 marker를 기록하지
않는다. parent는 이 파일용 confined apply-patch가 끝난 뒤 그 rc0, stderr-empty,
post-tool identity와 exact bytes/metadata를 실제로 관찰한다. 하나라도 다르면 marker를
만들지 않고 path를 incomplete로 보존한다.

### 4.4 `E1.COMPLETE` commit point

parent가 §4.3 사후 관찰을 마친 뒤에만 마지막 confined apply-patch `Add File`로 marker를
만든다. exact bytes는 다음 canonical four lines다. 세 SHA 값은 marker 생성 전에 이미
존재하는 file bytes로 계산한다.

```text
WS-WALKSAFE-WP001-E1-COMPLETE-V1
draft_content_seal_sha256=<draft-content-manifest.sha256 file SHA-256>
draft_post_sha256=<draft-post.json SHA-256>
e1_observation_sha256=<e1-observation.json SHA-256>
```

마지막 LF가 필수다. marker는 자기 SHA나 자기 creation invocation의 rc, stdout,
stderr, post-tool identity를 주장하지 않는다. marker invocation의 사후 관찰은
completion evidence의 입력이 아니다.

marker가 exact bytes로 완전히 존재하면, controller가 그 호출 결과를 관찰하기 전에
crash했더라도 recovery verifier가 §5를 read-only로 재계산해 commit을 판정할 수 있다.
marker가 absent, partial, wrong bytes/type/mode/owner/nlink이면 incomplete다. 어느 경우든
기존 path에 새 write, repair, resume를 하지 않는다.

## 5. crash-consistent E1 acceptance

E1 상태는 marker 존재만으로 결정하지 않고 다음 read-only 논리곱으로 결정한다.

- live authority와 R006 review 두 개가 `PASS 0/0/0`
- final candidate absent
- draft exact 9, evidence exact 4, extra/link/special/hardlink 0
- draft manifest/seal와 draft-post가 physical bytes/metadata의 독립 재계산과 일치
- e1-observation이 prior call만 기술하며 그 recorded prior results가 모두 PASS
- marker four lines가 current seal/draft-post/observation SHA로 재생성한 bytes와 exact equal
- source 실행, project/backup command, network 0
- live repository, backup, canonical, product, formal/device/Gate/release/official delta 0

marker 이전 crash는 marker absent이므로 incomplete다. marker write 도중 crash는 absent나
invalid marker이므로 incomplete다. complete marker 뒤 crash는 위 read-only 재검산이
PASS하면 `DRAFT_SOURCE_PREPARED_NOT_EXECUTED`다. marker 호출의 미관찰 rc를 추정하거나
receipt에 소급 기록하지 않는다.

negative fixtures는 새 scratch마다 다음 세 injection을 별도로 검사한다.

1. marker 전 crash -> marker absent, success state false, rerun write 거부
2. marker partial/wrong bytes -> success state false, repair/resume 거부
3. exact marker 생성 직후 parent-observation 전 crash -> read-only recovery PASS, 추가 write 0

각 fixture는 R005의 sentinel bytes/metadata, output subset, success-receipt/no-resume
postcondition도 그대로 만족해야 한다.

## 6. source input과 review filename replacement

`source-input-manifest.json`은 R006과 R006 review 두 개를 추가하고 R005 및 그 review를
history input으로 유지한다. R006의 미래 seal/receipt/output은 input으로 넣지 않는다.

source review exact files는 다음으로 R005 §9를 교체한다.

- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R006-R001-independent-boundary-review-r001.md`
- `WALKSAFE-WP001-BOOTSTRAP-DRAFT-R006-R001-independent-recovery-review-r001.md`

후속 전체 roadmap은 R005 §9를 그대로 유지한다. source review 두 개가 0/0/0이어도
별도 publication/projection plan과 review 전에는 source를 실행하지 않는다.

## 7. R006 independent review gate

서로 다른 새 agent 두 명이 frozen R006의 같은 SHA/bytes/lines를 서로의 결과 없이
검수한다. exact files는 다음이다.

- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R006-independent-structural-review-r001.md`
- `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R006-independent-skeptical-review-r001.md`

각 file은 review_id/type, reviewer_agent/session, independence_attestation,
target_sha256/bytes/lines, verdict, blocking/major/minor를 정확히 한 번 둔다. root는
orchestration completion으로 reviewer가 실제로 다른지 확인한다. 어느 severity든
nonzero면 E1은 0이고 E_FAIL만 수행한다. 둘 다 `PASS 0/0/0`이고 §1 authority도 true일
때만 E1을 수행한다.

## 8. finding closure와 next action

| finding | R006 closure |
|---|---|
| R005 skeptical B-01 | prior-only observation과 non-self-claiming `E1.COMPLETE` commit point를 분리 |
| crash before/during marker | marker absent/invalid, incomplete, no resume |
| crash after complete marker before parent observation | exact dependencies를 read-only 재계산해 deterministic PASS/FAIL |
| evidence allowlist | exact 4 files, order, schema, mode와 no-extra predicate |

다음 단일 행동은 R006을 freeze하고 서로 다른 두 새 agent에게 R005 frozen base와 R006
delta를 함께 독립 review시키는 것이다. finding이 하나라도 있으면 R006 draft/evidence
path를 만들지 않는다.
