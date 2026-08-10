# W8 release readiness current state

- document: `WS-W8-RELEASE-READINESS-CURRENT-STATE-20260727-001`
- status: `CURRENT_INTERNAL_RELEASE_READINESS_BOUNDARY_CORRECTED_PENDING_INDEPENDENT_REREVIEW_AND_EXTERNAL_EXECUTION`
- exact5: `DLV-REL-15, DLV-REL-16, DLV-REL-17, DLV-REL-19, DLV-REL-22`
- source fingerprint: `fd913a725400b57f274865da0f0c54e60a1025ea8a7634a03aa87764789e5897`
- input-set fingerprint: `d6f21a1cc13b62dfa79e8b650889759b5a3bbf5a7fc7699da79c3898be31615c`
- semantic projection fingerprint: `0d457c61834098d327220d63b34df5df13248f21ca63153ec3ff8a1db85f0991`
- JSON content fingerprint: `c2b9f33958da5a8c2c83dafd7473c9ad0cb8982f243eee79f3ea247f3efacb45`

> REL17 OK candidate 근거는 current content completeness와 bound current targeted-test PASS입니다. W9 shared source의 W8 exclusive change 또는 pre-W9 byte preservation은 주장하지 않습니다.

## Source bindings

| Binding | Path | Bytes | SHA-256 | Attribution | Role |
|---|---|---:|---|---|---|
| `SRC-W8-AUDIT` | `docs/control/execution/artifact-audits/20260726/release-ops-ws-closure-audit.json` | `39673` | `d465905e6e33c720aa776ffb48145c29ef7b8036d7d1da416641fb5c9226af44` | `AUDIT_BASELINE_INPUT` | exact5 audit baseline |
| `SRC-W8-USER-GUIDE` | `apps/android/USER_GUIDE.md` | `13833` | `746d5c13b99171e8da2d562efcdfb5ec0de47ba9fe5d167b11d9edfcb2f2a82b` | `W8_DIRECT_AUTHORED_CURRENT_SUBJECT` | REL17 current user guide; direct W8-authored current subject |
| `SRC-W9-SHARED-REL-BUILDER` | `scripts/build_walksafe_formal_rel_ops_cls_20260721.py` | `132367` | `3bf8c8b918b8c23d7da9ff111bfd3318593c60798447a10adef248effba92cd6` | `SUCCESSOR_CURRENT_SOURCE_W9_SHARED` | current REL/OPS/CLS deterministic builder; successor W9 shared source |
| `SRC-W9-SHARED-REL-TEST` | `tests/test_walksafe_formal_rel_ops_cls.py` | `28178` | `78d627a79202decdbadf81051b4700280b0c06056eebd8dacec359be64d75c12` | `SUCCESSOR_CURRENT_SOURCE_W9_SHARED` | current targeted builder/output boundary test; successor W9 shared source |
| `SRC-CURRENT-REL-HANDOVER` | `docs/deliverables/09-release/delivery-and-handover.md` | `31028` | `34a263359cab57d9245520a50f2733f50ab4848322646d771410738b9b82c82c` | `CURRENT_CONTENT_CONTAINER_NOT_CHANGE_ATTRIBUTION` | current REL handover container; not exclusive W8 change evidence |
| `SRC-CURRENT-REL17-SECTION` | `docs/deliverables/09-release/delivery-and-handover.md` | `5321` | `f6fe0a8e7cc6788cec9d2a0e56b7b3ccdc06c296b27ab68487a93807d7c2e61a` | `CURRENT_REL17_CONTENT_EVIDENCE_NOT_CHANGE_ATTRIBUTION` | current REL17 content evidence; no historical or exclusive change attribution |
| `SRC-W9-SHARED-REL-MANIFEST` | `docs/deliverables/manifests/rel-ops-cls-draft-20260721-r001.json` | `125261` | `c02986f83e36140903f5d001cfb32fe36902dad28753d37d6ffe7f98e70c4c1a` | `SUCCESSOR_CURRENT_SOURCE_W9_SHARED` | current REL/OPS/CLS draft manifest; successor W9 shared source |
| `SRC-W4-RECEIPT` | `docs/control/execution/artifact-remediation/20260726/w4/implementation-receipt.json` | `19207` | `e924b5216da5ba174fb765c8c5d8d18e91a9b2a8aeec8dd8b6876b0a644210ba` | `PREDECESSOR_RECEIPT_INPUT` | W4 terminal predecessor receipt |
| `SRC-W5-RECEIPT` | `docs/control/execution/artifact-remediation/20260726/w5/implementation-receipt.json` | `47592` | `c229b45989463a5f1eb4943289af42a4a9a8a720131de25421683ca59dc12dbe` | `PREDECESSOR_RECEIPT_INPUT` | W5 terminal predecessor receipt |
| `SRC-W7-CURRENT` | `docs/control/execution/artifact-remediation/20260726/w7/aiml-model-governance-current-state.json` | `41414` | `887fa726af570aa506e906dff5202818a14137b42009a1bee13b2cbf5cffa611` | `PREDECESSOR_W7_INPUT` | latest W7 current-state predecessor |
| `SRC-W7-REREVIEW` | `docs/control/execution/artifact-remediation/20260726/w7/independent-rereview.md` | `3444` | `4c1aa9d62ffd936672a697ffcc03e3d8d8a4b9fa49ee5c0e38d3cfc19f2da3c8` | `PREDECESSOR_W7_INPUT` | latest W7 independent rereview GO |

