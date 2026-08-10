# W3 DEV21 내부 통합 current state run002

- 결과: `INTERNAL_INTEGRATION_FAILED_POSTGRES_SCHEMA_DRIFT`
- DEV21 disposition: 정확히 1건, `INTERNAL_GAP` 유지, `OK` 권고 안 함
- 실행 방식: 단일 heavy process, 실패 재시도 0회
- predecessor: `integration-run-20260726-001` 불변

## 새 backend 직접 실행

| 범위 | 결과 | 수치 | 최대 RSS |
|---|---|---:|---:|
| DB-free admin security | PASS | 32 passed, PostgreSQL node 1 deselected | 139348 KiB |
| Test DB preflight | PASS | DB명 `walksafe_dev21_test_002` | 60288 KiB |
| Alembic upgrade head | PASS | exit 0 | 92552 KiB |
| Exact PostgreSQL node | FAIL | 0 passed, 1 failed | 122728 KiB |
| Container cleanup | PASS | removed | 28700 KiB |
| Secret removal | PASS | truncate, remove, absent assertion | 7732 KiB |

## Blocking finding

`W3-DEV21-FINDING-001`: migrated PostGIS에는 `admin_security_audits(sequence)` UniqueConstraint가 있으나 `Base.metadata`와 일치하지 않아 `compare_metadata`가 `remove_constraint` 1건을 반환했다. 이 run에서는 source 수정이나 재시도를 하지 않았다.

## 결합 coverage

| 구성요소 | 내부 결과 | Cross-process | 실기기/TMAP | Production |
|---|---|---|---|---|
| Android user | run001 728/728 PASS 재사용 | NOT_RUN | NOT_RUN | NOT_RUN |
| Android admin | run001 38/38 PASS 재사용 | NOT_RUN | NOT_RUN | NOT_RUN |
| Gateway | run001 62/62 PASS 재사용 | NOT_RUN | NOT_RUN | NOT_RUN |
| Backend | DB-free 32 PASS, PostgreSQL 1 FAIL | NOT_RUN | TMAP NOT_RUN | NOT_RUN |
| PostGIS | preflight/migration PASS, schema parity FAIL | app integration NOT_RUN | N/A | NOT_RUN |
| OpenAPI | W2 deterministic byte-identical PASS 재사용 | NOT_RUN | N/A | NOT_RUN |

## Gap 상태

| ID | 상태 | Owner | Wave | 종료 조건 요약 |
|---|---|---|---|---|
| `W3-DEV21-GAP-001` | CLOSED_INTERNAL | Backend owner | W3 | 현재 source DB-free 32 PASS receipt |
| `W3-DEV21-GAP-002` | OPEN | Integration test owner | W4 | Gateway→Backend→PostGIS cross-process trace |
| `W3-DEV21-GAP-003` | OPEN | Device/field owner | W4 | 실제 device·network·TMAP 실행 |
| `W3-DEV21-GAP-004` | OPEN | Release/ops owner | W8 | signing·deploy·rollback·release generation |
| `W3-DEV21-GAP-005` | OPEN | Backend schema owner | W4 | schema canonical화 후 fresh DB empty diff와 exact node PASS |
| `W3-DEV21-GAP-006` | OPEN | Backend test-infra owner | W4 | `admin_security_reconfirmations` cleanup과 연속 실행 residue 0 |

## Claim boundary

`formal279=NOT_RUN`, `gate5=NOT_RUN/unwaived`, `release=NOT_ELIGIBLE`, `signing=NOT_ASSESSED`다. Android↔Gateway↔Backend 실제 cross-process, 실기기, TMAP, production 결과는 주장하지 않는다.
