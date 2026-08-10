# W2 운영·관측·성능·복구 current-state successor

- 문서 ID: `WS-ARTIFACT-REMEDIATION-W2-OPERATIONS-CURRENT-STATE-20260726-001` / 버전 `1.1.0`
- 범위: exact 5 (`DLV-DES-22, DLV-DES-23, DLV-DES-24, DLV-DES-25, DLV-DES-27`)
- source: dirty-worktree exact path/SHA, `source_commit=null`
- 기존 정본·승인·기준선을 바꾸지 않는 successor다.

## exact disposition

| artifact | disposition |
|---|---|
| `DLV-DES-22` | `REMEDIATE_IMPLEMENTATION_CONTRACT_W3` |
| `DLV-DES-23` | `PRODUCERS_IMPLEMENTED_MONITORING_DEFERRED_W9` |
| `DLV-DES-24` | `PROVISIONAL_BUDGETS_REQUIRE_MEASUREMENT_W4` |
| `DLV-DES-25` | `PRUNE_FIXED_PROVISIONAL_RECOVERY_REQUIRES_DRILL_W8` |
| `DLV-DES-27` | `REMEDIATE_DEPENDENCY_CONTRACT_W3_AND_SECURITY_W5` |


## DES-23 versioned telemetry contract

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

- Admin allowlist: `schema_version,event_name,severity,correlation_id,outcome,failure_code`.
- Gateway allowlist: `schema_version,event_name,severity,correlation_id,route,method,http_status,failure_code`.
- correlation은 producer가 UUID v4로 새로 만들며 사용자/요청 `x-correlation-id`를 받거나 전달하지 않는다.
- secret, token, password, TOTP, recovery code, device identity, header/query/body, raw location·PII, 원 예외 메시지는 금지한다.
- sampling은 없다. covered event는 모두 기록한다. Gateway는 정의된 실패 상태만 event다.
- Admin sink는 Android Log, Gateway production sink는 stderr JSON이며 sink 실패는 제품 응답을 바꾸지 않는다.
- WalkSafe persistent collector·보존기간·dashboard·alert rule/delivery·production monitoring은 `NOT_IMPLEMENTED/NOT_RUN`, W9 gap이다. Alert 책임은 운영책임자, 보안 escalation은 보안·개인정보책임자다.

## DES-24 승인값

| ID | 값 | 단위 | 상태 |
|---|---:|---|---|
| `AP-STORAGE-PRIMARY` | 300 | GiB | `APPROVED_POLICY_VALUE` |
| `AP-STORAGE-BACKUP` | 300 | GiB | `APPROVED_POLICY_VALUE` |
| `AP-SERVER-CAPACITY-THRESHOLDS` | [70, 85, 95, 100] | percent | `APPROVED_POLICY_VALUE` |
| `AP-STORAGE-COST` | 30000 | KRW/month | `APPROVED_POLICY_VALUE` |
| `AP-BACKUP-RETENTION` | 35 | days | `APPROVED_POLICY_VALUE` |

위 5개만 승인 정책값이다. 아래 값은 모두 별도 승인 전 제안값이다.

## DES-24 provisional engineering budgets

