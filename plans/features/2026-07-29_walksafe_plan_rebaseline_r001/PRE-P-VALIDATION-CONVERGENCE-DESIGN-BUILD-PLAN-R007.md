# PRE-P Validation Convergence Design/Build Plan R007

## 1. standalone boundary, immutable provenance와 official ceiling

| 항목 | exact 값 |
|---|---|
| `document_id` | `WS-PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-20260731-R007` |
| `status` | `DEFERRED_NON_EFFECTIVE_DRAFT` |
| 작성일 | `2026-07-31` |
| R006 plan | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006.md` |
| R006 SHA / bytes / lines | `4a9f7f21d505bf6cf53d1ea8a16e21e7ebca5c154541d49928d03383b7d23de9` / `91165` / `1972` |
| R006 formal review | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006-independent-review-r001.md` |
| formal SHA / bytes / lines | `08dad026cd498bff36b10db51809342ed62fc7c78aa9c8b10ef5f83d38f5a520` / `32052` / `647` |
| formal verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY; BLOCKING=12 / MAJOR=5 / MINOR=1` |
| R006 skeptical review | `PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006-independent-skeptical-review-r001.md` |
| skeptical SHA / bytes / lines | `d8e4b0f710e15c6ebab64bbf17ee4bbb60e7a88d65b5c30b7e6832e95229b3af` / `28928` / `639` |
| skeptical verdict | `REJECTED_NON_EFFECTIVE_PLAN_ONLY; BLOCKING=8 / MAJOR=2 / MINOR=1` |
| R006 and earlier authority | `PROVENANCE_ONLY; SUPERSEDED_NON_NORMATIVE` |
| P17 | `P17_BUILD_DEFERRED` |

R007은 현재 accepted/normative/executable plan이 아니다. 이 파일은 아래
미결 사항과 중간 설계를 보존하는 deferred review draft일 뿐이다. R006, 두
R006 reviews와 이전 revision은 immutable finding provenance로만 읽는다.
후속 revision이 §24 미결을 폐쇄하고 새 독립 감사를 통과하기 전에는 이
문서의 어떤 operation, path, token 또는 문장도 authority를 만들지 않는다.

```text
artifact_closed_equivalent=126/257
artifact_open=131/257
artifact_completion_credit_delta=0
formal_pass=0/279
formal_not_run=279/279
formal_test_credit_delta=0
actual_device_event=0/0
actual_event_credit_delta=0
gate_pass=0/5
gate_not_run=5/5
remaining_gates_waived=false
production_deployment=0
release_status=NOT_ELIGIBLE
approval_credit_delta=0
canonical_gap_backlog=r021/r021
canonical_delta=0
product_credit_delta=0
```

future discovery132, full19, exact6, seq40, v2.4.1, exact26, application receipt는
planned only다. 어떤 결과도 위 artifact/formal/device/event/gate/deployment/
release/canonical/product credit를 자동으로 바꾸지 않는다.

```text
STATUS=DEFERRED_NON_EFFECTIVE_DRAFT
DEFERRED_FREEZE_STATE=DEFERRED_FREEZE_FOR_REVIEW
DOCUMENT_ACCEPTED=false
DOCUMENT_EXECUTABLE=false
CURRENT_AUTHORITY=ABSENT_DENY_ALL
STAGE_A_AUTHORITY=ABSENT_DENY_ALL
STAGE_B_AUTHORITY=ABSENT_DENY_ALL
STAGE_C_AUTHORITY=ABSENT_DENY_ALL
STAGE_D_AUTHORITY=ABSENT_DENY_ALL
STAGE_E_AUTHORITY=ABSENT_DENY_ALL
STAGE_F_AUTHORITY=ABSENT_DENY_ALL
STAGE_G_AUTHORITY=ABSENT_DENY_ALL
JOURNAL_BOOTSTRAP_AUTHORITY=ABSENT_DENY_ALL
ACTIVE_WRITE_ALLOWED=false
AUTHORITY_JOURNAL_WRITE_ALLOWED=false
STAGE_A_REQUEST_ALLOWED=false
```

## 2. exact current baseline

| 기준 | exact 값 |
|---|---|
| package | `WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4`, `ACTIVE` |
| checkpoint | `docs/control/walksafe-project-continuation-checkpoint.json` |
| checkpoint SHA / bytes / schema | `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c` / `1329415` / `1.25.0` |
| checkpoint dev/inode/uid/gid/mode/nlink | `66306/16943740/1000/1000/0644/1` |
| transition tail | seq `39`, `c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a` |
| managed source | count `603`, path `e445b7ccd8b76ef476248894e3d2f84eba5d2b3ba90e2be198b07c37e2d767b1`, content `69464310c396918802901d874165474b22edc879e98ff42abc6f558f24d7230a` |
| discovery | discovered `132`, assigned `127`, orphan `5` |
| regression lineage | A `450 PASS / 7 FAIL`; B `241 intended / NOT_RUN` |

two active CAS target identity:

| path | SHA-256 | bytes | dev/inode | uid:gid | mode | nlink |
|---|---|---:|---|---|---:|---:|
| `tests/requirements.lock` | `abd690bacf91a65907e6b8357252ead9082181e9ed1b4db98987b2685e51b9fe` | 20288 | `66306/16653542` | `1000:1000` | 0664 | 4 |
| `scripts/run_walksafe_test_layers_20260711.sh` | `4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d` | 15588 | `66306/16647409` | `1000:1000` | 0775 | 1 |

Stage A/B source snapshot과 Stage C pre-guard가 이 exact identity를 재검증한다.
CAS after는 fresh regular inode/nlink1이고 source hardlink group을 재사용하지
않는다.

## 3. authority stages, roots와 attempt grammar

| stage | exact token | scope |
|---|---|---|
| bootstrap | `JOURNAL_BOOTSTRAP_ONLY` | complete R007 authority root one-time publication |
| A | `PRE_P_ATTEMPT_SCOPED_CANDIDATE_ENV_PACK_BUILD_ONLY` | candidate/env/pack build and A lifecycle publication |
| B | `PRE_P_ATTEMPT_SCOPED_RESOLVE_VALIDATE_ONLY` | sibling resolved subject, two-env validation, B lifecycle |
| C | `PRE_P_EXACT26_FENCED_APPLY_EXACT6_RECEIPT_ONLY` | exact26, U1, transaction, exact6, receipt |
| D | `POST_SEQ40_R008_SUCCESSOR_DESIGN_REVIEW_ONLY` | committed seq40 기반 R008 successor |
| E | `P_CANDIDATE_BUILD_REVIEW_ONLY` | P seq40→41 candidate |
| F | `P_ATTEMPT_SCOPED_RESOLUTION_ONLY` | P resolution |
| G | `P_EXACT_RESOLVED_ATOMIC_APPLY_ONLY` | P apply |

D–G는 모두 `NON_OPERATIVE_FUTURE_LABEL_ONLY`이다. 이 문자열이나 A–C의
설계상 token을 제시해도 현재 grant/receipt/authority는 0이다.

exact roots:

```text
candidate base =
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r007/
resolved base =
  plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-authority-resolved-r007/
external validation base =
  /home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-validation-r007/
authority root =
  /home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-authority-r007/
authority journal =
  /home/ddobagi/.local/share/hanium-dreamup/
  walksafe-pre-p-authority-r007/journal/
```

```text
attempt-key = <12 decimal digits>-<52 lowercase base32 challenge digest>
transaction-id = <64 lowercase hex>
bootstrap-nonce = <64 lowercase hex>
```

fixed `-001`, root/review/raw reuse와 symlink alias는 금지한다. 각 retry는 fresh
sequence/challenge sibling이다. stage별 max attempts=16, max renewals=32,
first-attempt hard deadlines A/B/C/recovery=`21600/3600/7200/7200`초이고 retry가
deadline을 재시작하지 않는다.

## 4. whole-root bootstrap and PUBLISH_ONCE_AND_ADOPT

### 4.1 bootstrap state machine

authority root는 current absent다. 별도 user-approved bootstrap issuer만
`JOURNAL_BOOTSTRAP_ONLY` capability로 같은 filesystem/same parent에:

```text
/home/ddobagi/.local/share/hanium-dreamup/
  .walksafe-pre-p-authority-r007.bootstrap.<bootstrap-nonce>/
    journal/
      genesis/authority-journal-genesis.json
      genesis/authority-journal-global.lock
      records/
      preissuance/
      attempts/stage-a/
      attempts/stage-b/
      attempts/stage-c/
      attempts/recovery-stage-c/
      reviews/stage-a/
      reviews/stage-b/
      transactions/
    bootstrap-environment/
      rootfs/usr/
      rootfs/lib/
      rootfs/lib64/
      rootfs/bin/
      rootfs/etc/
      env/
      logical-content-manifest.json
      physical-materialization-receipt.json
      source-provenance.json
```

를 완전 생성한다. signer private key는 이 tree, repository, receipt 어디에도
없고 genesis는 verifier fingerprint만 가진다.

fixed directory table:

| logical path | uid:gid | final mode | type/nlink |
|---|---|---:|---|
| authority root | 1000:1000 | 0700 | mutable container; nlink is not signed |
| `journal` | 1000:1000 | 0700 | directory |
| `journal/genesis` | 1000:1000 | 0500 | sealed directory |
| `journal/records`, `journal/preissuance` | 1000:1000 | 0700 | mutable containers |
| `journal/attempts` and four stage children | 1000:1000 | 0700 | mutable containers |
| `journal/reviews` and two stage children | 1000:1000 | 0700 | mutable containers |
| `journal/transactions` | 1000:1000 | 0700 | mutable container |
| `bootstrap-environment` | 1000:1000 | 0500 | complete sealed directory |
| lock | 1000:1000 | 0600 | regular, nlink1, size0 |
| genesis | 1000:1000 | 0444 | regular, nlink1 |

builder opens the trusted share parent with `O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW`,
uses anchored `openat2(RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS|
RESOLVE_NO_MAGICLINKS)`, `mkdirat` NOREPLACE, lstat/open/fstat equality and no
unexpected child. Lock is created/fchmod0600/fsync first. Genesis then signs stable
final logical paths plus actual physical tuples for authority root, journal,
genesis parent, records, attempts, reviews, transactions, lock:

```text
DirPhysical={path,dev,inode,mnt_id,uid,gid,mode,nlink}
MutableContainerPhysical={path,dev,inode,mnt_id,uid,gid,mode,type:"DIRECTORY"}
FilePhysical={path,sha256,bytes,dev,inode,mnt_id,uid,gid,mode,nlink}
LockPhysical={path,dev,inode,mnt_id,uid,gid,mode0600,nlink1,size0}
```

`mnt_id` is the Linux `statx(STATX_MNT_ID)` value observed through the anchored
fd, not a pathname-derived label. Directory rename preserves those inodes and
mount identities. Genesis uses `MutableContainerPhysical`, never `DirPhysical`,
for authority root, journal, records, preissuance, attempts, reviews and
transactions. Their `nlink` and child count necessarily change; every mutation
instead revalidates the signed stable tuple and the journal-state-authorized
single child/nlink delta under the universal lock. Immutable directories continue
to use `DirPhysical`. Genesis also binds journal ID, allowed
record kinds, signature domains, numeric-slot rule, exact OFD acquisition contract,
limits/deadlines and `first_global_sequence=000000000001`.

publication order:

```text
create children bottom-up
-> fsync every regular file
-> fsync each directory bottom-up through temp authority root
-> reopen and verify complete tree + genesis signature + stable tuples
-> renameat2(temp authority root, final authority root, RENAME_NOREPLACE)
-> fsync trusted share parent
-> reopen final root anchored and reverify exact complete tree
```

partial temp roots are nonauthority and never renamed until complete. They are not
deleted, overwritten or adopted as final. Final absent permits a fresh bootstrap
attempt. Final exact complete permits read-only adopt. Final symlink, partial,
unexpected child, wrong tuple/signature/mode is fail-stop. Concurrent bootstrap has
winner1; loser replays exact final equality and either adopts or fail-stops.

Bootstrap authority is not inferred from an absent root or from the string
`JOURNAL_BOOTSTRAP_ONLY`. Before the first syscall the issuer verifies and
consumes this signed, externally stored one-use object:

```text
BootstrapGrantPayload={
 schema_version:"WS-PRE-P-R007-BOOTSTRAP-GRANT-V1",
 grant_id,challenge_id,nonce,
 plan:FilePhysical,plan_reviews:[FilePhysical exact2],
 authority_root_literal,authority_journal_literal,
 bootstrap_executable:FilePhysical,
 bootstrap_validator:FilePhysical,
 bootstrap_validator_closure:DirManifestPhysical,
 allowed_operation:"BOOTSTRAP_COMPLETE_ROOT_NOREPLACE",
 allowed_parent:DirPhysical,expected_final_state:"ABSENT",
 issuer_identity,verifier_identity,signer_fingerprint,
 issued_at,not_before,expires_at,revocation_state:"UNREVOKED",
 one_use:true
}
BootstrapGrant={payload:BootstrapGrantPayload,signature}
signature_input =
 ASCII("WS-PRE-P-R007-BOOTSTRAP-GRANT-V1") || NUL ||
 RFC8785_JCS(BootstrapGrantPayload)
```

The grant is outside the future authority root. A grant-consumption proof is
atomically claimed in the external issuer service before temp-root creation;
genesis binds the grant `FilePhysical`, payload digest and consumption proof.
Invalid, expired, revoked or consumed grants, an unexpected final root, or an
unbound executable/validator cause persistent syscall count zero.

### 4.1.1 first builder execution environment

Bootstrap issuer's journal-only scope also permits anchored read/copy/seal of one
Python execution closure. Source seed:

```text
logical path =
  /home/ddobagi/.local/share/hanium-dreamup/
  walksafe-general-cpu-verify-20260715/bin/python
resolved path =
  /home/ddobagi/.local/share/hanium-dreamup/
  python-3.12.13+20260510/bin/python3.12
version=Python 3.12.13
sha256=f7014f68e3c8f180811740735cf1dd5c28be6cff84db11d0ced2a8cd039670a0
bytes=102286096
dev/inode/mnt_id=66306/17202983/33
uid/gid/mode/nlink=1000/1000/0775/1
```

Using anchored read-only fds, not PATH, issuer copies the authoritative
shebang/ELF/Python stdlib closure into the temp root's bootstrap environment and
supplies its synthetic original-position rootfs loader/libs. Logical and physical
schemas are §7 schemas; files are fsynced/nlink1, directories bottom-up fsynced,
and genesis binds their manifests and stable tuples before whole-root rename.

`bootstrap-environment/source-provenance.json` is produced by the
`BootstrapGrantPayload.bootstrap_validator` after those two manifests seal:

```text
BootstrapSourceProvenancePayload={
 schema_version:"WS-PRE-P-R007-BOOTSTRAP-SOURCE-PROVENANCE-V1",
 bootstrap_grant:FilePhysical,
 source_python:FilePhysical,
 source_roots:[DirManifestPhysical],
 logical_manifest:FilePhysical,
 physical_receipt:FilePhysical,
 ordered_members:[{destination_relative_path,origin:FilePhysical,
                   closure_reason,resolver:FilePhysical}],
 member_set_sha256,status:"SEALED",signer_fingerprint
}
BootstrapSourceProvenance={
 payload:BootstrapSourceProvenancePayload,signature
}
signature_input =
 ASCII("WS-PRE-P-R007-BOOTSTRAP-SOURCE-PROVENANCE-V1") || NUL ||
 RFC8785_JCS(BootstrapSourceProvenancePayload)
```

Publication order is logical manifest, physical receipt, source provenance,
then genesis; source provenance excludes itself and genesis binds all three
`FilePhysical` values.

Stage-A builders run in bwrap with this genesis-bound rootfs/env mounted as §8.
Thus `/env/bin/python` exists before runtime-pack discovery/build. Stage-A can only
read/execute it; candidate environments are separate fresh materializations.

### 4.1.2 source-seed-bundle and one-way contract bootstrap

The bootstrap environment solves executable availability only. It does not create
the Stage-A schemas, templates, inputs or builder source. Before any Stage-A
receipt is issued, the external seed producer publishes this distinct immutable
bundle under the exact challenge-bound attempt parent:

```text
journal/attempts/stage-a/<stage-a-attempt-key>/source-seed-bundle/
  payload/<the exact45 source-relative files below>
  source-seed-manifest.json
  source-seed-review.json
  source-seed-binding.json
```

`payload` contains this exact literal set:

```text
schemas/physical.schema.json
schemas/signed-absent-target.schema.json
schemas/source-input-manifest.schema.json
schemas/builder-command-manifest.schema.json
schemas/build-receipt.schema.json
schemas/equality-receipt.schema.json
schemas/normative-exact26-target-table.schema.json
schemas/candidate-target-map.schema.json
schemas/candidate-review-subject-manifest.schema.json
templates/normative-exact26-target-table.template.json
input-templates/phase-0.inputs.template.json
input-templates/lane-a.inputs.template.json
input-templates/lane-c.inputs.template.json
input-templates/control-core.inputs.template.json
input-templates/lane-d-core.inputs.template.json
input-templates/lane-b-final.inputs.template.json
input-templates/lane-d-final.inputs.template.json
input-templates/after-control.inputs.template.json
input-templates/apply.inputs.template.json
input-templates/aggregate.inputs.template.json
templates/builder-command-manifest.template.json
builders/phase-0-builder.py
builders/lane-a-lock-env-builder.py
builders/lane-c-apply-helper-builder.py
builders/control-core-builder.py
builders/lane-d-core-builder.py
builders/lane-b-final-builder.py
builders/lane-d-final-builder.py
builders/after-control-builder.py
builders/apply-envelope-builder.py
builders/aggregate-builder.py
runtime-pack/schemas/logical-content-manifest.schema.json
runtime-pack/schemas/physical-materialization-receipt.schema.json
runtime-pack/schemas/transitive-closure.schema.json
runtime-pack/schemas/member-origin-map.schema.json
runtime-pack/schemas/pairwise-distinct-receipt.schema.json
runtime-pack/builders/discover-runtime-inputs.py
runtime-pack/builders/build-runtime-pack.py
runtime-pack/templates/runtime-pack.template.json
builders/resolved-after-control-builder.py
builders/resolved-apply-builder.py
regression-final/builders/regression-final-builder.py
templates/resolved-after-control.template.json
templates/resolved-apply.template.json
regression-final/templates/regression-final.template.json
```

The physical manifest uses these exact45 entries in displayed order. Its expanded
path-set hash is `FUTURE_SEALED` until those reviewed seed
bytes exist; a present-time fake digest is forbidden. There is no candidate
output, build receipt, equality receipt, N26 actual candidate, review subject,
resolved output, regression result or runtime-pack output in the bundle.
`source-seed-manifest.json` excludes itself and its two descendants and has:

```text
SourceSeedEntry={
  ordinal,
  source_relative_path,
  destination_relative_path,
  source:FilePhysical,
  destination_uid:1000,
  destination_gid:1000,
  destination_mode,
  executable
}
SourceSeedManifest={
  schema_version:"WS-PRE-P-R007-SOURCE-SEED-V1",
  challenge_id,attempt_key,contract_id,
  plan:FilePhysical,
  plan_reviews:{formal:FilePhysical,skeptical:FilePhysical,
                formal_verdict:"0/0/0",skeptical_verdict:"0/0/0"},
  normative_exact45_path_set,semantic_contract_digest,
  schema_validation_bindings:[SchemaValidationBinding],
  ordered_entries:[SourceSeedEntry exact45],
  source_path_set_sha256,source_content_set_sha256,
  destination_path_set_sha256,
  candidate_output_count:0,
  signature
}
SchemaValidationBinding={
 schema_id,normative_plan:FilePhysical,validator:FilePhysical,owner_role
}
SourceSeedReview={
  schema_version:"WS-PRE-P-R007-SOURCE-SEED-REVIEW-V1",
  challenge_id,attempt_key,contract_id,plan:FilePhysical,plan_reviews,
  subject_manifest:FilePhysical,
  reviewed_entries:[FilePhysical exact45],
  semantic_contract_digest,
  exact45_membership:true,semantic_conformance_to_plan:true,
  blocking:0,major:0,minor:0,verdict:"PASS",
  reviewer_identity,reviewed_at,signature
}
SourceSeedBinding={
  schema_version:"WS-PRE-P-R007-SOURCE-SEED-BINDING-V1",
  challenge_id,attempt_key,contract_id,plan:FilePhysical,plan_reviews,
  manifest:FilePhysical,review:FilePhysical,
  semantic_contract_digest,
  blocking:0,major:0,minor:0,status:"SEALED",signature
}
```

For all exact45 entries,
`destination_relative_path=source_relative_path`. Exactly the fifteen paths that
end in `.py` and contain `/builders/` or start with `builders/` have
`destination_mode="0755", executable=true`; every other entry has
`destination_mode="0644", executable=false`. All have uid/gid `1000/1000`.

The exact45 schema files externalize the schemas needed by their named payload
builders. Every other strict inline schema in this plan remains normative plan
content and has a `SchemaValidationBinding`: runtime schemas are owned by the
reviewed runtime builder; full19/regression/exact6 logical schemas by
`builders/after-control-builder.py`; resolved schemas by their named Stage-B
builder; and journal/receipt/application/environment schemas by the
genesis-bound bootstrap validator or authority-supervisor executable. Every
signed node names its `schema_id` and actual validator `FilePhysical`;
publication without that binding and validation PASS is forbidden. This rule
adds no source file to exact45.

`semantic_contract_digest` is:

```text
SHA256(
 ASCII("WS-PRE-P-R007-SOURCE-SEED-SEMANTIC-V1") || NUL ||
 JCS({contract_id,plan_sha256,formal_review_sha256,skeptical_review_sha256,
      ordered_entries:[{ordinal,source_relative_path,
                        destination_relative_path,sha256,bytes,
                        destination_uid,destination_gid,
                        destination_mode,executable}]})
)
```

It excludes manifest/review/binding identities and has no cycle. Both physical
R007 plan reviews must already be frozen `0/0/0`; arbitrary exact-path bytes
cannot inherit a PASS from a different plan or review.

Pre-receipt work has authority only through:

```text
StageAPreIssuanceGrantPayload={
 schema_version:"WS-PRE-P-R007-STAGE-A-PREISSUANCE-GRANT-V1",
 grant_id,challenge_id,attempt_key,attempt_ordinal,
 plan:FilePhysical,plan_reviews:[FilePhysical exact2],
 issuer_identity,producer_identity,reviewer_identity,
 not_before,expires_at,revocation_state:"UNREVOKED",one_use:true,
 prior_attempt:FilePhysical|SignedNotReached,
 exact_operations:[
  "CREATE_STAGE_A_ATTEMPT_PARENT","PUBLISH_SOURCE_SEED_PAYLOAD",
  "PUBLISH_SOURCE_SEED_MANIFEST","PUBLISH_SOURCE_SEED_REVIEW",
  "PUBLISH_SOURCE_SEED_BINDING","CREATE_SOURCE_SNAPSHOT",
  "CREATE_STAGE_A_SUBJECT","ISSUE_STAGE_A_RECEIPT"
 ],
 exact_paths:[literal CanonicalRootSpec],signature
}
```

It is signed over
`"WS-PRE-P-R007-STAGE-A-PREISSUANCE-GRANT-V1" || NUL || JCS(payload)`.
Canonical lifecycle is
`journal/preissuance/<attempt-key>/{grant.json,consume.json,closed-success.json}`
or `closed-failure.json`; all four are immutable and published under the
universal lock. `consume.json` proves consume ordinal1 and current head.
Preissuance attempts share Stage-A's ordinal chain, max16 and original hard
deadline; failure closes this ledger and only a fresh key may retry. A PASS
review is evidence, never a grant.

Every external review publication, including source-seed, Stage-A and the two
Stage-B reviews, also requires:

```text
ReviewPublicationGrantPayload={
 schema_version:"WS-PRE-P-R007-REVIEW-PUBLICATION-GRANT-V1",
 grant_id,operation:
  "PUBLISH_SOURCE_SEED_REVIEW"|"PUBLISH_STAGE_A_ATTEMPT_REVIEW"|
  "PUBLISH_STAGE_B_REGRESSION_REVIEW"|"PUBLISH_STAGE_B_RESOLVED_REVIEW",
 challenge_id,attempt_key,subject_manifest:FilePhysical,
 exact_final_path,reviewer_identity,issuer_identity,
 not_before,expires_at,revocation_state:"UNREVOKED",one_use:true
}
ReviewPublicationGrant={payload:ReviewPublicationGrantPayload,signature}
```

Its signature domain is
`WS-PRE-P-R007-REVIEW-PUBLICATION-GRANT-V1`. The reviewer acquires the universal
lock, verifies the current subject and one exact path, publishes once, fsyncs
the parent, records consumption, and releases. A review cannot issue or consume
the next-stage receipt.

The preissuance grant's ordered, non-cross-product scope is exactly:

```text
1 consume/fsync its preissuance ledger entry
2 mkdir/fsync/reopen journal/attempts/stage-a/<attempt-key>
3 mkdir/fsync/reopen its source-seed-bundle; publish exact payload/manifest
4 external reviewer uses one-path PUBLISH_SOURCE_SEED_REVIEW; issuer publishes binding
5 create the frozen source snapshot below
6 mkdir/fsync/reopen the repository candidate attempt/subject parent
7 materialize the reviewed seed into that subject
```

Every step is challenge/key-bound, NOREPLACE and parent-fsynced; reuse, reordering
or any sibling path fails. The issuer verifies every payload `FilePhysical`, manifest signature, external
`0/0/0`, binding signature, exact45 membership, plan/review fingerprints,
semantic-contract recomputation and challenge/attempt equality
through anchored read-only fds. Only then its one-use
capability reaches the final destination:

```text
pre-p-validation-convergence-candidate-r007/
  attempts/<stage-a-attempt-key>/subject/
```

For each ordered entry the issuer creates the exact parent with NOREPLACE, opens
a new destination inode with `O_CREAT|O_EXCL|O_NOFOLLOW`, copies bytes from the
already verified source fd, sets the declared mode/uid/gid, fsyncs, reopens and
requires hash/bytes equality, nlink1 and source/destination inode inequality.
Directories are fsynced bottom-up; the sealed subject source tree is then
reopened and compared to the destination set digest before issuance. No manual
write, heredoc, inline generated source, network fetch, unreviewed extra file,
hardlink, reflink with shared writable backing, or builder-generates-builder path
is allowed. The seed bundle is immutable input only and is excluded from
candidate outputs, N26, T1, C0 and M_after. This makes bootstrap edges strictly:

```text
external payload -> seed manifest -> external review -> seed binding
-> frozen source snapshot -> fresh subject contract sources
-> builder executions -> candidate outputs
```

The sole Stage-A repository input is a fresh-inode, immutable source snapshot:

```text
walksafe-pre-p-validation-r007/attempts/<stage-a-attempt-key>/source-snapshot/
  root/
  logical-content-manifest.json
  physical-materialization-receipt.json
SourceSnapshotManifest={
 schema_version:"WS-PRE-P-R007-SOURCE-SNAPSHOT-V1",
 attempt_key,plan:FilePhysical,source_checkpoint:FilePhysical,
 source_tail:{sequence:39,event_sha256},
 root:DirPhysical,
 exact_excluded_prefixes:[the six literal §17 exclusions],
 ordered_members:[{relative_path,type,mode,sha256,bytes}],
 member_path_set_sha256,member_content_set_sha256,status:"SEALED",signature
}
SourceSnapshotPhysicalReceipt={
 schema_version:"WS-PRE-P-R007-SOURCE-SNAPSHOT-PHYSICAL-V1",
 manifest:FilePhysical,root:DirPhysical,
 ordered_members:[{relative_path,dev,inode,mnt_id,uid,gid,mode,nlink,size,sha256}],
 source_destination_shared_regular_inode_count:0,
 all_regular_nlink1:true,file_fsyncs:true,
 directories_bottom_up_fsynced:true,parent_fsynced:true,status:"PASS",signature
}
```

The producer copies through anchored read-only source fds after verifying the
§2 checkpoint/tree/tail, applies the exact exclusions, fsyncs/reopens, and
publishes manifest then physical receipt before any Stage-A input manifest.
Every Stage-A project-content input is copied from this root, never from the
live repository. The excluded frozen plan and its two reviews are supplied only
through their already-bound `FilePhysical` values in the consumed preissuance
grant; they are the sole non-snapshot repository-origin inputs. The Stage-A
subject manifest binds
`source_snapshot_root:DirPhysical`, `source_snapshot_manifest:FilePhysical` and
`source_snapshot_physical_receipt:FilePhysical`; Stage B reaches source bytes
only through this chain.

### 4.2 dynamic parents and immutable publications

After bootstrap, dynamic attempt/review/transaction/evidence parents are created
under anchored final fds with `mkdirat` NOREPLACE, mode0700, child-to-parent fsync,
reopen/lstat=fstat, no unexpected child. Only the receipt-authorized exact key/TID
may create its parent, except the issuer has the ordered challenge-bound
`CREATE_ISSUANCE_PARENT` scope in §4.1.2 for the exact pre-receipt seed/subject
parents.

All receipt, incident, global/transaction numeric record, raw evidence and
standalone manifest publication uses:

```text
PUBLISH_ONCE_AND_ADOPT(expected final path, expected immutable bytes):
  if absent:
    O_TMPFILE in exact final parent
    -> write all bytes -> fchmod0444 -> fsync(tmp)
    -> linkat(AT_EMPTY_PATH) NOREPLACE
    -> fsync(parent)
  reopen final O_RDONLY|O_CLOEXEC|O_NOFOLLOW
  -> lstat=fstat -> regular0444/nlink1
  -> exact bytes/hash equality
  -> signature equality when artifact schema is signed
  if exact: ADOPT_CURRENT
  else: PUBLICATION_ABORTED
```

`EEXIST` never overwrites or deletes. Receipt/manifest linked before its global
binding and then orphaned is adopted only when attempt key, expected predecessor,
signature, exact bytes and current state all match. Otherwise same OFD lock holder
publishes a signed `PUBLICATION_ABORTED` global transition and incident; it cannot
reuse the path.

Transaction creation uses a same-parent:

```text
journal/transactions/.<transaction-id>.tmp.<nonce>/
  transaction-manifest.json
  records/
  evidence/stage-c-exact6/
```

complete subtree, fsyncs bottom-up, then
`renameat2(...,<transaction-id>,RENAME_NOREPLACE)` and fsyncs transactions parent.
Existing exact complete subtree is adopted; partial/invalid final fails. The
transaction manifest is logical sequence zero and does not contain its own
path/hash/bytes. Its external `FilePhysical` is first introduced by global binding
and first `FENCE_BOUND`.

This whole-subtree publication is the sole pre-FENCE bootstrap exception. It
requires either effective ordinary C CONSUMED+live LEASE or a separately
authorized recovery CONSUMED+live `RECOVERY_LEASED` state, a valid universal LockGuard,
the byte-equal TransactionTargetSpec and the
`PUBLISH_TRANSACTION_MANIFEST_BOOTSTRAP` capability paired to that spec's exact
transaction root.
It may create/fsync/rename only the empty records/evidence parents and immutable
manifest; target, U1, evidence invocation and numeric progress mutation remain
zero. After reopen yields ManifestSentinel, ordinary C publishes PREPARED with an
ACTUAL binding and then first `FENCE_BOUND`; pre-manifest recovery has no PREPARED
kind and proceeds directly to its first `FENCE_BOUND`. Both branches use the
same operation name above, and no unlisted bootstrap operation exists.

## 5. Stage-A normative materialization contract

### 5.1 complete schemas, templates, inputs and builders

Under:

```text
pre-p-validation-convergence-candidate-r007/
  attempts/<stage-a-attempt-key>/subject/