## Attribution and historical digest boundary

| Boundary | Exact state |
|---|---|
| W8 direct-authored current subject | `SRC-W8-USER-GUIDE` |
| W9 successor shared current sources | `SRC-W9-SHARED-REL-BUILDER, SRC-W9-SHARED-REL-MANIFEST, SRC-W9-SHARED-REL-TEST` as `SUCCESSOR_CURRENT_SOURCE_W9_SHARED` |
| Current REL17 content evidence | `SRC-CURRENT-REL17-SECTION`, bytes `5321`, SHA-256 `f6fe0a8e7cc6788cec9d2a0e56b7b3ccdc06c296b27ab68487a93807d7c2e61a` |
| Historical pre-W9 builder digest | `HISTORICAL_PRE_W9_DIGEST_UNKNOWN_NOT_RETAINED` |
| Historical pre-W9 test digest | `HISTORICAL_PRE_W9_DIGEST_UNKNOWN_NOT_RETAINED` |
| Historical pre-W9 manifest digest | `HISTORICAL_PRE_W9_DIGEST_UNKNOWN_NOT_RETAINED` |
| Historical pre-W9 REL17 section digest | `HISTORICAL_PRE_W9_DIGEST_UNKNOWN_NOT_RETAINED` |
| Historical byte preservation claim | `false` |
| Exclusive attribution claim count | `0` |
| Unsupported preservation claim count | `0` |
| REL17 candidate basis | `CURRENT_CONTENT_COMPLETENESS_AND_CURRENT_TEST` |

Current REL17 evidence는 handover raw byte offset `[6011,11332)`의 exact `5321` bytes입니다. 이 현재 content evidence는 W8 변화 귀속 또는 pre-W9 byte preservation 증거가 아닙니다.

## Authoring validation

| ID | Command | Result | Evidence class | Formal release credit | Exclusive W8 change credit |
|---|---|---|---|---:|---:|
| `W8-BUILDER-CHECK` | `python3 scripts/build_walksafe_formal_rel_ops_cls_20260721.py --check` | `PASS` | `BOUND_CURRENT_CONTENT_AND_TEST_EVIDENCE` | `false` | `false` |
| `W8-TARGETED-TEST-001` | `python3 -m pytest -q tests/test_walksafe_formal_rel_ops_cls.py` | `PASS` | `BOUND_CURRENT_CONTENT_AND_TEST_EVIDENCE` | `false` | `false` |

위 PASS 기록은 bound current content/test evidence입니다. 이 correction 작업은 명령을 재실행하지 않았고, formal release execution으로 credit하지 않습니다.

## Exact5 dispositions

| Artifact | Audit | Current disposition | Candidate | Required action | Boundary |
|---|---|---|---:|---|---|
| `DLV-REL-15` | `INTERNAL_GAP` | `INTERNAL_GAP_RETAINED` | `false` | 현재 배포 구조에 맞는 trigger, 순서, 검증과 실패 안전을 구체화한다. | current rollback template is structured, but rollback result remains NOT_RUN. |
| `DLV-REL-16` | `INTERNAL_GAP` | `INTERNAL_GAP_RETAINED` | `false` | 지원 구성, 사전조건, 설치, 검증과 제거 절차를 완성한다. | only unsigned/source-level material is bound; no signed artifact or installation execution exists. |
| `DLV-REL-17` | `INTERNAL_GAP` | `READY_FOR_INDEPENDENT_REVIEW_AS_OK_CANDIDATE` | `true` | 권한, 안전 제한, 핵심 흐름과 오류 복구를 현재 코드에 맞춘다. | current guide and current REL17 section completely project the four choices, network states, privacy-operation boundaries, TMAP external boundary and W7 model3 register-only limitation; the OK candidate basis is current content completeness plus the bound current targeted-test PASS, not exclusive W8 change attribution or pre-W9 byte preservation. |
| `DLV-REL-19` | `INTERNAL_GAP` | `INTERNAL_GAP_RETAINED` | `false` | 실제 build와 mock, 미검증 기능을 명확히 분리한 자료를 완성한다. | controlled material distinguishes build/mock/unverified behavior, but no demo or training session receipt exists. |
| `DLV-REL-22` | `INTERNAL_GAP` | `INTERNAL_GAP_RETAINED` | `false` | 직접 및 전이 dependency, 라이선스 본문과 고지 의무를 결속한다. | license inventory has 346 known and 185 unknown classifications, while final release notices are absent. |

