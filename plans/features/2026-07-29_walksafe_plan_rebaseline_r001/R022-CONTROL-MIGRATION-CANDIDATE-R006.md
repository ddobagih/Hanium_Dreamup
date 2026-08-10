# r022 제어계약 전환 후보 R006

- 작성일: `2026-07-30`
- 상태: `DESIGN_ONLY_NOT_EFFECTIVE_NOT_APPROVED_NOT_APPLIED`
- 현재 활성 제어: v2.4 / checkpoint sequence 39 / Gap·Backlog r021
- 설계 전환: `selector-preflight P → main v2.5 M`
- 제품·Goal·canonical authority: 없음

이 문서는 실패한 R005를 수정하지 않고 add-only successor 설계만 추가한다.
candidate, authorization request, canonical r022, Goal/event와 제품 코드를 만들거나
변경하지 않는다. R006 자체의 독립검수 findings가 모두 0이 되기 전에는 어느
transaction도 구현·발행·승인·적용하지 않는다.

## 1. frozen 입력과 현재 build blocker

R006은 다음 여섯 물리 문서를 선행 입력으로 결속한다.

| 역할 | 경로 | SHA-256 | bytes |
|---|---|---:|---:|
| 실패한 R003 설계 | `R022-CONTROL-MIGRATION-CANDIDATE-R003.md` | `aa60c7788779fd86746af4b82e9e62a56f33e34553d39e1c48a55b649ac9c908` | 74,328 |
| R003 FAIL 검수 | `R022-CONTROL-MIGRATION-CANDIDATE-R003-independent-review-r001.md` | `bc3628500e5f560d06b63b9fb38b39312f4aeecdf4b46f932821c930f2040220` | 8,588 |
| 실패한 R004 설계 | `R022-CONTROL-MIGRATION-CANDIDATE-R004.md` | `f44085550511eb346b8ddebc5db88cd5bb69160cc93b10bf78e50049fb555fcc` | 50,508 |
| R004 FAIL 검수 | `R022-CONTROL-MIGRATION-CANDIDATE-R004-independent-review-r001.md` | `d8335e91787bb6ca496813efb1804b619514cb7b4b3ecac7a6d4c369f96b801d` | 6,105 |
| 실패한 R005 설계 | `R022-CONTROL-MIGRATION-CANDIDATE-R005.md` | `4cfcd51904038d38b5ab82b097da375c09e953e0182be47669a147e5c99bef67` | 53,409 |
| R005 FAIL 검수 | `R022-CONTROL-MIGRATION-CANDIDATE-R005-independent-review-r001.md` | `c7bdf87e5ffb4deae8ad81f81bacb221e37c7fd322c8aee37fb5f1fae1f5e1a4` | 10,005 |

공통 prefix는
`plans/features/2026-07-29_walksafe_plan_rebaseline_r001/`이다. R003 판정
`FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_DESIGN`은 유지한다. R003의 final23,
candidate-authored trust root, 하나뿐인 publish primitive와 Approval2 계약은
구현 근거가 아니다.

R004 판정 `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R005`도 historical input으로
유지한다. R005 판정 `FAIL_REQUIRES_ADD_ONLY_SUCCESSOR_R006`을 현재 선행
판정으로 유지한다. R006은 R005의 정상 부분을 보존하고 그 FAIL 검수 §4
allowlist의 여덟 root-cause 교정만 적용한다.

현재 불변 physical pins는 다음과 같다.

- branch `codex/walksafe-rc2-hardening-20260715`
- HEAD `a3ad7eead6b5d834d3e0675422475a9aad351e3d`
- checkpoint sequence 39:
  `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c`,
  `1,329,415` bytes
- v2.4 static:
  `7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07`,
  `39,534` bytes
- r021 Gap:
  `f2e304679c5c3dfd3d7331340039e7222ab9e3915ede30de673f60f1b2aca97a`,
  `488,160` bytes
- r021 Backlog:
  `bcc4561ead39e1d659222f54161c0e063bd41fbf8b60c0a2b144a4535143c6a0`,
  `59,266` bytes
- artifact complete `126/257`, open `131`
- formal `0/279`, actual-device `0`, closed release gate `0/5`
- release `NOT_ELIGIBLE`
- Goal topology/status, ready frontier `EPIC-03`, `EPIC-12` 불변
- canonical r022, physical P/M candidate와 v2.5 active files 부재

현재 working snapshot은 checkpoint-projected content set과 다르다.

| 값 | SHA-256 |
|---|---:|
| sequence 39 expected managed content set | `69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a` |
| 603-file live content set 관찰값 | `51fa51b966219dc4d8c1ff8317e7209323726298365b4ec413b2520bdc90bf1b` |

따라서 현재 상태는
`SOURCE_WORKTREE_DRIFT_BLOCKS_PREFLIGHT_BUILD_APPLY`다. checkpoint-projected
clean source를 복원하거나, 별도 add-only snapshot strategy와 그 exact bytes에
대한 새 사용자 승인을 받기 전에는 P candidate build, approval request와 apply를
모두 write 0으로 막는다. R006/R005/R004 설계, R003, 기존 dirty bytes 또는 편의를 위한
snapshot은 이 drift를 암묵적으로 승인·정본화하지 않는다.

## 2. 명칭과 두 transaction의 권한 분리

- `P`: v2.4 selector-preflight transaction
- `M`: P가 끝난 뒤의 main v2.5/r022 transition
- `TP`: add-only P candidate root
  `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-4-selector-preflight-r006`
- `TM`: add-only M candidate root
  `plans/features/2026-07-29_walksafe_plan_rebaseline_r001/v2-5-control-candidate-r006`
- `GP`:
  `docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-4-SELECTOR-PREFLIGHT-20260730-001`
- `GM`:
  `docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-5-BULK-REBASELINE-20260730-001`
- `B`: repository/candidate 밖의 externally durable bootstrap/capture journal

P와 M은 candidate, transaction ID, challenge, user response, nonce, signed
consumption token, claim, progress root, post-check와 receipt가 전부 다르다.

| transaction | exact scope | 허용 final |
|---|---|---|
| P | `SELECTOR_PREFLIGHT_ONLY` | §8 pivot + exact P17, seq39→seq40 |
| M | `V25_MAIN_TRANSITION_ONLY` | §9 exact M15, r022 control projection |

P는 M, canonical r022, Goal start, FP-008 또는 제품 write 권한이 아니다. M도 Goal
start, FP-008와 제품 write 권한이 아니다. P response/nonce/claim/receipt를 M에
재사용하거나 두 scope를 한 request에 합치면 production write는 0이다.

## 3. candidate 밖의 production authority

### 3.1 external chain

production trust root는 정확히 다음뿐이다.

```text
A0 immutable host anchor
→ R0 pre-sequence-39 repository ACL
→ L_P 또는 L_M depth-zero leaf ACL
→ fresh signed revocation/now
→ challenge/request/user response
```

그 뒤 write authority는 서로 대체할 수 없는 두 lane으로 나뉜다.

```text
bootstrap lane:
external B의 fresh one-use BOOTSTRAP_CAPABILITY
→ B에서 atomic consume + BOOTSTRAP_CLAIM
→ enforced lock/lease
→ GP/GM bootstrap, 그리고 P에 한해 exact AGENTS pivot
→ exact bootstrap/pivot receipt
→ quick PASS
→ exact gate-local APPLICATION_CONSUMPTION_PROBE_STARTED
→ signed application-consumption ingress O-CAPTURE

application lane:
verified durable APPLICATION_CONSUMPTION attestation
→ declared runtime/tool O-CAPTURE
→ APPLICATION_CLAIM_INTENT
→ APPLICATION_CLAIM D_DONE
→ P17 remainder 또는 M15 application
```

bootstrap capability는 transaction, approval, exact directory lattice,
application-plan hash, lease policy와 허용 syscall을 결속한다. P capability는
GP bootstrap, §8 index 1 AGENTS CAS와 gate-local quick capture/result/PASS receipt
또는 quick normal-incident closure, quick PASS 뒤 exact gate-local
`APPLICATION_CONSUMPTION_PROBE_STARTED` 하나와 그 probe에 대한 signed
consumption ingress `O-CAPTURE`만 허용한다. M capability도 GM bootstrap, quick과
같은 두 preclaim write만 허용한다. ingress는 bound transaction/attempt/nonce의
control-plane signed response만 담고 runtime/tool bytes를 담지 않는다. verified
durable consumed attestation이 생기면 bootstrap capability의 repository/gate
write allowlist는 끝난다.

durable probe와 exact signed non-success ingress가 있고 verified consumed
attestation을 만들 수 없으면 bootstrap capability는 그 ingress에 byte-exact
결속된 gate-local terminal incident 하나의 intent/D-PUBLISH/final parent fsync만
추가로 허용한다. signed ingress가 인증한 `consumed=false`이면 reason과 boolean을
보존한 `APPLICATION_CONSUMPTION_NOT_ESTABLISHED`, `consumed=true`인데 valid
attestation이 없으면 `AUTH_SPENT_NO_APPLICATION`이다. ingress/signature/boolean이
absent·ambiguous하거나 valid attestation이 있거나 incident occupant가 다르면 이
예외는 write 0이다. 어느 branch도 runtime/claim/progress/final 권한이 아니다.

verified durable `APPLICATION_CONSUMPTION` attestation은 같은 live lease
epoch에서 declared runtime/tool `O-CAPTURE`, exact
`APPLICATION_CLAIM_INTENT`와 claim D-PUBLISH를 한 번 수행할 좁은 preclaim
authority다. claim intent 전에 epoch가 끝났거나 exact preclaim normal-failure
observation 때문에 claim을 만들 수 없을 때는 gate-local
`AUTH_SPENT_NO_APPLICATION` normal incident 하나만 허용한다. 이 attestation은
progress/final/checkpoint/post-check를 직접 허용하지 않는다. application claim이
`D_DONE`인 뒤에만 application claim이 progress/final/post-check와 그 success
receipt 또는 normal incident를 허용한다. physical divergence는 incident 권한에
포함되지 않는다. bootstrap claim 없이 첫 repository/gate mutation을 하거나
application claim 없이 final application/post-claim incident를 쓰면 write 0이다.

