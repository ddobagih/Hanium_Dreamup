# W2 운영 계약 evidence

- 문서 ID: `WS-W2-OPERATIONS-CONTRACT-EVIDENCE-20260726-001`
- exact artifact/disposition: 5/5
- source commit: `null`; exact path/SHA dirty-worktree snapshot

## 판정

- Admin telemetry: `11/11 PASS`
- Gateway telemetry: `2/2 PASS`
- Backup prune 35-day regression: `5/5 PASS`
- Collector/dashboard/production monitoring: `NOT_RUN`, W9
- Provisional performance budgets: `NOT_APPROVED/NOT_RUN`, W4
- Provisional recovery tiers and actual restore: `NOT_APPROVED/NOT_RUN`, W8
- Formal 279 and five unwaived gates: `NOT_RUN`; release `NOT_ELIGIBLE`

## 8-event allowlist contract

| event | surface | severity | outcome |
|---|---|---|---|
| `walksafe.admin.auth.succeeded` | `android_adminapp` | `INFO` | `succeeded` |
| `walksafe.admin.auth.failed` | `android_adminapp` | `WARN` | `failed` |
| `walksafe.admin.recovery.start.succeeded` | `android_adminapp` | `INFO` | `succeeded` |
| `walksafe.admin.recovery.start.failed` | `android_adminapp` | `WARN` | `failed` |
| `walksafe.admin.recovery.complete.succeeded` | `android_adminapp` | `INFO` | `succeeded` |
| `walksafe.admin.recovery.complete.failed` | `android_adminapp` | `WARN` | `failed` |
| `walksafe.gateway.proxy.failed` | `android_gateway` | `ERROR_FOR_5XX_WARN_FOR_409` | `FAILED_ENCODED_BY_EVENT_NAME_NO_OUTCOME_FIELD` |
| `walksafe.gateway.privacy.failed` | `android_gateway` | `ERROR_FOR_5XX` | `FAILED_ENCODED_BY_EVENT_NAME_NO_OUTCOME_FIELD` |

비밀·token·password·TOTP·복구코드·raw PII·header/query/body·사용자 correlation ID를 기록하지 않는다. Collector retention은 아직 결정·구현되지 않았다.

## approved versus provisional

승인값은 primary/backup `300/300 GiB`, server `70/85/95/100%`, storage `30,000 KRW/month`, backup retention `35 days`뿐이다. PB 14개와 T0/T1/T2는 모두 잠정 제안이며 실제 측정·복원 전 승인 주장이 금지된다.

## gaps

| `DES22-GAP-01` | `DLV-DES-22` | `INTERNAL_GAP` | `W3` | 기술책임자, QA책임자 | One versioned cross-module error catalogue and automated propagation proof passes internal integration tests. |
| `DES22-GAP-02` | `DLV-DES-22` | `INTERNAL_GAP` | `W3` | 기술책임자, 운영책임자 | Every external dependency has bounded retry/circuit ownership and internal failure tests. |
| `DES23-GAP-01` | `DLV-DES-23` | `INTERNAL_GAP` | `W9` | 운영책임자, 기술책임자 | Admin and gateway events reach an approved persistent collector with configured retention. |
| `DES23-GAP-02` | `DLV-DES-23` | `INTERNAL_GAP` | `W9` | 운영책임자, 보안·개인정보책임자 | Dashboard, alert thresholds, delivery route and on-call acknowledgement are verified in a production-like environment. |
| `DES23-GAP-03` | `DLV-DES-23` | `INTERNAL_GAP` | `W5` | 보안·개인정보책임자, 운영책임자 | Security review verifies all telemetry producers/collectors preserve the forbidden-data and redaction contract. |
| `DES24-GAP-01` | `DLV-DES-24` | `INTERNAL_GAP` | `W4` | 기술책임자, QA책임자 | All PB latency, throughput, CPU and memory budgets are measured on the immutable declared environments and separately approved or revised. |
| `DES24-GAP-02` | `DLV-DES-24` | `INTERNAL_GAP` | `W4` | Android책임자, QA책임자 | Battery, thermal, transient-storage and phone-queue budgets pass supported-device measurement and the phone queue gate closes. |
| `DES24-GAP-03` | `DLV-DES-24` | `INTERNAL_GAP` | `W4` | 운영책임자, 기술책임자 | Staging shape, capacity state, API saturation and storage/full-cloud cost are measured; separate full-cloud approval is recorded. |
| `DES25-GAP-01` | `DLV-DES-25` | `INTERNAL_GAP` | `W8` | 운영책임자, 제품책임자, QA책임자 | T0/T1/T2 provisional frequency, RPO and RTO are measured in isolated drills and explicitly approved or revised. |
| `DES25-GAP-02` | `DLV-DES-25` | `INTERNAL_GAP` | `W8` | 운영책임자, 보안·개인정보책임자 | A signed bundle containing DB/object/model-config/deletion ledger/key-version is restored with the nine-step contract. |
| `DES25-GAP-03` | `DLV-DES-25` | `INTERNAL_GAP` | `W8` | 운영책임자, 프로젝트책임자 | Identity-separated alternate environment capacity and traffic-transition authority are approved and drilled. |
| `DES25-GAP-04` | `DLV-DES-25` | `CLOSED_INTERNAL` | `W2` | 운영책임자, 기술책임자 | CLI default equals 35 days and strict boundary/default/dry-run/high-risk tests pass. |
| `DES27-GAP-01` | `DLV-DES-27` | `INTERNAL_GAP` | `W3` | 제품책임자, 운영책임자, 보안·개인정보책임자 | TMAP/GCS supplier limits and every dependency retry/backoff/circuit rule are implemented without unsafe substitution. |
| `DES27-GAP-02` | `DLV-DES-27` | `INTERNAL_GAP` | `W5` | 기술책임자, QA책임자, 접근성·안전책임자 | Cross-boundary admin/gateway/backend outage messages and telemetry pass security and accessibility review. |
| `DES27-GAP-03` | `DLV-DES-27` | `INTERNAL_GAP` | `W3` | QA책임자, 운영책임자 | Offline, timeout, quota and re-synchronization internal fault scenarios pass while external/device execution remains separately gated. |

## 무결성

- bound current-state SHA-256: `113ca25f769ecf932955c76edd47a4d8d5d23ccb064825f72f84de2be26ccb27`
- source binding: 42
- source path/SHA set: `16b9acf55e33f5c388f24e3350bcdaec538c881da866657c6acaceb1ac83fe76`
- JSON content fingerprint: `abec1f132a3b111e1ed986beea4a8bab9675640806532db2ec629e33a57ac68a`
- canonicalization: UTF-8, `ensure_ascii=false`, sorted compact JSON, fingerprint value `null`, trailing LF, SHA-256
