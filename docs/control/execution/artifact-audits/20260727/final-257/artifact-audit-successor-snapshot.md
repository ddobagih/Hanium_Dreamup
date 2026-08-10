# WalkSafe 최종 257 산출물 successor snapshot

> 생성일: `2026-07-27`  
> 원 baseline: `docs/control/execution/artifact-audits/20260726/artifact-audit-baseline.json` (immutable)  
> replay: `W1 → W2 → W3 → W4 → W5 → W6 → W7 → W8 → W9`  
> final independent review/seal: 이 package에서 생성하지 않음

## 최종 집계

| OK | INTERNAL_GAP | EXTERNAL | N_A_CANDIDATE | TOTAL |
|---:|---:|---:|---:|---:|
| 124 | 48 | 49 | 36 | 257 |

초기 대비 delta는 `OK +65 / INTERNAL_GAP -75 / EXTERNAL +10 / N_A_CANDIDATE 0`입니다. `N_A_CANDIDATE`는 적용성 후보 의미를 그대로 유지하며 승인·완료·비적용 확정으로 승격하지 않습니다.

## 전역 보수 경계

| 경계 | 상태 |
|---|---|
| formal 279 | `NOT_RUN` · executed/pass/evidence `0/0/0` |
| actual device | `NOT_RUN` |
| deploy / signing | `NOT_RUN / NOT_RUN` |
| legal / model / closure approval | `NOT_APPROVED / NOT_APPROVED / NOT_APPROVED` |
| remaining gates | `5` · `NOT_RUN` · waiver `0` |
| release | `NOT_ELIGIBLE` |
| 완화 | `0` |

## Action groups

| Group | Kind | Count | Owner/authority |
|---|---|---:|---|
| `FINAL-INT-DATA-QUALITY-SPLIT` | INTERNAL | 6 | `AI_ML_DATA_OWNER_ROLE` |
| `FINAL-INT-TRAINING-MODEL` | INTERNAL | 5 | `AI_ML_ENGINEERING_OWNER_ROLE` |
| `FINAL-INT-EVALUATION-EQUIV` | INTERNAL | 4 | `MODEL_VALIDATION_OWNER_ROLE` |
| `FINAL-INT-DESIGN-SECURITY` | INTERNAL | 5 | `SECURITY_ARCHITECT_ROLE` |
| `FINAL-INT-BUILD-SUPPLY` | INTERNAL | 6 | `BUILD_RELEASE_ENGINEERING_ROLE` |
| `FINAL-INT-STATIC-SECRET` | INTERNAL | 3 | `APPLICATION_SECURITY_OWNER_ROLE` |
| `FINAL-INT-INTEGRATION-RELEASE` | INTERNAL | 6 | `RELEASE_ENGINEERING_OWNER_ROLE` |
| `FINAL-INT-TEST-GOV-TRACE` | INTERNAL | 9 | `QA_GOVERNANCE_OWNER_ROLE` |
| `FINAL-INT-TEST-ENV-DEVICE` | INTERNAL | 4 | `QA_ENVIRONMENT_OWNER_ROLE` |
| `FINAL-EXT-DATA-LEGAL` | EXTERNAL | 8 | `DATA_PROTECTION_AND_LEGAL_AUTHORITY_ROLE` |
| `FINAL-EXT-FIELD-DEVICE` | EXTERNAL | 10 | `INDEPENDENT_FIELD_QA_AUTHORITY_ROLE` |
| `FINAL-EXT-RELEASE-OPS` | EXTERNAL | 13 | `RELEASE_AND_OPERATIONS_AUTHORITY_ROLE` |
| `FINAL-EXT-APPROVAL-HANDOVER` | EXTERNAL | 16 | `PROJECT_SPONSOR_RECIPIENT_AND_ACCEPTANCE_AUTHORITY_ROLE` |
| `FINAL-EXT-RESEARCH-OBSERVATION` | EXTERNAL | 2 | `INDEPENDENT_RESEARCH_OBSERVER_ROLE` |

## Exact 257 status·action table