```

the exact contract sources are:

```text
schemas/physical.schema.json
schemas/signed-absent-target.schema.json
schemas/source-input-manifest.schema.json
schemas/builder-command-manifest.schema.json
schemas/build-receipt.schema.json
schemas/equality-receipt.schema.json
schemas/normative-exact26-target-table.schema.json
schemas/candidate-target-map.schema.json
schemas/candidate-review-subject-manifest.schema.json
templates/normative-exact26-target-table.template.json
input-templates/phase-0.inputs.template.json
input-templates/lane-a.inputs.template.json
input-templates/lane-c.inputs.template.json
input-templates/control-core.inputs.template.json
input-templates/lane-d-core.inputs.template.json
input-templates/lane-b-final.inputs.template.json
input-templates/lane-d-final.inputs.template.json
input-templates/after-control.inputs.template.json
input-templates/apply.inputs.template.json
input-templates/aggregate.inputs.template.json
templates/builder-command-manifest.template.json
```

builder exact10:

```text
builders/phase-0-builder.py
builders/lane-a-lock-env-builder.py
builders/lane-c-apply-helper-builder.py
builders/control-core-builder.py
builders/lane-d-core-builder.py
builders/lane-b-final-builder.py
builders/lane-d-final-builder.py
builders/after-control-builder.py
builders/apply-envelope-builder.py
builders/aggregate-builder.py
```

After fresh seed copy and destination-Physical verification, the issuer expands
the copied logical templates into these actual signed bindings:

```text
inputs/phase-0.inputs.json
inputs/lane-a.inputs.json
inputs/lane-c.inputs.json
inputs/control-core.inputs.json
inputs/lane-d-core.inputs.json
inputs/lane-b-final.inputs.json
inputs/lane-d-final.inputs.json
inputs/after-control.inputs.json
inputs/apply.inputs.json
inputs/aggregate.inputs.json
core-command-manifests/wave-01.json
core-command-manifests/wave-02.json
core-command-manifests/wave-03.json
core-command-manifests/wave-04.json
core-command-manifests/wave-05.json
core-command-manifests/wave-06.json
core-command-manifests/wave-07.json
runtime-discovery-command-manifest.json
runtime-build-command-manifest.json
```

Templates contain literal roles/paths only and no `dev`, `inode`, `mnt_id`,
hash/bytes or signature. The issuer fills actual Physical values only after the
referenced destination/input exists and is sealed, then signs and publishes each
binding once at the §5.2 phase that makes all of its references actual. The
runtime command manifest is therefore not published with the earlier core
manifest. Thus reviewed seed bytes never predict fresh destination inodes and no
builder generates its own code or command.

Each builder command is derived, not inferred:

```json
[
  "/env/bin/python", "-I", "-B", "<literal builder path>",
  "--contract", "templates/normative-exact26-target-table.template.json",
  "--inputs", "/input/source-input-manifest.json",
  "--slot", "<build-01|build-02|candidate>"
]
```

The builder/inputs table resolves every metavariable:

| unit | builder | inputs | output roots |
|---|---|---|---|
| phase-0 | `builders/phase-0-builder.py` | `inputs/phase-0.inputs.json` | `phase-0-contracts/<slot>/` |
| lane-a | `builders/lane-a-lock-env-builder.py` | `inputs/lane-a.inputs.json` | `lane-a/<slot>/` |
| lane-c | `builders/lane-c-apply-helper-builder.py` | `inputs/lane-c.inputs.json` | `lane-c/<slot>/` |
| control-core | `builders/control-core-builder.py` | `inputs/control-core.inputs.json` | `control-core/<slot>/` |
| lane-d-core | `builders/lane-d-core-builder.py` | `inputs/lane-d-core.inputs.json` | `lane-d-core/<slot>/` |
| lane-b-final | `builders/lane-b-final-builder.py` | `inputs/lane-b-final.inputs.json` | `lane-b-final/<slot>/` |
| lane-d-final | `builders/lane-d-final-builder.py` | `inputs/lane-d-final.inputs.json` | `lane-d-final/<slot>/` |
| after-control | `builders/after-control-builder.py` | `inputs/after-control.inputs.json` | `after-control/<slot>/` |
| apply | `builders/apply-envelope-builder.py` | `inputs/apply.inputs.json` | `apply/<slot>/` |
| aggregate | `builders/aggregate-builder.py` | `inputs/aggregate.inputs.json` | `aggregate/<slot>/` |

Input template constructors are `F(role,literal_file_path)` and
`D(role,literal_directory_path,required_recursive_manifest_role)`. They expand to
ordered actual `FilePhysical` or `DirManifestPhysical` entries; there is no glob
or implicit repository read. The exact unit arrays are:

```text
phase-0=[
 F(PLAN,"plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007.md"),
 F(CHECKPOINT,"docs/control/walksafe-project-continuation-checkpoint.json"),
 F(R006,"plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006.md"),
 F(R006_FORMAL,"plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006-independent-review-r001.md"),
 F(R006_SKEPTICAL,"plans/features/2026-07-29_walksafe_plan_rebaseline_r001/PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R006-independent-skeptical-review-r001.md"),
 F(RUNNER_BEFORE,"scripts/run_walksafe_test_layers_20260711.sh"),
 F(REQUIREMENTS_BEFORE,"tests/requirements.lock")
]
lane-a=[
 F(PHASE0_PREDECESSOR,"phase-0-contracts/candidate/regression-predecessor-identity.json"),
 F(PHASE0_TRANSFORMS,"phase-0-contracts/candidate/regression-allowed-transformations.json"),
 F(REQUIREMENTS_BEFORE,"tests/requirements.lock"),
 F(REQUIREMENTS_SOURCE,"tests/requirements.txt"),
 F(BACKEND_REQUIREMENTS,"backend/requirements.txt"),
 F(BACKEND_LOCK,"backend/requirements.lock"),
 F(GENERAL_LOCK,"tests/general-quality-cp312-linux-x86_64-cpu.lock")
]
lane-c=[
 F(PHASE0_TRANSFORMS,"phase-0-contracts/candidate/regression-allowed-transformations.json"),
 F(GATEWAY_PACKAGE,"apps/android-gateway/package.json"),
 F(GATEWAY_LOCK,"apps/android-gateway/package-lock.json"),
 F(GATEWAY_TSCONFIG,"apps/android-gateway/tsconfig.json"),
 F(GATEWAY_SERVER,"apps/android-gateway/server.ts"),
 F(GATEWAY_OPENAPI,"apps/android-gateway/openapi.json"),
 D(GATEWAY_SRC,"apps/android-gateway/src","GATEWAY_SOURCE_LITERAL_SET"),
 D(GATEWAY_TEST,"apps/android-gateway/test","GATEWAY_TEST_LITERAL_SET")
]
control-core=[
 F(PHASE0_PREDECESSOR,"phase-0-contracts/candidate/regression-predecessor-identity.json"),
 F(CHECKPOINT,"docs/control/walksafe-project-continuation-checkpoint.json"),
 F(RUNNER_BEFORE,"scripts/run_walksafe_test_layers_20260711.sh"),
 D(CONTROL_V24,"docs/control/goals/walksafe-completion-graph-v2-4","CONTROL_V24_LITERAL_SET")
]
lane-d-core=[
 F(PHASE0_TRANSFORMS,"phase-0-contracts/candidate/regression-allowed-transformations.json"),
 F(BASELINE_APPROVAL,"docs/control/baselines/walksafe-artifact-baseline-approval-20260722-r001.json"),
 F(BASELINE_RECEIPT,"docs/control/baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json"),
 F(BASELINE_TEMPORAL,"docs/control/baselines/walksafe-artifact-baseline-application-temporal-provenance-supplement-20260722-r001.json"),
 F(DELIVERABLE_README,"docs/deliverables/00-control/README.md"),
 F(ARTIFACT_REGISTER,"docs/deliverables/00-control/artifact-register.json")
]
lane-b-final=[
 F(LANE_A_EQUALITY,"lane-a/candidate/equality-receipt.json"),
 F(LANE_C_EQUALITY,"lane-c/candidate/equality-receipt.json"),
 F(CONTROL_EQUALITY,"control-core/candidate/equality-receipt.json"),
 D(LANE_A_CANDIDATE,"lane-a/candidate","LANE_A_CANDIDATE_LITERAL_SET"),
 D(LANE_C_CANDIDATE,"lane-c/candidate","LANE_C_CANDIDATE_LITERAL_SET"),
 D(CONTROL_CANDIDATE,"control-core/candidate","CONTROL_CANDIDATE_LITERAL_SET")
]
lane-d-final=[
 F(LANE_A_EQUALITY,"lane-a/candidate/equality-receipt.json"),
 F(LANE_B_EQUALITY,"lane-b-final/candidate/equality-receipt.json"),
 F(LANE_C_EQUALITY,"lane-c/candidate/equality-receipt.json"),
 F(CONTROL_EQUALITY,"control-core/candidate/equality-receipt.json"),
 F(LANE_D_CORE_EQUALITY,"lane-d-core/candidate/equality-receipt.json"),
 D(LANE_A_CANDIDATE,"lane-a/candidate","LANE_A_CANDIDATE_LITERAL_SET"),
 D(LANE_B_CANDIDATE,"lane-b-final/candidate","LANE_B_CANDIDATE_LITERAL_SET"),
 D(LANE_C_CANDIDATE,"lane-c/candidate","LANE_C_CANDIDATE_LITERAL_SET"),
 D(CONTROL_CANDIDATE,"control-core/candidate","CONTROL_CANDIDATE_LITERAL_SET"),
 D(LANE_D_CORE_CANDIDATE,"lane-d-core/candidate","LANE_D_CORE_CANDIDATE_LITERAL_SET")
]
after-control=[
 F(LANE_B_EQUALITY,"lane-b-final/candidate/equality-receipt.json"),
 F(LANE_D_FINAL_EQUALITY,"lane-d-final/candidate/equality-receipt.json"),
 F(CONTROL_EQUALITY,"control-core/candidate/equality-receipt.json"),
 F(CHECKPOINT,"docs/control/walksafe-project-continuation-checkpoint.json"),
 D(DISCOVERY132,".","DISCOVERY132_EXACT_LITERAL_SET"),
 D(CONTROL_V24,"docs/control/goals/walksafe-completion-graph-v2-4","CONTROL_V24_LITERAL_SET")
]
apply=[
 F(LANE_A_EQUALITY,"lane-a/candidate/equality-receipt.json"),
 F(LANE_B_EQUALITY,"lane-b-final/candidate/equality-receipt.json"),
 F(LANE_C_EQUALITY,"lane-c/candidate/equality-receipt.json"),
 F(CONTROL_EQUALITY,"control-core/candidate/equality-receipt.json"),
 F(LANE_D_CORE_EQUALITY,"lane-d-core/candidate/equality-receipt.json"),
 F(LANE_D_FINAL_EQUALITY,"lane-d-final/candidate/equality-receipt.json"),
 F(AFTER_EQUALITY,"after-control/candidate/equality-receipt.json"),
 D(LANE_A_CANDIDATE,"lane-a/candidate","LANE_A_CANDIDATE_LITERAL_SET"),
 D(LANE_B_CANDIDATE,"lane-b-final/candidate","LANE_B_CANDIDATE_LITERAL_SET"),
 D(LANE_C_CANDIDATE,"lane-c/candidate","LANE_C_CANDIDATE_LITERAL_SET"),
 D(CONTROL_CANDIDATE,"control-core/candidate","CONTROL_CANDIDATE_LITERAL_SET"),
 D(LANE_D_CORE_CANDIDATE,"lane-d-core/candidate","LANE_D_CORE_CANDIDATE_LITERAL_SET"),
 D(LANE_D_FINAL_CANDIDATE,"lane-d-final/candidate","LANE_D_FINAL_CANDIDATE_LITERAL_SET"),
 D(AFTER_CANDIDATE,"after-control/candidate","AFTER_CANDIDATE_LITERAL_SET")
]
aggregate=[
 F(PHASE0_EQUALITY,"phase-0-contracts/candidate/equality-receipt.json"),
 F(LANE_A_EQUALITY,"lane-a/candidate/equality-receipt.json"),
 F(LANE_B_EQUALITY,"lane-b-final/candidate/equality-receipt.json"),
 F(LANE_C_EQUALITY,"lane-c/candidate/equality-receipt.json"),
 F(CONTROL_EQUALITY,"control-core/candidate/equality-receipt.json"),
 F(LANE_D_CORE_EQUALITY,"lane-d-core/candidate/equality-receipt.json"),
 F(LANE_D_FINAL_EQUALITY,"lane-d-final/candidate/equality-receipt.json"),
 F(AFTER_EQUALITY,"after-control/candidate/equality-receipt.json"),
 F(APPLY_EQUALITY,"apply/candidate/equality-receipt.json"),
 D(APPLY_CANDIDATE,"apply/candidate","APPLY_CANDIDATE_LITERAL_SET"),
 F(N26_TEMPLATE,"templates/normative-exact26-target-table.template.json")
]
```

`DirManifestPhysical` and every role used above are exact:

```text
DirManifestPhysical={
 root,root_physical:DirPhysical,
 ordered_members:[FilePhysical],
 member_path_set_sha256,member_content_set_sha256
}
GATEWAY_SOURCE_LITERAL_SET=[
 "apps/android-gateway/src/auth.ts","apps/android-gateway/src/backend.ts",
 "apps/android-gateway/src/config.ts","apps/android-gateway/src/exclusive-file-lock.ts",
 "apps/android-gateway/src/field-long-session.ts","apps/android-gateway/src/field-walk-ledger.ts",
 "apps/android-gateway/src/integrated-consent.ts","apps/android-gateway/src/node-adapter.ts",
 "apps/android-gateway/src/privacy-rights.ts","apps/android-gateway/src/request-body.ts",
 "apps/android-gateway/src/routes.ts","apps/android-gateway/src/telemetry.ts"
]
GATEWAY_TEST_LITERAL_SET=[
 "apps/android-gateway/test/exclusive-file-lock.test.ts",
 "apps/android-gateway/test/field-long-session.test.ts",
 "apps/android-gateway/test/field-walk-ledger.test.ts",
 "apps/android-gateway/test/gateway-contract.test.ts",
 "apps/android-gateway/test/integrated-consent.test.ts",
 "apps/android-gateway/test/node-adapter.test.ts",
 "apps/android-gateway/test/privacy-rights.test.ts",
 "apps/android-gateway/test/telemetry.test.ts"
]
CONTROL_V24_LITERAL_SET=[
 "docs/control/goals/walksafe-completion-graph-v2-4/README.md",
 "docs/control/goals/walksafe-completion-graph-v2-4/active-supersession-record-v2.3.0.json",
 "docs/control/goals/walksafe-completion-graph-v2-4/static-plan-manifest-v2.4.0.json",
 "docs/control/goals/walksafe-completion-graph-v2-4/superseded-v2.3.0-active-checkpoint.json"
]
LANE_A_CANDIDATE_LITERAL_SET=["lane-a/candidate/requirements.lock"]
LANE_B_CANDIDATE_LITERAL_SET=[
 "lane-b-final/candidate/walksafe-test-database-preflight-successor-r007.py",
 "lane-b-final/candidate/run-walksafe-test-layers-b-fragment.json"
]
LANE_C_CANDIDATE_LITERAL_SET=[
 "lane-c/candidate/walksafe-android-gateway-successor-r007.py"
]
CONTROL_CANDIDATE_LITERAL_SET=[
 "control-core/candidate/seq40-event-schema.json",
 "control-core/candidate/run-walksafe-test-layers-control-fragment.json"
]
LANE_D_CORE_CANDIDATE_LITERAL_SET=[
 "lane-d-core/candidate/check-walksafe-artifact-baseline-historical-event-time.py",
 "lane-d-core/candidate/check-walksafe-artifact-baseline-current-active.py",
 "lane-d-core/candidate/check-walksafe-artifact-baseline-dual-control.py"
]
LANE_D_FINAL_CANDIDATE_LITERAL_SET=[
 "lane-d-final/candidate/walksafe-artifact-baseline-historical-successor-r007.py",
 "lane-d-final/candidate/walksafe-artifact-baseline-current-successor-r007.py"
]
AFTER_CANDIDATE_LITERAL_SET=[
 "after-control/candidate/check-walksafe-project-continuation-v2-4-1.py",
 "after-control/candidate/check-walksafe-goal-graph-v2-4-1.py",
 "after-control/candidate/test-walksafe-project-continuation-v2-4-1-successor.py",
 "after-control/candidate/test-walksafe-goal-graph-v2-4-1-successor.py",
 "after-control/candidate/test-walksafe-v2-4-1-seq40-transition-successor.py",
 "after-control/candidate/test-routing-before-seq39-v2.4.json",
 "after-control/candidate/test-routing-after-seq40-v2.4.1.json",
 "after-control/candidate/superseded-v2.4.0-active-checkpoint.json",
 "after-control/candidate/v2.4-supersession-record.json",
 "after-control/candidate/v2.4-supersession-anchor.json",
 "after-control/candidate/transition-event-seq40.json",
 "after-control/candidate/static-plan-manifest-v2.4.1.json",
 "after-control/candidate/control-package-manifest-v2.4.1.json",
 "after-control/candidate/README.md",
 "after-control/candidate/full19-successor-contract.json",
 "after-control/candidate/regression-successor-contract.json",
 "after-control/candidate/stage-c-exact6-contract.json",
 "after-control/candidate/full19-output-oracle.json",
 "after-control/candidate/full19-consumer-map.json"
]
APPLY_CANDIDATE_LITERAL_SET=[
 "apply/candidate/requirements.lock",
 "apply/candidate/run_walksafe_test_layers_20260711.sh",
 "apply/candidate/walksafe-project-continuation-checkpoint.json"
]
DISCOVERY132_EXACT_LITERAL_SET=
  [the third column of §11 Complete normative discovered registry, in row order]
```

`ordered_sandbox_aliases` expands each ordered FilePhysical (including every
directory-role member) to
`/input/members/<six-digit-input-ordinal>/<source-relative-path>` in the same
order. The alias is a sandbox logical path, not a Physical; the invocation intent
binds its actual copied FilePhysical and byte/inode equality receipt.

The §11 cross-reference is a deterministic literal-array inclusion, not an
inference or glob. Each role's `ordered_members.path` must equal its displayed
array exactly.

Unit semantic outputs for each `<slot>` are the corresponding role array above;
phase-0 has its exact two files, aggregate has its exact N26 file:

For a role-array path, slot expansion replaces its single literal `/candidate/`
segment with `/build-01/`, `/build-02/` or `/candidate/`; candidate is the
identity substitution. Zero/multiple segments or any other rewrite fails.

```text
phase-0-contracts/<slot>/regression-predecessor-identity.json
phase-0-contracts/<slot>/regression-allowed-transformations.json
aggregate/<slot>/normative-exact26-target-table.json
```

Every unit also has exact metadata paths
`<unit-root>/<slot>/raw/{intent.json,stdout.bin,stderr.bin,access-trace.raw,access-trace.json,result.json}`
and `<unit-root>/<slot>/build-receipt.json`; candidate equality receipt is
`<unit-root>/candidate/equality-receipt.json`. `unit-root` is
`phase-0-contracts` for phase-0 and otherwise the unit name. No other output path
is allowed.

For each unit `allowed_read_paths` is the literal copied builder path, the actual
signed `inputs/<unit>.inputs.json` FilePhysical bound at the literal
`/input/source-input-manifest.json`, literal N26-template path, expanded
ordered array and the `/env` `DirManifestPhysical`. Input templates are
issuer-only derivation inputs and are not builder-readable. Its
`payload_write_allowlist` is only the exact semantic member array for the selected
slot. Its `supervisor_publication_allowlist` is the exact raw six plus
BuildReceipt, and after three slots the separately published EqualityReceipt.
Common literal
`denied_read_prefixes` are:

```text
.git
/home/sandbox/.ssh
/home/sandbox/.config
/home/sandbox/.aws
/home/sandbox/.gnupg
/proc/self/fd
/run/secrets
/home/ddobagi/.local/share/hanium-dreamup/walksafe-pre-p-authority-r007
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r007/attempts/<other-attempt-key>
<unit-root>/<other-slot>
```

The two key/slot placeholders are expanded to the exact denied siblings before
signing and never appear in the actual manifest. Network syscalls are denied
separately by the sandbox. An entry explicitly listed in an ordered input array
overrides only that exact path. A directory-role manifest expands to the literal
sorted member set before signing.

`<slot>` is exactly one of `build-01`, `build-02`, `candidate`; the row plus chosen
slot yields a literal array. Arbitrary output/target arg is forbidden. Builder
derives outputs only from the N26 contract and its input manifest.

The literal sandbox cwd is `/work/subject`; its host Physical is the sealed
attempt subject bound by §8.1. Supervisor starts `env -i` with:

```text
HOME=/home/sandbox
TMPDIR=/tmp
PATH=/env/bin:/usr/bin:/bin
LANG=C.UTF-8
LC_ALL=C.UTF-8
TZ=UTC
PYTHONHASHSEED=0
PYTHONDONTWRITEBYTECODE=1
```

`/env/bin/python` is an ACTUAL `FilePhysical` from the sealed Stage-A bootstrap
environment. Future builder/template/output hashes use:

```text
FutureSealed={state:"FUTURE_SEALED",role_id,producer_builder,input_manifest}
```

until bytes are produced and sealed. A made-up 64-hex value is forbidden.

### 5.2 exact schema fields

All JSON is strict RFC8785 JCS, duplicate/unknown fields forbidden.

All digest-bearing schemas in this plan use these common formulas; a bare
implementation-defined “digest” is forbidden:

```text
R007_SET_DIGEST(role,ordered_values)=SHA256(
 ASCII("WS-PRE-P-R007-SET-DIGEST-V1") || NUL ||
 RFC8785_JCS({role,ordering,ordered_values})
)
R007_GRAPH_DIGEST(role,ordered_nodes,ordered_edges)=SHA256(
 ASCII("WS-PRE-P-R007-GRAPH-DIGEST-V1") || NUL ||
 RFC8785_JCS({role,ordered_nodes,ordered_edges})
)
R007_SUBJECT_CONTENT_DIGEST(role,ordered_members,exact_exclusions)=SHA256(
 ASCII("WS-PRE-P-R007-SUBJECT-CONTENT-V1") || NUL ||
 RFC8785_JCS({role,ordered_members:[
   {relative_path,type,mode,sha256,bytes}
 ],exact_exclusions})
)
```

`role` is the containing `schema_version + ":" + field_name`; `ordering` is
`DISPLAYED_NORMATIVE_ORDER` for exact arrays and `UTF8_RELATIVE_PATH_ASC` for
path/member sets. No locale order is allowed. `ordered_values` is the complete
typed JCS value array for the field named by role, excluding only its digest
field and signature. Path-set fields use `{relative_path}`; content-set fields
use `{relative_path,type,mode,sha256,bytes}`. Command/input/output/environment/
node/edge/source/member/prefix set fields use the complete corresponding strict
objects. `graph_digest` uses node IDs in displayed/topological order and edges
by `(from_id,to_id,edge_role)` UTF-8 order. `subject_content_digest` recursively
enumerates every regular/symlink/directory member except the literal
`exact_exclusions`, with directories bytes=0 and sha256 of the empty byte string.
Every `*_set_sha256`, `graph_digest`, `subject_content_digest`,
`environment_set_sha256`, `command_set_sha256`, `input_set_sha256`,
`output_set_sha256`, `source_set_sha256`, `member_path_set_sha256` and
`member_content_set_sha256` in §4–§18 is exactly one of these formulas. The field
name fixes role and included array, so two conforming verifiers cannot choose
different inputs.

For both review-subject schemas, `ordered_graph_nodes` enumerates every bound
FilePhysical field/array element exactly once in that schema's displayed field
and array order. `ordered_graph_edges` contains every actual FilePhysical
reference edge between those nodes in the sort order above. Their
`graph_digest` uses only those two embedded arrays; an omitted/duplicate node,
dangling edge, or bound artifact/reference absent from them fails.

The only explicit exceptions are §17 `projected_path_set_sha256` and
`content_set_sha256`, whose LF/NUL byte-concatenation formulas and fixed
M_after role names override this common JCS family. Their application-receipt
bindings use those §17 values, not R007_SET_DIGEST.

```text
SourceInputManifest = {
  schema_version, unit, attempt_key, plan:FilePhysical,
  source_checkpoint:FilePhysical, source_tail,
  ordered_inputs:[FilePhysical|DirManifestPhysical],
  ordered_sandbox_aliases:[{source:FilePhysical,alias}],
  allowed_read_paths, denied_read_prefixes,
  input_set_sha256, signature
}

BuilderCommandManifest = {
  schema_version, attempt_key,
  commands:[{unit,builder:FilePhysical,template:FilePhysical,
             inputs:FilePhysical,slot,argv,cwd,env,
             read_allowlist,payload_write_allowlist,
             supervisor_publication_allowlist,timeout_seconds,
             stdout_cap,stderr_cap}],
  command_set_sha256, signature
}

BuildReceipt = {
  schema_version, attempt_key, unit, slot,
  plan:FilePhysical, n26_contract:FilePhysical,
  command_manifest:FilePhysical,
  builder:FilePhysical, templates:[FilePhysical],
  inputs:FilePhysical, argv, cwd, env,
  outputs:[FilePhysical], output_set_sha256,
  raw_intent:FilePhysical, raw_stdout:FilePhysical,
  raw_stderr:FilePhysical, raw_trace:FilePhysical,
  normalized_trace:FilePhysical, raw_result:FilePhysical,
  status:"PASS", signature
}

UnitEqualityReceipt = {
  schema_version, attempt_key, unit,
  build_01_receipt:FilePhysical, build_02_receipt:FilePhysical,
  n26_template:FilePhysical,structural_contract_role,
  ordered_output_roles,
  logical_byte_equal:true, pairwise_distinct_inode:true,
  candidate_outputs:[FilePhysical],
  status:"PASS", signature
}
AggregateEqualityReceipt = {
  schema_version:"WS-PRE-P-R007-AGGREGATE-EQUALITY-V1",
  attempt_key,unit:"aggregate",
  build_01_receipt:FilePhysical,build_02_receipt:FilePhysical,
  candidate_receipt:FilePhysical,
  upstream_unit_equalities:[FilePhysical exact9],
  candidate_target_map:FilePhysical,
  n26_payloads:[FilePhysical exact3],
  n26_digest,logical_byte_equal:true,
  pairwise_distinct_inode:true,status:"PASS",signature
}
EqualityReceipt=UnitEqualityReceipt|AggregateEqualityReceipt

CandidateTargetMap = {
  schema_version, contract_id, n26_digest,
  structural_rows:[NormativeTargetRow exact26],
  actual_candidates:[{ordinal,source:FilePhysical}],
  status:"SEALED", signature
}

CandidateReviewSubjectManifest = {
  schema_version,contract_id,attempt_key,
  plan:FilePhysical,plan_reviews:[FilePhysical exact2],
  source_seed_binding:FilePhysical,
  source_snapshot_root:DirPhysical,
  source_snapshot_manifest:FilePhysical,
  source_snapshot_physical_receipt:FilePhysical,
  source_input_manifests:[FilePhysical exact10],
  runtime_input_manifests:[FilePhysical exact2],
  builder_command_manifests:[
    FilePhysical("core-command-manifests/wave-01.json"),
    FilePhysical("core-command-manifests/wave-02.json"),
    FilePhysical("core-command-manifests/wave-03.json"),
    FilePhysical("core-command-manifests/wave-04.json"),
    FilePhysical("core-command-manifests/wave-05.json"),
    FilePhysical("core-command-manifests/wave-06.json"),
    FilePhysical("core-command-manifests/wave-07.json"),
    FilePhysical("runtime-discovery-command-manifest.json"),
    FilePhysical("runtime-build-command-manifest.json")
  ],
  builder_sources:[FilePhysical exact10],
  runtime_builder_sources:[FilePhysical exact2],
  build_receipts:[FilePhysical exact30],
  equality_receipts:[
    UnitEqualityReceipt FilePhysical exact9,
    AggregateEqualityReceipt FilePhysical exact1
  ],
  runtime_pack_receipts:[FilePhysical exact10],
  environment_attempt_manifest:FilePhysical,
  n26_payloads:[FilePhysical exact3],
  stage_a_runtime_role_contracts:[
    FilePhysical("after-control/candidate/full19-successor-contract.json"),
    FilePhysical("after-control/candidate/regression-successor-contract.json"),
    FilePhysical("after-control/candidate/stage-c-exact6-contract.json")
  ],
  candidate_target_map:FilePhysical,
  aggregate_equality_receipt:FilePhysical,
  ordered_candidate_sources:[FilePhysical exact26],
  ordered_graph_nodes:[{node_id,artifact:FilePhysical}],
  ordered_graph_edges:[{from_id,to_id,edge_role}],
  graph_digest,subject_content_digest,
  exact_exclusions:[
    "candidate-review-subject-manifest.json",
    "journal/reviews/stage-a/<attempt-key>/candidate-independent-review.md"
  ],
  status:"SEALED",signature
}
```

Its `runtime_pack_receipts exact10` expands in this order:

```text
runtime-pack/local-combined/build-01/build-receipt.json
runtime-pack/local-combined/build-02/build-receipt.json
runtime-pack/local-combined/candidate/build-receipt.json
runtime-pack/hosted-cpu/build-01/build-receipt.json
runtime-pack/hosted-cpu/build-02/build-receipt.json
runtime-pack/hosted-cpu/candidate/build-receipt.json
runtime-pack/local-combined/pairwise-distinct-receipt.json
runtime-pack/hosted-cpu/pairwise-distinct-receipt.json
walksafe-pre-p-validation-r007/attempts/<stage-a-attempt-key>/
  local-combined/physical-materialization-receipt.json
walksafe-pre-p-validation-r007/attempts/<stage-a-attempt-key>/
  hosted-cpu/physical-materialization-receipt.json
```

The last two are canonical relative to §3 external validation base; attempt-key
is expanded before subject seal.

The single seeded `templates/builder-command-manifest.template.json` is
instantiated as nine one-way manifests. Core waves are exactly
`[phase-0]`, `[lane-a,lane-c,control-core,lane-d-core]`, `[lane-b-final]`,
`[lane-d-final]`, `[after-control]`, `[apply]`, `[aggregate]`, each expanded
across three slots. Their command counts are `3,12,3,3,3,3,3` (total exact30).
Each wave manifest is published only after all its SourceInputManifests and
predecessor Physical references are actual, and before that wave runs. No wave
contains a later input.

After every core candidate, apply/aggregate/N26/map/equality node has sealed, the
after-control candidate's three `stage_a_runtime_role_contracts` are actual and
both RuntimeInputManifests have been published,
`runtime-discovery-command-manifest.json` contains exact6 discovery commands.
Only after all six signed RuntimeDiscoveryResults are actual does
`runtime-build-command-manifest.json` contain exact6 build commands. Runtime
receipts point to the applicable manifest. Neither
RuntimeInputManifest points back to either command manifest, so no command
manifest↔input manifest hash cycle exists.

`CandidateTargetMap` cannot add/remove/reorder or mutate structural N26 fields; it
only fills actual candidate hash/bytes/physical identities. Equality does not
override the normative table. Aggregate EqualityReceipt then binds the completed
map; candidate subject manifest binds map and receipts while excluding only its
own path. The map never references either descendant. BuildReceipt `outputs`
excludes its own receipt path; EqualityReceipt excludes its own path. External
review and Stage-C review binding point inward after subject seal. Thus all edges
are one-way and no map↔receipt↔manifest cycle exists.

Canonical physical DAG nodes and publication order are:

```text
aggregate/build-01/normative-exact26-target-table.json
aggregate/build-02/normative-exact26-target-table.json
aggregate/candidate/normative-exact26-target-table.json
-> aggregate/candidate/candidate-target-map.json
-> aggregate/candidate/equality-receipt.json
-> candidate-review-subject-manifest.json
-> journal/reviews/stage-a/<attempt-key>/candidate-independent-review.md
```

`candidate-target-map.json` is published by the aggregate supervisor from the
already sealed exact26 actual candidates; the aggregate builder cannot write it.
The aggregate equality receipt binds three equal N26 payloads plus that map. The
root subject manifest excludes itself and the later external review exactly, and
the review binds the root manifest. No node is omitted from unit membership or
points forward.

```text
RawBuildResult = {
  schema_version, unit, slot, argv_digest, cwd, env_digest,
  exec, rc, signal, timed_out,
  stdout_sha256,stdout_bytes,stderr_sha256,stderr_bytes,
  trace_sha256,trace_bytes,drop_count,
  read_set_digest,write_set_digest,
  output_set_digest,status,reason
}
```

### 5.3 builder publication and equality

Each build-01/build-02 payload writes only its clean §8.1 execution staging root.
After trace validation, the supervisor publishes every allowed member NOREPLACE
into the already bound empty reserved slot, fchmods regular files to the declared
mode, fsyncs files and directories bottom-up, fsyncs the slot parent, reopens and
recursively verifies the exact complete membership, then makes the slot
read-only. There is no directory rename over a precreated slot. build-01/build-02
have disjoint input/output/HOME/TMP roots. Candidate is materialized only after
exact logical byte equality, member-by-member with fresh inodes into its own
reserved empty slot, then identically fsynced and sealed.

Every source read and output write must appear in normalized trace. Unlisted read,
network, output, hardlink, symlink escape, overwrite or deletion fails. Each unit
publishes intent/stdout/stderr/raw trace/normalized trace/result and BuildReceipt
with PUBLISH_ONCE_AND_ADOPT. Corresponding regular files across build-01,
build-02 and candidate are pairwise distinct inode/nlink1.

## 6. exact N26 schema and source-to-target table

`NormativeTargetRow` exact fields:

```text
ordinal, candidate_source, target, operation, before_state, after_role,
target_uid, target_gid, target_mode, target_nlink, target_executable,
content_producer_builder, candidate_materializer_builder,
stage_c_target_applier, equality_receipts, parent_transition
```

`additionalProperties=false`. Common candidate source prefix is:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
pre-p-validation-convergence-candidate-r007/
attempts/<stage-a-attempt-key>/subject/
```

All candidate sources are sealed regular0444/nlink1. A row contains only
structural EqualityReceipt role paths. After N26 and candidates are sealed,
CandidateTargetMap binds actual sources; aggregate receipt binds the map; subject
manifest binds those artifacts; external Stage-A review verdict `0/0/0` binds the
sealed manifest. None of those descendants is embedded back into a row.

After-class literals:

```text
K = uid1000/gid1000/mode0664/nlink1/executable=false
R = uid1000/gid1000/mode0775/nlink1/executable=true
P = uid1000/gid1000/mode0664/nlink1/executable=false
D = uid1000/gid1000/mode0644/nlink1/executable=false
Q = uid1000/gid1000/mode0644/nlink1/executable=false
```

`before_state` is never a condensed slash string in N26. The table's compact
display expands by ordinal into one of:

```text
AbsentBefore={
 state:"SIGNED_ABSENT",reason:"TARGET_MUST_NOT_EXIST",
 expected_type:"REGULAR"
}
ActualBefore={state:"ACTUAL",physical:FilePhysical,control_tail}

ordinal1 ActualBefore.physical={
 path:"tests/requirements.lock",
 sha256:"abd690bacf91a65907e6b8357252ead9082181e9ed1b4db98987b2685e51b9fe",
 bytes:20288,dev:66306,inode:16653542,mnt_id:33,
 uid:1000,gid:1000,mode:"0664",nlink:4
}
ordinal2 ActualBefore.physical={
 path:"scripts/run_walksafe_test_layers_20260711.sh",
 sha256:"4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d",
 bytes:15588,dev:66306,inode:16647409,mnt_id:33,
 uid:1000,gid:1000,mode:"0775",nlink:1
}
ordinal26 ActualBefore.physical={
 path:"docs/control/walksafe-project-continuation-checkpoint.json",
 sha256:"6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c",
 bytes:1329415,dev:66306,inode:16943740,mnt_id:33,
 uid:1000,gid:1000,mode:"0644",nlink:1
}
ordinal26 ActualBefore.control_tail={
 sequence:39,
 event_sha256:"c12be7a16436d96b8939028d7b5bedb50580ad7761955410669d4c9d1cb7cf0a"
}
ordinal1..2 ActualBefore.control_tail={
 state:"SIGNED_NOT_APPLICABLE",reason:"NON_CHECKPOINT_TARGET"
}
ordinals3..25 before_state=AbsentBefore
```

The operation dictionary is ordinal1..2=`CAS_REPLACE`,
ordinal3..25=`NOREPLACE`, ordinal26=`CHECKPOINT_CAS_LAST`. The JCS payload
contains these typed objects/strings, never the compact table cell.

