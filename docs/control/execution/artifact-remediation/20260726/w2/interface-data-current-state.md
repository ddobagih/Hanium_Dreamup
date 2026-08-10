# W2 인터페이스·데이터 현행 successor

- 기록 ID: `WS-W2-INTERFACE-DATA-CURRENT-STATE-20260726-001`
- 기준일: `2026-07-26`
- exact 범위: `DLV-DES-09`, `DLV-DES-10`, `DLV-DES-11`, `DLV-DES-12`, `DLV-DES-13`, `DLV-DES-26`
- source: `source_commit=null`, dirty-worktree exact path/SHA 결속
- JSON content fingerprint: `7717f65df7ced174c9ccd242a128cc2b2585cf25991f7516e3d00809a18c2ee2`
- 기존 design 정본·승인·builder·checker를 수정하지 않는 비파괴 current-state successor

## 공통 경계

| 항목 | 판정 |
|---|---|
| 정책 | `PB-WALKSAFE-FEATURE-POLICY-1.0.1`, valid `COMMITTED` receipt 우선 |
| 문서 범위 | exact 6 `MATERIALIZED` |
| 구현 적합 | `PARTIAL`, 미구현·미확정은 `INTERNAL_GAP`/`NOT_IMPLEMENTED` |
| formal | 279개 전부 `NOT_RUN`, PASS 주장 없음 |
| device/network/deploy | `NOT_RUN` |
| migration/rollback/restore | `NOT_RUN` |
| gate/release | 5개 unwaived `NOT_RUN`, `NOT_ELIGIBLE` |

## DES-09·10 API·인터페이스

| 계약 | 현행 | 남은 경계 |
|---|---|---|
| Backend | OpenAPI `3.1.0`, API `0.1.0`, 28 paths / 30 operations / 38 schemas | runtime 설정 재생성·diff `NOT_RUN`, server 미선언 |
| Android Gateway | OpenAPI `3.1.0`, API `0.4.0`, 5 paths / 8 operations / 24 schemas | privacy/control·event schema 미포함, source diff `NOT_RUN` |
| Android user | session/walk/navigation/report/consent/deletion client | 실제 device/network `NOT_RUN` |
| Android admin | login/state/session/revoke/reauth/recovery client | 실제 device/backend `NOT_RUN` |

사용자 앱 `POST /api/reports/v2`는 Gateway가 Backend `POST /reports/v2`로 전달하고 navigation 두 경로도 같은 방식으로 연결한다. field-session/field-walk는 Gateway 로컬 통제다. Backend OpenAPI 보안 모양은 admin security 설정에 따라 달라지므로 static snapshot과 임의 runtime의 동일성을 주장하지 않는다.

Report client는 2xx 뒤 UUID/status/class/source/bbox/metadata/image path/confidence를 엄격 검사하고 connect/read timeout 8초/12초 및 status별 retry를 둔다. account deletion의 `Idempotency-Key`, field-walk request ID/lease conflict, admin action/method/path/nonce는 있으나 report create의 범용 idempotency와 통합 오류 envelope, server/deprecation/event registry는 없다.

## FP035 상태기계

| motion | network/preference | 판정 |
|---|---|---|
| `UNKNOWN` | any | `FAIL_CLOSED` |
| `WALKING` | any | `BLOCKED_WHILE_WALKING` |
| `STATIONARY` | Wi-Fi | `WIFI_ALLOWED` |
| `STATIONARY` | cellular + explicit allow | `APPROVED_CELLULAR_ALLOWED` |
| `STATIONARY` | cellular + Wi-Fi only | `QUEUED_UNTIL_WIFI`, 현재 전송 불가 |
| `STATIONARY` | offline/other | `FAIL_CLOSED` |

session·motion·consent·preference·transport를 synchronized admission에서 결합한다. unknown, tracking stop, session 전환, 이동 재감지 시 generation을 올리고 active/queued debug upload를 취소하며 HTTP 연결을 끊는다.

`QUEUED_UNTIL_WIFI`는 결정 이름뿐이다. metadata debug uploader의 손실 허용 memory queue와 frame debug best-effort는 durable product queue가 아니다. durable encrypted queue, restart recovery, chunk upload/resume, durable retry ledger는 `NOT_IMPLEMENTED`다.

## DES-11·12 ERD·데이터 사전

migration table은 12개, ORM mapping은 11개다. `actor_rate_limit_events`는 migration/raw SQL 전용이다. 선언된 DB FK/cascade는 0개다. report/status/read/image 및 admin/session/recovery/audit는 논리관계만 있고 삭제 정합은 service·retention 도구가 책임진다. live catalog와 ORM/head diff는 `NOT_RUN`이다. 모든 column/type/null, PK/unique/check/index는 JSON에 기계 판독형으로 기록했다.

