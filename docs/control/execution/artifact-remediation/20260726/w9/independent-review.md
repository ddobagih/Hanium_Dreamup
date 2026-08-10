# W9 closure·operations·WalkSafe 독립 검토

> 검토일: `2026-07-27`  
> 검토 대상: W9 exact 15 current-state, common source 2개, CLS/OPS와 WS 생성 파이프라인  
> 판정: `NO_GO`  
> finding: `BLOCKING 0 / MAJOR 2 / MINOR 1`

## Findings

### MAJOR-W9-001: external 7의 실행 계약이 실제 산출물에 투영되지 않았다

W9 current-state overlay에는 `DLV-CLS-08`, `DLV-CLS-10`, `DLV-CLS-14`, `DLV-CLS-15`, `DLV-CLS-16`, `DLV-OPS-17`, `DLV-OPS-19` 각각의 역할, 입력, activation condition, action, evidence schema와 completion test가 있다. 하지만 이 강화 계약은 해당 generated6 문서, CLS/OPS builder의 artifact contract, targeted test에 투영되지 않았다.

근거:

- overlay 계약: `closure-operations-walksafe-current-state.md:89`부터 external 7의 role/input/action/evidence/completion을 기술한다.
- generated 문서에는 catalog 필수 내용, lifecycle record field, 현재 `실제 ID []`와 `NOT_RUN` 경계는 있지만 overlay의 전체 실행 계약은 없다.
- builder의 기본 W9 contract 생성은 `build_walksafe_formal_rel_ops_cls_20260721.py:440` 부근이며, output validator는 `build_walksafe_formal_rel_ops_cls_20260721.py:2224` 부근에서 heading, outcome과 empty ID 중심으로 검사한다.
- targeted test도 lifecycle field와 현재 empty/NOT_RUN 경계는 확인하지만 external 7의 role/input/action/evidence schema와 여덟 completion 조건이 generated section에 존재하는지는 검사하지 않는다. 관련 위치는 `test_walksafe_formal_rel_ops_cls.py:266`, `test_walksafe_formal_rel_ops_cls.py:304` 부근이다.

영향:

- external execution과 receipt가 아직 없다는 사실은 올바르다.
- 그러나 `EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW`는 내부 작성이 끝나고 외부 사건만 남았다는 의미이므로, 실행에 필요한 완전한 계약이 실제 artifact에 없으면 해당 일곱 artifact의 내부 내용 완전성을 승인할 수 없다.

수정 지시:

1. external 7 각각의 role/approver, activation condition, required input, exact action, evidence schema와 completion test를 해당 generated artifact section에 투영한다.
2. evidence schema는 catalog fields, lifecycle record fields, 실제 receipt binding의 `repository_relative_path`, `sha256`, `byte_length`, event/decision ID, effective time, operator/acceptor identity를 보존한다.
3. synthetic row 금지, 실제 사건/권한 결정, 모든 lifecycle field 충족, scoped approval, 실제 receipt hash binding을 completion test로 생성물과 builder 양쪽에 고정한다.
4. generated6와 manifest를 다시 생성하고 targeted test가 일곱 section의 완전한 projection을 직접 검사하도록 한다.
5. 수정 전에는 external 7을 내부 내용 완료 후보로 중앙 전이하지 않는다.

### MAJOR-W9-002: semantic projection schema가 문서화되지 않아 독립 재현 계약이 불완전하다

기록된 semantic fingerprint는 `87c29e723062baf4985663cb7dfef56ee7260053d94ca30dfe42226206654285`이다. 열거된 12개 의미 field에 다음 숨은 항목을 추가하면 현재 값이 재현된다.

```json
{
  "projection_schema": "walksafe.w9.closure-operations-walksafe.semantic.v1"
}
```

그러나 current-state JSON/Markdown의 `semantic_projection_rule`에는 이 key와 value가 없고 별도 generator 또는 receipt에도 계산 payload가 없다. 외부 검토자는 이 문자열을 추측하거나 탐색하지 않고는 fingerprint를 재현할 수 없다.

관련 위치:

- `closure-operations-walksafe-current-state.json:2489`
- `closure-operations-walksafe-current-state.json:2494`
- `closure-operations-walksafe-current-state.md:215`

수정 지시:

1. `integrity`에 exact `projection_schema` key/value와 포함 field 목록을 기계 판독 가능하게 기록한다.
2. 해당 규칙으로 semantic fingerprint를 다시 생성한다.
3. 독립 checker가 문서에 기록된 정보만으로 content/input/semantic 세 fingerprint를 재현하도록 한다.

### MINOR-W9-003: 기존 authoring `3/3 PASS`와 diff `2+6+2`의 실행 provenance가 불충분하다

`generation_evidence`는 CLS/OPS와 WS targeted test를 각각 `selected=3`, `passed=3`, `failed=0`으로 기록하지만 test node ID, 전체 명령, output receipt 또는 log hash가 없다. common source 2개, generated6, generated2의 in-memory diff count도 preimage 또는 diff receipt가 없어 현재 hash-bound 파일 집합만 독립 확인할 수 있다.

