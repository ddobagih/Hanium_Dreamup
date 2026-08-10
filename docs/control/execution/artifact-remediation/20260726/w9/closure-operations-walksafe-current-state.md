# W9 Closure / Operations / WalkSafe current state

> Deterministic human-readable projection of the adjacent JSON current-state record. This is not GO or acceptance authority.

## Fixed boundary

- Exact15: `OK 7 / GAP 1 / EXTERNAL 7`
- Stable IDs: `47`
- Global product execution: `NOT_RUN`
- External7 execution: `NOT_RUN`
- W8 predecessor: `PATH_ONLY`
- Final independent rereview authority: `NOT_YET_BOUND`

## Exact15 disposition

```json
[
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-CLS-07",
    "artifact_name": "미해결 결함",
    "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-CLS-07"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-CLS-08",
    "artifact_name": "잔여 위험",
    "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-CLS-08"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-CLS-09",
    "artifact_name": "기술부채",
    "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-CLS-09"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-CLS-10",
    "artifact_name": "운영 소유권·권한 이관",
    "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-CLS-10"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-CLS-14",
    "artifact_name": "데이터 보존·이관·삭제",
    "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-CLS-14"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-CLS-15",
    "artifact_name": "계정·키·인프라 정리",
    "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-CLS-15"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-CLS-16",
    "artifact_name": "서비스 종료·폐기 계획",
    "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-CLS-16"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-OPS-17",
    "artifact_name": "장애 기록",
    "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-OPS-17"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-OPS-19",
    "artifact_name": "운영 변경이력",
    "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-OPS-19"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-OPS-20",
    "artifact_name": "유지보수 Backlog",
    "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-OPS-20"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-OPS-21",
    "artifact_name": "기술부채 목록",
    "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-OPS-21"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-OPS-24",
    "artifact_name": "외부 서비스·API 의존성 현황",
    "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-OPS-24"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-WS-08",
    "artifact_name": "오탐·미탐 안전분석",
    "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-WS-08"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-WS-10",
    "artifact_name": "PT-TFLite 변환 동등성",
    "current_disposition": "INTERNAL_GAP",
    "independent_review_status": "PENDING",
    "reason": "Fixed same-input PT-TFLite comparison and approved tolerance are NOT_RUN/NOT_ESTABLISHED.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-WS-10"
  },
  {
    "approval_status": "NOT_APPROVED",
    "artifact_id": "DLV-WS-18",
    "artifact_name": "navigation provider failure/quota/exit contract",
    "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
    "independent_review_status": "PENDING",
    "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
    "release_status": "NOT_ELIGIBLE",
    "stable_id": "W9-DISP-WS-18"
  }
]
```

## Hash-bound current source set

| # | Role | Path | Bytes | SHA-256 |
|---:|---|---|---:|---|
| 1 | `CURRENT_GAP_ANALYSIS` | `docs/control/audits/walksafe-implementation-gap-analysis-20260726-r020.json` | 479137 | `6c6bd3e1db80cfcca05361afedb6f2eb4de0cc4a67bf76b9a6830c71cf2a70b7` |
| 2 | `CURRENT_REMEDIATION_BACKLOG` | `docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r020.json` | 59125 | `20b103c83f397894a7b9d5c9eedd696b0aa977b02552f49614876714b540dd6f` |
| 3 | `CLS_OPS_BUILDER` | `scripts/build_walksafe_formal_rel_ops_cls_20260721.py` | 132367 | `3bf8c8b918b8c23d7da9ff111bfd3318593c60798447a10adef248effba92cd6` |
| 4 | `CLS_OPS_TARGETED_TEST` | `tests/test_walksafe_formal_rel_ops_cls.py` | 28178 | `78d627a79202decdbadf81051b4700280b0c06056eebd8dacec359be64d75c12` |
| 5 | `CLS_OPS_GENERATED_OPERATIONS_MD` | `docs/deliverables/10-operations/operations-control-registers.md` | 57361 | `e5f67afa7aa1a238f58624d80038443461ddecec1d821bc66216b774e00237b2` |
| 6 | `CLS_OPS_GENERATED_OPERATIONS_JSON` | `docs/deliverables/10-operations/registers/operations-registers.json` | 32049 | `8cdf279aebb020a94161414d75a219cc0f9dd926ce86235402185e3ab920fa2a` |
| 7 | `CLS_OPS_GENERATED_CLOSURE_HANDOVER_MD` | `docs/deliverables/12-closure/closure-handover-register.md` | 35094 | `35ae3e98cb563f7e23046c5e5f26ddd152334d320f4182b90cf8c34904f80fcd` |
| 8 | `CLS_OPS_GENERATED_DECOMMISSIONING_MD` | `docs/deliverables/12-closure/decommissioning-plan.md` | 33318 | `1cbebd3daec7b862874af8705f5a92d13f770f847cd5a39838a764ee7d67e3ce` |
| 9 | `CLS_OPS_GENERATED_CLOSURE_JSON` | `docs/deliverables/12-closure/registers/closure-readiness-register.json` | 35802 | `566ec31ed9d405604e914d4f8ed02f3875a420f9f9f7458376851d921d2daf46` |
| 10 | `CLS_OPS_GENERATED_MANIFEST` | `docs/deliverables/manifests/rel-ops-cls-draft-20260721-r001.json` | 125261 | `c02986f83e36140903f5d001cfb32fe36902dad28753d37d6ffe7f98e70c4c1a` |
| 11 | `WALKSAFE_BUILDER` | `scripts/build_walksafe_formal_sec_ws_20260721.py` | 130667 | `1b22bad59dda622e53ac910ace405c59e1d31aea47136894b3c187eed8faca5d` |
| 12 | `WALKSAFE_TARGETED_TEST` | `tests/test_walksafe_formal_sec_ws.py` | 19703 | `39c475e3f56d7c4d867cf1f85ab2742c6970876fb58e13892de7d2908c27b83e` |
| 13 | `WALKSAFE_GENERATED_POLICY_MD` | `docs/deliverables/11-walksafe/walksafe-safety-and-policy.md` | 68326 | `dfa1a5d817bf41b2ff81a14f76a559d04076e7e393f0eed4336eb2aa8b686ce5` |
| 14 | `WALKSAFE_GENERATED_MANIFEST` | `docs/deliverables/manifests/sec-ws-draft-20260721-r001.json` | 31302 | `4c73dbd4df8356b0ccba3de6b4193d6d693be801e887021a8a6964bc0b6e316f` |

- Direct evidence fingerprint: `82104b6ad52e82dcae304afaacb082a4ab19e24f6c2b827892dff262bb53d58b`
- Direct files: `14`
- Input subjects with remediation lineage: `15`
- Input-set fingerprint: `61e40326e93b0efa97da283cfddce96522a4b7b12f0b3622867a2febd6e0f69e`

## NO_GO remediation lineage

- `docs/control/execution/artifact-remediation/20260726/w9/independent-review.md`
- SHA-256 `6063f27a9563022dc523fd41ec4c40e79a7c20a5d72c20f3da03c99b1f8011ea`; bytes `12809`
- Recorded `NO_GO`, findings `BLOCKING 0 / MAJOR 2 / MINOR 1`.
- Use is `REMEDIATION_LINEAGE_ONLY`; disposition, acceptance, and transition authority are all `false`.

## Authoring provenance boundary

- `HISTORICAL_AUTHOR_OBSERVATION_NOT_ACCEPTANCE_EVIDENCE`
- Diff observations: common `2/2`, CLS/OPS `6/6`, WalkSafe `2/2`.
- Targeted-test observations: CLS/OPS `3/3`, WalkSafe `3/3`.
- Local node IDs, diff preimages, standalone logs, and execution receipts were not preserved.
- Current builder/test results become transition evidence only if the final independent rereview hash-binds exact current receipts and issues the disposition.
- Forward reference: `docs/control/execution/artifact-remediation/20260726/w9/independent-final-review.md` (`PATH_ONLY`, not bound).

```json
{
  "generation_evidence": {
    "acceptance_evidence": false,
    "classification": "HISTORICAL_AUTHOR_OBSERVATION_NOT_ACCEPTANCE_EVIDENCE",
    "historical_observations": [
      {
        "acceptance_evidence": false,
        "classification": "OBSERVATION_ONLY",
        "in_memory_diff_observation": "2_OF_2_OBSERVED",
        "scope": "COMMON_SOURCE"
      },
      {
        "acceptance_evidence": false,
        "builder_check_observation": "REVIEW_RECORDED_PASS_ONLY",
        "classification": "OBSERVATION_ONLY",
        "in_memory_diff_observation": "6_OF_6_OBSERVED",
        "scope": "CLS_OPS",
        "targeted_test_observation": "3_OF_3_REVIEW_RECORDED_PASS_ONLY"
      },
      {
        "acceptance_evidence": false,
        "builder_check_observation": "REVIEW_RECORDED_PASS_ONLY",
        "classification": "OBSERVATION_ONLY",
        "in_memory_diff_observation": "2_OF_2_OBSERVED",
        "scope": "WALKSAFE",
        "targeted_test_observation": "3_OF_3_REVIEW_RECORDED_PASS_ONLY"
      }
    ],
    "missing_provenance": [
      "LOCAL_TEST_NODE_IDS_NOT_PRESERVED",
      "DIFF_BASELINE_AND_PREIMAGE_NOT_PRESERVED",
      "STANDALONE_COMMAND_LOG_NOT_PRESERVED",
      "EXECUTION_RECEIPT_NOT_PRESERVED"
    ],
    "prohibited_use": [
      "ACCEPTANCE_EVIDENCE",
      "CURRENT_TRANSITION_AUTHORITY",
      "FINAL_GO_AUTHORITY"
    ]
  },
  "provenance_boundary": {
    "current_builder_check_and_targeted_test_results": {
      "acceptance_evidence": false,
      "current_use": "OBSERVATION_ONLY_NOT_TRANSITION_EVIDENCE",
      "may_become_transition_evidence_only_when": "A final independent rereview hash-binds exact current inputs, command/log receipts, test node IDs, results, and issues a disposition.",
      "recording_source": "docs/control/execution/artifact-remediation/20260726/w9/independent-review.md",
      "transition_authority": false
    },
    "final_independent_rereview": {
      "binding": "PATH_ONLY_FORWARD_REFERENCE",
      "byte_length": null,
      "current_status": "NOT_YET_BOUND",
      "path": "docs/control/execution/artifact-remediation/20260726/w9/independent-final-review.md",
      "required_for_transition": true,
      "sha256": null,
      "transition_authority_available": false
    },
    "first_review_is_authority": false,
    "first_review_use": "REMEDIATION_LINEAGE_ONLY",
    "historical_authoring_classification": "HISTORICAL_AUTHOR_OBSERVATION_NOT_ACCEPTANCE_EVIDENCE",
    "unsupported_provenance_claim_count": 0
  }
}
```

## Generated external7 lossless parity

- Schema `walksafe.w9.external-execution-contract.v1`
- Source `docs/deliverables/manifests/rel-ops-cls-draft-20260721-r001.json#/w9_content_assessment/external_execution_contracts`
- Generated and overlay fingerprint `9d09cedad2bf7ae0cb78bcc83799590fe2f3096298203acf42a9e62bef934d1a`
- Overlay exact object parity: `PASS`
- Generated JSON exact-object parity: `PASS`
- Generated Markdown stable ID/role/input/action/evidence/completion parity: `PASS`

| Generated artifact | Representation | Lossless contracts | Reference-only |
|---|---|---|---|
| `docs/deliverables/10-operations/operations-control-registers.md` | `MARKDOWN_LOSSLESS_CRITICAL_FIELDS` | `W9-EXT-OPS-17`, `W9-EXT-OPS-19` | - |
| `docs/deliverables/10-operations/registers/operations-registers.json` | `JSON_EXACT_OBJECT` | `W9-EXT-OPS-17`, `W9-EXT-OPS-19` | - |
| `docs/deliverables/12-closure/closure-handover-register.md` | `MARKDOWN_LOSSLESS_CRITICAL_FIELDS` | `W9-EXT-CLS-08`, `W9-EXT-CLS-10` | - |
| `docs/deliverables/12-closure/decommissioning-plan.md` | `MARKDOWN_LOSSLESS_CRITICAL_FIELDS` | `W9-EXT-CLS-14`, `W9-EXT-CLS-15`, `W9-EXT-CLS-16` | - |
| `docs/deliverables/12-closure/registers/closure-readiness-register.json` | `JSON_EXACT_OBJECT` | `W9-EXT-CLS-08`, `W9-EXT-CLS-10`, `W9-EXT-CLS-14`, `W9-EXT-CLS-15`, `W9-EXT-CLS-16` | - |
| `docs/deliverables/manifests/rel-ops-cls-draft-20260721-r001.json` | `JSON_EXACT_OBJECT` | `W9-EXT-CLS-08`, `W9-EXT-CLS-10`, `W9-EXT-CLS-14`, `W9-EXT-CLS-15`, `W9-EXT-CLS-16`, `W9-EXT-OPS-17`, `W9-EXT-OPS-19` | - |

### External7 canonical generated contracts

Copied without enrichment or field renaming from the builder-generated manifest.