`A0`와 `R0`는 R006, candidate, repository, gate root, environment, CLI path,
network response나 OS fallback trust store가 선택하지 않는다. 별도 privilege
domain의 preinstalled host trust service가 repository identity로 선택해 already
opened read-only FD로 제공한다. 다음을 모두 요구한다.

- fs-verity/verified read-only mount 또는 동등한 immutable store
- owner/root privilege, mount ID, dev/ino, mode/nlink, raw hash/bytes
- seq39 이전 provisioning과 append-only transparency proof
- root key, issuer/audience/purpose, allowed algorithms/key-use
- repository ACL, delegation ceiling, revocation signer와 rollback floor

하나라도 absent/unverified/stale하면
`PRODUCTION_APPLY_UNAVAILABLE_WRITE_ZERO`다. candidate가 root public key,
principal 또는 supervisor signer를 추가·교체할 수 없다.

`L_P`와 `L_M`은 external issuer가 해당 R0 아래 서명한 opaque ingress다. 각각
parent ACL hash/revision, repository/checkpoint, R006/candidate/review, transaction,
human principal, service identities, action set, TTL와 revocation generation을
결속한다. delegation depth는 0이고 새 key/delegate를 만들 수 없으며
principal/scope/TTL/action은 R0의 strict subset이다.

### 3.2 exact user provenance

P와 M은 별도 pre-prompt signed challenge-time token으로 issued/expires,
channel/session과 nonce를 먼저 고정한다. 완성 prompt raw bytes를 보낸 뒤 그
message ID에 대한 expected human principal의 exact reply만 허용한다.

```text
WALKSAFE_APPROVAL_SELECTOR_PREFLIGHT_V24 anchor_sha256=<64hex> repository_acl_sha256=<64hex> leaf_acl_sha256=<64hex> request_sha256=<64hex> challenge_sha256=<64hex> transaction_id=<safe-id> candidate_id=<safe-id> nonce=<64hex> repository_identity_sha256=<64hex> channel_id_b64u=<b64u> session_epoch_b64u=<b64u> issued_at=<UTC-seconds> expires_at=<UTC-seconds> scope=SELECTOR_PREFLIGHT_ONLY
```

```text
WALKSAFE_APPROVAL_MAIN_V25_R022 anchor_sha256=<64hex> repository_acl_sha256=<64hex> leaf_acl_sha256=<64hex> request_sha256=<64hex> challenge_sha256=<64hex> transaction_id=<safe-id> candidate_id=<safe-id> preflight_receipt_sha256=<64hex> seq40_checkpoint_sha256=<64hex> nonce=<64hex> repository_identity_sha256=<64hex> channel_id_b64u=<b64u> session_epoch_b64u=<b64u> issued_at=<UTC-seconds> expires_at=<UTC-seconds> scope=V25_MAIN_TRANSITION_ONLY
```

field와 순서는 exact, separator는 ASCII space 하나, terminal LF는 없다.
`safe-id`는 lowercase ASCII `[a-z0-9][a-z0-9-]{0,62}`, hash/nonce는 lowercase
64-hex다. server ID는 1~256 raw UTF-8 bytes의 canonical base64url-no-padding,
timestamp는 `YYYY-MM-DDTHH:MM:SSZ`다. placeholder, 추가/누락/중복 field,
preliminary prompt reply, wrong reply-to와 local copied string은 거부한다.

M request canonical object는 reviewed TM candidate와 independent-review exact
pins, `TM/detached-output-authorization-manifest.json`의 raw SHA-256/bytes,
expected future
`GM/bootstrap/detached-output-authorization-manifest.json` raw path/role, nearest
preexisting ancestor binding과 `ABSENT` tombstone을 결속한다. future GM parent의
dev/ino는 `UNRESOLVED_UNTIL_DIR_DURABLE`이고 request에 넣지 않으며 §6 ordered
directory bootstrap 뒤 D-PUBLISH intent가 처음 결속한다. 아직 없는 GM archive의
observed hash/bytes/dev/ino도 request나 challenge에 넣지 않는다. 위 exact reply의
`request_sha256`이 이 canonical object를 결속한다.

bootstrap consumption은 B의 expected-head CAS 아래 먼저 수행하고, 그 signed
claim을 repository lease 획득 뒤 첫 mutation 직전에 다시 검증한다. application
consumption은 이미 유지 중인 same repository lock/lease 아래 durable exact probe
marker를 publish한 뒤 수행하고 signed response를 위 ingress로 capture한다. control
plane은 서로 다른 one-use token을
transaction/attempt/lane에 원자적으로 consume해 signed bytes를 발행한다.
`issued ≤ prompt ≤ response ≤ consumed ≤ expires`와 current ACL/revocation을
검증한다. lane 교환이나 재사용은 실패하고 same attempt refetch는 byte-exact
signed response만 허용한다.

### 3.3 candidate preparation boundary

§3.1의 “첫 repository/gate mutation”은 frozen candidate/review 뒤 GP/GM,
AGENTS와 P17/M15에 가하는 첫 production mutation을 뜻한다. TP/TM candidate와
그 independent-review successor를 만드는 add-only preparation은 별도
`DESIGN_BUILD_ONLY_NON_PRODUCTION` lane이다.

이 lane도 암묵적으로 허용되지 않는다. exact projected source drift 0, 별도 명시적
build instruction, frozen deterministic builder와 다음 write allowlist가 모두
필요하다.

```text
TP/** 또는 TM/**
해당 candidate exact hash를 직접 결속하는 add-only independent-review file
```

NOREPLACE 밖 overwrite, GP/GM/active/canonical/Goal/product write, authorization
request 생성과 external authority material 생성은 금지한다. build/review receipt는
production approval이나 A0/R0/L leaf가 아니며 candidate가 trust root를 제공하지
못한다. 현재 §1 source drift에서는 이 lane도 write 0이다.

M의 exact preparation/authority 순서는
`seq40/P closure + source drift 0 → TM build → TM exact independent review
findings 0 → external issuer가 candidate/review-bound L_M 발행 → M
challenge/request/user authorization → bootstrap/apply`다. `L_M` 또는 M review를
TM build 선행조건으로 사용하거나 review 전 leaf/request를 미리 만들면 write
0이다.

## 4. 두 publication primitive

R006은 deterministic bytes와 opaque bytes를 같은 규칙으로 다루지 않는다.

### 4.1 `D-PUBLISH`

대상은 선행 durable inputs와 fixed transition function으로 exact bytes/hash를
전부 재계산할 수 있는 intent/progress/resolved/promotion/result descriptor/
outcome/normal incident/receipt다.

intent에는 deterministic temp name을 넣되 미래 temp inode는
`UNRESOLVED_UNTIL_DURABLE_ADOPTION`으로 둔다.

| state | physical observation | 허용 행동 |
|---|---|---|
| `D_BEFORE` | target exact before/tombstone, temp absent | `O_EXCL+O_NOFOLLOW` temp 생성 |
| `D_TEMP_UNRESOLVED` | 유일한 temp가 expected exact prefix, secure metadata | 같은 inode FD 재채택, missing suffix만 append |
| `D_TEMP_FULL` | exact full bytes | `fdatasync`, final metadata, file `fsync`, stable `fstat`; inode 첫 결속 |
| `D_NAME_SWITCHED` | target exact after가 adopted temp inode이고 source temp absent | same-parent fsync 또는 cross-parent target parent first fsync |
| `D_TARGET_PARENT_DURABLE` | cross-parent target confirmation exact, source temp absent | source parent fsync |
| `D_TARGET_FIRST_SOURCE_REPLAY` | cross-parent target exact after와 source temp가 same adopted inode인 exact dual-name | target fsync 재확인 뒤 bound source temp만 unlink, source parent fsync |
| `D_DONE` | same-parent confirmation 하나 또는 cross-parent target/source confirmations와 physical binding exact | 완료 |
| `PHYSICAL_DIVERGENCE` | 위 외 조합 | repository/gate write 0 |

`COPY_NOREPLACE`와 `CAS_REPLACE`의 namespace switch는 다르다.

- `COPY_NOREPLACE`: temp/target이 같은 filesystem임을 확인하고 switch 직전 target
  `ABSENT` tombstone과 lease epoch를 다시 검증한 뒤
  `renameat2(temp_dirfd,temp,target_dirfd,target,RENAME_NOREPLACE)`한다.
  호출 전 rescan에서 target exact-after/temp absent면 `D_NAME_SWITCHED`로
  재분류한다. target과 temp가 exact same adopted inode인 dual-name이면
  `D_TARGET_FIRST_SOURCE_REPLAY`로 재분류한다. 실제 호출이 `EEXIST`를 반환했을 때
  이 exact dual-name이 아니면 divergence다.
- `CAS_REPLACE`: intent가 고정한 before를 `O_NOFOLLOW` FD로 계속 보유한다.
  switch 직전 held FD와 name lookup의 dev/ino/hash/bytes/metadata, parent와 lease
  epoch를 다시 검증한 뒤 같은 filesystem에서
  `renameat2(temp_dirfd,temp,target_dirfd,target,0)`으로 교체한다. old before FD는
  `D_DONE`까지 보유하고 old/new binding을 progress에 기록한다. 강제 lease로
  비협조 writer가 차단되지 않으면 CAS 자체를 허용하지 않는다.

D-PUBLISH intent는 source-temp와 target parent 각각의 dev/ino 및 before/after
immediate entry set을 결속한다. source before/after를 `S0`(exact temp present)/
`S1`(temp absent), target before/after를 `T0`(COPY tombstone 또는 CAS exact before)/
`T1`(exact after)로 부른다. parent가 다르면 순서는 항상
`rename → fsync(target parent) → durable target confirmation →
fsync(source parent) → durable source confirmation`이다. raw path 정렬로 이
순서를 바꾸지 않는다. 같은 parent면 한 번만 fsync한다.

cross-parent rename 뒤 crash recovery observation은 다음 네 조합만 허용한다.

