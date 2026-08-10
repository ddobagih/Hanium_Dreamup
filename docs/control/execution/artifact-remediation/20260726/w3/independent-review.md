# W3 DEV 산출물 독립 검토

- 검토일: 2026-07-27
- 검토 범위: `W3` exact 17 (`DLV-DEV-01/02/03/04/05/06/07/08/09/12/14/16/17/18/19/20/21`)
- 검토 방식: 기존 JSON·Markdown·receipt·manifest의 구조, path/SHA-256, 내용 지문과 계보를 읽기 전용으로 대조
- 재실행하지 않은 항목: build, test, dependency install, 실제 기기, 외부 서비스, 배포
- 독립성 경계: 이 문서는 내부 기술 검토이며 사용자 승인, 법무 승인, 산출물 승인 또는 출시 승인이 아니다.

## 최종 판정

**APPROVED_WITH_OPEN_GAPS**

W3 최신 근거 패키지의 무결성, 내부 실행 결과, 미검증 경계 표시와 보수적 status disposition을 승인한다. 최종 disposition은 exact 17, `OK` 9개와 `INTERNAL_GAP` 8개다. 검토 중 제시됐던 16개 일괄 `OK` 승격은 실행 전에 철회됐으므로 활성 finding이 아니다.

- 근거 패키지 무결성: `PASS`
- exact 17 coverage: `PASS`
- 권고 전환: `OK` 9, `INTERNAL_GAP` 유지 8
- 일괄 `OK` 16 전환: `WITHDRAWN_NOT_APPLIED`
- blocking: 0
- major: 0
- minor: 0
- 승인 경계: 내부 기술 검토 승인일 뿐 사용자·외부·법무·산출물·출시 승인이 아니다.

## Findings

활성 finding은 없다.

- 철회된 16개 일괄 `OK` 제안은 최종 상태에 적용되지 않았다.
- exact disposition은 `OK` 9개와 `INTERNAL_GAP` 8개이며, 부분 충족 action을 완료로 승격하지 않는다.
- DEV21의 내부 backend PASS는 actual-device·cross-process action 완료로 사용하지 않는다.

## ID별 required_action 및 전환 판정

`OK`는 현재 산출물의 내부 내용·추적·무결성이 충족됐다는 뜻이며 formal, device, production 또는 release 완료를 뜻하지 않는다.

| ID | required_action 독립 판정 | 권고 전환 | 근거와 남은 경계 |
|---|---|---|---|
| `DLV-DEV-01` | `SATISFIED_INTERNAL` | `OK` | dirty-worktree 2,960파일 exact snapshot과 current backend subject가 결속됐다. clean commit·release 주장은 없다. |
| `DLV-DEV-02` | `SATISFIED_INTERNAL` | `OK` | 현재 모듈·서비스 경계, 정본 링크와 검증 명령이 current guide에 반영됐다. |
| `DLV-DEV-03` | `SATISFIED_CONTENT_ONLY` | `OK` | 고정 toolchain과 bootstrap 절차가 문서화됐다. exact locked Node·submission Python 실행과 first-time offline 재현 검증은 후속 gap이다. |
| `DLV-DEV-04` | `SATISFIED_CONTENT_ONLY` | `OK` | 두 Android 앱·Gateway·Backend의 실행·시험·cleanup 절차와 현재 내부 결과가 정합화됐다. formal·device·cross-process는 별도 `NOT_RUN`이다. |
| `DLV-DEV-05` | `SATISFIED_BY_THIS_REVIEW` | `OK` | 실패·부분 실행을 보존한 append-only 계보와 이 독립 검토가 결속된다. |
| `DLV-DEV-06` | `SATISFIED_CONTENT_ONLY` | `OK` | Python·TypeScript·Kotlin·Java 범위와 적용 도구가 명시됐다. 승인된 전체 static ruleset과 formal 실행은 DEV16/W4 경계다. |
| `DLV-DEV-07` | `SATISFIED_INTERNAL` | `OK` | 현행 lock source 5개, dependency 526개와 current source/SBOM hash가 결속됐다. unknown license 185건은 DEV17/W5 경계다. |
| `DLV-DEV-08` | `SATISFIED_CONTENT_ONLY` | `OK` | 모듈별 설정, 허용값, secret 경계와 fail-closed 오류 규칙이 갱신됐다. 실제 secret 값은 포함하지 않았다. |
| `DLV-DEV-09` | `PARTIALLY_SATISFIED` | `INTERNAL_GAP` | Android user/admin APK와 Gateway dist는 input·command·output·hash가 있다. 설치 가능한 backend/model release build와 startup·cross-process runtime은 없다. 목표 wave는 `W8`이다. |
| `DLV-DEV-12` | `PARTIALLY_SATISFIED` | `INTERNAL_GAP` | fresh PostGIS migration·schema parity·동일 DB 반복은 PASS다. formal build 결속, production migration, backup·restore·rollback은 미실행이다. 목표 wave는 `W8`이다. |
| `DLV-DEV-14` | `NOT_SATISFIED` | `INTERNAL_GAP` | canonical formal test 279개 중 explicit fixture link 0, unassigned 279다. `W4`에서 유지한다. |
| `DLV-DEV-16` | `PARTIALLY_SATISFIED` | `INTERNAL_GAP` | exact-source lint·typecheck·compileall raw 근거는 PASS다. 승인된 전체 static-analysis ruleset과 formal baseline은 없다. 최종 required_action 증거는 `W8`에서 결속한다. |
| `DLV-DEV-17` | `PARTIALLY_SATISFIED` | `INTERNAL_GAP` | 531개 component의 metadata inventory는 known 346, unknown 185, exception 13이다. unknown 해소와 법무·NOTICE 검토가 남았다. 목표 wave는 `W5/W8`이다. |
| `DLV-DEV-18` | `SATISFIED_INTERNAL` | `OK` | app 208, adminapp 25, gateway 26, backend 79, model-config 27의 5모듈·365파일 원장이 있고 중복 ownership은 0이다. |
| `DLV-DEV-19` | `PARTIALLY_SATISFIED` | `INTERNAL_GAP` | current SPDX 2.3·CycloneDX 1.6은 공식 schema 오류 0이다. 고정 release generation·license·signing 결속은 없다. 목표 wave는 `W8`이다. |
| `DLV-DEV-20` | `PARTIALLY_SATISFIED` | `INTERNAL_GAP` | dirty-worktree provenance와 artifact hash는 있다. `source_commit=null`이고 reproducible rebuild·attestation·signing은 `NOT_RUN/NOT_ASSESSED`다. required_action 증거는 `W8`, signing·release 결정은 별도 `W9` 경계다. |
| `DLV-DEV-21` | `PARTIALLY_SATISFIED` | `INTERNAL_GAP` | current backend DB-free/PostGIS 통합은 PASS다. 같은 release generation의 Android→Gateway→Backend→PostGIS, 실제 기기·network·TMAP 실행은 없다. 목표 wave는 `W4/W8`이다. |