```json
[
  {
    "approver_role": "프로젝트책임자",
    "artifact_type_id": "CLS-08",
    "catalog_artifact_id": "DLV-CLS-08",
    "completion_test": [
      "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 위험 ID·시나리오, 가능성·영향·노출.",
      "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
      "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
      "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
      "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
      "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
      "The named approver explicitly approves the exact scope and effective window.",
      "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
    ],
    "contract_schema": "walksafe.w9.external-execution-contract.v1",
    "current_state": {
      "acceptance_status": "NOT_APPROVED",
      "actual_event_count": 0,
      "actual_event_ids": [],
      "actual_evidence_count": 0,
      "actual_evidence_ids": [],
      "actual_receipt_count": 0,
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "execution_status": "NOT_RUN",
      "operator_state": "UNASSIGNED",
      "recipient_state": "UNASSIGNED"
    },
    "due_and_review": {
      "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
      "due_at": null,
      "due_condition": "BEFORE_PROJECT_CLOSURE_AND_AT_EACH_ACCEPTANCE_REVIEW",
      "review_due_at": null
    },
    "evidence_schema": {
      "catalog_fields": [
        "위험 ID·시나리오",
        "가능성·영향·노출",
        "기존 통제·효과",
        "잔여 등급",
        "모니터링·비상조치",
        "수용자·재검토일"
      ],
      "lifecycle_fields": [
        "risk_id",
        "source_ref",
        "severity",
        "likelihood",
        "user_data_service_impact",
        "mitigation",
        "owner_role",
        "due_condition",
        "acceptance_decision",
        "acceptor_identity",
        "effective_window",
        "evidence_refs",
        "receipt_ref"
      ]
    },
    "external_authority": "PROJECT_OWNER_EXPLICIT_SCOPED_ACCEPTANCE_OR_REJECTION",
    "external_trigger": "종료 시점에 수용·감시할 잔여 위험이 존재하는 경우 활성",
    "fake_event_or_receipt_allowed": false,
    "internal_action": "Record mitigation and monitoring for every residual risk, then obtain an explicit scoped acceptance or rejection from the authorized approver.",
    "normative_model": "BUILDER_INTERNAL_CANONICAL",
    "procedure": [
      "실제 잔여 위험과 activation 근거를 식별한다.",
      "모든 lifecycle field와 mitigation·monitoring을 채우거나 소유자·기한이 있는 차단 상태로 둔다.",
      "승인자가 exact scope와 effective window를 명시적으로 수용 또는 거절한다.",
      "실제 decision과 acceptor identity를 receipt hash binding으로 결속한다."
    ],
    "receipt_schema": [
      "repository_relative_path",
      "sha256",
      "byte_length",
      "source_event_or_decision_id",
      "occurred_or_effective_at",
      "operator_or_acceptor_identity"
    ],
    "release_impact": "승인된 실제 risk acceptance receipt 전에는 프로젝트 종료·인계를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
    "required_inputs": [
      "최종 인수 범위와 승인 릴리스 기준선",
      "산출물·결함·위험·부채·권한 inventory",
      "운영 수신자·계약·데이터 보존 결정"
    ],
    "responsible_role": "프로젝트관리자",
    "stable_id": "W9-EXT-CLS-08"
  },
  {
    "approver_role": "프로젝트책임자",
    "artifact_type_id": "CLS-10",
    "catalog_artifact_id": "DLV-CLS-10",
    "completion_test": [
      "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 자산·service owner, 계정·역할·접근.",
      "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
      "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
      "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
      "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
      "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
      "The named approver explicitly approves the exact scope and effective window.",
      "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
    ],
    "contract_schema": "walksafe.w9.external-execution-contract.v1",
    "current_state": {
      "acceptance_status": "NOT_APPROVED",
      "actual_event_count": 0,
      "actual_event_ids": [],
      "actual_evidence_count": 0,
      "actual_evidence_ids": [],
      "actual_receipt_count": 0,
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "execution_status": "NOT_RUN",
      "operator_state": "UNASSIGNED",
      "recipient_state": "UNASSIGNED"
    },
    "due_and_review": {
      "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
      "due_at": null,
      "due_condition": "BEFORE_OPERATIONAL_RESPONSIBILITY_TRANSFER_EFFECTIVE_AT",
      "review_due_at": null
    },
    "evidence_schema": {
      "catalog_fields": [
        "자산·service owner",
        "계정·역할·접근",
        "credential 회전·전달 방식",
        "runbook·지원·연락",
        "권한 test·회수",
        "양측 확인·일시"
      ],
      "lifecycle_fields": [
        "handover_id",
        "branch",
        "recipient_identity",
        "operator_identity",
        "scope",
        "account_permission_inventory_refs",
        "secret_reference_ids",
        "backlog_and_risk_refs",
        "support_boundary",
        "approval_status",
        "effective_at",
        "receipt_ref"
      ]
    },
    "external_authority": "PROJECT_OWNER_AND_NAMED_RECIPIENT_BILATERAL_CONFIRMATION",
    "external_trigger": "서비스·자산·계정의 운영 책임을 다른 주체에게 이관할 때 활성",
    "fake_event_or_receipt_allowed": false,
    "internal_action": "Execute ownership and least-privilege handover with named recipient/operator, verification, revocation boundary and bilateral confirmation.",
    "normative_model": "BUILDER_INTERNAL_CANONICAL",
    "procedure": [
      "실제 이관 decision과 named recipient/operator를 확인한다.",
      "자산·계정·권한·backlog·risk·support 범위를 최소권한으로 대조한다.",
      "전달·검증·기존 권한 회수 경계를 양측이 확인한다.",
      "effective time과 양측 identity를 실제 handover receipt에 hash 결속한다."
    ],
    "receipt_schema": [
      "repository_relative_path",
      "sha256",
      "byte_length",
      "source_event_or_decision_id",
      "occurred_or_effective_at",
      "operator_or_acceptor_identity"
    ],
    "release_impact": "named recipient의 실제 양측 인계 receipt 전에는 운영 이관·프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
    "required_inputs": [
      "최종 인수 범위와 승인 릴리스 기준선",
      "산출물·결함·위험·부채·권한 inventory",
      "운영 수신자·계약·데이터 보존 결정"
    ],
    "responsible_role": "프로젝트관리자",
    "stable_id": "W9-EXT-CLS-10"
  },
  {
    "approver_role": "프로젝트책임자",
    "artifact_type_id": "CLS-14",
    "catalog_artifact_id": "DLV-CLS-14",
    "completion_test": [
      "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 데이터 inventory·분류, 보존 근거·기간.",
      "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
      "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
      "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
      "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
      "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
      "The named approver explicitly approves the exact scope and effective window.",
      "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
    ],
    "contract_schema": "walksafe.w9.external-execution-contract.v1",
    "current_state": {
      "acceptance_status": "NOT_APPROVED",
      "actual_event_count": 0,
      "actual_event_ids": [],
      "actual_evidence_count": 0,
      "actual_evidence_ids": [],
      "actual_receipt_count": 0,
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "execution_status": "NOT_RUN",
      "operator_state": "UNASSIGNED",
      "recipient_state": "UNASSIGNED"
    },
    "due_and_review": {
      "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
      "due_at": null,
      "due_condition": "BEFORE_EACH_TRANSFER_OR_DELETION_AND_PROJECT_CLOSURE",
      "review_due_at": null
    },
    "evidence_schema": {
      "catalog_fields": [
        "데이터 inventory·분류",
        "보존 근거·기간",
        "이관 대상·형식·암호화",
        "삭제·익명화·backup",
        "사용자·기관 통지",
        "검증·승인 기록",
        "선행 승인된 CLS-16 폐기계획 ID와 데이터 보존·이관·삭제 실행 receipt"
      ],
      "lifecycle_fields": [
        "action_id",
        "branch",
        "data_class",
        "storage_location",
        "technical_retention_target",
        "legal_basis_status",
        "transfer_format",
        "encryption_control",
        "deletion_or_transfer_action",
        "backup_reconciliation",
        "notification_status",
        "operator_identity",
        "approval_status",
        "execution_status",
        "receipt_ref"
      ]
    },
    "external_authority": "PROJECT_OWNER_APPROVED_DATA_BRANCH_WITH_APPLICABLE_LEGAL_AUTHORITY",
    "external_trigger": "종료 시 보존·이관·삭제할 데이터가 존재하는 경우 활성",
    "fake_event_or_receipt_allowed": false,
    "internal_action": "Execute the approved retention, transfer or deletion branch for each data class and reconcile backups and notifications.",
    "normative_model": "BUILDER_INTERNAL_CANONICAL",
    "procedure": [
      "각 data class·storage location의 승인된 branch와 법적 권한을 확인한다.",
      "보존·이관·삭제, encryption, backup reconciliation, notification을 class별 실행한다.",
      "operator와 승인 범위 및 미처리 예외를 검증한다.",
      "실제 transfer/deletion event와 receipt를 hash 결속한다."
    ],
    "receipt_schema": [
      "repository_relative_path",
      "sha256",
      "byte_length",
      "source_event_or_decision_id",
      "occurred_or_effective_at",
      "operator_or_acceptor_identity"
    ],
    "release_impact": "법적 근거·고지 승인과 실제 class별 receipt 전에는 데이터 처분·프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
    "required_inputs": [
      "최종 인수 범위와 승인 릴리스 기준선",
      "산출물·결함·위험·부채·권한 inventory",
      "운영 수신자·계약·데이터 보존 결정"
    ],
    "responsible_role": "프로젝트관리자",
    "stable_id": "W9-EXT-CLS-14"
  },
  {
    "approver_role": "프로젝트책임자",
    "artifact_type_id": "CLS-15",
    "catalog_artifact_id": "DLV-CLS-15",
    "completion_test": [
      "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 자산·계정 inventory, 유지·이관·폐기 판정.",
      "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
      "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
      "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
      "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
      "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
      "The named approver explicitly approves the exact scope and effective window.",
      "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
    ],
    "contract_schema": "walksafe.w9.external-execution-contract.v1",
    "current_state": {
      "acceptance_status": "NOT_APPROVED",
      "actual_event_count": 0,
      "actual_event_ids": [],
      "actual_evidence_count": 0,
      "actual_evidence_ids": [],
      "actual_receipt_count": 0,
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "execution_status": "NOT_RUN",
      "operator_state": "UNASSIGNED",
      "recipient_state": "UNASSIGNED"
    },
    "due_and_review": {
      "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
      "due_at": null,
      "due_condition": "BEFORE_EACH_TRANSFER_ROTATION_OR_REVOCATION",
      "review_due_at": null
    },
    "evidence_schema": {
      "catalog_fields": [
        "자산·계정 inventory",
        "유지·이관·폐기 판정",
        "key·certificate 회전",
        "서버·storage·DNS 종료",
        "backup·로그 보존",
        "검증·비용 종료",
        "선행 승인된 CLS-16 폐기계획 ID와 계정·키·인프라 정리 실행 receipt"
      ],
      "lifecycle_fields": [
        "asset_id",
        "asset_type",
        "current_controller",
        "target_controller",
        "action_transfer_rotate_or_revoke",
        "secret_reference_id",
        "least_privilege_check",
        "recovery_boundary_check",
        "operator_identity",
        "approval_status",
        "effective_at",
        "verification_result",
        "receipt_ref"
      ]
    },
    "external_authority": "PROJECT_OWNER_AUTHORIZED_ASSET_RETAIN_TRANSFER_ROTATE_OR_REVOKE_DECISION",
    "external_trigger": "프로젝트 전용 계정·key·도메인·인프라가 존재하는 경우 활성",
    "fake_event_or_receipt_allowed": false,
    "internal_action": "Execute the authorized retain/transfer/rotate/revoke decision for every account, key and infrastructure asset without recording secret values.",
    "normative_model": "BUILDER_INTERNAL_CANONICAL",
    "procedure": [
      "실제 asset inventory와 authorized action을 확인하되 secret 값은 기록하지 않는다.",
      "retain/transfer/rotate/revoke를 최소권한·복구수단 분리 경계에서 실행한다.",
      "target controller, operator, effective time과 검증 결과를 확인한다.",
      "실제 asset action과 receipt를 hash 결속한다."
    ],
    "receipt_schema": [
      "repository_relative_path",
      "sha256",
      "byte_length",
      "source_event_or_decision_id",
      "occurred_or_effective_at",
      "operator_or_acceptor_identity"
    ],
    "release_impact": "모든 asset의 승인 action과 실제 receipt 전에는 권한·인프라 정리나 프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
    "required_inputs": [
      "최종 인수 범위와 승인 릴리스 기준선",
      "산출물·결함·위험·부채·권한 inventory",
      "운영 수신자·계약·데이터 보존 결정"
    ],
    "responsible_role": "프로젝트관리자",
    "stable_id": "W9-EXT-CLS-15"
  },
  {
    "approver_role": "프로젝트책임자",
    "artifact_type_id": "CLS-16",
    "catalog_artifact_id": "DLV-CLS-16",
    "completion_test": [
      "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 종료 사유·범위·일정, 사용자·이해관계자 고지.",
      "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
      "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
      "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
      "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
      "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
      "The named approver explicitly approves the exact scope and effective window.",
      "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
    ],
    "contract_schema": "walksafe.w9.external-execution-contract.v1",
    "current_state": {
      "acceptance_status": "NOT_APPROVED",
      "actual_event_count": 0,
      "actual_event_ids": [],
      "actual_evidence_count": 0,
      "actual_evidence_ids": [],
      "actual_receipt_count": 0,
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "execution_status": "NOT_RUN",
      "operator_state": "UNASSIGNED",
      "recipient_state": "UNASSIGNED"
    },
    "due_and_review": {
      "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
      "due_at": null,
      "due_condition": "BEFORE_DECOMMISSION_AUTHORIZATION_AND_EXECUTION",
      "review_due_at": null
    },
    "evidence_schema": {
      "catalog_fields": [
        "종료 사유·범위·일정",
        "사용자·이해관계자 고지",
        "기능·신규가입 단계 종료",
        "데이터 export·삭제",
        "인프라·계정 철거",
        "지원 종료·최종 검증",
        "CLS-14 데이터 처리와 CLS-15 계정·키·인프라 정리 전에 승인할 폐기 순서·중단점·책임자"
      ],
      "lifecycle_fields": [
        "decommission_id",
        "decision_branch",
        "authority_identity",
        "user_notice_status",
        "new_session_block_status",
        "data_disposition_refs",
        "provider_exit_refs",
        "account_key_infrastructure_refs",
        "monitoring_window",
        "final_verification",
        "approval_status",
        "execution_status",
        "receipt_ref"
      ]
    },
    "external_authority": "PROJECT_OWNER_APPROVED_SERVICE_SHUTDOWN_OR_DECOMMISSION_DECISION",
    "external_trigger": "서비스 중단 또는 완전 폐기가 승인된 경우 활성",
    "fake_event_or_receipt_allowed": false,
    "internal_action": "Approve and execute the ordered service shutdown/decommission branch with notices, data rights, provider exit, checkpoints and final verification.",
    "normative_model": "BUILDER_INTERNAL_CANONICAL",
    "procedure": [
      "실제 폐기 권한·범위·순서·중단점을 승인한다.",
      "사용자 고지와 신규세션 차단 뒤 CLS-14, CLS-15, provider exit를 순서대로 실행한다.",
      "monitoring window와 final verification에서 잔여 접근·자료·비용을 확인한다.",
      "실제 decommission decision, operator와 receipt를 hash 결속한다."
    ],
    "receipt_schema": [
      "repository_relative_path",
      "sha256",
      "byte_length",
      "source_event_or_decision_id",
      "occurred_or_effective_at",
      "operator_or_acceptor_identity"
    ],
    "release_impact": "승인된 폐기 decision과 모든 단계의 실제 receipt 전에는 service decommission·프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
    "required_inputs": [
      "최종 인수 범위와 승인 릴리스 기준선",
      "산출물·결함·위험·부채·권한 inventory",
      "운영 수신자·계약·데이터 보존 결정"
    ],
    "responsible_role": "프로젝트관리자",
    "stable_id": "W9-EXT-CLS-16"
  },
  {
    "approver_role": "서비스소유자",
    "artifact_type_id": "OPS-17",
    "catalog_artifact_id": "DLV-OPS-17",
    "completion_test": [
      "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: incident ID·severity, 탐지·시작·종료 시간.",
      "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
      "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
      "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
      "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
      "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
      "The named approver explicitly approves the exact scope and effective window.",
      "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
    ],
    "contract_schema": "walksafe.w9.external-execution-contract.v1",
    "current_state": {
      "acceptance_status": "NOT_APPROVED",
      "actual_event_count": 0,
      "actual_event_ids": [],
      "actual_evidence_count": 0,
      "actual_evidence_ids": [],
      "actual_receipt_count": 0,
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "execution_status": "NOT_RUN",
      "operator_state": "UNASSIGNED",
      "recipient_state": "UNASSIGNED"
    },
    "due_and_review": {
      "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
      "due_at": null,
      "due_condition": "ON_EACH_REAL_INCIDENT_BEFORE_INCIDENT_CLOSURE",
      "review_due_at": null
    },
    "evidence_schema": {
      "catalog_fields": [
        "incident ID·severity",
        "탐지·시작·종료 시간",
        "사용자·데이터 영향",
        "조치 timeline·담당",
        "원인 후보·증거",
        "복구·후속·통지"
      ],
      "lifecycle_fields": [
        "incident_id",
        "severity",
        "started_at",
        "detected_at",
        "ended_at",
        "user_and_data_impact",
        "timeline",
        "containment",
        "recovery",
        "owner_role",
        "evidence_refs",
        "postmortem_status",
        "receipt_ref"
      ]
    },
    "external_authority": "REAL_INCIDENT_EVENT_AND_SERVICE_OWNER_POSTMORTEM_APPROVAL",
    "external_trigger": "운영 준비 시 장애 원장 구조를 사전 개설하고, 사용자·데이터·SLO에 영향을 준 장애가 발생할 때마다 실행 행을 추가",
    "fake_event_or_receipt_allowed": false,
    "internal_action": "Keep the preopened register empty until a real incident occurs; then add one complete real incident row and approved postmortem receipt.",
    "normative_model": "BUILDER_INTERNAL_CANONICAL",
    "procedure": [
      "실제 incident가 발생하기 전에는 opening register를 비워 둔다.",
      "발생 시 timeline·impact·containment·recovery·owner·evidence lifecycle field를 실제 값으로 기록한다.",
      "서비스소유자가 exact incident scope와 postmortem을 승인한다.",
      "실제 incident event, operator와 postmortem receipt를 hash 결속한다."
    ],
    "receipt_schema": [
      "repository_relative_path",
      "sha256",
      "byte_length",
      "source_event_or_decision_id",
      "occurred_or_effective_at",
      "operator_or_acceptor_identity"
    ],
    "release_impact": "실제 incident가 발생하면 승인된 postmortem receipt 전까지 운영·관련 release 판정을 닫을 수 없으며 현재 release는 NOT_ELIGIBLE이다.",
    "required_inputs": [
      "승인된 릴리스·배포 아키텍처·SLO",
      "운영 환경·계정·외부 서비스·관측성 현황",
      "지원·보안·복구 책임과 연락 체계"
    ],
    "responsible_role": "운영책임자",
    "stable_id": "W9-EXT-OPS-17"
  },
  {
    "approver_role": "서비스소유자",
    "artifact_type_id": "OPS-19",
    "catalog_artifact_id": "DLV-OPS-19",
    "completion_test": [
      "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 변경 ID·대상·전후, 사유·위험·영향.",
      "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
      "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
      "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
      "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
      "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
      "The named approver explicitly approves the exact scope and effective window.",
      "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
    ],
    "contract_schema": "walksafe.w9.external-execution-contract.v1",
    "current_state": {
      "acceptance_status": "NOT_APPROVED",
      "actual_event_count": 0,
      "actual_event_ids": [],
      "actual_evidence_count": 0,
      "actual_evidence_ids": [],
      "actual_receipt_count": 0,
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "execution_status": "NOT_RUN",
      "operator_state": "UNASSIGNED",
      "recipient_state": "UNASSIGNED"
    },
    "due_and_review": {
      "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
      "due_at": null,
      "due_condition": "BEFORE_EACH_REAL_OPERATIONAL_CHANGE_EXECUTION",
      "review_due_at": null
    },
    "evidence_schema": {
      "catalog_fields": [
        "변경 ID·대상·전후",
        "사유·위험·영향",
        "승인·window·담당",
        "실행 commit·명령",
        "검증·관찰",
        "rollback·incident 연결"
      ],
      "lifecycle_fields": [
        "change_id",
        "requested_at",
        "requester_role",
        "risk_assessment",
        "target_scope",
        "release_or_config_ref",
        "approval_status",
        "execution_window",
        "rollback_plan_ref",
        "verification_result",
        "operator_identity",
        "evidence_refs",
        "receipt_ref"
      ]
    },
    "external_authority": "SERVICE_OWNER_AUTHORIZED_REAL_OPERATIONAL_CHANGE",
    "external_trigger": "운영 준비 시 변경 원장 구조를 사전 개설하고, 코드 외 설정·권한·인프라 변경 때마다 실행 행을 추가",
    "fake_event_or_receipt_allowed": false,
    "internal_action": "Keep the preopened register empty until a real operational change occurs; then add one authorized change row with rollback, verification and receipt.",
    "normative_model": "BUILDER_INTERNAL_CANONICAL",
    "procedure": [
      "실제 operational change 전에는 opening register를 비워 둔다.",
      "change 대상·위험·승인·window·rollback·verification lifecycle field를 채운다.",
      "서비스소유자가 실행 전 exact scope와 effective window를 승인한다.",
      "실제 change event, operator와 execution receipt를 hash 결속한다."
    ],
    "receipt_schema": [
      "repository_relative_path",
      "sha256",
      "byte_length",
      "source_event_or_decision_id",
      "occurred_or_effective_at",
      "operator_or_acceptor_identity"
    ],
    "release_impact": "승인·rollback·검증·실제 receipt 없는 운영 변경은 실행할 수 없고 관련 배포·release는 차단되며 현재 release는 NOT_ELIGIBLE이다.",
    "required_inputs": [
      "승인된 릴리스·배포 아키텍처·SLO",
      "운영 환경·계정·외부 서비스·관측성 현황",
      "지원·보안·복구 책임과 연락 체계"
    ],
    "responsible_role": "운영책임자",
    "stable_id": "W9-EXT-OPS-19"
  }
]
```

