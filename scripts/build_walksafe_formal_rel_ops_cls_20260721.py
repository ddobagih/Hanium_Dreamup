#!/usr/bin/env python3
"""Build controlled Draft REL/OPS/CLS deliverables for WalkSafe.

The builder materializes authorable plans, procedures, runbooks and empty
register structures.  Release artifacts, signatures, deployment/operation
executions and project-closure results remain Planned/NOT_RUN until real
evidence exists.
"""

from __future__ import annotations

import argparse
from collections import Counter
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile
from typing import Any, Iterable

try:
    from scripts import build_walksafe_feature_policy_baseline_approval_20260721 as approval_builder
except ModuleNotFoundError:  # Direct execution from scripts/.
    import build_walksafe_feature_policy_baseline_approval_20260721 as approval_builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
USER_GUIDE_PATH = REPO_ROOT / "apps" / "android" / "USER_GUIDE.md"
DATA_RETENTION_POLICY_PATH = REPO_ROOT / "docs" / "operations" / "data_retention_policy.md"
NAVIGATION_INTEGRATION_POLICY_PATH = REPO_ROOT / "docs" / "walksafe-v2" / "navigation_integration_policy.md"
DEPLOYMENT_DESCRIPTOR_PATH = REPO_ROOT / "model" / "deployments" / "local-deployment.json"
CONTROL_DIR = REPO_ROOT / "docs" / "control"
DELIVERABLES_DIR = REPO_ROOT / "docs" / "deliverables"
REL_DIR = DELIVERABLES_DIR / "09-release"
OPS_DIR = DELIVERABLES_DIR / "10-operations"
CLS_DIR = DELIVERABLES_DIR / "12-closure"

POLICY_PATH = CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-comprehensive-draft.json"
APPROVAL_RECORD_PATH = CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
BASELINE_MANIFEST_PATH = CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
ALIGNED_DECISION_REGISTER_PATH = CONTROL_DIR / "decision-interview" / "walksafe-effective-decision-register-aligned-20260721-r001.json"
ARTIFACT_CATALOG_PATH = CONTROL_DIR / "artifact-types.json"
ARTIFACT_BASELINE_CANDIDATE_PATH = (
    CONTROL_DIR
    / "baselines"
    / "walksafe-artifact-baseline-candidate-20260721-r001.json"
)
AUTHORING_PLAN_PATH = CONTROL_DIR / "documentation-authoring-preparation-plan.md"
PROJECT_ANSWERS_PATH = CONTROL_DIR / "questionnaire" / "source-records" / "walksafe-project-decisions-20260717-answers.json"
FP035_CORRECTION_CANDIDATE_PATH = CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"
GAP_ANALYSIS_R020_PATH = CONTROL_DIR / "audits" / "walksafe-implementation-gap-analysis-20260726-r020.json"
REMEDIATION_BACKLOG_R020_PATH = CONTROL_DIR / "audits" / "walksafe-implementation-remediation-backlog-20260726-r020.json"

REL_CONTROL_PATH = REL_DIR / "release-control.md"
REL_DEPLOYMENT_PATH = REL_DIR / "deployment-evidence.md"
REL_DELIVERY_PATH = REL_DIR / "delivery-and-handover.md"
REL_REGISTER_PATH = REL_DIR / "registers" / "release-control-register.json"
REL_EVIDENCE_TEMPLATE_PATH = REL_DIR / "templates" / "release-evidence-template.json"
REL_DEPLOYMENT_TEMPLATE_PATH = REL_DIR / "templates" / "deployment-execution-template.json"
REL_ACCEPTANCE_TEMPLATE_PATH = REL_DIR / "templates" / "external-acceptance-metadata-template.json"

OPS_GUIDE_PATH = OPS_DIR / "operator-guide.md"
OPS_RECOVERY_PATH = OPS_DIR / "recovery-plan.md"
OPS_CONTROL_PATH = OPS_DIR / "operations-control-registers.md"
OPS_REGISTER_PATH = OPS_DIR / "registers" / "operations-registers.json"
OPS_INCIDENT_TEMPLATE_PATH = OPS_DIR / "templates" / "incident-postmortem-template.json"
OPS_RECOVERY_TEMPLATE_PATH = OPS_DIR / "templates" / "recovery-exercise-template.json"

CLS_CLOSURE_PATH = CLS_DIR / "project-closure.md"
CLS_HANDOVER_PATH = CLS_DIR / "closure-handover-register.md"
CLS_DECOMMISSION_PATH = CLS_DIR / "decommissioning-plan.md"
CLS_REGISTER_PATH = CLS_DIR / "registers" / "closure-readiness-register.json"
CLS_EXECUTION_TEMPLATE_PATH = CLS_DIR / "templates" / "closure-execution-template.json"

MANIFEST_PATH = DELIVERABLES_DIR / "manifests" / "rel-ops-cls-draft-20260721-r001.json"
ARTIFACT_REGISTER_PATH = DELIVERABLES_DIR / "00-control" / "artifact-register.json"
ARTIFACT_CHANGE_LOG_PATH = (
    DELIVERABLES_DIR / "00-control" / "artifact-change-log.json"
)
ARTIFACT_REGISTER_README_PATH = (
    DELIVERABLES_DIR / "00-control" / "README.md"
)
ARTIFACT_REGISTER_CURRENT_NOTICE_PATH = (
    DELIVERABLES_DIR
    / "00-control"
    / "artifact-register-current-notice-20260728-r001.md"
)
ARTIFACT_CLOSURE_RUN_DIR = (
    CONTROL_DIR / "execution" / "artifact-closure" / "run-20260727-001"
)
READY25_PACKET_DIR = ARTIFACT_CLOSURE_RUN_DIR / "packets" / "phase1-ready25-cls-ops"
READY25_EVIDENCE_PATH = READY25_PACKET_DIR / "evidence.json"
READY25_RECEIPT_PATH = (
    READY25_PACKET_DIR / "phase1-ready25-cls-ops-check-receipt-r001.json"
)
SCOPE45_TRANSITION_RECEIPT_PATH = (
    ARTIFACT_CLOSURE_RUN_DIR / "phase1-scope45-transition-check-receipt-r001.json"
)
SCOPE45_TRANSITION_APPLICATION_PATH = (
    ARTIFACT_CLOSURE_RUN_DIR
    / "phase1-scope45-transition-application-r001.json"
)
SCOPE45_TRANSITION_APPLICATION_SHA256 = (
    "c4c665c07d3701296d6c47badc23c41da355bac2811db66653e79d426909d4ae"
)
SCOPE45_TRANSITION_RECEIPT_SHA256 = (
    "7015c81effdaab52dc83168484a36bd7a4fbdee6f8a405ab819d691bfa7810ad"
)
SCOPE45_PREDECESSOR_REGISTER_PROJECTION_SHA256 = (
    "2bd5e5cc4b7cc289934aabbc699c4665361739d7b03bf699cf88e89862c09187"
)
R010_EVIDENCE_PATH = (
    ARTIFACT_CLOSURE_RUN_DIR
    / "packets"
    / "phase1-exact257-successor-r010"
    / "evidence.json"
)
R010_RECEIPT_PATH = (
    ARTIFACT_CLOSURE_RUN_DIR / "phase1-exact257-successor-check-receipt-r010.json"
)
R007_LEDGER_PATH = (
    ARTIFACT_CLOSURE_RUN_DIR / "phase1-exact257-successor-ledger-r007.json"
)
READY25_CLS_OPS_R002_PACKET_DIR = (
    ARTIFACT_CLOSURE_RUN_DIR
    / "packets"
    / "phase1-ready25-cls-ops-r002"
)
READY25_CLS_OPS_R002_EVIDENCE_PATH = (
    READY25_CLS_OPS_R002_PACKET_DIR / "evidence.json"
)
READY25_CLS_OPS_R002_RECEIPT_PATH = (
    READY25_CLS_OPS_R002_PACKET_DIR
    / "phase1-ready25-cls-ops-check-receipt-r002.json"
)
READY25_REL_PACKET_DIR = (
    ARTIFACT_CLOSURE_RUN_DIR / "packets" / "phase1-ready25-rel"
)
READY25_REL_EVIDENCE_PATH = READY25_REL_PACKET_DIR / "evidence.json"
READY25_REL_RECEIPT_PATH = (
    READY25_REL_PACKET_DIR / "phase1-ready25-rel-check-receipt-r001.json"
)
READY25_AI_DEV_SEC_R001_EVIDENCE_PATH = (
    ARTIFACT_CLOSURE_RUN_DIR
    / "packets"
    / "phase1-ready25-ai-dev-sec"
    / "evidence.json"
)
READY25_AI_DEV_SEC_R001_RECEIPT_PATH = (
    ARTIFACT_CLOSURE_RUN_DIR
    / "phase1-ready25-ai-dev-sec-check-receipt-r001.json"
)
READY25_AI_DEV_SEC_R002_PACKET_DIR = (
    ARTIFACT_CLOSURE_RUN_DIR
    / "packets"
    / "phase1-ready25-ai-dev-sec-r002"
)
READY25_AI_DEV_SEC_R002_EVIDENCE_PATH = (
    READY25_AI_DEV_SEC_R002_PACKET_DIR / "evidence.json"
)
READY25_AI_DEV_SEC_R002_RECEIPT_PATH = (
    READY25_AI_DEV_SEC_R002_PACKET_DIR
    / "phase1-ready25-ai-dev-sec-check-receipt-r002.json"
)
READY25_CLS_OPS_R003_PACKET_DIR = (
    ARTIFACT_CLOSURE_RUN_DIR
    / "packets"
    / "phase1-ready25-cls-ops-r003"
)
READY25_CLS_OPS_R003_EVIDENCE_PATH = (
    READY25_CLS_OPS_R003_PACKET_DIR / "evidence.json"
)
READY25_CLS_OPS_R003_RECEIPT_PATH = (
    READY25_CLS_OPS_R003_PACKET_DIR
    / "phase1-ready25-cls-ops-check-receipt-r003.json"
)
READY25_REL_R002_PACKET_DIR = (
    ARTIFACT_CLOSURE_RUN_DIR / "packets" / "phase1-ready25-rel-r002"
)
READY25_REL_R002_EVIDENCE_PATH = READY25_REL_R002_PACKET_DIR / "evidence.json"
READY25_REL_R002_RECEIPT_PATH = (
    READY25_REL_R002_PACKET_DIR / "phase1-ready25-rel-check-receipt-r002.json"
)
ADD_ONLY_SUCCESSOR_OUTPUT_PATHS = (
    READY25_AI_DEV_SEC_R002_EVIDENCE_PATH,
    READY25_AI_DEV_SEC_R002_RECEIPT_PATH,
    READY25_CLS_OPS_R003_EVIDENCE_PATH,
    READY25_CLS_OPS_R003_RECEIPT_PATH,
    READY25_REL_R002_EVIDENCE_PATH,
    READY25_REL_R002_RECEIPT_PATH,
)

AS_OF = "2026-07-21"
VERSION = "0.1.0"
POLICY_BASELINE_ID = "PB-WALKSAFE-FEATURE-POLICY-1.0.0"
POLICY_DIGEST = "e49ffe0ee9e59de0cec173cac9425d61aa78e0ccd65980ba568045a3975e0b28"
DECISION_DIGEST = "16064cabf95dc16109bfe315032905a12881cab40cf7730e97d8171d15476538"
RELEASE_STATUS = "NOT_ELIGIBLE"
W9_AS_OF = "2026-07-27"
READY25_AS_OF = "2026-07-28"
READY25_PACKET_ID = "WS-PHASE1-READY25-CLS-OPS-EVIDENCE-20260728-R001"
READY25_RECEIPT_ID = "WS-PHASE1-READY25-CLS-OPS-CHECK-20260728-R001"
READY25_CLS_OPS_R002_PACKET_ID = (
    "WS-PHASE1-READY25-CLS-OPS-EVIDENCE-20260728-R002"
)
READY25_CLS_OPS_R002_RECEIPT_ID = (
    "WS-PHASE1-READY25-CLS-OPS-CHECK-20260728-R002"
)
READY25_REL_PACKET_ID = "WS-PHASE1-READY25-REL-EVIDENCE-20260728-R001"
READY25_REL_RECEIPT_ID = "WS-PHASE1-READY25-REL-CHECK-20260728-R001"
READY25_AI_DEV_SEC_R002_PACKET_ID = (
    "PHASE1-READY25-AI-DEV-SEC-20260728-R002"
)
READY25_AI_DEV_SEC_R002_RECEIPT_ID = (
    "PHASE1-READY25-AI-DEV-SEC-CHECK-20260728-R002"
)
READY25_CLS_OPS_R003_PACKET_ID = (
    "WS-PHASE1-READY25-CLS-OPS-EVIDENCE-20260728-R003"
)
READY25_CLS_OPS_R003_RECEIPT_ID = (
    "WS-PHASE1-READY25-CLS-OPS-CHECK-20260728-R003"
)
READY25_REL_R002_PACKET_ID = (
    "WS-PHASE1-READY25-REL-EVIDENCE-20260728-R002"
)
READY25_REL_R002_RECEIPT_ID = (
    "WS-PHASE1-READY25-REL-CHECK-20260728-R002"
)
READY25_R001_EVIDENCE_SHA256 = (
    "04babe6a092f8a4f9a7bedb463f7d8dbd84691c328472d3911c9e08fbe8e322e"
)
READY25_R001_RECEIPT_SHA256 = (
    "05bdabf4c5f332207c98c10c27395cc02f8a12ad9e532ed8a2ff3aed7fc60b67"
)
READY25_AI_DEV_SEC_R001_EVIDENCE_SHA256 = (
    "7c3045a4446ef86d14c42ee8230411590c2801a22fb498861f6cdd361a48962f"
)
READY25_AI_DEV_SEC_R001_RECEIPT_SHA256 = (
    "ceecad94da6a84b80143793b232ad556aa1460508cfd5abedc732c1df8799a7b"
)
READY25_CLS_OPS_R002_EVIDENCE_SHA256 = (
    "1d2d74361be98204ba827a1f7583a631146b5131854f573c47c0c7a7d9fcac1d"
)
READY25_CLS_OPS_R002_RECEIPT_SHA256 = (
    "a099edb275a944b1a44bc5d7dc9c4f87de7cc2c9d4fd1d28cd44f641fa8c7270"
)
READY25_REL_R001_EVIDENCE_SHA256 = (
    "52d899dba79d9143337fd8df85f3a9bf94171237fd6e262e73e75d642c923b09"
)
READY25_REL_R001_RECEIPT_SHA256 = (
    "0c41a32fe2e83cacb3737008f0815cc386fc3ce30f9926226c3141cffd1e8f05"
)
ARTIFACT_BASELINE_CANDIDATE_SHA256 = (
    "af0d9ca906cd40a3ca1aa65fa9e34d5a0733add4563e6299ef5a0c9147aa3683"
)
FP035_ISSUE_ID = "ISS-POLICY-FP035-NETWORK-001"
FP035_NORMALIZATION_STATUS = "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL"
FP035_NORMALIZED_POLICY = {
    "walking": "일반 활동원본은 보행 중 휴대전화에 암호화해 저장하고 서버로 전송하지 않는다.",
    "stopped_mobile_opted_in": "사용자가 이동통신망 전송을 명시적으로 선택한 경우에만 정지 판정 뒤 이동통신망 전송을 허용한다.",
    "stopped_mobile_not_opted_in": "이동통신망 전송을 선택하지 않은 사용자는 정지 뒤에도 Wi-Fi에서만 전송한다.",
}
FP035_NORMATIVE_RULE = "일반 활동원본은 보행 중 전송하지 않는다. 사용자가 이동통신망 전송을 명시적으로 선택한 경우 보행이 정지한 뒤 허용된 이동통신망으로 전송할 수 있으며, 선택하지 않은 경우에는 Wi-Fi에서만 전송한다."
GATE_IDS = [
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
]

READY25_EXACT13 = (
    "CLS-04",
    "CLS-08",
    "CLS-10",
    "CLS-11",
    "CLS-14",
    "CLS-15",
    "CLS-16",
    "OPS-06",
    "OPS-07",
    "OPS-11",
    "OPS-13",
    "OPS-18",
    "OPS-22",
)
READY25_AI_DEV_SEC_EXACT9 = (
    "AIML-19",
    "AIML-20",
    "AIML-25",
    "AIML-26",
    "DEV-10",
    "DEV-11",
    "DEV-13",
    "DEV-15",
    "SEC-18",
)
READY25_REL_EXACT3 = ("REL-03", "REL-05", "REL-09")
READY25_EXACT25 = (
    *READY25_AI_DEV_SEC_EXACT9,
    *READY25_EXACT13,
    *READY25_REL_EXACT3,
)
READY25_PREDECESSOR_REGISTER_PROJECTION_SHA256 = (
    "0c3abc9217c6385e380382854b4a7f0ffd06dc393b856d810e2592670e7be2f8"
)
READY25_CONTROLLED_ROW_PROJECTION_SHA256 = (
    "aa71becdf1885902b96fb5b6c5b64f19cd46c30a7852549b2bcf60a19d9200a5"
)
READY25_REGISTER_SOURCE_PROJECTION_SHA256 = (
    "ffaa2a923f3241d2b39082baf8d6d1bbd52927dab15caf3e281c63cfb9b719c2"
)
READY25_REGISTER_METADATA_STABLE_SHA256 = (
    "8524c41e29779903766bb93781e5acb1f0e252d9572c0f021f1f6517ef10b56f"
)
READY25_QA_REQUIRED = {
    "CLS-04",
    "CLS-08",
    "CLS-10",
    "CLS-11",
    "CLS-14",
    "CLS-15",
    "CLS-16",
}
READY25_ACTIVATION_STATES = {
    "CLS-04": "IN_SCOPE_FINAL_KPI_MEASUREMENT_WINDOW_PENDING",
    "CLS-08": "IN_SCOPE_CLOSURE_RISK_ACCEPTANCE_NOT_TRIGGERED",
    "CLS-10": "IN_SCOPE_HANDOVER_RECIPIENT_UNRESOLVED",
    "CLS-11": "IN_SCOPE_RETROSPECTIVE_NOT_HELD",
    "CLS-14": "IN_SCOPE_DATA_DISPOSITION_EVENT_NOT_TRIGGERED",
    "CLS-15": "IN_SCOPE_ASSET_DISPOSITION_EVENT_NOT_TRIGGERED",
    "CLS-16": "NOT_TRIGGERED_OPERATIONS_CONTINUE_NO_SHUTDOWN_DECISION",
    "OPS-06": "IN_SCOPE_OPERATING_ENVIRONMENT_AND_METRIC_SOURCE_PENDING",
    "OPS-07": "IN_SCOPE_ALERTING_ENVIRONMENT_AND_CHANNEL_PENDING",
    "OPS-11": "IN_SCOPE_RESTORE_EXERCISE_NOT_RUN",
    "OPS-13": "IN_SCOPE_DR_DRILL_NOT_RUN",
    "OPS-18": "NOT_TRIGGERED_INCIDENT_COUNT_0",
    "OPS-22": "IN_SCOPE_LIVE_RESOURCE_BILLING_AND_USAGE_PENDING",
}
READY25_DUE_CONDITIONS = {
    "CLS-04": "AFTER_FINAL_KPI_WINDOW_BEFORE_PROJECT_CLOSURE",
    "CLS-08": "BEFORE_PROJECT_CLOSURE_AND_EACH_RISK_ACCEPTANCE_REVIEW",
    "CLS-10": "BEFORE_OPERATIONAL_HANDOVER_EFFECTIVE_AT",
    "CLS-11": "AFTER_PHASE_OR_PROJECT_END_BEFORE_RETROSPECTIVE_ACCEPTANCE",
    "CLS-14": "BEFORE_EACH_DATA_TRANSFER_OR_DELETION_AND_PROJECT_CLOSURE",
    "CLS-15": "BEFORE_EACH_ASSET_TRANSFER_ROTATION_OR_REVOCATION",
    "CLS-16": "BEFORE_ANY_SHUTDOWN_OR_DECOMMISSION_AUTHORIZATION",
    "OPS-06": "BEFORE_LIVE_OPERATIONS_READINESS_APPROVAL",
    "OPS-07": "BEFORE_LIVE_ALERTING_ENABLEMENT",
    "OPS-11": "BEFORE_BACKUP_RESTORE_CAPABILITY_APPROVAL",
    "OPS-13": "BEFORE_DR_CAPABILITY_APPROVAL",
    "OPS-18": "ONLY_AFTER_A_QUALIFYING_REAL_INCIDENT_ENDS",
    "OPS-22": "BEFORE_PAID_OR_CAPACITY_LIMITED_OPERATIONS_REVIEW",
}
READY25_INTERNAL_CHECKLISTS = {
    "CLS-04": [
        "KPI ID·정의·계산식·baseline source·target source를 같은 표에서 추적한다.",
        "측정기간·표본·수집방법·분석 owner를 정하되 실제 측정값은 만들지 않는다.",
        "최종 KPI window 종료 전 실측·효과·미달조치·승인을 NOT_RUN으로 유지한다.",
    ],
    "CLS-08": [
        "5개 gate와 FP-035를 포함한 잔여위험 source pointer와 record schema를 유지한다.",
        "위험별 mitigation·monitoring·owner·due condition을 실제 source에서만 채운다.",
        "acceptor identity·acceptance decision·receipt는 실제 종료 검토 전 비워 둔다.",
    ],
    "CLS-10": [
        "자산·계정·권한·secret reference·backlog·risk·support 이관 checklist를 유지한다.",
        "현재 운영 지속 branch를 보존하고 named recipient/operator는 unresolved로 둔다.",
        "권한 test·회수·양측 확인·effective time·receipt는 실제 이관 전 생성하지 않는다.",
    ],
    "CLS-11": [
        "회고 범위·입력 evidence·참여 역할·실시 조건과 action schema를 정한다.",
        "의도 대비 실제 결과와 근본 원인은 실제 회고 참여자가 확인한 뒤에만 작성한다.",
        "incident 0 경계를 postmortem 또는 성공 증거로 바꾸지 않는다.",
    ],
    "CLS-14": [
        "data class·storage location·잠정 retention/transfer/deletion branch schema를 유지한다.",
        "법적 근거·최종 한국어 고지·operator·backup reconciliation을 blocker로 둔다.",
        "실제 transfer/deletion과 receipt는 승인된 종료 branch 전 생성하지 않는다.",
    ],
    "CLS-15": [
        "secret 값을 제외한 asset ID·유형·controller·예정 action schema를 유지한다.",
        "retain/transfer/rotate/revoke, 최소권한, 복구경계 검증 checklist를 둔다.",
        "실제 rotation/revocation·비용 종료·receipt는 실행 전 생성하지 않는다.",
    ],
    "CLS-16": [
        "현재 branch를 OPERATIONS_CONTINUE로 유지하고 shutdown decision은 없다고 명시한다.",
        "미래 폐기는 고지·신규세션 차단·CLS-14·CLS-15·provider exit·최종검증 순서로 계획한다.",
        "실제 shutdown authority·operator·execution·receipt는 승인 전 생성하지 않는다.",
    ],
    "OPS-06": [
        "health·latency·error·DB·queue·storage·TMAP·STT·model·보안 panel schema를 정한다.",
        "dashboard-as-code path·version·metric source·owner·time range checklist를 둔다.",
        "live URL·snapshot·freshness·실측 metric은 운영 환경 준비 전 생성하지 않는다.",
    ],
    "OPS-07": [
        "alert ID·신호·조건·severity·window·dedup·silence·runbook schema를 정한다.",
        "notification 대상·채널·해제·사후검토를 배포 전 checklist로 둔다.",
        "fire/clear/delivery 결과는 실제 alerting 환경 시험 전 생성하지 않는다.",
    ],
    "OPS-11": [
        "정본 복원 절차와 실행 결과를 분리하고 backup 선택·격리환경·검증 schema를 정한다.",
        "object count·bytes·hash·DB reference·권한·RTO·RPO checklist를 둔다.",
        "실제 backup ID/hash·복원 결과·결함·receipt는 시험 전 생성하지 않는다.",
    ],
    "OPS-13": [
        "재해 시나리오·지휘·연락·권한·대체 infra·failover/failback 계획을 정한다.",
        "훈련 시간·판정·gap·개선·재훈련 schema를 둔다.",
        "실제 drill·전환·복귀·서명 결과는 훈련 전 생성하지 않는다.",
    ],
    "OPS-18": [
        "OPS-17 incident 원장과 중대도·반복 trigger를 연결한 postmortem schema를 유지한다.",
        "현재 incident count 0은 NOT_TRIGGERED이며 무장애 증거가 아니라고 명시한다.",
        "실제 incident 없이 timeline·원인·action·postmortem receipt를 생성하지 않는다.",
    ],
    "OPS-22": [
        "300 GiB+300 GiB·30,000원·70/85/95/100%를 실측이 아닌 planning assumption으로 구분한다.",
        "자원·단가·청구 source·사용량·forecast·budget alert·비용배분 schema를 정한다.",
        "실제 청구·사용·추세·비용 결과는 운영 source 확보 전 생성하지 않는다.",
    ],
}
READY25_REL_ACTIVATION_STATES = {
    "REL-03": "IN_SCOPE_RELEASE_VERSION_NOT_NAMED",
    "REL-05": "IN_SCOPE_RELEASE_CANDIDATE_COMMIT_TAG_NOT_AVAILABLE",
    "REL-09": "IN_SCOPE_RELEASE_NOTES_CANDIDATE_NOT_NAMED",
}
READY25_REL_DUE_CONDITIONS = {
    "REL-03": "BEFORE_NAMED_VERSION_PUBLICATION_OR_EXTERNAL_DELIVERY",
    "REL-05": "AFTER_NAMED_CANDIDATE_APPROVAL_BEFORE_SOURCE_FREEZE",
    "REL-09": "AFTER_NAMED_VERSION_AND_CHANGESET_BEFORE_DISTRIBUTION",
}
READY25_REL_INTERNAL_CHECKLISTS = {
    "REL-03": [
        "제품·Android 앱·관리자 앱·server·API·DB schema·model·config version 체계를 정의한다.",
        "compatibility·breaking change·upgrade·support·EOL 규칙을 정하되 실제 release ID는 만들지 않는다.",
        "development→controlled demo→beta→production promotion과 rollback 조건을 5개 gate에 연결한다.",
    ],
    "REL-05": [
        "repository·commit SHA·protected tag·signature·submodule·LFS·dirty-tree 금지 schema를 정의한다.",
        "REL-04 manifest와 동일 release generation으로 source binding을 결속하는 절차를 정한다.",
        "named release candidate 승인 전 실제 commit/tag/signature 또는 conformance PASS를 생성하지 않는다.",
    ],
    "REL-09": [
        "release·날짜·대상·기능·결함·보안·migration·known issue·지원 링크 template을 정의한다.",
        "실제 change set과 잔여 제한은 named candidate의 검증된 source에서만 채우도록 한다.",
        "promotion·rollback·5개 gate 상태를 명시하되 배포·승인·release 결과를 생성하지 않는다.",
    ],
}

DOCUMENT_COVERAGE = {
    REL_CONTROL_PATH: [f"REL-{number:02d}" for number in range(1, 11)],
    REL_DEPLOYMENT_PATH: [f"REL-{number:02d}" for number in range(11, 16)],
    REL_DELIVERY_PATH: [f"REL-{number:02d}" for number in range(16, 23)],
    OPS_GUIDE_PATH: [f"OPS-{number:02d}" for number in range(1, 10)],
    OPS_RECOVERY_PATH: [f"OPS-{number:02d}" for number in range(10, 14)],
    OPS_CONTROL_PATH: [f"OPS-{number:02d}" for number in range(14, 25)],
    CLS_CLOSURE_PATH: ["CLS-01", "CLS-02", "CLS-03", "CLS-04", "CLS-11", "CLS-12"],
    CLS_HANDOVER_PATH: [f"CLS-{number:02d}" for number in range(5, 11)],
    CLS_DECOMMISSION_PATH: [f"CLS-{number:02d}" for number in range(13, 17)],
}

BUNDLE_BY_PATH = {
    REL_CONTROL_PATH: "BND-REL-CONTROL",
    REL_DEPLOYMENT_PATH: "BND-REL-DEPLOYMENT",
    REL_DELIVERY_PATH: "BND-REL-DELIVERY",
    OPS_GUIDE_PATH: "BND-OPS-GUIDE",
    OPS_RECOVERY_PATH: "BND-OPS-RECOVERY",
    OPS_CONTROL_PATH: "BND-OPS-CONTROL",
    CLS_CLOSURE_PATH: "BND-CLS-CLOSURE",
    CLS_HANDOVER_PATH: "BND-CLS-HANDOVER",
    CLS_DECOMMISSION_PATH: "BND-CLS-DECOMMISSION",
}

# These are real Draft deliverables: plans, procedures and empty/pre-opened
# registers.  A Draft does not imply approval or execution.
MATERIALIZED_ARTIFACT_TYPE_IDS = [
    "REL-01", "REL-02", "REL-10", "REL-11", "REL-12", "REL-15",
    "REL-16", "REL-17", "REL-18", "REL-19", "REL-22",
    "OPS-01", "OPS-02", "OPS-03", "OPS-04", "OPS-05",
    "OPS-08", "OPS-09", "OPS-10", "OPS-11", "OPS-12", "OPS-13",
    "OPS-14", "OPS-15", "OPS-16", "OPS-17", "OPS-19", "OPS-20",
    "OPS-21", "OPS-22", "OPS-23", "OPS-24",
    "CLS-07", "CLS-08", "CLS-09", "CLS-10", "CLS-14", "CLS-15", "CLS-16",
]

# These require a named release, a real execution/external signature, or a
# project-closure event.  Templates do not materialize these artifact types.
PLANNED_ARTIFACT_TYPE_IDS = [
    "REL-03", "REL-04", "REL-05", "REL-06", "REL-07", "REL-08", "REL-09",
    "REL-13", "REL-14", "REL-20", "REL-21",
    "OPS-06", "OPS-07", "OPS-18",
    "CLS-01", "CLS-02", "CLS-03", "CLS-04", "CLS-05", "CLS-06",
    "CLS-11", "CLS-12", "CLS-13",
]

SCOPE_ARTIFACT_TYPE_IDS = [
    *[f"REL-{number:02d}" for number in range(1, 23)],
    *[f"OPS-{number:02d}" for number in range(1, 25)],
    *[f"CLS-{number:02d}" for number in range(1, 17)],
]

SUPPORTING_ARTIFACT_IDS = {
    REL_REGISTER_PATH: [],
    REL_EVIDENCE_TEMPLATE_PATH: [],
    REL_DEPLOYMENT_TEMPLATE_PATH: [],
    REL_ACCEPTANCE_TEMPLATE_PATH: [],
    OPS_REGISTER_PATH: [],
    OPS_INCIDENT_TEMPLATE_PATH: [],
    OPS_RECOVERY_TEMPLATE_PATH: [],
    CLS_REGISTER_PATH: [],
    CLS_EXECUTION_TEMPLATE_PATH: [],
}

EXECUTION_BOUNDARY_CODES = {
    "REL-15", "REL-16", "REL-17", "REL-18", "REL-19", "REL-22",
    "OPS-11", "OPS-13", "OPS-17", "OPS-19", "OPS-20", "OPS-21", "OPS-22", "OPS-23", "OPS-24",
    "CLS-07", "CLS-08", "CLS-09", "CLS-10", "CLS-14", "CLS-15", "CLS-16",
}


class RelOpsClsError(ValueError):
    """Raised when controlled inputs or generated outputs are inconsistent."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise RelOpsClsError(message)


def _strict_json_equal(actual: Any, expected: Any) -> bool:
    if type(actual) is not type(expected):
        return False
    if isinstance(actual, dict):
        return (
            actual.keys() == expected.keys()
            and all(
                _strict_json_equal(actual[key], expected[key])
                for key in actual
            )
        )
    if isinstance(actual, list):
        return (
            len(actual) == len(expected)
            and all(
                _strict_json_equal(actual_item, expected_item)
                for actual_item, expected_item in zip(
                    actual,
                    expected,
                )
            )
        )
    return actual == expected


def _reject_constant(value: str) -> None:
    raise RelOpsClsError(f"non-standard JSON number is not allowed: {value}")


def _object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        _require(key not in value, f"duplicate JSON key: {key}")
        value[key] = item
    return value


def load_strict_json(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    _require(not raw.startswith(b"\xef\xbb\xbf"), f"UTF-8 BOM is not allowed: {path}")
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_object_pairs,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise RelOpsClsError(f"invalid JSON: {path}: {exc}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def _sha_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha_file(path: Path) -> str:
    return _sha_bytes(path.read_bytes())


def _object_sha(value: Any) -> str:
    serialized = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return _sha_bytes(serialized.encode("utf-8"))


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _md_bytes(value: str) -> bytes:
    return (value.rstrip() + "\n").encode("utf-8")


def _rel(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _binding(path: Path) -> dict[str, str]:
    return {"path": _rel(path), "sha256": _sha_file(path)}


def _project_facts() -> dict[str, Any]:
    """Normalize answers already supplied by the owner; do not reopen them."""

    source = load_strict_json(PROJECT_ANSWERS_PATH)
    answers = source.get("answers", {})
    notes = source.get("notes", {})
    expected = {
        "Q-GOV-003": "production",
        "Q-GOV-011": "milestone",
        "Q-REL-007": "managed_cloud",
        "Q-REL-008": "beta",
        "Q-REL-011": "staging_only",
        "Q-OPS-001": "나 ",
        "Q-OPS-002": "business_hours",
        "Q-OPS-003": "no_slo_beta",
    }
    for question_id, expected_value in expected.items():
        _require(answers.get(question_id) == expected_value, f"approved project answer differs: {question_id}")
    milestone_note = notes.get("Q-GOV-011", "")
    _require(
        "정해진 예산은 없고" in milestone_note and "7월 26일" in milestone_note,
        "project milestone note differs",
    )
    return {
        "target_stage": "PRODUCTION_AFTER_REQUIRED_GATES",
        "release_roadmap": ["DEVELOPMENT", "CONTROLLED_DEMO", "BETA", "RELEASE"],
        "controlled_demo_date": "2026-07-26",
        "controlled_demo_is_release": False,
        "fixed_project_budget_krw": None,
        "deployment_model": "MANAGED_CLOUD_STAGING_FIRST",
        "operations_owner_role": "PROJECT_OWNER_SINGLE_ADMIN",
        "operations_owner_source_answer": "Q-OPS-001",
        "support_window": "BUSINESS_HOURS",
        "beta_slo_policy": "NO_NUMERIC_SLO_UNTIL_MEASURED",
        "source_path": _rel(PROJECT_ANSWERS_PATH),
    }


def _fp035_correction_candidate() -> dict[str, Any]:
    candidate = load_strict_json(FP035_CORRECTION_CANDIDATE_PATH)
    content = {key: value for key, value in candidate.items() if key != "candidate_content_sha256"}
    _require(candidate.get("candidate_content_sha256") == _object_sha(content), "FP-035 correction candidate hash differs")
    metadata = candidate.get("metadata", {})
    _require(metadata.get("approval_status") == "NOT_APPROVED", "FP-035 candidate approval differs")
    _require(metadata.get("effective_status") == "NOT_EFFECTIVE", "FP-035 candidate effectiveness differs")
    _require(candidate.get("correction", {}).get("normative_rule") == FP035_NORMATIVE_RULE, "FP-035 correction rule differs")
    _require(candidate.get("planned_effective_policy", {}).get("state_change_now") is False, "FP-035 candidate changed policy state")
    boundary = candidate.get("authorization_boundary", {})
    _require(boundary.get("owner_directive_captured") is True and boundary.get("new_product_question_required") is False, "FP-035 owner directive boundary differs")
    _require(boundary.get("candidate_generation_is_approval") is False, "FP-035 candidate was treated as approval")
    return candidate


def _bullet(values: Iterable[str], empty: str = "해당 없음") -> str:
    items = list(values)
    return "\n".join(f"- {item}" for item in items) if items else f"- {empty}"


def _catalog_by_code(catalog: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {
        item["display_code"]: item
        for item in catalog["artifact_types"]
        if item.get("category") in {"REL", "OPS", "CLS"}
    }


def _draft_codes_for(path: Path) -> list[str]:
    materialized = set(MATERIALIZED_ARTIFACT_TYPE_IDS)
    return [code for code in DOCUMENT_COVERAGE[path] if code in materialized]


def _planned_codes_for(path: Path) -> list[str]:
    planned = set(PLANNED_ARTIFACT_TYPE_IDS)
    return [code for code in DOCUMENT_COVERAGE[path] if code in planned]


SPECIFIC_RULES: dict[str, list[str]] = {
    "REL-01": [
        "승인된 진행 순서는 개발 → 2026-07-26 통제 시연 → 베타 → 정식 출시다. 통제 시연은 릴리스나 사용자 안전성 승인으로 간주하지 않는다.",
        "현재는 명명된 릴리스 후보가 없으므로 release ID·artifact·승인자를 꾸며 쓰지 않는다. 정해진 전체 프로젝트 예산도 없으므로 실제 견적 전 금액을 만들지 않는다.",
        "릴리스 후보를 열 때 Android 사용자 앱, 별도 Android 관리자 앱, 서버, 모델, 설정, DB migration을 하나의 범위로 묶고 Web/PWA는 레거시 참고로 제외한다.",
        "5개 gate, TST-22, 보안·개인정보 검토, rollback 가능성을 모두 릴리스 진입조건으로 둔다.",
    ],
    "REL-02": [
        "현재 판정은 NOT_ELIGIBLE이며 5개 gate는 모두 NOT_RUN·미면제다.",
        "체크리스트 항목은 근거 ID와 판정자를 요구하며 공란·자기확인만으로 Go로 바꾸지 않는다.",
        "무가림 원본 수집 독립검토와 단일 관리자 복구훈련이 끝나기 전에는 승인할 수 없다.",
    ],
    "REL-03": ["명명된 release ID와 확정 artifact가 생긴 뒤 버전·호환성·변경범위를 기록한다."],
    "REL-04": ["source commit·tag, build·model·config·migration, hash·서명·SBOM·시험보고서를 같은 release generation으로 결속한 기계판독 manifest만 인정한다."],
    "REL-05": ["실제 release commit과 tag가 만들어진 뒤 불변 ID와 remote 위치를 기록하며 현재는 생성했다고 주장하지 않는다."],
    "REL-06": ["실제 APK/AAB·서버 image·모델 파일을 생성한 뒤 byte length와 SHA-256을 기록한다. PWA는 현행 정식 산출물에 포함하지 않는다."],
    "REL-07": ["각 파일 hash를 실제 바이트에서 계산하고 서명이 필요하면 서명자·키 ID·시각·검증 결과를 함께 보존한다."],
    "REL-08": ["실제 build 입력과 도구chain이 고정된 뒤 release별 SBOM과 provenance를 생성한다."],
    "REL-09": ["명명된 release의 실제 변경·해결·잔여 문제만 기록하고 계획을 완료 사실로 바꾸지 않는다."],
    "REL-10": [
        f"{FP035_ISSUE_ID}는 정규화 지시 포착·묶음 승인 대기로 유지한다. 일반 활동원본은 보행 중 전송하지 않고, 정지 뒤에는 이동통신망 명시 선택 시 이동통신망을 허용하며 미선택 시 Wi-Fi만 허용한다. 새 묶음 승인 전 관련 정식 시험은 NOT_RUN이다.",
        "5개 미실행 gate와 실제 사용자 영향·우회 가능성·우회 금지 조건을 릴리스 후보마다 다시 평가한다.",
        "위험 탐지·길안내를 안전보장 기능으로 설명하지 않고 Web/PWA를 Android 합격 근거로 쓰지 않는다.",
    ],
    "REL-11": [
        "배포 전 immutable release manifest, 환경, DB backup, secret·certificate, 관측·rollback 준비를 확인한다.",
        "한 번에 전체 전환하지 않고 승인된 단계에서만 진행하며 단계별 중단 기준을 먼저 기록한다.",
        "배포자는 실행 시각·명령·대상·실제 결과를 별도 append-only execution에 남긴다.",
    ],
    "REL-12": [
        "DB·데이터 migration은 사전검사, backup, dry-run, 적용, 검증, rollback 순서로 수행한다.",
        "원본·동의·삭제 상태를 잃거나 보존기한을 되돌리는 migration은 허용하지 않는다.",
        "실제 schema version과 실행 결과는 명명된 release가 생길 때만 기록한다.",
    ],
    "REL-13": ["TST-22가 Go 또는 Conditional Go를 내린 뒤에만 실제 배포 환경에서 smoke test를 실행한다."],
    "REL-14": ["TST-22가 Go 또는 Conditional Go를 내린 뒤 승인된 소규모 단계에서만 canary를 실행하고 비교지표·중단·확대 판정을 남긴다."],
    "REL-15": [
        "절차 Draft는 유지하되 rollback 실행 결과는 현재 NOT_RUN이다.",
        "중대 안전·개인정보·인증·데이터 무결성 이상이나 smoke/canary 중단 기준 충족 시 신규 세션을 막는다. 현재 DB와 호환되고 새 자료가 손실되지 않음을 시험으로 입증한 구성요소만 승인된 이전판으로 복귀하며, 그렇지 않은 구성요소는 안전정지한 채 수정판을 준비한다.",
        "DB가 비가역이면 애플리케이션만 되돌려 정상으로 표시하지 않고 별도 복구 절차와 사용자 영향판정을 수행한다.",
    ],
    "REL-16": ["Android 사용자 앱과 별도 비공개 Android 관리자 앱의 지원 OS·권한·설치원·버전 확인을 구분하며 Web/PWA 설치는 현행 범위가 아니다."],
    "REL-17": [
        "사용자 선택은 RAW_SOURCE_COLLECTION, AUTOMATIC_REPORTING, MOBILE_NETWORK_TRANSFER, TRAINING_REUSE 네 항목으로 독립한다. Android 권한, 로그인, 다른 선택 하나가 나머지 동의를 대신하지 않는다.",
        "일반 활동원본은 보행 중 서버로 전송하지 않는다. 정지 판정 뒤 MOBILE_NETWORK_TRANSFER를 선택한 경우에만 이동통신망 전송을 허용하고, 선택하지 않은 경우에는 Wi-Fi에서만 전송한다.",
        "철회는 해당 선택의 새 수집·보고·전송·학습 재사용을 중단하고 대기 작업을 차단한다. 삭제는 별도 요청과 서버 처리 증거가 필요하며, 재동의는 새 version과 시각으로 기록하고 과거 처리나 삭제 요청을 소급 취소하지 않는다.",
        "철회·삭제·재동의의 기술 동작과 법적 보유기간·예외·최종 한국어 동의 문안은 구분한다. 법적 보유기간과 최종 문안은 외부 검토·승인 전까지 NOT_APPROVED다.",
        "TMAP 목적지·위치 처리에는 제3자 제공/처리 관계가 있다. 국외이전 여부, 계약상 역할, 고지·동의 문구는 외부 계약·법률 검토 전 확정하지 않는다.",
        "W4의 formal 279, 실기기, TMAP live, 배포는 NOT_RUN이다. W5의 열린 finding은 유지하며 signing/deployment 평가는 NOT_ASSESSED다.",
        "W7은 exact 3개 모델 등록 완전성만 확인한다. 모델 평가는 NOT_RUN, 동등성 및 출시 승인은 NOT_APPROVED이며 전체 출시는 NOT_ELIGIBLE이다.",
        "Web/PWA와 unsigned APK는 정식 Android release나 설치·배포·사용자 검증 증거가 아니다.",
    ],
    "REL-18": ["단일 관리자 계정은 MFA 또는 패스키를 사용하고 복구수단을 관리자 휴대전화 밖에 보관하며 고위험 작업 동결·세션 폐기 절차를 설명한다."],
    "REL-19": ["시연은 실제 build와 명확히 연결하고 미실행 시험이나 mock 화면을 운영 완료 증거로 제시하지 않는다."],
    "REL-20": ["운영 수신자·인계 범위·권한·미해결 항목이 확정되고 실제 인계가 이뤄진 뒤 외부 원본으로 작성한다."],
    "REL-21": ["지정 검수자가 실제 release와 근거를 확인한 뒤 서명하며 서명 원본은 Git 밖 통제 저장소에 보관한다."],
    "REL-22": ["실제 release dependency와 artifact를 기준으로 라이선스·저작권·고지를 다시 생성·검토하며 후보 목록을 최종 고지로 간주하지 않는다."],

    "OPS-01": [
        "정식 운영 전 서비스 소유자·지원·관측·장애·변경·백업·보안·비용 절차를 모두 활성화한다.",
        "한 명의 관리자로 운영하되 존재하지 않는 두 번째 승인자를 만들지 않는다.",
        "대체 경보와 제한권한 비상 대응자가 준비되지 않은 기간에는 신규 보행 서비스를 운영하지 않는다.",
    ],
    "OPS-02": ["사용자 답변으로 프로젝트 책임자 본인이 단일 관리자·최종 승인자 역할을 맡는 것은 확정됐다. 문서에는 불필요한 실명 대신 역할·계정 ID를 쓰고 공용 비밀번호를 사용하지 않는다."],
    "OPS-03": ["일반 지원시간은 평일 09:00~18:00이다. 그 밖의 시간에도 중대 안전·보안 경보를 버리지 않고 자동 신규세션 차단·증거보존·제한권한 비상 대응을 수행한다."],
    "OPS-04": ["SLI는 기능별 성공·지연·오류·안전사건·경보 전달을 측정한다. SLO와 error budget 숫자는 측정·승인 전 임의로 만들지 않는다."],
    "OPS-05": ["로그·메트릭·트레이스에는 비밀번호·인증증명·정확 위치·원본 영상·음성을 넣지 않고 correlation ID와 최소 상태만 기록한다."],
    "OPS-06": ["실제 대시보드 구현·권한·데이터 공급·freshness 검증 뒤 URL·버전·검증결과를 증거로 등록한다."],
    "OPS-07": ["사용자 안전, 민감자료, 인증 문제를 최상위로 두고 경보 전달 실패 자체도 기록·대체연락한다. 단순 자료흐름 보류를 사용자에게 알리지 않는다."],
    "OPS-08": [
        "실시간 탐지·길안내 이상은 신규 보행 차단과 활성 사용자 안전안내를 우선한다.",
        "신고·통계·관측만 고장 나면 해당 흐름을 격리하고 핵심 보행기능을 계속 감시한다.",
        "관리자 연락 불가 시 비상 대응자는 장애확인·신규차단·검증된 복구만 할 수 있고 정책변경·대량조회·새 배포는 할 수 없다.",
    ],
    "OPS-09": ["매일·매주·릴리스 후 점검항목을 사전개설하되 실제 점검 행과 서명은 실행 전까지 비워 둔다."],
    "OPS-10": ["서울 리전 주 원본 300 GiB와 별도 backup 300 GiB를 분리하고 backup은 35일 순환한다. 암호화·권한분리·manifest·삭제 만료를 함께 확인한다."],
    "OPS-11": ["복원 절차 Draft는 작성하지만 실제 복원시험은 NOT_RUN이다. backup bundle 전체를 격리환경에 복원하고 object 수·bytes·hash·DB 참조·권한을 검증해야 결과가 된다."],
    "OPS-12": ["RTO·RPO 수치는 실제 서비스 영향·복원 측정·비용을 근거로 승인하기 전까지 미정이다. 미정 값을 0 또는 무손실로 표현하지 않는다."],
    "OPS-13": ["재해복구 계획은 계정·DB·원본·모델·설정·키를 포함한다. 실제 훈련 결과는 NOT_RUN이며 훈련 없이는 복구 가능을 주장하지 않는다."],
    "OPS-14": ["권한 원장은 사전개설한다. 실제 정기검토 때 계정·역할·필요성·최종사용·회수·예외를 append-only 행으로 남긴다."],
    "OPS-15": ["비밀정보·인증서·서명키는 코드와 문서에 넣지 않고 별도 보관하며 회전 시 새 버전·적용·폐기·복구 가능성을 검증한다."],
    "OPS-16": ["취약점 심각도·악용 가능성·사용자 안전·개인정보 영향을 함께 평가하고 patch, 완화, 검증, release 연결을 기록한다."],
    "OPS-17": ["장애 원장 구조만 사전개설하고 현재 실제 incident 행은 0개로 둔다. 미발생을 무장애 증명으로 해석하지 않는다."],
    "OPS-18": ["실제 중대 incident가 종료된 뒤 시간선·원인·영향·기여요인·재발방지·담당·기한을 근거와 함께 작성한다."],
    "OPS-19": ["운영 변경은 요청·위험·승인·시행·검증·rollback을 append-only로 기록하며 현재 실행 행은 0개다."],
    "OPS-20": ["5개 gate, FP-035 정규화 지시의 묶음 승인 대기, 운영 준비 gap을 완료 사실과 분리한 backlog로 유지한다."],
    "OPS-21": ["수치 미확정, 관리자 복구훈련 미실행, 대시보드·비상대응자 미활성 상태를 숨기지 않고 부채로 추적한다."],
    "OPS-22": [
        "서버 기준은 주 원본 300 GiB, 별도 backup 300 GiB, 월 저장비 30,000원이다.",
        "서버 사용률 70%는 관리자 경고, 85%는 신규 현장시험 참여자 추가 중단, 95%는 만료자료 정리 뒤 새 원본수집 세션 보류, 100%는 새 학습자료·자동신고 후보 생성을 조용히 보류한다.",
        "기존 암호화 자료와 실시간 탐지·길안내는 유지하고 용량 확보 뒤 자동 재개한다. 만료되지 않은 원본을 비용 때문에 임의 삭제하지 않는다.",
        "자료 흐름만 보류되는 동안 사용자에게 음성·진동·푸시를 보내지 않고 관리자 기록·운영 지표에 남긴다. 실시간 안전기능이 믿을 수 없을 때만 사용자에게 안전정지를 알린다.",
        "휴대전화 queue는 서버 백분율과 별개이며 실제 byte 상한은 기기 실측 gate가 끝나기 전 미정이다.",
    ],
    "OPS-23": ["삭제 실행 원장은 사전개설하되 현재 실행 행은 0개다. Git에는 원본 없이 요청 ID·범위·통제 저장소 ID·hash·기한·검증 결과만 남긴다."],
    "OPS-24": ["TMAP, object storage·backup, Android 배포·알림 경로의 소유자·quota·장애·대체경로·데이터 경계를 유지한다."],

    "CLS-01": ["TST-22·23, REL-21, gate, KPI, 인계·잔여 의무가 실제로 종결된 뒤에만 완료 확인을 작성한다."],
    "CLS-02": ["지정 인수자의 실제 외부 서명과 효력일이 생기기 전에는 미서명 Planned 상태다."],
    "CLS-03": ["실제 종료 시 목표·범위·산출물·비용·일정·품질·미해결·교훈을 근거로 작성한다."],
    "CLS-04": ["최종 KPI 측정값과 계산근거가 생긴 뒤 목표 대비 결과를 기록하며 미측정을 달성으로 간주하지 않는다."],
    "CLS-05": ["최종 승인본·release·외부 원본·archive의 실제 ID·version·hash·위치를 모은 뒤 final index를 만든다."],
    "CLS-06": ["최종 source·tag·release·model·config·DB migration·문서를 보존 archive로 만들고 hash·복원 검증을 마친 뒤 증거화한다."],
    "CLS-07": ["현재 defect 원장은 실행 0건이다. 종료 시점 snapshot을 만들기 위한 구조만 준비하고 0건을 결함 없음의 증거로 쓰지 않는다."],
    "CLS-08": ["현재 5개 gate와 FP-035 정규화 지시의 묶음 승인 대기 등 잔여위험 포인터를 유지하며 종료 시 소유자·기한·수용 근거를 확정한다."],
    "CLS-09": ["운영·개선으로 넘길 기술부채의 영향·우선순위·소유자·목표 시점을 기록한다."],
    "CLS-10": ["서비스를 계속 운영하면 운영 수신자에게 계정·권한·키·지원·backlog·risk를 최소권한으로 이관하고 실제 확인 전에는 완료 처리하지 않는다."],
    "CLS-11": ["실제 종료 참여자의 회고가 이뤄진 뒤 사실·교훈·후속 행동을 기록한다."],
    "CLS-12": ["회고·잔여위험·부채·운영 feedback이 생긴 뒤 담당·기한·완료 기준이 있는 개선계획을 확정한다."],
    "CLS-13": ["실제 계약·비용·외부업체가 식별되고 정산·권한회수·자료반환이 완료된 뒤 외부 증거를 연결한다."],
    "CLS-14": ["운영 이관이면 승인된 보존·접근 책임을 함께 넘기고, 서비스 폐기이면 원본·가공본·backup별 삭제기한과 증거를 실행한다. 현재 실행 결과는 NOT_RUN이다."],
    "CLS-15": ["운영 이관이면 최소권한으로 소유권을 이전하고, 폐기이면 계정·세션·키·인프라를 검증 순서로 회수한다. 단일 관리자 복구수단을 같은 휴대전화 안에서만 처리하지 않는다."],
    "CLS-16": ["운영 이관(CLS-10)과 서비스 폐기는 상호 다른 분기다. 폐기를 결정한 경우 사용자 고지·신규세션 차단·자료처분·외부의존성 해지·검증·보존 순서로 실행한다."],
}


W9_OK_CANDIDATE_CONTENT_COMPLETENESS = [
    "CLS-07",
    "CLS-09",
    "OPS-20",
    "OPS-21",
    "OPS-24",
]
W9_EXTERNAL_AFTER_INTERNAL = [
    "CLS-08",
    "CLS-10",
    "CLS-14",
    "CLS-15",
    "CLS-16",
    "OPS-17",
    "OPS-19",
]


def _w9_contract(
    code: str,
    lifecycle_status: str,
    record_fields: list[str],
    current_boundary: list[str],
) -> dict[str, Any]:
    outcome = (
        "OK_CANDIDATE_CONTENT_COMPLETENESS"
        if code in W9_OK_CANDIDATE_CONTENT_COMPLETENESS
        else "EXTERNAL_AFTER_INTERNAL"
    )
    return {
        "artifact_type_id": code,
        "content_outcome": outcome,
        "schema_status": "STRUCTURED_DRAFT_COMPLETE",
        "lifecycle_status": lifecycle_status,
        "record_fields": record_fields,
        "current_boundary": current_boundary,
        "approval_status": "NOT_APPROVED",
        "execution_status": "NOT_RUN",
        "receipt_status": "NOT_RUN",
        "actual_receipt_ids": [],
    }


W9_LIFECYCLE_CONTRACTS = {
    "CLS-07": _w9_contract(
        "CLS-07",
        "OPENING_SNAPSHOT_SCHEMA_READY",
        [
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
            "snapshot_sha256",
        ],
        [
            "formal_execution_count=0",
            "closure_snapshot_status=NOT_TAKEN",
            "opening snapshot의 0건은 결함 없음의 품질 증거가 아님",
        ],
    ),
    "CLS-08": _w9_contract(
        "CLS-08",
        "RISK_REGISTER_SCHEMA_READY_ACCEPTANCE_EXTERNAL",
        [
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
            "receipt_ref",
        ],
        [
            "risk_acceptance_status=NOT_APPROVED",
            "acceptor_identity=None",
            "actual acceptance receipt 없음",
        ],
    ),
    "CLS-09": _w9_contract(
        "CLS-09",
        "TECHNICAL_DEBT_SCHEMA_READY",
        [
            "debt_id",
            "source_ref",
            "impact",
            "priority",
            "owner_role",
            "due_condition",
            "closure_criteria",
            "evidence_refs",
            "risk_acceptance_status",
            "acceptance_receipt_ref",
        ],
        [
            "OPS-21 원장을 정본으로 사용",
            "closure_snapshot_status=NOT_TAKEN",
            "risk acceptance=NOT_APPROVED",
        ],
    ),
    "CLS-10": _w9_contract(
        "CLS-10",
        "HANDOVER_SCHEMA_READY_EXECUTION_EXTERNAL",
        [
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
            "receipt_ref",
        ],
        [
            "recipient_identity=None",
            "operator_identity=None",
            "approval_status=NOT_APPROVED",
            "actual handover=NOT_RUN",
        ],
    ),
    "CLS-14": _w9_contract(
        "CLS-14",
        "DATA_DISPOSITION_SCHEMA_READY_EXECUTION_EXTERNAL",
        [
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
            "receipt_ref",
        ],
        [
            "legal basis와 최종 한국어 고지=NOT_APPROVED",
            "actual transfer=NOT_RUN",
            "actual deletion=NOT_RUN",
            "external receipt 없음",
        ],
    ),
    "CLS-15": _w9_contract(
        "CLS-15",
        "ACCESS_SECRET_INFRA_SCHEMA_READY_EXECUTION_EXTERNAL",
        [
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
            "receipt_ref",
        ],
        [
            "secret 값 기록 금지",
            "recipient/operator=None",
            "transfer/rotation/revocation=NOT_RUN",
            "approval=NOT_APPROVED",
        ],
    ),
    "CLS-16": _w9_contract(
        "CLS-16",
        "DECOMMISSION_SCHEMA_READY_EXECUTION_EXTERNAL",
        [
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
            "receipt_ref",
        ],
        [
            "decommission decision/authority=None",
            "provider exit test=NOT_RUN",
            "decommission execution=NOT_RUN",
            "approval=NOT_APPROVED",
        ],
    ),
    "OPS-17": _w9_contract(
        "OPS-17",
        "PREOPENED_INCIDENT_REGISTER",
        [
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
            "receipt_ref",
        ],
        [
            "operations_started=false",
            "incidents=[]",
            "opening snapshot의 0건은 무사건·무장애 증명이 아님",
        ],
    ),
    "OPS-19": _w9_contract(
        "OPS-19",
        "PREOPENED_OPERATION_CHANGE_REGISTER",
        [
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
            "receipt_ref",
        ],
        [
            "operations_started=false",
            "operation_changes=[]",
            "opening snapshot의 0건은 무변경 증명이 아님",
        ],
    ),
    "OPS-20": _w9_contract(
        "OPS-20",
        "ACTIVE_MAINTENANCE_BACKLOG_SCHEMA_READY",
        [
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
            "receipt_ref",
        ],
        [
            "r020 audit/backlog에 hash 결속",
            "5개 gate와 FP-035 항목은 OPEN/NOT_RUN",
            "waiver와 completion receipt 없음",
        ],
    ),
    "OPS-21": _w9_contract(
        "OPS-21",
        "ACTIVE_TECHNICAL_DEBT_SCHEMA_READY",
        [
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
            "acceptance_receipt_ref",
        ],
        [
            "측정·dashboard·비상대응자·복구훈련 debt OPEN",
            "risk_acceptance_status=NOT_APPROVED",
            "acceptance receipt 없음",
        ],
    ),
    "OPS-24": _w9_contract(
        "OPS-24",
        "EXTERNAL_DEPENDENCY_SCHEMA_READY",
        [
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
            "receipt_ref",
        ],
        [
            "TMAP quota/cost/support=NOT_ESTABLISHED",
            "live/deployment/provider-exit/alternate validation=NOT_RUN",
            "contract·receipt 원본 없음",
        ],
    ),
}


W9_RECEIPT_BINDING_FIELDS = [
    "repository_relative_path",
    "sha256",
    "byte_length",
    "source_event_or_decision_id",
    "occurred_or_effective_at",
    "operator_or_acceptor_identity",
]


def _w9_external_completion_test(core_content: str) -> list[str]:
    return [
        f"다음 핵심 내용이 근거·버전과 함께 구체적으로 작성되어 있다: {core_content}.",
        "필수 내용에 공란·암묵적 가정이 없고 미결정은 소유자·기한이 있는 차단 항목으로 표시되어 있다.",
        "상위 입력과 요구·설계·코드·시험·변경요청의 해당 추적 링크가 유효하다.",
        "지정 검토자가 사실·일관성·추적성을 확인하고 승인자가 명시적으로 승인했다.",
        "The activation condition is satisfied by a real event or authorized decision, not a synthetic row.",
        "Every lifecycle field is populated or conservatively blocked with owner and due condition.",
        "The named approver explicitly approves the exact scope and effective window.",
        "The actual receipt path, SHA-256 and byte length reproduce and bind the real event/decision and operator/acceptor identity.",
    ]


def _w9_external_contract(
    code: str,
    stable_id: str,
    responsible_role: str,
    approver_role: str,
    external_trigger: str,
    external_authority: str,
    required_inputs: list[str],
    internal_action: str,
    procedure: list[str],
    catalog_fields: list[str],
    completion_core_content: str,
    due_condition: str,
    release_impact: str,
) -> dict[str, Any]:
    return {
        "contract_schema": "walksafe.w9.external-execution-contract.v1",
        "stable_id": stable_id,
        "artifact_type_id": code,
        "catalog_artifact_id": f"DLV-{code}",
        "responsible_role": responsible_role,
        "approver_role": approver_role,
        "external_trigger": external_trigger,
        "external_authority": external_authority,
        "required_inputs": required_inputs,
        "internal_action": internal_action,
        "procedure": procedure,
        "evidence_schema": {
            "catalog_fields": catalog_fields,
            "lifecycle_fields": W9_LIFECYCLE_CONTRACTS[code]["record_fields"],
        },
        "receipt_schema": W9_RECEIPT_BINDING_FIELDS,
        "completion_test": _w9_external_completion_test(completion_core_content),
        "current_state": {
            "actual_event_ids": [],
            "actual_evidence_ids": [],
            "actual_receipt_ids": [],
            "actual_event_count": 0,
            "actual_evidence_count": 0,
            "actual_receipt_count": 0,
            "execution_status": "NOT_RUN",
            "approval_status": "NOT_APPROVED",
            "acceptance_status": "NOT_APPROVED",
            "recipient_state": "UNASSIGNED",
            "operator_state": "UNASSIGNED",
        },
        "due_and_review": {
            "due_condition": due_condition,
            "due_at": None,
            "review_due_at": None,
            "date_status": "UNASSIGNED_UNTIL_REAL_TRIGGER",
        },
        "release_impact": release_impact,
        "fake_event_or_receipt_allowed": False,
        "normative_model": "BUILDER_INTERNAL_CANONICAL",
    }


W9_EXTERNAL_EXECUTION_CONTRACTS = {
    "CLS-08": _w9_external_contract(
        "CLS-08",
        "W9-EXT-CLS-08",
        "프로젝트관리자",
        "프로젝트책임자",
        "종료 시점에 수용·감시할 잔여 위험이 존재하는 경우 활성",
        "PROJECT_OWNER_EXPLICIT_SCOPED_ACCEPTANCE_OR_REJECTION",
        [
            "최종 인수 범위와 승인 릴리스 기준선",
            "산출물·결함·위험·부채·권한 inventory",
            "운영 수신자·계약·데이터 보존 결정",
        ],
        "Record mitigation and monitoring for every residual risk, then obtain an explicit scoped acceptance or rejection from the authorized approver.",
        [
            "실제 잔여 위험과 activation 근거를 식별한다.",
            "모든 lifecycle field와 mitigation·monitoring을 채우거나 소유자·기한이 있는 차단 상태로 둔다.",
            "승인자가 exact scope와 effective window를 명시적으로 수용 또는 거절한다.",
            "실제 decision과 acceptor identity를 receipt hash binding으로 결속한다.",
        ],
        [
            "위험 ID·시나리오",
            "가능성·영향·노출",
            "기존 통제·효과",
            "잔여 등급",
            "모니터링·비상조치",
            "수용자·재검토일",
        ],
        "위험 ID·시나리오, 가능성·영향·노출",
        "BEFORE_PROJECT_CLOSURE_AND_AT_EACH_ACCEPTANCE_REVIEW",
        "승인된 실제 risk acceptance receipt 전에는 프로젝트 종료·인계를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
    ),
    "CLS-10": _w9_external_contract(
        "CLS-10",
        "W9-EXT-CLS-10",
        "프로젝트관리자",
        "프로젝트책임자",
        "서비스·자산·계정의 운영 책임을 다른 주체에게 이관할 때 활성",
        "PROJECT_OWNER_AND_NAMED_RECIPIENT_BILATERAL_CONFIRMATION",
        [
            "최종 인수 범위와 승인 릴리스 기준선",
            "산출물·결함·위험·부채·권한 inventory",
            "운영 수신자·계약·데이터 보존 결정",
        ],
        "Execute ownership and least-privilege handover with named recipient/operator, verification, revocation boundary and bilateral confirmation.",
        [
            "실제 이관 decision과 named recipient/operator를 확인한다.",
            "자산·계정·권한·backlog·risk·support 범위를 최소권한으로 대조한다.",
            "전달·검증·기존 권한 회수 경계를 양측이 확인한다.",
            "effective time과 양측 identity를 실제 handover receipt에 hash 결속한다.",
        ],
        [
            "자산·service owner",
            "계정·역할·접근",
            "credential 회전·전달 방식",
            "runbook·지원·연락",
            "권한 test·회수",
            "양측 확인·일시",
        ],
        "자산·service owner, 계정·역할·접근",
        "BEFORE_OPERATIONAL_RESPONSIBILITY_TRANSFER_EFFECTIVE_AT",
        "named recipient의 실제 양측 인계 receipt 전에는 운영 이관·프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
    ),
    "CLS-14": _w9_external_contract(
        "CLS-14",
        "W9-EXT-CLS-14",
        "프로젝트관리자",
        "프로젝트책임자",
        "종료 시 보존·이관·삭제할 데이터가 존재하는 경우 활성",
        "PROJECT_OWNER_APPROVED_DATA_BRANCH_WITH_APPLICABLE_LEGAL_AUTHORITY",
        [
            "최종 인수 범위와 승인 릴리스 기준선",
            "산출물·결함·위험·부채·권한 inventory",
            "운영 수신자·계약·데이터 보존 결정",
        ],
        "Execute the approved retention, transfer or deletion branch for each data class and reconcile backups and notifications.",
        [
            "각 data class·storage location의 승인된 branch와 법적 권한을 확인한다.",
            "보존·이관·삭제, encryption, backup reconciliation, notification을 class별 실행한다.",
            "operator와 승인 범위 및 미처리 예외를 검증한다.",
            "실제 transfer/deletion event와 receipt를 hash 결속한다.",
        ],
        [
            "데이터 inventory·분류",
            "보존 근거·기간",
            "이관 대상·형식·암호화",
            "삭제·익명화·backup",
            "사용자·기관 통지",
            "검증·승인 기록",
            "선행 승인된 CLS-16 폐기계획 ID와 데이터 보존·이관·삭제 실행 receipt",
        ],
        "데이터 inventory·분류, 보존 근거·기간",
        "BEFORE_EACH_TRANSFER_OR_DELETION_AND_PROJECT_CLOSURE",
        "법적 근거·고지 승인과 실제 class별 receipt 전에는 데이터 처분·프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
    ),
    "CLS-15": _w9_external_contract(
        "CLS-15",
        "W9-EXT-CLS-15",
        "프로젝트관리자",
        "프로젝트책임자",
        "프로젝트 전용 계정·key·도메인·인프라가 존재하는 경우 활성",
        "PROJECT_OWNER_AUTHORIZED_ASSET_RETAIN_TRANSFER_ROTATE_OR_REVOKE_DECISION",
        [
            "최종 인수 범위와 승인 릴리스 기준선",
            "산출물·결함·위험·부채·권한 inventory",
            "운영 수신자·계약·데이터 보존 결정",
        ],
        "Execute the authorized retain/transfer/rotate/revoke decision for every account, key and infrastructure asset without recording secret values.",
        [
            "실제 asset inventory와 authorized action을 확인하되 secret 값은 기록하지 않는다.",
            "retain/transfer/rotate/revoke를 최소권한·복구수단 분리 경계에서 실행한다.",
            "target controller, operator, effective time과 검증 결과를 확인한다.",
            "실제 asset action과 receipt를 hash 결속한다.",
        ],
        [
            "자산·계정 inventory",
            "유지·이관·폐기 판정",
            "key·certificate 회전",
            "서버·storage·DNS 종료",
            "backup·로그 보존",
            "검증·비용 종료",
            "선행 승인된 CLS-16 폐기계획 ID와 계정·키·인프라 정리 실행 receipt",
        ],
        "자산·계정 inventory, 유지·이관·폐기 판정",
        "BEFORE_EACH_TRANSFER_ROTATION_OR_REVOCATION",
        "모든 asset의 승인 action과 실제 receipt 전에는 권한·인프라 정리나 프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
    ),
    "CLS-16": _w9_external_contract(
        "CLS-16",
        "W9-EXT-CLS-16",
        "프로젝트관리자",
        "프로젝트책임자",
        "서비스 중단 또는 완전 폐기가 승인된 경우 활성",
        "PROJECT_OWNER_APPROVED_SERVICE_SHUTDOWN_OR_DECOMMISSION_DECISION",
        [
            "최종 인수 범위와 승인 릴리스 기준선",
            "산출물·결함·위험·부채·권한 inventory",
            "운영 수신자·계약·데이터 보존 결정",
        ],
        "Approve and execute the ordered service shutdown/decommission branch with notices, data rights, provider exit, checkpoints and final verification.",
        [
            "실제 폐기 권한·범위·순서·중단점을 승인한다.",
            "사용자 고지와 신규세션 차단 뒤 CLS-14, CLS-15, provider exit를 순서대로 실행한다.",
            "monitoring window와 final verification에서 잔여 접근·자료·비용을 확인한다.",
            "실제 decommission decision, operator와 receipt를 hash 결속한다.",
        ],
        [
            "종료 사유·범위·일정",
            "사용자·이해관계자 고지",
            "기능·신규가입 단계 종료",
            "데이터 export·삭제",
            "인프라·계정 철거",
            "지원 종료·최종 검증",
            "CLS-14 데이터 처리와 CLS-15 계정·키·인프라 정리 전에 승인할 폐기 순서·중단점·책임자",
        ],
        "종료 사유·범위·일정, 사용자·이해관계자 고지",
        "BEFORE_DECOMMISSION_AUTHORIZATION_AND_EXECUTION",
        "승인된 폐기 decision과 모든 단계의 실제 receipt 전에는 service decommission·프로젝트 종료를 완료할 수 없고 release는 NOT_ELIGIBLE이다.",
    ),
    "OPS-17": _w9_external_contract(
        "OPS-17",
        "W9-EXT-OPS-17",
        "운영책임자",
        "서비스소유자",
        "운영 준비 시 장애 원장 구조를 사전 개설하고, 사용자·데이터·SLO에 영향을 준 장애가 발생할 때마다 실행 행을 추가",
        "REAL_INCIDENT_EVENT_AND_SERVICE_OWNER_POSTMORTEM_APPROVAL",
        [
            "승인된 릴리스·배포 아키텍처·SLO",
            "운영 환경·계정·외부 서비스·관측성 현황",
            "지원·보안·복구 책임과 연락 체계",
        ],
        "Keep the preopened register empty until a real incident occurs; then add one complete real incident row and approved postmortem receipt.",
        [
            "실제 incident가 발생하기 전에는 opening register를 비워 둔다.",
            "발생 시 timeline·impact·containment·recovery·owner·evidence lifecycle field를 실제 값으로 기록한다.",
            "서비스소유자가 exact incident scope와 postmortem을 승인한다.",
            "실제 incident event, operator와 postmortem receipt를 hash 결속한다.",
        ],
        [
            "incident ID·severity",
            "탐지·시작·종료 시간",
            "사용자·데이터 영향",
            "조치 timeline·담당",
            "원인 후보·증거",
            "복구·후속·통지",
        ],
        "incident ID·severity, 탐지·시작·종료 시간",
        "ON_EACH_REAL_INCIDENT_BEFORE_INCIDENT_CLOSURE",
        "실제 incident가 발생하면 승인된 postmortem receipt 전까지 운영·관련 release 판정을 닫을 수 없으며 현재 release는 NOT_ELIGIBLE이다.",
    ),
    "OPS-19": _w9_external_contract(
        "OPS-19",
        "W9-EXT-OPS-19",
        "운영책임자",
        "서비스소유자",
        "운영 준비 시 변경 원장 구조를 사전 개설하고, 코드 외 설정·권한·인프라 변경 때마다 실행 행을 추가",
        "SERVICE_OWNER_AUTHORIZED_REAL_OPERATIONAL_CHANGE",
        [
            "승인된 릴리스·배포 아키텍처·SLO",
            "운영 환경·계정·외부 서비스·관측성 현황",
            "지원·보안·복구 책임과 연락 체계",
        ],
        "Keep the preopened register empty until a real operational change occurs; then add one authorized change row with rollback, verification and receipt.",
        [
            "실제 operational change 전에는 opening register를 비워 둔다.",
            "change 대상·위험·승인·window·rollback·verification lifecycle field를 채운다.",
            "서비스소유자가 실행 전 exact scope와 effective window를 승인한다.",
            "실제 change event, operator와 execution receipt를 hash 결속한다.",
        ],
        [
            "변경 ID·대상·전후",
            "사유·위험·영향",
            "승인·window·담당",
            "실행 commit·명령",
            "검증·관찰",
            "rollback·incident 연결",
        ],
        "변경 ID·대상·전후, 사유·위험·영향",
        "BEFORE_EACH_REAL_OPERATIONAL_CHANGE_EXECUTION",
        "승인·rollback·검증·실제 receipt 없는 운영 변경은 실행할 수 없고 관련 배포·release는 차단되며 현재 release는 NOT_ELIGIBLE이다.",
    ),
}


DOCUMENT_INTROS = {
    REL_CONTROL_PATH: "릴리스 후보를 언제 열고 무엇을 확인해야 하며, 아직 존재하지 않는 release 증거를 어떻게 구분하는가?",
    REL_DEPLOYMENT_PATH: "안전하게 배포·migration·복귀하려면 어떤 순서를 따르고 실행 증거는 언제 만들 수 있는가?",
    REL_DELIVERY_PATH: "Android 제품을 설치·사용·관리·시연·인계할 때 무엇을 설명하고 어떤 외부 확인이 필요한가?",
    OPS_GUIDE_PATH: "한 명의 관리자가 서비스를 관찰·지원하고 장애 때 안전하게 대응하려면 무엇을 준비해야 하는가?",
    OPS_RECOVERY_PATH: "35일 backup을 어떻게 관리하고 실제 복원·재해훈련 전에는 어떤 주장을 금지하는가?",
    OPS_CONTROL_PATH: "접근·변경·장애·부채·용량·삭제·외부 의존성을 어떤 원장과 정책으로 관리하는가?",
    CLS_CLOSURE_PATH: "프로젝트가 실제로 끝나기 전에는 어떤 종료 결과도 만들지 않고, 종료 시 무엇을 확인하는가?",
    CLS_HANDOVER_PATH: "종료 시 결함·위험·부채와 운영 권한을 어떻게 빠짐없이 넘기는가?",
    CLS_DECOMMISSION_PATH: "운영 이관과 서비스 폐기를 나눠 데이터·계정·키·인프라를 어떻게 처리하는가?",
}


def _trace_for(code: str, policy: dict[str, Any]) -> dict[str, list[str]]:
    features = [
        feature
        for feature in policy["features"]
        if code in feature.get("traceability", {}).get("affected_deliverables", [])
    ]
    feature_ids = [feature["id"] for feature in features]
    decision_ids = sorted(
        {
            decision_id
            for feature in features
            for decision_id in feature.get("traceability", {}).get("decision_ids", [])
        }
    )
    gate_ids = sorted(
        {
            gate["id"]
            for feature in features
            for gate in feature.get("remaining_gates", [])
        }
    )
    if code.startswith("REL-"):
        gate_ids = sorted(set(gate_ids) | set(GATE_IDS))
    if code in {"OPS-01", "OPS-03", "OPS-04", "OPS-07", "OPS-08", "OPS-17", "OPS-18", "OPS-20", "OPS-21", "OPS-22"}:
        gate_ids = sorted(set(gate_ids) | {"GATE-SERVER-CAPACITY-STATE-CONTRACT", "GATE-SINGLE-ADMIN-RECOVERY-DRILL"})
    return {
        "feature_ids": feature_ids,
        "decision_ids": decision_ids,
        "requirement_ids": [f"RQ-{feature_id}-001" for feature_id in feature_ids],
        "gate_ids": gate_ids,
    }


def _header(title: str, draft_codes: list[str], question: str) -> str:
    code_text = ", ".join(draft_codes) if draft_codes else "없음(계획 계약만 수록)"
    return f"""# {title}

> Draft로 작성된 산출물: {code_text}  
> 버전: {VERSION} · 승인: 미승인  
> 정책 기준선: {POLICY_BASELINE_ID}  
> 실제 실행 증거: 없음 · 출시: {RELEASE_STATUS}

## 이 문서가 답하는 질문

{question}

## 쉬운 요약

이 문서는 앞으로 해야 할 일과 판단 기준을 정리한 초안입니다. 절차나 빈 원장을 만들었다고 해서 릴리스·운영·종료가 실행된 것은 아닙니다. 실제 실행·외부 서명·결과는 이름 붙인 대상과 원본 증거가 생긴 뒤 별도 기록합니다.
"""


def _execution_contract(code: str, item: dict[str, Any]) -> dict[str, Any]:
    """Give every result-like item an explicit no-evidence boundary."""

    if code in PLANNED_ARTIFACT_TYPE_IDS:
        status = "PLANNED_NOT_RUN"
    elif code in EXECUTION_BOUNDARY_CODES:
        status = "DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN"
    else:
        status = "AUTHORABLE_DRAFT_NO_EXECUTION_CLAIM"
    form = item["recommended_form"]
    if form == "EXTERNAL_RECORD":
        evidence_rule = "서명·발행된 외부 원본은 Git 밖 통제 저장소에 두고 발행자·시각·저장 ID·SHA-256·효력상태를 기록한다."
    elif form == "GENERATED_EVIDENCE":
        evidence_rule = "정확한 release·환경·수행자·시각·원자료·판정·결함을 append-only evidence instance로 남긴다."
    elif form == "REGISTER":
        evidence_rule = "실제 사건·변경·실행마다 식별자·시각·actor·근거·상태변경을 새 행으로 추가하고 빈 원장을 결과로 세지 않는다."
    else:
        evidence_rule = "적용 대상 version·검토 지적·승인 기록을 결속하며 절차 문서만으로 실행 완료를 주장하지 않는다."
    return {
        "status": status,
        "executor": item["owner_role"],
        "activation": item["activation_condition"],
        "prerequisites": item["required_inputs"],
        "evidence_rule": evidence_rule,
        "judgment": item["completion_criteria"],
    }


def _ready25_contract(code: str) -> dict[str, Any]:
    owner_role = "PROJECT_OWNER" if code.startswith("CLS-") else "SERVICE_OWNER"
    blockers = [
        {
            "blocker_id": f"READY25-{code}-REAL-TRIGGER-OR-EVIDENCE-PENDING",
            "status": "OPEN",
            "owner_role": owner_role,
            "owner_identity": "김민호",
            "due_condition": READY25_DUE_CONDITIONS[code],
            "due_at": None,
        }
    ]
    if code in READY25_QA_REQUIRED:
        blockers.append(
            {
                "blocker_id": f"READY25-{code}-INDEPENDENT-QA-UNASSIGNED",
                "status": "OPEN",
                "owner_role": "PROJECT_OWNER",
                "owner_identity": "김민호",
                "missing_role": "INDEPENDENT_QA_REVIEWER",
                "assigned_reviewer": None,
                "due_condition": "BEFORE_CONTENT_ACCEPTANCE_OR_PROJECT_CLOSURE",
                "due_at": None,
            }
        )
    contract = {
        "artifact_type_id": code,
        "catalog_artifact_id": f"DLV-{code}",
        "applicability": "IN_SCOPE",
        "activation_state": READY25_ACTIVATION_STATES[code],
        "content_status": "PLAN_SCHEMA_CHECKLIST_AUTHORED",
        "content_authored": True,
        "internal_checklist": READY25_INTERNAL_CHECKLISTS[code],
        "blockers": blockers,
        "responsibility_boundary": {
            "project_owner": "김민호",
            "service_owner": "김민호",
            "product_owner": "김민호",
            "independent_qa_reviewer": None,
            "independent_qa_status": "UNASSIGNED",
            "self_review_credit_allowed": False,
        },
        "claim_boundary": {
            "accepted": False,
            "approval_count": 0,
            "execution_count": 0,
            "actual_event_count": 0,
            "formal_evidence_count": 0,
            "release_credit_count": 0,
            "actual_receipt_ids": [],
            "release_status": RELEASE_STATUS,
        },
        "fabricated_result_prohibited": True,
    }
    if code == "OPS-18":
        contract["incident_boundary"] = {
            "incident_count": 0,
            "trigger_status": "NOT_TRIGGERED",
            "zero_incidents_is_no_incident_evidence": False,
            "postmortem_status": "NOT_RUN",
        }
    if code == "CLS-16":
        contract["service_lifecycle_boundary"] = {
            "current_branch": "OPERATIONS_CONTINUE",
            "shutdown_decision": None,
            "decommission_execution_status": "NOT_RUN",
        }
    if code == "CLS-10":
        contract["handover_boundary"] = {
            "current_branch": "OPERATIONS_CONTINUE",
            "recipient": None,
            "operator": None,
            "handover_execution_status": "NOT_RUN",
        }
    return contract


def _ready25_rel_contract(code: str) -> dict[str, Any]:
    blockers = [
        {
            "blocker_id": f"READY25-{code}-NAMED-CANDIDATE-OR-SOURCE-PENDING",
            "status": "OPEN",
            "owner_role": "PRODUCT_OWNER",
            "owner_identity": "김민호",
            "due_condition": READY25_REL_DUE_CONDITIONS[code],
            "due_at": None,
        },
        {
            "blocker_id": f"READY25-{code}-INDEPENDENT-QA-UNASSIGNED",
            "status": "OPEN",
            "owner_role": "PROJECT_OWNER",
            "owner_identity": "김민호",
            "missing_role": "INDEPENDENT_QA_REVIEWER",
            "assigned_reviewer": None,
            "due_condition": "BEFORE_CONTENT_ACCEPTANCE_OR_RELEASE_PROMOTION",
            "due_at": None,
        },
    ]
    return {
        "artifact_type_id": code,
        "catalog_artifact_id": f"DLV-{code}",
        "applicability": "IN_SCOPE",
        "activation_state": READY25_REL_ACTIVATION_STATES[code],
        "content_status": "PLAN_SCHEMA_CHECKLIST_AUTHORED",
        "content_authored": True,
        "internal_checklist": READY25_REL_INTERNAL_CHECKLISTS[code],
        "release_strategy": {
            "environment_order": [
                "DEVELOPMENT",
                "CONTROLLED_DEMO",
                "BETA",
                "PRODUCTION",
            ],
            "deployment_model": "MANAGED_CLOUD_STAGING_FIRST",
            "promotion_requires_all_five_gates": True,
            "rollback_plan_required": True,
            "named_release_candidate": None,
            "promotion_status": "NOT_RUN",
            "rollback_execution_status": "NOT_RUN",
        },
        "five_gate_boundary": [
            {"gate_id": gate_id, "status": "NOT_RUN", "waived": False}
            for gate_id in GATE_IDS
        ],
        "blockers": blockers,
        "responsibility_boundary": {
            "project_owner": "김민호",
            "service_owner": "김민호",
            "product_owner": "김민호",
            "independent_qa_reviewer": None,
            "independent_qa_status": "UNASSIGNED",
            "self_review_credit_allowed": False,
        },
        "claim_boundary": {
            "accepted": False,
            "approval_count": 0,
            "execution_count": 0,
            "actual_event_count": 0,
            "formal_evidence_count": 0,
            "release_credit_count": 0,
            "actual_receipt_ids": [],
            "release_status": RELEASE_STATUS,
        },
        "fabricated_release_candidate_or_result_prohibited": True,
    }


def _ready25_contract_markdown(code: str) -> str:
    if code not in READY25_EXACT13:
        return ""
    contract = _ready25_contract(code)
    checklist = "\n".join(f"- {item}" for item in contract["internal_checklist"])
    blocker_rows = "\n".join(
        "| `{blocker_id}` | `{status}` | `{owner_role}` / {owner_identity} | "
        "`{due_condition}` / due_at=`{due_at}` |".format(**blocker)
        for blocker in contract["blockers"]
    )
    return f"""

### Ready25 내부 작성·실행 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `{contract['activation_state']}` |
| accepted | `false` |
| approval / actual event / formal evidence / release credit | `0 / 0 / 0 / 0` |
| project/service/product owner | 김민호 / 김민호 / 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

{checklist}

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
{blocker_rows}
"""


def _ready25_rel_contract_markdown(code: str) -> str:
    if code not in READY25_REL_EXACT3:
        return ""
    contract = _ready25_rel_contract(code)
    checklist = "\n".join(f"- {item}" for item in contract["internal_checklist"])
    blocker_rows = "\n".join(
        "| `{blocker_id}` | `{status}` | `{owner_role}` / {owner_identity} | "
        "`{due_condition}` / due_at=`{due_at}` |".format(**blocker)
        for blocker in contract["blockers"]
    )
    gates = "<br>".join(
        f"`{item['gate_id']}`={item['status']}/waived={str(item['waived']).lower()}"
        for item in contract["five_gate_boundary"]
    )
    environment_order = " → ".join(
        contract["release_strategy"]["environment_order"]
    )
    return f"""

### Ready25 REL 내부 작성·promotion 차단 계약

| 항목 | 현재 판정 |
|---|---|
| applicability | `IN_SCOPE` |
| content authored | `true` · plan/schema/checklist만 작성 |
| activation | `{contract['activation_state']}` |
| release candidate | `null` |
| environment/promotion | `{environment_order}` · `MANAGED_CLOUD_STAGING_FIRST` · promotion `NOT_RUN` |
| rollback | 계획 필수 · 실행 `NOT_RUN` |
| five gates | {gates} |
| accepted / approval / execution / event / formal / release credit | `false / 0 / 0 / 0 / 0 / 0` |
| product owner | 김민호 |
| independent QA reviewer | `UNASSIGNED` · self-review credit 금지 |
| 실제 receipt | `[]` |

#### 내부 작성 checklist

{checklist}

#### 열린 blocker

| blocker ID | 상태 | owner | due |
|---|---|---|---|
{blocker_rows}
"""


def _w9_contract_markdown(code: str) -> str:
    ready25_projection = (
        _ready25_contract_markdown(code)
        + _ready25_rel_contract_markdown(code)
    )
    contract = W9_LIFECYCLE_CONTRACTS.get(code)
    if contract is None:
        return ready25_projection
    fields = "<br>".join(f"`{field}`" for field in contract["record_fields"])
    boundary = "<br>".join(contract["current_boundary"])
    external_contract = W9_EXTERNAL_EXECUTION_CONTRACTS.get(code)
    external_projection = ""
    if external_contract is not None:
        stable_id = external_contract["stable_id"]
        external_projection = f"""

### W9 stable external execution contract

아래 JSON은 builder 내부 정본 model의 lossless projection입니다. 실제 event·decision·evidence·receipt가 아니며 overlay를 source로 참조하지 않습니다.

<!-- W9-EXTERNAL-CONTRACT-START {stable_id} -->
```json
{json.dumps(external_contract, ensure_ascii=False, indent=2, sort_keys=True)}
```
<!-- W9-EXTERNAL-CONTRACT-END {stable_id} -->
"""
    return f"""

### W9 내용 완전성·lifecycle·schema 계약

| 항목 | 현재 계약 |
|---|---|
| W9 내용 판정 | `{contract['content_outcome']}` |
| schema | `{contract['schema_status']}` |
| lifecycle | `{contract['lifecycle_status']}` |
| 필수 record fields | {fields} |
| 현재 경계 | {boundary} |
| 승인 | `{contract['approval_status']}` |
| 실행 | `{contract['execution_status']}` |
| receipt | `{contract['receipt_status']}` · 실제 ID `[]` |
{external_projection}{ready25_projection}"""


def _section(
    code: str,
    item: dict[str, Any],
    policy: dict[str, Any],
    path: Path,
) -> str:
    materialized = code in set(MATERIALIZED_ARTIFACT_TYPE_IDS)
    state = "DRAFT / NOT_APPROVED"
    if not materialized:
        state = "PLANNED / NOT_RUN"
    elif code in {"REL-15", "OPS-11", "OPS-13", "CLS-10", "CLS-14", "CLS-15", "CLS-16"}:
        state = "DRAFT 절차 / 실행 결과 NOT_RUN"
    trace = _trace_for(code, policy)
    execution_contract = _execution_contract(code, item)
    responsibility = (
        f"작성 {item['owner_role']} · 검토 {', '.join(item['reviewer_roles']) or '배정 전'} · "
        f"승인 {item['approver_role']}"
    )
    change_rule = (
        "실제 실행·외부 원본은 덮어쓰지 않고 새 instance와 hash로 추가한다. "
        "계획·절차 변경은 DOC-05 변경이력과 새 version을 연결하고 이전본을 Superseded로 보존한다."
    )
    if item["recommended_form"] == "REGISTER":
        change_rule = "원장은 기존 행을 삭제하지 않고 새 행·상태변경 이력을 추가하며, snapshot과 대체 원장 ID를 연결한다."
    elif item["recommended_form"] in {"GENERATED_EVIDENCE", "EXTERNAL_RECORD"}:
        change_rule = "실행·발행 원본은 수정하지 않는다. 정정은 새 instance·시각·hash와 이전 원본 관계를 추가한다."
    direct_refs = []
    if trace["feature_ids"]:
        direct_refs.append("기능 정책 " + ", ".join(trace["feature_ids"]))
    if trace["decision_ids"]:
        direct_refs.append("결정 " + ", ".join(trace["decision_ids"]))
    if trace["requirement_ids"]:
        direct_refs.append("요구 " + ", ".join(trace["requirement_ids"]))
    if trace["gate_ids"]:
        direct_refs.append("gate " + ", ".join(trace["gate_ids"]))
    if code in {"REL-01", "REL-02", "REL-10", "REL-11", "OPS-20", "OPS-21", "CLS-08"}:
        direct_refs.append(f"{FP035_ISSUE_ID} 정규화 지시 포착·묶음 승인 대기 / 변경요청 REQ-CHG-20260721-002")
    trace_text = "<br>".join(direct_refs) if direct_refs else "상위 유형 의존성과 정책 기준선에서 상속; 직접 기능 연결은 없음"
    return f"""
<a id="{code.lower()}"></a>
## {code} {item['title']}

| 항목 | 현재 계약 |
|---|---|
| 상태 | `{state}` |
| 목적 | {item['purpose']} |
| 적용 조건 | {item['activation_condition']} |
| 책임 | {responsibility} |
| 정본 | `{_rel(path)}#{code.lower()}` |
| 선행 유형 | {', '.join(value.removeprefix('DLV-') for value in item['upstream_types']) or '없음'} |
| 후행 유형 | {', '.join(value.removeprefix('DLV-') for value in item['downstream_types']) or '없음'} |
| 정책·결정·요구·위험·변경 추적 | {trace_text} |

### 실행·증거 경계

| 항목 | 계약 |
|---|---|
| 현재 실행 상태 | `{execution_contract['status']}` |
| 실행 책임 | {execution_contract['executor']} |
| 활성 시점 | {execution_contract['activation']} |
| 필수 선행 | {_bullet(execution_contract['prerequisites']).replace(chr(10), '<br>')} |
| 남겨야 할 증거 | {execution_contract['evidence_rule']} |
| 판정 조건 | {_bullet(execution_contract['judgment']).replace(chr(10), '<br>')} |

### 정해진 작성·운영 규칙

{_bullet(SPECIFIC_RULES[code])}{_w9_contract_markdown(code)}

### 포함 내용과 필요한 입력

**포함 내용**

{_bullet(item['required_contents'])}

**작성 입력**

{_bullet(item['required_inputs'])}

### 완료·승인과 유지관리

완료하려면 아래 기준을 모두 충족하고 지정 승인자가 명시적으로 승인해야 합니다. 현재 Draft 또는 Planned 상태는 이 승인을 대신하지 않습니다.

{_bullet(item['completion_criteria'])}

갱신 조건:

{_bullet(item['update_triggers'])}

변경·대체·폐기: {change_rule}
"""


def _document(
    path: Path,
    title: str,
    catalog: dict[str, dict[str, Any]],
    policy: dict[str, Any],
    project_facts: dict[str, Any],
) -> str:
    draft_codes = _draft_codes_for(path)
    planned_codes = _planned_codes_for(path)
    sections = "".join(_section(code, catalog[code], policy, path) for code in DOCUMENT_COVERAGE[path])
    planned_summary = _bullet(
        (f"{code} {catalog[code]['title']} — 실제 대상·실행·외부 원본이 생길 때까지 PLANNED/NOT_RUN" for code in planned_codes),
        empty="이 묶음에는 별도 Planned 결과 유형이 없습니다.",
    )
    return _header(title, draft_codes, DOCUMENT_INTROS[path]) + f"""

## 현재 경계

- 현행 정식 제품 경계: Android 사용자 앱 + 별도 비공개 Android 관리자 앱
- Web/PWA: 재승인 전까지 레거시 참고이며 Android 합격·출시 근거가 아님
- 승인된 진행 순서: 개발 → {project_facts['controlled_demo_date']} 통제 시연 → 베타 → 정식 출시. 통제 시연은 릴리스가 아님
- 배포 방향: 관리형 cloud의 staging 우선. 정해진 전체 프로젝트 예산은 없음
- FP-035: 정규화 지시 포착·묶음 승인 대기. 정정 후보 NOT_APPROVED/NOT_EFFECTIVE. 보행 중 미전송, 정지 뒤 이동통신망 명시 선택 시 이동통신망 허용, 미선택 시 Wi-Fi만 허용. 관련 시험 NOT_RUN
- 남은 gate: 5개 모두 NOT_RUN·미면제
- 출시: `{RELEASE_STATUS}`

## 이 묶음에서 아직 만들지 않은 결과

{planned_summary}

## 공통 보관 원칙

민감한 위치·영상·음성·서명·운영 원본은 Git에 넣지 않습니다. Git에는 통제 저장소 ID, SHA-256, 권한, 보존기간, 실행·검토 상태만 남깁니다. 실제 결과가 생기면 template을 복사한 새 instance를 append-only로 보관하고 이 정본에는 포인터만 연결합니다.
""" + sections


def _metadata(title: str, artifact_type_ids: list[str]) -> dict[str, Any]:
    return {
        "title": title,
        "version": VERSION,
        "as_of": AS_OF,
        "lifecycle_status": "DRAFT",
        "approval_status": "NOT_APPROVED",
        "verification_status": "STRUCTURE_CHECKED_EXECUTION_NOT_RUN",
        "release_status": RELEASE_STATUS,
        "artifact_type_ids": artifact_type_ids,
        "source_policy_baseline": POLICY_BASELINE_ID,
        "generated_by": _rel(GENERATOR_PATH),
    }


def _gate_rows(policy: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        {
            "id": gate["id"],
            "title": gate["title"],
            "status": "NOT_RUN",
            "waived": False,
            "closure": gate["closure"],
            "evidence_ids": [],
        }
        for gate in policy["remaining_gates"]
    ]


def _release_register(policy: dict[str, Any], project_facts: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "walksafe.release-control-register.v1",
        "metadata": _metadata("WalkSafe 릴리스 통제 보조 원장", ["REL-01", "REL-02", "REL-10"]),
        "register_mode": "PREOPENED_APPEND_ONLY",
        "release_candidates": [],
        "formal_release_candidate_count": 0,
        "release_artifacts": [],
        "release_approvals": [],
        "approved_stage_roadmap": {
            "stages": project_facts["release_roadmap"],
            "controlled_demo_date": project_facts["controlled_demo_date"],
            "controlled_demo_is_release": False,
            "deployment_model": project_facts["deployment_model"],
            "fixed_project_budget_krw": project_facts["fixed_project_budget_krw"],
            "note": "일정 방향은 확정됐지만 명명된 release candidate와 release artifact는 아직 없다.",
        },
        "current_readiness": {
            "status": RELEASE_STATUS,
            "tst_22_decision": "NOT_RUN",
            "reason": "명명된 release 후보와 승인 증거가 없고 5개 gate가 모두 미실행이다.",
        },
        "known_issues": [
            {
                "issue_id": FP035_ISSUE_ID,
                "status": FP035_NORMALIZATION_STATUS,
                "affected_scope": ["일반 활동원본 전송", "Android 사용자 앱", "backend"],
                "owner_clarification_required": False,
                "normalized_policy": FP035_NORMALIZED_POLICY,
                "normative_rule": FP035_NORMATIVE_RULE,
                "related_test_status": "NOT_RUN",
                "correction_candidate_binding": _binding(FP035_CORRECTION_CANDIDATE_PATH),
                "correction_candidate_approval_status": "NOT_APPROVED",
                "correction_candidate_effective_status": "NOT_EFFECTIVE",
                "policy_effect_claimed": False,
                "resolution": "포착한 정규화 지시를 영향 산출물에 반영하고 새 묶음 승인을 받아 기준선에 결속한다. 같은 선택을 다시 묻지 않는다.",
                "change_request_id": "REQ-CHG-20260721-002",
                "waived": False,
            }
        ],
        "remaining_gates": _gate_rows(policy),
        "entry_contract": {
            "release_candidate_required_fields": [
                "release_id", "scope", "source_commit", "build_ids", "model_ids",
                "config_versions", "migration_ids", "owner", "opened_at", "status",
            ],
            "evidence_required_fields": [
                "evidence_id", "release_id", "artifact_path_or_storage_id", "sha256",
                "byte_length", "created_at", "issuer", "verification_status",
            ],
            "append_only": True,
        },
        "approval_boundary": {
            "draft_register_is_release_evidence": False,
            "release_completed": False,
            "release_approved": False,
            "gates_waived": False,
            "release_status": RELEASE_STATUS,
        },
    }


def _release_evidence_template() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.release-evidence-template.v1",
        "metadata": _metadata("릴리스 증거 instance 템플릿", []),
        "template_only": True,
        "is_live_evidence": False,
        "target_artifact_type_ids": ["REL-03", "REL-04", "REL-05", "REL-06", "REL-07", "REL-08", "REL-09"],
        "activation": "명명된 release 후보와 실제 artifact가 생성된 뒤 새 파일로 복사해 사용",
        "release_id": None,
        "version_description": None,
        "source_commit": None,
        "source_tag": None,
        "artifacts": [],
        "signatures": [],
        "sbom": None,
        "provenance": None,
        "release_notes": None,
        "execution_status": "NOT_RUN",
        "approval_status": "NOT_APPROVED",
        "evidence_ids": [],
        "storage_rule": "민감·서명 원본은 Git 밖 통제 저장소에 두고 ID·SHA-256·권한·보존기간만 연결",
    }


def _deployment_template() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.deployment-execution-template.v1",
        "metadata": _metadata("배포·migration·rollback 실행 템플릿", []),
        "template_only": True,
        "is_live_evidence": False,
        "target_artifact_type_ids": ["REL-13", "REL-14", "REL-15"],
        "release_id": None,
        "environment_id": None,
        "executed_by": None,
        "started_at": None,
        "ended_at": None,
        "precondition": {
            "tst_22_required_decisions_for_rel_13_and_rel_14": ["GO", "CONDITIONAL_GO"],
            "tst_22_actual_decision": "NOT_RUN",
            "eligible_to_execute_rel_13_or_rel_14": False,
        },
        "migration_steps": [],
        "smoke_results": [],
        "canary_results": [],
        "rollback_result": None,
        "raw_evidence_refs": [],
        "execution_status": "NOT_RUN",
        "result": None,
    }


def _external_acceptance_template() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.release-external-acceptance-metadata-template.v1",
        "metadata": _metadata("릴리스 외부 인수·승인 메타데이터 템플릿", []),
        "template_only": True,
        "is_live_external_record": False,
        "target_artifact_type_ids": ["REL-20", "REL-21"],
        "release_id": None,
        "handover_scope": [],
        "acceptor_role": None,
        "acceptor_identity_reference": None,
        "signed_at": None,
        "controlled_storage_id": None,
        "external_original_sha256": None,
        "signature_status": "NOT_ISSUED",
        "approval_status": "NOT_APPROVED",
    }


def _operations_register(policy: dict[str, Any], project_facts: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": "walksafe.operations-registers.v1",
        "metadata": _metadata(
            "WalkSafe 운영 지속 원장",
            ["OPS-02", "OPS-09", "OPS-14", "OPS-17", "OPS-19", "OPS-20", "OPS-21", "OPS-23", "OPS-24"],
        ),
        "register_mode": "PREOPENED_APPEND_ONLY",
        "w9_as_of": W9_AS_OF,
        "w9_source_bindings": _w9_source_bindings(),
        "w9_content_contracts": {
            code: W9_LIFECYCLE_CONTRACTS[code]
            for code in ("OPS-17", "OPS-19", "OPS-20", "OPS-21", "OPS-24")
        },
        "external_execution_contracts": [
            W9_EXTERNAL_EXECUTION_CONTRACTS[code]
            for code in ("OPS-17", "OPS-19")
        ],
        "opening_snapshot": {
            "as_of": W9_AS_OF,
            "operations_started": False,
            "incident_record_count": 0,
            "operation_change_record_count": 0,
            "proves_no_incidents": False,
            "proves_no_changes": False,
            "interpretation": "운영 개시 전 빈 원장 구조이며 무사건·무장애·무변경 증거가 아니다.",
        },
        "service_ownership": {
            "operating_model": "SINGLE_ADMIN",
            "administrator_and_final_approver_are_one_person": True,
            "assigned_person": None,
            "assigned_role": project_facts["operations_owner_role"],
            "source_answer_id": project_facts["operations_owner_source_answer"],
            "assignment_status": "ROLE_CONFIRMED_IDENTITY_NOT_STORED",
            "authentication": "MFA_OR_PASSKEY_REQUIRED",
            "recovery_material_location": "SEPARATE_FROM_ADMIN_PHONE",
            "shared_password_allowed": False,
            "high_risk_work_when_access_lost": "FROZEN_UNTIL_RECOVERY",
        },
        "support_contract": {
            "regular_hours": "WEEKDAYS_09:00_TO_18:00_ASIA_SEOUL",
            "outside_hours": [
                "자동 신규 보행 차단",
                "활성 사용자 안전안내",
                "민감자료 접근차단",
                "증거보존",
                "사전 지정 제한권한 비상 대응자 연락",
            ],
            "assigned_emergency_responder": None,
            "production_operation_allowed_without_responder": False,
        },
        "server_capacity_policy": {
            "scope": "SERVER_ONLY",
            "primary_original_capacity_gib": 300,
            "separate_backup_capacity_gib": 300,
            "monthly_storage_cost_limit_krw": 30000,
            "thresholds": [
                {"percent": 70, "action": "ADMIN_ONLY_WARNING"},
                {"percent": 85, "action": "PAUSE_NEW_FIELD_TEST_PARTICIPANTS"},
                {"percent": 95, "action": "CLEAN_EXPIRED_THEN_HOLD_NEW_RAW_COLLECTION_SESSIONS"},
                {"percent": 100, "action": "QUIETLY_HOLD_NEW_TRAINING_DATA_AND_AUTO_REPORT_CANDIDATES"},
            ],
            "preserve_at_all_thresholds": [
                "기존 암호화 자료",
                "실시간 객체 탐지",
                "실시간 길안내",
            ],
            "automatic_resume_when_capacity_available": True,
            "delete_unexpired_originals_for_cost": False,
            "user_notification_for_data_flow_only": False,
            "admin_record_required": True,
        },
        "phone_queue_policy": {
            "scope": "PHONE_ONLY_SEPARATE_FROM_SERVER_PERCENTAGES",
            "byte_limit": None,
            "limit_gate_id": "GATE-PHONE-QUEUE-BYTE-LIMIT",
            "limit_status": "NOT_RUN",
            "deletion_order": [
                "다시 만들 수 있는 임시 cache",
                "서버 수신 확인된 로컬 사본",
                "보존기간이 끝난 선택적 학습자료",
                "보존기간이 끝난 낮은 신뢰도의 미전송 신고 후보",
                "그래도 부족하면 새 학습자료·자동신고 후보 생성 보류",
            ],
            "quiet_data_flow_pause": True,
            "user_notification_only_if_realtime_safety_unreliable": True,
        },
        "backup_policy": {
            "retention_days": 35,
            "separate_access_boundary": True,
            "backup_instances": [],
            "restore_executions": [],
            "restore_execution_count": 0,
        },
        "operations_checklist_executions": [],
        "access_review_executions": [],
        "incidents": [],
        "operation_changes": [],
        "data_disposition_executions": [],
        "maintenance_backlog": [
            *[
                {
                    "backlog_id": f"OPS-BL-GATE-{index:02d}",
                    "source_type": "REMAINING_GATE",
                    "source_gate_id": gate["id"],
                    "source_ref": f"policy.remaining_gates[{gate['id']}]",
                    "title": gate["title"],
                    "status": "NOT_RUN",
                    "priority": "P0_RELEASE_BLOCKING",
                    "owner_role": project_facts["operations_owner_role"],
                    "due_condition": "BEFORE_NAMED_RELEASE_CANDIDATE",
                    "risk_if_open": "release remains NOT_ELIGIBLE",
                    "completion_criteria": "gate의 요구 증거·판정·승인이 같은 instance에 결속되어야 한다.",
                    "evidence_refs": [],
                    "waived": False,
                    "waiver_status": "NOT_APPROVED",
                    "approval_status": "NOT_APPROVED",
                    "receipt_ref": None,
                }
                for index, gate in enumerate(policy["remaining_gates"], start=1)
            ],
            {
                "backlog_id": "OPS-BL-FP035-001",
                "source_type": "OPEN_POLICY_ISSUE",
                "source_issue_id": FP035_ISSUE_ID,
                "source_ref": _rel(FP035_CORRECTION_CANDIDATE_PATH),
                "title": "FP-035 정규화 지시를 새 산출물 묶음 승인에 결속",
                "status": FP035_NORMALIZATION_STATUS,
                "priority": "P0_RELEASE_BLOCKING",
                "owner_role": project_facts["operations_owner_role"],
                "due_condition": "BEFORE_NAMED_RELEASE_CANDIDATE",
                "risk_if_open": "후보 정책은 NOT_EFFECTIVE이며 관련 release 시험을 시작할 수 없다.",
                "completion_criteria": "새 묶음 승인과 관련 시험 증거가 동일 정책 generation에 결속되어야 한다.",
                "evidence_refs": [],
                "normalized_policy": FP035_NORMALIZED_POLICY,
                "waived": False,
                "waiver_status": "NOT_APPROVED",
                "approval_status": "NOT_APPROVED",
                "receipt_ref": None,
            },
        ],
        "technical_debt": [
            {
                "debt_id": "OPS-TD-001",
                "source_ref": "OPS-04",
                "title": "SLI·SLO·error budget 수치 미측정",
                "status": "OPEN",
                "impact": "운영 품질 목표와 error-budget 판정을 수치로 할 수 없다.",
                "priority": "P0_BEFORE_PRODUCTION",
                "owner_role": project_facts["operations_owner_role"],
                "due_condition": "BEFORE_PRODUCTION_OPERATION",
                "closure_criteria": "실측 SLI와 승인된 SLO/error budget 및 산출 근거를 결속한다.",
                "evidence_refs": [],
                "risk_acceptance_status": "NOT_APPROVED",
                "acceptance_receipt_ref": None,
            },
            {
                "debt_id": "OPS-TD-002",
                "source_ref": "OPS-06",
                "title": "운영 dashboard 실제 구현·검증 전",
                "status": "OPEN",
                "impact": "운영자가 지연·오류·안전 경보 상태를 검증된 화면에서 확인할 수 없다.",
                "priority": "P0_BEFORE_PRODUCTION",
                "owner_role": project_facts["operations_owner_role"],
                "due_condition": "BEFORE_PRODUCTION_OPERATION",
                "closure_criteria": "실환경 dashboard 권한·data freshness·alert 연결을 검증한다.",
                "evidence_refs": [],
                "risk_acceptance_status": "NOT_APPROVED",
                "acceptance_receipt_ref": None,
            },
            {
                "debt_id": "OPS-TD-003",
                "source_ref": "OPS-03",
                "title": "비상 대응자 실명·대기표 미배정",
                "status": "OPEN",
                "impact": "단일 관리자 연락 불가 시 제한권한 안전 대응을 개시할 수 없다.",
                "priority": "P0_BEFORE_PRODUCTION",
                "owner_role": project_facts["operations_owner_role"],
                "due_condition": "BEFORE_PRODUCTION_OPERATION",
                "closure_criteria": "지정·연락·최소권한·대체 연락경로를 실제로 검증한다.",
                "evidence_refs": [],
                "risk_acceptance_status": "NOT_APPROVED",
                "acceptance_receipt_ref": None,
            },
            {
                "debt_id": "OPS-TD-004",
                "source_ref": "OPS-11,OPS-13",
                "title": "복원·재해복구·관리자 분실 훈련 미실행",
                "status": "OPEN",
                "impact": "backup 복원성과 단일 관리자 복구 가능성이 입증되지 않았다.",
                "priority": "P0_BEFORE_PRODUCTION",
                "owner_role": project_facts["operations_owner_role"],
                "due_condition": "BEFORE_PRODUCTION_OPERATION",
                "closure_criteria": "격리 복원·DR·관리자 접근상실 훈련 결과와 결함을 기록한다.",
                "evidence_refs": [],
                "risk_acceptance_status": "NOT_APPROVED",
                "acceptance_receipt_ref": None,
            },
        ],
        "external_dependencies": [
            {
                "dependency_id": "EXT-TMAP",
                "name": "TMAP route/map API",
                "data_boundary": "route request/response only; approved proxy boundary",
                "owner_role": project_facts["operations_owner_role"],
                "quota_status": "NOT_ESTABLISHED",
                "cost_status": "NOT_ESTABLISHED",
                "support_status": "NOT_ESTABLISHED",
                "review_due_condition": "BEFORE_LIVE_TMAP_OR_NAMED_RELEASE",
                "monitoring_signals": ["error taxonomy별 count", "4-second timeout count", "quota rejection count"],
                "quota_and_failure_validation": "NOT_RUN",
                "failure_fallback": "새 경로 요청 중지·stale route 금지·사용자 안전행동 안내",
                "provider_exit_status": "NOT_RUN",
                "alternate_validation_status": "NOT_RUN",
                "evidence_refs": [],
                "receipt_ref": None,
            },
            {
                "dependency_id": "EXT-OBJECT-STORAGE",
                "name": "서울 리전 원본·backup object storage",
                "data_boundary": "암호화 원본과 최소 metadata",
                "owner_role": project_facts["operations_owner_role"],
                "quota_status": "NOT_ESTABLISHED",
                "cost_status": "PROJECT_LIMIT_30000_KRW_NOT_PROVIDER_CONTRACT",
                "support_status": "NOT_ESTABLISHED",
                "review_due_condition": "BEFORE_PRODUCTION_OPERATION",
                "monitoring_signals": ["primary capacity percent", "backup age", "deletion reconciliation lag"],
                "quota_and_failure_validation": "NOT_RUN",
                "failure_fallback": "새 수집 단계별 보류; 기존 암호화 자료·실시간 기능 유지",
                "provider_exit_status": "NOT_RUN",
                "alternate_validation_status": "NOT_RUN",
                "evidence_refs": [],
                "receipt_ref": None,
            },
            {
                "dependency_id": "EXT-ALERT-PATHS",
                "name": "관리자·비상대응자 경보 경로",
                "data_boundary": "민감 원본·인증증명 없는 최소 장애정보",
                "owner_role": project_facts["operations_owner_role"],
                "quota_status": "NOT_ESTABLISHED",
                "cost_status": "NOT_ESTABLISHED",
                "support_status": "NOT_ESTABLISHED",
                "review_due_condition": "BEFORE_PRODUCTION_OPERATION",
                "monitoring_signals": ["delivery failure", "acknowledgment delay", "alternate path failure"],
                "quota_and_failure_validation": "NOT_RUN",
                "failure_fallback": "서로 다른 대체 연락경로와 자동 신규세션 차단",
                "provider_exit_status": "NOT_RUN",
                "alternate_validation_status": "NOT_RUN",
                "evidence_refs": [],
                "receipt_ref": None,
            },
            {
                "dependency_id": "EXT-ANDROID-DISTRIBUTION",
                "name": "Android build·distribution path",
                "data_boundary": "signed release artifact와 최소 배포 metadata",
                "owner_role": project_facts["operations_owner_role"],
                "quota_status": "NOT_ESTABLISHED",
                "cost_status": "NOT_ESTABLISHED",
                "support_status": "NOT_ESTABLISHED",
                "review_due_condition": "BEFORE_NAMED_RELEASE_CANDIDATE",
                "monitoring_signals": ["artifact hash mismatch", "installation failure", "rollout halt"],
                "quota_and_failure_validation": "NOT_RUN",
                "failure_fallback": "배포 중단; unsigned APK나 Web/PWA로 대체하지 않음",
                "provider_exit_status": "NOT_RUN",
                "alternate_validation_status": "NOT_RUN",
                "deployment_descriptor_ref": _rel(DEPLOYMENT_DESCRIPTOR_PATH),
                "evidence_refs": [],
                "receipt_ref": None,
            },
        ],
        "remaining_gates": _gate_rows(policy),
        "approval_boundary": {
            "registers_preopened": True,
            "operations_started": False,
            "incident_count": 0,
            "operation_change_count": 0,
            "restore_execution_count": 0,
            "postmortem_count": 0,
            "actual_execution_receipt_count": 0,
            "release_status": RELEASE_STATUS,
        },
    }


def _incident_template() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.incident-postmortem-template.v1",
        "metadata": _metadata("운영 incident·postmortem instance 템플릿", []),
        "template_only": True,
        "is_live_incident": False,
        "target_artifact_type_ids": ["OPS-17", "OPS-18"],
        "incident_id": None,
        "severity": None,
        "started_at": None,
        "detected_at": None,
        "ended_at": None,
        "user_and_data_impact": None,
        "timeline": [],
        "evidence_refs": [],
        "root_cause": None,
        "corrective_actions": [],
        "incident_status": "NOT_OCCURRED_OR_NOT_RECORDED",
        "postmortem_status": "NOT_RUN",
    }


def _recovery_template() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.recovery-exercise-template.v1",
        "metadata": _metadata("복원·재해복구 실행 템플릿", []),
        "template_only": True,
        "is_live_evidence": False,
        "target_artifact_type_ids": ["OPS-11", "OPS-13"],
        "exercise_id": None,
        "backup_bundle_id": None,
        "backup_age_days": None,
        "environment_id": None,
        "actual_rto_seconds": None,
        "actual_rpo_seconds": None,
        "object_and_database_verification": [],
        "raw_evidence_refs": [],
        "execution_status": "NOT_RUN",
        "result": None,
    }


def _closure_register() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.closure-readiness-register.v1",
        "metadata": _metadata("WalkSafe 종료·이관 준비 원장", ["CLS-07", "CLS-08", "CLS-09", "CLS-10"]),
        "register_mode": "PREOPENED_APPEND_ONLY",
        "w9_as_of": W9_AS_OF,
        "w9_source_bindings": _w9_source_bindings(),
        "w9_content_contracts": {
            code: W9_LIFECYCLE_CONTRACTS[code]
            for code in ("CLS-07", "CLS-08", "CLS-09", "CLS-10", "CLS-14", "CLS-15", "CLS-16")
        },
        "external_execution_contracts": [
            W9_EXTERNAL_EXECUTION_CONTRACTS[code]
            for code in ("CLS-08", "CLS-10", "CLS-14", "CLS-15", "CLS-16")
        ],
        "project_status": "ACTIVE_NOT_CLOSED",
        "closure_event_started": False,
        "final_acceptance": {"status": "NOT_ISSUED", "external_record_ref": None},
        "final_release": {"status": "NOT_AVAILABLE", "release_id": None},
        "final_artifact_index": {"status": "PLANNED", "items": []},
        "final_archive": {"status": "NOT_RUN", "archive_id": None, "sha256": None},
        "unresolved_defects": {
            "source_register": "docs/deliverables/06-testing/registers/defects.json",
            "formal_execution_count": 0,
            "snapshot_status": "NOT_TAKEN",
            "record_schema_fields": W9_LIFECYCLE_CONTRACTS["CLS-07"]["record_fields"],
            "records": [],
            "opening_snapshot_proves_no_defects": False,
            "warning": "시험 실행 0건은 결함 0건의 품질 증거가 아니다.",
        },
        "residual_risks": {
            "source_register": "docs/deliverables/06-testing/registers/residual-risks.json",
            "gate_ids": GATE_IDS,
            "open_policy_issue_ids": [FP035_ISSUE_ID],
            "record_schema_fields": W9_LIFECYCLE_CONTRACTS["CLS-08"]["record_fields"],
            "closure_snapshot_status": "NOT_TAKEN",
            "risk_acceptance_status": "NOT_APPROVED",
            "acceptor_identity": None,
            "risk_acceptance_records": [],
            "actual_receipt_ids": [],
        },
        "technical_debt": {
            "source_register": _rel(OPS_REGISTER_PATH),
            "record_schema_fields": W9_LIFECYCLE_CONTRACTS["CLS-09"]["record_fields"],
            "closure_snapshot_status": "NOT_TAKEN",
            "risk_acceptance_status": "NOT_APPROVED",
            "acceptance_receipt_ids": [],
        },
        "handover": {
            "branch": "UNDECIDED_CONTINUE_OPERATIONS_OR_DECOMMISSION",
            "recipient": None,
            "operator": None,
            "record_schema_fields": W9_LIFECYCLE_CONTRACTS["CLS-10"]["record_fields"],
            "account_and_permission_transfer_status": "NOT_RUN",
            "approval_status": "NOT_APPROVED",
            "execution_status": "NOT_RUN",
            "evidence_refs": [],
            "actual_handover_receipts": [],
        },
        "data_disposition": {
            "branch": "UNDECIDED_CONTINUE_OPERATIONS_OR_DECOMMISSION",
            "policy_ref": _rel(DATA_RETENTION_POLICY_PATH),
            "record_schema_fields": W9_LIFECYCLE_CONTRACTS["CLS-14"]["record_fields"],
            "legal_basis_status": "NOT_APPROVED",
            "final_korean_notice_status": "NOT_APPROVED",
            "operator": None,
            "approval_status": "NOT_APPROVED",
            "actual_transfer_status": "NOT_RUN",
            "actual_deletion_status": "NOT_RUN",
            "execution_records": [],
            "actual_receipt_ids": [],
        },
        "access_secret_infrastructure_disposition": {
            "record_schema_fields": W9_LIFECYCLE_CONTRACTS["CLS-15"]["record_fields"],
            "secret_values_allowed": False,
            "recipient": None,
            "operator": None,
            "approval_status": "NOT_APPROVED",
            "transfer_status": "NOT_RUN",
            "rotation_status": "NOT_RUN",
            "revocation_status": "NOT_RUN",
            "execution_records": [],
            "actual_receipt_ids": [],
        },
        "decommissioning": {
            "record_schema_fields": W9_LIFECYCLE_CONTRACTS["CLS-16"]["record_fields"],
            "decision_branch": None,
            "authority": None,
            "operator": None,
            "approval_status": "NOT_APPROVED",
            "execution_status": "NOT_RUN",
            "provider_exit_test_status": "NOT_RUN",
            "alternate_provider_validation_status": "NOT_RUN",
            "execution_records": [],
            "actual_receipt_ids": [],
        },
        "closure_results": [],
        "approval_boundary": {
            "project_completed": False,
            "project_closed": False,
            "final_acceptance_signed": False,
            "service_decommissioned": False,
            "risk_acceptance_approved": False,
            "actual_handover_claimed": False,
            "actual_data_transfer_or_deletion_claimed": False,
            "actual_secret_rotation_or_revocation_claimed": False,
            "closure_result_count": 0,
            "actual_execution_receipt_count": 0,
            "release_status": RELEASE_STATUS,
        },
    }


def _closure_execution_template() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.closure-execution-template.v1",
        "metadata": _metadata("종료·이관·폐기 실행 템플릿", []),
        "template_only": True,
        "is_live_evidence": False,
        "target_artifact_type_ids": ["CLS-02", "CLS-05", "CLS-06", "CLS-10", "CLS-13", "CLS-14", "CLS-15", "CLS-16"],
        "closure_id": None,
        "branch": None,
        "allowed_branches": ["TRANSFER_TO_OPERATIONS", "DECOMMISSION_SERVICE"],
        "recipient_or_authority": None,
        "data_actions": [],
        "account_key_infrastructure_actions": [],
        "contract_actions": [],
        "external_original_refs": [],
        "raw_evidence_refs": [],
        "execution_status": "NOT_RUN",
        "approval_status": "NOT_APPROVED",
        "result": None,
    }


def _w9_source_bindings() -> dict[str, dict[str, str]]:
    return {
        "implementation_gap_analysis_r020": _binding(GAP_ANALYSIS_R020_PATH),
        "remediation_backlog_r020": _binding(REMEDIATION_BACKLOG_R020_PATH),
        "data_retention_policy": _binding(DATA_RETENTION_POLICY_PATH),
        "navigation_integration_policy": _binding(NAVIGATION_INTEGRATION_POLICY_PATH),
        "development_deployment_descriptor": _binding(DEPLOYMENT_DESCRIPTOR_PATH),
        "android_user_guide": _binding(USER_GUIDE_PATH),
    }


def _ready25_source_bindings() -> dict[str, dict[str, str]]:
    return {
        "user_scope_transition_receipt": _binding(SCOPE45_TRANSITION_RECEIPT_PATH),
        "r010_successor_evidence": _binding(R010_EVIDENCE_PATH),
        "r010_successor_check_receipt": _binding(R010_RECEIPT_PATH),
    }


def _ready25_rel_source_bindings() -> dict[str, dict[str, str]]:
    return {
        "r007_exact257_ledger": _binding(R007_LEDGER_PATH),
        **_ready25_source_bindings(),
    }


def _source_bindings() -> dict[str, dict[str, str]]:
    return {
        "policy": _binding(POLICY_PATH),
        "policy_approval_record": _binding(APPROVAL_RECORD_PATH),
        "policy_baseline_manifest": _binding(BASELINE_MANIFEST_PATH),
        "aligned_decision_register": _binding(ALIGNED_DECISION_REGISTER_PATH),
        "artifact_catalog": _binding(ARTIFACT_CATALOG_PATH),
        "authoring_plan": _binding(AUTHORING_PLAN_PATH),
        "approved_project_answers": _binding(PROJECT_ANSWERS_PATH),
        "fp035_correction_candidate": _binding(FP035_CORRECTION_CANDIDATE_PATH),
        **_w9_source_bindings(),
        **_ready25_source_bindings(),
        "ready25_rel_r007_exact257_ledger": _binding(R007_LEDGER_PATH),
        "policy_approval_validator_source": _binding(Path(approval_builder.GENERATOR_PATH).resolve()),
        "generator": _binding(GENERATOR_PATH),
    }


def _validate_inputs() -> tuple[dict[str, Any], dict[str, Any], dict[str, Any], dict[str, Any]]:
    policy = load_strict_json(POLICY_PATH)
    approval = load_strict_json(APPROVAL_RECORD_PATH)
    baseline_manifest = load_strict_json(BASELINE_MANIFEST_PATH)
    aligned = load_strict_json(ALIGNED_DECISION_REGISTER_PATH)
    catalog = load_strict_json(ARTIFACT_CATALOG_PATH)
    project_facts = _project_facts()
    _fp035_correction_candidate()

    policy_body = {key: value for key, value in policy.items() if key != "document_content_sha256"}
    _require(policy.get("document_content_sha256") == _object_sha(policy_body), "policy body digest differs")
    _require(policy.get("document_content_sha256") == POLICY_DIGEST, "policy digest differs from approved value")
    _require(len(policy.get("features", [])) == 54, "policy feature count differs")
    _require(len(policy.get("common_policies", [])) == 9, "common-policy count differs")
    _require(
        [gate.get("id") for gate in policy.get("remaining_gates", [])] == GATE_IDS
        and all(gate.get("status") == "NOT_RUN" for gate in policy["remaining_gates"]),
        "remaining gate boundary differs",
    )
    try:
        approval_builder.validate_approval_record(approval)
        approval_builder.validate_baseline_manifest(baseline_manifest, approval)
    except (
        approval_builder.BaselineApprovalError,
        approval_builder.review_builder.BaselineReviewError,
        OSError,
        KeyError,
        TypeError,
        ValueError,
    ) as exc:
        raise RelOpsClsError(f"policy baseline validation failed: {exc}") from exc

    baseline_metadata = baseline_manifest.get("metadata", {})
    _require(baseline_metadata.get("lifecycle_status") == "BASELINED", "policy baseline is not established")
    target = baseline_manifest.get("baseline_payload", {}).get("approval_target", {})
    _require(target.get("document_content_sha256") == POLICY_DIGEST, "baseline policy target differs")
    _require(target.get("decision_binding_sha256") == DECISION_DIGEST, "baseline decision target differs")
    boundary = baseline_manifest.get("establishment_boundary", {})
    _require(boundary.get("remaining_gates_are_waived") is False, "baseline waived remaining gates")
    _require(boundary.get("release_status") == RELEASE_STATUS, "baseline release status differs")

    _require(len(catalog.get("artifact_types", [])) == 257, "artifact catalog must contain 257 types")
    scoped = [
        item
        for item in catalog["artifact_types"]
        if item.get("category") in {"REL", "OPS", "CLS"}
    ]
    _require(len(scoped) == 62, "REL/OPS/CLS catalog scope must contain 62 types")
    catalog_by_code = _catalog_by_code(catalog)
    _require(set(catalog_by_code) == set(SCOPE_ARTIFACT_TYPE_IDS), "catalog scope IDs differ")
    for path, codes in DOCUMENT_COVERAGE.items():
        expected_bundle = BUNDLE_BY_PATH[path]
        for code in codes:
            _require(
                catalog_by_code[code].get("recommended_bundle_id") == expected_bundle,
                f"catalog bundle differs for {code}",
            )

    _require(len(aligned.get("decisions", [])) == 135, "aligned decision register count differs")
    aligned_boundary = aligned.get("approval_boundary", {})
    _require(aligned_boundary.get("policy_alignment_status") == "COMPLETE", "decision alignment is incomplete")
    _require(aligned_boundary.get("release_status") == RELEASE_STATUS, "aligned release status differs")
    _require(AUTHORING_PLAN_PATH.is_file(), "authoring plan is missing")

    materialized = set(MATERIALIZED_ARTIFACT_TYPE_IDS)
    planned = set(PLANNED_ARTIFACT_TYPE_IDS)
    scoped_ids = set(SCOPE_ARTIFACT_TYPE_IDS)
    _require(not materialized & planned, "materialized/planned sets overlap")
    _require(materialized | planned == scoped_ids, "materialized/planned union does not cover scope")
    _require(len(materialized) == 39 and len(planned) == 23, "materialization counts differ")
    _require(set(SPECIFIC_RULES) == scoped_ids, "specific rule coverage differs")
    _require(len(DOCUMENT_COVERAGE) == len(BUNDLE_BY_PATH) == 9, "bundle document count differs")
    document_codes = [code for codes in DOCUMENT_COVERAGE.values() for code in codes]
    _require(
        len(document_codes) == len(set(document_codes)) == 62
        and set(document_codes) == set(SCOPE_ARTIFACT_TYPE_IDS),
        "document coverage scope differs",
    )
    return policy, catalog, aligned, project_facts


def _planned_reason(code: str, catalog: dict[str, dict[str, Any]]) -> str:
    form = catalog[code]["recommended_form"]
    if code.startswith("CLS-"):
        return "PROJECT_NOT_CLOSED_AND_CLOSURE_EVENT_NOT_STARTED"
    if form == "EXTERNAL_RECORD":
        return "EXTERNAL_ORIGINAL_NOT_ISSUED"
    if code == "OPS-06":
        return "OPERATIONS_DASHBOARD_NOT_IMPLEMENTED_OR_VALIDATED"
    if code == "OPS-07":
        return "ALERT_CONFIGURATION_NOT_IMPLEMENTED_OR_BOUND_TO_AN_OPERATING_ENVIRONMENT"
    if code == "OPS-18":
        return "NO_REAL_INCIDENT_POSTMORTEM_EXECUTION"
    if code in {"REL-13", "REL-14"}:
        return "TST_22_NOT_GO_OR_CONDITIONAL_GO_AND_DEPLOYMENT_NOT_RUN"
    return "NO_NAMED_RELEASE_OR_REAL_EXECUTION_EVIDENCE"


def _ready25_change_event() -> dict[str, Any]:
    scope45_application, _application_binding, _receipt_binding = (
        _ready25_scope45_application()
    )
    scope45_codes = [
        item["artifact_id"].removeprefix("DLV-")
        for item in scope45_application["transitions"]
    ]
    return {
        "change_id": "CHG-DOC-0013",
        "date": READY25_AS_OF,
        "change_type": "INTERNAL_READY25_REGISTER_SYNCHRONIZED",
        "title": (
            "scope45 현행 범위 투영·Ready25 exact25 적용·"
            "통제 원장 self-digest 재봉인"
        ),
        "reason": (
            "승인된 scope45의 43 IN_SCOPE·2 current-scope N/A 결정을 DOC-01에 "
            "완전 투영하고 Ready25 exact9/13/3의 내부 작성 상태와 QA 미지정 "
            "경계를 추가한 뒤, DOC-01/DOC-05의 생성 재현성과 self-digest를 "
            "현재 바이트 기준으로 검증하기 위함"
        ),
        "before_summary": (
            "scope45 정본 결정이 DOC-01 root에는 일부만 반영돼 있었고 exact9 "
            "행은 CONDITIONAL/PENDING_EVALUATION으로 남아 있었다. DOC-01의 "
            "content_sha256은 후속 mutation 뒤 재봉인되지 않았으며 기존 "
            "successor packet은 역사 경로에 보존돼 있다."
        ),
        "after_summary": (
            "scope45를 43 IN_SCOPE·DSC-04/WS-16 current-scope N/A로 완전 "
            "투영하고 exact9/13/3 총 25개 행의 content_authored 상태를 "
            "동기화했다. 독립 QA는 UNASSIGNED이며 acceptance·approval·"
            "execution·actual event·formal evidence·release credit은 모두 0이다. "
            "DOC-05를 먼저 append/self-seal한 뒤 DOC-01을 재봉인했다."
        ),
        "affected_artifact_codes": [
            "DOC-01",
            "DOC-05",
            *scope45_codes,
        ],
        "affected_paths": [
            _rel(GENERATOR_PATH),
            "tests/test_walksafe_formal_rel_ops_cls.py",
            _rel(ARTIFACT_REGISTER_PATH),
            _rel(ARTIFACT_CHANGE_LOG_PATH),
            _rel(SCOPE45_TRANSITION_APPLICATION_PATH),
            _rel(SCOPE45_TRANSITION_RECEIPT_PATH),
            *[_rel(path) for path in ADD_ONLY_SUCCESSOR_OUTPUT_PATHS],
        ],
        "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "lifecycle_status": "DRAFT",
        "reviewer": None,
        "approved_at": None,
        "requested_by": "PROJECT_SCOPE_OWNER",
        "affected_requirement_ids": [],
        "affected_test_ids": [],
        "review": {
            "review_status": "PENDING",
            "reviewer": None,
            "approval_status": "NOT_APPROVED",
            "approval_record": None,
        },
        "application": {
            "document_version": "1.0.2",
            "source_commit": None,
            "baseline_id": None,
            "predecessor_packet_bindings": {
                "ai_dev_sec_r001_evidence": _binding(
                    READY25_AI_DEV_SEC_R001_EVIDENCE_PATH
                ),
                "ai_dev_sec_r001_receipt": _binding(
                    READY25_AI_DEV_SEC_R001_RECEIPT_PATH
                ),
                "cls_ops_r002_evidence": _binding(
                    READY25_CLS_OPS_R002_EVIDENCE_PATH
                ),
                "cls_ops_r002_receipt": _binding(
                    READY25_CLS_OPS_R002_RECEIPT_PATH
                ),
                "rel_r001_evidence": _binding(READY25_REL_EVIDENCE_PATH),
                "rel_r001_receipt": _binding(READY25_REL_RECEIPT_PATH),
            },
            "acceptance_credit_count": 0,
            "approval_credit_count": 0,
            "execution_credit_count": 0,
            "actual_event_credit_count": 0,
            "formal_evidence_credit_count": 0,
            "release_credit_count": 0,
        },
        "rollback_or_supersedes": None,
    }


def _ready25_artifact_change_log() -> dict[str, Any]:
    change_log = load_strict_json(ARTIFACT_CHANGE_LOG_PATH)
    declared_digest = change_log.pop("content_sha256", None)
    _require(
        declared_digest == _object_sha(change_log),
        "DOC-05 predecessor self-digest differs",
    )
    changes = change_log.get("changes")
    _require(isinstance(changes, list), "DOC-05 changes list is missing")
    _require(
        len(changes) >= 12
        and _object_sha(changes[:12])
        == "22a91675ce4e2b7d3ea244c3d0c7f3debb43320f2c4327e28b37aa58452be800",
        "DOC-05 immutable CHG-DOC-0001~0012 prefix differs",
    )
    stable_projection = json.loads(json.dumps(change_log, ensure_ascii=False))
    stable_projection["changes"] = stable_projection["changes"][:12]
    stable_projection.pop("summary", None)
    stable_projection["source_bindings"] = [
        item
        for item in stable_projection.get("source_bindings", [])
        if item.get("name") != "current_revision_generator"
    ]
    stable_metadata = stable_projection["metadata"]
    for field in ("document_version", "as_of", "updated_at"):
        stable_metadata.pop(field, None)
    _require(
        _object_sha(stable_projection)
        == "5da67f1c971ccf0e3cf0159545803c3962a330c7a32b3b520c070ae327980b33",
        "DOC-05 stable predecessor projection differs",
    )
    event = _ready25_change_event()
    matching = [
        item for item in changes if item.get("change_id") == event["change_id"]
    ]
    if not matching:
        _require(
            len(changes) == 12
            and change_log.get("summary")
            == {
                "change_count": 12,
                "approved_change_count": 1,
                "last_change_id": "CHG-DOC-0012",
            },
            "DOC-05 has an unexpected predecessor before CHG-DOC-0013",
        )
        changes.append(event)
    else:
        _require(
            len(matching) == 1
            and len(changes) == 13
            and changes[-1] == event,
            "DOC-05 CHG-DOC-0013 differs or is duplicated",
        )
    metadata = change_log.setdefault("metadata", {})
    metadata["document_version"] = "1.0.2"
    metadata["as_of"] = READY25_AS_OF
    metadata["updated_at"] = READY25_AS_OF
    summary = change_log.setdefault("summary", {})
    summary["change_count"] = 13
    summary["approved_change_count"] = 1
    summary["last_change_id"] = event["change_id"]
    current_generator = {
        "name": "current_revision_generator",
        "path": _rel(GENERATOR_PATH),
        "sha256": _sha_file(GENERATOR_PATH),
    }
    current_generator_bindings = [
        item
        for item in change_log.get("source_bindings", [])
        if item.get("name") == "current_revision_generator"
    ]
    if current_generator_bindings:
        _require(
            len(current_generator_bindings) == 1,
            "DOC-05 current revision generator binding is duplicated",
        )
        current_generator_bindings[0].update(current_generator)
    else:
        change_log.setdefault("source_bindings", []).append(
            current_generator
        )
    change_log["content_sha256"] = _object_sha(change_log)
    return change_log


def _ready25_artifact_rows(value: Any) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if isinstance(value, dict):
        if (
            value.get("artifact_type_code", "").removeprefix("DLV-")
            in READY25_EXACT13
            and value.get("artifact_instance_id")
        ):
            rows.append(value)
        for item in value.values():
            rows.extend(_ready25_artifact_rows(item))
    elif isinstance(value, list):
        for item in value:
            rows.extend(_ready25_artifact_rows(item))
    return rows


def _ready25_ai_dev_sec_artifact_rows(value: Any) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if isinstance(value, dict):
        if (
            value.get("artifact_type_code", "").removeprefix("DLV-")
            in READY25_AI_DEV_SEC_EXACT9
            and value.get("artifact_instance_id")
        ):
            rows.append(value)
        for item in value.values():
            rows.extend(_ready25_ai_dev_sec_artifact_rows(item))
    elif isinstance(value, list):
        for item in value:
            rows.extend(_ready25_ai_dev_sec_artifact_rows(item))
    return rows


def _ready25_rel_artifact_rows(value: Any) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    if isinstance(value, dict):
        if (
            value.get("artifact_type_code", "").removeprefix("DLV-")
            in READY25_REL_EXACT3
            and value.get("artifact_instance_id")
        ):
            rows.append(value)
        for item in value.values():
            rows.extend(_ready25_rel_artifact_rows(item))
    elif isinstance(value, list):
        for item in value:
            rows.extend(_ready25_rel_artifact_rows(item))
    return rows


def _ready25_scope45_application(
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    application_bytes = SCOPE45_TRANSITION_APPLICATION_PATH.read_bytes()
    receipt_bytes = SCOPE45_TRANSITION_RECEIPT_PATH.read_bytes()
    _require(
        _sha_bytes(application_bytes)
        == SCOPE45_TRANSITION_APPLICATION_SHA256
        and _sha_bytes(receipt_bytes)
        == SCOPE45_TRANSITION_RECEIPT_SHA256,
        "Ready25 scope45 immutable application or receipt differs",
    )
    application = load_strict_json(
        SCOPE45_TRANSITION_APPLICATION_PATH
    )
    receipt = load_strict_json(SCOPE45_TRANSITION_RECEIPT_PATH)
    application_binding = _ready25_output_binding(
        SCOPE45_TRANSITION_APPLICATION_PATH,
        application_bytes,
    )
    receipt_binding = _ready25_output_binding(
        SCOPE45_TRANSITION_RECEIPT_PATH,
        receipt_bytes,
    )
    transitions = application["transitions"]
    transition_by_id = {
        item["artifact_id"]: item for item in transitions
    }
    decisions = Counter(
        item["decision"]["normalized_decision"]
        for item in transitions
    )
    n_a_ids = {
        item["artifact_id"]
        for item in transitions
        if item["decision"]["normalized_decision"]
        == "OUT_OF_SCOPE_N_A"
    }
    _require(
        receipt["status"] == "PASS"
        and receipt["application_binding"]
        == {
            "binding_id": "SCOPE45-TRANSITION-APPLICATION-R001",
            "byte_length": application_binding["byte_length"],
            "observation_basis": (
                "IN_MEMORY_FINAL_OUTPUT_BYTES_BEFORE_ADD_ONLY_WRITE"
            ),
            "path": application_binding["path"],
            "sha256": application_binding["sha256"],
            "subject_role": (
                "PHASE1_SCOPE45_TRANSITION_APPLICATION_OUTPUT"
            ),
        }
        and receipt["summary"]
        == {
            "check_count": 16,
            "fail_count": 0,
            "in_scope_open_count": 43,
            "pass_count": 16,
            "queue_sum_after": 257,
            "scope_n_a_closed_count": 2,
            "transition_count": 45,
        }
        and len(transitions) == len(transition_by_id) == 45
        and decisions
        == Counter({"IN_SCOPE": 43, "OUT_OF_SCOPE_N_A": 2})
        and n_a_ids == {"DLV-DSC-04", "DLV-WS-16"}
        and application["transition_contract"][
            "exact_transition_count"
        ]
        == 45
        and application["transition_contract"]["in_scope_count"]
        == 43
        and application["transition_contract"][
            "out_of_scope_n_a_count"
        ]
        == 2
        and application["queue_counts"]["after"]
        == {
            "ATTESTATION_REVIEW_PENDING": 4,
            "EVIDENCE_FACT_PENDING": 6,
            "INTERNAL_READY": 62,
            "INTERNAL_RUN_REQUIRED": 24,
            "OK_BASELINE": 124,
            "OWNER_APPROVAL_PENDING": 14,
            "REAL_EVENT_PENDING": 21,
            "SCOPE_DECISION_PENDING": 0,
            "SCOPE_N_A_APPROVED": 2,
        }
        and all(
            item["acceptance_boundary"][
                "content_acceptance_credit"
            ]
            == 0
            and item["acceptance_boundary"]["execution_credit"]
            == 0
            and item["acceptance_boundary"]["formal_test_credit"]
            == 0
            and item["acceptance_boundary"][
                "owner14_approval_credit"
            ]
            == 0
            and item["acceptance_boundary"]["attestation_credit"]
            == 0
            and item["acceptance_boundary"]["release_credit"]
            == 0
            and item["successor"]["release_eligibility"]
            == RELEASE_STATUS
            for item in transitions
        ),
        "Ready25 scope45 transition contract differs",
    )
    return application, application_binding, receipt_binding


def _ready25_scope45_predecessor_register_states(
    register: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    application, _application_binding, _receipt_binding = (
        _ready25_scope45_application()
    )
    rows_by_code = {
        row["artifact_type_code"].removeprefix("DLV-"): row
        for row in register["artifacts"]
    }
    result: dict[str, dict[str, Any]] = {}
    for transition in application["transitions"]:
        code = transition["artifact_id"].removeprefix("DLV-")
        row = rows_by_code[code]
        current_scope = row.get("scope45_current_scope")
        embedded = (
            current_scope.get("predecessor_register_state")
            if isinstance(current_scope, dict)
            else None
        )
        if isinstance(embedded, dict):
            predecessor = json.loads(
                json.dumps(embedded, ensure_ascii=False)
            )
        else:
            predecessor = {
                "applicability": row.get("applicability"),
                "activation_result": row.get(
                    "activation_result"
                ),
                "n_a_reason": row.get("n_a_reason"),
                "n_a_approved_by": row.get("n_a_approved_by"),
                "n_a_review_at": row.get("n_a_review_at"),
                "n_a_review_trigger": row.get(
                    "n_a_review_trigger"
                ),
                "state_blockers": list(
                    row.get("state", {}).get("blockers", [])
                ),
            }
        result[code] = predecessor
    _require(
        len(result) == 45
        and _object_sha(result)
        == SCOPE45_PREDECESSOR_REGISTER_PROJECTION_SHA256,
        "Ready25 scope45 pinned predecessor projection differs",
    )
    return result


def _ready25_apply_scope45(register: dict[str, Any]) -> None:
    application, application_binding, receipt_binding = (
        _ready25_scope45_application()
    )
    predecessor_register_states = (
        _ready25_scope45_predecessor_register_states(register)
    )
    rows_by_code = {
        row["artifact_type_code"]: row
        for row in register["artifacts"]
    }
    transitions = application["transitions"]
    transition_ids = {
        item["artifact_id"] for item in transitions
    }
    _require(
        transition_ids <= set(rows_by_code),
        "Ready25 scope45 artifact-register row set differs",
    )
    in_scope_ids: list[str] = []
    n_a_ids: list[str] = []
    for transition in transitions:
        artifact_id = transition["artifact_id"]
        row = rows_by_code[artifact_id]
        predecessor_register_state = json.loads(
            json.dumps(
                predecessor_register_states[
                    artifact_id.removeprefix("DLV-")
                ],
                ensure_ascii=False,
            )
        )
        decision = transition["decision"]
        successor = transition["successor"]
        normalized_decision = decision["normalized_decision"]
        is_current_scope_n_a = (
            normalized_decision == "OUT_OF_SCOPE_N_A"
        )
        row["scope45_current_scope"] = {
            "application_id": application["application_id"],
            "application_binding": application_binding,
            "receipt_binding": receipt_binding,
            "normalized_decision": normalized_decision,
            "decision_id": decision["decision_id"],
            "decision_maker_identity": decision[
                "decision_maker_identity"
            ],
            "decision_maker_role": decision[
                "decision_maker_role"
            ],
            "authority_basis": decision["authority_basis"],
            "scope_decision_status": successor[
                "scope_decision_status"
            ],
            "queue_route": successor["queue_route"],
            "queue_open": successor["queue_open"],
            "artifact_closure_status": successor[
                "artifact_closure_status"
            ],
            "current_scope_n_a_closure_claimed": (
                is_current_scope_n_a
            ),
            "global_artifact_completion_claimed": False,
            "content_acceptance_credit": 0,
            "execution_credit": 0,
            "formal_test_credit": 0,
            "owner14_approval_credit": 0,
            "attestation_credit": 0,
            "release_credit": 0,
            "release_eligibility": RELEASE_STATUS,
            "validity": transition["validity"],
            "reactivation_trigger": transition[
                "route_reconsideration_trigger"
            ],
            "scope_n_a_compatibility": transition.get(
                "scope_n_a_compatibility"
            ),
            "predecessor_register_state": (
                predecessor_register_state
            ),
        }
        if is_current_scope_n_a:
            n_a_ids.append(artifact_id)
            compatibility = transition["scope_n_a_compatibility"]
            row["applicability"] = "CONDITIONAL"
            row["activation_result"] = (
                "NOT_ACTIVE_CURRENT_BASELINE"
            )
            row["n_a_reason"] = compatibility[
                "enforced_boundary"
            ]
            row["n_a_approved_by"] = decision[
                "decision_maker_identity"
            ]
            row["n_a_review_at"] = decision["decided_on"]
            row["n_a_review_trigger"] = compatibility[
                "reactivation_trigger"
            ]
            row.setdefault("state", {})["blockers"] = [
                "NOT_ACTIVE_UNDER_CURRENT_BASELINE"
            ]
        else:
            in_scope_ids.append(artifact_id)
            row["applicability"] = "IN_SCOPE"
    register["scope45_current_scope"] = {
        "application_id": application["application_id"],
        "application_binding": application_binding,
        "receipt_binding": receipt_binding,
        "transition_count": 45,
        "in_scope_count": 43,
        "out_of_scope_n_a_count": 2,
        "in_scope_artifact_ids": in_scope_ids,
        "out_of_scope_n_a_artifact_ids": n_a_ids,
        "scope_n_a_current_scope_closure_credit_count": 2,
        "global_artifact_completion_credit_count": 0,
        "content_acceptance_credit_count": 0,
        "execution_credit_count": 0,
        "formal_test_credit_count": 0,
        "owner14_approval_credit_count": 0,
        "attestation_credit_count": 0,
        "release_credit_count": 0,
        "release_status": RELEASE_STATUS,
    }


def _ready25_prior_r001_bindings() -> dict[str, dict[str, Any]]:
    evidence = _ready25_output_binding(
        READY25_EVIDENCE_PATH, READY25_EVIDENCE_PATH.read_bytes()
    )
    receipt = _ready25_output_binding(
        READY25_RECEIPT_PATH, READY25_RECEIPT_PATH.read_bytes()
    )
    _require(
        evidence["sha256"] == READY25_R001_EVIDENCE_SHA256
        and receipt["sha256"] == READY25_R001_RECEIPT_SHA256,
        "Ready25 CLS/OPS r001 immutable predecessor differs",
    )
    return {
        "r001_evidence": evidence,
        "r001_check_receipt": receipt,
    }


def _ready25_predecessor_binding(
    path: Path,
    expected_sha256: str,
    label: str,
) -> dict[str, Any]:
    binding = _ready25_output_binding(path, path.read_bytes())
    _require(
        binding["sha256"] == expected_sha256,
        f"{label} immutable predecessor differs",
    )
    return binding


def _ready25_baseline_activation_states() -> dict[str, dict[str, str]]:
    candidate = load_strict_json(ARTIFACT_BASELINE_CANDIDATE_PATH)
    _require(
        _sha_file(ARTIFACT_BASELINE_CANDIDATE_PATH)
        == ARTIFACT_BASELINE_CANDIDATE_SHA256,
        "Ready25 activation baseline candidate physical binding differs",
    )
    candidate_body = {
        key: value
        for key, value in candidate.items()
        if key != "candidate_record_content_sha256"
    }
    _require(
        candidate["candidate_record_content_sha256"]
        == _object_sha(candidate_body),
        "Ready25 activation baseline candidate self-digest differs",
    )
    exact25 = {
        *READY25_AI_DEV_SEC_EXACT9,
        *READY25_EXACT13,
        *READY25_REL_EXACT3,
    }
    states = {
        item["artifact_type_code"].removeprefix("DLV-"): {
            "applicability": item["applicability"],
            "activation_result": item["activation_result"],
        }
        for item in candidate["artifact_dispositions"]
        if item["artifact_type_code"].removeprefix("DLV-") in exact25
    }
    _require(
        set(states) == exact25
        and all(
            state
            == {
                "applicability": "CONDITIONAL",
                "activation_result": "PENDING_EVALUATION",
            }
            for state in states.values()
        ),
        "Ready25 exact25 activation baseline differs",
    )
    return states


def _ready25_ai_dev_sec_r001() -> tuple[dict[str, Any], dict[str, Any]]:
    evidence = load_strict_json(READY25_AI_DEV_SEC_R001_EVIDENCE_PATH)
    receipt = load_strict_json(READY25_AI_DEV_SEC_R001_RECEIPT_PATH)
    for value, label in (
        (evidence, "Ready25 AI/DEV/SEC r001 evidence"),
        (receipt, "Ready25 AI/DEV/SEC r001 receipt"),
    ):
        integrity = value.get("nonself_integrity", {})
        body = {
            key: item
            for key, item in value.items()
            if key != "nonself_integrity"
        }
        projection = (
            json.dumps(
                body,
                ensure_ascii=True,
                sort_keys=True,
                separators=(",", ":"),
            )
            + "\n"
        ).encode("utf-8")
        _require(
            integrity.get("sha256") == _sha_bytes(projection)
            and integrity.get("projection_bytes") == len(projection),
            f"{label} nonself integrity differs",
        )
    _require(
        evidence.get("exact_set", {}).get("artifact_type_codes")
        == [f"DLV-{code}" for code in READY25_AI_DEV_SEC_EXACT9]
        and len(evidence.get("per_id_acceptance_content_crosswalk", []))
        == len(READY25_AI_DEV_SEC_EXACT9),
        "Ready25 AI/DEV/SEC r001 exact9 contract differs",
    )
    source_bindings = evidence.get("source_bindings", [])
    _require(
        len(source_bindings) == 14
        and len({item.get("path") for item in source_bindings})
        == len(source_bindings),
        "Ready25 AI/DEV/SEC r001 source binding set differs",
    )
    for binding in source_bindings:
        source_path = REPO_ROOT / binding["path"]
        _require(
            source_path.is_file()
            and source_path.stat().st_size == binding["bytes"]
            and _sha_file(source_path) == binding["sha256"],
            "Ready25 AI/DEV/SEC r001 physical source binding differs: "
            f"{binding['path']}",
        )
    _ready25_predecessor_binding(
        READY25_AI_DEV_SEC_R001_EVIDENCE_PATH,
        READY25_AI_DEV_SEC_R001_EVIDENCE_SHA256,
        "Ready25 AI/DEV/SEC r001 evidence",
    )
    _ready25_predecessor_binding(
        READY25_AI_DEV_SEC_R001_RECEIPT_PATH,
        READY25_AI_DEV_SEC_R001_RECEIPT_SHA256,
        "Ready25 AI/DEV/SEC r001 receipt",
    )
    return evidence, receipt


def _ready25_ai_dev_sec_predecessor_bindings() -> dict[str, dict[str, Any]]:
    _ready25_ai_dev_sec_r001()
    return {
        "r001_evidence": _ready25_predecessor_binding(
            READY25_AI_DEV_SEC_R001_EVIDENCE_PATH,
            READY25_AI_DEV_SEC_R001_EVIDENCE_SHA256,
            "Ready25 AI/DEV/SEC r001 evidence",
        ),
        "r001_check_receipt": _ready25_predecessor_binding(
            READY25_AI_DEV_SEC_R001_RECEIPT_PATH,
            READY25_AI_DEV_SEC_R001_RECEIPT_SHA256,
            "Ready25 AI/DEV/SEC r001 receipt",
        ),
    }


def _ready25_cls_ops_predecessor_bindings() -> dict[str, dict[str, Any]]:
    return {
        **_ready25_prior_r001_bindings(),
        "artifact_baseline_candidate": _ready25_predecessor_binding(
            ARTIFACT_BASELINE_CANDIDATE_PATH,
            ARTIFACT_BASELINE_CANDIDATE_SHA256,
            "Ready25 activation baseline candidate",
        ),
        "r002_evidence": _ready25_predecessor_binding(
            READY25_CLS_OPS_R002_EVIDENCE_PATH,
            READY25_CLS_OPS_R002_EVIDENCE_SHA256,
            "Ready25 CLS/OPS r002 evidence",
        ),
        "r002_check_receipt": _ready25_predecessor_binding(
            READY25_CLS_OPS_R002_RECEIPT_PATH,
            READY25_CLS_OPS_R002_RECEIPT_SHA256,
            "Ready25 CLS/OPS r002 receipt",
        ),
    }


def _ready25_rel_predecessor_bindings() -> dict[str, dict[str, Any]]:
    return {
        "artifact_baseline_candidate": _ready25_predecessor_binding(
            ARTIFACT_BASELINE_CANDIDATE_PATH,
            ARTIFACT_BASELINE_CANDIDATE_SHA256,
            "Ready25 activation baseline candidate",
        ),
        "r001_evidence": _ready25_predecessor_binding(
            READY25_REL_EVIDENCE_PATH,
            READY25_REL_R001_EVIDENCE_SHA256,
            "Ready25 REL r001 evidence",
        ),
        "r001_check_receipt": _ready25_predecessor_binding(
            READY25_REL_RECEIPT_PATH,
            READY25_REL_R001_RECEIPT_SHA256,
            "Ready25 REL r001 receipt",
        ),
    }


def _ready25_rel_r007_contracts() -> list[dict[str, Any]]:
    ledger = load_strict_json(R007_LEDGER_PATH)
    rows = [
        row
        for row in ledger.get("records", [])
        if row.get("artifact_type_code", "").removeprefix("DLV-")
        in READY25_REL_EXACT3
    ]
    _require(len(rows) == len(READY25_REL_EXACT3), "R007 Ready25 REL exact3 rows differ")
    result = []
    for row in rows:
        predecessor = row["predecessor_artifact_ledger_record"]
        code = row["artifact_type_code"].removeprefix("DLV-")
        result.append(
            {
                "artifact_type_id": code,
                "catalog_artifact_id": row["artifact_type_code"],
                "subject_of_truth": predecessor["subject_of_truth"],
                "acceptance_contract": predecessor["acceptance_contract"],
                "scope_decision_status": row["progress_axes"]["scope_decision"][
                    "status"
                ],
                "artifact_completion_claimed": row["artifact_closure"][
                    "completion_claimed"
                ],
                "release_eligible": row["release_eligibility"]["eligible"],
            }
        )
    return result


def _ready25_root_predecessor_register_state(
    row: dict[str, Any],
) -> dict[str, Any]:
    readiness = row.get("authoring_readiness", {})
    state = row.get("state", {})
    version = row.get("version", {})
    integrity = row.get("integrity", {})
    approval_control = row.get("approval_control")
    return {
        "authoring_readiness": {
            key: readiness.get(key)
            for key in (
                "readiness",
                "approval_proposed",
                "approval_track",
                "approval_application_status",
            )
        },
        "state": {
            key: state.get(key)
            for key in (
                "lifecycle_status",
                "verification_status",
                "approval_status",
                "baseline_status",
            )
        },
        "version": {
            key: version.get(key)
            for key in (
                "document_version",
                "approved_snapshot_version",
                "snapshot_id",
                "current_active_revision",
            )
        },
        "integrity": {
            "sha256": integrity.get("sha256"),
            "generator": integrity.get("generator"),
            "generator_sha256": integrity.get("generator_sha256"),
            "last_verified_at": integrity.get("last_verified_at"),
        },
        "approval_control": (
            json.loads(
                json.dumps(
                    approval_control,
                    ensure_ascii=False,
                )
            )
            if isinstance(approval_control, dict)
            else None
        ),
    }


def _ready25_remediation_field(code: str) -> str:
    if code in READY25_AI_DEV_SEC_EXACT9:
        return "ready25_ai_dev_sec_remediation"
    if code in READY25_EXACT13:
        return "ready25_remediation"
    if code in READY25_REL_EXACT3:
        return "ready25_rel_remediation"
    raise RelOpsClsError(
        f"Ready25 predecessor projection received an unknown code: {code}"
    )


def _ready25_pinned_predecessor_register_states(
    register: dict[str, Any],
) -> dict[str, dict[str, Any]]:
    rows_by_code = {
        row["artifact_type_code"].removeprefix("DLV-"): row
        for row in register["artifacts"]
    }
    _require(
        set(READY25_EXACT25) <= set(rows_by_code),
        "Ready25 predecessor register exact25 row set differs",
    )
    activation_states = _ready25_baseline_activation_states()
    result: dict[str, dict[str, Any]] = {}
    for code in READY25_EXACT25:
        row = rows_by_code[code]
        remediation = row.get(_ready25_remediation_field(code))
        embedded = (
            remediation.get("predecessor_register_state")
            if isinstance(remediation, dict)
            else None
        )
        if isinstance(embedded, dict):
            predecessor = json.loads(
                json.dumps(embedded, ensure_ascii=False)
            )
        else:
            predecessor = _ready25_root_predecessor_register_state(
                row
            )
            predecessor.update(activation_states[code])
        result[code] = predecessor
    _require(
        _object_sha(result)
        == READY25_PREDECESSOR_REGISTER_PROJECTION_SHA256,
        "Ready25 pinned predecessor register projection differs",
    )
    return result


def _ready25_mark_current_revision_pending(
    row: dict[str, Any],
    content_status: str,
) -> None:
    readiness = row.setdefault("authoring_readiness", {})
    readiness["ready25_current_revision_approval_application_status"] = (
        "NOT_APPLIED"
    )
    readiness["approval_application_status_scope"] = (
        "PRE_READY25_SNAPSHOT_ONLY"
        if readiness.get("approval_application_status")
        else "NO_PRE_READY25_APPLICATION"
    )
    state = row.setdefault("state", {})
    predecessor_approval = state.get("approval_status")
    predecessor_verification = state.get("verification_status")
    state["approval_status_scope"] = (
        "PRE_READY25_SNAPSHOT_ONLY"
        if predecessor_approval
        else "NO_PRE_READY25_APPROVAL"
    )
    state["verification_status_scope"] = (
        "PRE_READY25_SNAPSHOT_ONLY"
        if predecessor_verification
        else "NO_PRE_READY25_VERIFICATION"
    )
    state["lifecycle_status_scope"] = "PRE_READY25_SNAPSHOT_ONLY"
    state["baseline_status_scope"] = (
        "PRE_READY25_SNAPSHOT_ONLY"
        if state.get("baseline_status")
        else "NO_PRE_READY25_BASELINE"
    )
    state["ready25_current_revision"] = {
        "content_status": content_status,
        "content_accepted": False,
        "approval_status": "NOT_APPROVED",
        "verification_status": "INTERNAL_CONTENT_CHECKED",
        "baseline_status": "NOT_BASELINED",
        "execution_credit_count": 0,
        "actual_event_credit_count": 0,
        "formal_evidence_credit_count": 0,
        "release_credit_count": 0,
    }
    approval_control = row.get("approval_control")
    if isinstance(approval_control, dict):
        approval_control["approval_decision_scope"] = (
            "PRE_READY25_SNAPSHOT_ONLY"
        )
        approval_control["ready25_current_revision"] = {
            "approval_status": "NOT_APPROVED",
            "baseline_status": "NOT_BASELINED",
            "approval_credit_count": 0,
        }


def _ready25_refresh_register_summary(
    register: dict[str, Any],
) -> None:
    rows = register["artifacts"]
    applicability_counts = Counter(
        row["applicability"] for row in rows
    )
    readiness_counts = Counter(
        row["authoring_readiness"]["readiness"] for row in rows
    )
    lifecycle_counts = Counter(
        row["state"]["lifecycle_status"] for row in rows
    )
    activation_counts = Counter(
        row["activation_result"] for row in rows
    )
    category_counts = Counter(row["category"] for row in rows)
    form_counts = Counter(row["artifact_form"] for row in rows)
    ready25_current_revisions = [
        row["state"]["ready25_current_revision"]
        for row in rows
        if isinstance(
            row.get("state", {}).get(
                "ready25_current_revision"
            ),
            dict,
        )
    ]
    summary = register["summary"]
    summary.update(
        {
            "artifact_type_count": len(rows),
            "artifact_count": len(rows),
            "required_count": applicability_counts["REQUIRED"],
            "conditional_count": applicability_counts["CONDITIONAL"],
            "in_scope_count": applicability_counts["IN_SCOPE"],
            "current_scope_n_a_count": sum(
                row.get("scope45_current_scope", {}).get(
                    "current_scope_n_a_closure_claimed"
                )
                is True
                for row in rows
            ),
            "global_artifact_completion_from_scope_n_a_count": 0,
            "legacy_approval_and_lifecycle_counts_scope": (
                "PRE_READY25_SNAPSHOT_ONLY"
            ),
            "ready25_current_revision_count": len(
                ready25_current_revisions
            ),
            "ready25_current_content_accepted_count": sum(
                revision["content_accepted"] is True
                for revision in ready25_current_revisions
            ),
            "ready25_current_approval_count": sum(
                revision["approval_status"] != "NOT_APPROVED"
                for revision in ready25_current_revisions
            ),
            "ready25_current_execution_credit_count": sum(
                revision["execution_credit_count"]
                for revision in ready25_current_revisions
            ),
            "ready25_current_actual_event_credit_count": sum(
                revision["actual_event_credit_count"]
                for revision in ready25_current_revisions
            ),
            "ready25_current_formal_evidence_credit_count": sum(
                revision["formal_evidence_credit_count"]
                for revision in ready25_current_revisions
            ),
            "ready25_current_release_credit_count": sum(
                revision["release_credit_count"]
                for revision in ready25_current_revisions
            ),
            "applicability_counts": dict(
                sorted(applicability_counts.items())
            ),
            "n_a_count": activation_counts[
                "NOT_ACTIVE_CURRENT_BASELINE"
            ],
            "content_approval_candidate_count": sum(
                bool(row["authoring_readiness"]["approval_proposed"])
                for row in rows
            ),
            "content_approval_pending_count": sum(
                not bool(
                    row["authoring_readiness"]["approval_proposed"]
                )
                for row in rows
            ),
            "active_opening_snapshot_candidate_count": sum(
                row["authoring_readiness"]["approval_track"]
                == "ACTIVE_OPENING_SNAPSHOT"
                for row in rows
            ),
            "versioned_content_baseline_candidate_count": sum(
                row["authoring_readiness"]["approval_track"]
                == "VERSIONED_CONTENT_BASELINE"
                for row in rows
            ),
            "readiness_counts": dict(sorted(readiness_counts.items())),
            "materialized_artifact_count": sum(
                row["state"]["lifecycle_status"] != "PLANNED"
                for row in rows
            ),
            "planned_artifact_count": lifecycle_counts["PLANNED"],
            "category_counts": dict(sorted(category_counts.items())),
            "artifact_form_counts": dict(sorted(form_counts.items())),
            "lifecycle_status_counts": dict(
                sorted(lifecycle_counts.items())
            ),
            "activation_result_counts": dict(
                sorted(activation_counts.items())
            ),
            "direct_trace_linked_artifact_count": sum(
                row["trace"]["direct_link_applicability"]
                == "DIRECT_LINKS_INDEXED"
                for row in rows
            ),
            "direct_trace_pending_case_artifact_count": sum(
                row["trace"]["direct_link_applicability"]
                == "APPLICABLE_NO_CURRENT_PLANNED_TEST_CASES"
                for row in rows
            ),
            "approved_artifact_count": (
                lifecycle_counts["APPROVED_BASELINED"]
                + lifecycle_counts["ACTIVE"]
            ),
            "versioned_baselined_count": lifecycle_counts[
                "APPROVED_BASELINED"
            ],
            "active_artifact_count": lifecycle_counts["ACTIVE"],
            "draft_pending_count": lifecycle_counts["DRAFT"],
            "planned_not_run_count": lifecycle_counts["PLANNED"],
            "not_approved_count": (
                lifecycle_counts["DRAFT"]
                + lifecycle_counts["PLANNED"]
            ),
        }
    )


def _ready25_artifact_register(
    outputs: dict[Path, bytes],
    change_log_bytes: bytes | None = None,
) -> dict[str, Any]:
    register = load_strict_json(ARTIFACT_REGISTER_PATH)
    predecessor_digest = register.pop("content_sha256", None)
    predecessor_recomputed = _object_sha(register)
    if predecessor_digest != predecessor_recomputed:
        _require(
            predecessor_digest
            == "cbbd0225432e559ba09cef1c9c3fec1b7777268e7ad00dbde1e94a8111f52ceb"
            and predecessor_recomputed
            == "3bc5cee452ba58130145d24ca207076c24a4ee19df2d20d2674465c3ce606989"
            and _sha_file(ARTIFACT_REGISTER_PATH)
            == "875a21af9277a8caf536520a7c530df5d4504830cc323fbcc5e2620e827f44f1",
            "DOC-01 predecessor differs from the pinned stale handoff state",
        )
    predecessor_register_states = (
        _ready25_pinned_predecessor_register_states(register)
    )
    if change_log_bytes is None:
        change_log_bytes = _json_bytes(_ready25_artifact_change_log())
    change_log = json.loads(change_log_bytes)
    change_log_digest = change_log.pop("content_sha256", None)
    _require(
        change_log_digest == _object_sha(change_log),
        "DOC-05 successor self-digest differs",
    )
    _ready25_apply_scope45(register)
    activation_states = _ready25_baseline_activation_states()
    activation_baseline_binding = _ready25_predecessor_binding(
        ARTIFACT_BASELINE_CANDIDATE_PATH,
        ARTIFACT_BASELINE_CANDIDATE_SHA256,
        "Ready25 activation baseline candidate",
    )

    exact9_packet, _exact9_receipt = _ready25_ai_dev_sec_r001()
    exact9_crosswalk = {
        item["artifact_type_code"].removeprefix("DLV-"): item
        for item in exact9_packet["per_id_acceptance_content_crosswalk"]
    }
    exact9_source_bindings = {
        item["path"]: item for item in exact9_packet["source_bindings"]
    }
    exact9_rows = _ready25_ai_dev_sec_artifact_rows(register)
    _require(
        len(exact9_rows) == len(READY25_AI_DEV_SEC_EXACT9)
        and set(exact9_crosswalk) == set(READY25_AI_DEV_SEC_EXACT9),
        "Ready25 AI/DEV/SEC exact9 artifact-register rows differ",
    )
    for row in exact9_rows:
        code = row["artifact_type_code"].removeprefix("DLV-")
        crosswalk = exact9_crosswalk[code]
        predecessor_register_state = json.loads(
            json.dumps(
                predecessor_register_states[code],
                ensure_ascii=False,
            )
        )
        row["activation_result"] = activation_states[code][
            "activation_result"
        ]
        source_binding = exact9_source_bindings[crosswalk["primary_locator"]]
        blockers = [item["id"] for item in crosswalk["open_blockers"]]
        required_approver_role = crosswalk["approver_boundary"][
            "required_role"
        ]
        assigned_approver = (
            "김민호"
            if required_approver_role
            in {"제품책임자", "프로젝트책임자"}
            else None
        )
        responsibility_boundary = {
            "project_owner": "김민호",
            "service_owner": "김민호",
            "product_owner": "김민호",
            "owner_evidence_class": "USER_SELF_ASSERTED",
            "required_approver_role": required_approver_role,
            "assigned_approver": assigned_approver,
            "assigned_approver_evidence_class": (
                "USER_SELF_ASSERTED" if assigned_approver else None
            ),
            "approval_status": "NOT_PERFORMED",
            "independent_qa_reviewer": None,
            "independent_qa_status": "UNASSIGNED",
            "self_review_credit_allowed": False,
        }
        claim_boundary = {
            "accepted": False,
            "approval_count": 0,
            "execution_count": 0,
            "actual_event_count": 0,
            "formal_evidence_count": 0,
            "release_credit_count": 0,
            "actual_receipt_ids": [],
            "release_status": RELEASE_STATUS,
        }
        remediation = {
            "artifact_type_id": code,
            "catalog_artifact_id": row["artifact_type_code"],
            "applicability": "IN_SCOPE",
            "activation_state": row.get("activation_result"),
            "activation_state_scope": (
                "READY25_DETAIL_ONLY_NOT_ROOT_ACTIVATION_TRANSITION"
            ),
            "activation_state_changed_by_ready25": False,
            "activation_baseline_binding": activation_baseline_binding,
            "content_status": "CONTROLLED_CONTENT_AUTHORED_ACCEPTANCE_PENDING",
            "content_authored": True,
            "content_accepted": False,
            "completion_mode": crosswalk["completion_mode"],
            "primary_locator": crosswalk["primary_locator"],
            "coverage_anchor": crosswalk["coverage_anchor"],
            "canonical_source_binding": source_binding,
            "required_content_crosswalk": crosswalk[
                "required_content_crosswalk"
            ],
            "blockers": crosswalk["open_blockers"],
            "owner_attribution": crosswalk["owner_attribution"],
            "responsibility_boundary": responsibility_boundary,
            "claim_boundary": claim_boundary,
            "predecessor_register_state": predecessor_register_state,
            "fabricated_result_prohibited": True,
        }
        row["applicability"] = "IN_SCOPE"
        readiness = row.setdefault("authoring_readiness", {})
        readiness["readiness"] = "INTERNAL_CONTROLLED_CONTENT_AUTHORED_ACCEPTANCE_PENDING"
        readiness["reason"] = (
            "Ready25 exact9 packet에서 요구 내용과 명시적 blocker를 정본 locator에 "
            "결속했다. 독립 QA·지정 승인·실행·formal evidence·release credit은 없다."
        )
        readiness["content_authored"] = True
        readiness["approval_proposed"] = False
        readiness["approval_track"] = "NOT_PROPOSED"
        _ready25_mark_current_revision_pending(
            row,
            remediation["content_status"],
        )
        pending = readiness.get("pending_completion_contract")
        if not isinstance(pending, dict):
            pending = {}
            readiness["pending_completion_contract"] = pending
        pending["ready25_completion_mode"] = crosswalk["completion_mode"]
        pending["ready25_required_content_crosswalk"] = crosswalk[
            "required_content_crosswalk"
        ]
        pending["ready25_blockers"] = crosswalk["open_blockers"]
        pending["fabricated_result_prohibited"] = True
        row.setdefault("state", {})["blockers"] = blockers
        responsibility = row.setdefault("responsibility", {})
        responsibility["assigned_reviewers"] = []
        unassigned = set(
            responsibility.get("unassigned_required_reviewer_roles", [])
        )
        unassigned.update(responsibility.get("reviewer_roles", []))
        responsibility["unassigned_required_reviewer_roles"] = sorted(
            unassigned
        )
        responsibility["assigned_approver"] = assigned_approver
        unassigned_approvers = set(
            responsibility.get("unassigned_required_approver_roles", [])
        )
        if assigned_approver is None:
            unassigned_approvers.add(required_approver_role)
        else:
            unassigned_approvers.discard(required_approver_role)
        responsibility["unassigned_required_approver_roles"] = sorted(
            unassigned_approvers
        )
        responsibility["ready25_ai_dev_sec_role_boundary"] = (
            responsibility_boundary
        )
        trace = row.setdefault("trace", {})
        supporting = list(trace.get("supporting_artifact_paths", []))
        if _rel(READY25_AI_DEV_SEC_R002_EVIDENCE_PATH) not in supporting:
            supporting.append(_rel(READY25_AI_DEV_SEC_R002_EVIDENCE_PATH))
        trace["supporting_artifact_paths"] = supporting
        row.setdefault("dates", {})["updated_at"] = READY25_AS_OF
        integrity = row.setdefault("integrity", {})
        integrity["pre_ready25_register_sha256"] = (
            predecessor_register_state["integrity"]["sha256"]
        )
        integrity["sha256"] = source_binding["sha256"]
        integrity["sha256_scope"] = (
            "CURRENT_PHYSICAL_CANONICAL_BYTES_NOT_APPROVAL_CREDIT"
        )
        integrity["last_verified_at"] = READY25_AS_OF
        row["ready25_ai_dev_sec_remediation"] = remediation
    register["ready25_ai_dev_sec_remediation"] = {
        "packet_id": READY25_AI_DEV_SEC_R002_PACKET_ID,
        "packet_path": _rel(READY25_AI_DEV_SEC_R002_EVIDENCE_PATH),
        "as_of": READY25_AS_OF,
        "artifact_type_ids": list(READY25_AI_DEV_SEC_EXACT9),
        "applicability": "IN_SCOPE",
        "content_authored_count": len(READY25_AI_DEV_SEC_EXACT9),
        "accepted_count": 0,
        "approval_count": 0,
        "execution_count": 0,
        "actual_event_count": 0,
        "formal_evidence_count": 0,
        "release_credit_count": 0,
        "predecessor_lineage": _ready25_ai_dev_sec_predecessor_bindings(),
    }

    rows = _ready25_artifact_rows(register)
    _require(len(rows) == len(READY25_EXACT13), "Ready25 exact13 artifact-register rows differ")
    for row in rows:
        code = row["artifact_type_code"].removeprefix("DLV-")
        contract = _ready25_contract(code)
        predecessor_register_state = json.loads(
            json.dumps(
                predecessor_register_states[code],
                ensure_ascii=False,
            )
        )
        contract["predecessor_register_state"] = predecessor_register_state
        contract["activation_state_scope"] = (
            "READY25_DETAIL_ONLY_NOT_ROOT_ACTIVATION_TRANSITION"
        )
        contract["activation_state_changed_by_ready25"] = False
        contract["activation_baseline_binding"] = (
            activation_baseline_binding
        )
        row["applicability"] = "IN_SCOPE"
        row["activation_result"] = activation_states[code][
            "activation_result"
        ]
        readiness = row.setdefault("authoring_readiness", {})
        readiness["readiness"] = "INTERNAL_PLAN_SCHEMA_CHECKLIST_AUTHORED_EVENT_PENDING"
        readiness["reason"] = (
            "Ready25에서 저장소 내부 plan·schema·checklist를 작성했다. 실제 trigger, "
            "measurement, execution, acceptance, approval 및 receipt는 발생하지 않았다."
        )
        readiness["content_authored"] = True
        readiness["approval_proposed"] = False
        readiness["approval_track"] = "NOT_PROPOSED"
        _ready25_mark_current_revision_pending(
            row,
            contract["content_status"],
        )
        pending = readiness.setdefault("pending_completion_contract", {})
        pending["ready25_internal_checklist"] = contract["internal_checklist"]
        pending["ready25_blockers"] = contract["blockers"]
        pending["fabricated_result_prohibited"] = True
        state = row.setdefault("state", {})
        state["blockers"] = [item["blocker_id"] for item in contract["blockers"]]
        responsibility = row.setdefault("responsibility", {})
        responsibility["assigned_author"] = "김민호"
        responsibility["assigned_approver"] = "김민호"
        responsibility["ready25_role_boundary"] = contract["responsibility_boundary"]
        responsibility["assigned_reviewers"] = []
        unassigned = set(
            responsibility.get(
                "unassigned_required_reviewer_roles",
                [],
            )
        )
        unassigned.update(responsibility.get("reviewer_roles", []))
        responsibility["unassigned_required_reviewer_roles"] = sorted(
            unassigned
        )
        trace = row.setdefault("trace", {})
        supporting = list(trace.get("supporting_artifact_paths", []))
        if _rel(READY25_CLS_OPS_R003_EVIDENCE_PATH) not in supporting:
            supporting.append(_rel(READY25_CLS_OPS_R003_EVIDENCE_PATH))
        trace["supporting_artifact_paths"] = supporting
        dates = row.setdefault("dates", {})
        dates["updated_at"] = READY25_AS_OF
        dates["approved_at"] = None
        integrity = row.setdefault("integrity", {})
        integrity["generator"] = _rel(GENERATOR_PATH)
        integrity["generator_sha256"] = _sha_file(GENERATOR_PATH)
        integrity["pre_ready25_register_sha256"] = (
            predecessor_register_state["integrity"]["sha256"]
        )
        current_physical_sha256 = None
        canonical = row.get("location", {}).get("canonical_path")
        if canonical:
            canonical_path = REPO_ROOT / canonical
            if canonical_path in outputs:
                current_physical_sha256 = _sha_bytes(
                    outputs[canonical_path]
                )
                integrity["sha256"] = current_physical_sha256
                integrity["last_verified_at"] = READY25_AS_OF
        integrity["sha256_scope"] = (
            "CURRENT_PHYSICAL_CANONICAL_BYTES_NOT_APPROVAL_CREDIT"
            if current_physical_sha256
            else "NO_DIRECT_CANONICAL_PATH_PACKET_BINDING_ONLY"
        )
        controls = row.setdefault("record_controls", {})
        controls["notes"] = (
            "Ready25 plan/schema/checklist content는 작성됐으나 actual event, acceptance, "
            "approval, formal evidence와 release credit은 모두 0이다."
        )
        row["ready25_remediation"] = contract
    register["ready25_cls_ops_remediation"] = {
        "packet_id": READY25_CLS_OPS_R003_PACKET_ID,
        "packet_path": _rel(READY25_CLS_OPS_R003_EVIDENCE_PATH),
        "as_of": READY25_AS_OF,
        "artifact_type_ids": list(READY25_EXACT13),
        "applicability": "IN_SCOPE",
        "content_authored_count": len(READY25_EXACT13),
        "accepted_count": 0,
        "approval_count": 0,
        "execution_count": 0,
        "actual_event_count": 0,
        "formal_evidence_count": 0,
        "release_credit_count": 0,
        "source_bindings": _ready25_source_bindings(),
        "predecessor_lineage": _ready25_cls_ops_predecessor_bindings(),
    }
    rel_rows = _ready25_rel_artifact_rows(register)
    _require(len(rel_rows) == len(READY25_REL_EXACT3), "Ready25 REL artifact-register rows differ")
    for row in rel_rows:
        code = row["artifact_type_code"].removeprefix("DLV-")
        contract = _ready25_rel_contract(code)
        predecessor_register_state = json.loads(
            json.dumps(
                predecessor_register_states[code],
                ensure_ascii=False,
            )
        )
        contract["predecessor_register_state"] = predecessor_register_state
        contract["activation_state_scope"] = (
            "READY25_DETAIL_ONLY_NOT_ROOT_ACTIVATION_TRANSITION"
        )
        contract["activation_state_changed_by_ready25"] = False
        contract["activation_baseline_binding"] = (
            activation_baseline_binding
        )
        row["applicability"] = "IN_SCOPE"
        row["activation_result"] = activation_states[code][
            "activation_result"
        ]
        readiness = row.setdefault("authoring_readiness", {})
        readiness["readiness"] = "INTERNAL_PLAN_SCHEMA_CHECKLIST_AUTHORED_CANDIDATE_PENDING"
        readiness["reason"] = (
            "Ready25에서 release strategy·environment·promotion·rollback과 required "
            "content checklist를 작성했다. named candidate와 실제 approval, execution, "
            "event, formal evidence 및 release credit은 없다."
        )
        readiness["content_authored"] = True
        readiness["approval_proposed"] = False
        readiness["approval_track"] = "NOT_PROPOSED"
        _ready25_mark_current_revision_pending(
            row,
            contract["content_status"],
        )
        pending = readiness.setdefault("pending_completion_contract", {})
        pending["ready25_internal_checklist"] = contract["internal_checklist"]
        pending["ready25_blockers"] = contract["blockers"]
        pending["five_gate_boundary"] = contract["five_gate_boundary"]
        pending["fabricated_result_prohibited"] = True
        row.setdefault("state", {})["blockers"] = [
            item["blocker_id"] for item in contract["blockers"]
        ]
        responsibility = row.setdefault("responsibility", {})
        responsibility["assigned_author"] = "김민호"
        responsibility["assigned_approver"] = "김민호"
        responsibility["assigned_reviewers"] = []
        unassigned = set(responsibility.get("unassigned_required_reviewer_roles", []))
        unassigned.update(responsibility.get("reviewer_roles", []))
        responsibility["unassigned_required_reviewer_roles"] = sorted(unassigned)
        responsibility["ready25_role_boundary"] = contract[
            "responsibility_boundary"
        ]
        trace = row.setdefault("trace", {})
        supporting = list(trace.get("supporting_artifact_paths", []))
        if _rel(READY25_REL_R002_EVIDENCE_PATH) not in supporting:
            supporting.append(_rel(READY25_REL_R002_EVIDENCE_PATH))
        trace["supporting_artifact_paths"] = supporting
        row.setdefault("dates", {})["updated_at"] = READY25_AS_OF
        row["dates"]["approved_at"] = None
        integrity = row.setdefault("integrity", {})
        integrity["generator"] = _rel(GENERATOR_PATH)
        integrity["generator_sha256"] = _sha_file(GENERATOR_PATH)
        integrity["pre_ready25_register_sha256"] = (
            predecessor_register_state["integrity"]["sha256"]
        )
        current_physical_sha256 = None
        canonical = row.get("location", {}).get("canonical_path")
        if canonical:
            canonical_path = REPO_ROOT / canonical
            if canonical_path in outputs:
                current_physical_sha256 = _sha_bytes(
                    outputs[canonical_path]
                )
                integrity["sha256"] = current_physical_sha256
                integrity["last_verified_at"] = READY25_AS_OF
        integrity["sha256_scope"] = (
            "CURRENT_PHYSICAL_CANONICAL_BYTES_NOT_APPROVAL_CREDIT"
            if current_physical_sha256
            else "NO_DIRECT_CANONICAL_PATH_PACKET_BINDING_ONLY"
        )
        row.setdefault("record_controls", {})["notes"] = (
            "Ready25 REL plan/schema/checklist만 작성됐다. named candidate, approval, "
            "execution, event, formal evidence와 release credit은 모두 0이다."
        )
        row["ready25_rel_remediation"] = contract
    register["ready25_rel_remediation"] = {
        "packet_id": READY25_REL_R002_PACKET_ID,
        "packet_path": _rel(READY25_REL_R002_EVIDENCE_PATH),
        "as_of": READY25_AS_OF,
        "artifact_type_ids": list(READY25_REL_EXACT3),
        "applicability": "IN_SCOPE",
        "content_authored_count": len(READY25_REL_EXACT3),
        "accepted_count": 0,
        "approval_count": 0,
        "execution_count": 0,
        "actual_event_count": 0,
        "formal_evidence_count": 0,
        "release_credit_count": 0,
        "source_bindings": _ready25_rel_source_bindings(),
        "predecessor_lineage": _ready25_rel_predecessor_bindings(),
    }
    doc05_rows = [
        row
        for row in register.get("artifacts", [])
        if row.get("artifact_type_code") == "DLV-DOC-05"
    ]
    doc01_rows = [
        row
        for row in register.get("artifacts", [])
        if row.get("artifact_type_code") == "DLV-DOC-01"
    ]
    _require(
        len(doc05_rows) == len(doc01_rows) == 1,
        "DOC-01/DOC-05 register row cardinality differs",
    )
    doc05_integrity = doc05_rows[0].setdefault("integrity", {})
    doc05_integrity["sha256"] = _sha_bytes(change_log_bytes)
    doc05_integrity["generator"] = _rel(GENERATOR_PATH)
    doc05_integrity["generator_sha256"] = _sha_file(GENERATOR_PATH)
    doc05_integrity["last_verified_at"] = READY25_AS_OF
    doc05_rows[0].setdefault("version", {})["document_version"] = "1.0.2"
    doc05_rows[0]["version"]["current_active_revision"] = "1.0.2"
    doc05_rows[0].setdefault("dates", {})["updated_at"] = READY25_AS_OF
    doc01_integrity = doc01_rows[0].setdefault("integrity", {})
    doc01_integrity["generator"] = _rel(GENERATOR_PATH)
    doc01_integrity["generator_sha256"] = _sha_file(GENERATOR_PATH)
    doc01_integrity["last_verified_at"] = READY25_AS_OF
    doc01_rows[0].setdefault("version", {})["document_version"] = "1.0.2"
    doc01_rows[0]["version"]["current_active_revision"] = "1.0.2"
    doc01_rows[0].setdefault("dates", {})["updated_at"] = READY25_AS_OF
    metadata = register.setdefault("metadata", {})
    metadata["document_version"] = "1.0.2"
    metadata["as_of"] = READY25_AS_OF
    metadata["updated_at"] = READY25_AS_OF
    metadata["current_revision_authority"] = (
        "ARTIFACT_REGISTER_JSON_SCOPE45_AND_READY25_CURRENT_FIELDS"
    )
    metadata["current_status_notice"] = _binding(
        ARTIFACT_REGISTER_CURRENT_NOTICE_PATH
    )
    metadata["legacy_human_projection"] = {
        "path": _rel(
            DELIVERABLES_DIR
            / "00-control"
            / "artifact-register.html"
        ),
        "scope": "PRE_READY25_APPROVAL_SNAPSHOT_ONLY",
        "ready25_current_revision_rendered": False,
        "use_for_ready25_current_status": False,
        "navigation_warning": _binding(
            ARTIFACT_REGISTER_README_PATH
        ),
    }
    bindings = {
        item.get("name"): item for item in register.get("source_bindings", [])
    }
    _require(
        "current_doc05" in bindings and "generator" in bindings,
        "DOC-01 current_doc05/historical generator source bindings are missing",
    )
    bindings["current_doc05"]["sha256"] = _sha_bytes(change_log_bytes)
    current_generator = {
        "name": "current_revision_generator",
        "path": _rel(GENERATOR_PATH),
        "sha256": _sha_file(GENERATOR_PATH),
    }
    if "current_revision_generator" in bindings:
        bindings["current_revision_generator"].update(current_generator)
    else:
        register.setdefault("source_bindings", []).append(
            current_generator
        )
    for name, path in (
        (
            "scope45_transition_application",
            SCOPE45_TRANSITION_APPLICATION_PATH,
        ),
        (
            "scope45_transition_check_receipt",
            SCOPE45_TRANSITION_RECEIPT_PATH,
        ),
        (
            "artifact_register_current_status_notice",
            ARTIFACT_REGISTER_CURRENT_NOTICE_PATH,
        ),
        (
            "artifact_register_navigation_warning",
            ARTIFACT_REGISTER_README_PATH,
        ),
    ):
        current_scope_binding = {
            "name": name,
            "path": _rel(path),
            "sha256": _sha_file(path),
        }
        if name in bindings:
            bindings[name].update(current_scope_binding)
        else:
            register.setdefault("source_bindings", []).append(
                current_scope_binding
            )
    _ready25_refresh_register_summary(register)
    register["content_sha256"] = _object_sha(register)
    return register


def _ready25_output_binding(path: Path, content: bytes) -> dict[str, Any]:
    return {
        "path": _rel(path),
        "sha256": _sha_bytes(content),
        "byte_length": len(content),
    }


def _ready25_nonself_seal(value: dict[str, Any]) -> dict[str, Any]:
    value.pop("nonself_integrity", None)
    projection = (
        json.dumps(
            value,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")
    value["nonself_integrity"] = {
        "algorithm": "SHA-256",
        "canonicalization": (
            "UTF-8 JSON, recursively sorted keys, compact separators, "
            "one terminal LF"
        ),
        "projection_bytes": len(projection),
        "scope": "ENTIRE_DOCUMENT_EXCLUDING_TOP_LEVEL_NONSELF_INTEGRITY",
        "sha256": _sha_bytes(projection),
    }
    return value


def _ready25_ai_dev_sec_evidence(
    outputs: dict[Path, bytes],
) -> dict[str, Any]:
    predecessor, _receipt = _ready25_ai_dev_sec_r001()
    evidence = json.loads(json.dumps(predecessor, ensure_ascii=False))
    evidence["schema_version"] = (
        "walksafe.phase1-ready25-ai-dev-sec.evidence.v2"
    )
    evidence["metadata"]["packet_id"] = READY25_AI_DEV_SEC_R002_PACKET_ID
    evidence["claim_boundary"]["artifact_register_modified"] = True
    evidence["predecessor_lineage"] = (
        _ready25_ai_dev_sec_predecessor_bindings()
    )
    evidence["artifact_register_application"] = {
        "status": "APPLIED_TO_CURRENT_DOC01",
        "artifact_register": _ready25_output_binding(
            ARTIFACT_REGISTER_PATH,
            outputs[ARTIFACT_REGISTER_PATH],
        ),
        "artifact_change_log": _ready25_output_binding(
            ARTIFACT_CHANGE_LOG_PATH,
            outputs[ARTIFACT_CHANGE_LOG_PATH],
        ),
        "exact_row_count": len(READY25_AI_DEV_SEC_EXACT9),
        "content_authored_count": len(READY25_AI_DEV_SEC_EXACT9),
        "content_accepted_count": 0,
        "owner_approval_count": 0,
        "execution_credit_count": 0,
        "actual_event_credit_count": 0,
        "formal_test_credit_count": 0,
        "release_credit_count": 0,
    }
    source_paths = {
        item["path"] for item in evidence.get("source_bindings", [])
    }
    for path in (ARTIFACT_CHANGE_LOG_PATH, ARTIFACT_REGISTER_PATH):
        if _rel(path) not in source_paths:
            evidence["source_bindings"].append(
                _ready25_output_binding(path, outputs[path])
            )
    return _ready25_nonself_seal(evidence)


def _ready25_ai_dev_sec_receipt(
    evidence_bytes: bytes,
    outputs: dict[Path, bytes],
) -> dict[str, Any]:
    _evidence, predecessor = _ready25_ai_dev_sec_r001()
    receipt = json.loads(json.dumps(predecessor, ensure_ascii=False))
    receipt["schema_version"] = (
        "walksafe.phase1-ready25-ai-dev-sec-check-receipt.v2"
    )
    receipt["metadata"]["receipt_id"] = (
        READY25_AI_DEV_SEC_R002_RECEIPT_ID
    )
    receipt["claim_boundary"]["artifact_register_modified"] = True
    subject = _ready25_output_binding(
        READY25_AI_DEV_SEC_R002_EVIDENCE_PATH,
        evidence_bytes,
    )
    receipt["subject"] = subject
    receipt["predecessor_lineage"] = (
        _ready25_ai_dev_sec_predecessor_bindings()
    )
    output_bindings = [
        item
        for item in receipt.get("output_bindings", [])
        if item.get("path") != _rel(READY25_AI_DEV_SEC_R001_EVIDENCE_PATH)
    ]
    output_bindings.extend(
        [
            _ready25_output_binding(
                ARTIFACT_CHANGE_LOG_PATH,
                outputs[ARTIFACT_CHANGE_LOG_PATH],
            ),
            _ready25_output_binding(
                ARTIFACT_REGISTER_PATH,
                outputs[ARTIFACT_REGISTER_PATH],
            ),
            subject,
        ]
    )
    receipt["output_bindings"] = output_bindings
    for check in receipt["checks"]:
        if check["check_id"] == "READY25-010":
            check["name"] = (
                "ZERO_EXECUTION_FORMAL_RELEASE_AND_ARTIFACT_REGISTER_INTEGRATED"
            )
    receipt["checks"].append(
        {
            "check_id": "READY25-011",
            "name": "DOC01_DOC05_SELF_DIGEST_AND_EXACT9_APPLICATION",
            "result": "PASS",
        }
    )
    receipt["summary"]["pass_count"] = len(receipt["checks"])
    receipt["summary"]["fail_count"] = 0
    receipt["metadata"]["verdict"] = (
        "PASS_FOR_CONTENT_AUTHORING_AND_REGISTER_APPLICATION_BOUNDARY_ONLY"
    )
    return _ready25_nonself_seal(receipt)


def _ready25_evidence(
    outputs: dict[Path, bytes],
    catalog: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    bound_paths = {
        ARTIFACT_CHANGE_LOG_PATH,
        ARTIFACT_REGISTER_PATH,
        MANIFEST_PATH,
        OPS_REGISTER_PATH,
        CLS_REGISTER_PATH,
        *[
            path
            for path, codes in DOCUMENT_COVERAGE.items()
            if set(codes) & set(READY25_EXACT13)
        ],
    }
    contracts = []
    for code in READY25_EXACT13:
        contract = _ready25_contract(code)
        contract["acceptance_contract"] = {
            "required_contents": catalog[code]["required_contents"],
            "completion_criteria": catalog[code]["completion_criteria"],
        }
        contracts.append(contract)
    evidence = {
        "schema_version": "walksafe.phase1-ready25-cls-ops-evidence.v1",
        "packet_id": READY25_CLS_OPS_R003_PACKET_ID,
        "prepared_on": READY25_AS_OF,
        "predecessor_lineage": _ready25_cls_ops_predecessor_bindings(),
        "scope_decision": {
            "decision": "IN_SCOPE",
            "artifact_type_ids": list(READY25_EXACT13),
            "user_scope_receipt": _ready25_source_bindings()[
                "user_scope_transition_receipt"
            ],
        },
        "r010_chain_binding": {
            "semantics": "ADD_ONLY_READY25_SUCCESSOR_OVER_R010_WRAPPER_AND_R007_LEDGER_SUBJECT",
            "r010_evidence": _ready25_source_bindings()["r010_successor_evidence"],
            "r010_check_receipt": _ready25_source_bindings()[
                "r010_successor_check_receipt"
            ],
        },
        "responsibility_boundary": {
            "project_owner": "김민호",
            "service_owner": "김민호",
            "product_owner": "김민호",
            "independent_qa_reviewer": None,
            "independent_qa_status": "UNASSIGNED",
            "self_review_credit_allowed": False,
        },
        "artifact_contracts": contracts,
        "generated_output_bindings": [
            _ready25_output_binding(path, outputs[path])
            for path in sorted(bound_paths, key=_rel)
        ],
        "summary": {
            "exact_artifact_count": len(READY25_EXACT13),
            "in_scope_count": len(READY25_EXACT13),
            "content_authored_count": len(READY25_EXACT13),
            "accepted_count": 0,
            "approval_count": 0,
            "execution_count": 0,
            "actual_event_count": 0,
            "formal_evidence_count": 0,
            "release_credit_count": 0,
        },
        "claim_boundary": {
            "actual_kpi_result_count": 0,
            "risk_acceptance_count": 0,
            "actual_handover_count": 0,
            "actual_data_transfer_or_deletion_count": 0,
            "actual_rotation_or_revocation_count": 0,
            "shutdown_or_decommission_count": 0,
            "live_dashboard_count": 0,
            "actual_restore_test_count": 0,
            "actual_dr_drill_count": 0,
            "qualifying_incident_count": 0,
            "postmortem_count": 0,
            "actual_cost_result_count": 0,
            "release_status": RELEASE_STATUS,
        },
    }
    evidence["integrity"] = {
        "algorithm": "SHA-256",
        "content_sha256": _object_sha(evidence),
    }
    return evidence


def _ready25_receipt(evidence_bytes: bytes) -> dict[str, Any]:
    receipt = {
        "schema_version": "walksafe.phase1-ready25-cls-ops-check-receipt.v1",
        "receipt_id": READY25_CLS_OPS_R003_RECEIPT_ID,
        "prepared_on": READY25_AS_OF,
        "status": "PASS",
        "evidence_binding": _ready25_output_binding(
            READY25_CLS_OPS_R003_EVIDENCE_PATH, evidence_bytes
        ),
        "source_bindings": _ready25_source_bindings(),
        "checks": [
            {
                "check_id": "READY25-EXACT13-IN-SCOPE",
                "status": "PASS",
                "expected": len(READY25_EXACT13),
                "observed": len(READY25_EXACT13),
            },
            {
                "check_id": "READY25-EXACT13-CONTENT-AUTHORED",
                "status": "PASS",
                "expected": len(READY25_EXACT13),
                "observed": len(READY25_EXACT13),
            },
            {
                "check_id": "READY25-NO-ACCEPTANCE-APPROVAL-EXECUTION-EVENT-FORMAL-RELEASE-CREDIT",
                "status": "PASS",
                "expected": [0, 0, 0, 0, 0, 0],
                "observed": [0, 0, 0, 0, 0, 0],
            },
            {
                "check_id": "READY25-OPS18-INCIDENT0-NOT-TRIGGERED",
                "status": "PASS",
                "expected": "NOT_TRIGGERED_INCIDENT_COUNT_0",
                "observed": READY25_ACTIVATION_STATES["OPS-18"],
            },
            {
                "check_id": "READY25-CLS16-OPERATIONS-CONTINUE",
                "status": "PASS",
                "expected": "OPERATIONS_CONTINUE",
                "observed": _ready25_contract("CLS-16")[
                    "service_lifecycle_boundary"
                ]["current_branch"],
            },
            {
                "check_id": "READY25-CLS10-RECIPIENT-UNRESOLVED",
                "status": "PASS",
                "expected": None,
                "observed": _ready25_contract("CLS-10")["handover_boundary"][
                    "recipient"
                ],
            },
            {
                "check_id": "READY25-INDEPENDENT-QA-UNASSIGNED",
                "status": "PASS",
                "expected": None,
                "observed": _ready25_contract("CLS-04")[
                    "responsibility_boundary"
                ]["independent_qa_reviewer"],
            },
        ],
    }
    receipt["integrity"] = {
        "algorithm": "SHA-256",
        "content_sha256": _object_sha(receipt),
    }
    return receipt


def _ready25_rel_evidence(
    outputs: dict[Path, bytes],
    catalog: dict[str, dict[str, Any]],
) -> dict[str, Any]:
    contracts = []
    r007_contracts = {
        item["artifact_type_id"]: item
        for item in _ready25_rel_r007_contracts()
    }
    for code in READY25_REL_EXACT3:
        contract = _ready25_rel_contract(code)
        contract["catalog_acceptance_contract"] = {
            "required_contents": catalog[code]["required_contents"],
            "completion_criteria": catalog[code]["completion_criteria"],
        }
        contract["r007_subject_and_acceptance_contract"] = r007_contracts[code]
        contracts.append(contract)
    bound_paths = {
        ARTIFACT_CHANGE_LOG_PATH,
        ARTIFACT_REGISTER_PATH,
        MANIFEST_PATH,
        REL_CONTROL_PATH,
        REL_REGISTER_PATH,
    }
    evidence = {
        "schema_version": "walksafe.phase1-ready25-rel-evidence.v1",
        "packet_id": READY25_REL_R002_PACKET_ID,
        "prepared_on": READY25_AS_OF,
        "predecessor_lineage": _ready25_rel_predecessor_bindings(),
        "scope_decision": {
            "decision": "IN_SCOPE",
            "artifact_type_ids": list(READY25_REL_EXACT3),
            "user_scope_receipt": _ready25_source_bindings()[
                "user_scope_transition_receipt"
            ],
        },
        "r007_subject_contracts": list(r007_contracts.values()),
        "r010_chain_binding": {
            "semantics": "ADD_ONLY_READY25_REL_SUCCESSOR_OVER_R010_WRAPPER_AND_R007_LEDGER_SUBJECT",
            "r010_evidence": _ready25_source_bindings()["r010_successor_evidence"],
            "r010_check_receipt": _ready25_source_bindings()[
                "r010_successor_check_receipt"
            ],
        },
        "artifact_contracts": contracts,
        "generated_output_bindings": [
            _ready25_output_binding(path, outputs[path])
            for path in sorted(bound_paths, key=_rel)
        ],
        "five_gate_boundary": [
            {"gate_id": gate_id, "status": "NOT_RUN", "waived": False}
            for gate_id in GATE_IDS
        ],
        "summary": {
            "exact_artifact_count": len(READY25_REL_EXACT3),
            "in_scope_count": len(READY25_REL_EXACT3),
            "content_authored_count": len(READY25_REL_EXACT3),
            "accepted_count": 0,
            "approval_count": 0,
            "execution_count": 0,
            "actual_event_count": 0,
            "formal_evidence_count": 0,
            "release_credit_count": 0,
        },
        "claim_boundary": {
            "named_release_candidate_count": 0,
            "release_commit_or_tag_count": 0,
            "release_note_result_count": 0,
            "deployment_count": 0,
            "promotion_count": 0,
            "rollback_execution_count": 0,
            "release_status": RELEASE_STATUS,
        },
    }
    evidence["integrity"] = {
        "algorithm": "SHA-256",
        "content_sha256": _object_sha(evidence),
    }
    return evidence


def _ready25_rel_receipt(evidence_bytes: bytes) -> dict[str, Any]:
    receipt = {
        "schema_version": "walksafe.phase1-ready25-rel-check-receipt.v1",
        "receipt_id": READY25_REL_R002_RECEIPT_ID,
        "prepared_on": READY25_AS_OF,
        "status": "PASS",
        "evidence_binding": _ready25_output_binding(
            READY25_REL_R002_EVIDENCE_PATH, evidence_bytes
        ),
        "source_bindings": _ready25_rel_source_bindings(),
        "checks": [
            {
                "check_id": "READY25-REL-EXACT3-IN-SCOPE-CONTENT-AUTHORED",
                "status": "PASS",
                "expected": [3, 3],
                "observed": [3, 3],
            },
            {
                "check_id": "READY25-REL-NO-CREDIT",
                "status": "PASS",
                "expected": [0, 0, 0, 0, 0, 0],
                "observed": [0, 0, 0, 0, 0, 0],
            },
            {
                "check_id": "READY25-REL-FIVE-GATES-NOT-RUN-NOT-WAIVED",
                "status": "PASS",
                "expected": 5,
                "observed": 5,
            },
            {
                "check_id": "READY25-REL-NAMED-CANDIDATE-ABSENT",
                "status": "PASS",
                "expected": None,
                "observed": None,
            },
        ],
    }
    receipt["integrity"] = {
        "algorithm": "SHA-256",
        "content_sha256": _object_sha(receipt),
    }
    return receipt


def _ready25_side_outputs(outputs: dict[Path, bytes]) -> dict[Path, bytes]:
    catalog = _catalog_by_code(load_strict_json(ARTIFACT_CATALOG_PATH))
    change_log_bytes = _json_bytes(_ready25_artifact_change_log())
    side_outputs = {ARTIFACT_CHANGE_LOG_PATH: change_log_bytes}
    side_outputs[ARTIFACT_REGISTER_PATH] = _json_bytes(
        _ready25_artifact_register(outputs, change_log_bytes)
    )
    exact9_evidence = _ready25_ai_dev_sec_evidence(side_outputs | outputs)
    side_outputs[READY25_AI_DEV_SEC_R002_EVIDENCE_PATH] = _json_bytes(
        exact9_evidence
    )
    side_outputs[READY25_AI_DEV_SEC_R002_RECEIPT_PATH] = _json_bytes(
        _ready25_ai_dev_sec_receipt(
            side_outputs[READY25_AI_DEV_SEC_R002_EVIDENCE_PATH],
            side_outputs | outputs,
        )
    )
    evidence = _ready25_evidence(side_outputs | outputs, catalog)
    side_outputs[READY25_CLS_OPS_R003_EVIDENCE_PATH] = _json_bytes(evidence)
    side_outputs[READY25_CLS_OPS_R003_RECEIPT_PATH] = _json_bytes(
        _ready25_receipt(side_outputs[READY25_CLS_OPS_R003_EVIDENCE_PATH])
    )
    rel_evidence = _ready25_rel_evidence(side_outputs | outputs, catalog)
    side_outputs[READY25_REL_R002_EVIDENCE_PATH] = _json_bytes(rel_evidence)
    side_outputs[READY25_REL_R002_RECEIPT_PATH] = _json_bytes(
        _ready25_rel_receipt(side_outputs[READY25_REL_R002_EVIDENCE_PATH])
    )
    return side_outputs


def _build_outputs() -> dict[Path, bytes]:
    policy, catalog_raw, _aligned, project_facts = _validate_inputs()
    catalog = _catalog_by_code(catalog_raw)
    titles = {
        REL_CONTROL_PATH: "WalkSafe 릴리스 통제",
        REL_DEPLOYMENT_PATH: "WalkSafe 배포·복귀 절차와 실행 경계",
        REL_DELIVERY_PATH: "WalkSafe 설치·사용·관리·인도",
        OPS_GUIDE_PATH: "WalkSafe 운영·지원 안내",
        OPS_RECOVERY_PATH: "WalkSafe backup·복원·재해복구",
        OPS_CONTROL_PATH: "WalkSafe 운영 통제와 지속 원장",
        CLS_CLOSURE_PATH: "WalkSafe 프로젝트 종료 결과 계약",
        CLS_HANDOVER_PATH: "WalkSafe 종료 인계 원장",
        CLS_DECOMMISSION_PATH: "WalkSafe 운영 이관·서비스 폐기 계획",
    }
    outputs: dict[Path, bytes] = {
        **{
            path: _md_bytes(_document(path, titles[path], catalog, policy, project_facts))
            for path in DOCUMENT_COVERAGE
        },
        REL_REGISTER_PATH: _json_bytes(_release_register(policy, project_facts)),
        REL_EVIDENCE_TEMPLATE_PATH: _json_bytes(_release_evidence_template()),
        REL_DEPLOYMENT_TEMPLATE_PATH: _json_bytes(_deployment_template()),
        REL_ACCEPTANCE_TEMPLATE_PATH: _json_bytes(_external_acceptance_template()),
        OPS_REGISTER_PATH: _json_bytes(_operations_register(policy, project_facts)),
        OPS_INCIDENT_TEMPLATE_PATH: _json_bytes(_incident_template()),
        OPS_RECOVERY_TEMPLATE_PATH: _json_bytes(_recovery_template()),
        CLS_REGISTER_PATH: _json_bytes(_closure_register()),
        CLS_EXECUTION_TEMPLATE_PATH: _json_bytes(_closure_execution_template()),
    }

    generated_files = []
    for path, content in sorted(outputs.items(), key=lambda item: _rel(item[0])):
        if path in DOCUMENT_COVERAGE:
            artifact_ids = _draft_codes_for(path)
            file_role = "CANONICAL_DRAFT_OR_PLANNED_CONTRACT"
            bundle_id = BUNDLE_BY_PATH[path]
        else:
            artifact_ids = SUPPORTING_ARTIFACT_IDS[path]
            file_role = "SUPPORTING_DRAFT_REGISTER_OR_NON_EVIDENCE_TEMPLATE"
            bundle_id = None
        generated_files.append(
            {
                "path": _rel(path),
                "sha256": _sha_bytes(content),
                "byte_length": len(content),
                "file_role": file_role,
                "bundle_id": bundle_id,
                "artifact_type_ids": artifact_ids,
            }
        )

    artifact_location_index = []
    for path, codes in DOCUMENT_COVERAGE.items():
        for code in codes:
            is_materialized = code in set(MATERIALIZED_ARTIFACT_TYPE_IDS)
            artifact_location_index.append(
                {
                    "artifact_type_id": code,
                    "bundle_id": BUNDLE_BY_PATH[path],
                    "canonical_path": _rel(path),
                    "anchor": code.lower(),
                    "materialization_status": "DRAFT" if is_materialized else "PLANNED_NOT_RUN",
                    "actual_execution_or_external_evidence_count": 0,
                }
            )

    source_bindings = _source_bindings()
    manifest = {
        "schema_version": "walksafe.formal-rel-ops-cls-draft-manifest.v1",
        "metadata": {
            **_metadata("WalkSafe REL·OPS·CLS 정식 산출물 Draft manifest", MATERIALIZED_ARTIFACT_TYPE_IDS),
            "manifest_id": "WS-FORMAL-REL-OPS-CLS-DRAFT-20260721-001",
            "controlled_revision": 1,
        },
        "scope_artifact_type_ids": SCOPE_ARTIFACT_TYPE_IDS,
        "materialized_artifact_type_ids": MATERIALIZED_ARTIFACT_TYPE_IDS,
        "planned_artifact_type_ids": PLANNED_ARTIFACT_TYPE_IDS,
        "materialization_summary": {
            "scope_count": 62,
            "bundle_count": 9,
            "materialized_draft_count": 39,
            "planned_not_run_count": 23,
            "actual_execution_evidence_count": 0,
            "external_original_count": 0,
            "closure_result_count": 0,
        },
        "source_bindings": source_bindings,
        "source_binding_sha256": _object_sha(source_bindings),
        "w9_content_assessment": {
            "as_of": W9_AS_OF,
            "basis": {
                "gap_analysis_r020": source_bindings["implementation_gap_analysis_r020"],
                "remediation_backlog_r020": source_bindings["remediation_backlog_r020"],
            },
            "ok_candidate_content_completeness": W9_OK_CANDIDATE_CONTENT_COMPLETENESS,
            "external_after_internal": W9_EXTERNAL_AFTER_INTERNAL,
            "contracts": W9_LIFECYCLE_CONTRACTS,
            "external_execution_contracts": [
                W9_EXTERNAL_EXECUTION_CONTRACTS[code]
                for code in W9_EXTERNAL_AFTER_INTERNAL
            ],
            "boundary": {
                "content_completeness_is_approval": False,
                "content_completeness_is_execution": False,
                "actual_incident_or_change_receipt_count": 0,
                "actual_handover_deletion_rotation_decommission_receipt_count": 0,
                "risk_acceptance_status": "NOT_APPROVED",
            },
        },
        "ready25_cls_ops_remediation": {
            "as_of": READY25_AS_OF,
            "packet_id": READY25_CLS_OPS_R003_PACKET_ID,
            "evidence_path": _rel(READY25_CLS_OPS_R003_EVIDENCE_PATH),
            "check_receipt_path": _rel(READY25_CLS_OPS_R003_RECEIPT_PATH),
            "predecessor_lineage": _ready25_cls_ops_predecessor_bindings(),
            "artifact_type_ids": list(READY25_EXACT13),
            "applicability": "IN_SCOPE",
            "content_authored_count": len(READY25_EXACT13),
            "accepted_count": 0,
            "approval_count": 0,
            "execution_count": 0,
            "actual_event_count": 0,
            "formal_evidence_count": 0,
            "release_credit_count": 0,
            "source_bindings": _ready25_source_bindings(),
            "responsibility_boundary": _ready25_contract("CLS-04")[
                "responsibility_boundary"
            ],
            "contracts": {
                code: _ready25_contract(code) for code in READY25_EXACT13
            },
        },
        "ready25_rel_remediation": {
            "as_of": READY25_AS_OF,
            "packet_id": READY25_REL_R002_PACKET_ID,
            "evidence_path": _rel(READY25_REL_R002_EVIDENCE_PATH),
            "check_receipt_path": _rel(READY25_REL_R002_RECEIPT_PATH),
            "predecessor_lineage": _ready25_rel_predecessor_bindings(),
            "artifact_type_ids": list(READY25_REL_EXACT3),
            "applicability": "IN_SCOPE",
            "content_authored_count": len(READY25_REL_EXACT3),
            "accepted_count": 0,
            "approval_count": 0,
            "execution_count": 0,
            "actual_event_count": 0,
            "formal_evidence_count": 0,
            "release_credit_count": 0,
            "source_bindings": _ready25_rel_source_bindings(),
            "contracts": {
                code: _ready25_rel_contract(code)
                for code in READY25_REL_EXACT3
            },
        },
        "approved_project_facts_applied": project_facts,
        "generated_files": generated_files,
        "artifact_location_index": artifact_location_index,
        "planned_evidence_contracts": [
            {
                "artifact_type_id": code,
                "title": catalog[code]["title"],
                "status": "PLANNED",
                "verification_status": "NOT_RUN",
                "reason": _planned_reason(code, catalog),
                "activation_condition": catalog[code]["activation_condition"],
                "executor_role": catalog[code]["owner_role"],
                "reviewer_roles": catalog[code]["reviewer_roles"],
                "approver_role": catalog[code]["approver_role"],
                "prerequisites": catalog[code]["required_inputs"],
                "required_evidence": catalog[code]["required_contents"],
                "judgment_criteria": catalog[code]["completion_criteria"],
                "evidence_record_rule": _execution_contract(code, catalog[code])["evidence_rule"],
                "canonical_path": next(
                    _rel(path) for path, codes in DOCUMENT_COVERAGE.items() if code in codes
                ),
                "anchor": code.lower(),
                "evidence_ids": [],
            }
            for code in PLANNED_ARTIFACT_TYPE_IDS
        ],
        "remaining_gates": _gate_rows(policy),
        "known_open_issues": [
            {
                "issue_id": FP035_ISSUE_ID,
                "status": FP035_NORMALIZATION_STATUS,
                "owner_clarification_required": False,
                "normalized_policy": FP035_NORMALIZED_POLICY,
                "normative_rule": FP035_NORMATIVE_RULE,
                "related_test_status": "NOT_RUN",
                "correction_candidate_binding": _binding(FP035_CORRECTION_CANDIDATE_PATH),
                "correction_candidate_approval_status": "NOT_APPROVED",
                "correction_candidate_effective_status": "NOT_EFFECTIVE",
                "policy_effect_claimed": False,
                "waived": False,
                "change_request_id": "REQ-CHG-20260721-002",
            }
        ],
        "platform_boundary": {
            "formal_products": ["Android 사용자 앱", "별도 비공개 Android 관리자 앱"],
            "legacy_reference_only": ["Web/PWA"],
        },
        "authorization_boundary": {
            "policy_baseline_status": "BASELINED",
            "formal_deliverable_lifecycle_status": "DRAFT_OR_PLANNED_AS_LISTED",
            "formal_deliverables_approved": False,
            "release_artifacts_created": False,
            "deployment_execution_claimed": False,
            "operations_execution_claimed": False,
            "project_completion_claimed": False,
            "risk_acceptance_approved": False,
            "handover_or_decommission_execution_claimed": False,
            "remaining_gates_waived": False,
            "release_status": RELEASE_STATUS,
        },
    }
    manifest["manifest_content_sha256"] = _object_sha(manifest)
    outputs[MANIFEST_PATH] = _json_bytes(manifest)
    return outputs


def _validate_outputs(
    outputs: dict[Path, bytes],
    ready25_side_outputs: dict[Path, bytes] | None = None,
) -> None:
    expected_paths = set(DOCUMENT_COVERAGE) | set(SUPPORTING_ARTIFACT_IDS) | {MANIFEST_PATH}
    _require(
        set(outputs) == expected_paths and len(outputs) == len(expected_paths),
        "generated path set differs",
    )
    expected_ready25_side_outputs = _ready25_side_outputs(outputs)
    if ready25_side_outputs is None:
        ready25_side_outputs = expected_ready25_side_outputs

    for path, codes in DOCUMENT_COVERAGE.items():
        text = outputs[path].decode("utf-8")
        first_lines = "\n".join(text.splitlines()[:16])
        for code in _draft_codes_for(path):
            _require(code in first_lines, f"Draft code missing from header: {path}: {code}")
        for code in _planned_codes_for(path):
            _require(code not in first_lines, f"Planned code leaked into Draft header: {path}: {code}")
        for code in codes:
            _require(f'<a id="{code.lower()}"></a>' in text, f"artifact anchor missing: {path}: {code}")
        _require(RELEASE_STATUS in text, f"release boundary missing: {path}")

    rel17_text = outputs[REL_DELIVERY_PATH].decode("utf-8")
    for required_text in (
        "RAW_SOURCE_COLLECTION",
        "AUTOMATIC_REPORTING",
        "MOBILE_NETWORK_TRANSFER",
        "TRAINING_REUSE",
        "보행 중 서버로 전송하지 않는다",
        "정지 판정",
        "Wi-Fi에서만 전송",
        "철회",
        "삭제",
        "재동의",
        "법적 보유기간",
        "최종 한국어",
        "NOT_APPROVED",
        "TMAP",
        "제3자",
        "국외이전",
        "formal 279",
        "실기기",
        "TMAP live",
        "W5",
        "열린 finding",
        "signing/deployment",
        "NOT_ASSESSED",
        "W7",
        "exact 3",
        "모델 등록 완전성",
        "NOT_RUN",
        "NOT_ELIGIBLE",
        "Web/PWA",
        "unsigned APK",
    ):
        _require(required_text in rel17_text, f"REL-17 content boundary missing: {required_text}")

    manifest = json.loads(outputs[MANIFEST_PATH])
    expected_manifest_metadata = {
        **_metadata(
            "WalkSafe REL·OPS·CLS 정식 산출물 Draft manifest",
            MATERIALIZED_ARTIFACT_TYPE_IDS,
        ),
        "manifest_id": (
            "WS-FORMAL-REL-OPS-CLS-DRAFT-20260721-001"
        ),
        "controlled_revision": 1,
    }
    _require(
        set(manifest)
        == {
            "schema_version",
            "metadata",
            "scope_artifact_type_ids",
            "materialized_artifact_type_ids",
            "planned_artifact_type_ids",
            "materialization_summary",
            "source_bindings",
            "source_binding_sha256",
            "w9_content_assessment",
            "ready25_cls_ops_remediation",
            "ready25_rel_remediation",
            "approved_project_facts_applied",
            "generated_files",
            "artifact_location_index",
            "planned_evidence_contracts",
            "remaining_gates",
            "known_open_issues",
            "platform_boundary",
            "authorization_boundary",
            "manifest_content_sha256",
        }
        and manifest["schema_version"]
        == "walksafe.formal-rel-ops-cls-draft-manifest.v1"
        and _strict_json_equal(
            manifest["metadata"],
            expected_manifest_metadata,
        ),
        "manifest top-level schema or metadata differs",
    )
    _require(
        _strict_json_equal(
            manifest["platform_boundary"],
            {
                "formal_products": [
                    "Android 사용자 앱",
                    "별도 비공개 Android 관리자 앱",
                ],
                "legacy_reference_only": ["Web/PWA"],
            },
        )
        and _strict_json_equal(
            manifest["authorization_boundary"],
            {
                "policy_baseline_status": "BASELINED",
                "formal_deliverable_lifecycle_status": (
                    "DRAFT_OR_PLANNED_AS_LISTED"
                ),
                "formal_deliverables_approved": False,
                "release_artifacts_created": False,
                "deployment_execution_claimed": False,
                "operations_execution_claimed": False,
                "project_completion_claimed": False,
                "risk_acceptance_approved": False,
                "handover_or_decommission_execution_claimed": (
                    False
                ),
                "remaining_gates_waived": False,
                "release_status": RELEASE_STATUS,
            },
        ),
        "manifest platform or authorization boundary differs",
    )
    policy_contract = load_strict_json(POLICY_PATH)
    project_fact_contract = _project_facts()
    expected_supporting_documents = {
        REL_REGISTER_PATH: _release_register(
            policy_contract,
            project_fact_contract,
        ),
        REL_EVIDENCE_TEMPLATE_PATH: (
            _release_evidence_template()
        ),
        REL_DEPLOYMENT_TEMPLATE_PATH: _deployment_template(),
        REL_ACCEPTANCE_TEMPLATE_PATH: (
            _external_acceptance_template()
        ),
        OPS_REGISTER_PATH: _operations_register(
            policy_contract,
            project_fact_contract,
        ),
        OPS_INCIDENT_TEMPLATE_PATH: _incident_template(),
        OPS_RECOVERY_TEMPLATE_PATH: _recovery_template(),
        CLS_REGISTER_PATH: _closure_register(),
        CLS_EXECUTION_TEMPLATE_PATH: (
            _closure_execution_template()
        ),
    }
    for path, expected_document in (
        expected_supporting_documents.items()
    ):
        _require(
            _strict_json_equal(
                json.loads(outputs[path]),
                expected_document,
            ),
            (
                "supporting JSON exact schema or value differs: "
                f"{_rel(path)}"
            ),
        )
    ready25_zero_contract_claim = {
        "accepted": False,
        "approval_count": 0,
        "execution_count": 0,
        "actual_event_count": 0,
        "formal_evidence_count": 0,
        "release_credit_count": 0,
        "actual_receipt_ids": [],
        "release_status": RELEASE_STATUS,
    }
    ready25_role_boundary = {
        "project_owner": "김민호",
        "service_owner": "김민호",
        "product_owner": "김민호",
        "independent_qa_reviewer": None,
        "independent_qa_status": "UNASSIGNED",
        "self_review_credit_allowed": False,
    }
    ready25_release_strategy = {
        "environment_order": [
            "DEVELOPMENT",
            "CONTROLLED_DEMO",
            "BETA",
            "PRODUCTION",
        ],
        "deployment_model": "MANAGED_CLOUD_STAGING_FIRST",
        "promotion_requires_all_five_gates": True,
        "rollback_plan_required": True,
        "named_release_candidate": None,
        "promotion_status": "NOT_RUN",
        "rollback_execution_status": "NOT_RUN",
    }
    ready25_current_revision_keys = {
        "content_status",
        "content_accepted",
        "approval_status",
        "verification_status",
        "baseline_status",
        "execution_credit_count",
        "actual_event_credit_count",
        "formal_evidence_credit_count",
        "release_credit_count",
    }
    ready25_cls_contract_keys = {
        "artifact_type_id",
        "catalog_artifact_id",
        "applicability",
        "activation_state",
        "content_status",
        "content_authored",
        "internal_checklist",
        "blockers",
        "responsibility_boundary",
        "claim_boundary",
        "fabricated_result_prohibited",
    }
    ready25_rel_contract_keys = {
        "artifact_type_id",
        "catalog_artifact_id",
        "applicability",
        "activation_state",
        "content_status",
        "content_authored",
        "internal_checklist",
        "release_strategy",
        "five_gate_boundary",
        "blockers",
        "responsibility_boundary",
        "claim_boundary",
        "fabricated_release_candidate_or_result_prohibited",
    }
    ready25 = manifest["ready25_cls_ops_remediation"]
    _require(
        set(ready25)
        == {
            "as_of",
            "packet_id",
            "evidence_path",
            "check_receipt_path",
            "predecessor_lineage",
            "artifact_type_ids",
            "applicability",
            "content_authored_count",
            "accepted_count",
            "approval_count",
            "execution_count",
            "actual_event_count",
            "formal_evidence_count",
            "release_credit_count",
            "source_bindings",
            "responsibility_boundary",
            "contracts",
        }
        and ready25["as_of"] == READY25_AS_OF
        and ready25["packet_id"]
        == READY25_CLS_OPS_R003_PACKET_ID
        and ready25["evidence_path"]
        == _rel(READY25_CLS_OPS_R003_EVIDENCE_PATH)
        and ready25["check_receipt_path"]
        == _rel(READY25_CLS_OPS_R003_RECEIPT_PATH)
        and ready25["artifact_type_ids"] == list(READY25_EXACT13)
        and ready25["applicability"] == "IN_SCOPE"
        and _strict_json_equal(
            {
                key: ready25[key]
                for key in (
                    "content_authored_count",
                    "accepted_count",
                    "approval_count",
                    "execution_count",
                    "actual_event_count",
                    "formal_evidence_count",
                    "release_credit_count",
                )
            },
            {
                "content_authored_count": len(
                    READY25_EXACT13
                ),
                "accepted_count": 0,
                "approval_count": 0,
                "execution_count": 0,
                "actual_event_count": 0,
                "formal_evidence_count": 0,
                "release_credit_count": 0,
            },
        ),
        "Ready25 manifest scope or no-credit boundary differs",
    )
    _require(
        _strict_json_equal(
            ready25["responsibility_boundary"],
            ready25_role_boundary,
        ),
        "Ready25 manifest responsibility boundary differs",
    )
    _require(
        _strict_json_equal(
            ready25["source_bindings"],
            _ready25_source_bindings(),
        ),
        "Ready25 manifest source bindings differ",
    )
    _require(
        _strict_json_equal(
            ready25["predecessor_lineage"],
            _ready25_cls_ops_predecessor_bindings(),
        ),
        "Ready25 CLS/OPS predecessor lineage differs",
    )
    ready25_manifest_contracts = ready25["contracts"]
    _require(
        list(ready25_manifest_contracts) == list(READY25_EXACT13)
        and all(
            set(contract)
            == ready25_cls_contract_keys
            | (
                {"incident_boundary"}
                if code == "OPS-18"
                else {"handover_boundary"}
                if code == "CLS-10"
                else {"service_lifecycle_boundary"}
                if code == "CLS-16"
                else set()
            )
            and _strict_json_equal(
                contract,
                _ready25_contract(code),
            )
            and contract["artifact_type_id"] == code
            and contract["catalog_artifact_id"] == f"DLV-{code}"
            and contract["applicability"] == "IN_SCOPE"
            and contract["activation_state"]
            == READY25_ACTIVATION_STATES[code]
            and contract["content_status"]
            == "PLAN_SCHEMA_CHECKLIST_AUTHORED"
            and contract["content_authored"] is True
            and contract["internal_checklist"]
            == READY25_INTERNAL_CHECKLISTS[code]
            and contract["claim_boundary"]
            == ready25_zero_contract_claim
            and contract["responsibility_boundary"]
            == ready25_role_boundary
            and contract["fabricated_result_prohibited"] is True
            and "approval_status" not in contract
            and "release_eligible" not in contract
            and contract["blockers"]
            and all(
                blocker["status"] == "OPEN"
                for blocker in contract["blockers"]
            )
            for code, contract in ready25_manifest_contracts.items()
        )
        and ready25_manifest_contracts["OPS-18"][
            "incident_boundary"
        ]
        == {
            "incident_count": 0,
            "trigger_status": "NOT_TRIGGERED",
            "zero_incidents_is_no_incident_evidence": False,
            "postmortem_status": "NOT_RUN",
        }
        and ready25_manifest_contracts["CLS-10"][
            "handover_boundary"
        ]
        == {
            "current_branch": "OPERATIONS_CONTINUE",
            "recipient": None,
            "operator": None,
            "handover_execution_status": "NOT_RUN",
        }
        and ready25_manifest_contracts["CLS-16"][
            "service_lifecycle_boundary"
        ]
        == {
            "current_branch": "OPERATIONS_CONTINUE",
            "shutdown_decision": None,
            "decommission_execution_status": "NOT_RUN",
        },
        "Ready25 CLS/OPS manifest contract boundary differs",
    )
    ready25_rel = manifest["ready25_rel_remediation"]
    _require(
        set(ready25_rel)
        == {
            "as_of",
            "packet_id",
            "evidence_path",
            "check_receipt_path",
            "predecessor_lineage",
            "artifact_type_ids",
            "applicability",
            "content_authored_count",
            "accepted_count",
            "approval_count",
            "execution_count",
            "actual_event_count",
            "formal_evidence_count",
            "release_credit_count",
            "source_bindings",
            "contracts",
        }
        and ready25_rel["as_of"] == READY25_AS_OF
        and ready25_rel["packet_id"]
        == READY25_REL_R002_PACKET_ID
        and ready25_rel["evidence_path"]
        == _rel(READY25_REL_R002_EVIDENCE_PATH)
        and ready25_rel["check_receipt_path"]
        == _rel(READY25_REL_R002_RECEIPT_PATH)
        and ready25_rel["artifact_type_ids"] == list(READY25_REL_EXACT3)
        and ready25_rel["applicability"] == "IN_SCOPE"
        and _strict_json_equal(
            {
                key: ready25_rel[key]
                for key in (
                    "content_authored_count",
                    "accepted_count",
                    "approval_count",
                    "execution_count",
                    "actual_event_count",
                    "formal_evidence_count",
                    "release_credit_count",
                )
            },
            {
                "content_authored_count": len(
                    READY25_REL_EXACT3
                ),
                "accepted_count": 0,
                "approval_count": 0,
                "execution_count": 0,
                "actual_event_count": 0,
                "formal_evidence_count": 0,
                "release_credit_count": 0,
            },
        )
        and _strict_json_equal(
            ready25_rel["source_bindings"],
            _ready25_rel_source_bindings(),
        )
        and _strict_json_equal(
            ready25_rel["predecessor_lineage"],
            _ready25_rel_predecessor_bindings(),
        ),
        "Ready25 REL manifest scope or no-credit boundary differs",
    )
    ready25_rel_manifest_contracts = ready25_rel["contracts"]
    _require(
        list(ready25_rel_manifest_contracts)
        == list(READY25_REL_EXACT3)
        and all(
            set(contract) == ready25_rel_contract_keys
            and _strict_json_equal(
                contract,
                _ready25_rel_contract(code),
            )
            and contract["artifact_type_id"] == code
            and contract["catalog_artifact_id"] == f"DLV-{code}"
            and contract["applicability"] == "IN_SCOPE"
            and contract["activation_state"]
            == READY25_REL_ACTIVATION_STATES[code]
            and contract["content_status"]
            == "PLAN_SCHEMA_CHECKLIST_AUTHORED"
            and contract["content_authored"] is True
            and contract["internal_checklist"]
            == READY25_REL_INTERNAL_CHECKLISTS[code]
            and contract["claim_boundary"]
            == ready25_zero_contract_claim
            and contract["responsibility_boundary"]
            == ready25_role_boundary
            and contract["release_strategy"]
            == ready25_release_strategy
            and contract[
                "fabricated_release_candidate_or_result_prohibited"
            ]
            is True
            and "approval_status" not in contract
            and "release_eligible" not in contract
            and contract["blockers"]
            and all(
                blocker["status"] == "OPEN"
                for blocker in contract["blockers"]
            )
            and contract["five_gate_boundary"]
            == [
                {
                    "gate_id": gate_id,
                    "status": "NOT_RUN",
                    "waived": False,
                }
                for gate_id in GATE_IDS
            ]
            for code, contract in ready25_rel_manifest_contracts.items()
        ),
        "Ready25 REL manifest contract boundary differs",
    )
    change_log = json.loads(
        ready25_side_outputs[ARTIFACT_CHANGE_LOG_PATH]
    )
    change_log_body = {
        key: value
        for key, value in change_log.items()
        if key != "content_sha256"
    }
    change_log_stable_projection = json.loads(
        json.dumps(change_log_body, ensure_ascii=False)
    )
    change_log_stable_projection["changes"] = (
        change_log_stable_projection["changes"][:12]
    )
    change_log_stable_projection.pop("summary", None)
    change_log_stable_projection["source_bindings"] = [
        item
        for item in change_log_stable_projection[
            "source_bindings"
        ]
        if item.get("name") != "current_revision_generator"
    ]
    for field in ("document_version", "as_of", "updated_at"):
        change_log_stable_projection["metadata"].pop(
            field,
            None,
        )
    scope45_application_for_event, _scope45_application_binding, (
        _scope45_receipt_binding
    ) = _ready25_scope45_application()
    expected_change_event_codes = [
        "DOC-01",
        "DOC-05",
        *[
            item["artifact_id"].removeprefix("DLV-")
            for item in scope45_application_for_event["transitions"]
        ],
    ]
    change_event = change_log["changes"][-1]
    _require(
        set(change_log)
        == {
            "schema_version",
            "metadata",
            "source_bindings",
            "draft_discovery",
            "authorization_boundary",
            "summary",
            "changes",
            "opening_snapshot",
            "content_sha256",
        }
        and change_log["content_sha256"]
        == _object_sha(change_log_body)
        and _object_sha(change_log["changes"][:12])
        == "22a91675ce4e2b7d3ea244c3d0c7f3debb43320f2c4327e28b37aa58452be800"
        and _object_sha(change_log_stable_projection)
        == "5da67f1c971ccf0e3cf0159545803c3962a330c7a32b3b520c070ae327980b33"
        and len(change_log["changes"]) == 13
        and _strict_json_equal(
            change_event,
            _ready25_change_event(),
        )
        and change_event["affected_artifact_codes"]
        == expected_change_event_codes
        and len(set(change_event["affected_artifact_codes"]))
        == len(expected_change_event_codes)
        and change_event["review"]["approval_status"]
        == "NOT_APPROVED"
        and change_event["review"]["review_status"] == "PENDING"
        and all(
            change_event["application"][field] == 0
            for field in (
                "acceptance_credit_count",
                "approval_credit_count",
                "execution_credit_count",
                "actual_event_credit_count",
                "formal_evidence_credit_count",
                "release_credit_count",
            )
        )
        and change_log["metadata"]["document_version"]
        == "1.0.2"
        and change_log["metadata"]["as_of"] == READY25_AS_OF
        and change_log["metadata"]["updated_at"]
        == READY25_AS_OF
        and _strict_json_equal(
            change_log["summary"],
            {
                "change_count": 13,
                "approved_change_count": 1,
                "last_change_id": "CHG-DOC-0013",
            },
        ),
        "DOC-05 append-only event or self-digest differs",
    )
    current_change_log_generator_bindings = [
        item
        for item in change_log["source_bindings"]
        if item.get("name") == "current_revision_generator"
    ]
    _require(
        current_change_log_generator_bindings
        == [
            {
                "name": "current_revision_generator",
                "path": _rel(GENERATOR_PATH),
                "sha256": _sha_file(GENERATOR_PATH),
            }
        ],
        "DOC-05 current revision generator binding differs",
    )
    artifact_register = json.loads(ready25_side_outputs[ARTIFACT_REGISTER_PATH])
    predecessor_artifact_register = load_strict_json(
        ARTIFACT_REGISTER_PATH
    )
    preserved_register_fields = (
        "schema_version",
        "effective_policy_context",
        "draft_discovery",
        "authorization_boundary",
        "scope_summaries",
        "bundle_coverage",
        "trace_index",
        "remaining_gates",
        "content_readiness_audit",
        "approval_application",
    )
    _require(
        _strict_json_equal(
            {
                field: artifact_register[field]
                for field in preserved_register_fields
            },
            {
                field: predecessor_artifact_register[field]
                for field in preserved_register_fields
            },
        ),
        "DOC-01 preserved canonical projection differs",
    )
    _require(
        set(artifact_register)
        == {
            "schema_version",
            "metadata",
            "source_bindings",
            "effective_policy_context",
            "draft_discovery",
            "authorization_boundary",
            "summary",
            "scope_summaries",
            "bundle_coverage",
            "artifacts",
            "trace_index",
            "remaining_gates",
            "content_readiness_audit",
            "approval_application",
            "scope45_current_scope",
            "ready25_ai_dev_sec_remediation",
            "ready25_cls_ops_remediation",
            "ready25_rel_remediation",
            "content_sha256",
        }
        and set(artifact_register["summary"])
        == {
            "activation_result_counts",
            "active_artifact_count",
            "active_opening_snapshot_applied_count",
            "active_opening_snapshot_candidate_count",
            "applicability_counts",
            "approved_artifact_count",
            "artifact_count",
            "artifact_form_counts",
            "artifact_type_count",
            "bundle_count",
            "category_counts",
            "conditional_count",
            "content_approval_applied_count",
            "content_approval_candidate_count",
            "content_approval_pending_count",
            "current_scope_n_a_count",
            "direct_trace_linked_artifact_count",
            "direct_trace_pending_case_artifact_count",
            "draft_pending_count",
            "global_artifact_completion_from_scope_n_a_count",
            "in_scope_count",
            "legacy_approval_and_lifecycle_counts_scope",
            "lifecycle_status_counts",
            "materialized_artifact_count",
            "n_a_count",
            "not_approved_count",
            "planned_artifact_count",
            "planned_not_run_count",
            "readiness_counts",
            "ready25_current_actual_event_credit_count",
            "ready25_current_approval_count",
            "ready25_current_content_accepted_count",
            "ready25_current_execution_credit_count",
            "ready25_current_formal_evidence_credit_count",
            "ready25_current_release_credit_count",
            "ready25_current_revision_count",
            "required_count",
            "versioned_baselined_count",
            "versioned_content_baseline_applied_count",
            "versioned_content_baseline_candidate_count",
        },
        "DOC-01 top-level or summary schema differs",
    )
    _require(
        _strict_json_equal(
            artifact_register["remaining_gates"],
            predecessor_artifact_register["remaining_gates"],
        ),
        "DOC-01 remaining-gate contract differs",
    )
    _require(
        _strict_json_equal(
            artifact_register["authorization_boundary"],
            predecessor_artifact_register[
                "authorization_boundary"
            ],
        ),
        "DOC-01 authorization boundary differs",
    )
    register_source_projection = json.loads(
        json.dumps(
            artifact_register["source_bindings"],
            ensure_ascii=False,
        )
    )
    for binding in register_source_projection:
        if binding.get("name") in {
            "current_doc05",
            "current_revision_generator",
        }:
            binding.pop("sha256", None)
    register_metadata_stable = json.loads(
        json.dumps(
            artifact_register["metadata"],
            ensure_ascii=False,
        )
    )
    for field in ("document_version", "as_of", "updated_at"):
        register_metadata_stable.pop(field, None)
    _require(
        _object_sha(register_source_projection)
        == READY25_REGISTER_SOURCE_PROJECTION_SHA256
        and _object_sha(register_metadata_stable)
        == READY25_REGISTER_METADATA_STABLE_SHA256
        and artifact_register["metadata"]["document_version"]
        == "1.0.2"
        and artifact_register["metadata"]["as_of"]
        == READY25_AS_OF
        and artifact_register["metadata"]["updated_at"]
        == READY25_AS_OF,
        "DOC-01 current metadata or source projection differs",
    )
    controlled_ready25_rows: dict[str, dict[str, Any]] = {}
    for source_row in artifact_register["artifacts"]:
        code = source_row["artifact_type_code"].removeprefix(
            "DLV-"
        )
        if code not in READY25_EXACT25:
            continue
        projected_row = json.loads(
            json.dumps(source_row, ensure_ascii=False)
        )
        projected_row["integrity"].pop(
            "generator_sha256",
            None,
        )
        controlled_ready25_rows[code] = projected_row
    _require(
        set(controlled_ready25_rows) == set(READY25_EXACT25)
        and _object_sha(controlled_ready25_rows)
        == READY25_CONTROLLED_ROW_PROJECTION_SHA256,
        "DOC-01 Ready25 exact25 controlled row projection differs",
    )
    expected_predecessor_register_states = (
        _ready25_pinned_predecessor_register_states(
            artifact_register
        )
    )
    _require(
        all(
            (
                row["integrity"]["generator"]
                == _rel(GENERATOR_PATH)
                and row["integrity"]["generator_sha256"]
                == _sha_file(GENERATOR_PATH)
            )
            if code in {
                *READY25_EXACT13,
                *READY25_REL_EXACT3,
            }
            else (
                row["integrity"].get("generator")
                == expected_predecessor_register_states[code][
                    "integrity"
                ]["generator"]
                and row["integrity"].get("generator_sha256")
                == expected_predecessor_register_states[code][
                    "integrity"
                ]["generator_sha256"]
            )
            for code, row in (
                (
                    source_row["artifact_type_code"].removeprefix(
                        "DLV-"
                    ),
                    source_row,
                )
                for source_row in artifact_register["artifacts"]
                if source_row[
                    "artifact_type_code"
                ].removeprefix("DLV-")
                in READY25_EXACT25
            )
        ),
        "DOC-01 Ready25 exact25 generator binding differs",
    )
    summary_probe = {
        "artifacts": artifact_register["artifacts"],
        "summary": dict(artifact_register["summary"]),
    }
    _ready25_refresh_register_summary(summary_probe)
    _require(
        _strict_json_equal(
            artifact_register["summary"],
            summary_probe["summary"],
        ),
        "DOC-01 summary projection differs from current rows",
    )
    scope45_application, scope45_application_binding, (
        scope45_receipt_binding
    ) = _ready25_scope45_application()
    expected_scope45_predecessor_states = (
        _ready25_scope45_predecessor_register_states(
            artifact_register
        )
    )
    expected_scope45_transitions = {
        item["artifact_id"]: item
        for item in scope45_application["transitions"]
    }
    scope45_rows = {
        row["artifact_type_code"]: row
        for row in artifact_register["artifacts"]
        if row["artifact_type_code"]
        in expected_scope45_transitions
    }
    expected_scope45_in_scope = [
        item["artifact_id"]
        for item in scope45_application["transitions"]
        if item["decision"]["normalized_decision"] == "IN_SCOPE"
    ]
    expected_scope45_n_a = [
        item["artifact_id"]
        for item in scope45_application["transitions"]
        if item["decision"]["normalized_decision"]
        == "OUT_OF_SCOPE_N_A"
    ]
    _require(
        set(scope45_rows) == set(expected_scope45_transitions)
        and len(scope45_rows) == 45
        and all(
            set(row["scope45_current_scope"])
            == {
                "application_id",
                "application_binding",
                "receipt_binding",
                "normalized_decision",
                "decision_id",
                "decision_maker_identity",
                "decision_maker_role",
                "authority_basis",
                "scope_decision_status",
                "queue_route",
                "queue_open",
                "artifact_closure_status",
                "current_scope_n_a_closure_claimed",
                "global_artifact_completion_claimed",
                "content_acceptance_credit",
                "execution_credit",
                "formal_test_credit",
                "owner14_approval_credit",
                "attestation_credit",
                "release_credit",
                "release_eligibility",
                "validity",
                "reactivation_trigger",
                "scope_n_a_compatibility",
                "predecessor_register_state",
            }
            and _strict_json_equal(
                row["scope45_current_scope"],
                {
                "application_id": scope45_application[
                    "application_id"
                ],
                "application_binding": (
                    scope45_application_binding
                ),
                "receipt_binding": scope45_receipt_binding,
                "normalized_decision": transition[
                    "decision"
                ]["normalized_decision"],
                "decision_id": transition["decision"][
                    "decision_id"
                ],
                "decision_maker_identity": transition[
                    "decision"
                ]["decision_maker_identity"],
                "decision_maker_role": transition["decision"][
                    "decision_maker_role"
                ],
                "authority_basis": transition["decision"][
                    "authority_basis"
                ],
                "scope_decision_status": transition[
                    "successor"
                ]["scope_decision_status"],
                "queue_route": transition["successor"][
                    "queue_route"
                ],
                "queue_open": transition["successor"][
                    "queue_open"
                ],
                "artifact_closure_status": transition[
                    "successor"
                ]["artifact_closure_status"],
                "current_scope_n_a_closure_claimed": (
                    transition["decision"][
                        "normalized_decision"
                    ]
                    == "OUT_OF_SCOPE_N_A"
                ),
                "global_artifact_completion_claimed": False,
                "content_acceptance_credit": 0,
                "execution_credit": 0,
                "formal_test_credit": 0,
                "owner14_approval_credit": 0,
                "attestation_credit": 0,
                "release_credit": 0,
                "release_eligibility": RELEASE_STATUS,
                "validity": transition["validity"],
                "reactivation_trigger": transition[
                    "route_reconsideration_trigger"
                ],
                "scope_n_a_compatibility": transition.get(
                    "scope_n_a_compatibility"
                ),
                "predecessor_register_state": (
                    expected_scope45_predecessor_states[
                        artifact_id.removeprefix("DLV-")
                    ]
                ),
                },
            )
            and set(
                row["scope45_current_scope"][
                    "predecessor_register_state"
                ]
            )
            == {
                "applicability",
                "activation_result",
                "n_a_reason",
                "n_a_approved_by",
                "n_a_review_at",
                "n_a_review_trigger",
                "state_blockers",
            }
            and row["scope45_current_scope"]["application_id"]
            == scope45_application["application_id"]
            and row["scope45_current_scope"][
                "application_binding"
            ]
            == scope45_application_binding
            and row["scope45_current_scope"]["receipt_binding"]
            == scope45_receipt_binding
            and row["scope45_current_scope"][
                "normalized_decision"
            ]
            == transition["decision"]["normalized_decision"]
            and row["scope45_current_scope"]["decision_id"]
            == transition["decision"]["decision_id"]
            and row["scope45_current_scope"][
                "scope_decision_status"
            ]
            == transition["successor"]["scope_decision_status"]
            and row["scope45_current_scope"]["queue_route"]
            == transition["successor"]["queue_route"]
            and row["scope45_current_scope"][
                "artifact_closure_status"
            ]
            == transition["successor"]["artifact_closure_status"]
            and row["scope45_current_scope"][
                "global_artifact_completion_claimed"
            ]
            is False
            and row["scope45_current_scope"][
                "content_acceptance_credit"
            ]
            == 0
            and row["scope45_current_scope"]["execution_credit"]
            == 0
            and row["scope45_current_scope"]["formal_test_credit"]
            == 0
            and row["scope45_current_scope"][
                "owner14_approval_credit"
            ]
            == 0
            and row["scope45_current_scope"]["attestation_credit"]
            == 0
            and row["scope45_current_scope"]["release_credit"]
            == 0
            and row["scope45_current_scope"]["release_eligibility"]
            == RELEASE_STATUS
            and (
                (
                    transition["decision"]["normalized_decision"]
                    == "IN_SCOPE"
                    and row["applicability"] == "IN_SCOPE"
                    and row["scope45_current_scope"][
                        "current_scope_n_a_closure_claimed"
                    ]
                    is False
                )
                or (
                    transition["decision"]["normalized_decision"]
                    == "OUT_OF_SCOPE_N_A"
                    and row["applicability"] == "CONDITIONAL"
                    and row["activation_result"]
                    == "NOT_ACTIVE_CURRENT_BASELINE"
                    and row["scope45_current_scope"][
                        "current_scope_n_a_closure_claimed"
                    ]
                    is True
                    and row["scope45_current_scope"][
                        "scope_n_a_compatibility"
                    ]
                    == transition["scope_n_a_compatibility"]
                    and row["n_a_reason"]
                    == transition["scope_n_a_compatibility"][
                        "enforced_boundary"
                    ]
                    and row["n_a_review_trigger"]
                    == transition["scope_n_a_compatibility"][
                        "reactivation_trigger"
                    ]
                    and row["state"]["blockers"]
                    == ["NOT_ACTIVE_UNDER_CURRENT_BASELINE"]
                )
            )
            for artifact_id, transition
            in expected_scope45_transitions.items()
            for row in [scope45_rows[artifact_id]]
        )
        and _strict_json_equal(
            artifact_register["scope45_current_scope"],
            {
                "application_id": scope45_application[
                    "application_id"
                ],
                "application_binding": scope45_application_binding,
                "receipt_binding": scope45_receipt_binding,
                "transition_count": 45,
                "in_scope_count": 43,
                "out_of_scope_n_a_count": 2,
                "in_scope_artifact_ids": expected_scope45_in_scope,
                "out_of_scope_n_a_artifact_ids": expected_scope45_n_a,
                "scope_n_a_current_scope_closure_credit_count": 2,
                "global_artifact_completion_credit_count": 0,
                "content_acceptance_credit_count": 0,
                "execution_credit_count": 0,
                "formal_test_credit_count": 0,
                "owner14_approval_credit_count": 0,
                "attestation_credit_count": 0,
                "release_credit_count": 0,
                "release_status": RELEASE_STATUS,
            },
        )
        and artifact_register["summary"]["required_count"] == 147
        and artifact_register["summary"]["conditional_count"] == 67
        and artifact_register["summary"]["in_scope_count"] == 43
        and artifact_register["summary"][
            "current_scope_n_a_count"
        ]
        == 2
        and artifact_register["summary"][
            "global_artifact_completion_from_scope_n_a_count"
        ]
        == 0
        and artifact_register["summary"][
            "legacy_approval_and_lifecycle_counts_scope"
        ]
        == "PRE_READY25_SNAPSHOT_ONLY"
        and artifact_register["summary"][
            "ready25_current_revision_count"
        ]
        == 25
        and all(
            artifact_register["summary"][field] == 0
            for field in (
                "ready25_current_content_accepted_count",
                "ready25_current_approval_count",
                "ready25_current_execution_credit_count",
                "ready25_current_actual_event_credit_count",
                "ready25_current_formal_evidence_credit_count",
                "ready25_current_release_credit_count",
            )
        )
        and artifact_register["summary"]["applicability_counts"]
        == {
            "CONDITIONAL": 67,
            "IN_SCOPE": 43,
            "REQUIRED": 147,
        }
        and artifact_register["summary"][
            "activation_result_counts"
        ]
        == {
            "ACTIVE": 169,
            "NOT_ACTIVE_CURRENT_BASELINE": 2,
            "PENDING_EVALUATION": 86,
        }
        and artifact_register["summary"]["n_a_count"] == 2,
        "DOC-01 scope45 current projection differs",
    )
    artifact_register_body = {
        key: value
        for key, value in artifact_register.items()
        if key != "content_sha256"
    }
    doc05_row = next(
        row
        for row in artifact_register["artifacts"]
        if row["artifact_type_code"] == "DLV-DOC-05"
    )
    _require(
        artifact_register["content_sha256"]
        == _object_sha(artifact_register_body)
        and artifact_register["metadata"][
            "current_revision_authority"
        ]
        == "ARTIFACT_REGISTER_JSON_SCOPE45_AND_READY25_CURRENT_FIELDS"
        and artifact_register["metadata"]["current_status_notice"]
        == _binding(ARTIFACT_REGISTER_CURRENT_NOTICE_PATH)
        and artifact_register["metadata"][
            "legacy_human_projection"
        ]["scope"]
        == "PRE_READY25_APPROVAL_SNAPSHOT_ONLY"
        and artifact_register["metadata"][
            "legacy_human_projection"
        ]["ready25_current_revision_rendered"]
        is False
        and artifact_register["metadata"][
            "legacy_human_projection"
        ]["use_for_ready25_current_status"]
        is False
        and artifact_register["metadata"][
            "legacy_human_projection"
        ]["navigation_warning"]
        == _binding(ARTIFACT_REGISTER_README_PATH)
        and all(
            marker
            in ARTIFACT_REGISTER_CURRENT_NOTICE_PATH.read_text(
                encoding="utf-8"
            )
            for marker in (
                "PRE_READY25_APPROVAL_SNAPSHOT_ONLY",
                "43 IN_SCOPE / 2 current-scope N/A",
                "content acceptance, owner approval, execution, "
                "actual event, formal evidence, release credit은 모두 0",
                "독립 QA는 미지정",
                "NOT_ELIGIBLE",
            )
        )
        and all(
            marker
            in ARTIFACT_REGISTER_README_PATH.read_text(
                encoding="utf-8"
            )
            for marker in (
                "DOC-01 현행 상태 안내 R001",
                "PRE_READY25_APPROVAL_SNAPSHOT_ONLY",
                "레거시 관리대장 HTML",
            )
        )
        and doc05_row["integrity"]["sha256"]
        == _sha_bytes(ready25_side_outputs[ARTIFACT_CHANGE_LOG_PATH])
        and _sha_bytes(ready25_side_outputs[ARTIFACT_REGISTER_PATH])
        not in ready25_side_outputs[ARTIFACT_CHANGE_LOG_PATH].decode(
            "utf-8"
        ),
        "DOC-01 self-digest or DOC-05 physical binding differs",
    )
    register_bindings = {
        item["name"]: item for item in artifact_register["source_bindings"]
    }
    _require(
        register_bindings["current_doc05"]["sha256"]
        == _sha_bytes(ready25_side_outputs[ARTIFACT_CHANGE_LOG_PATH])
        and register_bindings["current_revision_generator"]["path"]
        == _rel(GENERATOR_PATH)
        and register_bindings["current_revision_generator"]["sha256"]
        == _sha_file(GENERATOR_PATH),
        "DOC-01 forward DOC-05 or current generator binding differs",
    )
    _require(
        all(
            register_bindings[name]
            == {
                "name": name,
                "path": _rel(path),
                "sha256": _sha_file(path),
            }
            for name, path in (
                (
                    "scope45_transition_application",
                    SCOPE45_TRANSITION_APPLICATION_PATH,
                ),
                (
                    "scope45_transition_check_receipt",
                    SCOPE45_TRANSITION_RECEIPT_PATH,
                ),
                (
                    "artifact_register_current_status_notice",
                    ARTIFACT_REGISTER_CURRENT_NOTICE_PATH,
                ),
                (
                    "artifact_register_navigation_warning",
                    ARTIFACT_REGISTER_README_PATH,
                ),
            )
        ),
        "DOC-01 current scope or human-warning bindings differ",
    )
    expected_activation_baseline_binding = (
        _ready25_predecessor_binding(
            ARTIFACT_BASELINE_CANDIDATE_PATH,
            ARTIFACT_BASELINE_CANDIDATE_SHA256,
            "Ready25 activation baseline candidate",
        )
    )

    def predecessor_history_matches(
        row: dict[str, Any],
        remediation_field: str,
        code: str,
    ) -> bool:
        remediation = row.get(remediation_field)
        if not isinstance(remediation, dict):
            return False
        predecessor = remediation.get(
            "predecessor_register_state"
        )
        expected = expected_predecessor_register_states.get(code)
        if not isinstance(predecessor, dict) or not isinstance(
            expected,
            dict,
        ):
            return False
        expected_current_approval_control = expected[
            "approval_control"
        ]
        if isinstance(expected_current_approval_control, dict):
            expected_current_approval_control = json.loads(
                json.dumps(
                    expected_current_approval_control,
                    ensure_ascii=False,
                )
            )
            expected_current_approval_control[
                "approval_decision_scope"
            ] = "PRE_READY25_SNAPSHOT_ONLY"
            expected_current_approval_control[
                "ready25_current_revision"
            ] = {
                "approval_status": "NOT_APPROVED",
                "baseline_status": "NOT_BASELINED",
                "approval_credit_count": 0,
            }
        return (
            predecessor == expected
            and expected["state"]
            == {
                key: row["state"].get(key)
                for key in (
                    "lifecycle_status",
                    "verification_status",
                    "approval_status",
                    "baseline_status",
                )
            }
            and expected["version"]
            == {
                key: row["version"].get(key)
                for key in (
                    "document_version",
                    "approved_snapshot_version",
                    "snapshot_id",
                    "current_active_revision",
                )
            }
            and row.get("approval_control")
            == expected_current_approval_control
            and expected["integrity"]["sha256"]
            == row["integrity"]["pre_ready25_register_sha256"]
        )

    exact9_rows = _ready25_ai_dev_sec_artifact_rows(artifact_register)
    exact9_r001_register_evidence, _exact9_r001_register_receipt = (
        _ready25_ai_dev_sec_r001()
    )
    expected_exact9_register_crosswalks = {
        item["artifact_type_code"].removeprefix("DLV-"): item
        for item in exact9_r001_register_evidence[
            "per_id_acceptance_content_crosswalk"
        ]
    }
    expected_exact9_register_sources = {
        item["path"]: item
        for item in exact9_r001_register_evidence[
            "source_bindings"
        ]
    }
    expected_exact9_register_roles: dict[
        str, dict[str, Any]
    ] = {}
    for code, crosswalk in expected_exact9_register_crosswalks.items():
        required_role = crosswalk["approver_boundary"][
            "required_role"
        ]
        assigned_approver = (
            "김민호"
            if required_role
            in {"제품책임자", "프로젝트책임자"}
            else None
        )
        expected_exact9_register_roles[code] = {
            "project_owner": "김민호",
            "service_owner": "김민호",
            "product_owner": "김민호",
            "owner_evidence_class": "USER_SELF_ASSERTED",
            "required_approver_role": required_role,
            "assigned_approver": assigned_approver,
            "assigned_approver_evidence_class": (
                "USER_SELF_ASSERTED"
                if assigned_approver
                else None
            ),
            "approval_status": "NOT_PERFORMED",
            "independent_qa_reviewer": None,
            "independent_qa_status": "UNASSIGNED",
            "self_review_credit_allowed": False,
        }
    exact9_summary = artifact_register[
        "ready25_ai_dev_sec_remediation"
    ]
    _require(
        set(exact9_summary)
        == {
            "packet_id",
            "packet_path",
            "as_of",
            "artifact_type_ids",
            "applicability",
            "content_authored_count",
            "accepted_count",
            "approval_count",
            "execution_count",
            "actual_event_count",
            "formal_evidence_count",
            "release_credit_count",
            "predecessor_lineage",
        }
        and exact9_summary["artifact_type_ids"]
        == list(READY25_AI_DEV_SEC_EXACT9)
        and exact9_summary["packet_id"]
        == READY25_AI_DEV_SEC_R002_PACKET_ID
        and exact9_summary["packet_path"]
        == _rel(READY25_AI_DEV_SEC_R002_EVIDENCE_PATH)
        and exact9_summary["as_of"] == READY25_AS_OF
        and exact9_summary["applicability"] == "IN_SCOPE"
        and _strict_json_equal(
            {
                key: exact9_summary[key]
                for key in (
                    "content_authored_count",
                    "accepted_count",
                    "approval_count",
                    "execution_count",
                    "actual_event_count",
                    "formal_evidence_count",
                    "release_credit_count",
                )
            },
            {
                "content_authored_count": len(
                    READY25_AI_DEV_SEC_EXACT9
                ),
                "accepted_count": 0,
                "approval_count": 0,
                "execution_count": 0,
                "actual_event_count": 0,
                "formal_evidence_count": 0,
                "release_credit_count": 0,
            },
        )
        and "release_eligible" not in exact9_summary
        and _strict_json_equal(
            exact9_summary["predecessor_lineage"],
            _ready25_ai_dev_sec_predecessor_bindings(),
        )
        and len(exact9_rows) == len(READY25_AI_DEV_SEC_EXACT9)
        and {
            row["artifact_type_code"].removeprefix("DLV-")
            for row in exact9_rows
        }
        == set(READY25_AI_DEV_SEC_EXACT9)
        and all(
            set(row["ready25_ai_dev_sec_remediation"])
            == {
                "artifact_type_id",
                "catalog_artifact_id",
                "applicability",
                "activation_state",
                "activation_state_scope",
                "activation_state_changed_by_ready25",
                "activation_baseline_binding",
                "content_status",
                "content_authored",
                "content_accepted",
                "completion_mode",
                "primary_locator",
                "coverage_anchor",
                "canonical_source_binding",
                "required_content_crosswalk",
                "blockers",
                "owner_attribution",
                "responsibility_boundary",
                "claim_boundary",
                "predecessor_register_state",
                "fabricated_result_prohibited",
            }
            and set(row["state"]["ready25_current_revision"])
            == ready25_current_revision_keys
            and predecessor_history_matches(
                row,
                "ready25_ai_dev_sec_remediation",
                row["artifact_type_code"].removeprefix(
                    "DLV-"
                ),
            )
            and row["applicability"] == "IN_SCOPE"
            and row["activation_result"] == "PENDING_EVALUATION"
            and row["authoring_readiness"]["content_authored"] is True
            and row["authoring_readiness"][
                "ready25_current_revision_approval_application_status"
            ]
            == "NOT_APPLIED"
            and row["authoring_readiness"][
                "approval_application_status_scope"
            ]
            in {
                "PRE_READY25_SNAPSHOT_ONLY",
                "NO_PRE_READY25_APPLICATION",
            }
            and row["ready25_ai_dev_sec_remediation"]["applicability"]
            == "IN_SCOPE"
            and row["ready25_ai_dev_sec_remediation"]["content_authored"]
            is True
            and row["ready25_ai_dev_sec_remediation"][
                "content_status"
            ]
            == "CONTROLLED_CONTENT_AUTHORED_ACCEPTANCE_PENDING"
            and row["ready25_ai_dev_sec_remediation"]["content_accepted"]
            is False
            and row["ready25_ai_dev_sec_remediation"][
                "required_content_crosswalk"
            ]
            == expected_exact9_register_crosswalks[
                row["artifact_type_code"].removeprefix("DLV-")
            ]["required_content_crosswalk"]
            and row["ready25_ai_dev_sec_remediation"][
                "completion_mode"
            ]
            == expected_exact9_register_crosswalks[
                row["artifact_type_code"].removeprefix("DLV-")
            ]["completion_mode"]
            and row["ready25_ai_dev_sec_remediation"][
                "primary_locator"
            ]
            == expected_exact9_register_crosswalks[
                row["artifact_type_code"].removeprefix("DLV-")
            ]["primary_locator"]
            and row["ready25_ai_dev_sec_remediation"][
                "coverage_anchor"
            ]
            == expected_exact9_register_crosswalks[
                row["artifact_type_code"].removeprefix("DLV-")
            ]["coverage_anchor"]
            and row["ready25_ai_dev_sec_remediation"][
                "canonical_source_binding"
            ]
            == expected_exact9_register_sources[
                expected_exact9_register_crosswalks[
                    row["artifact_type_code"].removeprefix("DLV-")
                ]["primary_locator"]
            ]
            and row["ready25_ai_dev_sec_remediation"][
                "blockers"
            ]
            == expected_exact9_register_crosswalks[
                row["artifact_type_code"].removeprefix("DLV-")
            ]["open_blockers"]
            and row["ready25_ai_dev_sec_remediation"][
                "owner_attribution"
            ]
            == expected_exact9_register_crosswalks[
                row["artifact_type_code"].removeprefix("DLV-")
            ]["owner_attribution"]
            and row["ready25_ai_dev_sec_remediation"][
                "responsibility_boundary"
            ]
            == expected_exact9_register_roles[
                row["artifact_type_code"].removeprefix("DLV-")
            ]
            and row["responsibility"][
                "ready25_ai_dev_sec_role_boundary"
            ]
            == expected_exact9_register_roles[
                row["artifact_type_code"].removeprefix("DLV-")
            ]
            and row["ready25_ai_dev_sec_remediation"][
                "fabricated_result_prohibited"
            ]
            is True
            and "release_eligible"
            not in row["ready25_ai_dev_sec_remediation"]
            and row["ready25_ai_dev_sec_remediation"]["activation_state"]
            == row["activation_result"]
            and row["ready25_ai_dev_sec_remediation"][
                "predecessor_register_state"
            ]["applicability"]
            == "CONDITIONAL"
            and row["ready25_ai_dev_sec_remediation"][
                "predecessor_register_state"
            ]["activation_result"]
            == "PENDING_EVALUATION"
            and row["ready25_ai_dev_sec_remediation"][
                "activation_state_changed_by_ready25"
            ]
            is False
            and row["ready25_ai_dev_sec_remediation"][
                "activation_state_scope"
            ]
            == "READY25_DETAIL_ONLY_NOT_ROOT_ACTIVATION_TRANSITION"
            and row["ready25_ai_dev_sec_remediation"][
                "activation_baseline_binding"
            ]
            == expected_activation_baseline_binding
            and row["ready25_ai_dev_sec_remediation"]["claim_boundary"][
                "accepted"
            ]
            is False
            and row["ready25_ai_dev_sec_remediation"]["claim_boundary"][
                "approval_count"
            ]
            == 0
            and row["ready25_ai_dev_sec_remediation"]["claim_boundary"][
                "execution_count"
            ]
            == 0
            and row["ready25_ai_dev_sec_remediation"]["claim_boundary"][
                "actual_event_count"
            ]
            == 0
            and row["ready25_ai_dev_sec_remediation"]["claim_boundary"][
                "formal_evidence_count"
            ]
            == 0
            and row["ready25_ai_dev_sec_remediation"]["claim_boundary"][
                "release_credit_count"
            ]
            == 0
            and row["ready25_ai_dev_sec_remediation"]["claim_boundary"][
                "actual_receipt_ids"
            ]
            == []
            and row["ready25_ai_dev_sec_remediation"]["claim_boundary"][
                "release_status"
            ]
            == RELEASE_STATUS
            and row["state"]["ready25_current_revision"]["approval_status"]
            == "NOT_APPROVED"
            and row["state"]["ready25_current_revision"]["content_accepted"]
            is False
            and row["state"]["ready25_current_revision"]["baseline_status"]
            == "NOT_BASELINED"
            and row["state"]["ready25_current_revision"][
                "verification_status"
            ]
            == "INTERNAL_CONTENT_CHECKED"
            and row["state"]["ready25_current_revision"][
                "content_status"
            ]
            == "CONTROLLED_CONTENT_AUTHORED_ACCEPTANCE_PENDING"
            and row["state"].get("approval_status")
            == row["ready25_ai_dev_sec_remediation"][
                "predecessor_register_state"
            ]["state"]["approval_status"]
            and row["state"].get("verification_status")
            == row["ready25_ai_dev_sec_remediation"][
                "predecessor_register_state"
            ]["state"]["verification_status"]
            and row["state"]["lifecycle_status_scope"]
            == "PRE_READY25_SNAPSHOT_ONLY"
            and row["state"]["approval_status_scope"]
            in {
                "PRE_READY25_SNAPSHOT_ONLY",
                "NO_PRE_READY25_APPROVAL",
            }
            and row["state"]["verification_status_scope"]
            in {
                "PRE_READY25_SNAPSHOT_ONLY",
                "NO_PRE_READY25_VERIFICATION",
            }
            and row["state"]["baseline_status_scope"]
            in {
                "PRE_READY25_SNAPSHOT_ONLY",
                "NO_PRE_READY25_BASELINE",
            }
            and row["state"]["ready25_current_revision"][
                "execution_credit_count"
            ]
            == 0
            and row["state"]["ready25_current_revision"][
                "actual_event_credit_count"
            ]
            == 0
            and row["state"]["ready25_current_revision"][
                "formal_evidence_credit_count"
            ]
            == 0
            and row["state"]["ready25_current_revision"][
                "release_credit_count"
            ]
            == 0
            and (
                not isinstance(row.get("approval_control"), dict)
                or (
                    row["approval_control"][
                        "approval_decision_scope"
                    ]
                    == "PRE_READY25_SNAPSHOT_ONLY"
                    and row["approval_control"][
                        "ready25_current_revision"
                    ]
                    == {
                        "approval_status": "NOT_APPROVED",
                        "baseline_status": "NOT_BASELINED",
                        "approval_credit_count": 0,
                    }
                )
            )
            and row["integrity"]["sha256"]
            == row["ready25_ai_dev_sec_remediation"][
                "canonical_source_binding"
            ]["sha256"]
            and row["integrity"]["sha256_scope"]
            == "CURRENT_PHYSICAL_CANONICAL_BYTES_NOT_APPROVAL_CREDIT"
            and row["integrity"]["generator"]
            == row["ready25_ai_dev_sec_remediation"][
                "predecessor_register_state"
            ]["integrity"]["generator"]
            and row["ready25_ai_dev_sec_remediation"][
                "responsibility_boundary"
            ]["approval_status"]
            == "NOT_PERFORMED"
            and row["ready25_ai_dev_sec_remediation"][
                "responsibility_boundary"
            ]["independent_qa_reviewer"]
            is None
            and row["ready25_ai_dev_sec_remediation"][
                "responsibility_boundary"
            ]["independent_qa_status"]
            == "UNASSIGNED"
            and row["ready25_ai_dev_sec_remediation"][
                "responsibility_boundary"
            ]["self_review_credit_allowed"]
            is False
            and row["responsibility"]["assigned_reviewers"] == []
            and set(row["responsibility"].get("reviewer_roles", []))
            <= set(
                row["responsibility"][
                    "unassigned_required_reviewer_roles"
                ]
            )
            and (
                (
                    row["ready25_ai_dev_sec_remediation"][
                        "responsibility_boundary"
                    ]["required_approver_role"]
                    == "기술책임자"
                    and row["responsibility"]["assigned_approver"] is None
                    and "기술책임자"
                    in row["responsibility"][
                        "unassigned_required_approver_roles"
                    ]
                )
                or (
                    row["ready25_ai_dev_sec_remediation"][
                        "responsibility_boundary"
                    ]["required_approver_role"]
                    in {"제품책임자", "프로젝트책임자"}
                    and row["responsibility"]["assigned_approver"]
                    == "김민호"
                )
            )
            for row in exact9_rows
        ),
        "Ready25 AI/DEV/SEC artifact-register exact9 boundary differs",
    )
    exact13_summary = artifact_register[
        "ready25_cls_ops_remediation"
    ]
    _require(
        set(exact13_summary)
        == {
            "packet_id",
            "packet_path",
            "as_of",
            "artifact_type_ids",
            "applicability",
            "content_authored_count",
            "accepted_count",
            "approval_count",
            "execution_count",
            "actual_event_count",
            "formal_evidence_count",
            "release_credit_count",
            "source_bindings",
            "predecessor_lineage",
        }
        and exact13_summary["packet_id"]
        == READY25_CLS_OPS_R003_PACKET_ID
        and exact13_summary["packet_path"]
        == _rel(READY25_CLS_OPS_R003_EVIDENCE_PATH)
        and exact13_summary["as_of"] == READY25_AS_OF
        and exact13_summary["artifact_type_ids"]
        == list(READY25_EXACT13)
        and exact13_summary["applicability"] == "IN_SCOPE"
        and _strict_json_equal(
            {
                key: exact13_summary[key]
                for key in (
                    "content_authored_count",
                    "accepted_count",
                    "approval_count",
                    "execution_count",
                    "actual_event_count",
                    "formal_evidence_count",
                    "release_credit_count",
                )
            },
            {
                "content_authored_count": len(
                    READY25_EXACT13
                ),
                "accepted_count": 0,
                "approval_count": 0,
                "execution_count": 0,
                "actual_event_count": 0,
                "formal_evidence_count": 0,
                "release_credit_count": 0,
            },
        )
        and "release_eligible" not in exact13_summary
        and _strict_json_equal(
            exact13_summary["source_bindings"],
            _ready25_source_bindings(),
        )
        and _strict_json_equal(
            exact13_summary["predecessor_lineage"],
            _ready25_cls_ops_predecessor_bindings(),
        ),
        "Ready25 CLS/OPS artifact-register aggregate differs",
    )
    exact3_summary = artifact_register["ready25_rel_remediation"]
    _require(
        set(exact3_summary)
        == {
            "packet_id",
            "packet_path",
            "as_of",
            "artifact_type_ids",
            "applicability",
            "content_authored_count",
            "accepted_count",
            "approval_count",
            "execution_count",
            "actual_event_count",
            "formal_evidence_count",
            "release_credit_count",
            "source_bindings",
            "predecessor_lineage",
        }
        and exact3_summary["packet_id"] == READY25_REL_R002_PACKET_ID
        and exact3_summary["packet_path"]
        == _rel(READY25_REL_R002_EVIDENCE_PATH)
        and exact3_summary["as_of"] == READY25_AS_OF
        and exact3_summary["artifact_type_ids"]
        == list(READY25_REL_EXACT3)
        and exact3_summary["applicability"] == "IN_SCOPE"
        and _strict_json_equal(
            {
                key: exact3_summary[key]
                for key in (
                    "content_authored_count",
                    "accepted_count",
                    "approval_count",
                    "execution_count",
                    "actual_event_count",
                    "formal_evidence_count",
                    "release_credit_count",
                )
            },
            {
                "content_authored_count": len(
                    READY25_REL_EXACT3
                ),
                "accepted_count": 0,
                "approval_count": 0,
                "execution_count": 0,
                "actual_event_count": 0,
                "formal_evidence_count": 0,
                "release_credit_count": 0,
            },
        )
        and "release_eligible" not in exact3_summary
        and _strict_json_equal(
            exact3_summary["source_bindings"],
            _ready25_rel_source_bindings(),
        )
        and _strict_json_equal(
            exact3_summary["predecessor_lineage"],
            _ready25_rel_predecessor_bindings(),
        ),
        "Ready25 REL artifact-register aggregate differs",
    )
    ready25_rows = _ready25_artifact_rows(artifact_register)
    _require(
        {row["artifact_type_code"].removeprefix("DLV-") for row in ready25_rows}
        == set(READY25_EXACT13)
        and all(row["applicability"] == "IN_SCOPE" for row in ready25_rows)
        and all(
            row["authoring_readiness"]["content_authored"] is True
            for row in ready25_rows
        )
        and all(
            set(row["ready25_remediation"])
            == ready25_cls_contract_keys
            | {
                "predecessor_register_state",
                "activation_state_scope",
                "activation_state_changed_by_ready25",
                "activation_baseline_binding",
            }
            | (
                {"incident_boundary"}
                if row["artifact_type_code"] == "DLV-OPS-18"
                else {"handover_boundary"}
                if row["artifact_type_code"] == "DLV-CLS-10"
                else {"service_lifecycle_boundary"}
                if row["artifact_type_code"] == "DLV-CLS-16"
                else set()
            )
            and set(row["state"]["ready25_current_revision"])
            == ready25_current_revision_keys
            and {
                key: row["ready25_remediation"][key]
                for key in _ready25_contract(
                    row["artifact_type_code"].removeprefix(
                        "DLV-"
                    )
                )
            }
            == _ready25_contract(
                row["artifact_type_code"].removeprefix("DLV-")
            )
            and predecessor_history_matches(
                row,
                "ready25_remediation",
                row["artifact_type_code"].removeprefix(
                    "DLV-"
                ),
            )
            and row["activation_result"] == "PENDING_EVALUATION"
            and row["ready25_remediation"]["activation_state"]
            == READY25_ACTIVATION_STATES[
                row["artifact_type_code"].removeprefix("DLV-")
            ]
            and row["ready25_remediation"][
                "predecessor_register_state"
            ]["applicability"]
            == "CONDITIONAL"
            and row["ready25_remediation"][
                "predecessor_register_state"
            ]["activation_result"]
            == "PENDING_EVALUATION"
            and row["ready25_remediation"]["content_status"]
            == "PLAN_SCHEMA_CHECKLIST_AUTHORED"
            and row["ready25_remediation"]["content_authored"]
            is True
            and row["ready25_remediation"]["internal_checklist"]
            == READY25_INTERNAL_CHECKLISTS[
                row["artifact_type_code"].removeprefix("DLV-")
            ]
            and row["ready25_remediation"]["blockers"]
            and all(
                blocker["status"] == "OPEN"
                for blocker in row["ready25_remediation"][
                    "blockers"
                ]
            )
            and row["ready25_remediation"][
                "fabricated_result_prohibited"
            ]
            is True
            and "approval_status"
            not in row["ready25_remediation"]
            and "release_eligible"
            not in row["ready25_remediation"]
            and row["ready25_remediation"]["activation_state_scope"]
            == "READY25_DETAIL_ONLY_NOT_ROOT_ACTIVATION_TRANSITION"
            and row["ready25_remediation"][
                "activation_state_changed_by_ready25"
            ]
            is False
            and row["ready25_remediation"][
                "activation_baseline_binding"
            ]
            == expected_activation_baseline_binding
            and row["ready25_remediation"]["claim_boundary"]
            == ready25_zero_contract_claim
            and row["ready25_remediation"]["responsibility_boundary"]
            == ready25_role_boundary
            and row["state"]["ready25_current_revision"]["approval_status"]
            == "NOT_APPROVED"
            and row["state"]["ready25_current_revision"]["content_accepted"]
            is False
            and row["state"]["ready25_current_revision"]["baseline_status"]
            == "NOT_BASELINED"
            and row["state"]["ready25_current_revision"][
                "verification_status"
            ]
            == "INTERNAL_CONTENT_CHECKED"
            and row["state"]["ready25_current_revision"][
                "content_status"
            ]
            == "PLAN_SCHEMA_CHECKLIST_AUTHORED"
            and row["state"].get("approval_status")
            == row["ready25_remediation"][
                "predecessor_register_state"
            ]["state"]["approval_status"]
            and row["state"].get("verification_status")
            == row["ready25_remediation"][
                "predecessor_register_state"
            ]["state"]["verification_status"]
            and row["state"]["ready25_current_revision"][
                "execution_credit_count"
            ]
            == 0
            and row["state"]["ready25_current_revision"][
                "actual_event_credit_count"
            ]
            == 0
            and row["state"]["ready25_current_revision"][
                "formal_evidence_credit_count"
            ]
            == 0
            and row["state"]["ready25_current_revision"][
                "release_credit_count"
            ]
            == 0
            and (
                not isinstance(row.get("approval_control"), dict)
                or (
                    row["approval_control"][
                        "approval_decision_scope"
                    ]
                    == "PRE_READY25_SNAPSHOT_ONLY"
                    and row["approval_control"][
                        "ready25_current_revision"
                    ]
                    == {
                        "approval_status": "NOT_APPROVED",
                        "baseline_status": "NOT_BASELINED",
                        "approval_credit_count": 0,
                    }
                )
            )
            for row in ready25_rows
        ),
        "Ready25 artifact-register exact13 boundary differs",
    )
    _require(
        all(
            row["responsibility"]["assigned_reviewers"] == []
            and set(row["responsibility"]["reviewer_roles"])
            <= set(
                row["responsibility"][
                    "unassigned_required_reviewer_roles"
                ]
            )
            for row in ready25_rows
        ),
        "Ready25 reviewer assignment boundary differs",
    )
    ready25_rel_rows = _ready25_rel_artifact_rows(artifact_register)
    _require(
        {row["artifact_type_code"].removeprefix("DLV-") for row in ready25_rel_rows}
        == set(READY25_REL_EXACT3)
        and all(row["applicability"] == "IN_SCOPE" for row in ready25_rel_rows)
        and all(
            set(row["ready25_rel_remediation"])
            == ready25_rel_contract_keys
            | {
                "predecessor_register_state",
                "activation_state_scope",
                "activation_state_changed_by_ready25",
                "activation_baseline_binding",
            }
            and set(row["state"]["ready25_current_revision"])
            == ready25_current_revision_keys
            and {
                key: row["ready25_rel_remediation"][key]
                for key in _ready25_rel_contract(
                    row["artifact_type_code"].removeprefix(
                        "DLV-"
                    )
                )
            }
            == _ready25_rel_contract(
                row["artifact_type_code"].removeprefix("DLV-")
            )
            and predecessor_history_matches(
                row,
                "ready25_rel_remediation",
                row["artifact_type_code"].removeprefix(
                    "DLV-"
                ),
            )
            and row["authoring_readiness"]["content_authored"] is True
            and row["activation_result"] == "PENDING_EVALUATION"
            and row["ready25_rel_remediation"]["activation_state"]
            == READY25_REL_ACTIVATION_STATES[
                row["artifact_type_code"].removeprefix("DLV-")
            ]
            and row["ready25_rel_remediation"][
                "predecessor_register_state"
            ]["applicability"]
            == "CONDITIONAL"
            and row["ready25_rel_remediation"][
                "predecessor_register_state"
            ]["activation_result"]
            == "PENDING_EVALUATION"
            and row["ready25_rel_remediation"]["content_status"]
            == "PLAN_SCHEMA_CHECKLIST_AUTHORED"
            and row["ready25_rel_remediation"]["content_authored"]
            is True
            and row["ready25_rel_remediation"][
                "internal_checklist"
            ]
            == READY25_REL_INTERNAL_CHECKLISTS[
                row["artifact_type_code"].removeprefix("DLV-")
            ]
            and row["ready25_rel_remediation"]["blockers"]
            and all(
                blocker["status"] == "OPEN"
                for blocker in row["ready25_rel_remediation"][
                    "blockers"
                ]
            )
            and row["ready25_rel_remediation"][
                "fabricated_release_candidate_or_result_prohibited"
            ]
            is True
            and "approval_status"
            not in row["ready25_rel_remediation"]
            and "release_eligible"
            not in row["ready25_rel_remediation"]
            and row["ready25_rel_remediation"]["activation_state_scope"]
            == "READY25_DETAIL_ONLY_NOT_ROOT_ACTIVATION_TRANSITION"
            and row["ready25_rel_remediation"][
                "activation_state_changed_by_ready25"
            ]
            is False
            and row["ready25_rel_remediation"][
                "activation_baseline_binding"
            ]
            == expected_activation_baseline_binding
            and row["ready25_rel_remediation"]["claim_boundary"]
            == ready25_zero_contract_claim
            and row["ready25_rel_remediation"][
                "responsibility_boundary"
            ]
            == ready25_role_boundary
            and row["ready25_rel_remediation"]["release_strategy"]
            == ready25_release_strategy
            and row["ready25_rel_remediation"][
                "five_gate_boundary"
            ]
            == [
                {
                    "gate_id": gate_id,
                    "status": "NOT_RUN",
                    "waived": False,
                }
                for gate_id in GATE_IDS
            ]
            and row["state"]["ready25_current_revision"]["approval_status"]
            == "NOT_APPROVED"
            and row["state"]["ready25_current_revision"]["content_accepted"]
            is False
            and row["state"]["ready25_current_revision"]["baseline_status"]
            == "NOT_BASELINED"
            and row["state"]["ready25_current_revision"][
                "verification_status"
            ]
            == "INTERNAL_CONTENT_CHECKED"
            and row["state"]["ready25_current_revision"][
                "content_status"
            ]
            == "PLAN_SCHEMA_CHECKLIST_AUTHORED"
            and row["state"].get("approval_status")
            == row["ready25_rel_remediation"][
                "predecessor_register_state"
            ]["state"]["approval_status"]
            and row["state"].get("verification_status")
            == row["ready25_rel_remediation"][
                "predecessor_register_state"
            ]["state"]["verification_status"]
            and row["state"]["ready25_current_revision"][
                "execution_credit_count"
            ]
            == 0
            and row["state"]["ready25_current_revision"][
                "actual_event_credit_count"
            ]
            == 0
            and row["state"]["ready25_current_revision"][
                "formal_evidence_credit_count"
            ]
            == 0
            and row["state"]["ready25_current_revision"][
                "release_credit_count"
            ]
            == 0
            and (
                not isinstance(row.get("approval_control"), dict)
                or (
                    row["approval_control"][
                        "approval_decision_scope"
                    ]
                    == "PRE_READY25_SNAPSHOT_ONLY"
                    and row["approval_control"][
                        "ready25_current_revision"
                    ]
                    == {
                        "approval_status": "NOT_APPROVED",
                        "baseline_status": "NOT_BASELINED",
                        "approval_credit_count": 0,
                    }
                )
            )
            and row["responsibility"]["assigned_reviewers"] == []
            and set(row["responsibility"]["reviewer_roles"])
            <= set(
                row["responsibility"][
                    "unassigned_required_reviewer_roles"
                ]
            )
            for row in ready25_rel_rows
        ),
        "Ready25 REL artifact-register exact3 boundary differs",
    )
    ops18 = next(
        row for row in ready25_rows if row["artifact_type_code"] == "DLV-OPS-18"
    )
    cls10 = next(
        row for row in ready25_rows if row["artifact_type_code"] == "DLV-CLS-10"
    )
    cls16 = next(
        row for row in ready25_rows if row["artifact_type_code"] == "DLV-CLS-16"
    )
    _require(
        ops18["activation_result"] == "PENDING_EVALUATION"
        and ops18["ready25_remediation"]["incident_boundary"]
        == {
            "incident_count": 0,
            "trigger_status": "NOT_TRIGGERED",
            "zero_incidents_is_no_incident_evidence": False,
            "postmortem_status": "NOT_RUN",
        }
        and cls10["ready25_remediation"]["handover_boundary"]
        == {
            "current_branch": "OPERATIONS_CONTINUE",
            "recipient": None,
            "operator": None,
            "handover_execution_status": "NOT_RUN",
        }
        and cls16["ready25_remediation"][
            "service_lifecycle_boundary"
        ]
        == {
            "current_branch": "OPERATIONS_CONTINUE",
            "shutdown_decision": None,
            "decommission_execution_status": "NOT_RUN",
        },
        "Ready25 OPS-18/CLS-10/CLS-16 trigger boundary differs",
    )
    exact9_evidence = json.loads(
        ready25_side_outputs[READY25_AI_DEV_SEC_R002_EVIDENCE_PATH]
    )
    exact9_evidence_integrity = exact9_evidence["nonself_integrity"]
    exact9_evidence_body = {
        key: value
        for key, value in exact9_evidence.items()
        if key != "nonself_integrity"
    }
    exact9_evidence_projection = (
        json.dumps(
            exact9_evidence_body,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")
    exact9_claim_boundary = {
        "actual_result_synthesized": False,
        "approval_claimed": False,
        "artifact_register_modified": True,
        "deployment_claimed": False,
        "execution_claimed": False,
        "formal_pass_claimed": False,
        "release_eligible_claimed": False,
        "rights_or_privacy_verified_claimed": False,
    }
    exact9_contracts = exact9_evidence[
        "per_id_acceptance_content_crosswalk"
    ]
    exact9_r001_evidence, _exact9_r001_receipt = (
        _ready25_ai_dev_sec_r001()
    )
    expected_exact9_source_bindings = list(
        exact9_r001_evidence["source_bindings"]
    )
    expected_exact9_source_paths = {
        item["path"] for item in expected_exact9_source_bindings
    }
    for path in (
        ARTIFACT_CHANGE_LOG_PATH,
        ARTIFACT_REGISTER_PATH,
    ):
        if _rel(path) not in expected_exact9_source_paths:
            expected_exact9_source_bindings.append(
                _ready25_output_binding(
                    path,
                    ready25_side_outputs[path],
                )
            )
    _require(
        set(exact9_evidence)
        == {
            "schema_version",
            "metadata",
            "exact_set",
            "source_bindings",
            "per_id_acceptance_content_crosswalk",
            "implementation_successor",
            "quality_successor",
            "data_model_r002_boundary",
            "owner_and_approval_boundary",
            "policy_gate_boundary",
            "credit_summary",
            "claim_boundary",
            "predecessor_lineage",
            "artifact_register_application",
            "nonself_integrity",
        }
        and exact9_evidence["schema_version"]
        == "walksafe.phase1-ready25-ai-dev-sec.evidence.v2"
        and "approval_status" not in exact9_evidence
        and "release_eligible" not in exact9_evidence
        and exact9_evidence["metadata"]["packet_id"]
        == READY25_AI_DEV_SEC_R002_PACKET_ID
        and exact9_evidence["metadata"]
        == {
            "approval_status": "NOT_APPROVED",
            "mode": "INTERNAL_CONTENT_REMEDIATION_ONLY",
            "packet_id": READY25_AI_DEV_SEC_R002_PACKET_ID,
            "prepared_on": READY25_AS_OF,
            "release_status": RELEASE_STATUS,
            "run_id": "WS-ARTIFACT-CLOSURE-RUN-20260727-001",
        }
        and _strict_json_equal(
            exact9_evidence["claim_boundary"],
            exact9_claim_boundary,
        )
        and _strict_json_equal(
            exact9_evidence["predecessor_lineage"],
            _ready25_ai_dev_sec_predecessor_bindings(),
        )
        and _strict_json_equal(
            exact9_evidence["source_bindings"],
            expected_exact9_source_bindings,
        )
        and all(
            _strict_json_equal(
                exact9_evidence[field],
                exact9_r001_evidence[field],
            )
            for field in (
                "exact_set",
                "implementation_successor",
                "quality_successor",
                "credit_summary",
            )
        )
        and _strict_json_equal(
            exact9_evidence["data_model_r002_boundary"],
            {
                "approval_credit": 0,
                "content_inspection": (
                    "NOT_PERFORMED_HASH_AND_METADATA_ONLY"
                ),
                "direct_capture_inclusion": "UNKNOWN",
                "formal_training_reproduction_credit": 0,
                "physical_binding": (
                    "CONFIRMED_FILE_IDENTITY_ONLY"
                ),
                "privacy_review": "NOT_VERIFIED",
                "release_credit": 0,
                "rights_verification": "NOT_VERIFIED",
                "source_completeness": "UNKNOWN",
                "split_and_leakage_validation_credit": 0,
                "trainer_attribution": (
                    "USER_SELF_ASSERTED_NOT_INDEPENDENTLY_VERIFIED"
                ),
                "user_provided_inclusion": "UNKNOWN",
            },
        )
        and _strict_json_equal(
            exact9_evidence["owner_and_approval_boundary"],
            {
                "approval_event_status": "NOT_PERFORMED",
                "independence_claimed": False,
                "product_project_owner_attribution": {
                    "evidence_class": "USER_SELF_ASSERTED",
                    "identity": "김민호",
                },
                "qa_reviewer": "UNASSIGNED",
                "scope_owner": {
                    "evidence_class": "USER_SELF_ASSERTED",
                    "identity": "김민호",
                    "role": "PROJECT_SCOPE_OWNER",
                },
            },
        )
        and _strict_json_equal(
            exact9_evidence["policy_gate_boundary"],
            {
                "all_status": "NOT_RUN",
                "gate_count": len(GATE_IDS),
                "gates": list(GATE_IDS),
                "waived_count": 0,
            },
        )
        and exact9_evidence["credit_summary"][
            "content_authored_true_count"
        ]
        == len(READY25_AI_DEV_SEC_EXACT9)
        and exact9_evidence["credit_summary"]["content_accepted_count"] == 0
        and exact9_evidence["credit_summary"]["owner_approval_count"] == 0
        and exact9_evidence["credit_summary"]["execution_credit_count"] == 0
        and exact9_evidence["credit_summary"]["formal_test_credit_count"] == 0
        and exact9_evidence["credit_summary"]["real_event_credit_count"] == 0
        and exact9_evidence["credit_summary"]["release_credit_count"] == 0
        and len(exact9_contracts) == len(READY25_AI_DEV_SEC_EXACT9)
        and [
            item["artifact_type_code"]
            for item in exact9_contracts
        ]
        == [f"DLV-{code}" for code in READY25_AI_DEV_SEC_EXACT9]
        and _strict_json_equal(
            exact9_contracts,
            exact9_r001_evidence[
                "per_id_acceptance_content_crosswalk"
            ],
        )
        and all(
            item["scope_status"] == "IN_SCOPE"
            and item["content_authored"] is True
            and item["content_accepted"] is False
            and item["owner_approval_credit"] == 0
            and item["execution_credit"] == 0
            and item["formal_test_credit"] == 0
            and item["release_credit"] == 0
            and item["qa_reviewer"] == "UNASSIGNED"
            and item["approver_boundary"]["approval_status"]
            == "NOT_PERFORMED"
            and item["owner_attribution"]["evidence_class"]
            == "USER_SELF_ASSERTED"
            and item["open_blockers"]
            for item in exact9_contracts
        )
        and _strict_json_equal(
            exact9_evidence["artifact_register_application"],
            {
                "status": "APPLIED_TO_CURRENT_DOC01",
                "artifact_register": _ready25_output_binding(
                    ARTIFACT_REGISTER_PATH,
                    ready25_side_outputs[
                        ARTIFACT_REGISTER_PATH
                    ],
                ),
                "artifact_change_log": _ready25_output_binding(
                    ARTIFACT_CHANGE_LOG_PATH,
                    ready25_side_outputs[
                        ARTIFACT_CHANGE_LOG_PATH
                    ],
                ),
                "exact_row_count": len(
                    READY25_AI_DEV_SEC_EXACT9
                ),
                "content_authored_count": len(
                    READY25_AI_DEV_SEC_EXACT9
                ),
                "content_accepted_count": 0,
                "owner_approval_count": 0,
                "execution_credit_count": 0,
                "actual_event_credit_count": 0,
                "formal_test_credit_count": 0,
                "release_credit_count": 0,
            },
        )
        and _strict_json_equal(
            exact9_evidence_integrity,
            {
                "algorithm": "SHA-256",
                "canonicalization": (
                    "UTF-8 JSON, recursively sorted keys, "
                    "compact separators, one terminal LF"
                ),
                "projection_bytes": len(
                    exact9_evidence_projection
                ),
                "scope": (
                    "ENTIRE_DOCUMENT_EXCLUDING_TOP_LEVEL_"
                    "NONSELF_INTEGRITY"
                ),
                "sha256": _sha_bytes(
                    exact9_evidence_projection
                ),
            },
        ),
        "Ready25 AI/DEV/SEC evidence boundary or integrity differs",
    )
    exact9_receipt = json.loads(
        ready25_side_outputs[READY25_AI_DEV_SEC_R002_RECEIPT_PATH]
    )
    exact9_receipt_integrity = exact9_receipt["nonself_integrity"]
    exact9_receipt_body = {
        key: value
        for key, value in exact9_receipt.items()
        if key != "nonself_integrity"
    }
    exact9_receipt_projection = (
        json.dumps(
            exact9_receipt_body,
            ensure_ascii=True,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")
    expected_exact9_checks = [
        ("READY25-001", "EXACT9_SET"),
        ("READY25-002", "PRIMARY_LOCATOR_AND_ANCHOR_CROSSWALK"),
        ("READY25-003", "CONTENT_AUTHORED_TRUE9_ACCEPTED_ZERO"),
        ("READY25-004", "SOURCE_PHYSICAL_HASH_BINDINGS"),
        (
            "READY25-005",
            "DEV_SUCCESSOR_ADD_ONLY_AND_EXACT_ARTIFACT_BINDINGS",
        ),
        ("READY25-006", "DEV15_ZERO_EVENT_BOUNDARY_NOT_EVENT_COMPLETION"),
        ("READY25-007", "DATA_MODEL_R002_ZERO_PROMOTION_BOUNDARY"),
        ("READY25-008", "FIVE_GATES_NOT_RUN_UNWAIVED"),
        (
            "READY25-009",
            "QA_UNASSIGNED_AND_APPROVAL_NOT_PERFORMED",
        ),
        (
            "READY25-010",
            "ZERO_EXECUTION_FORMAL_RELEASE_AND_ARTIFACT_REGISTER_INTEGRATED",
        ),
        (
            "READY25-011",
            "DOC01_DOC05_SELF_DIGEST_AND_EXACT9_APPLICATION",
        ),
    ]
    expected_exact9_check_rows = [
        {
            "check_id": check_id,
            "name": name,
            "result": "PASS",
        }
        for check_id, name in expected_exact9_checks
    ]
    expected_exact9_receipt_output_bindings = [
        item
        for item in _exact9_r001_receipt.get(
            "output_bindings",
            [],
        )
        if item.get("path")
        != _rel(READY25_AI_DEV_SEC_R001_EVIDENCE_PATH)
    ]
    expected_exact9_receipt_output_bindings.extend(
        [
            _ready25_output_binding(
                ARTIFACT_CHANGE_LOG_PATH,
                ready25_side_outputs[ARTIFACT_CHANGE_LOG_PATH],
            ),
            _ready25_output_binding(
                ARTIFACT_REGISTER_PATH,
                ready25_side_outputs[ARTIFACT_REGISTER_PATH],
            ),
            _ready25_output_binding(
                READY25_AI_DEV_SEC_R002_EVIDENCE_PATH,
                ready25_side_outputs[
                    READY25_AI_DEV_SEC_R002_EVIDENCE_PATH
                ],
            ),
        ]
    )
    _require(
        set(exact9_receipt)
        == {
            "schema_version",
            "metadata",
            "subject",
            "checks",
            "summary",
            "credit_summary",
            "claim_boundary",
            "predecessor_lineage",
            "output_bindings",
            "nonself_integrity",
        }
        and exact9_receipt["schema_version"]
        == "walksafe.phase1-ready25-ai-dev-sec-check-receipt.v2"
        and "approval_status" not in exact9_receipt
        and "release_eligible" not in exact9_receipt
        and exact9_receipt["metadata"]["receipt_id"]
        == READY25_AI_DEV_SEC_R002_RECEIPT_ID
        and _strict_json_equal(
            exact9_receipt["metadata"],
            {
                "check_mode": (
                    "DETERMINISTIC_DOCUMENT_AND_JSON_ONLY_"
                    "NO_PRODUCT_TEST_OR_BUILD"
                ),
                "checked_on": READY25_AS_OF,
                "receipt_id": (
                    READY25_AI_DEV_SEC_R002_RECEIPT_ID
                ),
                "run_id": (
                    "WS-ARTIFACT-CLOSURE-RUN-20260727-001"
                ),
                "verdict": (
                    "PASS_FOR_CONTENT_AUTHORING_AND_REGISTER_"
                    "APPLICATION_BOUNDARY_ONLY"
                ),
            },
        )
        and _strict_json_equal(
            exact9_receipt["summary"],
            {
                "fail_count": 0,
                "pass_count": len(
                    expected_exact9_check_rows
                ),
            },
        )
        and _strict_json_equal(
            exact9_receipt["checks"],
            expected_exact9_check_rows,
        )
        and _strict_json_equal(
            exact9_receipt["claim_boundary"],
            exact9_claim_boundary,
        )
        and _strict_json_equal(
            exact9_receipt["credit_summary"],
            exact9_evidence["credit_summary"],
        )
        and _strict_json_equal(
            exact9_receipt["predecessor_lineage"],
            _ready25_ai_dev_sec_predecessor_bindings(),
        )
        and _strict_json_equal(
            exact9_receipt["output_bindings"],
            expected_exact9_receipt_output_bindings,
        )
        and _strict_json_equal(
            exact9_receipt["subject"],
            _ready25_output_binding(
                READY25_AI_DEV_SEC_R002_EVIDENCE_PATH,
                ready25_side_outputs[
                    READY25_AI_DEV_SEC_R002_EVIDENCE_PATH
                ],
            ),
        )
        and _strict_json_equal(
            exact9_receipt_integrity,
            {
                "algorithm": "SHA-256",
                "canonicalization": (
                    "UTF-8 JSON, recursively sorted keys, "
                    "compact separators, one terminal LF"
                ),
                "projection_bytes": len(
                    exact9_receipt_projection
                ),
                "scope": (
                    "ENTIRE_DOCUMENT_EXCLUDING_TOP_LEVEL_"
                    "NONSELF_INTEGRITY"
                ),
                "sha256": _sha_bytes(
                    exact9_receipt_projection
                ),
            },
        ),
        "Ready25 AI/DEV/SEC receipt boundary or integrity differs",
    )
    evidence = json.loads(
        ready25_side_outputs[READY25_CLS_OPS_R003_EVIDENCE_PATH]
    )
    evidence_integrity = evidence["integrity"]
    evidence_body = {key: value for key, value in evidence.items() if key != "integrity"}
    zero_contract_claim = {
        "accepted": False,
        "approval_count": 0,
        "execution_count": 0,
        "actual_event_count": 0,
        "formal_evidence_count": 0,
        "release_credit_count": 0,
        "actual_receipt_ids": [],
        "release_status": RELEASE_STATUS,
    }
    ready25_catalog = _catalog_by_code(
        load_strict_json(ARTIFACT_CATALOG_PATH)
    )
    expected_cls_ops_bound_paths = {
        ARTIFACT_CHANGE_LOG_PATH,
        ARTIFACT_REGISTER_PATH,
        MANIFEST_PATH,
        OPS_REGISTER_PATH,
        CLS_REGISTER_PATH,
        *[
            path
            for path, codes in DOCUMENT_COVERAGE.items()
            if set(codes) & set(READY25_EXACT13)
        ],
    }
    expected_cls_ops_output_bindings = [
        _ready25_output_binding(
            path,
            (
                ready25_side_outputs[path]
                if path in ready25_side_outputs
                else outputs[path]
            ),
        )
        for path in sorted(expected_cls_ops_bound_paths, key=_rel)
    ]
    cls_ops_evidence_contracts = {
        contract["artifact_type_id"]: contract
        for contract in evidence["artifact_contracts"]
    }
    _require(
        set(evidence)
        == {
            "schema_version",
            "packet_id",
            "prepared_on",
            "predecessor_lineage",
            "scope_decision",
            "r010_chain_binding",
            "responsibility_boundary",
            "artifact_contracts",
            "generated_output_bindings",
            "summary",
            "claim_boundary",
            "integrity",
        }
        and evidence["schema_version"]
        == "walksafe.phase1-ready25-cls-ops-evidence.v1"
        and evidence["prepared_on"] == READY25_AS_OF
        and "approval_status" not in evidence
        and "release_eligible" not in evidence
        and evidence["packet_id"] == READY25_CLS_OPS_R003_PACKET_ID
        and _strict_json_equal(
            evidence["predecessor_lineage"],
            _ready25_cls_ops_predecessor_bindings(),
        )
        and evidence["scope_decision"]
        == {
            "decision": "IN_SCOPE",
            "artifact_type_ids": list(READY25_EXACT13),
            "user_scope_receipt": _ready25_source_bindings()[
                "user_scope_transition_receipt"
            ],
        }
        and evidence["r010_chain_binding"]
        == {
            "semantics": (
                "ADD_ONLY_READY25_SUCCESSOR_OVER_R010_"
                "WRAPPER_AND_R007_LEDGER_SUBJECT"
            ),
            "r010_evidence": _ready25_source_bindings()[
                "r010_successor_evidence"
            ],
            "r010_check_receipt": _ready25_source_bindings()[
                "r010_successor_check_receipt"
            ],
        }
        and _strict_json_equal(
            evidence["responsibility_boundary"],
            ready25_role_boundary,
        )
        and _strict_json_equal(
            evidence["summary"],
            {
                "exact_artifact_count": len(
                    READY25_EXACT13
                ),
                "in_scope_count": len(READY25_EXACT13),
                "content_authored_count": len(
                    READY25_EXACT13
                ),
                "accepted_count": 0,
                "approval_count": 0,
                "execution_count": 0,
                "actual_event_count": 0,
                "formal_evidence_count": 0,
                "release_credit_count": 0,
            },
        )
        and _strict_json_equal(
            evidence["generated_output_bindings"],
            expected_cls_ops_output_bindings,
        )
        and len(evidence["artifact_contracts"]) == len(READY25_EXACT13)
        and [
            contract["artifact_type_id"]
            for contract in evidence["artifact_contracts"]
        ]
        == list(READY25_EXACT13)
        and all(
            set(contract)
            == ready25_cls_contract_keys
            | {"acceptance_contract"}
            | (
                {"incident_boundary"}
                if contract["artifact_type_id"] == "OPS-18"
                else {"handover_boundary"}
                if contract["artifact_type_id"] == "CLS-10"
                else {"service_lifecycle_boundary"}
                if contract["artifact_type_id"] == "CLS-16"
                else set()
            )
            and _strict_json_equal(
                {
                    key: contract[key]
                    for key in _ready25_contract(
                        contract["artifact_type_id"]
                    )
                },
                _ready25_contract(
                    contract["artifact_type_id"]
                ),
            )
            and contract["applicability"] == "IN_SCOPE"
            and contract["content_authored"] is True
            and contract["content_status"]
            == "PLAN_SCHEMA_CHECKLIST_AUTHORED"
            and contract["internal_checklist"]
            == READY25_INTERNAL_CHECKLISTS[
                contract["artifact_type_id"]
            ]
            and contract["activation_state"]
            == READY25_ACTIVATION_STATES[
                contract["artifact_type_id"]
            ]
            and _strict_json_equal(
                contract["claim_boundary"],
                zero_contract_claim,
            )
            and contract["acceptance_contract"]
            == {
                "required_contents": ready25_catalog[
                    contract["artifact_type_id"]
                ]["required_contents"],
                "completion_criteria": ready25_catalog[
                    contract["artifact_type_id"]
                ]["completion_criteria"],
            }
            and contract["blockers"]
            and all(
                blocker["status"] == "OPEN"
                for blocker in contract["blockers"]
            )
            and contract["responsibility_boundary"][
                "independent_qa_reviewer"
            ]
            is None
            and contract["responsibility_boundary"][
                "independent_qa_status"
            ]
            == "UNASSIGNED"
            and contract["responsibility_boundary"][
                "self_review_credit_allowed"
            ]
            is False
            and contract["fabricated_result_prohibited"] is True
            and "approval_status" not in contract
            and "release_eligible" not in contract
            for contract in evidence["artifact_contracts"]
        )
        and set(cls_ops_evidence_contracts)
        == set(READY25_EXACT13)
        and cls_ops_evidence_contracts["OPS-18"][
            "incident_boundary"
        ]
        == {
            "incident_count": 0,
            "trigger_status": "NOT_TRIGGERED",
            "zero_incidents_is_no_incident_evidence": False,
            "postmortem_status": "NOT_RUN",
        }
        and cls_ops_evidence_contracts["CLS-10"][
            "handover_boundary"
        ]
        == {
            "current_branch": "OPERATIONS_CONTINUE",
            "recipient": None,
            "operator": None,
            "handover_execution_status": "NOT_RUN",
        }
        and cls_ops_evidence_contracts["CLS-16"][
            "service_lifecycle_boundary"
        ]
        == {
            "current_branch": "OPERATIONS_CONTINUE",
            "shutdown_decision": None,
            "decommission_execution_status": "NOT_RUN",
        }
        and _strict_json_equal(
            evidence["claim_boundary"],
            {
                "actual_kpi_result_count": 0,
                "risk_acceptance_count": 0,
                "actual_handover_count": 0,
                "actual_data_transfer_or_deletion_count": 0,
                "actual_rotation_or_revocation_count": 0,
                "shutdown_or_decommission_count": 0,
                "live_dashboard_count": 0,
                "actual_restore_test_count": 0,
                "actual_dr_drill_count": 0,
                "qualifying_incident_count": 0,
                "postmortem_count": 0,
                "actual_cost_result_count": 0,
                "release_status": RELEASE_STATUS,
            },
        )
        and _strict_json_equal(
            evidence_integrity,
            {
                "algorithm": "SHA-256",
                "content_sha256": _object_sha(evidence_body),
            },
        ),
        "Ready25 evidence boundary or integrity differs",
    )
    receipt = json.loads(
        ready25_side_outputs[READY25_CLS_OPS_R003_RECEIPT_PATH]
    )
    receipt_integrity = receipt["integrity"]
    receipt_body = {key: value for key, value in receipt.items() if key != "integrity"}
    expected_ready25_checks = [
        {
            "check_id": "READY25-EXACT13-IN-SCOPE",
            "status": "PASS",
            "expected": len(READY25_EXACT13),
            "observed": len(READY25_EXACT13),
        },
        {
            "check_id": "READY25-EXACT13-CONTENT-AUTHORED",
            "status": "PASS",
            "expected": len(READY25_EXACT13),
            "observed": len(READY25_EXACT13),
        },
        {
            "check_id": (
                "READY25-NO-ACCEPTANCE-APPROVAL-EXECUTION-"
                "EVENT-FORMAL-RELEASE-CREDIT"
            ),
            "status": "PASS",
            "expected": [0, 0, 0, 0, 0, 0],
            "observed": [0, 0, 0, 0, 0, 0],
        },
        {
            "check_id": "READY25-OPS18-INCIDENT0-NOT-TRIGGERED",
            "status": "PASS",
            "expected": "NOT_TRIGGERED_INCIDENT_COUNT_0",
            "observed": READY25_ACTIVATION_STATES["OPS-18"],
        },
        {
            "check_id": "READY25-CLS16-OPERATIONS-CONTINUE",
            "status": "PASS",
            "expected": "OPERATIONS_CONTINUE",
            "observed": "OPERATIONS_CONTINUE",
        },
        {
            "check_id": "READY25-CLS10-RECIPIENT-UNRESOLVED",
            "status": "PASS",
            "expected": None,
            "observed": None,
        },
        {
            "check_id": "READY25-INDEPENDENT-QA-UNASSIGNED",
            "status": "PASS",
            "expected": None,
            "observed": None,
        },
    ]
    _require(
        set(receipt)
        == {
            "schema_version",
            "receipt_id",
            "prepared_on",
            "status",
            "evidence_binding",
            "source_bindings",
            "checks",
            "integrity",
        }
        and receipt["schema_version"]
        == "walksafe.phase1-ready25-cls-ops-check-receipt.v1"
        and receipt["prepared_on"] == READY25_AS_OF
        and "approval_status" not in receipt
        and "release_eligible" not in receipt
        and receipt["receipt_id"] == READY25_CLS_OPS_R003_RECEIPT_ID
        and receipt["status"] == "PASS"
        and _strict_json_equal(
            receipt["checks"],
            expected_ready25_checks,
        )
        and _strict_json_equal(
            receipt["evidence_binding"],
            _ready25_output_binding(
                READY25_CLS_OPS_R003_EVIDENCE_PATH,
                ready25_side_outputs[
                    READY25_CLS_OPS_R003_EVIDENCE_PATH
                ],
            ),
        )
        and receipt["source_bindings"] == _ready25_source_bindings()
        and _strict_json_equal(
            receipt_integrity,
            {
                "algorithm": "SHA-256",
                "content_sha256": _object_sha(receipt_body),
            },
        ),
        "Ready25 receipt binding or integrity differs",
    )
    rel_evidence = json.loads(
        ready25_side_outputs[READY25_REL_R002_EVIDENCE_PATH]
    )
    rel_evidence_integrity = rel_evidence["integrity"]
    rel_evidence_body = {
        key: value for key, value in rel_evidence.items() if key != "integrity"
    }
    expected_rel_bound_paths = {
        ARTIFACT_CHANGE_LOG_PATH,
        ARTIFACT_REGISTER_PATH,
        MANIFEST_PATH,
        REL_CONTROL_PATH,
        REL_REGISTER_PATH,
    }
    expected_rel_output_bindings = [
        _ready25_output_binding(
            path,
            (
                ready25_side_outputs[path]
                if path in ready25_side_outputs
                else outputs[path]
            ),
        )
        for path in sorted(expected_rel_bound_paths, key=_rel)
    ]
    expected_r007_rel_contracts = {
        item["artifact_type_id"]: item
        for item in _ready25_rel_r007_contracts()
    }
    _require(
        set(rel_evidence)
        == {
            "schema_version",
            "packet_id",
            "prepared_on",
            "predecessor_lineage",
            "scope_decision",
            "r007_subject_contracts",
            "r010_chain_binding",
            "artifact_contracts",
            "generated_output_bindings",
            "five_gate_boundary",
            "summary",
            "claim_boundary",
            "integrity",
        }
        and rel_evidence["schema_version"]
        == "walksafe.phase1-ready25-rel-evidence.v1"
        and rel_evidence["prepared_on"] == READY25_AS_OF
        and "approval_status" not in rel_evidence
        and "release_eligible" not in rel_evidence
        and rel_evidence["packet_id"] == READY25_REL_R002_PACKET_ID
        and _strict_json_equal(
            rel_evidence["predecessor_lineage"],
            _ready25_rel_predecessor_bindings(),
        )
        and rel_evidence["scope_decision"]
        == {
            "decision": "IN_SCOPE",
            "artifact_type_ids": list(READY25_REL_EXACT3),
            "user_scope_receipt": _ready25_source_bindings()[
                "user_scope_transition_receipt"
            ],
        }
        and rel_evidence["r010_chain_binding"]
        == {
            "semantics": (
                "ADD_ONLY_READY25_REL_SUCCESSOR_OVER_R010_"
                "WRAPPER_AND_R007_LEDGER_SUBJECT"
            ),
            "r010_evidence": _ready25_source_bindings()[
                "r010_successor_evidence"
            ],
            "r010_check_receipt": _ready25_source_bindings()[
                "r010_successor_check_receipt"
            ],
        }
        and _strict_json_equal(
            rel_evidence["summary"],
            {
                "exact_artifact_count": len(
                    READY25_REL_EXACT3
                ),
                "in_scope_count": len(
                    READY25_REL_EXACT3
                ),
                "content_authored_count": len(
                    READY25_REL_EXACT3
                ),
                "accepted_count": 0,
                "approval_count": 0,
                "execution_count": 0,
                "actual_event_count": 0,
                "formal_evidence_count": 0,
                "release_credit_count": 0,
            },
        )
        and _strict_json_equal(
            rel_evidence["generated_output_bindings"],
            expected_rel_output_bindings,
        )
        and _strict_json_equal(
            rel_evidence["r007_subject_contracts"],
            list(expected_r007_rel_contracts.values()),
        )
        and len(rel_evidence["artifact_contracts"])
        == len(READY25_REL_EXACT3)
        and [
            contract["artifact_type_id"]
            for contract in rel_evidence["artifact_contracts"]
        ]
        == list(READY25_REL_EXACT3)
        and all(
            set(contract)
            == ready25_rel_contract_keys
            | {
                "catalog_acceptance_contract",
                "r007_subject_and_acceptance_contract",
            }
            and _strict_json_equal(
                {
                    key: contract[key]
                    for key in _ready25_rel_contract(
                        contract["artifact_type_id"]
                    )
                },
                _ready25_rel_contract(
                    contract["artifact_type_id"]
                ),
            )
            and contract["applicability"] == "IN_SCOPE"
            and contract["content_authored"] is True
            and contract["content_status"]
            == "PLAN_SCHEMA_CHECKLIST_AUTHORED"
            and contract["internal_checklist"]
            == READY25_REL_INTERNAL_CHECKLISTS[
                contract["artifact_type_id"]
            ]
            and contract["activation_state"]
            == READY25_REL_ACTIVATION_STATES[
                contract["artifact_type_id"]
            ]
            and _strict_json_equal(
                contract["claim_boundary"],
                zero_contract_claim,
            )
            and contract["catalog_acceptance_contract"]
            == {
                "required_contents": ready25_catalog[
                    contract["artifact_type_id"]
                ]["required_contents"],
                "completion_criteria": ready25_catalog[
                    contract["artifact_type_id"]
                ]["completion_criteria"],
            }
            and _strict_json_equal(
                contract[
                    "r007_subject_and_acceptance_contract"
                ],
                expected_r007_rel_contracts[
                    contract["artifact_type_id"]
                ],
            )
            and contract["blockers"]
            and all(
                blocker["status"] == "OPEN"
                for blocker in contract["blockers"]
            )
            and contract["responsibility_boundary"][
                "independent_qa_reviewer"
            ]
            is None
            and contract["responsibility_boundary"][
                "independent_qa_status"
            ]
            == "UNASSIGNED"
            and contract["responsibility_boundary"][
                "self_review_credit_allowed"
            ]
            is False
            and contract["release_strategy"]
            == ready25_release_strategy
            and contract["five_gate_boundary"]
            == [
                {
                    "gate_id": gate_id,
                    "status": "NOT_RUN",
                    "waived": False,
                }
                for gate_id in GATE_IDS
            ]
            and contract[
                "fabricated_release_candidate_or_result_prohibited"
            ]
            is True
            and "approval_status" not in contract
            and "release_eligible" not in contract
            for contract in rel_evidence["artifact_contracts"]
        )
        and _strict_json_equal(
            rel_evidence["five_gate_boundary"],
            [
                {
                    "gate_id": gate_id,
                    "status": "NOT_RUN",
                    "waived": False,
                }
                for gate_id in GATE_IDS
            ],
        )
        and _strict_json_equal(
            rel_evidence["claim_boundary"],
            {
                "named_release_candidate_count": 0,
                "release_commit_or_tag_count": 0,
                "release_note_result_count": 0,
                "deployment_count": 0,
                "promotion_count": 0,
                "rollback_execution_count": 0,
                "release_status": RELEASE_STATUS,
            },
        )
        and _strict_json_equal(
            rel_evidence_integrity,
            {
                "algorithm": "SHA-256",
                "content_sha256": _object_sha(
                    rel_evidence_body
                ),
            },
        ),
        "Ready25 REL evidence boundary or integrity differs",
    )
    rel_receipt = json.loads(
        ready25_side_outputs[READY25_REL_R002_RECEIPT_PATH]
    )
    rel_receipt_integrity = rel_receipt["integrity"]
    rel_receipt_body = {
        key: value for key, value in rel_receipt.items() if key != "integrity"
    }
    expected_ready25_rel_checks = [
        {
            "check_id": (
                "READY25-REL-EXACT3-IN-SCOPE-CONTENT-AUTHORED"
            ),
            "status": "PASS",
            "expected": [3, 3],
            "observed": [3, 3],
        },
        {
            "check_id": "READY25-REL-NO-CREDIT",
            "status": "PASS",
            "expected": [0, 0, 0, 0, 0, 0],
            "observed": [0, 0, 0, 0, 0, 0],
        },
        {
            "check_id": (
                "READY25-REL-FIVE-GATES-NOT-RUN-NOT-WAIVED"
            ),
            "status": "PASS",
            "expected": len(GATE_IDS),
            "observed": len(GATE_IDS),
        },
        {
            "check_id": "READY25-REL-NAMED-CANDIDATE-ABSENT",
            "status": "PASS",
            "expected": None,
            "observed": None,
        },
    ]
    _require(
        set(rel_receipt)
        == {
            "schema_version",
            "receipt_id",
            "prepared_on",
            "status",
            "evidence_binding",
            "source_bindings",
            "checks",
            "integrity",
        }
        and rel_receipt["schema_version"]
        == "walksafe.phase1-ready25-rel-check-receipt.v1"
        and rel_receipt["prepared_on"] == READY25_AS_OF
        and "approval_status" not in rel_receipt
        and "release_eligible" not in rel_receipt
        and rel_receipt["receipt_id"] == READY25_REL_R002_RECEIPT_ID
        and rel_receipt["status"] == "PASS"
        and _strict_json_equal(
            rel_receipt["checks"],
            expected_ready25_rel_checks,
        )
        and _strict_json_equal(
            rel_receipt["evidence_binding"],
            _ready25_output_binding(
                READY25_REL_R002_EVIDENCE_PATH,
                ready25_side_outputs[
                    READY25_REL_R002_EVIDENCE_PATH
                ],
            ),
        )
        and rel_receipt["source_bindings"] == _ready25_rel_source_bindings()
        and _strict_json_equal(
            rel_receipt_integrity,
            {
                "algorithm": "SHA-256",
                "content_sha256": _object_sha(
                    rel_receipt_body
                ),
            },
        ),
        "Ready25 REL receipt binding or integrity differs",
    )
    scope = manifest["scope_artifact_type_ids"]
    materialized = manifest["materialized_artifact_type_ids"]
    planned = manifest["planned_artifact_type_ids"]
    _require(scope == SCOPE_ARTIFACT_TYPE_IDS, "manifest scope order differs")
    _require(materialized == MATERIALIZED_ARTIFACT_TYPE_IDS, "manifest materialized order differs")
    _require(planned == PLANNED_ARTIFACT_TYPE_IDS, "manifest planned order differs")
    _require(
        _strict_json_equal(
            manifest["materialization_summary"],
            {
                "scope_count": 62,
                "bundle_count": 9,
                "materialized_draft_count": 39,
                "planned_not_run_count": 23,
                "actual_execution_evidence_count": 0,
                "external_original_count": 0,
                "closure_result_count": 0,
            },
        ),
        "manifest materialization summary differs",
    )
    _require(not set(materialized) & set(planned), "manifest materialization overlap")
    _require(set(materialized) | set(planned) == set(scope), "manifest materialization union differs")
    _require(manifest["metadata"]["artifact_type_ids"] == materialized, "manifest metadata materialization differs")
    expected_generated_files = []
    for path, content in sorted(
        (
            (path, content)
            for path, content in outputs.items()
            if path != MANIFEST_PATH
        ),
        key=lambda item: _rel(item[0]),
    ):
        if path in DOCUMENT_COVERAGE:
            artifact_type_ids = _draft_codes_for(path)
            file_role = (
                "CANONICAL_DRAFT_OR_PLANNED_CONTRACT"
            )
            bundle_id = BUNDLE_BY_PATH[path]
        else:
            artifact_type_ids = SUPPORTING_ARTIFACT_IDS[path]
            file_role = (
                "SUPPORTING_DRAFT_REGISTER_OR_NON_EVIDENCE_"
                "TEMPLATE"
            )
            bundle_id = None
        expected_generated_files.append(
            {
                "path": _rel(path),
                "sha256": _sha_bytes(content),
                "byte_length": len(content),
                "file_role": file_role,
                "bundle_id": bundle_id,
                "artifact_type_ids": artifact_type_ids,
            }
        )
    _require(
        _strict_json_equal(
            manifest["generated_files"],
            expected_generated_files,
        ),
        "manifest generated-files projection differs",
    )
    declared_codes = [
        code
        for file_entry in manifest["generated_files"]
        for code in file_entry["artifact_type_ids"]
    ]
    _require(
        len(declared_codes) == len(set(declared_codes)) == len(materialized)
        and set(declared_codes) == set(materialized),
        "generated_files Draft declaration differs",
    )
    _require(not set(declared_codes) & set(planned), "generated_files declares a Planned code")
    _require(
        manifest["source_bindings"].get(
            "approved_project_answers"
        )
        == _binding(PROJECT_ANSWERS_PATH)
        and _strict_json_equal(
            manifest["approved_project_facts_applied"],
            _project_facts(),
        ),
        "approved project-answer binding or normalized facts differ",
    )
    _require(
        {
            key: manifest["source_bindings"][key]
            for key in _w9_source_bindings()
        }
        == _w9_source_bindings(),
        "W9 source bindings differ",
    )
    w9 = manifest["w9_content_assessment"]
    _require(
        w9["ok_candidate_content_completeness"] == W9_OK_CANDIDATE_CONTENT_COMPLETENESS
        and w9["external_after_internal"] == W9_EXTERNAL_AFTER_INTERNAL
        and w9["contracts"] == W9_LIFECYCLE_CONTRACTS
        and set(w9["contracts"]) == set(W9_OK_CANDIDATE_CONTENT_COMPLETENESS + W9_EXTERNAL_AFTER_INTERNAL),
        "W9 content outcome or contract coverage differs",
    )
    external_contracts = w9["external_execution_contracts"]
    _require(
        [item["artifact_type_id"] for item in external_contracts] == W9_EXTERNAL_AFTER_INTERNAL
        and len({item["stable_id"] for item in external_contracts}) == 7
        and all(item == W9_EXTERNAL_EXECUTION_CONTRACTS[item["artifact_type_id"]] for item in external_contracts),
        "W9 external7 stable contract parity differs",
    )
    _require(
        all(
            item["responsible_role"]
            and item["approver_role"]
            and item["external_trigger"]
            and item["external_authority"]
            and item["required_inputs"]
            and item["internal_action"]
            and item["procedure"]
            and item["evidence_schema"]["catalog_fields"]
            and item["evidence_schema"]["lifecycle_fields"]
            == W9_LIFECYCLE_CONTRACTS[item["artifact_type_id"]]["record_fields"]
            and item["receipt_schema"] == W9_RECEIPT_BINDING_FIELDS
            and len(item["completion_test"]) == 8
            and item["due_and_review"]["date_status"] == "UNASSIGNED_UNTIL_REAL_TRIGGER"
            and item["release_impact"]
            and item["fake_event_or_receipt_allowed"] is False
            for item in external_contracts
        ),
        "W9 external7 required execution-contract fields differ",
    )
    _require(
        all(
            item["current_state"]["actual_event_ids"] == []
            and item["current_state"]["actual_evidence_ids"] == []
            and item["current_state"]["actual_receipt_ids"] == []
            and item["current_state"]["actual_event_count"] == 0
            and item["current_state"]["actual_evidence_count"] == 0
            and item["current_state"]["actual_receipt_count"] == 0
            and item["current_state"]["execution_status"] == "NOT_RUN"
            and item["current_state"]["approval_status"] == "NOT_APPROVED"
            and item["current_state"]["acceptance_status"] == "NOT_APPROVED"
            and item["current_state"]["recipient_state"] == "UNASSIGNED"
            and item["current_state"]["operator_state"] == "UNASSIGNED"
            for item in external_contracts
        ),
        "W9 external7 current no-event/no-receipt boundary differs",
    )
    _require(
        w9["boundary"]["content_completeness_is_approval"] is False
        and w9["boundary"]["content_completeness_is_execution"] is False
        and w9["boundary"]["risk_acceptance_status"] == "NOT_APPROVED"
        and w9["boundary"]["actual_incident_or_change_receipt_count"] == 0
        and w9["boundary"]["actual_handover_deletion_rotation_decommission_receipt_count"] == 0,
        "W9 authorization boundary differs",
    )
    _require(
        manifest["known_open_issues"][0]["status"] == FP035_NORMALIZATION_STATUS
        and manifest["known_open_issues"][0]["normalized_policy"] == FP035_NORMALIZED_POLICY
        and manifest["known_open_issues"][0]["related_test_status"] == "NOT_RUN"
        and manifest["known_open_issues"][0]["correction_candidate_binding"]
        == _binding(FP035_CORRECTION_CANDIDATE_PATH)
        and manifest["known_open_issues"][0]["correction_candidate_approval_status"]
        == "NOT_APPROVED"
        and manifest["known_open_issues"][0]["correction_candidate_effective_status"]
        == "NOT_EFFECTIVE"
        and manifest["known_open_issues"][0]["policy_effect_claimed"] is False,
        "manifest FP-035 normalization boundary differs",
    )
    for file_entry in manifest["generated_files"]:
        path = REPO_ROOT / file_entry["path"]
        content = outputs[path]
        _require(file_entry["sha256"] == _sha_bytes(content), f"generated file hash differs: {path}")
        _require(file_entry["byte_length"] == len(content), f"generated file length differs: {path}")
    _require(len(manifest["artifact_location_index"]) == 62, "artifact location index count differs")
    expected_planned_evidence_contracts = [
        {
            "artifact_type_id": code,
            "title": ready25_catalog[code]["title"],
            "status": "PLANNED",
            "verification_status": "NOT_RUN",
            "reason": _planned_reason(
                code,
                ready25_catalog,
            ),
            "activation_condition": ready25_catalog[code][
                "activation_condition"
            ],
            "executor_role": ready25_catalog[code][
                "owner_role"
            ],
            "reviewer_roles": ready25_catalog[code][
                "reviewer_roles"
            ],
            "approver_role": ready25_catalog[code][
                "approver_role"
            ],
            "prerequisites": ready25_catalog[code][
                "required_inputs"
            ],
            "required_evidence": ready25_catalog[code][
                "required_contents"
            ],
            "judgment_criteria": ready25_catalog[code][
                "completion_criteria"
            ],
            "evidence_record_rule": _execution_contract(
                code,
                ready25_catalog[code],
            )["evidence_rule"],
            "canonical_path": next(
                _rel(path)
                for path, codes in DOCUMENT_COVERAGE.items()
                if code in codes
            ),
            "anchor": code.lower(),
            "evidence_ids": [],
        }
        for code in PLANNED_ARTIFACT_TYPE_IDS
    ]
    _require(
        _strict_json_equal(
            manifest["planned_evidence_contracts"],
            expected_planned_evidence_contracts,
        ),
        "planned evidence contracts differ",
    )
    _require(
        all(
            item["status"] == "PLANNED"
            and item["verification_status"] == "NOT_RUN"
            and not item["evidence_ids"]
            and item["executor_role"]
            and item["prerequisites"]
            and item["required_evidence"]
            and item["judgment_criteria"]
            for item in manifest["planned_evidence_contracts"]
        ),
        "planned evidence boundary differs",
    )
    _require(
        _strict_json_equal(
            manifest["remaining_gates"],
            _gate_rows(policy_contract),
        ),
        "manifest gate boundary differs",
    )

    release_register = json.loads(outputs[REL_REGISTER_PATH])
    _require(release_register["formal_release_candidate_count"] == 0, "release candidate count must be zero")
    _require(not release_register["release_artifacts"] and not release_register["release_approvals"], "release evidence must be empty")
    _require(release_register["known_issues"][0]["issue_id"] == FP035_ISSUE_ID, "FP-035 issue missing")
    _require(
        release_register["known_issues"][0]["status"] == FP035_NORMALIZATION_STATUS
        and release_register["known_issues"][0]["owner_clarification_required"] is False
        and release_register["known_issues"][0]["normalized_policy"] == FP035_NORMALIZED_POLICY
        and release_register["known_issues"][0]["related_test_status"] == "NOT_RUN"
        and release_register["known_issues"][0]["correction_candidate_binding"]
        == _binding(FP035_CORRECTION_CANDIDATE_PATH)
        and release_register["known_issues"][0]["correction_candidate_approval_status"]
        == "NOT_APPROVED"
        and release_register["known_issues"][0]["correction_candidate_effective_status"]
        == "NOT_EFFECTIVE"
        and release_register["known_issues"][0]["policy_effect_claimed"] is False,
        "FP-035 normalization boundary differs",
    )
    _require(
        release_register["approved_stage_roadmap"]["controlled_demo_date"] == "2026-07-26"
        and release_register["approved_stage_roadmap"]["controlled_demo_is_release"] is False
        and release_register["formal_release_candidate_count"] == 0,
        "approved roadmap and actual release boundary were mixed",
    )

    deployment = json.loads(outputs[REL_DEPLOYMENT_TEMPLATE_PATH])
    _require(deployment["template_only"] and not deployment["is_live_evidence"], "deployment template boundary differs")
    _require(deployment["execution_status"] == "NOT_RUN" and deployment["result"] is None, "deployment result is overstated")
    _require(
        deployment["precondition"]["tst_22_required_decisions_for_rel_13_and_rel_14"] == ["GO", "CONDITIONAL_GO"]
        and deployment["precondition"]["tst_22_actual_decision"] == "NOT_RUN"
        and deployment["precondition"]["eligible_to_execute_rel_13_or_rel_14"] is False,
        "REL-13/14 TST-22 gate differs",
    )
    for path in (REL_EVIDENCE_TEMPLATE_PATH, REL_ACCEPTANCE_TEMPLATE_PATH, OPS_INCIDENT_TEMPLATE_PATH, OPS_RECOVERY_TEMPLATE_PATH, CLS_EXECUTION_TEMPLATE_PATH):
        template = json.loads(outputs[path])
        _require(template["template_only"] is True, f"template marker missing: {path}")

    operations = json.loads(outputs[OPS_REGISTER_PATH])
    _require(operations["service_ownership"]["operating_model"] == "SINGLE_ADMIN", "single-admin policy differs")
    _require(operations["service_ownership"]["assigned_person"] is None, "fabricated administrator identity")
    _require(
        operations["service_ownership"]["assigned_role"] == "PROJECT_OWNER_SINGLE_ADMIN"
        and operations["service_ownership"]["source_answer_id"] == "Q-OPS-001"
        and operations["service_ownership"]["assignment_status"] == "ROLE_CONFIRMED_IDENTITY_NOT_STORED",
        "approved single-admin role was not applied",
    )
    _require(operations["service_ownership"]["shared_password_allowed"] is False, "shared password allowed")
    server = operations["server_capacity_policy"]
    _require(server["scope"] == "SERVER_ONLY", "server capacity scope differs")
    _require(server["primary_original_capacity_gib"] == 300 and server["separate_backup_capacity_gib"] == 300, "server capacities differ")
    _require([item["percent"] for item in server["thresholds"]] == [70, 85, 95, 100], "server thresholds differ")
    _require(server["delete_unexpired_originals_for_cost"] is False, "unexpired originals may be cost-deleted")
    phone = operations["phone_queue_policy"]
    _require(phone["scope"] == "PHONE_ONLY_SEPARATE_FROM_SERVER_PERCENTAGES", "phone/server capacity scopes mixed")
    _require(phone["byte_limit"] is None and phone["limit_status"] == "NOT_RUN", "phone byte limit fabricated")
    _require(operations["backup_policy"]["retention_days"] == 35, "backup retention differs")
    _require(operations["backup_policy"]["restore_execution_count"] == 0, "restore execution overstated")
    _require(not operations["incidents"] and not operations["operation_changes"] and not operations["data_disposition_executions"], "operational execution invented")
    _require(
        operations["opening_snapshot"]["operations_started"] is False
        and operations["opening_snapshot"]["proves_no_incidents"] is False
        and operations["opening_snapshot"]["proves_no_changes"] is False,
        "empty opening snapshot was treated as no-event evidence",
    )
    _require(
        set(operations["w9_content_contracts"]) == {"OPS-17", "OPS-19", "OPS-20", "OPS-21", "OPS-24"}
        and all(item["record_fields"] for item in operations["w9_content_contracts"].values())
        and all(item["actual_receipt_ids"] == [] for item in operations["w9_content_contracts"].values()),
        "W9 operations lifecycle/schema contract differs",
    )
    _require(
        operations["external_execution_contracts"]
        == [W9_EXTERNAL_EXECUTION_CONTRACTS[code] for code in ("OPS-17", "OPS-19")],
        "W9 operations external contract projection differs",
    )
    _require(
        all(
            item["owner_role"]
            and item["due_condition"]
            and item["completion_criteria"]
            and item["evidence_refs"] == []
            and item["waiver_status"] == "NOT_APPROVED"
            and item["approval_status"] == "NOT_APPROVED"
            and item["receipt_ref"] is None
            for item in operations["maintenance_backlog"]
        ),
        "OPS-20 backlog lifecycle fields differ",
    )
    _require(
        all(
            item["impact"]
            and item["priority"]
            and item["owner_role"]
            and item["due_condition"]
            and item["closure_criteria"]
            and item["risk_acceptance_status"] == "NOT_APPROVED"
            and item["acceptance_receipt_ref"] is None
            for item in operations["technical_debt"]
        ),
        "OPS-21 technical-debt fields or risk-acceptance boundary differ",
    )
    _require(
        all(
            item["owner_role"]
            and item["review_due_condition"]
            and item["monitoring_signals"]
            and item["quota_and_failure_validation"] == "NOT_RUN"
            and item["provider_exit_status"] == "NOT_RUN"
            and item["alternate_validation_status"] == "NOT_RUN"
            and item["evidence_refs"] == []
            and item["receipt_ref"] is None
            for item in operations["external_dependencies"]
        ),
        "OPS-24 dependency lifecycle/schema fields differ",
    )

    closure = json.loads(outputs[CLS_REGISTER_PATH])
    boundary = closure["approval_boundary"]
    _require(closure["project_status"] == "ACTIVE_NOT_CLOSED", "project status overstated")
    _require(not closure["closure_event_started"], "closure event invented")
    _require(
        boundary["project_completed"] is False
        and boundary["project_closed"] is False
        and boundary["final_acceptance_signed"] is False
        and boundary["service_decommissioned"] is False
        and boundary["risk_acceptance_approved"] is False
        and boundary["actual_handover_claimed"] is False
        and boundary["actual_data_transfer_or_deletion_claimed"] is False
        and boundary["actual_secret_rotation_or_revocation_claimed"] is False
        and boundary["closure_result_count"] == 0,
        "closure result boundary differs",
    )
    _require(
        set(closure["w9_content_contracts"]) == {"CLS-07", "CLS-08", "CLS-09", "CLS-10", "CLS-14", "CLS-15", "CLS-16"}
        and all(item["record_fields"] for item in closure["w9_content_contracts"].values())
        and all(item["actual_receipt_ids"] == [] for item in closure["w9_content_contracts"].values()),
        "W9 closure lifecycle/schema contract differs",
    )
    _require(
        closure["external_execution_contracts"]
        == [
            W9_EXTERNAL_EXECUTION_CONTRACTS[code]
            for code in ("CLS-08", "CLS-10", "CLS-14", "CLS-15", "CLS-16")
        ],
        "W9 closure external contract projection differs",
    )
    _require(
        closure["unresolved_defects"]["opening_snapshot_proves_no_defects"] is False
        and closure["residual_risks"]["risk_acceptance_status"] == "NOT_APPROVED"
        and closure["residual_risks"]["risk_acceptance_records"] == []
        and closure["handover"]["recipient"] is None
        and closure["handover"]["operator"] is None
        and closure["handover"]["approval_status"] == "NOT_APPROVED"
        and closure["handover"]["execution_status"] == "NOT_RUN"
        and closure["handover"]["actual_handover_receipts"] == [],
        "CLS-07/08/10 opening or external boundary differs",
    )
    _require(
        closure["data_disposition"]["actual_transfer_status"] == "NOT_RUN"
        and closure["data_disposition"]["actual_deletion_status"] == "NOT_RUN"
        and closure["data_disposition"]["legal_basis_status"] == "NOT_APPROVED"
        and closure["data_disposition"]["actual_receipt_ids"] == []
        and closure["access_secret_infrastructure_disposition"]["secret_values_allowed"] is False
        and closure["access_secret_infrastructure_disposition"]["transfer_status"] == "NOT_RUN"
        and closure["access_secret_infrastructure_disposition"]["rotation_status"] == "NOT_RUN"
        and closure["access_secret_infrastructure_disposition"]["revocation_status"] == "NOT_RUN"
        and closure["decommissioning"]["execution_status"] == "NOT_RUN"
        and closure["decommissioning"]["provider_exit_test_status"] == "NOT_RUN",
        "CLS-14/15/16 execution boundary differs",
    )

    all_markdown = "\n".join(outputs[path].decode("utf-8") for path in DOCUMENT_COVERAGE)
    for required_text in (
        "Android 사용자 앱 + 별도 비공개 Android 관리자 앱",
        "Web/PWA",
        FP035_ISSUE_ID,
        "주 원본 300 GiB",
        "70%는 관리자 경고",
        "85%는 신규 현장시험 참여자 추가 중단",
        "95%는 만료자료 정리 뒤 새 원본수집 세션 보류",
        "100%는 새 학습자료·자동신고 후보 생성을 조용히 보류",
        "backup은 35일 순환",
        "TST-22가 Go 또는 Conditional Go",
        "프로젝트가 실제로 끝나기 전",
    ):
        _require(required_text in all_markdown, f"critical policy text missing: {required_text}")
    for code in W9_OK_CANDIDATE_CONTENT_COMPLETENESS + W9_EXTERNAL_AFTER_INTERNAL:
        path = next(path for path, codes in DOCUMENT_COVERAGE.items() if code in codes)
        section = outputs[path].decode("utf-8").split(f'<a id="{code.lower()}"></a>', 1)[1]
        section = section.split('<a id="', 1)[0]
        _require("W9 내용 완전성·lifecycle·schema 계약" in section, f"W9 markdown contract missing: {code}")
        _require(W9_LIFECYCLE_CONTRACTS[code]["content_outcome"] in section, f"W9 outcome missing: {code}")
        _require("실제 ID `[]`" in section, f"W9 empty receipt boundary missing: {code}")
        if code in W9_EXTERNAL_EXECUTION_CONTRACTS:
            external_contract = W9_EXTERNAL_EXECUTION_CONTRACTS[code]
            stable_id = external_contract["stable_id"]
            _require(
                f"<!-- W9-EXTERNAL-CONTRACT-START {stable_id} -->" in section
                and f"<!-- W9-EXTERNAL-CONTRACT-END {stable_id} -->" in section
                and json.dumps(external_contract, ensure_ascii=False, indent=2, sort_keys=True) in section,
                f"W9 external contract lossless markdown projection differs: {code}",
            )
    generated6_text = "\n".join(
        outputs[path].decode("utf-8")
        for path in (
            OPS_CONTROL_PATH,
            OPS_REGISTER_PATH,
            CLS_HANDOVER_PATH,
            CLS_DECOMMISSION_PATH,
            CLS_REGISTER_PATH,
            MANIFEST_PATH,
        )
    )
    for forbidden_claim in (
        '"execution_status": "COMPLETED"',
        '"approval_status": "APPROVED"',
        '"acceptance_status": "APPROVED"',
        '"actual_receipt_count": 1',
        '"operations_started": true',
        '"recipient_state": "ASSIGNED"',
        '"operator_state": "ASSIGNED"',
    ):
        _require(forbidden_claim not in generated6_text, f"forbidden completion claim: {forbidden_claim}")
    _require(manifest["source_bindings"] == _source_bindings(), "manifest source bindings are stale")
    body = {key: value for key, value in manifest.items() if key != "manifest_content_sha256"}
    _require(manifest["manifest_content_sha256"] == _object_sha(body), "manifest content digest differs")
    _require(
        outputs == _build_outputs(),
        "generated output deterministic projection differs",
    )
    _require(
        ready25_side_outputs == expected_ready25_side_outputs,
        "Ready25 side output deterministic projection differs",
    )


def _require_add_only_successor_targets_absent(
    paths: Iterable[Path] = ADD_ONLY_SUCCESSOR_OUTPUT_PATHS,
) -> None:
    for path in paths:
        if path.exists() or path.is_symlink():
            try:
                display_path = _rel(path)
            except ValueError:
                display_path = path.as_posix()
            raise RelOpsClsError(
                f"add-only successor target already exists: {display_path}"
            )


def _write_outputs_transactionally(
    all_outputs: dict[Path, bytes],
    add_only_paths: Iterable[Path] = ADD_ONLY_SUCCESSOR_OUTPUT_PATHS,
    staging_parent: Path = REPO_ROOT,
) -> None:
    add_only = set(add_only_paths)
    mutable_updates = {
        path: content
        for path, content in all_outputs.items()
        if path not in add_only
        and (not path.is_file() or path.read_bytes() != content)
    }
    successor_outputs = {
        path: content
        for path, content in all_outputs.items()
        if path in add_only
    }
    _require(
        set(successor_outputs) == add_only,
        "transaction add-only output set differs",
    )
    original_bytes = {
        path: path.read_bytes() if path.is_file() else None
        for path in mutable_updates
    }
    original_modes = {
        path: (path.stat().st_mode & 0o777) if path.exists() else 0o644
        for path in mutable_updates
    }
    staged: dict[Path, Path] = {}
    staged_identities: dict[Path, tuple[int, int]] = {}
    published_successors: list[Path] = []
    replaced_mutable: list[Path] = []
    with tempfile.TemporaryDirectory(
        prefix=".walksafe-ready25-",
        dir=staging_parent,
    ) as temporary_directory:
        staging_directory = Path(temporary_directory)
        for index, (path, content) in enumerate(
            (mutable_updates | successor_outputs).items()
        ):
            path.parent.mkdir(parents=True, exist_ok=True)
            staged_path = staging_directory / f"{index:03d}.staged"
            staged_path.write_bytes(content)
            staged_path.chmod(
                original_modes.get(path, 0o644)
            )
            staged[path] = staged_path
            staged_stat = staged_path.stat()
            staged_identities[path] = (
                staged_stat.st_dev,
                staged_stat.st_ino,
            )
        try:
            for path in mutable_updates:
                os.replace(staged[path], path)
                replaced_mutable.append(path)
            for path in successor_outputs:
                os.link(staged[path], path)
                published_successors.append(path)
        except BaseException as exc:
            rollback_errors: list[str] = []

            def target_has_staged_identity(path: Path) -> bool:
                try:
                    target_stat = path.stat(follow_symlinks=False)
                except FileNotFoundError:
                    return False
                return (
                    target_stat.st_dev,
                    target_stat.st_ino,
                ) == staged_identities[path]

            owned_successors = [
                path
                for path in successor_outputs
                if target_has_staged_identity(path)
            ]
            owned_mutable = [
                path
                for path in mutable_updates
                if target_has_staged_identity(path)
            ]
            changed_successors = [
                path
                for path in published_successors
                if path not in owned_successors
                and (path.exists() or path.is_symlink())
            ]
            changed_mutable = [
                path
                for path in replaced_mutable
                if path not in owned_mutable
            ]
            for path in reversed(owned_successors):
                try:
                    path.unlink(missing_ok=True)
                except OSError as rollback_exc:
                    rollback_errors.append(f"{path}: {rollback_exc}")
            for path in reversed(owned_mutable):
                try:
                    previous = original_bytes[path]
                    if previous is None:
                        path.unlink(missing_ok=True)
                        continue
                    rollback_path = (
                        staging_directory
                        / f"rollback-{len(rollback_errors):03d}.staged"
                    )
                    rollback_path.write_bytes(previous)
                    rollback_path.chmod(original_modes[path])
                    os.replace(rollback_path, path)
                except OSError as rollback_exc:
                    rollback_errors.append(f"{path}: {rollback_exc}")
            rollback_errors.extend(
                f"{path}: successor target changed during rollback"
                for path in changed_successors
            )
            rollback_errors.extend(
                f"{path}: mutable target changed during rollback"
                for path in changed_mutable
            )
            if rollback_errors:
                raise RelOpsClsError(
                    "Ready25 transaction failed and rollback was incomplete: "
                    + "; ".join(rollback_errors)
                ) from exc
            raise


@contextmanager
def _ready25_writer_lock() -> Iterable[None]:
    lock_fd = os.open(
        REPO_ROOT,
        os.O_RDONLY | os.O_DIRECTORY,
    )
    acquired = False
    try:
        try:
            fcntl.flock(
                lock_fd,
                fcntl.LOCK_EX | fcntl.LOCK_NB,
            )
        except BlockingIOError as exc:
            raise RelOpsClsError(
                "Ready25 writer lock is already held"
            ) from exc
        acquired = True
        yield
    finally:
        if acquired:
            fcntl.flock(lock_fd, fcntl.LOCK_UN)
        os.close(lock_fd)


def generate() -> dict[Path, bytes]:
    _require_add_only_successor_targets_absent()
    with _ready25_writer_lock():
        _require_add_only_successor_targets_absent()
        outputs = _build_outputs()
        side_outputs = _ready25_side_outputs(outputs)
        _validate_outputs(outputs, side_outputs)
        _write_outputs_transactionally(outputs | side_outputs)
        return outputs


def check() -> dict[Path, bytes]:
    with _ready25_writer_lock():
        outputs = _build_outputs()
        side_outputs = _ready25_side_outputs(outputs)
        _validate_outputs(outputs, side_outputs)
        for path, expected in (outputs | side_outputs).items():
            _require(
                path.is_file(),
                f"generated output is missing: {_rel(path)}",
            )
            _require(
                path.read_bytes() == expected,
                f"generated output is stale: {_rel(path)}",
            )
        return outputs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify committed outputs without rewriting")
    args = parser.parse_args()
    try:
        outputs = check() if args.check else generate()
    except (RelOpsClsError, OSError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    action = "verified" if args.check else "generated"
    print(
        f"{action} {len(outputs)} REL/OPS/CLS files; "
        f"Draft={len(MATERIALIZED_ARTIFACT_TYPE_IDS)}, "
        f"Planned/NOT_RUN={len(PLANNED_ARTIFACT_TYPE_IDS)}, "
        f"release={RELEASE_STATUS}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