## 무결성 및 계보 검토

### exact scope

- W3 plan의 covered ID는 17개, 중복 0, 누락 0, 추가 0이다.
- guide 6 + inventory 4 + execution 6 + DEV21 integration 1의 합집합이 exact 17이다.
- `DLV-DEV-14`는 `GAP_REMAINS`, `INTERNAL_GAP`, target `W4`로 일관된다.

### current-state source binding

| current-state JSON | source binding | path 누락 | SHA 불일치 | recomputed content fingerprint |
|---|---:|---:|---:|---|
| `development-guide-current-state.json` | 55 | 0 | 0 | `5eddc7f8fbb84ff02caf9b93773dd07be42c5d78e0cac2da325c33f6278a3072` PASS |
| `engineering-inventory-current-state.json` | 19 | 0 | 0 | `b07754e31637ac104aaffc7b9caa92a7195989697631529dd682e225144c63e4` PASS |
| `engineering-execution-current-state.json` | 40 | 0 | 0 | `3c07d7d073513361261421887bdad2e8e8839df9e0003693a457f67b282e4c21` PASS |

세 Markdown pair는 각 JSON의 exact ID, 내부 PASS 범위, formal 279 `NOT_RUN`, release `NOT_ELIGIBLE`과 동일한 의미를 유지한다.

### final run/evidence

- `run-20260726-003/command-receipt.json`: SHA-256 `899d88cfd6337e80992c1ccaba620d23fdca4a611f51aeac9cc19923a7b3cdf4`
- run003 command: exact 5, exit code 0은 5개다.
- `evidence-20260726-003`: JSON output exact 8이며 manifest binding 7개는 누락 0, SHA 불일치 0이다.
- evidence003 manifest SHA-256: `13674e1132727cd7d065bf67658505d8ba2859bf1c2680e316495dc6738825d5`
- source snapshot SHA-256: `9db28f43297b961a0e7290c1f85f706d2ca34b84c95879e71293f32a4266e103`
- module inventory SHA-256: `5473ba952634f7c9596ce970d377c62bed78beba9be1a86b846d29ac78830fb1`
- lock inventory SHA-256: `baddd42d96d21a751df4494a48fa0b1e81f7df233c60d63981dd4e754ee5df04`
- fixture inventory SHA-256: `be584ced83fad77cf859dbd5f3ec2e3c2ba8ba56c848d7a16afccdddbc6a1aa6`
- internal build provenance SHA-256: `56b7ec0a352bdf590846bcda6ec69d7594a68989d1326917c43874ea016e0492`
- CycloneDX SHA-256: `cdad7be21efe592c5d3ae429b3718a2561f475c3bccc7a98e3430beaf876cfce`
- SPDX SHA-256: `7599f3129872f20b44ca9b8c6363b29880018f536a54100279cd4a90a66ddb9e`