## Semantic projection replay contract

- Exact key/value: `"projection_schema": "walksafe.w9.closure-operations-walksafe.semantic.v1"`
- Embedded payload: `/integrity/semantic_projection/payload`
- Fingerprint: `5b5dd16aad3e41b7e742bcd217841d3a9462f8c1d09239783f5dc47e3b69279a`
- Canonicalization: UTF-8 JSON; object keys sorted; separators ',' and ':'; ensure_ascii=false; no insignificant whitespace.
- Replay: create the exact schema member, copy every included top-level field without transformation, canonicalize, and SHA-256.

Included fields:

- `projection_schema`
- `exact_artifact_ids`
- `authority_boundary`
- `w8_predecessor`
- `remediation_lineage`
- `provenance_boundary`
- `generation_evidence`
- `artifact_dispositions`
- `cls_ops_lifecycle_contracts`
- `operations_current_state`
- `closure_current_state`
- `external_execution_contracts`
- `generated_external7_parity`
- `walksafe_contracts`
- `stable_id_manifest`
- `verification_boundary`

### Embedded semantic payload

```json
{
  "artifact_dispositions": [
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-CLS-07",
      "artifact_name": "미해결 결함",
      "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-CLS-07"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-CLS-08",
      "artifact_name": "잔여 위험",
      "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-CLS-08"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-CLS-09",
      "artifact_name": "기술부채",
      "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-CLS-09"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-CLS-10",
      "artifact_name": "운영 소유권·권한 이관",
      "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-CLS-10"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-CLS-14",
      "artifact_name": "데이터 보존·이관·삭제",
      "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-CLS-14"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-CLS-15",
      "artifact_name": "계정·키·인프라 정리",
      "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-CLS-15"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-CLS-16",
      "artifact_name": "서비스 종료·폐기 계획",
      "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-CLS-16"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-OPS-17",
      "artifact_name": "장애 기록",
      "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-OPS-17"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-OPS-19",
      "artifact_name": "운영 변경이력",
      "current_disposition": "EXTERNAL_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal schema is complete, but a real authorized decision/event/operator action and receipt are required externally.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-OPS-19"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-OPS-20",
      "artifact_name": "유지보수 Backlog",
      "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-OPS-20"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-OPS-21",
      "artifact_name": "기술부채 목록",
      "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-OPS-21"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-OPS-24",
      "artifact_name": "외부 서비스·API 의존성 현황",
      "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-OPS-24"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-WS-08",
      "artifact_name": "오탐·미탐 안전분석",
      "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-WS-08"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-WS-10",
      "artifact_name": "PT-TFLite 변환 동등성",
      "current_disposition": "INTERNAL_GAP",
      "independent_review_status": "PENDING",
      "reason": "Fixed same-input PT-TFLite comparison and approved tolerance are NOT_RUN/NOT_ESTABLISHED.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-WS-10"
    },
    {
      "approval_status": "NOT_APPROVED",
      "artifact_id": "DLV-WS-18",
      "artifact_name": "navigation provider failure/quota/exit contract",
      "current_disposition": "OK_CANDIDATE_PENDING_INDEPENDENT_REVIEW",
      "independent_review_status": "PENDING",
      "reason": "Internal content/schema is complete at current-state level; independent review is pending and no approval or execution is inferred.",
      "release_status": "NOT_ELIGIBLE",
      "stable_id": "W9-DISP-WS-18"
    }
  ],
  "authority_boundary": {
    "approval_status": "NOT_AN_APPROVAL",
    "artifact_kind": "INTERNAL_CURRENT_STATE_TRACE_AND_CANDIDATE_DISPOSITION",
    "closure_claim": false,
    "content_completeness_is_execution": false,
    "deployment_claim": false,
    "fake_event_or_receipt_allowed": false,
    "formal_pass_claim": false,
    "release_eligibility": "NOT_ELIGIBLE"
  },
  "closure_current_state": {
    "access_secret_infrastructure_disposition": {
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "execution_records": [],
      "operator": null,
      "recipient": null,
      "record_schema_fields": [
        "asset_id",
        "asset_type",
        "current_controller",
        "target_controller",
        "action_transfer_rotate_or_revoke",
        "secret_reference_id",
        "least_privilege_check",
        "recovery_boundary_check",
        "operator_identity",
        "approval_status",
        "effective_at",
        "verification_result",
        "receipt_ref"
      ],
      "revocation_status": "NOT_RUN",
      "rotation_status": "NOT_RUN",
      "secret_values_allowed": false,
      "transfer_status": "NOT_RUN"
    },
    "data_disposition": {
      "actual_deletion_status": "NOT_RUN",
      "actual_receipt_ids": [],
      "actual_transfer_status": "NOT_RUN",
      "approval_status": "NOT_APPROVED",
      "branch": "UNDECIDED_CONTINUE_OPERATIONS_OR_DECOMMISSION",
      "execution_records": [],
      "final_korean_notice_status": "NOT_APPROVED",
      "legal_basis_status": "NOT_APPROVED",
      "operator": null,
      "policy_ref": "docs/operations/data_retention_policy.md",
      "record_schema_fields": [
        "action_id",
        "branch",
        "data_class",
        "storage_location",
        "technical_retention_target",
        "legal_basis_status",
        "transfer_format",
        "encryption_control",
        "deletion_or_transfer_action",
        "backup_reconciliation",
        "notification_status",
        "operator_identity",
        "approval_status",
        "execution_status",
        "receipt_ref"
      ]
    },
    "decommissioning": {
      "actual_receipt_ids": [],
      "alternate_provider_validation_status": "NOT_RUN",
      "approval_status": "NOT_APPROVED",
      "authority": null,
      "decision_branch": null,
      "execution_records": [],
      "execution_status": "NOT_RUN",
      "operator": null,
      "provider_exit_test_status": "NOT_RUN",
      "record_schema_fields": [
        "decommission_id",
        "decision_branch",
        "authority_identity",
        "user_notice_status",
        "new_session_block_status",
        "data_disposition_refs",
        "provider_exit_refs",
        "account_key_infrastructure_refs",
        "monitoring_window",
        "final_verification",
        "approval_status",
        "execution_status",
        "receipt_ref"
      ]
    },
    "handover": {
      "account_and_permission_transfer_status": "NOT_RUN",
      "actual_handover_receipts": [],
      "approval_status": "NOT_APPROVED",
      "branch": "UNDECIDED_CONTINUE_OPERATIONS_OR_DECOMMISSION",
      "evidence_refs": [],
      "execution_status": "NOT_RUN",
      "operator": null,
      "recipient": null,
      "record_schema_fields": [
        "handover_id",
        "branch",
        "recipient_identity",
        "operator_identity",
        "scope",
        "account_permission_inventory_refs",
        "secret_reference_ids",
        "backlog_and_risk_refs",
        "support_boundary",
        "approval_status",
        "effective_at",
        "receipt_ref"
      ]
    },
    "residual_risks": {
      "acceptor_identity": null,
      "actual_receipt_ids": [],
      "closure_snapshot_status": "NOT_TAKEN",
      "gate_ids": [
        "GATE-PHONE-QUEUE-BYTE-LIMIT",
        "GATE-SERVER-CAPACITY-STATE-CONTRACT",
        "GATE-RAW-COLLECTION-RELEASE-REVIEW",
        "GATE-CLOUD-COST-MEASUREMENT",
        "GATE-SINGLE-ADMIN-RECOVERY-DRILL"
      ],
      "open_policy_issue_ids": [
        "ISS-POLICY-FP035-NETWORK-001"
      ],
      "record_schema_fields": [
        "risk_id",
        "source_ref",
        "severity",
        "likelihood",
        "user_data_service_impact",
        "mitigation",
        "owner_role",
        "due_condition",
        "acceptance_decision",
        "acceptor_identity",
        "effective_window",
        "evidence_refs",
        "receipt_ref"
      ],
      "risk_acceptance_records": [],
      "risk_acceptance_status": "NOT_APPROVED",
      "source_register": "docs/deliverables/06-testing/registers/residual-risks.json"
    },
    "technical_debt": {
      "acceptance_receipt_ids": [],
      "closure_snapshot_status": "NOT_TAKEN",
      "record_schema_fields": [
        "debt_id",
        "source_ref",
        "impact",
        "priority",
        "owner_role",
        "due_condition",
        "closure_criteria",
        "evidence_refs",
        "risk_acceptance_status",
        "acceptance_receipt_ref"
      ],
      "risk_acceptance_status": "NOT_APPROVED",
      "source_register": "docs/deliverables/10-operations/registers/operations-registers.json"
    },
    "unresolved_defects": {
      "formal_execution_count": 0,
      "opening_snapshot_proves_no_defects": false,
      "record_schema_fields": [
        "snapshot_id",
        "source_register",
        "as_of",
        "defect_id",
        "severity",
        "affected_release_id",
        "status",
        "owner_role",
        "target_condition",
        "evidence_refs",
        "snapshot_sha256"
      ],
      "records": [],
      "snapshot_status": "NOT_TAKEN",
      "source_register": "docs/deliverables/06-testing/registers/defects.json",
      "warning": "시험 실행 0건은 결함 0건의 품질 증거가 아니다."
    }
  },
  "cls_ops_lifecycle_contracts": {
    "CLS-07": {
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "artifact_type_id": "CLS-07",
      "content_outcome": "OK_CANDIDATE_CONTENT_COMPLETENESS",
      "current_boundary": [
        "formal_execution_count=0",
        "closure_snapshot_status=NOT_TAKEN",
        "opening snapshot의 0건은 결함 없음의 품질 증거가 아님"
      ],
      "execution_status": "NOT_RUN",
      "lifecycle_status": "OPENING_SNAPSHOT_SCHEMA_READY",
      "receipt_status": "NOT_RUN",
      "record_fields": [
        "snapshot_id",
        "source_register",
        "as_of",
        "defect_id",
        "severity",
        "affected_release_id",
        "status",
        "owner_role",
        "target_condition",
        "evidence_refs",
        "snapshot_sha256"
      ],
      "schema_status": "STRUCTURED_DRAFT_COMPLETE"
    },
    "CLS-08": {
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "artifact_type_id": "CLS-08",
      "content_outcome": "EXTERNAL_AFTER_INTERNAL",
      "current_boundary": [
        "risk_acceptance_status=NOT_APPROVED",
        "acceptor_identity=None",
        "actual acceptance receipt 없음"
      ],
      "execution_status": "NOT_RUN",
      "lifecycle_status": "RISK_REGISTER_SCHEMA_READY_ACCEPTANCE_EXTERNAL",
      "receipt_status": "NOT_RUN",
      "record_fields": [
        "risk_id",
        "source_ref",
        "severity",
        "likelihood",
        "user_data_service_impact",
        "mitigation",
        "owner_role",
        "due_condition",
        "acceptance_decision",
        "acceptor_identity",
        "effective_window",
        "evidence_refs",
        "receipt_ref"
      ],
      "schema_status": "STRUCTURED_DRAFT_COMPLETE"
    },
    "CLS-09": {
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "artifact_type_id": "CLS-09",
      "content_outcome": "OK_CANDIDATE_CONTENT_COMPLETENESS",
      "current_boundary": [
        "OPS-21 원장을 정본으로 사용",
        "closure_snapshot_status=NOT_TAKEN",
        "risk acceptance=NOT_APPROVED"
      ],
      "execution_status": "NOT_RUN",
      "lifecycle_status": "TECHNICAL_DEBT_SCHEMA_READY",
      "receipt_status": "NOT_RUN",
      "record_fields": [
        "debt_id",
        "source_ref",
        "impact",
        "priority",
        "owner_role",
        "due_condition",
        "closure_criteria",
        "evidence_refs",
        "risk_acceptance_status",
        "acceptance_receipt_ref"
      ],
      "schema_status": "STRUCTURED_DRAFT_COMPLETE"
    },
    "CLS-10": {
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "artifact_type_id": "CLS-10",
      "content_outcome": "EXTERNAL_AFTER_INTERNAL",
      "current_boundary": [
        "recipient_identity=None",
        "operator_identity=None",
        "approval_status=NOT_APPROVED",
        "actual handover=NOT_RUN"
      ],
      "execution_status": "NOT_RUN",
      "lifecycle_status": "HANDOVER_SCHEMA_READY_EXECUTION_EXTERNAL",
      "receipt_status": "NOT_RUN",
      "record_fields": [
        "handover_id",
        "branch",
        "recipient_identity",
        "operator_identity",
        "scope",
        "account_permission_inventory_refs",
        "secret_reference_ids",
        "backlog_and_risk_refs",
        "support_boundary",
        "approval_status",
        "effective_at",
        "receipt_ref"
      ],
      "schema_status": "STRUCTURED_DRAFT_COMPLETE"
    },
    "CLS-14": {
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "artifact_type_id": "CLS-14",
      "content_outcome": "EXTERNAL_AFTER_INTERNAL",
      "current_boundary": [
        "legal basis와 최종 한국어 고지=NOT_APPROVED",
        "actual transfer=NOT_RUN",
        "actual deletion=NOT_RUN",
        "external receipt 없음"
      ],
      "execution_status": "NOT_RUN",
      "lifecycle_status": "DATA_DISPOSITION_SCHEMA_READY_EXECUTION_EXTERNAL",
      "receipt_status": "NOT_RUN",
      "record_fields": [
        "action_id",
        "branch",
        "data_class",
        "storage_location",
        "technical_retention_target",
        "legal_basis_status",
        "transfer_format",
        "encryption_control",
        "deletion_or_transfer_action",
        "backup_reconciliation",
        "notification_status",
        "operator_identity",
        "approval_status",
        "execution_status",
        "receipt_ref"
      ],
      "schema_status": "STRUCTURED_DRAFT_COMPLETE"
    },
    "CLS-15": {
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "artifact_type_id": "CLS-15",
      "content_outcome": "EXTERNAL_AFTER_INTERNAL",
      "current_boundary": [
        "secret 값 기록 금지",
        "recipient/operator=None",
        "transfer/rotation/revocation=NOT_RUN",
        "approval=NOT_APPROVED"
      ],
      "execution_status": "NOT_RUN",
      "lifecycle_status": "ACCESS_SECRET_INFRA_SCHEMA_READY_EXECUTION_EXTERNAL",
      "receipt_status": "NOT_RUN",
      "record_fields": [
        "asset_id",
        "asset_type",
        "current_controller",
        "target_controller",
        "action_transfer_rotate_or_revoke",
        "secret_reference_id",
        "least_privilege_check",
        "recovery_boundary_check",
        "operator_identity",
        "approval_status",
        "effective_at",
        "verification_result",
        "receipt_ref"
      ],
      "schema_status": "STRUCTURED_DRAFT_COMPLETE"
    },
    "CLS-16": {
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "artifact_type_id": "CLS-16",
      "content_outcome": "EXTERNAL_AFTER_INTERNAL",
      "current_boundary": [
        "decommission decision/authority=None",
        "provider exit test=NOT_RUN",
        "decommission execution=NOT_RUN",
        "approval=NOT_APPROVED"
      ],
      "execution_status": "NOT_RUN",
      "lifecycle_status": "DECOMMISSION_SCHEMA_READY_EXECUTION_EXTERNAL",
      "receipt_status": "NOT_RUN",
      "record_fields": [
        "decommission_id",
        "decision_branch",
        "authority_identity",
        "user_notice_status",
        "new_session_block_status",
        "data_disposition_refs",
        "provider_exit_refs",
        "account_key_infrastructure_refs",
        "monitoring_window",
        "final_verification",
        "approval_status",
        "execution_status",
        "receipt_ref"
      ],
      "schema_status": "STRUCTURED_DRAFT_COMPLETE"
    },
    "OPS-17": {
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "artifact_type_id": "OPS-17",
      "content_outcome": "EXTERNAL_AFTER_INTERNAL",
      "current_boundary": [
        "operations_started=false",
        "incidents=[]",
        "opening snapshot의 0건은 무사건·무장애 증명이 아님"
      ],
      "execution_status": "NOT_RUN",
      "lifecycle_status": "PREOPENED_INCIDENT_REGISTER",
      "receipt_status": "NOT_RUN",
      "record_fields": [
        "incident_id",
        "severity",
        "started_at",
        "detected_at",
        "ended_at",
        "user_and_data_impact",
        "timeline",
        "containment",
        "recovery",
        "owner_role",
        "evidence_refs",
        "postmortem_status",
        "receipt_ref"
      ],
      "schema_status": "STRUCTURED_DRAFT_COMPLETE"
    },
    "OPS-19": {
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "artifact_type_id": "OPS-19",
      "content_outcome": "EXTERNAL_AFTER_INTERNAL",
      "current_boundary": [
        "operations_started=false",
        "operation_changes=[]",
        "opening snapshot의 0건은 무변경 증명이 아님"
      ],
      "execution_status": "NOT_RUN",
      "lifecycle_status": "PREOPENED_OPERATION_CHANGE_REGISTER",
      "receipt_status": "NOT_RUN",
      "record_fields": [
        "change_id",
        "requested_at",
        "requester_role",
        "risk_assessment",
        "target_scope",
        "release_or_config_ref",
        "approval_status",
        "execution_window",
        "rollback_plan_ref",
        "verification_result",
        "operator_identity",
        "evidence_refs",
        "receipt_ref"
      ],
      "schema_status": "STRUCTURED_DRAFT_COMPLETE"
    },
    "OPS-20": {
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "artifact_type_id": "OPS-20",
      "content_outcome": "OK_CANDIDATE_CONTENT_COMPLETENESS",
      "current_boundary": [
        "r020 audit/backlog에 hash 결속",
        "5개 gate와 FP-035 항목은 OPEN/NOT_RUN",
        "waiver와 completion receipt 없음"
      ],
      "execution_status": "NOT_RUN",
      "lifecycle_status": "ACTIVE_MAINTENANCE_BACKLOG_SCHEMA_READY",
      "receipt_status": "NOT_RUN",
      "record_fields": [
        "backlog_id",
        "source_type",
        "source_ref",
        "title",
        "status",
        "priority",
        "owner_role",
        "due_condition",
        "risk_if_open",
        "completion_criteria",
        "evidence_refs",
        "waiver_status",
        "approval_status",
        "receipt_ref"
      ],
      "schema_status": "STRUCTURED_DRAFT_COMPLETE"
    },
    "OPS-21": {
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "artifact_type_id": "OPS-21",
      "content_outcome": "OK_CANDIDATE_CONTENT_COMPLETENESS",
      "current_boundary": [
        "측정·dashboard·비상대응자·복구훈련 debt OPEN",
        "risk_acceptance_status=NOT_APPROVED",
        "acceptance receipt 없음"
      ],
      "execution_status": "NOT_RUN",
      "lifecycle_status": "ACTIVE_TECHNICAL_DEBT_SCHEMA_READY",
      "receipt_status": "NOT_RUN",
      "record_fields": [
        "debt_id",
        "source_ref",
        "title",
        "impact",
        "priority",
        "owner_role",
        "due_condition",
        "closure_criteria",
        "evidence_refs",
        "risk_acceptance_status",
        "acceptance_receipt_ref"
      ],
      "schema_status": "STRUCTURED_DRAFT_COMPLETE"
    },
    "OPS-24": {
      "actual_receipt_ids": [],
      "approval_status": "NOT_APPROVED",
      "artifact_type_id": "OPS-24",
      "content_outcome": "OK_CANDIDATE_CONTENT_COMPLETENESS",
      "current_boundary": [
        "TMAP quota/cost/support=NOT_ESTABLISHED",
        "live/deployment/provider-exit/alternate validation=NOT_RUN",
        "contract·receipt 원본 없음"
      ],
      "execution_status": "NOT_RUN",
      "lifecycle_status": "EXTERNAL_DEPENDENCY_SCHEMA_READY",
      "receipt_status": "NOT_RUN",
      "record_fields": [
        "dependency_id",
        "name",
        "data_boundary",
        "owner_role",
        "quota_status",
        "cost_status",
        "support_status",
        "review_due_condition",
        "monitoring_signals",
        "failure_fallback",
        "provider_exit_status",
        "alternate_validation_status",
        "evidence_refs",
        "receipt_ref"
      ],
      "schema_status": "STRUCTURED_DRAFT_COMPLETE"
    }
  },
  "exact_artifact_ids": [
    "DLV-CLS-07",
    "DLV-CLS-08",
    "DLV-CLS-09",
    "DLV-CLS-10",
    "DLV-CLS-14",
    "DLV-CLS-15",
    "DLV-CLS-16",
    "DLV-OPS-17",
    "DLV-OPS-19",
    "DLV-OPS-20",
    "DLV-OPS-21",
    "DLV-OPS-24",
    "DLV-WS-08",
    "DLV-WS-10",
    "DLV-WS-18"
  ],
  "external_execution_contracts": [
    {
      "approver_role": "프로젝트책임자",
      "artifact_type_id": "CLS-08",
      "catalog_artifact_id": "DLV-CLS-08",
      "completion_test": [
        "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 위험 ID·시나리오, 가능성·영향·노출.",
        "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
        "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
        "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
        "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
        "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
        "The named approver explicitly approves the exact scope and effective window.",
        "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
      ],
      "contract_schema": "walksafe.w9.external-execution-contract.v1",
      "current_state": {
        "acceptance_status": "NOT_APPROVED",
        "actual_event_count": 0,
        "actual_event_ids": [],
        "actual_evidence_count": 0,
        "actual_evidence_ids": [],
        "actual_receipt_count": 0,
        "actual_receipt_ids": [],
        "approval_status": "NOT_APPROVED",
        "execution_status": "NOT_RUN",
        "operator_state": "UNASSIGNED",
        "recipient_state": "UNASSIGNED"
      },
      "due_and_review": {
        "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
        "due_at": null,
        "due_condition": "BEFORE_PROJECT_CLOSURE_AND_AT_EACH_ACCEPTANCE_REVIEW",
        "review_due_at": null
      },
      "evidence_schema": {
        "catalog_fields": [
          "위험 ID·시나리오",
          "가능성·영향·노출",
          "기존 통제·효과",
          "잔여 등급",
          "모니터링·비상조치",
          "수용자·재검토일"
        ],
        "lifecycle_fields": [
          "risk_id",
          "source_ref",
          "severity",
          "likelihood",
          "user_data_service_impact",
          "mitigation",
          "owner_role",
          "due_condition",
          "acceptance_decision",
          "acceptor_identity",
          "effective_window",
          "evidence_refs",
          "receipt_ref"
        ]
      },
      "external_authority": "PROJECT_OWNER_EXPLICIT_SCOPED_ACCEPTANCE_OR_REJECTION",
      "external_trigger": "종료 시점에 수용·감시할 잔여 위험이 존재하는 경우 활성",
      "fake_event_or_receipt_allowed": false,
      "internal_action": "Record mitigation and monitoring for every residual risk, then obtain an explicit scoped acceptance or rejection from the authorized approver.",
      "normative_model": "BUILDER_INTERNAL_CANONICAL",
      "procedure": [
        "실제 잔여 위험과 activation 근거를 식별한다.",
        "모든 lifecycle field와 mitigation·monitoring을 채우거나 소유자·기한이 있는 차단 상태로 둔다.",
        "승인자가 exact scope와 effective window를 명시적으로 수용 또는 거절한다.",
        "실제 decision과 acceptor identity를 receipt hash binding으로 결속한다."
      ],
      "receipt_schema": [
        "repository_relative_path",
        "sha256",
        "byte_length",
        "source_event_or_decision_id",
        "occurred_or_effective_at",
        "operator_or_acceptor_identity"
      ],
      "release_impact": "승인된 실제 risk acceptance receipt 전에는 프로젝트 종료·인계를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
      "required_inputs": [
        "최종 인수 범위와 승인 릴리스 기준선",
        "산출물·결함·위험·부채·권한 inventory",
        "운영 수신자·계약·데이터 보존 결정"
      ],
      "responsible_role": "프로젝트관리자",
      "stable_id": "W9-EXT-CLS-08"
    },
    {
      "approver_role": "프로젝트책임자",
      "artifact_type_id": "CLS-10",
      "catalog_artifact_id": "DLV-CLS-10",
      "completion_test": [
        "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 자산·service owner, 계정·역할·접근.",
        "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
        "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
        "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
        "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
        "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
        "The named approver explicitly approves the exact scope and effective window.",
        "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
      ],
      "contract_schema": "walksafe.w9.external-execution-contract.v1",
      "current_state": {
        "acceptance_status": "NOT_APPROVED",
        "actual_event_count": 0,
        "actual_event_ids": [],
        "actual_evidence_count": 0,
        "actual_evidence_ids": [],
        "actual_receipt_count": 0,
        "actual_receipt_ids": [],
        "approval_status": "NOT_APPROVED",
        "execution_status": "NOT_RUN",
        "operator_state": "UNASSIGNED",
        "recipient_state": "UNASSIGNED"
      },
      "due_and_review": {
        "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
        "due_at": null,
        "due_condition": "BEFORE_OPERATIONAL_RESPONSIBILITY_TRANSFER_EFFECTIVE_AT",
        "review_due_at": null
      },
      "evidence_schema": {
        "catalog_fields": [
          "자산·service owner",
          "계정·역할·접근",
          "credential 회전·전달 방식",
          "runbook·지원·연락",
          "권한 test·회수",
          "양측 확인·일시"
        ],
        "lifecycle_fields": [
          "handover_id",
          "branch",
          "recipient_identity",
          "operator_identity",
          "scope",
          "account_permission_inventory_refs",
          "secret_reference_ids",
          "backlog_and_risk_refs",
          "support_boundary",
          "approval_status",
          "effective_at",
          "receipt_ref"
        ]
      },
      "external_authority": "PROJECT_OWNER_AND_NAMED_RECIPIENT_BILATERAL_CONFIRMATION",
      "external_trigger": "서비스·자산·계정의 운영 책임을 다른 주체에게 이관할 때 활성",
      "fake_event_or_receipt_allowed": false,
      "internal_action": "Execute ownership and least-privilege handover with named recipient/operator, verification, revocation boundary and bilateral confirmation.",
      "normative_model": "BUILDER_INTERNAL_CANONICAL",
      "procedure": [
        "실제 이관 decision과 named recipient/operator를 확인한다.",
        "자산·계정·권한·backlog·risk·support 범위를 최소권한으로 대조한다.",
        "전달·검증·기존 권한 회수 경계를 양측이 확인한다.",
        "effective time과 양측 identity를 실제 handover receipt에 hash 결속한다."
      ],
      "receipt_schema": [
        "repository_relative_path",
        "sha256",
        "byte_length",
        "source_event_or_decision_id",
        "occurred_or_effective_at",
        "operator_or_acceptor_identity"
      ],
      "release_impact": "named recipient의 실제 양측 인계 receipt 전에는 운영 이관·프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
      "required_inputs": [
        "최종 인수 범위와 승인 릴리스 기준선",
        "산출물·결함·위험·부채·권한 inventory",
        "운영 수신자·계약·데이터 보존 결정"
      ],
      "responsible_role": "프로젝트관리자",
      "stable_id": "W9-EXT-CLS-10"
    },
    {
      "approver_role": "프로젝트책임자",
      "artifact_type_id": "CLS-14",
      "catalog_artifact_id": "DLV-CLS-14",
      "completion_test": [
        "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 데이터 inventory·분류, 보존 근거·기간.",
        "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
        "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
        "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
        "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
        "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
        "The named approver explicitly approves the exact scope and effective window.",
        "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
      ],
      "contract_schema": "walksafe.w9.external-execution-contract.v1",
      "current_state": {
        "acceptance_status": "NOT_APPROVED",
        "actual_event_count": 0,
        "actual_event_ids": [],
        "actual_evidence_count": 0,
        "actual_evidence_ids": [],
        "actual_receipt_count": 0,
        "actual_receipt_ids": [],
        "approval_status": "NOT_APPROVED",
        "execution_status": "NOT_RUN",
        "operator_state": "UNASSIGNED",
        "recipient_state": "UNASSIGNED"
      },
      "due_and_review": {
        "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
        "due_at": null,
        "due_condition": "BEFORE_EACH_TRANSFER_OR_DELETION_AND_PROJECT_CLOSURE",
        "review_due_at": null
      },
      "evidence_schema": {
        "catalog_fields": [
          "데이터 inventory·분류",
          "보존 근거·기간",
          "이관 대상·형식·암호화",
          "삭제·익명화·backup",
          "사용자·기관 통지",
          "검증·승인 기록",
          "선행 승인된 CLS-16 폐기계획 ID와 데이터 보존·이관·삭제 실행 receipt"
        ],
        "lifecycle_fields": [
          "action_id",
          "branch",
          "data_class",
          "storage_location",
          "technical_retention_target",
          "legal_basis_status",
          "transfer_format",
          "encryption_control",
          "deletion_or_transfer_action",
          "backup_reconciliation",
          "notification_status",
          "operator_identity",
          "approval_status",
          "execution_status",
          "receipt_ref"
        ]
      },
      "external_authority": "PROJECT_OWNER_APPROVED_DATA_BRANCH_WITH_APPLICABLE_LEGAL_AUTHORITY",
      "external_trigger": "종료 시 보존·이관·삭제할 데이터가 존재하는 경우 활성",
      "fake_event_or_receipt_allowed": false,
      "internal_action": "Execute the approved retention, transfer or deletion branch for each data class and reconcile backups and notifications.",
      "normative_model": "BUILDER_INTERNAL_CANONICAL",
      "procedure": [
        "각 data class·storage location의 승인된 branch와 법적 권한을 확인한다.",
        "보존·이관·삭제, encryption, backup reconciliation, notification을 class별 실행한다.",
        "operator와 승인 범위 및 미처리 예외를 검증한다.",
        "실제 transfer/deletion event와 receipt를 hash 결속한다."
      ],
      "receipt_schema": [
        "repository_relative_path",
        "sha256",
        "byte_length",
        "source_event_or_decision_id",
        "occurred_or_effective_at",
        "operator_or_acceptor_identity"
      ],
      "release_impact": "법적 근거·고지 승인과 실제 class별 receipt 전에는 데이터 처분·프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
      "required_inputs": [
        "최종 인수 범위와 승인 릴리스 기준선",
        "산출물·결함·위험·부채·권한 inventory",
        "운영 수신자·계약·데이터 보존 결정"
      ],
      "responsible_role": "프로젝트관리자",
      "stable_id": "W9-EXT-CLS-14"
    },
    {
      "approver_role": "프로젝트책임자",
      "artifact_type_id": "CLS-15",
      "catalog_artifact_id": "DLV-CLS-15",
      "completion_test": [
        "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 자산·계정 inventory, 유지·이관·폐기 판정.",
        "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
        "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
        "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
        "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
        "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
        "The named approver explicitly approves the exact scope and effective window.",
        "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
      ],
      "contract_schema": "walksafe.w9.external-execution-contract.v1",
      "current_state": {
        "acceptance_status": "NOT_APPROVED",
        "actual_event_count": 0,
        "actual_event_ids": [],
        "actual_evidence_count": 0,
        "actual_evidence_ids": [],
        "actual_receipt_count": 0,
        "actual_receipt_ids": [],
        "approval_status": "NOT_APPROVED",
        "execution_status": "NOT_RUN",
        "operator_state": "UNASSIGNED",
        "recipient_state": "UNASSIGNED"
      },
      "due_and_review": {
        "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
        "due_at": null,
        "due_condition": "BEFORE_EACH_TRANSFER_ROTATION_OR_REVOCATION",
        "review_due_at": null
      },
      "evidence_schema": {
        "catalog_fields": [
          "자산·계정 inventory",
          "유지·이관·폐기 판정",
          "key·certificate 회전",
          "서버·storage·DNS 종료",
          "backup·로그 보존",
          "검증·비용 종료",
          "선행 승인된 CLS-16 폐기계획 ID와 계정·키·인프라 정리 실행 receipt"
        ],
        "lifecycle_fields": [
          "asset_id",
          "asset_type",
          "current_controller",
          "target_controller",
          "action_transfer_rotate_or_revoke",
          "secret_reference_id",
          "least_privilege_check",
          "recovery_boundary_check",
          "operator_identity",
          "approval_status",
          "effective_at",
          "verification_result",
          "receipt_ref"
        ]
      },
      "external_authority": "PROJECT_OWNER_AUTHORIZED_ASSET_RETAIN_TRANSFER_ROTATE_OR_REVOKE_DECISION",
      "external_trigger": "프로젝트 전용 계정·key·도메인·인프라가 존재하는 경우 활성",
      "fake_event_or_receipt_allowed": false,
      "internal_action": "Execute the authorized retain/transfer/rotate/revoke decision for every account, key and infrastructure asset without recording secret values.",
      "normative_model": "BUILDER_INTERNAL_CANONICAL",
      "procedure": [
        "실제 asset inventory와 authorized action을 확인하되 secret 값은 기록하지 않는다.",
        "retain/transfer/rotate/revoke를 최소권한·복구수단 분리 경계에서 실행한다.",
        "target controller, operator, effective time과 검증 결과를 확인한다.",
        "실제 asset action과 receipt를 hash 결속한다."
      ],
      "receipt_schema": [
        "repository_relative_path",
        "sha256",
        "byte_length",
        "source_event_or_decision_id",
        "occurred_or_effective_at",
        "operator_or_acceptor_identity"
      ],
      "release_impact": "모든 asset의 승인 action과 실제 receipt 전에는 권한·인프라 정리나 프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
      "required_inputs": [
        "최종 인수 범위와 승인 릴리스 기준선",
        "산출물·결함·위험·부채·권한 inventory",
        "운영 수신자·계약·데이터 보존 결정"
      ],
      "responsible_role": "프로젝트관리자",
      "stable_id": "W9-EXT-CLS-15"
    },
    {
      "approver_role": "프로젝트책임자",
      "artifact_type_id": "CLS-16",
      "catalog_artifact_id": "DLV-CLS-16",
      "completion_test": [
        "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 종료 사유·범위·일정, 사용자·이해관계자 고지.",
        "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
        "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
        "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
        "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
        "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
        "The named approver explicitly approves the exact scope and effective window.",
        "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
      ],
      "contract_schema": "walksafe.w9.external-execution-contract.v1",
      "current_state": {
        "acceptance_status": "NOT_APPROVED",
        "actual_event_count": 0,
        "actual_event_ids": [],
        "actual_evidence_count": 0,
        "actual_evidence_ids": [],
        "actual_receipt_count": 0,
        "actual_receipt_ids": [],
        "approval_status": "NOT_APPROVED",
        "execution_status": "NOT_RUN",
        "operator_state": "UNASSIGNED",
        "recipient_state": "UNASSIGNED"
      },
      "due_and_review": {
        "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
        "due_at": null,
        "due_condition": "BEFORE_DECOMMISSION_AUTHORIZATION_AND_EXECUTION",
        "review_due_at": null
      },
      "evidence_schema": {
        "catalog_fields": [
          "종료 사유·범위·일정",
          "사용자·이해관계자 고지",
          "기능·신규가입 단계 종료",
          "데이터 export·삭제",
          "인프라·계정 철거",
          "지원 종료·최종 검증",
          "CLS-14 데이터 처리와 CLS-15 계정·키·인프라 정리 전에 승인할 폐기 순서·중단점·책임자"
        ],
        "lifecycle_fields": [
          "decommission_id",
          "decision_branch",
          "authority_identity",
          "user_notice_status",
          "new_session_block_status",
          "data_disposition_refs",
          "provider_exit_refs",
          "account_key_infrastructure_refs",
          "monitoring_window",
          "final_verification",
          "approval_status",
          "execution_status",
          "receipt_ref"
        ]
      },
      "external_authority": "PROJECT_OWNER_APPROVED_SERVICE_SHUTDOWN_OR_DECOMMISSION_DECISION",
      "external_trigger": "서비스 중단 또는 완전 폐기가 승인된 경우 활성",
      "fake_event_or_receipt_allowed": false,
      "internal_action": "Approve and execute the ordered service shutdown/decommission branch with notices, data rights, provider exit, checkpoints and final verification.",
      "normative_model": "BUILDER_INTERNAL_CANONICAL",
      "procedure": [
        "실제 폐기 권한·범위·순서·중단점을 승인한다.",
        "사용자 고지와 신규세션 차단 뒤 CLS-14, CLS-15, provider exit를 순서대로 실행한다.",
        "monitoring window와 final verification에서 잔여 접근·자료·비용을 확인한다.",
        "실제 decommission decision, operator와 receipt를 hash 결속한다."
      ],
      "receipt_schema": [
        "repository_relative_path",
        "sha256",
        "byte_length",
        "source_event_or_decision_id",
        "occurred_or_effective_at",
        "operator_or_acceptor_identity"
      ],
      "release_impact": "승인된 폐기 decision과 모든 단계의 실제 receipt 전에는 service decommission·프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
      "required_inputs": [
        "최종 인수 범위와 승인 릴리스 기준선",
        "산출물·결함·위험·부채·권한 inventory",
        "운영 수신자·계약·데이터 보존 결정"
      ],
      "responsible_role": "프로젝트관리자",
      "stable_id": "W9-EXT-CLS-16"
    },
    {
      "approver_role": "서비스소유자",
      "artifact_type_id": "OPS-17",
      "catalog_artifact_id": "DLV-OPS-17",
      "completion_test": [
        "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: incident ID·severity, 탐지·시작·종료 시간.",
        "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
        "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
        "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
        "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
        "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
        "The named approver explicitly approves the exact scope and effective window.",
        "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
      ],
      "contract_schema": "walksafe.w9.external-execution-contract.v1",
      "current_state": {
        "acceptance_status": "NOT_APPROVED",
        "actual_event_count": 0,
        "actual_event_ids": [],
        "actual_evidence_count": 0,
        "actual_evidence_ids": [],
        "actual_receipt_count": 0,
        "actual_receipt_ids": [],
        "approval_status": "NOT_APPROVED",
        "execution_status": "NOT_RUN",
        "operator_state": "UNASSIGNED",
        "recipient_state": "UNASSIGNED"
      },
      "due_and_review": {
        "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
        "due_at": null,
        "due_condition": "ON_EACH_REAL_INCIDENT_BEFORE_INCIDENT_CLOSURE",
        "review_due_at": null
      },
      "evidence_schema": {
        "catalog_fields": [
          "incident ID·severity",
          "탐지·시작·종료 시간",
          "사용자·데이터 영향",
          "조치 timeline·담당",
          "원인 후보·증거",
          "복구·후속·통지"
        ],
        "lifecycle_fields": [
          "incident_id",
          "severity",
          "started_at",
          "detected_at",
          "ended_at",
          "user_and_data_impact",
          "timeline",
          "containment",
          "recovery",
          "owner_role",
          "evidence_refs",
          "postmortem_status",
          "receipt_ref"
        ]
      },
      "external_authority": "REAL_INCIDENT_EVENT_AND_SERVICE_OWNER_POSTMORTEM_APPROVAL",
      "external_trigger": "운영 준비 시 장애 원장 구조를 사전 개설하고, 사용자·데이터·SLO에 영향을 준 장애가 발생할 때마다 실행 행을 추가",
      "fake_event_or_receipt_allowed": false,
      "internal_action": "Keep the preopened register empty until a real incident occurs; then add one complete real incident row and approved postmortem receipt.",
      "normative_model": "BUILDER_INTERNAL_CANONICAL",
      "procedure": [
        "실제 incident가 발생하기 전에는 opening register를 비워 둔다.",
        "발생 시 timeline·impact·containment·recovery·owner·evidence lifecycle field를 실제 값으로 기록한다.",
        "서비스소유자가 exact incident scope와 postmortem을 승인한다.",
        "실제 incident event, operator와 postmortem receipt를 hash 결속한다."
      ],
      "receipt_schema": [
        "repository_relative_path",
        "sha256",
        "byte_length",
        "source_event_or_decision_id",
        "occurred_or_effective_at",
        "operator_or_acceptor_identity"
      ],
      "release_impact": "실제 incident가 발생하면 승인된 postmortem receipt 전까지 운영·관련 release 판정을 닫을 수 없으며 현재 release는 NOT_ELIGIBLE이다.",
      "required_inputs": [
        "승인된 릴리스·배포 아키텍처·SLO",
        "운영 환경·계정·외부 서비스·관측성 현황",
        "지원·보안·복구 책임과 연락 체계"
      ],
      "responsible_role": "운영책임자",
      "stable_id": "W9-EXT-OPS-17"
    },
    {
      "approver_role": "서비스소유자",
      "artifact_type_id": "OPS-19",
      "catalog_artifact_id": "DLV-OPS-19",
      "completion_test": [
        "다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: 변경 ID·대상·전후, 사유·위험·영향.",
        "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
        "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
        "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
        "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
        "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
        "The named approver explicitly approves the exact scope and effective window.",
        "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity."
      ],
      "contract_schema": "walksafe.w9.external-execution-contract.v1",
      "current_state": {
        "acceptance_status": "NOT_APPROVED",
        "actual_event_count": 0,
        "actual_event_ids": [],
        "actual_evidence_count": 0,
        "actual_evidence_ids": [],
        "actual_receipt_count": 0,
        "actual_receipt_ids": [],
        "approval_status": "NOT_APPROVED",
        "execution_status": "NOT_RUN",
        "operator_state": "UNASSIGNED",
        "recipient_state": "UNASSIGNED"
      },
      "due_and_review": {
        "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
        "due_at": null,
        "due_condition": "BEFORE_EACH_REAL_OPERATIONAL_CHANGE_EXECUTION",
        "review_due_at": null
      },
      "evidence_schema": {
        "catalog_fields": [
          "변경 ID·대상·전후",
          "사유·위험·영향",
          "승인·window·담당",
          "실행 commit·명령",
          "검증·관찰",
          "rollback·incident 연결"
        ],
        "lifecycle_fields": [
          "change_id",
          "requested_at",
          "requester_role",
          "risk_assessment",
          "target_scope",
          "release_or_config_ref",
          "approval_status",
          "execution_window",
          "rollback_plan_ref",
          "verification_result",
          "operator_identity",
          "evidence_refs",
          "receipt_ref"
        ]
      },
      "external_authority": "SERVICE_OWNER_AUTHORIZED_REAL_OPERATIONAL_CHANGE",
      "external_trigger": "운영 준비 시 변경 원장 구조를 사전 개설하고, 코드 외 설정·권한·인프라 변경 때마다 실행 행을 추가",
      "fake_event_or_receipt_allowed": false,
      "internal_action": "Keep the preopened register empty until a real operational change occurs; then add one authorized change row with rollback, verification and receipt.",
      "normative_model": "BUILDER_INTERNAL_CANONICAL",
      "procedure": [
        "실제 operational change 전에는 opening register를 비워 둔다.",
        "change 대상·위험·승인·window·rollback·verification lifecycle field를 채운다.",
        "서비스소유자가 실행 전 exact scope와 effective window를 승인한다.",
        "실제 change event, operator와 execution receipt를 hash 결속한다."
      ],
      "receipt_schema": [
        "repository_relative_path",
        "sha256",
        "byte_length",
        "source_event_or_decision_id",
        "occurred_or_effective_at",
        "operator_or_acceptor_identity"
      ],
      "release_impact": "승인·rollback·검증·실제 receipt 없는 운영 변경은 실행할 수 없고 관련 배포·release는 차단되며 현재 release는 NOT_ELIGIBLE이다.",
      "required_inputs": [
        "승인된 릴리스·배포 아키텍처·SLO",
        "운영 환경·계정·외부 서비스·관측성 현황",
        "지원·보안·복구 책임과 연락 체계"
      ],
      "responsible_role": "운영책임자",
      "stable_id": "W9-EXT-OPS-19"
    }
  ],
  "generated_external7_parity": {
    "contract_schema": "walksafe.w9.external-execution-contract.v1",
    "exact_lossless_overlay_parity": true,
    "generated_contract_count": 7,
    "generated_contract_fingerprint": "9d09cedad2bf7ae0cb78bcc83799590fe2f3096298203acf42a9e62bef934d1a",
    "generated_json_domain_exact_object_parity": true,
    "generated_manifest_source": {
      "byte_length": 125261,
      "json_pointer": "/w9_content_assessment/external_execution_contracts",
      "normative_model": "BUILDER_INTERNAL_CANONICAL",
      "path": "docs/deliverables/manifests/rel-ops-cls-draft-20260721-r001.json",
      "sha256": "c02986f83e36140903f5d001cfb32fe36902dad28753d37d6ffe7f98e70c4c1a"
    },
    "generated_markdown_critical_field_lossless_parity": true,
    "overlay_contract_count": 7,
    "overlay_contract_fingerprint": "9d09cedad2bf7ae0cb78bcc83799590fe2f3096298203acf42a9e62bef934d1a",
    "per_generated_artifact": [
      {
        "byte_length": 57361,
        "lossless_contract_ids": [
          "W9-EXT-OPS-17",
          "W9-EXT-OPS-19"
        ],
        "path": "docs/deliverables/10-operations/operations-control-registers.md",
        "reference_only_contract_ids": [],
        "referenced_external_stable_ids": [
          "W9-EXT-OPS-17",
          "W9-EXT-OPS-19"
        ],
        "representation": "MARKDOWN_LOSSLESS_CRITICAL_FIELDS",
        "sha256": "e5f67afa7aa1a238f58624d80038443461ddecec1d821bc66216b774e00237b2"
      },
      {
        "byte_length": 32049,
        "lossless_contract_ids": [
          "W9-EXT-OPS-17",
          "W9-EXT-OPS-19"
        ],
        "path": "docs/deliverables/10-operations/registers/operations-registers.json",
        "reference_only_contract_ids": [],
        "referenced_external_stable_ids": [
          "W9-EXT-OPS-17",
          "W9-EXT-OPS-19"
        ],
        "representation": "JSON_EXACT_OBJECT",
        "sha256": "8cdf279aebb020a94161414d75a219cc0f9dd926ce86235402185e3ab920fa2a"
      },
      {
        "byte_length": 35094,
        "lossless_contract_ids": [
          "W9-EXT-CLS-08",
          "W9-EXT-CLS-10"
        ],
        "path": "docs/deliverables/12-closure/closure-handover-register.md",
        "reference_only_contract_ids": [],
        "referenced_external_stable_ids": [
          "W9-EXT-CLS-08",
          "W9-EXT-CLS-10"
        ],
        "representation": "MARKDOWN_LOSSLESS_CRITICAL_FIELDS",
        "sha256": "35ae3e98cb563f7e23046c5e5f26ddd152334d320f4182b90cf8c34904f80fcd"
      },
      {
        "byte_length": 33318,
        "lossless_contract_ids": [
          "W9-EXT-CLS-14",
          "W9-EXT-CLS-15",
          "W9-EXT-CLS-16"
        ],
        "path": "docs/deliverables/12-closure/decommissioning-plan.md",
        "reference_only_contract_ids": [],
        "referenced_external_stable_ids": [
          "W9-EXT-CLS-14",
          "W9-EXT-CLS-15",
          "W9-EXT-CLS-16"
        ],
        "representation": "MARKDOWN_LOSSLESS_CRITICAL_FIELDS",
        "sha256": "1cbebd3daec7b862874af8705f5a92d13f770f847cd5a39838a764ee7d67e3ce"
      },
      {
        "byte_length": 35802,
        "lossless_contract_ids": [
          "W9-EXT-CLS-08",
          "W9-EXT-CLS-10",
          "W9-EXT-CLS-14",
          "W9-EXT-CLS-15",
          "W9-EXT-CLS-16"
        ],
        "path": "docs/deliverables/12-closure/registers/closure-readiness-register.json",
        "reference_only_contract_ids": [],
        "referenced_external_stable_ids": [
          "W9-EXT-CLS-08",
          "W9-EXT-CLS-10",
          "W9-EXT-CLS-14",
          "W9-EXT-CLS-15",
          "W9-EXT-CLS-16"
        ],
        "representation": "JSON_EXACT_OBJECT",
        "sha256": "566ec31ed9d405604e914d4f8ed02f3875a420f9f9f7458376851d921d2daf46"
      },
      {
        "byte_length": 125261,
        "lossless_contract_ids": [
          "W9-EXT-CLS-08",
          "W9-EXT-CLS-10",
          "W9-EXT-CLS-14",
          "W9-EXT-CLS-15",
          "W9-EXT-CLS-16",
          "W9-EXT-OPS-17",
          "W9-EXT-OPS-19"
        ],
        "path": "docs/deliverables/manifests/rel-ops-cls-draft-20260721-r001.json",
        "reference_only_contract_ids": [],
        "referenced_external_stable_ids": [
          "W9-EXT-CLS-08",
          "W9-EXT-CLS-10",
          "W9-EXT-CLS-14",
          "W9-EXT-CLS-15",
          "W9-EXT-CLS-16",
          "W9-EXT-OPS-17",
          "W9-EXT-OPS-19"
        ],
        "representation": "JSON_EXACT_OBJECT",
        "sha256": "c02986f83e36140903f5d001cfb32fe36902dad28753d37d6ffe7f98e70c4c1a"
      }
    ],
    "required_contract_fields": [
      "stable_id",
      "artifact_type_id",
      "catalog_artifact_id",
      "contract_schema",
      "normative_model",
      "responsible_role",
      "approver_role",
      "external_authority",
      "external_trigger",
      "required_inputs",
      "internal_action",
      "procedure",
      "evidence_schema",
      "receipt_schema",
      "completion_test",
      "due_and_review",
      "current_state",
      "fake_event_or_receipt_allowed",
      "release_impact"
    ],
    "stable_ids": [
      "W9-EXT-CLS-08",
      "W9-EXT-CLS-10",
      "W9-EXT-CLS-14",
      "W9-EXT-CLS-15",
      "W9-EXT-CLS-16",
      "W9-EXT-OPS-17",
      "W9-EXT-OPS-19"
    ]
  },
  "generation_evidence": {
    "acceptance_evidence": false,
    "classification": "HISTORICAL_AUTHOR_OBSERVATION_NOT_ACCEPTANCE_EVIDENCE",
    "historical_observations": [
      {
        "acceptance_evidence": false,
        "classification": "OBSERVATION_ONLY",
        "in_memory_diff_observation": "2_OF_2_OBSERVED",
        "scope": "COMMON_SOURCE"
      },
      {
        "acceptance_evidence": false,
        "builder_check_observation": "REVIEW_RECORDED_PASS_ONLY",
        "classification": "OBSERVATION_ONLY",
        "in_memory_diff_observation": "6_OF_6_OBSERVED",
        "scope": "CLS_OPS",
        "targeted_test_observation": "3_OF_3_REVIEW_RECORDED_PASS_ONLY"
      },
      {
        "acceptance_evidence": false,
        "builder_check_observation": "REVIEW_RECORDED_PASS_ONLY",
        "classification": "OBSERVATION_ONLY",
        "in_memory_diff_observation": "2_OF_2_OBSERVED",
        "scope": "WALKSAFE",
        "targeted_test_observation": "3_OF_3_REVIEW_RECORDED_PASS_ONLY"
      }
    ],
    "missing_provenance": [
      "LOCAL_TEST_NODE_IDS_NOT_PRESERVED",
      "DIFF_BASELINE_AND_PREIMAGE_NOT_PRESERVED",
      "STANDALONE_COMMAND_LOG_NOT_PRESERVED",
      "EXECUTION_RECEIPT_NOT_PRESERVED"
    ],
    "prohibited_use": [
      "ACCEPTANCE_EVIDENCE",
      "CURRENT_TRANSITION_AUTHORITY",
      "FINAL_GO_AUTHORITY"
    ]
  },
  "operations_current_state": {
    "approval_boundary": {
      "actual_execution_receipt_count": 0,
      "incident_count": 0,
      "operation_change_count": 0,
      "operations_started": false,
      "postmortem_count": 0,
      "registers_preopened": true,
      "release_status": "NOT_ELIGIBLE",
      "restore_execution_count": 0
    },
    "external_dependencies": [
      {
        "alternate_validation_status": "NOT_RUN",
        "cost_status": "NOT_ESTABLISHED",
        "data_boundary": "route request/response only; approved proxy boundary",
        "dependency_id": "EXT-TMAP",
        "evidence_refs": [],
        "failure_fallback": "새 경로 요청 중지·stale route 금지·사용자 안전행동 안내",
        "monitoring_signals": [
          "error taxonomy별 count",
          "4-second timeout count",
          "quota rejection count"
        ],
        "name": "TMAP route/map API",
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "provider_exit_status": "NOT_RUN",
        "quota_and_failure_validation": "NOT_RUN",
        "quota_status": "NOT_ESTABLISHED",
        "receipt_ref": null,
        "review_due_condition": "BEFORE_LIVE_TMAP_OR_NAMED_RELEASE",
        "support_status": "NOT_ESTABLISHED"
      },
      {
        "alternate_validation_status": "NOT_RUN",
        "cost_status": "PROJECT_LIMIT_30000_KRW_NOT_PROVIDER_CONTRACT",
        "data_boundary": "암호화 원본과 최소 metadata",
        "dependency_id": "EXT-OBJECT-STORAGE",
        "evidence_refs": [],
        "failure_fallback": "새 수집 단계별 보류; 기존 암호화 자료·실시간 기능 유지",
        "monitoring_signals": [
          "primary capacity percent",
          "backup age",
          "deletion reconciliation lag"
        ],
        "name": "서울 리전 원본·backup object storage",
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "provider_exit_status": "NOT_RUN",
        "quota_and_failure_validation": "NOT_RUN",
        "quota_status": "NOT_ESTABLISHED",
        "receipt_ref": null,
        "review_due_condition": "BEFORE_PRODUCTION_OPERATION",
        "support_status": "NOT_ESTABLISHED"
      },
      {
        "alternate_validation_status": "NOT_RUN",
        "cost_status": "NOT_ESTABLISHED",
        "data_boundary": "민감 원본·인증증명 없는 최소 장애정보",
        "dependency_id": "EXT-ALERT-PATHS",
        "evidence_refs": [],
        "failure_fallback": "서로 다른 대체 연락경로와 자동 신규세션 차단",
        "monitoring_signals": [
          "delivery failure",
          "acknowledgment delay",
          "alternate path failure"
        ],
        "name": "관리자·비상대응자 경보 경로",
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "provider_exit_status": "NOT_RUN",
        "quota_and_failure_validation": "NOT_RUN",
        "quota_status": "NOT_ESTABLISHED",
        "receipt_ref": null,
        "review_due_condition": "BEFORE_PRODUCTION_OPERATION",
        "support_status": "NOT_ESTABLISHED"
      },
      {
        "alternate_validation_status": "NOT_RUN",
        "cost_status": "NOT_ESTABLISHED",
        "data_boundary": "signed release artifact와 최소 배포 metadata",
        "dependency_id": "EXT-ANDROID-DISTRIBUTION",
        "deployment_descriptor_ref": "model/deployments/local-deployment.json",
        "evidence_refs": [],
        "failure_fallback": "배포 중단; unsigned APK나 Web/PWA로 대체하지 않음",
        "monitoring_signals": [
          "artifact hash mismatch",
          "installation failure",
          "rollout halt"
        ],
        "name": "Android build·distribution path",
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "provider_exit_status": "NOT_RUN",
        "quota_and_failure_validation": "NOT_RUN",
        "quota_status": "NOT_ESTABLISHED",
        "receipt_ref": null,
        "review_due_condition": "BEFORE_NAMED_RELEASE_CANDIDATE",
        "support_status": "NOT_ESTABLISHED"
      }
    ],
    "incidents": [],
    "maintenance_backlog": [
      {
        "approval_status": "NOT_APPROVED",
        "backlog_id": "OPS-BL-GATE-01",
        "completion_criteria": "gate의 요구 증거·판정·승인이 같은 instance에 결속되어야 한다.",
        "due_condition": "BEFORE_NAMED_RELEASE_CANDIDATE",
        "evidence_refs": [],
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "priority": "P0_RELEASE_BLOCKING",
        "receipt_ref": null,
        "risk_if_open": "release remains NOT_ELIGIBLE",
        "source_gate_id": "GATE-PHONE-QUEUE-BYTE-LIMIT",
        "source_ref": "policy.remaining_gates[GATE-PHONE-QUEUE-BYTE-LIMIT]",
        "source_type": "REMAINING_GATE",
        "status": "NOT_RUN",
        "title": "휴대전화 대기자료의 실제 용량 한도",
        "waived": false,
        "waiver_status": "NOT_APPROVED"
      },
      {
        "approval_status": "NOT_APPROVED",
        "backlog_id": "OPS-BL-GATE-02",
        "completion_criteria": "gate의 요구 증거·판정·승인이 같은 instance에 결속되어야 한다.",
        "due_condition": "BEFORE_NAMED_RELEASE_CANDIDATE",
        "evidence_refs": [],
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "priority": "P0_RELEASE_BLOCKING",
        "receipt_ref": null,
        "risk_if_open": "release remains NOT_ELIGIBLE",
        "source_gate_id": "GATE-SERVER-CAPACITY-STATE-CONTRACT",
        "source_ref": "policy.remaining_gates[GATE-SERVER-CAPACITY-STATE-CONTRACT]",
        "source_type": "REMAINING_GATE",
        "status": "NOT_RUN",
        "title": "서버 용량상태를 휴대전화에 전달하는 규칙",
        "waived": false,
        "waiver_status": "NOT_APPROVED"
      },
      {
        "approval_status": "NOT_APPROVED",
        "backlog_id": "OPS-BL-GATE-03",
        "completion_criteria": "gate의 요구 증거·판정·승인이 같은 instance에 결속되어야 한다.",
        "due_condition": "BEFORE_NAMED_RELEASE_CANDIDATE",
        "evidence_refs": [],
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "priority": "P0_RELEASE_BLOCKING",
        "receipt_ref": null,
        "risk_if_open": "release remains NOT_ELIGIBLE",
        "source_gate_id": "GATE-RAW-COLLECTION-RELEASE-REVIEW",
        "source_ref": "policy.remaining_gates[GATE-RAW-COLLECTION-RELEASE-REVIEW]",
        "source_type": "REMAINING_GATE",
        "status": "NOT_RUN",
        "title": "무가림 원본 수집의 출시 전 독립 검토",
        "waived": false,
        "waiver_status": "NOT_APPROVED"
      },
      {
        "approval_status": "NOT_APPROVED",
        "backlog_id": "OPS-BL-GATE-04",
        "completion_criteria": "gate의 요구 증거·판정·승인이 같은 instance에 결속되어야 한다.",
        "due_condition": "BEFORE_NAMED_RELEASE_CANDIDATE",
        "evidence_refs": [],
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "priority": "P0_RELEASE_BLOCKING",
        "receipt_ref": null,
        "risk_if_open": "release remains NOT_ELIGIBLE",
        "source_gate_id": "GATE-CLOUD-COST-MEASUREMENT",
        "source_ref": "policy.remaining_gates[GATE-CLOUD-COST-MEASUREMENT]",
        "source_type": "REMAINING_GATE",
        "status": "NOT_RUN",
        "title": "실제 클라우드 저장비 측정",
        "waived": false,
        "waiver_status": "NOT_APPROVED"
      },
      {
        "approval_status": "NOT_APPROVED",
        "backlog_id": "OPS-BL-GATE-05",
        "completion_criteria": "gate의 요구 증거·판정·승인이 같은 instance에 결속되어야 한다.",
        "due_condition": "BEFORE_NAMED_RELEASE_CANDIDATE",
        "evidence_refs": [],
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "priority": "P0_RELEASE_BLOCKING",
        "receipt_ref": null,
        "risk_if_open": "release remains NOT_ELIGIBLE",
        "source_gate_id": "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
        "source_ref": "policy.remaining_gates[GATE-SINGLE-ADMIN-RECOVERY-DRILL]",
        "source_type": "REMAINING_GATE",
        "status": "NOT_RUN",
        "title": "관리자 휴대전화 분실 복구훈련",
        "waived": false,
        "waiver_status": "NOT_APPROVED"
      },
      {
        "approval_status": "NOT_APPROVED",
        "backlog_id": "OPS-BL-FP035-001",
        "completion_criteria": "새 묶음 승인과 관련 시험 증거가 동일 정책 generation에 결속되어야 한다.",
        "due_condition": "BEFORE_NAMED_RELEASE_CANDIDATE",
        "evidence_refs": [],
        "normalized_policy": {
          "stopped_mobile_not_opted_in": "이동통신망 전송을 선택하지 않은 사용자는 정지 뒤에도 Wi-Fi에서만 전송한다.",
          "stopped_mobile_opted_in": "사용자가 이동통신망 전송을 명시적으로 선택한 경우에만 정지 판정 뒤 이동통신망 전송을 허용한다.",
          "walking": "일반 활동원본은 보행 중 휴대전화에 암호화해 저장하고 서버로 전송하지 않는다."
        },
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "priority": "P0_RELEASE_BLOCKING",
        "receipt_ref": null,
        "risk_if_open": "후보 정책은 NOT_EFFECTIVE이며 관련 release 시험을 시작할 수 없다.",
        "source_issue_id": "ISS-POLICY-FP035-NETWORK-001",
        "source_ref": "docs/control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json",
        "source_type": "OPEN_POLICY_ISSUE",
        "status": "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL",
        "title": "FP-035 정규화 지시를 새 산출물 묶음 승인에 결속",
        "waived": false,
        "waiver_status": "NOT_APPROVED"
      }
    ],
    "opening_snapshot": {
      "as_of": "2026-07-27",
      "incident_record_count": 0,
      "interpretation": "운영 개시 전 빈 원장 구조이며 무사건·무장애·무변경 증거가 아니다.",
      "operation_change_record_count": 0,
      "operations_started": false,
      "proves_no_changes": false,
      "proves_no_incidents": false
    },
    "operation_changes": [],
    "technical_debt": [
      {
        "acceptance_receipt_ref": null,
        "closure_criteria": "실측 SLI와 승인된 SLO/error budget 및 산출 근거를 결속한다.",
        "debt_id": "OPS-TD-001",
        "due_condition": "BEFORE_PRODUCTION_OPERATION",
        "evidence_refs": [],
        "impact": "운영 품질 목표와 error-budget 판정을 수치로 할 수 없다.",
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "priority": "P0_BEFORE_PRODUCTION",
        "risk_acceptance_status": "NOT_APPROVED",
        "source_ref": "OPS-04",
        "status": "OPEN",
        "title": "SLI·SLO·error budget 수치 미측정"
      },
      {
        "acceptance_receipt_ref": null,
        "closure_criteria": "실환경 dashboard 권한·data freshness·alert 연결을 검증한다.",
        "debt_id": "OPS-TD-002",
        "due_condition": "BEFORE_PRODUCTION_OPERATION",
        "evidence_refs": [],
        "impact": "운영자가 지연·오류·안전 경보 상태를 검증된 화면에서 확인할 수 없다.",
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "priority": "P0_BEFORE_PRODUCTION",
        "risk_acceptance_status": "NOT_APPROVED",
        "source_ref": "OPS-06",
        "status": "OPEN",
        "title": "운영 dashboard 실제 구현·검증 전"
      },
      {
        "acceptance_receipt_ref": null,
        "closure_criteria": "지정·연락·최소권한·대체 연락경로를 실제로 검증한다.",
        "debt_id": "OPS-TD-003",
        "due_condition": "BEFORE_PRODUCTION_OPERATION",
        "evidence_refs": [],
        "impact": "단일 관리자 연락 불가 시 제한권한 안전 대응을 개시할 수 없다.",
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "priority": "P0_BEFORE_PRODUCTION",
        "risk_acceptance_status": "NOT_APPROVED",
        "source_ref": "OPS-03",
        "status": "OPEN",
        "title": "비상 대응자 실명·대기표 미배정"
      },
      {
        "acceptance_receipt_ref": null,
        "closure_criteria": "격리 복원·DR·관리자 접근상실 훈련 결과와 결함을 기록한다.",
        "debt_id": "OPS-TD-004",
        "due_condition": "BEFORE_PRODUCTION_OPERATION",
        "evidence_refs": [],
        "impact": "backup 복원성과 단일 관리자 복구 가능성이 입증되지 않았다.",
        "owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "priority": "P0_BEFORE_PRODUCTION",
        "risk_acceptance_status": "NOT_APPROVED",
        "source_ref": "OPS-11,OPS-13",
        "status": "OPEN",
        "title": "복원·재해복구·관리자 분실 훈련 미실행"
      }
    ]
  },
  "projection_schema": "walksafe.w9.closure-operations-walksafe.semantic.v1",
  "provenance_boundary": {
    "current_builder_check_and_targeted_test_results": {
      "acceptance_evidence": false,
      "current_use": "OBSERVATION_ONLY_NOT_TRANSITION_EVIDENCE",
      "may_become_transition_evidence_only_when": "A final independent rereview hash-binds exact current inputs, command/log receipts, test node IDs, results, and issues a disposition.",
      "recording_source": "docs/control/execution/artifact-remediation/20260726/w9/independent-review.md",
      "transition_authority": false
    },
    "final_independent_rereview": {
      "binding": "PATH_ONLY_FORWARD_REFERENCE",
      "byte_length": null,
      "current_status": "NOT_YET_BOUND",
      "path": "docs/control/execution/artifact-remediation/20260726/w9/independent-final-review.md",
      "required_for_transition": true,
      "sha256": null,
      "transition_authority_available": false
    },
    "first_review_is_authority": false,
    "first_review_use": "REMEDIATION_LINEAGE_ONLY",
    "historical_authoring_classification": "HISTORICAL_AUTHOR_OBSERVATION_NOT_ACCEPTANCE_EVIDENCE",
    "unsupported_provenance_claim_count": 0
  },
  "remediation_lineage": {
    "acceptance_authority": false,
    "binding_use": "REMEDIATION_LINEAGE_ONLY",
    "byte_length": 12809,
    "disposition_authority": false,
    "note": "This first review identifies correction lineage only; it is not current acceptance or final rereview authority.",
    "path": "docs/control/execution/artifact-remediation/20260726/w9/independent-review.md",
    "recorded_disposition": "NO_GO",
    "recorded_findings": {
      "BLOCKING": 0,
      "MAJOR": 2,
      "MINOR": 1
    },
    "sha256": "6063f27a9563022dc523fd41ec4c40e79a7c20a5d72c20f3da03c99b1f8011ea",
    "transition_authority": false
  },
  "stable_id_manifest": [
    {
      "artifact_id": "DLV-CLS-07",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-CLS-07"
    },
    {
      "artifact_id": "DLV-CLS-08",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-CLS-08"
    },
    {
      "artifact_id": "DLV-CLS-09",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-CLS-09"
    },
    {
      "artifact_id": "DLV-CLS-10",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-CLS-10"
    },
    {
      "artifact_id": "DLV-CLS-14",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-CLS-14"
    },
    {
      "artifact_id": "DLV-CLS-15",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-CLS-15"
    },
    {
      "artifact_id": "DLV-CLS-16",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-CLS-16"
    },
    {
      "artifact_id": "DLV-OPS-17",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-OPS-17"
    },
    {
      "artifact_id": "DLV-OPS-19",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-OPS-19"
    },
    {
      "artifact_id": "DLV-OPS-20",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-OPS-20"
    },
    {
      "artifact_id": "DLV-OPS-21",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-OPS-21"
    },
    {
      "artifact_id": "DLV-OPS-24",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-OPS-24"
    },
    {
      "artifact_id": "DLV-WS-08",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-WS-08"
    },
    {
      "artifact_id": "DLV-WS-10",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-WS-10"
    },
    {
      "artifact_id": "DLV-WS-18",
      "kind": "ARTIFACT_DISPOSITION",
      "stable_id": "W9-DISP-WS-18"
    },
    {
      "artifact_id": "DLV-CLS-07",
      "kind": "CLS_OPS_LIFECYCLE_CONTRACT",
      "stable_id": "W9-LIFECYCLE-CLS-07"
    },
    {
      "artifact_id": "DLV-CLS-08",
      "kind": "CLS_OPS_LIFECYCLE_CONTRACT",
      "stable_id": "W9-LIFECYCLE-CLS-08"
    },
    {
      "artifact_id": "DLV-CLS-09",
      "kind": "CLS_OPS_LIFECYCLE_CONTRACT",
      "stable_id": "W9-LIFECYCLE-CLS-09"
    },
    {
      "artifact_id": "DLV-CLS-10",
      "kind": "CLS_OPS_LIFECYCLE_CONTRACT",
      "stable_id": "W9-LIFECYCLE-CLS-10"
    },
    {
      "artifact_id": "DLV-CLS-14",
      "kind": "CLS_OPS_LIFECYCLE_CONTRACT",
      "stable_id": "W9-LIFECYCLE-CLS-14"
    },
    {
      "artifact_id": "DLV-CLS-15",
      "kind": "CLS_OPS_LIFECYCLE_CONTRACT",
      "stable_id": "W9-LIFECYCLE-CLS-15"
    },
    {
      "artifact_id": "DLV-CLS-16",
      "kind": "CLS_OPS_LIFECYCLE_CONTRACT",
      "stable_id": "W9-LIFECYCLE-CLS-16"
    },
    {
      "artifact_id": "DLV-OPS-17",
      "kind": "CLS_OPS_LIFECYCLE_CONTRACT",
      "stable_id": "W9-LIFECYCLE-OPS-17"
    },
    {
      "artifact_id": "DLV-OPS-19",
      "kind": "CLS_OPS_LIFECYCLE_CONTRACT",
      "stable_id": "W9-LIFECYCLE-OPS-19"
    },
    {
      "artifact_id": "DLV-OPS-20",
      "kind": "CLS_OPS_LIFECYCLE_CONTRACT",
      "stable_id": "W9-LIFECYCLE-OPS-20"
    },
    {
      "artifact_id": "DLV-OPS-21",
      "kind": "CLS_OPS_LIFECYCLE_CONTRACT",
      "stable_id": "W9-LIFECYCLE-OPS-21"
    },
    {
      "artifact_id": "DLV-OPS-24",
      "kind": "CLS_OPS_LIFECYCLE_CONTRACT",
      "stable_id": "W9-LIFECYCLE-OPS-24"
    },
    {
      "artifact_id": "DLV-CLS-08",
      "kind": "EXTERNAL_EXECUTION_CONTRACT",
      "stable_id": "W9-EXT-CLS-08"
    },
    {
      "artifact_id": "DLV-CLS-10",
      "kind": "EXTERNAL_EXECUTION_CONTRACT",
      "stable_id": "W9-EXT-CLS-10"
    },
    {
      "artifact_id": "DLV-CLS-14",
      "kind": "EXTERNAL_EXECUTION_CONTRACT",
      "stable_id": "W9-EXT-CLS-14"
    },
    {
      "artifact_id": "DLV-CLS-15",
      "kind": "EXTERNAL_EXECUTION_CONTRACT",
      "stable_id": "W9-EXT-CLS-15"
    },
    {
      "artifact_id": "DLV-CLS-16",
      "kind": "EXTERNAL_EXECUTION_CONTRACT",
      "stable_id": "W9-EXT-CLS-16"
    },
    {
      "artifact_id": "DLV-OPS-17",
      "kind": "EXTERNAL_EXECUTION_CONTRACT",
      "stable_id": "W9-EXT-OPS-17"
    },
    {
      "artifact_id": "DLV-OPS-19",
      "kind": "EXTERNAL_EXECUTION_CONTRACT",
      "stable_id": "W9-EXT-OPS-19"
    },
    {
      "artifact_id": "DLV-WS-08",
      "class_id": "person",
      "kind": "WS08_CLASS_RISK",
      "stable_id": "W9-WS08-RISK-00-PERSON"
    },
    {
      "artifact_id": "DLV-WS-08",
      "class_id": "bicycle",
      "kind": "WS08_CLASS_RISK",
      "stable_id": "W9-WS08-RISK-01-BICYCLE"
    },
    {
      "artifact_id": "DLV-WS-08",
      "class_id": "car",
      "kind": "WS08_CLASS_RISK",
      "stable_id": "W9-WS08-RISK-02-CAR"
    },
    {
      "artifact_id": "DLV-WS-08",
      "class_id": "motorcycle",
      "kind": "WS08_CLASS_RISK",
      "stable_id": "W9-WS08-RISK-03-MOTORCYCLE"
    },
    {
      "artifact_id": "DLV-WS-08",
      "class_id": "bus",
      "kind": "WS08_CLASS_RISK",
      "stable_id": "W9-WS08-RISK-04-BUS"
    },
    {
      "artifact_id": "DLV-WS-08",
      "class_id": "truck",
      "kind": "WS08_CLASS_RISK",
      "stable_id": "W9-WS08-RISK-05-TRUCK"
    },
    {
      "artifact_id": "DLV-WS-08",
      "class_id": "traffic light",
      "kind": "WS08_CLASS_RISK",
      "stable_id": "W9-WS08-RISK-06-TRAFFIC-LIGHT"
    },
    {
      "artifact_id": "DLV-WS-08",
      "class_id": "normal_tactile_block",
      "kind": "WS08_CLASS_RISK",
      "stable_id": "W9-WS08-RISK-07-NORMAL-TACTILE-BLOCK"
    },
    {
      "artifact_id": "DLV-WS-08",
      "class_id": "damaged_tactile_block",
      "kind": "WS08_CLASS_RISK",
      "stable_id": "W9-WS08-RISK-08-DAMAGED-TACTILE-BLOCK"
    },
    {
      "artifact_id": "DLV-WS-08",
      "class_id": "crosswalk",
      "kind": "WS08_CLASS_RISK",
      "stable_id": "W9-WS08-RISK-09-CROSSWALK"
    },
    {
      "artifact_id": "DLV-WS-08",
      "class_id": "curb_step",
      "kind": "WS08_CLASS_RISK",
      "stable_id": "W9-WS08-RISK-10-CURB-STEP"
    },
    {
      "artifact_id": "DLV-WS-08",
      "class_id": "uneven_sidewalk",
      "kind": "WS08_CLASS_RISK",
      "stable_id": "W9-WS08-RISK-11-UNEVEN-SIDEWALK"
    },
    {
      "artifact_id": "DLV-WS-08",
      "class_id": "e_scooter_obstruction",
      "kind": "WS08_CLASS_RISK",
      "stable_id": "W9-WS08-RISK-12-E-SCOOTER-OBSTRUCTION"
    }
  ],
  "verification_boundary": {
    "current_builder_and_targeted_test_observations": "NOT_TRANSITION_AUTHORITY_UNTIL_FINAL_INDEPENDENT_REREVIEW",
    "external7_execution_status": "NOT_RUN",
    "final_independent_rereview_required": true,
    "global_acceptance_status": "NOT_RUN",
    "global_product_execution_status": "NOT_RUN",
    "historical_authoring_observations": "NOT_ACCEPTANCE_EVIDENCE",
    "permitted_claims": [
      "HASH_BOUND_CURRENT_CONTENT_ASSESSMENT",
      "BUILDER_GENERATED_EXTERNAL7_CONTRACT_PARITY",
      "EMPTY_REAL_EXECUTION_AND_RECEIPT_STATE"
    ],
    "prohibited_claims": [
      "PRODUCT_VALIDATED",
      "EXTERNAL_ACTION_COMPLETED",
      "ACCEPTANCE_APPROVED",
      "GO_DISPOSITION_WITHOUT_FINAL_INDEPENDENT_REREVIEW"
    ]
  },
  "w8_predecessor": {
    "acceptance_authority": false,
    "binding": "PATH_ONLY",
    "binding_deferred_to": "CENTRAL_INTEGRATION",
    "binding_kind": "PATH_ONLY_FORWARD_REFERENCE",
    "byte_length": null,
    "existence_claimed": false,
    "path": "docs/control/execution/artifact-remediation/20260726/w8/implementation-receipt.json",
    "reason": "W8 receipt was not materialized at W9 authoring time; central integration must bind its actual path/SHA-256/byte length after creation.",
    "sha256": null
  },
  "walksafe_contracts": {
    "WS-08": {
      "android_device_safety_validation_status": "NOT_RUN",
      "approval_status": "NOT_APPROVED",
      "class_analysis": [
        {
          "class_id": "person",
          "context": "전방 보행자 접근·정지·경로 점유",
          "detectability": "거리·상대 움직임·연속 track으로 검출 가능성 확인 필요",
          "exposure": "도심 보도에서 빈번할 수 있으나 측정 NOT_RUN",
          "fail_closed": "거리·움직임 근거가 stale이면 방향 지시 없이 안전정지",
          "false_negative": "접근 보행자를 놓쳐 충돌 가능",
          "false_positive": "불필요한 정지와 반복 음성으로 주의 분산",
          "harm": "QUALITATIVE_HIGH_NOT_SCORED",
          "model_control": "후보 threshold 0.20은 미승인; 거리·track 안정화 필요",
          "policy_control": "접근 또는 경로 차단 근거가 있을 때만 제한 경고",
          "required_evidence": "거리·혼잡·가림별 FP/FN과 실기기 TTS 중재",
          "residual_field_limitation_notice": "군중·가림·역광에서 안전 보장 불가를 고지",
          "source_row_index": 0,
          "stable_id": "W9-WS08-RISK-00-PERSON",
          "ui_control": "사람 존재가 아니라 정지·주변 확인 중심의 짧은 안내"
        },
        {
          "class_id": "bicycle",
          "context": "주행·정차 자전거의 접근 또는 통로 점유",
          "detectability": "상대 속도·방향·depth 연속성 검증 필요",
          "exposure": "자전거 혼용 보도 노출 측정 NOT_RUN",
          "fail_closed": "속도·거리 불신 시 경고 정밀도를 낮추고 안전정지",
          "false_negative": "빠른 접근 자전거를 놓쳐 충돌 가능",
          "false_positive": "정차 자전거를 즉시 충돌 위험으로 과잉 경고",
          "harm": "QUALITATIVE_HIGH_NOT_SCORED",
          "model_control": "후보 threshold 0.20은 미승인; motion gate 필요",
          "policy_control": "접근 또는 경로 차단이 확인되지 않으면 위험 확정 금지",
          "required_evidence": "정차·접근·교차 자전거별 fixed-set와 실기기 시험",
          "residual_field_limitation_notice": "고속·가림·야간은 현장 승인 전 비지원",
          "source_row_index": 1,
          "stable_id": "W9-WS08-RISK-01-BICYCLE",
          "ui_control": "좌우 회피 대신 멈춤·주변 확인 안내"
        },
        {
          "class_id": "car",
          "context": "보도 진입·주차·접근 차량",
          "detectability": "보행 공간 교차·상대 움직임·거리 근거 필요",
          "exposure": "교차로·주차장 출입구 노출 측정 NOT_RUN",
          "fail_closed": "차량 위치·움직임 불신 시 전체 안전정지",
          "false_negative": "보도 진입 차량을 놓쳐 중대 충돌 가능",
          "false_positive": "도로 옆 차량을 즉시 보행 충돌로 오인",
          "harm": "QUALITATIVE_CRITICAL_NOT_SCORED",
          "model_control": "후보 threshold 0.20은 미승인; ROI·motion 결합 필요",
          "policy_control": "차량 class만으로 횡단·회피 지시 금지",
          "required_evidence": "진입·정차·원거리 차량별 FP/FN과 near-miss 통제시험",
          "residual_field_limitation_notice": "차량 접근과 신호 안전을 보장하지 않음을 고지",
          "source_row_index": 2,
          "stable_id": "W9-WS08-RISK-02-CAR",
          "ui_control": "즉시 멈춤과 주변 확인만 안내"
        },
        {
          "class_id": "motorcycle",
          "context": "보도·골목에서 접근하거나 정차한 이륜차",
          "detectability": "작은 bbox·속도·가림 조건 검증 필요",
          "exposure": "골목·혼용 공간 노출 측정 NOT_RUN",
          "fail_closed": "연속 관측 실패 시 안전정지",
          "false_negative": "작고 빠른 이륜차를 놓쳐 충돌 가능",
          "false_positive": "정차 이륜차를 이동 위험으로 과잉 경고",
          "harm": "QUALITATIVE_HIGH_NOT_SCORED",
          "model_control": "후보 threshold 0.20은 미승인; temporal 확인 필요",
          "policy_control": "접근 근거 없는 방향 지시 금지",
          "required_evidence": "거리·속도·가림별 fixed-set와 실기기 시험",
          "residual_field_limitation_notice": "고속 접근 탐지를 보장하지 않음을 고지",
          "source_row_index": 3,
          "stable_id": "W9-WS08-RISK-03-MOTORCYCLE",
          "ui_control": "멈춤·주변 확인 중심 안내"
        },
        {
          "class_id": "bus",
          "context": "정류장·도로 가장자리·보도 인접 대형차",
          "detectability": "큰 bbox의 부분 가림과 거리 포화 검증 필요",
          "exposure": "정류장 인접 노출 측정 NOT_RUN",
          "fail_closed": "거리 포화·부분 검출이면 정밀 방향 안내 중지",
          "false_negative": "보행 공간 침범 버스를 놓침",
          "false_positive": "안전하게 분리된 버스를 경로 차단으로 오인",
          "harm": "QUALITATIVE_CRITICAL_NOT_SCORED",
          "model_control": "후보 threshold 0.20은 미승인; ROI·depth sanity 필요",
          "policy_control": "class만으로 도로 진입이나 우회 지시 금지",
          "required_evidence": "정류장·원거리·부분 bbox별 FP/FN 시험",
          "residual_field_limitation_notice": "대형차 주변 사각지대 안전을 보장하지 않음",
          "source_row_index": 4,
          "stable_id": "W9-WS08-RISK-04-BUS",
          "ui_control": "정지와 주변 확인 안내"
        },
        {
          "class_id": "truck",
          "context": "공사·하역·도로 인접 대형 화물차",
          "detectability": "부분 bbox·큰 물체 거리·움직임 검증 필요",
          "exposure": "공사·하역 구간 노출 측정 NOT_RUN",
          "fail_closed": "공사·하역 맥락 또는 거리 불신 시 전체 안전정지",
          "false_negative": "보행 공간 침범·접근 화물차를 놓침",
          "false_positive": "분리된 화물차를 즉시 보행 위험으로 오인",
          "harm": "QUALITATIVE_CRITICAL_NOT_SCORED",
          "model_control": "후보 threshold 0.20은 미승인; ROI·motion 결합 필요",
          "policy_control": "공사구간은 현장 승인 전 비지원",
          "required_evidence": "공사·하역 통제환경 FP/FN과 중단 시험",
          "residual_field_limitation_notice": "공사구간과 대형차 사각지대는 비지원 고지",
          "source_row_index": 5,
          "stable_id": "W9-WS08-RISK-05-TRUCK",
          "ui_control": "정지·지원 요청 안내"
        },
        {
          "class_id": "traffic light",
          "context": "교차로 주변 신호등 객체의 존재",
          "detectability": "객체 존재만 검출하며 신호 상태·보행 허용 판독 근거 없음",
          "exposure": "교차로 노출 측정 NOT_RUN",
          "fail_closed": "신호 관련 질문에는 기존 보조수단 확인 안내",
          "false_negative": "신호등 존재를 놓침",
          "false_positive": "신호등이 아닌 물체를 신호 안전 근거로 오인",
          "harm": "QUALITATIVE_CRITICAL_IF_MISUSED_NOT_SCORED",
          "model_control": "후보 threshold 0.30은 미승인; 신호 상태 결정에 사용 금지",
          "policy_control": "횡단보도 참고 정보만 제공하고 횡단 허용 판단 금지",
          "required_evidence": "객체 FP/FN과 신호판단 비사용 회귀시험",
          "residual_field_limitation_notice": "교통신호·차량 접근 안전을 보장하지 않음",
          "source_row_index": 6,
          "stable_id": "W9-WS08-RISK-06-TRAFFIC-LIGHT",
          "ui_control": "건너도 된다는 안내를 생성하지 않음"
        },
        {
          "class_id": "normal_tactile_block",
          "context": "저장 TMAP 경로와 정렬된 가까운 점자블록",
          "detectability": "TMAP 방향·camera ROI·연속성 정렬 검증 필요",
          "exposure": "포장 패턴·마모 환경 노출 측정 NOT_RUN",
          "fail_closed": "반대·끊김·정렬 불명확이면 따라가라 안내 금지",
          "false_negative": "유효한 근거리 점자블록 보조를 놓침",
          "false_positive": "비점자 패턴을 경로 보조로 잘못 안내",
          "harm": "QUALITATIVE_HIGH_NOT_SCORED",
          "model_control": "후보 threshold 0.30은 미승인; route alignment gate 필요",
          "policy_control": "목적지 route graph가 아닌 근거리 보조만 허용",
          "required_evidence": "패턴·방향·마모별 FP/FN과 route 정렬 시험",
          "residual_field_limitation_notice": "점자블록이 목적지까지 이어짐을 보장하지 않음",
          "source_row_index": 7,
          "stable_id": "W9-WS08-RISK-07-NORMAL-TACTILE-BLOCK",
          "ui_control": "정렬이 확인된 경우에만 제한적 follow 안내"
        },
        {
          "class_id": "damaged_tactile_block",
          "context": "손상 점자블록 위험·자동신고 후보",
          "detectability": "정상/손상 혼동과 중복 후보 검증 필요",
          "exposure": "손상 유형·조명별 노출 측정 NOT_RUN",
          "fail_closed": "근거 불충분 후보는 기관 제출 없이 관리자 검수",
          "false_negative": "손상 위험과 신고 후보를 놓침",
          "false_positive": "정상 블록을 손상으로 신고해 운영 오염",
          "harm": "QUALITATIVE_HIGH_NOT_SCORED",
          "model_control": "후보 threshold 0.30은 미승인; class·중복 안정화 필요",
          "policy_control": "report-only이며 자동 결과를 길안내 확정에 사용 금지",
          "required_evidence": "손상 유형별 FP/FN·중복률·report trace",
          "residual_field_limitation_notice": "신고 생성이 보행 안전이나 기관 처리를 보장하지 않음",
          "source_row_index": 8,
          "stable_id": "W9-WS08-RISK-08-DAMAGED-TACTILE-BLOCK",
          "ui_control": "자동신고 후보별 기본 알림 없음; 안전 영향 시 제한 경고"
        },
        {
          "class_id": "crosswalk",
          "context": "횡단보도 표면·경계의 참고 관측",
          "detectability": "객체 존재만 확인하며 차량·신호 상태는 알 수 없음",
          "exposure": "도색 마모·가림별 노출 측정 NOT_RUN",
          "fail_closed": "신호·차량 근거 없으면 정지·기존 보조수단 확인",
          "false_negative": "횡단보도 존재를 놓침",
          "false_positive": "유사 도색을 횡단보도로 오인",
          "harm": "QUALITATIVE_CRITICAL_IF_MISUSED_NOT_SCORED",
          "model_control": "후보 threshold 0.30은 미승인; 횡단 안전판단 입력 금지",
          "policy_control": "참고 정보만 제공하고 횡단 지시 금지",
          "required_evidence": "도색·가림별 FP/FN과 금지 안내 회귀시험",
          "residual_field_limitation_notice": "횡단 가능 여부와 교통 안전을 보장하지 않음",
          "source_row_index": 9,
          "stable_id": "W9-WS08-RISK-09-CROSSWALK",
          "ui_control": "건너기 시작 안내를 생성하지 않음"
        },
        {
          "class_id": "curb_step",
          "context": "보행 진행면의 턱·단차",
          "detectability": "depth·바닥면·연속 frame 검증 필요",
          "exposure": "높이·조명·거리별 노출 측정 NOT_RUN",
          "fail_closed": "depth 또는 바닥면 불신 시 전체 안전정지",
          "false_negative": "단차를 놓쳐 걸림·낙상 가능",
          "false_positive": "그림자·경계를 단차로 오인해 불필요 정지",
          "harm": "QUALITATIVE_HIGH_NOT_SCORED",
          "model_control": "후보 threshold 0.20은 미승인; depth sanity 필요",
          "policy_control": "이동 방향 대신 정지·주변 확인만 허용",
          "required_evidence": "높이·조명·장착별 FP/FN과 실기기 depth 시험",
          "residual_field_limitation_notice": "작은 턱·투명 경계 탐지를 보장하지 않음",
          "source_row_index": 10,
          "stable_id": "W9-WS08-RISK-10-CURB-STEP",
          "ui_control": "턱 가능성과 안전정지를 짧게 안내"
        },
        {
          "class_id": "uneven_sidewalk",
          "context": "균열·들뜸·불균일한 보도면",
          "detectability": "낮은 recall blocker와 표면 다양성 검증 필요",
          "exposure": "재질·조명·젖은 노면 노출 측정 NOT_RUN",
          "fail_closed": "표면 판단 불신 시 방향 안내 중지",
          "false_negative": "낙상 가능한 보도 손상을 놓침",
          "false_positive": "무늬·그림자를 불균일 보도로 오인",
          "harm": "QUALITATIVE_HIGH_NOT_SCORED",
          "model_control": "후보 threshold 0.15은 미승인; field gate 미충족",
          "policy_control": "검증 전 안전 보장·정밀 회피 지시 금지",
          "required_evidence": "표면 재질·조명별 FP/FN과 field gate 재평가",
          "residual_field_limitation_notice": "현행 후보 recall 부족과 미세 손상 한계를 고지",
          "source_row_index": 11,
          "stable_id": "W9-WS08-RISK-11-UNEVEN-SIDEWALK",
          "ui_control": "불확실하면 정지·주변 확인 안내"
        },
        {
          "class_id": "e_scooter_obstruction",
          "context": "보행로를 막는 전동킥보드",
          "detectability": "보행 ROI 점유·거리·가림 검증 필요",
          "exposure": "배치·가림·조명별 노출 측정 NOT_RUN",
          "fail_closed": "통과 공간 안전 미확인 시 진행 지시 금지",
          "false_negative": "통로를 막는 킥보드를 놓쳐 충돌 가능",
          "false_positive": "통로 밖 킥보드를 경로 차단으로 오인",
          "harm": "QUALITATIVE_HIGH_NOT_SCORED",
          "model_control": "후보 threshold 0.35은 미승인; obstruction gate 필요",
          "policy_control": "안전한 이동 공간 검증 전 좌우 회피 지시 금지",
          "required_evidence": "배치·가림·거리별 FP/FN과 통제경로 시험",
          "residual_field_limitation_notice": "넘어진 형태·부분 가림 탐지를 보장하지 않음",
          "source_row_index": 12,
          "stable_id": "W9-WS08-RISK-12-E-SCOOTER-OBSTRUCTION",
          "ui_control": "정지·주변 확인 안내"
        }
      ],
      "class_count": 13,
      "class_order": [
        "person",
        "bicycle",
        "car",
        "motorcycle",
        "bus",
        "truck",
        "traffic light",
        "normal_tactile_block",
        "damaged_tactile_block",
        "crosswalk",
        "curb_step",
        "uneven_sidewalk",
        "e_scooter_obstruction"
      ],
      "lifecycle_status": "DRAFT",
      "quantitative_evaluation_status": "NOT_RUN",
      "release_status": "NOT_ELIGIBLE",
      "runtime_model_id": "unified_walksafe"
    },
    "WS-10": {
      "completion_eligible": false,
      "execution_status": "NOT_RUN",
      "fixed_same_input_pt_tflite_comparison": {
        "dataset_snapshot_sha256": null,
        "preprocessing_contract_sha256": null,
        "result": null,
        "sample_results": null,
        "status": "NOT_RUN"
      },
      "gap_status": "INTERNAL_GAP",
      "lifecycle_status": "PLANNED",
      "release_status": "NOT_ELIGIBLE",
      "runtime_config_artifact_sha256": "92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19",
      "source_model": {
        "path": "model/artifacts/candidates/walksafe_13cls_yolo26n_img768_20260708/walksafe_13cls_yolo26n_img768_best_epoch270.pt",
        "sha256": "a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669"
      },
      "tflite_model": {
        "path": "apps/android/app/src/main/assets/models/walksafe_unified_yolo26n_768_float32.tflite",
        "sha256": "92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19"
      },
      "tolerance": {
        "approval_ref": null,
        "status": "NOT_ESTABLISHED",
        "value": null
      }
    },
    "WS-18": {
      "alternate_provider_validation_status": "NOT_RUN",
      "automatic_retry_budget": 0,
      "cache_reuse_for_new_search_or_route": "PROHIBITED",
      "deployment_status": "NOT_RUN",
      "endpoints": [
        {
          "endpoint": "/api/navigation/destinations/search",
          "hop": "ANDROID_TO_GATEWAY_SEARCH",
          "method": "GET",
          "status": "DEPLOYMENT_NOT_RUN"
        },
        {
          "endpoint": "/api/navigation/walking",
          "hop": "ANDROID_TO_GATEWAY_ROUTE",
          "method": "POST",
          "status": "DEPLOYMENT_NOT_RUN"
        },
        {
          "endpoint": "TMAP_POI_SEARCH_URL",
          "hop": "BACKEND_TO_TMAP_SEARCH",
          "method": "GET",
          "status": "LIVE_TMAP_NOT_RUN"
        },
        {
          "endpoint": "https://apis.openapi.sk.com/tmap/routes/pedestrian",
          "hop": "BACKEND_TO_TMAP_ROUTE",
          "method": "POST",
          "status": "LIVE_TMAP_NOT_RUN"
        }
      ],
      "error_taxonomy": [
        {
          "automatic_retry_budget": 0,
          "behavior": "검색·경로 시작 차단, secret 원문 없는 기능 불가 안내",
          "error_class": "CONFIGURATION_UNAVAILABLE"
        },
        {
          "automatic_retry_budget": 0,
          "behavior": "의존성 차단·escalation, provider 자동 전환 금지",
          "error_class": "AUTH_OR_CONTRACT_REJECTED"
        },
        {
          "automatic_retry_budget": 0,
          "behavior": "quota 상태 기록, 현재 방향안내 중지, 사용자 재요청만 허용",
          "error_class": "QUOTA_OR_RATE_LIMITED"
        },
        {
          "automatic_retry_budget": 0,
          "behavior": "4초에 요청 취소, 오래된 회전안내 재사용 금지",
          "error_class": "PROVIDER_TIMEOUT"
        },
        {
          "automatic_retry_budget": 0,
          "behavior": "빈 경로로 변환하지 않고 offline/fallback 안내",
          "error_class": "PROVIDER_UNAVAILABLE"
        },
        {
          "automatic_retry_budget": 0,
          "behavior": "응답 폐기, 마지막 성공 경로를 새 경로로 표시 금지",
          "error_class": "INVALID_OR_OVERSIZED_RESPONSE"
        },
        {
          "automatic_retry_budget": 0,
          "behavior": "없음 안내, 임의 목적지·경로 생성 금지",
          "error_class": "NO_ROUTE_OR_NO_RESULT"
        }
      ],
      "lifecycle_status": "DRAFT",
      "live_tmap_status": "NOT_RUN",
      "planned_tests": [
        {
          "status": "NOT_RUN",
          "test": "route_and_search_endpoint_contract"
        },
        {
          "status": "NOT_RUN",
          "test": "four_second_timeout_and_cancellation"
        },
        {
          "status": "NOT_RUN",
          "test": "error_taxonomy_mapping"
        },
        {
          "status": "NOT_RUN",
          "test": "retry_zero_and_cache_stale_rejection"
        },
        {
          "status": "NOT_RUN",
          "test": "offline_fallback_and_accessible_notice"
        },
        {
          "status": "NOT_RUN",
          "test": "monitoring_redaction_and_escalation"
        },
        {
          "status": "NOT_RUN",
          "test": "provider_exit_and_alternate_validation"
        }
      ],
      "provider_exit_status": "NOT_RUN",
      "provider_timeout_seconds": 4.0,
      "quota_contracts": [
        {
          "due": "BEFORE_LIVE_TMAP_OR_NAMED_RELEASE",
          "item": "contract_quota_burst_daily",
          "owner": "OPERATIONS_OWNER_ROLE",
          "value": "NOT_ESTABLISHED"
        },
        {
          "due": "BEFORE_LIVE_TMAP_OR_NAMED_RELEASE",
          "item": "quota_cost_overage_policy",
          "owner": "PROJECT_OWNER_ROLE",
          "value": "NOT_ESTABLISHED"
        },
        {
          "due": "BEFORE_LIVE_TMAP_OR_NAMED_RELEASE",
          "item": "provider_status_support_contract",
          "owner": "OPERATIONS_OWNER_ROLE",
          "value": "NOT_ESTABLISHED"
        },
        {
          "due": "BEFORE_LIVE_TMAP_OR_NAMED_RELEASE",
          "item": "quota_measurement_block_test",
          "owner": "QA_OWNER_ROLE",
          "value": "NOT_RUN"
        }
      ],
      "quota_validation_status": "NOT_RUN",
      "release_status": "NOT_ELIGIBLE",
      "stale_route_guidance": "PROHIBITED"
    },
    "source_manifest_path": "docs/deliverables/manifests/sec-ws-draft-20260721-r001.json",
    "source_manifest_sha256": "4c73dbd4df8356b0ccba3de6b4193d6d693be801e887021a8a6964bc0b6e316f"
  }
}
```