| ID | metric | 상태 | numeric thresholds | owner | wave |
|---|---|---|---|---|---|
| `PB-C2F-01` | camera-to-feedback latency | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"p50_max": 900, "p95_max": 1500, "p99_max": 2000, "absolute_max": 2500} | Android책임자, QA책임자 | `W4` |
| `PB-ROUTE-01` | route acquisition latency | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"p50_max": 1500, "p95_max": 3000, "p99_max": 4500, "absolute_max": 8000} | Backend책임자, QA책임자 | `W4` |
| `PB-REPORT-01` | report upload to validated receipt latency | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"p50_max": 2500, "p95_max": 6000, "p99_max": 10000, "absolute_max": 12000} | Android책임자, Gateway책임자, QA책임자 | `W4` |
| `PB-CPU-01` | Android app CPU | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"average_max": 80, "p95_max": 120, "above_150_contiguous_seconds_max": 10} | Android책임자, QA책임자 | `W4` |
| `PB-MEM-01` | Android app PSS | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"p95_max": 512, "absolute_max": 640, "oom_or_lmk_max_count": 0} | Android책임자, QA책임자 | `W4` |
| `PB-BAT-01` | battery consumption | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"consumption_max": 12, "abnormal_termination_max_count": 0} | QA책임자, Android책임자 | `W4` |
| `PB-THERM-01` | thermal status and degradation response | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"critical_entry_max_count": 0, "severe_total_seconds_max": 60, "degradation_response_seconds_max": 5} | Android책임자, QA책임자 | `W4` |
| `PB-STORAGE-01` | controlled transient storage and remaining device space | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"controlled_transient_gib_max": 1, "free_space_gib_min": 2, "free_space_percent_min": 10} | Android책임자, QA책임자 | `W4` |
| `PB-PHONE-QUEUE-01` | encrypted Android report/raw queue | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"capacity_mib": 256, "report_count_max": 20, "single_report_mib_max": 8, "optional_degrade_at_percent": 80, "new_optional_reject_at_percent": 100} | Android책임자, 데이터책임자, QA책임자 | `W4` |
| `PB-API-01` | general non-external API service level | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"sustained_rps_min": 20, "latency_p95_ms_max": 750, "latency_p99_ms_max": 1500, "http_5xx_percent_max_exclusive": 1} | Backend책임자, QA책임자, 운영책임자 | `W4` |
| `PB-API-NAV-01` | navigation stub throughput | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"stub_aggregate_rps_min": 2} | Backend책임자, QA책임자 | `W4` |
| `PB-API-REPORT-01` | serialized report throughput | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"sustained_uploads_per_minute_min": 6, "serialized_busy_error_max_count": 0, "allowed_in_flight": 1} | Gateway책임자, QA책임자 | `W4` |
| `PB-STAGING-SHAPE-01` | provisional staging resource shape | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"gateway_vcpu": 1, "gateway_memory_gib": 1, "backend_vcpu": 2, "backend_memory_gib": 4, "postgres_vcpu": 2, "postgres_memory_gib": 4, "postgres_storage_gib": 20, "authenticated_sessions": 25} | 기술책임자, 운영책임자, QA책임자 | `W4` |
| `PB-CLOUD-COST-01` | full-cloud monthly cost control | `PROVISIONAL_ENGINEERING_BUDGET` / `NOT_APPROVED` / `NOT_RUN` | {"provisional_alert": 80000, "provisional_stop": 100000} | 운영책임자, 프로젝트책임자 | `W4` |

각 budget의 환경·측정법은 JSON에 non-null로 결속했다. 실제 기기·부하·비용 측정은 W4 `NOT_RUN`이다. 저장 전용 승인값 월 30,000원과 full-cloud 잠정 경보 80,000원/중지선 100,000원은 서로 다른 범위이며 후자는 별도 승인이 필요하다.

## DES-25 backup/recovery

- prune CLI 기본은 정책과 같은 35일, minimum copies 3, 기본 dry-run이다. 회귀 `5/5 PASS`지만 자동 schedule·실제 prune은 미구현/미실행이다.
- bundle은 단일 ID, quiesce, DB/object/model-config/deletion ledger/key version, OpenPGP, digest, signature, signed receipt를 요구한다.

| tier | frequency | provisional RPO | provisional RTO | 상태 | wave |
|---|---|---:|---:|---|---|
| `T0_CONTROL` | change-event bundle plus daily presence/signature verification | 1h | 2h | `NOT_APPROVED` / `NOT_RUN` | `W8` |
| `T1_DB` | daily full plus immediately before destructive action | 24h | 4h | `NOT_APPROVED` / `NOT_RUN` | `W8` |
| `T2_OBJECT` | daily change bundle plus weekly full plus immediately before destructive action | 24h | 12h | `NOT_APPROVED` / `NOT_RUN` | `W8` |

격리 복원은 9단계이며 actual restore/RPO/RTO는 `NOT_RUN/null`이다. T0/T1/T2는 모두 `NOT_APPROVED`다.

## 내부 테스트 근거

| test | cases | passed | status |
|---|---:|---:|---|
| `W2-OPS-TEST-ADMIN-TELEMETRY` | 11 | 11 | `PASS` |
| `W2-OPS-TEST-GATEWAY-TELEMETRY` | 2 | 2 | `PASS` |
| `W2-OPS-TEST-BACKUP-PRUNE` | 5 | 5 | `PASS` |

이는 producer/prune 내부 근거다. 정식 279건, 실제 기기·부하·복원·장애주입·failover·deploy는 `NOT_RUN`, release는 `NOT_ELIGIBLE`이다.

## stable gap register

| gap | artifact | status | target | owner | closure |
|---|---|---|---|---|---|
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

- source binding: 41
- source path/SHA set: `d89c3a9fde07b80d14729137110cc33fe9b71c8b085d8f1a27b33b01e541b35c`
- JSON content fingerprint: `445dc54d1113b13c33718df1077b10911f3143c30bd53017d9a8bb8f76d35f81`
- fingerprint canonicalization: UTF-8, `ensure_ascii=false`, sorted compact JSON, fingerprint value `null`, trailing LF, SHA-256