REL17만 correction 후 독립 재검토 대상 OK candidate입니다. 이 문서는 central transition 또는 독립 재검토 결과를 생성하지 않습니다.

## REL17 user guide exact projection

### Four independent consent choices

| Choice | Default | Independent | Meaning |
|---|---|---:|---|
| `RAW_SOURCE_COLLECTION` | `OFF` | `true` | 신고 확인용 원본 이미지·위치·탐지자료 기기 수집 |
| `AUTOMATIC_REPORTING` | `OFF` | `true` | 조건 통과 손상 점자블록 후보 자동신고 처리 |
| `MOBILE_NETWORK_TRANSFER` | `OFF` | `true` | 정지 뒤 허용 자료 이동통신망 전송 |
| `TRAINING_REUSE` | `OFF` | `true` | 신고 처리와 별도로 승인 자료 모델 연구·학습 재사용 |

Android 권한, 로그인, 네 선택은 상호 독립이며 bundled grant는 없습니다. 구체 consent schema version은 현재 사용자 설명서에 명시되지 않았습니다.

### Walking/stopped network rules

| State | Transfer | Transport | Rule |
|---|---|---|---|
| `WALKING` | `DENIED` | `NONE` | 일반 활동원본은 보행 중 서버로 전송하지 않는다. |
| `STOPPED_MOBILE_OPTED_IN` | `CONDITIONALLY_ALLOWED` | `CELLULAR_OR_WIFI` | MOBILE_NETWORK_TRANSFER를 명시적으로 선택한 사용자만 정지 판정 뒤 이동통신망 전송을 허용한다. |
| `STOPPED_MOBILE_NOT_OPTED_IN` | `CONDITIONALLY_ALLOWED` | `WIFI_ONLY` | 이동통신망 전송을 선택하지 않은 사용자는 정지 뒤에도 Wi-Fi에서만 전송한다. |

### Withdrawal, deletion, reconsent

| Operation | Technical behavior | Execution | Legal boundary |
|---|---|---|---|
| `WITHDRAWAL` | 해당 선택의 새 수집·보고·전송·학습 재사용을 중단하고 대기 작업을 차단한다. | `NOT_RUN` | 최종 한국어 철회 문안과 효력 범위는 외부 법률 승인 전 NOT_APPROVED. |
| `DELETION` | 동의 철회와 별도 요청이며 서버·backup 범위, 처리 결과와 증거가 필요하다. | `NOT_RUN` | 보유기간, 예외와 실제 삭제 범위는 외부 법률 승인 전 NOT_APPROVED. |
| `RECONSENT` | 새 동의 version과 시각을 기록하며 과거 처리를 소급 승인하거나 진행 중 삭제를 취소하지 않는다. | `NOT_RUN` | 최종 한국어 재동의 문안과 법적 효과는 외부 법률 승인 전 NOT_APPROVED. |

- retention: `180` days is a technical draft target, not an approved legal retention period.
- TMAP: `EXTERNAL_THIRD_PARTY_PROCESSING`, live `NOT_RUN`, contract/legal `NOT_APPROVED`; unresolved `제공/위탁 구분, 국외이전, 계약 역할, 최종 고지·동의`.

### W7 model3 register-only boundary

| Model | Provenance | Approval | Evaluation / equivalence / device | Deployment eligible |
|---|---|---|---|---:|
| `unified_walksafe` | `CANDIDATE_SOURCE_BOUND` | `NOT_APPROVED` | `NOT_RUN` / `NOT_RUN` / `NOT_RUN` | `false` |
| `custom_tactile` | `UNKNOWN_PROVENANCE` | `NOT_APPROVED` | `NOT_RUN` / `NOT_RUN` / `NOT_RUN` | `false` |
| `coco_general` | `UNKNOWN_PROVENANCE` | `NOT_APPROVED` | `NOT_RUN` / `NOT_RUN` / `NOT_RUN` | `false` |

세 모델은 registry projection일 뿐 release 포함, runtime 승인 또는 deployment eligibility를 뜻하지 않습니다.

## Retained release gaps

