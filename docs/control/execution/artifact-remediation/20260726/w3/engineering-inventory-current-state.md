# W3 개발 인벤토리 현재 상태

- 문서 ID: `WS-ARTIFACT-REMEDIATION-W3-ENGINEERING-INVENTORY-CURRENT-STATE-20260726-001`
- 재결속: `evidence-20260726-003` + `aux-execution-20260726-005` + `integration-run-20260726-004`
- 범위: `DLV-DEV-01`, `DLV-DEV-07`, `DLV-DEV-14`, `DLV-DEV-18` exact 4
- source commit: `null`
- 공통 subject fingerprint: `b45b6076b211fbdad5343601761ea562f1a6854e36d497d1b7a0ea9cb8e6d783`
- JSON content fingerprint: `b07754e31637ac104aaffc7b9caa92a7195989697631529dd682e225144c63e4`

기존 baseline·승인을 수정하지 않는 add-only 현재 상태 보완본이다. 최종 분류는 W3 독립 검토와 `artifact-status-delta.json`이 결정한다.

## 독립 재판정

| 산출물 | disposition | 권고 분류 | 재판정 근거 |
|---|---|---|---|
| `DLV-DEV-01` | 내부 근거 해소, W3 검토 대기 | `OK` 유지 | 2,960-file snapshot·current backend subject·integration-run-004 내부 PASS |
| `DLV-DEV-07` | 내부 근거 해소, W3 검토 대기 | `OK` 유지 | 현행 lock 5개와 aux-005의 current SBOM 공식 schema PASS |
| `DLV-DEV-14` | `GAP_REMAINS` | `INTERNAL_GAP` 유지 | formal 279 중 explicit fixture link 0, unassigned 279 |
| `DLV-DEV-18` | 내부 근거 해소, W3 검토 대기 | `OK` 유지 | 5개 모듈·365파일·중복 0, backend 79파일 current subject 결속 |

## current source·모듈

- snapshot: 2,960 stable files
- path set: `971cee4fbded63faf05e30b0dc7343a9eff41e4612edff307f3df5581c880eff`
- content set: `f7a05a1abd7b89053dd7d7d052508dfd43b19821174c8de5a82fe754ae90cade`
- backend: 79 files, content set `5294c9a706fa5b69389e42b8c505a7b1fb4f72fa18f210d724037a79be2c431c`
- app 208, adminapp 25, gateway 26, backend 79, model-config 27; duplicate ownership 0
- 역사 오류 `apps/android/admin`, `adminapp=0`, `android-gateway=0`은 current fact가 아니다.

## current dependency·실행

- final evidence-003: 8개, content set `faa5f06b34b6b111593c6357b70eabc0f477baf22a0a9ab2f6dcbbf71ee93b43`
- lock: 5개, dependency 526개
- aux-005: `PASS_INTERNAL_CURRENT_SOURCE_WITH_EXTERNAL_BOUNDARIES`
- SBOM: CycloneDX 1.6·SPDX 2.3 공식 local schema PASS
- license metadata: 531개 중 known 346, unknown 185, exception 13; 법률 승인 아님
- integration-run-004: DB-free 33 PASS, fresh PostGIS node 2회 PASS, migration·schema parity·cleanup PASS
- 남은 경계: Android→Gateway→Backend cross-process, actual device/network/TMAP `NOT_RUN`

## fixture·경계

fixture 6개와 formal ID 279개를 inventory했지만 explicit link 0·unassigned 279이므로 DEV-14는 W4 `GAP_REMAINS`다. signing `NOT_ASSESSED`, formal `279 NOT_RUN`, gate 5개 모두 `NOT_RUN`·unwaived, release `NOT_ELIGIBLE`다.

## stable gaps

| gap | wave | 범위 |
|---|---|---|
| `W3-DEV-INVENTORY-GAP-001` | W3 | exact 4 검토·중앙 delta |
| `W3-DEV-INVENTORY-GAP-002` | W4 | DEV-14 fixture/formal |
| `W3-DEV-INVENTORY-GAP-003` | W5 | DEV-07 license |
| `W3-DEV-INVENTORY-GAP-004` | W6 | DEV-18 model/runtime |
| `W3-DEV-INVENTORY-GAP-005` | W7 | DEV-01/18 cross-process·actual device |
| `W3-DEV-INVENTORY-GAP-006` | W9 | DEV-01/07/18 signing·gate·release |

JSON은 current 직접 근거 19개의 exact path/SHA와 각 gap의 owner·종료 조건·기대 증거를 포함한다. 생성 시 exact-4, 단일 disposition, source mismatch 0, backend subject 일치, gap orphan 0을 확인했다.
