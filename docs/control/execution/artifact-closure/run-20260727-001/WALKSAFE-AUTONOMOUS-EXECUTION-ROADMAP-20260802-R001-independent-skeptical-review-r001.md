# WalkSafe 자율 실행 로드맵 20260802 R001 독립 공격검수 R001

- document_id: `WS-WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R001-INDEPENDENT-SKEPTICAL-REVIEW-R001`
- review_date: `2026-08-02`
- review_subject_type: `INTERNAL_EXECUTION_ROADMAP_REVIEW`
- review_authority: `NONE / REVIEW_ONLY`
- verdict: `REVISION_REQUIRED`
- findings: `BLOCKING=2 / MAJOR=3 / MINOR=0`

## 1. Exact target과 검수 경계

| 항목 | 값 |
|---|---|
| target | `WALKSAFE-AUTONOMOUS-EXECUTION-ROADMAP-20260802-R001.md` |
| SHA-256 | `73d3bcc1e47a896fbc7e5156639a85a7f44ba29e3ec90d24d25d0a80f333d94b` |
| bytes / lines | `16,268 / 334` |
| target mutation | `0` |

본 검수는 실패·우회·self-authorization·stale pointer·full gate·역사/현행
분리·artifact/formal 과장·single-leaf 충돌을 공격 관점에서 확인했다. 판정은
roadmap 품질 검토일 뿐 canonical 전환, 제품 변경 또는 외부 권한을 만들지 않는다.

## 2. BLOCKING findings

### SK-BLOCKING-001 — add-only 후보 권한이 canonical 전환 권한으로 확대된다

§0 24~26행은 사용자 권한을 저장소 내부 구현과 `add-only 통제 후보`까지로
제한한다. 그러나 WP-002 195~204행은 내부 agent 검수만 통과하면 versioned
control을 `fenced apply`하여 checkpoint까지 전환한다. 승인 주체·근거와 허용
delta가 없고, 104행의 root writer 지정은 write 주체일 뿐 승인 권한이 아니다.

Required correction: WP-002를 successor 후보 준비와 canonical 활성화로 분리한다.
후보는 `PREPARED_NOT_ACTIVATED`로 끝내고, exact successor manifest/hash와 허용
delta에 결속된 기존 권한 근거 또는 별도 사용자 승인이 확인되기 전에는 active
pointer/checkpoint를 바꾸지 않는다.

### SK-BLOCKING-002 — successor 이후에도 과거 `19/19` full gate로 시작할 수 있다

WP-002 199~204행은 successor continuation/Goal/full-gate 계약을 새로 만든다.
반면 WP-003 225~233행은 시작 수용조건을 고정 `full gate 19/19`로 두고, §5
322행은 quick check에만 `v2.4 또는 활성 successor`를 명시한다. 따라서 active
successor 계약이 달라져도 과거 v2.4의 19개 출력으로 literal 조건을 만족시키는
stale-gate 우회가 남는다. S5 235~246행에도 각 후속 leaf 시작 직전의 active
full-contract 재결속이 명시되지 않았다.

Required correction: 고정 수를 제거하고 각 `GOAL_STARTED`/resume 직전에 active
package ID, full-contract SHA-256, registry-derived exact 명령 집합, source/config/
test/toolchain snapshot과 event-scoped 출력 hash가 모두 일치해야 한다고 규정한다.
입력 byte 또는 active contract가 바뀌면 이전 gate는 무효다.

## 3. MAJOR findings

### SK-MAJOR-001 — MINOR finding 발생 시 S1이 영구 교착된다

16~17행과 129~136행은 두 검수 모두 `0/0/0`이어야 WP-001을 허용하지만,
131~132행은 BLOCKING/MAJOR가 있을 때만 R002를 만들도록 한다. MINOR만 있으면
실행도 successor 작성도 허용되지 않는다.

Required correction: any-finding이면 add-only successor를 만들도록 하거나,
MINOR를 수용할 명시적 non-blocking disposition·owner·기한을 정의한다.

### SK-MAJOR-002 — roadmap review와 실제 시작 bytes 사이 CAS가 없다

§1은 대규모 dirty snapshot과 backup을 기록하고 S1은 roadmap SHA만 검수한다.
공통 루프 91행의 `current bytes와 정본 pin`은 §1 snapshot과 같아야 한다는 조건이
아니다. review 뒤 WP-001 전에 source/test bytes가 바뀌어도 검수 결과를 그대로
사용할 수 있다.

Required correction: WP-001 시작 시 base HEAD, relevant tracked/untracked path set,
content-set hash와 roadmap/review hash를 하나의 precondition으로 재확인한다.
불일치하면 write 0으로 새 snapshot·새 review 또는 명시적 영향판정을 요구한다.

### SK-MAJOR-003 — safety/security critical failure가 전역 격리되지 않는다

112행은 막힌 branch를 우회해 다른 internal-ready branch를 계속하고, 113~114행은
safety/security critical failure에도 `해당 write`만 중단한다. 공유 candidate나
control snapshot의 신뢰가 깨진 경우에도 다른 branch apply가 계속될 수 있다.

Required correction: 정본 hash 손상, 정책 충돌, 안전·보안 critical failure는
영향 candidate/control snapshot 전체를 fail-closed quarantine하고 독립 판정 전
모든 apply를 금지한다. 단순 외부 blocker만 branch-local로 유지한다.

## 4. 확인된 방어와 최종 판정

- FP-048 stale pointer를 바로 시작하지 않고 live frontier 재계산 뒤 선택한다.
- historical/current Gateway와 artifact epoch를 분리하고 과거 receipt를 덮지 않는다.
- artifact/formal/device/Gate/release credit을 0으로 유지해 증거 과장은 피한다.
- canonical `IN_PROGRESS` leaf 하나 원칙과 WP-001의 비제품 후보 예외는 구분된다.

그러나 두 BLOCKING finding이 실행 권한과 full gate를 우회할 수 있으므로 R001은
실행 승인 기준을 충족하지 않는다. 원본을 고치지 말고 위 correction을 반영한
add-only R002를 동일 방식으로 freeze·독립 검수해야 한다.