### current product subject

- run003 backend subject raw SHA-256과 integration004 current backend subject raw SHA-256은 모두 `eeae9b7e143dcf0336e97ed504a8456283d5fa2c46b355fd5af9f79989bd45cc`로 정확히 일치한다.
- backend 79파일 content set은 inventory, execution, integration binding에서 모두 `5294c9a706fa5b69389e42b8c505a7b1fb4f72fa18f210d724037a79be2c431c`다.
- source snapshot content set은 inventory와 execution에서 모두 `f7a05a1abd7b89053dd7d7d052508dfd43b19821174c8de5a82fe754ae90cade`다.
- backend schema/test fix source SHA-256은 `backend/app/models.py` `9d361130...`, `backend/tests/conftest.py` `28597417...`, `backend/tests/test_admin_security.py` `dff5dca5...`로 integration004 binding과 일치한다.

### append-only predecessor

- `run-20260726-001`, `aux-execution-20260726-002`, `integration-run-20260726-001/002/003` 디렉터리와 실패·부분 실행 파일은 보존돼 있다.
- integration004는 integration001/002/003을 `immutable=true` predecessor로 선언한다.
- final rebinding은 `predecessor_evidence_rewritten=false`이며 실패를 성공으로 재라벨하지 않는다.
- run003, evidence003, integration004, aux005는 각각 최신 successor 역할을 하며 선행 기록을 덮어쓰지 않는다.

## 내부 실행 근거 검토

| 범위 | 판정 | 제한 |
|---|---|---|
| generator `--write` / `--check` | `PASS` | final run003/evidence003 생성 정합성 |
| generator unittest | `48 PASS` | 최종 source 기준 내부 회귀 |
| final tooling review | blocking/major/minor `0/0/0`, heavy `GO` | 내부 독립 code review이며 사용자·외부 승인 아님 |
| run003 build/package/subject capture | exact 5, exit 0 | Android user/admin debug APK·Gateway dist·source subjects |
| backend/model compileall | `PASS` | Python bytecode compile이며 installable release build 아님 |
| Android user/admin lint | `PASS_REUSED_EXACT_SOURCE` | 재실행이 아니라 동일 module content set 재사용 |
| Gateway typecheck | `PASS_REUSED_EXACT_SOURCE` | exact Node lock 실행은 별도 gap |
| migration/schema | `PASS` | fresh PostGIS 내부 DB, production/backup/restore 아님 |
| license inventory | `PASS_METADATA_INVENTORY_NOT_LEGAL_APPROVAL` | unknown 185, 법무 승인 없음 |
| CycloneDX 1.6 official schema | `PASS`, errors 0 | license·release 승인 아님 |
| SPDX 2.3 official schema | `PASS`, errors 0 | license·release 승인 아님 |
| provenance | `PASS_INTERNAL_CAPTURE` | reproducible build·attestation·signing 아님 |

## 시험 결과 경계

- Gateway 62, Android user 728, Android admin 38은 unchanged exact source에 대한 내부 PASS 재사용이다.
- backend DB-free 33은 current backend subject에서 PASS다.
- PostgreSQL exact node 1건은 같은 single-use migrated DB에서 순차 2회 모두 PASS다.
- 위 수치는 서로 다른 내부 suite이며 formal 279와 합산하거나 대체하지 않는다.
- formal 279는 `279/279 NOT_RUN`, `pass_claimed=false`다.
- 실제 Android device, Android→Gateway→Backend→PostGIS cross-process, network transition, TMAP, production deploy, signing과 release gate 5개는 `NOT_RUN/NOT_ASSESSED`다.
- release gate waiver는 0이고 release는 `NOT_ELIGIBLE`이다.

## Open gap 후속 evidence

- `W4`: DEV14의 279개 test-fixture 명시 결속과 DEV21의 actual-device·cross-process 실행 근거를 추가한다.
- `W5`: DEV17의 unknown 185개, exception, NOTICE·의무와 법무 검토 근거를 추가한다.
- `W8`: DEV09·DEV12·DEV16·DEV19·DEV20과 DEV21 잔여 required_action의 formal build, migration roundtrip·backup, approved static ruleset, release-generation SBOM, reproducibility·provenance, 통합 판정 근거를 결속한다.
- 후속 receipt 전에는 8개 `INTERNAL_GAP`을 `OK`로 전환하지 않는다.
- formal 279, 실제 기기, 외부 서비스, production, signing과 release gate는 각 실행·승인 receipt 전까지 기존 `NOT_RUN/NOT_ASSESSED/NOT_ELIGIBLE` 경계를 유지한다.