| Artifact | Preserved state | Missing execution/evidence |
|---|---|---|
| `DLV-REL-15` | template `DRAFT_TEMPLATE_PRESENT` | rollback `NOT_RUN`, result `null` |
| `DLV-REL-16` | `UNSIGNED_SOURCE_OR_BUILD_OUTPUT_ONLY`; signed artifacts `0` | signing `NOT_RUN`, install `NOT_RUN` |
| `DLV-REL-19` | material separates actual build/mock/unverified | demo `NOT_RUN`, session receipts `0` |
| `DLV-REL-22` | license known `346`, unknown `185`, total `531` | final notices `ABSENT` |

## Manifest and global boundary

| Boundary | Current state |
|---|---|
| `manifest.lifecycle_status` | `DRAFT` |
| `manifest.approval_status` | `NOT_APPROVED` |
| `manifest.execution_status` | `STRUCTURE_CHECKED_EXECUTION_NOT_RUN` |
| `manifest.release_status` | `NOT_ELIGIBLE` |
| `manifest.materialized_artifact_count` | `39` |
| `manifest.planned_artifact_count` | `23` |
| `manifest.actual_execution_evidence_count` | `0` |
| `manifest.external_original_count` | `0` |
| `manifest.closure_result_count` | `0` |
| `manifest.release_gate_count` | `5` |
| `manifest.release_gate_status` | `NOT_RUN` |
| `manifest.release_gates_waived` | `false` |
| `source_commit` | `null` |
| `build_id` | `null` |
| `formal_test_count` | `279` |
| `formal_execution_status` | `NOT_RUN` |
| `formal_executed_count` | `0` |
| `formal_pass_count` | `0` |
| `formal_evidence_count` | `0` |
| `actual_device_status` | `NOT_RUN` |
| `live_tmap_status` | `NOT_RUN` |
| `signing_execution_status` | `NOT_RUN` |
| `signing_assessment_status` | `NOT_ASSESSED` |
| `deployment_execution_status` | `NOT_RUN` |
| `installation_execution_status` | `NOT_RUN` |
| `rollback_execution_status` | `NOT_RUN` |
| `controlled_demo_execution_status` | `NOT_RUN` |
| `legal_approval_status` | `NOT_APPROVED` |
| `model_approval_status` | `NOT_APPROVED` |
| `release_approval_status` | `NOT_APPROVED` |
| `release_status` | `NOT_ELIGIBLE` |
| `release_eligible_claimed` | `false` |
| `release_gate_count` | `5` |
| `release_gate_status` | `NOT_RUN` |
| `release_gates_waived` | `false` |

## Semantic projection replay contract

- projection id: `W8_RELEASE_READINESS_SEMANTIC_PROJECTION_V2`
- exact payload: 아래 `walksafe-w8-semantic-projection-payload` JSON object
- field membership: 아래 selection contract와 payload에 있는 member만 포함하며 extra member는 금지
- types: payload에 인코딩된 JSON string/boolean/integer/null/array/object type이 규범이며 coercion 금지
- serialization: UTF-8, `ensure_ascii=false`, recursive `sort_keys=true`, separators `(',', ':')`, whitespace 없음, trailing LF 정확히 1 byte
- SHA-256: `0d457c61834098d327220d63b34df5df13248f21ca63153ec3ff8a1db85f0991`

### Exact selection, array order, and type contract

