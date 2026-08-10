# WalkSafe R009 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R009-INDEPENDENT-STRUCTURAL-REVIEW-R001
review_type = INTERNAL_STRUCTURAL_REVIEW
reviewer_agent = /root/r009_structural_review
reviewer_session = /root/r009_structural_review@20260802-r001
independence_attestation = TRUE
target_sha256 = bce7b9dbac301e09f1083b4bc92d892d282b421bfaad0b75ec8d8cd0a6c9ce13
target_bytes = 12404
target_lines = 209
verdict = PASS
blocking = 0
major = 0
minor = 0
```

## 범위와 독립성

동결된 R009, R008 roadmap과 서로 다른 두 PASS plan review, R008 boundary/recovery source
review, 그리고 결정을 위해 필요한 R005~R008의 authority, one-shot path, exact epoch,
runtime/restore, evidence와 acceptance 계약만 읽었다. 다른 R009 reviewer의 결과를 읽거나
요청하거나 전달받지 않았다.

대상의 SHA-256, byte 수, line 수를 직접 재계산했고 위 identity와 일치했다. R008 roadmap과
두 plan review identity도 각각 선언값과 일치했으며 두 review는 서로 다른 agent의
`PASS 0/0/0`이었다. boundary source review는
`ce5b13ebff278f41534a19d5a797707fad0b7a0ab2fe0656c9819d546cd355ed`, 14,661 bytes,
130 lines, `6B/4M/2m`이고 recovery source review는
`e4914ca2951993953901a9c19ffb6907f5a8a74a1963c40ade4f035496a6e698`, 13,034 bytes,
203 lines, `7B/0M/0m`으로 선언값과 일치했다.

후보 Python source는 실행, import, byte-compile 또는 pycompile하지 않았다. predecessor는
read-only stat/hash와 literal child inventory만 확인했으며, 검수 중 candidate, evidence,
backup, repository 또는 canonical bytes를 수정하지 않았다.

## 구조 검수 결과

- **권한 전이:** R008 `E_FAIL_SUCCESSOR`와 두 nonzero source review는 R009 plan만 허용하고
  실행 권한을 재생하지 않는다. R009 source authoring은 live authority가 유지되고 서로 다른
  두 새 agent가 같은 frozen target identity를 각각 통과시킨 뒤에만 열린다. plan review 전
  실행 범위, source/network 금지, 공식·제품·canonical delta 0도 일관된다.
- **path와 epoch:** R009 draft/final/evidence 세 absolute root가 유일하게 고정된다.
  E0 author 1개, E0 review 2개, E1 draft exact 9와 evidence exact 4, E2 source review 2개와
  기존 daylog append, failure successor R010 1개의 write set은 서로 분리되고 모두 literal이다.
  frozen plan write 0, E1 final absent, 별도 automation epoch도 명시돼 암묵적 source write가
  없다.
- **frozen predecessor와 no-resume:** R008 draft는 exact 9, evidence는 exact 4이고 모두
  regular `0600`, uid/gid `1000/1000`, nlink 1이며 두 root는 `0700`이었다. manifest,
  seal-file, draft-post, observation, marker의 다섯 SHA는 R009 선언과 일치하고 final root는
  absent였다. R009 세 root도 모두 initially absent였다. R008 세 root의 write/chmod/delete/
  repair/rerun/resume 금지와 R009 실패 root 보존·새 successor suffix 규칙이 동시에 고정된다.
- **finding 전수 매핑:** source 원문 19개와 C01~C13의 mapping multiset을 대조했다.
  `C01=boundary B-01+recovery B-02`, `C02=boundary B-02`, `C03=boundary B-03`,
  `C04=boundary B-04+recovery B-01`, `C05=boundary B-05`,
  `C06=boundary B-06+recovery B-04`, `C07=boundary M-01+recovery B-03`,
  `C08=boundary M-02`, `C09=boundary M-03+recovery B-07`,
  `C10=boundary M-04+recovery B-06`, `C11=boundary m-01`, `C12=boundary m-02`,
  `C13=recovery B-05`로 누락과 중복이 없다. 병합 후 최고 severity도 정확히
  `10B/1M/2m`이다.
- **상속과 replacement:** rootfd/no-follow ancestor 검증, create-only one-shot, exact
  metadata, confined Add File, fixed backup/branch/status, two-pass tar restore와 zero-delta
  계약만 안전하게 유지한다. 결함이 있던 FD map, libm mode, canonical JSON, control mode,
  same-read CAS, projection/publication oracle, Git argv/env, schema/evidence coverage, negative
  oracle와 I/O error path는 C01~C13으로 명시적으로 교체된다. 특히 R005 runtime table의
  `libm.so.6` `0644`와 R006 prior-only exact-four evidence/marker 계약이 새 closure와
  충돌하지 않는다.
- **source-input과 evidence cycle:** current input은 이미 존재하는 R009와 두 plan review,
  correction input은 이미 동결된 두 R008 source review다. rejected R008 identity와 immutable
  predecessor physical anchors는 history/anchor이고, 미래 R009 seal/evidence/source review/
  publication/projection output은 배제된다. authorization → payload 7 → manifest/seal →
  draft-post → prior-only observation → non-self-claiming marker 순서에는 자기·미래 참조가 없다.
- **acceptance와 정적/동적 경계:** `PLAN_OK`는 live authority, 서로 다른 두 reviewer,
  동일 target identity와 두 PASS의 논리곱이다. `E1_PHYSICAL_OK`는 초기 absent, predecessor
  불변, exact set/reconstruction, non-execution과 모든 zero-delta를 추가한다.
  `R009_SOURCE_OK`는 C01~C13 static closure와 두 source review PASS를 요구하면서 dynamic
  validation은 `NOT_RUN`, candidate는 unexecuted로 유지한다. 각 cluster의 later sandbox,
  mutation, drift, fault, crash 검증은 현재 review 권한과 분리돼 있다.
- **failure path:** 어느 severity든 plan finding이면 R009 root write는 0이고 R010 roadmap
  하나만 허용된다. E1 실패나 source finding도 기존 R009 root를 고치지 않고 R010으로만
  진행한다. 두 source review가 통과해도 별도 publication/projection plan과 두 독립 review
  전에는 source 실행 권한이 생기지 않는다.

## exact negative case 검수

C10 registry에는 다음 25개 ID만 있고 모두 고유하다.

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

앞의 12개는 inherited path/input/status negative, 다음 9개는 publication step별 crash,
마지막 4개는 E1 marker crash/recovery를 닫는다. output만으로 소비를 증명할 수 없는
before-first case를 제외하고, child spawn 전 create-only attempt claim을 소비하며
fault-disabled 두 번째 호출을 producer 진입 전 `ONE_SHOT_CONSUMED`로 고정한 구조도
recovery finding과 일치한다.

## 결론

R009는 권한과 identity를 successor로 정확히 전환하고, frozen predecessor를 재사용하거나
수선하지 않으며, 두 source review의 모든 finding을 cycle-free한 successor-only static
closure로 닫는다. exact paths/write sets, acceptance 논리곱, dynamic deferral 및 R010
failure-only 경로에서 재현 가능한 구조적 finding은 없다. 이 판정은 R009 corrected source
authoring을 여는 plan gate에만 한정되며 candidate 실행이나 publication/projection 권한을
부여하지 않는다.