이번 독립 검토에서 명시적인 targeted `3+3`을 새로 실행해 현재 동작은 확인했으나, 이 결과가 기존 authoring에서 선택했다는 동일한 여섯 node였는지는 증명할 수 없다.

관련 위치:

- `closure-operations-walksafe-current-state.json:147`
- `closure-operations-walksafe-current-state.json:157`
- `closure-operations-walksafe-current-state.json:164`
- `closure-operations-walksafe-current-state.md:50`

수정 지시:

1. 선택한 test node ID, 명령, exit code, 개별 결과와 대상 source hash를 기록한다.
2. diff count에는 baseline/current binding 또는 결정론적 diff receipt를 결속한다.
3. standalone log를 만들지 않는다면 current-state 자체에 위 최소 provenance를 포함한다.

## 검토 subject

- JSON: `closure-operations-walksafe-current-state.json`, `99449` bytes, SHA-256 `4078a29304551e417ea712ee253cfead787282d1a7b8116a19ed422e89ceb440`
- Markdown: `closure-operations-walksafe-current-state.md`, `42097` bytes, SHA-256 `ad475489cdb29c9606c51e5977f193a73b25e30be96ff4108052690f3e9ea85b`
- 포함 범위: current-state pair 2개, direct evidence 14개, transitive artifact catalog 1개
- subject count: `17`
- digest 규칙: repository-relative path 순으로 정렬한 `{path, sha256, byte_length}` 배열을 UTF-8 JSON, `ensure_ascii=false`, key sort, compact separators, trailing LF로 canonicalize한 뒤 SHA-256
- subject digest: `ba0519acbcc4f8b0ebf5d6e416bd357ebb3b02aee03de1d31c6eed04fb11105c`

W8의 `implementation-receipt.json` path-only forward reference는 존재하지 않는 파일을 존재하거나 hash-bound됐다고 주장하지 않으므로 현재 표현은 보수적이다. 중앙 통합은 실제 receipt가 생성된 뒤 path, SHA-256, byte length와 W8 독립 판정을 결속하기 전에는 이 선행조건을 충족한 것으로 처리하면 안 된다.

## exact 15 outcome 검증

| outcome | count | artifact |
|---|---:|---|
| `OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW` | 7 | `DLV-CLS-07`, `DLV-CLS-09`, `DLV-OPS-20`, `DLV-OPS-21`, `DLV-OPS-24`, `DLV-WS-08`, `DLV-WS-18` |
| `INTERNAL_GAP` | 1 | `DLV-WS-10` |
| `EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW` | 7 | `DLV-CLS-08`, `DLV-CLS-10`, `DLV-CLS-14`, `DLV-CLS-15`, `DLV-CLS-16`, `DLV-OPS-17`, `DLV-OPS-19` |

JSON과 Markdown의 exact ID/outcome/review/approval tuple은 `15/15` 일치한다. 다만 `MAJOR-W9-001` 때문에 external 7의 상태 delta는 허용하지 않는다. 나머지 8개에 대한 부분 전이도 이 review에서 승인하지 않는다.

## CLS/OPS 내용 검증

- generated6:
  - `docs/deliverables/10-operations/operations-control-registers.md`
  - `docs/deliverables/10-operations/registers/operations-registers.json`
  - `docs/deliverables/12-closure/closure-handover-register.md`
  - `docs/deliverables/12-closure/decommissioning-plan.md`
  - `docs/deliverables/12-closure/registers/closure-readiness-register.json`
  - `docs/deliverables/manifests/rel-ops-cls-draft-20260721-r001.json`
- exact 12의 catalog `required_contents`, 기본 `completion_criteria`, lifecycle status와 required record fields는 generated section에 투영되어 있다.
- lifecycle contract count는 `12`; external execution contract count는 `7`.
- external 7의 `fake_event_or_receipt_allowed=false`, actual event/evidence/receipt count는 모두 `0`.
- actual handover, acceptance, data transfer/deletion, secret rotation/revocation, decommission receipt는 없다.
- `operations_started=false`, `incidents=[]`, `operation_changes=[]`이며 이 빈 배열은 무사건·무변경·운영 완료의 증거가 아니라고 명시한다.
- `DLV-OPS-20`은 r020 audit/backlog, 열린 gate와 FP-035를 유지한다.
- `DLV-OPS-21`은 미측정·dashboard·비상대응자·복구훈련 debt와 미승인 risk acceptance를 유지한다.
- `DLV-OPS-24`는 TMAP quota/cost/support 미확정과 live/deploy/provider-exit 검증 미실행을 유지한다.

## WalkSafe 내용 검증

- generated2:
  - `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md`
  - `docs/deliverables/manifests/sec-ws-draft-20260721-r001.json`
