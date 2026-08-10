# W8 Central Sealing Independent Review

- Review date: `2026-07-27` (`Asia/Seoul`)
- Scope: W8 central artifacts, W7 predecessor binding, final-review authority, current-state pair, and successor/history boundaries
- Blocking findings: `0`
- Major findings: `0`
- Minor findings: `0`
- Verdict: `GO_W8_SEALED`

## 1. Central Artifact Integrity

The three W8 central artifacts were hashed from their current bytes. Each self-fingerprint was also independently replayed from the full JSON object after setting only its own `integrity.content_fingerprint.value` to `null`, using sorted compact JSON, UTF-8, and one trailing LF.

| Artifact | Bytes | Raw SHA-256 | Replayed self-fingerprint | Result |
|---|---:|---|---|---|
| `artifact-status-delta.json` | `35307` | `0051091685e07e8b3adf29b7c8b56008ef89ea5c272ef733daddb38e871203e3` | `2d53f9fefb8a6fb2159eefeedfde6a684c04e8839ecd2c5cad2e4ba39f12bc7a` | `PASS` |
| `validation-summary.json` | `29611` | `bbf9a1ba4b2c973a7e397cd02eb4269cb0d967c5a1b4cedb6e070949a93300a9` | `43477198a850ac92dbbddd200388bc1cbb941e137ab102e54139830e6d9dadf6` | `PASS` |
| `implementation-receipt.json` | `29777` | `d05e7ebcc2be943f9d9d35d3eb9f163327ba387e694fc6366e439c4dc7aabd05` | `2c601678875b617d1f8160b49e4f544d084619001753753158c0a86d83a59ae3` | `PASS` |

The W7 predecessor was independently rebound as `23293` bytes with raw SHA-256 `90bd754abe86e5bdd833a796c42af46e60bee4a50cc179a0a11793cf3bd94ad4` and replayed self-fingerprint `b33b32fa319fbc40c7e31a7ba57240fd224683a7d5dc9812fc4c8920aeebdccd`. Both values match the W8 declarations.

## 2. Input, Subject, Common, and DAG Contracts

| Contract | Independent result |
|---|---|
| Exact input set | `16/16` current raw hashes and byte lengths match |
| Canonical exact-16 input preimage | `2875` bytes, SHA-256 `aa940edd66b899d9356937e6a4db3859e79e79643f6435965996874567834a89` |
| Common-fingerprint preimage | `4452` bytes, SHA-256 `d78adebb5f1175db27ac9cc35b0e91453fa3f60e4db874bb7f4de0c6848fc02e` |
| Final-review exact-14 subject preimage | `14` TSV records, exactly `2004` UTF-8 bytes, SHA-256 `ef8e490bf88dc157a3f9f8b159a7777ee74407c88cb87a69c9a5afbe9b14f31d` |
| Exact-14 physical subject files | `14/14` current raw hashes and byte lengths match |
| Dependency graph | `19` nodes, `19` edges, `ACYCLIC` |

The exact-16 input digest and common fingerprint replay identically from all three central artifacts. The final-review fenced subject block is the explicit preimage: no inferred ordering, omitted record, normalized byte sequence, or implicit file membership is needed to reproduce it.

## 3. Review Authority and Historical Isolation

| Review artifact | Raw SHA-256 | Recorded disposition | Authority treatment |
|---|---|---|---|
| `independent-final-review.md` | `f66f04b6fe55c34fa0eb9b88397568e368529e5488c5b9dd9da718da92227ecd` | `GO_STATUS_DELTA_ALLOWED`, `0/0/0` | Sole transition authority |
| `independent-review.md` | `556ba08d0b88d96760309e5226a9f09bb5647705b2787761cc3054ca25937010` | `NO_GO`, `0/2/0` | `HISTORICAL_ONLY` |
| `independent-rereview.md` | `a2acd1e0555cfce4b5fad6a53cb557cb919ee359b98e3da8f029d0caeebf71b3` | Prior `GO`, `0/0/0` | `HISTORICAL_GO_WITH_NON_REPRODUCIBLE_SUBJECT_PREIMAGE_NOT_TRANSITION_AUTHORITY` |

The prior rereview subject digest `216ede1c6c2c810608fa6292a8a372f249152299e913c114aa106aa7b52b91ae` is retained only as history. It cannot compete with or broaden the final review's reproducible exact-14 authority. The earlier `NO_GO` is likewise preserved without being silently rewritten.

## 4. Direct Central Sealing Checklist

The following `29` central sealing assertions were evaluated directly:

| ID | Assertion | Result |
|---|---|---|
| `CS-01` | Delta raw SHA-256 and byte length | `PASS` |
| `CS-02` | Validation raw SHA-256 and byte length | `PASS` |
| `CS-03` | Receipt raw SHA-256 and byte length | `PASS` |
| `CS-04` | Delta self-fingerprint replay | `PASS` |
| `CS-05` | Validation self-fingerprint replay | `PASS` |
| `CS-06` | Receipt self-fingerprint replay | `PASS` |
| `CS-07` | Exact-16 canonical input preimage replay | `PASS` |
| `CS-08` | Exact-16 physical input hashes and lengths | `PASS` |
| `CS-09` | Delta common-fingerprint replay | `PASS` |
| `CS-10` | Validation common-fingerprint replay | `PASS` |
| `CS-11` | Receipt common-fingerprint replay | `PASS` |
| `CS-12` | Final-review exact-14 subject shape and order | `PASS` |
| `CS-13` | Final-review explicit preimage length `2004` | `PASS` |
| `CS-14` | Final-review subject digest `ef8e490b...f31d` | `PASS` |
| `CS-15` | Exact-14 physical subject hashes and lengths | `PASS` |
| `CS-16` | Final-review authority raw binding | `PASS` |
| `CS-17` | Sole authority and final-review `0/0/0` | `PASS` |
| `CS-18` | Initial `NO_GO` remains historical-only | `PASS` |
| `CS-19` | Non-reproducible prior `GO` remains non-authoritative | `PASS` |
| `CS-20` | W7 predecessor raw and self binding | `PASS` |
| `CS-21` | Dependency graph `19/19/ACYCLIC` | `PASS` |
| `CS-22` | Exact-5 delivery scope uniqueness | `PASS` |
| `CS-23` | Only `DLV-REL-17` changes to `OK` | `PASS` |
| `CS-24` | Before-count vector and total | `PASS` |
| `CS-25` | After-count vector, delta, and invariant total | `PASS` |
| `CS-26` | Current JSON/Markdown pair fingerprints and payload parity | `PASS` |
| `CS-27` | W9 shared-successor attribution and stale-zero check | `PASS` |
| `CS-28` | REL-17 current slice and unknown historical boundary | `PASS` |
| `CS-29` | Conservative authority/release boundary and stored validation matrix | `PASS` |

Result: `29/29 PASS`.

The persisted `validation-summary.json` contains its own artifact-level matrix of exactly `28/28 PASS`. That matrix is not being relabeled as 29 checks. A temporary generator harness asserted `len == 28` while its independent central checklist actually enumerated 29 checks; that off-by-one harness assertion is harness-only and does not constitute an artifact finding. The central result above comes from direct evaluation of all 29 listed conditions.

## 5. Exact-5 Status Delta

The exact delivery scope is:

`DLV-REL-15`, `DLV-REL-16`, `DLV-REL-17`, `DLV-REL-19`, `DLV-REL-22`

Only `DLV-REL-17` changes from `INTERNAL_GAP` to `OK`. The other four entries remain `INTERNAL_GAP`, and the out-of-scope change count is `0`.

| State | Before | After | Delta |
|---|---:|---:|---:|
| `OK` | `116` | `117` | `+1` |
| `INTERNAL_GAP` | `63` | `62` | `-1` |
| `EXTERNAL` | `42` | `42` | `0` |
| `N_A_CANDIDATE` | `36` | `36` | `0` |
| `TOTAL` | `257` | `257` | `0` |

The `257`-item total is conserved.

## 6. Current-State Pair and Successor Boundary

The current-state pair is internally and mutually reproducible:

| Artifact | Bytes | Raw SHA-256 |
|---|---:|---|
| `release-readiness-current-state.json` | `56684` | `49a5885953c32031eb977181c3d76b359b88eeb2b1b13d66ab320b52974c2879` |
| `release-readiness-current-state.md` | `31392` | `8a1575492fc58f0bb28e8d6d05cc1a0d0b5429354741e5c9404e4950637ff50e` |

- JSON self/content fingerprint: `c2b9f33958da5a8c2c83dafd7473c9ad0cb8982f243eee79f3ea247f3efacb45`
- Source fingerprint: `fd913a725400b57f274865da0f0c54e60a1025ea8a7634a03aa87764789e5897`
- Input fingerprint: `d6f21a1cc13b62dfa79e8b650889759b5a3bbf5a7fc7699da79c3898be31615c`
- Projection fingerprint: `0d457c61834098d327220d63b34df5df13248f21ca63153ec3ff8a1db85f0991`
- Markdown payload parity with the JSON projection: `PASS`

The W8 direct current subject is limited to the declared `USER_GUIDE` evidence. The builder, test, and manifest sources are correctly attributed as `SUCCESSOR_CURRENT_SOURCE_W9_SHARED`; all `3/3` current bindings match and their stale count is `0`.

The current `DLV-REL-17` section is bound to byte interval `[6011,11332)`, length `5321`, SHA-256 `f6fe0a8e7cc6788cec9d2a0e56b7b3ccdc06c296b27ab68487a93807d7c2e61a`. For the historical builder, test, manifest, and REL-17 status, the retained state is explicitly `HISTORICAL_PRE_W9_DIGEST_UNKNOWN_NOT_RETAINED`; historical byte preservation is therefore not falsely claimed. The exclusive-change count remains `0`.

Formal and device-dependent execution remains `NOT_RUN`, Gate 5 remains `NOT_RUN` and unwaived, and release eligibility remains `NOT_ELIGIBLE`. The sealed W8 status delta does not imply broader test completion or release approval.

## 7. Findings and Verdict

No blocking, major, or minor artifact finding was identified.

- Findings: `0 blocking / 0 major / 0 minor`
- Central checklist: `29/29 PASS`
- Persisted validation matrix: `28/28 PASS`
- Final verdict: `GO_W8_SEALED`
