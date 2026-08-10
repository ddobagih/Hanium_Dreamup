#!/usr/bin/env python3
"""Build the add-only final 257-artifact audit successor package."""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
from typing import Any, Iterable


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
BASELINE_PATH = (
    REPO_ROOT
    / "docs/control/execution/artifact-audits/20260726/artifact-audit-baseline.json"
)
WAVE_PLAN_PATH = (
    REPO_ROOT
    / "docs/control/execution/artifact-audits/20260726/artifact-remediation-wave-plan.json"
)
REMEDIATION_ROOT = (
    REPO_ROOT / "docs/control/execution/artifact-remediation/20260726"
)
OUTPUT_DIR = (
    REPO_ROOT
    / "docs/control/execution/artifact-audits/20260727/final-257"
)
SNAPSHOT_PATH = OUTPUT_DIR / "artifact-audit-successor-snapshot.json"
SNAPSHOT_MD_PATH = OUTPUT_DIR / "artifact-audit-successor-snapshot.md"
EXTERNAL_PACKET_PATH = OUTPUT_DIR / "external-action-packet.json"
MANIFEST_PATH = OUTPUT_DIR / "final-audit-manifest.json"

WAVE_IDS = tuple(f"W{number}" for number in range(1, 10))
STATUS_ORDER = ("OK", "INTERNAL_GAP", "EXTERNAL", "N_A_CANDIDATE")
INITIAL_COUNTS = {
    "OK": 59,
    "INTERNAL_GAP": 123,
    "EXTERNAL": 39,
    "N_A_CANDIDATE": 36,
    "TOTAL": 257,
}
FINAL_COUNTS = {
    "OK": 124,
    "INTERNAL_GAP": 48,
    "EXTERNAL": 49,
    "N_A_CANDIDATE": 36,
    "TOTAL": 257,
}
FINAL_DELTA = {
    "OK": 65,
    "INTERNAL_GAP": -75,
    "EXTERNAL": 10,
    "N_A_CANDIDATE": 0,
    "TOTAL": 0,
}

EXPECTED_W9_RECEIPT_SHA256 = (
    "d06abb36b55190b9219cc95011b60a552013d0cc79788a8c950b11698e051fb8"
)
W9_GO_SEAL_RELATIVE_PATH = (
    "docs/control/execution/artifact-remediation/20260726/w9/"
    "central-final-sealing-review.md"
)
EXPECTED_W9_GO_SEAL_SHA256 = (
    "f26637765d92e736e527d2ee7ba258a38c07dc21e265c832b75407f727850ce9"
)
HISTORICAL_W9_RECEIPT_SHA256 = (
    "88f0114458e6ce0fda969cb8aabb74f603151681d95fc996d6b8ad4ce8685cc9"
)
HISTORICAL_CENTRAL_NO_GO_PATH = (
    REMEDIATION_ROOT / "w9" / "central-sealing-review.md"
)
EXPECTED_HISTORICAL_CENTRAL_NO_GO_SHA256 = (
    "410c25430df668a208885f1ddf29921d0e28c18942ff97ea33f1bb3db9daef30"
)
PREDECESSOR_BRIDGE_PATHS = {
    "W3": (
        REMEDIATION_ROOT
        / "w3/evidence-20260726-003/source-snapshot.json",
        REMEDIATION_ROOT / "w3/engineering-trace-current-state.json",
    ),
    "W4": (
        REMEDIATION_ROOT / "w4/test-trace-current-state.json",
    ),
}

CONTENT_FINGERPRINT_RULE = (
    "UTF-8 JSON, ensure_ascii=false, sort_keys=true, compact separators, "
    "integrity.content_fingerprint.value=null, trailing LF"
)
SOURCE_SET_RULE = (
    "repository-relative path sort; [{path,sha256,byte_length}]; UTF-8 JSON; "
    "ensure_ascii=false; sort_keys=true; compact separators; trailing LF"
)
SEMANTIC_SCHEMA = "walksafe.final-257-successor.semantic.v1"
SEMANTIC_FIELDS = [
    "projection_schema",
    "artifact_status_tuples",
    "internal_action_membership",
    "external_action_membership",
    "replay_epoch_counts",
    "global_boundary",
]


