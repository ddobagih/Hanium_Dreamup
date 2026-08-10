#!/usr/bin/env python3
"""Build WalkSafe SEC/WS Draft documents without inventing execution evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any, Iterable

try:
    from scripts import build_walksafe_effective_decision_register_alignment_20260721 as alignment_builder
    from scripts import build_walksafe_feature_policy_baseline_approval_20260721 as approval_builder
except ModuleNotFoundError:  # Direct execution from scripts/.
    import build_walksafe_effective_decision_register_alignment_20260721 as alignment_builder
    import build_walksafe_feature_policy_baseline_approval_20260721 as approval_builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
CONTROL_DIR = REPO_ROOT / "docs" / "control"
DELIVERABLES_DIR = REPO_ROOT / "docs" / "deliverables"
SEC_DIR = DELIVERABLES_DIR / "07-security"
WS_DIR = DELIVERABLES_DIR / "11-walksafe"
MANIFEST_DIR = DELIVERABLES_DIR / "manifests"

POLICY_PATH = CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-comprehensive-draft.json"
POLICY_APPROVAL_PATH = CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
POLICY_MANIFEST_PATH = CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
ALIGNED_DECISIONS_PATH = CONTROL_DIR / "decision-interview" / "walksafe-effective-decision-register-aligned-20260721-r001.json"
CATALOG_PATH = CONTROL_DIR / "artifact-types.json"
CHANGE_REQUESTS_PATH = DELIVERABLES_DIR / "01-management" / "registers" / "change-requests.json"
FP035_CORRECTION_CANDIDATE_PATH = CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"

SEC_PLAN_PATH = SEC_DIR / "security-and-privacy-plan.md"
SEC_VERIFICATION_PATH = SEC_DIR / "security-verification-evidence.md"
SEC_RESPONSE_PATH = SEC_DIR / "security-response-and-monitoring.md"
SEC_REGISTERS_DIR = SEC_DIR / "registers"
SEC_RISK_REGISTER_PATH = SEC_REGISTERS_DIR / "security-risks.json"
SEC_VULNERABILITY_REGISTER_PATH = SEC_REGISTERS_DIR / "vulnerabilities.json"
SEC_EXCEPTION_REGISTER_PATH = SEC_REGISTERS_DIR / "security-exceptions.json"
SEC_EVIDENCE_REGISTER_PATH = SEC_REGISTERS_DIR / "security-verification-evidence.json"

WS_SAFETY_PATH = WS_DIR / "walksafe-safety-and-policy.md"
WS_ACCEPTANCE_PATH = WS_DIR / "walksafe-acceptance-matrix.md"
WS_FIELD_PATH = WS_DIR / "field-test-safety-records.md"
WS_REGISTERS_DIR = WS_DIR / "registers"
WS_ACCEPTANCE_REGISTER_PATH = WS_REGISTERS_DIR / "acceptance-evidence.json"
WS_FIELD_INDEX_PATH = WS_REGISTERS_DIR / "field-test-external-record-index.json"

MANIFEST_PATH = MANIFEST_DIR / "sec-ws-draft-20260721-r001.json"
W9_RUNTIME_CONFIG_PATH = REPO_ROOT / "apps" / "android" / "app" / "src" / "main" / "assets" / "model-config" / "two_model_runtime.json"
W9_MODEL_REGISTER_PATH = DELIVERABLES_DIR / "08-ai-ml-data" / "registers" / "model-register.json"
W9_NAVIGATION_POLICY_PATH = REPO_ROOT / "docs" / "walksafe-v2" / "navigation_integration_policy.md"

AS_OF = "2026-07-21"
VERSION = "0.1.0"
RELEASE_STATUS = "NOT_ELIGIBLE"
BASELINE_ID = "PB-WALKSAFE-FEATURE-POLICY-1.0.0"
POLICY_CONTENT_SHA256 = "e49ffe0ee9e59de0cec173cac9425d61aa78e0ccd65980ba568045a3975e0b28"
DECISION_FINGERPRINT = "16064cabf95dc16109bfe315032905a12881cab40cf7730e97d8171d15476538"
FP035_ISSUE_ID = "ISS-POLICY-FP035-NETWORK-001"
FP035_NORMALIZATION_STATUS = "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL"
FP035_NORMALIZED_POLICY = {
    "walking": "일반 활동원본은 보행 중 휴대전화에 암호화해 저장하고 서버로 전송하지 않는다.",
    "stopped_mobile_opted_in": "사용자가 이동통신망 전송을 명시적으로 선택한 경우에만 정지 판정 뒤 이동통신망 전송을 허용한다.",
    "stopped_mobile_not_opted_in": "이동통신망 전송을 선택하지 않은 사용자는 정지 뒤에도 Wi-Fi에서만 전송한다.",
}
FP035_NORMATIVE_RULE = "일반 활동원본은 보행 중 전송하지 않는다. 사용자가 이동통신망 전송을 명시적으로 선택한 경우 보행이 정지한 뒤 허용된 이동통신망으로 전송할 수 있으며, 선택하지 않은 경우에는 Wi-Fi에서만 전송한다."

SEC_PLAN_CODES = [f"SEC-{number:02d}" for number in range(1, 10)]
SEC_VERIFICATION_CODES = [f"SEC-{number:02d}" for number in range(10, 15)]
SEC_RESPONSE_CODES = [f"SEC-{number:02d}" for number in range(15, 20)]
WS_SAFETY_CODES = ["WS-01", "WS-02", "WS-03", "WS-04", "WS-05", "WS-08", "WS-18", "WS-19", "WS-22"]
WS_ACCEPTANCE_CODES = ["WS-06", "WS-07", "WS-09", "WS-10", "WS-11", "WS-12", "WS-13", "WS-14", "WS-15", "WS-16", "WS-17", "WS-20"]
WS_FIELD_CODES = ["WS-21"]

MATERIALIZED_CODES = SEC_PLAN_CODES + SEC_RESPONSE_CODES + WS_SAFETY_CODES + ["WS-11"]
PLANNED_CODES = SEC_VERIFICATION_CODES + [code for code in WS_ACCEPTANCE_CODES if code != "WS-11"] + WS_FIELD_CODES
SCOPE_CODES = [f"SEC-{number:02d}" for number in range(1, 20)] + [f"WS-{number:02d}" for number in range(1, 23)]

BUNDLE_PATHS = {
    "BND-SEC-PLAN": "docs/deliverables/07-security/security-and-privacy-plan.md",
    "BND-SEC-VERIFICATION": "docs/deliverables/07-security/security-verification-evidence.md",
    "BND-SEC-RESPONSE": "docs/deliverables/07-security/security-response-and-monitoring.md",
    "BND-WS-SAFETY": "docs/deliverables/11-walksafe/walksafe-safety-and-policy.md",
    "BND-WS-ACCEPTANCE": "docs/deliverables/11-walksafe/walksafe-acceptance-matrix.md",
    "BND-WS-FIELD-SAFETY": "docs/deliverables/11-walksafe/field-test-safety-records.md",
}
BUNDLE_CODES = {
    "BND-SEC-PLAN": SEC_PLAN_CODES,
    "BND-SEC-VERIFICATION": SEC_VERIFICATION_CODES,
    "BND-SEC-RESPONSE": SEC_RESPONSE_CODES,
    "BND-WS-SAFETY": WS_SAFETY_CODES,
    "BND-WS-ACCEPTANCE": WS_ACCEPTANCE_CODES,
    "BND-WS-FIELD-SAFETY": WS_FIELD_CODES,
}

W9_UNIFIED_CLASS_ORDER = [
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
    "e_scooter_obstruction",
]


class SecurityWalkSafeError(ValueError):
    """Raised when controlled inputs or generated SEC/WS outputs are inconsistent."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise SecurityWalkSafeError(message)