| table | ORM | 민감도 | 보존·삭제 |
|---|---|---|---|
| `reports` | `Report` | HIGH: image reference, exact GPS, capture time and JSON metadata | real row/image 180 days; fake/demo up to 30 days; guarded explicit row/file deletion, no cascade |
| `report_export_audits` | `ReportExportAudit` | HIGH: actor, filter, location precision and row digest | export file 7 days; append-only audit-row retention/legal basis INTERNAL_GAP |
| `report_status_audits` | `ReportStatusAudit` | HIGH: report linkage, actor and free text | append-only; retention and deleted-report reconciliation INTERNAL_GAP |
| `report_read_audits` | `ReportReadAudit` | HIGH: actor/resource access trail | append-only; retention/legal basis INTERNAL_GAP |
| `actor_rate_limit_events` | `NONE` | PSEUDONYMOUS_HIGH: actor digest and timing | no bound retention/delete rule, INTERNAL_GAP |
| `admin_security_controls` | `AdminSecurityControl` | CRITICAL: administrator credential verifier/state | rotation, retention and deletion rules INTERNAL_GAP |
| `admin_security_sessions` | `AdminSecuritySession` | CRITICAL: device session digest/activity | revocation exists; expired/revoked-row purge INTERNAL_GAP |
| `admin_security_reconfirmations` | `AdminSecurityReconfirmation` | CRITICAL: action-bound proof | one-time consume exists; historical-row retention INTERNAL_GAP |
| `admin_security_recovery_codes` | `AdminSecurityRecoveryCode` | CRITICAL: recovery verifier | one-time use exists; used-code retention/disposal INTERNAL_GAP |
| `admin_security_recovery_transactions` | `AdminSecurityRecoveryTransaction` | CRITICAL: recovery transaction/device | expiry/completion exists; row retention/orphan handling INTERNAL_GAP |
| `admin_security_auth_attempts` | `AdminSecurityAuthAttempt` | CRITICAL: auth pseudonyms/timing | retention/delete rule INTERNAL_GAP |
| `admin_security_audits` | `AdminSecurityAudit` | CRITICAL: immutable security event chain | statement-level update/delete/truncate rejection; legal retention/archive/erasure exception INTERNAL_GAP |

## DES-13 생명주기

- real report row/image 180일, fake/demo 최대 30일. guarded apply는 backup/restore pair, actor, lock, quarantine, manifest를 요구하지만 production apply는 `NOT_RUN`이다.
- debug/test capture 최대 7일 권장, export file 7일이다. 실제 운영 삭제는 `NOT_RUN`이다.
- export/read/status audit, admin credential/session/recovery/audit, actor rate-limit event의 보존·법적 근거·erasure 예외는 `INTERNAL_GAP`이다.
- backup/log 잔존 procedure는 결속했지만 실제 backup/restore 및 삭제 뒤 잔존 검증은 `NOT_RUN`이다.

## DES-26 migration

Alembic은 12개 선형 revision이며 head는 `202607260001`이다. `upgrade head`는 적용 revision 재실행을 막지만 raw DDL 수동 재실행은 지원하지 않는다. 모든 downgrade 함수는 존재하나 운영 downgrade는 금지되고 여러 단계가 table/감사/보안 자료를 제거한다.

| revision | predecessor | 효과 | 위험 |
|---|---|---|---|
| `202605120001` | `ROOT` | PostGIS, reports and base indexes | `DESTRUCTIVE_DOWNGRADE` |
| `202607110001` | `202605120001` | report geography/composite/JSONB indexes | `INDEX_LOCK_IO_NOT_MEASURED` |
| `202607110002` | `202607110001` | report checks NOT VALID then VALIDATE | `VALIDATION_SCAN_NOT_MEASURED` |
| `202607110003` | `202607110002` | export/status audits and stricter checks | `AUDIT_DATA_LOSS` |
| `202607110004` | `202607110003` | append-only function/triggers | `AUDIT_GUARD_REMOVAL` |
| `202607130001` | `202607110004` | truncate guards | `AUDIT_GUARD_REMOVAL` |
| `202607130002` | `202607130001` | export requested-id/digest backfill and uniqueness | `TRIGGER_TEMPORARILY_DISABLED_FOR_BACKFILL` |
| `202607130003` | `202607130002` | append-only read audit | `AUDIT_DATA_LOSS` |
| `202607130004` | `202607130003` | actor rate-limit events | `ORM_RETENTION_GAP` |
| `202607160001` | `202607130004` | duplicate-check read-audit enum | `DOWNGRADE_MAY_CONFLICT_WITH_NEW_VALUES` |
| `202607220001` | `202607160001` | admin credential/session/recovery/auth/audit tables | `SECURITY_AUDIT_DATA_LOSS` |
| `202607260001` | `202607220001` | action reconfirmation and audit-chain backfill | `ACCESS_EXCLUSIVE_FULL_BACKFILL_UNMEASURED` |