```json
{
  "array_order": {
    "/artifact_dispositions": "scope.exact_artifact_ids declaration order",
    "/attribution_lineage/successor_current_source_binding_ids": "lexicographic binding_id order",
    "/attribution_lineage/w8_direct_authored_current_binding_ids": "lexicographic binding_id order",
    "/authoring_validation": "authoring_validation declaration order",
    "/release_readiness_controls/*/*": "preserve each selected source array declaration order recursively",
    "/scope/exact_artifact_ids": "scope.exact_artifact_ids declaration order"
  },
  "field_selection": [
    {
      "payload_pointer": "/artifact_dispositions",
      "selected_fields": [
        "artifact_type_code",
        "current_disposition",
        "ok_candidate"
      ],
      "source_pointer": "/artifact_dispositions/*",
      "type": "array<object>"
    },
    {
      "payload_pointer": "/attribution_lineage",
      "selected_fields": "entire object",
      "source_pointer": "/attribution_lineage",
      "type": "object"
    },
    {
      "payload_pointer": "/authoring_validation",
      "selected_fields": [
        "evidence_classification",
        "exclusive_w8_change_credit",
        "result",
        "validation_id"
      ],
      "source_pointer": "/authoring_validation/*",
      "type": "array<object>"
    },
    {
      "payload_pointer": "/document_id",
      "selected_fields": "scalar",
      "source_pointer": "/document_id",
      "type": "string"
    },
    {
      "payload_pointer": "/manifest_boundary",
      "selected_fields": "entire object",
      "source_pointer": "/manifest_boundary",
      "type": "object"
    },
    {
      "payload_pointer": "/release_readiness_controls",
      "selected_fields": "entire object",
      "source_pointer": "/release_readiness_controls",
      "type": "object"
    },
    {
      "payload_pointer": "/schema_version",
      "selected_fields": "scalar",
      "source_pointer": "/schema_version",
      "type": "string"
    },
    {
      "payload_pointer": "/scope",
      "selected_fields": [
        "artifact_count",
        "central_transition_applied",
        "exact_artifact_ids"
      ],
      "source_pointer": "/scope",
      "type": "object"
    },
    {
      "payload_pointer": "/status",
      "selected_fields": "scalar",
      "source_pointer": "/status",
      "type": "string"
    },
    {
      "payload_pointer": "/verification_boundary",
      "selected_fields": "entire object",
      "source_pointer": "/verification_boundary",
      "type": "object"
    },
    {
      "payload_pointer": "/wave_id",
      "selected_fields": "scalar",
      "source_pointer": "/wave_id",
      "type": "string"
    }
  ],
  "payload_type_contract": {
    "arrays": "JSON arrays; order is normative as specified by array_order",
    "booleans": "JSON true/false only; no string coercion",
    "integers": "base-10 JSON integer numbers only; no string coercion",
    "nulls": "JSON null only where encoded in payload",
    "objects": "JSON objects; selected membership is exact and extra members are forbidden",
    "strings": "UTF-8 JSON strings; no normalization or case coercion"
  },
  "projection_id": "W8_RELEASE_READINESS_SEMANTIC_PROJECTION_V2"
}
```