| ord | candidate source | final target | operation / before | role/class | content provenance → candidate materializer |
|---:|---|---|---|---|---|
| 1 | `apply/candidate/requirements.lock` | `tests/requirements.lock` | `CAS_REPLACE`; `abd690bacf91a65907e6b8357252ead9082181e9ed1b4db98987b2685e51b9fe/20288/dev66306/ino16653542/1000:1000/0664/nlink4` | lock/K | lane-a → apply |
| 2 | `apply/candidate/run_walksafe_test_layers_20260711.sh` | `scripts/run_walksafe_test_layers_20260711.sh` | `CAS_REPLACE`; `4f75501a42118472b4f577f4dd8778de9df9bdf89d08320ba1ac4de9f232b42d/15588/dev66306/ino16647409/1000:1000/0775/nlink1` | runner/R | control+B → apply |
| 3 | `lane-b-final/candidate/walksafe-test-database-preflight-successor-r007.py` | `tests/walksafe_test_database_preflight_successor_20260731_r007.py` | `NOREPLACE/SIGNED_ABSENT` | preflight/P | lane-b-final → lane-b-final |
| 4 | `lane-c/candidate/walksafe-android-gateway-successor-r007.py` | `tests/walksafe_android_gateway_public_routes_successor_20260731_r007.py` | `NOREPLACE/SIGNED_ABSENT` | gateway/P | lane-c → lane-c |
| 5 | `lane-d-core/candidate/check-walksafe-artifact-baseline-historical-event-time.py` | `scripts/check_walksafe_artifact_baseline_historical_event_time_20260731.py` | `NOREPLACE/SIGNED_ABSENT` | D-historical/P | lane-d-core → lane-d-core |
| 6 | `lane-d-core/candidate/check-walksafe-artifact-baseline-current-active.py` | `scripts/check_walksafe_artifact_baseline_current_active_20260731.py` | `NOREPLACE/SIGNED_ABSENT` | D-current/P | lane-d-core → lane-d-core |
| 7 | `lane-d-core/candidate/check-walksafe-artifact-baseline-dual-control.py` | `scripts/check_walksafe_artifact_baseline_dual_control_20260731.py` | `NOREPLACE/SIGNED_ABSENT` | D-dual/P | lane-d-core → lane-d-core |
| 8 | `lane-d-final/candidate/walksafe-artifact-baseline-historical-successor-r007.py` | `tests/walksafe_artifact_baseline_historical_successor_20260731_r007.py` | `NOREPLACE/SIGNED_ABSENT` | D-test-history/P | lane-d-final → lane-d-final |
| 9 | `lane-d-final/candidate/walksafe-artifact-baseline-current-successor-r007.py` | `tests/walksafe_artifact_baseline_current_successor_20260731_r007.py` | `NOREPLACE/SIGNED_ABSENT` | D-test-current/P | lane-d-final → lane-d-final |
| 10 | `after-control/candidate/check-walksafe-project-continuation-v2-4-1.py` | `scripts/check_walksafe_project_continuation_v2_4_1.py` | `NOREPLACE/SIGNED_ABSENT` | continuation/P | after-control → after-control |
| 11 | `after-control/candidate/check-walksafe-goal-graph-v2-4-1.py` | `scripts/check_walksafe_goal_graph_v2_4_1.py` | `NOREPLACE/SIGNED_ABSENT` | goal/P | after-control → after-control |
| 12 | `after-control/candidate/test-walksafe-project-continuation-v2-4-1-successor.py` | `tests/walksafe_project_continuation_v2_4_1_successor_20260731.py` | `NOREPLACE/SIGNED_ABSENT` | direct-cont/P | after-control → after-control |
| 13 | `after-control/candidate/test-walksafe-goal-graph-v2-4-1-successor.py` | `tests/walksafe_goal_graph_v2_4_1_successor_20260731.py` | `NOREPLACE/SIGNED_ABSENT` | direct-goal/P | after-control → after-control |
| 14 | `after-control/candidate/test-walksafe-v2-4-1-seq40-transition-successor.py` | `tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py` | `NOREPLACE/SIGNED_ABSENT` | direct-seq40/P | after-control → after-control |
| 15 | `control-core/candidate/seq40-event-schema.json` | `docs/control/goals/walksafe-completion-graph-v2-4-1/seq40-event-schema.json` | `NOREPLACE/SIGNED_ABSENT` | schema/D | control-core → control-core |
| 16 | `after-control/candidate/test-routing-before-seq39-v2.4.json` | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-before-seq39-v2.4.json` | `NOREPLACE/SIGNED_ABSENT` | before-routing/D | after-control → after-control |
| 17 | `after-control/candidate/test-routing-after-seq40-v2.4.1.json` | `docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-after-seq40-v2.4.1.json` | `NOREPLACE/SIGNED_ABSENT` | after-routing/D | after-control → after-control |
| 18 | `after-control/candidate/superseded-v2.4.0-active-checkpoint.json` | `docs/control/goals/walksafe-completion-graph-v2-4-1/superseded-v2.4.0-active-checkpoint.json` | `NOREPLACE/SIGNED_ABSENT` | archive/D | after-control → after-control |
| 19 | `after-control/candidate/v2.4-supersession-record.json` | `docs/control/goals/walksafe-completion-graph-v2-4-1/v2.4-supersession-record.json` | `NOREPLACE/SIGNED_ABSENT` | supersession-record/D | after-control → after-control |
| 20 | `after-control/candidate/v2.4-supersession-anchor.json` | `docs/control/goals/walksafe-completion-graph-v2-4-1/v2.4-supersession-anchor.json` | `NOREPLACE/SIGNED_ABSENT` | supersession-anchor/D | after-control → after-control |
| 21 | `after-control/candidate/transition-event-seq40.json` | `docs/control/goals/walksafe-completion-graph-v2-4-1/transition-event-seq40.json` | `NOREPLACE/SIGNED_ABSENT` | transition-event/D | after-control → after-control |
| 22 | `after-control/candidate/static-plan-manifest-v2.4.1.json` | `docs/control/goals/walksafe-completion-graph-v2-4-1/static-plan-manifest-v2.4.1.json` | `NOREPLACE/SIGNED_ABSENT` | static-manifest/D | after-control → after-control |
| 23 | `after-control/candidate/control-package-manifest-v2.4.1.json` | `docs/control/goals/walksafe-completion-graph-v2-4-1/control-package-manifest-v2.4.1.json` | `NOREPLACE/SIGNED_ABSENT` | control-package/D | after-control → after-control |
| 24 | `after-control/candidate/README.md` | `docs/control/goals/walksafe-completion-graph-v2-4-1/README.md` | `NOREPLACE/SIGNED_ABSENT` | readme/D | after-control → after-control |
| 25 | `after-control/candidate/full19-successor-contract.json` | `docs/control/goals/walksafe-completion-graph-v2-4-1/full19-successor-contract.json` | `NOREPLACE/SIGNED_ABSENT` | full19/D | after-control → after-control |
| 26 | `apply/candidate/walksafe-project-continuation-checkpoint.json` | `docs/control/walksafe-project-continuation-checkpoint.json` | `CHECKPOINT_CAS_LAST`; `6ec0e4f1771a414989c254eefdb754b2fa384ac1b335ff48197898e31ebd698c/1329415/dev66306/ino16943740/1000:1000/0644/nlink1/seq39` | checkpoint/Q | after-control → apply |

Every row has target uid/gid/mode/nlink/executable exactly from its class, its
actual candidate-source materializer, the named producer EqualityReceipt and
parent transition `EXISTING_PARENT`, except rows15..25 use `AUX_PARENT_001`.
Candidate source prefix maps exactly:

```text
lane-a/candidate -> builders/lane-a-lock-env-builder.py
lane-c/candidate -> builders/lane-c-apply-helper-builder.py
control-core/candidate -> builders/control-core-builder.py
lane-d-core/candidate -> builders/lane-d-core-builder.py
lane-b-final/candidate -> builders/lane-b-final-builder.py
lane-d-final/candidate -> builders/lane-d-final-builder.py
after-control/candidate -> builders/after-control-builder.py
apply/candidate -> builders/apply-envelope-builder.py
```

`candidate_materializer_builder` means the builder that created and sealed the
literal candidate source after build equality. It is not the Stage-C mutator.
Every row separately has constant
`stage_c_target_applier="R007_FENCED_EXACT26_APPLIER"`, whose only operations are
the row operation plus §14 guards/§15 progress.

Canonical N26:

```text
N26_payload = {
  schema_version:"1.0.0",
  contract_id:"WS-PRE-P-R007-NORMATIVE-TARGET-TABLE",
  rows:[the exact 26 expanded NormativeTargetRow objects above]
}
N26 = SHA256(
  ASCII("WS-PRE-P-R007-NORMATIVE-TARGET-TABLE-V1") ||
  NUL || RFC8785_JCS(N26_payload)
)
```

The R007 checker expands every table row with its literal class, builder,
EqualityReceipt role and parent transition before JCS. The exact N26 digest is
computed from the physical canonical payload:

```text
aggregate/build-01/normative-exact26-target-table.json
aggregate/build-02/normative-exact26-target-table.json
aggregate/candidate/normative-exact26-target-table.json
```

The expansion algorithm takes only §6 row cells and exact dictionaries:

```text
class -> target_uid/gid/mode/nlink/executable
producer-name -> literal builders/<name>.py
producer-name -> literal <unit>/candidate/equality-receipt.json
candidate source prefix -> literal materializer dictionary above
all rows -> stage_c_target_applier:"R007_FENCED_EXACT26_APPLIER"
ordinal -> typed operation/before_state dictionary above
row 15..25 parent_transition -> AUX_PARENT_001
other rows parent_transition -> EXISTING_PARENT
```

Composite row2 expands without inference:

```text
producer-name="CONTROL_B_COMPOSITE"
content_producer_builder="builders/apply-envelope-builder.py"
equality_receipts=[
  "control-core/candidate/equality-receipt.json",
  "lane-b-final/candidate/equality-receipt.json",
  "apply/candidate/equality-receipt.json"
]
```

All other producer names map one-to-one to the literal builder/unit in §5 and add
`apply/candidate/equality-receipt.json`; aggregate receipt is a later descendant,
not a row field.

It expands all 26 objects, sorts object keys by RFC8785, preserves row order1..26,
rejects any missing/extra/ambiguous cell and writes the JCS payload bytes. build-01
and build-02 payload bytes must be equal; candidate is a fresh inode with identical
bytes. The later AggregateEqualityReceipt computes and binds N26. The payload itself
contains neither N26, its own Physical, candidate actual hashes nor any descendant,
so there is no self-reference.

At plan time:

```text
N26_BINDING={
  state:"FUTURE_SEALED",
  role_id:"R007_NORMATIVE_EXACT26_TARGET_TABLE_SHA256",
  producer:"builders/aggregate-builder.py",
  canonical_path:"aggregate/candidate/normative-exact26-target-table.json"
}
```

No current 64-hex digest is asserted. Before Stage-A subject seal, the builder
computes N26 from the exact candidate payload, the checker independently recomputes
it, and the AggregateEqualityReceipt, CandidateTargetMap, Stage-B review, X1,
Stage-C receipt and transaction manifest bind the resulting actual digest. The
nine earlier UnitEqualityReceipts bind only the structural template and are
inward inputs of the aggregate receipt; they never claim a future N26. Equality cannot
change table semantics and no fake future candidate hash is inserted into N26.

## 7. runtime-pack logical/physical split and provenance

### 7.1 exact env × build tree

Stage-A subject contains:

```text
runtime-pack/schemas/logical-content-manifest.schema.json
runtime-pack/schemas/physical-materialization-receipt.schema.json
runtime-pack/schemas/transitive-closure.schema.json
runtime-pack/schemas/member-origin-map.schema.json
runtime-pack/schemas/pairwise-distinct-receipt.schema.json
runtime-pack/builders/discover-runtime-inputs.py
runtime-pack/builders/build-runtime-pack.py
runtime-pack/templates/runtime-pack.template.json
runtime-pack/local-combined/build-01/
runtime-pack/local-combined/build-02/
runtime-pack/local-combined/candidate/
runtime-pack/hosted-cpu/build-01/
runtime-pack/hosted-cpu/build-02/
runtime-pack/hosted-cpu/candidate/
```

Each of those six leaf roots contains exact:

```text
root/
rootfs.tar
discovery-result.json
logical-content-manifest.json
transitive-closure.json
member-origin-map.json
physical-materialization-receipt.json
build-receipt.json
raw/discovery/intent.json
raw/discovery/stdout.bin
raw/discovery/stderr.bin
raw/discovery/access-trace.raw
raw/discovery/access-trace.json
raw/discovery/result.json
raw/build/intent.json
raw/build/stdout.bin
raw/build/stderr.bin
raw/build/access-trace.raw
raw/build/access-trace.json
raw/build/result.json
```

Each environment also has
`runtime-pack/<environment>/pairwise-distinct-receipt.json`.
External materialization:

```text
walksafe-pre-p-validation-r007/attempts/<stage-a-attempt-key>/
  local-combined/root/
  local-combined/logical-content-manifest.json
  local-combined/physical-materialization-receipt.json
  hosted-cpu/root/
  hosted-cpu/logical-content-manifest.json
  hosted-cpu/physical-materialization-receipt.json
  environment-attempt-manifest.json
```

`environment-attempt-manifest.json` is published only after both external roots
and both internal pairwise receipts seal:

```text
EnvironmentAttemptManifest={
 schema_version:"WS-PRE-P-R007-ENVIRONMENT-ATTEMPT-V1",
 attempt_key,plan:FilePhysical,plan_reviews:[FilePhysical exact2],
 source_seed_binding:FilePhysical,
 environments:[
  {environment:"local-combined",
   candidate_build_receipt:FilePhysical,
   candidate_logical_manifest:FilePhysical,
   candidate_physical_receipt:FilePhysical,
   pairwise_distinct_receipt:FilePhysical,
   external_root:DirPhysical,
   external_logical_manifest:FilePhysical,
   external_physical_receipt:FilePhysical},
  {environment:"hosted-cpu",
   candidate_build_receipt:FilePhysical,
   candidate_logical_manifest:FilePhysical,
   candidate_physical_receipt:FilePhysical,
   pairwise_distinct_receipt:FilePhysical,
   external_root:DirPhysical,
   external_logical_manifest:FilePhysical,
   external_physical_receipt:FilePhysical}
 ],
 exact_exclusions:["environment-attempt-manifest.json"],
 environment_set_sha256,status:"SEALED",signature
}
```

The external environment issuer publishes it with PUBLISH_ONCE_AND_ADOPT; it
excludes itself and points only inward. CandidateReviewSubjectManifest binds its
FilePhysical, Stage-A review binds that subject manifest, and Stage-B accepts
environment inputs only through this chain.

The byte-equal logical manifest contains only:

```text
schema_version, environment, logical_root_id
members:[{relative_path,type,mode,size,sha256,relative_link_target,
          origin_role,closure_reason}]
member_set_sha256, closure_graph_sha256, origin_map_sha256
```

It contains no dev, inode, uid, gid, nlink, host absolute path or extraction path.
The separate physical receipt contains:

```text
schema_version, environment, slot, logical_manifest:FilePhysical
materialization_root:DirPhysical
members:[{relative_path,dev,inode,mnt_id,uid,gid,mode,nlink,size,sha256}]
extraction_before, extraction_after, status, signature
```

The other seeded runtime schemas are strict:

```text
TransitiveClosure={
 schema_version,environment,slot,
 root_roles:[{role_id,source:FilePhysical}],
 nodes:[{node_id,member_path,type,mode,size,sha256,origin_role}],
 edges:[{ordinal,from_node,to_node,resolution_kind,resolver:FilePhysical}],
 node_set_sha256,edge_set_sha256,graph_sha256,status:"SEALED",signature
}
MemberOriginMap={
 schema_version,environment,slot,
 members:[{member_path,origin:FilePhysical,acquisition_root,
           acquisition_method,lock_or_archive:FilePhysical,trace_refs}],
 member_set_sha256,origin_set_sha256,status:"SEALED",signature
}
PairwiseDistinctReceipt={
 schema_version,environment,
 build_01:FilePhysical,build_02:FilePhysical,
 candidate:FilePhysical,external:FilePhysical,
 comparisons:[{left,right,shared_regular_inode_count:0} exact6],
 all_regular_nlink1:true,logical_manifests_equal:true,
 status:"PASS",signature
}
RuntimeInputManifest={
 schema_version,environment,attempt_key,
 plan:FilePhysical,source_seed_binding:FilePhysical,
 runtime_discovery_builder:FilePhysical,
 runtime_pack_builder:FilePhysical,
 runtime_pack_template:FilePhysical,
 full19_contract:FilePhysical,
 regression_contract:FilePhysical,exact6_contract:FilePhysical,
 executable_roles:[{role_id,source:FilePhysical}],
 environment_roles:[{
   role_id,logical_manifest:FilePhysical,
   physical_receipt:FilePhysical,root:DirPhysical,ordered_member_prefix
 }],
 package_lock_roles:[{role_id,source:FilePhysical}],
 acquisition_roots:[DirPhysical],denied_roots,
 ordered_source_roles:[
  {state:"ACTUAL",role_id,source:FilePhysical|DirManifestPhysical} |
  {state:"LATE_BOUND_ROLE",role_id,
   constructor:"F"|"P"|"R"|"RUNTIME_ACTUAL"|"LIVE_TRANSACTION_DATA",
   allowed_phase:"STAGE_B_INTENT_ONLY"|"STAGE_C_INTENT_ONLY"}
 ],
 ordered_sandbox_aliases,
 source_set_sha256,signature
}
RuntimeDiscoveryResult={
 schema_version:"WS-PRE-P-R007-RUNTIME-DISCOVERY-RESULT-V1",
 environment,slot,input_manifest:FilePhysical,
 command_manifest:FilePhysical,argv,cwd,env,
 ordered_root_roles,ordered_nodes,ordered_edges,
 ordered_origin_bindings,closure_graph_sha256,origin_set_sha256,
 evidence:{intent,stdout,stderr,raw_trace,normalized_trace}
   as exact5 FilePhysical,
 status:"PASS",signature
}
RuntimePackBuildReceipt={
 schema_version,environment,slot,
 discovery_command_manifest:FilePhysical,
 build_command_manifest:FilePhysical,input_manifest:FilePhysical,
 discovery_command,build_command,discovery_result:FilePhysical,
 discovery_evidence:{intent,stdout,stderr,raw_trace,normalized_trace,result}
   as exact6 FilePhysical,
 build_evidence:{intent,stdout,stderr,raw_trace,normalized_trace,result}
   as exact6 FilePhysical,
 logical_manifest:FilePhysical,physical_receipt:FilePhysical,
 transitive_closure:FilePhysical,member_origin_map:FilePhysical,
 rootfs_tar:FilePhysical,status:"PASS",signature
}
```

`executable_roles` contains regular executable files only. Prebuilt directory
closures such as JDK, Android SDK and Gradle seed use `environment_roles`; an
E constructor tagged `PREBUILT_RUNTIME` resolves exactly to one such Stage-A
role. Stage-B-derived Web type seed is forbidden in RuntimeInputManifest and is
resolved only by the `STAGE_B_DERIVED` E branch in §9 after its equality receipt.
In
particular the Gradle seed root is
`walksafe-pre-p-validation-r007/attempts/<stage-a-key>/<environment>/
root/env/gradle-home-seed`, with its runtime logical manifest and external
physical receipt.

Corresponding regular members in build-01/build-02/candidate/external root are
pairwise distinct `(dev,inode)`, each `nlink=1`. The distinctness receipt binds all
four physical receipts. Logical equality cannot be replaced by physical equality,
and physical distinctness cannot override logical bytes.

### 7.2 authoritative typed closure

Per environment, `discovery-intent.json` freezes seed argv/cwd/env, resolver and
tracer actual identities, allowed acquisition roots and source package/archive/lock
Physical references. The authoritative closure builder starts only from builder,
executable, environment and package-lock closure roles needed by BEFORE/AFTER,
full19, regression and exact6. Invocation-data roles remain typed late-bound and
are not runtime-pack membership inputs. It resolves:

- script shebang interpreter and its own closure;
- Python executable, stdlib, imported package/module/data, distributions,
  RECORD and subprocess targets;
- ELF `PT_INTERP`, recursive `DT_NEEDED`, loader search path and NSS modules;
- Node/npm executable, package-lock-selected package files, native addon closure;
- Java/JDK, Gradle wrapper/JAR/plugin dependency and Android SDK/build-tools/platform;
- locale, timezone, NSS, CA and explicitly consumed configuration.

Every member has an origin Physical, resolution edge and selection reason.
Ambiguous resolution or unconstrained dynamic load fails. Observed syscall trace is
detection-only: it can expose a missing frozen edge but cannot expand the allowlist.
Trace drop/detach/overflow or a member with no authoritative origin fails.

`discover-runtime-inputs.py` returns the closure/origin candidate over captured
stdout. The supervisor publishes the five preceding discovery evidence files,
the signed sibling `discovery-result.json`, then raw/discovery `result.json`
binding that semantic result; this is the exact discovery six-file evidence.
`build-runtime-pack.py` consumes only that sealed semantic result,
materializes rootfs/env, and publishes separate build six-file evidence,
BuildReceipt, logical manifest and physical receipt. Neither builder can read the
other slot or observed trace to add a member. Discovery/build receipt paths are
mutually one-way and each receipt excludes itself.

After the after-control candidate seals, both RuntimeInputManifests bind the same
three already-actual Stage-A role contracts:

```text
full19_contract =
  after-control/candidate/full19-successor-contract.json
regression_contract =
  after-control/candidate/regression-successor-contract.json
exact6_contract =
  after-control/candidate/stage-c-exact6-contract.json
```

They also bind the copied runtime builders/template named in their schema. No
field points to a Stage-B product or to the later runtime command manifest.
After all executable/environment/package-lock closure roles for those three
contracts are sealed, the issuer
publishes exact `runtime-pack/<environment>/runtime-input-manifest.json`, and only
then publishes the runtime command manifest described in §5.2. For each
`environment in [local-combined,hosted-cpu]` and
`slot in [build-01,build-02,candidate]`, literal commands are:

```json
[
 "/env/bin/python","-I","-S","-B",
 "runtime-pack/builders/discover-runtime-inputs.py",
 "--template","runtime-pack/templates/runtime-pack.template.json",
 "--inputs","/input/runtime-input-manifest.json",
 "--environment","<environment>","--slot","<slot>"
]
[
 "/env/bin/python","-I","-S","-B",
 "runtime-pack/builders/build-runtime-pack.py",
 "--template","runtime-pack/templates/runtime-pack.template.json",
 "--inputs","/input/runtime-input-manifest.json",
 "--discovery-result",
 "/input/discovery-result.json",
 "--environment","<environment>","--slot","<slot>"
]
```

The six environment/slot values are expanded before command-manifest signing;
sealed argv contains strings only. cwd is literal `/work/subject` and env is the
exact bootstrap env in §5.1. Discovery read allowlist is its builder/template,
RuntimeInputManifest and listed source/acquisition objects; output is only its
raw/discovery six. Build read allowlist is its builder/template/input manifest
and exact sealed discovery result plus the discovery closure's ordered origin/
source FilePhysical set; its normalized read trace must equal that set with only
declared interpreter/runtime reads. The build payload write array is exactly
`["rootfs.tar","logical-content-manifest.json","transitive-closure.json",
"member-origin-map.json"]`. After validation, the supervisor extracts
`rootfs.tar` with fresh inodes to exact `root/`, fsyncs/reopens it, publishes
`physical-materialization-receipt.json`, then `build-receipt.json`; raw/build
contributes the exact six paths in §7.1. Thus the payload never authors a
physical or signed receipt. Network, sibling slot and host fallback are denied.
`build-receipt.json` is supervisor-only, is outside the payload output array,
and binds both discovery/build command manifests, RuntimeInputManifest and both
command intents/results.

CPython is `3.12.13`, Pillow `12.3.0`, pytest `8.4.2`. Node/JDK/SDK identities are
actual sealed pack roles. No fake future hash is accepted.

For row14, closure also contains a read-only logical directory
`/env/gradle-home-seed` with the wrapper distribution, plugin artifacts and all
dependency artifacts selected by wrapper properties, verification metadata and
lockfiles. It is represented by a `DirManifestPhysical` plus the enclosing
runtime-pack physical receipt, never by a FilePhysical. Before each row14
invocation, supervisor makes a fresh-inode copy into that invocation's
`GRADLE_HOME` host scratch, fsyncs it and publishes the exact §8.2
`ScratchSeedReceipt` binding seed/copy logical equality and inode disjointness.
Thus `--offline` never depends on an empty cache or ambient host Gradle state.

### 7.3 frozen host bootstrap

The only host executable used before entering the sandbox:

```text
path=/usr/bin/bwrap
version=bubblewrap 0.11.1
sha256=0abea81db798ebf6b4742ac0664802d97521547a353c2a0dbdc21d76cbbfd2c0
bytes=80424
mode=0755
nlink=1
```

It is lstat/open O_NOFOLLOW/fstat/hash verified before every invocation. Host PATH
lookup and alternative bwrap are forbidden.

## 8. exact sandbox mount, scratch and evidence isolation

### 8.1 original-position runtime layout

The pack rootfs is not mounted at `/runtime`; `/runtime` occurrence in any
invocation argv/environment/mount is zero. Exact bwrap token skeleton:

```text
/usr/bin/bwrap
  --unshare-all
  --die-with-parent
  --new-session
  --clearenv
  --ro-bind <EnvironmentRole.usr> /usr
  --ro-bind <EnvironmentRole.lib> /lib
  --ro-bind <EnvironmentRole.lib64> /lib64
  --ro-bind <EnvironmentRole.bin> /bin
  --ro-bind <EnvironmentRole.etc> /etc
  --ro-bind <EnvironmentRole.env> /env
  --ro-bind <sealed-subject-or-projection> <literal-projection-target>
  --ro-bind <sealed-invocation-input-bundle> /input
  --proc /proc
  --dev /dev
  <exact ScratchBindArray tokens>
  <sorted --setenv name value triples>
  --chdir <literal invocation cwd>
  --
  <exact payload argv tokens>
```

The five rootfs sources are synthetic pack subtrees, never broad host binds.
Python is `/env/bin/python`, Bash `/usr/bin/bash`, Node root `/env/node`, JDK
`/env/jdk`, SDK `/env/android-sdk`; PATH is `/env/bin:/usr/bin:/bin`.

Common exact environment:

```text
HOME=/home/sandbox
PATH=/env/bin:/usr/bin:/bin
LANG=C.UTF-8
LC_ALL=C.UTF-8
TZ=UTC
PYTHONDONTWRITEBYTECODE=1
PYTHONHASHSEED=0
PYTHONNOUSERSITE=1
PYTEST_DISABLE_PLUGIN_AUTOLOAD=1
CI=true
TMPDIR=/tmp
```

Ambient `BASH_ENV`, `ENV`, proxy, credential, SSH, Git credential/config and
package-manager user config variables are unset. Slot additions are in §9.
This common block is exact for Stage B/C. Stage A instead uses the exact smaller
environment in §5.1; applying either block to the other ProjectionRole fails.

Final `SandboxArgv` is a literal JSON token array constructed only as:

```text
fixed prefix through --clearenv
-> exact rootfs/env binds
-> ProjectionRole bind:
   STAGE_A_SEALED_SUBJECT | STAGE_B_RESOLUTION_SUBJECT |
   STAGE_B_SEALED_PROJECTION | STAGE_C_LIVE_ROOT
-> for STAGE_A/STAGE_B_RESOLUTION only, exact BuilderOutputBindArray
-> /input RO bind
-> --proc/--dev tokens
-> exact ScratchBindArray selected by invocation ID
-> one ["--setenv",name,value] triple for every exact env field, sorted by name
-> ["--chdir",literal cwd,"--"]
-> exact payload argv string tokens
```

The strict projection union is:

```text
ProjectionRole =
  {kind:"STAGE_A_SEALED_SUBJECT",source:DirManifestPhysical,
   sandbox_target:"/work/subject"} |
  {kind:"STAGE_B_RESOLUTION_SUBJECT",source:DirManifestPhysical,
   sandbox_target:"/work/subject"} |
  {kind:"STAGE_B_SEALED_PROJECTION",source:DirManifestPhysical,
   sandbox_target:"/work/walksafe"} |
  {kind:"STAGE_C_LIVE_ROOT",source:DirPhysical,
   source_manifest:FilePhysical,sandbox_target:"/work/walksafe"}
ProjectionBindArray=[
  "--ro-bind",ProjectionRole.source.root_physical.path,
  ProjectionRole.sandbox_target
]
```

For the live-root branch `source.path` replaces
`source.root_physical.path`. The environment union is also strict:

```text
EnvironmentRole =
 {kind:"STAGE_A_BOOTSTRAP",
  manifest:FilePhysical,physical_receipt:FilePhysical,
  usr:DirPhysical,lib:DirPhysical,lib64:DirPhysical,
  bin:DirPhysical,etc:DirPhysical,env:DirPhysical} |
 {kind:"RUNTIME_PACK",environment:"local-combined"|"hosted-cpu",
  logical_manifest:FilePhysical,physical_receipt:FilePhysical,
  usr:DirPhysical,lib:DirPhysical,lib64:DirPhysical,
  bin:DirPhysical,etc:DirPhysical,env:DirPhysical}
```

Stage A can use only the genesis `bootstrap-environment/rootfs/{usr,lib,lib64,
bin,etc}` and `bootstrap-environment/env` actual Physical nodes. Stage B/C can
use only the selected runtime pack. Thus Stage A never depends on its future
runtime pack. No final intent contains metavariables. Missing/extra/reordered
token fails.

Stage-A core/runtime builders and Stage-B resolution builders use this same
frozen bwrap executable and rootfs, with the following additional strict
contract:

```text
ProjectionRole="STAGE_A_SEALED_SUBJECT"|"STAGE_B_RESOLUTION_SUBJECT"
projection_target="/work/subject"
BuilderOutputBindArray=[
  "--bind",actual_fresh_host_output_root,literal_sandbox_output_root
]
input_bind=["--ro-bind",actual_invocation_input_bundle,"/input"]
cwd="/work/subject"
scratch_roles=["HOME","TMP"]
```

The supervisor precreates an empty reserved final slot directory in the subject
and binds its DirPhysical-as-empty in the input subject manifest. The sealed
contract-source portion underneath stays read-only; a distinct fresh staging
directory is mounted over that reserved path inside bwrap. The one literal sandbox
output root is `/work/subject/<unit-root>/<slot>` for a core command or
`/work/subject/runtime-pack/<environment>/<slot>` for a runtime command. Core invocation IDs
use literal `unit-<unit>-<slot>` and are the Cartesian expansion of exact10 unit
IDs ×
`[build-01,build-02,candidate]`. Runtime IDs are the expansion of
`[local-combined,hosted-cpu] × [build-01,build-02,candidate] ×
[discover,build]` as
`runtime-<environment>-<slot>-<discover|build>`; the signed command manifest
expands every placeholder and no other ID is accepted. The runtime literal
output root is `/work/subject/runtime-pack/<environment>/<slot>`.

Stage-A host execution state is exactly:

```text
pre-p-validation-convergence-candidate-r007/attempts/<stage-a-key>/
  execution/<invocation-id>/
    input/
    output/
    scratch/HOME/
    scratch/TMP/
    evidence/
```

`input/` is a sealed exact bundle. Core bundles contain
`source-input-manifest.json` plus every ordered input at
`members/<six-digit-input-ordinal>/<source-relative-path>`; directory roles are
their already expanded literal member arrays under the same ordinal. Runtime
bundles contain `runtime-input-manifest.json` plus the manifest's source/origin
objects under the identical ordinal grammar, and build bundles additionally
contain `discovery-result.json`. The input manifest maps every source
FilePhysical to exactly one alias; builders open only aliases, never the host
source path. Every member is a fresh-inode immutable copy whose FilePhysical and
source equality are in the intent. `output/`, HOME and TMP are
fresh mode0700, invocation-owned and empty; `output/` is
`actual_fresh_host_output_root`, while HOME/TMP bind to `/home/sandbox` and
`/tmp`. All four DirPhysical values and bind triples are in the intent.

The payload may create only its exact semantic output member set in that staging
root. Intent, stdout, stderr, raw trace, normalized trace, result, BuildReceipt
and EqualityReceipt are supervisor publications through host-owned pipes and
anchored fds; no evidence directory/fd is visible to the payload. For runtime
discovery the semantic candidate is returned over captured stdout and the
supervisor publishes the normalized discovery result. Runtime build receives
that result only as `/input/discovery-result.json` and writes only the exact
four payload semantic members listed in §7.2. After exit, the supervisor validates the
trace and publishes each staged immutable member plus metadata into the reserved
final slot with PUBLISH_ONCE_AND_ADOPT, fsyncs files and directories bottom-up,
reopens the complete member set, then seals the slot. Core invocation membership
is semantic outputs + raw six + BuildReceipt; EqualityReceipt is published later.
Runtime discover and build have different staging roots and disjoint append-only
final members: discover publishes top-level `discovery-result.json` plus
`raw/discovery/<six>`; build publishes the four payload members, supervisor-
materialized `root/`, two supervisor receipts and `raw/build/<six>`. Neither can replace the
other's members. The live working tree and authority root are not mounted; only
the reviewed sealed candidate subject and bootstrap environment are mounted
read-only. Any second RW bind, preexisting staging member, sibling output,
host path token or subject write outside the selected mount fails before subject
seal.

Stage-B resolution uses the same rules under:

```text
pre-p-validation-convergence-authority-resolved-r007/
  attempts/<stage-b-key>/subject/execution/<invocation-id>/
    input/
    output/
    scratch/HOME/
    scratch/TMP/
    evidence/
```

Its invocation IDs are exactly
`resolved-<after-control|apply|regression-final>-<build-01|build-02|candidate>`.
The input bundle has exactly one signed manifest at
`/input/resolved-after-control.inputs.json`,
`/input/resolved-apply.inputs.json` or
`/input/regression-final.inputs.json`, matching the unit, plus every manifest
input at `/input/members/<six-digit-input-ordinal>/<source-relative-path>` using
the same fresh-copy/equality rule. The exact Stage-A review is one such copied
member; the journal itself is never mounted or read by the builder. It mounts the selected
runtime-pack EnvironmentRole, never the bootstrap EnvironmentRole. The reviewed
Stage-B builder/template subject remains RO at `/work/subject`; only the one
fresh output staging bind is RW. Publication into reserved final slots follows
the same supervisor-only evidence and NOREPLACE member rules.

### 8.2 exact writable scratch

Source/live projection remains read-only. Invocation manifest selects only:

| scratch role | sandbox target | consumers |
|---|---|---|
| `HOME` | `/home/sandbox` | all |
| `TMP` | `/tmp` | all |
| `NPM_CACHE` | `/home/sandbox/.npm-cache` | full19 6..12 |
| `GRADLE_HOME` | `/home/sandbox/.gradle` | full19 14 |
| `GATEWAY_DIST` | `/work/walksafe/apps/android-gateway/dist` | 6..8 |
| `GATEWAY_COVERAGE` | `/work/walksafe/apps/android-gateway/coverage` | 7 |
| `GATEWAY_NODE_CACHE` | `/work/walksafe/apps/android-gateway/node_modules/.cache` | 6..8 |
| `GATEWAY_TSBUILDINFO` | `/work/walksafe/apps/android-gateway/tsconfig.tsbuildinfo` | 6,8 |
| `WEB_NEXT` | `/work/walksafe/apps/web/.next` | 9..12 |
| `WEB_COVERAGE` | `/work/walksafe/apps/web/coverage` | 9 |
| `WEB_NODE_CACHE` | `/work/walksafe/apps/web/node_modules/.cache` | 9..12 |
| `WEB_TSBUILDINFO` | `/work/walksafe/apps/web/tsconfig.tsbuildinfo` | 11,12 |
| `ANDROID_DOT_GRADLE` | `/work/walksafe/apps/android/.gradle` | 14 |
| `ANDROID_KOTLIN` | `/work/walksafe/apps/android/.kotlin` | 14 |
| `ANDROID_ROOT_BUILD` | `/work/walksafe/apps/android/build` | 14 |
| `ANDROID_APP_BUILD` | `/work/walksafe/apps/android/app/build` | 14 |

Each source is a fresh same-invocation object under the external projection
scratch root: directory mode0700 except the two `*_TSBUILDINFO` sources, which are
regular0600/nlink1/size0. All are empty except row14 `GRADLE_HOME` and rows11/12
`WEB_NEXT`. Row14 is the exact fresh-inode clone of `/env/gradle-home-seed`.
Rows11/12 are separate fresh-inode clones of the environment's sealed
`WEB_NEXT_TYPE_SEED`; the seed/scratch root represents `apps/web/.next` and its
initial logical members are exactly `types/routes.d.ts` and
`types/validator.ts` plus `types/cache-life.d.ts`, ordered exactly
`cache-life.d.ts`, `routes.d.ts`, `validator.ts` by UTF-8 relative path. Each
seeded scratch has its own signed
ScratchSeedReceipt. It is bound RW over the exact target after the source RO
bind. A slot may select only its rows. Python slots select HOME and TMP but no
worktree scratch; pycache is disabled and pytest uses `-p no:cacheprovider`.

```text
ScratchSeedReceipt={
 schema_version:"WS-PRE-P-R007-SCRATCH-SEED-V1",
 stage_b_attempt_key,environment,invocation_id,
 role_id:
   "ROW14_GRADLE_SCRATCH_SEED_RECEIPT" |
   "ROW11_WEB_NEXT_TYPE_SEED_RECEIPT" |
   "ROW12_WEB_NEXT_TYPE_SEED_RECEIPT",
 seed_logical_manifest:FilePhysical,
 seed_physical_receipt:FilePhysical,
 seed_root:DirPhysical,
 scratch_logical_manifest:FilePhysical,
 scratch_physical_receipt:FilePhysical,
 scratch_root:DirPhysical,
 ordered_directories:[
   {relative_path,seed:DirPhysical,scratch:DirPhysical}
 ],
 ordered_files:[
   {relative_path,seed:FilePhysical,scratch:FilePhysical,
    byte_equal:true,inode_distinct:true}
 ],
 member_count,member_path_set_sha256,member_content_set_sha256,
 no_extra_members:true,all_scratch_regular_nlink1:true,
 cross_seed_shared_regular_inode_count:0,
 cross_invocation_shared_regular_inode_count:0,
 file_fsyncs:true,directories_bottom_up_fsynced:true,parent_fsynced:true,
 status:"PASS",signature
}
```

Exact host scratch/evidence roots:

```text
walksafe-pre-p-validation-r007/stage-b-attempts/<stage-b-key>/
  projections/<environment>/scratch/<invocation-id>/<scratch-role>
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-authority-resolved-r007/
  attempts/<stage-b-key>/subject/
  projections/<environment>/raw/<invocation-id>/<six evidence files>
  projections/<environment>/seeds/web-next-types/
    .next/types/cache-life.d.ts
    .next/types/routes.d.ts
    .next/types/validator.ts
    logical-manifest.json
    physical-materialization-receipt.json
    build-receipt.json
  projections/<environment>/raw/web-next-type-seed/<six evidence files>
  projections/<environment>/scratch-seed-receipts/
    full19-011/WEB_NEXT.logical-manifest.json
    full19-011/WEB_NEXT.physical-materialization-receipt.json
    full19-011/WEB_NEXT.json
    full19-012/WEB_NEXT.logical-manifest.json
    full19-012/WEB_NEXT.physical-materialization-receipt.json
    full19-012/WEB_NEXT.json
    full19-014/GRADLE_HOME.logical-manifest.json
    full19-014/GRADLE_HOME.physical-materialization-receipt.json
    full19-014/GRADLE_HOME.json

