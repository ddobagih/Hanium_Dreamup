# W2 인터페이스 계약 실행 증거

- evidence ID: `WS-W2-INTERFACE-CONTRACT-EVIDENCE-20260726-001`
- 범위: `DLV-DES-09`, `DLV-DES-10`
- content fingerprint: `c9b87548a8dd79fcb640f4459fa08e83bddfc7f0e77a955e3f04390d759bc438`
- runtime raw: `docs/control/execution/artifact-remediation/20260726/w2/runtime-openapi-current.json` SHA `e66280b6cc4308c07c49ee183ce557613b531e2fc0554124c55bb472fe9b5d1b`
- source commit: `null`, exact path/content binding

## Runtime generation

| 항목 | 결과 |
|---|---|
| profile | canonical production-shape, non-runtime fixture credentials |
| 독립 생성 | 2회 |
| run hashes | `e66280b6cc4308c07c49ee183ce557613b531e2fc0554124c55bb472fe9b5d1b`, `e66280b6cc4308c07c49ee183ce557613b531e2fc0554124c55bb472fe9b5d1b` |
| 결정성 | `PASS` |
| checked-in byte equality | `PASS` |
| backend missing/extra/mismatch | `0/0/0` |
| unexplained | `0` |

실행 profile은 field security와 DB-backed admin security를 켠 `test` schema profile이다. 기본 development profile의 legacy `WalkSafeAdminToken`과 달리 canonical production-shape는 bearer+app-kind+role+audience+device 문맥을 요구한다. 이 차이는 숨기지 않고 breaking auth profile 차이로 분류한다.

## Backend normalized operation surface