| source/target observation | 분류와 exact 행동 |
|---|---|
| `S0,T0` | rename이 durable하지 않은 `D_TEMP_FULL`; switch predicate를 다시 검증하고 rename 재시도 |
| `S1,T1` | `D_NAME_SWITCHED`; target parent를 먼저 fsync/confirm하고 source parent를 fsync/confirm |
| `S0,T1` | 두 name이 exact same adopted dev/ino/bytes/metadata이고 replay nlink/entry set이 exact일 때만 `D_TARGET_FIRST_SOURCE_REPLAY` |
| `S1,T0` | authorized full temp와 target after가 모두 사라진 loss state이므로 physical divergence |

dual-name recovery는 target parent fsync를 먼저 반복하고 두 name의 same adopted
inode와 exact entry set을 재검증한 뒤 broker가 intent-bound source temp name만
`unlinkat`한다. 그 다음 source parent를 fsync하고 source absent, target unchanged,
final nlink와 양 entry set을 검증한다. unlink와 source fsync 사이 crash는
`S0,T1` 또는 `S1,T1`로만 돌아와 같은 suffix를 반복한다. 이는 unknown occupant
unlink 금지의 유일한 예외다. 다른 inode/bytes/metadata/nlink/entry set, `S1,T0`
또는 위 네 조합 밖 관찰은 divergence다. 이 crash observation 집합과 target-first
directory-fsync durability를 attested supported-filesystem contract로 보장하지
못하면 cross-parent D-PUBLISH는 prewrite 0이다. required confirmation 하나라도
없으면 `D_DONE`이 아니다.

둘 이상의 temp, non-prefix, wrong metadata, intent 없는 exact final과 다른
transaction occupant는 divergence다. unknown occupant를 truncate/unlink/
overwrite하지 않으며 위 exact authorized CAS만 before name을 교체할 수 있다.
future inode를 사전승인 hash에 넣지 않는다.

### 4.2 `O-CAPTURE`

대상은 signed control-plane ingress, host/runtime attestation과 command
stdout/stderr/completion envelope다.

`CAPTURE_INTENT`에는 producer/verifier, purpose, nonce, byte limit, spool role와
deterministic name만 둔다. 미래 raw hash/bytes/inode/signature를 요구하지 않는다.
trusted broker가 producer 시작 전에 spool을 만들고 빈 file/parent를 fsync한 뒤
descriptor identity를 `SPOOL_READY_DURABLE`에서 처음 결속한다. producer는 broker-held
FD에만 bytes를 쓴다.

opaque descriptor 자체의 first binding은 supported filesystem에서
`O_TMPFILE → full write → file fsync →
linkat(tmpfd,"",parent_dirfd,name,AT_EMPTY_PATH)`로 수행한다. Linux `linkat`에
별도 `NOREPLACE` flag가 있다고 가정하지 않는다. destination이 이미 있으면
`EEXIST`를 받고 intent-bound exact occupant만 recovery 대상으로 분류한다.

link 성공 뒤 parent fsync 전은 `O_LINKED_UNCONFIRMED`다. descriptor
hash/bytes/dev/ino/metadata와 exact parent entry set이 intent와 맞으면 parent
fsync만 반복해 `O_DONE`으로 간다. absent/foreign occupant는 PASS가 아니라
unknown 또는 divergence다. link 전 crash는 PASS로 resume하지 않는다. external
signed ingress는 stable attempt ID로 exact refetch하고, command result는
`EXECUTION_RESULT_UNKNOWN` normal incident로 끝낸다. candidate가 만든 unsigned
JSON은 trusted opaque ingress가 아니다.

opaque capture가 durable해진 뒤에는 그 exact physical binding을 선행 input으로
삼아 result descriptor와 final raw promotion을 `D-PUBLISH`한다. 따라서
expected-hash intent의 무한 회귀와 hash만으로 잃어버린 suffix를 복원하는 규칙이
없다.

## 5. command spool state machine

P/M quick, P/M post-check와 external full19은 모두 다음 상태기를 쓴다.

1. `CAPTURE_INTENT_DURABLE`
2. broker가 child 전 stdout/stderr/completion spool 생성
3. empty spool file/parent fsync
4. `SPOOL_READY_DURABLE` — name/parent/dev/ino/uid/gid/mode/nlink 첫 결속
5. `CHECK_STARTED_DURABLE`
6. fixed-FD child exec; broker가 pipe를 spool로 직접 drain
7. child 종료와 stdout/stderr EOF 확인
8. 두 raw spool 각각 `fdatasync → held-FD full reread/hash → fstat → fsync`
9. spool parent fsync
10. trusted supervisor가 exit/signal/timeout/truncation, exec FD와 두 raw binding을
    결속한 signed completion envelope 발행
11. completion spool file/parent fsync와 signature/revocation 검증
12. `CHECK_RESULT_INTENT_DURABLE`
13. 각 raw spool을 정확히 한 번 `COPY_NOREPLACE` D-PUBLISH로 같은 inode의 final
    raw name에 승격한다. 다른 parent면 raw target parent first, spool source
    parent second로 fsync/confirm하고 §4.1의 `S0,T0 | S1,T1 | S0,T1 | S1,T0`
    recovery를 그대로 적용한다. exact same-inode dual-name은 source spool
    cleanup만 resume하고 foreign dual-name은 divergence다. 같은 parent면 한 번만
    fsync한다.
14. deterministic result descriptor `D-PUBLISH`
15. `CHECK_RESULT_DURABLE`
16. ordered results 뒤 `OUTCOME_INTENT → OUTCOME_DURABLE`

| crash observation | 판정 |
|---|---|
| spool ready, started 없음, all empty/exact | 같은 attempt 실행 가능 |
| started 있음, valid durable completion 없음 | normal `EXECUTION_RESULT_UNKNOWN`, 재실행 금지 |
| completion exact, raw/result promotion incomplete | 같은 result exact resume, command 재실행 금지 |
| completion exact, spool/raw same-inode dual-name | §4.1 target-first source cleanup resume, command 재실행 금지 |
| spool/descriptor/inode/hash/entry-set mismatch | physical divergence |

raw durability는 stdout/stderr뿐 아니라 signed completion envelope의 file/parent
fsync까지 뜻한다. aggregate outcome도 ordered result bindings와 expected bytes를
결속한 별도 `OUTCOME_INTENT` 뒤에만 publish한다. raw result descriptor와
aggregate outcome은 각 spool→raw D-PUBLISH가 `D_DONE`인 뒤에만 publish한다.

## 6. bootstrap lattice, directory와 raw path

### 6.1 external bootstrap root

`B`는 A0가 seq39 이전에 provision한 repository 밖 append-only journal service다.
A0는 B service principal/key, broker executable identity, immutable namespace ID,
already-opened root FD의 mount-ID/dev/ino/owner/mode, repository별 ACL,
genesis sequence/head hash와 rollback-counter identity를 직접 pin한다. fresh
`B_HEAD_ATTESTATION`이 genesis부터 current append sequence/head까지의 hash chain,
monotonic counter와 R0 floor를 증명한다. B의 root/path/head를 candidate,
repository, environment 또는 runtime lookup이 고르지 않는다.

B append는 R006 publication primitive의 선행 trust primitive다. preinstalled
single-writer service가 caller의 expected sequence/head를 원자적으로 compare한
뒤 signed record를 append하고 file/service durable barrier와 새 head를 결속한
signed receipt를 반환한다. head mismatch, rollback, duplicate sequence, 다른
namespace/service key 또는 durability receipt 부재는 repository write 0이다.
따라서 첫 `CAPTURE_INTENT`를 다시 O-CAPTURE해야 하는 순환이 없다.

crash/interruption recovery observation도 B native record다. preinstalled
external lease service가 old lease epoch의 writer와 writable handles를 먼저
fence/revoke한 뒤에만 `CRASH_INTERRUPTION_OBSERVATION`을 expected-head append한다.
record는 repository/transaction/lane, exact phase와 progress tail, old lease
epoch/lease ID/owner, source/root/entry snapshot, observation reason과 producer를
결속한다. process 부재, heartbeat timeout, repository selector의 로컬 추론이나
unsigned 관찰만으로는 recovery predicate가 성립하지 않는다.

B에서 recovery 발행은 fresh `B_HEAD_ATTESTATION.current_head` hash chain을 해당
transaction/lease lineage로 투영했을 때 latest record가 그 unclosed observation이고
observation receipt `new_head`가 current chain의 exact ancestor/equal일 때만
허용된다. current global head를 expected head로 쓰는 CAS가 observation ID/hash와
recovery attempt/nonce를 결속한
`RECOVERY_OBSERVATION_CONSUMED`를 원자적으로 append하고 one-use
`SAME_TRANSACTION_RECOVERY_CAPABILITY`를 반환한다. same attempt refetch는
byte-exact capability만 허용하며 다른 B append/head 변화 뒤에는 사용하지 않고
discovery부터 다시 한다. 복구 또는 terminal closure가 끝나면 그 result
phase/receipt를 결속한 `RECOVERY_OBSERVATION_CLOSED`와 durable append receipt를
남긴다. recovery lease 자체가 먼저 끝나면 새 lease ID의 후속 observation이 최신
lineage record가 되고, consumed/closed/stale observation은 다시 매치되지 않는다.

첫 repository mutation 전 다음 순서가 B에서 끝나야 한다.

```text
B_HEAD_ATTESTATION
→ BOOTSTRAP_ROOT_INTENT + atomic first-append receipt
→ BOOTSTRAP_CAPABILITY_CONSUMED
→ BOOTSTRAP_CLAIM
→ enforced repository lease
```

그 뒤 외부 signer ingress와 runtime bytes에는 §3.1이 그 phase에 부여한 exact
authority 아래에서만 §4.2 `O-CAPTURE`를 적용한다.
`BOOTSTRAP_ROOT_INTENT`는 다음을 결속한다.

- repository/transaction과 raw-byte gate path
- existing ancestor descriptors와 exact entry sets
- ordered directory lattice와 metadata
- candidate initialization plan physical hash/bytes
- supervisor/tool identity
- authority/approval/leaf ACL physical bindings

