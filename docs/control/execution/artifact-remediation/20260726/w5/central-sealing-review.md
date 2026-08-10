# W5 중앙 봉인 독립 검토

- 검토일: `2026-07-27`
- 판정: `GO_W5_SEALED`
- finding: `blocking 0 / major 0 / minor 0`
- 봉인 범위: W5 immutable input 27개와 중앙 output 3개
- seal subject count: `30`
- seal subject digest: `b98846f8f88b0b7353b6b0f088978eb746a741e9b4d4f66cf24c6c58983d61c5`
- digest 규칙: `{byte_count, path, sha256}` exact30을 path UTF-8 byte order로 정렬하고 UTF-8 JSON, `ensure_ascii=true`, `sort_keys=true`, compact separator, trailing LF로 canonicalize한 뒤 SHA-256

이 판정은 W5 중앙 상태 전환 패키지의 무결성과 계보를 봉인한다. formal 보안 PASS, 모든 finding 종결, 법률 승인, signing, deployment 또는 release 적격을 승인하지 않는다.

## 중앙 raw 및 content fingerprint

| Output | Raw SHA-256 | Content fingerprint | 판정 |
|---|---|---|---|
| `artifact-status-delta.json` | `82e81294c5acf3346bc4128c0ebd0ed7b31142c70b04877d8af9826635e16932` | `54624f2640f88ca3be453cc6ac6292130b766a3359d1637694319c24a6eadc66` | `PASS` |
| `validation-summary.json` | `48de6bc48d552e8b9609bb6069f87edbbc78c67816d091ae09aba6aaaf31224c` | `fa5cf9edfd51c0ad062fc277179fe1b2d68bd430506ba33432a15c270a66c62d` | `PASS` |
| `implementation-receipt.json` | `c229b45989463a5f1eb4943289af42a4a9a8a720131de25421683ca59dc12dbe` | `5ec25c1140588b6c502f1c9c596b2a70e2e8fcde5234001ffeb5be2fd3d21633` | `PASS` |

각 content fingerprint는 해당 문서의 `integrity.content_fingerprint.value`만 JSON `null`로 바꾸고 UTF-8, `ensure_ascii=false`, `sort_keys=true`, compact separator, trailing LF로 canonicalize해 독립 재현했다.

## 공통 digest

| Digest | 독립 재현값 | 판정 |
|---|---|---|
| input set, exact27 | `e385856ee00ce497c69e732e415ceab9d38efe8fb5c773c05044b2ba6a5ae833` | 모든 실제 path/hash/byte 일치 |
| final review subject, exact22 | `649a8eeceae8863b0f75a19a40672fbc2e36484f496366daaea95026d7cccee8` | `<path><TAB><sha256><LF>` 직렬화 재현 |
| W5 common fingerprint | `5ae2a694c2361ee407d91a192d16f6fb6123ec8a1af111dade700b8d332f8424` | 중앙 3파일의 preimage와 wrapper 모두 일치 |

중앙 3파일의 input, final-subject 및 common preimage는 byte-for-byte 동등한 구조화 값이다. W5 final review는 final subject에서 제외되고 별도 raw SHA-256으로 결속되어 자기참조를 만들지 않는다.

## W4 predecessor와 계수

- W4 terminal predecessor: `docs/control/execution/artifact-remediation/20260726/w4/implementation-receipt.json`
- W4 predecessor SHA-256: `e924b5216da5ba174fb765c8c5d8d18e91a9b2a8aeec8dd8b6876b0a644210ba`
- 실제 path/hash/byte 및 W4 common preimage의 post count: `PASS`

| 구분 | OK | INTERNAL_GAP | EXTERNAL | N_A_CANDIDATE | TOTAL |
|---|---:|---:|---:|---:|---:|
| W5 전, W4 terminal | 109 | 71 | 41 | 36 | 257 |
| W5 delta | +5 | -6 | +1 | 0 | 0 |
| W5 후 | 114 | 65 | 42 | 36 | 257 |

각 분류의 `pre + delta = post`이며 전체 257은 변하지 않는다.

## Exact9 transition

