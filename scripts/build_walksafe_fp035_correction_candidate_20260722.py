#!/usr/bin/env python3
"""Build the non-effective FP-035 correction candidate for bundled approval.

The approved 1.0.0 policy bytes remain immutable.  This record captures the
owner's later clarification as a deterministic overlay and does not apply it.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
CONTROL_DIR = REPO_ROOT / "docs" / "control"
BASELINE_DIR = CONTROL_DIR / "baselines"
SOURCE_DIR = CONTROL_DIR / "decision-interview" / "source-records"

POLICY_PATH = (
    CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-comprehensive-draft.json"
)
APPROVAL_PATH = (
    BASELINE_DIR / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
)
MANIFEST_PATH = (
    BASELINE_DIR / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
)
RESOLUTION_PATH = (
    CONTROL_DIR
    / "decision-interview"
    / "walksafe-feature-policy-baseline-review-resolution-20260721-r001.json"
)
OWNER_ANSWER_PATH = (
    SOURCE_DIR / "walksafe-feature-policy-comprehensive-review-20260719-answers.json"
)
GOAL_INTAKE_PATH = (
    SOURCE_DIR / "walksafe-artifact-authoring-goal-20260722-r001.intake.json"
)

OUTPUT_PATH = (
    CONTROL_DIR
    / "decision-interview"
    / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"
)
SUMMARY_PATH = (
    CONTROL_DIR
    / "decision-interview"
    / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.md"
)

PREPARED_AT = "2026-07-22T12:48:00+09:00"
EXACT_RULE = (
    "일반 활동원본은 보행 중 전송하지 않는다. 사용자가 이동통신망 전송을 명시적으로 "
    "선택한 경우 보행이 정지한 뒤 허용된 이동통신망으로 전송할 수 있으며, 선택하지 "
    "않은 경우에는 Wi-Fi에서만 전송한다."
)
EXPECTED_GATE_IDS = {
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
}
EXPECTED_IMMUTABLE_HASHES = {
    POLICY_PATH: "5741749fd6f3a361eb5a43f3dbca48e6f5ec1a0f786e3169007327ceca5ce6ac",
    APPROVAL_PATH: "10ce10b104da2f625ebba51a9bf37dced2eab8007d2dd0519a67c268d387bbd5",
    MANIFEST_PATH: "7285111aafd3907a5e8e338c79ca42f9af2c0d7db9de0460512b66a6cfac90be",
    RESOLUTION_PATH: "d71cf9940cf8037226d3e095be26aa4ed34fe2c2feabe565c88f803eaab0dc50",
    OWNER_ANSWER_PATH: "ef840c33215767d210fc930a8087f09e5eda7b99af695ded5cd9d226d8ab5150",
    GOAL_INTAKE_PATH: "38234fb4cfd07f13044470a3c07d58c9d63532cc81946a616aaec47af2e58ed8",
}


class CorrectionCandidateError(RuntimeError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise CorrectionCandidateError(message)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _object_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _relative(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise CorrectionCandidateError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_strict_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_strict_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(
                CorrectionCandidateError(f"non-standard JSON number: {token}")
            ),
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise CorrectionCandidateError(f"cannot read strict JSON: {path}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def _source_binding(name: str, path: Path) -> dict[str, Any]:
    return {
        "name": name,
        "path": _relative(path),
        "byte_length": path.stat().st_size,
        "sha256": _sha256(path),
    }


def _validate_sources() -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    for path, expected in EXPECTED_IMMUTABLE_HASHES.items():
        _require(path.is_file(), f"required source is missing: {_relative(path)}")
        _require(_sha256(path) == expected, f"immutable source changed: {_relative(path)}")

    approval = load_strict_json(APPROVAL_PATH)
    manifest = load_strict_json(MANIFEST_PATH)
    intake = load_strict_json(GOAL_INTAKE_PATH)
    payload = approval["approved_baseline_payload"]
    target = payload["approval_target"]
    _require(payload["baseline_id"] == "PB-WALKSAFE-FEATURE-POLICY-1.0.0", "base policy ID differs")
    _require(payload["baseline_version"] == "1.0.0", "base policy version differs")
    _require(
        target["document_content_sha256"]
        == "e49ffe0ee9e59de0cec173cac9425d61aa78e0ccd65980ba568045a3975e0b28",
        "approved policy content fingerprint differs",
    )
    _require(
        target["decision_binding_sha256"]
        == "16064cabf95dc16109bfe315032905a12881cab40cf7730e97d8171d15476538",
        "approved decision fingerprint differs",
    )
    _require(intake["fp035_normalization"]["statement"] == EXACT_RULE, "owner FP-035 rule differs")
    gates = manifest["remaining_gates"]
    _require({gate["id"] for gate in gates} == EXPECTED_GATE_IDS, "gate set differs")
    _require({gate["status"] for gate in gates} == {"NOT_RUN"}, "a gate is no longer NOT_RUN")
    return approval, manifest, intake


def build_candidate() -> dict[str, Any]:
    approval, manifest, intake = _validate_sources()
    gates = manifest["remaining_gates"]
    sources = [
        _source_binding("approved_policy_payload", POLICY_PATH),
        _source_binding("approved_policy_record", APPROVAL_PATH),
        _source_binding("approved_policy_manifest", MANIFEST_PATH),
        _source_binding("approved_review_resolution", RESOLUTION_PATH),
        _source_binding("original_owner_answer_export", OWNER_ANSWER_PATH),
        _source_binding("authoring_goal_owner_directive_intake", GOAL_INTAKE_PATH),
        _source_binding("generator", GENERATOR_PATH),
    ]
    source_binding_sha256 = _object_sha256(sources)
    correction = {
        "change_request_id": "CR-0002",
        "change_control_refs": [
            "CR-0002",
            "ISS-POLICY-FP035-NETWORK-001",
            "RAID-011",
        ],
        "feature_id": "FP-035",
        "policy_clause_id": "FP-035-POLICY-001",
        "change_kind": "OWNER_CLARIFICATION_CORRECTION",
        "reason": (
            "SP-13의 '데이터'가 일반 수집 동의가 아니라 이동통신망 사용 선택을 뜻한다는 "
            "후속 설명을 반영해, Wi-Fi 전용으로 읽히는 기존 문장을 바로잡는다."
        ),
        "normative_rule": EXACT_RULE,
        "decision_branches": [
            {
                "condition": "보행 중",
                "result": "일반 활동원본을 어떤 망으로도 전송하지 않는다.",
            },
            {
                "condition": "보행 정지 후, 이동통신망 전송을 명시적으로 선택했고 현재 망이 허용됨",
                "result": "배터리·저장공간 등 다른 전송 조건도 충족하면 이동통신망 전송을 허용한다.",
            },
            {
                "condition": "보행 정지 후, 이동통신망 전송을 선택하지 않음",
                "result": "Wi-Fi에서만 전송한다.",
            },
            {
                "condition": "허용된 망이 없음",
                "result": "암호화 대기열에 보관하고 기존 보존·용량·삭제 정책을 적용한다.",
            },
        ],
        "overlay_interpretation": {
            "replaces_unconditional_wifi_only_reading": True,
            "unchanged_rules": [
                "보행 중 일반 활동원본 전송 금지",
                "명시적 선택 없이 이동통신망으로 자동 전환 금지",
                "전송 실패 시 암호화 대기와 보존·용량·삭제 정책 적용",
                "신고자료 우선 보호와 조각 무결성 확인",
            ],
            "base_document_remains_immutable": True,
        },
        "affected_artifact_codes": ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"],
        "required_related_records": [
            {
                "artifact_code": "REQ-16",
                "path": "docs/deliverables/03-requirements/rtm.json",
            },
            {
                "artifact_code": "REQ-18",
                "path": "docs/deliverables/03-requirements/requirement-change-log.json",
            },
            {
                "artifact_code": "DES-06",
                "path": "docs/deliverables/04-design/design-traceability-register.json",
            },
            {
                "artifact_code": "MGT-14",
                "path": "docs/deliverables/01-management/registers/raid.json",
            },
            {
                "artifact_code": "MGT-16",
                "path": "docs/deliverables/01-management/registers/change-requests.json",
            },
            {
                "artifact_code": "DOC-05",
                "path": "docs/deliverables/00-control/artifact-change-log.json",
            },
        ],
    }
    candidate: dict[str, Any] = {
        "schema_version": "walksafe.feature-policy-correction-candidate.v1",
        "metadata": {
            "candidate_id": "WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001",
            "candidate_version": "1.0.0",
            "controlled_revision": 1,
            "prepared_at": PREPARED_AT,
            "lifecycle_status": "READY_FOR_BUNDLED_APPROVAL",
            "approval_status": "NOT_APPROVED",
            "effective_status": "NOT_EFFECTIVE",
            "approver": None,
            "approved_at": None,
            "supersedes_candidate_id": None,
        },
        "source_bindings": sources,
        "source_binding_sha256": source_binding_sha256,
        "base_policy": {
            "baseline_id": approval["approved_baseline_payload"]["baseline_id"],
            "baseline_version": approval["approved_baseline_payload"]["baseline_version"],
            "document_id": approval["approved_baseline_payload"]["approval_target"]["document_id"],
            "document_version": approval["approved_baseline_payload"]["approval_target"]["document_version"],
            "document_content_sha256": approval["approved_baseline_payload"]["approval_target"]["document_content_sha256"],
            "decision_binding_sha256": approval["approved_baseline_payload"]["approval_target"]["decision_binding_sha256"],
            "bytes_modified_by_this_candidate": False,
        },
        "correction": correction,
        "correction_binding_sha256": _object_sha256(correction),
        "planned_effective_policy": {
            "baseline_id_after_explicit_approval": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "baseline_version_after_explicit_approval": "1.0.1",
            "composition": "PB-WALKSAFE-FEATURE-POLICY-1.0.0 + this exact correction overlay",
            "state_change_now": False,
            "must_be_approved_with_affected_artifacts": True,
        },
        "remaining_gates": gates,
        "authorization_boundary": {
            "owner_directive_captured": True,
            "new_product_question_required": False,
            "candidate_generation_is_approval": False,
            "policy_1_0_0_is_superseded_now": False,
            "affected_artifacts_are_approved_now": False,
            "implementation_conformance_assessed": False,
            "test_completion_claimed": False,
            "remaining_gates_are_waived": False,
            "release_status": "NOT_ELIGIBLE",
            "required_activation_event": "EXACT_NEW_BUNDLED_OWNER_APPROVAL_STATEMENT",
            "authoring_and_planning_allowed_before_activation": True,
            "mobile_network_branch_implementation_status": "FROZEN_PENDING_EXACT_BUNDLED_APPROVAL",
            "mobile_network_branch_formal_test_status": "NOT_RUN_BLOCKED_PENDING_EXACT_BUNDLED_APPROVAL",
        },
        "owner_directive_source": {
            "intake_record_id": intake["metadata"]["record_id"],
            "source_attachment_sha256": intake["metadata"]["source_sha256"],
            "exact_rule_sha256": hashlib.sha256(EXACT_RULE.encode("utf-8")).hexdigest(),
        },
    }
    candidate["candidate_content_sha256"] = _object_sha256(candidate)
    validate_candidate(candidate)
    return candidate


def validate_candidate(candidate: dict[str, Any]) -> None:
    _validate_sources()
    expected_hash = _object_sha256(
        {key: value for key, value in candidate.items() if key != "candidate_content_sha256"}
    )
    _require(candidate["candidate_content_sha256"] == expected_hash, "candidate content hash differs")
    _require(
        candidate["source_binding_sha256"] == _object_sha256(candidate["source_bindings"]),
        "source binding hash differs",
    )
    _require(
        candidate["correction_binding_sha256"] == _object_sha256(candidate["correction"]),
        "correction binding hash differs",
    )
    _require(candidate["correction"]["normative_rule"] == EXACT_RULE, "FP-035 rule differs")
    _require(
        candidate["correction"]["affected_artifact_codes"]
        == ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"],
        "affected artifact list differs",
    )
    _require(
        candidate["correction"]["change_control_refs"]
        == ["CR-0002", "ISS-POLICY-FP035-NETWORK-001", "RAID-011"],
        "change-control refs differ",
    )
    _require(
        {row["artifact_code"] for row in candidate["correction"]["required_related_records"]}
        == {"REQ-16", "REQ-18", "DES-06", "MGT-14", "MGT-16", "DOC-05"},
        "related change/trace record set differs",
    )
    _require(candidate["metadata"]["approval_status"] == "NOT_APPROVED", "candidate claims approval")
    _require(candidate["metadata"]["effective_status"] == "NOT_EFFECTIVE", "candidate claims effect")
    boundary = candidate["authorization_boundary"]
    for key in (
        "candidate_generation_is_approval",
        "policy_1_0_0_is_superseded_now",
        "affected_artifacts_are_approved_now",
        "implementation_conformance_assessed",
        "test_completion_claimed",
        "remaining_gates_are_waived",
    ):
        _require(boundary[key] is False, f"unsafe boundary differs: {key}")
    _require(boundary["release_status"] == "NOT_ELIGIBLE", "release boundary differs")
    _require(
        boundary["mobile_network_branch_implementation_status"]
        == "FROZEN_PENDING_EXACT_BUNDLED_APPROVAL",
        "pre-approval implementation blocker differs",
    )
    _require(
        boundary["mobile_network_branch_formal_test_status"]
        == "NOT_RUN_BLOCKED_PENDING_EXACT_BUNDLED_APPROVAL",
        "pre-approval test blocker differs",
    )
    _require({gate["id"] for gate in candidate["remaining_gates"]} == EXPECTED_GATE_IDS, "gate set differs")
    _require({gate["status"] for gate in candidate["remaining_gates"]} == {"NOT_RUN"}, "gate status differs")


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def _render_markdown(candidate: dict[str, Any], json_sha256: str) -> str:
    branches = "\n".join(
        f"- **{row['condition']}**: {row['result']}"
        for row in candidate["correction"]["decision_branches"]
    )
    affected = ", ".join(candidate["correction"]["affected_artifact_codes"])
    related = ", ".join(
        f"{row['artifact_code']} (`{row['path']}`)"
        for row in candidate["correction"]["required_related_records"]
    )
    gates = "\n".join(
        f"- `{gate['id']}` — {gate['title']}: `NOT_RUN`"
        for gate in candidate["remaining_gates"]
    )
    return f"""# WalkSafe FP-035 정책 정정 후보