| operation | auth | request | response | error | idempotency header | servers | deprecated |
|---|---|---|---|---|---|---|---|
| `GET /admin/security/sessions` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `0/0` | resp `200` | err `none` | idem `none` | server `False` | deprecated `False` |
| `GET /admin/security/state` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `0/0` | resp `200` | err `none` | idem `none` | server `False` | deprecated `False` |
| `GET /android/debug/depth-logs/recent` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `1/0` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `GET /android/debug/frame-captures/recent` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `1/0` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `GET /detect/health` | `WalkSafeFieldToken` | req `0/0` | resp `200` | err `none` | idem `none` | server `False` | deprecated `False` |
| `GET /detect/v2/health` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `0/0` | resp `200` | err `none` | idem `none` | server `False` | deprecated `False` |
| `GET /health` | `WalkSafeFieldToken` | req `0/0` | resp `200` | err `none` | idem `none` | server `False` | deprecated `False` |
| `GET /navigation/destinations/search` | `WalkSafeActorAssertion+WalkSafeActorId+WalkSafeFieldToken` | req `4/0` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `GET /navigation/destinations/search/health` | `WalkSafeFieldToken` | req `0/0` | resp `200` | err `none` | idem `none` | server `False` | deprecated `False` |
| `GET /navigation/walking/health` | `WalkSafeFieldToken` | req `0/0` | resp `200` | err `none` | idem `none` | server `False` | deprecated `False` |
| `GET /ready` | `WalkSafeFieldToken` | req `0/0` | resp `200` | err `none` | idem `none` | server `False` | deprecated `False` |
| `GET /reports` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `17/0` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `GET /reports/duplicate-check` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `9/0` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `GET /reports/export` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `23/0` | resp `200,422` | err `422` | idem `X-WalkSafe-Reconfirm-Nonce` | server `False` | deprecated `False` |
| `GET /reports/summary` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `17/0` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `GET /reports/{report_id}` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `3/0` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `GET /uploads/{filename}` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `3/0` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `PATCH /reports/{report_id}/status` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `3/1` | resp `200,422` | err `422` | idem `X-WalkSafe-Reconfirm-Nonce` | server `False` | deprecated `False` |
| `POST /admin/security/reauthenticate` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `0/1` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `POST /admin/security/recovery/complete` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `0/1` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `POST /admin/security/recovery/start` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `0/1` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `POST /admin/security/sessions` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `0/1` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `POST /admin/security/sessions/{session_id}/revoke` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `1/0` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `POST /android/debug/depth-logs` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `0/1` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `POST /android/debug/frame-captures` | `WalkSafeAdminAppKind+WalkSafeAdminAudience+WalkSafeAdminBearer+WalkSafeAdminDeviceId+WalkSafeAdminRole` | req `0/1` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `POST /detect` | `WalkSafeActorAssertion+WalkSafeActorId+WalkSafeFieldToken` | req `0/1` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `POST /detect/v2` | `WalkSafeActorAssertion+WalkSafeActorId+WalkSafeFieldToken` | req `0/1` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `POST /navigation/walking` | `WalkSafeActorAssertion+WalkSafeActorId+WalkSafeFieldToken` | req `0/1` | resp `200,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `POST /reports` | `WalkSafeActorAssertion+WalkSafeActorId+WalkSafeFieldToken` | req `1/1` | resp `201,422` | err `422` | idem `none` | server `False` | deprecated `False` |
| `POST /reports/v2` | `WalkSafeActorAssertion+WalkSafeActorId+WalkSafeFieldToken` | req `1/1` | resp `201,422` | err `422` | idem `none` | server `False` | deprecated `False` |

runtime과 checked-in은 method/path/auth/request/response/error/idempotency/server/deprecation/x-extension 전체에서 동일하다. `servers` 미선언과 report create 범용 idempotency 부재는 동일성 PASS와 별개의 W3 계약 gap이다.

## Gateway source 대 OpenAPI

| surface | method/path | control | 판정 | operation/gap |
|---|---|---|---|---|
| `GW-DECL-DELETE-API-FIELD-SESSION` | `DELETE /api/field-session` | `-` | `MATCHED` | `DELETE /api/field-session` |
| `GW-DECL-GET-API-FIELD-SESSION` | `GET /api/field-session` | `-` | `MATCHED` | `GET /api/field-session` |
| `GW-DECL-POST-API-FIELD-SESSION` | `POST /api/field-session` | `-` | `MATCHED` | `POST /api/field-session` |
| `GW-DECL-GET-API-FIELD-WALK` | `GET /api/field-walk` | `-` | `MATCHED` | `GET /api/field-walk` |
| `GW-DECL-POST-API-FIELD-WALK` | `POST /api/field-walk` | `-` | `MATCHED` | `POST /api/field-walk` |
| `GW-DECL-GET-API-NAVIGATION-DESTINATIONS-SEARCH` | `GET /api/navigation/destinations/search` | `-` | `MATCHED` | `GET /api/navigation/destinations/search` |
| `GW-DECL-POST-API-NAVIGATION-WALKING` | `POST /api/navigation/walking` | `-` | `MATCHED` | `POST /api/navigation/walking` |
| `GW-DECL-POST-API-REPORTS-V2` | `POST /api/reports/v2` | `-` | `MATCHED` | `POST /api/reports/v2` |
| `GW-PRIVACY-PAGE-GET` | `GET /privacy/rights` | `-` | `UNDECLARED_GAP` | `W2-IFDATA-GAP-002` |
| `GW-PRIVACY-PAGE-HEAD` | `HEAD /privacy/rights` | `-` | `UNDECLARED_GAP` | `W2-IFDATA-GAP-002` |
| `GW-INTEGRATED-CONSENT-GET` | `GET /privacy/rights` | `integrated-consent` | `UNDECLARED_GAP` | `W2-IFDATA-GAP-002` |
| `GW-INTEGRATED-CONSENT-PUT` | `PUT /privacy/rights` | `integrated-consent` | `UNDECLARED_GAP` | `W2-IFDATA-GAP-002` |
| `GW-ACCOUNT-DELETION-GET` | `GET /privacy/rights` | `account-deletion` | `UNDECLARED_GAP` | `W2-IFDATA-GAP-002` |
| `GW-ACCOUNT-DELETION-POST` | `POST /privacy/rights` | `account-deletion` | `UNDECLARED_GAP` | `W2-IFDATA-GAP-002` |

OpenAPI 8개 operation은 source와 모두 연결된다. `/privacy/rights`의 6개 query-discriminated surface는 실제 source에 있지만 OpenAPI에는 없으며 전부 `W2-IFDATA-GAP-002`로 분류했다. 설명되지 않은 missing/extra/mismatch는 0이다.

## Android client call mapping

| call | target | control | 판정 | operation/gap |
|---|---|---|---|---|
| `USR-FIELD-SESSION-GET` | `GET /api/field-session` | `-` | `MATCHED` | `GET /api/field-session` |
| `USR-FIELD-SESSION-POST` | `POST /api/field-session` | `-` | `MATCHED` | `POST /api/field-session` |
| `USR-FIELD-SESSION-DELETE` | `DELETE /api/field-session` | `-` | `MATCHED` | `DELETE /api/field-session` |
| `USR-FIELD-WALK-GET` | `GET /api/field-walk` | `-` | `MATCHED` | `GET /api/field-walk` |
| `USR-FIELD-WALK-POST` | `POST /api/field-walk` | `-` | `MATCHED` | `POST /api/field-walk` |
| `USR-DESTINATION-SEARCH` | `GET /api/navigation/destinations/search` | `-` | `MATCHED` | `GET /api/navigation/destinations/search` |
| `USR-WALKING-ROUTE` | `POST /api/navigation/walking` | `-` | `MATCHED` | `POST /api/navigation/walking` |
| `USR-REPORT-V2` | `POST /api/reports/v2` | `-` | `MATCHED` | `POST /api/reports/v2` |
| `USR-CONSENT-GET` | `GET /privacy/rights` | `integrated-consent` | `UNDECLARED_GAP` | `UNDECLARED_GAP` |
| `USR-CONSENT-PUT` | `PUT /privacy/rights` | `integrated-consent` | `UNDECLARED_GAP` | `UNDECLARED_GAP` |
| `USR-DELETION-GET` | `GET /privacy/rights` | `account-deletion` | `UNDECLARED_GAP` | `UNDECLARED_GAP` |
| `USR-DELETION-POST` | `POST /privacy/rights` | `account-deletion` | `UNDECLARED_GAP` | `UNDECLARED_GAP` |
| `ADM-LOGIN` | `POST /admin/security/sessions` | `-` | `MATCHED` | `POST /admin/security/sessions` |
| `ADM-STATE` | `GET /admin/security/state` | `-` | `MATCHED` | `GET /admin/security/state` |
| `ADM-SESSIONS` | `GET /admin/security/sessions` | `-` | `MATCHED` | `GET /admin/security/sessions` |
| `ADM-REVOKE` | `POST /admin/security/sessions/{session_id}/revoke` | `-` | `MATCHED` | `POST /admin/security/sessions/{session_id}/revoke` |
| `ADM-REAUTH` | `POST /admin/security/reauthenticate` | `-` | `MATCHED` | `POST /admin/security/reauthenticate` |
| `ADM-RECOVERY-START` | `POST /admin/security/recovery/start` | `-` | `MATCHED` | `POST /admin/security/recovery/start` |
| `ADM-RECOVERY-COMPLETE` | `POST /admin/security/recovery/complete` | `-` | `MATCHED` | `POST /admin/security/recovery/complete` |

User 12개, admin 7개 호출 모두 operation 또는 `UNDECLARED_GAP`에 연결됐다. 미분류 호출은 0이다. Admin source mapping은 PASS지만 실제 분리 앱/device/backend security integration은 W5다.

## Predecessor change 판정

| change | 분류 | surface | 후속 조치 |
|---|---|---|---|
| `W2-IFDATA-CHG-001` | `ADDITIVE` | runtime OpenAPI evidence | none; evidence-only |
| `W2-IFDATA-CHG-002` | `BREAKING` | administrator authentication profile | legacy clients cannot be treated as compatible; Android admin integration remains W5 |
| `W2-IFDATA-CHG-003` | `BEHAVIORAL` | FP035 original-activity upload | durable queue implementation W3 and formal network compatibility W4 |
| `W2-IFDATA-CHG-004` | `ADDITIVE` | Gateway privacy/control HTTP surface | declare all control variants in Gateway OpenAPI during W3 |
| `W2-IFDATA-CHG-005` | `BEHAVIORAL` | event/order semantics | stable W3 gap; do not call HTTP schema versions an event registry |

## Event payload/order registry

중앙 event payload/order registry와 async event bus registry는 `NOT_IMPLEMENTED`다. walking route, destination search, field-walk, account deletion, frame capture의 local `schema_version`, account deletion revision, admin audit sequence/hash는 존재하지만 이를 중앙 event registry로 승격하지 않는다. stable gap은 `W2-IFDATA-GAP-002`, target `W3`다.

## Exact DES disposition

| artifact | exactly one disposition | 근거 |
|---|---|---|
| `DLV-DES-09` | `ACCEPT_CURRENT_STATE_WITH_CLASSIFIED_W3_W4_W5_GAPS` | backend runtime/checked contract exact; Gateway privacy and cross-client formal/security integration remain classified |
| `DLV-DES-10` | `ACCEPT_SCHEMA_EVIDENCE_WITH_W3_EVENT_AND_GATEWAY_DECLARATION_GAPS` | runtime determinism proven; event registry and privacy-control declarations absent |
| `DLV-DES-11` | `ACCEPT_CURRENT_ERD_FACTS_WITH_W3_W4_DATA_INTEGRITY_GAPS` | logical relations are explicit; FK/live catalog gaps remain |
| `DLV-DES-12` | `ACCEPT_CURRENT_DICTIONARY_WITH_W3_W5_W4_CLOSURE_GAPS` | column/index/sensitivity facts present; ORM/live schema and security retention remain |
| `DLV-DES-13` | `ACCEPT_CURRENT_LIFECYCLE_WITH_W3_W5_W8_GAPS` | retention boundaries recorded; queue/security/legal/restore gaps remain |
| `DLV-DES-26` | `ACCEPT_MIGRATION_CHAIN_FACTS_WITH_W8_EXECUTION_GAP` | linear chain and risks recorded; migration/rollback/restore execution absent |

## Gap closure contract

| gap | 상태 | wave | closure condition |
|---|---|---|---|
| `W2-IFDATA-GAP-001` | `CLOSED_INTERNAL` | `W2` | two independent production-shape generations are byte-identical and equal checked-in contract with zero normalized differences |
| `W2-IFDATA-GAP-002` | `OPEN` | `W3` | Gateway OpenAPI declares page/consent/deletion control variants and an explicit event-registry presence or governed N/A decision; source/schema diff has zero unexplained items |
| `W2-IFDATA-GAP-003` | `OPEN` | `W3` | each operation has governed error/idempotency/server/deprecation disposition with executable schema or explicit N/A rationale |
| `W2-IFDATA-GAP-004` | `OPEN` | `W3` | FK/cascade/ORM implementation or compensating no-FK controls are selected, implemented and source-tested |
| `W2-IFDATA-GAP-005` | `OPEN` | `W5` | security/privacy authority approves retention, archive and erasure-exception matrix and verification evidence binds implementation |
| `W2-IFDATA-GAP-006` | `OPEN` | `W3` | approved product disposition is implemented and source tests prove durable limit/encryption/restart/resume behavior or explicitly remove queue claim |
| `W2-IFDATA-GAP-007` | `OPEN` | `W8` | controlled migration plus rollback/isolated restore drill produces signed receipts and measured lock/volume result |
| `W2-IFDATA-GAP-008` | `OPEN` | `W4` | isolated integration database reaches head and normalized catalog/ORM diff has zero unexplained mismatches |
| `W2-IFDATA-GAP-009` | `OPEN` | `W4` | formal contract integration executes all mapped user calls and classified error branches with actual network transport |
| `W2-IFDATA-GAP-010` | `OPEN` | `W5` | formal security contract integration proves login/session/revoke/reauth/recovery and high-risk nonce behavior on the separated admin app |

모든 gap은 `gap_id`, `owner_roles`, `target_wave`, `closure_condition`, `expected_evidence_paths`를 JSON에 가진다. 구현은 W3, formal/compat integration은 W4, security contract는 W5, migration/restore는 W8에 배정했다.

## 금지 주장

이 evidence는 runtime schema 결정성과 source mapping을 증명한다. formal 279, device/network, security integration, migration/restore, gate 또는 release PASS를 증명하지 않는다.