| Artifact | From | To |
|---|---|---|
| `DLV-SEC-01` | `INTERNAL_GAP` | `INTERNAL_GAP` |
| `DLV-SEC-02` | `INTERNAL_GAP` | `OK` |
| `DLV-SEC-03` | `INTERNAL_GAP` | `OK` |
| `DLV-SEC-06` | `INTERNAL_GAP` | `EXTERNAL` |
| `DLV-SEC-09` | `INTERNAL_GAP` | `OK` |
| `DLV-SEC-10` | `INTERNAL_GAP` | `INTERNAL_GAP` |
| `DLV-SEC-11` | `INTERNAL_GAP` | `OK` |
| `DLV-SEC-12` | `INTERNAL_GAP` | `INTERNAL_GAP` |
| `DLV-SEC-15` | `INTERNAL_GAP` | `OK` |

- exact9 order, unique count 및 transition count: `PASS`
- changed transition 6건, retained transition 3건: `PASS`
- out-of-scope status mutation: `0`
- `OK` 의미: 내부 content/trace required action 충족이며 formal security PASS가 아님

## 권한과 이력

현재 status transition의 단일 권한은 `independent-final-review.md`다.

- raw SHA-256: `11bcfcde2203cd1215f22858e6f0c84c971f20cf0d2d69cbbbf90bf6f99bf0d9`
- status: `GO_STATUS_DELTA_ALLOWED`
- subject digest: `649a8eeceae8863b0f75a19a40672fbc2e36484f496366daaea95026d7cccee8`
- finding: `0 / 0 / 0`
- authority scope: `EXACT9_INTERNAL_ARTIFACT_STATUS_DELTA_ONLY`
- formal/signing/deployment/release transition allowed: `false`

두 선행 리뷰는 변경 또는 삭제되지 않고 historical remediation evidence로 보존된다.

- `independent-review.md`: `NO_GO_REQUIRES_CORRECTION_AND_REREVIEW`, `2 blocking / 1 major`
- `independent-rereview.md`: `NO_GO_REQUIRES_CORRECTION_AND_REREVIEW`, `1 blocking`
- 두 이력 모두 `status_delta_allowed=false`

## 비순환 output DAG

Topological order:

1. `artifact-status-delta.json`
2. `validation-summary.json`
3. `implementation-receipt.json`

- delta는 immutable upstream raw hash만 결속하고 validation/receipt는 path-only forward reference다.
- validation은 delta raw SHA-256을 결속하고 receipt는 path-only forward reference다.
- receipt는 delta와 validation raw SHA-256을 결속하며 downstream이 없다.
- downstream output raw SHA를 upstream에서 역결속한 edge는 `0`.
- cycle status: `ACYCLIC`
- validation self checks: `28 passed / 0 failed`

## Open4 및 release 경계

| Artifact | Classification | Boundary | Status |
|---|---|---|---|
| `DLV-SEC-01` | `INTERNAL_GAP` | 역할 배정·교육·확인 receipt | `OPEN` |
| `DLV-SEC-10` | `INTERNAL_GAP` | Kotlin parse error 1건과 named source generation | `OPEN` |
| `DLV-SEC-12` | `INTERNAL_GAP` | 전체 repository history secret scan | `NOT_RUN` |
| `DLV-SEC-06` | `EXTERNAL` | 법률·개인정보·제품·TMAP 계약 승인 | `PROPOSED_PENDING_EXTERNAL_LEGAL_REVIEW` |

- formal catalog: `279`
- formal execution/pass/evidence: `NOT_RUN / 0 / 0`
- actual device, full E2E, signing, deployment: `NOT_RUN`
- release gates: `5 / NOT_RUN / not waived`
- legal/privacy approval: `NOT_RUN_EXTERNAL_PENDING`
- full repository-history secret scan: `NOT_RUN`
- formal security PASS 및 zero vulnerability claim: `false`
- release: `NOT_ELIGIBLE`

이번 검토는 파일 바이트와 구조화 봉인 계약만 독립 재현했다. 테스트, Git, Android/Gradle 및 daylog는 실행하거나 변경하지 않았다.