<!-- walksafe-w8-semantic-projection-payload:start -->
```json
{
  "artifact_dispositions": [
    {
      "artifact_type_code": "DLV-REL-15",
      "current_disposition": "INTERNAL_GAP_RETAINED",
      "ok_candidate": false
    },
    {
      "artifact_type_code": "DLV-REL-16",
      "current_disposition": "INTERNAL_GAP_RETAINED",
      "ok_candidate": false
    },
    {
      "artifact_type_code": "DLV-REL-17",
      "current_disposition": "READY_FOR_INDEPENDENT_REVIEW_AS_OK_CANDIDATE",
      "ok_candidate": true
    },
    {
      "artifact_type_code": "DLV-REL-19",
      "current_disposition": "INTERNAL_GAP_RETAINED",
      "ok_candidate": false
    },
    {
      "artifact_type_code": "DLV-REL-22",
      "current_disposition": "INTERNAL_GAP_RETAINED",
      "ok_candidate": false
    }
  ],
  "attribution_lineage": {
    "candidate_basis": "CURRENT_CONTENT_COMPLETENESS_AND_CURRENT_TEST",
    "current_rel17_content_evidence": {
      "binding_id": "SRC-CURRENT-REL17-SECTION",
      "byte_length": 5321,
      "path": "docs/deliverables/09-release/delivery-and-handover.md",
      "sha256": "f6fe0a8e7cc6788cec9d2a0e56b7b3ccdc06c296b27ab68487a93807d7c2e61a"
    },
    "exclusive_attribution_claim_count": 0,
    "historical_pre_w9_byte_preservation_claimed": false,
    "historical_pre_w9_digest_retained_count": 0,
    "historical_pre_w9_digest_status": {
      "builder": "HISTORICAL_PRE_W9_DIGEST_UNKNOWN_NOT_RETAINED",
      "manifest": "HISTORICAL_PRE_W9_DIGEST_UNKNOWN_NOT_RETAINED",
      "rel17_section": "HISTORICAL_PRE_W9_DIGEST_UNKNOWN_NOT_RETAINED",
      "test": "HISTORICAL_PRE_W9_DIGEST_UNKNOWN_NOT_RETAINED"
    },
    "successor_current_source_binding_ids": [
      "SRC-W9-SHARED-REL-BUILDER",
      "SRC-W9-SHARED-REL-MANIFEST",
      "SRC-W9-SHARED-REL-TEST"
    ],
    "unsupported_preservation_claim_count": 0,
    "w8_direct_authored_current_binding_ids": [
      "SRC-W8-USER-GUIDE"
    ]
  },
  "authoring_validation": [
    {
      "evidence_classification": "BOUND_CURRENT_CONTENT_AND_TEST_EVIDENCE",
      "exclusive_w8_change_credit": false,
      "result": "PASS",
      "validation_id": "W8-BUILDER-CHECK"
    },
    {
      "evidence_classification": "BOUND_CURRENT_CONTENT_AND_TEST_EVIDENCE",
      "exclusive_w8_change_credit": false,
      "result": "PASS",
      "validation_id": "W8-TARGETED-TEST-001"
    }
  ],
  "document_id": "WS-W8-RELEASE-READINESS-CURRENT-STATE-20260727-001",
  "manifest_boundary": {
    "actual_execution_evidence_count": 0,
    "approval_status": "NOT_APPROVED",
    "claims": {
      "closure_complete": false,
      "deployment_complete": false,
      "handover_complete": false,
      "operations_complete": false,
      "release_artifact_complete": false,
      "risk_acceptance_complete": false
    },
    "closure_result_count": 0,
    "execution_status": "STRUCTURE_CHECKED_EXECUTION_NOT_RUN",
    "external_original_count": 0,
    "lifecycle_status": "DRAFT",
    "materialized_artifact_count": 39,
    "planned_artifact_count": 23,
    "release_gate_count": 5,
    "release_gate_status": "NOT_RUN",
    "release_gates_waived": false,
    "release_status": "NOT_ELIGIBLE"
  },
  "release_readiness_controls": {
    "DLV-REL-15": {
      "required_template_fields": [
        "trigger",
        "ordered steps",
        "post-rollback verification",
        "failure-safe action",
        "owner/time/evidence"
      ],
      "rollback_execution_status": "NOT_RUN",
      "rollback_result": null,
      "rollback_template_status": "DRAFT_TEMPLATE_PRESENT",
      "safe_boundary": "DB compatibility and losslessness must be proven for rollback; otherwise stop safely rather than force downgrade."
    },
    "DLV-REL-16": {
      "approval_status": "NOT_APPROVED",
      "artifact_state": "UNSIGNED_SOURCE_OR_BUILD_OUTPUT_ONLY",
      "document_status": "DRAFT",
      "excluded_client_scope": [
        "Web/PWA"
      ],
      "installation_execution_status": "NOT_RUN",
      "installation_receipt_count": 0,
      "signed_artifact_count": 0,
      "signed_artifact_ids": [],
      "signing_assessment_status": "NOT_ASSESSED",
      "signing_execution_status": "NOT_RUN",
      "supported_client_scope": [
        "Android user app",
        "private Android admin app"
      ]
    },
    "DLV-REL-17": {
      "choice_independence": {
        "android_permissions_independent": true,
        "bundled_grant": false,
        "login_independent": true,
        "mutually_independent": true
      },
      "consent_choices": [
        {
          "choice_id": "RAW_SOURCE_COLLECTION",
          "default": "OFF",
          "independent": true,
          "meaning": "신고 확인용 원본 이미지·위치·탐지자료 기기 수집"
        },
        {
          "choice_id": "AUTOMATIC_REPORTING",
          "default": "OFF",
          "independent": true,
          "meaning": "조건 통과 손상 점자블록 후보 자동신고 처리"
        },
        {
          "choice_id": "MOBILE_NETWORK_TRANSFER",
          "default": "OFF",
          "independent": true,
          "meaning": "정지 뒤 허용 자료 이동통신망 전송"
        },
        {
          "choice_id": "TRAINING_REUSE",
          "default": "OFF",
          "independent": true,
          "meaning": "신고 처리와 별도로 승인 자료 모델 연구·학습 재사용"
        }
      ],
      "current_content_evidence": {
        "binding_id": "SRC-CURRENT-REL17-SECTION",
        "byte_length": 5321,
        "candidate_basis": "CURRENT_CONTENT_COMPLETENESS_AND_CURRENT_TEST",
        "exclusive_w8_change_attribution_claimed": false,
        "historical_pre_w9_digest_status": "HISTORICAL_PRE_W9_DIGEST_UNKNOWN_NOT_RETAINED",
        "path": "docs/deliverables/09-release/delivery-and-handover.md",
        "sha256": "f6fe0a8e7cc6788cec9d2a0e56b7b3ccdc06c296b27ab68487a93807d7c2e61a"
      },
      "document_state": {
        "approval_status": "NOT_APPROVED",
        "lifecycle_status": "DRAFT",
        "release_status": "NOT_ELIGIBLE",
        "specific_consent_schema_version_documented": false,
        "title": "REL-17 사용자 설명서"
      },
      "model_register_boundary": {
        "model_count": 3,
        "models": [
          {
            "approval_status": "NOT_APPROVED",
            "conversion_equivalence_status": "NOT_RUN",
            "deployment_eligible": false,
            "device_validation_status": "NOT_RUN",
            "evaluation_status": "NOT_RUN",
            "model_id": "unified_walksafe",
            "provenance": "CANDIDATE_SOURCE_BOUND"
          },
          {
            "approval_status": "NOT_APPROVED",
            "conversion_equivalence_status": "NOT_RUN",
            "deployment_eligible": false,
            "device_validation_status": "NOT_RUN",
            "evaluation_status": "NOT_RUN",
            "model_id": "custom_tactile",
            "provenance": "UNKNOWN_PROVENANCE"
          },
          {
            "approval_status": "NOT_APPROVED",
            "conversion_equivalence_status": "NOT_RUN",
            "deployment_eligible": false,
            "device_validation_status": "NOT_RUN",
            "evaluation_status": "NOT_RUN",
            "model_id": "coco_general",
            "provenance": "UNKNOWN_PROVENANCE"
          }
        ],
        "register_only": true,
        "release_inclusion_claimed": false,
        "release_status": "NOT_ELIGIBLE",
        "source": "W7 current state"
      },
      "network_states": [
        {
          "allowed_transport": "NONE",
          "rule": "일반 활동원본은 보행 중 서버로 전송하지 않는다.",
          "server_transfer": "DENIED",
          "state": "WALKING"
        },
        {
          "allowed_transport": "CELLULAR_OR_WIFI",
          "rule": "MOBILE_NETWORK_TRANSFER를 명시적으로 선택한 사용자만 정지 판정 뒤 이동통신망 전송을 허용한다.",
          "server_transfer": "CONDITIONALLY_ALLOWED",
          "state": "STOPPED_MOBILE_OPTED_IN"
        },
        {
          "allowed_transport": "WIFI_ONLY",
          "rule": "이동통신망 전송을 선택하지 않은 사용자는 정지 뒤에도 Wi-Fi에서만 전송한다.",
          "server_transfer": "CONDITIONALLY_ALLOWED",
          "state": "STOPPED_MOBILE_NOT_OPTED_IN"
        }
      ],
      "privacy_operations": [
        {
          "execution_status": "NOT_RUN",
          "legal_boundary": "최종 한국어 철회 문안과 효력 범위는 외부 법률 승인 전 NOT_APPROVED.",
          "operation": "WITHDRAWAL",
          "technical_behavior": "해당 선택의 새 수집·보고·전송·학습 재사용을 중단하고 대기 작업을 차단한다."
        },
        {
          "execution_status": "NOT_RUN",
          "legal_boundary": "보유기간, 예외와 실제 삭제 범위는 외부 법률 승인 전 NOT_APPROVED.",
          "operation": "DELETION",
          "technical_behavior": "동의 철회와 별도 요청이며 서버·backup 범위, 처리 결과와 증거가 필요하다."
        },
        {
          "execution_status": "NOT_RUN",
          "legal_boundary": "최종 한국어 재동의 문안과 법적 효과는 외부 법률 승인 전 NOT_APPROVED.",
          "operation": "RECONSENT",
          "technical_behavior": "새 동의 version과 시각을 기록하며 과거 처리를 소급 승인하거나 진행 중 삭제를 취소하지 않는다."
        }
      ],
      "retention_boundary": {
        "approved_legal_retention_period": null,
        "claim": "180일은 기술·운영 draft 목표일이며 승인된 법적 보유기간이 아니다.",
        "technical_draft_target_days": 180
      },
      "tmap_boundary": {
        "boundary_kind": "EXTERNAL_THIRD_PARTY_PROCESSING",
        "contract_and_legal_status": "NOT_APPROVED",
        "data": [
          "목적지",
          "정확 위치"
        ],
        "live_status": "NOT_RUN",
        "provider": "TMAP",
        "unresolved": [
          "제공/위탁 구분",
          "국외이전",
          "계약 역할",
          "최종 고지·동의"
        ]
      }
    },
    "DLV-REL-19": {
      "approval_status": "NOT_APPROVED",
      "controlled_demo_execution_status": "NOT_RUN",
      "document_status": "DRAFT",
      "material_boundary": "actual build, mock and unverified features are explicitly distinct",
      "participant_receipt_count": 0,
      "session_receipt_count": 0,
      "training_or_demo_completion_claimed": false
    },
    "DLV-REL-22": {
      "approval_status": "NOT_APPROVED",
      "claim": "candidate inventory is not a final notice and must be regenerated from the actual release dependency/artifact set.",
      "document_status": "DRAFT",
      "final_notice_file_count": 0,
      "final_notice_status": "ABSENT",
      "release_artifact_dependency_reconciliation_status": "NOT_RUN",
      "sbom_license_counts": {
        "known": 346,
        "total": 531,
        "unknown": 185
      },
      "unknown_requires_resolution": true
    }
  },
  "schema_version": "walksafe.w8.release-readiness-current-state.v2",
  "scope": {
    "artifact_count": 5,
    "central_transition_applied": false,
    "exact_artifact_ids": [
      "DLV-REL-15",
      "DLV-REL-16",
      "DLV-REL-17",
      "DLV-REL-19",
      "DLV-REL-22"
    ]
  },
  "status": "CURRENT_INTERNAL_RELEASE_READINESS_BOUNDARY_CORRECTED_PENDING_INDEPENDENT_REREVIEW_AND_EXTERNAL_EXECUTION",
  "verification_boundary": {
    "actual_device_status": "NOT_RUN",
    "build_id": null,
    "controlled_demo_execution_status": "NOT_RUN",
    "deployment_execution_status": "NOT_RUN",
    "formal_evidence_count": 0,
    "formal_executed_count": 0,
    "formal_execution_status": "NOT_RUN",
    "formal_pass_count": 0,
    "formal_test_count": 279,
    "installation_execution_status": "NOT_RUN",
    "legal_approval_status": "NOT_APPROVED",
    "live_tmap_status": "NOT_RUN",
    "model_approval_status": "NOT_APPROVED",
    "release_approval_status": "NOT_APPROVED",
    "release_eligible_claimed": false,
    "release_gate_count": 5,
    "release_gate_status": "NOT_RUN",
    "release_gates_waived": false,
    "release_status": "NOT_ELIGIBLE",
    "rollback_execution_status": "NOT_RUN",
    "signing_assessment_status": "NOT_ASSESSED",
    "signing_execution_status": "NOT_RUN",
    "source_commit": null
  },
  "wave_id": "W8"
}
```
<!-- walksafe-w8-semantic-projection-payload:end -->