- `DLV-WS-08`: exact 13 class order와 13개 risk row가 일치한다.
- 각 WS-08 row는 class/context/FP/FN/harm/exposure/detectability/model/policy/UI/fail-closed/evidence/residual field limitation 필드를 가진다.
- WS-08 quantitative evaluation과 Android device safety validation은 `NOT_RUN`, approval은 `NOT_APPROVED`, release는 `NOT_ELIGIBLE`이다.
- `DLV-WS-10`: source PT와 Android TFLite hash는 구분되지만 fixed same-input 비교가 `NOT_RUN`, dataset/preprocessing/result가 `null`, tolerance가 `NOT_ESTABLISHED`/`null`, completion eligible은 `false`이다.
- `DLV-WS-18`: timeout `4.0s`, error class `7`, automatic retry budget `0`, 새 검색/경로 cache reuse와 stale route guidance는 `PROHIBITED`이다.
- WS-18 planned test `7`개와 live TMAP, quota, deployment, provider exit, alternate-provider validation은 모두 `NOT_RUN`이다.

## source, parity 및 fingerprint

| 검사 | 결과 |
|---|---|
| direct evidence current hash/byte length | `14/14 PASS` |
| transitive artifact catalog hash/byte length | `1/1 PASS` |
| common gap audit | `479137` bytes, `6c6bd3e1db80cfcca05361afedb6f2eb4de0cc4a67bf76b9a6830c71cf2a70b7` |
| common remediation backlog | `59125` bytes, `20b103c83f397894a7b9d5c9eedd696b0aa977b02552f49614876714b540dd6f` |
| exact tuple JSON/Markdown parity | `15/15 PASS` |
| stable ID parity | semantic `47` = manifest `47` = Markdown `47`, unique, delta `0` |
| content fingerprint | `PASS`, `c9c192c0c51b2e8190b00294b0ffd73a717c316a9e297b54367b6dd30eb49914` |
| input-set fingerprint | `PASS`, `6551fe176a71d439e49afb7273417d46c2a816e0a98665a1c4263d3c7bef59f1` |
| semantic fingerprint bytes | 숨은 schema를 추정하면 `PASS`, `87c29e723062baf4985663cb7dfef56ee7260053d94ca30dfe42226206654285` |
| documented semantic reproduction contract | `FAIL` |
| global boundary JSON/Markdown parity | `PASS` |

stable ID 47개는 disposition `15`, lifecycle `12`, external contract `7`, WS-08 class risk `13`으로 구성된다.

## 독립 실행 결과

두 builder는 직렬 실행했다.

```text
PYTHONDONTWRITEBYTECODE=1 python3 scripts/build_walksafe_formal_rel_ops_cls_20260721.py --check
PASS: verified 19 REL/OPS/CLS files; Draft=39, Planned/NOT_RUN=23, release=NOT_ELIGIBLE

PYTHONDONTWRITEBYTECODE=1 python3 scripts/build_walksafe_formal_sec_ws_20260721.py --check
PASS: verified 13 SEC/WS files; Draft=24, Planned/NOT_RUN=17, release=NOT_ELIGIBLE
```

Targeted test는 각 파이프라인에서 세 node를 직렬 실행했다.

```text
tests.test_walksafe_formal_rel_ops_cls.WalkSafeFormalRelOpsClsTests.test_w9_internal_content_and_external_boundaries
tests.test_walksafe_formal_rel_ops_cls.WalkSafeFormalRelOpsClsTests.test_single_admin_backup_and_operational_execution_boundary
tests.test_walksafe_formal_rel_ops_cls.WalkSafeFormalRelOpsClsTests.test_project_is_explicitly_not_closed
PASS: 3/3

tests.test_walksafe_formal_sec_ws.WalkSafeFormalSecurityWalkSafeTests.test_w9_ws08_ws10_ws18_contracts_are_explicit
tests.test_walksafe_formal_sec_ws.WalkSafeFormalSecurityWalkSafeTests.test_manifest_has_exact_draft_planned_and_source_bindings
tests.test_walksafe_formal_sec_ws.WalkSafeFormalSecurityWalkSafeTests.test_five_gates_remain_not_run_unwaived_and_release_blocked
PASS: 3/3
```

## 전역 보수 경계

- source commit/build ID: `null`
- formal 279: `NOT_RUN`, executed `0`, pass `0`, evidence `0`
- actual device, WS-08 quantitative, PT/TFLite equivalence: `NOT_RUN`
- live provider, quota, provider exit, alternate provider: `NOT_RUN`
- signing, deployment, closure execution: `NOT_RUN`
- formal QA, signing, deployment, closure approval: 승인되지 않음
- remaining gate: `5`, 모두 `NOT_RUN`, waiver `0`
- release: `NOT_ELIGIBLE`

## 승인 경계

- W9 독립 검토: `NO_GO`
- finding: `0 BLOCKING / 2 MAJOR / 1 MINOR`
- `GO_STATUS_DELTA_ALLOWED`: 부여하지 않음
- central transition: 적용 금지
- release/deployment/signing/closure/legal/model approval: 부여하지 않음
- 재검토 조건: 세 finding 수정, current-state pair와 affected generated files 재생성, 새 subject digest, 세 fingerprint 독립 재현, 명명된 builder/test 검증 통과