future directory inode는 root intent에 없다. B의 native append receipt는
repository/gate final member나 application authority가 아니다.

### 6.2 directory transition

| state | physical state와 행동 |
|---|---|
| `MKDIR_INTENT_DURABLE` | B 또는 이미 durable한 parent journal에 expected child/metadata 기록 |
| `ABSENT` | exact parent-before와 child absent면 `mkdirat` |
| `EXACT_UNCONFIRMED` | expected directory와 stage-specific entry set이면 child/parent fsync |
| `DIR_DURABLE` | 그 뒤 confirmation이 dev/ino와 parent before/after entry set 첫 결속 |
| `PHYSICAL_DIVERGENCE` | symlink, wrong metadata, premature/unknown child, parent drift |

`DIR_DURABLE` 전 crash에서 child가 absent면 새 inode로 다시 만들 수 있고, exact
unconfirmed이면 fsync를 반복한다. confirmed 뒤 absent/different inode는
divergence다. “모든 directory가 empty”라는 규칙 대신 initializer plan의 stage
`k`마다 각 ancestor의 exact immediate-child set을 계산한다.

GP와 GM은 서로 다른 bootstrap root/receipt와 다음 고정 container를 가진다.

```text
<G>/bootstrap/application-plan-input.json
<GM>/bootstrap/detached-output-authorization-manifest.json
<G>/authorization/{ingress,consume-attempts}
<G>/quick/attempt-000001/{spool,raw}
<G>/runtime/attempts
<G>/transaction-progress
<G>/postcheck/attempt-000001/{spool,raw}
<G>/postcommit
```

bootstrap DAG는
`BOOTSTRAP_ROOT_INTENT + atomic first-append receipt → B bootstrap claim →
enforced lease → ordered DIR_DURABLE → subordinate policy archive → gate-bootstrap →
EXTERNAL_BOOTSTRAP_RECEIPT`다. exact bootstrap receipt 없이는 quick/
application-consume/application-claim을 시작하지 않는다.

### 6.3 path canonicalization

filesystem raw path/name은 JSON string으로 직접 해석하지 않는다.

- `path_b64u`/`child_name_b64u`: raw bytes의 base64url-no-padding
- `path_bytes`/`child_name_bytes`: unsigned byte length
- `path_sha256`/`child_name_sha256`: raw-byte SHA-256
- ordering: decoded raw bytes의 unsigned lexicographic order

decode/re-encode 불일치, duplicate raw bytes, `/`, NUL, `.`/`..` component,
invalid descendant와 ambiguous normalization은 실패한다.

## 7. fixed-FD supervisor와 continuous lock

fixed lock은
`<realpath(git-common-dir)>/walksafe-control-transition.lock`이다. A0/R0가 허용한
pre-sequence-39 preprovisioned regular inode여야 하며 absent이면 runtime에 새로
만들지 않고 write 0이다. A0/R0가 허용한
external host service가 repository root/common-dir, lock, interpreter,
launcher/writer/verifier/check modules와 operation broker executable/module을
`openat2(RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS)`/`O_NOFOLLOW`로 열고
dev/ino/mount-ID/type/owner/mode/nlink/size/hash/bytes/fs-verity/build ID를 검증한다.
broker signer/key, exact syscall/target/metadata allowlist와 actual exec/mapped
inode도 A0/R0/L leaf와 runtime attestation에 결속한다.

supervisor는 environment를 비우고 exact FD table, read/write policy,
`no_new_privs`, network namespace/egress 0, seccomp/Landlock/RO mounts를 설치한다.
검증한 interpreter는 path로 다시 열지 않고 정확히
`execveat(interpreter_fd,"",argv,envp,AT_EMPTY_PATH)`로 실행한다. PT_INTERP와
shared libraries는 sealed minimal RO mount의 verified inode만 허용하고 actual
mapped inode를 attestation에 결속한다. repository module은 inherited source
FD의 verified memory bytes만 compile한다.

host-exclusive no-writer lease는 advisory lock만 뜻하지 않는다. external
privileged repository service가 protected repository/common-dir/gate mount와
모든 managed inode set을 lease epoch/owner/expiry에 결속하고, 기존 writable
handle을 revoke하며, broker 외 mount namespace를 read-only 또는 write-denied로
강제한다. broker는 모든 mutation 직전 current epoch/owner/expiry와 차단 상태를
재검증한다. 이 강제를 증명할 수 없는 filesystem/host에서는 production write가
0이다.

각 transaction은 user response와 B bootstrap claim 검증 뒤 첫 repository
mutation이나 quick pre-snapshot보다 먼저 lock과 강제 lease를 한 번 획득한다.
P는 그 lock 아래 source seq39를 마지막으로 재검증한 뒤 GP bootstrap과 pivot
intent/CAS/receipt를 수행한다. M은 같은 방식으로 seq40/P closure를 재검증하고
GM bootstrap을 수행한다. 같은 OFD/lease/root FD를

```text
gate bootstrap, P이면 pivot intent/temp/switch/receipt
→ quick intent → quick spool/results/receipt
→ bootstrap-cap probe marker + signed consumption ingress O-CAPTURE
→ verified-attestation runtime/tool O-CAPTURE + application-claim intent/claim
→ progress/resolved/promotions
→ post-check → receipt 또는 normal incident final fsync
```

동안 끊김 없이 유지한다. quick도 relative path나 path exec 없이 same supervisor의
fixed-FD module을 실행한다. live epoch의 close/reopen, generation/boot-ID/root
mount drift, second writer 또는 recovery capability가 없거나 검증 실패한
supervisor/broker loss는 이후 write 0이다.

이 close/reopen 금지는 하나의 live lease epoch에 적용한다. process/host crash로
epoch가 끝나면 started command를 재실행하지 않는다. §4~§6이 명시적으로 resumable
이라고 분류한 deterministic suffix/fsync state만, §6.1의 exact latest unclosed
observation을 one-use consume해 발행한 `SAME_TRANSACTION_RECOVERY_CAPABILITY`로
재채택할 수 있다. 새 supervisor는 capability와 current B head를 다시 검증하고 새
lease epoch를 강제로 획득한 뒤 전체 root/source/entry/inode predicate를
재검증해야 하며, 이는 live FD reopen이나 새 transaction 또는 제3 authority
lane이 아니다.

recovery capability는 original bootstrap 또는 verified consumed
attestation/application claim 중 정확히 하나, remaining operation allowlist,
current A0/R0/L revocation/now, prior progress tail, observation/old lease ID,
one-use recovery nonce와 새 lease epoch를 결속한다. exact application
`APPLICATION_CLAIM_INTENT`/temp가 이미 있으면 claim D-PUBLISH suffix만 resume할
수 있다. claim intent가 없으면 already-started declared runtime/tool O-CAPTURE의
durable suffix와 `AUTH_SPENT_NO_APPLICATION` incident closure만 resume하며 새 claim
intent/claim을 mint하지 않는다. attestation이 없으면 capture나 claim도 mint하지
않는다. 단 original-bootstrap recovery branch는 durable probe + exact signed
non-success ingress + valid attestation absent를 결속했을 때 §3.1의 deterministic
`APPLICATION_CONSUMPTION_NOT_ESTABLISHED` 또는 `AUTH_SPENT_NO_APPLICATION`
incident 하나의 missing D-PUBLISH/fsync suffix만 허용한다. no ingress, ambiguous
consumed boolean, success/valid-attestation ingress에는 이 branch를 열지 않는다.
recovery capability 자체는 bootstrap lane에서 application/final authority를
부여하지 않는다. exact recovered bootstrap/quick PASS 뒤에는 정상 절차의 fresh
`APPLICATION_CONSUMPTION`으로만 진행한다. completion/terminal
closure 뒤 `RECOVERY_OBSERVATION_CLOSED`가 durable하지 않으면 public success가
아니며 같은 observation을 새 capability에 재사용하지 않는다. capability,
predicate, current B head 또는 external durability가 불완전하면 write 0이다.

writer는 raw filesystem mutation syscall을 갖지 않고 operation broker만
resolved exact dirfd/name/operation/hash/metadata allowlist를 수행한다. product,
undeclared control path, hardlink, procfd, mount와 network write는 금지한다.

## 8. P bootstrap pivot와 exact P17

### 8.1 AGENTS pivot

P 자체의 claim-before-selector 순환은 단일 bootstrap exception으로 끊는다.
P user authorization receipt와 external bootstrap receipt 뒤, P application
consumption 전에
`BOOTSTRAP_SELECTOR_PIVOT_INTENT`가 current `AGENTS.md` exact before, dispatcher
after bytes, A0/R0/L_P, P transaction, CAS mode와 recovery launcher를 결속한다.

AGENTS pivot은 P final member index 1이지만 일반 promotion loop에서 다시 쓰지
않는다.

1. intent/temp가 link/switch 전 crash: canonical AGENTS unchanged, seq39 authority
2. AGENTS exact-after/parent fsync: 새 세션은 GP/GM의 consume marker, attestation,
   claim temp/spool, progress와 receipt를 먼저 읽음
3. `BOOTSTRAP_SELECTOR_PIVOT_RECEIPT` durable
4. 그 뒤에만 P quick, consume와 claim 허용

pivot exact-after인데 receipt가 없으면 AGENTS 자체가 exact intent/after를 확인해
P recovery launcher로 라우팅한다. P index 4가 아직 없을 수 있으므로 이 구간은
canonical path lookup이 아니라 bootstrap intent가 결속한 reviewed TP launcher
FD role을 external supervisor가 실행한다. index 4 durable 뒤에만 canonical
launcher path를 fixed-FD로 열 수 있다. pivot temp만 남고 AGENTS가 before면
source authority는 seq39이고 external B recovery entry만 허용하며 readiness는
fail-closed한다. pivot authority/receipt가 없으면 P application consumption은
write 0이다.

### 8.2 final17

P outcome은 add-only 5, CAS 11과 checkpoint commit 1의 exact 17 physical
members다. index 1은 이미 pivot exact-after여야 하고 P progress가 그 physical
binding을 확인만 한다.