def _reject_constant(value: str) -> None:
    raise SecurityWalkSafeError(f"non-standard JSON number is not allowed: {value}")


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
        value = json.loads(raw.decode("utf-8"), object_pairs_hook=_object_pairs, parse_constant=_reject_constant)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise SecurityWalkSafeError(f"invalid JSON: {path}: {exc}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def _sha_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha_file(path: Path) -> str:
    return _sha_bytes(path.read_bytes())


def _object_sha(value: Any) -> str:
    encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return _sha_bytes(encoded)


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _md_bytes(value: str) -> bytes:
    return (value.rstrip() + "\n").encode("utf-8")


def _rel(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _binding(path: Path) -> dict[str, str]:
    return {"path": _rel(path), "sha256": _sha_file(path)}


def _fp035_correction_candidate() -> dict[str, Any]:
    candidate = load_strict_json(FP035_CORRECTION_CANDIDATE_PATH)
    content = {key: value for key, value in candidate.items() if key != "candidate_content_sha256"}
    _require(candidate.get("candidate_content_sha256") == _object_sha(content), "FP-035 correction candidate hash differs")
    metadata = candidate.get("metadata", {})
    _require(metadata.get("approval_status") == "NOT_APPROVED", "FP-035 correction candidate approval differs")
    _require(metadata.get("effective_status") == "NOT_EFFECTIVE", "FP-035 correction candidate effectiveness differs")
    _require(candidate.get("correction", {}).get("normative_rule") == FP035_NORMATIVE_RULE, "FP-035 correction candidate rule differs")
    _require(candidate.get("planned_effective_policy", {}).get("state_change_now") is False, "FP-035 candidate changed policy state")
    boundary = candidate.get("authorization_boundary", {})
    _require(boundary.get("owner_directive_captured") is True and boundary.get("new_product_question_required") is False, "FP-035 owner directive boundary differs")
    _require(boundary.get("candidate_generation_is_approval") is False, "FP-035 candidate was treated as approval")
    return candidate


def _bullet(values: Iterable[str], empty: str = "해당 없음") -> str:
    items = list(values)
    return "<br>".join(f"- {item}" for item in items) if items else f"- {empty}"


def _validate_w9_inputs(
    runtime_config: dict[str, Any],
    model_register: dict[str, Any],
    navigation_policy: str,
) -> None:
    runtime_model = runtime_config.get("models", {}).get("unified_walksafe", {})
    registered_model = next(
        (row for row in model_register.get("models", []) if row.get("runtime_model_id") == "unified_walksafe"),
        None,
    )
    _require(registered_model is not None, "W9 unified model register row is missing")
    _require(runtime_config.get("primary_model") == "unified_walksafe", "W9 primary model differs")
    _require(runtime_model.get("classes") == W9_UNIFIED_CLASS_ORDER, "W9 runtime exact13 class order differs")
    _require(
        registered_model.get("class_schema", {}).get("class_order") == W9_UNIFIED_CLASS_ORDER,
        "W9 model-register exact13 class order differs",
    )
    _require(
        registered_model.get("class_schema", {}).get("class_count") == 13,
        "W9 model-register class count differs",
    )
    _require(
        registered_model.get("runtime_config", {}).get("sha256") == _sha_file(W9_RUNTIME_CONFIG_PATH),
        "W9 runtime config hash differs from model register",
    )
    _require(
        runtime_model.get("source_model_sha256") == registered_model.get("source_artifact", {}).get("sha256"),
        "W9 source model hash differs",
    )
    _require(
        runtime_model.get("artifact_sha256") == registered_model.get("android_artifact", {}).get("sha256"),
        "W9 TFLite model hash differs",
    )
    required_navigation_tokens = [
        "## W9 현행 호출·endpoint 계약",
        "/api/navigation/destinations/search",
        "/api/navigation/walking",
        "TMAP_TIMEOUT_SECONDS=4.0",
        "자동 provider retry budget은 0",
        "`NOT_ESTABLISHED`",
        "stale route",
        "live TMAP, quota, deployment, provider exit와 alternate validation은 모두 `NOT_RUN`",
    ]
    for token in required_navigation_tokens:
        _require(token in navigation_policy, f"W9 navigation policy token is missing: {token}")


def _validate_inputs() -> tuple[
    dict[str, Any],
    dict[str, Any],
    dict[str, Any],
    dict[str, Any],
    dict[str, Any],
    dict[str, Any],
    dict[str, Any],
    str,
]:
    policy = load_strict_json(POLICY_PATH)
    approval = load_strict_json(POLICY_APPROVAL_PATH)
    baseline_manifest = load_strict_json(POLICY_MANIFEST_PATH)
    decisions = load_strict_json(ALIGNED_DECISIONS_PATH)
    catalog = load_strict_json(CATALOG_PATH)
    change_requests = load_strict_json(CHANGE_REQUESTS_PATH)
    runtime_config = load_strict_json(W9_RUNTIME_CONFIG_PATH)
    model_register = load_strict_json(W9_MODEL_REGISTER_PATH)
    navigation_raw = W9_NAVIGATION_POLICY_PATH.read_bytes()
    _require(not navigation_raw.startswith(b"\xef\xbb\xbf"), "W9 navigation policy has UTF-8 BOM")
    try:
        navigation_policy = navigation_raw.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise SecurityWalkSafeError(f"invalid UTF-8: {W9_NAVIGATION_POLICY_PATH}: {exc}") from exc
    _validate_w9_inputs(runtime_config, model_register, navigation_policy)
    _fp035_correction_candidate()

    policy_body = {key: value for key, value in policy.items() if key != "document_content_sha256"}
    _require(policy.get("document_content_sha256") == _object_sha(policy_body), "policy body hash differs")
    _require(policy.get("document_content_sha256") == POLICY_CONTENT_SHA256, "approved policy digest differs")
    _require(len(policy.get("features", [])) == 54, "policy feature coverage differs")
    _require(len(policy.get("common_policies", [])) == 9, "common policy coverage differs")
    _require(len(policy.get("remaining_gates", [])) == 5, "remaining gate count differs")

    try:
        approval_builder.validate_approval_record(approval)
        approval_builder.validate_baseline_manifest(baseline_manifest, approval)
    except (Exception,) as exc:  # Validators expose several domain-specific errors.
        raise SecurityWalkSafeError(f"policy baseline approval validation failed: {exc}") from exc
    _require(baseline_manifest.get("metadata", {}).get("lifecycle_status") == "BASELINED", "policy is not baselined")
    boundary = baseline_manifest.get("establishment_boundary", {})
    _require(boundary.get("formal_deliverables_authorized") is True, "formal authoring is not authorized")
    _require(boundary.get("remaining_gates_are_waived") is False, "remaining gates were unexpectedly waived")
    _require(boundary.get("release_status") == RELEASE_STATUS, "release boundary differs")

    try:
        alignment_sources = alignment_builder._load_and_validate_sources()
        alignment_builder.validate_alignment(decisions, alignment_sources)
    except (Exception,) as exc:
        raise SecurityWalkSafeError(f"aligned decision register validation failed: {exc}") from exc
    _require(len(decisions.get("decisions", [])) == 135, "aligned decision count differs")
    _require(
        decisions.get("approved_baseline_binding", {}).get("decision_binding_sha256") == DECISION_FINGERPRINT,
        "aligned decision fingerprint differs",
    )

    scoped = [item for item in catalog.get("artifact_types", []) if item.get("category") in {"SEC", "WS"}]
    actual_codes = [item.get("display_code") for item in scoped]
    _require(len(actual_codes) == len(set(actual_codes)) == 41, "SEC/WS catalog coverage differs")
    _require(set(actual_codes) == set(SCOPE_CODES), "SEC/WS scope codes differ")
    by_code = {item["display_code"]: item for item in scoped}
    for code in MATERIALIZED_CODES:
        _require(by_code[code]["recommended_form"] in {"CANONICAL_DOCUMENT", "REGISTER"}, f"Draft form differs: {code}")
    for code in PLANNED_CODES:
        _require(by_code[code]["recommended_form"] in {"GENERATED_EVIDENCE", "EXTERNAL_RECORD"}, f"planned form differs: {code}")

    for bundle_id, codes in BUNDLE_CODES.items():
        for code in codes:
            _require(
                by_code[code]["recommended_bundle_id"] == bundle_id,
                f"catalog bundle differs: {code}",
            )

    fp035 = next(
        (row for row in change_requests.get("requests", []) if row.get("change_request_id") == "CR-0002"),
        None,
    )
    _require(fp035 is not None, "FP-035 change request is missing")
    _require(fp035.get("status") == FP035_NORMALIZATION_STATUS, "FP-035 source change-request status differs")
    _require(
        fp035.get("captured_owner_directive")
        == "일반 활동원본은 보행 중 전송하지 않는다. 사용자가 이동통신망 전송을 명시적으로 선택한 경우 보행이 정지한 뒤 허용된 이동통신망으로 전송할 수 있으며, 선택하지 않은 경우에는 Wi-Fi에서만 전송한다.",
        "FP-035 captured owner directive differs",
    )
    _require(fp035.get("approval_status") == "NOT_APPROVED", "FP-035 change request was unexpectedly approved")

    return (
        policy,
        baseline_manifest,
        decisions,
        catalog,
        change_requests,
        runtime_config,
        model_register,
        navigation_policy,
    )


def _catalog_by_code(catalog: dict[str, Any]) -> dict[str, dict[str, Any]]:
    return {
        item["display_code"]: item
        for item in catalog["artifact_types"]
        if item.get("category") in {"SEC", "WS"}
    }


def _features_for_code(policy: dict[str, Any], code: str) -> list[dict[str, Any]]:
    return [
        feature
        for feature in policy["features"]
        if code in feature.get("traceability", {}).get("affected_deliverables", [])
    ]


def _decisions_for_code(decisions: dict[str, Any], code: str) -> list[dict[str, Any]]:
    return [row for row in decisions["decisions"] if code in row.get("affected_artifact_type_ids", [])]


def _gates_for_features(policy: dict[str, Any], feature_ids: set[str]) -> list[dict[str, Any]]:
    return [gate for gate in policy["remaining_gates"] if feature_ids.intersection(gate["affected_feature_ids"])]


def _header(title: str, draft_codes: list[str], summary: str) -> str:
    declared = ", ".join(draft_codes) if draft_codes else "없음"
    return f"""# {title}

> Draft 산출물: {declared}  
> 문서 버전: {VERSION} · 상태: Draft · 승인: 미승인  
> 승인 입력: WalkSafe 기능 정책 기준선 1.0.0  
> 출시 상태: `NOT_ELIGIBLE` · 남은 gate: 5개 `NOT_RUN`, 면제 없음

## 이 문서를 읽는 방법

{summary}

`Draft`는 작성 가능한 정책·절차·명세를 적었다는 뜻이며 실제 시험 통과나 외부 승인을 뜻하지 않습니다. 역할명은 책임을 나타내며 서로 다른 사람이 이미 배정됐다는 뜻이 아닙니다. 1인 개발 단계에서는 가짜 검토자를 만들지 않고, 독립 검토가 필요한 항목은 실제 검토자가 참여할 때까지 미승인으로 둡니다.

## 공통 통제 경계

- 현행 정식 제품은 Android 사용자 앱과 별도 비공개 Android 관리자 앱입니다.
- Web/PWA는 재승인 전까지 레거시 참고 범위입니다.
- 위치·영상·음성·서명 등 민감 원본은 Git에 넣지 않습니다. Git에는 통제 저장소 ID, SHA-256, 접근등급, 보존기한과 판정만 둡니다.
- 구조검사 통과는 구현 완료·시험 통과·출시 가능을 의미하지 않습니다.
"""


def _artifact_contract(
    code: str,
    item: dict[str, Any],
    policy: dict[str, Any],
    decisions: dict[str, Any],
    *,
    status: str,
    canonical_path: Path,
) -> str:
    features = _features_for_code(policy, code)
    decision_rows = _decisions_for_code(decisions, code)
    feature_ids = {feature["id"] for feature in features}
    gates = _gates_for_features(policy, feature_ids)
    status_text = (
        "Draft — 정책·계획·명세 또는 사전개설 원장만 작성, 사람 검토·승인 전"
        if status == "DRAFT"
        else "Planned / NOT_RUN — 실행 증거가 아직 없으며 합격·완료를 주장하지 않음"
    )
    traces = ", ".join(f"{feature['id']}({feature['name']})" for feature in features) or "직접 기능 연결 없음; 상위 요구·설계로 추적"
    decision_refs = ", ".join(row["decision_id"] for row in decision_rows) or "직접 결정 연결 없음"
    gate_refs = ", ".join(f"{gate['id']}={gate['status']}" for gate in gates) or "직접 연결 gate 없음"
    candidates = item.get("existing_candidate_paths", [])
    return f"""
<a id="{code.lower()}"></a>
## {code} {item['title']}

**현재 판정:** {status_text}

| 관리 항목 | 확정 내용 |
|---|---|
| 목적 | {item['purpose']} |
| 적용 | {item['default_applicability']} — {item['activation_condition']} |
| 정본 형식·위치 | `{item['recommended_form']}` · `{_rel(canonical_path)}` |
| 작성자 | {item['owner_role']} |
| 검토 역할 | {', '.join(item['reviewer_roles'])} |
| 승인 역할 | {item['approver_role']} |
| 필요한 입력 | {_bullet(item['required_inputs'])} |
| 선행 산출물 | {', '.join(item['upstream_types']) or '없음'} |
| 후행 산출물 | {', '.join(item['downstream_types']) or '없음'} |
| 후보 자료 | {', '.join(f'`{path}`' for path in candidates) if candidates else '없음 — 새 증거 필요'} |
| 승인 정책 추적 | {traces} |
| 정렬 결정 추적 | {decision_refs} |
| 미실행 gate | {gate_refs} |

### 포함 내용과 완료 기준

**반드시 들어갈 내용:** {_bullet(item['required_contents'])}

**완료 판단:** {_bullet(item['completion_criteria'])}

### 갱신·대체·폐기

- 갱신: {_bullet(item['update_triggers'])}
- 검토 주기: 변경 발생 즉시. 보안 정책·원장은 활성 프로젝트 동안 최소 월 1회, 실행 증거는 새 build·기기·환경·도구 결과마다 검토합니다.
- 변경: 정책·명세는 변경요청과 영향분석 뒤 새 버전을 만들고 이전 승인본을 `Superseded`로 보존합니다. 원장은 과거 행을 지우지 않고 상태변경 행을 추가합니다. 실행 증거는 손으로 고치지 않고 새 입력 형상으로 다시 생성합니다.
- 보존·폐기: 프로젝트 생명주기와 종료계획을 따릅니다. 개인정보·외부 원본은 SEC-07 보존표와 통제 저장소 삭제 영수증을 따르며 Git에 원본을 보관하지 않습니다.
"""


def _policy_trace_table(policy: dict[str, Any], codes: list[str]) -> str:
    selected: dict[str, dict[str, Any]] = {}
    for code in codes:
        for feature in _features_for_code(policy, code):
            selected[feature["id"]] = feature
    rows = []
    for feature in sorted(selected.values(), key=lambda row: row["order"]):
        gate_ids = ", ".join(gate["id"] for gate in feature.get("remaining_gates", [])) or "없음"
        rows.append(f"| {feature['id']} | {feature['name']} | {feature['effective_policy_summary']} | {gate_ids} |")
    return "\n".join(rows)


def _gate_table(policy: dict[str, Any]) -> str:
    return "\n".join(
        f"| {gate['id']} | {gate['title']} | {gate['status']} | {gate['closure']} |"
        for gate in policy["remaining_gates"]
    )


SEC_PLAN_DETAILS: dict[str, str] = {
    "SEC-01": """
### 보안 활동을 개발에 넣는 방법

| 시점 | 반드시 할 일 | 남길 근거 | 멈춤 조건 |
|---|---|---|---|
| 요구·정책 변경 | 개인정보 항목, 권한, 외부 API와 오용 시나리오 재확인 | 변경요청·위협 연결 | 승인 정책과 충돌 |
| 설계 변경 | 신뢰 경계, 최소 권한, 암호화·삭제·장애 동작 검토 | 설계 검토 기록 | 고위험 통제 누락 |
| 코드 통합 전 | SAST·SCA·secret scan 실행 계획과 결과 연결 | SEC-10~12 instance | 차단 취약점 미해결 |
| 출시 후보 생성 | 같은 앱·서버·모델·설정 묶음으로 DAST·필요 시 침투시험 | SEC-13~14 receipt | 근거 버전 불일치 |
| 출시 승인 전 | 개인정보 독립검토와 관리자 복구훈련을 포함한 5개 gate 확인 | gate receipt | 하나라도 NOT_RUN |

현재는 계획만 Draft입니다. 실제 scan과 독립검토를 실행했다는 뜻이 아닙니다.

### 발견사항 심각도와 최초 조치 목표

아래 값은 1인 개발 단계의 관리 기본값이며 실제 보안시험 결과나 운영 SLO가 아닙니다. 더 엄격한 외부 의무가 확인되면 그 값을 우선합니다.

| 심각도 | 예 | 최초 분류 목표 | 출시·조치 원칙 |
|---|---|---:|---|
| CRITICAL | 보행 안전 직접 위해, 관리자 탈취, 민감 원본 대량 노출 | 발견 후 4 업무시간 이내 | 즉시 관련 기능·출시 차단, 격리 후 수정·재시험 전 해제 금지 |
| HIGH | 권한 우회, 원본·삭제 무결성 훼손 가능 | 1 업무일 이내 | 베타·출시 전 수정·재시험. 불가피하면 기한 있는 SEC-16 예외와 보상통제 필요 |
| MEDIUM | 제한된 조건의 노출·오용 | 5 업무일 이내 | 다음 배포 전 계획·소유자·기한 확정, 영향 확대 여부 재평가 |
| LOW | 즉시 위해가 낮은 강화 항목 | 10 업무일 이내 | 유지보수 backlog와 다음 검토일 등록 |

심각도는 낮춰 닫지 않습니다. 영향·도달 가능성·사용자 안전·개인정보를 다시 평가한 근거와 같은 대상의 재시험 증거가 있어야 상태를 바꿉니다.
""",
    "SEC-02": """
### 자산·행위자·신뢰 경계

| 구분 | 보호 대상 또는 경계 | 기본 통제 |
|---|---|---|
| Android 사용자 앱 | 로그인 토큰, 원본 대기열, 경로, 동의상태 | Android 안전 저장소, 앱별 서명, 기기별 세션, 암호화 |
| 비공개 관리자 앱 | 신고 원본, 검수·상태 변경 권한 | MFA/패스키, 단일 관리자, 모든 조회·변경 감사 |
| 서버 | 계정, 신고, 원본, 학습 승인, 삭제 상태 | 역할별 API, 저장·전송 암호화, 무결성 확인, 최소 권한 |
| 외부 TMAP | 검색·경로 요청과 응답 | 비밀키는 서버만 보유, timeout·quota·fallback |
| 통제 원본 저장소 | 영상·음성·정확 위치·서명 원본 | Git 분리, 접근 승인, hash, 보존·삭제 기록 |

### 우선 위협과 대응

| 위협 | 영향 | 예방·탐지·복구 | 현재 잔여상태 |
|---|---|---|---|
| 휴대전화 분실·토큰 탈취 | 계정·원본 노출 | 기기별 세션, 원격 폐기, 저장소 암호화 | 복구훈련 NOT_RUN |
| 관리자 계정 상실·탈취 | 신고·삭제·권한 오용 | MFA/패스키, 외부 복구수단, 고위험 작업 동결 | 복구훈련 NOT_RUN |
| 원본 저장소 또는 백업 노출 | 위치·영상·음성 대량 노출 | 키 분리, 최소 권한, 접근 감사, 유한 보존 | 독립 검토 NOT_RUN |
| 신고 중복·조작·스팸 | 잘못된 위험정보·운영 마비 | 고정 신고번호, idempotency, 중복 검수, 항소 | 통합·악용 시험 NOT_RUN |
| 경로 응답 변조·오래된 안내 | 위험한 방향 안내 | TLS, 저장 경로, 연속 GPS, 이탈 시 안내 중지 | 현장시험 NOT_RUN |
| 앱·모델·설정 변조 | 탐지·안내 오작동 | 서명, manifest, hash, 동일 release 묶음 | 기기 E2E NOT_RUN |
""",
    "SEC-03": """
### 지속 위험관리

정본 원장은 [registers/security-risks.json](registers/security-risks.json)입니다. 이 원장은 설계상 알려진 위험을 사전 등록했으며 실제 취약점을 발견했다는 보고서가 아닙니다. 가능성·영향·통제·검증·소유자·기한·수용결정을 분리하고, 위험을 닫을 때는 실행 증거나 명시적 SEC-16 수용 기록을 연결합니다.
""",
    "SEC-04": """
### 설계 검토 절차

1. 검토 대상 commit·문서·API·데이터 흐름을 하나의 기준선으로 고정합니다.
2. 인증·권한, 원본 수집, 암호화, 삭제, 외부 API, 로그, 장애, 복구의 요구를 위협별로 대조합니다.
3. 발견사항은 심각도·영향·재현조건·필수 수정·소유자·기한과 함께 SEC-15에 기록합니다.
4. 개발자 본인 확인과 독립 검토가 필요한 영역을 구분합니다. 개인정보·모델 안전·접근성 독립성은 생략하지 않습니다.
5. 수정 뒤 같은 기준으로 재검토하고, 미해결 항목은 SEC-16의 명시적 수용 없이는 닫지 않습니다.

현재 설계 검토 **절차만 Draft**이며 특정 설계가 통과했다고 판정하지 않았습니다.
""",
    "SEC-05": """
### 개인정보 영향평가 초안

승인된 정책은 동의한 활성 보행에서 무가림 원본을 수집하는 것입니다. 이 Draft는 그 결정을 재논의하지 않고 목적 제한·접근통제·보존·권리행사를 설계에 옮깁니다. 다만 출시 전 독립검토 gate는 `NOT_RUN`이고 법률 적합성을 확정하지 않습니다.

| 처리 | 필요성과 범위 | 주 위험 | 확정 통제 | 남은 확인 |
|---|---|---|---|---|
| 실시간 탐지·안내 | 가까운 위험과 이동 방향 보조 | 잘못된 안내, 주변인 원본 포함 | 안전정지, 지원환경 제한, 유한 보존 | 현장·접근성 시험 |
| 일반 활동원본 수집 | 모델·안전·성능 개선 | 과수집·재식별·유출 | 최초 명확한 동의, 암호화, 최소권한, 삭제기한 | 원본 독립검토 gate |
| 자동신고 | 손상 점자블록 검수·기관 전달 | 모르는 자동처리, 잘못된 신고 | 최초 동의, 설정 전체 끄기, 상태·삭제 결과 확인 | 실제 전체흐름 시험 |
| 관리자 검수 | 신고 근거 확인과 상태 변경 | 내부자 오용 | 단일 관리자 MFA, 목적별 접근, 감사로그 | 복구훈련 gate |
| 학습자료 승인 | 승인 원본·라벨로 모델 개선 | 삭제 요청 누락·데이터 오염 | 검역, provenance, 삭제 영수증, 재학습 기준 | AIML 독립검토 |

FP-035는 사용자 지시를 다음처럼 정규화해 포착했습니다. 일반 활동원본은 보행 중 전송하지 않습니다. 정지 판정 뒤에는 이동통신망 전송을 명시적으로 선택한 사용자만 이동통신망을 허용하고, 선택하지 않은 사용자는 Wi-Fi에서만 전송합니다. CR-0002는 이 정규화 지시를 새 산출물 묶음으로 승인하기 전까지 열어 두며, 같은 정책 선택을 사용자에게 다시 묻지 않습니다. 관련 구현 확정과 정식 시험은 묶음 승인 뒤 시작합니다.
""",
    "SEC-06": """
### 동의와 상태를 분리하는 규칙

운영체제 권한, 로그인, 전체 원본 동의, 자동신고 사용, 이동통신망 사용 선택은 서로 다른 상태입니다. 한 상태 변경을 다른 상태 변경으로 간주하지 않습니다.

| 항목 | 설명 시점·내용 | 거부·철회 효과 | 기록 |
|---|---|---|---|
| 전체 원본 수집 | 최초 동의에서 항목·목적·전송·보존·삭제를 함께 설명 | 새 원본 수집·전송 즉시 중단, 저장위치별 삭제기한 적용 | 동의문 버전·시각·상태 |
| 자동신고 | 최초 동의에서 자료·전송시점·180일 보존을 설명 | 새 후보 즉시 중단, 미전송 24시간·서버 7일 삭제 | 설정 변경·삭제 결과 |
| 이동통신망 | 별도 선택값으로 관리 | 선택하지 않으면 모바일 데이터 전송 금지 | 선택 버전·시각; FP-035 CR 연결 |
| 카메라·위치·마이크 | 기능을 처음 쓰기 직전 이유 설명 | 의존 기능만 중단, 남은 기능이 안전하지 않으면 전체 안전정지 | OS 권한 상태와 확인시각 |
| 현장시험 | 시험 목적·위험·철회·영상 처리를 별도 설명 | 참여 중단, 이후 새 수집 중단, 기존 자료는 동의서 조건에 따라 처리 | 외부 서명 원본 ID·hash만 Git 기록 |

자동신고 후보마다 알림이나 개별 취소를 제공하지 않습니다. 사용자는 설정에서 향후 자동신고를 끄고, 접근 가능한 권리요청 경로에서 개인정보 삭제와 처리 결과를 확인할 수 있습니다.
""",
    "SEC-07": """
### 데이터별 보존·삭제 기준

| 데이터 | 정상 보존 상한 | 기산점·삭제 방법 |
|---|---:|---|
| 안내용 휴대전화 경로 | 보행 종료 또는 24시간 중 먼저 | 세션 정리에서 안전 삭제 |
| 서버 수신 확인 휴대전화 사본 | 확인증 후 24시간 | 전체 파일 commit·SHA-256 일치 뒤 삭제 |
| 미전송 휴대전화 원본 | 최대 30일 | 확신도와 무관하게 만료 삭제·내부 기록 |
| 서버 수신·검역 중 원본 | 최대 14일 | 검역 판정 뒤 목적 저장소 이동 또는 삭제 |
| 서버 일반·자동신고 원본 | 최대 180일 | 생성·수신 기준 만료 삭제 |
| 승인 학습원본·라벨·고정 검증자료 | 승인 후 3년 | 승인 dataset version 단위 삭제·대체 |
| 수신구역 사본 | 승인 후 30일 또는 업로드 후 180일 중 먼저 | 승인 dataset 반영 확인 뒤 삭제 |
| 운영 백업 | 35일 순환 | 만료 세대 폐기; 복원 때 삭제 영수증 우선 재적용 |
| 삭제 영수증 | 3년 | 원본 없이 식별값 hash·처리시각·결과만 보존 |

전체 삭제 요청은 새 수집·전송을 즉시 멈추고 휴대전화 24시간, 서버 원본·검역·복사본 7일, 학습자료·라벨·가공본 30일, 백업 최대 35일 안에 처리합니다. 비용 때문에 만료되지 않은 원본을 임의로 조기 삭제하지 않습니다.

서버 용량은 주 원본 300 GiB와 백업 300 GiB를 분리합니다. 주 원본 70%는 관리자 경고, 85%는 신규 현장시험 참여자 추가 중단, 95%는 만료자료 정리 뒤 새 원본 수집 보류, 100%는 새 학습자료·자동신고 후보 생성을 조용히 보류합니다. 이 비율은 휴대전화 저장공간에 적용하지 않습니다.
""",
    "SEC-08": """
### 역할·권한 표

| 주체 | 허용 | 금지·제한 | 인증·감사 |
|---|---|---|---|
| 일반 사용자 | 자신의 계정·동의·설정·신고상태·삭제요청 | 다른 사용자·관리자 자료 접근 | 기기별 세션, 민감 변경 재확인 |
| 지정 관리자 1명 | 신고 검수·기관 전달·상태 관리·장애 확인·감사조회 | 원본 대량 내려받기, 근거 없는 삭제·정책승인 | MFA 또는 패스키, 조회·변경 이유 기록 |
| 비상 대응자 | 장애확인·신규세션 차단·검증된 복구 | 정책승인·원본 열람·임의 권한확대 | 사전 지정 제한권한, 모든 행위 감사 |
| 앱·서버 서비스 계정 | 기능별 최소 API·저장소 작업 | 대화형 로그인·범위 밖 자료 접근 | 짧은 자격증명, rotation, 서비스 ID |

관리자 휴대전화와 별도로 복구코드 또는 보안키를 보관하고, 분실 시 다른 경로로 그 기기 세션을 폐기합니다. 복구수단까지 잃으면 우회 비밀번호를 쓰지 않고 출시·권한변경·데이터삭제 같은 고위험 작업을 동결합니다.
""",
    "SEC-09": """
### 비밀정보 생명주기

| 종류 | 저장·사용 | 회전·복구 | Git 규칙 |
|---|---|---|---|
| TMAP·외부 API key | 서버 비밀 저장소에서 런타임 주입 | 노출·권한변경·정기주기 때 교체 | 값 저장 금지, 변수명 예시만 허용 |
| DB·object storage 자격증명 | 환경별 최소권한 계정 | 유출·인력/역할 변경 때 폐기·재발급 | 값·접속문자열 금지 |
| TLS·서버 서명키 | 키 저장소, 공개 인증서만 배포 | 만료 전 교체와 rollback 준비 | private key 금지 |
| Android 앱 서명키 | 사용자 앱·관리자 앱 키와 배포경로 분리 | 암호화 백업, 접근 기록, 분실 절차 | 키·복구자료 금지 |
| 관리자 복구코드·보안키 | 관리자 휴대전화 밖에 분리 보관 | 사용 후 폐기·재발급 | 원본·사진·seed 금지 |

비밀 scan 결과는 SEC-12 실행 뒤 별도 evidence instance로 남깁니다. 현재 이 문서는 scan 통과를 주장하지 않습니다.
""",
}


SEC_RESPONSE_DETAILS: dict[str, str] = {
    "SEC-15": """
### 취약점 처리 원장

정본 원장은 [registers/vulnerabilities.json](registers/vulnerabilities.json)입니다. 현재 행이 0개인 것은 “취약점이 없음”이 아니라 아직 정식 scan·침투시험 결과를 수입하지 않았다는 뜻입니다.

새 발견사항은 출처, 영향받는 정확한 build·commit·component, 심각도 근거, 재현조건, 민감하지 않은 증거 위치, 조치 담당과 기한을 기록합니다. 수정 뒤 같은 조건으로 재검증하고 evidence hash를 연결하기 전에는 `CLOSED`로 바꾸지 않습니다.
""",
    "SEC-16": """
### 위험 수용·예외 규칙

정본 원장은 [registers/security-exceptions.json](registers/security-exceptions.json)입니다. 현재 승인된 보안 예외는 0건입니다.

- 예외는 편의를 위한 영구 우회가 아니라 범위·기한·보상통제·종료조건이 있는 임시 결정입니다.
- 민감정보 노출, 권한 우회, 사용자 안전 위해, 계정·신고자료의 복구 불가능한 유실은 출시 차단 항목이며 근거 없이 수용하지 않습니다.
- 1인 개발자가 작성자와 승인 역할을 동시에 표시해 독립 검토를 가장하지 않습니다. 필요한 독립 검토는 실제 검토자가 참여할 때까지 `PENDING`입니다.
- 만료된 예외는 자동 연장하지 않고 위험을 다시 평가합니다.
""",
    "SEC-17": """
### 보안사고 대응 절차

1. **탐지·접수:** 사건 ID, 발견시각, 영향 시스템과 신고자를 기록하되 경보에 원본·정확 위치·token을 넣지 않습니다.
2. **분류:** 사용자 안전, 민감자료, 인증·권한 문제를 최우선 심각도로 봅니다.
3. **격리:** 의심 세션·키·계정을 폐기하고 필요한 경우 신규 보행 세션을 차단합니다. 활성 사용자는 안전정지 절차로 종료합니다.
4. **보존·조사:** 로그·build·설정·hash를 읽기 전용으로 보존합니다. 원본 접근은 사건 범위와 승인자를 기록합니다.
5. **복구:** 검증된 정상 버전·키·backup만 사용하고 삭제 영수증을 먼저 다시 적용합니다.
6. **통지·후속:** 법률상 또는 사용자의 즉각 행동이 필요한 경우 대상자에게 정해진 기한 안에 알리고 원인·영향·재발방지를 기록합니다.

숨은 공용 비밀번호나 검증되지 않은 우회 경로는 사고 복구수단으로 사용하지 않습니다.

| 등급 | 판단 예 | 즉시 자동·수동 조치 | 종결 조건 |
|---|---|---|---|
| SEV-1 | 활성 사용자 안전 위해, 관리자 계정 탈취, 민감 원본 광범위 노출 | 신규 보행 차단, 영향 세션·키 격리, 증거보존, 책임자 호출 | 영향범위·원인·복구·필요 통지·재발방지와 재검증 완료 |
| SEV-2 | 제한된 권한·자료 노출 또는 핵심 기능 장애 | 영향 기능 격리, 확대 감시, 안전하지 않으면 전체 정지 | 수정·재검증과 잔여위험 승인 |
| SEV-3 | 사용자 안전과 민감자료에 직접 영향 없는 제한 장애 | 기록·우회·다음 업무시간 조사 | 원인·조치·회귀시험 연결 |

지원시간 밖에도 SEV-1 경보를 버리지 않고 자동 안전조치와 증거보존을 수행합니다. 실제 연락수단·당직·법정 통지시간은 운영 전에 지역·서비스 의무를 확인해 승인하고, 현재 준비되지 않은 채널을 운영 중이라고 쓰지 않습니다.
""",
    "SEC-18": """
### 외부 취약점 신고정책 활성 조건

이 정책은 공개 베타·외부 사용·공개 접점 중 하나가 생기기 전에 활성화하는 조건부 Draft입니다. 공개 베타 진입조건에는 실제 접수주소, 담당 역할, 접근권한, 비밀신고 방법과 응답 기록 시험을 포함합니다. 활성 전에는 공개 접수 채널이 운영 중이라고 주장하지 않습니다.

활성 시에는 접수주소, 대상 범위, 금지된 시험, 안전한 조사 원칙, 최초 회신·상태 안내·수정·공개 조정 절차를 게시합니다. 신고자에게 개인정보·서비스 방해·물리적 보행시험을 요구하지 않으며, 신고를 SEC-15에 연결하고 비밀·개인정보를 제거한 receipt만 Git에 둡니다.
""",
    "SEC-19": """
### 감사로그 기준

| 사건 | 반드시 남길 최소 필드 | 금지 필드 |
|---|---|---|
| 로그인·MFA·세션 폐기 | event ID, 주체의 가명 ID, 기기 ID, 결과, 시각, 이유 | 비밀번호, token, 복구코드 |
| 관리자 조회·변경 | 대상 record ID, action, before/after 상태, 이유, 관리자 ID, 시각 | 불필요한 영상·음성·정확 위치 |
| 원본 접근·복사·삭제 | object ID, 목적, 승인 ref, actor, 결과, 시각, hash | 원본 내용 자체 |
| 동의·철회·삭제요청 | 문구 version, 상태변경, 요청 ID, 처리기한·결과 | 서명·음성 원본 |
| 비밀·키·복구 작업 | key alias, operation, actor, result, timestamp | private key·seed |
| 보안사고·차단 | incident ID, 심각도, 자동조치, 후속 상태 | 경보 수신자에게 불필요한 민감자료 |

감사기록은 수정·삭제 권한을 운영 권한과 분리하고 append-only 변경 이력을 유지합니다. 삭제 영수증은 원본 없이 3년 보존합니다. 그 밖의 감사로그 보존기간은 처리 목적과 운영·법률 검토를 거쳐 SEC-07 표에 추가하기 전까지 임의 무기한 보존하지 않습니다.

경보 규칙은 반복 로그인 실패, 관리자 권한·원본 접근, 대량 조회·삭제, 키·복구 작업, 감사 전송 중단을 우선 감시합니다. 각 경보에는 규칙 version, 최초·마지막 시각, 건수, actor 가명 ID, 조사자, 판정, 사건 ID를 남깁니다. 현재 실제 경보 규칙과 보존기간은 운영환경에 배포·검증되지 않았으므로 `NOT_RUN`이며, 수치나 URL을 꾸며 쓰지 않습니다.
""",
}


WS08_CLASS_ANALYSIS: list[dict[str, str]] = [
    {
        "class_id": "person",
        "context": "전방 보행자 접근·정지·경로 점유",
        "false_positive": "불필요한 정지와 반복 음성으로 주의 분산",
        "false_negative": "접근 보행자를 놓쳐 충돌 가능",
        "harm": "QUALITATIVE_HIGH_NOT_SCORED",
        "exposure": "도심 보도에서 빈번할 수 있으나 측정 NOT_RUN",
        "detectability": "거리·상대 움직임·연속 track으로 검출 가능성 확인 필요",
        "model_control": "후보 threshold 0.20은 미승인; 거리·track 안정화 필요",
        "policy_control": "접근 또는 경로 차단 근거가 있을 때만 제한 경고",
        "ui_control": "사람 존재가 아니라 정지·주변 확인 중심의 짧은 안내",
        "fail_closed": "거리·움직임 근거가 stale이면 방향 지시 없이 안전정지",
        "required_evidence": "거리·혼잡·가림별 FP/FN과 실기기 TTS 중재",
        "residual_field_limitation_notice": "군중·가림·역광에서 안전 보장 불가를 고지",
    },
    {
        "class_id": "bicycle",
        "context": "주행·정차 자전거의 접근 또는 통로 점유",
        "false_positive": "정차 자전거를 즉시 충돌 위험으로 과잉 경고",
        "false_negative": "빠른 접근 자전거를 놓쳐 충돌 가능",
        "harm": "QUALITATIVE_HIGH_NOT_SCORED",
        "exposure": "자전거 혼용 보도 노출 측정 NOT_RUN",
        "detectability": "상대 속도·방향·depth 연속성 검증 필요",
        "model_control": "후보 threshold 0.20은 미승인; motion gate 필요",
        "policy_control": "접근 또는 경로 차단이 확인되지 않으면 위험 확정 금지",
        "ui_control": "좌우 회피 대신 멈춤·주변 확인 안내",
        "fail_closed": "속도·거리 불신 시 경고 정밀도를 낮추고 안전정지",
        "required_evidence": "정차·접근·교차 자전거별 fixed-set와 실기기 시험",
        "residual_field_limitation_notice": "고속·가림·야간은 현장 승인 전 비지원",
    },
    {
        "class_id": "car",
        "context": "보도 진입·주차·접근 차량",
        "false_positive": "도로 옆 차량을 즉시 보행 충돌로 오인",
        "false_negative": "보도 진입 차량을 놓쳐 중대 충돌 가능",
        "harm": "QUALITATIVE_CRITICAL_NOT_SCORED",
        "exposure": "교차로·주차장 출입구 노출 측정 NOT_RUN",
        "detectability": "보행 공간 교차·상대 움직임·거리 근거 필요",
        "model_control": "후보 threshold 0.20은 미승인; ROI·motion 결합 필요",
        "policy_control": "차량 class만으로 횡단·회피 지시 금지",
        "ui_control": "즉시 멈춤과 주변 확인만 안내",
        "fail_closed": "차량 위치·움직임 불신 시 전체 안전정지",
        "required_evidence": "진입·정차·원거리 차량별 FP/FN과 near-miss 통제시험",
        "residual_field_limitation_notice": "차량 접근과 신호 안전을 보장하지 않음을 고지",
    },
    {
        "class_id": "motorcycle",
        "context": "보도·골목에서 접근하거나 정차한 이륜차",
        "false_positive": "정차 이륜차를 이동 위험으로 과잉 경고",
        "false_negative": "작고 빠른 이륜차를 놓쳐 충돌 가능",
        "harm": "QUALITATIVE_HIGH_NOT_SCORED",
        "exposure": "골목·혼용 공간 노출 측정 NOT_RUN",
        "detectability": "작은 bbox·속도·가림 조건 검증 필요",
        "model_control": "후보 threshold 0.20은 미승인; temporal 확인 필요",
        "policy_control": "접근 근거 없는 방향 지시 금지",
        "ui_control": "멈춤·주변 확인 중심 안내",
        "fail_closed": "연속 관측 실패 시 안전정지",
        "required_evidence": "거리·속도·가림별 fixed-set와 실기기 시험",
        "residual_field_limitation_notice": "고속 접근 탐지를 보장하지 않음을 고지",
    },
    {
        "class_id": "bus",
        "context": "정류장·도로 가장자리·보도 인접 대형차",
        "false_positive": "안전하게 분리된 버스를 경로 차단으로 오인",
        "false_negative": "보행 공간 침범 버스를 놓침",
        "harm": "QUALITATIVE_CRITICAL_NOT_SCORED",
        "exposure": "정류장 인접 노출 측정 NOT_RUN",
        "detectability": "큰 bbox의 부분 가림과 거리 포화 검증 필요",
        "model_control": "후보 threshold 0.20은 미승인; ROI·depth sanity 필요",
        "policy_control": "class만으로 도로 진입이나 우회 지시 금지",
        "ui_control": "정지와 주변 확인 안내",
        "fail_closed": "거리 포화·부분 검출이면 정밀 방향 안내 중지",
        "required_evidence": "정류장·원거리·부분 bbox별 FP/FN 시험",
        "residual_field_limitation_notice": "대형차 주변 사각지대 안전을 보장하지 않음",
    },
    {
        "class_id": "truck",
        "context": "공사·하역·도로 인접 대형 화물차",
        "false_positive": "분리된 화물차를 즉시 보행 위험으로 오인",
        "false_negative": "보행 공간 침범·접근 화물차를 놓침",
        "harm": "QUALITATIVE_CRITICAL_NOT_SCORED",
        "exposure": "공사·하역 구간 노출 측정 NOT_RUN",
        "detectability": "부분 bbox·큰 물체 거리·움직임 검증 필요",
        "model_control": "후보 threshold 0.20은 미승인; ROI·motion 결합 필요",
        "policy_control": "공사구간은 현장 승인 전 비지원",
        "ui_control": "정지·지원 요청 안내",
        "fail_closed": "공사·하역 맥락 또는 거리 불신 시 전체 안전정지",
        "required_evidence": "공사·하역 통제환경 FP/FN과 중단 시험",
        "residual_field_limitation_notice": "공사구간과 대형차 사각지대는 비지원 고지",
    },
    {
        "class_id": "traffic light",
        "context": "교차로 주변 신호등 객체의 존재",
        "false_positive": "신호등이 아닌 물체를 신호 안전 근거로 오인",
        "false_negative": "신호등 존재를 놓침",
        "harm": "QUALITATIVE_CRITICAL_IF_MISUSED_NOT_SCORED",
        "exposure": "교차로 노출 측정 NOT_RUN",
        "detectability": "객체 존재만 검출하며 신호 상태·보행 허용 판독 근거 없음",
        "model_control": "후보 threshold 0.30은 미승인; 신호 상태 결정에 사용 금지",
        "policy_control": "횡단보도 참고 정보만 제공하고 횡단 허용 판단 금지",
        "ui_control": "건너도 된다는 안내를 생성하지 않음",
        "fail_closed": "신호 관련 질문에는 기존 보조수단 확인 안내",
        "required_evidence": "객체 FP/FN과 신호판단 비사용 회귀시험",
        "residual_field_limitation_notice": "교통신호·차량 접근 안전을 보장하지 않음",
    },
    {
        "class_id": "normal_tactile_block",
        "context": "저장 TMAP 경로와 정렬된 가까운 점자블록",
        "false_positive": "비점자 패턴을 경로 보조로 잘못 안내",
        "false_negative": "유효한 근거리 점자블록 보조를 놓침",
        "harm": "QUALITATIVE_HIGH_NOT_SCORED",
        "exposure": "포장 패턴·마모 환경 노출 측정 NOT_RUN",
        "detectability": "TMAP 방향·camera ROI·연속성 정렬 검증 필요",
        "model_control": "후보 threshold 0.30은 미승인; route alignment gate 필요",
        "policy_control": "목적지 route graph가 아닌 근거리 보조만 허용",
        "ui_control": "정렬이 확인된 경우에만 제한적 follow 안내",
        "fail_closed": "반대·끊김·정렬 불명확이면 따라가라 안내 금지",
        "required_evidence": "패턴·방향·마모별 FP/FN과 route 정렬 시험",
        "residual_field_limitation_notice": "점자블록이 목적지까지 이어짐을 보장하지 않음",
    },
    {
        "class_id": "damaged_tactile_block",
        "context": "손상 점자블록 위험·자동신고 후보",
        "false_positive": "정상 블록을 손상으로 신고해 운영 오염",
        "false_negative": "손상 위험과 신고 후보를 놓침",
        "harm": "QUALITATIVE_HIGH_NOT_SCORED",
        "exposure": "손상 유형·조명별 노출 측정 NOT_RUN",
        "detectability": "정상/손상 혼동과 중복 후보 검증 필요",
        "model_control": "후보 threshold 0.30은 미승인; class·중복 안정화 필요",
        "policy_control": "report-only이며 자동 결과를 길안내 확정에 사용 금지",
        "ui_control": "자동신고 후보별 기본 알림 없음; 안전 영향 시 제한 경고",
        "fail_closed": "근거 불충분 후보는 기관 제출 없이 관리자 검수",
        "required_evidence": "손상 유형별 FP/FN·중복률·report trace",
        "residual_field_limitation_notice": "신고 생성이 보행 안전이나 기관 처리를 보장하지 않음",
    },
    {
        "class_id": "crosswalk",
        "context": "횡단보도 표면·경계의 참고 관측",
        "false_positive": "유사 도색을 횡단보도로 오인",
        "false_negative": "횡단보도 존재를 놓침",
        "harm": "QUALITATIVE_CRITICAL_IF_MISUSED_NOT_SCORED",
        "exposure": "도색 마모·가림별 노출 측정 NOT_RUN",
        "detectability": "객체 존재만 확인하며 차량·신호 상태는 알 수 없음",
        "model_control": "후보 threshold 0.30은 미승인; 횡단 안전판단 입력 금지",
        "policy_control": "참고 정보만 제공하고 횡단 지시 금지",
        "ui_control": "건너기 시작 안내를 생성하지 않음",
        "fail_closed": "신호·차량 근거 없으면 정지·기존 보조수단 확인",
        "required_evidence": "도색·가림별 FP/FN과 금지 안내 회귀시험",
        "residual_field_limitation_notice": "횡단 가능 여부와 교통 안전을 보장하지 않음",
    },
    {
        "class_id": "curb_step",
        "context": "보행 진행면의 턱·단차",
        "false_positive": "그림자·경계를 단차로 오인해 불필요 정지",
        "false_negative": "단차를 놓쳐 걸림·낙상 가능",
        "harm": "QUALITATIVE_HIGH_NOT_SCORED",
        "exposure": "높이·조명·거리별 노출 측정 NOT_RUN",
        "detectability": "depth·바닥면·연속 frame 검증 필요",
        "model_control": "후보 threshold 0.20은 미승인; depth sanity 필요",
        "policy_control": "이동 방향 대신 정지·주변 확인만 허용",
        "ui_control": "턱 가능성과 안전정지를 짧게 안내",
        "fail_closed": "depth 또는 바닥면 불신 시 전체 안전정지",
        "required_evidence": "높이·조명·장착별 FP/FN과 실기기 depth 시험",
        "residual_field_limitation_notice": "작은 턱·투명 경계 탐지를 보장하지 않음",
    },
    {
        "class_id": "uneven_sidewalk",
        "context": "균열·들뜸·불균일한 보도면",
        "false_positive": "무늬·그림자를 불균일 보도로 오인",
        "false_negative": "낙상 가능한 보도 손상을 놓침",
        "harm": "QUALITATIVE_HIGH_NOT_SCORED",
        "exposure": "재질·조명·젖은 노면 노출 측정 NOT_RUN",
        "detectability": "낮은 recall blocker와 표면 다양성 검증 필요",
        "model_control": "후보 threshold 0.15은 미승인; field gate 미충족",
        "policy_control": "검증 전 안전 보장·정밀 회피 지시 금지",
        "ui_control": "불확실하면 정지·주변 확인 안내",
        "fail_closed": "표면 판단 불신 시 방향 안내 중지",
        "required_evidence": "표면 재질·조명별 FP/FN과 field gate 재평가",
        "residual_field_limitation_notice": "현행 후보 recall 부족과 미세 손상 한계를 고지",
    },
    {
        "class_id": "e_scooter_obstruction",
        "context": "보행로를 막는 전동킥보드",
        "false_positive": "통로 밖 킥보드를 경로 차단으로 오인",
        "false_negative": "통로를 막는 킥보드를 놓쳐 충돌 가능",
        "harm": "QUALITATIVE_HIGH_NOT_SCORED",
        "exposure": "배치·가림·조명별 노출 측정 NOT_RUN",
        "detectability": "보행 ROI 점유·거리·가림 검증 필요",
        "model_control": "후보 threshold 0.35은 미승인; obstruction gate 필요",
        "policy_control": "안전한 이동 공간 검증 전 좌우 회피 지시 금지",
        "ui_control": "정지·주변 확인 안내",
        "fail_closed": "통과 공간 안전 미확인 시 진행 지시 금지",
        "required_evidence": "배치·가림·거리별 FP/FN과 통제경로 시험",
        "residual_field_limitation_notice": "넘어진 형태·부분 가림 탐지를 보장하지 않음",
    },
]

WS18_ENDPOINT_CONTRACTS: list[dict[str, str]] = [
    {"hop": "ANDROID_TO_GATEWAY_SEARCH", "method": "GET", "endpoint": "/api/navigation/destinations/search", "status": "DEPLOYMENT_NOT_RUN"},
    {"hop": "ANDROID_TO_GATEWAY_ROUTE", "method": "POST", "endpoint": "/api/navigation/walking", "status": "DEPLOYMENT_NOT_RUN"},
    {"hop": "BACKEND_TO_TMAP_SEARCH", "method": "GET", "endpoint": "TMAP_POI_SEARCH_URL", "status": "LIVE_TMAP_NOT_RUN"},
    {"hop": "BACKEND_TO_TMAP_ROUTE", "method": "POST", "endpoint": "https://apis.openapi.sk.com/tmap/routes/pedestrian", "status": "LIVE_TMAP_NOT_RUN"},
]

WS18_ERROR_TAXONOMY: list[dict[str, str | int]] = [
    {"error_class": "CONFIGURATION_UNAVAILABLE", "behavior": "검색·경로 시작 차단, secret 원문 없는 기능 불가 안내", "automatic_retry_budget": 0},
    {"error_class": "AUTH_OR_CONTRACT_REJECTED", "behavior": "의존성 차단·escalation, provider 자동 전환 금지", "automatic_retry_budget": 0},
    {"error_class": "QUOTA_OR_RATE_LIMITED", "behavior": "quota 상태 기록, 현재 방향안내 중지, 사용자 재요청만 허용", "automatic_retry_budget": 0},
    {"error_class": "PROVIDER_TIMEOUT", "behavior": "4초에 요청 취소, 오래된 회전안내 재사용 금지", "automatic_retry_budget": 0},
    {"error_class": "PROVIDER_UNAVAILABLE", "behavior": "빈 경로로 변환하지 않고 offline/fallback 안내", "automatic_retry_budget": 0},
    {"error_class": "INVALID_OR_OVERSIZED_RESPONSE", "behavior": "응답 폐기, 마지막 성공 경로를 새 경로로 표시 금지", "automatic_retry_budget": 0},
    {"error_class": "NO_ROUTE_OR_NO_RESULT", "behavior": "없음 안내, 임의 목적지·경로 생성 금지", "automatic_retry_budget": 0},
]

WS18_QUOTA_CONTRACTS: list[dict[str, str]] = [
    {"item": "contract_quota_burst_daily", "value": "NOT_ESTABLISHED", "owner": "OPERATIONS_OWNER_ROLE", "due": "BEFORE_LIVE_TMAP_OR_NAMED_RELEASE"},
    {"item": "quota_cost_overage_policy", "value": "NOT_ESTABLISHED", "owner": "PROJECT_OWNER_ROLE", "due": "BEFORE_LIVE_TMAP_OR_NAMED_RELEASE"},
    {"item": "provider_status_support_contract", "value": "NOT_ESTABLISHED", "owner": "OPERATIONS_OWNER_ROLE", "due": "BEFORE_LIVE_TMAP_OR_NAMED_RELEASE"},
    {"item": "quota_measurement_block_test", "value": "NOT_RUN", "owner": "QA_OWNER_ROLE", "due": "BEFORE_LIVE_TMAP_OR_NAMED_RELEASE"},
]

WS18_PLANNED_TESTS: list[dict[str, str]] = [
    {"test": "route_and_search_endpoint_contract", "status": "NOT_RUN"},
    {"test": "four_second_timeout_and_cancellation", "status": "NOT_RUN"},
    {"test": "error_taxonomy_mapping", "status": "NOT_RUN"},
    {"test": "retry_zero_and_cache_stale_rejection", "status": "NOT_RUN"},
    {"test": "offline_fallback_and_accessible_notice", "status": "NOT_RUN"},
    {"test": "monitoring_redaction_and_escalation", "status": "NOT_RUN"},
    {"test": "provider_exit_and_alternate_validation", "status": "NOT_RUN"},
]


def _w9_source_binding_table() -> str:
    rows = [
        ("runtime model config", W9_RUNTIME_CONFIG_PATH),
        ("W7 model register", W9_MODEL_REGISTER_PATH),
        ("latest navigation policy", W9_NAVIGATION_POLICY_PATH),
    ]
    return "\n".join(
        f"| {name} | `{_rel(path)}` | `{_sha_file(path)}` |"
        for name, path in rows
    )


def _ws08_detail() -> str:
    fields = [
        "class_id", "context", "false_positive", "false_negative", "harm", "exposure",
        "detectability", "model_control", "policy_control", "ui_control", "fail_closed",
        "required_evidence", "residual_field_limitation_notice",
    ]
    rows = "\n".join(
        "| " + " | ".join(row[field] for field in fields) + " |"
        for row in WS08_CLASS_ANALYSIS
    )
    return f"""
### exact13 class별 FP/FN 위해 분석

| source | path | SHA-256 |
|---|---|---|
{_w9_source_binding_table()}

물체 후보와 관측근거는 FP-019가 만들고 위험 단계와 행동은 FP-020만 결정합니다. 아래 harm·exposure·detectability는 수치점수가 아니라 분석 항목이며 수치평가와 실기기 안전성은 모두 `NOT_RUN`입니다.

| class | context | FP | FN | harm | exposure | detectability | model control | policy control | UI control | fail-closed | required evidence | residual field limitation·notice |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
{rows}

**실행 경계:** quantitative evaluation=`NOT_RUN`; Android device safety validation=`NOT_RUN`; approval=`NOT_APPROVED`; release=`NOT_ELIGIBLE`. model/TFLite hash와 class 계약의 정적 결속은 수치 합격이나 현장 안전성을 뜻하지 않습니다.
"""


def _ws18_detail() -> str:
    endpoint_rows = "\n".join(
        f"| {row['hop']} | `{row['method']}` | `{row['endpoint']}` | `{row['status']}` |"
        for row in WS18_ENDPOINT_CONTRACTS
    )
    error_rows = "\n".join(
        f"| `{row['error_class']}` | {row['behavior']} | {row['automatic_retry_budget']} |"
        for row in WS18_ERROR_TAXONOMY
    )
    quota_rows = "\n".join(
        f"| `{row['item']}` | `{row['value']}` | `{row['owner']}` | `{row['due']}` |"
        for row in WS18_QUOTA_CONTRACTS
    )
    test_rows = "\n".join(
        f"| `{row['test']}` | `{row['status']}` |"
        for row in WS18_PLANNED_TESTS
    )
    return f"""
### 지도·경로 API 장애 계약

| source | path | SHA-256 |
|---|---|---|
{_w9_source_binding_table()}

#### route·search endpoint와 deadline

| hop | method | endpoint·setting | status |
|---|---|---|---|
{endpoint_rows}

provider deadline은 `TMAP_TIMEOUT_SECONDS=4.0`, 즉 4초입니다. Android·gateway가 이 deadline을 무제한으로 늘리면 안 됩니다.

#### 오류 taxonomy와 retry 0

| error class | fail-closed·사용자 동작 | automatic retry budget |
|---|---|---:|
{error_rows}

사용자가 `새 경로 요청`을 다시 선택한 경우에만 새 요청 1건을 시작합니다. 자동 retry는 항상 0이며 `Retry-After`도 무한 예약에 사용하지 않습니다.

#### cache·stale 금지와 offline/fallback

- 새 검색·경로 요청을 대신하는 provider response cache 재사용은 `PROHIBITED`입니다.
- stale route·만료 경로·위치 신뢰가 없는 이전 회전안내 재사용은 `PROHIBITED`입니다.
- 현재 세션 경로 상태는 세션 종료 또는 24시간 중 먼저 도달할 때까지만 위치 비교·실패 설명에 사용할 수 있으며 새 경로로 승격하지 않습니다.
- network 또는 TMAP이 없으면 새 검색·경로 생성을 막고 `길안내를 현재 사용할 수 없음`, `안전한 장소에서 연결과 위치를 확인`, `길안내 종료`를 접근 가능하게 알립니다.
- 다른 provider, Web/PWA, unsigned APK로 자동 또는 수동 fallback하지 않습니다. 온디바이스 위험안내의 독립 안전성이 검증되지 않았으면 전체 보행기능을 안전정지합니다.

#### quota·비용·책임

| item | current value | owner | due |
|---|---|---|---|
{quota_rows}

`NOT_ESTABLISHED`는 무제한·무료를 뜻하지 않으며 quota·SLA·계약 receipt를 꾸며 쓰지 않습니다.

#### monitoring·escalation·provider exit

- 허용 telemetry: request count, latency, normalized error class, timeout, quota 차단, 마지막 성공 시각, 현재 세션 경로 만료.
- 금지 telemetry: 검색어, 정확 좌표, route 원문, app key, credential.
- timeout·5xx는 `OPERATIONS_OWNER_ROLE`·`SAFETY_OWNER_ROLE`, 401/403·설정 오류는 `SECURITY_OWNER_ROLE`·`OPERATIONS_OWNER_ROLE`, 429는 `OPERATIONS_OWNER_ROLE`·`PROJECT_OWNER_ROLE`, schema·약관 변경은 `TECHNICAL_OWNER_ROLE`·`PRIVACY_OWNER_ROLE`로 escalation합니다.
- provider exit 전 대체 provider 계약·데이터 처리·endpoint·quota·schema mapping·경로 의미·오류 taxonomy·offline 동작·안전시험을 새로 승인해야 하며 자동 전환하지 않습니다.

| planned test | status |
|---|---|
{test_rows}

**실행 경계:** live TMAP=`NOT_RUN`; quota validation=`NOT_RUN`; deployment=`NOT_RUN`; provider exit·alternate validation=`NOT_RUN`; release=`NOT_ELIGIBLE`.
"""


WS_SAFETY_DETAILS: dict[str, str] = {
    "WS-01": """
### 보행약자 핵심 시나리오

| 시나리오 | 시작 조건 | 정상 결과 | 실패·중지 |
|---|---|---|---|
| 목적지 없이 위험 알림 | 가입·동의·권한·기기점검·장착 통과 | 가까운 후보를 판단해 짧은 음성·필요 시 진동 | 카메라·거리·위험판단·음성 불신 시 전체 안전정지 |
| 목적지 길안내 | 신뢰 GPS와 저장된 TMAP 경로 | 큰 이동방향과 회전을 안내 | GPS 불신·이탈 의심 시 오래된 회전안내 중지 |
| 손상 점자블록 자동신고 | 최초 명확한 동의와 자동신고 ON | 후보를 조용히 대기·보행 종료 뒤 허용망 전송 | 용량만 부족하면 생성 보류·내부 기록, 사용자 알림 없음 |
| 음성 조작 | 보행 ACTIVE, 호출어·STT 준비 | 정식 명령만 현재 상태에 맞게 실행 | 낮은 확실성·미등록 명령은 실행하지 않음 |
| 장애·종료 | 뒤로가기·사용자 종료·핵심기능 장애 | 새 처리 중단, 이유·다음 행동 안내 | 재부팅 뒤 이전 보행 자동재개 금지 |

전맹과 저시력 사용자를 같은 우선순위로 지원합니다. 첫 공식 환경은 비·눈이 없는 밝은 시간의 일반 도심 보도이며, 야간·악천후·공사구간·매우 붐비는 곳은 환경별 시험 전까지 비지원입니다. 횡단보도는 참고 정보만 제공하고 기존 보행 보조수단을 대신한다고 설명하지 않습니다.
""",
    "WS-02": """
### 신고·검증·반영 상태 흐름

`CANDIDATE_LOCAL → QUEUED → UPLOADING → RECEIVED_HASH_VERIFIED → ADMIN_REVIEW → REJECTED 또는 AGENCY_SUBMITTED → RESOLVED`

- 자동신고 후보는 최초 동의 뒤 후보별 음성·진동·푸시 알림과 개별 취소 없이 생성합니다.
- 사용자가 설정에서 자동신고를 끄면 새 후보를 즉시 막고 미전송 후보를 24시간 안에 삭제합니다. 서버 원본은 삭제요청 상태로 바꿔 7일 안에 삭제합니다.
- 수동 신고는 사용자가 직접 요청하므로 성공·실패를 접근 가능한 방식으로 알립니다.
- 같은 신고번호를 재사용해 중복 전송을 막고 서버가 전체 파일·hash를 확인해야 수신 완료로 봅니다.
- 지정 관리자 한 명이 근거·위치·중복을 검수하고 기각 이유, 제출자·시각, 자료 version, 기관 receipt를 남깁니다.
- 후보별 알림은 없지만 사용자는 설정·권리요청 화면에서 자동신고 사용상태와 삭제 결과를 확인할 수 있습니다.
""",
    "WS-03": """
### 잘못된 위험정보의 안전대책

| 실패 유형 | 예방 | 사용자 안전 동작 | 사후 처리 |
|---|---|---|---|
| 오탐·오래된 신고 | 신뢰도·시간·거리·중복 검수 | 신고 지점을 확정 위험처럼 지시하지 않음 | 관리자 검수·정정·삭제 |
| 미탐·신고 누락 | 자동신고를 안전보장으로 설명하지 않음 | 기존 보조수단 유지 고지 | 오류·모델·환경 추적 |
| 악의적·반복 신고 | 고정 ID, idempotency, rate limit, 유사도 | 실시간 안내에 무검수 반영 금지 | 차단·항소·감사 |
| 관리자 오판 | 근거·사유·version·행위자 기록 | 사용자에게 상태·기각 이유 제공 | 정정·삭제 요청 경로 |

잘못된 신고 하나만으로 좌우 이동이나 위험한 우회를 지시하지 않습니다. 안전 영향이 불명확하면 “멈추세요. 주변을 확인하세요.”처럼 검증된 제한 안내를 사용합니다.
""",
    "WS-04": """
### 권한별 기능 흐름

| 권한·상태 | 사용 목적 | 거부·철회 시 |
|---|---|---|
| 카메라 | 물체·점자블록 후보와 품질 입력 | 탐지 중단; 안전한 보행 유지 불가 시 전체 안전정지 |
| 정확 위치 | TMAP 경로, 남은 거리, 도착·이탈 | 방향안내 중단; 보폭으로 위치를 대신하지 않음 |
| 마이크 | 호출어·정식 음성명령 | 음성조작 중단; 필수 조작 대안이 없으면 보행 중단 |
| 동작·회전 센서 | 이동·정지, 카메라 방향, 보폭 보조 | 의존 판단 중단, 오래된 방향 재사용 금지 |
| 로그인 | 계정·기기·동의 연결 | 세션 종료; OS 권한·서버 동의가 자동 철회되지는 않음 |
| 원본 동의 | 활성 보행 원본 수집 | 새 원본 수집·전송 중단, 삭제 절차 시작 |

이미 허용한 권한은 반복해서 묻지 않고 기능 사용 직전에 실제 OS 상태를 조용히 확인합니다. 재부팅·비정상 종료 뒤 이전 보행은 자동으로 재개하지 않습니다.

처음 거부했거나 운영체제에서 영구 거부된 권한은 반복 팝업으로 압박하지 않습니다. 필요한 기능을 선택했을 때 이유와 중단되는 기능을 TalkBack으로 설명하고 `설정 열기`와 `취소`를 제공합니다. 설정에서 돌아오면 실제 권한 상태를 다시 확인할 뿐 자동으로 보행을 시작하지 않습니다. 권한을 다시 허용해도 로그인·원본 동의·자동신고·이동통신망 선택을 함께 켠 것으로 간주하지 않습니다.
""",
    "WS-05": """
### 위치·영상·음성 원본의 흐름

동의한 사용자의 활성 보행 시작부터 일시중지·종료·의존 권한 철회·전체 삭제요청·용량상 수집 보류 전까지 영상, RGB·depth·confidence, 마이크 음성과 STT, 정확 위치·속도·방향, TMAP 검색·경로, 가속도·회전·보폭·걸음, 탐지·위험·모델·설정, 자동신고, 성능·오류·전송 원본을 수집합니다. 주변인의 얼굴·번호판·목소리도 가리지 않은 수집 원본 범위에 포함합니다.

| 위치 | 정상 보존 | 전체 삭제요청 |
|---|---:|---:|
| 휴대전화 수신확인 사본 | 확인 뒤 24시간 | 24시간 |
| 휴대전화 미전송 원본 | 최대 30일 | 24시간 |
| 서버 수신·검역 | 최대 14일 | 7일 |
| 서버 일반·자동신고 원본 | 최대 180일 | 7일 |
| 승인 학습자료·라벨 | 승인 뒤 3년 | 30일 내 삭제·제외 |
| 운영 백업 | 35일 순환 | 최대 35일 |

민감 원본은 Git에 저장하지 않습니다. Git에는 외부 object ID, hash, 보존기한, 권한등급과 삭제 결과만 기록합니다.
""",
    "WS-08": _ws08_detail(),
    "WS-18": _ws18_detail(),
    "WS-19": """
### 악용·중복·스팸 통제

| 통제 | 규칙 | 잘못 차단했을 때 |
|---|---|---|
| idempotency | 기기·세션·신고 고정 ID로 같은 쓰기 중복 방지 | 기존 상태 조회 뒤 이어 처리 |
| rate limit | 계정·기기·네트워크·시간창을 함께 보되 접근성 사용을 일괄 차단하지 않음 | 관리자 재검토·해제 |
| 유사도 | 가까운 위치·시간·내용·파일 hash를 후보로 묶음 | 원본 신고는 보존기간 안에서 별도 추적 |
| 신뢰·검수 | 자동 결과만으로 기관 전달·길안내 반영 금지 | 기각 이유·정정 경로 제공 |
| 감사 | 차단 근거·규칙 version·actor·시각·결과 기록 | 항소 결과를 원장에 추가 |

저장공간 부족 시 임시 cache, 서버 수신 확인 사본, 만료 선택 학습자료, 만료 낮은 신뢰 미전송 후보 순으로 정리합니다. 그래도 부족하면 새 학습자료·자동신고 후보만 조용히 보류하고 실시간 탐지·길안내는 유지합니다.

### 초기 서버 보호값

아래 값은 공개 베타 전 부하·악용시험으로 조정할 `DRAFT_INITIAL_NOT_APPROVED` 기본값입니다. 한도를 넘긴 자료를 지우거나 자동으로 악성으로 확정하지 않고 대기·검수로 보냅니다.

| 대상 | 초기값 | 초과 처리 |
|---|---:|---|
| 같은 idempotency key | 24시간 동안 같은 결과 재사용 | 새 신고를 만들지 않고 기존 상태 반환 |
| 수동 신고 | 계정·기기 합산 5건/분, 30건/시간 | 추가 요청은 지연·검수 대기, 접근 가능한 오류와 재시도 시점 제공 |
| 자동신고 후보 | 기기당 120건/시간 | 새 후보는 검수 대기열로 격리하고 원본은 보존정책 적용 |

실제 사용자 접근성을 막는 오탐이 생기면 즉시 해제·항소할 수 있어야 합니다. 수치는 rule version과 설정 hash로 관리하고 변경 전후 악용·정상 사용 시험을 남깁니다.
""",
    "WS-22": """
### 사용자에게 반드시 알릴 제한

1. WalkSafe는 위험과 이동 방향을 추가로 알려주는 Android 보행 보조수단이며 흰지팡이·안내견·보행훈련을 대신하거나 안전을 보장하지 않습니다.
2. 첫 공식 환경은 비·눈이 없는 밝은 시간의 일반 도심 보도입니다. 야간·악천후·공사구간·매우 붐비는 곳은 시험·승인 전까지 지원하지 않습니다.
3. 횡단보도에서는 참고 정보만 제공합니다. 교통신호·차량 접근 안전을 보장하지 않습니다.
4. GPS·TMAP·카메라·거리·모델·음성 중 핵심 입력을 믿을 수 없으면 이유를 말하고 관련 안내 또는 전체 보행기능을 멈춥니다.
5. 자동신고는 손상 점자블록 신고를 돕지만 보행안전을 보장하지 않으며 후보마다 알리거나 취소받지 않습니다.
6. 사용자가 동의하면 활성 보행 원본과 주변인의 얼굴·번호판·목소리를 가리지 않은 원본을 수집합니다. 수집 항목·전송·보존·삭제는 최초 동의에서 설명합니다.
7. 긴급상황에는 WalkSafe만 의존하지 말고 평소 사용하던 이동지원 수단과 지역 긴급·지원 연락처를 사용합니다.

이 고지는 최초 설명, 동의, 보행 시작 전 교육, 장애 화면, 사용자 설명서와 릴리스 노트에서 같은 뜻으로 유지합니다.
""",
}


WS11_DETAIL = """
### 정식 음성 명령·의도 매핑

| 대표 명령 | 정식 intent | 실행 가능한 상태 | 확인·실패 동작 |
|---|---|---|---|
| 목적지 찾기 | `SEARCH_DESTINATION` | 보행 준비·길안내 미시작 | 검색 실패를 알리고 아무 경로도 시작하지 않음 |
| 다음 후보 | `NEXT_DESTINATION_CANDIDATE` | 목적지 후보 읽는 중 | 마지막 후보면 더 없음을 안내 |
| 이 장소 선택 | `SELECT_DESTINATION` | 후보가 하나 선택 가능 | 선택한 장소를 짧게 다시 확인 |
| 길안내 시작 | `START_NAVIGATION` | 목적지·GPS·TMAP·필수 점검 통과 | 하나라도 실패하면 시작하지 않고 이유 안내 |
| 잠시 멈춰 | `PAUSE_WALK` | 보행 ACTIVE | 새 처리와 안내를 안전하게 멈추고 상태 안내 |
| 다시 시작 | `RESUME_WALK` | 명시적 PAUSED, 원인 해소 | 권한·입력·연결을 재검사한 뒤만 재개 |
| 길안내 종료 | `STOP_NAVIGATION` | 길안내 중 | 길안내만 종료하고 위험알림 유지 여부를 명확히 안내 |
| 현재 상태 | `READ_STATUS` | 가입 후 모든 안전 상태 | 민감 원본을 읽지 않고 핵심 상태만 안내 |
| 신고해 줘 | `CREATE_MANUAL_REPORT` | 신고 입력 준비 | 수동 신고 성공·실패는 사용자에게 안내 |
| 도움말 | `READ_HELP` | 조작 가능한 상태 | 현재 상태에서 가능한 짧은 명령만 안내 |

호출어는 `길라잡이` 정책을 따르지만 허용 발음·듣기 시간·정확도·기기 내 STT 조합은 소음환경 시험 전까지 확정 증거가 없습니다. 미등록 문장이나 낮은 확실성은 비슷한 명령으로 추측해 실행하지 않습니다. 위험 안내가 확인 대화·길안내·부가 안내보다 우선합니다. 로그아웃·계정삭제는 음성으로 요청할 수 있어도 현재 보행을 안전하게 끝낸 뒤 TalkBack 가능한 확인 화면에서 본인확인과 결과 설명을 거쳐야 합니다.
"""


PLANNED_PROTOCOLS: dict[str, dict[str, Any]] = {
    "SEC-10": {
        "activation": "Android 사용자·관리자 앱과 backend의 이름 붙인 source commit 및 분석 구성이 준비될 때 실행",
        "procedure": ["source·generated code·제외경로를 고정", "선정한 SAST 도구·규칙·버전을 기록", "결과를 triage하고 SEC-15와 연결", "수정 뒤 같은 구성으로 재실행"],
        "evidence": ["commit SHA", "도구·규칙 version", "원본 report의 외부 저장소 ID·SHA-256", "심각도별 건수와 disposition", "재검증 receipt"],
        "pass": "민감정보 노출·권한 우회·안전 위해 차단 항목 0, 그 밖의 미해결 high/critical은 SEC-16 승인 없이 0",
        "blocker": "실행하지 않았으므로 현재 결과는 NOT_RUN",
    },
    "SEC-11": {
        "activation": "실제 lock file·SBOM·Android/backend build가 같은 release candidate에 묶일 때 실행",
        "procedure": ["직접·전이 의존성 전체 수집", "취약점 DB 시각과 도구 version 기록", "도달 가능성·영향·license를 triage", "update 또는 보상통제 뒤 재검사"],
        "evidence": ["lock/SBOM hash", "DB snapshot 시각", "component·CVE·severity·reachability", "조치·예외 ID", "재검증 receipt"],
        "pass": "출시 차단 취약점 0, 나머지는 기한·소유자 또는 승인 예외 연결",
        "blocker": "이 Draft에서 SCA를 실행하지 않음",
    },
    "SEC-12": {
        "activation": "전체 Git history와 release source 범위가 확정될 때 실행",
        "procedure": ["현재 tree와 필요한 history 범위를 scan", "후보 값을 원문 노출 없이 검증", "실제 secret이면 즉시 폐기·회전", "history·artifact·log 노출범위를 조사"],
        "evidence": ["scan scope·commit", "도구·rule version", "후보·확인 건수", "폐기·회전 receipt", "민감값 없는 report hash"],
        "pass": "검증된 활성 secret 0, 발견 secret은 모두 폐기·회전·영향조사 완료",
        "blocker": "이 Draft에서 secret scan을 실행하지 않음",
    },
    "SEC-13": {
        "activation": "원격 접근 가능한 staging API·관리자 접점과 test account가 준비된 경우에만 활성",
        "procedure": ["승인된 endpoint·method·account 범위 고정", "운영·실사용자 자료가 없는 격리환경 사용", "인증·권한·입력·rate limit·민감응답 검사", "발견사항을 SEC-15에 연결"],
        "evidence": ["staging build·OpenAPI hash", "도구·policy version", "request/response를 비식별화한 receipt", "결함·재검증 ID"],
        "pass": "민감정보 노출·권한 우회·안전 위해 결함 0, 활성 범위 전체 coverage",
        "blocker": "조건부 적용 평가와 실행환경이 아직 없음",
    },
    "SEC-14": {
        "activation": "이름 붙인 release candidate와 독립 시험자·범위·허가가 정해진 경우에만 활성",
        "procedure": ["서면 scope·금지행위·시험창 승인", "외부 시험자가 앱·API·관리자·저장 흐름 검증", "원본 report를 통제 저장소에 보관", "요약 finding·hash만 Git에 기록", "조치 뒤 독립 재시험"],
        "evidence": ["시험자·계약/승인 ID", "release manifest hash", "외부 원본 report ID·SHA-256", "findings와 remediation", "retest 서명 receipt"],
        "pass": "독립 시험 범위 완료, 출시 차단 finding 0, 잔여위험은 명시적 수용",
        "blocker": "독립 시험자·release candidate·외부 원본이 없음",
    },
    "WS-06": {
        "activation": "지원 Android 기기·장착·통제 경로와 WS-21 안전계획이 준비되면 실행",
        "procedure": ["정확도 좋음·나쁨·음영·점프를 재현", "한 점이 아닌 정확도·경로거리·연속관측 확인", "이탈 의심·확정·사용자 선택을 기록", "GPS 불신 때 방향안내 중지를 확인"],
        "evidence": ["기기·OS·APK·경로 hash", "가명화 GPS trace 외부 ID", "정확도·이탈·도착 판정", "안내·중지 시각", "결함 ID"],
        "pass": "사전 승인한 거리·시간 기준과 모든 안전중지 case 충족",
        "blocker": "기기별 실제 수치 기준과 현장 실행이 없음",
    },
    "WS-07": {
        "activation": "개발자 통제시험 통과 후 WS-21 참여자 동의·안전요원·중단계획이 준비되면 실행",
        "procedure": ["비차량 통제경로부터 단계 확대", "장착 품질·GPS·TMAP·탐지·음성을 함께 실행", "중단조건을 안전요원이 즉시 적용", "near miss와 우회 행동까지 기록"],
        "evidence": ["참여·동의 external record ID", "route·날씨·시간", "APK·model·config hash", "관찰·중단·결함", "개인정보 편집본 hash"],
        "pass": "승인 시나리오 통과, 안전 위해·중단 미처리 0, 대상 사용자 확인",
        "blocker": "현장시험·참여자 동의가 실행되지 않음",
    },
    "WS-09": {
        "activation": "지원 후보 Android 기기·OS·장착방법·TFLite model이 고정되면 실행",
        "procedure": ["카메라·depth 가용성 사전점검", "전체 동시기능을 장시간 실행", "평균뿐 아니라 p95·누락·오래된 frame·온도·배터리 기록", "기기별 전체/거리제한/사용불가 판정"],
        "evidence": ["기기 ID·OS", "APK·model·delegate·config hash", "FPS·latency·drop·thermal·battery", "장착·조명 조건", "지원판정"],
        "pass": "사전 승인한 기기별 성능·안전정지 기준 충족",
        "blocker": "지원 기기별 임계값과 실측 결과가 없음",
    },
    "WS-10": {
        "activation": "학습 model과 배포 TFLite, 고정 검증 dataset·전처리·threshold가 준비되면 실행",
        "procedure": ["입력 dataset와 두 model hash 고정", "동일 전처리·후처리로 inference", "출력·class·box·score 차이 비교", "허용오차 밖 sample 원인 분석"],
        "evidence": ["source/TFLite model hash", "dataset split hash", "runtime·delegate version", "sample별 비교", "허용오차와 판정"],
        "pass": "사전 승인한 동등성 허용오차와 안전 class 기준 충족",
        "blocker": "AIML-21 동등성 protocol·실행 결과가 없음",
    },
    "WS-12": {
        "activation": "기기 내 한국어 호출어·STT 후보, 정식 명령, 목표 사용자 발화와 소음조건이 준비되면 실행",
        "procedure": ["무음·도심·교통·대화 소음 단계 고정", "정식·유사·미등록 발화를 균형 실행", "명령별 정인식·오실행·무실행·지연 측정", "위험 안내 중재를 함께 검사"],
        "evidence": ["기기·STT model hash", "가명화 audio external ID", "소음 조건", "intent confusion matrix", "false activation·latency"],
        "pass": "사전 승인한 명령별 오실행 상한·정확도·지연 충족",
        "blocker": "STT 조합·허용 발음·시간·수치 기준과 실행이 없음",
    },
    "WS-13": {
        "activation": "Android TTS·진동 pattern과 전체 위험·길안내 우선순위 build가 준비되면 실행",
        "procedure": ["정상·중첩·offline·TTS 실패 case 실행", "위험 안내가 다른 발화를 중단하는지 확인", "중대 위험 진동 구분성과 장애 안전정지 확인", "TalkBack 충돌 여부 기록"],
        "evidence": ["기기·OS·APK", "TTS engine·voice", "진동 pattern version", "안내 시작·종료 시각", "판정·결함"],
        "pass": "모든 필수 안내·중재·장애 안전정지 case 충족",
        "blocker": "실기기 TTS·진동 결과가 없음",
    },
    "WS-14": {
        "activation": "가입·로그인·동의·권한·장애·계정관리 화면이 Android build에 준비되면 실행",
        "procedure": ["TalkBack만으로 처음부터 끝까지 조작", "초점 순서·label·상태·오류·결과 확인", "큰 글자·화면회전·키보드/스위치와 병행", "보행 음성과 화면읽기 충돌 검사"],
        "evidence": ["기기·OS·TalkBack version", "APK hash", "화면별 절차·결과", "접근성 tree 또는 편집 영상 hash", "결함"],
        "pass": "필수 흐름을 화면을 보지 않고 완료, 접근 불가 차단결함 0",
        "blocker": "TalkBack 실제 실행 증거가 없음",
    },
    "WS-15": {
        "activation": "정식 Android 화면과 design token·지원 글자배율이 고정되면 실행",
        "procedure": ["색 대비·색 외 표시 측정", "글자 확대·reflow·잘림 확인", "touch target·간격·orientation·zoom 확인", "오류·focus 표시와 실제 조작 검사"],
        "evidence": ["APK·화면·design token version", "도구 version", "측정값·화면별 판정", "실기기 편집 screenshot hash", "결함"],
        "pass": "승인 접근성 기준 충족, 필수 화면 차단결함 0",
        "blocker": "정식 build 기준 실기기 결과가 없음",
    },
    "WS-16": {
        "activation": "Web/PWA를 공식 지원 제품으로 별도 재승인한 경우에만 활성",
        "procedure": ["재승인된 manifest·service worker·offline 범위를 고정", "설치·standalone·offline·queue·update·rollback 실행", "Android 정식 범위와 증거를 섞지 않음"],
        "evidence": ["재승인 변경요청", "Web build hash", "browser/OS", "cache·update trace", "판정·결함"],
        "pass": "재승인된 Web/PWA acceptance 기준 충족",
        "blocker": "현재 기준선에서는 NOT_ACTIVE_CURRENT_BASELINE이며 실행하지 않음",
    },
    "WS-17": {
        "activation": "둘 이상의 Android OS·기기 조합을 공식 지원할 때 활성",
        "procedure": ["지원 후보별 카메라·GPS·마이크·sensor·storage 사전점검", "핵심 가입→보행→신고→장애 흐름 실행", "전체/부분/비지원 등급과 우회·결함 기록", "Web/PWA 재승인 전 browser 열을 만들지 않음"],
        "evidence": ["기기 model·OS", "APK·model·config", "기능별 result", "제약·결함", "지원등급·검토일"],
        "pass": "공식 지원 조합마다 필수 흐름 결과와 차단결함 0",
        "blocker": "지원 matrix와 실기기 결과가 없음",
    },
    "WS-20": {
        "activation": "이름 붙인 Android APK·앱·model·설정·서버와 실제 휴대전화 환경이 준비되면 실행",
        "procedure": ["동일 release 묶음으로 가입·동의·권한부터 실행", "카메라 탐지·거리·길안내·호출어·STT·TTS·진동·신고 queue 동시 실행", "장애·복구·종료까지 기록", "민감 원본은 통제 저장소에 두고 Git에는 편집본·hash만 기록"],
        "evidence": ["Android 기기·OS", "APK·model·config·server manifest hash", "시나리오·시간 동기화", "편집 영상·log external ID·hash", "판정·결함"],
        "pass": "동일 release 묶음의 승인 E2E 시나리오 통과, 안전·개인정보·접근성 차단결함 0",
        "blocker": "Android 실제 휴대전화 E2E를 실행하지 않음",
    },
    "WS-21": {
        "activation": "사람이 참여하는 정지·실외 보행시험 전에 활성",
        "procedure": ["참여·제외 기준과 접근 가능한 설명 준비", "경로·날씨·장착·안전요원·중단·응급계획 승인", "참여자별 동의·철회·영상처리 서명", "원본은 외부 통제 저장소에 보관", "시험 종료·철회·사고 기록 연결"],
        "evidence": ["내부 안전계획 version·승인", "참여자별 external record ID", "signer·signed_at·원본 SHA-256", "철회·중단·사건 상태", "보존·삭제기한"],
        "pass": "시험 전 안전계획 승인과 모든 참여자의 유효한 동의·중단권·비상대응 준비",
        "blocker": "실제 참여자·외부 서명 원본·안전계획 승인이 없음",
    },
}


def _security_plan_document(policy: dict[str, Any], decisions: dict[str, Any], catalog: dict[str, Any]) -> str:
    by_code = _catalog_by_code(catalog)
    text = _header(
        "WalkSafe 보안·개인정보 계획",
        SEC_PLAN_CODES,
        "보안과 개인정보를 개발·설계·운영에 어떻게 적용하고, 무가림 원본·권한·비밀값·삭제를 어떤 규칙으로 통제하는지 설명합니다.",
    )
    text += """

## 승인 입력과 현재 경계

- 원본 수집 정책은 승인됐습니다. 동의한 활성 보행에서 영상·음성·정확 위치·센서·탐지·경로·신고·성능과 주변인의 얼굴·번호판·목소리를 가리지 않은 수집 원본을 다룹니다.
- 원본 정책의 출시 전 독립검토는 아직 실행되지 않았습니다. 따라서 정책 재논의 없이 설계는 진행하되 출시 승인은 차단됩니다.
- FP-035 일반 활동원본은 보행 중 전송하지 않고, 정지 뒤 이동통신망 명시 선택 시에만 이동통신망을 허용하며 미선택 시 Wi-Fi만 허용하는 것으로 정규화 지시를 포착했습니다. 정정 후보는 `NOT_APPROVED/NOT_EFFECTIVE`, CR-0002는 새 산출물 묶음 승인 대기 상태이고 관련 시험은 `NOT_RUN`입니다.
- 민감 원본과 서명 원본은 Git 밖 통제 저장소에 두고, 이 저장소에는 식별자·hash·상태·보존기한만 둡니다.
"""
    for code in SEC_PLAN_CODES:
        text += _artifact_contract(code, by_code[code], policy, decisions, status="DRAFT", canonical_path=SEC_PLAN_PATH)
        text += SEC_PLAN_DETAILS[code]
    text += f"""

## 승인 정책 직접 추적

| 기능 정책 | 기능 | 이 문서에 반영한 확정 정책 | 관련 gate |
|---|---|---|---|
{_policy_trace_table(policy, SEC_PLAN_CODES)}

## 남은 gate

| ID | 제목 | 상태 | 완료조건 |
|---|---|---|---|
{_gate_table(policy)}

이 문서의 구조검사는 보안 설계 통과나 법률 적합성 승인이 아닙니다.
"""
    return text


def _planned_contract(
    code: str,
    item: dict[str, Any],
    protocol: dict[str, Any],
    policy: dict[str, Any],
    decisions: dict[str, Any],
    canonical_path: Path,
) -> str:
    text = _artifact_contract(code, item, policy, decisions, status="PLANNED", canonical_path=canonical_path)
    return text + f"""

### 실행 계약

- 활성 조건: {protocol['activation']}
- 실행 절차: {_bullet(protocol['procedure'])}
- 필요한 증거: {_bullet(protocol['evidence'])}
- 합격·완료 조건: {protocol['pass']}
- 현재 미완료 사유: {protocol['blocker']}
- 실행 상태: `NOT_RUN`
"""


def _security_verification_document(policy: dict[str, Any], decisions: dict[str, Any], catalog: dict[str, Any]) -> str:
    by_code = _catalog_by_code(catalog)
    text = _header(
        "WalkSafe 보안 검증 증거 계획",
        [],
        "아직 실행하지 않은 보안 scan·동적검사·외부 침투시험의 실행 조건과 증거 형식을 정합니다. 이 파일 자체는 시험 결과가 아닙니다.",
    )
    text += """

## 현재 실행 상태

아래 다섯 보안 검증 산출물은 모두 `Planned / NOT_RUN`입니다. 보고서·취약점 0건·통과 서명·외부 시험 receipt는 아직 없습니다. 기존 파일이나 도구 흔적을 결과로 승격하지 않습니다.
"""
    for code in SEC_VERIFICATION_CODES:
        text += _planned_contract(code, by_code[code], PLANNED_PROTOCOLS[code], policy, decisions, SEC_VERIFICATION_PATH)
    text += """

## 증거 보관 규칙

- 정본 실행 상태는 [registers/security-verification-evidence.json](registers/security-verification-evidence.json)에 기록합니다.
- 원본 scanner report와 침투시험 보고서는 통제 저장소에 보관하고 Git에는 도구·입력 형상·판정·외부 ID·SHA-256만 둡니다.
- 결과는 손으로 고치지 않습니다. 입력 commit·build·도구·규칙이 바뀌면 새 evidence ID로 다시 생성합니다.
- 미실행 항목을 `PASS`, `0건`, `완료`, `면제`로 바꾸지 않습니다.
"""
    return text


def _security_response_document(policy: dict[str, Any], decisions: dict[str, Any], catalog: dict[str, Any]) -> str:
    by_code = _catalog_by_code(catalog)
    text = _header(
        "WalkSafe 보안 대응·모니터링",
        SEC_RESPONSE_CODES,
        "보안 위험·취약점·예외·사고·외부 신고·감사기록을 어떻게 접수하고 추적하며 닫는지 정합니다.",
    )
    text += """

## 운영 원칙

사건·취약점·예외 원장은 append-only로 관리합니다. 발견 전 상태와 수정 뒤 상태를 덮어쓰지 않고 별도 시각·근거를 남깁니다. 관리자 화면이나 관측 기능만 고장 난 경우 실시간 보행 자원을 침범하지 않게 해당 기능을 멈추고 내부 경보를 남기며, 실시간 안전기능을 믿을 수 없을 때만 사용자에게 이유를 알리고 전체 안전정지합니다.
"""
    for code in SEC_RESPONSE_CODES:
        text += _artifact_contract(code, by_code[code], policy, decisions, status="DRAFT", canonical_path=SEC_RESPONSE_PATH)
        text += SEC_RESPONSE_DETAILS[code]
    text += f"""

## 승인 정책 직접 추적

| 기능 정책 | 기능 | 이 문서에 반영한 확정 정책 | 관련 gate |
|---|---|---|---|
{_policy_trace_table(policy, SEC_RESPONSE_CODES)}
"""
    return text


def _walksafe_safety_document(policy: dict[str, Any], decisions: dict[str, Any], catalog: dict[str, Any]) -> str:
    by_code = _catalog_by_code(catalog)
    text = _header(
        "WalkSafe 기능 안전·정책",
        WS_SAFETY_CODES,
        "비전공자도 기능이 언제 시작되고 무엇을 판단하며 실패하면 어떻게 멈추는지 이해할 수 있도록 WalkSafe 핵심 흐름을 정리합니다.",
    )
    text += """

## 기능 간 책임 경계

| 입력·기능 | 맡는 판단 | 맡지 않는 판단 |
|---|---|---|
| 물체 후보 탐지 | 후보 class·box·confidence·관측근거 | 위험 단계·사용자 행동 결정 |
| 위험 평가 | 거리·움직임·경로를 합쳐 위험 단계·제한 행동 결정 | 검증 전 좌우 이동 지시 |
| GPS | 실제 이동 위치·방향, 경로와의 거리 | 카메라가 보는 방향 |
| 저장 TMAP 경로 | 가야 할 큰 방향·회전·남은 거리의 주 기준 | 실시간 장애물 안전 판단 |
| 회전센서·ARCore | 카메라가 보는 방향 | 사용자의 실제 위치 확정 |
| 보폭 | 남은 거리·도착·이탈의 진행량 보조 | 위치·방향 대체 판단 |

GPS를 믿을 수 없으면 보폭으로 위치나 방향을 대신 정하지 않고 방향 안내를 일시중지합니다.
"""
    for code in WS_SAFETY_CODES:
        text += _artifact_contract(code, by_code[code], policy, decisions, status="DRAFT", canonical_path=WS_SAFETY_PATH)
        text += WS_SAFETY_DETAILS[code]
    text += f"""

## 승인 정책 직접 추적

| 기능 정책 | 기능 | 이 문서에 반영한 확정 정책 | 관련 gate |
|---|---|---|---|
{_policy_trace_table(policy, WS_SAFETY_CODES)}

## 현재 출시 판단

안전정책은 Draft로 작성됐지만 실제 휴대전화·현장·접근성·보안·원본 독립검토 증거가 없습니다. 5개 gate는 모두 `NOT_RUN`이고 출시는 `NOT_ELIGIBLE`입니다.
"""
    return text


def _walksafe_acceptance_document(policy: dict[str, Any], decisions: dict[str, Any], catalog: dict[str, Any]) -> str:
    by_code = _catalog_by_code(catalog)
    text = _header(
        "WalkSafe 인수·실기기 검증 매트릭스",
        ["WS-11"],
        "정식 음성 명령은 Draft 명세로 고정하고, 실제 기기·현장·모델·접근성 시험은 실행 계약만 준비해 결과를 과장하지 않습니다.",
    )
    text += """

## Draft와 실행 증거의 구분

이 문서에서 음성 명령·의도 매핑만 Draft 명세입니다. 아래 실기기·현장·모델·접근성·E2E 항목은 모두 실행 전입니다. Web/PWA 시험은 현재 제품 범위 밖이라 비활성입니다.
"""
    text += _artifact_contract("WS-11", by_code["WS-11"], policy, decisions, status="DRAFT", canonical_path=WS_ACCEPTANCE_PATH)
    text += WS11_DETAIL
    text += """

<a id="ws-11-scope-end"></a>

## Planned / NOT_RUN 실행 항목
"""
    for code in [item for item in WS_ACCEPTANCE_CODES if item != "WS-11"]:
        text += _planned_contract(code, by_code[code], PLANNED_PROTOCOLS[code], policy, decisions, WS_ACCEPTANCE_PATH)
    text += """

## 실행 증거 연결

정본 실행 상태는 [registers/acceptance-evidence.json](registers/acceptance-evidence.json)에서 관리합니다. 실제 원본 영상·음성·정확 위치·참여자 정보는 Git에 넣지 않고 편집본 또는 외부 object ID·hash·권한·보존기한만 연결합니다. 서로 다른 APK·model·config·server 결과를 하나의 통과 근거로 섞지 않습니다.
"""
    return text


def _walksafe_field_document(policy: dict[str, Any], decisions: dict[str, Any], catalog: dict[str, Any]) -> str:
    by_code = _catalog_by_code(catalog)
    text = _header(
        "WalkSafe 현장시험 안전·외부기록 계획",
        [],
        "사람이 참여하는 시험 전에 필요한 안전계획과 외부 서명 원본의 기록 형식을 준비합니다. 참여자나 서명이 이미 있다는 뜻은 아닙니다.",
    )
    text += """

## 현재 상태

현장시험 참여자 동의·안전계획은 `Planned / NOT_RUN`입니다. 실제 참여자 0명, 서명 원본 0건, 승인된 현장시험 회차 0건입니다. 이 저장소에는 이름·연락처·서명·영상·음성·정확 위치 원본을 넣지 않습니다.
"""
    text += _planned_contract("WS-21", by_code["WS-21"], PLANNED_PROTOCOLS["WS-21"], policy, decisions, WS_FIELD_PATH)
    text += """

### 시험 전 안전계획 template

1. 시험 ID, 목적, 범위와 이름 붙인 Android APK·model·config·server를 적습니다.
2. 참여 기준·제외 조건과 사용자가 이해할 수 있는 설명 방식을 적습니다.
3. 경로·시간·날씨·차량·공사·장착 위험을 평가하고 더 안전한 통제환경부터 시작합니다.
4. 안전요원·관찰자·연락수단, 즉시 중단 권한, 응급·귀가 절차를 정합니다.
5. 위험상황, 기기과열, 안내지연, 위치 불신, 참여자 요청을 즉시 중단 조건으로 둡니다.
6. 수집 데이터·촬영·보존·삭제·철회 효과와 보상·문의 경로를 설명합니다.
7. 내부 계획 승인과 참여자별 서명 동의를 서로 다른 record로 관리합니다.

정본 색인은 [registers/field-test-external-record-index.json](registers/field-test-external-record-index.json)입니다. 외부 원본을 받기 전에는 signer·signed_at·SHA-256을 채우지 않습니다.
"""
    return text


def _register_metadata(register_id: str, title: str, artifact_codes: list[str]) -> dict[str, Any]:
    return {
        "register_id": register_id,
        "title": title,
        "version": VERSION,
        "as_of": AS_OF,
        "lifecycle_status": "DRAFT",
        "approval_status": "NOT_APPROVED",
        "release_status": RELEASE_STATUS,
        "artifact_type_ids": artifact_codes,
        "source_policy_baseline": BASELINE_ID,
        "generated_by": _rel(GENERATOR_PATH),
    }


def _security_risk_register() -> dict[str, Any]:
    risks = [
        {
            "risk_id": "SR-001",
            "title": "무가림 원본의 유출·목적 외 접근",
            "asset": "영상·음성·정확 위치·센서·신고 원본",
            "threat": "계정·저장소·백업 접근 탈취 또는 내부 오용",
            "likelihood": "MEDIUM",
            "impact": "CRITICAL",
            "inherent_rating": "CRITICAL",
            "controls": ["최초 동의", "전송·저장 암호화", "키 분리", "최소권한", "접근·복사·삭제 감사", "유한 보존"],
            "verification_refs": ["GATE-RAW-COLLECTION-RELEASE-REVIEW", "SEC-10", "SEC-12", "SEC-14"],
            "owner_role": "보안·개인정보책임자",
            "status": "OPEN",
            "residual_rating": "NOT_ASSESSED",
            "acceptance_ref": None,
        },
        {
            "risk_id": "SR-002",
            "title": "단일 관리자 휴대전화 분실·복구 실패",
            "asset": "관리자 세션·권한·복구자료·고위험 작업",
            "threat": "기기 분실 또는 복구수단 동시 상실",
            "likelihood": "MEDIUM",
            "impact": "HIGH",
            "inherent_rating": "HIGH",
            "controls": ["MFA/패스키", "외부 복구코드·보안키", "원격 세션 폐기", "고위험 작업 동결"],
            "verification_refs": ["GATE-SINGLE-ADMIN-RECOVERY-DRILL"],
            "owner_role": "프로젝트책임자",
            "status": "OPEN",
            "residual_rating": "NOT_ASSESSED",
            "acceptance_ref": None,
        },
        {
            "risk_id": "SR-003",
            "title": "신고 중복·스팸·관리자 오판",
            "asset": "자동·수동 신고, 기관 전달, 사용자 상태",
            "threat": "반복·조작 신고 또는 근거 없는 승인·기각",
            "likelihood": "MEDIUM",
            "impact": "HIGH",
            "inherent_rating": "HIGH",
            "controls": ["고정 신고번호", "idempotency", "rate limit", "유사도 검수", "사유·actor 감사", "정정·항소"],
            "verification_refs": ["WS-02", "WS-03", "WS-19", "WS-20"],
            "owner_role": "보안·개인정보책임자",
            "status": "OPEN",
            "residual_rating": "NOT_ASSESSED",
            "acceptance_ref": None,
        },
        {
            "risk_id": "SR-004",
            "title": "휴대전화 대기자료 포화·유실",
            "asset": "미전송 신고·원본·동의·삭제·보안기록",
            "threat": "바이트 상한 미정 또는 잘못된 삭제순서",
            "likelihood": "HIGH",
            "impact": "HIGH",
            "inherent_rating": "HIGH",
            "controls": ["우선 삭제순서", "신고·권리기록 우선보호", "새 자료 생성 보류", "실시간 안전기능 자원 분리"],
            "verification_refs": ["GATE-PHONE-QUEUE-BYTE-LIMIT", "GATE-SERVER-CAPACITY-STATE-CONTRACT"],
            "owner_role": "기술책임자",
            "status": "OPEN",
            "residual_rating": "NOT_ASSESSED",
            "acceptance_ref": None,
        },
        {
            "risk_id": "SR-005",
            "title": "TMAP 장애·오래된 경로 안내",
            "asset": "경로·회전·도착·이탈 상태",
            "threat": "timeout·quota·GPS 불신에서 오래된 안내 지속",
            "likelihood": "MEDIUM",
            "impact": "CRITICAL",
            "inherent_rating": "CRITICAL",
            "controls": ["최초 경로 로컬 저장", "연속 이탈 판정", "의심 시 회전안내 중지", "사용자 선택 뒤만 재호출"],
            "verification_refs": ["WS-06", "WS-07", "WS-18", "WS-20"],
            "owner_role": "접근성·안전책임자",
            "status": "OPEN",
            "residual_rating": "NOT_ASSESSED",
            "acceptance_ref": None,
        },
        {
            "risk_id": "SR-006",
            "title": "FP-035 이동통신망 정규화 지시의 묶음 승인 대기",
            "asset": "일반 활동원본 전송 정책·동의·구현",
            "threat": "포착된 정규화 지시와 다르거나 묶음 승인 전에 확정된 이동통신망 전송 구현",
            "likelihood": "MEDIUM",
            "impact": "HIGH",
            "inherent_rating": "HIGH",
            "controls": [
                "CR-0002 정규화 지시 포착·묶음 승인 대기",
                "보행 중 일반 활동원본 전송 금지",
                "정지 뒤 명시 선택 사용자만 이동통신망 허용",
                "미선택 사용자는 Wi-Fi만 허용",
                "묶음 승인 전 관련 정식 시험 NOT_RUN",
            ],
            "normalized_policy": FP035_NORMALIZED_POLICY,
            "verification_refs": ["CR-0002", "ISS-POLICY-FP035-NETWORK-001"],
            "owner_role": "제품책임자",
            "status": "OPEN",
            "residual_rating": "NOT_ASSESSED",
            "acceptance_ref": None,
        },
    ]
    return {
        "schema_version": "walksafe.security-risk-register.v1",
        "metadata": _register_metadata("WS-SECURITY-RISK-REGISTER-001", "WalkSafe 보안 위험대장", ["SEC-03"]),
        "rules": {
            "append_only": True,
            "closure_requires_evidence_or_approved_exception": True,
            "empty_or_open_rows_do_not_prove_security": True,
            "review_cadence": "사건 발생 즉시, 활성 프로젝트 동안 최소 월 1회",
        },
        "risks": risks,
        "summary": {
            "total": len(risks),
            "open": len(risks),
            "closed": 0,
            "accepted": 0,
            "release_status": RELEASE_STATUS,
        },
    }


def _vulnerability_register() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.vulnerability-register.v1",
        "metadata": _register_metadata("WS-VULNERABILITY-REGISTER-001", "WalkSafe 취약점·조치 관리대장", ["SEC-15"]),
        "field_contract": {
            "required": [
                "finding_id", "source_evidence_id", "affected_generation", "component", "severity", "impact",
                "reproduction_ref", "owner_role", "due_date", "status", "remediation_ref", "retest_evidence_id",
            ],
            "allowed_statuses": ["OPEN", "TRIAGED", "IN_REMEDIATION", "READY_FOR_RETEST", "CLOSED", "ACCEPTED_WITH_SEC_16"],
            "closure_rule": "재검증 evidence 또는 승인된 SEC-16 예외 없이는 CLOSED 금지",
        },
        "findings": [],
        "summary": {
            "registered_finding_count": 0,
            "meaning": "정식 scan·침투시험 결과가 아직 수입되지 않았으며 취약점 부재를 뜻하지 않음",
        },
    }


def _exception_register() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.security-exception-register.v1",
        "metadata": _register_metadata("WS-SECURITY-EXCEPTION-REGISTER-001", "WalkSafe 위험 수용·보안 예외 기록", ["SEC-16"]),
        "field_contract": {
            "required": [
                "exception_id", "risk_or_finding_refs", "scope", "business_reason", "residual_risk", "compensating_controls",
                "owner_role", "reviewer_identity", "approver_identity", "approved_at", "expires_at", "exit_condition", "status",
            ],
            "allowed_statuses": ["PROPOSED", "IN_REVIEW", "APPROVED", "REJECTED", "EXPIRED", "CLOSED"],
            "no_auto_renewal": True,
        },
        "exceptions": [],
        "summary": {"approved_exception_count": 0, "waived_gate_count": 0},
    }


