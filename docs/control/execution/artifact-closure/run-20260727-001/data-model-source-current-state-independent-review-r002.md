# Data/model source current-state independent review R002

## Verdict

- Verdict: `PASS`
- Severity: `NONE`
- Finding count: `0`
- Review date: `2026-07-28`
- Review mode: independent current-tree recomputation; no source mutation, Git operation, daylog update, or memory write

## Reviewed subjects

| Role | Path | Bytes | SHA-256 | Nonself SHA-256 |
|---|---|---:|---|---|
| Subject | `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715/docs/control/execution/artifact-closure/run-20260727-001/data-model-source-current-state-r002.json` | 6,982 | `2bbaefb26e73414cb61e69f4871775e879cc2b6c4a405776033cfbd3c56d4c86` | `512424ad50e23255d3bbddb61568686dfe47394822c07ed00b63707087c559c5` |
| Check receipt | `/home/ddobagi/Code/hanium-dreamup-walksafe-rc2-20260715/docs/control/execution/artifact-closure/run-20260727-001/data-model-source-current-state-check-receipt-r002.json` | 3,091 | `535826176c47a5914a4863b2de1abbfeac19e9ab7365d92dbceaed8dbaec54dd` | `158a21382a0107fade4d1c76be2ce88c18a7204043ad83924c758b6a3300fbd7` |

Both JSON files parse successfully, equal recursively key-sorted compact UTF-8 JSON with one terminal LF, and have valid embedded nonself SHA-256 values. The receipt's subject path, byte length, full SHA-256, nonself SHA-256, and document ID match the subject.

## Physical binding recomputation

All seven files were independently streamed and rehashed.

| Binding | Bytes | SHA-256 | Result |
|---|---:|---|---|
| Dataset manifest primary | 77,865,056 | `5ea9796589e02fcb64b82af564400df081731d7b2136e9a654422df56fdb94f6` | `MATCH` |
| Dataset manifest archived metadata copy | 77,865,056 | `5ea9796589e02fcb64b82af564400df081731d7b2136e9a654422df56fdb94f6` | `MATCH` |
| Training results primary | 37,060 | `1dc4b32335db04910ed51746f4bb3e7955d3f545c25dbd5dd4c44253b9d62dcc` | `MATCH` |
| Training results evaluation copy | 37,060 | `1dc4b32335db04910ed51746f4bb3e7955d3f545c25dbd5dd4c44253b9d62dcc` | `MATCH` |
| Candidate PT primary | 5,411,845 | `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669` | `MATCH` |
| Candidate PT evaluation copy | 5,411,845 | `a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669` | `MATCH` |
| Android TFLite primary | 9,984,493 | `92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19` | `MATCH` |

The manifest, training-results, and PT pairs are byte-for-byte equal and have equal byte lengths and SHA-256 values.

The four hashes match existing RC2 declarations. The training-results declaration is in `model/registry/walksafe-model-registry.json`; the manifest, PT, and TFLite values are also supported by the predecessor current-state and applicable dataset/model registers.

## Recomputed receipt checks

| Check | Independent result |
|---|---|
| `CHECK-ADD-ONLY-SUCCESSOR` | `PASS` |
| `CHECK-SUBJECT-JSON-PARSE` | `PASS` |
| `CHECK-SUBJECT-CANONICAL-SERIALIZATION` | `PASS` |
| `CHECK-SUBJECT-NONSELF-INTEGRITY` | `PASS` |
| `CHECK-PHYSICAL-BINDINGS` | `PASS` |
| `CHECK-RC2-DECLARED-HASH-MATCHES` | `PASS` |
| `CHECK-COPY-EQUALITY` | `PASS` |
| `CHECK-UNCERTAINTY-PRESERVED` | `PASS` |
| `CHECK-USER-ATTESTATION-BOUNDARY` | `PASS` |
| `CHECK-ZERO-CREDIT-BOUNDARY` | `PASS` |

The receipt summary of 10 passes and zero failures agrees with the independent recomputation. Add-only status is confirmed from the extant distinct successor/predecessor paths and declared relationship; this current-tree review does not claim an independent historical Git proof.

## Evidence and governance boundary

- All seven bound files are under the sibling root `/home/ddobagi/Code/hanium-dreamup`, outside the RC2 worktree.
- Every binding remains marked `external_to_rc2: true` and `immutable_copy: false`; the hashes prove point-in-time file identity only, not controlled retention.
- The statement that Kim Minho directly trained the candidate remains a self-asserted `USER_PROVIDED_ATTESTATION`, limited to trainer attribution and independently `NOT_VERIFIED`.
- Source-list completeness, direct-capture inclusion, and user-provided inclusion remain `UNKNOWN`.
- Rights verification and privacy review remain `NOT_VERIFIED`.
- Formal training reproduction, split/leakage validation, approval, and release credits remain `0`.
- Approval remains `NOT_APPROVED`, and release remains `NOT_ELIGIBLE`.
- Dataset rows and model payload semantics were not inspected; this review does not promote governance, evaluation, approval, or release status.
