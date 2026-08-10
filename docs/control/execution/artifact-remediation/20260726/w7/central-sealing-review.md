# W7 Central Sealing Independent Review

- Review date: `2026-07-27`
- Review scope: W7 central three files, W6 predecessor receipt, current W6/W7 subject pairs, historical review, and final rereview authority
- Findings: `blocking 0 / major 0 / minor 0`
- Verdict: `GO_SEALED`

## 1. Raw and self-integrity reproduction

| File | Bytes | Raw SHA-256 | Reproduced content/self SHA-256 |
|---|---:|---|---|
| `artifact-status-delta.json` | 32623 | `3ac944a44cbcd621a0c9f7b5ed265d0acffd3240f32f92ca022ad8e519d725b0` | `354212699795ef18062870d56408acbb38e8b5f7b50558ad3e512daf155e91fe` |
| `validation-summary.json` | 21731 | `58ad8417d3ba6c657d08fcd2371b01de2b216af873059cd4c62ac008cd3c7771` | `c7eb134f2ba73f9a953a421e2c77a1ce4a3b77e1243e871589878f8a12e93a43` |
| `implementation-receipt.json` | 23293 | `90bd754abe86e5bdd833a796c42af46e60bee4a50cc179a0a11793cf3bd94ad4` | `b33b32fa319fbc40c7e31a7ba57240fd224683a7d5dc9812fc4c8920aeebdccd` |
| W6 predecessor `implementation-receipt.json` | 22403 | `5f04aa4c55ca4d52c9fd9e995ae8336413c7c957b983cef87d26da08c81e3db1` | `f2f0dfdcfc357a21e7dc7506ffb1ce1b4ca4521619e76e185814281c343b3e3b` |

Self fingerprint는 각 전체 JSON에서 해당 문서의
`integrity.content_fingerprint.value`만 `null`로 바꾸고 UTF-8,
`ensure_ascii=false`, 재귀 key 정렬, compact JSON separator, trailing LF를
적용해 독립 재계산했다. 네 값이 모두 일치한다.

## 2. Current subjects and reviews

| Subject | Bytes | Raw SHA-256 |
|---|---:|---|
| W6 current JSON | 22179 | `a5a1dc844eb66289f5fd6d21c57b9ab7031a47fb499afe9ce12a58c1bbab0c4f` |
| W6 current Markdown | 11828 | `a3c455d6a5a892e519574c2bb703f1c5a590e04ca4326fb7efdccaab0a94b661` |
| W7 current JSON | 41414 | `887fa726af570aa506e906dff5202818a14137b42009a1bee13b2cbf5cffa611` |
| W7 current Markdown | 11458 | `466a3e869dac2c8be3389e7f44fbb5bdefcc78b434ea67f773e674a160a86714` |
| W7 historical review | 6359 | `d3d7508823de8282904e44add8aedeab16e692ddeeea8cf98111d05f25d50345` |
| W7 final rereview | 3444 | `4c1aa9d62ffd936672a697ffcc03e3d8d8a4b9fa49ee5c0e38d3cfc19f2da3c8` |

## 3. Central digest reproduction

| Contract | Canonical preimage | Reproduced SHA-256 | Result |
|---|---|---|---|
| Input set | exact 14 records, path order, `{byte_count,path,sha256}`, role excluded, `ensure_ascii=true`, compact JSON, LF | `45997dd2966d6a062cee12266edbd459224cc31107b3f27f0c02cfc47aea2bbc` | `PASS` in all three central files |
| Current review subject | exact 11 records, path order, `{path,sha256,byte_length}`, `ensure_ascii=false`, compact JSON, LF | `7bc296d82ce24ec68f59cc0d185fce5693030784effcbc6b28c0bbad44088240` | `PASS` in all three central files |
| Central common | declared W7 common preimage, `ensure_ascii=true`, compact JSON, LF | `b30c42ba8af7b4e38e43aa704685a574b06e09d5e14e5262406d3b191d07ca19` | `PASS` in all three central files |

## 4. Binding DAG

세 문서에서 dependency edge를 독립 복원한 결과 17 nodes, 17 edges이고
순환은 없다. Immutable inputs는 delta로만 들어가며 delta는 validation과
receipt로, validation은 receipt로 진행한다. Validation의 delta raw SHA,
receipt의 delta 및 validation raw SHA가 실제 값과 일치한다. 판정은
`ACYCLIC / PASS`이다.

## 5. Exact8 transition and accounting

Exact scope:

`DLV-AIML-06`, `DLV-AIML-07`, `DLV-AIML-10`, `DLV-AIML-14`,
`DLV-AIML-15`, `DLV-AIML-16`, `DLV-AIML-17`, `DLV-AIML-23`

- Transition 1: `DLV-AIML-14`, `INTERNAL_GAP -> OK`
- Stay 7: 나머지 7개, `INTERNAL_GAP -> INTERNAL_GAP`
- Out-of-scope status changes: `0`

| State | Before | After | Delta |
|---|---:|---:|---:|
| `OK` | 115 | 116 | +1 |
| `INTERNAL_GAP` | 64 | 63 | -1 |
| `EXTERNAL` | 42 | 42 | 0 |
| `N_A_CANDIDATE` | 36 | 36 | 0 |
| `TOTAL` | 257 | 257 | 0 |

## 6. Review lineage and authority

Historical review:

- Raw SHA:
  `d3d7508823de8282904e44add8aedeab16e692ddeeea8cf98111d05f25d50345`
- Subject digest:
  `6912e7e3a87394d33ac1d17755c39064e6457d7bc44cfa1ea635ee4d04dc134f`
- Verdict and findings: `GO / blocking 0 / major 0 / minor 2`
- Minor 1: fallback role tokens가 canonical 역할 토큰과 달랐다.
- Minor 2: semantic projection fingerprint의 재현 계약이 충분히 명시되지 않았다.
- Authority: `HISTORICAL_ONLY`

Final rereview:

- Raw SHA:
  `4c1aa9d62ffd936672a697ffcc03e3d8d8a4b9fa49ee5c0e38d3cfc19f2da3c8`
- Current subject digest:
  `7bc296d82ce24ec68f59cc0d185fce5693030784effcbc6b28c0bbad44088240`
- Current subject digest independent replay: `PASS`
- 두 historical minor의 closure: `PASS`
- Verdict and findings: `GO / blocking 0 / major 0 / minor 0`
- Authority: `SOLE_TRANSITION_AUTHORITY`

Historical minor 2건은 보존된 이력이지만 현재 finding이 아니다. W7 전이 권위는
현재 exact11 subject를 결속한 final rereview 하나뿐이다.

## 7. Claim boundary and findings

`DLV-AIML-14`의 `OK`는 exact3 runtime model registry/governance
current-state candidate 충족만 뜻한다. Model approval, model card, quality,
leakage, frozen evaluation, threshold sweep, conversion equivalence, actual
device validation 또는 release completion을 뜻하지 않는다. Formal execution,
evaluation, equivalence와 device validation은 `NOT_RUN`, deployment는
`false`, release는 `NOT_ELIGIBLE`이다.

| Severity | Count |
|---|---:|
| Blocking | 0 |
| Major | 0 |
| Minor | 0 |

## Final verdict

`GO_SEALED`

이 판정은 위 raw SHA로 식별된 W7 central three files와 `W7_EXACT8_ONLY`
경계에만 적용된다.