def _security_evidence_register(catalog: dict[str, Any]) -> dict[str, Any]:
    by_code = _catalog_by_code(catalog)
    items = []
    for code in SEC_VERIFICATION_CODES:
        protocol = PLANNED_PROTOCOLS[code]
        items.append(
            {
                "artifact_type_id": code,
                "title": by_code[code]["title"],
                "evidence_instance_id": None,
                "lifecycle_status": "PLANNED",
                "execution_status": "NOT_RUN",
                "activation_condition": protocol["activation"],
                "input_generation_id": None,
                "tool_or_examiner": None,
                "executed_at": None,
                "result": None,
                "external_original_record_id": None,
                "sha256": None,
                "completion_criteria": protocol["pass"],
                "blocker": protocol["blocker"],
            }
        )
    return {
        "schema_version": "walksafe.security-verification-evidence-register.v1",
        "metadata": _register_metadata("WS-SECURITY-VERIFICATION-EVIDENCE-001", "WalkSafe 보안검증 증거 원장", []),
        "planned_artifact_type_ids": SEC_VERIFICATION_CODES,
        "raw_evidence_policy": "Git에는 원본 report를 저장하지 않고 통제 저장소 ID·hash·최소 판정만 기록",
        "items": items,
        "summary": {"planned": 5, "not_run": 5, "passed": 0, "failed": 0, "waived": 0},
    }