| index | final path/role | promotion |
|---:|---|---|
| 1 | `AGENTS.md` bootstrap dispatcher | `PRECONSUMPTION_PIVOT_CAS_ALREADY_EXACT` |
| 2 | `GP/selector-preflight-manifest.json` | `COPY_NOREPLACE` |
| 3 | `scripts/check_walksafe_active_control_discovery_20260730.py` | `COPY_NOREPLACE` |
| 4 | `scripts/launch_walksafe_v2_5_r022_authorized_20260730.py` | `COPY_NOREPLACE` |
| 5 | `docs/control/walksafe-active-control-discovery.json` | `COPY_NOREPLACE` |
| 6 | `scripts/check_walksafe_project_continuation_v2_4.py` | `CAS_REPLACE` |
| 7 | `scripts/check_walksafe_goal_graph_v2_4.py` | `CAS_REPLACE` |
| 8 | `tests/test_walksafe_project_continuation_v2_4.py` | `CAS_REPLACE` |
| 9 | `tests/test_walksafe_goal_graph_v2_4.py` | `CAS_REPLACE` |
| 10 | `scripts/run_walksafe_test_layers_20260711.sh` | `CAS_REPLACE` |
| 11 | `scripts/README.md` | `CAS_REPLACE` |
| 12 | `docs/control/walksafe-project-resumption-runbook.md` | `CAS_REPLACE` |
| 13 | `docs/control/goals/README.md` | `CAS_REPLACE` |
| 14 | `docs/control/README.md` | `CAS_REPLACE` |
| 15 | `README.md` | `CAS_REPLACE` |
| 16 | `tests/test_walksafe_active_control_discovery_20260730.py` | `COPY_NOREPLACE` |
| 17 | `docs/control/walksafe-project-continuation-checkpoint.json` | `SEQ40_TRANSFORM_CAS_COMMIT_POINT` |

index 2~5, 16이 add-only다. index 1, 6~15가 CAS 11개다. index 17은 별도
transform CAS다. `GP` 축약을 푼 index 2 exact path는
`docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-4-SELECTOR-PREFLIGHT-20260730-001/selector-preflight-manifest.json`이다.

P application plan은 `GP/bootstrap/application-plan-input.json` dynamic required
role이며 P17 final member가 아니다. candidate version의 exact member/order/
before/expected-after/promotion/owner/mode를 byte-exact archive한 뒤 pivot
intent보다 먼저 durable해야 한다.
seq40은 source seq39 Goal/focus/canonical r021/count/product bytes를 보존하고 event
`CONTROL_DISCOVERY_SELECTOR_PREFLIGHT_APPLIED`만 append한다. checkpoint는
`SELECTOR_PREFLIGHT_COMMITTED_REQUIRES_RECEIPT`를 선언하고 미래 P receipt hash를
포함하지 않는다.

현재 v2.4 static manifest
`docs/control/goals/walksafe-completion-graph-v2-4/static-plan-manifest-v2.4.0.json`
(`7325de1f...8b07`)과 같은 graph의 `README.md`는 P/M 모두
`PREEXISTING_EXACT_V24_PROTECTED`이며 한 byte도 쓰지 않는다.

P post-check PASS 뒤 dynamic post-commit receipt가 logical #18이지만 final
member가 아니다. receipt가 seq40/P17/post-check/progress tail을 단방향으로
결속하고 parent fsync confirmation까지 있어야 `V24_SELECTOR_READY`다.

selector regression은 최소 다음 경우를 검증한다.

- 격리된 regression fixture의 reverse-seq40은 P17의 exact before/tombstone으로
  byte-exact seq39 closure를 복원한 뒤 기존 v2.4 검사를 통과하는 정상 peel-back
- production recovery에서 partial/unpinned peel-back 또는 rollback 시도는
  divergence
- P claim/progress 또는 seq40-no-receipt는 preflight recovery
- P receipt 뒤 main queue가 없으면 selector ready, M marker부터 main recovery
- test registry는 seq39 / selector-ready / v2.5 active에서 exact conditional set

## 9. M source와 exact final15

M candidate는 다음 source/build 조건이 전부 exact인 뒤에만 build할 수 있다.

- seq40 checkpoint raw hash/tail과 P17 physical closure
- P post-commit receipt와 receipt-parent-fsync progress tail
- P normal incident/physical divergence 부재
- seq40가 P17 exact after/tombstone으로 재계산·결속한 expected managed
  content-set과 live source snapshot의 drift 0
- §3.3의 별도 명시적 build instruction과 frozen deterministic builder

하나라도 없으면 `MAIN_CANDIDATE_BUILD_WRITE_ZERO`다. 그 source에서 TM candidate를
build/freeze한 뒤에만 exact candidate-bound independent review를 만들 수 있다.
findings 0 뒤 external A0/R0 검증과 candidate/review-bound fresh `L_M` 발행,
M request/challenge와 user authorization 순서로 진행한다. review/leaf가 없으면
`MAIN_REQUEST_BOOTSTRAP_APPLICATION_WRITE_ZERO`다. candidate/review bytes가 바뀌면
`L_M`, request, challenge와 response를 모두 무효화한다. M challenge는 seq39가
아니라 이 seq40 source와 P receipt를 직접 결속한다.

P index 1~16의 다음 path는 M에서 재작성하지 않는다.

```text
AGENTS.md
docs/control/execution/goal-gates/WS-GOAL-GRAPH-V2-4-SELECTOR-PREFLIGHT-20260730-001/selector-preflight-manifest.json
scripts/check_walksafe_active_control_discovery_20260730.py
scripts/launch_walksafe_v2_5_r022_authorized_20260730.py
docs/control/walksafe-active-control-discovery.json
scripts/check_walksafe_project_continuation_v2_4.py
scripts/check_walksafe_goal_graph_v2_4.py
tests/test_walksafe_project_continuation_v2_4.py
tests/test_walksafe_goal_graph_v2_4.py
scripts/run_walksafe_test_layers_20260711.sh
scripts/README.md
docs/control/walksafe-project-resumption-runbook.md
docs/control/goals/README.md
docs/control/README.md
README.md
tests/test_walksafe_active_control_discovery_20260730.py
```

이들은 `PREEXISTING_EXACT_PREFLIGHT_PROTECTED` closure다. selector/discovery bytes는
fixed GP/GM role/schema만 알고 actual M hashes는 M package/resolved/receipt에서
join하므로 future-hash cycle이 없다.

M 전에는 P closure가 P17과 P receipt를 포함한다. M index 15가 live checkpoint를
교체한 뒤에는 P immutable closure를 index 1~16으로 정의하고, P index 17의 exact
seq40 bytes는 M index 3 archive가 대체 보존한다. P receipt는 자신보다 미래인
M index 3을 참조하지 않는다. ACTIVE checker가 P receipt에 결속된 original P17
index 17 bytes와 M index 3의 observed physical binding이 byte-exact인지 검증한다.
따라서 post-M closure가 서로 다른 두 live checkpoint bytes를 동시에 요구하지
않는다.

M final은 exact 15개다.

| index | source/role | final path | promotion |
|---:|---|---|---|
| 1 | reviewed exact68 r022 Gap | `docs/control/audits/walksafe-implementation-gap-analysis-20260726-r022.json` | `COPY_NOREPLACE` |
| 2 | reviewed exact68 r022 Backlog | `docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r022.json` | `COPY_NOREPLACE` |
| 3 | exact seq40 source checkpoint | `docs/control/goals/walksafe-completion-graph-v2-5/superseded-v2.4-seq40-selector-ready-checkpoint.json` | `COPY_NOREPLACE` |
| 4 | v2.5 static manifest | `docs/control/goals/walksafe-completion-graph-v2-5/static-plan-manifest-v2.5.0.json` | `COPY_NOREPLACE` |
| 5 | M application plan | `GM/application-transaction-plan.json` | `COPY_NOREPLACE` |
| 6 | transition seq1 prefix | `docs/control/goals/walksafe-completion-graph-v2-5/transition-history-seq1-prefix-v2.5.json` | `COPY_NOREPLACE` |
| 7 | v2.5 package manifest | `docs/control/goals/walksafe-completion-graph-v2-5/control-package-manifest-v2.5.json` | `COPY_NOREPLACE` |
| 8 | active validation core | `scripts/walksafe_v2_5_validation.py` | `COPY_NOREPLACE` |
| 9 | active continuation wrapper | `scripts/check_walksafe_project_continuation_v2_5.py` | `COPY_NOREPLACE` |
| 10 | active Goal wrapper | `scripts/check_walksafe_goal_graph_v2_5.py` | `COPY_NOREPLACE` |
| 11 | authorized recovery writer | `scripts/apply_walksafe_v2_5_r022_authorized.py` | `COPY_NOREPLACE` |
| 12 | v2.5 graph README | `docs/control/goals/walksafe-completion-graph-v2-5/README.md` | `COPY_NOREPLACE` |
| 13 | candidate-independent active test | `tests/test_walksafe_v2_5_active_control_20260730.py` | `COPY_NOREPLACE` |
| 14 | final history transform | `docs/control/goals/walksafe-completion-graph-v2-5/transition-history-v2.5.json` | `SEALED_TRANSFORM_NOREPLACE` |
| 15 | active checkpoint transform | `docs/control/walksafe-project-continuation-checkpoint.json` | `SEALED_TRANSFORM_CAS_COMMIT_POINT` |

index 5 application plan은 final15 exact role이다. quick 전 prerequisite는
B와 `GM/bootstrap/application-plan-input.json`에 보존한 detached candidate plan
binding이다. final target `GM/application-transaction-plan.json`은 index 5
promotion 전까지 explicit `ABSENT`이므로 bootstrap과 NOREPLACE가 충돌하지 않는다.
M index 15 checkpoint의 phase-specific receipt state는
`TARGET_COMMITTED_REQUIRES_DURABLE_POSTCOMMIT_RECEIPT`다. 모든 NOREPLACE는
parent dev/ino를 포함한 explicit `ABSENT` tombstone을 쓴다. CAS는 actual before
hash/bytes/dev/ino/metadata를 challenge에 고정한다. regular final owner는
`1000:1000`, nlink 1, mode `0644`다.

