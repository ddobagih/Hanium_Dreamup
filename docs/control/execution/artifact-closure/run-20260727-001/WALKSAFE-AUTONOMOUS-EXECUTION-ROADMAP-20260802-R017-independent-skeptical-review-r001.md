# WalkSafe R017 독립 skeptical review r001

```text
review_id = WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R017-INDEPENDENT-SKEPTICAL-REVIEW-R001
reviewer_agent = /root/fp048_transition_mechanics
reviewer_axis = SKEPTICAL_REVISION_NAMESPACE_EXISTING_ROOT_GATE_AUTHORITY
target_path = docs/control/execution/artifact-closure/run-20260727-001/WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R017.md
target_sha256 = 6437009fe07692f56e9c4320ce645bc9b13f5b28d8fe6d448e26c634c6f442a5
target_bytes = 13494
target_lines = 316
status = REVISION_REQUIRED
findings = BLOCKING=1 MAJOR=1 MINOR=0
authority_granted = NONE
```

## 범위와 물리 결속

R017 316줄 전체와 현재 core, builder, targeted test, r001 exact six를 읽기 전용으로
대조했다. 재계산한 동결 입력은 다음과 같으며 R017 기재값과 모두 일치했다.

| 대상 | SHA-256 | bytes |
|---|---|---:|
| C0 checkpoint | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` | 1,329,415 |
| R002 pair manifest | `7d1e5c0488342e62d3ee37db657cd3257ba7631a16a29bca676f9b858a9c5b08` | 12,972 |
| R002 PASS review | `f390c653e73646d68b94d5e9b96c81684eb32f0802d1ae98a5a3d7d5d2d75fb7` | 2,366 |
| r001 static plan | `213cdea4f061d77f587211b3c315f5fb27eb5a2f39dbe7a9382e2c34e7a29a68` | 34,117 |
| r001 application plan | `bb7819d4c3cc27878685fea3d4764ef7d55d511674eb078fc0cce8bfc0840275` | 659,092 |
| r001 transition history | `1b859617a6cc7a007a9cdca61f10d5b040ba743bbd90bd5f7183163a8a1f494e` | 2,676 |
| r001 checkpoint projection | `273b320645ee17737d452af263d45b45058cd7ed012f0bf32c6a9345ae894864` | 218,564 |
| r001 package manifest | `3e27d14670c804bcc6df202f360a9a0541c1b2a83b3ad759961d2319a577261d` | 27,037 |
| r001 output manifest | `ffd8e1616891f1d3fc75c24bcbfc9d676760afe786fe19d63218c66024119945` | 19,757 |

현재 corrected source preimage도 core `e8daf70a...ab5f`/180,432 bytes,
builder `d4fa8039...e175`/51,153 bytes, test `00aa576b...9523`/69,685 bytes로
R017 §1.2와 일치했다. r001 root의 entry는 정확히 위 여섯 regular file이었다.

## Findings

### BLOCKING R017-SK-B01 — existing-root 계약과 허용 source delta가 양립하지 않음

R017 §1.2는 P2에서 test의 `revision-specific assertion`만 바꿀 수 있다고 제한한다.
반면 §7은 exact r002를 포함해 target이 이미 존재하는 모든 경우
`NEW_REVISION_REQUIRED`로 끝내도록 요구한다. 현재 builder는 exact-existing target과
`RENAME_NOREPLACE` race를 `_recover_exact_existing()`로 성공
`RECOVERED_EXACT_EXISTING` 처리한다. targeted test도 exact-existing 재호출과 parent
fsync 실패 뒤 재호출이 성공해야 한다고 두 곳에서 명시한다.

따라서 현재 제한을 지키면 §7을 위반하고, §7을 구현하면 builder 동작과 두 semantic
test assertion을 바꿔야 하므로 §1.2를 위반한다. 어느 쪽도 R017의 pre-build 37 PASS와
source-correction 계약을 동시에 만족할 수 없다.

최소 교정: P2 허용 delta에 exact-existing/race recovery 제거와 대응하는 두 publication
test의 `NEW_REVISION_REQUIRED` negative oracle 변경을 명시적으로 추가한다. 성공적으로
공개된 r002를 읽는 present dual-state oracle과 `--check`의 read-only 허용은 별도이며
약화하지 않는다고 구분한다. parent fsync가 예외를 냈지만 target rename이 끝난 경우도
같은 revision 재호출 성공이 아니라 새 revision terminal임을 고정한다.

### MAJOR R017-SK-M01 — candidate event ID migration과 pre-publication 잔존 검사가 누락됨

§4.1은 주요 상수와 history/checkpoint/output-manifest ID를 R002로 열거하지만 현재
candidate-specific event ID 세 개는 migration 표에 없다.

```text
WS-V25-PACKAGE-PREPARED-20260730-001
WS-V25-PACKAGE-ACTIVATED-20260730-001
WS-V25-BULK-REBASELINE-APPLIED-20260730-001
```

첫 ID는 r001 physical history에도 이미 존재한다. 그대로 둬도 AST, duplicate-key,
legacy-token, 37-test gate는 통과할 수 있고, 현재 tests는 이 세 ID의 R002 값을
assert하지 않는다. 그러면 §5의 r001/r002 ID 충돌 0을 위반한 r002가 P3에서 먼저
공개되고 P4에서야 발견되어 불필요하게 r003가 필요해진다.

최소 교정: seq1/seq2/seq3 event ID의 exact `...-002` mapping을 §4.1에 추가하고 tests에
세 값을 고정한다. pre-build gate에는 R016/R017 provenance, reviewed R002 이름 및
의도적으로 유지하는 canonical final path처럼 허용된 역사 문자열을 구분한 exact
candidate-namespace residual scan을 추가해 publication 전에 누락을 차단한다.

## 나머지 공격 축

R016과 R017 review를 먼저 고정하고 그 physical pins를 P2 source에 넣는 순서는
review-before-subject cycle을 만들지 않는다. r002 candidate reviews와 core review는
미래 request/receipt가 생성될 때 한 방향으로 결속하므로 self-hash cycle도 없다.
three-file allowlist와 P3/P4 add-only 경로는 canonical, checkpoint, Goal, product write로
확대되지 않으며 authority/credit은 0으로 유지된다.

## 판정

R017은 위 BLOCKING 1건 때문에 현재 형태로 실행할 수 없다. P2 이후 write 권한은
부여되지 않으며, 두 최소 교정을 반영한 새 roadmap revision과 그 독립 review가
필요하다.