def _walksafe_acceptance_register(catalog: dict[str, Any]) -> dict[str, Any]:
    by_code = _catalog_by_code(catalog)
    codes = [code for code in WS_ACCEPTANCE_CODES if code != "WS-11"]
    items = []
    for code in codes:
        protocol = PLANNED_PROTOCOLS[code]
        activation_status = "NOT_ACTIVE_CURRENT_BASELINE" if code == "WS-16" else "ACTIVE_OR_PENDING_EXECUTION"
        items.append(
            {
                "artifact_type_id": code,
                "title": by_code[code]["title"],
                "evidence_instance_id": None,
                "lifecycle_status": "PLANNED",
                "execution_status": "NOT_RUN",
                "activation_status": activation_status,
                "activation_condition": protocol["activation"],
                "generation": {"apk_sha256": None, "model_sha256": None, "config_sha256": None, "server_manifest_sha256": None},
                "device_and_environment": None,
                "executed_at": None,
                "result": None,
                "external_original_record_id": None,
                "sha256": None,
                "completion_criteria": protocol["pass"],
                "blocker": protocol["blocker"],
            }
        )
    return {
        "schema_version": "walksafe.ws-acceptance-evidence-register.v1",
        "metadata": _register_metadata("WS-ACCEPTANCE-EVIDENCE-001", "WalkSafe 실기기·현장·접근성 증거 원장", []),
        "planned_artifact_type_ids": codes,
        "scope_rule": "Android 사용자 앱과 별도 Android 관리자 앱이 현행 범위이며 Web/PWA는 WS-16 재승인 전 비활성",
        "sensitive_evidence_rule": "영상·음성·정확 위치·참여자 원본은 Git 밖에 저장하고 Git에는 외부 ID·SHA-256·접근등급·보존기한만 기록",
        "items": items,
        "summary": {
            "planned": len(items),
            "not_run": len(items),
            "passed": 0,
            "failed": 0,
            "inactive_current_baseline": 1,
            "release_status": RELEASE_STATUS,
        },
    }