walksafe-pre-p-authority-r007/journal/attempts/
  <stage-c|recovery-stage-c>/<executor-attempt-key>/
  scratch/<transaction-id>/<invocation-id>/<scratch-role>
```

The exact canonical role order is:

```text
HOME,TMP,NPM_CACHE,GRADLE_HOME,
GATEWAY_DIST,GATEWAY_COVERAGE,GATEWAY_NODE_CACHE,GATEWAY_TSBUILDINFO,
WEB_NEXT,WEB_COVERAGE,WEB_NODE_CACHE,WEB_TSBUILDINFO,
ANDROID_DOT_GRADLE,ANDROID_KOTLIN,ANDROID_ROOT_BUILD,ANDROID_APP_BUILD
```

For a selected invocation, `ScratchBindArray` is the concatenation, in that order,
of one literal triple per selected role:

```text
["--bind", actual_host_scratch_path(role), literal_sandbox_target(role)]
```

There is no other scratch token form. File mounts use the same three tokens.
Before intent seal, supervisor requires each actual host source and sandbox target
mountpoint to have the same declared file/directory type. It also requires source
uid/gid/mode/emptiness (or the exact row11/12/14 seed receipt), anchored path and
fresh-invocation ownership. Projection
builder creates every worktree target mountpoint as exact empty directory or,
for `*_TSBUILDINFO`, exact empty regular file before sealing. HOME contains the
predeclared cache/Gradle child mountpoints for the selected row; TMP is empty.
Worktree and HOME manifests bind type/mode/emptiness; row11/12 Web and row14
Gradle manifests instead bind their exact seeded members. The Web seed/scratch
root itself represents `.next`; its receipt has exact one ordered child directory
`types` and the exact three files
members above. The Gradle receipt expands every wrapper/plugin/dependency cache
member from its seed manifest. Scratch modes are normalized to directory0700,
nonexecutable regular0600 and executable regular0700 while relative path, type,
content and executable bit remain equal. `R(role_id)` resolves
to the canonical per-environment receipt path displayed above and the sealed
intent binds that receipt FilePhysical plus the exact ScratchBindArray triple.
`SandboxIntent.scratch_bindings` is a strict array of
`{ordinal,role_id,host_source:DirPhysical|FilePhysical,sandbox_target,
object_type,seed_receipt:FilePhysical|null}` and is byte-for-byte equivalent to
the §8.2 role order and actual bwrap triples. Its
`resolved_consumers:[{kind:"ENVIRONMENT_CLOSURE"|"RUNTIME_RECEIPT",role_id,
actual:[FilePhysical]}]` maps every E/R role; every R has exactly one same-role
seed receipt and every unseeded role has null. The receipt binds invocation/root
but never its future intent.
Absent, unauthorized nonempty, wrong-type,
aliased or reused source/target fails before bwrap.

Exact per-full19 selections, in canonical order, are:

```text
001..005 = [HOME,TMP]
006 = [HOME,TMP,NPM_CACHE,GATEWAY_DIST,GATEWAY_NODE_CACHE,GATEWAY_TSBUILDINFO]
007 = [HOME,TMP,NPM_CACHE,GATEWAY_DIST,GATEWAY_COVERAGE,GATEWAY_NODE_CACHE]
008 = [HOME,TMP,NPM_CACHE,GATEWAY_DIST,GATEWAY_NODE_CACHE,GATEWAY_TSBUILDINFO]
009 = [HOME,TMP,NPM_CACHE,WEB_NEXT,WEB_COVERAGE,WEB_NODE_CACHE]
010 = [HOME,TMP,NPM_CACHE,WEB_NEXT,WEB_NODE_CACHE]
011..012 = [HOME,TMP,NPM_CACHE,WEB_NEXT,WEB_NODE_CACHE,WEB_TSBUILDINFO]
013 = [HOME,TMP]
014 = [HOME,TMP,GRADLE_HOME,ANDROID_DOT_GRADLE,ANDROID_KOTLIN,
       ANDROID_ROOT_BUILD,ANDROID_APP_BUILD]
015..019 = [HOME,TMP]
before-validate,after-validate,regression-a,regression-b,after-repeat = [HOME,TMP]
web-next-type-seed = [HOME,TMP,NPM_CACHE,WEB_NEXT,WEB_NODE_CACHE]
stage-c exact6 001..006 = [HOME,TMP]
```

The displayed `<...>` strings above are construction grammar only. Every sealed
intent substitutes canonical actual host paths, keys, environment, invocation ID
and transaction ID; it contains no angle-bracket metavariable. After the scratch
triples, all `--setenv` triples are sorted by variable name, then the only
terminator is `["--chdir",literal_cwd,"--"]`, followed by payload tokens.

Pre/post source projection recursive logical digest must be identical. Writes
outside selected scratch and `/tmp`/HOME fail. Scratch is nonauthoritative and
never a target candidate.

### 8.3 payload-invisible evidence

No `/out` path or bind exists. Supervisor owns stdout/stderr pipes and syscall
tracer outside the payload namespace. Intent, trace, normalized trace and result
are written into an unbound host evidence directory. Payload receives no evidence
directory fd. Publisher seals each sibling through PUBLISH_ONCE_AND_ADOPT:

```text
intent.json
stdout.bin
stderr.bin
access-trace.raw
access-trace.json
result.json
```

All six become `FilePhysical`. Missing/dropped trace, tracer kill, pipe
substitution, payload-visible evidence path, `/out` or `/runtime` literal fails.

### 8.4 invocation allowlists

Stage-B per environment exact IDs:

```text
before-validate
after-validate
web-next-type-seed
full19-001
full19-002
full19-003
full19-004
full19-005
full19-006
full19-007
full19-008
full19-009
full19-010
full19-011
full19-012
full19-013
full19-014
full19-015
full19-016
full19-017
full19-018
full19-019
regression-a
regression-b
after-repeat
```

Stage-C evidence root and exact IDs:

```text
transactions/<transaction-id>/evidence/stage-c-exact6/
  <executor-attempt-key>/
    <invocation-id>/
```

This path is canonical relative to the authority journal root in §3.
`invocation-id` is exactly one of
`001-live-exact26`, `002-activation-seal`, `003-continuation-quick`,
`004-goal-quick`, `005-routing-validate`, `006-control-and-state`; there is no
additional or omitted path level.
Each ID contains exactly the six files in §8.3, including recovery execution.
Existing expected siblings are adopted; wrong/extraneous member is incident.

## 9. full19 literal logical contract

### 9.1 common row and equality

```text
Token = UTF8String |
        {state:"RUNTIME_ACTUAL",role_id:"FULL19_GATE_EVENT_ID"}
Full19Row={
  ordinal,id,impact,commands:[Token arrays],cwd,env_additions,
  executable_roles,input_consumers,timeout_seconds,
  stdout_cap=268435456,stderr_cap=268435456,
  expected_rc=0,assertions:[Assertion],output_oracle_ref
}
Assertion={assertion_id,operator,expected,source_role}
EvidenceExtractor={
 assertion_id,source_role,
 source_kind:
  "COMMAND_RESULT_FIELD"|"TRACE_SET_DERIVATION"|
  "IMMUTABLE_FILE_JCS_POINTER"|"RAW_STDOUT_JCS_POINTER"|
  "REVIEWED_EXIT_CONTRACT",
 evidence_roles:[{invocation_id,member_role}],
 command_ordinals:[integer],json_pointer:string|null,
 normalizer:
  "RFC8785_JCS_VALUE"|"INTEGER"|"BOOLEAN"|"SORTED_UTF8_ARRAY"|
  "SHA256_BYTES"|"RC0_CERTIFICATE",
 exit_contract:{
  executable_roles:[string],input_consumer_roles:[string],
  rule_id:string
 }|null
}
OutputOracle={
 schema_version:"WS-PRE-P-R007-FULL19-OUTPUT-ORACLE-V1",
 logical_full19_digest,
 rows:[{
   ordinal,id,assertions:[Assertion],
   required_output_format:"WS-PRE-P-R007-FULL19-ROW-RESULT-V1",
   extraction_map:[EvidenceExtractor]
 } exact19],
 status:"SEALED",signature
}
Full19RowResultMetadata={
 schema_version:"WS-PRE-P-R007-FULL19-ROW-RESULT-V1",
 ordinal,id,logical_full19_digest,projection_intent_digest
}
Full19ExecutedResultCommon=Full19RowResultMetadata + {
 command_results:[{
   ordinal,argv_digest,rc,signal,timed_out,
   stdout:FilePhysical,stderr:FilePhysical,trace:FilePhysical
 }],
 observations:{exact assertion_id keys and typed derived values},
 assertion_results:[{assertion_id,extractor_digest,actual,passed:boolean}]
}
Full19RowResult =
 Full19ExecutedResultCommon + {
  status:"PASS",failed_command_ordinals:[],failed_assertion_ids:[]
 } |
 Full19ExecutedResultCommon + {
  status:"FAIL",failed_command_ordinals:[integer],
  failed_assertion_ids:[string],failure_reasons:[
   "NONZERO_RC"|"SIGNAL"|"TIMEOUT"|"STDOUT_CAP"|"STDERR_CAP"|
   "TRACE_MISMATCH"|"ASSERTION_MISMATCH"
  ]
 } |
 Full19RowResultMetadata + {
  status:"NOT_RUN",command_results:[],observations:{},assertion_results:[],
  not_run_reason:
   "PRIOR_REQUIRED_FAILURE"|"PRESPAWN_VALIDATION_FAILURE"
 }
```

All use §8 env and cwd `/work/walksafe`. `actual rc0`, nonempty required semantic
output, no FAIL/NOT_RUN/skip and all row assertions are required.

```text
logical_full19_payload={schema_version:"WS-PRE-P-R007-FULL19-V1",
                        rows:[exact19 Full19Row]}
logical_full19_digest=SHA256(
  ASCII("WS-PRE-P-R007-FULL19-LOGICAL-V1") || NUL ||
  RFC8785_JCS(logical_full19_payload)
)
projection_intent_payload={
  logical_full19_digest,
  environment_manifest:FilePhysical,
  environment_physical_receipt:FilePhysical,
  pack_logical_digest,
  projection_manifest:FilePhysical,
  runtime_actual_binding:FilePhysical,
  resolved_consumer_bindings:[
    {constructor:"F"|"P"|"E"|"R",role_id,actual:[FilePhysical]}
  ],
  scratch_bindings:[
    {ordinal,role_id,host_source:DirPhysical|FilePhysical,sandbox_target,
     object_type,seed_receipt:FilePhysical|null}
  ],
  seed_receipts:[FilePhysical]
}
projection_intent_digest=SHA256(
  ASCII("WS-PRE-P-R007-FULL19-PROJECTION-V1") || NUL ||
  RFC8785_JCS(projection_intent_payload)
)
```

Two env logical rows are byte-identical; only environment/pack Physical differs.
Unresolved future hashes/nodeids use typed `FutureSealed`, never fake hex.

### 9.2 changed/meta rows 1..4 and 15..19

| # | ID | exact commands | exact consumers/oracle | timeout |
|---:|---|---|---|---:|
| 1 | `CONTINUATION` | `[["/env/bin/python","-I","-S","-B","scripts/check_walksafe_project_continuation_v2_4_1.py"]]` | checkpoint, v2.4.1 static/control, seq40/supersession; rc0 | 300 |
| 2 | `GOAL_GRAPH` | `[["/env/bin/python","-I","-S","-B","scripts/check_walksafe_goal_graph_v2_4_1.py"]]` | same plus managed Goals; rc0 | 300 |
| 3 | `BASELINE_MATERIALIZATION` | ordered exact3 below | D scripts, historical receipt/posttransition, typed historical preimage and live README/register; three axes PASS | 300 |
| 4 | `ANDROID_GATEWAY_BOUNDARY` | `[["/env/bin/python","-B","-m","pytest","-p","no:cacheprovider","-q","tests/walksafe_android_gateway_public_routes_successor_20260731_r007.py"]]` | config/routes/openapi/existing checker/C direct; fixtures PASS | 300 |
| 15 | `TEST_LAYER_REGISTRY_VALIDATE` | runner array below | runner/checkpoint/AFTER routing/literal132; `132/132/0`, duplicate0/missing0, negative child0 | 600 |
| 16 | `FIELD_AND_RELEASE_PYTEST` | pytest prefix + exact2 | exact2/imports, CPython3.12.13/Pillow12.3.0/pytest8.4.2+lock pair | 1800 |
| 17 | `GOAL_CONTROL_PYTEST` | pytest prefix + direct3 | direct3, frozen nodeids/per-file roles, old v2.4 current0 | 1800 |
| 18 | `CONTROL_AND_TRACE_PYTEST` | exact array below | trace9→B-r007→D2-r007→deselect6, legacy/C helper0 | 1800 |
| 19 | `REPOSITORY_STATE` | exact array below | checkpoint/seq40, Git raw double-read/inventory, lower-only after18, one canonical JSON | 300 |

Row3:

```json
[
 ["/env/bin/python","-I","-S","-B","scripts/check_walksafe_artifact_baseline_historical_event_time_20260731.py","--root","/work/walksafe"],
 ["/env/bin/python","-I","-S","-B","scripts/check_walksafe_artifact_baseline_current_active_20260731.py","--root","/work/walksafe"],
 ["/env/bin/python","-I","-S","-B","scripts/check_walksafe_artifact_baseline_dual_control_20260731.py","--root","/work/walksafe"]
]
```

Row15:

```json
[[
 "/usr/bin/bash",
 "/work/walksafe/scripts/run_walksafe_test_layers_20260711.sh",
 "--layer","validate","--root","/work/walksafe",
 "--checkpoint","docs/control/walksafe-project-continuation-checkpoint.json",
 "--control-selector","AFTER_SEQ40_V241",
 "--routing-manifest",
 "docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-after-seq40-v2.4.1.json"
]]
```

Row15 adds `PYTHON_BIN=/env/bin/python`.

Pytest prefix:

```json
["/env/bin/python","-B","-m","pytest","-p","no:cacheprovider","-q"]
```

Rows04 and16..18 intentionally omit `-I`: their reviewed tests import repository
packages (`model` and `scripts`) from the literal cwd `/work/walksafe`.
`PYTHONNOUSERSITE=1`, cleared `PYTHONPATH`, empty HOME, the synthetic pack and the
exact consumer trace keep that cwd insertion deterministic; adding `-I` or any
other import root fails.

Row16 appends:

```text
tests/test_android_field_session_summary.py
tests/test_release_evidence_gate.py
```

Row17 appends:

```text
tests/walksafe_project_continuation_v2_4_1_successor_20260731.py
tests/walksafe_goal_graph_v2_4_1_successor_20260731.py
tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py
```

Row18 appends after the pytest prefix, in exact order:

```text
tests/test_walksafe_epic01_phase_b_trace_20260722.py
tests/test_walksafe_epic01_phase_c_trace_20260722.py
tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py
tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py
tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py
tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py
tests/test_walksafe_epic02_trace_v2_2_history.py
tests/test_walksafe_epic02_trace_v2_3_history.py
tests/test_walksafe_android_gateway_boundary_20260723.py
tests/walksafe_test_database_preflight_successor_20260731_r007.py
tests/walksafe_artifact_baseline_historical_successor_20260731_r007.py
tests/walksafe_artifact_baseline_current_successor_20260731_r007.py
--deselect=tests/test_walksafe_epic01_phase_b_trace_20260722.py::WalkSafeEpic01PhaseBTraceTest::test_generated_files_are_current_and_deterministic
--deselect=tests/test_walksafe_epic01_phase_c_trace_20260722.py::WalkSafeEpic01PhaseCTraceTest::test_generated_files_are_current_and_deterministic
--deselect=tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py::WalkSafeEpic01PhaseETraceTest::test_generated_files_are_current_and_deterministic
--deselect=tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py::WalkSafeEpic01PhaseFTraceTest::test_generated_files_are_current_and_deterministic
--deselect=tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py::WalkSafeEpic01PhaseGTraceTest::test_generated_files_are_current_and_deterministic
--deselect=tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py::WalkSafeEpic02PhaseATraceTest::test_generated_files_are_current_and_deterministic
```

Row19:

```json
[[
 "/env/bin/python","-I","-S","-B",
 "scripts/check_walksafe_project_continuation_v2_4_1.py",
 "--root","/work/walksafe",
 "--checkpoint","docs/control/walksafe-project-continuation-checkpoint.json",
 "--print-gate-repository-state",
 "--gate-event-id",{"state":"RUNTIME_ACTUAL","role_id":"FULL19_GATE_EVENT_ID"}
]]
```

The runtime scalar becomes one actual string in sealed intent; unresolved/fake hash
fails. All other tokens are strings at contract freeze; sealed intents contain
strings only.

### 9.3 pinned Bash rows 5..14

Every row has exact nine tokens:

```json
["/usr/bin/bash","--noprofile","--norc","-e","-u","-o","pipefail","-c","<COMMAND_NO_LF>"]
```

| # | ID | exact no-LF command | SHA-256 | timeout |
|---:|---|---|---|---:|
| 5 | `NODE_TOOLCHAIN_PRE` | `: "${WALKSAFE_NODE_BIN_DIR:?required}" && WALKSAFE_NODE_ROOT="$(/usr/bin/dirname -- "${WALKSAFE_NODE_BIN_DIR}")" && test "${WALKSAFE_NODE_BIN_DIR}" = "${WALKSAFE_NODE_ROOT}/bin" && python3 -I -S -B scripts/check_walksafe_node_toolchain_20260715.py --node-root "${WALKSAFE_NODE_ROOT}" --lock configs/walksafe_node_toolchain_lock_20260715.json` | `f0693130552260f2926d7ff23f7dbe2277ee12e5ce52b4013ef3c6fc456169dc` | 120 |
| 6 | `GATEWAY_TYPECHECK` | `: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway run typecheck` | `00293c78110b38ed6d77b0bf2a2fcfa0118963f7ea13aa620187fcd498b055b6` | 1200 |
| 7 | `GATEWAY_TEST` | `: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway test` | `a04ae21453c8c7ff4ee40cb3dbbc42275209110e83af6007cb51d734fc50ec05` | 1200 |
| 8 | `GATEWAY_BUILD` | `: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/android-gateway run build` | `efdcbf1392d5607325203a5cfa68c77fbca8545e8631765b46b28685273d15b4` | 1200 |
| 9 | `WEB_TEST` | `: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web test` | `71f525e644d909fc8f0fb327cb822037e6181c6ea40cadccfa3335290bbbc4fa` | 1200 |
| 10 | `WEB_LINT` | `: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run lint` | `0293d54ea860df64ad8bc21214902488a4062f3c0809ea9c7ca69be35fbfa35b` | 1200 |
| 11 | `WEB_TYPECHECK` | `: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run typecheck` | `891b6f5cca3f1fdf65fb11d204a6e522ffb66e5cc29c5314a3354f6983b7b3f0` | 1200 |
| 12 | `WEB_BUILD` | `: "${WALKSAFE_NODE_BIN_DIR:?required}" && PATH="${WALKSAFE_NODE_BIN_DIR}:/usr/bin:/bin" "${WALKSAFE_NODE_BIN_DIR}/npm" --prefix apps/web run build` | `a4c40d0dc8b8c2716a1e28fa2a55210a685a236a6d5450d886d25b261038bca8` | 1200 |
| 13 | `NODE_TOOLCHAIN_POST` | same exact bytes as row5 | `f0693130552260f2926d7ff23f7dbe2277ee12e5ce52b4013ef3c6fc456169dc` | 120 |
| 14 | `ANDROID_UNIT_ASSEMBLE_LINT` | `(cd apps/android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --offline --no-daemon)` | `bb549399e69927f44e2324933ecda6d83f8fa07be27f83615aae5b24658d0baf` | 3600 |

Rows5/13 add `WALKSAFE_NODE_BIN_DIR=/env/node/bin`; rows6..12 add that same field
plus:

```text
NPM_CONFIG_CACHE=/home/sandbox/.npm-cache
NPM_CONFIG_USERCONFIG=/dev/null
NPM_CONFIG_GLOBALCONFIG=/dev/null
```

Row14 adds:

```text
JAVA_HOME=/env/jdk
ANDROID_HOME=/env/android-sdk
ANDROID_SDK_ROOT=/env/android-sdk
GRADLE_USER_HOME=/home/sandbox/.gradle
```

Rows5/13 `python3` resolves only `/env/bin/python3`; dirname/bash/env/sh only from
synthetic rootfs. Consumers:

### 9.4 exact consumer arrays

Consumer constructors are canonical typed values:

```text
F(path)={kind:"FILE",path}
P(path,role_id)={kind:"PREFIX_MANIFEST",path,role_id}
E(role_id)=
 {kind:"ENVIRONMENT_CLOSURE",phase:"PREBUILT_RUNTIME",role_id} |
 {kind:"ENVIRONMENT_CLOSURE",phase:"STAGE_B_DERIVED",role_id}
R(role_id)={kind:"RUNTIME_RECEIPT",role_id}
```

`F` path is one exact file. `P` is not a glob. It remains a sealed typed
path/role pair in the Stage-A logical contract; it resolves in the later sealed
Stage-B projection manifest to one sorted recursive literal member array and actual
digest. `E` resolves to one sealed runtime logical manifest plus its invocation
physical receipt. All displayed ordinary E roles are PREBUILT_RUNTIME;
`E("WEB_NEXT_TYPE_SEED")` is the sole STAGE_B_DERIVED role and resolves to the
per-environment WebNextTypeSeedBuildReceipt, seed logical manifest/physical
receipt/root and the cross-environment equality receipt in §10. Future outputs
remain typed `FutureSealed` until produced. The
following arrays are part of each Full19Row and their order is normative:

```text
01=[
 F("scripts/check_walksafe_project_continuation_v2_4_1.py"),
 F("docs/control/walksafe-project-continuation-checkpoint.json"),
 P("docs/control/goals/walksafe-completion-graph-v2-4-1",
   "CHECKPOINT_V241_STATIC_CONTROL_SEQ40_SUPERSESSION_SET"),
 E("PYTHON_RUNTIME")
]
02=[
 F("scripts/check_walksafe_goal_graph_v2_4_1.py"),
 F("docs/control/walksafe-project-continuation-checkpoint.json"),
 P("docs/control/goals/walksafe-completion-graph-v2-4-1",
   "CHECKPOINT_V241_STATIC_CONTROL_SEQ40_SUPERSESSION_SET"),
 P("docs/control/goals","CHECKPOINT_MANAGED_GOALS_SET"),
 E("PYTHON_RUNTIME")
]
03=[
 F("scripts/check_walksafe_artifact_baseline_historical_event_time_20260731.py"),
 F("scripts/check_walksafe_artifact_baseline_current_active_20260731.py"),
 F("scripts/check_walksafe_artifact_baseline_dual_control_20260731.py"),
 F("docs/control/baselines/walksafe-artifact-baseline-approval-20260722-r001.json"),
 F("docs/control/baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json"),
 F("docs/control/baselines/walksafe-artifact-baseline-application-temporal-provenance-supplement-20260722-r001.json"),
 F("docs/control/baselines/walksafe-artifact-baseline-candidate-20260721-r001.json"),
 F("docs/control/baseline-candidates/walksafe-artifact-baseline-candidate-20260722-r001.json"),
 F("docs/README.md"),
 F("docs/deliverables/00-control/README.md"),
 F("docs/deliverables/00-control/artifact-change-log.json"),
 F("docs/deliverables/00-control/artifact-register.json"),
 E("PYTHON_RUNTIME")
]
04=[
 F("tests/walksafe_android_gateway_public_routes_successor_20260731_r007.py"),
 F("scripts/check_walksafe_android_gateway_boundary_20260723.py"),
 F("apps/android-gateway/src/config.ts"),
 F("apps/android-gateway/src/routes.ts"),
 F("apps/android-gateway/openapi.json"),
 P("apps/android-gateway/test","GATEWAY_BOUNDARY_FIXTURE_SET"),
 E("PYTHON_RUNTIME")
]
05=[
 F("scripts/check_walksafe_node_toolchain_20260715.py"),
 F("configs/walksafe_node_toolchain_lock_20260715.json"),
 E("NODE_ENVIRONMENT")
]
06=[
 F("apps/android-gateway/package.json"),
 F("apps/android-gateway/package-lock.json"),
 F("apps/android-gateway/tsconfig.json"),
 F("apps/android-gateway/server.ts"),
 P("apps/android-gateway/src","GATEWAY_SOURCE_SET"),
 P("apps/android-gateway/test","GATEWAY_TEST_SET"),
 P("apps/android-gateway/node_modules","GATEWAY_NODE_MODULE_SET"),
 E("NODE_ENVIRONMENT")
]
07=[
 F("apps/android-gateway/package.json"),
 F("apps/android-gateway/package-lock.json"),
 F("apps/android-gateway/tsconfig.json"),
 F("apps/android-gateway/server.ts"),
 P("apps/android-gateway/src","GATEWAY_SOURCE_SET"),
 P("apps/android-gateway/test","GATEWAY_TEST_SET"),
 P("apps/android-gateway/node_modules","GATEWAY_NODE_MODULE_SET"),
 E("NODE_ENVIRONMENT")
]
08=[
 F("apps/android-gateway/package.json"),
 F("apps/android-gateway/package-lock.json"),
 F("apps/android-gateway/tsconfig.json"),
 F("apps/android-gateway/server.ts"),
 P("apps/android-gateway/src","GATEWAY_SOURCE_SET"),
 P("apps/android-gateway/test","GATEWAY_TEST_SET"),
 P("apps/android-gateway/node_modules","GATEWAY_NODE_MODULE_SET"),
 E("NODE_ENVIRONMENT")
]
09=[
 F("apps/web/package.json"),F("apps/web/package-lock.json"),
 F("apps/web/tsconfig.json"),F("apps/web/eslint.config.mjs"),
 F("apps/web/next.config.mjs"),
 F("apps/web/next-env.d.ts"),F("apps/web/proxy.ts"),
 F("apps/web/legacy-runtime-boundary.ts"),
 F("scripts/check_frontend_policy_suite.sh"),
 F("scripts/check_frontend_admin_report_summary_policy_20260525.sh"),
 F("scripts/check_frontend_api_client_contract_policy_20260711.sh"),
 F("scripts/check_frontend_field_telemetry_policy_20260711.sh"),
 F("scripts/check_frontend_motion_projection_policy_20260711.sh"),
 F("scripts/check_frontend_motion_roi_policy_20260526.sh"),
 F("scripts/check_frontend_navigation_destination_policy_20260525.sh"),
 F("scripts/check_frontend_navigation_guidance_policy_20260524.sh"),
 F("scripts/check_frontend_pwa_policy_20260526.sh"),
 F("scripts/check_frontend_risk_evaluator_policy_20260523.sh"),
 F("scripts/check_frontend_route_progress_policy_20260525.sh"),
 F("scripts/check_frontend_settings_privacy_20260526.sh"),
 F("scripts/check_frontend_step_length_policy_20260525.sh"),
 F("scripts/check_frontend_walksafe_test_log_policy_20260701.sh"),
 P("apps/web/app","WEB_APP_SET"),P("apps/web/lib","WEB_LIB_SET"),
 P("apps/web/types","WEB_TYPE_SET"),P("apps/web/tests","WEB_TEST_SET"),
 P("apps/web/public","WEB_PUBLIC_SET"),
 P("apps/web/node_modules","WEB_NODE_MODULE_SET"),
 F("scripts/check_navigation_reroute_gate_20260525.py"),
 E("NODE_ENVIRONMENT"),E("PYTHON_RUNTIME")
]
10=[
 F("apps/web/package.json"),F("apps/web/package-lock.json"),
 F("apps/web/tsconfig.json"),F("apps/web/eslint.config.mjs"),
 F("apps/web/next.config.mjs"),
 F("apps/web/next-env.d.ts"),F("apps/web/proxy.ts"),
 F("apps/web/legacy-runtime-boundary.ts"),
 P("apps/web/app","WEB_APP_SET"),P("apps/web/lib","WEB_LIB_SET"),
 P("apps/web/types","WEB_TYPE_SET"),P("apps/web/tests","WEB_TEST_SET"),
 P("apps/web/node_modules","WEB_NODE_MODULE_SET"),E("NODE_ENVIRONMENT")
]
11=[
 F("apps/web/package.json"),F("apps/web/package-lock.json"),
 F("apps/web/tsconfig.json"),F("apps/web/eslint.config.mjs"),
 F("apps/web/next.config.mjs"),
 F("apps/web/next-env.d.ts"),F("apps/web/proxy.ts"),
 F("apps/web/legacy-runtime-boundary.ts"),
 P("apps/web/app","WEB_APP_SET"),P("apps/web/lib","WEB_LIB_SET"),
 P("apps/web/types","WEB_TYPE_SET"),P("apps/web/tests","WEB_TEST_SET"),
 P("apps/web/node_modules","WEB_NODE_MODULE_SET"),E("NODE_ENVIRONMENT"),
 E("WEB_NEXT_TYPE_SEED"),R("ROW11_WEB_NEXT_TYPE_SEED_RECEIPT")
]
12=[
 F("apps/web/package.json"),F("apps/web/package-lock.json"),
 F("apps/web/tsconfig.json"),F("apps/web/eslint.config.mjs"),
 F("apps/web/next.config.mjs"),
 F("apps/web/next-env.d.ts"),F("apps/web/proxy.ts"),
 F("apps/web/legacy-runtime-boundary.ts"),
 P("apps/web/app","WEB_APP_SET"),P("apps/web/lib","WEB_LIB_SET"),
 P("apps/web/types","WEB_TYPE_SET"),
 P("apps/web/tests","WEB_TEST_SET"),
 P("apps/web/public","WEB_PUBLIC_SET"),
 P("apps/web/node_modules","WEB_NODE_MODULE_SET"),E("NODE_ENVIRONMENT"),
 E("WEB_NEXT_TYPE_SEED"),R("ROW12_WEB_NEXT_TYPE_SEED_RECEIPT")
]
13=[
 F("scripts/check_walksafe_node_toolchain_20260715.py"),
 F("configs/walksafe_node_toolchain_lock_20260715.json"),
 E("NODE_ENVIRONMENT")
]
14=[
 F("apps/android/gradlew"),
 F("apps/android/gradle/wrapper/gradle-wrapper.jar"),
 F("apps/android/gradle/wrapper/gradle-wrapper.properties"),
 F("apps/android/settings.gradle.kts"),F("apps/android/build.gradle.kts"),
 F("apps/android/app/build.gradle.kts"),F("apps/android/app/gradle.lockfile"),
 F("apps/android/adminapp/build.gradle.kts"),F("apps/android/adminapp/gradle.lockfile"),
 F("apps/android/gradle/verification-metadata.xml"),
 P("apps/android/app/src","ANDROID_APP_SOURCE_SET"),
 P("apps/android/adminapp","ANDROID_ADMINAPP_SOURCE_SET"),
 E("JDK_ENVIRONMENT"),E("ANDROID_SDK_ENVIRONMENT"),
 E("GRADLE_HOME_SEED_ENVIRONMENT"),
 R("ROW14_GRADLE_SCRATCH_SEED_RECEIPT")
]
15=[
 F("scripts/run_walksafe_test_layers_20260711.sh"),
 F("docs/control/walksafe-project-continuation-checkpoint.json"),
 F("docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-after-seq40-v2.4.1.json"),
 P(".","ROUTING_LITERAL132_SET"),E("PYTHON_RUNTIME")
]
16=[
 F("tests/test_android_field_session_summary.py"),
 F("tests/test_release_evidence_gate.py"),
 F("tests/requirements.lock"),
 F("tests/general-quality-cp312-linux-x86_64-cpu.lock"),
 F("configs/walksafe_node_toolchain_lock_20260715.json"),
 F("docs/testing/release_evidence.example.json"),
 F("model/two_model_runtime.py"),
 P("scripts","ROW16_TRANSITIVE_IMPORT_SET"),
 P(".git","ROW16_GIT_INVENTORY_SET"),
 E("PYTHON_RUNTIME"),E("GIT_ENVIRONMENT")
]
17=[
 F("tests/walksafe_project_continuation_v2_4_1_successor_20260731.py"),
 F("tests/walksafe_goal_graph_v2_4_1_successor_20260731.py"),
 F("tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py"),
 F("scripts/check_walksafe_project_continuation_v2_4_1.py"),
 F("scripts/check_walksafe_goal_graph_v2_4_1.py"),
 F("docs/control/walksafe-project-continuation-checkpoint.json"),
 P("docs/control/goals/walksafe-completion-graph-v2-4-1",
   "ROW17_V241_CONTROL_SET"),
 P("docs/control/goals","ROW17_MANAGED_GOALS_SET"),
 F("tests/requirements.lock"),E("PYTHON_RUNTIME")
]
18=[
 F("tests/test_walksafe_epic01_phase_b_trace_20260722.py"),
 F("tests/test_walksafe_epic01_phase_c_trace_20260722.py"),
 F("tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py"),
 F("tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py"),
 F("tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py"),
 F("tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py"),
 F("tests/test_walksafe_epic02_trace_v2_2_history.py"),
 F("tests/test_walksafe_epic02_trace_v2_3_history.py"),
 F("tests/test_walksafe_android_gateway_boundary_20260723.py"),
 F("tests/walksafe_test_database_preflight_successor_20260731_r007.py"),
 F("tests/walksafe_artifact_baseline_historical_successor_20260731_r007.py"),
 F("tests/walksafe_artifact_baseline_current_successor_20260731_r007.py"),
 F("tests/requirements.lock"),
 F("configs/walksafe_product_boundary_20260722.json"),
 P("scripts","ROW18_BUILD_SCRIPT_CLOSURE"),
 P("docs/control","ROW18_CONTROL_DOC_CLOSURE"),
 P(".github/workflows","ROW18_QUALITY_WORKFLOW_SET"),
 F("README.md"),
 P("apps/android","ROW18_ANDROID_PRODUCT_SET"),
 P("apps/android-gateway","ROW18_GATEWAY_PRODUCT_SET"),
 P("apps/web","ROW18_WEB_PRODUCT_SET"),
 P("deploy","ROW18_DEPLOY_SET"),
 E("PYTHON_RUNTIME")
]
19=[
 F("scripts/check_walksafe_project_continuation_v2_4_1.py"),
 F("docs/control/walksafe-project-continuation-checkpoint.json"),
 P("docs/control/goals/walksafe-completion-graph-v2-4-1",
   "LOWER_ONLY_AFTER18_CONTROL_SET"),
 P(".","M_AFTER627_WORKTREE_INVENTORY"),
 P(".git","DOUBLE_READ_GIT_INVENTORY_SET"),E("PYTHON_RUNTIME")
]
```

Every `P` role is materialized as an actual sorted literal path array only after
the relevant source seals, through two non-interchangeable bindings:

```text
StageAClosureRoleBinding={
 role_id,source_snapshot_manifest:FilePhysical,
 ordered_members:[FilePhysical],member_set_sha256,
 purpose:"RUNTIME_CLOSURE_DISCOVERY"
}
StageBInvocationRoleBinding={
 role_id,projection_manifest:FilePhysical,
 ordered_members:[FilePhysical],member_set_sha256,
 purpose:"INVOCATION_READ_ALLOWLIST"
}
```

The first resolves every P role against the frozen Stage-A source snapshot
before runtime discovery, solely to find executable/runtime dependencies. The
second resolves the same logical role against the applicable BEFORE or AFTER
projection after that projection seals and before the Stage-B intent seals.
Their source manifests and purposes must differ; neither digest substitutes for
the other. The intent binds the second role, exact array and digest; trace
equality rejects any read outside it. The Stage-A logical contract contains no
future projection Physical, so no pack→projection→P→pack cycle exists and a
prefix never grants arbitrary recursive access.

### 9.5 exact impact, env, executable and output-oracle rows

Exact env-addition arrays, sorted by name:

```text
E0=[]
E_NODE=[
 {name:"WALKSAFE_NODE_BIN_DIR",value:"/env/node/bin"}
]
E_NPM=[
 {name:"NPM_CONFIG_CACHE",value:"/home/sandbox/.npm-cache"},
 {name:"NPM_CONFIG_GLOBALCONFIG",value:"/dev/null"},
 {name:"NPM_CONFIG_USERCONFIG",value:"/dev/null"},
 {name:"WALKSAFE_NODE_BIN_DIR",value:"/env/node/bin"}
]
E_ANDROID=[
 {name:"ANDROID_HOME",value:"/env/android-sdk"},
 {name:"ANDROID_SDK_ROOT",value:"/env/android-sdk"},
 {name:"GRADLE_USER_HOME",value:"/home/sandbox/.gradle"},
 {name:"JAVA_HOME",value:"/env/jdk"}
]
E_RUNNER=[
 {name:"PYTHON_BIN",value:"/env/bin/python"}
]
```

Executable roles resolve to actual FilePhysical entries in RuntimeInputManifest.
The exact row metadata is:

| # | exact impact | env | exact executable_roles |
|---:|---|---|---|
| 1 | `CHANGED_V241_SEQ40` | E0 | `[PYTHON,CONTINUATION_CHECKER]` |
| 2 | `CHANGED_V241_SEQ40` | E0 | `[PYTHON,GOAL_GRAPH_CHECKER]` |
| 3 | `CHANGED_D_TRIPLE` | E0 | `[PYTHON,HISTORICAL_CHECKER,CURRENT_CHECKER,DUAL_CONTROL_CHECKER]` |
| 4 | `CHANGED_C_HELPER` | E0 | `[PYTHON,PYTEST,GATEWAY_R007_TEST]` |
| 5 | `UNAFFECTED_BYTE_PROOF_SYNTHETIC_PACK` | E_NODE | `[BASH,DIRNAME,PYTHON,NODE_TOOLCHAIN_CHECKER,NODE]` |
| 6 | `UNAFFECTED_BYTE_PROOF_SYNTHETIC_PACK` | E_NPM | `[BASH,NODE,NPM,TSC]` |
| 7 | `UNAFFECTED_BYTE_PROOF_SYNTHETIC_PACK` | E_NPM | `[BASH,NODE,NPM,NODE_TEST_RUNNER,TSC]` |
| 8 | `UNAFFECTED_BYTE_PROOF_SYNTHETIC_PACK` | E_NPM | `[BASH,NODE,NPM,TSC]` |
| 9 | `UNAFFECTED_BYTE_PROOF_SYNTHETIC_PACK` | E_NPM | `[BASH,NODE,NPM,TSC,WEB_TEST_RUNNER,FRONTEND_POLICY_SUITE,PYTHON,NAVIGATION_REROUTE_CHECKER]` |
| 10 | `UNAFFECTED_NO_LF_BYTE_PROOF` | E_NPM | `[BASH,NODE,NPM,ESLINT]` |
| 11 | `UNAFFECTED_BYTE_PROOF_SYNTHETIC_PACK` | E_NPM | `[BASH,NODE,NPM,TSC]` |
| 12 | `UNAFFECTED_BYTE_PROOF_SYNTHETIC_PACK` | E_NPM | `[BASH,NODE,NPM,NEXT]` |
| 13 | `UNAFFECTED_BYTE_PROOF_SYNTHETIC_PACK` | E_NODE | `[BASH,DIRNAME,PYTHON,NODE_TOOLCHAIN_CHECKER,NODE]` |
| 14 | `UNAFFECTED_BYTE_PROOF_SYNTHETIC_PACK` | E_ANDROID | `[BASH,GRADLEW,JAVA]` |
| 15 | `CHANGED_SINGLE_RUNNER_VALIDATE` | E_RUNNER | `[BASH,TEST_LAYER_RUNNER,PYTHON]` |
| 16 | `CHANGED_CURRENT_ENV` | E0 | `[PYTHON,PYTEST,GIT]` |
| 17 | `CHANGED_V241_DIRECT3` | E0 | `[PYTHON,PYTEST]` |
| 18 | `CHANGED_B_D_TRACE9_DESELECT6` | E0 | `[PYTHON,PYTEST]` |
| 19 | `CHANGED_SEQ40_STATE_EVENT` | E0 | `[PYTHON,CONTINUATION_CHECKER,GIT]` |

`FS(role,producer,input_manifest)` expands exactly to the §5 FutureSealed object
and must resolve before the full19 logical payload seals. It never points to the
output oracle it helps produce. Exact deselection and assertion arrays are:

```text
DESELECT6=[
 "tests/test_walksafe_epic01_phase_b_trace_20260722.py::WalkSafeEpic01PhaseBTraceTest::test_generated_files_are_current_and_deterministic",
 "tests/test_walksafe_epic01_phase_c_trace_20260722.py::WalkSafeEpic01PhaseCTraceTest::test_generated_files_are_current_and_deterministic",
 "tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py::WalkSafeEpic01PhaseETraceTest::test_generated_files_are_current_and_deterministic",
 "tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py::WalkSafeEpic01PhaseFTraceTest::test_generated_files_are_current_and_deterministic",
 "tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py::WalkSafeEpic01PhaseGTraceTest::test_generated_files_are_current_and_deterministic",
 "tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py::WalkSafeEpic02PhaseATraceTest::test_generated_files_are_current_and_deterministic"
]
A01=[EQ("RC",0),EQ("CONTINUATION_STATE","PASS"),
     EQ("CHECKPOINT_SEQUENCE",40),EQ("ACTIVE_PACKAGE","v2.4.1")]