class FinalAuditError(ValueError):
    """Raised when an authority input or generated output is inconsistent."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise FinalAuditError(message)


def _reject_constant(value: str) -> None:
    raise FinalAuditError(f"non-standard JSON number is not allowed: {value}")


def _object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        _require(key not in value, f"duplicate JSON key: {key}")
        value[key] = item
    return value


def _load_json(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    _require(not raw.startswith(b"\xef\xbb\xbf"), f"UTF-8 BOM is not allowed: {path}")
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_object_pairs,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise FinalAuditError(f"invalid JSON: {path}: {exc}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def _sha_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _rel(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _raw_binding(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    return {
        "path": _rel(path),
        "sha256": _sha_bytes(raw),
        "byte_length": len(raw),
    }


def _canonical_bytes(value: Any) -> bytes:
    return (
        json.dumps(
            value,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n"
    ).encode("utf-8")


def _json_bytes(value: Any) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _md_bytes(value: str) -> bytes:
    return (value.rstrip() + "\n").encode("utf-8")


def _with_content_fingerprint(value: dict[str, Any]) -> dict[str, Any]:
    integrity = value.setdefault("integrity", {})
    integrity["content_fingerprint"] = {
        "algorithm": "SHA-256",
        "canonicalization": CONTENT_FINGERPRINT_RULE,
        "value": None,
    }
    digest = _sha_bytes(_canonical_bytes(value))
    integrity["content_fingerprint"]["value"] = digest
    return value


def _validate_content_fingerprint(value: dict[str, Any]) -> None:
    fingerprint = value.get("integrity", {}).get("content_fingerprint", {})
    expected = fingerprint.get("value")
    _require(isinstance(expected, str) and len(expected) == 64, "content fingerprint missing")
    candidate = json.loads(json.dumps(value, ensure_ascii=False))
    candidate["integrity"]["content_fingerprint"]["value"] = None
    _require(
        _sha_bytes(_canonical_bytes(candidate)) == expected,
        "content fingerprint differs",
    )


def _normalize_status(value: str) -> str:
    normalized = value.replace("N/A_CANDIDATE", "N_A_CANDIDATE")
    _require(normalized in STATUS_ORDER, f"unknown classification: {value}")
    return normalized


def _counts(status_by_id: dict[str, str]) -> dict[str, int]:
    counter = Counter(status_by_id.values())
    result = {status: counter.get(status, 0) for status in STATUS_ORDER}
    result["TOTAL"] = len(status_by_id)
    return result


def _delta_counts(before: dict[str, int], after: dict[str, int]) -> dict[str, int]:
    return {
        **{status: after[status] - before[status] for status in STATUS_ORDER},
        "TOTAL": after["TOTAL"] - before["TOTAL"],
    }


def _normalize_count_record(value: dict[str, Any]) -> dict[str, int]:
    normalized: dict[str, int] = {}
    for key, item in value.items():
        status = key.replace("N/A_CANDIDATE", "N_A_CANDIDATE")
        if status in (*STATUS_ORDER, "TOTAL"):
            _require(isinstance(item, int) and not isinstance(item, bool), f"invalid count: {key}")
            normalized[status] = item
    _require(set(STATUS_ORDER).issubset(normalized), "classification count record is incomplete")
    normalized.setdefault("TOTAL", sum(normalized[status] for status in STATUS_ORDER))
    return normalized


def _iter_dicts(value: Any) -> Iterable[dict[str, Any]]:
    if isinstance(value, dict):
        yield value
        for item in value.values():
            yield from _iter_dicts(item)
    elif isinstance(value, list):
        for item in value:
            yield from _iter_dicts(item)


def _find_raw_binding(value: Any, path: Path, binding: dict[str, Any]) -> bool:
    relative = _rel(path)
    for item in _iter_dicts(value):
        if item.get("path") != relative:
            continue
        digest = item.get("sha256", item.get("file_sha256"))
        length = item.get("byte_length", item.get("byte_count"))
        if digest == binding["sha256"] and (
            length is None or length == binding["byte_length"]
        ):
            return True
    return False


def _artifact_ids(value: dict[str, Any]) -> list[str]:
    for container_key in ("scope", "wave", "completion_scope"):
        container = value.get(container_key)
        if isinstance(container, dict) and isinstance(container.get("exact_artifact_ids"), list):
            return container["exact_artifact_ids"]
    return []


def _dlv(values: list[str]) -> list[str]:
    return [f"DLV-{value}" for value in values]


def _internal_group(
    group_id: str,
    title: str,
    codes: list[str],
    owner_role: str,
    required_inputs: list[str],
    ordered_actions: list[str],
    evidence_schema: list[str],
    completion_predicates: list[str],
    external_group_ids: list[str],
) -> dict[str, Any]:
    return {
        "group_id": group_id,
        "title": title,
        "artifact_ids": _dlv(codes),
        "owner_role": owner_role,
        "required_inputs": required_inputs,
        "ordered_actions": ordered_actions,
        "evidence_schema": evidence_schema,
        "completion_predicates": completion_predicates,
        "blocked_by_external": {
            "present": bool(external_group_ids),
            "external_group_ids": external_group_ids,
            "rule": (
                "내부 completion predicate 충족은 외부 event·권한 결정·receipt를 대신하지 "
                "않으며 연결된 external group이 끝날 때까지 release 효과를 갖지 않는다."
            ),
        },
    }


INTERNAL_ACTION_GROUPS = [
    _internal_group(
        "FINAL-INT-DATA-QUALITY-SPLIT",
        "AI/ML 데이터 품질·분할·오염 통제",
        ["AIML-05", "AIML-06", "AIML-07", "AIML-08", "AIML-09", "AIML-10"],
        "AI_ML_DATA_OWNER_ROLE",
        [
            "immutable source inventory와 class taxonomy",
            "subject/location/time leakage key",
            "중복·오염·라벨 품질 기준",
        ],
        [
            "source·label schema를 고정한다.",
            "subject/location/time 기준으로 train/validation/test를 분리한다.",
            "duplicate, leakage, imbalance와 contamination을 계산한다.",
            "정정 전후 row count와 hash를 결속한다.",
        ],
        [
            "dataset_snapshot_id",
            "source_inventory_sha256",
            "split_key_schema",
            "class_counts",
            "duplicate_and_leakage_findings",
            "label_quality_findings",
            "correction_lineage",
            "evidence_refs",
        ],
        [
            "exact six artifact IDs have complete rows",
            "split sets are disjoint under every declared leakage key",
            "all findings have owner, due condition and evidence reference",
            "independent data-quality recheck passes",
        ],
        ["FINAL-EXT-DATA-LEGAL"],
    ),
    _internal_group(
        "FINAL-INT-TRAINING-MODEL",
        "학습·모델 구성·재현성",
        ["AIML-11", "AIML-12", "AIML-13", "AIML-15", "AIML-16"],
        "AI_ML_ENGINEERING_OWNER_ROLE",
        [
            "approved dataset snapshot pointer",
            "training configuration and random seed contract",
            "model registry candidate identity",
        ],
        [
            "training input·configuration·environment를 immutable ID로 묶는다.",
            "seed와 dependency lock을 기록한다.",
            "checkpoint·metric·failure lineage를 생성한다.",
            "candidate model을 registry와 양방향 추적한다.",
        ],
        [
            "training_run_id",
            "dataset_snapshot_ref",
            "config_sha256",
            "environment_lock_sha256",
            "seed",
            "checkpoint_refs",
            "metric_refs",
            "registry_model_id",
        ],
        [
            "all required inputs are immutable and hash-bound",
            "one named run is reproducible from the recorded contract",
            "registry and training lineage are bidirectionally consistent",
            "no release or approval is inferred from candidate registration",
        ],
        ["FINAL-EXT-DATA-LEGAL", "FINAL-EXT-FIELD-DEVICE"],
    ),
    _internal_group(
        "FINAL-INT-EVALUATION-EQUIV",
        "평가·변환 동등성",
        ["AIML-17", "AIML-21", "AIML-23", "WS-10"],
        "MODEL_VALIDATION_OWNER_ROLE",
        [
            "fixed same-input evaluation dataset",
            "source and target model hashes",
            "approved preprocessing and tolerance contract",
        ],
        [
            "fixed dataset과 preprocessing을 hash 고정한다.",
            "source·target runtime을 같은 input으로 실행한다.",
            "class별 output delta와 safety regression을 계산한다.",
            "tolerance 승인 전 결과를 release 합격으로 쓰지 않는다.",
        ],
        [
            "evaluation_id",
            "dataset_sha256",
            "preprocessing_sha256",
            "source_model_sha256",
            "target_model_sha256",
            "per_class_results",
            "tolerance_contract",
            "decision_and_evidence_refs",
        ],
        [
            "fixed same-input execution is complete",
            "every class has reproducible comparison output",
            "tolerance is explicitly approved by the named authority",
            "independent model re-audit passes",
        ],
        ["FINAL-EXT-DATA-LEGAL", "FINAL-EXT-FIELD-DEVICE"],
    ),
    _internal_group(
        "FINAL-INT-DESIGN-SECURITY",
        "설계·보안 경계",
        ["DES-15", "DES-19", "DES-20", "SEC-01", "SEC-19"],
        "SECURITY_ARCHITECT_ROLE",
        [
            "current architecture and trust boundaries",
            "identity/session/authorization state model",
            "threat and recovery assumptions",
        ],
        [
            "actor·asset·trust boundary를 최신 구현에 맞춘다.",
            "authentication, authorization, recovery와 denial state를 명시한다.",
            "threat·control·test trace를 양방향 연결한다.",
            "미검증 보안 경계를 release blocker로 남긴다.",
        ],
        [
            "design_generation",
            "trust_boundary_rows",
            "state_transition_rows",
            "threat_control_trace",
            "verification_refs",
            "open_security_boundaries",
        ],
        [
            "all actors and trust crossings have explicit controls",
            "state transitions deny unauthorized and stale operations",
            "threat-control-test links are complete",
            "independent security review has zero unresolved major findings",
        ],
        ["FINAL-EXT-APPROVAL-HANDOVER"],
    ),
    _internal_group(
        "FINAL-INT-BUILD-SUPPLY",
        "build·dependency·supply-chain",
        ["DEV-09", "DEV-12", "DEV-14", "DEV-17", "DEV-19", "DEV-20"],
        "BUILD_RELEASE_ENGINEERING_ROLE",
        [
            "current source snapshot",
            "dependency locks and toolchain versions",
            "artifact generation and provenance contract",
        ],
        [
            "source·lock·toolchain generation을 고정한다.",
            "reproducible build와 dependency inventory를 실행한다.",
            "SBOM·provenance·artifact hash를 같은 generation에 묶는다.",
            "scan finding과 waiver 없는 release blocker를 기록한다.",
        ],
        [
            "build_id",
            "source_snapshot_sha256",
            "lockfile_bindings",
            "toolchain_versions",
            "artifact_bindings",
            "sbom_and_provenance_refs",
            "scan_findings",
        ],
        [
            "all inputs and outputs are hash-bound",
            "repeated build equivalence is demonstrated",
            "dependency and provenance inventories are complete",
            "no unresolved critical finding or unapproved waiver remains",
        ],
        ["FINAL-EXT-RELEASE-OPS"],
    ),
    _internal_group(
        "FINAL-INT-STATIC-SECRET",
        "정적분석·비밀정보 통제",
        ["DEV-16", "SEC-10", "SEC-12"],
        "APPLICATION_SECURITY_OWNER_ROLE",
        [
            "exact current-tree source set",
            "approved SAST/SCA/secret rules",
            "finding severity and exception policy",
        ],
        [
            "current-tree input set을 hash 고정한다.",
            "SAST, dependency and secret scan을 실행한다.",
            "finding을 source location과 remediation에 결속한다.",
            "비밀 값·가짜 clean claim 없이 재검증한다.",
        ],
        [
            "scan_execution_id",
            "input_set_sha256",
            "tool_and_rule_versions",
            "finding_rows",
            "remediation_refs",
            "exception_approval_refs",
            "repeat_scan_result",
        ],
        [
            "current-tree scan input is complete",
            "all blocking findings are remediated or explicitly unapproved",
            "no secret value is stored in evidence",
            "repeat scan reproduces the declared result",
        ],
        ["FINAL-EXT-DATA-LEGAL", "FINAL-EXT-RELEASE-OPS"],
    ),
    _internal_group(
        "FINAL-INT-INTEGRATION-RELEASE",
        "통합·release 준비 절차",
        ["DEV-21", "REL-15", "REL-16", "REL-18", "REL-19", "REL-22"],
        "RELEASE_ENGINEERING_OWNER_ROLE",
        [
            "named candidate scope and immutable artifact manifest",
            "rollback, installation, support and license contracts",
            "security, privacy and model gate states",
        ],
        [
            "candidate component generation을 하나로 묶는다.",
            "rollback·install·admin recovery·demo·license 절차를 완성한다.",
            "각 절차의 NOT_RUN execution boundary를 유지한다.",
            "release gate와 external receipt 선행조건을 연결한다.",
        ],
        [
            "candidate_generation",
            "component_manifest",
            "rollback_contract",
            "installation_contract",
            "admin_recovery_contract",
            "demo_and_license_contracts",
            "gate_and_evidence_refs",
        ],
        [
            "all procedures are internally complete and traceable",
            "execution templates cannot be mistaken for results",
            "every gate and external receipt remains explicit",
            "release stays NOT_ELIGIBLE until actual execution and approval",
        ],
        ["FINAL-EXT-RELEASE-OPS", "FINAL-EXT-APPROVAL-HANDOVER"],
    ),
    _internal_group(
        "FINAL-INT-TEST-GOV-TRACE",
        "시험 거버넌스·추적·판정 계약",
        ["TST-02", "TST-05", "TST-06", "TST-08", "TST-11", "TST-18", "TST-19", "TST-20", "TST-21"],
        "QA_GOVERNANCE_OWNER_ROLE",
        [
            "requirement-design-code trace",
            "test inventory and environment taxonomy",
            "defect, risk and evidence acceptance rules",
        ],
        [
            "test 목적·입력·환경·oracle을 유형별 고정한다.",
            "요구·위험·시험·결함 trace를 완성한다.",
            "실행/미실행과 pass/fail/not-assessed를 분리한다.",
            "formal 또는 external 판정을 내부 test row로 대체하지 않는다.",
        ],
        [
            "test_contract_id",
            "trace_links",
            "input_and_environment_refs",
            "oracle_and_judgment",
            "execution_status",
            "defect_and_risk_refs",
            "evidence_bindings",
        ],
        [
            "all nine artifact contracts are complete",
            "trace links have no unknown endpoint",
            "not-run work is never counted as pass",
            "independent QA contract review passes",
        ],
        ["FINAL-EXT-FIELD-DEVICE", "FINAL-EXT-RELEASE-OPS", "FINAL-EXT-APPROVAL-HANDOVER"],
    ),
    _internal_group(
        "FINAL-INT-TEST-ENV-DEVICE",
        "시험환경·device 준비",
        ["TST-03", "TST-07", "TST-09", "TST-14"],
        "QA_ENVIRONMENT_OWNER_ROLE",
        [
            "environment and device matrix",
            "fixture/data assignment",
            "network/provider and permission prerequisites",
        ],
        [
            "environment·device·fixture identity를 고정한다.",
            "permission, network, provider와 data preflight를 수행한다.",
            "실기기 실행 전 fail-closed 조건을 확인한다.",
            "실행 receipt가 없으면 NOT_RUN을 유지한다.",
        ],
        [
            "environment_id",
            "device_id_or_pool",
            "os_and_build_versions",
            "fixture_and_data_refs",
            "preflight_results",
            "execution_receipt_ref",
        ],
        [
            "all environment identities and inputs are assigned",
            "preflight checks pass without fabricated device execution",
            "actual device receipt is hash-bound when execution occurs",
            "independent environment re-audit passes",
        ],
        ["FINAL-EXT-FIELD-DEVICE"],
    ),
]


def _external_group(
    group_id: str,
    title: str,
    codes: list[str],
    authority_role: str,
    separation_rule: str,
    authority_scope: str,
    prerequisites: list[str],
    required_inputs: list[str],
    trigger: str,
    ordered_steps: list[str],
    completion_predicates: list[str],
) -> dict[str, Any]:
    return {
        "group_id": group_id,
        "title": title,
        "artifact_ids": _dlv(codes),
        "authority_contract": {
            "authority_role": authority_role,
            "separation_rule": separation_rule,
            "authority_scope": authority_scope,
            "actual_authority_identity": None,
            "actual_decision": None,
            "decision_status": "NOT_APPROVED",
        },
        "prerequisites": prerequisites,
        "required_inputs": required_inputs,
        "trigger": trigger,
        "ordered_steps": ordered_steps,
        "synthetic_event_allowed": False,
        "evidence_schema": [
            "source_event_or_decision_id",
            "occurred_or_effective_at",
            "scope",
            "operator_identity",
            "authority_identity",
            "decision",
            "result",
            "defects_or_conditions",
        ],
        "receipt_schema": [
            "repository_relative_path",
            "sha256",
            "byte_length",
            "source_event_or_decision_id",
            "occurred_or_effective_at",
            "operator_or_acceptor_identity",
        ],
        "completion_predicates": completion_predicates,
    }


EXTERNAL_ACTION_GROUPS = [
    _external_group(
        "FINAL-EXT-DATA-LEGAL",
        "데이터 권리·법률·개인정보 외부 결정",
        ["AIML-01", "AIML-02", "AIML-03", "AIML-22", "SEC-04", "SEC-05", "SEC-06", "SEC-17"],
        "DATA_PROTECTION_AND_LEGAL_AUTHORITY_ROLE",
        "implementer, data provider and approving legal/privacy authority must be distinguishable",
        "exact data class, source, purpose, retention, transfer and notice generation",
        [
            "internal data inventory and processing map complete",
            "technical retention and consent behavior documented",
            "all unresolved legal questions have owner and due condition",
        ],
        [
            "source rights and consent originals",
            "controller/processor and cross-border roles",
            "retention/deletion/transfer notice text",
        ],
        "실제 provider 계약·법률 검토·privacy authority decision이 존재할 때 활성",
        [
            "exact scope와 authority identity를 확인한다.",
            "source right, consent, legal basis와 notice를 검토한다.",
            "approve, reject 또는 conditional decision을 기록한다.",
            "decision 원본과 effective time을 receipt hash에 결속한다.",
        ],
        [
            "real authority decision exists",
            "scope and effective window are explicit",
            "receipt bytes reproduce path, hash and length",
            "independent re-audit passes",
        ],
    ),
    _external_group(
        "FINAL-EXT-FIELD-DEVICE",
        "실기기·현장·사용자 검증",
        ["WS-06", "WS-07", "WS-09", "WS-12", "WS-13", "WS-14", "WS-15", "WS-17", "WS-20", "WS-21"],
        "INDEPENDENT_FIELD_QA_AUTHORITY_ROLE",
        "field executor and independent safety/QA acceptor must be separate from the feature author",
        "named build, device matrix, scenario, participant boundary and safety stop rule",
        [
            "internally complete test and safety contracts",
            "named release candidate and device inventory",
            "approved participant, privacy and stop conditions",
        ],
        [
            "device/build/OS identity",
            "scenario and expected behavior",
            "raw result, defect and safety interruption evidence",
        ],
        "승인된 실기기·현장 실행 window가 열리고 named executor가 실행할 때 활성",
        [
            "preflight와 안전 중단 조건을 확인한다.",
            "실제 device에서 named scenario를 실행한다.",
            "result·defect·중단 사건을 append-only로 기록한다.",
            "독립 QA가 exact scope를 재검토하고 receipt를 발행한다.",
        ],
        [
            "actual device event exists",
            "all assigned scenarios have real results",
            "safety defects and interruptions are resolved or blocking",
            "independent re-audit passes",
        ],
    ),
    _external_group(
        "FINAL-EXT-RELEASE-OPS",
        "release·운영 실행",
        ["OPS-06", "OPS-11", "OPS-13", "OPS-17", "OPS-19", "OPS-22", "OPS-23", "REL-13", "REL-20", "REL-21", "CLS-14", "CLS-15", "CLS-16"],
        "RELEASE_AND_OPERATIONS_AUTHORITY_ROLE",
        "executor/operator and release or service approver identities must be distinct or explicitly controlled",
        "named environment, release generation, operation event and rollback/recovery boundary",
        [
            "internal runbooks and execution schemas complete",
            "named immutable release/environment exists",
            "security, privacy, model and QA gates remain explicit",
        ],
        [
            "deployment/operation event identity",
            "environment and artifact hashes",
            "operator, result, rollback and verification evidence",
        ],
        "실제 release·운영·incident·change·restore·closure event가 승인되어 발생할 때 활성",
        [
            "실행 authority와 exact scope를 확인한다.",
            "승인된 순서와 중단점으로 실제 action을 실행한다.",
            "결과·defect·rollback·data/account disposition을 검증한다.",
            "operator/approver identity와 receipt bytes를 결속한다.",
        ],
        [
            "real operational event exists",
            "all lifecycle fields and rollback checks are complete",
            "actual receipt binds event, operator and authority",
            "independent re-audit passes",
        ],
    ),
    _external_group(
        "FINAL-EXT-APPROVAL-HANDOVER",
        "승인·검수·인계",
        ["CLS-02", "CLS-04", "CLS-08", "CLS-10", "CLS-11", "DES-21", "DSC-05", "DSC-06", "DSC-11", "MGT-03", "MGT-08", "REQ-12", "REQ-13", "REQ-15", "TST-22", "TST-23"],
        "PROJECT_SPONSOR_RECIPIENT_AND_ACCEPTANCE_AUTHORITY_ROLE",
        "author, reviewer, approver and recipient identities and scopes must be explicit",
        "exact baseline/release/result scope, effective window, residual risks and recipient obligations",
        [
            "internal artifact content and trace complete",
            "named candidate, result or handover scope exists",
            "residual risk and open obligation inventory complete",
        ],
        [
            "approval/acceptance decision source",
            "reviewed scope and version",
            "signer/recipient identity and effective time",
        ],
        "실제 승인·검수·인계 decision을 권한자가 내리고 수신자가 존재할 때 활성",
        [
            "권한자·수신자·exact scope를 확인한다.",
            "evidence, risk, obligation과 acceptance criteria를 검토한다.",
            "approve, reject 또는 conditional decision을 양측 확인한다.",
            "서명·decision 원본을 receipt hash에 결속한다.",
        ],
        [
            "real scoped authority decision exists",
            "recipient and obligations are unambiguous",
            "actual receipt binds signer, scope and effective time",
            "independent re-audit passes",
        ],
    ),
    _external_group(
        "FINAL-EXT-RESEARCH-OBSERVATION",
        "조사·관찰 증거",
        ["DSC-07", "DSC-09"],
        "INDEPENDENT_RESEARCH_OBSERVER_ROLE",
        "observer and artifact author must be distinguishable",
        "named observation method, participant/context boundary and immutable raw record",
        [
            "research question and observation schema complete",
            "participant/privacy boundary approved",
            "synthetic observation is prohibited",
        ],
        [
            "real observation event and context",
            "observer identity and method",
            "raw notes/record and analysis lineage",
        ],
        "실제 사용자·현장 observation event가 승인된 방법으로 발생할 때 활성",
        [
            "participant/context와 observer를 확인한다.",
            "승인된 방법으로 실제 observation을 수행한다.",
            "raw record와 derived finding을 분리해 기록한다.",
            "독립 재감사와 receipt binding을 수행한다.",
        ],
        [
            "real observation event exists",
            "raw and derived records are traceable",
            "no synthetic participant or result is present",
            "independent re-audit passes",
        ],
    ),
]


GLOBAL_BOUNDARY = {
    "formal_279": {
        "status": "NOT_RUN",
        "executed_count": 0,
        "pass_count": 0,
        "evidence_count": 0,
    },
    "actual_device_validation": "NOT_RUN",
    "deployment_execution": "NOT_RUN",
    "deployment_approval": "NOT_APPROVED",
    "signing_execution": "NOT_RUN",
    "signing_approval": "NOT_APPROVED",
    "legal_approval": "NOT_APPROVED",
    "model_evaluation": "NOT_RUN",
    "model_approval": "NOT_APPROVED",
    "closure_execution": "NOT_RUN",
    "closure_approval": "NOT_APPROVED",
    "remaining_gates": {
        "count": 5,
        "status": "NOT_RUN",
        "waived_count": 0,
    },
    "release_status": "NOT_ELIGIBLE",
    "boundary_relaxation_count": 0,
}


def _wave_paths(wave_id: str) -> dict[str, Path]:
    directory = REMEDIATION_ROOT / wave_id.lower()
    return {
        "delta": directory / "artifact-status-delta.json",
        "validation": directory / "validation-summary.json",
        "receipt": directory / "implementation-receipt.json",
    }


def _normalize_transitions(
    wave_id: str,
    delta: dict[str, Any],
) -> list[dict[str, Any]]:
    records = delta.get("transitions")
    if records is None:
        records = delta.get("rows")
    _require(isinstance(records, list) and records, f"{wave_id} transition rows missing")
    result = []
    for record in records:
        code = record.get("artifact_type_code")
        _require(isinstance(code, str), f"{wave_id} transition artifact ID missing")
        if "from" in record:
            before = record["from"]
            after = record["to"]
        elif "baseline_classification" in record:
            before = record["baseline_classification"]
            after = record["projected_classification"]
        else:
            before = record.get("pre_w2_classification")
            after = record.get("post_w2_classification")
        result.append(
            {
                "artifact_type_code": code,
                "from_status": _normalize_status(before),
                "to_status": _normalize_status(after),
                "required_action": record.get("required_action"),
                "evidence": record.get(
                    "evidence_paths",
                    record.get(
                        "successor_evidence_paths",
                        record.get("evidence_refs", []),
                    ),
                ),
                "external_boundary": record.get("external_boundary"),
                "basis": record.get(
                    "basis",
                    record.get("transition_rationale"),
                ),
                "authority_record": record,
            }
        )
    ids = [item["artifact_type_code"] for item in result]
    _require(len(ids) == len(set(ids)), f"{wave_id} transition IDs are duplicated")
    return result


def _assert_current_go(
    wave_id: str,
    delta: dict[str, Any],
    validation: dict[str, Any],
    receipt: dict[str, Any],
) -> dict[str, Any]:
    transition_rows = delta.get("transitions", delta.get("rows", []))
    row_authority_values = [
        row[key]
        for row in transition_rows
        for key in (
            "transition_authority_status",
            "independent_review_status",
            "review_status",
        )
        if key in row
    ]
    allowed_row_authorities = {
        "GO_STATUS_DELTA_ALLOWED",
        "GO",
        "GO_FOR_INTERNAL_ARTIFACT_DELTA",
        "APPROVED_WITH_OPEN_GAPS",
    }
    if row_authority_values:
        _require(
            all(value in allowed_row_authorities for value in row_authority_values),
            f"{wave_id} transition row authority is not GO",
        )
    authorities = [
        document["transition_authority"]
        for document in (delta, validation, receipt)
        if isinstance(document.get("transition_authority"), dict)
    ]
    if authorities:
        for authority in authorities:
            if "status" in authority:
                _require(
                    authority["status"] in allowed_row_authorities,
                    f"{wave_id} current transition authority is not GO",
                )
            else:
                _require(
                    authority.get("sole_transition_authority") is True
                    and bool(row_authority_values),
                    f"{wave_id} current transition authority has no GO row binding",
                )
            findings = authority.get("findings", {})
            if findings:
                _require(
                    all(findings.get(level) == 0 for level in ("blocking", "major", "minor")),
                    f"{wave_id} current authority has unresolved findings",
                )
        authority_summary = {
            "kind": "EXPLICIT_GO_STATUS_DELTA_ALLOWED",
            "path": authorities[0].get("path"),
            "status": authorities[0].get(
                "status",
                sorted(set(row_authority_values))[0],
            ),
        }
    elif row_authority_values:
        authority_summary = {
            "kind": "ROW_LEVEL_BOUND_GO_AUTHORITY",
            "path": None,
            "status": sorted(set(row_authority_values))[0],
        }
    else:
        reviews = [
            binding
            for document in (delta, validation, receipt)
            for binding in document.get("source_bindings", [])
            if "INDEPENDENT_REVIEW" in binding.get("role", "")
        ]
        _require(reviews, f"{wave_id} independent review authority missing")
        _require(
            all(
                "APPROVED" in binding.get("allowed_claim", "").upper()
                and "NO_GO" not in binding.get("allowed_claim", "").upper()
                for binding in reviews
            ),
            f"{wave_id} independent review is not approved",
        )
        authority_summary = {
            "kind": "BOUND_FINAL_INDEPENDENT_REVIEW_APPROVED",
            "path": reviews[0]["path"],
            "status": "GO_INTERNAL_STATUS_PROJECTION_ONLY",
        }

    for document in (delta, validation, receipt):
        for key in ("status", "validation_result", "result"):
            current = str(document.get(key, "")).upper()
            _require(
                current not in {"NO_GO", "FAIL", "FAILED", "REJECTED"}
                and not current.startswith("NO_GO"),
                f"{wave_id} non-GO authority result: {key}={current}",
            )
    validation_pass = (
        validation.get("validation_result") == "PASS"
        or str(validation.get("status", "")).startswith("PASS")
        or (
            isinstance(validation.get("result"), dict)
            and validation["result"].get("validation_result") == "PASS"
        )
        or validation.get("validation_totals", {}).get("result") == "PASS"
    )
    _require(validation_pass, f"{wave_id} validation is not PASS")
    return authority_summary


def _assert_count_projection(
    wave_id: str,
    delta: dict[str, Any],
    validation: dict[str, Any],
    receipt: dict[str, Any],
    before: dict[str, int],
    after: dict[str, int],
) -> None:
    expected_delta = _delta_counts(before, after)
    if wave_id == "W1":
        for document in (delta, receipt):
            projection = document.get("classification_projection", {})
            _require(
                _normalize_count_record(projection["baseline"]) == before
                and _normalize_count_record(projection["delta"]) == expected_delta
                and _normalize_count_record(projection["post_w1"]) == after,
                "W1 count projection differs",
            )
        return
    if wave_id == "W2":
        for document in (delta, receipt):
            projection = document.get("classification_projection", {})
            _require(
                _normalize_count_record(projection["pre_w2_post_w1"]) == before
                and _normalize_count_record(projection["delta"]) == expected_delta
                and _normalize_count_record(projection["post_w2"]) == after,
                "W2 count projection differs",
            )
        return

    state = receipt.get("state_transition", {})
    before_values = [
        value
        for key, value in state.items()
        if isinstance(value, dict) and "before" in key.lower()
    ]
    after_values = [
        value
        for key, value in state.items()
        if isinstance(value, dict) and ("after" in key.lower() or key.lower() == "after")
    ]
    delta_values = [
        value
        for key, value in state.items()
        if isinstance(value, dict) and "delta" in key.lower()
    ]
    _require(before_values and after_values and delta_values, f"{wave_id} receipt counts missing")
    _require(
        _normalize_count_record(before_values[0]) == before
        and _normalize_count_record(after_values[0]) == after
        and _normalize_count_record(delta_values[0]) == expected_delta,
        f"{wave_id} receipt count projection differs",
    )
    validation_state = validation.get("state_transition")
    if isinstance(validation_state, dict):
        for key, expected in (("before", before), ("after", after), ("delta", expected_delta)):
            if isinstance(validation_state.get(key), dict):
                _require(
                    _normalize_count_record(validation_state[key]) == expected,
                    f"{wave_id} validation {key} counts differ",
                )


def _assert_authority_chain(
    wave_id: str,
    paths: dict[str, Path],
    delta: dict[str, Any],
    validation: dict[str, Any],
    receipt: dict[str, Any],
    previous_receipt: Path | None,
) -> dict[str, Any]:
    bindings = {role: _raw_binding(path) for role, path in paths.items()}
    bindings["predecessor_bridge_bindings"] = []
    _require(
        _find_raw_binding(validation, paths["delta"], bindings["delta"]),
        f"{wave_id} validation does not hash-bind delta",
    )
    _require(
        _find_raw_binding(receipt, paths["validation"], bindings["validation"]),
        f"{wave_id} receipt does not hash-bind validation",
    )
    if previous_receipt is not None:
        previous_binding = _raw_binding(previous_receipt)
        directly_bound = any(
            _find_raw_binding(document, previous_receipt, previous_binding)
            for document in (delta, validation, receipt)
        )
        if not directly_bound:
            bridge_paths = PREDECESSOR_BRIDGE_PATHS.get(wave_id, ())
            _require(bridge_paths, f"{wave_id} predecessor receipt binding missing")
            upstream_path = previous_receipt
            upstream_binding = previous_binding
            for bridge_path in bridge_paths:
                _require(bridge_path.is_file(), f"{wave_id} predecessor bridge missing")
                bridge_document = _load_json(bridge_path)
                _require(
                    _find_raw_binding(
                        bridge_document,
                        upstream_path,
                        upstream_binding,
                    ),
                    f"{wave_id} predecessor bridge hash mismatch: {_rel(bridge_path)}",
                )
                bridge_dag = bridge_document.get("binding_dag")
                if isinstance(bridge_dag, dict):
                    _require(
                        bridge_dag.get("cycle_status", "ACYCLIC") == "ACYCLIC"
                        and bridge_dag.get("dag_status", "PASS") == "PASS",
                        f"{wave_id} predecessor bridge DAG is not acyclic/pass",
                    )
                upstream_path = bridge_path
                upstream_binding = _raw_binding(bridge_path)
                bindings["predecessor_bridge_bindings"].append(upstream_binding)
            _require(
                _find_raw_binding(delta, upstream_path, upstream_binding),
                f"{wave_id} delta does not hash-bind predecessor bridge",
            )
    for document in (delta, validation, receipt):
        dag = document.get("binding_dag")
        if isinstance(dag, dict):
            _require(
                dag.get("cycle_status") == "ACYCLIC"
                and dag.get("dag_status", "PASS") == "PASS",
                f"{wave_id} input binding DAG is not acyclic/pass",
            )
    return bindings


def _source_set(paths: list[Path]) -> tuple[list[dict[str, Any]], str]:
    bindings = sorted((_raw_binding(path) for path in paths), key=lambda item: item["path"])
    return bindings, _sha_bytes(_canonical_bytes(bindings))


def _validate_action_group_design() -> tuple[dict[str, str], dict[str, str]]:
    internal_assignment: dict[str, str] = {}
    for group in INTERNAL_ACTION_GROUPS:
        _require(
            group["owner_role"]
            and group["required_inputs"]
            and group["ordered_actions"]
            and group["evidence_schema"]
            and group["completion_predicates"]
            and isinstance(group["blocked_by_external"], dict),
            f"internal group contract incomplete: {group['group_id']}",
        )
        for code in group["artifact_ids"]:
            _require(code not in internal_assignment, f"duplicate internal action member: {code}")
            internal_assignment[code] = group["group_id"]

    external_assignment: dict[str, str] = {}
    for group in EXTERNAL_ACTION_GROUPS:
        authority = group["authority_contract"]
        _require(
            authority["authority_role"]
            and authority["separation_rule"]
            and authority["authority_scope"]
            and authority["actual_decision"] is None
            and authority["decision_status"] == "NOT_APPROVED"
            and group["prerequisites"]
            and group["required_inputs"]
            and group["trigger"]
            and group["ordered_steps"]
            and group["synthetic_event_allowed"] is False
            and group["evidence_schema"]
            and group["receipt_schema"]
            and group["completion_predicates"],
            f"external group contract incomplete: {group['group_id']}",
        )
        for code in group["artifact_ids"]:
            _require(code not in external_assignment, f"duplicate external action member: {code}")
            external_assignment[code] = group["group_id"]

    _require(
        [len(group["artifact_ids"]) for group in INTERNAL_ACTION_GROUPS]
        == [6, 5, 4, 5, 6, 3, 6, 9, 4],
        "internal group sizes differ",
    )
    _require(
        [len(group["artifact_ids"]) for group in EXTERNAL_ACTION_GROUPS]
        == [8, 10, 13, 16, 2],
        "external group sizes differ",
    )
    _require(len(internal_assignment) == 48, "internal action membership must be 48")
    _require(len(external_assignment) == 49, "external action membership must be 49")
    _require(
        not set(internal_assignment) & set(external_assignment),
        "internal/external action membership overlaps",
    )
    return internal_assignment, external_assignment


def _semantic_projection(
    artifact_tuples: list[dict[str, Any]],
    epoch_summaries: list[dict[str, Any]],
    internal_assignment: dict[str, str],
    external_assignment: dict[str, str],
) -> dict[str, Any]:
    payload = {
        "projection_schema": SEMANTIC_SCHEMA,
        "artifact_status_tuples": artifact_tuples,
        "internal_action_membership": [
            {"artifact_type_code": code, "group_id": internal_assignment[code]}
            for code in sorted(internal_assignment)
        ],
        "external_action_membership": [
            {"artifact_type_code": code, "group_id": external_assignment[code]}
            for code in sorted(external_assignment)
        ],
        "replay_epoch_counts": [
            {
                "wave_id": item["wave_id"],
                "before": item["before_counts"],
                "delta": item["applied_delta"],
                "after": item["after_counts"],
            }
            for item in epoch_summaries
        ],
        "global_boundary": GLOBAL_BOUNDARY,
    }
    _require(list(payload) == SEMANTIC_FIELDS, "semantic payload field order differs")
    return {
        "projection_schema": SEMANTIC_SCHEMA,
        "included_fields": SEMANTIC_FIELDS,
        "canonicalization": (
            "exact payload shown below; UTF-8 JSON; ensure_ascii=false; "
            "sort_keys=true; compact separators; trailing LF"
        ),
        "payload": payload,
        "sha256": _sha_bytes(_canonical_bytes(payload)),
    }


def _make_external_records(
    baseline_by_id: dict[str, dict[str, Any]],
    lineage_by_id: dict[str, list[dict[str, Any]]],
    external_assignment: dict[str, str],
) -> tuple[list[dict[str, Any]], dict[str, dict[str, Any]]]:
    groups_by_id = {group["group_id"]: group for group in EXTERNAL_ACTION_GROUPS}
    records = []
    by_code = {}
    for code in sorted(external_assignment):
        baseline = baseline_by_id[code]
        group = groups_by_id[external_assignment[code]]
        lineage = lineage_by_id[code]
        changed_to_external = [
            item
            for item in lineage
            if item["from_status"] != "EXTERNAL" and item["to_status"] == "EXTERNAL"
        ]
        record = {
            "record_id": f"FINAL-EXT-ACTION-{code.removeprefix('DLV-')}",
            "artifact_type_code": code,
            "group_id": group["group_id"],
            "initial_status": _normalize_status(baseline["classification"]),
            "current_status": "EXTERNAL",
            "authority_role": group["authority_contract"]["authority_role"],
            "authority_separation": group["authority_contract"]["separation_rule"],
            "authority_scope": group["authority_contract"]["authority_scope"],
            "actual_authority_identity": None,
            "actual_decision": None,
            "approval_status": "NOT_APPROVED",
            "prerequisites": group["prerequisites"],
            "required_inputs": group["required_inputs"],
            "trigger": group["trigger"],
            "ordered_steps": group["ordered_steps"],
            "synthetic_event_allowed": False,
            "evidence_schema": group["evidence_schema"],
            "receipt_schema": group["receipt_schema"],
            "completion_predicates": group["completion_predicates"],
            "baseline_required_action": baseline["required_action"],
            "baseline_external_dependency": baseline.get("external_dependency"),
            "baseline_external_dependency_present": baseline.get(
                "external_dependency_present",
                bool(baseline.get("external_dependency")),
            ),
            "transition_lineage": lineage,
            "new_external_lineage": {
                "newly_external": bool(changed_to_external),
                "authority_wave_id": (
                    changed_to_external[-1]["wave_id"] if changed_to_external else None
                ),
                "allowed_new_external_authority_waves": ["W1", "W5", "W9"],
            },
            "actual_evidence": {
                "source_event_or_decision_id": None,
                "occurred_or_effective_at": None,
                "scope": None,
                "operator_identity": None,
                "authority_identity": None,
                "decision": None,
                "result": None,
                "repository_relative_path": None,
                "sha256": None,
                "byte_length": None,
            },
            "completion_state": {
                "actual_event_occurred": False,
                "actual_event_count": 0,
                "actual_receipt_count": 0,
                "independent_reaudit": "NOT_RUN",
                "predicates_satisfied": False,
                "completion_eligible": False,
            },
            "release_impact": "BLOCKS_RELEVANT_APPROVAL_OPERATION_OR_RELEASE; RELEASE_NOT_ELIGIBLE",
        }
        records.append(record)
        by_code[code] = record
    return records, by_code


def _build_binding_dag(
    authority_paths: dict[str, dict[str, Path]],
    extra_authority_path: Path,
) -> dict[str, Any]:
    edges: list[dict[str, str]] = []

    def edge(source: Path, target: Path, relation: str) -> None:
        edges.append({"from": _rel(source), "to": _rel(target), "relation": relation})

    previous_receipt: Path | None = None
    for wave_id in WAVE_IDS:
        paths = authority_paths[wave_id]
        if previous_receipt is None:
            edge(BASELINE_PATH, paths["delta"], "REPLAY_PREDECESSOR")
        else:
            upstream_path = previous_receipt
            for bridge_path in PREDECESSOR_BRIDGE_PATHS.get(wave_id, ()):
                edge(
                    upstream_path,
                    bridge_path,
                    "HASH_BINDS_REPLAY_PREDECESSOR",
                )
                upstream_path = bridge_path
            edge(upstream_path, paths["delta"], "REPLAY_PREDECESSOR")
        edge(WAVE_PLAN_PATH, paths["delta"], "PLANNED_AUTHORITY_ORDER")
        edge(paths["delta"], paths["validation"], "VALIDATES_DELTA")
        edge(paths["validation"], paths["receipt"], "RECEIPTS_VALIDATED_DELTA")
        previous_receipt = paths["receipt"]
    edge(
        HISTORICAL_CENTRAL_NO_GO_PATH,
        extra_authority_path,
        "REMEDIATION_HISTORY_ONLY_NOT_TRANSITION_AUTHORITY",
    )
    edge(extra_authority_path, authority_paths["W9"]["receipt"], "W9_GO_SEAL_AUTHORIZES_RECEIPT")
    edge(authority_paths["W9"]["receipt"], SNAPSHOT_PATH, "FINAL_REPLAY_INPUT")
    edge(authority_paths["W9"]["receipt"], EXTERNAL_PACKET_PATH, "FINAL_EXTERNAL_BOUNDARY_INPUT")
    edge(GENERATOR_PATH, SNAPSHOT_PATH, "GENERATES")
    edge(GENERATOR_PATH, SNAPSHOT_MD_PATH, "GENERATES")
    edge(GENERATOR_PATH, EXTERNAL_PACKET_PATH, "GENERATES")
    edge(GENERATOR_PATH, MANIFEST_PATH, "GENERATES")
    edge(SNAPSHOT_PATH, SNAPSHOT_MD_PATH, "RENDERS")
    edge(EXTERNAL_PACKET_PATH, SNAPSHOT_MD_PATH, "RENDERS_EXTERNAL_PARITY")
    edge(SNAPSHOT_PATH, MANIFEST_PATH, "BINDS_RAW_OUTPUT")
    edge(SNAPSHOT_MD_PATH, MANIFEST_PATH, "BINDS_RAW_OUTPUT")
    edge(EXTERNAL_PACKET_PATH, MANIFEST_PATH, "BINDS_RAW_OUTPUT")

    nodes = sorted(
        {
            _rel(BASELINE_PATH),
            _rel(WAVE_PLAN_PATH),
            _rel(GENERATOR_PATH),
            _rel(extra_authority_path),
            _rel(HISTORICAL_CENTRAL_NO_GO_PATH),
            _rel(SNAPSHOT_PATH),
            _rel(SNAPSHOT_MD_PATH),
            _rel(EXTERNAL_PACKET_PATH),
            _rel(MANIFEST_PATH),
            *(
                _rel(path)
                for paths in authority_paths.values()
                for path in paths.values()
            ),
            *(
                _rel(path)
                for paths in PREDECESSOR_BRIDGE_PATHS.values()
                for path in paths
            ),
        }
    )
    outgoing: dict[str, list[str]] = {node: [] for node in nodes}
    indegree = {node: 0 for node in nodes}
    for item in edges:
        _require(item["from"] != item["to"], "binding DAG self-cycle")
        outgoing[item["from"]].append(item["to"])
        indegree[item["to"]] += 1
    ready = sorted(node for node, degree in indegree.items() if degree == 0)
    visited: list[str] = []
    while ready:
        node = ready.pop(0)
        visited.append(node)
        for target in sorted(outgoing[node]):
            indegree[target] -= 1
            if indegree[target] == 0:
                ready.append(target)
                ready.sort()
    _require(len(visited) == len(nodes), "binding DAG contains a cycle")
    return {
        "nodes": nodes,
        "edges": edges,
        "topological_order": visited,
        "cycle_status": "ACYCLIC",
        "dag_status": "PASS",
        "manifest_is_not_an_output_hash_input": True,
    }


def _render_markdown(
    snapshot: dict[str, Any],
    external_packet: dict[str, Any],
) -> str:
    counts = snapshot["final_counts"]
    group_lines = [
        f"| `{group['group_id']}` | INTERNAL | {len(group['artifact_ids'])} | `{group['owner_role']}` |"
        for group in snapshot["internal_action_groups"]
    ]
    group_lines.extend(
        f"| `{group['group_id']}` | EXTERNAL | {len(group['artifact_ids'])} | `{group['authority_contract']['authority_role']}` |"
        for group in external_packet["external_action_groups"]
    )
    artifact_lines = [
        "| `{artifact_type_code}` | `{initial_status}` | `{current_status}` | "
        "`{action_kind}` | `{action_group_id}` | {lineage_count} |".format(
            artifact_type_code=item["artifact_type_code"],
            initial_status=item["initial_status"],
            current_status=item["current_status"],
            action_kind=item["action_assignment"]["kind"],
            action_group_id=item["action_assignment"]["group_id"] or "NONE",
            lineage_count=len(item["transition_lineage"]),
        )
        for item in snapshot["artifacts"]
    ]
    tuples_json = json.dumps(
        snapshot["artifact_status_tuples"],
        ensure_ascii=False,
        indent=2,
        sort_keys=True,
    )
    return f"""# WalkSafe 최종 257 산출물 successor snapshot