def _field_record_index() -> dict[str, Any]:
    return {
        "schema_version": "walksafe.field-test-external-record-index.v1",
        "metadata": _register_metadata("WS-FIELD-TEST-EXTERNAL-RECORD-INDEX-001", "WalkSafe 현장시험 외부기록 색인", []),
        "planned_artifact_type_ids": ["WS-21"],
        "execution_status": "NOT_RUN",
        "storage_policy": {
            "raw_personal_data_in_git": False,
            "external_original_required": True,
            "git_fields_only": [
                "test_plan_id", "plan_version", "plan_approval_ref", "participant_pseudonym_id",
                "external_record_id", "signer_role", "signed_at", "sha256", "retention_until", "withdrawal_status",
            ],
        },
        "plan_instances": [],
        "participant_consent_records": [],
        "summary": {
            "approved_plan_count": 0,
            "participant_count": 0,
            "signed_consent_count": 0,
            "field_session_count": 0,
        },
    }


def _source_bindings() -> dict[str, dict[str, str]]:
    return {
        "approved_policy_document": _binding(POLICY_PATH),
        "policy_approval_record": _binding(POLICY_APPROVAL_PATH),
        "policy_baseline_manifest": _binding(POLICY_MANIFEST_PATH),
        "aligned_decision_register": _binding(ALIGNED_DECISIONS_PATH),
        "artifact_catalog": _binding(CATALOG_PATH),
        "fp035_change_request_register": _binding(CHANGE_REQUESTS_PATH),
        "fp035_correction_candidate": _binding(FP035_CORRECTION_CANDIDATE_PATH),
        "w9_runtime_model_config": _binding(W9_RUNTIME_CONFIG_PATH),
        "w9_model_register": _binding(W9_MODEL_REGISTER_PATH),
        "w9_navigation_policy": _binding(W9_NAVIGATION_POLICY_PATH),
        "generator": _binding(GENERATOR_PATH),
    }