| Artifact | Initial | Current | Action | Group | Lineage |
|---|---|---|---|---|---:|
| `DLV-AIML-01` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-DATA-LEGAL` | 0 |
| `DLV-AIML-02` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-DATA-LEGAL` | 0 |
| `DLV-AIML-03` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-DATA-LEGAL` | 0 |
| `DLV-AIML-04` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-AIML-05` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-DATA-QUALITY-SPLIT` | 1 |
| `DLV-AIML-06` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-DATA-QUALITY-SPLIT` | 1 |
| `DLV-AIML-07` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-DATA-QUALITY-SPLIT` | 1 |
| `DLV-AIML-08` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-DATA-QUALITY-SPLIT` | 1 |
| `DLV-AIML-09` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-DATA-QUALITY-SPLIT` | 1 |
| `DLV-AIML-10` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-DATA-QUALITY-SPLIT` | 1 |
| `DLV-AIML-11` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TRAINING-MODEL` | 1 |
| `DLV-AIML-12` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TRAINING-MODEL` | 1 |
| `DLV-AIML-13` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TRAINING-MODEL` | 1 |
| `DLV-AIML-14` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-AIML-15` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TRAINING-MODEL` | 1 |
| `DLV-AIML-16` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TRAINING-MODEL` | 1 |
| `DLV-AIML-17` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-EVALUATION-EQUIV` | 1 |
| `DLV-AIML-18` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-AIML-19` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-AIML-20` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-AIML-21` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-EVALUATION-EQUIV` | 1 |
| `DLV-AIML-22` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-DATA-LEGAL` | 0 |
| `DLV-AIML-23` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-EVALUATION-EQUIV` | 1 |
| `DLV-AIML-24` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-AIML-25` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-AIML-26` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-CLS-01` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-CLS-02` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-CLS-03` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-CLS-04` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-CLS-05` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-CLS-06` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-CLS-07` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-CLS-08` | `INTERNAL_GAP` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 1 |
| `DLV-CLS-09` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-CLS-10` | `INTERNAL_GAP` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 1 |
| `DLV-CLS-11` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-CLS-12` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-CLS-13` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-CLS-14` | `INTERNAL_GAP` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RELEASE-OPS` | 1 |
| `DLV-CLS-15` | `INTERNAL_GAP` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RELEASE-OPS` | 1 |
| `DLV-CLS-16` | `INTERNAL_GAP` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RELEASE-OPS` | 1 |
| `DLV-DES-01` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-02` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-03` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-04` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-05` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-06` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-07` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-08` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-09` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-10` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-11` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-12` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-13` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-14` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-15` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-DESIGN-SECURITY` | 0 |
| `DLV-DES-16` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-17` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-18` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-19` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-DESIGN-SECURITY` | 0 |
| `DLV-DES-20` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-DESIGN-SECURITY` | 0 |
| `DLV-DES-21` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-DES-22` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-23` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-24` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-25` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-26` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DES-27` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DEV-01` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DEV-02` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DEV-03` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DEV-04` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DEV-05` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DEV-06` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DEV-07` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DEV-08` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DEV-09` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-BUILD-SUPPLY` | 1 |
| `DLV-DEV-10` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-DEV-11` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-DEV-12` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-BUILD-SUPPLY` | 1 |
| `DLV-DEV-13` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-DEV-14` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-BUILD-SUPPLY` | 1 |
| `DLV-DEV-15` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-DEV-16` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-STATIC-SECRET` | 1 |
| `DLV-DEV-17` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-BUILD-SUPPLY` | 1 |
| `DLV-DEV-18` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DEV-19` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-BUILD-SUPPLY` | 1 |
| `DLV-DEV-20` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-BUILD-SUPPLY` | 1 |
| `DLV-DEV-21` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-INTEGRATION-RELEASE` | 1 |
| `DLV-DOC-01` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DOC-02` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-DOC-03` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-DOC-04` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-DOC-05` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DSC-01` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-DSC-02` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-DSC-03` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DSC-04` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-DSC-05` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-DSC-06` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-DSC-07` | `INTERNAL_GAP` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RESEARCH-OBSERVATION` | 1 |
| `DLV-DSC-08` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-DSC-09` | `INTERNAL_GAP` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RESEARCH-OBSERVATION` | 1 |
| `DLV-DSC-10` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-DSC-11` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-DSC-12` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-DSC-13` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-DSC-14` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-DSC-15` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-MGT-01` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-MGT-02` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-MGT-03` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-MGT-04` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-MGT-05` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-MGT-06` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-MGT-07` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-MGT-08` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-MGT-09` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-MGT-10` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-MGT-11` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-MGT-12` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-MGT-13` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-MGT-14` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-MGT-15` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-MGT-16` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-MGT-17` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-MGT-18` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-OPS-01` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-OPS-02` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-OPS-03` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-OPS-04` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-OPS-05` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-OPS-06` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RELEASE-OPS` | 0 |
| `DLV-OPS-07` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-OPS-08` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-OPS-09` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-OPS-10` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-OPS-11` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RELEASE-OPS` | 0 |
| `DLV-OPS-12` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-OPS-13` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RELEASE-OPS` | 0 |
| `DLV-OPS-14` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-OPS-15` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-OPS-16` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-OPS-17` | `INTERNAL_GAP` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RELEASE-OPS` | 1 |
| `DLV-OPS-18` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-OPS-19` | `INTERNAL_GAP` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RELEASE-OPS` | 1 |
| `DLV-OPS-20` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-OPS-21` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-OPS-22` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RELEASE-OPS` | 0 |
| `DLV-OPS-23` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RELEASE-OPS` | 0 |
| `DLV-OPS-24` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-REL-01` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REL-02` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REL-03` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-REL-04` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-REL-05` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-REL-06` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-REL-07` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-REL-08` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-REL-09` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-REL-10` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REL-11` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REL-12` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REL-13` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RELEASE-OPS` | 0 |
| `DLV-REL-14` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-REL-15` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-INTEGRATION-RELEASE` | 1 |
| `DLV-REL-16` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-INTEGRATION-RELEASE` | 1 |
| `DLV-REL-17` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-REL-18` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-INTEGRATION-RELEASE` | 0 |
| `DLV-REL-19` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-INTEGRATION-RELEASE` | 1 |
| `DLV-REL-20` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RELEASE-OPS` | 0 |
| `DLV-REL-21` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-RELEASE-OPS` | 0 |
| `DLV-REL-22` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-INTEGRATION-RELEASE` | 1 |
| `DLV-REQ-01` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REQ-02` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REQ-03` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-REQ-04` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REQ-05` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REQ-06` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-REQ-07` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REQ-08` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REQ-09` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REQ-10` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REQ-11` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REQ-12` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-REQ-13` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-REQ-14` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-REQ-15` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-REQ-16` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-REQ-17` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-REQ-18` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-REQ-19` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-SEC-01` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-DESIGN-SECURITY` | 1 |
| `DLV-SEC-02` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-SEC-03` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-SEC-04` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-DATA-LEGAL` | 0 |
| `DLV-SEC-05` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-DATA-LEGAL` | 0 |
| `DLV-SEC-06` | `INTERNAL_GAP` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-DATA-LEGAL` | 1 |
| `DLV-SEC-07` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-SEC-08` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-SEC-09` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-SEC-10` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-STATIC-SECRET` | 1 |
| `DLV-SEC-11` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-SEC-12` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-STATIC-SECRET` | 1 |
| `DLV-SEC-13` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-SEC-14` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-SEC-15` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-SEC-16` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-SEC-17` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-DATA-LEGAL` | 0 |
| `DLV-SEC-18` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-SEC-19` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-DESIGN-SECURITY` | 0 |
| `DLV-TST-01` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-TST-02` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TEST-GOV-TRACE` | 1 |
| `DLV-TST-03` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TEST-ENV-DEVICE` | 1 |
| `DLV-TST-04` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-TST-05` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TEST-GOV-TRACE` | 1 |
| `DLV-TST-06` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TEST-GOV-TRACE` | 0 |
| `DLV-TST-07` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TEST-ENV-DEVICE` | 1 |
| `DLV-TST-08` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TEST-GOV-TRACE` | 0 |
| `DLV-TST-09` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TEST-ENV-DEVICE` | 1 |
| `DLV-TST-10` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-TST-11` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TEST-GOV-TRACE` | 1 |
| `DLV-TST-12` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-TST-13` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-TST-14` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TEST-ENV-DEVICE` | 1 |
| `DLV-TST-15` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-TST-16` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-TST-17` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-TST-18` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TEST-GOV-TRACE` | 1 |
| `DLV-TST-19` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TEST-GOV-TRACE` | 1 |
| `DLV-TST-20` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TEST-GOV-TRACE` | 1 |
| `DLV-TST-21` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-TEST-GOV-TRACE` | 1 |
| `DLV-TST-22` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-TST-23` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-APPROVAL-HANDOVER` | 0 |
| `DLV-WS-01` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-WS-02` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-WS-03` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-WS-04` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-WS-05` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-WS-06` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-FIELD-DEVICE` | 0 |
| `DLV-WS-07` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-FIELD-DEVICE` | 0 |
| `DLV-WS-08` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-WS-09` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-FIELD-DEVICE` | 0 |
| `DLV-WS-10` | `INTERNAL_GAP` | `INTERNAL_GAP` | `INTERNAL` | `FINAL-INT-EVALUATION-EQUIV` | 1 |
| `DLV-WS-11` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-WS-12` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-FIELD-DEVICE` | 0 |
| `DLV-WS-13` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-FIELD-DEVICE` | 0 |
| `DLV-WS-14` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-FIELD-DEVICE` | 0 |
| `DLV-WS-15` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-FIELD-DEVICE` | 0 |
| `DLV-WS-16` | `N_A_CANDIDATE` | `N_A_CANDIDATE` | `NONE` | `NONE` | 0 |
| `DLV-WS-17` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-FIELD-DEVICE` | 0 |
| `DLV-WS-18` | `INTERNAL_GAP` | `OK` | `NONE` | `NONE` | 1 |
| `DLV-WS-19` | `OK` | `OK` | `NONE` | `NONE` | 0 |
| `DLV-WS-20` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-FIELD-DEVICE` | 0 |
| `DLV-WS-21` | `EXTERNAL` | `EXTERNAL` | `EXTERNAL` | `FINAL-EXT-FIELD-DEVICE` | 0 |
| `DLV-WS-22` | `OK` | `OK` | `NONE` | `NONE` | 0 |

## Lossless artifact tuple projection

아래 payload는 snapshot JSON 및 external-action packet JSON의 `artifact_status_tuples`와 byte-semantic parity를 이룹니다.

<!-- FINAL-257-TUPLES-START -->
```json
[
  {
    "action_group_id": "FINAL-EXT-DATA-LEGAL",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-AIML-01",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-DATA-LEGAL",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-AIML-02",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-DATA-LEGAL",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-AIML-03",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-AIML-04",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-DATA-QUALITY-SPLIT",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-05",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-DATA-QUALITY-SPLIT",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-06",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-DATA-QUALITY-SPLIT",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-07",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-DATA-QUALITY-SPLIT",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-08",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-DATA-QUALITY-SPLIT",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-09",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-DATA-QUALITY-SPLIT",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-10",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TRAINING-MODEL",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-11",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TRAINING-MODEL",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-12",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TRAINING-MODEL",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-13",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-AIML-14",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TRAINING-MODEL",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-15",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TRAINING-MODEL",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-16",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-EVALUATION-EQUIV",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-17",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-AIML-18",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-AIML-19",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-AIML-20",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-INT-EVALUATION-EQUIV",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-21",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-EXT-DATA-LEGAL",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-AIML-22",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-INT-EVALUATION-EQUIV",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-AIML-23",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-AIML-24",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-AIML-25",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-AIML-26",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-CLS-01",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-CLS-02",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-CLS-03",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-CLS-04",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-CLS-05",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-CLS-06",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-CLS-07",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-CLS-08",
    "current_status": "EXTERNAL",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-CLS-09",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-CLS-10",
    "current_status": "EXTERNAL",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-CLS-11",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-CLS-12",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-CLS-13",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-EXT-RELEASE-OPS",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-CLS-14",
    "current_status": "EXTERNAL",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-EXT-RELEASE-OPS",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-CLS-15",
    "current_status": "EXTERNAL",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-EXT-RELEASE-OPS",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-CLS-16",
    "current_status": "EXTERNAL",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-01",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-02",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-03",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-04",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-05",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-06",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-07",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-08",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-09",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-10",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-11",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-12",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-13",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-14",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-DESIGN-SECURITY",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-DES-15",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-16",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-17",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-18",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-DESIGN-SECURITY",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-DES-19",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-DESIGN-SECURITY",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-DES-20",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-DES-21",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-22",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-23",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-24",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-25",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-26",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DES-27",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DEV-01",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DEV-02",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DEV-03",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DEV-04",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DEV-05",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DEV-06",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DEV-07",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DEV-08",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-BUILD-SUPPLY",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-DEV-09",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DEV-10",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DEV-11",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-INT-BUILD-SUPPLY",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-DEV-12",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DEV-13",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-INT-BUILD-SUPPLY",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-DEV-14",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DEV-15",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-INT-STATIC-SECRET",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-DEV-16",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-BUILD-SUPPLY",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-DEV-17",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DEV-18",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-BUILD-SUPPLY",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-DEV-19",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-BUILD-SUPPLY",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-DEV-20",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-INTEGRATION-RELEASE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-DEV-21",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DOC-01",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DOC-02",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DOC-03",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DOC-04",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DOC-05",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DSC-01",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DSC-02",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DSC-03",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DSC-04",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-DSC-05",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-DSC-06",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-RESEARCH-OBSERVATION",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-DSC-07",
    "current_status": "EXTERNAL",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DSC-08",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-RESEARCH-OBSERVATION",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-DSC-09",
    "current_status": "EXTERNAL",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DSC-10",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-DSC-11",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DSC-12",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DSC-13",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DSC-14",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-DSC-15",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-01",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-02",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-MGT-03",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-04",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-05",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-06",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-07",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-MGT-08",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-09",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-10",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-11",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-12",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-13",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-14",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-15",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-16",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-17",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-MGT-18",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-01",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-02",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-03",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-04",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-05",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-RELEASE-OPS",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-OPS-06",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-07",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-08",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-09",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-10",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-RELEASE-OPS",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-OPS-11",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-12",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-RELEASE-OPS",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-OPS-13",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-14",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-15",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-16",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-RELEASE-OPS",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-OPS-17",
    "current_status": "EXTERNAL",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-18",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-EXT-RELEASE-OPS",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-OPS-19",
    "current_status": "EXTERNAL",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-20",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-21",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-EXT-RELEASE-OPS",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-OPS-22",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-RELEASE-OPS",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-OPS-23",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-OPS-24",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-01",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-02",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-03",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-04",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-05",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-06",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-07",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-08",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-09",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-10",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-11",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-12",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-RELEASE-OPS",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-REL-13",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-14",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-INT-INTEGRATION-RELEASE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-REL-15",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-INTEGRATION-RELEASE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-REL-16",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REL-17",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-INTEGRATION-RELEASE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-REL-18",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-INTEGRATION-RELEASE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-REL-19",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-EXT-RELEASE-OPS",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-REL-20",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-RELEASE-OPS",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-REL-21",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-INT-INTEGRATION-RELEASE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-REL-22",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-01",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-02",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-03",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-04",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-05",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-06",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-07",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-08",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-09",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-10",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-11",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-REQ-12",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-REQ-13",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-14",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-REQ-15",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-16",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-17",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-18",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-REQ-19",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-DESIGN-SECURITY",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-SEC-01",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-SEC-02",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-SEC-03",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-EXT-DATA-LEGAL",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-SEC-04",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-DATA-LEGAL",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-SEC-05",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-DATA-LEGAL",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-SEC-06",
    "current_status": "EXTERNAL",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-SEC-07",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-SEC-08",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-SEC-09",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-STATIC-SECRET",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-SEC-10",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-SEC-11",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-STATIC-SECRET",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-SEC-12",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-SEC-13",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-SEC-14",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-SEC-15",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-SEC-16",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-DATA-LEGAL",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-SEC-17",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-SEC-18",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-INT-DESIGN-SECURITY",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-SEC-19",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-TST-01",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TEST-GOV-TRACE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-TST-02",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TEST-ENV-DEVICE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-TST-03",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-TST-04",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TEST-GOV-TRACE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-TST-05",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TEST-GOV-TRACE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-TST-06",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TEST-ENV-DEVICE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-TST-07",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TEST-GOV-TRACE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-TST-08",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TEST-ENV-DEVICE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-TST-09",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-TST-10",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-INT-TEST-GOV-TRACE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-TST-11",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-TST-12",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-TST-13",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-INT-TEST-ENV-DEVICE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-TST-14",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-TST-15",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-TST-16",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-TST-17",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-INT-TEST-GOV-TRACE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-TST-18",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TEST-GOV-TRACE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-TST-19",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TEST-GOV-TRACE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-TST-20",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-INT-TEST-GOV-TRACE",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-TST-21",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-TST-22",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-APPROVAL-HANDOVER",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-TST-23",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-WS-01",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-WS-02",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-WS-03",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-WS-04",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-WS-05",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-FIELD-DEVICE",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-WS-06",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-FIELD-DEVICE",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-WS-07",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-WS-08",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": "FINAL-EXT-FIELD-DEVICE",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-WS-09",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-INT-EVALUATION-EQUIV",
    "action_kind": "INTERNAL",
    "artifact_type_code": "DLV-WS-10",
    "current_status": "INTERNAL_GAP",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-WS-11",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-FIELD-DEVICE",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-WS-12",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-FIELD-DEVICE",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-WS-13",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-FIELD-DEVICE",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-WS-14",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-FIELD-DEVICE",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-WS-15",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-WS-16",
    "current_status": "N_A_CANDIDATE",
    "initial_status": "N_A_CANDIDATE"
  },
  {
    "action_group_id": "FINAL-EXT-FIELD-DEVICE",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-WS-17",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-WS-18",
    "current_status": "OK",
    "initial_status": "INTERNAL_GAP"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-WS-19",
    "current_status": "OK",
    "initial_status": "OK"
  },
  {
    "action_group_id": "FINAL-EXT-FIELD-DEVICE",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-WS-20",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": "FINAL-EXT-FIELD-DEVICE",
    "action_kind": "EXTERNAL",
    "artifact_type_code": "DLV-WS-21",
    "current_status": "EXTERNAL",
    "initial_status": "EXTERNAL"
  },
  {
    "action_group_id": null,
    "action_kind": "NONE",
    "artifact_type_code": "DLV-WS-22",
    "current_status": "OK",
    "initial_status": "OK"
  }
]
```
<!-- FINAL-257-TUPLES-END -->

## Fingerprints

- source-set: `c5a1c09c3b433d81cad0cb904f3ddae2b0a201e98a207b1fae959fdcea8e17b4`
- semantic: `449a1228b7cb8830dce9f0bb8f84f4a6129f478686d94027aa29be36780405ad`
- snapshot content: `84555e865755f5028ffe17f354b02b1d1e3ee94eba448d8a1122929111e5cc36`
- external packet content: `5b2fce734254a36d3956e475a3e0274cab817a0f252146e92b8b2e788f85c63b`

## 권한 경계

이 문서는 immutable baseline을 수정하지 않는 add-only successor입니다. W1~W9의 hash-bound GO authority만 replay하며 formal test, device, deployment, signing, legal, model, closure, gate waiver 또는 release 승인을 새로 부여하지 않습니다.