reviewed `TM/detached-output-authorization-manifest.json`이 indices 1~13의 physical
after와 transform 14/15의 spec/ordered slots를 결속한다. M request/challenge는
independent review가 결속한 이 TM source manifest의 exact raw hash/bytes와
candidate/review pins, expected future GM archive raw path/role, nearest
preexisting ancestor binding과 `ABSENT` tombstone을 직접 고정한다. future GM
parent dev/ino는 `UNRESOLVED_UNTIL_DIR_DURABLE`이고 bootstrap 뒤 D-PUBLISH
intent에서 처음 결속한다. 아직 없는 GM archive는 request prerequisite나 observed
authorization subject가 아니다.

authorization, B bootstrap claim과 lease 뒤 GM bootstrap이 reviewed TM source의
exact raw bytes를
`GM/bootstrap/detached-output-authorization-manifest.json`으로
`COPY_NOREPLACE` D-PUBLISH한다. external bootstrap receipt는 request hash, reviewed
TM source binding, observed GM physical hash/bytes/dev/ino, byte equality와
target/source parent-fsync confirmations를 결속한다. 이 receipt 전에는 quick
write가 0이다.

final15 physical-hash dependency는 machine-checkable `REF` DAG 하나로 동결한다.
`REF(i,j)`는 final member `i`의 raw bytes 또는 실행 의미가 member `j`의 exact
role/path/physical SHA-256/bytes를 소비한다는 뜻이며, materialization
topological order에서 `j`가 `i`보다 먼저다. reviewed TM detached manifest는 exact
node set `{1..15}`, 모든 `REF` adjacency, canonical raw-sorted edge set,
deterministic tie-break order와 graph SHA-256을 결속한다. validator는 모든 schema
field, embedded constant와 semantic alias에서 physical-hash reference를 독립
재구성해 declared edge set과 strict equality를 요구한다. unknown/undeclared/
duplicate edge, self-edge, unprocessed node 또는 SCC size 2 이상이면 build/request/
promotion write가 0이다.

preauthorization topology도 full15 exact다. indices 1~13은 actual physical
bindings, 14/15는 exact transform dependency/spec slot을 가지며 actual
SHA-256/bytes만 `UNRESOLVED_UNTIL_MAIN_AUTH_QUICK`이다. topology와 node/edge order는
authorization 뒤 바뀌지 않는다. index 5의 `REF(5,*)`는 empty다. index 7은
`REF(7,j)`에서 `j ∈ {1..6,8..13}`만 가질 수 있고 그 어느 `j`에서도 direct/indirect
path로 7을 다시 참조할 수 없다.

non-manifest indices 1~6, 8~13은 index 7, detached/GM archive, request,
authorization, quick, resolved, progress, receipt 또는 future final member의
physical hash/bytes를 참조하지 않는다. transform 14/15가 prior final member를
참조하는 edge는 reviewed manifest의 ordered transform allowlist에 명시된
`REF(14,j)`와 `REF(15,j)`만 허용하며 producer가 먼저 materialize돼야 한다.
14/15는 detached/GM archive, resolved, progress/postcommit receipt, 자신 또는
future physical hash를 참조하지 않는다.

cross-phase 방향도 단방향이다.

```text
final 1..13 actual + transform 14/15 specs
→ reviewed TM detached manifest
→ M request/challenge/authorization
→ GM byte-exact archive + bootstrap receipt
→ quick + verified application authority
→ transform 14/15 actual bytes
→ resolved manifest
→ promotions/progress
→ post-check/receipt
```

progress bootstrap 뒤 fixed inputs로 transform bytes를 memory에서 계산한다.
resolved D-PUBLISH 직전 actual final15 bytes에서 `REF`를 다시 추출해 reviewed
topology/node/edge/order와 exact equality 및 full topological sort를 반복한다.
resolved의 `validation_inputs`는 실제 pre-promotion read만,
`expected_after`는 아직 없는 final15 target의 authorized future binding만 가진다.
`observed_after`는 resolved에 금지하고 promotion progress와 final receipt에서만
기록한다. resolved가 actual final15/graph를 단방향으로 결속하고 final receipt가
resolved/observed final15를 결속한다. 어느 final member도 detached/resolved의
physical binding을 역참조하지 않는다.

ACTIVE required read closure는 기존 P protected closure + GP exact
receipt/progress + M final15 + GM dynamic records/detached archive에
`FINAL_ACTIVE_MANAGED_SOURCE_CLOSURE`를 더한 exact union이다. 이 source closure는
quick PASS source와 authorized deterministic M overlay에서 resolved 전에
계산하고 index 5/resolved/post-check trace가 같은 manifest를 결속하며, index 15
final checkpoint가 자기 path를 제외한 expected closure를 self-contained로
투영한다. ACTIVE가 live bytes에서 expected 값을 새로 학습하거나 정본화하지
않는다.

`FINAL_ACTIVE_MANAGED_SOURCE_CLOSURE`는 base HEAD/tree, fixed product pathspec
`apps`, `backend`, `configs`, `contracts`, `deploy`, `model`, `product`, `voice`,
`docker-compose.yml`, checkpoint-declared managed/control source와 각 raw
path_b64u/length/hash, expected type/uid/gid/mode/nlink/size/SHA-256/bytes,
class별 count/path-set/content-set digest를 가진다. Git class는 index stage를 포함한
tracked regular, approved tracked-deleted `ABSENT` tombstone, nonignored-untracked,
ignored를 exact set으로 나눈다. untracked/ignored도 자동 제외하지 않고 reviewed
policy가 허용한 path와 physical binding만 인정한다. candidate/design/review/
fixture와 checkpoint 자기 path는 source set에서 제외하되 위 control closure에서
별도 검증한다.

ACTIVE checker는 candidate root나 R001~R006 설계/review/fixture를 열지 않는다.
pinned repository/Git/root FD와 continuous read/no-writer lease 아래 system/global
exclude를 끄고 repository config, `.gitignore`, `.git/info/exclude`의 exact
physical binding을 검증한다. NUL-framed HEAD/tree, raw Git index/stages,
porcelain-v2 status with untracked+ignored, tracked/deleted/others sets와 secure
dirfd filesystem walk를 두 번 독립 수집한다. raw Git union과 filesystem walk,
checkpoint expected inventory가 exact해야 하며 final live source를 직접
재해시한다. governed scope의 extra/missing/reclassified path, index/status/
config/ignore drift, unexpected tombstone, content/type/metadata/nlink drift,
symlink/gitlink/special file, double-read/root/mount/lease drift는
`PHYSICAL_DIVERGENCE_FAIL_CLOSED(reason=ACTIVE_MANAGED_SOURCE_DRIFT)`와 write 0이다.
original seq40는 index 3 archive에서 읽고 candidate root deny/remove 뒤에도 같은
ACTIVE 검사가 PASS해야 한다.

## 10. exact P/M DAG와 progress

### 10.1 P

```text
clean seq39 source
→ A0/R0/L_P + P candidate independent review 0
→ P user authorization
→ B head/root attestation + P bootstrap capability consume/claim
→ lock/lease + under-lock clean seq39 revalidation
→ GP bootstrap + external bootstrap receipt
→ BOOTSTRAP_SELECTOR_PIVOT_INTENT
→ AGENTS pivot exact + pivot receipt
→ P quick durable PASS
→ bootstrap-cap APPLICATION_CONSUMPTION_PROBE_STARTED
→ bootstrap-cap signed consumption ingress O-CAPTURE
→ verified consumed attestation
→ attestation-authorized runtime/tool O-CAPTURE
→ APPLICATION_CLAIM_INTENT
→ application claim D-PUBLISH
→ P progress root DIR_DURABLE
→ 000000-PROGRESS-BOOTSTRAP
→ P resolved intent/manifest
→ verify pivot index 1, promote indices 2..17
→ P post-check
→ normal incident XOR P post-commit receipt
```

P bootstrap/pivot receipt 뒤 quick PASS receipt가 아직 없으면 quick intent가
absent인 zero-quick과 partial/unclosed intent/spool/result를 모두
application marker absent일 때만
`SELECTOR_PREFLIGHT_BOOTSTRAP_RECOVERY_REQUIRED`로 분류한다. quick PASS receipt
없이 application marker가 있으면 predecessor violation physical divergence다.
quick PASS receipt가
exact이고 current live lease epoch/OFD/root FD를 exact supervisor가 계속 보유하며
interruption observation이 없으면 discovery state가 아닌 in-flight transient로
source authority seq39에서 fresh application consumption으로 진행한다. 그 전에
§6.1의 exact latest unclosed crash observation이 durable하면 같은 bootstrap
recovery state가 소유한다. `APPLICATION_CONSUMPTION_PROBE_STARTED`부터 같은
current live owner에게는 application-lane in-flight이고, 이후 lease가 끝나 §6.1
latest unclosed observation이 durable해지면 selector가
`SELECTOR_PREFLIGHT_RECOVERY_REQUIRED`를 노출한다.

### 10.2 M

```text
exact seq40 + P17 + P receipt
→ explicit instruction + build/freeze M candidate
→ candidate-bound independent review findings 0
→ A0/R0 + candidate/review-bound fresh L_M
→ reviewed TM manifest-bound request/challenge + M user authorization
→ B head/root attestation + M bootstrap capability consume/claim
→ lock/lease + under-lock seq40/P closure revalidation
→ GM bootstrap + reviewed TM bytes COPY_NOREPLACE archive
→ source/request/GM equality external bootstrap receipt
→ M quick durable PASS
→ bootstrap-cap APPLICATION_CONSUMPTION_PROBE_STARTED
→ bootstrap-cap signed consumption ingress O-CAPTURE
→ verified consumed attestation
→ attestation-authorized runtime/tool O-CAPTURE
→ APPLICATION_CLAIM_INTENT
→ application claim D-PUBLISH
→ M progress root DIR_DURABLE
→ 000000-PROGRESS-BOOTSTRAP
→ compute transforms in memory
→ RESOLVED_MANIFEST_INTENT
→ resolved D-PUBLISH
→ promotions 1..15
→ M post-check
→ normal incident XOR M post-commit receipt
```

