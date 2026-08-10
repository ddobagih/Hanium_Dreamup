# WalkSafe R017 독립 structural review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R017-INDEPENDENT-STRUCTURAL-REVIEW-R001
reviewer_agent = /root/v25_delegation_delta
reviewer_axis = STRUCTURAL_R001_IMMUTABILITY_R002_NAMESPACE_PROVENANCE_EPOCH_ORACLE_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R017.md
target_sha256 = 6437009fe07692f56e9c4320ce645bc9b13f5b28d8fe6d448e26c634c6f442a5
target_bytes = 13494
target_lines = 316
reviewed_at = 2026-08-02T11:29:11+09:00
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=2 MINOR=0
authority_granted = NONE
```

## 범위와 identity

R017 전체를 R016과 그 독립 review 둘, reviewed R002 pair/PASS review, C0, 현재 three-file
source preimage, 실패한 physical r001 six-output과 대조했다. 대상 R017은 시작 시 regular
`0664`, uid/gid `1000/1000`, nlink 1이었고 위 SHA-256/bytes/lines와 일치했다.

R016 chain의 frozen identity는 roadmap
`49ca08d423dd6ec648de4907e81dc671980d9e0ee6f1bb8acff6f68f7175d8c2`
(12,690 bytes), structural review
`eced95aa16a6b32ca31c1ad6d98b0bbf3e7afbaff3a4bd73b26b4d88e633ac87`
(3,758 bytes), skeptical review
`9bae362a0e62252c99aa7fd119a8252add3629eff6e8c780b263064495f6bc0e`
(5,354 bytes)로 일치했다. 현재 source도 core
`e8daf70a870c81fa5d9aaa10a57bcf575423a6f35fdfc325d02cd24d7266ab5f`, builder
`d4fa8039adc30a5305aad0de0db8f11dbc669eb9f2f1f15537e0ca7ba003e175`, test
`00aa576b62f2043249d3d7ed8e503fdb14d8e93315a8f2e44fc381a731429523`와 정확히 일치했다.

physical r001 root는 directory `0700`이고 exact six entry는 모두 regular `0644`,
uid/gid `1000/1000`, nlink 1이었다. 각 identity도 다음과 일치했다.

| file | SHA-256 | bytes |
|---|---|---:|
| `static-plan-manifest-v2.5.0.candidate.json` | `213cdea4f061d77f587211b3c315f5fb27eb5a2f39dbe7a9382e2c34e7a29a68` | 34,117 |
| `v2.5-application-transaction-plan.candidate.json` | `bb7819d4c3cc27878685fea3d4764ef7d55d511674eb078fc0cce8bfc0840275` | 659,092 |
| `transition-history-v2.5.candidate.json` | `1b859617a6cc7a007a9cdca61f10d5b040ba743bbd90bd5f7183163a8a1f494e` | 2,676 |
| `walksafe-project-continuation-checkpoint-v2.5.candidate.json` | `273b320645ee17737d452af263d45b45058cd7ed012f0bf32c6a9345ae894864` | 218,564 |
| `v2.5-control-package-manifest.candidate.json` | `3e27d14670c804bcc6df202f360a9a0541c1b2a83b3ad759961d2319a577261d` | 27,037 |
| `candidate-output-manifest.json` | `ffd8e1616891f1d3fc75c24bcbfc9d676760afe786fe19d63218c66024119945` | 19,757 |

package가 봉인한 이전 test SHA
`5f1e80f3a22018191a753957bcefbcf620960017f47ccab55a00f031d8d46459`
(68,995 bytes)와 현재 corrected test SHA가 다른 것이 실패의 직접 원인이다. 현재 source에서
targeted suite를 다시 실행한 결과는 37 tests, exit 1, failures 2였고 실패는
`test_check_is_read_only_and_active_canonical_targets_are_absent`와
`test_physical_bundle_and_both_shared_core_wrappers_pass`였다. builder `--check`도 exit 1,
`candidate outputs differ from deterministic rebuild`였다. 실행 전후 R017, three-file
source, r001 six files의 SHA/bytes는 불변이었다.

## Findings

### BLOCKING-01 — exact-existing publication 계약이 내부적으로 모순된다

R017 §1.2는 P2에서 revision-specific assertion만 바꾸고 pre/post dual-state oracle을
보존하도록 제한한다. 반면 §7은 r002가 exact six-output으로 이미 존재하는 경우까지
포함해 absent가 아니면 수정·resume하지 않고 `NEW_REVISION_REQUIRED`로 종료하도록 한다.
현재 builder는 `scripts/build_walksafe_v2_5_control_candidate_20260730.py:1137`의
`_recover_exact_existing()`와 line 1220/1265 경로에서 exact-existing을
`RECOVERED_EXACT_EXISTING` 성공으로 반환한다. 현재 test는 line 1415와 line 1454-1481에서
일반 exact-existing retry와 rename 뒤 parent-fsync 예외 후 동일 revision retry를 모두
성공으로 요구한다.

따라서 §1.2의 허용 범위 안에서는 §7의 terminal no-resume 계약을 구현하면서 기존
non-revision-specific semantic test assertion까지 유지할 수 없다. 이 모순을 그대로 둔
P2/P3는 37-test PASS와 publication 계약을 동시에 만족시킬 수 없다.

최소 수정은 다음과 같다.

- §1.2가 exact-existing/race recovery 제거와 위 두 semantic test의 terminal negative
  oracle 전환을 P2에서 명시적으로 허용해야 한다.
- write/publication 경로는 target이 어떤 형태로든 존재하면 bytes가 exact여도
  `NEW_REVISION_REQUIRED`로 실패해야 한다. rename은 성공했지만 parent fsync가 예외를
  반환한 경우에도 같은 revision retry 성공을 허용하지 않아야 한다.
- 이미 공개된 physical r002를 읽는 post-build `--check`, present-state validator와 두
  wrapper는 publication retry가 아니므로 read-only 검증 경로로 계속 허용한다고 구분해야
  한다.

### MAJOR-01 — r002 namespace migration이 event와 resolved-output 경로를 완전히 닫지 않는다

R017 §4.1은 package/checkpoint/output-manifest ID는 R002로 열거하지만 history seq1/2/3의
exact event ID를 열거하지 않는다. 현재 generator에는 다음 r001-colliding literal이 남아
있다.

```text
scripts/build_walksafe_v2_5_control_candidate_20260730.py:449
  WS-V25-PACKAGE-PREPARED-20260730-001