A02=[EQ("RC",0),EQ("GOAL_GRAPH_STATE","PASS"),
     EQ("MANAGED_GOALS_MATCH",true),EQ("ACTIVE_PACKAGE","v2.4.1")]
A03=[EQ("RC_VECTOR",[0,0,0]),EQ("HISTORICAL_EVENT_TIME","PASS"),
     EQ("CURRENT_ACTIVE","PASS"),EQ("DUAL_CONTROL","PASS")]
A04=[EQ("RC",0),EQ("FAILED",0),EQ("SKIPPED",0),
     EQ("GATEWAY_BOUNDARY_FIXTURES","PASS")]
A05=[EQ("RC",0),EQ("NODE_LOCK_MATCH",true),EQ("HOST_TOOLCHAIN_READS",0)]
A06=[EQ("RC",0),EQ("TYPE_ERRORS",0),EQ("UNDECLARED_READS",0)]
A07=[EQ("RC",0),EQ("FAILED",0),EQ("SKIPPED",0)]
A08=[EQ("RC",0),EQ("BUILD_ARTIFACT_MANIFEST_MATCH",true)]
A09=[EQ("RC",0),EQ("FAILED",0),EQ("FRONTEND_POLICY_CHILDREN",13)]
A10=[EQ("RC",0),EQ("LINT_ERRORS",0)]
A11=[EQ("RC",0),EQ("TYPE_ERRORS",0)]
A12=[EQ("RC",0),EQ("WEB_BUILD","PASS")]
A13=[EQ("RC",0),EQ("NODE_LOCK_MATCH",true),EQ("PRE_POST_NODE_IDENTITY",true)]
A14=[EQ("RC",0),EQ("GRADLE_TASKS_PASS",3),EQ("OFFLINE_NETWORK_CALLS",0),
     EQ("GRADLE_SEED_RECEIPT","PASS")]
A15=[EQ("RC",0),EQ("DISCOVERED",132),EQ("ASSIGNED",132),
     EQ("UNASSIGNED",0),EQ("DUPLICATE",0),EQ("EXTRA",0),
     EQ("DIRECT",5),EQ("CHILD_EXEC_NEGATIVE",0)]
A16=[EQ("RC",0),EQ("INPUT_TEST_FILES",2),EQ("FAILED",0),EQ("SKIPPED",0),
     EQ("PYTHON_VERSION","3.12.13"),EQ("PILLOW_VERSION","12.3.0"),
     EQ("PYTEST_VERSION","8.4.2")]
A17=[EQ("RC",0),EQ("DIRECT_FILES",3),EQ("OLD_V24_CURRENT_NODEIDS",0),
     EQ("COLLECTED_NODEIDS",
        FS("ROW17_EXACT_COLLECTED_NODEIDS",
           "builders/after-control-builder.py",
           "inputs/after-control.inputs.json")),
     EQ("PER_FILE_DIGESTS",
        FS("ROW17_EXACT_PER_FILE_DIGESTS",
           "builders/after-control-builder.py",
           "inputs/after-control.inputs.json"))]
A18=[EQ("RC",0),EQ("INPUT_FILES",12),EQ("DESELECTED_COUNT",6),
     EQ("DESELECTED_NODEIDS",DESELECT6),
     EQ("COLLECTED_NODEIDS",
        FS("ROW18_EXACT_COLLECTED_NODEIDS",
           "builders/after-control-builder.py",
           "inputs/after-control.inputs.json")),
     EQ("EXECUTED_EQUALS_COLLECTED_MINUS_DESELECTED",true),
     EQ("FAILED",0),EQ("SKIPPED",0)]
A19=[EQ("RC",0),EQ("CHECKPOINT_SEQUENCE",40),
     EQ("GATE_EVENT_ID_MATCH",true),EQ("GIT_DOUBLE_READ_EQUAL",true),
     EQ("M_AFTER_INVENTORY_COUNT",627),EQ("LOWER_ONLY_AFTER18",true),
     EQ("CANONICAL_JSON_DOCUMENT_COUNT",1)]
```

Here `EQ(id,value)` expands to
`{assertion_id:id,operator:"EQ",expected:value,source_role:id}`. Each row n binds
exactly `An`, and all rows bind
`output_oracle_ref="after-control/candidate/full19-output-oracle.json"`.
For every displayed assertion, `extraction_map` contains one same-order
EvidenceExtractor. It never points to Full19RowResult observations. `RC` and
`RC_VECTOR` use COMMAND_RESULT_FIELD over immutable raw result command entries.
Host/undeclared/network/read-set assertions use TRACE_SET_DERIVATION over the
normalized trace and sealed consumer array. Checkpoint/manifest/file inventory
assertions use IMMUTABLE_FILE_JCS_POINTER. Row19 repository-state fields use
RAW_STDOUT_JCS_POINTER after requiring exactly one RFC8785 JSON document and no
non-whitespace suffix. All remaining semantic assertions use
REVIEWED_EXIT_CONTRACT: the extractor binds the exact command ordinals,
executable role Physical resolved in the intent, exact input consumer roles and
a unique `rule_id="ROW<ordinal>_<assertion_id>_RC0_V1"`; actual equals expected
only when every bound command has rc0, no signal/timeout, exact trace and the
reviewed checker/runner contract declares that assertion as its rc0 postcondition.
The exact extractor array and exit contracts are Stage-A full19 contract bytes,
not Stage-B-generated claims.

The supervisor derives observations from those immutable sources. An independent
verifier repeats every extractor and ignores the supplied observation/assertion
result until equality is established; mismatch fails. Required output is one strict Full19RowResult
per row, with command_results length equal to that row's command count; the raw
command stdout/stderr remain separate FilePhysical evidence. Stdout prose alone
cannot satisfy an assertion. The output oracle and consumer
map are auxiliary Stage-A semantic outputs, source-snapshot excluded and not
exact26 targets.

### 9.6 RuntimeActualBinding

Stage-B supervisor publishes before either environment runs:

```text
projections/runtime-actual/full19-gate-event-id-binding.json
RuntimeActualBinding={
 schema_version:"WS-PRE-P-R007-RUNTIME-ACTUAL-V1",
 role_id:"FULL19_GATE_EVENT_ID",
 producer:"STAGE_B_SUPERVISOR",
 stage_a_subject:FilePhysical,
 resolved_equality_receipts:[FilePhysical exact3],
 source_checkpoint:FilePhysical,
 source_tail:{sequence:39,event_sha256},
 n26_digest,resolved_subject_digest,stage_b_attempt_key,
 value,substitution_count:1,
 consumer_intent_roles:["LOCAL_FULL19_019","HOSTED_FULL19_019"],
 signature
}
value="WS-PRE-P-R007-" + lowercase_hex(SHA256(
 ASCII("WS-PRE-P-R007-FULL19-GATE-EVENT-ID-V1") || NUL ||
 JCS({stage_b_attempt_key,source_checkpoint_sha256,source_tail,
      n26_digest,resolved_subject_digest})
))
```

`resolved_subject_digest` is the JCS digest of the already sealed Stage-A
subject plus the three equality receipts in §10; it is not the later
ResolvedReviewSubjectManifest. Thus the binding has no forward edge to validation
results or reviews.

Only after both row19 runs and both normalized results seal, the supervisor
publishes:

```text
projections/runtime-actual/full19-gate-event-id-use-receipt.json
RuntimeActualUseReceipt={
 schema_version:"WS-PRE-P-R007-RUNTIME-ACTUAL-USE-V1",
 binding:FilePhysical,
 local_intent:FilePhysical,local_result:FilePhysical,
 hosted_intent:FilePhysical,hosted_result:FilePhysical,
 local_resolved_argv_digest,hosted_resolved_argv_digest,
 substitution_counts:[1,1],
 normalized_outputs:[FilePhysical exact2],
 normalized_outputs_byte_equal:true,status:"PASS",signature
}
```

For FAIL, `failed_command_ordinals` is unique ascending command order,
`failed_assertion_ids` is unique assertion declaration order, and
`failure_reasons` is unique in the fixed order shown in the schema. The
NOT_RUN branch contains only its displayed empty execution fields; it does not
inherit executed-result cardinality requirements.

Supervisor substitutes this one value into the one row19 token in each
environment; all other tokens remain unchanged. Each sealed intent binds the
actual binding FilePhysical, `substitution_count=1` and
`resolved_payload_argv_digest`. Both environments must have the same value and
resolved logical argv digest. Each result rebinds that digest and its semantic
repository-state JSON; the two normalized semantic outputs must be byte-equal.
After both runs, a `RuntimeActualUseReceipt` points inward to the binding, two
intents, two results and their equal normalized output; the binding never points
to those future consumers. Missing, duplicate, environment-specific or
post-intent substitution fails.

Slot swap, ID/order/argv/cwd/env/executable/module/input/timeout/cap drift,
consumer omission/duplication, unexpected input, undeclared/cross-env/host read,
payload write, `/runtime` or `/out` literal fails.

## 10. Phase0, Stage-B resolution, regression and full19 oracle

Phase0 is predecessor-only:

```text
phase-0-contracts/<slot>/regression-predecessor-identity.json
phase-0-contracts/<slot>/regression-allowed-transformations.json
```

Both have `executable=false`, `acceptance=false`, successor hash
`FutureSealed`, and only typed future roles. A `450 PASS/7 FAIL`, B
`241 intended/NOT_RUN` are lineage, not acceptance.

Allowed transforms only:

- runtime lock/env/pack binding;
- single runner exact named argv;
- history exact6 and orphan exact5 classification;
- B/C direct2, v2.4.1 direct3, D direct2;
- R007 revision-owned target suffix change for ordinals3/4/8/9.

The Stage-A auxiliary
`after-control/candidate/regression-successor-contract.json` is strict:

```text
RegressionSuccessorContract={
 schema_version:"WS-PRE-P-R007-REGRESSION-SUCCESSOR-V1",
 producer_builder:FilePhysical("builders/after-control-builder.py"),
 producer_inputs:FilePhysical("inputs/after-control.inputs.json"),
 invocations:[
  {id:"before-validate",commands:[BEFORE_RUNNER_VALIDATE],
   expected_rc:[0],timeout_seconds:600},
  {id:"after-validate",commands:[AFTER_RUNNER_VALIDATE],
   expected_rc:[0],timeout_seconds:600},
  {id:"regression-a",commands:[AFTER_RUNNER_UNIT],
   expected_rc:[0],timeout_seconds:1800},
  {id:"regression-b",
   commands:concat(FULL19_ROW03,FULL19_ROW04,FULL19_ROW17,FULL19_ROW18),
   expected_rc:[0,0,0,0,0,0],timeout_seconds:3600},
  {id:"after-repeat",commands:[AFTER_RUNNER_VALIDATE],
   expected_rc:[0],timeout_seconds:600}
 ],
 stdout_cap:268435456,stderr_cap:268435456,
 assertions:[
  "BEFORE_SEQ39_V24_PASS","AFTER_SEQ40_V241_PASS",
  "REGRESSION_A_CURRENT_UNIT_PLUS_DIRECT2_PASS",
  "REGRESSION_B_D_FINAL_ROWS_03_04_17_18_PASS",
  "AFTER_REPEAT_SEMANTIC_DIGEST_EQUALS_AFTER_VALIDATE"
 ],
 contract_digest,status:"SEALED",signature
}
```

`BEFORE_RUNNER_VALIDATE`, `AFTER_RUNNER_VALIDATE` and `AFTER_RUNNER_UNIT` are the
§9.2 row15 literal array with respectively:

```text
BEFORE:
  --layer validate
  --control-selector BEFORE_SEQ39_V24
  --routing-manifest
    docs/control/goals/walksafe-completion-graph-v2-4-1/
    test-routing-before-seq39-v2.4.json
AFTER_VALIDATE:
  --layer validate
  --control-selector AFTER_SEQ40_V241
  --routing-manifest
    docs/control/goals/walksafe-completion-graph-v2-4-1/
    test-routing-after-seq40-v2.4.1.json
AFTER_UNIT:
  identical to AFTER_VALIDATE except --layer unit
```

All other row15 tokens remain byte-identical and in the same positions.
`concat(...)` is literal array inclusion of the fully expanded §9 command arrays
in displayed row order, not a runtime macro. Row03 contributes three commands;
rows04/17/18 contribute one each, so regression-b command count is exact6.
Every invocation uses cwd `/work/walksafe`, §8 common env plus the row env
additions, exact consumer arrays from the included rows, and §8 raw six. The
Stage-B regression-final builder reconstructs these bytes and cannot substitute a
future Stage-B contract.

After both environment projections pass `after-validate` and before either
full19-001, the Stage-B supervisor runs `web-next-type-seed` once per
environment. Exact payload:

```json
[
 "/env/node/bin/node",
 "/work/walksafe/apps/web/node_modules/next/dist/bin/next",
 "typegen",
 "/work/walksafe/apps/web"
]
```

cwd is `/work/walksafe`; environment is §8 common + E_NPM +
`NEXT_TELEMETRY_DISABLED=1`; timeout is 1200 seconds; stdout/stderr caps are
268435456 bytes; expected rc is `[0]`; network is denied. Its exact consumers,
in order, are:

```text
F("apps/web/package.json"),F("apps/web/package-lock.json"),
F("apps/web/tsconfig.json"),F("apps/web/next.config.mjs"),
F("apps/web/next-env.d.ts"),F("apps/web/proxy.ts"),
F("apps/web/legacy-runtime-boundary.ts"),
P("apps/web/app","WEB_APP_SET"),P("apps/web/lib","WEB_LIB_SET"),
P("apps/web/types","WEB_TYPE_SET"),
P("apps/web/node_modules","WEB_NODE_MODULE_SET"),E("NODE_ENVIRONMENT")
```

It starts with an empty WEB_NEXT scratch and may produce only
`types/cache-life.d.ts`, `types/routes.d.ts`, `types/validator.ts`. The supervisor
captures the §8 raw six, copies those three regular files with fresh inodes into
`projections/<environment>/seeds/web-next-types/.next`, publishes the logical
manifest and physical receipt, and then:

```text
WebNextTypeSeedBuildReceipt={
 schema_version:"WS-PRE-P-R007-WEB-NEXT-TYPE-SEED-V1",
 stage_b_attempt_key,environment,projection:FilePhysical,
 environment_logical_manifest:FilePhysical,
 environment_physical_receipt:FilePhysical,
 argv,consumers,timeout_seconds:1200,
 stdout_cap:268435456,stderr_cap:268435456,
 evidence:{intent,stdout,stderr,raw_trace,normalized_trace,result}
   as exact6 FilePhysical,
 seed_root:DirPhysical,seed_logical_manifest:FilePhysical,
 seed_physical_receipt:FilePhysical,
 ordered_members:[
  FilePhysical("types/cache-life.d.ts"),
  FilePhysical("types/routes.d.ts"),
  FilePhysical("types/validator.ts")
 ],
 status:"PASS",signature
}
WebNextTypeSeedEqualityReceipt={
 schema_version:"WS-PRE-P-R007-WEB-NEXT-TYPE-SEED-EQUALITY-V1",
 local_build_receipt:FilePhysical,hosted_build_receipt:FilePhysical,
 local_logical_manifest:FilePhysical,hosted_logical_manifest:FilePhysical,
 logical_bytes_equal:true,shared_regular_inode_count:0,
 all_regular_nlink1:true,status:"PASS",signature
}
```

The equality receipt is exactly
`projections/web-next-type-seed-equality-receipt.json`. Its barrier is
`local AFTER || hosted AFTER -> local seed || hosted seed -> equality receipt`.
Only then are the row11/12 fresh clones and ScratchSeedReceipts allowed. An
unexpected fourth member, missing raw sibling, environment byte drift, shared
inode or forward reference fails.

Stage-B issuer first publishes only two signed literal input manifests:

```text
inputs/resolved-after-control.inputs.json=[
 candidate-review-subject-manifest.json,
 aggregate/candidate/candidate-target-map.json,
 aggregate/candidate/equality-receipt.json,
 journal/reviews/stage-a/<stage-a-key>/candidate-independent-review.md,
 after-control/candidate/equality-receipt.json,
 AFTER_CANDIDATE_LITERAL_SET
]
inputs/resolved-apply.inputs.json=[
 candidate-review-subject-manifest.json,
 aggregate/candidate/candidate-target-map.json,
 aggregate/candidate/equality-receipt.json,
 apply/candidate/equality-receipt.json,
 CANDIDATE_TARGET_MAP.actual_candidates[ordinal1..26],
 APPLY_CANDIDATE_LITERAL_SET
]
```

It then publishes
`resolved-builder-command-manifest.json` with exact6 commands (two units × three
slots). After the six resolved receipts make the regression input actual, it
publishes `regression-builder-command-manifest.json` with exact3 commands. Both
use:

```text
StageBBuilderCommandManifest={
 schema_version:"WS-PRE-P-R007-STAGE-B-BUILDER-COMMANDS-V1",
 stage_b_attempt_key,phase:"RESOLVED"|"REGRESSION",
 commands:[{
  invocation_id,unit,slot,builder:FilePhysical,template:FilePhysical,
  inputs:FilePhysical,argv,cwd:"/work/subject",env,
  read_allowlist,payload_write_allowlist,
  supervisor_publication_allowlist,timeout_seconds,
  stdout_cap,stderr_cap
 }],
 command_set_sha256,signature
}
```

The RESOLVED/REGRESSION command counts are exactly6/exact3 and no manifest
contains the other's future input.

Array entries are actual FilePhysical/DirManifestPhysical in displayed order; the
two role arrays expand exactly from §5. The seeded logical templates permit only
those paths. The issuer runs both resolved builders for
`[build-01,build-02,candidate]`, seals their exact receipts and equality
receipts, and only then publishes:

```text
regression-final/inputs/regression-final.inputs.json=[
 candidate/after-control/full19-successor-contract.json,
 candidate/after-control/full19-output-oracle.json,
 candidate/after-control/full19-consumer-map.json,
 candidate/after-control/resolved-build-receipt.json,
 candidate/apply/resolved-build-receipt.json
]
```

Those two receipt paths are now ACTUAL and point only inward. The regression
manifest did not exist when either resolved command manifest was signed, so no
future Physical or reverse edge occurs. For every
`slot in [build-01,build-02,candidate]`, cwd is the Stage-B subject root, env is
§8 common env, and the literal sandbox cwd is `/work/subject`. Payload argv is:

```json
["/env/bin/python","-I","-S","-B",
 "builders/resolved-after-control-builder.py",
 "--template","templates/resolved-after-control.template.json",
 "--inputs","/input/resolved-after-control.inputs.json","--slot","<slot>"]
["/env/bin/python","-I","-S","-B",
 "builders/resolved-apply-builder.py",
 "--template","templates/resolved-apply.template.json",
 "--inputs","/input/resolved-apply.inputs.json","--slot","<slot>"]
["/env/bin/python","-I","-S","-B",
 "regression-final/builders/regression-final-builder.py",
 "--template","regression-final/templates/regression-final.template.json",
 "--inputs","/input/regression-final.inputs.json",
 "--slot","<slot>"]