> 후보 ID: `{candidate['metadata']['candidate_id']}`  
> 상태: **미승인·미효력**  
> JSON SHA-256: `{json_sha256}`

## 무엇을 바로잡는가

기존 답변에서 ‘데이터’는 수집 동의가 아니라 **이동통신망 전송 선택**을 뜻했습니다. 따라서 Wi-Fi만 가능한 것으로 읽히는 문장을 다음 하나의 규칙으로 바로잡습니다.

> {candidate['correction']['normative_rule']}

{branches}

## 영향 범위

- 직접 영향 산출물: {affected}
- 함께 갱신할 기록: {related}
- 승인되면 만들 정책 기준선: `PB-WALKSAFE-FEATURE-POLICY-1.0.1`

기존 정책 1.0.0 파일은 수정하거나 덮어쓰지 않았습니다. 이 후보는 뒤에서 만드는 산출물 일괄 승인문에 정확한 지문으로 함께 묶일 때만 효력이 생깁니다.

문서 작성·시험 계획 수립은 계속할 수 있지만, **일괄 승인 전에는 이동통신망 전송 분기의 정식 구현을 시작하지 않고 관련 정식 시험도 실행하지 않습니다.**

## 남아 있는 출시 gate

{gates}

정정 후보 작성은 구현 적합성·시험 완료·gate 면제·출시 승인이 아닙니다. 출시 상태는 `NOT_ELIGIBLE`입니다.
"""


def build_outputs() -> dict[Path, bytes]:
    candidate = build_candidate()
    json_bytes = _json_bytes(candidate)
    json_hash = hashlib.sha256(json_bytes).hexdigest()
    return {
        OUTPUT_PATH: json_bytes,
        SUMMARY_PATH: _render_markdown(candidate, json_hash).encode("utf-8"),
    }


def _write_or_check(outputs: dict[Path, bytes], *, check: bool) -> None:
    stale: list[str] = []
    for path, content in outputs.items():
        if check:
            if not path.is_file() or path.read_bytes() != content:
                stale.append(_relative(path))
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    if stale:
        raise CorrectionCandidateError("generated outputs are stale: " + ", ".join(stale))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        outputs = build_outputs()
        _write_or_check(outputs, check=args.check)
    except CorrectionCandidateError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    mode = "verified" if args.check else "generated"
    print(f"{mode}: FP-035 correction candidate; approval=NOT_APPROVED; release=NOT_ELIGIBLE")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