JSON contract payload와 이 Markdown payload는 동일 object이며 canonical replay 결과가 위 fingerprint와 일치합니다.

## Deterministic validation

| Check | Result |
|---|---|
| `candidate1` | `PASS` |
| `candidate_basis_current_content_and_test` | `PASS` |
| `exact5` | `PASS` |
| `exclusive_attribution0` | `PASS` |
| `global_boundary` | `PASS` |
| `input_fingerprint_reproducible` | `PASS` |
| `internal_gap4` | `PASS` |
| `json_markdown_parity` | `PASS` |
| `manifest_zero_execution` | `PASS` |
| `projection_fingerprint_reproducible` | `PASS` |
| `rel15_not_run` | `PASS` |
| `rel16_unsigned_uninstalled` | `PASS` |
| `rel17_choice_exact4` | `PASS` |
| `rel17_current_section_bound` | `PASS` |
| `rel17_model3_register_only` | `PASS` |
| `rel17_network_exact3` | `PASS` |
| `rel17_privacy_ops_not_run` | `PASS` |
| `rel17_tmap_boundary` | `PASS` |
| `rel19_receipt_absent` | `PASS` |
| `rel22_counts_notices_absent` | `PASS` |
| `semantic_projection_exact_contract` | `PASS` |
| `semantic_replay_pass` | `PASS` |
| `source_binding_unique` | `PASS` |
| `source_fingerprint_reproducible` | `PASS` |
| `unsupported_preservation_claim0` | `PASS` |
| `validation_pass2` | `PASS` |
| `shared3_current_hash_match3_of_3` | `PASS` |
| `source_binding_stale0` | `PASS` |