```

`CANDIDATE_TARGET_MAP.actual_candidates[ordinal1..26]` expands to the map's exact
ordered 26 actual FilePhysical source entries; map verification proves equality
with §6 paths. The slot is expanded before signing. Read allowlist is exact
builder/template/input members plus its sealed Stage-A inputs. Output allowlists
are respectively `<slot>/after-control/` mapped exact AFTER members plus
`<slot>/after-control/{resolved-build-receipt.json,raw/<six>}`,
`<slot>/apply/` mapped exact APPLY members plus
`<slot>/resolved-target-overlay/<each §6 target path>` exact26,
`<slot>/resolved-projection-template-manifest.json` and
`<slot>/apply/{resolved-build-receipt.json,raw/<six>}`, and
`regression-final/<slot>/{regression-contract.json,output-oracle.json,
consumer-map.json,build-receipt.json,raw/<six>}`. No sibling slot, live repo,
or journal/host/network read/write is allowed. The exact Stage-A review is read
only through its `/input/members/...` copy and source-equality binding.
build01/build02 logical bytes
must match and candidate is a fresh-inode materialization.

Strict Stage-B schemas and their canonical paths are:

```text
StageBResolvedBuildReceipt={
 schema_version:"WS-PRE-P-R007-STAGE-B-RESOLVED-BUILD-V1",
 stage_b_attempt_key,unit:"after-control"|"apply"|"regression-final",slot,
 command_manifest:FilePhysical,input_manifest:FilePhysical,
 builder:FilePhysical,template:FilePhysical,argv,cwd,env,
 outputs:[FilePhysical],raw_evidence:[FilePhysical exact6],
 output_set_sha256,status:"PASS",signature
}
StageBEqualityReceipt={
 schema_version:"WS-PRE-P-R007-STAGE-B-EQUALITY-V1",
 stage_b_attempt_key,unit:"after-control"|"apply"|"regression-final",
 build_01_receipt:FilePhysical,build_02_receipt:FilePhysical,
 candidate_receipt:FilePhysical,
 ordered_logical_roles,logical_byte_equal:true,
 pairwise_distinct_regular_inodes:true,all_regular_nlink1:true,
 status:"PASS",signature
}
StageBProjectionTemplateManifest={
 schema_version:"WS-PRE-P-R007-PROJECTION-TEMPLATE-V1",
 stage_b_attempt_key,candidate_apply_receipt:FilePhysical,
 source_snapshot_manifest:FilePhysical,
 source_snapshot:DirManifestPhysical,
 resolved_overlay:DirManifestPhysical,
 ordered_members:[{relative_path,source:FilePhysical}],
 member_path_set_sha256,member_content_set_sha256,
 status:"SEALED",signature
}
StageBEnvironmentProjectionManifest={
 schema_version:"WS-PRE-P-R007-ENVIRONMENT-PROJECTION-V1",
 stage_b_attempt_key,environment:"local-combined"|"hosted-cpu",
 phase:"BEFORE"|"AFTER",
 template_manifest:FilePhysical|SignedNotReached,
 source_manifest:FilePhysical,root_physical:DirPhysical,
 ordered_members:[FilePhysical],member_path_set_sha256,
 member_content_set_sha256,status:"SEALED",signature
}
StageBEnvironmentProjectionPhysicalReceipt={
 schema_version:"WS-PRE-P-R007-ENVIRONMENT-PROJECTION-PHYSICAL-V1",
 stage_b_attempt_key,environment,manifest:FilePhysical,
 projection_root:DirPhysical,
 members:[{relative_path,dev,inode,mnt_id,uid,gid,mode,nlink,size,sha256}],
 file_fsyncs:true,directories_bottom_up_fsynced:true,parent_fsynced:true,
 status:"PASS",signature
}
ResolvedReviewSubjectManifest={
 schema_version:"WS-PRE-P-R007-RESOLVED-REVIEW-SUBJECT-V1",
 stage_b_attempt_key,plan:FilePhysical,
 stage_a_subject:FilePhysical,stage_a_review:FilePhysical,
 environment_attempt_manifest:FilePhysical,
 builder_command_manifests:[FilePhysical exact2],
 resolved_input_manifests:[FilePhysical exact2],
 regression_input_manifest:FilePhysical,
 resolved_build_receipts:[FilePhysical exact6],
 regression_build_receipts:[FilePhysical exact3],
 equality_receipts:[FilePhysical exact3],
  candidate_projection_manifests:[FilePhysical exact2],
  candidate_projection_physical_receipts:[FilePhysical exact2],
  before_projection_manifests:[FilePhysical exact2],
  before_projection_physical_receipts:[FilePhysical exact2],
 web_next_seed_receipts:[FilePhysical exact3],
 scratch_seed_receipts:[FilePhysical exact6],
 runtime_actual_binding:FilePhysical,
 runtime_actual_use_receipt:FilePhysical,
 environment_invocation_results:[FilePhysical exact48],
  environment_evidence_sets:[{invocation_id,ordered_members:[FilePhysical exact6]}
                            exact48],
  ordered_graph_nodes:[{node_id,artifact:FilePhysical}],
  ordered_graph_edges:[{from_id,to_id,edge_role}],
  graph_digest,subject_content_digest,
 exact_exclusions:[
  "resolved-review-subject-manifest.json",
  "journal/reviews/stage-b/<stage-b-key>/regression-final-independent-review.md",
  "journal/reviews/stage-b/<stage-b-key>/resolved-independent-review.md"
 ],
 status:"SEALED",signature
}
```

Equality receipts are exactly
`resolved-equality/after-control-equality-receipt.json`,
`resolved-equality/apply-equality-receipt.json` and
`regression-final/candidate/equality-receipt.json`. `resolved_build_receipts`
orders after-control slots then apply slots, each
`[build-01,build-02,candidate]`; regression uses the same slot order.
`web_next_seed_receipts` orders local build, hosted build, cross-environment
equality. `scratch_seed_receipts` orders environments
`[local-combined,hosted-cpu]`, then rows `[011,012,014]`.
`environment_invocation_results/evidence_sets` exclude the separately bound web
seed invocation and order environments then
`[before-validate,after-validate,full19-001..019,regression-a,regression-b,
after-repeat]`, exactly24 per environment. Every edge points to an already sealed
node. The subject manifest excludes itself and its two later reviews; each review
binds the subject, never vice versa.

The candidate apply builder publishes the sole environment-neutral template at
`candidate/resolved-projection-template-manifest.json`. After its equality
receipt, the Stage-B projection supervisor materializes two fresh-inode roots:

```text
projections/local-combined/before-root/
projections/local-combined/before-projection-manifest.json
projections/local-combined/before-projection-physical-materialization-receipt.json
projections/local-combined/root/
projections/local-combined/resolved-projection-manifest.json
projections/local-combined/projection-physical-materialization-receipt.json
projections/hosted-cpu/before-root/
projections/hosted-cpu/before-projection-manifest.json
projections/hosted-cpu/before-projection-physical-materialization-receipt.json
projections/hosted-cpu/root/
projections/hosted-cpu/resolved-projection-manifest.json
projections/hosted-cpu/projection-physical-materialization-receipt.json
```

Before validation it copies only the Stage-A source snapshot into each
`before-root`, with `phase="BEFORE"`, a SignedNotReached template and the exact
source snapshot manifest. After that invocation it copies the same snapshot plus
target-shaped overlay by template order into each `root`, with `phase="AFTER"`
and the actual template.
fsyncs/reopens each root and publishes manifest then physical receipt. The two
member path/content arrays are byte-equal and all corresponding regular inodes
are pairwise distinct; environment and physical identity remain outside the
content equality. `STAGE_B_SEALED_PROJECTION.source` is the matching manifest's
strict `{root:root_physical,manifest:FilePhysical,ordered_members}` projection
into `DirManifestPhysical`; the whole StageBEnvironmentProjectionManifest is
never cast to that type. Every
projection intent binds its manifest and physical receipt. Publication order is
source snapshot → two BEFORE materializations/manifests/receipts → BEFORE
validation → candidate apply/equality → template → two AFTER
materializations/manifests/receipts → AFTER validation. Every exact2 array
orders local then hosted.

The resolved-apply builder copies every ordered source to a fresh inode at its
target-shaped relative path under `resolved-target-overlay`, with §6 target
mode/uid/gid/nlink/executable and no active-repo mutation. It overlays that exact
tree on the sealed source snapshot to make each environment projection. Thus B,
C and D successor bytes are present for full19; CandidateTargetMap metadata never
stands in for reading the actual candidate bytes.

The DAG:

```text
Phase0
-> lane A || lane C || control-core || lane D-core
-> lane B-final(A,C,control)
-> lane D-final(A,B,C,control,D-core)
-> after-control
-> apply/N26/aggregate seal
-> external Stage-A review
-> Stage-B resolved build-01/build-02/candidate
-> regression-final equality
-> local/hosted source-only BEFORE projection manifests/receipts -> BEFORE
-> projection template -> local/hosted fresh AFTER projection manifests/receipts
-> local AFTER || hosted AFTER
-> local web seed || hosted web seed -> web seed equality
-> local full19 || hosted full19
-> each environment regression A PASS -> regression B PASS -> AFTER repeat
-> runtime-actual use receipt
-> resolved-review-subject-manifest seal
-> external regression and resolved reviews
-> V1
```

Stage-B subject exact roots:

```text
builders/resolved-after-control-builder.py
builders/resolved-apply-builder.py
regression-final/builders/regression-final-builder.py
inputs/resolved-after-control.inputs.json
inputs/resolved-apply.inputs.json
resolved-builder-command-manifest.json
regression-builder-command-manifest.json
regression-final/inputs/regression-final.inputs.json
templates/resolved-after-control.template.json
templates/resolved-apply.template.json
regression-final/templates/regression-final.template.json
build-01/after-control/
build-01/apply/
build-01/resolved-target-overlay/
build-02/after-control/
build-02/apply/
build-02/resolved-target-overlay/
candidate/after-control/
candidate/apply/
candidate/resolved-target-overlay/
resolved-equality/after-control-equality-receipt.json
resolved-equality/apply-equality-receipt.json
regression-final/build-01/
regression-final/build-02/
regression-final/candidate/
projections/local-combined/
projections/hosted-cpu/
projections/runtime-actual/
projections/web-next-type-seed-equality-receipt.json
resolved-review-subject-manifest.json
```

Stage-B per environment:

```text
BEFORE validate
-> AFTER overlay and validate
-> cross-environment AFTER barrier
-> web-next-type-seed
-> cross-environment web-seed equality barrier
-> ordered full19 1..19
-> regression A
-> regression B
-> AFTER repeat
```

requires 19/19 actual rc0, no FAIL/NOT_RUN, regression A/B PASS and repeat digest
equality. Each projection uses its own env/pack and §8 evidence.

The Stage-A after-control builder materializes:

```text
after-control/<slot>/full19-successor-contract.json
after-control/<slot>/regression-successor-contract.json
after-control/<slot>/stage-c-exact6-contract.json
after-control/<slot>/full19-output-oracle.json
after-control/<slot>/full19-consumer-map.json
```

from §9/§16 exact rows. Schemas require exact19 unique ordered full19 IDs, exact
regression A/B/repeat command roles, exact6 unique ordered Stage-C IDs, command arrays,
cwd/env additions, executables, consumers, timeout/caps and typed assertions.
`builders/lane-d-final-builder.py` resolves only `FutureSealed` roles after
A/B/C/control/D freeze.
`regression-final/builders/regression-final-builder.py` independently
reconstructs §9, checks no row swap/consumer omission and binds its logical digest.
The final target `full19-successor-contract.json` references the sealed oracle and
consumer-map roles without inventing future hashes. Both external Stage-B reviews
compare physical files to this plan; an intent cannot define its own oracle.

## 11. discovery, routing and single runner

Final role arithmetic:

```text
UNIT28 + FUNCTIONAL23 + INTEGRATION7 + MODEL3
+ HISTORICAL53 + ACTIVE16 + PROTOTYPE2 = 132
assigned/unassigned/duplicate/extra = 132/0/0/0
selected/excluded = 74/58
runner_all = discovered74 + direct2 = 76
```

Complete normative discovered registry:

```text
UNIT | RUNNER_ALL | tests/test_voice_intents.py
UNIT | RUNNER_ALL | tests/test_voice_model_integrity.py
UNIT | RUNNER_ALL | tests/test_voice_stt_policy.py
UNIT | RUNNER_ALL | tests/test_voice_stt_server.py
UNIT | RUNNER_ALL | tests/test_voice_tts.py
UNIT | RUNNER_ALL | tests/test_submission_build_io.py
UNIT | RUNNER_ALL | tests/test_submission_isolated_python.py
UNIT | RUNNER_ALL | tests/test_submission_manifest_policy.py
UNIT | RUNNER_ALL | tests/test_walksafe_node_toolchain_lock.py
UNIT | RUNNER_ALL | tests/test_pwa_release_update_checker.py
UNIT | RUNNER_ALL | tests/test_submission_promotion.py
UNIT | RUNNER_ALL | tests/test_tactile_route_policy_contract.py
UNIT | RUNNER_ALL | tests/test_android_depth_scaffold_contract.py
UNIT | RUNNER_ALL | tests/test_android_apk_model_asset_check.py
UNIT | RUNNER_ALL | tests/test_web_runtime_trace_scope.py
UNIT | RUNNER_ALL | tests/test_web_build_manifest.py
UNIT | RUNNER_ALL | tests/test_walksafe_full_rc_tooling.py
UNIT | RUNNER_ALL | tests/test_walksafe_isolated_python_bootstrap.py
UNIT | RUNNER_ALL | tests/test_walksafe_product_quality_receipt.py
UNIT | RUNNER_ALL | tests/test_walksafe_operator_attestation.py
UNIT | RUNNER_ALL | tests/test_walksafe_android_gateway_boundary_20260723.py
UNIT | RUNNER_ALL | model/test_two_model_runtime.py
UNIT | RUNNER_ALL | backend/tests/test_field_test_security.py
UNIT | RUNNER_ALL | backend/tests/test_health_readiness.py
UNIT | RUNNER_ALL | backend/tests/test_openapi_contract.py
UNIT | RUNNER_ALL | backend/tests/test_report_storage_reconciliation.py
UNIT | RUNNER_ALL | backend/tests/test_report_retention.py
UNIT | RUNNER_ALL | backend/tests/test_inference_process.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_admin_security.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_actor_rate_limit_store.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_android_debug_logs.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_backup_source.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_detect.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_report_policy.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_reports.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_reports_v2.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_request_limits.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_test_storage_isolation.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_uploads.py
FUNCTIONAL | RUNNER_ALL | backend/tests/test_yolo_inference_adapter.py
FUNCTIONAL | RUNNER_ALL | tests/test_agency_submission_receipt.py
FUNCTIONAL | RUNNER_ALL | tests/test_android_field_session_summary.py
FUNCTIONAL | RUNNER_ALL | tests/test_cloudflare_field_runner.py
FUNCTIONAL | RUNNER_ALL | tests/test_web_field_session_summary.py
FUNCTIONAL | RUNNER_ALL | tests/test_field_telemetry_retention.py
FUNCTIONAL | RUNNER_ALL | tests/test_report_retention_operational_safety.py
FUNCTIONAL | RUNNER_ALL | tests/test_report_retention_scheduler.py
FUNCTIONAL | RUNNER_ALL | tests/test_test_contamination_audit.py
FUNCTIONAL | RUNNER_ALL | tests/test_walksafe_backup_prune.py
FUNCTIONAL | RUNNER_ALL | tests/test_walksafe_environment_identity.py
FUNCTIONAL | RUNNER_ALL | tests/test_walksafe_admin_high_risk_data_delete_gate.py
INTEGRATION | RUNNER_ALL | tests/test_runtime_model_integration.py
INTEGRATION | RUNNER_ALL | tests/test_release_evidence_gate.py
INTEGRATION | RUNNER_ALL | tests/test_walksafe_backup_integrity.py
INTEGRATION | RUNNER_ALL | tests/test_local_model_registry.py
INTEGRATION | RUNNER_ALL | tests/test_submission_visual_privacy.py
INTEGRATION | RUNNER_ALL | backend/tests/test_navigation_routes.py
INTEGRATION | RUNNER_ALL | backend/tests/test_detect_v2.py
MODEL | INVENTORY_ONLY | tests/test_aihub183_dataset_integrity.py
MODEL | INVENTORY_ONLY | tests/test_aihub189_depthprediction_offline.py
MODEL | INVENTORY_ONLY | tests/test_dataset_content_integrity.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_answer_review.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_baseline_approval_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_baseline_candidate.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_baseline_candidate_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_content_readiness_audit.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_independent_review_record_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_temporal_provenance_supplement_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_control_bootstrap.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_decision_interview.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_design_deliverables.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_document_preparation.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_effective_baseline.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_effective_decision_register_alignment.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_feature_policy_baseline_approval.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_feature_policy_baseline_review.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_feature_policy_document.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_feature_policy_report.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_feature_policy_resolution.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_aiml.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_deliverables_0_6.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_dev_test.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_management_discovery.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_rel_ops_cls.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_sec_ws.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_formal_trace_7_12.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_fp035_correction_candidate.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_implementation_gap_analysis_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_integrated_baseline.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_legacy_web_boundary_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_fp004_priority_user_trace_20260724.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_fp005_official_environment_trace_20260724.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_fp006_phone_mounting_trace_20260724.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_fp010_first_run_registration_trace_20260725.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_fp018_walk_state_recovery_trace_20260724.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_goal_graph.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_goal_graph_v2_2_history.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_goal_graph_v2_3.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_goal_graph_v2_3_history.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_npc_permission_session_trace_20260724.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_project_continuation.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_project_continuation_v2_3.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_project_questionnaire.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_requirements_draft.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_trace_integration_report.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_test_database_preflight.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_android_product_boundary.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_artifact_baseline_materialization_20260722.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_project_continuation_v2_4.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_goal_graph_v2_4.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_phase1_exact257_successor_r011_20260729.py
HISTORICAL | INVENTORY_ONLY | tests/test_walksafe_w3_engineering_evidence_20260726.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic01_phase_b_trace_20260722.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic01_phase_c_trace_20260722.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic02_trace_v2_2_history.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_epic02_trace_v2_3_history.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp011_long_lived_login_trace_20260725.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp012_multi_device_session_ledger_trace_20260726.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp013_integrated_consent_trace_20260725.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp014_permission_denial_revocation_trace_20260726.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_fp016_camera_centered_buttonless_screen_trace_20260726.py
ACTIVE | RUNNER_ALL | tests/test_walksafe_goal_package.py
PROTOTYPE | INVENTORY_ONLY | tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py
PROTOTYPE | INVENTORY_ONLY | tests/test_walksafe_v2_5_control_candidate_20260730.py
```

Exact transforms:

```text
UNIT -> HISTORICAL:
  tests/test_walksafe_test_database_preflight.py
  tests/test_walksafe_android_product_boundary.py
  tests/test_walksafe_artifact_baseline_materialization_20260722.py
ACTIVE -> HISTORICAL:
  tests/test_walksafe_project_continuation_v2_4.py
  tests/test_walksafe_goal_graph_v2_4.py
orphan -> HISTORICAL:
  tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
  tests/test_walksafe_phase1_exact257_successor_r011_20260729.py
  tests/test_walksafe_w3_engineering_evidence_20260726.py
orphan -> PROTOTYPE:
  tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py
  tests/test_walksafe_v2_5_control_candidate_20260730.py
```

History exact6 has current argv count0:

```text
tests/test_walksafe_test_database_preflight.py
tests/test_walksafe_android_product_boundary.py
tests/test_walksafe_artifact_baseline_materialization_20260722.py
tests/test_walksafe_project_continuation_v2_4.py
tests/test_walksafe_goal_graph_v2_4.py
tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
```

Non-discovery direct exact:

| class/consumer | literal path |
|---|---|
| current, runner all and full19-18 | `tests/walksafe_test_database_preflight_successor_20260731_r007.py` |
| current, runner all and full19-04 | `tests/walksafe_android_gateway_public_routes_successor_20260731_r007.py` |
| current, full19-17/exact6-006 | `tests/walksafe_project_continuation_v2_4_1_successor_20260731.py` |
| current, full19-17/exact6-006 | `tests/walksafe_goal_graph_v2_4_1_successor_20260731.py` |
| current, full19-17/exact6-006 | `tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py` |
| D, full19-03/18 | `tests/walksafe_artifact_baseline_historical_successor_20260731_r007.py` |
| D, full19-03/18 | `tests/walksafe_artifact_baseline_current_successor_20260731_r007.py` |

Routing manifests contain literal 132 path→role→consumer rows, selected74,
excluded58, direct5 and D2 arrays. They preserve all unchanged paths from the
current arrays; only the transformations above apply.

Single runner exact:

```text
bash /work/walksafe/scripts/run_walksafe_test_layers_20260711.sh
  --layer <validate|unit|all>
  --root /work/walksafe
  --checkpoint <root-relative checkpoint>
  --control-selector <BEFORE_SEQ39_V24|AFTER_SEQ40_V241>
  --routing-manifest <exact root-relative final manifest>
```

No default/env inference. BEFORE binds seq39/v2.4 and
`test-routing-before-seq39-v2.4.json`; AFTER binds seq40/v2.4.1 and
`test-routing-after-seq40-v2.4.1.json`. Unknown/missing/duplicate flag, third
selector, path escape, wrong package/tail/manifest is pre-spawn rc2/child0.

## 12. global journal, exact schemas and publication lock

### 12.1 numeric streams and signed envelope

```text
journal/records/<12digit>.json
journal/transactions/<transaction-id>/records/<12digit>.json
```

Kind appears only in signed body. There is no head/claim/sequence directory or
`<seq>-<kind>` path. Filename sequence equals payload sequence. Global signature:

```text
WS-PRE-P-R007-GLOBAL-RECORD-V1 || NUL || RFC8785_JCS(payload)
```

The nonnumeric genesis file is the unique sequence-zero predecessor, not a global
record slot:

```text
GlobalGenesisSentinel={
 path:"genesis/authority-journal-genesis.json",
 sha256,bytes,sequence:"000000000000",
 record_kind:"JOURNAL_GENESIS"
}
```

The first `000000000001.json.prior_record` equals this sentinel. Every later
`prior_record` is an exact `RecordRef` to the immediately preceding numeric file.

Allowed global kinds:

```text
ISSUED
CONSUME_CLAIMED
LEASE_ACQUIRED
LEASE_RENEWED
PREPARED
DELEGATED_AND_CLOSED
CLOSED_SUCCESS
CLOSED_INCIDENT
REVOKED
PUBLICATION_ABORTED
RECOVERY_ISSUED
RECOVERY_CONSUMED
RECOVERY_LEASE_ACQUIRED
RECOVERY_LEASE_RENEWED
RECOVERY_CLOSED_SUCCESS
RECOVERY_CLOSED_INCIDENT
RECOVERY_REQUIRED_NO_AUTHORITY
```

All global publication, including A/B, requires the same genesis-bound OFD lock.
A/B acquire/replay/validate/append/parent-fsync/release per record; long builds and
reviews run outside the lock. B→C, effective C and recovery retain one continuous
lock lifetime as §14 defines.

### 12.2 common tagged union

```text
RecordRef={path,sha256,bytes,sequence,record_kind}
SignedAbsent={state:"SIGNED_ABSENT",reason}
SignedNotReached={state:"SIGNED_NOT_REACHED",reason:"NOT_REACHED"}
TransactionTargetSpec={
 state:"TARGET_SPEC",transaction_id,
 manifest_path:"transactions/<transaction-id>/transaction-manifest.json",
 records_path:"transactions/<transaction-id>/records",
 evidence_path:"transactions/<transaction-id>/evidence/stage-c-exact6",
 future_manifest_hash_state:"FORBIDDEN"
}
TransactionBinding =
  SignedAbsent(reason:"STAGE_HAS_NO_TRANSACTION") |
  TransactionTargetSpec |
  {state:"ACTUAL",transaction_id,manifest:FilePhysical}
Common={
  schema_version,journal_id,record_kind,sequence,prior_record,
  genesis:FilePhysical,event_at,actor,stage,attempt_key,
  challenge_id,nonce,transaction_binding
}
```

All Common fields are required and null/unknown/extra fails. A/B transaction
binding is exactly SIGNED_ABSENT; transaction_id/path/hash fields are FORBIDDEN.
C ISSUED/CONSUME/LEASE and any pre-manifest recovery use exact TARGET_SPEC. Once
the transaction subtree seals, C PREPARED/progress/terminal and post-manifest
recovery use ACTUAL. No immutable receipt or pre-manifest record contains a
future manifest Physical/hash. `original_transaction` in recovery is the exact
TARGET_SPEC when no manifest exists and exact ACTUAL otherwise; mixing states
fails.

| kind | exact additional fields |
|---|---|
| `ISSUED` | `attempt_ordinal,receipt:FilePhysical,request,challenge,raw_response,scope,source,validity,prior_attempt,authorization_state` |
| `CONSUME_CLAIMED` | `issued,receipt,expected_head,consume_ordinal=1,one_use=true,authorization_state` |
| `LEASE_ACQUIRED` | `consume,receipt,lease,grant_head,authorization_state` |
| `LEASE_RENEWED` | `consume,receipt,prior_lease,prior_lease_ordinal,lease,authorization_state` |
| `PREPARED` | `consume,active_lease,receipt,subject_manifest,reviews,environments,regression,transaction_manifest_state,authorization_state` |
| `DELEGATED_AND_CLOSED` | `b_issued,b_consume,b_lease,b_prepared,b_receipt,c_issued,c_pending_receipt,c_validity,c_scope,pair_digest,issuance_predecessor,handoff_head,before_states,after_states` |
| `CLOSED_SUCCESS` | `issued,consume,prepared,active_lease,receipt,terminal_evidence,before_state,after_state,reason` |
| `CLOSED_INCIDENT` | `issued,consume_state,lease_state,prepared_state,receipt,incident:FilePhysical,before_state,after_state,reason` |
| `REVOKED` | `issued,receipt,revocation_origin,before_state,after_state,reason` |
| `PUBLICATION_ABORTED` | `artifact_role,expected_path,observed_identity,expected_identity_state,incident:FilePhysical,state_before,state_after,reason` |
| `RECOVERY_ISSUED` | `attempt_ordinal,receipt,request,challenge,raw_response,scope,source,original_transaction,original_c_attempt,original_consume_state,original_incident,observed_progress,observed_checkpoint,prior_recovery,validity,authorization_state` |
| `RECOVERY_CONSUMED` | `recovery_issued,receipt,expected_head,observed_progress,consume_ordinal=1,one_use=true,authorization_state` |
| `RECOVERY_LEASE_ACQUIRED` | `recovery_consume,receipt,lease,grant_head,original_transaction,authorization_state` |
| `RECOVERY_LEASE_RENEWED` | `recovery_consume,receipt,prior_lease,prior_lease_ordinal,lease,original_transaction,authorization_state` |
| `RECOVERY_CLOSED_SUCCESS` | `recovery_issued,recovery_consume,active_lease,receipt,original_transaction,terminal_evidence,before_state,after_state,reason` |
| `RECOVERY_CLOSED_INCIDENT` | `recovery_issued,recovery_consume_state,lease_state,receipt,original_transaction,incident,before_state,after_state,reason` |
| `RECOVERY_REQUIRED_NO_AUTHORITY` | `original_c_close,original_incident,original_transaction,observed_progress,observed_checkpoint,recovery_authority_state:"SIGNED_ABSENT",required_scope,release_required:true,before_state,after_state,reason` |

Lease:

```text
{start,end,hard_deadline,renewal_ordinal,fencing_epoch}
```

Acquire ordinal0; renewal ordinal1..32 binds prior lease path/hash/bytes/sequence and
keeps the same epoch. Expiry/hard deadline cannot be exceeded.

## 13. receipts, paired capabilities, incident and FSM

### 13.1 receipt and incident exact paths

```text
journal/attempts/stage-a/<key>/authority-receipt.json
journal/attempts/stage-a/<key>/incident.json
journal/attempts/stage-b/<key>/authority-receipt.json
journal/attempts/stage-b/<key>/incident.json
journal/attempts/stage-c/<key>/authority-receipt.json
journal/attempts/stage-c/<key>/review-binding.json
journal/attempts/stage-c/<key>/incident.json
journal/attempts/recovery-stage-c/<key>/authority-receipt.json
journal/attempts/recovery-stage-c/<key>/incident.json
```

Receipt signature domain:

```text
WS-PRE-P-R007-ATTEMPT-RECEIPT-V1 || NUL || JCS(payload)
```

Common receipt exact fields:

```text
schema_version,stage,attempt_key,attempt_ordinal,challenge_id,nonce
transaction_binding,scope,issuance_expected_predecessor:RecordRef
plan/formal_review/skeptical_review/source checkpoint bindings
request/challenge/raw_response FilePhysical
issuer/verifier/custodian identities and signer fingerprint
issued_at,not_before,expires_at,ttl,lease_slice,hard_deadline
authorization_origin,revocation_origin,delegation_depth=0,handoff_depth
revocation_state,one_use=true,prior_attempt,prior_receipt,prior_closure
authorization_state
```

A/B receipts carry SignedAbsent. Pending/effective C and a recovery issued before
manifest creation carry the same byte-equal TransactionTargetSpec; an existing
transaction recovery carries ACTUAL. The later TransactionManifest binds the C
receipt Physical, never vice versa. The first global/transaction record that uses
ACTUAL is published only after the manifest subtree is sealed and reopened.

Incident signature domain:

```text
WS-PRE-P-R007-INCIDENT-V1 || NUL || JCS(payload)
```

```text
Incident={
  schema_version,stage,attempt_key,transaction_binding,
  reason,phase,event_at,custodian,
  observed_global_head,observed_transaction_head,
  observed_receipt,consume_state,lease_state,prepared_state,
  observed_target_or_checkpoint_state,
  raw_references:[FilePhysical],remediation_state:"TERMINAL_FAIL_STOP",
  signature
}
```

Reason enum:

```text
EARLY_BUILD_FAILURE
REVIEW_NONZERO
PENDING_EXPIRED
PENDING_REVOKED
CUSTODIAN_LOST
LEASE_EXPIRED
EFFECTIVE_UNCONSUMED_ABANDONED
MUTATION_MAY_HAVE_COMMITTED
PUBLICATION_MISMATCH
POSTCOMMIT_VALIDATION_FAILED
RECOVERY_FAILURE
```

Incident is PUBLISH_ONCE_AND_ADOPT before the close/abort record binds its
FilePhysical. Orphan expected incident is adopted; mismatched incident leads to
PUBLICATION_ABORTED without overwrite. Duplicate terminal close fails replay.

### 13.2 paired capability scopes

Scope is not an operation×root cross product:

```text
Scope={
  scope_id,
  allowed_capabilities:[{operation,roots:[literal roots]}],
  denied_capabilities:[{operation,roots:[literal roots]}]
}
```

Stage A exact pairs:

```text
READ_SEALED_SOURCE -> source snapshot allowlist
READ_EXEC_BOOTSTRAP_ENV -> sealed /env and pack
CREATE_WRITE_NEW_FSYNC_SEAL -> its candidate subject root
CREATE_WRITE_NEW_FSYNC_SEAL -> its external env attempt root
PUBLISH_LIFECYCLE_GLOBAL -> journal/records numeric next slot
READ_BIND_EXTERNAL_REVIEW -> exact external Stage-A review
```

Stage B exact pairs:

```text
READ_SEALED -> exact A subject/env/pack/review
CREATE_WRITE_NEW_FSYNC_SEAL -> its resolved subject/projection/raw root
EXEC_BWRAP -> frozen /usr/bin/bwrap plus exact mount contract
PUBLISH_LIFECYCLE_GLOBAL -> journal/records numeric next slot
READ_BIND_EXTERNAL_REVIEW -> exact two Stage-B reviews
```

A/B deny active repo mutation, checkpoint/app receipt/transaction writes, other
attempt/review writes, delete/overwrite/hardlink, unmanifested host/network. A/B
PREPARED uses
`transaction_manifest_state=SIGNED_ABSENT/STAGE_A_OR_B_NO_TRANSACTION`.

C exact pairs:

```text
READ_SEALED_EXEC -> A/B subjects, env/pack, reviews, live repo
MUTATE_EXACT_TARGET -> exact26 table operations
MATERIALIZE_AUX_PARENT_001 -> exact U1 operations/path
PUBLISH_LIFECYCLE_GLOBAL -> global numeric next slot
PUBLISH_TRANSACTION_MANIFEST_BOOTSTRAP -> exact TransactionTargetSpec transaction root
PUBLISH_TRANSACTION -> its transaction numeric/evidence roots
EXEC_EXACT6_READONLY -> live repo and sealed input
APPLICATION_RECEIPT_FINALIZE -> exact private sibling/final receipt paths
```

Recovery pairs are phase-limited subsets: a pre-manifest recovery has
`PUBLISH_TRANSACTION_MANIFEST_BOOTSTRAP -> exact original TransactionTargetSpec
transaction root`; remaining suffix/U1/progress are allowed before checkpoint;
exact6/application receipt is allowed after checkpoint only when the predecessor
is not POSTCHECK_FAILED; all include global lifecycle. Everything else denied.

Crash custodian has a distinct non-composable scope:

```text
UNCLEAN_C_CLOSE_ONLY -> exact original incident path
UNCLEAN_C_CLOSE_ONLY -> next CLOSED_INCIDENT global slot
UNCLEAN_C_CLOSE_ONLY -> next RECOVERY_REQUIRED_NO_AUTHORITY global slot
```

It denies recovery issuance/consume/lease/fence and all target/progress mutation.
A recovery receipt supplies a separate scope; observing a crash never creates it.

`ApplicationReceiptTargetSpec` is path-only at issue:

```text
{
  state:"SIGNED_ABSENT_AT_ISSUE",
  final_directory,
  final_receipt_path,
  private_sibling_pattern,
  final_directory_uid:1000,final_directory_gid:1000,
  private_mode:"0700",final_directory_mode:"0555",
  receipt_mode:"0444",receipt_nlink:1,
  signature_domain:"WS-PRE-P-R007-APPLICATION-RECEIPT-V1",
  content_formula_role:"R007_APPLICATION_RECEIPT_CONTENT",
  future_hash_state:"FORBIDDEN"
}
```

Pending receipt, transaction manifest, C scope and final progress record require
byte-equal TargetSpec. A future receipt hash in pending authority is forbidden.

### 13.3 exact FSM and pending head

```text
A/B:
  ISSUED -> CONSUMED -> LEASED(renew*) -> PREPARED
  A -> CLOSED_SUCCESS
  B -> DELEGATED_AND_CLOSED
  ISSUED|CONSUMED|LEASED -> CLOSED_INCIDENT

C pending:
  ISSUED/PENDING -> DELEGATED_AND_CLOSED
  ISSUED/PENDING -> CLOSED_INCIDENT

C effective:
  EFFECTIVE_UNCONSUMED -> CLOSED_INCIDENT
  EFFECTIVE_UNCONSUMED -> CONSUMED -> LEASED(renew*)
  -> PUBLISH_TRANSACTION_MANIFEST_BOOTSTRAP -> PREPARED
  -> FENCE_BOUND -> transaction mutation -> CLOSED_SUCCESS|CLOSED_INCIDENT

recovery:
  original CLOSED_INCIDENT + separately preauthorized recovery receipt
  -> retain current LockGuard -> RECOVERY_ISSUED
  original CLOSED_INCIDENT + recovery authority SIGNED_ABSENT
  -> RECOVERY_REQUIRED_NO_AUTHORITY -> clean release
  RECOVERY_REQUIRED_NO_AUTHORITY + separate recovery authority
  -> fresh lock acquisition
  RECOVERY_ISSUED -> RECOVERY_CONSUMED -> RECOVERY_LEASED(renew*)
  -> [if TARGET_SPEC: PUBLISH_TRANSACTION_MANIFEST_BOOTSTRAP]
  -> FENCE_BOUND -> RECOVERY_CLOSED_SUCCESS|INCIDENT
```

The recovery success branch is forbidden when observed progress is
`POSTCHECK_FAILED`; that predecessor can only reconcile durable records and end
in `RECOVERY_CLOSED_INCIDENT`.

Early/pending/effective-unconsumed close uses consume/lease/prepared
`SignedNotReached`; missing/null is forbidden. Recovery original consume is:

```text
ACTUAL RecordRef | SignedNotReached(reason:"NOT_REACHED")
```

and original incident is always ACTUAL before recovery issue.

Let B PREPARED head be H0. The pending C receipt binds
`issuance_expected_predecessor=H0`. Its ISSUED record H1 binds the receipt and has
`prior_record=H0`; it never appears inside the receipt. Handoff current HH is:

```text
HH = H1
or a contiguous descendant chain containing only LEASE_RENEWED for the same
prepared B attempt, with no close/revoke/foreign attempt/state change.
```

Under the same continuous OFD lock, handoff validates B current PREPARED,
consume/unrevoked/live lease, C ISSUED/PENDING, not-before<=event<expiry,
unrevoked/unconsumed, H0/H1/HH relation, same transaction/scope/N26/T1/X1/V1 and
unique pair digest. One `DELEGATED_AND_CLOSED` at `prior=HH` atomically changes:

```text
B PREPARED_EFFECTIVE -> CLOSED_SUCCESS_INEFFECTIVE
C PENDING_INEFFECTIVE -> EFFECTIVE_UNCONSUMED
```

No foreign intervening kind or future-head injection is allowed.

## 14. universal OFD lock, actor split and fencing

### 14.1 acquisition and held proof

Exact lock:

```text
/home/ddobagi/.local/share/hanium-dreamup/
walksafe-pre-p-authority-r007/journal/genesis/authority-journal-global.lock
```

The actor anchors from the share parent through authority root, journal and genesis
using `openat2` with `RESOLVE_BENEATH|RESOLVE_NO_SYMLINKS|
RESOLVE_NO_MAGICLINKS`, lstat/open/fstat equality against genesis `DirPhysical`,
then opens lock `O_RDWR|O_CLOEXEC|O_NOFOLLOW`. It validates
LockPhysical and calls:

```text
fcntl(F_OFD_SETLK,
      l_type=F_WRLCK,l_whence=SEEK_SET,l_start=0,l_len=0)
```

Whole-file Linux OFD only. `flock`, POSIX `F_SETLK`, blocking/range fallback,
dup/fork/pass_fds/SCM_RIGHTS/daemon inheritance are forbidden.

Successful acquisition creates an opaque:

```text
LockGuard={
  generation:<fresh 64hex>,
  anchored root/journal/genesis/lock tuples,
  owner_open_file_description,
  acquired_at,actor_role,valid=true
}
```

Any explicit unlock, close, fd error/replacement or generation mismatch irreversibly
sets `valid=false`; close/reopen cannot restore it. Before each mutation the
primary fd first reasserts the identical `F_OFD_SETLK` successfully while its
fd/open-file-description identity still matches the LockGuard generation. Then
an independently opened OFD attempts the same lock and must receive
`EAGAIN/EACCES`. If primary reassertion fails, identity/generation differs, or the
independent probe acquires, the probe immediately unlocks/closes, the guard is
invalidated, an incident is published when possible and no semantic mutation
syscall is invoked. Same-primary-fd reassertion, opaque generation or independent
probe alone is insufficient; all three plus anchored tuple equality are required.

No child inherits owner/probe fd. Parent/root/genesis/lock replacement, mount-id
drift, fd reuse and probe result drift fail.

### 14.2 LOCK_REQUIRED_GLOBAL and actors

`LOCK_REQUIRED_GLOBAL` is the complete global kind set in §12.1: every A/B/C/
recovery issue, consume, lease, renewal, prepared, delegate, close, revoke and
PUBLICATION_ABORTED, including the close-only
`RECOVERY_REQUIRED_NO_AUTHORITY` marker. Actors:

| actor | lock lifetime |
|---|---|
| issuer A/B | one replay→validate→append→records-parent-fsync |
| executor A/B | one lifecycle append; long build outside |
| pending C issuer/closer/revoker | one append, except handoff publisher retains |
| B→C publisher + ordinary C executor | same process, same FD continuously from handoff precheck to terminal close |
| crash custodian + preauthorized recovery executor | same process/FD only when separate recovery receipt was already valid at close |
| close-only custodian | original close + no-authority marker parent-fsync, then closes FD |
| later recovery executor | fresh FD/LockGuard after separately authorized marker replay |
| external revoker | one replay→validate→append; LOCK_BUSY means no record |

Lock order:

```text
OFD -> global numeric stream -> transaction numeric stream
-> target parent/target -> evidence or application receipt
```

For continuous B→C/effective-C/recovery actors, clean release is allowed only
after their terminal global record link and records-parent fsync. A/B and other
single-append actors instead release after that one record link and
records-parent fsync exactly as their table rows state.
Expiry does not steal a kernel-held lock. A stopped stale executor blocks recovery;
when it resumes, expired pre-guard causes mutation syscall0 and incident close.

### 14.3 fencing and guards

`fencing_epoch` is the integer sequence of ordinary or recovery
`LEASE_ACQUIRED`; renewal preserves it. First transaction evidence under each
executor is `FENCE_BOUND` and binds LockGuard generation, lock/root tuples, receipt,
consume, lease, global/transaction heads, next operation, live prefix and epoch.

Except for the narrowly scoped §4.2 transaction-manifest bootstrap, every
prewrite, CAS/NOREPLACE/mkdir, file/parent fsync, progress append, checkpoint,
exact6 and application receipt operation performs:

```text
LockGuard.valid and same generation
anchored tuples unchanged
same-primary-fd OFD reassert succeeds with identical range/flags
independent OFD conflict probe == EAGAIN/EACCES
receipt/consume active, unrevoked
lease active and fencing_epoch == grant global sequence
global replay head compatible
transaction replay head exact
next operation/ordinal/phase exact
live prefix/checkpoint exact
active_stage_c_transaction_count <= 1
active_fence_count <= 1
```

Pre-guard failure means semantic syscall not invoked and operation/write count0.
The final held proof and semantic syscall are dispatched by the single-threaded
supervisor; owner fd is private, async signal handlers never close/unlock it and
no other thread can mutate it. Nevertheless a fault injected after final proof
dispatch starts is not claimed atomic. From `mutation_dispatch_started`, any
unlock/close/I/O/crash uncertainty is never reported as write0, even if the
linearization result was not observed:

```text
MUTATION_MAY_HAVE_COMMITTED
-> preserve live bytes; rollback0
-> ordinary progress0 if not already durable
-> incident/fail-stop
-> higher-epoch recovery durability adjudication
```

Same lock prevents lease/revoke/head drift within the critical section; only
unavoidable syscall/fsync/crash ambiguity takes the latter branch.

### 14.4 unclean custodian adjudication

After ordinary process death/kernel release, custodian obtains the same OFD lock,
replays global/transaction/live state and requires no active unexpired holder. It
must hold a signed `UNCLEAN_C_CLOSE_ONLY` capability paired only with the exact
original incident and next global close slots; this capability cannot issue,
derive or synthesize recovery authority. It publishes/adopts original incident
and `CLOSED_INCIDENT` first:

| original state | consume/lease/prepared binding | mutation state |
|---|---|---|
| `EFFECTIVE_UNCONSUMED` | all `SIGNED_NOT_REACHED` | target mutation0 |
| `CONSUMED` | consume ACTUAL; lease/prepared not reached | target mutation0 |
| `LEASED` | consume/lease ACTUAL; prepared maybe not reached | progress/live adjudicated |
| `PREPARED` | all ACTUAL | progress/live adjudicated |

Recovery can begin only after original terminal close is parent-fsynced. If a
separately signed, challenge-bound recovery receipt already exists and its exact
scope/current predecessor is valid, the custodian may retain the same LockGuard
and publish recovery ISSUED, consume, lease-acquired with strictly higher epoch
and FENCE_BOUND in the original transaction stream.

An observed `POSTCHECK_FAILED` is a valid closed-incident recovery predecessor.
Recovery binds it and its Incident but may not delete/rewrite targets, rerun
full19, retry exact6 or publish an application receipt; it may only reconcile
already-durable journal state and close another incident as explicitly scoped.
A later repair requires a different approved plan/transaction.

If recovery authority is absent, the close-only custodian instead appends
`RECOVERY_REQUIRED_NO_AUTHORITY`, fsyncs the records parent, closes the original
OFD and stops. A later independently authorized recovery executor acquires a
fresh LockGuard, replays that marker as current, binds it in `RECOVERY_ISSUED`,
and only then consumes/leases/fences. No marker, close capability or observed
need composes authority. Stale original later performs persistent write0.

## 15. transaction manifest, progress, U1 and recovery

### 15.1 manifest sentinel and numeric schema

Transaction manifest exact payload:

```text
TransactionManifest={
 schema_version:"1.0.0",transaction_id,origin_c_attempt,
 C_receipt:FilePhysical,N26_binding,T1,X1,V1,
 ordered_exact26:[NormativeTargetRow references],
 aux_parent_spec:U1,application_receipt_target:ApplicationReceiptTargetSpec,
 source_checkpoint:FilePhysical,source_tail,
 initial_global_head:RecordRef,lock_contract:LockContract,
 created_at,signer_fingerprint
}
LockContract={
 authority_root:DirPhysical,journal:DirPhysical,genesis_dir:DirPhysical,
 lock:LockPhysical,
 open_flags:["O_RDWR","O_CLOEXEC","O_NOFOLLOW"],
 api:"F_OFD_SETLK",l_type:"F_WRLCK",l_whence:"SEEK_SET",
 l_start:0,l_len:0,
 resolution:["RESOLVE_BENEATH","RESOLVE_NO_SYMLINKS",
             "RESOLVE_NO_MAGICLINKS"],
 held_proof:["PRIMARY_REASSERT","OPAQUE_GENERATION",
             "INDEPENDENT_CONFLICT_EAGAIN_OR_EACCES"]
}
```

Signature domain:

```text
WS-PRE-P-R007-TRANSACTION-MANIFEST-V1 || NUL || JCS(payload)
```

Manifest contains no self path/hash/bytes. After atomic subtree publication,
reopen/hash yields:

The bound C receipt contains only the matching TransactionTargetSpec, so the edge
is `C receipt -> target path spec`, then `TransactionManifest -> C receipt
Physical`; there is no receipt↔manifest Physical cycle. Subsequent ACTUAL records
point to the already sealed manifest.

```text
ManifestSentinel={
 path:"transactions/<transaction-id>/transaction-manifest.json",
 sha256,bytes,sequence:"000000000000",
 progress_kind:"TRANSACTION_MANIFEST"
}
```

First `FENCE_BOUND.prior_progress` equals the sentinel; each later numeric record
is contiguous. `TRANSACTION_MANIFEST` is allowed only in the sentinel; it is
forbidden as a numeric record kind. Exact predecessor reference:

Ordinary bootstrap order is exactly C LEASED →
PUBLISH_TRANSACTION_MANIFEST_BOOTSTRAP → manifest subtree/sentinel → global C
PREPARED(ACTUAL manifest) → first FENCE_BOUND. Pre-manifest recovery order is
RECOVERY_LEASED → the same bootstrap operation → sentinel → first recovery
FENCE_BOUND; there is no recovery PREPARED kind. No target/U1/evidence/application
operation can occur between sentinel and FENCE_BOUND.

```text
ProgressRef={path,sha256,bytes,sequence,progress_kind}
progress_kind =
  "TRANSACTION_MANIFEST" |
  one of the numeric AllowedProgressKind values below