> 생성일: `2026-07-27`  
> 원 baseline: `{_rel(BASELINE_PATH)}` (immutable)  
> replay: `W1 → W2 → W3 → W4 → W5 → W6 → W7 → W8 → W9`  
> final independent review/seal: 이 package에서 생성하지 않음

## 최종 집계

| OK | INTERNAL_GAP | EXTERNAL | N_A_CANDIDATE | TOTAL |
|---:|---:|---:|---:|---:|
| {counts['OK']} | {counts['INTERNAL_GAP']} | {counts['EXTERNAL']} | {counts['N_A_CANDIDATE']} | {counts['TOTAL']} |

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
{chr(10).join(group_lines)}

## Exact 257 status·action table

| Artifact | Initial | Current | Action | Group | Lineage |
|---|---|---|---|---|---:|
{chr(10).join(artifact_lines)}

## Lossless artifact tuple projection

아래 payload는 snapshot JSON 및 external-action packet JSON의 `artifact_status_tuples`와 byte-semantic parity를 이룹니다.

<!-- FINAL-257-TUPLES-START -->
```json
{tuples_json}
```
<!-- FINAL-257-TUPLES-END -->

## Fingerprints

- source-set: `{snapshot['source_set']['sha256']}`
- semantic: `{snapshot['integrity']['semantic_projection']['sha256']}`
- snapshot content: `{snapshot['integrity']['content_fingerprint']['value']}`
- external packet content: `{external_packet['integrity']['content_fingerprint']['value']}`