def _w9_contracts(runtime_config: dict[str, Any], model_register: dict[str, Any]) -> dict[str, Any]:
    runtime_model = runtime_config["models"]["unified_walksafe"]
    registered_model = next(
        row for row in model_register["models"]
        if row["runtime_model_id"] == "unified_walksafe"
    )
    return {
        "WS-08": {
            "lifecycle_status": "DRAFT",
            "runtime_model_id": "unified_walksafe",
            "class_order": W9_UNIFIED_CLASS_ORDER,
            "class_count": 13,
            "class_analysis": WS08_CLASS_ANALYSIS,
            "quantitative_evaluation_status": "NOT_RUN",
            "android_device_safety_validation_status": "NOT_RUN",
            "approval_status": "NOT_APPROVED",
            "release_status": RELEASE_STATUS,
        },
        "WS-10": {
            "lifecycle_status": "PLANNED",
            "execution_status": "NOT_RUN",
            "gap_status": "INTERNAL_GAP",
            "source_model": {
                "path": registered_model["source_artifact"]["path"],
                "sha256": registered_model["source_artifact"]["sha256"],
            },
            "tflite_model": {
                "path": registered_model["android_artifact"]["path"],
                "sha256": registered_model["android_artifact"]["sha256"],
            },
            "runtime_config_artifact_sha256": runtime_model["artifact_sha256"],
            "fixed_same_input_pt_tflite_comparison": {
                "status": "NOT_RUN",
                "dataset_snapshot_sha256": None,
                "preprocessing_contract_sha256": None,
                "sample_results": None,
                "result": None,
            },
            "tolerance": {
                "status": "NOT_ESTABLISHED",
                "value": None,
                "approval_ref": None,
            },
            "completion_eligible": False,
            "release_status": RELEASE_STATUS,
        },
        "WS-18": {
            "lifecycle_status": "DRAFT",
            "endpoints": WS18_ENDPOINT_CONTRACTS,
            "provider_timeout_seconds": 4.0,
            "error_taxonomy": WS18_ERROR_TAXONOMY,
            "automatic_retry_budget": 0,
            "cache_reuse_for_new_search_or_route": "PROHIBITED",
            "stale_route_guidance": "PROHIBITED",
            "quota_contracts": WS18_QUOTA_CONTRACTS,
            "planned_tests": WS18_PLANNED_TESTS,
            "live_tmap_status": "NOT_RUN",
            "quota_validation_status": "NOT_RUN",
            "deployment_status": "NOT_RUN",
            "provider_exit_status": "NOT_RUN",
            "alternate_provider_validation_status": "NOT_RUN",
            "release_status": RELEASE_STATUS,
        },
    }