scripts/walksafe_v2_5_candidate_validation.py:3817
  WS-V25-PACKAGE-ACTIVATED-20260730-001
scripts/walksafe_v2_5_candidate_validation.py:3842
  WS-V25-BULK-REBASELINE-APPLIED-20260730-001
```

특히 seq1 ID는 immutable r001 history와 물리적으로 충돌한다. 또한 R017은
`APPLICATION_GATE_REL`을 `...20260730-002`로 바꾸지만 builder line 780-788의
`resolved_output_manifest_contract.path`는 `...20260730-001/resolved-output-manifest.json`
literal이다. validator에는 이미 `RESOLVED_OUTPUT_MANIFEST_REL` 파생 상수가 있으므로 이를
사용하지 않으면 r002 package 내부 path binding이 서로 갈라질 수 있다. 현재 37 tests는
세 event ID의 exact r002 값과 builder의 해당 파생 경로 사용을 모두 고정하지 않아 이
충돌을 놓칠 수 있다.

최소 수정은 seq1/2/3을 각각
`WS-V25-PACKAGE-PREPARED-20260730-002`,
`WS-V25-PACKAGE-ACTIVATED-20260730-002`,
`WS-V25-BULK-REBASELINE-APPLIED-20260730-002`로 명시하고, builder path를
`validation.RESOLVED_OUTPUT_MANIFEST_REL`로 단일화하며, exact assertion을 tests에 넣는
것이다. P3 전에는 immutable r001 CAS 및 의도적으로 고정된 historical/canonical 참조만
allowlist한 residual namespace scan을 실행해 그 밖의 r001 path/ID collision이 0임을
검증해야 한다.

### MAJOR-02 — r001 no-overwrite는 content CAS만으로 물리 identity 변경을 검출하지 못한다

R017 §2는 r001과 parent 아래 기존 entry의 delete, overwrite, rename, repair, chmod를
모두 금지한다. 그러나 §6과 §7의 실행 oracle은 r001에 대해 six-file SHA/bytes 재확인만
요구한다. 동일 bytes를 유지한 replacement, chmod/chown, hard-link 변화, root/entry
rename-repair는 content CAS만으로 닫히지 않는다. 이번 검수 시점의 mode/uid/gid/nlink가
정상이라는 관찰도 P2-P4 동안의 물리 불변을 보장하지 않는다.

최소 수정은 P2 직전과 P4 종료 후 no-follow `lstat`로 r001 root와 exact six entry의
`st_dev`, `st_ino`, `st_mode`, `st_uid`, `st_gid`, `st_nlink`, `st_size`, `st_mtime_ns`,
`st_ctime_ns`를 봉인·동등 비교하고, root가 non-symlink directory, six entry가
non-symlink regular file이며 entry set에 extra/missing이 없음을 검증하는 것이다. 각
write epoch 전후에도 이 oracle과 content CAS를 함께 통과해야 다음 epoch가 열리도록 해야
한다.

## 나머지 구조 판정

- C0 `6ec0e4bc...d698c`, reviewed R002 pair `7d1e5c04...5b08`와 PASS review
  `f390c653...5fb7`, R016 three-file chain은 현재 bytes와 일치하고 R017 provenance는
  source pin에서 r002 candidate, candidate dual review, 미래 receipt로만 향하는 단방향
  DAG이다. self-hash 또는 미래 receipt가 과거 source authority를 만드는 cycle은 없다.
- P0-P4 write epoch와 allowlist는 순서와 write 범위를 분리한다. 다만 위 findings가 있는
  현재 P1은 PASS가 아니므로 P2 이후 write는 열리지 않는다.
- pre/post 37-test dual-state oracle, two checkers, source 시작/종료 CAS의 큰 구조는
  적절하다. BLOCKING-01을 해소한 뒤 absent publication과 present read-only 검증의 역할을
  명확히 나누면 실행 가능하다.
- r001은 `effective=false`, `approved=false`, `applied=false`, post receipt false이며
  canonical, Goal, 제품 credit은 0이다. R017도 real dynamic receipt, resolved output,
  active final, canonical r022, checkpoint, Goal, full19와 제품 write 권한을 부여하지 않는다.
- 이미 생성된 R017 skeptical review
  `d10ba282eee810c58a0d76d8658a4a21d937dcd4c76882a4936dc97538d61fe4`
  (5,326 bytes, 91 lines)도 `REVISION_REQUIRED`이므로 P1 dual PASS 조건은 현재 성립하지
  않는다.

## 결론

R017은 BLOCKING 1건과 MAJOR 2건 때문에 그대로 실행할 수 없다. R017 또는 그 지시 아래
source/candidate write 권한은 부여하지 않으며 `authority_granted=NONE`이다. 위 최소 수정을
반영한 새 roadmap revision과 새 독립 dual review가 필요하다.