```

Transaction record signature:

```text
WS-PRE-P-R007-TRANSACTION-RECORD-V1 || NUL || JCS(payload)
```

Allowed progress kinds:

```text
FENCE_BOUND
PREWRITE
TARGET_CAS_COMMITTED
FILE_FSYNCED
PARENT_FSYNCED
PREFIX_ADVANCED
RECONCILED_PREFIX
AUX_PARENT_PREWRITE
AUX_PARENT_CREATED
AUX_PARENT_NEW_DIR_FSYNCED
AUX_PARENT_PARENT_FSYNCED
AUX_PARENT_VERIFIED
AUX_PARENT_RECONCILED
CHECKPOINT_CAS_COMMITTED
CHECKPOINT_RECONCILED_DURABLE
POSTCHECK_PASSED
POSTCHECK_FAILED
APPLICATION_RECEIPT_FINALIZED
APPLICATION_RECEIPT_PREWRITE
APPLICATION_PRIVATE_CREATED
APPLICATION_RECEIPT_LINKED
APPLICATION_PRIVATE_SEALED
```

Common:

```text
schema_version,transaction_id,progress_kind,sequence,prior_progress:ProgressRef
transaction_manifest:FilePhysical,origin_c_attempt,executor_attempt,executor_kind
consume,lease,fencing_epoch,lock_guard_generation,lock:LockPhysical
observed_global_head,event_at,durable_prefix,next_operation
```

Every numeric record is a strict tagged union with
`additionalProperties=false`. Exact kind fields in addition to Common:

| progress kind | exact additional fields |
|---|---|
| `FENCE_BOUND` | `receipt,capability_pair,global_grant,live_prefix,checkpoint_state,lock_contract_digest,primary_reassert,independent_probe` |
| `PREWRITE` | `ordinal,target,operation,before_state,after_candidate,next_syscall` |
| `TARGET_CAS_COMMITTED` | `ordinal,target,operation,syscall_result,linearized_after:FilePhysical` |
| `FILE_FSYNCED` | `ordinal,target,after:FilePhysical,file_fsync_evidence` |
| `PARENT_FSYNCED` | `ordinal,target,after:FilePhysical,parent:DirPhysical,parent_fsync_evidence,reopened:FilePhysical` |
| `PREFIX_ADVANCED` | `ordinal,prefix_start:1,prefix_end,prefix_digest,after:FilePhysical` |
| `RECONCILED_PREFIX` | `ordinal,observed_after:FilePhysical,file_fsync,parent_fsync,reopened:FilePhysical,second_mutation_count:0` |
| `AUX_PARENT_PREWRITE` | `aux_parent_spec,before,next_syscall:"mkdirat"` |
| `AUX_PARENT_CREATED` | `aux_parent_spec,mkdir_result,opened_dir:DirPhysical,fchown_result,fchmod_result` |
| `AUX_PARENT_NEW_DIR_FSYNCED` | `aux_parent_spec,new_dir:DirPhysical,dir_fsync_evidence` |
| `AUX_PARENT_PARENT_FSYNCED` | `aux_parent_spec,parent:DirPhysical,parent_fsync_evidence` |
| `AUX_PARENT_VERIFIED` | `aux_parent_spec,reopened:DirPhysical,allowed_child_prefix` |
| `AUX_PARENT_RECONCILED` | `aux_parent_spec,observed:DirPhysical,child_prefix,dir_fsync,parent_fsync` |
| `CHECKPOINT_CAS_COMMITTED` | `ordinal:26,target,operation:"CHECKPOINT_CAS_LAST",before:FilePhysical,after:FilePhysical,syscall_result` |
| `CHECKPOINT_RECONCILED_DURABLE` | `ordinal:26,after:FilePhysical,file_fsync,parent_fsync,reopened:FilePhysical,second_cas_count:0,prefix_advanced_count:0` |
| `POSTCHECK_PASSED` | `checkpoint_durable,ordered_exact6_results:[FilePhysical exact6],ordered_evidence_sets:[Exact6EvidenceSet exact6],aggregate_digest,all_status:"PASS"` |
| `POSTCHECK_FAILED` | `checkpoint_durable,ordered_exact6_results:[FilePhysical exact6],ordered_evidence_sets:[Exact6EvidenceSet exact6],failed_ids:[string exact1..6],incident:FilePhysical,aggregate_digest,retry_state:"FORBIDDEN",rollback_state:"FORBIDDEN"` |
| `APPLICATION_RECEIPT_PREWRITE` | `target_spec,private_nonce,private_relative_path,final_relative_path,before_states,next_syscall:"mkdirat"` |
| `APPLICATION_PRIVATE_CREATED` | `target_spec,private_nonce,private_dir:DirPhysical,mkdir_result,goal_gates_parent_fsync` |
| `APPLICATION_RECEIPT_LINKED` | `target_spec,private_nonce,private_dir:DirPhysical,receipt:FilePhysical,receipt_tmp_fsync,link_result,private_parent_fsync` |
| `APPLICATION_PRIVATE_SEALED` | `target_spec,private_nonce,private_dir:DirPhysical,receipt:FilePhysical,fchmod0555_result,private_dir_fsync,reopened_dir:DirPhysical` |
| `APPLICATION_RECEIPT_FINALIZED` | `target_spec,private_nonce,postcheck_passed:ProgressRef,final_dir:DirPhysical,receipt:FilePhysical,rename_result,goal_gates_parent_fsync,reopened_final:DirPhysical` |

No kind may borrow a field from another union member. Physical references include
`mnt_id`; every progress record is fsynced through the transaction records parent
before becoming a valid predecessor.

### 15.2 ordinary ordinals and U1

Ordinals1..25:

```text
PREWRITE
-> CAS_REPLACE|NOREPLACE linearization
-> TARGET_CAS_COMMITTED
-> file fsync -> FILE_FSYNCED
-> target parent fsync + reopen/hash -> PARENT_FSYNCED
-> PREFIX_ADVANCED
```

After durable prefix1..14 and before target15, exact U1:

```text
U1={
 id:"AUX_PARENT_001",
 path:"docs/control/goals/walksafe-completion-graph-v2-4-1",
 before:{state:"SIGNED_ABSENT"},
 after:{type:"directory",uid:1000,gid:1000,mode:"0775",nlink:2}
}
```

Sequence:

```text
AUX_PARENT_PREWRITE
-> mkdirat("walksafe-completion-graph-v2-4-1",0700) NOREPLACE
-> openat O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW
-> fchown(fd,1000,1000) -> fchmod(fd,0775)
-> fstat exact uid/gid/mode/nlink2 -> AUX_PARENT_CREATED
-> fsync(new directory) -> AUX_PARENT_NEW_DIR_FSYNCED
-> fsync(docs/control/goals) -> AUX_PARENT_PARENT_FSYNCED
-> reopen O_DIRECTORY|O_NOFOLLOW, lstat=fstat exact
-> AUX_PARENT_VERIFIED
-> target15
```

No PREFIX_ADVANCED for U1. U1 is outside exact26/T1/A/M_after. X1, transaction and
application receipt bind it.

Higher-epoch recovery may act only after durable AUX_PARENT_PREWRITE. If absent it
performs the same create sequence. If present it requires exact identity and
contents either empty or the contiguous target15..25 prefix implied by progress,
fsyncs/reopens it and writes AUX_PARENT_RECONCILED. Extra/non-prefix content fails.

### 15.3 prefix reconciliation and checkpoint26

For inferred ordinals1..25 recovery performs:

```text
lstat -> open O_NOFOLLOW -> fstat equality -> after hash/bytes/mode/nlink
-> fsync file -> fsync parent -> reopen/hash/identity
-> RECONCILED_PREFIX -> records-parent fsync
-> PREFIX_ADVANCED
```

No second CAS. Non-prefix/ambiguous state fails.

Checkpoint26 normal:

```text
PREWRITE -> CHECKPOINT_CAS_COMMITTED
-> FILE_FSYNCED -> PARENT_FSYNCED/reopen
```

Prior durable prefix must be1..25 and U1 verified. No checkpoint
PREFIX_ADVANCED. If live checkpoint is after bytes but progress was lost,
higher-epoch recovery performs file fsync, checkpoint parent fsync, reopen/hash and
publishes:

```text
CHECKPOINT_RECONCILED_DURABLE={
 prior_durable_prefix:"1..25",checkpoint_after:FilePhysical,
 file_fsync,parent_fsync,reopened_identity,
 second_cas_count:0,prefix_advanced_count:0
}
```

`POSTCHECK_PASSED.checkpoint_durable` and
`POSTCHECK_FAILED.checkpoint_durable` use the same tagged union:

```text
PARENT_FSYNCED(normal ordinal26) |
CHECKPOINT_RECONCILED_DURABLE(recovery)
```

Pre-rename live-before permits first checkpoint CAS; post-rename live-after uses
normal progress or reconciliation only; mixed/unknown state fails.

### 15.4 application receipt private sibling

`ApplicationReceiptTargetSpec.content_formula_role=
"R007_APPLICATION_RECEIPT_CONTENT"` resolves only to this strict payload:

```text
ApplicationReceiptPayload={
 schema_version:"WS-PRE-P-R007-APPLICATION-RECEIPT-V1",
 receipt_id,
 plan:FilePhysical,plan_reviews:[FilePhysical exact2],
 stage_c_receipt:FilePhysical,finalizing_executor_receipt:FilePhysical,
 transaction_manifest:FilePhysical,
 application_predecessor:ProgressRef(progress_kind:"POSTCHECK_PASSED"),
 n26_digest,T1,X1,V1,
 ordered_applied_targets:[FilePhysical exact26],
 aux_parent:DirPhysical,
 checkpoint_after:FilePhysical,
 checkpoint_tail:{sequence:40,event_sha256},
 ordered_exact6_results:[FilePhysical exact6],
 runtime_environment_manifest:FilePhysical,
 runtime_physical_receipt:FilePhysical,
 m_after_count:627,
 m_after_path_set_sha256:
  "2c98e5795a5bf26e90a2487f41899d2a88114a3e4e02f830924f33066b9cdbb6",
 m_after_content_set_sha256,
 official_credit_delta:0,
 gate_claim:"PRE_P_VALIDATION_ONLY",
 issued_at,signer_fingerprint
}
ApplicationReceipt={
 payload:ApplicationReceiptPayload,
 signature
}
signature_input =
 ASCII("WS-PRE-P-R007-APPLICATION-RECEIPT-V1") || NUL ||
 RFC8785_JCS(ApplicationReceiptPayload)
```

`receipt_id` is exactly
`WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-APPLIED-20260731-001`.
The plan reviews are the two frozen physical R007 reviews in §18 order
[formal,skeptical]. `ordered_applied_targets` follows N26 ordinals1..26.
`stage_c_receipt` is the original effective C receipt;
`finalizing_executor_receipt` is that same receipt for ordinary completion or
the separately authorized recovery receipt for recovery completion.
`ordered_exact6_results` follows §16. `runtime_environment_manifest` and
`runtime_physical_receipt` are the hosted-cpu external runtime objects actually
used by Stage C. `m_after_content_set_sha256` is computed by §17 only after all
627 after bytes seal; no FutureSealed value is permitted in this payload.

The payload deliberately excludes its own path/hash/bytes, its private/final
directory Physical, `APPLICATION_RECEIPT_FINALIZED` and the later global close.
The receipt therefore points only inward to `POSTCHECK_PASSED`; the later final
progress binds the receipt FilePhysical and reopened final directory, and
`CLOSED_SUCCESS.terminal_evidence` binds that final progress. Any
`terminal_transaction_progress` field in the receipt, alternate signature
domain, guessed content hash or self/future reference is forbidden.

Exact final:

```text
docs/control/execution/goal-gates/
WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-APPLIED-20260731-001/
application-receipt.json
```

After checkpoint durable and exact6 PASS, same LockGuard:

```text
APPLICATION_RECEIPT_PREWRITE binds one fresh 64hex nonce,
  exact private sibling path and exact final path
mkdirat goal-gates/
  .WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-APPLIED-20260731-001.tmp.<nonce>
  mode0700 NOREPLACE
-> fsync goal-gates
-> open private sibling O_DIRECTORY|O_NOFOLLOW
-> APPLICATION_PRIVATE_CREATED
-> O_TMPFILE, write signed receipt, fchmod0444, fsync
-> linkat AT_EMPTY_PATH as application-receipt.json NOREPLACE
-> fsync private directory
-> reopen receipt/hash/bytes/mode0444/nlink1
-> APPLICATION_RECEIPT_LINKED
-> fchmod private directory 0555
-> fsync private directory and reopen/verify
-> APPLICATION_PRIVATE_SEALED
-> renameat2(private sibling, exact final directory, RENAME_NOREPLACE)
-> fsync goal-gates and reopen exact final/receipt
-> APPLICATION_RECEIPT_FINALIZED
```

Capability operation is `APPLICATION_RECEIPT_FINALIZE`; the only terminal durable
progress kind is `APPLICATION_RECEIPT_FINALIZED`. The record binds TargetSpec, receipt/final
directory Physical, temp/link/fsync/seal/rename evidence and exact6 aggregate.
Existing exact sealed final is fsync/reopen/adopted. Wrong collision fails; private
partial sibling is nonauthority and only the same transaction, exact durable
PREWRITE nonce/path and matching phase progress may verify and continue. An
unbound nonce/path is never scanned or adopted. No overwrite/delete.

## 16. Stage-C hosted exact6

Stage C does not rerun full19/regression. It uses hosted-cpu env/pack and RO live
root preserving final inodes. The Stage-A after-control builder publishes actual
`after-control/<slot>/stage-c-exact6-contract.json`; candidate is the sole
Stage-C contract and is auxiliary/non-exact26.

```text
StageCExact6Row={
 ordinal,id,commands:[Token arrays],cwd:"/work/walksafe",
 env_additions,timeout_seconds,
 stdout_cap:268435456,stderr_cap:268435456,
 expected_rc:[integer],input_roles:[string],assertions:[string]
}
StageCExact6Contract={
 schema_version:"WS-PRE-P-R007-STAGE-C-EXACT6-CONTRACT-V1",
 producer_builder:FilePhysical("builders/after-control-builder.py"),
 producer_inputs:FilePhysical("inputs/after-control.inputs.json"),
 ordered_ids:[
  "001-live-exact26","002-activation-seal","003-continuation-quick",
  "004-goal-quick","005-routing-validate","006-control-and-state"
 ],
 rows:[StageCExact6Row exact6],
 contract_digest,status:"SEALED",signature
}
contract_digest=SHA256(
 ASCII("WS-PRE-P-R007-STAGE-C-EXACT6-CONTRACT-V1") || NUL ||
 RFC8785_JCS({ordered_ids,rows})
)
```

The six rows, in that exact order, are:

```json
{
 "ordinal":1,"id":"001-live-exact26",
 "commands":[["/env/bin/python","-I","-S","-B",
  "scripts/check_walksafe_project_continuation_v2_4_1.py",
  "--root","/work/walksafe",
  "--checkpoint","docs/control/walksafe-project-continuation-checkpoint.json",
  "--verify-exact26","/input/transaction-manifest.json"]],
 "cwd":"/work/walksafe","env_additions":[],
 "timeout_seconds":300,"stdout_cap":268435456,"stderr_cap":268435456,
 "expected_rc":[0],
 "input_roles":["CONTINUATION_CHECKER","CHECKPOINT","TRANSACTION_MANIFEST",
                "LIVE_EXACT26","AUX_PARENT_001","AFTER_ROUTING"],
 "assertions":["LIVE26_N26_U1_ROUTING_IDENTITIES_EQUAL_TRANSACTION"]
}
{
 "ordinal":2,"id":"002-activation-seal",
 "commands":[["/env/bin/python","-I","-S","-B",
  "scripts/check_walksafe_goal_graph_v2_4_1.py",
  "--root","/work/walksafe",
  "--checkpoint","docs/control/walksafe-project-continuation-checkpoint.json",
  "--verify-activation-seal"]],
 "cwd":"/work/walksafe","env_additions":[],
 "timeout_seconds":300,"stdout_cap":268435456,"stderr_cap":268435456,
 "expected_rc":[0],
 "input_roles":["GOAL_GRAPH_CHECKER","CHECKPOINT","V24_CONTROL",
                "V241_CONTROL","MANAGED_GOALS"],
 "assertions":["SEQ40_V24_SUPERSEDED_V241_ACTIVE_CHECKPOINT_SEALED"]
}
{
 "ordinal":3,"id":"003-continuation-quick",
 "commands":[["/env/bin/python","-I","-S","-B",
  "scripts/check_walksafe_project_continuation_v2_4_1.py",
  "--root","/work/walksafe",
  "--checkpoint","docs/control/walksafe-project-continuation-checkpoint.json",
  "--mode","QUICK_POSTCHECK"]],
 "cwd":"/work/walksafe","env_additions":[],
 "timeout_seconds":300,"stdout_cap":268435456,"stderr_cap":268435456,
 "expected_rc":[0],
 "input_roles":["CONTINUATION_CHECKER","CHECKPOINT","V241_CONTROL"],
 "assertions":["CONTINUATION_QUICK_PASS"]
}
{
 "ordinal":4,"id":"004-goal-quick",
 "commands":[["/env/bin/python","-I","-S","-B",
  "scripts/check_walksafe_goal_graph_v2_4_1.py",
  "--root","/work/walksafe",
  "--checkpoint","docs/control/walksafe-project-continuation-checkpoint.json",
  "--mode","QUICK_POSTCHECK"]],
 "cwd":"/work/walksafe","env_additions":[],
 "timeout_seconds":300,"stdout_cap":268435456,"stderr_cap":268435456,
 "expected_rc":[0],
 "input_roles":["GOAL_GRAPH_CHECKER","CHECKPOINT","V241_CONTROL",
                "MANAGED_GOALS"],
 "assertions":["GOAL_QUICK_PASS"]
}
{
 "ordinal":5,"id":"005-routing-validate",
 "commands":[["/usr/bin/bash",
  "/work/walksafe/scripts/run_walksafe_test_layers_20260711.sh",
  "--layer","validate","--root","/work/walksafe",
  "--checkpoint","docs/control/walksafe-project-continuation-checkpoint.json",
  "--control-selector","AFTER_SEQ40_V241",
  "--routing-manifest",
  "docs/control/goals/walksafe-completion-graph-v2-4-1/test-routing-after-seq40-v2.4.1.json"]],
 "cwd":"/work/walksafe",
 "env_additions":[{"name":"PYTHON_BIN","value":"/env/bin/python"}],
 "timeout_seconds":600,"stdout_cap":268435456,"stderr_cap":268435456,
 "expected_rc":[0],
 "input_roles":["TEST_LAYER_RUNNER","CHECKPOINT","AFTER_ROUTING",
                "ROUTING_LITERAL132_SET","PYTHON_RUNTIME"],
 "assertions":["DISCOVERED_132","ASSIGNED_132","UNASSIGNED_0",
               "DIRECT_5","DUPLICATE_0","MISSING_0"]
}
{
 "ordinal":6,"id":"006-control-and-state",
 "commands":[
  ["/env/bin/python","-B","-m","pytest","-p","no:cacheprovider","-q",
   "tests/walksafe_project_continuation_v2_4_1_successor_20260731.py",
   "tests/walksafe_goal_graph_v2_4_1_successor_20260731.py",
   "tests/walksafe_v2_4_1_seq40_transition_successor_20260731.py"],
  ["/env/bin/python","-I","-S","-B",
   "scripts/check_walksafe_project_continuation_v2_4_1.py",
   "--root","/work/walksafe",
   "--checkpoint","docs/control/walksafe-project-continuation-checkpoint.json",
   "--print-gate-repository-state",
   "--gate-event-id",
   {"state":"RUNTIME_ACTUAL","role_id":"FULL19_GATE_EVENT_ID"}]
 ],
 "cwd":"/work/walksafe","env_additions":[],
 "timeout_seconds":1800,"stdout_cap":268435456,"stderr_cap":268435456,
 "expected_rc":[0,0],
 "input_roles":["ROW17_DIRECT3","ROW17_V241_CONTROL_SET",
                "ROW17_MANAGED_GOALS_SET","CONTINUATION_CHECKER","CHECKPOINT",
                "M_AFTER627_WORKTREE_INVENTORY","DOUBLE_READ_GIT_INVENTORY_SET",
                "RUNTIME_ACTUAL_BINDING"],
 "assertions":["DIRECT3_PASS","OLD_V24_CURRENT_0",
               "ONE_CANONICAL_REPOSITORY_STATE_JSON","GATE_EVENT_ID_MATCH"]
}
```

The contract may contain the typed RuntimeActual token only in row006 command2.
Each sealed intent substitutes the one actual string from the already-sealed
Stage-B RuntimeActualBinding, binds that FilePhysical and records
`substitution_count=1`; all sealed argv tokens are strings. The row006 pytest
command omits `-I` for the same reviewed repository-import reason as §9.2 and
inherits the cleared/PYTHONNOUSERSITE sandbox. Each intent binds the actual
contract FilePhysical, command executable/scripts/input role expansions, hosted
environment logical manifest/physical receipt, pack manifest, live-root
observation, timeout/caps and assertions.

The five pre-result siblings and aggregate binding are strict:

```text
Exact6EvidenceSet={
 ordinal,id,executor_attempt,
 canonical_relative_directory:
  "transactions/<transaction-id>/evidence/stage-c-exact6/"
  "<executor-attempt-key>/<id>",
 ordered_members:[
  {role:"intent",file:FilePhysical("intent.json")},
  {role:"stdout",file:FilePhysical("stdout.bin")},
  {role:"stderr",file:FilePhysical("stderr.bin")},
  {role:"raw_trace",file:FilePhysical("access-trace.raw")},
  {role:"normalized_trace",file:FilePhysical("access-trace.json")}
 ]
}
Exact6ResultCommon={
 schema_version:"WS-PRE-P-R007-EXACT6-RESULT-V1",
 ordinal,id,executor_attempt,contract:FilePhysical,
 commands,inputs:[FilePhysical],
 environment_root_manifest:FilePhysical,
 environment_physical_receipt:FilePhysical,
 pack_manifest:FilePhysical,evidence_set:Exact6EvidenceSet,
 command_results:[{ordinal,argv_digest,rc,signal,timed_out}],
 assertion_results
}
Exact6Result =
 Exact6ResultCommon + {
   status:"PASS",all_expected_rc:true,all_assertions_passed:true
 } |
 Exact6ResultCommon + {
  status:"FAIL",failed_command_ordinals:[integer],
   failed_assertion_ids:[string],primary_failure_class:
    "COMMAND_RC"|"SIGNAL"|"TIMEOUT"|"ASSERTION"|"TRACE_OR_CAP"
 }
```

Unknown/borrowed union fields fail. Rows001..005 have command_results length1;
row006 length2. A PASS requires every expected rc, signal absent,
`timed_out=false`, all assertions true, exact input trace and caps. Any complete
evidence-bearing semantic violation is FAIL; `primary_failure_class` is the
lowest command ordinal failure, with class precedence
`SIGNAL,TIMEOUT,COMMAND_RC,TRACE_OR_CAP,ASSERTION`. The result excludes its own
`result.json`; its evidence_set binds only the preceding five siblings. For
ordered index i, evidence/result id, executor attempt and directory must match,
and `ordered_exact6_results[i].path` must be that directory's `result.json`.

All six read-only invocations run in order even after a semantic FAIL, while the
LockGuard remains valid, so the terminal aggregate always has exact6 results and
exact6 evidence sets. All PASS publishes `POSTCHECK_PASSED`. Any FAIL publishes/
adopts all exact evidence/results, publishes an Incident with reason
`POSTCOMMIT_VALIDATION_FAILED`, then publishes `POSTCHECK_FAILED` binding that
incident, the ordered exact6 arrays and failed IDs, parent-fsyncs the transaction
record, and finally publishes global `CLOSED_INCIDENT`. Only separately
authorized recovery may continue. Infrastructure failure that prevents a
complete immutable result/evidence set closes with `RECOVERY_FAILURE` without
inventing `POSTCHECK_FAILED`. A higher-epoch crash recovery may adopt an exact
complete sibling/result and execute only the next invocation proven never
dispatched; it never re-executes a completed/ambiguous invocation. After a
semantic FAIL, exact6 retry is forbidden. Rollback and full19 rerun are always
forbidden.

## 17. C0, exact26 arithmetic and hash topology

Literal sets:

```text
S = seq39 managed_changed_paths, count603
C = {
  scripts/run_walksafe_test_layers_20260711.sh,
  tests/requirements.lock
}
A = exact NOREPLACE targets ordinals3..25, count23
Q = {docs/control/walksafe-project-continuation-checkpoint.json}
```

Current exact:

```text
C intersection S = {scripts/run_walksafe_test_layers_20260711.sh}
C - S = {tests/requirements.lock}
A intersection S = empty
A intersection C = empty
Q not in S or A or C
M_after = sort_utf8(unique(S union A union C)-Q)
M_after_count = 627
```

There is no `C subset S` assertion. New R007 revision-owned paths yield:

```text
path_set_bytes =
  concat(UTF8(path)||LF for path in M_after ordered by UTF-8 bytes)
projected_path_set_sha256 =
  2c98e5795a5bf26e90a2487f41899d2a88114a3e4e02f830924f33066b9cdbb6
```

Content:

```text
content_set_bytes =
  concat(UTF8(path)||NUL||ASCII(final file sha256)||LF
         for path in M_after order)
content_set_sha256=SHA256(content_set_bytes)
```

is FUTURE_SEALED and computed only after all after bytes are sealed. Current
`source_commit_or_snapshot.content_set_sha256`
`60eba216d34e53dea2aface304738ebb07b0067aaacdd38938ed13f99aa3a215`
cannot be carried forward.

Future checkpoint:

```text
working_tree_snapshot.managed_changed_path_count=627
working_tree_snapshot.managed_changed_paths=M_after
working_tree_snapshot.path_set_sha256=
  2c98e5795a5bf26e90a2487f41899d2a88114a3e4e02f830924f33066b9cdbb6