def _build_outputs() -> dict[Path, bytes]:
    (
        policy,
        _baseline_manifest,
        decisions,
        catalog,
        _change_requests,
        runtime_config,
        model_register,
        _navigation_policy,
    ) = _validate_inputs()
    outputs: dict[Path, bytes] = {
        SEC_PLAN_PATH: _md_bytes(_security_plan_document(policy, decisions, catalog)),
        SEC_VERIFICATION_PATH: _md_bytes(_security_verification_document(policy, decisions, catalog)),
        SEC_RESPONSE_PATH: _md_bytes(_security_response_document(policy, decisions, catalog)),
        SEC_RISK_REGISTER_PATH: _json_bytes(_security_risk_register()),
        SEC_VULNERABILITY_REGISTER_PATH: _json_bytes(_vulnerability_register()),
        SEC_EXCEPTION_REGISTER_PATH: _json_bytes(_exception_register()),
        SEC_EVIDENCE_REGISTER_PATH: _json_bytes(_security_evidence_register(catalog)),
        WS_SAFETY_PATH: _md_bytes(_walksafe_safety_document(policy, decisions, catalog)),
        WS_ACCEPTANCE_PATH: _md_bytes(_walksafe_acceptance_document(policy, decisions, catalog)),
        WS_FIELD_PATH: _md_bytes(_walksafe_field_document(policy, decisions, catalog)),
        WS_ACCEPTANCE_REGISTER_PATH: _json_bytes(_walksafe_acceptance_register(catalog)),
        WS_FIELD_INDEX_PATH: _json_bytes(_field_record_index()),
    }
    file_codes = {
        SEC_PLAN_PATH: SEC_PLAN_CODES,
        SEC_VERIFICATION_PATH: [],
        SEC_RESPONSE_PATH: SEC_RESPONSE_CODES,
        SEC_RISK_REGISTER_PATH: [],
        SEC_VULNERABILITY_REGISTER_PATH: [],
        SEC_EXCEPTION_REGISTER_PATH: [],
        SEC_EVIDENCE_REGISTER_PATH: [],
        WS_SAFETY_PATH: WS_SAFETY_CODES,
        WS_ACCEPTANCE_PATH: ["WS-11"],
        WS_FIELD_PATH: [],
        WS_ACCEPTANCE_REGISTER_PATH: [],
        WS_FIELD_INDEX_PATH: [],
    }
    generated_files = [
        {
            "path": _rel(path),
            "sha256": _sha_bytes(content),
            "byte_length": len(content),
            "artifact_type_ids": list(file_codes[path]),
        }
        for path, content in sorted(outputs.items(), key=lambda item: _rel(item[0]))
    ]
    source_bindings = _source_bindings()
    manifest: dict[str, Any] = {
        "schema_version": "walksafe.formal-sec-ws-draft-manifest.v1",
        "metadata": {
            "manifest_id": "WS-FORMAL-SEC-WS-DRAFT-20260721-001",
            "title": "WalkSafe SEC·WS 정식 산출물 Draft manifest",
            "version": VERSION,
            "controlled_revision": 1,
            "as_of": AS_OF,
            "lifecycle_status": "DRAFT",
            "approval_status": "NOT_APPROVED",
            "release_status": RELEASE_STATUS,
            "artifact_type_ids": MATERIALIZED_CODES,
            "source_policy_baseline": BASELINE_ID,
            "generated_by": _rel(GENERATOR_PATH),
        },
        "scope_artifact_type_ids": SCOPE_CODES,
        "materialized_artifact_type_ids": MATERIALIZED_CODES,
        "planned_artifact_type_ids": PLANNED_CODES,
        "source_bindings": source_bindings,
        "source_binding_sha256": _object_sha(source_bindings),
        "w9_contracts": _w9_contracts(runtime_config, model_register),
        "coverage": {
            "SEC": {"expected": 19, "materialized_draft": 14, "planned_not_run": 5},
            "WS": {"expected": 22, "materialized_draft": 10, "planned_not_run": 12},
            "bundle_count": 6,
            "missing_artifact_type_ids": [],
            "duplicate_artifact_type_ids": [],
        },
        "generated_files": generated_files,
        "evidence_execution_summary": {
            "planned_not_run_artifact_count": len(PLANNED_CODES),
            "executed_evidence_artifact_count": 0,
            "passed_evidence_artifact_count": 0,
            "external_original_record_count": 0,
            "waived_evidence_artifact_count": 0,
        },
        "remaining_gates": [
            {"id": gate["id"], "status": "NOT_RUN", "waived": False}
            for gate in policy["remaining_gates"]
        ],
        "open_change_requests": [
            {
                "change_request_id": "CR-0002",
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
                "approval_boundary": "사용자 정규화 지시는 포착했으나 새 산출물 묶음 승인 전이므로 관련 구현 확정과 정식 시험은 대기한다.",
            }
        ],
        "scope_boundary": {
            "current_products": ["Android 사용자 앱", "별도 비공개 Android 관리자 앱"],
            "web_pwa_status": "LEGACY_NOT_ACTIVE; WS-16 requires separate reapproval",
            "raw_original_collection_policy": "APPROVED_POLICY; release independent review NOT_RUN",
            "sensitive_raw_data_in_git": False,
        },
        "authorization_boundary": {
            "policy_baseline_status": "BASELINED",
            "formal_deliverable_lifecycle_status": "DRAFT_OR_PLANNED_AS_DECLARED",
            "formal_deliverables_approved": False,
            "security_verification_completion_claimed": False,
            "field_or_device_test_completion_claimed": False,
            "external_consent_or_pentest_claimed": False,
            "remaining_gates_waived": False,
            "release_status": RELEASE_STATUS,
        },
    }
    manifest["manifest_content_sha256"] = _object_sha(manifest)
    outputs[MANIFEST_PATH] = _json_bytes(manifest)
    return outputs