M external bootstrap receipt 뒤 quick PASS receipt가 아직 없으면 quick intent가
absent인 zero-quick과 partial/unclosed intent/spool/result를 모두
application marker absent일 때만 `M_BOOTSTRAP_RECOVERY_REQUIRED`로 분류한다.
quick PASS receipt 없이 application marker가 있으면 predecessor violation
physical divergence다. quick PASS receipt가 exact여도
current live lease epoch/OFD/root FD를 exact supervisor가 계속 보유하며 interruption
observation이 없으면 discovery state가 아닌 in-flight transient로 fresh application
consumption을 진행한다. 그 전에 §6.1의 exact latest unclosed crash observation이
durable하면 같은 bootstrap recovery state가 소유한다.
`APPLICATION_CONSUMPTION_PROBE_STARTED`부터 같은 current live owner에게는
application-lane in-flight이고, 이후 lease가 끝나 §6.1 latest unclosed
observation이 durable해지면
`V25_TRANSITION_RECOVERY_REQUIRED`다.

progress record는 zero-padded monotonic sequence, previous record physical
hash/bytes, transaction과 phase를 결속한다. gap/fork/duplicate/higher tail은
divergence다. next record bytes가 이전 durable tail과 fixed transition으로
결정되지 않으면 D-PUBLISH를 사용할 수 없다.

application claim temp/spool, consumption marker/attestation, transaction root,
resolved, promotion prefix와 target checkpoint-no-receipt는 모두 recovery
predicate다.

## 11. discovery와 recovery truth table

판정 우선순위는
`physical divergence > exact normal incident > recovery > stable`이다. 아래
stable row는 더 높은 우선순위의 temp/spool/incident/attempt artifact가 전혀
없거나, 해당 phase receipt로 exact closure된 artifact뿐일 때만 성립한다.
startup/discovery의 recovery row는 §6.1 B latest head와 일치하는 최신 unclosed
crash observation을 추가로 요구한다. 같은 live lease의 current owner에게 보이는
중간 상태는 discovery recovery가 아니라 in-flight transient다. recovery artifact가
있지만 observation이 아직 없으면 `RECOVERY_OBSERVATION_REQUIRED_WRITE_ZERO`이고
external lease service의 fence/revoke+append 외 repository write는 0이다.
consumed/closed/stale observation을 current로 제시하면 physical divergence다.
terminal/stable row는 consumed-but-unclosed recovery observation이 없어야 한다.
recovery attempt가 한 번이라도 있었으면 §6.1 result phase를 결속한
`RECOVERY_OBSERVATION_CLOSED`가 fresh B current-head chain에서 해당 transaction/
observation lineage의 latest record여야 한다. 그 전에는
`RECOVERY_OBSERVATION_CLOSE_REQUIRED_WRITE_ZERO`이며 B close append 외 write는
0이다.

| physical state | public state | authority |
|---|---|---|
| unknown entry/fork/inode/bytes, consumed/closed/stale recovery observation의 current 재사용, same transaction에서 quick PASS receipt absent + application marker present, 또는 same transaction·same terminal phase의 normal incident + competing post-commit success receipt | `PHYSICAL_DIVERGENCE_FAIL_CLOSED` | repository write 0 |
| exact P normal incident, P receipt 없음 | `SELECTOR_PREFLIGHT_TERMINAL_INCIDENT` | 기존 v2.4만, successor 전 repository write 0 |
| exact M normal incident, M receipt 없음 | `V25_TRANSITION_TERMINAL_INCIDENT` | 현재 durable source control만, successor 전 repository write 0 |
| P application marker absent + (external bootstrap receipt 없음 + bootstrap claim/partial GP, 또는 external bootstrap receipt exact + pivot receipt 없음 + pivot intent absent/AGENTS before, 또는 pivot receipt 없음 + pivot intent/temp/AGENTS exact-after, 또는 pivot receipt exact + quick PASS receipt 없음 + zero/partial/unclosed quick, 또는 pivot receipt와 quick PASS receipt exact) | `SELECTOR_PREFLIGHT_BOOTSTRAP_RECOVERY_REQUIRED` | latest-observation-bound P same-tx recovery capability만 |
| P quick PASS receipt exact + P post-commit receipt 없음 + application marker/attestation/preclaim runtime·tool O-CAPTURE/claim temp·spool/progress/P prefix/seq40, incident 없음 | `SELECTOR_PREFLIGHT_RECOVERY_REQUIRED` | latest-observation-bound P same-tx recovery capability만 |
| M application marker absent + (external bootstrap receipt 없음 + bootstrap claim/partial GM, 또는 external bootstrap receipt exact + quick PASS receipt 없음 + zero/partial/unclosed quick, 또는 external bootstrap receipt와 quick PASS receipt exact), incident 없음 | `M_BOOTSTRAP_RECOVERY_REQUIRED` | latest-observation-bound M same-tx recovery capability만 |
| M quick PASS receipt exact + M post-commit receipt 없음 + target checkpoint absent + application marker/attestation/preclaim runtime·tool O-CAPTURE/claim temp·spool/progress/final prefix index 1~14, incident 없음 | `V25_TRANSITION_RECOVERY_REQUIRED` | latest-observation-bound M same-tx recovery capability만 |
| M target checkpoint, receipt·incident 없음 | `V25_COMMITTED_RECOVERY_REQUIRED` | post-check/recovery만 |
| exact seq39 + AGENTS before, pivot intent/temp/incident 없음 | `V24_SEQ39_STEADY` | 기존 v2.4만 |
| exact seq40 + P17 + P receipt + M bootstrap claim 없음, M application marker/incident 없음 | `V24_SELECTOR_READY` | 기존 v2.4, M 준비만 |
| P index 1~16 + P receipt/original seq40 + exact M index 3 archive equality + M15 + M receipt/fsync tail, incident 없음 | `V25_ACTIVE` | v2.5 control only |

P/M quick PASS와 application marker 뒤 signed non-success ingress가 exact하지만
valid consumed attestation이 없고 incident가 아직 없으면 위 application recovery
row의 public state를 사용한다. authority는 original-bootstrap recovery capability의
§3.1 narrow terminal-incident suffix뿐이다. `consumed=false`는
`APPLICATION_CONSUMPTION_NOT_ESTABLISHED`, `consumed=true`는
`AUTH_SPENT_NO_APPLICATION` 하나로 닫고 claim/progress/final은 0이다.

control-plane application token이 소비됐지만 application-lane
`APPLICATION_CLAIM_INTENT`/delta가 전혀 없으면 bootstrap/pivot delta와 무관하게
`AUTH_SPENT_NO_APPLICATION` normal incident로 기록하고 token을 폐기한다. P pivot
이후 또는 어느 application claim temp/spool 뒤에는 rollback이나 새 transaction
resume이 없다. exact normal incident는 해당 attempt를 닫으며 same-tx recovery
대상이 아니다. fixed GP/GM과 add-only namespace를 재사용하지 않으므로
pre-consumption incident도 새 transaction으로 덮지 않고 successor design을
요구한다.

P가 설치한 AGENTS/selector/discovery는 startup 때 authorization text보다 먼저
GP/GM exact entry set과 위 predicate를 검사한다. P pivot 전에는 preinstalled
host service가 repository AGENTS보다 먼저 B의 unclosed bootstrap claim/head를
검사해 external recovery entry로 라우팅한다. recovery state에서는 그
same-transaction의 exact recovery/post-check 외 ordinary/new quick, external
full19, Goal start, product command와 ordinary planning을 fail-closed한다.
terminal-incident state에서는 successor authorization 전 repository write가 0이다.

## 12. quick, post-check와 external full19

R003에서 독립 재계산된 logical command contracts는 historical input으로만
보존한다.

| contract | version | checks | digest |
|---|---|---:|---:|
| main quick logical order | `2026-07-30.4` | 2 | `0261f6574b6bc279df6e41ce927477b0582271a381af2a4443f05c831a487d14` |
| full19 logical order | `2026-07-30.3` | 19 | `8d92a521980e67a06e5712dbad62f76ff56519debae5be7b2c74664e7f0e00d5` |
| main read-only post-check | `2026-07-30.1` | 3 | `85c3548f2aee314f3db309e164927aa5319db86198470da3f63a81672ff76971` |

이 digest만으로 path execution을 승인하지 않는다. P/M candidate는 check ID/order와
fixed `executable_fd_role`, `module_fd_role`, argv tail, root cwd FD, exact env,
timeout/output limit을 담은 새 execution-envelope digest를 독립검수 전에
동결한다. R004에서 동결되어 R005/R006이 byte-exact 상속한 P quick logical contract
`2026-07-30.r004.1`은 두 check이며 digest는
`01713a874fcda224527027c7cd62092e239b704ca73f9b6dcf66daf61b4b9eeb`다.
그 digest의 canonical object는 UTF-8, sorted keys, compact separators,
`allow_nan=false`, terminal LF 없이 직렬화한 다음 exact 값이다.

```json
{"checks":[{"check_id":"PREFLIGHT_CONTINUATION_QUICK_R004","command":"PINNED_FD_EXEC role=PREFLIGHT_CONTINUATION_CANDIDATE mode=PREFLIGHT_QUICK"},{"check_id":"PREFLIGHT_GOAL_GRAPH_QUICK_R004","command":"PINNED_FD_EXEC role=PREFLIGHT_GOAL_GRAPH_CANDIDATE mode=PREFLIGHT_QUICK"}],"contract_version":"2026-07-30.r004.1"}
```

P/M quick와 post-check는 §5 spool state machine을 사용한다. P post-check는
seq40 receipt-pending mode, M post-check는 target-checkpoint receipt-pending
mode에서 active source만 읽는다.

full19은 M receipt 뒤의 `VALIDATION_EVIDENCE_ONLY_NON_AUTHORIZING` 작업이다.
raw/start/result/read-trace와 producer/verifier receipts는 전부 repository 밖 B의
별도 immutable namespace에 둔다. host repository 또는 GM에 post-active write하지
않는다.

