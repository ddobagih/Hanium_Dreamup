# WALKSAFE R007 correction implementation plan R003

## 0. 문서 지위와 금지선

```text
artifact_class = NON_EXECUTABLE_CORRECTION_IMPLEMENTATION_PLAN
document_status = PRE_REVIEW
authority = NONE
official_progress_delta = 0
execution_authorized = false
checkpoint_mutation_authorized = false
canonical_mutation_authorized = false
product_mutation_authorized = false
release_or_production_authorized = false
```

이 문서는 rejected R006, rejected R001 correction plan과 frozen rejected R002의
후속 R007을 작성하기 위한 add-only 구현계획이다. 이 문서 자체는 R007 successor,
authority grant, approval, consume receipt, closure evidence 또는 실행
지시가 아니다.

R006, R001/R002 plan, 열두 exact source, checkpoint, canonical, product,
daylog와 memory의 수정·대체·삭제를 허용하지 않는다. R007 author는 새
successor만 add-only로 작성한다. Freeze gate는 둘이다. 먼저 ledger,
Stage-C와 authority 세 independent plan review가 같은 frozen R003 SHA를
검수하고 각각 findings `0/0/0`이어야 이
계획을 authoring input으로 쓸 수 있다. 그 뒤 생성한 R007은 별도로 두
independent roadmap review가 같은 frozen R007 SHA를 검수하고 각각
findings `0/0/0`이어야 한다. 어느 gate도 통과하기 전 해당 artifact의
`PRE_REVIEW`를 올리지 않는다.

## 1. exact source identity와 predecessor disposition

상대 경로 기준점은 exact UTF-8
`docs/control/execution/artifact-closure/run-20260727-001/`이다.

| Source ID | 파일 | SHA-256 | bytes | lines | disposition |
|---|---|---|---:|---:|---|
| `SRC-R006` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006.md` | `0795cff0703b85469b61f454cbfefd3d6351b94900aaa49025edca3231d35c0a` | 306496 | 6670 | `REJECTED_HISTORY` |
| `SRC-R006-FORMAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-review-r001.md` | `ced8c9319996805ebcbb691dfa49cf8d5c6e38283c0f56254a1554952be2f374` | 20542 | 412 | `FAIL`, `6/0/0` |
| `SRC-R006-SKEPTICAL` | `WALKSAFE-NEXT-STEPS-DETAILED-ROADMAP-20260731-R006-independent-skeptical-review-r001.md` | `a4a3873589192fc09e0a6138aa1d76d0bb7aee1817f4655f2fabb88180332b2c` | 24902 | 465 | `FAIL`, `18/4/0` |
| `SRC-PLAN-R001` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001.md` | `bcf67b07c5d2a0454b6a2af325964e62d55f39e04c8c88684b2006738f027944` | 61353 | 2024 | `REJECTED_HISTORY` |
| `SRC-PLAN-R001-LEDGER` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001-independent-ledger-review-r001.md` | `1ed21a96908e5a9300ca433c259c12ffab0f2ba16fec3014fcaa1e76a9294c17` | 25582 | 581 | `FAIL`, `12/8/0` |
| `SRC-PLAN-R001-STAGEC` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001-independent-stagec-review-r001.md` | `448d8e7cd31ce7d1207d2426955c20c4a51ef65897f85b324ce8e314bfe2746c` | 26466 | 592 | `FAIL`, `6/4/0` |
| `SRC-PLAN-R001-AUTHORITY` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R001-independent-authority-review-r001.md` | `c1098e45a4e8eaddc33ca7be6a795d8f01daa999cf3e75ff08f8a7f9d6096cf3` | 31791 | 650 | `FAIL`, `16/3/0` |
| `SRC-PLAN-R002` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R002.md` | `5c7fd7482557325817b05dca2638eaf13a4451a22ab83d6255d4bd6b1f8aa4c0` | 299396 | 6338 | `REJECTED_HISTORY` |
| `SRC-PLAN-R002-INTEGRATED` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R002-integrated-frozen-audit-r001.md` | `38945b80b9bdb40ecdab955efb30f29c3f06c8778a2892e55f58104bbd25dd47` | 25643 | 510 | `FAIL`, `26/7/0` |
| `SRC-PLAN-R002-LEDGER` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R002-independent-ledger-review-r001.md` | `3a6449959334ad96833bfd02b2b946ba1b41bf092ca87462261b34a93c02e27f` | 20759 | 466 | `FAIL`, `7/0/0` |
| `SRC-PLAN-R002-AUTHORITY` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R002-independent-authority-review-r001.md` | `6983b1d0e3f6f3931c6ba9c4696dcee309aa12662ed34f9d6f8ee589492c91af` | 24096 | 533 | `FAIL`, `7/0/0` |
| `SRC-PLAN-R002-STAGEC` | `WALKSAFE-R007-CORRECTION-IMPLEMENTATION-PLAN-20260731-R002-independent-stagec-review-r001.md` | `9af58669ee91315c7151908906c7312c12027cf3ea83f8a91727e55bbc867dfd` | 29007 | 579 | `FAIL`, `7/1/0` |

```text
R006 canonical correction ledger = 20 BLOCKING / 4 MAJOR / 0 MINOR
R006 source refs before dedupe = 24 BLOCKING + 4 MAJOR
R001 plan-review raw headings = 34 BLOCKING + 15 MAJOR = 49
R006/R001 executable authority = NONE
R006/R001/R002 executable authority = NONE
R006/R001/R002 history mutation count = 0
R002 frozen identity = 5c7fd7482557325817b05dca2638eaf13a4451a22ab83d6255d4bd6b1f8aa4c0 / 299396 bytes / 6338 LF lines
R002 integrated correction union = 26 BLOCKING / 7 MAJOR / 0 MINOR
R002 review raw references total = 55
R002 supplemental-only review raw references = 22
R003 successor correction union = 31 BLOCKING / 7 MAJOR / 0 MINOR
R007 initial status = PRE_REVIEW
official progress delta = 0
```

## 2. 작성 목표와 source-order 원칙

R007 author는 다음을 동시에 만족해야 한다.

1. §3.1의 inherited 24개 finding, §3.2의 R002 18개 correction cluster와
   §3.3의 R003 `31B/7M` correction union을 누락하지 않는다.
2. 모든 JSON schema를 RFC 8785 JCS, `additionalProperties=false`와 exact
   tagged union으로 닫는다.
3. 어떤 body도 자기 SHA 또는 future artifact/receipt SHA를 가지지 않는다.
4. static template registry와 runtime literal instance registry를 분리하고
   runtime registry의 path/schema/publisher/Physical을 전부 채운다.
5. authority lifecycle, exact-four aggregate, all-four FSM, transition core,
   outbox, terminal seal을 one-way source order로 닫는다.
6. B06, B04, H2, M02와 H4를 literal row·stable edge·closed schema 수준으로
   물리화한다.
7. 모든 고정 수치는 행 열거 또는 registry filter로 재생성한다.
8. exact R003 bytes를 먼저 freeze해 ledger, Stage-C와 authority 세 plan
   review가 같은 SHA를 검수하고 각각 findings `0/0/0`을 보고한 뒤,
   그 gate 통과 뒤 작성한 exact R007 bytes를 두 roadmap review가 별도의
   같은 R007 SHA에서 검수한다.

## 3. correction ledgers

### 3.1 inherited R006-review canonical 24

| Canonical ID | exact R006 review source finding | correction surface |
|---|---|---|
| `R007-B001` | `R006-SK-BLOCKING-001` | G0 digest split |
| `R007-B002` | `R006-SK-BLOCKING-002` | nonexecution seal |
| `R007-B003` | `R006-SK-BLOCKING-003` | ALLOW exact-four genesis |
| `R007-B004` | `R006-SK-BLOCKING-004` | atomic deadline |
| `R007-B005` | `R006-FORMAL-BLOCKING-001` | finalization grant→execution consume |
| `R007-B006` | `R006-FORMAL-BLOCKING-002` | wrapper grant→finalization consume |
| `R007-B007` | `R006-FORMAL-BLOCKING-004`; `R006-SK-BLOCKING-005` | signed CRASH |
| `R007-B008` | `R006-SK-BLOCKING-006` | non-wrapper pre-revoke |
| `R007-B009` | `R006-SK-BLOCKING-007` | wrapper pre-close revoke |
| `R007-B010` | `R006-SK-BLOCKING-008` | consumed-open recovery |
| `R007-B011` | `R006-FORMAL-BLOCKING-005`; `R006-SK-BLOCKING-009` | wrapper totality |
| `R007-B012` | `R006-FORMAL-BLOCKING-006`; `R006-SK-BLOCKING-010` | GT/EO/ET |
| `R007-B013` | `R006-SK-BLOCKING-011` | expansion lineage |
| `R007-B014` | `R006-FORMAL-BLOCKING-003`; `R006-SK-BLOCKING-012` | PostG7 authority |
| `R007-B015` | `R006-SK-BLOCKING-013` | B04 application |
| `R007-B016` | `R006-SK-BLOCKING-014` | B06 semantic contract |
| `R007-B017` | `R006-SK-BLOCKING-015` | H2 exact6/current authority |
| `R007-B018` | `R006-SK-BLOCKING-016` | H4 run authority |
| `R007-B019` | `R006-SK-BLOCKING-017` | H4 exact-five |
| `R007-B020` | `R006-SK-BLOCKING-018` | seal final write |
| `R007-M001` | `R006-SK-MAJOR-001` | full plane binding |
| `R007-M002` | `R006-SK-MAJOR-002` | constructor encoding |
| `R007-M003` | `R006-SK-MAJOR-003` | M02 triple/U1 |
| `R007-M004` | `R006-SK-MAJOR-004` | G3 timing |

```text
canonical BLOCKING IDs = 20
canonical MAJOR IDs = 4
source BLOCKING references = 24
source MAJOR references = 4
merged BLOCKING overlap clusters = 4
unmapped or duplicate source heading = 0
```

### 3.2 R001 plan-review 49-heading deduplicated correction ledger

Source prefix는 `L=R001-LEDGER-REVIEW`, `S=R007-STAGEC`,
`A=R007-PLAN-AUTH`다. 아래 namespace는 §3.1과 분리된다.

| R002 canonical ID | exact plan-review source IDs | section |
|---|---|---|
| `R002-PLAN-C001` | `L-BLOCKING-001`; `S-BLOCKING-001`; `A-B001`; `A-B002` | §4.1~§4.4 |
| `R002-PLAN-C002` | `L-BLOCKING-002`; `L-BLOCKING-003`; `A-B003` | §4.5 |
| `R002-PLAN-C003` | `L-BLOCKING-006`; `A-B004`; `A-B005` | §4.6 |
| `R002-PLAN-C004` | `L-BLOCKING-005`; `A-B006` | §4.7 |
| `R002-PLAN-C005` | `S-BLOCKING-006`; `A-B007`; `A-B008` | §4.8 |
| `R002-PLAN-C006` | `L-BLOCKING-004`; `A-B009`; `A-B010`; `A-B011` | §4.9 |
| `R002-PLAN-C007` | `L-BLOCKING-007`; `L-MAJOR-003` | §4.10 |
| `R002-PLAN-C008` | `L-MAJOR-002`; `A-B012` | §5.1 |
| `R002-PLAN-C009` | `L-BLOCKING-008`; `A-B013` | §5.2 |
| `R002-PLAN-C010` | `L-MAJOR-001`; `A-B016`; `A-M001` | §5.3~§5.4 |
| `R002-PLAN-C011` | `L-MAJOR-004` | §4.3 |
| `R002-PLAN-C012` | `L-BLOCKING-009`; `S-BLOCKING-002` | §7 |
| `R002-PLAN-C013` | `L-BLOCKING-010`; `L-MAJOR-007`; `S-BLOCKING-003`; `A-B015` | §8 |
| `R002-PLAN-C014` | `L-BLOCKING-011`; `S-BLOCKING-004` | §10.1~§10.3 |
| `R002-PLAN-C015` | `L-BLOCKING-012`; `S-BLOCKING-005` | §10.4 |
| `R002-PLAN-C016` | `S-MAJOR-001`; `S-MAJOR-002`; `S-MAJOR-003` | §6, §7.1, §9 |
| `R002-PLAN-C017` | `L-MAJOR-006`; `S-MAJOR-004`; `A-B014` | §13~§15 |
| `R002-PLAN-C018` | `L-MAJOR-005`; `L-MAJOR-008`; `A-M002`; `A-M003` | §12, §4.9 |

```text
plan-review source ID occurrences = 49
unique plan-review source IDs = 49
R002 canonical plan clusters = 18
cross-namespace ID collision = 0
```

### 3.3 R002 frozen-audit 55-ref dedupe와 exact carry-in

`I`는 `SRC-PLAN-R002-INTEGRATED`, `L2`는 `SRC-PLAN-R002-LEDGER`,
`A2`는 `SRC-PLAN-R002-AUTHORITY`, `S2`는 `SRC-PLAN-R002-STAGEC`다.
아래 각 source finding은 정확히 한 correction row의 member다. 괄호나
prefix 생략은 허용하지 않으며 `R007-PLAN-AUTH-B008`은 55개 R002-review
raw ref 밖의 별도 exact carry-in이다.

| R003 correction ID | severity | exact source finding IDs | normalized correction surface |
|---|---|---|---|
| `R003-CORR-B001` | BLOCKING | `I:B01`; `L2:R002-LEDGER-REVIEW-BLOCKING-002`; `A2:R002-AUTH-B003` | WORK/TERMINAL/SEAL windows and seal fail-close |
| `R003-CORR-B002` | BLOCKING | `I:B02`; `L2:R002-LEDGER-REVIEW-BLOCKING-001` | lifecycle publication and SEALED atomic recovery |
| `R003-CORR-B003` | BLOCKING | `I:B03` | signed timer/CAS/lease authority |
| `R003-CORR-B004` | BLOCKING | `I:B04` | total preselection and activation timeout |
| `R003-CORR-B005` | BLOCKING | `I:B05`; `S2:R002-STAGEC-B001` | adverse sources and GT availability |
| `R003-CORR-B006` | BLOCKING | `I:B06`; `A2:R002-AUTH-B005` | original-outbox-only FSM027 resume |
| `R003-CORR-B007` | BLOCKING | `I:B07` | complete tagged terminal preimages |
| `R003-CORR-B008` | BLOCKING | `I:B08` | manifest payload-to-signature edge |
| `R003-CORR-B009` | BLOCKING | `I:B09` | consume/work-outbox/expansion lineage |
| `R003-CORR-B010` | BLOCKING | `I:B10` | acyclic PostG7/Ready order |
| `R003-CORR-B011` | BLOCKING | `I:B11` | separate recovery suffix/application consume |
| `R003-CORR-B012` | BLOCKING | `I:B12`; `S2:R002-STAGEC-B003` | branch-specific APPIN edges and APPIN002 |
| `R003-CORR-B013` | BLOCKING | `I:B13` | issuance same-observation authority |
| `R003-CORR-B014` | BLOCKING | `I:B14`; `L2:R002-LEDGER-REVIEW-BLOCKING-003`; `S2:R002-STAGEC-B004` | immutable H2 guard and acyclic idempotency |
| `R003-CORR-B015` | BLOCKING | `I:B15` | 25 complete H2 guard identities |
| `R003-CORR-B016` | BLOCKING | `I:B16` | H4 spec digest versus resolved digest |
| `R003-CORR-B017` | BLOCKING | `I:B17` | H4 predecessor SHA and Physical lineage |
| `R003-CORR-B018` | BLOCKING | `I:B18` | H4 closed arrays and constructors |
| `R003-CORR-B019` | BLOCKING | `I:B19`; `S2:R002-STAGEC-B006` | signed frozen required-run universe |
| `R003-CORR-B020` | BLOCKING | `I:B20` | literal H4 Gate role/node/edge/AST rows |
| `R003-CORR-B021` | BLOCKING | `I:B21`; `A2:R002-AUTH-B007`; `S2:R002-STAGEC-B005` | H4 CAS attestations and receipt outboxes |
| `R003-CORR-B022` | BLOCKING | `I:B22`; `L2:R002-LEDGER-REVIEW-BLOCKING-004`; `S2:R002-STAGEC-B007` | create-only registry generations/closure receipts |
| `R003-CORR-B023` | BLOCKING | `I:B23` | complete registry publication triplet refs |
| `R003-CORR-B024` | BLOCKING | `I:B24` | 24 literal positive case IDs |
| `R003-CORR-B025` | BLOCKING | `I:B25`; `L2:R002-LEDGER-REVIEW-BLOCKING-006` | executable fixture/self-audit PASS iff oracle |
| `R003-CORR-B026` | BLOCKING | `I:B26`; `L2:R002-LEDGER-REVIEW-BLOCKING-007` | five malformed GFM rows removed |
| `R003-CORR-B027` | BLOCKING | `L2:R002-LEDGER-REVIEW-BLOCKING-005`; `A2:R002-AUTH-B001`; `S2:R002-STAGEC-B002` | all context/set/output/obligation/outbox/expansion constructors |
| `R003-CORR-B028` | BLOCKING | `A2:R002-AUTH-B002` | activation CAS/project receipt crash recovery |
| `R003-CORR-B029` | BLOCKING | `A2:R002-AUTH-B004` | post-rename/pre-CAS exact-file reconciliation |
| `R003-CORR-B030` | BLOCKING | `A2:R002-AUTH-B006` | embedded CAS-signed terminal certificate |
| `R003-CORR-B031` | BLOCKING | `R007-PLAN-AUTH-B008` | exact five-state outbox carry-in and role separation |
| `R003-CORR-M001` | MAJOR | `I:M01` | DENY/ALLOW tagged context |
| `R003-CORR-M002` | MAJOR | `I:M02` | exact CauseEnum and phase subsets |
| `R003-CORR-M003` | MAJOR | `I:M03` | graph digest constructors |
| `R003-CORR-M004` | MAJOR | `I:M04` | B04 digest constructors |
| `R003-CORR-M005` | MAJOR | `I:M05` | EP004/G3E002 tuple dedupe |
| `R003-CORR-M006` | MAJOR | `I:M06`; `S2:R002-STAGEC-M001` | closed M02 tagged rows and endpoints |
| `R003-CORR-M007` | MAJOR | `I:M07` | unified AuthorityPlane row bytes |

```text
R002 integrated raw refs = 26 BLOCKING + 7 MAJOR = 33
R002 supplemental raw refs = 21 BLOCKING + 1 MAJOR = 22
R002 review raw refs total = 47 BLOCKING + 8 MAJOR = 55
deduplicated R002 correction union = 30 BLOCKING + 7 MAJOR
duplicate surplus = 17 BLOCKING + 1 MAJOR = 18
exact carry-in outside raw 55 = R007-PLAN-AUTH-B008 → R003-CORR-B031
R003 correction union = 31 BLOCKING + 7 MAJOR
raw ref mapped = 55/55
carry-in mapped = 1/1
unmapped = 0
multi-mapped raw source finding = 0
```

## 4. authority lifecycle, contexts, FSM, outbox와 seal

이 절의 모든 contract는 §4.1~§4.10의 closed schema와 표만 사용한다.

### 4.1 공통 scalar, Physical과 path expansion

모든 JSON object는 RFC 8785 JCS object이고 열거한 key가 required이며 다른
key는 금지한다. `SHA256(x)`의 출력은 raw 32 bytes이고 JSON SHA field는
그 raw bytes의 exact lowercase hexadecimal 64자 표현이다.

```text
Sha256Hex       = string matching ^[0-9a-f]{64}$
Nonce256Hex     = string matching ^[0-9a-f]{64}$
Identifier      = non-empty NFC UTF-8 string, NUL and '/' forbidden
LiteralPath     = non-empty NFC UTF-8 project-relative path,
                  no '.', '..', empty component, backslash, NUL or symlink
SourceAbsolutePath = NFC UTF-8 absolute preexisting read-source path,
                     leading '/' required; no '.', '..', empty component,
                     backslash, NUL or symlink
PrivateStageAbsolutePath = NFC UTF-8 absolute path beneath the exact
                           transaction-private staging root; leading '/'
                           required; no '.', '..', empty component,
                           backslash, NUL or symlink
Ordinal         = JSON integer 0..9007199254740991
Count           = JSON integer 0..9007199254740991
UtcNano         = string matching ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\.[0-9]{9}Z$
ModeOctal       = string matching ^0[0-7]{3}$
SignatureBytes  = lowercase even-length hexadecimal string
```

`FilePhysicalV2` closed fields:

```text
physical_type = FILE
literal_path: LiteralPath
parent_literal_path: LiteralPath
parent_anchor_sha: Sha256Hex
dev: Ordinal
ino: Ordinal
mode: ModeOctal
uid: Ordinal
gid: Ordinal
nlink: Ordinal
bytes: Count
sha256: Sha256Hex
file_type = REGULAR
nofollow_open = true
```

`DirPhysicalV2`는 같은 field 중 `bytes/sha256`을 제외하고
`physical_type=DIRECTORY`, `file_type=DIRECTORY`를 사용한다.
Private H2 staging files use a separate closed type:

```text
StagedFilePhysicalV2 = {
  "physical_type":"STAGED_FILE",
  "private_stage_absolute_path":PrivateStageAbsolutePath,
  "parent_private_stage_absolute_path":PrivateStageAbsolutePath,
  "parent_anchor_sha":Sha256Hex,
  "dev":Ordinal,"ino":Ordinal,"mode":ModeOctal,
  "uid":Ordinal,"gid":Ordinal,"nlink":Ordinal,
  "bytes":Count,"sha256":Sha256Hex,
  "file_type":"REGULAR","nofollow_open":true
}
```

It is never accepted where `FilePhysicalV2` or a project `LiteralPath` is
required.
`CasRecordPhysicalV2`는 project file이 아니며 다음 exact fields만 가진다.

```text
physical_type = CAS_RECORD
service_id: Identifier
service_physical_sha: Sha256Hex
partition_id: Identifier
record_key_jcs_sha: Sha256Hex
record_generation: Ordinal
record_sha: Sha256Hex
```

`SourceAbsolutePath` is allowed only in preexisting source/read constructors
such as M02. `PrivateStageAbsolutePath` is allowed only in H2 private staging.
Neither can be an output `literal_path`; every project output uses
`LiteralPath`.

Static template에서 허용되는 token은
`{successor_revision_id}`, `{attempt_namespace_id}`, `{attempt_id}`,
`{transition_id}`, `{role_id}`, `{h2_transaction_id}`, `{h4_id}`,
`{run_id}`, `{gate_slug}` exact nine 개다. Each token substitutes one already
validated scalar without encoding or normalization. `RoleInstanceRegistryV2`
contains zero brace tokens, wildcards or ellipsis tokens.

```text
ROADMAP_DIR =
docs/control/execution/artifact-closure/run-20260727-001

H1_ROOT_TEMPLATE =
docs/control/execution/artifact-closure/run-20260727-001/
walksafe-pre-p-closure-successor-h1-r007-{successor_revision_id}

ATTEMPT_ROOT_TEMPLATE =
H1_ROOT_TEMPLATE/attempts/{attempt_namespace_id}

AUTH_ROOT_TEMPLATE =
ATTEMPT_ROOT_TEMPLATE/authority-v2
```

위 display line break는 bytes에 포함되지 않는다. Runtime expansion은 `/`로
이어진 한 줄이며 `LiteralPath` validation 뒤 nofollow parent reopen을 한다.

### 4.2 namespace lifecycle와 ALLOW authority aggregate 분리

Namespace commit 직후 만드는 유일 lifecycle key:

```text
AttemptLifecycleKeyV2 object = {
  "schema":"ATTEMPT_LIFECYCLE_KEY_V2",
  "successor_revision_id": Identifier,
  "attempt_namespace_id": Sha256Hex
}
lifecycle_key_digest =
  SHA256(
    ASCII("WS-WALKSAFE-R007-ATTEMPT-LIFECYCLE-KEY-V2") || 0x00 ||
    RFC8785_JCS(AttemptLifecycleKeyV2 object)
  )
```

Lifecycle register state:

```text
NAMESPACE_COMMITTED
→ AWAITING_REQUEST
AWAITING_REQUEST
  → REQUEST_PENDING | ABANDONED_PRE_ALLOW
REQUEST_PENDING
  → DENIED | REQUEST_EXPIRED | ABANDONED_PRE_ALLOW | ALLOW_DECIDED
ALLOW_DECIDED
  → ACTIVATION_PENDING | ABANDONED_POST_ALLOW_AGGREGATE_ABSENT
ACTIVATION_PENDING
  → AUTHORITY_ACTIVE | ABANDONED_POST_ALLOW_AGGREGATE_PRESENT
DENIED | REQUEST_EXPIRED | ABANDONED_PRE_ALLOW |
ABANDONED_POST_ALLOW_AGGREGATE_ABSENT |
ABANDONED_POST_ALLOW_AGGREGATE_PRESENT
  → TERMINAL_SELECTED → SEAL_QUEUED → SEALED | SEAL_FAILED_CLOSED
AUTHORITY_ACTIVE
  → TERMINAL_SELECTED → TERMINAL_SETTLED → SEAL_QUEUED → SEALED
```

`PreselectionDeadlineSetV3` is frozen and signed by namespace commit. It has
`request_issue_not_after,request_outcome_not_after,
activation_commit_not_after,lifecycle_seal_not_after`, one trusted-clock
binding and three `SignedTimerRegistrationRefV3` rows. It requires
`namespace_committed_at < request_issue_not_after <
request_outcome_not_after < activation_commit_not_after <
lifecycle_seal_not_after`. A submitted request copies
`request_expires_at=request_outcome_not_after`; an ALLOW decision copies the
activation boundary and requires it before every role operation deadline.

Every arrow above is a literal signed token-CAS row. At each boundary, the
timer firing and the competing request/decision/activation event compare the
same lifecycle token, so exactly one wins. The `AWAITING_REQUEST` timeout
selects pre-ALLOW abandon; the `REQUEST_PENDING` timeout selects
`REQUEST_EXPIRED`; the `ALLOW_DECIDED` timeout proves aggregate absence and
selects post-ALLOW abandon. An activation CAS creates the aggregate and four
`U` rows but leaves lifecycle `ACTIVATION_PENDING`; only activation receipt
`ATTESTED` atomically changes it to `AUTHORITY_ACTIVE`. Activation publication
expiry changes all four `U→N`, freezes exact absence/collision evidence and
queues one original seal outbox. No later activation or role consume is legal.
Every unlisted state/event pair has token/receipt/lease/write count `0`.

Lifecycle row는 namespace append receipt의 committed token, member/head/
observation SHA와 Physical, lifecycle pre/post token, CAS service identity와
`linearized_at`을 가진 same-store record다. It never contains `attempt_id`.
`DENIED`, `REQUEST_EXPIRED`, `ABANDONED_PRE_ALLOW`와
`ABANDONED_POST_ALLOW_AGGREGATE_ABSENT`는 authority aggregate row count가
`0`이다. `ABANDONED_POST_ALLOW_AGGREGATE_PRESENT` alone has the existing
aggregate and exactly four terminal `N` rows; it may not create another key.

`attempt_id`는 ALLOW decision에서 처음 생긴다. 그 뒤에만 다음 aggregate
key를 exact 한 번 만든다.

```text
AttemptAuthorityAggregateKeyV2 object = {
  "schema":"ATTEMPT_AUTHORITY_AGGREGATE_KEY_V2",
  "successor_revision_id": Identifier,
  "attempt_namespace_id": Sha256Hex,
  "attempt_id": Sha256Hex
}
aggregate_key_digest =
  SHA256(
    ASCII("WS-WALKSAFE-R007-AUTHORITY-AGGREGATE-KEY-V2") || 0x00 ||
    RFC8785_JCS(AttemptAuthorityAggregateKeyV2 object)
  )
```

Role ordinal과 ID는 exact four다.

| ordinal | role_id |
|---:|---|
| 0 | `EXECUTION` |
| 1 | `CLOSE_RECOVERY` |
| 2 | `POST_CLOSE_FINALIZATION` |
| 3 | `TERMINAL_WRAPPER_RECOVERY` |

Legacy per-role authority key, aggregate와 legacy mixed transition, ALLOW 전
aggregate construction, non-ALLOW aggregate construction은 모두 effect
`NONE`, receipt/lease/project write `0`이다.

### 4.3 G0 digest split과 exact constructors

`ProtectedFreshnessObservationV2` is constructed only after its protected
snapshot exists:

```text
schema
observed_protected_input_rows[]
observed_protected_input_count
g0_after_snapshot_digest
observation_actor_id + observation_actor_physical_sha
observed_at
```

The two identities are deliberately distinct:

```text
g0_after_snapshot_digest =
  SHA256(
    ASCII("WS-WALKSAFE-R007-G0-AFTER-SNAPSHOT-V2") || 0x00 ||
    RFC8785_JCS(observed_protected_input_rows))

protected_freshness_observation_digest =
  SHA256(
    ASCII("WS-WALKSAFE-R007-PROTECTED-FRESHNESS-OBSERVATION-V2") || 0x00 ||
    RFC8785_JCS(ProtectedFreshnessObservationV2))
```

The observation inward-carries the already computed snapshot digest. P0
compares the snapshot digest to its expected snapshot field and the observation
object digest to its expected observation field separately. Neither equality
aliases the two. A preimage containing its own observation digest, a
snapshot/object digest alias or a P0 comparison against the wrong field is
rejected by `FX-R007-B001-G0-DIGEST-SPLIT`.

`AttemptNamespaceConstructorV2`의 field order below is documentation order;
JCS key order is lexicographic UTF-16 code-unit order.

For a non-genesis constructor, the namespace CAS service first issues the
closed predecessor-only `OldRootRecheckChallengeEnvelopeV2`:

```text
{
  "body": {
    "schema":"OLD_ROOT_RECHECK_CHALLENGE_V2",
    "successor_revision_id":Identifier,
    "tested_successor_sha":Sha256Hex,
    "predecessor_attempt_namespace_id":Sha256Hex,
    "predecessor_terminal_seal_sha":Sha256Hex,
    "predecessor_terminal_seal_physical_digest":Sha256Hex,
    "predecessor_sealed_root_identity_digest":Sha256Hex,
    "predecessor_head_cas_token":Sha256Hex,
    "challenge_nonce":Nonce256Hex,
    "issued_at":UtcNano,
    "not_after":UtcNano,
    "issuer_actor_id":Identifier,
    "issuer_physical_sha":Sha256Hex
  },
  "service_signature": {
    "algorithm":Identifier,
    "signer_actor_id":Identifier,
    "signer_physical_sha":Sha256Hex,
    "signature_domain":"WS-WALKSAFE-R007-OLD-ROOT-RECHECK-CHALLENGE-SIGNATURE-V2",
    "signature":SignatureBytes
  }
}
```

The signature input is its literal signature domain, NUL and body JCS.
`old_root_recheck_challenge_digest` is
`SHA256(ASCII("WS-WALKSAFE-R007-OLD-ROOT-RECHECK-CHALLENGE-V2") || 0x00 ||
RFC8785_JCS(the full envelope))`. The challenge contains no successor attempt
namespace ID, successor attempt ID or recheck receipt reference. Its nonce is
unique for the predecessor head token and `issued_at < not_after`.

```text
AttemptNamespaceConstructorV2 = {
  "schema":"ATTEMPT_NAMESPACE_CONSTRUCTOR_V2",
  "successor_revision_id": Identifier,
  "tested_successor_sha": Sha256Hex,
  "successor_revision_member_payload_sha": Sha256Hex,
  "successor_revision_member_wrapper_sha": Sha256Hex,
  "successor_revision_append_receipt_sha": Sha256Hex,
  "successor_revision_head_observation_sha": Sha256Hex,
  "attempt_ordinal": Ordinal,
  "predecessor":
    {"kind":"GENESIS_NA"}
    XOR
    {
      "kind":"PREDECESSOR",
      "attempt_namespace_id":Sha256Hex,
      "terminal_seal_sha":Sha256Hex,
      "terminal_seal_physical_digest":Sha256Hex,
      "sealed_root_identity_digest":Sha256Hex,
      "head_cas_token":Sha256Hex,
      "old_root_recheck_challenge_digest":Sha256Hex
    },
  "fresh_attempt_namespace_nonce": Nonce256Hex,
  "namespace_materializer_actor_id": Identifier,
  "namespace_frozen_at": UtcNano,
  "attempt_role_template_digest": Sha256Hex,
  "schema_registry_digest": Sha256Hex,
  "root_parent_physical_digest": Sha256Hex
}
attempt_namespace_id =
  SHA256(
    ASCII("WS-WALKSAFE-R007-ATTEMPT-NAMESPACE-CONSTRUCTOR-V2") || 0x00 ||
    RFC8785_JCS(AttemptNamespaceConstructorV2)
  )
```

Genesis uses only `{"kind":"GENESIS_NA"}`. Omitted predecessor keys, JSON
`null`, empty string, `"NA"`, `0`, lowercase/uppercase tag substitution,
raw-hash/string cast, locale sort and path normalization are invalid.
Ordinals are JSON integers; SHA/nonces are lowercase hex strings; times use
`UtcNano`.

ALLOW decision contains `AttemptIdConstructorV2` and the computed ID.

```text
AttemptIdConstructorV2 = {
  "schema":"ATTEMPT_ID_CONSTRUCTOR_V2",
  "successor_revision_id":Identifier,
  "attempt_namespace_id":Sha256Hex,
  "request_payload_sha":Sha256Hex,
  "request_wrapper_sha":Sha256Hex,
  "response_provenance_sha":Sha256Hex,
  "request_nonce":Nonce256Hex,
  "response_nonce":Nonce256Hex,
  "decision_nonce":Nonce256Hex,
  "decision_sequence":Ordinal,
  "decision_issued_at":UtcNano,
  "decision_actor_id":Identifier,
  "decision_outcome":"ALLOW"
}
attempt_id =
  SHA256(
    ASCII("WS-WALKSAFE-R007-ATTEMPT-ID-CONSTRUCTOR-V2") || 0x00 ||
    RFC8785_JCS(AttemptIdConstructorV2)
  )
```

Decision payload SHA, decision wrapper SHA, attempt ID itself and any
post-decision value are excluded from this preimage. R007 freezes at least one
GENESIS and one PREDECESSOR canonical JCS byte vector plus expected SHA in
`FX-R007-M002-CONSTRUCTOR-GOLDEN`; generator output and independently
re-serialized bytes must be byte-equal.

### 4.4 self/future-free phase contexts

`AllowlistEntryV2` closed fields:

```text
ordinal: Ordinal
role_id: Identifier
literal_path: LiteralPath
schema_role: Identifier
schema_sha: Sha256Hex
publisher_actor_id: Identifier
publisher_physical_sha: Sha256Hex
physical_kind: FILE | DIRECTORY | CAS_RECORD
plane: CONTROL | DATA
access: READ | WRITE
write_phase:
  NAMESPACE | REQUEST | DECISION | ACTIVATION | EXECUTION |
  RECOVERY | FINALIZATION | WRAPPER | TERMINAL | SEAL
branch_predicate_id: Identifier
exact_cardinality: Count
```

Allowlist rows sort by
`(plane ordinal CONTROL=0/DATA=1, write_phase enum ordinal, role_id UTF-8
bytes, literal_path UTF-8 bytes, ordinal)`. `ordinal` must equal the zero-based
position after this sort.

`PlaneEntryV2` has the same thirteen fields. Control/data arrays are projections
of exact allowlist rows, not independently authored copies.

```text
ConcreteAllowlistBindingV2 = {
  "ordered_entries": array of AllowlistEntryV2,
  "entry_count":Count,
  "ordered_entries_digest":Sha256Hex
}

AttemptPlaneSetBindingV2 = {
  "ordered_control_entries": array of PlaneEntryV2,
  "control_count":Count,
  "control_digest":Sha256Hex,
  "ordered_data_entries": array of PlaneEntryV2,
  "data_count":Count,
  "data_digest":Sha256Hex,
  "ordered_union_entries": array of PlaneEntryV2,
  "union_count":Count,
  "union_digest":Sha256Hex,
  "ordered_intersection_entries": array of PlaneEntryV2,
  "intersection_count":Count,
  "intersection_digest":Sha256Hex
}
```

Each digest is
`SHA256(ASCII("WS-WALKSAFE-R007-"+NAME+"-V2") || 0x00 ||
RFC8785_JCS(array))`. Union is a stable merge by the allowlist comparator.
Intersection key is
`(role_id,literal_path,schema_sha,publisher_actor_id,
publisher_physical_sha)`. Required intersection is empty:
`intersection_count=0` and
`intersection_digest=SHA256(ASCII("WS-WALKSAFE-R007-PLANE-INTERSECTION-V2")
||0x00||RFC8785_JCS([]))`.

`FrozenNamespaceContextV2` contains only namespace-commit-time values:

```text
schema = FROZEN_NAMESPACE_CONTEXT_V2
successor_revision_id
tested_successor_sha
attempt_namespace_id
attempt_ordinal
attempt_namespace_constructor_digest
literal_attempt_root
namespace_member_payload_sha + namespace_member_wrapper_sha
namespace_member_payload_physical + namespace_member_wrapper_physical
namespace_append_receipt_sha + namespace_append_receipt_physical
namespace_head_payload_sha + namespace_head_wrapper_sha
namespace_head_payload_physical + namespace_head_wrapper_physical
namespace_head_observation_sha + namespace_head_observation_physical
namespace_cas_token
namespace_committed_at: UtcNano
concrete_allowlist_binding: ConcreteAllowlistBindingV2
attempt_plane_set_binding: AttemptPlaneSetBindingV2
lifecycle_key_digest
lifecycle_genesis_token
lifecycle_seal_not_after: UtcNano
lifecycle_trusted_clock_source_id: Identifier
lifecycle_trusted_clock_correlation_sha: Sha256Hex
lifecycle_timer_registration: SignedTimerRegistrationRefV3
```

It contains no request, response, decision, attempt ID, grant, activation,
consume, transition, output or seal reference.

`AuthorityRequestContextV2` exact fields:

```text
schema = AUTHORITY_REQUEST_CONTEXT_V2
frozen_namespace_context: FrozenNamespaceContextV2
request_id: Sha256Hex
request_nonce: Nonce256Hex
requester_actor_id: Identifier
requester_physical_sha: Sha256Hex
ordered_requested_scope_entries: array of AuthorityScopeEntryV2
requested_scope_count: Count
requested_scope_digest: Sha256Hex
lifecycle_seal_not_after: exact copy from frozen_namespace_context
lifecycle_trusted_clock_source_id: exact copy from frozen_namespace_context
lifecycle_trusted_clock_correlation_sha: exact copy from frozen_namespace_context
request_issued_at: UtcNano
request_operation_not_after: UtcNano
request_expires_at: UtcNano
```

The namespace CAS freezes
`namespace_committed_at < request_operation_not_after <= request_expires_at <
lifecycle_seal_not_after`. The request timer is registered in the same CAS as
`REQUEST_PENDING`; its signed registration is copied into the request context.
Consequently `REQUEST_PENDING` has exactly one finite outcome even when no
response arrives.

`request_id` hashes domain
`WS-WALKSAFE-R007-AUTHORITY-REQUEST-ID-V2`, NUL and the JCS projection
`{successor_revision_id,attempt_namespace_id,request_nonce,
requester_actor_id,requested_scope_digest,request_issued_at,
request_operation_not_after,request_expires_at,lifecycle_seal_not_after,
lifecycle_trusted_clock_source_id,lifecycle_trusted_clock_correlation_sha}`.

`AuthorityResponseContextV2` exact fields:

```text
schema = AUTHORITY_RESPONSE_CONTEXT_V2
request_payload_sha + request_wrapper_sha
request_payload_physical + request_wrapper_physical
request_context: AuthorityRequestContextV2
response_outcome: ALLOW | DENY
response_nonce: Nonce256Hex
response_origin_sha + response_origin_physical
response_provenance_sha + response_provenance_physical
authority_actor_id: Identifier
authority_actor_physical_sha: Sha256Hex
responded_at: UtcNano
```

Request and response are mandatory detached-signature pairs:

| role | literal path template | schema | publisher |
|---|---|---|---|
| `AUTHORITY-REQUEST-PAYLOAD` | `AUTH_ROOT_TEMPLATE/request/authority-request.payload.json` | `AUTHORITY_REQUEST_PAYLOAD_V2` | `AUTHORITY_REQUEST_SERIALIZER` |
| `AUTHORITY-REQUEST-SIGNATURE` | `AUTH_ROOT_TEMPLATE/request/authority-request.signature.json` | `AUTHORITY_REQUEST_DETACHED_SIGNATURE_V2` | `AUTHORITY_REQUEST_SIGNER` |
| `AUTHORITY-RESPONSE-PAYLOAD` | `AUTH_ROOT_TEMPLATE/response/authority-response.payload.json` | `AUTHORITY_RESPONSE_PAYLOAD_V2` | `AUTHORITY_RESPONSE_SERIALIZER` |
| `AUTHORITY-RESPONSE-SIGNATURE` | `AUTH_ROOT_TEMPLATE/response/authority-response.signature.json` | `AUTHORITY_RESPONSE_DETACHED_SIGNATURE_V2` | `AUTHORITY_RESPONSE_SIGNER` |

`AuthorityRequestPayloadV2` exact fields are
`schema,request_context,request_reason_code,publisher_actor_id,
publisher_physical_sha,signature_domain`; the response payload exact fields are
`schema,response_context,response_reason_code,publisher_actor_id,
publisher_physical_sha,signature_domain`. Each detached signature has exact
fields `schema,payload_path,payload_schema_sha,payload_sha,payload_physical,
signer_actor_id,signer_physical_sha,signature_algorithm,signature_domain,
signature`. The domains are respectively
`WS-WALKSAFE-R007-AUTHORITY-REQUEST-PAYLOAD-V2` and
`WS-WALKSAFE-R007-AUTHORITY-RESPONSE-PAYLOAD-V2`; signing input is domain, NUL
and full payload JCS.

`DecisionBoundContextV2` exact fields:

```text
schema = DECISION_BOUND_CONTEXT_V2
frozen_namespace_context: FrozenNamespaceContextV2
request_payload_sha + request_wrapper_sha
request_payload_physical + request_wrapper_physical
response_payload_sha + response_wrapper_sha
response_payload_physical + response_wrapper_physical
response_context: AuthorityResponseContextV2
response_origin_sha + response_provenance_sha
response_origin_physical + response_provenance_physical
response_outcome: ALLOW | DENY
request_nonce + response_nonce + decision_nonce
decision_sequence: Ordinal
decision_issued_at: UtcNano
decision_actor_id: Identifier
decision_actor_physical_sha: Sha256Hex
ordered_decided_scope_entries: array of AuthorityScopeEntryV2
decided_scope_count: Count
decided_scope_digest: Sha256Hex
deadline_context:
  {"kind":"NONALLOW_NOT_APPLICABLE"}
  XOR
  {
    "kind":"ALLOW_WITH_ROLE_DEADLINES",
    "role_deadlines":RoleDeadlineSetV3,
    "outbox_deadline_set":OutboxDeadlineSetV3
  }
attempt_id_binding:
  {"kind":"NOT_APPLICABLE","reason":"NON_ALLOW"}
  XOR
  {
    "kind":"ALLOW_ATTEMPT_ID",
    "constructor":AttemptIdConstructorV2,
    "attempt_id":Sha256Hex
  }
```

`response_outcome=DENY` requires both `deadline_context.kind=
NONALLOW_NOT_APPLICABLE` and `attempt_id_binding.kind=NOT_APPLICABLE`.
`response_outcome=ALLOW` requires both ALLOW alternatives. Cross-tagged
objects, a DENY with role deadlines, or an ALLOW without them are invalid.

Execution decision publication is mandatory pair:

```text
AUTH_ROOT_TEMPLATE/decision/decision.payload.json
  schema = EXECUTION_DECISION_PAYLOAD_V2
  publisher = AUTHORITY_DECISION_SERIALIZER
AUTH_ROOT_TEMPLATE/decision/decision.signature.json
  schema = EXECUTION_DECISION_DETACHED_SIGNATURE_V2
  publisher = AUTHORITY_DECISION_SIGNER
```

Payload fields are
`schema,DecisionBoundContextV2,decision_outcome,decision_reason_code,
decision_published_at,publisher_actor_id,publisher_physical_sha,
signature_domain`. The detached wrapper fields are
`schema,payload_path,payload_schema_sha,payload_sha,payload_physical,
signer_actor_id,signer_physical_sha,signature_algorithm,signature_domain,
signature`. Signature input is
`ASCII("WS-WALKSAFE-R007-EXECUTION-DECISION-PAYLOAD-V2") || 0x00 ||
RFC8785_JCS(full payload)`.

Mandatory direct chain:

```text
AR001 AUTHORITY-REQUEST-PAYLOAD → AUTHORITY-REQUEST-SIGNATURE
AR002 AUTHORITY-REQUEST-SIGNATURE → AUTHORITY-RESPONSE-PAYLOAD
AR003 AUTHORITY-RESPONSE-PAYLOAD → AUTHORITY-RESPONSE-SIGNATURE
AR004 AUTHORITY-RESPONSE-SIGNATURE → EXECUTION-DECISION-PAYLOAD
AR005 EXECUTION-DECISION-PAYLOAD → EXECUTION-DECISION-SIGNATURE
```

Decision construction compares its embedded request and response contexts to
the four artifact SHA/Physical bindings byte-for-byte. A response provenance
digest without the response pair, a decision that skips `AR003/AR004`, or a
response referring to a different request cannot materialize.

`AuthorityScopeEntryV2` closed fields:

```text
ordinal
role_id
access: READ | WRITE
literal_path
schema_role
schema_sha
publisher_actor_id
publisher_physical_sha
physical_kind
branch_predicate_id
exact_cardinality
```

It sorts by `(access READ=0/WRITE=1, role_id, literal_path, ordinal)`.

`RoleDeadlineSetV3` is a closed object with `activation_not_after` and
`ordered_role_deadline_rows[4]` in role ordinal order. Each row has
`role_id,operation_not_after,settlement_not_after,
trusted_clock_source_id,trusted_clock_source_physical_sha,
trusted_clock_correlation_sha`. For every row,
`activation_not_after < operation_not_after < settlement_not_after`.
Cross-role ordering is:

```text
EXECUTION.operation_not_after
< EXECUTION.settlement_not_after
<= CLOSE_RECOVERY.operation_not_after
< CLOSE_RECOVERY.settlement_not_after
<= POST_CLOSE_FINALIZATION.operation_not_after
< POST_CLOSE_FINALIZATION.settlement_not_after
<= TERMINAL_WRAPPER_RECOVERY.operation_not_after
< TERMINAL_WRAPPER_RECOVERY.settlement_not_after
```

`OutboxDeadlineProfileV3` is the exact tagged row:

```text
schema=OUTBOX_DEADLINE_PROFILE_V3
kind = WORK | TERMINAL | SEAL
first_claim_not_after
publish_not_after
reconcile_not_after
trusted_clock_source_id
trusted_clock_source_physical_sha
trusted_clock_correlation_sha
timer_registration: SignedTimerRegistrationRefV3
claim_lease_ns: Ordinal
```

`OutboxDeadlineSetV3` is a closed object containing exactly three profiles in
the order `WORK,TERMINAL,SEAL`. Profiles are disjoint and obey:

```text
WORK.first_claim_not_after
< WORK.publish_not_after
<= WORK.reconcile_not_after
<= min(all four role settlement_not_after)
<= TERMINAL.first_claim_not_after
< TERMINAL.publish_not_after
< TERMINAL.reconcile_not_after
<= SEAL.first_claim_not_after
< SEAL.publish_not_after
< SEAL.reconcile_not_after
<= lifecycle_seal_not_after
```

The decision serializer constructs it before signing the ALLOW decision.
Activation must linearize before `activation_not_after` and atomically stores
the three signed timer registrations with the aggregate genesis and the
activation-receipt outbox. Every obligation chooses exactly one profile by
slot: dispatch and finalization work use `WORK`; functional/disposition
terminal batches use `TERMINAL`; `ATTEMPT_SEAL` uses `SEAL`. No profile field
is copied from another profile and no terminal deadline aliases a seal
deadline.

Every profile digest is
`SHA256(ASCII("WS-WALKSAFE-R007-OUTBOX-DEADLINE-PROFILE-V3") || 0x00 ||
RFC8785_JCS(profile))`; the set digest uses domain
`WS-WALKSAFE-R007-OUTBOX-DEADLINE-SET-V3`, NUL and the ordered three full
profiles. Later artifacts copy the complete set byte-for-byte.

`SignedTimerRegistrationRefV3` contains
`timer_kind,timer_id,record_sha,record_physical:CasRecordPhysicalV2,
deadline,trusted_clock_source_id,trusted_clock_source_physical_sha,
trusted_clock_correlation_sha,timer_service_actor_id,
timer_service_physical_sha,service_signature_domain,
service_signature_algorithm,service_signature`. The signed timer body binds
the lifecycle or aggregate key, exact expected state/token, event ID, deadline
and CAS comparator. Registration, callback firing and expiry each have a
predecessor-only CAS receipt with domain, NUL and body JCS; a callback can only
invoke its registered event against its frozen key/token. The sole lease
duration authority is the selected profile's positive `claim_lease_ns`, and
`claim_not_after=min(claimed_at+claim_lease_ns,publish_not_after)`.

`ArtifactBindingV2` is the reusable prior-artifact reference:

```text
role_id
literal_path
schema_role
schema_sha
content_sha
file_physical: FilePhysicalV2
publisher_actor_id
publisher_physical_sha
published_at
frozen_at
artifact_nonce
scope_digest
```

No enclosing artifact puts its own `content_sha` in this type.

`RoleGrantBindingV2` exact fields:

```text
role_id
grant_payload: ArtifactBindingV2
grant_wrapper: ArtifactBindingV2
grant_nonce
attempt_id
scope_binding: RoleScopeBindingV2
operation_not_after + settlement_not_after
outbox_deadline_set: OutboxDeadlineSetV3
initial_revocation_head_payload_sha + initial_revocation_head_wrapper_sha
initial_revocation_head_payload_physical
initial_revocation_head_wrapper_physical
initial_revocation_ordinal = 0
initial_revocation_token
```

`RoleScopeBindingV2` exact fields:

```text
ordered_read_scope_entries: array of AuthorityScopeEntryV2
read_count + read_digest
ordered_write_scope_entries: array of AuthorityScopeEntryV2
write_count + write_digest
ordered_scope_union_entries: array of AuthorityScopeEntryV2
union_count + union_digest
read_write_intersection_count=0 + intersection_digest
decided_scope_digest
write_subset_witness_rows[]{
  grant_write_ordinal,decided_scope_ordinal,full_entry_digest
}
write_subset_count + write_subset_digest
subset_result=PASS
```

All arrays use the `AuthorityScopeEntryV2` comparator. Each digest is
`SHA256(ASCII("WS-WALKSAFE-R007-ROLE-SCOPE-"+NAME+"-V2") || 0x00 ||
RFC8785_JCS(array))`. Every grant read/write row must equal one full decided
scope row; every write row has one witness, and no decided-scope row may be
manufactured by digest aliasing. Counts, arrays, digests, intersection and
subset witnesses are all compared at activation and consume.

`ActivationInputContextV2` exact fields:

```text
schema = ACTIVATION_INPUT_CONTEXT_V2
decision_context: DecisionBoundContextV2 with ALLOW_ATTEMPT_ID
decision_payload_binding: ArtifactBindingV2
decision_wrapper_binding: ArtifactBindingV2
watchdog_payload_binding: ArtifactBindingV2
watchdog_wrapper_binding: ArtifactBindingV2
close_recovery_grant: RoleGrantBindingV2
post_close_finalization_grant: RoleGrantBindingV2
terminal_wrapper_recovery_grant: RoleGrantBindingV2
aggregate_key_digest
outbox_deadline_set: OutboxDeadlineSetV3
activation_requested_at
```

The execution role binds the decision payload/wrapper; the other roles bind
their named grant pair. Watchdog and all three grant pairs precede activation.
Grant/watchdog role paths are exact:

```text
AUTH_ROOT_TEMPLATE/grants/close-recovery.payload.json
AUTH_ROOT_TEMPLATE/grants/close-recovery.signature.json
AUTH_ROOT_TEMPLATE/grants/post-close-finalization.payload.json
AUTH_ROOT_TEMPLATE/grants/post-close-finalization.signature.json
AUTH_ROOT_TEMPLATE/grants/terminal-wrapper-recovery.payload.json
AUTH_ROOT_TEMPLATE/grants/terminal-wrapper-recovery.signature.json
AUTH_ROOT_TEMPLATE/watchdog/execution-watchdog.payload.json
AUTH_ROOT_TEMPLATE/watchdog/execution-watchdog.signature.json
```

`RoleGrantPayloadV2` closed fields:

```text
schema
role_id =
  CLOSE_RECOVERY | POST_CLOSE_FINALIZATION | TERMINAL_WRAPPER_RECOVERY
attempt_plane_set_binding: AttemptPlaneSetBindingV2
decision_payload_binding: ArtifactBindingV2
decision_wrapper_binding: ArtifactBindingV2
attempt_id
grant_nonce
scope_binding: RoleScopeBindingV2
operation_not_after + settlement_not_after
outbox_deadline_set: OutboxDeadlineSetV3
initial_revocation_head_payload_binding: ArtifactBindingV2
initial_revocation_head_wrapper_binding: ArtifactBindingV2
initial_revocation_ordinal=0
initial_revocation_token
issued_at + frozen_at
publisher_actor_id + publisher_physical_sha
signature_domain = WS-WALKSAFE-R007-ROLE-GRANT-PAYLOAD-V2
```

The detached grant signature uses the exact common signature fields from the
request/response pairs and signs domain, NUL and full grant payload JCS.
Payload publishers are `CLOSE_RECOVERY_GRANT_SERIALIZER`,
`FINALIZATION_GRANT_SERIALIZER` and `WRAPPER_GRANT_SERIALIZER`; signatures are
published by their corresponding `_SIGNER` actors, each with a concrete
publisher Physical SHA.

`ExecutionWatchdogPayloadV2` exact fields:

```text
schema
attempt_plane_set_binding: AttemptPlaneSetBindingV2
decision_payload_binding: ArtifactBindingV2
decision_wrapper_binding: ArtifactBindingV2
attempt_id
execution_role_scope_binding: RoleScopeBindingV2
heartbeat_register_id
heartbeat_schema_sha
heartbeat_timeout_ns
process_probe_schema_sha
watchdog_nonce
operation_not_after
outbox_deadline_set: OutboxDeadlineSetV3
issued_at + frozen_at
publisher_actor_id + publisher_physical_sha
signature_domain = WS-WALKSAFE-R007-EXECUTION-WATCHDOG-PAYLOAD-V2
```

Its detached signature uses the common fields and the watchdog domain.
Publishers are `EXECUTION_WATCHDOG_SERIALIZER` and
`EXECUTION_WATCHDOG_SIGNER`. All grant/watchdog payload and signature
`FilePhysicalV2` values are captured by `ArtifactBindingV2`.

For every grant:

```text
decision_issued_at <= issued_at <= frozen_at < activation CAS linearized_at
activation CAS linearized_at < operation_not_after
operation_not_after < settlement_not_after <= outbox_deadline_set.TERMINAL.first_claim_not_after
outbox_deadline_set is byte-equal to the decision deadline set
grant attempt_id = decision attempt_id = aggregate attempt_id
grant nonce is unique across all three grants and watchdog nonce
scope_binding.decided_scope_digest = decision decided_scope_digest
scope_binding.subset_result = PASS
initial head SHA/Physical/token = current head at activation
```

Any inequality, nonce reuse, scope row mismatch, missing subset witness,
revocation-head drift or publisher/Physical mismatch makes activation
cardinality zero.

`ActivatedRoleContextV2` is legal only in artifacts whose predecessor signed
activation receipt already exists:

```text
schema = ACTIVATED_ROLE_CONTEXT_V2
activation_input_digest
activation_receipt_sha
activation_receipt_physical: FilePhysicalV2
attempt_plane_set_binding: AttemptPlaneSetBindingV2
aggregate_key_digest
aggregate_expected_token
attempt_state_expected = OPEN
role_id
role_expected_token
role_scope_binding: RoleScopeBindingV2
current_revocation_head_payload_sha
current_revocation_head_wrapper_sha
current_revocation_head_payload_physical
current_revocation_head_wrapper_physical
current_revocation_token
outbox_deadline_set: OutboxDeadlineSetV3
role_initial_binding: ArtifactBindingV2
role_grant_binding:
  {"kind":"EXECUTION_DECISION","decision_payload":ArtifactBindingV2,
   "decision_wrapper":ArtifactBindingV2}
  XOR
  {"kind":"NAMED_GRANT","grant":RoleGrantBindingV2}
```

Request, response, decision, watchdog, grants and activation input may not use
`ActivatedRoleContextV2`. Nonexecution uses `DecisionBoundContextV2` with
`attempt_id_binding=NOT_APPLICABLE`; it never writes a fake `NA` SHA field.
Across every schema, own payload/wrapper/receipt SHA field count is `0`.

Every execution, close-recovery, finalization and wrapper
consume/pre-revoke/pre-expire intent also has the required direct field
`attempt_plane_set_binding: AttemptPlaneSetBindingV2`. At each construction
and CAS, its full JCS bytes must equal the binding in
`FrozenNamespaceContextV2`, the applicable grant/watchdog, the activated role
context and the transition core. Comparing only count/digest scalars is
invalid.

### 4.5 ALLOW signed exact-four same-store activation

Activation path and publisher:

```text
AUTH_ROOT_TEMPLATE/activation/authority-aggregate-activation.receipt.json
schema = AUTHORITY_AGGREGATE_ACTIVATION_RECEIPT_V2
publisher = AUTHORITY_AGGREGATE_CAS_SERVICE
physical_kind = FILE
signature_domain =
  WS-WALKSAFE-R007-AUTHORITY-AGGREGATE-ACTIVATION-RECEIPT-V2
```

The one-file envelope has `body` and `service_signature`. `service_signature`
has exact fields
`algorithm,signer_actor_id,signer_physical_sha,signature_domain,signature`.
Signature input is
the literal domain above, NUL, and `RFC8785_JCS(body)`; signature is not a body
field. Body exact fields:

```text
schema
lifecycle_key_digest
lifecycle_pre_token
lifecycle_post_token
aggregate_key_digest
aggregate_precondition = ABSENT
aggregate_post_token
attempt_id
attempt_state_pre = ABSENT
attempt_state_post = OPEN
activation_input_context: ActivationInputContextV2
decision_payload_sha + decision_wrapper_sha
watchdog_payload_sha + watchdog_wrapper_sha
ordered_grant_refs[3]:
  role_id,payload_sha,wrapper_sha,payload_physical,wrapper_physical,
  published_at,frozen_at,grant_nonce,read_digest,write_digest,
  union_digest,write_subset_digest,scope_binding,
  initial_revocation_head_payload_sha,initial_revocation_head_wrapper_sha,
  initial_revocation_head_payload_physical,
  initial_revocation_head_wrapper_physical,initial_revocation_token
ordered_initial_role_rows[4]:
  ordinal,role_id,state=UNSPENT_UNREVOKED,role_token,
  granting_payload_sha,granting_wrapper_sha,
  granting_payload_physical,granting_wrapper_physical,
  role_scope_binding,
  current_revocation_head_payload_sha,current_revocation_head_wrapper_sha,
  current_revocation_head_payload_physical,
  current_revocation_head_wrapper_physical,current_revocation_token,
  operation_not_after,settlement_not_after,
  ordered_obligation_ids=[]
outbox_deadline_set: OutboxDeadlineSetV3
cas_linearized_at
cas_service_actor_id
cas_service_physical_sha
aggregate_state_record_physical: CasRecordPhysicalV2
receipt_publisher_actor_id
receipt_publisher_physical_sha
```

All four initial states are `UNSPENT_UNREVOKED`; `NOT_NEEDED` is forbidden at
activation. Exact atomic effects:

```text
lifecycle ALLOW_DECIDED → ACTIVATION_PENDING
aggregate ABSENT → present
attempt ABSENT → OPEN
four role rows created = 4
aggregate token created = 1
role tokens created = 4 distinct
project activation receipt obligation created = 1
activation receipt immutable batch/state row created = 1/1
activation timer registration copied from signed ALLOW context = 1
```

The activation CAS atomically commits `ActivationReceiptPublicationV3` with
the deterministic envelope bytes/content SHA, literal path, publisher
Physical, idempotency key, `WORK` deadline profile, initial state `PENDING`,
batch token and signed timer registration. Its dedicated state machine uses
the same exact five states and transition meanings as §4.8. A claim creates a
lease only in mutable claim evidence; create-exclusive, file fsync, parent
fsync, nofollow reopen and exact-byte/Physical adoption lead to `PUBLISHED`,
then a CAS-service attestation leads to `ATTESTED`. At the WORK deadline the
timer first reopens the expected path: exact bytes are adopted and attested;
absence becomes `EXPIRED`, atomically changes every initial role `U→N`, and
queues the one post-ALLOW seal outbox. Collision bytes take the same signed
fail-closed path and are never overwritten. Exact receipt attestation instead
changes lifecycle `ACTIVATION_PENDING→AUTHORITY_ACTIVE` in that same CAS.
Thus a crash after activation CAS or after rename resumes the same immutable
batch and cannot strand an active aggregate.

Every later intent binds the activation project receipt SHA/FilePhysical,
its publication attestation SHA/CasRecordPhysical, aggregate post token and
its role token; it is ineligible until activation publication state is
`ATTESTED`. Missing/late grant, wrong attempt/nonce/wrapper, wrong
published/frozen time, wrong scope/subset/head/plane or activation Physical
causes consume/dispatch/lease/downstream project writes `0`.

Mandatory direct edges:

```text
AG001 FINALIZATION-GRANT-WRAPPER → EXECUTION-CONSUME-INTENT
AG002 TERMINAL-WRAPPER-GRANT-WRAPPER → FINALIZATION-CONSUME-INTENT
AG003 WATCHDOG-WRAPPER → EXECUTION-CONSUME-INTENT
AG004 CLOSE-RECOVERY-GRANT-WRAPPER → EXECUTION-CONSUME-INTENT
AG005 DECISION-WRAPPER → AUTHORITY-ACTIVATION
AG006 WATCHDOG-WRAPPER → AUTHORITY-ACTIVATION
AG007 CLOSE-RECOVERY-GRANT-WRAPPER → AUTHORITY-ACTIVATION
AG008 FINALIZATION-GRANT-WRAPPER → AUTHORITY-ACTIVATION
AG009 TERMINAL-WRAPPER-GRANT-WRAPPER → AUTHORITY-ACTIVATION
```

Activation fan-out is expanded as one edge from activation to every literal
consume/revoke/expire/crash/selector/finalization/wrapper/timeout intent in
`EdgeInstanceRegistryV2`; aggregate shorthand edges are forbidden.

### 4.6 role/attempt total FSM, contender selector와 finite timeout

Role state closed enum:

```text
U = UNSPENT_UNREVOKED
P = PRE_CLOSE_BLOCK_PENDING
C = CONSUMED_OPEN
O = OUTCOME_SELECTED
S = SETTLED
N = NOT_NEEDED
```

Attempt state closed enum:

```text
OPEN
TERMINAL_SELECTED
TERMINAL_SETTLED
SEAL_QUEUED
SEALED
SEAL_FAILED_CLOSED
```

Every CAS compares and writes the attempt state/token plus all four role
states/tokens. The vector order is always
`[EXECUTION,CLOSE_RECOVERY,POST_CLOSE_FINALIZATION,
TERMINAL_WRAPPER_RECOVERY]`. Bound variables are:

```text
r ∈ {U,S} before execution selection; q,n ∈ {S,N} after it
f ∈ {U,S}; w ∈ {U,P}
e ∈ {U,C}; x,y,z ∈ {U,C,O,S,N}
terminalize(z) = S when z=S, otherwise N
```

They are copied/evaluated byte-exact in the CAS. The table includes the attempt
pre-state; there is no inferred transition.

| ID | event/guard | attempt pre | all-four pre | all-four post | durable attempt post / effect |
|---|---|---|---|---|---|
| `FSM001` | ALLOW activation | absent | absent | `[U,U,U,U]` | `OPEN`; signed activation |
| `FSM002` | execution consume before deadline | `OPEN` | `[U,r,f,w]` | `[C,r,f,w]` | `OPEN`; dispatch obligation |
| `FSM003` | execution pre-consume revoke/expiry | `OPEN` | `[U,r,f,w]` | `[O,terminalize(r),terminalize(f),N]` | `TERMINAL_SELECTED`; abandoned terminal batch |
| `FSM004` | execution abandoned terminal settled | `TERMINAL_SELECTED` | `[O,q,n,N]` | `[S,q,n,N]` | atomic `TERMINAL_SETTLED→SEAL_QUEUED`; settlement + seal obligation |
| `FSM005` | close-recovery pre-consume revoke/expiry | `OPEN` | `[e,U,f,w]` | `[e,O,f,w]` | `OPEN`; unavailable disposition batch |
| `FSM006` | close-recovery disposition settled | `OPEN` | `[e,O,f,w]` | `[e,S,f,w]` | `OPEN`; original close consume remains 0 |
| `FSM007` | finalization pre-consume revoke/expiry | `OPEN` | `[x,y,U,w]` | `[x,y,O,w]` | `OPEN`; unavailable disposition batch |
| `FSM008` | finalization disposition settled | `OPEN` | `[x,y,O,w]` | `[x,y,S,w]` | `OPEN`; original finalization consume remains 0 |
| `FSM009` | wrapper pre-close revoke/expiry/crash | `OPEN` | `[x,y,z,U]` | `[x,y,z,P]` | `OPEN`; store pending cause, project writes 0 |
| `FSM010` | NORMAL execution selector, recovery unused | `OPEN` | `[C,U,f,w]` | `[O,N,f,w]` | `OPEN`; normal execution-terminal batch |
| `FSM011` | NORMAL execution selector, recovery unavailable | `OPEN` | `[C,S,f,w]` | `[O,S,f,w]` | `OPEN`; normal execution-terminal batch |
| `FSM012` | adverse selector, recovery available | `OPEN` | `[C,U,f,w]` | `[O,C,f,w]` | `OPEN`; recovery consume + execution-terminal batch |
| `FSM013` | adverse selector, recovery unavailable | `OPEN` | `[C,S,f,w]` | `[O,S,f,w]` | `OPEN`; fail-closed execution-terminal batch |
| `FSM014` | normal terminal settled | `OPEN` | `[O,N,f,w]` | `[S,N,f,w]` | `OPEN`; finalization eligibility evaluated |
| `FSM015` | recovered terminal settled | `OPEN` | `[O,C,f,w]` | `[S,S,f,w]` | `OPEN`; close role settles with E |
| `FSM016` | unavailable terminal settled | `OPEN` | `[O,S,f,w]` | `[S,S,f,w]` | `OPEN`; finalization eligibility evaluated |
| `FSM017` | finalization already unavailable | `OPEN` | `[S,q,S,w]` | `[S,q,S,N]` | `TERMINAL_SELECTED`; finalization-unavailable terminal batch |
| `FSM018` | finalization-unavailable terminal settled | `TERMINAL_SELECTED` | `[S,q,S,N]` | unchanged | atomic `TERMINAL_SETTLED→SEAL_QUEUED`; settlement + seal obligation |
| `FSM019` | finalization consume, no pending wrapper cause | `OPEN` | `[S,q,U,U]` | `[S,q,C,U]` | `OPEN`; work batch + resumable tail obligation |
| `FSM019P` | finalization consume with preexisting pending wrapper cause | `OPEN` | `[S,q,U,P]` | `[S,q,O,P]` via recorded intermediate `[S,q,C,P]` | `OPEN`; consume + tail obligation + deterministic partial-close batch in one CAS |
| `FSM020` | normal finalization success/failure, wrapper unused | `OPEN` | `[S,q,C,U]` | `[S,q,O,N]` | `TERMINAL_SELECTED`; functional terminal batch |
| `FSM021` | normal finalization terminal settled | `TERMINAL_SELECTED` | `[S,q,O,N]` | `[S,q,S,N]` | atomic `TERMINAL_SETTLED→SEAL_QUEUED`; settlement + seal obligation |
| `FSM022` | post-consume CRASH/REVOCATION/EXPIRY/SETTLEMENT_TIMEOUT | `OPEN` | `[S,q,C,w]`, `w∈{U,P}` | `[S,q,O,P]` | `OPEN`; fallback partial-close batch and exact signed adverse tuple stored atomically |
| `FSM023` | fallback partial close settled | `OPEN` | `[S,q,O,P]` | `[S,q,S,P]` | `OPEN`; stored adverse tuple makes wrapper disposition eligible |
| `FSM024` | wrapper publication selected | `OPEN` | `[S,q,S,U]` | `[S,q,S,O]` | `TERMINAL_SELECTED`; wrapper terminal batch |
| `FSM025` | pending adverse cause selected | `OPEN` | `[S,q,S,P]` | `[S,q,S,O]` | `TERMINAL_SELECTED`; exact disposition pair batch |
| `FSM026` | wrapper/disposition terminal settled | `TERMINAL_SELECTED` | `[S,q,S,O]` | `[S,q,S,S]` | atomic `TERMINAL_SETTLED→SEAL_QUEUED`; settlement + seal obligation |
| `FSM027` | original wrapper/disposition outbox resume | `TERMINAL_SELECTED` or `SEAL_QUEUED` | unchanged | unchanged | resume only the same aggregate/attempt/transition/batch identity; selection, consume, functional write, obligation, outbox and seal creation all `0` |
| `FSM028` | WORK batch reaches its attestation deadline | `OPEN` | any reachable vector with eligible unsettled WORK obligation | every role `terminalize(state)` | `TERMINAL_SELECTED`; old lease invalidated, same WORK batch `EXPIRED`, fail-closed terminal batch selected |
| `FSM029` | timeout terminal attested | `TERMINAL_SELECTED` | every role is `S` or `N` | unchanged | atomic `TERMINAL_SETTLED→SEAL_QUEUED`; attestation + seal obligation |
| `FSM030` | seal project file attested | `SEAL_QUEUED` | every role is `S` or `N` | unchanged | `SEALED`; store-internal final CAS |
| `FSM031` | selected terminal batch first claim times out | `TERMINAL_SELECTED` | unchanged | unchanged | same output identity; invalidate stale lease and return same batch to `PENDING` |
| `FSM032` | selected terminal batch reaches `TERMINAL.publish_not_after` | `TERMINAL_SELECTED` | unchanged | unchanged | exact-path reconciliation first; absent output changes the same batch to `EXPIRED` and continues as `FSM032F` in the same CAS |
| `FSM032F` | `FSM032` absent-output fail-close continuation | `TERMINAL_SELECTED` | any reachable vector | every role `terminalize(state)` | attempt atomically records `TERMINAL_SETTLED→SEAL_QUEUED`, same-identity `FAILED_CLOSED` certificate and new SEAL-profile obligation; terminal output write 0 |
| `FSM033` | SEAL batch reaches `SEAL.publish_not_after` without exact output | `SEAL_QUEUED` | every role is `S` or `N` | unchanged | seal batch `EXPIRED`; attempt `SEAL_FAILED_CLOSED`; signed no-output/collision evidence; project write 0 |
| `FSM034` | any event not matched above | any | any | unchanged | unchanged; effect/receipt/lease/write `0` |

For the four settlement rows that queue a seal, one CAS records
`attempt_pre_state=TERMINAL_SELECTED`,
`attempt_intermediate_state=TERMINAL_SETTLED` and
`attempt_post_state=SEAL_QUEUED`; it also commits the terminal settlement
attestation and exact `ATTEMPT_SEAL` obligation. Thus `SEAL_QUEUED` is durable,
not an unused enum. `FSM028` atomically changes every prior unsettled WORK batch to
`EXPIRED`, invalidates its publication lease and freezes its output identities
before creating the timeout terminal identity; no old batch can later publish.
For `FSM022`, if wrapper state is `U`, the same CAS stores
`PRE_CLOSE_BLOCK_PENDING` plus the complete signed source binding, cause,
effective time, priority and deterministic tie key; if it is already `P`, the
CAS requires byte equality with the existing stored tuple. Normal
`FSM020 U→N` and adverse `FSM022 U→P→FSM025 O` contend on the same role token,
so they are mutually exclusive. Each of `CRASH`, `REVOCATION`, `EXPIRY` and
`SETTLEMENT_TIMEOUT` has exactly one eventual disposition selection and seal;
a losing or different tuple has effect/receipt/write `0`.
For an identity already selected, `FSM031` never selects a replacement.
`TERMINAL.first_claim_not_after < TERMINAL.publish_not_after <
TERMINAL.reconcile_not_after <= SEAL.first_claim_not_after`; recovery claims
reuse the same output bytes,
idempotency key and batch token lineage. A signed repair CAS may return that
same batch to `PENDING` only before `TERMINAL.publish_not_after`. If no repair
settles it, one CAS with
`TERMINAL.publish_not_after <= trusted linearized_at <
SEAL.reconcile_not_after` performs `FSM032` and `FSM032F` as recorded
intermediate steps, freezes the same transition/core/output identity,
invalidates every lease, marks every role as `S` or `N`, commits exactly one
predecessor-only `TerminalSettlementCoreV2` with
`settlement_kind=FAILED_CLOSED`, and queues the seal. It creates no
replacement terminal selection and no functional project output.
Thus no terminal-selected attempt hangs in an unbounded or undefined state,
and no second terminal identity appears.
No seal is legal unless every role is `S` or `N`.

Nonexecution lifecycle has zero authority rows:

| lifecycle event | authority rows | terminal variant | data writes | next |
|---|---:|---|---:|---|
| DENY decision | 0 | `DENIED` | 0 | seal outbox |
| request deadline | 0 | `REQUEST_EXPIRED` | 0 | seal outbox |
| explicit pre-ALLOW abandon | 0 | `ABANDONED` | 0 | seal outbox |

Preconsume revoke/expiry never enters the execution cause selector. It uses
`FSM003/FSM005/FSM007` disposition and direct terminal/seal chains.
`REVOCATION` selector eligibility is:

```text
original_execution_consume_transition_id exists
and EXECUTION state ∈ {C,O}
and signed post-consume revocation source exists
```

The execution cause candidate schema is:

```text
cause: REVOCATION | EXPIRY | CRASH | NORMAL
source_payload_sha
source_wrapper_or_receipt_sha
source_payload_physical
source_wrapper_or_receipt_physical
effective_at
observed_at
priority: 0 | 1 | 2 | 3
```

`CauseEnumV3` is the closed ordered enum
`REVOCATION,EXPIRY,CRASH,SETTLEMENT_TIMEOUT,NORMAL`. Its tagged subsets are:

| selector kind | exact allowed causes | priority order |
|---|---|---|
| `EXECUTION_POST_CONSUME` | `REVOCATION`, `EXPIRY`, `CRASH`, `NORMAL` | `REVOCATION<EXPIRY<CRASH<NORMAL` |
| `ROLE_PRECONSUME` | `REVOCATION`, `EXPIRY` | `REVOCATION<EXPIRY` |
| `WRAPPER_PRECLOSE` | `REVOCATION`, `EXPIRY`, `CRASH` | `REVOCATION<EXPIRY<CRASH` |
| `FINALIZATION_POSTCONSUME` | `REVOCATION`, `EXPIRY`, `CRASH`, `SETTLEMENT_TIMEOUT` | `REVOCATION<EXPIRY<CRASH<SETTLEMENT_TIMEOUT` |
| `OUTBOX_TIMEOUT` | `SETTLEMENT_TIMEOUT` | singleton |

The enum ordinal is never used before `effective_at`: selection minimizes
`(effective_at,subset_priority,source_payload_sha)`. A cause outside its tagged
subset is schema-invalid rather than a low-priority candidate.

`AdverseSourceInstanceRegistryV3` is the following exact 12-row closed set;
each suffix expands under `AUTH_ROOT_TEMPLATE/events/` to a payload path
`suffix.payload.json` and signature path `suffix.signature.json`.

| ord | role | phase | cause | suffix | payload publisher / signer |
|---:|---|---|---|---|---|
| 1 | `EXECUTION` | `PRECONSUME` | `REVOCATION` | `execution-preconsume-revocation` | `AUTHORITY_REVOCATION_SERIALIZER` / `AUTHORITY_REVOCATION_SIGNER` |
| 2 | `EXECUTION` | `PRECONSUME` | `EXPIRY` | `execution-preconsume-expiry` | `AUTHORITY_EXPIRY_SERIALIZER` / `AUTHORITY_EXPIRY_SIGNER` |
| 3 | `CLOSE_RECOVERY` | `PRECONSUME` | `REVOCATION` | `close-recovery-preconsume-revocation` | `AUTHORITY_REVOCATION_SERIALIZER` / `AUTHORITY_REVOCATION_SIGNER` |
| 4 | `CLOSE_RECOVERY` | `PRECONSUME` | `EXPIRY` | `close-recovery-preconsume-expiry` | `AUTHORITY_EXPIRY_SERIALIZER` / `AUTHORITY_EXPIRY_SIGNER` |
| 5 | `POST_CLOSE_FINALIZATION` | `PRECONSUME` | `REVOCATION` | `finalization-preconsume-revocation` | `AUTHORITY_REVOCATION_SERIALIZER` / `AUTHORITY_REVOCATION_SIGNER` |
| 6 | `POST_CLOSE_FINALIZATION` | `PRECONSUME` | `EXPIRY` | `finalization-preconsume-expiry` | `AUTHORITY_EXPIRY_SERIALIZER` / `AUTHORITY_EXPIRY_SIGNER` |
| 7 | `TERMINAL_WRAPPER_RECOVERY` | `PRECLOSE` | `REVOCATION` | `wrapper-preclose-revocation` | `AUTHORITY_REVOCATION_SERIALIZER` / `AUTHORITY_REVOCATION_SIGNER` |
| 8 | `TERMINAL_WRAPPER_RECOVERY` | `PRECLOSE` | `EXPIRY` | `wrapper-preclose-expiry` | `AUTHORITY_EXPIRY_SERIALIZER` / `AUTHORITY_EXPIRY_SIGNER` |
| 9 | `TERMINAL_WRAPPER_RECOVERY` | `PRECLOSE` | `CRASH` | `wrapper-preclose-crash` | `ROLE_WATCHDOG_OBSERVER` / `ROLE_WATCHDOG_SIGNER` |
| 10 | `POST_CLOSE_FINALIZATION` | `POSTCONSUME` | `REVOCATION` | `finalization-postconsume-revocation` | `AUTHORITY_REVOCATION_SERIALIZER` / `AUTHORITY_REVOCATION_SIGNER` |
| 11 | `POST_CLOSE_FINALIZATION` | `POSTCONSUME` | `EXPIRY` | `finalization-postconsume-expiry` | `AUTHORITY_EXPIRY_SERIALIZER` / `AUTHORITY_EXPIRY_SIGNER` |
| 12 | `POST_CLOSE_FINALIZATION` | `POSTCONSUME` | `CRASH` | `finalization-postconsume-crash` | `ROLE_WATCHDOG_OBSERVER` / `ROLE_WATCHDOG_SIGNER` |

Every row expands to distinct payload/signature RoleInstance and Node rows.
`AdverseEventPayloadV3` has exact fields
`schema,aggregate_key_digest,attempt_id,activation_attestation_binding,
role_id,phase,cause,grant_or_decision_binding,role_state,role_token,
current_revocation_head_binding,consume_binding:{NOT_CONSUMED|CONSUMED with
receipt binding},outbox_deadline_set,effective_at,observed_at,
target_fsm_event,publisher_actor_id,publisher_physical_sha,signature_domain`.
The signature uses its row-specific domain
`WS-WALKSAFE-R007-ADVERSE-{ROLE}-{PHASE}-{CAUSE}-V3`, NUL and payload JCS.
For row ordinal `n`, five literal direct edges have contiguous IDs
`ASR(5n-4)` through `ASR(5n)`: activation attestation→payload,
grant/current-head-or-consume authority→payload, cause authority→payload,
payload→signature, signature→the named FSM intent/core. The generator expands
all 60 tuples and the registry rejects a range token at runtime. A separate
three-edge `AST061..AST063` timer-registration→timer-firing→original-batch
timeout core is the sole `SETTLEMENT_TIMEOUT` source.

Priority is exactly
`REVOCATION=0 < EXPIRY=1 < CRASH=2 < NORMAL=3`. Selector chooses minimum
`(effective_at,priority,source_payload_sha)` after eligibility validation.
Different effective time always wins before priority; priority is only the
tie-break. Same tuple with different source bytes is invalid.

All four contenders have a closed, authenticated source. CRASH is expanded in
§4.7; the other three exact pairs are:

| cause | payload path template | signature path template | payload schema | publisher / signer |
|---|---|---|---|---|
| `REVOCATION` | `AUTH_ROOT_TEMPLATE/events/execution-post-consume-revocation.payload.json` | `AUTH_ROOT_TEMPLATE/events/execution-post-consume-revocation.signature.json` | `EXECUTION_POST_CONSUME_REVOCATION_PAYLOAD_V2` | `AUTHORITY_REVOCATION_SERIALIZER` / `AUTHORITY_REVOCATION_SIGNER` |
| `EXPIRY` | `AUTH_ROOT_TEMPLATE/events/execution-expiry-observation.payload.json` | `AUTH_ROOT_TEMPLATE/events/execution-expiry-observation.signature.json` | `EXECUTION_EXPIRY_OBSERVATION_PAYLOAD_V2` | `AUTHORITY_EXPIRY_SERIALIZER` / `AUTHORITY_EXPIRY_SIGNER` |
| `NORMAL` | `AUTH_ROOT_TEMPLATE/events/execution-normal-completion.payload.json` | `AUTH_ROOT_TEMPLATE/events/execution-normal-completion.signature.json` | `EXECUTION_NORMAL_COMPLETION_PAYLOAD_V2` | `EXECUTION_RESULT_SERIALIZER` / `EXECUTION_RESULT_SIGNER` |

Each payload has common exact fields:

```text
schema,cause
activated_execution_context: ActivatedRoleContextV2
execution_consume_transition_id
execution_consume_receipt_sha + execution_consume_receipt_physical
dispatch_transition_id + dispatch_receipt_sha + dispatch_receipt_physical
aggregate_current_token
execution_role_current_token
current_revocation_head_sha + current_revocation_head_physical
effective_at + observed_at
publisher_actor_id + publisher_physical_sha
signature_domain
```

`REVOCATION` additionally requires
`revocation_payload_sha,revocation_wrapper_sha,both Physical,
revocation_ordinal,revocation_token` and proves
`execution_consume.linearized_at < effective_at`; without the original
post-consume receipt it is ineligible. `EXPIRY` additionally carries
`operation_not_after,expiry_timer_id,timer_service_physical_sha` and requires
`effective_at=operation_not_after` plus selector CAS time at or after it.
`NORMAL` additionally carries
`execution_result_sha,result_physical,result_status=PASS,
normal_completion_transition_id` and requires its effective time from the
trusted completion CAS.

Detached signatures use the common closed signature fields, concrete signer
Physical and the respective exact domains
`WS-WALKSAFE-R007-EXECUTION-POST-CONSUME-REVOCATION-V2`,
`WS-WALKSAFE-R007-EXECUTION-EXPIRY-OBSERVATION-V2` and
`WS-WALKSAFE-R007-EXECUTION-NORMAL-COMPLETION-V2`. Signing input is domain,
NUL and full payload JCS. The selector recomputes every specific predicate,
then requires payload/signature SHA/Physical and dispatch/current
head/current aggregate/current role-token equality.

Every authority CAS uses the CAS service's trusted `linearized_at`; client
`event_at` is not a guard. Consume/NORMAL require
`linearized_at < operation_not_after`. Expiry becomes eligible at
`operation_not_after <= linearized_at`. A committed work/terminal obligation
may publish while `linearized_at < settlement_not_after`.

At `settlement_not_after`, the pre-registered CAS timer deterministically
selects `SETTLEMENT_TIMEOUT` if a required batch is not `PUBLISHED`. That
transaction must linearize before `attempt_seal_not_after`, writes a
fail-closed terminal batch and queues its seal path. The selected output
identity remains publishable after the selection deadline because it was
committed before `attempt_seal_not_after`; no new output identity may be
created. Thus timeout has a finite logical winner even if a publisher is
restarted. Next namespace still waits for the actual seal file and recheck.

After attempt state `TERMINAL_SELECTED`, new consume/revoke/expire/normal
selection effects are `NONE`; token/receipt/lease/project write count is `0`.
Only already committed terminal batch settlement, atomic seal enqueue and
seal settlement remain legal.

### 4.7 signed CRASH payload/signature pair and direct lineage

Exact templates:

```text
AUTH_ROOT_TEMPLATE/events/execution-crash-observation.payload.json
AUTH_ROOT_TEMPLATE/events/execution-crash-observation.signature.json
```

Roles:

| role | schema | publisher | Physical |
|---|---|---|---|
| `EXECUTION-CRASH-PAYLOAD` | `EXECUTION_CRASH_OBSERVATION_PAYLOAD_V2` | `EXECUTION_WATCHDOG_OBSERVER` | payload publisher `FilePhysicalV2` |
| `EXECUTION-CRASH-SIGNATURE` | `EXECUTION_CRASH_OBSERVATION_DETACHED_SIGNATURE_V2` | `EXECUTION_WATCHDOG_SIGNER` | signer `FilePhysicalV2` |

Payload exact fields:

```text
schema
activated_execution_context: ActivatedRoleContextV2
dispatch_transition_id
dispatch_receipt_sha + dispatch_receipt_physical
dispatch_lease_id
process_instance_id
process_start_nonce
watchdog_payload_sha + watchdog_wrapper_sha
watchdog_payload_physical + watchdog_wrapper_physical
heartbeat_register_id
heartbeat_head_sha
heartbeat_head_physical
heartbeat_store_token
heartbeat_sequence
last_heartbeat_at
heartbeat_timeout_ns: Ordinal
lost_process_effective_at
process_probe_result = ABSENT
process_probe_receipt_sha + process_probe_receipt_physical
observed_at
trusted_clock_source_id
trusted_clock_source_physical_sha
trusted_clock_correlation_sha
publisher_actor_id
publisher_physical_sha
signature_domain =
  WS-WALKSAFE-R007-EXECUTION-CRASH-OBSERVATION-PAYLOAD-V2
```

Lost-process predicate:

```text
lost_process_effective_at =
  UTC_NANO_ADD(last_heartbeat_at, heartbeat_timeout_ns)
observed_at >= lost_process_effective_at
lost_process_effective_at > last_heartbeat_at
process_probe_result = ABSENT
dispatch lease/process/start nonce =
  current authoritative dispatch row values
heartbeat head/token/sequence =
  current heartbeat register values at selector CAS linearization
watchdog payload/wrapper =
  activation input watchdog values
```

Signature wrapper exact fields are
`schema,payload_path,payload_schema_sha,payload_sha,payload_physical,
signer_actor_id,signer_physical_sha,signature_algorithm,
signature_domain,signature`. Signing input:

```text
ASCII("WS-WALKSAFE-R007-EXECUTION-CRASH-OBSERVATION-PAYLOAD-V2")
|| 0x00 || RFC8785_JCS(full payload)
```

The close-recovery grant read scope contains these exact four static rows
before activation:

| ord | role ID | literal path template | schema role | publisher |
|---:|---|---|---|---|
| 0 | `EXECUTION-WATCHDOG-PAYLOAD` | `AUTH_ROOT_TEMPLATE/watchdog/execution-watchdog.payload.json` | `EXECUTION_WATCHDOG_PAYLOAD_V2` | `EXECUTION_WATCHDOG_SERIALIZER` + exact Physical |
| 1 | `EXECUTION-WATCHDOG-SIGNATURE` | `AUTH_ROOT_TEMPLATE/watchdog/execution-watchdog.signature.json` | `EXECUTION_WATCHDOG_DETACHED_SIGNATURE_V2` | `EXECUTION_WATCHDOG_SIGNER` + exact Physical |
| 2 | `EXECUTION-CRASH-PAYLOAD` | `AUTH_ROOT_TEMPLATE/events/execution-crash-observation.payload.json` | `EXECUTION_CRASH_OBSERVATION_PAYLOAD_V2` | `EXECUTION_WATCHDOG_OBSERVER` + exact Physical |
| 3 | `EXECUTION-CRASH-SIGNATURE` | `AUTH_ROOT_TEMPLATE/events/execution-crash-observation.signature.json` | `EXECUTION_CRASH_OBSERVATION_DETACHED_SIGNATURE_V2` | `EXECUTION_WATCHDOG_SIGNER` + exact Physical |

All rows have `access=READ`, full schema SHA, publisher actor/Physical,
`physical_kind=FILE`, exact literal path and branch predicate. The first two
materialize exactly once. The latter two share predicate
`VALID_CURRENT_EXECUTION_CRASH_PAIR` and materialize together: runtime pair
cardinality is `0` or `2`, and selected CRASH is exact `2`. Scope count/digest
is calculated over all four static rows before activation; runtime projection
count/digest is separately recomputed. Partial pair is invalid.

Literal direct edges:

```text
CR001 WATCHDOG-PAYLOAD → CRASH-PAYLOAD
CR002 WATCHDOG-SIGNATURE → CRASH-PAYLOAD
CR003 DISPATCH-RECEIPT → CRASH-PAYLOAD
CR004 HEARTBEAT-HEAD → CRASH-PAYLOAD
CR005 PROCESS-PROBE-RECEIPT → CRASH-PAYLOAD
CR006 CRASH-PAYLOAD → CRASH-SIGNATURE
CR007 CRASH-SIGNATURE → CLOSE-RECOVERY-CONSUME-INTENT
CR008 CLOSE-RECOVERY-CONSUME-RECEIPT → ET003
CR009 ET003 → EXECUTION-CAUSE-SELECTOR
CR010 EXECUTION-CAUSE-SELECTOR → SELECTED-EXECUTION-TERMINAL-BODY
CR011 SELECTED-EXECUTION-TERMINAL-BODY → EXECUTION-TERMINAL-OUTBOX
```

Selector CAS rechecks current heartbeat head/token/sequence and exact
dispatch/process/watchdog equality. Absent, forged, stale, wrong-process,
wrong-dispatch, wrong-Physical or untrusted-clock CRASH has materialization
and selection cardinality `0`.

### 4.8 predecessor-only transition core and transactional outbox

`TransitionCoreV2` is computed before the CAS and before any transition
receipt. It has no output content hash.

```text
schema = TRANSITION_CORE_V2
aggregate_key_digest
lifecycle_key_digest
activated_role_context_sha
phase_context_type
phase_context_sha
attempt_plane_set_binding: AttemptPlaneSetBindingV2
role_scope_binding: RoleScopeBindingV2
aggregate_pre_token + aggregate_post_token
current_revocation_head_payload_sha
current_revocation_head_wrapper_sha
current_revocation_head_payload_physical
current_revocation_head_wrapper_physical
current_revocation_token
transition_ordinal
prior_transition_id_or:
  {"kind":"GENESIS_NA"} XOR
  {"kind":"PRIOR","transition_id":Sha256Hex,"core_digest":Sha256Hex}
attempt_expected_state + attempt_expected_token
ordered_role_preconditions[4]:
  ordinal,role_id,expected_state,expected_role_token
event_kind:
  CONSUME | PRE_REVOKE | PRE_EXPIRE | CAUSE_SELECT |
  FINALIZATION_FALLBACK | WRAPPER_SELECT | SETTLEMENT_TIMEOUT |
  TERMINAL_SETTLE | SEAL_SETTLE
ordered_event_source_refs[]:
  role_id,literal_path,schema_sha,content_sha,physical_digest
selector_tuple_or:
  {"kind":"NOT_APPLICABLE"} XOR
  {"kind":"SELECTED","cause":CauseEnumV2,"effective_at":UtcNano,
   "priority":integer 0..3,"source_set_digest":Sha256Hex}
cas_predicate:
  trusted_clock_source_id,trusted_clock_correlation_sha,
  operation_not_after,settlement_not_after,
  outbox_deadline_set:OutboxDeadlineSetV3,
  required_time_relation
ordered_role_poststates[4]:
  ordinal,role_id,post_state,next_role_token
attempt_post_state + next_attempt_token
ordered_output_specs[]:
  entry_id,outbox_slot,role_id,literal_path,schema_role,schema_sha,
  publisher_actor_id,publisher_physical_sha,constructor_id,
  predecessor_set_digest
```

```text
core_digest =
  SHA256(
    ASCII("WS-WALKSAFE-R007-TRANSITION-CORE-V2") || 0x00 ||
    RFC8785_JCS(TransitionCoreV2)
  )
transition_id =
  SHA256(
    ASCII("WS-WALKSAFE-R007-TRANSITION-ID-V2") || 0x00 ||
    RFC8785_JCS({
      "aggregate_key_digest":aggregate_key_digest,
      "transition_ordinal":transition_ordinal,
      "core_digest":core_digest
    })
  )
```

Output bodies reference only `transition_id` and `core_digest`, never a future
transition receipt SHA. The deterministic output constructor then computes
each output byte sequence and content SHA.

`OutputEntryV2` exact fields:

```text
entry_ordinal
entry_id
outbox_slot
role_id
literal_path
schema_role
schema_sha
publisher_actor_id
publisher_physical_sha
constructor_id
transition_id
core_digest
content_sha
content_bytes
predecessor_set_digest
idempotency_key
```

`idempotency_key` is the SHA of domain
`WS-WALKSAFE-R007-PUBLISH-ONCE-V2`, NUL and JCS
`{aggregate_key_digest,outbox_slot,entry_id,literal_path,content_sha}`.

Outbox slots are ordered exact nine:

```text
0 EXECUTION_DISPATCH
1 EXECUTION_TERMINAL
2 EXECUTION_PRE_REVOKE
3 CLOSE_RECOVERY_PRE_REVOKE
4 FINALIZATION_PRE_REVOKE
5 FINALIZATION_WORK
6 FINALIZATION_TERMINAL
7 WRAPPER_TERMINAL
8 ATTEMPT_SEAL
```

`SettlementObligationV2`:

```text
schema
transition_id + core_digest
aggregate_key_digest
outbox_slot
branch_id
ordered_output_entries: array of OutputEntryV2
output_entry_count + output_entry_digest
operation_not_after + settlement_not_after
outbox_deadline_set: OutboxDeadlineSetV3
idempotency_set_digest
terminal_batch: true | false
```

`OutboxBatchV2`:

```text
schema
transition_id + core_digest
aggregate_key_digest + outbox_slot
obligation_digest
ordered_output_entries
output_entry_count + output_entry_digest
selected_terminal_variant_or_NA
event_set_digest
outbox_deadline_set: OutboxDeadlineSetV3
created_at
initial_batch_state = PENDING
initial_batch_token
```

`OutboxBatchStateV3` is the exact five-value enum
`PENDING|CLAIMED|PUBLISHED|ATTESTED|EXPIRED`, separate from role state and
terminal settlement kind. `PUBLISHING`, `FAILED`, `SETTLED` and
`FAILED_CLOSED` are forbidden outbox states. Publication failure is signed
evidence attached to a retry or a terminal receipt; role settlement is an
all-four CAS effect. The complete state/event relation is literal:

| transition ID | event | from | to | exact guard/effect |
|---|---|---|---|---|
| `OBX001` | `CLAIM_INITIAL` | `PENDING` | `CLAIMED` | trusted time before selected profile `first_claim_not_after`; create one lease with `claim_not_after=min(claimed_at+claim_lease_ns,publish_not_after)`; increment attempt count |
| `OBX002` | `CLAIM_RECOVERY` | `PENDING` | `CLAIMED` | trusted time from first-claim bound through strictly before `publish_not_after`; same immutable batch and output identities; create one lease |
| `OBX003` | `PUBLISH_AND_ATTEST` | `CLAIMED` | `ATTESTED` | active lease; `RENAME_NOREPLACE` or exact adoption; fsync, parent fsync and complete nofollow reopen; CAS records intermediate `PUBLISHED`, then `ATTESTED`, publication attestation and downstream role/attempt effect atomically |
| `OBX004` | `RECLAIM_STALE` | `CLAIMED` | `PENDING` | `claim_not_after <= trusted time < publish_not_after`; invalidate exact lease; immutable batch unchanged |
| `OBX005` | `CLAIM_FAILURE_RETRY` | `CLAIMED` | `PENDING` | active lease and signed failure evidence before `publish_not_after`; invalidate lease; immutable batch unchanged |
| `OBX006` | `DEADLINE_EXPIRE_PENDING` | `PENDING` | `EXPIRED` | at/after `publish_not_after`, signed nofollow inventory proves complete exact output set absent or collided; no new claim or project write |
| `OBX007` | `DEADLINE_EXPIRE_CLAIMED` | `CLAIMED` | `EXPIRED` | same proof after invalidating exact active lease; no overwrite |
| `OBX008` | `ATTEST_STORED_PUBLISHED` | `PUBLISHED` | `ATTESTED` | store-only migration/resume for a previously signed exact publication; commits attestation and downstream state effect, project writes `0` |
| `OBX009` | `RECONCILE_EXACT_AND_ATTEST` | `PENDING` or `CLAIMED` | `ATTESTED` | deadline worker finds the complete exact output set; invalidates any lease, records intermediate `PUBLISHED`, adopts and attests in one CAS; project writes `0` |

`ATTESTED` and `EXPIRED` are immutable. A durable `PUBLISHED` row may advance
only by `OBX008`; `PUBLISHED→EXPIRED` is forbidden. Every `(state,event)` pair
not listed above has effect/receipt/lease/write count `0`, so no implicit
wildcard exists. At `EXPIRED`, WORK invokes `FSM028`, TERMINAL invokes
`FSM032F`, and SEAL invokes `FSM033`. A WORK attestation keeps its owning role
`CONSUMED_OPEN`; only terminal attestation changes the owning role to
`SETTLED` and queues exactly one SEAL-profile obligation.

The mutable `OutboxBatchStateRecordV2` has exact fields:

```text
schema
transition_id + core_digest + obligation_digest + outbox_digest
aggregate_key_digest + outbox_slot
batch_state = PENDING | CLAIMED | PUBLISHED | ATTESTED | EXPIRED
batch_token
state_generation
claim_attempt_count
outbox_deadline_set: OutboxDeadlineSetV3
claim_binding:
  {"kind":"NO_CLAIM"}
  XOR
  {
    "kind":"ACTIVE_CLAIM",
    "publication_lease_id":Identifier,
    "publication_lease_token":Sha256Hex,
    "claimant_actor_id":Identifier,
    "claimant_physical_sha":Sha256Hex,
    "claimed_at":UtcNano,
    "claim_not_after":UtcNano
  }
ordered_output_identity_digest
last_publication_evidence_or:
  {"kind":"NO_PUBLICATION_EVIDENCE"}
  XOR {"kind":"PUBLICATION_EVIDENCE",
       "ordered_reopened_output_physical":array of FilePhysicalV2,
       "published_or_adopted_at":UtcNano}
last_failure_evidence_or:
  {"kind":"NO_FAILURE_EVIDENCE"}
  XOR {"kind":"FAILURE_EVIDENCE","code":Identifier,"observed_at":UtcNano,
       "signed_evidence_sha":Sha256Hex,
       "signed_evidence_physical":CasRecordPhysicalV2}
last_linearized_at
```

The CAS value does not contain its own `CasRecordPhysicalV2`. The CAS API
returns `(record bytes, CasRecordPhysicalV2)` externally; subsequent
transitions bind that pair. Claim, stale-claim recovery, failure retry and reconciliation all
compare the prior batch token, exact immutable outbox digest and full lease
tuple. The initial row has `state_generation=0,claim_attempt_count=0,
claim_binding=NO_CLAIM`; every listed event increments generation exactly
once. Retry cannot alter an output entry.

Every listed event returns an `OutboxBatchTransitionReceiptV2` signed envelope
whose body has exact fields:

```text
schema
transition_id + core_digest + obligation_digest + outbox_digest
aggregate_key_digest + outbox_slot
event_id + event_kind
pre_batch_state + post_batch_state
pre_batch_token + post_batch_token
pre_state_generation + post_state_generation
pre_claim_attempt_count + post_claim_attempt_count
pre_claim_binding + post_claim_binding
ordered_output_identity_digest
outbox_deadline_set: OutboxDeadlineSetV3
trusted_linearized_at
evaluated_time_relation:
  BEFORE_FIRST_CLAIM | RECOVERY_WINDOW |
  STALE_LEASE_IN_RECOVERY_WINDOW | DEADLINE_RECONCILIATION |
  STORE_ONLY_ATTESTATION
reconciliation_result:
  {"kind":"NOT_RECONCILIATION"}
  XOR {"kind":"EXACT_COMPLETE_SET","ordered_reopened_output_physical":
       array of FilePhysicalV2}
  XOR {"kind":"ABSENT_OR_COLLISION","signed_inventory_evidence_sha":Sha256Hex,
       "signed_inventory_evidence_physical":CasRecordPhysicalV2}
failure_or_retry_authority_binding
cas_service_actor_id + cas_service_physical_sha
```

Its service signature domain is
`WS-WALKSAFE-R007-OUTBOX-BATCH-TRANSITION-RECEIPT-V2`, with NUL and full body
JCS. The receipt omits its own SHA/record Physical; the CAS returns those
externally. All three times, trusted clock identity/correlation and seal
deadline must byte-equal the decision-frozen
`OutboxDeadlineSetV3` in the obligation, immutable batch and current
state row. Claim, reclaim, retry and deadline CAS evaluate the literal
strict inequalities above against `trusted_linearized_at`; a client timer,
different clock correlation or equality at an upper exclusive bound rejects.

The same CAS commits all-role state, `TransitionCoreV2`,
`SettlementObligationV2`, immutable `OutboxBatchV2` and initial
`OutboxBatchStateRecordV2` keyed by `transition_id`. A post-commit CAS-service
signed `TransitionReceiptV2` is a CAS-internal envelope whose body has exact
fields:

```text
schema
transition_id + core_digest
aggregate_key_digest + lifecycle_key_digest
phase_context_type + phase_context_sha
role_scope_binding: RoleScopeBindingV2
aggregate_pre_token + aggregate_post_token
ordered_role_pre_tokens[4] + ordered_role_post_tokens[4]
attempt_pre_token + attempt_post_token
obligation_digest + outbox_digest + initial_batch_token
linearized_at
cas_service_actor_id + cas_service_physical_sha
```

Its service-signature fields are
`algorithm,signer_actor_id,signer_physical_sha,signature_domain,signature`,
with domain `WS-WALKSAFE-R007-TRANSITION-RECEIPT-V2`, NUL and body JCS as
input. The envelope bytes do not contain their own SHA or
`CasRecordPhysicalV2`; the CAS return value supplies both externally.
Predecessors bind `transition_id/core_digest` and, when receipt evidence is
required, the already committed receipt SHA plus external
`CasRecordPhysicalV2`.

Publication is exact:

```text
claim PENDING with batch token
→ CLAIMED with one-use publication lease
→ create-exclusive staged sibling
→ file fsync
→ parent fsync
→ nofollow reopen
→ bytes/SHA/FilePhysical equality
→ RENAME_NOREPLACE or exact existing-file adoption
→ one CAS records PUBLISHED as an intermediate state
→ same CAS records ATTESTED, signed publication attestation and downstream effect
```

A stored exact `PUBLISHED` row is legal only as a migration/crash-resume input
and is closed by `ATTEST_STORED_PUBLISHED`; ordinary publication never exposes
that intermediate state to a later worker. After rename but before CAS, the
deadline callback inventories every expected path. Exact complete bytes use
`RECONCILE_EXACT_AND_ATTEST`; absent, collision or partial inventory uses
`EXPIRED`. The signed inventory always records what physically exists.

Before the terminal-settlement CAS, the service constructs
`TerminalSettlementCoreV2`:

```text
schema
selected_transition_id + selected_core_digest
selected_obligation_digest + selected_outbox_digest
outbox_deadline_set: OutboxDeadlineSetV3
settlement_kind = ATTESTED_OUTPUT | FAILED_CLOSED
settlement_evidence:
  {"kind":"ATTESTED_OUTPUT","output_entry_digest":Sha256Hex,
   "ordered_reopened_output_physical":array of FilePhysicalV2}
  XOR
  {"kind":"FAILED_CLOSED","frozen_output_identity_digest":Sha256Hex,
   "terminal_failure_code":Identifier,
   "signed_inventory_evidence_sha":Sha256Hex,
   "signed_inventory_evidence_physical":CasRecordPhysicalV2}
ordered_role_poststates[4]
attempt_intermediate_state = TERMINAL_SETTLED
attempt_post_state = SEAL_QUEUED
seal_output_spec:
  entry_id,outbox_slot=ATTEMPT_SEAL,role_id,literal_path,schema_role,
  schema_sha,publisher_actor_id,publisher_physical_sha,constructor_id,
  predecessor_set_digest
```

Its digest uses domain
`WS-WALKSAFE-R007-TERMINAL-SETTLEMENT-CORE-V2`, NUL and full core JCS.
The core contains no seal content SHA, seal obligation/outbox digest,
transition receipt, publication attestation SHA or CAS-record Physical.

Before constructing seal bytes the same terminal CAS service signs
`EmbeddedTerminalSettlementCertificateV3`. Its predecessor-only body has
exact fields
`schema,aggregate_key_digest,selected_transition_id,selected_core_digest,
selected_obligation_digest,selected_outbox_digest,
selected_outbox_terminal_state=ATTESTED|EXPIRED,
ordered_reopened_terminal_output_physical_or_signed_absence,
ordered_role_poststates[4],attempt_intermediate_state=TERMINAL_SETTLED,
attempt_post_state=SEAL_QUEUED,outbox_deadline_set,cas_linearized_at,
cas_service_actor_id,cas_service_physical_sha`. The envelope signature domain
is `WS-WALKSAFE-R007-EMBEDDED-TERMINAL-SETTLEMENT-CERTIFICATE-V3`, followed by
NUL and body JCS. It contains no seal bytes, seal content SHA, seal obligation
or seal outbox identity and therefore is acyclic.

The terminal publish CAS atomically commits terminal `ATTESTED` or
`FAILED_CLOSED`, role `SETTLED`/`NOT_NEEDED`, attempt `SEAL_QUEUED`, the signed
certificate, deterministic seal bytes/content identity, SEAL obligation,
SEAL outbox `PENDING`, and a postcommit publication binding. The seal body
embeds the complete signed certificate and reopened Physical/absence variant;
the later postcommit binding may name seal obligation/outbox digests without
being embedded back into the certificate.

`PublicationAttestationV3` is a CAS-internal signed envelope with
exact body:

```text
schema
transition_id + core_digest + obligation_digest + outbox_digest
terminal_seal_binding:
  {"kind":"NOT_A_TERMINAL_SETTLEMENT"}
  XOR
  {"kind":"TERMINAL_SETTLEMENT",
   "terminal_settlement_core_digest":Sha256Hex,
   "seal_obligation_digest":Sha256Hex,
   "seal_outbox_digest":Sha256Hex}
outbox_slot
pre_batch_state=CLAIMED|PENDING|PUBLISHED
intermediate_batch_state=PUBLISHED
post_batch_state=ATTESTED
pre_batch_token + post_batch_token
claim_binding_or:
  {"kind":"ACTIVE_CLAIM","publication_lease_id":Identifier,
   "publication_lease_token":Sha256Hex,
   "claimant_actor_id":Identifier,"claimant_physical_sha":Sha256Hex}
  XOR {"kind":"STORED_OR_RECONCILED_EXACT"}
ordered_settled_outputs[]{
  entry_id,literal_path,schema_sha,content_sha,reopened_file_physical
}
output_count + output_digest
settled_at
cas_service_actor_id + cas_service_physical_sha
```

Its signature object has the same five exact service-signature fields and
domain `WS-WALKSAFE-R007-PUBLICATION-ATTESTATION-V3`. The body excludes its own
SHA/record Physical; consumers receive those externally from the CAS return
tuple.

For `FSM032F`, the corresponding post-commit
`TerminalFailureSettlementAttestationV2` exact body is
`schema,selected_transition_id,selected_core_digest,
terminal_settlement_core_digest,failed_obligation_digest,
failed_outbox_digest,seal_obligation_digest,seal_outbox_digest,
frozen_output_identity_digest,terminal_failure_code,
outbox_deadline_set,linearized_at,
cas_service_actor_id,cas_service_physical_sha`. It requires
the TERMINAL profile to be expired and the SEAL profile still open, signs domain
`WS-WALKSAFE-R007-TERMINAL-FAILURE-SETTLEMENT-ATTESTATION-V2`, and binds the
same selected identity without claiming that a functional project output was
published. The predecessor-only signed certificate, not this postcommit
binding, is embedded in the seal body.

Same path/same bytes/same `FilePhysicalV2` may be adopted. Same path with
different bytes/schema/publisher/Physical/constructor produces signed
collision evidence, causes no overwrite and enters the fail-closed fallback.
Duplicate workers can produce
only one physical file.

Nonexecution has two dedicated signed source pairs:

| source kind | payload role / exact path | signature role / exact path | payload schema / signature schema | payload/signature publisher |
|---|---|---|---|---|
| `REQUEST_EXPIRED` | `REQUEST-EXPIRED-OBSERVATION-PAYLOAD` / `AUTH_ROOT_TEMPLATE/lifecycle/request-expired-observation.payload.json` | `REQUEST-EXPIRED-OBSERVATION-SIGNATURE` / `AUTH_ROOT_TEMPLATE/lifecycle/request-expired-observation.signature.json` | `REQUEST_EXPIRED_OBSERVATION_PAYLOAD_V2` / `REQUEST_EXPIRED_OBSERVATION_SIGNATURE_V2` | `LIFECYCLE_EXPIRY_OBSERVER` / `LIFECYCLE_EXPIRY_SIGNER` |
| `PRE_ALLOW_ABANDONED` | `PRE-ALLOW-ABANDON-PAYLOAD` / `AUTH_ROOT_TEMPLATE/lifecycle/pre-allow-abandon.payload.json` | `PRE-ALLOW-ABANDON-SIGNATURE` / `AUTH_ROOT_TEMPLATE/lifecycle/pre-allow-abandon.signature.json` | `PRE_ALLOW_ABANDON_PAYLOAD_V2` / `PRE_ALLOW_ABANDON_SIGNATURE_V2` | `LIFECYCLE_ABANDON_SERIALIZER` / `LIFECYCLE_ABANDON_SIGNER` |

Each RoleInstance row has a non-empty payload or detached-signature
`schema_sha`, content SHA, resolved literal path, publisher actor ID,
publisher executable Physical SHA and output `FilePhysicalV2`. The
`RequestExpiredObservationPayloadV2` closed body is:

```text
schema
lifecycle_key_digest
frozen_namespace_context: FrozenNamespaceContextV2
request_payload_binding: ArtifactBindingV2
request_signature_binding: ArtifactBindingV2
request_expires_at
lifecycle_seal_not_after
trusted_linearized_at
trusted_clock_source_id + trusted_clock_source_physical_sha
trusted_clock_correlation_sha
expiry_timer_registration_sha
expiry_timer_registration_physical: FilePhysicalV2
observation_nonce
publisher_actor_id + publisher_physical_sha
signature_domain =
  WS-WALKSAFE-R007-REQUEST-EXPIRED-OBSERVATION-PAYLOAD-V2
```

It requires `request_expires_at <= trusted_linearized_at <
lifecycle_seal_not_after` and exact equality to the pre-registered lifecycle
timer and frozen request. The `PreAllowAbandonPayloadV2` closed body is:

```text
schema
lifecycle_key_digest
frozen_namespace_context: FrozenNamespaceContextV2
request_payload_binding: ArtifactBindingV2
request_signature_binding: ArtifactBindingV2
lifecycle_pre_state=REQUEST_PENDING
lifecycle_pre_token
request_expires_at
lifecycle_seal_not_after
abandon_reason_code
abandon_requested_at
trusted_linearized_at
trusted_clock_source_id + trusted_clock_source_physical_sha
trusted_clock_correlation_sha
abandon_nonce
publisher_actor_id + publisher_physical_sha
signature_domain =
  WS-WALKSAFE-R007-PRE-ALLOW-ABANDON-PAYLOAD-V2
```

It requires `abandon_requested_at <= trusted_linearized_at <
request_expires_at` and no ALLOW/DENY decision predecessor. Both detached
signature schemas have exact fields
`schema,payload_path,payload_schema_sha,payload_sha,payload_physical,
signer_actor_id,signer_physical_sha,signature_algorithm,signature_domain,
signature`; each signs its literal payload domain, NUL and full payload JCS.
Neither payload nor signature contains its own SHA/Physical.

The complete direct nonexecution-source edges are:

```text
NEX001 AUTHORITY-REQUEST-PAYLOAD →
       REQUEST-EXPIRED-OBSERVATION-PAYLOAD
NEX002 AUTHORITY-REQUEST-SIGNATURE →
       REQUEST-EXPIRED-OBSERVATION-PAYLOAD
NEX003 REQUEST-EXPIRED-OBSERVATION-PAYLOAD →
       REQUEST-EXPIRED-OBSERVATION-SIGNATURE
NEX004 REQUEST-EXPIRED-OBSERVATION-SIGNATURE →
       LIFECYCLE-TRANSITION-CORE [REQUEST_EXPIRED]
NEX005 AUTHORITY-REQUEST-PAYLOAD → PRE-ALLOW-ABANDON-PAYLOAD
NEX006 AUTHORITY-REQUEST-SIGNATURE → PRE-ALLOW-ABANDON-PAYLOAD
NEX007 PRE-ALLOW-ABANDON-PAYLOAD → PRE-ALLOW-ABANDON-SIGNATURE
NEX008 PRE-ALLOW-ABANDON-SIGNATURE →
       LIFECYCLE-TRANSITION-CORE [ABANDONED/PRE_ALLOW]
```

`LifecycleTransitionCoreV2.decision_or_request_source_refs` contains exactly
the selected pair in payload-then-signature order and byte-compares their
bindings. These roles, paths, schemas, publishers and domains are disjoint
from the post-consume `EXECUTION-EXPIRY` pair; aliasing, substituting or
reusing an execution-expiry artifact yields no lifecycle transition or seal.
The selected core continues to the seal through the single branch edge
`SEAL002`; there is no duplicate or alias edge.

Nonexecution uses a disjoint lifecycle-only constructor, never a fabricated
aggregate:

```text
LifecycleTransitionCoreV2:
  schema
  lifecycle_key_digest
  transition_ordinal
  prior_lifecycle_transition_id_or =
    {"kind":"GENESIS_NA"} |
    {"kind":"PRIOR","transition_id":Sha256Hex,"core_digest":Sha256Hex}
  lifecycle_pre_state + lifecycle_pre_token
  selected_nonexecution_variant =
    DENIED | REQUEST_EXPIRED | ABANDONED
  decision_or_request_source_refs[]{
    role_id,literal_path,schema_sha,content_sha,physical
  }
  lifecycle_intermediate_state=TERMINAL_SELECTED
  lifecycle_post_state=SEAL_QUEUED
  lifecycle_post_token
  seal_output_spec:
    entry_id,outbox_slot=ATTEMPT_SEAL,role_id,literal_path,
    schema_role,schema_sha,publisher_actor_id,publisher_physical_sha,
    constructor_id,predecessor_set_digest

lifecycle_core_digest =
  SHA256(ASCII("WS-WALKSAFE-R007-LIFECYCLE-TRANSITION-CORE-V2") ||
         0x00 || RFC8785_JCS(LifecycleTransitionCoreV2))

lifecycle_transition_id =
  SHA256(ASCII("WS-WALKSAFE-R007-LIFECYCLE-TRANSITION-ID-V2") ||
         0x00 || RFC8785_JCS({
           "lifecycle_key_digest":lifecycle_key_digest,
           "transition_ordinal":transition_ordinal,
           "core_digest":lifecycle_core_digest
         }))
```

`LifecycleSealObligationV2` exact fields are
`schema,lifecycle_transition_id,lifecycle_core_digest,lifecycle_key_digest,
selected_nonexecution_variant,seal_output_entry,output_entry_digest,
lifecycle_seal_not_after,lifecycle_trusted_clock_source_id,
lifecycle_trusted_clock_correlation_sha,
lifecycle_fail_close_timer_registration_sha,
lifecycle_fail_close_timer_registration_physical,lifecycle_idempotency_key`.
The lifecycle-only idempotency key is exactly

```text
SHA256(
  ASCII("WS-WALKSAFE-R007-LIFECYCLE-PUBLISH-ONCE-V2") || 0x00 ||
  RFC8785_JCS({
    "lifecycle_key_digest":lifecycle_key_digest,
    "outbox_slot":"ATTEMPT_SEAL",
    "entry_id":seal_output_entry.entry_id,
    "literal_path":seal_output_entry.literal_path,
    "content_sha":seal_output_entry.content_sha
  })
)
```

It never uses or creates an aggregate key. `LifecycleSealOutboxBatchV2` exact
fields are
`schema,lifecycle_transition_id,lifecycle_core_digest,obligation_digest,
outbox_slot=ATTEMPT_SEAL,seal_output_entry,output_entry_digest,
initial_batch_state=PENDING,initial_batch_token,lifecycle_idempotency_key,
created_at,lifecycle_deadline_profile:LifecycleSealDeadlineProfileV3,
lifecycle_trusted_clock_source_id,lifecycle_trusted_clock_source_physical_sha,
lifecycle_trusted_clock_correlation_sha,
lifecycle_fail_close_timer_registration_sha,
lifecycle_fail_close_timer_registration_physical`.
Its distinct mutable `LifecycleOutboxBatchStateRecordV2` has exact fields:

```text
schema
lifecycle_transition_id + lifecycle_core_digest
obligation_digest + lifecycle_outbox_digest
lifecycle_key_digest
outbox_slot=ATTEMPT_SEAL
batch_state=PENDING|CLAIMED|PUBLISHED|ATTESTED|EXPIRED
batch_token + state_generation
claim_attempt_count
lifecycle_deadline_profile:LifecycleSealDeadlineProfileV3
lifecycle_trusted_clock_source_id
lifecycle_trusted_clock_source_physical_sha
lifecycle_trusted_clock_correlation_sha
lifecycle_fail_close_timer_registration_sha
lifecycle_fail_close_timer_registration_physical:FilePhysicalV2
claim_binding:
  {"kind":"NO_CLAIM"}
  XOR
  {
    "kind":"ACTIVE_CLAIM",
    "publication_lease_id":Identifier,
    "publication_lease_token":Sha256Hex,
    "claimant_actor_id":Identifier,
    "claimant_physical_sha":Sha256Hex,
    "claimed_at":UtcNano,
    "claim_not_after":UtcNano
  }
seal_output_identity_digest
publication_or_expiry_evidence:
  {"kind":"NONE"}
  XOR {"kind":"EXACT_PUBLICATION","reopened_physical":FilePhysicalV2}
  XOR {"kind":"SIGNED_EXPIRY_INVENTORY","evidence_sha":Sha256Hex,
       "evidence_physical":CasRecordPhysicalV2}
last_trusted_linearized_at
```

It is not an alias or field-substitution of the aggregate batch schema.
Its initial mutable row has
`state_generation=0,claim_attempt_count=0,claim_binding=NO_CLAIM`.

`LifecycleSealDeadlineProfileV3` has exact fields
`schema,first_claim_not_after,publish_not_after,reconcile_not_after,
claim_lease_ns,trusted_clock_source_id,trusted_clock_source_physical_sha,
trusted_clock_correlation_sha,timer_registration:SignedTimerRegistrationRefV3`.
It requires `first_claim_not_after < publish_not_after <
reconcile_not_after <= lifecycle_seal_not_after`; its digest uses domain
`WS-WALKSAFE-R007-LIFECYCLE-SEAL-DEADLINE-PROFILE-V3`, NUL and full body JCS.
The positive `claim_lease_ns` is frozen only here, and every claim computes
`claim_not_after=min(linearized_at+claim_lease_ns,publish_not_after)`.

The lifecycle outbox has this complete literal event relation:

| transition ID | event | from | to | exact CAS guard/effect |
|---|---|---|---|---|
| `LOBX001` | `CLAIM` | `PENDING` | `CLAIMED` | trusted time before `publish_not_after`; create the formula-derived lease and increment attempt count |
| `LOBX002` | `PUBLISH_AND_ATTEST_SEAL` | `CLAIMED` | `ATTESTED` | active lease, create-exclusive/adopt and exact reopen; one CAS records intermediate `PUBLISHED`, final `ATTESTED` and lifecycle `SEAL_QUEUED→SEALED` |
| `LOBX003` | `CLAIM_FAILURE_RETRY` | `CLAIMED` | `PENDING` | signed failure before `publish_not_after`; invalidate lease; immutable batch unchanged |
| `LOBX004` | `RECLAIM_STALE` | `CLAIMED` | `PENDING` | stale lease before `publish_not_after`; invalidate exact lease; immutable batch unchanged |
| `LOBX005` | `ATTEST_STORED_PUBLISHED` | `PUBLISHED` | `ATTESTED` | exact stored publication proof; store-only CAS atomically changes lifecycle to `SEALED`, including after wall deadline |
| `LOBX006` | `DEADLINE_EXPIRE_PENDING` | `PENDING` | `EXPIRED` | at/after `publish_not_after`, signed nofollow inventory proves seal absent/collided; atomically lifecycle `SEAL_FAILED_CLOSED` |
| `LOBX007` | `DEADLINE_RECONCILE_CLAIMED` | `CLAIMED` | `ATTESTED` or `EXPIRED` | invalidate lease and reopen; exact seal records intermediate `PUBLISHED` then `ATTESTED/SEALED`, otherwise signed inventory and `EXPIRED/SEAL_FAILED_CLOSED` |
| `LOBX008` | `REPLAY_TERMINAL` | `ATTESTED` or `EXPIRED` | unchanged | receipt/lease/project-write `0`; lifecycle terminal state must match |

Every other `(state,event)` has effect/receipt/lease/project-write count `0`.
The exact five spellings equal the aggregate outbox; `PUBLISHED→EXPIRED` and
every outbox spelling outside that set are forbidden.

Every row produces a CAS-internal `LifecycleOutboxTransitionReceiptV2` signed
envelope with exact body:

```text
schema
lifecycle_transition_id + lifecycle_core_digest
obligation_digest + lifecycle_outbox_digest
lifecycle_key_digest + lifecycle_idempotency_key
event_id + event_kind
pre_batch_state + post_batch_state
pre_batch_token + post_batch_token
pre_state_generation + post_state_generation
pre_claim_attempt_count + post_claim_attempt_count
pre_claim_binding + post_claim_binding
seal_output_identity_digest
lifecycle_deadline_profile:LifecycleSealDeadlineProfileV3
lifecycle_trusted_clock_source_id
lifecycle_trusted_clock_source_physical_sha
lifecycle_trusted_clock_correlation_sha
lifecycle_fail_close_timer_registration_sha
lifecycle_fail_close_timer_registration_physical
trusted_linearized_at
failure_or_retry_authority_binding
lifecycle_pre_state + lifecycle_post_state
cas_service_actor_id + cas_service_physical_sha
```

The signature domain is
`WS-WALKSAFE-R007-LIFECYCLE-OUTBOX-TRANSITION-RECEIPT-V2`, NUL and full body
JCS. Receipt bytes omit their own SHA/record Physical. Every retry compares
the immutable obligation/outbox/idempotency key, prior token/generation,
lease tuple, claim-attempt count, deadline and trusted clock binding. A crash
before claim, after claim, after create-exclusive, after reopen or before the
final CAS therefore resumes the same batch and produces no extra project
file.
The independently published timer registration precedes lifecycle selection
and names the CAS service, exact lifecycle key, deadline and trusted clock
binding. That service deterministically attempts `LOBX006/007` at the
publish boundary; no caller-selected timer or polling worker is the
terminal authority.

`LifecyclePublicationFailureTerminalV2`, emitted only by `LOBX008`, is a
CAS-internal signed envelope whose exact body is
`schema,lifecycle_transition_id,lifecycle_core_digest,obligation_digest,
lifecycle_outbox_digest,lifecycle_key_digest,seal_output_identity_digest,
failure_code,final_batch_state=EXPIRED,lifecycle_pre_state=SEAL_QUEUED,
lifecycle_post_state=SEAL_FAILED_CLOSED,lifecycle_seal_not_after,
trusted_linearized_at,lifecycle_trusted_clock_source_id,
lifecycle_trusted_clock_source_physical_sha,
lifecycle_trusted_clock_correlation_sha,cas_service_actor_id,
cas_service_physical_sha`. It signs domain
`WS-WALKSAFE-R007-LIFECYCLE-PUBLICATION-FAILURE-TERMINAL-V2`. Thus every
nonexecution selection eventually reaches either one reopened seal and
`SEALED`, or this signed fail-closed terminal; the latter creates no project
artifact and permanently blocks successor namespace append.

The lifecycle selection CAS commits core, obligation and batch together,
records `TERMINAL_SELECTED→SEAL_QUEUED`, and returns a signed lifecycle
transition receipt with the same predecessor-only pattern. Authority row
count, aggregate key count and role row count remain zero. Seal publication
then performs the lifecycle-only `SEAL_QUEUED→SEALED` CAS. Selection,
publication and the final CAS all recheck the frozen lifecycle clock binding.
Only new claim/rename authorization is deadline-bounded; a stored exact
`PUBLISHED` seal is always eligible for store-only attestation. DENIED,
REQUEST_EXPIRED and
pre-ALLOW ABANDONED therefore have a deadline without inventing a decision
role deadline set. At equality or later, exact physical evidence chooses the
store-only `SEALED` path and exact absence/collision chooses the no-project-write
`SEAL_FAILED_CLOSED` path.

Dispatch and `FINALIZATION_WORK` batch settlement leave the owning role
`CONSUMED_OPEN`. A functional terminal batch settlement alone changes
`OUTCOME_SELECTED→SETTLED`. It atomically stores the signed settlement
attestation and enqueues the `ATTEMPT_SEAL` batch. Terminal settlement
attestations and final seal-settled CAS receipts are CAS-internal; therefore
the only project control file after the functional terminal is the seal.

### 4.9 finalization fallback and crash-resumable AttemptRootSeal

`FSM019` and `FSM019P` finalization consume CAS store
`FINALIZATION_WORK` (or the pending-cause partial-close batch in `FSM019P`)
and the following resumable tail obligation:

```text
FinalizationTailObligationV2 = {
  "schema":"FINALIZATION_TAIL_OBLIGATION_V2",
  "transition_id":Sha256Hex,
  "core_digest":Sha256Hex,
  "finalization_role_token":Sha256Hex,
  "finalization_grant_payload_sha":Sha256Hex,
  "finalization_grant_wrapper_sha":Sha256Hex,
  "finalization_grant_payload_physical":FilePhysicalV2,
  "finalization_grant_wrapper_physical":FilePhysicalV2,
  "terminal_wrapper_grant_payload_sha":Sha256Hex,
  "terminal_wrapper_grant_wrapper_sha":Sha256Hex,
  "terminal_wrapper_grant_payload_physical":FilePhysicalV2,
  "terminal_wrapper_grant_wrapper_physical":FilePhysicalV2,
  "ordered_success_output_specs":array of TransitionCoreV2 output spec,
  "ordered_failure_output_specs":array of TransitionCoreV2 output spec,
  "fallback_causes":[
    "CRASH","REVOCATION","EXPIRY","SETTLEMENT_TIMEOUT"
  ],
  "fallback_partial_close_payload_spec":TransitionCoreV2.output spec,
  "fallback_partial_close_receipt_spec":TransitionCoreV2.output spec,
  "operation_not_after":UtcNano,
  "settlement_not_after":UtcNano,
  "attempt_seal_not_after":UtcNano,
  "idempotency_set_digest":Sha256Hex
}
```

Worker crash, accepted post-consume revocation, expiry and settlement timeout
all select from this precommitted set. They cannot introduce a new path,
schema, publisher or constructor. The fallback CAS writes
`FINALIZATION_TERMINAL` with deterministic partial-close payload/receipt,
their `FilePhysicalV2` after settlement and then permits wrapper selection.
No fallback re-consumes the role. `CRASH`, `REVOCATION`, `EXPIRY` and
`SETTLEMENT_TIMEOUT` have separate fixture cuts and each produces one terminal
path.

The only attempt seal artifact is:

```text
ATTEMPT_ROOT_TEMPLATE/control/attempt-terminal-seal-receipt.json
schema = ATTEMPT_ROOT_SEAL_ENVELOPE_V2
role_id = ATTEMPT_TERMINAL_SEAL
aliases = [AttemptRootSealReceiptV2, AttemptTerminalSealReceiptV2]
publisher = ATTEMPT_SEAL_FINALIZER
signature_domain = WS-WALKSAFE-R007-ATTEMPT-ROOT-SEAL-BODY-V2
```

Aliases are schema names for the same file/bytes/Physical, not additional
roles. The one-file envelope is:

```text
{
  "body": AttemptRootSealBodyV2,
  "signature": {
    "algorithm":Identifier,
    "signer_actor_id":Identifier,
    "signer_physical_sha":Sha256Hex,
    "signature_domain":
      "WS-WALKSAFE-R007-ATTEMPT-ROOT-SEAL-BODY-V2",
    "signature":SignatureBytes
  }
}
```

Signature input is domain, NUL and `RFC8785_JCS(body)`. Neither body nor
signature object contains seal envelope SHA or its future `FilePhysicalV2`.

`AttemptRootSealBodyV2` is a strict common body plus one tagged
`variant_body`. Common exact fields:

```text
schema
variant: EXECUTED | DENIED | REQUEST_EXPIRED | ABANDONED
successor_revision_id
attempt_namespace_id + attempt_ordinal
literal_attempt_root
lifecycle_key_digest + lifecycle_final_token
namespace_member_payload_sha + namespace_member_wrapper_sha
namespace_member_payload_physical + namespace_member_wrapper_physical
namespace_append_receipt_sha + namespace_append_receipt_physical
namespace_head_payload_sha + namespace_head_wrapper_sha
namespace_head_payload_physical + namespace_head_wrapper_physical
namespace_head_observation_sha + namespace_head_observation_physical
namespace_cas_token
seal_predecessor_certificate:
  {"kind":"LIFECYCLE_SELECTION_CERTIFICATE",
   "certificate":EmbeddedLifecycleSelectionCertificateV3}
  XOR
  {"kind":"AUTHORITY_TERMINAL_CERTIFICATE",
   "certificate":EmbeddedTerminalSettlementCertificateV3}
authority_binding:
  {"kind":"NOT_APPLICABLE","reason":
    "DENIED"|"REQUEST_EXPIRED"|"PRE_ALLOW_ABANDONED"|
    "POST_ALLOW_AGGREGATE_ABSENT"}
  XOR
  {
    "kind":"ALLOW_AGGREGATE",
    "attempt_id":Sha256Hex,
    "aggregate_key_digest":Sha256Hex,
    "aggregate_final_token":Sha256Hex,
    "attempt_state":"SEAL_QUEUED",
    "ordered_final_role_states":[
      {"ordinal":0..3,"role_id":Identifier,
       "state":"SETTLED"|"NOT_NEEDED","role_token":Sha256Hex}
    ]
  }
ordered_preseal_inventory[]:
  ordinal,role_id,literal_path,schema_role,schema_sha,publisher_actor_id,
  publisher_physical_sha,content_sha,file_physical
preseal_member_count
preseal_total_bytes
preseal_member_set_digest
preseal_tree_physical_digest
nofollow_regular_file_check = PASS
zero_data_plane_proof: ZeroDataPlaneProofV2
variant_terminal_ref_digest
declared_seal_path
sealed_root_identity_digest
seal_selected_at
trusted_clock_source_id + trusted_clock_correlation_sha
post_seal_write_count_under_sealed_attempt_root = 0
variant_body
```

`EmbeddedLifecycleSelectionCertificateV3` is the lifecycle counterpart to the
authority certificate in §4.8. Its signed predecessor-only body binds the
lifecycle transition/core, selected nonexecution tag, exact decision or
request/adverse pair with SHA and Physical, lifecycle pre/post token,
preselection deadline/timer evidence, signed aggregate-absence proof when
applicable, seal output spec and CAS linearization. It excludes seal bytes,
seal content SHA, seal obligation/outbox digest, its own SHA and CAS Physical.
Its domain is
`WS-WALKSAFE-R007-EMBEDDED-LIFECYCLE-SELECTION-CERTIFICATE-V3`, NUL and body
JCS. Exactly one of the two signed certificate alternatives is embedded in
every seal; an unsigned core is not a seal authority.

`ZeroDataPlaneProofV2` exact fields:

```text
applicable: true | false
ordered_observed_data_entries[]
observed_data_entry_count
observed_data_entry_digest
required_data_entry_count
required_data_entry_digest
proof_result: PASS | NOT_APPLICABLE
```

For `DENIED`, `REQUEST_EXPIRED`, `ABANDONED`: `applicable=true`, both counts
are `0`, both digests use the exact empty-array domain
`WS-WALKSAFE-R007-ZERO-DATA-PLANE-V2`, result is `PASS`.
For `EXECUTED`: `applicable=false`, arrays empty, result
`NOT_APPLICABLE`.

`TerminalUnavailabilitySourceBindingV2` has exact fields
`role_id,literal_or_cas_logical_path,schema_role,schema_sha,content_sha,
physical:FilePhysicalV2|CasRecordPhysicalV2,publisher_actor_id,
publisher_physical_sha,cause,effective_at,aggregate_token`. It refers only to
an already signed timeout/branch-terminal artifact or CAS receipt and cannot
contain its own identity.

`TerminalTransitionOrV2` is exactly
`{"kind":"TRANSITION","transition_id":Sha256Hex,"core_digest":Sha256Hex,
"transition_receipt_sha":Sha256Hex,
"transition_receipt_physical":CasRecordPhysicalV2}` XOR
`{"kind":"NOT_APPLICABLE","reason":
"SETTLEMENT_TIMEOUT_BEFORE_EXECUTION_TERMINAL"|
"SETTLEMENT_TIMEOUT_BEFORE_FINALIZATION_TERMINAL"|
"BRANCH_TERMINATED_BEFORE_ROLE",
"unavailability_source":TerminalUnavailabilitySourceBindingV2}`. The source is the signed timeout
or branch-terminal predecessor and its cause/time/aggregate token must
byte-equal the selector tuple in the terminal core.
`FinalizationCloseOrV2` is exactly
`{"kind":"ARTIFACT","binding":ArtifactBindingV2}` XOR
`{"kind":"NOT_APPLICABLE","reason":
"SETTLEMENT_TIMEOUT_BEFORE_FINALIZATION_CLOSE"|
"BRANCH_TERMINATED_BEFORE_FINALIZATION_CLOSE",
"unavailability_source":TerminalUnavailabilitySourceBindingV2}`. A bare reason string is invalid.

`variant_body` is exactly one of:

```text
{"kind":"EXECUTED",
 "selected_terminal_transition_id":Sha256Hex,
 "selected_terminal_core_digest":Sha256Hex,
 "terminal_output_kind":Identifier,
 "terminal_settlement_core":TerminalSettlementCoreV2,
 "terminal_settlement_core_digest":Sha256Hex,
 "terminal_evidence":
   {"kind":"PUBLISHED",
    "execution_terminal_or":TerminalTransitionOrV2,
    "finalization_terminal_or":TerminalTransitionOrV2,
    "functional_terminal_refs":non-empty array of ArtifactBindingV2,
    "functional_terminal_ref_count":Count,
    "functional_terminal_ref_digest":Sha256Hex,
    "finalization_close_or":FinalizationCloseOrV2}
   XOR
   {"kind":"FAILED_CLOSED_NO_PROJECT_OUTPUT",
    "frozen_output_identity_digest":Sha256Hex,
    "execution_terminal_or":TerminalTransitionOrV2,
    "finalization_terminal_or":TerminalTransitionOrV2,
    "finalization_close_or":FinalizationCloseOrV2,
    "functional_terminal_refs":[],
    "functional_terminal_ref_count":0,
    "functional_terminal_ref_digest":Sha256Hex}}

{"kind":"DENIED",
 "decision_payload_sha":Sha256Hex,"decision_wrapper_sha":Sha256Hex,
 "decision_payload_physical":FilePhysicalV2,
 "decision_wrapper_physical":FilePhysicalV2,
 "denied_at":UtcNano,"denial_reason_code":Identifier}

{"kind":"REQUEST_EXPIRED",
 "request_payload_sha":Sha256Hex,"request_wrapper_sha":Sha256Hex,
 "request_payload_physical":FilePhysicalV2,
 "request_wrapper_physical":FilePhysicalV2,
 "request_expires_at":UtcNano,
 "expiry_observation_sha":Sha256Hex,
 "expiry_observation_physical":FilePhysicalV2,
 "expiry_observation_signature_sha":Sha256Hex,
 "expiry_observation_signature_physical":FilePhysicalV2}

{"kind":"ABANDONED",
 "abandon_phase":"PRE_ALLOW"|"POST_ALLOW_AGGREGATE_ABSENT"|
   "POST_ALLOW_AGGREGATE_PRESENT",
 "abandon_source_kind":
   "PRE_ALLOW_ABANDON"|"ACTIVATION_TIMEOUT"|
   "EXECUTION_PRE_REVOKED"|"EXECUTION_PRE_EXPIRED",
 "abandon_source_payload_sha":Sha256Hex,
 "abandon_source_signature_sha":Sha256Hex,
 "abandon_source_payload_physical":FilePhysicalV2,
 "abandon_source_signature_physical":FilePhysicalV2,
 "abandoned_at":UtcNano,"abandon_reason_code":Identifier}
```

Strict variant constraints:

| variant | queue kind | authority binding | functional refs / attestations | zero-data proof |
|---|---|---|---|---|
| `EXECUTED/ATTESTED_OUTPUT` | `AUTHORITY_AGGREGATE` | `ALLOW_AGGREGATE`, four rows all `S` or `N` | functional refs count at least 1; settlement core kind `ATTESTED_OUTPUT`; every transition/close tag exact and each absence carries signed cause proof | `NOT_APPLICABLE` |
| `EXECUTED/EXPIRED` | `AUTHORITY_AGGREGATE` | `ALLOW_AGGREGATE`, four rows all `S` or `N` | functional refs count 0; settlement core kind `FAILED_CLOSED`; signed observed inventory exact; every absence carries signed cause proof | `NOT_APPLICABLE` |
| `DENIED` | `LIFECYCLE` | `NOT_APPLICABLE/DENIED` | fields forbidden | empty/empty `PASS` |
| `REQUEST_EXPIRED` | `LIFECYCLE` | `NOT_APPLICABLE/REQUEST_EXPIRED` | fields forbidden | empty/empty `PASS` |
| `ABANDONED/PRE_ALLOW` | `LIFECYCLE` | `NOT_APPLICABLE/PRE_ALLOW_ABANDONED` | fields forbidden | empty/empty `PASS` |
| `ABANDONED/POST_ALLOW_AGGREGATE_ABSENT` | `LIFECYCLE` | `NOT_APPLICABLE/POST_ALLOW_AGGREGATE_ABSENT`; signed absence proof | fields forbidden | empty/empty `PASS` |
| `ABANDONED/POST_ALLOW_AGGREGATE_PRESENT` | `AUTHORITY_AGGREGATE` | `ALLOW_AGGREGATE`, four rows all `S` or `N`; activation expiry evidence | fields forbidden | empty/empty `PASS` |

`TerminalVariantRefPreimageV3` is a closed seven-alternative union. Every
alternative starts with
`schema,tag,successor_revision_id,attempt_namespace_id,lifecycle_key_digest,
embedded_certificate_digest` and then requires exactly the following tagged
fields; keys listed for another tag are forbidden.

| tag | exact additional preimage fields |
|---|---|
| `EXECUTED_ATTESTED_OUTPUT` | `aggregate_key_digest,attempt_id,aggregate_final_token,ordered_final_role_states[4],terminal_certificate_digest,ordered_functional_terminal_refs,finalization_close_or` |
| `EXECUTED_EXPIRED` | `aggregate_key_digest,attempt_id,aggregate_final_token,ordered_final_role_states[4],terminal_certificate_digest,frozen_output_identity_digest,signed_observed_inventory_binding` |
| `DENIED` | `decision_payload_binding,decision_signature_binding,denial_reason_code,denied_at` |
| `REQUEST_EXPIRED` | `request_payload_binding,request_signature_binding,expiry_observation_payload_binding,expiry_observation_signature_binding,request_expires_at` |
| `ABANDONED_PRE_ALLOW` | `abandon_payload_binding,abandon_signature_binding,abandon_reason_code,abandoned_at` |
| `ABANDONED_POST_ALLOW_AGGREGATE_ABSENT` | `allow_decision_payload_binding,allow_decision_signature_binding,activation_timer_firing_binding,signed_aggregate_absence_proof,abandon_reason_code,abandoned_at` |
| `ABANDONED_POST_ALLOW_AGGREGATE_PRESENT` | `allow_decision_payload_binding,allow_decision_signature_binding,activation_outbox_expiry_binding,aggregate_final_token,ordered_final_role_states[4],adverse_source_payload_binding,adverse_source_signature_binding,abandon_reason_code,abandoned_at` |

For tag `T`, `variant_terminal_ref_digest =
SHA256(ASCII("WS-WALKSAFE-R007-TERMINAL-VARIANT-REF-" || T || "-V3") ||
0x00 || RFC8785_JCS(full tagged preimage))`. The ASCII tag is one of the seven
literal table values, array order is role or artifact registry order, and
duplicate members are invalid. A tag substitution, missing field, extra field
or digest of a non-tagged projection rejects.

Fields belonging to another variant are forbidden rather than ignored.
For every reachable `FSM003`–`FSM032F` terminal, exactly one row above is
constructible. In particular, early `FSM028` cannot invent an execution,
finalization or close reference: if its deterministic terminal batch is
published, it uses the `PUBLISHED` branch with the relevant
`NOT_APPLICABLE+unavailability_source` tags; if that selected batch itself
reaches the recovery deadline without publication, `FSM032F` uses
`FAILED_CLOSED_NO_PROJECT_OUTPUT` with the same signed tags. Missing proof,
an untagged absence or more than one constructible seal variant rejects.

Seal source order:

1. Nonexecution selection CAS signs the predecessor-only lifecycle certificate,
   embeds it in deterministic seal bytes and commits the `ATTEMPT_SEAL`
   obligation/outbox atomically.
2. Executed terminal settlement signs the predecessor-only terminal
   certificate, embeds it in deterministic seal bytes and commits terminal
   state plus the seal obligation/outbox atomically. The attested branch uses
   exact reopened terminal `FilePhysicalV2`; `FSM032F` uses signed observed
   inventory and the frozen output identity.
3. Publisher uses §4.8 `PUBLISH_ONCE`.
4. Seal reopen success changes lifecycle/attempt to `SEALED` in a
   store-internal final CAS; no second project receipt is written.

The seal body never contains the SHA or `CasRecordPhysicalV2` of the
post-commit transition receipt or publication attestation, nor its own seal
obligation/outbox digest. Its predecessor-only signed certificate is fixed
before seal bytes. The seal content SHA then enters its output entry and seal
obligation/outbox; the post-commit receipt/attestation binds those digests,
producing the one-way order
`predecessor core/output → signed certificate → seal body/content SHA →
obligation/outbox → CAS →
receipt/attestation`.

Crash after seal enqueue or during publication resumes the same batch and
idempotency key. A settlement timeout first selects a fail-closed functional
terminal and seal obligation before its separate `attempt_seal_not_after`.

The seal excludes its own path from preseal inventory. Inventory entries sort
by `(literal_path,role_id,ordinal)`. `preseal_tree_physical_digest` hashes the
ordered directory/file `PhysicalV2` walk with domain
`WS-WALKSAFE-R007-PRESEAL-TREE-PHYSICAL-V2`.
`SealedRootIdentityPreimageV2` is the closed object
`{schema,successor_revision_id,attempt_namespace_id,literal_attempt_root,
variant,preseal_member_count,preseal_total_bytes,preseal_member_set_digest,
preseal_tree_physical_digest,variant_terminal_ref_digest,
seal_predecessor_certificate}`. `variant_terminal_ref_digest` is constructed
only from the exact seven-tag union above. Then
`sealed_root_identity_digest =
SHA256(ASCII("WS-WALKSAFE-R007-SEALED-ATTEMPT-ROOT-IDENTITY-V2") || 0x00 ||
RFC8785_JCS(SealedRootIdentityPreimageV2))`. The preimage's complete tagged
`seal_predecessor_certificate` bytes are byte-identical to the selected
`SEAL002` or `SEAL007` signed certificate source.

The next namespace constructor's `PREDECESSOR` variant and member payload both
contain predecessor seal SHA, seal `FilePhysicalV2` digest and sealed-root
identity. Before compare-and-append, `OldRootRecheckReceiptV2` nofollow-reopens
the seal and entire old inventory, recomputes both digests and requires
post-seal member count `0`.

The recheck is this one-file signed envelope:

```text
path =
  H1_ROOT_TEMPLATE/preflight/{attempt_namespace_id}/
  predecessor-old-root-recheck.receipt.json
schema = OLD_ROOT_RECHECK_RECEIPT_V2
publisher = OLD_ROOT_INDEPENDENT_RECHECKER
signature_domain = WS-WALKSAFE-R007-OLD-ROOT-RECHECK-BODY-V2

body exact fields:
  schema
  successor_revision_id
  current_attempt_namespace_id
  predecessor_attempt_namespace_id
  predecessor_seal_sha
  predecessor_seal_physical: FilePhysicalV2
  predecessor_sealed_root_identity_digest
  ordered_predecessor_inventory_refs[]{
    ordinal,role_id,literal_path,schema_sha,content_sha,file_physical
  }
  recomputed_member_count
  recomputed_total_bytes
  recomputed_member_set_digest
  recomputed_tree_physical_digest
  post_seal_new_member_count=0
  old_root_recheck_challenge: OldRootRecheckChallengeEnvelopeV2
  old_root_recheck_challenge_digest
  checked_at
  checker_actor_id + checker_physical_sha

signature exact fields:
  algorithm,signer_actor_id,signer_physical_sha,signature_domain,signature
```

Signature input is domain, NUL and body JCS. The current namespace member
payload binds predecessor seal SHA, full predecessor seal `FilePhysicalV2`,
sealed-root identity digest, recheck envelope SHA, recheck
`FilePhysicalV2` and challenge digest. Its `AttemptNamespaceConstructorV2`
binds the seal Physical digest, sealed-root identity and challenge digest.
The rechecker verifies the challenge service signature, requires
`issued_at <= checked_at < not_after`, and byte-compares its predecessor
fields to the reopened seal/head. The compare-and-append CAS recomputes the
full challenge digest and reopens/compares all artifacts; a digest-only
shortcut, nonce reuse, expired challenge or changed inode/parent/nlink/mode
fails.

```text
SEAL001 FUNCTIONAL-TERMINAL-PHYSICAL → ATTEMPT-SEAL-BODY
        [branch = EXECUTED/PUBLISHED only]
SEAL002 EMBEDDED-LIFECYCLE-SELECTION-CERTIFICATE → ATTEMPT-SEAL-BODY
        [seal_predecessor_certificate.kind = LIFECYCLE_SELECTION_CERTIFICATE]
SEAL003 ATTEMPT-SEAL-FILE → NEXT-NAMESPACE-MEMBER-PAYLOAD
SEAL004 ATTEMPT-SEAL-FILE → OLD-ROOT-RECHECK
SEAL005 OLD-ROOT-RECHECK → NAMESPACE-COMPARE-AND-APPEND
SEAL006 ATTEMPT-SEAL-OBLIGATION-OUTBOX →
        TERMINAL-SETTLEMENT-ATTESTATION
        [AUTHORITY_TERMINAL_CORE, post-commit direction]
SEAL007 EMBEDDED-TERMINAL-SETTLEMENT-CERTIFICATE → ATTEMPT-SEAL-BODY
        [seal_predecessor_certificate.kind = AUTHORITY_TERMINAL_CERTIFICATE]
```

`SEAL002` and `SEAL007` are mutually exclusive branch edges; their summed
materialized cardinality is exactly `1`. The edge source's signed certificate
bytes/digest must byte-equal the selected tagged member used by
`sealed_root_identity_digest`. A lifecycle seal cannot depend on a nonexistent
terminal certificate, and an authority seal cannot substitute a lifecycle
certificate.

Terminal seal variants are exact `4`; nonexecution variants exact `3`;
executed variant exact `1`. Functional terminal is the last
data/finalization-plane write. The seal is the sole later project control
write beneath that `literal_attempt_root`. After seal, project write count
under the sealed predecessor attempt root is `0`; an H1-level preflight
recheck and the different candidate namespace append remain legal. The
preflight path is computable from the candidate namespace ID and is outside
both the sealed predecessor root and the not-yet-appended candidate root.

### 4.10 wrapper final selection and exact race schedules

Wrapper selection is a CAS-internal `WRAPPER_SELECT` transition. Its pre-CAS
`WrapperSelectionIntentV2` exact fields:

```text
schema = WRAPPER_SELECTION_INTENT_V2
aggregate_key_digest
activated_wrapper_context: ActivatedRoleContextV2
finalization_consume_transition_id
finalization_tail_obligation_digest
prior_application_payload_path
prior_application_payload_schema
prior_application_payload_sha
prior_application_payload_physical: FilePhysicalV2
prior_partial_close_path
prior_partial_close_schema
prior_partial_close_sha
prior_partial_close_physical: FilePhysicalV2
preselection_terminal_output =
  {"kind":"ABSENT","matching_cardinality":0}
selected_output_kind:
  WRAPPER_PUBLICATION | REVOKED_DISPOSITION | EXPIRED_DISPOSITION |
  CRASHED_DISPOSITION | TIMED_OUT_DISPOSITION
ordered_selected_output_specs[]:
  ordinal,role_id,literal_path,schema_role,schema_sha,
  publisher_actor_id,publisher_physical_sha,constructor_id
selected_output_spec_count
selected_output_spec_digest
final_adverse_ref:
  {"kind":"NO_ADVERSE","reason":"NORMAL_WRAPPER"}
  XOR
  {
    "kind":"ADVERSE_SOURCE",
    "cause":"REVOCATION"|"EXPIRY"|"CRASH"|"SETTLEMENT_TIMEOUT",
    "payload_sha":Sha256Hex,
    "payload_physical":FilePhysicalV2,
    "signature_or_receipt_sha":Sha256Hex,
    "signature_or_receipt_physical":FilePhysicalV2|CasRecordPhysicalV2,
    "effective_at":UtcNano
  }
ordered_event_set[]:
  cause,source_sha,source_physical,effective_at,observed_at,priority
event_set_count + event_set_digest
selected_cause
publish_mode = PUBLISH_ONCE
```

The CAS direct preconditions independently compare
the prior application payload Physical, prior partial-close Physical and the
absent preselection terminal-output binding. `ABSENT` is required for every
new selection. An exact file with no current original outbox is a collision,
not an adoption authority. Output bytes reference
`transition_id/core_digest`, not selection receipt SHA. Wrapper/disposition
paths are frozen in the terminal-wrapper grant:

```text
ATTEMPT_ROOT_TEMPLATE/finalization/terminal-wrapper.signature.json
ATTEMPT_ROOT_TEMPLATE/failure/revoked-partial-close-disposition.payload.json
ATTEMPT_ROOT_TEMPLATE/failure/revoked-partial-close-disposition.signature.json
ATTEMPT_ROOT_TEMPLATE/failure/expired-partial-close-disposition.payload.json
ATTEMPT_ROOT_TEMPLATE/failure/expired-partial-close-disposition.signature.json
ATTEMPT_ROOT_TEMPLATE/failure/crashed-partial-close-disposition.payload.json
ATTEMPT_ROOT_TEMPLATE/failure/crashed-partial-close-disposition.signature.json
ATTEMPT_ROOT_TEMPLATE/failure/timed-out-partial-close-disposition.payload.json
ATTEMPT_ROOT_TEMPLATE/failure/timed-out-partial-close-disposition.signature.json
```

`WRAPPER_PUBLICATION` has exactly one selected output spec, the terminal
wrapper signature. Every disposition has exactly two specs, payload then
signature. `selected_output_spec_digest` hashes only the ordered pre-CAS
specifications; it is never a content/output digest. All other kind/count
combinations reject.

The dependency order is acyclic:

```text
WrapperSelectionIntentV2
→ wrapper_selection_intent_digest
→ TransitionCoreV2
→ core_digest
→ transition_id
→ deterministic output bytes/content SHA/idempotency key
→ CAS selection + immutable outbox
→ WrapperSelectionReceiptV2
→ optional outbox claim lease
```

`WrapperSelectionReceiptV2` is post-commit and has exact body
`schema,wrapper_selection_intent_digest,transition_id,core_digest,
aggregate_key_digest,aggregate_pre_token,aggregate_post_token,
selected_output_kind,selected_output_spec_count,selected_output_spec_digest,
materialized_output_digest_or,final_adverse_ref,event_set_digest,
selection_linearized_at,functional_obligation_outbox_or,
seal_obligation_outbox_or,cas_service_actor_id,cas_service_physical_sha`.
`functional_obligation_outbox_or` is always `NEW_FUNCTIONAL_OUTBOX` with both
digests for a materialized selection. `seal_obligation_outbox_or` is always
`NOT_YET_SEAL_ELIGIBLE`; only the later original terminal attestation queues
the seal. The service signature uses domain
`WS-WALKSAFE-R007-WRAPPER-SELECTION-RECEIPT-V2`, NUL and body JCS. Receipt
bytes exclude their own SHA/record Physical.

`materialized_output_digest_or` is tagged
`NEW_OUTPUT_ENTRY_DIGEST(output_entry_digest)` after deterministic
construction. No post-construction digest enters the selection intent or
transition-ID preimage.

Existing exact output recovery is outside wrapper selection and has the exact
three original-outbox modes:

```text
FSM027A: current aggregate/attempt original batch CLAIMED plus exact file
  → run OBX003 on the same transition/core/obligation/outbox;
    new selection/core/outbox/write/seal = 0/0/0/0/0
FSM027B: original batch PUBLISHED
  → run OBX008 store-only; the original terminal CAS queues exactly one
    original seal outbox; recovery-created records = 0
FSM027C: original batch ATTESTED and attempt SEAL_QUEUED
  → resume the exact original seal outbox; new selection/core/functional
    outbox/seal outbox/write = 0/0/0/0/0
```

All three require byte-equal aggregate key, namespace, attempt ID,
transition/core, obligation/outbox, output paths and immutable bytes. Cross-
aggregate or path-only reuse rejects. `EXPIRED` cannot be adopted and an exact
file without its current original batch is signed collision evidence.

Publication lease ID/token/not-after and claimant Physical exist only in the
later `OutboxBatchStateRecordV2 ACTIVE_CLAIM` CAS generation. They never enter
the selection intent, core digest or transition-ID preimage.

Selection after a revoke/expiry pending state uses the same immutable selected
cause tuple stored by `FSM009`. Selection after wrapper consume makes every
later revoke/expiry `effect=NONE`, token/receipt/lease/write `0`.

Exact race schedules:

| schedule | linearization | selected output | final adverse ref | new functional writes |
|---|---|---|---|---:|
| `WR-RACE-001` | consume/select before revoke | wrapper | `NO_ADVERSE` | 1 |
| `WR-RACE-002` | revoke before select, partial close exists | revoked disposition pair | exact revoke receipt | 2 |
| `WR-RACE-003` | equal effective time | priority selects revoke before wrapper normal event | exact revoke receipt | 2 |
| `WR-RACE-004` | expiry before select | expired disposition pair | exact expiry source | 2 |
| `WR-RACE-005` | CRASH before select | crashed disposition pair | exact CRASH pair | 2 |
| `WR-RACE-006` | settlement timeout before select | timed-out disposition pair | exact timeout receipt | 2 |
| `WR-RACE-007A` | original batch `CLAIMED`, exact terminal file exists | reconcile/attest same original batch | original outbox ref | 0 |
| `WR-RACE-007B` | original batch `PUBLISHED` | store-only attest same original batch | original outbox ref | 0 |
| `WR-RACE-007C` | original batch `ATTESTED` | resume same original seal batch | original outbox ref | 0 |

Each schedule ends with one terminal outcome and one queued seal. Crash before,
during or after create-exclusive resumes the same outbox. Same output retry
creates `0` additional physical files.

The exact-one invariant is:

```text
new wrapper logical cardinality
+ new disposition-pair logical cardinality
+ existing original terminal logical cardinality
= 1 logical terminal outcome
```

The pair counts as one logical selected outcome and two project files. Any
second contender after selection is `FSM034` and has no effect.

## 5. graph projection, expansion, PostG7와 G3

이 절은 GT/EO/ET, expansion lineage와 full-projection scope를 닫는다.

`R007DigestV3(D,V) = SHA256(ASCII(D) || 0x00 || RFC8785_JCS(V))`이다.
Every ordered-row digest below first requires contiguous zero-based ordinal,
unique ID, unique resolved edge tuple, declared count equal to array length
and supplied order byte-equal to registry order; the hash step never silently
resorts. The closed constructor registry is:

| identity | literal domain | exact preimage |
|---|---|---|
| GT edge set | `WS-WALKSAFE-R007-GT-EDGE-SET-V3` | `{cut_id,set_kind,ordered_edge_ids}` where set kind is one of `STATIC`, `MATERIALIZED`, `NEW_WRITE` |
| manifest nodes | `WS-WALKSAFE-R007-DEPENDENCY-MANIFEST-NODE-ROWS-V3` | full ordered `DependencyManifestNodeSpecV3` rows |
| manifest edges | `WS-WALKSAFE-R007-DEPENDENCY-MANIFEST-EDGE-ROWS-V3` | full ordered `DependencyManifestEdgeSpecV3` rows |
| expansion nodes | `WS-WALKSAFE-R007-DEPENDENCY-EXPANSION-NODE-ROWS-V3` | full ordered selected Node rows |
| expansion edges | `WS-WALKSAFE-R007-DEPENDENCY-EXPANSION-EDGE-ROWS-V3` | full ordered selected Edge rows |
| finalization lineage | `WS-WALKSAFE-R007-FINALIZATION-LINEAGE-PREFIX-V3` | seven ordered predecessor bindings from §5.2 |
| PostG7 scope | `WS-WALKSAFE-R007-POSTG7-WRITE-SCOPE-V3` | exact two full scope rows |
| PostG7 projection | `WS-WALKSAFE-R007-POSTG7-PROJECTION-ROWS-V3` | ordered `PostG7ProjectionRowV3` rows |
| B04 variants | `WS-WALKSAFE-R007-B04-VARIANT-ROWS-V3` | full ordered variant rows |
| B04 B06 inputs | `WS-WALKSAFE-R007-B04-B06-OUTPUT-ROWS-V3` | full ordered ten B06 output rows |
| B04 fixture variants | `WS-WALKSAFE-R007-B04-VARIANT-CASE-ROWS-V3` | full ordered fixture-variant rows |
| B04 negatives | `WS-WALKSAFE-R007-B04-NEGATIVE-CASE-IDS-V3` | ordered literal negative-case IDs |
| B04 H1 edges | `WS-WALKSAFE-R007-B04-H1-EDGE-ROWS-V3` | all 23 full Edge rows |

ID-only hashes may be used only where the preimage column explicitly says
IDs. Every context, output, predecessor set, obligation, immutable batch,
mutable state row, publication evidence, expansion projection and
idempotency set also has one schema-specific literal domain, NUL and full JCS
constructor registered before implementation; omitted/alternate domains and
field projections are fixture failures.

### 5.1 GT, EO and ET full vectors

Vector dimensions are fixed:

```text
GT_PRECAS = cardinality(GT_PRECAS_IDS)
GT_NORMAL = cardinality(GT_NORMAL_IDS)
EO = cardinality(EO_IDS)
ET = [ET001,ET002,ET003,ET004,ET005]
```

`ET001=CAS-PRECLOSE→selector`,
`ET002=close-recovery-consume→selector`,
`ET003=selected adverse source→selector`,
`ET004=selector→selected terminal`,
`ET005=selected terminal→finalization consume`.

Every runtime cut also carries two required `GtAvailabilityV3` values:

```text
{"kind":"AVAILABLE","authority_ref":ArtifactBindingV2}
XOR
{"kind":"UNAVAILABLE","reason_code":Identifier,
 "signed_source_ref":ArtifactBindingV2}
```

Null, boolean, omitted authority and an untagged reason are invalid. The
displayed cut table below is the base `AVAILABLE/AVAILABLE` projection. The
closed expansion rule generates the availability cross-product without a
numeric seed: close-recovery `UNAVAILABLE` selects `FSM013`, sets `ET002=0`
and materializes direct edge `GTU001` from its signed unavailable disposition
to the fail-closed selector; finalization `UNAVAILABLE` selects `FSM017`, sets
`ET005=0` and materializes direct edge `GTU002` from its signed unavailable
disposition to the terminal core. When both are unavailable, both zeros and
both edges occur. All remaining displayed dimensions are unchanged. The
availability tags and full source refs are included in static/materialized/
new-write digest preimages and have positive/negative cross-product fixtures.

The ordered literal ID arrays are:

```text
GT_PRECAS_IDS =
  [GT016,GT017,GT018,GT019,GT020,GT021,GT022,GT023,GT024,GT025,
   GT026,GT027,GT028,GT029,GT030]
GT_NORMAL_IDS =
  [GT031,GT032,GT033,GT034,GT035,GT036,GT037,GT038,GT039,GT040,
   GT041,GT042,GT043,GT044,GT045]
EO_IDS =
  [EO001,EO002,EO003,EO004,EO005,EO006,EO007,EO008,EO009,EO010,
   EO011,EO012,EO013,EO014,EO015]
ET_IDS = [ET001,ET002,ET003,ET004,ET005]
```

For integer `k`, `EO_PREFIX(k)` means the first exactly `k` rows of the
literal `EO_IDS` array (`EO_PREFIX(0)=[]`); it is a registry slice operation,
not an ID range. The numeric projection vector is:

```text
[GT_PRECAS_count,GT_NORMAL_count,EO_count,
 ET001,ET002,ET003,ET004,ET005]
```

Its static vector is always `[15,15,15,1,1,1,1,1]`. Materialized and
new-write vectors are:

| cut_id | exact runtime condition | materialized vector | new-write vector |
|---|---|---|---|
| `GT-CUT-01-K00` | result 0, CAS absent, recovery | `[0,0,0,0,1,1,1,1]` | `[0,0,0,0,1,1,1,1]` |
| `GT-CUT-01-K01` | result 1, CAS absent, recovery | `[0,0,1,0,1,1,1,1]` | `[0,0,1,0,1,1,1,1]` |
| `GT-CUT-01-K02` | result 2, CAS absent, recovery | `[0,0,2,0,1,1,1,1]` | `[0,0,2,0,1,1,1,1]` |
| `GT-CUT-01-K03` | result 3, CAS absent, recovery | `[0,0,3,0,1,1,1,1]` | `[0,0,3,0,1,1,1,1]` |
| `GT-CUT-01-K04` | result 4, CAS absent, recovery | `[0,0,4,0,1,1,1,1]` | `[0,0,4,0,1,1,1,1]` |
| `GT-CUT-01-K05` | result 5, CAS absent, recovery | `[0,0,5,0,1,1,1,1]` | `[0,0,5,0,1,1,1,1]` |
| `GT-CUT-01-K06` | result 6, CAS absent, recovery | `[0,0,6,0,1,1,1,1]` | `[0,0,6,0,1,1,1,1]` |
| `GT-CUT-01-K07` | result 7, CAS absent, recovery | `[0,0,7,0,1,1,1,1]` | `[0,0,7,0,1,1,1,1]` |
| `GT-CUT-01-K08` | result 8, CAS absent, recovery | `[0,0,8,0,1,1,1,1]` | `[0,0,8,0,1,1,1,1]` |
| `GT-CUT-01-K09` | result 9, CAS absent, recovery | `[0,0,9,0,1,1,1,1]` | `[0,0,9,0,1,1,1,1]` |
| `GT-CUT-01-K10` | result 10, CAS absent, recovery | `[0,0,10,0,1,1,1,1]` | `[0,0,10,0,1,1,1,1]` |
| `GT-CUT-01-K11` | result 11, CAS absent, recovery | `[0,0,11,0,1,1,1,1]` | `[0,0,11,0,1,1,1,1]` |
| `GT-CUT-01-K12` | result 12, CAS absent, recovery | `[0,0,12,0,1,1,1,1]` | `[0,0,12,0,1,1,1,1]` |
| `GT-CUT-01-K13` | result 13, CAS absent, recovery | `[0,0,13,0,1,1,1,1]` | `[0,0,13,0,1,1,1,1]` |
| `GT-CUT-01-K14` | result 14, CAS absent, recovery | `[0,0,14,0,1,1,1,1]` | `[0,0,14,0,1,1,1,1]` |
| `GT-CUT-02` | result 15, CAS absent, recovery | `[0,0,15,0,1,1,1,1]` | `[0,0,15,0,1,1,1,1]` |
| `GT-CUT-03` | result 15, CAS present-unwrapped, recovery | `[15,0,15,1,1,1,1,1]` | `[15,0,15,1,1,1,1,1]` |
| `GT-CUT-04` | result 15, CAS present-unwrapped, NORMAL | `[15,15,0,1,0,0,1,1]` | `[15,15,0,1,0,0,1,1]` |
| `GT-CUT-05N` | exact wrapper exists, stored profile `NORMAL_15` | `[15,15,0,1,0,0,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K00` | existing stored recovery profile, result 0 | `[0,0,0,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K01` | existing stored recovery profile, result 1 | `[0,0,1,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K02` | existing stored recovery profile, result 2 | `[0,0,2,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K03` | existing stored recovery profile, result 3 | `[0,0,3,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K04` | existing stored recovery profile, result 4 | `[0,0,4,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K05` | existing stored recovery profile, result 5 | `[0,0,5,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K06` | existing stored recovery profile, result 6 | `[0,0,6,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K07` | existing stored recovery profile, result 7 | `[0,0,7,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K08` | existing stored recovery profile, result 8 | `[0,0,8,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K09` | existing stored recovery profile, result 9 | `[0,0,9,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K10` | existing stored recovery profile, result 10 | `[0,0,10,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K11` | existing stored recovery profile, result 11 | `[0,0,11,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K12` | existing stored recovery profile, result 12 | `[0,0,12,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K13` | existing stored recovery profile, result 13 | `[0,0,13,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K14` | existing stored recovery profile, result 14 | `[0,0,14,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-A-K15` | existing stored recovery profile, result 15 | `[0,0,15,0,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |
| `GT-CUT-05R-U` | exact wrapper exists, stored `RECOVERY_UNWRAPPED_15` | `[15,0,15,1,1,1,1,1]` | `[0,0,0,0,0,0,0,0]` |

The frozen cut registry stores all rows displayed above. Each materialized set
is exactly the concatenation of the
literal arrays selected by nonzero dimensions and `EO_PREFIX(k)`.

`GtCutIdV2` is the closed tagged type:

```text
{"kind":"CAS_ABSENT_RECOVERY_PREFIX","result_count":integer 0..14}
{"kind":"CAS_ABSENT_RECOVERY_COMPLETE"}
{"kind":"UNWRAPPED_RECOVERY"}
{"kind":"UNWRAPPED_NORMAL"}
{"kind":"ADOPTED_NORMAL"}
{"kind":"ADOPTED_RECOVERY_PREFIX","result_count":integer 0..15}
{"kind":"ADOPTED_RECOVERY_UNWRAPPED"}
```

Its canonical ASCII encoder maps those variants respectively to
`GT-CUT-01-K` plus a two-digit zero-padded count, `GT-CUT-02`, `GT-CUT-03`,
`GT-CUT-04`, `GT-CUT-05N`, `GT-CUT-05R-A-K` plus a two-digit zero-padded
count, and `GT-CUT-05R-U`. No other string, Unicode normalization, decimal
width or case is accepted.

Wrapped adoption must contain one closed
`StoredSelectorProfileV2={profile_id,result_count,cas_state,
GT_PRECAS,GT_NORMAL,EO,ET,ordered_edge_ids,edge_set_digest}` and reproduce
that row byte-exact. It may not relabel recovery as normal. Adoption performs
no selection/outbox/functional project write.

Each row records:

```text
static_edge_ids
materialized_edge_ids
new_write_edge_ids
static_edge_set_digest
materialized_edge_set_digest
new_write_edge_set_digest
```

Each of the three digests uses the §5 constructor
`R007DigestV3("WS-WALKSAFE-R007-GT-EDGE-SET-V3",
{cut_id,set_kind,close_recovery_availability,
finalization_availability,ordered_edge_ids})`; the set kind distinguishes
`STATIC`, `MATERIALIZED` and `NEW_WRITE`, and the cut ID is its literal
validated canonical encoding. Result
count below 15 with a CAS, above 15, ambiguous CAS state, wrong stored profile
or terminal mismatch is invalid. ET004 is exact `1` on a newly selected
terminal branch. ET005 is `1` only when finalization availability is
`AVAILABLE`; `FSM017` requires `0`.

### 5.2 mandatory finalization consume→expansion lineage

The predecessor manifest pair uses these canonical role IDs:

| role | literal path template | schema | publisher |
|---|---|---|---|
| `DEPENDENCY-EDGE-MANIFEST-PAYLOAD` | `ATTEMPT_ROOT_TEMPLATE/finalization/dependency-edge-manifest.payload.json` | `DEPENDENCY_EDGE_MANIFEST_PAYLOAD_V2` | `DEPENDENCY_MANIFEST_SERIALIZER` |
| `DEPENDENCY-EDGE-MANIFEST-SIGNATURE` | `ATTEMPT_ROOT_TEMPLATE/finalization/dependency-edge-manifest.signature.json` | `DEPENDENCY_EDGE_MANIFEST_SIGNATURE_V2` | `DEPENDENCY_MANIFEST_SIGNER` |

The formerly conceptual lineage endpoints are exact primary rows:

| node ID | role instance or node kind | exact path/key | schema | publisher |
|---|---|---|---|---|
| `N-SELECTED-TERMINAL-ATTESTATION` | `SELECTED-EXECUTION-TERMINAL-ATTESTATION` | CAS subrecord `TERMINAL_SETTLEMENT_ATTESTATION` | `PUBLICATION_ATTESTATION_V3` | `AUTHORITY_AGGREGATE_CAS_SERVICE` |
| `N-FINALIZATION-CONSUME-INTENT` | `FINALIZATION-CONSUME-INTENT` | `AUTH_ROOT_TEMPLATE/intents/post-close-finalization-consume.intent.json` | `FINALIZATION_CONSUME_INTENT_V3` | `FINALIZATION_CONSUME_SERIALIZER` |
| `N-FINALIZATION-CONSUME-CAS` | `CAS_TRANSITION`, no role | aggregate transition key | no project content | `AUTHORITY_AGGREGATE_CAS_SERVICE` |
| `N-FINALIZATION-CONSUME-RECEIPT` | `FINALIZATION-CONSUME-TRANSITION-RECEIPT` | CAS subrecord `FINALIZATION_CONSUME_RECEIPT` | `TRANSITION_RECEIPT_V2` | same CAS service |
| `N-FINALIZATION-WORK-OBLIGATION` | `FINALIZATION-WORK-OBLIGATION` | CAS subrecord `FINALIZATION_WORK_OBLIGATION`, slot 5 | `SETTLEMENT_OBLIGATION_V2` | same CAS service |
| `N-FINALIZATION-WORK-OUTBOX` | `FINALIZATION-WORK-OUTBOX-BATCH` | CAS subrecord `FINALIZATION_WORK_OUTBOX`, slot 5 | `OUTBOX_BATCH_V2` | same CAS service |
| `N-PREFIX-FAILURE-CHECKPOINT-PAYLOAD` | `FINALIZATION-PREFIX-FAILURE-CHECKPOINT-PAYLOAD` | `ATTEMPT_ROOT_TEMPLATE/finalization/finalization-prefix-failure-checkpoint.payload.json` | `FINALIZATION_PREFIX_FAILURE_CHECKPOINT_PAYLOAD_V3` | `FINALIZATION_CHECKPOINT_SERIALIZER` |
| `N-PREFIX-FAILURE-CHECKPOINT-SIGNATURE` | `FINALIZATION-PREFIX-FAILURE-CHECKPOINT-SIGNATURE` | `ATTEMPT_ROOT_TEMPLATE/finalization/finalization-prefix-failure-checkpoint.signature.json` | `FINALIZATION_PREFIX_FAILURE_CHECKPOINT_SIGNATURE_V3` | `FINALIZATION_CHECKPOINT_SIGNER` |

The CAS subrecord key is
`{schema:"R007_CAS_SUBRECORD_KEY_V3",aggregate_key_digest,transition_id,
record_kind,outbox_slot_or}` and its digest is
`R007DigestV3("WS-WALKSAFE-R007-CAS-SUBRECORD-KEY-V3",full object)`.
`record_kind` is the exact four-value set shown above; only the obligation and
outbox alternatives carry `{"kind":"SLOT","value":5}`, while the other two
carry `{"kind":"NO_SLOT"}`.

`FinalizationWorkObligationProjectionV3` contains only
`schema,transition_id,core_digest,aggregate_key_digest,outbox_slot=5,
branch_id,ordered_output_specs_without_content_sha,output_spec_count,
output_spec_digest,selected_WORK_deadline_profile,idempotency_projection`.
It excludes expansion payload/signature, every final content SHA, obligation
digest, outbox digest and its own digest. Its identity is exactly
`R007DigestV3("WS-WALKSAFE-R007-FINALIZATION-WORK-OBLIGATION-PROJECTION-V3",
full projection)`.

The payload exact fields are
`schema,selected_runtime_branch,ordered_allowed_node_rows:
DependencyManifestNodeSpecV3[],node_count,node_digest,
ordered_allowed_edge_rows:DependencyManifestEdgeSpecV3[],edge_count,edge_digest,
publisher_actor_id,publisher_physical_sha,signature_domain`; its domain is
`WS-WALKSAFE-R007-DEPENDENCY-EDGE-MANIFEST-V2`. The detached signature has
the common payload path/schema/SHA/Physical and signer fields and signs domain,
NUL and full payload JCS.

The two spec types contain stable role/node/edge identity, path, schema,
publisher and branch AST but no runtime content SHA or output Physical.
Therefore manifest payload and signature may appear as future specs without a
self-preimage. Node/edge digests use their full-row §5 domains, not ID arrays.

`DependencyExpansionPayloadV2` exact lineage fields:

```text
schema
selected_terminal_attestation: ArtifactBindingV2
finalization_grant_payload: ArtifactBindingV2
finalization_grant_signature: ArtifactBindingV2
finalization_consume_intent: ArtifactBindingV2
finalization_consume_transition_receipt: ArtifactBindingV2
finalization_work_obligation: ArtifactBindingV2
finalization_work_outbox: ArtifactBindingV2
dependency_manifest_payload: ArtifactBindingV2
dependency_manifest_signature: ArtifactBindingV2
finalization_lineage_prefix_digest
selected_runtime_branch
ordered_expanded_node_rows + expanded_node_count + expanded_node_digest
ordered_expanded_edge_rows + expanded_edge_count + expanded_edge_digest
publisher_actor_id + publisher_physical_sha
```

There is no activation alternative. `finalization_work_obligation_digest`
means the §5.2 projection digest and excludes expansion/final content.
`finalization_lineage_prefix_digest` hashes exactly these seven ordered rows:
selected terminal attestation, finalization grant payload, finalization grant
signature, finalization consume intent, finalization consume receipt,
finalization work obligation, finalization work outbox. Each row is
`{ordinal:0..6,kind,binding}` and the domain is the §5 lineage domain.

Expansion publication is the exact pair:

| role | literal path template | schema | publisher |
|---|---|---|---|
| `DEPENDENCY-EXPANSION-PAYLOAD` | `ATTEMPT_ROOT_TEMPLATE/finalization/dependency-expansion.payload.json` | `DEPENDENCY_EXPANSION_PAYLOAD_V2` | `DEPENDENCY_EXPANSION_SERIALIZER` |
| `DEPENDENCY-EXPANSION-SIGNATURE` | `ATTEMPT_ROOT_TEMPLATE/finalization/dependency-expansion.signature.json` | `DEPENDENCY_EXPANSION_DETACHED_SIGNATURE_V2` | `DEPENDENCY_EXPANSION_SIGNER` |

The signature exact fields are
`schema,payload_path,payload_schema_sha,payload_sha,payload_physical,
signer_actor_id,signer_physical_sha,signature_algorithm,signature_domain,
signature`. The domain is
`WS-WALKSAFE-R007-DEPENDENCY-EXPANSION-PAYLOAD-V2`; signing input is domain,
NUL and full payload JCS. Both role rows carry concrete publisher Physical and
output `FilePhysicalV2` in the runtime registry.

Direct edges:

```text
DM001 DEPENDENCY-EDGE-MANIFEST-PAYLOAD →
      DEPENDENCY-EDGE-MANIFEST-SIGNATURE
FL001 SELECTED-EXECUTION-TERMINAL-ATTESTATION →
      FINALIZATION-CONSUME-INTENT
FL002 FINALIZATION-GRANT-SIGNATURE → FINALIZATION-CONSUME-INTENT
FL003 FINALIZATION-CONSUME-INTENT → FINALIZATION-CONSUME-CAS
FW001 FINALIZATION-CONSUME-CAS → FINALIZATION-CONSUME-TRANSITION-RECEIPT
FW002 FINALIZATION-CONSUME-CAS → FINALIZATION-WORK-OBLIGATION
FW003 FINALIZATION-CONSUME-CAS → FINALIZATION-WORK-OUTBOX-BATCH
EP001 SELECTED-EXECUTION-TERMINAL-ATTESTATION →
      DEPENDENCY-EXPANSION-PAYLOAD
EP002 DEPENDENCY-EDGE-MANIFEST-SIGNATURE → DEPENDENCY-EXPANSION-PAYLOAD
EP003 DEPENDENCY-EXPANSION-PAYLOAD → DEPENDENCY-EXPANSION-SIGNATURE
EP005 FINALIZATION-CONSUME-TRANSITION-RECEIPT →
      DEPENDENCY-EXPANSION-PAYLOAD
EP006 FINALIZATION-WORK-OBLIGATION → DEPENDENCY-EXPANSION-PAYLOAD
EP007 FINALIZATION-WORK-OUTBOX-BATCH → DEPENDENCY-EXPANSION-PAYLOAD
EXC001 DEPENDENCY-EXPANSION-SIGNATURE → T01-COMPLETION
EXC002 DEPENDENCY-EXPANSION-SIGNATURE → T02-COMPLETION
EXC003 DEPENDENCY-EXPANSION-SIGNATURE → T03-COMPLETION
EXC004 DEPENDENCY-EXPANSION-SIGNATURE → T04-COMPLETION
EXC005 DEPENDENCY-EXPANSION-SIGNATURE → T05-COMPLETION
EXC006 DEPENDENCY-EXPANSION-SIGNATURE → T06-COMPLETION
EXC007 DEPENDENCY-EXPANSION-SIGNATURE → T07-COMPLETION
EXC008 DEPENDENCY-EXPANSION-SIGNATURE → T08-COMPLETION
EXC009 DEPENDENCY-EXPANSION-SIGNATURE → T09-COMPLETION
EXC010 DEPENDENCY-EXPANSION-SIGNATURE → T10-COMPLETION
EXC011 DEPENDENCY-EXPANSION-SIGNATURE → T11-COMPLETION
EXC012 DEPENDENCY-EXPANSION-SIGNATURE → T12-COMPLETION
EXC013 DEPENDENCY-EXPANSION-SIGNATURE → T13-COMPLETION
EXC014 DEPENDENCY-EXPANSION-SIGNATURE → T14-COMPLETION
EXC015 DEPENDENCY-EXPANSION-SIGNATURE → T15-COMPLETION
EXF001 DEPENDENCY-EXPANSION-SIGNATURE →
       FINALIZATION-PREFIX-FAILURE-CHECKPOINT-PAYLOAD
EXF002 FINALIZATION-PREFIX-FAILURE-CHECKPOINT-PAYLOAD →
       FINALIZATION-PREFIX-FAILURE-CHECKPOINT-SIGNATURE
```

The 15 completion endpoints are literal primary RoleInstance/Node rows:

| role/node ID | literal path | schema | publisher |
|---|---|---|---|
| `T01-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t01/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T01_FINALIZER` |
| `T02-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t02/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T02_FINALIZER` |
| `T03-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t03/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T03_FINALIZER` |
| `T04-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t04/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T04_FINALIZER` |
| `T05-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t05/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T05_FINALIZER` |
| `T06-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t06/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T06_FINALIZER` |
| `T07-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t07/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T07_FINALIZER` |
| `T08-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t08/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T08_FINALIZER` |
| `T09-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t09/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T09_FINALIZER` |
| `T10-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t10/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T10_FINALIZER` |
| `T11-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t11/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T11_FINALIZER` |
| `T12-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t12/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T12_FINALIZER` |
| `T13-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t13/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T13_FINALIZER` |
| `T14-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t14/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T14_FINALIZER` |
| `T15-COMPLETION` | `ATTEMPT_ROOT_TEMPLATE/v1/tasks/t15/completion.receipt.json` | `V1_TASK_COMPLETION_RECEIPT_V3` | `T15_FINALIZER` |

Every runtime row adds exact schema SHA, publisher executable Physical SHA,
content SHA and output `FilePhysicalV2`; no conceptual target is accepted.

`EP004` does not exist in R003; `G3E002` is the sole canonical resolved tuple
from expansion signature to the G3 check. The fifteen `EXC` rows materialize on the finalization-consumed completion
branch. `EXF001` materializes exact 1 only for a bounded prefix failure after
expansion exists; success and pre-expansion failure use `0`.
Activation-only, wrong consume receipt/Physical, wrong grant, wrong
obligation or wrong prefix digest is rejected.

### 5.3 PostG7 rooted exact-two roles and phase-equal scope

Two static templates:

| ordinal | role_id | literal path template | schema role | publisher role | Physical |
|---:|---|---|---|---|---|
| 0 | `POSTG7-FULL-PROJECTION-PAYLOAD` | `ATTEMPT_ROOT_TEMPLATE/ready/post-g7-full-projection-check.payload.json` | `POST_G7_FULL_PROJECTION_CHECK_PAYLOAD_V2` | `POST_G7_PROJECTION_SERIALIZER` | serializer `FilePhysicalV2` |
| 1 | `POSTG7-FULL-PROJECTION-SIGNATURE` | `ATTEMPT_ROOT_TEMPLATE/ready/post-g7-full-projection-check.signature.json` | `POST_G7_FULL_PROJECTION_CHECK_DETACHED_SIGNATURE_V2` | `POST_G7_PROJECTION_SIGNER` | signer `FilePhysicalV2` |

At namespace freeze, `ATTEMPT_ROOT_TEMPLATE` is expanded to two runtime
literal paths and both schema SHA and publisher Physical SHA are non-empty.
Finalization `SUCCESS_SUFFIX_CANDIDATE` write scope contains these exact rows:

```text
ordinal,role_id,access=WRITE,literal_path,schema_role,schema_sha,
publisher_actor_id,publisher_physical_sha,physical_kind=FILE,
branch_predicate_id=FINALIZATION_SUCCESS,exact_cardinality=1
```

The ordered two-row bytes, count `2` and digest with domain
`WS-WALKSAFE-R007-POSTG7-WRITE-SCOPE-V2` are embedded byte-equal in:

```text
authority request context
authority response context
ALLOW decision context
post-close-finalization grant payload
post-close-finalization grant wrapper
activation input
activation receipt role row
finalization consume intent
finalization consume CAS core
finalization consume transition receipt
```

Success runtime cardinality is `2`; failure is `0`. Global allowlist presence
without this role scope is not authority.

Payload exact fields:

```text
schema
profile = THROUGH_READY_EVALUATION_PAYLOAD_V3
activated_finalization_context
finalization_consume_transition_receipt_sha + Physical
g3_result_payload_sha + wrapper_sha + both Physical
g6_disposition_payload_sha + wrapper_sha + both Physical
p7_review_pair_shas[2] + both Physical
g7_result_payload_sha + wrapper_sha + both Physical
ready_evaluation_payload: ArtifactBindingV2
target_boundary_node_id = READY-EVALUATION-PAYLOAD
selected_runtime_branch
ordered_actual_rows: PostG7ProjectionRowV3[]
actual_count + actual_digest
ordered_projected_rows: PostG7ProjectionRowV3[]
projected_count + projected_digest
equality_result = PASS
publisher_actor_id + publisher_physical_sha
signature_domain =
  WS-WALKSAFE-R007-POST-G7-FULL-PROJECTION-PAYLOAD-V2
```

`PostG7ProjectionRowV3` is a tagged full row: either
`{"kind":"NODE","ordinal":Ordinal,"node":NodeRegistryRowV3}` or
`{"kind":"EDGE","ordinal":Ordinal,"edge":EdgeRegistryRowV3}`. Nodes appear
in registry order followed by edges in registry order; ordinals are contiguous
across the union and duplicates reject. The payload excludes its own pair,
finalization close, Ready signature and every later artifact. Both digests use
the common §5 PostG7 projection domain.

Detached signature schema uses the common payload path/schema/SHA/Physical,
signer actor/Physical, algorithm/domain/signature fields. Signing input is the
literal domain above, NUL and full payload JCS.

Direct chain:

```text
GE038 G7-RESULT-SIGNATURE → READY-EVALUATION-PAYLOAD
GE039 READY-EVALUATION-PAYLOAD → POSTG7-FULL-PROJECTION-PAYLOAD
GE040 POSTG7-FULL-PROJECTION-PAYLOAD →
      POSTG7-FULL-PROJECTION-SIGNATURE
GE041 POSTG7-FULL-PROJECTION-SIGNATURE → FINALIZATION-CLOSE
GE042 FINALIZATION-CONSUME-TRANSITION-RECEIPT → FINALIZATION-CLOSE
GE043 FINALIZATION-CLOSE → FINALIZATION-CLOSE-RECEIPT
GE044 FINALIZATION-CLOSE-RECEIPT → READY-EVALUATION-SIGNATURE
GE045 READY-EVALUATION-PAYLOAD → READY-EVALUATION-SIGNATURE
```

`FINALIZATION-CLOSE` directly compares the same payload SHA, signature SHA and
both `FilePhysicalV2`. Ready signature is constructed only after the close
receipt, so the order is
`Ready payload→PostG7 pair→finalization close→Ready signature` and the graph
has no future/self SCC. Fixtures reject missing pair, wrong schema, wrong
publisher, wrong publisher Physical, allowlist-only substitution, one-stage
scope substitution and GE/close SHA or Physical drift.

### 5.4 G3 current-expansion profile

Only these profiles exist:

```text
CURRENT_RUNTIME_EXPANSION_VALIDATION_V2
THROUGH_READY_EVALUATION_PAYLOAD_V3
```

G3 uses the first and directly binds:

```text
dependency edge manifest payload/wrapper SHA + Physical
dependency expansion payload/wrapper SHA + Physical
selected RuntimeBranchEnum value
manifest ordered node/edge sets and digests
expansion ordered node/edge sets and digests
```

It executes four independent predicates:

```text
G3-PRED-01 subset =
  expansion nodes/edges are a subset of signed manifest rows
G3-PRED-02 cardinality =
  expansion counts equal recomputed array lengths and branch projection count
G3-PRED-03 order =
  expansion rows equal registry ordinal order byte-for-byte
G3-PRED-04 digest =
  recomputed node/edge digests equal expansion and manifest projection digests
```

G3 cannot read G4/G5/G6/P7/G7/Ready/PostG7 artifacts. Full actual/projected
equality belongs only to the second profile in the PostG7 payload.
`FX-R007-M004-G3-TIMING` contains positive vectors and one failure for each
predicate, plus missing/wrong manifest, expansion wrapper and branch binding.

G3 is materialized as four exact roles:

| role | literal path template | schema | publisher |
|---|---|---|---|
| `G3-CURRENT-EXPANSION-CHECK-PAYLOAD` | `ATTEMPT_ROOT_TEMPLATE/finalization/g3-current-expansion-check.payload.json` | `G3_CURRENT_EXPANSION_CHECK_PAYLOAD_V2` | `G3_SPEC_SERIALIZER` |
| `G3-CURRENT-EXPANSION-CHECK-SIGNATURE` | `ATTEMPT_ROOT_TEMPLATE/finalization/g3-current-expansion-check.signature.json` | `G3_CURRENT_EXPANSION_CHECK_SIGNATURE_V2` | `G3_SPEC_SIGNER` |
| `G3-CURRENT-EXPANSION-RESULT-PAYLOAD` | `ATTEMPT_ROOT_TEMPLATE/finalization/g3-current-expansion-result.payload.json` | `G3_CURRENT_EXPANSION_RESULT_PAYLOAD_V2` | `G3_INDEPENDENT_CHECKER` |
| `G3-CURRENT-EXPANSION-RESULT-SIGNATURE` | `ATTEMPT_ROOT_TEMPLATE/finalization/g3-current-expansion-result.signature.json` | `G3_CURRENT_EXPANSION_RESULT_SIGNATURE_V2` | `G3_RESULT_SIGNER` |

`G3CurrentExpansionCheckPayloadV2` exact fields:

```text
schema
profile=CURRENT_RUNTIME_EXPANSION_VALIDATION_V2
dependency_manifest_payload_sha + dependency_manifest_signature_sha
dependency_manifest_payload_physical + dependency_manifest_signature_physical
dependency_expansion_payload_sha + dependency_expansion_signature_sha
dependency_expansion_payload_physical + dependency_expansion_signature_physical
selected_runtime_branch
manifest_node_count + manifest_node_digest
manifest_edge_count + manifest_edge_digest
expansion_node_count + expansion_node_digest
expansion_edge_count + expansion_edge_digest
publisher_actor_id + publisher_physical_sha
signature_domain=WS-WALKSAFE-R007-G3-CURRENT-EXPANSION-CHECK-V2
```

`G3CurrentExpansionResultPayloadV2` exact fields:

```text
schema
profile=CURRENT_RUNTIME_EXPANSION_VALIDATION_V2
check_payload_sha + check_signature_sha
check_payload_physical + check_signature_physical
ordered_recomputed_expansion_node_rows[]
ordered_recomputed_expansion_edge_rows[]
recomputed_node_count + recomputed_node_digest
recomputed_edge_count + recomputed_edge_digest
subset_status=PASS|FAIL
cardinality_status=PASS|FAIL
order_status=PASS|FAIL
digest_status=PASS|FAIL
result_status=PASS|FAIL
checked_at
checker_actor_id + checker_physical_sha
signature_domain=WS-WALKSAFE-R007-G3-CURRENT-EXPANSION-RESULT-V2
```

Both detached signatures use the common exact fields and sign domain, NUL and
their full payload JCS. The result is PASS iff all four statuses are PASS.
Direct edges are:

```text
G3E001 DEPENDENCY-EDGE-MANIFEST-SIGNATURE →
        G3-CURRENT-EXPANSION-CHECK-PAYLOAD
G3E002 DEPENDENCY-EXPANSION-SIGNATURE →
        G3-CURRENT-EXPANSION-CHECK-PAYLOAD
G3E003 G3-CURRENT-EXPANSION-CHECK-PAYLOAD →
        G3-CURRENT-EXPANSION-CHECK-SIGNATURE
G3E004 G3-CURRENT-EXPANSION-CHECK-SIGNATURE →
        G3-CURRENT-EXPANSION-RESULT-PAYLOAD
G3E005 G3-CURRENT-EXPANSION-RESULT-PAYLOAD →
        G3-CURRENT-EXPANSION-RESULT-SIGNATURE
```

## 6. B06 constructive H1 correction

이 절은 B06 exact `10 roles / 26 edges`를 닫는다.

### 6.1 output and source identity rows

B06 output path templates:

| ordinal | role_id | suffix under `ATTEMPT_ROOT_TEMPLATE/v1/stage-c/b06/` | schema | publisher | output Physical |
|---:|---|---|---|---|---|
| 0 | `B06-N26-PAYLOAD` | `n26.payload.json` | `B06_N26_PAYLOAD_V2` | `P3_B06_N26_SERIALIZER` | `FilePhysicalV2` |
| 1 | `B06-N26-SIGNATURE` | `n26.signature.json` | `B06_N26_DETACHED_SIGNATURE_V2` | `P3_B06_N26_SIGNER` | `FilePhysicalV2` |
| 2 | `B06-T1-PAYLOAD` | `t1.payload.json` | `B06_T1_PAYLOAD_V2` | `P3_B06_T1_SERIALIZER` | `FilePhysicalV2` |
| 3 | `B06-T1-SIGNATURE` | `t1.signature.json` | `B06_T1_DETACHED_SIGNATURE_V2` | `P3_B06_T1_SIGNER` | `FilePhysicalV2` |
| 4 | `B06-X1-PAYLOAD` | `x1.payload.json` | `B06_X1_PAYLOAD_V2` | `P3_B06_X1_SERIALIZER` | `FilePhysicalV2` |
| 5 | `B06-X1-SIGNATURE` | `x1.signature.json` | `B06_X1_DETACHED_SIGNATURE_V2` | `P3_B06_X1_SIGNER` | `FilePhysicalV2` |
| 6 | `B06-REVIEW-PAYLOAD` | `stage-c-review-binding.payload.json` | `B06_STAGE_C_REVIEW_BINDING_PAYLOAD_V2` | `P5_B06_REVIEW_SERIALIZER` | `FilePhysicalV2` |
| 7 | `B06-REVIEW-SIGNATURE` | `stage-c-review-binding.signature.json` | `B06_STAGE_C_REVIEW_BINDING_DETACHED_SIGNATURE_V2` | `P5_B06_REVIEW_SIGNER` | `FilePhysicalV2` |
| 8 | `B06-V1-PAYLOAD` | `v1-binding.payload.json` | `B06_V1_BINDING_PAYLOAD_V2` | `P5_B06_V1_SERIALIZER` | `FilePhysicalV2` |
| 9 | `B06-V1-SIGNATURE` | `v1-binding.signature.json` | `B06_V1_BINDING_DETACHED_SIGNATURE_V2` | `P5_B06_V1_SIGNER` | `FilePhysicalV2` |

Every runtime row contains the fully expanded literal path, non-empty schema
SHA, publisher actor ID, publisher executable Physical SHA and final output
`FilePhysicalV2`. Serializer and signer Physical must differ.

B06 direct source rows:

| source role_id | exact path template | schema | publisher/Physical role |
|---|---|---|---|
| `P0-EXACT26-TABLE` | `ROADMAP_DIR/roadmap-r007-pre-successor-{successor_revision_id}/p0/inventory.payload.json` | `P0_INVENTORY_PAYLOAD_V2` | `P0_INVENTORY_SERIALIZER` |
| `P0-TARGET-MAP` | `ROADMAP_DIR/roadmap-r007-pre-successor-{successor_revision_id}/p0/allowed-delta.json` | `P0_ALLOWED_DELTA_V2` | `P0_ALLOWED_DELTA_SERIALIZER` |
| `P0-TARGET-EQUALITY` | `ROADMAP_DIR/roadmap-r007-pre-successor-{successor_revision_id}/p0/synthetic-derivation-receipt.json` | `P0_SYNTHETIC_DERIVATION_RECEIPT_V2` | `P0_DERIVATION_VERIFIER` |
| `U1-DIR-PHYSICAL-SPEC` | `H1_ROOT_TEMPLATE/successor/bundles/fixtures/u1-dir-physical-spec.json` | `U1_DIR_PHYSICAL_SPEC_V2` | `P3_U1_SPEC_SERIALIZER` |
| `M03-RECOVERY-CONTRACT` | `ATTEMPT_ROOT_TEMPLATE/v1/m03/disjoint-recovery-contract.signature.json` | `M03_DISJOINT_RECOVERY_CONTRACT_SIGNATURE_V2` | `P3_M03_CONTRACT_SIGNER` |
| `EXACT6-CONTRACT` | `H1_ROOT_TEMPLATE/successor/bundles/fixtures/exact6-contract.signature.json` | `EXACT6_CONTRACT_SIGNATURE_V2` | `P3_EXACT6_CONTRACT_SIGNER` |
| `APPLICATION-RECEIPT-TARGET-SPEC` | `H1_ROOT_TEMPLATE/successor/bundles/closure-schemas/application-receipt-target-spec.json` | `APPLICATION_RECEIPT_TARGET_SPEC_V2` | `P3_APPLICATION_TARGET_SPEC_SERIALIZER` |
| `RESOLVED-STAGE-C-SUBJECT` | `ATTEMPT_ROOT_TEMPLATE/bridge/bridge.signature.json` | `RESOLVED_STAGE_C_SUBJECT_SIGNATURE_V2` | `V0_BRIDGE_SIGNER` |
| `STAGE-B-FORMAL-REVIEW` | `ATTEMPT_ROOT_TEMPLATE/pre-authority-successor-review/formal.json` | `STAGE_B_FORMAL_REVIEW_RECEIPT_V2` | `STAGE_B_FORMAL_REVIEWER` |
| `STAGE-B-SKEPTICAL-REVIEW` | `ATTEMPT_ROOT_TEMPLATE/pre-authority-successor-review/skeptical.json` | `STAGE_B_SKEPTICAL_REVIEW_RECEIPT_V2` | `STAGE_B_SKEPTICAL_REVIEWER` |
| `STAGE-B-REVIEW-PAIR` | `ATTEMPT_ROOT_TEMPLATE/pre-authority-successor-review/pair.json` | `STAGE_B_REVIEW_PAIR_RECEIPT_V2` | `STAGE_B_REVIEW_PAIR_VERIFIER` |

The runtime instance registry resolves every template and records source
content SHA and `FilePhysicalV2`; conceptual source names without these rows
are invalid.

### 6.2 five closed payload schemas and one signing profile

Common payload fields:

```text
schema
object_type: N26 | T1 | X1 | STAGE_C_REVIEW_BINDING | V1
branch = CONSTRUCTIVE_H1
literal_path
publisher_actor_id
publisher_physical_sha
producer_executable_sha
producer_argv: array of NFC UTF-8 strings
semantic_domain
semantic_value_sha
body
```

For `N26`, `T1`, `X1` and `STAGE_C_REVIEW_BINDING`,
`semantic_value_sha` hashes domain, NUL and the payload projection without
`semantic_value_sha`. `V1` is the sole explicit exception: its semantic value
hashes the referenced `StageCReviewBinding` payload JCS, as specified below,
not its own payload projection. Exact semantic domains:

```text
N26 = WS-WALKSAFE-R007-B06-NORMATIVE-TARGET-TABLE-V2
T1 = WS-WALKSAFE-R007-B06-T1-TARGET-BINDING-V2
X1 = WS-WALKSAFE-R007-B06-X1-STAGE-C-BINDING-V2
STAGE_C_REVIEW_BINDING =
  WS-WALKSAFE-R007-B06-STAGE-C-REVIEW-BINDING-V2
V1 = R007_RESOLVED_REVIEW_BINDING_V2
```

`N26.body`:

```text
source_table_sha + source_table_physical
ordered_target_rows[26]:
  ordinal=1..26,target_role_id,literal_target_path,required_presence=true
target_count=26
target_order_digest
target_set_digest
```

`target_schema_sha` and `target_value_sha` are forbidden because they are not
members of the protected exact26 row schema.

The `ordered_target_rows` comparator is unsigned numeric `ordinal`, then UTF-8
byte order of `target_role_id`, then UTF-8 byte order of
`literal_target_path`; the valid normative table already has unique ordinals
`1..26`. The verifier first requires the supplied array already equals its
comparator-sorted copy byte-for-byte; it does not normalize a permutation.
The two digests are:

```text
target_order_digest =
  SHA256(
    ASCII("WS-WALKSAFE-R007-B06-N26-TARGET-ORDER-V2") || 0x00 ||
    RFC8785_JCS(ordered_target_rows)
  )

ordered_target_set_rows =
  sort_unique(
    projection(ordered_target_rows,
      [target_role_id,literal_target_path]),
    comparator=(UTF8(target_role_id),UTF8(literal_target_path))
  )

target_set_digest =
  SHA256(
    ASCII("WS-WALKSAFE-R007-B06-N26-TARGET-SET-V2") || 0x00 ||
    RFC8785_JCS(ordered_target_set_rows)
  )
```

The uniqueness check requires `length(ordered_target_set_rows)=26`; duplicate
target pairs reject before either digest is accepted.

`T1.body`:

```text
n26_payload_sha + n26_signature_sha
n26_payload_physical + n26_signature_physical
n26_semantic_value_sha
target_map_sha + target_map_physical
ordered_target_map_rows[26]:
  ordinal=1..26,target_role_id,literal_target_path
target_map_count=26 + target_map_digest
ordered_projected_n26_target_map_rows[26]:
  ordinal=1..26,target_role_id,literal_target_path
projected_n26_count=26 + projected_n26_digest
equality_receipt_sha + equality_receipt_physical
equality_result=PASS
```

The producer recomputes
`ordered_projected_n26_target_map_rows = projection(
N26.ordered_target_rows, [ordinal,target_role_id,literal_target_path])`.
`equality_result=PASS` iff projected and supplied arrays are byte-equal in
order, both counts are 26, both independently recomputed digests match, and
the equality receipt binds those four values. Count-only or set-only equality
is invalid.

Both T1 arrays use the same unsigned-ordinal/UTF-8 comparator as N26 and must
have the exact ordinal sequence `1..26`. Their digest constructors are:

```text
target_map_digest =
  SHA256(
    ASCII("WS-WALKSAFE-R007-B06-T1-TARGET-MAP-V2") || 0x00 ||
    RFC8785_JCS(ordered_target_map_rows)
  )

projected_n26_digest =
  SHA256(
    ASCII("WS-WALKSAFE-R007-B06-T1-PROJECTED-N26-V2") || 0x00 ||
    RFC8785_JCS(ordered_projected_n26_target_map_rows)
  )
```

`P0_SYNTHETIC_DERIVATION_RECEIPT_V2` must carry
`target_map_count,target_map_digest,projected_n26_count,
projected_n26_digest`, the two complete ordered arrays and
`ordered_arrays_byte_equal=true`; its verifier recomputes all four scalar
values and compares the two RFC8785 JCS byte strings. A receipt omitting an
array, changing the comparator or hashing an unordered map rejects.
The B06 negative set explicitly includes a permutation with unchanged row
members, a correct preimage under each wrong digest domain and a one-row
omission with forged count. Each produces `REJECT`; independent digest
equality means no digest value can stand in for another constructor.

`X1.body`:

```text
n26_payload/signature/semantic SHA + both Physical
t1_payload/signature/semantic SHA + both Physical
u1_spec_sha + u1_spec_physical
m03_recovery_contract_sha + m03_recovery_contract_physical
exact6_contract_sha + exact6_contract_physical
application_target_spec_sha + application_target_spec_physical
application_target_spec_jcs_sha
```

`StageCReviewBinding.body`:

```text
x1_payload/signature/semantic SHA + both Physical
resolved_subject_sha + resolved_subject_physical
ordered_stage_b_reviews[2]:
  ordinal,review_kind=FORMAL|SKEPTICAL,receipt_sha,receipt_physical,
  blocking=0,major=0,minor=0
review_pair_sha + review_pair_physical
review_pair_result=PASS
```

`V1.body`:

```text
review_binding_payload_schema_sha
review_binding_payload_sha
review_binding_payload_physical
review_binding_signature_sha
review_binding_signature_physical
review_binding_payload_jcs_sha
semantic_preimage_kind =
  EXACT_STAGE_C_REVIEW_BINDING_PAYLOAD_JCS
```

V1 semantic value hashes exact `StageCReviewBinding` payload JCS bytes only.
Review wrapper SHA/Physical are provenance fields outside that semantic
preimage.

All five detached signature wrappers use one profile:

```text
schema
payload_role_id
payload_path
payload_schema_sha
payload_sha
payload_physical
signer_actor_id
signer_physical_sha
signature_algorithm
signature_domain = WS-WALKSAFE-R007-B06-DETACHED-SIGNATURE-V2
signature

signing_input =
  ASCII("WS-WALKSAFE-R007-B06-DETACHED-SIGNATURE-V2") || 0x00 ||
  RFC8785_JCS(full payload)
```

Partial-field signing and R006's former signing digest are forbidden.

### 6.3 exact 26 direct edges

| edge_id | source role | target role |
|---|---|---|
| `B06E001` | `B06-N26-PAYLOAD` | `B06-N26-SIGNATURE` |
| `B06E002` | `B06-T1-PAYLOAD` | `B06-T1-SIGNATURE` |
| `B06E003` | `B06-X1-PAYLOAD` | `B06-X1-SIGNATURE` |
| `B06E004` | `B06-REVIEW-PAYLOAD` | `B06-REVIEW-SIGNATURE` |
| `B06E005` | `B06-V1-PAYLOAD` | `B06-V1-SIGNATURE` |
| `B06E006` | `P0-EXACT26-TABLE` | `B06-N26-PAYLOAD` |
| `B06E007` | `B06-N26-PAYLOAD` | `B06-T1-PAYLOAD` |
| `B06E008` | `B06-N26-SIGNATURE` | `B06-T1-PAYLOAD` |
| `B06E009` | `P0-TARGET-MAP` | `B06-T1-PAYLOAD` |
| `B06E010` | `P0-TARGET-EQUALITY` | `B06-T1-PAYLOAD` |
| `B06E011` | `B06-N26-PAYLOAD` | `B06-X1-PAYLOAD` |
| `B06E012` | `B06-N26-SIGNATURE` | `B06-X1-PAYLOAD` |
| `B06E013` | `B06-T1-PAYLOAD` | `B06-X1-PAYLOAD` |
| `B06E014` | `B06-T1-SIGNATURE` | `B06-X1-PAYLOAD` |
| `B06E015` | `U1-DIR-PHYSICAL-SPEC` | `B06-X1-PAYLOAD` |
| `B06E016` | `M03-RECOVERY-CONTRACT` | `B06-X1-PAYLOAD` |
| `B06E017` | `EXACT6-CONTRACT` | `B06-X1-PAYLOAD` |
| `B06E018` | `APPLICATION-RECEIPT-TARGET-SPEC` | `B06-X1-PAYLOAD` |
| `B06E019` | `B06-X1-PAYLOAD` | `B06-REVIEW-PAYLOAD` |
| `B06E020` | `B06-X1-SIGNATURE` | `B06-REVIEW-PAYLOAD` |
| `B06E021` | `RESOLVED-STAGE-C-SUBJECT` | `B06-REVIEW-PAYLOAD` |
| `B06E022` | `STAGE-B-FORMAL-REVIEW` | `B06-REVIEW-PAYLOAD` |
| `B06E023` | `STAGE-B-SKEPTICAL-REVIEW` | `B06-REVIEW-PAYLOAD` |
| `B06E024` | `STAGE-B-REVIEW-PAIR` | `B06-REVIEW-PAYLOAD` |
| `B06E025` | `B06-REVIEW-PAYLOAD` | `B06-V1-PAYLOAD` |
| `B06E026` | `B06-REVIEW-SIGNATURE` | `B06-V1-PAYLOAD` |

The edge digest domain is `WS-WALKSAFE-R007-B06-EDGE-REGISTRY-V2`.
Output roles/edges are exactly `10/26`; aliases and duplicate tuples are `0`.

## 7. B04 H1과 canonical Stage-C application

이 절은 B04 H1과 actual application pair를 닫는다.

### 7.1 B04 H1 exact six roles / 23 edges

Output rows:

| role_id | path under `ATTEMPT_ROOT_TEMPLATE/v1/stage-c/b04/` | schema | publisher | Physical |
|---|---|---|---|---|
| `B04-CONTRACT-PAYLOAD` | `c-recovery-extension-contract.payload.json` | `B04_C_RECOVERY_EXTENSION_CONTRACT_PAYLOAD_V2` | `P5_B04_CONTRACT_SERIALIZER` | `FilePhysicalV2` |
| `B04-CONTRACT-SIGNATURE` | `c-recovery-extension-contract.signature.json` | `B04_C_RECOVERY_EXTENSION_CONTRACT_SIGNATURE_V2` | `P5_B04_CONTRACT_SIGNER` | `FilePhysicalV2` |
| `B04-FIXTURE-PAYLOAD` | `constructive-fixture.payload.json` | `B04_CONSTRUCTIVE_FIXTURE_PAYLOAD_V2` | `P5_B04_FIXTURE_SERIALIZER` | `FilePhysicalV2` |
| `B04-FIXTURE-SIGNATURE` | `constructive-fixture.signature.json` | `B04_CONSTRUCTIVE_FIXTURE_SIGNATURE_V2` | `P5_B04_FIXTURE_SIGNER` | `FilePhysicalV2` |
| `B04-VERIFY-PAYLOAD` | `extension-verification.payload.json` | `B04_EXTENSION_VERIFICATION_PAYLOAD_V2` | `P5_B04_VERIFY_SERIALIZER` | `FilePhysicalV2` |
| `B04-VERIFY-SIGNATURE` | `extension-verification.signature.json` | `B04_EXTENSION_VERIFICATION_SIGNATURE_V2` | `P5_B04_VERIFY_SIGNER` | `FilePhysicalV2` |

Exact H1 source rows used below:

| source role | literal path template | schema | publisher |
|---|---|---|---|
| `STAGE-C-APPLICATION-SCHEMA` | `H1_ROOT_TEMPLATE/successor/bundles/closure-schemas/stage-c-application-payload.schema.json` | `JSON_SCHEMA_DOCUMENT_V2` | `P5_SCHEMA_FREEZER` |
| `APPLICATION-RECEIPT-TARGET-SPEC` | `H1_ROOT_TEMPLATE/successor/bundles/closure-schemas/application-receipt-target-spec.json` | `APPLICATION_RECEIPT_TARGET_SPEC_V2` | `P3_APPLICATION_TARGET_SPEC_SERIALIZER` |
| `M03-RECOVERY-CONTRACT` | `ATTEMPT_ROOT_TEMPLATE/v1/m03/disjoint-recovery-contract.signature.json` | `M03_DISJOINT_RECOVERY_CONTRACT_SIGNATURE_V2` | `P3_M03_CONTRACT_SIGNER` |

`B04CRecoveryExtensionContractPayloadV2` exact fields:

```text
schema
application_schema_sha + application_schema_physical
application_target_spec_sha + application_target_spec_physical
application_target_spec_jcs + application_target_spec_jcs_sha
m03_contract_sha + m03_contract_physical
ordered_variant_rows[3]{
  ordinal,
  variant=ORDINARY_STAGE_C|RECOVERY_EXACT6_SUFFIX|
          RECOVERY_APPLICATION_FINALIZATION,
  required_predecessor_role_ids,
  forbidden_predecessor_role_ids,
  required_output_role_ids,
  exact_application_pair_count
}
variant_count=3 + variant_digest
publisher_actor_id + publisher_physical_sha
signature_domain=WS-WALKSAFE-R007-B04-CONTRACT-PAYLOAD-V2
```

`B04ConstructiveFixturePayloadV2` exact fields:

```text
schema
contract_signature_sha + contract_signature_physical
ordered_b06_output_refs[10]{
  ordinal,role_id,literal_path,schema_sha,content_sha,file_physical
}
b06_output_count=10 + b06_output_digest
m03_contract_sha + m03_contract_physical
ordered_variant_case_specs[3]{
  ordinal,
  variant=ORDINARY_STAGE_C|RECOVERY_EXACT6_SUFFIX|
          RECOVERY_APPLICATION_FINALIZATION,
  deterministic_input_constructor_id,
  ordered_required_role_ids,
  expected_application_pair_count
}
variant_case_count=3 + variant_case_digest
ordered_negative_case_ids
negative_case_count + negative_case_digest
publisher_actor_id + publisher_physical_sha
signature_domain=WS-WALKSAFE-R007-B04-FIXTURE-PAYLOAD-V2
```

`B04ExtensionVerificationPayloadV2` exact fields:

```text
schema
contract_payload_sha + contract_signature_sha
contract_payload_physical + contract_signature_physical
fixture_payload_sha + fixture_signature_sha
fixture_payload_physical + fixture_signature_physical
variant_exhaustiveness_result=PASS
application_target_byte_equality_result=PASS
m03_branch_disjointness_result=PASS
self_reference_count=0
future_reference_count=0
dependency_scc_count=0
recomputed_h1_role_count=6
recomputed_h1_edge_count=23
recomputed_edge_digest
verification_result=PASS
publisher_actor_id + publisher_physical_sha
signature_domain=WS-WALKSAFE-R007-B04-VERIFICATION-PAYLOAD-V2
```

All three detached signatures have exact fields
`schema,payload_role_id,payload_path,payload_schema_sha,payload_sha,
payload_physical,signer_actor_id,signer_physical_sha,signature_algorithm,
signature_domain,signature`. Each signs its payload-specific domain, NUL and
full payload JCS. Payload and signer publishers/Physical are those in the
six-row output table; no pair may reuse a producer Physical.

Direct M03→fixture from R006 is intentionally preserved. Therefore R001's
provisional `22` is replaced by exact `23`.

The three bodies' direct artifact bindings are exactly the inbound sources in
`B04H004`–`B04H022`: contract consumes application schema, target spec and
M03; fixture consumes the contract signature, ten B06 outputs and M03; verify
consumes the contract/fixture pairs. The contract signature inward-binds its
payload. `ordered_variant_case_specs` are embedded deterministic specs, not
unlisted artifact SHA/Physical references.

| edge ID | exact source | exact target |
|---|---|---|
| `B04H001` | `B04-CONTRACT-PAYLOAD` | `B04-CONTRACT-SIGNATURE` |
| `B04H002` | `B04-FIXTURE-PAYLOAD` | `B04-FIXTURE-SIGNATURE` |
| `B04H003` | `B04-VERIFY-PAYLOAD` | `B04-VERIFY-SIGNATURE` |
| `B04H004` | `STAGE-C-APPLICATION-SCHEMA` | `B04-CONTRACT-PAYLOAD` |
| `B04H005` | `APPLICATION-RECEIPT-TARGET-SPEC` | `B04-CONTRACT-PAYLOAD` |
| `B04H006` | `M03-RECOVERY-CONTRACT` | `B04-CONTRACT-PAYLOAD` |
| `B04H007` | `B04-CONTRACT-SIGNATURE` | `B04-FIXTURE-PAYLOAD` |
| `B04H008` | `B06-N26-PAYLOAD` | `B04-FIXTURE-PAYLOAD` |
| `B04H009` | `B06-N26-SIGNATURE` | `B04-FIXTURE-PAYLOAD` |
| `B04H010` | `B06-T1-PAYLOAD` | `B04-FIXTURE-PAYLOAD` |
| `B04H011` | `B06-T1-SIGNATURE` | `B04-FIXTURE-PAYLOAD` |
| `B04H012` | `B06-X1-PAYLOAD` | `B04-FIXTURE-PAYLOAD` |
| `B04H013` | `B06-X1-SIGNATURE` | `B04-FIXTURE-PAYLOAD` |
| `B04H014` | `B06-REVIEW-PAYLOAD` | `B04-FIXTURE-PAYLOAD` |
| `B04H015` | `B06-REVIEW-SIGNATURE` | `B04-FIXTURE-PAYLOAD` |
| `B04H016` | `B06-V1-PAYLOAD` | `B04-FIXTURE-PAYLOAD` |
| `B04H017` | `B06-V1-SIGNATURE` | `B04-FIXTURE-PAYLOAD` |
| `B04H018` | `M03-RECOVERY-CONTRACT` | `B04-FIXTURE-PAYLOAD` |
| `B04H019` | `B04-CONTRACT-PAYLOAD` | `B04-VERIFY-PAYLOAD` |
| `B04H020` | `B04-CONTRACT-SIGNATURE` | `B04-VERIFY-PAYLOAD` |
| `B04H021` | `B04-FIXTURE-PAYLOAD` | `B04-VERIFY-PAYLOAD` |
| `B04H022` | `B04-FIXTURE-SIGNATURE` | `B04-VERIFY-PAYLOAD` |
| `B04H023` | `B04-VERIFY-SIGNATURE` | `T15-RESULT-PAYLOAD` |

The M03→fixture edge `B04H018` is intentionally preserved. Therefore the
literal digest has 23, not 22, rows.

The B04 constructor inputs are closed and ordered without an authored count:

```text
B04_VARIANTS =
  [ORDINARY_STAGE_C,
   RECOVERY_EXACT6_SUFFIX,
   RECOVERY_APPLICATION_FINALIZATION]
B04_B06_OUTPUT_ORDER =
  [B06-N26-PAYLOAD,B06-N26-SIGNATURE,
   B06-T1-PAYLOAD,B06-T1-SIGNATURE,
   B06-X1-PAYLOAD,B06-X1-SIGNATURE,
   B06-REVIEW-PAYLOAD,B06-REVIEW-SIGNATURE,
   B06-V1-PAYLOAD,B06-V1-SIGNATURE]
B04_NEGATIVE_CASE_IDS =
  [B015-N01-MISSING-M03,B015-N02-TRIPLE-MEMBER-MISSING,
   B015-N03-DUPLICATE-PAIR,B015-N04-FUTURE-SHA,
   B015-N05-UNDEFINED-APPISS-ENDPOINT,
   B015-N06-CROSS-BRANCH-SUBJECT,
   B015-N07-RECOVERY-SUFFIX-PUBLISHES-APPLICATION]
B04_H1_EDGE_ORDER = full B04H001 through B04H023 rows above
```

Variant, B06 input, fixture-variant, negative and H1 edge digests use their
five literal §5 B04 domains and full rows in those orders. The verification
payload recomputes all five, and `verification_result=PASS` iff every array,
count, order, digest, branch pair count, self/future count `0/0`, SCC count
`0` and H1 `6/23` predicate passes. An ID-only edge digest is invalid.

### 7.2 ordinary/recovery issuance and consume

```text
H2_ROOT_TEMPLATE =
AUTH_ROOT_TEMPLATE/journal/transactions/{h2_transaction_id}

ORDINARY_JOURNAL_TEMPLATE =
AUTH_ROOT_TEMPLATE/journal/attempts/stage-c/{attempt_id}

RECOVERY_JOURNAL_TEMPLATE =
AUTH_ROOT_TEMPLATE/journal/attempts/recovery-stage-c/{attempt_id}
```

Ordinary issuance paths:

```text
ORDINARY_JOURNAL_TEMPLATE/authority-issuance.payload.json
ORDINARY_JOURNAL_TEMPLATE/authority-issuance.signature.json
```

Recovery suffix issuance uses
`RECOVERY_JOURNAL_TEMPLATE/issuance/exact6-suffix/`; recovery application
issuance uses
`RECOVERY_JOURNAL_TEMPLATE/issuance/application-finalization/`. Each root has
distinct `current-head.observation.json`, `authority-issuance.payload.json`
and `authority-issuance.signature.json`; no role/path/key is shared between
the two recovery stages. Payload schema is a tagged union:

```text
schema = STAGE_C_AUTHORITY_ISSUANCE_PAYLOAD_V2
phase_context: StageCApplicationPhaseContextV2
decision_payload_sha + decision_wrapper_sha + both Physical
ordered_capability_rows + capability_count + capability_digest
application_target_spec_jcs + application_target_spec_jcs_sha
current_head_observation: ArtifactBindingV2
current_head_snapshot_digest
operation_not_after + settlement_not_after
issuer_actor_id + issuer_physical_sha
issued_at
variant_binding:
  {
    "kind":"ORDINARY_STAGE_C",
    "ordered_existing_result_refs":[],
    "dispatch_ordinal_set":[1,2,3,4,5,6],
    "application_pair_publication_count":2
  }
  XOR
  {
    "kind":"RECOVERY_EXACT6_SUFFIX",
    "durable_prefix_count":integer 0..5,
    "ordered_durable_prefix_result_refs":array with that exact count,
    "dispatch_ordinal_set":exact suffix of [1,2,3,4,5,6],
    "postcheck_binding":{"kind":"NOT_APPLICABLE"},
    "application_pair_publication_count":0
  }
  XOR
  {
    "kind":"RECOVERY_APPLICATION_FINALIZATION",
    "durable_prefix_count":6,
    "ordered_durable_prefix_result_refs":array with exact ordinals 1..6,
    "dispatch_ordinal_set":[],
    "postcheck_binding":{
      "kind":"EXISTING_POSTCHECK_PASSED",
      "sha":Sha256Hex,"physical":FilePhysicalV2
    },
    "application_pair_publication_count":2
  }
signature_domain=WS-WALKSAFE-R007-STAGE-C-AUTHORITY-ISSUANCE-PAYLOAD-V2
```

Each issuance branch has its own signed `CurrentHeadObservationV3` file at the
branch issuance root `current-head.observation.json`. Its body exact fields
are
`schema,observation_id,aggregate_key_digest,store_snapshot_token,
authority_head_payload:ArtifactBindingV2,
authority_head_signature:ArtifactBindingV2,authority_token,
revocation_head_payload:ArtifactBindingV2,
revocation_head_signature:ArtifactBindingV2,revocation_token,observed_at,
observer_actor_id,observer_physical_sha,head_snapshot_digest`.
`head_snapshot_digest` uses domain
`WS-WALKSAFE-R007-STAGE-C-CURRENT-HEAD-SNAPSHOT-V3`, NUL and the JCS projection
of both head pairs, both tokens and `store_snapshot_token`. Issuance,
phase context, consume receipt and application payload carry only the
observation binding plus this digest, never independently supplied head
fields. Consume CAS reopens that one observation and compares every member to
one same-store snapshot at its linearization.

It contains no consume, application output, finalization, close or future
transition reference. An existing postcheck is permitted only in the tagged
`RECOVERY_APPLICATION_FINALIZATION` predecessor binding. Detached signature
domain is
`WS-WALKSAFE-R007-STAGE-C-AUTHORITY-ISSUANCE-PAYLOAD-V2`.
For `ORDINARY_STAGE_C`, `phase_context.selected_stage_c_evidence.kind` is
`ORDINARY_STAGE_C` and its subject/approval receipt bindings byte-equal
`APPISS004/APPISS007`. For either recovery issuance variant that evidence kind
is `RECOVERY_STAGE_C`; suffix bindings byte-equal
`RXSISS004/RXSISS007`, while application-finalization bindings byte-equal
`RAFISS004/RAFISS007`. Separate issuance root, role and consume key also
distinguish the two stages.
`RECOVERY_EXACT6_SUFFIX` cannot be relabelled as application finalization.
The issuance serializer also verifies the branch-specific review pair and
approval payload at `APPISS005/006`, `RXSISS005/006` or `RAFISS005/006` refer
to the same subject and approval receipt. Cross-journal substitution produces
no issuance.

Every issuance signature has exact fields
`schema,payload_path,payload_schema_sha,payload_sha,payload_physical,
signer_actor_id,signer_physical_sha,signature_algorithm,signature_domain,
signature`, with a concrete signer Physical. Ordinary publisher/signer roles
are `STAGE_C_AUTHORITY_ISSUER`/`STAGE_C_AUTHORITY_SIGNER`; recovery uses
distinct `STAGE_C_RECOVERY_AUTHORITY_ISSUER`/
`STAGE_C_RECOVERY_AUTHORITY_SIGNER`. Signature input is domain, NUL and full
payload JCS.

Consume key is the JCS digest of the closed object
`{schema:"STAGE_C_AUTHORITY_CONSUME_KEY_V2",successor_revision_id,
attempt_namespace_id,attempt_id,consume_namespace:
"ORDINARY_STAGE_C"|"RECOVERY_EXACT6_SUFFIX"|
"RECOVERY_APPLICATION_FINALIZATION"}`. All three namespaces are disjoint.
The digest constructor is
`SHA256(ASCII("WS-WALKSAFE-R007-STAGE-C-AUTHORITY-CONSUME-KEY-V2") ||
0x00 || RFC8785_JCS(object))`; substituting the three literal namespace values
produces `ordinary_consume_key_digest`, `recovery_suffix_consume_key_digest`
and `recovery_application_consume_key_digest`.
The post-commit
`StageCAuthorityConsumeTransitionReceiptV2` body has exact fields
`schema,consume_key_digest,variant_binding,phase_context,
issuance_payload_sha,issuance_signature_sha,both issuance Physical,
activation_receipt_sha,activation_receipt_physical,
activation_publication_attestation_sha,
activation_publication_attestation_physical,aggregate_key_digest,
aggregate_pre_token,aggregate_post_token,role_pre_token,role_post_token,
role_scope_binding,current_head_observation,current_head_snapshot_digest,
operation_not_after,linearized_at,
transition_id,core_digest,obligation_digest,cas_service_actor_id,
cas_service_physical_sha`. Its service signature uses domain
`WS-WALKSAFE-R007-STAGE-C-AUTHORITY-CONSUME-RECEIPT-V2`, NUL and body JCS.
The receipt bytes omit their own SHA/`CasRecordPhysicalV2`; the CAS return
tuple supplies both externally. CAS requires trusted
`linearized_at < operation_not_after` and requires
`phase_context.authority_consume_expected_pre_token =
aggregate_pre_token`; the post token exists only in the receipt and is not a
future field in issuance.

Recovery branch XOR:

```text
RECOVERY_EXACT6_SUFFIX(k), k∈{0,1,2,3,4,5}:
  durable prefix count=k
  dispatch only ordinals k+1..6
  application publication count=0

RECOVERY_APPLICATION_FINALIZATION:
  existing POSTCHECK_PASSED
  exact6 dispatch count=0
  application pair publication count=2
```

Every issuance edge endpoint below is the literal `role_instance_id` of a
`RoleInstanceRegistryRowV2`, never a prose alias. The required template rows
are:

| role_instance_id | exact path template or typed CAS logical-path constructor | schema role | publisher role | Physical kind |
|---|---|---|---|---|
| `EXECUTION-DECISION-PAYLOAD` | `AUTH_ROOT_TEMPLATE/decision/decision.payload.json` | `EXECUTION_DECISION_PAYLOAD_V2` | `AUTHORITY_DECISION_SERIALIZER` | `FILE` |
| `EXECUTION-DECISION-SIGNATURE` | `AUTH_ROOT_TEMPLATE/decision/decision.signature.json` | `EXECUTION_DECISION_DETACHED_SIGNATURE_V2` | `AUTHORITY_DECISION_SIGNER` | `FILE` |
| `AUTHORITY-AGGREGATE-ACTIVATION` | `AUTH_ROOT_TEMPLATE/activation/authority-aggregate-activation.receipt.json` | `AUTHORITY_AGGREGATE_ACTIVATION_RECEIPT_V2` | `AUTHORITY_AGGREGATE_CAS_SERVICE` | `FILE` |
| `STAGE-C-SUBJECT` | `ORDINARY_JOURNAL_TEMPLATE/stage-c-subject.json` | `STAGE_C_SUBJECT_V2` | `STAGE_C_SUBJECT_SERIALIZER` | `FILE` |
| `STAGE-C-SUBJECT-REVIEW-PAIR` | `ORDINARY_JOURNAL_TEMPLATE/stage-c-subject-review-pair.json` | `STAGE_C_SUBJECT_REVIEW_PAIR_V2` | `STAGE_C_SUBJECT_REVIEW_PAIR_VERIFIER` | `FILE` |
| `STAGE-C-APPROVAL-PAYLOAD` | `ORDINARY_JOURNAL_TEMPLATE/stage-c-approval.payload.json` | `STAGE_C_APPROVAL_PAYLOAD_V2` | `STAGE_C_APPROVAL_SERIALIZER` | `FILE` |
| `STAGE-C-APPROVAL-RECEIPT` | `ORDINARY_JOURNAL_TEMPLATE/stage-c-approval.receipt.json` | `STAGE_C_APPROVAL_RECEIPT_V2` | `STAGE_C_APPROVAL_CAS_SERVICE` | `FILE` |
| `RECOVERY-STAGE-C-SUBJECT` | `RECOVERY_JOURNAL_TEMPLATE/stage-c-subject.json` | `RECOVERY_STAGE_C_SUBJECT_V2` | `RECOVERY_STAGE_C_SUBJECT_SERIALIZER` | `FILE` |
| `RECOVERY-STAGE-C-SUBJECT-REVIEW-PAIR` | `RECOVERY_JOURNAL_TEMPLATE/stage-c-subject-review-pair.json` | `RECOVERY_STAGE_C_SUBJECT_REVIEW_PAIR_V2` | `RECOVERY_STAGE_C_SUBJECT_REVIEW_PAIR_VERIFIER` | `FILE` |
| `RECOVERY-STAGE-C-APPROVAL-PAYLOAD` | `RECOVERY_JOURNAL_TEMPLATE/stage-c-approval.payload.json` | `RECOVERY_STAGE_C_APPROVAL_PAYLOAD_V2` | `RECOVERY_STAGE_C_APPROVAL_SERIALIZER` | `FILE` |
| `RECOVERY-STAGE-C-APPROVAL-RECEIPT` | `RECOVERY_JOURNAL_TEMPLATE/stage-c-approval.receipt.json` | `RECOVERY_STAGE_C_APPROVAL_RECEIPT_V2` | `RECOVERY_STAGE_C_APPROVAL_CAS_SERVICE` | `FILE` |
| `APPLICATION-RECEIPT-TARGET-SPEC` | `H1_ROOT_TEMPLATE/successor/bundles/closure-schemas/application-receipt-target-spec.json` | `APPLICATION_RECEIPT_TARGET_SPEC_V2` | `P3_APPLICATION_TARGET_SPEC_SERIALIZER` | `FILE` |
| `POST-CLOSE-FINALIZATION-GRANT-PAYLOAD` | `AUTH_ROOT_TEMPLATE/grants/post-close-finalization.payload.json` | `ROLE_GRANT_PAYLOAD_V2` | `FINALIZATION_GRANT_SERIALIZER` | `FILE` |
| `POST-CLOSE-FINALIZATION-GRANT-SIGNATURE` | `AUTH_ROOT_TEMPLATE/grants/post-close-finalization.signature.json` | `ROLE_GRANT_DETACHED_SIGNATURE_V2` | `FINALIZATION_GRANT_SIGNER` | `FILE` |
| `TERMINAL-WRAPPER-RECOVERY-GRANT-PAYLOAD` | `AUTH_ROOT_TEMPLATE/grants/terminal-wrapper-recovery.payload.json` | `ROLE_GRANT_PAYLOAD_V2` | `WRAPPER_GRANT_SERIALIZER` | `FILE` |
| `TERMINAL-WRAPPER-RECOVERY-GRANT-SIGNATURE` | `AUTH_ROOT_TEMPLATE/grants/terminal-wrapper-recovery.signature.json` | `ROLE_GRANT_DETACHED_SIGNATURE_V2` | `WRAPPER_GRANT_SIGNER` | `FILE` |
| `STAGE-C-CURRENT-HEAD-OBSERVATION` | `ORDINARY_JOURNAL_TEMPLATE/current-head.observation.json` | `STAGE_C_CURRENT_HEAD_OBSERVATION_V3` | `STAGE_C_HEAD_OBSERVER` | `FILE` |
| `STAGE-C-ISSUANCE-PAYLOAD` | `ORDINARY_JOURNAL_TEMPLATE/authority-issuance.payload.json` | `STAGE_C_AUTHORITY_ISSUANCE_PAYLOAD_V2` | `STAGE_C_AUTHORITY_ISSUER` | `FILE` |
| `STAGE-C-ISSUANCE-SIGNATURE` | `ORDINARY_JOURNAL_TEMPLATE/authority-issuance.signature.json` | `STAGE_C_AUTHORITY_ISSUANCE_SIGNATURE_V2` | `STAGE_C_AUTHORITY_SIGNER` | `FILE` |
| `STAGE-C-CONSUME-TRANSITION` | `CasLogicalRecordPathV2("AUTHORITY_AGGREGATE_CAS_SERVICE",ordinary_consume_key_digest)` | `STAGE_C_AUTHORITY_CONSUME_TRANSITION_RECEIPT_V2` | `AUTHORITY_AGGREGATE_CAS_SERVICE` | `CAS_RECORD` |
| `RECOVERY-SUFFIX-CURRENT-HEAD-OBSERVATION` | `RECOVERY_JOURNAL_TEMPLATE/issuance/exact6-suffix/current-head.observation.json` | `STAGE_C_CURRENT_HEAD_OBSERVATION_V3` | `STAGE_C_RECOVERY_HEAD_OBSERVER` | `FILE` |
| `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` | `RECOVERY_JOURNAL_TEMPLATE/issuance/exact6-suffix/authority-issuance.payload.json` | `STAGE_C_AUTHORITY_ISSUANCE_PAYLOAD_V2` | `STAGE_C_RECOVERY_AUTHORITY_ISSUER` | `FILE` |
| `RECOVERY-SUFFIX-ISSUANCE-SIGNATURE` | `RECOVERY_JOURNAL_TEMPLATE/issuance/exact6-suffix/authority-issuance.signature.json` | `STAGE_C_AUTHORITY_ISSUANCE_SIGNATURE_V2` | `STAGE_C_RECOVERY_AUTHORITY_SIGNER` | `FILE` |
| `RECOVERY-SUFFIX-CONSUME-TRANSITION` | `CasLogicalRecordPathV2("AUTHORITY_AGGREGATE_CAS_SERVICE",recovery_suffix_consume_key_digest)` | `STAGE_C_AUTHORITY_CONSUME_TRANSITION_RECEIPT_V2` | `AUTHORITY_AGGREGATE_CAS_SERVICE` | `CAS_RECORD` |
| `RECOVERY-APPLICATION-CURRENT-HEAD-OBSERVATION` | `RECOVERY_JOURNAL_TEMPLATE/issuance/application-finalization/current-head.observation.json` | `STAGE_C_CURRENT_HEAD_OBSERVATION_V3` | `STAGE_C_RECOVERY_HEAD_OBSERVER` | `FILE` |
| `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` | `RECOVERY_JOURNAL_TEMPLATE/issuance/application-finalization/authority-issuance.payload.json` | `STAGE_C_AUTHORITY_ISSUANCE_PAYLOAD_V2` | `STAGE_C_RECOVERY_AUTHORITY_ISSUER` | `FILE` |
| `RECOVERY-APPLICATION-ISSUANCE-SIGNATURE` | `RECOVERY_JOURNAL_TEMPLATE/issuance/application-finalization/authority-issuance.signature.json` | `STAGE_C_AUTHORITY_ISSUANCE_SIGNATURE_V2` | `STAGE_C_RECOVERY_AUTHORITY_SIGNER` | `FILE` |
| `RECOVERY-APPLICATION-CONSUME-TRANSITION` | `CasLogicalRecordPathV2("AUTHORITY_AGGREGATE_CAS_SERVICE",recovery_application_consume_key_digest)` | `STAGE_C_AUTHORITY_CONSUME_TRANSITION_RECEIPT_V2` | `AUTHORITY_AGGREGATE_CAS_SERVICE` | `CAS_RECORD` |

Each materialized row additionally has its resolved literal path, non-empty
`schema_sha`, publisher actor ID, non-empty publisher executable
`publisher_physical_sha`, content SHA and `FilePhysicalV2` or
`CasRecordPhysicalV2`. The three current-head observation rows are distinct
instances of the closed `CurrentHeadObservationV3` contract above and are not
aliases for either member of either head pair.
The repeated appearances of `APPLICATION-RECEIPT-TARGET-SPEC` in B06, B04,
issuance and application tables all reference this one primary
RoleInstance row with identical path/schema/content/publisher/Physical; they
do not create duplicate registry rows or aliases.

`CasLogicalRecordPathV2(service_id,record_key_jcs_sha)` is the typed
concatenation `UTF8("cas-record/") || UTF8(service_id) || UTF8("/") ||
LOWERCASE_HEX(record_key_jcs_sha)`. It is used only as the CAS instance's
logical registry identifier, contains no brace or angle placeholder and
byte-equals the `resolved_literal_path` rule in §13.2. The three consume-key
digests are computed from the closed consume-key object above with the
ordinary, recovery-suffix or recovery-application namespace respectively.

Ordinary issuance lineage uses these exact 15 literal edges, including the
consume edge:

| edge ID | source role_instance_id | target role_instance_id |
|---|---|---|
| `APPISS001` | `EXECUTION-DECISION-PAYLOAD` | `STAGE-C-ISSUANCE-PAYLOAD` |
| `APPISS002` | `EXECUTION-DECISION-SIGNATURE` | `STAGE-C-ISSUANCE-PAYLOAD` |
| `APPISS003` | `AUTHORITY-AGGREGATE-ACTIVATION` | `STAGE-C-ISSUANCE-PAYLOAD` |
| `APPISS004` | `STAGE-C-SUBJECT` | `STAGE-C-ISSUANCE-PAYLOAD` |
| `APPISS005` | `STAGE-C-SUBJECT-REVIEW-PAIR` | `STAGE-C-ISSUANCE-PAYLOAD` |
| `APPISS006` | `STAGE-C-APPROVAL-PAYLOAD` | `STAGE-C-ISSUANCE-PAYLOAD` |
| `APPISS007` | `STAGE-C-APPROVAL-RECEIPT` | `STAGE-C-ISSUANCE-PAYLOAD` |
| `APPISS008` | `APPLICATION-RECEIPT-TARGET-SPEC` | `STAGE-C-ISSUANCE-PAYLOAD` |
| `APPISS009` | `POST-CLOSE-FINALIZATION-GRANT-PAYLOAD` | `STAGE-C-ISSUANCE-PAYLOAD` |
| `APPISS010` | `POST-CLOSE-FINALIZATION-GRANT-SIGNATURE` | `STAGE-C-ISSUANCE-PAYLOAD` |
| `APPISS011` | `TERMINAL-WRAPPER-RECOVERY-GRANT-PAYLOAD` | `STAGE-C-ISSUANCE-PAYLOAD` |
| `APPISS012` | `TERMINAL-WRAPPER-RECOVERY-GRANT-SIGNATURE` | `STAGE-C-ISSUANCE-PAYLOAD` |
| `APPISS013` | `STAGE-C-CURRENT-HEAD-OBSERVATION` | `STAGE-C-ISSUANCE-PAYLOAD` |
| `APPISS014` | `STAGE-C-ISSUANCE-PAYLOAD` | `STAGE-C-ISSUANCE-SIGNATURE` |
| `APPCON001` | `STAGE-C-ISSUANCE-SIGNATURE` | `STAGE-C-CONSUME-TRANSITION` |

Recovery exact-six suffix uses these exact 15 distinct literal edges:

| edge ID | source role_instance_id | target role_instance_id |
|---|---|---|
| `RXSISS001` | `EXECUTION-DECISION-PAYLOAD` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` |
| `RXSISS002` | `EXECUTION-DECISION-SIGNATURE` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` |
| `RXSISS003` | `AUTHORITY-AGGREGATE-ACTIVATION` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` |
| `RXSISS004` | `RECOVERY-STAGE-C-SUBJECT` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` |
| `RXSISS005` | `RECOVERY-STAGE-C-SUBJECT-REVIEW-PAIR` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` |
| `RXSISS006` | `RECOVERY-STAGE-C-APPROVAL-PAYLOAD` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` |
| `RXSISS007` | `RECOVERY-STAGE-C-APPROVAL-RECEIPT` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` |
| `RXSISS008` | `APPLICATION-RECEIPT-TARGET-SPEC` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` |
| `RXSISS009` | `POST-CLOSE-FINALIZATION-GRANT-PAYLOAD` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` |
| `RXSISS010` | `POST-CLOSE-FINALIZATION-GRANT-SIGNATURE` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` |
| `RXSISS011` | `TERMINAL-WRAPPER-RECOVERY-GRANT-PAYLOAD` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` |
| `RXSISS012` | `TERMINAL-WRAPPER-RECOVERY-GRANT-SIGNATURE` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` |
| `RXSISS013` | `RECOVERY-SUFFIX-CURRENT-HEAD-OBSERVATION` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` |
| `RXSISS014` | `RECOVERY-SUFFIX-ISSUANCE-PAYLOAD` | `RECOVERY-SUFFIX-ISSUANCE-SIGNATURE` |
| `RXSCON001` | `RECOVERY-SUFFIX-ISSUANCE-SIGNATURE` | `RECOVERY-SUFFIX-CONSUME-TRANSITION` |

Recovery application-finalization uses a separate exact 15-edge namespace:

| edge ID | source role_instance_id | target role_instance_id |
|---|---|---|
| `RAFISS001` | `EXECUTION-DECISION-PAYLOAD` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` |
| `RAFISS002` | `EXECUTION-DECISION-SIGNATURE` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` |
| `RAFISS003` | `AUTHORITY-AGGREGATE-ACTIVATION` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` |
| `RAFISS004` | `RECOVERY-STAGE-C-SUBJECT` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` |
| `RAFISS005` | `RECOVERY-STAGE-C-SUBJECT-REVIEW-PAIR` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` |
| `RAFISS006` | `RECOVERY-STAGE-C-APPROVAL-PAYLOAD` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` |
| `RAFISS007` | `RECOVERY-STAGE-C-APPROVAL-RECEIPT` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` |
| `RAFISS008` | `APPLICATION-RECEIPT-TARGET-SPEC` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` |
| `RAFISS009` | `POST-CLOSE-FINALIZATION-GRANT-PAYLOAD` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` |
| `RAFISS010` | `POST-CLOSE-FINALIZATION-GRANT-SIGNATURE` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` |
| `RAFISS011` | `TERMINAL-WRAPPER-RECOVERY-GRANT-PAYLOAD` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` |
| `RAFISS012` | `TERMINAL-WRAPPER-RECOVERY-GRANT-SIGNATURE` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` |
| `RAFISS013` | `RECOVERY-APPLICATION-CURRENT-HEAD-OBSERVATION` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` |
| `RAFISS014` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` | `RECOVERY-APPLICATION-ISSUANCE-SIGNATURE` |
| `RAFCON001` | `RECOVERY-APPLICATION-ISSUANCE-SIGNATURE` | `RECOVERY-APPLICATION-CONSUME-TRANSITION` |

`StageCIssuanceEdgeRegistryV3` is the exact ordered concatenation of the three
literal tables. Its exact static counts are
`ordinary=15,recovery_suffix=15,recovery_application=15,total=45`; exactly one
branch materializes `15`. Its
digest is
`SHA256(ASCII("WS-WALKSAFE-R007-STAGE-C-ISSUANCE-EDGE-REGISTRY-V2") ||
0x00 || RFC8785_JCS(ordered_edge_rows))`. Duplicate edge IDs or tuples,
undefined role-instance endpoints and conceptual label endpoints all reject.

### 7.3 canonical application payload/signature pair

There is one canonical pair and no H1/B04 copy:

```text
H2_ROOT_TEMPLATE/stage-c/application-receipt.payload.json
  schema = STAGE_C_APPLICATION_PAYLOAD_V2
  publisher = STAGE_C_APPLICATION_FINALIZER

H2_ROOT_TEMPLATE/stage-c/application-receipt.signature.json
  schema = STAGE_C_APPLICATION_DETACHED_SIGNATURE_V2
  publisher = STAGE_C_APPLICATION_SIGNER
```

`StageCApplicationPhaseContextV2` exact fields:

```text
schema
successor_revision_id + attempt_namespace_id + attempt_id
h2_transaction_id
literal_h2_root
selected_stage_c_evidence:
  {"kind":"ORDINARY_STAGE_C",
   "stage_c_subject_sha":Sha256Hex,
   "stage_c_subject_physical":FilePhysicalV2,
   "stage_c_approval_receipt_sha":Sha256Hex,
   "stage_c_approval_receipt_physical":FilePhysicalV2}
  XOR
  {"kind":"RECOVERY_STAGE_C",
   "recovery_stage_c_subject_sha":Sha256Hex,
   "recovery_stage_c_subject_physical":FilePhysicalV2,
   "recovery_stage_c_approval_receipt_sha":Sha256Hex,
   "recovery_stage_c_approval_receipt_physical":FilePhysicalV2}
application_target_spec_sha + application_target_spec_physical
candidate_payload_sha + candidate_payload_physical
candidate_sha
activation_receipt_sha + activation_receipt_physical
aggregate_key_digest + authority_consume_expected_pre_token
current_head_observation: ArtifactBindingV2
current_head_snapshot_digest
operation_not_after + settlement_not_after
```

`ApplicationReceiptTargetSpecV2` is the source of the candidate binding. Its
exact fields are
`schema,target_literal_path,target_schema_role,target_schema_sha,
candidate_payload_sha,candidate_payload_physical,candidate_sha,
allowed_application_variants,expected_pair_count=2,publisher_actor_id,
publisher_physical_sha,signature_domain,signature_algorithm,signature`.
`candidate_sha` is the immutable candidate content identity carried by the
already existing target spec; it is not the application payload's own SHA.

`StageCApplicationPayloadV2` exact fields:

```text
schema
application_variant:
  ORDINARY_STAGE_C | RECOVERY_APPLICATION_FINALIZATION
phase_context: StageCApplicationPhaseContextV2
application_target:
  target_spec_jcs,target_spec_jcs_sha,target_literal_path,
  target_schema_role,target_schema_sha
runtime_binding:
  runtime_branch,h2_transaction_id,literal_h2_root,
  h2_binding_payload_sha,h2_binding_signature_sha,
  h2_binding_payload_physical,h2_binding_signature_physical
live_root_triple:
  manifest_payload_sha + manifest_payload_physical
  physical_publication_receipt_sha + receipt_physical
  live_root_published_progress_sha + progress_physical
  member_count=3 + ordered_member_digest
authority_issuance:
  payload_sha,signature_sha,payload_physical,signature_physical,
  variant,capability_digest,issued_at
authority_consume:
  transition_receipt_sha,transition_receipt_physical,
  transition_id,core_digest,linearized_at
postcheck:
  progress_sha,progress_physical,status=PASSED,passed_at
exact6_results[6]:
  ordinal=1..6,invocation_id,intent_sha,intent_physical,
  stdout_sha,stdout_physical,stderr_sha,stderr_physical,
  access_trace_raw_sha,access_trace_raw_physical,
  access_trace_json_sha,access_trace_json_physical,
  result_sha,result_physical,result_status=PASS,assertion_digest
exact6_result_count=6 + exact6_result_digest
finalizing_executor:
  actor_id,actor_physical_sha,executable_sha,literal_argv
terminal_claim:
  claim="STAGE_C_APPLICATION_COMPLETE_CANDIDATE",
  candidate_sha,exact6_pass_count=6,postcheck_status=PASSED,
  application_output_count=2
published_at
publisher_actor_id + publisher_physical_sha
signature_domain =
  WS-WALKSAFE-R007-STAGE-C-APPLICATION-PAYLOAD-V2
```

The payload contains no own SHA, signature SHA, FINALIZED SHA or CLOSED SHA.
`terminal_claim.candidate_sha` must equal both
`phase_context.candidate_sha` and the candidate SHA inside APPIN001's
`ApplicationReceiptTargetSpecV2`; APPIN001's content SHA/Physical and candidate
payload SHA/Physical are the complete predecessor authority for that value.
Signature wrapper exact fields are
`schema,payload_path,payload_schema_sha,payload_sha,payload_physical,
signer_actor_id,signer_physical_sha,signature_algorithm,signature_domain,
signature`. Signing input is the literal application domain, NUL and full
payload JCS.

### 7.4 static 57, materialized 52 and branch-exact application edges

`STAGE-C-RUNTIME-BINDING` is one primary `RoleInstanceRegistryRowV2`, not a
prose alias:

| role_instance_id | exact path template | schema role | publisher role | Physical kind |
|---|---|---|---|---|
| `STAGE-C-RUNTIME-BINDING` | `H2_ROOT_TEMPLATE/stage-c/runtime-target-binding.json` | `STAGE_C_RUNTIME_TARGET_BINDING_V3` | `STAGE_C_RUNTIME_BINDING_SERIALIZER` | `FILE` |

Its signed body has exactly
`schema,successor_revision_id,attempt_namespace_id,attempt_id,
h2_transaction_id,literal_h2_root,selected_runtime_branch,phase_context_sha,
phase_context_physical,application_target_spec:ArtifactBindingV2,
h2_binding_payload:ArtifactBindingV2,
h2_binding_signature:ArtifactBindingV2,authority_source_kind,publisher_actor_id,
publisher_physical_sha,signature_domain`. `selected_runtime_branch` and
`authority_source_kind` are the same exact literal
`ORDINARY_STAGE_C` or `RECOVERY_APPLICATION_FINALIZATION`; the latter is not
available to `RECOVERY_EXACT6_SUFFIX`. Signature domain is
`WS-WALKSAFE-R007-STAGE-C-RUNTIME-BINDING-V3`; the one-file envelope signs
that domain, NUL and full body JCS. The body excludes its own SHA/Physical,
the application payload/signature, finalization and close outputs, and every
future reference. Its resolved row carries the frozen schema SHA, publisher
actor ID, publisher executable Physical SHA, content SHA and
`FilePhysicalV2`.

The 48 original application inbound rows are:

| edge | source role/path | schema |
|---|---|---|
| `APPIN001` | `APPLICATION-RECEIPT-TARGET-SPEC` / `H1_ROOT_TEMPLATE/successor/bundles/closure-schemas/application-receipt-target-spec.json` | `APPLICATION_RECEIPT_TARGET_SPEC_V2` |
| `APPIN002` | `STAGE-C-RUNTIME-BINDING` / `H2_ROOT_TEMPLATE/stage-c/runtime-target-binding.json` | `STAGE_C_RUNTIME_TARGET_BINDING_V3` |
| `APPIN003` | `STAGE-C-SUBJECT` / `ORDINARY_JOURNAL_TEMPLATE/stage-c-subject.json` | `STAGE_C_SUBJECT_V2` |
| `APPIN004` | `STAGE-C-APPROVAL-RECEIPT` / `ORDINARY_JOURNAL_TEMPLATE/stage-c-approval.receipt.json` | `STAGE_C_APPROVAL_RECEIPT_V2` |
| `APPIN005` | `H2-BINDING-PAYLOAD` / `H2_ROOT_TEMPLATE/stage-c/h2-literal-root-binding.payload.json` | `H2_LITERAL_ROOT_BINDING_PAYLOAD_V2` |
| `APPIN006` | `H2-BINDING-SIGNATURE` / `H2_ROOT_TEMPLATE/stage-c/h2-literal-root-binding.signature.json` | `H2_LITERAL_ROOT_BINDING_SIGNATURE_V2` |
| `APPIN007` | `ACTUAL-EXACT6-INPUT` / `H2_ROOT_TEMPLATE/stage-c/actual-exact6-input.json` | `STAGE_C_ACTUAL_EXACT6_INPUT_V2` |
| `APPIN008` | `LIVE-ROOT-INTEGRATION` / `H2_ROOT_TEMPLATE/stage-c/live-root-integration-receipt.json` | `STAGE_C_LIVE_ROOT_INTEGRATION_RECEIPT_V2` |
| `APPIN009` | `STAGE-C-ISSUANCE-PAYLOAD` / `ORDINARY_JOURNAL_TEMPLATE/authority-issuance.payload.json` | `STAGE_C_AUTHORITY_ISSUANCE_PAYLOAD_V2` |
| `APPIN010` | `STAGE-C-ISSUANCE-SIGNATURE` / `ORDINARY_JOURNAL_TEMPLATE/authority-issuance.signature.json` | `STAGE_C_AUTHORITY_ISSUANCE_SIGNATURE_V2` |
| `APPIN011` | `STAGE-C-CONSUME-TRANSITION` / `CasLogicalRecordPathV2("AUTHORITY_AGGREGATE_CAS_SERVICE",ordinary_consume_key_digest)` | `STAGE_C_AUTHORITY_CONSUME_TRANSITION_RECEIPT_V2` |
| `APPIN012` | `POSTCHECK-PASSED` / `H2_ROOT_TEMPLATE/stage-c/postcheck-passed.progress.json` | `STAGE_C_POSTCHECK_PASSED_PROGRESS_V2` |
| `APPIN013` | `H2-INV001-INTENT` / `H2_ROOT_TEMPLATE/stage-c/invocations/001-live-exact26/intent.json` | `H2_INVOCATION_INTENT_V2` |
| `APPIN014` | `H2-INV001-STDOUT` / `H2_ROOT_TEMPLATE/stage-c/invocations/001-live-exact26/stdout.raw` | `H2_FRAMED_STDOUT_V2` |
| `APPIN015` | `H2-INV001-STDERR` / `H2_ROOT_TEMPLATE/stage-c/invocations/001-live-exact26/stderr.raw` | `H2_FRAMED_STDERR_V2` |
| `APPIN016` | `H2-INV001-ACCESS-RAW` / `H2_ROOT_TEMPLATE/stage-c/invocations/001-live-exact26/access-trace.raw` | `H2_FRAMED_ACCESS_TRACE_V2` |
| `APPIN017` | `H2-INV001-ACCESS-JSON` / `H2_ROOT_TEMPLATE/stage-c/invocations/001-live-exact26/access-trace.json` | `H2_ACCESS_TRACE_V2` |
| `APPIN018` | `H2-INV001-RESULT` / `H2_ROOT_TEMPLATE/stage-c/invocations/001-live-exact26/result.json` | `H2_INVOCATION_RESULT_V2` |
| `APPIN019` | `H2-INV002-INTENT` / `H2_ROOT_TEMPLATE/stage-c/invocations/002-activation-seal/intent.json` | `H2_INVOCATION_INTENT_V2` |
| `APPIN020` | `H2-INV002-STDOUT` / `H2_ROOT_TEMPLATE/stage-c/invocations/002-activation-seal/stdout.raw` | `H2_FRAMED_STDOUT_V2` |
| `APPIN021` | `H2-INV002-STDERR` / `H2_ROOT_TEMPLATE/stage-c/invocations/002-activation-seal/stderr.raw` | `H2_FRAMED_STDERR_V2` |
| `APPIN022` | `H2-INV002-ACCESS-RAW` / `H2_ROOT_TEMPLATE/stage-c/invocations/002-activation-seal/access-trace.raw` | `H2_FRAMED_ACCESS_TRACE_V2` |
| `APPIN023` | `H2-INV002-ACCESS-JSON` / `H2_ROOT_TEMPLATE/stage-c/invocations/002-activation-seal/access-trace.json` | `H2_ACCESS_TRACE_V2` |
| `APPIN024` | `H2-INV002-RESULT` / `H2_ROOT_TEMPLATE/stage-c/invocations/002-activation-seal/result.json` | `H2_INVOCATION_RESULT_V2` |
| `APPIN025` | `H2-INV003-INTENT` / `H2_ROOT_TEMPLATE/stage-c/invocations/003-continuation-quick/intent.json` | `H2_INVOCATION_INTENT_V2` |
| `APPIN026` | `H2-INV003-STDOUT` / `H2_ROOT_TEMPLATE/stage-c/invocations/003-continuation-quick/stdout.raw` | `H2_FRAMED_STDOUT_V2` |
| `APPIN027` | `H2-INV003-STDERR` / `H2_ROOT_TEMPLATE/stage-c/invocations/003-continuation-quick/stderr.raw` | `H2_FRAMED_STDERR_V2` |
| `APPIN028` | `H2-INV003-ACCESS-RAW` / `H2_ROOT_TEMPLATE/stage-c/invocations/003-continuation-quick/access-trace.raw` | `H2_FRAMED_ACCESS_TRACE_V2` |
| `APPIN029` | `H2-INV003-ACCESS-JSON` / `H2_ROOT_TEMPLATE/stage-c/invocations/003-continuation-quick/access-trace.json` | `H2_ACCESS_TRACE_V2` |
| `APPIN030` | `H2-INV003-RESULT` / `H2_ROOT_TEMPLATE/stage-c/invocations/003-continuation-quick/result.json` | `H2_INVOCATION_RESULT_V2` |
| `APPIN031` | `H2-INV004-INTENT` / `H2_ROOT_TEMPLATE/stage-c/invocations/004-goal-quick/intent.json` | `H2_INVOCATION_INTENT_V2` |
| `APPIN032` | `H2-INV004-STDOUT` / `H2_ROOT_TEMPLATE/stage-c/invocations/004-goal-quick/stdout.raw` | `H2_FRAMED_STDOUT_V2` |
| `APPIN033` | `H2-INV004-STDERR` / `H2_ROOT_TEMPLATE/stage-c/invocations/004-goal-quick/stderr.raw` | `H2_FRAMED_STDERR_V2` |
| `APPIN034` | `H2-INV004-ACCESS-RAW` / `H2_ROOT_TEMPLATE/stage-c/invocations/004-goal-quick/access-trace.raw` | `H2_FRAMED_ACCESS_TRACE_V2` |
| `APPIN035` | `H2-INV004-ACCESS-JSON` / `H2_ROOT_TEMPLATE/stage-c/invocations/004-goal-quick/access-trace.json` | `H2_ACCESS_TRACE_V2` |
| `APPIN036` | `H2-INV004-RESULT` / `H2_ROOT_TEMPLATE/stage-c/invocations/004-goal-quick/result.json` | `H2_INVOCATION_RESULT_V2` |
| `APPIN037` | `H2-INV005-INTENT` / `H2_ROOT_TEMPLATE/stage-c/invocations/005-routing-validate/intent.json` | `H2_INVOCATION_INTENT_V2` |
| `APPIN038` | `H2-INV005-STDOUT` / `H2_ROOT_TEMPLATE/stage-c/invocations/005-routing-validate/stdout.raw` | `H2_FRAMED_STDOUT_V2` |
| `APPIN039` | `H2-INV005-STDERR` / `H2_ROOT_TEMPLATE/stage-c/invocations/005-routing-validate/stderr.raw` | `H2_FRAMED_STDERR_V2` |
| `APPIN040` | `H2-INV005-ACCESS-RAW` / `H2_ROOT_TEMPLATE/stage-c/invocations/005-routing-validate/access-trace.raw` | `H2_FRAMED_ACCESS_TRACE_V2` |
| `APPIN041` | `H2-INV005-ACCESS-JSON` / `H2_ROOT_TEMPLATE/stage-c/invocations/005-routing-validate/access-trace.json` | `H2_ACCESS_TRACE_V2` |
| `APPIN042` | `H2-INV005-RESULT` / `H2_ROOT_TEMPLATE/stage-c/invocations/005-routing-validate/result.json` | `H2_INVOCATION_RESULT_V2` |
| `APPIN043` | `H2-INV006-INTENT` / `H2_ROOT_TEMPLATE/stage-c/invocations/006-control-and-state/intent.json` | `H2_INVOCATION_INTENT_V2` |
| `APPIN044` | `H2-INV006-STDOUT` / `H2_ROOT_TEMPLATE/stage-c/invocations/006-control-and-state/stdout.raw` | `H2_FRAMED_STDOUT_V2` |
| `APPIN045` | `H2-INV006-STDERR` / `H2_ROOT_TEMPLATE/stage-c/invocations/006-control-and-state/stderr.raw` | `H2_FRAMED_STDERR_V2` |
| `APPIN046` | `H2-INV006-ACCESS-RAW` / `H2_ROOT_TEMPLATE/stage-c/invocations/006-control-and-state/access-trace.raw` | `H2_FRAMED_ACCESS_TRACE_V2` |
| `APPIN047` | `H2-INV006-ACCESS-JSON` / `H2_ROOT_TEMPLATE/stage-c/invocations/006-control-and-state/access-trace.json` | `H2_ACCESS_TRACE_V2` |
| `APPIN048` | `H2-INV006-RESULT` / `H2_ROOT_TEMPLATE/stage-c/invocations/006-control-and-state/result.json` | `H2_INVOCATION_RESULT_V2` |

Every source row also carries publisher actor ID, publisher Physical SHA,
content SHA and output `FilePhysicalV2` (or `CasRecordPhysicalV2` for
`APPIN011`) in the runtime instance registry. The five recovery-application
counterparts are distinct edge IDs and exact endpoints:

| edge | exact source role/path/schema | exact target role/path/schema |
|---|---|---|
| `RAPPIN001` | `RECOVERY-STAGE-C-SUBJECT` / `RECOVERY_JOURNAL_TEMPLATE/stage-c-subject.json` / `RECOVERY_STAGE_C_SUBJECT_V2` | `STAGE-C-APPLICATION-PAYLOAD` / `H2_ROOT_TEMPLATE/stage-c/application-receipt.payload.json` / `STAGE_C_APPLICATION_PAYLOAD_V2` |
| `RAPPIN002` | `RECOVERY-STAGE-C-APPROVAL-RECEIPT` / `RECOVERY_JOURNAL_TEMPLATE/stage-c-approval.receipt.json` / `RECOVERY_STAGE_C_APPROVAL_RECEIPT_V2` | `STAGE-C-APPLICATION-PAYLOAD` / `H2_ROOT_TEMPLATE/stage-c/application-receipt.payload.json` / `STAGE_C_APPLICATION_PAYLOAD_V2` |
| `RAPPIN003` | `RECOVERY-APPLICATION-ISSUANCE-PAYLOAD` / `RECOVERY_JOURNAL_TEMPLATE/issuance/application-finalization/authority-issuance.payload.json` / `STAGE_C_AUTHORITY_ISSUANCE_PAYLOAD_V2` | `STAGE-C-APPLICATION-PAYLOAD` / `H2_ROOT_TEMPLATE/stage-c/application-receipt.payload.json` / `STAGE_C_APPLICATION_PAYLOAD_V2` |
| `RAPPIN004` | `RECOVERY-APPLICATION-ISSUANCE-SIGNATURE` / `RECOVERY_JOURNAL_TEMPLATE/issuance/application-finalization/authority-issuance.signature.json` / `STAGE_C_AUTHORITY_ISSUANCE_SIGNATURE_V2` | `STAGE-C-APPLICATION-PAYLOAD` / `H2_ROOT_TEMPLATE/stage-c/application-receipt.payload.json` / `STAGE_C_APPLICATION_PAYLOAD_V2` |
| `RAPPIN005` | `RECOVERY-APPLICATION-CONSUME-TRANSITION` / `CasLogicalRecordPathV2("AUTHORITY_AGGREGATE_CAS_SERVICE",recovery_application_consume_key_digest)` / `STAGE_C_AUTHORITY_CONSUME_TRANSITION_RECEIPT_V2` | `STAGE-C-APPLICATION-PAYLOAD` / `H2_ROOT_TEMPLATE/stage-c/application-receipt.payload.json` / `STAGE_C_APPLICATION_PAYLOAD_V2` |

These five rows carry the same full publisher/content/Physical identity rule
as `APPIN003`,`APPIN004`,`APPIN009`–`APPIN011`. The application payload and
runtime binding carry one closed authority-source body, but that body does
not collapse graph edge identities:

```text
StageCApplicationAuthoritySourceV3 =
  {"kind":"ORDINARY_STAGE_C",
   "stage_c_subject":ArtifactBindingV2,
   "stage_c_approval_receipt":ArtifactBindingV2,
   "issuance_payload":ArtifactBindingV2,
   "issuance_signature":ArtifactBindingV2,
   "issuance_variant":"ORDINARY_STAGE_C",
   "consume_namespace":"ORDINARY_STAGE_C",
   "consume_receipt_sha":Sha256Hex,
   "consume_receipt_physical":CasRecordPhysicalV2}
  XOR
  {"kind":"RECOVERY_APPLICATION_FINALIZATION",
   "recovery_stage_c_subject":ArtifactBindingV2,
   "recovery_stage_c_approval_receipt":ArtifactBindingV2,
   "issuance_payload":ArtifactBindingV2,
   "issuance_signature":ArtifactBindingV2,
   "issuance_variant":"RECOVERY_APPLICATION_FINALIZATION",
   "consume_namespace":"RECOVERY_APPLICATION_FINALIZATION",
   "consume_receipt_sha":Sha256Hex,
   "consume_receipt_physical":CasRecordPhysicalV2}
```

For `ORDINARY_STAGE_C`, subject, approval receipt and the two issuance binding
paths must equal their rows under `ORDINARY_JOURNAL_TEMPLATE`; for
`RECOVERY_APPLICATION_FINALIZATION`, all four must equal their rows under
`RECOVERY_JOURNAL_TEMPLATE` and its application-finalization issuance root.
The ordinary branch materializes `APPIN003`,`APPIN004`,`APPIN009`–`APPIN011`;
the recovery application branch materializes `RAPPIN001`–`RAPPIN005`.
`StageCApplicationPayloadV2.application_variant`, the issuance payload's
`variant_binding.kind` and the consume receipt's `variant_binding` must
byte-equal `ORDINARY_STAGE_C` or
`RECOVERY_APPLICATION_FINALIZATION`. The corresponding
`phase_context.selected_stage_c_evidence.kind` is mapped exactly to
`ORDINARY_STAGE_C` or `RECOVERY_STAGE_C`. The complete phase-context JCS
carried by issuance, consume and application must be byte-identical. The
consume namespace and key must match that branch: `ORDINARY_STAGE_C` uses
`ordinary_consume_key_digest`; `RECOVERY_APPLICATION_FINALIZATION` uses
`recovery_application_consume_key_digest`.
Mixed ordinary/recovery subject, approval, issuance or consume members reject.

`RECOVERY_EXACT6_SUFFIX` is deliberately absent from this union. Its issuance
has `application_pair_publication_count=0`; an application intent, payload,
signature or application-edge materialization from that variant is
invalid. Only `RECOVERY_APPLICATION_FINALIZATION`, with an exact existing
`POSTCHECK_PASSED`, six durable results, empty dispatch set and matching
recovery phase context, may publish the recovery application pair.
Exactly one branch materializes. Thus 43 common original inbound edges plus
one selected five-edge authority branch give 48 original inbound edges on
either runtime path.

Triple, signing and tail rows are literal:

| edge ID | source role / literal path / schema | target role / literal path / schema |
|---|---|---|
| `APPIN049` | `LIVE-ROOT-MANIFEST-PAYLOAD` / `H2_ROOT_TEMPLATE/stage-c/live-root-manifest.payload.json` / `STAGE_C_LIVE_ROOT_MANIFEST_PAYLOAD_V2` | `STAGE-C-APPLICATION-PAYLOAD` / `H2_ROOT_TEMPLATE/stage-c/application-receipt.payload.json` / `STAGE_C_APPLICATION_PAYLOAD_V2` |
| `APPIN050` | `LIVE-ROOT-PHYSICAL-PUBLICATION-RECEIPT` / `H2_ROOT_TEMPLATE/stage-c/live-root-physical-publication-receipt.json` / `STAGE_C_LIVE_ROOT_PHYSICAL_PUBLICATION_RECEIPT_V2` | `STAGE-C-APPLICATION-PAYLOAD` / `H2_ROOT_TEMPLATE/stage-c/application-receipt.payload.json` / `STAGE_C_APPLICATION_PAYLOAD_V2` |
| `APPIN051` | `LIVE-ROOT-PUBLISHED-PROGRESS` / `H2_ROOT_TEMPLATE/stage-c/live-root-published.progress.json` / `STAGE_C_LIVE_ROOT_PUBLISHED_PROGRESS_V2` | `STAGE-C-APPLICATION-PAYLOAD` / `H2_ROOT_TEMPLATE/stage-c/application-receipt.payload.json` / `STAGE_C_APPLICATION_PAYLOAD_V2` |
| `APPIN052` | `STAGE-C-APPLICATION-PAYLOAD` / `H2_ROOT_TEMPLATE/stage-c/application-receipt.payload.json` / `STAGE_C_APPLICATION_PAYLOAD_V2` | `STAGE-C-APPLICATION-SIGNATURE` / `H2_ROOT_TEMPLATE/stage-c/application-receipt.signature.json` / `STAGE_C_APPLICATION_DETACHED_SIGNATURE_V2` |
| `APPTAIL001` | `STAGE-C-APPLICATION-SIGNATURE` / `H2_ROOT_TEMPLATE/stage-c/application-receipt.signature.json` / `STAGE_C_APPLICATION_DETACHED_SIGNATURE_V2` | `STAGE-C-FINALIZED-PROGRESS` / `H2_ROOT_TEMPLATE/stage-c/finalized.progress.json` / `STAGE_C_FINALIZED_PROGRESS_V2` |
| `APPTAIL002` | `STAGE-C-FINALIZED-PROGRESS` / `H2_ROOT_TEMPLATE/stage-c/finalized.progress.json` / `STAGE_C_FINALIZED_PROGRESS_V2` | `STAGE-C-CLOSED-SUCCESS` / `H2_ROOT_TEMPLATE/stage-c/closed-success-receipt.json` / `STAGE_C_CLOSED_SUCCESS_RECEIPT_V2` |

Every source and target row also carries the publisher actor, publisher
Physical SHA, content SHA and output `FilePhysicalV2` in the runtime registry.

`StageCApplicationEdgeRegistryV3` is the exact static ordered concatenation
`APPIN001..APPIN052,RAPPIN001..RAPPIN005`. It has 57 rows; at materialization
the five non-selected authority rows are absent, leaving exactly 52 pair
lineage edges. Duplicate edge IDs/tuples, a mixed branch, or a conceptual
endpoint rejects. Its digest is
`SHA256(ASCII("WS-WALKSAFE-R007-STAGE-C-APPLICATION-EDGE-REGISTRY-V3") ||
0x00 || RFC8785_JCS(ordered_full_edge_rows))`.

Counts:

```text
common original inbound = 43
selected authority branch inbound = 5
materialized original inbound = 48
LIVE_ROOT_TRIPLE direct inbound = 3
payload→signature = 1
materialized application pair lineage = 52
static application edge union = 57
ordinary issuance lineage = 14
ordinary consume edge = 1
ordinary terminal tail = 2
ordinary actual total = 14 + 1 + 52 + 2 = 69
recovery-application issuance lineage = 14
recovery-application consume edge = 1
recovery-application terminal tail = 2
recovery-application actual total = 14 + 1 + 52 + 2 = 69
```

These values come from the rows above; no `51/68` compatibility alias remains.

## 8. H2 exact-six 25-batch guarded publication

이 절은 `50 file roles / 25 guard receipts / 75 project outputs`를 닫는다.

### 8.1 exact 50 file roles

| group | exact roles | count |
|---|---|---:|
| H2 binding | payload, signature | 2 |
| checkpoint | durability receipt | 1 |
| live-root manifest | payload, signature | 2 |
| live-root publication | physical receipt, published progress | 2 |
| actual exact6 input | one signed JSON envelope | 1 |
| six invocations | each `intent.json`, `stdout.raw`, `stderr.raw`, `access-trace.raw`, `access-trace.json`, `result.json` | 36 |
| integration/postcheck | integration receipt, postcheck progress | 2 |
| canonical application | payload, signature | 2 |
| final tail | finalized progress, closed-success receipt | 2 |
| **total** |  | **50** |

The exact 50 RoleTemplate rows are:

| ord | role ID | literal path template | schema role | publisher role |
|---:|---|---|---|---|
| 1 | `H2-BINDING-PAYLOAD` | `H2_ROOT_TEMPLATE/stage-c/h2-literal-root-binding.payload.json` | `H2_LITERAL_ROOT_BINDING_PAYLOAD_V2` | `H2_BINDING_SERIALIZER` |
| 2 | `H2-BINDING-SIGNATURE` | `H2_ROOT_TEMPLATE/stage-c/h2-literal-root-binding.signature.json` | `H2_LITERAL_ROOT_BINDING_SIGNATURE_V2` | `H2_BINDING_SIGNER` |
| 3 | `H2-CHECKPOINT-DURABILITY` | `H2_ROOT_TEMPLATE/stage-c/checkpoint-durability-receipt.json` | `H2_CHECKPOINT_DURABILITY_RECEIPT_V2` | `H2_CHECKPOINT_SERVICE` |
| 4 | `LIVE-ROOT-MANIFEST-PAYLOAD` | `H2_ROOT_TEMPLATE/stage-c/live-root-manifest.payload.json` | `STAGE_C_LIVE_ROOT_MANIFEST_PAYLOAD_V2` | `LIVE_ROOT_MANIFEST_SERIALIZER` |
| 5 | `LIVE-ROOT-MANIFEST-SIGNATURE` | `H2_ROOT_TEMPLATE/stage-c/live-root-manifest.signature.json` | `STAGE_C_LIVE_ROOT_MANIFEST_SIGNATURE_V2` | `LIVE_ROOT_MANIFEST_SIGNER` |
| 6 | `LIVE-ROOT-PHYSICAL-PUBLICATION-RECEIPT` | `H2_ROOT_TEMPLATE/stage-c/live-root-physical-publication-receipt.json` | `STAGE_C_LIVE_ROOT_PHYSICAL_PUBLICATION_RECEIPT_V2` | `LIVE_ROOT_PUBLICATION_SERVICE` |
| 7 | `LIVE-ROOT-PUBLISHED-PROGRESS` | `H2_ROOT_TEMPLATE/stage-c/live-root-published.progress.json` | `STAGE_C_LIVE_ROOT_PUBLISHED_PROGRESS_V2` | `LIVE_ROOT_PROGRESS_SERIALIZER` |
| 8 | `ACTUAL-EXACT6-INPUT` | `H2_ROOT_TEMPLATE/stage-c/actual-exact6-input.json` | `STAGE_C_ACTUAL_EXACT6_INPUT_V2` | `H2_EXACT6_INPUT_SERIALIZER` |
| 9 | `H2-INV001-INTENT` | `H2_ROOT_TEMPLATE/stage-c/invocations/001-live-exact26/intent.json` | `H2_INVOCATION_INTENT_V2` | `H2_INV001_INTENT_SERIALIZER` |
| 10 | `H2-INV001-STDOUT` | `H2_ROOT_TEMPLATE/stage-c/invocations/001-live-exact26/stdout.raw` | `H2_FRAMED_STDOUT_V2` | `H2_INV001_EXECUTOR` |
| 11 | `H2-INV001-STDERR` | `H2_ROOT_TEMPLATE/stage-c/invocations/001-live-exact26/stderr.raw` | `H2_FRAMED_STDERR_V2` | `H2_INV001_EXECUTOR` |
| 12 | `H2-INV001-ACCESS-RAW` | `H2_ROOT_TEMPLATE/stage-c/invocations/001-live-exact26/access-trace.raw` | `H2_FRAMED_ACCESS_TRACE_V2` | `H2_INV001_EXECUTOR` |
| 13 | `H2-INV001-ACCESS-JSON` | `H2_ROOT_TEMPLATE/stage-c/invocations/001-live-exact26/access-trace.json` | `H2_ACCESS_TRACE_V2` | `H2_INV001_TRACE_SERIALIZER` |
| 14 | `H2-INV001-RESULT` | `H2_ROOT_TEMPLATE/stage-c/invocations/001-live-exact26/result.json` | `H2_INVOCATION_RESULT_V2` | `H2_INV001_RESULT_SERIALIZER` |
| 15 | `H2-INV002-INTENT` | `H2_ROOT_TEMPLATE/stage-c/invocations/002-activation-seal/intent.json` | `H2_INVOCATION_INTENT_V2` | `H2_INV002_INTENT_SERIALIZER` |
| 16 | `H2-INV002-STDOUT` | `H2_ROOT_TEMPLATE/stage-c/invocations/002-activation-seal/stdout.raw` | `H2_FRAMED_STDOUT_V2` | `H2_INV002_EXECUTOR` |
| 17 | `H2-INV002-STDERR` | `H2_ROOT_TEMPLATE/stage-c/invocations/002-activation-seal/stderr.raw` | `H2_FRAMED_STDERR_V2` | `H2_INV002_EXECUTOR` |
| 18 | `H2-INV002-ACCESS-RAW` | `H2_ROOT_TEMPLATE/stage-c/invocations/002-activation-seal/access-trace.raw` | `H2_FRAMED_ACCESS_TRACE_V2` | `H2_INV002_EXECUTOR` |
| 19 | `H2-INV002-ACCESS-JSON` | `H2_ROOT_TEMPLATE/stage-c/invocations/002-activation-seal/access-trace.json` | `H2_ACCESS_TRACE_V2` | `H2_INV002_TRACE_SERIALIZER` |
| 20 | `H2-INV002-RESULT` | `H2_ROOT_TEMPLATE/stage-c/invocations/002-activation-seal/result.json` | `H2_INVOCATION_RESULT_V2` | `H2_INV002_RESULT_SERIALIZER` |
| 21 | `H2-INV003-INTENT` | `H2_ROOT_TEMPLATE/stage-c/invocations/003-continuation-quick/intent.json` | `H2_INVOCATION_INTENT_V2` | `H2_INV003_INTENT_SERIALIZER` |
| 22 | `H2-INV003-STDOUT` | `H2_ROOT_TEMPLATE/stage-c/invocations/003-continuation-quick/stdout.raw` | `H2_FRAMED_STDOUT_V2` | `H2_INV003_EXECUTOR` |
| 23 | `H2-INV003-STDERR` | `H2_ROOT_TEMPLATE/stage-c/invocations/003-continuation-quick/stderr.raw` | `H2_FRAMED_STDERR_V2` | `H2_INV003_EXECUTOR` |
| 24 | `H2-INV003-ACCESS-RAW` | `H2_ROOT_TEMPLATE/stage-c/invocations/003-continuation-quick/access-trace.raw` | `H2_FRAMED_ACCESS_TRACE_V2` | `H2_INV003_EXECUTOR` |
| 25 | `H2-INV003-ACCESS-JSON` | `H2_ROOT_TEMPLATE/stage-c/invocations/003-continuation-quick/access-trace.json` | `H2_ACCESS_TRACE_V2` | `H2_INV003_TRACE_SERIALIZER` |
| 26 | `H2-INV003-RESULT` | `H2_ROOT_TEMPLATE/stage-c/invocations/003-continuation-quick/result.json` | `H2_INVOCATION_RESULT_V2` | `H2_INV003_RESULT_SERIALIZER` |
| 27 | `H2-INV004-INTENT` | `H2_ROOT_TEMPLATE/stage-c/invocations/004-goal-quick/intent.json` | `H2_INVOCATION_INTENT_V2` | `H2_INV004_INTENT_SERIALIZER` |
| 28 | `H2-INV004-STDOUT` | `H2_ROOT_TEMPLATE/stage-c/invocations/004-goal-quick/stdout.raw` | `H2_FRAMED_STDOUT_V2` | `H2_INV004_EXECUTOR` |
| 29 | `H2-INV004-STDERR` | `H2_ROOT_TEMPLATE/stage-c/invocations/004-goal-quick/stderr.raw` | `H2_FRAMED_STDERR_V2` | `H2_INV004_EXECUTOR` |
| 30 | `H2-INV004-ACCESS-RAW` | `H2_ROOT_TEMPLATE/stage-c/invocations/004-goal-quick/access-trace.raw` | `H2_FRAMED_ACCESS_TRACE_V2` | `H2_INV004_EXECUTOR` |
| 31 | `H2-INV004-ACCESS-JSON` | `H2_ROOT_TEMPLATE/stage-c/invocations/004-goal-quick/access-trace.json` | `H2_ACCESS_TRACE_V2` | `H2_INV004_TRACE_SERIALIZER` |
| 32 | `H2-INV004-RESULT` | `H2_ROOT_TEMPLATE/stage-c/invocations/004-goal-quick/result.json` | `H2_INVOCATION_RESULT_V2` | `H2_INV004_RESULT_SERIALIZER` |
| 33 | `H2-INV005-INTENT` | `H2_ROOT_TEMPLATE/stage-c/invocations/005-routing-validate/intent.json` | `H2_INVOCATION_INTENT_V2` | `H2_INV005_INTENT_SERIALIZER` |
| 34 | `H2-INV005-STDOUT` | `H2_ROOT_TEMPLATE/stage-c/invocations/005-routing-validate/stdout.raw` | `H2_FRAMED_STDOUT_V2` | `H2_INV005_EXECUTOR` |
| 35 | `H2-INV005-STDERR` | `H2_ROOT_TEMPLATE/stage-c/invocations/005-routing-validate/stderr.raw` | `H2_FRAMED_STDERR_V2` | `H2_INV005_EXECUTOR` |
| 36 | `H2-INV005-ACCESS-RAW` | `H2_ROOT_TEMPLATE/stage-c/invocations/005-routing-validate/access-trace.raw` | `H2_FRAMED_ACCESS_TRACE_V2` | `H2_INV005_EXECUTOR` |
| 37 | `H2-INV005-ACCESS-JSON` | `H2_ROOT_TEMPLATE/stage-c/invocations/005-routing-validate/access-trace.json` | `H2_ACCESS_TRACE_V2` | `H2_INV005_TRACE_SERIALIZER` |
| 38 | `H2-INV005-RESULT` | `H2_ROOT_TEMPLATE/stage-c/invocations/005-routing-validate/result.json` | `H2_INVOCATION_RESULT_V2` | `H2_INV005_RESULT_SERIALIZER` |
| 39 | `H2-INV006-INTENT` | `H2_ROOT_TEMPLATE/stage-c/invocations/006-control-and-state/intent.json` | `H2_INVOCATION_INTENT_V2` | `H2_INV006_INTENT_SERIALIZER` |
| 40 | `H2-INV006-STDOUT` | `H2_ROOT_TEMPLATE/stage-c/invocations/006-control-and-state/stdout.raw` | `H2_FRAMED_STDOUT_V2` | `H2_INV006_EXECUTOR` |
| 41 | `H2-INV006-STDERR` | `H2_ROOT_TEMPLATE/stage-c/invocations/006-control-and-state/stderr.raw` | `H2_FRAMED_STDERR_V2` | `H2_INV006_EXECUTOR` |
| 42 | `H2-INV006-ACCESS-RAW` | `H2_ROOT_TEMPLATE/stage-c/invocations/006-control-and-state/access-trace.raw` | `H2_FRAMED_ACCESS_TRACE_V2` | `H2_INV006_EXECUTOR` |
| 43 | `H2-INV006-ACCESS-JSON` | `H2_ROOT_TEMPLATE/stage-c/invocations/006-control-and-state/access-trace.json` | `H2_ACCESS_TRACE_V2` | `H2_INV006_TRACE_SERIALIZER` |
| 44 | `H2-INV006-RESULT` | `H2_ROOT_TEMPLATE/stage-c/invocations/006-control-and-state/result.json` | `H2_INVOCATION_RESULT_V2` | `H2_INV006_RESULT_SERIALIZER` |
| 45 | `LIVE-ROOT-INTEGRATION` | `H2_ROOT_TEMPLATE/stage-c/live-root-integration-receipt.json` | `STAGE_C_LIVE_ROOT_INTEGRATION_RECEIPT_V2` | `LIVE_ROOT_INTEGRATION_VERIFIER` |
| 46 | `POSTCHECK-PASSED` | `H2_ROOT_TEMPLATE/stage-c/postcheck-passed.progress.json` | `STAGE_C_POSTCHECK_PASSED_PROGRESS_V2` | `STAGE_C_POSTCHECK_VERIFIER` |
| 47 | `STAGE-C-APPLICATION-PAYLOAD` | `H2_ROOT_TEMPLATE/stage-c/application-receipt.payload.json` | `STAGE_C_APPLICATION_PAYLOAD_V2` | `STAGE_C_APPLICATION_FINALIZER` |
| 48 | `STAGE-C-APPLICATION-SIGNATURE` | `H2_ROOT_TEMPLATE/stage-c/application-receipt.signature.json` | `STAGE_C_APPLICATION_DETACHED_SIGNATURE_V2` | `STAGE_C_APPLICATION_SIGNER` |
| 49 | `STAGE-C-FINALIZED-PROGRESS` | `H2_ROOT_TEMPLATE/stage-c/finalized.progress.json` | `STAGE_C_FINALIZED_PROGRESS_V2` | `STAGE_C_FINALIZATION_SERVICE` |
| 50 | `STAGE-C-CLOSED-SUCCESS` | `H2_ROOT_TEMPLATE/stage-c/closed-success-receipt.json` | `STAGE_C_CLOSED_SUCCESS_RECEIPT_V2` | `STAGE_C_CLOSE_SERVICE` |

Every row has exact schema SHA and publisher Physical SHA at freeze; every
runtime output has `FilePhysicalV2`. The table itself, not the summary, is the
50-row count authority.

The eleven fixed JSON roles 1–7, 45–46 and 49–50 use the closed one-file
`H2FixedJsonEnvelopeV2`:

```text
{
  "body": {
    "schema": one exact schema role from its RoleTemplate row,
    "artifact_kind":
      "H2_BINDING_PAYLOAD"|"H2_BINDING_SIGNATURE"|
      "CHECKPOINT_DURABILITY"|"LIVE_ROOT_MANIFEST_PAYLOAD"|
      "LIVE_ROOT_MANIFEST_SIGNATURE"|"LIVE_ROOT_PHYSICAL_PUBLICATION"|
      "LIVE_ROOT_PUBLISHED_PROGRESS"|"LIVE_ROOT_INTEGRATION"|
      "POSTCHECK_PASSED"|"STAGE_C_FINALIZED_PROGRESS"|
      "STAGE_C_CLOSED_SUCCESS",
    "h2_transaction_id": Identifier,
    "phase_context": StageCApplicationPhaseContextV2,
    "publisher_actor_id": Identifier,
    "publisher_physical_sha": Sha256Hex,
    "signature_domain": exact domain selected by artifact_kind,
    "variant_body": exactly one body below
  },
  "signature": {
    "algorithm": Identifier,
    "signer_actor_id": Identifier,
    "signer_physical_sha": Sha256Hex,
    "signature_domain": exact copy of body.signature_domain,
    "signature": SignatureBytes
  }
}
```

The tagged `variant_body` alternatives have exactly these fields:

```text
H2_BINDING_PAYLOAD:
  h2_literal_root
  ordered_role_template_rows[50] + role_count=50 + role_digest
  ordered_batch_rows[25] + batch_count=25 + batch_digest
  ordered_guard_identity_rows[25]: H2GuardIdentityRowV3
  guard_count=25 + guard_identity_digest

H2_BINDING_SIGNATURE:
  binding_payload: ArtifactBindingV2
  binding_payload_jcs_sha

CHECKPOINT_DURABILITY:
  binding_payload: ArtifactBindingV2
  binding_signature: ArtifactBindingV2
  checkpoint_payload: ArtifactBindingV2
  checkpoint_fsync_result=PASS
  checkpoint_parent_fsync_result=PASS
  checkpoint_reopen_physical: FilePhysicalV2

LIVE_ROOT_MANIFEST_PAYLOAD:
  checkpoint_durability: ArtifactBindingV2
  candidate_sha + candidate_physical
  ordered_live_root_members[]:
    ordinal,role_id,literal_path,schema_sha,content_sha,file_physical
  member_count + member_digest + tree_physical_digest

LIVE_ROOT_MANIFEST_SIGNATURE:
  manifest_payload: ArtifactBindingV2
  manifest_payload_jcs_sha

LIVE_ROOT_PHYSICAL_PUBLICATION:
  checkpoint_durability: ArtifactBindingV2
  manifest_payload: ArtifactBindingV2
  manifest_signature: ArtifactBindingV2
  destination_root_literal_path
  destination_root_physical: DirPhysicalV2
  published_member_count + published_member_digest
  published_tree_physical_digest
  publication_result=PASS

LIVE_ROOT_PUBLISHED_PROGRESS:
  physical_publication_receipt: ArtifactBindingV2
  manifest_payload: ArtifactBindingV2
  manifest_signature: ArtifactBindingV2
  published_root_literal_path
  published_root_physical: DirPhysicalV2
  progress_state=LIVE_ROOT_PUBLISHED

LIVE_ROOT_INTEGRATION:
  live_root_triple[3]: exact ordered ArtifactBindingV2 members
  actual_exact6_input: ArtifactBindingV2
  ordered_invocation_result_bindings[6]
  invocation_result_count=6
  invocation_result_digest
  integration_assertion_result=PASS

POSTCHECK_PASSED:
  live_root_integration: ArtifactBindingV2
  ordered_invocation_result_bindings[6]
  ordered_recomputed_assertions[]
  assertion_count + assertion_digest
  postcheck_state=PASSED

STAGE_C_FINALIZED_PROGRESS:
  postcheck_passed: ArtifactBindingV2
  application_payload: ArtifactBindingV2
  application_signature: ArtifactBindingV2
  finalization_transition_receipt_sha
  finalization_transition_receipt_physical: CasRecordPhysicalV2
  progress_state=FINALIZED

STAGE_C_CLOSED_SUCCESS:
  finalized_progress: ArtifactBindingV2
  application_payload: ArtifactBindingV2
  application_signature: ArtifactBindingV2
  application_batch_settlement_attestation_sha
  application_batch_settlement_attestation_physical: CasRecordPhysicalV2
  finalization_batch_settlement_attestation_sha
  finalization_batch_settlement_attestation_physical: CasRecordPhysicalV2
  close_state=CLOSED_SUCCESS
```

The exact domains, in the same alternative order, are
`WS-WALKSAFE-R007-H2-BINDING-PAYLOAD-V2`,
`WS-WALKSAFE-R007-H2-BINDING-SIGNATURE-V2`,
`WS-WALKSAFE-R007-H2-CHECKPOINT-DURABILITY-V2`,
`WS-WALKSAFE-R007-H2-LIVE-ROOT-MANIFEST-PAYLOAD-V2`,
`WS-WALKSAFE-R007-H2-LIVE-ROOT-MANIFEST-SIGNATURE-V2`,
`WS-WALKSAFE-R007-H2-LIVE-ROOT-PHYSICAL-PUBLICATION-V2`,
`WS-WALKSAFE-R007-H2-LIVE-ROOT-PUBLISHED-PROGRESS-V2`,
`WS-WALKSAFE-R007-H2-LIVE-ROOT-INTEGRATION-V2`,
`WS-WALKSAFE-R007-H2-POSTCHECK-PASSED-V2`,
`WS-WALKSAFE-R007-H2-FINALIZED-PROGRESS-V2` and
`WS-WALKSAFE-R007-H2-CLOSED-SUCCESS-V2`. Signing input is that exact domain,
NUL and `RFC8785_JCS(body)`. Body and signature objects exclude the
envelope's own SHA and output `FilePhysicalV2`; batch settlement supplies
them. A tag/domain/schema/publisher mismatch is invalid.

Six invocation IDs and command counts:

```text
001-live-exact26            1
002-activation-seal         1
003-continuation-quick      1
004-goal-quick              1
005-routing-validate        1
006-control-and-state       2
command-count vector = [1,1,1,1,1,2]
```

All 36 invocation file paths are the literal templates listed in rows 9–44.
The runtime role registry resolves `H2_ROOT_TEMPLATE` and changes no suffix.

### 8.2 exact raw frame and invocation schemas

Each `stdout.raw`, `stderr.raw` and `access-trace.raw` uses this binary frame:

| byte offset | width | encoding | value |
|---:|---:|---|---|
| 0 | 8 | ASCII | `WSH2RAW2` = hex `5753483252415732` |
| 8 | 2 | unsigned big-endian | version `2` |
| 10 | 1 | unsigned | kind: stdout `1`, stderr `2`, access trace `3` |
| 11 | 1 | unsigned | flags `0` |
| 12 | 4 | unsigned big-endian | member count |
| 16+ | repeated | record | member record below |

Member record is:

```text
ordinal: 4-byte unsigned big-endian, starting 1
byte_length: 8-byte unsigned big-endian
sha256: raw 32 bytes
payload: exact byte_length bytes
```

There is no padding or trailer. Member count is `1` for invocation IDs
001, 002, 003, 004 and 005
and `2` for 006. Hash JSON representations use lowercase hex; frame hashes are
raw 32 bytes.

`H2InvocationInputV2` is one row inside `ActualExact6InputV2`:

```text
ordinal=1..6
invocation_id
command_count
ordered_commands[]:
  command_ordinal,literal_argv[],working_directory,
  ordered_environment_entries[{name,value_sha}],
  stdin_sha_or={"kind":"EMPTY"}
ordered_input_refs[]:
  role_id,literal_path,schema_sha,content_sha,physical
ordered_assertion_specs[]:
  assertion_id,predicate_kind,operand_refs[],expected_value
input_digest
```

Predicate kind closed enum:

```text
EXIT_CODE_EQUALS
STDOUT_SHA_EQUALS
STDERR_SHA_EQUALS
ACCESS_PATH_SET_EQUALS
ACCESS_PATH_SUBSET
OUTPUT_SCHEMA_VALID
OUTPUT_CONTENT_DIGEST_EQUALS
COMMAND_BOUNDARY_EQUALS
CURRENT_AUTHORITY_EQUALS
NO_UNDECLARED_WRITE
```

`ActualExact6InputV2` body exact fields:

```text
schema
phase_context: StageCApplicationPhaseContextV2
live_root_triple with exact three SHA/Physical members
ordered_invocation_inputs[6]
invocation_count=6
command_count_vector=[1,1,1,1,1,2]
invocation_input_digest
publisher_actor_id + publisher_physical_sha
signature_domain =
  WS-WALKSAFE-R007-H2-ACTUAL-EXACT6-INPUT-V2
```

`H2InvocationIntentV2` body:

```text
schema
invocation_input: H2InvocationInputV2
actual_exact6_input_sha + Physical
phase_context
prior_batch_guard_sha + Physical
current_aggregate_token + current_revocation_head
stage_c_consume_sha + consume_physical
operation_not_after
dispatch_nonce
publisher_actor_id + publisher_physical_sha
signature_domain =
  WS-WALKSAFE-R007-H2-INVOCATION-INTENT-V2
```

`H2AccessTraceV2` body exact fields:

```text
schema
invocation_id
access_trace_raw_sha + raw_physical
ordered_access_rows[]:
  ordinal,operation=READ|WRITE|EXEC,path,pre_sha_or_NA,post_sha_or_NA,
  result=ALLOWED|DENIED
access_row_count + access_row_digest
undeclared_write_count
publisher_actor_id + publisher_physical_sha
signature_domain =
  WS-WALKSAFE-R007-H2-ACCESS-TRACE-V2
```

`H2AssertionResultV2`:

```text
assertion_id
predicate_kind
ordered_operand_refs[]
expected_value
recomputed_actual_value
recomputation_digest
status = PASS | FAIL
```

`H2InvocationResultV2` body:

```text
schema
invocation_id + invocation_ordinal
intent_sha + intent_physical
stdout_sha + stdout_physical
stderr_sha + stderr_physical
access_trace_raw_sha + access_trace_raw_physical
access_trace_json_sha + access_trace_json_physical
ordered_command_results[]:
  command_ordinal,exit_code,started_at,ended_at
ordered_assertion_results[]
assertion_count + assertion_digest
result_status = PASS | FAIL
publisher_actor_id + publisher_physical_sha
signature_domain =
  WS-WALKSAFE-R007-H2-INVOCATION-RESULT-V2
```

These four JSON types—actual exact-six input, intent, access JSON and
result—use one common one-file `H2SignedEnvelopeV2`:

```text
{
  "body": one exact body named above,
  "signature": {
    "algorithm": Identifier,
    "signer_actor_id": Identifier,
    "signer_physical_sha": Sha256Hex,
    "signature_domain": exact domain equal to body.signature_domain,
    "signature": SignatureBytes
  }
}
```

Signing input is the literal domain, NUL and `RFC8785_JCS(body)`. Neither body
nor signature object contains the envelope's own SHA or output
`FilePhysicalV2`; the batch settlement supplies both. This envelope remains
one file/role, preserving the exact 50 count. Raw files are authenticated by
their content SHA inside the result body and the signed guard receipt for the
same atomic batch. Result status is recomputed from every assertion; a status
string alone has no effect.

### 8.3 exact 25 ordered guarded batches

Guard path prefix is
`H2_ROOT_TEMPLATE/stage-c/guards/`. The table lists every target member.

| ordinal / batch_id | guarded target files | guard filename |
|---|---|---|
| 01 `H2B01-BINDING-PAYLOAD` | `stage-c/h2-literal-root-binding.payload.json` | `01-binding-payload.receipt.json` |
| 02 `H2B02-BINDING-SIGNATURE` | `stage-c/h2-literal-root-binding.signature.json` | `02-binding-signature.receipt.json` |
| 03 `H2B03-CHECKPOINT` | `stage-c/checkpoint-durability-receipt.json` | `03-checkpoint.receipt.json` |
| 04 `H2B04-MANIFEST-PAYLOAD` | `stage-c/live-root-manifest.payload.json` | `04-manifest-payload.receipt.json` |
| 05 `H2B05-MANIFEST-SIGNATURE` | `stage-c/live-root-manifest.signature.json` | `05-manifest-signature.receipt.json` |
| 06 `H2B06-PHYSICAL-PUBLICATION` | `stage-c/live-root-physical-publication-receipt.json` | `06-physical-publication.receipt.json` |
| 07 `H2B07-LIVE-ROOT-PUBLISHED` | `stage-c/live-root-published.progress.json` | `07-live-root-published.receipt.json` |
| 08 `H2B08-EXACT6-INPUT` | `stage-c/actual-exact6-input.json` | `08-exact6-input.receipt.json` |
| 09 `H2B09-INV001-INTENT` | `H2-INV001-INTENT` | `09-inv001-intent.receipt.json` |
| 10 `H2B10-INV001-RESULT` | `H2-INV001-STDOUT`; `H2-INV001-STDERR`; `H2-INV001-ACCESS-RAW`; `H2-INV001-ACCESS-JSON`; `H2-INV001-RESULT` | `10-inv001-result.receipt.json` |
| 11 `H2B11-INV002-INTENT` | `H2-INV002-INTENT` | `11-inv002-intent.receipt.json` |
| 12 `H2B12-INV002-RESULT` | `H2-INV002-STDOUT`; `H2-INV002-STDERR`; `H2-INV002-ACCESS-RAW`; `H2-INV002-ACCESS-JSON`; `H2-INV002-RESULT` | `12-inv002-result.receipt.json` |
| 13 `H2B13-INV003-INTENT` | `H2-INV003-INTENT` | `13-inv003-intent.receipt.json` |
| 14 `H2B14-INV003-RESULT` | `H2-INV003-STDOUT`; `H2-INV003-STDERR`; `H2-INV003-ACCESS-RAW`; `H2-INV003-ACCESS-JSON`; `H2-INV003-RESULT` | `14-inv003-result.receipt.json` |
| 15 `H2B15-INV004-INTENT` | `H2-INV004-INTENT` | `15-inv004-intent.receipt.json` |
| 16 `H2B16-INV004-RESULT` | `H2-INV004-STDOUT`; `H2-INV004-STDERR`; `H2-INV004-ACCESS-RAW`; `H2-INV004-ACCESS-JSON`; `H2-INV004-RESULT` | `16-inv004-result.receipt.json` |
| 17 `H2B17-INV005-INTENT` | `H2-INV005-INTENT` | `17-inv005-intent.receipt.json` |
| 18 `H2B18-INV005-RESULT` | `H2-INV005-STDOUT`; `H2-INV005-STDERR`; `H2-INV005-ACCESS-RAW`; `H2-INV005-ACCESS-JSON`; `H2-INV005-RESULT` | `18-inv005-result.receipt.json` |
| 19 `H2B19-INV006-INTENT` | `H2-INV006-INTENT` | `19-inv006-intent.receipt.json` |
| 20 `H2B20-INV006-RESULT` | `H2-INV006-STDOUT`; `H2-INV006-STDERR`; `H2-INV006-ACCESS-RAW`; `H2-INV006-ACCESS-JSON`; `H2-INV006-RESULT` | `20-inv006-result.receipt.json` |
| 21 `H2B21-INTEGRATION` | `stage-c/live-root-integration-receipt.json` | `21-integration.receipt.json` |
| 22 `H2B22-POSTCHECK` | `stage-c/postcheck-passed.progress.json` | `22-postcheck.receipt.json` |
| 23 `H2B23-APPLICATION-PAIR` | `STAGE-C-APPLICATION-PAYLOAD`; `STAGE-C-APPLICATION-SIGNATURE` | `23-application-pair.receipt.json` |
| 24 `H2B24-FINALIZED` | `stage-c/finalized.progress.json` | `24-finalized.receipt.json` |
| 25 `H2B25-CLOSED` | `stage-c/closed-success-receipt.json` | `25-closed.receipt.json` |

The guard file is not an anonymous extra output. `H2GuardIdentityRowV3` has
exact fields `guard_ordinal,batch_id,role_instance_id,node_id,output_entry_id,
literal_path,schema_role,schema_sha,publisher_actor_id,
publisher_physical_sha,physical_kind=FILE,exact_cardinality=1`. Its exact 25
identity rows are:

| ord | batch ID | role instance ID | node ID | output entry ID | literal path | schema role | publisher actor ID |
|---:|---|---|---|---|---|---|---|
| 01 | `H2B01-BINDING-PAYLOAD` | `H2-GUARD-01-BINDING-PAYLOAD` | `H2-GUARD-01-BINDING-PAYLOAD-NODE` | `H2-OUTPUT-GUARD-01` | `H2_ROOT_TEMPLATE/stage-c/guards/01-binding-payload.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 02 | `H2B02-BINDING-SIGNATURE` | `H2-GUARD-02-BINDING-SIGNATURE` | `H2-GUARD-02-BINDING-SIGNATURE-NODE` | `H2-OUTPUT-GUARD-02` | `H2_ROOT_TEMPLATE/stage-c/guards/02-binding-signature.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 03 | `H2B03-CHECKPOINT` | `H2-GUARD-03-CHECKPOINT` | `H2-GUARD-03-CHECKPOINT-NODE` | `H2-OUTPUT-GUARD-03` | `H2_ROOT_TEMPLATE/stage-c/guards/03-checkpoint.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 04 | `H2B04-MANIFEST-PAYLOAD` | `H2-GUARD-04-MANIFEST-PAYLOAD` | `H2-GUARD-04-MANIFEST-PAYLOAD-NODE` | `H2-OUTPUT-GUARD-04` | `H2_ROOT_TEMPLATE/stage-c/guards/04-manifest-payload.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 05 | `H2B05-MANIFEST-SIGNATURE` | `H2-GUARD-05-MANIFEST-SIGNATURE` | `H2-GUARD-05-MANIFEST-SIGNATURE-NODE` | `H2-OUTPUT-GUARD-05` | `H2_ROOT_TEMPLATE/stage-c/guards/05-manifest-signature.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 06 | `H2B06-PHYSICAL-PUBLICATION` | `H2-GUARD-06-PHYSICAL-PUBLICATION` | `H2-GUARD-06-PHYSICAL-PUBLICATION-NODE` | `H2-OUTPUT-GUARD-06` | `H2_ROOT_TEMPLATE/stage-c/guards/06-physical-publication.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 07 | `H2B07-LIVE-ROOT-PUBLISHED` | `H2-GUARD-07-LIVE-ROOT-PUBLISHED` | `H2-GUARD-07-LIVE-ROOT-PUBLISHED-NODE` | `H2-OUTPUT-GUARD-07` | `H2_ROOT_TEMPLATE/stage-c/guards/07-live-root-published.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 08 | `H2B08-EXACT6-INPUT` | `H2-GUARD-08-EXACT6-INPUT` | `H2-GUARD-08-EXACT6-INPUT-NODE` | `H2-OUTPUT-GUARD-08` | `H2_ROOT_TEMPLATE/stage-c/guards/08-exact6-input.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 09 | `H2B09-INV001-INTENT` | `H2-GUARD-09-INV001-INTENT` | `H2-GUARD-09-INV001-INTENT-NODE` | `H2-OUTPUT-GUARD-09` | `H2_ROOT_TEMPLATE/stage-c/guards/09-inv001-intent.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 10 | `H2B10-INV001-RESULT` | `H2-GUARD-10-INV001-RESULT` | `H2-GUARD-10-INV001-RESULT-NODE` | `H2-OUTPUT-GUARD-10` | `H2_ROOT_TEMPLATE/stage-c/guards/10-inv001-result.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 11 | `H2B11-INV002-INTENT` | `H2-GUARD-11-INV002-INTENT` | `H2-GUARD-11-INV002-INTENT-NODE` | `H2-OUTPUT-GUARD-11` | `H2_ROOT_TEMPLATE/stage-c/guards/11-inv002-intent.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 12 | `H2B12-INV002-RESULT` | `H2-GUARD-12-INV002-RESULT` | `H2-GUARD-12-INV002-RESULT-NODE` | `H2-OUTPUT-GUARD-12` | `H2_ROOT_TEMPLATE/stage-c/guards/12-inv002-result.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 13 | `H2B13-INV003-INTENT` | `H2-GUARD-13-INV003-INTENT` | `H2-GUARD-13-INV003-INTENT-NODE` | `H2-OUTPUT-GUARD-13` | `H2_ROOT_TEMPLATE/stage-c/guards/13-inv003-intent.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 14 | `H2B14-INV003-RESULT` | `H2-GUARD-14-INV003-RESULT` | `H2-GUARD-14-INV003-RESULT-NODE` | `H2-OUTPUT-GUARD-14` | `H2_ROOT_TEMPLATE/stage-c/guards/14-inv003-result.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 15 | `H2B15-INV004-INTENT` | `H2-GUARD-15-INV004-INTENT` | `H2-GUARD-15-INV004-INTENT-NODE` | `H2-OUTPUT-GUARD-15` | `H2_ROOT_TEMPLATE/stage-c/guards/15-inv004-intent.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 16 | `H2B16-INV004-RESULT` | `H2-GUARD-16-INV004-RESULT` | `H2-GUARD-16-INV004-RESULT-NODE` | `H2-OUTPUT-GUARD-16` | `H2_ROOT_TEMPLATE/stage-c/guards/16-inv004-result.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 17 | `H2B17-INV005-INTENT` | `H2-GUARD-17-INV005-INTENT` | `H2-GUARD-17-INV005-INTENT-NODE` | `H2-OUTPUT-GUARD-17` | `H2_ROOT_TEMPLATE/stage-c/guards/17-inv005-intent.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 18 | `H2B18-INV005-RESULT` | `H2-GUARD-18-INV005-RESULT` | `H2-GUARD-18-INV005-RESULT-NODE` | `H2-OUTPUT-GUARD-18` | `H2_ROOT_TEMPLATE/stage-c/guards/18-inv005-result.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 19 | `H2B19-INV006-INTENT` | `H2-GUARD-19-INV006-INTENT` | `H2-GUARD-19-INV006-INTENT-NODE` | `H2-OUTPUT-GUARD-19` | `H2_ROOT_TEMPLATE/stage-c/guards/19-inv006-intent.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 20 | `H2B20-INV006-RESULT` | `H2-GUARD-20-INV006-RESULT` | `H2-GUARD-20-INV006-RESULT-NODE` | `H2-OUTPUT-GUARD-20` | `H2_ROOT_TEMPLATE/stage-c/guards/20-inv006-result.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 21 | `H2B21-INTEGRATION` | `H2-GUARD-21-INTEGRATION` | `H2-GUARD-21-INTEGRATION-NODE` | `H2-OUTPUT-GUARD-21` | `H2_ROOT_TEMPLATE/stage-c/guards/21-integration.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 22 | `H2B22-POSTCHECK` | `H2-GUARD-22-POSTCHECK` | `H2-GUARD-22-POSTCHECK-NODE` | `H2-OUTPUT-GUARD-22` | `H2_ROOT_TEMPLATE/stage-c/guards/22-postcheck.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 23 | `H2B23-APPLICATION-PAIR` | `H2-GUARD-23-APPLICATION-PAIR` | `H2-GUARD-23-APPLICATION-PAIR-NODE` | `H2-OUTPUT-GUARD-23` | `H2_ROOT_TEMPLATE/stage-c/guards/23-application-pair.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 24 | `H2B24-FINALIZED` | `H2-GUARD-24-FINALIZED` | `H2-GUARD-24-FINALIZED-NODE` | `H2-OUTPUT-GUARD-24` | `H2_ROOT_TEMPLATE/stage-c/guards/24-finalized.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |
| 25 | `H2B25-CLOSED` | `H2-GUARD-25-CLOSED` | `H2-GUARD-25-CLOSED-NODE` | `H2-OUTPUT-GUARD-25` | `H2_ROOT_TEMPLATE/stage-c/guards/25-closed.receipt.json` | `H2_BATCH_GUARD_RECEIPT_V2` | `H2_BATCH_GUARD_CAS_SERVICE` |

Every row's `schema_sha` and `publisher_physical_sha` are non-empty frozen
values in the role-instance registry. `guard_identity_digest` is
`R007DigestV3("WS-WALKSAFE-R007-H2-GUARD-IDENTITY-ROWS-V3",
ordered_full_25_rows)`; ID-only or path-only projections are invalid. These
same full rows, in ordinal order, are the binding payload's
`ordered_guard_identity_rows[25]`.

Invocation result batches contain exact five files:
`stdout.raw`, `stderr.raw`, `access-trace.raw`, `access-trace.json`,
`result.json`. All five are one `OutboxBatchV2`; partial settlement is
forbidden. Intent batches contain exact one file. Application is one batch
with exact two targets.

`H2BatchGuardReceiptV2` is a one-file signed envelope with exact body:

```text
schema
h2_transaction_id + batch_id + batch_ordinal
phase_context
prior_binding:
  {"kind":"GENESIS_NA"}
  XOR
  {
    "kind":"PRIOR_ATTESTED",
    "prior_batch_id":Identifier,
    "prior_batch_ordinal":Ordinal,
    "prior_guard_sha":Sha256Hex,
    "prior_guard_physical":FilePhysicalV2,
    "prior_batch_settlement_attestation_sha":Sha256Hex,
    "prior_batch_settlement_attestation_physical":CasRecordPhysicalV2
  }
current_aggregate_token
current_revocation_head_sha + head_physical
stage_c_consume_sha + consume_physical
role_state = CONSUMED_OPEN
operation_not_after
cas_linearized_at
pre_guard_token + post_guard_token
ordered_target_specs[]:
  target_ordinal,role_id,literal_path,schema_role,schema_sha,publisher_actor_id,
  publisher_physical_sha,content_sha
target_count + target_set_digest
ordered_staged_output_refs[]:
  target_ordinal,role_id,private_stage_path:PrivateStageAbsolutePath,content_sha,
  staged_file_physical: StagedFilePhysicalV2
staged_output_count + staged_output_digest
target_publication_spec: H2BatchPublicationSpecV3
target_idempotency_projection: H2TargetIdempotencyProjectionV3
cas_service_actor_id + cas_service_physical_sha
signature_domain =
  WS-WALKSAFE-R007-H2-BATCH-GUARD-RECEIPT-V2
```

`H2TargetIdempotencyProjectionV3` is guard-excluding and lease-free. Its exact
fields and value are

```text
domain = WS-WALKSAFE-R007-H2-IMMUTABLE-TARGET-IDEMPOTENCY-SET-V3
ordered_target_entries[]: H2ImmutableTargetEntryV3
target_entry_count
target_entry_digest = R007DigestV3(domain, ordered_target_entries)

H2ImmutableTargetEntryV3 =
  target_ordinal,role_instance_id,literal_path,schema_role,schema_sha,
  publisher_actor_id,publisher_physical_sha,physical_kind=FILE,content_sha
```

The rows are exact projections of `ordered_target_specs` in target ordinal
order. The projection contains no guard identity or bytes, staged path or
Physical, token, timestamp, outbox state/ID, settlement, worker or lease
field. Duplicate target ordinals, roles or literal paths reject. A retry may
reuse the batch only when every immutable target entry and this digest are
byte-identical. The guard role is categorically forbidden from
`ordered_target_entries`.

`H2BatchPublicationSpecV3` is likewise a target-only predecessor object with
exact fields `domain=WS-WALKSAFE-R007-H2-TARGET-PUBLICATION-SPEC-V3,
h2_transaction_id,batch_id,batch_ordinal,ordered_immutable_target_entries,
target_count,target_set_digest,publication_not_after`. Its digest is over
those exact fields, and neither the guard identity/content nor an outbox ID,
core digest, lease or settlement field is allowed in it.

The full-batch idempotency identity is deliberately unavailable at guard
construction. Only the post-settlement `H2BatchSettlementAttestationV3`
carries
`ordered_settled_target_refs,target_count,target_set_digest,
settled_guard_ref:H2GuardIdentityResolvedRefV3,
full_batch_member_count=target_count+1,full_batch_idempotency_domain=
WS-WALKSAFE-R007-H2-FULL-BATCH-IDEMPOTENCY-SET-V3,
full_batch_idempotency_digest`. That digest hashes the full immutable target
rows followed by the resolved guard row with its content SHA and
`FilePhysicalV2`. It is computed only after target and guard publication are
reopened and settlement transitions the outbox to `ATTESTED`; it never
appears in a guard body or pre-settlement outbox row.

The constructor order is closed: target bytes and the target-only publication
spec are frozen first; guard CAS signs the guard over that predecessor spec;
only after guard bytes have a content SHA does the same CAS construct the full
output set `(ordered targets, guard)`, allocate `outbox_transition_id`, compute
the full outbox core digest and persist the postcommit binding plus `PENDING`
outbox. Consequently no guard field depends on a structure that contains the
guard SHA. Any implementation that computes an outbox/output-set digest
before the guard SHA or inserts such a digest into the guard is cyclic and
rejects.

The CAS service constructs/signs the guard receipt and adds it as a co-output
of that same batch. Guard receipt is explicitly exempt from its own
`ordered_target_specs`; this is the sole guard-of-guard exception. It is still
one of the 25 project outputs counted as guards. Batch `n+1` requires both
target batch `n` and guard co-output `n` to have an exact settlement
attestation in outbox state `ATTESTED`. A store-only `PUBLISHED` row does not
authorize the next guard CAS.

The guard one-file envelope is `{"body":H2BatchGuardReceiptV2,
"service_signature":{algorithm,signer_actor_id,signer_physical_sha,
signature_domain,signature}}`. Signature input is the exact guard domain, NUL
and body JCS. The body excludes its own SHA, output `FilePhysicalV2` and CAS
record Physical. The guard CAS returns the guard body/signature content
identity and its external `CasRecordPhysicalV2`; settlement later returns the
guard output `FilePhysicalV2`.

All 25 batches follow a topological staging order. A multi-member batch does
not construct every target before staging its dependencies:

```text
for each dependency-ready target in target_ordinal order:
  deterministically construct target bytes
  → write its private non-project staging file
  → fsync + nofollow reopen + capture StagedFilePhysicalV2
  → make that captured SHA/Physical available only to later target constructors
→ guard CAS rechecks staged bytes/SHA/Physical and current authority
→ CAS chooses linearized_at and creates guard bytes/signature
→ CAS commits target identities, guard co-output identity and PENDING outbox
→ worker claim CAS creates the mutable one-use lease after guard commit
→ publish/adopt targets and guard with PUBLISH_ONCE
→ nofollow reopen final project outputs
→ settlement CAS
```

`private_stage_path` is a `PrivateStageAbsolutePath` under the transaction's
private staging directory, never a project role/path and never counted among
75 outputs. A staged file confers no authority. Changing it after the guard
CAS, or a staged/final content mismatch, fails without overwrite. The guard
itself is constructed inside the CAS and therefore has no staged-self
reference.

For each six invocation result batch, the exact local dependency order is
`stdout.raw → stderr.raw → access-trace.raw → access-trace.json →
result.json`: the first three are staged/captured before constructing access
JSON, and those four are staged/captured before constructing result JSON.
The application pair similarly stages/captures payload before constructing
its signature. Every other multi-member batch follows its declared direct-edge
order. A constructor may reference only an earlier target's
`StagedFilePhysicalV2`; no target references a sibling's future project
`FilePhysicalV2`.

CAS predicate requires:

```text
trusted cas_linearized_at < operation_not_after
aggregate token exact
current unrevoked head exact
Stage-C consume SHA/Physical exact
role scope/allowlist/plane equality exact
prior batch settlement exact
```

Batch 01 alone uses `GENESIS_NA`. Batches 02–25 use the corresponding literal
prior-publication edge:

| edge ID | exact source node | exact target node |
|---|---|---|
| `H2P001` | `H2B01-BINDING-PAYLOAD-ATTESTED` | `H2B02-BINDING-SIGNATURE-GUARD-CAS` |
| `H2P002` | `H2B02-BINDING-SIGNATURE-ATTESTED` | `H2B03-CHECKPOINT-GUARD-CAS` |
| `H2P003` | `H2B03-CHECKPOINT-ATTESTED` | `H2B04-MANIFEST-PAYLOAD-GUARD-CAS` |
| `H2P004` | `H2B04-MANIFEST-PAYLOAD-ATTESTED` | `H2B05-MANIFEST-SIGNATURE-GUARD-CAS` |
| `H2P005` | `H2B05-MANIFEST-SIGNATURE-ATTESTED` | `H2B06-PHYSICAL-PUBLICATION-GUARD-CAS` |
| `H2P006` | `H2B06-PHYSICAL-PUBLICATION-ATTESTED` | `H2B07-LIVE-ROOT-PUBLISHED-GUARD-CAS` |
| `H2P007` | `H2B07-LIVE-ROOT-PUBLISHED-ATTESTED` | `H2B08-EXACT6-INPUT-GUARD-CAS` |
| `H2P008` | `H2B08-EXACT6-INPUT-ATTESTED` | `H2B09-INV001-INTENT-GUARD-CAS` |
| `H2P009` | `H2B09-INV001-INTENT-ATTESTED` | `H2B10-INV001-RESULT-GUARD-CAS` |
| `H2P010` | `H2B10-INV001-RESULT-ATTESTED` | `H2B11-INV002-INTENT-GUARD-CAS` |
| `H2P011` | `H2B11-INV002-INTENT-ATTESTED` | `H2B12-INV002-RESULT-GUARD-CAS` |
| `H2P012` | `H2B12-INV002-RESULT-ATTESTED` | `H2B13-INV003-INTENT-GUARD-CAS` |
| `H2P013` | `H2B13-INV003-INTENT-ATTESTED` | `H2B14-INV003-RESULT-GUARD-CAS` |
| `H2P014` | `H2B14-INV003-RESULT-ATTESTED` | `H2B15-INV004-INTENT-GUARD-CAS` |
| `H2P015` | `H2B15-INV004-INTENT-ATTESTED` | `H2B16-INV004-RESULT-GUARD-CAS` |
| `H2P016` | `H2B16-INV004-RESULT-ATTESTED` | `H2B17-INV005-INTENT-GUARD-CAS` |
| `H2P017` | `H2B17-INV005-INTENT-ATTESTED` | `H2B18-INV005-RESULT-GUARD-CAS` |
| `H2P018` | `H2B18-INV005-RESULT-ATTESTED` | `H2B19-INV006-INTENT-GUARD-CAS` |
| `H2P019` | `H2B19-INV006-INTENT-ATTESTED` | `H2B20-INV006-RESULT-GUARD-CAS` |
| `H2P020` | `H2B20-INV006-RESULT-ATTESTED` | `H2B21-INTEGRATION-GUARD-CAS` |
| `H2P021` | `H2B21-INTEGRATION-ATTESTED` | `H2B22-POSTCHECK-GUARD-CAS` |
| `H2P022` | `H2B22-POSTCHECK-ATTESTED` | `H2B23-APPLICATION-PAIR-GUARD-CAS` |
| `H2P023` | `H2B23-APPLICATION-PAIR-ATTESTED` | `H2B24-FINALIZED-GUARD-CAS` |
| `H2P024` | `H2B24-FINALIZED-ATTESTED` | `H2B25-CLOSED-GUARD-CAS` |

Each `*-ATTESTED` node is the signed settlement attestation covering both the
listed target set and that batch's guard co-output. It is not a count-only
marker and is distinct from the outbox's store-only `PUBLISHED` state.

Client timestamps and `<=` are invalid. CAS stores target content identities,
guard content identity, obligation and the lease-free `PENDING` outbox
atomically. A later `PENDING→CLAIMED` CAS alone creates
`publication_lease_id,lease_token,lease_expires_at`. Publication
uses create-exclusive→fsync→parent-fsync→nofollow-reopen→exact adoption from
§4.8. Crash before or during publication resumes the same batch; wrong bytes
or Physical never overwrite.

```text
H2 file roles = 50
H2 guarded transition batches = 25
H2 guard receipt project files = 25
H2 project outputs = 50 + 25 = 75
raw/evidence files covered = 24/24
guard recursion = 0
```

H2 final node/edge total is generated only from the 50 role rows, 25 batch
rows, prior-settlement edges, M02 triple constructor edges and alias
resolution in §13. No `262`, `306` or other seed is accepted.

## 9. M02 late-bound registry

이 절은 `19 roles / (23+1) local edges`와 H2 triple constructor edge를
서로 다른 namespace에서 닫는다.

### 9.1 exact 19 role rows

`M02LateBoundRoleV2`는 다음 closed fields를 가진다.

```text
schema
role_ordinal
binding_role_id = "STAGE_C_INVOCATION::" || logical_role_id
logical_role_id
constructor_enum
constructor_actor_id
constructor_physical_path
constructor_physical_sha
literal_argv[]
allowed_phase = STAGE_C_INTENT_ONLY
constructor_source: M02ConstructorSourceV3
output_tagged_type
output_schema_role
output_schema_sha
expected_member_cardinality: M02CardinalityV3
carrier_role = ACTUAL-EXACT6-INPUT
carrier_literal_path =
  H2_ROOT_TEMPLATE/stage-c/actual-exact6-input.json
carrier_json_pointer
wrong_phase_predicate
```

Cardinality is one closed tagged union; raw numbers, prose and field names are
not schema values:

```text
M02CardinalityV3 =
  {"kind":"EXACT","count":PositiveInteger}
  XOR {"kind":"MANIFEST_FIELD","manifest_role_id":"TRANSACTION_MANIFEST",
       "json_pointer":JsonPointer}
  XOR {"kind":"COMPOSITE_MEMBERS","composite_count":1,
       "ordered_member_role_ids":[Identifier,...],
       "member_count":PositiveInteger}
```

The heterogeneous source field is forbidden. The exact tagged union is:

```text
M02ConstructorSourceV3 =
  {"kind":"NOFOLLOW_FILE","literal_path":SourceAbsolutePath}
  XOR {"kind":"SEALED_PREFIX_MANIFEST","root":SourceAbsolutePath,
       "selector_enum":V24_CONTROL|V241_CONTROL|MANAGED_GOALS|
                       ROW17_V241_CONTROL_SET|ROW17_MANAGED_GOALS_SET}
  XOR {"kind":"SEALED_LITERAL_SET","root":SourceAbsolutePath,
       "selector_enum":ROUTING_LITERAL132_SET|ROW17_DIRECT3}
  XOR {"kind":"H2_ROOT_TRIPLE_BINDING",
       "constructor_node_id":"H2-LIVE-ROOT-TRIPLE-CONSTRUCTOR",
       "binding_signature_role_id":"H2-BINDING-SIGNATURE"}
  XOR {"kind":"TRANSACTION_INPUT_FILE",
       "literal_path":"/input/transaction-manifest.json"}
  XOR {"kind":"TRANSACTION_MANIFEST_FIELD",
       "manifest_role_id":"TRANSACTION_MANIFEST","json_pointer":JsonPointer}
  XOR {"kind":"STAGE_B_RUNTIME_BINDING",
       "literal_path":"/work/walksafe/projections/runtime-actual/full19-gate-event-id-binding.json"}
```

The constructor identity shared by all rows is exact:

```text
constructor_actor_id = M02_LATE_BOUND_BINDING_SERVICE
constructor_physical_path =
  /work/walksafe/bin/walksafe-m02-late-bound-binding-v3
constructor_physical_sha =
  M02_CONSTRUCTOR_IDENTITY.file_physical.sha256
M02_CONSTRUCTOR_IDENTITY =
  {literal_path,content_sha,file_physical:FilePhysicalV2,
   actor_id="M02_LATE_BOUND_BINDING_SERVICE"}
```

At preparation freeze the executable is nofollow-opened and the signed actor
registry records this full identity; `content_sha`, `file_physical.sha256` and
`constructor_physical_sha` must be the same non-empty lowercase SHA. A path or
actor name without that frozen SHA/Physical rejects.

| ord | logical role ID | constructor | exact source path or closed selector | tagged output/schema role | cardinality |
|---:|---|---|---|---|---:|
| 1 | `CONTINUATION_CHECKER` | `NOFOLLOW_FILE` | `/work/walksafe/scripts/check_walksafe_project_continuation_v2_4_1.py` | `FilePhysical` / `STAGE_C_LB_CONTINUATION_CHECKER_V1` | 1 |
| 2 | `CHECKPOINT` | `NOFOLLOW_FILE` | `/work/walksafe/docs/control/walksafe-project-continuation-checkpoint.json` | `FilePhysical` / `STAGE_C_LB_CHECKPOINT_V1` | 1 |
| 3 | `AFTER_ROUTING` | `NOFOLLOW_FILE` | `/work/walksafe/docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-after-seq40-v2.4.1.json` | `FilePhysical` / `STAGE_C_LB_AFTER_ROUTING_V1` | 1 |
| 4 | `GOAL_GRAPH_CHECKER` | `NOFOLLOW_FILE` | `/work/walksafe/scripts/check_walksafe_goal_graph_v2_4_1.py` | `FilePhysical` / `STAGE_C_LB_GOAL_GRAPH_CHECKER_V1` | 1 |
| 5 | `TEST_LAYER_RUNNER` | `NOFOLLOW_FILE` | `/work/walksafe/scripts/run_walksafe_test_layers_20260711.sh` | `FilePhysical` / `STAGE_C_LB_TEST_LAYER_RUNNER_V1` | 1 |
| 6 | `V24_CONTROL` | `SEALED_PREFIX_MANIFEST` | root `/work/walksafe/docs/control/goals/walksafe-completion-graph-v2-4`; selector enum `V24_CONTROL` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_V24_CONTROL_V1` | manifest field `v24_control_count` |
| 7 | `V241_CONTROL` | `SEALED_PREFIX_MANIFEST` | root `/work/walksafe/docs/control/goals/walksafe-completion-graph-v2-4-1`; selector enum `V241_CONTROL` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_V241_CONTROL_V1` | manifest field `v241_control_count` |
| 8 | `MANAGED_GOALS` | `SEALED_PREFIX_MANIFEST` | root `/work/walksafe/docs/control/goals`; selector enum `MANAGED_GOALS` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_MANAGED_GOALS_V1` | manifest field `managed_goals_count` |
| 9 | `ROUTING_LITERAL132_SET` | `SEALED_LITERAL_SET` | root `/work/walksafe`; selector enum `ROUTING_LITERAL132_SET` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_ROUTING_LITERAL132_SET_V1` | 132 |
| 10 | `ROW17_DIRECT3` | `SEALED_LITERAL_SET` | ordered exact three successor test paths frozen in the R007 registry | `OrderedFilePhysicalManifest` / `STAGE_C_LB_ROW17_DIRECT3_V1` | 3 |
| 11 | `ROW17_V241_CONTROL_SET` | `SEALED_PREFIX_MANIFEST` | root `/work/walksafe/docs/control/goals/walksafe-completion-graph-v2-4-1`; selector enum `ROW17_V241_CONTROL_SET` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_ROW17_V241_CONTROL_SET_V1` | manifest field `row17_v241_control_count` |
| 12 | `ROW17_MANAGED_GOALS_SET` | `SEALED_PREFIX_MANIFEST` | root `/work/walksafe/docs/control/goals`; selector enum `ROW17_MANAGED_GOALS_SET` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_ROW17_MANAGED_GOALS_SET_V1` | manifest field `row17_managed_goals_count` |
| 13 | `LIVE_ROOT_TRIPLE` | `H2_ROOT_TRIPLE_BINDING` | constructor node `H2-LIVE-ROOT-TRIPLE-CONSTRUCTOR` and §9.3 exact member edges | `LiveRootPhysicalTriple` / `STAGE_C_LB_LIVE_ROOT_TRIPLE_V1` | 1 composite with 3 members |
| 14 | `TRANSACTION_MANIFEST` | `TRANSACTION_INPUT_FILE` | `/input/transaction-manifest.json` | `FilePhysical` / `STAGE_C_LB_TRANSACTION_MANIFEST_V1` | 1 |
| 15 | `LIVE_EXACT26` | `TRANSACTION_MANIFEST_FIELD` | field `ordered_applied_targets` | `OrderedTargetBinding` / `STAGE_C_LB_LIVE_EXACT26_V1` | 26 |
| 16 | `AUX_PARENT_001` | `TRANSACTION_MANIFEST_FIELD` | field `aux_parent_001` | `DirPhysical` / `STAGE_C_LB_AUX_PARENT_001_V1` | 1 |
| 17 | `M_AFTER627_WORKTREE_INVENTORY` | `TRANSACTION_MANIFEST_FIELD` | field `m_after627_worktree_inventory` | `OrderedFilePhysicalManifest` / `STAGE_C_LB_M_AFTER627_V1` | 627 |
| 18 | `DOUBLE_READ_GIT_INVENTORY_SET` | `TRANSACTION_MANIFEST_FIELD` | field `double_read_git_inventory_set` | `OrderedGitPhysicalManifest` / `STAGE_C_LB_DOUBLE_READ_GIT_V1` | manifest field `double_read_git_inventory_count` |
| 19 | `RUNTIME_ACTUAL_BINDING` | `STAGE_B_RUNTIME_BINDING` | `/work/walksafe/projections/runtime-actual/full19-gate-event-id-binding.json` | `RuntimeActualBindingPhysical` / `WS_PRE_P_R007_RUNTIME_ACTUAL_V1` | 1 |

The last prose column above is only a reader summary. The serialized
`expected_member_cardinality` in the exact 19 rows is:

| ord | literal `M02CardinalityV3` value |
|---:|---|
| 1 | `{"kind":"EXACT","count":1}` |
| 2 | `{"kind":"EXACT","count":1}` |
| 3 | `{"kind":"EXACT","count":1}` |
| 4 | `{"kind":"EXACT","count":1}` |
| 5 | `{"kind":"EXACT","count":1}` |
| 6 | `{"kind":"MANIFEST_FIELD","manifest_role_id":"TRANSACTION_MANIFEST","json_pointer":"/v24_control_count"}` |
| 7 | `{"kind":"MANIFEST_FIELD","manifest_role_id":"TRANSACTION_MANIFEST","json_pointer":"/v241_control_count"}` |
| 8 | `{"kind":"MANIFEST_FIELD","manifest_role_id":"TRANSACTION_MANIFEST","json_pointer":"/managed_goals_count"}` |
| 9 | `{"kind":"EXACT","count":132}` |
| 10 | `{"kind":"EXACT","count":3}` |
| 11 | `{"kind":"MANIFEST_FIELD","manifest_role_id":"TRANSACTION_MANIFEST","json_pointer":"/row17_v241_control_count"}` |
| 12 | `{"kind":"MANIFEST_FIELD","manifest_role_id":"TRANSACTION_MANIFEST","json_pointer":"/row17_managed_goals_count"}` |
| 13 | `{"kind":"COMPOSITE_MEMBERS","composite_count":1,"ordered_member_role_ids":["LIVE-ROOT-MANIFEST-PAYLOAD","LIVE-ROOT-PHYSICAL-PUBLICATION-RECEIPT","LIVE-ROOT-PUBLISHED-PROGRESS"],"member_count":3}` |
| 14 | `{"kind":"EXACT","count":1}` |
| 15 | `{"kind":"EXACT","count":26}` |
| 16 | `{"kind":"EXACT","count":1}` |
| 17 | `{"kind":"EXACT","count":627}` |
| 18 | `{"kind":"MANIFEST_FIELD","manifest_role_id":"TRANSACTION_MANIFEST","json_pointer":"/double_read_git_inventory_count"}` |
| 19 | `{"kind":"EXACT","count":1}` |

The table's source column serializes to `M02ConstructorSourceV3` exactly by
its constructor tag. For ordinals 15–18 the manifest-field JSON pointers are
respectively `/ordered_applied_targets`, `/aux_parent_001`,
`/m_after627_worktree_inventory` and `/double_read_git_inventory_set`; no
field-name shorthand is serialized. All other paths, roots, selectors and
constructor-node IDs are copied literally from the displayed row into their
matching union alternative.

The remaining per-row values are exact, not defaults inferred at execution:

| ord | binding role ID | carrier JSON pointer |
|---:|---|---|
| 1 | `STAGE_C_INVOCATION::CONTINUATION_CHECKER` | `/body/ordered_m02_bindings/0` |
| 2 | `STAGE_C_INVOCATION::CHECKPOINT` | `/body/ordered_m02_bindings/1` |
| 3 | `STAGE_C_INVOCATION::AFTER_ROUTING` | `/body/ordered_m02_bindings/2` |
| 4 | `STAGE_C_INVOCATION::GOAL_GRAPH_CHECKER` | `/body/ordered_m02_bindings/3` |
| 5 | `STAGE_C_INVOCATION::TEST_LAYER_RUNNER` | `/body/ordered_m02_bindings/4` |
| 6 | `STAGE_C_INVOCATION::V24_CONTROL` | `/body/ordered_m02_bindings/5` |
| 7 | `STAGE_C_INVOCATION::V241_CONTROL` | `/body/ordered_m02_bindings/6` |
| 8 | `STAGE_C_INVOCATION::MANAGED_GOALS` | `/body/ordered_m02_bindings/7` |
| 9 | `STAGE_C_INVOCATION::ROUTING_LITERAL132_SET` | `/body/ordered_m02_bindings/8` |
| 10 | `STAGE_C_INVOCATION::ROW17_DIRECT3` | `/body/ordered_m02_bindings/9` |
| 11 | `STAGE_C_INVOCATION::ROW17_V241_CONTROL_SET` | `/body/ordered_m02_bindings/10` |
| 12 | `STAGE_C_INVOCATION::ROW17_MANAGED_GOALS_SET` | `/body/ordered_m02_bindings/11` |
| 13 | `STAGE_C_INVOCATION::LIVE_ROOT_TRIPLE` | `/body/ordered_m02_bindings/12` |
| 14 | `STAGE_C_INVOCATION::TRANSACTION_MANIFEST` | `/body/ordered_m02_bindings/13` |
| 15 | `STAGE_C_INVOCATION::LIVE_EXACT26` | `/body/ordered_m02_bindings/14` |
| 16 | `STAGE_C_INVOCATION::AUX_PARENT_001` | `/body/ordered_m02_bindings/15` |
| 17 | `STAGE_C_INVOCATION::M_AFTER627_WORKTREE_INVENTORY` | `/body/ordered_m02_bindings/16` |
| 18 | `STAGE_C_INVOCATION::DOUBLE_READ_GIT_INVENTORY_SET` | `/body/ordered_m02_bindings/17` |
| 19 | `STAGE_C_INVOCATION::RUNTIME_ACTUAL_BINDING` | `/body/ordered_m02_bindings/18` |

For each displayed row, `output_schema_sha` is the non-empty content SHA of
that row's exact `output_schema_role` in the frozen schema registry, and
`carrier_json_pointer` is the literal pointer above. `literal_argv` is the
following exact argument vector after substituting only the displayed binding
role, pointer and output schema role:

```text
[/work/walksafe/bin/walksafe-m02-late-bound-binding-v3,
 resolve,
 --role-id, <literal binding role ID>,
 --carrier, <H2_ROOT_TEMPLATE/stage-c/actual-exact6-input.json>,
 --carrier-pointer, <literal carrier JSON pointer>,
 --phase, STAGE_C_INTENT_ONLY,
 --output-schema, <literal output schema role>,
 --emit-jcs]
```

Angle-bracket terms above are typed substitutions from the same frozen row,
not runtime path placeholders; the materialized argv contains none. Every row
has the exact predicate
`wrong_phase_predicate=CURRENT_PHASE_NOT_EQUALS_STAGE_C_INTENT_ONLY`. If that
predicate is true, or if the carrier already has a guard CAS identity, the
constructor must emit no bytes and the verifier rejects. Thus all 19 rows
carry actor/path/SHA/argv, output schema SHA, carrier pointer and wrong-phase
predicate inside the registry digest.

Prefix selectors are not globs. Their ordered literal-member manifests are
resolved and signed before Stage C intent. `ROW17_DIRECT3` must likewise be
expanded to three literal paths in a runtime `RoleInstanceRegistryV2`; the
phrase “successor test paths” is not permitted in that runtime registry.

The registry digest is:

```text
SHA256(
  ASCII("WS-WALKSAFE-R007-M02-LATE-BOUND-ROLE-REGISTRY-V2") ||
  0x00 || RFC8785_JCS(ordered exact 19 M02LateBoundRoleV2 rows))
```

The verifier regenerates all 19 rows from this table. Missing, extra,
duplicate, constructor alias, wrong-phase use, wrong type, wrong member count
or supplied-row trust is rejection.

`AUX_PARENT_001` has this exact `DirPhysicalV2` value; it may not be represented
as `FilePhysicalV2`.

```text
physical_type = DIRECTORY
literal_path = value bound by transaction manifest field aux_parent_001
parent_literal_path = its literal nofollow-opened parent
parent_anchor_sha = exact parent anchor SHA
dev = exact stat integer
ino = exact stat integer
mode = "0775"
uid = 1000
gid = 1000
nlink = 2
file_type = DIRECTORY
nofollow_open = true
```

### 9.2 exact local edge registry

The 23 M02X rows are these exact rows:

| edge ID | exact source | exact target |
|---|---|---|
| `M02X001` | `M02-REGISTRY-PAYLOAD` | `M02-REGISTRY-SIGNATURE` |
| `M02X002` | `M02-REGISTRY-SIGNATURE` | `M02-VERIFICATION-PAYLOAD` |
| `M02X003` | `M02-VERIFICATION-PAYLOAD` | `M02-VERIFICATION-SIGNATURE` |
| `M02X004` | `M02-VERIFICATION-SIGNATURE` | `T01-RESULT-PAYLOAD` |
| `M02X005` | `CONTINUATION_CHECKER` | `ACTUAL-EXACT6-INPUT` |
| `M02X006` | `CHECKPOINT` | `ACTUAL-EXACT6-INPUT` |
| `M02X007` | `AFTER_ROUTING` | `ACTUAL-EXACT6-INPUT` |
| `M02X008` | `GOAL_GRAPH_CHECKER` | `ACTUAL-EXACT6-INPUT` |
| `M02X009` | `TEST_LAYER_RUNNER` | `ACTUAL-EXACT6-INPUT` |
| `M02X010` | `V24_CONTROL` | `ACTUAL-EXACT6-INPUT` |
| `M02X011` | `V241_CONTROL` | `ACTUAL-EXACT6-INPUT` |
| `M02X012` | `MANAGED_GOALS` | `ACTUAL-EXACT6-INPUT` |
| `M02X013` | `ROUTING_LITERAL132_SET` | `ACTUAL-EXACT6-INPUT` |
| `M02X014` | `ROW17_DIRECT3` | `ACTUAL-EXACT6-INPUT` |
| `M02X015` | `ROW17_V241_CONTROL_SET` | `ACTUAL-EXACT6-INPUT` |
| `M02X016` | `ROW17_MANAGED_GOALS_SET` | `ACTUAL-EXACT6-INPUT` |
| `M02X017` | `LIVE_ROOT_TRIPLE` | `ACTUAL-EXACT6-INPUT` |
| `M02X018` | `TRANSACTION_MANIFEST` | `ACTUAL-EXACT6-INPUT` |
| `M02X019` | `LIVE_EXACT26` | `ACTUAL-EXACT6-INPUT` |
| `M02X020` | `AUX_PARENT_001` | `ACTUAL-EXACT6-INPUT` |
| `M02X021` | `M_AFTER627_WORKTREE_INVENTORY` | `ACTUAL-EXACT6-INPUT` |
| `M02X022` | `DOUBLE_READ_GIT_INVENTORY_SET` | `ACTUAL-EXACT6-INPUT` |
| `M02X023` | `RUNTIME_ACTUAL_BINDING` | `ACTUAL-EXACT6-INPUT` |

`M02P001` is the one additional local predecessor edge:

```text
edge_namespace = M02P
edge_ordinal = 1
edge_id = M02P001
source = H2-BINDING-SIGNATURE
target = H2-LIVE-ROOT-TRIPLE-CONSTRUCTOR
branch = H2_STAGE_C
meaning = root binding authorizes construction but is not a triple member
```

Therefore M02 local counts are exactly:

```text
role rows = 19
M02X edges = 23
M02P edges = 1
local edges = 24
```

### 9.3 H2-owned triple member edges

The three composite-member inbounds are deliberately in the H2 edge
namespace, so they do not change M02's local `23+1`:

| edge ID | exact source | exact target | member ordinal |
|---|---|---|---:|
| `H2M001` | `LIVE-ROOT-MANIFEST-PAYLOAD` | `H2-LIVE-ROOT-TRIPLE-CONSTRUCTOR` | 1 |
| `H2M002` | `LIVE-ROOT-PHYSICAL-PUBLICATION-RECEIPT` | `H2-LIVE-ROOT-TRIPLE-CONSTRUCTOR` | 2 |
| `H2M003` | `LIVE-ROOT-PUBLISHED-PROGRESS` | `H2-LIVE-ROOT-TRIPLE-CONSTRUCTOR` | 3 |

The constructor emits `LIVE_ROOT_TRIPLE`; `M02X017` is its one outgoing edge
to `ACTUAL-EXACT6-INPUT`. Thus the complete topology is one authorization
predecessor (`M02P001`), three H2 member predecessors
(`H2M001`, `H2M002`, `H2M003`) and one
outgoing carrier edge (`M02X017`). The wrapper inward-references the manifest
payload, but wrapper and payload are never both counted as members. Missing
`LIVE-ROOT-PUBLISHED`, wrong phase, wrong Physical or a member count other than
three rejects both M02 verification and H2 exact-six input.

## 10. H4 scope, run과 exact-five Gate

이 절은 H4 scope/run/Gate의 template registry와 runtime literal instance
registry를 닫는다.

```text
H4_ROOT_TEMPLATE =
ROADMAP_DIR/walksafe-h4-r007-{h4_id}

H4_RUN_ROOT_TEMPLATE =
H4_ROOT_TEMPLATE/runs/{run_id}
```

`h4_id`와 모든 `run_id`는 §4.1 `Identifier`이고 H4 activation 전에 frozen
ordered list로 정한다. Runtime registry에는 token이 하나도 남지 않는다.

### 10.1 scope authority: exact 8 read + 2 control

Scope read plane의 ordered exact eight:

| ord | role ID | literal path template | schema role | publisher |
|---:|---|---|---|---|
| 1 | `H4-SCOPE-CANDIDATE` | `H4_ROOT_TEMPLATE/inputs/candidate.payload.json` | `H4_CANDIDATE_PAYLOAD_V2` | `H4_CANDIDATE_SERIALIZER` |
| 2 | `H4-SCOPE-CANDIDATE-PUBLICATION` | `H4_ROOT_TEMPLATE/inputs/candidate-publication.receipt.json` | `H4_CANDIDATE_PUBLICATION_RECEIPT_V2` | `H4_CANDIDATE_PUBLICATION_SERVICE` |
| 3 | `H4-SCOPE-TEST-PLAN-APPROVAL` | `H4_ROOT_TEMPLATE/inputs/test-plan-approval.receipt.json` | `H4_TEST_PLAN_APPROVAL_RECEIPT_V2` | `H4_TEST_PLAN_APPROVER` |
| 4 | `H4-SCOPE-ELIGIBLE-INVENTORY` | `H4_ROOT_TEMPLATE/inputs/eligible-inventory.payload.json` | `H4_ELIGIBLE_INVENTORY_V2` | `H4_ELIGIBLE_INVENTORY_PUBLISHER` |
| 5 | `H4-SCOPE-ELIGIBLE-INVENTORY-RECEIPT` | `H4_ROOT_TEMPLATE/inputs/eligible-inventory.receipt.json` | `H4_ELIGIBLE_INVENTORY_RECEIPT_V2` | `H4_INVENTORY_REVIEW_SERVICE` |
| 6 | `H4-SCOPE-INVENTORY-REVALIDATION` | `H4_ROOT_TEMPLATE/inputs/immediate-inventory-revalidation.receipt.json` | `H4_INVENTORY_REVALIDATION_RECEIPT_V2` | `H4_INVENTORY_REVALIDATOR` |
| 7 | `H4-SCOPE-PHONE-GATE-RESULT` | `H4_ROOT_TEMPLATE/gates/02-phone-queue-byte-limit/result.json` | `H4_GATE_RESULT_V2` | `H4_GATE_RESULT_REVIEWER` |
| 8 | `H4-SCOPE-PHONE-GATE-RECEIPT` | `H4_ROOT_TEMPLATE/gates/02-phone-queue-byte-limit/receipt.json` | `H4_GATE_RECEIPT_V2` | `H4_GATE_CAS_SERVICE` |

Scope control plane의 ordered exact two:

| ord | role ID | literal path template | schema role | publisher |
|---:|---|---|---|---|
| 1 | `H4-SCOPE-AUTHORITY` | `H4_ROOT_TEMPLATE/scope/scope-authority-receipt.json` | `H4_SCOPE_AUTHORITY_RECEIPT_V2` | `H4_SCOPE_AUTHORITY_ISSUER` |
| 2 | `H4-SCOPE-FREEZE` | `H4_ROOT_TEMPLATE/scope/scope-freeze-receipt.json` | `H4_SCOPE_FREEZE_RECEIPT_V2` | `H4_SCOPE_FREEZE_SERVICE` |

Every materialized read row includes content SHA and `FilePhysicalV2`; every
read/control row includes exact publisher actor/Physical. Artifact envelopes
also bind their algorithm and signature.
To avoid self/future references, the signed scope uses two closed row types:

```text
H4ScopeRoleSpecV2:
  ordinal,role_id,literal_path,schema_role,schema_sha,
  publisher_actor_id,publisher_physical_sha,physical_kind=FILE,
  exact_cardinality=1

H4ScopeResolvedReadRefV2:
  every H4ScopeRoleSpecV2 field,
  content_sha,artifact_physical:FilePhysicalV2
```

All eight read roles are resolved refs. The authority and freeze outputs are
the two `H4ScopeRoleSpecV2` control specs: publisher Physical is known, but
their own/future content SHA and output Physical are forbidden. The read and
control digests hash their complete displayed row types; union hashes the
eight read rows projected to `H4ScopeRoleSpecV2` followed by the two control
specs.
`H4ScopeAuthorityReceiptV2` is a one-file signed envelope with exact body:

```text
schema
h4_id
candidate_sha + candidate_physical
candidate_publication_receipt_sha + receipt_physical
test_plan_approval_receipt_sha + receipt_physical
eligible_inventory_payload_sha + payload_physical
eligible_inventory_receipt_sha + receipt_physical
inventory_revalidation_receipt_sha + receipt_physical
phone_gate_result_sha + result_physical
phone_gate_receipt_sha + receipt_physical
ordered_read_role_ids[8]
ordered_read_refs[8]: H4ScopeResolvedReadRefV2
read_count=8 + read_digest
ordered_control_role_ids[2]
ordered_control_specs[2]: H4ScopeRoleSpecV2
control_count=2 + control_template_digest
data_count=0
read_control_intersection_count=0
read_data_intersection_count=0
control_data_intersection_count=0
union_count=10 + union_digest
authority_nonce
issued_at + operation_not_after + revocation_head_sha
publisher_actor_id + publisher_physical_sha
signature_domain = WS-WALKSAFE-R007-H4-SCOPE-AUTHORITY-V2
signature_algorithm + signature
```

`H4ScopeFreezeReceiptV2` exact body:

```text
schema
h4_id
scope_authority_sha + scope_authority_physical
the same eight ordered read refs
read_count=8 + read_digest
the same two ordered control role IDs
the same two ordered control specs
control_count=2 + control_template_digest
data_count=0
all three pairwise intersection counts=0
union_count=10 + union_digest
immutable_inventory_tuple_set_digest
phone_gate_coverage_tuple_set_digest
ordered_final_supported_scope_tuple_rows[]
final_supported_scope_tuple_count + final_supported_scope_tuple_set_digest
scope_equation =
  IMMUTABLE_ELIGIBLE_INVENTORY_INTERSECT_PHONE_GATE_PASS_COVERAGE
phone_gate_status=PASS
phone_gate_waived=false
scope_frozen_at + trusted_clock_source
publisher_actor_id + publisher_physical_sha
signature_domain = WS-WALKSAFE-R007-H4-SCOPE-FREEZE-V2
signature_algorithm + signature
```

Signature input for both is domain bytes, NUL and JCS of the object with
`signature` omitted. The freeze is valid only when the two operand sets are
recomputed and their literal sorted-set intersection equals every frozen row.
Digest-only references, count-only equality, missing Physical or a
self/future SHA are rejected.

### 10.2 each run: exact 9 read + 11 control + `D_i`

The ordered nine read roles for every resolved `run_id` are:

| ord | role ID | literal path template | schema role | publisher role |
|---:|---|---|---|---|
| 1 | `H4-RUN-CANDIDATE` | `H4_ROOT_TEMPLATE/inputs/candidate.payload.json` | `H4_CANDIDATE_PAYLOAD_V2` | `H4_CANDIDATE_SERIALIZER` |
| 2 | `H4-RUN-CANDIDATE-PUBLICATION` | `H4_ROOT_TEMPLATE/inputs/candidate-publication.receipt.json` | `H4_CANDIDATE_PUBLICATION_RECEIPT_V2` | `H4_CANDIDATE_PUBLICATION_SERVICE` |
| 3 | `H4-RUN-TEST-PLAN-APPROVAL` | `H4_ROOT_TEMPLATE/inputs/test-plan-approval.receipt.json` | `H4_TEST_PLAN_APPROVAL_RECEIPT_V2` | `H4_TEST_PLAN_APPROVER` |
| 4 | `H4-RUN-SCOPE-AUTHORITY` | `H4_ROOT_TEMPLATE/scope/scope-authority-receipt.json` | `H4_SCOPE_AUTHORITY_RECEIPT_V2` | `H4_SCOPE_AUTHORITY_ISSUER` |
| 5 | `H4-RUN-SCOPE-FREEZE` | `H4_ROOT_TEMPLATE/scope/scope-freeze-receipt.json` | `H4_SCOPE_FREEZE_RECEIPT_V2` | `H4_SCOPE_FREEZE_SERVICE` |
| 6 | `H4-RUN-ADMIN-GATE-RAW` | `H4_ROOT_TEMPLATE/gates/01-single-admin-recovery-drill/raw-evidence.bin` | `H4_GATE_RAW_EVIDENCE_V2` | `H4_GATE_EXECUTOR` |
| 7 | `H4-RUN-ADMIN-GATE-RESULT` | `H4_ROOT_TEMPLATE/gates/01-single-admin-recovery-drill/result.json` | `H4_GATE_RESULT_V2` | `H4_GATE_RESULT_REVIEWER` |
| 8 | `H4-RUN-ADMIN-GATE-RECEIPT` | `H4_ROOT_TEMPLATE/gates/01-single-admin-recovery-drill/receipt.json` | `H4_GATE_RECEIPT_V2` | `H4_GATE_CAS_SERVICE` |
| 9 | `H4-RUN-SUBJECT` | `H4_ROOT_TEMPLATE/subjects/{run_id}.payload.json` | `H4_RUN_SUBJECT_PAYLOAD_V2` | `H4_RUN_SUBJECT_SERIALIZER` |

The ordered eleven control roles are:

| ord | role ID | filename under `H4_RUN_ROOT_TEMPLATE` | schema role | publisher role |
|---:|---|---|---|---|
| 1 | `H4-RUN-GRANT-PAYLOAD` | `grant.payload.json` | `H4_RUN_GRANT_PAYLOAD_V2` | `H4_RUN_GRANT_SERIALIZER` |
| 2 | `H4-RUN-GRANT-SIGNATURE` | `grant.signature.json` | `H4_RUN_GRANT_SIGNATURE_V2` | `H4_RUN_GRANT_SIGNER` |
| 3 | `H4-RUN-STATE-INIT` | `state-init-receipt.json` | `H4_RUN_STATE_INIT_RECEIPT_V2` | `H4_RUN_STATE_CAS_SERVICE` |
| 4 | `H4-RUN-CONSUME-INTENT-PAYLOAD` | `consume-intent.payload.json` | `H4_RUN_CONSUME_INTENT_PAYLOAD_V2` | `H4_RUN_CONSUME_SERIALIZER` |
| 5 | `H4-RUN-CONSUME-INTENT-SIGNATURE` | `consume-intent.signature.json` | `H4_RUN_CONSUME_INTENT_SIGNATURE_V2` | `H4_RUN_CONSUME_SIGNER` |
| 6 | `H4-RUN-CONSUME-RECEIPT` | `consume-receipt.json` | `H4_RUN_CONSUME_RECEIPT_V2` | `H4_RUN_STATE_CAS_SERVICE` |
| 7 | `H4-RUN-DISPATCH-RECEIPT` | `dispatch-receipt.json` | `H4_RUN_DISPATCH_RECEIPT_V2` | `H4_RUN_DISPATCH_SERVICE` |
| 8 | `H4-RUN-RESULT-RECEIPT` | `result-receipt.json` | `H4_RUN_RESULT_RECEIPT_V2` | `H4_RUN_REVIEWER` |
| 9 | `H4-RUN-CLOSE-INTENT-PAYLOAD` | `close-intent.payload.json` | `H4_RUN_CLOSE_INTENT_PAYLOAD_V2` | `H4_RUN_CLOSE_SERIALIZER` |
| 10 | `H4-RUN-CLOSE-INTENT-SIGNATURE` | `close-intent.signature.json` | `H4_RUN_CLOSE_INTENT_SIGNATURE_V2` | `H4_RUN_CLOSE_SIGNER` |
| 11 | `H4-RUN-CLOSE-RECEIPT` | `close-receipt.json` | `H4_RUN_CLOSE_RECEIPT_V2` | `H4_RUN_STATE_CAS_SERVICE` |

At freeze, each table row expands to this closed spec:

```text
H4RunRoleSpecV2:
  ordinal,role_id,literal_path,schema_role,schema_sha,
  publisher_actor_id,publisher_physical_sha,physical_kind=FILE,
  exact_cardinality=1

H4ResolvedReadRefV2:
  every H4RunRoleSpecV2 field,
  content_sha,artifact_physical:FilePhysicalV2

H4ResolvedControlRefV2:
  every H4RunRoleSpecV2 field,
  content_sha,artifact_physical:FilePhysicalV2,
  producing_transition_id,predecessor_set_digest
```

`ordered_read_refs[9]` is exactly nine `H4ResolvedReadRefV2` rows.
`ordered_control_specs[11]` is exactly eleven `H4RunRoleSpecV2` rows, because
future control outputs cannot carry future content SHA/Physical in the grant.
As each control output settles, the runtime registry adds the corresponding
`H4ResolvedControlRefV2`; every later receipt directly binds all of its
resolved predecessors. The H4 authority-scope registry carries the same
spec/ref bytes at the applicable phase. No path-only or count-only row is
valid.

`D_i` is the exact `data_count` for run `i`, not a prose wildcard. The signed
grant contains `ordered_data_refs[D_i]` with
`ordinal,role_id,literal_path,schema_role,schema_sha,publisher_actor_id,
publisher_physical_sha,physical_kind=FILE,content_sha,
artifact_physical:FilePhysicalV2`, and the runtime RoleInstance registry
contains the same resolved rows. Thus:

```text
read_count = 9
control_count = 11
data_count = D_i
pairwise read/control/data intersections = 0/0/0
union_count = 20 + D_i
read_digest = digest(ordered 9 full refs)
control_digest = digest(ordered 11 full refs)
data_digest = digest(ordered D_i full refs)
union_digest = digest(read rows || control rows || data rows)
```

The immutable per-run authority key is:

```text
H4RunAuthorityKeyV2 = {
  "schema":"H4_RUN_AUTHORITY_KEY_V2",
  "h4_id":Identifier,
  "run_id":Identifier,
  "subject_sha":Sha256Hex,
  "candidate_sha":Sha256Hex,
  "scope_freeze_sha":Sha256Hex,
  "grant_nonce":Nonce256Hex
}

run_authority_key_digest =
  SHA256(ASCII("WS-WALKSAFE-R007-H4-RUN-AUTHORITY-KEY-V2") || 0x00 ||
         RFC8785_JCS(H4RunAuthorityKeyV2))
```

The following are the closed payload bodies; every detached signature file has
exact fields `schema,payload_sha,payload_physical,signer_actor_id,
signer_physical_sha,signature_domain,signature_algorithm,signature`.

```text
H4RunGrantPayloadV2:
  schema,h4_id,run_id,subject_sha,subject_physical
  run_authority_key:H4RunAuthorityKeyV2,run_authority_key_digest
  candidate_sha,candidate_physical
  scope_authority_sha,scope_authority_physical
  scope_freeze_sha,scope_freeze_physical
  admin_gate_result_sha,admin_gate_result_physical
  admin_gate_receipt_sha,admin_gate_receipt_physical
  ordered_read_refs[9],ordered_control_specs[11],ordered_data_refs[D_i]
  read_count,control_count,data_count,union_count
  read_digest,control_digest,data_digest,union_digest
  three pairwise intersection counts
  grant_nonce,scope_digest,issued_at,operation_not_after,settlement_not_after
  trusted_clock_source_id,trusted_clock_source_physical_sha,
  trusted_clock_correlation_sha
  revocation_head_sha,revocation_head_physical,revocation_head_token
  publisher_actor_id,publisher_physical_sha

H4RunStateInitReceiptV2:
  schema,h4_id,run_id,grant_payload_sha,grant_signature_sha
  run_authority_key_digest
  pre_state=ABSENT,key_nonexistence_proof_digest
  post_state=UNSPENT,initial_state_token
  operation_not_after,settlement_not_after
  trusted_clock_source_id,trusted_clock_source_physical_sha,
  trusted_clock_correlation_sha
  revocation_head_sha,revocation_head_physical,revocation_head_token
  authority_store_id,run_state_record_physical
  initialized_at,publisher_actor_id,publisher_physical_sha
  signature_domain=WS-WALKSAFE-R007-H4-RUN-STATE-INIT-V2
  signature_algorithm,signature

H4RunConsumeIntentPayloadV2:
  schema,h4_id,run_id,grant_payload_sha,grant_signature_sha
  run_authority_key_digest
  expected_state=UNSPENT,expected_state_token
  grant_nonce,scope_digest
  revocation_head_sha,revocation_head_physical,revocation_head_token
  operation_not_after,settlement_not_after,dispatch_nonce
  trusted_clock_source_id,trusted_clock_source_physical_sha,
  trusted_clock_correlation_sha
  publisher_actor_id,publisher_physical_sha

H4RunConsumeReceiptV2:
  schema,h4_id,run_id,consume_intent_payload_sha,consume_intent_signature_sha
  run_authority_key_digest
  pre_state=UNSPENT,post_state=CONSUMED_UNDISPATCHED
  pre_state_token,post_state_token
  consume_committed_at,operation_not_after,settlement_not_after
  trusted_clock_source_id,trusted_clock_source_physical_sha,
  trusted_clock_correlation_sha
  revocation_head_sha,revocation_head_physical,revocation_head_token
  authority_store_id,run_state_record_physical
  publisher_actor_id,publisher_physical_sha
  signature_domain=WS-WALKSAFE-R007-H4-RUN-CONSUME-V2
  signature_algorithm,signature

H4RunDispatchReceiptV2:
  schema,h4_id,run_id,consume_receipt_sha,consume_receipt_physical
  run_authority_key_digest
  pre_state=CONSUMED_UNDISPATCHED,post_state=DISPATCHED
  pre_state_token,post_state_token,dispatch_nonce
  dispatch_started_at,operation_not_after,settlement_not_after
  trusted_clock_source_id,trusted_clock_source_physical_sha,
  trusted_clock_correlation_sha
  revocation_head_sha,revocation_head_physical,revocation_head_token
  ordered_data_refs[D_i],data_digest
  worker_actor_id,worker_physical_sha
  publisher_actor_id,publisher_physical_sha
  signature_domain=WS-WALKSAFE-R007-H4-RUN-DISPATCH-V2
  signature_algorithm,signature

H4RunResultReceiptV2:
  schema,h4_id,run_id,dispatch_receipt_sha,dispatch_receipt_physical
  run_authority_key_digest
  pre_state=DISPATCHED,post_state=RESULT_RECORDED
  pre_state_token,post_state_token
  operation_not_after,settlement_not_after
  trusted_clock_source_id,trusted_clock_source_physical_sha,
  trusted_clock_correlation_sha
  revocation_head_sha,revocation_head_physical,revocation_head_token
  ordered_raw_evidence_refs[]
  raw_evidence_count,raw_evidence_digest
  ordered_assertion_results[]
  assertion_count,assertion_digest
  result_status=PASS|FAIL
  recorded_at,publisher_actor_id,publisher_physical_sha
  signature_domain=WS-WALKSAFE-R007-H4-RUN-RESULT-V2
  signature_algorithm,signature

H4RunCloseIntentPayloadV2:
  schema,h4_id,run_id,result_receipt_sha,result_receipt_physical
  grant_payload_sha,grant_payload_physical
  grant_signature_sha,grant_signature_physical
  run_authority_key_digest
  expected_state=RESULT_RECORDED,expected_state_token
  final_result_status,grant_nonce,scope_digest
  operation_not_after,settlement_not_after
  revocation_head_sha,revocation_head_physical,revocation_head_token
  trusted_clock_source_id,trusted_clock_source_physical_sha,
  trusted_clock_correlation_sha
  publisher_actor_id,publisher_physical_sha

H4RunCloseReceiptV2:
  schema,h4_id,run_id,close_intent_payload_sha,close_intent_signature_sha
  run_authority_key_digest
  result_receipt_sha,result_receipt_physical
  pre_state=RESULT_RECORDED,post_state=SPENT_CLOSED
  pre_state_token,post_state_token,final_result_status
  close_committed_at,operation_not_after,settlement_not_after
  trusted_clock_source_id,trusted_clock_source_physical_sha,
  trusted_clock_correlation_sha
  revocation_head_sha,revocation_head_physical,revocation_head_token
  authority_store_id,run_state_record_physical
  publisher_actor_id,publisher_physical_sha
  signature_domain=WS-WALKSAFE-R007-H4-RUN-CLOSE-V2
  signature_algorithm,signature
```

Grant and consume-intent signature domains are respectively
`WS-WALKSAFE-R007-H4-RUN-GRANT-V2` and
`WS-WALKSAFE-R007-H4-RUN-CONSUME-INTENT-V2`; close-intent uses
`WS-WALKSAFE-R007-H4-RUN-CLOSE-INTENT-V2`. Signing input is always domain,
NUL and JCS payload. Every receipt is published by the named CAS service
Physical, except the result receipt, which is published by the independent run
reviewer Physical.

`aggregate_state_record_physical` and `run_state_record_physical` identify the
already committed underlying authority-state CAS record, never the enclosing
project receipt. The enclosing receipt's own SHA/`FilePhysicalV2` is supplied
only by its later publication settlement.

The grant requires
`issued_at < operation_not_after < settlement_not_after`. Initialization CAS
compares nonexistence of exactly `run_authority_key_digest`; every later CAS
byte-compares the same key, grant pair, scope, deadlines, trusted-clock
binding, current revocation head SHA/Physical/token and expected state token.
No later intent may introduce or extend a deadline. Any mismatch has
state/token/output effect `0`.

The same-store FSM is total:

```text
ABSENT --INITIALIZE--> UNSPENT
UNSPENT --CONSUME, trusted_now < operation_not_after-->
  CONSUMED_UNDISPATCHED
CONSUMED_UNDISPATCHED --DISPATCH, trusted_now < operation_not_after-->
  DISPATCHED
DISPATCHED --RECORD_RESULT--> RESULT_RECORDED
RESULT_RECORDED --CLOSE, trusted_now < settlement_not_after--> SPENT_CLOSED
```

All other event/state pairs reject without output. In particular result must
precede close. Consume stores only `consume_committed_at`; dispatch time exists
only in its later receipt. Equality at either deadline rejects.

### 10.3 exact five Gate artifacts and publishers

The gate order is immutable:

```text
1 GATE-SINGLE-ADMIN-RECOVERY-DRILL
2 GATE-PHONE-QUEUE-BYTE-LIMIT
3 GATE-SERVER-CAPACITY-STATE-CONTRACT
4 GATE-RAW-COLLECTION-RELEASE-REVIEW
5 GATE-CLOUD-COST-MEASUREMENT
```

The exact 15 Gate paths are:

| gate ID | raw evidence path | result path | receipt path |
|---|---|---|---|
| `GATE-SINGLE-ADMIN-RECOVERY-DRILL` | `H4_ROOT_TEMPLATE/gates/01-single-admin-recovery-drill/raw-evidence.bin` | `H4_ROOT_TEMPLATE/gates/01-single-admin-recovery-drill/result.json` | `H4_ROOT_TEMPLATE/gates/01-single-admin-recovery-drill/receipt.json` |
| `GATE-PHONE-QUEUE-BYTE-LIMIT` | `H4_ROOT_TEMPLATE/gates/02-phone-queue-byte-limit/raw-evidence.bin` | `H4_ROOT_TEMPLATE/gates/02-phone-queue-byte-limit/result.json` | `H4_ROOT_TEMPLATE/gates/02-phone-queue-byte-limit/receipt.json` |
| `GATE-SERVER-CAPACITY-STATE-CONTRACT` | `H4_ROOT_TEMPLATE/gates/03-server-capacity-state-contract/raw-evidence.bin` | `H4_ROOT_TEMPLATE/gates/03-server-capacity-state-contract/result.json` | `H4_ROOT_TEMPLATE/gates/03-server-capacity-state-contract/receipt.json` |
| `GATE-RAW-COLLECTION-RELEASE-REVIEW` | `H4_ROOT_TEMPLATE/gates/04-raw-collection-release-review/raw-evidence.bin` | `H4_ROOT_TEMPLATE/gates/04-raw-collection-release-review/result.json` | `H4_ROOT_TEMPLATE/gates/04-raw-collection-release-review/receipt.json` |
| `GATE-CLOUD-COST-MEASUREMENT` | `H4_ROOT_TEMPLATE/gates/05-cloud-cost-measurement/raw-evidence.bin` | `H4_ROOT_TEMPLATE/gates/05-cloud-cost-measurement/result.json` | `H4_ROOT_TEMPLATE/gates/05-cloud-cost-measurement/receipt.json` |

Each row is typed by:

```text
H4GateEvidenceV2:
  exact raw bytes; FilePhysicalV2 and content SHA are bound by result

H4GateResultV2:
  schema,h4_id,gate_id,gate_ordinal
  candidate_sha,candidate_physical
  test_plan_approval_sha,test_plan_approval_physical
  raw_evidence_sha,raw_evidence_physical
  ordered_measurement_rows[],measurement_count,measurement_digest
  result_status=PASS|FAIL
  waived=false
  reviewed_at,reviewer_actor_id,reviewer_physical_sha
  signature_domain=WS-WALKSAFE-R007-H4-GATE-RESULT-V2
  signature_algorithm,signature

H4GateReceiptV2:
  schema,h4_id,gate_id,gate_ordinal
  candidate_sha,candidate_physical
  raw_evidence_sha,raw_evidence_physical
  result_sha,result_physical
  result_status=PASS|FAIL,waived=false
  reviewer_actor_id,reviewer_physical_sha
  reviewer_signature_algorithm,reviewer_signature
  gate_cas_token,committed_at
  publisher_actor_id,publisher_physical_sha
  signature_domain=WS-WALKSAFE-R007-H4-GATE-RECEIPT-V2
  signature_algorithm,signature
```

Result is signed by an independent reviewer; receipt is signed by the Gate CAS
service. Each signature input is its domain, NUL and JCS object without the
`signature` field. Raw evidence, result and receipt must all share the exact
candidate and Gate ID; `waived` has the singleton domain `false`.

### 10.4 must-close targets, exact-five closure and H4 completion

The six unique literal target templates are:

| target ID | literal path template | schema role | publisher role |
|---|---|---|---|
| `FIRST-USER-TEST-DISPATCH-ELIGIBILITY` | `H4_ROOT_TEMPLATE/targets/first-user-test-dispatch-eligibility.receipt.json` | `H4_MUST_CLOSE_TARGET_RECEIPT_V2` | `H4_MUST_CLOSE_TARGET_SERVICE` |
| `SUPPORTED-DEVICE-USER-TEST-SCOPE-FREEZE` | `H4_ROOT_TEMPLATE/targets/scope-freeze.receipt.json` | `H4_MUST_CLOSE_TARGET_RECEIPT_V2` | `H4_MUST_CLOSE_TARGET_SERVICE` |
| `SERVER-CAPACITY-INTEGRATION-COMPLETION` | `H4_ROOT_TEMPLATE/targets/server-capacity-integration-completion.receipt.json` | `H4_MUST_CLOSE_TARGET_RECEIPT_V2` | `H4_MUST_CLOSE_TARGET_SERVICE` |
| `TST22-READINESS` | `H4_ROOT_TEMPLATE/targets/TST22-readiness.receipt.json` | `H4_MUST_CLOSE_TARGET_RECEIPT_V2` | `H4_MUST_CLOSE_TARGET_SERVICE` |
| `ACTUAL-DEPLOY-ELIGIBILITY` | `H4_ROOT_TEMPLATE/targets/actual-deploy-eligibility.receipt.json` | `H4_MUST_CLOSE_TARGET_RECEIPT_V2` | `H4_MUST_CLOSE_TARGET_SERVICE` |
| `OPERATING-COST-BASELINE-FREEZE` | `H4_ROOT_TEMPLATE/targets/operating-cost-baseline-freeze.receipt.json` | `H4_MUST_CLOSE_TARGET_RECEIPT_V2` | `H4_MUST_CLOSE_TARGET_SERVICE` |

The exact seven direct edges and required multiplicity vector are:

| edge ID | Gate source | target |
|---|---|---|
| `H4G001` | `GATE-SINGLE-ADMIN-RECOVERY-DRILL` receipt PASS | `FIRST-USER-TEST-DISPATCH-ELIGIBILITY` |
| `H4G002` | `GATE-PHONE-QUEUE-BYTE-LIMIT` receipt PASS | `SUPPORTED-DEVICE-USER-TEST-SCOPE-FREEZE` |
| `H4G003` | `GATE-SERVER-CAPACITY-STATE-CONTRACT` receipt PASS | `SERVER-CAPACITY-INTEGRATION-COMPLETION` |
| `H4G004` | `GATE-RAW-COLLECTION-RELEASE-REVIEW` receipt PASS | `TST22-READINESS` |
| `H4G005` | `GATE-RAW-COLLECTION-RELEASE-REVIEW` receipt PASS | `ACTUAL-DEPLOY-ELIGIBILITY` |
| `H4G006` | `GATE-CLOUD-COST-MEASUREMENT` receipt PASS | `OPERATING-COST-BASELINE-FREEZE` |
| `H4G007` | `GATE-CLOUD-COST-MEASUREMENT` receipt PASS | `TST22-READINESS` |

Thus per-Gate multiplicity is `[1,1,1,2,2]`, edge count is seven and target
count is six. `TST22-READINESS` requires both incoming receipts.

Each target is one signed `H4MustCloseTargetReceiptV2` with exact body:

```text
schema
h4_id
candidate_sha + candidate_physical
target_id + target_literal_path
target_schema_role + target_schema_sha
ordered_required_source_gate_refs[]{
  edge_id,gate_id,gate_ordinal,
  gate_result_sha,gate_result_physical,
  gate_receipt_sha,gate_receipt_physical,
  result_status=PASS,waived=false
}
required_source_count
required_source_digest
prior_target_state=ABSENT
prior_target_token
post_target_state=CLOSED
post_target_token
linearized_at
publisher_actor_id + publisher_physical_sha
signature_domain=WS-WALKSAFE-R007-H4-MUST-CLOSE-TARGET-V2
signature_algorithm + signature
```

The required source rows are exactly the incoming `H4G` edges. Five targets
require one row; `TST22-READINESS` requires ordered rows `H4G004,H4G007`.
The CAS compares absence/token and commits each target identity once. The body
does not contain its own SHA or output `FilePhysicalV2`; publication
settlement supplies them. Wrong schema, publisher/Physical, source
multiplicity, candidate, Gate Physical or token rejects.

The 16th exact-five artifact is
`H4_ROOT_TEMPLATE/gates/exact-five-closure.receipt.json`.
`H4ExactFiveClosureReceiptV2` exact body:

```text
schema,h4_id,candidate_sha,candidate_physical
ordered_gate_rows[5]{
  gate_id,gate_ordinal,raw_evidence_sha,raw_evidence_physical,
  result_sha,result_physical,receipt_sha,receipt_physical,
  result_status=PASS,waived=false,reviewer_actor_id,reviewer_physical_sha,
  reviewer_signature_algorithm,reviewer_signature
}
gate_count=5,ordered_gate_digest
ordered_must_close_edges[7]{edge_id,source_gate_id,target_id,target_literal_path}
must_close_edge_count=7,must_close_edge_digest
ordered_unique_target_refs[6]{
  target_id,literal_path,schema_role,schema_sha,
  publisher_actor_id,publisher_physical_sha,content_sha,physical
}
unique_target_count=6,unique_target_digest
publisher_actor_id,publisher_physical_sha
signature_domain=WS-WALKSAFE-R007-H4-EXACT-FIVE-CLOSURE-V2
signature_algorithm,signature
```

The H4 completion path is
`H4_ROOT_TEMPLATE/completion/h4-completion.receipt.json`.
`H4CompletionReceiptV2` has exact body:

```text
schema,h4_id,candidate_sha,candidate_physical
scope_authority_sha,scope_authority_physical
scope_freeze_sha,scope_freeze_physical
ordered_required_run_close_refs[]{
  run_id,close_receipt_sha,close_receipt_physical,final_result_status=PASS
}
required_run_count,required_run_digest
exact_five_closure_sha,exact_five_closure_physical
ordered_gate_rows[5] identical to the closure rows
ordered_gate_digest
ordered_unique_target_refs[6] identical to the closure rows
unique_target_digest
completion_status=PASS
completed_at,publisher_actor_id,publisher_physical_sha
signature_domain=WS-WALKSAFE-R007-H4-COMPLETION-V2
signature_algorithm,signature
```

It is valid only after all required runs reach `SPENT_CLOSED/PASS`, all five
Gate triples are present and exact, seven must-close edges have materialized,
all six target files have exact content/Physical, and the two embedded digests
equal the exact-five closure. Count-only “five PASS” is invalid.

### 10.5 exact negative registry

| ID | rejection predicate |
|---|---|
| `H4-N01` | scope authority missing |
| `H4-N02` | scope authority signature, publisher or Physical mismatch |
| `H4-N03` | freeze missing, precedes, self-references or mismatches authority |
| `H4-N04` | scope plane not exactly `8/2/0/10` or intersection nonzero |
| `H4-N05` | run grant wrong candidate, subject, scope, nonce or full refs |
| `H4-N06` | state-init missing, duplicate or not `ABSENT→UNSPENT` |
| `H4-N07` | consume wrong token or `trusted_now >= operation_not_after` |
| `H4-N08` | consume contains a future dispatch time |
| `H4-N09` | dispatch without `CONSUMED_UNDISPATCHED` or wrong `D_i` |
| `H4-N10` | result unknown status or missing `DISPATCHED→RESULT_RECORDED` |
| `H4-N11` | raw evidence, assertion or result digest mismatch |
| `H4-N12` | close before result, wrong final token or expired settlement |
| `H4-N13` | run plane not exactly `9/11/D_i/(20+D_i)` or intersection nonzero |
| `H4-N14` | Gate triple missing/duplicate/wrong candidate, reviewer or `waived!=false` |
| `H4-N15` | multiplicity vector, seven edges or six unique targets mismatch |
| `H4-N16` | completion is count-only, misses run close, exact-five closure or Physical |

## 11. consolidated cardinality registry

모든 수치는 §13 registry filter 결과로만 주장한다. `static`은 registry
row, `materialized`는 named runtime branch에서 predicate가 true인 row,
`new write`는 adoption/idempotent replay를 제외한 실제 새 Physical이다.

| branch or cut | exact cardinality / invariant |
|---|---|
| committed namespace | lifecycle key `1`; authority aggregate key `0` before ALLOW, `1` after ALLOW |
| `DENIED` | selected decision 1, activation 0, data write 0, `DENIED` seal 1 |
| `REQUEST_EXPIRED` | selected decision 0, dedicated signed lifecycle source pair 1, activation 0, data write 0, `REQUEST_EXPIRED` seal obligation 1 |
| pre-ALLOW `ABANDONED` | selected decision 0, dedicated signed lifecycle source pair 1, activation/data write 0, `ABANDONED` seal obligation 1 |
| all nonexecution branches | variants 3, data-plane writes 0; either seal project write 1 or signed fail-closed lifecycle terminal 1, never both |
| seal schema variants | `DENIED`, `REQUEST_EXPIRED`, `ABANDONED`, `EXECUTED`; exact 4 |
| ALLOW activation | aggregate activation receipt 1, role rows 4, each initial state `U` |
| execution pre-revoke | consume/dispatch `0/0`, selected disposition pair `1`, seal 1 |
| close-recovery pre-revoke | original consume 0; selected unavailable disposition pair 1 |
| finalization pre-revoke | original consume 0; selected unavailable disposition pair 1 |
| wrapper pre-close revoke/expiry/crash | pending cause 1, early terminal-key consumption 0 |
| normal terminal | selected normal/outbox/terminal `1/1/1`; recovery selection 0 |
| recovery terminal | selected recovery/outbox/terminal `1/1/1`; normal selection 0 |
| consumed finalization | consume/work outbox `1/1`; expansion payload/signature 2; completion inputs 15 |
| successful finalization | PostG7 pair 2, final close 1 |
| failed or pre-revoked finalization | PostG7 pair 0; one failure/unavailable disposition |
| existing wrapper adoption | stored functional profile unchanged; functional outbox/new writes `0/0`; seal obligation/outbox `1/1`; eventual seal project write 1 |
| wrapper consume winner | selection/outbox/wrapper `1/1/1`; disposition 0 |
| wrapper revoke/expiry/crash winner | selection/outbox `1/1`; wrapper 0; disposition pair 1 |
| selected outbox item with `PUBLISH_SUCCESS` | physical output 1; settlement CAS 1 |
| same outbox retry | retry count unbounded; additional physical output 0 |
| terminal selection then late revoke | accepted/effective revoke `0/0` |
| functional terminal then seal | data/finalization writes 0; queued `ATTEMPT_SEAL` 1; seal project write 1 |
| after seal | project writes under the sealed attempt root 0; next-namespace preflight/append outside that root allowed |
| GT CAS-absent recovery | `[0,1,1,1,1]` |
| GT 15 unwrapped recovery | `[1,1,1,1,1]` |
| GT 15 unwrapped normal | `[1,0,0,1,1]` |
| GT wrapped adoption | stored normal/recovery vector; all new-write dimensions 0 |
| expansion | finalization consume receipt and obligation inbounds `2`; completion outbounds 15 |
| PostG7 | success pair 2; all failure branches 0 |
| G3 | predicates subset/cardinality/order/digest `4/4 PASS` |
| B06 H1 | roles/edges `10/26` |
| B04 H1 | roles/edges `6/23` |
| B04 ordinary application | original 48 + triple 3 + signing 1 = inbound 52 |
| B04 ordinary actual | issuance 14 + consume 1 + application 52 + tail 2 = 69 |
| H2 | file roles 50, batches 25, guards 25, project outputs 75 |
| H2 result batches | 6 batches × exact five outputs |
| M02 | roles `19`; local edges `23+1=24`; H2 member-constructor edges 3 |
| H4 scope | read/control/data/union `8/2/0/10`; intersections `0/0/0` |
| each H4 run | read/control/data/union `9/11/D_i/(20+D_i)`; intersections `0/0/0` |
| H4 Gates | raw/result/receipt/closure `5/5/5/1`; ordered Gate count 5 |
| H4 must-close | multiplicity `[1,1,1,2,2]`; edges/unique targets `7/6` |

No other number in prose is authoritative. H2 final graph node/edge totals,
global totals, alias-resolved totals and any sum containing runtime `D_i` are
generated values and must not be seeded from `262`, `306` or R001/R006
intermediate totals.

## 12. preparation and canonical closure order

Primitive 준비와 canonical finding closure를 분리한다. Preparation commit
message, PR 본문과 FindingCompletion row는 `Prepares:`만 사용할 수 있고
`Closes: R007-*`를 쓸 수 없다.

| preparation | exact scope | verification before merge |
|---|---|---|
| `P1` | §4.1~§4.5 scalar, constructor, lifecycle, closed contexts, grant and activation schemas | constructor golden/negative vectors; exact-four activation rows; nonexecution zero-authority |
| `P2` | §4.6~§4.10 total FSM, selector, CRASH pair, transition core, outbox, wrapper and seal | event/state product totality; four-role settlement; crash/replay and cause-tie fixtures |
| `P3` | §5 projections, GT/EO/ET, expansion, PostG7 and G3 | all cut vectors; consume→expansion lineage; 2/0 scope; four G3 predicates |
| `P4` | §6 B06 and §7 B04/application schemas and literal edges | B06 `10/26`; B04 `6/23`; application `52/69` |
| `P5` | §8 H2 batches and §9 M02 | H2 `50/25/75`; six exact-five result batches; M02 `19/24`; three H2 member edges |
| `P6` | §10 H4 scope/run/Gates | scope `8/2/0/10`; run `9/11/D_i`; Gate `5/15/1`; edge/target `7/6`; 16 negatives |
| `P7` | §13~§15 registries, fixtures, predicate runner and report generator | closed-schema validation; 24 fixture rows; source/finding/predicate bijection |

Preparation may be developed in parallel where files do not overlap, but all
seven must be frozen and their registries regenerated before `C1`. A
preparation failure leaves all canonical findings open.

Canonical closure is the sole sequence:

| closure batch | exact findings | hard predecessors | atomic completion rule |
|---|---|---|---|
| `C1` | `R007-B001`, `R007-B002`, `R007-B003`, `R007-B004`, `R007-B005`, `R007-B006`, `R007-B007`, `R007-B008`, `R007-B009`, `R007-B010`, `R007-B011` | `P1`, `P2`, `P3`, `P4`, `P5`, `P6`, `P7` all PASS | eleven FindingCompletion rows and their fixtures/predicates PASS in one generated registry version |
| `C2` | `R007-B012`, `R007-B013`, `R007-B014` | durable `C1` receipt | three rows PASS; GT/EO/ET, expansion and PostG7 digest equality |
| `C3` | `R007-B015`, `R007-B016`, `R007-B017`, `R007-B018`, `R007-B019`, `R007-B020` | durable `C2` receipt | six rows PASS; B04/B06/H2/H4/seal cardinalities regenerated |
| `C4` | `R007-M001`, `R007-M002`, `R007-M003`, `R007-M004` | durable `C3` receipt and every blocking finding closed | four rows PASS; full mechanical and skeptical regression |

The literal order is therefore:

```text
P1,P2,P3,P4,P5,P6,P7 preparation
→ C1 {B001,B002,B003,B004,B005,B006,B007,B008,B009,B010,B011}
→ C2 {B012,B013,B014}
→ C3 {B015,B016,B017,B018,B019,B020}
→ C4 {M001,M002,M003,M004}
```

Within a closure batch, each completion row directly binds its canonical
finding ID, source references, implementation commit SHA, role/node/edge/scope
registry digests, fixture SHA/Physical, predicate result SHA/Physical and
reviewer signature. Partial batch success is recorded as test evidence only;
it does not materialize a subset of `Closes:` rows. `C4` cannot be pulled
forward merely because a major fixture happens to pass.

## 13. deterministic closed registries

Role, Node, Edge, BranchCardinality, AuthorityScope, Fixture와
FindingCompletion registry를 닫는다. A schema named below is a closed JSON
object: every field is required, no additional field is allowed, and every
array order is semantic.

### 13.1 registry bundle and publication without self-reference

The generated bundle contains these exact seven logical registries:

```text
ROLE
NODE
EDGE
BRANCH_CARDINALITY
AUTHORITY_SCOPE
FIXTURE
FINDING_COMPLETION
```

`ROLE` has separate template and instance tables within one payload. Each
registry is published as a payload file, detached-signature file and
CAS-internal publication binding. The exact 14 paths are:

```text
H1_ROOT_TEMPLATE/successor/registries/role.payload.json
H1_ROOT_TEMPLATE/successor/registries/role.signature.json
H1_ROOT_TEMPLATE/successor/registries/node.payload.json
H1_ROOT_TEMPLATE/successor/registries/node.signature.json
H1_ROOT_TEMPLATE/successor/registries/edge.payload.json
H1_ROOT_TEMPLATE/successor/registries/edge.signature.json
H1_ROOT_TEMPLATE/successor/registries/branch-cardinality.payload.json
H1_ROOT_TEMPLATE/successor/registries/branch-cardinality.signature.json
H1_ROOT_TEMPLATE/successor/registries/authority-scope.payload.json
H1_ROOT_TEMPLATE/successor/registries/authority-scope.signature.json
H1_ROOT_TEMPLATE/successor/registries/fixture.payload.json
H1_ROOT_TEMPLATE/successor/registries/fixture.signature.json
H1_ROOT_TEMPLATE/successor/registries/finding-completion.payload.json
H1_ROOT_TEMPLATE/successor/registries/finding-completion.signature.json
```

`RegistryKindV2` is the closed enum
`ROLE|NODE|EDGE|BRANCH_CARDINALITY|AUTHORITY_SCOPE|FIXTURE|
FINDING_COMPLETION`.

`RegistryPayloadEnvelopeV2` exact fields:

```text
schema
registry_kind
registry_version=2
successor_revision_id
attempt_namespace_id
ordered_source_refs[]{
  source_id,literal_path,content_sha,physical
}
source_count + source_digest
registry_rows:
  {"kind":"ROLE",
   "ordered_template_rows":array of RoleTemplateRegistryRowV2,
   "template_count":Count,"template_digest":Sha256Hex,
   "ordered_instance_rows":array of RoleInstanceRegistryRowV2,
   "instance_count":Count,"instance_digest":Sha256Hex}
  XOR
  {"kind":"SINGLE_ROW_SET",
   "ordered_rows":array of the registry-kind-specific closed row type,
   "row_count":Count,"row_digest":Sha256Hex}
producer_actor_id + producer_physical_sha
generated_at
```

It contains neither its own content SHA nor signature/output identity.
`RegistryDetachedSignatureV2` exact fields:

```text
schema
registry_kind
payload_literal_path
payload_schema_sha
payload_sha
payload_physical
signer_actor_id + signer_physical_sha
signature_domain
signature_algorithm
signature
```

Signature input is the registry-specific domain, NUL and full payload JCS.
`RegistryPublicationBindingV2` is a signed CAS envelope, not a project file:

```text
{
  "body":{
    "schema":Identifier,
    "registry_kind":RegistryKindV2,
    "payload_sha":Sha256Hex,
    "payload_physical":FilePhysicalV2,
    "signature_sha":Sha256Hex,
    "signature_physical":FilePhysicalV2,
    "publication_transition_id":Sha256Hex,
    "publication_core_digest":Sha256Hex,
    "published_at":UtcNano,
    "publisher_actor_id":Identifier,
    "publisher_physical_sha":Sha256Hex
  },
  "service_signature":{
    "algorithm":Identifier,
    "signer_actor_id":Identifier,
    "signer_physical_sha":Sha256Hex,
    "signature_domain":"WS-WALKSAFE-R007-REGISTRY-PUBLICATION-BINDING-V2",
    "signature":SignatureBytes
  }
}
```

The signature input is its literal domain, NUL and body JCS. The binding body
does not contain its own SHA or record Physical. The CAS
return tuple supplies publication-binding SHA and `CasRecordPhysicalV2`
externally. This later tuple is the output-Physical authority for both files
and removes any need for a file to contain its own SHA or
`FilePhysicalV2`. Consumers must bind payload SHA/Physical, signature
SHA/Physical and the publication binding's SHA/`CasRecordPhysicalV2`;
content-only references are invalid.

Registry signing domains are exact:

```text
ROLE               WS-WALKSAFE-R007-ROLE-REGISTRY-V2
NODE               WS-WALKSAFE-R007-NODE-REGISTRY-V2
EDGE               WS-WALKSAFE-R007-EDGE-REGISTRY-V2
BRANCH_CARDINALITY WS-WALKSAFE-R007-BRANCH-CARDINALITY-REGISTRY-V2
AUTHORITY_SCOPE    WS-WALKSAFE-R007-AUTHORITY-SCOPE-REGISTRY-V2
FIXTURE            WS-WALKSAFE-R007-FIXTURE-REGISTRY-V2
FINDING_COMPLETION WS-WALKSAFE-R007-FINDING-COMPLETION-REGISTRY-V2
```

Row digest for non-ROLE registry `K` is:

```text
SHA256(ASCII(domain(K)) || 0x00 || RFC8785_JCS(ordered_rows))
```

For ROLE, template and instance arrays are independently sorted by
`registry_ordinal`, independently hashed with domains
`WS-WALKSAFE-R007-ROLE-TEMPLATE-ROWS-V2` and
`WS-WALKSAFE-R007-ROLE-INSTANCE-ROWS-V2`, then the ROLE payload binds both
counts and digests. Mixing the two row types in one untagged array is invalid.

### 13.2 closed Role, Node and Edge rows

`LogicalCardinalitySpecV2` is exactly one of:

```text
{"kind":"EXACT","value":Count}
{"kind":"BRANCH_EXACT","branch_ast":BranchAstV2,"value":Count}
{"kind":"DATA_AFFINE","run_id":Identifier,"base":Count,"coefficient":1}
{"kind":"DERIVED_UNIQUE",
 "dedupe_key_enum":
   "ROLE_ID"|"NODE_ID"|"EDGE_TUPLE"|"OUTPUT_PHYSICAL_IDENTITY"}
```

`RoleTemplateRegistryRowV2` exact fields:

```text
schema
registry_ordinal
role_id
path_template
ordered_allowed_tokens[]
schema_role
schema_sha
publisher_actor_role
publisher_physical_sha
physical_kind = FILE | DIRECTORY | CAS_RECORD
logical_cardinality_spec: LogicalCardinalitySpecV2
branch_ast
alias_binding:
  {"kind":"PRIMARY"}
  | {"kind":"ALIAS_OF","target_role_id":Identifier}
```

Only §4.1's nine tokens may occur, each token must be listed exactly once in
`ordered_allowed_tokens`, and wildcard/glob/ellipsis are forbidden.
`RoleInstanceRegistryRowV2` exact fields:

```text
schema
registry_ordinal
template_role_id
role_instance_id
ordered_token_bindings[]{token,value}
resolved_literal_path
schema_role
schema_sha
publisher_actor_id
publisher_physical_sha
content_sha
physical_kind = FILE | DIRECTORY | CAS_RECORD
output_physical: FilePhysicalV2 | DirPhysicalV2 | CasRecordPhysicalV2
branch_ast: BranchAstV2
branch_evaluation:
  {"runtime_branch":BranchEnumV2,"result":true}
alias_binding:
  {"kind":"PRIMARY"}
  | {"kind":"ALIAS_OF","target_role_instance_id":Identifier}
```

Instance rows permit no brace token, wildcard, ellipsis token, unresolved
selector or unresolved `D_i`. A CAS row uses a stable logical
`resolved_literal_path` formed by concatenating `cas-record/`, `service_id`,
`/` and `record_key_jcs_sha` solely as an identifier and
must carry `CasRecordPhysicalV2`; it is never counted as a project file.

`NodeRegistryRowV2` exact fields:

```text
schema
node_namespace
node_ordinal
node_id
node_kind = ROLE_OUTPUT | CONSTRUCTOR | CAS_TRANSITION | TERMINAL
role_instance_id_or:
  {"kind":"ROLE","role_instance_id":Identifier}
  | {"kind":"NO_ROLE"}
content_sha_or:
  {"kind":"CONTENT","sha":Sha256Hex}
  | {"kind":"NO_CONTENT"}
output_physical_or:
  {"kind":"PHYSICAL","physical":FilePhysicalV2|DirPhysicalV2|CasRecordPhysicalV2}
  | {"kind":"NO_PHYSICAL"}
branch_ast
```

`EdgeRegistryRowV2` exact fields:

```text
schema
edge_namespace
edge_ordinal
edge_id
source_node_id
target_node_id
edge_kind =
  DIRECT | COMPOSITE_MEMBER | AUTHORIZATION_PREDECESSOR |
  PRIOR_SETTLEMENT | OUTBOX_PUBLICATION | ALIAS_BINDING
source_role_instance_id_or:
  {"kind":"ROLE","role_instance_id":Identifier}
  | {"kind":"NO_ROLE"}
target_role_instance_id_or:
  {"kind":"ROLE","role_instance_id":Identifier}
  | {"kind":"NO_ROLE"}
member_ordinal_or:
  {"kind":"MEMBER","ordinal":Ordinal}
  | {"kind":"NOT_A_MEMBER"}
branch_ast
```

Within each `edge_namespace`, ordinal starts at one and is contiguous.
`edge_id = edge_namespace || decimal(edge_ordinal zero-padded to three
digits)`. The unique key is
`(edge_namespace,edge_ordinal,source_node_id,target_node_id,normalized
branch_ast)`. One ID for two tuples, two IDs for one resolved tuple, gaps,
implicit range expansion and duplicate tuple are all rejected.

### 13.3 closed Branch AST and enum

`BranchEnumV2` is the following closed ordered enum:

```text
ALWAYS
DECISION_DENIED
LIFECYCLE_REQUEST_EXPIRED
LIFECYCLE_PRE_ALLOW_ABANDONED
AUTHORITY_POST_ALLOW_PRECONSUME_ABANDONED
LIFECYCLE_SEAL_FAILED_CLOSED
EXECUTION_ALLOW
PRE_REVOKED_EXECUTION
PRE_REVOKED_CLOSE_RECOVERY
PRE_REVOKED_FINALIZATION
WRAPPER_NORMAL
WRAPPER_RECOVERY
WRAPPER_ADVERSE
FINALIZATION_SUCCESS
FINALIZATION_FAILURE
GT_CAS_ABSENT_RECOVERY
GT_UNWRAPPED_RECOVERY
GT_UNWRAPPED_NORMAL
GT_ADOPTED_NORMAL
GT_ADOPTED_RECOVERY
H2_STAGE_C
STAGE_C_ORDINARY_APPLICATION
STAGE_C_RECOVERY_EXACT6_SUFFIX
STAGE_C_RECOVERY_APPLICATION_FINALIZATION
H4_SCOPE
H4_RUN
H4_GATE
H4_COMPLETE
```

`BranchAstV2` is this tagged recursive grammar and no other expression:

```text
{"kind":"TRUE"}
{"kind":"BRANCH_IS","branch":BranchEnumV2}
{"kind":"ROLE_IS","role_id":Identifier}
{"kind":"GATE_IS","gate_id":Identifier}
{"kind":"RESULT_COUNT_EQ","value":integer 0..15}
{"kind":"RESULT_COUNT_LT","value":integer 1..16}
{"kind":"DATA_COUNT_EQ","run_id":Identifier,"value":Count}
{"kind":"AND","children":array of BranchAstV2 with length at least 2}
{"kind":"OR","children":array of BranchAstV2 with length at least 2}
{"kind":"NOT","child":one non-composite leaf}
```

`AND/OR` have at least two children, nested same-kind nodes are flattened,
children are sorted by their JCS bytes, duplicate children are rejected, and
`TRUE` is removed from `AND` but forbidden inside `OR`. An empty composite,
double negation, arbitrary string predicate or implementation-language
expression is invalid. `normalized_branch_ast` means exactly this
normalization, not source-code evaluation.

### 13.4 closed cardinality and authority-scope rows

`BranchCardinalityRegistryRowV2` exact fields:

```text
schema
registry_ordinal
assertion_id
branch_ast
projection_kind = STATIC | MATERIALIZED | NEW_WRITE
target_kind = ROLE | NODE | EDGE | PROJECT_OUTPUT
selector:
  {"kind":"EXACT_ID_SET","ordered_ids":non-empty array of Identifier}
  | {"kind":"EDGE_NAMESPACE","edge_namespace":Identifier}
  | {"kind":"ROLE_NAMESPACE","role_namespace":Identifier}
expected:
  {"kind":"EXACT_INTEGER","value":Count}
  | {"kind":"DATA_AFFINE","run_id":Identifier,"base":Count,"coefficient":1}
  | {"kind":"DERIVED_UNIQUE","dedupe_key_enum":
      "ROLE_ID"|"NODE_ID"|"EDGE_TUPLE"|"OUTPUT_PHYSICAL_IDENTITY"}
expected_ordered_id_digest
fixture_id
predicate_id
```

`DERIVED_UNIQUE` never carries an expected numeric seed. Its generated count
and ordered ID digest are outputs compared independently by the checker.
`DATA_AFFINE` is permitted only for H4 `20+D_i`.

`PhaseContextTypeV2` is the closed enum:

```text
FROZEN_NAMESPACE
AUTHORITY_REQUEST
AUTHORITY_RESPONSE
DECISION_BOUND
ACTIVATION_INPUT
ACTIVATED_ROLE
STAGE_C_APPLICATION
H2_STAGE_C
H4_SCOPE
H4_RUN
H4_GATE
H4_COMPLETE
```

Authority registry rows use these two closed row shapes:

```text
AuthorityPlaneSpecV2:
  role_instance_id,literal_path,schema_role,schema_sha,
  publisher_actor_id,publisher_physical_sha,physical_kind,
  branch_ast,exact_cardinality

AuthorityResolvedReadRefV2:
  every AuthorityPlaneSpecV2 field,
  content_sha,artifact_physical:FilePhysicalV2|DirPhysicalV2|CasRecordPhysicalV2
```

`AuthorityScopeRegistryRowV2` exact fields:

```text
schema
registry_ordinal
scope_id
phase_context_type: PhaseContextTypeV2
phase_context_sha
branch_ast
ordered_read_refs[]: AuthorityResolvedReadRefV2
ordered_control_specs[]: AuthorityPlaneSpecV2
ordered_data_specs[]: AuthorityPlaneSpecV2
read_count + read_digest
control_count + control_digest
data_count + data_digest
read_control_intersection_count
read_data_intersection_count
control_data_intersection_count
union_count + union_digest
authority_key_digest
current_authority_token
current_revocation_head_sha + current_revocation_head_physical +
current_revocation_head_token
operation_not_after + settlement_not_after
trusted_clock_source_id + trusted_clock_source_physical_sha +
trusted_clock_correlation_sha
```

Counts and plane digests are regenerated from full `AuthorityPlaneSpecV2`
bytes; each read ref projects byte-exact to its spec and additionally proves
content/output Physical. Control/data are future-safe specs and therefore
cannot contain their own/future content SHA or output Physical. Digest
equality without full-row equality, or a resolved read without Physical, is
invalid.

### 13.5 closed Fixture and FindingCompletion rows

Fixture expectations use these closed types:

```text
FixtureExpectedBranchV2 = {
  "runtime_branch":BranchEnumV2,
  "branch_ast":BranchAstV2
}

NamedCardinalityVectorV2 = {
  "ordered_dimensions":[{
    "ordinal":Ordinal,
    "dimension_id":Identifier,
    "unit":"LOGICAL_ROLE"|"NODE"|"EDGE"|"PROJECT_FILE"|
           "CAS_RECORD"|"BYTE"|"BOOLEAN",
    "projection":"STATIC"|"MATERIALIZED"|"NEW_WRITE",
    "expected":
      {"kind":"EXACT_INTEGER","value":Count}
      XOR {"kind":"EXACT_BOOLEAN","value":true|false}
      XOR {"kind":"DATA_AFFINE","run_id":Identifier,
           "base":Count,"coefficient":1}
  }],
  "dimension_count":Count,
  "dimension_digest":Sha256Hex
}

FixtureCaseResultV2 = {
  "case_id":Identifier,
  "case_kind":"POSITIVE"|"NEGATIVE",
  "expected_status":"PASS"|"REJECT",
  "actual_status":"PASS"|"REJECT"|"ERROR",
  "assertion_count":Count,
  "ordered_assertion_result_digest":Sha256Hex,
  "status":"PASS"|"FAIL"
}
```

`FixtureRegistryRowV2` exact fields:

```text
schema
registry_ordinal
fixture_id
canonical_finding_id
ordered_source_finding_refs[]{source_id,source_finding_id}
positive_case_id
ordered_negative_case_ids[]
input_manifest_sha + input_manifest_physical
expected_branch: FixtureExpectedBranchV2
expected_cardinality_vector: NamedCardinalityVectorV2
expected_static_node_digest
expected_static_edge_digest
expected_runtime_node_digest
expected_runtime_edge_digest
expected_scope_digest
runner_literal_path
runner_sha
runner_physical
literal_argv[]
result_literal_path
result_schema_sha
```

Fixture definition rows contain no result SHA, result Physical or result
status. After the frozen fixture registry is published, the runner emits a
separate signed `FixtureExecutionReceiptV2`:

```text
{
  "body":{
    "schema":Identifier,
    "fixture_id":Identifier,
    "canonical_finding_id":Identifier,
    "fixture_registry_digest":Sha256Hex,
    "fixture_registry_payload_sha":Sha256Hex,
    "fixture_registry_payload_physical":FilePhysicalV2,
    "input_manifest_sha":Sha256Hex,
    "input_manifest_physical":FilePhysicalV2,
    "runner_sha":Sha256Hex,
    "runner_physical":FilePhysicalV2,
    "literal_argv":array of non-empty UTF-8 strings,
    "implementation_commit_sha":Sha256Hex,
    "role_registry_digest":Sha256Hex,
    "node_registry_digest":Sha256Hex,
    "edge_registry_digest":Sha256Hex,
    "branch_cardinality_registry_digest":Sha256Hex,
    "authority_scope_registry_digest":Sha256Hex,
    "positive_case_result":FixtureCaseResultV2,
    "ordered_negative_case_results":array of FixtureCaseResultV2,
    "negative_case_count":Count,
    "negative_case_digest":Sha256Hex,
    "recomputed_static_node_digest":Sha256Hex,
    "recomputed_static_edge_digest":Sha256Hex,
    "recomputed_runtime_node_digest":Sha256Hex,
    "recomputed_runtime_edge_digest":Sha256Hex,
    "recomputed_scope_digest":Sha256Hex,
    "result_status":"PASS"|"FAIL",
    "completed_at":UtcNano,
    "publisher_actor_id":Identifier,
    "publisher_physical_sha":Sha256Hex,
    "signature_domain":"WS-WALKSAFE-R007-FIXTURE-EXECUTION-RECEIPT-V2"
  },
  "signature":{
    "algorithm":Identifier,
    "signer_actor_id":Identifier,
    "signer_physical_sha":Sha256Hex,
    "signature_domain":
      "WS-WALKSAFE-R007-FIXTURE-EXECUTION-RECEIPT-V2",
    "signature":SignatureBytes
  }
}
```

Signature input is the literal domain, NUL and body JCS. Its own SHA and
`FilePhysicalV2` are supplied only after publication.
`FindingCompletionRegistryRowV2` binds that later SHA/Physical. Thus the
fixture registry digest is an inward predecessor of the result and never
depends on result identity.

`FindingCompletionRegistryRowV2` exact fields:

```text
{
  "completion_body":{
    "schema":Identifier,
    "registry_ordinal":Ordinal,
    "canonical_finding_id":Identifier,
    "severity":"BLOCKING"|"MAJOR",
    "closure_batch_id":"C1"|"C2"|"C3"|"C4",
    "ordered_source_finding_refs":
      array of {"source_id":Identifier,"source_finding_id":Identifier},
    "fixture_id":Identifier,
    "predicate_id":Identifier,
    "implementation_commit_sha":Sha256Hex,
    "role_registry_digest":Sha256Hex,
    "node_registry_digest":Sha256Hex,
    "edge_registry_digest":Sha256Hex,
    "branch_cardinality_registry_digest":Sha256Hex,
    "authority_scope_registry_digest":Sha256Hex,
    "fixture_registry_digest":Sha256Hex,
    "fixture_result_sha":Sha256Hex,
    "fixture_result_physical":FilePhysicalV2,
    "predicate_result_sha":Sha256Hex,
    "predicate_result_physical":FilePhysicalV2,
    "predecessor_closure_receipt_or":
      {"kind":"GENESIS_C1"}
      XOR {"kind":"RECEIPT","sha":Sha256Hex,"physical":FilePhysicalV2},
    "reviewer_actor_id":Identifier,
    "reviewer_physical_sha":Sha256Hex,
    "reviewed_at":UtcNano,
    "status":"PASS"|"FAIL",
    "signature_domain":"WS-WALKSAFE-R007-FINDING-COMPLETION-V2"
  },
  "review_signature":{
    "algorithm":Identifier,
    "signer_actor_id":Identifier,
    "signer_physical_sha":Sha256Hex,
    "signature_domain":"WS-WALKSAFE-R007-FINDING-COMPLETION-V2",
    "signature":SignatureBytes
  }
}
```

The review signature signs the literal domain, NUL and
`RFC8785_JCS(completion_body)`. An open finding has no completion-registry row;
the row materializes only as signed PASS/FAIL evidence.
`PASS` is admitted only when the fixture and predicate independently return
PASS for the same implementation commit and all six registry digests. Missing
source mapping, a reused fixture/predicate, predecessor closure mismatch or
future/self reference forces `FAIL`.

### 13.6 alias, counting and static/runtime separation

Aliases are legal only when source and target have byte-equal resolved path,
schema SHA, content SHA, publisher actor/Physical, output Physical and branch
AST/evaluation. Alias chains and cycles are forbidden; every alias points
directly to a `PRIMARY` instance.

```text
logical_role_count =
  count(unique role_instance_id satisfying branch_ast)

project_output_count =
  count(unique
    (resolved_literal_path,schema_sha,content_sha,
     publisher_physical_sha,output_physical identity)
    satisfying branch_ast and physical_kind=FILE)

node_count =
  count(unique node_id satisfying branch_ast)

edge_count =
  count(unique
    (edge_namespace,edge_ordinal,source_node_id,target_node_id,
     normalized_branch_ast)
    satisfying branch_ast)
```

Static template rows establish possible roles/edges only. Runtime rows must
resolve every token, selector, content SHA, publisher and Physical. A template
count cannot substitute for a materialized count. Adoption retains
materialized rows but produces zero `NEW_WRITE` rows.

H2 final edges are regenerated from the exact 50 role rows, 25 batch/guard
co-output rows, the literal 24 `H2P` prior-publication rows,
`H2M001,H2M002,H2M003`, the literal application/M02 rows, then alias
resolution. This list is an input rule, not a numeric final total. The
independent checker derives the same ordered tuples from source registries and
compares count and digest; a hard-coded starting total is forbidden.

## 14. fixture registry

§3.1의 24 canonical finding과 exact one-to-one fixture를 둔다. Every row
expands to one `FixtureRegistryRowV2`; positive and negative case IDs below are
literal IDs, not narrative examples.

| ord | fixture ID | canonical finding | positive expected result | required negative cases |
|---:|---|---|---|---|
| 1 | `FX-R007-B001-G0-DIGEST-SPLIT` | `R007-B001` | snapshot digest and observation-object digest independently recompute and match | `B001-N01-SELF-PREIMAGE`, `B001-N02-DIGEST-ALIAS`, `B001-N03-WRONG-SNAPSHOT` |
| 2 | `FX-R007-B002-NONEXECUTION-SEAL` | `R007-B002` | three nonexecution cuts each produce zero data writes and one matching seal; all four seal variants schema-valid | `B002-N01-MISSING-SEAL`, `B002-N02-WRONG-VARIANT`, `B002-N03-DATA-WRITE`, `B002-N04-NEXT-FREEZE-WRONG-ROOT`, `B002-N05-MISSING-LIFECYCLE-SOURCE-SIGNATURE`, `B002-N06-EXECUTION-EXPIRY-SUBSTITUTE`, `B002-N07-LIFECYCLE-LEASE-CRASH-GAP` |
| 3 | `FX-R007-B003-AGGREGATE-ACTIVATION` | `R007-B003` | one activation receipt with roles `execution,close-recovery,finalization,terminal-wrapper-recovery`, each `U` | `B003-N01-THREE-ROWS`, `B003-N02-FIVE-ROWS`, `B003-N03-DUPLICATE`, `B003-N04-LEGACY-KEY` |
| 4 | `FX-R007-B004-DEADLINE-BOUNDARY` | `R007-B004` | trusted CAS tick immediately before deadline accepts | `B004-N01-EQUAL`, `B004-N02-AFTER`, `B004-N03-PRECHECK-COMMIT-ADVANCE`, `B004-N04-CLIENT-CLOCK` |
| 5 | `FX-R007-B005-FINALIZATION-GRANT` | `R007-B005` | finalization grant directly precedes execution consume and exact scope is equal | `B005-N01-MISSING-GRANT`, `B005-N02-LATE-GRANT`, `B005-N03-WRONG-SCOPE`, `B005-N04-WRONG-NONCE` |
| 6 | `FX-R007-B006-WRAPPER-GRANT` | `R007-B006` | terminal-wrapper grant directly precedes finalization consume | `B006-N01-MISSING-GRANT`, `B006-N02-LATE-GRANT`, `B006-N03-WRONG-TARGET`, `B006-N04-REVOKED-GRANT` |
| 7 | `FX-R007-B007-CRASH-SOURCE` | `R007-B007` | one signed current-heartbeat/current-token CRASH pair selects ET003 | `B007-N01-FORGED`, `B007-N02-STALE-HEARTBEAT`, `B007-N03-WRONG-PROCESS`, `B007-N04-UNTRUSTED-CLOCK`, `B007-N05-TWO-SOURCES` |
| 8 | `FX-R007-B008-NONWRAPPER-PREREVOKE` | `R007-B008` | each of three non-wrapper roles leaves original consume at zero and produces one disposition pair | `B008-N01-ORIGINAL-CONSUME`, `B008-N02-UNRESOLVED-ROLE`, `B008-N03-TWO-DISPOSITIONS` |
| 9 | `FX-R007-B009-WRAPPER-PRECLOSE` | `R007-B009` | early revoke/expiry/crash preserves pending cause and leaves terminal key unconsumed until finalization close | `B009-N01-EARLY-CONSUME`, `B009-N02-CAUSE-LOSS`, `B009-N03-WRONG-TIE` |
| 10 | `FX-R007-B010-PRECLOSE-CRASH` | `R007-B010` | every crash point after consume resumes the same obligation/outbox and settles once | `B010-N01-LOST-OBLIGATION`, `B010-N02-DUPLICATE-WRITE`, `B010-N03-WRONG-BYTES-ADOPT`, `B010-N04-UNBOUNDED-OPEN` |
| 11 | `FX-R007-B011-WRAPPER-TOTALITY` | `R007-B011` | normal/recovery/revocation/expiry/crash/timeout schedules end in one terminal and one seal | `B011-N01-LEASE-CRASH-GAP`, `B011-N02-LATE-REVOKE-WINS`, `B011-N03-EXPIRY-HANG`, `B011-N04-TIMEOUT-NONSEAL`, `B011-N05-FIFTH-OUTBOX-STATE`, `B011-N06-MISSING-PENDING-TO-FAILED`, `B011-N07-CALLER-SELECTED-RECOVERY-TIMER`, `B011-N08-ADVERSE-U-TO-O`, `B011-N09-EARLY-TIMEOUT-UNPROVEN-ABSENCE`, `B011-N10-ADOPTION-MISSING-STORED-OUTBOX` |
| 12 | `FX-R007-B012-GT-EO-TRUTH` | `R007-B012` | every §5.1 row reproduces its materialized and new-write vectors | `B012-N01-CAS-ABSENT-WRONG-ET`, `B012-N02-RECOVERY15-WRONG-ET`, `B012-N03-NORMAL15-WRONG-ET`, `B012-N04-ADOPTION-NEW-WRITE`, `B012-N05-WRONG-CUT-ID-ENCODING`, `B012-N06-WRONG-CUT-DIGEST-DOMAIN` |
| 13 | `FX-R007-B013-EXPANSION-LINEAGE` | `R007-B013` | consume receipt and obligation each have SHA/Physical inbound; completion edges exactly 15 | `B013-N01-ACTIVATION-SUBSTITUTE`, `B013-N02-MISSING-CONSUME`, `B013-N03-MISSING-PHYSICAL`, `B013-N04-WRONG-OBLIGATION` |
| 14 | `FX-R007-B014-POSTG7-AUTHORITY` | `R007-B014` | success branch scope/materialization is exact pair `2`; every failure branch `0` | `B014-N01-ALLOWLIST-ONLY`, `B014-N02-ONE-ROLE`, `B014-N03-WRONG-PUBLISHER`, `B014-N04-PHYSICAL-DRIFT` |
| 15 | `FX-R007-B015-B04-CONSTRUCTIBILITY` | `R007-B015` | H1 `6/23`; application inbound `52`; ordinary total `69`; self/future refs zero | `B015-N01-MISSING-M03`, `B015-N02-TRIPLE-MEMBER-MISSING`, `B015-N03-DUPLICATE-PAIR`, `B015-N04-FUTURE-SHA`, `B015-N05-UNDEFINED-APPISS-ENDPOINT`, `B015-N06-CROSS-BRANCH-SUBJECT`, `B015-N07-RECOVERY-SUFFIX-PUBLISHES-APPLICATION` |
| 16 | `FX-R007-B016-B06-SEMANTIC` | `R007-B016` | ten roles and 26 literal direct edges; all five payload semantics verify | `B016-N01-SCHEMA-ALIAS`, `B016-N02-WRONG-DOMAIN`, `B016-N03-MISSING-SOURCE`, `B016-N04-EDGE-COUNT-ONLY`, `B016-N05-TARGET-PERMUTATION`, `B016-N06-ROW-OMISSION-FORGED-COUNT`, `B016-N07-DIGEST-CONSTRUCTOR-SUBSTITUTION` |
| 17 | `FX-R007-B017-H2-EXACT6-AUTHORITY` | `R007-B017` | file/batch/guard/project counts `50/25/25/75`; command vector `[1,1,1,1,1,2]` | `B017-N01-PARTIAL-RESULT-BATCH`, `B017-N02-MISSING-PRIOR-SETTLEMENT`, `B017-N03-STALE-TOKEN`, `B017-N04-GUARD-RECURSION`, `B017-N05-HARDCODED-SEED` |
| 18 | `FX-R007-B018-H4-RUN-AUTHORITY` | `R007-B018` | scope `8/2/0/10`; run `9/11/D_i/(20+D_i)`; FSM includes `RESULT_RECORDED` | `H4-N01`, `H4-N02`, `H4-N03`, `H4-N04`, `H4-N05`, `H4-N06`, `H4-N07`, `H4-N08`, `H4-N09`, `H4-N10`, `H4-N11`, `H4-N12`, `H4-N13` |
| 19 | `FX-R007-B019-H4-EXACT5` | `R007-B019` | ordered Gate five, triples `5/5/5`, closure 1, multiplicity/edge/target `[1,1,1,2,2]/7/6` | `H4-N14`, `H4-N15`, `H4-N16` |
| 20 | `FX-R007-B020-SEAL-LAST-WRITE` | `R007-B020` | terminal CAS queues seal; seal is only later write under that attempt root; post-seal writes under the sealed root are zero | `B020-N01-DATA-AFTER-TERMINAL`, `B020-N02-SEAL-NOT-QUEUED`, `B020-N03-SELF-SIGNATURE`, `B020-N04-WRITE-UNDER-SEALED-ROOT` |
| 21 | `FX-R007-M001-PLANE-BINDING` | `R007-M001` | full read/control/data rows, counts, digests, intersections and union byte-equal across phase | `M001-N01-DIGEST-ONLY`, `M001-N02-MISSING-ROW`, `M001-N03-INTERSECTION`, `M001-N04-PHYSICAL-DRIFT` |
| 22 | `FX-R007-M002-CONSTRUCTOR-GOLDEN` | `R007-M002` | exact domain/tag/types/order/JCS generate the frozen namespace and attempt IDs | `M002-N01-UNTAGGED-NA`, `M002-N02-NUMERIC-AS-STRING`, `M002-N03-UPPERCASE-HEX`, `M002-N04-NONJCS`, `M002-N05-PATH-NORMALIZED` |
| 23 | `FX-R007-M003-M02-TRIPLE` | `R007-M003` | roles/local edges/member edges `19/24/3`; triple member order exact; U1 is exact directory | `M003-N01-WRAPPER-AS-MEMBER`, `M003-N02-MISSING-PROGRESS`, `M003-N03-EDGE-NAMESPACE-DRIFT`, `M003-N04-U1-FILE` |
| 24 | `FX-R007-M004-G3-TIMING` | `R007-M004` | current expansion only and four predicates subset/cardinality/order/digest PASS | `M004-N01-FUTURE-READ`, `M004-N02-FULL-EQUALITY`, `M004-N03-WRONG-BRANCH`, `M004-N04-EACH-PREDICATE-FAIL` |

Each negative bundle stores every ID shown as an individual ordered row. Each fixture result
contains both expected and recomputed counts and ordered-set digests. A fixture
cannot be shared by two canonical findings, and a canonical finding cannot
have a second fixture row.

## 15. stable self-audit predicates

§14 fixture와 stable predicate ID를 직접 결속한다. This is the required
24-row canonical→source→fixture→predicate traceability authority.

| ord | canonical ID | exact source finding refs | fixture ID | predicate ID |
|---:|---|---|---|---|
| 1 | `R007-B001` | `R006-SK-BLOCKING-001` | `FX-R007-B001-G0-DIGEST-SPLIT` | `SA-R007-B001-01` |
| 2 | `R007-B002` | `R006-SK-BLOCKING-002` | `FX-R007-B002-NONEXECUTION-SEAL` | `SA-R007-B002-01` |
| 3 | `R007-B003` | `R006-SK-BLOCKING-003` | `FX-R007-B003-AGGREGATE-ACTIVATION` | `SA-R007-B003-01` |
| 4 | `R007-B004` | `R006-SK-BLOCKING-004` | `FX-R007-B004-DEADLINE-BOUNDARY` | `SA-R007-B004-01` |
| 5 | `R007-B005` | `R006-FORMAL-BLOCKING-001` | `FX-R007-B005-FINALIZATION-GRANT` | `SA-R007-B005-01` |
| 6 | `R007-B006` | `R006-FORMAL-BLOCKING-002` | `FX-R007-B006-WRAPPER-GRANT` | `SA-R007-B006-01` |
| 7 | `R007-B007` | `R006-FORMAL-BLOCKING-004`; `R006-SK-BLOCKING-005` | `FX-R007-B007-CRASH-SOURCE` | `SA-R007-B007-01` |
| 8 | `R007-B008` | `R006-SK-BLOCKING-006` | `FX-R007-B008-NONWRAPPER-PREREVOKE` | `SA-R007-B008-01` |
| 9 | `R007-B009` | `R006-SK-BLOCKING-007` | `FX-R007-B009-WRAPPER-PRECLOSE` | `SA-R007-B009-01` |
| 10 | `R007-B010` | `R006-SK-BLOCKING-008` | `FX-R007-B010-PRECLOSE-CRASH` | `SA-R007-B010-01` |
| 11 | `R007-B011` | `R006-FORMAL-BLOCKING-005`; `R006-SK-BLOCKING-009` | `FX-R007-B011-WRAPPER-TOTALITY` | `SA-R007-B011-01` |
| 12 | `R007-B012` | `R006-FORMAL-BLOCKING-006`; `R006-SK-BLOCKING-010` | `FX-R007-B012-GT-EO-TRUTH` | `SA-R007-B012-01` |
| 13 | `R007-B013` | `R006-SK-BLOCKING-011` | `FX-R007-B013-EXPANSION-LINEAGE` | `SA-R007-B013-01` |
| 14 | `R007-B014` | `R006-FORMAL-BLOCKING-003`; `R006-SK-BLOCKING-012` | `FX-R007-B014-POSTG7-AUTHORITY` | `SA-R007-B014-01` |
| 15 | `R007-B015` | `R006-SK-BLOCKING-013` | `FX-R007-B015-B04-CONSTRUCTIBILITY` | `SA-R007-B015-01` |
| 16 | `R007-B016` | `R006-SK-BLOCKING-014` | `FX-R007-B016-B06-SEMANTIC` | `SA-R007-B016-01` |
| 17 | `R007-B017` | `R006-SK-BLOCKING-015` | `FX-R007-B017-H2-EXACT6-AUTHORITY` | `SA-R007-B017-01` |
| 18 | `R007-B018` | `R006-SK-BLOCKING-016` | `FX-R007-B018-H4-RUN-AUTHORITY` | `SA-R007-B018-01` |
| 19 | `R007-B019` | `R006-SK-BLOCKING-017` | `FX-R007-B019-H4-EXACT5` | `SA-R007-B019-01` |
| 20 | `R007-B020` | `R006-SK-BLOCKING-018` | `FX-R007-B020-SEAL-LAST-WRITE` | `SA-R007-B020-01` |
| 21 | `R007-M001` | `R006-SK-MAJOR-001` | `FX-R007-M001-PLANE-BINDING` | `SA-R007-M001-01` |
| 22 | `R007-M002` | `R006-SK-MAJOR-002` | `FX-R007-M002-CONSTRUCTOR-GOLDEN` | `SA-R007-M002-01` |
| 23 | `R007-M003` | `R006-SK-MAJOR-003` | `FX-R007-M003-M02-TRIPLE` | `SA-R007-M003-01` |
| 24 | `R007-M004` | `R006-SK-MAJOR-004` | `FX-R007-M004-G3-TIMING` | `SA-R007-M004-01` |

The output/closure continuation of those same 24 rows is:

| canonical ID | predicate output literal path template | FindingCompletion row ID | closure batch |
|---|---|---|---|
| `R007-B001` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b001-01.result.json` | `FC-R007-B001` | `C1` |
| `R007-B002` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b002-01.result.json` | `FC-R007-B002` | `C1` |
| `R007-B003` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b003-01.result.json` | `FC-R007-B003` | `C1` |
| `R007-B004` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b004-01.result.json` | `FC-R007-B004` | `C1` |
| `R007-B005` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b005-01.result.json` | `FC-R007-B005` | `C1` |
| `R007-B006` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b006-01.result.json` | `FC-R007-B006` | `C1` |
| `R007-B007` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b007-01.result.json` | `FC-R007-B007` | `C1` |
| `R007-B008` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b008-01.result.json` | `FC-R007-B008` | `C1` |
| `R007-B009` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b009-01.result.json` | `FC-R007-B009` | `C1` |
| `R007-B010` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b010-01.result.json` | `FC-R007-B010` | `C1` |
| `R007-B011` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b011-01.result.json` | `FC-R007-B011` | `C1` |
| `R007-B012` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b012-01.result.json` | `FC-R007-B012` | `C2` |
| `R007-B013` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b013-01.result.json` | `FC-R007-B013` | `C2` |
| `R007-B014` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b014-01.result.json` | `FC-R007-B014` | `C2` |
| `R007-B015` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b015-01.result.json` | `FC-R007-B015` | `C3` |
| `R007-B016` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b016-01.result.json` | `FC-R007-B016` | `C3` |
| `R007-B017` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b017-01.result.json` | `FC-R007-B017` | `C3` |
| `R007-B018` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b018-01.result.json` | `FC-R007-B018` | `C3` |
| `R007-B019` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b019-01.result.json` | `FC-R007-B019` | `C3` |
| `R007-B020` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-b020-01.result.json` | `FC-R007-B020` | `C3` |
| `R007-M001` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-m001-01.result.json` | `FC-R007-M001` | `C4` |
| `R007-M002` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-m002-01.result.json` | `FC-R007-M002` | `C4` |
| `R007-M003` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-m003-01.result.json` | `FC-R007-M003` | `C4` |
| `R007-M004` | `H1_ROOT_TEMPLATE/successor/validation/sa-r007-m004-01.result.json` | `FC-R007-M004` | `C4` |

Each output is schema `SELF_AUDIT_PREDICATE_RESULT_V2`, published by the
independent predicate runner with its concrete publisher Physical and output
`FilePhysicalV2`. Joining both tables on unique canonical ID must be bijective
forward and reverse: source refs→canonical→fixture→predicate→output→completion
row→closure batch, with missing/extra/duplicate count zero.

`SelfAuditPredicateResultV2` is this closed signed envelope:

```text
{
  "body":{
    "schema":"SELF_AUDIT_PREDICATE_RESULT_V2",
    "canonical_finding_id":Identifier,
    "fixture_id":Identifier,
    "predicate_id":Identifier,
    "fixture_execution_receipt_sha":Sha256Hex,
    "fixture_execution_receipt_physical":FilePhysicalV2,
    "implementation_commit_sha":Sha256Hex,
    "role_registry_digest":Sha256Hex,
    "node_registry_digest":Sha256Hex,
    "edge_registry_digest":Sha256Hex,
    "branch_cardinality_registry_digest":Sha256Hex,
    "authority_scope_registry_digest":Sha256Hex,
    "fixture_registry_digest":Sha256Hex,
    "ordered_assertion_results":[{
      "ordinal":Ordinal,
      "assertion_id":Identifier,
      "expected_value_jcs":JSONValue,
      "recomputed_actual_value_jcs":JSONValue,
      "recomputation_digest":Sha256Hex,
      "status":"PASS"|"FAIL"
    }],
    "assertion_count":Count,
    "assertion_digest":Sha256Hex,
    "result_status":"PASS"|"FAIL",
    "completed_at":UtcNano,
    "publisher_actor_id":Identifier,
    "publisher_physical_sha":Sha256Hex,
    "signature_domain":"WS-WALKSAFE-R007-SELF-AUDIT-PREDICATE-RESULT-V2"
  },
  "signature":{
    "algorithm":Identifier,
    "signer_actor_id":Identifier,
    "signer_physical_sha":Sha256Hex,
    "signature_domain":
      "WS-WALKSAFE-R007-SELF-AUDIT-PREDICATE-RESULT-V2",
    "signature":SignatureBytes
  }
}
```

`JSONValue` here is exactly RFC 8785 JSON null, boolean, finite
I-JSON-compatible number, string, array or object; duplicate keys and
non-finite numbers are invalid. Signature input is the literal domain, NUL and
body JCS. The result's own SHA/`FilePhysicalV2` exists only after publication
and is bound by the later FindingCompletion row.

Each `SA-R007-*` implementation reads the exact `FixtureRegistryRowV2`, reruns
the named positive and every named negative case, recomputes all registry
filters/digests and emits one signed predicate result. It may not parse this
prose table as the test oracle.

The global self-audit emits the following stable assertions:

```text
SA-R007-GLOBAL-001 source identities exact = PASS
SA-R007-GLOBAL-002 historical source byte mutation count = 0
SA-R007-GLOBAL-003 official progress delta = 0
SA-R007-GLOBAL-004 authority granted by plan = 0

SA-R007-GLOBAL-005 canonical findings BLOCKING/MAJOR = 20/4
SA-R007-GLOBAL-006 canonical IDs unique = 24
SA-R007-GLOBAL-007 source finding refs covered = 28/28
SA-R007-GLOBAL-008 canonical→source→fixture→predicate→output→completion bijection = PASS
SA-R007-GLOBAL-009 R001 review headings covered = 49/49
SA-R007-GLOBAL-009A dual-source overlap clusters exact = 4
SA-R007-GLOBAL-009B R001 ledger-review correction headings covered = 20/20

SA-R007-GLOBAL-010 committed lifecycle genesis/seal-obligation = 1/1
SA-R007-GLOBAL-011 nonexecution variants/data writes/seal obligations = 3/0/3
SA-R007-GLOBAL-012 seal schema variants = 4
SA-R007-GLOBAL-013 ALLOW activation receipts/role rows = 1/4
SA-R007-GLOBAL-014 nonexecution authority rows = 0
SA-R007-GLOBAL-015 legacy role keys accepted = 0

SA-R007-GLOBAL-016 constructor domain/tag/type/order/encoding = PASS
SA-R007-GLOBAL-017 constructor alternate encoding accepted = 0
SA-R007-GLOBAL-018 request/response/decision/activation context equality = PASS
SA-R007-GLOBAL-019 grant scope/head/token/nonce/terminal-recovery-deadline equality = PASS

SA-R007-GLOBAL-020 role/aggregate-outbox/lifecycle-outbox event-state products covered = 100 percent
SA-R007-GLOBAL-021 terminal selector cardinality = 1
SA-R007-GLOBAL-022 cause priority = REVOCATION<EXPIRY<CRASH<NORMAL
SA-R007-GLOBAL-023 accepted consume obligation creation = 1
SA-R007-GLOBAL-024 same-outbox replay extra output = 0
SA-R007-GLOBAL-025 unresolved role at seal = 0
SA-R007-GLOBAL-026 post-seal project writes under sealed attempt root = 0

SA-R007-GLOBAL-027 CRASH source pair selected cardinality = 0 or 2 files
SA-R007-GLOBAL-028 selected valid CRASH pair cardinality = 2
SA-R007-GLOBAL-029 ET003/ET004 after valid CRASH = 1/1
SA-R007-GLOBAL-030 frozen first-claim/recovery/seal trusted CAS comparators = PASS

SA-R007-GLOBAL-031 GT/EO/ET frozen cut registry = PASS
SA-R007-GLOBAL-032 finalization-consume→expansion direct edge = 1
SA-R007-GLOBAL-033 expansion→completion direct edges = 15
SA-R007-GLOBAL-034 PostG7 success/failure scope = 2/0
SA-R007-GLOBAL-035 G3 predicate vector = [PASS,PASS,PASS,PASS]

SA-R007-GLOBAL-036 B06 roles/edges and four independent target digests = 10/26/PASS
SA-R007-GLOBAL-037 B04 H1 roles/edges = 6/23
SA-R007-GLOBAL-038 application inbound/ordinary total and branch truth = 52/69/PASS
SA-R007-GLOBAL-039 H2 file/batch/guard/output = 50/25/25/75
SA-R007-GLOBAL-040 H2 hard-coded final graph seed accepted = 0
SA-R007-GLOBAL-041 M02 roles/local edges/member edges = 19/24/3
SA-R007-GLOBAL-042 M02 U1 physical type = DIRECTORY

SA-R007-GLOBAL-043 H4 scope plane = 8/2/0/10
SA-R007-GLOBAL-044 H4 run plane = 9/11/D_i/(20+D_i)
SA-R007-GLOBAL-045 H4 FSM contains RESULT_RECORDED and result-before-close = PASS
SA-R007-GLOBAL-046 H4 Gate raw/result/receipt/closure = 5/5/5/1
SA-R007-GLOBAL-047 H4 multiplicity/edges/targets = [1,1,1,2,2]/7/6
SA-R007-GLOBAL-048 H4 negatives exercised = 16/16

SA-R007-GLOBAL-049 undefined role endpoint/path/schema/publisher/Physical = 0
SA-R007-GLOBAL-050 undefined/conceptual/duplicate edge tuple = 0
SA-R007-GLOBAL-051 unexpanded runtime token/selector/D_i = 0
SA-R007-GLOBAL-052 prose/generated cardinality mismatch = 0
SA-R007-GLOBAL-053 self/future content reference = 0
SA-R007-GLOBAL-054 registry producer/checker Physical equality = false
SA-R007-GLOBAL-055 finding completion cardinality per canonical ID = 1
SA-R007-GLOBAL-056 premature completion rows before predecessor batch = 0
SA-R007-GLOBAL-057 closure dependency DAG cycle count = 0
```

For assertion 27, an absent/nonselected CRASH source has zero project files;
a selected CRASH source has the exact payload/signature pair. A singleton
value is always failure. For assertion 44 the instance substitutes an exact
integer for `D_i` before evaluation.

## 16. mechanical validation

UTF-8/LF, table, fence, path, count, digest와 source coverage를 검사한다.
Future R007 must ship the validator output as a separate signed artifact; it
must not place its own SHA inside its payload.

| check ID | mechanical check | expected |
|---|---|---|
| `MV001` | decode every controlled Markdown/JSON as strict UTF-8 | success |
| `MV002` | count CR and NUL bytes | `0/0` |
| `MV003` | verify every controlled text file ends in one LF | PASS |
| `MV004` | parse CommonMark fenced blocks and require matched opening/closing fences | unmatched `0` |
| `MV005` | parse every Markdown table and compare pipe-cell count per row after escaped-pipe handling | malformed `0` |
| `MV006` | recompute the seven §1 source SHA/byte/line triples | exact match |
| `MV007` | recompute canonical/source coverage from §3.1 | canonical `24`, source refs `28`, unmapped `0` |
| `MV008` | recompute R001-review coverage from §3.2 | headings `49`, mapped `49`, duplicate `0` |
| `MV009` | validate every closed JSON schema with unknown-field rejection | invalid `0` |
| `MV010` | validate all static path tokens against the exact nine-token set | invalid token `0` |
| `MV011` | validate runtime paths have no token, wildcard, ellipsis, `.`/`..`, backslash or symlink | invalid `0` |
| `MV012` | recompute registry row orders, counts and domain-separated digests | all equal |
| `MV013` | check role/node/edge IDs, conceptual endpoints, resolved tuples and both outbox state/event tables for missing/duplicate/gap/unknown enum | all `0` |
| `MV014` | run all 24 positive fixture cases | PASS `24/24` |
| `MV015` | run every literal negative case and require rejection | PASS all |
| `MV016` | recompute all 59 global self-audit assertions | PASS `59/59` |
| `MV017` | scan payload dependency graph for SCC/self/future content references | `0/0/0` |
| `MV018` | independently regenerate fixed cardinalities from registry filters | §11 exact |
| `MV019` | independently derive H2 final node/edge counts without a numeric seed | producer/checker equality |
| `MV020` | verify producer/checker executable SHA and Physical differ | PASS |
| `MV021` | compare with the R007-authoring start snapshot: protected/historical bytes unchanged and delta limited to authorized R007/review/validator outputs | PASS |
| `MV022` | verify R002 ledger, Stage-C and authority reviews target the same frozen R002 SHA and each report `0B/0M/0m` | PASS before authoring entry |
| `MV023` | verify R007 formal and skeptical roadmap reviews target the same frozen R007 SHA and each report `0B/0M/0m` | PASS before R007 promotion |

Line counting is bytes split on LF after `MV003`; byte count is raw file
length. Markdown-table validation ignores pipes inside code fences and handles
escaped `\|`. Fence validation recognizes both backtick and tilde fences and
their delimiter lengths.

The predecessor disposition oracle is:

| artifact | frozen disposition | review findings |
|---|---|---|
| R006 roadmap | `REJECTED_HISTORY` | formal `6B/0M/0m`; skeptical `18B/4M/0m` |
| R001 plan | `REJECTED_HISTORY` | ledger `12B/8M/0m`; Stage-C `6B/4M/0m`; authority `16B/3M/0m` |
| R002 plan | `PRE_REVIEW`, `NONE` | not an execution artifact; official delta `0` |

The R001 immutable checksum guard is:

```text
bcf67b07c5d2a0454b6a2af325964e62d55f39e04c8c88684b2006738f027944
```

It is checked before and after authoring. R002's own SHA/bytes/lines are
reported outside this file after final bytes are frozen, avoiding a
self-hash.

## 17. acceptance and author instructions

This R002 plan is acceptable only when:

1. seven source identities and all predecessor dispositions equal §1/§16;
2. the 24 canonical and 18 R002 correction clusters cover all source headings;
3. lifecycle, aggregate authority, contexts, FSM, outbox and seal form one
   constructible predecessor-only system;
4. every literal role/edge/schema/publisher/Physical requirement is either
   enumerated here or deterministically expanded by the closed registry;
5. fixed counts regenerate to §11 and generated totals have no seed;
6. all 24 fixtures and 59 global predicates are specified and mechanically
   addressable;
7. this document changes no historical source, canonical state, product,
   checkpoint, daylog or memory.

Those seven design checks do not themselves open authoring. The frozen R002
bytes must first receive ledger, Stage-C and authority reviews that all target
that one R002 SHA and independently report `0B/0M/0m`. Only then may R007
authoring begin.

Future R007 author instructions:

1. Preserve R006, R001 and all five review bytes. Add a new R007; never repair
   rejected history in place.
2. Implement all seven preparations, but use no canonical `Closes:` until the exact
   `C1→C2→C3→C4` sequence.
3. Generate schemas and registry rows first; do not replace closed fields with
   vague collective nouns, directory-relative shorthand, ID ranges or a broad
   untyped map.
4. Expand all static templates to runtime literal paths before activation.
5. Keep lifecycle key and ALLOW aggregate key disjoint; never create four
   legacy per-role authority keys.
6. Use only predecessor content identities in CAS core/obligation/outbox.
   Publish signed receipts after commit.
7. Make role state and outbox batch state separate; a settled work output may
   leave its role `CONSUMED_OPEN` until the role terminal transition.
8. A successful terminal CAS must queue `ATTEMPT_SEAL`; only the later seal
   worker may perform the final control-plane project write.
9. Treat stored wrapper adoption as zero new functional writes, preserve its
   stored normal/recovery selector profile, and still queue the one seal
   outbox required by `FSM027`.
10. Recompute B06 `10/26`, B04 `6/23`, application `52/69`, H2 `50/25/75`,
    M02 `19/24`, H4 scope/run/Gate values from rows after every change.
11. Run positive and negative fixtures, then two independent producers/checkers
    for graph/digest equality.
12. Freeze exact R007 bytes once. Both formal and skeptical roadmap reviews must name
    that same SHA and independently report `0B/0M/0m`.
13. If either review is nonzero or targets different bytes, retain that R007
    as `REJECTED_HISTORY` and create another add-only successor.
14. Until both same-SHA zero-finding reviews exist, R007 remains
    `PRE_REVIEW`, grants no execution authority and changes official progress
    by zero.

## 18. 최종 non-authority statement

```text
this document = implementation plan only
this document status = PRE_REVIEW
this document grants authority = false
R006 roadmap remains rejected history = true
R001 plan remains rejected history = true
R006/R001/five-review bytes may be mutated = false
official progress delta = 0
execution allowed now = false
PRE-P/H2/H4/product/checkpoint/canonical mutation allowed now = false
```