working_tree_snapshot.content_set_sha256=recomputed future
session_handoff.changed_files=M_after
source_commit_or_snapshot.file_count=627
source_commit_or_snapshot.path/content hashes=working snapshot hashes
```

`source_commit_or_snapshot.changed_files` does not exist and must not be added or
asserted. U1 is a directory outside S/A/C/Q/T1/M_after and does not change 627.

```text
ACTIVE_TARGET_COUNT=26
CAS_REPLACE=2
NOREPLACE=23
CHECKPOINT_CAS_LAST=1
DUPLICATE_PATH_COUNT=0
AUX_PARENT_COUNT_OUTSIDE_TARGETS=1
APPLICATION_RECEIPT_OUTSIDE_TARGETS=true
```

Source snapshot exact exclusions:

```text
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007-independent-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  PRE-P-VALIDATION-CONVERGENCE-DESIGN-BUILD-PLAN-R007-independent-skeptical-review-r001.md
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-candidate-r007/**
plans/features/2026-07-29_walksafe_plan_rebaseline_r001/
  pre-p-validation-convergence-authority-resolved-r007/**
docs/control/execution/goal-gates/
  WS-GOAL-GRAPH-V2-4-1-PRE-P-VALIDATION-CONVERGENCE-APPLIED-20260731-001/**
```

`**` is a signed prefix type, not runtime glob.

Hash topology:

```text
S0 -> E0 -> C0 -> T1(N26 exact table) -> X1(N26,U1,recovery,exact6)
-> external attempt reviews -> V1 -> C receipt
-> transaction sentinel/progress -> application receipt
```

Managed bytes cannot reference C0/T1/X1/V1/C receipt/application receipt or
descendants. Typed graph rejects self/reverse/future edges and SCC.

## 18. review classes and V1

| review class | exact location | authority | source snapshot | V1 |
|---|---|---|---|---|
| R007 physical formal plan review | repository path in §17 | nonauthoritative physical review | exact excluded | not consumer |
| R007 physical skeptical plan review | repository path in §17 | nonauthoritative physical review | exact excluded | not consumer |
| Stage-A attempt review | `journal/reviews/stage-a/<key>/candidate-independent-review.md` | external attempt evidence | outside repo | Stage-B input |
| Stage-B regression review | `journal/reviews/stage-b/<key>/regression-final-independent-review.md` | external attempt evidence | outside repo | V1 input |
| Stage-B resolved review | `journal/reviews/stage-b/<key>/resolved-independent-review.md` | external attempt evidence | outside repo | V1 input |

The strict §10 `ResolvedReviewSubjectManifest` at
`resolved-review-subject-manifest.json` excludes itself and both reviews and
binds the complete resolved/equality/seed/runtime/validation evidence arrays.
Reviews are written only after subject seal.
Stage-C:

```text
journal/attempts/stage-c/<key>/review-binding.json
```

binds X1, subject manifest, the two Stage-B reviews and exact `0/0/0` verdicts.

```text
V1 = SHA256(
 ASCII("R007_RESOLVED_REVIEW_BINDING_V1") ||
 NUL || RFC8785_JCS(review-binding payload)
)
```

Review does not reference V1; C receipt is first V1 consumer. Plan reviews never
enter V1. Missing/mutated/replaced review, review-before-seal or cycle fails.

## 19. required negative, concurrency and crash matrix

Every pre-spawn/static negative requires rc2, `child_exec_count=0` and
`persistent_write_count=0`. A post-publication fault may retain only the one
explicitly listed durable object. Every test reopens through anchored fds and
checks global numeric continuity, signatures, stable tuples and absence of
unlisted paths.

### 19.1 bootstrap, seed, publication and materialization

| ID | fixture and exact expected result |
|---|---|
| `BOOT-01` | two complete whole-root bootstraps race: one RENAME_NOREPLACE winner; loser adopts only byte/tuple/signature-identical final |
| `BOOT-02` | fault at every file fsync, directory fsync, root rename and share-parent fsync cut: final absent or one complete valid root; temp root never authority |
| `BOOT-03` | final symlink, partial tree, extra child, wrong root/journal/genesis/lock tuple or genesis signature: adopt0, mutation0 |
| `BOOT-04` | bootstrap Python source hash/version/closure drift, missing loader/library/stdlib, broad host bind: builder spawn0 |
| `SEED-01` | missing/extra/reordered exact45 entry, source/destination path mismatch, nonzero external review or challenge mismatch: issuance0 |
| `SEED-02` | manual source write, builder self-generation, network fetch, hardlink/reflink alias, source/destination same inode: Stage-A receipt0 |
| `SEED-03` | crash during fresh-inode copy or bottom-up fsync: subject is absent/non-authoritative; retry uses a fresh attempt key and exact seed |
| `PUB-01` | same path/same immutable bytes races: winner1; loser reopens, verifies and adopts; overwrite/delete0 |
| `PUB-02` | same path/different bytes, schema or signature races: one final; loser emits signed publication-abort transition and incident under the lock |
| `PUB-03` | raw unsigned bytes are rejected for a signed schema and a signed wrapper is rejected as raw stdout/stderr/trace; immutable-byte equality remains separate from signature equality |
| `PARENT-01` | absent attempt parent without exact challenge-bound `CREATE_ISSUANCE_PARENT`, wrong key or reused capability: mkdir0 |
| `MAT-01` | missing/extra schema/template/input/builder, undeclared read/write, wrong argv/cwd/env/slot/output: candidate seal0 |
| `MAT-02` | build-01/build-02 bytes differ, candidate inode aliases either build, nlink>1 or receipt omits raw six: equality0 |
| `MAT-03` | row2 drops any of control-core/lane-b-final/apply receipts or maps composite producer to an inferred builder: N26 seal0 |
| `MAT-04` | CandidateTargetMap points to aggregate receipt/subject/review, receipt includes itself, or graph has SCC/reverse edge: seal0 |
| `MAT-05` | per-unit ordered input/read/output literal array drift, actual input manifest omitted, auxiliary full19 output omitted or target-shaped resolved overlay lacks any exact26 source: build0 |
| `MAT-06` | core/runtime command manifests are collapsed, RuntimeInputManifest points back to either command manifest, or regression/exact6 role contract is a Stage-B future: subject seal0 |
| `MAT-07` | Stage-B regression input is published before both candidate resolved receipts, receipt path differs from builder output, equality receipt missing, or resolved review subject has a forward/review edge: Stage-B review0 |
| `STAGEA-BWRAP-01` | Stage A uses runtime-pack EnvironmentRole, `/work/walksafe`, noncanonical invocation ID/input alias, second RW bind, live tree/authority mount or payload-authored raw/receipt: spawn0/seal0 |
| `N26-01` | row/count/order/path/class/op/producer/parent-transition drift, duplicate path or checkpoint not ordinal26: fail |
| `N26-02` | made-up current N26 hex, compressed/untyped before state, mnt_id/control-tail loss, incomplete expansion or three-way logical inequality: fail |
| `U1-01` | existing absent parent: exactly one mkdir winner, parent fsync and reopen; loser adopts only exact uid/gid/mode/nlink2 tuple |
| `U1-02` | U1 symlink, regular file, wrong mode/owner/nlink, unexpected child or preexisting unbound parent: target15 mutation0 |

### 19.2 journal slot and universal OFD concurrency

| ID | fixture and exact expected result |
|---|---|
| `SEQ-SLOT-00` | first global prior is not exact genesis sequence0, or first transaction prior is not exact manifest ProgressRef sequence0: replay0 |
| `SEQ-SLOT-01` | same global numeric N/different kinds race: one `N.json`; loser replays under the same OFD lock and cannot reuse payload at N+1 |
| `SEQ-SLOT-02` | same N/same kind/different payload: final exact1; loser persistent write0 except required abort/incident at later valid slots |
| `SEQ-SLOT-03` | transaction PREWRITE and recovery FENCE_BOUND target same transaction N: one numeric file only |
| `SEQ-SLOT-04` | nonnumeric filename, kind suffix, duplicate slot, gap, overflow or invalid prior hash: append/target0 |
| `SEQ-SLOT-05` | crash before temp fsync or after temp fsync/before link: numeric final absent; no reservation file |
| `SEQ-SLOT-06` | crash after link before/after parent fsync: restart accepts absent or one complete valid record only; never an empty slot |
| `SEQ-SLOT-07` | global or transaction slot skip, invalid-slot overwrite or loser retry with stale head: rc2 |
| `LOCK-01` | lock symlink/nonregular/nlink2/wrong owner/mode/size, parent/genesis replacement, mount-id or lstat/fstat mismatch: acquire0 |
| `LOCK-02` | independent OFD conflict probe while guard held must return EAGAIN/EACCES; success, same-fd-only reassertion or probe alias fails held proof |
| `LOCK-03` | holder vs contender: holder1, contender `LOCK_BUSY`, contender persistent write0 |
| `LOCK-04` | flock/POSIX fallback, mixed API, dup/pass_fds/SCM_RIGHTS/daemon inheritance or child fd visibility: fail |
| `LOCK-05` | supervisor death closes original OFD; payload death while supervisor lives leaves contender busy |
| `LOCK-06` | A/B per-append release is allowed only after linked record parent fsync; B→C, C and recovery retain one continuous guard through terminal close |
| `LOCK-07` | any global A/B/C/recovery/abort publication without the universal journal OFD guard: syscall0 |
| `CONC-A-01` | A renewal vs A prepared/incident on same head: one next record; loser stale-head mutation0 |
| `CONC-AB-01` | A close/delegate vs B issuance: B issuance succeeds only after exact A terminal/predecessor head; otherwise zero |
| `CONC-B-01` | B renewal vs B prepared/incident: one next record; expired or stale loser cannot reopen B |
| `CONC-BC-01` | B prepared/delegation vs C issuance: exact one current B→C pair digest and one unconsumed C |
| `CONC-C-01` | ordinary C CLOSED_SUCCESS vs application-receipt finalization: serialized under continuous guard; success close before durable receipt is impossible; incident close remains legal |
| `CONC-CREC-01` | ordinary C incident/terminal vs recovery issuance/consume: exact one wins current head; active transaction/fence never exceeds1 |
| `CONC-APP-01` | two application receipt finalizers: one RENAME_NOREPLACE winner; loser adopts only exact final FilePhysical |

Invariant checked after every concurrency fixture:

```text
active_stage_c_transaction_count <= 1
active_fence_count <= 1
global_slot_names == sorted contiguous 12-digit numeric JSON names
transaction_slot_names == sorted contiguous 12-digit numeric JSON names
lock_order == OFD -> global replay/head -> transaction -> target
all_global_publications_bind_same_genesis_lock_identity == true
each_publication_binds_one_valid_critical_section_generation == true
B_to_C_through_terminal_generation_is_continuous == true
```

### 19.3 fence, recovery, ordinal and application-receipt faults

| ID | fixture and exact expected result |
|---|---|
| `FENCE-01` | ordinary dies before FENCE_BOUND: target0; recovery epoch strictly greater |
| `FENCE-02` | epoch zero/equal/decrease, wrong grant tuple, renewal changes epoch or missing FENCE_BOUND: operation0 |
| `FENCE-03` | stale ordinary/recovery actor, consumed receipt, mismatched progress head or LockGuard generation: syscall0 |
| `FENCE-04` | lease expiry immediately pre-mutation: mutation0; expiry immediately post-mutation enters ambiguity/reconciliation, never claimed write0 |
| `FENCE-05` | fault-forced unlock after final proof dispatch starts: mark ambiguous, claim neither syscall0 nor success, preserve live state and require higher-epoch adjudication |
| `RECOVERY-01` | recovery observed head is stale, or original lifecycle state is outside exact `EFFECTIVE_UNCONSUMED|CONSUMED|LEASED|PREPARED` plus ACTUAL/SIGNED_NOT_REACHED bindings: issuance/consume/fence0 |
| `RECOVERY-02` | recovery without higher epoch, original capability pair, exact transaction or continuous new custodian guard: operation0 |
| `RECOVERY-03` | preauthorized exact recovery may retain the close LockGuard; without that separate receipt custodian must append no-authority marker, fsync, release, and later recovery must reacquire |
| `RECOVERY-04` | close-only capability, no-authority marker or observed need is used to synthesize recovery authority: issuance0 |
| `ORD-CRASH-01` | for ordinals1..14 and16..25, every prewrite/temp-fsync/link-or-CAS/file-fsync/parent-fsync/reopen/progress cut yields absent target or exact durable after target; higher recovery reconciles only after full proof |
| `U1-CRASH-01` | crash at U1 mkdir, chmod/chown, parent fsync or reopen: target15 remains blocked until exact parent tuple is durable/adopted |
| `ORD15-CRASH-01` | target15 publication after U1 follows the ordinary prefix rule; no progress may claim it before target and parent durability proof |
| `CHECKPOINT-CRASH-01` | ord26 CAS durable but progress absent: higher recovery proves file+parent durability and writes `CHECKPOINT_RECONCILED_DURABLE`; second CAS0 and no PREFIX record |
| `CHECKPOINT-CRASH-02` | ord26 progress exists but target/hash/seq40 differs: terminal failure and incident; rollback/overwrite0 |
| `EXACT6-CRASH-01` | every exact6 invocation/raw sibling/result publication cut: restart adopts exact siblings or fails; a check is PASS only with exact six physical files |
| `EXACT6-CRASH-02` | complete semantic FAIL: exact6 evidence/results seal, POSTCOMMIT_VALIDATION_FAILED Incident, POSTCHECK_FAILED and CLOSED_INCIDENT in order; application receipt/retry/rollback/full19 count0 |
| `APP-CRASH-01` | PREWRITE nonce, private-created, receipt-linked, private-sealed, final rename and parent-fsync cuts: absent or one exact phase-bound receipt; unbound private sibling adoption0 |
| `APP-CRASH-02` | durable final receipt before progress: recovery emits only `APPLICATION_RECEIPT_FINALIZED` after proof under operation `APPLICATION_RECEIPT_FINALIZE`; receipt rewrite0 |
| `TERMINAL-CRASH-01` | transaction terminal durable before global close: recovery publishes global close only; target/checkpoint/receipt rewrite0 |

For each ordinary target, the fixture also cuts immediately before and after each
anchored guard, syscall, file fsync, parent fsync and progress publication. A
post-syscall guard failure is always ambiguous until reopened physical equality
and durability are proven.

### 19.4 runtime-pack, sandbox, raw evidence and full19

| ID | rejected fixture |
|---|---|
| `PACK-01` | missing shebang/ELF/Python/Node/Java/Gradle/SDK/locale/timezone/NSS/CA/config transitive member |
| `PACK-02` | logical manifest contains dev/inode/host absolute path, physical receipt omitted, or any of build01/build02/candidate/external inodes alias |
| `PACK-03` | discovery and build are collapsed, either lacks exact six raw files, or traced acquisition differs from declared source provenance |
| `PACK-04` | device/FIFO/socket/hardlink/nlink>1, escaping/dangling/cyclic link, host fallback or unreviewed download |
| `PACK-05` | build trace reads a source/origin object outside sealed discovery ordered set or omits a declared source: pack seal0 |
| `PACK-06` | runtime leaf omits any of its six named non-raw outputs, discovery/build input alias drifts, or build overwrites a discovery member: pack seal0 |
| `BWRAP-01` | host bwrap identity/version drift, alternative executable, PATH lookup, missing `--unshare-all`/`--clearenv` |
| `BWRAP-02` | runtime placed at the forbidden synthetic convenience prefix, original-position loader/lib mismatch or broad host root bind |
| `WEB-SEED-01` | web typegen argv/env/order/consumer drift, output not exact cache-life/routes/validator triple, either env differs, shared inode or full19 starts before equality: row11/12 spawn0 |
| `SEED-RECEIPT-01` | Web/Gradle directory seed typed as FilePhysical, canonical receipt absent, member/relative-root/cross-invocation inode mapping drifts, or R role does not equal intent bind: spawn0 |
| `SCRATCH-01` | selected role missing/extra/reordered, triple not exactly `--bind/source/target`, actual path unresolved or target type differs |
| `SCRATCH-02` | file scratch emitted as directory form, wrong mountpoint type, nonempty/reused/aliased host scratch or cross-invocation path |
| `SCRATCH-03` | scratch outside exact row allowlist, projection logical pre/post digest differs, write outside HOME/TMP/selected worktree overlays |
| `ENV-01` | env field missing/extra/reordered, host credential/proxy/config variable survives or terminator/chdir/payload token order drifts |
| `RAW-01` | evidence host path/fd visible to payload, `/out` bind, supervisor pipe substitution, result written by payload |
| `RAW-02` | missing/extra evidence sibling, trace drop/overflow/detach, stdout/stderr cap bypass, raw bytes mutated after seal |
| `RAW-03` | Stage-B evidence path is outside the canonical resolved base or Stage-C path omits executor-attempt-key/invocation-id |
| `FULL19-01` | row count/order/ID/impact/argv/cwd/env/executable/input/timeout/cap or assertion drift |
| `FULL19-02` | Bash rows not exact nine tokens, command contains LF, command digest differs, host Bash/Node/npm/Gradle access |
| `FULL19-03` | consumer array/path/prefix role omission, duplicate/unexpected trace read or prefix role not expanded to exact literal set |
| `FULL19-04` | runtime scalar remains typed in sealed intent, fake hex inserted, environment logical rows differ or physical receipt reused |
| `FULL19-05` | either environment not actual 19/19 rc0, nonempty semantic PASS output absent, skip/NOT_RUN accepted |
| `FULL19-06` | output-oracle assertion ID/value drift, row17 nodeid or row18 collect-minus-DESELECT6 mismatch |
| `FULL19-07` | RuntimeActual substitution count not one, two env values/digests differ, binding points forward or use receipt output inequality |
| `FULL19-08` | row14 Gradle seed missing/not fresh-inode-equal, ambient cache read or offline network attempt |
| `REGRESSION-01` | regression runs before lanes/full19, accepts fixed count without collected digest, A/B order swaps or repeat omitted |
| `EXACT6-01` | argv/input/result schema drift, command count not 1/1/1/1/1/2, result references itself or does not bind the preceding five Physical files |
| `EXACT6-02` | ordered evidence-set count/order/path/member roles differ, PASS contains any failed command/assertion, FAIL lacks exact6 aggregate/incident, or undefined failure token is used |

### 19.5 schema, discovery, topology, arithmetic and claim ceiling

| ID | rejected fixture |
|---|---|
| `SCHEMA-01` | required common/kind field missing/null/unknown, wrong tagged-union member or forbidden field present |
| `SCHEMA-02` | SIGNED_ABSENT/ACTUAL/FUTURE_SEALED state used outside its declared field or future typed value replaced with guessed hash |
| `FSM-01` | A/B early incident rejected, early incident requires PREPARED, or terminal state reused |
| `FSM-02` | C expiry/incident cannot close effective-unconsumed receipt, or recovery consumes a different transaction |
| `FSM-03` | ordinary/recovery TARGET_SPEC reaches FENCE without the one bootstrap operation, recovery invents PREPARED, or POSTCHECK_FAILED reaches recovery success/application receipt |
| `HEAD-01` | C receipt binds future ISSUED head, omits signed predecessor, or effective expected head rule differs from §13.3 |
| `CAP-01` | paired capability scope is treated as cross-product, C operation outside C_ALLOW or recovery crosses original pair |
| `CAP-02` | transaction-manifest bootstrap operation/root pair missing, renamed, used after ACTUAL or applied without effective C/recovery consume+live lease |
| `INCIDENT-01` | incident path/schema/signature missing, wrong stage parent or incident publication not immutable |
| `DISCOVERY-01` | roles do not sum132, assigned/unassigned/duplicate/extra is not132/0/0/0 |
| `DISCOVERY-02` | orphan5/history6/direct5/D2 path or classification drift; historical current argv nonzero |
| `RUNNER-01` | selected74+excluded58 mismatch, direct/discovery overlap, runner total not76 or child spawns during validation |
| `C0-01` | requirements omitted, C asserted subset of S, A overlaps S/C/Q, M_after not627 or path hash differs |
| `C0-02` | stale current content hash carried forward, future content hash computed before after bytes seal, nonexistent source changed-files field added |
| `CAS-01` | requirements before identity drift, after hardlink/nlink4 reuse, runner CAS not fresh nlink1 or checkpoint not last |
| `TOPOLOGY-01` | managed bytes reference C0/T1/X1/V1/receipt, review references V1/self, or any self/reverse/future SCC |
| `REVIEW-01` | attempt review in repo, review before subject seal, subject/review mutation, nonzero verdict or plan review consumed by V1 |
| `RECEIPT-01` | application receipt put in exact26/M_after, omitted from C_ALLOW, target spec contains fabricated future FilePhysical |
| `RECEIPT-02` | application payload field/order/signature domain/content formula drifts, M_after hash/count differs, or receipt binds itself/final progress/future directory Physical |
| `OFFICIAL-01` | preliminary 126/257, historical 0/279, waived gate, deployment/release/device or successor credit promoted to current fact |
| `RETRY-01` | fixed `-001`, reused key/root/review/raw, skipped prior attempt, attempt17, renewal33 or deadline reset |

## 20. stop conditions

Execution stops without target mutation when any of these is true:

- either immutable R006 review or R006 plan fingerprint differs from §1;
- the new physical R007 plan reviews are absent or not independently `0/0/0`;
- current checkpoint/tail/source/CAS target identity differs from §2;
- authority final is absent without explicit bootstrap approval, partial, invalid,
  lock-busy, forked, gapped or not genesis-bound;
- bootstrap environment or exact45 source seed/review/binding is incomplete;
- attempt key/challenge, prior-attempt chain, max16/renew32/deadline is invalid;
- candidate builder/template/input/output, exact26, N26 or one-way hash DAG drifts;
- core/runtime command-manifest publication order, Stage-A bwrap staging/input
  alias, runtime closure, logical/physical equality, provenance, sandbox, scratch or raw
  evidence validation fails;
- Phase0 transform escapes the allowlist, Stage-B DAG/reviews or either
  environment full19/regression/repeat is non-PASS;
- discovery literal132, direct registries, history6, selected/excluded/runner
  arithmetic differs;
- current lifecycle head, receipt validity/consumption, paired capability,
  incident or universal OFD held proof differs;
- active C transaction/fence count exceeds1, lock continuity/order fails or an
  actor attempts stale/expired/revoked work;
- C0/T1/X1/V1 topology, exact26 order/mode/identity, U1, M_after627 or path hash
  differs;
- ordinary prefix/reconciliation, checkpoint durable reconciliation, hosted
  exact6 contract/evidence/result/failure transition or application-receipt
  content/signature/durability is not exact;
- any rollback, overwrite, delete, second CAS, orphan path reuse or nonnumeric
  journal filename would be needed;
- the official ceiling, delta statement, Stage-D R008 scope or later P-stage
  boundary would be exceeded.

## 21. R006 finding remediation matrix

### 21.1 formal review

| exact finding | R007 closure |
|---|---|
| `PRE-P-R006-BLOCKING-001` runtime logical bytes vs physical inode | §7 logical manifest excludes physical identity; separate build/external physical receipts |
| `BLOCKING-002` no executable builder/materializer contract | §4.1.1–4.1.2 first Python plus reviewed exact45 seed; §5 exact builders/argv/schemas |
| `BLOCKING-003` Stage-C exact6 argv/raw/result absent | §8.3–8.4 and §16 StageCExact6Contract, six literal argv rows/timeouts/caps, typed evidence sets, PASS/FAIL result and legal failed transition |
| `BLOCKING-004` full19 literal argv/consumer map absent | §9 exact19 arrays, pinned Bash bytes/hashes, exact consumer arrays and strict OutputOracle/result extraction |
| `BLOCKING-005` incident artifact/schema/publication absent | §13.1 exact incident path/tagged schema and PUBLISH_ONCE_AND_ADOPT |
| `BLOCKING-006` C expected head temporal cycle | §13.3 signed predecessor/effective-head rule; receipt cannot bind its future ISSUED record |
| `BLOCKING-007` application parent sealed too early | §15.4 private sibling publication, inner fsync/link/seal, final rename and parent fsync |
| `BLOCKING-008` checkpoint lost-progress recovery absent | §15.3 checkpoint-only durable reconciliation state and second-CAS prohibition |
| `BLOCKING-009` genesis/lock bootstrap crash bricks root | §4 whole temp root, bottom-up fsync, root NOREPLACE and complete-only adoption |
| `BLOCKING-010` unclean ordinary C has no adjudication | §14.4 custody close/new recovery epoch and §15 recovery reconciliation |
| `BLOCKING-011` authorization lifecycle not one OFD lock | §12.1 universal publication rule and §14 actor/continuity contract |
| `BLOCKING-012` transaction sequence zero/prior progress absent | §15.1 manifest sentinel, first prior-progress and exact progress tagged union |
| `MAJOR-001` runtime input discovery/acquisition provenance | §7.2–7.3 separate discover/build builders, trace, origin map and six raw files each |
| `MAJOR-002` bwrap writable model incompatible | §8 original-position pack, typed Stage-A/B/C projection/environment roles, Stage-A staging and exact canonical scratch bind arrays |
| `MAJOR-003` application target ACTUAL/precreation state | §13.2 path-only future target spec and §15.4 exact inward-only signed payload plus absent/NOREPLACE physical finalization |
| `MAJOR-004` lock parent identity/held proof missing | §14.1 anchored tuples, opaque LockGuard generation and independent conflict probe |
| `MAJOR-005` post-write guard incorrectly means write0 | §14.3 explicit pre-syscall zero vs post-syscall ambiguous recovery semantics |
| `MINOR-001` physical plan review location conflict | §17 exclusions and §18 single repository physical-review class |

### 21.2 skeptical review and auxiliary parent

| exact finding | R007 closure |
|---|---|
| `PRE-P-R006-BLOCKING-001` first authority bootstrap absent | §4.1 complete stable whole-root genesis/lock/bootstrap environment |
| `BLOCKING-002` single convenience runtime bind cannot execute | §7 closure and §8 rootfs `/usr`/loader/library original-position binds |
| `BLOCKING-003` RO worktree and writable evidence conflict | §8.2 typed scratch overlays; §8.3 payload-invisible host pipes/evidence |
| `BLOCKING-004` A/B receipt transaction/scope not exact | §12 tagged A/B transaction SignedAbsent and §13 paired scopes |
| `BLOCKING-005` C expected-head/ISSUED cycle | §13.3 acyclic predecessor and pending-head formulas |
| `BLOCKING-006` incident/effective-unconsumed close/recovery absent | §13 incident/FSM and §14.4–§15 original-receipt recovery |
| `BLOCKING-007` 0555 application parent unwritable | §15.4 private writable sibling then seal and atomic final rename |
| `BLOCKING-008` checkpoint CAS crash not reconcilable | §15.3 `CHECKPOINT_RECONCILED_DURABLE` without second CAS/PREFIX |
| `MAJOR-001` pre-ISSUED orphan ownership absent | §4.2 exact-byte adopt or publication-abort incident under current head/lock |
| `MAJOR-002` exact26 source→target map nonnormative | §6 all26 source/target/op/class/producer rows and structural N26 |
| `MINOR-001` plan review location contradiction | §18 repository-only nonauthoritative plan review class |
| `AUX_PARENT_001` materialization map | §6 ordinals15..25 parent transition and §15.2 exact U1 create/fsync/adopt contract |

## 22. preliminary findings closed in this revision

These are design-review observations found while constructing R007. They are
closed by the normative text, not silently waived:

| ID | preliminary issue | closure |
|---|---|---|
| `PRELIM-001` first attempt parent does not yet have a receipt | one-use challenge-bound `CREATE_ISSUANCE_PARENT` in §4.2 |
| `PRELIM-002` raw bytes and signed publication were conflated | §4.2 immutable bytes plus conditional signature validation; §19 `PUB-03` |
| `PRELIM-003` first builder had no Python | genesis-bound bootstrap environment in §4.1.1 |
| `PRELIM-004` schemas/builders themselves had no origin | reviewed exact45 source-seed one-way fresh-inode bootstrap in §4.1.2 |
| `PRELIM-005` composite target row2 producer was ambiguous | explicit three-receipt `CONTROL_B_COMPOSITE` expansion in §6 |
| `PRELIM-006` CandidateTargetMap/receipt/manifest cycle | structural map points only inward; aggregate/subject/reviews point one-way in §5.2 |
| `PRELIM-007` runtime discovery could merely observe, not build | separate discover/build builders and raw six for both in §7 |
| `PRELIM-008` bwrap intent left scratch grammar unresolved | exact role order, literal bind triples, type check and per-row arrays in §8.2 |
| `PRELIM-009` Stage-B raw root was abbreviated | canonical §3 resolved-base-relative evidence path in §8.2 |
| `PRELIM-010` exact6 result could bind itself | §16 result binds preceding five; POSTCHECK binds result plus those five |
| `PRELIM-011` runtime event scalar did not fit string argv | §9 Token union before seal and strings-only sealed intent |
| `PRELIM-012` full19 consumers were prose-only | exact ordered `F/P/E/R` arrays for rows01..19 in §9.4 |
| `PRELIM-013` Stage D could be mistaken for current execution | exact `POST_SEQ40_R008_SUCCESSOR_DESIGN_REVIEW_ONLY` in §3 |
| `PRELIM-014` A/B/global abort publication lock differed | one genesis-bound OFD for every global publication in §12/§14 |
| `PRELIM-015` two orphan terminal names existed | single `PUBLICATION_ABORTED` kind throughout |
| `PRELIM-016` Stage-C evidence path lacked executor identity | canonical transaction/executor/invocation hierarchy in §8.4 |
| `PRELIM-017` application operation and progress kind collided | operation `APPLICATION_RECEIPT_FINALIZE`; terminal durable kind `APPLICATION_RECEIPT_FINALIZED` |
| `PRELIM-018` partial held proof could fake ownership | primary reassert + opaque generation + independent EAGAIN/EACCES probe in §14.1 |
| `PRELIM-019` future N26 could be faked with current hex | typed FUTURE_SEALED binding and physical three-way recomputation in §6 |
| `PRELIM-020` global/transaction sequence-zero predecessors were underspecified | §12 GlobalGenesisSentinel and §15 ManifestSentinel/ProgressRef domains |
| `PRELIM-021` transaction did not bind the lock contract/mount identity | §4 mnt_id Physical and §15 exact root/journal/genesis/lock OFD contract |
| `PRELIM-022` crash need could synthesize recovery authority | §13 close-only scope and §14 no-authority marker release/reacquire branch |
| `PRELIM-023` private receipt nonce/path was not durable before mutation | §15 four phase progress kinds before terminal finalization |
| `PRELIM-024` per-unit input/output semantics were only directory names | §5 exact ordered role/path arrays, semantic members and slot substitution |
| `PRELIM-025` condensed N26 before state was not canonical JCS | §6 typed actual/absent dictionaries with mnt_id and seq39 tail |
| `PRELIM-026` candidate materializer and Stage-C applier were conflated | §6 prefix→builder dictionary plus constant fenced applier |
| `PRELIM-027` candidate map/subject nodes had no physical paths | §5.2 canonical one-way map/equality/manifest/review publication DAG |
| `PRELIM-028` runtime/Stage-B builders lacked literal execution contracts | §7 and §10 exact argv/cwd/env/input/read/output contracts |
| `PRELIM-029` full19 impact/env/executables/assertions were incomplete | §9.5 exact19 metadata and typed output oracle |
| `PRELIM-030` runtime scalar producer/use equality was unbound | §9.6 acyclic RuntimeActual binding/use receipt and two-env equality |
| `PRELIM-031` row14 offline Gradle used an empty home | §7 seeded Gradle closure and §8 fresh-inode ScratchSeedReceipt |
| `PRELIM-032` Stage-B overlay omitted lane B/C/D bytes | §10 exact26 actual-candidate read set and target-shaped resolved overlay |
| `PRELIM-033` C receipt and TransactionManifest formed a Physical SCC | §12/§13 path-only TransactionTargetSpec, then one-way manifest→receipt Physical |
| `PRELIM-034` RuntimeInputManifest and command manifest formed a hash cycle | §5.2/§7 split core/runtime command manifests and inward-only Stage-A runtime role contracts |
| `PRELIM-035` Stage-B regression manifest pointed to future/mismatched receipts | §10 two-phase input publication with canonical candidate receipt paths |
| `PRELIM-036` Stage-B equality/review subject was prose-only | §10 strict equality and ResolvedReviewSubjectManifest schemas/DAG |
| `PRELIM-037` WEB_NEXT was empty although next-env imports generated types | §8/§10 exact3 deterministic typegen seed, cross-env equality and row11/12 clone receipts |
| `PRELIM-038` directory scratch seed was typed FilePhysical | §8 ScratchSeedReceipt uses root/logical/physical member mappings and canonical paths |
| `PRELIM-039` Stage-A bwrap inherited a Stage-B-only projection grammar | §8 typed ProjectionRole/EnvironmentRole, `/work/subject`, input aliases and supervisor-only evidence |
| `PRELIM-040` exact6 failure named no legal record/reason | §13/§15/§16 POSTCOMMIT_VALIDATION_FAILED → POSTCHECK_FAILED → CLOSED_INCIDENT |
| `PRELIM-041` application receipt content/signature was unspecified | §15.4 exact strict payload, JCS signature domain and no-self/future rule |
| `PRELIM-042` universal lock filename implied Stage C only | neutral `authority-journal-global.lock` throughout §4/§14 |
| `PRELIM-043` recovery could not bootstrap a never-created transaction | exact bootstrap capability pair and ordinary/recovery FSM branches in §4/§13/§15 |

## 23. Quick2, freeze fingerprints and self-check

Quick2 means only Stage-C exact6 checks 003 and 004:

```text
AFTER continuation Quick = PASS
AFTER Goal Quick = PASS
```

It does not mean a full19 rerun, regression substitution or official success.

Immutable input freeze:

```text
R006 sha256=4a9f7f21d505bf6cf53d1ea8a16e21e7ebca5c154541d49928d03383b7d23de9
R006 bytes/lines=91165/1972
formal sha256=08dad026cd498bff36b10db51809342ed62fc7c78aa9c8b10ef5f83d38f5a520
formal bytes/lines/findings=32052/647/12B-5M-1m
skeptical sha256=d8e4b0f710e15c6ebab64bbf17ee4bbb60e7a88d65b5c30b7e6832e95229b3af
skeptical bytes/lines/findings=28928/639/8B-2M-1m
```

Deferred-freeze structural self-check actual (`2026-07-31 08:50 KST`):

```text
R007 regular=true; symlink=false; mode=0664; nlink=1; NUL=0; terminal-LF=true
Markdown fence-marker count=354 (even)
R006 and both review SHA/bytes/lines equal the values above=true
exact target rows=26; ordinals=1..26; unique targets=26=true
CAS_REPLACE=2; NOREPLACE=23; CHECKPOINT_CAS_LAST=1=true
revision-owned targets use r007 for ordinals 3,4,8,9
M_after_count=627
M_after_path_set_sha256=
  2c98e5795a5bf26e90a2487f41899d2a88114a3e4e02f830924f33066b9cdbb6
registry literal rows=132; source-seed entries=45
full19 rows=19; Bash rows=10; exact6 invocations=6
core/runtime command manifests=9 (core7+runtime2);
Stage-A builder invocations=30+12
Stage-C exact6 contract rows=6; command counts=1/1/1/1/1/2
Web Next type seed members=3 in each environment; logical equality=true
physical R007 formal/skeptical review files are source-snapshot excluded
candidate/resolved/authority/application-receipt paths are not M_after members
stale lock token=0; obsolete failure token=0; "same exact fields" alias=0
```

The physical deferred snapshot hash/bytes/lines are computed externally after
the final edit. It is review input only, not a findings-zero candidate. No
Stage-A request may be formed from this revision.

Final current-state assertions:

```text
FINAL_AUTHORITY=false
STAGE_A_EXECUTED=false
STAGE_B_EXECUTED=false
STAGE_C_EXECUTED=false
APPLICATION_RECEIPT_EXISTS=false
CURRENT_CHECKPOINT_SEQUENCE=39
TARGET_MUTATION_COUNT=0
OFFICIAL_PRE_P_VALIDATION_SUCCESS=false
```

R007 is therefore a preserved, standalone but NOT_ACCEPTED and NOT_EXECUTABLE
draft. It neither closes all known findings nor grants authority or claims
execution, checkpoint seq40, official validation, deployment, release or device
success.

## 24. DEFERRED_FREEZE_FOR_REVIEW ledger

The three auditor-domain counts last reported before deferral were authority
`5B/2M`, materialization `6B/2M`, and frozen-oracle `8B/3M`. They overlap and
must not be added. The deduplicated unresolved ledger is `16 BLOCKING / 4 MAJOR`;
patched-but-unreviewed work is separately listed as re-audit required.

| ID | severity | single unresolved closure |
|---|---|---|
| `DFR-B01` | BLOCKING | make preissuance/review grants constructible: define CanonicalRootSpec, move preissuance signature outside payload, and specify consume/close artifacts, predecessors, paths and signature domains |
| `DFR-B02` | BLOCKING | define exact revocation FSM edges and the common current-head/time/deadline/one-use/unrevoked consume/lease/renew guard |
| `DFR-B03` | BLOCKING | materialize A/B/C/recovery capability pairs as signed literal CanonicalRootSpec arrays; no alias or operation×root cross-product may remain |
| `DFR-B04` | BLOCKING | add the strict Stage-C/recovery receipt tagged extension that binds N26/T1/X1/V1 and the byte-equal ApplicationReceiptTargetSpec |
| `DFR-B05` | BLOCKING | retain D–G solely as non-operative labels and define the separate future approval boundary before any successor use |
| `DFR-B06` | BLOCKING | define strict T1 and X1 payloads/types/paths/publishers/domain-separated hashes plus StageCReviewBinding payload/signature so V1 bytes are constructible |
| `DFR-B07` | BLOCKING | define exact10 StageBValidationResult PASS/FAIL/NOT_RUN objects, evidence extractors, aggregates and repeat-equality receipt |
| `DFR-B08` | BLOCKING | define signed StageBSourceInputManifest for the two resolved inputs and regression-final input, including aliases, digest and publication timing |
| `DFR-B09` | BLOCKING | define and publish StageCLiveRootManifest after checkpoint durability and bind it into all exact6 roles/intents/results |
| `DFR-B10` | BLOCKING | define Exact6InputManifest/bundle/type expansion and literal per-assertion extractors, expected/actual/PASS rules and independent recomputation |
| `DFR-B11` | BLOCKING | exclude or normalize every generated scratch target in source/projection construction so required empty mountpoints are deterministic |
| `DFR-B12` | BLOCKING | make BEFORE/AFTER projection schemas exactly type-correct with canonical DirManifestPhysical fields and one noncontradictory publication order |
| `DFR-B13` | BLOCKING | close RuntimeActual: exact resolved-subject payload/hash, local/hosted/C/recovery consumers, Stage-C use receipts and canonical normalized-output paths/producers |
| `DFR-B14` | BLOCKING | publish the exact per-assertion full19 extraction map and structured pytest summary/nodeid evidence; a reviewed rc0 claim alone is insufficient |
| `DFR-B15` | BLOCKING | add row18's complete transitive source/test/control/.git consumers and GIT executable so trace equality is satisfiable |
| `DFR-B16` | BLOCKING | remove the lane-d/after-control FutureSealed ownership conflict and fix each producer's exact resolution boundary |
| `DFR-M01` | MAJOR | close row15 executable roles for dirname/mktemp/chmod/rm/find/sort or prove a reviewed runner replacement removes them |
| `DFR-M02` | MAJOR | make every future Stage-C invocation-data role typed late-bound while keeping only executable/environment/package closure actual in Stage A |
| `DFR-M03` | MAJOR | split recovery authorization: exact6 requires adoptable complete prefix plus never-dispatched suffix; application receipt requires actual POSTCHECK_PASSED |
| `DFR-M04` | MAJOR | prove complete embedded graph-node/edge membership and deterministic graph_digest inputs for both review-subject manifests |

Re-audit is mandatory for the unreviewed patches to bootstrap grant/container,
source provenance and exact45 mapping, source snapshot and separate plan-input
channel, two-phase P binding, runtime discovery/build manifests and receipt
ownership, embedded graph arrays, and BEFORE/AFTER projection split. None is
counted as closed.

Last audit provenance is deliberately explicit:

```text
audit_state=INTERIM_MUTABLE_SNAPSHOTS_ONLY
parent_observation=2026-07-31T08:41+09:00
parent_candidate_sha256_prefix=ceff53
parent_candidate_bytes=250403
parent_candidate_lines=5206
frozen_oracle_snapshot_prefixes=c7777a9,722d702,b1ddae
materialization_reaudit=live mutable observations through 2026-07-31T08:50+09:00
final_findings_zero_audit=NOT_RUN
freeze_ready_signal=NOT_ISSUED
```

Deferred terminal facts:

```text
DEFERRED_FREEZE_FOR_REVIEW=true
CURRENT_AUTHORITY=ABSENT_DENY_ALL
ACTIVE_WRITE_ALLOWED=false
AUTHORITY_JOURNAL_WRITE_ALLOWED=false
CURRENT_CHECKPOINT_SEQUENCE=39
TARGET_MUTATION_COUNT=0
APPLICATION_RECEIPT_EXISTS=false
OFFICIAL_PRE_P_VALIDATION_SUCCESS=false
```