- exact: `5 / unique 5`
- candidates: `1` (`DLV-REL-17`)
- retained gaps: `4`
- shared-source exclusive attribution claims: `0`
- stale shared-source bindings: `0`
- shared3 current hash matches: `3 / 3`
- unsupported pre-W9 preservation claims: `0`
- source fingerprint: `fd913a725400b57f274865da0f0c54e60a1025ea8a7634a03aa87764789e5897`
- input-set fingerprint: `d6f21a1cc13b62dfa79e8b650889759b5a3bbf5a7fc7699da79c3898be31615c`
- semantic projection fingerprint: `0d457c61834098d327220d63b34df5df13248f21ca63153ec3ff8a1db85f0991`
- JSON content fingerprint: `c2b9f33958da5a8c2c83dafd7473c9ad0cb8982f243eee79f3ea247f3efacb45`
<!-- walksafe-w8-exact-artifacts: DLV-REL-15,DLV-REL-16,DLV-REL-17,DLV-REL-19,DLV-REL-22 -->
<!-- walksafe-w8-source-fingerprint: fd913a725400b57f274865da0f0c54e60a1025ea8a7634a03aa87764789e5897 -->
<!-- walksafe-w8-semantic-projection-fingerprint: 0d457c61834098d327220d63b34df5df13248f21ca63153ec3ff8a1db85f0991 -->
