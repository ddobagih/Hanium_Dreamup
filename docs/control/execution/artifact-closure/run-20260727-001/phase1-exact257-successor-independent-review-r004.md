# Phase 1 exact257 Successor R004 Independent Review

## Review control

- Review date: `2026-07-28`
- Review target: `packets/phase1-exact257-successor-r004/evidence.json`
- Companion receipt: `phase1-exact257-successor-check-receipt-r004.json`
- Canonical ledger: `phase1-exact257-successor-ledger.json`
- Review method: independent structural, physical-binding, projection-integrity, and invariant recomputation
- Historical boundary: R001 through R003 inputs were treated as immutable predecessors
- Verdict: `PASS`

## Findings

| Severity | Count |
| --- | ---: |
| BLOCKING | 0 |
| MAJOR | 0 |
| MINOR | 0 |

## Independent verification

| Check | Result | Independently observed value |
| --- | --- | --- |
| Canonical ledger physical binding | PASS | `2,498,803` bytes; SHA-256 `db60888e2fef5c0a1ac76865d92dac6eafac348076272fbc99e8e6e187c97052` |
| exact257 identity | PASS | `257` records and `257` unique `artifact_type_code` values |
| Canonical status counts | PASS | `OK 124 / INTERNAL_GAP 48 / EXTERNAL 49 / N_A_CANDIDATE 36` |
| Queue route counts | PASS | `OK_BASELINE 124 / INTERNAL_READY 37 / INTERNAL_RUN_REQUIRED 16 / OWNER_APPROVAL_PENDING 14 / ATTESTATION_REVIEW_PENDING 4 / EVIDENCE_FACT_PENDING 6 / SCOPE_DECISION_PENDING 45 / REAL_EVENT_PENDING 11` |
| Unsupported promotion count | PASS | Closure, route change, release, formal pass, approval, verified fact, scope decision, and real-event promotion counts are all `0` |
| Canonical ledger subject role | PASS | Current expected and observed role both equal `PHASE1_EXACT257_SUCCESSOR_LEDGER_OUTPUT` |
| R001 three-entry mapping | PASS | `LEDGER`, `R001_EVIDENCE`, and `R001_RECEIPT`; expected and observed counts both `3`; physical path, length, and SHA-256 bindings agree |
| Preserved source set | PASS | `37/37` paths have matching physical byte length and SHA-256 |
| Preserved source tuple fingerprint | PASS | `9,281` canonical bytes; SHA-256 `1f71cfe933c03dd9d4ee82c1a0bdf758ee5c0da4f960a5faee49520574bab121` |
| R004 evidence non-self integrity | PASS | `81,809` projection bytes; SHA-256 `40baff06a04114c25312b189c12ec5a15ee28e2181c2346b109dd67b7ee1d018` |
| R004 receipt non-self integrity | PASS | `8,814` projection bytes; SHA-256 `31a3504ebb450798303ef31f009b91c2fc456ede161e72106d801b0166bc84e1` |
| R004 evidence raw binding | PASS | `110,612` bytes; SHA-256 `c1b673fbeb29615ea3ee8176972daab2c3025e14315691c6750d1bf7ff3bb3a1` |
| R004 receipt raw file | PASS | `11,706` bytes; SHA-256 `feb497f54b0a52c16e259fa5c4d3ee23c8094df048bfc686e9e42a25e8ef7aa6` |
| Receipt checks | PASS | `11 PASS / 0 FAIL`; all declared expected/observed comparisons agree |

## Finding closure verification

The R004 closure register contains exactly four unique entries, resolves their evidence bindings, and marks every entry `CLOSED`. The companion receipt contains an individual passing check for each finding, an exact-set closure check, and matching summary statuses.

| Finding | R004 status |
| --- | --- |
| `R001-MINOR-001` | `CLOSED` |
| `R001-MINOR-002` | `CLOSED` |
| `R002-MINOR-ROLE-001` | `CLOSED` |
| `R003-MINOR-CLOSURE-REGISTER-001` | `CLOSED` |

## Verification procedure summary

- Parsed the R004 evidence, R004 receipt, and canonical successor ledger as JSON.
- Recomputed record uniqueness, canonical counts, queue counts, and all zero-promotion predicates from ledger records.
- Rehashed all 37 bound source files and compared physical length and SHA-256 to their declared bindings.
- Reconstructed the declared sorted four-field source tuple projection and recomputed its SHA-256.
- Applied each declared JSON-pointer null projection, recursively sorted compact JSON serialization, UTF-8 encoding, and final LF before recomputing evidence and receipt integrity values.
- Resolved the R004 closure-register evidence selectors and compared the four-finding exact set across evidence, receipt checks, and receipt summary.

## Claim boundary

This review validates the R004 successor package, its physical bindings, and its preservation of the exact257 baseline. It does not grant artifact closure, owner approval, verified external facts, scope decisions, real-event credit, formal279 PASS credit, release eligibility, or deployment approval. The canonical successor ledger correctly remains at zero new promotions.