## 권한 경계

이 문서는 immutable baseline을 수정하지 않는 add-only successor입니다. W1~W9의 hash-bound GO authority만 replay하며 formal test, device, deployment, signing, legal, model, closure, gate waiver 또는 release 승인을 새로 부여하지 않습니다.
"""


def _build_model() -> tuple[
    dict[str, Any],
    dict[str, Any],
    list[dict[str, Any]],
    list[dict[str, Any]],
    dict[str, dict[str, Path]],
    Path,
]:
    _require(
        len(EXPECTED_W9_RECEIPT_SHA256) == 64
        and len(EXPECTED_W9_GO_SEAL_SHA256) == 64,
        "new W9 receipt or GO seal binding is invalid",
    )
    go_seal_path = REPO_ROOT / W9_GO_SEAL_RELATIVE_PATH
    _require(go_seal_path.is_file(), "W9 GO seal is missing")
    _require(
        _raw_binding(go_seal_path)["sha256"] == EXPECTED_W9_GO_SEAL_SHA256,
        "W9 GO seal raw hash differs",
    )
    go_seal_text = go_seal_path.read_text(encoding="utf-8")
    for required_marker in (
        "- Blocking findings: `0`",
        "- Major findings: `0`",
        "- Minor findings: `0`",
        "- Verdict: `GO_W9_SEALED`",
        EXPECTED_W9_RECEIPT_SHA256,
        "REMEDIATION_HISTORY_NOT_TRANSITION_AUTHORITY",
    ):
        _require(required_marker in go_seal_text, f"W9 GO seal marker missing: {required_marker}")
    historical_no_go_binding = _raw_binding(HISTORICAL_CENTRAL_NO_GO_PATH)
    _require(
        historical_no_go_binding["sha256"]
        == EXPECTED_HISTORICAL_CENTRAL_NO_GO_SHA256,
        "historical central NO_GO raw hash differs",
    )

    baseline = _load_json(BASELINE_PATH)
    wave_plan = _load_json(WAVE_PLAN_PATH)
    _require(
        baseline.get("schema_version") == "walksafe.artifact-audit-baseline.v1"
        and baseline.get("baseline_id") == "WS-ARTIFACT-AUDIT-BASELINE-20260726",
        "baseline identity differs",
    )
    items = baseline.get("items")
    _require(isinstance(items, list) and len(items) == 257, "baseline must contain exact 257 rows")
    baseline_by_id = {item["artifact_type_code"]: item for item in items}
    _require(len(baseline_by_id) == 257, "baseline artifact IDs are duplicated")
    status_by_id = {
        code: _normalize_status(item["classification"])
        for code, item in baseline_by_id.items()
    }
    _require(_counts(status_by_id) == INITIAL_COUNTS, "initial baseline counts differ")
    _require(
        baseline.get("validation", {}).get("status") == "PASS"
        and baseline.get("validation", {}).get("missing_codes") == []
        and baseline.get("validation", {}).get("extra_codes") == [],
        "baseline validation is not exact PASS",
    )

    baseline_binding = _raw_binding(BASELINE_PATH)
    plan_baseline_bindings = [
        item
        for item in wave_plan.get("source_bindings", [])
        if item.get("path") == _rel(BASELINE_PATH)
    ]
    _require(
        len(plan_baseline_bindings) == 1
        and plan_baseline_bindings[0].get("sha256") == baseline_binding["sha256"],
        "wave plan does not bind the current immutable baseline",
    )
    plan_summary = wave_plan.get("summary", {})
    _require(
        plan_summary.get("internal_gap_count") == 123
        and plan_summary.get("external_count") == 39
        and plan_summary.get("n_a_candidate_count") == 36,
        "wave plan initial summary differs",
    )
    planned_ids = {
        item.get("wave_id")
        for item in wave_plan.get("internal_waves", [])
    }
    _require(set(WAVE_IDS).issubset(planned_ids), "wave plan omits a replay epoch")

    authority_paths = {wave_id: _wave_paths(wave_id) for wave_id in WAVE_IDS}
    lineage_by_id: dict[str, list[dict[str, Any]]] = {
        code: [] for code in baseline_by_id
    }
    epoch_summaries = []
    previous_receipt: Path | None = None
    previous_after = INITIAL_COUNTS
    for order, wave_id in enumerate(WAVE_IDS, start=1):
        paths = authority_paths[wave_id]
        _require(all(path.is_file() for path in paths.values()), f"{wave_id} authority triple missing")
        delta = _load_json(paths["delta"])
        validation = _load_json(paths["validation"])
        receipt = _load_json(paths["receipt"])
        authority = _assert_current_go(wave_id, delta, validation, receipt)
        bindings = _assert_authority_chain(
            wave_id,
            paths,
            delta,
            validation,
            receipt,
            previous_receipt,
        )
        transitions = _normalize_transitions(wave_id, delta)
        scope_ids = _artifact_ids(delta)
        _require(scope_ids == [item["artifact_type_code"] for item in transitions], f"{wave_id} scope/order differs")
        receipt_ids = _artifact_ids(receipt)
        _require(not receipt_ids or receipt_ids == scope_ids, f"{wave_id} receipt scope differs")

        before = _counts(status_by_id)
        _require(before == previous_after, f"{wave_id} predecessor count mismatch")
        for transition in transitions:
            code = transition["artifact_type_code"]
            _require(code in status_by_id, f"{wave_id} unknown artifact: {code}")
            _require(
                status_by_id[code] == transition["from_status"],
                f"{wave_id} from-state mismatch: {code}: "
                f"{status_by_id[code]} != {transition['from_status']}",
            )
            status_by_id[code] = transition["to_status"]
            lineage_by_id[code].append(
                {
                    "wave_id": wave_id,
                    "authority_order": order,
                    "from_status": transition["from_status"],
                    "to_status": transition["to_status"],
                    "changed": transition["from_status"] != transition["to_status"],
                    "basis": transition["basis"],
                    "required_action": transition["required_action"],
                    "evidence": transition["evidence"],
                    "external_boundary": transition["external_boundary"],
                    "delta_binding": bindings["delta"],
                    "validation_binding": bindings["validation"],
                    "receipt_binding": bindings["receipt"],
                    "authority": authority,
                    "authority_transition_record": transition["authority_record"],
                }
            )
        after = _counts(status_by_id)
        _assert_count_projection(wave_id, delta, validation, receipt, before, after)
        epoch_summaries.append(
            {
                "wave_id": wave_id,
                "authority_order": order,
                "transition_count": len(transitions),
                "changed_count": sum(
                    item["from_status"] != item["to_status"] for item in transitions
                ),
                "retained_count": sum(
                    item["from_status"] == item["to_status"] for item in transitions
                ),
                "before_counts": before,
                "applied_delta": _delta_counts(before, after),
                "after_counts": after,
                "authority": authority,
                "delta_binding": bindings["delta"],
                "validation_binding": bindings["validation"],
                "receipt_binding": bindings["receipt"],
                "predecessor_bridge_bindings": bindings[
                    "predecessor_bridge_bindings"
                ],
            }
        )
        previous_after = after
        previous_receipt = paths["receipt"]

    w9_receipt_binding = _raw_binding(authority_paths["W9"]["receipt"])
    _require(
        w9_receipt_binding["sha256"] == EXPECTED_W9_RECEIPT_SHA256,
        "W9 receipt raw hash differs from handed-off authority",
    )
    epoch_summaries[-1]["final_go_seal_binding"] = _raw_binding(go_seal_path)
    epoch_summaries[-1]["historical_superseded"] = [
        {
            "kind": "PRIOR_W9_RECEIPT_RAW_HASH",
            "sha256": HISTORICAL_W9_RECEIPT_SHA256,
            "physical_path": None,
            "status": "HISTORICAL_SUPERSEDED_NOT_AUTHORITY",
        },
        {
            **historical_no_go_binding,
            "kind": "PRIOR_CENTRAL_NO_GO_REVIEW",
            "verdict": "NO_GO",
            "status": "REMEDIATION_HISTORY_NOT_TRANSITION_AUTHORITY",
        },
    ]
    _require(_counts(status_by_id) == FINAL_COUNTS, "final replay counts differ")
    _require(
        _delta_counts(INITIAL_COUNTS, FINAL_COUNTS) == FINAL_DELTA,
        "initial-to-final delta differs",
    )
    initial_na = {
        code
        for code, item in baseline_by_id.items()
        if _normalize_status(item["classification"]) == "N_A_CANDIDATE"
    }
    final_na = {code for code, status in status_by_id.items() if status == "N_A_CANDIDATE"}
    _require(initial_na == final_na and len(final_na) == 36, "N/A candidate meaning was promoted")

    internal_assignment, external_assignment = _validate_action_group_design()
    all_codes = set(baseline_by_id)
    _require(set(internal_assignment) == {code for code, status in status_by_id.items() if status == "INTERNAL_GAP"}, "internal48 membership differs from replay")
    _require(set(external_assignment) == {code for code, status in status_by_id.items() if status == "EXTERNAL"}, "external49 membership differs from replay")
    _require(not (set(internal_assignment) | set(external_assignment)) - all_codes, "action membership has unknown IDs")

    new_external = {
        code: lineage[-1]["wave_id"]
        for code, lineage in lineage_by_id.items()
        if _normalize_status(baseline_by_id[code]["classification"]) != "EXTERNAL"
        and status_by_id[code] == "EXTERNAL"
    }
    _require(
        Counter(new_external.values()) == Counter({"W1": 2, "W5": 1, "W9": 7})
        and len(new_external) == 10,
        "new external lineage must be exact W1/W5/W9 = 2/1/7",
    )

    artifact_tuples = []
    for code in sorted(baseline_by_id):
        if code in internal_assignment:
            action_kind = "INTERNAL"
            group_id = internal_assignment[code]
        elif code in external_assignment:
            action_kind = "EXTERNAL"
            group_id = external_assignment[code]
        else:
            action_kind = "NONE"
            group_id = None
        artifact_tuples.append(
            {
                "artifact_type_code": code,
                "initial_status": _normalize_status(
                    baseline_by_id[code]["classification"]
                ),
                "current_status": status_by_id[code],
                "action_kind": action_kind,
                "action_group_id": group_id,
            }
        )
    _require(len(artifact_tuples) == 257, "artifact tuple count differs")

    source_paths = [
        GENERATOR_PATH,
        BASELINE_PATH,
        WAVE_PLAN_PATH,
        go_seal_path,
        HISTORICAL_CENTRAL_NO_GO_PATH,
        *(
            path
            for wave_id in WAVE_IDS
            for path in authority_paths[wave_id].values()
        ),
        *(
            path
            for wave_id in WAVE_IDS
            for path in PREDECESSOR_BRIDGE_PATHS.get(wave_id, ())
        ),
    ]
    source_bindings, source_set_sha256 = _source_set(source_paths)
    semantic = _semantic_projection(
        artifact_tuples,
        epoch_summaries,
        internal_assignment,
        external_assignment,
    )
    external_records, external_by_code = _make_external_records(
        baseline_by_id,
        lineage_by_id,
        external_assignment,
    )

    artifacts = []
    for item in sorted(items, key=lambda value: value["artifact_type_code"]):
        code = item["artifact_type_code"]
        tuple_item = next(
            value for value in artifact_tuples if value["artifact_type_code"] == code
        )
        last_action = next(
            (
                lineage["required_action"]
                for lineage in reversed(lineage_by_id[code])
                if lineage["required_action"]
            ),
            item["required_action"],
        )
        artifacts.append(
            {
                "artifact_type_code": code,
                "prefix": item["prefix"],
                "priority": item["priority"],
                "initial_status": tuple_item["initial_status"],
                "initial_status_source_value": item["classification"],
                "current_status": tuple_item["current_status"],
                "initial_baseline_record": item,
                "transition_lineage": lineage_by_id[code],
                "evidence": {
                    "initial_evidence_paths": item["evidence_paths"],
                    "transition_evidence": [
                        lineage["evidence"] for lineage in lineage_by_id[code]
                    ],
                },
                "required_action": {
                    "initial": item["required_action"],
                    "current": last_action,
                },
                "external_boundary": {
                    "initial_dependency": item.get("external_dependency"),
                    "initial_dependency_present": item.get(
                        "external_dependency_present",
                        bool(item.get("external_dependency")),
                    ),
                    "initial_boundary": item.get("boundary"),
                    "current_external": code in external_assignment,
                    "external_action_record_id": (
                        external_by_code[code]["record_id"]
                        if code in external_by_code
                        else None
                    ),
                    "completion_eligible": False if code in external_assignment else None,
                },
                "action_assignment": {
                    "kind": tuple_item["action_kind"],
                    "group_id": tuple_item["action_group_id"],
                },
            }
        )

    source_set = {
        "canonicalization": SOURCE_SET_RULE,
        "bindings": source_bindings,
        "binding_count": len(source_bindings),
        "sha256": source_set_sha256,
    }
    snapshot = {
        "schema_version": "walksafe.artifact-audit-successor-snapshot.v1",
        "snapshot_id": "WS-FINAL-257-SUCCESSOR-20260727-001",
        "prepared_on": "2026-07-27",
        "baseline": baseline_binding,
        "authority_order": list(WAVE_IDS),
        "source_set": source_set,
        "initial_counts": INITIAL_COUNTS,
        "final_counts": FINAL_COUNTS,
        "initial_to_final_delta": FINAL_DELTA,
        "replay_epochs": epoch_summaries,
        "artifact_status_tuples": artifact_tuples,
        "artifacts": artifacts,
        "internal_action_groups": INTERNAL_ACTION_GROUPS,
        "external_action_group_index": [
            {
                "group_id": group["group_id"],
                "artifact_ids": group["artifact_ids"],
                "packet_path": _rel(EXTERNAL_PACKET_PATH),
            }
            for group in EXTERNAL_ACTION_GROUPS
        ],
        "action_membership": {
            "internal_count": 48,
            "external_count": 49,
            "overlap_count": 0,
            "unknown_count": 0,
        },
        "new_external_lineage": [
            {"artifact_type_code": code, "authority_wave_id": new_external[code]}
            for code in sorted(new_external)
        ],
        "n_a_candidate_boundary": {
            "count": 36,
            "initial_equals_current": True,
            "meaning": "APPLICABILITY_CANDIDATE_ONLY_NOT_APPROVED_NOT_COMPLETED_NOT_FINAL_N_A",
            "promotion_count": 0,
        },
        "global_boundary": GLOBAL_BOUNDARY,
        "authorization_boundary": {
            "add_only_successor": True,
            "immutable_baseline_modified": False,
            "final_independent_review_generated": False,
            "final_seal_generated": False,
            "formal_or_release_approval_granted": False,
        },
        "integrity": {
            "artifact_count": 257,
            "artifact_unique_count": 257,
            "semantic_projection": semantic,
        },
    }
    _with_content_fingerprint(snapshot)

    external_packet = {
        "schema_version": "walksafe.final-257-external-action-packet.v1",
        "packet_id": "WS-FINAL-257-EXTERNAL-ACTIONS-20260727-001",
        "prepared_on": "2026-07-27",
        "source_set": source_set,
        "artifact_status_tuples": artifact_tuples,
        "external_action_groups": [
            {
                **group,
                "records": [
                    external_by_code[code] for code in group["artifact_ids"]
                ],
            }
            for group in EXTERNAL_ACTION_GROUPS
        ],
        "external_action_records": external_records,
        "membership": {
            "external_count": 49,
            "unique_count": 49,
            "internal_overlap_count": 0,
            "unknown_count": 0,
            "new_external_count": 10,
            "new_external_authority_epochs": {
                "W1": 2,
                "W5": 1,
                "W9": 7,
            },
        },
        "current_execution_boundary": {
            "actual_event_count": 0,
            "actual_receipt_count": 0,
            "approved_decision_count": 0,
            "independent_reaudit_status": "NOT_RUN",
            "completion_eligible_count": 0,
            "synthetic_event_allowed": False,
        },
        "global_boundary": GLOBAL_BOUNDARY,
        "authorization_boundary": {
            "action_contract_is_execution": False,
            "action_contract_is_approval": False,
            "external_completion_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "integrity": {
            "external_group_count": 5,
            "external_record_count": 49,
            "semantic_projection": semantic,
        },
    }
    _with_content_fingerprint(external_packet)
    return (
        snapshot,
        external_packet,
        source_bindings,
        epoch_summaries,
        authority_paths,
        go_seal_path,
    )


def _validate_model(
    snapshot: dict[str, Any],
    external_packet: dict[str, Any],
) -> None:
    _validate_content_fingerprint(snapshot)
    _validate_content_fingerprint(external_packet)
    _require(snapshot["final_counts"] == FINAL_COUNTS, "snapshot final counts differ")
    _require(
        snapshot["initial_to_final_delta"] == FINAL_DELTA,
        "snapshot final delta differs",
    )
    _require(
        len(snapshot["artifacts"]) == 257
        and len({item["artifact_type_code"] for item in snapshot["artifacts"]}) == 257,
        "snapshot exact257 invariant differs",
    )
    _require(
        snapshot["artifact_status_tuples"] == external_packet["artifact_status_tuples"],
        "snapshot/external packet tuple parity differs",
    )
    records = external_packet["external_action_records"]
    _require(
        len(records) == len({item["artifact_type_code"] for item in records}) == 49,
        "external49 record invariant differs",
    )
    _require(
        all(
            item["actual_evidence"]["repository_relative_path"] is None
            and item["actual_evidence"]["sha256"] is None
            and item["actual_evidence"]["byte_length"] is None
            and item["actual_evidence"]["source_event_or_decision_id"] is None
            and item["actual_evidence"]["operator_identity"] is None
            and item["actual_decision"] is None
            and item["approval_status"] == "NOT_APPROVED"
            and item["synthetic_event_allowed"] is False
            and item["completion_state"]["actual_event_occurred"] is False
            and item["completion_state"]["independent_reaudit"] == "NOT_RUN"
            and item["completion_state"]["completion_eligible"] is False
            for item in records
        ),
        "external action packet invented event, authority, receipt or completion",
    )
    _require(
        snapshot["n_a_candidate_boundary"]["promotion_count"] == 0
        and snapshot["n_a_candidate_boundary"]["initial_equals_current"] is True,
        "N/A candidate boundary differs",
    )
    _require(
        snapshot["global_boundary"] == GLOBAL_BOUNDARY
        and external_packet["global_boundary"] == GLOBAL_BOUNDARY
        and GLOBAL_BOUNDARY["boundary_relaxation_count"] == 0,
        "global conservative boundary differs",
    )


def _build_outputs() -> dict[Path, bytes]:
    (
        snapshot,
        external_packet,
        source_bindings,
        epoch_summaries,
        authority_paths,
        go_seal_path,
    ) = _build_model()
    _validate_model(snapshot, external_packet)
    markdown = _render_markdown(snapshot, external_packet)
    tuple_projection = json.dumps(
        snapshot["artifact_status_tuples"],
        ensure_ascii=False,
        indent=2,
        sort_keys=True,
    )
    _require(
        tuple_projection in markdown,
        "snapshot Markdown does not losslessly project exact257 tuples",
    )

    snapshot_bytes = _json_bytes(snapshot)
    markdown_bytes = _md_bytes(markdown)
    external_bytes = _json_bytes(external_packet)
    output_bindings = sorted(
        [
            {
                "path": _rel(SNAPSHOT_PATH),
                "sha256": _sha_bytes(snapshot_bytes),
                "byte_length": len(snapshot_bytes),
            },
            {
                "path": _rel(SNAPSHOT_MD_PATH),
                "sha256": _sha_bytes(markdown_bytes),
                "byte_length": len(markdown_bytes),
            },
            {
                "path": _rel(EXTERNAL_PACKET_PATH),
                "sha256": _sha_bytes(external_bytes),
                "byte_length": len(external_bytes),
            },
        ],
        key=lambda item: item["path"],
    )
    dag = _build_binding_dag(authority_paths, go_seal_path)
    validation_matrix = [
        {"id": "BASELINE_EXACT_257", "status": "PASS", "observed": 257},
        {"id": "AUTHORITY_REPLAY_W1_W9", "status": "PASS", "observed": 9},
        {"id": "FROM_STATE_MATCH", "status": "PASS", "mismatch_count": 0},
        {"id": "CURRENT_AUTHORITY_GO", "status": "PASS", "non_go_count": 0},
        {"id": "RECEIPT_HASH_CHAIN", "status": "PASS", "missing_count": 0},
        {"id": "FINAL_COUNTS", "status": "PASS", "observed": FINAL_COUNTS},
        {"id": "INITIAL_FINAL_DELTA", "status": "PASS", "observed": FINAL_DELTA},
        {"id": "N_A_MEANING_UNCHANGED", "status": "PASS", "promotion_count": 0},
        {"id": "INTERNAL_ACTION_MEMBERSHIP", "status": "PASS", "observed": 48},
        {"id": "EXTERNAL_ACTION_MEMBERSHIP", "status": "PASS", "observed": 49},
        {"id": "ACTION_OVERLAP_UNKNOWN", "status": "PASS", "observed": "0/0"},
        {"id": "JSON_MD_PACKET_TUPLE_PARITY", "status": "PASS", "observed": 257},
        {"id": "GLOBAL_BOUNDARY_RELAXATION", "status": "PASS", "observed": 0},
        {"id": "NON_CIRCULAR_DAG", "status": "PASS", "observed": "ACYCLIC"},
        {"id": "FINAL_REVIEW_OR_SEAL_NOT_GENERATED", "status": "PASS", "observed": 0},
    ]
    semantic = snapshot["integrity"]["semantic_projection"]
    manifest = {
        "schema_version": "walksafe.final-257-audit-manifest.v1",
        "manifest_id": "WS-FINAL-257-AUDIT-MANIFEST-20260727-001",
        "prepared_on": "2026-07-27",
        "input_raw_bindings": source_bindings,
        "input_source_set": snapshot["source_set"],
        "output_raw_bindings": output_bindings,
        "replay_epochs": epoch_summaries,
        "replay_summary": {
            "epoch_count": 9,
            "authority_order": list(WAVE_IDS),
            "initial_counts": INITIAL_COUNTS,
            "final_counts": FINAL_COUNTS,
            "initial_to_final_delta": FINAL_DELTA,
        },
        "action_summary": {
            "internal_group_count": 9,
            "internal_artifact_count": 48,
            "external_group_count": 5,
            "external_artifact_count": 49,
            "overlap_count": 0,
            "unknown_count": 0,
        },
        "validation_matrix": validation_matrix,
        "binding_dag": dag,
        "global_boundary": GLOBAL_BOUNDARY,
        "authorization_boundary": {
            "baseline_modified": False,
            "wave_plan_modified": False,
            "final_independent_review_generated": False,
            "final_seal_generated": False,
            "output_manifest_self_hash_included": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "integrity": {
            "input_count": len(source_bindings),
            "output_hash_count": 3,
            "semantic_projection": semantic,
        },
    }
    _with_content_fingerprint(manifest)
    _validate_content_fingerprint(manifest)
    outputs = {
        SNAPSHOT_PATH: snapshot_bytes,
        SNAPSHOT_MD_PATH: markdown_bytes,
        EXTERNAL_PACKET_PATH: external_bytes,
        MANIFEST_PATH: _json_bytes(manifest),
    }
    _require(
        set(outputs)
        == {SNAPSHOT_PATH, SNAPSHOT_MD_PATH, EXTERNAL_PACKET_PATH, MANIFEST_PATH}
        and len(outputs) == 4,
        "expected output set must be exact four successor files",
    )
    return outputs


def write() -> dict[Path, bytes]:
    outputs = _build_outputs()
    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return outputs


def check() -> dict[Path, bytes]:
    outputs = _build_outputs()
    for path, expected in outputs.items():
        _require(path.is_file(), f"generated output missing: {_rel(path)}")
        _require(path.read_bytes() == expected, f"generated output stale: {_rel(path)}")
    return outputs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="write exact four successor files")
    mode.add_argument("--check", action="store_true", help="verify exact four successor files")
    args = parser.parse_args()
    try:
        outputs = write() if args.write else check()
    except (FinalAuditError, OSError, KeyError, TypeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    action = "wrote" if args.write else "verified"
    print(
        f"{action} {len(outputs)} final-257 successor files; "
        "OK=124 INTERNAL_GAP=48 EXTERNAL=49 N_A_CANDIDATE=36; "
        "release=NOT_ELIGIBLE"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