- producer P19와 independent verifier V19는 A0/R0가 서로 겸임 불가로 고정한
  별도 principal/key holder이며 서로 다른 executable/import closure와 role/key ID
- P19가 continuous read lock/no-writer lease를 획득하고 V19 signed receipt가
  durable해질 때까지 같은 lease epoch와 immutable lower snapshot을 유지
- P19가 §5 spool로 checks 1~19 실행
- trusted syscall/LSM trace가 실제 read/exec/mmap/getdents input을 기록
- minimal synthetic RO filesystem은 declared repo/tool/lib/SDK/cache만 노출
- undeclared `/etc`, HOME, tmp, proc, sibling repo, clock/random/uname은
  deny하거나 explicit synthetic binding
- upper/cache/temp는 empty에서 시작하고 broker-created provenance만 read 허용
- check 18 뒤 process tree reap, upper files/dirs fsync와 signed manifest
- upper/overlay를 제거한 새 namespace의 original lower-only root에서 check 19;
  lower mount-ID/root dev·ino/content-set, mount table과 upper/work 부재를 서명
- V19가 같은 pinned lower root FD/snapshot에서 raw results, trace, lower
  projection과 check19 stdout을 독립 재계산
- P19와 V19 둘 다 PASS일 때만 external nonauthorizing receipt

`ANDROID_HOME`과 `ANDROID_SDK_ROOT`는 각각 같은 pinned absolute SDK raw value로
설정하고 equality를 검사한다. 문자열 `ANDROID_HOME=ANDROID_SDK_ROOT`를 값으로
쓰지 않는다. full19 PASS도 Goal/FP-008/product 권한이 아니다.

## 13. normal incident, receipt와 physical divergence

normal terminal은 journal/filesystem 구조가 exact인 상태에서 관찰한
nonzero/signal/timeout, semantic FAIL 또는 started-without-durable-completion이다.
application consumption의 signed non-success ingress terminal은 §3.1의 authenticated
`consumed` boolean과 raw response reason/hash/bytes를 `DURABLE_FAILURE_OBSERVATION`
선행 input으로 보존하며 다른 reason/state로 정규화하지 않는다.
lease/process interruption에서 나온 failure observation은 §6.1 external lease
service의 exact phase/lease-ID `CRASH_INTERRUPTION_OBSERVATION`과 B native durable
receipt/latest head를 선행 input으로 가져야 한다. command completion envelope나
semantic result 자체가 durable한 normal failure는 그 exact signed binding을
사용한다. local timeout 추론이나 stale/consumed observation으로 incident를
만들지 않는다.

```text
DURABLE_FAILURE_OBSERVATION
→ NORMAL_INCIDENT_INTENT
→ INCIDENT_PHYSICAL_DURABLE
→ INCIDENT_PARENT_FSYNC_CONFIRMED
```

normal success는 다음뿐이다.

```text
ORDERED_OUTCOME_PASS_DURABLE
→ RECEIPT_INTENT
→ RECEIPT_PHYSICAL_DURABLE
→ RECEIPT_PARENT_FSYNC_CONFIRMED
```

checkpoint는 `receipt_requirement_state` 하나만 가지며 P index 17은
`SELECTOR_PREFLIGHT_COMMITTED_REQUIRES_RECEIPT`, M index 15는
`TARGET_COMMITTED_REQUIRES_DURABLE_POSTCOMMIT_RECEIPT`를 exact 값으로 쓴다.
generic 제3 token이나 미래 receipt hash를 참조하지 않는다. receipt가
checkpoint/outcome/progress를 단방향 결속한다.

physical divergence는 valid incident도 valid receipt도 아니다. temp duplicate/
non-prefix, entry/inode mismatch, journal fork, intent 없는 final, 같은 transaction·
같은 terminal phase의 normal incident와 competing post-commit success receipt,
wrong receipt occupant가 있으면 `PHYSICAL_DIVERGENCE_FAIL_CLOSED`이고 repository/
gate에 새 incident 또는 receipt를 쓰지 않는다. bootstrap/pivot/quick prerequisite
receipt는 다른 phase의 normal incident와 공존해도 이 conflict가 아니다. B의
external audit sink에만 signed observation을 남길 수 있다. public UI가 recovery로
묶어 보여도 internal reason을 normal terminal과 혼동하지 않는다. recovery
completion 또는 terminal incident의 final parent fsync 뒤 §6.1
`RECOVERY_OBSERVATION_CLOSED`가 B latest head에 durable해야 해당 observation
lifecycle이 닫힌다.

## 14. FP-008와 Approval2 제거

R006은 R005가 보존한 R003 §6 Approval2 폐기를 되돌리지 않는다.

- Approval2 schema/prefix/gate root/request/receipt/claim/verifier: 만들지 않음
- `FP008_AUTHORIZATION=ABSENT_DENY_ALL`
- `GOAL_START=DENIED`
- `PRODUCT_WRITE_ALLOWLIST=[]`
- 상태: `DENY_ALL_FUTURE_SEPARATE_REVIEW_REQUIRED`

`WALKSAFE_APPROVAL2_FP008` 문자열, valid-looking receipt, P/M approval, v2.5
ACTIVE와 full19 PASS 어느 조합도 FP-008/Goal/product write를 열지 않는다. 기존
FP-008 preparation review는 future design validation input일 뿐 authority가
아니다. FP-008은 별도 add-only successor design, 독립검수와 새 user challenge
없이는 시작할 수 없다.

## 15. 필수 검증과 현재 판정

### authority/pivot

- A0/R0 absent, candidate self-signed root, alternate OS root, env/path override,
  rollback revocation, unsigned/expanded/delegating leaf는 prewrite 0
- P와 M response/nonce/claim 교환·재사용 실패
- pivot-before crash는 seq39, AGENTS exact-after부터 P recovery route
- pivot receipt 없이는 P application consumption 0, P receipt 없이는 M
  build/application claim 0
- M source→build→review0→L_M→request 순서 위반, prebuild review/leaf와
  candidate/review 변경 뒤 stale leaf/request는 prewrite 0
- M request가 observed GM archive를 요구하면 실패; reviewed TM source, future GM
  tombstone와 post-authorization byte-equality receipt만 PASS
- bootstrap capability의 marker/consumption ingress 밖 preclaim/final write,
  consumed attestation의 undeclared capture나 direct final write, claim D_DONE 전
  progress/final은 실패
- signed non-success ingress의 consumed boolean/reason을 바꾸거나 no/ambiguous
  ingress에서 incident를 쓰면 실패; `consumed=false`는
  `APPLICATION_CONSUMPTION_NOT_ESTABLISHED`, `consumed=true`+no valid attestation은
  `AUTH_SPENT_NO_APPLICATION` 하나만 PASS하고 claim/progress/final은 실패
- stale/consumed/closed crash observation, wrong phase/lease ID, non-latest B head,
  recovery capability second consume와 close 없는 success는 실패

### publication/crash

- D-PUBLISH 모든 byte offset에서 exact-prefix resume, 미래 inode 사전결속 실패
- O-CAPTURE spool-ready/start/EOF/raw fsync/completion/result/outcome 모든 경계
- started 뒤 completion 부재는 normal unknown, raw suffix 추측·재실행 금지
- bootstrap stage별 ancestor entry set과 mkdir absent/unconfirmed/durable
- claim/outcome/incident/receipt intent의 topological sort와 cycle fixture
- cross-parent `S0,T0 | S1,T1 | S0,T1 | S1,T0` 전부와 target-fsync/source-fsync/
  dual-name unlink 각 crash 경계; same-inode 아닌 dual과 unsupported filesystem 실패
- spool→raw target-first/dual cleanup은 command 재실행 없이 exact resume하고
  D_DONE 전 result descriptor/outcome publish는 실패

### selector/P/M

- P exact add5/CAS11/checkpoint@index17과 dynamic receipt logical #18
- old v2.4 path는 partial P에서 readiness FAIL, pivot selector는 recovery
- isolated reverse seq40은 byte-exact seq39 peel-back PASS, partial production
  peel-back은 divergence
- queue peel-back과 registry 3-state 실패
- M exact final15/checkpoint@index15, P index 1~16 protected path write 시도 실패
- application consumption marker, claim temp/spool, progress prefix 각각 recovery
- P/M quick PASS receipt absent + application marker present는 두 recovery가 아니라
  predecessor physical divergence; bootstrap/app rows의 marker/PASS predicate 교환 실패
- resolved expected-after와 progress observed-after 혼합 실패
- final15 REF node/edge/order/graph digest를 독립 재구성해 Kahn/Tarjan processed
  15, self-edge 0, SCC>1 0; `7→7`, `5→7`, `7→8→7`, indirect cycle,
  member→detached/resolved→member, undeclared nested/stale/future hash와 1-bit
  member/edge 변조 실패
- candidate root deny/remove 뒤 P selector와 final ACTIVE PASS

### execution/inventory

- verified interpreter FD와 actual `execveat` inode 불일치 실패
- quick 중 lock close/reopen, lease/root generation drift, second writer 실패
- non-UTF8 path b64u/length/hash/raw sort와 duplicate/invalid path 실패
- checkpoint-declared managed/product inventory와 Git index/stage/tombstone/
  nonignored-untracked/ignored exact sets를 두 번 직접 열거·재해시
- managed 1-byte/same-length 변조, delete, regular→symlink/hardlink, mode/nlink,
  extra/reclassified tracked·untracked·ignored, tombstone/index/ignore/config drift,
  aggregate-only 위조, enumerate→read/root/mount/lease drift와 read-trace
  누락/중복은 ACTIVE 실패
- full19 P19=V19, undeclared read, host-repo write, upper shadow check19,
  raw/trace/signature drift 실패
- full19/Approval2-looking input이 Goal/FP-008/product write를 열면 실패

현재 R006 상태는
`R005_REVIEW_FAILED_R006_DESIGN_ONLY_SOURCE_DRIFT_BLOCKED`다. 다음 허용 행동은
R006 exact bytes의 작성자 내부검토와 독립검수뿐이다. findings 0과 source drift
해결 전에는 P/M candidate build, authorization request, canonical/Goal/product
write를 하지 않는다.
