# WalkSafe R016 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R016-INDEPENDENT-SKEPTICAL-REVIEW-R001
type = INDEPENDENT_SKEPTICAL_STATIC_REVIEW
reviewer_agent = /root/r016_skeptical_review
reviewer_axis = SKEPTICAL_ADVERSARIAL
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R016.md
target_sha256 = 49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2
target_bytes = 12690
target_lines = 292
reviewed_at = 2026-08-02T10:23:29+09:00
status = PASS
findings = 0/0/0
blocking = 0
major = 0
minor = 0
authority_granted = R016_CANDIDATE_SOURCE_BUILD_REVIEW_ONLY
```

## 범위와 identity

frozen R016 292줄 전부를 읽고 R015 skeptical blocker 4건, R015 structural chronology
blocker, 현재 validator/builder/test 구현, source pin 및 add-only publication 경로와
대조했다. 시작 identity는 `ddobagi@localhost`, 저장소
`/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715`, branch
`codex/walksafe-rc2-hardening-20260715`, HEAD
`a3ad7eead6b5d834d3e0675422475a9aad351e3d`였다. 대상은 regular file,
mode `0664`, uid/gid `1000/1000`, nlink 1이며 동결 SHA-256·bytes·lines와 일치했다.

검수는 비효력 candidate source/build/review 가능성만 대상으로 했다. source, candidate,
canonical, checkpoint, Goal, 제품은 실행하거나 수정하지 않았고 이 review 파일만
add-only로 추가했다.

## 공격 검증 결과

| 축 | 판정 | 근거 |
|---|---|---|
| R015-SK-B01 monotonic freshness | CLOSED_FOR_CANDIDATE_ONLY | R016은 wall clock을 비권위 감사값으로 한정하고, 실제 activation을 R017로 미루며 같은 supervisor process·lock epoch의 `time.monotonic_ns()` PREPARE→C1 `<=600s`와 599/600/601 fixture를 요구한다. R016 자체는 supervisor·activation write 권한을 부여하지 않는다. |
| R015-SK-B02 fixed receipt wedge | CLOSED_FOR_CANDIDATE_ONLY | pre-C1 partial을 `NEW_REVISION_REQUIRED_UNCOMMITTED_AUTHORITY_ZERO` terminal로 고정해 새 QUICK나 fixed path 재사용을 요구하지 않는다. resume·overwrite·delete·repair가 모두 금지된다. |
| R015-SK-B03 QUICK-dependent partial mismatch | CLOSED_FOR_CANDIDATE_ONLY | `RESUME_SAME_TX`와 exact-member skip을 제거해 QUICK-A bytes를 QUICK-B로 복구한다는 도달 불가능한 주장을 폐기했다. C0/v2.4/r021만 active라는 실패 경계도 명시됐다. |
| R015-SK-B04 v2.4 full19 after v2.5 | CLOSED_BY_SCOPE_REMOVAL | FP008·Goal·full19·제품 write는 R016 밖이며 R018의 v2.5 event/full19, R019의 FP008/`GOAL_STARTED`로 분리됐다. R016 성공 기준에 full19나 FP008 완료 주장이 없다. |
| strict chronology | PASS | seq2는 quick `checked_at`, seq3는 그 instant의 canonical RFC3339 정확한 `+1us`이며 seq3 `<=valid_until`이다. equality, reversal, naive/invalid offset, expiry negative가 요구되어 현 `seq2==seq3` 및 `<=` 구현을 세 파일 안에서 교정할 수 있다. |
| truthful delegation / replay boundary | PASS | 실제 한국어 지시 840 bytes와 SHA-256을 재계산해 일치함을 확인했다. 원문과 normalized scope를 분리하고 identity/signature/token 주장을 금지하며 false verification flags, nonce-before-serialization, single-use, exact bindings 및 모든 authority/credit delta 0을 요구한다. |
| source correction implementability | PASS | 현재 결함은 승인 상수·request/receipt exact schema·projection chronology·plan recovery contract와 그 검증/fixture에 집중되어 있다. 모두 allowlist의 shared validation core, builder, 단일 test module에서 생성·검증되고 candidate wrappers는 shared core를 호출하므로 wrapper 수정 없이 구현 가능하다. 초기 세 source SHA도 모두 재계산해 일치했다. |
| deterministic build/publication | PASS | exact six in-memory bytes를 semantic validation한 뒤 staging file/directory fsync, `RENAME_NOREPLACE`, parent fsync로 한 directory를 게시하고, source CAS 전후 재검증과 absent-root/partial·mismatch·extra fail-closed 조건을 고정했다. 현재 candidate root가 absent임도 확인했다. |
| review authority / cycle | PASS | R016 dual PASS가 three-file correction보다 선행하고, candidate dual review가 exact six outputs·manifest·generator/source/test bindings를 검수한다. R017은 그 exact candidate/reviews를 다시 결속한다. authorization request는 package/core review 뒤, receipt는 current-session delegation와 request 뒤, QUICK는 receipt 뒤여서 자기 hash 추측이나 review-before-subject cycle이 없다. |

## 판정

R016은 `PASS`, findings `0/0/0`이다. 부여 권한은 정확히
`R016_CANDIDATE_SOURCE_BUILD_REVIEW_ONLY`이며, 세 source correction과 exact six-output
비효력 candidate build 및 그 review에만 한정된다. activation, real dynamic receipt,
resolved/final/canonical/checkpoint/Goal/full19/product write 권한은 0이다. R017 이후의
monotonic enforcement를 R016 PASS로 구현 완료 또는 활성화 완료라고 간주해서는 안 된다.

종료 시에도 동일 실행 identity와 target identity를 read-only로 재확인하며, target
SHA-256 `49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2`,
12,690 bytes, 292 lines가 유지되어야 한다.
