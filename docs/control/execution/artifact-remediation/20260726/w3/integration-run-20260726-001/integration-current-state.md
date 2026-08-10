# W3 DEV21 내부 통합 current state

- 문서 ID: `WALKSAFE-W3-DEV21-INTEGRATION-CURRENT-STATE-20260726-001`
- 실행 결과: `PARTIAL_INTERNAL_INTEGRATION_PASS`
- DEV21 disposition: 정확히 1건, `INTERNAL_GAP` 유지
- 실행 방식: heavy process 1개씩 직렬 실행
- 기준 source: `evidence-20260726-002`, source content set `976ba4d9cec00fdc56891f06e8927c34d9686cbb460dc9b88f5ac678afba3bcf`

## 필수 명령 결과

| 구성요소 | 명령 | 결과 | 테스트 | 최대 RSS |
|---|---|---:|---:|---:|
| Gateway | `npm test` (`NODE_OPTIONS=--max-old-space-size=1024`) | PASS | 62/62 | 264668 KiB |
| Android user | `:app:testDebugUnitTest --offline --no-daemon --max-workers=1` | PASS | 728/728 | 638184 KiB |
| Android admin | `:adminapp:testDebugUnitTest --offline --no-daemon --max-workers=1` | PASS | 38/38 | 620664 KiB |

각 명령의 start/end, argv, cwd, tool, exit code, log SHA, resource peak, Node/JUnit count는 `receipts/`에 기록했다.

## 재사용 및 금지 판정

| 증거 | 판정 | 근거 |
|---|---|---|
| W2 runtime OpenAPI | `REUSED_PASS_INTERNAL_CONTRACT_EVIDENCE` | 2회 SHA `e66280b6cc4308c07c49ee183ce557613b531e2fc0554124c55bb472fe9b5d1b`, canonical과 byte-identical |
| W3 aux003 PostGIS | `REUSED_PASS_INTERNAL_EPHEMERAL_MIGRATION` | upgrade → downgrade -1 → re-upgrade → current 모두 exit 0 |
| FP047 backend tests | `REUSE_PROHIBITED_NOT_RUN` | FP047는 evidence002 backend subject SHA가 아닌 exact31 결속이며 현재 checker가 binding 불일치로 exit 1; 재시도 없음 |

## Coverage matrix

| 구성요소 | 내부 판정 | Cross-process | 실기기 | TMAP | Production |
|---|---|---|---|---|---|
| Android user | unit command PASS | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| Android admin | unit command PASS | NOT_RUN | NOT_RUN | N/A direct | NOT_RUN |
| Gateway | Node test PASS | NOT_RUN | NOT_RUN | NOT_RUN | NOT_RUN |
| Backend | NOT_RUN, reuse prohibited | NOT_RUN | N/A direct | NOT_RUN | NOT_RUN |
| PostGIS | ephemeral migration roundtrip PASS | app integration NOT_RUN | N/A | N/A | NOT_RUN |
| OpenAPI contract | deterministic byte-identical PASS | NOT_RUN | N/A | N/A | NOT_RUN |

## Open gaps

| ID | Owner | 목표 wave | 종료 조건 요약 |
|---|---|---|---|
| `W3-DEV21-GAP-001` | Backend owner | W4 | evidence002 backend subject에 직접 결속한 fresh backend suite와 raw receipt |
| `W3-DEV21-GAP-002` | Integration test owner | W4 | Gateway→Backend→PostGIS cross-process contract/database trace |
| `W3-DEV21-GAP-003` | Android device and field-test owner | W4 | 실제 user/admin device·network·TMAP 결과와 결함 재검증 |
| `W3-DEV21-GAP-004` | Release and operations owner | W8 | signing assessment·deployment·rollback·release generation 결속 |

## Claim boundary

`formal279=NOT_RUN`, `gate5=NOT_RUN/unwaived`, `release=NOT_ELIGIBLE`, `signing=NOT_ASSESSED`다. 실제 cross-process network, 실제 기기, TMAP, production 결과는 주장하지 않는다.
