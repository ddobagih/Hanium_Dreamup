# W6 Central Sealing Independent Review

- Review date: `2026-07-27`
- Review scope: W6 central three files, W5 predecessor receipt, current W6/W7 subject pairs, and W6 review authority
- Findings: `blocking 0 / major 0 / minor 0`
- Verdict: `GO_SEALED`

## 1. Raw and self-integrity reproduction

| File | Bytes | Raw SHA-256 | Reproduced content/self SHA-256 |
|---|---:|---|---|
| `artifact-status-delta.json` | 31223 | `72dae9c85b1fb7f8de334ec5dbdd19080cbb2929cc3fa93702c8d871f0597341` | `fedf7ad6e8a1af8ed2d92556f8902bd3c686b68ecd9dccd79e25f9389ee897b6` |
| `validation-summary.json` | 20872 | `72e048d7f9fac090f138bc48b3fc13fc6fbd465a5d5cc99a53feee93dcaa9896` | `cca4cbebe2642a330893fda99a71e288dad7b1d32649854e83007466b31d7f5b` |
| `implementation-receipt.json` | 22403 | `5f04aa4c55ca4d52c9fd9e995ae8336413c7c957b983cef87d26da08c81e3db1` | `f2f0dfdcfc357a21e7dc7506ffb1ce1b4ca4521619e76e185814281c343b3e3b` |
| W5 predecessor `implementation-receipt.json` | 47592 | `c229b45989463a5f1eb4943289af42a4a9a8a720131de25421683ca59dc12dbe` | `5ec25c1140588b6c502f1c9c596b2a70e2e8fcde5234001ffeb5be2fd3d21633` |

Self fingerprint는 각 전체 JSON에서 해당 문서의
`integrity.content_fingerprint.value`만 `null`로 바꾸고 UTF-8,
`ensure_ascii=false`, 재귀 key 정렬, compact JSON separator, trailing LF를
적용해 독립 재계산했다. 네 값이 모두 일치한다.

## 2. Current subjects and review authority

| Subject | Bytes | Raw SHA-256 |
|---|---:|---|
| W6 current JSON | 22179 | `a5a1dc844eb66289f5fd6d21c57b9ab7031a47fb499afe9ce12a58c1bbab0c4f` |
| W6 current Markdown | 11828 | `a3c455d6a5a892e519574c2bb703f1c5a590e04ca4326fb7efdccaab0a94b661` |
| W7 successor current JSON | 41414 | `887fa726af570aa506e906dff5202818a14137b42009a1bee13b2cbf5cffa611` |
| W7 successor current Markdown | 11458 | `466a3e869dac2c8be3389e7f44fbb5bdefcc78b434ea67f773e674a160a86714` |
| W6 independent review | 4139 | `5439c7007049bff7108418ff210a17d98030d6bc3b62f5a080a0c48e47dc576a` |

W6 current JSON의 content, semantic, evidence-input fingerprints는 각각
`5d26dba8ca105121ccc29ee4cb56e710615aec08aa8dcde13caec800bedf665a`,
`151b9092646686e86b313db67fc298be35686363efad65b03b73f28a75d34926`,
`2d145f9693807c661d153a8870ba7811b6cb7430f8498f7250d9d4d1d8baf860`
이다.

## 3. Central digest reproduction

| Contract | Canonical preimage | Reproduced SHA-256 | Result |
|---|---|---|---|
| Input set | exact 13 records, path order, `{byte_count,path,sha256}`, role excluded, `ensure_ascii=true`, compact JSON, LF | `99ff64bf715ee41751b1ff5899cbecc1b0b913bded79f9fe44759833a9572e64` | `PASS` |
| Current-successor subject | exact 11 records, path order, `{path,sha256,byte_length}`, `ensure_ascii=false`, compact JSON, LF | `7bc296d82ce24ec68f59cc0d185fce5693030784effcbc6b28c0bbad44088240` | `PASS` |
| Central common | declared W6 common preimage, `ensure_ascii=true`, compact JSON, LF | `d8eff6371281e5ae3ac2b89d0bc2ee9799c071e93cd5be49740c4f535a679ac2` | `PASS` in all three central files |

## 4. Binding DAG

독립적으로 복원한 순서는 immutable upstream inputs에서 delta로, delta에서
validation으로, delta와 validation에서 receipt로 진행한다. Delta의 두
downstream 참조는 `PATH_ONLY`이므로 forward raw-hash self-reference가 없다.
Validation은 delta raw SHA를, receipt는 delta와 validation raw SHA를 정확히
결속한다. 역방향 edge와 순환은 없으며 판정은 `ACYCLIC / PASS`이다.

## 5. Exact8 transition and accounting

Exact scope:

`DLV-AIML-04`, `DLV-AIML-05`, `DLV-AIML-08`, `DLV-AIML-09`,
`DLV-AIML-11`, `DLV-AIML-12`, `DLV-AIML-13`, `DLV-AIML-21`

- Transition 1: `DLV-AIML-04`, `INTERNAL_GAP -> OK`
- Stay 7: 나머지 7개, `INTERNAL_GAP -> INTERNAL_GAP`
- Out-of-scope status changes: `0`

| State | Before | After | Delta |
|---|---:|---:|---:|
| `OK` | 114 | 115 | +1 |
| `INTERNAL_GAP` | 65 | 64 | -1 |
| `EXTERNAL` | 42 | 42 | 0 |
| `N_A_CANDIDATE` | 36 | 36 | 0 |
| `TOTAL` | 257 | 257 | 0 |

## 6. Temporal authority boundary

- W6 review-time subject digest:
  `6912e7e3a87394d33ac1d17755c39064e6457d7bc44cfa1ea635ee4d04dc134f`
- Current-successor subject digest:
  `7bc296d82ce24ec68f59cc0d185fce5693030784effcbc6b28c0bbad44088240`
- 두 digest는 동일 대상이라고 주장하지 않는다.
- 차이는 W6 review 이후 W7 minor 2건을 닫기 위해 W7 pair가 수정됐기 때문이다.
- Historical preimage는 현재 보존돼 있지 않아 현 working tree에서 재생할 수 없다.
- W6 pair와 공통 source 7종은 불변이고, current-successor exact11 digest는
  현재 자료로 독립 재현됐다.
- W6 independent review의 `GO / 0/0/0`은 `W6_EXACT8_ONLY`의 sole
  transition authority이다.
- W6 review가 revised W7 pair를 검토했다는 주장은 없다. Revised W7 pair의
  권위는 별도 W7 final rereview에 있다.

따라서 historical preimage 부재는 명시된 temporal 재현 제한이지만 W6 exact8
전이 또는 현재 중앙 결속의 finding은 아니다.

## 7. Claim boundary and findings

`OK`는 내부 current-state candidate 증거 충족만 뜻한다. Formal 279,
actual device validation, equivalence, deployment는 `NOT_RUN`, release gate
5개는 `NOT_RUN / unwaived`, release는 `NOT_ELIGIBLE`이다.

| Severity | Count |
|---|---:|
| Blocking | 0 |
| Major | 0 |
| Minor | 0 |

## Final verdict

`GO_SEALED`

이 판정은 위 raw SHA로 식별된 W6 central three files와 `W6_EXACT8_ONLY`
경계에만 적용된다.