def _validate_outputs(outputs: dict[Path, bytes]) -> None:
    _require(len(SCOPE_CODES) == len(set(SCOPE_CODES)) == 41, "scope codes are not unique")
    _require(len(MATERIALIZED_CODES) == len(set(MATERIALIZED_CODES)) == 24, "materialized codes are not unique")
    _require(len(PLANNED_CODES) == len(set(PLANNED_CODES)) == 17, "planned codes are not unique")
    _require(set(MATERIALIZED_CODES).isdisjoint(PLANNED_CODES), "materialized and planned sets overlap")
    _require(set(MATERIALIZED_CODES).union(PLANNED_CODES) == set(SCOPE_CODES), "scope union differs")

    documents = {
        SEC_PLAN_PATH: SEC_PLAN_CODES,
        SEC_VERIFICATION_PATH: SEC_VERIFICATION_CODES,
        SEC_RESPONSE_PATH: SEC_RESPONSE_CODES,
        WS_SAFETY_PATH: WS_SAFETY_CODES,
        WS_ACCEPTANCE_PATH: WS_ACCEPTANCE_CODES,
        WS_FIELD_PATH: WS_FIELD_CODES,
    }
    for path, codes in documents.items():
        text = outputs[path].decode("utf-8")
        for code in codes:
            _require(f'id="{code.lower()}"' in text, f"artifact anchor missing: {code}")
        _require("NOT_ELIGIBLE" in text and "NOT_RUN" in text, f"release boundary missing: {_rel(path)}")

    for path, draft_codes in {
        SEC_PLAN_PATH: SEC_PLAN_CODES,
        SEC_VERIFICATION_PATH: [],
        SEC_RESPONSE_PATH: SEC_RESPONSE_CODES,
        WS_SAFETY_PATH: WS_SAFETY_CODES,
        WS_ACCEPTANCE_PATH: ["WS-11"],
        WS_FIELD_PATH: [],
    }.items():
        first_sixteen = "\n".join(outputs[path].decode("utf-8").splitlines()[:16])
        declared_codes = set(re.findall(r"(?:SEC|WS)-\d{2}", first_sixteen))
        _require(declared_codes == set(draft_codes), f"top-of-document Draft declaration differs: {_rel(path)}")

    verification = json.loads(outputs[SEC_EVIDENCE_REGISTER_PATH])
    _require(verification["summary"] == {"planned": 5, "not_run": 5, "passed": 0, "failed": 0, "waived": 0}, "security evidence state differs")
    _require(all(item["execution_status"] == "NOT_RUN" and item["result"] is None for item in verification["items"]), "security evidence is overstated")

    acceptance = json.loads(outputs[WS_ACCEPTANCE_REGISTER_PATH])
    _require(len(acceptance["items"]) == 11, "WS planned evidence count differs")
    _require(all(item["execution_status"] == "NOT_RUN" and item["result"] is None for item in acceptance["items"]), "WS evidence is overstated")
    ws16 = next(item for item in acceptance["items"] if item["artifact_type_id"] == "WS-16")
    _require(ws16["activation_status"] == "NOT_ACTIVE_CURRENT_BASELINE", "WS-16 scope boundary differs")

    field = json.loads(outputs[WS_FIELD_INDEX_PATH])
    _require(field["summary"]["participant_count"] == 0 and field["summary"]["signed_consent_count"] == 0, "field evidence is overstated")
    _require(not field["storage_policy"]["raw_personal_data_in_git"], "raw personal data Git rule differs")

    policy_doc = outputs[SEC_PLAN_PATH].decode("utf-8")
    _require("미전송 휴대전화 원본 | 최대 30일" in policy_doc, "phone retention policy missing")
    _require("서버 일반·자동신고 원본 | 최대 180일" in policy_doc, "server retention policy missing")
    _require("승인 학습원본·라벨·고정 검증자료 | 승인 후 3년" in policy_doc, "training retention policy missing")
    _require(
        "FP-035" in policy_doc
        and "CR-0002" in policy_doc
        and "보행 중 전송하지 않습니다" in policy_doc
        and "명시적으로 선택한 사용자만 이동통신망" in policy_doc
        and "Wi-Fi에서만 전송" in policy_doc,
        "FP-035 normalized boundary missing",
    )

    walksafe_doc = outputs[WS_SAFETY_PATH].decode("utf-8")
    _require("보폭 | 남은 거리·도착·이탈의 진행량 보조" in walksafe_doc, "stride responsibility differs")
    _require("자동신고 후보는 최초 동의 뒤 후보별 음성·진동·푸시 알림과 개별 취소 없이" in walksafe_doc, "automatic report policy differs")
    _require("주변인의 얼굴·번호판·목소리도 가리지 않은 수집 원본" in walksafe_doc, "raw collection policy missing")
    for class_id in W9_UNIFIED_CLASS_ORDER:
        _require(f"| {class_id} |" in walksafe_doc, f"WS-08 class analysis is missing: {class_id}")
    for token in [
        "quantitative evaluation=`NOT_RUN`",
        "Android device safety validation=`NOT_RUN`",
        "/api/navigation/destinations/search",
        "/api/navigation/walking",
        "TMAP_TIMEOUT_SECONDS=4.0",
        "automatic retry budget",
        "cache 재사용은 `PROHIBITED`",
        "stale route·만료 경로",
        "live TMAP=`NOT_RUN`",
        "provider exit·alternate validation=`NOT_RUN`",
    ]:
        _require(token in walksafe_doc, f"W9 safety token is missing: {token}")

    manifest = json.loads(outputs[MANIFEST_PATH])
    _require(manifest["scope_artifact_type_ids"] == SCOPE_CODES, "manifest scope order differs")
    _require(manifest["materialized_artifact_type_ids"] == MATERIALIZED_CODES, "manifest materialized scope differs")
    _require(manifest["metadata"]["artifact_type_ids"] == MATERIALIZED_CODES, "manifest metadata scope differs")
    _require(manifest["planned_artifact_type_ids"] == PLANNED_CODES, "manifest planned scope differs")
    flattened = [code for row in manifest["generated_files"] for code in row["artifact_type_ids"]]
    _require(len(flattened) == len(set(flattened)) == 24, "generated file Draft claims differ")
    _require(set(flattened) == set(MATERIALIZED_CODES), "generated file Draft coverage differs")
    _require(all(gate["status"] == "NOT_RUN" and not gate["waived"] for gate in manifest["remaining_gates"]), "gate boundary differs")
    _require(manifest["authorization_boundary"]["release_status"] == RELEASE_STATUS, "manifest release boundary differs")
    _require(not manifest["scope_boundary"]["sensitive_raw_data_in_git"], "manifest sensitive data boundary differs")
    for key, path in {
        "w9_runtime_model_config": W9_RUNTIME_CONFIG_PATH,
        "w9_model_register": W9_MODEL_REGISTER_PATH,
        "w9_navigation_policy": W9_NAVIGATION_POLICY_PATH,
    }.items():
        _require(manifest["source_bindings"][key] == _binding(path), f"W9 source binding differs: {key}")
    w9 = manifest["w9_contracts"]
    ws08 = w9["WS-08"]
    _require(ws08["class_order"] == W9_UNIFIED_CLASS_ORDER and ws08["class_count"] == 13, "WS-08 exact13 differs")
    _require(ws08["class_analysis"] == WS08_CLASS_ANALYSIS, "WS-08 class analysis differs")
    required_analysis_fields = {
        "class_id", "context", "false_positive", "false_negative", "harm", "exposure",
        "detectability", "model_control", "policy_control", "ui_control", "fail_closed",
        "required_evidence", "residual_field_limitation_notice",
    }
    _require(
        all(set(row) == required_analysis_fields for row in ws08["class_analysis"]),
        "WS-08 analysis field contract differs",
    )
    _require(
        ws08["quantitative_evaluation_status"] == "NOT_RUN"
        and ws08["android_device_safety_validation_status"] == "NOT_RUN",
        "WS-08 execution boundary differs",
    )
    ws10 = w9["WS-10"]
    _require(
        ws10["lifecycle_status"] == "PLANNED"
        and ws10["execution_status"] == "NOT_RUN"
        and ws10["gap_status"] == "INTERNAL_GAP"
        and ws10["fixed_same_input_pt_tflite_comparison"]["status"] == "NOT_RUN"
        and ws10["tolerance"]["status"] == "NOT_ESTABLISHED"
        and ws10["completion_eligible"] is False,
        "WS-10 gap boundary differs",
    )
    _require(ws10["source_model"]["sha256"] != ws10["tflite_model"]["sha256"], "WS-10 model identities collapsed")
    ws18 = w9["WS-18"]
    _require(ws18["endpoints"] == WS18_ENDPOINT_CONTRACTS, "WS-18 endpoint contract differs")
    _require(ws18["provider_timeout_seconds"] == 4.0, "WS-18 timeout differs")
    _require(ws18["error_taxonomy"] == WS18_ERROR_TAXONOMY, "WS-18 error taxonomy differs")
    _require(
        ws18["automatic_retry_budget"] == 0
        and ws18["cache_reuse_for_new_search_or_route"] == "PROHIBITED"
        and ws18["stale_route_guidance"] == "PROHIBITED",
        "WS-18 retry/cache/stale boundary differs",
    )
    _require(
        all(row["status"] == "NOT_RUN" for row in ws18["planned_tests"])
        and ws18["live_tmap_status"] == "NOT_RUN"
        and ws18["quota_validation_status"] == "NOT_RUN"
        and ws18["deployment_status"] == "NOT_RUN"
        and ws18["provider_exit_status"] == "NOT_RUN"
        and ws18["alternate_provider_validation_status"] == "NOT_RUN",
        "WS-18 execution boundary differs",
    )
    _require(manifest["open_change_requests"][0]["change_request_id"] == "CR-0002", "FP-035 change request missing")
    _require(
        manifest["open_change_requests"][0]["status"] == FP035_NORMALIZATION_STATUS
        and manifest["open_change_requests"][0]["owner_clarification_required"] is False
        and manifest["open_change_requests"][0]["normalized_policy"] == FP035_NORMALIZED_POLICY
        and manifest["open_change_requests"][0]["related_test_status"] == "NOT_RUN"
        and manifest["open_change_requests"][0]["correction_candidate_binding"]
        == _binding(FP035_CORRECTION_CANDIDATE_PATH)
        and manifest["open_change_requests"][0]["correction_candidate_approval_status"]
        == "NOT_APPROVED"
        and manifest["open_change_requests"][0]["correction_candidate_effective_status"]
        == "NOT_EFFECTIVE"
        and manifest["open_change_requests"][0]["policy_effect_claimed"] is False,
        "FP-035 normalization directive boundary differs",
    )


def generate() -> dict[Path, bytes]:
    outputs = _build_outputs()
    _validate_outputs(outputs)
    for path, content in outputs.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return outputs


def check() -> dict[Path, bytes]:
    outputs = _build_outputs()
    _validate_outputs(outputs)
    for path, content in outputs.items():
        _require(path.is_file(), f"generated output is missing: {_rel(path)}")
        _require(path.read_bytes() == content, f"generated output is stale: {_rel(path)}")
    return outputs


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify committed outputs without rewriting")
    args = parser.parse_args()
    try:
        outputs = check() if args.check else generate()
    except (SecurityWalkSafeError, OSError, KeyError, TypeError, ValueError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    action = "verified" if args.check else "generated"
    print(f"{action} {len(outputs)} SEC/WS files; Draft=24, Planned/NOT_RUN=17, release={RELEASE_STATUS}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