## Deterministic self-validation

```json
{
  "content_fingerprint_replay": {
    "rule": "Set /integrity/content_fingerprint to null, canonicalize the full JSON document, then SHA-256 the UTF-8 bytes.",
    "status": "PASS"
  },
  "exact15_disposition": {
    "artifact_count": 15,
    "counts": {
      "EXTERNAL": 7,
      "GAP": 1,
      "OK": 7
    },
    "status": "PASS"
  },
  "external7_generated_overlay_parity": {
    "exact_object_parity": true,
    "fingerprint": "9d09cedad2bf7ae0cb78bcc83799590fe2f3096298203acf42a9e62bef934d1a",
    "generated_count": 7,
    "generated_json_domain_parity": true,
    "generated_markdown_critical_field_parity": true,
    "overlay_count": 7,
    "status": "PASS"
  },
  "global_execution_boundary": {
    "external7_execution": "NOT_RUN",
    "final_independent_rereview_authority": "NOT_YET_BOUND",
    "product_execution": "NOT_RUN",
    "status": "PASS"
  },
  "semantic_projection_schema_replay": {
    "fingerprint": "5b5dd16aad3e41b7e742bcd217841d3a9462f8c1d09239783f5dc47e3b69279a",
    "payload_pointer": "/integrity/semantic_projection/payload",
    "projection_schema": "walksafe.w9.closure-operations-walksafe.semantic.v1",
    "reconstructed_payload_matches_embedded_payload": true,
    "status": "PASS"
  },
  "source_staleness": {
    "direct_checked": 14,
    "lineage_checked": 1,
    "stale_count": 0,
    "status": "PASS",
    "w8_reference_handling": "PATH_ONLY_EXCLUDED_FROM_STALENESS"
  },
  "stable_id_manifest": {
    "status": "PASS",
    "unique_stable_id_count": 47
  },
  "unsupported_provenance_claims": {
    "count": 0,
    "historical_classification": "HISTORICAL_AUTHOR_OBSERVATION_NOT_ACCEPTANCE_EVIDENCE",
    "status": "PASS"
  }
}
```

- Direct evidence fingerprint: `82104b6ad52e82dcae304afaacb082a4ab19e24f6c2b827892dff262bb53d58b`
- Input-set fingerprint: `61e40326e93b0efa97da283cfddce96522a4b7b12f0b3622867a2febd6e0f69e`
- Semantic projection fingerprint: `5b5dd16aad3e41b7e742bcd217841d3a9462f8c1d09239783f5dc47e3b69279a`
- JSON content fingerprint: `312dd10dcae5147ba0bddf6f2f380c07019320fbb83a70e3300c2227a7525cb1`