특히 최신 revision은 `admin_security_audits`에 `ACCESS EXCLUSIVE` lock을 잡고 전체 chain을 backfill한다. 영향 측정, mixed app/schema 호환 window, deploy, migration, rollback, signed backup과 isolated restore drill은 모두 `NOT_RUN`이다.

## 남은 gap

| ID | 상태 | 내용 | 해소 조건 |
|---|---|---|---|
| `W2-IFDATA-GAP-001` | `INTERNAL_GAP` | 정적 OpenAPI를 정확한 runtime 설정으로 재생성·semantic diff하지 않음 | `BEFORE_DESIGN_BASELINE_APPROVAL` |
| `W2-IFDATA-GAP-002` | `INTERNAL_GAP` | Gateway privacy/control surface와 event payload/order registry가 실행 schema에 없음 | `BEFORE_INTERFACE_CONTRACT_APPROVAL` |
| `W2-IFDATA-GAP-003` | `INTERNAL_GAP` | 통합 오류 envelope, 완전한 idempotency, server, deprecation 계약 미구현 | `BEFORE_INTERFACE_CONTRACT_APPROVAL` |
| `W2-IFDATA-GAP-004` | `INTERNAL_GAP` | 교차 entity는 전부 논리관계이고 DB FK/cascade 0개, actor rate table ORM mapping 없음 | `BEFORE_DATA_DESIGN_APPROVAL` |
| `W2-IFDATA-GAP-005` | `INTERNAL_GAP` | admin/audit/rate-limit audit-row 보존·법적근거·erasure 예외 미확정 | `BEFORE_DATA_LIFECYCLE_APPROVAL` |
| `W2-IFDATA-GAP-006` | `NOT_IMPLEMENTED` | FP035 durable encrypted queue, restart recovery, chunk upload/resume ledger 없음 | `BEFORE_GATES_PHONE_QUEUE_AND_RAW_COLLECTION` |
| `W2-IFDATA-GAP-007` | `NOT_RUN` | deploy/migration/rollback/restore/retention drill과 lock·volume 측정 미실행 | `BEFORE_RELEASE_ELIGIBILITY_REVIEW` |
| `W2-IFDATA-GAP-008` | `NOT_RUN` | live PostgreSQL catalog와 ORM-to-head 비교 미실행 | `BEFORE_DATA_DESIGN_APPROVAL` |

## 금지 주장

이 successor로 승인·기준선화, runtime OpenAPI 동일, DB FK/cascade, durable queue/chunk resume, production migration·삭제·backup·rollback·restore, formal279, 실기기, 배포, gate 또는 release PASS를 주장할 수 없다.

## DES09·10 runtime 계약 보완 증거

- evidence: `docs/control/execution/artifact-remediation/20260726/w2/interface-contract-evidence.json` fingerprint `c9b87548a8dd79fcb640f4459fa08e83bddfc7f0e77a955e3f04390d759bc438`
- runtime raw: `docs/control/execution/artifact-remediation/20260726/w2/runtime-openapi-current.json` SHA `e66280b6cc4308c07c49ee183ce557613b531e2fc0554124c55bb472fe9b5d1b`
- 독립 생성 2회 hash 일치, checked-in byte equality `PASS`
- backend normalized missing/extra/mismatch `0/0/0`, unexplained `0`
- Gateway source는 선언 8개가 모두 MATCHED이고 privacy/control 6개는 `W2-IFDATA-GAP-002 / UNDECLARED_GAP`으로 분류
- Android user 12개·admin 7개 호출은 operation 또는 explicit gap에 전부 연결, 미분류 0
- 중앙 event payload/order registry는 `NOT_IMPLEMENTED`, stable W3 gap
- DES09..13/26 각각 exactly one disposition과 모든 gap closure 계약은 evidence JSON에 결속
- `DLV-DES-09` exactly-one disposition `gap_ids`: `W2-IFDATA-GAP-001`, `W2-IFDATA-GAP-003`, `W2-IFDATA-GAP-009`, `W2-IFDATA-GAP-010`; `GAP-009/010` gap records는 `DLV-DES-09` 역참조 포함
