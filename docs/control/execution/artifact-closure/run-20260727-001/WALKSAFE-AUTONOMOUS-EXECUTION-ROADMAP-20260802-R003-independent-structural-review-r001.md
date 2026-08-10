# WalkSafe R003 독립 structural review r001

```text
review_id = WS-WALKSAFE-R003-INDEPENDENT-STRUCTURAL-REVIEW-R001
review_type = INTERNAL_STRUCTURAL_REVIEW
reviewer_agent = /root/r003_structural
reviewer_session = /root/r003_structural@20260802-r001
independence_attestation = TRUE
target_sha256 = 2c15a76ad4c5e9fe2d9aa1c6558f92df7bba56827d9374b45dcbc92d75b84383
target_bytes = 13798
target_lines = 235
verdict = REVISION_REQUIRED
blocking = 2
major = 1
minor = 0
```

## 판정 근거

대상은 R002의 review identity, 미래 input, manifest 자기참조, network epoch,
self-certified checker, stale fail-first, one-shot genesis 문제를 구조적으로 분리했다.
또한 pretransition snapshot 자체를 DOC-01/DOC-05의 posttransition receipt bytes로
보지 않고, 고정 시각 reconstruction의 `plan.canonical_outputs`에서 historical post
bytes를 얻도록 한 교정은 적절하다. 그러나 아래 세 finding 때문에 현재 scope는
선언한 경계 그대로 실행할 수 없다.

## Blocking findings

### B1. 필수 control artifact 쓰기와 live-repository write 0가 양립하지 않는다

§0은 현재 허용 작업을 저장소 밖 candidate source 작성으로만 한정하고 live
repository mutation을 금지한다. 반면 §3은 저장소 내부의 adjacent review 두 파일을
source-build 선행조건으로 요구하고, §4.4는 실패 시 저장소의 daylog에 판정을
남기도록 요구한다. review를 현재 epoch 밖으로 볼지, 허용된 control write로 볼지,
source-build 전후 중 어느 baseline에서 write 0를 판정할지도 정의하지 않았다.
따라서 review gate를 충족하거나 실패를 기록하는 정상 경로가 completion의 live
repository write 0와 동시에 참일 수 없다.

수정 시 review/control-record phase와 WP001 epoch의 시작점을 명시적으로 분리하고,
각 phase의 exact writable path를 열거해야 한다. daylog를 저장소 밖 receipt로
대체하거나 예외 write로 인정한다면 그 파일의 pre/post CAS와 허용 delta도 함께
고정해야 한다.

### B2. 현재 source-build writer는 exact write boundary를 강제하거나 증명하지 못한다

§4.1의 ancestor `lstat`는 검사 뒤 path component 교체가 가능한 TOCTOU check이며,
이후의 `apply_patch`는 read-only namespace, held directory fd, `openat2`/`O_NOFOLLOW`
및 `O_EXCL` writer 중 어느 것으로도 candidate root에 제한되지 않는다. 또한 dirty
live repository와 candidate 밖 경로에 대한 pre/post byte manifest 또는 write-set
receipt가 completion 조건에 없다. 따라서 잘못된 patch path나 동시 path 교체가
candidate의 8-file manifest를 통과하면서 다른 경로를 쓸 수 있다. 이는 R002의
live-write 및 ancestor ambiguity를 현재 epoch에서 해소하지 못한다.

수정 시 writer 자체를 candidate root만 writable인 검수된 namespace에 넣거나,
신뢰한 dirfd에서 create-only 파일을 여는 최소 writer로 바꾸고, 모든 외부 경로를
read-only로 강제해야 한다. 정책 문구만 유지한다면 적어도 frozen pre-state와
post-state의 exact delta oracle, 매 write 직전 component 재검증, exclusive create를
완료 조건으로 고정해야 한다.

## Major finding

### M1. builder에 넣을 exact backup selector와 Git ref/commit이 결정되지 않았다

§2는 backup root와 네 hash만 주고 exact filename을 고정하지 않으며, §4.2는 exact
branch/HEAD clone을 요구하면서 기대 ref와 commit 또는 bundle에서 이를 유일하게
선택하는 규칙을 주지 않는다. source 작성자가 frozen input을 보고 임의로 selector와
ref를 정하면 source review가 사후 선택을 검사할 뿐 roadmap이 요구한 deterministic
bootstrap을 검증할 수 없다. 네 absolute input path, 기대 ref/commit, 그리고 bundle
head가 비유일할 때 abort하는 규칙을 source 작성 전에 고정해야 한다.

## 결론

R003의 pre-execution artifact 교정과 여러 epoch 분리는 유지할 수 있다. 다만 B1의
phase/write 계약, B2의 강제 가능한 writer 경계, M1의 deterministic selector를 먼저
고친 add-only successor roadmap이 필요하며 현재 WP001 source build로 진행하면 안
된다.
